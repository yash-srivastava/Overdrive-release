package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.storage.StorageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;

/**
 * Quality Settings API Handler - manages recording and streaming quality settings.
 * 
 * SOTA: Also handles storage limit settings via StorageManager.
 * 
 * Endpoints:
 * - GET /api/settings/quality - Get current quality settings
 * - POST /api/settings/quality - Update quality settings
 * - GET /api/settings/storage - Get storage limit settings
 * - POST /api/settings/storage - Update storage limit settings
 */
public class QualitySettingsApiHandler {
    
    // Stored quality settings
    private static String recordingQuality = "NORMAL";
    private static String recordingBitrate = "MEDIUM";  // LOW (2Mbps), MEDIUM (3Mbps), HIGH (6Mbps)
    private static String recordingCodec = "H264";      // H264 or H265
    
    private static final String UNIFIED_CONFIG_FILE = "/data/local/tmp/overdrive_config.json";
    private static final String LEGACY_SETTINGS_FILE = "/data/local/tmp/camera_settings.json";
    
    /**
     * Handle quality settings API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/settings/quality") && method.equals("GET")) {
            sendQualitySettings(out);
            return true;
        }
        if (path.equals("/api/settings/quality") && method.equals("POST")) {
            handleQualitySettingsPost(out, body);
            return true;
        }
        // SOTA: Storage limit settings
        if (path.equals("/api/settings/storage") && method.equals("GET")) {
            sendStorageSettings(out);
            return true;
        }
        if (path.equals("/api/settings/storage") && method.equals("POST")) {
            handleStorageSettingsPost(out, body);
            return true;
        }
        // SOTA: Unified config endpoint for cross-UID sync (proximityGuard, recording, streaming)
        if (path.equals("/api/settings/unified") && method.equals("GET")) {
            sendUnifiedConfig(out);
            return true;
        }
        if (path.equals("/api/settings/unified") && method.equals("POST")) {
            handleUnifiedConfigPost(out, body);
            return true;
        }
        // Telemetry overlay settings
        if (path.equals("/api/settings/telemetry-overlay") && method.equals("GET")) {
            sendTelemetryOverlaySettings(out);
            return true;
        }
        if (path.equals("/api/settings/telemetry-overlay") && method.equals("POST")) {
            handleTelemetryOverlayPost(out, body);
            return true;
        }
        return false;
    }
    
    /**
     * Send storage limit settings.
     */
    private static void sendStorageSettings(OutputStream out) throws Exception {
        StorageManager storage = StorageManager.getInstance();
        
        // SOTA: Refresh SD card detection if not currently available
        // This handles the case where SD card was inserted after app start
        if (!storage.isSdCardAvailable()) {
            storage.refreshSdCard();
        }
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("recordingsLimitMb", storage.getRecordingsLimitMb());
        response.put("surveillanceLimitMb", storage.getSurveillanceLimitMb());
        response.put("minLimitMb", StorageManager.getMinLimitMb());
        response.put("maxLimitMb", StorageManager.getMaxLimitMb());
        response.put("maxLimitMbSdCard", StorageManager.getMaxLimitMbSdCard());
        response.put("recordingsPath", storage.getRecordingsPath());
        response.put("surveillancePath", storage.getSurveillancePath());
        response.put("recordingsSize", storage.getRecordingsSize());
        response.put("surveillanceSize", storage.getSurveillanceSize());
        response.put("recordingsCount", storage.getRecordingsCount());
        response.put("surveillanceCount", storage.getSurveillanceCount());
        
        // SOTA: Storage type selection
        response.put("recordingsStorageType", storage.getRecordingsStorageType().name());
        response.put("surveillanceStorageType", storage.getSurveillanceStorageType().name());
        
        // SD card info
        response.put("sdCardAvailable", storage.isSdCardAvailable());
        response.put("sdCardPath", storage.getSdCardPath());
        if (storage.isSdCardAvailable()) {
            response.put("sdCardFreeSpace", storage.getSdCardFreeSpace());
            response.put("sdCardTotalSpace", storage.getSdCardTotalSpace());
            response.put("sdCardFreeFormatted", StorageManager.formatSize(storage.getSdCardFreeSpace()));
            response.put("sdCardTotalFormatted", StorageManager.formatSize(storage.getSdCardTotalSpace()));
        }
        
        // Internal storage info
        response.put("internalFreeSpace", storage.getInternalFreeSpace());
        response.put("internalTotalSpace", storage.getInternalTotalSpace());
        response.put("internalFreeFormatted", StorageManager.formatSize(storage.getInternalFreeSpace()));
        response.put("internalTotalFormatted", StorageManager.formatSize(storage.getInternalTotalSpace()));
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Handle storage settings POST.
     */
    private static void handleStorageSettingsPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            StorageManager storage = StorageManager.getInstance();
            
            // Handle storage type changes first (before limit changes)
            boolean storageTypeChanged = false;
            
            if (settings.has("recordingsStorageType")) {
                String typeStr = settings.getString("recordingsStorageType").toUpperCase();
                StorageManager.StorageType type = "SD_CARD".equals(typeStr) ? 
                    StorageManager.StorageType.SD_CARD : StorageManager.StorageType.INTERNAL;
                boolean success = storage.setRecordingsStorageType(type);
                if (success) {
                    storageTypeChanged = true;
                    CameraDaemon.log("Recordings storage type set to: " + type);
                } else {
                    CameraDaemon.log("Failed to set recordings storage type to SD_CARD - not available");
                }
            }
            
            if (settings.has("surveillanceStorageType")) {
                String typeStr = settings.getString("surveillanceStorageType").toUpperCase();
                StorageManager.StorageType type = "SD_CARD".equals(typeStr) ? 
                    StorageManager.StorageType.SD_CARD : StorageManager.StorageType.INTERNAL;
                boolean success = storage.setSurveillanceStorageType(type);
                if (success) {
                    storageTypeChanged = true;
                    CameraDaemon.log("Surveillance storage type set to: " + type);
                    
                    // Update running sentry engine's output directory to match new storage
                    try {
                        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                            CameraDaemon.getGpuPipeline();
                        if (pipeline != null && pipeline.getSentry() != null) {
                            pipeline.getSentry().setEventOutputDir(storage.getSurveillanceDir());
                            CameraDaemon.log("Updated sentry output dir: " + 
                                storage.getSurveillanceDir().getAbsolutePath());
                        }
                    } catch (Exception e) {
                        CameraDaemon.log("Warning: could not update sentry output dir: " + e.getMessage());
                    }
                } else {
                    CameraDaemon.log("Failed to set surveillance storage type to SD_CARD - not available");
                }
            }
            
            // Calculate how much will be deleted before applying changes
            long recordingsToDelete = 0;
            long surveillanceToDelete = 0;
            int recordingsFilesToDelete = 0;
            int surveillanceFilesToDelete = 0;
            
            if (settings.has("recordingsLimitMb")) {
                long newLimit = settings.getLong("recordingsLimitMb");
                long currentSize = storage.getRecordingsSize();
                long newLimitBytes = newLimit * 1024 * 1024;
                if (currentSize > newLimitBytes) {
                    recordingsToDelete = currentSize - newLimitBytes;
                    // Estimate files to delete (rough estimate based on average file size)
                    int count = storage.getRecordingsCount();
                    if (count > 0) {
                        long avgSize = currentSize / count;
                        recordingsFilesToDelete = (int) Math.ceil((double) recordingsToDelete / avgSize);
                    }
                }
                storage.setRecordingsLimitMb(newLimit);
                CameraDaemon.log("Recordings limit set to: " + newLimit + " MB");
            }
            
            if (settings.has("surveillanceLimitMb")) {
                long newLimit = settings.getLong("surveillanceLimitMb");
                long currentSize = storage.getSurveillanceSize();
                long newLimitBytes = newLimit * 1024 * 1024;
                if (currentSize > newLimitBytes) {
                    surveillanceToDelete = currentSize - newLimitBytes;
                    // Estimate files to delete
                    int count = storage.getSurveillanceCount();
                    if (count > 0) {
                        long avgSize = currentSize / count;
                        surveillanceFilesToDelete = (int) Math.ceil((double) surveillanceToDelete / avgSize);
                    }
                }
                storage.setSurveillanceLimitMb(newLimit);
                CameraDaemon.log("Surveillance limit set to: " + newLimit + " MB");
            }
            
            // Run cleanup async to not block HTTP response
            new Thread(() -> {
                storage.runCleanup();
                CameraDaemon.log("Storage cleanup completed after limit change");
            }, "StorageLimitCleanup").start();
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("recordingsLimitMb", storage.getRecordingsLimitMb());
            response.put("surveillanceLimitMb", storage.getSurveillanceLimitMb());
            response.put("recordingsStorageType", storage.getRecordingsStorageType().name());
            response.put("surveillanceStorageType", storage.getSurveillanceStorageType().name());
            response.put("recordingsPath", storage.getRecordingsPath());
            response.put("surveillancePath", storage.getSurveillancePath());
            
            // Include cleanup info in response
            if (recordingsToDelete > 0 || surveillanceToDelete > 0) {
                JSONObject cleanup = new JSONObject();
                if (recordingsToDelete > 0) {
                    cleanup.put("recordingsToDelete", StorageManager.formatSize(recordingsToDelete));
                    cleanup.put("recordingsFilesEstimate", recordingsFilesToDelete);
                }
                if (surveillanceToDelete > 0) {
                    cleanup.put("surveillanceToDelete", StorageManager.formatSize(surveillanceToDelete));
                    cleanup.put("surveillanceFilesEstimate", surveillanceFilesToDelete);
                }
                response.put("cleanup", cleanup);
                response.put("message", "Storage settings updated. Oldest files will be deleted to meet new limit.");
            } else if (storageTypeChanged) {
                response.put("message", "Storage location changed successfully.");
            } else {
                response.put("message", "Storage settings updated.");
            }
            
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("Error setting storage limits: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
    
    /**
     * Send full unified config for cross-UID sync.
     * Returns the entire config including proximityGuard, recording, streaming sections.
     */
    private static void sendUnifiedConfig(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            if (unifiedFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject config = new JSONObject(sb.toString());
                response.put("config", config);
                response.put("lastModified", unifiedFile.lastModified());
            } else {
                // Return default config structure
                JSONObject config = new JSONObject();
                config.put("version", 1);
                
                JSONObject recording = new JSONObject();
                recording.put("bitrate", recordingBitrate);
                recording.put("codec", recordingCodec);
                recording.put("quality", recordingQuality);
                config.put("recording", recording);
                
                JSONObject streaming = new JSONObject();
                streaming.put("quality", StreamingApiHandler.getStreamingQuality());
                config.put("streaming", streaming);
                
                JSONObject proximityGuard = new JSONObject();
                proximityGuard.put("triggerLevel", "RED");
                proximityGuard.put("preRecordSeconds", 5);
                proximityGuard.put("postRecordSeconds", 10);
                config.put("proximityGuard", proximityGuard);
                
                response.put("config", config);
                response.put("lastModified", System.currentTimeMillis());
            }
        } catch (Exception e) {
            CameraDaemon.log("sendUnifiedConfig: Error reading config: " + e.getMessage());
            // Return minimal default
            JSONObject config = new JSONObject();
            JSONObject proximityGuard = new JSONObject();
            proximityGuard.put("triggerLevel", "RED");
            proximityGuard.put("preRecordSeconds", 5);
            proximityGuard.put("postRecordSeconds", 10);
            config.put("proximityGuard", proximityGuard);
            response.put("config", config);
        }
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Handle unified config POST - updates a specific section.
     * Body format: { "section": "proximityGuard", "data": { ... } }
     */
    private static void handleUnifiedConfigPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject request = new JSONObject(body);
            String section = request.optString("section", "");
            JSONObject data = request.optJSONObject("data");
            
            if (section.isEmpty() || data == null) {
                HttpResponse.sendJsonError(out, "Missing 'section' or 'data' in request");
                return;
            }
            
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            JSONObject unified;
            
            if (unifiedFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                unified = new JSONObject(sb.toString());
            } else {
                unified = new JSONObject();
                unified.put("version", 1);
            }
            
            // SOTA: Merge into existing section (not replace) to preserve keys
            // that are set independently (e.g. surveillanceEnabled is set via toggle,
            // not via the detection settings form). Replacing would wipe it.
            JSONObject existingSection = unified.optJSONObject(section);
            if (existingSection == null) {
                existingSection = new JSONObject();
            }
            java.util.Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                existingSection.put(key, data.get(key));
            }
            unified.put(section, existingSection);
            unified.put("lastModified", System.currentTimeMillis());
            
            // Write back
            FileWriter writer = new FileWriter(unifiedFile);
            writer.write(unified.toString(2));
            writer.close();
            
            unifiedFile.setReadable(true, false);
            unifiedFile.setWritable(true, false);
            
            CameraDaemon.log("Unified config section '" + section + "' updated");
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("section", section);
            response.put("message", "Config section updated");
            
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("Error updating unified config: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    private static void sendQualitySettings(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        // Read from unified config for cross-UID sync
        String currentBitrate = recordingBitrate;
        String currentCodec = recordingCodec;
        String currentRecQuality = recordingQuality;
        String currentStreamQuality = StreamingApiHandler.getStreamingQuality();
        long lastModified = System.currentTimeMillis();
        
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            if (unifiedFile.exists()) {
                lastModified = unifiedFile.lastModified();
                
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject unified = new JSONObject(sb.toString());
                
                JSONObject recording = unified.optJSONObject("recording");
                if (recording != null) {
                    String fileBitrate = recording.optString("bitrate", "");
                    if (fileBitrate.equals("LOW") || fileBitrate.equals("MEDIUM") || fileBitrate.equals("HIGH")) {
                        currentBitrate = fileBitrate;
                        recordingBitrate = fileBitrate;
                    }
                    
                    String fileCodec = recording.optString("codec", "");
                    if (fileCodec.equals("H264") || fileCodec.equals("H265")) {
                        currentCodec = fileCodec;
                        recordingCodec = fileCodec;
                    }
                    
                    String fileQuality = recording.optString("quality", "");
                    if (fileQuality.equals("LOW") || fileQuality.equals("REDUCED") || fileQuality.equals("NORMAL")) {
                        currentRecQuality = fileQuality;
                        recordingQuality = fileQuality;
                    }
                }
                
                JSONObject streaming = unified.optJSONObject("streaming");
                if (streaming != null) {
                    String fileStreamQuality = streaming.optString("quality", "");
                    if (!fileStreamQuality.isEmpty()) {
                        currentStreamQuality = fileStreamQuality;
                        StreamingApiHandler.setStreamingQuality(fileStreamQuality);
                    }
                }
            }
        } catch (Exception e) {
            CameraDaemon.log("sendQualitySettings: Could not read unified config: " + e.getMessage());
        }
        
        response.put("recordingQuality", currentRecQuality);
        response.put("streamingQuality", currentStreamQuality);
        response.put("recordingBitrate", currentBitrate);
        response.put("recordingCodec", currentCodec);
        response.put("lastModified", lastModified);
        
        // Camera FPS setting
        int currentFps = 15;
        try {
            org.json.JSONObject cameraConfig = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cameraConfig != null) {
                currentFps = cameraConfig.optInt("targetFps", 15);
            }
        } catch (Exception e) { /* use default */ }
        response.put("cameraFps", currentFps);
        
