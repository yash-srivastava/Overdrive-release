package com.overdrive.app.remoteaccess;

import com.overdrive.app.byd.DiLink5LockRail;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.config.BluetoothKeepAlivePolicy;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** Validated, durable settings. Missing or corrupt settings never enable monitoring. */
public final class RemoteAccessSettings {
    public static final String SECTION = "remoteAccess";
    /**
     * Marker: the two DI5 lock keys were saved deliberately (a PUT carried one of them). Every
     * generic save persists the full merged object, so a persisted copy WITHOUT this marker is a
     * build default filled in by an older build, not a choice: {@link #read()} drops such copies so
     * the current defaults apply. Not in {@link #defaults()} (implicitly false); a PUT of
     * {@code di5LockSettingsExplicit=false} resets both keys to their defaults on the next read.
     */
    public static final String DI5_LOCK_SETTINGS_EXPLICIT_KEY = "di5LockSettingsExplicit";
    /**
     * 17 Oct product decision: when a local proximity lock/unlock command is refused or not confirmed
     * by the car's local readback, send the same command once through the BYD cloud and confirm again
     * locally. Actuator only: cloud state never feeds the proximity gate or any confirmation.
     * Default on; rollback = save false.
     */
    public static final String CLOUD_FALLBACK_KEY = "cloudFallback";
    public static final boolean CLOUD_FALLBACK_DEFAULT = true;
    /**
     * 18 Oct (snappy proximity): ask the car to confirm a dispatched proximity command — the
     * driver-door readback window, the legacy unlock re-send and, when the readback does not
     * confirm, the cloud fallback leg (which needs that readback to know it is due). Off = the
     * outcome is recorded as {@code sent} right after the SDK accepted the command and the cloud
     * fallback never runs for the proximity path. Default on so field logs stay diagnosable; the
     * snappiness comes from running the confirmation off the decision monitor, not from skipping it.
     * Advanced-mode setting: normal mode forces it on.
     */
    public static final String CONFIRM_WITH_VEHICLE_KEY = "confirmWithVehicle";
    public static final boolean CONFIRM_WITH_VEHICLE_DEFAULT = true;
    /**
     * B4 relock grace after a proximity unlock and A2 post-lock fob quiet period, as settings
     * (18 Oct; both Advanced, ms, [3000, 30000], default 10 s). The evaluators keep their original
     * 15 s constants as the defaults of their old signatures; the handler passes these values in.
     */
    public static final String RELOCK_GRACE_MS_KEY = "relockGraceMs";
    public static final String POST_LOCK_QUIET_MS_KEY = "postLockQuietMs";
    public static final long RELOCK_GRACE_MS_DEFAULT = 10_000L;
    public static final long POST_LOCK_QUIET_MS_DEFAULT = 10_000L;
    public static final long GRACE_MS_MIN = 3_000L;
    public static final long GRACE_MS_MAX = 30_000L;
    /**
     * 19 Oct: the key fob rail's own unlock trigger, independent of the phones' {@code unlockTrigger}.
     * {@code near} (the default) unlocks on the first fresh detection frame (mask 8) of a locked, off
     * car. {@code away_to_near} first needs the car's own absence frame (mask 4) or the post-lock
     * arming seed; the car emits that frame only after a lock event, so after an install, a restart,
     * a settings reset or a remote power cycle with the car already locked the first approach would
     * not unlock (field log 19 Oct 21:18:38). Values validated like {@code unlockTrigger}.
     * Advanced-mode setting: normal mode forces the default.
     */
    public static final String KEY_FOB_UNLOCK_TRIGGER_KEY = "keyFobUnlockTrigger";
    public static final String KEY_FOB_UNLOCK_TRIGGER_DEFAULT = ProximityEvaluator.UnlockTrigger.NEAR.setting;
    /**
     * Marker: the five evaluator timing keys ({@link #TIMING_KEYS}) were saved deliberately (a PUT
     * carried one of them). Same pattern as {@link #DI5_LOCK_SETTINGS_EXPLICIT_KEY}: every generic
     * save persists the full merged object, so timing values persisted WITHOUT this marker came from
     * an older build. On read, such values equal to the OLD defaults (15000/4000/4000/2000/30000 —
     * values the user never chose) are dropped so the current defaults apply, while values that
     * differ from them (a deliberate choice made before the marker existed) persist. Not in
     * {@link #defaults()} (implicitly false); a PUT of {@code timingExplicit=false} drops all five
     * timing keys on the next read, resetting them to the defaults.
     */
    public static final String TIMING_EXPLICIT_KEY = "timingExplicit";
    /** The five timing keys governed by {@link #TIMING_EXPLICIT_KEY}, and their pre-18-Oct defaults. */
    public static final String[] TIMING_KEYS = {"windowMs", "dwellMs", "nearWindowMs", "nearDwellMs", "cooldownMs"};
    private static final long[] LEGACY_TIMING_DEFAULTS = {15000L, 4000L, 4000L, 2000L, 30000L};
    /**
     * 20 Oct (snappy locks): the phones' {@code unlockTrigger} defaults to {@code near}; {@code away_to_near}
     * stays selectable in Advanced mode (normal mode forces {@code near}). Marker that the trigger was saved
     * deliberately (a PUT named it), same pattern as {@link #TIMING_EXPLICIT_KEY}: on read, an unmarked
     * persisted {@code away_to_near} (the old default echoed back by a generic save) is dropped so the
     * default applies; {@code near} and marked values persist. Not in {@link #defaults()} (implicitly false).
     */
    public static final String UNLOCK_TRIGGER_EXPLICIT_KEY = "unlockTriggerExplicit";
    /**
     * 20 Oct: a phone departure with key fob locking configured no longer waits for the key search to
     * confirm absence; a fresh key approach or Present result still vetoes it. On restores that wait.
     * Fob-triggered departures are unchanged. Advanced-mode setting: normal mode forces the default (off).
     */
    public static final String LOCK_WAITS_FOR_KEY_ABSENCE_KEY = "lockWaitsForKeyAbsence";
    public static final boolean LOCK_WAITS_FOR_KEY_ABSENCE_DEFAULT = false;
    /**
     * 20 Oct: completed Absent key searches that confirm absence for fob locking, 1-3; the default 1 locks
     * on the first clean Absent result (see {@link KeyFobPresenceEvaluator#requiredAbsentSamples}).
     * Advanced-mode setting: normal mode forces the default.
     */
    public static final String KEY_FOB_LOCK_SAMPLES_KEY = "keyFobLockSamples";
    public static final int KEY_FOB_LOCK_SAMPLES_DEFAULT = 1;
    /** 20 Oct: default key fob locking window, ms (search cadence 5 s); 10 s before. */
    public static final long KEY_FOB_LOCK_WINDOW_MS_DEFAULT = 5_000L;
    /**
     * Marker: the key fob locking window or result count was saved deliberately (a PUT named
     * {@code keyFobLockWindowMs} or {@link #KEY_FOB_LOCK_SAMPLES_KEY}). On read, an unmarked persisted
     * window of 10000 (the pre-20-Oct default) is dropped so the 5 s default applies; any other value
     * persists. Not in {@link #defaults()} (implicitly false).
     */
    public static final String KEY_FOB_LOCK_EXPLICIT_KEY = "keyFobLockExplicit";
    private static final long LEGACY_KEY_FOB_LOCK_WINDOW_MS = 10_000L;
    private static final String[] BOOLEAN_KEYS = {
            "enabled", "unlockEnabled", "lockEnabled", "dryRun", "keepBluetoothOn", "notifyPush", "notifyTelegram",
            "advancedMode", "keyFobEnabled", "keyFobLockEnabled",
            "restrictLockToOutsideSafeZones", "restrictUnlockToOutsideSafeZones", CLOUD_FALLBACK_KEY,
            DI5_LOCK_SETTINGS_EXPLICIT_KEY, CONFIRM_WITH_VEHICLE_KEY, TIMING_EXPLICIT_KEY,
            UNLOCK_TRIGGER_EXPLICIT_KEY, LOCK_WAITS_FOR_KEY_ABSENCE_KEY, KEY_FOB_LOCK_EXPLICIT_KEY
    };
    private static final String[] NUMBER_KEYS = {
            "nearThreshold", "awayThreshold", "windowMs", "dwellMs", "nearWindowMs", "nearDwellMs",
            "staleMs", "cooldownMs", "keyFobLockWindowMs", RELOCK_GRACE_MS_KEY, POST_LOCK_QUIET_MS_KEY,
            KEY_FOB_LOCK_SAMPLES_KEY
    };
    private RemoteAccessSettings() {}

