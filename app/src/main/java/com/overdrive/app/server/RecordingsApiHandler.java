package com.overdrive.app.server;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.storage.StorageManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recordings API Handler - serves recording list, metadata, and video files.
 * 
 * SOTA: Uses StorageManager for dedicated Overdrive directories with size limits.
 * 
 * Endpoints:
 * - GET /api/recordings - List all recordings with optional filters
 * - GET /api/recordings/dates - Get dates with recordings (for calendar)
 * - GET /api/recordings/stats - Get storage statistics
 * - GET /video/{filename} - Stream video file
 * - GET /thumb/{filename} - Get video thumbnail (cached)
 * - DELETE /api/recordings/{filename} - Delete a recording
 */
public class RecordingsApiHandler {
    
    // Thumbnail cache directory - use parent of recordings dir
    private static String getThumbnailCacheDir() {
        String recordingsPath = StorageManager.getInstance().getRecordingsPath();
        File recordingsDir = new File(recordingsPath);
        File baseDir = recordingsDir.getParentFile();
        return new File(baseDir, "thumbs").getAbsolutePath();
    }
    
    // SOTA: Use StorageManager for paths
    private static String getRecordingsDir() {
        return StorageManager.getInstance().getRecordingsPath();
    }
    
    private static String getSentryDir() {
        return StorageManager.getInstance().getSurveillancePath();
    }
    
    // -----------------------------------------------------------------
    // Index integration
    // -----------------------------------------------------------------
    //
    // The H2-backed RecordingsIndex (server/RecordingsIndex.java) is the
    // single source of truth for the listing endpoints. The legacy
    // RECORDING_CACHE + in-memory inverted index that lived here was
    // replaced because it (a) couldn't survive cross-UID reads, (b)
    // didn't persist across daemon restarts, and (c) repeated the full
    // dir-walk + sidecar-parse on every cold start.
    //
    // Public stubs below preserve the call shape used by callers
    // outside this class (HardwareEventRecorderGpu, RecordingScanner.kt,
    // CameraDaemon's hourly maintenance) so no caller had to be touched
    // when the impl flipped to indexed SQL.

    /**
     * Drop a cache entry for the given mp4 absolute path. Callers outside
     * this class (loop rotation in HardwareEventRecorderGpu, the Kotlin
     * RecordingScanner, manual SD-card maintenance) call this when they
     * delete an .mp4 so the API doesn't return a phantom entry.
     *
     * <p>Now delegates to {@link RecordingsIndex#remove(String)} — the
     * old per-(path|type) parse cache no longer exists.
     */
    public static void invalidateRecordingCache(String absMp4Path) {
        if (absMp4Path == null) return;
        try {
            RecordingsIndex.getInstance().removeByPath(absMp4Path);
        } catch (Throwable ignored) {}
    }

    /**
     * Pre-populate the index without serving a request. Called from the
     * daemon's post-startup background thread so the first user-visible
     * /api/recordings call doesn't pay the full directory-walk cost
     * inline. The index's own {@link RecordingsIndex#warmupAsync()} is
     * idempotent.
     */
    public static void warmupCache() {
        try {
            RecordingsIndex.getInstance().warmupAsync();
        } catch (Throwable t) {
            CameraDaemon.log("RecordingsApiHandler warmup kick failed: " + t.getMessage());
        }
    }

    /**
     * Periodic prune. Reconciles the index against the filesystem so
     * SD-card mounts/unmounts, out-of-band rsync edits, and dropped
     * FileObserver events on FUSE all converge eventually. Cheap when
     * already in sync.
     */
    public static void pruneRecordingCache() {
        try {
            RecordingsIndex.getInstance().requestReconcile("periodic-prune");
        } catch (Throwable t) {
            CameraDaemon.log("RecordingsApiHandler reconcile request failed: " + t.getMessage());
        }
    }
    
    
    /**
     * Handle recordings API requests.
     * @return true if handled, false if not a recordings endpoint
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        // List recordings (with optional query params)
        if ((path.equals("/api/recordings") || path.startsWith("/api/recordings?")) && method.equals("GET")) {
            String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            Map<String, String> params = parseQuery(query);
            String type = params.get("type");
            String date = params.get("date");
            int page = parseIntParam(params.get("page"), 1);
            int pageSize = parseIntParam(params.get("pageSize"), 12);
            // Clamp pageSize. Native fragment & web events.html paginate
            // at 12-30 rows per visible page, but the native scanner's
            // bulk fetch (RecordingsApiClient.fetchAllRecordings) wants
            // 200 to keep the round-trip count low for full-library
            // exports + segment counters. With the H2 index every
            // request is an indexed seek + bounded result-set walk —
            // 200 rows ≈ same wall-clock as 50.
            pageSize = Math.max(1, Math.min(pageSize, 200));
            // v3 filters (item 6): comma-separated lists of class groups, severities,
            // and proximity bands. Empty / missing = no filter.
            String classes = params.get("class");        // e.g. "person,vehicle"
            String severities = params.get("severity");  // e.g. "ALERT,CRITICAL"
            String proximities = params.get("proximity"); // e.g. "VERY_CLOSE,CLOSE"
            // Place filter (item 7): single short label, case-insensitive.
            // Server-side so pagination + totalCount stay honest under the
            // filter — client-side filtering would let "page 2 of 5" hide
            // matching clips on later pages.
            String place = params.get("place");
            // Free-text place substring search — matches across short,
            // medium, and displayName labels. Distinct from `place` (exact
            // chip match): "Bay" hits "Marina Bay"+"Bay City", `Cheras`
            // chip is exact.
            String placeContains = params.get("placeContains");
            // Country narrowing — ISO 3166-1 alpha-2 lowercased.
            String country = params.get("country");
            // Storage-volume narrowing — comma-separated INTERNAL/SD_CARD/USB.
            // Missing = all volumes (the index already spans every location).
            String storage = params.get("storage");
            listRecordings(out, type, date, page, pageSize,
                    classes, severities, proximities, place,
                    placeContains, country, storage);
            return true;
        }

        // Distinct places list (top-N by count) — drives the dynamic
        // Place chip row in events.html. Scoped by the SAME filter
        // context as /api/recordings (minus the place filter itself),
        // so e.g. switching to the Sentry tab refreshes the chip set
        // to "places where sentry events happened" instead of every
        // place across every type.
        if ((path.equals("/api/recordings/places") || path.startsWith("/api/recordings/places?"))
                && method.equals("GET")) {
            String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            Map<String, String> params = parseQuery(query);
            String type = params.get("type");
            String date = params.get("date");
            String classes = params.get("class");
            String severities = params.get("severity");
            String proximities = params.get("proximity");
            String placeContains = params.get("placeContains");
            String country = params.get("country");
            String storage = params.get("storage");
            listPlaces(out, type, date, classes, severities, proximities,
                    placeContains, country, storage);
            return true;
        }

        // Get dates with recordings
        if (path.equals("/api/recordings/dates") && method.equals("GET")) {
            getDatesWithRecordings(out);
            return true;
        }
        
        // Get storage stats
        if (path.equals("/api/recordings/stats") && method.equals("GET")) {
            getStorageStats(out);
            return true;
        }
        
        // Exact identity routes must be checked before legacy filename prefixes.
        if (path.startsWith("/thumb/id/")) {
            String recordingId = stripQuery(path.substring("/thumb/id/".length()));
            serveThumbnailById(out, recordingId);
            return true;
        }

        if (path.startsWith("/video/id/")) {
            String recordingId = stripQuery(path.substring("/video/id/".length()));
            streamVideoById(out, recordingId, null, null);
            return true;
        }

        if (path.startsWith("/api/recordings/id/") && method.equals("DELETE")) {
            String recordingId = stripQuery(path.substring("/api/recordings/id/".length()));
            deleteRecordingById(out, recordingId);
            return true;
        }

        if (path.startsWith("/api/events/id/") && method.equals("GET")) {
            String recordingId = stripQuery(path.substring("/api/events/id/".length()));
            serveEventTimelineById(out, recordingId);
            return true;
        }

        // Serve legacy filename thumbnail
        if (path.startsWith("/thumb/")) {
            String filename = path.substring(7);
            // Strip any cache-busting/auth query (e.g. /thumb/foo.jpg?t=<token>);
            // the notification-log snapshot URLs carry one, and serveThumbnail's
            // ".jpg" fast-path check + File lookup would otherwise fail → 404.
            int qIdx = filename.indexOf('?');
            if (qIdx >= 0) filename = filename.substring(0, qIdx);
            serveThumbnail(out, filename);
            return true;
        }
        
        // Stream video file
        if (path.startsWith("/video/")) {
            String filename = path.substring(7);
            streamVideo(out, filename, null, null);
            return true;
        }
        
        // Batch delete recordings
        if (path.equals("/api/recordings/batch-delete") && method.equals("POST")) {
            batchDeleteRecordings(out, body);
            return true;
        }

        // In-flight recording probe — used by events.js to show a pinned
        // "Recording in progress" placeholder when the user taps a fresh
        // notification before the .mp4.tmp has been finalized to .mp4.
        if (path.startsWith("/api/recordings/inflight/") && method.equals("GET")) {
            String filename = path.substring("/api/recordings/inflight/".length());
            serveInflightStatus(out, filename);
            return true;
        }

        // Delete recording
        if (path.startsWith("/api/recordings/") && method.equals("DELETE")) {
            String filename = path.substring(16);
            deleteRecording(out, filename);
            return true;
        }
        
        // SOTA: Get event timeline for a recording (JSON sidecar)
        if (path.startsWith("/api/events/") && method.equals("GET")) {
            String filename = path.substring(12);
            serveEventTimeline(out, filename);
            return true;
        }
        
        return false;
    }
    
    private static int parseIntParam(String value, int defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    /**
     * Handle with Range header support for video seeking and conditional GET
     * (If-None-Match) for ETag-based 304 responses on cached recordings.
     */
    public static boolean handleWithRange(String method, String path, String body,
                                          String rangeHeader, String ifNoneMatchHeader,
                                          OutputStream out) throws Exception {
        if (path.startsWith("/video/id/")) {
            String recordingId = stripQuery(path.substring("/video/id/".length()));
            streamVideoById(out, recordingId, rangeHeader, ifNoneMatchHeader);
            return true;
        }
        if (path.startsWith("/video/")) {
            String filename = path.substring(7);
            streamVideo(out, filename, rangeHeader, ifNoneMatchHeader);
            return true;
        }
        return handle(method, path, body, out);
    }

