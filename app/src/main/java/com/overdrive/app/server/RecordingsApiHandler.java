package com.overdrive.app.server;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.storage.StorageManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    
    // Legacy paths for backward compatibility (migration)
    private static final String LEGACY_RECORDINGS_DIR = "/storage/emulated/0/Android/data/com.overdrive.app/files";
    private static final String LEGACY_SENTRY_DIR = LEGACY_RECORDINGS_DIR + "/sentry_events";
    
    // Filename patterns (support optional _N segment suffix for multi-segment recordings)
    private static final Pattern CAM_PATTERN = Pattern.compile("cam(\\d+)?_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern EVENT_PATTERN = Pattern.compile("event_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final Pattern PROXIMITY_PATTERN = Pattern.compile("proximity_(\\d{8})_(\\d{6})(?:_\\d+)?\\.mp4");
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    
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
            // Clamp pageSize to reasonable limits
            pageSize = Math.max(1, Math.min(pageSize, 50));
            listRecordings(out, type, date, page, pageSize);
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
        
        // Serve thumbnail
        if (path.startsWith("/thumb/")) {
            String filename = path.substring(7);
            serveThumbnail(out, filename);
            return true;
        }
        
        // Stream video file
        if (path.startsWith("/video/")) {
            String filename = path.substring(7);
            streamVideo(out, filename, null);
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
     * Handle with Range header support for video seeking.
     */
    public static boolean handleWithRange(String method, String path, String rangeHeader, OutputStream out) throws Exception {
        if (path.startsWith("/video/")) {
            String filename = path.substring(7);
            streamVideo(out, filename, rangeHeader);
            return true;
        }
        return handle(method, path, null, out);
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
            HttpResponse.sendError(out, 400, "Invalid filename");
            return;
        }
        
        // Check cache first
        File cacheDir = new File(getThumbnailCacheDir());
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        
        String thumbName = filename.replace(".mp4", ".jpg");
        File thumbFile = new File(cacheDir, thumbName);
        
        // If cached thumbnail exists and is valid, serve it immediately
        if (thumbFile.exists() && thumbFile.length() > 0) {
            HttpResponse.sendImage(out, thumbFile, "image/jpeg");
            return;
        }
        
        // Find the source video file
        File videoFile = findVideoFile(filename);
        if (videoFile == null) {
            HttpResponse.sendError(out, 404, "Video not found: " + filename);
            return;
        }
        
        // Queue background generation if not already pending
        if (!pendingThumbs.contains(filename)) {
            pendingThumbs.add(filename);
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
        String headers = "HTTP/1.1 202 Accepted\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Retry-After: 1\r\n" +
                        "Connection: close\r\n\r\n";
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
        try {
            retriever.setDataSource(videoFile.getAbsolutePath());
            
            // Get frame at 1 second (1000000 microseconds)
            Bitmap frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                // Try frame at 0 if 1 second fails
                frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            
            if (frame == null) {
                return null;
            }
            
            // Scale down to thumbnail size (160x90 for 16:9 aspect)
            int targetWidth = 160;
            int targetHeight = 90;
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
     * Find a video file across all storage locations.
     * Uses StorageManager to get all possible directories without hardcoding paths.
     */
    private static File findVideoFile(String filename) {
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
        
        // Check legacy recordings location
        File legacyFile = new File(LEGACY_RECORDINGS_DIR, filename);
        if (legacyFile.exists() && legacyFile.canRead() && legacyFile.length() > 0) return legacyFile;
        
        // Check legacy sentry location
        File legacySentryFile = new File(LEGACY_SENTRY_DIR, filename);
        if (legacySentryFile.exists() && legacySentryFile.canRead() && legacySentryFile.length() > 0) return legacySentryFile;
        
        return null;
    }
    
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }
    
    /**
     * List all recordings with optional filters and pagination.
     */
    private static void listRecordings(OutputStream out, String typeFilter, String dateFilter, 
                                       int page, int pageSize) throws Exception {
        List<JSONObject> recordings = new ArrayList<>();
        StorageManager sm = StorageManager.getInstance();
        
        // Scan normal recordings from ALL locations (active + alternate + legacy)
        if (typeFilter == null || typeFilter.equals("normal")) {
            for (File dir : sm.getAllRecordingsDirs()) {
                scanDirectory(dir, "normal", recordings, dateFilter);
            }
            // Also scan legacy location for backward compatibility
            File legacyDir = new File(LEGACY_RECORDINGS_DIR);
            if (legacyDir.exists()) {
                scanDirectory(legacyDir, "normal", recordings, dateFilter);
            }
        }
        
        // Scan sentry events from ALL locations (active + alternate + legacy)
        if (typeFilter == null || typeFilter.equals("sentry")) {
            for (File dir : sm.getAllSurveillanceDirs()) {
                scanDirectory(dir, "sentry", recordings, dateFilter);
            }
            // Also scan legacy location for backward compatibility
            File legacySentryDir = new File(LEGACY_SENTRY_DIR);
            if (legacySentryDir.exists()) {
                scanDirectory(legacySentryDir, "sentry", recordings, dateFilter);
            }
        }
        
        // Scan proximity events from ALL locations (active + alternate)
        if (typeFilter == null || typeFilter.equals("proximity")) {
            for (File dir : sm.getAllProximityDirs()) {
                scanDirectory(dir, "proximity", recordings, dateFilter);
            }
        }
        
        // Sort by timestamp descending (newest first)
        recordings.sort((a, b) -> Long.compare(
            b.optLong("timestamp", 0), 
            a.optLong("timestamp", 0)
        ));
        
        // Deduplicate by filename — same file may appear from multiple scan locations
        // (e.g., SD card + internal storage fallback). Keep the first occurrence (largest/newest).
        Set<String> seenFilenames = new HashSet<>();
        recordings.removeIf(rec -> {
            String name = rec.optString("filename", "");
            if (seenFilenames.contains(name)) return true;
            seenFilenames.add(name);
            return false;
        });
        
        // Pagination
        int totalCount = recordings.size();
        int totalPages = (int) Math.ceil((double) totalCount / pageSize);
        if (totalPages == 0) totalPages = 1;
        
        // Clamp page to valid range
        page = Math.max(1, Math.min(page, totalPages));
        
        int startIndex = (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, totalCount);
        
        List<JSONObject> pageRecordings = startIndex < totalCount 
            ? recordings.subList(startIndex, endIndex) 
            : new ArrayList<>();
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("recordings", new JSONArray(pageRecordings));
        response.put("totalCount", totalCount);
        response.put("totalPages", totalPages);
        response.put("page", page);
        response.put("pageSize", pageSize);
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void scanDirectory(File dir, String type, List<JSONObject> recordings, String dateFilter) {
        if (!dir.exists() || !dir.isDirectory()) return;
        
        // Verify directory is actually readable (catches unmounted SD card ghost paths)
        if (!dir.canRead()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null) return;
        
        long filterStart = 0, filterEnd = 0;
        if (dateFilter != null && !dateFilter.isEmpty()) {
            try {
                // Parse date filter (YYYY-MM-DD format)
                String[] parts = dateFilter.split("-");
                Calendar cal = Calendar.getInstance();
                cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]), 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                filterStart = cal.getTimeInMillis();
                cal.add(Calendar.DAY_OF_MONTH, 1);
                filterEnd = cal.getTimeInMillis();
            } catch (Exception e) {
                CameraDaemon.log("Invalid date filter: " + dateFilter);
            }
        }
        
        for (File file : files) {
            // Skip ghost files: must be readable and have actual content
            // On BYD, unmounted SD card can leave stale directory entries with 0-byte ghosts
            if (!file.canRead() || file.length() <= 0) continue;
            
            JSONObject recording = parseRecording(file, type);
            if (recording != null) {
                // Apply date filter if specified
                if (filterStart > 0) {
                    long ts = recording.optLong("timestamp", 0);
                    if (ts < filterStart || ts >= filterEnd) continue;
                }
                recordings.add(recording);
            }
        }
    }
    
    private static JSONObject parseRecording(File file, String type) {
        try {
            String name = file.getName();
            long timestamp;
            int cameraId = 0;
            
            if (type.equals("sentry")) {
                Matcher m = EVENT_PATTERN.matcher(name);
                if (!m.matches()) return null;
                String dateStr = m.group(1) + "_" + m.group(2);
                timestamp = DATE_FORMAT.parse(dateStr).getTime();
            } else if (type.equals("proximity")) {
                Matcher m = PROXIMITY_PATTERN.matcher(name);
                if (!m.matches()) return null;
                String dateStr = m.group(1) + "_" + m.group(2);
                timestamp = DATE_FORMAT.parse(dateStr).getTime();
            } else {
                Matcher m = CAM_PATTERN.matcher(name);
                if (!m.matches()) return null;
                String camStr = m.group(1);
                cameraId = camStr != null ? Integer.parseInt(camStr) : 0;
                String dateStr = m.group(2) + "_" + m.group(3);
                timestamp = DATE_FORMAT.parse(dateStr).getTime();
            }
            
            JSONObject rec = new JSONObject();
            rec.put("filename", name);
            rec.put("path", file.getAbsolutePath());
            rec.put("type", type);
            rec.put("cameraId", cameraId);
            rec.put("timestamp", timestamp);
            rec.put("size", file.length());
            rec.put("sizeFormatted", formatSize(file.length()));
            
            // Format date/time for display
            Date date = new Date(timestamp);
            rec.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date));
            rec.put("time", new SimpleDateFormat("HH:mm:ss", Locale.US).format(date));
            rec.put("dateFormatted", new SimpleDateFormat("MMM dd, yyyy", Locale.US).format(date));
            rec.put("timeFormatted", new SimpleDateFormat("h:mm a", Locale.US).format(date));
            
            // Video URL for playback
            rec.put("videoUrl", "/video/" + name);
            
            // Thumbnail URL - server generates thumbnail from video
            rec.put("thumbnailUrl", "/thumb/" + name);
            
            return rec;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Get dates that have recordings (for calendar highlighting).
     */
    private static void getDatesWithRecordings(OutputStream out) throws Exception {
        Set<String> dates = new HashSet<>();
        Map<String, Integer> countByDate = new HashMap<>();
        Map<String, Boolean> hasSentryByDate = new HashMap<>();
        StorageManager sm = StorageManager.getInstance();
        
        // Scan normal recordings from ALL locations (active + alternate + legacy)
        for (File dir : sm.getAllRecordingsDirs()) {
            scanDatesInDirectory(dir, false, dates, countByDate, hasSentryByDate);
        }
        File legacyDir = new File(LEGACY_RECORDINGS_DIR);
        if (legacyDir.exists()) {
            scanDatesInDirectory(legacyDir, false, dates, countByDate, hasSentryByDate);
        }
        
        // Scan sentry events from ALL locations (active + alternate + legacy)
        for (File dir : sm.getAllSurveillanceDirs()) {
            scanDatesInDirectory(dir, true, dates, countByDate, hasSentryByDate);
        }
        File legacySentryDir = new File(LEGACY_SENTRY_DIR);
        if (legacySentryDir.exists()) {
            scanDatesInDirectory(legacySentryDir, true, dates, countByDate, hasSentryByDate);
        }
        
        // Scan proximity events from ALL locations (active + alternate)
        for (File dir : sm.getAllProximityDirs()) {
            scanDatesInDirectory(dir, false, dates, countByDate, hasSentryByDate);
        }
        
        JSONArray datesArray = new JSONArray();
        for (String date : dates) {
            JSONObject dateObj = new JSONObject();
            dateObj.put("date", date);
            dateObj.put("count", countByDate.getOrDefault(date, 0));
            dateObj.put("hasSentry", hasSentryByDate.getOrDefault(date, false));
            datesArray.put(dateObj);
        }
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("dates", datesArray);
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    private static void scanDatesInDirectory(File dir, boolean isSentry, Set<String> dates, 
            Map<String, Integer> countByDate, Map<String, Boolean> hasSentryByDate) {
        if (!dir.exists() || !dir.isDirectory() || !dir.canRead()) return;
        
        File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
        if (files == null) return;
        
        for (File file : files) {
            // Skip ghost files from unmounted SD card
            if (!file.canRead() || file.length() <= 0) continue;
            
            String name = file.getName();
            String dateStr = null;
            boolean isSentryFile = false;
            
            // Try all patterns to extract date — handles mixed directories
            Matcher eventMatcher = EVENT_PATTERN.matcher(name);
            Matcher camMatcher = CAM_PATTERN.matcher(name);
            Matcher proxMatcher = PROXIMITY_PATTERN.matcher(name);
            
            if (eventMatcher.matches()) {
                dateStr = eventMatcher.group(1);
                isSentryFile = true;
            } else if (camMatcher.matches()) {
                dateStr = camMatcher.group(2);
            } else if (proxMatcher.matches()) {
                dateStr = proxMatcher.group(1);
            }
            
            if (dateStr != null && dateStr.length() == 8) {
                String formattedDate = dateStr.substring(0, 4) + "-" + 
                                       dateStr.substring(4, 6) + "-" + 
                                       dateStr.substring(6, 8);
                dates.add(formattedDate);
                countByDate.merge(formattedDate, 1, Integer::sum);
                if (isSentryFile) {
                    hasSentryByDate.put(formattedDate, true);
                }
            }
        }
    }
    
    /**
     * Get storage statistics.
     * Scans ALL locations (active + alternate) via StorageManager, deduplicating by filename.
     */
    private static void getStorageStats(OutputStream out) throws Exception {
        StorageManager storage = StorageManager.getInstance();
        
        long normalSize = 0, normalCount = 0;
        long sentrySize = 0, sentryCount = 0;
        long proximitySize = 0, proximityCount = 0;
        
        long normalTodayCount = 0;
        long sentryTodayCount = 0;
        long proximityTodayCount = 0;
        
        String todayStr = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        
        // Track seen filenames to avoid double-counting files that exist in both locations
        Set<String> seenNormal = new HashSet<>();
        Set<String> seenSentry = new HashSet<>();
        Set<String> seenProximity = new HashSet<>();
        
        // Normal recordings from ALL locations
        for (File dir : storage.getAllRecordingsDirs()) {
            if (!dir.exists() || !dir.canRead()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files == null) continue;
            for (File f : files) {
                if (!f.canRead() || f.length() <= 0) continue;
                if (seenNormal.contains(f.getName())) continue;
                seenNormal.add(f.getName());
                normalSize += f.length();
                normalCount++;
                if (isFileFromToday(f.getName(), todayStr, CAM_PATTERN, 2)) {
                    normalTodayCount++;
                }
            }
        }
        // Legacy location
        File legacyDir = new File(LEGACY_RECORDINGS_DIR);
        if (legacyDir.exists() && legacyDir.canRead()) {
            File[] files = legacyDir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files != null) {
                for (File f : files) {
                    if (!f.canRead() || f.length() <= 0) continue;
                    if (seenNormal.contains(f.getName())) continue;
                    seenNormal.add(f.getName());
                    normalSize += f.length();
                    normalCount++;
                    if (isFileFromToday(f.getName(), todayStr, CAM_PATTERN, 2)) {
                        normalTodayCount++;
                    }
                }
            }
        }
        
        // Sentry events from ALL locations
        for (File dir : storage.getAllSurveillanceDirs()) {
            if (!dir.exists() || !dir.canRead()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files == null) continue;
            for (File f : files) {
                if (!f.canRead() || f.length() <= 0) continue;
                if (seenSentry.contains(f.getName())) continue;
                seenSentry.add(f.getName());
                sentrySize += f.length();
                sentryCount++;
                if (isFileFromToday(f.getName(), todayStr, EVENT_PATTERN, 1)) {
                    sentryTodayCount++;
                }
            }
        }
        // Legacy sentry location
        File legacySentryDir = new File(LEGACY_SENTRY_DIR);
        if (legacySentryDir.exists() && legacySentryDir.canRead()) {
            File[] files = legacySentryDir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files != null) {
                for (File f : files) {
                    if (!f.canRead() || f.length() <= 0) continue;
                    if (seenSentry.contains(f.getName())) continue;
                    seenSentry.add(f.getName());
                    sentrySize += f.length();
                    sentryCount++;
                    if (isFileFromToday(f.getName(), todayStr, EVENT_PATTERN, 1)) {
                        sentryTodayCount++;
                    }
                }
            }
        }
        
        // Proximity events from ALL locations
        for (File dir : storage.getAllProximityDirs()) {
            if (!dir.exists() || !dir.canRead()) continue;
            File[] files = dir.listFiles((d, name) -> name.endsWith(".mp4"));
            if (files == null) continue;
            for (File f : files) {
                if (!f.canRead() || f.length() <= 0) continue;
                if (seenProximity.contains(f.getName())) continue;
                seenProximity.add(f.getName());
                proximitySize += f.length();
                proximityCount++;
                if (isFileFromToday(f.getName(), todayStr, PROXIMITY_PATTERN, 1)) {
                    proximityTodayCount++;
                }
            }
        }
        
        // Get available space from the active recordings directory
        File activeRecDir = storage.getRecordingsDir();
        long availableSpace = activeRecDir.exists() ? activeRecDir.getFreeSpace() : 0;
        long totalSpace = activeRecDir.exists() ? activeRecDir.getTotalSpace() : 0;
        
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("normalCount", normalCount);
        response.put("normalSize", normalSize);
        response.put("normalSizeFormatted", formatSize(normalSize));
        response.put("sentryCount", sentryCount);
        response.put("sentrySize", sentrySize);
        response.put("sentrySizeFormatted", formatSize(sentrySize));
        response.put("proximityCount", proximityCount);
        response.put("proximitySize", proximitySize);
        response.put("proximitySizeFormatted", formatSize(proximitySize));
        response.put("totalCount", normalCount + sentryCount + proximityCount);
        response.put("totalSize", normalSize + sentrySize + proximitySize);
        response.put("totalSizeFormatted", formatSize(normalSize + sentrySize + proximitySize));
        response.put("availableSpace", availableSpace);
        response.put("availableSpaceFormatted", formatSize(availableSpace));
        response.put("totalSpace", totalSpace);
        response.put("totalSpaceFormatted", formatSize(totalSpace));
        
        // Today's counts
        response.put("normalTodayCount", normalTodayCount);
        response.put("sentryTodayCount", sentryTodayCount);
        response.put("proximityTodayCount", proximityTodayCount);
        response.put("totalTodayCount", normalTodayCount + sentryTodayCount + proximityTodayCount);
        
        // SOTA: Add storage limit info
        response.put("recordingsLimitMb", storage.getRecordingsLimitMb());
        response.put("surveillanceLimitMb", storage.getSurveillanceLimitMb());
        response.put("recordingsLimitBytes", storage.getRecordingsLimitMb() * 1024 * 1024);
        response.put("surveillanceLimitBytes", storage.getSurveillanceLimitMb() * 1024 * 1024);
        response.put("recordingsUsagePercent", storage.getRecordingsLimitMb() > 0 ? 
            Math.round(normalSize * 100.0 / (storage.getRecordingsLimitMb() * 1024 * 1024)) : 0);
        response.put("surveillanceUsagePercent", storage.getSurveillanceLimitMb() > 0 ? 
            Math.round(sentrySize * 100.0 / (storage.getSurveillanceLimitMb() * 1024 * 1024)) : 0);
        
        // Storage paths
        response.put("recordingsPath", getRecordingsDir());
        response.put("surveillancePath", getSentryDir());
        
        HttpResponse.sendJson(out, response.toString());
    }
    
    /**
     * Check if a filename matches today's date based on the pattern.
     * @param filename The filename to check
     * @param todayStr Today's date in YYYYMMDD format
     * @param pattern The regex pattern to match
     * @param dateGroup The group index containing the date in the pattern
     * @return true if the file is from today
     */
    private static boolean isFileFromToday(String filename, String todayStr, Pattern pattern, int dateGroup) {
        Matcher m = pattern.matcher(filename);
        if (m.matches()) {
            String dateStr = m.group(dateGroup);
            return todayStr.equals(dateStr);
        }
        return false;
    }
    
    /**
     * Stream video file with optional Range support.
     */
    private static void streamVideo(OutputStream out, String filename, String rangeHeader) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendError(out, 400, "Invalid filename");
            return;
        }
        
        // Use shared findVideoFile which checks ALL storage locations
        File file = findVideoFile(filename);
        
        if (file == null) {
            HttpResponse.sendError(out, 404, "Recording not found: " + filename);
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
                    HttpResponse.sendError(out, 416, "Range Not Satisfiable");
                    return;
                }
                
                HttpResponse.sendVideoRange(out, file, start, end);
            } else {
                HttpResponse.sendVideo(out, file);
            }
        } catch (NumberFormatException e) {
            HttpResponse.sendError(out, 400, "Invalid Range header");
        } catch (java.io.FileNotFoundException e) {
            // File disappeared between check and read (SD card unmount)
            HttpResponse.sendError(out, 410, "File no longer accessible");
        }
    }
    
    /**
     * Delete a recording.
     */
    private static void deleteRecording(OutputStream out, String filename) throws Exception {
        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            HttpResponse.sendJsonError(out, "Invalid filename");
            return;
        }
        
        // Use shared findVideoFile which checks ALL storage locations
        File file = findVideoFile(filename);
        
        if (file == null) {
            HttpResponse.sendJsonError(out, "Recording not found");
            return;
        }
        
        boolean deleted = file.delete();
        
        // SOTA: Also delete JSON sidecar if it exists
        if (deleted) {
            String jsonName = filename.replace(".mp4", ".json");
            File jsonFile = new File(file.getParentFile(), jsonName);
            if (jsonFile.exists()) {
                jsonFile.delete();
            }
            
            // Delete cached thumbnail
            String thumbName = filename.replace(".mp4", ".jpg");
            File thumbFile = new File(getThumbnailCacheDir(), thumbName);
            if (thumbFile.exists()) {
                thumbFile.delete();
            }
        }
        
        JSONObject response = new JSONObject();
        response.put("success", deleted);
        if (!deleted) {
            response.put("error", "Failed to delete file");
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
            HttpResponse.sendError(out, 400, "Invalid filename");
            return;
        }
        
        // Convert .mp4 filename to .json
        String jsonFilename = filename.replace(".mp4", ".json");
        
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
