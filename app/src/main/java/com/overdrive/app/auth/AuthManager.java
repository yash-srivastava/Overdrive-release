package com.overdrive.app.auth;

import android.util.Base64;
import com.overdrive.app.util.ScratchPaths;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authentication Manager for BYD Champ.
 *
 * Simple device token authentication - no external OAuth needed.
 * Works with any tunnel (Cloudflare, Zrok, etc.) since no origin validation required.
 *
 * Auth Flow:
 * 1. User enters device token (displayed in app)
 * 2. Token validated → JWT session created
 * 3. JWT used for subsequent requests (30 day expiry, then re-login)
 *
 * Security:
 * - Device token = deviceId + secret (e.g., byd-a1b2c3d4-x7k9m2p5)
 * - JWT signed with HMAC-SHA256 using device secret
 *
 * Persistence:
 * The auth section ({@code "auth"}) lives inside the unified config at
 * {@code /data/local/tmp/overdrive_config.json}. UnifiedConfigManager
 * sets the file world-rw (chmod 666), so the daemon (UID 2000) and the
 * app process (UID 10xxx) read/write the same secret. Without this, the
 * two processes used to mint independent in-memory secrets and JWT
 * signatures diverged on fresh installs (because {@code /data/local/tmp/}
 * is not writable by app UID).
 *
 * Existing devices: on first run after this change, if the unified
 * config has no auth section but the legacy {@code /data/local/tmp/.byd_auth.json}
 * exists, its contents are migrated in-place — so a device that was
 * already logged in keeps its secret and existing JWTs.
 *
 * The device ID file at {@code /data/local/tmp/.overdrive_device_id} is
 * still consulted (it's written by ADB shell during MainActivity startup),
 * so the deviceId remains stable across uninstalls even though the
 * unified config is wiped on factory reset.
 */
public class AuthManager {

    // Section name inside the unified config.
    private static final String CONFIG_SECTION = "auth";
    private static final String KEY_DEVICE_ID = "deviceId";
    private static final String KEY_DEVICE_SECRET = "deviceSecret";
    private static final String KEY_LAST_ACCESS = "lastAccess";

    // Legacy single-purpose auth file. Read-only at this point — kept
    // around purely so existing installs can be migrated forward into
    // the unified config without forcing the user to re-pair.
    private static final String LEGACY_AUTH_FILE = ScratchPaths.path(".byd_auth.json");

    // Device ID file — written via ADB shell from MainActivity, survives
    // app reinstall. Consulted only when the unified config has no
    // deviceId yet (cold-start before MainActivity has synced).
    private static final String DEVICE_ID_FILE = ScratchPaths.path(".overdrive_device_id");

    // JWT settings.
    //
    // Was 1 year ("effectively indefinite") — anyone who read the device
    // secret once (a co-located process reading the world-rw unified config,
    // or a leaked config backup per ConfigBackupService's own documented
    // risk) got near-permanent API access, since nothing re-validates a
    // still-unexpired JWT against anything but its signature. 30 days caps
    // that exposure window at roughly 1/12th of the previous one.
    //
    // There's no silent-refresh endpoint: a client's cookie/localStorage JWT
    // is minted once at login (AuthApiHandler.handleTokenValidation) and used
    // until it expires, at which point auth.js's 401 handler redirects to
    // /login.html for the user to re-enter their (unchanged) device token.
    // That's an acceptable, deliberately simple trade-off at 30 days; a
    // shorter expiry would need an actual refresh mechanism to avoid
    // constant re-login friction.
    private static final long JWT_EXPIRY_MS = 30L * 24 * 60 * 60 * 1000L; // 30 days
    // Public so AuthApiHandler's login response can report the real value
    // instead of hardcoding its own copy that could drift from this one.
    public static final long JWT_EXPIRY_SECONDS = JWT_EXPIRY_MS / 1000;
    private static final String JWT_ALGORITHM = "HS256";

    // In-memory cache. UnifiedConfigManager already mtime-invalidates its
    // own cache, but a tiny per-instance cache lets us hand out the same
    // AuthState reference for repeated calls within a single request and
    // skips a JSON parse on the JWT validation hot path.
    private static volatile AuthState cachedState = null;
    private static volatile long cachedConfigMtime = 0;

    // Monotonic counter incremented every time cachedState is replaced.
    // Lets downstream JWT consumers (DaemonHttpClient, WebViewFragment cookie)
    // detect a swap and invalidate their own per-secret caches without
    // having to compare opaque secret material.
    private static volatile long stateVersion = 0;

    /**
     * Auth state persisted to the unified config.
     */
    public static class AuthState {
        public String deviceId;
        public String deviceSecret;      // Random secret for token generation
        public long lastAccess;          // Last successful auth timestamp

        public String getDeviceToken() {
            return deviceId + "-" + deviceSecret;
        }

        /**
         * Get just the secret part (for display in app UI).
         * User combines with device ID shown on login page.
         */
        public String getSecret() {
            return deviceSecret;
        }

        public JSONObject toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put(KEY_DEVICE_ID, deviceId);
                json.put(KEY_DEVICE_SECRET, deviceSecret);
                json.put(KEY_LAST_ACCESS, lastAccess);
                return json;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        public static AuthState fromJson(JSONObject json) {
            AuthState state = new AuthState();
            state.deviceId = json.optString(KEY_DEVICE_ID, "");
            state.deviceSecret = json.optString(KEY_DEVICE_SECRET, "");
            state.lastAccess = json.optLong(KEY_LAST_ACCESS, 0);
            return state;
        }
    }

    /**
     * JWT validation result.
     */
    public static class JwtValidation {
        public boolean valid;
        public String deviceId;
        public String error;

        public static JwtValidation success(String deviceId) {
            JwtValidation v = new JwtValidation();
            v.valid = true;
            v.deviceId = deviceId;
            return v;
        }

        public static JwtValidation failure(String error) {
            JwtValidation v = new JwtValidation();
            v.valid = false;
            v.error = error;
            return v;
        }
    }

    // ==================== INITIALIZATION ====================

    /**
     * Initialize auth state. Creates device secret if not exists.
     * Call this on app/daemon startup.
     */
    public static synchronized AuthState initialize() {
        AuthState state = loadFromConfig();

        // Migration: if unified config has no auth yet but the legacy
        // .byd_auth.json exists from a previous version, lift it forward
        // so existing devices don't lose their secret. We only attempt
        // the write when the unified config file is already there and
        // world-rw — which it is on any device that has run the daemon
        // at least once. On a brand-new install where the daemon hasn't
        // run yet, the write will silently fail; the daemon will perform
        // the migration on its first boot.
        if (state == null) {
            AuthState legacy = loadLegacyAuthFile();
            if (legacy != null && legacy.deviceSecret != null && !legacy.deviceSecret.isEmpty()) {
                if (state == null || legacy.deviceId != null) {
                    state = legacy;
                    if (state.deviceId == null || state.deviceId.isEmpty()) {
                        state.deviceId = loadDeviceId();
                    }
                    if (writeToConfig(state)) {
                        log("Migrated auth state from legacy file " + LEGACY_AUTH_FILE);
                    } else {
                        log("Legacy auth state held in-memory; will be migrated when unified config becomes writable");
                    }
                }
            }
        }

        // Still nothing? Mint a fresh state. Critical: only persist if
        // the write actually succeeds. On app-UID processes the unified
        // config file is unwritable until the daemon (UID 2000) creates
        // it with chmod 666. If the write fails we DO NOT cache the
        // generated secret — that would make the app sign JWTs with
        // a secret the daemon will never accept. Instead we return null
        // so callers (e.g. WebViewFragment) retry once the daemon has
        // booted and getState() can pull the canonical value.
        if (state == null || state.deviceSecret == null || state.deviceSecret.isEmpty()) {
            if (state == null) state = new AuthState();
            if (state.deviceId == null || state.deviceId.isEmpty()) {
                state.deviceId = loadDeviceId();
            }
            String candidateSecret = generateSecret(8);
            state.deviceSecret = candidateSecret;
            boolean persisted = writeToConfig(state)
                    && unifiedConfigContainsSecret(candidateSecret);
            if (!persisted) {
                log("WARN: cannot persist auth secret to unified config (likely app UID before daemon boot) — will defer to daemon");
                // Do NOT cache. Returning null keeps callers in a
                // "retry later" loop instead of locking in a secret
                // that won't agree with whatever the daemon writes.
                cachedState = null;
                cachedConfigMtime = 0;
                return null;
            }
            log("Generated new device secret");
        }

        cachedState = state;
        cachedConfigMtime = UnifiedConfigManager.getLastModified();
        stateVersion++;
        log("Auth initialized. Device: " + state.deviceId);
        return state;
    }

    /**
     * Get current auth state.
     *
     * Reads from the unified config when its mtime has advanced beyond
     * the cached snapshot. UnifiedConfigManager already throttles its
     * own disk reads via the cached-config + mtime check, so this is
     * cheap to call on every JWT validation.
     */
    public static AuthState getState() {
        AuthState cur = cachedState;
        long fileMtime = UnifiedConfigManager.getLastModified();
        if (cur != null && fileMtime != 0 && fileMtime == cachedConfigMtime) {
            return cur;
        }
        // Cache stale or unset — pull from config.
        synchronized (AuthManager.class) {
            // Re-check inside the lock.
            cur = cachedState;
            fileMtime = UnifiedConfigManager.getLastModified();
            if (cur != null && fileMtime != 0 && fileMtime == cachedConfigMtime) {
                return cur;
            }
            AuthState fresh = loadFromConfig();
            if (fresh != null && fresh.deviceSecret != null && !fresh.deviceSecret.isEmpty()) {
                if (cur == null
                        || !equalsSafe(cur.deviceSecret, fresh.deviceSecret)
                        || !equalsSafe(cur.deviceId, fresh.deviceId)) {
                    cachedState = fresh;
                    stateVersion++;
                    log("Auth state refreshed from unified config (mtime=" + fileMtime + ")");
                }
                cachedConfigMtime = fileMtime;
                return cachedState;
            }
            // Config has no auth yet — initialize (handles migration too).
            return initialize();
        }
    }

    private static boolean equalsSafe(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ==================== TOKEN VALIDATION ====================

    /**
     * Validate device token.
     * Token format: {deviceId}-{secret}
     */
    public static boolean validateDeviceToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        AuthState state = getState();
        if (state == null) {
            return false;
        }
        return token.equals(state.getDeviceToken());
    }

    /**
     * Regenerate device token (invalidates all sessions).
     *
     * Returns the new token on success, or {@code null} if persistence
     * failed (e.g. the unified config file disappeared between read and
     * write, or a cross-UID write race). On failure we deliberately
     * leave the previous cachedState in place — caching a phantom secret
     * here would re-introduce the exact divergence the unified-store
     * refactor was meant to eliminate, this time triggered by the user
     * pressing "Regenerate".
     */
    public static synchronized String regenerateToken() {
        AuthState state = getState();
        if (state == null) {
            state = new AuthState();
            state.deviceId = loadDeviceId();
        } else {
            // Don't mutate the cached AuthState in place — if the write
            // fails, callers reading cachedState concurrently would see
            // a half-applied secret. Snapshot fields onto a new object.
            AuthState fresh = new AuthState();
            fresh.deviceId = state.deviceId;
            fresh.lastAccess = state.lastAccess;
            state = fresh;
        }

        String candidate = generateSecret(8);
        state.deviceSecret = candidate;

        boolean persisted = writeToConfig(state)
                && unifiedConfigContainsSecret(candidate);
        if (!persisted) {
            log("ERROR: regenerateToken failed to persist new secret — keeping previous state");
            return null;
        }

        cachedState = state;
        cachedConfigMtime = UnifiedConfigManager.getLastModified();
        stateVersion++;

        log("Token regenerated. New token: " + state.getDeviceToken());
        return state.getDeviceToken();
    }

    // ==================== JWT MANAGEMENT ====================

    /**
     * Generate a JWT session token.
     */
    public static String generateJwt() {
        AuthState state = getState();
        if (state == null) {
            return null;
        }

        try {
            long now = System.currentTimeMillis() / 1000;
            long exp = now + (JWT_EXPIRY_MS / 1000);

            JSONObject header = new JSONObject();
            header.put("alg", JWT_ALGORITHM);
            header.put("typ", "JWT");

            JSONObject payload = new JSONObject();
            payload.put("sub", state.deviceId);
            payload.put("iat", now);
            payload.put("exp", exp);

            String headerB64 = base64UrlEncode(header.toString().getBytes(StandardCharsets.UTF_8));
            String payloadB64 = base64UrlEncode(payload.toString().getBytes(StandardCharsets.UTF_8));
            String content = headerB64 + "." + payloadB64;

            String signature = hmacSha256(content, state.deviceSecret);

            // We deliberately do NOT bump lastAccess on every JWT mint:
            // the previous implementation rewrote the auth file on every
            // call (~1Hz from /status polling) which thrashed the unified
            // config and bumped its mtime, defeating the mtime-based
            // cache. lastAccess wasn't read by anything load-bearing.
            return content + "." + signature;

        } catch (Exception e) {
            log("JWT generation error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Mint a single-purpose thumb token for a given filename. Compact HS256
     * over the existing device secret with claims {@code sub=filename} and
     * {@code exp=now+ttlSec}. The token can be carried as a {@code ?t=}
     * query param so browsers fetching the thumbnail (Web Push notification
     * service worker, FCM image fetch, iOS WebKit notification body) don't
     * need to send Authorization headers — useful when the URL ends up in
     * the OS-level notification banner where headers are not configurable.
     */
    public static String signThumbToken(String filename, long ttlSec) {
        AuthState state = getState();
        if (state == null || filename == null) return null;
        try {
            long now = System.currentTimeMillis() / 1000;
            JSONObject header = new JSONObject();
            header.put("alg", JWT_ALGORITHM);
            header.put("typ", "THM");
            JSONObject payload = new JSONObject();
            payload.put("sub", filename);
            payload.put("iat", now);
            payload.put("exp", now + ttlSec);
            String headerB64 = base64UrlEncode(header.toString().getBytes(StandardCharsets.UTF_8));
            String payloadB64 = base64UrlEncode(payload.toString().getBytes(StandardCharsets.UTF_8));
            String content = headerB64 + "." + payloadB64;
            String signature = hmacSha256(content, state.deviceSecret);
            return content + "." + signature;
        } catch (Exception e) {
            log("Thumb token sign error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Validate a thumb token against an expected filename. Returns true iff
     * signature matches the device secret, {@code typ=="THM"},
     * {@code sub==filename}, and {@code exp} is in the future.
     */
    public static boolean validateThumbToken(String filename, String token) {
        if (filename == null || token == null || token.isEmpty()) return false;
        AuthState state = getState();
        if (state == null) return false;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return false;
        try {
            String content = parts[0] + "." + parts[1];
            String expectedSig = hmacSha256(content, state.deviceSecret);
            if (!expectedSig.equals(parts[2])) return false;
            String headerJson = new String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8);
            JSONObject header = new JSONObject(headerJson);
            if (!"THM".equals(header.optString("typ"))) return false;
            String payloadJson = new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8);
            JSONObject payload = new JSONObject(payloadJson);
            if (!filename.equals(payload.optString("sub"))) return false;
            long exp = payload.optLong("exp", 0);
            return System.currentTimeMillis() / 1000 <= exp;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Invalidate cached auth state.
     * Called via IPC when app regenerates token.
     * Next JWT validation will reload from the unified config.
     */
    public static synchronized void invalidateCache() {
        cachedState = null;
        cachedConfigMtime = 0;
        stateVersion++;
        log("Auth cache invalidated - will reload on next validation");
    }

    /**
     * Monotonic counter that bumps every time the cached auth state is
     * replaced. Callers that cache JWTs derived from the state (so they
     * don't pay HMAC cost per request) can pin their cache entry to a
     * specific stateVersion and invalidate when this number moves on.
     */
    public static long getStateVersion() {
        return stateVersion;
    }

    /**
     * Validate a JWT and extract claims.
     */
    public static JwtValidation validateJwt(String jwt) {
        if (jwt == null || jwt.isEmpty()) {
            return JwtValidation.failure("No token provided");
        }

        if (jwt.startsWith("Bearer ")) {
            jwt = jwt.substring(7);
        }

        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return JwtValidation.failure("Invalid token format");
        }

        AuthState state = getState();
        if (state == null) {
            return JwtValidation.failure("Auth not initialized");
        }

        try {
            String content = parts[0] + "." + parts[1];
            String expectedSig = hmacSha256(content, state.deviceSecret);

            if (!expectedSig.equals(parts[2])) {
                log("JWT signature mismatch - token may have been regenerated");
                return JwtValidation.failure("Invalid signature");
            }

            String payloadJson = new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8);
            JSONObject payload = new JSONObject(payloadJson);

            long exp = payload.getLong("exp");
            if (System.currentTimeMillis() / 1000 > exp) {
                return JwtValidation.failure("Token expired");
            }

            String tokenDeviceId = payload.getString("sub");
            if (!tokenDeviceId.equals(state.deviceId)) {
                return JwtValidation.failure("Device mismatch");
            }

            return JwtValidation.success(tokenDeviceId);

        } catch (Exception e) {
            return JwtValidation.failure("Token validation error: " + e.getMessage());
        }
    }

    // ==================== UNIFIED CONFIG I/O ====================

    /**
     * Load auth state from the unified config. Returns null if the auth
     * section is absent or has no secret (treat as "not initialized").
     */
    private static AuthState loadFromConfig() {
        try {
            JSONObject all = UnifiedConfigManager.loadConfig();
            JSONObject section = all.optJSONObject(CONFIG_SECTION);
            if (section == null) return null;
            String secret = section.optString(KEY_DEVICE_SECRET, "");
            if (secret.isEmpty()) return null;
            AuthState state = AuthState.fromJson(section);
            return state;
        } catch (Exception e) {
            log("Failed to load auth from unified config: " + e.getMessage());
            return null;
        }
    }

    /**
     * Persist auth state to the unified config. UnifiedConfigManager
     * handles the world-rw chmod and atomic-rename write, so both the
     * daemon (UID 2000) and the app process (UID 10xxx) see the new
     * value once the file has been created.
     *
     * Important caveat: UnifiedConfigManager.updateSection mutates its
     * in-memory cache in-place BEFORE the disk write, so when the disk
     * write fails (cross-UID, before the daemon has created the file)
     * the in-memory cache silently retains the new auth section. We
     * detect that with unifiedConfigContainsSecret() and force a reload
     * from disk to roll the mutation back — otherwise the next reader
     * in this process would see a phantom secret that no other process
     * agrees on.
     */
    private static boolean writeToConfig(AuthState state) {
        try {
            boolean ok = UnifiedConfigManager.updateSection(CONFIG_SECTION, state.toJson());
            if (ok && unifiedConfigContainsSecret(state.deviceSecret)) {
                cachedConfigMtime = UnifiedConfigManager.getLastModified();
                return true;
            }
            log("UnifiedConfigManager.updateSection failed to persist auth (likely cross-UID before daemon created the file); rolling back in-memory mutation");
            // Force reload from disk so the in-memory cache no longer
            // claims auth.deviceSecret = our locally-generated value.
            UnifiedConfigManager.forceReload();
            return false;
        } catch (Exception e) {
            log("Failed to write auth to unified config: " + e.getMessage());
            try { UnifiedConfigManager.forceReload(); } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * Verify against on-disk state, bypassing UnifiedConfigManager's
     * in-memory cache. Necessary because {@code updateSection} mutates
     * its in-memory config in-place before the disk write — so a save
     * that silently failed (cross-UID permission) still leaves the
     * cache holding the new secret. This routine confirms the secret
     * actually landed on disk where the daemon will see it.
     */
    private static boolean unifiedConfigContainsSecret(String expectedSecret) {
        try {
            File file = new File(ScratchPaths.path("overdrive_config.json"));
            if (!file.exists() || !file.canRead()) return false;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            JSONObject all = new JSONObject(sb.toString());
            JSONObject section = all.optJSONObject(CONFIG_SECTION);
            if (section == null) return false;
            return expectedSecret.equals(section.optString(KEY_DEVICE_SECRET, ""));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Read the legacy single-purpose auth file. Used exactly once during
     * migration so devices that were paired before this change keep
     * their secret.
     */
    private static AuthState loadLegacyAuthFile() {
        try {
            File file = new File(LEGACY_AUTH_FILE);
            if (!file.exists() || !file.canRead()) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            String content = sb.toString().trim();
            if (content.isEmpty()) return null;

            JSONObject json = new JSONObject(content);
            return AuthState.fromJson(json);
        } catch (Exception e) {
            log("Failed to read legacy auth file: " + e.getMessage());
            return null;
        }
    }

    private static String loadDeviceId() {
        try {
            File file = new File(DEVICE_ID_FILE);
            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String id = reader.readLine();
                    if (id != null && id.startsWith("byd-")) {
                        return id.trim();
                    }
                }
            }
        } catch (Exception e) {
            log("Error reading device ID file: " + e.getMessage());
        }
        // File doesn't exist yet — temp ID. MainActivity writes the real
        // file via ADB shell on app launch; the next reconcile in
        // getState() picks up the canonical ID from the unified config.
        String tempId = "byd-" + generateSecret(8);
        log("Device ID file not found, using temporary ID: " + tempId);
        return tempId;
    }

    // ==================== CRYPTO UTILS ====================

    private static String generateSecret(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return base64UrlEncode(hash);
    }

    private static String base64UrlEncode(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private static byte[] base64UrlDecode(String data) {
        return Base64.decode(data, Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private static void log(String message) {
        CameraDaemon.log("AUTH: " + message);
    }
}
