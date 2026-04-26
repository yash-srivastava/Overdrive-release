package com.overdrive.app.surveillance;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.telemetry.TelemetryDataCollector;

import com.overdrive.app.camera.PanoramicCameraGpu;

import java.io.File;

/**
 * GpuSurveillancePipeline - Complete GPU Zero-Copy surveillance system.
 * 
 * Orchestrates all components of the GPU pipeline:
 * - PanoramicCameraGpu: Camera → GPU texture
 * - GpuMosaicRecorder: GPU composition → Encoder
 * - GpuDownscaler: GPU thumbnail → CPU
 * - SurveillanceEngineGpu: Motion detection & AI
 * - AdaptiveBitrateController: Quality optimization
 * 
 * Achieves <10% CPU usage through GPU zero-copy architecture.
 */
public class GpuSurveillancePipeline {
    private static final String TAG = "GpuPipeline";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Components
    private PanoramicCameraGpu camera;
    private GpuMosaicRecorder recorder;  // Single recorder for both modes
    private GpuDownscaler downscaler;
    private SurveillanceEngineGpu sentry;
    private HardwareEventRecorderGpu encoder;  // Single encoder for recording/surveillance
    private AdaptiveBitrateController bitrateController;
    
    // Streaming components (separate encoder - always available)
    private com.overdrive.app.streaming.GpuStreamScaler streamScaler;
    private HardwareEventRecorderGpu streamEncoder;
    private com.overdrive.app.streaming.WebSocketStreamServer wsStreamServer;
    private boolean streamingEnabled = false;
    
    // Telemetry overlay
    private TelemetryDataCollector telemetryCollector;
    private volatile boolean overlayEnabledConfig = false;
    
    // Mode tracking
    private enum Mode {
        IDLE,           // Nothing active
        NORMAL_RECORDING,   // User manually recording
        SURVEILLANCE    // Auto-recording on motion
    }
    private Mode currentMode = Mode.IDLE;
    
    // Configuration
    private final int cameraWidth;
    private final int cameraHeight;
    private final int encoderWidth;
    private final int encoderHeight;
    private final File eventOutputDir;
    private GpuPipelineConfig config;
    
    // State
    private boolean initialized = false;
    private boolean running = false;
    private boolean recordingMode = false;  // true = recording, false = viewing only
    
    // Deferred recording: stored when startRecording() is called before encoder is ready
    private volatile java.io.File pendingRecordingDir = null;
    private volatile String pendingRecordingPrefix = null;
    
    /**
     * Creates the GPU surveillance pipeline.
     * 
     * @param cameraWidth Camera width (typically 5120)
     * @param cameraHeight Camera height (typically 960)
     * @param eventOutputDir Directory for event recordings
     */
    public GpuSurveillancePipeline(int cameraWidth, int cameraHeight, File eventOutputDir) {
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
        this.encoderWidth = 2560;
        this.encoderHeight = 1920;
        this.eventOutputDir = eventOutputDir;
        this.config = new GpuPipelineConfig();
    }
    
    /**
     * Gets the configuration.
     */
    public GpuPipelineConfig getConfig() {
        return config;
    }
    
    /**
     * Sets the recording mode (Normal/Sentry).
     */
    public void setRecordingMode(GpuPipelineConfig.RecordingMode mode) {
        config.setRecordingMode(mode);
        
        // Apply to encoder - but DON'T override user's bitrate setting
        // Only change FPS (which requires encoder restart anyway)
        if (encoder != null) {
            // Use the user's configured bitrate, not the mode's default
            int userBitrate = config.getEffectiveBitrate();
            if (bitrateController != null) {
                bitrateController.setImmediateBitrate(userBitrate);
            }
            // Note: FPS is set during encoder initialization
            // Dynamic FPS change would require encoder restart
            logger.info(String.format("Recording mode: %s (using user bitrate=%d Mbps, mode default was %d Mbps)",
                    mode, userBitrate / 1_000_000, mode.bitrate / 1_000_000));
        }
    }
    
    /**
     * Sets the streaming quality (HQ/LQ).
     */
    public void setStreamingQuality(GpuPipelineConfig.StreamingQuality quality) {
        config.setStreamingQuality(quality);
        // Quality is saved — it will be applied on next stream start.
        // Don't restart the active stream to avoid disrupting the live view.
        logger.info(String.format("Streaming quality saved: %s (%dx%d @ %dfps)",
                quality, quality.width, quality.height, quality.fps));
    }
    
