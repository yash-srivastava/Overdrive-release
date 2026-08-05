package com.overdrive.app.server;

import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP Response utilities - shared by all handlers.
 */
public class HttpResponse {
    
    public static void sendError(OutputStream out, int code, String message) throws Exception {
        String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
                         "Content-Type: text/plain\r\n" +
                         "Connection: close\r\n\r\n" +
                         message;
        out.write(response.getBytes());
        out.flush();
    }

    public static void sendHtml(OutputStream out, String html) throws Exception {
        byte[] body = html.getBytes("UTF-8");
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }

    public static void sendJson(OutputStream out, String json) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                        "Cache-Control: no-cache, no-store\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }
    
    public static void sendJsonSuccess(OutputStream out) throws Exception {
        sendJson(out, "{\"success\":true}");
    }

    /** Send sensitive transient bytes with mandatory no-store semantics. */
    public static void sendBinaryNoStore(OutputStream out, String contentType, byte[] body,
                                         Map<String, String> extraHeaders) throws Exception {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 200 OK\r\n")
               .append("Content-Type: ").append(safeHeader(contentType)).append("\r\n")
               .append("Content-Length: ").append(body.length).append("\r\n")
               .append("Cache-Control: no-store, no-cache, must-revalidate, private\r\n")
               .append("Pragma: no-cache\r\n")
               .append("X-Content-Type-Options: nosniff\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                headers.append(safeHeader(entry.getKey())).append(": ")
                       .append(safeHeader(entry.getValue())).append("\r\n");
            }
            headers.append("Access-Control-Expose-Headers: X-Overdrive-Width, X-Overdrive-Height, ")
                   .append("X-Overdrive-PixelCopy-Code, X-Overdrive-PixelCopy-Result, ")
                   .append("X-Overdrive-Capture-Backend, X-Overdrive-Frame-Sequence, ")
                   .append("X-Overdrive-Activity\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static String safeHeader(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "");
    }

    /**
     * Send a JSON body with a non-200 status. Used when the body shape is
     * still application JSON (so {@link #sendError} would obscure it) but
     * the HTTP semantics require a 4xx/5xx — for example 410 Gone when a
     * subscription id is tombstoned.
     */
    public static void sendJson(OutputStream out, int status, String json) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String reason;
        switch (status) {
            case 400: reason = "Bad Request"; break;
            case 401: reason = "Unauthorized"; break;
            case 403: reason = "Forbidden"; break;
            case 404: reason = "Not Found"; break;
            case 409: reason = "Conflict"; break;
            case 410: reason = "Gone"; break;
            case 429: reason = "Too Many Requests"; break;
            case 500: reason = "Internal Server Error"; break;
            case 503: reason = "Service Unavailable"; break;
            default:  reason = "OK";
        }
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                        "Cache-Control: no-cache, no-store\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }
    
    /**
     * Send CORS preflight response for OPTIONS requests.
     * Browsers send OPTIONS before cross-origin POST/PUT/DELETE with JSON content-type.
     * Without this, the webapp (accessed via external URL/tunnel) cannot save settings.
     */
    public static void sendCorsPreflightResponse(OutputStream out) throws Exception {
        String headers = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                        "Access-Control-Max-Age: 86400\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.flush();
    }
    
    public static void sendJsonError(OutputStream out, String error) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("error", error);
        sendJson(out, response.toString());
    }
    
    /**
     * Send 401 Unauthorized response with JSON body.
     */
    public static void sendUnauthorized(OutputStream out, String json) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String headers = "HTTP/1.1 401 Unauthorized\r\n" +
                        "Content-Type: application/json\r\n" +
                        "WWW-Authenticate: Bearer realm=\"BYD Champ\"\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }
    
    /**
     * Send 302 redirect response.
     */
    public static void sendRedirect(OutputStream out, String location) throws Exception {
        String response = "HTTP/1.1 302 Found\r\n" +
                         "Location: " + location + "\r\n" +
                         "Connection: close\r\n\r\n";
        out.write(response.getBytes());
        out.flush();
    }
    