        // Add bitrate info for UI
        JSONObject bitrateInfo = new JSONObject();
        bitrateInfo.put("LOW", "2 Mbps (~15-20 MB/2min)");
        bitrateInfo.put("MEDIUM", "3 Mbps (~22-30 MB/2min)");
        bitrateInfo.put("HIGH", "6 Mbps (~90 MB/2min)");
        response.put("bitrateOptions", bitrateInfo);
        
        // Add codec info for UI
        JSONObject codecInfo = new JSONObject();
        codecInfo.put("H264", "H.264/AVC (Compatible)");
        codecInfo.put("H265", "H.265/HEVC (50% smaller)");
        response.put("codecOptions", codecInfo);
        
        // Add FPS options for UI
        JSONObject fpsInfo = new JSONObject();
        fpsInfo.put("8", "8 FPS (Low power)");
        fpsInfo.put("15", "15 FPS (Balanced)");
        fpsInfo.put("25", "25 FPS (Smooth)");
        response.put("fpsOptions", fpsInfo);
        
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleQualitySettingsPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            
            if (settings.has("recordingQuality")) {
                String recQuality = settings.getString("recordingQuality");
                if (recQuality.equals("LOW") || recQuality.equals("REDUCED") || recQuality.equals("NORMAL")) {
                    recordingQuality = recQuality;
                    CameraDaemon.log("Recording quality set to: " + recQuality);
                    CameraDaemon.setRecordingQuality(recQuality);
                }
            }
            