    public static JSONObject defaults() {
        try {
            return new JSONObject().put("enabled", false)
                    .put("deviceAddress", "").put("deviceName", "")
                    .put("unlockEnabled", false).put("lockEnabled", false).put("dryRun", true)
                    .put("unlockTrigger", ProximityEvaluator.UnlockTrigger.NEAR.setting)
                    .put(KEY_FOB_UNLOCK_TRIGGER_KEY, KEY_FOB_UNLOCK_TRIGGER_DEFAULT)
                    .put("notifyPush", false).put("notifyTelegram", false).put("phones", new JSONArray())
                    .put("advancedMode", false).put("keyFobEnabled", false).put("keyFobLockEnabled", false)
                    .put("restrictLockToOutsideSafeZones", false)
                    .put("restrictUnlockToOutsideSafeZones", false).put("proximitySafeZoneIds", new JSONArray())
                    .put("keyFobLockWindowMs", KEY_FOB_LOCK_WINDOW_MS_DEFAULT)
                    .put(KEY_FOB_LOCK_SAMPLES_KEY, KEY_FOB_LOCK_SAMPLES_DEFAULT)
                    .put(LOCK_WAITS_FOR_KEY_ABSENCE_KEY, LOCK_WAITS_FOR_KEY_ABSENCE_DEFAULT)
                    .put("nearThreshold", -12).put("awayThreshold", -24)
                    // 18 Oct snappy defaults: the collector keeps 1 Hz near/in the transition band
                    // so arrival confirmation gets two readings inside these windows. Clearly-away
                    // phones may use a 3 s cadence, which still fits the departure window and lease.
                    .put("windowMs", 6000).put("dwellMs", 2000).put("staleMs", 5000)
                    .put("nearWindowMs", 2000).put("nearDwellMs", 1000)
                    .put("cooldownMs", 10000).put("keepBluetoothOn", false)
                    .put(CLOUD_FALLBACK_KEY, CLOUD_FALLBACK_DEFAULT)
                    .put(CONFIRM_WITH_VEHICLE_KEY, CONFIRM_WITH_VEHICLE_DEFAULT)
                    .put(RELOCK_GRACE_MS_KEY, RELOCK_GRACE_MS_DEFAULT)
                    .put(POST_LOCK_QUIET_MS_KEY, POST_LOCK_QUIET_MS_DEFAULT)
                    .put(DI5_LOCK_RAILS_KEY, DI5_LOCK_RAILS_DEFAULT)
                    .put(DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_KEY, DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_DEFAULT)
                    .put("revision", 0L);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * DI5 gate rails: canonical comma list over {@code typed-cabin,ota,carsvc}. Default
     * {@code typed-cabin,carsvc} (17 Oct Stage 0: typed raw=0 and OTA -1 on the Di5 car, carsvc
     * usable), derived through the enum's canonicalisation so it equals what a PUT round-trips to.
     */
    public static final String DI5_LOCK_RAILS_KEY = "di5LockRails";
    public static final String DI5_LOCK_RAILS_DEFAULT = DiLink5LockRail.toTags(
            java.util.EnumSet.of(DiLink5LockRail.TYPED_CABIN, DiLink5LockRail.CARSVC));
    /**
     * DI5 carsvc rail: maximum age of the car_service LF lock HAL event, seconds, [60, 86400].
     * Default 24 h (design Q5: the HAL does not re-emit without events, but a fob operation after a
     * long park still advances the timestamp, so the dump's own 2 s freshness and status 0 carry
     * the content-age guarantee). Must stay equal to the collector's default constant.
     */
    public static final String DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_KEY = "di5CarSvcLockMaxEventAgeSec";
    public static final long DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_DEFAULT = 86_400L;
    public static final long DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_MIN = 60L;
    public static final long DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_MAX = 86_400L;
    // Last persisted DI5 value that fell back to its default (null when none); consumers log it once.
    private static volatile String di5PersistedFallback;

    /** Cloud fallback actuator switch of the proximity lock/unlock path; false for a null object. */
    public static boolean cloudFallback(JSONObject settings) {
        return settings != null && settings.optBoolean(CLOUD_FALLBACK_KEY, CLOUD_FALLBACK_DEFAULT);
    }

    /** Vehicle confirmation of dispatched proximity commands; the default (on) for a null object. */
    public static boolean confirmWithVehicle(JSONObject settings) {
        return settings == null || settings.optBoolean(CONFIRM_WITH_VEHICLE_KEY, CONFIRM_WITH_VEHICLE_DEFAULT);
    }

    /**
     * 20 Oct: whether a phone departure waits for confirmed key absence when key fob locking is configured;
     * true (wait) for a null object, the default (no wait) for a missing key.
     */
    public static boolean lockWaitsForKeyAbsence(JSONObject settings) {
        return settings == null
                || settings.optBoolean(LOCK_WAITS_FOR_KEY_ABSENCE_KEY, LOCK_WAITS_FOR_KEY_ABSENCE_DEFAULT);
    }

    /** 20 Oct: completed Absent key searches that confirm absence (1-3); the default for anything odd. */
    public static int keyFobLockSamples(JSONObject settings) {
        Object value = settings == null ? null : settings.opt(KEY_FOB_LOCK_SAMPLES_KEY);
        if (!(value instanceof Number)) return KEY_FOB_LOCK_SAMPLES_DEFAULT;
        double samples = ((Number) value).doubleValue();
        return samples == Math.rint(samples) && samples >= KeyFobPresenceEvaluator.MIN_LOCK_SAMPLES
                && samples <= KeyFobPresenceEvaluator.MAX_LOCK_SAMPLES
                ? (int) samples : KEY_FOB_LOCK_SAMPLES_DEFAULT;
    }

    /** B4 relock grace of a settings object, ms; the settings default for anything odd. */
    public static long relockGraceMs(JSONObject settings) {
        return graceMs(settings, RELOCK_GRACE_MS_KEY, RELOCK_GRACE_MS_DEFAULT);
    }

    /** A2 post-lock fob quiet period of a settings object, ms; the settings default for anything odd. */
    public static long postLockQuietMs(JSONObject settings) {
        return graceMs(settings, POST_LOCK_QUIET_MS_KEY, POST_LOCK_QUIET_MS_DEFAULT);
    }

    private static long graceMs(JSONObject settings, String key, long fallback) {
        Object value = settings == null ? null : settings.opt(key);
        if (!(value instanceof Number)) return fallback;
        long ms = ((Number) value).longValue();
        return ms >= GRACE_MS_MIN && ms <= GRACE_MS_MAX ? ms : fallback;
    }

    /** The enabled DI5 gate rails of a settings object; the default set for anything odd. */
    public static Set<DiLink5LockRail> di5LockRails(JSONObject settings) {
        Set<DiLink5LockRail> rails = settings == null ? null
                : DiLink5LockRail.parseTags(settings.optString(DI5_LOCK_RAILS_KEY, null));
        return rails != null ? rails : DiLink5LockRail.parseTags(DI5_LOCK_RAILS_DEFAULT);
    }

    /** The carsvc HAL-event age bound of a settings object, seconds; the default for anything odd. */
    public static long di5CarSvcLockMaxEventAgeSec(JSONObject settings) {
        Object value = settings == null ? null : settings.opt(DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_KEY);
        Long seconds = di5CarSvcLockMaxEventAgeSecOrNull(value);
        return seconds != null ? seconds : DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_DEFAULT;
    }

    /** Non-null once a persisted DI5 value was replaced by its default in this process. */
    public static String di5PersistedFallbackDetail() { return di5PersistedFallback; }

    static void resetDi5PersistedFallbackForTest() { di5PersistedFallback = null; }

    private static Object normalizeDi5LockRails(Object value, boolean persisted) {
        Set<DiLink5LockRail> rails = value instanceof String
                ? DiLink5LockRail.parseTags((String) value) : null;
        if (rails != null) return DiLink5LockRail.toTags(rails);
        if (!persisted)
            throw new IllegalArgumentException(DI5_LOCK_RAILS_KEY
                    + " must list 1-3 of typed-cabin, ota, carsvc");
        di5PersistedFallback = DI5_LOCK_RAILS_KEY + "=" + value;
        return DI5_LOCK_RAILS_DEFAULT;
    }

    private static Object normalizeDi5CarSvcLockMaxEventAgeSec(Object value, boolean persisted) {
        Long seconds = di5CarSvcLockMaxEventAgeSecOrNull(value);
        if (seconds != null) return seconds;
        if (!persisted)
            throw new IllegalArgumentException(DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_KEY
                    + " must be an integer between " + DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_MIN
                    + " and " + DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_MAX);
        di5PersistedFallback = DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_KEY + "=" + value;
        return DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_DEFAULT;
    }

    private static Long di5CarSvcLockMaxEventAgeSecOrNull(Object value) {
        if (!(value instanceof Number)) return null;
        double d = ((Number) value).doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d)) return null;
        long seconds = ((Number) value).longValue();
        return seconds >= DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_MIN
                && seconds <= DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_MAX ? seconds : null;
    }

