package com.overdrive.app.server;

import com.overdrive.app.storage.removable.RemovableStorageFs;
import com.overdrive.app.storage.removable.SeekableFileReader;

import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP Response utilities - shared by all handlers.
 */
public class HttpResponse {

    // File streaming chunk: fewer reads and socket writes per clip.
    private static final int FILE_STREAM_BUFFER = 64 * 1024;

    /**
     * No fetch directives: live view (JMuxer from jsDelivr, Broadway and Draco blob workers,
     * WebAssembly) must keep loading on the head unit's Chrome 58 WebView.
     */
    public static final String CSP =
            "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    public static final String SECURITY_HEADERS =
            "Content-Security-Policy: " + CSP + "\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "X-Content-Type-Options: nosniff\r\n";

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
                        SECURITY_HEADERS +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(body);
        out.flush();
    }

    public static void sendJson(OutputStream out, String json) throws Exception {
        sendJsonInternal(out, 200, json, true);
    }

    /** Same-origin-only JSON for credential-bearing local APIs. */
    public static void sendJsonNoCors(OutputStream out, String json) throws Exception {
        sendJsonInternal(out, 200, json, false);
    }

    /** Same-origin-only error JSON for credential-bearing local APIs. */
    public static void sendJsonNoCors(
            OutputStream out, int status, String json) throws Exception {
        sendJsonInternal(out, status, json, false);
    }

    private static void sendJsonInternal(
            OutputStream out, int status, String json, boolean cors)
            throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String reason = reason(status);
        StringBuilder headers = new StringBuilder()
                .append("HTTP/1.1 ").append(status).append(' ')
                .append(reason).append("\r\n")
                .append("Content-Type: application/json\r\n");
        if (cors) {
            headers.append("Access-Control-Allow-Origin: *\r\n")
                    .append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
                    .append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n");
        }
        headers.append("Cache-Control: no-cache, no-store\r\n")
                .append(SECURITY_HEADERS)
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
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
     * Start a connection-close-delimited attachment response for content that
     * is generated while it is streamed and therefore has no known length.
     */
    public static void sendAttachmentNoStoreHeaders(
            OutputStream out, String contentType, String filename)
            throws Exception {
        String safeName = safeHeader(filename)
                .replace('"', '_')
                .replace('\\', '_')
                .replace('/', '_');
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + safeHeader(contentType) + "\r\n"
                + "Content-Disposition: attachment; filename=\""
                + safeName + "\"\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate, private\r\n"
                + "Pragma: no-cache\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Send a JSON body with a non-200 status. Used when the body shape is
     * still application JSON (so {@link #sendError} would obscure it) but
     * the HTTP semantics require a 4xx/5xx — for example 410 Gone when a
     * subscription id is tombstoned.
     */
    public static void sendJson(OutputStream out, int status, String json) throws Exception {
        sendJsonInternal(out, status, json, true);
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 410: return "Gone";
            case 413: return "Payload Too Large";
            case 422: return "Unprocessable Entity";
            case 428: return "Precondition Required";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "HTTP " + status;
        }
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

    public static void sendCorsPreflightResponse(
            OutputStream out, String allowedOrigin) throws Exception {
        String origin = safeHeader(allowedOrigin);
        String headers = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: " + origin + "\r\n" +
                        "Vary: Origin\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                        "Access-Control-Max-Age: 600\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
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
     * Stream an arbitrary media file with an explicit Content-Type. Same chunked
     * body as {@link #sendVideo} but caller-chosen MIME, so audio (mp3/wav/…) and
     * video both serve correctly to a streaming MediaPlayer. No Range (callers use
     * this for small library files that buffer fine over the local loopback).
     */
    public static void sendMediaFile(OutputStream out, File file, String contentType) throws Exception {
        sendFullMedia(out, file, contentType, null);
    }

    /**
     * Stream a media file with a caller-chosen Content-Type and full HTTP Range support.
     * Honours a {@code bytes=start-end} request header with a 206 Partial Content reply;
     * a null/non-byte range falls back to a 200 full-file stream.
     *
     * <p>This is what {@link #sendMediaFile} could not do: a streaming {@code MediaPlayer}
     * (VideoView / audio service) issues Range requests to locate an MP4's {@code moov}
     * atom — non-faststart MP4s store it at the END of the file. If the server ignores the
     * Range and always returns the file from byte 0, the extractor never finds the header
     * and {@code prepare()} stalls, so "Play Video" produced nothing while a linearly-
     * streamable MP3 still played. Serving 206 fixes video playback from the library.
     */
    public static void sendMediaFileRanged(OutputStream out, File file,
                                           String contentType, String rangeHeader) throws Exception {
        if (!RemovableStorageFs.exists(file)) {
            sendError(out, 404, "File not found");
            return;
        }
        long fileLength = RemovableStorageFs.length(file);
        long[] range;
        try {
            range = parseSingleByteRange(rangeHeader, fileLength);
        } catch (IndexOutOfBoundsException e) {
            sendRangeNotSatisfiable(out, fileLength, "Range Not Satisfiable");
            return;
        } catch (IllegalArgumentException e) {
            sendError(out, 400, "Invalid Range header");
            return;
        }
        long start = range == null ? 0 : range[0];
        long end = range == null ? fileLength - 1 : range[1];
        boolean partial = range != null;
        sendMediaHeaders(out, contentType, fileLength, start, end, partial, null);
        streamFileRange(out, file, start, end - start + 1);
    }

    /**
     * Parse one HTTP byte range. Returns {@code null} when no byte range was requested.
     * Malformed ranges throw {@link IllegalArgumentException}; valid but unsatisfiable
     * ranges throw {@link IndexOutOfBoundsException}.
     */
    static long[] parseSingleByteRange(String rangeHeader, long fileLength) {
        String header = rangeHeader == null ? null : rangeHeader.trim();
        if (header == null || !header.regionMatches(true, 0, "bytes=", 0, 6)) {
            return null;
        }

        String spec = header.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0 || dash != spec.lastIndexOf('-') || spec.indexOf(',') >= 0) {
            throw new IllegalArgumentException("Invalid Range header");
        }

        String from = spec.substring(0, dash).trim();
        String to = spec.substring(dash + 1).trim();
        if (from.isEmpty()) {
            if (to.isEmpty()) throw new IllegalArgumentException("Invalid Range header");
            long suffixLength = Long.parseLong(to);
            if (suffixLength <= 0 || fileLength <= 0) {
                throw new IndexOutOfBoundsException("Range Not Satisfiable");
            }
            return new long[]{Math.max(0, fileLength - suffixLength), fileLength - 1};
        }

        long start = Long.parseLong(from);
        long end = to.isEmpty() ? fileLength - 1 : Long.parseLong(to);
        if (start < 0 || start >= fileLength || end < start) {
            throw new IndexOutOfBoundsException("Range Not Satisfiable");
        }
        return new long[]{start, Math.min(end, fileLength - 1)};
    }

    static void sendRangeNotSatisfiable(OutputStream out, long fileLength, String message)
            throws Exception {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Range: bytes */" + fileLength + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** Send video without an ETag, retaining the no-cache policy. */
    public static void sendVideo(OutputStream out, File file) throws Exception {
        sendVideo(out, file, null);
    }

    public static void sendVideo(OutputStream out, File file, String etag) throws Exception {
        sendFullMedia(out, file, "video/mp4", etag);
    }

    private static void sendFullMedia(OutputStream out, File file, String contentType,
                                      String etag) throws Exception {
        if (!RemovableStorageFs.exists(file)) {
            sendError(out, 404, "File not found");
            return;
        }
        long fileLength = RemovableStorageFs.length(file);
        sendMediaHeaders(out, contentType, fileLength, 0, fileLength - 1, false, etag);
        streamFullFile(out, file, FILE_STREAM_BUFFER);
    }

    public static void sendVideoRange(OutputStream out, File file, long start, long end) throws Exception {
        sendVideoRange(out, file, start, end, null);
    }

    public static void sendVideoRange(OutputStream out, File file, long start, long end, String etag) throws Exception {
        if (!RemovableStorageFs.exists(file)) {
            sendError(out, 404, "File not found");
            return;
        }

        long fileLength = RemovableStorageFs.length(file);
        if (start < 0 || start >= fileLength) {
            sendRangeNotSatisfiable(out, fileLength, "Range Not Satisfiable");
            return;
        }
        if (end < 0 || end >= fileLength) {
            end = fileLength - 1;
        }
        if (end < start) {
            sendRangeNotSatisfiable(out, fileLength, "Range Not Satisfiable");
            return;
        }
        sendMediaHeaders(out, "video/mp4", fileLength, start, end, true, etag);
        streamFileRange(out, file, start, end - start + 1);
    }

    private static void sendMediaHeaders(OutputStream out, String contentType, long fileLength,
                                         long start, long end, boolean partial, String etag) throws Exception {
        StringBuilder headers = new StringBuilder();
        headers.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
               .append("Content-Type: ").append(contentType).append("\r\n")
               .append("Content-Length: ").append(end - start + 1).append("\r\n");
        if (partial) {
            headers.append("Content-Range: bytes ").append(start).append("-").append(end)
                   .append("/").append(fileLength).append("\r\n");
        }
        headers.append("Accept-Ranges: bytes\r\n");
        if (etag != null) {
            headers.append("Cache-Control: ").append(VIDEO_CACHE_CONTROL).append("\r\n")
                   .append("ETag: ").append(etag).append("\r\n");
        } else {
            headers.append("Cache-Control: no-cache\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes());
    }

    private static void streamFullFile(OutputStream out, File file, int bufferSize) throws Exception {
        try (FileInputStream fis = RemovableStorageFs.openInput(file)) {
            byte[] buffer = new byte[bufferSize];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        }
        out.flush();
    }

    private static void streamFileRange(OutputStream out, File file, long start,
                                        long contentLength) throws Exception {
        try (SeekableFileReader raf = new SeekableFileReader(file)) {
            raf.seek(start);
            byte[] buffer = new byte[FILE_STREAM_BUFFER];
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
     * Default Cache-Control for images served to an authenticated client.
     *
     * <p>{@code private} keeps the browser cache (the reason for the 24 h)
     * while forbidding shared caches. Every image sent through here sits
     * behind the session cookie / JWT, and a Cloudflare-proxied hostname
     * caches {@code .jpg}/{@code .mp4} by extension and, with Origin Cache
     * Control on, honours {@code public} even on Authorization-bearing
     * requests — which would have let the CDN hand a recording thumbnail to
     * an unauthenticated visitor. Precedent: ParkingApiHandler already uses
     * {@code private}.
     */
    static final String IMAGE_CACHE_CONTROL = "private, max-age=86400";

    /** Cache-Control for images rendered from a file that is still changing. */
    static final String NO_STORE = "no-store";

    /**
     * Send an image file with default 24h private cache headers.
     */
    public static void sendImage(OutputStream out, File file, String contentType) throws Exception {
        sendImage(out, file, contentType, IMAGE_CACHE_CONTROL);
    }

    /**
     * Send an image file with a caller-specified Cache-Control. Use
     * "no-cache" for assets that change in place (e.g. user-uploaded
     * deterrent image) — otherwise WebViews and external clients will
     * keep the previous version up to 24h even after a re-upload.
     */
    public static void sendImage(OutputStream out, File file, String contentType,
                                  String cacheControl) throws Exception {
        if (!RemovableStorageFs.exists(file)) {
            sendError(out, 404, "Image not found");
            return;
        }

        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + RemovableStorageFs.length(file) + "\r\n" +
                        "Cache-Control: " + cacheControl + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());

        streamFullFile(out, file, 8192);
    }
    
    /**
     * Send image bytes directly with private 24h caching headers.
     */
    public static void sendImageBytes(OutputStream out, byte[] data, String contentType) throws Exception {
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + data.length + "\r\n" +
                        "Cache-Control: " + IMAGE_CACHE_CONTROL + "\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.write(data);
        out.flush();
    }
}
