package com.overdrive.app.server;

import com.overdrive.app.auth.AuthManager;
import com.overdrive.app.daemon.CameraDaemon;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Authentication middleware for HttpServer.
 * 
 * Validates JWT tokens from cookies or Authorization header.
 * Redirects unauthenticated requests to login page.
 * 
 * Public paths (no auth required):
 * - /auth/*          - Auth endpoints
 * - /login.html      - Login page
 * - /shared/*        - Static assets (CSS, JS, images)
 * - /favicon.ico     - Favicon
 */
public class AuthMiddleware {
    
    // Paths that don't require authentication
    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
        "/auth/status",
        "/auth/token",
        "/auth/logout",
        "/login.html",
        "/login",
        "/favicon.ico",
        "/status"
    ));
    
    // Path prefixes that don't require authentication
    private static final String[] PUBLIC_PREFIXES = {
        "/shared/",      // Static assets
        "/auth/"         // All auth endpoints
    };
    
    // Cookie name for JWT
    private static final String JWT_COOKIE_NAME = "byd_session";
    
    /**
     * Check if request is authenticated.
     * 
     * @param path Request path
     * @param headers Raw headers string (for cookie/auth extraction)
     * @param out Output stream (for sending 401/redirect)
     * @return true if authenticated or public path, false if should block
     */
    public static boolean checkAuth(String path, String cookieHeader, String authHeader, OutputStream out) throws Exception {
        // Check if path is public
        if (isPublicPath(path)) {
            return true;
        }
        
        // Try to get JWT from Authorization header first
        String jwt = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            jwt = authHeader.substring(7);
        }
        
        // Fall back to cookie
        if (jwt == null && cookieHeader != null) {
            jwt = extractJwtFromCookie(cookieHeader);
        }
        
        // No JWT found
        if (jwt == null || jwt.isEmpty()) {
            return handleUnauthorized(path, out, "No session token");
        }
        
        // Validate JWT
        AuthManager.JwtValidation validation = AuthManager.validateJwt(jwt);
        
        if (!validation.valid) {
            return handleUnauthorized(path, out, validation.error);
        }
        
        // JWT valid - allow request
        return true;
    }
    
    /**
     * Check if path is public (no auth required).
     */
    public static boolean isPublicPath(String path) {
        // Exact match
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        
        // Strip query string for matching
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        if (PUBLIC_PATHS.contains(pathOnly)) {
            return true;
        }
        
        // Prefix match
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Extract JWT from cookie header.
     */
    private static String extractJwtFromCookie(String cookieHeader) {
        if (cookieHeader == null) {
            return null;
        }
        
        // Parse cookies: "name1=value1; name2=value2"
        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && parts[0].trim().equals(JWT_COOKIE_NAME)) {
                return parts[1].trim();
            }
        }
        
        return null;
    }
    
    /**
     * Handle unauthorized request.
     * For API requests, returns 401 JSON.
     * For page requests, redirects to login.
     */
    private static boolean handleUnauthorized(String path, OutputStream out, String reason) throws Exception {
        log("Unauthorized: " + path + " - " + reason);
        
        // API requests get 401 JSON
        if (path.startsWith("/api/") || path.startsWith("/ws") || 
            path.startsWith("/snapshot/") || path.startsWith("/video/") ||
            path.startsWith("/thumb/") || path.startsWith("/h264/") || path.equals("/status")) {
            
            String json = "{\"error\":\"Unauthorized\",\"reason\":\"" + reason + "\",\"login\":\"/login.html\"}";
            HttpResponse.sendUnauthorized(out, json);
            return false;
        }
        
        // Page requests get redirected to login
        String redirectUrl = "/login.html?redirect=" + urlEncode(path);
        HttpResponse.sendRedirect(out, redirectUrl);
        return false;
    }
    
    /**
     * Simple URL encoding for redirect parameter.
     */
    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
    
    private static void log(String message) {
        CameraDaemon.log("AUTH: " + message);
    }
}
