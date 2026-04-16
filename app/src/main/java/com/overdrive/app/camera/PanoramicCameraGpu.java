package com.overdrive.app.camera;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.GpuDownscaler;
import com.overdrive.app.surveillance.GpuMosaicRecorder;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * PanoramicCameraGpu - GPU Edition with Zero-Copy Pipeline.
 * 
 * This is the GPU-native version of PanoramicCamera that replaces ImageReader
 * with SurfaceTexture. Camera frames flow directly to GPU texture, enabling:
 * - Zero-copy recording (camera → GPU → encoder)
 * - Minimal AI readback (GPU downscales to 320x240)
 * - <10% total CPU usage
 * 
 * Architecture:
 * - Camera writes to GL_TEXTURE_EXTERNAL_OES via SurfaceTexture
 * - Render loop on dedicated GL thread distributes frames to:
 *   - Recording Lane: GpuMosaicRecorder (zero-copy to encoder)
 *   - AI Lane: GpuDownscaler (2 FPS readback for motion detection)
 */
public class PanoramicCameraGpu {
    private static final String TAG = "PanoramicCameraGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final int PHYSICAL_CAMERA_ID = 1;
    private static final int MAX_SURFACE_MODE = 5;  // Probe surface modes 0-5
    
    // AVMCamera surface mode — 0 works on Seal, Atto 1 may need different value
    // Set via setCameraSurfaceMode() before start() for per-model override
    private int cameraSurfaceMode = 0;
    
    // Camera ID override — set via setCameraId() before start()
    private int cameraIdOverride = -1;  // -1 = use default PHYSICAL_CAMERA_ID
    
    // Auto-probe mode — tries all camera IDs to find one with actual image data
    private boolean autoProbeCameras = false;
    private int probeStartId = -1;  // Tracks where probe started for wrap-around detection
    
    // Callback when auto-probe discovers a working camera config
    public interface CameraProbeCallback {
        void onCameraFound(int cameraId, int surfaceMode);
    }
    private CameraProbeCallback probeCallback;
    
    // Camera dimensions
    private final int width;
    private final int height;
    
    // EGL and OpenGL
    private EGLCore eglCore;
    private android.opengl.EGLSurface dummySurface;  // Pbuffer for headless context
    private int cameraTextureId;
    private SurfaceTexture cameraSurfaceTexture;
    private Surface cameraSurface;
    
    // Camera object (via reflection)
    private Object cameraObj;
    
    // Render loop
    private HandlerThread glThread;
    private Handler glHandler;
    private volatile boolean running = false;
    private final Object frameSync = new Object();
    
    // Consumers
    private GpuMosaicRecorder recorder;
    private HardwareEventRecorderGpu encoder;  // Direct encoder reference for draining
    private com.overdrive.app.streaming.GpuStreamScaler streamScaler;  // Stream scaler (optional)
    private HardwareEventRecorderGpu streamEncoder;  // Stream encoder (optional)
    private GpuDownscaler downscaler;
    private SurveillanceEngineGpu sentry;
    
    // Frame timing
    private int frameCounter = 0;
    private static final int AI_FRAME_SKIP = 15;  // 30fps / 15 = 2 FPS for AI
    private long lastFrameTime = 0;
    private long startTime = 0;
    
    // Watchdog for GL thread hang detection
    private volatile long lastGlThreadHeartbeat = 0;
    private Thread watchdogThread;
    private static final long GL_THREAD_TIMEOUT_MS = 3000;
    
    // SOTA: BYD camera coordinator for cooperative sharing and error recovery
    private BydCameraCoordinator cameraCoordinator;
    private volatile boolean cameraYielded = false;
    
    // Camera health monitor — detects stalled frames and triggers recovery
    private static final long FRAME_STALL_THRESHOLD_MS = 2000;  // 2 seconds without frames
    
    // SOTA: Pre-yield listener — pipeline registers this to finalize recordings before yield
    public interface CameraYieldListener {
        /** Called BEFORE camera is yielded. Finalize any active recording to prevent corruption. */
        void onPreYield();
        /** Called AFTER camera is re-acquired. Resume recording if needed. */
        void onPostReacquire();
    }
    private CameraYieldListener yieldListener;
    
    // CPU usage monitoring
    private long lastCpuCheckTime = 0;
    private static final long CPU_CHECK_INTERVAL_MS = 10000;  // Every 10 seconds
    
    // Stats logging (time-based, not frame-based)
    private long lastStatsTime = 0;
    private static final long STATS_INTERVAL_MS = 120000;  // Every 2 minutes
    
    /**
     * Creates a GPU-based panoramic camera.
     * 
     * @param width Camera width (typically 5120)
     * @param height Camera height (typically 960)
     */
    public PanoramicCameraGpu(int width, int height) {
        this.width = width;
        this.height = height;
    }
    