    /**
     * Applies a bitrate change to the encoder.
     * 
     * Reinitializes encoder immediately to ensure new bitrate is used.
     * 
     * @param bitrate New bitrate in bps
     */
    public void applyBitrateChange(int bitrate) {
        // Update config first
        config.setCustomBitrate(bitrate);
        
        if (encoder == null) {
            logger.info("Bitrate setting saved (encoder not initialized yet): " + (bitrate / 1_000_000) + " Mbps");
            return;
        }
        
        // Check if bitrate actually changed
        if (encoder.getBitrate() == bitrate) {
            logger.info("Bitrate already set to: " + (bitrate / 1_000_000) + " Mbps");
            return;
        }
        
        logger.info("Bitrate change requested: " + (bitrate / 1_000_000) + " Mbps - reinitializing encoder");
        
        boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
        boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
        boolean wasRecording = isRecording();
        
        try {
            // Stop current recording first if active
            if (wasRecording && recorder != null && recorder.isRecording()) {
                logger.info("Stopping recording for bitrate change");
                recorder.stopRecording();
                // Wait for encoder to finish writing
                Thread.sleep(500);
            }
            
            // Reinitialize encoder with new bitrate
            reinitializeEncoder();
            
            // Update bitrate controller
            if (bitrateController != null) {
                bitrateController.setImmediateBitrate(bitrate);
            }
            
            // Restart recording if it was active
            if (wasRecording) {
                if (wasSurveillance) {
                    logger.info("Restarting surveillance mode with new bitrate");
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    logger.info("Restarting normal recording with new bitrate");
                    startRecording();
                }
            }
            
            logger.info("Bitrate change applied successfully: " + (bitrate / 1_000_000) + " Mbps");
            
        } catch (Exception e) {
            logger.error("Failed to apply bitrate change: " + e.getMessage(), e);
            // Try to recover
            try {
                if (wasSurveillance) {
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    startRecording();
                }
            } catch (Exception e2) {
                logger.error("Failed to recover after bitrate change error", e2);
            }
        }
    }
    
    /**
     * Applies a codec change. Requires encoder restart.
     * 
     * @param codec New video codec
     */
    public void applyCodecChange(GpuPipelineConfig.VideoCodec codec) {
        // Store the new codec setting
        config.setVideoCodec(codec);
        
        // If encoder doesn't exist yet, just save the setting
        if (encoder == null) {
            logger.info("Codec changed to: " + codec.displayName + " - will apply when encoder initializes");
            return;
        }
        
        // Check if codec actually changed
        String currentCodec = encoder.getCodecMimeType();
        String newCodec = config.getCodecMimeType();
        if (currentCodec.equals(newCodec)) {
            logger.info("Codec already set to: " + codec.displayName);
            return;
        }
        
        logger.info("Codec change requested: " + codec.displayName + " - reinitializing encoder");
        
        boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
        boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
        boolean wasRecording = isRecording();
        
        try {
            // Stop current recording first if active
            if (wasRecording && recorder != null && recorder.isRecording()) {
                logger.info("Stopping recording for codec change");
                recorder.stopRecording();
                // Wait for encoder to finish writing
                Thread.sleep(500);
            }
            
            // Reinitialize encoder with new codec
            reinitializeEncoder();
            
            // Restart recording if it was active
            if (wasRecording) {
                if (wasSurveillance) {
                    logger.info("Restarting surveillance mode with new codec");
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    logger.info("Restarting normal recording with new codec");
                    startRecording();
                }
            }
            
            logger.info("Codec change applied successfully: " + codec.displayName);
            
        } catch (Exception e) {
            logger.error("Failed to apply codec change: " + e.getMessage(), e);
            // Try to recover by restarting what was running
            try {
                if (wasSurveillance) {
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    startRecording();
                }
            } catch (Exception e2) {
                logger.error("Failed to recover after codec change error", e2);
            }
        }
    }
    
    /**
     * Reinitializes the encoder with current config settings.
     * This is a synchronous operation that waits for completion.
     * 
     * SOTA: Properly synchronizes with GL thread to prevent EGL_BAD_SURFACE errors.
     */
    private void reinitializeEncoder() throws Exception {
        logger.info("Reinitializing encoder...");
        
        // SOTA: First, release recorder's encoder surface on GL thread
        // This prevents EGL_BAD_SURFACE errors when the encoder is released
        if (camera != null && camera.getGlHandler() != null && recorder != null) {
            final Object releaseLock = new Object();
            final boolean[] releaseDone = {false};
            
            camera.getGlHandler().post(() -> {
                try {
                    // Release recorder's surface (it will be recreated after new encoder is ready)
                    recorder.releaseEncoderSurface();
                    logger.info("Recorder encoder surface released on GL thread");
                } catch (Exception e) {
                    logger.warn("Error releasing recorder surface: " + e.getMessage());
                } finally {
                    synchronized (releaseLock) {
                        releaseDone[0] = true;
                        releaseLock.notify();
                    }
                }
            });
            
            // Wait for GL thread to release surface (max 1 second)
            synchronized (releaseLock) {
                if (!releaseDone[0]) {
                    releaseLock.wait(1000);
                }
            }
        }
        
        // Now safe to release old encoder
        if (encoder != null) {
            // Wait for any pending writes to complete
            if (encoder.isWritingToFile()) {
                logger.info("Waiting for encoder to finish writing...");
                encoder.flushAndClose();
                Thread.sleep(200);
            }
            encoder.release();
            encoder = null;
        }
        
        // Create new encoder with current config
        String codecMimeType = config.getCodecMimeType();
        int bitrate = config.getEffectiveBitrate();
        
        logger.info("Creating new encoder: " + 
            (codecMimeType.contains("hevc") ? "H.265" : "H.264") + 
            " @ " + (bitrate / 1_000_000) + " Mbps");
        
        encoder = new HardwareEventRecorderGpu(encoderWidth, encoderHeight, 15, bitrate, codecMimeType);
        encoder.init();
        
        // Reinitialize recorder with new encoder on GL thread
        if (camera != null && camera.getEglCore() != null) {
            final Object initLock = new Object();
            final boolean[] initDone = {false};
            final Exception[] initError = {null};
            
            camera.getGlHandler().post(() -> {
                try {
                    // Recreate recorder if needed
                    if (recorder == null) {
                        recorder = new GpuMosaicRecorder();
                    }
                    recorder.init(camera.getEglCore(), encoder);
                    logger.info("Recorder reinitialized on GL thread");
                } catch (Exception e) {
                    initError[0] = e;
                    logger.error("Failed to reinitialize recorder on GL thread", e);
                } finally {
                    synchronized (initLock) {
                        initDone[0] = true;
                        initLock.notify();
                    }
                }
            });
            
            // Wait for GL thread initialization (max 3 seconds)
            synchronized (initLock) {
                if (!initDone[0]) {
                    initLock.wait(3000);
                }
            }
            
            if (initError[0] != null) {
                throw initError[0];
            }
            
            if (!initDone[0]) {
                throw new RuntimeException("Encoder reinitialization timed out");
            }
        }
        
        // Update bitrate controller
        if (bitrateController != null) {
            bitrateController = new AdaptiveBitrateController(encoder, bitrate);
        }
        
        logger.info("Encoder reinitialized successfully: " + 
            (codecMimeType.contains("hevc") ? "H.265" : "H.264") + 
            " @ " + (bitrate / 1_000_000) + " Mbps");
    }
    