    /**
     * Send JSON response with Set-Cookie header for JWT.
     */
    public static void sendJsonWithCookie(OutputStream out, String json, String cookieName, String cookieValue, int maxAgeSeconds) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String cookie = cookieName + "=" + cookieValue + "; Path=/; Max-Age=" + maxAgeSeconds + "; HttpOnly; SameSite=Strict";
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Set-Cookie: " + cookie + "\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }
    
    /**
     * Cache directive used for finalized event recordings. Filenames are
     * unique-per-event and the file is immutable once renamed from
     * .mp4.tmp, so a long max-age plus immutable lets the WebView's HTTP
     * cache satisfy repeat playback locally. ETag invalidates if the
     * underlying file is ever replaced.
     */
    private static final String VIDEO_CACHE_CONTROL = "private, max-age=86400, immutable";

    /**
     * Backwards-compat overload — callers that don't compute an ETag get the
     * old "no-cache" behaviour so any future /video/* caller (e.g. live
     * streams) opting out of caching just calls the no-ETag version.
     */
    /**
     * Stream an arbitrary media file with an explicit Content-Type. Same chunked
     * body as {@link #sendVideo} but caller-chosen MIME, so audio (mp3/wav/…) and
     * video both serve correctly to a streaming MediaPlayer. No Range (callers use
     * this for small library files that buffer fine over the local loopback).
     */
    public static void sendMediaFile(OutputStream out, java.io.File file, String contentType) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "File not found");
            return;
        }
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 200 OK\r\n")
               .append("Content-Type: ").append(contentType).append("\r\n")
               .append("Content-Length: ").append(file.length()).append("\r\n")
               .append("Accept-Ranges: bytes\r\n")
               .append("Cache-Control: no-cache\r\n")
               .append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes());
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        out.flush();
    }

    /**
     * Stream a media file with a caller-chosen Content-Type and full HTTP Range support.
     * Honours a {@code bytes=start-end} request header with a 206 Partial Content reply;
     * a null/blank/malformed range falls back to a 200 full-file stream.
     *
     * <p>This is what {@link #sendMediaFile} could not do: a streaming {@code MediaPlayer}
     * (VideoView / audio service) issues Range requests to locate an MP4's {@code moov}
     * atom — non-faststart MP4s store it at the END of the file. If the server ignores the
     * Range and always returns the file from byte 0, the extractor never finds the header
     * and {@code prepare()} stalls, so "Play Video" produced nothing while a linearly-
     * streamable MP3 still played. Serving 206 fixes video playback from the library.
     */
    public static void sendMediaFileRanged(OutputStream out, java.io.File file,
                                           String contentType, String rangeHeader) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "File not found");
            return;
        }
        long fileLength = file.length();
        long start = 0, end = fileLength - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String spec = rangeHeader.substring(6).trim();
                int dash = spec.indexOf('-');
                if (dash < 0) { sendError(out, 400, "Invalid Range header"); return; }
                String from = spec.substring(0, dash).trim();
                String to = spec.substring(dash + 1).trim();
                if (from.isEmpty()) {
                    // Suffix range "bytes=-N" — the LAST N bytes. Split-on-"-" would lose
                    // this (it produces ["","N"]), so parse the two sides by the dash index.
                    long suffix = Long.parseLong(to);
                    if (suffix <= 0) { sendError(out, 416, "Range Not Satisfiable"); return; }
                    start = Math.max(0, fileLength - suffix);
                    end = fileLength - 1;
                } else {
                    start = Long.parseLong(from);
                    if (!to.isEmpty()) end = Long.parseLong(to);
                }
                if (start < 0 || start >= fileLength) { sendError(out, 416, "Range Not Satisfiable"); return; }
                if (end < 0 || end >= fileLength) end = fileLength - 1;
                if (end < start) end = start;
                partial = true;
            } catch (NumberFormatException e) {
                sendError(out, 400, "Invalid Range header");
                return;
            }
        }
        long contentLength = end - start + 1;

        StringBuilder headers = new StringBuilder();
        headers.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
               .append("Content-Type: ").append(contentType).append("\r\n")
               .append("Content-Length: ").append(contentLength).append("\r\n");
        if (partial) {
            headers.append("Content-Range: bytes ").append(start).append("-").append(end)
                   .append("/").append(fileLength).append("\r\n");
        }
        headers.append("Accept-Ranges: bytes\r\n")
               .append("Cache-Control: no-cache\r\n")
               .append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes());

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(start);
            byte[] buffer = new byte[16384];
            long remaining = contentLength;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, toRead);
                if (read <= 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        out.flush();
    }

    public static void sendVideo(OutputStream out, java.io.File file) throws Exception {
        sendVideoInternal(out, file, null);
    }

    public static void sendVideo(OutputStream out, java.io.File file, String etag) throws Exception {
        sendVideoInternal(out, file, etag);
    }

    private static void sendVideoInternal(OutputStream out, java.io.File file, String etag) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "File not found");
            return;
        }

        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 200 OK\r\n")
               .append("Content-Type: video/mp4\r\n")
               .append("Content-Length: ").append(file.length()).append("\r\n")
               .append("Accept-Ranges: bytes\r\n");
        if (etag != null) {
            headers.append("Cache-Control: ").append(VIDEO_CACHE_CONTROL).append("\r\n")
                   .append("ETag: ").append(etag).append("\r\n");
        } else {
            headers.append("Cache-Control: no-cache\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes());

        // Stream file in chunks
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        out.flush();
    }

    public static void sendVideoRange(OutputStream out, java.io.File file, long start, long end) throws Exception {
        sendVideoRangeInternal(out, file, start, end, null);
    }

    public static void sendVideoRange(OutputStream out, java.io.File file, long start, long end, String etag) throws Exception {
        sendVideoRangeInternal(out, file, start, end, etag);
    }

    private static void sendVideoRangeInternal(OutputStream out, java.io.File file, long start, long end, String etag) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "File not found");
            return;
        }

        long fileLength = file.length();
        if (start < 0 || start >= fileLength) {
            sendError(out, 416, "Range Not Satisfiable");
            return;
        }
        if (end < 0 || end >= fileLength) {
            end = fileLength - 1;
        }
        if (end < start) {
            end = start;
        }
        long contentLength = end - start + 1;

        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 206 Partial Content\r\n")
               .append("Content-Type: video/mp4\r\n")
               .append("Content-Length: ").append(contentLength).append("\r\n")
               .append("Content-Range: bytes ").append(start).append("-").append(end).append("/").append(fileLength).append("\r\n")
               .append("Accept-Ranges: bytes\r\n");
        if (etag != null) {
            headers.append("Cache-Control: ").append(VIDEO_CACHE_CONTROL).append("\r\n")
                   .append("ETag: ").append(etag).append("\r\n");
        } else {
            headers.append("Cache-Control: no-cache\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes());

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            raf.seek(start);
            byte[] buffer = new byte[16384];
            long remaining = contentLength;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = raf.read(buffer, 0, toRead);
                if (read <= 0) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        out.flush();
    }

    /**
     * 304 Not Modified — no body. Echoes the ETag so the client knows the
     * cached entry is still authoritative. Cache-Control reaffirms the
     * caching policy in case the client previously saw no-cache.
     */
    public static void sendNotModified(OutputStream out, String etag) throws Exception {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 304 Not Modified\r\n")
               .append("ETag: ").append(etag).append("\r\n")
               .append("Cache-Control: ").append(VIDEO_CACHE_CONTROL).append("\r\n")
               .append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes());
        out.flush();
    }
    
    /**
     * Send an image file with default 24h cache headers.
     */
    public static void sendImage(OutputStream out, java.io.File file, String contentType) throws Exception {
        sendImage(out, file, contentType, "public, max-age=86400");
    }

    /**
     * Send an image file with a caller-specified Cache-Control. Use
     * "no-cache" for assets that change in place (e.g. user-uploaded
     * deterrent image) — otherwise WebViews and external clients will
     * keep the previous version up to 24h even after a re-upload.
     */
    public static void sendImage(OutputStream out, java.io.File file, String contentType,
                                  String cacheControl) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "Image not found");
            return;
        }

        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + file.length() + "\r\n" +
                        "Cache-Control: " + cacheControl + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());

        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        out.flush();
    }
    
    /**
     * Send image bytes directly with caching headers.
     */
    public static void sendImageBytes(OutputStream out, byte[] data, String contentType) throws Exception {
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + data.length + "\r\n" +
                        "Cache-Control: public, max-age=86400\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(data);
        out.flush();
    }
}
