package com.overdrive.app.server;

import com.overdrive.app.storage.ExternalStorageCleaner;
import com.overdrive.app.storage.StorageManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * External Storage API Handler - manages SD card and CDR cleanup settings.
 * 
 * SOTA: Auto-cleanup of BYD dashcam (CDR) files to ensure Overdrive has space on SD card.
 * When Overdrive uses SD card for recordings/surveillance, this automatically manages
 * the BYD dashcam files to maintain reserved space.
 * 
 * Endpoints:
 * - GET  /api/storage/external          - Get SD card and CDR status
 * - POST /api/storage/external/config   - Update cleanup configuration
 * - POST /api/storage/external/cleanup  - Trigger manual cleanup
 * - GET  /api/storage/external/preview  - Preview what would be deleted
 * - POST /api/storage/external/refresh  - Refresh SD card detection
 */
public class ExternalStorageApiHandler {
    
    /**
     * Handle external storage API requests.
     * 
     * @param path Request path (after /api/storage/external)
     * @param method HTTP method
     * @param body Request body (for POST)
     * @param out Output stream
     * @return true if handled
     */
    public static boolean handle(String path, String method, String body, OutputStream out) throws Exception {
        
        if (path.equals("/api/storage/external") && method.equals("GET")) {
            getExternalStorageStatus(out);
            return true;
        }
        
        if (path.equals("/api/storage/external/config") && method.equals("POST")) {
            updateConfig(body, out);
            return true;
        }
        
        if (path.equals("/api/storage/external/cleanup") && method.equals("POST")) {
            triggerCleanup(body, out);
            return true;
        }
        
        if (path.startsWith("/api/storage/external/preview") && method.equals("GET")) {
            // Parse query params from path if present
            Map<String, String> params = new java.util.HashMap<>();
            int queryIdx = path.indexOf('?');
            if (queryIdx > 0) {
                String query = path.substring(queryIdx + 1);
                for (String param : query.split("&")) {
                    String[] kv = param.split("=", 2);
                    if (kv.length == 2) {
                        params.put(kv[0], kv[1]);
                    }
                }
            }
            previewCleanup(params, out);
            return true;
        }
        
        if (path.equals("/api/storage/external/refresh") && method.equals("POST")) {
            refreshPaths(out);
            return true;
        }
        
        return false;
    }
    