    /**
     * Initializes the complete GPU pipeline.
     * 
     * @throws Exception if initialization fails
     */
    public void init() throws Exception {
        init(null, null);
    }
    
    /**
     * Initializes the complete GPU pipeline with AssetManager for YOLO.
     * 
     * @param assetManager Android AssetManager for loading YOLO model (null = skip YOLO)
     * @throws Exception if initialization fails
     */
    public void init(android.content.res.AssetManager assetManager) throws Exception {
        init(assetManager, null);
    }
    
    /**
     * Initializes the complete GPU pipeline with Context for Java TFLite.
     * 
     * @param assetManager Android AssetManager (unused, kept for compatibility)
     * @param context Android Context for TFLite initialization
     * @throws Exception if initialization fails
     */
    public void init(android.content.res.AssetManager assetManager, android.content.Context context) throws Exception {
        if (initialized) {
            logger.warn("Already initialized");
            return;
        }
        
        logger.info("Initializing GPU surveillance pipeline...");
        
        // Ensure output directory exists
        if (!eventOutputDir.exists()) {
            eventOutputDir.mkdirs();
        }
        
        // SOTA: Release any stuck encoder resources before creating new one
        // This helps recover from previous crashes that left encoder in bad state
        if (encoder != null) {
            logger.info("Releasing previous encoder before reinit...");
            try {
                encoder.release();
            } catch (Exception e) {
                logger.warn("Error releasing previous encoder: " + e.getMessage());
            }
            encoder = null;
        }
        
        // 1. Create hardware encoder (shared by normal recording and surveillance)
        // Use config settings for bitrate and codec
        String codecMimeType = config.getCodecMimeType();
        int bitrate = config.getEffectiveBitrate();
        logger.info("Creating encoder with config: " + 
            (codecMimeType.contains("hevc") ? "H.265" : "H.264") + 
            " @ " + (bitrate / 1_000_000) + " Mbps");
        encoder = new HardwareEventRecorderGpu(encoderWidth, encoderHeight, 15, bitrate, codecMimeType);
        encoder.init();
        
        // 2. Create GPU mosaic recorder (shared)
        recorder = new GpuMosaicRecorder();
        // Note: recorder.init() will be called after EGL context is created by camera
        
        // Wire up telemetry collector to new recorder if available
        if (telemetryCollector != null) {
            recorder.setTelemetryCollector(telemetryCollector);
        }
        // Apply persisted overlay enabled state to new recorder
        recorder.setOverlayEnabled(overlayEnabledConfig);
        
        // 3. Create GPU downscaler
        downscaler = new GpuDownscaler();
        // Note: downscaler.init() will be called after EGL context is created by camera
        
        // 4. Create surveillance engine (uses shared recorder)
        sentry = new SurveillanceEngineGpu();
        sentry.init(eventOutputDir, downscaler, assetManager, context);  // Pass Context for Java TFLite
        sentry.setRecorder(recorder);  // Share recorder with normal recording
        
        // 4b. Load saved config from disk (if exists)
        try {
            SurveillanceConfigManager configManager = new SurveillanceConfigManager();
            if (configManager.configExists()) {
                SurveillanceConfig savedConfig = configManager.loadConfig();
                sentry.setConfig(savedConfig);
                logger.info("Loaded saved surveillance config");
            }
        } catch (Exception e) {
            logger.warn("Failed to load saved config, using defaults: " + e.getMessage());
        }
        
        // 5. Create camera (this creates EGL context)
        camera = new PanoramicCameraGpu(cameraWidth, cameraHeight);
        camera.setConsumers(recorder, downscaler, sentry);
        
        // Camera config: saved config → or first-launch probe (ID 0 then ID 1).
        // Both Seal (ID 1) and Atto 3 (ID 0) output a 4-camera strip at 5120x960 with surfaceMode 0.
        // CRITICAL: Don't cycle through cameras — the Atto 3 HAL needs the camera to stay
        // open for all 4 cameras to initialize in the strip.
        logger.info("Vehicle model: " + getVehicleModel());
        
        boolean configured = false;
        try {
            org.json.JSONObject cameraConfig = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            int savedId = cameraConfig != null ? cameraConfig.optInt("probedCameraId", -1) : -1;
            int savedMode = cameraConfig != null ? cameraConfig.optInt("probedSurfaceMode", -1) : -1;
            
            if (savedId >= 0 && savedMode >= 0) {
                // Use saved surface mode but ALWAYS start from ID 0 with probe enabled.
                // On some models (Seal U), the HAL needs camera ID 0 to be opened briefly
                // before ID 1 produces image data. Starting directly with saved ID 1
                // results in BLACK frames.
                logger.info("Saved camera config: id=" + savedId + ", surfaceMode=" + savedMode +
                    " — starting from id=0 with probe (HAL warm-up)");
                camera.setCameraId(0);
                camera.setCameraSurfaceMode(savedMode);
                camera.setAutoProbeCameras(true);
                // SOTA: Always register probe callback so config is re-persisted
                // if the saved config no longer works (e.g., after firmware update)
                camera.setCameraProbeCallback((cameraId, surfaceMode) -> {
                    logger.info("Probe found working camera: id=" + cameraId + ", surfaceMode=" + surfaceMode);
                    try {
                        org.json.JSONObject camCfg = new org.json.JSONObject();
                        camCfg.put("probedCameraId", cameraId);
                        camCfg.put("probedSurfaceMode", surfaceMode);
                        com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                        logger.info("Saved camera config for next launch");
                    } catch (Exception ex) {
                        logger.warn("Failed to save camera config: " + ex.getMessage());
                    }
                    // Check if there's a pending recording that was deferred
                    // Wait briefly for encoder to receive first frame format
                    new Thread(() -> {
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        checkPendingRecording();
                    }, "PendingRecCheck").start();
                });
                configured = true;
            }
        } catch (Exception e) {
            logger.warn("Failed to load saved camera config: " + e.getMessage());
        }
        
        if (!configured) {
            logger.info("No saved config — starting with id=0, surfaceMode=0 (probe enabled)");
            camera.setCameraId(0);
            camera.setCameraSurfaceMode(0);
            camera.setAutoProbeCameras(true);
            camera.setCameraProbeCallback((cameraId, surfaceMode) -> {
                logger.info("Probe found working camera: id=" + cameraId + ", surfaceMode=" + surfaceMode);
                try {
                    org.json.JSONObject camCfg = new org.json.JSONObject();
                    camCfg.put("probedCameraId", cameraId);
                    camCfg.put("probedSurfaceMode", surfaceMode);
                    com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                    logger.info("Saved camera config for next launch");
                } catch (Exception ex) {
                    logger.warn("Failed to save camera config: " + ex.getMessage());
                }
                // Check if there's a pending recording that was deferred
                new Thread(() -> {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    checkPendingRecording();
                }, "PendingRecCheck").start();
            });
        }
        
        // Always 4-camera mosaic — both devices output the same strip format
        if (recorder != null) {
            recorder.setCameraLayout(0);
        }
        
        // 6. Create adaptive bitrate controller
        bitrateController = new AdaptiveBitrateController(encoder, 6_000_000);
        
        initialized = true;
        logger.info( "GPU surveillance pipeline initialized");
    }
    
