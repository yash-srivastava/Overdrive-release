package com.overdrive.app.daemon;

import android.os.Handler;
import android.os.Looper;

import com.overdrive.app.abrp.AbrpConfig;
import com.overdrive.app.abrp.AbrpTelemetryService;
import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.server.HttpServer;
import com.overdrive.app.server.SurveillanceIpcServer;
import com.overdrive.app.server.TcpCommandServer;

import com.overdrive.app.daemon.proxy.Safe;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main Camera Daemon - orchestrates all camera operations.
 * 
 * Runs as a standalone process via app_process:
 *   adb shell "CLASSPATH=/data/app/.../base.apk app_process / \
 *       com.overdrive.app.daemon.CameraDaemon [outputDir] [nativeLibDir]"
 * 
 * Components:
 * - TcpCommandServer: JSON commands on port 19876
 * - HttpServer: Web UI and H.264 streaming on port 8080
 * - PanoramicCamera: BYD panoramic camera access
 * - VirtualView: Per-camera view cropping and encoding
 * - AccMonitor: Sentry mode when ACC goes off
 */
public class CameraDaemon {

    private static final String TAG = "CameraDaemon";
    
    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** com.overdrive.app */
    private static String APP_PACKAGE_NAME() { return Safe.s("3Is1Ze/xWL6dkFvd9bF+deUGK/HqnInkSi6jinpc6s8="); }
    /** /data/local/tmp/cam_stream */
    private static String PATH_CAMERA_STREAM_DIR() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxzxuq9ag7mKGoQaOvzuwMDqM="); }
    /** /sdcard/DCIM/BYDCam */
    private static String PATH_CAMERA_OUTPUT_DIR() { return Safe.s("C6E+8XkzSNnhdgOIKBfVSXGyuhqY7qDiNp4pBP/hRuY="); }
    /** /data/local/tmp/stream_mode.txt */
    private static String PATH_STREAM_MODE_FILE() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz4A79W/sQd0NkqiGs/MIZWo="); }
    /** /data/local/tmp/.byd_device_id */
    private static String PATH_DEVICE_ID_FILE() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz8mvs/gQENVv3FEZ6OVKD54="); }
    
    // ==================== CONFIGURATION ====================
    public static final int TCP_PORT = 19876;
    public static final int HTTP_PORT = 8080;
    public static String STREAM_DIR() { return PATH_CAMERA_STREAM_DIR(); }
    public static final String APP_STREAM_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files/stream";
    
    // Recording config (full quality)
    public static final int PANO_WIDTH = 5120;
    public static final int PANO_HEIGHT = 960;
    public static final int VIEW_WIDTH = 1280;
    public static final int VIEW_HEIGHT = 960;
    public static final int FRAME_RATE = 25;
    public static final int BITRATE = 4_000_000;
    public static final int KEYFRAME_INTERVAL = 2;
    public static final long SEGMENT_DURATION_MS = 2 * 60 * 1000;
    
    // Streaming config (SIM-optimized)
    public static final int STREAM_WIDTH = 640;
    public static final int STREAM_HEIGHT = 480;
    public static final int STREAM_JPEG_QUALITY = 70;  // Increased from 40 for better quality
    public static final long STREAM_INTERVAL_MS = 100;
    
    // ==================== STATE ====================
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Handler mainHandler;
    private static String outputDir = null; // Initialized in main()
    private static String nativeLibDir = null; // Initialized in parseArguments()
    
    // ==================== LOGGING ====================
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // ==================== SERVERS ====================
    private static TcpCommandServer tcpServer;
    private static HttpServer httpServer;
    private static SurveillanceIpcServer ipcServer;
    private static AccMonitor accMonitor;
    
    // ==================== SURVEILLANCE ====================
    private static com.overdrive.app.surveillance.GpuSurveillancePipeline gpuPipeline;
    private static boolean surveillanceEnabled = false;
    private static volatile boolean safeZoneSuppressed = false;
    // Pending ACC OFF state: if ACC goes off before GPU pipeline is ready,
    // queue the request and apply it once the pipeline initializes
    private static volatile boolean pendingAccOff = false;
    
    // ==================== RECORDING MODE MANAGER ====================
    private static com.overdrive.app.recording.RecordingModeManager recordingModeManager;
    
    // ==================== STREAM MODE ====================
    public static final String STREAM_MODE_PRIVATE = "private";  // Local H.264 only
    public static final String STREAM_MODE_PUBLIC = "public";    // Tunnel access
    private static String streamMode = STREAM_MODE_PRIVATE;
    
    // ==================== DEVICE ID ====================
    private static String deviceId = "unknown";
    
    // ==================== ABRP TELEMETRY ====================
    private static AbrpTelemetryService abrpTelemetryService;
    private static com.overdrive.app.abrp.SohEstimator sohEstimator;
    
    // ==================== MQTT CONNECTIONS ====================
    private static com.overdrive.app.mqtt.MqttConnectionManager mqttConnectionManager;
    
    // ==================== TRIP ANALYTICS ====================
    private static com.overdrive.app.trips.TripAnalyticsManager tripAnalyticsManager;
    
    // ==================== TELEMETRY DATA COLLECTOR ====================
    private static com.overdrive.app.telemetry.TelemetryDataCollector telemetryDataCollector;
    
    // ==================== SHARED APP CONTEXT ====================
    private static android.content.Context sharedAppContext = null;
    
    /** Get the shared app context (for use by other components in this process). */
    public static android.content.Context getAppContext() { return sharedAppContext; }
    
    // Lock file for singleton enforcement
    private static final String LOCK_FILE = "/data/local/tmp/camera_daemon.lock";
    private static java.io.RandomAccessFile lockFile;
    private static java.nio.channels.FileLock fileLock;