            if (settings.has("streamingQuality")) {
                String streamQuality = settings.getString("streamingQuality").toUpperCase();
                if (streamQuality.equals("ULTRA_LOW") || streamQuality.equals("LOW") || 
                    streamQuality.equals("MEDIUM") || streamQuality.equals("HIGH") || 
                    streamQuality.equals("ULTRA_HIGH") || streamQuality.equals("LQ") || streamQuality.equals("HQ")) {
                    StreamingApiHandler.setStreamingQuality(streamQuality);
                    CameraDaemon.log("Streaming quality set to: " + streamQuality);
                    CameraDaemon.setStreamingQuality(streamQuality);
                }
            }
            
            if (settings.has("recordingBitrate")) {
                String bitrate = settings.getString("recordingBitrate").toUpperCase();
                if (bitrate.equals("LOW") || bitrate.equals("MEDIUM") || bitrate.equals("HIGH")) {
                    recordingBitrate = bitrate;
                    CameraDaemon.log("Recording bitrate set to: " + bitrate);
                    CameraDaemon.setRecordingBitrate(bitrate);
                }
            }
            
            if (settings.has("recordingCodec")) {
                String codec = settings.getString("recordingCodec").toUpperCase();
                if (codec.equals("H264") || codec.equals("H265")) {
                    recordingCodec = codec;
                    CameraDaemon.log("Recording codec set to: " + codec);
                    CameraDaemon.setRecordingCodec(codec);
                }
            }
            
