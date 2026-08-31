package com.overdrive.app.daemon;

import android.os.Handler;
import android.os.Looper;
import com.overdrive.app.util.ScratchPaths;

import com.overdrive.app.abrp.AbrpConfig;
import com.overdrive.app.abrp.AbrpTelemetryService;
import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.logging.DaemonLogConfig;
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
    /** " + ScratchPaths.getDir() + "/cam_stream */
    private static String PATH_CAMERA_STREAM_DIR() {
        return com.overdrive.app.util.ScratchPaths.path(
                Safe.s("ZHx6IP38aGV/Q7iMCCcxzxuq9ag7mKGoQaOvzuwMDqM="));
    }
    /** /sdcard/DCIM/BYDCam */
    private static String PATH_CAMERA_OUTPUT_DIR() { return Safe.s("C6E+8XkzSNnhdgOIKBfVSXGyuhqY7qDiNp4pBP/hRuY="); }
    /** " + ScratchPaths.getDir() + "/stream_mode.txt */
    private static String PATH_STREAM_MODE_FILE() {
        return com.overdrive.app.util.ScratchPaths.path(
                Safe.s("ZHx6IP38aGV/Q7iMCCcxz4A79W/sQd0NkqiGs/MIZWo="));
    }
    /** " + ScratchPaths.getDir() + "/.byd_device_id */
    private static String PATH_DEVICE_ID_FILE() {
        return com.overdrive.app.util.ScratchPaths.path(
                Safe.s("ZHx6IP38aGV/Q7iMCCcxz8mvs/gQENVv3FEZ6OVKD54="));
    }

    // ==================== CONFIGURATION ====================
    public static final int TCP_PORT = 19876;
    public static final int HTTP_PORT = 8080;
    public static String STREAM_DIR() { return PATH_CAMERA_STREAM_DIR(); }
    public static final String APP_STREAM_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files/stream";

    // Recording config defaults. Runtime panoramic geometry comes from
    // CameraConfigResolver; these stay as legacy-profile fallbacks for code
    // paths that still read the daemon constants directly.
    public static final int PANO_WIDTH = com.overdrive.app.camera.CameraProfiles
        .getLegacyDefault().getPanoWidth();
    public static final int PANO_HEIGHT = com.overdrive.app.camera.CameraProfiles
        .getLegacyDefault().getPanoHeight();
    public static final int VIEW_WIDTH = PANO_WIDTH / 4;
    public static final int VIEW_HEIGHT = PANO_HEIGHT;
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
    private static final AtomicBoolean shutdownStarted = new AtomicBoolean(false);
    private static final AtomicBoolean forceTerminationStarted = new AtomicBoolean(false);
    private static final long TERMINAL_SHUTDOWN_BUDGET_MS = 20_000L;
    private static final Object TERMINAL_SHUTDOWN_GUARD_LOCK = new Object();
    private static Thread terminalShutdownGuard;
    private static Runnable terminalShutdownHandlerCallback;
    /**
     * Budget for {@link #requestUrgentCameraReleaseRestart}: the process is
     * halted this long after an urgent camera-release restart is requested,
     * whether or not the trip checkpoint completed. Deliberately much shorter
     * than {@link #TERMINAL_SHUTDOWN_BUDGET_MS}: while this deadline runs the
     * daemon is holding an AVMCamera handle it can no longer close or yield,
     * so every second is a second the native AVM app shows no video signal.
     * 5s still covers trip-analytics init or a briefly-blocking flush write;
     * a write blocked longer than this on a wedged FUSE mount was never going
     * to complete inside any deadline we could responsibly wait out.
     */
    private static final long URGENT_CAMERA_RELEASE_BUDGET_MS = 5_000L;
    /**
     * Latched true by {@link #requestUrgentCameraReleaseRestart} when its
     * non-cancellable halt deadline is armed (arm-once). Never cleared: once
     * armed, process death is guaranteed, so {@link #isProcessRestartPending()}
     * must keep refusing new camera/GL bring-up even if the conservative
     * coordinator's failure paths clear {@link #PROCESS_RESTART_REQUESTED}.
     */
    private static final AtomicBoolean URGENT_CAMERA_RELEASE_ARMED = new AtomicBoolean(false);
    private static Handler mainHandler;
    private static String outputDir = null; // Initialized in main()
    private static String nativeLibDir = null; // Initialized in parseArguments()

    // ==================== LOGGING ====================
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // ==================== SERVERS ====================
    private static TcpCommandServer tcpServer;
    private static HttpServer httpServer;
    private static SurveillanceIpcServer ipcServer;
    private static com.overdrive.app.server.AacIngestServer aacIngestServer;
    private static AccMonitor accMonitor;

    // ==================== SURVEILLANCE ====================
    // Volatile because static onAccStateChanged / onGearChanged / IPC
    // handlers + pendingAccOff drain read this from arbitrary threads
    // (GearMonitor poll, AccSentry IPC, HTTP, ADAS callbacks). Publication
    // via Thread.start() happens-before is fragile against future refactors
    // that read the field before the constructing thread starts a worker;
    // volatile gives a hard guarantee on ARM weak-memory cores.
    private static volatile com.overdrive.app.surveillance.GpuSurveillancePipeline gpuPipeline;
    // Volatile: written from ACC handlers (multiple threads), read by HTTP
    // handlers, accOnDisarmWatchdog poll thread, lock-gate watchdog, and
    // status JSON readers. Without volatile, the ARM weak-memory model lets
    // a writer's update sit unseen by peer threads for milliseconds —
    // acceptable for cosmetic chip flicker but risky for the watchdog's
    // force-disarm decision.
    private static volatile boolean surveillanceEnabled = false;
    // OEM Dashcam pipeline — separate forward sensor, distinct from pano AVM.
    // Lazily allocated when the user enables OEM Dashcam recording or the
    // streaming view mode 5 is requested. Both pipelines may run
    // concurrently when camera.concurrentAvmSupported == 1; otherwise they
    // share the single AVMCamera client via the priorityWhenContended UCM
    // policy.
    //
    // VOLATILE: writers go through OemDashcamApiHandler.LIFECYCLE_LOCK, but
    // readers (surveillance event handler, ACC dispatch, stream router,
    // /api/oem-dashcam/config GET) deliberately don't hold the lock — the
    // volatile barrier is what makes a freshly-published reference visible
    // across CPU cores without a lock acquisition on every read.
    private static volatile com.overdrive.app.camera.OemDashcamPipeline oemDashcamPipeline;
    private static volatile boolean safeZoneSuppressed = false;
    // Pending ACC OFF state: if ACC goes off before GPU pipeline is ready,
    // queue the request and apply it once the pipeline initializes
    private static volatile boolean pendingAccOff = false;
    // Pending ACC ON state: symmetric counterpart so an ACC ON IPC that
    // arrives before the GPU pipeline is ready isn't silently dropped. The
    // drain at end of initSurveillance fires onAccStateChanged(false) once
    // the pipeline is non-null, seeding RecordingModeManager so pano
    // recording starts cleanly on cold-boot when ACC is already ON.
    // Mutually exclusive with pendingAccOff — setting one always clears the
    // other so the drain order is unambiguous.
    private static volatile boolean pendingAccOn = false;
    // Generation of the ACC transition represented by the pending flag. A
    // drain must claim this token and revalidate it before replaying; otherwise
    // an old init/retry drain can resurrect the opposite state after a newer
    // edge has already arrived.
    private static long pendingAccTransitionGeneration = 0L;

    // DiLink 4 post-ACC-OFF camera-open grace duration. oem hardcodes 60 s
    // (FlameoutService p111dh/C4995i.java:407 m22726w(60_000L)). Earlier
    // AVMCamera.open races the MCU/ISP power-down and yields all-zero
    // frames forever. The actual gate lives in PanoramicCameraGpu and
    // covers ALL open paths (sentry, streaming, OEM, recording-mode).
    // Legacy fleet never arms it.
    private static final long DILINK4_SENTRY_DEFER_MS = 60_000L;

    // ==================== DOOR LOCK GATE (surveillance arm/disarm) ====================
    // Lock detection runs in CameraDaemon's process where cloud MQTT is active.
    // Surveillance is only armed after doors are locked (reduces false triggers from owner exiting).
    private static volatile boolean doorLockListenerArmed = false;

    // Lock-mode fallback signal: set true the moment ANY lock source delivers a
    // DEFINITE lock/unlock reading (applyLockEvent is only ever called with a
    // real value — INVALID/unknown reads never reach it). The 60s force-arm uses
    // this to distinguish two cases on an unlocked car:
    //   - reading was UNREADABLE the whole window  → force-arm (fallback for the
    //     common BYD trims whose lock sensors return INVALID ACC-off), and
    //   - reading was READABLE and said UNLOCKED    → stay disarmed (lock mode
    //     honors unlock; arming here would fight the unlock poll and cause the
    //     arm-then-disarm flap).
    // Reset at gate entry and on ACC-ON cleanup.
    private static volatile boolean sawValidLockReading = false;

    // Three parallel lock-event sources, all active simultaneously while the
    // gate is open. Cloud is fragile in the field (rarely fires lock events
    // even when MQTT is healthy), so device-SDK and polling exist as
    // independent backups rather than as a fallback chain.
    private static com.overdrive.app.byd.cloud.BydCloudDataProvider.CloudLockStateListener cloudLockListener = null;
    private static final long MANAGED_THREAD_JOIN_MS = 500L;
    private static final Object unlockPollThreadLock = new Object();
    private static Thread unlockPollThread = null;
    private static long unlockPollThreadGeneration;
    private static final Object doorLockTimeoutLock = new Object();
    private static Thread doorLockTimeoutThread = null;
    private static long doorLockTimeoutGeneration;
    // Reverse watchdog: periodically queries hardware ACC state and force-
    // disables surveillance if ACC went ON without an event reaching us.
    // Symmetric counterpart to the ACC-OFF DoorLockTimeout that force-arms.
    private static final Object accOnDisarmWatchdogLock = new Object();
    private static Thread accOnDisarmWatchdog = null;
    private static long accOnDisarmWatchdogGeneration;
    private static final long ACC_ON_DISARM_POLL_INTERVAL_MS = 5_000;
    private static final long DOOR_LOCK_ARM_TIMEOUT_MS = 60_000;  // 60s grace period
    private static final long UNLOCK_POLL_INTERVAL_MS = 5_000;
    private static final int DOOR_STATE_INVALID = 0;
    private static final int DOOR_STATE_UNLOCK = 1;
    private static final int DOOR_STATE_LOCK = 2;
    private static final long DOOR_LOCK_QUERY_TIMEOUT_MS = 750L;
    private static final long HARDWARE_QUERY_RECOVERY_GRACE_MS = 2_000L;
    private static final java.util.concurrent.atomic.AtomicReference<Thread>
            DOOR_LOCK_QUERY_WORKER = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicLong
            DOOR_LOCK_QUERY_STUCK_DEADLINE_NANOS =
                new java.util.concurrent.atomic.AtomicLong();

    // ==================== RECORDING MODE MANAGER ====================
    // Volatile: read by static onGearChanged/onAccStateChanged/onSafeZoneEnter
    // from arbitrary threads. See gpuPipeline volatile rationale.
    private static volatile com.overdrive.app.recording.RecordingModeManager recordingModeManager;
    // Published immediately before the fully constructed manager reference for the current
    // pipeline, and cleared before either owner begins teardown/replacement. Probe handoff may
    // only be promised while this ownership pair is intact.
    private static volatile com.overdrive.app.surveillance.GpuSurveillancePipeline
        recordingModeManagerPipelineOwner;

    // ==================== AVC HAL KEEP-ALIVE ====================
    // Keeps com.byd.avc alive while ACC is ON and pipeline is running.
    // Prevents BYD system from killing the camera app, which destabilizes
    // the HAL and causes "no video signal" on the native DVR.
    // Volatile + lazy-init guarded by AVC_WARMUP_INIT_LOCK. Without these,
    // two worker threads (RMM activate paths, resync retry, IPC) can both
    // observe `avcHalWarmup == null`, both `new AvcHalWarmup()`, both call
    // startKeepAlive() — leaking a duplicate keep-alive thread that never
    // gets stopped (the field holds only the second instance).
    private static volatile com.overdrive.app.camera.AvcHalWarmup avcHalWarmup;
    private static final Object AVC_WARMUP_INIT_LOCK = new Object();

    // ==================== STREAM MODE ====================
    public static final String STREAM_MODE_PRIVATE = "private";  // Local H.264 only
    public static final String STREAM_MODE_PUBLIC = "public";    // Tunnel access
    // Volatile: read/written from HTTP threads + boot init thread.
    private static volatile String streamMode = STREAM_MODE_PRIVATE;

    // ==================== DEVICE ID ====================
    // Volatile: written once at boot, read from many threads. Volatile
    // documents the contract and protects future refactors that might read
    // it before the writing thread starts a worker.
    private static volatile String deviceId = "unknown";

    // ==================== ABRP TELEMETRY ====================
    // All four below are volatile for the same reason as gpuPipeline /
    // recordingModeManager: cross-thread reads from IPC + HTTP + monitor
    // poll threads, hard memory guarantee instead of relying on
    // Thread.start() happens-before.
    private static volatile AbrpTelemetryService abrpTelemetryService;
    private static volatile com.overdrive.app.abrp.SohEstimator sohEstimator;

    // ==================== MQTT CONNECTIONS ====================
    private static volatile com.overdrive.app.mqtt.MqttConnectionManager mqttConnectionManager;

    // ==================== TRIP ANALYTICS ====================
    private static volatile com.overdrive.app.trips.TripAnalyticsManager tripAnalyticsManager;
    private static volatile java.util.concurrent.CompletableFuture<Void> tripAnalyticsInitFuture;
    private static final Object TRIP_ANALYTICS_LIFECYCLE_LOCK = new Object();
    private static final java.util.concurrent.atomic.AtomicBoolean
            TRIP_ANALYTICS_SHUTDOWN_REQUESTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Camera/EGL watchdog restarts are not trip ends. The coordinator checkpoints
    // the open journal before setting this flag; the shutdown hook then releases
    // camera resources without finalizing the trip into a separate card.
    private static final java.util.concurrent.atomic.AtomicBoolean
            PROCESS_RESTART_REQUESTED = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile boolean processRestartIntent;

    // ==================== CHARGING ANALYTICS ====================
    private static volatile com.overdrive.app.charging.ChargingSessionManager chargingSessionManager;

    // ==================== DATA LAYER (RecordingsIndex parallel kick) ====================
    // Completes when the parallel RecordingsIndex.init() + warmupAsync kick
    // returns. shutdown() and any caller that needs a guaranteed-open index
    // joins on this so we don't tear down a half-initialised DB.
    private static volatile java.util.concurrent.CompletableFuture<Void> dataLayerInitFuture;

    // ==================== TELEMETRY DATA COLLECTOR ====================
    private static volatile com.overdrive.app.telemetry.TelemetryDataCollector telemetryDataCollector;

    // ==================== ROADSENSE ====================
    // Daemon-side road-hazard detection brain (D-019/D-023). Reuses the already-
    // initialized BydDataCollector + GpsMonitor singletons; the app-side IMU
    // sidecar feeds it via the IMU_BATCH IPC command (see handleCommand). Driven
    // by the daemon housekeeping tick (onVehicleStatePoll + onWarningTick).
    private static volatile com.overdrive.app.roadsense.RoadSenseController roadSense;

    /** Accessor for the IPC server's IMU_BATCH case. */
    public static com.overdrive.app.roadsense.RoadSenseController getRoadSense() { return roadSense; }

    // ==================== GENAI BYOK ====================
    // Daemon-owned so explicit requests can run while parked in onAndOff mode.
    // The runtime is transport-lazy: attaching only installs a cheap config
    // listener; no HTTP client/thread/socket exists until a user request.
    private static volatile com.overdrive.app.genai.GenAiRuntime genAiRuntime;

    public static com.overdrive.app.genai.GenAiRuntime getGenAiRuntime() {
        return genAiRuntime;
    }

    // ==================== SHARED APP CONTEXT ====================
    // Volatile: written at boot AND re-published on ACC ON via
    // reinitContextDependentComponents (different thread). Without volatile,
    // the re-publication has no happens-before guarantee for arbitrary
    // readers (HTTP, monitors, IPC).
    private static volatile android.content.Context sharedAppContext = null;

    // Raw ACC probes must never call AccMonitor.probeAccState(): that API writes the global ACC
    // cache and dispatches panel/cluster effects before CameraDaemon can validate its observation
    // generation. This lock serializes side-effect-free bodywork reads instead.
    private static final Object ACC_HARDWARE_PROBE_LOCK = new Object();
    private static final long RAW_ACC_PROBE_CALL_TIMEOUT_MS = 1_000L;
    private static final java.util.concurrent.atomic.AtomicReference<Thread>
            RAW_ACC_PROBE_WORKER = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicLong
            RAW_ACC_PROBE_STUCK_DEADLINE_NANOS =
                new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicBoolean
            HARDWARE_QUERY_PROCESS_RECOVERY_REQUESTED =
                new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean
            DEFERRED_ACC_VALIDATION_IN_FLIGHT =
                new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile java.lang.reflect.Method rawAccGetInstanceMethod;
    private static volatile java.lang.reflect.Method rawAccGetPowerLevelMethod;
    private static volatile boolean rawAccReflectionResolved;
    private static volatile boolean rawAccReflectionFailed;

    private static final class AccProbeResult {
        final boolean accIsOff;
        final boolean trustworthy;

        AccProbeResult(boolean accIsOff, boolean trustworthy) {
            this.accIsOff = accIsOff;
            this.trustworthy = trustworthy;
        }
    }

    // ==================== INIT-SURVEILLANCE RETRY (audit R2) ====================
    // Cold-boot transients (AssetManager cookie=0, GpuSurveillancePipeline
    // init throwing on a HAL race, DaemonBootstrap.getContext() crash during
    // framework warm-up) can leave gpuPipeline=null with no retry path —
    // pano recording stays dead until manual daemon kill / reboot. Schedule
    // a bounded exponential-backoff retry from the catch block; gated by a
    // CAS so concurrent callers don't queue duplicate retries.
    private static final java.util.concurrent.atomic.AtomicBoolean initSurveillanceRetryInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicInteger initSurveillanceRetryAttempts =
        new java.util.concurrent.atomic.AtomicInteger(0);
    // FIX (audit R8, finding "initSurveillance retry budget exhaustion"):
    // dropped MAX_RETRIES cap. A bounded cap leaves pano permanently dead
    // when transient HAL flakes outlive the 155s budget — same principle as
    // the user memory rule "no retry cap on any watchdog". Backoff steps
    // 5s/30s/120s/300s and then stays at 300s (5 min) forever; success
    // short-circuits the retry, so unbounded retry is safe here.
    private static final long[] INIT_SURVEILLANCE_RETRY_DELAYS_MS =
        { 5_000L, 30_000L, 120_000L, 300_000L };
    private static final long INIT_SURVEILLANCE_RETRY_MAX_DELAY_MS = 300_000L;

    // ==================== SHARED-CONTEXT WATCHDOG (audit R2) ====================
    // When initSurveillance() succeeds in constructing gpuPipeline but
    // sharedAppContext was null, RecordingModeManager is never created and
    // the boot probe queues pendingAccOn/Off without firing them. Spawn a
    // one-shot poll thread that watches for sharedAppContext to become
    // valid (e.g. system_server warm-up completes), then drives
    // reinitContextDependentComponents() to construct rmm and drain the
    // queue. CAS-guarded so we never spawn two of these.
    private static final java.util.concurrent.atomic.AtomicBoolean contextWatchdogInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long CONTEXT_WATCHDOG_POLL_INTERVAL_MS = 2_000L;
    private static final long CONTEXT_WATCHDOG_MAX_DURATION_MS = 60_000L;

    /** Get the shared app context (for use by other components in this process). */
    public static android.content.Context getAppContext() { return sharedAppContext; }

    /** Check if the shared context is a broken fallback (null base). */
    private static boolean isContextBroken() {
        if (sharedAppContext == null) return true;
        return isContextBrokenFor(sharedAppContext);
    }

    /** Check if a given context is a broken fallback (null base). */
    private static boolean isContextBrokenFor(android.content.Context ctx) {
        if (ctx == null) return true;
        if (ctx instanceof PermissionBypassContext) {
            try {
                ctx.getMainLooper();
                return false;
            } catch (NullPointerException e) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-initialize components that depend on a valid app context.
     * Called on ACC ON after successfully recreating a broken context.
     */
    private static void reinitContextDependentComponents() {
        // Re-init BydDataCollector (was 0/17 devices with broken context)
        try {
            com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
            collector.init(sharedAppContext);
            collector.logSummary();
            log("ACC ON: BydDataCollector re-initialized (" + collector.getData().availableDevices.length + " devices)");
        } catch (Exception e) {
            log("ACC ON: BydDataCollector re-init failed: " + e.getMessage());
        }

        // Start BYD Cloud MQTT subscriber (if credentials configured)
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().startSubscriberIfConfigured();
        } catch (Exception e) {
            log("Cloud subscriber start failed: " + e.getMessage());
        }

        // Re-init GearMonitor with valid context
        try {
            com.overdrive.app.monitor.GearMonitor gearMonitor = com.overdrive.app.monitor.GearMonitor.getInstance();
            gearMonitor.init(sharedAppContext);
            if (telemetryDataCollector != null) {
                gearMonitor.setTelemetrySource(telemetryDataCollector);
            }
            log("ACC ON: GearMonitor re-initialized with valid context");
        } catch (Exception e) {
            log("ACC ON: GearMonitor re-init failed: " + e.getMessage());
        }

        // Re-init TelemetryDataCollector (BYD speed/gear/light devices were unavailable)
        try {
            if (telemetryDataCollector != null) {
                telemetryDataCollector.init(sharedAppContext);
                log("ACC ON: TelemetryDataCollector re-initialized");
            }
        } catch (Exception e) {
            log("ACC ON: TelemetryDataCollector re-init failed: " + e.getMessage());
        }

        // Re-init RecordingModeManager if it wasn't created (sharedAppContext was null at init time)
        if (recordingModeManager == null && gpuPipeline != null) {
            try {
                com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = gpuPipeline;
                com.overdrive.app.recording.RecordingModeManager manager =
                    new com.overdrive.app.recording.RecordingModeManager(
                        sharedAppContext, pipeline);
                recordingModeManagerPipelineOwner = pipeline;
                recordingModeManager = manager;
                log("ACC ON: RecordingModeManager created with valid context");

                try {
                    long pendingGeneration = claimPendingAccState(false);
                    if (pendingGeneration != 0L) {
                        log("ACC ON: replaying pending full ACC ON transition");
                        onAccStateChanged(false, pendingGeneration);
                    } else {
                        pendingGeneration = claimPendingAccState(true);
                        if (pendingGeneration != 0L) {
                            log("ACC ON: replaying pending full ACC OFF transition");
                            onAccStateChanged(true, pendingGeneration);
                        }
                    }
                } catch (Throwable t) {
                    log("WARN: rmm drain on context-recreate failed: " + t.getMessage());
                    forceLatestAccStateReconciliation(
                        "context-recreate pending replay failure");
                }
            } catch (Exception e) {
                log("ACC ON: RecordingModeManager creation failed: " + e.getMessage());
            }
        }

        // Re-init VehicleDataMonitor
        try {
            com.overdrive.app.monitor.VehicleDataMonitor vehicleMonitor =
                com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            vehicleMonitor.init(sharedAppContext);
            if (!vehicleMonitor.isRunning()) {
                vehicleMonitor.start();
            }
            log("ACC ON: VehicleDataMonitor re-initialized");
        } catch (Exception e) {
            log("ACC ON: VehicleDataMonitor re-init failed: " + e.getMessage());
        }

        // Re-drive the notifications subsystem. If the boot-time init lost the
        // sharedAppContext race, the CategoryRegistry couldn't load and
        // initNotifications() returned in degraded mode WITHOUT latching
        // notificationsInitialized — so HistorySink was never subscribed,
        // NotificationStore never opened, and /api/notifications/log 503s
        // (empty Log tab) for the whole session. Now that the context is valid,
        // re-run it: the internal `if (notificationsInitialized) return` makes
        // this a cheap no-op when the boot init already succeeded.
        try {
            initNotifications();
        } catch (Exception e) {
            log("ACC ON: notifications re-init failed: " + e.getMessage());
        }
    }

    /** Context watchdog re-entry is asynchronous to ACC IPC delivery. */
    private static void reinitContextDependentComponentsForCurrentAccState() {
        final long generation;
        final Boolean accIsOff;
        synchronized (parkTerminateLock) {
            generation = accTransitionGeneration;
            accIsOff = latestAccIsOff;
        }
        if (accIsOff != null
                && !isAccTransitionCurrent(generation, accIsOff.booleanValue())) {
            log("Context-dependent reinit skipped — ACC transition was superseded");
            return;
        }
        reinitContextDependentComponents();
        if (accIsOff != null
                && !isAccTransitionCurrent(generation, accIsOff.booleanValue())) {
            forceLatestAccStateReconciliation(
                "stale context-dependent component reinitialization");
        }
    }

    // Build stamp printed at startup so logs identify the running build.
    // BUMP THIS on every code change you intend to deploy + verify.
    private static final String BUILD_TAG = "20260603-coldstart-recfix-1";

    // Lock file for singleton enforcement (ScratchPaths remaps on Shark)
    private static String lockFilePath() {
        com.overdrive.app.util.ScratchPaths.syncFromEnv();
        return com.overdrive.app.util.ScratchPaths.path("camera_daemon.lock");
    }
    private static java.io.RandomAccessFile lockFile;
    private static java.nio.channels.FileLock fileLock;

    public static void main(String[] args) {
        com.overdrive.app.util.ScratchPaths.syncFromEnv();
        com.overdrive.app.util.ScratchPaths.ensureDir();
        initFileLogging();

        // CRITICAL: Acquire singleton lock FIRST - exit if another instance is running
        if (!acquireSingletonLock()) {
            log("ERROR: Another CameraDaemon instance is already running. Exiting.");
            System.exit(1);
            return;
        }

        // Clear any stale screen-deterrent flags left from a previous unclean
        // exit (SIGKILL bypasses our shutdown hook). Without this, AccSentry
        // could see a future screenDeterrentActiveUntilMs and skip backlight
        // off forever, draining the 12V battery until the next ACC cycle.
        try {
            java.util.Map<String, Object> reset = new java.util.HashMap<>();
            reset.put("screenDeterrentActiveUntilMs", 0L);
            reset.put("screenDeterrentForceStop", false);
            com.overdrive.app.config.UnifiedConfigManager.updateValues(
                    "surveillance", reset);
        } catch (Exception ignored) {}

        // SAFETY: if a previous daemon was SIGKILL'd while a driver-cluster
        // blind-spot projection was open, the gauges were left blanked (the
        // shutdown hook couldn't run). The leaked clusterProjection* gate flags
        // tell us to blind-fire the projection-close opcodes (18→0) so the native
        // gauges are restored on this respawn. Stateless / harmless if nothing
        // leaked. Mirrors the screen-deterrent reset above.
        try {
            com.overdrive.app.surveillance.ClusterProjectionController.clearStaleGateAtBoot();
        } catch (Exception ignored) {}

        // SAFETY (companion to the gauge restore above): if that SIGKILL'd daemon was casting a
        // 3rd-party app onto the cluster, the app is now stranded on the closed cluster display
        // with cluster affinity (no stop() / shutdown ran to rehome it). Reparent it back to
        // display 0. Best-effort, runs on its own thread (dumpsys+am can take seconds), and a
        // no-op if nothing was stranded or AMS already reparented it. Reads via forceReload like
        // clearStaleGateAtBoot, so it needs no UnifiedConfigManager.init() first.
        try {
            com.overdrive.app.launcher.ClusterCast.reparentStrandedCastAtBoot();
        } catch (Exception ignored) {}

        // Enable daemon logging for StorageManager (uses DaemonLogger instead of android.util.Log).
        // The StorageManager singleton itself is constructed later, after the HTTP/TCP/IPC
        // server threads are already running — so a flaky external volume can't wedge the
        // daemon's recovery UI. See "RECOVERY-FIRST STARTUP" comment further down.
        com.overdrive.app.storage.StorageManager.enableDaemonLogging();

        log("=== CAMERA DAEMON STARTING ===");
        // Build stamp — bump BUILD_TAG on every change so the field log
        // unambiguously identifies which build is actually running. (Deploys
        // via `adb install -r` do NOT restart the in-memory daemon; this line
        // makes it trivial to confirm a restart actually loaded new code.)
        log("BUILD_TAG: " + BUILD_TAG);
        log("PID: " + android.os.Process.myPid() + ", UID: " + android.os.Process.myUid());

        // Grant all manifest permissions via shell (supplements PermissionBypassContext)
        PermissionGranter.grantAllPermissions(APP_PACKAGE_NAME());

        // Deferred "navigate here": watch for ACC-on to offer a target that a phone/on-car
        // Navigate queued while the car was off. Self-contained (own ACC watcher); guarded
        // so it can never take the daemon down.
        try {
            com.overdrive.app.telenav.DeferredNavManager.start();
        } catch (Throwable t) {
            log("DeferredNavManager start failed: " + t.getMessage());
        }

        // Global exception handler - NEVER let the daemon die from uncaught exceptions
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (!(throwable instanceof ThreadDeath)) {
                log("FATAL: Uncaught exception in " + thread.getName() + ": " + throwable);
                Throwable current = throwable;
                int depth = 0;
                while (current != null && depth < 8) {
                    if (depth > 0) {
                        log("  Caused by: " + current);
                    }
                    for (StackTraceElement element : current.getStackTrace()) {
                        log("    at " + element);
                    }
                    current = current.getCause();
                    depth++;
                }
                // DO NOT kill the daemon - just log and continue
                // The daemon should stay alive even if individual operations fail
            }
        });

        if (Looper.getMainLooper() == null) {
            try {
                Looper.prepareMainLooper();
            } catch (Throwable ignored) {
                if (Looper.myLooper() == null) Looper.prepare();
            }
        }
        mainHandler = new Handler(Looper.getMainLooper() != null ? Looper.getMainLooper() : Looper.myLooper());

        // Parse arguments (sets outputDir if provided)
        parseArguments(args);

        // Initialize outputDir if not set by arguments
        if (outputDir == null) {
            outputDir = PATH_CAMERA_OUTPUT_DIR();
        }

        // Load native libraries
        loadNativeLibraries();

        // Seed the process font system BEFORE any renderer can draw text.
        // CameraDaemon is launched standalone (not forked from Zygote), so on
        // some BSPs (observed on DiLink 5) Android's native default typeface is
        // never initialized — gDefaultTypeface stays null and the first
        // Canvas.drawText/Paint.measureText hits a native LOG_ALWAYS_FATAL_IF
        // ("Assertion failed: src == nullptr && gDefaultTypeface == nullptr")
        // that abort()s the whole process (SIGABRT / exit 134), crash-looping
        // the daemon. DaemonFonts loads a real font straight from disk (which
        // never consults the null default) and seeds the process default so
        // every daemon-side overlay/deterrent/thumbnail text draw is safe.
        // No-op / harmless on DiLink 3/4 where the font system already works.
        try {
            boolean fontsOk = com.overdrive.app.util.DaemonFonts.bootstrap();
            log("Font bootstrap: " + (fontsOk ? "OK (text enabled)"
                    : "FAILED (text disabled — icons/shapes only, daemon stays alive)"));
        } catch (Throwable t) {
            log("Font bootstrap threw (continuing): " + t.getMessage());
        }

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

        // === RECOVERY-FIRST STARTUP ===
        // Construct + spawn the HTTP/TCP/IPC servers BEFORE any subsystem that
        // can block on external state (StorageManager mount probes,
        // GPU pipeline init, BYD HAL reflection). Reasoning:
        //
        //   The HTTP API is the user's only recovery surface — if the daemon
        //   wedges during init (e.g. a configured-but-missing SD/USB volume
        //   makes `sm list-volumes` / `sm mount` hang on certain ROMs), the
        //   user has no way to clear the bad config from the web UI because
        //   the web UI never came up. Pre-v18 the daemon's startup was small
        //   enough that this never bit us; v18.1's USB-storage support added
        //   shell-process calls without timeouts inside the StorageManager
        //   constructor, surfacing the latent fragility.
        //
        //   Handlers null-check gpuPipeline / storageManager and degrade
        //   gracefully when called before those subsystems are ready, so it
        //   is safe to expose the API early. A request that needs a
        //   subsystem returns 503 / a structured "not ready" payload until
        //   it's wired up — the user can still hit /api/storage/config to
        //   force surveillanceStorageType=INTERNAL and unblock the rest.
        tcpServer = new TcpCommandServer(TCP_PORT);
        httpServer = new HttpServer(HTTP_PORT);
        ipcServer = new SurveillanceIpcServer(19877);
        aacIngestServer = new com.overdrive.app.server.AacIngestServer();
        accMonitor = new AccMonitor();

        // Initialize the unified config (migration from legacy + schema fill)
        // BEFORE the IPC server starts accepting commands. The app process now
        // forwards its config writes to us as UPDATE_SECTION/UPDATE_VALUES IPC
        // commands; if the server accepted one before init() ran, the write
        // could interleave with migrateFromLegacy()'s own save. Running init
        // first makes the daemon a clean atomic writer from its first accepted
        // command. (init() is fast — file read + optional one-shot migration.)
        com.overdrive.app.config.UnifiedConfigManager.init();

        try {
            genAiRuntime = new com.overdrive.app.genai.GenAiRuntime();
            genAiRuntime.attach();
            log("GenAI runtime attached (transport remains lazy)");
        } catch (Throwable t) {
            genAiRuntime = null;
            log("GenAI runtime attach failed: " + t.getMessage());
        }

        new Thread(tcpServer::start, "TcpServer").start();
        new Thread(httpServer::start, "HttpServer").start();
        new Thread(ipcServer, "SurveillanceIPC").start();
        new Thread(aacIngestServer, "AacIngest").start();

        // Init app context. This will break the app if run in a thread
        if (sharedAppContext == null) {
            try {
                sharedAppContext = createAppContext();
            } catch (Throwable ignored) {}
        }

        // Dark-panel recovery (boot). If a previous session turned the backlight
        // off while parked and its process was killed, the panel is still dark
        // and nothing is left to wake it. The ACC-sentry daemon does the same
        // check in its own main(), but if THAT process stays dead this is the
        // only remaining recovery — and this one is independently watchdogged.
        // Mirrors the sibling boot-recovery pattern already here (screen-deterrent
        // flags, clearStaleGateAtBoot, reparentStrandedCastAtBoot).
        //
        // Gated on a CONFIRMED ACC-ON reading: waking while genuinely parked
        // would light up a surveilled car. probeAccStateWithBackoff returns true
        // when ACC is confirmed OFF, so !it is our ON signal. turnOn() self-skips
        // when the screen already reads on, so this is a no-op on a normal boot.
        // Off-thread so we never delay HTTP/IPC startup on binder reflection.
        final long panelProbeGeneration = captureAccObservationGeneration();
        new Thread(() -> {
            try {
                AccProbeResult probe = probeAccStateWithBackoff("panel-recovery");
                if (!probe.trustworthy) {
                    log("Panel boot recovery skipped — ACC hardware state was not trustworthy");
                } else if (!probe.accIsOff
                        && isAccObservationCurrent(panelProbeGeneration)) {
                    com.overdrive.app.power.StealthPanel.turnOn(sharedAppContext);
                    if (!isAccObservationCurrent(panelProbeGeneration)) {
                        forceLatestAccStateReconciliation(
                            "stale panel-recovery effect");
                    }
                } else if (!isAccObservationCurrent(panelProbeGeneration)) {
                    log("Panel boot recovery skipped — ACC changed during hardware probe");
                }
            } catch (Throwable t) {
                log("Panel boot recovery failed: " + t.getMessage());
            }
        }, "PanelBootRecovery").start();

        // Notifications subsystem — registry, push subscriptions, sinks.
        // Lives in this process because HttpServer (where the API routes bind)
        // runs here, and every v1 emit source (surveillance, proximity, tyre)
        // lives here too. Init on a background thread because reading APK
        // assets can take a moment and we don't want to delay HTTP startup.
        //
        // RETRY until it takes: initNotifications() reads the CategoryRegistry
        // from APK assets via sharedAppContext. On a cold boot that context can
        // still be null here (system_server warm-up), and a single attempt then
        // returns in degraded mode — leaving HistorySink unsubscribed and the
        // NotificationStore closed for the WHOLE session (empty Log tab, 503 on
        // /api/notifications/log), while Telegram (separate process) keeps
        // working and hides the failure. This bounded poll re-attempts until
        // notificationsInitialized latches true (the method's own guard makes
        // each post-success call a no-op) or the ceiling is hit — decoupled
        // from the ACC/rmm-scoped context watchdog so a parked car that never
        // toggles ACC still self-heals.
        new Thread(() -> {
            final long deadline = System.currentTimeMillis() + 300_000L; // 5 min
            long delayMs = 2_000L;
            while (true) {
                try {
                    initNotifications();
                } catch (Exception e) {
                    log("Notifications init failed: " + e.getMessage());
                }
                if (notificationsInitialized || System.currentTimeMillis() >= deadline) {
                    if (!notificationsInitialized) {
                        log("Notifications init gave up after 5 min — context never became ready");
                    }
                    break;
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                delayMs = Math.min(delayMs * 2, 30_000L); // 2s→4s→…→30s cap
            }
        }, "NotificationsInit").start();

        // (UnifiedConfigManager.init() moved above IPC-server startup so the
        // daemon is a clean atomic writer before it can accept app-forwarded
        // UPDATE_SECTION/UPDATE_VALUES commands.)

        // OTA-survives stickiness for the "Disable Native DVR" toggle.
        // If the user previously disabled com.byd.cdr but a factory reset /
        // OTA / external `pm enable` resurrected it, re-apply pm disable-user
        // here. Cheap (two `pm list packages` calls); no-op when the user
        // never opted in or the package isn't on this trim. We're already
        // running as UID shell so pm calls succeed directly.
        com.overdrive.app.server.OemDashcamApiHandler.enforceStickyDisableIfRequested();
        // Same OTA-survives contract for BYD's traffic monitor. A firmware OTA
        // re-scans com.byd.trafficmonitor and resurrects it, so without this the
        // user had to re-disable it by hand after every single update.
        com.overdrive.app.byd.TrafficMonitorPolicy.enforceStickyDisableIfRequested();
        // OEM Dashcam pipeline: sticky enable is INTENTIONALLY deferred until
        // after the ACC hardware probe at line ~794. The two-axis resolver
        // gates each axis on AccMonitor.isAccOn(), and AccMonitor defaults to
        // accOn=false at boot. If we run the resolver here, a daemon respawn
        // mid-drive (ACC actually ON) would be misclassified as parked and
        // would arm the surveillance axis on top of an actively-driving car.
        // Move sticky-enable below probeAccState so the resolver sees the
        // real ACC state on its first run.
        // Concurrent-AVM probe: write camera.concurrentAvmSupported once
        // when both pano and OEM ids are known. The probe opens both
        // AVMCameras for ~2-5s to verify HAL allows simultaneous clients.
        // Async on a background thread so daemon boot isn't blocked. The
        // result feeds OemDashcamPipeline.applyBitrateBudgetCap so the
        // OEM pipeline's bitrate is correctly capped when concurrent
        // operation is supported. Without this call the probe is dead
        // code and concurrentAvmSupported stays at -1 forever.
        new Thread(() -> {
            try {
                Thread.sleep(15_000);   // wait for pano probe to settle
                com.overdrive.app.camera.ConcurrentAvmProbe.runIfNeeded();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log("ConcurrentAvmProbe boot run failed: " + t.getMessage());
            }
        }, "ConcurrentAvmProbeBoot").start();

        // Load persisted quality settings BEFORE initializing surveillance
        // This ensures the encoder is created with the correct settings
        HttpServer.loadPersistedSettings();

        // Construct StorageManager AFTER the servers are already accepting
        // connections. The constructor is passive (config read + directory
        // resolution only); mount attempts for a missing SD/USB volume happen
        // in startDaemonMaintenance() below on a background thread — those
        // calls are time-bounded (see ensureVolumeMounted) but on a
        // pathological ROM they can still take seconds. Doing this here means
        // the user's web UI is already alive even on slow paths, and the
        // watchdogs below kick in once the singleton exists.
        com.overdrive.app.storage.StorageManager storageManager =
            com.overdrive.app.storage.StorageManager.getInstance();
        storageManager.fixAllPermissions();
        // Daemon-only maintenance: async mount attempts + the one-shot startup
        // reap. These used to run from the constructor, which meant whichever
        // process touched getInstance() first — including the app-UID UI
        // (RecordingViewModel) — ran destructive cleanup with process-local
        // locks and no access to the daemon-only RecordingsIndex H2 (ghost
        // rows, cross-process reap races). The constructor is now passive;
        // this explicit call is the single place maintenance starts.
        storageManager.startDaemonMaintenance();

        // Start the SD-card mount watchdog at daemon boot (instead of only on
        // ACC OFF). The watchdog no-ops when no storage type is set to SD, so
        // it's safe to start unconditionally — but it must run continuously
        // because BYD/Android can unmount the SD card at any time, including
        // while ACC is ON. Stopping it on ACC ON (the previous behavior) left
        // a hole where the HTTP server returned empty recordings until the
        // user cycled ACC OFF→ON.
        storageManager.startSdCardWatchdog();

        // Start the accessibility bind watchdog. Key mapping rides on the
        // app-process KeepAliveAccessibilityService, whose OS bind can wedge in
        // AMS "Binding" on a long-lived heavy app process (keys go dead + the OEM
        // action loops). This daemon runs as a different UID, so it survives the
        // app force-stop and is the stable supervisor: when key mapping is enabled
        // with bindings AND the service is confirmed enabled-but-not-bound, it
        // force-restarts the app so AMS re-binds into a fresh process. No-ops
        // entirely when key mapping is off / unconfigured, and only ever restarts
        // when the keys are already broken — see KeymapApiHandler for the ladder.
        try {
            com.overdrive.app.server.KeymapApiHandler.startAccessibilityWatchdog();
        } catch (Throwable t) {
            log("Keymap a11y watchdog start failed: " + t.getMessage());
        }

        // Data-usage sampler. Arms ONLY if the feature is enabled in config
        // (opt-in, default off), so a disabled feature reads no /proc/net stats
        // and schedules no wakeups — zero overhead when off. resolveAppUid lets it
        // attribute the app-UID (10xxx) traffic alongside the UID-2000 daemons +
        // tunnels. startIfEnabled re-checks on every config-change edge below.
        try {
            com.overdrive.app.monitor.DataUsageMonitor dum =
                    com.overdrive.app.monitor.DataUsageMonitor.getInstance();
            if (sharedAppContext != null) dum.resolveAppUid(sharedAppContext);
            dum.startIfEnabled();
        } catch (Throwable t) {
            log("DataUsageMonitor start failed: " + t.getMessage());
        }

        // Touch the OEM-dashcam cleaner singleton so its constructor runs
        // and (if enabled in saved config) auto-starts the periodic monitor.
        // Without this the cleaner is lazy-initialized on first UI/API hit,
        // meaning a fresh boot with `enabled=true` in config never actually
        // begins reserving SD space until the user opens a settings screen.
        com.overdrive.app.storage.ExternalStorageCleaner.getInstance();

        // Periodic cleanup of our own recordings/surveillance dirs — runs
        // continuously instead of only while a recording is active. This
        // catches the case where the daemon crashed mid-recording leaving
        // the dir at 95%, or the user lowered the size limit while nothing
        // was recording. Cost: one directory walk every 30s; the threshold
        // check exits early if usage is below 90%.
        //
        // Wire the encoder-writing probe at BOOT — before the ticker starts
        // and before initSurveillance() ever constructs gpuPipeline. The
        // probeWired gate inside the ticker early-returns the ENTIRE tick
        // (incl. the encoder-independent trips/proximity categories) until a
        // probe is bound, and the only other wiring point is inside
        // GpuSurveillancePipeline.init(). A persistent pre-init throw (AVM HAL
        // unavailable at cold boot, AssetManager cookie failure, the ~187 MB
        // encoder-construction OOM) means init() is never reached, probeWired
        // stays false forever, and limit enforcement is silently disabled even
        // though the uncapped initSurveillance retry keeps re-arming. The
        // lambda reads the static gpuPipeline field via the null-safe
        // isEncoderWriting() accessor, so it reports idle while the pipeline is
        // null/pre-init — flipping probeWired=true now without ever fail-opening
        // a destructive delete during an active write. init() may re-wire the
        // concrete probe later; that is idempotent (probeWired is already true).
        storageManager.setEncoderWritingProbe(() -> {
            com.overdrive.app.surveillance.GpuSurveillancePipeline p = gpuPipeline; // static field
            return p != null && p.isEncoderWriting();
        });
        storageManager.startPeriodicCleanup();

        // Data-layer kickoff. RecordingsIndex's H2 open + warmup walk is
        // independent of GPU / ABRP / MQTT / TripDB and dominated user-
        // visible "Recordings page takes 5+ min" because it used to run
        // last in the serial init chain. Move it to a dedicated thread
        // that races initSurveillance() — the index is ready before the
        // GPU pipeline is armed in 99% of cases. File watchers wired in
        // the same block so they observe writes that begin during
        // initSurveillance().
        //
        // dataLayerInitFuture lets shutdown() join cleanly without
        // serializing the GPU init behind it. start() wrapped in try/
        // catch so an OOM/Security exception at thread spawn doesn't
        // leave the future forever pending.
        dataLayerInitFuture = new java.util.concurrent.CompletableFuture<>();
        try {
            Thread dataLayerThread = new Thread(() -> {
                try {
                    log("Initializing RecordingsIndex (parallel)...");
                    long t0 = System.currentTimeMillis();
                    boolean idxOk = com.overdrive.app.server.RecordingsIndex.getInstance().init();
                    if (idxOk) {
                        log("RecordingsIndex initialized in "
                                + (System.currentTimeMillis() - t0) + "ms — kicking off async warmup");
                        com.overdrive.app.server.RecordingsIndex.getInstance().warmupAsync();
                        try {
                            RecordingsIndexFileWatcher.getInstance().start();
                        } catch (Throwable t) {
                            log("RecordingsIndexFileWatcher start error: "
                                    + t.getClass().getSimpleName() + ": " + t.getMessage());
                        }
                    } else {
                        log("RecordingsIndex init returned false — API will fall back to direct-FS");
                    }
                } catch (Exception e) {
                    log("RecordingsIndex init error: " + e.getMessage());
                } finally {
                    dataLayerInitFuture.complete(null);
                }
                // Prime the storage size/count caches off the user-visible path.
                // /api/settings/storage serves these stale-while-revalidate, so
                // it only ever blocks on the COLD case (no value cached yet) —
                // which, without this, is exactly the first settings-page load
                // after boot. Doing the first walk here means the settings page
                // gets an instant answer even on its first open. Runs after the
                // future completes so a slow walk can't delay startup.
                try {
                    long t1 = System.currentTimeMillis();
                    com.overdrive.app.storage.StorageManager sm =
                            com.overdrive.app.storage.StorageManager.getInstance();
                    sm.getRecordingsSize();
                    sm.getRecordingsCount();
                    sm.getSurveillanceSize();
                    sm.getSurveillanceCount();
                    log("Storage stat cache primed in " + (System.currentTimeMillis() - t1) + "ms");
                } catch (Throwable t) {
                    log("Storage stat prime failed (non-fatal): " + t.getMessage());
                }
            }, "RecordingsIndexInit");
            dataLayerThread.setDaemon(true);
            dataLayerThread.start();
        } catch (Throwable t) {
            log("RecordingsIndex thread spawn failed: " + t.getMessage());
            dataLayerInitFuture.complete(null);
        }

        // Note: nothing to seed here. Version identity is BuildConfig-derived
        // (AppUpdater.getInstalledVersion() = UPDATE_CHANNEL + "-v" +
        // VERSION_NAME, e.g. "alpha-v26.0") — the running build's true identity,
        // identical in every process and never read from a file, so it's always
        // correct regardless of how the build was installed.


        // ImageReader FPS probe sentinel: when " + ScratchPaths.getDir() + "/run_imagereader_probe
        // exists, run AvmImageReaderFpsProbe BEFORE initSurveillance so the probe
        // has exclusive HAL access. Verifies whether replacing the live pipeline's
        // SurfaceTexture consumer with an ImageReader unblocks the ~8.5 fps panoramic
        // throttle (see CAMERA_FPS_INVESTIGATION.md). Sentinel is consumed (deleted)
        // so the probe runs once per `touch` invocation.
        try {
            File irProbeSentinel = new File(ScratchPaths.path("run_imagereader_probe"));
            if (irProbeSentinel.exists()) {
                log("=== ImageReader probe sentinel detected — running probe ===");
                File irProbeDir = new File(ScratchPaths.path("imagereader_probe"));
                new com.overdrive.app.camera.AvmImageReaderFpsProbe(irProbeDir).run();
                if (!irProbeSentinel.delete()) {
                    log("WARN: Could not delete ImageReader probe sentinel " + irProbeSentinel);
                }
                log("=== ImageReader probe finished — continuing with normal startup ===");
            }
        } catch (Throwable t) {
            log("ImageReader probe invocation failed: " + t.getMessage());
        }

        // Initialize surveillance module (will use loaded settings)
        initSurveillance();

        // Apply persisted settings to GPU pipeline (for runtime changes)
        // Note: Codec/bitrate are already applied during init, but this ensures
        // the config object is in sync and handles any settings that need runtime application
        applyPersistedSettings();

        // The pending-ACC drain used to run HERE, as a ~100-line synchronous
        // block on the startup thread. Field evidence (SD-outage review): the
        // drain's onAccStateChanged dispatch can block for minutes on a
        // wedged SD/USB volume — the daemon then never reaches "Daemon
        // ready", the BYD collector never initialises, and every vehicle
        // automation fails.
        //
        // It is now routed through the existing AccStateReconciler worker:
        // reconcilePendingAccStateFromHardware() already implements the exact
        // drain semantics (trustworthy HW probe + generation-current check +
        // claimPendingAccState + agree→onAccStateChanged /
        // disagree→onObservedAccStateChanged + resetPowerEdge), serialized
        // and with bounded retries on an untrustworthy probe — strictly
        // better than the one-shot inline block, which PRESERVED pending
        // state on a bad probe and then had nobody to replay it.
        //
        // Ordering note: the old comment demanded the drain run before
        // accMonitor.start() so an ACC OFF arriving during initSurveillance()
        // couldn't be missed. AccMonitor.start() is a documented NO-OP
        // ("passive mode — ACC detection by AccSentryDaemon"), so there is no
        // observer to order against; the reconciler's generation guards
        // handle any live IPC event racing the replay. tcpServer /
        // httpServer / ipcServer threads were spawned at the very top of
        // main() (recovery-first startup).
        requestAccTransitionReconciliation(true);
        accMonitor.start();  // no-op (passive mode); kept for the log line

        // Initialize GPS monitor with app context for standard LocationManager access
        initGpsMonitor();

        // Initialize Safe Location Manager (geofence zones)
        com.overdrive.app.surveillance.SafeLocationManager.getInstance().init();

        // RoadSense: daemon-side road-hazard detection (D-019/D-023). BydDataCollector
        // + GpsMonitor are up by now (initGpsMonitor above; collector re-init on ACC ON),
        // which RoadSense reuses (D-020). Never let it block daemon boot.
        //
        // We always CONSTRUCT the controller (so getRoadSense() is non-null for the IPC
        // IMU_BATCH case and the map API), but call attach() — NOT start() — so a DISABLED
        // feature costs ~zero: attach() only start()s the heavy machinery (stores, the 2 Hz
        // ticker, the sync executor) when roadSense.enabled is true, and installs a config
        // listener that start()s/stop()s it live when the user flips the toggle. No daemon
        // restart needed either way.
        try {
            roadSense = new com.overdrive.app.roadsense.RoadSenseController(sharedAppContext);
            roadSense.attach();
            log("RoadSense controller attached (starts iff enabled)");
        } catch (Throwable t) {
            log("RoadSense attach failed: " + t.getMessage());
        }

        // Pre-warm the geocode cache so the first recording's place
        // resolution is a synchronous in-memory hit instead of a 2.5 MB
        // disk read on the recorder-stop path. Gated on at least one flow
        // having geocoding enabled — for the >95% of users who never
        // opt in, paying a 4 MB JSON read at every daemon boot is pure
        // waste. Users who enable the feature later trigger the natural
        // lazy-load path (first put / first get) at no perceptible cost.
        try {
            boolean recordingOn = com.overdrive.app.config.UnifiedConfigManager
                    .isGeocodingEnabledForFlow("recording");
            boolean surveillanceOn = com.overdrive.app.config.UnifiedConfigManager
                    .isGeocodingEnabledForFlow("surveillance");
            if (recordingOn || surveillanceOn) {
                com.overdrive.app.geo.GeoCache.getInstance().ensureLoaded();
            }
        } catch (Throwable t) {
            log("GeoCache prewarm failed: " + t.getMessage());
        }

        // Recordings index warmup. The very first /api/recordings call after
        // a daemon restart used to pay a directory walk + sidecar parse for
        // every recording (100-1000 typical) on the HTTP worker thread, so
        // the first events.html load showed a 1-3 s spinner. The H2-backed
        // RecordingsIndex now persists that work across daemon restarts;
        // warmupAsync() is a no-op once warmupComplete is set, and the first
        // run still happens off the user-visible path.
        //
        // Note: RecordingsIndex.init() + the FileObservers are wired in
        // alongside TripAnalyticsManager init below — they need
        // StorageManager/SohEstimator-adjacent state to be ready first.
        // The legacy RecordingsApiHandler.warmupCache() in-memory cache is
        // superseded by the index and will be removed in a follow-up.

        // Initialize SohEstimator (load persisted SOH — capacity detection deferred until collector is ready)
        try {
            sohEstimator = new SohEstimator();
            sohEstimator.init();
            if (!sohEstimator.isInitializationReady()) {
                log("SohEstimator init deferred; auto-detection remains blocked");
            }
        } catch (Exception e) {
            log("SohEstimator init error: " + e.getMessage());
        }

        // Initialize Trip Analytics on its own thread, kicked HERE so it
        // races initVehicleDataMonitor() (which on long-running installs
        // pays a 100+ s background SOC migration) plus ABRP / MQTT init.
        // The H2 trip database open + orphan-trip cleanup + size_bytes
        // backfill takes ~3-5 s and writes nothing the UI depends on
        // until the user actually shifts out of P, by which point this
        // future is long completed.
        // GearMonitor.getCurrentGear() returns GEAR_P when not yet
        // started (initVehicleDataMonitor wires it later), so the
        // auto-start branch correctly no-ops at boot.
        // tripAnalyticsInitFuture lets shutdown() join cleanly.
        final SohEstimator sohEstSnapshot = sohEstimator;
        tripAnalyticsInitFuture = new java.util.concurrent.CompletableFuture<>();
        try {
            Thread tripAnalyticsThread = new Thread(() -> {
                try {
                    log("Initializing Trip Analytics (parallel)...");
                    long t0 = System.currentTimeMillis();
                    com.overdrive.app.trips.TripAnalyticsManager tam =
                            new com.overdrive.app.trips.TripAnalyticsManager();
                    tam.init(sharedAppContext, telemetryDataCollector, sohEstSnapshot);
                    boolean initializedEnabled = tam.isEnabled();
                    if (!publishTripAnalyticsManager(
                            tam, sohEstSnapshot)) {
                        log("Trip Analytics initialized after shutdown admission; "
                                + "closing it without publication");
                        tam.shutdown();
                        return;
                    }
                    log("Trip Analytics initialized in "
                            + (System.currentTimeMillis() - t0) + "ms (enabled="
                            + initializedEnabled + ")");

                } catch (Exception e) {
                    log("Trip Analytics init error: " + e.getMessage());
                } finally {
                    tripAnalyticsInitFuture.complete(null);
                }
            }, "TripAnalyticsInit");
            tripAnalyticsThread.setDaemon(true);
            tripAnalyticsThread.start();
        } catch (Throwable t) {
            log("Trip Analytics thread spawn failed: " + t.getMessage());
            tripAnalyticsInitFuture.complete(null);
        }

        // Initialize Vehicle Data Monitor + BydDataCollector
        initVehicleDataMonitor();

        // Re-arm an AC switch-off window that was still pending when this process last exited.
        // Must run AFTER the collector is up (the timer consults the AC power state before
        // issuing a shutdown) and only once, before anything else can arm a timer. This is what
        // makes the window survive the onOnly ACC-off self-terminate (parkTerminate ->
        // killProcess) and any watchdog restart; without it a pending shutdown would be lost
        // exactly when the car is parked with the AC still running.
        try {
            com.overdrive.app.byd.AcAutoOffTimer.restore();
        } catch (Exception e) {
            log("AcAutoOffTimer restore error: " + e.getMessage());
        }

        // Now that BydDataCollector is ready, detect car model for accurate capacity
        try {
            if (sohEstimator != null) {
                if (!sohEstimator.isInitializationReady()) {
                    // UnifiedConfig may have been briefly unavailable during
                    // early boot. Retry the authoritative read, but never let
                    // heuristics run while user-nominal authority is unknown.
                    sohEstimator.init();
                }
                if (sohEstimator.isInitializationReady()) {
                    sohEstimator.autoDetectCarModel(sharedAppContext);
                    sohEstimator.seedInitialEstimate();
                    // DB initialization happened above. Re-publish now that an
                    // auto nominal may exist so startup calibration replay gets
                    // another deterministic opportunity.
                    com.overdrive.app.monitor.SocHistoryDatabase
                        .getInstance().setSohEstimator(sohEstimator);
                    com.overdrive.app.abrp.SohEstimator.ResolvedSoh resolvedSoh =
                        sohEstimator.getResolvedSoh();
                    log("SOH: " + (resolvedSoh.getPercent() > 0
                            ? String.format("%.1f%%", resolvedSoh.getPercent())
                            : "unavailable")
                        + " (source: " + resolvedSoh.getSource()
                        + ", capacity: " + String.format("%.2f kWh", sohEstimator.getNominalCapacityKwh()) + ")");
                } else {
                    log("SohEstimator auto-detection deferred; config authority unavailable");
                }
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

        // Start BYD Cloud MQTT subscriber for remote command results + push data
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().startSubscriberIfConfigured();
        } catch (Exception e) {
            log("Cloud MQTT subscriber start failed: " + e.getMessage());
        }

        // Trip Analytics + RecordingsIndex init were both kicked in parallel
        // earlier in main() — see dataLayerInitFuture (after StorageManager)
        // and tripAnalyticsInitFuture (after sohEstimator.init). By the time
        // execution reaches here, both inits are almost always already done;
        // the recordings-index warmup keeps running on its own thread and
        // clients see warming=true responses until it finishes.

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
            // FIX (audit R4): use the same backoff probe as the drain path —
            // a single sentinel reading at boot can falsely report ACC OFF
            // because AccMonitor.accOn defaults to false and probeAccState
            // returns `!accOn` on sentinel power levels. Looping settles
            // transient HAL bluffs before we drop pano CONTINUOUS / DRIVE_MODE
            // mid-drive into a false sentry entry.
            long recoveryProbeGeneration = captureAccObservationGeneration();
            AccProbeResult recoveryProbe = probeAccStateWithBackoff("recovery");
            boolean accIsOff = recoveryProbe.accIsOff;
            if (!recoveryProbe.trustworthy) {
                log("RECOVERY: hardware probe was not trustworthy; awaiting live ACC admission");
                requestTrustedAccHardwareRecovery("untrustworthy startup recovery probe");
            } else if (!isAccObservationCurrent(recoveryProbeGeneration)) {
                log("RECOVERY: hardware probe superseded by live ACC admission");
            } else if (accIsOff) {
                log("RECOVERY: Hardware probe shows ACC OFF — entering sentry mode");
                onObservedAccStateChanged(true, recoveryProbeGeneration, "startup-recovery");
            } else if (!hasPendingAccState(false)
                    && recordingModeManager != null
                    && !recordingModeManager.isAccOn()) {
                // Symmetric ACC ON recovery: daemon restarted while car is on.
                // RecordingModeManager hasn't been seeded by an ACC IPC, so the
                // recording-mode dispatcher won't start CONTINUOUS / DRIVE_MODE
                // pano recording until the user toggles ACC. Seed it directly.
                // pendingAccOn guard avoids fighting the initSurveillance drain
                // when the IPC arrived during init.
                //
                // FIX (audit R5): route through CameraDaemon.onAccStateChanged
                // (accIsOff=false) instead of seeding RMM directly. Direct seed
                // bypassed the dedup cache + full ACC ON side-effect chain
                // (AccMonitor.setAccState, surveillance disable, gear monitor
                // restart). lastDispatchedAccIsOff is null on cold boot, so the
                // dedup short-circuit can't fire and the full chain runs once.
                log("RECOVERY: Hardware probe shows ACC ON — dispatching full ACC ON chain");
                onObservedAccStateChanged(false, recoveryProbeGeneration, "startup-recovery");
            } else if (!accIsOff && recordingModeManager == null) {
                // FIX (audit R1): initSurveillance early-returned with
                // sharedAppContext null, leaving rmm uncreated. The previous
                // boot probe had no branch for this — ACC ON went undelivered
                // until the user toggled ACC. Queue pendingAccOn so the next
                // ACC IPC handler (or the re-init path inside the ACC ON
                // hook) seeds the manager and dispatches recording start.
                // Also seed AccMonitor so downstream consumers don't read
                // the false default before any IPC arrives.
                log("RECOVERY: Hardware probe shows ACC ON but RMM null — "
                    + "queuing pendingAccOn for delayed seed");
                onObservedAccStateChanged(
                    false, recoveryProbeGeneration,
                    "startup-recovery/deferred");
            }
        } catch (Exception e) {
            log("ACC hardware probe error: " + e.getMessage());
        }

        // Now that AccMonitor has been seeded by the hardware probe, the OEM
        // resolver can produce the right desired-state for the current ACC
        // phase. enforceStickyEnableIfRequested submits the recalc to the
        // dedicated lifecycle executor; it runs async so daemon boot isn't
        // blocked by AVC warmup + AVMCamera open inside the OEM pipeline.
        com.overdrive.app.server.OemDashcamApiHandler.enforceStickyEnableIfRequested();

        // Periodic OEM self-heal. The OEM lifecycle is edge-driven (ACC IPC,
        // surveillance IPC, config POST, stream-view, pano-ready); a start
        // that raced or transiently failed — most visibly in the DashCam+Pano
        // dual-AVMCamera layout where OEM and pano contend for the HAL handle
        // at ACC ON — otherwise stayed dead until the next incidental edge.
        // This ticker re-drives the (idempotent) resolver every 30s so a
        // missed/lost start recovers within ~30s instead of mid-drive.
        com.overdrive.app.server.OemDashcamApiHandler.startSelfHealTicker();

        // Periodic blind-spot self-arm. The app arms the BS lane only on the
        // com.byd.action.ACC_ON broadcast EDGE; on a hard reboot ACC is already
        // ON before the app's receiver exists, so that edge is missed and the
        // lane stays dead until the user manually hits debug-preview. The daemon
        // knows ACC=ON from its own hardware probe, so this idempotent resolver
        // (no-op once armed) brings the lane up independently within ~30s. Also
        // re-driven inline from the daemon ACC-on edge + the pano-ready hook so
        // the common cold-reboot case arms in seconds, not at the first tick.
        com.overdrive.app.server.StreamingApiHandler.startBsSelfHealTicker();
        com.overdrive.app.server.StreamingApiHandler.resolveBlindSpotLifecycle();

        // Recover gracefully if the app cast onto the cluster (or the persisted ACC-on
        // auto-start app) is uninstalled: tear down a live cast + mirror in the SF-safe
        // order and clear a stale auto-start package. Runtime-registered (manifest
        // PACKAGE_REMOVED receivers don't fire on API 26+); no-op if no app context.
        try {
            com.overdrive.app.receiver.CastPackageWatcher.register(getAppContext());
        } catch (Throwable t) {
            log("CastPackageWatcher register failed: " + t.getMessage());
        }

        // Register the cluster-view mirror Binder service so the Projection screen can hand
        // its TextureView Surface across the process boundary for SurfaceFlinger to composite
        // the cluster into (the resize-correct mirror path — HTTP can't carry a Surface).
        // uid-2000 daemon + live Looper make ServiceManager.addService valid here. Guarded.
        try {
            com.overdrive.app.surveillance.ClusterViewMirrorService.register();
        } catch (Throwable t) {
            log("ClusterViewMirrorService register failed: " + t.getMessage());
        }

        try {
            if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
                com.overdrive.app.daemon.sentry.DiLink5PowerDiagnostics.start(getAppContext());
            }
        } catch (Throwable t) {
            log("DiLink5PowerDiagnostics start failed: " + t.getMessage());
        }

        log("Daemon ready on TCP:" + TCP_PORT + " HTTP:" + HTTP_PORT);

        // Periodic memory monitor — mirrors AccSentryDaemon.logMemoryStatus().
        // Without this, post-mortem on a 1-2hr park silently dying tells us
        // nothing about whether the cause was OOM (RSS climbing toward limit)
        // or HAL-cascade (RSS flat, native FD count climbing, etc.). Cheap:
        // one ActivityManager.getMemoryInfo() + one Runtime.totalMemory()
        // every 5 minutes.
        startPeriodicMemoryLogging();

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
            // If ACC is ON, warm up the camera HAL first on a background thread
            // to avoid blocking the HTTP/TCP handler thread for 4 seconds.
            if (AccMonitor.isAccOn() && avcHalWarmup != null) {
                final boolean fViewOnly = viewOnly;
                new Thread(() -> {
                    avcHalWarmup.warmupAndWait();
                    startPipelineInternal(viewId, fViewOnly);
                }, "CameraWarmup").start();
            } else {
                startPipelineInternal(viewId, viewOnly);
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

    /**
     * Internal: starts the GPU pipeline after any warmup delay.
     */
    private static void startPipelineInternal(int viewId, boolean viewOnly) {
        if (gpuPipeline == null || gpuPipeline.isRunning()) return;
        try {
            gpuPipeline.start(!viewOnly);
            log("GPU pipeline started for camera " + viewId);

            if (!viewOnly) {
                log("Auto-recording enabled (will start when recorder ready)");
            } else {
                log("View-only mode - recording NOT started");
            }

            // Start AVC keep-alive if ACC is ON
            startAvcKeepAliveIfNeeded();

        } catch (Exception e) {
            log("ERROR: Failed to start GPU pipeline: " + e.getMessage());
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
                stopAvcKeepAlive();
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
            stopAvcKeepAlive();
        }
    }


    // GPU pipeline handles camera internally - no separate camera management needed

    // ==================== AVC HAL KEEP-ALIVE ====================

    /**
     * Starts the AVC keep-alive watchdog.
     *
     * On legacy cars: gated on the GPU pipeline being live so we don't
     * waste am-start cycles when no consumer is using the camera.
     *
     * On dilink4 (byd_apa firmware): starts unconditionally. The AVM HAL
     * gates frame delivery on com.byd.avc being a co-consumer of
     * vendor.byd.avm; if BYD's reaper kills AVC at any point the next
     * camera open lands on a zombie HAL that returns all-zero buffers.
     * We keep AVC warm at all times — ACC ON or OFF — so a streaming-
     * client connect, surveillance arm, or recording start can never
     * race a fresh AVC reap.
     */
    public static void startAvcKeepAliveIfNeeded() {
        // Double-checked locking: cheap volatile read on the hot path,
        // synchronized init on the cold path. Prevents two concurrent
        // callers from each instantiating AvcHalWarmup and orphaning a
        // running keep-alive thread.
        com.overdrive.app.camera.AvcHalWarmup local = avcHalWarmup;
        if (local == null) {
            synchronized (AVC_WARMUP_INIT_LOCK) {
                local = avcHalWarmup;
                if (local == null) {
                    local = new com.overdrive.app.camera.AvcHalWarmup();
                    avcHalWarmup = local;
                }
            }
        }
        boolean dilink4 = isDilink4ModeActive();
        boolean pipelineLive = gpuPipeline != null && gpuPipeline.isRunning();
        if (dilink4 || pipelineLive) {
            if (!local.isActive()) {
                local.startKeepAlive();
                log("AVC keep-alive started (dilink4=" + dilink4
                    + ", pipelineLive=" + pipelineLive
                    + ", accOn=" + AccMonitor.isAccOn() + ")");
            }
            // Heartbeat is dilink4-only and self-gates inside; safe on legacy.
            startCameraActiveHeartbeatIfNeeded();
        }
    }

    /**
     * Stops the AVC keep-alive watchdog when the pipeline stops.
     *
     * On dilink4 this is a no-op — see {@link #startAvcKeepAliveIfNeeded}
     * for why AVC must stay alive across pipeline lifecycles. Use
     * {@link #stopAvcKeepAliveForShutdown} on daemon teardown.
     */
    public static void stopAvcKeepAlive() {
        if (isDilink4ModeActive()) {
            // Skip — dilink4 needs AVC alive for the next pipeline start
            // (streaming client connect, sentry arm, recording start).
            return;
        }
        stopAvcKeepAliveForShutdown();
    }

    /**
     * Force-stops the AVC keep-alive watchdog. Used on daemon shutdown
     * regardless of camera mode — at that point we're tearing everything
     * down and there will be no future camera consumer.
     */
    public static void stopAvcKeepAliveForShutdown() {
        if (avcHalWarmup != null && avcHalWarmup.isActive()) {
            avcHalWarmup.stopKeepAlive();
            log("AVC keep-alive stopped");
        }
        stopCameraActiveHeartbeat();
    }

    // ==================== CAMERA-ACTIVE HEARTBEAT ====================
    //
    // AccSentryDaemon (UID 2000, separate process) runs a 10s keepalive
    // that calls setBacklightState(false). On byd_apa firmware that tears
    // down the AVMCamera preview surface and emits HAL event=8. To stop
    // that, we refresh a lease deadline CAMERA_ACTIVE_LEASE_MS (8s) ahead of
    // now every CAMERA_ACTIVE_TICK_MS (4s) while the GPU pipeline is consuming
    // frames. The lease is a single timestamp in a dedicated sidecar file
    // (" + ScratchPaths.getDir() + "/camera_active_lease), NOT a unified-config key — see
    // writeCameraActiveLease for why the shared-config channel was too
    // expensive at a 4s cadence. AccSentryDaemon reads the sidecar cross-process
    // and skips its backlight-off tick while the lease is live. Gated on
    // cameraMode=dilink4 — legacy cars don't have the HAL-display coupling and
    // shouldn't suppress power-save.
    private static volatile Thread cameraActiveHeartbeatThread = null;
    private static volatile boolean cameraActiveHeartbeatRunning = false;
    private static final long CAMERA_ACTIVE_TICK_MS = 4_000L;
    private static final long CAMERA_ACTIVE_LEASE_MS = 8_000L;

    public static void startCameraActiveHeartbeatIfNeeded() {
        if (gpuPipeline == null || !gpuPipeline.isRunning()) return;
        if (!isDilink4ModeActive()) return;
        if (cameraActiveHeartbeatRunning) return;
        cameraActiveHeartbeatRunning = true;
        cameraActiveHeartbeatThread = new Thread(() -> {
            log("Camera-active heartbeat started (dilink4, " +
                CAMERA_ACTIVE_LEASE_MS + "ms lease, refreshed every " +
                CAMERA_ACTIVE_TICK_MS + "ms)");
            while (cameraActiveHeartbeatRunning) {
                try {
                    if (gpuPipeline != null && gpuPipeline.isRunning()) {
                        publishCameraActiveLease();
                    }
                    Thread.sleep(CAMERA_ACTIVE_TICK_MS);
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    log("Camera-active heartbeat error: " + t.getMessage());
                    try { Thread.sleep(1_000L); } catch (InterruptedException ie) { break; }
                }
            }
            // Best-effort lease clear so AccSentryDaemon stops suppressing
            // the backlight as soon as we tear the pipeline down. A stuck
            // future-dated lease would keep the screen on for up to 8s
            // after pipeline.stop() — recoverable but messy.
            try { clearCameraActiveLease(); } catch (Throwable ignored) {}
            log("Camera-active heartbeat stopped");
        }, "CamActiveHeartbeat");
        cameraActiveHeartbeatThread.setDaemon(true);
        cameraActiveHeartbeatThread.start();
    }

    public static void stopCameraActiveHeartbeat() {
        if (!cameraActiveHeartbeatRunning) return;
        cameraActiveHeartbeatRunning = false;
        Thread t = cameraActiveHeartbeatThread;
        if (t != null) t.interrupt();
        cameraActiveHeartbeatThread = null;
    }

    /**
     * Read bodywork power without mutating AccMonitor. A definitive result is admitted later via
     * onObservedAccStateChanged(), which is the same generation-linearized path used by IPC edges.
     */
    private static AccProbeResult probeAccStateWithBackoff(String tag) {
        boolean lastReading = !com.overdrive.app.monitor.AccMonitor.isAccOn();
        for (int attempt = 0; attempt < 3; attempt++) {
            Integer level;
            synchronized (ACC_HARDWARE_PROBE_LOCK) {
                level = readRawAccPowerLevelBounded();
            }
            if (level != null && level >= 0 && level <= 3) {
                boolean reading = level < 2;
                if (reading != lastReading && attempt > 0) {
                    log("WARN: ACC HW probe (" + tag + ") disagreed across attempts "
                        + "(was=" + lastReading + " now=" + reading + " attempt="
                        + attempt + ")");
                }
                lastReading = reading;
                log("ACC HW probe (" + tag + ") returned trustworthy accIsOff="
                    + reading + " level=" + level + " on attempt " + (attempt + 1));
                return new AccProbeResult(reading, true);
            }
            log("WARN: ACC HW probe (" + tag + ") attempt " + attempt
                + " returned no clean power level; retrying");
            try {
                Thread.sleep(200);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean cacheFallback = !com.overdrive.app.monitor.AccMonitor.isAccOn();
        log("WARN: ACC HW probe (" + tag + ") remained untrustworthy; "
            + "preserving state instead of admitting accIsOff=" + cacheFallback);
        return new AccProbeResult(cacheFallback, false);
    }

    /**
     * Deferred lock/schedule effects must not trust AccMonitor's potentially stale cache.
     * The raw Binder read is isolated behind the existing bounded worker; an unavailable or
     * ambiguous read fails closed and leaves the periodic watchdog/retry path to try again.
     */
    private static boolean validateAccOffForDeferredEffect(
            long transitionGeneration, String tag) {
        if (!isAccTransitionCurrent(transitionGeneration, true)) {
            return false;
        }
        if (!DEFERRED_ACC_VALIDATION_IN_FLIGHT.compareAndSet(false, true)) {
            log("Skipping deferred " + tag
                + " effect — ACC hardware validation already in flight");
            return false;
        }
        long observationGeneration = captureAccObservationGeneration();
        try {
            Integer level;
            synchronized (ACC_HARDWARE_PROBE_LOCK) {
                level = readRawAccPowerLevelBounded();
            }
            if (!isAccObservationCurrent(observationGeneration)
                    || observationGeneration != transitionGeneration) {
                return false;
            }
            if (level == null || level < 0 || level > 3) {
                log("Skipping deferred " + tag
                    + " effect — current ACC hardware state is unavailable");
                return false;
            }
            boolean accIsOff = level < 2;
            if (!accIsOff) {
                log("Skipping deferred " + tag
                    + " effect — bounded hardware probe shows ACC ON");
                onObservedAccStateChanged(
                    false, observationGeneration, "deferred-" + tag);
                return false;
            }
            return isAccTransitionCurrent(transitionGeneration, true);
        } finally {
            DEFERRED_ACC_VALIDATION_IN_FLIGHT.set(false);
        }
    }

    private static Integer readRawAccPowerLevelBounded() {
        Thread existing = RAW_ACC_PROBE_WORKER.get();
        if (existing != null && existing.isAlive()) {
            existing.interrupt();
            escalateStuckHardwareQueryIfExpired(
                RAW_ACC_PROBE_STUCK_DEADLINE_NANOS,
                "raw ACC Binder query");
            return null;
        }
        RAW_ACC_PROBE_STUCK_DEADLINE_NANOS.set(0L);

        java.util.concurrent.atomic.AtomicReference<Integer> result =
            new java.util.concurrent.atomic.AtomicReference<>();
        final Thread worker;
        try {
            worker = new Thread(() -> {
                try {
                    result.set(readRawAccPowerLevel());
                } catch (Throwable failure) {
                    log("Raw ACC power probe failed: " + failure.getMessage());
                } finally {
                    RAW_ACC_PROBE_WORKER.compareAndSet(
                        Thread.currentThread(), null);
                }
            }, "RawAccPowerProbe");
            worker.setDaemon(true);
        } catch (Throwable creationFailure) {
            log("Raw ACC power probe worker creation failed: "
                + creationFailure.getMessage());
            return null;
        }
        if (!RAW_ACC_PROBE_WORKER.compareAndSet(existing, worker)) {
            return null;
        }
        try {
            worker.start();
        } catch (Throwable startFailure) {
            RAW_ACC_PROBE_WORKER.compareAndSet(worker, null);
            log("Raw ACC power probe worker start failed: "
                + startFailure.getMessage());
            return null;
        }
        try {
            worker.join(RAW_ACC_PROBE_CALL_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            return null;
        }
        if (worker.isAlive()) {
            worker.interrupt();
            armHardwareQueryRecoveryDeadline(
                RAW_ACC_PROBE_STUCK_DEADLINE_NANOS);
            log("Raw ACC power probe exceeded "
                + RAW_ACC_PROBE_CALL_TIMEOUT_MS + "ms");
            return null;
        }
        RAW_ACC_PROBE_WORKER.compareAndSet(worker, null);
        RAW_ACC_PROBE_STUCK_DEADLINE_NANOS.set(0L);
        return result.get();
    }

    private static void armHardwareQueryRecoveryDeadline(
            java.util.concurrent.atomic.AtomicLong deadline) {
        long recoveryDeadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                HARDWARE_QUERY_RECOVERY_GRACE_MS);
        deadline.compareAndSet(0L, recoveryDeadline);
    }

    private static void escalateStuckHardwareQueryIfExpired(
            java.util.concurrent.atomic.AtomicLong deadline,
            String label) {
        long value = deadline.get();
        if (value == 0L) {
            armHardwareQueryRecoveryDeadline(deadline);
            return;
        }
        if (System.nanoTime() - value >= 0L) {
            requestHardwareQueryProcessRecovery(
                label + " remained stuck after "
                    + HARDWARE_QUERY_RECOVERY_GRACE_MS + "ms");
        }
    }

    private static void requestHardwareQueryProcessRecovery(
            String reason) {
        if (!HARDWARE_QUERY_PROCESS_RECOVERY_REQUESTED.compareAndSet(
                false, true)) {
            return;
        }
        log("Requesting trip-safe daemon restart: " + reason);
        requestProcessRestartPreservingTrip(
            "hardware query recovery: " + reason);
    }

    private static Integer readRawAccPowerLevel() throws Exception {
        if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            // DiLink 5.0 (Snapdragon SA8155P / Android Automotive 11)
            // Uses dumpsys car_service Power Mute State or PowerManager/interactive
            try {
                if (com.overdrive.app.monitor.AccMonitor.probeAccState(sharedAppContext)) {
                    return 0; // POWER_LEVEL_OFF (Standby/Sleep/Parked)
                } else {
                    return 2; // POWER_LEVEL_ON (Active)
                }
            } catch (Throwable ignored) {}
        }
        if (!rawAccReflectionResolved && !rawAccReflectionFailed) {
            synchronized (CameraDaemon.class) {
                if (!rawAccReflectionResolved && !rawAccReflectionFailed) {
                    try {
                        Class<?> cls = Class.forName(
                            "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
                        rawAccGetInstanceMethod = cls.getMethod(
                            "getInstance", android.content.Context.class);
                        rawAccGetPowerLevelMethod = cls.getMethod("getPowerLevel");
                        rawAccReflectionResolved = true;
                    } catch (Exception reflectionFailure) {
                        rawAccReflectionFailed = true;
                        throw reflectionFailure;
                    }
                }
            }
        }
        if (!rawAccReflectionResolved) return null;
        Object device = rawAccGetInstanceMethod.invoke(null, sharedAppContext);
        if (device == null) return null;
        Object value = rawAccGetPowerLevelMethod.invoke(device);
        return value instanceof Number
            ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    private static boolean isDilink4ModeActive() {
        try {
            org.json.JSONObject c = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (c == null) return false;
            return "dilink4".equalsIgnoreCase(c.optString("cameraMode", "default"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Public alias for cross-class callers (RecordingModeManager,
     *  GpuSurveillancePipeline, SafeLocationManager). */
    public static boolean isDilink4ModeActiveStatic() {
        return isDilink4ModeActive();
    }

    // Dedicated sidecar file for the camera-active lease — a single timestamp
    // (millis-epoch deadline), NOT a key in the shared unified config. This is
    // written every 4s while the pipeline runs; routing it through
    // updateSection("surveillance",…) meant every 4s taking the cross-process
    // config file lock, re-reading + re-parsing + re-serializing the whole ~10KB
    // config, atomic-renaming it, and firing the listener fanout — AND bumping the
    // config mtime, which defeated the mtime-gated loadConfig cache that RoadSense
    // (500ms tick), KeyMapDispatcher, StatusOverlayService etc. rely on, forcing
    // THEM to re-parse too and stalling every peer process on the shared lock. A
    // tiny sidecar file the other daemon reads directly is O(bytes) with no lock,
    // no parse, and no cross-subsystem cache invalidation. The reader
    // (AccSentryDaemon.isCameraPipelineActive) is a different process at the same
    // UID 2000, so a plain file in " + ScratchPaths.getDir() + " is the right cross-process channel.
    private static final String CAMERA_ACTIVE_LEASE_PATH =
        ScratchPaths.path("camera_active_lease");

    private static void publishCameraActiveLease() {
        writeCameraActiveLease(System.currentTimeMillis() + CAMERA_ACTIVE_LEASE_MS);
    }

    private static void clearCameraActiveLease() {
        writeCameraActiveLease(0L);
    }

    private static void writeCameraActiveLease(long deadlineMs) {
        try {
            byte[] payload = Long.toString(deadlineMs)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            // Atomic write: write to a temp sibling then rename, so a concurrent
            // reader never sees a half-written value (a torn read would only ever
            // fail safe to "not active" anyway, but the rename keeps it clean).
            // NO fsync: this lease is EPHEMERAL — it self-expires in 8s and is
            // meaningless across a reboot, and cross-process visibility to the
            // peer daemon is via the page cache (fsync isn't needed for that). An
            // fsync every 4s on the car-ON path would be a real disk-flush cost on
            // exactly the path this change exists to make cheap, so it's omitted.
            java.io.File tmp = new java.io.File(CAMERA_ACTIVE_LEASE_PATH + ".tmp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                fos.write(payload);
            }
            java.io.File dest = new java.io.File(CAMERA_ACTIVE_LEASE_PATH);
            if (!tmp.renameTo(dest)) {
                // Rename can fail across some FUSE quirks — fall back to a direct
                // overwrite (the value is a single token, torn read fails safe).
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dest)) {
                    fos.write(payload);
                }
                tmp.delete();
            }
            // Match the UnifiedConfigManager invariant for " + ScratchPaths.getDir() + " files:
            // world-readable/writable so a non-creator UID could open it. The only
            // reader today is the same-UID (2000) acc_sentry daemon, so this isn't
            // strictly required now, but it keeps parity with the config files and
            // future-proofs against an app-UID (10xxx) reader. Cheap: perms are
            // sticky to the inode, so this is a no-op stat/chmod once set.
            dest.setReadable(true, false);
            dest.setWritable(true, false);
        } catch (Throwable t) {
            // Throttled by the caller's cadence; a failed lease write just means
            // the peer daemon may run one backlight-off tick during active camera,
            // which self-corrects on the next 4s write.
            log("writeCameraActiveLease failed: " + t.getMessage());
        }
    }

    // ==================== GETTERS ====================

    public static java.util.Map<Integer, Object> getVirtualViews() {
        // GPU pipeline doesn't use VirtualView - return empty map for compatibility
        return new java.util.HashMap<>();
    }

    public static boolean isRunning() {
        return running.get();
    }

    public static HttpServer getHttpServer() {
        return httpServer;
    }

    /**
     * The live MQTT connection manager, or null if MQTT init failed / hasn't run. Exposed
     * for the automation "Publish MQTT" action to fan a message out to every active
     * connection. Null-check at the call site — a car with no MQTT setup returns null.
     */
    public static com.overdrive.app.mqtt.MqttConnectionManager getMqttConnectionManager() {
        return mqttConnectionManager;
    }

    /**
     * Periodic memory monitor. Daemon-process equivalent of
     * {@code AccSentryDaemon.logMemoryStatus()}: emits ActivityManager
     * memory info plus our Java heap usage every 5 minutes. The daemon
     * runs for hours unattended during sentry mode; without this, a slow
     * heap leak (motion-event storm under no-AI, MediaCodec slot leak
     * across encoder reinits, etc.) is invisible in cam_daemon.log until
     * the LMK or SIGABRT kill lands.
     */
    private static java.util.concurrent.ScheduledExecutorService memoryLogScheduler;
    // Dedicated scheduler for the geo backfill sweep — kept OFF memoryLogScheduler
    // because the sweep does blocking Nominatim I/O that must not stall the memory
    // watchdog / cache-prune / index-reconcile ticks.
    private static java.util.concurrent.ScheduledExecutorService geoBackfillScheduler;

    public static void startPeriodicMemoryLogging() {
        if (memoryLogScheduler != null) return;
        memoryLogScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MemoryLog");
            t.setDaemon(true);
            return t;
        });
        memoryLogScheduler.scheduleAtFixedRate(
            CameraDaemon::logMemoryStatus,
            1, 5, java.util.concurrent.TimeUnit.MINUTES);
        // Piggy-back recording-cache prune onto the same scheduler. The
        // RecordingsApiHandler.RECORDING_CACHE is invalidated synchronously
        // when the daemon itself rotates an mp4 (HardwareEventRecorderGpu)
        // or storage cleanup deletes one (StorageManager). External SD
        // edits (eject + delete on a host PC, manual file-explorer delete
        // from the app) leave phantom entries that can only be reaped here.
        //
        // Wrapped in a try/catch: ScheduledExecutorService.scheduleAtFixedRate
        // permanently cancels a recurring task on the first uncaught throw.
        // A flapping SD mount could surface a transient IOException out of
        // File.exists(); without this guard one bad tick silently kills the
        // prune cadence for the rest of the daemon's life.
        // RecordingsIndex reconcile — backstop for FileObserver event drops
        // on FUSE-mounted SD/USB volumes. Patches missing rows + drops
        // phantoms; also covers the external-SD edit case (eject + delete on
        // a host PC, manual file-explorer delete) that leaves phantom cache
        // entries no in-process invalidation can catch.
        //
        // DE-DUPLICATED (perf): this used to be TWO scheduleAtFixedRate tasks
        // on the same 60-minute cadence — one calling
        // RecordingsApiHandler.pruneRecordingCache(), one calling
        // RecordingsIndex.reconcile() directly. pruneRecordingCache() is a
        // thin wrapper whose entire body is that same reconcile() call, so
        // the index was reconciled twice per hour, back to back. On a large
        // library each pass is a full stat() sweep over every indexed row
        // through the FUSE bridge, so the duplicate was pure cost. reconcile()
        // is idempotent (documented at RecordingsIndex.reconcile), so the
        // second pass could never observe anything the first one missed.
        //
        // Kept the direct call rather than the wrapper: one less indirection,
        // and the wrapper stays available for any future caller.
        //
        // Wrapped in a try/catch: ScheduledExecutorService.scheduleAtFixedRate
        // permanently cancels a recurring task on the first uncaught throw.
        // A flapping SD mount could surface a transient IOException out of
        // File.exists(); without this guard one bad tick silently kills the
        // reconcile cadence for the rest of the daemon's life.
        memoryLogScheduler.scheduleAtFixedRate(() -> {
            try {
                com.overdrive.app.server.RecordingsIndex.getInstance()
                        .requestReconcile("hourly-integrity");
            } catch (Throwable t) {
                log("RecordingsIndex reconcile request failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, 60, 60, java.util.concurrent.TimeUnit.MINUTES);

        log("Periodic memory monitor started (5-minute cadence); index reconcile armed (60-minute cadence)");

        // Geo place-name backfill — re-resolves recordings that have a GPS fix
        // but no resolved place (cache-miss at record time with no working async
        // retry; see GeoBackfillSweep). Bounded per-tick + age-gated + online-gated;
        // cheap stat-only pass on a steady-state library. 10-minute cadence: prompt
        // enough that a just-recorded first-visit clip gets tagged soon, light on
        // the Nominatim budget.
        //
        // DEDICATED scheduler (NOT memoryLogScheduler): the sweep does SYNCHRONOUS
        // Nominatim I/O (resolveBlocking, ~10s worst case on a routable-but-dead
        // endpoint before the rate-limiter cooldown kicks in). The shared scheduler
        // also runs the leak/OOM memory watchdog + cache prune + index reconcile;
        // a stuck geocode tick on it would stall those. Its own single thread keeps
        // the blocking I/O off the watchdog's cadence. Same try/catch guard so one
        // bad tick (flapping SD, network burp) can't cancel the recurring task.
        geoBackfillScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GeoBackfill");
            t.setDaemon(true);
            return t;
        });
        geoBackfillScheduler.scheduleAtFixedRate(() -> {
            try {
                com.overdrive.app.geo.GeoBackfillSweep.run();
            } catch (Throwable t) {
                log("Geo backfill tick failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, 5, 10, java.util.concurrent.TimeUnit.MINUTES);
        log("Geo backfill armed on dedicated thread (10-minute cadence)");

        // Privacy-preserving DAU/MAU ping (analytics-edge). At most ONE tiny POST
        // per install per UTC day; maybePing() short-circuits cheaply when already
        // pinged today or disabled (analytics.enabled kill-switch, default on). We
        // tick HOURLY so a day-boundary crossing (or a boot after midnight) sends
        // promptly; the day guard + server-side (day,id) PK make repeat ticks
        // no-ops. Piggy-backed on memoryLogScheduler: the work is a rare, short,
        // proxy-aware HTTP call that swallows all failures internally, so it can't
        // stall the watchdog cadence. Same try/catch guard as the ticks above —
        // scheduleAtFixedRate cancels a recurring task on first uncaught throw.
        memoryLogScheduler.scheduleAtFixedRate(() -> {
            try {
                com.overdrive.app.analytics.AnalyticsPinger.INSTANCE.maybePing(
                    System.currentTimeMillis());
            } catch (Throwable t) {
                log("Analytics ping tick failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, 2, 60, java.util.concurrent.TimeUnit.MINUTES);
        log("Analytics DAU/MAU ping armed (hourly check, <=1 send/day)");
    }

    private static void logMemoryStatus() {
        try {
            Runtime rt = Runtime.getRuntime();
            long heapTotalMB = rt.totalMemory() / 1024 / 1024;
            long heapFreeMB = rt.freeMemory() / 1024 / 1024;
            long heapUsedMB = heapTotalMB - heapFreeMB;
            long heapMaxMB = rt.maxMemory() / 1024 / 1024;

            String sysLine = "";
            android.content.Context ctx = sharedAppContext;
            if (ctx != null) {
                try {
                    android.app.ActivityManager.MemoryInfo memInfo =
                        new android.app.ActivityManager.MemoryInfo();
                    android.app.ActivityManager am = (android.app.ActivityManager)
                        ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.getMemoryInfo(memInfo);
                        long availMB = memInfo.availMem / 1024 / 1024;
                        long totalMB = memInfo.totalMem / 1024 / 1024;
                        sysLine = String.format(
                            ", sys.avail=%dMB / %dMB, lowMem=%s, threshold=%dMB",
                            availMB, totalMB, memInfo.lowMemory,
                            memInfo.threshold / 1024 / 1024);
                    }
                } catch (Exception ignored) {}
            }

            // Native heap (direct ByteBuffers, MediaCodec internal pools, GL).
            long nativeHeapMB = android.os.Debug.getNativeHeapAllocatedSize() / 1024 / 1024;
            long nativeSizeMB = android.os.Debug.getNativeHeapSize() / 1024 / 1024;

            log(String.format(
                "MEM: heap=%d/%dMB (max=%d), native=%d/%dMB%s",
                heapUsedMB, heapTotalMB, heapMaxMB,
                nativeHeapMB, nativeSizeMB, sysLine));
        } catch (Throwable t) {
            log("logMemoryStatus error: " + t.getMessage());
        }
    }

    /**
     * Sentinel file that signals the shell watchdog wrapper to NOT restart the daemon.
     * Written by shutdown() when the daemon is intentionally disabled (UI/Telegram).
     * The watchdog script checks for this file before each restart attempt.
     * To re-enable, delete this file and start the watchdog script again.
     */
    private static final String DISABLE_SENTINEL = ScratchPaths.path("camera_daemon.disabled");

    public static void shutdown() {
        shutdownInternal(true);
    }

    /**
     * "Vehicle ON only" parked terminate. Called at the END of the ACC-off branch (after
     * all mandatory finalize + the G2 gate) when operatingMode==onOnly. Performs the SAME
     * ordered graceful teardown as {@link #shutdown()} (close H2 / trips / RecordingsIndex,
     * stop servers, kill own watchdog, kill self) so nothing is corrupted — but plants the
     * PARKED-SHUTDOWN marker instead of the user `.disabled` sentinel. The marker keeps the
     * whole stack down for the parked window (watchdogs + app health-check honor it) yet is
     * NOT the user-stop signal and is cleared automatically on the ACC-on edge. The
     * AccSentryDaemon-launched reaper is the process-wide backstop; this is CameraDaemon
     * doing its OWN clean shutdown because it owns the durable H2 state a SIGKILL could
     * corrupt.
     */
    public static void parkTerminate() {
        final long generation;
        synchronized (parkTerminateLock) {
            if (!Boolean.TRUE.equals(latestAccIsOff)) {
                log("parkTerminate ignored — latest admitted ACC state is not OFF");
                return;
            }
            generation = accTransitionGeneration;
        }
        parkTerminate(generation);
    }

    private static boolean parkTerminate(long expectedGeneration) {
        log("onOnly parkTerminate — planting parked-shutdown marker + graceful shutdown");
        // Run any automation already triggered by this ACC-off edge (notably a "when power turns
        // off" rule) BEFORE killing the process. The queue's worker is a daemon thread, so without
        // this the trigger fires and the actions are lost to the exit. Twelve seconds covers one
        // older serialized bridge attempt plus the latest command's bounded launch, physical
        // readback and daemon fallback path, while still capping pauses/waits in unrelated action
        // chains. Only already-due items run (a delayed rule cannot be honoured by a process that
        // is about to exit).
        try {
            com.overdrive.app.automation.AutomationQueue.ShutdownDrainResult drain =
                com.overdrive.app.automation.AutomationQueue.drainDueNowResult(12_000L);
            if (drain.completed > 0) {
                log("Ran " + drain.completed + " due automation(s) before park shutdown");
            }
            if (!drain.mayCommitShutdown()) {
                log("parkTerminate deferred — automation drain still owns queued or running work");
                abortUncommittedAutomationDrain("automation drain not quiescent");
                markCurrentAccApplyRetry();
                requestAccTransitionReconciliation(false);
                return false;
            }
        } catch (Throwable t) {
            log("Automation shutdown drain failed: " + t.getMessage());
            abortUncommittedAutomationDrain("shutdown drain failure");
            markCurrentAccApplyRetry();
            requestAccTransitionReconciliation(false);
            return false;
        }

        final boolean supersededAfterDrain;
        synchronized (parkTerminateLock) {
            supersededAfterDrain = expectedGeneration != accTransitionGeneration
                || !Boolean.TRUE.equals(latestAccIsOff)
                || !running.get();
        }
        if (supersededAfterDrain) {
            log("parkTerminate canceled — ACC generation changed during shutdown drain");
            abortUncommittedAutomationDrain("ACC changed during shutdown drain");
            return false;
        }
        if (!com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
            log("parkTerminate canceled — operating mode changed to onAndOff during shutdown drain");
            abortUncommittedAutomationDrain("operating mode changed during shutdown drain");
            forceLatestAccStateReconciliation("operating mode changed during shutdown drain");
            return false;
        }

        java.util.concurrent.atomic.AtomicBoolean markerAttempted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean committed =
            com.overdrive.app.automation.AutomationQueue.commitShutdownIfQuiescent(() -> {
                markerAttempted.set(true);
                if (!commitParkedShutdownMarker(expectedGeneration)) {
                    return false;
                }
                // Keep AutomationQueue's commit lock until process termination. No producer can
                // enqueue a due action after the final empty check and lose it to this exit.
                shutdownInternal(false);
                return true;
            });
        if (committed) {
            return true;
        }

        abortUncommittedAutomationDrain(markerAttempted.get()
            ? "parked marker commit failure"
            : "automation queue changed before marker commit");
        markCurrentAccApplyRetry();
        requestAccTransitionReconciliation(false);
        return false;
    }

    /**
     * Freeze ACC admission across the marker's atomic publication. The marker rename is the
     * cross-process commit point; because onAccStateChanged also needs parkTerminateLock, no
     * OFF->ON->OFF generation can cross that point or inherit another generation's commit.
     */
    private static boolean commitParkedShutdownMarker(long expectedGeneration) {
        synchronized (parkMarkerIoLock) {
            synchronized (parkTerminateLock) {
                if (expectedGeneration != accTransitionGeneration
                        || !Boolean.TRUE.equals(latestAccIsOff)
                        || !running.get() || parkShutdownCommitted) {
                    return false;
                }
                if (!armTerminalShutdownDeadline()) {
                    log("parkTerminate deferred — terminal deadline could not be armed");
                    return false;
                }

                // Commit the exact OFF generation before the atomic rename can make the marker
                // visible to another process. ACC admission stays frozen by parkTerminateLock.
                parkShutdownCommitted = true;
                parkShutdownCommitGeneration = expectedGeneration;
                running.set(false);

                boolean markerWritten =
                    writeParkedShutdownMarker(expectedGeneration);
                if (!markerWritten) {
                    parkMarkerWriteFailed = true;
                    boolean currentMarkerVisible =
                        parkedShutdownMarkerBelongsToGeneration(
                            expectedGeneration);
                    boolean cleared = !currentMarkerVisible
                        || clearParkedShutdownMarker();
                    parkMarkerClearFailed = !cleared;
                    if (currentMarkerVisible && !cleared) {
                        // The current-generation marker crossed the atomic visibility point. Keep
                        // the already-published generation commit and guarantee process exit.
                        log("Parked marker rollback failed; retaining current OFF generation commit");
                        parkMarkerWriteFailed = false;
                        return true;
                    }

                    parkShutdownCommitted = false;
                    parkShutdownCommitGeneration = 0L;
                    running.set(true);
                    disarmTerminalShutdownDeadline();
                    log("parkTerminate deferred — parked marker was not durably written");
                    return false;
                }
                parkMarkerWriteFailed = false;
                parkMarkerClearFailed = false;
                return true;
            }
        }
    }

    private static void abortUncommittedAutomationDrain(String reason) {
        com.overdrive.app.automation.AutomationQueue.ShutdownDrainCancelResult result =
            com.overdrive.app.automation.AutomationQueue.abortShutdownDrain(
                2_000L, () -> forceLatestAccStateReconciliation(
                    "late uncommitted-drain quiescence after " + reason));
        if (result
                == com.overdrive.app.automation.AutomationQueue.ShutdownDrainCancelResult.TIMED_OUT) {
            log("Uncommitted automation drain is still quiescing after " + reason);
        }
    }

    private static boolean isParkShutdownCommitted() {
        synchronized (parkTerminateLock) {
            return parkShutdownCommitted;
        }
    }

    private static boolean isParkShutdownCommittedForGeneration(long generation) {
        synchronized (parkTerminateLock) {
            return parkShutdownCommitted
                && parkShutdownCommitGeneration == generation;
        }
    }

    private static boolean parkedShutdownMarkerExists() {
        try {
            return new java.io.File(
                com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).exists();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Reconcile marker state for an admitted ON transition. A committed shutdown remains
     * irreversible; clearing only allows the supervisor to recover the now-active vehicle.
     */
    private static boolean reconcileParkedShutdownMarkerOn(long generation) {
        boolean staleAfterClear = false;
        boolean restored = true;
        synchronized (parkMarkerIoLock) {
            if (!isAccTransitionCurrent(generation, false)) return false;
            if (!parkedShutdownMarkerExists()) {
                synchronized (parkTerminateLock) {
                    parkMarkerClearFailed = false;
                }
                return true;
            }
            boolean cleared = clearParkedShutdownMarker();
            synchronized (parkTerminateLock) {
                parkMarkerClearFailed = !cleared;
            }
            if (!cleared) {
                markCurrentAccApplyRetry();
                return false;
            }
            if (isAccTransitionCurrent(generation, false)) {
                return true;
            }

            staleAfterClear = true;
            // OFF won while delete() was in flight. Restore a committed marker before
            // releasing the I/O lock. An uncommitted newer OFF will write its own marker
            // after this transaction.
            boolean latestOff;
            boolean shutdownCommitted;
            long latestGeneration;
            long committedGeneration;
            synchronized (parkTerminateLock) {
                latestOff = Boolean.TRUE.equals(latestAccIsOff);
                shutdownCommitted = parkShutdownCommitted;
                latestGeneration = accTransitionGeneration;
                committedGeneration = parkShutdownCommitGeneration;
            }
            if (latestOff && shutdownCommitted
                    && latestGeneration == committedGeneration) {
                restored = writeParkedShutdownMarker(
                    committedGeneration);
                synchronized (parkTerminateLock) {
                    parkMarkerWriteFailed = !restored;
                }
            }
        }
        if (staleAfterClear) {
            forceLatestAccStateReconciliation(
                restored
                    ? "stale parked-marker clear"
                    : "failed parked-marker restoration");
        }
        return false;
    }

    private static boolean reconcileCommittedParkedShutdownOff(long generation) {
        synchronized (parkMarkerIoLock) {
            if (!isAccTransitionCurrent(generation, true)
                    || !isParkShutdownCommittedForGeneration(generation)) {
                return false;
            }
            if (parkedShutdownMarkerBelongsToGeneration(generation)) {
                synchronized (parkTerminateLock) {
                    parkMarkerWriteFailed = false;
                }
                return true;
            }
            boolean written = writeParkedShutdownMarker(generation);
            synchronized (parkTerminateLock) {
                parkMarkerWriteFailed = !written;
            }
            if (!written) {
                markCurrentAccApplyRetry();
                return false;
            }
            if (!isAccTransitionCurrent(generation, true)) {
                forceLatestAccStateReconciliation(
                    "stale committed parked-marker write");
                return false;
            }
            return true;
        }
    }

    private static boolean writeParkedShutdownMarker(long generation) {
        String path = com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH;
        java.io.File marker = new java.io.File(path);
        java.io.File parent = marker.getParentFile();
        java.io.File temporary = new java.io.File(
            parent, marker.getName() + ".tmp." + android.os.Process.myPid()
                + "." + Thread.currentThread().getId());
        try {
            if (parkedShutdownMarkerBelongsToGeneration(generation)) {
                try (java.io.RandomAccessFile existing =
                        new java.io.RandomAccessFile(marker, "rw")) {
                    existing.getFD().sync();
                }
                if (!forceDirectorySync(parent)) {
                    log("WARNING: Existing parked marker directory sync failed: " + path);
                    return false;
                }
                return true;
            }
            // Existing app/shell readers consume the entire file as a decimal epoch-millis
            // value. Keep generation ownership in process; putting keyed metadata in this file
            // makes the stale-marker fail-safe treat a valid marker as unparseable forever.
            long timestampMs = System.currentTimeMillis();
            byte[] contents = (Long.toString(timestampMs) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            try (java.io.FileOutputStream output =
                    new java.io.FileOutputStream(temporary, false)) {
                output.write(contents);
                output.flush();
                output.getFD().sync();
            }
            java.nio.file.Files.move(
                temporary.toPath(),
                marker.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (!forceDirectorySync(parent)) {
                java.nio.file.Files.deleteIfExists(marker.toPath());
                forceDirectorySync(parent);
                parkMarkerGeneration = 0L;
                parkMarkerTimestampMs = 0L;
                log("WARNING: Parked marker rename was not durably committed: " + path);
                return false;
            }
            try {
                marker.setReadable(true, false);
                marker.setWritable(true, false);
            } catch (Exception ignored) {}
            if (!marker.exists() || marker.length() == 0L) {
                log("WARNING: Parked-shutdown marker write could not be verified: " + path);
                return false;
            }
            parkMarkerGeneration = generation;
            parkMarkerTimestampMs = timestampMs;
            log("Parked-shutdown marker written: " + path);
            return true;
        } catch (Throwable e) {
            try { java.nio.file.Files.deleteIfExists(temporary.toPath()); }
            catch (Throwable ignored) {}
            log("WARNING: Failed to write parked-shutdown marker: " + e.getMessage());
            return false;
        }
    }

    private static boolean parkedShutdownMarkerBelongsToGeneration(
            long generation) {
        if (generation != parkMarkerGeneration || parkMarkerTimestampMs <= 0L) {
            return false;
        }
        String path =
            com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(path))) {
            String value = reader.readLine();
            return value != null
                && Long.parseLong(value.trim()) == parkMarkerTimestampMs
                && reader.readLine() == null;
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean forceDirectorySync(java.io.File directory) {
        if (directory == null) return false;
        try (java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(
                    directory.toPath(), java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
            return true;
        } catch (Throwable t) {
            log("WARNING: Directory fsync failed for " + directory + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean clearParkedShutdownMarker() {
        String path = com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH;
        try {
            java.io.File marker = new java.io.File(path);
            java.io.File parent = marker.getParentFile();
            if (!marker.exists()) {
                boolean synced = forceDirectorySync(parent);
                if (synced) {
                    parkMarkerGeneration = 0L;
                    parkMarkerTimestampMs = 0L;
                }
                return synced;
            }
            if (!java.nio.file.Files.deleteIfExists(marker.toPath()) || marker.exists()) {
                log("WARNING: ACC ON could not clear parked-shutdown marker: " + path);
                return false;
            }
            boolean synced = forceDirectorySync(parent);
            if (synced) {
                parkMarkerGeneration = 0L;
                parkMarkerTimestampMs = 0L;
            }
            return synced;
        } catch (Throwable t) {
            log("WARNING: ACC ON parked-marker clear failed: " + t.getMessage());
            return false;
        }
    }

    private static void shutdownInternal(boolean writeDisableSentinel) {
        synchronized (TRIP_ANALYTICS_LIFECYCLE_LOCK) {
            if (!shutdownStarted.compareAndSet(false, true)) {
                return;
            }
            TRIP_ANALYTICS_SHUTDOWN_REQUESTED.set(true);
        }
        Thread accOwner;
        Thread surveillanceOwner;
        synchronized (parkTerminateLock) {
            running.set(false);
            accOwner = activeAccTransitionThread;
            surveillanceOwner = activeSurveillanceEnableThread;
            activeAccTransitionGeneration = 0L;
            activeAccTransitionLease = 0L;
            activeAccTransitionDeadlineNanos = 0L;
            activeAccTransitionThread = null;
            activeSurveillanceEnableGeneration = 0L;
            activeSurveillanceEnableRevision = 0L;
            activeSurveillanceEnableLease = 0L;
            activeSurveillanceEnableDeadlineNanos = 0L;
            activeSurveillanceEnableThread = null;
            invalidateAccCompletionLocked();
        }
        if (accOwner != null && accOwner != Thread.currentThread()) {
            accOwner.interrupt();
        }
        if (surveillanceOwner != null
                && surveillanceOwner != Thread.currentThread()) {
            surveillanceOwner.interrupt();
        }
        // Arm the independent kill path before any logging, marker or service I/O can block.
        if (!armTerminalShutdownDeadline()) {
            shutdownTripAnalyticsBeforeBlockingCleanup();
            forceTerminateProcess("terminal shutdown guard unavailable");
            return;
        }
        try {
            log("Shutdown requested (writeDisableSentinel="
                + writeDisableSentinel + ") — cleaning up...");
        } catch (Throwable ignored) {}

        // Publish restart suppression before any external cleanup can block.
        if (writeDisableSentinel) {
            writeDisableSentinel();
        }

        // Trip state is the first durable subsystem boundary. Camera, HAL and
        // server teardown below can block until the terminal guard fires; by
        // closing trips here, an active drive is checkpointed while its
        // telemetry monitors and database are still available.
        shutdownTripAnalyticsBeforeBlockingCleanup();

        try {
        // Stop the wrapper before service cleanup can block. The already-durable disable/park
        // marker prevents restart if this best-effort process cleanup itself stalls.
        try { killWatchdogWrapper(); }
        catch (Throwable t) { log("Watchdog wrapper shutdown error: " + t.getMessage()); }
        stopScheduleChecker();
        cleanupDoorLockGate();

        // Stop AVC keep-alive immediately (force, daemon teardown)
        stopAvcKeepAliveForShutdown();

        // Stop periodic memory monitor
        if (memoryLogScheduler != null) {
            try { memoryLogScheduler.shutdownNow(); } catch (Exception ignored) {}
            memoryLogScheduler = null;
        }
        // Stop the geo backfill scheduler too (symmetric) — shutdownNow interrupts
        // an in-flight resolveBlocking so no wasted blocking network I/O / sidecar
        // write runs during teardown.
        if (geoBackfillScheduler != null) {
            try { geoBackfillScheduler.shutdownNow(); } catch (Exception ignored) {}
            geoBackfillScheduler = null;
        }

        // Cancel PermissionGranter to stop orphaned pm grant processes
        PermissionGranter.cancel();

        // killProcess/halt do not run Java shutdown hooks. Perform the safety-critical hook-only
        // cleanup here while the terminal deadline independently guarantees process exit.
        try {
            com.overdrive.app.surveillance.ScreenDeterrent.getInstance().cancel();
            java.util.Map<String, Object> reset = new java.util.HashMap<>();
            reset.put("screenDeterrentActiveUntilMs", 0L);
            reset.put("screenDeterrentForceStop", false);
            com.overdrive.app.config.UnifiedConfigManager.updateValues(
                "surveillance", reset);
        } catch (Throwable t) {
            log("Screen deterrent shutdown error: " + t.getMessage());
        }
        try {
            com.overdrive.app.receiver.CastPackageWatcher.unregister(getAppContext());
            com.overdrive.app.surveillance.ClusterViewMirrorService
                .forceDetachIfActive("daemon-shutdown");
            com.overdrive.app.surveillance.ClusterMirrorController.shutdownIfActive();
            com.overdrive.app.surveillance.ClusterProjectionController.shutdownIfActive();
        } catch (Throwable t) {
            log("Cluster shutdown error: " + t.getMessage());
        }
        try {
            com.overdrive.app.camera.OemDashcamPipeline oem = getOemDashcamPipeline();
            if (oem != null && oem.isRunning()) {
                try { oem.stopRecording(); } catch (Throwable ignored) {}
                oem.stop();
                setOemDashcamPipeline(null);
            }
        } catch (Throwable t) {
            log("OEM dashcam shutdown error: " + t.getMessage());
        }

        // Stop RoadSense + RecordingModeManager (shared with the JVM shutdown
        // hook). Early so their tickers can't fire against tearing-down state.
        closeGenAiRuntime();
        detachRoadSenseAndRecordingMode();

        // Stop cameras and GPU pipeline
        try { stopAllCameras(); }
        catch (Throwable t) { log("Camera shutdown error: " + t.getMessage()); }
        if (gpuPipeline != null) {
            try { gpuPipeline.stop(); } catch (Exception e) { log("GPU pipeline stop error: " + e.getMessage()); }
        }
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline
                .shutdownStreamEncoderReleaseExec(4_000L);
        } catch (Throwable t) {
            log("Stream encoder release shutdown error: " + t.getMessage());
        }

        // Stop all monitors
        try { com.overdrive.app.monitor.VehicleDataMonitor.getInstance().stop(); } catch (Exception ignored) {}
        try { com.overdrive.app.monitor.GpsMonitor.getInstance().stop(); } catch (Exception ignored) {}
        try { com.overdrive.app.monitor.GearMonitor.getInstance().stop(); } catch (Exception ignored) {}
        try { com.overdrive.app.monitor.PerformanceMonitor.getInstance().stop(); } catch (Exception ignored) {}
        // Stop the charging fast-sampler BEFORE closing the H2 DB it writes to.
        try { if (chargingSessionManager != null) chargingSessionManager.shutdown(); } catch (Exception ignored) {}
        try { com.overdrive.app.monitor.SocHistoryDatabase.getInstance().stop(); } catch (Exception ignored) {}
        try { com.overdrive.app.monitor.DataUsageMonitor.getInstance().shutdown(); } catch (Exception ignored) {}
        try { com.overdrive.app.notifications.NotificationStore.getInstance().stop(); } catch (Exception ignored) {}

        // Stop services. Both the trip analytics + recordings index inits
        // run on parallel threads (see main()); join with a short timeout
        // before tearing down so we don't close a half-opened H2
        // connection. 5 s comfortably exceeds measured init time (~3-5 s
        // for trips, ~1 s for index open).
        try {
            if (tripAnalyticsInitFuture != null) {
                tripAnalyticsInitFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log("Trip analytics init join error: " + e.getMessage());
        }
        try {
            if (dataLayerInitFuture != null) {
                dataLayerInitFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log("Data layer init join error: " + e.getMessage());
        }
        try {
            if (tripAnalyticsManager != null) tripAnalyticsManager.shutdown();
        } catch (Throwable t) {
            log("Trip analytics shutdown error: " + t.getMessage());
        }
        // Tear down RecordingsIndex AFTER trips so any in-flight upserts
        // from the recorder have already drained. stop() the watcher
        // first to silence inotify callbacks before close() yanks the
        // H2 connection (otherwise a late event would log a noisy
        // "upsert failed: connection closed" warning).
        try { RecordingsIndexFileWatcher.getInstance().stop(); }
        catch (Exception e) { log("RecordingsIndexFileWatcher stop error: " + e.getMessage()); }
        try { com.overdrive.app.server.RecordingsIndex.getInstance().close(); }
        catch (Exception e) { log("RecordingsIndex close error: " + e.getMessage()); }
        try { if (abrpTelemetryService != null) abrpTelemetryService.stop(); }
        catch (Throwable t) { log("ABRP shutdown error: " + t.getMessage()); }
        try { if (mqttConnectionManager != null) mqttConnectionManager.stopAll(); }
        catch (Throwable t) { log("MQTT shutdown error: " + t.getMessage()); }
        try { if (tcpServer != null) tcpServer.stop(); }
        catch (Throwable t) { log("TCP shutdown error: " + t.getMessage()); }
        try { if (httpServer != null) httpServer.stop(); }
        catch (Throwable t) { log("HTTP shutdown error: " + t.getMessage()); }
        try { if (ipcServer != null) ipcServer.stop(); }
        catch (Throwable t) { log("IPC shutdown error: " + t.getMessage()); }
        try { if (aacIngestServer != null) aacIngestServer.stop(); }
        catch (Throwable t) { log("AAC shutdown error: " + t.getMessage()); }

        // Shutdown StorageManager (schedulers, executors)
        try { com.overdrive.app.storage.StorageManager.getInstance().shutdown(); } catch (Exception ignored) {}

        // Release singleton lock
        releaseSingletonLock();

        log("Daemon shutdown cleanup complete");
        } catch (Throwable t) {
            log("Daemon shutdown cleanup aborted: " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
        } finally {
            forceTerminateProcess("shutdown cleanup complete");
        }
    }

    private static boolean armTerminalShutdownDeadline() {
        synchronized (TERMINAL_SHUTDOWN_GUARD_LOCK) {
            if (terminalShutdownHandlerCallback != null) {
                return true;
            }
            if (terminalShutdownGuard != null
                    && terminalShutdownGuard.isAlive()) {
                // An interrupted guard belongs to a rolled-back marker attempt. Do not claim that
                // its canceled deadline protects a new fsync, and do not allocate over it.
                return !terminalShutdownGuard.isInterrupted();
            }

            boolean armed = false;
            try {
                Thread guard = new Thread(() -> {
                    try {
                        Thread.sleep(TERMINAL_SHUTDOWN_BUDGET_MS);
                        forceTerminateProcess("shutdown deadline exceeded");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        synchronized (TERMINAL_SHUTDOWN_GUARD_LOCK) {
                            if (terminalShutdownGuard == Thread.currentThread()) {
                                terminalShutdownGuard = null;
                            }
                        }
                    }
                }, "TerminalShutdownGuard");
                guard.setDaemon(false);
                terminalShutdownGuard = guard;
                guard.start();
                armed = true;
            } catch (Throwable t) {
                terminalShutdownGuard = null;
                try { log("Could not start terminal shutdown guard: " + t.getMessage()); }
                catch (Throwable ignored) {}
            }

            Handler handler = mainHandler;
            if (handler != null) {
                Runnable callback =
                    () -> forceTerminateProcess("main-loop shutdown deadline exceeded");
                try {
                    if (handler.postDelayed(
                            callback, TERMINAL_SHUTDOWN_BUDGET_MS)) {
                        terminalShutdownHandlerCallback = callback;
                        armed = true;
                    }
                } catch (Throwable ignored) {}
            }
            return armed;
        }
    }

    /**
     * Shared RoadSense + RecordingModeManager teardown, used by BOTH the normal
     * shutdown path (shutdownInternal) and the JVM shutdown hook. Must run
     * before GPU, monitor and database teardown.
     *
     * <p>RoadSense: detach() — not merely stop() — because it also removes the
     * live-toggle config listener (the inverse of attach()); it releases the
     * ticker, sync worker, IMU sidecar, warning audio and H2 stores. Safe even
     * if the controller was never started (disabled). Early so its warning-tick
     * can't fire against tearing-down state.
     *
     * <p>RecordingModeManager: stopped BEFORE the pipeline so its periodic
     * resync ticker can't fire one more activateMode() call against a
     * tearing-down pipeline. Idempotent w.r.t. modeActive bookkeeping; safe
     * even if the manager's pipeline state is already half-torn.
     */
    private static void detachRoadSenseAndRecordingMode() {
        if (roadSense != null) {
            try { roadSense.detach(); }
            catch (Throwable t) { log("RoadSense detach error: " + t.getMessage()); }
        }
        recordingModeManagerPipelineOwner = null;
        if (recordingModeManager != null) {
            try { recordingModeManager.shutdown(); }
            catch (Throwable t) { log("RecordingModeManager shutdown error: " + t.getMessage()); }
        }
    }

    /** Idempotent inverse of the startup attach; cancels provider I/O first. */
    private static void closeGenAiRuntime() {
        com.overdrive.app.genai.GenAiRuntime runtime = genAiRuntime;
        genAiRuntime = null;
        if (runtime != null) {
            try { runtime.close(); }
            catch (Throwable t) { log("GenAI runtime shutdown error: " + t.getMessage()); }
        }
    }

    private static void shutdownTripAnalyticsBeforeBlockingCleanup() {
        markTripAnalyticsShutdownRequested();
        try {
            java.util.concurrent.CompletableFuture<Void> initFuture =
                    tripAnalyticsInitFuture;
            if (initFuture != null) {
                initFuture.get(
                        5, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception initFailure) {
            log("Early trip analytics init join error: "
                    + initFailure.getMessage());
        }
        try {
            com.overdrive.app.trips.TripAnalyticsManager manager;
            synchronized (TRIP_ANALYTICS_LIFECYCLE_LOCK) {
                manager = tripAnalyticsManager;
            }
            if (shouldFinalizeTripsOnShutdown() && manager != null
                    && manager.isInitialized()) {
                manager.shutdown();
            } else if (manager != null && manager.isInitialized()) {
                // A camera/EGL restart is not a trip end. Checkpoint the open
                // trip instead so it is not split into a separate card.
                manager.checkpointActiveTrip();
            }
        } catch (Throwable tripFailure) {
            log("Early trip analytics shutdown error: "
                    + tripFailure.getMessage());
        }
    }

    private static void disarmTerminalShutdownDeadline() {
        final Thread guard;
        synchronized (TERMINAL_SHUTDOWN_GUARD_LOCK) {
            if (shutdownStarted.get()) return;
            guard = terminalShutdownGuard;
            Runnable callback = terminalShutdownHandlerCallback;
            terminalShutdownHandlerCallback = null;
            Handler handler = mainHandler;
            if (callback != null && handler != null) {
                try { handler.removeCallbacks(callback); }
                catch (Throwable ignored) {}
            }
        }
        if (guard != null) {
            guard.interrupt();
            try {
                guard.join(MANAGED_THREAD_JOIN_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!guard.isAlive()) {
                synchronized (TERMINAL_SHUTDOWN_GUARD_LOCK) {
                    if (terminalShutdownGuard == guard) {
                        terminalShutdownGuard = null;
                    }
                }
            }
        }
    }

    private static void forceTerminateProcess(String reason) {
        if (!forceTerminationStarted.compareAndSet(false, true)) return;
        try {
            try { android.os.Process.killProcess(android.os.Process.myPid()); }
            catch (Throwable ignored) {}
        } finally {
            Runtime.getRuntime().halt(0);
        }
    }

    /**
     * Write the disable sentinel file that tells the shell watchdog wrapper
     * to stop restarting the daemon.
     */
    private static void writeDisableSentinel() {
        try {
            java.io.FileWriter fw = new java.io.FileWriter(DISABLE_SENTINEL);
            fw.write("disabled at " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date()) + "\n");
            fw.write("pid=" + android.os.Process.myPid() + "\n");
            fw.close();
            log("Disable sentinel written: " + DISABLE_SENTINEL);
        } catch (Exception e) {
            log("WARNING: Failed to write disable sentinel: " + e.getMessage());
        }
    }

    /**
     * Kill the shell watchdog wrapper process (start_cam_daemon.sh).
     * Uses the PID file if available, falls back to pkill.
     */
    private static void killWatchdogWrapper() {
        try {
            // Try PID file first
            java.io.File pidFile = new java.io.File(ScratchPaths.path("cam_watchdog.pid"));
            if (pidFile.exists()) {
                String pid = new java.util.Scanner(pidFile).useDelimiter("\\A").next().trim();
                Runtime.getRuntime().exec(new String[]{"kill", "-9", pid});
                log("Killed watchdog wrapper via PID file (pid=" + pid + ")");
                pidFile.delete();
            }
            // Also pkill as fallback
            Runtime.getRuntime().exec(new String[]{"pkill", "-9", "-f", "start_cam_daemon"});
            // Delete the script so it can't be accidentally re-run
            new java.io.File(ScratchPaths.path("start_cam_daemon.sh")).delete();
        } catch (Exception e) {
            log("Watchdog wrapper kill error (non-fatal): " + e.getMessage());
        }
    }

    /**
     * Check if the daemon has been intentionally disabled.
     * Called by the shell watchdog wrapper before restarting.
     * Also callable from Java to check state.
     */
    public static boolean isDisabledBySentinel() {
        return new java.io.File(DISABLE_SENTINEL).exists();
    }

    /**
     * Acquire a file lock to ensure only one daemon instance runs at a time.
     * Uses Java NIO FileLock which is process-safe.
     */
    private static boolean acquireSingletonLock() {
        try {
            File lockFileObj = new File(lockFilePath());
            lockFile = new java.io.RandomAccessFile(lockFileObj, "rw");
            java.nio.channels.FileChannel channel = lockFile.getChannel();

            // Try to acquire exclusive lock (non-blocking)
            fileLock = channel.tryLock();

            if (fileLock == null) {
                // Another process holds the lock — check if it's actually alive
                // AND that it's actually a CameraDaemon. We treat the following
                // as stale-lock cases, because each one means no live daemon
                // owns the lock:
                //   - empty lock file
                //   - corrupt/non-numeric PID
                //   - holder PID is our own PID (previous crash)
                //   - /proc/<pid> doesn't exist (dead PID)
                //   - /proc/<pid>/cmdline doesn't look like a CameraDaemon
                //     (PID was recycled to an unrelated process — the kernel
                //     flock should have been released, but if we got here the
                //     file content still points at a stale PID)
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
                            // PID is alive — verify it's actually a CameraDaemon
                            // before declaring a real conflict. Without this an
                            // unrelated process that inherited the prior daemon's
                            // recycled PID would lock us out of starting forever.
                            //
                            // Three outcomes from readProcCmdline:
                            //   MATCH    → real conflict, refuse to start
                            //   NO_MATCH → PID is alive but not a daemon → stale
                            //   UNKNOWN  → cmdline unreadable (Android 10+
                            //              hidepid=2 blocks cross-UID reads).
                            //              We MUST NOT steal the lock in this
                            //              case — a legitimately-running daemon
                            //              under a different UID would be booted
                            //              out. Refuse to start; the watchdog's
                            //              backoff handles the retry.
                            CmdlineMatch match = classifyCmdline(pid);
                            if (match == CmdlineMatch.MATCH) {
                                log("Singleton: live daemon PID " + pid + " holds the lock"
                                    + " (cmdline=" + readProcCmdline(pid) + ")");
                                try { lockFile.close(); } catch (Exception ignored) {}
                                return false;
                            }
                            if (match == CmdlineMatch.UNKNOWN) {
                                log("Singleton: PID " + pid + " holds the lock but its "
                                    + "/proc/<pid>/cmdline is unreadable (different UID? "
                                    + "hidepid?) — assuming live daemon, refusing to start");
                                try { lockFile.close(); } catch (Exception ignored) {}
                                return false;
                            }
                            // NO_MATCH
                            stale = true;
                            reason = "PID " + pid + " is alive but not a CameraDaemon"
                                + " (cmdline=" + readProcCmdline(pid) + ")";
                        }
                    }
                } catch (NumberFormatException nfe) {
                    stale = true;
                    reason = "corrupt PID in lock file";
                } catch (Exception e) {
                    log("Singleton: lock-file inspection failed: " + e.getMessage());
                    try { lockFile.close(); } catch (Exception ignored) {}
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

            // Register shutdown hook to release lock and clean up ALL resources on process termination.
            // CRITICAL: System.exit(0) from the GL watchdog skips normal cleanup.
            // Without this, the MediaCodec encoder, EGL context, camera HAL connection,
            // and TFLite GPU delegate leak across restarts. After 3-4 rapid restarts,
            // the Adreno 610 runs out of GPU contexts and the hardware encoder exhausts
            // its codec instance limit, causing system-level freezes.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                markTripAnalyticsShutdownRequested();
                log("Shutdown hook: cleaning up all resources...");

                // -1. URGENT: quiesce active trip storage before anything that
                //     might block. RESTART-AWARE: a trip-safe process restart
                //     (System.exit from requestProcessRestartPreservingTrip) is
                //     NOT a trip end — manager.shutdown() would finalize the
                //     active trip and, on a short leg, route it to discardTrip()
                //     which DELETES the telemetry file. The helper checkpoints
                //     the open journal instead when processRestartIntent is set.
                try {
                    shutdownTripAnalyticsBeforeBlockingCleanup();
                    log("Shutdown hook: trip analytics quiesced (early, "
                            + (shouldFinalizeTripsOnShutdown()
                                    ? "finalized" : "checkpointed for restart") + ")");
                } catch (Exception e) {
                    log("Shutdown hook: early trip teardown error: " + e.getMessage());
                }

                // 0. Tear down any in-progress ScreenDeterrent FIRST. The
                //    deterrent owns SurfaceControl + UCM gate flags; if we
                //    skip this, AccSentryDaemon (separate process) reads
                //    a stuck screenDeterrentActiveUntilMs in the future and
                //    permanently skips its setBacklightState(false) — the
                //    panel stays lit until the next ACC transition.
                //    cancel() is non-blocking; the executor's finally block
                //    clears UCM and turns the backlight off.
                try {
                    com.overdrive.app.surveillance.ScreenDeterrent.getInstance().cancel();
                    // Defensive: clear cross-process flags directly in case
                    // the executor doesn't get a chance to finish (SIGKILL
                    // or VM dying mid-cleanup).
                    java.util.Map<String, Object> reset = new java.util.HashMap<>();
                    reset.put("screenDeterrentActiveUntilMs", 0L);
                    reset.put("screenDeterrentForceStop", false);
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", reset);
                    log("Shutdown hook: screen deterrent flags cleared");
                } catch (Exception e) {
                    log("Shutdown hook: screen deterrent cleanup error: " + e.getMessage());
                }

                // 0.5 SAFETY: if a driver-cluster blind-spot projection is open, the
                //     native gauges are currently REPLACED. Restore them FIRST and
                //     SYNCHRONOUSLY (shutdown() fires 18→0 and blocks on a latch so
                //     the restore lands before the VM dies). shutdown() itself clears
                //     the recovery gate flags ONLY after the full 18→0 issued — so we
                //     must NOT clear them here. If shutdown()'s close did not complete
                //     (wedged thread / latch timeout), the flags stay SET on purpose so
                //     the next respawn's clearStaleGateAtBoot() re-fires 18→0. No-op
                //     when on head-unit / already closed (instance never constructed).
                // 0.5a Tear down the head-unit cluster MIRROR FIRST — BEFORE the OEM
                //     projection close below. Its own SurfaceFlinger virtual display reads
                //     the fission SOURCE layerStack; destroying that source (projection
                //     close) while our VD is still bound faults SurfaceFlinger natively.
                //     shutdownIfActive is SYNCHRONOUS (unbind+destroy the VD, bounded await)
                //     so it completes before the projection close. No-op if never started.
                try {
                    com.overdrive.app.receiver.CastPackageWatcher.unregister(getAppContext());
                } catch (Exception e) {
                    log("Shutdown hook: CastPackageWatcher unregister error: " + e.getMessage());
                }
                try {
                    com.overdrive.app.surveillance.ClusterViewMirrorService.forceDetachIfActive("daemon-shutdown");
                    com.overdrive.app.surveillance.ClusterMirrorController.shutdownIfActive();
                    log("Shutdown hook: cluster mirror torn down");
                } catch (Exception e) {
                    log("Shutdown hook: cluster mirror cleanup error: " + e.getMessage());
                }

                // 0.5b THEN close the OEM cluster projection + restore gauges.
                try {
                    com.overdrive.app.surveillance.ClusterProjectionController.shutdownIfActive();
                    log("Shutdown hook: cluster projection restore issued");
                } catch (Exception e) {
                    log("Shutdown hook: cluster projection cleanup error: " + e.getMessage());
                }

                // 0.7 Stop RoadSense + RecordingModeManager (shared helper, same
                //     ordering as shutdownInternal: AFTER the screen-deterrent
                //     flag reset and cluster gauge restoration — those are
                //     safety-critical and must land even if this teardown wedges
                //     and the terminal guard halts us — but BEFORE GPU, monitor
                //     and database teardown. detach() removes the live config
                //     listener and releases the ticker, sync worker, IMU
                //     sidecar, warning audio and H2 stores; without it a
                //     watchdog System.exit leaks the sidecar and lets the
                //     warning-tick fire against tearing-down state.
                try {
                    closeGenAiRuntime();
                    detachRoadSenseAndRecordingMode();
                    log("Shutdown hook: GenAI/RoadSense/RMM teardown complete");
                } catch (Exception e) {
                    log("Shutdown hook: GenAI/RoadSense/RMM teardown error: " + e.getMessage());
                }

                // 1. Stop PermissionGranter — prevent orphaned pm grant processes
                //    from continuing to hammer PMS after we exit
                try {
                    PermissionGranter.cancel();
                } catch (Exception e) {
                    log("Shutdown hook: PermissionGranter cancel error: " + e.getMessage());
                }

                // 1.5. Flush the geocode cache. Puts are coalesced (30 s
                //      window) so a graceful shutdown that occurs inside
                //      the window would otherwise drop the latest reverse-
                //      geocode hits. Inline flush is bounded (≤ 4 MB JSON
                //      write) and finishes well within the shutdown budget.
                try {
                    com.overdrive.app.geo.GeoCache.getInstance().flushNow();
                } catch (Exception e) {
                    log("Shutdown hook: GeoCache flush error: " + e.getMessage());
                }

                // 1.6 Stop the OEM Dashcam pipeline outright. Pano's stop()
                //     also cascades to OEM (when pano is running), but if a
                //     user runs OEM standalone (recordingMode=continuous,
                //     no pano dashcam) gpuPipeline.stop() early-returns at
                //     !running and the cascade never fires — orphaning the
                //     OEM MediaCodec, drainer, and AVMCamera handle until
                //     daemon respawn. Tearing down here is unconditional.
                try {
                    com.overdrive.app.camera.OemDashcamPipeline oem =
                        getOemDashcamPipeline();
                    if (oem != null && oem.isRunning()) {
                        try { oem.stopRecording(); } catch (Throwable ignored) {}
                        oem.stop();
                        setOemDashcamPipeline(null);
                        log("Shutdown hook: OEM dashcam pipeline stopped");
                    }
                } catch (Exception e) {
                    log("Shutdown hook: OEM cleanup error: " + e.getMessage());
                }

                // 2. Stop the GPU pipeline (releases MediaCodec encoder slot, camera HAL, EGL).
                //    The encoder.release() and closeCamera() are synchronous.
                //    releaseGl() is posted to the GL thread which may be blocked — that's
                //    acceptable because EGL contexts are destroyed when the process exits.
                try {
                    if (gpuPipeline != null) {
                        gpuPipeline.stop();
                        log("Shutdown hook: GPU pipeline stopped");
                    }
                } catch (Exception e) {
                    log("Shutdown hook: GPU pipeline cleanup error: " + e.getMessage());
                }

                // 2.5 Drain the streaming-encoder release executor.
                //     gpuPipeline.stop()'s disableStreaming hands
                //     encoder.release() to STREAM_ENCODER_RELEASE_EXEC and
                //     returns immediately. Without an explicit drain, JVM
                //     exit kills the daemon thread mid-release and leaks
                //     the MediaCodec until the next process spawn.
                try {
                    boolean drained = com.overdrive.app.surveillance.GpuSurveillancePipeline
                        .shutdownStreamEncoderReleaseExec(4000);
                    if (drained) {
                        log("Shutdown hook: stream encoder release executor drained");
                    } else {
                        log("Shutdown hook: stream encoder release exec did NOT drain in 4s — "
                            + "shutdownNow used; queued releases dropped (MediaCodec may leak "
                            + "until next daemon spawn)");
                    }
                } catch (Exception e) {
                    log("Shutdown hook: stream encoder exec drain error: " + e.getMessage());
                }

                // 3. Stop all monitors (VehicleDataMonitor, GpsMonitor, GearMonitor,
                //    PerformanceMonitor) — these hold BYD device listeners and schedulers
                try {
                    com.overdrive.app.monitor.VehicleDataMonitor.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }
                try {
                    com.overdrive.app.monitor.GpsMonitor.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }
                try {
                    com.overdrive.app.monitor.GearMonitor.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }
                try {
                    com.overdrive.app.monitor.PerformanceMonitor.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }

                // 4. Close SOC History Database (H2 JDBC connection + scheduler).
                // Stop the geo backfill scheduler so an in-flight blocking
                // resolveBlocking / sidecar write doesn't run during teardown.
                try {
                    if (geoBackfillScheduler != null) {
                        geoBackfillScheduler.shutdownNow();
                        geoBackfillScheduler = null;
                    }
                } catch (Exception ignored) { /* best-effort */ }
                // Stop the charging fast-sampler first so it can't write mid-close.
                try {
                    if (chargingSessionManager != null) chargingSessionManager.shutdown();
                } catch (Exception e) { /* may not be initialized */ }
                try {
                    com.overdrive.app.monitor.SocHistoryDatabase.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }
                try {
                    com.overdrive.app.notifications.NotificationStore.getInstance().stop();
                } catch (Exception e) { /* may not be initialized */ }

                // 5. Stop services (MQTT, ABRP, Trip Analytics).
                // Trip Analytics + RecordingsIndex were inited on parallel
                // threads — join briefly so we don't tear down a half-opened
                // H2 connection. 5 s exceeds measured init time.
                try {
                    if (tripAnalyticsInitFuture != null) {
                        tripAnalyticsInitFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Exception e) { /* ignore */ }
                try {
                    if (dataLayerInitFuture != null) {
                        dataLayerInitFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    }
                } catch (Exception e) { /* ignore */ }
                try {
                    if (mqttConnectionManager != null) mqttConnectionManager.stopAll();
                } catch (Exception e) { /* ignore */ }
                try {
                    if (abrpTelemetryService != null) abrpTelemetryService.stop();
                } catch (Exception e) { /* ignore */ }
                // RESTART-AWARE: same rule as the early quiesce above — never
                // finalize the active trip when a trip-safe restart is in
                // progress (the coordinator already checkpointed it). A no-op
                // when the early quiesce already shut the manager down.
                try {
                    if (shouldFinalizeTripsOnShutdown()
                            && tripAnalyticsManager != null) {
                        tripAnalyticsManager.shutdown();
                    }
                } catch (Exception e) { /* ignore */ }
                // RecordingsIndex teardown — same ordering as shutdown():
                // unregister observers first so late inotify events don't
                // hit a closed JDBC connection.
                try { RecordingsIndexFileWatcher.getInstance().stop(); }
                catch (Exception e) { /* ignore */ }
                try { com.overdrive.app.server.RecordingsIndex.getInstance().close(); }
                catch (Exception e) { /* ignore */ }

                // 6. Stop servers (TCP, HTTP, IPC)
                try {
                    if (tcpServer != null) tcpServer.stop();
                } catch (Exception e) { /* ignore */ }
                try {
                    if (httpServer != null) httpServer.stop();
                } catch (Exception e) { /* ignore */ }
                try {
                    if (ipcServer != null) ipcServer.stop();
                } catch (Exception e) { /* ignore */ }
                try {
                    if (aacIngestServer != null) aacIngestServer.stop();
                } catch (Exception e) { /* ignore */ }

                // 7. Shutdown StorageManager (schedulers, executors, SD card watchdog)
                try {
                    com.overdrive.app.storage.StorageManager.getInstance().shutdown();
                } catch (Exception e) { /* ignore */ }

                // 8. Release singleton lock (must be last)
                releaseSingletonLock();
                log("Shutdown hook: cleanup complete");
            }, "DaemonShutdown"));

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

    /** Result of inspecting /proc/<pid>/cmdline for singleton-lock validation. */
    private enum CmdlineMatch {
        /** cmdline matches a CameraDaemon process — real conflict. */
        MATCH,
        /** cmdline is readable AND clearly NOT us — stale lock, recycled PID. */
        NO_MATCH,
        /** cmdline is unreadable (EACCES, hidepid=2, race against PID exit).
         *  Caller must NOT steal the lock — a legitimately-running daemon
         *  under a different UID could be booted out. */
        UNKNOWN
    }

    /**
     * Classify a PID's cmdline. Distinguishes "definitely not us" from
     * "we can't tell" — the latter happens on Android 10+ when the holder
     * runs under a different UID and procfs is mounted with hidepid=2.
     */
    private static CmdlineMatch classifyCmdline(int pid) {
        java.io.File f = new java.io.File("/proc/" + pid + "/cmdline");
        if (!f.exists()) return CmdlineMatch.NO_MATCH; // PID gone in our window
        if (!f.canRead()) return CmdlineMatch.UNKNOWN; // EACCES / hidepid
        String cmdline = readProcCmdline(pid);
        if (cmdline.isEmpty()) {
            // canRead() said yes but read produced nothing — could be a
            // kernel thread (whose /proc/.../cmdline is empty by design)
            // or a transient race. Either way it's not our daemon.
            // Treat as NO_MATCH so the next-step retry handles it.
            return CmdlineMatch.NO_MATCH;
        }
        return isCameraDaemonCmdline(cmdline) ? CmdlineMatch.MATCH : CmdlineMatch.NO_MATCH;
    }

    /**
     * Read /proc/<pid>/cmdline and return it with NUL bytes turned into
     * spaces. Returns "" if the file is unreadable (race against PID exit,
     * permission denied, etc.). NOT a sufficient check on its own — callers
     * doing security-critical decisions must use {@link #classifyCmdline}.
     *
     * /proc/<pid>/cmdline reports stat()-size=0 on most kernels even when
     * it has content, so Files.readAllBytes (size-hinted) can short-read.
     * Stream until EOF instead. Capped at 4096 because cmdlines longer
     * than that are pathological and we only need a substring match.
     */
    private static String readProcCmdline(int pid) {
        java.io.File f = new java.io.File("/proc/" + pid + "/cmdline");
        if (!f.exists()) return "";
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while (total < buf.length && (n = fis.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            if (total == 0) return "";
            // /proc/.../cmdline is NUL-separated and trailing-NUL-terminated.
            StringBuilder sb = new StringBuilder(total);
            for (int i = 0; i < total; i++) {
                byte b = buf[i];
                sb.append(b == 0 ? ' ' : (char) (b & 0xff));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Tighter cmdline match: the only legitimate ways our daemon shows up in
     * /proc/<pid>/cmdline are:
     *   - argv[0] (after kernel applies nice-name): "byd_cam_daemon"
     *   - app_process invocation: "...--nice-name=byd_cam_daemon..."
     *   - some launchers append "com.overdrive.app.daemon.CameraDaemon"
     *     as the entry-point arg
     *
     * We anchor on the underscore-named token / FQCN to reduce collisions
     * with unrelated processes (e.g. `logcat -s CameraDaemon`, `grep
     * cam_daemon`, an ADB shell that has these strings in its argv). A
     * bare `cam_daemon` substring is too broad; require either the
     * "byd_" prefix or the FQCN.
     */
    private static boolean isCameraDaemonCmdline(String cmdline) {
        if (cmdline == null || cmdline.isEmpty()) return false;
        return cmdline.contains("byd_cam_daemon")
            || cmdline.contains("com.overdrive.app.daemon.CameraDaemon");
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
            new File(lockFilePath()).delete();
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

    private static boolean publishTripAnalyticsManager(
            com.overdrive.app.trips.TripAnalyticsManager manager,
            SohEstimator sohEstimatorSnapshot) {
        synchronized (TRIP_ANALYTICS_LIFECYCLE_LOCK) {
            if (manager == null
                    || TRIP_ANALYTICS_SHUTDOWN_REQUESTED.get()
                    || shutdownStarted.get()
                    || !running.get()) {
                return false;
            }

            try {
                // Complete every operation that can touch the newly
                // initialized manager before releasing the admission lock.
                // Shutdown marks its intent under this same lock, so it cannot
                // close the manager mid-publication.
                runTripConsumptionBucketMigration(
                        manager, sohEstimatorSnapshot);

                // initSurveillance() currently runs before this thread starts.
                // Re-read the volatile defensively so a future ordering change
                // still binds the final collector before shutdown intervenes.
                com.overdrive.app.telemetry.TelemetryDataCollector collector =
                        telemetryDataCollector;
                if (collector != null) {
                    try {
                        manager.setTelemetryDataCollector(collector);
                    } catch (Throwable bindFailure) {
                        log("Trip Analytics collector bind failed: "
                                + bindFailure.getMessage());
                    }
                }

                // Publish only after all startup dependencies are wired.
                // A gear edge that arrived before the database finished opening
                // is recovered by the ACC-ON gear probe in onAccOn(), and by
                // GearMonitor's next notification.
                tripAnalyticsManager = manager;
                return true;
            } catch (Exception e) {
                log("Trip Analytics publication failed: "
                        + e.getMessage());
                return false;
            }
        }
    }

    private static void runTripConsumptionBucketMigration(
            com.overdrive.app.trips.TripAnalyticsManager manager,
            SohEstimator sohEstimatorSnapshot) {
        // ONE-TIME migration: clear poisoned consumption buckets if this is a
        // PHEV and the migration has not already completed.
        try {
            java.io.File marker = new java.io.File(
                    ScratchPaths.path("overdrive_bucket_migration_done"));
            if (sohEstimatorSnapshot == null
                    || sohEstimatorSnapshot.getNominalCapacityKwh() <= 0
                    || sohEstimatorSnapshot.getNominalCapacityKwh() >= 30.0
                    || manager.getDatabase() == null
                    || marker.exists()) {
                return;
            }
            manager.getDatabase().clearConsumptionBuckets();
            com.overdrive.app.trips.RangeEstimator estimator =
                    manager.getRangeEstimator();
            if (estimator == null) {
                log("WARNING: PHEV bucket migration could not request "
                        + "backfill; marker not written");
                return;
            }
            log("One-time PHEV bucket migration: cleared poisoned "
                    + "consumption data; buckets refill as trips complete");
            try {
                new java.io.FileWriter(marker).close();
            } catch (Exception markerFailure) {
                log("WARNING: Could not write bucket migration marker: "
                        + markerFailure.getMessage());
            }
        } catch (Throwable migrationFailure) {
            log("WARNING: PHEV bucket migration failed: "
                    + migrationFailure.getMessage());
        }
    }

    private static void markTripAnalyticsShutdownRequested() {
        synchronized (TRIP_ANALYTICS_LIFECYCLE_LOCK) {
            TRIP_ANALYTICS_SHUTDOWN_REQUESTED.set(true);
        }
    }

    /**
     * Request a process restart for an unrecoverable camera/EGL failure without
     * ending the current drive. Only this method may service camera watchdog
     * exits: it waits for trip initialization, durably checkpoints the active
     * recorder and journal, then lets the JVM shutdown hook release resources.
     *
     * <p>If storage is temporarily unavailable, the non-daemon coordinator keeps
     * retrying while telemetry collection remains alive. A hard halt/kill is
     * never used BEFORE the checkpoint is durable because it would bypass both
     * the journal checkpoint and the resource cleanup hook. AFTER the checkpoint
     * commits, the terminal shutdown guard is armed just before System.exit so a
     * shutdown hook wedged on the failed camera/GL state cannot hold the process
     * open forever — at that point a forced halt loses nothing.
     *
     * <p>The ONE deliberate exception to "no halt before the checkpoint is
     * durable" is {@link #requestUrgentCameraReleaseRestart}: when the process
     * holds an AVMCamera handle it can no longer safely close or yield, an
     * unbounded checkpoint wait leaves the native AVM app without video
     * indefinitely — and the checkpoint write can BLOCK on the very storage
     * wedge that broke the camera path in the first place (the flush path
     * swallows write FAILURES, so this coordinator only ever waits on init or
     * on a blocked write — see prepareTripsForProcessRestart). That variant
     * arms a short independent halt deadline first and then delegates here,
     * trading a bounded telemetry-buffer loss for a guaranteed camera release.
     */
    /**
     * True once a trip-safe process restart has been requested and its coordinator
     * is (or is about to be) running. Camera/pipeline lifecycle entry points consult
     * this to refuse bringing up NEW camera/GL state while the process is on its way
     * down: the coordinator is ASYNCHRONOUS and can retry the trip checkpoint for a
     * while before System.exit, and a fresh pipeline started in that window would
     * open a second camera/EGL stack alongside the wedged one the restart exists to
     * escape. The flag self-clears on the coordinator's failure paths, so a restart
     * that could not be carried out lifts the gate rather than bricking the daemon.
     *
     * <p>Also true once an URGENT camera-release restart has armed its halt
     * deadline ({@link #requestUrgentCameraReleaseRestart}). That latch never
     * self-clears: the halt is non-cancellable, so process death is guaranteed
     * and bringing up new camera/GL state in the remaining seconds would only
     * hand the wrapper a dirtier crash.
     */
    public static boolean isProcessRestartPending() {
        return PROCESS_RESTART_REQUESTED.get() || URGENT_CAMERA_RELEASE_ARMED.get();
    }

    public static void requestProcessRestartPreservingTrip(String reason) {
        if (!PROCESS_RESTART_REQUESTED.compareAndSet(false, true)) {
            return;
        }

        final Thread coordinator;
        try {
            coordinator = new Thread(() -> {
                int attempts = 0;
                while (!processRestartIntent) {
                    attempts++;
                    if (prepareTripsForProcessRestart(reason)) {
                        processRestartIntent = true;
                        log("Trip-safe process restart prepared: " + reason);
                        // Arm the independent kill path BEFORE System.exit: the
                        // shutdown hook it triggers can wedge on the very
                        // GL/H2/monitor state that forced this restart, and a
                        // wedged hook would otherwise hold the process open
                        // forever. The trip is already durably checkpointed, so
                        // a forced halt after the budget loses nothing.
                        boolean guardArmed = armTerminalShutdownDeadline();
                        if (!guardArmed) {
                            log("Trip-safe restart: terminal shutdown guard could not be armed; "
                                    + "exiting anyway (hook wedge would require external kill)");
                        }
                        try {
                            System.exit(0);
                        } catch (Throwable t) {
                            processRestartIntent = false;
                            PROCESS_RESTART_REQUESTED.set(false);
                            HARDWARE_QUERY_PROCESS_RECOVERY_REQUESTED.set(false);
                            if (guardArmed) {
                                // Exit did not happen; don't let the armed
                                // deadline halt a process we chose to keep.
                                disarmTerminalShutdownDeadline();
                            }
                            // The checkpoint left the trip open and recording,
                            // so there is no prepared state to undo here.
                            log("Trip-safe System.exit failed; process left running: "
                                    + t.getMessage());
                        }
                        return;
                    }

                    if (attempts == 1 || attempts % 10 == 0) {
                        log("Deferring camera process restart until trip checkpoint is durable"
                                + " (attempt=" + attempts + ", reason=" + reason + ")");
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        PROCESS_RESTART_REQUESTED.set(false);
                        HARDWARE_QUERY_PROCESS_RECOVERY_REQUESTED.set(false);
                        return;
                    }
                }
            }, "TripSafeProcessRestart");
        } catch (Throwable creationFailure) {
            PROCESS_RESTART_REQUESTED.set(false);
            HARDWARE_QUERY_PROCESS_RECOVERY_REQUESTED.set(false);
            log("Could not create trip-safe process restart coordinator: "
                    + creationFailure.getMessage());
            return;
        }
        try {
            coordinator.setDaemon(false);
            coordinator.start();
        } catch (Throwable startFailure) {
            PROCESS_RESTART_REQUESTED.set(false);
            HARDWARE_QUERY_PROCESS_RECOVERY_REQUESTED.set(false);
            log("Could not start trip-safe process restart coordinator: "
                    + startFailure.getMessage());
        }
    }

    /**
     * URGENT bounded variant of {@link #requestProcessRestartPreservingTrip},
     * for callers that hold an AVMCamera handle they can no longer safely
     * close or yield (wedged encoder drainer before a camera close, GL thread
     * heartbeat-dead while the camera is open). Process death is the only safe
     * way to release the handle, and it must happen on a short bound: the
     * native BYD AVM app shows NO VIDEO SIGNAL until the handle is released,
     * and the conservative coordinator can wait indefinitely (its checkpoint
     * write can block on the same wedged FUSE/SD mount that broke the camera
     * path — write FAILURES are swallowed by the flush path, so only init-wait
     * and blocked writes ever hold it).
     *
     * <p>Ordering contract:
     * <ol>
     *   <li>Arm a dedicated, NON-CANCELLABLE halt deadline
     *       ({@link #URGENT_CAMERA_RELEASE_BUDGET_MS}) first. Deliberately not
     *       {@link #armTerminalShutdownDeadline()}: that shared guard is
     *       disarmed by parkTerminate's deferral path and by the conservative
     *       coordinator's System.exit-failure handling — either would silently
     *       cancel the camera-release guarantee. Park flows and AVM usage
     *       coincide (parking maneuvers), so that race is realistic.</li>
     *   <li>Arm-once: the FIRST wedge site's deadline stands; later calls in
     *       the same incident return immediately.</li>
     *   <li>Arms even when {@link #PROCESS_RESTART_REQUESTED} is already true
     *       (escalation of an in-flight conservative restart, e.g. one already
     *       requested by an encoder-worker wedge from the same stall).</li>
     *   <li>Then delegate to the conservative coordinator so the trip
     *       checkpoint gets its bounded shot; if it lands first, the normal
     *       trip-safe System.exit wins the race and the halt never fires.</li>
     *   <li>If NO deadline can be armed, halt immediately — never fall back to
     *       an unbounded path, and never attempt a synchronous checkpoint here
     *       (a blocking flush is the exact trap this method exists to escape).</li>
     * </ol>
     *
     * <p>The deadline fires {@link #forceTerminateProcess} — SIGKILL via
     * Process.killProcess (the wrapper normally observes 137), with the
     * finally-block Runtime.halt(0) running whenever killProcess returns or
     * throws, so termination is unconditional either way — and NOT
     * System.exit: shutdown hooks could pile onto the wedged
     * startStopLock/GL/storage state and hold the process open. The
     * DaemonLauncher watchdog respawns on ANY exit code unless the user
     * disable-sentinel or parked marker exists, and clears the stale FileLock
     * file on 137/134 so the respawn isn't refused by the singleton-lock
     * check.
     *
     * <p>Worst-case data loss: the un-flushed telemetry buffer (≤60s cadence),
     * or the whole trip if the halt lands before the first 5s flush; the trip
     * row itself is rebuilt from the on-disk .jsonl.gz by next-boot recovery
     * whenever any flush has landed. Accepted trade — the alternative is an
     * indefinite camera blackout in the vehicle's parking/AVM display.
     */
    public static void requestUrgentCameraReleaseRestart(String reason) {
        if (!URGENT_CAMERA_RELEASE_ARMED.compareAndSet(false, true)) {
            return;
        }

        // Arm FIRST, log AFTER (audit follow-up): the logger can itself block
        // on the wedged FUSE/SD storage this path fires under. Logging before
        // the guards are armed would set the arm-once latch, wedge here, and
        // leave NO deadline running while every later urgent request no-ops
        // on the latch — exactly the lost guarantee this method exists to
        // prevent. Nothing observable happens between the latch CAS and the
        // guard arms below.
        //
        // Monotonic deadline, stamped BEFORE the guard thread starts:
        // elapsedRealtime is immune to wall-clock changes (NTP/manual set
        // would stretch or shrink a currentTimeMillis window), and stamping it
        // here means scheduler delay in starting the guard thread cannot
        // extend the camera-hold bound — the 5s clock is already running.
        final long haltDeadlineElapsedMs =
                android.os.SystemClock.elapsedRealtime() + URGENT_CAMERA_RELEASE_BUDGET_MS;

        boolean armed = false;
        Throwable guardStartFailure = null;
        try {
            Thread guard = new Thread(() -> {
                // Non-cancellable AND non-accelerable: wait out the full
                // deadline across interrupts (nothing should hold a reference
                // to this thread, but an errant interrupt must neither cancel
                // the halt nor fire it early — the checkpoint deserves its
                // full bounded shot).
                long remaining;
                while ((remaining = haltDeadlineElapsedMs
                        - android.os.SystemClock.elapsedRealtime()) > 0) {
                    try {
                        Thread.sleep(remaining);
                    } catch (InterruptedException ignored) {
                        // fall through and re-check the deadline
                    }
                }
                forceTerminateProcess("urgent camera-release deadline exceeded: " + reason);
            }, "UrgentCameraReleaseGuard");
            guard.setDaemon(false);
            guard.start();
            armed = true;
        } catch (Throwable t) {
            // Defer the failure log: NO deadline is armed yet, and the logger
            // can block on the wedged storage this path fires under — logging
            // here would leave the latch set with nothing running, exactly the
            // lost guarantee this method exists to prevent. Reported below,
            // after a deadline exists (or the immediate halt makes it moot).
            guardStartFailure = t;
        }

        // Second, independent arm on the main handler (same dual-arm shape as
        // armTerminalShutdownDeadline): covers a thread-creation failure as
        // long as the main looper is still turning. Scheduled with the
        // REMAINING monotonic budget, not a fresh window — if the thread arm
        // stalled before failing, a fresh 5s here would extend the
        // camera-hold bound past the stamped deadline. Never removed — the
        // halt is idempotent via forceTerminationStarted, and a stray late
        // halt of a process that is exiting anyway is harmless.
        Handler handler = mainHandler;
        if (handler != null) {
            try {
                long remainingBudgetMs = haltDeadlineElapsedMs
                        - android.os.SystemClock.elapsedRealtime();
                if (remainingBudgetMs <= 0) {
                    // The deadline already expired while arming (e.g. the
                    // thread arm stalled before failing). postDelayed(0) would
                    // only QUEUE the halt behind a possibly-blocked main
                    // looper — and when the thread arm failed, this queue
                    // entry would be the ONLY "deadline", making the
                    // guarantee depend on the very looper health this path
                    // cannot assume. The contract is already breached: halt
                    // now. (Idempotent — a concurrent halt just wins.)
                    forceTerminateProcess(
                            "urgent camera-release deadline already expired during arming: "
                                    + reason);
                    return;
                }
                if (handler.postDelayed(
                        () -> forceTerminateProcess(
                                "urgent camera-release deadline exceeded (main loop): "
                                        + reason),
                        remainingBudgetMs)) {
                    armed = true;
                }
            } catch (Throwable ignored) {}
        }

        if (!armed) {
            // No deadline could be armed at all (OOM-grade distress). Halting
            // immediately is the only way to keep the camera-release
            // guarantee — silently degrading to the unbounded coordinator
            // would re-open the indefinite native-app blackout this method
            // exists to close.
            forceTerminateProcess("urgent camera-release guard unavailable: " + reason);
            return;
        }

        // Only NOW is it safe to touch the (possibly wedged) logger: the halt
        // deadline is armed, so a blocking log call can no longer strand the
        // camera — the guard halts the process out from under it.
        try {
            log("URGENT camera-release restart armed: halting in "
                    + URGENT_CAMERA_RELEASE_BUDGET_MS + "ms unless the trip-safe exit "
                    + "lands first (" + reason + ")");
        } catch (Throwable ignored) {}
        if (guardStartFailure != null) {
            try {
                log("Urgent camera-release guard thread could not start ("
                        + guardStartFailure.getMessage()
                        + ") — halt deadline is riding the main-handler fallback");
            } catch (Throwable ignored) {}
        }

        // Give the trip checkpoint its bounded shot. A no-op if a conservative
        // restart is already pending — the deadline above is armed either way,
        // which is exactly the escalation semantics we need.
        try {
            requestProcessRestartPreservingTrip(reason);
        } catch (Throwable t) {
            try {
                log("Urgent camera-release: trip-safe delegate failed: " + t.getMessage());
            } catch (Throwable ignored) {}
        }
    }

    private static boolean prepareTripsForProcessRestart(String reason) {
        java.util.concurrent.CompletableFuture<Void> initFuture = tripAnalyticsInitFuture;
        if (initFuture == null || !initFuture.isDone()) {
            return false;
        }

        com.overdrive.app.trips.TripAnalyticsManager manager = tripAnalyticsManager;
        if (manager == null) {
            // Initialization completed without publishing a manager, so this
            // process cannot own an active in-memory trip. Any older journal
            // remains untouched for the wrapper's next process.
            return true;
        }
        if (!manager.isInitialized()) {
            return false;
        }
        if (!manager.isEnabled()) {
            return true;
        }
        try {
            // Flush buffered telemetry and leave the trip OPEN. Next boot
            // rebuilds the row from the on-disk file, so a camera restart
            // mid-drive does not end the trip.
            manager.checkpointActiveTrip();
            return true;
        } catch (Throwable t) {
            log("Trip checkpoint before camera restart failed (" + reason + "): "
                    + t.getMessage());
            return false;
        }
    }

    static boolean shouldFinalizeTripsOnShutdown() {
        return !processRestartIntent;
    }

    public static com.overdrive.app.charging.ChargingSessionManager getChargingSessionManager() {
        return chargingSessionManager;
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

            // Resolve camera profile (Seal vs Tang) so the pipeline gets
            // correct strip dimensions per vehicle. Falls back to legacy Seal
            // if ro.product.model is unrecognized — same behavior as before
            // for existing Seal/Atto installs.
            com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
                com.overdrive.app.camera.CameraConfigResolver.resolve();

            // SOTA: Use StorageManager for surveillance output directory
            com.overdrive.app.storage.StorageManager storageManager =
                com.overdrive.app.storage.StorageManager.getInstance();
            File eventDir = storageManager.getSurveillanceDir();

            // Create GPU pipeline with resolved profile dimensions
            recordingModeManagerPipelineOwner = null;
            gpuPipeline = new com.overdrive.app.surveillance.GpuSurveillancePipeline(
                resolvedCamera.getPanoWidth(), resolvedCamera.getPanoHeight(), eventDir);

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

            // Prefer the canonical recordingQuality tier (ECONOMY..MAX) over
            // the legacy recordingBitrate (LOW/MEDIUM/HIGH) — applyPersistedSettings
            // will later apply recordingQuality, and if pre-init used the
            // legacy preset (which maps to a smaller bitrate range) the
            // encoder gets reinitialized at boot. That reinit allocates a
            // larger pre-record pool against the daemon's already-warm heap
            // and can OOM (5s × 30fps × 10Mbps tries to grab 187 MB).
            String persistedQuality = HttpServer.getRecordingQuality();
            if (persistedQuality != null) {
                com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality tier =
                    com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality.fromString(persistedQuality);
                gpuPipeline.getConfig().setRecordingQuality(tier);
                int effectiveBitrate = gpuPipeline.getConfig().getEffectiveBitrate();
                log("Pre-init: Set quality to " + tier + " (" + effectiveBitrate / 1_000_000 + " Mbps for " +
                    gpuPipeline.getConfig().getVideoCodec() + ")");
            } else {
                // Fall back to legacy bitrate preset for installs that haven't
                // migrated to the tier-based config yet.
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
            }

            gpuPipeline.init(assetManager, com.overdrive.app.daemon.DaemonBootstrap.getContext());

            log("GPU Surveillance initialized: profile=" + resolvedCamera.getProfile().getDisplayName()
                + ", panoCam=" + resolvedCamera.getPanoCameraId()
                + ", size=" + resolvedCamera.getPanoWidth() + "x" + resolvedCamera.getPanoHeight()
                + " -> " + resolvedCamera.getProfile().getEncoderWidth()
                + "x" + resolvedCamera.getProfile().getEncoderHeight() + " (mosaic)");

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
                com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = gpuPipeline;
                com.overdrive.app.recording.RecordingModeManager manager =
                    new com.overdrive.app.recording.RecordingModeManager(
                        sharedAppContext, pipeline);
                recordingModeManagerPipelineOwner = pipeline;
                recordingModeManager = manager;
                log("RecordingModeManager initialized");

                // Create AVC HAL warmup instance (shared with RecordingModeManager)
                // under the same init lock as startAvcKeepAliveIfNeeded so
                // we don't race a worker thread that just observed null
                // and is about to instantiate its own.
                synchronized (AVC_WARMUP_INIT_LOCK) {
                    if (avcHalWarmup == null) {
                        avcHalWarmup = new com.overdrive.app.camera.AvcHalWarmup();
                    }
                }
                log("AvcHalWarmup initialized");

                // dilink4: kick AVC keep-alive at boot regardless of pipeline
                // state. The byd_apa AVM HAL gates frame delivery on
                // com.byd.avc being a co-consumer; we cannot afford to let it
                // get reaped between camera consumers (streaming-client
                // connect, sentry arm, recording start). startKeepAlive
                // re-launches AVC every 60 s; AccSentry's 10 s pidof tick
                // covers the gap during sentry mode.
                if (isDilink4ModeActive()) {
                    try {
                        com.overdrive.app.camera.AvcHalWarmup.ensureAvcAlive();
                    } catch (Throwable t) {
                        log("Boot-time AVC ensureAlive failed: " + t.getMessage());
                    }
                    if (!avcHalWarmup.isActive()) {
                        avcHalWarmup.startKeepAlive();
                        log("AVC keep-alive started at daemon boot (dilink4)");
                    }
                }

                // Now initialize TelemetryDataCollector (context is guaranteed available)
                try {
                    telemetryDataCollector =
                        new com.overdrive.app.telemetry.TelemetryDataCollector();
                    telemetryDataCollector.init(sharedAppContext);
                    gpuPipeline.setTelemetryCollector(telemetryDataCollector);

                    // Apply persisted overlay enabled state. The resolver
                    // honours per-flow keys (panoEnabled / oemDashcamEnabled)
                    // and falls back to legacy `enabled` for pano so older
                    // configs continue to work.
                    boolean overlayEnabled = com.overdrive.app.config.UnifiedConfigManager
                        .isTelemetryOverlayEnabledFor("pano");
                    gpuPipeline.setOverlayEnabled(overlayEnabled);
                    // Apply the independent ACC-off surveillance overlay master
                    // (opt-in, default off). Its field selection is loaded per
                    // event at record-start via the pipeline's flow resolver.
                    boolean survOverlayEnabled = com.overdrive.app.config.UnifiedConfigManager
                        .isTelemetryOverlayEnabledFor("surveillance");
                    gpuPipeline.setSurveillanceOverlayEnabled(survOverlayEnabled);
                    log("TelemetryDataCollector initialized, pano overlay=" + overlayEnabled
                        + " surveillance overlay=" + survOverlayEnabled);

                    // Apply persisted recording layout (standard 360 mosaic / dashcam)
                    String recLayout = com.overdrive.app.config.UnifiedConfigManager
                        .getRecording().optString("recordingLayout", "standard");
                    gpuPipeline.setRecordingLayout("dashcam".equals(recLayout) ? 1 : 0);
                    boolean dashcamUseWindshield = com.overdrive.app.config.UnifiedConfigManager
                        .getRecording().optBoolean("dashcamUseWindshield", false);
                    gpuPipeline.setDashcamUseWindshield(dashcamUseWindshield);
                    log("Recording layout: " + recLayout);

                    // Apply persisted sentry (surveillance) layout — the
                    // independent counterpart to the dashcam layout above.
                    // Falls back to the dashcam values when the sentry keys are
                    // unset so existing installs keep their current look.
                    org.json.JSONObject survLayoutCfg =
                        com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                    String survLayout = survLayoutCfg.optString("recordingLayout", recLayout);
                    gpuPipeline.setSurveillanceRecordingLayout("dashcam".equals(survLayout) ? 1 : 0);
                    boolean survUseWindshield = survLayoutCfg.has("useWindshield")
                        ? survLayoutCfg.optBoolean("useWindshield", false)
                        : dashcamUseWindshield;
                    gpuPipeline.setSurveillanceUseWindshield(survUseWindshield);
                    log("Sentry layout: " + survLayout);

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
                // FIX (audit R2, finding "Boot recovery probe leaves
                // pendingAccOn dangling when sharedAppContext is null at
                // init"): without a watchdog, rmm stays null until the user
                // toggles ACC AND the OFF→ON IPCs are not deduped. Spawn a
                // bounded poll thread that watches for sharedAppContext to
                // become valid, then drives reinitContextDependentComponents
                // to create rmm and drain pendingAccOn/Off.
                scheduleSharedContextWatchdog();
            }
            // Successful init — reset retry counter so a future restart-style
            // re-entry starts from attempt 0.
            initSurveillanceRetryAttempts.set(0);

        } catch (Exception e) {
            log("ERROR: GPU Surveillance init failed: " + e.getMessage());
            log("ERROR: Exception type: " + e.getClass().getName());
            if (e.getCause() != null) {
                log("ERROR: Caused by: " + e.getCause().getMessage());
            }
            // Print stack trace to logcat
            e.printStackTrace();
            recordingModeManagerPipelineOwner = null;
            gpuPipeline = null;
            // FIX (audit R2, finding "initSurveillance() exception → permanent
            // gpuPipeline=null with no retry path"): kick a bounded
            // exponential-backoff retry on a background thread. Without this,
            // every subsequent IPC takes the gpuPipeline-null queue branch,
            // the post-init drain never fires, and pano recording stays dead
            // until manual daemon restart or reboot.
            scheduleInitSurveillanceRetry();
        }
    }

    /**
     * FIX (audit R2): bounded retry of initSurveillance() after a transient
     * cold-boot failure. CAS-guarded so concurrent callers don't queue
     * duplicates; surfaces final failure via log and the gpuPipeline-null
     * branch the existing /api/status / IPC paths already cope with.
     */
    private static void scheduleInitSurveillanceRetry() {
        if (!initSurveillanceRetryInFlight.compareAndSet(false, true)) {
            log("initSurveillance retry already in flight — skipping duplicate schedule");
            return;
        }
        final int attempt = initSurveillanceRetryAttempts.get();
        // FIX (audit R8): no budget cap. Use the explicit step table for the
        // first few attempts, then clamp to INIT_SURVEILLANCE_RETRY_MAX_DELAY_MS
        // forever. Success short-circuits via the gpuPipeline!=null check.
        final long delayMs;
        if (attempt < INIT_SURVEILLANCE_RETRY_DELAYS_MS.length) {
            delayMs = INIT_SURVEILLANCE_RETRY_DELAYS_MS[attempt];
        } else {
            delayMs = INIT_SURVEILLANCE_RETRY_MAX_DELAY_MS;
        }
        Thread t = new Thread(() -> {
            try {
                log("initSurveillance retry: attempt " + (attempt + 1)
                    + " scheduled in " + delayMs + "ms (uncapped)");
                try { Thread.sleep(delayMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (gpuPipeline != null) {
                    log("initSurveillance retry: gpuPipeline already non-null — skipping retry");
                    return;
                }
                initSurveillanceRetryAttempts.incrementAndGet();
                log("initSurveillance retry: invoking initSurveillance() (attempt "
                    + (attempt + 1) + ")");
                initSurveillance();
                if (gpuPipeline != null) {
                    log("initSurveillance retry: SUCCESS on attempt " + (attempt + 1)
                        + " — draining pending ACC state");
                    // Replay any queued ACC state through the post-init drain
                    // shape used at end of main(). Cannot call drain
                    // directly (it lives inline in main()), so re-enter the
                    // dispatch path: pendingAccOn/Off is read by the boot
                    // probe shape we mirror here.
                    //
                    // FIX (audit R8, finding "retry replay path lacks HW guard"):
                    // up to several minutes elapse during retries; HW state can
                    // flip without an IPC reaching us in that window. HW-probe
                    // before replay so we don't seed RMM with a stale flag.
                    // Mirrors the main() drain shape at lines 731-769.
                    try {
                        long replayProbeGeneration =
                            captureAccObservationGeneration();
                        AccProbeResult replayProbe =
                            probeAccStateWithBackoff("retry-replay");
                        boolean hwAccIsOff_replay = replayProbe.accIsOff;
                        boolean hwAccIsOn_replay = !hwAccIsOff_replay;
                        if (!replayProbe.trustworthy) {
                            log("initSurveillance retry: hardware probe was not trustworthy; "
                                + "preserving pending ACC state");
                            requestTrustedAccHardwareRecovery(
                                "untrustworthy init-surveillance replay probe");
                            return;
                        }
                        if (!isAccObservationCurrent(replayProbeGeneration)) {
                            log("initSurveillance retry: hardware probe superseded; "
                                + "preserving pending ACC state");
                            return;
                        }
                        if (hasPendingAccState(true) && recordingModeManager != null) {
                            long pendingGeneration = claimPendingAccState(true);
                            if (hwAccIsOff_replay) {
                                if (pendingGeneration != 0L) {
                                    log("initSurveillance retry: replaying pending ACC OFF (HW-probed)");
                                    onAccStateChanged(true, pendingGeneration);
                                }
                            } else {
                                if (pendingGeneration != 0L) {
                                    log("initSurveillance retry: pending ACC OFF discarded — HW probe shows ACC ON");
                                    onObservedAccStateChanged(
                                        false,
                                        replayProbeGeneration,
                                        "retry-replay");
                                    // Release the power latch the queued edge already set, same as the
                                    // main() drain — otherwise a retracted "off" keeps suppressing the
                                    // true state until the next ACC dispatch.
                                    com.overdrive.app.automation.condition.BydEvent
                                        .resetPowerEdge(false);
                                }
                            }
                        } else if (hasPendingAccState(false) && recordingModeManager != null) {
                            long pendingGeneration = claimPendingAccState(false);
                            if (hwAccIsOn_replay) {
                                if (pendingGeneration != 0L) {
                                    log("initSurveillance retry: replaying pending ACC ON (HW-probed)");
                                    onAccStateChanged(false, pendingGeneration);
                                }
                            } else {
                                if (pendingGeneration != 0L) {
                                    log("initSurveillance retry: pending ACC ON discarded — HW probe shows ACC OFF");
                                    onObservedAccStateChanged(
                                        true,
                                        replayProbeGeneration,
                                        "retry-replay");
                                    // Symmetric to the ACC OFF replay above — release the retracted edge.
                                    com.overdrive.app.automation.condition.BydEvent
                                        .resetPowerEdge(true);
                                }
                            }
                        }
                    } catch (Throwable th) {
                        log("WARN: initSurveillance retry replay failed: " + th.getMessage());
                    }
                }
            } finally {
                initSurveillanceRetryInFlight.set(false);
                // FIX (audit R8): re-arm forever as long as initSurveillance
                // hasn't succeeded. Eventual HAL recovery (hours later) can
                // still self-heal pano without daemon restart.
                if (gpuPipeline == null) {
                    log("initSurveillance retry: still null — re-arming next attempt (uncapped)");
                    scheduleInitSurveillanceRetry();
                }
            }
        }, "InitSurveillanceRetry");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable startFailure) {
            initSurveillanceRetryInFlight.set(false);
            log("initSurveillance retry worker could not start: "
                + startFailure.getMessage());
            Handler handler = mainHandler;
            if (handler != null) {
                handler.postDelayed(
                    CameraDaemon::scheduleInitSurveillanceRetry, 1_000L);
            }
        }
    }

    /**
     * FIX (audit R2): one-shot bounded poll thread that waits for
     * sharedAppContext to become valid and then drives
     * reinitContextDependentComponents(), which itself drains
     * pendingAccOn/Off into a freshly-created RecordingModeManager.
     * CAS-guarded against duplicates. Bails after CONTEXT_WATCHDOG_MAX_DURATION_MS.
     */
    private static void scheduleSharedContextWatchdog() {
        if (!contextWatchdogInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            log("sharedAppContext watchdog: starting (poll="
                + CONTEXT_WATCHDOG_POLL_INTERVAL_MS + "ms, max="
                + CONTEXT_WATCHDOG_MAX_DURATION_MS + "ms)");
            long deadline = System.currentTimeMillis() + CONTEXT_WATCHDOG_MAX_DURATION_MS;
            try {
                while (System.currentTimeMillis() < deadline) {
                    if (sharedAppContext == null) {
                        try {
                            android.content.Context ctx = createAppContext();
                            if (ctx != null && !isContextBrokenFor(ctx)) {
                                sharedAppContext = ctx;
                                log("sharedAppContext watchdog: context created — "
                                    + "invoking reinitContextDependentComponents to drain queue");
                                reinitContextDependentComponentsForCurrentAccState();
                                return;
                            }
                        } catch (Throwable th) {
                            log("sharedAppContext watchdog: createAppContext threw: "
                                + th.getMessage());
                        }
                    } else if (recordingModeManager == null && gpuPipeline != null) {
                        // Context appeared via another path — finish the
                        // job by running the rmm-creation drain.
                        log("sharedAppContext watchdog: context now non-null but rmm null — "
                            + "invoking reinitContextDependentComponents");
                        reinitContextDependentComponentsForCurrentAccState();
                        return;
                    } else {
                        // rmm already exists — nothing left to do.
                        return;
                    }
                    try { Thread.sleep(CONTEXT_WATCHDOG_POLL_INTERVAL_MS); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                log("WARN: sharedAppContext watchdog: timed out after "
                    + CONTEXT_WATCHDOG_MAX_DURATION_MS + "ms — rmm still null, "
                    + "next ACC IPC will retry via existing isContextBroken path");
            } finally {
                contextWatchdogInFlight.set(false);
                if (running.get() && recordingModeManager == null
                        && gpuPipeline != null) {
                    Handler handler = mainHandler;
                    if (handler != null) {
                        handler.postDelayed(
                            CameraDaemon::scheduleSharedContextWatchdog,
                            30_000L);
                    }
                }
            }
        }, "SharedContextWatchdog");
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable startFailure) {
            contextWatchdogInFlight.set(false);
            log("sharedAppContext watchdog worker could not start: "
                + startFailure.getMessage());
            Handler handler = mainHandler;
            if (handler != null) {
                handler.postDelayed(
                    CameraDaemon::scheduleSharedContextWatchdog, 1_000L);
            }
        }
    }

    /**
     * Enable surveillance mode.
     */
    public static void enableSurveillance() {
        long generation = captureCurrentAccOffGenerationForSurveillance();
        if (generation < 0L) {
            log("enableSurveillance() REJECTED — ACC is ON or not yet authoritatively known");
            return;
        }
        enableSurveillanceForAccGeneration(generation, "public/deferred request");
    }

    private static boolean enableSurveillanceForAccGeneration(
            long expectedGeneration, String source) {
        SurveillanceEnableLease lease =
            claimSurveillanceEnableLease(expectedGeneration);
        if (lease == null) {
            log("enableSurveillance() skipped (" + source
                + ") — ACC OFF generation is no longer current");
            return false;
        }
        // OEM Dashcam: every non-ACC-rejected exit path of this method must
        // fire a recalc, because the user-facing surveillance state may have
        // changed (suppression cleared, schedule window opened, lock-gate
        // armed, etc.) and the resolver re-evaluates survSuppressed +
        // keepWarmSurv from the latest UCM/safe-zone/schedule state. The
        // try/finally guarantees the recalc fires even when an exception
        // propagates out of the surveillance start path.
        try {
            if (stopStaleSurveillanceEnable(lease, source + " admission")) {
                return false;
            }
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                gpuPipeline;
            if (pipeline == null) {
                log("GPU pipeline not ready — queuing surveillance enable for when pipeline initializes");
                markCurrentAccApplyDeferred();
                invalidateLatestAccCompletionForCompensation();
                if (!queuePendingAccState(true, lease.generation)) {
                    log("GPU pipeline queue skipped — ACC OFF phase was superseded");
                    requestAccTransitionReconciliation(true);
                }
                if (!isAccTransitionCurrent(lease.generation, true)) {
                    forceLatestAccStateReconciliation(
                        "stale pipeline-null surveillance deferral");
                }
                return false;
            }

            // SOTA: Safe Location check — don't start camera if parked at safe zone
            com.overdrive.app.surveillance.SafeLocationManager safeMgr =
                com.overdrive.app.surveillance.SafeLocationManager.getInstance();
            if (safeMgr.isInSafeZone()) {
                if (stopStaleSurveillanceEnable(
                        lease, source + " safe-zone lookup")) {
                    return false;
                }
                log("SAFE ZONE: Surveillance suppressed — " + safeMgr.getCurrentZoneName()
                    + " (dist=" + Math.round(safeMgr.getDistanceToNearestZone()) + "m)");
                surveillanceEnabled = true;   // Mark intent so it auto-starts when leaving zone
                safeZoneSuppressed = true;
                if (stopStaleSurveillanceEnable(
                        lease, source + " safe-zone suppression commit")) {
                    return false;
                }
                return false;  // Camera never opens. Zero resources.
            }

            log("Enabling GPU surveillance (pipeline=true"
                + ", running=" + pipeline.isRunning()
                + ", sentry=" + (pipeline.getSentry() != null) + ")");
            surveillanceEnabled = true;
            safeZoneSuppressed = false;

            try {
                if (!pipeline.isRunning()) {
                    log("Pipeline not running — starting...");
                    pipeline.start();
                    if (stopStaleSurveillanceEnable(
                            lease, source + " pipeline start")) {
                        return false;
                    }
                }
                // Enable surveillance mode (motion detection)
                pipeline.enableSurveillance();
                if (stopStaleSurveillanceEnable(
                        lease, source + " surveillance activation")) {
                    return false;
                }
                // AVC keep-alive: same 60s `am start com.byd.avc` poke we use on
                // the ACC-ON / streaming / recording-mode flows. Without it, BYD
                // reaps com.byd.avc during a multi-hour park, the AVM HAL goes
                // cold, frames stall, and the GL watchdog drops into the restart
                // cascade that eventually trips MAX_RETRIES on the wrapper.
                startAvcKeepAliveIfNeeded();
                if (stopStaleSurveillanceEnable(
                        lease, source + " AVC keep-alive")) {
                    return false;
                }
                log("Surveillance mode activated successfully (AVC keep-alive on)");
                return true;
            } catch (Throwable e) {
                log("ERROR: Failed to enable surveillance: " + e.getMessage());
                markCurrentAccApplyRetry();
                forceLatestAccStateReconciliation(
                    "failed surveillance enable (" + source + ")");
                return false;
            }
        } finally {
            releaseSurveillanceEnableLease(lease);
            try {
                com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
            } catch (Throwable ignored) {}
        }
    }

    private static long captureCurrentAccOffGenerationForSurveillance() {
        synchronized (parkTerminateLock) {
            if (latestAccIsOff == null) {
                // The daemon exposes HTTP before the startup hardware recovery probe. Do not turn
                // AccMonitor's default cache value into a synthetic OFF generation in that window.
                return -1L;
            }
            return latestAccIsOff.booleanValue()
                ? accTransitionGeneration : -1L;
        }
    }

    private static SurveillanceEnableLease claimSurveillanceEnableLease(
            long expectedGeneration) {
        AccApplyContext accContext = ACC_APPLY_CONTEXT.get();
        synchronized (parkTerminateLock) {
            if (expectedGeneration != accTransitionGeneration
                    || !Boolean.TRUE.equals(latestAccIsOff)
                    || parkShutdownCommitted
                    || !running.get()) {
                return null;
            }
            if (activeSurveillanceEnableLease != 0L) {
                if (activeSurveillanceEnableGeneration != expectedGeneration
                        || leaseExpired(activeSurveillanceEnableDeadlineNanos)) {
                    revokeActiveSurveillanceEnableLocked(
                        activeSurveillanceEnableGeneration != expectedGeneration
                            ? "newer surveillance generation"
                            : "enable deadline");
                } else {
                    return null;
                }
            }
            // Revoking a prior surveillance owner invalidates the reconciliation revision.
            // Recompute nesting afterward so a stale ACC owner cannot bypass lease exclusion.
            boolean nestedInCurrentAccOwner = accContext != null
                && activeAccTransitionGeneration == accContext.generation
                && activeAccTransitionLease == accContext.lease
                && accContext.revision == accReconciliationRevision;
            if (activeAccTransitionLease != 0L
                    && !nestedInCurrentAccOwner) {
                if (activeAccTransitionGeneration != expectedGeneration
                        || leaseExpired(activeAccTransitionDeadlineNanos)) {
                    revokeActiveAccTransitionLocked(
                        activeAccTransitionGeneration != expectedGeneration
                            ? "newer surveillance generation"
                            : "effect deadline");
                } else {
                    return null;
                }
            }
            long token = ++nextSurveillanceEnableLease;
            activeSurveillanceEnableGeneration = expectedGeneration;
            activeSurveillanceEnableRevision = accReconciliationRevision;
            activeSurveillanceEnableLease = token;
            activeSurveillanceEnableDeadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    SURVEILLANCE_ENABLE_LEASE_MAX_MS);
            activeSurveillanceEnableThread = Thread.currentThread();
            return new SurveillanceEnableLease(
                expectedGeneration, accReconciliationRevision, token);
        }
    }

    private static boolean stopStaleSurveillanceEnable(
            SurveillanceEnableLease lease, String phase) {
        synchronized (parkTerminateLock) {
            if (lease.generation == accTransitionGeneration
                    && Boolean.TRUE.equals(latestAccIsOff)
                    && lease.revision == accReconciliationRevision
                    && activeSurveillanceEnableGeneration == lease.generation
                    && activeSurveillanceEnableRevision == lease.revision
                    && activeSurveillanceEnableLease == lease.token
                    && running.get()
                    && !parkShutdownCommitted) {
                return false;
            }
        }
        log("Surveillance enable gen=" + lease.generation
            + " became stale during " + phase);
        markCurrentAccApplyRetry();
        forceLatestAccStateReconciliation(
            "stale surveillance effect (" + phase + ")");
        return true;
    }

    private static void releaseSurveillanceEnableLease(
            SurveillanceEnableLease lease) {
        boolean retryLatestState = false;
        synchronized (parkTerminateLock) {
            if (activeSurveillanceEnableGeneration == lease.generation
                    && activeSurveillanceEnableRevision == lease.revision
                    && activeSurveillanceEnableLease == lease.token) {
                activeSurveillanceEnableGeneration = 0L;
                activeSurveillanceEnableRevision = 0L;
                activeSurveillanceEnableLease = 0L;
                activeSurveillanceEnableDeadlineNanos = 0L;
                activeSurveillanceEnableThread = null;
                retryLatestState = running.get()
                    && !parkShutdownCommitted
                    && latestAccIsOff != null
                    && !isCurrentAccTransitionCompletedLocked();
            } else {
                // A revoked owner may have landed an effect after its replacement completed.
                retryLatestState = running.get()
                    && !parkShutdownCommitted && latestAccIsOff != null;
            }
        }
        if (retryLatestState) {
            requestAccTransitionReconciliation(false);
        }
    }

    /**
     * Ensure camera is running for surveillance (called by SurveillanceEngine when it becomes active).
     * This avoids circular calls between CameraDaemon and SurveillanceEngine.
     */
    public static void ensureCameraForSurveillance() {
        log("ensureCameraForSurveillance called");
        enableSurveillance();
    }

    /**
     * Disable surveillance mode.
     */
    public static void disableSurveillance() {
        // Log the immediate caller so a spurious disarm is attributable from a
        // single logcat line (user can't always pull full logs). Cheap — only
        // runs on the rare disable path, not in any hot loop.
        String caller = "unknown";
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            // [0]=getStackTrace, [1]=this method, [2]=immediate caller.
            if (st.length > 2) {
                caller = st[2].getMethodName() + "@" + st[2].getFileName()
                    + ":" + st[2].getLineNumber();
            }
        } catch (Throwable ignored) {}
        log("Disabling surveillance mode (caller=" + caller + ")");
        surveillanceEnabled = false;

        if (gpuPipeline != null) {
            gpuPipeline.disableSurveillance();
            // Keep pipeline running for potential streaming
        }
        // OEM Dashcam: surv-axis state changed (schedule window closed, master
        // toggle off, etc.). Recalc so surv=continuous tears down and surv=smart
        // unwarms the pipeline if no other consumer keeps it alive.
        try {
            com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
        } catch (Throwable ignored) {}
    }

    // ==================== DOOR LOCK GATE ====================
    // Surveillance is only armed after doors are locked. This prevents false motion
    // events from the owner exiting the car. Cloud lock detection is primary (MQTT
    // subscriber runs in this process), device SDK is fallback, 60s timeout is last resort.

    /**
     * Register door lock listener and arm surveillance when doors lock.
     * Called from ACC OFF path after all other gates (user enabled, safe zone, schedule) pass.
     *
     * RACE CONDITION SAFETY: Every callback and timeout validates current hardware ACC state
     * before arming. If ACC turns ON during the lock wait, surveillance is NOT armed.
     */
    private static void registerDoorLockListenerAndArmOnLock(long transitionGeneration) {
        if (stopStaleAccTransition(
                transitionGeneration, true, "door-lock gate setup")) {
            return;
        }
        doorLockListenerArmed = false;
        // Reset the fallback signal for this ACC-off cycle. Flipped true by
        // applyLockEvent() the first time any source delivers a definite
        // lock/unlock reading; the 60s force-arm only fires when it stayed false
        // (lock state was never readable on this trim).
        sawValidLockReading = false;

        // Two parallel lock-event sources, in priority order:
        //
        //   1. OTA polling (BYDAutoOtaDevice.getLFDoorLockState) — primary.
        //      Verified live ACC=OFF on DiLink 3.0 with ~1.5s latency. The
        //      legacy BYDAutoDoorLockDevice path (typed listener +
        //      getDoorLockStatus) returned INVALID on every firmware in the
        //      field, so it was removed.
        //
        //   2. Cloud MQTT (BydCloudDataProvider) — secondary. Lags 1-2s
        //      vs OTA on this trim but is the only source for the RF/LR/RR
        //      doors (OTA exposes only LF on DiLink 3.0). When the trim
        //      doesn't expose getLFDoorLockState (older firmware?), cloud
        //      is the sole signal.
        //
        // Both converge through applyLockEvent() which is idempotent —
        // multiple sources reporting the same transition cause exactly one
        // arm or disarm. attachDeviceLockSource() is now a no-op stub kept
        // for symmetry with attachCloudLockSource and the poll thread.

        attachCloudLockSource(transitionGeneration);
        attachDeviceLockSource();
        startUnlockPollThread(transitionGeneration);

        // Initial state probe — priority order: device (OTA-fast-path) BEFORE
        // cloud. The OTA device exposes LF state ACC=OFF with sub-second
        // latency; cloud MQTT can lag 1-2s and may not have a fresh
        // snapshot at gate-entry on cold boot. If device is INVALID
        // (older trim without OTA LF support), cloud initial fills the gap.
        // Both calls are gate-idempotent so order only matters for the
        // log line that reports which source decided.
        Boolean deviceInitial = currentDeviceLockState();
        if (deviceInitial != null) {
            applyLockEvent(deviceInitial, "device-initial", transitionGeneration);
        }
        Boolean cloudInitial = currentCloudLockState();
        if (cloudInitial != null) {
            applyLockEvent(cloudInitial, "cloud-initial", transitionGeneration);
        }

        // Force-arm deadline (lock-mode FALLBACK for trims that can't read lock
        // state): DOOR_LOCK_ARM_TIMEOUT_MS (60s) after ACC-OFF, if lock state was
        // NEVER readable during the window (sawValidLockReading == false), arm
        // anyway. Most BYD trims here return INVALID for the door-lock sensors
        // ACC-off (cloud rarely fires lock events, OTA exposes only the LF door,
        // the legacy device path returns INVALID on every field firmware) — without
        // this fallback, lock mode would never arm on those cars.
        //
        // CRITICAL: this fires ONLY when lock state stayed unreadable. If a source
        // DID deliver a definite reading (sawValidLockReading == true) and it said
        // UNLOCKED, lock mode intentionally stays disarmed — force-arming there
        // would (a) violate lock mode's contract (arm only when locked) and (b)
        // fight the 5s unlock poll, producing the ~5s arm-then-disarm flap that was
        // the original "not arming while unlocked" bug. Users who want arming on an
        // unlocked car should select power mode.
        //
        // Still gated on ACC — if ACC came back ON in the meantime we must not arm.
        Thread timeoutThread = new Thread(() -> {
            try {
                try {
                    Thread.sleep(DOOR_LOCK_ARM_TIMEOUT_MS);
                } catch (InterruptedException ignored) {
                    log("LOCK GATE TIMEOUT: thread interrupted before deadline — not arming");
                    return;
                }
                if (stopStaleAccTransition(
                        transitionGeneration, true, "door-lock timeout")) {
                    return;
                }
                if (!validateAccOffForDeferredEffect(
                        transitionGeneration, "door-lock-timeout")) {
                    return;
                }
            // Authoritative, not a flag check: set armed + call enableSurveillance()
            // directly. Idempotent — a no-op if the pipeline is already running, and
            // enableSurveillance() itself still honors safe-zone / schedule
            // suppression. This guarantees we end armed at 60s even if an unlock
            // inside the grace window had disarmed us.
            //
            // RACE FIX: do the flag write + enableSurveillance() + consistency
            // re-check inside the same monitor that applyLockEvent() synchronizes
            // on (the Class object — both are static). Without this, an
            // applyLockEvent(false) could interleave between enableSurveillance()
            // here and the flag write, see doorLockListenerArmed=true, and call
            // disableSurveillance() — leaving surveillance off though we intended
            // to arm. Holding the lock makes "set flag + enable + verify" atomic
            // w.r.t. every other lock-event source.
                if (stopStaleAccTransition(
                        transitionGeneration, true, "door-lock timeout commit")) {
                    return;
                }
                synchronized (CameraDaemon.class) {
                // FAST-PATH EXIT: if a lock source already detected the lock and
                // armed us during the grace window (the common case — OTA reports
                // LOCKED within ~1s of ACC-off), this force-arm is a redundant
                // no-op. Exit quietly instead of logging the misleading
                // "TIMEOUT ... force-arming regardless of lock state" line, which
                // reads as "we never saw the lock" when in fact we armed instantly.
                // Checked inside the monitor so it's consistent with applyLockEvent.
                if (doorLockListenerArmed) {
                    log("LOCK GATE: already armed via lock detection — force-arm deadline is a no-op");
                    return;
                }
                if (!isAccTransitionCurrent(transitionGeneration, true)) {
                    log("LOCK GATE TIMEOUT: ACC turned ON before force-arm — not arming");
                    return;
                }
                // FALLBACK GUARD: only force-arm when lock state was never readable.
                // If a source delivered a definite reading, it wasn't LOCKED (else
                // doorLockListenerArmed would be true and we'd have exited above), so
                // it was UNLOCKED — and lock mode honors that. Staying disarmed here
                // is what prevents the arm-then-disarm flap against the unlock poll.
                if (sawValidLockReading) {
                    log("LOCK GATE TIMEOUT: lock state was readable and not locked "
                        + "(owner left it unlocked) — lock mode stays disarmed; "
                        + "use power arm mode to arm an unlocked car");
                    return;
                }
                // SCHEDULE re-check: the upstream ACC-OFF gate (see :3582) only
                // starts this 60s timer when we parked INSIDE the schedule window,
                // but the window can END during the grace period. enableSurveillance()
                // re-checks safe-zone but NOT schedule, so without this guard a
                // force-arm could arm just outside the window (the 5-min periodic
                // checker would stop it on its next tick, but that's a delayed
                // correction). Mirror the upstream gate exactly: if a schedule is
                // enabled and we're now outside it, skip the force-arm and leave the
                // intent flag set so the periodic checker arms when the window opens.
                try {
                    com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                        com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
                    if (schedule != null && schedule.isEnabled() && !schedule.isActiveNow()) {
                        log("LOCK GATE TIMEOUT: outside schedule window ("
                            + schedule.getSummary() + ") — not force-arming; "
                            + "periodic checker will arm when the window opens");
                        surveillanceEnabled = true;  // keep intent for the periodic checker
                        return;
                    }
                } catch (Exception e) {
                    log("LOCK GATE TIMEOUT: schedule check error (proceeding): " + e.getMessage());
                }
                log("LOCK GATE TIMEOUT: " + (DOOR_LOCK_ARM_TIMEOUT_MS / 1000)
                    + "s elapsed and lock state never readable on this trim — "
                    + "force-arming surveillance (lock-mode fallback)");
                if (stopStaleAccTransition(
                        transitionGeneration, true, "door-lock force-arm")) {
                    return;
                }
                boolean enabled = enableSurveillanceForAccGeneration(
                    transitionGeneration, "door-lock timeout");
                doorLockListenerArmed = enabled;
                // Consistency guard: enableSurveillance() can decline to start the
                // pipeline for two reasons — (1) ACC turned ON in the gap before its
                // internal re-check (see :2787), or (2) we're parked in a safe zone
                // and it suppressed the start (see :2815, sets safeZoneSuppressed).
                // In either case the pipeline never armed, so leaving
                // doorLockListenerArmed=true would be a lie (flag says "armed" but
                // surveillance isn't running) and a later unlock would call a
                // spurious disableSurveillance(). Revert the flag if the pipeline did
                // not actually start.
                if (!isAccTransitionCurrent(transitionGeneration, true)) {
                    log("LOCK GATE TIMEOUT: ACC turned ON during force-arm — reverting doorLockListenerArmed");
                    doorLockListenerArmed = false;
                } else if (safeZoneSuppressed
                        || gpuPipeline == null || !gpuPipeline.isRunning()) {
                    log("LOCK GATE TIMEOUT: pipeline not running after force-arm "
                        + "(safeZone=" + safeZoneSuppressed + ") — reverting doorLockListenerArmed");
                    doorLockListenerArmed = false;
                }
                }
            } finally {
                boolean ownedSlot = false;
                synchronized (doorLockTimeoutLock) {
                    if (doorLockTimeoutThread == Thread.currentThread()) {
                        doorLockTimeoutThread = null;
                        doorLockTimeoutGeneration = 0L;
                        ownedSlot = true;
                    }
                }
                long currentOffGeneration =
                    captureCurrentAccOffGenerationForSurveillance();
                if (ownedSlot && running.get()
                        && currentOffGeneration >= 0L
                        && currentOffGeneration
                            != transitionGeneration) {
                    requestManagedAccWorkerRecovery(
                        "stale door-lock timeout exited");
                }
            }
        }, "DoorLockTimeout");
        timeoutThread.setDaemon(true);
        Thread previousTimeout;
        synchronized (doorLockTimeoutLock) {
            previousTimeout = doorLockTimeoutThread;
            if (previousTimeout != null
                    && previousTimeout.isAlive()
                    && doorLockTimeoutGeneration
                        == transitionGeneration) {
                startAccOnDisarmWatchdog(
                    transitionGeneration);
                return;
            }
        }
        if (previousTimeout != null
                && previousTimeout.isAlive()
                && !interruptAndJoinManagedThread(
                    previousTimeout, "door-lock timeout")) {
            requestManagedAccWorkerRecovery(
                "stuck door-lock timeout");
            return;
        }
        synchronized (doorLockTimeoutLock) {
            Thread current = doorLockTimeoutThread;
            if (current != null && current.isAlive()
                    && current != previousTimeout) {
                return;
            }
            doorLockTimeoutThread = timeoutThread;
            doorLockTimeoutGeneration = transitionGeneration;
        }
        try {
            timeoutThread.start();
        } catch (Throwable t) {
            synchronized (doorLockTimeoutLock) {
                if (doorLockTimeoutThread == timeoutThread) {
                    doorLockTimeoutThread = null;
                    doorLockTimeoutGeneration = 0L;
                }
            }
            log("LOCK GATE TIMEOUT: worker could not start: " + t.getMessage());
            requestManagedAccWorkerRecovery(
                "door-lock timeout start failure");
        }

        // Reverse fallback: ACC-ON disarm watchdog. Periodically queries
        // hardware ACC state directly. If ACC turned ON without any IPC
        // event reaching us (rare but seen during AccSentryDaemon restart
        // races), this thread force-disables surveillance.
        startAccOnDisarmWatchdog(transitionGeneration);
    }

    /**
     * Single arm/disarm path. Idempotent: redundant calls in the same state
     * are no-ops. Every lock-event source flows through here.
     */
    private static void applyLockEvent(
            boolean locked, String source, long transitionGeneration) {
        if (stopStaleAccTransition(
                transitionGeneration, true, "door-lock event " + source)) {
            return;
        }
        if (!validateAccOffForDeferredEffect(
                transitionGeneration, "door-lock-" + source)) {
            return;
        }
        synchronized (CameraDaemon.class) {
                if (!isAccTransitionCurrent(transitionGeneration, true)) {
                    log("LOCK GATE [" + source + "]: "
                        + (locked ? "LOCKED" : "UNLOCKED")
                        + " but ACC is ON — ignoring");
                    return;
                }
                // A definite reading arrived (this method is only ever called with a real
                // LOCKED/UNLOCKED value — INVALID/unknown reads are filtered upstream in
                // the poll and cloud sources). Record it so the 60s force-arm fallback
                // knows lock state is READABLE on this trim and must NOT override a
                // genuine unlock.
                sawValidLockReading = true;
                // Publish the central-lock state to the AUTOMATION engine — this is the single
                // funnel every definite lock reading (SDK OTA poll + cloud) converges through, so
                // a "when the car locks/unlocks" trigger and a "only while locked" condition both
                // get every real edge here. Done BEFORE the arm-gate early-returns below so a lock
                // automation fires regardless of whether surveillance was already armed. Level-
                // triggered + deduped in Automations.update, so a repeated same-state read no-ops.
                try {
                    com.overdrive.app.automation.AutomationQueue.runLatestStatePublication(
                        com.overdrive.app.automation.AutomationQueue.LatestStateStream.LOCK,
                        () -> com.overdrive.app.automation.AutomationQueue
                            .runLatestStateMutation(
                                com.overdrive.app.automation.AutomationQueue
                                    .LatestStateStream.LOCK,
                                () -> com.overdrive.app.automation.Automations.update(
                                    com.overdrive.app.automation.condition.BydEvent.LOCK,
                                    locked ? "locked" : "unlocked")));
                } catch (Throwable t) {
                    log("lock automation publish failed: " + t.getMessage());
                }
                if (locked) {
                    if (doorLockListenerArmed) return;
                    log("LOCK GATE [" + source + "]: LOCKED — arming surveillance");
                    boolean enabled = enableSurveillanceForAccGeneration(
                        transitionGeneration, "door-lock event/" + source);
                    doorLockListenerArmed = enabled;
                    if (!enabled) {
                        requestAccTransitionReconciliation(false);
                    }
                } else {
                    if (!doorLockListenerArmed) return;
                    log("LOCK GATE [" + source
                        + "]: UNLOCKED — disarming surveillance (owner returning)");
                    disableSurveillance();
                    doorLockListenerArmed = false;
                    if (stopStaleAccTransition(
                            transitionGeneration,
                            true,
                            "door-unlock surveillance disable")) {
                        return;
                    }
                }
            }
    }

    /** Cloud (MQTT) lock-event source. Always attached — runs in parallel
     *  with the device-SDK source. No primary/fallback toggle. */
    private static void attachCloudLockSource(long transitionGeneration) {
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider cloudProvider =
                com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            if (cloudLockListener != null) {
                cloudProvider.removeLockStateListener(cloudLockListener);
            }
            cloudLockListener = (locked, timestampMs) ->
                applyLockEvent(locked, "cloud", transitionGeneration);
            cloudProvider.addLockStateListener(cloudLockListener);
            log("LOCK GATE: Cloud lock listener attached");
        } catch (Exception e) {
            log("LOCK GATE: Cloud listener attach failed: " + e.getMessage());
        }
    }

    /** Device-SDK lock-event source via BydDataCollector's typed listener.
     *  Always attached — runs in parallel with the cloud source. */
    /**
     * The legacy {@code BYDAutoDoorLockDevice} listener path was removed —
     * its {@code onDoorLockStatusChanged} callback never fired on any
     * firmware in the field, and the polled {@code getDoorLockStatus(area)}
     * returned INVALID. The OTA device fast-path (5s poll via
     * {@link #readDoorLockStatus}) replaces it. This stub is kept for
     * symmetry with the cloud / poll attachers — call sites unchanged.
     */
    private static void attachDeviceLockSource() {
        // Intentional no-op. See javadoc above.
    }

    /** @return true=locked, false=unlocked, null=unknown/cloud unavailable. */
    private static Boolean currentCloudLockState() {
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider cloudProvider =
                com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            if (!cloudProvider.isLockStateFresh()) return null;
            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = cloudProvider.getSnapshot();
            if (cs == null) return null;
            if (cs.isAllLocked()) return true;
            if (cs.isAnyUnlocked()) return false;
        } catch (Exception ignored) {}
        return null;
    }

    /** @return true=locked, false=unlocked, null=unknown/OTA unavailable. */
    private static Boolean currentDeviceLockState() {
        int s = readDoorLockStatus();
        if (s == DOOR_STATE_LOCK) return true;
        if (s == DOOR_STATE_UNLOCK) return false;
        return null;
    }

    /**
     * ACC-ON disarm watchdog. While surveillance is active during ACC OFF,
     * polls hardware ACC state every few seconds. If hardware says ACC ON
     * but the normal IPC was missed, admits that observation through the same
     * generation-linearized ACC path as an IPC edge.
     */
    private static void startAccOnDisarmWatchdog(long transitionGeneration) {
        Thread previous;
        synchronized (accOnDisarmWatchdogLock) {
            previous = accOnDisarmWatchdog;
            if (previous != null && previous.isAlive()
                    && accOnDisarmWatchdogGeneration
                        == transitionGeneration) {
                return;
            }
        }
        if (previous != null && previous.isAlive()
                && !interruptAndJoinManagedThread(
                    previous, "ACC-ON disarm watchdog")) {
            log("ACC-ON disarm watchdog replacement deferred; old worker is still alive");
            requestManagedAccWorkerRecovery(
                "stuck ACC-ON disarm watchdog");
            return;
        }

        final Thread worker;
        try {
            worker = new Thread(() -> {
                try {
                    log("ACC-ON disarm watchdog started");
                    while (true) {
                        try {
                            Thread.sleep(
                                ACC_ON_DISARM_POLL_INTERVAL_MS);
                        } catch (InterruptedException interrupted) {
                            return;
                        }
                        if (!isAccTransitionCurrent(
                                transitionGeneration, true)) {
                            return;
                        }
                        if (sharedAppContext == null) {
                            continue;
                        }

                        long observationGeneration =
                            captureAccObservationGeneration();
                        if (observationGeneration
                                != transitionGeneration) {
                            return;
                        }
                        AccProbeResult probe =
                            probeAccStateWithBackoff(
                                "disarm-watchdog");
                        if (!isAccObservationCurrent(
                                observationGeneration)) {
                            return;
                        }
                        if (!probe.accIsOff && probe.trustworthy) {
                            log("ACC-ON disarm watchdog: clean hardware ON observation");
                            onObservedAccStateChanged(
                                false,
                                observationGeneration,
                                "disarm-watchdog");
                            return;
                        }
                        if (!probe.accIsOff && !probe.trustworthy
                                && surveillanceEnabled) {
                            log("ACC-ON disarm watchdog: ignoring untrustworthy ON default");
                        }
                    }
                } finally {
                    boolean ownedSlot = false;
                    synchronized (accOnDisarmWatchdogLock) {
                        if (accOnDisarmWatchdog
                                == Thread.currentThread()) {
                            accOnDisarmWatchdog = null;
                            accOnDisarmWatchdogGeneration = 0L;
                            ownedSlot = true;
                        }
                    }
                    if (ownedSlot && running.get()
                            && isAccTransitionCurrent(
                                transitionGeneration, true)) {
                        requestManagedAccWorkerRecovery(
                            "ACC-ON disarm watchdog exited");
                    }
                }
            }, "AccOnDisarmWatchdog");
            worker.setDaemon(true);
        } catch (Throwable creationFailure) {
            requestManagedAccWorkerRecovery(
                "ACC-ON disarm watchdog creation failure");
            return;
        }

        synchronized (accOnDisarmWatchdogLock) {
            Thread current = accOnDisarmWatchdog;
            if (current != null && current.isAlive()
                    && current != previous) {
                return;
            }
            accOnDisarmWatchdog = worker;
            accOnDisarmWatchdogGeneration =
                transitionGeneration;
        }
        try {
            worker.start();
        } catch (Throwable startFailure) {
            synchronized (accOnDisarmWatchdogLock) {
                if (accOnDisarmWatchdog == worker) {
                    accOnDisarmWatchdog = null;
                    accOnDisarmWatchdogGeneration = 0L;
                }
            }
            requestManagedAccWorkerRecovery(
                "ACC-ON disarm watchdog start failure");
        }
    }

    private static void stopAccOnDisarmWatchdog() {
        Thread worker;
        synchronized (accOnDisarmWatchdogLock) {
            worker = accOnDisarmWatchdog;
        }
        if (interruptAndJoinManagedThread(
                worker, "ACC-ON disarm watchdog")) {
            synchronized (accOnDisarmWatchdogLock) {
                if (accOnDisarmWatchdog == worker) {
                    accOnDisarmWatchdog = null;
                    accOnDisarmWatchdogGeneration = 0L;
                }
            }
        }
    }

    // PRIVATE monitor for the probe-edge bookkeeping below.
    //
    // MUST NOT be the CameraDaemon class monitor (audit R3): applyLockEvent and
    // the door-lock force-arm block hold `CameraDaemon.class` across
    // enableSurveillance() -> gpuPipeline.start(), which can reach
    // RecordingModeManager and acquire its lifecycleSerializer. Meanwhile a
    // setMode() caller already holds lifecycleSerializer when it probes ACC and
    // reaches dispatchProbedAccEdge. Making that method `static synchronized`
    // therefore created a classic opposite-order deadlock
    // (lifecycleSerializer -> CameraDaemon.class vs CameraDaemon.class ->
    // lifecycleSerializer) that would freeze arming outright. A dedicated lock
    // that is never held across any call into RMM or the pipeline removes the
    // cycle entirely.
    private static final Object probeEdgeLock = new Object();

    // Serialized ownership for probe-driven ACC dispatch. A latest-request mailbox absorbs
    // observations while one worker is active; no replacement is started until that worker exits.
    private static final java.util.concurrent.atomic.AtomicLong probeEdgeDispatchOwner =
        new java.util.concurrent.atomic.AtomicLong(0L);

    // Monotonic source of ownership generations. Never reset.
    private static final java.util.concurrent.atomic.AtomicLong probeEdgeDispatchGen =
        new java.util.concurrent.atomic.AtomicLong(0L);
    private static final long PROBE_EDGE_DISPATCH_LEASE_MS = 20_000L;
    private static long probeEdgeDispatchDeadlineNanos;
    private static long probeEdgeDispatchObservationGeneration;
    private static Boolean probeEdgeDispatchAccIsOff;
    private static Thread probeEdgeDispatchThread;
    private static final long PROBE_EDGE_REVOCATION_GRACE_MS = 2_000L;
    private static boolean probeEdgeDispatchRevoked;
    private static long probeEdgeDispatchRevocationDeadlineNanos;
    private static ProbeEdgeDispatchRequest pendingProbeEdgeDispatch;
    private static boolean probeEdgeRetryPosted;

    private static final class ProbeEdgeDispatchRequest {
        final boolean accIsOff;
        final String reason;
        final long observationGeneration;
        final com.overdrive.app.recording.RecordingModeManager managerOwner;
        final com.overdrive.app.surveillance.GpuSurveillancePipeline pipelineOwner;
        final boolean handoffPromised;

        ProbeEdgeDispatchRequest(
                boolean accIsOff, String reason,
                long observationGeneration,
                com.overdrive.app.recording.RecordingModeManager managerOwner,
                com.overdrive.app.surveillance.GpuSurveillancePipeline pipelineOwner,
                boolean handoffPromised) {
            this.accIsOff = accIsOff;
            this.reason = reason;
            this.observationGeneration = observationGeneration;
            this.managerOwner = managerOwner;
            this.pipelineOwner = pipelineOwner;
            this.handoffPromised = handoffPromised;
        }
    }

    // How many CONSECUTIVE probe observations of "the ACC chain's state disagrees
    // with hardware" are required before dispatching from the path where
    // AccMonitor already AGREES with hardware. See dispatchProbedAccEdge.
    private static final int PROBE_EDGE_CONFIRM_TICKS = 2;

    // Minimum ELAPSED time the disagreement must persist, in addition to the tick
    // count (audit R3). Measured with System.nanoTime(), NOT currentTimeMillis:
    // this head unit syncs its clock from GPS, so wall-clock STEPS are real here —
    // a forward jump would satisfy the floor prematurely (weakening the guard) and
    // a backward jump would stall it. nanoTime is monotonic, so neither can happen.
    //
    // WHY BOTH: the tick count alone is not a time guarantee. Probes are NOT only
    // the 30s resync ticker — resyncFromHardware is also driven by the SD/USB mount
    // watchdog (15s cadence), storage-type switches, wipe-media, pipeline storage
    // retries, recorder writer-aborts and warmup-retriggers, and every one of those
    // calls queryAccStateFromHardware. A burst from those sources could satisfy
    // "2 consecutive observations" within a couple of seconds — still inside the
    // window where a genuine in-flight AccSentry IPC has passed onAccStateChanged's
    // dedup read but not yet written the latch — re-admitting the duplicate-chain
    // defect the confirm gate exists to prevent. Requiring real elapsed time makes
    // the guard independent of probe cadence. 25s sits just under the 30s ticker
    // period so a normal no-IPC park still arms on its second tick.
    private static final long PROBE_EDGE_CONFIRM_MIN_MS = 25_000L;

    // Consecutive observations that the chain is behind hardware, when the first
    // of them was seen (System.nanoTime; 0 = unset), and for which direction. All
    // reset together whenever the chain catches up or the direction flips.
    private static int probeEdgeBehindTicks = 0;
    private static long probeEdgeBehindFirstNanos = 0L;
    private static Boolean probeEdgeBehindFor = null;

    // Backoff for repeated probe-driven dispatches of the SAME edge. The ACC
    // chain deliberately nulls lastDispatchedAccIsOff when arming fails so the
    // next signal retries; with a 30s probe that would otherwise re-run the full
    // ACC-OFF prologue (a fresh acc_events row, trip-finalize attempt, and a
    // ~15s ensureSdCardMounted) every 30s forever on a structurally broken
    // pipeline (audit R2 defect #3). Escalate the spacing instead.
    private static final long[] PROBE_EDGE_RETRY_BACKOFF_MS = {
        0L, 60_000L, 300_000L, 900_000L, 1_800_000L
    };
    private static int probeEdgeRetryCount = 0;
    private static Boolean probeEdgeRetryFor = null;
    private static long probeEdgeRetryNextAllowedMs = 0L;

    /**
     * Dispatch a full ACC-edge chain that was discovered by a HARDWARE PROBE
     * rather than by an AccSentryDaemon IPC.
     *
     * <p>WHY THIS EXISTS (observed 2026-07-28, log_X7RYXG6B): the sentry-arming
     * chain lives entirely inside {@link #onAccStateChanged} — the "Vehicle ON
     * only" gate, safe-zone / schedule gates, {@code startSentryPipeline}, the
     * arm-mode branch (power vs door-lock gate), and the schedule checker. The
     * ONLY producers of that call are AccSentryDaemon's IPC
     * (SurveillanceIpcServer {@code accOff} / TcpCommandServer {@code setAccState})
     * and the boot {@code RECOVERY:} probe.
     *
     * <p>When AccSentryDaemon stops delivering the ACC-OFF IPC (dead, wedged,
     * killed mid-park, or AP asleep with "Keep USB powered" off) on an
     * ALREADY-RUNNING daemon, the only component that still notices the
     * transition is {@link com.overdrive.app.recording.RecordingModeManager}'s
     * 30s hardware-probe resync. That path used to write straight to
     * {@code AccMonitor.setAccState(false)}, which updates the static + fires
     * {@code notifyAccEdge} (cluster/mirror teardown) but NEVER reaches the
     * arming chain. Net effect: the car parks, RMM tears the pipeline down for
     * mode=NONE, and surveillance never comes back — a silent 70-minute
     * unmonitored park in the referenced log, with manual
     * {@code POST /api/surveillance/enable} working perfectly the whole time
     * (proving config/gates were fine and only the trigger was dead).
     *
     * <p>WHY NOT hook {@code AccMonitor.notifyAccEdge} instead: the IPC path
     * ({@link #onAccStateChanged}) itself calls {@code AccMonitor.setAccState},
     * so dispatching from inside the edge funnel would re-enter the whole
     * ACC chain a second time for every normal IPC — duplicate recordAccEvent
     * rows, duplicate trip finalize, duplicate arm. The probe write-through is
     * the only caller that is MISSING the chain, so the hook belongs there.
     *
     * <p>THREADING: always dispatched on a fresh thread. The caller is RMM's
     * {@code queryAccStateFromHardware}, which runs both on the resync ticker
     * and inside {@code setMode} while holding {@code lifecycleSerializer};
     * {@link #onAccStateChanged} calls back into
     * {@code recordingModeManager.onAccStateChanged}, which takes that same
     * lock and then performs camera/encoder I/O plus a ~4s AVC warmup. A
     * synchronous call would therefore self-deadlock or pin RMM's lifecycle
     * lock across seconds of I/O.
     *
     * <p>RETRYABILITY — the load-bearing property (audit R1, 2026-07-29). This
     * method is called AFTER the caller has already written the new state to
     * {@code AccMonitor}, so the caller's own "does hardware disagree with
     * AccMonitor?" test will NOT fire again on the next tick. Therefore this
     * method must NOT gate on AccMonitor agreement, and a skipped dispatch must
     * not be silently lost. It gates on {@link #lastDispatchedAccIsOff} — the
     * daemon's record of what the ACC CHAIN has actually processed — so any tick
     * that finds the chain behind hardware re-dispatches, however many ticks
     * later that is. Callers must therefore invoke this on EVERY probe with a
     * definitive reading, not only on write-through ticks
     * (see {@code RecordingModeManager.queryAccStateFromHardware}).
     *
     * <p>Without that, an edge dropped by the in-flight CAS (the chain can hold
     * it for tens of seconds: trip finalize, {@code ensureSdCardMounted} ~15s,
     * pipeline start) was lost permanently — and because
     * {@code lastDispatchedAccIsOff} had already latched the opposite state, the
     * IPC dedup at the top of {@link #onAccStateChanged} then suppressed every
     * later edge too: surveillance could never arm again for the daemon's
     * lifetime. That is strictly worse than the bug this fix targets.
     *
     * <p>Because it is therefore called on EVERY definitive probe reading, three
     * guards keep the every-tick cadence from doing harm (added per audits R2/R3):
     * a confirm gate requiring the disagreement to survive both
     * {@link #PROBE_EDGE_CONFIRM_TICKS} observations and
     * {@link #PROBE_EDGE_CONFIRM_MIN_MS} of wall-clock, so a probe — or a burst of
     * them from the off-cadence callers — landing inside a genuine in-flight IPC's
     * dedup window cannot spawn a duplicate chain; a
     * revocable, two-slot generation-token gate so a blocked dispatch cannot multiply
     * effect threads or suppress a newer observation; and {@link #PROBE_EDGE_RETRY_BACKOFF_MS}, so a chain that
     * keeps failing to arm is retried with escalating spacing instead of re-running
     * the heavy ACC-OFF prologue every 30s forever. On a healthy system the whole
     * method is a silent two-volatile-read no-op.
     *
     * <p>LOCKING: the confirm/backoff counters are mutated per call and probes can
     * arrive concurrently (resync ticker + HTTP {@code setMode}), so they are
     * guarded by the dedicated {@link #probeEdgeLock}. That lock is deliberately
     * NOT the {@code CameraDaemon.class} monitor — see the field comment for the
     * deadlock that choice created. Nothing inside the lock calls into
     * RecordingModeManager or the pipeline (the ACC chain runs on the spawned
     * thread), so it is a leaf lock and cannot participate in a cycle.
     *
     * @return {@code true} iff a dispatch thread was actually started for THIS
     *         edge — meaning the caller can rely on
     *         {@code recordingModeManager.onAccStateChanged} being driven by the
     *         chain and must NOT drive it again itself (see
     *         {@code RecordingModeManager.resyncFromHardware}, where a duplicate
     *         local teardown could land after the chain armed surveillance and
     *         tear the pipeline back down).
     */
    public static boolean dispatchProbedAccEdge(boolean accIsOff, String reason) {
        // Don't resurrect lifecycle state after teardown has begun. shutdownInternal
        // / parkTerminate stop the pipeline and shut the RMM down; a dispatch landing
        // afterwards would re-run the arming chain (pipeline.start, schedule checker,
        // disarm watchdog, unlock poll) against torn-down state. Racy by a hair
        // against a concurrent shutdownInternal (bounded by the killProcess that
        // follows), matching the pre-existing pattern on this path.
        if (!running.get()) return false;
        final long observationGeneration = captureAccObservationGeneration();
        synchronized (probeEdgeLock) {
            return dispatchProbedAccEdgeLocked(
                accIsOff, reason, observationGeneration);
        }
    }

    /** Body of {@link #dispatchProbedAccEdge}; caller holds {@link #probeEdgeLock}.
     *  Never calls into RecordingModeManager or the pipeline — the ACC chain runs
     *  on the thread this spawns — so the lock stays a leaf. */
    private static boolean dispatchProbedAccEdgeLocked(
            boolean accIsOff, String reason, long observationGeneration) {
        if (!isAccObservationCurrent(observationGeneration)) {
            return false;
        }
        // The ACC CHAIN's own record — NOT AccMonitor. If the chain has already
        // processed this state, there is nothing to drive and the caller should do
        // its own local sync (return false). If it hasn't, we may dispatch — even
        // if AccMonitor was reconciled many ticks ago, which is exactly the
        // dropped-edge recovery path this method exists for.
        Boolean last = lastDispatchedAccIsOff;
        if (last != null && last.booleanValue() == accIsOff) {
            // Chain agrees with hardware — clear the confirm/backoff trackers so a
            // future disagreement starts from a clean slate.
            probeEdgeBehindTicks = 0;
            probeEdgeBehindFirstNanos = 0L;
            probeEdgeBehindFor = null;
            probeEdgeRetryCount = 0;
            probeEdgeRetryFor = null;
            probeEdgeRetryNextAllowedMs = 0L;
            return false;
        }

        long nowMs = System.currentTimeMillis();

        // CONFIRM GATE (audit R2 defect #1; time floor added per audit R3). We are
        // called on EVERY definitive probe reading, which is what makes a dropped
        // edge recoverable — but it also means we fire while a GENUINE AccSentry IPC
        // is mid-flight through onAccStateChanged: that method reads its dedup latch
        // at entry and only writes it after recordAccEvent + trip finalize, so a
        // probe landing in that window would spawn a second full chain and write a
        // duplicate acc_events "OFF" row.
        //
        // The disagreement must therefore persist BOTH across
        // PROBE_EDGE_CONFIRM_TICKS observations AND for PROBE_EDGE_CONFIRM_MIN_MS
        // of wall-clock. The tick count alone is NOT a time guarantee: probes also
        // arrive from the 15s SD/USB mount watchdog, storage retries, writer-aborts
        // and warmup-retriggers, so a burst could otherwise satisfy the gate within
        // seconds — still inside the IPC's pre-latch window. By the time both
        // conditions hold, a real in-flight IPC has long since latched, while a
        // genuinely missed edge (no IPC coming at all — the failure this fix
        // targets) still arms on the next 30s tick. Direction-tagged so an ACC flap
        // restarts the count.
        // nanoTime can legitimately be 0 or negative, so 0 is not a usable
        // "unset" sentinel on its own — the direction tag is what tells us
        // whether a run is in progress, and it is always set together with the
        // stamp below. Offset by 1 so the stored value is never 0 while a run is
        // active, keeping the field's own 0 == unset invariant intact.
        long nowNanos = System.nanoTime() | 1L;
        if (probeEdgeBehindFor == null || probeEdgeBehindFor.booleanValue() != accIsOff) {
            probeEdgeBehindFor = accIsOff ? Boolean.TRUE : Boolean.FALSE;
            probeEdgeBehindTicks = 1;
            probeEdgeBehindFirstNanos = nowNanos;
        } else {
            probeEdgeBehindTicks++;
            if (probeEdgeBehindFirstNanos == 0L) probeEdgeBehindFirstNanos = nowNanos;
        }
        // Subtraction is overflow-safe across nanoTime wrap (the JLS-sanctioned
        // comparison form), and monotonic so no clock step can shortcut it.
        if (probeEdgeBehindTicks < PROBE_EDGE_CONFIRM_TICKS
                || (nowNanos - probeEdgeBehindFirstNanos)
                    < PROBE_EDGE_CONFIRM_MIN_MS * 1_000_000L) {
            // Quiet: on a healthy system this is the normal overlap with an IPC
            // that is already being processed.
            return false;
        }

        // BACKOFF (audit R2 defect #3). onAccStateChanged nulls its dedup latch
        // when arming fails, so without this every 30s probe would re-run the full
        // ACC-OFF prologue — a fresh acc_events row, another trip-finalize, and a
        // ~15s ensureSdCardMounted — forever on a structurally broken pipeline.
        if (probeEdgeRetryFor == null || probeEdgeRetryFor.booleanValue() != accIsOff) {
            probeEdgeRetryFor = accIsOff ? Boolean.TRUE : Boolean.FALSE;
            probeEdgeRetryCount = 0;
            probeEdgeRetryNextAllowedMs = 0L;
        } else if (probeEdgeRetryNextAllowedMs > 0L && nowMs < probeEdgeRetryNextAllowedMs) {
            log("ACC probe edge (" + reason + "): ACC " + (accIsOff ? "OFF" : "ON")
                + " chain retry deferred — " + (probeEdgeRetryNextAllowedMs - nowMs)
                + "ms left in backoff (attempt " + probeEdgeRetryCount + ")");
            return false;
        }

        // If the published manager/pipeline pair isn't intact, onAccStateChanged only queues
        // pendingAccOff/On (or can race manager teardown) and returns WITHOUT safely reaching
        // recordingModeManager.onAccStateChanged — so
        // we must not claim a handoff the chain won't honour, or the caller would
        // skip its own local ACC sync for that tick (audit R3). Dispatch anyway
        // (the queue + post-init drain is the whole point) but report no handoff.
        com.overdrive.app.recording.RecordingModeManager managerOwner =
            recordingModeManager;
        com.overdrive.app.surveillance.GpuSurveillancePipeline pipelineOwner =
            gpuPipeline;
        boolean canDriveRmm = managerOwner != null
            && pipelineOwner != null
            && recordingModeManagerPipelineOwner == pipelineOwner
            && running.get();

        boolean spawned = startProbeEdgeDispatchLocked(
            new ProbeEdgeDispatchRequest(
                accIsOff, reason, observationGeneration,
                managerOwner, pipelineOwner, canDriveRmm));
        // Handoff is promised ONLY when a dispatch is running AND the chain can
        // actually reach RMM (see canDriveRmm above).
        return spawned && canDriveRmm;
    }

    /** Caller holds {@link #probeEdgeLock}. */
    private static boolean startProbeEdgeDispatchLocked(
            ProbeEdgeDispatchRequest request) {
        long owner = probeEdgeDispatchOwner.get();
        if (owner != 0L) {
            Thread active = probeEdgeDispatchThread;
            boolean sameObservation =
                probeEdgeDispatchObservationGeneration
                        == request.observationGeneration
                && probeEdgeDispatchAccIsOff != null
                && probeEdgeDispatchAccIsOff.booleanValue()
                        == request.accIsOff;
            boolean ownerSuperseded = !sameObservation
                || leaseExpired(probeEdgeDispatchDeadlineNanos);

            // AUDIT FIX (daemon-restart loop, part 2 — redundant lease
            // supersession): a dispatch over its lease but (a) driving the
            // SAME observation and (b) alive in the storage phase is neither
            // wedged nor stale — it IS the requested work, waiting on a slow
            // mount. Cancelling it can never land (monitor entry is not
            // interruptible; interrupt() only sets a flag) and re-dispatching
            // would queue an identical chain behind the same lock. Re-arm
            // the lease and keep the owner.
            if (ownerSuperseded && sameObservation
                    && isThreadInStoragePhase(active)) {
                probeEdgeDispatchDeadlineNanos = System.nanoTime()
                    + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                        PROBE_EDGE_DISPATCH_LEASE_MS);
                log("ACC probe edge (" + request.reason
                    + "): dispatch over lease but storage-bound on the same"
                    + " observation — lease re-armed, not cancelling");
                ownerSuperseded = false;
            }

            if (!ownerSuperseded) {
                log("ACC probe edge (" + request.reason
                    + "): matching dispatch already owns this observation");
                if (probeEdgeBehindTicks > 0) {
                    probeEdgeBehindTicks--;
                }
                return false;
            }
            pendingProbeEdgeDispatch = request;

            if (!probeEdgeDispatchRevoked) {
                probeEdgeDispatchRevoked = true;
                probeEdgeDispatchRevocationDeadlineNanos =
                    System.nanoTime()
                        + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                            PROBE_EDGE_REVOCATION_GRACE_MS);
                if (active != null) active.interrupt();
                log("ACC probe edge (" + request.reason
                    + "): requested stale dispatch cancellation; "
                    + "latest observation retained");
            } else if (leaseExpired(
                    probeEdgeDispatchRevocationDeadlineNanos)) {
                // AUDIT FIX (daemon-restart loop, part 1 — the remedy was
                // wildly disproportionate to the fault): process recovery
                // exists for a dispatch wedged in a HAL binder query — the
                // fault a restart actually fixes. A dispatch blocked on
                // STORAGE (mount lock, slow SD) is not that: restarting
                // doesn't unwedge the card, drops 21-63s of recording, and
                // the post-restart boot work worsens the storage pressure
                // that caused the stall (the field log showed this exact
                // loop 10× in 68 minutes). Extend the grace and keep
                // waiting — the storage internals are individually bounded,
                // so the thread WILL exit; the pending request + retry
                // supervisor then dispatch the latest observation.
                if (isThreadInStoragePhase(active)) {
                    probeEdgeDispatchRevocationDeadlineNanos =
                        System.nanoTime()
                            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                                PROBE_EDGE_REVOCATION_GRACE_MS);
                    log("ACC probe edge (" + request.reason
                        + "): revoked dispatch is storage-bound — extending"
                        + " grace instead of process recovery (restart cannot"
                        + " fix slow storage)");
                } else {
                    requestHardwareQueryProcessRecovery(
                        "probe-edge dispatch ignored cancellation");
                }
            }
            scheduleProbeEdgeStartRetryLocked();
            return false;
        }

        final long token = probeEdgeDispatchGen.incrementAndGet();
        final Thread worker;
        try {
            worker = new Thread(() -> {
                try {
                    if (!running.get()
                            || !isAccObservationCurrent(
                                request.observationGeneration)) {
                        log("ACC probe edge (" + request.reason
                            + "): observation superseded before dispatch");
                        return;
                    }
                    if (request.handoffPromised
                            && !isProbeHandoffOwnerCurrent(request)) {
                        // The daemon still owns this accepted hardware edge. Drive it through
                        // the current lifecycle; applyAccTransitionEffects will queue the edge
                        // if the replacement manager/pipeline is not ready yet.
                        log("ACC probe edge (" + request.reason
                            + "): manager/pipeline ownership changed; "
                            + "redirecting dispatch to latest lifecycle");
                    }
                    log("ACC probe edge (" + request.reason
                        + "): dispatching full ACC "
                        + (request.accIsOff ? "OFF" : "ON")
                        + " chain from hardware observation");
                    onObservedAccStateChanged(
                        request.accIsOff,
                        request.observationGeneration,
                        "probe-edge/" + request.reason);
                } catch (Throwable failure) {
                    log("ACC probe edge dispatch failed ("
                        + request.reason + "): "
                        + failure.getMessage());
                } finally {
                    finishProbeEdgeDispatch(
                        token, Thread.currentThread());
                }
            }, "ProbedAccEdge");
            worker.setDaemon(true);
        } catch (Throwable creationFailure) {
            pendingProbeEdgeDispatch = request;
            scheduleProbeEdgeStartRetryLocked();
            log("ACC probe edge dispatch creation failed ("
                + request.reason + "): "
                + creationFailure.getMessage());
            return false;
        }

        probeEdgeDispatchOwner.set(token);
        probeEdgeDispatchDeadlineNanos = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                PROBE_EDGE_DISPATCH_LEASE_MS);
        probeEdgeDispatchObservationGeneration =
            request.observationGeneration;
        probeEdgeDispatchAccIsOff =
            Boolean.valueOf(request.accIsOff);
        probeEdgeDispatchThread = worker;
        probeEdgeDispatchRevoked = false;
        probeEdgeDispatchRevocationDeadlineNanos = 0L;
        pendingProbeEdgeDispatch = null;
        try {
            worker.start();
        } catch (Throwable startFailure) {
            if (probeEdgeDispatchOwner.compareAndSet(token, 0L)) {
                probeEdgeDispatchDeadlineNanos = 0L;
                probeEdgeDispatchObservationGeneration = 0L;
                probeEdgeDispatchAccIsOff = null;
                probeEdgeDispatchThread = null;
                probeEdgeDispatchRevoked = false;
                probeEdgeDispatchRevocationDeadlineNanos = 0L;
            }
            pendingProbeEdgeDispatch = request;
            scheduleProbeEdgeStartRetryLocked();
            log("ACC probe edge dispatch start failed ("
                + request.reason + "): "
                + startFailure.getMessage());
            return false;
        }

        int index = Math.min(
            probeEdgeRetryCount,
            PROBE_EDGE_RETRY_BACKOFF_MS.length - 1);
        probeEdgeRetryNextAllowedMs = System.currentTimeMillis()
            + PROBE_EDGE_RETRY_BACKOFF_MS[index];
        probeEdgeRetryCount++;
        probeEdgeBehindTicks = 0;
        probeEdgeBehindFirstNanos = 0L;
        probeEdgeBehindFor = null;
        return true;
    }

    private static boolean isProbeHandoffOwnerCurrent(
            ProbeEdgeDispatchRequest request) {
        return request.managerOwner != null
            && request.pipelineOwner != null
            && recordingModeManager == request.managerOwner
            && gpuPipeline == request.pipelineOwner
            && recordingModeManagerPipelineOwner == request.pipelineOwner;
    }

    private static void finishProbeEdgeDispatch(
            long token, Thread worker) {
        boolean compensate;
        synchronized (probeEdgeLock) {
            compensate = probeEdgeDispatchRevoked
                && probeEdgeDispatchThread == worker;
            if (probeEdgeDispatchOwner.compareAndSet(token, 0L)) {
                probeEdgeDispatchDeadlineNanos = 0L;
                probeEdgeDispatchObservationGeneration = 0L;
                probeEdgeDispatchAccIsOff = null;
                probeEdgeDispatchThread = null;
                probeEdgeDispatchRevoked = false;
                probeEdgeDispatchRevocationDeadlineNanos = 0L;
            }
        }
        if (compensate) {
            forceLatestAccStateReconciliation(
                "revoked probe-edge dispatch returned");
        }
        retryPendingProbeEdgeDispatch();
    }

    private static void retryPendingProbeEdgeDispatch() {
        synchronized (probeEdgeLock) {
            probeEdgeRetryPosted = false;
            ProbeEdgeDispatchRequest pending =
                pendingProbeEdgeDispatch;
            if (pending == null) {
                return;
            }
            if (!running.get()
                    || !isAccObservationCurrent(
                        pending.observationGeneration)) {
                pendingProbeEdgeDispatch = null;
                return;
            }
            startProbeEdgeDispatchLocked(pending);
        }
    }

    /** Caller holds {@link #probeEdgeLock}. */
    private static void scheduleProbeEdgeStartRetryLocked() {
        if (probeEdgeRetryPosted) return;
        try {
            Thread retry = new Thread(() -> {
                try {
                    Thread.sleep(ACC_RECONCILE_DELAYS_MS[0]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                retryPendingProbeEdgeDispatch();
            }, "ProbeEdgeDispatchSupervisor");
            retry.setDaemon(true);
            probeEdgeRetryPosted = true;
            retry.start();
        } catch (Throwable startFailure) {
            probeEdgeRetryPosted = false;
            requestHardwareQueryProcessRecovery(
                "probe-edge retry supervisor unavailable");
        }
    }


    /**
     * Read driver-door (LF) lock status from {@code BYDAutoOtaDevice}.
     *
     * <p>Uses {@code BYDAutoOtaDevice.getLFDoorLockState()} — the OTA device
     * caches the LF lock signal even when the BCM is asleep (ACC=OFF).
     * Empirically verified live-tracking on DiLink 3.0 with ~1.5s
     * transition latency.
     *
     * <p>The legacy {@code BYDAutoDoorLockDevice.getDoorLockStatus(area)} +
     * {@code getDoorLockState()} paths were removed: every BYD firmware in
     * the field returns INVALID for them to user UID — no observed
     * firmware actually delivered a working signal. Cloud is the fallback
     * (full 4-door state via {@code BydCloudDataProvider}); see
     * {@link #currentCloudLockState}.
     *
     * <p>For all 4 doors (RF/LR/RR), the OTA device exposes only LF on
     * DiLink 3.0 (verified 2026-06-03). Use the cloud snapshot path for
     * full per-door state.
     *
     * @return {@link #DOOR_STATE_INVALID}(0), {@link #DOOR_STATE_UNLOCK}(1),
     *         or {@link #DOOR_STATE_LOCK}(2).
     */
    private static int readDoorLockStatus() {
        Thread existing = DOOR_LOCK_QUERY_WORKER.get();
        if (existing != null && existing.isAlive()) {
            existing.interrupt();
            escalateStuckHardwareQueryIfExpired(
                DOOR_LOCK_QUERY_STUCK_DEADLINE_NANOS,
                "door-lock Binder query");
            return DOOR_STATE_INVALID;
        }
        DOOR_LOCK_QUERY_STUCK_DEADLINE_NANOS.set(0L);

        java.util.concurrent.atomic.AtomicInteger result =
            new java.util.concurrent.atomic.AtomicInteger(
                DOOR_STATE_INVALID);
        final Thread worker;
        try {
            worker = new Thread(() -> {
                try {
                    result.set(readDoorLockStatusUnbounded());
                } finally {
                    DOOR_LOCK_QUERY_WORKER.compareAndSet(
                        Thread.currentThread(), null);
                }
            }, "DoorLockHardwareQuery");
            worker.setDaemon(true);
        } catch (Throwable creationFailure) {
            log("Door-lock query worker creation failed: "
                + creationFailure.getMessage());
            return DOOR_STATE_INVALID;
        }
        if (!DOOR_LOCK_QUERY_WORKER.compareAndSet(existing, worker)) {
            return DOOR_STATE_INVALID;
        }
        try {
            worker.start();
        } catch (Throwable startFailure) {
            DOOR_LOCK_QUERY_WORKER.compareAndSet(worker, null);
            log("Door-lock query worker start failed: "
                + startFailure.getMessage());
            return DOOR_STATE_INVALID;
        }
        try {
            worker.join(DOOR_LOCK_QUERY_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            return DOOR_STATE_INVALID;
        }
        if (worker.isAlive()) {
            worker.interrupt();
            armHardwareQueryRecoveryDeadline(
                DOOR_LOCK_QUERY_STUCK_DEADLINE_NANOS);
            log("Door-lock hardware query exceeded "
                + DOOR_LOCK_QUERY_TIMEOUT_MS + "ms");
            return DOOR_STATE_INVALID;
        }
        DOOR_LOCK_QUERY_WORKER.compareAndSet(worker, null);
        DOOR_LOCK_QUERY_STUCK_DEADLINE_NANOS.set(0L);
        return result.get();
    }

    private static int readDoorLockStatusUnbounded() {
        if (sharedAppContext == null) return DOOR_STATE_INVALID;
        try {
            Object otaDevice = com.overdrive.app.byd.BydDeviceHelper.getDevice(
                "android.hardware.bydauto.ota.BYDAutoOtaDevice", sharedAppContext);
            if (otaDevice == null) return DOOR_STATE_INVALID;
            Object v = com.overdrive.app.byd.BydDeviceHelper.callGetter(
                otaDevice, "getLFDoorLockState");
            if (v instanceof Number) {
                int state = ((Number) v).intValue();
                if (state == DOOR_STATE_UNLOCK || state == DOOR_STATE_LOCK) {
                    return state;
                }
                // 0=INVALID or anything out-of-range falls through.
            }
        } catch (Throwable t) {
            // Trim doesn't expose getLFDoorLockState. Returned INVALID;
            // caller relies on cloud as the secondary source.
        }
        return DOOR_STATE_INVALID;
    }

    // BYDAutoDoorLockDevice listener path removed — it never fired on any
    // firmware in the field. OTA polling (readDoorLockStatus) is the
    // primary lock signal now; cloud is the secondary.


    /**
     * Continuous unlock polling thread — detects door lock/unlock transitions.
     * Uses getDoorLockStatus(1) for the driver's door.
     * Polls every 5s while ACC is off.
     */
    private static void startUnlockPollThread(long transitionGeneration) {
        Thread previous;
        synchronized (unlockPollThreadLock) {
            previous = unlockPollThread;
            if (previous != null && previous.isAlive()
                    && unlockPollThreadGeneration
                        == transitionGeneration) {
                return;
            }
        }
        if (previous != null && previous.isAlive()
                && !interruptAndJoinManagedThread(
                    previous, "unlock poller")) {
            requestManagedAccWorkerRecovery(
                "stuck unlock poller");
            return;
        }

        final Thread worker;
        try {
            worker = new Thread(() -> {
            try {
            log("Unlock poll thread started (5s polling getDoorLockStatus + REST fallback)");

            int restPollCounter = 0;
            // BUGFIX (surv disarms after ~1 event): debounce the UNLOCK direction.
            // A single transient/bluff DOOR_STATE_UNLOCK read from the OTA poll used
            // to disarm immediately (applyLockEvent(false) → disableSurveillance →
            // badge OFF, no re-arm until restart). Require 2 consecutive UNLOCK reads
            // before disarming. ARM (LOCK) stays eager — arming early is always safe,
            // and a missed lock is already backstopped by the force-arm timeout.
            int consecutiveUnlockReads = 0;

            while (isAccTransitionCurrent(transitionGeneration, true)) {
                try {
                    Thread.sleep(UNLOCK_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!isAccTransitionCurrent(transitionGeneration, true)) {
                    return;
                }

                // Source 1: OTA device poll (BYDAutoOtaDevice.getLFDoorLockState).
                // Works ACC=OFF with sub-second latency for the LF (driver) door —
                // verified live on DiLink 3.0. The legacy BYDAutoDoorLockDevice
                // path returned INVALID on every firmware we observed and was
                // removed.
                try {
                    int state = readDoorLockStatus();
                    if (state == DOOR_STATE_LOCK) {
                        consecutiveUnlockReads = 0;
                        applyLockEvent(true, "ota-poll", transitionGeneration);
                    } else if (state == DOOR_STATE_UNLOCK) {
                        consecutiveUnlockReads++;
                        if (consecutiveUnlockReads >= 2) {
                            applyLockEvent(false, "ota-poll", transitionGeneration);
                        } else {
                            log("LOCK GATE [ota-poll]: single UNLOCK read — "
                                + "debouncing (need 2 consecutive before disarm)");
                        }
                    } else {
                        // INVALID / out-of-range: not a real transition, don't let it
                        // reset the unlock streak either way (treat as no-signal).
                    }
                } catch (Exception e) {
                    // Silently continue — OTA device may be unreachable
                }

                // Source 2: REST realtime poll fallback. Fires only when the
                // cached cloud lock state has gone stale (5 min default), and
                // is internally rate-limited at 30s. So this loop calls it
                // every UNLOCK_POLL_INTERVAL_MS but the actual REST hit only
                // happens when we genuinely need fresh data.
                // The 12-iteration gate avoids hitting refreshLockStateIfStale()
                // every 5s — that's still a no-op call but cheap to skip.
                restPollCounter++;
                if (restPollCounter >= 12) { // ~ once per minute at 5s interval
                    restPollCounter = 0;
                    try {
                        com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance()
                                .refreshLockStateIfStale();
                        // The data provider fires its CloudLockStateListener
                        // automatically when the fetch reveals a transition,
                        // which we attached via attachCloudLockSource().
                    } catch (Exception e) {
                        // Silently continue — cloud may be down
                    }
                }
            }
            log("Unlock poll thread exiting (ACC ON)");
            } finally {
                boolean ownedSlot = false;
                synchronized (unlockPollThreadLock) {
                    if (unlockPollThread
                            == Thread.currentThread()) {
                        unlockPollThread = null;
                        unlockPollThreadGeneration = 0L;
                        ownedSlot = true;
                    }
                }
                if (ownedSlot && running.get()
                        && isAccTransitionCurrent(
                            transitionGeneration, true)) {
                    requestManagedAccWorkerRecovery(
                        "unlock poller exited");
                }
            }
        }, "UnlockPoll");
            worker.setDaemon(true);
        } catch (Throwable creationFailure) {
            requestManagedAccWorkerRecovery(
                "unlock poller creation failure");
            return;
        }

        synchronized (unlockPollThreadLock) {
            Thread current = unlockPollThread;
            if (current != null && current.isAlive()
                    && current != previous) {
                return;
            }
            unlockPollThread = worker;
            unlockPollThreadGeneration = transitionGeneration;
        }
        try {
            worker.start();
        } catch (Throwable startFailure) {
            synchronized (unlockPollThreadLock) {
                if (unlockPollThread == worker) {
                    unlockPollThread = null;
                    unlockPollThreadGeneration = 0L;
                }
            }
            requestManagedAccWorkerRecovery(
                "unlock poller start failure");
        }
    }

    private static void stopUnlockPollThread() {
        Thread worker;
        synchronized (unlockPollThreadLock) {
            worker = unlockPollThread;
        }
        if (interruptAndJoinManagedThread(
                worker, "unlock poller")) {
            synchronized (unlockPollThreadLock) {
                if (unlockPollThread == worker) {
                    unlockPollThread = null;
                    unlockPollThreadGeneration = 0L;
                }
            }
        }
    }

    /**
     * Clean up all door lock gate resources. Called on ACC ON.
     */
    private static void cleanupDoorLockGate() {
        doorLockListenerArmed = false;
        sawValidLockReading = false;
        stopDoorLockTimeoutThread();

        // Detach all three lock-event sources
        if (cloudLockListener != null) {
            try {
                com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance()
                    .removeLockStateListener(cloudLockListener);
            } catch (Exception ignored) {}
            cloudLockListener = null;
        }
        // (legacy device-SDK lock listener removed — see attachDeviceLockSource javadoc)
        stopUnlockPollThread();

        // Stop the reverse-fallback ACC-ON disarm watchdog
        stopAccOnDisarmWatchdog();
    }

    private static void stopDoorLockTimeoutThread() {
        Thread timeout;
        synchronized (doorLockTimeoutLock) {
            timeout = doorLockTimeoutThread;
        }
        if (interruptAndJoinManagedThread(
                timeout, "door-lock timeout")) {
            synchronized (doorLockTimeoutLock) {
                if (doorLockTimeoutThread == timeout) {
                    doorLockTimeoutThread = null;
                    doorLockTimeoutGeneration = 0L;
                }
            }
        }
    }

    private static boolean interruptAndJoinManagedThread(
            Thread worker, String label) {
        if (worker == null) return true;
        if (worker == Thread.currentThread()) {
            worker.interrupt();
            return false;
        }
        if (!worker.isAlive()) return true;
        worker.interrupt();
        try {
            worker.join(MANAGED_THREAD_JOIN_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (worker.isAlive()) {
            log(label + " did not stop within "
                + MANAGED_THREAD_JOIN_MS + "ms; retaining its slot");
            return false;
        }
        return true;
    }

    private static void requestManagedAccWorkerRecovery(
            String reason) {
        if (ACC_APPLY_CONTEXT.get() != null) {
            markCurrentAccApplyRetry();
        } else {
            invalidateLatestAccCompletionForCompensation();
        }
        requestAccTransitionReconciliation(false);
        log("ACC managed-worker recovery requested after "
            + reason);
    }

    /**
     * Notify surveillance of ACC state change.
     *
     * ACC OFF (sentry mode): Start pipeline with surveillance enabled
     * ACC ON (normal mode): Stop pipeline completely to save power
     */
    // FIX (audit R1): equality short-circuit. AccSentry heartbeat publishes
    // the cached ACC state every 30 s; without dedup each tick re-runs the
    // full side-effect chain (cleanupDoorLockGate, surveillanceEnabled reset,
    // OEM recalc, trip analytics, etc.). Track the last dispatched ACC state
    // so duplicate IPCs no-op. Boxed Boolean (-1/null sentinel) so the very
    // first call is never elided. Volatile for cross-thread reads from IPC +
    // heartbeat threads.
    private static volatile Boolean lastDispatchedAccIsOff = null;
    private static final Object parkTerminateLock = new Object();
    private static final Object parkMarkerIoLock = new Object();
    private static long accTransitionGeneration =
            newProcessAccGenerationBase();

    private static long newProcessAccGenerationBase() {
        long random =
                java.util.UUID.randomUUID()
                        .getLeastSignificantBits()
                        & 0x000003ffffffffffL;
        return (1L << 62) | random;
    }
    /**
     * Bumped whenever a stale or failed external effect may have landed after a newer attempt.
     * Every lease captures this value, so an already-issued attempt cannot publish completion
     * after a compensation request races its finalization.
     */
    private static long accReconciliationRevision;
    /** Latest ACC edge admitted at method ingress, including one still running its side effects. */
    private static Boolean latestAccIsOff;
    /** True once parked shutdown has planted its marker and made teardown irreversible. */
    private static boolean parkShutdownCommitted;
    /** Exact OFF generation whose marker crossed the terminal commit point. */
    private static long parkShutdownCommitGeneration;
    /** Sticky diagnostics/retry state; cleared only after the corresponding I/O succeeds. */
    private static boolean parkMarkerWriteFailed;
    private static boolean parkMarkerClearFailed;
    /** In-process identity for the numeric, reader-compatible marker written by this daemon. */
    private static long parkMarkerGeneration;
    private static long parkMarkerTimestampMs;
    /** Generation currently attempting the full effect chain; guarded by parkTerminateLock. */
    private static long activeAccTransitionGeneration;
    /** Revocable ownership token for the current effect attempt. */
    private static long activeAccTransitionLease;
    private static long activeAccTransitionDeadlineNanos;
    private static Thread activeAccTransitionThread;
    private static long nextAccTransitionLease;
    /** Only a successful, still-current lease is published as completed. */
    private static long completedAccTransitionGeneration;
    private static long completedAccTransitionLease;
    private static long completedAccTransitionRevision;

    private static final long ACC_EFFECT_LEASE_MAX_MS = 20_000L;
    private static final long SURVEILLANCE_ENABLE_LEASE_MAX_MS = 15_000L;

    // Durable/non-idempotent effects distinguish an in-progress attempt from a committed one.
    // Failed attempts release their claim so reconciliation can retry.
    private static final int ACC_EFFECT_LEDGER_SIZE = 64;
    private static final AccEffectLedger persistedAccEventGenerations =
        new AccEffectLedger();
    private static final AccEffectLedger notifiedTripAccGenerations =
        new AccEffectLedger();
    // Once-per-generation guard for the RecordingModeManager ACC-OFF dispatch
    // (audit: arm/disarm notification storm). RMM's OFF handler activates mode
    // NONE and STOPS the whole pipeline — re-driving it on a reconciler retry
    // pass of the SAME generation (e.g. after an SD mount failure) tore down
    // armed surveillance and re-fired the disarmed/armed automations on every
    // pass. Commit after success; release (stay retryable) only on throw. A
    // genuine new OFF edge is a new generation and always dispatches.
    private static final AccEffectLedger rmmAccOffDispatchGenerations =
        new AccEffectLedger();

    /** Revocable serialized ownership for public and deferred surveillance starts. */
    private static long nextSurveillanceEnableLease;
    private static long activeSurveillanceEnableLease;
    private static long activeSurveillanceEnableGeneration;
    private static long activeSurveillanceEnableRevision;
    private static long activeSurveillanceEnableDeadlineNanos;
    private static Thread activeSurveillanceEnableThread;

    private static final Object accReconcileLock = new Object();
    private static final long[] ACC_RECONCILE_DELAYS_MS = {100L, 500L, 2_000L, 5_000L};
    private static Thread accReconcileWorker;
    private static boolean accReconcileRequested;
    private static boolean accReconcileImmediate;
    private static boolean accReconcileFallbackPosted;
    private static volatile int accReconcileAttempt;
    private static volatile boolean trustedAccHardwareRecoveryRequested;

    private static final class AccEffectLedger {
        final java.util.ArrayDeque<Long> committedOrder = new java.util.ArrayDeque<>();
        final java.util.HashSet<Long> committed = new java.util.HashSet<>();
        final java.util.HashMap<Long, AccEffectInProgress> inProgress =
            new java.util.HashMap<>();
        long nextToken;
    }

    private static final class AccEffectInProgress {
        final long token;
        final Thread owner;

        AccEffectInProgress(long token, Thread owner) {
            this.token = token;
            this.owner = owner;
        }
    }

    private static final class AccEffectClaim {
        final AccEffectLedger ledger;
        final long generation;
        final long token;

        AccEffectClaim(
                AccEffectLedger ledger, long generation,
                long token) {
            this.ledger = ledger;
            this.generation = generation;
            this.token = token;
        }
    }


    private static final class AccApplyContext {
        final long generation;
        final boolean accIsOff;
        final long lease;
        final long revision;
        boolean retry;
        boolean deferred;

        AccApplyContext(
                long generation, boolean accIsOff, long lease, long revision) {
            this.generation = generation;
            this.accIsOff = accIsOff;
            this.lease = lease;
            this.revision = revision;
        }
    }

    private static final ThreadLocal<AccApplyContext> ACC_APPLY_CONTEXT = new ThreadLocal<>();

    private static final class AccTransitionLease {
        final long generation;
        final boolean accIsOff;
        final long token;
        final long revision;

        AccTransitionLease(
                long generation, boolean accIsOff, long token, long revision) {
            this.generation = generation;
            this.accIsOff = accIsOff;
            this.token = token;
            this.revision = revision;
        }
    }

    private static final class SurveillanceEnableLease {
        final long generation;
        final long revision;
        final long token;

        SurveillanceEnableLease(long generation, long revision, long token) {
            this.generation = generation;
            this.revision = revision;
            this.token = token;
        }
    }

    private static boolean isAccTransitionCurrent(long generation, boolean accIsOff) {
        synchronized (parkTerminateLock) {
            return running.get()
                && !parkShutdownCommitted
                && generation == accTransitionGeneration
                && latestAccIsOff != null
                && latestAccIsOff.booleanValue() == accIsOff;
        }
    }

    private static boolean isAccOnRequestedOrCached() {
        synchronized (parkTerminateLock) {
            if (latestAccIsOff != null) {
                return !latestAccIsOff.booleanValue();
            }
        }
        return com.overdrive.app.monitor.AccMonitor.isAccOn();
    }

    private static long captureAccObservationGeneration() {
        synchronized (parkTerminateLock) {
            return accTransitionGeneration;
        }
    }

    private static boolean isAccObservationCurrent(long observationGeneration) {
        synchronized (parkTerminateLock) {
            return running.get()
                && !parkShutdownCommitted
                && observationGeneration == accTransitionGeneration;
        }
    }

    private static boolean isCurrentAccApplyLease(AccApplyContext context) {
        if (context == null) return true;
        synchronized (parkTerminateLock) {
            return running.get()
                && !parkShutdownCommitted
                && context.generation == accTransitionGeneration
                && latestAccIsOff != null
                && latestAccIsOff.booleanValue() == context.accIsOff
                && context.revision == accReconciliationRevision
                && ((activeAccTransitionGeneration == context.generation
                        && activeAccTransitionLease == context.lease)
                    || (completedAccTransitionGeneration == context.generation
                        && completedAccTransitionLease == context.lease
                        && completedAccTransitionRevision == context.revision));
        }
    }

    private static boolean stopStaleAccTransition(
            long generation, boolean accIsOff, String phase) {
        AccApplyContext context = ACC_APPLY_CONTEXT.get();
        if (isAccTransitionCurrent(generation, accIsOff)
                && isCurrentAccApplyLease(context)) {
            return false;
        }
        log("ACC " + (accIsOff ? "OFF" : "ON") + " transition gen=" + generation
            + " superseded during " + phase + " — stopping stale side effects");
        if (context != null) {
            context.retry = true;
        }
        invalidateLatestAccCompletionForCompensation();
        requestAccTransitionReconciliation(true);
        return true;
    }

    private static void invalidateLatestAccCompletionForCompensation() {
        synchronized (parkTerminateLock) {
            invalidateAccCompletionLocked();
        }
    }

    private static void invalidateAccCompletionLocked() {
        accReconciliationRevision++;
        completedAccTransitionGeneration = 0L;
        completedAccTransitionLease = 0L;
        completedAccTransitionRevision = 0L;
        lastDispatchedAccIsOff = null;
    }

    private static boolean leaseExpired(long deadlineNanos) {
        return deadlineNanos != 0L
            && System.nanoTime() - deadlineNanos >= 0L;
    }

    private static void revokeActiveAccTransitionLocked(String reason) {
        Thread owner = activeAccTransitionThread;
        if (activeAccTransitionLease == 0L) return;
        log("Revoking ACC effect lease gen=" + activeAccTransitionGeneration
            + " after " + reason);
        activeAccTransitionGeneration = 0L;
        activeAccTransitionLease = 0L;
        activeAccTransitionDeadlineNanos = 0L;
        activeAccTransitionThread = null;
        invalidateAccCompletionLocked();
        if (owner != null && owner != Thread.currentThread()) {
            owner.interrupt();
        }
    }

    private static void revokeActiveSurveillanceEnableLocked(String reason) {
        Thread owner = activeSurveillanceEnableThread;
        if (activeSurveillanceEnableLease == 0L) return;
        log("Revoking surveillance-enable lease gen="
            + activeSurveillanceEnableGeneration + " after " + reason);
        activeSurveillanceEnableGeneration = 0L;
        activeSurveillanceEnableRevision = 0L;
        activeSurveillanceEnableLease = 0L;
        activeSurveillanceEnableDeadlineNanos = 0L;
        activeSurveillanceEnableThread = null;
        invalidateAccCompletionLocked();
        if (owner != null && owner != Thread.currentThread()) {
            owner.interrupt();
        }
    }

    private static void forceLatestAccStateReconciliation(String reason) {
        log("ACC latest-state compensation requested after " + reason);
        invalidateLatestAccCompletionForCompensation();
        requestAccTransitionReconciliation(true);
    }

    /** Apply an automation operating-mode change to the current parked cycle. */
    public static void reconcileOperatingModeForCurrentAccState() {
        final Boolean accIsOff;
        synchronized (parkTerminateLock) {
            if (!running.get() || parkShutdownCommitted) return;
            accIsOff = latestAccIsOff;
        }
        if (accIsOff == null) {
            requestTrustedAccHardwareRecovery("operating mode automation");
        } else if (accIsOff.booleanValue()) {
            forceLatestAccStateReconciliation("operating mode automation");
        }
    }

    private static void requestTrustedAccHardwareRecovery(String reason) {
        log("ACC trusted hardware recovery requested after " + reason);
        trustedAccHardwareRecoveryRequested = true;
        requestAccTransitionReconciliation(false);
    }

    private static AccTransitionLease claimAccTransitionLease(
            long generation, boolean accIsOff) {
        synchronized (parkTerminateLock) {
            if (!running.get()
                    || parkShutdownCommitted
                    || generation != accTransitionGeneration
                    || latestAccIsOff == null
                    || latestAccIsOff.booleanValue() != accIsOff) {
                return null;
            }
            if (completedAccTransitionGeneration == generation
                    && completedAccTransitionRevision == accReconciliationRevision
                    && lastDispatchedAccIsOff != null
                    && lastDispatchedAccIsOff.booleanValue() == accIsOff) {
                return null;
            }
            if (activeAccTransitionLease != 0L) {
                if (activeAccTransitionGeneration != generation
                        || leaseExpired(activeAccTransitionDeadlineNanos)) {
                    revokeActiveAccTransitionLocked(
                        activeAccTransitionGeneration != generation
                            ? "newer ACC generation"
                            : "effect deadline");
                } else {
                    return null;
                }
            }
            if (activeSurveillanceEnableLease != 0L) {
                if (!accIsOff
                        || activeSurveillanceEnableGeneration != generation
                        || leaseExpired(activeSurveillanceEnableDeadlineNanos)) {
                    revokeActiveSurveillanceEnableLocked(
                        !accIsOff ? "ACC ON admission"
                            : (activeSurveillanceEnableGeneration != generation
                                ? "newer ACC generation"
                                : "enable deadline"));
                } else {
                    return null;
                }
            }
            long token = ++nextAccTransitionLease;
            activeAccTransitionGeneration = generation;
            activeAccTransitionLease = token;
            activeAccTransitionDeadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                    ACC_EFFECT_LEASE_MAX_MS);
            activeAccTransitionThread = Thread.currentThread();
            return new AccTransitionLease(
                generation, accIsOff, token, accReconciliationRevision);
        }
    }

    private static void markCurrentAccApplyRetry() {
        AccApplyContext context = ACC_APPLY_CONTEXT.get();
        if (context != null) context.retry = true;
    }

    private static void markCurrentAccApplyDeferred() {
        AccApplyContext context = ACC_APPLY_CONTEXT.get();
        if (context != null) context.deferred = true;
    }

    // ==================== ACC-chain storage-phase tracking ====================
    // (audit: daemon-restart loop.) The probe-edge lease/revocation machinery
    // exists to catch a dispatch wedged in a HAL binder query — the one fault
    // a process restart actually fixes. A dispatch thread that is merely
    // executing STORAGE work (mount, lock wait, FS probe) must not trip that
    // hammer: restarting the daemon doesn't unwedge a slow SD card, costs
    // 21-63s of recording, and the post-restart boot work (remounts, index
    // warmup, boot reap) makes the storage pressure WORSE — the log showed
    // this looping 10× in 68 minutes. Threads mark themselves around
    // storage-bound ACC work; the escalation branch checks the mark and
    // waits instead of killing the process. The set self-heals: entries are
    // removed in finally, and a dead thread is ignored by the reader.
    private static final java.util.Set<Thread> ACC_STORAGE_PHASE_THREADS =
        java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<Thread, Boolean>());

    static void enterAccStoragePhase() {
        ACC_STORAGE_PHASE_THREADS.add(Thread.currentThread());
    }

    static void exitAccStoragePhase() {
        ACC_STORAGE_PHASE_THREADS.remove(Thread.currentThread());
    }

    private static boolean isThreadInStoragePhase(Thread t) {
        return t != null && t.isAlive() && ACC_STORAGE_PHASE_THREADS.contains(t);
    }

    // Single-flight latch for the ACC-ON background remount. Repeat ON chains
    // (probe re-dispatch, reconciler retry) must not stack workers that would
    // just queue on the mount lock behind each other.
    private static final java.util.concurrent.atomic.AtomicBoolean accOnRemountInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Fire-and-forget ACC-ON external remount (audit: daemon-restart loop —
     * this ran inline on the ACC dispatch thread and blocked it for 50-281s
     * against a 20s lease). The ON transition proceeds immediately on
     * internal storage; when the mount lands, StorageManager's centralized
     * came-online path re-points the recorder and reindexes. Failures are
     * the VolumeWatchdog's to retry — the transition is NOT marked for
     * retry on mount failure anymore, because re-running the ON chain
     * wouldn't do anything the watchdog isn't already doing.
     */
    private static void startAccOnRemountAsync() {
        if (!accOnRemountInFlight.compareAndSet(false, true)) {
            log("ACC ON: external remount already in flight — not stacking another worker");
            return;
        }
        try {
            Thread worker = new Thread(() -> {
                try {
                    enterAccStoragePhase();
                    boolean ok = com.overdrive.app.storage.StorageManager.getInstance()
                        .remountExternalOnAccOn();
                    if (!ok) {
                        log("ACC ON: external remount incomplete — VolumeWatchdog continues retrying");
                    }
                } catch (Throwable t) {
                    log("ACC ON: external remount failed: " + t.getMessage());
                } finally {
                    exitAccStoragePhase();
                    accOnRemountInFlight.set(false);
                }
            }, "AccOnRemount");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable spawnFailure) {
            accOnRemountInFlight.set(false);
            log("ACC ON: external remount worker spawn failed: " + spawnFailure.getMessage()
                + " — VolumeWatchdog remains the recovery path");
        }
    }

    // Single-flight latch for the ACC-OFF background force-mount. Repeat OFF
    // chains (probe re-dispatch, reconciler retry, 60s heartbeat) must not
    // stack workers that would just queue on the mount lock behind each other.
    private static final java.util.concurrent.atomic.AtomicBoolean accOffSdMountInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Fire-and-forget ACC-OFF SD force-mount (audit: arm/disarm notification
     * storm — the previous bounded 30s join ran on the reconciler thread
     * inside the 20s ACC effect lease, so the lease revocation interrupted
     * the wait and the retry re-ran the whole OFF lifecycle). Mirror of
     * {@link #startAccOnRemountAsync}: the OFF transition proceeds
     * immediately on internal storage; a late-landing mount is re-pointed by
     * StorageManager's came-online path and the engine's per-trigger
     * surveillance-dir refresh. Failures are the VolumeWatchdog's to retry —
     * the ACC transition is never marked for retry on mount failure.
     */
    private static void startAccOffSdMountAsync(
            com.overdrive.app.storage.StorageManager storage) {
        if (!accOffSdMountInFlight.compareAndSet(false, true)) {
            log("ACC OFF: SD force mount already in flight — not stacking another worker");
            return;
        }
        log("FORCE mounting SD card (ACC OFF, SD card configured for storage)...");
        try {
            Thread worker = new Thread(() -> {
                try {
                    // Storage-phase mark: the probe-edge lease machinery must
                    // treat a slow mount as "slow storage", never "wedged HAL"
                    // (see ACC_STORAGE_PHASE_THREADS).
                    enterAccStoragePhase();
                    if (storage.ensureSdCardMounted(true)) {
                        log("SD card force mounted (ACC OFF, async)");
                    } else {
                        log("WARNING: ACC-OFF SD force mount incomplete - using internal"
                            + " storage until VolumeWatchdog lands the mount");
                    }
                } catch (Throwable t) {
                    log("ACC-OFF SD force mount threw: " + t.getMessage()
                        + " — VolumeWatchdog remains the recovery path");
                } finally {
                    exitAccStoragePhase();
                    accOffSdMountInFlight.set(false);
                }
            }, "AccOffSdMount");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable spawnFailure) {
            accOffSdMountInFlight.set(false);
            log("ACC OFF: SD force mount worker spawn failed: " + spawnFailure.getMessage()
                + " — VolumeWatchdog remains the recovery path");
        }
    }

    private static void finishAccTransitionLease(AccApplyContext context) {
        boolean scheduleRetry = false;
        synchronized (parkTerminateLock) {
            boolean ownsLease = activeAccTransitionGeneration == context.generation
                && activeAccTransitionLease == context.lease;
            boolean current = context.generation == accTransitionGeneration
                && latestAccIsOff != null
                && latestAccIsOff.booleanValue() == context.accIsOff
                && context.revision == accReconciliationRevision;

            if (ownsLease) {
                activeAccTransitionGeneration = 0L;
                activeAccTransitionLease = 0L;
                activeAccTransitionDeadlineNanos = 0L;
                activeAccTransitionThread = null;
            }
            if (ownsLease && current && !context.retry && !context.deferred) {
                completedAccTransitionGeneration = context.generation;
                completedAccTransitionLease = context.lease;
                completedAccTransitionRevision = context.revision;
                lastDispatchedAccIsOff = Boolean.valueOf(context.accIsOff);
                accReconcileAttempt = 0;
            } else if (context.retry || !context.deferred || !ownsLease) {
                // The abandoned lease may have completed an external side effect after a
                // newer lease published success. Invalidate that publication so reconciliation
                // cannot short-circuit and the latest state is forcibly applied again.
                invalidateAccCompletionLocked();
                scheduleRetry = true;
            }
        }
        if (scheduleRetry) {
            requestAccTransitionReconciliation(false);
        }
    }

    private static boolean isCurrentAccTransitionCompletedLocked() {
        return latestAccIsOff != null
            && completedAccTransitionGeneration == accTransitionGeneration
            && completedAccTransitionRevision == accReconciliationRevision
            && lastDispatchedAccIsOff != null
            && lastDispatchedAccIsOff.booleanValue() == latestAccIsOff.booleanValue();
    }

    private static boolean isCurrentAccTransitionDeferredLocked() {
        if (latestAccIsOff == null) return false;
        return latestAccIsOff.booleanValue() ? pendingAccOff : pendingAccOn;
    }

    /**
     * Re-drive only the latest admitted state. The retry thread never owns transition effects, so
     * a blocked HAL/Binder call cannot hold admission or prevent a newer generation from running.
     */
    private static void requestAccTransitionReconciliation(boolean immediate) {
        boolean runInline = false;
        synchronized (accReconcileLock) {
            accReconcileRequested = true;
            accReconcileImmediate |= immediate;
            if (accReconcileWorker != null && accReconcileWorker.isAlive()) {
                accReconcileLock.notifyAll();
                return;
            }
            try {
                Thread worker = new Thread(
                    CameraDaemon::runAccReconciliationWorker,
                    "AccStateReconciler");
                worker.setDaemon(true);
                accReconcileWorker = worker;
                worker.start();
            } catch (Throwable startFailure) {
                accReconcileWorker = null;
                log("ACC state reconciler could not start: " + startFailure.getMessage());
                boolean currentOwnsEffects = ACC_APPLY_CONTEXT.get() != null;
                synchronized (parkTerminateLock) {
                    currentOwnsEffects |=
                        activeSurveillanceEnableThread == Thread.currentThread();
                }
                if (currentOwnsEffects) {
                    // The owner cannot recurse into its own effect chain. Make its eventual lease
                    // release schedule another attempt, and add a main-loop nudge for the
                    // surveillance-only case where no ACC apply context exists.
                    markCurrentAccApplyRetry();
                    Handler handler = mainHandler;
                    if (handler != null && !accReconcileFallbackPosted) {
                        accReconcileFallbackPosted = true;
                        boolean posted = false;
                        try {
                            posted = handler.postDelayed(() -> {
                                synchronized (accReconcileLock) {
                                    accReconcileFallbackPosted = false;
                                }
                                requestAccTransitionReconciliation(false);
                            }, ACC_RECONCILE_DELAYS_MS[0]);
                        } catch (Throwable ignored) {}
                        if (!posted) {
                            accReconcileFallbackPosted = false;
                        }
                    }
                } else {
                    // Thread creation can fail transiently under memory pressure. The requesting
                    // thread becomes the reconciler so the request has no terminal failure path.
                    accReconcileWorker = Thread.currentThread();
                    runInline = true;
                }
            }
        }
        if (runInline) {
            runAccReconciliationWorker();
        }
    }

    private static void runAccReconciliationWorker() {
        try {
            runAccReconciliationLoop();
        } catch (Throwable failure) {
            log("ACC state reconciler exited unexpectedly: "
                + failure.getMessage());
        } finally {
            synchronized (accReconcileLock) {
                if (accReconcileWorker
                        == Thread.currentThread()) {
                    accReconcileWorker = null;
                }
            }
            boolean retry;
            synchronized (parkTerminateLock) {
                retry = running.get() && !parkShutdownCommitted
                    && (trustedAccHardwareRecoveryRequested
                        || (latestAccIsOff != null
                            && !isCurrentAccTransitionCompletedLocked()
                            && !isCurrentAccTransitionDeferredLocked()));
            }
            if (retry) {
                requestAccTransitionReconciliation(false);
            }
        }
    }

    private static void runAccReconciliationLoop() {
        while (true) {
            final boolean immediate;
            final long delayMs;
            synchronized (accReconcileLock) {
                if (!accReconcileRequested) {
                    if (accReconcileWorker == Thread.currentThread()) {
                        accReconcileWorker = null;
                    }
                    return;
                }
                immediate = accReconcileImmediate;
                accReconcileRequested = false;
                accReconcileImmediate = false;
                int index = Math.min(
                    accReconcileAttempt, ACC_RECONCILE_DELAYS_MS.length - 1);
                delayMs = immediate ? 0L : ACC_RECONCILE_DELAYS_MS[index];
                if (!immediate && accReconcileAttempt
                        < ACC_RECONCILE_DELAYS_MS.length - 1) {
                    accReconcileAttempt++;
                }
            }

            if (delayMs > 0L) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    synchronized (accReconcileLock) {
                        if (accReconcileWorker == Thread.currentThread()) {
                            accReconcileWorker = null;
                        }
                    }
                    return;
                }
            }

            final long generation;
            final Boolean accIsOff;
            final boolean pendingReplay;
            final boolean recoverUnknownState;
            synchronized (parkTerminateLock) {
                if (!running.get() || parkShutdownCommitted) {
                    continue;
                }
                if (isCurrentAccTransitionCompletedLocked()) {
                    trustedAccHardwareRecoveryRequested = false;
                    continue;
                }
                pendingReplay = isCurrentAccTransitionDeferredLocked()
                    && gpuPipeline != null && recordingModeManager != null;
                recoverUnknownState = latestAccIsOff == null
                    && trustedAccHardwareRecoveryRequested;
                if (isCurrentAccTransitionDeferredLocked() && !pendingReplay) {
                    continue;
                }
                if (latestAccIsOff == null && !recoverUnknownState) {
                    continue;
                }
                generation = accTransitionGeneration;
                accIsOff = latestAccIsOff;
            }

            boolean trustedRecoveryRetry = false;
            if (pendingReplay) {
                trustedRecoveryRetry = !reconcilePendingAccStateFromHardware();
            } else if (recoverUnknownState) {
                AccProbeResult probe =
                    probeAccStateWithBackoff("autonomous-recovery");
                if (!isAccObservationCurrent(generation)) {
                    trustedAccHardwareRecoveryRequested = false;
                } else if (!probe.trustworthy) {
                    trustedRecoveryRetry = true;
                } else {
                    trustedAccHardwareRecoveryRequested = false;
                    onObservedAccStateChanged(
                        probe.accIsOff, generation, "autonomous-recovery");
                }
            } else if (accIsOff != null) {
                onAccStateChanged(accIsOff.booleanValue(), generation);
            }

            boolean retry;
            synchronized (parkTerminateLock) {
                retry = running.get() && !parkShutdownCommitted
                    && ((trustedRecoveryRetry
                            && trustedAccHardwareRecoveryRequested)
                        || (!isCurrentAccTransitionCompletedLocked()
                            && !isCurrentAccTransitionDeferredLocked()));
            }
            if (retry) {
                synchronized (accReconcileLock) {
                    accReconcileRequested = true;
                }
            }
        }
    }

    /**
     * Resolve one deferred replay only from a trustworthy probe bound to the
     * generation sampled before it. False requests another bounded reconciler pass.
     */
    private static boolean reconcilePendingAccStateFromHardware() {
        final long observationGeneration;
        final boolean pendingIsOff;
        synchronized (parkTerminateLock) {
            if (!pendingAccOff && !pendingAccOn) {
                return true;
            }
            observationGeneration = accTransitionGeneration;
            pendingIsOff = pendingAccOff;
        }

        AccProbeResult probe =
            probeAccStateWithBackoff("pending-reconcile");
        if (!isAccObservationCurrent(observationGeneration)) {
            trustedAccHardwareRecoveryRequested = false;
            return true;
        }
        if (!probe.trustworthy) {
            trustedAccHardwareRecoveryRequested = true;
            return false;
        }
        trustedAccHardwareRecoveryRequested = false;

        long pendingGeneration = claimPendingAccState(pendingIsOff);
        if (pendingGeneration == 0L) {
            return true;
        }
        if (probe.accIsOff == pendingIsOff) {
            onAccStateChanged(pendingIsOff, pendingGeneration);
            return true;
        }

        onObservedAccStateChanged(
            probe.accIsOff,
            observationGeneration,
            "pending-reconcile");
        com.overdrive.app.automation.condition.BydEvent
            .resetPowerEdge(!pendingIsOff);
        return true;
    }

    private static AccEffectClaim claimAccEffectOnce(
            AccEffectLedger ledger, long generation) {
        boolean busy = false;
        synchronized (ledger) {
            if (ledger.committed.contains(generation)) {
                return null;
            }
            AccEffectInProgress existing =
                ledger.inProgress.get(generation);
            if (existing != null
                    && existing.owner != null
                    && existing.owner.isAlive()) {
                busy = true;
            } else {
                long token = ++ledger.nextToken;
                ledger.inProgress.put(
                    generation,
                    new AccEffectInProgress(
                        token, Thread.currentThread()));
                return new AccEffectClaim(
                    ledger, generation, token);
            }
        }
        if (busy) {
            // Do not duplicate a durable/non-idempotent call. The exact owner will either commit
            // or release; this transition remains retryable until one of those happens.
            markCurrentAccApplyRetry();
        }
        return null;
    }

    private static void commitAccEffect(
            AccEffectClaim claim) {
        boolean staleClaim = false;
        synchronized (claim.ledger) {
            AccEffectInProgress inProgress =
                claim.ledger.inProgress.get(claim.generation);
            if (inProgress == null
                    || inProgress.token != claim.token
                    || inProgress.owner
                        != Thread.currentThread()) {
                staleClaim = true;
            } else {
                claim.ledger.inProgress.remove(
                    claim.generation);
                if (claim.ledger.committed.add(
                        claim.generation)) {
                    claim.ledger.committedOrder.addLast(
                        claim.generation);
                }
                while (claim.ledger.committedOrder.size()
                        > ACC_EFFECT_LEDGER_SIZE) {
                    Long removed =
                        claim.ledger.committedOrder.removeFirst();
                    claim.ledger.committed.remove(removed);
                }
            }
        }
        if (staleClaim) {
            forceLatestAccStateReconciliation(
                "stale durable-effect claimant returned");
        }
    }

    private static void releaseAccEffect(
            AccEffectClaim claim) {
        synchronized (claim.ledger) {
            AccEffectInProgress inProgress =
                claim.ledger.inProgress.get(claim.generation);
            if (inProgress != null
                    && inProgress.token == claim.token
                    && inProgress.owner
                        == Thread.currentThread()) {
                claim.ledger.inProgress.remove(
                    claim.generation);
            }
        }
    }

    private static boolean isAccEffectCommitted(
            AccEffectLedger ledger, long generation) {
        synchronized (ledger) {
            return ledger.committed.contains(generation);
        }
    }

    /**
     * Deliver an ACC edge to trip analytics. Duplicate/heartbeat IPC is already
     * filtered by the caller's generation check, so one real edge reaches the
     * manager once: BYD's power HAL repeats ACC OFF while parked, and finalizing
     * on every repeat would split one drive into several cards.
     */
    private static void notifyTripAnalyticsManager(boolean accIsOff) {
        com.overdrive.app.trips.TripAnalyticsManager manager =
                tripAnalyticsManager;
        if (manager == null) return;
        try {
            if (accIsOff) {
                manager.onAccOff();
            } else {
                manager.onAccOn();
            }
        } catch (Throwable failure) {
            log("Trip Analytics ACC " + (accIsOff ? "OFF" : "ON")
                    + " error: " + failure.getMessage());
        }
    }

    /**
     * Queue a transition whose generation has already been admitted.
     */
    private static boolean queuePendingAccState(
            boolean accIsOff, long transitionGeneration) {
        synchronized (parkTerminateLock) {
            if (transitionGeneration != accTransitionGeneration
                    || latestAccIsOff == null
                    || latestAccIsOff.booleanValue() != accIsOff) {
                return false;
            }
            pendingAccOff = accIsOff;
            pendingAccOn = !accIsOff;
            pendingAccTransitionGeneration = transitionGeneration;
            return true;
        }
    }

    /**
     * Atomically remove a pending state and return the generation it belongs to.
     * Zero means no current pending transition was available.
     */
    private static long claimPendingAccState(boolean accIsOff) {
        synchronized (parkTerminateLock) {
            boolean pending = accIsOff ? pendingAccOff : pendingAccOn;
            if (!pending) {
                return 0L;
            }
            if (accIsOff) {
                pendingAccOff = false;
            } else {
                pendingAccOn = false;
            }
            long generation = pendingAccTransitionGeneration;
            if (generation != accTransitionGeneration
                    || latestAccIsOff == null
                    || latestAccIsOff.booleanValue() != accIsOff) {
                log("Discarding stale pending ACC " + (accIsOff ? "OFF" : "ON")
                    + " transition gen=" + generation);
                return 0L;
            }
            return generation;
        }
    }

    private static boolean hasPendingAccState(boolean accIsOff) {
        synchronized (parkTerminateLock) {
            return accIsOff ? pendingAccOff : pendingAccOn;
        }
    }

    private static void stopTripRequiredPollersForAccOff(
            long transitionGeneration) {
        if (!isAccTransitionCurrent(transitionGeneration, true)) {
            return;
        }

        if (telemetryDataCollector != null) {
            telemetryDataCollector.setOverlayRecordingActive(false);
            telemetryDataCollector.forceStopPolling();
            log("TelemetryDataCollector force-stopped (confirmed ACC OFF)");
        }
        if (stopStaleAccTransition(
                transitionGeneration, true, "telemetry poller teardown")) {
            return;
        }

        com.overdrive.app.monitor.GearMonitor.getInstance().stop();
        log("GearMonitor stopped (confirmed ACC OFF)");
        if (stopStaleAccTransition(
                transitionGeneration, true, "gear poller teardown")) {
            return;
        }

        com.overdrive.app.byd.BydDataCollector.getInstance()
                .setAccState(false);
        stopStaleAccTransition(
            transitionGeneration, true, "BYD poller teardown");
    }

    private static boolean markAccTransitionDispatched(
            long generation, boolean accIsOff) {
        synchronized (parkTerminateLock) {
            if (generation != accTransitionGeneration
                    || latestAccIsOff == null
                    || latestAccIsOff.booleanValue() != accIsOff) {
                return false;
            }
            if (accIsOff) {
                pendingAccOff = false;
            } else {
                pendingAccOn = false;
            }
            return true;
        }
    }

    private static void clearAccDispatchForRetry(
            long generation, boolean accIsOff, String reason) {
        synchronized (parkTerminateLock) {
            if (generation != accTransitionGeneration
                    || latestAccIsOff == null
                    || latestAccIsOff.booleanValue() != accIsOff) {
                return;
            }
            log(reason);
            accReconciliationRevision++;
            lastDispatchedAccIsOff = null;
            completedAccTransitionGeneration = 0L;
            completedAccTransitionLease = 0L;
            completedAccTransitionRevision = 0L;
        }
        markCurrentAccApplyRetry();
        requestAccTransitionReconciliation(false);
    }

    public static void onAccStateChanged(boolean accIsOff) {
        onAccStateChanged(accIsOff, 0L);
    }

    /**
     * @param requiredGeneration zero for a newly observed edge, or the token
     *        claimed from a pending queue replay
     */
    private static void onAccStateChanged(boolean accIsOff, long requiredGeneration) {
        final long transitionGeneration;
        synchronized (parkTerminateLock) {
            if (requiredGeneration != 0L
                    && (requiredGeneration != accTransitionGeneration
                        || latestAccIsOff == null
                        || latestAccIsOff.booleanValue() != accIsOff)) {
                log("Skipping stale pending ACC " + (accIsOff ? "OFF" : "ON")
                    + " replay gen=" + requiredGeneration);
                return;
            }
            trustedAccHardwareRecoveryRequested = false;

            Boolean previousRequestedState = latestAccIsOff;
            boolean transitionChanged = requiredGeneration == 0L
                && (previousRequestedState == null
                    || previousRequestedState.booleanValue() != accIsOff);
            latestAccIsOff = Boolean.valueOf(accIsOff);
            if (transitionChanged) {
                accTransitionGeneration++;
                // The old latch describes the previous completed phase. Clear
                // it until this generation finishes so a heartbeat can retry if
                // this invocation is interrupted before publishing completion.
                lastDispatchedAccIsOff = null;
                // A newer opposite edge invalidates the old pending replay.
                if (accIsOff) {
                    pendingAccOn = false;
                } else {
                    pendingAccOff = false;
                }
            }
            transitionGeneration = accTransitionGeneration;
        }
        runAdmittedAccTransition(accIsOff, transitionGeneration);
    }

    /**
     * Apply a hardware observation only if no newer ACC admission occurred after the sample was
     * scheduled. Unlike a pending replay, the observed state may legitimately differ from the
     * current state, so admission and generation increment happen here after the base-token check.
     */
    private static void onObservedAccStateChanged(
            boolean accIsOff, long observationGeneration, String source) {
        final long transitionGeneration;
        synchronized (parkTerminateLock) {
            if (observationGeneration != accTransitionGeneration) {
                log("Ignoring stale ACC hardware observation (" + source + ") baseGen="
                    + observationGeneration + " currentGen=" + accTransitionGeneration);
                requestAccTransitionReconciliation(true);
                return;
            }
            trustedAccHardwareRecoveryRequested = false;
            Boolean previousRequestedState = latestAccIsOff;
            boolean changed = previousRequestedState == null
                || previousRequestedState.booleanValue() != accIsOff;
            latestAccIsOff = Boolean.valueOf(accIsOff);
            if (changed) {
                accTransitionGeneration++;
                lastDispatchedAccIsOff = null;
                if (accIsOff) {
                    pendingAccOn = false;
                } else {
                    pendingAccOff = false;
                }
            }
            transitionGeneration = accTransitionGeneration;
        }
        runAdmittedAccTransition(accIsOff, transitionGeneration);
    }

    private static void runAdmittedAccTransition(
            boolean accIsOff, long transitionGeneration) {
        AccTransitionLease lease = claimAccTransitionLease(
            transitionGeneration, accIsOff);
        if (lease == null) {
            synchronized (parkTerminateLock) {
                if (completedAccTransitionGeneration == transitionGeneration
                        && completedAccTransitionRevision == accReconciliationRevision
                        && lastDispatchedAccIsOff != null
                        && lastDispatchedAccIsOff.booleanValue() == accIsOff) {
                    log("onAccStateChanged: no-op (completed "
                        + (accIsOff ? "OFF" : "ON") + ", duplicate IPC / heartbeat)");
                    return;
                }
            }
            requestAccTransitionReconciliation(false);
            return;
        }

        AccApplyContext context = new AccApplyContext(
            transitionGeneration, accIsOff, lease.token, lease.revision);
        ACC_APPLY_CONTEXT.set(context);
        try {
            applyAccTransitionEffects(accIsOff, transitionGeneration);
        } catch (Throwable t) {
            context.retry = true;
            log("ACC " + (accIsOff ? "OFF" : "ON")
                + " transition failed: " + t.getClass().getSimpleName()
                + ": " + t.getMessage());
        } finally {
            ACC_APPLY_CONTEXT.remove();
            finishAccTransitionLease(context);
        }
    }

    private static void applyAccTransitionEffects(
            boolean accIsOff, long transitionGeneration) {
            if (stopStaleAccTransition(
                    transitionGeneration, accIsOff, "effect lease admission")) {
                return;
            }
            // This call may dispatch panel/cluster work. It is intentionally not
            // protected by a monitor held across external IPC; a newer generation
            // can run immediately, and the post-call currency check re-drives it.
            com.overdrive.app.monitor.AccMonitor.setAccState(!accIsOff);
            if (stopStaleAccTransition(
                    transitionGeneration, accIsOff, "ACC cache publication")) {
                return;
            }

        // Re-open a terminal OFF drain before publishing ON. Enqueues are retained while the
        // drain gate is active, but ordering cancellation first also prevents the ON edge from
        // sitting behind stale drain work.
        if (!accIsOff) {
            com.overdrive.app.automation.AutomationQueue.ShutdownDrainCancelResult cancelResult =
                com.overdrive.app.automation.AutomationQueue.cancelShutdownDrain(
                    2_000L, () -> forceLatestAccStateReconciliation(
                        "late shutdown-drain quiescence"));
            if (cancelResult
                    != com.overdrive.app.automation.AutomationQueue.ShutdownDrainCancelResult.NO_DRAIN) {
                log("ACC ON canceled parked-shutdown automation drain ("
                    + cancelResult + ")");
            }
            if (stopStaleAccTransition(
                    transitionGeneration, false, "shutdown-drain cancellation")) {
                return;
            }
        }

        // Publish the "power" automation trigger on the EDGE. It otherwise rides the telemetry
        // snapshot only, which is 90s while parked — so "when power turns off" fired up to 90s
        // late, and in onOnly mode never at all (parkTerminate below kills the process first).
        // Placed here deliberately: after the dedup guard (so it can't double-fire on a repeat
        // heartbeat), but before both the gpuPipeline==null early return and parkTerminate.
        // Publishes the same lowercase vocabulary as the snapshot path, and Automations.update
        // is edge-triggered, so a later snapshot carrying the same value is a no-op.
        boolean powerPublished = false;
        final AccApplyContext publicationContext = ACC_APPLY_CONTEXT.get();
        try {
            powerPublished =
                com.overdrive.app.automation.AutomationQueue
                    .runLatestStatePublicationGuarded(
                        com.overdrive.app.automation.AutomationQueue
                            .LatestStateStream.POWER,
                        publicationCommit -> {
                            synchronized (parkTerminateLock) {
                                // Name the failed admission check: a rejected power edge is a
                                // missed "power on/off" automation until a retry (or the
                                // snapshot grace-window fallback in BydEvent.publishPower)
                                // delivers it, and field logs previously showed nothing at all
                                // for this — the 2026-08 "power on never fires" report was
                                // undiagnosable from a device log.
                                String rejection =
                                    !running.get() ? "daemon not running"
                                    : parkShutdownCommitted ? "park shutdown committed"
                                    : publicationContext == null ? "no apply context"
                                    : publicationContext.generation != transitionGeneration
                                        ? "context generation stale"
                                    : publicationContext.accIsOff != accIsOff
                                        ? "context ACC side mismatch"
                                    : publicationContext.revision != accReconciliationRevision
                                        ? "reconciliation revision stale"
                                    : transitionGeneration != accTransitionGeneration
                                        ? "transition generation superseded"
                                    : latestAccIsOff == null ? "no observed ACC state"
                                    : latestAccIsOff.booleanValue() != accIsOff
                                        ? "observed ACC state contradicts edge"
                                    : activeAccTransitionGeneration != transitionGeneration
                                        ? "active transition superseded"
                                    : activeAccTransitionLease != publicationContext.lease
                                        ? "transition lease superseded"
                                    : activeAccTransitionThread != Thread.currentThread()
                                        ? "transition thread superseded"
                                    : null;
                                if (rejection != null) {
                                    log("ACC edge: power automation publication REJECTED ("
                                        + rejection + ") for ACC "
                                        + (accIsOff ? "OFF" : "ON")
                                        + " gen=" + transitionGeneration
                                        + " — will retry via reconciliation");
                                    return false;
                                }
                                publicationCommit.publish();
                                return true;
                            }
                        },
                        () -> com.overdrive.app.automation.condition.BydEvent
                            .publishPowerEdge(!accIsOff));
        } catch (Throwable t) {
            log("ACC edge: power automation publish failed: " + t.getMessage());
            markCurrentAccApplyRetry();
        }
        if (!powerPublished) {
            log("ACC edge: 'power' NOT published for ACC " + (accIsOff ? "OFF" : "ON")
                + " gen=" + transitionGeneration
                + " — power automations will not fire until a retry or the snapshot "
                + "fallback delivers this edge");
            if (!stopStaleAccTransition(
                    transitionGeneration, accIsOff,
                    "power automation publication admission")) {
                markCurrentAccApplyRetry();
                requestAccTransitionReconciliation(false);
            }
            return;
        }
        if (stopStaleAccTransition(
                transitionGeneration, accIsOff, "power automation publication")) {
            return;
        }

        if (!accIsOff) {
            if (!reconcileParkedShutdownMarkerOn(transitionGeneration)) {
                if (isAccTransitionCurrent(transitionGeneration, false)) {
                    markCurrentAccApplyRetry();
                }
                return;
            }
            // Marker removal makes a committed shutdown recoverable, but teardown itself
            // is irreversible. Do not restart components in a process already shutting down.
            if (isParkShutdownCommitted()) {
                return;
            }
        } else if (isParkShutdownCommittedForGeneration(
                transitionGeneration)) {
            reconcileCommittedParkedShutdownOff(transitionGeneration);
            return;
        } else if (isParkShutdownCommitted()) {
            // A committed OFF1 cannot be inherited by OFF2. This process is already under its
            // terminal deadline; a fresh process will recover and run OFF2 before committing it.
            log("Ignoring parked-marker transfer to newer OFF generation "
                + transitionGeneration);
            return;
        }

        // CRITICAL: Capture the BydVehicleData snapshot and record the ACC
        // transition BEFORE any pipeline/teardown work. The OFF event must
        // be persisted before BydDataCollector.setAccState(false) (further
        // down) zeroes out polling — otherwise the OFF row would have stale
        // or null telemetry. For ON, the collector is being resumed, not
        // torn down; the snapshot may be a few seconds stale, which is
        // fine (a 3s skew is negligible vs a 12-hour park, and any latency
        // biases the displayed delta toward zero — conservative).
        //
        // Wrapped in try/catch — must NEVER throw out of onAccStateChanged
        // because that would break the daemon's state machine.
        AccEffectClaim persistedEventClaim =
            claimAccEffectOnce(
                persistedAccEventGenerations,
                transitionGeneration);
        if (persistedEventClaim != null) {
            try {
                com.overdrive.app.byd.BydVehicleData accSnapshot = null;
                try {
                    com.overdrive.app.byd.BydDataCollector collector =
                        com.overdrive.app.byd.BydDataCollector.getInstance();
                    if (collector != null && collector.isInitialized()) {
                        accSnapshot = collector.getData();
                    }
                } catch (Throwable t) {
                    // Collector not initialized yet on cold boot, etc. — pass
                    // null snapshot, the row will still be recorded with the
                    // event type so future correlation is possible.
                }
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance()
                    .recordAccEvent(accIsOff ? "OFF" : "ON", accSnapshot);
                commitAccEffect(persistedEventClaim);
            } catch (Throwable t) {
                log("recordAccEvent failed (non-fatal): " + t.getMessage());
                releaseAccEffect(persistedEventClaim);
                markCurrentAccApplyRetry();
            }
        }

        if (stopStaleAccTransition(
                transitionGeneration, accIsOff, "event persistence")) {
            return;
        }

        // ALWAYS notify trip analytics, regardless of GPU pipeline state: trip
        // detection depends on ACC edges and must not be blocked by pipeline
        // readiness. Reached only on a leased (non-duplicate) transition, so a
        // repeated ACC-OFF heartbeat cannot finalize the same drive twice.
        notifyTripAnalyticsManager(accIsOff);

        if (gpuPipeline == null || recordingModeManager == null) {
            log("ACC " + (accIsOff ? "OFF" : "ON")
                + " dependencies not ready (pipeline="
                + (gpuPipeline != null) + ", rmm="
                + (recordingModeManager != null)
                + ") — queuing full transition replay");
            if (!queuePendingAccState(accIsOff, transitionGeneration)) {
                log("ACC " + (accIsOff ? "OFF" : "ON")
                    + " became stale before its pending state could be queued");
            } else {
                markCurrentAccApplyDeferred();
            }
            // NOTE: leave lastDispatchedAccIsOff unset so the post-init drain
            // can re-enter this method and run the full side-effect chain.
            return;
        }

        // Mark this state as fully dispatched only AFTER passing the
        // gpuPipeline-null queuing branch. See dedup comment above.
        if (!markAccTransitionDispatched(transitionGeneration, accIsOff)) {
            log("ACC " + (accIsOff ? "OFF" : "ON")
                + " became stale before dispatch latch publication");
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
                //
                // ONCE PER GENERATION (audit: arm/disarm notification storm): RMM's
                // OFF handler activates mode NONE and stops the whole pipeline. On a
                // reconciler retry pass of the same OFF generation (SD mount failure,
                // lease revocation) that stop tore down already-armed surveillance and
                // re-fired the disarmed/armed automations on every pass. Ledger-guard
                // the dispatch: commit after success, release + retry only on throw.
                if (recordingModeManager != null) {
                    AccEffectClaim rmmAccOffClaim = claimAccEffectOnce(
                        rmmAccOffDispatchGenerations, transitionGeneration);
                    if (rmmAccOffClaim != null) {
                        try {
                            log("ACC OFF - notifying RecordingModeManager to finalize active recording...");
                            recordingModeManager.onAccStateChanged(false);
                            commitAccEffect(rmmAccOffClaim);
                        } catch (Throwable t) {
                            log("RecordingModeManager ACC OFF dispatch failed: "
                                + t.getMessage());
                            releaseAccEffect(rmmAccOffClaim);
                            markCurrentAccApplyRetry();
                            // The pipeline state is indeterminate mid-stop; arming
                            // on top of it would race the failed teardown. ABORT
                            // this pass (matching the pre-ledger behavior, where a
                            // throw here propagated to the outer catch) — the
                            // retry flag makes finishAccTransitionLease invalidate
                            // completion and schedule reconciliation, which re-runs
                            // the chain from the top with the effect released.
                            return;
                        }
                    } else if (!isAccEffectCommitted(
                            rmmAccOffDispatchGenerations, transitionGeneration)) {
                        // BUSY, not committed: a revoked (lease-expired) apply
                        // thread is still executing the RMM stop — interruption
                        // is cooperative, so it keeps running until it finishes.
                        // Continuing into arming here would race its in-flight
                        // pipeline stop (stop-after-arm). claimAccEffectOnce
                        // already marked this pass for retry; end it and let the
                        // reconciler re-drive once the owner commits or releases.
                        log("ACC OFF - RecordingModeManager dispatch still owned by a"
                            + " prior apply thread for gen=" + transitionGeneration
                            + " — deferring this pass to the reconciler retry");
                        return;
                    } else {
                        log("ACC OFF - RecordingModeManager dispatch already committed"
                            + " for gen=" + transitionGeneration + " — skipping"
                            + " duplicate pipeline stop");
                    }
                }
                if (stopStaleAccTransition(
                        transitionGeneration, true, "recording finalization")) {
                    return;
                }
                // OEM Dashcam ACC-off behaviour. accOffMode='off' (default)
                // tears down the pipeline so the encoder + camera handle
                // release at ACC-off; 'continuous' lets it run via the
                // existing AccSentry peripheral keep-alive lease so the
                // user gets parked-recording without separate plumbing.
                //
                // SURVEILLANCE INTEGRATION: when oem.surveillance.enabled is
                // true the user has opted into OEM clips on motion events.
                // If we tear down at ACC OFF, the surveillance event-trigger
                // path (SurveillanceEngineGpu.startEventRecording) finds
                // pipeline=null and silently skips OEM. Treat surveillance
                // intent as an implicit "keep alive" — same path as
                // accOffMode=continuous — so motion events can fire OEM
                // recordings during sentry without the user needing to
                // toggle continuous mode separately.
                try {
                    // Post-migration mode-based dispatch (R9 regression #1).
                    // Pre-fix this read oem.enabled / oem.accOffMode — both
                    // are nulled out by migrateOemDashcamModes, so this
                    // branch silently no-op'd on every install after first
                    // boot. Now read the mode-tier accessors directly.
                    String recMode = com.overdrive.app.config.UnifiedConfigManager
                        .getOemRecordingMode();
                    String survMode = com.overdrive.app.config.UnifiedConfigManager
                        .getOemSurveillanceMode();
                    boolean anyTriggerOn = com.overdrive.app.config.UnifiedConfigManager
                        .isAnyOemDashcamTriggerEnabled();
                    // Determine whether surveillance suppression also suppresses OEM keep-alive.
                    // User explicitly opted into safe-zone privacy / schedule windows; we honor
                    // that for the OEM dashcam too.  Surveillance-side OEM modes
                    // (continuous/smart on the surv axis) record / wake during the parked
                    // window — same window the user told us to be silent in. The
                    // recording-side continuous (rec=continuous) is the user's "record across
                    // ACC OFF too" intent and intentionally bypasses surveillance suppression,
                    // mirroring how pano dashcam recording continues across ACC OFF when
                    // oem.recordingMode=continuous.
                    boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
                    boolean inSafeZone = com.overdrive.app.surveillance.SafeLocationManager.getInstance().isInSafeZone();
                    boolean outsideSchedule = false;
                    try {
                        com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                            com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
                        outsideSchedule = (schedule != null && schedule.isEnabled() && !schedule.isActiveNow());
                    } catch (Exception ignored) {}
                    boolean surveillanceSuppressed = !userEnabled || inSafeZone || outsideSchedule;
                    // Two-axis policy: recording-side modes describe drive-time
                    // intent (ACC ON only); surveillance-side modes describe
                    // parked-time intent (ACC OFF only). At ACC OFF the recording
                    // axis is dormant by design — only the surveillance axis can
                    // keep the pipeline alive. ALWAYS schedule a recalc so the
                    // resolver in OemDashcamApiHandler.applyTriggerLifecycleFromUcm
                    // is the single source of truth: the ACC boundary itself is a
                    // state change (e.g. rec=continuous,surv=smart must stop
                    // recording AND keep the pipeline warm — only the resolver
                    // can express that without duplicating the gating logic here).
                    if (anyTriggerOn) {
                        log("OEM Dashcam: ACC OFF — recalc (rec=" + recMode
                            + ", surv=" + survMode
                            + (surveillanceSuppressed ? ", survSuppressed" : "") + ")");
                        com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
                    }
                } catch (Throwable t) {
                    log("OEM Dashcam ACC OFF dispatch failed: " + t.getMessage());
                    markCurrentAccApplyRetry();
                }

                boolean onOnly = com.overdrive.app.config.UnifiedConfigManager
                        .isVehicleOnOnlyMode();
                // The trip was already finalized synchronously above, so the
                // pollers it needed can be released immediately.
                stopTripRequiredPollersForAccOff(transitionGeneration);

                if (stopStaleAccTransition(
                        transitionGeneration, true, "parked monitor teardown")) {
                    return;
                }

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
                    // ASYNC + SINGLE-FLIGHT (audit: arm/disarm notification storm).
                    // This used to be a bounded 30s join on the reconciler thread,
                    // which sat inside the 20s ACC effect lease — the lease expiry
                    // interrupted the wait, the transition marked retry, and every
                    // retry pass re-ran the full ACC-OFF lifecycle (RMM pipeline
                    // stop → "disarmed" automation → re-arm → "armed" automation)
                    // while the SD card was slow to enumerate. Mirror of the
                    // ACC-ON fix (startAccOnRemountAsync): the OFF transition
                    // proceeds immediately on internal storage; when the mount
                    // lands, StorageManager's centralized came-online path
                    // re-points writers and reindexes, and the engine's
                    // per-trigger getLiveSurveillanceDir() refresh routes each
                    // event to SD. Mount failure is the VolumeWatchdog's to
                    // retry — the transition is deliberately NOT marked for
                    // retry, because re-running the OFF chain wouldn't do
                    // anything the watchdog isn't already doing.
                    startAccOffSdMountAsync(storage);
                    // Watchdog already started at daemon boot in main(); calling
                    // startSdCardWatchdog() again is idempotent (it stops any
                    // existing watchdog before starting). Kept here as a
                    // defensive re-arm in case the previous instance died.
                    storage.startSdCardWatchdog();
                }

                if (stopStaleAccTransition(
                        transitionGeneration, true, "ACC OFF storage remount")) {
                    return;
                }

                // Check if user has enabled surveillance in config.
                // GATE (G2): also short-circuit when the "Vehicle ON only" operating
                // mode is selected — no post-vehicle-OFF surveillance may arm. This gate
                // sits AFTER all the mandatory ACC-OFF bookkeeping above (recordAccEvent,
                // trip finalize, recording finalize, telemetry/gear stop, SD force-mount +
                // watchdog, OEM lifecycle recalc) so those still run in onOnly — only the
                // sentry pipeline / arm dispatch / door-lock gate / schedule checker below
                // is skipped. Defense-in-depth with AccSentryDaemon's G1 gate (separate
                // process, reached by a different IPC path). Fail-open: false → arm as usual.
                boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
                if (onOnly) {
                    // "Vehicle ON only": all mandatory ACC-off bookkeeping above has now
                    // completed (recordAccEvent, trip finalize, recording segment finalize,
                    // telemetry/gear stop, SD force-mount). There is no post-OFF work in this
                    // mode — instead of just skipping surveillance, TERMINATE this daemon
                    // gracefully (parkTerminate closes H2/servers, plants the parked marker,
                    // kills own watchdog + self). AccSentryDaemon's reaper terminates the rest
                    // of the stack and enforces the marker. Nothing recovers until the ACC-on
                    // edge clears the marker. This call does not return (kills the process).
                    if (stopStaleAccTransition(
                            transitionGeneration, true, "parked shutdown commit")) {
                        return;
                    }
                    log("onOnly mode — ACC-off finalize complete; parkTerminate (full shutdown, sleep while parked)");
                    if (!parkTerminate(transitionGeneration)
                            && isAccTransitionCurrent(transitionGeneration, true)) {
                        markCurrentAccApplyRetry();
                    }
                    return;
                }
                if (!userEnabled) {
                    log("Surveillance NOT enabled in config — skipping auto-start on ACC OFF");
                    return;  // SD card is mounted + watchdog running
                }

                // Safe zone check — don't start surveillance if parked at home/work
                com.overdrive.app.surveillance.SafeLocationManager safeMgr =
                    com.overdrive.app.surveillance.SafeLocationManager.getInstance();
                if (safeMgr.isInSafeZone()) {
                    if (stopStaleAccTransition(
                            transitionGeneration, true, "safe-zone suppression")) {
                        return;
                    }
                    log("SAFE ZONE: Surveillance suppressed on ACC OFF — " + safeMgr.getCurrentZoneName()
                        + " (dist=" + Math.round(safeMgr.getDistanceToNearestZone()) + "m)");
                    surveillanceEnabled = true;   // Mark intent so it auto-starts when leaving zone
                    safeZoneSuppressed = true;
                    if (stopStaleAccTransition(
                            transitionGeneration,
                            true,
                            "safe-zone suppression commit")) {
                        return;
                    }
                    return;  // SD card is mounted + watchdog running, just skip surveillance
                }

                // Schedule check — don't start surveillance outside configured time windows
                try {
                    com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                        com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
                    if (schedule != null && schedule.isEnabled() && !schedule.isActiveNow()) {
                        if (stopStaleAccTransition(
                                transitionGeneration, true, "schedule suppression")) {
                            return;
                        }
                        log("SCHEDULE: Surveillance suppressed on ACC OFF — outside time window (" +
                            schedule.getSummary() + ")");
                        surveillanceEnabled = true;  // Mark intent so periodic checker can start it later
                        if (stopStaleAccTransition(
                                transitionGeneration,
                                true,
                                "schedule suppression commit")) {
                            return;
                        }
                        return;  // SD card is mounted + watchdog running, just skip surveillance
                    }
                } catch (Exception e) {
                    log("Schedule check error (proceeding with surveillance): " + e.getMessage());
                }

                Runnable startSentryPipeline = () -> {
                    if (stopStaleAccTransition(
                            transitionGeneration, true, "sentry pipeline start")) {
                        return;
                    }
                    if (!gpuPipeline.isRunning()) {
                        log("Starting pipeline for sentry mode...");
                        try { gpuPipeline.start(); } catch (Exception e) {
                            log("Pipeline start failed: " + e.getMessage());
                            // FIX (cold-boot arming race): arming did NOT complete,
                            // but lastDispatchedAccIsOff was already set true at :3533.
                            // Without clearing it, every subsequent ACC-OFF heartbeat
                            // short-circuits at the dedup guard (:3451) and arming is
                            // never retried — aiProcessed stays 0 forever. Clear the
                            // flag so the next heartbeat re-runs the full dispatch.
                            clearAccDispatchForRetry(
                                transitionGeneration,
                                true,
                                "WARN: clearing lastDispatchedAccIsOff so next ACC heartbeat re-runs sentry arming");
                            return;
                        }
                    }
                    if (stopStaleAccTransition(
                            transitionGeneration, true, "sentry pipeline initialization")) {
                        return;
                    }
                    gpuPipeline.setRecordingMode(
                        com.overdrive.app.surveillance.GpuPipelineConfig.RecordingMode.SENTRY);
                    // AVC keep-alive for sentry — same 60s poke we use during ACC-ON
                    // and streaming/recording-mode. See enableSurveillance() for why.
                    startAvcKeepAliveIfNeeded();
                    if (stopStaleAccTransition(
                            transitionGeneration,
                            true,
                            "sentry recording-mode activation")) {
                        return;
                    }
                    // Arm mode decides WHEN we arm after ACC-off:
                    //   "power" — arm immediately, no lock gate. Disarm is handled
                    //             by the ACC-ON path (cleanupDoorLockGate +
                    //             gpuPipeline.onAccOn). Deterministic on every trim.
                    //   "lock"  — arm on door-lock, disarm on unlock, with a 60s
                    //             fallback force-arm when lock state is unreadable
                    //             (see registerDoorLockListenerAndArmOnLock).
                    // Both paths still honor safe-zone + schedule suppression via
                    // enableSurveillance() and the gates above.
                    String armMode = com.overdrive.app.config.UnifiedConfigManager
                        .getSurveillanceArmMode();
                    if ("power".equals(armMode)) {
                        if (stopStaleAccTransition(
                                transitionGeneration, true, "power-mode arm")) {
                            return;
                        }
                        log("Pipeline started in sentry mode — arm mode=power (grace period 15s before arming)");
                        // Grace period of 15s to allow passenger/driver exit before motion detection starts
                        Thread powerArmThread = new Thread(() -> {
                            try {
                                Thread.sleep(15000);
                                if (stopStaleAccTransition(
                                        transitionGeneration, true, "power-mode grace-period arm")) {
                                    return;
                                }
                                if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                                    log("Power arm cancelled: ACC is ON");
                                    return;
                                }
                                log("Arming surveillance now (power mode grace period elapsed)");
                                doorLockListenerArmed =
                                    enableSurveillanceForAccGeneration(
                                        transitionGeneration, "ACC OFF power arm");
                                if (com.overdrive.app.monitor.AccMonitor.isAccOn()
                                        || safeZoneSuppressed
                                        || gpuPipeline == null || !gpuPipeline.isRunning()) {
                                    log("Arm mode=power: pipeline not running after enable "
                                        + "(safeZone=" + safeZoneSuppressed + ") — reverting armed flag");
                                    doorLockListenerArmed = false;
                                }
                            } catch (InterruptedException ignored) {
                                log("Power arm grace period interrupted");
                            }
                        }, "PowerArmGraceThread");
                        powerArmThread.start();
                        // Still need the ACC-ON disarm watchdog as the reverse
                        // fallback (ACC turns ON without an IPC reaching us).
                        startAccOnDisarmWatchdog(transitionGeneration);
                    } else {
                        // Door lock gate: surveillance is armed after doors lock,
                        // disarmed on unlock, force-armed at 60s if lock state is
                        // unreadable. Prevents false motion events from the owner
                        // exiting the car while still arming on trims that can't
                        // report lock state. Sources fire concurrently (cloud MQTT,
                        // 5s OTA poll); ACC-ON disarm watchdog runs as reverse
                        // fallback.
                        log("Pipeline started in sentry mode — arm mode=lock, waiting for door lock to arm surveillance");
                        registerDoorLockListenerAndArmOnLock(transitionGeneration);
                    }

                    // SOTA: Periodic schedule checker — monitors time window transitions
                    // during active sentry. If the schedule window ends, surveillance stops.
                    // If the window starts (e.g., user parked before the window), surveillance starts.
                    // Runs every 5 minutes. Only active when ACC is off.
                    startScheduleChecker(transitionGeneration);

                    log("Pipeline started in sentry mode");
                };

                if (isDilink4ModeActive()) {
                    // No delay before camera open on dilink4 — retained
                    // deliberately (an added gate regressed arming on-device).
                    //
                    // CORRECTION to the previous note here: the reference app's
                    // FlameoutService 60s timer (dh/i.w(60000) → le.b.t()) IS what
                    // starts its sentry camera consumer, so it does open the camera
                    // ~60s after ACC-off, not merely schedule a secondary consumer.
                    // We still do NOT copy that delay; the ordering problem it was
                    // masking (camera opened before the AVM/ISP rail was held) is
                    // fixed causally instead — AccSentryDaemon.enterSentryMode now
                    // casts the dilink4 rail hold INLINE, before the ACC-OFF IPC
                    // that gets us here. So by this point the rail is already live
                    // and arming immediately is correct.
                    //
                    // ensureAvcAlive() (pidof on dilink4 — no am start)
                    // probes AVC presence without launching it.
                    try {
                        com.overdrive.app.camera.AvcHalWarmup.ensureAvcAlive();
                    } catch (Throwable th) {
                        log("AVC initial probe failed: " + th.getMessage());
                    }
                }
                startSentryPipeline.run();
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (errorMsg == null) {
                    errorMsg = e.getClass().getSimpleName();
                }
                log("ERROR: Failed to start pipeline for sentry: " + errorMsg);
                e.printStackTrace();
                // FIX (cold-boot arming race, observed 2026-06-28): a transient
                // encoder-init failure (MediaCodec.createInputSurface IllegalStateException
                // at cold boot) can null gpuPipeline between the :3516 guard and the
                // setRecordingMode() lambda at :3695, throwing an NPE here. We caught it,
                // but lastDispatchedAccIsOff was already set true at :3533 — so every
                // 60s ACC-OFF heartbeat afterward hits the dedup no-op (:3451) and arming
                // is never retried (aiProcessed=0 forever). Mirror the ACC-ON self-heal
                // (:3867/:3916/:3956): clear the dedup flag so the next heartbeat re-runs
                // the full sentry-arming dispatch once initSurveillance recovers the pipeline.
                clearAccDispatchForRetry(
                    transitionGeneration,
                    true,
                    "WARN: clearing lastDispatchedAccIsOff so next ACC heartbeat re-runs sentry arming");
            }
        } else {
            // ACC ON. We intentionally leave the SD-card watchdog running here:
            // BYD/Android can unmount the SD even with ACC on, and stopping the
            // watchdog created a window where the HTTP server returned empty
            // recordings until the user cycled ACC OFF→ON. The watchdog is
            // started at daemon boot in main() and runs for the daemon's
            // lifetime as long as any storage type is set to SD.

            // Active force-remount of the configured external volume at wake.
            // The USB-bridged SD (SCSI major 8) loses power across the ACC-off→on
            // transition — the reader rail follows AP/display wake, so the bus
            // re-enumerates and the card drops for the first several seconds of
            // the drive. The ACC-OFF branch above force-remounts; without the
            // symmetric call here, ACC-ON recovery fell entirely to the 15s
            // VolumeWatchdog, which needs TWO consecutive failed ticks before it
            // even tries a remount and then races the 4-15s slow-mount tail of the
            // re-powering reader → a 30s+ window where the card reads "not
            // detected" with the car ON. remountExternalOnAccOn() drives it back
            // at wake and resets the two-strikes counters.
            //
            // ASYNC (audit: daemon-restart loop). This used to run INLINE on
            // the ACC dispatch thread ("may block, but holds no transition
            // monitor") — and field logs showed mounts of 50-90s (worst 281s)
            // against the 20s probe-edge dispatch lease. The lease expiry
            // interrupted a thread parked on the mount lock (monitor entry is
            // not interruptible — the cancel can never land), the 2s grace
            // always expired, and requestHardwareQueryProcessRecovery killed
            // the daemon: 10 of 12 restarts in one 68-min drive, each costing
            // 21-63s of recording. The remount is best-effort side work, not
            // a precondition of the ON transition: resolveActive falls back
            // to internal until the mount lands, and the landing itself
            // triggers dir init + active-dir update + the index notify via
            // the centralized came-online path. So: fire it on a dedicated
            // worker and let the dispatch thread move on. Single-flight —
            // repeat ON chains must not stack workers that would serialize
            // on the mount lock behind each other.
            if (stopStaleAccTransition(
                    transitionGeneration, false, "ACC ON external remount start")) {
                return;
            }
            startAccOnRemountAsync();

            // Stop schedule checker (only runs during ACC OFF sentry mode)
            stopScheduleChecker();

            // Stop door lock gate: detach cloud + device-SDK listeners, stop
            // unlock poll, stop ACC-ON disarm watchdog.
            cleanupDoorLockGate();

            // Reset surveillance intent flag. The safe-zone-suppressed and
            // schedule-suppressed branches in the ACC OFF handler set
            // surveillanceEnabled=true *without* actually arming, as an
            // "intent" marker for later re-evaluation. Once ACC turns ON
            // those branches no longer apply, and leaving the flag set
            // misleads the next cycle's force-arm timeout (which gates on it)
            // and the schedule checker. enableSurveillance() will set it
            // again next time the lock gate or schedule fires.
            if (surveillanceEnabled) {
                log("ACC ON: clearing sticky surveillanceEnabled flag");
                surveillanceEnabled = false;
            }

            // Clear safe-zone suppression flag. It was set during the prior
            // ACC OFF in a safe zone to record "would have armed surveillance,
            // but suppressed by geofence." Once the user has turned ACC back
            // ON the suppression no longer applies — recording modes
            // (CONTINUOUS / DRIVE_MODE / PROXIMITY_GUARD) handle their own
            // activation independent of surveillance state. Without this
            // clear, the daemon status JSON keeps reporting safeZoneSuppressed=true
            // until the GPS poller eventually notices the boundary crossing,
            // which can be minutes after driving away.
            if (safeZoneSuppressed) {
                log("Clearing safeZoneSuppressed flag on ACC ON (was set during last sentry suppression)");
                safeZoneSuppressed = false;
            }

            // Recreate app context if it was broken (system server was dead during init).
            // createAppContext() can block, but no transition monitor is held; a newer
            // generation can proceed and the post-call lease check compensates it.
            if (isContextBroken()) {
                if (stopStaleAccTransition(
                        transitionGeneration, false, "context recreation start")) {
                    return;
                }
                log("ACC ON: sharedAppContext is broken — attempting recreation...");
                android.content.Context newContext = createAppContext();
                if (stopStaleAccTransition(
                        transitionGeneration, false, "context recreation lookup")) {
                    return;
                }
                if (newContext != null && !isContextBrokenFor(newContext)) {
                    sharedAppContext = newContext;
                    log("ACC ON: App context recreated successfully");

                    // Re-init components that failed with the broken context
                    reinitContextDependentComponents();
                    if (stopStaleAccTransition(
                            transitionGeneration, false, "context-dependent reinit")) {
                        return;
                    }

                    // Now start GearMonitor if it still isn't running
                    com.overdrive.app.monitor.GearMonitor gm =
                        com.overdrive.app.monitor.GearMonitor.getInstance();
                    if (!gm.isRunning()) {
                        try {
                            gm.start();
                            log("ACC ON: GearMonitor started after context recreation");
                        } catch (Exception e) {
                            log("ACC ON: GearMonitor start failed after recreation: "
                                + e.getMessage());
                            markCurrentAccApplyRetry();
                        }
                    }

                    // Notify RecordingModeManager of current gear now that GearMonitor works
                    if (recordingModeManager != null && gm.isRunning()) {
                        recordingModeManager.onGearChanged(gm.getCurrentGear());
                        if (stopStaleAccTransition(
                                transitionGeneration,
                                false,
                                "context-recreate gear publication")) {
                            return;
                        }
                    }
                } else {
                    log("ACC ON: Context recreation failed — system services may still be starting");
                    markCurrentAccApplyRetry();
                }
            }

            // Restart GearMonitor (stopped on ACC OFF)
            com.overdrive.app.monitor.GearMonitor gearMonitor = com.overdrive.app.monitor.GearMonitor.getInstance();
            if (!gearMonitor.isRunning()) {
                try {
                    gearMonitor.start();
                    log("GearMonitor restarted (ACC ON)");
                } catch (Exception e) {
                    log("GearMonitor restart failed (ACC ON): " + e.getMessage());
                    markCurrentAccApplyRetry();
                }
            }

            // Tell BydDataCollector to resume full polling (speed/engine/gearbox)
            com.overdrive.app.byd.BydDataCollector.getInstance().setAccState(true);

            if (stopStaleAccTransition(
                    transitionGeneration, false, "active monitor restoration")) {
                return;
            }

            // If pipeline is currently in SURVEILLANCE mode, gracefully exit it:
            // finalize any in-progress sentry recording, flush the encoder, drop
            // out of SURVEILLANCE, and reopen the camera so BYD's native AVM app
            // can grab the primary slot. Skipped when not in surveillance —
            // calling onAccOn() in steady-state NORMAL_RECORDING would stop the
            // active recording and reopen the camera, which is exactly the
            // regression we're avoiding for duplicate ACC ON IPCs.
            if (gpuPipeline != null && gpuPipeline.isSurveillanceMode()) {
                try {
                    gpuPipeline.onAccOn();
                    if (stopStaleAccTransition(
                            transitionGeneration, false, "surveillance exit")) {
                        return;
                    }
                } catch (Exception e) {
                    log("gpuPipeline.onAccOn() error: " + e.getMessage()
                        + " — forcing pipeline.stop() to clear wedge state");
                    // Compensating teardown: a half-failed surveillance->normal
                    // transition leaves running=true with a dead camera handle,
                    // and the next pipeline.start() short-circuits because
                    // isRunning() returns true. Force stop() so the next
                    // recordingModeManager.onAccStateChanged(true) below sees a
                    // cleanly-stopped pipeline and runs a fresh start(false).
                    try {
                        gpuPipeline.stop();
                    } catch (Throwable t) {
                        log("Compensating pipeline.stop() also failed: " + t.getMessage());
                    }
                    // FIX (audit R7, finding "Dedup cache set before side-effects
                    // complete"): we set lastDispatchedAccIsOff=ACC_ON above
                    // BEFORE running this side-effect chain. If onAccOn() threw
                    // and the compensating stop ran, the pipeline is now down
                    // but the dedup cache says "ACC ON fully dispatched". The
                    // next AccSentry 30s heartbeat would no-op via the dedup
                    // guard at the top of this method and pano CONTINUOUS /
                    // DRIVE_MODE recording would stay dead until a manual ACC
                    // cycle. Null the cache so the next heartbeat re-enters
                    // and reruns the full chain (including
                    // recordingModeManager.onAccStateChanged(true) / RMM
                    // wedge-resync) against the now-cleanly-stopped pipeline.
                    clearAccDispatchForRetry(
                        transitionGeneration,
                        false,
                        "WARN: clearing lastDispatchedAccIsOff so next ACC heartbeat re-runs full ACC ON dispatch");
                }
            } else if (gpuPipeline != null && gpuPipeline.isRecording()) {
                // FIX (false-GREEN PROX pill at ACC-ON): NOT in surveillance
                // mode, so the onAccOn() drain above was skipped — but the
                // recorder still reports isRecording()=true. That is a STALE
                // leftover clip from the parked window (most often a proximity
                // radar trigger whose recorder survived the ACC-OFF→ON
                // transition without being finalized), NOT a genuinely-live
                // drive recording (a fresh ACC-ON activation has not started
                // one yet at this point). Left uncleared, /status reports
                // isRecording=true and the overlay paints a false GREEN "PROX"
                // until the first real trigger's stop clears it.
                //
                // Drain ONLY the recording (stopRecording finalizes the stale
                // clip) — deliberately NOT the full onAccOn() (which also
                // reopens the camera; the comment above documents why that is
                // a regression for steady-state NORMAL_RECORDING / duplicate
                // ACC-ON IPCs). stopRecording() is idempotent and cheap.
                try {
                    log("ACC ON - draining stale leftover recording (not in surveillance mode, "
                        + "isRecording()=true before fresh activation) to clear false-GREEN pill");
                    gpuPipeline.stopRecording();
                } catch (Throwable t) {
                    log("Stale-recording drain on ACC ON failed: " + t.getMessage());
                    markCurrentAccApplyRetry();
                }
                if (stopStaleAccTransition(
                        transitionGeneration, false, "stale-recording drain")) {
                    return;
                }
            }

            // Notify RecordingModeManager — it handles starting recording mode
            log("ACC ON - notifying RecordingModeManager...");
            if (recordingModeManager != null) {
                // FIX (audit R8, finding "notifyAccState treats {success:false}
                // IPC reply as success → seeds dedup against a partial-apply"):
                // recordingModeManager.onAccStateChanged(true) can throw under
                // pipeline wedge / HAL flake. The outer ACC-ON else branch
                // here lacks an outer try/catch, so the IPC reply path
                // (SurveillanceIpcServer outer try) returns success:false and
                // AccSentry's heartbeat dedup seeds against a partial apply.
                // Mirror the gpuPipeline.onAccOn() compensating teardown:
                // null lastDispatchedAccIsOff so the next AccSentry heartbeat
                // re-runs the full chain instead of being suppressed by the
                // dedup guard at the top of this method.
                try {
                    recordingModeManager.onAccStateChanged(true);
                    if (stopStaleAccTransition(
                            transitionGeneration, false, "recording-mode restoration")) {
                        return;
                    }
                } catch (Throwable t) {
                    log("WARN: recordingModeManager.onAccStateChanged(true) threw: "
                        + t.getMessage()
                        + " — clearing lastDispatchedAccIsOff so next ACC heartbeat re-runs");
                    clearAccDispatchForRetry(
                        transitionGeneration,
                        false,
                        "WARN: clearing lastDispatchedAccIsOff so next ACC heartbeat re-runs full ACC ON dispatch");
                }
            }
            // OEM Dashcam ACC-on hook. The ACC boundary itself is a state
            // transition for the two-axis resolver — recording-axis modes only
            // arm during ACC ON, surveillance-axis modes only arm during ACC
            // OFF. Always recalc when any trigger is on, regardless of whether
            // the pipeline is currently live (it may have been kept warm by
            // surv=smart during sentry, in which case rec=continuous still
            // needs to flip recording on now).
            try {
                if (com.overdrive.app.config.UnifiedConfigManager
                        .isAnyOemDashcamTriggerEnabled()) {
                    log("OEM Dashcam: ACC ON — recalc");
                    com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
                }
            } catch (Throwable t) {
                log("OEM Dashcam ACC ON dispatch failed: " + t.getMessage());
                markCurrentAccApplyRetry();
            }
            if (recordingModeManager == null) {
                // Previously this branch tore down the pipeline as "legacy
                // power-save fallback." That races initSurveillance(): if the
                // ACC ON IPC arrives before the manager is constructed, we'd
                // stop the pipeline that init was about to wire up, and the
                // pendingAccOn drain would then fire onAccStateChanged on a
                // pipeline we just killed. Leave the pipeline alone — a future
                // component (manager init drain, OEM resolver, surveillance
                // event) will start/stop it as needed.
                log("WARNING: recordingModeManager null on ACC ON — "
                    + "recording disabled until daemon restart or init completes");
                // FIX (audit R8, finding "ACC ON dispatch sets lastDispatchedAccIsOff
                // before checking rmm==null branch"): we set
                // lastDispatchedAccIsOff=ACC_ON above before reaching this
                // branch. RMM was never notified, so the next AccSentry
                // heartbeat IPC must re-run the full chain. Null the cache
                // (mirrors the R7 pattern at the gpuPipeline.onAccOn() catch
                // and the new R8 RMM-throw pattern above) so the dedup guard
                // doesn't suppress the heartbeat after watchdog/ContextRecreate
                // eventually creates rmm.
                clearAccDispatchForRetry(
                    transitionGeneration,
                    false,
                    "WARN: clearing lastDispatchedAccIsOff so next ACC heartbeat re-runs full ACC ON dispatch");
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
    private static volatile int lastNotifiedGear = Integer.MIN_VALUE;

    public static void onGearChanged(int gear) {
        long observedElapsedMs =
                android.os.SystemClock.elapsedRealtime();
        long observedEpochMs = System.currentTimeMillis();
        String gearName = com.overdrive.app.recording.RecordingModeManager.gearToString(gear);

        // GearMonitor primes the system with one initial notification on
        // start(); subsequent rapid duplicates can also slip through during
        // ACC ON re-init. Skip logging when the gear value is unchanged from
        // the last notification — downstream listeners already short-circuit
        // duplicate gears, but the daemon log shouldn't keep restating it.
        boolean redundant = (gear == lastNotifiedGear);
        lastNotifiedGear = gear;
        if (!redundant) {
            log("Gear changed to: " + gearName);
        }

        // Feed trip detection before recording-mode callbacks can block. A gear
        // edge arriving before the trip database finishes opening is dropped
        // here; the detector re-probes the live gear on publication.
        com.overdrive.app.trips.TripAnalyticsManager tripManager =
                tripAnalyticsManager;
        if (tripManager != null) {
            try {
                tripManager.onGearChanged(gear);
            } catch (Throwable failure) {
                log("Trip Analytics gear " + gearName + " error: "
                        + failure.getMessage());
            }
        }

        if (recordingModeManager != null) {
            recordingModeManager.onGearChanged(gear);
        } else if (!redundant) {
            log("RecordingModeManager not initialized - gear change ignored");
        }

        // Feed the AUTOMATION engine too, off the FAST GearMonitor path (200ms, runs
        // regardless of ACC). Previously the automation GEAR event was published ONLY
        // from BydEvent.bydEvent — i.e. the telemetry snapshot build(), whose gearMode
        // is collected ONLY while ACC is on (collectGearbox is ACC-gated) and at the
        // slow parked cadence. That made "when gear → P" (and any gear rule) unreliable:
        // P is the terminal PARKED gear, reached right as ACC turns off, so the snapshot
        // path often never saw the transition. Publishing here — the same value format
        // BydEvent uses (gearToString lowercased: p/r/n/d/m/s, matching the trigger's
        // option ids) — gives gear automations the same fast, ACC-independent delivery
        // recording/trips already get. Automations.update is level-triggered + dedups,
        // so this is idempotent with the snapshot path when both fire. Best-effort:
        // never let an automation publish failure disrupt gear notification.
        try {
            com.overdrive.app.automation.Automations.update(
                    com.overdrive.app.automation.condition.BydEvent.GEAR,
                    com.overdrive.app.monitor.GearMonitor.gearToString(gear).toLowerCase());
        } catch (Throwable t) {
            if (!redundant) log("gear automation publish failed: " + t.getMessage());
        }
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

    /**
     * True while the door-lock gate has fired and surveillance is genuinely
     * armed (owner has stepped away). Cleared on unlock or ACC ON. Used by
     * the mode-switch restart path to distinguish "user wants surveillance
     * generally" (UnifiedConfig.isSurveillanceEnabled) from "surveillance is
     * actually live right now" — the latter must be true to safely re-arm
     * after a stop+restart, otherwise an unlock-during-restart would re-arm
     * a session the owner just walked back into.
     */
    public static boolean isDoorLockArmed() {
        return doorLockListenerArmed;
    }

    public static void setSafeZoneSuppressed(boolean suppressed) {
        safeZoneSuppressed = suppressed;
    }

    // ==================== SCHEDULE CHECKER ====================

    private static final Object scheduleCheckerLock =
        new Object();
    private static Thread scheduleCheckerThread = null;
    private static long scheduleCheckerGeneration;

    /**
     * Starts the periodic schedule checker that monitors time window transitions.
     * Runs every 5 minutes while ACC is off. Stops when ACC turns on.
     */
    private static void startScheduleChecker(long transitionGeneration) {
        Thread previous;
        synchronized (scheduleCheckerLock) {
            previous = scheduleCheckerThread;
            if (previous != null && previous.isAlive()
                    && scheduleCheckerGeneration
                        == transitionGeneration) {
                return;
            }
        }
        if (previous != null && previous.isAlive()
                && !interruptAndJoinManagedThread(
                    previous, "schedule checker")) {
            requestManagedAccWorkerRecovery(
                "stuck schedule checker");
            return;
        }

        final Thread worker;
        try {
            worker = new Thread(new Runnable() {
            public void run() {
                try {
                log("Schedule checker started (5-min interval)");
                while (!Thread.currentThread().isInterrupted()
                        && isAccTransitionCurrent(transitionGeneration, true)) {
                    try {
                        Thread.sleep(5 * 60 * 1000);  // 5 minutes
                    } catch (InterruptedException e) {
                        break;
                    }

                    // Only check when ACC is off
                    if (!isAccTransitionCurrent(transitionGeneration, true)) {
                        break;
                    }
                    if (!validateAccOffForDeferredEffect(
                            transitionGeneration, "schedule-check")) {
                        if (!isAccTransitionCurrent(transitionGeneration, true)) {
                            break;
                        }
                        continue;
                    }

                    try {
                        com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                            com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();

                        // Schedule disabled = always active, no window to check — but
                        // still SELF-HEAL a dead sentry pipeline. Without a schedule
                        // the window-transition block below never runs, so a pipeline
                        // torn down mid-park (e.g. the live-view WebSocket idle-timeout
                        // auto-stop, or any stop() that fired while armed) would stay
                        // dead until the user manually toggles surveillance or cycles
                        // ACC — the "have to turn it off and on again" symptom. Re-arm
                        // when we still INTEND to be watching but the pipeline isn't
                        // actually in surveillance mode.
                        //
                        // Gate on doorLockListenerArmed (the authoritative "we armed
                        // this park" truth, set by both power- and lock-arm paths and
                        // reverted on unlock / ACC-ON / declined-enable — but NOT by a
                        // pipeline teardown). This is deliberately NOT surveillanceEnabled,
                        // which is a looser intent flag that can read true even when lock
                        // mode intentionally stayed disarmed on an unlocked car. When a
                        // schedule IS configured, the in-window branch below already
                        // self-heals (it re-enables when currentlyActive is false), so
                        // this only covers the no-schedule case.
                        if (schedule == null || !schedule.isEnabled()) {
                            boolean intendWatching = doorLockListenerArmed && !safeZoneSuppressed;
                            boolean actuallyRunning = gpuPipeline != null
                                && gpuPipeline.isRunning() && gpuPipeline.isSurveillanceMode();
                            if (intendWatching && !actuallyRunning
                                    && com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled()) {
                                if (!isAccTransitionCurrent(transitionGeneration, true)) {
                                    break;
                                }
                                log("SELF-HEAL: surveillance armed but pipeline not in sentry mode "
                                    + "(pipeline=" + (gpuPipeline != null)
                                    + ", running=" + (gpuPipeline != null && gpuPipeline.isRunning())
                                    + ", survMode=" + (gpuPipeline != null && gpuPipeline.isSurveillanceMode())
                                    + ") — re-enabling");
                                enableSurveillanceForAccGeneration(
                                    transitionGeneration, "schedule self-heal");
                            }
                            continue;
                        }

                        boolean withinWindow = schedule.isActiveNow();
                        boolean currentlyActive = surveillanceEnabled && gpuPipeline != null
                                && gpuPipeline.isSurveillanceMode();

                        if (!withinWindow && currentlyActive) {
                            // Schedule window ended — stop surveillance
                            if (!isAccTransitionCurrent(transitionGeneration, true)) {
                                break;
                            }
                            log("SCHEDULE: Time window ended (" + schedule.getSummary() +
                                ") — stopping surveillance");
                            disableSurveillance();
                            if (stopStaleAccTransition(
                                    transitionGeneration,
                                    true,
                                    "schedule surveillance disable")) {
                                break;
                            }
                        } else if (withinWindow && !currentlyActive && !safeZoneSuppressed) {
                            // Schedule window started — enable surveillance if other conditions met
                            boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager
                                .isSurveillanceEnabled();
                            if (userEnabled) {
                                if (!isAccTransitionCurrent(transitionGeneration, true)) {
                                    break;
                                }
                                log("SCHEDULE: Time window started (" + schedule.getSummary() +
                                    ") — enabling surveillance");
                                enableSurveillanceForAccGeneration(
                                    transitionGeneration, "schedule window start");
                            }
                        }
                    } catch (Exception e) {
                        log("Schedule checker error: " + e.getMessage());
                    }
                }
                log("Schedule checker stopped");
                } finally {
                    boolean ownedSlot = false;
                    synchronized (scheduleCheckerLock) {
                        if (scheduleCheckerThread
                                == Thread.currentThread()) {
                            scheduleCheckerThread = null;
                            scheduleCheckerGeneration = 0L;
                            ownedSlot = true;
                        }
                    }
                    if (ownedSlot && running.get()
                            && isAccTransitionCurrent(
                                transitionGeneration, true)) {
                        requestManagedAccWorkerRecovery(
                            "schedule checker exited");
                    }
                }
            }
        }, "ScheduleChecker");
            worker.setDaemon(true);
        } catch (Throwable creationFailure) {
            requestManagedAccWorkerRecovery(
                "schedule checker creation failure");
            return;
        }

        synchronized (scheduleCheckerLock) {
            Thread current = scheduleCheckerThread;
            if (current != null && current.isAlive()
                    && current != previous) {
                return;
            }
            scheduleCheckerThread = worker;
            scheduleCheckerGeneration = transitionGeneration;
        }
        try {
            worker.start();
        } catch (Throwable startFailure) {
            synchronized (scheduleCheckerLock) {
                if (scheduleCheckerThread == worker) {
                    scheduleCheckerThread = null;
                    scheduleCheckerGeneration = 0L;
                }
            }
            requestManagedAccWorkerRecovery(
                "schedule checker start failure");
        }
    }

    /**
     * Stops the periodic schedule checker.
     */
    private static void stopScheduleChecker() {
        Thread worker;
        synchronized (scheduleCheckerLock) {
            worker = scheduleCheckerThread;
        }
        if (interruptAndJoinManagedThread(
                worker, "schedule checker")) {
            synchronized (scheduleCheckerLock) {
                if (scheduleCheckerThread == worker) {
                    scheduleCheckerThread = null;
                    scheduleCheckerGeneration = 0L;
                }
            }
        }
    }

    /**
     * Check if surveillance is actively processing.
     */
    public static boolean isSurveillanceActive() {
        return gpuPipeline != null && gpuPipeline.isRunning();
    }

    /**
     * Set recording quality tier — single user-facing knob that bundles
     * bitrate + perceptual quality. Accepts the new tier names
     * (ECONOMY/STANDARD/HIGH/PREMIUM/MAX). Anything else falls back to
     * STANDARD per the migration policy.
     */
    public static void setRecordingQuality(String quality) {
        if (gpuPipeline == null) return;
        if (gpuPipeline.getConfig() == null) {
            log("setRecordingQuality: config is null, skipping");
            return;
        }

        com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality tier =
            com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality.fromString(quality);

        gpuPipeline.getConfig().setRecordingQuality(tier);
        int effectiveBitrate = gpuPipeline.getConfig().getEffectiveBitrate();
        gpuPipeline.applyBitrateChange(effectiveBitrate);
        log("Recording quality set to: " + tier
            + " (" + effectiveBitrate / 1_000_000 + " Mbps for "
            + gpuPipeline.getConfig().getVideoCodec() + ")");
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
     * @deprecated use {@link #setRecordingQuality(String)} with one of
     *             ECONOMY / STANDARD / HIGH / PREMIUM / MAX. Old LOW/MEDIUM/
     *             HIGH bitrate strings are mapped to the closest tier.
     */
    @Deprecated
    public static void setRecordingBitrate(String bitrate) {
        if (bitrate == null) return;
        String tier;
        switch (bitrate.toUpperCase()) {
            case "LOW":    tier = "ECONOMY"; break;
            case "MEDIUM": tier = "STANDARD"; break;
            case "HIGH":   tier = "HIGH"; break;
            default:       tier = "STANDARD"; break;
        }
        log("setRecordingBitrate(" + bitrate + ") → mapping to recordingQuality=" + tier);
        setRecordingQuality(tier);
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
     * Get current recording quality tier (ECONOMY..MAX).
     * Canonical accessor — prefer this over the deprecated bitrate alias.
     */
    public static String getRecordingQuality() {
        if (gpuPipeline == null || gpuPipeline.getConfig() == null) return "STANDARD";
        return gpuPipeline.getConfig().getRecordingQuality().name();
    }

    /**
     * Get current recording bitrate setting.
     * @deprecated Use {@link #getRecordingQuality()} for the canonical tier.
     */
    @Deprecated
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

    /**
     * Get the OEM Dashcam pipeline instance (or null if not started).
     */
    public static com.overdrive.app.camera.OemDashcamPipeline getOemDashcamPipeline() {
        return oemDashcamPipeline;
    }

    /**
     * Get the shared TelemetryDataCollector. Null until pano pipeline
     * initialises it (line 1856-1858). OEM lifecycle injects this so its
     * overlay refcount discipline can hold polling like pano does.
     */
    public static com.overdrive.app.telemetry.TelemetryDataCollector getTelemetryDataCollector() {
        return telemetryDataCollector;
    }

    /**
     * Generation counter for OEM dashcam pipeline instances. Bumped on
     * every {@link #setOemDashcamPipeline} call. Surveillance captures
     * the value at event-trigger time and compares on event-end so a
     * stop call only fires against the SAME pipeline instance that
     * issued tryStartIfIdle. Without this, a quality-mirror restart
     * (which tears down and rebuilds the pipeline) would let
     * surveillance's event-end stop the user's NEW continuous recording.
     */
    private static final java.util.concurrent.atomic.AtomicInteger
        oemDashcamPipelineGeneration = new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Set the OEM Dashcam pipeline reference. Called by RecordingModeManager
     * after it constructs / starts the pipeline. Setting null indicates the
     * pipeline has been torn down.
     */
    public static void setOemDashcamPipeline(com.overdrive.app.camera.OemDashcamPipeline p) {
        oemDashcamPipeline = p;
        oemDashcamPipelineGeneration.incrementAndGet();
    }

    /** Read the current pipeline generation. Surveillance compares this
     *  on event-end against what it captured at event-start. */
    public static int getOemDashcamPipelineGeneration() {
        return oemDashcamPipelineGeneration.get();
    }

    /**
     * Switch the WebSocket stream sink to the OEM Dashcam encoder. Called by
     * StreamingApiHandler when view mode 6 is selected. Returns true if the
     * routing actually attached, false otherwise — caller surfaces an
     * honest error to the client when the underlying gates refuse.
     */
    public static boolean routeStreamToOemDashcam() {
        if (oemDashcamPipeline == null) {
            log("routeStreamToOemDashcam: pipeline null; ignoring");
            return false;
        }
        if (gpuPipeline == null) {
            log("routeStreamToOemDashcam: gpuPipeline null; ignoring");
            return false;
        }
        try {
            return gpuPipeline.attachExternalStreamCallback(oemDashcamPipeline);
        } catch (Throwable t) {
            log("routeStreamToOemDashcam: attach failed: " + t.getMessage());
            return false;
        }
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
                // cameraUserRegistered intentionally omitted — registerCameraUser is
                // permanently DISABLED, the value is always false. Polling fallback
                // is the only live path. See BydCameraCoordinator.register().
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
                // Self-heal for older installs: the legacy saveDeviceId()
                // didn't chmod the file, leaving it at the shell-UID-only
                // mode 0600 default. The app UID couldn't read it, so
                // CredentialCipher.deriveKey() threw and every stored
                // credential failed to decrypt. setReadable(true, false) is idempotent
                // — no-op if it's already world-readable from a recent
                // install. Apply on every daemon start so a re-deploy
                // repairs older devices automatically.
                try {
                    idFile.setReadable(true, false);
                    idFile.setWritable(true, false);
                } catch (Exception ignored) {}
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

        // No existing file: mint a fresh device id. This is the sole seed for
        // CredentialCipher's AES key (protects the Telegram bot token, BYD
        // Cloud password, NavMap routing key), so it must be unpredictable —
        // NOT derived from Build.SERIAL (only a 32-bit hash, and the serial is
        // often readable by other apps / shown on the vehicle's info screen)
        // or Build.FINGERPRINT (identical across every unit running the same
        // firmware build, so every car of that model+version would share the
        // exact same derived key). SecureRandom removes both shortcuts.
        byte[] randomBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(randomBytes);
        StringBuilder hex = new StringBuilder("byd-");
        for (byte b : randomBytes) {
            hex.append(String.format(java.util.Locale.US, "%02x", b));
        }
        deviceId = hex.toString();
        saveDeviceId(deviceId);
        log("Device ID generated randomly: " + deviceId);
    }

    private static void saveDeviceId(String id) {
        try {
            File idFile = new File(PATH_DEVICE_ID_FILE());
            java.io.FileWriter writer = new java.io.FileWriter(idFile);
            writer.write(id);
            writer.close();
            // Files created in " + ScratchPaths.getDir() + " by the shell-UID daemon land
            // at mode 0600 owned by shell. The app UID can't read them at
            // that mode, so CredentialCipher.readDid() returns null, deriveKey()
            // throws, and every encrypted credential (telegram bot token,
            // BYD-cloud password) fails to decrypt in the app process.
            // Set world-readable so both UIDs read the same DID and derive
            // the same key. setWritable too so the app can update the DID
            // if a future migration ever needs to.
            idFile.setReadable(true, false);
            idFile.setWritable(true, false);
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

        // Load libod.so via explicit path too — System.loadLibrary("od") can't
        // resolve by name in the app_process daemon (same reason as surveillance).
        // Without this, Od.resolve() returns zeros in the daemon and the view-7/8
        // stitch shader gets all-zero coefficients → black blind-spot stream.
        if (nativeLibDir != null) {
            boolean odLoaded = com.overdrive.app.od.Od.tryLoadLibrary(nativeLibDir);
            log("od native lib loaded (daemon): " + odLoaded);
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
        DaemonLogger.Config cfg = DaemonLogger.Config.defaults()
            .withStdoutLog(true)  // Enable stdout for daemon processes
            .withFileLog(true)
            .withConsoleLog(true);
        // The default minLevel is INFO and withMinLevel() was never called anywhere, so every
        // logger.debug(...) in the whole daemon was discarded at runtime. That silently gutted the
        // BYD_TELEMETRY capture: the lines explaining WHY a HAL read failed (accessor-width misses,
        // "Could not resolve deviceType", manager-unavailable reasons) are all debug-level, so a
        // capture showed the symptom and never the cause. Lower the floor only when that flag is on,
        // so normal builds keep INFO and no extra I/O.
        if (DaemonLogConfig.BYD_TELEMETRY) {
            cfg = cfg.withMinLevel(DaemonLogger.Level.DEBUG);
        }
        DaemonLogger.configure(cfg);
        log("=== CameraDaemon Log Started ===");
    }

    public static void log(String message) {
        logger.info(message);
    }

    // ==================== NOTIFICATIONS ====================

    /**
     * Idempotency guard. Once the registry + sinks are wired, repeat calls
     * are no-ops.
     */
    private static volatile boolean notificationsInitialized = false;

    /**
     * Initialize the Web Push notification subsystem. Loads the category
     * registry from APK assets, opens persistent stores under
     * {@code " + ScratchPaths.getDir() + "/.push/}, registers PushSink + LogSink with
     * NotificationBus, and wires NotificationApiHandler so HTTP routes can
     * resolve.
     */
    public static synchronized void initNotifications() throws Exception {
        if (notificationsInitialized) return;

        com.overdrive.app.notifications.CategoryRegistry registry = null;

        // The registry JSON ships in the APK assets. Use the cached
        // sharedAppContext if already populated; Do not create one
        // as it breaks in a thread
        android.content.Context appContext = getAppContext();
        if (appContext != null) {
            try {
                registry = com.overdrive.app.notifications.CategoryRegistry.loadFromAssets(appContext);
            } catch (Exception e) {
                log("Failed to load notifications-categories.json: " + e.getMessage());
            }
        }
        if (registry == null) {
            // sharedAppContext wasn't populated yet (boot-time race — see the
            // context watchdog) or the asset read threw. Do NOT latch
            // notificationsInitialized: leaving it false lets a later caller
            // re-drive this once the context comes up. Without a re-drive the
            // whole subsystem — HistorySink, NotificationStore, and the
            // /api/notifications/log route — stays dark for the entire daemon
            // session (503 → empty Log tab), while Telegram (separate process,
            // its own IPC) keeps working, masking the failure. The context
            // watchdog's reinitContextDependentComponents() retries this.
            log("Notification registry unavailable; will retry when app context is ready.");
            return;
        }

        java.io.File pushDir = new java.io.File(ScratchPaths.path(".push"));
        if (!pushDir.exists()) pushDir.mkdirs();

        com.overdrive.app.notifications.push.VapidKeyStore keyStore =
                new com.overdrive.app.notifications.push.VapidKeyStore(
                        new java.io.File(pushDir, "vapid.json"));
        // Touch the keystore so we generate / cache the keypair eagerly.
        keyStore.publicKeyB64Url();

        com.overdrive.app.notifications.push.SubscriptionStore subStore =
                new com.overdrive.app.notifications.push.SubscriptionStore(
                        new java.io.File(pushDir, "subscriptions.json"));
        subStore.load();

        com.overdrive.app.notifications.push.VapidSigner signer =
                new com.overdrive.app.notifications.push.VapidSigner(keyStore, "");

        // Persistent notification log (Notifications ▸ Log tab). Dedicated H2
        // store; the HistorySink writes EVERY bus event so history captures all
        // categories with no per-publisher change. Init before subscribing so
        // the sink never sees an uninitialized store.
        com.overdrive.app.notifications.NotificationStore notifStore =
                com.overdrive.app.notifications.NotificationStore.getInstance();
        try {
            notifStore.init();
        } catch (Exception e) {
            log("NotificationStore init failed (log tab will be empty): " + e.getMessage());
        }

        // Wire every sink BEFORE sealing the bus so any boot-window event
        // buffered by NotificationBus is flushed to ALL of them at seal — not
        // just the first to subscribe. A door-open / charging-fault during the
        // startup window then reaches the persisted log AND Web Push AND
        // Telegram. Subscribe order is cosmetic now (seal-time flush hits every
        // subscribed sink); HistorySink stays first only for readability.
        com.overdrive.app.notifications.NotificationBus.get()
                .subscribe(new com.overdrive.app.notifications.sinks.HistorySink(notifStore, registry));
        com.overdrive.app.notifications.NotificationBus.get()
                .subscribe(new com.overdrive.app.notifications.sinks.LogSink());
        com.overdrive.app.notifications.NotificationBus.get()
                .subscribe(new com.overdrive.app.notifications.sinks.PushSink(
                        subStore, registry, keyStore, signer));
        // Forward WARN/CRITICAL vehicle events (charging fault/full, door
        // opened, tyre alarm/leak, SOH mismatch) to Telegram too — they were
        // Web-Push-only before. Excludes surveillance.* (delivered to Telegram
        // directly via TelegramNotifier) so there is no double-send.
        com.overdrive.app.notifications.NotificationBus.get()
                .subscribe(new com.overdrive.app.notifications.sinks.TelegramSink());

        // All sinks are now wired. Seal the bus: flush the buffered
        // boot-window events to every subscribed sink (once each) and switch to
        // live-only dispatch. Without this seal, publish() would buffer forever.
        // The steps between the subscribes above and the latch below cannot
        // throw (NotificationApiHandler.init is field assignment), so the
        // NotificationsInit retry loop can never partially re-enter and
        // double-subscribe the sinks.
        com.overdrive.app.notifications.NotificationBus.get().sealPreSubscribeBuffer();

        com.overdrive.app.server.NotificationApiHandler.init(registry, subStore, keyStore);

        notificationsInitialized = true;
        log("Notifications initialized: " + registry.all().size() + " categories, "
                + subStore.size() + " subscriptions");
    }

    // ==================== GPS MONITOR ====================

    /**
     * Initialize GPS Monitor with app context for standard LocationManager access.
     * Uses PermissionBypassContext to access location services without runtime permission prompts.
     */
    private static void initGpsMonitor() {
        try {
            log("Initializing GPS Monitor with app context...");

            // Location permissions are already granted by PermissionGranter on its
            // background thread. No need to duplicate those 3 synchronous pm grant
            // calls here — they were blocking initGpsMonitor for several seconds
            // and adding redundant load to PackageManagerService.

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

            com.overdrive.app.byd.dilink5.Dilink5SdkInjector.ensure(sharedAppContext);

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
            try {
                gearMonitor.start();
            } catch (Exception e) {
                log("GearMonitor start failed (will retry on ACC ON): " + e.getMessage());
            }

            log("Gear Monitor initialized successfully");

            // Initialize Performance Monitor for system instrumentation.
            // init() only resolves pid/uid/context — it does NOT start polling.
            // Polling is ON-DEMAND: it starts when a client opens the perf page
            // (PerformanceApiHandler.clientConnected → PerformanceMonitor.start)
            // and stops when the last client disconnects. Do NOT call start()
            // here: an eager start ran the 1 Hz sampler 24/7, and its thermal
            // sweep (30 zones × 2 sysfs reads ≈ 1.5s of blocking I/O per tick on
            // this 48-zone head-unit) pegged the "PerfMonitor" thread at ~40% of
            // a core continuously — the single largest consumer in the daemon,
            // dwarfing the GL/encoder pipeline and causing the ACC-ON lag. The
            // monitor is a diagnostics tool; it must cost nothing when nobody's
            // looking at it.
            com.overdrive.app.monitor.PerformanceMonitor perfMonitor =
                com.overdrive.app.monitor.PerformanceMonitor.getInstance();
            perfMonitor.init(sharedAppContext);

            log("Performance Monitor initialized successfully (polling on-demand)");

            // Initialize SOC History Database for persistent battery tracking
            com.overdrive.app.monitor.SocHistoryDatabase socDb =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
            socDb.init();
            // setSohEstimator triggers pending calibration replay. It must run
            // after init(), otherwise replay exits on isInitialized=false and
            // startup has no guaranteed retry.
            socDb.setSohEstimator(sohEstimator);
            socDb.start();

            log("SOC History Database initialized successfully");

            // Charging Analytics — fast in-session power sampler + rollups. The
            // discrete session edges are recorded by SocHistoryDatabase itself
            // (on the 2-min SoC tick); this manager only adds the fine-grained
            // ramp sampler driven by ChargingDetector's fused charging edge.
            try {
                com.overdrive.app.charging.ChargingSessionManager csm =
                    new com.overdrive.app.charging.ChargingSessionManager();
                csm.init(sharedAppContext);
                chargingSessionManager = csm;
                log("Charging Analytics initialized successfully");
            } catch (Exception e) {
                log("Charging Analytics init failed: " + e.getMessage());
            }

            // Fix stale kWh records from before PHEV capacity was correctly
            // detected. Runs on a background thread. The database transaction
            // owns a durable version marker and per-row format marker, so the
            // migration cannot be reapplied later using a newer SOH. It has been observed to
            // take 100+ seconds on a long-running install (full table scan
            // with per-row arithmetic). Blocking the main init thread here
            // delayed ABRP / MQTT / TripAnalytics by the same 100+ s, which
            // is exactly the "trips loading 3-4 min" symptom users hit.
            //
            // SocHistoryDatabase serializes the shared H2 connection and
            // revalidates this immutable capacity/SOH token immediately before
            // commit. If reset or nominal-capacity mutation races the scan, the
            // transaction rolls back and retries with a fresh token.
            final com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot
                    capacitySohSnapshotForMigration = sohEstimator != null
                            ? sohEstimator.getCapacitySohSnapshot() : null;
            if (capacitySohSnapshotForMigration != null
                    && capacitySohSnapshotForMigration.getNominalCapacityKwh() > 0
                    && capacitySohSnapshotForMigration.getNominalCapacityKwh() < 30.0) {
                Thread migration = new Thread(() -> {
                    try {
                        long t0 = System.currentTimeMillis();
                        log("Fixing stale kWh records for PHEV (nominal="
                                + capacitySohSnapshotForMigration.getNominalCapacityKwh()
                                + " kWh) - async");
                        if (socDb.fixStaleRemainingKwh(capacitySohSnapshotForMigration)) {
                            log("Stale kWh migration done in "
                                    + (System.currentTimeMillis() - t0) + "ms");
                        } else {
                            log("Stale kWh migration deferred; no version marker committed");
                        }
                    } catch (Throwable t) {
                        log("Stale kWh migration failed: " + t.getMessage());
                    }
                }, "SocHistoryMigration");
                migration.setDaemon(true);
                migration.setPriority(Thread.MIN_PRIORITY);
                migration.start();
            }

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

            String packageName = (android.os.Process.myUid() == 2000) ? "com.android.shell" : APP_PACKAGE_NAME();
            log("createAppContext: Creating package context for " + packageName);
            android.content.Context appContext = systemContext.createPackageContext(packageName,
                    android.content.Context.CONTEXT_INCLUDE_CODE | android.content.Context.CONTEXT_IGNORE_SECURITY);
            log("createAppContext: appContext = " + appContext);

            if (appContext == null) {
                log("createAppContext: appContext is null, trying fallback...");
                return createFallbackContext();
            }

            com.overdrive.app.byd.BydDeviceHelper.fixContextImplForUid2000(appContext);
            com.overdrive.app.byd.BydDeviceHelper.fixContextImplForUid2000(systemContext);

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

        // Null-safe overrides for when base context is null (fallback mode).
        // CRITICAL: getMainLooper() must be overridden — BYDAutoDeviceManager calls
        // context.getMainLooper() in its constructor, and ContextWrapper delegates
        // to the base context which is null in fallback mode, causing NPE that
        // makes all 18 BYD device monitors null.
        @Override public android.os.Looper getMainLooper() {
            try { return super.getMainLooper(); } catch (NullPointerException e) {
                // Return the process main looper — BYD devices use it to register
                // Handler callbacks for CAN bus data change listeners.
                android.os.Looper looper = android.os.Looper.getMainLooper();
                return looper != null ? looper : android.os.Looper.myLooper();
            }
        }
        // Return the WRAPPER, not the raw base. The BYD SDK commonly normalizes to
        // the application context before a permission check
        // (ctx.getApplicationContext().checkSelfPermission("BYDAUTO_*_SET")); if we
        // handed back super.getApplicationContext() (the un-wrapped system context),
        // that check would hit the real context — which returns DENIED for the
        // signature-level BYDAUTO_*_SET perms our self-signed APK doesn't hold — and
        // the SDK would refuse the write BEFORE the binder call, exactly the
        // energy/operation/regen/steering failure. A custom Application subclass
        // avoids this naturally (its getApplicationContext() returns itself, so a
        // permission override on it stays in force through any SDK re-normalization);
        // returning `this` gives our ContextWrapper the same always-in-force property.
        @Override public android.content.Context getApplicationContext() {
            return this;
        }
        @Override public String getPackageName() {
            if (android.os.Process.myUid() == 2000) return "com.android.shell";
            try { return super.getPackageName(); } catch (NullPointerException e) { return APP_PACKAGE_NAME(); }
        }
        @Override public String getOpPackageName() {
            if (android.os.Process.myUid() == 2000) return "com.android.shell";
            try { return super.getOpPackageName(); } catch (Throwable e) { return "com.android.shell"; }
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