    public static JSONObject read() {
        try {
            JSONObject all = UnifiedConfigManager.loadConfig();
            return fromPersisted(all.optJSONObject(SECTION))
                    .put("keepBluetoothOn", BluetoothKeepAlivePolicy.enabled(all));
        } catch (Exception malformed) {
            return defaults();
        }
    }

    static JSONObject fromPersisted(JSONObject raw) {
        try {
            if (raw == null) return defaults();
            JSONObject migrated = new JSONObject(raw.toString());
            // DI5 lock keys without the explicit marker are an older build's defaults echoed back
            // by a generic save, not a choice: drop them so the current defaults apply. A marker
            // that is anything but true counts as absent (a malformed one never revokes the section).
            if (!Boolean.TRUE.equals(migrated.opt(DI5_LOCK_SETTINGS_EXPLICIT_KEY))) {
                migrated.remove(DI5_LOCK_RAILS_KEY);
                migrated.remove(DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_KEY);
                migrated.remove(DI5_LOCK_SETTINGS_EXPLICIT_KEY);
            }
            // 18 Oct: timing keys without the explicit marker came from an older build. An explicit
            // false is a reset request and drops all five; otherwise only values equal to the OLD
            // defaults (never chosen) are dropped, so the current defaults apply to them while a
            // custom value chosen before the marker existed persists.
            // The pre-arrival-control copy below applies only to sections that never had the
            // arrival keys, not to keys dropped here as old defaults.
            boolean hadNearWindow = migrated.has("nearWindowMs"), hadNearDwell = migrated.has("nearDwellMs");
            Object timingMarker = migrated.opt(TIMING_EXPLICIT_KEY);
            if (!Boolean.TRUE.equals(timingMarker)) {
                boolean resetAll = Boolean.FALSE.equals(timingMarker);
                for (int i = 0; i < TIMING_KEYS.length; i++) {
                    Object value = migrated.opt(TIMING_KEYS[i]);
                    if (resetAll || value instanceof Number
                            && ((Number) value).longValue() == LEGACY_TIMING_DEFAULTS[i])
                        migrated.remove(TIMING_KEYS[i]);
                }
                migrated.remove(TIMING_EXPLICIT_KEY);
            }
            // 20 Oct (snappy locks): the phones' trigger and the key fob locking window changed defaults.
            // Unmarked copies of the OLD defaults (away_to_near, 10000) were echoed back by a generic save,
            // not chosen: drop them so the current defaults apply. Any other value persists, and a marker
            // that is anything but true counts as absent (a malformed one never revokes the section).
            if (!Boolean.TRUE.equals(migrated.opt(UNLOCK_TRIGGER_EXPLICIT_KEY))) {
                if (ProximityEvaluator.UnlockTrigger.AWAY_TO_NEAR.setting.equals(migrated.opt("unlockTrigger")))
                    migrated.remove("unlockTrigger");
                migrated.remove(UNLOCK_TRIGGER_EXPLICIT_KEY);
            }
            if (!Boolean.TRUE.equals(migrated.opt(KEY_FOB_LOCK_EXPLICIT_KEY))) {
                Object window = migrated.opt("keyFobLockWindowMs");
                if (window instanceof Number && ((Number) window).longValue() == LEGACY_KEY_FOB_LOCK_WINDOW_MS)
                    migrated.remove("keyFobLockWindowMs");
                migrated.remove(KEY_FOB_LOCK_EXPLICIT_KEY);
            }
            // Existing Advanced timing was shared by both directions. Preserve
            // nondefault custom values; the old defaults adopt faster arrival.
            if (migrated.optBoolean("advancedMode", false)) {
                if (!hadNearWindow && migrated.has("windowMs"))
                    migrated.put("nearWindowMs", migrated.optLong("windowMs") == 15000
                            ? 4000 : migrated.get("windowMs"));
                if (!hadNearDwell && migrated.has("dwellMs"))
                    migrated.put("nearDwellMs", migrated.optLong("dwellMs") == 4000
                            ? 2000 : migrated.get("dwellMs"));
            }
            return merge(defaults(), migrated, true);
        }
        catch (Exception malformed) { return defaults(); }
    }

