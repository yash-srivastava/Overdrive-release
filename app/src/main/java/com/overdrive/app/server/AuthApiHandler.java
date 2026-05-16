package com.overdrive.app.server;

import com.overdrive.app.auth.AuthManager;
import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP API handler for authentication endpoints.
 *
 * Endpoints:
 * - GET  /auth/status     - Check auth status (auth required — surfaces deviceId)
 * - POST /auth/token      - Validate device token and get JWT (rate-limited)
 * - POST /auth/logout     - Clear session
 *
 * Rate limiting on /auth/token: 10 attempts per minute per client identity, then
 * 30s lockout. Identity is the X-Forwarded-For value when present (so a tunnel
 * attacker can't share the loopback bucket with the legitimate WebView), falling
 * back to socket address otherwise.
 */
public class AuthApiHandler {

    // Rate-limit constants — small numbers picked for human convenience while
    // making brute-force attempts on a 64-bit token costly.
    private static final int RATE_LIMIT_WINDOW_MS = 60_000;     // 1 minute window
    private static final int RATE_LIMIT_MAX_ATTEMPTS = 10;      // 10 attempts allowed
    private static final long RATE_LIMIT_LOCKOUT_MS = 30_000;   // 30s lockout after exceeded

    private static final ConcurrentHashMap<String, RateLimitBucket> rateLimits = new ConcurrentHashMap<>();

    private static class RateLimitBucket {
        final Deque<Long> attempts = new ArrayDeque<>();
        long lockedUntil = 0L;
    }

    /**
     * Handle auth API requests.
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        return handle(method, path, body, out, null);
    }

    /**
     * Handle auth API requests with rate-limit identity (X-Forwarded-For or socket).
     */
    public static boolean handle(String method, String path, String body, OutputStream out,
                                  String rateLimitIdentity) throws Exception {

        if (path.equals("/auth/status") && method.equals("GET")) {
            return handleStatus(out);
        }

        if (path.equals("/auth/token") && method.equals("POST")) {
            // Rate-limit token validation to slow down brute-force attempts
            // through public tunnels. Identity prefers X-Forwarded-For so a
            // tunnel attacker can't share a bucket with the loopback caller.
            String idForLimit = (rateLimitIdentity != null && !rateLimitIdentity.isEmpty())
                ? rateLimitIdentity : "unknown";
            String rateError = checkRateLimit(idForLimit);
            if (rateError != null) {
                JSONObject resp = new JSONObject();
                resp.put("success", false);
                resp.put("error", rateError);
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }
            return handleTokenValidation(body, out, idForLimit);
        }

        if (path.equals("/auth/logout") && method.equals("POST")) {
            return handleLogout(out);
        }

        return false;
    }

    /**
     * @return null if request may proceed, error string if rate limited.
     */
    private static String checkRateLimit(String identity) {
        long now = System.currentTimeMillis();
        RateLimitBucket bucket = rateLimits.computeIfAbsent(identity, k -> new RateLimitBucket());
        synchronized (bucket) {
            if (bucket.lockedUntil > now) {
                long secs = (bucket.lockedUntil - now) / 1000 + 1;
                return Messages.get("errors.rate_limited_locked_for_seconds", secs);
            }
            // Drop attempts outside the window
            long windowStart = now - RATE_LIMIT_WINDOW_MS;
            while (!bucket.attempts.isEmpty() && bucket.attempts.peekFirst() < windowStart) {
                bucket.attempts.pollFirst();
            }
            if (bucket.attempts.size() >= RATE_LIMIT_MAX_ATTEMPTS) {
                bucket.lockedUntil = now + RATE_LIMIT_LOCKOUT_MS;
                bucket.attempts.clear();
                log("Rate limit exceeded for " + identity + " — locked for "
                    + (RATE_LIMIT_LOCKOUT_MS / 1000) + "s");
                return Messages.get("errors.rate_limited_locked_for_seconds", (RATE_LIMIT_LOCKOUT_MS / 1000));
            }
            bucket.attempts.addLast(now);
        }
        return null;
    }

    /**
     * Reset the rate-limit bucket for an identity after a successful login.
     */
    private static void clearRateLimit(String identity) {
        if (identity != null) rateLimits.remove(identity);
    }
    
    /**
     * GET /auth/status
     * Returns device info.
     */
    private static boolean handleStatus(OutputStream out) throws Exception {
        AuthManager.AuthState state = AuthManager.getState();
        
        JSONObject response = new JSONObject();
        response.put("status", "ok");
        
        if (state != null) {
            response.put("deviceId", state.deviceId);
        } else {
            response.put("deviceId", "unknown");
        }
        
        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    /**
     * POST /auth/token
     * Validates device token and returns JWT session.
     */
    private static boolean handleTokenValidation(String body, OutputStream out, String rateLimitIdentity) throws Exception {
        JSONObject response = new JSONObject();

        try {
            JSONObject request = new JSONObject(body);
            String token = request.optString("token", "");

            boolean valid = AuthManager.validateDeviceToken(token);

            if (valid) {
                // Successful login — wipe attempt counter so the user gets a
                // fresh 10-attempt budget on their next session.
                clearRateLimit(rateLimitIdentity);

                String jwt = AuthManager.generateJwt();
                AuthManager.AuthState state = AuthManager.getState();
                response.put("success", true);
                response.put("jwt", jwt);
                response.put("deviceId", state.deviceId);
                response.put("expiresIn", 365 * 24 * 60 * 60); // 1 year — matches JWT expiry

                log("Token validated for device: " + state.deviceId);
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.invalid_device_token"));
                log("Invalid token attempt from " + rateLimitIdentity);
            }

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", Messages.get("errors.invalid_request_with_detail", e.getMessage()));
        }

        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    /**
     * POST /auth/logout
     * Logs out the user. Client should clear stored JWT.
     */
    private static boolean handleLogout(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("message", Messages.get("messages.logged_out"));
        
        HttpResponse.sendJson(out, response.toString());
        return true;
    }
    
    private static void log(String message) {
        CameraDaemon.log("AUTH: " + message);
    }
}
