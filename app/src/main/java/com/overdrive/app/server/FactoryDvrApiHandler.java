package com.overdrive.app.server;

import com.overdrive.app.storage.ExternalStorageCleaner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Read-only web access to the BYD factory dashcam folder (not the OverDrive recording index). */
public final class FactoryDvrApiHandler {
    private static final String BASE = "/api/factory-dvr";
    private static final long LISTING_TTL_NANOS = 5_000_000_000L;

    /** Only the latest bounded scan is addressable; IDs never encode a filesystem path. */
    private static volatile Snapshot current = new Snapshot(null, Collections.emptyMap());

    // One scan at a time; requests inside the TTL reuse the last listing.
    private static final Object scanLock = new Object();
    private static String listedJson;     // guarded by scanLock
    private static int listedStatus;      // guarded by scanLock
    private static long listedAtNanos;    // guarded by scanLock

    private FactoryDvrApiHandler() {}

    private static final class Snapshot {
        final File root;
        final Map<String, File> files;

        Snapshot(File root, Map<String, File> files) {
            this.root = root;
            this.files = Collections.unmodifiableMap(new HashMap<>(files));
        }
    }

    public static boolean handle(String method, String path, String range, OutputStream out)
            throws Exception {
        String route = path.split("\\?", 2)[0];
        if (!route.equals(BASE) && !route.equals(BASE + "/file")) return false;
        if (!"GET".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }
        if (route.equals(BASE)) {
            if (!path.equals(BASE)) {
                HttpResponse.sendError(out, 400, "Unexpected query");
                return true;
            }
            list(out);
        } else {
            file(path, range, out);
        }
        return true;
    }

    private static void list(OutputStream out) throws Exception {
        String json;
        int status;
        synchronized (scanLock) {
            long now = System.nanoTime();
            if (listedJson == null || now - listedAtNanos >= LISTING_TTL_NANOS) {
                scanLocked();
                listedAtNanos = System.nanoTime();
            }
            json = listedJson;
            status = listedStatus;
        }
        if (status == 200) {
            HttpResponse.sendJsonNoCors(out, json);
        } else {
            HttpResponse.sendJsonNoCors(out, status, json);
        }
    }

    private static void scanLocked() throws Exception {
        try {
            // Never ExternalStorageCleaner.getInstance(): it may arm the cleanup monitor.
            ExternalStorageCleaner.FactoryVideoSnapshot scan =
                    ExternalStorageCleaner.scanFactoryVideosReadOnly(true);
            if (scan.status == ExternalStorageCleaner.FactoryVideoSnapshot.Status.UNREADABLE) {
                throw new IOException("factory DVR folder unreadable");
            }
            JSONArray videos = new JSONArray();
            Map<String, File> lookup = new HashMap<>();
            File root = null;
            if (scan.status == ExternalStorageCleaner.FactoryVideoSnapshot.Status.OK
                    && scan.directory != null) {
                root = new File(scan.directory).getCanonicalFile();
                for (ExternalStorageCleaner.FactoryVideo video : scan.videos) {
                    File canonical = video.file.getCanonicalFile();
                    if (!allowed(canonical, root)) continue;
                    String id = idFor(canonical);
                    lookup.put(id, canonical);
                    JSONObject entry = new JSONObject();
                    entry.put("id", id);
                    entry.put("name", canonical.getName());
                    entry.put("relativePath", video.relativePath.replace(File.separatorChar, '/'));
                    entry.put("bytes", video.sizeBytes);
                    entry.put("modified", video.modifiedMs);
                    videos.put(entry);
                }
            }
            current = new Snapshot(root, lookup);
            JSONObject response = new JSONObject();
            response.put("found", root != null);
            response.put("limited", scan.partial || scan.totalFound > scan.videos.size());
            response.put("videos", videos);
            listedJson = response.toString();
            listedStatus = 200;
        } catch (IOException | SecurityException error) {
            current = new Snapshot(null, Collections.emptyMap());
            listedJson = new JSONObject()
                    .put("error", "Factory DVR storage is missing or unreadable")
                    .toString();
            listedStatus = 503;
        }
    }

