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
        
        // V2 Pipeline settings
        if (sentryConfig != null) {
            config.put("environmentPreset", sentryConfig.getEnvironmentPreset());
            config.put("sensitivityLevel", sentryConfig.getSensitivityLevel());
            config.put("detectionZone", sentryConfig.getDetectionZone());
            config.put("loiteringTime", sentryConfig.getLoiteringTimeSeconds());
            boolean[] cameras = sentryConfig.getCameraEnabled();
            config.put("cameraFront", cameras[0]);
            config.put("cameraRight", cameras[1]);
            config.put("cameraLeft", cameras[2]);
            config.put("cameraRear", cameras[3]);
            config.put("motionHeatmap", sentryConfig.isMotionHeatmapEnabled());
            config.put("filterDebugLog", sentryConfig.isFilterDebugLogEnabled());
            config.put("shadowFilter", sentryConfig.getShadowFilterMode());
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
                boolean left  = configJson.optBoolean("cameraLeft",  existing[2]);
                boolean rear  = configJson.optBoolean("cameraRear",  existing[3]);
                sentryConfig.setCameraEnabled(0, front);
                sentryConfig.setCameraEnabled(1, right);
                sentryConfig.setCameraEnabled(2, left);
                sentryConfig.setCameraEnabled(3, rear);
                if (sentry != null) {
                    sentry.setV2QuadrantEnabled(0, front);
                    sentry.setV2QuadrantEnabled(1, right);
                    sentry.setV2QuadrantEnabled(2, left);
                    sentry.setV2QuadrantEnabled(3, rear);
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
            
            if (configChanged) {
                try {
                    SurveillanceConfigManager configManager = new SurveillanceConfigManager();
                    configManager.saveConfig(sentryConfig);
                    if (sentry != null) sentry.setConfig(sentryConfig);
                } catch (Exception e) {
                    CameraDaemon.log("Failed to save config: " + e.getMessage());
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
}
