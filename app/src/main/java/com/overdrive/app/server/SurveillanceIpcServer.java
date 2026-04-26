package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IPC server for surveillance configuration.
 * Listens on port 19877 for surveillance config commands from the app UI.
 * 
 * SOTA: Uses thread pool to prevent thread exhaustion under load.
 */
public class SurveillanceIpcServer implements Runnable {
    private static final String TAG = "SurveillanceIPC";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = true;
    
    // SOTA FIX: Thread Pool to prevent server clogging
    // IPC server typically has fewer connections than HTTP, so 8 threads is sufficient
    private final ExecutorService threadPool = Executors.newFixedThreadPool(8);

    // ABRP integration references (set by CameraDaemon)
    private static volatile com.overdrive.app.abrp.AbrpConfig abrpConfig;
    private static volatile com.overdrive.app.abrp.AbrpTelemetryService abrpService;

    public static void setAbrpReferences(com.overdrive.app.abrp.AbrpConfig config, com.overdrive.app.abrp.AbrpTelemetryService service) {
        abrpConfig = config;
        abrpService = service;
    }

    // MQTT integration reference (set by CameraDaemon)
    private static volatile com.overdrive.app.mqtt.MqttConnectionManager mqttManager;

    public static void setMqttManager(com.overdrive.app.mqtt.MqttConnectionManager manager) {
        mqttManager = manager;
    }
    
