package com.overdrive.app.logging;

import com.overdrive.app.BuildConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Compile-time logging configuration for release builds.
 * 
 * By default, all file logging is OFF in release builds.
 * To debug a specific daemon/component on a device:
 *   1. Set ENABLE_ALL = true (logs everything), OR
 *   2. Set individual flags below to true for targeted logging
 *   3. Build and deploy to the device
 *   4. Logs will appear in /data/local/tmp/<tag>.log
 * 
 * IMPORTANT: The proguard rules in proguard-rules.pro must also be updated.
 *   When LogConfig flags are enabled, the corresponding -assumenosideeffects
 *   rules are conditionally excluded so R8 doesn't strip the log calls.
 *   See the "Log Stripping" section in proguard-rules.pro.
 * 
 * After debugging, set everything back to false before shipping to production.
 */
public final class DaemonLogConfig {

    private DaemonLogConfig() {}

    // ==================== MASTER SWITCH ====================
    
    /**
     * Set true to enable file+stdout logging for ALL daemons/components.
     * Overrides all individual flags below.
     * 
     * When true, proguard log stripping is FULLY DISABLED (see proguard-rules.pro).
     */
    public static final boolean ENABLE_ALL = false;

    // ==================== DAEMON PROCESSES ====================
    
    /** CameraDaemon - main camera pipeline, GPU init, surveillance orchestration */
    public static final boolean CAMERA_DAEMON = false;
    
    /** AccSentryDaemon - ACC state detection, sentry mode transitions */
    public static final boolean ACC_SENTRY_DAEMON = false;
    
    /** SentryDaemon - legacy sentry process */
    public static final boolean SENTRY_DAEMON = false;
    
    /** TelegramBotDaemon - Telegram notification service */
    public static final boolean TELEGRAM_BOT_DAEMON = false;
    
    /** BydEventDaemon - BYD vehicle event listener */
    public static final boolean BYD_EVENT_DAEMON = false;

    /**
     * BYD HAL access + telemetry collection ({@code BydManagerChannel}, {@code BydDataCollector}).
     *
     * <p>Flip to {@code true} to get a targeted RELEASE capture of the HAL-access diagnostics:
     * which device-acquisition tier won, whether {@code enableDevice} was accepted, which read
     * channel produced a charging-power value, and the pre-scale raw feature value. Without it
     * those lines exist only in a braveheart build — in {@code release} the Gradle auto-detect adds
     * {@code proguard-rules-strip-logs.pro}, which removes the {@code info()}/{@code debug()} calls
     * outright, AND no BYD telemetry tag was in the allowlist, so the lines were unreachable three
     * ways over. Setting this both keeps the calls (via the auto-detect below) and opens the tags.
     */
    public static final boolean BYD_TELEMETRY = false;
    
    /** GlobalProxyDaemon - VPN/proxy daemon (uses own log method, not DaemonLogger) */
    public static final boolean GLOBAL_PROXY_DAEMON = false;

    // ==================== GPU PIPELINE ====================
    
    /** GpuPipeline - GPU surveillance pipeline orchestration */
    public static final boolean GPU_PIPELINE = false;
    
    /** PanoramicCameraGpu - AVMCamera init, GL thread, frame delivery */
    public static final boolean PANORAMIC_CAMERA = false;
    
    /** EGLCore - EGL context creation, surface management */
    public static final boolean EGL_CORE = false;
    
    /** GlUtil - shader compilation, texture creation */
    public static final boolean GL_UTIL = false;
    
    /** GpuMosaicRecorder - mosaic rendering, encoder surface */
    public static final boolean GPU_MOSAIC_RECORDER = false;
    
    /** GpuDownscaler - GPU frame downscaling for AI lane */
    public static final boolean GPU_DOWNSCALER = false;
    
    /** GpuStreamScaler - GPU stream scaling */
    public static final boolean GPU_STREAM_SCALER = false;
    
