package com.overdrive.app.telegram.config;

import com.overdrive.app.byd.cloud.crypto.CredentialCipher;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

/**
 * Single source of truth for Telegram bot configuration.
 *
 * Backed by {@link UnifiedConfigManager}'s {@code telegram} section, which lives
 * at {@code /data/local/tmp/overdrive_config.json} with 0666 permissions so both
 * the app process (UID 10xxx) and the shell-UID daemons (UID 2000) read and
 * write the same store. The {@code botToken} field is wrapped with
 * {@link CredentialCipher} (AES-GCM, device-bound key) — same posture used for
 * the BYD Cloud raw password.
 *
 * Schema (all fields optional unless noted):
 * <pre>
 *   "telegram": {
 *     "botToken":        "ENC:…",          // encrypted at rest
 *     "botId":           0,
 *     "botUsername":     "",
 *     "botFirstName":    "",
 *     "ownerChatId":     -1,                // long; -1 = unpaired
 *     "ownerUsername":   "",
 *     "ownerFirstName":  "",
 *     "ownerPairedAt":   0,
 *     "pairPin":         "",                // 6-digit, short-lived
 *     "pairPinExpiry":   0,                 // ms epoch
 *     "videoUploads":    false,
 *     "autoStartAccOff": false,
 *     "criticalAlerts":  true,
 *     "connectivity":    false,
 *     "motionText":      true,
 *     "outputDir":       "",
 *     "apkPath":         ""
 *   }
 * </pre>
 *
 * Legacy migration: on first read after upgrade, any keys still living in
 * {@code /data/local/tmp/telegram_config.properties} (or the now-removed
 * {@code telegram_bot_prefs} / {@code telegram_owner_prefs} EncryptedSharedPrefs)
 * are imported. The properties file is then deleted so it can't drift again.
 * Migration is idempotent — guarded by the {@code _migrated} marker.
 */
public final class UnifiedTelegramConfig {

    public static final String SECTION = "telegram";

    // Field names — kept short and JSON-friendly.
    public static final String K_BOT_TOKEN        = "botToken";
    public static final String K_BOT_ID           = "botId";
    public static final String K_BOT_USERNAME     = "botUsername";
    public static final String K_BOT_FIRST_NAME   = "botFirstName";
    public static final String K_OWNER_CHAT_ID    = "ownerChatId";
    public static final String K_OWNER_USERNAME   = "ownerUsername";
    public static final String K_OWNER_FIRST_NAME = "ownerFirstName";
    public static final String K_OWNER_PAIRED_AT  = "ownerPairedAt";
    public static final String K_PAIR_PIN         = "pairPin";
    public static final String K_PAIR_PIN_EXPIRY  = "pairPinExpiry";
    public static final String K_VIDEO_UPLOADS    = "videoUploads";
    public static final String K_AUTO_START       = "autoStartAccOff";
    // Cross-UID mirror of the app-side "Telegram daemon enabled" switch on the
    // Daemons screen. PreferencesManager lives in app-private storage (UID
    // 10xxx) which the shell-UID (2000) AccSentryDaemon cannot read, so the
    // ACC-off auto-start had no way to see that the user had enabled the bot.
    // Distinct from K_AUTO_START: that one means "parked-only" (start on
    // ACC-off, stop again on ACC-on), whereas this means "keep it running".
    public static final String K_DAEMON_ENABLED   = "daemonEnabled";
    public static final String K_CRITICAL_ALERTS  = "criticalAlerts";
    // Dedicated tyre-pressure/leak toggle, mirroring the per-category control
    // Web Push already has. Previously tyre alerts could only be silenced by
    // turning off criticalAlerts, which also killed charging faults, proximity
    // and battery-health alerts.
    public static final String K_TYRE_ALERTS      = "tyreAlerts";
    public static final String K_CONNECTIVITY     = "connectivity";
    public static final String K_MOTION_TEXT      = "motionText";
    // Per-severity tier toggles. Sit alongside the category toggles above so
    // the gate that decides whether a Telegram message goes out for a motion
    // recording can read both "category enabled?" and "tier loud enough?"
    // from one section. Previously these lived on SurveillanceConfig and
    // were only refreshed when the camera daemon restarted, which made
    // toggle changes feel sticky.
    public static final String K_TIER_NOTICES     = "tierNotices";
    public static final String K_TIER_ALERTS      = "tierAlerts";
    public static final String K_TIER_CRITICAL    = "tierCritical";
    public static final String K_OUTPUT_DIR       = "outputDir";
    public static final String K_APK_PATH         = "apkPath";