    /**
     * GET /api/storage/external
     * Returns SD card status, CDR info, and cleanup configuration.
     */
    private static void getExternalStorageStatus(OutputStream out) throws Exception {
        ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
        StorageManager storage = StorageManager.getInstance();
        
        // SOTA: Refresh SD card detection if not currently available
        // This handles the case where SD card was inserted after app start
        if (!cleaner.isSdCardAvailable()) {
            cleaner.refresh();
            storage.refreshSdCard();
        }
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        
        // SD Card info
        response.put("sdCardAvailable", cleaner.isSdCardAvailable());
        response.put("sdCardPath", cleaner.getSdCardPath());
        
        if (cleaner.isSdCardAvailable()) {
            long sdFree = cleaner.getSdCardFreeSpace();
            long sdTotal = cleaner.getSdCardTotalSpace();
            response.put("sdCardFree", sdFree);
            response.put("sdCardTotal", sdTotal);
            response.put("sdCardFreeFormatted", ExternalStorageCleaner.formatSize(sdFree));
            response.put("sdCardTotalFormatted", ExternalStorageCleaner.formatSize(sdTotal));
            response.put("sdCardUsedPercent", sdTotal > 0 ? 
                Math.round((sdTotal - sdFree) * 100.0 / sdTotal) : 0);
        }
        
        // CDR info
        response.put("cdrPath", cleaner.getCdrPath());
        
        if (cleaner.getCdrPath() != null) {
            long cdrUsage = cleaner.getCdrUsage();
            int cdrCount = cleaner.getCdrFileCount();
            long protectedSize = cleaner.getProtectedSize();
            long deletableSize = cleaner.getDeletableSize();
            
            response.put("cdrUsage", cdrUsage);
            response.put("cdrUsageFormatted", ExternalStorageCleaner.formatSize(cdrUsage));
            response.put("cdrFileCount", cdrCount);
            response.put("cdrProtectedSize", protectedSize);
            response.put("cdrProtectedFormatted", ExternalStorageCleaner.formatSize(protectedSize));
            response.put("cdrDeletableSize", deletableSize);
            response.put("cdrDeletableFormatted", ExternalStorageCleaner.formatSize(deletableSize));
        }
        
        // Cleanup configuration
        response.put("cleanupEnabled", cleaner.isEnabled());
        response.put("reservedSpaceMb", cleaner.getReservedSpaceMb());
        response.put("protectedHours", cleaner.getProtectedHours());
        response.put("minFilesKeep", cleaner.getMinFilesKeep());
        response.put("monitoringActive", cleaner.isMonitoringActive());
        
        // Statistics
        response.put("totalBytesFreed", cleaner.getTotalBytesFreed());
        response.put("totalBytesFreedFormatted", ExternalStorageCleaner.formatSize(cleaner.getTotalBytesFreed()));
        response.put("totalFilesDeleted", cleaner.getTotalFilesDeleted());
        response.put("lastCleanupTime", cleaner.getLastCleanupTime());
        
        // SOTA: Show if Overdrive is using SD card (auto-enable recommendation)
        boolean overdriveUsesSdCard = storage.getRecordingsStorageType() == StorageManager.StorageType.SD_CARD ||
                                       storage.getSurveillanceStorageType() == StorageManager.StorageType.SD_CARD;
        response.put("overdriveUsesSdCard", overdriveUsesSdCard);
        response.put("recommendAutoCleanup", overdriveUsesSdCard && !cleaner.isEnabled());
        
        sendJson(out, 200, response);
    }

    /**
     * POST /api/storage/external/config
     * Update cleanup configuration.
     * Body: { enabled, reservedSpaceMb, protectedHours, minFilesKeep }
     */
    private static void updateConfig(String body, OutputStream out) throws Exception {
        ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
        
        try {
            JSONObject config = new JSONObject(body);
            
            if (config.has("enabled")) {
                cleaner.setEnabled(config.getBoolean("enabled"));
            }
            if (config.has("reservedSpaceMb")) {
                cleaner.setReservedSpaceMb(config.getLong("reservedSpaceMb"));
            }
            if (config.has("protectedHours")) {
                cleaner.setProtectedHours(config.getInt("protectedHours"));
            }
            if (config.has("minFilesKeep")) {
                cleaner.setMinFilesKeep(config.getInt("minFilesKeep"));
            }
            
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("message", Messages.get("messages.external_storage_config_updated"));
            response.put("cleanupEnabled", cleaner.isEnabled());
            response.put("reservedSpaceMb", cleaner.getReservedSpaceMb());
            response.put("protectedHours", cleaner.getProtectedHours());
            response.put("minFilesKeep", cleaner.getMinFilesKeep());
            
            sendJson(out, 200, response);
            
        } catch (Exception e) {
            JSONObject error = new JSONObject();
            error.put("success", false);
            error.put("error", Messages.get("errors.external_storage_invalid_config_with_detail", e.getMessage()));
            sendJson(out, 400, error);
        }
    }
    