    /**
     * Starts the GPU pipeline.
     * 
     * @throws Exception if start fails
     */
    public void start() throws Exception {
        start(false);
    }
    
    /**
     * Starts the GPU pipeline.
     * 
     * @param autoStartRecording If true, automatically starts recording when recorder is ready
     * @throws Exception if start fails
     */
    public void start(boolean autoStartRecording) throws Exception {
        // CRITICAL: Set running flag FIRST to prevent race conditions
        // Multiple threads may call start() concurrently (HTTP + WebSocket)
        synchronized (this) {
            if (running) {
                logger.warn( "Already running");
                return;
            }
            running = true;  // Set immediately to block concurrent starts
        }
        
        try {
            // Reinitialize if stopped (encoder/recorder were released)
            if (!initialized) {
                init();
            }
            
            logger.info( "Starting GPU pipeline (autoRecord=" + autoStartRecording + ")...");
            
            // Start camera (this creates EGL context and initializes downscaler)
            camera.start();
            
            // SOTA: Register yield listener for recording finalization during camera yield.
            // When contention is detected and the camera must yield to the native AVM app,
            // this ensures any active recording is properly finalized (moov atom written)
            // before the camera closes, and recording resumes after re-acquisition.
            camera.setCameraYieldListener(new PanoramicCameraGpu.CameraYieldListener() {
                @Override
                public void onPreYield() {
                    logger.info("Pre-yield: finalizing active recording...");
                    
                    // Stop any active recording to finalize the MP4 file
                    if (recorder != null && recorder.isRecording()) {
                        recorder.stopRecording();
                        logger.info("Pre-yield: recording stopped");
                    }
                    
                    // Flush encoder to ensure all buffered frames are written
                    if (encoder != null && encoder.isWritingToFile()) {
                        encoder.flushAndClose();
                        logger.info("Pre-yield: encoder flushed");
                    }
                }
                
                @Override
                public void onPostReacquire() {
                    logger.info("Post-reacquire: resuming recording and streaming...");
                    
                    // Restore streaming components if streaming was enabled.
                    // yieldCameraInternal and restartCameraAfterError call clearStreamingComponents()
                    // which nulls the camera's local refs. The pipeline still holds the actual objects.
                    if (streamingEnabled && streamScaler != null && streamEncoder != null && camera != null) {
                        camera.setStreamingComponents(streamScaler, streamEncoder);
                        logger.info("Post-reacquire: streaming components restored");
                    }
                    
                    // Resume recording in whatever mode was active before yield
                    if (currentMode == Mode.SURVEILLANCE) {
                        // Sentry mode — re-enable surveillance (it will start recording on motion)
                        if (sentry != null && !sentry.isActive()) {
                            sentry.enable();
                        }
                        logger.info("Post-reacquire: surveillance mode restored");
                    } else if (currentMode == Mode.NORMAL_RECORDING || recordingMode) {
                        // Normal recording mode — restart recording
                        if (recorder != null && !recorder.isRecording()) {
                            recorder.startRecording();
                            logger.info("Post-reacquire: normal recording resumed");
                        }
                    }
                }
            });
            
            // Wait for camera to fully initialize and GL context to be ready
            // Increased timeout to ensure recorder can be initialized
            Thread.sleep(1500);  // Increased from 1000ms to 1500ms
            
            // Set callback to start recording when recorder is ready
            if (autoStartRecording) {
                recordingMode = true;
                camera.setRecorderInitCallback(() -> {
                    logger.info( "Recorder ready - starting recording automatically");
                    recorder.startRecording();
                    currentMode = Mode.NORMAL_RECORDING;
                    
                    // Enable overlay for auto-started recording
                    recorder.setOverlayRecordingModeAllowed(true);
                    if (telemetryCollector != null && recorder.isOverlayEnabled()) {
                        telemetryCollector.setOverlayRecordingActive(true);
                        telemetryCollector.startPolling();
                    }
                });
            } else {
                recordingMode = false;
            }
            
            // Initialize recorder on GL thread (CRITICAL: must be on GL thread!)
            if (camera.getEglCore() != null) {
                camera.initRecorderOnGlThread(recorder, encoder);
                logger.info( "Recorder initialization scheduled on GL thread");
                
                // Wait for recorder to initialize before continuing
                Thread.sleep(500);
            }
            
            // DON'T auto-enable streaming - enable on-demand when client requests
            // Streaming will be enabled via enableStreaming() when HTTP client connects
            
            // DON'T auto-enable surveillance - let caller decide
            // Surveillance should only be enabled when explicitly requested
            // sentry.enable();  // REMOVED - caller must explicitly enable
            
            logger.info( "GPU pipeline started (streaming on-demand, surveillance NOT auto-enabled)");
        
        } catch (Exception e) {
            // Reset running flag on failure so retry is possible
            synchronized (this) {
                running = false;
            }
            throw e;
        }
    }
    