    private static final String K_MIGRATED = "_migrated";
    /**
     * Separate marker for the surveillance.telegram* → telegram.tier* move.
     * Independent of {@link #K_MIGRATED} (which guards the legacy .properties
     * import) so a device that was already migrated off properties still
     * gets the tier-key copy on first run after this upgrade.
     */
    private static final String K_TIER_MIGRATED = "_tierMigrated";
    private static final String LEGACY_PROPS_PATH =
            ScratchPaths.path("telegram_config.properties");

    /**
     * Per-process latch that suppresses repeated migration attempts within
     * one daemon/app lifetime. The persistent {@code _migrated} marker is
     * the canonical signal across restarts; this volatile is just a fast
     * path so every getter call doesn't re-stat the legacy file.
     *
     * Critically, this also stops a forever-loop when the legacy file
     * exists but is unreadable: the first call sets the latch, the
     * unreadable case bails out, and subsequent calls are O(1).
     */
    private static volatile boolean migrationCheckedThisProcess = false;

    /**
     * Per-process latch for the tier-key migration. Same rationale as
     * {@link #migrationCheckedThisProcess} but for the
     * surveillance.telegramNotices/Alerts/Critical → telegram.tier* copy.
     */
    private static volatile boolean tierMigrationCheckedThisProcess = false;

    private UnifiedTelegramConfig() {}

    // ──────────────────────────── Read ────────────────────────────

    /**
     * Snapshot of the current telegram section. Triggers a one-shot migration
     * from the legacy {@code .properties} file on first read after upgrade.
     */
    public static JSONObject load() {
        migrateLegacyIfNeeded();
        migrateTierKeysIfNeeded();
        return UnifiedConfigManager.getTelegram();
    }

    /** Plain-text bot token (decrypted). Empty string when unset. */
    public static String getBotToken() {
        return CredentialCipher.decrypt(load().optString(K_BOT_TOKEN, ""));
    }

    public static boolean hasBotToken() {
        String t = getBotToken();
        return t != null && !t.isEmpty();
    }

    /**
     * True when a bot-token ciphertext IS stored but it currently decrypts to
     * empty — i.e. the field is set but unrecoverable (device-id file missing,
     * or written under a key we can no longer reproduce). Distinct from "no
     * token configured" so the daemon can log a precise diagnostic instead of
     * the misleading "bot_token not set". Cheap: reads the section once.
     */
    public static boolean botTokenPresentButUndecryptable() {
        String cipher = load().optString(K_BOT_TOKEN, "");
        if (cipher.isEmpty()) return false;
        String plain = CredentialCipher.decrypt(cipher);
        return plain == null || plain.isEmpty();
    }

    /**
     * One-shot upgrade: if the stored bot token was written under the LEGACY
     * firmware-fingerprint-bound key, re-encrypt it under the stable
     * (device-id-only) key so a future OTA can't strand it. No-op when there's
     * no token, when it's already stable, or when it can't be decrypted at all.
     * Returns true only when a re-encrypt write actually happened.
     *
     * <p>Must be called from a UID that can write the config (the UID-2000
     * daemon path); a failed write is harmless — decrypt() keeps reading the
     * legacy value via its fallback, and the next privileged run retries.
     */
    public static boolean reEncryptBotTokenIfLegacy() {
        // Shared single-key-delta + CAS upgrade (legacy fingerprint key →
        // stable device-id key). The helper re-reads fresh and bails if the
        // token changed under us (concurrent clear/rotate), and writes only the
        // botToken key so other telegram fields aren't clobbered. Returns false
        // when nothing should change (not legacy / unrecoverable / fail-open
        // encrypt guard inside upgradeToStableOrNull).
        return com.overdrive.app.byd.cloud.crypto.CredentialUpgrade
                .reEncryptKeyIfLegacy(SECTION, K_BOT_TOKEN);
    }

    public static long getOwnerChatId() {
        return load().optLong(K_OWNER_CHAT_ID, -1);
    }

    public static boolean hasOwner() {
        return getOwnerChatId() > 0;
    }