    public static void main(String[] args) {
        initFileLogging();
        
        // CRITICAL: Acquire singleton lock FIRST - exit if another instance is running
        if (!acquireSingletonLock()) {
            log("ERROR: Another CameraDaemon instance is already running. Exiting.");
            System.exit(1);
            return;
        }
        
        // Enable daemon logging for StorageManager (uses DaemonLogger instead of android.util.Log)
        com.overdrive.app.storage.StorageManager.enableDaemonLogging();
        
        // SOTA: Fix storage permissions so UI app can read recordings
        // Note: StorageManager constructor will auto-mount SD card if configured
        com.overdrive.app.storage.StorageManager.getInstance().fixAllPermissions();
        
        log("=== CAMERA DAEMON STARTING ===");
        log("PID: " + android.os.Process.myPid() + ", UID: " + android.os.Process.myUid());

        // Global exception handler - NEVER let the daemon die from uncaught exceptions
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (!(throwable instanceof ThreadDeath)) {
                log("FATAL: Uncaught exception in " + thread.getName() + ": " + throwable.getMessage());
                if (throwable.getCause() != null) {
                    log("  Cause: " + throwable.getCause().getMessage());
                }
                // Log stack trace
                for (StackTraceElement element : throwable.getStackTrace()) {
                    log("    at " + element.toString());
                }
                // DO NOT kill the daemon - just log and continue
                // The daemon should stay alive even if individual operations fail
            }
        });

        if (Looper.myLooper() == null) Looper.prepare();
        mainHandler = new Handler(Looper.myLooper());

        // Parse arguments (sets outputDir if provided)
        parseArguments(args);
        
        // Initialize outputDir if not set by arguments
        if (outputDir == null) {
            outputDir = PATH_CAMERA_OUTPUT_DIR();
        }
        
        // Load native libraries
        loadNativeLibraries();
        
        // Create directories
        new File(outputDir).mkdirs();
        new File(STREAM_DIR()).mkdirs();
        new File(APP_STREAM_DIR).mkdirs();
        
        // Generate device ID
        generateDeviceId();
        
        log("Output dir: " + outputDir);
        log("Device ID: " + deviceId);
        
        // Camera scan disabled — opening/closing all camera IDs can briefly
        // disrupt the BYD dashcam. Camera ID is auto-detected in GpuSurveillancePipeline.init()
        // scanCameras();

        // Start servers
        tcpServer = new TcpCommandServer(TCP_PORT);
        httpServer = new HttpServer(HTTP_PORT);
        ipcServer = new SurveillanceIpcServer(19877);
        accMonitor = new AccMonitor();
        
        // SOTA: Initialize unified config manager (handles migration from legacy configs)
        com.overdrive.app.config.UnifiedConfigManager.init();
        
        // Load persisted quality settings BEFORE initializing surveillance
        // This ensures the encoder is created with the correct settings
        HttpServer.loadPersistedSettings();
        
        // Initialize surveillance module (will use loaded settings)
        initSurveillance();
        
        // Apply persisted settings to GPU pipeline (for runtime changes)
        // Note: Codec/bitrate are already applied during init, but this ensures
        // the config object is in sync and handles any settings that need runtime application
        applyPersistedSettings();
        
        // If ACC went OFF before pipeline was ready, apply it now
        if (pendingAccOff && gpuPipeline != null) {
            log("Applying pending ACC OFF surveillance request...");
            pendingAccOff = false;
            onAccStateChanged(true);
        }
        
        new Thread(tcpServer::start, "TcpServer").start();
        new Thread(httpServer::start, "HttpServer").start();
        new Thread(ipcServer, "SurveillanceIPC").start();
        new Thread(accMonitor::start, "AccMonitor").start();
        
        // Initialize GPS monitor with app context for standard LocationManager access
        initGpsMonitor();

        // Initialize Safe Location Manager (geofence zones)
        com.overdrive.app.surveillance.SafeLocationManager.getInstance().init();

        // Initialize SohEstimator (load persisted SOH — capacity detection deferred until collector is ready)
        try {
            sohEstimator = new SohEstimator();
            sohEstimator.init();
        } catch (Exception e) {
            log("SohEstimator init error: " + e.getMessage());
        }

        // Initialize Vehicle Data Monitor + BydDataCollector
        initVehicleDataMonitor();

        // Now that BydDataCollector is ready, detect car model for accurate capacity
        try {
            if (sohEstimator != null) {
                sohEstimator.autoDetectCarModel(sharedAppContext);
                sohEstimator.seedInitialEstimate();
                log("SohEstimator: " + (sohEstimator.hasEstimate() ? sohEstimator.getCurrentSoh() + "%" : "no estimate") +
                    " (capacity: " + sohEstimator.getNominalCapacityKwh() + " KWh)");
            }
        } catch (Exception e) {
            log("SohEstimator autoDetect error: " + e.getMessage());
        }

        // Initialize ABRP Telemetry Service
        try {
            log("Initializing ABRP telemetry...");
            AbrpConfig abrpConfig = new AbrpConfig();
            abrpConfig.load();
            
            // Auto-set car_model in ABRP config if not already set
            if (sohEstimator != null && (abrpConfig.getCarModel() == null || abrpConfig.getCarModel().isEmpty())) {
                double cap = sohEstimator.getNominalCapacityKwh();
                String model = capacityToModelName(cap);
                if (model != null) {
                    abrpConfig.setCarModel(model);
                    abrpConfig.save();
                    log("Auto-detected car model for ABRP: " + model + " (" + cap + " KWh)");
                }
            }
            
            abrpTelemetryService = new AbrpTelemetryService(abrpConfig, sohEstimator);
            abrpTelemetryService.init(sharedAppContext);
            
            // Set IPC references so SurveillanceIpcServer can access ABRP
            SurveillanceIpcServer.setAbrpReferences(abrpConfig, abrpTelemetryService);
            
            if (abrpConfig.isEnabled() && abrpConfig.isConfigured()) {
                abrpTelemetryService.start();
                log("ABRP telemetry started (token: " + abrpConfig.getMaskedToken() + ")");
            } else {
                log("ABRP telemetry not started (enabled=" + abrpConfig.isEnabled() + ", configured=" + abrpConfig.isConfigured() + ")");
            }
        } catch (Exception e) {
            log("ABRP init error: " + e.getMessage());
        }

        // Initialize MQTT Connection Manager
        try {
            log("Initializing MQTT connections...");
            mqttConnectionManager = new com.overdrive.app.mqtt.MqttConnectionManager();
            mqttConnectionManager.init(deviceId, sohEstimator);

            // Set IPC reference so SurveillanceIpcServer can access MQTT
            SurveillanceIpcServer.setMqttManager(mqttConnectionManager);

            // Start all enabled connections
            mqttConnectionManager.startAll();
            log("MQTT initialized (" + mqttConnectionManager.getActiveCount() + " active connections)");
        } catch (Exception e) {
            log("MQTT init error: " + e.getMessage());
        }

        // Initialize Trip Analytics
        try {
            log("Initializing Trip Analytics...");
            tripAnalyticsManager = new com.overdrive.app.trips.TripAnalyticsManager();
            tripAnalyticsManager.init(sharedAppContext, telemetryDataCollector, sohEstimator);
            log("Trip Analytics initialized (enabled=" + tripAnalyticsManager.isEnabled() + ")");

            // Clear poisoned consumption buckets if this is a PHEV
            // (old trips may have been recorded with wrong nominal capacity)
            if (sohEstimator != null && sohEstimator.getNominalCapacityKwh() > 0
                    && sohEstimator.getNominalCapacityKwh() < 30.0
                    && tripAnalyticsManager.getDatabase() != null) {
                tripAnalyticsManager.getDatabase().clearConsumptionBuckets();
            }

            // AUTO-START: If gear is already in a driving position (not P), start trip
            // recording immediately. This handles the case where CameraDaemon restarts
            // mid-drive (e.g., EGL crash watchdog, manual restart) or starts after the
            // driver has already shifted out of P.
            if (tripAnalyticsManager.isEnabled()) {
                try {
                    int currentGear = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear();
                    if (currentGear != com.overdrive.app.monitor.GearMonitor.GEAR_P) {
                        log("Trip Analytics: non-P gear detected at startup (gear="
                                + com.overdrive.app.monitor.GearMonitor.gearToString(currentGear)
                                + ") — auto-starting trip recording");
                        tripAnalyticsManager.onGearChanged(currentGear);
                    }
                } catch (Exception e) {
                    log("Trip Analytics gear probe error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log("Trip Analytics init error: " + e.getMessage());
        }

        // Initialize OdometerReader for trip distance
        try {
            com.overdrive.app.trips.OdometerReader.getInstance().init(sharedAppContext);
        } catch (Exception e) {
            log("OdometerReader init error: " + e.getMessage());
        }

        // Restore stream mode from previous session
        loadStreamMode();

        // RECOVERY: Probe ACC state directly from hardware.
        // If CameraDaemon was restarted (e.g., EGL crash watchdog) while ACC was off,
        // AccSentryDaemon won't re-send the ACC OFF command. Reading the hardware
        // directly has zero dependency on AccSentryDaemon.
        try {
            boolean accIsOff = com.overdrive.app.monitor.AccMonitor.probeAccState(sharedAppContext);
            if (accIsOff) {
                log("RECOVERY: Hardware probe shows ACC OFF — entering sentry mode");
                onAccStateChanged(true);  // true = accIsOff
            }
        } catch (Exception e) {
            log("ACC hardware probe error: " + e.getMessage());
        }

        log("Daemon ready on TCP:" + TCP_PORT + " HTTP:" + HTTP_PORT);
        
        // RESILIENT LOOPER: BYD framework listeners (gearbox, bodywork, etc.) can throw
        // uncaught exceptions from their internal processing (e.g., learningEPB → CarSettings
        // UID mismatch). These exceptions escape through Handler.dispatchMessage and kill
        // Looper.loop(). Wrapping in a retry loop keeps the daemon alive.
        while (running.get()) {
            try {
                Looper.loop();
                // Looper.loop() only returns if someone calls quit()
                break;
            } catch (Throwable t) {
                log("LOOPER CRASH (recovered): " + t.getClass().getSimpleName() + ": " + t.getMessage());
                if (t.getCause() != null) {
                    log("  Cause: " + t.getCause().getMessage());
                }
                // Log first 5 stack frames
                StackTraceElement[] stack = t.getStackTrace();
                for (int i = 0; i < Math.min(5, stack.length); i++) {
                    log("    at " + stack[i].toString());
                }
                // Continue looping — the Looper is still valid, just the current message failed
            }
        }
    }
    
    /**
     * Applies persisted settings to the GPU pipeline after initialization.
     */
    private static void applyPersistedSettings() {
        if (gpuPipeline == null) return;
        
        try {
            // Apply bitrate setting to config and encoder
            String bitrate = HttpServer.getRecordingBitrate();
            if (bitrate != null) {
                setRecordingBitrate(bitrate);
                log("Applied persisted bitrate: " + bitrate);
            }
            
            // Apply codec setting to config (encoder already created with this codec)
            String codec = HttpServer.getRecordingCodec();
            if (codec != null) {
                // Just update the config, don't reinitialize encoder
                com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec videoCodec;
                switch (codec.toUpperCase()) {
                    case "H265":
                    case "HEVC":
                        videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265;
                        break;
                    case "H264":
                    case "AVC":
                    default:
                        videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;
                        break;
                }
                gpuPipeline.getConfig().setVideoCodec(videoCodec);
                log("Applied persisted codec: " + codec);
            }
            
            // Apply quality settings
            String recQuality = HttpServer.getRecordingQuality();
            if (recQuality != null) {
                setRecordingQuality(recQuality);
                log("Applied persisted recording quality: " + recQuality);
            }
            
            String streamQuality = HttpServer.getStreamingQuality();
            if (streamQuality != null) {
                setStreamingQuality(streamQuality);
                log("Applied persisted streaming quality: " + streamQuality);
            }
        } catch (Exception e) {
            log("Error applying persisted settings: " + e.getMessage());
        }
    }

    // ==================== CAMERA MANAGEMENT ====================
    
    public static void startCamera(int viewId, boolean enableStreaming, boolean viewOnly) {
        if (viewId < 1 || viewId > 4) {
            log("ERROR: Invalid view ID: " + viewId);
            return;
        }
        
        log("Starting camera " + viewId + " (GPU mosaic recording, viewOnly=" + viewOnly + ")");
        
        // GPU pipeline handles all cameras together
        if (gpuPipeline != null && !gpuPipeline.isRunning()) {
            try {
                // Start pipeline with auto-recording if not view-only
                gpuPipeline.start(!viewOnly);
                log("GPU pipeline started for camera " + viewId);
                
                if (!viewOnly) {
                    log("Auto-recording enabled (will start when recorder ready)");
                } else {
                    log("View-only mode - recording NOT started");
                }
                
            } catch (Exception e) {
                log("ERROR: Failed to start GPU pipeline: " + e.getMessage());
            }
        } else if (gpuPipeline != null && gpuPipeline.isRunning()) {
            // Pipeline already running - start recording if requested (stops surveillance)
            if (!viewOnly) {
                log("Pipeline already running - starting normal recording (stops surveillance if active)");
                gpuPipeline.startRecording();
            } else {
                log("Pipeline already running for camera " + viewId + " (view-only)");
            }
        }
    }

    public static void stopCamera(int viewId) {
        stopCamera(viewId, false);
    }
    
    /**
     * Stop a camera view.
     * @param viewId The view ID (1-4)
     * @param forceStop If true, stops even if recording. If false, only stops if not recording.
     */
    public static void stopCamera(int viewId, boolean forceStop) {
        try {
            log("Stopping camera " + viewId + " (GPU pipeline)");
            
            // GPU pipeline handles all cameras
            // Only stop if forcing
            if (forceStop && gpuPipeline != null) {
                gpuPipeline.stop();
                log("GPU pipeline stopped");
            }
        } catch (Exception e) {
            log("ERROR: Exception in stopCamera(" + viewId + "): " + e.getMessage());
        }
    }
    
    /**
     * Force stop a camera, even if recording.
     * Use this when user explicitly wants to stop everything.
     */
    public static void forceStopCamera(int viewId) {
        stopCamera(viewId, true);
    }

    public static void stopAllCameras() {
        stopAllCameras(true);
    }
    
    /**
     * Stop all cameras.
     * @param forceStop If true, stops all cameras. If false, only stops non-recording cameras.
     */
    public static void stopAllCameras(boolean forceStop) {
        log("Stopping all cameras (GPU pipeline, force=" + forceStop + ")");
        if (forceStop && gpuPipeline != null) {
            gpuPipeline.stop();
        }
    }
    
    
    // GPU pipeline handles camera internally - no separate camera management needed
    
    // ==================== GETTERS ====================
    
    public static java.util.Map<Integer, Object> getVirtualViews() {
        // GPU pipeline doesn't use VirtualView - return empty map for compatibility
        return new java.util.HashMap<>();
    }
    
    public static boolean isRunning() {
        return running.get();
    }
    
    public static void shutdown() {
        log("Shutdown requested...");
        stopAllCameras();
        running.set(false);
        
        if (tripAnalyticsManager != null) tripAnalyticsManager.shutdown();
        if (abrpTelemetryService != null) abrpTelemetryService.stop();
        if (mqttConnectionManager != null) mqttConnectionManager.stopAll();
        if (tcpServer != null) tcpServer.stop();
        if (httpServer != null) httpServer.stop();
        if (ipcServer != null) ipcServer.stop();
        
        // Release singleton lock
        releaseSingletonLock();
        
        log("Daemon killing self");
        android.os.Process.killProcess(android.os.Process.myPid());
    }
    
    /**
     * Acquire a file lock to ensure only one daemon instance runs at a time.
     * Uses Java NIO FileLock which is process-safe.
     */
    private static boolean acquireSingletonLock() {
        try {
            File lockFileObj = new File(LOCK_FILE);
            lockFile = new java.io.RandomAccessFile(lockFileObj, "rw");
            java.nio.channels.FileChannel channel = lockFile.getChannel();
            
            // Try to acquire exclusive lock (non-blocking)
            fileLock = channel.tryLock();
            
            if (fileLock == null) {
                // Another process holds the lock — check if it's actually alive.
                // We treat "holder PID is dead", "holder PID is missing/corrupt",
                // and "holder PID is our own PID" all as stale-lock cases, because
                // each one means no live daemon owns the lock.
                boolean stale = false;
                String reason = null;
                try {
                    lockFile.seek(0);
                    String pidStr = lockFile.readLine();
                    int myPid = android.os.Process.myPid();
                    if (pidStr == null || pidStr.trim().isEmpty()) {
                        stale = true;
                        reason = "empty lock file";
                    } else {
                        int pid = Integer.parseInt(pidStr.trim());
                        if (pid == myPid) {
                            stale = true;
                            reason = "lock held by our own PID (previous crash)";
                        } else if (!new File("/proc/" + pid).exists()) {
                            stale = true;
                            reason = "dead PID " + pid;
                        } else {
                            // Live process holds the lock — real conflict.
                            log("Singleton: live daemon PID " + pid + " holds the lock");
                            lockFile.close();
                            return false;
                        }
                    }
                } catch (NumberFormatException nfe) {
                    stale = true;
                    reason = "corrupt PID in lock file";
                } catch (Exception e) {
                    lockFile.close();
                    return false;
                }
                
                if (stale) {
                    log("Singleton: stale lock (" + reason + ") — cleaning up");
                    try { lockFile.close(); } catch (Exception ignored) {}
                    lockFileObj.delete();
                    
                    // Small delay so the kernel releases the inode lock before retry
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    
                    // Retry lock acquisition on the new inode
                    lockFile = new java.io.RandomAccessFile(lockFileObj, "rw");
                    channel = lockFile.getChannel();
                    fileLock = channel.tryLock();
                    
                    if (fileLock == null) {
                        log("Singleton: retry after stale-lock cleanup still failed");
                        try { lockFile.close(); } catch (Exception ignored) {}
                        return false;
                    }
                    // Fall through to write PID and register shutdown hook
                }
            }
            
            // Write our PID to the lock file for debugging
            lockFile.seek(0);
            lockFile.setLength(0);
            lockFile.writeBytes(String.valueOf(android.os.Process.myPid()));
            
            log("Acquired singleton lock (PID: " + android.os.Process.myPid() + ")");
            
            // Register shutdown hook to release lock on process termination
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                releaseSingletonLock();
            }));
            
            return true;
            
        } catch (java.nio.channels.OverlappingFileLockException e) {
            // Lock already held by this JVM (shouldn't happen but handle it)
            log("Lock already held by this process");
            return false;
        } catch (Exception e) {
            // Don't fall back to port checks — TCP sockets linger in TIME_WAIT
            // long after the daemon dies and would cause spurious "already
            // running" decisions during a fast retry loop. If we can't take
            // the lock, admit defeat and let the watchdog back off.
            log("Failed to acquire singleton lock: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Release the singleton lock on shutdown.
     */
    private static void releaseSingletonLock() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            if (lockFile != null) {
                lockFile.close();
                lockFile = null;
            }
            // Delete lock file
            new File(LOCK_FILE).delete();
            log("Released singleton lock");
        } catch (Exception e) {
            log("Error releasing singleton lock: " + e.getMessage());
        }
    }
    
    /**
     * Check if a port is already in use (fallback check).
     */
    private static boolean isPortInUse(int port) {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(port)) {
            socket.setReuseAddress(true);
            return false;
        } catch (java.io.IOException e) {
            return true;
        }
    }
    
    public static Handler getMainHandler() {
        return mainHandler;
    }
    
    public static String getOutputDir() {
        return outputDir;
    }
    
    public static String getDeviceId() {
        return deviceId;
    }
    
    public static com.overdrive.app.trips.TripAnalyticsManager getTripAnalyticsManager() {
        return tripAnalyticsManager;
    }
    
    // ==================== STREAMING CONTROL (REMOVED - VPS functionality removed) ====================
    
    /**
     * Start streaming a camera (DISABLED - VPS streaming removed).
     */
    public static void startStreaming(int viewId) {
        log("startStreaming(" + viewId + ") - VPS streaming removed, use local HTTP streaming instead");
    }
    
    /**
     * Stop streaming a camera (DISABLED - VPS streaming removed).
     */
    public static void stopStreaming(int viewId) {
        log("stopStreaming(" + viewId + ") - VPS streaming removed");
    }
    
    /**
     * Start streaming all cameras (DISABLED - VPS streaming removed).
     */
    public static void startAllStreaming() {
        log("startAllStreaming() - VPS streaming removed, use local HTTP streaming instead");
    }
    
    /**
     * Stop all streaming (DISABLED - VPS streaming removed).
     */
    public static void stopAllStreaming() {
        log("stopAllStreaming() - VPS streaming removed");
    }
    
    /**
     * Check if streaming is enabled (always false - VPS streaming removed).
     */
    public static boolean isStreamingEnabled() {
        return false;
    }
    
    /**
     * Get list of cameras currently streaming (empty - VPS streaming removed).
     */
    public static java.util.List<Integer> getStreamingCameras() {
        return new java.util.ArrayList<>();
    }
    
    // ==================== SURVEILLANCE CONTROL ====================
    
    /**
     * Initialize surveillance with hardware encoding.
     * CPU usage: ~20% during recording
     */
    private static void initSurveillance() {
        try {
            log("Initializing GPU Surveillance Pipeline...");
            
            // SOTA: Use StorageManager for surveillance output directory
            com.overdrive.app.storage.StorageManager storageManager =
                com.overdrive.app.storage.StorageManager.getInstance();
            File eventDir = storageManager.getSurveillanceDir();
            
            // Create GPU pipeline
            gpuPipeline = new com.overdrive.app.surveillance.GpuSurveillancePipeline(
                PANO_WIDTH, PANO_HEIGHT, eventDir);
            
            // Get AssetManager from the app's APK
            // Since we're running as app_process, load model from filesystem
            android.content.res.AssetManager assetManager = null;
            try {
                // Try to create AssetManager from APK path
                String classpath = System.getenv("CLASSPATH");
                log("CLASSPATH: " + classpath);
                
                // Extract the app APK path (not framework jars)
                String apkPath = null;
                if (classpath != null) {
                    String[] paths = classpath.split(":");
                    for (String path : paths) {
                        if (path.contains("com.overdrive.app") && path.endsWith(".apk")) {
                            apkPath = path;
                            break;
                        }
                    }
                }
                
                if (apkPath != null) {
                    android.content.res.AssetManager mgr = android.content.res.AssetManager.class.newInstance();
                    java.lang.reflect.Method addAssetPath = android.content.res.AssetManager.class
                        .getDeclaredMethod("addAssetPath", String.class);
                    int cookie = (Integer) addAssetPath.invoke(mgr, apkPath);
                    
                    if (cookie != 0) {
                        assetManager = mgr;
                        log("AssetManager created from APK: " + apkPath);
                        
                        // Extract web assets for HTTP server
                        HttpServer.extractWebAssets(assetManager);
                    } else {
                        log("Failed to add asset path (cookie=0)");
                    }
                } else {
                    log("Could not find app APK in CLASSPATH");
                }
            } catch (Exception e) {
                log("Could not create AssetManager: " + e.getMessage());
                e.printStackTrace();
            }
            
            // Apply persisted settings to config BEFORE init
            // IMPORTANT: Set codec FIRST, then bitrate (so bitrate is calculated for correct codec)
            String persistedCodec = HttpServer.getRecordingCodec();
            if (persistedCodec != null) {
                com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec videoCodec;
                switch (persistedCodec.toUpperCase()) {
                    case "H265":
                    case "HEVC":
                        videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265;
                        break;
                    case "H264":
                    case "AVC":
                    default:
                        videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;
                        break;
                }
                gpuPipeline.getConfig().setVideoCodec(videoCodec);
                log("Pre-init: Set codec to " + persistedCodec);
            }
            
            String persistedBitrate = HttpServer.getRecordingBitrate();
            if (persistedBitrate != null) {
                com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset preset;
                switch (persistedBitrate.toUpperCase()) {
                    case "LOW":
                        preset = com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset.LOW;
                        break;
                    case "HIGH":
                        preset = com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset.HIGH;
                        break;
                    case "MEDIUM":
                    default:
                        preset = com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset.MEDIUM;
                        break;
                }
                gpuPipeline.getConfig().setBitratePreset(preset);
                int effectiveBitrate = gpuPipeline.getConfig().getEffectiveBitrate();
                log("Pre-init: Set bitrate to " + persistedBitrate + " (" + effectiveBitrate / 1_000_000 + " Mbps for " +
                    gpuPipeline.getConfig().getVideoCodec() + ")");
            }
            
            gpuPipeline.init(assetManager);
            
            log("GPU Surveillance initialized: " + PANO_WIDTH + "x" + PANO_HEIGHT + 
                " -> 2560x1920 (mosaic)");
            
            // Clean up orphaned .tmp files from previous crashed recordings
            try {
                com.overdrive.app.storage.StorageManager sm = com.overdrive.app.storage.StorageManager.getInstance();
                com.overdrive.app.surveillance.HardwareEventRecorderGpu.cleanupOrphanedTmpFiles(sm.getRecordingsDir());
                com.overdrive.app.surveillance.HardwareEventRecorderGpu.cleanupOrphanedTmpFiles(sm.getSurveillanceDir());
            } catch (Exception e) {
                log("Tmp cleanup error: " + e.getMessage());
            }
            
            // Initialize TelemetryDataCollector for overlay (needs app context)
            // Moved after RecordingModeManager init since sharedAppContext may not exist yet
            
            // Initialize RecordingModeManager
            if (sharedAppContext == null) {
                sharedAppContext = createAppContext();
            }
            if (sharedAppContext != null) {
                recordingModeManager = new com.overdrive.app.recording.RecordingModeManager(
                    sharedAppContext, gpuPipeline);
                log("RecordingModeManager initialized");
                
                // Now initialize TelemetryDataCollector (context is guaranteed available)
                try {
                    telemetryDataCollector =
                        new com.overdrive.app.telemetry.TelemetryDataCollector();
                    telemetryDataCollector.init(sharedAppContext);
                    gpuPipeline.setTelemetryCollector(telemetryDataCollector);
                    
                    // Apply persisted overlay enabled state
                    boolean overlayEnabled = com.overdrive.app.config.UnifiedConfigManager
                        .getTelemetryOverlay().optBoolean("enabled", false);
                    gpuPipeline.setOverlayEnabled(overlayEnabled);
                    log("TelemetryDataCollector initialized, overlay=" + overlayEnabled);
                    
                    // Late-bind TelemetryDataCollector to TripAnalyticsManager
                    // (it was null when TripAnalytics was initialized before the 45s GPU delay)
                    if (tripAnalyticsManager != null) {
                        tripAnalyticsManager.setTelemetryDataCollector(telemetryDataCollector);
                        log("TelemetryDataCollector bound to TripAnalyticsManager");
                    }
                } catch (Exception e) {
                    log("WARNING: TelemetryDataCollector init failed: " + e.getMessage());
                }
            } else {
                log("WARNING: Could not create app context for RecordingModeManager");
            }
            
        } catch (Exception e) {
            log("ERROR: GPU Surveillance init failed: " + e.getMessage());
            log("ERROR: Exception type: " + e.getClass().getName());
            if (e.getCause() != null) {
                log("ERROR: Caused by: " + e.getCause().getMessage());
            }
            // Print stack trace to logcat
            e.printStackTrace();
            gpuPipeline = null;
        }
    }
    
    /**
     * Enable surveillance mode.
     */
    public static void enableSurveillance() {
        if (gpuPipeline == null) {
            log("GPU pipeline not ready — queuing surveillance enable for when pipeline initializes");
            pendingAccOff = true;
            return;
        }
        
        // SOTA: Safe Location check — don't start camera if parked at safe zone
        com.overdrive.app.surveillance.SafeLocationManager safeMgr =
            com.overdrive.app.surveillance.SafeLocationManager.getInstance();
        if (safeMgr.isInSafeZone()) {
            log("SAFE ZONE: Surveillance suppressed — " + safeMgr.getCurrentZoneName()
                + " (dist=" + Math.round(safeMgr.getDistanceToNearestZone()) + "m)");
            surveillanceEnabled = true;   // Mark intent so it auto-starts when leaving zone
            safeZoneSuppressed = true;
            return;  // Camera never opens. Zero resources.
        }
        
        log("Enabling GPU surveillance (pipeline=" + (gpuPipeline != null) + 
            ", running=" + (gpuPipeline != null && gpuPipeline.isRunning()) +
            ", sentry=" + (gpuPipeline != null && gpuPipeline.getSentry() != null) + ")");
        surveillanceEnabled = true;
        safeZoneSuppressed = false;
        
        try {
            if (!gpuPipeline.isRunning()) {
                log("Pipeline not running — starting...");
                gpuPipeline.start();
            }
            // Enable surveillance mode (motion detection)
            gpuPipeline.enableSurveillance();
            log("Surveillance mode activated successfully");
        } catch (Exception e) {
            log("ERROR: Failed to enable surveillance: " + e.getMessage());
        }
    }
    
    /**
     * Ensure camera is running for surveillance (called by SurveillanceEngine when it becomes active).
     * This avoids circular calls between CameraDaemon and SurveillanceEngine.
     */
    public static void ensureCameraForSurveillance() {
        log("ensureCameraForSurveillance called");
        surveillanceEnabled = true;
        enableSurveillance();
    }
    
    /**
     * Disable surveillance mode.
     */
    public static void disableSurveillance() {
        log("Disabling surveillance mode");
        surveillanceEnabled = false;
        
        if (gpuPipeline != null) {
            gpuPipeline.disableSurveillance();
            // Keep pipeline running for potential streaming
        }
    }
    
    /**
     * Notify surveillance of ACC state change.
     * 
     * ACC OFF (sentry mode): Start pipeline with surveillance enabled
     * ACC ON (normal mode): Stop pipeline completely to save power
     */
    public static void onAccStateChanged(boolean accIsOff) {
        // Update AccMonitor state for HTTP API responses
        com.overdrive.app.monitor.AccMonitor.setAccState(!accIsOff);
        
        // ALWAYS notify TripAnalyticsManager regardless of GPU pipeline state.
        // Trip detection depends on ACC events and must not be blocked by pipeline readiness.
        if (tripAnalyticsManager != null) {
            try {
                if (accIsOff) {
                    tripAnalyticsManager.onAccOff();
                } else {
                    tripAnalyticsManager.onAccOn();
                }
            } catch (Exception e) {
                log("Trip Analytics ACC " + (accIsOff ? "OFF" : "ON") + " error: " + e.getMessage());
            }
        }
        
        if (gpuPipeline == null) {
            if (accIsOff) {
                log("ACC OFF but GPU pipeline not ready — queuing for when pipeline initializes");
                pendingAccOff = true;
            } else {
                log("ACC ON but GPU pipeline not ready — clearing pending state");
                pendingAccOff = false;
            }
            return;
        }
        
        log("ACC state changed: " + (accIsOff ? "OFF (entering sentry)" : "ON (exiting sentry)"));
        
        if (accIsOff) {
            // ACC OFF - Start pipeline for sentry mode
            try {
                // CRITICAL: Notify RecordingModeManager FIRST so it can finalize any
                // active continuous/drive-mode recording segment before we transition
                // to surveillance. Without this, the last recording segment is lost
                // when surveillance is disabled or suppressed by safe zone (early returns
                // below skip enableSurveillance which was the only path that stopped recording).
                if (recordingModeManager != null) {
                    log("ACC OFF - notifying RecordingModeManager to finalize active recording...");
                    recordingModeManager.onAccStateChanged(false);
                }
                
                // CRITICAL: Force-stop TelemetryDataCollector when ACC goes off.
                // No consumer needs it when the car is off (no overlay, no trip recording).
                // This prevents refcount leaks from keeping the poller alive during sentry mode.
                if (telemetryDataCollector != null) {
                    telemetryDataCollector.setOverlayRecordingActive(false);
                    telemetryDataCollector.forceStopPolling();
                    log("TelemetryDataCollector force-stopped (ACC OFF)");
                }
                
                // Stop GearMonitor polling — gear is always P when ACC is off.
                // It will be restarted on ACC ON.
                com.overdrive.app.monitor.GearMonitor.getInstance().stop();
                log("GearMonitor stopped (ACC OFF)");
                
                // Tell BydDataCollector to skip speed/engine/gearbox polling (always 0 when parked)
                com.overdrive.app.byd.BydDataCollector.getInstance().setAccState(false);
                
                // CRITICAL: FORCE remount SD card when ACC goes off — BEFORE any early returns.
                // Even if surveillance is disabled or suppressed by safe zone, the SD card must stay
                // mounted so the HTTP server can serve existing recordings/events/trips.
                // Android/BYD system unmounts SD card when ACC is off, so we MUST force remount.
                com.overdrive.app.storage.StorageManager storage = 
                    com.overdrive.app.storage.StorageManager.getInstance();
                boolean anyStorageOnSd = 
                    storage.getSurveillanceStorageType() == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD ||
                    storage.getRecordingsStorageType() == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD ||
                    storage.getTripsStorageType() == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD;
                if (anyStorageOnSd) {
                    log("FORCE mounting SD card (ACC OFF, SD card configured for storage)...");
                    if (storage.ensureSdCardMounted(true)) {
                        log("SD card force mounted");
                    } else {
                        log("WARNING: SD card mount failed - using internal storage");
                    }
                    // Start watchdog to keep SD card mounted while ACC is off.
                    // BYD system may repeatedly unmount it — watchdog keeps it alive
                    // so recordings/events/trips remain accessible via HTTP.
                    storage.startSdCardWatchdog();
                }
                
                // Check if user has enabled surveillance in config
                boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
                if (!userEnabled) {
                    log("Surveillance NOT enabled in config — skipping auto-start on ACC OFF");
                    return;  // SD card is mounted + watchdog running
                }
                
                // Safe zone check — don't start surveillance if parked at home/work
                com.overdrive.app.surveillance.SafeLocationManager safeMgr =
                    com.overdrive.app.surveillance.SafeLocationManager.getInstance();
                if (safeMgr.isInSafeZone()) {
                    log("SAFE ZONE: Surveillance suppressed on ACC OFF — " + safeMgr.getCurrentZoneName()
                        + " (dist=" + Math.round(safeMgr.getDistanceToNearestZone()) + "m)");
                    surveillanceEnabled = true;   // Mark intent so it auto-starts when leaving zone
                    safeZoneSuppressed = true;
                    return;  // SD card is mounted + watchdog running, just skip surveillance
                }
                
                if (!gpuPipeline.isRunning()) {
                    log("Starting pipeline for sentry mode...");
                    gpuPipeline.start();
                }
                gpuPipeline.setRecordingMode(
                    com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode.SENTRY);
                // NOTE: Do NOT call gpuPipeline.enableSurveillance() here.
                // Surveillance is enabled by AccSentryDaemon after the door lock gate
                // (registerDoorLockListenerAndArmOnLock). Enabling here would bypass
                // the door lock check and cause a double-enable when AccSentryDaemon
                // sends its own enable IPC.
                log("Pipeline started in sentry mode (surveillance will be armed by AccSentryDaemon)");
                
                // FALLBACK: If AccSentryDaemon doesn't arm surveillance within 45s
                // (e.g., door lock API broken, IPC failed, daemon not running),
                // arm it directly from CameraDaemon. This prevents the car sitting
                // with no motion detection indefinitely.
                final long SURVEILLANCE_ARM_FALLBACK_MS = 45_000;
                new Thread(() -> {
                    try {
                        Thread.sleep(SURVEILLANCE_ARM_FALLBACK_MS);
                        if (!surveillanceEnabled && gpuPipeline != null && !gpuPipeline.isSurveillanceMode()) {
                            log("FALLBACK: AccSentryDaemon did not arm surveillance within " + 
                                (SURVEILLANCE_ARM_FALLBACK_MS / 1000) + "s — arming directly");
                            enableSurveillance();
                        }
                    } catch (InterruptedException ignored) {}
                }, "SurveillanceArmFallback").start();
                
                log("Pipeline started in sentry mode");
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null) {
                    errorMsg = e.getClass().getSimpleName();
                }
                log("ERROR: Failed to start pipeline for sentry: " + errorMsg);
                e.printStackTrace();
            }
        } else {
            // ACC ON - Stop SD card watchdog (system manages SD card normally when ACC is on)
            com.overdrive.app.storage.StorageManager.getInstance().stopSdCardWatchdog();
            
            // Restart GearMonitor (stopped on ACC OFF)
            com.overdrive.app.monitor.GearMonitor gearMonitor = com.overdrive.app.monitor.GearMonitor.getInstance();
            if (!gearMonitor.isRunning()) {
                gearMonitor.start();
                log("GearMonitor restarted (ACC ON)");
            }
            
            // Tell BydDataCollector to resume full polling (speed/engine/gearbox)
            com.overdrive.app.byd.BydDataCollector.getInstance().setAccState(true);
            
            // Notify RecordingModeManager — it handles stopping surveillance pipeline
            // and starting recording mode
            log("ACC ON - notifying RecordingModeManager...");
            if (recordingModeManager != null) {
                recordingModeManager.onAccStateChanged(true);
            } else {
                // Fallback: Stop pipeline completely to save power (legacy behavior)
                log("Stopping pipeline (ACC ON - saving power)...");
                if (gpuPipeline != null) {
                    gpuPipeline.onAccOn();
                    gpuPipeline.stop();
                }
                log("Pipeline stopped - power saving mode");
            }
        }
    }
    
    /**
     * Notify of gear state change.
     * 
     * Used by PROXIMITY_GUARD mode to activate/deactivate based on gear position.
     * When gear != P, proximity guard starts monitoring.
     * When gear = P, proximity guard stops (ADAS sensors go to ABNORMAL which is expected).
     * 
     * @param gear The new gear position (1=P, 2=R, 3=N, 4=D, 5=M, 6=S)
     */
    public static void onGearChanged(int gear) {
        String gearName = com.overdrive.app.recording.RecordingModeManager.gearToString(gear);
        log("Gear changed to: " + gearName);
        
        if (recordingModeManager != null) {
            recordingModeManager.onGearChanged(gear);
        } else {
            log("RecordingModeManager not initialized - gear change ignored");
        }
        
        if (tripAnalyticsManager != null) tripAnalyticsManager.onGearChanged(gear);
    }
    
    /**
     * Check if surveillance is enabled.
     */
    public static boolean isSurveillanceEnabled() {
        return surveillanceEnabled;
    }
    
    /** True if surveillance was requested but suppressed because car is in a safe zone. */
    public static boolean isSafeZoneSuppressed() {
        return safeZoneSuppressed;
    }
    
    public static void setSafeZoneSuppressed(boolean suppressed) {
        safeZoneSuppressed = suppressed;
    }
    
    /**
     * Check if surveillance is actively processing.
     */
    public static boolean isSurveillanceActive() {
        return gpuPipeline != null && gpuPipeline.isRunning();
    }
    
    /**
     * Set recording quality/mode.
     */
    public static void setRecordingQuality(String quality) {
        if (gpuPipeline == null) return;
        
        com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode mode;
        switch (quality.toUpperCase()) {
            case "LOW":
            case "SENTRY":
                mode = com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode.SENTRY;
                break;
            case "REDUCED":
                mode = com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode.SENTRY;
                break;
            case "NORMAL":
            default:
                mode = com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode.NORMAL;
                break;
        }
        
        gpuPipeline.setRecordingMode(mode);
        log("Recording quality set to: " + quality + " (mode=" + mode + ")");
    }
    
    /**
     * Set streaming quality.
     */
    public static void setStreamingQuality(String quality) {
        if (gpuPipeline == null) return;
        
        com.overdrive.app.surveillance.GpuPipelineConfig.StreamingQuality streamQuality =
            com.overdrive.app.surveillance.GpuPipelineConfig.StreamingQuality.fromString(quality);
        
        gpuPipeline.setStreamingQuality(streamQuality);
        log("Streaming quality set to: " + streamQuality.displayName);
    }
    
    /**
     * Set recording bitrate (2, 3, or 6 Mbps).
     */
    public static void setRecordingBitrate(String bitrate) {
        if (gpuPipeline == null) {
            log("setRecordingBitrate: gpuPipeline is null, skipping");
            return;
        }
        
        try {
            com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset preset;
            switch (bitrate.toUpperCase()) {
                case "LOW":
                    preset = com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset.LOW;
                    break;
                case "HIGH":
                    preset = com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset.HIGH;
                    break;
                case "MEDIUM":
                default:
                    preset = com.overdrive.app.surveillance.GpuPipelineConfig.BitratePreset.MEDIUM;
                    break;
            }
            
            if (gpuPipeline.getConfig() == null) {
                log("setRecordingBitrate: config is null, skipping");
                return;
            }
            
            gpuPipeline.getConfig().setBitratePreset(preset);
            // Use codec-aware bitrate (H.265 uses lower bitrate for same quality)
            int effectiveBitrate = gpuPipeline.getConfig().getEffectiveBitrate();
            gpuPipeline.applyBitrateChange(effectiveBitrate);
            log("Recording bitrate set to: " + bitrate + " (" + effectiveBitrate / 1_000_000 + " Mbps for " + 
                gpuPipeline.getConfig().getVideoCodec() + ")");
        } catch (Exception e) {
            log("setRecordingBitrate error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Set recording codec (H.264 or H.265).
     * Note: Codec change requires encoder restart.
     */
    public static void setRecordingCodec(String codec) {
        if (gpuPipeline == null) {
            log("setRecordingCodec: gpuPipeline is null, skipping");
            return;
        }
        
        try {
            com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec videoCodec;
            switch (codec.toUpperCase()) {
                case "H265":
                case "HEVC":
                    videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265;
                    break;
                case "H264":
                case "AVC":
                default:
                    videoCodec = com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;
                    break;
            }
            
            if (gpuPipeline.getConfig() == null) {
                log("setRecordingCodec: config is null, skipping");
                return;
            }
            
            gpuPipeline.getConfig().setVideoCodec(videoCodec);
            gpuPipeline.applyCodecChange(videoCodec);
            log("Recording codec set to: " + codec + " (" + videoCodec.displayName + ") - restart recording to apply");
        } catch (Exception e) {
            log("setRecordingCodec error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Get current recording bitrate setting.
     */
    public static String getRecordingBitrate() {
        if (gpuPipeline == null) return "MEDIUM";
        return gpuPipeline.getConfig().getBitratePreset().name();
    }
    
    /**
     * Get current recording codec setting.
     */
    public static String getRecordingCodec() {
        if (gpuPipeline == null) return "H264";
        return gpuPipeline.getConfig().getVideoCodec() == 
            com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265 ? "H265" : "H264";
    }
    
    /**
     * Get GPU pipeline instance.
     */
    public static com.overdrive.app.surveillance.GpuSurveillancePipeline getGpuPipeline() {
        return gpuPipeline;
    }
    
    // ==================== RECORDING MODE CONTROL ====================
    
    /**
     * Set recording mode (NONE, CONTINUOUS, DRIVE_MODE, PROXIMITY_GUARD).
     */
    public static void setRecordingMode(String mode) {
        if (recordingModeManager == null) {
            log("ERROR: RecordingModeManager not initialized");
            return;
        }
        
        try {
            com.overdrive.app.recording.RecordingModeManager.Mode modeEnum =
                com.overdrive.app.recording.RecordingModeManager.Mode.valueOf(mode.toUpperCase());
            recordingModeManager.setMode(modeEnum);
            log("Recording mode set to: " + mode);
        } catch (IllegalArgumentException e) {
            log("ERROR: Invalid recording mode: " + mode);
        }
    }
    
    /**
     * Get current recording mode.
     */
    public static String getRecordingMode() {
        if (recordingModeManager == null) {
            return "NONE";
        }
        return recordingModeManager.getCurrentMode().name();
    }
    
    /**
     * Get recording mode manager instance.
     */
    public static com.overdrive.app.recording.RecordingModeManager getRecordingModeManager() {
        return recordingModeManager;
    }
    
    /**
     * Get surveillance status for API.
     */
    public static java.util.Map<String, Object> getSurveillanceStatus() {
        java.util.Map<String, Object> status = new java.util.HashMap<>();
        
        if (gpuPipeline != null) {
            status.put("initialized", gpuPipeline.isInitialized());
            status.put("enabled", surveillanceEnabled);
            status.put("active", gpuPipeline.isRunning());
            status.put("recording", gpuPipeline.getSentry() != null && gpuPipeline.getSentry().isRecording());
            status.put("frameCount", gpuPipeline.getCamera() != null ? gpuPipeline.getCamera().getFrameCount() : 0);
            status.put("encoderType", "gpu-zero-copy");
            
            // Grid motion stats (for UI display)
            if (gpuPipeline.getSentry() != null) {
                status.put("activeBlocks", gpuPipeline.getSentry().getLastActiveBlocksCount());
                status.put("totalBlocks", gpuPipeline.getSentry().getTotalBlocks());
                status.put("baselineBlocks", gpuPipeline.getSentry().getBaselineNoiseBlocks());
                status.put("blockSensitivity", gpuPipeline.getSentry().getBlockSensitivity());
                status.put("requiredBlocks", gpuPipeline.getSentry().getRequiredActiveBlocks());
                
                // SOTA: Enhanced motion detection stats
                status.put("temporalBlocks", gpuPipeline.getSentry().getLastTemporalBlocksCount());
                status.put("estimatedDistance", gpuPipeline.getSentry().getLastEstimatedDistance());
                int[] bounds = gpuPipeline.getSentry().getLastMotionBounds();
                if (bounds != null) {
                    status.put("motionMinY", bounds[0]);
                    status.put("motionMaxY", bounds[1]);
                }
            }
            
            // Get today's events with details
            java.util.List<java.util.Map<String, Object>> events = getTodaysEvents();
            status.put("totalEventsToday", events.size());
            status.put("events", events);
        } else {
            status.put("initialized", false);
            status.put("enabled", false);
            status.put("active", false);
            status.put("encoderType", "none");
            status.put("totalEventsToday", 0);
            status.put("events", new java.util.ArrayList<>());
        }
        
        // SOTA: Safe Location status
        com.overdrive.app.surveillance.SafeLocationManager safeMgr =
            com.overdrive.app.surveillance.SafeLocationManager.getInstance();
        status.put("safeZoneSuppressed", safeZoneSuppressed);
        status.put("inSafeZone", safeMgr.isInSafeZone());
        status.put("safeZoneName", safeMgr.getCurrentZoneName());
        
        // SOTA: BYD camera coordinator status
        if (gpuPipeline != null && gpuPipeline.getCamera() != null) {
            com.overdrive.app.camera.BydCameraCoordinator coordinator = 
                gpuPipeline.getCamera().getCameraCoordinator();
            if (coordinator != null) {
                status.put("cameraServiceRegistered", coordinator.isRegistered());
                status.put("cameraUserRegistered", coordinator.isRegisteredAsUser());
                status.put("cameraYielded", coordinator.isYielded());
                status.put("nativeAppActive", coordinator.isNativeAppActive());
                status.put("cameraEventCallback", coordinator.isEventCallbackActive());
            }
            
            // SOTA: Camera probe status
            com.overdrive.app.camera.PanoramicCameraGpu cam = gpuPipeline.getCamera();
            status.put("probeComplete", cam.isProbeComplete());
            status.put("activeCameraId", cam.getCameraId());
            status.put("activeSurfaceMode", cam.getCameraSurfaceMode());
        }
        
        return status;
    }
    
    /**
     * Count event recordings from today.
     * Looks for files matching pattern: event_YYYYMMDD_*.mp4 in sentry_events directory
     */
    private static int countTodaysEvents() {
        return getTodaysEvents().size();
    }
    
    /** Map battery capacity to ABRP car model name */
    private static String capacityToModelName(double capacityKwh) {
        if (capacityKwh >= 105) return "byd:seal:23:108";     // Tang EV
        if (capacityKwh >= 84) return "byd:han:21:85";        // Han EV
        if (capacityKwh >= 80) return "byd:seal:23:82";       // Seal
        if (capacityKwh >= 70) return "byd:seal_u:24:72";     // Seal U
        if (capacityKwh >= 59) return "byd:atto3:22:60";      // Atto 3
        if (capacityKwh >= 55) return "byd:qin_plus:21:56";   // Qin Plus
        if (capacityKwh >= 43) return "byd:dolphin:22:45";    // Dolphin
        if (capacityKwh >= 36) return "byd:seagull:23:38";    // Seagull
        return null;
    }
    
    /**
     * Get list of today's events with timestamps.
     * Returns list of event info maps with filename, time, and size.
     */
    public static java.util.List<java.util.Map<String, Object>> getTodaysEvents() {
        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        
        try {
            // Get today's date prefix (e.g., "event_20260111_")
            String todayPrefix = "event_" + new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date()) + "_";
            
            // SOTA: Use StorageManager for surveillance directory
            com.overdrive.app.storage.StorageManager storageManager =
                com.overdrive.app.storage.StorageManager.getInstance();
            java.io.File sentryDir = storageManager.getSurveillanceDir();
            java.io.File[] files = null;
            
            if (sentryDir.exists() && sentryDir.isDirectory()) {
                files = sentryDir.listFiles((dir, name) -> 
                    name.startsWith(todayPrefix) && name.endsWith(".mp4"));
            }
            
            // Fallback to legacy locations for backward compatibility
            if (files == null || files.length == 0) {
                sentryDir = new java.io.File(outputDir, "sentry_events");
                if (sentryDir.exists() && sentryDir.isDirectory()) {
                    files = sentryDir.listFiles((dir, name) -> 
                        name.startsWith(todayPrefix) && name.endsWith(".mp4"));
                }
            }
            
            if (files == null || files.length == 0) {
                sentryDir = new java.io.File("/storage/emulated/0/Android/data/com.overdrive.app/files/sentry_events");
                if (sentryDir.exists() && sentryDir.isDirectory()) {
                    files = sentryDir.listFiles((dir, name) -> 
                        name.startsWith(todayPrefix) && name.endsWith(".mp4"));
                }
            }
            
            if (files != null) {
                // Sort by filename (which includes timestamp) descending (newest first)
                java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                
                for (java.io.File file : files) {
                    java.util.Map<String, Object> event = new java.util.HashMap<>();
                    event.put("filename", file.getName());
                    event.put("size", file.length() / 1024); // KB
                    
                    // Extract time from filename: event_YYYYMMDD_HHMMSS.mp4
                    String name = file.getName();
                    if (name.length() >= 22) {
                        String timeStr = name.substring(15, 21); // HHMMSS
                        String formatted = timeStr.substring(0, 2) + ":" + timeStr.substring(2, 4) + ":" + timeStr.substring(4, 6);
                        event.put("time", formatted);
                    } else {
                        event.put("time", "--:--:--");
                    }
                    
                    events.add(event);
                }
            }
        } catch (Exception e) {
            log("Error getting today's events: " + e.getMessage());
        }
        
        return events;
    }
    
    /**
     * Get comprehensive streaming status (VPS streaming removed).
     * Returns a map with streaming state info for API responses.
     */
    public static Map<String, Object> getStreamingStatus() {
        Map<String, Object> status = new java.util.HashMap<>();
        status.put("enabled", false);
        status.put("deviceId", deviceId);
        status.put("activeCameras", new java.util.ArrayList<>());
        status.put("publisherCount", 0);
        status.put("mode", streamMode);
        status.put("note", "VPS streaming removed - use local HTTP streaming");
        
        // Per-camera status (all false)
        Map<Integer, Boolean> cameraStatus = new java.util.HashMap<>();
        for (int i = 1; i <= 4; i++) {
            cameraStatus.put(i, false);
        }
        status.put("cameras", cameraStatus);
        
        return status;
    }

    // ==================== STREAM MODE CONTROL ====================
    
    /**
     * Set stream mode: "private" (local only) or "public" (tunnel access).
     * Both modes now use tunnel URLs for remote access.
     */
    public static void setStreamMode(String mode) {
        if (!STREAM_MODE_PRIVATE.equals(mode) && !STREAM_MODE_PUBLIC.equals(mode)) {
            log("ERROR: Invalid stream mode: " + mode);
            return;
        }
        
        String oldMode = streamMode;
        streamMode = mode;
        
        // Persist to file
        saveStreamMode(mode);
        
        log("Stream mode changed: " + oldMode + " -> " + mode);
        // VPS heartbeat removed - both modes use tunnel URLs now
    }
    
    /**
     * Save stream mode to file for persistence.
     */
    private static void saveStreamMode(String mode) {
        try {
            java.io.FileWriter writer = new java.io.FileWriter(PATH_STREAM_MODE_FILE());
            writer.write(mode);
            writer.close();
        } catch (Exception e) {
            log("Failed to save stream mode: " + e.getMessage());
        }
    }
    
    /**
     * Load stream mode from file.
     */
    private static void loadStreamMode() {
        try {
            File file = new File(PATH_STREAM_MODE_FILE());
            if (file.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
                String mode = reader.readLine();
                reader.close();
                
                if (STREAM_MODE_PUBLIC.equals(mode)) {
                    log("Restored stream mode: PUBLIC");
                    setStreamMode(STREAM_MODE_PUBLIC);
                } else {
                    log("Restored stream mode: PRIVATE");
                    streamMode = STREAM_MODE_PRIVATE;
                }
            }
        } catch (Exception e) {
            log("Failed to load stream mode: " + e.getMessage());
        }
    }
    
    /**
     * Get current stream mode.
     */
    public static String getStreamMode() {
        return streamMode;
    }
    
    /**
     * Check if public streaming is enabled.
     */
    public static boolean isPublicMode() {
        return STREAM_MODE_PUBLIC.equals(streamMode);
    }
    
    /**
     * Get list of recording cameras (helper for status).
     */
    private static java.util.List<Integer> getRecordingCameras() {
        java.util.List<Integer> recording = new java.util.ArrayList<>();
        // GPU pipeline records all 4 cameras in mosaic
        if (gpuPipeline != null && gpuPipeline.isRunning()) {
            recording.add(1);
            recording.add(2);
            recording.add(3);
            recording.add(4);
        }
        return recording;
    }

    // ==================== INITIALIZATION ====================
    
    private static void generateDeviceId() {
        // FIRST: Try to read from shared file (written by app with context)
        // This ensures daemon uses the same ID as the app
        try {
            File idFile = new File(PATH_DEVICE_ID_FILE());
            if (idFile.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(idFile));
                String fileId = reader.readLine();
                reader.close();
                if (fileId != null && !fileId.isEmpty() && fileId.startsWith("byd-")) {
                    deviceId = fileId;
                    log("Device ID loaded from file: " + deviceId);
                    return;
                }
            }
        } catch (Exception e) {
            log("WARN: Could not read device ID from file: " + e.getMessage());
        }
        
        // Fallback: use serial number hash
        try {
            String serial = android.os.Build.SERIAL;
            if (serial != null && !serial.equals("unknown")) {
                deviceId = "byd-" + Integer.toHexString(serial.hashCode()).substring(0, 8);
                saveDeviceId(deviceId);
                log("Device ID generated from serial: " + deviceId);
                return;
            }
        } catch (Exception e) {
            log("WARN: Could not get serial: " + e.getMessage());
        }
        
        // Fallback: use build fingerprint hash
        try {
            String fingerprint = android.os.Build.FINGERPRINT;
            if (fingerprint != null && !fingerprint.isEmpty()) {
                deviceId = "byd-" + Integer.toHexString(fingerprint.hashCode()).substring(0, 8);
                saveDeviceId(deviceId);
                log("Device ID generated from fingerprint: " + deviceId);
                return;
            }
        } catch (Exception e) {
            log("WARN: Could not get fingerprint: " + e.getMessage());
        }
        
        // Last resort: generate random ID
        deviceId = "byd-" + Long.toHexString(System.currentTimeMillis()).substring(4);
        saveDeviceId(deviceId);
        log("Device ID generated randomly: " + deviceId);
    }
    
    private static void saveDeviceId(String id) {
        try {
            File idFile = new File(PATH_DEVICE_ID_FILE());
            java.io.FileWriter writer = new java.io.FileWriter(idFile);
            writer.write(id);
            writer.close();
        } catch (Exception e) {
            log("WARN: Could not save device ID to file: " + e.getMessage());
        }
    }
    
    private static void parseArguments(String[] args) {
        if (args.length > 0) {
            outputDir = args[0];
            log("Arg[0] outputDir: " + outputDir);
        }
        
        if (args.length > 1) {
            nativeLibDir = args[1];  // Use class field
            log("Arg[1] nativeLibDir: " + nativeLibDir);
        }
    }
    
    private static void loadNativeLibraries() {
        try {
            try { System.loadLibrary("nativehelper"); } catch (Throwable t) {}
            System.loadLibrary("cutils");
            System.loadLibrary("utils");
            System.loadLibrary("binder");
            System.loadLibrary("gui");
            System.loadLibrary("bmmcamera");
        } catch (Throwable e) {
            log("WARN: System lib warning: " + e.getMessage());
        }
        
        // Load surveillance library - try default path first
        if (!com.overdrive.app.surveillance.NativeMotion.isLibraryLoaded()) {
            // Try explicit path using nativeLibDir
            if (nativeLibDir != null) {
                if (com.overdrive.app.surveillance.NativeMotion.tryLoadLibrary(nativeLibDir)) {
                    log("Surveillance library loaded from: " + nativeLibDir);
                } else {
                    // Try alternate paths
                    loadSurveillanceFromPath(nativeLibDir);
                }
            }
            
            // Final check
            if (com.overdrive.app.surveillance.NativeMotion.isLibraryLoaded()) {
                log("Surveillance library loaded successfully");
            } else {
                log("WARN: Surveillance library NOT available: " + 
                    com.overdrive.app.surveillance.NativeMotion.getLoadError());
            }
        } else {
            log("Surveillance library already loaded");
        }
    }
    
    private static void loadSurveillanceFromPath(String nativeLibDir) {
        // Load surveillance library
        String[] surveillancePaths = {
            nativeLibDir + "/libsurveillance.so",
            nativeLibDir.replace("/arm64", "/arm64-v8a") + "/libsurveillance.so",
            nativeLibDir + "-v8a/libsurveillance.so"
        };
        
        for (String libPath : surveillancePaths) {
            if (new File(libPath).exists()) {
                try {
                    System.load(libPath);
                    log("SUCCESS: Surveillance library loaded from: " + libPath);
                    return;
                } catch (Throwable e) {
                    log("ERROR: FAILED to load " + libPath + ": " + e.getMessage());
                }
            }
        }
    }
    
    private static void scanCameras() {
        log("--- CAMERA SCAN ---");
        try {
            Class<?> infoClass = Class.forName("android.hardware.BmmCameraInfo");
            java.lang.reflect.Method mGetTags = infoClass.getDeclaredMethod("getValidCameraTag");
            mGetTags.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<String> tags = (java.util.List<String>) mGetTags.invoke(null);

            java.lang.reflect.Method mGetId = infoClass.getDeclaredMethod("getCameraId", String.class);
            mGetId.setAccessible(true);

            if (tags != null) {
                for (String tag : tags) {
                    int id = (int) mGetId.invoke(null, tag);
                    log("FOUND: [" + tag.toUpperCase() + "] -> ID: " + id);
                }
            }
        } catch (Exception e) {
            log("WARN: BmmCamera scan failed: " + e.getMessage());
        }
        
        // Probe AVMCamera IDs 0-5 to find which cameras exist on this device
        try {
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
            java.lang.reflect.Constructor<?> ctor = avmClass.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            java.lang.reflect.Method mOpen = avmClass.getDeclaredMethod("open");
            mOpen.setAccessible(true);
            java.lang.reflect.Method mClose = avmClass.getDeclaredMethod("close");
            mClose.setAccessible(true);
            
            for (int id = 0; id <= 5; id++) {
                try {
                    Object cam = ctor.newInstance(id);
                    boolean opened = (boolean) mOpen.invoke(cam);
                    if (opened) {
                        log("AVMCamera ID " + id + ": AVAILABLE");
                        mClose.invoke(cam);
                    } else {
                        log("AVMCamera ID " + id + ": open() returned false");
                    }
                } catch (Exception e) {
                    // Camera ID doesn't exist or can't be opened
                }
            }
        } catch (ClassNotFoundException e) {
            log("WARN: AVMCamera not available on this device");
        } catch (Exception e) {
            log("WARN: AVMCamera probe failed: " + e.getMessage());
        }
        
        log("--- END SCAN ---");
    }

    // ==================== LOGGING ====================
    
    private static void initFileLogging() {
        // Configure DaemonLogger for daemon context (enable stdout for app_process)
        DaemonLogger.configure(DaemonLogger.Config.defaults()
            .withStdoutLog(true)  // Enable stdout for daemon processes
            .withFileLog(true)
            .withConsoleLog(true));
        log("=== CameraDaemon Log Started ===");
    }
    
    public static void log(String message) {
        logger.info(message);
    }
    
    // ==================== GPS MONITOR ====================
    
    /**
     * Initialize GPS Monitor with app context for standard LocationManager access.
     * Uses PermissionBypassContext to access location services without runtime permission prompts.
     */
    private static void initGpsMonitor() {
        try {
            log("Initializing GPS Monitor with app context...");
            
            // Grant location permissions via shell (daemon runs as root/system)
            grantLocationPermissions();
            
            // Try to get or create shared app context
            if (sharedAppContext == null) {
                sharedAppContext = createAppContext();
            }
            
            if (sharedAppContext == null) {
                log("WARNING: Could not create app context for GpsMonitor, falling back to daemon mode");
                com.overdrive.app.monitor.GpsMonitor.getInstance().init(null);
                return;
            }
            
            log("Got app context: " + sharedAppContext.getClass().getName());
            
            // Verify LocationManager is accessible
            Object locMgr = sharedAppContext.getSystemService(android.content.Context.LOCATION_SERVICE);
            if (locMgr == null) {
                log("WARNING: LocationManager not available, falling back to daemon mode");
                com.overdrive.app.monitor.GpsMonitor.getInstance().init(null);
                return;
            }
            log("LocationManager available: " + locMgr.getClass().getName());
            
            com.overdrive.app.monitor.GpsMonitor gpsMonitor =
                com.overdrive.app.monitor.GpsMonitor.getInstance();
            
            gpsMonitor.init(sharedAppContext);
            gpsMonitor.start();  // Start GPS tracking immediately
            
            log("GPS Monitor initialized with Context mode");
            
            // Initialize NetworkMonitor for WiFi/Mobile Data status in sidebar
            com.overdrive.app.monitor.NetworkMonitor.init(sharedAppContext);
            log("Network Monitor initialized");
            
        } catch (Exception e) {
            log("Failed to initialize GPS Monitor with context: " + e.getMessage());
            log("Falling back to daemon mode (shell commands)");
            com.overdrive.app.monitor.GpsMonitor.getInstance().init(null);
        }
    }
    
    /**
     * Grant location permissions to the app via shell commands.
     * The daemon runs with elevated privileges so it can grant permissions.
     */
    private static void grantLocationPermissions() {
        String[] permissions = {
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION"
        };
        
        log("Granting location permissions...");
        
        for (String perm : permissions) {
            try {
                Process process = Runtime.getRuntime().exec(
                    "pm grant com.overdrive.app " + perm);
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    log("Granted: " + perm);
                } else {
                    log("Failed to grant: " + perm + " (exit=" + exitCode + ")");
                }
            } catch (Exception e) {
                log("Error granting " + perm + ": " + e.getMessage());
            }
        }
    }
    
    // ==================== VEHICLE DATA MONITOR ====================
    
    /**
     * Initialize Vehicle Data Monitor for EV battery and charging data.
     * Reuses shared app context with PermissionBypassContext for BYD hardware access.
     */
    private static void initVehicleDataMonitor() {
        try {
            log("Initializing Vehicle Data Monitor...");
            
            // Reuse shared context if available, otherwise create new
            if (sharedAppContext == null) {
                sharedAppContext = createAppContext();
            }
            
            if (sharedAppContext == null) {
                log("WARNING: Could not create app context for VehicleDataMonitor");
                return;
            }
            
            com.overdrive.app.monitor.VehicleDataMonitor vehicleMonitor =
                com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            
            vehicleMonitor.init(sharedAppContext);
            vehicleMonitor.start();
            
            log("Vehicle Data Monitor initialized successfully");
            
            // Initialize Universal BYD Data Collector (runs alongside existing monitors)
            try {
                com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
                collector.init(sharedAppContext);
                collector.logSummary();
                log("BYD Data Collector initialized (" + collector.getData().availableDevices.length + " devices)");
            } catch (Exception e) {
                log("BYD Data Collector init error (non-fatal): " + e.getMessage());
            }
            
            // Initialize Gear Monitor for PROXIMITY_GUARD mode
            com.overdrive.app.monitor.GearMonitor gearMonitor =
                com.overdrive.app.monitor.GearMonitor.getInstance();
            gearMonitor.init(sharedAppContext);
            // Wire GearMonitor to read gear from TelemetryDataCollector's cached snapshot
            // when the overlay poller is running, avoiding duplicate CAN bus reads
            if (telemetryDataCollector != null) {
                gearMonitor.setTelemetrySource(telemetryDataCollector);
            }
            gearMonitor.start();
            
            log("Gear Monitor initialized successfully");
            
            // Initialize Performance Monitor for system instrumentation
            com.overdrive.app.monitor.PerformanceMonitor perfMonitor =
                com.overdrive.app.monitor.PerformanceMonitor.getInstance();
            perfMonitor.init(sharedAppContext);
            perfMonitor.start();
            
            log("Performance Monitor initialized successfully");
            
            // Initialize SOC History Database for persistent battery tracking
            com.overdrive.app.monitor.SocHistoryDatabase socDb =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
            socDb.setSohEstimator(sohEstimator);
            socDb.init();
            socDb.start();
            
            // Fix stale kWh records from before PHEV capacity was correctly detected
            if (sohEstimator != null && sohEstimator.getNominalCapacityKwh() > 0
                    && sohEstimator.getNominalCapacityKwh() < 30.0) {
                log("Fixing stale kWh records for PHEV (nominal=" + sohEstimator.getNominalCapacityKwh() + " kWh)");
                socDb.fixStaleRemainingKwh(sohEstimator.getNominalCapacityKwh());
            }
            
            log("SOC History Database initialized successfully");
            
        } catch (Exception e) {
            log("Failed to initialize Vehicle Data Monitor: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Create app context with permission bypass for BYD hardware access.
     */
    private static android.content.Context createAppContext() {
        try {
            log("createAppContext: Starting...");
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread;

            // Strategy 1: Get existing ActivityThread (works if app process is running)
            try {
                java.lang.reflect.Method currentActivityThread = activityThreadClass.getMethod("currentActivityThread");
                activityThread = currentActivityThread.invoke(null);
                log("createAppContext: currentActivityThread = " + activityThread);
            } catch (Exception e) {
                log("createAppContext: currentActivityThread failed: " + e.getMessage());
                activityThread = null;
            }

            // Strategy 2: systemMain() with timeout — this can deadlock on some firmware
            if (activityThread == null) {
                log("createAppContext: Trying systemMain with 10s timeout...");
                final Object[] result = new Object[1];
                final Exception[] error = new Exception[1];
                Thread systemMainThread = new Thread(() -> {
                    try {
                        java.lang.reflect.Method systemMain = activityThreadClass.getMethod("systemMain");
                        result[0] = systemMain.invoke(null);
                    } catch (Exception e) {
                        error[0] = e;
                    }
                }, "SystemMainInit");
                systemMainThread.setDaemon(true);
                systemMainThread.start();
                systemMainThread.join(10_000); // 10 second timeout
                
                if (systemMainThread.isAlive()) {
                    log("createAppContext: systemMain TIMED OUT (10s)");
                    systemMainThread.interrupt();
                    try {
                        java.lang.reflect.Method currentActivityThread = activityThreadClass.getMethod("currentActivityThread");
                        activityThread = currentActivityThread.invoke(null);
                        log("createAppContext: post-timeout currentActivityThread = " + activityThread);
                    } catch (Exception e2) {
                        log("createAppContext: post-timeout currentActivityThread also failed");
                    }
                } else if (error[0] != null) {
                    log("createAppContext: systemMain failed: " + error[0].getMessage());
                } else {
                    activityThread = result[0];
                    log("createAppContext: systemMain = " + activityThread);
                }
            }
            
            // Strategy 3: Prepare looper manually + create ActivityThread via constructor
            if (activityThread == null) {
                log("createAppContext: Trying manual ActivityThread creation...");
                try {
                    // Ensure main looper exists (idempotent if already prepared)
                    try { android.os.Looper.prepareMainLooper(); } catch (Exception ignored) {}
                    
                    // Create ActivityThread via default constructor
                    java.lang.reflect.Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    activityThread = ctor.newInstance();
                    
                    // Set as the current thread via sCurrentActivityThread field
                    try {
                        java.lang.reflect.Field sField = activityThreadClass.getDeclaredField("sCurrentActivityThread");
                        sField.setAccessible(true);
                        sField.set(null, activityThread);
                    } catch (NoSuchFieldException e) {
                        // Some Android versions use different field name
                        try {
                            java.lang.reflect.Field sField = activityThreadClass.getDeclaredField("sMainThreadHandler");
                            // If we got here, the field layout is different — just proceed
                        } catch (Exception ignored) {}
                    }
                    
                    log("createAppContext: manual ActivityThread = " + activityThread);
                } catch (Exception e) {
                    log("createAppContext: manual creation failed: " + e.getMessage());
                }
            }

            if (activityThread == null) {
                // Strategy 4: Last resort — get system context directly via ContextImpl
                log("createAppContext: All ActivityThread strategies failed, trying ContextImpl...");
                return createFallbackContext();
            }

            java.lang.reflect.Method getSystemContext = activityThreadClass.getMethod("getSystemContext");
            android.content.Context systemContext = (android.content.Context) getSystemContext.invoke(activityThread);
            log("createAppContext: systemContext = " + systemContext);
            
            if (systemContext == null) {
                log("createAppContext: systemContext is null, trying fallback...");
                return createFallbackContext();
            }

            String packageName = APP_PACKAGE_NAME();
            log("createAppContext: Creating package context for " + packageName);
            android.content.Context appContext = systemContext.createPackageContext(packageName,
                    android.content.Context.CONTEXT_INCLUDE_CODE | android.content.Context.CONTEXT_IGNORE_SECURITY);
            log("createAppContext: appContext = " + appContext);
            
            if (appContext == null) {
                log("createAppContext: appContext is null, trying fallback...");
                return createFallbackContext();
            }
            
            PermissionBypassContext wrapped = new PermissionBypassContext(appContext);
            log("createAppContext: Success, returning PermissionBypassContext");
            return wrapped;

        } catch (Exception e) {
            log("createAppContext failed: " + e.getMessage() + ", trying fallback...");
            return createFallbackContext();
        }
    }
    
    /**
     * Fallback context creation when ActivityThread is completely unavailable.
     * Creates a minimal context via ContextImpl reflection that's enough for
     * BYD device getInstance() calls (they just need enforceCallingOrSelfPermission to not NPE).
     */
    private static android.content.Context createFallbackContext() {
        try {
            // Try to create ContextImpl directly
            Class<?> contextImplClass = Class.forName("android.app.ContextImpl");
            
            // Try createSystemContext() — available on most Android versions
            try {
                java.lang.reflect.Method createSystemContext = contextImplClass.getDeclaredMethod("createSystemContext", 
                    Class.forName("android.app.ActivityThread"));
                createSystemContext.setAccessible(true);
                // Pass null ActivityThread — some versions tolerate this
                android.content.Context ctx = (android.content.Context) createSystemContext.invoke(null, (Object) null);
                if (ctx != null) {
                    log("createFallbackContext: ContextImpl.createSystemContext succeeded");
                    return new PermissionBypassContext(ctx);
                }
            } catch (Exception e) {
                log("createFallbackContext: createSystemContext failed: " + e.getMessage());
            }
            
            // Try createAppContext with minimal params
            try {
                java.lang.reflect.Method[] methods = contextImplClass.getDeclaredMethods();
                for (java.lang.reflect.Method m : methods) {
                    if (m.getName().equals("createAppContext") && m.getParameterTypes().length == 2) {
                        m.setAccessible(true);
                        // Can't call without valid params, skip
                        break;
                    }
                }
            } catch (Exception ignored) {}
            
            // Last resort: use a bare PermissionBypassContext with a dummy base
            // This creates a context that returns PERMISSION_GRANTED for all checks
            // and delegates everything else to the system
            log("createFallbackContext: Using null-safe PermissionBypassContext as last resort");
            return new PermissionBypassContext(null);
            
        } catch (Exception e) {
            log("createFallbackContext failed completely: " + e.getMessage());
            return new PermissionBypassContext(null);
        }
    }
    
    /**
     * Context wrapper that bypasses permission checks and handles null base context.
     * Required for accessing BYD hardware services without signature permissions.
     * When base is null (fallback mode), provides safe defaults for methods BYD devices call.
     */
    private static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(android.content.Context base) { super(base); }
        
        @Override public void enforceCallingOrSelfPermission(String permission, String message) {}
        @Override public void enforcePermission(String permission, int pid, int uid, String message) {}
        @Override public void enforceCallingPermission(String permission, String message) {}
        @Override public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        @Override public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        
        // Null-safe overrides for when base context is null (fallback mode)
        @Override public android.content.Context getApplicationContext() {
            try { return super.getApplicationContext(); } catch (NullPointerException e) { return this; }
        }
        @Override public String getPackageName() {
            try { return super.getPackageName(); } catch (NullPointerException e) { return APP_PACKAGE_NAME(); }
        }
        @Override public Object getSystemService(String name) {
            try { return super.getSystemService(name); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
            try { return super.getApplicationInfo(); } catch (NullPointerException e) { return new android.content.pm.ApplicationInfo(); }
        }
        @Override public android.content.ContentResolver getContentResolver() {
            try { return super.getContentResolver(); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.res.Resources getResources() {
            try { return super.getResources(); } catch (NullPointerException e) { return null; }
        }
        @Override public android.content.Context createPackageContext(String packageName, int flags) {
            try { return super.createPackageContext(packageName, flags); } catch (Exception e) { return this; }
        }
    }
}