    /**
     * Stops the GPU pipeline.
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        logger.info( "Stopping GPU pipeline...");
        running = false;
        
        // Clear any pending deferred recording
        pendingRecordingDir = null;
        pendingRecordingPrefix = null;
        
        // Reset mode so status API reflects that we're not in any active mode
        currentMode = Mode.IDLE;
        
        // Stop recording first to finalize file
        if (recorder != null && recorder.isRecording()) {
            recorder.stopRecording();
        }
        
        // Disable streaming — stream encoder/scaler hold EGL surfaces that will be
        // destroyed when the camera stops. They must be released before camera.stop().
        if (streamingEnabled) {
            disableStreaming();
        }
        
        // Disable surveillance
        if (sentry != null) {
            sentry.disable();
        }
        
        // Stop camera (this releases EGL context and surfaces)
        if (camera != null) {
            camera.stop();
        }
        
        // CRITICAL: Release recorder and encoder since EGL context is gone
        // They must be recreated on next start()
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
        
        // Mark as not initialized so init() can be called again
        initialized = false;
        
        logger.info( "GPU pipeline stopped");
    }
    
    /**
     * Releases all resources.
     */
    public void release() {
        stop();
        
        if (bitrateController != null) {
            bitrateController.release();
            bitrateController = null;
        }
        
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        
        if (downscaler != null) {
            downscaler.release();
            downscaler = null;
        }
        
        if (sentry != null) {
            sentry.release();
            sentry = null;
        }
        
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
        
        initialized = false;
        logger.info( "GPU pipeline released");
    }
    
    /**
     * Starts recording.
     * Stops surveillance if active (mutually exclusive).
     */
    public void startRecording() {
        startRecording(null, "cam");
    }
    