    /** HWEncoderGpu - MediaCodec hardware encoder */
    public static final boolean HW_ENCODER = true;
    
    /** AdaptiveBitrate - bitrate controller */
    public static final boolean ADAPTIVE_BITRATE = false;
    
    /** H264CircularBuffer - legacy pre-record slot pool (replaced by byte ring). */
    public static final boolean H264_CIRCULAR_BUFFER = false;
    /** H264ByteRing - SOTA pre-record byte-ring buffer */
    public static final boolean H264_BYTE_RING = false;
    
    /** GpuPipelineFactory - pipeline factory */
    public static final boolean GPU_PIPELINE_FACTORY = false;

    // ==================== SURVEILLANCE & MOTION ====================
    
    /** SurveillanceEngineGpu - motion detection, event recording triggers */
    public static final boolean SURVEILLANCE_ENGINE = true;
    
    /** EventTimeline - event timeline collector */
    public static final boolean EVENT_TIMELINE = false;
    
    /** ModeTransition - mode transition manager */
    public static final boolean MODE_TRANSITION = false;
    
    /** SafeLocationManager - geofence/safe zone logic (logs via CameraDaemon.log) */
    public static final boolean SAFE_LOCATION = false;
    
    /** SurveillanceConfigManager - surveillance config persistence */
    public static final boolean SURVEILLANCE_CONFIG = false;

    // ==================== RECORDING ====================
    
    /** RecordingModeManager - recording mode state machine */
    public static final boolean RECORDING_MODE_MANAGER = false;

    // ==================== PROXIMITY GUARD ====================
    
    /** ProximityGuardController - proximity guard orchestration */
    public static final boolean PROXIMITY_GUARD_CONTROLLER = false;
    
    /** ProximityRadarMonitor - radar data monitoring */
    public static final boolean PROXIMITY_RADAR_MONITOR = false;
    
    /** ProximityRecordingHandler - proximity-triggered recording */
    public static final boolean PROXIMITY_RECORDING_HANDLER = false;
    
    /** ProximityGuardConfig - proximity guard configuration */
    public static final boolean PROXIMITY_GUARD_CONFIG = false;

    // ==================== MONITORS ====================
    
    /** GearMonitor - gear state detection */
    public static final boolean GEAR_MONITOR = false;
    
    /** VehicleDataMonitor - BYD vehicle CAN bus data */
    public static final boolean VEHICLE_DATA_MONITOR = false;
    
    /** VehicleDataBridge - vehicle data bridge for HTTP API */
    public static final boolean VEHICLE_DATA_BRIDGE = false;
    
    /** PerformanceMonitor - CPU/memory/thermal monitoring */
    public static final boolean PERFORMANCE_MONITOR = false;
    
    /** PerformanceBridge - performance monitoring bridge for HTTP API */
    public static final boolean PERFORMANCE_BRIDGE = false;
    
    /** SocHistoryDatabase - SOC history H2 database */
    public static final boolean SOC_HISTORY_DATABASE = false;

    /** RoadSense - speed-breaker/pothole detect+warn+crowdsource. Covers the daemon
     *  controller (regime transitions, IMU-flow heartbeat, hazard detections,
     *  calibration restore/auto-accept), the H2 store, and the ground-truth store.
     *  FALSE in production (no file-log spam — the 30 s imu-flow heartbeat would
     *  otherwise churn the daemon log); flip to true to debug a RoadSense field issue.
     *  android.util.Log calls in these classes are always stripped in release anyway. */
    public static final boolean ROADSENSE = false;

    // ==================== STREAMING ====================
    
    /** WSStreamServer - WebSocket stream server */
    public static final boolean WS_STREAM_SERVER = false;

    // ==================== TELEMETRY & ABRP ====================
    
    /** AbrpTelemetryService - ABRP telemetry */
    public static final boolean ABRP_TELEMETRY = false;
    