    public static String getOwnerUsername() {
        return load().optString(K_OWNER_USERNAME, "");
    }

    public static String getOwnerFirstName() {
        return load().optString(K_OWNER_FIRST_NAME, "");
    }

    public static long getOwnerPairedAt() {
        return load().optLong(K_OWNER_PAIRED_AT, 0);
    }

    public static String getBotUsername() {
        return load().optString(K_BOT_USERNAME, "");
    }

    public static String getBotFirstName() {
        return load().optString(K_BOT_FIRST_NAME, "");
    }

    public static long getBotId() {
        return load().optLong(K_BOT_ID, -1);
    }

    public static String getPairPin() {
        return load().optString(K_PAIR_PIN, "");
    }

    public static long getPairPinExpiry() {
        return load().optLong(K_PAIR_PIN_EXPIRY, 0);
    }

    public static boolean isVideoUploads() {
        return load().optBoolean(K_VIDEO_UPLOADS, false);
    }

    public static boolean isAutoStartAccOff() {
        return load().optBoolean(K_AUTO_START, false);
    }

    /**
     * Cross-UID mirror of the Daemons-screen enable switch. Read by
     * AccSentryDaemon (shell UID 2000) which cannot see the app's private
     * SharedPreferences. Defaults false so a never-toggled install behaves
     * exactly as before.
     */
    public static boolean isDaemonEnabled() {
        return load().optBoolean(K_DAEMON_ENABLED, false);
    }

    /**
     * True when the bot should be (re)started on the ACC-off edge — either the
     * parked-only auto-start toggle or the always-on Daemons-screen switch.
     */
    public static boolean shouldStartOnAccOff() {
        JSONObject c = load();
        return c.optBoolean(K_AUTO_START, false) || c.optBoolean(K_DAEMON_ENABLED, false);
    }

    public static boolean isCriticalAlerts() {
        return load().optBoolean(K_CRITICAL_ALERTS, true);
    }

    /**
     * Tyre pressure / air-leak Telegram alerts. Default ON to match the
     * pre-existing behaviour (they used to ride criticalAlerts, itself default
     * ON), so nobody silently loses tyre alerts on upgrade.
     */
    public static boolean isTyreAlerts() {
        return load().optBoolean(K_TYRE_ALERTS, true);
    }

    public static boolean isConnectivity() {
        return load().optBoolean(K_CONNECTIVITY, false);
    }

    public static boolean isMotionText() {
        return load().optBoolean(K_MOTION_TEXT, true);
    }

    /**
     * Per-severity tier filters. Mirror the push tier toggles and decide
     * whether a Telegram motion message of a given severity is suppressed.
     * Defaults match the legacy SurveillanceConfig defaults (notices=off,
     * alerts=on, critical=on) so an upgrade with no migration data still
     * behaves the same way.
     */
    public static boolean isTierNotices()  { return load().optBoolean(K_TIER_NOTICES, false); }
    public static boolean isTierAlerts()   { return load().optBoolean(K_TIER_ALERTS, true);  }
    public static boolean isTierCritical() { return load().optBoolean(K_TIER_CRITICAL, true); }

    public static String getOutputDir() {
        return load().optString(K_OUTPUT_DIR, "");
    }

    public static String getApkPath() {
        return load().optString(K_APK_PATH, "");
    }

    // ──────────────────────────── Write ────────────────────────────