            if (settings.has("cameraFps")) {
                int fps = settings.getInt("cameraFps");
                if (fps == 8 || fps == 15 || fps == 25) {
                    // Save to unified config
                    try {
                        org.json.JSONObject camCfg = com.overdrive.app.config.UnifiedConfigManager
                            .loadConfig().optJSONObject("camera");
                        if (camCfg == null) camCfg = new org.json.JSONObject();
                        camCfg.put("targetFps", fps);
                        com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", camCfg);
                    } catch (Exception e) {
                        CameraDaemon.log("Failed to save camera FPS: " + e.getMessage());
                    }
                    // Apply to running camera (takes effect on next camera open/restart)
                    com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
                    if (pipeline != null && pipeline.getCamera() != null) {
                        pipeline.getCamera().setTargetFps(fps);
                    }
                    CameraDaemon.log("Camera FPS set to: " + fps + " (applies on next camera restart)");
                }
            }
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("recordingBitrate", recordingBitrate);
            response.put("recordingCodec", recordingCodec);
            response.put("note", recordingCodec.equals("H265") ? 
                "H.265 provides ~50% smaller files. Restart recording to apply codec change." : null);
            
            persistSettings();
            
            HttpResponse.sendJson(out, response.toString());
            
        } catch (Exception e) {
            CameraDaemon.log("Error setting quality: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }

    /**
     * Loads persisted settings from unified config file.
     * Called during HttpServer initialization.
     */
    public static void loadPersistedSettings() {
        // Try unified config first
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            if (unifiedFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject unified = new JSONObject(sb.toString());
                
                JSONObject recording = unified.optJSONObject("recording");
                if (recording != null) {
                    if (recording.has("bitrate")) {
                        String bitrate = recording.getString("bitrate");
                        if (bitrate.equals("LOW") || bitrate.equals("MEDIUM") || bitrate.equals("HIGH")) {
                            recordingBitrate = bitrate;
                            CameraDaemon.log("Restored recording bitrate from unified: " + bitrate);
                        }
                    }
                    if (recording.has("codec")) {
                        String codec = recording.getString("codec");
                        if (codec.equals("H264") || codec.equals("H265")) {
                            recordingCodec = codec;
                            CameraDaemon.log("Restored recording codec from unified: " + codec);
                        }
                    }
                    if (recording.has("quality")) {
                        String quality = recording.getString("quality");
                        if (quality.equals("LOW") || quality.equals("REDUCED") || quality.equals("NORMAL")) {
                            recordingQuality = quality;
                            CameraDaemon.log("Restored recording quality from unified: " + quality);
                        }
                    }
                }
                
                JSONObject streaming = unified.optJSONObject("streaming");
                if (streaming != null && streaming.has("quality")) {
                    String quality = streaming.getString("quality");
                    StreamingApiHandler.setStreamingQuality(quality);
                    CameraDaemon.log("Restored streaming quality from unified: " + quality);
                }
                
                CameraDaemon.log("Settings loaded from unified config: " + UNIFIED_CONFIG_FILE);
                return;
            }
        } catch (Exception e) {
            CameraDaemon.log("Could not load from unified config: " + e.getMessage());
        }
        
        // Fallback to legacy settings file
        loadLegacySettings();
    }

    private static void loadLegacySettings() {
        try {
            File file = new File(LEGACY_SETTINGS_FILE);
            CameraDaemon.log("Loading settings from legacy: " + LEGACY_SETTINGS_FILE + " (exists=" + file.exists() + ")");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                
                JSONObject settings = new JSONObject(sb.toString());
                
                if (settings.has("recordingBitrate")) {
                    String bitrate = settings.getString("recordingBitrate");
                    if (bitrate.equals("LOW") || bitrate.equals("MEDIUM") || bitrate.equals("HIGH")) {
                        recordingBitrate = bitrate;
                    }
                }
                if (settings.has("recordingCodec")) {
                    String codec = settings.getString("recordingCodec");
                    if (codec.equals("H264") || codec.equals("H265")) {
                        recordingCodec = codec;
                    }
                }
                if (settings.has("recordingQuality")) {
                    String quality = settings.getString("recordingQuality");
                    if (quality.equals("LOW") || quality.equals("REDUCED") || quality.equals("NORMAL")) {
                        recordingQuality = quality;
                    }
                }
                if (settings.has("streamingQuality")) {
                    String quality = settings.getString("streamingQuality");
                    StreamingApiHandler.setStreamingQuality(quality);
                }
                
                CameraDaemon.log("Settings loaded from legacy " + LEGACY_SETTINGS_FILE);
                // Migrate to unified config
                persistSettings();
            }
        } catch (Exception e) {
            CameraDaemon.log("Could not load legacy settings: " + e.getMessage());
        }
    }
    
    /**
     * Persists current settings to unified config file.
     */
    public static void persistSettings() {
        try {
            File unifiedFile = new File(UNIFIED_CONFIG_FILE);
            JSONObject unified;
            
            if (unifiedFile.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(unifiedFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                unified = new JSONObject(sb.toString());
            } else {
                unified = new JSONObject();
                unified.put("version", 1);
            }
            
            // Update recording section
            JSONObject recording = unified.optJSONObject("recording");
            if (recording == null) recording = new JSONObject();
            recording.put("bitrate", recordingBitrate);
            recording.put("codec", recordingCodec);
            recording.put("quality", recordingQuality);
            unified.put("recording", recording);
            
            // Update streaming section
            JSONObject streaming = unified.optJSONObject("streaming");
            if (streaming == null) streaming = new JSONObject();
            streaming.put("quality", StreamingApiHandler.getStreamingQuality());
            unified.put("streaming", streaming);
            
            unified.put("lastModified", System.currentTimeMillis());
            
            FileWriter writer = new FileWriter(unifiedFile);
            writer.write(unified.toString(2));
            writer.close();
            
            unifiedFile.setReadable(true, false);
            unifiedFile.setWritable(true, false);
            
            CameraDaemon.log("Settings persisted to unified config: " + UNIFIED_CONFIG_FILE);
        } catch (Exception e) {
            CameraDaemon.log("Could not persist settings: " + e.getMessage());
        }
    }

    // Static getters for cross-component access
    public static String getRecordingQuality() { return recordingQuality; }
    public static String getRecordingBitrate() { return recordingBitrate; }
    public static String getRecordingCodec() { return recordingCodec; }
    
    // Static setters for app UI and IPC
    public static void setRecordingQuality(String quality) { 
        if (quality.equals("LOW") || quality.equals("REDUCED") || quality.equals("NORMAL")) {
            recordingQuality = quality;
        }
    }
    
    public static void setRecordingBitrate(String bitrate) {
        if (bitrate.equals("LOW") || bitrate.equals("MEDIUM") || bitrate.equals("HIGH")) {
            recordingBitrate = bitrate;
            CameraDaemon.setRecordingBitrate(bitrate);
            persistSettings();
        }
    }
    
    public static void setRecordingCodec(String codec) {
        if (codec.equals("H264") || codec.equals("H265")) {
            recordingCodec = codec;
            CameraDaemon.setRecordingCodec(codec);
            persistSettings();
        }
    }
    
    // Static setters for IPC server (updates variable only, no CameraDaemon call)
    public static void setRecordingBitrateStatic(String bitrate) {
        if (bitrate.equals("LOW") || bitrate.equals("MEDIUM") || bitrate.equals("HIGH")) {
            recordingBitrate = bitrate;
        }
    }
    
    public static void setRecordingCodecStatic(String codec) {
        if (codec.equals("H264") || codec.equals("H265")) {
            recordingCodec = codec;
        }
    }

    /**
     * Send telemetry overlay settings.
     */
    private static void sendTelemetryOverlaySettings(OutputStream out) throws Exception {
        JSONObject overlayConfig = com.overdrive.app.config.UnifiedConfigManager.getTelemetryOverlay();
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("enabled", overlayConfig.optBoolean("enabled", false));
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Handle telemetry overlay settings POST.
     */
    private static void handleTelemetryOverlayPost(OutputStream out, String body) throws Exception {
        try {
            JSONObject settings = new JSONObject(body);
            boolean enabled = settings.optBoolean("enabled", false);

            JSONObject overlayConfig = new JSONObject();
            overlayConfig.put("enabled", enabled);
            com.overdrive.app.config.UnifiedConfigManager.setTelemetryOverlay(overlayConfig);

            // Notify pipeline
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline != null) {
                pipeline.setOverlayEnabled(enabled);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("enabled", enabled);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            CameraDaemon.log("Error setting telemetry overlay: " + e.getMessage());
            HttpResponse.sendJsonError(out, e.getMessage());
        }
    }
}