    /**
     * Sets the consumers for the camera frames.
     * 
     * @param recorder GPU mosaic recorder for zero-copy recording
     * @param downscaler GPU downscaler for AI lane
     * @param sentry Surveillance engine for motion detection
     */
    public void setConsumers(GpuMosaicRecorder recorder, GpuDownscaler downscaler, 
                            SurveillanceEngineGpu sentry) {
        this.recorder = recorder;
        this.downscaler = downscaler;
        this.sentry = sentry;
    }
    
    /**
     * Starts the GPU camera pipeline.
     * 
     * @throws Exception if initialization fails
     */
    public void start() throws Exception {
        logger.info( "Starting GPU camera pipeline...");
        startTime = System.currentTimeMillis();
        
        // SOTA: Initialize BYD camera coordinator for cooperative sharing
        if (cameraCoordinator == null) {
            cameraCoordinator = new BydCameraCoordinator();
            cameraCoordinator.setYieldCallback(new BydCameraCoordinator.CameraYieldCallback() {
                @Override
                public void onYieldCamera() {
                    // Contention detected — yield on GL thread
                    logger.info("YIELD: Contention detected — releasing camera for native app");
                    cameraYielded = true;
                    if (glHandler != null) {
                        glHandler.post(() -> yieldCameraInternal());
                    }
                }

                @Override
                public void onReacquireCamera() {
                    // Native app released camera after contention yield — re-acquire
                    logger.info("REACQUIRE: Native app released camera — reopening");
                    cameraYielded = false;
                    if (glHandler != null) {
                        glHandler.post(() -> {
                            try {
                                startCamera();
                                if (cameraCoordinator != null && cameraObj != null) {
                                    cameraCoordinator.resetEventCallbackState();
                                    cameraCoordinator.setupEventCallback(cameraObj);
                                }
                                
                                // SOTA: Notify pipeline to resume recording
                                if (yieldListener != null) {
                                    try {
                                        yieldListener.onPostReacquire();
                                        logger.info("Post-reacquire: recording resumed");
                                    } catch (Exception e) {
                                        logger.warn("Post-reacquire callback error: " + e.getMessage());
                                    }
                                }
                                
                                logger.info("Camera re-acquired after contention yield");
                            } catch (Exception e) {
                                logger.error("Failed to re-acquire camera: " + e.getMessage());
                            }
                        });
                    }
                }

                @Override
                public void onCameraError(int eventType) {
                    // Camera HAL error — trigger full restart cycle
                    logger.error("CAMERA ERROR: event=" + eventType + " — restarting camera");
                    if (glHandler != null) {
                        glHandler.post(() -> restartCameraAfterError());
                    }
                }
            });
            cameraCoordinator.register();
        }
        
        // Start GL thread
        glThread = new HandlerThread("GL-RenderLoop");
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        
        // Initialize on GL thread
        glHandler.post(() -> {
            try {
                initializeGl();
                startCamera();
                
                // SOTA: Setup event callback for HAL error detection (-10086, 8)
                if (cameraCoordinator != null && cameraObj != null) {
                    cameraCoordinator.setupEventCallback(cameraObj);
                }
                
                running = true;
                
                // Start render loop
                glHandler.post(this::renderLoop);
                
                // Start watchdog
                startWatchdog();
                
                logger.info( "GPU camera pipeline started");
            } catch (Exception e) {
                logger.error( "Failed to start GPU pipeline", e);
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Initializes OpenGL context and textures.
     */
    private void initializeGl() {
        // Create EGL context
        eglCore = new EGLCore();
        
        // Create a dummy pbuffer surface and make it current
        // This is required before any OpenGL calls can be made
        dummySurface = eglCore.createPbufferSurface(1, 1);
        eglCore.makeCurrent(dummySurface);
        
        // Log GL info (now that context is current)
        GlUtil.logGlInfo();
        
        // Create camera texture (OES type for external camera)
        cameraTextureId = GlUtil.createExternalTexture();
        
        // Create SurfaceTexture from camera texture
        cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
        cameraSurfaceTexture.setDefaultBufferSize(width, height);
        cameraSurfaceTexture.setOnFrameAvailableListener(this::onFrameAvailable);
        
        // Create Surface for camera
        cameraSurface = new Surface(cameraSurfaceTexture);
        
        // Initialize GPU components now that EGL context exists
        if (recorder != null) {
            // Recorder needs to be initialized with EGLCore and encoder
            // This should be done by the caller after encoder is created
            logger.debug( "Recorder initialization deferred to caller");
        }
        
        if (downscaler != null) {
            downscaler.init();  // Default RGB mode
            logger.debug( "Downscaler initialized");
        }
        
        logger.info( "OpenGL initialized (texture=" + cameraTextureId + ")");
    }
    
    /**
     * Initializes the recorder on the GL thread.
     * 
     * This must be called after the GL context is created and made current.
     * 
     * @param recorder GPU mosaic recorder to initialize
     * @param encoder Hardware encoder providing the input surface
     */
    public void initRecorderOnGlThread(GpuMosaicRecorder recorder, HardwareEventRecorderGpu encoder) {
        if (glHandler == null) {
            logger.error( "GL thread not started");
            return;
        }
        
        // Store encoder reference for draining in render loop
        this.encoder = encoder;
        
        glHandler.post(() -> {
            try {
                recorder.init(eglCore, encoder);
                logger.info( "Recorder initialized on GL thread");
                
                // Notify pipeline that recorder is ready
                if (recorderInitCallback != null) {
                    recorderInitCallback.run();
                }
            } catch (Exception e) {
                logger.error( "Failed to initialize recorder on GL thread", e);
            }
        });
    }
    
    // Callback for when recorder is initialized
    private Runnable recorderInitCallback;
    
    /**
     * Sets a callback to be invoked when the recorder is initialized.
     * 
     * @param callback Callback to run on GL thread after recorder init
     */
    public void setRecorderInitCallback(Runnable callback) {
        this.recorderInitCallback = callback;
    }
    
    /**
     * Initializes the stream scaler on the GL thread.
     * 
     * @param streamScaler GPU stream scaler to initialize
     * @param streamEncoder Hardware encoder for streaming
     */
    public void initStreamScalerOnGlThread(com.overdrive.app.streaming.GpuStreamScaler streamScaler,
                                          HardwareEventRecorderGpu streamEncoder) {
        if (glHandler == null) {
            logger.error("GL thread not started");
            return;
        }
        
        glHandler.post(() -> {
            try {
                streamScaler.init(eglCore, streamEncoder);
                logger.info("Stream scaler initialized on GL thread");
            } catch (Exception e) {
                logger.error("Failed to initialize stream scaler on GL thread", e);
            }
        });
    }
    
    /**
     * Gets the EGL core for initializing GPU components.
     * 
     * @return EGLCore instance (only valid after start() is called)
     */
    public EGLCore getEglCore() {
        return eglCore;
    }
    
    /**
     * Starts the BYD camera via reflection.
     */
    private void startCamera() throws Exception {
        // GATE: Don't open camera if yielded to native app via IBYDCameraUser callback
        if (cameraCoordinator != null && cameraCoordinator.isCameraYielded()) {
            logger.info("Camera yielded to native app — skipping open");
            cameraYielded = true;
            return;
        }

        // Open physical camera via AVMCamera
        int cameraId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
        Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
        Constructor<?> constructor = avmClass.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        cameraObj = constructor.newInstance(cameraId);
        
        Method mOpen = avmClass.getDeclaredMethod("open");
        mOpen.setAccessible(true);
        if (!(boolean) mOpen.invoke(cameraObj)) {
            throw new RuntimeException("Failed to open panoramic camera (id=" + cameraId + ")");
        }
        
        // Connect surface — mode 0 works on Seal, other models may need different mode
        Method mAddSurface = avmClass.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
        mAddSurface.setAccessible(true);
        mAddSurface.invoke(cameraObj, cameraSurface, cameraSurfaceMode);
        
        // Start preview
        Method mStart = avmClass.getDeclaredMethod("startPreview");
        mStart.setAccessible(true);
        mStart.invoke(cameraObj);
        
        cameraYielded = false;
        logger.info("Camera started (" + width + "x" + height + 
            ", id=" + cameraId + ", surfaceMode=" + cameraSurfaceMode + ")");
        
        // Update coordinator with actual camera ID
        if (cameraCoordinator != null) {
            cameraCoordinator.setActiveCameraId(cameraId);
        }
    }
    
    /**
     * Called when a new camera frame is available.
     */
    private void onFrameAvailable(SurfaceTexture st) {
        synchronized (frameSync) {
            frameSync.notify();
        }
    }
    
    /**
     * Main render loop - distributes frames to recording and AI lanes.
     */
    private void renderLoop() {
        if (!running) {
            return;
        }

        try {
            // Wait for new frame (hardware sync)
            synchronized (frameSync) {
                try {
                    frameSync.wait(100);  // Timeout to check running flag
                } catch (InterruptedException e) {
                    // Continue
                }
            }

            if (!running) {
                return;
            }

            // Update watchdog heartbeat
            lastGlThreadHeartbeat = System.currentTimeMillis();

            // SOTA: Skip frame processing if camera is yielded to native app
            if (cameraYielded || cameraObj == null) {
                // GL thread stays alive but doesn't touch camera — waiting for re-acquire
                return;
            }

            // CRITICAL: Always consume camera texture FIRST to keep the camera HAL's
            // BufferQueue flowing. If we don't call updateTexImage() promptly, the HAL
            // buffer fills up and the BYD native camera app loses video signal.
            cameraSurfaceTexture.updateTexImage();
            frameCounter++;
            lastFrameTime = System.currentTimeMillis();
            
            // AUTO-PROBE: After 15 frames (~2 sec), check if current camera has image data.
            // If blank and auto-probe is enabled, try next camera ID.
            if (frameCounter == 15 && downscaler != null) {
                try {
                    byte[] probe = downscaler.readPixels(cameraTextureId, 8, 8);
                    boolean hasData = false;
                    if (probe != null) {
                        for (int i = 0; i < Math.min(probe.length, 192); i++) {
                            if ((probe[i] & 0xFF) > 10) { hasData = true; break; }
                        }
                    }
                    int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                    boolean isPanoramic = width >= 5000;
                    logger.info("Camera ID " + currentId + " probe: " + 
                        (hasData ? "HAS DATA" : "BLACK") +
                        " | resolution=" + width + "x" + height +
                        " | type=" + (isPanoramic ? "PANORAMIC" : "SINGLE") +
                        " | surfaceMode=" + cameraSurfaceMode);
                    
                    if (hasData && isPanoramic) {
                        // Found a working panoramic camera — use it
                        logger.info("Auto-probe: SELECTED camera ID " + currentId + 
                            " (panoramic, has image data, surfaceMode=" + cameraSurfaceMode + ")");
                        autoProbeCameras = false;
                        probeStartId = -1;
                        // Notify pipeline to persist this config
                        if (probeCallback != null) {
                            probeCallback.onCameraFound(currentId, cameraSurfaceMode);
                        }
                    } else if (autoProbeCameras) {
                        // Only try ID 0 and ID 1 with surfaceMode 0
                        if (currentId == 0) {
                            logger.info("Auto-probe: camera ID 0 " + 
                                (!hasData ? "blank" : "not panoramic") + ", trying ID 1...");
                            cameraIdOverride = 1;
                            frameCounter = 0;
                            lastGlThreadHeartbeat = System.currentTimeMillis();
                            BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
                            cameraObj = null;
                            if (cameraCoordinator != null) {
                                cameraCoordinator.resetEventCallbackState();
                            }
                            lastGlThreadHeartbeat = System.currentTimeMillis();
                            startCamera();
                            if (cameraCoordinator != null && cameraObj != null) {
                                cameraCoordinator.setupEventCallback(cameraObj);
                            }
                        } else {
                            logger.warn("Auto-probe: camera ID 0 and 1 both failed — giving up");
                            autoProbeCameras = false;
                            probeStartId = -1;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Camera probe failed: " + e.getMessage());
                }
            }

            long loopStartNs = System.nanoTime();

            // PASS 1: Recording (Zero-Copy GPU Path)
            // SOTA: Always render to encoder (for pre-record circular buffer)
            GpuMosaicRecorder localRecorder = recorder;
            HardwareEventRecorderGpu localEncoder = encoder;
            if (localRecorder != null) {
                localRecorder.drawFrame(cameraTextureId);

                // CRITICAL: Drain encoder immediately after frame submission
                // This prevents eglSwapBuffers from blocking when encoder buffers fill up
                if (localEncoder != null) {
                    localEncoder.drainEncoder();
                }
                
                // RECOVERY: If encoder surface died (EGL_BAD_SURFACE after prolonged use),
                // reinitialize the encoder and reconnect the recorder
                if (localRecorder.needsReinit() && localEncoder != null) {
                    logger.warn("Encoder surface lost - reinitializing encoder...");
                    try {
                        recorder.releaseEncoderSurface();
                        encoder.release();
                        encoder.init();
                        recorder.init(eglCore, encoder);
                        recorder.clearReinitFlag();
                        logger.info("Encoder reinitialized successfully after surface loss");
                    } catch (Exception reinitEx) {
                        logger.error("Encoder reinit failed: " + reinitEx.getMessage());
                        // If reinit fails, force process restart — EGL context is likely corrupt
                        logger.error("CRITICAL: Encoder reinit failed, forcing process restart");
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        System.exit(0);
                    }
                }
            }

            // PASS 1B: Streaming (Parallel Zero-Copy GPU Path)
            // Only runs if streaming is enabled - uses separate encoder at lower resolution
            // Capture local refs to avoid NPE from concurrent pipeline shutdown
            com.overdrive.app.streaming.GpuStreamScaler localStreamScaler = streamScaler;
            HardwareEventRecorderGpu localStreamEncoder = streamEncoder;
            if (localStreamScaler != null && localStreamEncoder != null) {
                localStreamScaler.drawFrame(cameraTextureId);
                localStreamEncoder.drainEncoder();
            }

            // PASS 2: AI Lane (Downscale & Readback at 2 FPS)
            // Only run AI lane if we have time budget remaining (< 50ms per loop iteration)
            // This prevents AI processing from starving the camera HAL during heavy recording
            long elapsedMs = (System.nanoTime() - loopStartNs) / 1_000_000;
            if (sentry != null && sentry.isActive() && downscaler != null && elapsedMs < 50) {
                if (frameCounter % AI_FRAME_SKIP == 0) {
                    try {
                        byte[] smallFrame = downscaler.readPixels(cameraTextureId, 640, 480);
                        if (smallFrame != null) {
                            // Buffer is recycled inside processFrame's finally block
                            sentry.processFrame(smallFrame);
                        }
                    } catch (Exception e) {
                        // Log but don't crash - AI lane is non-critical
                        logger.warn("AI lane error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                }
            }

            // Log stats periodically (every 2 minutes, time-based)
            long now = System.currentTimeMillis();
            if (now - lastStatsTime >= STATS_INTERVAL_MS) {
                lastStatsTime = now;
                long elapsed = now - startTime;
                float fps = (frameCounter * 1000.0f) / elapsed;
                logger.info( String.format("Stats: %d frames, %.1f FPS, uptime=%ds",
                        frameCounter, fps, elapsed / 1000));
            }

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) {
                msg = e.getClass().getSimpleName();
            }
            logger.error("Render loop error: " + msg, e);
        }

        // Schedule next frame
        if (running) {
            glHandler.post(this::renderLoop);
        }
    }
    
    /**
     * Starts the watchdog thread that monitors GL thread health.
     * 
     * If the GL thread hangs (e.g., eglSwapBuffers blocks), the watchdog
     * will call System.exit(0) to force a process restart, since EGL
     * contexts cannot be recovered from a blocked thread.
     */
    private void startWatchdog() {
        lastGlThreadHeartbeat = System.currentTimeMillis();
        
        watchdogThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(1000);  // Check every second
                    
                    long now = System.currentTimeMillis();
                    long timeSinceHeartbeat = now - lastGlThreadHeartbeat;
                    
                    if (timeSinceHeartbeat > GL_THREAD_TIMEOUT_MS) {
                        logger.error( "CRITICAL: GL thread blocked for " + timeSinceHeartbeat + 
                                "ms - forcing process restart");
                        
                        // Try to flush logs before exit
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {}
                        
                        // Exit code 0 triggers restart loop in DaemonLauncher wrapper.
                        // EGL contexts cannot be recovered from a blocked thread.
                        System.exit(0);
                    }
                    
                    // SOTA: Frame health monitor — detect stalled camera feed
                    // If GL thread is alive but no new frames for FRAME_STALL_THRESHOLD_MS,
                    // the camera HAL may be starved or dead.
                    // Decision is contention-aware: if native app is active, yield.
                    // If native app is NOT active, restart camera (HAL issue).
                    if (!cameraYielded && lastFrameTime > 0 && 
                        timeSinceHeartbeat < GL_THREAD_TIMEOUT_MS) {
                        long timeSinceFrame = now - lastFrameTime;
                        if (timeSinceFrame > FRAME_STALL_THRESHOLD_MS) {
                            logger.warn("FRAME STALL: No frames for " + timeSinceFrame + "ms");
                            // Reset lastFrameTime to prevent repeated triggers
                            lastFrameTime = now;
                            
                            if (cameraCoordinator != null) {
                                // Ask coordinator: is this contention or a HAL issue?
                                boolean didYield = cameraCoordinator.onFrameStallDetected();
                                if (!didYield) {
                                    // Not contention — restart camera
                                    logger.info("Frame stall is HAL issue — restarting camera");
                                    if (glHandler != null) {
                                        glHandler.post(() -> restartCameraAfterError());
                                    }
                                }
                                // If yielded, coordinator will handle re-acquire via onCloseCamera
                            } else {
                                // No coordinator — just restart
                                if (glHandler != null) {
                                    glHandler.post(() -> restartCameraAfterError());
                                }
                            }
                        }
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "GL-Watchdog");
        
        watchdogThread.setDaemon(true);
        watchdogThread.start();
        
        logger.info( "GL thread watchdog started (timeout=" + GL_THREAD_TIMEOUT_MS + "ms, " +
            "frameStall=" + FRAME_STALL_THRESHOLD_MS + "ms)");
    }
    
    /**
     * SOTA: Yields the camera to the native BYD AVM app.
     * 
     * Called on GL thread when contention is detected (frame stall while native
     * app is active). Finalizes any active recording FIRST to prevent MP4 corruption,
     * then does a clean camera close.
     * 
     * The GL render loop continues running but skips frame processing while yielded.
     * Camera is re-acquired when onCloseCamera fires from IBYDCameraService.
     */
    private void yieldCameraInternal() {
        logger.info("Yielding camera to native AVM app...");
        
        // CRITICAL: Finalize active recording BEFORE closing camera.
        if (yieldListener != null) {
            try {
                yieldListener.onPreYield();
                logger.info("Pre-yield: recording finalized");
            } catch (Exception e) {
                logger.warn("Pre-yield callback error: " + e.getMessage());
            }
        }
        
        // Detach streaming components to stop drainer threads
        if (streamScaler != null || streamEncoder != null) {
            clearStreamingComponents();
        }
        
        // Wait for encoder drainer threads to quiesce
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        
        if (cameraObj != null) {
            BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
            }
            logger.info("Camera yielded — GL pipeline idle, waiting for onCloseCamera");
        }
    }
    
    /**
     * SOTA: Restarts the camera after a HAL error event or frame stall.
     * 
     * Called on GL thread. Does a full close→reopen cycle with proper cleanup.
     * This is faster than the watchdog kill+restart because it doesn't require
     * a full process restart — just a camera reopen.
     */
    private void restartCameraAfterError() {
        logger.info("Restarting camera after error/stall...");
        
        try {
            // CRITICAL: Finalize active recording BEFORE closing camera.
            // If the encoder is still consuming frames when we destroy the camera's
            // native mutex, we get a FORTIFY abort (pthread_mutex_lock on destroyed mutex).
            if (yieldListener != null) {
                try {
                    yieldListener.onPreYield();
                    logger.info("Pre-restart: recording finalized");
                } catch (Exception e) {
                    logger.warn("Pre-restart callback error: " + e.getMessage());
                }
            }
            
            // CRITICAL: Detach streaming components so the stream encoder's drainer
            // thread stops trying to read from the camera's SurfaceTexture.
            // Without this, the drainer thread hits the destroyed mutex → FORTIFY crash.
            if (streamScaler != null || streamEncoder != null) {
                clearStreamingComponents();
                logger.info("Pre-restart: streaming components detached");
            }
            
            // Wait for encoder drainer threads to quiesce after detach
            Thread.sleep(100);
            
            // Close with proper cleanup
            if (cameraObj != null) {
                BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
                cameraObj = null;
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                }
            }
            
            // Brief pause to let HAL settle
            Thread.sleep(500);
            
            // Update heartbeat so watchdog doesn't kill us during restart
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // Reopen
            startCamera();
            
            // Re-register event callback
            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.setupEventCallback(cameraObj);
            }
            
            // Resume recording/surveillance after camera restart
            if (yieldListener != null) {
                try {
                    yieldListener.onPostReacquire();
                    logger.info("Post-restart: recording/surveillance resumed");
                } catch (Exception e) {
                    logger.warn("Post-restart callback error: " + e.getMessage());
                }
            }
            
            logger.info("Camera restarted successfully after error");
            
        } catch (Exception e) {
            logger.error("Camera restart failed: " + e.getMessage());
            // If restart fails, the watchdog will eventually kill the process
        }
    }
    
    /**
     * Stops the GPU camera pipeline.
     */
    public void stop() {
        logger.info( "Stopping GPU camera pipeline...");
        running = false;
        
        // Stop watchdog
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }
        
        // SOTA: Unregister from IBYDCameraService
        if (cameraCoordinator != null) {
            cameraCoordinator.unregister();
        }
        
        // SOTA: Proper cleanup order — disablePreviewCallback → stopPreview → close
        // Skipping disablePreviewCallback leaves the HAL dirty and triggers micro system update
        if (cameraObj != null) {
            BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
            cameraObj = null;
        }
        
        // Cleanup on GL thread
        if (glHandler != null) {
            glHandler.post(this::releaseGl);
        }
        
        // Stop GL thread
        if (glThread != null) {
            glThread.quitSafely();
            try {
                glThread.join(1000);
            } catch (InterruptedException e) {
                logger.warn( "GL thread join interrupted");
            }
            glThread = null;
        }
        
        logger.info( "GPU camera pipeline stopped");
    }
    /**
     * Releases and reopens the AVMCamera without tearing down the GL pipeline.
     *
     * This is needed during ACC OFF→ON transitions. The daemon holds the camera
     * open continuously (surveillance → recording mode), which prevents the BYD
     * native camera app from getting video frames. By briefly releasing the camera,
     * the native app can grab it, and when we reopen we get added as a secondary
     * consumer via addPreviewSurface.
     */
    /**
     * Releases and reopens the AVMCamera without tearing down the GL pipeline.
     * 
     * During ACC OFF→ON, the daemon holds the camera from surveillance mode.
     * The BYD native camera app starts on ACC ON but can't get frames.
     * Releasing briefly lets the native app grab the primary slot, then we
     * get added as secondary consumer via addPreviewSurface.
     */
    public void reopenCamera() {
        reopenCamera(15000);
    }

    public void reopenCamera(long maxWaitMs) {
        if (!running) {
            logger.warn("Cannot reopen camera - not running");
            return;
        }

        logger.info("Reopening AVMCamera...");

        try {
            // Proper cleanup order via BydCameraCoordinator
            if (cameraObj != null) {
                BydCameraCoordinator.closeCamera(cameraObj, cameraSurfaceMode);
                cameraObj = null;
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                }
                logger.info("Camera closed (proper cleanup)");
            }

            // If registered as camera user, the onCloseCamera callback handles reacquisition.
            // We just need to wait for the native app to claim and then release the camera.
            // The callback will fire onReacquireCamera → which calls restartCameraAfterError
            // or reopenCamera again. So we only need a simple delay here.
            if (cameraCoordinator != null && cameraCoordinator.isRegisteredAsUser()) {
                logger.info("Registered as camera user — waiting for onCloseCamera callback " +
                    "(native app will trigger reacquire)");
                // Minimum wait for native app to boot and claim camera
                Thread.sleep(3000);

                // If native app hasn't claimed it yet (no onPreOpenCamera fired),
                // just reopen — we're not contending
                if (!cameraCoordinator.isCameraYielded()) {
                    logger.info("No yield triggered — native app may not be running, reopening");
                    startCamera();
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                    logger.info("Camera reopened (no contention detected)");
                } else {
                    logger.info("Camera yielded via callback — waiting for onCloseCamera to reacquire");
                    // onCloseCamera → onCameraAvailable → onReacquireCamera will handle it
                }
                return;
            }

            // FALLBACK: No registerUser — use polling to detect native app
            logger.info("No registerUser — using polling fallback (maxWait=" + maxWaitMs + "ms)");
            long minWaitMs = 3000;
            Thread.sleep(minWaitMs);

            if (cameraCoordinator != null && cameraCoordinator.isRegistered()) {
                long deadline = System.currentTimeMillis() + (maxWaitMs - minWaitMs);
                boolean nativeAppDetected = false;

                while (System.currentTimeMillis() < deadline) {
                    if (cameraCoordinator.checkNativeAppActive()) {
                        nativeAppDetected = true;
                        logger.info("Native app claimed camera (polling) — waiting for release");
                        Thread.sleep(500);
                        break;
                    }
                    Thread.sleep(500);
                }

                if (!nativeAppDetected) {
                    logger.info("Native app not detected after polling — reopening");
                }
            } else {
                long remainingWait = maxWaitMs - minWaitMs;
                logger.info("No service available — fixed delay (" + remainingWait + "ms)");
                Thread.sleep(remainingWait);
            }

            startCamera();

            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.setupEventCallback(cameraObj);
            }

            logger.info("Camera reopened successfully");

        } catch (Exception e) {
            logger.error("Failed to reopen camera: " + e.getMessage(), e);
            try {
                if (cameraObj == null) {
                    logger.warn("Retry camera open...");
                    startCamera();
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                }
            } catch (Exception e2) {
                logger.error("Camera retry failed: " + e2.getMessage());
            }
        }
    }
    
    /**
     * Releases OpenGL resources.
     */
    private void releaseGl() {
        if (cameraSurfaceTexture != null) {
            cameraSurfaceTexture.release();
            cameraSurfaceTexture = null;
        }
        
        if (cameraSurface != null) {
            cameraSurface.release();
            cameraSurface = null;
        }
        
        if (cameraTextureId != 0) {
            GlUtil.deleteTexture(cameraTextureId);
            cameraTextureId = 0;
        }
        
        if (dummySurface != null) {
            eglCore.destroySurface(dummySurface);
            dummySurface = null;
        }
        
        if (eglCore != null) {
            eglCore.release();
            eglCore = null;
        }
        
        logger.info( "OpenGL resources released");
    }
    
    /**
     * Sets streaming components for parallel GPU path.
     * 
     * @param streamScaler GPU stream scaler
     * @param streamEncoder Stream encoder
     */
    public void setStreamingComponents(com.overdrive.app.streaming.GpuStreamScaler streamScaler,
                                      HardwareEventRecorderGpu streamEncoder) {
        this.streamScaler = streamScaler;
        this.streamEncoder = streamEncoder;
    }
    
    /**
     * Clears streaming components (called when streaming is disabled).
     * This prevents the render loop from trying to use released surfaces.
     */
    public void clearStreamingComponents() {
        this.streamScaler = null;
        this.streamEncoder = null;
    }
    
    /**
     * Gets the GL thread handler for posting operations.
     * 
     * @return Handler for GL thread
     */
    public Handler getGlHandler() {
        return glHandler;
    }
    
    /**
     * Checks if the camera is running.
     * 
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Sets the AVMCamera surface mode for addPreviewSurface().
     * Must be called before start(). Default is 0 (works on Seal).
     * Atto 1 may need mode 1 for processed panoramic output.
     */
    public void setCameraSurfaceMode(int mode) {
        this.cameraSurfaceMode = mode;
        logger.info("Camera surface mode set to: " + mode);
    }
    
    /**
     * Gets the current camera surface mode.
     */
    public int getCameraSurfaceMode() {
        return cameraSurfaceMode;
    }
    
    /**
     * Sets the AVMCamera ID to use.
     * Must be called before start(). Default is 1 (works on Seal).
     * Dolphin/Atto 1 may need ID 0.
     */
    public void setCameraId(int id) {
        this.cameraIdOverride = id;
        logger.info("Camera ID override set to: " + id);
    }
    
    /**
     * Enables auto-probe mode: tries camera IDs 0-5 at startup to find
     * the one that produces actual image data. Logs resolution and pixel
     * content for each ID. Auto-selects the first panoramic (5120-wide) camera
     * with non-black frames.
     */
    public void setAutoProbeCameras(boolean enabled) {
        this.autoProbeCameras = enabled;
        logger.info("Camera auto-probe: " + (enabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * Sets a callback to be notified when auto-probe discovers a working camera.
     * The pipeline can use this to persist the result for faster restarts.
     */
    public void setCameraProbeCallback(CameraProbeCallback callback) {
        this.probeCallback = callback;
    }
    
    /**
     * Gets the timestamp of the last frame.
     * 
     * @return Timestamp in milliseconds
     */
    public long getLastFrameTime() {
        return lastFrameTime;
    }
    
    /**
     * SOTA: Gets the BYD camera coordinator for status queries.
     */
    public BydCameraCoordinator getCameraCoordinator() {
        return cameraCoordinator;
    }
    
    /**
     * SOTA: Sets the yield listener for recording finalization during camera yield.
     * The pipeline registers this to ensure recordings are properly closed before
     * the camera is released, and resumed after re-acquisition.
     */
    public void setCameraYieldListener(CameraYieldListener listener) {
        this.yieldListener = listener;
    }
    
    /**
     * SOTA: Returns true if camera is currently yielded to native BYD app.
     */
    public boolean isCameraYielded() {
        return cameraYielded;
    }
    
    /**
     * Gets the total frame count.
     * 
     * @return Frame count
     */
    public int getFrameCount() {
        return frameCounter;
    }
    
    /**
     * Gets the camera width.
     * 
     * @return Width in pixels
     */
    public int getWidth() {
        return width;
    }
    
    /**
     * Gets the camera height.
     * 
     * @return Height in pixels
     */
    public int getHeight() {
        return height;
    }
    
    /**
     * Gets the latest JPEG frame for a specific camera (for HTTP snapshot).
     * 
     * @param cameraId Camera ID (1-4)
     * @return JPEG byte array, or null if not available
     */
    public byte[] getLatestJpegFrame(int cameraId) {
        // This would need to be implemented by storing the latest extracted frame
        // For now, return null (MJPEG streaming handles this via callback)
        return null;
    }
    
    /**
     * Checks CPU usage and logs warning if exceeds threshold.
     * 
     * Provides breakdown by component to identify bottlenecks.
     */
    private void checkCpuUsage() {
        long now = System.currentTimeMillis();
        if (now - lastCpuCheckTime < CPU_CHECK_INTERVAL_MS) {
            return;
        }
        
        lastCpuCheckTime = now;
        
        try {
            // Read /proc/stat for total CPU time
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();
            
            // Parse CPU times
            String[] tokens = line.split("\\s+");
            long totalCpu = 0;
            for (int i = 1; i < tokens.length; i++) {
                totalCpu += Long.parseLong(tokens[i]);
            }
            
            // Read /proc/self/stat for process CPU time
            reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/stat"));
            line = reader.readLine();
            reader.close();
            
            tokens = line.split("\\s+");
            long processCpu = Long.parseLong(tokens[13]) + Long.parseLong(tokens[14]);
            
            // Calculate CPU percentage (simplified)
            // Note: This is a rough estimate. For accurate measurement, use
            // Android Profiler or systrace.
            // Logging disabled to reduce log spam - uncomment for debugging
            // logger.debug( String.format("CPU check: process=%d, total=%d", processCpu, totalCpu));
            
        } catch (Exception e) {
            // Silent fail - CPU monitoring is optional
        }
    }
}
