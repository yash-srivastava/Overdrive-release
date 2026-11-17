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
 * - GET  /auth/status     - Device info. PUBLIC: the login page needs the deviceId
 *                           before the user has a JWT (see AuthMiddleware.PUBLIC_PATHS).
 * - POST /auth/token      - Validate device token and get JWT (rate-limited)
 * - POST /auth/logout     - Clear session
 *
 * Rate limiting on /auth/token, two buckets, both of which must pass:
 *
 *   1. Shared per-socket-peer ceiling: 600 attempts per sliding minute, keyed on the peer IP
 *      WITHOUT its port, and never latching a lockout. This is the only constraint added
 *      here; it exists because the per-client identity below can be rotated (a caller that
 *      varies X-Forwarded-For minted a fresh bucket on every request, so nothing bounded it).
 *      It must not latch: every client behind one tunnel shares this peer, so a latch would
 *      deny them all. Charged FIRST, so a request it refuses — which never reaches token
 *      validation — costs nobody their personal allowance.
 *   2. Per-client budget: 10 attempts per minute, then a 30s lockout which clears the window
 *      so capacity returns whole. UNCHANGED from the original, including its identity: the
 *      leftmost X-Forwarded-For when present, else the full socket address WITH its port.
 *      Keeping the port is deliberate — it makes a non-forwarded identity per-connection, so
 *      those callers stay unthrottled, and keying on the bare IP instead turned 127.0.0.1
 *      into one shared budget for the on-screen login, adb, and every remote client arriving
 *      through a forward that injects no header.
 *
 * Known, pre-existing and unchanged: the leftmost X-Forwarded-For is attacker-supplied on a
 * non-PROXY tunnel, so a caller can spend (and latch) a budget keyed on an address it does
 * not own. Fixing that needs an authenticated client identity, not a different bucket key.
 */
public class AuthApiHandler {

    // Rate-limit constants — small numbers picked for human convenience while
    // making brute-force attempts on the device secret costly.
    private static final int RATE_LIMIT_WINDOW_MS = 60_000;     // 1 minute window
    private static final int RATE_LIMIT_MAX_ATTEMPTS = 10;      // 10 attempts allowed
    private static final long RATE_LIMIT_LOCKOUT_MS = 30_000;   // 30s lockout after exceeded

    // Loose ceiling for the socket-peer bucket, charged on every /auth/token attempt.
    // Its ONLY job is to make a caller that rotates X-Forwarded-For bounded rather than
    // unlimited; the per-client bucket above is the real budget. Deliberately high and
    // LOCKOUT-FREE (see checkRateLimit): behind a tunnel every remote client shares one
    // socket peer (127.0.0.1), so a lockout here would be a login kill-switch against the
    // owner — ~2 req/s would have kept them permanently locked out.
    private static final int RATE_LIMIT_MAX_ATTEMPTS_PER_SOCKET = 600;

    // Bucket-key namespaces, so a socket address and a forwarded address never collide.
    private static final String CLIENT_KEY_PREFIX = "client:";

    private static final ConcurrentHashMap<String, RateLimitBucket> rateLimits = new ConcurrentHashMap<>();

    // The shared ceiling's counters live in their OWN map, NOT in rateLimits. The per-client
    // map keeps HEAD's unconditional hard-cap eviction (restored on purpose), and because a
    // non-forwarded client bucket is minted per CONNECTION it churns fast — a ceiling bucket
    // sitting in there would be evicted in hash order long before reaching its threshold,
    // silently voiding the bound. Distinct peer IPs are few, so this map needs only a light
    // bound of its own.
    private static final ConcurrentHashMap<String, RateLimitBucket> socketCeilings =
            new ConcurrentHashMap<>();
    private static final int MAX_CEILING_BUCKETS = 256;

    // Inline GC counter for the rateLimits map. Without this, a publicly
    // tunneled instance accumulates a permanent entry per scanner IP that
    // ever hits /auth/token (typical: 10²–10⁴ unique IPs/week per public
    // endpoint). pruneStaleRateLimits walks the map opportunistically every
    // RATE_LIMIT_GC_EVERY checkRateLimit calls to keep it bounded.
    private static final java.util.concurrent.atomic.AtomicInteger rateLimitOps =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int RATE_LIMIT_GC_EVERY = 64;
    // Stop bucket-keep growth at a hard cap so a burst of fresh IPs faster
    // than the GC cadence can't push the map above this. Far above the
    // legitimate per-tunnel concurrent client count (hundreds) but well
    // below "leak" territory.
    private static final int RATE_LIMIT_MAX_BUCKETS = 1024;