    /**
     * Starts recording with custom output directory and filename prefix.
     * Stops surveillance if active (mutually exclusive).
     * 
     * @param outputDir Custom output directory (null for default recordings dir)
     * @param prefix Filename prefix (e.g., "cam", "proximity", "event")
     */
    public void startRecording(java.io.File outputDir, String prefix) {
        // Stop surveillance if active (mutually exclusive)
        if (currentMode == Mode.SURVEILLANCE) {
            logger.info("Stopping surveillance to start normal recording (mutually exclusive)");
            if (sentry != null) {
                sentry.disable();
            }
        }
        
        // SOTA: Ensure storage is ready (mount SD card if needed) for recordings
        if (outputDir == null) {  // Only check for default recordings dir
            try {
                StorageManager storage = StorageManager.getInstance();
                if (!storage.ensureStorageReady(false)) {
                    logger.warn("Storage not ready for recording, but continuing with fallback");
                }
            } catch (Exception e) {
                logger.warn("Error checking storage readiness: " + e.getMessage());
            }
        }
        
        if (recorder != null) {
            // Check if encoder is ready (has received at least one frame from camera).
            if (recorder.getEncoder() != null && recorder.getEncoder().isFormatAvailable()) {
                recorder.startRecording(outputDir, prefix);
                currentMode = Mode.NORMAL_RECORDING;
                recorder.setOverlayRecordingModeAllowed(true);
                if (telemetryCollector != null) {
                    telemetryCollector.setOverlayRecordingActive(true);
                    telemetryCollector.startPolling();
                }
                logger.info("Normal recording started (dir=" + (outputDir != null ? outputDir.getName() : "default") + ", prefix=" + prefix + ")");
            } else {
                // Encoder not ready yet (camera still probing on ACC ON).
                // Store the request and let the pipeline start recording once the
                // encoder format becomes available. The render loop or probe-complete
                // callback will pick this up.
                logger.info("Encoder not ready yet — recording will start when camera is ready");
                pendingRecordingDir = outputDir;
                pendingRecordingPrefix = prefix;
                recordingMode = true;
            }
        }
    }
    
    /**
     * Called when the encoder format becomes available (probe complete, first frame encoded).
     * Starts any pending recording that was deferred because the encoder wasn't ready.
     */
    void checkPendingRecording() {
        if (pendingRecordingPrefix == null) return;
        if (recorder == null || recorder.getEncoder() == null) return;
        if (!recorder.getEncoder().isFormatAvailable()) return;
        
        java.io.File dir = pendingRecordingDir;
        String prefix = pendingRecordingPrefix;
        pendingRecordingDir = null;
        pendingRecordingPrefix = null;
        
        logger.info("Encoder now ready — starting deferred recording");
        recorder.startRecording(dir, prefix);
        currentMode = Mode.NORMAL_RECORDING;
        recorder.setOverlayRecordingModeAllowed(true);
        if (telemetryCollector != null) {
            telemetryCollector.setOverlayRecordingActive(true);
            telemetryCollector.startPolling();
        }
        logger.info("Deferred normal recording started (dir=" + 
            (dir != null ? dir.getName() : "default") + ", prefix=" + prefix + ")");
    }
    
    /**
     * Stops recording.
     */
    public void stopRecording() {
        if (recorder != null) {
            recorder.stopRecording();
            
            // Disable overlay compositing when recording stops
            recorder.setOverlayRecordingModeAllowed(false);
            if (telemetryCollector != null) {
                telemetryCollector.setOverlayRecordingActive(false);
                telemetryCollector.stopPolling();
            }
            
            currentMode = Mode.IDLE;
            logger.info( "Normal recording stopped");
        }
    }
    
