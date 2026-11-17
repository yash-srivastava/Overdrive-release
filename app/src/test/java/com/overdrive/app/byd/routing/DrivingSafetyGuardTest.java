package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.routing.DrivingSafetyGuard.GearReading;

import org.json.JSONObject;
import org.junit.Test;

public class DrivingSafetyGuardTest {

    @Test
    public void accConfidentlyOffIsNeverBlocked() {
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.NOT_PARK, false, true, 40.0));
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.UNKNOWN, false, true, Double.NaN));
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.PARK, false, true, 0.0));
    }

    @Test
    public void unauthoritativeAccFailsClosed() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, false, 0.0));
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.PARK, false, false, 0.0));
    }

    @Test
    public void parkedAndStationaryIsNotBlocked() {
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, true, 0.0));
    }

    @Test
    public void parkWithMissingSpeedFailsClosed() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, true, Double.NaN));
    }

    @Test
    public void rollingInParkIsBlocked() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, true, 2.1));
    }

    @Test
    public void justBelowThresholdIsNotBlocked() {
        assertFalse(DrivingSafetyGuard.isBlocked(GearReading.PARK, true, true, 1.9));
    }

    @Test
    public void freshAccurateGpsOverridesFrozenVehicleSpeed() {
        assertEquals(0.0, DrivingSafetyGuard.preferFreshGpsSpeedKmh(
                60.0, 0.0f, 5.0f, 1_000L, false), 0.0);
        assertEquals(60.0, DrivingSafetyGuard.preferFreshGpsSpeedKmh(
                60.0, 0.0f, 5.0f, 5_001L, false), 0.0);
    }

    @Test
    public void drivingGearIsBlocked() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.NOT_PARK, true, true, 0.0));
    }

    @Test
    public void unknownGearIsBlocked() {
        assertTrue(DrivingSafetyGuard.isBlocked(GearReading.UNKNOWN, true, true, 0.0));
    }

    @Test
    public void gearCodesAreValidatedBeforeTheyReachTheSafetyGate() {
        assertEquals(GearReading.PARK, DrivingSafetyGuard.gearReading(1));
        assertEquals(GearReading.NOT_PARK, DrivingSafetyGuard.gearReading(2));
        assertEquals(GearReading.NOT_PARK, DrivingSafetyGuard.gearReading(6));
        assertEquals(GearReading.UNKNOWN, DrivingSafetyGuard.gearReading(0));
        assertEquals(GearReading.UNKNOWN, DrivingSafetyGuard.gearReading(7));
    }

    @Test
    public void blockReasonDistinguishesNonParkFromUnavailableState() {
        assertEquals("not_park", DrivingSafetyGuard.blockReason(
                GearReading.NOT_PARK, true, true, 0.0));
        assertEquals("gear_unknown", DrivingSafetyGuard.blockReason(
                GearReading.UNKNOWN, true, true, 0.0));
        assertEquals("speed_unknown", DrivingSafetyGuard.blockReason(
                GearReading.PARK, true, true, Double.NaN));
        assertNull(DrivingSafetyGuard.blockReason(
                GearReading.PARK, true, true, 0.0));
    }

    @Test
    public void iviRebootAllowsConfirmedParkWhenOnlySpeedIsUnavailable() {
        assertNull(DrivingSafetyGuard.iviRebootBlockReason("speed_unknown"));
        assertEquals("moving",
                DrivingSafetyGuard.iviRebootBlockReason("moving"));
        assertEquals("not_park",
                DrivingSafetyGuard.iviRebootBlockReason("not_park"));
        assertEquals("gear_unknown",
                DrivingSafetyGuard.iviRebootBlockReason("gear_unknown"));
        assertEquals("state_unknown",
                DrivingSafetyGuard.iviRebootBlockReason("state_unknown"));
    }

    @Test
    public void dilink5NeverLetsStaleAccOffOverrideMotion() {
        assertEquals("not_park", DrivingSafetyGuard.blockReasonDiLink5(
                GearReading.NOT_PARK, false, true, false, 0.0));
        assertEquals("moving", DrivingSafetyGuard.blockReasonDiLink5(
                GearReading.PARK, false, true, false, 3.0));
        assertNull(DrivingSafetyGuard.blockReasonDiLink5(
                GearReading.PARK, false, true, false, 0.0));
        assertEquals("state_unknown", DrivingSafetyGuard.blockReasonDiLink5(
                GearReading.PARK, false, true, false, 0.0, false));
        assertNull(DrivingSafetyGuard.blockReasonDiLink5(
                GearReading.PARK, false, true, true, 0.0));
    }

    @Test
    public void configurableGuardsDefaultOnAndOnlyAcceptBooleans() throws Exception {
        JSONObject settings = new JSONObject();
        assertTrue(DrivingSafetyGuard.isGuardEnabled(
                settings, DrivingSafetyGuard.GUARD_DOOR_LOCKS));

        settings.put(DrivingSafetyGuard.GUARD_DOOR_LOCKS, false);
        assertFalse(DrivingSafetyGuard.isGuardEnabled(
                settings, DrivingSafetyGuard.GUARD_DOOR_LOCKS));

        settings.put(DrivingSafetyGuard.GUARD_DOOR_LOCKS, "false");
        assertTrue(DrivingSafetyGuard.isGuardEnabled(
                settings, DrivingSafetyGuard.GUARD_DOOR_LOCKS));
        assertTrue(DrivingSafetyGuard.isGuardEnabled(settings, "futureGuard"));
    }

    @Test
    public void daemonAdmissionRequiresStrictMatchingBooleanResponse() throws Exception {
        String key = DrivingSafetyGuard.GUARD_SCREEN_MEDIA;
        assertTrue(DrivingSafetyGuard.isDaemonResponseUnblocked(
                new JSONObject()
                        .put("success", true)
                        .put("guard", key)
                        .put("blocked", false),
                key));
        assertFalse(DrivingSafetyGuard.isDaemonResponseUnblocked(
                new JSONObject()
                        .put("success", "true")
                        .put("guard", key)
                        .put("blocked", "false"),
                key));
        assertFalse(DrivingSafetyGuard.isDaemonResponseUnblocked(
                new JSONObject()
                        .put("success", true)
                        .put("guard", DrivingSafetyGuard.GUARD_DISPLAY_POWER)
                        .put("blocked", false),
                key));
    }
}
