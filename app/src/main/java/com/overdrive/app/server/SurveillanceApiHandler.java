package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;
import com.overdrive.app.surveillance.SurveillanceConfig;
import com.overdrive.app.surveillance.SurveillanceConfigManager;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;
import com.overdrive.app.surveillance.MotionPipelineV2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Surveillance API Handler - manages surveillance configuration and status.
 * 
 * SOTA: Distance slider (1-5) controls minObjectSize for AI detection range.
 * SOTA: Sensitivity slider (1-5) controls requiredBlocks for motion detection.
 * Block size is LOCKED at 32 - never changes.
 */
public class SurveillanceApiHandler {
    
    private static final String UNIFIED_CONFIG_FILE = "/data/local/tmp/overdrive_config.json";
    
    /**
     * Handle surveillance API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        // Strip query parameters for path matching
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        
        if (cleanPath.equals("/api/surveillance/config") && method.equals("GET")) {
            sendConfig(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/config") && method.equals("POST")) {
            handleConfigPost(out, body);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/status")) {
            sendStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/enable")) {
            handleEnable(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/disable")) {
            handleDisable(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/heatmap")) {
            sendHeatmap(out);
            return true;
        }
        if (cleanPath.startsWith("/api/surveillance/snapshot/")) {
            try {
                int quadrant = Integer.parseInt(cleanPath.substring("/api/surveillance/snapshot/".length()));
                sendQuadrantSnapshot(out, quadrant);
            } catch (NumberFormatException e) {
                HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_invalid_quadrant_id"));
            }
            return true;
        }
        if (cleanPath.equals("/api/surveillance/filterlog")) {
            sendFilterLog(out);
            return true;
        }
        return false;
    }
    
    private static void sendConfig(OutputStream out) throws Exception {
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        JSONObject config = new JSONObject();
        
        SurveillanceConfig sentryConfig = null;
        SurveillanceEngineGpu sentry = null;
        
        if (gpuPipeline != null && gpuPipeline.getSentry() != null) {
            sentry = gpuPipeline.getSentry();
            sentryConfig = sentry.getConfig();
        }
        
        if (sentryConfig == null) {
            try {
                SurveillanceConfigManager configManager = new SurveillanceConfigManager();
                if (configManager.configExists()) {
                    sentryConfig = configManager.loadConfig();
                }
            } catch (Exception e) {
                CameraDaemon.log("Failed to load config: " + e.getMessage());
            }
        }
        
        // Read persisted preference (not runtime state) for the UI toggle
        config.put("enabled", com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled());
        
        if (sentryConfig != null) {
            config.put("sadThreshold", sentry != null ? sentry.getSadThreshold() : 0.05f);
            config.put("preRecordSeconds", sentryConfig.getPreRecordSeconds());
            config.put("postRecordSeconds", sentryConfig.getPostRecordSeconds());
            config.put("totalBlocks", sentry != null ? sentry.getTotalBlocks() : 300);
            config.put("flashImmunity", sentryConfig.getFlashImmunity());
            config.put("aiEnabled", true);
            config.put("aiConfidence", sentryConfig.getAiConfidence());
            config.put("minObjectSize", sentryConfig.getMinObjectSize());
            config.put("detectPerson", sentryConfig.isDetectPerson());
            config.put("detectCar", sentryConfig.isDetectCar());
            config.put("detectBike", sentryConfig.isDetectBike());
            
            // SOTA: Distance preset and block settings
            config.put("distancePreset", sentryConfig.getDistancePreset().name());
            config.put("blockSize", sentryConfig.getBlockSize());
            config.put("maxDistanceM", sentryConfig.getMaxDistanceM());
            config.put("nightMode", sentryConfig.isNightMode());
            config.put("shadowThreshold", sentryConfig.getShadowThreshold());
            config.put("densityThreshold", sentryConfig.getDensityThreshold());
            config.put("alarmBlockThreshold", sentryConfig.getAlarmBlockThreshold());
            
            // SOTA: Return sensitivity as slider value (1-5) based on requiredBlocks
            int reqBlocks = sentryConfig.getRequiredBlocks();
            int sensitivityLevel;
            if (reqBlocks >= 4) {
                sensitivityLevel = 1;  // Strict
            } else if (reqBlocks == 3) {
                sensitivityLevel = 2;  // Conservative
            } else if (reqBlocks == 2) {
                sensitivityLevel = 3;  // Default
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
            config.put("sadThreshold", 0.05f);
            config.put("sensitivity", 3);  // Default slider value
            config.put("distance", 3);     // Default slider value
            config.put("totalBlocks", 300);
            config.put("flashImmunity", 2);
            config.put("aiEnabled", true);
            config.put("aiConfidence", 0.4f);
            config.put("minObjectSize", 0.12f);
            config.put("detectPerson", true);
            config.put("detectCar", true);
            config.put("detectBike", true);
            config.put("preRecordSeconds", 5);
            config.put("postRecordSeconds", 10);
        }
        
        // Load recording settings from unified config
        try {
            JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
            config.put("recordingBitrate", recording.optString("bitrate", "MEDIUM"));
            config.put("recordingCodec", recording.optString("codec", "H264"));
        } catch (Exception e) {
            config.put("recordingBitrate", "MEDIUM");
            config.put("recordingCodec", "H264");
        }
        
        try {
            java.io.File unifiedFile = new java.io.File(UNIFIED_CONFIG_FILE);
            config.put("lastModified", unifiedFile.exists() ? unifiedFile.lastModified() : System.currentTimeMillis());
        } catch (Exception e) {
            config.put("lastModified", System.currentTimeMillis());
        }
        
        // SOTA: Safe Location status
        com.overdrive.app.surveillance.SafeLocationManager safeMgr =
            com.overdrive.app.surveillance.SafeLocationManager.getInstance();
        config.put("safeZoneSuppressed", CameraDaemon.isSafeZoneSuppressed());
        config.put("inSafeZone", safeMgr.isInSafeZone());
        config.put("safeZoneName", safeMgr.getCurrentZoneName());
        
        // SOTA: Deterrent action setting
        JSONObject survConfig = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
        config.put("deterrentAction", survConfig.optString("deterrentAction", "silent"));
        config.put("deterrentCooldownSeconds", survConfig.optInt("deterrentCooldownSeconds", 60));
        
        // SOTA: BYD Cloud connection status
        JSONObject bydCloud = com.overdrive.app.config.UnifiedConfigManager.getBydCloud();
        config.put("bydCloudEnabled", bydCloud.optBoolean("enabled", false));
        config.put("bydCloudUsername", bydCloud.optString("username", ""));
        config.put("bydCloudVin", bydCloud.optString("vin", ""));
        
        // V2 Pipeline settings
        if (sentryConfig != null) {
            config.put("environmentPreset", sentryConfig.getEnvironmentPreset());
            config.put("sensitivityLevel", sentryConfig.getSensitivityLevel());
            config.put("detectionZone", sentryConfig.getDetectionZone());
            config.put("loiteringTime", sentryConfig.getLoiteringTimeSeconds());
            boolean[] cameras = sentryConfig.getCameraEnabled();
            config.put("cameraFront", cameras[0]);
            config.put("cameraRight", cameras[1]);
            config.put("cameraRear", cameras[2]);
            config.put("cameraLeft", cameras[3]);
            config.put("motionHeatmap", sentryConfig.isMotionHeatmapEnabled());
            config.put("filterDebugLog", sentryConfig.isFilterDebugLogEnabled());
            config.put("telegramSendStartPing", sentryConfig.isTelegramSendStartPing());
            config.put("shadowFilter", sentryConfig.getShadowFilterMode());
            
            // ROI polygons and enabled flags
            org.json.JSONObject roiObj = new org.json.JSONObject();
            String[] qKeys = {"Q0", "Q1", "Q2", "Q3"};
            for (int q = 0; q < 4; q++) {
                // Always include polygon if it exists (even when disabled)
                float[][] poly = sentryConfig.getRoiPolygon(q);
                if (poly != null && poly.length >= 3) {
                    org.json.JSONArray polyArr = new org.json.JSONArray();
                    for (float[] vertex : poly) {
                        org.json.JSONObject pt = new org.json.JSONObject();
                        pt.put("x", vertex[0]);
                        pt.put("y", vertex[1]);
                        polyArr.put(pt);
                    }
                    roiObj.put(qKeys[q], polyArr);
                }
                // Per-quadrant block mask and enabled flag from unified config (source of truth)
                try {
                    org.json.JSONObject survCfg = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                    org.json.JSONArray blocks = survCfg.optJSONArray("roiBlocks_" + qKeys[q]);
                    if (blocks != null) config.put("roiBlocks_" + qKeys[q], blocks);
                    // Read enabled flag from persisted config, not in-memory sentryConfig
                    if (survCfg.has("roiEnabled_" + qKeys[q])) {
                        config.put("roiEnabled_" + qKeys[q], survCfg.optBoolean("roiEnabled_" + qKeys[q], false));
                    } else {
                        config.put("roiEnabled_" + qKeys[q], sentryConfig.isRoiEnabled(q));
                    }
                } catch (Exception ignored) {
                    config.put("roiEnabled_" + qKeys[q], sentryConfig.isRoiEnabled(q));
                }
            }
            config.put("roiPolygons", roiObj);
            
            // Schedule — read from persisted config file (source of truth)
            try {
                org.json.JSONObject survCfg = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                config.put("scheduleEnabled", survCfg.optBoolean("scheduleEnabled", false));
                org.json.JSONArray persistedRules = survCfg.optJSONArray("scheduleRules");
                if (persistedRules != null) {
                    config.put("scheduleRules", persistedRules);
                } else {
                    config.put("scheduleRules", new org.json.JSONArray());
                }
            } catch (Exception e) {
                // Fallback to in-memory if file read fails
                config.put("scheduleEnabled", sentryConfig.getSchedule().isEnabled());
                org.json.JSONArray schedRules = new org.json.JSONArray();
                for (com.overdrive.app.surveillance.SurveillanceSchedule.Rule rule : sentryConfig.getSchedule().getRules()) {
                    schedRules.put(rule.toJson());
                }
                config.put("scheduleRules", schedRules);
            }
            
            // Camera ID info
            try {
                org.json.JSONObject camCfg = com.overdrive.app.config.UnifiedConfigManager
                    .loadConfig().optJSONObject("camera");
                if (camCfg != null) {
                    config.put("cameraId", camCfg.optInt("probedCameraId", -1));
                    config.put("cameraManualOverride", camCfg.optBoolean("manualOverride", false));
                }
            } catch (Exception ignored) {}
        } else {
            config.put("environmentPreset", "outdoor");
            config.put("sensitivityLevel", 3);
            config.put("detectionZone", "normal");
            config.put("loiteringTime", 3);
            config.put("cameraFront", true);
            config.put("cameraRight", true);
            config.put("cameraLeft", true);
            config.put("cameraRear", true);
            config.put("motionHeatmap", false);
            config.put("filterDebugLog", false);
            config.put("telegramSendStartPing", false);
            config.put("shadowFilter", 2);
        }
        
        response.put("config", config);
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void sendStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        java.util.Map<String, Object> statusMap = CameraDaemon.getSurveillanceStatus();
        JSONObject statusJson = new JSONObject(statusMap);
        response.put("status", statusJson);
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void handleConfigPost(OutputStream out, String body) throws Exception {
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        
        try {
            JSONObject configJson = new JSONObject(body);
            
            SurveillanceEngineGpu sentry = null;
            if (gpuPipeline != null) {
                sentry = gpuPipeline.getSentry();
            }
            
            SurveillanceConfig sentryConfig = null;
            if (sentry != null) {
                sentryConfig = sentry.getConfig();
            }
            if (sentryConfig == null) {
                try {
                    SurveillanceConfigManager configManager = new SurveillanceConfigManager();
                    sentryConfig = configManager.configExists() ? configManager.loadConfig() : new SurveillanceConfig();
                } catch (Exception e) {
                    sentryConfig = new SurveillanceConfig();
                }
            }
            
            boolean configChanged = false;
            
            if (sentry != null && configJson.has("sadThreshold")) {
                sentry.setSadThreshold((float) configJson.optDouble("sadThreshold", 0.05));
            }
            
            if (configJson.has("preRecordSeconds")) {
                int val = configJson.optInt("preRecordSeconds", 5);
                sentryConfig.setPreRecordSeconds(val);
                if (sentry != null) sentry.setPreRecordSeconds(val);
                configChanged = true;
            }
            
            if (configJson.has("postRecordSeconds")) {
                int val = configJson.optInt("postRecordSeconds", 10);
                sentryConfig.setPostRecordSeconds(val);
                if (sentry != null) sentry.setPostRecordSeconds(val);
                configChanged = true;
            }
            
            if (configJson.has("sensitivity")) {
                // SOTA: Handle sensitivity slider (1-5) - controls motion detection thresholds
                Object sensVal = configJson.opt("sensitivity");
                if (sensVal instanceof Number) {
                    int sensitivityLevel = ((Number) sensVal).intValue();
                    if (sensitivityLevel >= 1 && sensitivityLevel <= 5) {
                        // Map slider value to motion detection thresholds
                        // 1=Strict (req=4), 2=Conservative (req=3), 3=Default (req=2), 4=Sensitive (req=2), 5=Aggressive (req=1)
                        int requiredBlocks;
                        switch (sensitivityLevel) {
                            case 1: requiredBlocks = 4; break;
                            case 2: requiredBlocks = 3; break;
                            case 3: requiredBlocks = 2; break;
                            case 4: requiredBlocks = 2; break;
                            case 5: requiredBlocks = 1; break;
                            default: requiredBlocks = 2; break;
                        }
                        
                        int sensitivityPercent = sensitivityLevel * 20;
                        sentryConfig.setUnifiedSensitivity(sensitivityPercent);
                        sentryConfig.setRequiredBlocks(requiredBlocks);
                        
                        if (sentry != null) {
                            sentry.setUnifiedSensitivity(sensitivityPercent);
                            sentry.setRequiredActiveBlocks(requiredBlocks);
                        }
                        
                        configChanged = true;
                        CameraDaemon.log(String.format("Motion sensitivity set to level %d (%d%%, alarm=%d blocks)",
                            sensitivityLevel, sensitivityPercent, requiredBlocks));
                    }
                }
                // Legacy string sensitivity ("LOW"/"MEDIUM"/"HIGH") is no longer supported
            }
            
            // AI detection settings
            if (configJson.has("aiConfidence")) {
                float aiConf = (float) configJson.optDouble("aiConfidence", 0.4);
                sentryConfig.setAiConfidence(aiConf);
                configChanged = true;
            }
            if (configJson.has("minObjectSize")) {
                float minObjSize = (float) configJson.optDouble("minObjectSize", 0.12);
                sentryConfig.setMinObjectSize(minObjSize);
                configChanged = true;
            }
            if (configJson.has("detectPerson")) {
                sentryConfig.setDetectPerson(configJson.optBoolean("detectPerson", true));
                configChanged = true;
            }
            if (configJson.has("detectCar")) {
                sentryConfig.setDetectCar(configJson.optBoolean("detectCar", true));
                configChanged = true;
            }
            if (configJson.has("detectBike")) {
                sentryConfig.setDetectBike(configJson.optBoolean("detectBike", true));
                configChanged = true;
            }
            
            // Apply object filters to running engine
            if (sentry != null && configChanged) {
                sentry.setObjectFilters(
                    sentryConfig.getMinObjectSize(),
                    sentryConfig.getAiConfidence(),
                    sentryConfig.isDetectPerson(),
                    sentryConfig.isDetectCar(),
                    sentryConfig.isDetectBike()
                );
            }
            
            // Flash immunity setting
            if (configJson.has("flashImmunity")) {
                int val = configJson.optInt("flashImmunity", 2);
                sentryConfig.setFlashImmunity(val);
                if (sentry != null) sentry.setFlashImmunity(val);
                configChanged = true;
            }
            
            // SOTA: Deterrent action setting (silent / flash_lights / find_car)
            if (configJson.has("deterrentAction")) {
                String action = configJson.optString("deterrentAction", "silent");
                if ("silent".equals(action) || "flash_lights".equals(action) || "find_car".equals(action)) {
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", java.util.Collections.singletonMap("deterrentAction", action));
                    CameraDaemon.log("Deterrent action set to: " + action);
                    // Reset deterrent so it picks up new config
                    try {
                        com.overdrive.app.byd.cloud.BydCloudDeterrent.getInstance().reset();
                    } catch (Exception ignored) {}
                }
            }
            
            if (configJson.has("deterrentCooldownSeconds")) {
                int cooldown = configJson.optInt("deterrentCooldownSeconds", 60);
                if (cooldown >= 10 && cooldown <= 600) {
                    com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", java.util.Collections.singletonMap("deterrentCooldownSeconds", cooldown));
                }
            }
            
            // SOTA: Handle distance slider (1-5) - ONLY controls minObjectSize (AI detection range)
            // Motion sensitivity (requiredBlocks, densityThreshold) is handled separately
            if (configJson.has("distance") || configJson.has("distancePreset")) {
                String distanceStr = configJson.has("distance") ? 
                    configJson.optString("distance", "3") : 
                    configJson.optString("distancePreset", "MEDIUM");
                
                CameraDaemon.log("Distance field received: " + distanceStr);
                
                // Map distance to minObjectSize for AI detection
                float minObjSize;
                String distanceLabel;
                
                try {
                    int distanceValue = Integer.parseInt(distanceStr);
                    
                    if (distanceValue <= 5) {
                        // Slider index mapping (1-5):
                        // 1 = Close (~3m, 25%), 2 = Near (~5m, 18%), 3 = Medium (~8m, 12%), 
                        // 4 = Far (~10m, 8%), 5 = Very Far (~15m, 5%)
                        switch (distanceValue) {
                            case 1: minObjSize = 0.25f; distanceLabel = "CLOSE (~3m)"; break;
                            case 2: minObjSize = 0.18f; distanceLabel = "NEAR (~5m)"; break;
                            case 3: minObjSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                            case 4: minObjSize = 0.08f; distanceLabel = "FAR (~10m)"; break;
                            case 5: minObjSize = 0.05f; distanceLabel = "VERY_FAR (~15m)"; break;
                            default: minObjSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                        }
                        CameraDaemon.log("Distance slider index " + distanceValue + " mapped to: " + distanceLabel);
                    } else {
                        // Treat as actual distance in meters (6m+)
                        if (distanceValue <= 5) {
                            minObjSize = 0.18f; distanceLabel = "NEAR (~5m)";
                        } else if (distanceValue <= 8) {
                            minObjSize = 0.12f; distanceLabel = "MEDIUM (~8m)";
                        } else if (distanceValue <= 12) {
                            minObjSize = 0.08f; distanceLabel = "FAR (~10m)";
                        } else {
                            minObjSize = 0.05f; distanceLabel = "VERY_FAR (~15m)";
                        }
                        CameraDaemon.log("Distance " + distanceValue + "m mapped to: " + distanceLabel);
                    }
                } catch (NumberFormatException e) {
                    // Handle preset names (CLOSE, MEDIUM, FAR, VERY_FAR)
                    String presetName = distanceStr.toUpperCase();
                    switch (presetName) {
                        case "CLOSE": minObjSize = 0.25f; distanceLabel = "CLOSE (~3m)"; break;
                        case "NEAR": minObjSize = 0.18f; distanceLabel = "NEAR (~5m)"; break;
                        case "FAR": minObjSize = 0.08f; distanceLabel = "FAR (~10m)"; break;
                        case "VERY_FAR": minObjSize = 0.05f; distanceLabel = "VERY_FAR (~15m)"; break;
                        case "MEDIUM":
                        default: minObjSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                    }
                    CameraDaemon.log("Distance preset name: " + distanceLabel);
                }
                
                // Only update minObjectSize - don't touch motion sensitivity settings
                sentryConfig.setMinObjectSize(minObjSize);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    float confidence = sentryConfig.getAiConfidence();
                    boolean dPerson = sentryConfig.isDetectPerson();
                    boolean dCar = sentryConfig.isDetectCar();
                    boolean dBike = sentryConfig.isDetectBike();
                    sentry.setObjectFilters(minObjSize, confidence, dPerson, dCar, dBike);
                }
                
                CameraDaemon.log(String.format("Distance set: %s (minObjectSize=%.0f%%)",
                    distanceLabel, minObjSize * 100));
            } else {
                CameraDaemon.log("No distance field in request - using existing config");
            }
            
            // SOTA: Handle night mode toggle
            if (configJson.has("nightMode")) {
                boolean val = configJson.optBoolean("nightMode", false);
                sentryConfig.setNightMode(val);
                if (sentry != null) sentry.setNightMode(val);
                configChanged = true;
            }
            
            // V2 Motion Detection settings
            // These are persisted to SurveillanceConfig; sentry.setConfig() below re-applies
            // them to the live pipeline via pipelineV2Config.applyConfig().
            if (configJson.has("environmentPreset")) {
                String preset = configJson.optString("environmentPreset", "outdoor");
                sentryConfig.setEnvironmentPreset(preset);
                if (sentry != null) sentry.applyV2EnvironmentPreset(preset);
                configChanged = true;
            }
            if (configJson.has("sensitivityLevel")) {
                int level = configJson.optInt("sensitivityLevel", 3);
                sentryConfig.setSensitivityLevel(level);
                if (sentry != null) sentry.applyV2Sensitivity(level);
                configChanged = true;
            }
            if (configJson.has("detectionZone")) {
                String zone = configJson.optString("detectionZone", "normal");
                sentryConfig.setDetectionZone(zone);
                configChanged = true;
            }
            if (configJson.has("loiteringTime")) {
                int seconds = configJson.optInt("loiteringTime", 3);
                sentryConfig.setLoiteringTimeSeconds(seconds);
                if (sentry != null) sentry.setV2LoiteringTime(seconds);
                configChanged = true;
            }
            if (configJson.has("shadowFilter")) {
                int mode = configJson.optInt("shadowFilter", 2);
                sentryConfig.setShadowFilterMode(mode);
                if (sentry != null) sentry.setV2ShadowFilterMode(mode);
                configChanged = true;
            }
            if (configJson.has("cameraFront") || configJson.has("cameraRight") ||
                configJson.has("cameraLeft")  || configJson.has("cameraRear")) {
                boolean[] existing = sentryConfig.getCameraEnabled();
                boolean front = configJson.optBoolean("cameraFront", existing[0]);
                boolean right = configJson.optBoolean("cameraRight", existing[1]);
                boolean rear  = configJson.optBoolean("cameraRear",  existing[2]);
                boolean left  = configJson.optBoolean("cameraLeft",  existing[3]);
                sentryConfig.setCameraEnabled(0, front);
                sentryConfig.setCameraEnabled(1, right);
                sentryConfig.setCameraEnabled(2, rear);
                sentryConfig.setCameraEnabled(3, left);
                if (sentry != null) {
                    sentry.setV2QuadrantEnabled(0, front);
                    sentry.setV2QuadrantEnabled(1, right);
                    sentry.setV2QuadrantEnabled(2, rear);
                    sentry.setV2QuadrantEnabled(3, left);
                }
                configChanged = true;
            }
            if (configJson.has("motionHeatmap")) {
                sentryConfig.setMotionHeatmapEnabled(configJson.optBoolean("motionHeatmap", false));
                configChanged = true;
            }
            if (configJson.has("filterDebugLog")) {
                boolean val = configJson.optBoolean("filterDebugLog", false);
                sentryConfig.setFilterDebugLogEnabled(val);
                if (sentry != null) sentry.setFilterDebugEnabled(val);
                configChanged = true;
            }
            if (configJson.has("telegramSendStartPing")) {
                sentryConfig.setTelegramSendStartPing(
                        configJson.optBoolean("telegramSendStartPing", false));
                configChanged = true;
            }
            
            // Per-quadrant ROI polygons
            if (configJson.has("roiPolygons")) {
                try {
                    org.json.JSONObject roiObj = configJson.getJSONObject("roiPolygons");
                    String[] quadrantKeys = {"Q0", "Q1", "Q2", "Q3"};
                    for (int q = 0; q < 4; q++) {
                        if (roiObj.has(quadrantKeys[q])) {
                            org.json.JSONArray polyArr = roiObj.optJSONArray(quadrantKeys[q]);
                            if (polyArr != null && polyArr.length() >= 3) {
                                float[][] polygon = new float[polyArr.length()][2];
                                for (int v = 0; v < polyArr.length(); v++) {
                                    org.json.JSONObject pt = polyArr.getJSONObject(v);
                                    polygon[v][0] = (float) pt.getDouble("x");
                                    polygon[v][1] = (float) pt.getDouble("y");
                                }
                                sentryConfig.setRoiPolygon(q, polygon);
                                // Only apply to C++ if ROI is enabled for this quadrant
                                if (sentryConfig.isRoiEnabled(q) && sentry != null) {
                                    sentry.applyQuadrantRoi(q, polygon);
                                }
                            } else if (polyArr == null) {
                                // Explicit null = clear polygon data
                                sentryConfig.clearRoi(q);
                                if (sentry != null) sentry.clearQuadrantRoi(q);
                            }
                        }
                    }
                    configChanged = true;
                } catch (Exception e) {
                    CameraDaemon.log("ROI parse error: " + e.getMessage());
                }
            }
            
            // Per-quadrant ROI enabled/disabled toggle (separate from polygon data)
            {
                String[] quadrantKeys = {"Q0", "Q1", "Q2", "Q3"};
                for (int q = 0; q < 4; q++) {
                    String enabledKey = "roiEnabled_" + quadrantKeys[q];
                    if (configJson.has(enabledKey)) {
                        boolean enabled = configJson.optBoolean(enabledKey, false);
                        if (enabled && sentryConfig.getRoiPolygon(q) != null) {
                            // Enable ROI — apply the persisted polygon to C++
                            sentryConfig.setRoiEnabled(q, true);
                            if (sentry != null) sentry.applyQuadrantRoi(q, sentryConfig.getRoiPolygon(q));
                        } else {
                            // Disable ROI — clear C++ mask but keep polygon in config
                            sentryConfig.setRoiEnabled(q, false);
                            if (sentry != null) sentry.clearQuadrantRoi(q);
                        }
                        configChanged = true;
                    }
                }
            }
            
            // Direct block mask per quadrant (from block-tap UI)
            // Accepts roiBlocks_Q0: [1,1,0,0,...] (70 elements, 1=active 0=inactive)
            {
                String[] quadrantKeys = {"Q0", "Q1", "Q2", "Q3"};
                for (int q = 0; q < 4; q++) {
                    String blocksKey = "roiBlocks_" + quadrantKeys[q];
                    if (configJson.has(blocksKey)) {
                        org.json.JSONArray arr = configJson.optJSONArray(blocksKey);
                        if (arr != null && arr.length() == 70) {
                            byte[] blockMask = new byte[70];
                            boolean anyActive = false;
                            for (int i = 0; i < 70; i++) {
                                blockMask[i] = (byte)(arr.optInt(i, 1) != 0 ? 1 : 0);
                                if (blockMask[i] != 0) anyActive = true;
                            }
                            if (anyActive) {
                                sentryConfig.setRoiEnabled(q, true);
                                // Store block mask as a synthetic polygon (not used, blocks are direct)
                                // Apply directly to C++ via JNI
                                try {
                                    com.overdrive.app.surveillance.NativeMotion.setQuadrantRoi(q, blockMask);
                                    CameraDaemon.log("ROI blocks applied to Q" + q + " via direct mask");
                                } catch (Exception e) {
                                    CameraDaemon.log("ROI blocks apply failed Q" + q + ": " + e.getMessage());
                                }
                            } else {
                                sentryConfig.setRoiEnabled(q, false);
                                if (sentry != null) sentry.clearQuadrantRoi(q);
                            }
                            // Persist the block array in unified config
                            try {
                                org.json.JSONObject survCfg = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                                survCfg.put(blocksKey, arr);
                                survCfg.put("roiEnabled_" + quadrantKeys[q], anyActive);
                                com.overdrive.app.config.UnifiedConfigManager.setSurveillance(survCfg);
                            } catch (Exception e) {
                                CameraDaemon.log("ROI blocks persist failed: " + e.getMessage());
                            }
                            configChanged = true;
                        }
                    }
                }
            }
            
            // Surveillance schedule
            if (configJson.has("scheduleEnabled") || configJson.has("scheduleRules")) {
                try {
                    com.overdrive.app.surveillance.SurveillanceSchedule schedule = sentryConfig.getSchedule();
                    if (configJson.has("scheduleEnabled")) {
                        schedule.setEnabled(configJson.optBoolean("scheduleEnabled", false));
                    }
                    if (configJson.has("scheduleRules")) {
                        schedule.getRules().clear();
                        org.json.JSONArray rulesArr = configJson.getJSONArray("scheduleRules");
                        for (int i = 0; i < rulesArr.length(); i++) {
                            com.overdrive.app.surveillance.SurveillanceSchedule.Rule rule =
                                com.overdrive.app.surveillance.SurveillanceSchedule.Rule.fromJson(rulesArr.getJSONObject(i));
                            if (rule != null) schedule.getRules().add(rule);
                        }
                    }
                    // Persist schedule to unified config
                    org.json.JSONObject survConfig = com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                    org.json.JSONObject scheduleJson = schedule.toJson();
                    survConfig.put("scheduleEnabled", scheduleJson.optBoolean("scheduleEnabled", false));
                    survConfig.put("scheduleRules", scheduleJson.optJSONArray("scheduleRules"));
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillance(survConfig);
                    CameraDaemon.log("Schedule updated: " + schedule.getSummary());
                    configChanged = true;
                    
                    // IMMEDIATE ENFORCEMENT: If surveillance is currently active and the
                    // new schedule says we're outside the window, stop it now. Don't wait
                    // for the 5-minute periodic checker.
                    // Conversely, if surveillance is inactive and the schedule now allows it,
                    // start it (respecting safe zone and other gates).
                    if (schedule.isEnabled()) {
                        boolean withinWindow = schedule.isActiveNow();
                        boolean currentlyActive = sentry != null && sentry.isActive();
                        
                        if (!withinWindow && currentlyActive) {
                            CameraDaemon.log("SCHEDULE: Immediately stopping surveillance (outside new schedule window)");
                            CameraDaemon.disableSurveillance();
                        } else if (withinWindow && !currentlyActive 
                                && !com.overdrive.app.monitor.AccMonitor.isAccOn()
                                && !CameraDaemon.isSafeZoneSuppressed()) {
                            CameraDaemon.log("SCHEDULE: Immediately enabling surveillance (within new schedule window)");
                            CameraDaemon.enableSurveillance();
                        }
                    } else {
                        // Schedule just disabled — if surveillance was suppressed by schedule,
                        // resume it now (respecting safe zone and ACC state)
                        boolean currentlyActive = sentry != null && sentry.isActive();
                        if (!currentlyActive 
                                && !com.overdrive.app.monitor.AccMonitor.isAccOn()
                                && !CameraDaemon.isSafeZoneSuppressed()
                                && com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled()) {
                            CameraDaemon.log("SCHEDULE: Disabled — resuming surveillance immediately");
                            CameraDaemon.enableSurveillance();
                        }
                    }
                } catch (Exception e) {
                    CameraDaemon.log("Schedule parse error: " + e.getMessage());
                }
            }
            
            // Manual camera ID override
            if (configJson.has("manualCameraId")) {
                int camId = configJson.optInt("manualCameraId", -1);
                if (camId >= 0 && camId <= 5) {
                    try {
                        org.json.JSONObject camCfg = new org.json.JSONObject();
                        camCfg.put("probedCameraId", camId);
                        camCfg.put("probedSurfaceMode", 0);
                        camCfg.put("probedAndValidated", true);
                        camCfg.put("manualOverride", true);
                        com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                        CameraDaemon.log("Manual camera ID set: " + camId + " (will take effect on next restart)");
                    } catch (Exception e) {
                        CameraDaemon.log("Failed to save manual camera ID: " + e.getMessage());
                    }
                    configChanged = true;
                }
            }
            if (configJson.has("clearManualCameraId") && configJson.optBoolean("clearManualCameraId", false)) {
                try {
                    org.json.JSONObject camCfg = new org.json.JSONObject();
                    camCfg.put("probedCameraId", -1);
                    camCfg.put("probedSurfaceMode", -1);
                    camCfg.put("probedAndValidated", false);
                    camCfg.put("manualOverride", false);
                    com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                    CameraDaemon.log("Manual camera ID cleared — will auto-detect on next restart");
                } catch (Exception e) {
                    CameraDaemon.log("Failed to clear manual camera ID: " + e.getMessage());
                }
                configChanged = true;
            }
            
            if (configChanged) {
                try {
                    // Apply config to the running surveillance engine
                    if (sentry != null) sentry.setConfig(sentryConfig);
                } catch (Exception e) {
                    CameraDaemon.log("Failed to apply config: " + e.getMessage());
                }

                // Persist to disk so settings survive ACC OFF/ON (pipeline.stop()
                // sets initialized=false, and the next start() reloads config from
                // disk via SurveillanceConfigManager.loadConfig() — without this
                // save, every detection/recording field reverts to the last
                // persisted value on the next ACC cycle).
                try {
                    new SurveillanceConfigManager().saveConfig(sentryConfig);
                } catch (Exception e) {
                    CameraDaemon.log("Failed to persist surveillance config: " + e.getMessage());
                }
            }
            
            // Save recording settings (bitrate, codec) to unified config
            boolean recordingChanged = false;
            if (configJson.has("recordingBitrate") || configJson.has("recordingCodec")) {
                try {
                    JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
                    if (configJson.has("recordingBitrate")) {
                        String bitrate = configJson.optString("recordingBitrate", "MEDIUM");
                        recording.put("bitrate", bitrate);
                        recordingChanged = true;
                        // Apply to running pipeline
                        try {
                            CameraDaemon.setRecordingBitrate(bitrate);
                        } catch (Exception e) {
                            CameraDaemon.log("Failed to apply bitrate to pipeline: " + e.getMessage());
                        }
                    }
                    if (configJson.has("recordingCodec")) {
                        String codec = configJson.optString("recordingCodec", "H264");
                        recording.put("codec", codec);
                        recordingChanged = true;
                        // Apply to running pipeline (will take effect on next recording)
                        try {
                            CameraDaemon.setRecordingCodec(codec);
                        } catch (Exception e) {
                            CameraDaemon.log("Failed to apply codec to pipeline: " + e.getMessage());
                        }
                    }
                    if (recordingChanged) {
                        com.overdrive.app.config.UnifiedConfigManager.setRecording(recording);
                        CameraDaemon.log("Recording settings saved: bitrate=" + recording.optString("bitrate") + 
                                        ", codec=" + recording.optString("codec"));
                    }
                } catch (Exception e) {
                    CameraDaemon.log("Failed to save recording settings: " + e.getMessage());
                }
            }
            
            HttpResponse.sendJsonSuccess(out);
            
        } catch (Exception e) {
            CameraDaemon.log("Error applying surveillance config: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
    
    private static void handleEnable(OutputStream out) throws Exception {
        // SOTA: Only persist the preference. Surveillance should only activate on ACC OFF.
        // Starting motion detection while driving wastes CPU/GPU and is meaningless.
        com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true);
        
        // Only actually start surveillance if ACC is currently OFF (sentry mode)
        boolean accIsOn = com.overdrive.app.monitor.AccMonitor.isAccOn();
        if (!accIsOn) {
            CameraDaemon.enableSurveillance();
        } else {
            CameraDaemon.log("Surveillance preference saved — will activate on next ACC OFF");
        }
        HttpResponse.sendJsonSuccess(out);
    }
    
    private static void handleDisable(OutputStream out) throws Exception {
        CameraDaemon.disableSurveillance();
        com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false);
        HttpResponse.sendJsonSuccess(out);
    }
    
    /**
     * Returns per-quadrant block confidence data for the motion heatmap overlay.
     * 
     * Response format:
     * {
     *   "quadrants": [
     *     { "id": 0, "name": "front", "enabled": true, "suppressed": false,
     *       "meanLuma": 85.3, "activeBlocks": 2, "confirmedBlocks": 1,
     *       "threatLevel": 2, "confidence": [0.0, 0.0, 0.3, 0.7, ...] },
     *     ...
     *   ],
     *   "gridCols": 10, "gridRows": 7
     * }
     */
    private static void sendHeatmap(OutputStream out) throws Exception {
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        
        JSONObject response = new JSONObject();
        response.put("gridCols", 10);
        response.put("gridRows", 7);
        
        // Include current stream view mode so the UI knows whether to draw
        // a 2x2 mosaic heatmap or a single full-frame quadrant heatmap.
        // 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left, -1=No stream
        int viewMode = -1;
        if (gpuPipeline != null) {
            if (gpuPipeline.isStreamingEnabled()) {
                viewMode = gpuPipeline.getStreamViewMode();
            }
            // If not streaming but surveillance is running, report the recording view.
            // Surveillance always records the mosaic, but the heatmap should show
            // all quadrants in a unified layout since there's no visible stream.
            if (viewMode < 0 && gpuPipeline.isSurveillanceMode()) {
                viewMode = 0;  // Mosaic (surveillance records all cameras)
            }
        }
        response.put("viewMode", viewMode);
        
        JSONArray quadrants = new JSONArray();
        String[] names = {"front", "right", "left", "rear"};
        
        SurveillanceEngineGpu sentry = (gpuPipeline != null) ? gpuPipeline.getSentry() : null;
        MotionPipelineV2.QuadrantResult[] results = (sentry != null) ? sentry.getV2Results() : null;
        
        for (int q = 0; q < 4; q++) {
            JSONObject qObj = new JSONObject();
            qObj.put("id", q);
            qObj.put("name", names[q]);
            
            if (results != null && results[q] != null) {
                qObj.put("enabled", true);
                qObj.put("suppressed", results[q].brightnessSuppressed);
                qObj.put("meanLuma", Math.round(results[q].meanLuma * 10) / 10.0);
                qObj.put("activeBlocks", results[q].activeBlocks);
                qObj.put("confirmedBlocks", results[q].confirmedBlocks);
                qObj.put("threatLevel", results[q].threatLevel);
                qObj.put("componentSize", results[q].componentSize);
                
                // Block confidence array (70 floats, rounded to 2 decimal places)
                JSONArray conf = new JSONArray();
                for (int i = 0; i < results[q].blockConfidence.length; i++) {
                    conf.put(Math.round(results[q].blockConfidence[i] * 100) / 100.0);
                }
                qObj.put("confidence", conf);
            } else {
                qObj.put("enabled", false);
                qObj.put("suppressed", false);
            }
            
            quadrants.put(qObj);
        }
        
        response.put("quadrants", quadrants);
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Returns recent filter debug log entries.
     * Ring buffer of the last 100 filter decisions (newest first).
     */
    private static void sendFilterLog(OutputStream out) throws Exception {
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        SurveillanceEngineGpu sentry = (gpuPipeline != null) ? gpuPipeline.getSentry() : null;
        
        JSONObject response = new JSONObject();
        JSONArray entries = new JSONArray();
        
        if (sentry != null) {
            String[] logEntries = sentry.getFilterLogEntries();
            for (String entry : logEntries) {
                if (entry != null) entries.put(entry);
            }
        }
        
        response.put("entries", entries);
        response.put("count", entries.length());
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Serves a JPEG snapshot of a specific camera quadrant for the ROI drawing UI.
     * 
     * Strategy:
     * 1. Try live mosaic frame from surveillance engine (available when sentry is running)
     * 2. Fall back to extracting a frame from the most recent event video on disk
     */
    private static void sendQuadrantSnapshot(OutputStream out, int quadrant) throws Exception {
        if (quadrant < 0 || quadrant > 3) {
            HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_invalid_quadrant_with_id", quadrant));
            return;
        }
        
        // Try live mosaic frame first
        byte[] mosaicRgb = null;
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        if (gpuPipeline != null && gpuPipeline.getSentry() != null) {
            mosaicRgb = gpuPipeline.getSentry().getLatestMosaicFrame();
        }
        
        if (mosaicRgb != null) {
            // Live frame available — crop quadrant from 640x480 mosaic
            sendQuadrantFromMosaic(out, mosaicRgb, quadrant, 640, 480);
            return;
        }
        
        // Fallback: extract frame from most recent event video on disk
        android.graphics.Bitmap frameBitmap = getFrameFromLatestEvent();
        if (frameBitmap != null) {
            try {
                // Event videos are mosaic (all 4 cameras) — crop the quadrant
                int fullW = frameBitmap.getWidth();
                int fullH = frameBitmap.getHeight();
                int qW = fullW / 2;
                int qH = fullH / 2;
                int startX = (quadrant % 2) * qW;
                int startY = (quadrant / 2) * qH;
                
                android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(
                        frameBitmap, startX, startY, qW, qH);
                
                java.io.ByteArrayOutputStream jpegOut = new java.io.ByteArrayOutputStream();
                cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, jpegOut);
                if (cropped != frameBitmap) cropped.recycle();
                frameBitmap.recycle();
                
                byte[] jpegBytes = jpegOut.toByteArray();
                String header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + jpegBytes.length + "\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "\r\n";
                out.write(header.getBytes());
                out.write(jpegBytes);
                out.flush();
            } catch (Exception e) {
                frameBitmap.recycle();
                HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_event_frame_failed_with_detail", e.getMessage()));
            }
            return;
        }
        
        HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_no_frame_available"));
    }
    
    /**
     * Crops a quadrant from a raw RGB mosaic byte array and sends as JPEG.
     */
    private static void sendQuadrantFromMosaic(OutputStream out, byte[] mosaicRgb, int quadrant, int mosaicW, int mosaicH) throws Exception {
        int qW = mosaicW / 2, qH = mosaicH / 2;
        int startX = (quadrant % 2) * qW;
        int startY = (quadrant / 2) * qH;
        
        int[] pixels = new int[qW * qH];
        for (int y = 0; y < qH; y++) {
            for (int x = 0; x < qW; x++) {
                int srcIdx = ((startY + y) * mosaicW + (startX + x)) * 3;
                if (srcIdx + 2 < mosaicRgb.length) {
                    int r = mosaicRgb[srcIdx] & 0xFF;
                    int g = mosaicRgb[srcIdx + 1] & 0xFF;
                    int b = mosaicRgb[srcIdx + 2] & 0xFF;
                    pixels[y * qW + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }
        }
        
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                pixels, qW, qH, android.graphics.Bitmap.Config.ARGB_8888);
        
        java.io.ByteArrayOutputStream jpegOut = new java.io.ByteArrayOutputStream();
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, jpegOut);
        bitmap.recycle();
        
        byte[] jpegBytes = jpegOut.toByteArray();
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: " + jpegBytes.length + "\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "\r\n";
        out.write(header.getBytes());
        out.write(jpegBytes);
        out.flush();
    }
    
    /**
     * Extracts a frame from the most recent event video in the surveillance directory.
     * Returns a Bitmap or null if no events exist.
     */
    private static android.graphics.Bitmap getFrameFromLatestEvent() {
        try {
            com.overdrive.app.storage.StorageManager storage = com.overdrive.app.storage.StorageManager.getInstance();
            java.io.File survDir = storage.getSurveillanceDir();
            if (survDir == null || !survDir.exists()) return null;
            
            java.io.File[] events = survDir.listFiles((dir, name) -> 
                    name.startsWith("event_") && name.endsWith(".mp4"));
            if (events == null || events.length == 0) return null;
            
            // Sort by name descending (newest first — filenames contain timestamp)
            java.util.Arrays.sort(events, (a, b) -> b.getName().compareTo(a.getName()));
            
            // Try the most recent file (fall back to next if extraction fails).
            // Use the FileDescriptor overload — setDataSource(String) NPEs on the
            // headless daemon because ActivityThread.currentApplication() is null.
            for (int i = 0; i < Math.min(3, events.length); i++) {
                android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
                try (java.io.FileInputStream fis = new java.io.FileInputStream(events[i])) {
                    retriever.setDataSource(fis.getFD());
                    android.graphics.Bitmap frame = retriever.getFrameAtTime(
                            1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) {
                        frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    }
                    if (frame != null) return frame;
                } catch (Exception e) {
                    // Try next file
                } finally {
                    try { retriever.release(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            // Storage not available
        }
        return null;
    }
}
