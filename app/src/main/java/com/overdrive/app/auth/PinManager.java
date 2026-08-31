package com.overdrive.app.auth;

import android.util.Base64;
import com.overdrive.app.util.ScratchPaths;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PIN-lock manager for the OverDrive app UI.
 *
 * Scope: this gates {@link com.overdrive.app.ui.MainActivity} only. The
 * cam daemon, AccSentry, surveillance, recording, Telegram alerts, status
 * overlay, and DeterrentActivity all run independently and never read this
 * state — by design. A lock on the user UI must never disable a safety or
 * security flow.
 *
 * Persistence: PIN material lives inside {@link UnifiedConfigManager}'s
 * {@code "pinLock"} section, the same cross-UID-safe store used by
 * AuthManager. We never use EncryptedSharedPreferences (per-UID, splits
 * app process from daemon process — see [[feedback_credentials_unified_pattern]]).
 *
 * Hash: PBKDF2-HMAC-SHA256, 32-byte salt, 120 000 rounds, 32-byte key.
 * Numeric PINs have low entropy by definition; the iteration count is
 * the only thing standing between an attacker with a config-file dump
 * and the secret. 120k rounds takes ~150ms on the BYD head unit (Adreno
 * 610-class CPU), which is invisible at unlock time but adds real cost
 * to bulk attempts.
 *
 * Recovery: a touch-flag at {@code /data/local/tmp/.overdrive_pin_reset}
 * triggers a one-shot wipe of the {@code pinLock} section on next
 * {@link #isEnabled()} call. The flag is then deleted. Designed for the
 * "user forgot their PIN" case — recoverable via ADB shell without a
 * factory reset of the head unit.
 */
public class PinManager {

    private static final String CONFIG_SECTION = "pinLock";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SALT = "salt";
    private static final String KEY_HASH = "hash";
    private static final String KEY_ITERATIONS = "iterations";
    private static final String KEY_AUTOLOCK_MS = "autoLockMs";
    private static final String KEY_FAILED_ATTEMPTS = "failedAttempts";
    private static final String KEY_LOCKOUT_UNTIL = "lockoutUntilMs";

    private static final String RESET_FLAG_FILE = ScratchPaths.path(".overdrive_pin_reset");
    private static final String KEY_LAST_RECOVERY_APPLIED = "lastRecoveryAppliedMs";

    private static final int DEFAULT_ITERATIONS = 120_000;
    private static final int SALT_BYTES = 32;
    private static final int HASH_BYTES = 32;

    /**
     * Public min/max so the UI (PinLockActivity, SettingsSecurityFragment)
     * uses the same numbers as the verifier. Keeping them in three call
     * sites silently diverges over time.
     */
    public static final int MIN_PIN_LEN = 4;
    public static final int MAX_PIN_LEN = 8;

    // Process-local fallback counter so a persist failure doesn't
    // disable the lockout schedule. Reset to mirror persisted state on
    // any successful write.
    private static volatile int volatileFailedAttempts = 0;
    private static volatile long volatileLockoutUntilMs = 0L;

    public static final long DEFAULT_AUTOLOCK_MS = 5L * 60L * 1000L; // 5 minutes

    public enum SetResult { OK, INVALID_LENGTH, NON_NUMERIC, PERSIST_FAILED }
    public enum VerifyResult { OK, WRONG, LOCKED_OUT, NOT_ENABLED }

    public static class LockoutInfo {
        public final boolean lockedOut;
        public final long remainingMs;
        public final int failedAttempts;
        LockoutInfo(boolean lockedOut, long remainingMs, int failedAttempts) {
            this.lockedOut = lockedOut;
            this.remainingMs = remainingMs;
            this.failedAttempts = failedAttempts;
        }
    }

    /**
     * True iff a PIN is set and the feature is enabled. Side effect: on
     * the first call after process start, checks the recovery flag and
     * wipes the section if present.
     */
    public static synchronized boolean isEnabled() {
        maybeApplyRecoveryFlag();
        JSONObject section = loadSection();
        if (section == null) return false;
        return section.optBoolean(KEY_ENABLED, false)
                && !section.optString(KEY_HASH, "").isEmpty()
                && !section.optString(KEY_SALT, "").isEmpty();
    }

    /** Auto-lock timeout in ms, or DEFAULT_AUTOLOCK_MS if unset. 0 = lock immediately on pause. -1 = never. */
    public static synchronized long getAutoLockMs() {
        JSONObject section = loadSection();
        if (section == null || !section.has(KEY_AUTOLOCK_MS)) return DEFAULT_AUTOLOCK_MS;
        return section.optLong(KEY_AUTOLOCK_MS, DEFAULT_AUTOLOCK_MS);
    }

    public static synchronized boolean setAutoLockMs(long ms) {
        JSONObject section = loadSection();
        if (section == null) section = new JSONObject();
        try {
            section.put(KEY_AUTOLOCK_MS, ms);
            return UnifiedConfigManager.updateSection(CONFIG_SECTION, section);
        } catch (Exception e) {
            log("setAutoLockMs failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set or replace the PIN. Caller must have verified the existing PIN
     * via {@link #verify(String)} before calling this for a "change PIN"
     * flow. For a fresh enable (no prior PIN) just call this directly.
     */
    public static synchronized SetResult setPin(String newPin) {
        if (newPin == null || newPin.length() < MIN_PIN_LEN || newPin.length() > MAX_PIN_LEN) {
            return SetResult.INVALID_LENGTH;
        }
        for (int i = 0; i < newPin.length(); i++) {
            if (!Character.isDigit(newPin.charAt(i))) return SetResult.NON_NUMERIC;
        }

        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash;
        try {
            hash = pbkdf2(newPin.toCharArray(), salt, DEFAULT_ITERATIONS, HASH_BYTES);
        } catch (Exception e) {
            log("PBKDF2 derive failed: " + e.getMessage());
            return SetResult.PERSIST_FAILED;
        }

        JSONObject section = loadSection();
        if (section == null) section = new JSONObject();
        try {
            section.put(KEY_ENABLED, true);
            section.put(KEY_SALT, base64(salt));
            section.put(KEY_HASH, base64(hash));
            section.put(KEY_ITERATIONS, DEFAULT_ITERATIONS);
            // Reset attempt counters on a new PIN.
            section.put(KEY_FAILED_ATTEMPTS, 0);
            section.put(KEY_LOCKOUT_UNTIL, 0L);
            // Preserve existing autoLockMs if set; otherwise default.
            if (!section.has(KEY_AUTOLOCK_MS)) {
                section.put(KEY_AUTOLOCK_MS, DEFAULT_AUTOLOCK_MS);
            }

            boolean ok = UnifiedConfigManager.updateSection(CONFIG_SECTION, section);
            // Zero out the secret material as soon as it's persisted.
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(hash, (byte) 0);
            if (!ok) {
                UnifiedConfigManager.forceReload();
                return SetResult.PERSIST_FAILED;
            }
            volatileFailedAttempts = 0;
            volatileLockoutUntilMs = 0L;
            log("PIN set/updated");
            return SetResult.OK;
        } catch (Exception e) {
            log("setPin failed: " + e.getMessage());
            return SetResult.PERSIST_FAILED;
        }
    }

    /**
     * Disable PIN lock entirely. Caller must have verified the current PIN first.
     *
     * Critical detail: {@link UnifiedConfigManager#updateSection(String, JSONObject)}
     * MERGES into the on-disk section rather than replacing it, so we
     * have to explicitly write {@link JSONObject#NULL} for the
     * salt/hash/iterations keys. UnifiedConfigManager does NOT actually
     * drop nulled keys (the existing oem-section pattern at
     * UnifiedConfigManager.kt:853 has the same behavior) — the keys
     * persist on disk as JSON {@code null}, which {@link JSONObject#optString}
     * collapses to {@code ""} so {@link #isEnabled} returns false and
     * no reader sees the prior secret. The original base64-encoded
     * PBKDF2 hash STRING is overwritten — that is the relevant
     * confidentiality goal — even though the {@code "hash":} key
     * itself remains as {@code null}.
     */
    public static synchronized boolean disable() {
        JSONObject delta = new JSONObject();
        try {
            delta.put(KEY_ENABLED, false);
            delta.put(KEY_SALT, JSONObject.NULL);
            delta.put(KEY_HASH, JSONObject.NULL);
            delta.put(KEY_ITERATIONS, JSONObject.NULL);
            delta.put(KEY_FAILED_ATTEMPTS, 0);
            delta.put(KEY_LOCKOUT_UNTIL, 0L);
            // Preserve autoLockMs preference for re-enable convenience.
            long autoLock = getAutoLockMs();
            delta.put(KEY_AUTOLOCK_MS, autoLock);
            boolean ok = UnifiedConfigManager.updateSection(CONFIG_SECTION, delta);
            if (ok) {
                volatileFailedAttempts = 0;
                volatileLockoutUntilMs = 0L;
                log("PIN disabled (PBKDF2 material cleared from disk)");
            }
            return ok;
        } catch (Exception e) {
            log("disable failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Verify a candidate PIN. Updates the failed-attempt counter and
     * lockout window on the persisted section.
     *
     * Lockout schedule (matches the threat model — casual valet/passenger,
     * not a sophisticated attacker; we want recovery friction without
     * making honest typos painful):
     *   attempts 1-4 : no lockout
     *   attempt  5   : 30s
     *   attempt  10  : 5 min
     *   attempt  15+ : 10 min, sustained
     */
    public static synchronized VerifyResult verify(String candidate) {
        if (candidate == null) return VerifyResult.WRONG;
        JSONObject section = loadSection();
        if (section == null || !section.optBoolean(KEY_ENABLED, false)) {
            return VerifyResult.NOT_ENABLED;
        }

        long now = System.currentTimeMillis();
        long lockoutUntil = Math.max(
                section.optLong(KEY_LOCKOUT_UNTIL, 0L),
                volatileLockoutUntilMs
        );
        if (lockoutUntil > now) return VerifyResult.LOCKED_OUT;

        String saltB64 = section.optString(KEY_SALT, "");
        String hashB64 = section.optString(KEY_HASH, "");
        int iterations = section.optInt(KEY_ITERATIONS, DEFAULT_ITERATIONS);
        if (saltB64.isEmpty() || hashB64.isEmpty()) return VerifyResult.NOT_ENABLED;

        byte[] salt = unbase64(saltB64);
        byte[] expectedHash = unbase64(hashB64);
        byte[] candidateHash;
        try {
            candidateHash = pbkdf2(candidate.toCharArray(), salt, iterations, expectedHash.length);
        } catch (Exception e) {
            log("verify PBKDF2 failed: " + e.getMessage());
            return VerifyResult.WRONG;
        }

        boolean match = constantTimeEquals(expectedHash, candidateHash);
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(expectedHash, (byte) 0);
        Arrays.fill(candidateHash, (byte) 0);

        try {
            if (match) {
                section.put(KEY_FAILED_ATTEMPTS, 0);
                section.put(KEY_LOCKOUT_UNTIL, 0L);
                UnifiedConfigManager.updateSection(CONFIG_SECTION, section);
                volatileFailedAttempts = 0;
                volatileLockoutUntilMs = 0L;
                return VerifyResult.OK;
            } else {
                // Tally on persisted counter when present; fall back to
                // the in-memory counter so a UnifiedConfig write failure
                // (cross-UID race, EACCES, etc.) doesn't disable the
                // lockout schedule entirely.
                int persistedAttempts = section.optInt(KEY_FAILED_ATTEMPTS, 0);
                int attempts = Math.max(persistedAttempts, volatileFailedAttempts) + 1;
                section.put(KEY_FAILED_ATTEMPTS, attempts);
                long penalty = penaltyForAttempt(attempts);
                long until = penalty > 0L ? now + penalty : 0L;
                if (until > 0L) section.put(KEY_LOCKOUT_UNTIL, until);
                boolean persisted = UnifiedConfigManager.updateSection(CONFIG_SECTION, section);
                // Always update the volatile mirror — it's the floor that
                // prevents bypassing the lockout via repeated write failures.
                volatileFailedAttempts = attempts;
                volatileLockoutUntilMs = until;
                if (!persisted) {
                    log("verify failed-attempt counter persist failed; relying on volatile fallback (attempts=" + attempts + ")");
                }
                return penalty > 0L ? VerifyResult.LOCKED_OUT : VerifyResult.WRONG;
            }
        } catch (Exception e) {
            log("verify state-write failed: " + e.getMessage());
            // Even on exception, advance the volatile counter so an
            // attacker can't farm verify attempts by induction of write
            // failures. Mirror the in-tree formula at the top of verify
            // so persisted+volatile stay aligned regardless of which
            // path runs (otherwise drift of a few attempts can fire
            // lockouts a tick early — fail-secure, but inconsistent).
            if (!match) {
                int persistedAttempts = section == null ? 0 : section.optInt(KEY_FAILED_ATTEMPTS, 0);
                int n = Math.max(persistedAttempts, volatileFailedAttempts) + 1;
                volatileFailedAttempts = n;
                long penalty = penaltyForAttempt(n);
                if (penalty > 0L) volatileLockoutUntilMs = now + penalty;
            }
            return match ? VerifyResult.OK : VerifyResult.WRONG;
        }
    }

    /**
     * Live snapshot of the lockout state for the unlock UI. Combines the
     * persisted state with the in-memory fallback so a write-failure-
     * induced bypass attempt can't observe a stale lockoutUntil.
     */
    public static synchronized LockoutInfo getLockoutInfo() {
        JSONObject section = loadSection();
        long now = System.currentTimeMillis();
        long persistedUntil = section == null ? 0L : section.optLong(KEY_LOCKOUT_UNTIL, 0L);
        int persistedAttempts = section == null ? 0 : section.optInt(KEY_FAILED_ATTEMPTS, 0);
        long until = Math.max(persistedUntil, volatileLockoutUntilMs);
        int attempts = Math.max(persistedAttempts, volatileFailedAttempts);
        long remaining = Math.max(0L, until - now);
        return new LockoutInfo(remaining > 0L, remaining, attempts);
    }

    private static long penaltyForAttempt(int attempts) {
        if (attempts < 5) return 0L;
        if (attempts < 10) return 30_000L;
        if (attempts < 15) return 5L * 60_000L;
        return 10L * 60_000L;
    }

    // ==================== INTERNAL ====================

    private static JSONObject loadSection() {
        try {
            JSONObject all = UnifiedConfigManager.loadConfig();
            if (all == null) return null;
            return all.optJSONObject(CONFIG_SECTION);
        } catch (Exception e) {
            log("loadSection failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Recovery flag handling. The user creates the flag from a host
     * machine via {@code adb shell touch /data/local/tmp/.overdrive_pin_reset}.
     * The flag is owned by uid {@code shell:shell}; the app process
     * (uid 10xxx) cannot {@code unlink()} it because /data/local/tmp/
     * has the sticky bit. So instead of trying to delete, we record
     * the flag's mtime in the section under {@code lastRecoveryAppliedMs}
     * and only re-apply when a future flag mtime is strictly newer
     * (i.e., the user touched the flag again).
     *
     * Called from every {@link #isEnabled} invocation — there is no
     * process-static guard. Without it, a user who forgot their PIN
     * twice in the same process lifetime (touch flag, recover, set
     * new PIN, forget again, touch flag, expect recovery) would be
     * stuck until the process died. The cost is one
     * {@link File#exists()} + {@code lastModified()} syscall per call,
     * which is dominated by the JSON-cache check that follows anyway.
     *
     * Failure-mode handling: if {@code lastModified()} returns 0 (stat
     * error / FS quirk) we fail closed — do nothing. A repeatedly
     * stuck-mtime that wipes a real PIN every cold start would be
     * worse than a one-time recovery that fails and forces a real ADB
     * fix.
     */
    private static void maybeApplyRecoveryFlag() {
        try {
            File flag = new File(RESET_FLAG_FILE);
            if (!flag.exists()) return;
            long flagMtime = flag.lastModified();
            if (flagMtime <= 0L) {
                log("Recovery flag present but mtime=" + flagMtime + " — skipping wipe (fail closed)");
                return;
            }

            JSONObject section = loadSection();
            long lastApplied = section == null ? 0L : section.optLong(KEY_LAST_RECOVERY_APPLIED, 0L);
            if (flagMtime <= lastApplied) {
                // Already consumed this touch — don't re-wipe.
                return;
            }

            JSONObject cleared = new JSONObject();
            cleared.put(KEY_ENABLED, false);
            cleared.put(KEY_SALT, JSONObject.NULL);
            cleared.put(KEY_HASH, JSONObject.NULL);
            cleared.put(KEY_ITERATIONS, JSONObject.NULL);
            cleared.put(KEY_FAILED_ATTEMPTS, 0);
            cleared.put(KEY_LOCKOUT_UNTIL, 0L);
            cleared.put(KEY_LAST_RECOVERY_APPLIED, flagMtime);
            UnifiedConfigManager.updateSection(CONFIG_SECTION, cleared);
            // Reset the in-memory floor too — otherwise a stale lockout
            // counter survives the wipe and the freshly-recovered (but
            // not-yet-set) PIN looks lockout'd from attempt 1. setPin()
            // resets these on success only, so we have to clear them
            // here to keep the symmetry.
            volatileFailedAttempts = 0;
            volatileLockoutUntilMs = 0L;
            log("PIN reset via recovery flag at " + RESET_FLAG_FILE + " (mtime=" + flagMtime + ")");
        } catch (Exception e) {
            log("recovery flag handling failed: " + e.getMessage());
        }
    }

    private static byte[] pbkdf2(char[] pin, byte[] salt, int iterations, int keyLenBytes) throws Exception {
        SecretKeyFactory kf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(pin, salt, iterations, keyLenBytes * 8);
        try {
            return kf.generateSecret(spec).getEncoded();
        } finally {
            ((PBEKeySpec) spec).clearPassword();
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }

    private static String base64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING);
    }

    private static byte[] unbase64(String s) {
        try {
            return Base64.decode(s, Base64.URL_SAFE | Base64.NO_PADDING);
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static void log(String msg) {
        try {
            CameraDaemon.log("PIN: " + msg);
        } catch (Throwable ignored) {
            android.util.Log.i("PinManager", msg);
        }
    }

    private PinManager() {}
}
