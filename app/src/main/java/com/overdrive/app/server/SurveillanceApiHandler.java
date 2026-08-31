package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;
import com.overdrive.app.surveillance.SurveillanceConfig;
import com.overdrive.app.surveillance.SurveillanceConfigManager;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;
import com.overdrive.app.surveillance.MotionPipelineV2;
import com.overdrive.app.util.ScratchPaths;

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
    
    private static final String UNIFIED_CONFIG_FILE = ScratchPaths.path("overdrive_config.json");
    
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
        if (cleanPath.equals("/api/surveillance/camera-preview")) {
            sendCameraPreview(path, out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/prepare-restart") && method.equals("POST")) {
            handlePrepareRestart(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/abort-restart") && method.equals("POST")) {
            // Companion to prepare-restart: if the dialog's SIGKILL fails
            // and the daemon survives, this lets the client unstick the
            // shutdown latch so future preview requests work again without
            // needing a manual daemon restart.
            // Nothing to resume on the trip side: prepare-restart only
            // flushed telemetry and left the trip open and sampling.
            shutdownInProgress = false;
            CameraDaemon.log("abort-restart: shutdown latch cleared");
            HttpResponse.sendJsonSuccess(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/screen-deterrent/image") && method.equals("POST")) {
            handleScreenDeterrentImageUpload(out, body);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/screen-deterrent/image") && method.equals("GET")) {
            handleScreenDeterrentImageGet(out);
            return true;
        }
        if (cleanPath.equals("/api/surveillance/screen-deterrent/test") && method.equals("POST")) {
            try {
                String error = com.overdrive.app.surveillance.ScreenDeterrent
                        .getInstance().previewNow();
                if (error == null) HttpResponse.sendJsonSuccess(out);
                else HttpResponse.sendJsonError(out, error);
            } catch (Exception e) {
                HttpResponse.sendJsonError(out, "Failed to trigger screen deterrent: " + e.getMessage());
            }
            return true;
        }
        return false;
    }

    /**
     * Only files inside this directory + with this filename prefix can be
     * served or deleted by the deterrent endpoints. Without this lock, an
     * attacker who could touch UCM (world-writable JSON) could redirect
     * screenDeterrentImagePath at /etc/* and have the web server stream or
     * delete arbitrary readable files.
     */
    private static final String SCREEN_DETERRENT_DIR =
            com.overdrive.app.surveillance.ScreenDeterrentAsset.DIRECTORY;
    private static final String SCREEN_DETERRENT_PREFIX =
            com.overdrive.app.surveillance.ScreenDeterrentAsset.PREFIX;
    private static final int SCREEN_DETERRENT_MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    // 10 MB base64-encodes below HttpServer's 16 MB generic request cap.
    private static final int SCREEN_DETERRENT_MAX_VIDEO_BYTES = 10 * 1024 * 1024;
    private static final long SCREEN_DETERRENT_MAX_VIDEO_MS = 10_500L;
    private static final int SCREEN_DETERRENT_MAX_VIDEO_DIM = 1920;
    private static final int SCREEN_DETERRENT_MAX_VIDEO_MINOR_DIM = 1080;
    // 1080-line H.264/HEVC commonly stores 1088 coded rows plus a crop.
    private static final int SCREEN_DETERRENT_MAX_VIDEO_CODED_MINOR_DIM = 1088;
    private static final int SCREEN_DETERRENT_MAX_GIF_DIM = 4096;
    private static final long SCREEN_DETERRENT_MAX_GIF_PIXELS = 1920L * 1920L;
    private static final Object SCREEN_DETERRENT_ASSET_LOCK = new Object();

    // ponytail: one worker bounds a wedged MediaExtractor JNI call; restart the
    // daemon if that worker hangs rather than leaking one thread per upload.
    private static final java.util.concurrent.ThreadPoolExecutor
            SCREEN_DETERRENT_VIDEO_VALIDATOR =
            new java.util.concurrent.ThreadPoolExecutor(
                    1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(1),
                    r -> {
                        Thread t = new Thread(r, "DeterrentVideoValidate");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** Returns the normalized on-disk extension from magic bytes only. */
    static String deterrentAssetExtension(byte[] data) {
        if (com.overdrive.app.surveillance.ScreenDeterrentVideo.isMp4(data)) {
            return "mp4";
        }
        if (data == null) return null;
        if (data.length >= 8
                && (data[0] & 0xff) == 0x89
                && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 13 && data[5] == 10
                && data[6] == 26 && data[7] == 10) {
            return "png";
        }
        if (data.length >= 3
                && (data[0] & 0xff) == 0xff
                && (data[1] & 0xff) == 0xd8
                && (data[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (data.length >= 6
                && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8'
                && (data[4] == '7' || data[4] == '9')
                && data[5] == 'a') {
            return "gif";
        }
        if (data.length >= 12
                && data[0] == 'R' && data[1] == 'I'
                && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E'
                && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static boolean isAllowedDeterrentPath(String path) {
        return com.overdrive.app.surveillance.ScreenDeterrentAsset
                .isAllowedPath(path);
    }

    private static void handleScreenDeterrentImageGet(OutputStream out) throws Exception {
        java.io.FileInputStream input;
        long length;
        String contentType;
        synchronized (SCREEN_DETERRENT_ASSET_LOCK) {
            String path = com.overdrive.app.config.UnifiedConfigManager.getSurveillance()
                    .optString("screenDeterrentImagePath", "");
            if (path.isEmpty()) {
                HttpResponse.sendError(out, 404, "No deterrent asset set");
                return;
            }
            if (!isAllowedDeterrentPath(path)) {
                HttpResponse.sendError(out, 403, "Forbidden");
                return;
            }
            java.io.File file = new java.io.File(path).getCanonicalFile();
            if (!file.isFile() || file.length() == 0) {
                HttpResponse.sendError(out, 404, "Deterrent asset missing");
                return;
            }

            String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".gif")) contentType = "image/gif";
            else if (lower.endsWith(".mp4")) contentType = "video/mp4";
            else if (lower.endsWith(".webp")) contentType = "image/webp";
            else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) contentType = "image/jpeg";
            else contentType = "image/png";
            try {
                input = new java.io.FileInputStream(file);
                length = file.length();
            } catch (java.io.IOException missing) {
                HttpResponse.sendError(out, 404, "Deterrent asset missing");
                return;
            }
        }

        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        try (java.io.FileInputStream stream = input) {
            out.write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        out.flush();
    }

    /**
     * Accept a base64-encoded image / GIF / MP4 for the screen deterrent. JSON body:
     *   { "filename": "warning.gif", "dataBase64": "<base64>" }
     *
     * Persists below /data/local/tmp/.overdrive/screen_deterrent_asset.*
     * world-readable so the daemon UID 2000 can decode it. Updates
     * surveillance.screenDeterrentImagePath in unified config on success.
     */
    private static void handleScreenDeterrentImageUpload(OutputStream out, String body) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendJsonError(out, "Empty request body");
            return;
        }

        JSONObject req;
        try {
            req = new JSONObject(body);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid JSON");
            return;
        }

        String dataB64 = req.optString("dataBase64", "");
        if (dataB64.isEmpty()) {
            HttpResponse.sendJsonError(out, "Missing dataBase64");
            return;
        }

        // Strip optional "data:image/png;base64," prefix the browser may include.
        int comma = dataB64.indexOf(',');
        if (dataB64.startsWith("data:") && comma > 0) {
            dataB64 = dataB64.substring(comma + 1);
        }

        byte[] data;
        try {
            data = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid base64");
            return;
        }

        // Reject 0-byte uploads — they'd pass the path-restriction checks
        // later but produce a "current asset" preview that's broken.
        if (data.length == 0) {
            HttpResponse.sendJsonError(out, "Empty file");
            return;
        }
        String ext = deterrentAssetExtension(data);
        if (ext == null) {
            HttpResponse.sendJsonError(out,
                    "Unsupported or unreadable file (use PNG, JPG, WebP, GIF or MP4)");
            return;
        }
        boolean isVideo = "mp4".equals(ext);
        int maxBytes = isVideo
                ? SCREEN_DETERRENT_MAX_VIDEO_BYTES
                : SCREEN_DETERRENT_MAX_IMAGE_BYTES;
        if (data.length > maxBytes) {
            HttpResponse.sendJsonError(out, "File too large (max "
                    + (maxBytes / (1024 * 1024)) + "MB)");
            return;
        }

        persistScreenDeterrentAsset(out, data, ext, isVideo);
    }

    private static void persistScreenDeterrentAsset(
            OutputStream out, byte[] data, String ext, boolean isVideo) throws Exception {
        synchronized (SCREEN_DETERRENT_ASSET_LOCK) {
            java.io.File dir = new java.io.File(SCREEN_DETERRENT_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                HttpResponse.sendJsonError(out, "Could not create deterrent asset directory");
                return;
            }
            try {
                dir.setReadable(true, false);
                dir.setExecutable(true, false);
            } catch (Exception ignored) {}

            java.io.File tmpFile = null;
            java.io.File outFile = null;
            boolean configCommitted = false;
            try {
                // Unique staging and final names prevent concurrent requests,
                // crashed uploads, and same-extension replacement from sharing
                // an inode. The configured old asset remains untouched until
                // the new file is durable and UCM accepts the new path.
                tmpFile = java.io.File.createTempFile(
                        SCREEN_DETERRENT_PREFIX + "upload.", ".tmp", dir);
                try (java.io.FileOutputStream fos =
                             new java.io.FileOutputStream(tmpFile, false)) {
                    fos.write(data);
                    fos.getFD().sync();
                }
                try { tmpFile.setReadable(true, false); } catch (Exception ignored) {}

                String reject = isVideo
                        ? validateDeterrentVideo(tmpFile)
                        : validateDeterrentImage(tmpFile, "gif".equals(ext));
                if (reject != null) {
                    HttpResponse.sendJsonError(out, reject);
                    return;
                }

                outFile = new java.io.File(
                        dir,
                        SCREEN_DETERRENT_PREFIX
                                + java.util.UUID.randomUUID() + "." + ext);
                try {
                    java.nio.file.Files.move(
                            tmpFile.toPath(), outFile.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    java.nio.file.Files.move(
                            tmpFile.toPath(), outFile.toPath());
                }
                tmpFile = null;
                try { outFile.setReadable(true, false); } catch (Exception ignored) {}

                boolean persisted =
                        com.overdrive.app.config.UnifiedConfigManager.updateValues(
                                "surveillance",
                                java.util.Collections.singletonMap(
                                        "screenDeterrentImagePath",
                                        outFile.getAbsolutePath()));
                if (!persisted) {
                    try { outFile.delete(); } catch (Exception ignored) {}
                    HttpResponse.sendJsonError(out, "Could not save deterrent asset");
                    return;
                }
                configCommitted = true;

                deleteDeterrentAssetsExcept(dir, outFile);
                CameraDaemon.log("Screen deterrent asset uploaded: "
                        + outFile.getAbsolutePath() + " (" + data.length + " bytes)");

                JSONObject resp = new JSONObject();
                resp.put("success", true);
                resp.put("path", outFile.getAbsolutePath());
                resp.put("size", data.length);
                resp.put("assetType", isVideo ? "video" : "image");
                HttpResponse.sendJson(out, resp.toString());
            } catch (Throwable t) {
                if (!configCommitted && outFile != null) {
                    try { outFile.delete(); } catch (Exception ignored) {}
                }
                if (configCommitted) {
                    CameraDaemon.log("Screen deterrent asset committed but response failed: "
                            + t.getMessage());
                } else {
                    HttpResponse.sendJsonError(out, "Write failed: " + t.getMessage());
                }
            } finally {
                if (tmpFile != null) {
                    try { tmpFile.delete(); } catch (Exception ignored) {}
                }
            }
        }
    }

    private static void deleteDeterrentAssetsExcept(
            java.io.File dir, java.io.File keep) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        String keepPath = keep == null ? "" : keep.getAbsolutePath();
        for (java.io.File file : files) {
            String name = file.getName();
            if (!name.startsWith(SCREEN_DETERRENT_PREFIX)
                    || file.getAbsolutePath().equals(keepPath)) {
                continue;
            }
            boolean staging = name.endsWith(".tmp");
            if (!staging && !isAllowedDeterrentPath(file.getAbsolutePath())) continue;
            try { file.delete(); } catch (Exception ignored) {}
        }
    }

    private static String validateDeterrentVideo(java.io.File file) {
        if (SCREEN_DETERRENT_VIDEO_VALIDATOR.getActiveCount() > 0
                || !SCREEN_DETERRENT_VIDEO_VALIDATOR.getQueue().isEmpty()) {
            return "Video validation is busy; try again";
        }

        java.util.concurrent.FutureTask<String> task =
                new java.util.concurrent.FutureTask<>(
                        () -> validateDeterrentVideoNow(file));
        try {
            SCREEN_DETERRENT_VIDEO_VALIDATOR.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return "Video validation is busy; try again";
        }

        try {
            return task.get(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            task.cancel(true);
            SCREEN_DETERRENT_VIDEO_VALIDATOR.remove(task);
            CameraDaemon.log("Deterrent video validation timed out — rejecting "
                    + file.getName());
            return "Could not read that MP4 (validation timed out)";
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            SCREEN_DETERRENT_VIDEO_VALIDATOR.remove(task);
            Thread.currentThread().interrupt();
            return "Video validation interrupted";
        } catch (java.util.concurrent.ExecutionException failed) {
            return "Could not validate that MP4";
        }
    }

    private static String validateDeterrentImage(
            java.io.File file, boolean animatedGif) {
        try {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(
                    file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return "Could not decode that image";
            }
            if (animatedGif
                    && (bounds.outWidth > SCREEN_DETERRENT_MAX_GIF_DIM
                        || bounds.outHeight > SCREEN_DETERRENT_MAX_GIF_DIM
                        || (long) bounds.outWidth * bounds.outHeight
                            > SCREEN_DETERRENT_MAX_GIF_PIXELS)) {
                return "GIF dimensions too large for this head unit";
            }
            return null;
        } catch (Throwable invalid) {
            return "Could not decode that image";
        }
    }

    private static String validateDeterrentVideoNow(java.io.File file) {
        if (!com.overdrive.app.surveillance.ScreenDeterrentVideo
                .isMp4File(file.getAbsolutePath())) {
            return "That file is not a readable MP4";
        }
        com.overdrive.app.surveillance.ScreenDeterrentVideo.Probe probe =
                com.overdrive.app.surveillance.ScreenDeterrentVideo.probe(
                        file.getAbsolutePath());
        if (probe == null) return "That MP4 has no readable video track";
        if (probe.durationUs <= 0) return "Could not read the video duration";

        long durationMs = probe.durationUs / 1000L;
        if (durationMs > SCREEN_DETERRENT_MAX_VIDEO_MS) {
            return "Video too long (" + (durationMs / 1000L)
                    + "s, max 10s) — it loops, so keep it short";
        }
        if (probe.width <= 0 || probe.height <= 0
                || probe.visibleWidth <= 0 || probe.visibleHeight <= 0) {
            return "Could not read the video resolution";
        }
        int codedMajor = Math.max(probe.width, probe.height);
        int codedMinor = Math.min(probe.width, probe.height);
        int visibleMajor = Math.max(probe.visibleWidth, probe.visibleHeight);
        int visibleMinor = Math.min(probe.visibleWidth, probe.visibleHeight);
        if (codedMajor > SCREEN_DETERRENT_MAX_VIDEO_DIM
                || codedMinor > SCREEN_DETERRENT_MAX_VIDEO_CODED_MINOR_DIM
                || visibleMajor > SCREEN_DETERRENT_MAX_VIDEO_DIM
                || visibleMinor > SCREEN_DETERRENT_MAX_VIDEO_MINOR_DIM) {
            return "Video too large (" + probe.width + "x" + probe.height
                    + ", max 1920x1080)";
        }
        if (com.overdrive.app.surveillance.ScreenDeterrentVideo
                .decoderForFormat(probe.format) == null) {
            return "This head unit cannot decode " + probe.mime
                    + " — re-encode as H.264 / AVC";
        }
        return null;
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
            // Fallback must match the real per-quadrant grid (10×7=70), not the
            // old dead 640×480 figure — the ROI editor posts back a length-70
            // roiBlocks_Q* array and applyEffectiveRoi drops any other length.
            config.put("totalBlocks", sentry != null ? sentry.getTotalBlocks()
                    : com.overdrive.app.surveillance.MotionPipelineV2.TOTAL_BLOCKS);
            config.put("flashImmunity", sentryConfig.getFlashImmunity());
            config.put("aiEnabled", true);
            config.put("aiConfidence", sentryConfig.getAiConfidence());
            config.put("minObjectSize", sentryConfig.getMinObjectSize());
            config.put("detectPerson", sentryConfig.isDetectPerson());
            config.put("detectCar", sentryConfig.isDetectCar());
            config.put("detectBike", sentryConfig.isDetectBike());
            config.put("detectAnimal", sentryConfig.isDetectAnimal());

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
            config.put("totalBlocks",
                    com.overdrive.app.surveillance.MotionPipelineV2.TOTAL_BLOCKS);
            config.put("flashImmunity", 2);
            config.put("aiEnabled", true);
            config.put("aiConfidence", 0.4f);
            config.put("minObjectSize", 0.12f);
            config.put("detectPerson", true);
            config.put("detectCar", true);
            config.put("detectBike", true);
            config.put("detectAnimal", false);
            config.put("preRecordSeconds", 5);
            config.put("postRecordSeconds", 10);
        }
        
        // Load recording settings from unified config. The new tier
        // (recordingQuality: ECONOMY/STANDARD/HIGH/PREMIUM/MAX) replaces
        // the legacy recordingBitrate string. Surveillance UI consumes
        // recordingQuality; recordingBitrate is no longer surfaced.
        try {
            JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
            config.put("recordingQuality", recording.optString("recordingQuality",
                recording.optString("quality", "STANDARD")));
            config.put("recordingCodec", recording.optString("codec", "H264"));
        } catch (Exception e) {
            config.put("recordingQuality", "STANDARD");
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
        
        // SOTA: Deterrent action setting. forceReload because the daemon
        // process (byd_cam_daemon) writes screenDeterrentImagePath via the
        // upload endpoint; without forceReload the in-memory UCM cache here
        // can be stale until the next file mtime tick is observed.
        // Single forceReload — read every section we need off the SAME fresh
        // snapshot. (Calling forceReload again below for "power" would re-parse
        // the file and leave survConfig pointing at the earlier snapshot.)
        JSONObject ucmRoot = com.overdrive.app.config.UnifiedConfigManager.forceReload();
        JSONObject survConfig = ucmRoot.optJSONObject("surveillance");
        if (survConfig == null) survConfig = new JSONObject();
        config.put("deterrentAction", survConfig.optString("deterrentAction", "silent"));
        config.put("deterrentCooldownSeconds", survConfig.optInt("deterrentCooldownSeconds", 60));
        config.put("screenDeterrentEnabled", survConfig.optBoolean("screenDeterrentEnabled", false));
        config.put("screenDeterrentDurationSeconds", survConfig.optInt("screenDeterrentDurationSeconds", 8));
        config.put("screenDeterrentMessage", survConfig.optString("screenDeterrentMessage", ""));
        // ACC-OFF mode: "smart" (motion + YOLO) | "continuous" (plain rolling).
        // Branched at SurveillanceEngineGpu.enable(). Default smart.
        config.put("accOffMode", survConfig.optString("accOffMode", "smart"));
        // Arm mode: "lock" (arm on door-lock, disarm on unlock, 60s fallback when
        // lock state unreadable) | "power" (arm immediately on ACC-off, disarm on
        // ACC-on). Branched in CameraDaemon's ACC-off dispatch. Default lock.
        config.put("armMode", survConfig.optString("armMode", "lock"));
        // Operating mode: "onAndOff" (full behaviour incl. post-OFF keep-awake +
        // surveillance) | "onOnly" (let the head unit sleep after the car is off;
        // only-while-ON features run). Default onAndOff. Read by every post-OFF gate.
        config.put("operatingMode", survConfig.optString("operatingMode", "onAndOff"));
        // True once the user has EXPLICITLY set the operating mode (onboarding or
        // Settings). Lets the onboarding daemon-ready flush distinguish an untouched
        // default from a deliberate choice, so a stale pending value never clobbers a
        // later Settings change. Default false = never set by a user yet.
        config.put("operatingModeSetByUser", survConfig.optBoolean("operatingModeSetByUser", false));
        // Keep ONLY the USB/data rail powered after ACC OFF (cameras unaffected).
        // Default true; read by AccSentryDaemon on the next ACC-OFF cycle.
        config.put("keepUsbPowerOnAccOff", survConfig.optBoolean("keepUsbPowerOnAccOff", true));
        // Parked cellular keep-alive. Default FALSE (opt-in) — see the daemon's
        // keep-alive loop; only needed where the data module sleeps after ACC OFF.
        config.put("mobileDataKeepAlive", survConfig.optBoolean("mobileDataKeepAlive", false));
        // HV-battery SoC surveillance cutoff (%). Lives in the "power" section
        // (the key SocCutoffMonitor reads), NOT "surveillance" — surface it on
        // the surveillance config so the General-tab slider can hydrate. 0=Off.
        // Default 10 matches SocCutoffMonitor.DEFAULT_CUTOFF_PERCENT.
        org.json.JSONObject powerConfig = ucmRoot.optJSONObject("power");
        config.put("lowSocCutoffPercent",
                powerConfig != null ? powerConfig.optInt("lowSocCutoffPercent", 10) : 10);
        // Verify the file actually exists before claiming hasImage=true.
        // Without this check, a stale UCM pointer (file deleted out-of-band)
        // makes the UI show a broken preview spinner forever.
        String imgPath = survConfig.optString("screenDeterrentImagePath", "");
        boolean hasImage = false;
        if (isAllowedDeterrentPath(imgPath)) {
            try {
                java.io.File f = new java.io.File(imgPath).getCanonicalFile();
                hasImage = f.isFile() && f.length() > 0;
                if (hasImage) imgPath = f.getAbsolutePath();
            } catch (Exception ignored) {}
        }
        if (!hasImage) imgPath = "";
        config.put("screenDeterrentImagePath", imgPath);
        config.put("screenDeterrentHasImage", hasImage);
        config.put("screenDeterrentAssetType",
                hasImage && imgPath.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4")
                        ? "video" : (hasImage ? "image" : ""));
        
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
            config.put("approachTrigger", sentryConfig.getApproachTriggerSeconds());
            boolean[] cameras = sentryConfig.getCameraEnabled();
            config.put("cameraFront", cameras[0]);
            config.put("cameraRight", cameras[1]);
            config.put("cameraRear", cameras[2]);
            config.put("cameraLeft", cameras[3]);
            config.put("motionHeatmap", sentryConfig.isMotionHeatmapEnabled());
            config.put("filterDebugLog", sentryConfig.isFilterDebugLogEnabled());
            config.put("discardEmptyBrightMotionEvents", sentryConfig.isDiscardEmptyBrightMotionEvents());
            config.put("discardEmptyMotionAtNight", sentryConfig.isDiscardEmptyMotionAtNight());
            config.put("motionSalienceEnabled", sentryConfig.isMotionSalienceEnabled());
            config.put("postParkVigilanceEnabled", sentryConfig.isPostParkVigilanceEnabled());
            config.put("telegramSendStartPing", sentryConfig.isTelegramSendStartPing());
            // Per-tier filter now lives in the telegram unified-config section
            // (see UnifiedTelegramConfig.K_TIER_*). Wire format on
            // /api/surveillance/config keeps the legacy key names so the web
            // UI doesn't need to know the storage moved.
            config.put("telegramNotices",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierNotices());
            config.put("telegramAlerts",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierAlerts());
            config.put("telegramCritical",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierCritical());
            config.put("shadowFilter", sentryConfig.getShadowFilterMode());

            // Per-quadrant overrides (sensitivity / detection zone). Each
            // entry is omitted when no override is set (= inherit global).
            org.json.JSONObject overrides = new org.json.JSONObject();
            String[] qKeysOv = {"Q0", "Q1", "Q2", "Q3"};
            for (int q = 0; q < 4; q++) {
                Integer sens = sentryConfig.getQuadrantSensitivityOverride(q);
                String zone = sentryConfig.getQuadrantDetectionZoneOverride(q);
                if (sens != null || zone != null) {
                    org.json.JSONObject perQ = new org.json.JSONObject();
                    if (sens != null) perQ.put("sensitivityLevel", sens.intValue());
                    if (zone != null) perQ.put("detectionZone", zone);
                    overrides.put(qKeysOv[q], perQ);
                }
            }
            config.put("quadrantOverrides", overrides);

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
                boolean passiveApaMode = false;
                if (camCfg != null) {
                    config.put("cameraId", camCfg.optInt("probedCameraId", -1));
                    config.put("cameraManualOverride", camCfg.optBoolean("manualOverride", false));
                    // Persisted ingestion mode. Default = "default" (legacy
                    // ImageReader + 4-strip → 2x2). UI uses this to pre-select
                    // the radio group; absence falls back to default.
                    config.put("cameraMode",
                        camCfg.optString("cameraMode", "default"));
                    // Red-calibration-overlay GL mask fallback. The dialog
                    // reads this to pre-check the switch.
                    config.put("dilink4RedMask",
                        camCfg.optBoolean("dilink4RedMask", false));
                    passiveApaMode = camCfg.optBoolean("dilink4PassiveApaMode", false);
                    config.put("dilink4PassiveApaMode", passiveApaMode);
                }
                // DiLink 4 mosaic-viewpoint handshake result. This is the write
                // that flips the byd_apa HAL out of single-camera dashcam mode;
                // when it does not land, the HAL streams ONE camera and every 2x2
                // quadrant assumption downstream is wrong — which looks to a user
                // like a garbled or wrong-looking tile. Surfacing it here is what
                // makes that distinguishable in the field instead of guessed at.
                //
                // Emitted ONLY on dilink4 so a legacy car's response payload is
                // byte-identical to before (the values would be meaningless there
                // anyway — the viewpoint write is never attempted).
                if (camCfg != null
                        && "dilink4".equalsIgnoreCase(camCfg.optString("cameraMode", "default"))
                        && !passiveApaMode) {
                    config.put("dilink4MosaicViewpointConfirmed",
                        com.overdrive.app.camera.BydApaViewpointHelper.isMosaicViewpointConfirmed());
                    config.put("dilink4ViewpointRc",
                        com.overdrive.app.camera.BydApaViewpointHelper.getLastAcquireRc());
                }
            } catch (Exception ignored) {}
        } else {
            config.put("environmentPreset", "outdoor");
            config.put("sensitivityLevel", 3);
            config.put("detectionZone", "normal");
            config.put("loiteringTime", 3);
            config.put("approachTrigger", 2);
            config.put("cameraFront", true);
            config.put("cameraRight", true);
            config.put("cameraLeft", true);
            config.put("cameraRear", true);
            config.put("motionHeatmap", false);
            config.put("filterDebugLog", false);
            config.put("discardEmptyBrightMotionEvents", false);
            config.put("discardEmptyMotionAtNight", false);
            config.put("motionSalienceEnabled", false);
            config.put("postParkVigilanceEnabled", true);
            config.put("telegramSendStartPing", false);
            // Tier toggles live on the telegram unified-config section, so
            // they're available even when SurveillanceConfig isn't loaded.
            config.put("telegramNotices",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierNotices());
            config.put("telegramAlerts",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierAlerts());
            config.put("telegramCritical",
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTierCritical());
            config.put("shadowFilter", 2);
        }

        // Merge resolved camera profile summary so the diagnostics camera-
        // mapping dialog can populate role list, current mappings, and
        // preview candidates in a single round-trip. Failures are non-fatal —
        // the rest of the config response still ships.
        try {
            com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
                com.overdrive.app.camera.CameraConfigResolver.resolve();
            JSONObject resolvedJson = com.overdrive.app.camera.CameraConfigResolver
                .resolvedSummaryJson(resolvedCamera);
            java.util.Iterator<String> keys = resolvedJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                config.put(key, resolvedJson.get(key));
            }
        } catch (Exception e) {
            CameraDaemon.log("Failed to resolve camera profile summary: " + e.getMessage());
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

            // ---- Camera profile selection (vehicle class) ----
            // Surfaces save failures back to the caller — saveCameraProfile
            // returns false when the unified-config write fails (filesystem
            // permission on app-UID writes is the common case on this device).
            if (configJson.has("cameraProfile")
                    || configJson.optBoolean("clearCameraProfile", false)) {
                String requestedProfile = configJson.optBoolean("clearCameraProfile", false)
                    ? com.overdrive.app.camera.CameraProfiles.PROFILE_AUTO
                    : configJson.optString("cameraProfile",
                        com.overdrive.app.camera.CameraProfiles.PROFILE_AUTO);
                if (!com.overdrive.app.camera.CameraConfigResolver
                        .saveCameraProfile(requestedProfile)) {
                    HttpResponse.sendJsonError(out,
                        "Failed to save camera profile: " + requestedProfile);
                    return;
                }
                CameraDaemon.log("Camera profile saved: " + requestedProfile);
            }

            // ---- Camera role → source mapping (diagnostics camera-mapping dialog) ----
            // Single-role write: { cameraRoleMapping: { role: "panoFront",
            //   source: { kind: "panoramicSlice", slice: "slice4" } } }
            //   - or { kind: "direct", cameraId: 2 }
            //   - or { kind: "panoramicVirtual", view: "front" }
            // Or clear: { cameraRoleMapping: { role: "panoFront", clear: true } }
            if (configJson.has("cameraRoleMapping")) {
                JSONObject mappingJson = configJson.optJSONObject("cameraRoleMapping");
                if (mappingJson == null) {
                    HttpResponse.sendJsonError(out, "Invalid cameraRoleMapping payload");
                    return;
                }
                com.overdrive.app.camera.CameraRole role =
                    com.overdrive.app.camera.CameraRole.fromKey(
                        mappingJson.optString("role", null));
                if (role == null) {
                    HttpResponse.sendJsonError(out,
                        "Unknown role: " + mappingJson.optString("role", "(missing)"));
                    return;
                }
                if (mappingJson.optBoolean("clear", false)) {
                    if (!com.overdrive.app.camera.CameraConfigResolver.clearRoleMapping(role)) {
                        HttpResponse.sendJsonError(out, "Failed to clear role mapping");
                        return;
                    }
                } else {
                    com.overdrive.app.camera.CameraSourceRef source =
                        com.overdrive.app.camera.CameraSourceRef.fromJson(
                            mappingJson.optJSONObject("source"));
                    if (source == null) {
                        HttpResponse.sendJsonError(out,
                            "Invalid or missing source for role " + role.getKey());
                        return;
                    }
                    if (!com.overdrive.app.camera.CameraConfigResolver
                            .saveRoleMapping(role, source)) {
                        HttpResponse.sendJsonError(out,
                            "Failed to save role mapping for " + role.getKey());
                        return;
                    }
                }
            }

            // ---- Bulk reset: revert all role mappings to profile defaults ----
            if (configJson.optBoolean("clearCameraRoleMappings", false)) {
                for (com.overdrive.app.camera.CameraRole role
                        : com.overdrive.app.camera.CameraRole.values()) {
                    com.overdrive.app.camera.CameraConfigResolver.clearRoleMapping(role);
                }
            }

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
            boolean reconcileOperatingMode = false;
            
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
            // ALL-CLASSES-OFF GUARD. A save whose RESULTING state disables every
            // object class silently degrades the whole trigger stack: aiEnabled
            // flips false, the YOLO interpreter is unloaded, and the no-AI rate
            // limit (NO_AI_MIN_GAP_MS) suppresses+resets motion sequences for 30s
            // after every recording — real loiter events die without triggering
            // (observed on-car 2026-07-19: a stale/mis-tapped Detection-tab Apply
            // carried all four flags false alongside a preset change; detection
            // was blind for 14 minutes until the next save). Compute the WOULD-BE
            // state (request value where present, else current persisted value)
            // and reject unless the caller explicitly confirms — the UI shows a
            // confirmation dialog and retries with confirmDisableAllClasses:true.
            boolean anyDetectFlagInRequest = configJson.has("detectPerson")
                    || configJson.has("detectCar")
                    || configJson.has("detectBike")
                    || configJson.has("detectAnimal");
            if (anyDetectFlagInRequest) {
                boolean wouldPerson = configJson.has("detectPerson")
                        ? configJson.optBoolean("detectPerson", true) : sentryConfig.isDetectPerson();
                boolean wouldCar = configJson.has("detectCar")
                        ? configJson.optBoolean("detectCar", true) : sentryConfig.isDetectCar();
                boolean wouldBike = configJson.has("detectBike")
                        ? configJson.optBoolean("detectBike", true) : sentryConfig.isDetectBike();
                boolean wouldAnimal = configJson.has("detectAnimal")
                        ? configJson.optBoolean("detectAnimal", false) : sentryConfig.isDetectAnimal();
                boolean anyCurrentlyOn = sentryConfig.isDetectPerson() || sentryConfig.isDetectCar()
                        || sentryConfig.isDetectBike() || sentryConfig.isDetectAnimal();
                if (!wouldPerson && !wouldCar && !wouldBike && !wouldAnimal
                        && anyCurrentlyOn
                        && !configJson.optBoolean("confirmDisableAllClasses", false)) {
                    CameraDaemon.log("WARN: config save would disable ALL object classes "
                            + "(AI gate + YOLO off) — rejected without confirmDisableAllClasses");
                    // Machine-readable code so the UI can key its confirm dialog
                    // off it instead of matching localized error text.
                    JSONObject rejection = new JSONObject();
                    rejection.put("success", false);
                    rejection.put("code", "all_classes_off");
                    rejection.put("error", Messages.get("errors.surveillance_all_classes_off"));
                    HttpResponse.sendJson(out, rejection.toString());
                    return;
                }
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
            if (configJson.has("detectAnimal")) {
                sentryConfig.setDetectAnimal(configJson.optBoolean("detectAnimal", false));
                configChanged = true;
            }

            // Apply object filters to running engine
            if (sentry != null && configChanged) {
                sentry.setObjectFilters(
                    sentryConfig.getMinObjectSize(),
                    sentryConfig.getAiConfidence(),
                    sentryConfig.isDetectPerson(),
                    sentryConfig.isDetectCar(),
                    sentryConfig.isDetectBike(),
                    sentryConfig.isDetectAnimal()
                );
            }
            
            // Flash immunity setting
            if (configJson.has("flashImmunity")) {
                int val = configJson.optInt("flashImmunity", 2);
                sentryConfig.setFlashImmunity(val);
                if (sentry != null) sentry.setFlashImmunity(val);
                configChanged = true;
            }
            
            // Arm mode: "lock" or "power". Takes effect on the next ACC-off cycle
            // (the door-lock gate / immediate-arm branch reads it fresh then), so
            // no mid-session engine restart is needed here. Invalid values are
            // rejected — getSurveillanceArmMode() falls back to "lock" anyway, but
            // logging the bad value aids debugging.
            if (configJson.has("armMode")) {
                String armMode = configJson.optString("armMode", "lock");
                if ("lock".equals(armMode) || "power".equals(armMode)) {
                    boolean persisted = com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", java.util.Collections.singletonMap("armMode", armMode));
                    if (!persisted) {
                        CameraDaemon.log("Failed to persist armMode=" + armMode);
                        HttpResponse.sendJsonError(out, "Failed to save arm mode");
                        return;
                    }
                    CameraDaemon.log("Arm mode set to: " + armMode);
                    configChanged = true;
                } else {
                    CameraDaemon.log("Rejected armMode: " + armMode);
                }
            }

            // Operating mode: onAndOff (default full behaviour) | onOnly (disable all
            // post-vehicle-OFF work so the head unit can sleep). Settings writes remain
            // next-cycle changes. Automation may set applyCurrentAccState=true so an action
            // triggered on the ACC-off edge safely completes or cancels parked shutdown now.
            // Invalid values are rejected; isVehicleOnOnlyMode() fails open to onAndOff.
            if (configJson.has("operatingMode")) {
                String opMode = configJson.optString("operatingMode", "onAndOff");
                if ("onAndOff".equals(opMode) || "onOnly".equals(opMode)) {
                    // Persist the mode AND a "user explicitly set this" marker in one
                    // atomic write. The marker lets the onboarding daemon-ready flush
                    // (OnboardingHost.flushPendingOperatingMode) tell an untouched default
                    // ("onAndOff", never chosen) apart from a deliberate later Settings
                    // change — without it, a stale pending onboarding choice could re-POST
                    // over a change the user just made here. Every operatingMode write
                    // (onboarding OR Settings) is a genuine user choice, so set it true.
                    java.util.Map<String, Object> opModeVals = new java.util.HashMap<>();
                    opModeVals.put("operatingMode", opMode);
                    opModeVals.put("operatingModeSetByUser", true);
                    boolean persisted = com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", opModeVals);
                    if (!persisted) {
                        CameraDaemon.log("Failed to persist operatingMode=" + opMode);
                        HttpResponse.sendJsonError(out, "Failed to save operating mode");
                        return;
                    }
                    CameraDaemon.log("Operating mode set to: " + opMode);
                    reconcileOperatingMode =
                            configJson.optBoolean("applyCurrentAccState", false);
                    configChanged = true;
                } else {
                    CameraDaemon.log("Rejected operatingMode: " + opMode);
                }
            } else if (configJson.has("operatingModeSetByUser")) {
                // Standalone write of the "user explicitly chose a mode" marker WITHOUT an
                // operatingMode change. Used by onboarding reset/replay to clear the flag
                // (operatingModeSetByUser=false) so a wiped session no longer inherits a
                // prior session's choice marker — otherwise the daemon-ready flush would
                // wrongly drop a legitimate NEW replay pick as if it were stale. Only the
                // false-write is meaningful here (true is set atomically with the mode
                // above); accept the boolean as sent.
                boolean setByUser = configJson.optBoolean("operatingModeSetByUser", false);
                boolean persisted = com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance", java.util.Collections.singletonMap("operatingModeSetByUser", setByUser));
                if (!persisted) {
                    CameraDaemon.log("Failed to persist operatingModeSetByUser=" + setByUser);
                    HttpResponse.sendJsonError(out, "Failed to save operating mode marker");
                    return;
                }
                CameraDaemon.log("operatingModeSetByUser set to: " + setByUser);
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

            // Screen deterrent (independent of cloud deterrent — both can be on).
            // Commit every supplied field in one UCM transaction so the browser
            // never receives success for a partial or failed immediate save.
            java.util.Map<String, Object> screenDeterrentUpdates =
                    new java.util.HashMap<>();
            boolean resetScreenDeterrent = false;
            Boolean screenDeterrentEnabledValue = null;
            if (configJson.has("screenDeterrentEnabled")) {
                boolean enabled = configJson.optBoolean("screenDeterrentEnabled", false);
                screenDeterrentUpdates.put("screenDeterrentEnabled", enabled);
                resetScreenDeterrent = true;
                screenDeterrentEnabledValue = enabled;
            }

            if (configJson.has("screenDeterrentDurationSeconds")) {
                int dur = configJson.optInt("screenDeterrentDurationSeconds", 8);
                if (dur < 3 || dur > 30) {
                    HttpResponse.sendJsonError(out,
                            "Screen deterrent duration must be between 3 and 30 seconds");
                    return;
                }
                screenDeterrentUpdates.put("screenDeterrentDurationSeconds", dur);
            }

            if (configJson.has("screenDeterrentMessage")) {
                String msg = configJson.optString("screenDeterrentMessage", "");
                if (msg.length() > 120) msg = msg.substring(0, 120);
                screenDeterrentUpdates.put("screenDeterrentMessage", msg);
            }

            if (!screenDeterrentUpdates.isEmpty()) {
                boolean persisted =
                        com.overdrive.app.config.UnifiedConfigManager.updateValues(
                                "surveillance", screenDeterrentUpdates);
                if (!persisted) {
                    HttpResponse.sendJsonError(out,
                            "Could not save screen deterrent settings");
                    return;
                }
                if (screenDeterrentEnabledValue != null) {
                    CameraDaemon.log("Screen deterrent enabled: "
                            + screenDeterrentEnabledValue);
                }
                if (resetScreenDeterrent) {
                    try {
                        com.overdrive.app.surveillance.ScreenDeterrent
                                .getInstance().reset();
                    } catch (Exception ignored) {}
                }
            }

            // ACC-OFF mode: only "smart" or "continuous" are valid; anything
            // else is rejected silently (the engine falls back to "smart"
            // anyway, but logging the bad value here makes debugging easier).
            if (configJson.has("accOffMode")) {
                String mode = configJson.optString("accOffMode", "smart");
                if ("smart".equals(mode) || "continuous".equals(mode)) {
                    String prevMode = com.overdrive.app.config.UnifiedConfigManager
                            .getSurveillance().optString("accOffMode", "smart");
                    boolean persisted = com.overdrive.app.config.UnifiedConfigManager.updateValues(
                            "surveillance", java.util.Collections.singletonMap("accOffMode", mode));
                    if (!persisted) {
                        // UCM write failed (typically EACCES on app-UID writes
                        // to /data/local/tmp). Without this guard the engine
                        // would still flip — but since enable() forceReloads
                        // and reads the OLD value from disk, the engine ends
                        // up back in the previous mode while the JS layer
                        // shows a "saved" toast. Surface the failure so the
                        // UI revert path runs.
                        CameraDaemon.log("Failed to persist accOffMode=" + mode);
                        HttpResponse.sendJsonError(out, "Failed to save ACC-OFF mode");
                        return;
                    }
                    CameraDaemon.log("ACC-OFF mode set to: " + mode);

                    // Mid-session honor: if surveillance is currently armed
                    // (ACC OFF + door-lock arm fired) AND the mode actually
                    // flipped, restart the engine. disableSurveillance()
                    // closes the in-flight recording cleanly and clears the
                    // engine's latch; enableSurveillance() re-runs sentry.enable(),
                    // which forceReloads UnifiedConfig and picks up the new mode.
                    // Skip when ACC is ON — the change just sits in config and
                    // takes effect on the next ACC OFF cycle.
                    boolean modeChanged = !mode.equals(prevMode);
                    boolean accOff = !com.overdrive.app.monitor.AccMonitor.isAccOn();
                    // Genuinely armed = user wants surveillance AND the engine
                    // is actually live. Safe-zone-suppressed sessions have
                    // surveillanceEnabled=true (intent flag) but the engine
                    // never started, so a disable+enable roundtrip is wasted
                    // work that bounces the safeZoneSuppressed flag. The next
                    // time the car leaves the safe zone, the cloud-MQTT zone-
                    // exit handler will arm surveillance fresh and sentry.enable()
                    // will read the latest mode from UCM at that point.
                    boolean armed = CameraDaemon.isSurveillanceEnabled()
                            && !CameraDaemon.isSafeZoneSuppressed();
                    if (modeChanged && accOff && armed) {
                        CameraDaemon.log("Mid-session mode switch (" + prevMode + "→" + mode
                                + ") — restarting surveillance engine");
                        // Restart on a worker so the HTTP response thread isn't
                        // tied up by the brief stop+restart. The engine's
                        // disable() drains in-flight inferences (~50 ms) and
                        // closeEventRecording flushes the muxer; enable() then
                        // re-allocates the pipeline state and triggers the
                        // first segment of the new mode.
                        //
                        // Edge cases the post-sleep recheck guards against:
                        //  - ACC turns ON during the 300 ms gap → enableSurveillance()
                        //    has its own ACC-ON guard, so the recheck is belt-and-
                        //    braces but cheap.
                        //  - Owner unlocks the car during the gap → applyLockEvent
                        //    fires disableSurveillance() and clears surveillanceEnabled.
                        //    Without this recheck we'd silently re-arm a session the
                        //    user just disarmed by walking up to the car.
                        //  - Concurrent mode flips → the LATER worker's disable wins
                        //    because both sleep then re-check; whichever observes
                        //    surveillanceEnabled=false (from the other's disable)
                        //    skips its enable. The remaining flip stays armed in
                        //    the latest mode persisted to UCM.
                        new Thread(() -> {
                            try {
                                CameraDaemon.disableSurveillance();
                                Thread.sleep(300);
                                if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                                    CameraDaemon.log("Mode-switch: ACC turned ON during restart — skipping re-arm");
                                    return;
                                }
                                // Re-arm only if the door-lock gate is still
                                // armed (owner hasn't returned). UnifiedConfig's
                                // isSurveillanceEnabled is the user's general
                                // preference and stays true even after an
                                // owner-unlock disarm — it would falsely re-arm
                                // a session the owner just walked into. The
                                // door-lock arm flag is the runtime truth and
                                // is independent of disableSurveillance() so it
                                // survives our stop call.
                                if (!CameraDaemon.isDoorLockArmed()) {
                                    CameraDaemon.log("Mode-switch: lock gate disarmed during restart — skipping re-arm");
                                    return;
                                }
                                // Schedule check: if the user has a time-window
                                // schedule and we fall outside it during the gap,
                                // the schedule checker would have disabled
                                // surveillance — we must respect that. Without
                                // this re-check the schedule-checker's tick at
                                // the window edge can race our re-arm and the
                                // engine ends up running outside the user's
                                // configured surveillance window.
                                try {
                                    com.overdrive.app.surveillance.SurveillanceSchedule sch =
                                        com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
                                    if (sch != null && sch.isEnabled() && !sch.isActiveNow()) {
                                        CameraDaemon.log("Mode-switch: outside schedule window — skipping re-arm");
                                        return;
                                    }
                                } catch (Throwable ignored) {}
                                // If a peer (schedule checker, lock-event source)
                                // re-armed the engine during our gap, the engine
                                // is already running in the new mode — re-calling
                                // enableSurveillance() would re-init pipelineV2,
                                // reset baselines, and clobber the in-flight
                                // recording. SurveillanceEngineGpu.enable() has
                                // no idempotency guard. Skip if already armed.
                                if (CameraDaemon.isSurveillanceEnabled()) {
                                    CameraDaemon.log("Mode-switch: peer re-armed during gap — skipping redundant enable");
                                    return;
                                }
                                CameraDaemon.enableSurveillance();
                            } catch (Throwable t) {
                                CameraDaemon.log("Mode-switch restart error: " + t.getMessage());
                            }
                        }, "AccOffModeSwitch").start();
                    }
                } else {
                    CameraDaemon.log("Rejected accOffMode: " + mode);
                }
            }

            // Keep ONLY the USB/data rail powered after ACC OFF (cameras unaffected).
            // Pure persist — no mid-session restart: AccSentryDaemon reads this fresh
            // on the next ACC-OFF setup, so the change takes effect on the next cycle
            // exactly as the user expects (the current parked session, if any, already
            // configured its rail). Default true; only a real boolean is accepted.
            if (configJson.has("keepUsbPowerOnAccOff")) {
                boolean keepUsb = configJson.optBoolean("keepUsbPowerOnAccOff", true);
                boolean persisted = com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance", java.util.Collections.singletonMap("keepUsbPowerOnAccOff", keepUsb));
                if (!persisted) {
                    CameraDaemon.log("Failed to persist keepUsbPowerOnAccOff=" + keepUsb);
                    HttpResponse.sendJsonError(out, "Failed to save USB-power setting");
                    return;
                }
                CameraDaemon.log("Keep USB powered while parked set to: " + keepUsb
                        + " (takes effect next ACC-OFF cycle)");
            }

            // Parked cellular keep-alive. Same pure-persist contract as the USB toggle
            // above: the daemon snapshots this when the next parked session starts, so
            // an in-flight session keeps whatever it armed with. Default FALSE.
            if (configJson.has("mobileDataKeepAlive")) {
                boolean dataKeepAlive = configJson.optBoolean("mobileDataKeepAlive", false);
                boolean persisted = com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "surveillance",
                        java.util.Collections.singletonMap("mobileDataKeepAlive", dataKeepAlive));
                if (!persisted) {
                    CameraDaemon.log("Failed to persist mobileDataKeepAlive=" + dataKeepAlive);
                    HttpResponse.sendJsonError(out, "Failed to save mobile-data setting");
                    return;
                }
                CameraDaemon.log("Mobile-data keep-alive while parked set to: " + dataKeepAlive
                        + " (takes effect next ACC-OFF cycle)");
            }

            // HV-battery SoC surveillance cutoff (%). Routed to the "power"
            // section — power.lowSocCutoffPercent is the EXACT key
            // SocCutoffMonitor.cutoffPercent() reads, so the slider must land
            // there (not in "surveillance"). Range 0..30; 0 = Off (the monitor
            // early-returns on pct<=0 before the cutoff compare, so it never
            // arms). Out-of-range is clamped, not rejected.
            if (configJson.has("lowSocCutoffPercent")) {
                int pct = configJson.optInt("lowSocCutoffPercent", 10);
                if (pct < 0) pct = 0;
                if (pct > 30) pct = 30;
                boolean persisted = com.overdrive.app.config.UnifiedConfigManager.updateValues(
                        "power", java.util.Collections.singletonMap("lowSocCutoffPercent", pct));
                if (!persisted) {
                    CameraDaemon.log("Failed to persist lowSocCutoffPercent=" + pct);
                    HttpResponse.sendJsonError(out, "Failed to save low-battery cutoff");
                    return;
                }
                CameraDaemon.log("Low-battery surveillance cutoff set to: "
                        + (pct == 0 ? "Off" : pct + "%")
                        + " (SocCutoffMonitor reads live on next SoC tick)");
            }

            if (configJson.has("clearScreenDeterrentImage") && configJson.optBoolean("clearScreenDeterrentImage", false)) {
                synchronized (SCREEN_DETERRENT_ASSET_LOCK) {
                    // Clear the configured pointer first. If persistence fails,
                    // leave every file untouched so the current deterrent keeps
                    // working instead of pointing UCM at a deleted asset.
                    boolean persisted =
                            com.overdrive.app.config.UnifiedConfigManager.updateValues(
                                    "surveillance",
                                    java.util.Collections.singletonMap(
                                            "screenDeterrentImagePath", ""));
                    if (!persisted) {
                        HttpResponse.sendJsonError(out,
                                "Could not clear deterrent asset");
                        return;
                    }
                    java.io.File dir = new java.io.File(SCREEN_DETERRENT_DIR);
                    if (dir.isDirectory()) {
                        deleteDeterrentAssetsExcept(dir, null);
                    }
                }
                CameraDaemon.log("Screen deterrent image cleared");
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
                    boolean dAnimal = sentryConfig.isDetectAnimal();
                    sentry.setObjectFilters(minObjSize, confidence, dPerson, dCar, dBike, dAnimal);
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
            if (configJson.has("approachTrigger")) {
                int seconds = configJson.optInt("approachTrigger", 2);
                sentryConfig.setApproachTriggerSeconds(seconds);
                if (sentry != null) sentry.setV2ApproachTrigger(seconds);
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
            if (configJson.has("quadrantOverrides")) {
                org.json.JSONObject overrides = configJson.optJSONObject("quadrantOverrides");
                String[] qKeysOv = {"Q0", "Q1", "Q2", "Q3"};
                for (int q = 0; q < 4; q++) {
                    org.json.JSONObject perQ = overrides != null
                            ? overrides.optJSONObject(qKeysOv[q]) : null;
                    if (perQ == null) {
                        sentryConfig.setQuadrantSensitivityOverride(q, null);
                        sentryConfig.setQuadrantDetectionZoneOverride(q, null);
                    } else {
                        sentryConfig.setQuadrantSensitivityOverride(q,
                                perQ.has("sensitivityLevel") ? perQ.optInt("sensitivityLevel", 3) : null);
                        sentryConfig.setQuadrantDetectionZoneOverride(q,
                                perQ.has("detectionZone") ? perQ.optString("detectionZone", null) : null);
                    }
                }
                configChanged = true;
            }
            if (configJson.has("motionHeatmap")) {
                sentryConfig.setMotionHeatmapEnabled(configJson.optBoolean("motionHeatmap", false));
                configChanged = true;
            }
            if (configJson.has("discardEmptyBrightMotionEvents")) {
                sentryConfig.setDiscardEmptyBrightMotionEvents(
                        configJson.optBoolean("discardEmptyBrightMotionEvents", false));
                configChanged = true;
            }
            if (configJson.has("motionSalienceEnabled")) {
                sentryConfig.setMotionSalienceEnabled(
                        configJson.optBoolean("motionSalienceEnabled", false));
                configChanged = true;
            }
            if (configJson.has("postParkVigilanceEnabled")) {
                sentryConfig.setPostParkVigilanceEnabled(
                        configJson.optBoolean("postParkVigilanceEnabled", true));
                configChanged = true;
            }
            if (configJson.has("discardEmptyMotionAtNight")) {
                sentryConfig.setDiscardEmptyMotionAtNight(
                        configJson.optBoolean("discardEmptyMotionAtNight", false));
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
            // Per-tier Telegram filter — persisted in the telegram section
            // of unified config so NotificationGate.shouldTelegram() picks
            // the new value up immediately via forceReload(), instead of
            // waiting for the next camera-daemon restart.
            if (configJson.has("telegramNotices")) {
                com.overdrive.app.telegram.config.UnifiedTelegramConfig.setBoolean(
                        com.overdrive.app.telegram.config.UnifiedTelegramConfig.K_TIER_NOTICES,
                        configJson.optBoolean("telegramNotices", false));
            }
            if (configJson.has("telegramAlerts")) {
                com.overdrive.app.telegram.config.UnifiedTelegramConfig.setBoolean(
                        com.overdrive.app.telegram.config.UnifiedTelegramConfig.K_TIER_ALERTS,
                        configJson.optBoolean("telegramAlerts", true));
            }
            if (configJson.has("telegramCritical")) {
                com.overdrive.app.telegram.config.UnifiedTelegramConfig.setBoolean(
                        com.overdrive.app.telegram.config.UnifiedTelegramConfig.K_TIER_CRITICAL,
                        configJson.optBoolean("telegramCritical", true));
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
            
            // Per-quadrant ROI enabled/disabled toggle (separate from polygon/block data).
            // Handles BOTH storage modes:
            //   - polygon ROI: re-apply the persisted polygon on enable.
            //   - block-tap ROI: the mask lives in unified config; on disable we must
            //     clear the unified-config roiEnabled_* flag too, otherwise the
            //     engine's applyEffectiveRoi() (which reads unified config) would
            //     revive the just-disabled zone on the setConfig() re-apply below.
            {
                String[] quadrantKeys = {"Q0", "Q1", "Q2", "Q3"};
                for (int q = 0; q < 4; q++) {
                    String enabledKey = "roiEnabled_" + quadrantKeys[q];
                    if (configJson.has(enabledKey)) {
                        boolean enabled = configJson.optBoolean(enabledKey, false);
                        if (enabled && sentryConfig.getRoiPolygon(q) != null) {
                            // Enable polygon ROI — apply the persisted polygon to C++
                            sentryConfig.setRoiEnabled(q, true);
                            if (sentry != null) sentry.applyQuadrantRoi(q, sentryConfig.getRoiPolygon(q));
                        } else if (!enabled) {
                            // Disable ROI — clear C++ mask, keep polygon data in config,
                            // and mirror the disable into unified config so the block-tap
                            // mask is not revived by the engine's config re-apply.
                            sentryConfig.setRoiEnabled(q, false);
                            if (sentry != null) sentry.clearQuadrantRoi(q);
                            try {
                                org.json.JSONObject survCfg =
                                    com.overdrive.app.config.UnifiedConfigManager.getSurveillance();
                                survCfg.put(enabledKey, false);
                                com.overdrive.app.config.UnifiedConfigManager.setSurveillance(survCfg);
                            } catch (Exception e) {
                                CameraDaemon.log("ROI disable persist failed Q" + q + ": " + e.getMessage());
                            }
                        } else {
                            // enabled==true but no polygon: a block-tap ROI is being
                            // enabled. The roiBlocks_* handler below carries the mask and
                            // its own enable flag; just record the intent on sentryConfig.
                            sentryConfig.setRoiEnabled(q, true);
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
            
            // Manual camera ID override. Persists into the same `camera`
            // section keys the new resolver reads (probedCameraId,
            // probedSurfaceMode, probedWidth, probedHeight, probedAndValidated,
            // manualOverride), with width/height pulled from the resolved
            // profile so a Tang override keeps Tang's 720 height, not Seal's 960.
            //
            // Surfaces save failures back to the caller — UnifiedConfigManager
            // can return false on this device when the app UID can't write
            // /data/local/tmp/overdrive_config.json (EACCES). Previously we
            // swallowed the failure and still answered success, leaving the
            // dialog reporting "saved" while the disk record was unchanged.
            // Also writes the manualOverride keys + camera section atomically
            // via UnifiedConfigManager.updateSection so a partial write can't
            // leave the resolver reading inconsistent state.
            if (configJson.has("manualCameraId")) {
                int camId = configJson.optInt("manualCameraId", -1);
                if (camId >= 0 && camId <= 5) {
                    com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
                        com.overdrive.app.camera.CameraConfigResolver.resolve();
                    org.json.JSONObject camCfg = new org.json.JSONObject();
                    try {
                        camCfg.put("probedCameraId", camId);
                        camCfg.put("probedSurfaceMode", resolvedCamera.getPanoSurfaceMode());
                        camCfg.put("probedWidth", resolvedCamera.getPanoWidth());
                        camCfg.put("probedHeight", resolvedCamera.getPanoHeight());
                        camCfg.put("probedAndValidated", true);
                        camCfg.put("fallbackFromProbe", false);
                        camCfg.put("manualOverride", true);
                    } catch (org.json.JSONException je) {
                        HttpResponse.sendJsonError(out, "Failed to build camera config: " + je.getMessage());
                        return;
                    }
                    boolean saved = com.overdrive.app.config.UnifiedConfigManager
                        .updateSection("camera", camCfg);
                    if (!saved) {
                        CameraDaemon.log("Failed to persist manual camera ID " + camId
                            + " — UnifiedConfigManager.updateSection returned false");
                        HttpResponse.sendJsonError(out,
                            "Could not persist camera config (filesystem permission?)");
                        return;
                    }
                    CameraDaemon.log("Manual camera ID set: " + camId
                        + " (will take effect on next pipeline init)");
                    configChanged = true;
                } else {
                    HttpResponse.sendJsonError(out,
                        "manualCameraId must be in range 0..5, got " + camId);
                    return;
                }
            }
            if (configJson.has("clearManualCameraId")
                    && configJson.optBoolean("clearManualCameraId", false)) {
                com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
                    com.overdrive.app.camera.CameraConfigResolver.resolve();
                org.json.JSONObject camCfg = new org.json.JSONObject();
                try {
                    camCfg.put("probedCameraId", -1);
                    camCfg.put("probedSurfaceMode", -1);
                    camCfg.put("probedWidth", resolvedCamera.getPanoWidth());
                    camCfg.put("probedHeight", resolvedCamera.getPanoHeight());
                    camCfg.put("probedAndValidated", false);
                    camCfg.put("fallbackFromProbe", false);
                    camCfg.put("manualOverride", false);
                } catch (org.json.JSONException je) {
                    HttpResponse.sendJsonError(out, "Failed to build camera config: " + je.getMessage());
                    return;
                }
                boolean saved = com.overdrive.app.config.UnifiedConfigManager
                    .updateSection("camera", camCfg);
                if (!saved) {
                    CameraDaemon.log("Failed to clear manual camera ID — "
                        + "UnifiedConfigManager.updateSection returned false");
                    HttpResponse.sendJsonError(out,
                        "Could not persist camera config (filesystem permission?)");
                    return;
                }
                CameraDaemon.log("Manual camera ID cleared — will auto-detect on next pipeline init");
                configChanged = true;
            }

            // DiLink 4 compatibility options. Passive APA mode leaves preview
            // port 0 untouched and suppresses all panorama viewpoint writes;
            // the red mask remains a cosmetic fallback for baked-in chrome.
            if (configJson.has("dilink4PassiveApaMode")
                    || configJson.has("dilink4RedMask")) {
                org.json.JSONObject camCfg = new org.json.JSONObject();
                try {
                    if (configJson.has("dilink4PassiveApaMode")) {
                        camCfg.put("dilink4PassiveApaMode",
                            configJson.optBoolean("dilink4PassiveApaMode", false));
                    }
                    if (configJson.has("dilink4RedMask")) {
                        camCfg.put("dilink4RedMask",
                            configJson.optBoolean("dilink4RedMask", false));
                    }
                } catch (org.json.JSONException je) {
                    HttpResponse.sendJsonError(out, "Failed to build camera config: " + je.getMessage());
                    return;
                }
                boolean saved = com.overdrive.app.config.UnifiedConfigManager
                    .updateSection("camera", camCfg);
                if (!saved) {
                    HttpResponse.sendJsonError(out,
                        "Could not persist DiLink 4 compatibility options");
                    return;
                }
                CameraDaemon.log("DiLink 4 compatibility options updated: " + camCfg);
                configChanged = true;
            }

            // Camera ingestion mode: "default" (legacy ImageReader + 4-strip
            // → 2x2 rearrangement) vs "dilink4" (oem SurfaceTexture +
            // passthrough). Persisted under camera.cameraMode and read by
            // PanoramicCameraGpu / GpuSurveillancePipeline at init. Save
            // triggers the same prepare-restart flow as a manual cam-id
            // change so the new mode takes effect.
            if (configJson.has("cameraMode")) {
                String mode = configJson.optString("cameraMode", "default")
                    .toLowerCase(java.util.Locale.US);
                if (!"default".equals(mode) && !"dilink4".equals(mode)) {
                    HttpResponse.sendJsonError(out,
                        "cameraMode must be 'default' or 'dilink4', got '" + mode + "'");
                    return;
                }
                org.json.JSONObject camCfg = new org.json.JSONObject();
                try {
                    camCfg.put("cameraMode", mode);
                } catch (org.json.JSONException je) {
                    HttpResponse.sendJsonError(out, "Failed to build camera config: " + je.getMessage());
                    return;
                }
                boolean saved = com.overdrive.app.config.UnifiedConfigManager
                    .updateSection("camera", camCfg);
                if (!saved) {
                    CameraDaemon.log("Failed to persist cameraMode=" + mode
                        + " — UnifiedConfigManager.updateSection returned false");
                    HttpResponse.sendJsonError(out,
                        "Could not persist camera mode (filesystem permission?)");
                    return;
                }
                CameraDaemon.log("Camera ingestion mode set: " + mode
                    + " (will take effect on next daemon restart)");
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
            
            // Save recording settings (quality tier, codec) to unified config.
            // Accepts both the new `recordingQuality` (ECONOMY..MAX) and the
            // legacy `recordingBitrate` (LOW/MEDIUM/HIGH) for forward compat.
            boolean recordingChanged = false;
            if (configJson.has("recordingQuality") || configJson.has("recordingBitrate") || configJson.has("recordingCodec")) {
                try {
                    JSONObject recording = com.overdrive.app.config.UnifiedConfigManager.getRecording();
                    String appliedTier = null;
                    if (configJson.has("recordingQuality")) {
                        appliedTier = configJson.optString("recordingQuality", "STANDARD");
                    } else if (configJson.has("recordingBitrate")) {
                        // Legacy path: translate LOW/MEDIUM/HIGH → tier name
                        // and persist under the canonical key.
                        String bitrate = configJson.optString("recordingBitrate", "MEDIUM");
                        switch (bitrate.toUpperCase()) {
                            case "LOW":    appliedTier = "ECONOMY"; break;
                            case "MEDIUM": appliedTier = "STANDARD"; break;
                            case "HIGH":   appliedTier = "HIGH"; break;
                            default:       appliedTier = "STANDARD"; break;
                        }
                    }
                    if (appliedTier != null) {
                        recording.put("recordingQuality", appliedTier);
                        recording.put("quality", appliedTier);  // mirror for legacy readers
                        recording.remove("bitrate");  // drop stale LOW/MEDIUM/HIGH so cross-channel readers don't see drift
                        recordingChanged = true;
                        try {
                            CameraDaemon.setRecordingQuality(appliedTier);
                        } catch (Exception e) {
                            CameraDaemon.log("Failed to apply recordingQuality to pipeline: " + e.getMessage());
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
                        CameraDaemon.log("Recording settings saved: recordingQuality="
                                + recording.optString("recordingQuality")
                                + ", codec=" + recording.optString("codec"));
                    }
                } catch (Exception e) {
                    CameraDaemon.log("Failed to save recording settings: " + e.getMessage());
                }
            }

            if (reconcileOperatingMode) {
                CameraDaemon.reconcileOperatingModeForCurrentAccState();
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
        //
        // The persist result is LOAD-BEARING, not advisory: the ACC-OFF arm
        // dispatch re-reads the persisted flag, so a failed write (EACCES on
        // app-UID writes is the common case here) means surveillance will NOT
        // arm on the next park. Reporting success there is what makes an
        // automation look like it ran while nothing was ever armed.
        if (!com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true)) {
            CameraDaemon.log("Failed to persist surveillanceEnabled=true — surveillance will NOT arm on next ACC OFF");
            HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_persist_failed"));
            return;
        }

        // Only actually start surveillance if ACC is currently OFF (sentry mode)
        boolean accIsOn = com.overdrive.app.monitor.AccMonitor.isAccOn();
        if (!accIsOn) {
            CameraDaemon.enableSurveillance();   // fires OEM recalc internally
        } else {
            CameraDaemon.log("Surveillance preference saved — will activate on next ACC OFF");
            // Even though pano sentry doesn't arm during ACC ON, the OEM
            // resolver reads UnifiedConfigManager.isSurveillanceEnabled()
            // into survSuppressed (negated). Without this recalc, an OEM
            // surv=continuous user who flips master ON while driving would
            // see correct behavior on next ACC OFF only because the ACC OFF
            // dispatch fires another recalc — but the resolver also feeds
            // keepWarmSurv at any reachable ACC OFF transition. Defensive.
            try {
                com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
            } catch (Throwable ignored) {}
        }
        // deferred=true means "preference stored, nothing armed yet" — the
        // caller (automation / web toggle / key mapping) can say so instead of
        // reporting a plain success the user reads as "surveillance is on now".
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("deferred", accIsOn);
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleDisable(OutputStream out) throws Exception {
        // ACC-GATED teardown. While ACC is ON no sentry is armed, so
        // disableSurveillance() has nothing to tear down — its only effects are
        // gpuPipeline.disableSurveillance() and clearing the in-memory
        // `surveillanceEnabled` intent field. The pipeline call is the hazard:
        // it forces currentMode IDLE (GpuSurveillancePipeline:3855) and pushes
        // the dashcam layout profile, so an automation firing "surveillance off"
        // mid-drive re-applies the layout under a live CONTINUOUS/DRIVE_MODE
        // recording for no reason. The ACC-ON path already clears the intent
        // field itself, so skipping the whole call here loses nothing.
        boolean accIsOn = com.overdrive.app.monitor.AccMonitor.isAccOn();
        if (!accIsOn) {
            CameraDaemon.disableSurveillance();   // fires OEM recalc internally
        }
        if (!com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false)) {
            CameraDaemon.log("Failed to persist surveillanceEnabled=false — surveillance may re-arm on next ACC OFF");
            HttpResponse.sendJsonError(out, Messages.get("errors.surveillance_persist_failed"));
            return;
        }
        // disableSurveillance ran BEFORE the UCM write, so its recalc saw the
        // old surveillanceEnabled=true. Fire a second recalc post-write so
        // the resolver picks up the now-disabled master toggle and applies
        // survSuppressed=true to any in-flight surv=continuous recording.
        // Also the ONLY recalc on the ACC-ON path, where the disable above is
        // skipped — the resolver still has to see the new master toggle.
        try {
            com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
        } catch (Throwable ignored) {}
        JSONObject response = new JSONObject();
        response.put("success", true);
        // Nothing was armed to stop, so the write only affects the next park.
        response.put("deferred", accIsOn);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Graceful pre-restart flush. Called by the camera-mapping dialog
     * BEFORE killing the daemon, so the running pipeline finalizes any
     * in-flight recording (writes the MP4 moov atom, flushes the H264
     * circular buffer, closes encoder + EGL surfaces) instead of being
     * SIGKILL'd mid-write.
     *
     * <p>Synchronous: blocks until pipeline.stop() returns. Bounded by the
     * pipeline's own teardown timeline (typically 1-2 s) — the dialog's
     * 4 s read timeout covers it.
     */
    private static void handlePrepareRestart(OutputStream out) throws Exception {
        // Mark shutdown so future cold-start requests fall through to
        // "Preview unavailable" instead of looping the dialog on 503.
        // Critical: must be set BEFORE we wait on coldStartInProgress —
        // otherwise a new sendCameraPreview can start another cold-start
        // immediately after the existing one releases the flag, and we'd
        // race in a circle.
        shutdownInProgress = true;
        // CAS: take ownership of the cold-start flag. If a panoramic-slice
        // preview kicked off a cold-start, wait briefly for it to finish
        // before stop() — running stop() concurrently with start() leaks
        // encoder/EGL.
        //
        // If cold-start is still in flight after 3 s, reject this prepare
        // instead of force-taking the flag (which would race the still-running
        // start and corrupt the encoder). The client must not SIGKILL unless
        // this endpoint confirms that both startup ownership and trip
        // durability are settled.
        long deadline = System.currentTimeMillis() + 3000;
        boolean tookFlag = false;
        while (true) {
            if (coldStartInProgress.compareAndSet(false, true)) {
                tookFlag = true;
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                CameraDaemon.log("prepare-restart: cold-start still in flight after 3s — "
                        + "rejecting restart");
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!tookFlag) {
            shutdownInProgress = false;
            JSONObject failure = new JSONObject();
            failure.put("success", false);
            failure.put("error", "Camera startup is still in progress; restart was not prepared");
            failure.put("retryable", true);
            failure.put("retryAfterMs", 1000);
            HttpResponse.sendJson(out, 503, failure.toString());
            return;
        }
        // CHECKPOINT any in-progress trip before the caller SIGKILLs us. The
        // client's restart flow is prepare-restart + `killall -9`, which never
        // runs the JVM shutdown hook, so the hook's trip finalize is skipped
        // here and the trip would otherwise lose everything buffered since the
        // last periodic flush.
        //
        // Deliberately prepareForProcessRestart(), NOT shutdown(). shutdown() would
        // finalizeActiveTrip() → apply the 60s/0.2km floors → discardTrip() →
        // DELETE the telemetry file, destroying a short trip that previously
        // survived the kill as a recoverable file. It would also flip
        // initialized/enabled false and close the H2 store, which strands trips
        // dead for the rest of the process whenever the caller's SIGKILL fails
        // (the abort-restart endpoint exists precisely because it can).
        // A positive result is mandatory. Reporting success after a timed-out
        // or failed flush lets the caller kill the only process holding the
        // telemetry tail and is indistinguishable from data loss.
        boolean tripCheckpointDurable = true;
        // Carried into the 503 body. Without it every distinct cause below
        // reached the updater as an indistinguishable "HTTP 503".
        String tripCheckpointFailure = null;
        try {
            com.overdrive.app.trips.TripAnalyticsManager tam =
                CameraDaemon.getTripAnalyticsManager();
            if (tam == null || !tam.isInitialized()) {
                tripCheckpointDurable = false;
                // Trip analytics initializes on its own thread while HTTP is
                // already serving, so this is a startup race, not a fault.
                tripCheckpointFailure = "trip analytics is still starting up";
                CameraDaemon.log("prepare-restart: trip manager is not ready");
            } else if (tam.isEnabled()) {
                CameraDaemon.log("prepare-restart: checkpointing active trip before kill");
                // Flush buffered telemetry so the on-disk .jsonl.gz covers
                // everything sampled so far. The trip is left OPEN — next boot
                // rebuilds the row from the file. Best-effort by contract, so
                // there is no verdict to gate the restart on.
                tam.checkpointActiveTrip();
            }
        } catch (Throwable t) {
            CameraDaemon.log("prepare-restart: trip checkpoint failed: " + t.getMessage());
            tripCheckpointDurable = false;
            tripCheckpointFailure = "trip checkpoint threw "
                    + t.getClass().getSimpleName();
        }
        if (!tripCheckpointDurable) {
            coldStartInProgress.set(false);
            shutdownInProgress = false;
            JSONObject failure = new JSONObject();
            failure.put("success", false);
            failure.put("error", tripCheckpointFailure != null
                    ? "Active trip could not be durably checkpointed: "
                            + tripCheckpointFailure
                    : "Active trip could not be durably checkpointed");
            // Every cause here clears on its own within a few seconds (spool
            // drain, startup, final flush), so the client should retry.
            failure.put("retryable", true);
            failure.put("retryAfterMs", 1000);
            HttpResponse.sendJson(out, 503, failure.toString());
            return;
        }
        boolean pipelinePrepared = true;
        try {
            GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline != null && pipeline.isRunning()) {
                CameraDaemon.log("prepare-restart: stopping pipeline gracefully");
                pipeline.stop();
            }
        } catch (Exception e) {
            CameraDaemon.log("prepare-restart: pipeline.stop failed: " + e.getMessage());
            pipelinePrepared = false;
        } finally {
            coldStartInProgress.set(false);
        }
        if (!pipelinePrepared) {
            // No trip state to undo: the checkpoint above only flushed
            // telemetry and left the trip open and recording.
            shutdownInProgress = false;
            JSONObject failure = new JSONObject();
            failure.put("success", false);
            failure.put("error", "Camera pipeline could not be stopped safely");
            // Deliberately NOT retryable: pipeline.stop() catches its own
            // per-step failures, so reaching here means teardown threw out of
            // the whole block. Retrying that would re-enter stop() on a
            // half-torn-down encoder rather than wait out a transient state.
            failure.put("retryable", false);
            HttpResponse.sendJson(out, 503, failure.toString());
            return;
        }
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
        // MUST match MotionPipelineV2.QUADRANT_NAMES: Q0=front, Q1=right, Q2=REAR,
        // Q3=LEFT. This array had "left" and "rear" transposed, so the heatmap
        // labelled every Q2 (rear) reading "left" and every Q3 (left) reading
        // "rear" — i.e. enabling the debug heatmap showed motion blocks on the
        // rear camera when the motion was actually on the left one. It was the
        // only place in the codebase with this order (grep: EventTimelineCollector
        // and MotionPipelineV2 both use the canonical one).
        String[] names = MotionPipelineV2.QUADRANT_NAMES;
        
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

    // ==================== Camera-preview cold-start single-flight ====================
    //
    // The camera-mapping dialog renders preview tiles in sequence (Prev/Next
    // navigation, ~2s polling cadence). Without coordination, each unmapped-
    // pipeline request would call gpuPipeline.start(false) + Thread.sleep on
    // the HTTP worker; multiple in-flight requests stacked starts and burned
    // 6+ seconds of HTTP-thread time per dialog open.
    //
    // Single-flight gate — first request triggers an async start on a
    // dedicated executor and returns 503 Retry-After=2s. Subsequent requests
    // also return 503 until the pipeline is up. No HTTP worker ever blocks on
    // a HAL warm-up.
    private static final java.util.concurrent.atomic.AtomicBoolean coldStartInProgress =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    // Set by prepare-restart to mark the daemon as "shutting down for a
    // restart that the dialog is about to SIGKILL through". When true,
    // requestColdStartAsync returns false → sendCameraPreview falls through
    // to "Preview unavailable" instead of looping the dialog on 503 while
    // the daemon dies. The flag isn't cleared on this daemon (the kill is
    // imminent); the relaunched JVM resets it naturally.
    private static volatile boolean shutdownInProgress = false;
    private static final java.util.concurrent.ExecutorService coldStartExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CameraPreviewColdStart");
            t.setDaemon(true);
            return t;
        });

    private static boolean requestColdStartAsync(GpuSurveillancePipeline pipeline) {
        if (pipeline == null) return false;
        // If a shutdown is in flight, do not promise the dialog that
        // warming-up will eventually serve a frame — the daemon is about
        // to die. Tell sendCameraPreview to send "Preview unavailable" so
        // the dialog stops polling.
        if (shutdownInProgress) return false;
        // CAS: if another cold-start is already in flight, that's fine —
        // the existing executor task will finish and serve subsequent
        // requests. Tell caller to send 503 retry.
        if (!coldStartInProgress.compareAndSet(false, true)) return true;
        coldStartExecutor.execute(() -> {
            try {
                CameraDaemon.log("camera-preview: cold-starting pipeline (single-flight)");
                pipeline.start(false);
            } catch (Exception e) {
                CameraDaemon.log("camera-preview cold start failed: " + e.getMessage());
            } finally {
                coldStartInProgress.set(false);
            }
        });
        return true;
    }

    private static void sendWarmingUp(OutputStream out) throws Exception {
        String body = "{\"success\":false,\"error\":\"warming-up\",\"retryAfterMs\":2000}";
        byte[] payload = body.getBytes("UTF-8");
        String header = "HTTP/1.1 503 Service Unavailable\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + payload.length + "\r\n" +
                "Retry-After: 2\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes());
        out.write(payload);
        out.flush();
    }

    /**
     * GET /api/surveillance/camera-preview?kind=&...
     *
     * Three preview kinds for the diagnostics camera-mapping dialog:
     *   kind=direct&cameraId=N[&width=W&height=H]   — direct AVMCamera open;
     *      refused when surveillance pipeline currently holds the same
     *      cameraId (multi-claim crashes the BYD HAL — event 1002).
     *   kind=panoramicSlice&slice=sliceN            — quadrant of the live
     *      mosaic JPEG published by SurveillanceEngineGpu. Zero camera open,
     *      zero GL work — volatile read + JPEG decode/crop on HTTP worker.
     *   kind=panoramic&view=front|right|rear|left   — same as panoramicSlice
     *      but keyed by virtual view (legacy view-mode mapping).
     *
     * On cold pipeline (proximity-guard mode, surveillance idle) the
     * panoramic kinds trigger a single-flight cold-start and return 503
     * Retry-After=2s. Direct kind never auto-starts the pipeline.
     */
    private static void sendCameraPreview(String path, OutputStream out) throws Exception {
        com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
            com.overdrive.app.camera.CameraConfigResolver.resolve();
        String kind = getQueryParam(path, "kind");
        GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        byte[] jpegBytes = null;

        if ("direct".equalsIgnoreCase(kind)) {
            int cameraId = safeParseInt(getQueryParam(path, "cameraId"), -1);
            if (cameraId < 0 || cameraId > 5) {
                HttpResponse.sendJsonError(out, "Invalid direct camera ID");
                return;
            }
            int width = safeParseInt(getQueryParam(path, "width"),
                    resolvedCamera.getProfile().getDirectPreviewWidth());
            int height = safeParseInt(getQueryParam(path, "height"),
                    resolvedCamera.getProfile().getDirectPreviewHeight());

            // For the panoramic camera ID, ALWAYS route to the 2x2 mosaic
            // sampler — never call captureDirectPreviewJpeg. The BYD HAL
            // delivers a 5120×960 raw strip on this camera ID, and an
            // ImageReader sized to (1280×960) just squashes the strip
            // horizontally → wide compressed image, not the 2x2 mosaic
            // the user expects to see. The mosaic sampler renders the
            // proper 2560×1920 (Seal) / 2560×1440 (Tang) 2x2 grid via
            // a sync FBO on its own GL thread (recording-safe).
            //
            // Cold-start the pipeline if it's not running (fresh install /
            // proximity-guard mode before first dialog open).
            if (cameraId == resolvedCamera.getPanoCameraId() && gpuPipeline != null) {
                if (!gpuPipeline.isRunning()) {
                    if (requestColdStartAsync(gpuPipeline)) {
                        sendWarmingUp(out);
                        return;
                    }
                }
                if (gpuPipeline.getCamera() != null) {
                    jpegBytes = gpuPipeline.getCamera().sampleFullResMosaicJpeg();
                }
            } else {
                // Other direct cameras (front-facing, dashcam, cabin, etc.)
                // are single-feed and ImageReader-sizing works correctly.
                jpegBytes = com.overdrive.app.camera.CameraPreviewHelper
                    .captureDirectPreviewJpeg(cameraId, width, height);
            }
        } else if ("panoramicSlice".equalsIgnoreCase(kind)) {
            com.overdrive.app.camera.PanoramicSlice slice =
                com.overdrive.app.camera.PanoramicSlice.fromId(getQueryParam(path, "slice"));
            if (slice == null) {
                HttpResponse.sendJsonError(out, "Invalid panoramic slice");
                return;
            }
            jpegBytes = com.overdrive.app.camera.CameraPreviewHelper.capturePanoramicSliceJpeg(slice);
            if (jpegBytes == null && gpuPipeline != null) {
                if (!gpuPipeline.isRunning()) {
                    if (requestColdStartAsync(gpuPipeline)) {
                        sendWarmingUp(out);
                        return;
                    }
                }
                // Pipeline running, capturePanoramicSliceJpeg already tried
                // both engine-mosaic and high-res slice render via
                // CameraPreviewHelper. If both came back null the camera
                // texture isn't bound yet (cold HAL warmup) — tell dialog
                // to retry.
                if (jpegBytes == null) {
                    sendWarmingUp(out);
                    return;
                }
            }
        } else {
            com.overdrive.app.camera.CameraVirtualView view =
                com.overdrive.app.camera.CameraVirtualView.fromId(getQueryParam(path, "view"));
            if (view == null) {
                HttpResponse.sendJsonError(out, "Invalid panoramic view");
                return;
            }
            jpegBytes = com.overdrive.app.camera.CameraPreviewHelper.capturePanoramicViewJpeg(view);
            if (jpegBytes == null && gpuPipeline != null) {
                boolean cold = !gpuPipeline.isRunning();
                if (cold && requestColdStartAsync(gpuPipeline)) {
                    sendWarmingUp(out);
                    return;
                }
                if (!cold) {
                    sendWarmingUp(out);
                    return;
                }
            }
        }

        if (jpegBytes == null || jpegBytes.length == 0) {
            HttpResponse.sendJsonError(out, "Preview unavailable");
            return;
        }

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

    private static String getQueryParam(String path, String key) {
        if (path == null) return null;
        int queryStart = path.indexOf('?');
        if (queryStart < 0 || queryStart >= path.length() - 1) return null;
        String[] pairs = path.substring(queryStart + 1).split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && key.equalsIgnoreCase(kv[0])) {
                try {
                    return java.net.URLDecoder.decode(kv[1], "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private static int safeParseInt(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