    public SurveillanceIpcServer(int port) {
        this.port = port;
    }
    
    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"));
            logger.info("Surveillance IPC server listening on 127.0.0.1:" + port);
            
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    // SOTA FIX: Offload to Thread Pool instead of spawning new thread
                    threadPool.execute(() -> handleClient(client));
                } catch (Exception e) {
                    if (running) {
                        logger.error("Error accepting client", e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to start IPC server", e);
        }
    }
    
    private void handleClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter out = new PrintWriter(client.getOutputStream(), true);
            
            String line = in.readLine();
            if (line != null) {
                JSONObject request = new JSONObject(line);
                JSONObject response = handleCommand(request);
                out.println(response.toString());
            }
            
            client.close();
        } catch (Exception e) {
            logger.error("Error handling client", e);
        }
    }
    
    private JSONObject handleCommand(JSONObject request) {
        JSONObject response = new JSONObject();
        
        try {
            String command = request.optString("command", "");
            
            switch (command) {
                // ==================== TELEGRAM DAEMON COMMANDS ====================
                // These commands are sent by TelegramBotDaemon for remote control
                
                case "START":
                    // Start surveillance (from Telegram /start command)
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true);
                    if (!com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                        CameraDaemon.enableSurveillance();
                        logger.info("Surveillance started via Telegram IPC");
                    } else {
                        logger.info("Surveillance preference saved via Telegram — will activate on ACC OFF");
                    }
                    response.put("success", true);
                    response.put("enabled", true);
                    response.put("message", "Surveillance enabled");
                    break;
                    
                case "STOP":
                    // Stop surveillance (from Telegram /stop command)
                    CameraDaemon.disableSurveillance();
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false);
                    logger.info("Surveillance stopped via Telegram IPC");
                    response.put("success", true);
                    response.put("enabled", false);
                    response.put("message", "Surveillance stopped");
                    break;
                    
                case "STATUS": {
                    // Get surveillance status (from Telegram /status command)
                    // Read from persisted config (not in-memory flag which can get stale)
                    boolean enabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
                    boolean active = CameraDaemon.isSurveillanceActive();
                    response.put("success", true);
                    response.put("enabled", enabled);
                    response.put("active", active);
                    response.put("recording", active);
                    logger.info("Status requested via Telegram IPC: enabled=" + enabled + ", active=" + active);
                    break;
                }
                
                // ==================== APP UI COMMANDS ====================
                // These commands are sent by the app UI for configuration
                
                case "ENABLE_SURVEILLANCE":
                    // Persist preference only — surveillance will auto-start on next ACC OFF
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true);
                    logger.info("Surveillance preference set to ENABLED (will activate on ACC OFF)");
                    response.put("success", true);
                    response.put("enabled", true);
                    break;
                    
                case "DISABLE_SURVEILLANCE":
                    // Persist preference and stop if currently running
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false);
                    CameraDaemon.disableSurveillance();
                    logger.info("Surveillance preference set to DISABLED and stopped");
                    response.put("success", true);
                    response.put("enabled", false);
                    break;
                
                case "GET_CONFIG":
                    response.put("success", true);
                    response.put("config", getDefaultConfig());
                    break;
                    
                case "SET_CONFIG":
                    JSONObject config = request.optJSONObject("config");
                    if (config != null) {
                        applyConfig(config);
                    }
                    response.put("success", true);
                    response.put("message", "Config updated");
                    break;
                    
                case "GET_STATUS":
                    response.put("success", true);
                    response.put("status", getSurveillanceStatus());
                    break;
                    
                // ==================== VEHICLE DATA COMMANDS ====================
                // These commands provide access to BYD vehicle telemetry
                
                case "GET_VEHICLE_DATA":
                    response.put("success", true);
                    response.put("data", getVehicleData());
                    break;
                    
                case "GET_BATTERY_VOLTAGE":
                    response.put("success", true);
                    response.put("data", getBatteryVoltageData());
                    break;
                    
                case "GET_BATTERY_POWER":
                    response.put("success", true);
                    response.put("data", getBatteryPowerData());
                    break;
                    
                case "GET_BATTERY_SOC":
                    response.put("success", true);
                    response.put("data", getBatterySocData());
                    break;
                    
                case "GET_CHARGING_STATE":
                    response.put("success", true);
                    response.put("data", getChargingStateData());
                    break;
                    
                case "GET_CHARGING_POWER":
                    response.put("success", true);
                    response.put("data", getChargingPowerData());
                    break;
                    
                case "GET_DRIVING_RANGE":
                    response.put("success", true);
                    response.put("data", getDrivingRangeData());
                    break;
                    
                case "GET_ROI":
                    response.put("success", true);
                    response.put("roi", getRoiData());
                    break;
                    
                case "SET_ROI":
                    JSONObject roiData = request.optJSONObject("roi");
                    if (roiData != null) {
                        applyRoi(roiData);
                    }
                    response.put("success", true);
                    response.put("message", "ROI updated");
                    break;
                
                // ==================== SAFE LOCATION COMMANDS ====================
                
                case "GET_SAFE_LOCATIONS":
                    response = com.overdrive.app.surveillance.SafeLocationManager.getInstance().getStatusJson();
                    response.put("success", true);
                    break;
                    
                case "ADD_SAFE_LOCATION": {
                    JSONObject zoneData = request.optJSONObject("zone");
                    if (zoneData != null) {
                        com.overdrive.app.surveillance.SafeLocation zone =
                            com.overdrive.app.surveillance.SafeLocationManager.getInstance().addZone(
                                zoneData.optString("name", "Unnamed"),
                                zoneData.optDouble("lat", 0),
                                zoneData.optDouble("lng", 0),
                                zoneData.optInt("radiusM", 150));
                        response.put("success", zone != null);
                        if (zone != null) response.put("zone", zone.toJson());
                        else response.put("error", "Max zones reached (10)");
                    } else {
                        response.put("success", false);
                        response.put("error", "Missing zone data");
                    }
                    break;
                }
                    
                case "UPDATE_SAFE_LOCATION": {
                    String zoneId = request.optString("id", null);
                    JSONObject updates = request.optJSONObject("updates");
                    if (zoneId != null && updates != null) {
                        boolean updated = com.overdrive.app.surveillance.SafeLocationManager.getInstance()
                            .updateZone(zoneId, updates);
                        response.put("success", updated);
                    } else {
                        response.put("success", false);
                        response.put("error", "Missing id or updates");
                    }
                    break;
                }
                    
                case "DELETE_SAFE_LOCATION": {
                    String zoneId = request.optString("id", null);
                    if (zoneId != null) {
                        boolean removed = com.overdrive.app.surveillance.SafeLocationManager.getInstance()
                            .removeZone(zoneId);
                        response.put("success", removed);
                    } else {
                        response.put("success", false);
                        response.put("error", "Missing id");
                    }
                    break;
                }
                    
                case "TOGGLE_SAFE_LOCATIONS": {
                    boolean enabled = request.optBoolean("enabled", true);
                    com.overdrive.app.surveillance.SafeLocationManager.getInstance().setFeatureEnabled(enabled);
                    response.put("success", true);
                    response.put("enabled", enabled);
                    break;
                }
                
                // ==================== GPS SIDECAR COMMANDS ====================
                // GPS data from LocationSidecarService (app writes via IPC, daemon writes to file)
                
                case "UPDATE_GPS":
                    handleGpsUpdate(request);
                    response.put("success", true);
                    break;

                // ==================== ABRP COMMANDS ====================
                case "SET_ABRP_CONFIG":
                    handleSetAbrpConfig(request, response);
                    break;

                case "GET_ABRP_CONFIG":
                    handleGetAbrpConfig(response);
                    break;

                case "GET_ABRP_STATUS":
                    handleGetAbrpStatus(response);
                    break;

                case "DELETE_ABRP_TOKEN":
                    handleDeleteAbrpToken(response);
                    break;

                // ==================== MQTT COMMANDS ====================
                case "GET_MQTT_CONNECTIONS":
                    handleGetMqttConnections(response);
                    break;

                case "ADD_MQTT_CONNECTION":
                    handleAddMqttConnection(request, response);
                    break;

                case "UPDATE_MQTT_CONNECTION":
                    handleUpdateMqttConnection(request, response);
                    break;

                case "DELETE_MQTT_CONNECTION":
                    handleDeleteMqttConnection(request, response);
                    break;

                case "GET_MQTT_STATUS":
                    handleGetMqttStatus(response);
                    break;

                case "GET_MQTT_TELEMETRY":
                    handleGetMqttTelemetry(response);
                    break;

                // ==================== TELEMETRY OVERLAY COMMANDS ====================

                case "SET_TELEMETRY_OVERLAY": {
                    boolean enabled = request.optBoolean("enabled", false);
                    JSONObject overlayConfig = new JSONObject();
                    overlayConfig.put("enabled", enabled);
                    com.overdrive.app.config.UnifiedConfigManager.setTelemetryOverlay(overlayConfig);
                    // Notify pipeline
                    com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
                    if (pipeline != null) {
                        pipeline.setOverlayEnabled(enabled);
                    }
                    response.put("success", true);
                    response.put("enabled", enabled);
                    break;
                }

                case "GET_TELEMETRY_OVERLAY": {
                    JSONObject overlayConfig = com.overdrive.app.config.UnifiedConfigManager.getTelemetryOverlay();
                    response.put("success", true);
                    response.put("enabled", overlayConfig.optBoolean("enabled", false));
                    break;
                }

                default:
                    logger.warn("Unknown IPC command: " + command);
                    response.put("success", false);
                    response.put("error", "Unknown command: " + command);
            }
        } catch (Exception e) {
            logger.error("Error handling IPC command", e);
            try {
                response.put("success", false);
                response.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        
        return response;
    }
    
    /**
     * Apply configuration changes to surveillance system.
     * Updates both the running engine (if available) AND persists to config file.
     * Config is ALWAYS persisted even if surveillance is not running.
     */
    private void applyConfig(JSONObject config) {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            
            // Sentry may be null if surveillance is not running - that's OK
            com.overdrive.app.surveillance.SurveillanceEngineGpu sentry = null;
            if (pipeline != null) {
                sentry = pipeline.getSentry();
            }
            
            // Get or create SurveillanceConfig for persistence
            // Even if sentry is null, we still want to persist the config
            com.overdrive.app.surveillance.SurveillanceConfig sentryConfig = null;
            if (sentry != null) {
                sentryConfig = sentry.getConfig();
            }
            if (sentryConfig == null) {
                // Load from file or create new
                try {
                    com.overdrive.app.surveillance.SurveillanceConfigManager configManager =
                        new com.overdrive.app.surveillance.SurveillanceConfigManager();
                    if (configManager.configExists()) {
                        sentryConfig = configManager.loadConfig();
                        logger.info("Loaded existing config from file for update");
                    } else {
                        sentryConfig = new com.overdrive.app.surveillance.SurveillanceConfig();
                        logger.info("Created new config for persistence");
                    }
                } catch (Exception e) {
                    sentryConfig = new com.overdrive.app.surveillance.SurveillanceConfig();
                    logger.error("Failed to load config, using defaults", e);
                }
            }
            
            boolean configChanged = false;
            
            // Handle surveillance storage type change (INTERNAL or SD_CARD)
            if (config.has("surveillanceStorageType")) {
                String typeStr = config.getString("surveillanceStorageType").toUpperCase();
                com.overdrive.app.storage.StorageManager storageManager =
                    com.overdrive.app.storage.StorageManager.getInstance();
                com.overdrive.app.storage.StorageManager.StorageType type =
                    "SD_CARD".equals(typeStr) ?
                        com.overdrive.app.storage.StorageManager.StorageType.SD_CARD :
                        com.overdrive.app.storage.StorageManager.StorageType.INTERNAL;
                boolean success = storageManager.setSurveillanceStorageType(type);
                if (success) {
                    logger.info("Surveillance storage type set to " + type + " via IPC");
                    // Update sentry's event output directory if running
                    if (sentry != null) {
                        sentry.setEventOutputDir(storageManager.getSurveillanceDir());
                        logger.info("Updated sentry output dir: " + storageManager.getSurveillanceDir().getAbsolutePath());
                    }
                } else {
                    logger.warn("Failed to set surveillance storage to SD_CARD - not available");
                }
            }
            
            // Handle surveillance storage limit change
            if (config.has("surveillanceLimitMb")) {
                long limitMb = config.getLong("surveillanceLimitMb");
                com.overdrive.app.storage.StorageManager storageManager =
                    com.overdrive.app.storage.StorageManager.getInstance();
                storageManager.setSurveillanceLimitMb(limitMb);
                logger.info("Surveillance limit set to " + storageManager.getSurveillanceLimitMb() + " MB via IPC");
                // Trigger async cleanup
                new Thread(() -> storageManager.ensureSurveillanceSpace(0), "SurvLimitCleanup").start();
            }
            
            // Check if enabled state changed
            if (config.has("enabled")) {
                boolean enabled = config.getBoolean("enabled");
                // Persist to unified config so ACC OFF respects user preference
                com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(enabled);
                if (enabled) {
                    CameraDaemon.enableSurveillance();
                    logger.info("Surveillance enabled via IPC");
                } else {
                    CameraDaemon.disableSurveillance();
                    logger.info("Surveillance disabled via IPC");
                }
            }
            
            // Handle ACC state if provided
            if (config.has("accOff")) {
                boolean accOff = config.getBoolean("accOff");
                CameraDaemon.onAccStateChanged(accOff);
                logger.info("ACC state changed via IPC: " + (accOff ? "OFF" : "ON"));
            }
            
            // Handle gear state if provided
            if (config.has("gear")) {
                int gear = config.getInt("gear");
                CameraDaemon.onGearChanged(gear);
                logger.info("Gear changed via IPC: " + com.overdrive.app.recording.RecordingModeManager.gearToString(gear));
            }
            
            // Handle sensitivity setting (maps to minObjectSize)
            if (config.has("sensitivity")) {
                String sensitivity = config.optString("sensitivity", "MEDIUM").toUpperCase();
                float minSize;
                switch (sensitivity) {
                    case "LOW":
                        minSize = 0.02f;  // 2% - detect distant objects
                        break;
                    case "HIGH":
                        minSize = 0.15f;  // 15% - only close objects
                        break;
                    case "MEDIUM":
                    default:
                        minSize = 0.08f;  // 8% - balanced
                        break;
                }
                float confidence = (float) config.optDouble("aiConfidence", sentryConfig.getAiConfidence());
                boolean detectPerson = config.optBoolean("detectPerson", sentryConfig.isDetectPerson());
                boolean detectCar = config.optBoolean("detectCar", sentryConfig.isDetectCar());
                boolean detectBike = config.optBoolean("detectBike", sentryConfig.isDetectBike());
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setObjectFilters(minSize, confidence, detectPerson, detectCar, detectBike);
                }
                
                // Update config object for persistence
                sentryConfig.setMinObjectSize(minSize);
                sentryConfig.setAiConfidence(confidence);
                sentryConfig.setDetectPerson(detectPerson);
                sentryConfig.setDetectCar(detectCar);
                sentryConfig.setDetectBike(detectBike);
                configChanged = true;
                
                logger.info("Sensitivity set to " + sensitivity + " (minObjectSize=" + minSize + ")");
            }
            
            // Apply object detection filters (direct minObjectSize override)
            if (config.has("minObjectSize") || config.has("aiConfidence") || 
                config.has("detectPerson") || config.has("detectCar") || config.has("detectBike")) {
                
                float minSize = (float) config.optDouble("minObjectSize", sentryConfig.getMinObjectSize());
                float confidence = (float) config.optDouble("aiConfidence", sentryConfig.getAiConfidence());
                boolean detectPerson = config.optBoolean("detectPerson", sentryConfig.isDetectPerson());
                boolean detectCar = config.optBoolean("detectCar", sentryConfig.isDetectCar());
                boolean detectBike = config.optBoolean("detectBike", sentryConfig.isDetectBike());
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setObjectFilters(minSize, confidence, detectPerson, detectCar, detectBike);
                }
                
                // Update config object for persistence
                sentryConfig.setMinObjectSize(minSize);
                sentryConfig.setAiConfidence(confidence);
                sentryConfig.setDetectPerson(detectPerson);
                sentryConfig.setDetectCar(detectCar);
                sentryConfig.setDetectBike(detectBike);
                configChanged = true;
                
                logger.info("Object filters applied (sentry " + (sentry != null ? "running" : "not running") + ")");
            }
            
            // Handle pre/post record seconds
            if (config.has("preRecordSeconds") || config.has("preEventBufferSeconds")) {
                int preRecordSeconds = config.has("preRecordSeconds") 
                    ? config.optInt("preRecordSeconds", 5)
                    : config.optInt("preEventBufferSeconds", 5);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setPreRecordSeconds(preRecordSeconds);
                }
                
                sentryConfig.setPreRecordSeconds(preRecordSeconds);
                configChanged = true;
                logger.info("Pre-record seconds set to: " + preRecordSeconds);
            }
            
            if (config.has("postRecordSeconds") || config.has("postEventBufferSeconds")) {
                int postRecordSeconds = config.has("postRecordSeconds")
                    ? config.optInt("postRecordSeconds", 10)
                    : config.optInt("postEventBufferSeconds", 10);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setPostRecordSeconds(postRecordSeconds);
                }
                
                sentryConfig.setPostRecordSeconds(postRecordSeconds);
                configChanged = true;
                logger.info("Post-record seconds set to: " + postRecordSeconds);
            }
            
            // Handle bitrate setting
            if (config.has("bitrate")) {
                String bitrate = config.optString("bitrate", "MEDIUM").toUpperCase();
                if (bitrate.equals("LOW") || bitrate.equals("MEDIUM") || bitrate.equals("HIGH")) {
                    CameraDaemon.setRecordingBitrate(bitrate);
                    // Also update HttpServer's static setting for web UI sync
                    HttpServer.setRecordingBitrateStatic(bitrate);
                    logger.info("Recording bitrate set to: " + bitrate);
                }
            }
            
            // Handle codec setting
            if (config.has("codec")) {
                String codec = config.optString("codec", "H264").toUpperCase();
                if (codec.equals("H264") || codec.equals("H265")) {
                    CameraDaemon.setRecordingCodec(codec);
                    // Also update HttpServer's static setting for web UI sync
                    HttpServer.setRecordingCodecStatic(codec);
                    logger.info("Recording codec set to: " + codec);
                }
            }
            
            // Persist recording settings to file so web UI can read them
            if (config.has("bitrate") || config.has("codec")) {
                HttpServer.persistSettingsStatic();
            }
            
            // SOTA: Handle flash immunity setting (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
            if (config.has("flashImmunity")) {
                int flashImmunity = config.optInt("flashImmunity", 2);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setFlashImmunity(flashImmunity);
                }
                
                sentryConfig.setFlashImmunity(flashImmunity);
                configChanged = true;
                logger.info("Flash immunity set to: " + flashImmunity);
            }
            
            // SOTA: Handle distance preset (1-5 slider value)
            // Distance ONLY controls minObjectSize (AI detection range)
            // Block size is LOCKED at 32. Motion sensitivity is handled separately.
            if (config.has("distance")) {
                int distance = config.optInt("distance", 3);
                
                // Map slider value to minObjectSize for AI detection
                // 1 = Close (~3m, 25%), 2 = Near (~5m, 18%), 3 = Medium (~8m, 12%), 
                // 4 = Far (~10m, 8%), 5 = Very Far (~15m, 5%)
                float minObjectSize;
                String distanceLabel;
                switch (distance) {
                    case 1: minObjectSize = 0.25f; distanceLabel = "CLOSE (~3m)"; break;
                    case 2: minObjectSize = 0.18f; distanceLabel = "NEAR (~5m)"; break;
                    case 3: minObjectSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                    case 4: minObjectSize = 0.08f; distanceLabel = "FAR (~10m)"; break;
                    case 5: minObjectSize = 0.05f; distanceLabel = "VERY_FAR (~15m)"; break;
                    default: minObjectSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                }
                
                // Only update minObjectSize - don't touch motion sensitivity settings
                sentryConfig.setMinObjectSize(minObjectSize);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    float confidence = sentryConfig.getAiConfidence();
                    boolean detectPerson = sentryConfig.isDetectPerson();
                    boolean detectCar = sentryConfig.isDetectCar();
                    boolean detectBike = sentryConfig.isDetectBike();
                    sentry.setObjectFilters(minObjectSize, confidence, detectPerson, detectCar, detectBike);
                }
                
                logger.info(String.format("Distance set via IPC: %s (minObjectSize=%.0f%%)",
                    distanceLabel, minObjectSize * 100));
            }
            
            // SOTA: Handle motion sensitivity slider (1-5) - SEPARATE from distance
            // Controls requiredBlocks and densityThreshold for motion detection
            // Block size is LOCKED at 32
            if (config.has("sensitivity") && config.optInt("sensitivity", -1) >= 1 && config.optInt("sensitivity", -1) <= 5) {
                int sensitivityLevel = config.optInt("sensitivity", 3);
                
                // Map slider value to motion detection thresholds
                // Production table with block size locked at 32:
                // 1=Strict (req=4, density=48), 2=Conservative (req=3, density=40), 
                // 3=Default (req=2, density=32), 4=Sensitive (req=2, density=16), 5=Aggressive (req=1, density=12)
                int requiredBlocks;
                
                switch (sensitivityLevel) {
                    case 1:  // Strict - large objects only
                        requiredBlocks = 4;
                        break;
                    case 2:  // Conservative - solid objects
                        requiredBlocks = 3;
                        break;
                    case 3:  // Default - balanced
                        requiredBlocks = 2;
                        break;
                    case 4:  // Sensitive - catches motion quickly
                        requiredBlocks = 2;
                        break;
                    case 5:  // Aggressive - any motion
                        requiredBlocks = 1;
                        break;
                    default:
                        requiredBlocks = 2;
                        break;
                }
                
                // Convert slider 1-5 to percentage 20-100 for unified sensitivity
                int sensitivityPercent = sensitivityLevel * 20;
                
                // Update config for persistence
                sentryConfig.setRequiredBlocks(requiredBlocks);
                sentryConfig.setUnifiedSensitivity(sensitivityPercent);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setUnifiedSensitivity(sensitivityPercent);
                    sentry.setRequiredActiveBlocks(requiredBlocks);
                }
                
                logger.info(String.format("Motion sensitivity set to level %d (%d%%, alarm=%d blocks)",
                    sensitivityLevel, sensitivityPercent, requiredBlocks));
            }
            
            // Handle block sensitivity (grid motion detection) - skip if distance was set
            if (config.has("blockSensitivity") && !config.has("distance")) {
                float blockSens = (float) config.optDouble("blockSensitivity", 0.04);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setBlockSensitivity(blockSens);
                }
                
                sentryConfig.setSensitivity(blockSens);
                configChanged = true;
                logger.info("Block sensitivity set to: " + blockSens);
            }
            
            // Handle required active blocks - skip if distance was set
            if (config.has("requiredActiveBlocks") && !config.has("distance")) {
                int reqBlocks = config.optInt("requiredActiveBlocks", 2);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setRequiredActiveBlocks(reqBlocks);
                }
                
                sentryConfig.setRequiredBlocks(reqBlocks);
                configChanged = true;
                logger.info("Required active blocks set to: " + reqBlocks);
            }
            
            // Apply updated config to engine (if running) and ALWAYS persist to file
            if (configChanged) {
                // Apply config to running engine (syncs internal state like preRecordMs)
                if (sentry != null) {
                    sentry.setConfig(sentryConfig);
                }
                
                // ALWAYS persist to file - this is critical for config to survive restarts
                try {
                    com.overdrive.app.surveillance.SurveillanceConfigManager configManager =
                        new com.overdrive.app.surveillance.SurveillanceConfigManager();
                    configManager.saveConfig(sentryConfig);
                    logger.info("Surveillance config persisted to file (sentry " + 
                        (sentry != null ? "running" : "not running") + ")");
                } catch (Exception e) {
                    logger.error("Failed to persist surveillance config", e);
                }
            }
            
            // Note: minObjectHeight filter is now applied in C++ (yolo_detector.cpp)
            // Height filter (10% of frame) is applied BEFORE NMS in native code for efficiency
            
            // ========================================================================
            // V2 Pipeline Settings
            // ========================================================================
            
            // Environment preset (outdoor/garage/street) — sets slider defaults
            if (config.has("environmentPreset")) {
                String preset = config.optString("environmentPreset", "outdoor").toLowerCase();
                sentryConfig.setEnvironmentPreset(preset);
                if (sentry != null) {
                    sentry.applyV2EnvironmentPreset(preset);
                }
                configChanged = true;
                logger.info("V2 environment preset: " + preset);
            }
            
            // Sensitivity level (1-5)
            if (config.has("sensitivityLevel")) {
                int level = config.optInt("sensitivityLevel", 3);
                sentryConfig.setSensitivityLevel(level);
                if (sentry != null) {
                    sentry.applyV2Sensitivity(level);
                }
                configChanged = true;
                logger.info("V2 sensitivity level: " + level);
            }
            
            // Detection zone (close/normal/extended)
            if (config.has("detectionZone")) {
                String zone = config.optString("detectionZone", "normal").toLowerCase();
                sentryConfig.setDetectionZone(zone);
                configChanged = true;
                logger.info("V2 detection zone: " + zone);
            }
            
            // Loitering time (seconds)
            if (config.has("loiteringTime")) {
                int seconds = config.optInt("loiteringTime", 3);
                sentryConfig.setLoiteringTimeSeconds(seconds);
                if (sentry != null) {
                    sentry.setV2LoiteringTime(seconds);
                }
                configChanged = true;
                logger.info("V2 loitering time: " + seconds + "s");
            }
            
            // Shadow filter mode (0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE)
            if (config.has("shadowFilter")) {
                int mode = config.optInt("shadowFilter", 2);
                sentryConfig.setShadowFilterMode(mode);
                if (sentry != null) {
                    sentry.setV2ShadowFilterMode(mode);
                }
                configChanged = true;
                String[] modeNames = {"OFF", "LIGHT", "NORMAL", "AGGRESSIVE"};
                logger.info("V2 shadow filter: " + (mode >= 0 && mode <= 3 ? modeNames[mode] : "invalid"));
            }
            
            // Per-camera enable/disable
            if (config.has("cameraFront")) {
                boolean enabled = config.optBoolean("cameraFront", true);
                sentryConfig.setCameraEnabled(0, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(0, enabled);
                configChanged = true;
            }
            if (config.has("cameraRight")) {
                boolean enabled = config.optBoolean("cameraRight", true);
                sentryConfig.setCameraEnabled(1, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(1, enabled);
                configChanged = true;
            }
            if (config.has("cameraLeft")) {
                boolean enabled = config.optBoolean("cameraLeft", true);
                sentryConfig.setCameraEnabled(2, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(2, enabled);
                configChanged = true;
            }
            if (config.has("cameraRear")) {
                boolean enabled = config.optBoolean("cameraRear", true);
                sentryConfig.setCameraEnabled(3, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(3, enabled);
                configChanged = true;
            }
            
            // Developer toggles
            if (config.has("motionHeatmap")) {
                sentryConfig.setMotionHeatmapEnabled(config.optBoolean("motionHeatmap", false));
                configChanged = true;
            }
            if (config.has("filterDebugLog")) {
                boolean debugEnabled = config.optBoolean("filterDebugLog", false);
                sentryConfig.setFilterDebugLogEnabled(debugEnabled);
                if (sentry != null) {
                    sentry.setFilterDebugEnabled(debugEnabled);
                }
                configChanged = true;
            }
            
        } catch (Exception e) {
            logger.error("Failed to apply config", e);
        }
    }
    
    /**
     * Apply ROI configuration to surveillance system.
     */
    private void applyRoi(JSONObject roiData) {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            
            if (pipeline == null || pipeline.getSentry() == null) {
                logger.warn("Cannot apply ROI - surveillance not initialized");
                return;
            }
            
            // Parse polygon points
            org.json.JSONArray pointsArray = roiData.optJSONArray("points");
            if (pointsArray == null || pointsArray.length() < 3) {
                // Clear ROI
                pipeline.getSentry().setRoiMask(null);
                logger.info("ROI cleared");
                return;
            }
            
            // Convert to float array
            float[][] points = new float[pointsArray.length()][2];
            for (int i = 0; i < pointsArray.length(); i++) {
                org.json.JSONArray point = pointsArray.getJSONArray(i);
                points[i][0] = (float) point.getDouble(0);
                points[i][1] = (float) point.getDouble(1);
            }
            
            // Apply to surveillance engine
            pipeline.getSentry().setRoiFromPolygon(points);
            logger.info("ROI applied with " + points.length + " vertices");
            
        } catch (Exception e) {
            logger.error("Failed to apply ROI", e);
        }
    }
    
    /**
     * Get current ROI data.
     */
    private JSONObject getRoiData() {
        JSONObject roi = new JSONObject();
        try {
            // For now, return empty - would need to store ROI points
            roi.put("enabled", false);
            roi.put("points", new org.json.JSONArray());
        } catch (Exception e) {
            logger.error("Failed to get ROI data", e);
        }
        return roi;
    }
    
    private JSONObject getDefaultConfig() throws Exception {
        JSONObject config = new JSONObject();
        
        // Read persisted preference (not runtime state) for the UI toggle
        boolean enabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
        
        config.put("enabled", enabled);
        config.put("noiseThreshold", 0.0001);
        config.put("lightThreshold", 0.4);
        config.put("aiEnabled", true);
        config.put("scheduleEnabled", false);
        config.put("bitrate", CameraDaemon.getRecordingBitrate());
        config.put("codec", CameraDaemon.getRecordingCodec());
        
        // Get actual values from sentry config if available
        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
            CameraDaemon.getGpuPipeline();
        
        com.overdrive.app.surveillance.SurveillanceConfig sentryConfig = null;
        
        if (pipeline != null && pipeline.getSentry() != null) {
            com.overdrive.app.surveillance.SurveillanceEngineGpu sentry = pipeline.getSentry();
            sentryConfig = sentry.getConfig();
        }
        
        // If sentry not running, try to load from file
        if (sentryConfig == null) {
            try {
                com.overdrive.app.surveillance.SurveillanceConfigManager configManager =
                    new com.overdrive.app.surveillance.SurveillanceConfigManager();
                if (configManager.configExists()) {
                    sentryConfig = configManager.loadConfig();
                    logger.info("Loaded config from file for GET_CONFIG (sentry not running)");
                }
            } catch (Exception e) {
                logger.error("Failed to load config from file", e);
            }
        }
        
        if (sentryConfig != null) {
            // Read from config object
            config.put("minObjectSize", sentryConfig.getMinObjectSize());
            config.put("aiConfidence", sentryConfig.getAiConfidence());
            config.put("detectPerson", sentryConfig.isDetectPerson());
            config.put("detectCar", sentryConfig.isDetectCar());
            config.put("detectBike", sentryConfig.isDetectBike());
            config.put("flashImmunity", sentryConfig.getFlashImmunity());
            config.put("preEventBufferSeconds", sentryConfig.getPreRecordSeconds());
            config.put("postEventBufferSeconds", sentryConfig.getPostRecordSeconds());
            config.put("blockSensitivity", sentryConfig.getSensitivity());
            config.put("requiredActiveBlocks", sentryConfig.getRequiredBlocks());
            
            // SOTA: Return sensitivity as slider value (1-5) based on requiredBlocks
            // Maps: 1=Strict(req=4), 2=Conservative(req=3), 3=Default(req=2), 4=Sensitive(req=2,density=16), 5=Aggressive(req=1)
            int reqBlocks = sentryConfig.getRequiredBlocks();
            int sensitivityLevel;
            if (reqBlocks >= 4) {
                sensitivityLevel = 1;  // Strict
            } else if (reqBlocks == 3) {
                sensitivityLevel = 2;  // Conservative
            } else if (reqBlocks == 2) {
                sensitivityLevel = 3;  // Default (could be 3 or 4, default to 3)
            } else {
                sensitivityLevel = 5;  // Aggressive
            }
            config.put("sensitivity", sensitivityLevel);
            
            // SOTA: Return distance as slider value (1-5) based on minObjectSize
            float minSize = sentryConfig.getMinObjectSize();
            int distanceLevel;
            if (minSize >= 0.22f) {
                distanceLevel = 1;  // ~3m (near)
            } else if (minSize >= 0.15f) {
                distanceLevel = 2;  // ~5m
            } else if (minSize >= 0.10f) {
                distanceLevel = 3;  // ~8m
            } else if (minSize >= 0.06f) {
                distanceLevel = 4;  // ~10m
            } else {
                distanceLevel = 5;  // ~15m (far)
            }
            config.put("distance", distanceLevel);
        } else {
            // Defaults when no config available
            config.put("sensitivity", 3);  // Default (slider value 1-5)
            config.put("distance", 3);     // ~8m (slider value 1-5)
            config.put("minObjectSize", 0.08);
            config.put("aiConfidence", 0.6);
            config.put("detectPerson", true);
            config.put("detectCar", true);
            config.put("detectBike", true);
            config.put("flashImmunity", 2);  // Default MEDIUM
            config.put("preEventBufferSeconds", 5);
            config.put("postEventBufferSeconds", 10);
            config.put("blockSensitivity", 0.04);
            config.put("requiredActiveBlocks", 2);
        }
        
        // SOTA: Add lastModified timestamp for web UI sync detection
        config.put("lastModified", com.overdrive.app.config.UnifiedConfigManager.getLastModified());
        
        return config;
    }
    
    private JSONObject getSurveillanceStatus() throws Exception {
        JSONObject status = new JSONObject();
        
        // Read from persisted config (not in-memory flag)
        boolean enabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
        boolean active = CameraDaemon.isSurveillanceActive();
        
        status.put("enabled", enabled);
        status.put("active", active);
        status.put("recording", false);
        
        return status;
    }
    
    // ==================== VEHICLE DATA HELPERS ====================
    
    private JSONObject getVehicleData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        return monitor.getAllData();
    }
    
    private JSONObject getBatteryVoltageData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.BatteryVoltageData data = monitor.getBatteryVoltage();
        
        if (data == null) throw new Exception("Battery voltage data not available");
        
        JSONObject json = new JSONObject();
        json.put("level", data.level);
        json.put("levelName", data.levelName);
        json.put("isWarning", data.isWarning);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getBatteryPowerData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.BatteryPowerData data = monitor.getBatteryPower();
        
        if (data == null) throw new Exception("Battery power data not available");
        
        JSONObject json = new JSONObject();
        json.put("voltageVolts", data.voltageVolts);
        json.put("isWarning", data.isWarning);
        json.put("isCritical", data.isCritical);
        json.put("healthStatus", data.getHealthStatus());
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getBatterySocData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.BatterySocData data = monitor.getBatterySoc();
        
        if (data == null) throw new Exception("Battery SOC data not available");
        
        JSONObject json = new JSONObject();
        json.put("socPercent", data.socPercent);
        json.put("isLow", data.isLow);
        json.put("isCritical", data.isCritical);
        json.put("status", data.getStatus());
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getChargingStateData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.ChargingStateData data = monitor.getChargingState();
        
        if (data == null) throw new Exception("Charging state data not available");
        
        JSONObject json = new JSONObject();
        json.put("stateCode", data.stateCode);
        json.put("stateName", data.stateName);
        json.put("status", data.status.name());
        json.put("isError", data.isError);
        json.put("errorType", data.errorType);
        json.put("chargingPowerKW", data.chargingPowerKW);
        json.put("isDischarging", data.isDischarging);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getChargingPowerData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.ChargingStateData data = monitor.getChargingState();
        
        if (data == null) throw new Exception("Charging power data not available");
        
        JSONObject json = new JSONObject();
        json.put("chargingPowerKW", data.chargingPowerKW);
        json.put("isDischarging", data.isDischarging);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getDrivingRangeData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.DrivingRangeData data = monitor.getDrivingRange();
        
        if (data == null) throw new Exception("Driving range data not available");
        
        JSONObject json = new JSONObject();
        json.put("elecRangeKm", data.elecRangeKm);
        json.put("fuelRangeKm", data.fuelRangeKm);
        json.put("totalRangeKm", data.totalRangeKm);
        json.put("isLow", data.isLow);
        json.put("isCritical", data.isCritical);
        json.put("status", data.getStatus());
        json.put("isPureEV", data.isPureEV());
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    // ==================== GPS SIDECAR HANDLER ====================
    
    /**
     * Handle GPS update from LocationSidecarService.
     * Directly updates GpsMonitor's cached values - no file needed.
     */
    private void handleGpsUpdate(JSONObject request) {
        try {
            double lat = request.optDouble("lat", 0.0);
            double lng = request.optDouble("lng", 0.0);
            float speed = (float) request.optDouble("speed", 0.0);
            float heading = (float) request.optDouble("heading", 0.0);
            float accuracy = (float) request.optDouble("accuracy", 0.0);
            double altitude = request.optDouble("altitude", 0.0);
            long time = request.optLong("time", System.currentTimeMillis());
            
            // Directly update GpsMonitor
            com.overdrive.app.monitor.GpsMonitor.getInstance()
                .updateFromIpc(lat, lng, speed, heading, accuracy, time, altitude);
            
        } catch (Exception e) {
            logger.error("Failed to update GPS", e);
        }
    }

    // ==================== ABRP COMMAND HANDLERS ====================

    private void handleSetAbrpConfig(JSONObject request, JSONObject response) throws Exception {
        if (abrpConfig == null) {
            response.put("success", false);
            response.put("error", "ABRP not initialized");
            return;
        }

        String token = request.optString("token", null);
        if (token != null && !token.isEmpty()) {
            abrpConfig.setUserToken(token);
        }

        if (request.has("car_model")) {
            abrpConfig.setCarModel(request.optString("car_model", null));
        }

        boolean wasEnabled = abrpConfig.isEnabled();
        if (request.has("enabled")) {
            abrpConfig.setEnabled(request.optBoolean("enabled", false));
        }

        abrpConfig.save();

        // Start or stop service if enabled state changed
        boolean nowEnabled = abrpConfig.isEnabled();
        if (abrpService != null && wasEnabled != nowEnabled) {
            if (nowEnabled && abrpConfig.isConfigured()) {
                abrpService.start();
                logger.info("ABRP service started via IPC");
            } else {
                abrpService.stop();
                logger.info("ABRP service stopped via IPC");
            }
        }

        response.put("success", true);
        response.put("message", "ABRP config updated");
    }

    private void handleGetAbrpConfig(JSONObject response) throws Exception {
        if (abrpConfig == null) {
            response.put("success", false);
            response.put("error", "ABRP not initialized");
            return;
        }

        response.put("success", true);
        response.put("config", abrpConfig.toJson());
    }

    private void handleGetAbrpStatus(JSONObject response) throws Exception {
        if (abrpService == null) {
            // Return basic status when service is not initialized
            JSONObject status = new JSONObject();
            status.put("running", false);
            status.put("totalUploads", 0);
            status.put("failedUploads", 0);
            status.put("lastUploadTime", 0);
            status.put("consecutiveFailures", 0);
            status.put("currentInterval", 0);
            response.put("success", true);
            response.put("status", status);
            return;
        }

        response.put("success", true);
        response.put("status", abrpService.getStatus());
    }

    private void handleDeleteAbrpToken(JSONObject response) throws Exception {
        if (abrpConfig == null) {
            response.put("success", false);
            response.put("error", "ABRP not initialized");
            return;
        }

        abrpConfig.deleteToken();
        abrpConfig.save();

        if (abrpService != null && abrpService.isRunning()) {
            abrpService.stop();
            logger.info("ABRP service stopped after token deletion");
        }

        response.put("success", true);
        response.put("message", "ABRP token deleted");
    }

    // ==================== MQTT HANDLER METHODS ====================

    private void handleGetMqttConnections(JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", "MQTT not initialized");
            return;
        }
        response.put("success", true);
        response.put("connections", mqttManager.getAllStatus());
        response.put("maxConnections", com.overdrive.app.mqtt.MqttConnectionStore.MAX_CONNECTIONS);
    }

    private void handleAddMqttConnection(JSONObject request, JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", "MQTT not initialized");
            return;
        }

        com.overdrive.app.mqtt.MqttConnectionConfig added = mqttManager.addConnection(request);
        if (added != null) {
            response.put("success", true);
            response.put("connection", added.toSafeJson());
            response.put("message", "MQTT connection added");
        } else {
            response.put("success", false);
            response.put("error", "Max connections reached (" + com.overdrive.app.mqtt.MqttConnectionStore.MAX_CONNECTIONS + ")");
        }
    }

    private void handleUpdateMqttConnection(JSONObject request, JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", "MQTT not initialized");
            return;
        }

        String id = request.optString("id", null);
        if (id == null || id.isEmpty()) {
            response.put("success", false);
            response.put("error", "Missing connection ID");
            return;
        }

        boolean updated = mqttManager.updateConnection(id, request);
        response.put("success", updated);
        if (updated) {
            response.put("message", "MQTT connection updated");
        } else {
            response.put("error", "Connection not found: " + id);
        }
    }

    private void handleDeleteMqttConnection(JSONObject request, JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", "MQTT not initialized");
            return;
        }

        String id = request.optString("id", null);
        if (id == null || id.isEmpty()) {
            response.put("success", false);
            response.put("error", "Missing connection ID");
            return;
        }

        boolean deleted = mqttManager.deleteConnection(id);
        response.put("success", deleted);
        if (deleted) {
            response.put("message", "MQTT connection deleted");
        } else {
            response.put("error", "Connection not found: " + id);
        }
    }

    private void handleGetMqttStatus(JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", "MQTT not initialized");
            return;
        }
        response.put("success", true);
        response.put("connections", mqttManager.getAllStatus());
    }

    private void handleGetMqttTelemetry(JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", "MQTT not initialized");
            return;
        }
        response.put("success", true);
        response.put("telemetry", mqttManager.getLatestTelemetry());
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {
            logger.error("Error stopping IPC server", e);
        }
        // SOTA FIX: Kill all active connections immediately on shutdown
        threadPool.shutdownNow();
    }
}