    private static class RateLimitBucket {
        final Deque<Long> attempts = new ArrayDeque<>();
        long lockedUntil = 0L;
    }

    /**
     * Handle auth API requests.
     *
     * @param clientIdentity per-client rate-limit identity, UNCHANGED from the original
     *                       behaviour: the forwarded client address when the request carried
     *                       one, else the full socket address INCLUDING its port. The port
     *                       makes a non-forwarded identity effectively per-connection, so
     *                       such callers are not throttled — that is deliberate. Keying on
     *                       the bare IP instead made `127.0.0.1` one shared budget for the
     *                       on-screen WebView, adb, and every remote client arriving through
     *                       a forward that injects no header (WireGuard, ssh -R, socat), so
     *                       two people mistyping could lock each other out.
     * @param socketIp       the peer IP WITHOUT its port, used only for the shared ceiling
     *                       below. That ceiling is the one genuinely new constraint: it
     *                       bounds a caller who rotates X-Forwarded-For, which otherwise
     *                       minted a fresh per-client bucket on every single request.
     */
    /**
     * The app (a session from this head unit) taking the device secret it was handed (handoff: the shared config stops
     * carrying it), or asking for a new one (regenerate: the reply carries it; other sessions end).
     */
    static void handleSecret(String method, String path, boolean localSession, OutputStream out) throws Exception {
        if (!"POST".equals(method)) {
            HttpResponse.sendJson(out, 405, "{\"success\":false,\"error\":\"POST only\"}");
            return;
        }
        if (!localSession) {
            HttpResponse.sendJson(out, 403, "{\"success\":false,\"error\":\"Local session only\"}");
            return;
        }
        if (path.equals("/api/auth/handoff")) {
            boolean ok = AuthManager.handoffDone();
            HttpResponse.sendJson(out, ok ? 200 : 503, new JSONObject().put("success", ok).toString());
            return;
        }
        String token = AuthManager.regenerateToken();
        AuthManager.AuthState state = AuthManager.getState();
        if (token == null || state == null) {
            HttpResponse.sendJson(out, 503, "{\"success\":false,\"error\":\"Could not save a new secret\"}");
            return;
        }
        HttpResponse.sendJson(out, 200, new JSONObject().put("success", true).put("deviceId", state.deviceId)
                .put("secret", state.deviceSecret).toString());
    }

    public static boolean handle(String method, String path, String body, OutputStream out,
                                  String clientIdentity, String socketIp)
            throws Exception {
        return handle(method, path, body, out, clientIdentity, socketIp, null, null);
    }

    /** As above; {@code jwt} is the caller's token (logout signs that browser out), {@code userAgent} labels a login. */
    public static boolean handle(String method, String path, String body, OutputStream out,
                                  String clientIdentity, String socketIp, String jwt, String userAgent)
            throws Exception {

        if (path.equals("/auth/status") && method.equals("GET")) {
            return handleStatus(out);
        }

        if (path.equals("/auth/token") && method.equals("POST")) {
            String clientKey = CLIENT_KEY_PREFIX
                    + ((clientIdentity != null && !clientIdentity.isEmpty())
                        ? clientIdentity : "unknown");

            // The shared ceiling is consulted FIRST: a request it refuses never reaches token
            // validation, so it must not consume anybody's personal allowance. It never
            // latches, because every client behind one tunnel shares this socket peer and a
            // latch here would be a login kill-switch rather than a throttle.
            String rateError = checkSocketCeiling(
                    (socketIp != null && !socketIp.isEmpty()) ? socketIp : "unknown");

            // Then the per-client budget, with the original latch-and-clear semantics.
            if (rateError == null) {
                rateError = checkRateLimit(clientKey);
            }

            if (rateError != null) {
                com.overdrive.app.notifications.LoginAttemptNotifier.loginFailed(clientIdentity, socketIp, true);
                JSONObject resp = new JSONObject();
                resp.put("success", false);
                resp.put("error", rateError);
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }
            return handleTokenValidation(body, out, clientKey, userAgent, clientIdentity, socketIp);
        }

        if (path.equals("/auth/logout") && method.equals("POST")) {
            return handleLogout(out, jwt);
        }

        return false;
    }

