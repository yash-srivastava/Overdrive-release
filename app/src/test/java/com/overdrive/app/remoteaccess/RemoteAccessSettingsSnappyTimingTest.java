package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 18 Oct snappy proximity, settings half (S1/S3/S4): the new timing defaults and floors, the
 * {@code timingExplicit} migration marker, the {@code confirmWithVehicle} switch and the two grace
 * settings ({@code relockGraceMs}, {@code postLockQuietMs}), with normal mode forcing every default.
 * Style of {@link RemoteAccessSettingsTest}.
 */
public class RemoteAccessSettingsSnappyTimingTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";
    private static final String[] TIMING = {"windowMs", "dwellMs", "nearWindowMs", "nearDwellMs", "cooldownMs"};
    private static final long[] OLD = {15000, 4000, 4000, 2000, 30000};
    private static final long[] NEW = {6000, 2000, 2000, 1000, 10000};

    // ── S1: defaults, floors, ceilings ───────────────────────────────────────────────────────────

    @Test
    public void defaultsAreTheSnappyTimingsAndCarryTheNewSwitchesButNotTheMarker() throws Exception {
        JSONObject defaults = RemoteAccessSettings.defaults();
        for (int i = 0; i < TIMING.length; i++) assertEquals(TIMING[i], NEW[i], defaults.getLong(TIMING[i]));
        assertEquals(5000, defaults.getLong("staleMs"));
        assertTrue(defaults.getBoolean(RemoteAccessSettings.CONFIRM_WITH_VEHICLE_KEY));
        assertEquals(10_000L, defaults.getLong(RemoteAccessSettings.RELOCK_GRACE_MS_KEY));
        assertEquals(10_000L, defaults.getLong(RemoteAccessSettings.POST_LOCK_QUIET_MS_KEY));
        assertFalse(defaults.getBoolean("parkedBluetoothSafeZoneEnabled"));
        assertFalse(defaults.has(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
        assertFalse(defaults.has(RemoteAccessSettings.DI5_LOCK_SETTINGS_EXPLICIT_KEY));
        // The settings defaults differ from the evaluators' own constants on purpose: those stay the
        // defaults of the old signatures only.
        assertEquals(15_000L, KeyFobPresenceEvaluator.RELOCK_GRACE_MS);
        assertEquals(15_000L, KeyFobEventEvaluator.POST_LOCK_QUIET_MS);
        // Normal-mode evaluator configuration is exactly the defaults.
        ProximityEvaluator.Config config = RemoteAccessSettings.evaluatorConfig(defaults);
        assertEquals(6000, config.windowMs);
        assertEquals(2000, config.stableMs);
        assertEquals(2000, config.nearWindowMs);
        assertEquals(1000, config.nearStableMs);
        assertEquals(ProximityEvaluator.MIN_CONFIRMING_SAMPLES, config.minStableSamples);
    }

    @Test
    public void newFloorsHoldAndCeilingsAreUnchanged() throws Exception {
        long[] floors = {4000, 1000, 2000, 1000, 5000};
        long[] ceilings = {60000, 30000, 60000, 30000, 300000};
        for (int i = 0; i < TIMING.length; i++) {
            String key = TIMING[i];
            assertEquals(key, floors[i], put(key, floors[i]).getLong(key));
            assertEquals(key, ceilings[i], put(key, ceilings[i]).getLong(key));
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, floors[i] - 1));
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, ceilings[i] + 1));
        }
        // The floors are at least two collector readings at 1 Hz inside each window.
        assertTrue(floors[2] >= 1000L * ProximityEvaluator.MIN_CONFIRMING_SAMPLES);
        assertTrue(floors[0] >= 1000L * ProximityEvaluator.MIN_CONFIRMING_SAMPLES);
    }

    // ── S1: migration through the explicit marker ────────────────────────────────────────────────

    @Test
    public void persistedOldDefaultsWithoutTheMarkerAdoptTheNewDefaults() throws Exception {
        JSONObject persisted = trustedPhone();
        for (int i = 0; i < TIMING.length; i++) persisted.put(TIMING[i], OLD[i]);
        persisted.remove(RemoteAccessSettings.TIMING_EXPLICIT_KEY);
        for (boolean advanced : new boolean[] {false, true}) {
            persisted.put("advancedMode", advanced);
            JSONObject restored = RemoteAccessSettings.fromPersisted(persisted);
            for (int i = 0; i < TIMING.length; i++) assertEquals(TIMING[i], NEW[i], restored.getLong(TIMING[i]));
            assertFalse(restored.has(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
            assertTrue(restored.getBoolean("enabled"));
            assertEquals(PHONE, restored.getString("deviceAddress"));
        }
    }

    @Test
    public void persistedCustomValuesWithoutTheMarkerPersist() throws Exception {
        // A choice made on a build that had no marker yet: it differs from the old default, so it stays.
        long[] custom = {24000, 7000, 9000, 5000, 45000};
        JSONObject persisted = trustedPhone().put("advancedMode", true);
        for (int i = 0; i < TIMING.length; i++) persisted.put(TIMING[i], custom[i]);
        persisted.remove(RemoteAccessSettings.TIMING_EXPLICIT_KEY);
        JSONObject restored = RemoteAccessSettings.fromPersisted(persisted);
        for (int i = 0; i < TIMING.length; i++) assertEquals(TIMING[i], custom[i], restored.getLong(TIMING[i]));
        // Mixed: only the keys still at their old default move.
        JSONObject mixed = trustedPhone().put("advancedMode", true)
                .put("windowMs", 24000).put("dwellMs", 4000).put("nearWindowMs", 4000)
                .put("nearDwellMs", 5000).put("cooldownMs", 30000);
        mixed.remove(RemoteAccessSettings.TIMING_EXPLICIT_KEY);
        JSONObject mixedRestored = RemoteAccessSettings.fromPersisted(mixed);
        assertEquals(24000, mixedRestored.getLong("windowMs"));
        assertEquals(2000, mixedRestored.getLong("dwellMs"));
        assertEquals(2000, mixedRestored.getLong("nearWindowMs"));
        assertEquals(5000, mixedRestored.getLong("nearDwellMs"));
        assertEquals(10000, mixedRestored.getLong("cooldownMs"));
    }

    @Test
    public void chosenValuesWithTheMarkerPersistEvenWhenTheyEqualTheOldDefaults() throws Exception {
        JSONObject persisted = trustedPhone().put("advancedMode", true);
        for (int i = 0; i < TIMING.length; i++) persisted.put(TIMING[i], OLD[i]);
        persisted.put(RemoteAccessSettings.TIMING_EXPLICIT_KEY, true);
        JSONObject restored = RemoteAccessSettings.fromPersisted(persisted);
        for (int i = 0; i < TIMING.length; i++) assertEquals(TIMING[i], OLD[i], restored.getLong(TIMING[i]));
        assertTrue(restored.getBoolean(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
        // The marker rides along a round trip (every generic save persists the merged object).
        JSONObject again = RemoteAccessSettings.fromPersisted(restored);
        assertEquals(30000, again.getLong("cooldownMs"));
        assertTrue(again.getBoolean(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
    }

    @Test
    public void aPutNamingAnyTimingKeySetsTheMarkerAndOtherPutsDoNot() throws Exception {
        JSONObject saved = RemoteAccessSettings.merge(trustedPhone(), new JSONObject().put("notifyPush", true));
        assertFalse(saved.has(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
        for (String key : TIMING) {
            JSONObject marked = RemoteAccessSettings.merge(trustedPhone(), new JSONObject().put(key, 20000));
            assertTrue(key, marked.getBoolean(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
            assertEquals(20000, marked.getLong(key));
            // A deliberately chosen old default survives the read because the marker is set.
            JSONObject oldDefault = RemoteAccessSettings.merge(trustedPhone(),
                    new JSONObject().put(key, oldDefaultOf(key)));
            assertEquals(key, oldDefaultOf(key), RemoteAccessSettings.fromPersisted(oldDefault).getLong(key));
        }
        // The marker itself is a boolean setting: non-booleans are rejected, unknown keys still are too.
        rejected(trustedPhone(), new JSONObject().put(RemoteAccessSettings.TIMING_EXPLICIT_KEY, "true"));
        rejected(trustedPhone(), new JSONObject().put("timingExplicitly", true));
    }

    @Test
    public void aPutOfTimingExplicitFalseResetsAllFiveOnTheNextRead() throws Exception {
        long[] custom = {24000, 7000, 9000, 5000, 45000};
        JSONObject chosen = trustedPhone().put("advancedMode", true);
        for (int i = 0; i < TIMING.length; i++) chosen.put(TIMING[i], custom[i]);
        chosen.put(RemoteAccessSettings.TIMING_EXPLICIT_KEY, true);
        JSONObject reset = RemoteAccessSettings.merge(chosen,
                new JSONObject().put(RemoteAccessSettings.TIMING_EXPLICIT_KEY, false));
        // The save itself keeps the values (the full merged object is persisted) …
        for (int i = 0; i < TIMING.length; i++) assertEquals(custom[i], reset.getLong(TIMING[i]));
        assertFalse(reset.getBoolean(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
        // … the next read applies the defaults to all five, custom or not.
        JSONObject restored = RemoteAccessSettings.fromPersisted(reset);
        for (int i = 0; i < TIMING.length; i++) assertEquals(TIMING[i], NEW[i], restored.getLong(TIMING[i]));
        assertFalse(restored.has(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
        // A reset PUT that also names a timing key is a choice again.
        JSONObject both = RemoteAccessSettings.merge(chosen, new JSONObject()
                .put(RemoteAccessSettings.TIMING_EXPLICIT_KEY, false).put("cooldownMs", 12000));
        assertTrue(both.getBoolean(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
        assertEquals(12000, RemoteAccessSettings.fromPersisted(both).getLong("cooldownMs"));
    }

    @Test
    public void malformedMarkerCountsAsAbsentAndNonNumericOldValuesAreLeftToValidation() throws Exception {
        JSONObject persisted = trustedPhone().put("advancedMode", true).put("cooldownMs", 30000)
                .put("windowMs", 24000).put(RemoteAccessSettings.TIMING_EXPLICIT_KEY, "yes");
        JSONObject restored = RemoteAccessSettings.fromPersisted(persisted);
        assertEquals(10000, restored.getLong("cooldownMs"));
        assertEquals(24000, restored.getLong("windowMs"));
        assertFalse(restored.has(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
        assertTrue(restored.getBoolean("enabled"));
        // A corrupt timing value still revokes the section as before (defaults, master off).
        JSONObject corrupt = trustedPhone().put("cooldownMs", "30000");
        corrupt.remove(RemoteAccessSettings.TIMING_EXPLICIT_KEY);
        assertFalse(RemoteAccessSettings.fromPersisted(corrupt).getBoolean("enabled"));
    }

    @Test
    public void legacyWindowToNearWindowMigrationStillRunsOnWhatSurvived() throws Exception {
        // Pre-arrival-control Advanced section: custom departure timing is copied to the arrival
        // control; an old-default departure value adopts the new default and the arrival control
        // gets its own new default.
        JSONObject legacy = trustedPhone().put("advancedMode", true).put("windowMs", 24000).put("dwellMs", 7000);
        legacy.remove("nearWindowMs");
        legacy.remove("nearDwellMs");
        legacy.remove(RemoteAccessSettings.TIMING_EXPLICIT_KEY);
        JSONObject restored = RemoteAccessSettings.fromPersisted(legacy);
        assertEquals(24000, restored.getLong("nearWindowMs"));
        assertEquals(7000, restored.getLong("nearDwellMs"));
        JSONObject oldDefaults = trustedPhone().put("advancedMode", true).put("windowMs", 15000).put("dwellMs", 4000);
        oldDefaults.remove("nearWindowMs");
        oldDefaults.remove("nearDwellMs");
        oldDefaults.remove(RemoteAccessSettings.TIMING_EXPLICIT_KEY);
        JSONObject restoredDefaults = RemoteAccessSettings.fromPersisted(oldDefaults);
        assertEquals(2000, restoredDefaults.getLong("nearWindowMs"));
        assertEquals(1000, restoredDefaults.getLong("nearDwellMs"));
        assertEquals(6000, restoredDefaults.getLong("windowMs"));
        assertEquals(2000, restoredDefaults.getLong("dwellMs"));
        // effective() fills a missing arrival control from the departure one the same way.
        JSONObject noNear = trustedPhone().put("advancedMode", true).put("windowMs", 24000).put("dwellMs", 7000);
        noNear.remove("nearWindowMs");
        noNear.remove("nearDwellMs");
        JSONObject effective = RemoteAccessSettings.effective(noNear);
        assertEquals(24000, effective.getLong("nearWindowMs"));
        assertEquals(7000, effective.getLong("nearDwellMs"));
        JSONObject noNearDefaults = trustedPhone().put("advancedMode", true);
        noNearDefaults.remove("nearWindowMs");
        noNearDefaults.remove("nearDwellMs");
        assertEquals(2000, RemoteAccessSettings.effective(noNearDefaults).getLong("nearWindowMs"));
        assertEquals(1000, RemoteAccessSettings.effective(noNearDefaults).getLong("nearDwellMs"));
    }

    @Test
    public void normalModeRunsTheNewDefaultsWhateverIsSaved() throws Exception {
        JSONObject saved = trustedPhone();
        for (int i = 0; i < TIMING.length; i++) saved.put(TIMING[i], OLD[i]);
        saved.put(RemoteAccessSettings.TIMING_EXPLICIT_KEY, true);
        JSONObject effective = RemoteAccessSettings.effective(saved);
        for (int i = 0; i < TIMING.length; i++) assertEquals(TIMING[i], NEW[i], effective.getLong(TIMING[i]));
        saved.put("advancedMode", true);
        JSONObject advanced = RemoteAccessSettings.effective(saved);
        for (int i = 0; i < TIMING.length; i++) assertEquals(TIMING[i], OLD[i], advanced.getLong(TIMING[i]));
    }

    // ── S3: confirmWithVehicle ───────────────────────────────────────────────────────────────────

    @Test
    public void confirmWithVehicleDefaultsOnPersistsOffAndIsForcedOnInNormalMode() throws Exception {
        assertTrue(RemoteAccessSettings.confirmWithVehicle(null));
        assertTrue(RemoteAccessSettings.confirmWithVehicle(new JSONObject()));
        assertTrue(RemoteAccessSettings.confirmWithVehicle(RemoteAccessSettings.defaults()));
        JSONObject off = RemoteAccessSettings.merge(trustedPhone(),
                new JSONObject().put(RemoteAccessSettings.CONFIRM_WITH_VEHICLE_KEY, false).put("advancedMode", true));
        assertFalse(off.getBoolean(RemoteAccessSettings.CONFIRM_WITH_VEHICLE_KEY));
        assertFalse(RemoteAccessSettings.confirmWithVehicle(off));
        assertFalse(RemoteAccessSettings.fromPersisted(off).getBoolean(RemoteAccessSettings.CONFIRM_WITH_VEHICLE_KEY));
        // Advanced keeps the choice; normal mode forces the switch on (snappiness comes from the
        // background confirmation, not from dropping it).
        assertFalse(RemoteAccessSettings.confirmWithVehicle(RemoteAccessSettings.effective(off)));
        JSONObject normal = RemoteAccessSettings.merge(off, new JSONObject().put("advancedMode", false));
        assertFalse(normal.getBoolean(RemoteAccessSettings.CONFIRM_WITH_VEHICLE_KEY));
        assertTrue(RemoteAccessSettings.confirmWithVehicle(RemoteAccessSettings.effective(normal)));
        // Turning the switch off does not touch the cloud fallback setting itself.
        assertTrue(RemoteAccessSettings.cloudFallback(off));
        rejected(trustedPhone(), new JSONObject().put(RemoteAccessSettings.CONFIRM_WITH_VEHICLE_KEY, "false"));
        rejected(trustedPhone(), new JSONObject().put(RemoteAccessSettings.CONFIRM_WITH_VEHICLE_KEY, 0));
        // Older persisted sections without the key read as on.
        JSONObject legacy = trustedPhone();
        legacy.remove(RemoteAccessSettings.CONFIRM_WITH_VEHICLE_KEY);
        assertTrue(RemoteAccessSettings.fromPersisted(legacy).getBoolean(RemoteAccessSettings.CONFIRM_WITH_VEHICLE_KEY));
    }

    // ── S4: relock grace and post-lock quiet period ──────────────────────────────────────────────

    @Test
    public void graceAndQuietAreTenSecondsByDefaultBoundedAndForcedInNormalMode() throws Exception {
        for (String key : new String[] {RemoteAccessSettings.RELOCK_GRACE_MS_KEY, RemoteAccessSettings.POST_LOCK_QUIET_MS_KEY}) {
            assertEquals(key, 10_000L, RemoteAccessSettings.defaults().getLong(key));
            assertEquals(key, 3000, put(key, 3000).getLong(key));
            assertEquals(key, 30000, put(key, 30000).getLong(key));
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, 2999));
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, 30001));
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, "10000"));
            rejected(RemoteAccessSettings.defaults(), new JSONObject().put(key, 10000.5));
            JSONObject custom = trustedPhone().put(key, 20000);
            assertEquals(key, 10_000L, RemoteAccessSettings.effective(custom).getLong(key));
            custom.put("advancedMode", true);
            assertEquals(key, 20_000L, RemoteAccessSettings.effective(custom).getLong(key));
            // Persisted round trip keeps the custom value; an older section without the key gets the default.
            assertEquals(key, 20_000L, RemoteAccessSettings.fromPersisted(custom).getLong(key));
            JSONObject legacy = trustedPhone();
            legacy.remove(key);
            assertEquals(key, 10_000L, RemoteAccessSettings.fromPersisted(legacy).getLong(key));
        }
        // Grace values are not evaluator timing: they neither set the marker nor move with it.
        JSONObject graceOnly = RemoteAccessSettings.merge(trustedPhone(),
                new JSONObject().put(RemoteAccessSettings.RELOCK_GRACE_MS_KEY, 5000));
        assertFalse(graceOnly.has(RemoteAccessSettings.TIMING_EXPLICIT_KEY));
        assertEquals(5000, RemoteAccessSettings.fromPersisted(graceOnly).getLong(RemoteAccessSettings.RELOCK_GRACE_MS_KEY));
    }

    @Test
    public void graceAccessorsReadTheSettingsAndFallBackToTheDefaultsOnOddInput() throws Exception {
        assertEquals(10_000L, RemoteAccessSettings.relockGraceMs(null));
        assertEquals(10_000L, RemoteAccessSettings.postLockQuietMs(null));
        assertEquals(10_000L, RemoteAccessSettings.relockGraceMs(new JSONObject()));
        assertEquals(7_000L, RemoteAccessSettings.relockGraceMs(new JSONObject().put("relockGraceMs", 7000)));
        assertEquals(7_000L, RemoteAccessSettings.postLockQuietMs(new JSONObject().put("postLockQuietMs", 7000)));
        assertEquals(10_000L, RemoteAccessSettings.relockGraceMs(new JSONObject().put("relockGraceMs", "7000")));
        assertEquals(10_000L, RemoteAccessSettings.relockGraceMs(new JSONObject().put("relockGraceMs", 1000)));
        assertEquals(10_000L, RemoteAccessSettings.postLockQuietMs(new JSONObject().put("postLockQuietMs", 90_000)));
        assertEquals(10_000L, RemoteAccessSettings.relockGraceMs(RemoteAccessSettings.effective(trustedPhone()
                .put("relockGraceMs", 25_000))));
        assertEquals(25_000L, RemoteAccessSettings.relockGraceMs(RemoteAccessSettings.effective(trustedPhone()
                .put("relockGraceMs", 25_000).put("advancedMode", true))));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static long oldDefaultOf(String key) {
        for (int i = 0; i < TIMING.length; i++) if (TIMING[i].equals(key)) return OLD[i];
        throw new IllegalArgumentException(key);
    }

    private static JSONObject put(String key, long value) throws Exception {
        return RemoteAccessSettings.merge(RemoteAccessSettings.defaults(), new JSONObject().put(key, value));
    }

    private static JSONObject trustedPhone() throws Exception {
        return RemoteAccessSettings.merge(RemoteAccessSettings.defaults(),
                new JSONObject().put("enabled", true).put("deviceAddress", PHONE).put("phones", new JSONArray()
                        .put(new JSONObject().put("address", PHONE).put("name", "Owner").put("enabled", true)
                                .put("nearThreshold", -8).put("awayThreshold", -35))));
    }

    private static void rejected(JSONObject current, JSONObject patch) throws Exception {
        try {
            RemoteAccessSettings.merge(current, patch);
            fail("Expected settings rejection: " + patch);
        } catch (IllegalArgumentException | JSONException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isEmpty());
        }
    }
}