    /**
     * Persist a token alongside its bot identity. Token is encrypted before
     * write; passing {@code null} or empty for {@code token} clears it (and
     * its bot identity).
     */
    public static boolean setBotToken(String token, long botId,
                                      String botUsername, String botFirstName) {
        JSONObject delta = new JSONObject();
        try {
            if (token == null || token.isEmpty()) {
                delta.put(K_BOT_TOKEN, "");
                delta.put(K_BOT_ID, -1);
                delta.put(K_BOT_USERNAME, "");
                delta.put(K_BOT_FIRST_NAME, "");
            } else {
                String enc = CredentialCipher.encrypt(token);
                // encrypt() is fail-open (returns plaintext on JCE error). Never
                // persist a bare token to the world-readable 0666 config —
                // abort the save instead (same invariant as the migration paths).
                if (!CredentialCipher.isEncrypted(enc)) return false;
                delta.put(K_BOT_TOKEN, enc);
                delta.put(K_BOT_ID, botId);
                delta.put(K_BOT_USERNAME, botUsername == null ? "" : botUsername);
                delta.put(K_BOT_FIRST_NAME, botFirstName == null ? "" : botFirstName);
            }
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    /** Persist owner info. Pass {@code chatId <= 0} via {@link #clearOwner()}. */
    public static boolean setOwner(long chatId, String username, String firstName,
                                   long pairedAt) {
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_OWNER_CHAT_ID, chatId);
            delta.put(K_OWNER_USERNAME, username == null ? "" : username);
            delta.put(K_OWNER_FIRST_NAME, firstName == null ? "" : firstName);
            delta.put(K_OWNER_PAIRED_AT, pairedAt);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean clearOwner() {
        // Also nukes any in-flight pair PIN so a stale code can't bind a new owner.
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_OWNER_CHAT_ID, -1);
            delta.put(K_OWNER_USERNAME, "");
            delta.put(K_OWNER_FIRST_NAME, "");
            delta.put(K_OWNER_PAIRED_AT, 0);
            delta.put(K_PAIR_PIN, "");
            delta.put(K_PAIR_PIN_EXPIRY, 0);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean setPairPin(String pin, long expiryMs) {
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_PAIR_PIN, pin == null ? "" : pin);
            delta.put(K_PAIR_PIN_EXPIRY, expiryMs);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean clearPairPin() {
        return setPairPin("", 0);
    }

    /**
     * Idempotent self-heal: if there's no bot token, drop any leftover
     * bot identity, owner, and PIN. Without this, a user who landed in
     * the inconsistent "no token but bot info present" state (legacy
     * migration imported username/first_name without a token, or a
     * partial clear failed mid-write) sees the HTML page render bot
     * info while the integrations card simultaneously says "Not set
     * up" — confusing UX. Returns true if a write happened, false if
     * the section was already clean.
     */
    public static boolean clearOrphanIdentityIfTokenMissing() {
        JSONObject section = load();
        // hasBotToken would re-derive via decrypt — cheaper to check the
        // raw ciphertext field directly here.
        String tokenCipher = section.optString(K_BOT_TOKEN, "");
        if (!tokenCipher.isEmpty()) return false;
        // Anything to clean?
        boolean dirty = section.optLong(K_BOT_ID, -1) > 0
                || !section.optString(K_BOT_USERNAME, "").isEmpty()
                || !section.optString(K_BOT_FIRST_NAME, "").isEmpty()
                || section.optLong(K_OWNER_CHAT_ID, -1) > 0
                || !section.optString(K_OWNER_USERNAME, "").isEmpty()
                || !section.optString(K_OWNER_FIRST_NAME, "").isEmpty()
                || !section.optString(K_PAIR_PIN, "").isEmpty();
        if (!dirty) return false;
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_BOT_ID, -1);
            delta.put(K_BOT_USERNAME, "");
            delta.put(K_BOT_FIRST_NAME, "");
            delta.put(K_OWNER_CHAT_ID, -1);
            delta.put(K_OWNER_USERNAME, "");
            delta.put(K_OWNER_FIRST_NAME, "");
            delta.put(K_OWNER_PAIRED_AT, 0);
            delta.put(K_PAIR_PIN, "");
            delta.put(K_PAIR_PIN_EXPIRY, 0);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    /**
     * Wipe everything — token, bot identity, owner, PIN. Notification
     * preferences are preserved (the user's intent for video_uploads etc.
     * outlives a token rotation).
     */
    public static boolean clearAll() {
        JSONObject delta = new JSONObject();
        try {
            delta.put(K_BOT_TOKEN, "");
            delta.put(K_BOT_ID, -1);
            delta.put(K_BOT_USERNAME, "");
            delta.put(K_BOT_FIRST_NAME, "");
            delta.put(K_OWNER_CHAT_ID, -1);
            delta.put(K_OWNER_USERNAME, "");
            delta.put(K_OWNER_FIRST_NAME, "");
            delta.put(K_OWNER_PAIRED_AT, 0);
            delta.put(K_PAIR_PIN, "");
            delta.put(K_PAIR_PIN_EXPIRY, 0);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean setBoolean(String key, boolean value) {
        JSONObject delta = new JSONObject();
        try {
            delta.put(key, value);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean setString(String key, String value) {
        JSONObject delta = new JSONObject();
        try {
            delta.put(key, value == null ? "" : value);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    public static boolean setLaunchPaths(String outputDir, String apkPath) {
        JSONObject delta = new JSONObject();
        try {
            if (outputDir != null) delta.put(K_OUTPUT_DIR, outputDir);
            if (apkPath != null)   delta.put(K_APK_PATH, apkPath);
        } catch (Exception e) {
            return false;
        }
        return UnifiedConfigManager.updateSection(SECTION, delta);
    }

    // ──────────────────────────── Migration ────────────────────────────

    /**
     * One-shot import of any state still sitting in
     * {@code /data/local/tmp/telegram_config.properties}. Idempotent — guarded
     * by a {@code _migrated} marker so re-running is a cheap no-op.
     *
     * The legacy properties file is removed on success. If removal fails (e.g.
     * the calling UID lacks delete permission), we still flip the marker so a
     * later read by the privileged UID can clean up.
     */
    public static synchronized void migrateLegacyIfNeeded() {
        if (migrationCheckedThisProcess) return;

        JSONObject section = UnifiedConfigManager.getTelegram();
        if (section.optBoolean(K_MIGRATED, false)) {
            migrationCheckedThisProcess = true;
            return;
        }

        File legacy = new File(LEGACY_PROPS_PATH);
        if (!legacy.exists()) {
            // Nothing to import. Don't fire a stampMigrated() write just to
            // record that fact — when the app UID can't write to
            // /data/local/tmp, that write fails anyway, and the in-process
            // latch below covers the common case (no legacy file, never
            // had one). On a fresh install the daemon will set the marker
            // when it does its own init.
            migrationCheckedThisProcess = true;
            return;
        }

        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(legacy)) {
            props.load(fis);
        } catch (Exception e) {
            // Can't read this session — set the per-process latch so we
            // don't hammer the FS on every getter. Leave the persistent
            // marker unset so a future startup with working perms (e.g.
            // shell-UID daemon vs app-UID this run) still gets a shot.
            migrationCheckedThisProcess = true;
            return;
        }

        JSONObject delta = new JSONObject();
        try {
            String tok = props.getProperty("bot_token", "");
            if (!tok.isEmpty()) {
                // The legacy file holds plaintext; encrypt on import.
                delta.put(K_BOT_TOKEN, CredentialCipher.encrypt(tok));
                // Bot identity is only meaningful when paired with a token.
                // Importing username/first_name without a token leaves the
                // status endpoint emitting orphan bot info ("Configured as
                // @Foo") even though hasBotToken() returns false — which
                // looks broken to the user. Gate identity import on a
                // present token.
                putIfPresent(props, delta, "bot_username", K_BOT_USERNAME);
                putIfPresent(props, delta, "bot_first_name", K_BOT_FIRST_NAME);
            }

            // The previous TelegramSettingsFragment (pre-refactor) wrote
            // owner_id while the daemon used owner_chat_id. Both could be
            // present from different code paths; prefer chat_id, fall back
            // to id, so users paired only via the native fragment don't
            // lose their owner on upgrade.
            String ownerStr = props.getProperty("owner_chat_id", "");
            if (ownerStr.isEmpty()) ownerStr = props.getProperty("owner_id", "");
            if (!ownerStr.isEmpty()) {
                try { delta.put(K_OWNER_CHAT_ID, Long.parseLong(ownerStr.trim())); }
                catch (NumberFormatException ignored) {}
            }
            putIfPresent(props, delta, "owner_username", K_OWNER_USERNAME);
            putIfPresent(props, delta, "owner_first_name", K_OWNER_FIRST_NAME);

            // A paired_at stamp wasn't written by the legacy daemon. Use
            // file mtime as a best-effort backstop so the UI doesn't show
            // "paired Jan 1 1970".
            if (delta.has(K_OWNER_CHAT_ID) && !section.has(K_OWNER_PAIRED_AT)) {
                delta.put(K_OWNER_PAIRED_AT, legacy.lastModified());
            }

            // PIN — only import if not yet expired, otherwise it's noise.
            String pin = props.getProperty("pair_pin", "");
            long pinExpiry = parseLongOr(props.getProperty("pair_pin_expiry", "0"), 0);
            if (!pin.isEmpty() && pinExpiry > System.currentTimeMillis()) {
                delta.put(K_PAIR_PIN, pin);
                delta.put(K_PAIR_PIN_EXPIRY, pinExpiry);
            }

            putBoolIfPresent(props, delta, "video_uploads",      K_VIDEO_UPLOADS);
            putBoolIfPresent(props, delta, "auto_start_acc_off", K_AUTO_START);
            putBoolIfPresent(props, delta, "critical_alerts",    K_CRITICAL_ALERTS);
            putBoolIfPresent(props, delta, "connectivity",       K_CONNECTIVITY);
            putBoolIfPresent(props, delta, "motion_text",        K_MOTION_TEXT);
            putIfPresent(props, delta, "output_dir", K_OUTPUT_DIR);
            putIfPresent(props, delta, "apk_path",   K_APK_PATH);

            delta.put(K_MIGRATED, true);
        } catch (Exception e) {
            return;
        }

        UnifiedConfigManager.updateSection(SECTION, delta);
        migrationCheckedThisProcess = true;

        // Best-effort delete; if we can't (permission), the marker still
        // suppresses re-import. The next privileged-UID startup will clean
        // up via the cleanup branch below.
        try { legacy.delete(); } catch (Exception ignored) {}
    }

    /**
     * One-shot copy of {@code surveillance.telegramNotices/Alerts/Critical}
     * into {@code telegram.tierNotices/tierAlerts/tierCritical}. Lets users
     * who already configured the per-tier filter on the Sentry settings
     * page keep their choices after the gate moves to the Telegram section.
     *
     * Runs at most once per device (persistent {@code _tierMigrated} marker)
     * and at most once per process (in-memory latch). Idempotent; if the
     * surveillance keys are absent the migration writes an empty delta with
     * just the marker so subsequent reads short-circuit.
     */
    private static synchronized void migrateTierKeysIfNeeded() {
        if (tierMigrationCheckedThisProcess) return;

        JSONObject section = UnifiedConfigManager.getTelegram();
        if (section.optBoolean(K_TIER_MIGRATED, false)) {
            tierMigrationCheckedThisProcess = true;
            return;
        }

        JSONObject delta = new JSONObject();
        try {
            // Only copy keys that already had been written in the surveillance
            // section. A field that was never written there must keep its
            // documented default (false/true/true) — copying optBoolean's
            // implicit false would silently disable critical/alert tiers for
            // users who never touched the toggle.
            JSONObject surveillance = UnifiedConfigManager.getSurveillance();
            if (surveillance.has("telegramNotices")) {
                delta.put(K_TIER_NOTICES, surveillance.optBoolean("telegramNotices", false));
            }
            if (surveillance.has("telegramAlerts")) {
                delta.put(K_TIER_ALERTS, surveillance.optBoolean("telegramAlerts", true));
            }
            if (surveillance.has("telegramCritical")) {
                delta.put(K_TIER_CRITICAL, surveillance.optBoolean("telegramCritical", true));
            }
            delta.put(K_TIER_MIGRATED, true);
        } catch (Exception e) {
            // Latch so we don't hammer the FS on every getter; skip the
            // persistent marker so a future read with healthy state retries.
            tierMigrationCheckedThisProcess = true;
            return;
        }

        UnifiedConfigManager.updateSection(SECTION, delta);
        tierMigrationCheckedThisProcess = true;
    }

    private static void stampMigrated() {
        JSONObject delta = new JSONObject();
        try { delta.put(K_MIGRATED, true); } catch (Exception ignored) {}
        UnifiedConfigManager.updateSection(SECTION, delta);
    }

    private static void putIfPresent(Properties src, JSONObject dst,
                                     String legacyKey, String unifiedKey)
            throws Exception {
        String v = src.getProperty(legacyKey, "");
        if (!v.isEmpty()) dst.put(unifiedKey, v);
    }

    private static void putBoolIfPresent(Properties src, JSONObject dst,
                                         String legacyKey, String unifiedKey)
            throws Exception {
        String v = src.getProperty(legacyKey);
        if (v == null) return;
        dst.put(unifiedKey, "true".equalsIgnoreCase(v.trim()));
    }

    private static long parseLongOr(String s, long fallback) {
        try { return Long.parseLong(s == null ? "" : s.trim()); }
        catch (Exception e) { return fallback; }
    }
}