    /**
     * Signed-in browsers: GET /api/auth/sessions, POST /api/auth/sessions/revoke {id},
     * POST /api/auth/sessions/revoke-others. The caller holds a valid JWT (checked by HttpServer).
     */
    static void handleSessions(String method, String path, String body, OutputStream out, String jwt)
            throws Exception {
        String currentId = AuthManager.sessionIdOf(jwt);
        if (path.equals("/api/auth/sessions") && "GET".equals(method)) {
            HttpResponse.sendJsonNoCors(out, new JSONObject()
                    .put("success", true)
                    .put("currentId", currentId == null ? JSONObject.NULL : currentId)
                    .put("sessions", AuthManager.listSessions(currentId)).toString());
            return;
        }
        if (path.equals("/api/auth/sessions/revoke") && "POST".equals(method)) {
            String id = "";
            try {
                id = new JSONObject(body == null ? "" : body).optString("id", "");
            } catch (Exception ignored) {}
            boolean ok = com.overdrive.app.auth.AuthSessions.revoke(id);
            if (ok) log("Signed out a browser session");
            HttpResponse.sendJsonNoCors(out, ok ? 200 : 404, new JSONObject()
                    .put("success", ok).put("current", ok && id.equals(currentId)).toString());
            return;
        }
        if (path.equals("/api/auth/sessions/revoke-others") && "POST".equals(method)) {
            int revoked = com.overdrive.app.auth.AuthSessions.revokeAllExcept(currentId);
            if (revoked > 0) log("Signed out " + revoked + " other browser session(s)");
            HttpResponse.sendJsonNoCors(out, revoked >= 0 ? 200 : 503, new JSONObject()
                    .put("success", revoked >= 0).put("revoked", Math.max(0, revoked)).toString());
            return;
        }
        HttpResponse.sendJsonNoCors(out, 404, "{\"success\":false,\"error\":\"Not found\"}");
    }