    public static JSONObject merge(JSONObject current, JSONObject patch) throws Exception {
        return merge(current, patch, false);
    }

    private static JSONObject merge(JSONObject current, JSONObject patch, boolean persisted)
            throws Exception {
        JSONObject result = new JSONObject(current.toString());
        Iterator<String> keys = patch.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = patch.get(key);
            if ("revision".equals(key)) {
                if (persisted) {
                    long revision = integer(value, key);
                    if (revision < 0) throw new IllegalArgumentException("revision must be nonnegative");
                    result.put(key, revision);
                }
                continue; // revisions are exclusively assigned by the server
            }
            if ("phones".equals(key)) {
                if (!(value instanceof JSONArray)) throw new IllegalArgumentException("phones must be a list");
                value = validatePhones((JSONArray) value);
            } else if ("proximitySafeZoneIds".equals(key)) {
                if (!(value instanceof JSONArray))
                    throw new IllegalArgumentException("Proximity safe zones must be a list");
                value = validateSafeZoneIds((JSONArray) value);
            } else if (contains(BOOLEAN_KEYS, key)) {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException(key + " must be a boolean");
            } else if (contains(NUMBER_KEYS, key)) {
                value = integer(value, key);
            } else if ("unlockTrigger".equals(key) || KEY_FOB_UNLOCK_TRIGGER_KEY.equals(key)) {
                value = ProximityEvaluator.UnlockTrigger.fromSetting(value).setting;
            } else if (DI5_LOCK_RAILS_KEY.equals(key)) {
                // PUT: reject; persisted: fall back to the default so the rest of the section survives.
                value = normalizeDi5LockRails(value, persisted);
            } else if (DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_KEY.equals(key)) {
                value = normalizeDi5CarSvcLockMaxEventAgeSec(value, persisted);
            } else if ("deviceAddress".equals(key) || "deviceName".equals(key)) {
                if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be text");
                value = ((String) value).trim();
                if ("deviceAddress".equals(key)) value = ((String) value).toUpperCase(Locale.US);
                if (((String) value).length() > 120) throw new IllegalArgumentException(key + " is too long");
            } else {
                if (persisted) continue;
                throw new IllegalArgumentException("Unknown setting: " + key);
            }
            result.put(key, value);
        }
        // A PUT naming either DI5 lock key is a deliberate choice: mark it so read() keeps the keys.
        if (!persisted && (patch.has(DI5_LOCK_RAILS_KEY) || patch.has(DI5_CARSVC_LOCK_MAX_EVENT_AGE_SEC_KEY)))
            result.put(DI5_LOCK_SETTINGS_EXPLICIT_KEY, true);
        // Likewise a PUT naming any evaluator timing key marks the timing as chosen (18 Oct).
        if (!persisted) for (String key : TIMING_KEYS) if (patch.has(key)) result.put(TIMING_EXPLICIT_KEY, true);
        // 20 Oct: a PUT naming the phones' trigger, or the key fob locking window or result count, marks it chosen.
        if (!persisted && patch.has("unlockTrigger")) result.put(UNLOCK_TRIGGER_EXPLICIT_KEY, true);
        if (!persisted && (patch.has("keyFobLockWindowMs") || patch.has(KEY_FOB_LOCK_SAMPLES_KEY)))
            result.put(KEY_FOB_LOCK_EXPLICIT_KEY, true);
        JSONObject selected = phone(result, result.optString("deviceAddress"));
        if (selected != null) {
            result.put("deviceName", selected.getString("name"));
            if (patch.has("phones") || !patch.has("nearThreshold"))
                result.put("nearThreshold", selected.getInt("nearThreshold"));
            if (patch.has("phones") || !patch.has("awayThreshold"))
                result.put("awayThreshold", selected.getInt("awayThreshold"));
        }
        range(result, "nearThreshold", -127, 20);
        range(result, "awayThreshold", -127, 20);
        if (result.getInt("nearThreshold") <= result.getInt("awayThreshold"))
            throw new IllegalArgumentException("Near threshold must be higher than away threshold");
        // 18 Oct floors; ceilings unchanged. ProximityEvaluator.MIN_CONFIRMING_SAMPLES (two readings
        // per decision) is the hard floor beneath every window.
        range(result, "windowMs", 4000, 60000);
        range(result, "dwellMs", 1000, 30000);
        range(result, "nearWindowMs", 2000, 60000);
        range(result, "nearDwellMs", 1000, 30000);
        range(result, "staleMs", 4000, 15000);
        range(result, "cooldownMs", 5000, 300000);
        range(result, RELOCK_GRACE_MS_KEY, GRACE_MS_MIN, GRACE_MS_MAX);
        range(result, POST_LOCK_QUIET_MS_KEY, GRACE_MS_MIN, GRACE_MS_MAX);
        range(result, "keyFobLockWindowMs", 5000, 300000);
        if (result.getLong("keyFobLockWindowMs") % 5000 != 0)
            throw new IllegalArgumentException("Key fob locking window must use 5-second steps");
        range(result, KEY_FOB_LOCK_SAMPLES_KEY, KeyFobPresenceEvaluator.MIN_LOCK_SAMPLES,
                KeyFobPresenceEvaluator.MAX_LOCK_SAMPLES);
        if (result.getBoolean("keyFobLockEnabled") && !result.getBoolean("keyFobEnabled"))
            throw new IllegalArgumentException("Enable key fob detection before key fob locking");
        if ((result.getBoolean("restrictLockToOutsideSafeZones")
                || result.getBoolean("restrictUnlockToOutsideSafeZones"))
                && result.getJSONArray("proximitySafeZoneIds").length() == 0)
            throw new IllegalArgumentException("Select at least one safe zone for the proximity restriction");
        String address = result.getString("deviceAddress");
        if (!address.isEmpty() && !address.matches("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}"))
            throw new IllegalArgumentException("Select a valid paired Bluetooth device");
        JSONArray phones = result.getJSONArray("phones");
        // Migrate existing single-phone settings without losing calibration.
        if (!patch.has("phones") && phones.length() == 0 && !address.isEmpty()) {
            phones.put(new JSONObject().put("address", address).put("name", result.getString("deviceName"))
                    .put("enabled", true).put("nearThreshold", result.getInt("nearThreshold"))
                    .put("awayThreshold", result.getInt("awayThreshold")));
        }
        if (!address.isEmpty() && !containsPhone(phones, address))
            throw new IllegalArgumentException("Selected phone must be in the trusted list");
        int enabledCount = 0;
        for (int i = 0; i < phones.length(); i++) if (phones.getJSONObject(i).optBoolean("enabled")) enabledCount++;
        if (result.getBoolean("enabled") && enabledCount == 0 && !result.getBoolean("keyFobEnabled"))
            throw new IllegalArgumentException("Enable key fob detection or add and enable a trusted paired phone");
        if (result.getBoolean("enabled") && enabledCount > 0 && address.isEmpty())
            throw new IllegalArgumentException("Select a trusted phone for Bluetooth readings");
        // Legacy partial threshold writes update the selected profile too.
        if (!patch.has("phones") && (patch.has("nearThreshold") || patch.has("awayThreshold")))
            for (int i = 0; i < phones.length(); i++) {
                JSONObject phone = phones.getJSONObject(i);
                if (address.equals(phone.getString("address"))) {
                    phone.put("nearThreshold", result.getInt("nearThreshold"));
                    phone.put("awayThreshold", result.getInt("awayThreshold"));
                }
            }
        return result;
    }

    private static JSONArray validatePhones(JSONArray raw) throws Exception {
        if (raw.length() > 8) throw new IllegalArgumentException("At most eight trusted phones are supported");
        JSONArray result = new JSONArray();
        HashSet<String> addresses = new HashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject phone = raw.getJSONObject(i);
            Object addressValue = phone.opt("address"), nameValue = phone.opt("name");
            if (!(addressValue instanceof String) || !(nameValue instanceof String))
                throw new IllegalArgumentException("Phone address and name must be text");
            String address = ((String) addressValue).trim().toUpperCase(Locale.US);
            String name = ((String) nameValue).trim();
            if (!address.matches("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}") || !addresses.add(address))
                throw new IllegalArgumentException("Each trusted phone needs a unique valid Bluetooth address");
            if (name.length() > 120) throw new IllegalArgumentException("Phone name is too long");
            Object enabled = phone.has("enabled") ? phone.get("enabled") : true;
            if (!(enabled instanceof Boolean)) throw new IllegalArgumentException("Phone enabled must be a boolean");
            long near = integer(phone.has("nearThreshold") ? phone.get("nearThreshold") : -12, "nearThreshold");
            long away = integer(phone.has("awayThreshold") ? phone.get("awayThreshold") : -24, "awayThreshold");
            if (near > 20 || away < -127 || near <= away)
                throw new IllegalArgumentException("Each phone's near threshold must exceed away within [-127,20]");
            result.put(new JSONObject().put("address", address).put("name", name).put("enabled", enabled)
                    .put("nearThreshold", near).put("awayThreshold", away));
        }
        return result;
    }

    private static JSONArray validateSafeZoneIds(JSONArray raw) throws Exception {
        if (raw.length() > 10)
            throw new IllegalArgumentException("At most ten proximity safe zones are supported");
        JSONArray result = new JSONArray();
        HashSet<String> ids = new HashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            Object value = raw.get(i);
            if (!(value instanceof String))
                throw new IllegalArgumentException("Safe zone IDs must be text");
            String id = (String) value;
            if (!id.matches("[A-Za-z0-9_-]{1,120}") || !ids.add(id))
                throw new IllegalArgumentException("Select unique valid safe zone IDs");
            result.put(id);
        }
        return result;
    }

    private static boolean containsPhone(JSONArray phones, String address) throws Exception {
        for (int i = 0; i < phones.length(); i++)
            if (address.equals(phones.getJSONObject(i).getString("address"))) return true;
        return false;
    }

    public static JSONObject phone(JSONObject settings, String address) {
        JSONArray phones = settings.optJSONArray("phones");
        if (phones != null) for (int i = 0; i < phones.length(); i++) {
            JSONObject phone = phones.optJSONObject(i);
            if (phone != null && address.equalsIgnoreCase(phone.optString("address"))) return phone;
        }
        return null;
    }

    /** Normal mode uses measured defaults, independently of retained custom settings. */
    public static JSONObject effective(JSONObject saved) {
        try {
            JSONObject result = new JSONObject(saved.toString());
            if (!result.has("nearWindowMs"))
                result.put("nearWindowMs", result.optBoolean("advancedMode")
                        && result.optLong("windowMs", 6000) != 6000
                        ? result.optLong("windowMs", 6000) : 2000);
            if (!result.has("nearDwellMs"))
                result.put("nearDwellMs", result.optBoolean("advancedMode")
                        && result.optLong("dwellMs", 2000) != 2000
                        ? result.optLong("dwellMs", 2000) : 1000);
            if (!result.optBoolean("advancedMode", false)) {
                JSONObject defaults = defaults();
                for (String key : NUMBER_KEYS) result.put(key, defaults.get(key));
                // Vehicle confirmation is an Advanced switch too: normal mode keeps it on (18 Oct).
                result.put(CONFIRM_WITH_VEHICLE_KEY, CONFIRM_WITH_VEHICLE_DEFAULT);
                // The fob trigger too: normal mode unlocks on the first fresh detection (19 Oct).
                result.put(KEY_FOB_UNLOCK_TRIGGER_KEY, KEY_FOB_UNLOCK_TRIGGER_DEFAULT);
                // 20 Oct: phones unlock on Near and a phone departure does not wait for the key search;
                // the key fob window and result count are NUMBER_KEYS, forced above.
                result.put("unlockTrigger", ProximityEvaluator.UnlockTrigger.NEAR.setting);
                result.put(LOCK_WAITS_FOR_KEY_ABSENCE_KEY, LOCK_WAITS_FOR_KEY_ABSENCE_DEFAULT);
                JSONArray phones = result.getJSONArray("phones");
                for (int i = 0; i < phones.length(); i++)
                    phones.getJSONObject(i).put("nearThreshold", -12).put("awayThreshold", -24);
            }
            return result;
        } catch (Exception malformed) { return defaults(); }
    }

    private static long integer(Object value, String key) {
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
        double d = ((Number) value).doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < 0L - Integer.MAX_VALUE || d > Long.MAX_VALUE)
            throw new IllegalArgumentException(key + " must be an integer");
        return ((Number) value).longValue();
    }

    private static void range(JSONObject value, String key, long min, long max) throws Exception {
        long number = value.getLong(key);
        if (number < min || number > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
    }

    private static boolean contains(String[] values, String key) {
        for (String value : values) if (value.equals(key)) return true;
        return false;
    }

    public static ProximityEvaluator.Config evaluatorConfig(JSONObject value) {
        return new ProximityEvaluator.Config(value.optInt("nearThreshold", -12),
                value.optInt("awayThreshold", -24), value.optLong("windowMs", 6000),
                value.optLong("dwellMs", 2000), value.optLong("staleMs", 5000),
                value.optLong("nearWindowMs", 2000), value.optLong("nearDwellMs", 1000),
                ProximityEvaluator.UnlockTrigger.fromSetting(
                        value.optString("unlockTrigger", "away_to_near")));
    }

    public static ProximityEvaluator.Config evaluatorConfig(JSONObject value, JSONObject phone) {
        return new ProximityEvaluator.Config(phone.optInt("nearThreshold", -12),
                phone.optInt("awayThreshold", -24), value.optLong("windowMs", 6000),
                value.optLong("dwellMs", 2000), value.optLong("staleMs", 5000),
                value.optLong("nearWindowMs", 2000), value.optLong("nearDwellMs", 1000),
                ProximityEvaluator.UnlockTrigger.fromSetting(
                        value.optString("unlockTrigger", "away_to_near")));
    }
}