    /**
     * Enables surveillance mode (motion detection + event recording).
     * Stops normal recording if active (mutually exclusive).
     * SOTA: Ensures SD card is mounted if SD card storage is selected.
     */
    public void enableSurveillance() {
        // Stop normal recording if active (mutually exclusive)
        if (currentMode == Mode.NORMAL_RECORDING) {
            logger.info("Stopping normal recording to enable surveillance (mutually exclusive)");
            if (recorder != null) {
                recorder.stopRecording();
            }
        }
        
        // SOTA: Ensure storage is ready (mount SD card if needed)
        try {
            StorageManager storage = StorageManager.getInstance();
            if (!storage.ensureStorageReady(true)) {
                logger.warn("Storage not ready for surveillance, but continuing with fallback");
            }
            
            // SOTA: Update sentry's event output directory to current surveillance path
            // This handles storage type changes (internal <-> SD card) at runtime
            if (sentry != null) {
                File currentSurveillanceDir = storage.getSurveillanceDir();
                sentry.setEventOutputDir(currentSurveillanceDir);
                logger.info("Surveillance output directory: " + currentSurveillanceDir.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("Error checking storage readiness: " + e.getMessage());
        }
        
        if (sentry != null) {
            sentry.enable();
            currentMode = Mode.SURVEILLANCE;
            logger.info("Surveillance mode enabled (sentry.active=" + sentry.isActive() + ")");
        } else {
            logger.error("Cannot enable surveillance: sentry is null!");
        }
        
        // Disable overlay compositing in surveillance mode
        if (recorder != null) {
            recorder.setOverlayRecordingModeAllowed(false);
        }
        if (telemetryCollector != null) {
            telemetryCollector.setOverlayRecordingActive(false);
            telemetryCollector.stopPolling();
        }
    }
    
    /**
     * Disables surveillance mode.
     */
    public void disableSurveillance() {
        if (sentry != null) {
            sentry.disable();
            currentMode = Mode.IDLE;
            logger.info( "Surveillance mode disabled");
        }
    }
    
    /**
     * Called when ACC turns ON - stops surveillance recording.
     * This ensures sentry recordings are properly finalized when car starts.
     * 
     * CRITICAL: Must synchronously close any active recording to prevent file corruption.
     */
    public void onAccOn() {
        logger.info("ACC ON detected - stopping surveillance and finalizing recordings");

        // First, stop any active recording immediately (synchronous)
        if (recorder != null && recorder.isRecording()) {
            logger.info("Stopping active recording before ACC transition");
            recorder.stopRecording();
        }

        // Also flush and close the encoder to ensure file is finalized
        if (encoder != null && encoder.isWritingToFile()) {
            logger.info("Flushing encoder before ACC transition");
            encoder.flushAndClose();
        }

        // Now disable surveillance mode
        if (currentMode == Mode.SURVEILLANCE) {
            disableSurveillance();
        }

        // Also stop normal recording if active
        if (currentMode == Mode.NORMAL_RECORDING) {
            stopRecording();
        }

        // CRITICAL: Reopen camera to let BYD native app get video frames.
        // During ACC OFF the daemon holds the camera exclusively for surveillance.
        // The native camera app starts on ACC ON but can't get frames because we
        // already have the primary slot. Briefly releasing and reopening the camera
        // lets the native app grab it first, then we get added as secondary consumer.
        if (camera != null && running) {
            camera.reopenCamera();
        }

        logger.info("ACC ON transition complete - all recordings finalized, camera reopened");
    }
    
    /**
     * Enables H.264 streaming with separate encoder.
     * 
     * @param streamWidth Stream width (e.g., 1280)
     * @param streamHeight Stream height (e.g., 960)
     * @param streamFps Stream FPS (e.g., 10)
     * @param streamBitrate Stream bitrate (e.g., 2 Mbps)
     */
    public void enableStreaming(int streamWidth, int streamHeight, int streamFps, 
                               int streamBitrate) throws Exception {
        if (streamingEnabled) {
            logger.warn("Streaming already enabled");
            return;
        }
        
        // Auto-start pipeline if not running (e.g., DRIVE_MODE in gear P, user opens stream)
        if (!running) {
            logger.info("Pipeline not running — auto-starting for streaming (view-only)");
            start(false);  // Start without auto-recording
        }
        
        // Verify camera GL thread is ready after start
        if (camera == null || camera.getGlHandler() == null) {
            logger.error("Cannot enable streaming - camera GL thread not ready");
            throw new IllegalStateException("Camera GL thread not initialized");
        }
        
        logger.info(String.format("Enabling H.264 streaming: %dx%d @ %dfps, %d Mbps",
                streamWidth, streamHeight, streamFps, streamBitrate / 1_000_000));
        
        // Create stream encoder
        logger.info("Creating stream encoder...");
        streamEncoder = new HardwareEventRecorderGpu(streamWidth, streamHeight, streamFps, streamBitrate);
        streamEncoder.setUsePreRecordBuffer(false);  // Stream-only, no pre-record needed
        streamEncoder.init();
        logger.info("Stream encoder initialized");
        
        // Create stream scaler
        logger.info("Creating stream scaler...");
        streamScaler = new com.overdrive.app.streaming.GpuStreamScaler(streamWidth, streamHeight);
        
        // Always 4-camera mosaic for streaming
        streamScaler.setCameraLayout(0);
        
        // Initialize on GL thread and WAIT for completion
        // This ensures the scaler is ready before we set streaming components
        final Object initLock = new Object();
        final boolean[] initDone = {false};
        final Exception[] initError = {null};
        
        camera.getGlHandler().post(() -> {
            try {
                streamScaler.init(camera.getEglCore(), streamEncoder);
                logger.info("Stream scaler initialized on GL thread");
            } catch (Exception e) {
                logger.error("Failed to initialize stream scaler on GL thread", e);
                initError[0] = e;
            } finally {
                synchronized (initLock) {
                    initDone[0] = true;
                    initLock.notify();
                }
            }
        });
        
        // Wait for GL thread initialization (max 2 seconds)
        synchronized (initLock) {
            if (!initDone[0]) {
                initLock.wait(2000);
            }
        }
        
        if (!initDone[0]) {
            throw new RuntimeException("Stream scaler initialization timed out");
        }
        
        if (initError[0] != null) {
            throw new RuntimeException("Stream scaler initialization failed: " + initError[0].getMessage(), initError[0]);
        }
        
        // Now set components on camera (scaler is guaranteed initialized)
        logger.info("Setting streaming components on camera...");
        camera.setStreamingComponents(streamScaler, streamEncoder);
        
        // Create WebSocket stream server (port 8887)
        // WebSocket has zero buffering delay vs HTTP Chunked (64KB+ buffer)
        logger.info("Starting WebSocket stream server...");
        wsStreamServer = new com.overdrive.app.streaming.WebSocketStreamServer();
        
        // Set idle shutdown callback - auto-stop pipeline when no clients for 15 seconds
        final GpuSurveillancePipeline self = this;
        wsStreamServer.setIdleShutdownCallback(new Runnable() {
            @Override
            public void run() {
                logger.info("WebSocket idle timeout - stopping streaming and pipeline");
                // Run on separate thread to avoid blocking timer thread
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            self.disableStreaming();
                            // Only stop pipeline if surveillance is not active
                            if (currentMode != Mode.SURVEILLANCE && running) {
                                logger.info("Surveillance not active - stopping pipeline to save resources");
                                self.stop();
                            }
                        } catch (Exception e) {
                            logger.error("Error during idle shutdown", e);
                        }
                    }
                }, "IdleShutdown").start();
            }
        });
        
        wsStreamServer.start();
        logger.info("WebSocket server started, setting stream callback...");
        streamEncoder.setStreamCallback(wsStreamServer);
        
        streamingEnabled = true;
        logger.info("H.264 streaming enabled (WebSocket port 8887)");
    }
    
    /**
     * Disables H.264 streaming and releases stream encoder.
     */
    public void disableStreaming() {
        if (!streamingEnabled) {
            return;
        }
        
        logger.info("Disabling H.264 streaming...");
        streamingEnabled = false;
        
        // CRITICAL: Clear streaming components from camera FIRST
        // This prevents render loop from using released surfaces
        if (camera != null) {
            camera.clearStreamingComponents();
        }
        
        // Clear stream callback
        if (streamEncoder != null) {
            streamEncoder.clearStreamCallback();
        }
        
        // Stop WebSocket server
        if (wsStreamServer != null) {
            wsStreamServer.shutdown();
            wsStreamServer = null;
        }
        
        // Release stream encoder
        if (streamEncoder != null) {
            streamEncoder.release();
            streamEncoder = null;
        }
        
        // Release stream scaler
        if (streamScaler != null) {
            streamScaler.release();
            streamScaler = null;
        }
        
        logger.info("H.264 streaming disabled");
    }
    
    /**
     * Checks if streaming is enabled.
     */
    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }
    
    /**
     * Gets the stream scaler component.
     */
    public com.overdrive.app.streaming.GpuStreamScaler getStreamScaler() {
        return streamScaler;
    }
    
    /**
     * Gets the stream encoder component.
     */
    public HardwareEventRecorderGpu getStreamEncoder() {
        return streamEncoder;
    }
    
    /**
     * Gets the WebSocket stream server.
     */
    public com.overdrive.app.streaming.WebSocketStreamServer getWebSocketServer() {
        return wsStreamServer;
    }
    
    /**
     * Sets the stream view mode (which camera to show).
     * 
     * @param mode 0=Mosaic (2x2 grid), 1=Front, 2=Right, 3=Rear, 4=Left
     */
    public void setStreamViewMode(int mode) {
        if (streamScaler != null) {
            streamScaler.setViewMode(mode);
            logger.info("Stream view mode changed to " + mode);
        } else {
            logger.warn("Cannot set stream view mode - streaming not enabled");
        }
    }
    
    /**
     * Gets the current stream view mode.
     * 
     * @return 0=Mosaic, 1-4=Single camera, -1 if streaming not enabled
     */
    public int getStreamViewMode() {
        return streamScaler != null ? streamScaler.getViewMode() : -1;
    }
    
    /**
     * Checks if currently recording.
     * 
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recorder != null && recorder.isRecording();
    }
    
    /**
     * Checks if in recording mode (vs viewing mode).
     */
    public boolean isRecordingMode() {
        return recordingMode;
    }
    
    /**
     * Checks if initialized.
     * 
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    private static String getVehicleModel() {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class)
                .invoke(null, "ro.product.model", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    /**
     * Checks if running.
     * 
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Gets the camera component.
     * 
     * @return PanoramicCameraGpu instance
     */
    public PanoramicCameraGpu getCamera() {
        return camera;
    }
    
    /**
     * Gets the surveillance engine.
     * 
     * @return SurveillanceEngineGpu instance
     */
    public SurveillanceEngineGpu getSentry() {
        return sentry;
    }
    
    /**
     * Gets the adaptive bitrate controller.
     * 
     * @return AdaptiveBitrateController instance
     */
    public AdaptiveBitrateController getBitrateController() {
        return bitrateController;
    }
    
    /**
     * Checks if surveillance mode is active.
     * 
     * @return true if in surveillance mode
     */
    public boolean isSurveillanceMode() {
        return currentMode == Mode.SURVEILLANCE;
    }
    
    /**
     * Checks if normal recording mode is active.
     * 
     * @return true if in normal recording mode
     */
    public boolean isNormalRecordingMode() {
        return currentMode == Mode.NORMAL_RECORDING;
    }
    
    /**
     * Sets the telemetry collector instance for overlay data.
     */
    public void setTelemetryCollector(TelemetryDataCollector collector) {
        this.telemetryCollector = collector;
        if (recorder != null) {
            recorder.setTelemetryCollector(collector);
        }
    }
    
    /**
     * Enables or disables the telemetry overlay.
     * Starts/stops the telemetry collector based on current recording mode.
     */
    public void setOverlayEnabled(boolean enabled) {
        this.overlayEnabledConfig = enabled;
        if (recorder != null) {
            recorder.setOverlayEnabled(enabled);
        }
        // Start/stop telemetry collector based on overlay state
        if (enabled && currentMode == Mode.NORMAL_RECORDING && telemetryCollector != null) {
            telemetryCollector.setOverlayRecordingActive(true);
            telemetryCollector.startPolling();
        } else if (!enabled && telemetryCollector != null) {
            telemetryCollector.setOverlayRecordingActive(false);
            telemetryCollector.stopPolling();
        }
    }
}