    private static String stripQuery(String value) {
        int query = value.indexOf('?');
        return query >= 0 ? value.substring(0, query) : value;
    }

    private static boolean validRecordingId(String recordingId) {
        return recordingId != null && recordingId.matches("[0-9a-f]{32}");
    }
    
    // Background thumbnail generator
    private static final java.util.concurrent.ExecutorService thumbExecutor = 
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final Set<String> pendingThumbs = java.util.Collections.synchronizedSet(new HashSet<>());
    
    /**
     * Serve a cached thumbnail for a video file.
     * Returns placeholder immediately if not cached, generates in background.
     */
    private static void serveThumbnail(OutputStream out, String filename) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }

        // Direct sidecar JPEG hits — heroes ("event_xxx.jpg") or per-actor
        // ("thumb_event_xxx_a17_9300.jpg") written by ThumbnailBuffer next to
        // the MP4. Looking these up here means events.js can use a single URL
        // shape (/thumb/<filename>) for both video-frame and AI thumbnails.
        if (filename.toLowerCase(Locale.US).endsWith(".jpg")) {
            File jpegFile = findSiblingJpeg(filename);
            if (jpegFile != null && jpegFile.exists() && jpegFile.length() > 0) {
                HttpResponse.sendImage(out, jpegFile, "image/jpeg");
                return;
            }
            HttpResponse.sendError(out, 404, Messages.get("errors.recordings_thumbnail_not_found_with_filename", filename));
            return;
        }

        // Check cache first
        File cacheDir = new File(getThumbnailCacheDir());
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }

        String thumbName = filename.replace(".mp4", ".jpg");
        File thumbFile = new File(cacheDir, thumbName);

        // SOTA: if a v3 hero JPEG exists alongside the MP4, prefer it.
        // It's the peak-severity moment captured during the recording rather
        // than a generic frame at +1s. Backwards-compat: legacy clips without
        // a hero file fall through to the cache + MediaMetadataRetriever path.
        File heroSibling = findSiblingJpeg(thumbName);
        if (heroSibling != null && heroSibling.exists() && heroSibling.length() > 0) {
            HttpResponse.sendImage(out, heroSibling, "image/jpeg");
            return;
        }

        // If cached thumbnail exists and is valid, serve it immediately
        if (thumbFile.exists() && thumbFile.length() > 0) {
            HttpResponse.sendImage(out, thumbFile, "image/jpeg");
            return;
        }

        // Find the source video file. allowInFlightTmp=true so a notification
        // tapped within seconds of motion still gets a hero image: the
        // MediaMetadataRetriever can read sync frames from <name>.mp4.tmp
        // before the muxer finalises the moov atom on close.
        File videoFile = findVideoFile(filename, true);
        if (videoFile == null) {
            HttpResponse.sendError(out, 404, Messages.get("errors.recordings_video_not_found_with_filename", filename));
            return;
        }

        // Queue background generation if not already pending. add() returns
        // false when the element was already present, so a single atomic
        // call avoids the check-then-act race where two concurrent requests
        // both pass `contains()` and submit overlapping FileOutputStreams to
        // the same thumb file.
        if (pendingThumbs.add(filename)) {
            final File vf = videoFile;
            final File tf = thumbFile;
            final String fn = filename;
            thumbExecutor.submit(() -> {
                try {
                    byte[] data = generateThumbnail(vf);
                    if (data != null) {
                        try (FileOutputStream fos = new FileOutputStream(tf)) {
                            fos.write(data);
                        }
                    }
                } catch (Exception e) {
                    CameraDaemon.log("Background thumb gen failed: " + e.getMessage());
                } finally {
                    pendingThumbs.remove(fn);
                }
            });
        }
        
        // Return 202 Accepted with retry hint - client should retry
        sendThumbnailGenerating(out);
    }

    private static void serveThumbnailById(OutputStream out, String recordingId) throws Exception {
        if (!validRecordingId(recordingId)) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        RecordingsIndex.RecordingRef ref =
                RecordingsIndex.getInstance().resolveById(recordingId);
        if (ref == null) {
            HttpResponse.sendError(out, 404,
                Messages.get("errors.recordings_thumbnail_not_found_with_filename", recordingId));
            return;
        }
        File videoFile = ref.file();
        if (!videoFile.isFile() || !videoFile.canRead()) {
            HttpResponse.sendError(out, 410, Messages.get("errors.recordings_file_no_longer_accessible"));
            return;
        }
        if (ref.heroThumbnail != null && !ref.heroThumbnail.isEmpty()) {
            File hero = new File(videoFile.getParentFile(), ref.heroThumbnail);
            if (hero.isFile() && hero.canRead() && hero.length() > 0) {
                HttpResponse.sendImage(out, hero, "image/jpeg");
                return;
            }
        }
        File cacheDir = new File(getThumbnailCacheDir());
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File thumbFile = new File(cacheDir, recordingId + ".jpg");
        if (thumbFile.isFile() && thumbFile.length() > 0) {
            HttpResponse.sendImage(out, thumbFile, "image/jpeg");
            return;
        }
        if (pendingThumbs.add(recordingId)) {
            thumbExecutor.submit(() -> {
                try {
                    byte[] data = generateThumbnail(videoFile);
                    if (data != null) {
                        try (FileOutputStream output = new FileOutputStream(thumbFile)) {
                            output.write(data);
                        }
                    }
                } catch (Exception failure) {
                    CameraDaemon.log("ID thumbnail generation failed: " + failure.getMessage());
                } finally {
                    pendingThumbs.remove(recordingId);
                }
            });
        }
        sendThumbnailGenerating(out);
    }

    private static void sendThumbnailGenerating(OutputStream out) throws Exception {
        String headers = "HTTP/1.1 202 Accepted\r\n"
                + "Content-Type: application/json\r\n"
                + "Retry-After: 1\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write("{\"status\":\"generating\"}".getBytes());
        out.flush();
    }
    
    /**
     * Generate a thumbnail from a video file using MediaMetadataRetriever.
     * Extracts frame at 1 second mark, scales to 160x90 for efficiency.
     */
    private static byte[] generateThumbnail(File videoFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        // setDataSource(String) calls ActivityThread.currentApplication().getPackageManager()
        // for MIME lookup. The daemon has no registered Application, so that NPEs on DiLink5.
        // The FileDescriptor overload skips the package-manager probe entirely.
        try (FileInputStream fis = new FileInputStream(videoFile)) {
            retriever.setDataSource(fis.getFD());

            // Get frame at 1 second (1000000 microseconds)
            Bitmap frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                // Try frame at 0 if 1 second fails
                frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            
            if (frame == null) {
                return null;
            }
            
            // Scale down to thumbnail size (320x180 for 16:9 aspect)
            int targetWidth = 320;
            int targetHeight = 180;
            Bitmap scaled = Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true);
            
            // Compress to JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos);
            
            // Clean up
            if (scaled != frame) {
                scaled.recycle();
            }
            frame.recycle();
            
            return baos.toByteArray();
        } catch (Exception e) {
            CameraDaemon.log("Thumbnail generation failed: " + e.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {}
        }
    }
    
    /**
     * Reports whether a given filename is currently being written by the
     * encoder as {@code <filename>.tmp}. Used by the events page to display
     * a pinned "Recording in progress" placeholder when the user taps a
     * notification before the post-record window finalizes the file.
     *
     * <p>Response shape:
     * <pre>{ "inflight": true, "filename": "...", "sizeBytes": 1234567 }</pre>
     * or
     * <pre>{ "inflight": false, "filename": "..." }</pre>
     *
     * <p>{@code inflight=false} can mean either "the file finished and was
     * renamed" (success) or "no such recording exists" — the caller already
     * reloads the recordings list when the probe flips, so the success and
     * not-found branches converge in the UI.
     */
    private static void serveInflightStatus(OutputStream out, String filename) throws Exception {
        // Security: prevent path traversal
        if (filename == null || filename.isEmpty()
                || filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        File tmp = findInflightTmp(filename);
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            json.put("filename", filename);
            json.put("inflight", tmp != null);
            if (tmp != null) {
                json.put("sizeBytes", tmp.length());
            }
        } catch (Exception ignored) {}
        HttpResponse.sendJson(out, json.toString());
    }

    /**
     * Locate {@code <filename>.tmp} across all recording storage roots.
     * Returns null when no in-flight write is happening.
     */
    private static File findInflightTmp(String filename) {
        StorageManager sm = StorageManager.getInstance();
        String tmpName = filename + ".tmp";
        for (File dir : sm.getAllRecordingsDirs()) {
            File f = new File(dir, tmpName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllSurveillanceDirs()) {
            File f = new File(dir, tmpName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllProximityDirs()) {
            File f = new File(dir, tmpName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        return null;
    }

    /**
     * Find a video file across all storage locations.
     * Uses StorageManager to get all possible directories without hardcoding paths.
     */
    private static File findVideoFile(String filename) {
        return findVideoFile(filename, false);
    }

    /**
     * @param allowInFlightTmp when true, fall through to {@code <filename>.tmp}
     *        for files still being written by HardwareEventRecorderGpu. Useful
     *        for thumbnail generation (MediaMetadataRetriever reads frames
     *        without needing the moov atom). NOT safe for video streaming —
     *        a .tmp lacks the moov atom and the {@code <video>} element will
     *        fail to load it. Streaming MUST use the default false.
     */
    private static File findVideoFile(String filename, boolean allowInFlightTmp) {
        StorageManager sm = StorageManager.getInstance();

        // Search all recordings directories (active + alternate)
        for (File dir : sm.getAllRecordingsDirs()) {
            File f = new File(dir, filename);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }

        // Search all surveillance directories (active + alternate)
        for (File dir : sm.getAllSurveillanceDirs()) {
            File f = new File(dir, filename);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }

        // Search all proximity directories (active + alternate)
        for (File dir : sm.getAllProximityDirs()) {
            File f = new File(dir, filename);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }

        // In-flight fallback (thumbnails only): a notification fires the moment
        // startRecording() returns, but the file on disk is still
        // <name>.mp4.tmp until closeEventRecording() finishes (10-15s
        // post-record). Without this fallback, a tap within that window
        // fetches /thumb/<name> and gets 404, so the push notification banner
        // shows no hero image. We DON'T enable this for video streaming
        // because a .tmp lacks the moov atom.
        if (allowInFlightTmp) {
            String tmpName = filename + ".tmp";
            for (File dir : sm.getAllRecordingsDirs()) {
                File f = new File(dir, tmpName);
                if (f.exists() && f.canRead() && f.length() > 0) return f;
            }
            for (File dir : sm.getAllSurveillanceDirs()) {
                File f = new File(dir, tmpName);
                if (f.exists() && f.canRead() && f.length() > 0) return f;
            }
            for (File dir : sm.getAllProximityDirs()) {
                File f = new File(dir, tmpName);
                if (f.exists() && f.canRead() && f.length() > 0) return f;
            }
        }

        return null;
    }
    
    /**
     * Locate a JPEG sibling next to a recording. Used to serve hero / per-actor
     * thumbnails that ThumbnailBuffer writes alongside the MP4. Same security
     * + directory-search rules as findVideoFile.
     */
    private static File findSiblingJpeg(String jpegName) {
        if (jpegName == null || jpegName.isEmpty()) return null;
        if (jpegName.contains("..") || jpegName.contains("/")) return null;
        StorageManager sm = StorageManager.getInstance();
        for (File dir : sm.getAllRecordingsDirs()) {
            File f = new File(dir, jpegName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllSurveillanceDirs()) {
            File f = new File(dir, jpegName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        for (File dir : sm.getAllProximityDirs()) {
            File f = new File(dir, jpegName);
            if (f.exists() && f.canRead() && f.length() > 0) return f;
        }
        return null;
    }

    private static Set<String> splitCsvLower(String csv) {
        if (csv == null || csv.isEmpty()) return java.util.Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim().toLowerCase(Locale.US);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Set<String> splitCsvUpper(String csv) {
        if (csv == null || csv.isEmpty()) return java.util.Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim().toUpperCase(Locale.US);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }


    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;

        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                // URL-decode both halves so values with spaces or
                // unicode (e.g. place="Petaling Jaya" → "Petaling%20Jaya"
                // or "Cheras" → "Cheras") survive the round-trip.
                //
                // Important: java.net.URLDecoder.decode honours form-
                // urlencoded semantics where `+` decodes to space. Our
                // web client uses encodeURIComponent which emits `%20`
                // for spaces and passes literal `+` through unchanged.
                // Pre-escape `+` to `%2B` BEFORE decoding so a real-world
                // place name like "Marina Bay+" keeps its plus sign
                // instead of becoming "Marina Bay " (trailing space).
                // Pre-feature filters (class/severity/proximity) used a
                // fixed vocab without `+`; place names are user / OSM
                // strings and may contain it.
                //
                // Decode failure falls back to the raw value so a
                // malformed param can't break the request.
                String key = kv[0];
                String val = kv[1];
                try {
                    key = java.net.URLDecoder.decode(key.replace("+", "%2B"), "UTF-8");
                    val = java.net.URLDecoder.decode(val.replace("+", "%2B"), "UTF-8");
                } catch (Exception ignored) {}
                params.put(key, val);
            }
        }
        return params;
    }
    
    /**
     * List all recordings with optional filters and pagination.
     */
    private static void listRecordings(OutputStream out, String typeFilter, String dateFilter,
                                       int page, int pageSize) throws Exception {
        listRecordings(out, typeFilter, dateFilter, page, pageSize,
                null, null, null, null, null, null, null);
    }

    /**
     * SOTA: every list query is an indexed SQL seek + LIMIT/OFFSET against
     * the H2 recordings index. Replaces the prior O(N) directory walk +
     * O(N) JSON-sidecar parse + in-memory inverted index. With ~1000 clips
     * the per-request cost dropped from ~2 minutes to single-digit
     * milliseconds.
     *
     * <p>Warmup gating: while the index is populating after a fresh boot,
     * we surface {@code {warming: true, progress: {done, total}}} so the
     * UI can render a one-time "Building library index" skeleton instead
     * of a partial list. After warmup completes (one-shot per device
     * lifetime) all subsequent requests serve from the index.
     */
    private static void listRecordings(OutputStream out, String typeFilter, String dateFilter,
                                       int page, int pageSize,
                                       String classFilter, String severityFilter,
                                       String proximityFilter,
                                       String placeFilter,
                                       String placeContainsFilter,
                                       String countryFilter,
                                       String storageFilter) throws Exception {
        RecordingsIndex idx = RecordingsIndex.getInstance();

        // Index down (H2 closed the store and it could not be re-opened).
        // This MUST NOT be reported as an empty library: the clips are on
        // disk and an empty 200 response made events.html render "no
        // recordings" for hours while recordings kept being written. Report
        // the failure so clients show an error + retry instead.
        if (sendIndexUnavailable(out, "recordings", page, pageSize)) return;

        RecordingsIndex.WarmupSnapshot snap = idx.warmupState();
        if (!snap.complete && snap.total > 0) {
            // Warmup in flight — return progress so the UI shows the
            // skeleton. Empty recordings array preserves the response
            // shape; clients treat it as "no data yet, retry."
            JSONObject warming = new JSONObject();
            warming.put("success", true);
            warming.put("warming", true);
            JSONObject prog = new JSONObject();
            prog.put("done", snap.done);
            prog.put("total", snap.total);
            warming.put("progress", prog);
            warming.put("recordings", new JSONArray());
            warming.put("totalCount", 0);
            warming.put("totalPages", 1);
            warming.put("page", page);
            warming.put("pageSize", pageSize);
            HttpResponse.sendJson(out, warming.toString());
            return;
        }

        RecordingsIndex.Filter f = buildFilter(typeFilter, dateFilter,
                classFilter, severityFilter, proximityFilter, placeFilter,
                placeContainsFilter, countryFilter, storageFilter);

        int totalCount = idx.queryCount(f);
        int totalPages = (int) Math.ceil((double) totalCount / pageSize);
        if (totalPages == 0) totalPages = 1;
        page = Math.max(1, Math.min(page, totalPages));

        int offset = (page - 1) * pageSize;
        List<JSONObject> rows = idx.queryRecordings(f, pageSize, offset);

        // Repair-on-read: if a request expected at least one row but the
        // index is empty AND the filesystem actually has files, kick a
        // background reconcile — covers the case where a FileObserver
        // event was dropped on FUSE-mounted SD card. Cheap when in sync;
        // bounded by the fact that reconcile() itself is O(distinct
        // filenames) once.
        boolean reconcileKicked = false;
        if (rows.isEmpty() && totalCount == 0 && page == 1
                && hasAnyMp4OnDisk()) {
            reconcileKicked = kickBackgroundReconcile(idx);
        }

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("recordings", new JSONArray(rows));
        response.put("totalCount", totalCount);
        response.put("totalPages", totalPages);
        response.put("page", page);
        response.put("pageSize", pageSize);
        // Hint to the client that the index is being rebuilt RIGHT NOW
        // (storage hot-plug, fresh boot, type-switch). Clients that see
        // this should retry the same request after ~1.5s — by then the
        // reconcile thread has likely populated the missing rows. The
        // existing `warming` flag covers the cold-boot case; this covers
        // the runtime "index drifted vs disk" case.
        if (reconcileKicked) {
            response.put("reconciling", true);
            response.put("retryAfterMs", 1500);
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Build a {@link RecordingsIndex.Filter} from the legacy comma-separated
     * query strings. Mirrors the pre-existing splitCsv* helpers so the
     * client API doesn't change.
     */
    private static RecordingsIndex.Filter buildFilter(String typeFilter, String dateFilter,
                                                      String classFilter, String severityFilter,
                                                      String proximityFilter, String placeFilter,
                                                      String placeContainsFilter,
                                                      String countryFilter,
                                                      String storageFilter) {
        RecordingsIndex.Filter f = new RecordingsIndex.Filter();
        // Multi-type CSV is the native-fragment path (Dashcam segment
        // wants NORMAL + OEM_DASHCAM + PROXIMITY together). Single-type
        // (web events.html) stays as-is and auto-folds oemDashcam under
        // "normal" via RecordingsIndex.buildWhere.
        if (typeFilter != null && typeFilter.indexOf(',') >= 0) {
            f.types = splitCsvLower(typeFilter);
            // Caller might have meant "normal+anything" — keep auto-fold
            // by also adding oemDashcam when normal is present.
            if (f.types.contains("normal")) f.types.add("oemdashcam"); // intentional lowercase
            // Re-canonicalize: server stored types are camel-case
            // ("oemDashcam"). Map our lowercase tokens back.
            java.util.Set<String> canon = new java.util.HashSet<>();
            for (String t : f.types) {
                switch (t) {
                    case "normal": canon.add("normal"); break;
                    case "sentry": canon.add("sentry"); break;
                    case "proximity": canon.add("proximity"); break;
                    case "replay": canon.add("replay"); break;
                    case "oemdashcam":
                    case "oem_dashcam": canon.add("oemDashcam"); break;
                    default: canon.add(t); // unknown — pass through (logs as zero matches)
                }
            }
            f.types = canon;
        } else {
            f.type = (typeFilter != null && !typeFilter.isEmpty()) ? typeFilter : null;
        }
        f.date = (dateFilter != null && !dateFilter.isEmpty()) ? dateFilter : null;
        f.classes = splitCsvLower(classFilter);
        f.severities = splitCsvUpper(severityFilter);
        f.proximities = splitCsvUpper(proximityFilter);
        f.place = (placeFilter != null && !placeFilter.isEmpty())
                ? placeFilter.toLowerCase(Locale.US) : null;
        f.placeContains = (placeContainsFilter != null && !placeContainsFilter.isEmpty())
                ? placeContainsFilter.toLowerCase(Locale.US) : null;
        f.country = (countryFilter != null && !countryFilter.isEmpty())
                ? countryFilter.toLowerCase(Locale.US) : null;
        // Storage-volume filter: comma-separated INTERNAL / SD_CARD / USB,
        // upper-cased to match the stored column tokens. Empty/missing = all
        // volumes (the index already spans internal + SD + USB).
        f.storages = splitCsvUpper(storageFilter);
        return f;
    }

    /**
     * Cheap probe — used to decide whether an empty index merits a
     * reconcile kick. Returns at the first .mp4 found across any
     * recording dir; doesn't exhaustively walk.
     */
    private static boolean hasAnyMp4OnDisk() {
        StorageManager sm = StorageManager.getInstance();
        for (File dir : sm.getAllRecordingsDirs()) {
            if (dirHasMp4(dir)) return true;
        }
        for (File dir : sm.getAllSurveillanceDirs()) {
            if (dirHasMp4(dir)) return true;
        }
        for (File dir : sm.getAllProximityDirs()) {
            if (dirHasMp4(dir)) return true;
        }
        return false;
    }

    private static boolean dirHasMp4(File dir) {
        if (dir == null) return false;
        // Use StorageManager.listMp4Files for the FUSE shell-fallback —
        // SD-card listFiles() returns null under daemon UID 2000 even when
        // the dir is full. Without this, repair-on-read would never trigger
        // on the volume the user actually configured.
        File[] files = StorageManager.getInstance().listMp4Files(dir);
        return files != null && files.length > 0;
    }

    /**
     * @return true only when the coalesced repair request was accepted.
     */
    private static boolean kickBackgroundReconcile(RecordingsIndex idx) {
        return idx.requestReconcile("api-repair-on-read");
    }

    /**
     * Distinct places across the filtered set. Same filter inputs as
     * {@link #listRecordings} except the place filter itself — chips
     * are derived from "places that are reachable under the current
     * type/date/class/severity/proximity context," NOT from the
     * already-narrowed-by-place subset (that would always return only
     * the active chip).
     *
     * <p>Returns top {@link #PLACES_LIMIT} entries by count, alpha
     * tiebreak, with bucketed display label = canonical mixed-case
     * picked from the most recent clip in each bucket.
     */
    private static void listPlaces(OutputStream out, String typeFilter, String dateFilter,
                                   String classFilter, String severityFilter,
                                   String proximityFilter,
                                   String placeContainsFilter,
                                   String countryFilter,
                                   String storageFilter) throws Exception {
        // Indexed GROUP BY query — replaces the prior in-memory bucket
        // walk over the full filtered set. Same response shape, ~10-100x
        // faster on a 1000-clip library.
        if (sendIndexUnavailable(out, "places")) return;
        RecordingsIndex.Filter f = buildFilter(typeFilter, dateFilter,
                classFilter, severityFilter, proximityFilter,
                /* placeFilter */ null, placeContainsFilter, countryFilter, storageFilter);
        List<RecordingsIndex.PlaceBucket> buckets =
                RecordingsIndex.getInstance().queryPlaces(f, PLACES_LIMIT);

        JSONArray places = new JSONArray();
        for (RecordingsIndex.PlaceBucket b : buckets) {
            JSONObject row = new JSONObject();
            row.put("key", b.label.toLowerCase(Locale.US)); // matches /api/recordings?place=
            row.put("label", b.label);
            row.put("count", b.count);
            places.put(row);
        }

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("places", places);
        response.put("totalDistinct", places.length());
        HttpResponse.sendJson(out, response.toString());
    }

    /** Cap mirrored across native + web. */
    private static final int PLACES_LIMIT = 8;

    /**
     * Emit a 503 {@code indexUnavailable} response when the recordings index
     * is down, and report whether the caller should stop.
     *
     * <p>Every index-backed endpoint must go through this. When H2 closes the
     * store underneath the daemon, the query methods return empty
     * lists/zeroed aggregates — indistinguishable from a genuinely empty
     * library. Reporting that as {@code success:true} is what made the field
     * failure invisible: events.html rendered "no recordings" and the native
     * header rendered "0 clips" while the clips sat on disk, and the native
     * client's direct-FS fallback (which triggers on a null payload) was
     * skipped because the payload looked valid.
     *
     * @return true when the error response was sent and the caller must return.
     */
    private static boolean sendIndexUnavailable(OutputStream out, String surface)
            throws Exception {
        return sendIndexUnavailable(out, surface, 1, 0);
    }

    private static boolean sendIndexUnavailable(OutputStream out, String surface,
                                                int page, int pageSize) throws Exception {
        // Tri-state check (see RecordingsIndex.isUnavailableForClients):
        // reports true only when an empty answer would be a lie — the index
        // was up and died, or init() permanently failed. A cold boot (HTTP
        // server accepts requests before the index init thread runs) falls
        // through to the normal warming / repair-on-read response, because
        // 503-ing there would blank the storage card, calendar and place chips
        // on the first page load after every boot.
        if (!RecordingsIndex.getInstance().isUnavailableForClients()) return false;
        JSONObject err = new JSONObject();
        err.put("success", false);
        err.put("indexUnavailable", true);
        err.put("error", Messages.get("errors.recordings_index_unavailable"));
        err.put("retryAfterMs", 5000);
        // Preserve each surface's collection key so a client that reads only
        // the payload field degrades to "empty" rather than throwing on a
        // missing key. success=false is the authoritative signal.
        if ("places".equals(surface)) {
            err.put("places", new JSONArray());
            err.put("totalDistinct", 0);
        } else if ("dates".equals(surface)) {
            err.put("dates", new JSONArray());
        } else if ("recordings".equals(surface)) {
            err.put("recordings", new JSONArray());
            err.put("totalCount", 0);
            err.put("totalPages", 1);
            err.put("page", page);
            err.put("pageSize", pageSize);
        }
        HttpResponse.sendJson(out, 503, err.toString());
        return true;
    }

    
    


    
    /**
     * Get dates that have recordings (for calendar highlighting).
     */
    private static void getDatesWithRecordings(OutputStream out) throws Exception {
        // Indexed GROUP BY ymd — single SQL pass. Replaces the prior
        // multi-dir walk that re-stat'd every file across active +
        // mirror + legacy paths.
        if (sendIndexUnavailable(out, "dates")) return;
        List<RecordingsIndex.DateBucket> buckets =
                RecordingsIndex.getInstance().queryDates();

        JSONArray datesArray = new JSONArray();
        for (RecordingsIndex.DateBucket b : buckets) {
            JSONObject dateObj = new JSONObject();
            dateObj.put("date", b.date);
            dateObj.put("count", b.count);
            dateObj.put("hasSentry", b.hasSentry);
            datesArray.put(dateObj);
        }

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("dates", datesArray);
        HttpResponse.sendJson(out, response.toString());
    }
    
    
    /**
     * Storage stats backed by the RecordingsIndex aggregate query.
     * Replaces the prior multi-dir + multi-mirror walk that stat()'d every
     * file across SD/USB/internal mirrors via FUSE — cost dropped from
     * O(N files × dirs) FUSE round-trips to a single SQL aggregate.
     *
     * <p>Wire format is a strict superset of the legacy response: every
     * field that existed before is still emitted with the same name and
     * units, so events.js / recording.js / surveillance.js storage cards
     * keep working unchanged. The cleanups added are net-new fields
     * (totalBytes alias, structured per-type sub-objects).
     */
    private static void getStorageStats(OutputStream out) throws Exception {
        // Index down: emitting all-zero counters here would be reported as
        // authoritative ("0 clips, 0 B") by both the web storage card and the
        // native header — and the native client's direct-FS fallback ladder
        // only engages when fetchStats() returns null. Fail loudly instead.
        if (sendIndexUnavailable(out, "stats")) return;
        StorageManager storage = StorageManager.getInstance();
        RecordingsIndex.Stats s = RecordingsIndex.getInstance().queryStats();

        // Available space from the active recordings volume.
        File activeRecDir = storage.getRecordingsDir();
        long availableSpace = activeRecDir != null && activeRecDir.exists()
                ? activeRecDir.getFreeSpace() : 0;
        long totalSpace = activeRecDir != null && activeRecDir.exists()
                ? activeRecDir.getTotalSpace() : 0;

        JSONObject response = new JSONObject();
        response.put("success", true);

        // Legacy flat fields — preserved verbatim for client compat.
        response.put("normalCount", s.normalCount);
        response.put("normalSize", s.normalBytes);
        response.put("normalSizeFormatted", formatSize(s.normalBytes));
        response.put("sentryCount", s.sentryCount);
        response.put("sentrySize", s.sentryBytes);
        response.put("sentrySizeFormatted", formatSize(s.sentryBytes));
        response.put("proximityCount", s.proximityCount);
        response.put("proximitySize", s.proximityBytes);
        response.put("proximitySizeFormatted", formatSize(s.proximityBytes));
        response.put("replayCount", s.replayCount);
        response.put("replaySize", s.replayBytes);
        response.put("replaySizeFormatted", formatSize(s.replayBytes));
        response.put("totalCount", s.totalCount());
        response.put("totalSize", s.totalBytes());
        response.put("totalSizeFormatted", formatSize(s.totalBytes()));
        response.put("availableSpace", availableSpace);
        response.put("availableSpaceFormatted", formatSize(availableSpace));
        response.put("totalSpace", totalSpace);
        response.put("totalSpaceFormatted", formatSize(totalSpace));
        response.put("normalTodayCount", s.normalToday);
        response.put("sentryTodayCount", s.sentryToday);
        response.put("proximityTodayCount", s.proximityToday);
        response.put("replayTodayCount", s.replayToday);
        response.put("totalTodayCount", s.totalToday());

        // Storage limits.
        long recLimitMb = storage.getRecordingsLimitMb();
        long surLimitMb = storage.getSurveillanceLimitMb();
        response.put("recordingsLimitMb", recLimitMb);
        response.put("surveillanceLimitMb", surLimitMb);
        response.put("recordingsLimitBytes", recLimitMb * 1024L * 1024L);
        response.put("surveillanceLimitBytes", surLimitMb * 1024L * 1024L);
        // Replays live in the recordings dirs and are reaped under the same
        // 'recordings' category as cam_*/dvr_* (StorageManager auxiliary
        // prefixes), so they count toward the recordings limit here too.
        response.put("recordingsUsagePercent",
                recLimitMb > 0 ? Math.round((s.normalBytes + s.replayBytes) * 100.0
                        / (recLimitMb * 1024L * 1024L)) : 0);
        response.put("surveillanceUsagePercent",
                surLimitMb > 0 ? Math.round(s.sentryBytes * 100.0 / (surLimitMb * 1024L * 1024L)) : 0);
        response.put("recordingsPath", getRecordingsDir());
        response.put("surveillancePath", getSentryDir());

        // Modernized per-type sub-objects — same data, cleaner shape for
        // future clients. Existing clients ignore these.
        JSONObject byType = new JSONObject();
        byType.put("normal", typeBlock(s.normalCount, s.normalBytes, s.normalToday));
        byType.put("sentry", typeBlock(s.sentryCount, s.sentryBytes, s.sentryToday));
        byType.put("proximity", typeBlock(s.proximityCount, s.proximityBytes, s.proximityToday));
        byType.put("replay", typeBlock(s.replayCount, s.replayBytes, s.replayToday));
        response.put("byType", byType);

        // Index health surface — clients can detect a still-warming index.
        RecordingsIndex.WarmupSnapshot snap = RecordingsIndex.getInstance().warmupState();
        if (!snap.complete && snap.total > 0) {
            JSONObject warm = new JSONObject();
            warm.put("warming", true);
            warm.put("done", snap.done);
            warm.put("total", snap.total);
            response.put("indexState", warm);
        }
        // Field diagnostics: nonzero means H2 closed the store under the
        // daemon at least once and it was re-opened. Cheap to surface and it
        // is the signal that would have made the original "recordings missing
        // from events.html" report diagnosable without a full log pull.
        int reconnects = RecordingsIndex.getInstance().reconnectCount();
        if (reconnects > 0) response.put("indexReconnects", reconnects);

        HttpResponse.sendJson(out, response.toString());
    }

    private static JSONObject typeBlock(long count, long bytes, long today) throws Exception {
        JSONObject o = new JSONObject();
        o.put("count", count);
        o.put("bytes", bytes);
        o.put("bytesFormatted", formatSize(bytes));
        o.put("todayCount", today);
        return o;
    }
    
    
    /**
     * Stream video file with optional Range support and ETag-based caching.
     *
     * Finalized event recordings are immutable (the daemon writes to
     * <name>.mp4.tmp and atomically renames once the file is closed), so we
     * emit a strong ETag derived from length+mtime and a 24h max-age so the
     * WebView's HTTP cache can serve repeat playback locally instead of
     * re-streaming from the daemon. Cache headers are added in
     * HttpResponse.sendVideo / sendVideoRange.
     */
    private static void streamVideo(OutputStream out, String filename, String rangeHeader,
                                    String ifNoneMatchHeader) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }

        // Use shared findVideoFile which checks ALL storage locations
        File file = findVideoFile(filename);

        if (file == null) {
            HttpResponse.sendError(out, 404, Messages.get("errors.recordings_not_found_with_filename", filename));
            return;
        }

        streamVideoFile(out, file, rangeHeader, ifNoneMatchHeader);
    }

    private static void streamVideoById(OutputStream out, String recordingId,
                                        String rangeHeader, String ifNoneMatchHeader) throws Exception {
        if (!validRecordingId(recordingId)) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        RecordingsIndex.RecordingRef ref =
                RecordingsIndex.getInstance().resolveById(recordingId);
        if (ref == null) {
            HttpResponse.sendError(out, 404, Messages.get("errors.recordings_not_found"));
            return;
        }
        File file = ref.file();
        if (!file.isFile() || !file.canRead()) {
            HttpResponse.sendError(out, 410, Messages.get("errors.recordings_file_no_longer_accessible"));
            return;
        }
        streamVideoFile(out, file, rangeHeader, ifNoneMatchHeader);
    }

    private static void streamVideoFile(OutputStream out, File file, String rangeHeader,
                                        String ifNoneMatchHeader) throws Exception {
        // Conditional GET: if the client's cached copy matches our ETag,
        // skip re-streaming. Tag is "<length>-<mtime>" so any append/replace
        // invalidates without us needing a content hash.
        String etag = buildVideoEtag(file);
        if (ifNoneMatchHeader != null && etagMatches(ifNoneMatchHeader, etag)) {
            HttpResponse.sendNotModified(out, etag);
            return;
        }

        // Handle Range request for video seeking
        try {
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String rangeSpec = rangeHeader.substring(6);
                String[] parts = rangeSpec.split("-");
                long start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
                long end = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : -1;

                // Validate range
                long fileLength = file.length();
                if (start < 0 || start >= fileLength) {
                    HttpResponse.sendError(out, 416, Messages.get("errors.recordings_range_not_satisfiable"));
                    return;
                }

                HttpResponse.sendVideoRange(out, file, start, end, etag);
            } else {
                HttpResponse.sendVideo(out, file, etag);
            }
        } catch (NumberFormatException e) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_range_header"));
        } catch (java.io.FileNotFoundException e) {
            // File disappeared between check and read (SD card unmount)
            HttpResponse.sendError(out, 410, Messages.get("errors.recordings_file_no_longer_accessible"));
        }
    }

    /**
     * Build a strong ETag for a video file from its size and mtime. Anything
     * that mutates the file (replacement, append, ext-storage rotation)
     * changes at least one of these, invalidating the client's cache.
     */
    private static String buildVideoEtag(File file) {
        return "\"" + file.length() + "-" + file.lastModified() + "\"";
    }

    /**
     * Check whether the client's If-None-Match header matches our ETag.
     * Tolerates the wildcard form, weak prefix ("W/"), and comma-separated
     * lists per RFC 7232 §3.2.
     */
    private static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || etag == null) return false;
        if ("*".equals(ifNoneMatch.trim())) return true;
        for (String token : ifNoneMatch.split(",")) {
            String t = token.trim();
            if (t.startsWith("W/")) t = t.substring(2);
            if (t.equals(etag)) return true;
        }
        return false;
    }

    /**
     * Delete a recording.
     */
    private static void deleteRecording(OutputStream out, String filename) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendJsonError(out, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        
        // Use shared findVideoFile which checks ALL storage locations
        File file = findVideoFile(filename);
        
        if (file == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.recordings_not_found"));
            return;
        }
        
        boolean deleted = file.delete();
        if (deleted) {
            deleteSidecars(file, filename);
        }

        JSONObject response = new JSONObject();
        response.put("success", deleted);
        if (!deleted) {
            response.put("error", Messages.get("errors.recordings_delete_failed"));
        }

        HttpResponse.sendJson(out, response.toString());
    }

    private static void deleteRecordingById(OutputStream out, String recordingId) throws Exception {
        if (!validRecordingId(recordingId)) {
            HttpResponse.sendJsonError(out, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        RecordingsIndex.RecordingRef ref =
            RecordingsIndex.getInstance().resolveById(recordingId);
        if (ref == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.recordings_not_found"));
            return;
        }
        File file = ref.file();
        boolean deleted = file.delete();
        if (deleted) {
            deleteSidecars(file, ref.filename);
        }
        JSONObject response = new JSONObject();
        response.put("success", deleted);
        if (!deleted) response.put("error", Messages.get("errors.recordings_delete_failed"));
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Sweep the .mp4's sidecar files: JSON event timeline, cached thumb,
     * v3 hero JPEG, per-actor thumbs ({@code thumb_<base>_a*.jpg}).
     *
     * Mirrors RecordingScanner.deleteRecording on the Android side — without
     * this sweep, web-UI deletes leak hero/per-actor JPEGs into the storage
     * directory until disk fills (the loop-rotation cleanup also doesn't see
     * them because it only iterates .mp4 files).
     */
    /**
     * Public wrapper so the surveillance engine (different package) can delete an
     * event's sidecars when discarding a confirmed-empty false-positive recording.
     * Also removes the {@code .srt} subtitle sibling, which the private
     * {@link #deleteSidecars} historically did not cover.
     */
    public static void deleteEventSidecars(File mp4File, String filename) {
        if (mp4File == null || filename == null) return;
        // deleteSidecars covers .json/.srt/.jpg/per-actor thumbs AND ends by
        // invalidating the storage size/count cache. The separate .srt block that
        // used to live here was redundant (deleteSidecars deletes the same path)
        // and, once the invalidation moved into deleteSidecars, it also ran too
        // late to be counted — so it is gone rather than reordered.
        deleteSidecars(mp4File, filename);
    }

    private static void deleteSidecars(File mp4File, String filename) {
        // This helper is the single index-removal owner. It delegates to
        // RecordingsIndex.remove(filename), so a second explicit remove here
        // would repeat the synchronized DELETE and advance the tombstone
        // sequence twice for every file deletion.
        invalidateRecordingCache(mp4File.getAbsolutePath());

        // Sidecar stem: strip a TRAILING ".mp4" only.
        //
        // These three used filename.replace(".mp4", "<ext>"), which is wrong in two
        // ways and silently leaked sidecars: replace() rewrites EVERY occurrence,
        // so "event_a.mp4_2.mp4" produced "event_a.json_2.json" instead of
        // "event_a.mp4_2.json"; and for a name without a trailing ".mp4" it
        // returned the name unchanged, so the code stat'ed the video path itself
        // and never touched the real sidecar. SrtWriter/LocationSidecarWriter build
        // these names by stripping at the LAST dot, so this now matches the writers.
        String stem = filename.endsWith(".mp4")
                ? filename.substring(0, filename.length() - ".mp4".length())
                : filename;
        File sidecarDir = mp4File.getParentFile();

        // JSON event timeline
        File jsonFile = new File(sidecarDir, stem + ".json");
        if (jsonFile.exists()) jsonFile.delete();

        // SRT subtitle sidecar (parity — future callers shouldn't re-hit the gap).
        File srtFile = new File(sidecarDir, stem + ".srt");
        if (srtFile.exists()) srtFile.delete();

        // Cached thumbnail
        File thumbFile = new File(getThumbnailCacheDir(), stem + ".jpg");
        if (thumbFile.exists()) thumbFile.delete();
        File idThumbFile = new File(getThumbnailCacheDir(),
            RecordingIdentity.fromPath(mp4File.getAbsolutePath()).recordingId + ".jpg");
        if (idThumbFile.exists()) idThumbFile.delete();

        // v3 hero JPEG sibling: <base>.jpg next to the mp4.
        //
        // This used to `return` when the parent wasn't readable, which skipped the
        // cache invalidation at the tail — even though the .mp4/.json/.srt are
        // already deleted by this point, so their bytes stayed in the reported
        // size. The bail is reachable in practice: it is exactly the FUSE-mounted
        // SD/USB case under daemon UID 2000 that the per-actor lister below exists
        // to work around. Scoped as an if-block so the tail always runs.
        File parent = mp4File.getParentFile();
        if (parent != null && parent.canRead()) {
            String base = filename.endsWith(".mp4")
                    ? filename.substring(0, filename.length() - 4)
                    : filename;
            File heroSibling = new File(parent, base + ".jpg");
            if (heroSibling.exists()) heroSibling.delete();

            // Per-actor thumbs: thumb_<base>_a<id>(_<rel>).jpg
            // Anchor with "_a" so a sibling segment named "<base>_2.mp4" with
            // its own thumbs at "thumb_<base>_2_a*.jpg" is NOT swept when we
            // delete <base>.mp4 — the underscore-after-_2_ is followed by 'a'
            // for actor thumbs, but "_2_" itself is followed by an actor digit
            // that the original prefix-only check would catch incorrectly.
            final String perActorPrefix = "thumb_" + base + "_a";
            // Route through StorageManager's FUSE-aware lister so SD-card +
            // USB cleanup doesn't silently leak per-actor thumbs when
            // listFiles() returns null on those FUSE mounts.
            File[] perActor = StorageManager.getInstance()
                    .listFilesWithFallback(parent, perActorPrefix, ".jpg");
            for (File f : perActor) f.delete();
        }

        // The mp4 + every sidecar counted toward the category's reported size is
        // now gone. The storage card's size/count cache is served
        // stale-while-revalidate, so without this the freed space isn't reflected
        // until the TTL lapses — a user who just deleted clips would still see
        // the old usage. Common tail of both deleteRecording and
        // batchDeleteRecordings, so one call covers both. Category is derived
        // from the filename prefix (event_* = surveillance, proximity_* =
        // proximity, cam*/dvr_/replay_ = recordings); null would clear all
        // categories, which is also correct but does more work than needed.
        try {
            StorageManager.getInstance()
                    .invalidateCategorySizeCache(categoryForFilename(filename));
        } catch (Throwable ignored) {
            // Never let cache bookkeeping fail a delete that already succeeded.
        }
    }

    /** Map a recording filename to the StorageManager category whose reported
     *  size/count includes it. Returns null (= all categories) when unknown, so
     *  an unrecognised name errs toward a correct-but-broader invalidation. */
    private static String categoryForFilename(String filename) {
        if (filename == null) return null;
        if (filename.startsWith("event_")) return "surveillance";
        if (filename.startsWith("proximity_")) return "proximity";
        if (filename.startsWith("cam") || filename.startsWith("dvr_")
                || filename.startsWith("replay_")) return "recordings";
        return null;
    }
    
    /**
     * Batch delete multiple recordings at once.
     * Accepts JSON body: { "filenames": ["file1.mp4", "file2.mp4", ...] }
     * Returns: { "success": true, "deleted": N, "failed": N, "errors": [...] }
     */
    private static void batchDeleteRecordings(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        
        if (body == null || body.isEmpty()) {
            response.put("success", false);
            response.put("error", Messages.get("errors.recordings_body_required"));
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        
        try {
            JSONObject request = new JSONObject(body);
                JSONArray ids = request.optJSONArray("ids");
                JSONArray filenames = request.optJSONArray("filenames");
            
                if ((ids == null || ids.length() == 0)
                    && (filenames == null || filenames.length() == 0)) {
                response.put("success", false);
                response.put("error", Messages.get("errors.recordings_no_filenames"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            
            // Limit batch size to prevent abuse
            int maxBatch = 100;
            int idCount = ids != null ? ids.length() : 0;
            int filenameCount = filenames != null ? filenames.length() : 0;
            int batchSize = idCount + filenameCount;
            if (batchSize > maxBatch) {
                response.put("success", false);
                response.put("error", Messages.get("errors.recordings_max_batch_with_count", maxBatch));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            
            int deleted = 0;
            int failed = 0;
            JSONArray errors = new JSONArray();
            
            for (int i = 0; i < batchSize; i++) {
                String recordingId = i < idCount ? ids.getString(i) : null;
                String filename = recordingId == null ? filenames.getString(i - idCount) : null;
                
                // Security: prevent path traversal
                if ((recordingId != null && !validRecordingId(recordingId))
                        || (filename != null && (filename.contains("..") || filename.contains("/")))) {
                    failed++;
                    errors.put((recordingId != null ? recordingId : filename) + ": invalid identity");
                    continue;
                }
                
                RecordingsIndex.RecordingRef ref = recordingId != null
                        ? RecordingsIndex.getInstance().resolveById(recordingId) : null;
                File file = recordingId != null
                    ? (ref != null ? ref.file() : null)
                    : findVideoFile(filename);
                String resolvedFilename = ref != null ? ref.filename : filename;
                if (file == null) {
                    failed++;
                    errors.put((recordingId != null ? recordingId : filename) + ": not found");
                    continue;
                }
                
                boolean success = file.delete();
                if (success) {
                    deleted++;
                    deleteSidecars(file, resolvedFilename);
                } else {
                    failed++;
                    errors.put((recordingId != null ? recordingId : filename) + ": delete failed");
                }
            }
            
            response.put("success", true);
            response.put("deleted", deleted);
            response.put("failed", failed);
            if (errors.length() > 0) {
                response.put("errors", errors);
            }
            
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", Messages.get("errors.invalid_request_with_detail", e.getMessage()));
        }
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * SOTA: Serve event timeline JSON for a recording.
     * Returns the JSON sidecar if it exists, or an empty events array for backward compatibility.
     */
    private static void serveEventTimeline(OutputStream out, String filename) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        
        // Replace only the trailing extension. Segmented/custom names may
        // legitimately contain ".mp4" earlier in the stem.
        String jsonFilename = filename.endsWith(".mp4")
            ? filename.substring(0, filename.length() - ".mp4".length()) + ".json"
            : filename + ".json";
        
        // Search for the JSON sidecar in all storage locations
        File jsonFile = findJsonSidecar(jsonFilename);
        
        if (jsonFile != null && jsonFile.exists()) {
            // Serve the actual event data
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(jsonFile));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                HttpResponse.sendJson(out, sb.toString());
            } catch (Exception e) {
                // File exists but can't be read — return empty
                sendEmptyTimeline(out);
            }
        } else {
            // Backward compatible: no sidecar = empty events array
            sendEmptyTimeline(out);
        }
    }

    private static void serveEventTimelineById(OutputStream out, String recordingId) throws Exception {
        if (!validRecordingId(recordingId)) {
            HttpResponse.sendError(out, 400, Messages.get("errors.recordings_invalid_filename"));
            return;
        }
        RecordingsIndex.RecordingRef ref =
                RecordingsIndex.getInstance().resolveById(recordingId);
        if (ref == null) {
            HttpResponse.sendError(out, 404, Messages.get("errors.recordings_not_found"));
            return;
        }
        String name = ref.filename;
        String stem = name.endsWith(".mp4")
                ? name.substring(0, name.length() - ".mp4".length()) : name;
        File jsonFile = new File(ref.file().getParentFile(), stem + ".json");
        serveTimelineFile(out, jsonFile);
    }

    private static void serveTimelineFile(OutputStream out, File jsonFile) throws Exception {
        if (jsonFile != null && jsonFile.exists()) {
            try (java.io.BufferedReader reader =
                         new java.io.BufferedReader(new java.io.FileReader(jsonFile))) {
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
                HttpResponse.sendJson(out, json.toString());
                return;
            } catch (Exception ignored) {
                // Fall through to the backward-compatible empty timeline.
            }
        }
        sendEmptyTimeline(out);
    }
    
    /**
     * Send an empty timeline response (backward compatibility for videos without sidecars).
     */
    private static void sendEmptyTimeline(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("version", 1);
        response.put("events", new JSONArray());
        response.put("durationMs", 0);
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Find a JSON sidecar file across all storage locations.
     * Uses StorageManager to get all possible directories without hardcoding paths.
     */
    private static File findJsonSidecar(String jsonFilename) {
        StorageManager sm = StorageManager.getInstance();
        
        // Check all surveillance directories
        for (File dir : sm.getAllSurveillanceDirs()) {
            File f = new File(dir, jsonFilename);
            if (f.exists()) return f;
        }
        
        // Check all recordings directories
        for (File dir : sm.getAllRecordingsDirs()) {
            File f = new File(dir, jsonFilename);
            if (f.exists()) return f;
        }
        
        // Check all proximity directories
        for (File dir : sm.getAllProximityDirs()) {
            File f = new File(dir, jsonFilename);
            if (f.exists()) return f;
        }
        
        return null;
    }
    
    private static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        } else if (bytes >= 1_000_000) {
            return String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0);
        } else if (bytes >= 1_000) {
            return String.format(Locale.US, "%.1f KB", bytes / 1_000.0);
        }
        return bytes + " B";
    }
}