    /**
     * Per-client budget. IDENTICAL to the original: 10 attempts per sliding minute, then a
     * 30s lockout that also clears the window so capacity returns whole when it expires.
     *
     * @return null if request may proceed, error string if rate limited.
     */
    private static String checkRateLimit(String identity) {
        long now = System.currentTimeMillis();
        // Opportunistic GC to bound the map under public-tunnel scanner load.
        // Runs on every Nth invocation so the steady-state cost is negligible.
        if ((rateLimitOps.incrementAndGet() & (RATE_LIMIT_GC_EVERY - 1)) == 0) {
            pruneStaleRateLimits(now);
        }
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
     * Shared per-peer ceiling: {@value #RATE_LIMIT_MAX_ATTEMPTS_PER_SOCKET} attempts per
     * sliding minute, keyed on the peer IP without its port. NEVER latches a lockout —
     * every client behind one tunnel shares this peer, so a latch would deny them all
     * rather than throttle one. Refuses only the over-budget request, so capacity returns
     * as the oldest attempt ages out.
     *
     * @return null if request may proceed, error string if over the ceiling
     */
    private static String checkSocketCeiling(String ip) {
        long now = System.currentTimeMillis();
        if (socketCeilings.size() > MAX_CEILING_BUCKETS) {
            // Drop peers with nothing inside the current window. Evicting a bucket a
            // concurrent caller just created can only LOSE a count, i.e. grant allowance;
            // it can never deny a request that should have been served.
            socketCeilings.entrySet().removeIf(e -> {
                RateLimitBucket b = e.getValue();
                synchronized (b) {
                    return b.attempts.isEmpty()
                            || b.attempts.peekLast() < now - RATE_LIMIT_WINDOW_MS;
                }
            });
        }
        RateLimitBucket bucket =
                socketCeilings.computeIfAbsent(ip, k -> new RateLimitBucket());
        synchronized (bucket) {
            long windowStart = now - RATE_LIMIT_WINDOW_MS;
            while (!bucket.attempts.isEmpty() && bucket.attempts.peekFirst() < windowStart) {
                bucket.attempts.pollFirst();
            }
            if (bucket.attempts.size() >= RATE_LIMIT_MAX_ATTEMPTS_PER_SOCKET) {
                // peekFirst is non-null here (size >= the ceiling, which is >= 1).
                long retrySecs = Math.max(1L,
                        (bucket.attempts.peekFirst() + RATE_LIMIT_WINDOW_MS - now) / 1000);
                return Messages.get("errors.rate_limited_locked_for_seconds", retrySecs);
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
     * Walks the rateLimits map and removes buckets that are no longer
     * useful: lockout has expired AND the attempt window holds no entries.
     * Then enforces RATE_LIMIT_MAX_BUCKETS as a hard ceiling by dropping buckets in
     * ConcurrentHashMap hash order (NOT oldest-first, so it is a capacity valve rather
     * than an LRU) until the map is back under the cap. CHM iteration is weakly
     * consistent and the per-bucket synchronized block in checkRateLimit keeps
     * state-machine consistency, so concurrent iteration here is safe.
     */
    private static void pruneStaleRateLimits(long now) {
        long stalenessCutoff = now - RATE_LIMIT_LOCKOUT_MS - RATE_LIMIT_WINDOW_MS;
        java.util.Iterator<java.util.Map.Entry<String, RateLimitBucket>> it =
            rateLimits.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<String, RateLimitBucket> e = it.next();
            RateLimitBucket b = e.getValue();
            synchronized (b) {
                // Only drop buckets that have a HISTORY (most recent attempt
                // older than stalenessCutoff) or a fully-expired lockout.
                // Critical: do NOT drop a bucket that is empty + lockedUntil=0
                // — that's the freshly-created state from computeIfAbsent
                // before the caller has entered its synchronized block. The
                // caller (checkRateLimit) holds the bucket reference but the
                // map lookup hasn't been synchronized; if we evict here, the
                // caller adds attempts to an orphaned bucket and the next
                // checkRateLimit for the same identity creates a new bucket,
                // silently losing the rate-limit state. Brute-force attackers
                // could time-attack the prune cadence to bypass protection.
                if (b.lockedUntil > 0 && b.lockedUntil < now && b.attempts.isEmpty()) {
                    it.remove();
                } else if (!b.attempts.isEmpty() && b.attempts.peekLast() < stalenessCutoff
                        && b.lockedUntil < now) {
                    it.remove();
                }
            }
        }
        // Hard cap, unconditional — as originally written. Under a sustained scanner burst
        // the staleness prune above may not keep up, and this must remain a genuine ceiling:
        // skipping "recently active" buckets (an earlier revision did) let the map grow past
        // the cap with nothing able to shrink it until those attempts aged out.
        if (rateLimits.size() > RATE_LIMIT_MAX_BUCKETS) {
            int over = rateLimits.size() - RATE_LIMIT_MAX_BUCKETS;
            java.util.Iterator<java.util.Map.Entry<String, RateLimitBucket>> dropIt =
                rateLimits.entrySet().iterator();
            while (over > 0 && dropIt.hasNext()) {
                dropIt.next();
                dropIt.remove();
                over--;
            }
        }
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
    private static boolean handleTokenValidation(String body, OutputStream out,
                                                  String clientKey, String userAgent,
                                                  String clientIdentity, String socketIp)
            throws Exception {
        JSONObject response = new JSONObject();

        try {
            JSONObject request = new JSONObject(body);
            String token = request.optString("token", "");

            boolean valid = AuthManager.validateDeviceToken(token);

            if (valid) {
                // Successful login — wipe THIS client's counter so they get a fresh
                // budget next session. Deliberately NOT the shared socket ceiling: that
                // is not this client's to reset, and clearing it would let one legitimate
                // login hand every other tunnel caller a full fresh allowance.
                clearRateLimit(clientKey);

                String jwt = AuthManager.generateSessionJwt(userAgent);
                AuthManager.AuthState state = AuthManager.getState();
                if (jwt == null || state == null) {
                    // Auth state was invalidated between validateDeviceToken
                    // and here (e.g. concurrent regenerateToken). Treat as
                    // a transient failure rather than NPEing on state.deviceId.
                    response.put("success", false);
                    response.put("error", Messages.get("errors.invalid_device_token"));
                    log("Auth state vanished mid-login — asking client to retry");
                } else {
                    response.put("success", true);
                    response.put("jwt", jwt);
                    response.put("deviceId", state.deviceId);
                    response.put("expiresIn", AuthManager.JWT_EXPIRY_SECONDS);

                    log("Token validated for device: " + state.deviceId);
                    com.overdrive.app.notifications.LoginAttemptNotifier.loginSucceeded(clientIdentity, socketIp,
                            com.overdrive.app.auth.AuthSessions.labelFor(userAgent));
                }
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.invalid_device_token"));
                log("Invalid token attempt from " + clientKey);
                com.overdrive.app.notifications.LoginAttemptNotifier.loginFailed(clientIdentity, socketIp, false);
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
    private static boolean handleLogout(OutputStream out, String jwt) throws Exception {
        // A tracked browser's token stops working here too, not only in that browser.
        String sessionId = AuthManager.sessionIdOf(jwt);
        if (sessionId != null) com.overdrive.app.auth.AuthSessions.revoke(sessionId);
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