    /** AbrpConfig - ABRP configuration */
    public static final boolean ABRP_CONFIG = false;
    
    /** SohEstimator - battery SOH estimation */
    public static final boolean SOH_ESTIMATOR = BuildConfig.DEBUG;
    
    /** TelemetryDataCollector - telemetry data collection */
    public static final boolean TELEMETRY_DATA_COLLECTOR = false;
    
    /** OverlayRenderer - telemetry overlay rendering */
    public static final boolean OVERLAY_RENDERER = false;

    // ==================== MQTT ====================

    /** MqttConnectionManager - MQTT connection orchestration */
    public static final boolean MQTT_CONNECTION_MANAGER = false;

    /** MqttPublisher - per-connection MQTT publishing */
    public static final boolean MQTT_PUBLISHER = false;

    /** MqttConnectionStore - MQTT config persistence */
    public static final boolean MQTT_CONNECTION_STORE = false;

    // ==================== TRIP ANALYTICS ====================
    
    /** Trip analytics — trip detection, telemetry recording, scoring, range estimation */
    public static final boolean TRIP_ANALYTICS = false;

    // ==================== STORAGE ====================
    
    /** StorageManager - storage management, SD card mounting */
    public static final boolean STORAGE_MANAGER = false;
    
    /** ExternalStorageCleaner - external storage cleanup */
    public static final boolean EXTERNAL_STORAGE_CLEANER = false;

    // ==================== SERVERS ====================
    
    /** HttpServer - HTTP API server (logs via CameraDaemon.log) */
    public static final boolean HTTP_SERVER = false;
    
    /** SurveillanceIPC - surveillance IPC server */
    public static final boolean SURVEILLANCE_IPC = false;
    
    /** AbrpApiHandler - ABRP API handler */
    public static final boolean ABRP_API_HANDLER = false;
    
    /** PerformanceApiHandler - performance API handler */
    public static final boolean PERFORMANCE_API_HANDLER = false;

    /** Automations - Covers all automation logs */
    public static final boolean AUTOMATIONS = false;

    // ==================== TAG LOOKUP ====================
    
    private static final Set<String> ENABLED_TAGS = new HashSet<>();
    
    /**
     * Returns true if ANY logging is enabled (for proguard-safe fast check).
     * When this is false and proguard strips log calls, this class is a no-op.
     */
    public static final boolean ANY_LOGGING_ENABLED = BuildConfig.LOG_CAPTURE || ENABLE_ALL
        || CAMERA_DAEMON || ACC_SENTRY_DAEMON || SENTRY_DAEMON
        || TELEGRAM_BOT_DAEMON || BYD_EVENT_DAEMON || BYD_TELEMETRY || GLOBAL_PROXY_DAEMON
        || GPU_PIPELINE || PANORAMIC_CAMERA || EGL_CORE || GL_UTIL
        || GPU_MOSAIC_RECORDER || GPU_DOWNSCALER || GPU_STREAM_SCALER
        || HW_ENCODER || ADAPTIVE_BITRATE || H264_CIRCULAR_BUFFER
        || GPU_PIPELINE_FACTORY || SURVEILLANCE_ENGINE || EVENT_TIMELINE
        || MODE_TRANSITION || SAFE_LOCATION || SURVEILLANCE_CONFIG
        || RECORDING_MODE_MANAGER || PROXIMITY_GUARD_CONTROLLER
        || PROXIMITY_RADAR_MONITOR || PROXIMITY_RECORDING_HANDLER
        || PROXIMITY_GUARD_CONFIG || GEAR_MONITOR || VEHICLE_DATA_MONITOR
        || VEHICLE_DATA_BRIDGE || PERFORMANCE_MONITOR || PERFORMANCE_BRIDGE
        || SOC_HISTORY_DATABASE || WS_STREAM_SERVER || ABRP_TELEMETRY
        || ABRP_CONFIG || SOH_ESTIMATOR || TELEMETRY_DATA_COLLECTOR
        || OVERLAY_RENDERER || TRIP_ANALYTICS || STORAGE_MANAGER
        || MQTT_CONNECTION_MANAGER || MQTT_PUBLISHER || MQTT_CONNECTION_STORE
        || EXTERNAL_STORAGE_CLEANER || HTTP_SERVER || SURVEILLANCE_IPC
        || ABRP_API_HANDLER || PERFORMANCE_API_HANDLER || AUTOMATIONS;
    