    /**
     * POST /api/storage/external/cleanup
     * Trigger manual cleanup.
     * Body (optional): { bytesToFree } - if not specified, uses reserved space calculation
     */
    private static void triggerCleanup(String body, OutputStream out) throws Exception {
        ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();

        // Refuse to force-delete OEM dashcam files when the feature is disabled.
        // Previously this endpoint silently bypassed the user's opt-out; an empty
        // POST or any caller could nuke 500MB+ of OEM recordings even when the
        // user had cleanup off.
        if (!cleaner.isEnabled()) {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("error", Messages.get("errors.external_storage_cleanup_disabled"));
            err.put("hint", "Enable cleanup first via POST /api/storage/external/config");
            sendJson(out, 403, err);
            return;
        }

        ExternalStorageCleaner.CleanupResult result;

        if (body != null && !body.isEmpty()) {
            try {
                JSONObject params = new JSONObject(body);
                long bytesToFree = params.optLong("bytesToFree", 0);
                if (bytesToFree > 0) {
                    result = cleaner.forceCleanup(bytesToFree);
                } else {
                    result = cleaner.ensureReservedSpace();
                }
            } catch (Exception e) {
                result = cleaner.ensureReservedSpace();
            }
        } else {
            // Empty body → run reserved-space-driven cleanup, not a hardcoded
            // 500MB force-delete. The previous default was a footgun: an
            // accidental empty POST silently deleted 500MB of OEM files even
            // when the SD card had plenty of free space.
            result = cleaner.ensureReservedSpace();
        }
        
        JSONObject response = new JSONObject();
        response.put("success", result.isSuccess());
        
        if (result.isSuccess()) {
            response.put("bytesFreed", result.bytesFreed);
            response.put("freedFormatted", ExternalStorageCleaner.formatSize(result.bytesFreed));
            response.put("filesDeleted", result.filesDeleted);
            
            JSONArray deletedArray = new JSONArray();
            for (String fileName : result.deletedFiles) {
                deletedArray.put(fileName);
            }
            response.put("deletedFiles", deletedArray);
            
            // Include updated stats
            response.put("sdCardFree", cleaner.getSdCardFreeSpace());
            response.put("sdCardFreeFormatted", ExternalStorageCleaner.formatSize(cleaner.getSdCardFreeSpace()));
        } else {
            response.put("error", result.error);
        }
        
        sendJson(out, 200, response);
    }
    
    /**
     * GET /api/storage/external/preview
     * Preview what would be deleted.
     * Query params: bytesToFree (optional, default 500MB)
     */
    private static void previewCleanup(Map<String, String> params, OutputStream out) throws Exception {
        ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
        
        long bytesToFree = 500 * 1024 * 1024; // Default 500MB
        if (params.containsKey("bytesToFree")) {
            try {
                bytesToFree = Long.parseLong(params.get("bytesToFree"));
            } catch (NumberFormatException ignored) {}
        }
        
        List<ExternalStorageCleaner.FileInfo> preview = cleaner.previewCleanup(bytesToFree);
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("targetBytes", bytesToFree);
        response.put("targetFormatted", ExternalStorageCleaner.formatSize(bytesToFree));
        
        JSONArray filesArray = new JSONArray();
        long totalSize = 0;
        
        for (ExternalStorageCleaner.FileInfo file : preview) {
            JSONObject fileObj = new JSONObject();
            fileObj.put("name", file.name);
            fileObj.put("size", file.size);
            fileObj.put("sizeFormatted", ExternalStorageCleaner.formatSize(file.size));
            fileObj.put("lastModified", file.lastModified);
            fileObj.put("path", file.path);
            filesArray.put(fileObj);
            totalSize += file.size;
        }
        
        response.put("files", filesArray);
        response.put("fileCount", preview.size());
        response.put("totalSize", totalSize);
        response.put("totalSizeFormatted", ExternalStorageCleaner.formatSize(totalSize));
        
        sendJson(out, 200, response);
    }
    
    /**
     * POST /api/storage/external/refresh
     * Refresh SD card and CDR path detection.
     */
    private static void refreshPaths(OutputStream out) throws Exception {
        ExternalStorageCleaner cleaner = ExternalStorageCleaner.getInstance();
        cleaner.refresh();
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("sdCardAvailable", cleaner.isSdCardAvailable());
        response.put("sdCardPath", cleaner.getSdCardPath());
        response.put("cdrPath", cleaner.getCdrPath());
        
        sendJson(out, 200, response);
    }
    
    /**
     * Send JSON response.
     */
    private static void sendJson(OutputStream out, int status, JSONObject json) throws Exception {
        String body = json.toString();
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        
        String statusText = status == 200 ? "OK" : (status == 400 ? "Bad Request" : "Error");
        String response = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + bodyBytes.length + "\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "\r\n";
        
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }
}