    private static void file(String path, String rangeHeader, OutputStream out) throws Exception {
        String id = null;
        boolean download = false;
        int question = path.indexOf('?');
        if (question < 0) {
            HttpResponse.sendError(out, 400, "Missing file ID");
            return;
        }
        for (String part : path.substring(question + 1).split("&", -1)) {
            if (part.startsWith("id=") && id == null) {
                id = part.substring(3);
            } else if (part.equals("download=1") && !download) {
                download = true;
            } else {
                HttpResponse.sendError(out, 400, "Invalid query");
                return;
            }
        }
        if (id == null || !id.matches("[0-9a-f]{32}")) {
            HttpResponse.sendError(out, 400, "Invalid file ID");
            return;
        }
        Snapshot snapshot = current;
        File source = snapshot.files.get(id);
        if (source == null || snapshot.root == null) {
            HttpResponse.sendError(out, 404, "Refresh the Factory DVR listing");
            return;
        }
        // Re-resolve on every request: the card or a symlink can change after the scan.
        try {
            if (!allowed(source.getCanonicalFile(), snapshot.root.getCanonicalFile())) {
                HttpResponse.sendError(out, 410, "Factory DVR file is unavailable");
                return;
            }
        } catch (IOException | SecurityException e) {
            HttpResponse.sendError(out, 410, "Factory DVR file is unavailable");
            return;
        }
        try {
            stream(out, source, rangeHeader, download);
        } catch (FileNotFoundException e) {
            HttpResponse.sendError(out, 410, "Factory DVR file is unavailable");
        }
    }

    private static boolean allowed(File file, File root) {
        return root.isDirectory() && file.getPath().startsWith(root.getPath() + File.separator)
                && file.isFile() && file.canRead();
    }

    private static String idFor(File file) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(file.getPath().getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder(32);
        for (int i = 0; i < 16; ++i) {
            id.append(Character.forDigit((digest[i] >>> 4) & 15, 16));
            id.append(Character.forDigit(digest[i] & 15, 16));
        }
        return id.toString();
    }

    private static String contentType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }

    private static void stream(OutputStream out, File file, String requestedRange, boolean download)
            throws Exception {
        // no-store: private OEM footage that the dashcam may still overwrite.
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length();
            if (size == 0) {
                HttpResponse.sendError(out, 410, "Factory DVR file is empty");
                return;
            }
            long[] range;
            try {
                range = HttpResponse.parseSingleByteRange(requestedRange, size);
            } catch (IndexOutOfBoundsException invalid) {
                HttpResponse.sendRangeNotSatisfiable(out, size, "Range Not Satisfiable");
                return;
            } catch (IllegalArgumentException invalid) {
                HttpResponse.sendError(out, 400, "Invalid Range header");
                return;
            }
            long start = range == null ? 0 : range[0];
            long end = range == null ? size - 1 : range[1];
            StringBuilder header = new StringBuilder()
                    .append(range == null ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\n")
                    .append("Content-Type: ").append(contentType(file.getName())).append("\r\n")
                    .append("Content-Length: ").append(end - start + 1).append("\r\n")
                    .append("Accept-Ranges: bytes\r\n")
                    .append("Cache-Control: private, no-store\r\n")
                    .append("X-Content-Type-Options: nosniff\r\n");
            if (range != null) {
                header.append("Content-Range: bytes ").append(start).append('-')
                        .append(end).append('/').append(size).append("\r\n");
            }
            if (download) {
                String safeName = file.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
                if (safeName.length() > 100) safeName = safeName.substring(safeName.length() - 100);
                header.append("Content-Disposition: attachment; filename=\"")
                        .append(safeName).append("\"\r\n");
            }
            header.append("Connection: close\r\n\r\n");
            out.write(header.toString().getBytes(StandardCharsets.UTF_8));
            input.seek(start);
            byte[] bytes = new byte[64 * 1024];
            long remaining = end - start + 1;
            while (remaining > 0) {
                int count = input.read(bytes, 0, (int) Math.min(bytes.length, remaining));
                if (count < 0) break; // Card removed or file truncated mid-stream.
                out.write(bytes, 0, count);
                remaining -= count;
            }
            out.flush();
        }
    }
}
