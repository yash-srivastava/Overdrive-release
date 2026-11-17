package com.overdrive.app.auth;

import android.util.Base64;

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
    private static final String LEGACY_AUTH_FILE = com.overdrive.app.util.ScratchRoot.path(".byd_auth.json");

    // Device ID file — written via ADB shell from MainActivity, survives
    // app reinstall. Consulted only when the unified config has no
    // deviceId yet (cold-start before MainActivity has synced).
    private static final String DEVICE_ID_FILE = com.overdrive.app.util.ScratchRoot.path(".overdrive_device_id");

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

    // Device-secret length, in characters from the 36-symbol lowercase+digit alphabet.
    // This secret is BOTH the typed login credential and the HMAC key every JWT and thumb
    // token is signed with, so its entropy also bounds how hard a captured token is to
    // crack offline — the old 8 chars (~41 bits) was within reach of a single GPU. 20 gives
    // ~103 bits and stays typeable. Only affects newly minted secrets: existing pairings
    // keep whatever is already persisted, so no device has to be re-paired.
    private static final int DEVICE_SECRET_LENGTH = 20;

    // ==================== WHERE THE SECRET LIVES ====================
    //
    // The secret is the typed access code and the key of every session: whoever reads it can log in. The daemons
    // (UID 2000) keep it in an owner-only file and the app its own private copy. The shared config, which every app on
    // the head unit can read and write, carries it only as a handoff (a new secret, the move from an older version, a
    // regenerate) until the app shows it holds it: a session it signs with it is accepted (POST /api/auth/handoff).
    // Older versions kept it there for good; the same secret moves, so no session ends.
    private static final String DAEMON_SECRET_NAME = ".overdrive_auth_secret.json";
    private static final String DAEMON_SECRET_FILE =
            com.overdrive.app.util.ScratchRoot.path(DAEMON_SECRET_NAME);
    private static final String KEY_HANDED_OFF = "handedOff";
    private static volatile File appSecretFile = null;
    // when the copy cachedState came from was written (0: not from one)
    private static volatile long cachedOwnMtime = 0;
    // the daemon couldn't keep a private file: the shared config holds the secret, as in older versions
    private static volatile boolean ownFileUnavailable = false;
    // the shared config's mtime when the daemon last made sure it carries the secret only while it should
    private static volatile long checkedConfigMtime = 0;
    private static volatile long configCheckedAtMs = 0;
    private static final long CONFIG_RECHECK_MS = 30_000L;
    // the app: a handoff the daemon refused (stale, or not the daemon's), and when one was last checked
    private static volatile String refusedHandoff = null;
    private static volatile String checkedHandoff = null;
    private static volatile long handoffCheckAt = 0;
    private static final long HANDOFF_RETRY_MS = 10_000L;
    private static final java.util.concurrent.atomic.AtomicBoolean handoffChecking =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    // the app: reads the daemon's own file as shell, when it holds no secret the daemon accepts (its copy lost)
    private static volatile DaemonSecretReader daemonSecretReader = null;
    private static volatile long recoverAt = 0;
    private static final long RECOVER_RETRY_MS = 10_000L;
    private static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> recovering =
            new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.ExecutorService AUX =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread th = new Thread(r, "auth-sync");
                th.setDaemon(true);
                return th;
            });

    /** The app's private copy lives in {@code dir} (its own files dir); called at app start. */
    public static void useAppPrivateDir(File dir) {
        appSecretFile = dir == null ? null : new File(dir, "auth_secret.json");
    }

    /** Reads a shell-owned file over the app's ADB link; null when it can't. */
    public interface DaemonSecretReader {
        String read(String path);
    }

    /** How the app reads the daemon's own secret file; called at app start. */
    public static void useDaemonSecretReader(DaemonSecretReader reader) {
        daemonSecretReader = reader;
    }

    // The daemons run as shell (or root); the app under its own UID.
    private static boolean daemonSide() {
        return android.os.Process.myUid() < android.os.Process.FIRST_APPLICATION_UID;
    }

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
        public boolean handedOff;        // daemon's file only: the app has shown it holds this secret

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
            state.handedOff = json.optBoolean(KEY_HANDED_OFF, false);
            return state;
        }

        AuthState copy() {
            AuthState s = new AuthState();
            s.deviceId = deviceId;
            s.deviceSecret = deviceSecret;
            s.lastAccess = lastAccess;
            s.handedOff = handedOff;
            return s;
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
        if (!daemonSide()) return getStateApp();
        if (!ownFileUnavailable) {
            AuthState s = initializeDaemon();
            if (s != null || !ownFileUnavailable) return s;
        }
        return initializeFromConfig();
    }

    // Daemon: its own file; at first the secret older versions kept in the shared config (or the legacy file), so no
    // session ends, else a new one; then the shared config carries it until the app has it.
    private static AuthState initializeDaemon() {
        AuthState state = readOwn();
        if (state == null) {
            state = loadFromConfig();
            if (state == null) state = loadLegacyAuthFile();
            if (state == null) state = new AuthState();
            if (state.deviceSecret == null || state.deviceSecret.isEmpty()) {
                state.deviceSecret = generateSecret(DEVICE_SECRET_LENGTH);
                log("Generated new device secret");
            }
            if (state.deviceId == null || state.deviceId.isEmpty()) state.deviceId = loadDeviceId();
            state.handedOff = false;
            AuthState kept = createOwn(state);
            if (kept == null) {
                log("WARN: cannot keep the secret in a private file; the shared config holds it as before");
                ownFileUnavailable = true;
                return null;
            }
            state = kept;
            if (new File(LEGACY_AUTH_FILE).delete()) log("Removed the legacy auth file");
        }
        cachedState = state;
        cachedOwnMtime = new File(DAEMON_SECRET_FILE).lastModified();
        stateVersion++;
        syncConfig(state);
        log("Auth initialized. Device: " + state.deviceId);
        return state;
    }

    // Older versions' behaviour: the shared config holds the secret (also this daemon's, if it can't keep a file).
    private static AuthState initializeFromConfig() {
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
            String candidateSecret = generateSecret(DEVICE_SECRET_LENGTH);
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
        if (!daemonSide()) return getStateApp();
        if (!ownFileUnavailable) {
            AuthState cur = cachedState;
            long own = new File(DAEMON_SECRET_FILE).lastModified();
            if (cur != null && own != 0 && own == cachedOwnMtime) {
                // (the shared config changed, or a while passed — its mtime moves in whole seconds: make sure it
                // carries the secret only while it should, off this path)
                long cfg = UnifiedConfigManager.getLastModified();
                long now = System.currentTimeMillis();
                if (cfg != checkedConfigMtime || now - configCheckedAtMs > CONFIG_RECHECK_MS) {
                    checkedConfigMtime = cfg;
                    configCheckedAtMs = now;
                    AUX.execute(() -> {
                        AuthState s = cachedState;
                        if (s != null && !ownFileUnavailable) syncConfig(s);
                    });
                }
                return cur;
            }
            synchronized (AuthManager.class) {
                AuthState fresh = readOwn();
                if (fresh != null) {
                    if (cachedState == null || !equalsSafe(cachedState.deviceSecret, fresh.deviceSecret)
                            || !equalsSafe(cachedState.deviceId, fresh.deviceId)) stateVersion++;
                    cachedState = fresh;
                    cachedOwnMtime = new File(DAEMON_SECRET_FILE).lastModified();
                    return fresh;
                }
                AuthState made = initializeDaemon();
                if (made != null || !ownFileUnavailable) return made;
            }
        }
        return getStateFromConfig();
    }

    private static AuthState getStateFromConfig() {
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
            return initializeFromConfig();
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
        return constantTimeEquals(token, state.getDeviceToken());
    }

    /**
     * Compare two credential strings without an early-exit on the first differing byte.
     * Length still leaks, which is fine — the token's shape is public.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return a == b;
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
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
        if (!daemonSide()) return regenerateApp();
        if (!ownFileUnavailable) {
            AuthState cur = getState();
            if (!ownFileUnavailable) {
                AuthState fresh = new AuthState();
                fresh.deviceId = cur != null && cur.deviceId != null && !cur.deviceId.isEmpty() ? cur.deviceId : loadDeviceId();
                fresh.lastAccess = cur != null ? cur.lastAccess : 0;
                fresh.deviceSecret = generateSecret(DEVICE_SECRET_LENGTH);
                fresh.handedOff = false;
                if (!writeOwn(fresh)) {
                    log("ERROR: regenerateToken failed to persist new secret — keeping previous state");
                    return null;
                }
                cachedState = fresh;
                cachedOwnMtime = new File(DAEMON_SECRET_FILE).lastModified();
                stateVersion++;
                // the app takes it from the response; the config carries it until the app says so
                syncConfig(fresh);
                log("Token regenerated for device: " + fresh.deviceId);
                return fresh.getDeviceToken();
            }
        }
        return regenerateViaConfig();
    }

    // Older versions' regenerate: a new secret written to the shared config (an app with an older daemon).
    private static String regenerateViaConfig() {
        AuthState state = getStateFromConfigOrApp();
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

        String candidate = generateSecret(DEVICE_SECRET_LENGTH);
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

        // Never log the token itself — the caller (DashboardFragment) already shows the
        // new value to the user, and this line used to put a working credential in the
        // daemon log file, which the log uploader can ship off-device.
        log("Token regenerated for device: " + state.deviceId);
        return state.getDeviceToken();
    }

    private static AuthState getStateFromConfigOrApp() {
        return daemonSide() ? getStateFromConfig() : getStateApp();
    }

    // ==================== DAEMON: OWN FILE, HANDOFF ====================

    /** The app has shown it holds the current secret (a session signed with it): the shared config stops carrying it. */
    public static synchronized boolean handoffDone() {
        if (!daemonSide() || ownFileUnavailable) return false;
        AuthState cur = getState();
        if (cur == null || ownFileUnavailable) return false;
        if (!cur.handedOff) {
            AuthState s = cur.copy();
            s.handedOff = true;
            if (!writeOwn(s)) return false;
            cachedState = s;
            cachedOwnMtime = new File(DAEMON_SECRET_FILE).lastModified();
        }
        syncConfig(cachedState);
        return true;
    }

    /** A restored backup's secret, for a caller holding a session: sessions from before the reset work again. */
    public static synchronized boolean adoptSecret(String secret) {
        if (!daemonSide() || secret == null || secret.isEmpty()) return false;
        AuthState cur = getState();
        if (cur != null && secret.equals(cur.deviceSecret)) return true;
        AuthState s = new AuthState();
        s.deviceId = cur != null && cur.deviceId != null && !cur.deviceId.isEmpty() ? cur.deviceId : loadDeviceId();
        s.lastAccess = cur != null ? cur.lastAccess : 0;
        s.deviceSecret = secret;
        s.handedOff = false;
        if (ownFileUnavailable) {
            if (!writeToConfig(s)) return false;
            invalidateCache();
            return true;
        }
        if (!writeOwn(s)) return false;
        cachedState = s;
        cachedOwnMtime = new File(DAEMON_SECRET_FILE).lastModified();
        stateVersion++;
        syncConfig(s);
        return true;
    }

    // The shared config carries the secret only while the app hasn't shown it holds it; then never (an older version's
    // copy, a restored backup's, another app's write all go).
    private static void syncConfig(AuthState state) {
        try {
            // (from disk: another process's write in the same second as our last read isn't in the cache)
            UnifiedConfigManager.forceReload();
            JSONObject section = UnifiedConfigManager.loadConfig().optJSONObject(CONFIG_SECTION);
            String inConfig = section == null ? "" : section.optString(KEY_DEVICE_SECRET, "");
            String want = state.handedOff ? "" : state.deviceSecret;
            String idInConfig = section == null ? "" : section.optString(KEY_DEVICE_ID, "");
            if (!want.equals(inConfig) || !equalsSafe(state.deviceId, idInConfig)) {
                JSONObject s = new JSONObject();
                s.put(KEY_DEVICE_ID, state.deviceId);
                s.put(KEY_DEVICE_SECRET, want);
                s.put(KEY_LAST_ACCESS, state.lastAccess);
                UnifiedConfigManager.updateSection(CONFIG_SECTION, s);
            }
            checkedConfigMtime = UnifiedConfigManager.getLastModified();
        } catch (Exception e) {
            log("Auth config sync failed: " + e.getMessage());
        }
    }

    private static AuthState readOwn() {
        return readStateFile(new File(DAEMON_SECRET_FILE));
    }

    // Owner-only (0600), written whole then renamed in.
    private static boolean writeOwn(AuthState state) {
        File file = new File(DAEMON_SECRET_FILE);
        File tmp = new File(file.getPath() + ".tmp");
        try {
            if (!writeOwnerOnly(tmp, ownJson(state))) return false;
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            log("Failed to write the private auth file: " + e.getMessage());
            tmp.delete();
            return false;
        }
    }

    // The file made only if no daemon made it first (both start at boot): the one on disk then. Under a lock the
    // daemons share (a hard link, the atomic way, is refused in /data/local/tmp).
    private static AuthState createOwn(AuthState state) {
        File lock = new File(DAEMON_SECRET_FILE + ".lock");
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(lock, "rw");
             java.nio.channels.FileLock ignored = raf.getChannel().lock()) {
            AuthState made = readOwn();
            if (made != null) return made;
            return writeOwn(state) ? readOwn() : null;
        } catch (Exception e) {
            log("Failed to create the private auth file: " + e.getMessage());
            return null;
        }
    }

    private static String ownJson(AuthState state) throws Exception {
        JSONObject json = state.toJson();
        json.put(KEY_HANDED_OFF, state.handedOff);
        return json.toString();
    }

    private static boolean writeOwnerOnly(File file, String content) throws Exception {
        file.delete();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            android.system.Os.chmod(file.getPath(), 0600);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        return true;
    }

    private static AuthState readStateFile(File file) {
        try {
            if (file == null || !file.exists()) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            AuthState s = AuthState.fromJson(new JSONObject(sb.toString()));
            return s.deviceSecret == null || s.deviceSecret.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== APP: PRIVATE COPY ====================

    // The app's private copy; the shared config's secret while there is none yet (as before), and one that differs is
    // taken once the daemon accepts a session signed with it (another app could have put it there).
    private static AuthState getStateApp() {
        File own = appSecretFile;
        AuthState mine = null;
        if (own != null) {
            long mtime = own.lastModified();
            AuthState cur = cachedState;
            if (cur != null && mtime != 0 && mtime == cachedOwnMtime) mine = cur;
            else if (mtime != 0) {
                mine = readStateFile(own);
                if (mine != null) cachedOwnMtime = mtime;
            }
        }
        AuthState offered = loadFromConfig();
        if (offered != null && (mine == null || !offered.deviceSecret.equals(mine.deviceSecret))
                && !offered.deviceSecret.equals(refusedHandoff)) {
            checkHandoff(offered);
            if (mine == null) return publishApp(offered, false);
        }
        if (mine == null) {
            recoverFromDaemon(0);
            return null;
        }
        return publishApp(mine, true);
    }

    private static AuthState publishApp(AuthState s, boolean fromOwnCopy) {
        AuthState cur = cachedState;
        if (cur == null || !equalsSafe(cur.deviceSecret, s.deviceSecret) || !equalsSafe(cur.deviceId, s.deviceId)) {
            cachedState = s;
            stateVersion++;
        }
        if (!fromOwnCopy) cachedOwnMtime = 0;
        return cachedState;
    }

    // Asks the daemon, off the caller's thread, whether a session signed with [offered] is accepted; if so it becomes the
    // app's copy and the shared config stops carrying it.
    private static void checkHandoff(AuthState offered) {
        long now = System.currentTimeMillis();
        if (offered.deviceSecret.equals(checkedHandoff) && now - handoffCheckAt < HANDOFF_RETRY_MS) return;
        if (!handoffChecking.compareAndSet(false, true)) return;
        checkedHandoff = offered.deviceSecret;
        handoffCheckAt = now;
        AuthState s = offered.copy();
        AUX.execute(() -> {
            try {
                int code = postLocal("/api/auth/handoff", jwtFor(s), null);
                if (code == 200) {
                    if (writeAppCopy(s)) log("Auth: took the handed-off secret into the app's own copy");
                    publishApp(s, true);
                } else if (code == 401 || code == 403) {
                    // (not the daemon's: stale, or planted by another app)
                    refusedHandoff = s.deviceSecret;
                    log("Auth: the daemon refused a handed-off secret (" + code + ")");
                }
                // else an older daemon (404) or none yet: the shared config's, as before, checked again later
            } finally {
                handoffChecking.set(false);
            }
        });
    }

    /**
     * The app holds no secret the daemon accepts: takes the daemon's own, read as shell, once the daemon accepts a
     * session signed with it. One attempt at a time, at most every {@link #RECOVER_RETRY_MS}; waits up to
     * {@code waitMs} for it, never on the main thread.
     */
    public static void recoverFromDaemon(long waitMs) {
        if (daemonSide() || daemonSecretReader == null || appSecretFile == null) return;
        java.util.concurrent.CountDownLatch done = recovering.get();
        if (done == null) {
            long now = System.currentTimeMillis();
            if (now - recoverAt < RECOVER_RETRY_MS) return;
            java.util.concurrent.CountDownLatch mine = new java.util.concurrent.CountDownLatch(1);
            if (recovering.compareAndSet(null, mine)) {
                recoverAt = now;
                done = mine;
                AUX.execute(() -> {
                    try {
                        takeDaemonSecret();
                    } catch (Throwable t) {
                        log("Auth: reading the daemon's secret failed: " + t.getClass().getSimpleName());
                    } finally {
                        recovering.compareAndSet(mine, null);
                        mine.countDown();
                    }
                });
            } else {
                done = recovering.get();
            }
        }
        android.os.Looper main = android.os.Looper.getMainLooper();
        if (done == null || waitMs <= 0 || (main != null && main == android.os.Looper.myLooper())) return;
        try {
            done.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void takeDaemonSecret() throws Exception {
        DaemonSecretReader reader = daemonSecretReader;
        if (reader == null) return;
        String raw = reader.read(com.overdrive.app.util.ScratchRoot.path(DAEMON_SECRET_NAME));
        if (raw == null || raw.trim().isEmpty()) return;
        AuthState read = AuthState.fromJson(new JSONObject(raw.trim()));
        if (read.deviceSecret == null || read.deviceSecret.isEmpty()) return;
        AuthState s = new AuthState();
        s.deviceId = read.deviceId == null || read.deviceId.isEmpty() ? loadDeviceId() : read.deviceId;
        s.deviceSecret = read.deviceSecret;
        s.lastAccess = read.lastAccess;
        int code = postLocal("/api/auth/handoff", jwtFor(s), null);
        if (code != 200) {
            log("Auth: the daemon did not accept its own secret file (" + code + ")");
            return;
        }
        if (writeAppCopy(s)) log("Auth: took the daemon's secret into the app's own copy");
        else log("Auth: couldn't save the daemon's secret to the app's copy");
        publishApp(s, true);
    }

    // True when the last app-side regenerate was done by the daemon itself, which ends every session at once.
    private static volatile boolean regeneratedInDaemon = false;

    public static boolean lastRegenerateEndedSessions() {
        return regeneratedInDaemon;
    }

    // The daemon makes a new secret (it keeps the secret); this call is a session signed with the current one.
    private static String regenerateApp() {
        regeneratedInDaemon = false;
        AuthState cur = getStateApp();
        if (cur == null) return null;
        org.json.JSONObject[] body = new org.json.JSONObject[1];
        int code = postLocal("/api/auth/regenerate", jwtFor(cur), body);
        if (code == 404) return regenerateViaConfig();   // an older daemon: the shared config, as before
        if (code != 200 || body[0] == null) return null;
        String secret = body[0].optString("secret", "");
        if (secret.isEmpty()) return null;
        AuthState s = new AuthState();
        s.deviceId = body[0].optString("deviceId", cur.deviceId);
        s.deviceSecret = secret;
        s.lastAccess = cur.lastAccess;
        if (!writeAppCopy(s)) log("Auth: couldn't save the new secret to the app's copy");
        publishApp(s, true);
        // it holds it now: the daemon stops handing it off
        postLocal("/api/auth/handoff", jwtFor(s), null);
        regeneratedInDaemon = true;
        return s.getDeviceToken();
    }

    private static boolean writeAppCopy(AuthState s) {
        File file = appSecretFile;
        if (file == null) return false;
        File tmp = new File(file.getPath() + ".tmp");
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(s.toJson().toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
                return false;
            }
            cachedOwnMtime = file.lastModified();
            return true;
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
    }

    // POST to the daemon on this head unit with a session; the HTTP status (-1: unreachable), the JSON reply into [into].
    private static int postLocal(String path, String jwt, org.json.JSONObject[] into) {
        if (jwt == null) return -1;
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:8080" + path)
                    .openConnection(java.net.Proxy.NO_PROXY);
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + jwt);
            c.setRequestProperty("Content-Type", "application/json");
            try (java.io.OutputStream o = c.getOutputStream()) {
                o.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (into != null && code == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                }
                into[0] = new JSONObject(sb.toString());
            }
            return code;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ==================== JWT MANAGEMENT ====================

    /**
     * Generate a JWT session token.
     */
    public static String generateJwt() {
        return jwtFor(getState());
    }

    /**
     * A browser sign-in token. In the daemon it carries a {@code jti} tracked by {@link AuthSessions}, so it can be
     * listed and signed out; if that can't be saved it is a plain token, as before.
     */
    public static String generateSessionJwt(String userAgent) {
        AuthState state = getState();
        if (state == null) return null;
        long now = System.currentTimeMillis() / 1000;
        long exp = now + (JWT_EXPIRY_MS / 1000);
        String jti = null;
        if (daemonSide()) {
            try {
                jti = AuthSessions.register(AuthSessions.labelFor(userAgent), now, exp, state.deviceSecret);
            } catch (Throwable t) {
                log("Session tracking unavailable: " + t.getMessage());
            }
        }
        return jwtFor(state, jti, now, exp);
    }

    private static String jwtFor(AuthState state) {
        long now = System.currentTimeMillis() / 1000;
        return jwtFor(state, null, now, now + (JWT_EXPIRY_MS / 1000));
    }

    private static String jwtFor(AuthState state, String jti, long now, long exp) {
        if (state == null) {
            return null;
        }

        try {
            JSONObject header = new JSONObject();
            header.put("alg", JWT_ALGORITHM);
            header.put("typ", "JWT");

            JSONObject payload = new JSONObject();
            payload.put("sub", state.deviceId);
            payload.put("iat", now);
            payload.put("exp", exp);
            if (jti != null) payload.put("jti", jti);

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
            if (!constantTimeEquals(expectedSig, parts[2])) return false;
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
        cachedOwnMtime = 0;
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

            if (!constantTimeEquals(expectedSig, parts[2])) {
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

            // A tracked browser sign-in must still be listed (not signed out).
            if (payload.has("jti") && daemonSide()
                    && !AuthSessions.isActive(payload.optString("jti", ""))) {
                return JwtValidation.failure("Signed out");
            }

            return JwtValidation.success(tokenDeviceId);

        } catch (Exception e) {
            return JwtValidation.failure("Token validation error: " + e.getMessage());
        }
    }

    /** The sign-in id of a valid token, or null (untracked token, or not valid). */
    public static String sessionIdOf(String jwt) {
        if (jwt == null || !validateJwt(jwt).valid) return null;
        try {
            String token = jwt.startsWith("Bearer ") ? jwt.substring(7) : jwt;
            String[] parts = token.split("\\.");
            JSONObject payload = new JSONObject(
                    new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8));
            String jti = payload.optString("jti", "");
            return AuthSessions.validId(jti) ? jti : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Browser sign-ins made with the current secret (daemon side). */
    public static org.json.JSONArray listSessions(String currentId) {
        AuthState state = getState();
        if (state == null || !daemonSide()) return new org.json.JSONArray();
        return AuthSessions.list(currentId, state.deviceSecret);
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
            File file = new File(com.overdrive.app.util.ScratchRoot.path("overdrive_config.json"));
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
