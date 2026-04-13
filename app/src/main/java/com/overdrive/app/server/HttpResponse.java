package com.overdrive.app.server;

import org.json.JSONObject;
import java.io.OutputStream;

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
    
    public static void sendVideo(OutputStream out, java.io.File file) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "File not found");
            return;
        }
        
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Content-Length: " + file.length() + "\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        
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
        
        String headers = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Content-Length: " + contentLength + "\r\n" +
                        "Content-Range: bytes " + start + "-" + end + "/" + fileLength + "\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        
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
     * Send an image file with caching headers.
     */
    public static void sendImage(OutputStream out, java.io.File file, String contentType) throws Exception {
        if (!file.exists()) {
            sendError(out, 404, "Image not found");
            return;
        }
        
        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + file.length() + "\r\n" +
                        "Cache-Control: public, max-age=86400\r\n" +
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