    static {
        if (!ENABLE_ALL) {
            if (CAMERA_DAEMON)              ENABLED_TAGS.add("CameraDaemon");
            if (ACC_SENTRY_DAEMON)          ENABLED_TAGS.add("AccSentryDaemon");
            if (SENTRY_DAEMON)              ENABLED_TAGS.add("SentryDaemon");
            if (TELEGRAM_BOT_DAEMON)        ENABLED_TAGS.add("TelegramBotDaemon");
            if (BYD_EVENT_DAEMON)           ENABLED_TAGS.add("BydEventDaemon");
            if (BYD_TELEMETRY) {
                // The three tags needed to diagnose a NaN charging surface. BydDeviceHelper is not
                // optional: it owns callGetProbing's per-width failure lines and
                // "Could not resolve deviceType", which are the lines that actually explain WHY a
                // read produced nothing. Without it the capture shows the symptom and not the cause.
                ENABLED_TAGS.add("BydManagerChannel");
                ENABLED_TAGS.add("BydDataCollector");
                ENABLED_TAGS.add("BydDeviceHelper");
            }
            if (GLOBAL_PROXY_DAEMON)        ENABLED_TAGS.add("GlobalProxyDaemon");
            if (GPU_PIPELINE)               ENABLED_TAGS.add("GpuPipeline");
            if (PANORAMIC_CAMERA)           ENABLED_TAGS.add("PanoramicCameraGpu");
            if (EGL_CORE)                   ENABLED_TAGS.add("EGLCore");
            if (GL_UTIL)                    ENABLED_TAGS.add("GlUtil");
            if (GPU_MOSAIC_RECORDER)        ENABLED_TAGS.add("GpuMosaicRecorder");
            if (GPU_DOWNSCALER)             ENABLED_TAGS.add("GpuDownscaler");
            if (GPU_STREAM_SCALER)          ENABLED_TAGS.add("GpuStreamScaler");
            if (HW_ENCODER)                 ENABLED_TAGS.add("HWEncoderGpu");
            if (ADAPTIVE_BITRATE)           ENABLED_TAGS.add("AdaptiveBitrate");
            if (H264_CIRCULAR_BUFFER)       ENABLED_TAGS.add("H264CircularBuffer");
            if (H264_BYTE_RING)             ENABLED_TAGS.add("H264ByteRing");
            if (GPU_PIPELINE_FACTORY)       ENABLED_TAGS.add("GpuPipelineFactory");
            if (SURVEILLANCE_ENGINE)        ENABLED_TAGS.add("SurveillanceEngineGpu");
            if (EVENT_TIMELINE)             ENABLED_TAGS.add("EventTimeline");
            if (MODE_TRANSITION)            ENABLED_TAGS.add("ModeTransition");
            if (SAFE_LOCATION)              ENABLED_TAGS.add("SafeLocation");
            if (RECORDING_MODE_MANAGER)     ENABLED_TAGS.add("RecordingModeManager");
            if (PROXIMITY_GUARD_CONTROLLER) ENABLED_TAGS.add("ProximityGuardController");
            if (PROXIMITY_RADAR_MONITOR)    ENABLED_TAGS.add("ProximityRadarMonitor");
            if (PROXIMITY_RECORDING_HANDLER) ENABLED_TAGS.add("ProximityRecordingHandler");
            if (PROXIMITY_GUARD_CONFIG)     ENABLED_TAGS.add("ProximityGuardConfig");
            if (GEAR_MONITOR)               ENABLED_TAGS.add("GearMonitor");
            if (VEHICLE_DATA_MONITOR)       ENABLED_TAGS.add("VehicleDataMonitor");
            if (VEHICLE_DATA_BRIDGE)        ENABLED_TAGS.add("VehicleDataBridge");
            if (PERFORMANCE_MONITOR)        ENABLED_TAGS.add("PerformanceMonitor");
            if (PERFORMANCE_BRIDGE)         ENABLED_TAGS.add("PerformanceBridge");
            if (SOC_HISTORY_DATABASE)       ENABLED_TAGS.add("SocHistoryDatabase");
            if (ROADSENSE) {
                ENABLED_TAGS.add("RoadSense");              // controller plog
                ENABLED_TAGS.add("RoadSenseStore");         // H2 store
                ENABLED_TAGS.add("RoadSense/GroundTruth");  // labels store
            }
            if (WS_STREAM_SERVER)           ENABLED_TAGS.add("WSStreamServer");
            if (ABRP_TELEMETRY)             ENABLED_TAGS.add("AbrpTelemetryService");
            if (ABRP_CONFIG)                ENABLED_TAGS.add("AbrpConfig");
            if (SOH_ESTIMATOR)              ENABLED_TAGS.add("SohEstimator");
            if (TELEMETRY_DATA_COLLECTOR)   ENABLED_TAGS.add("TelemetryDataCollector");
            if (OVERLAY_RENDERER)           ENABLED_TAGS.add("OverlayRenderer");
            if (MQTT_CONNECTION_MANAGER)    ENABLED_TAGS.add("MqttConnectionManager");
            if (MQTT_PUBLISHER)             ENABLED_TAGS.add("MqttPublisher");
            if (MQTT_CONNECTION_STORE)      ENABLED_TAGS.add("MqttConnectionStore");
            if (TRIP_ANALYTICS) {
                ENABLED_TAGS.add("TripAnalyticsManager");
                ENABLED_TAGS.add("TripDetector");
                ENABLED_TAGS.add("TripTelemetryRecorder");
                ENABLED_TAGS.add("TripScoreEngine");
                ENABLED_TAGS.add("TripDatabase");
                ENABLED_TAGS.add("RangeEstimator");
                ENABLED_TAGS.add("TripConfig");
                ENABLED_TAGS.add("TelemetryStore");
            }
            if (STORAGE_MANAGER)            ENABLED_TAGS.add("StorageManager");
            if (EXTERNAL_STORAGE_CLEANER)   ENABLED_TAGS.add("ExternalStorageCleaner");
            if (HTTP_SERVER)                ENABLED_TAGS.add("HttpServer");
            if (SURVEILLANCE_IPC)           ENABLED_TAGS.add("SurveillanceIPC");
            if (ABRP_API_HANDLER)           ENABLED_TAGS.add("AbrpApiHandler");
            if (PERFORMANCE_API_HANDLER)    ENABLED_TAGS.add("PerformanceApiHandler");
            if (AUTOMATIONS) {
                ENABLED_TAGS.add("Automations");
                ENABLED_TAGS.add("AutomationAction");
            }
        }
    }

    /**
     * Check if file logging is enabled for a given tag.
     * Called by DaemonLogger on every log write.
     */
    public static boolean isFileLoggingEnabled(String tag) {
        // Braveheart (release-type build with LOG_CAPTURE=true) keeps file
        // logging on for EVERY daemon tag so customers can send per-daemon
        // logs. The DaemonLogger log calls survive R8 in that variant because
        // its buildType omits proguard-rules-strip-logs.pro.
        if (BuildConfig.LOG_CAPTURE) return true;
        if (ENABLE_ALL) return true;
        return ENABLED_TAGS.contains(tag);
    }
}
