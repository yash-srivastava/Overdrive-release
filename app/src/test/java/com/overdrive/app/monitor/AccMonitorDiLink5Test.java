package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccMonitorDiLink5Test {
    @Test
    public void powerModeParserDoesNotConfuseStrWithStartup() {
        assertEquals(1, AccMonitor.classifyDiLink5PowerMode("current = 2=StartUp"));
        assertEquals(1, AccMonitor.classifyDiLink5PowerMode("current = 10=DisPlay on"));
        assertEquals(0, AccMonitor.classifyDiLink5PowerMode("current = 4=Standby"));
        assertEquals(0, AccMonitor.classifyDiLink5PowerMode("current = 5=Str"));
        assertEquals(0, AccMonitor.classifyDiLink5PowerMode("current = 1=PowerMode Pre StartUp"));
        assertEquals(0, AccMonitor.classifyDiLink5PowerMode("current = 12=PowerMode Tod"));
        assertEquals(0, AccMonitor.classifyDiLink5PowerMode("IVI_PM_PRE_STARTUP"));
        assertEquals(0, AccMonitor.classifyDiLink5PowerMode("IVI-PM-STR-SUSPENDING"));
        assertEquals(1, AccMonitor.classifyDiLink5PowerMode("IVI_PM_DISPLAY_ON"));
        assertEquals(1, AccMonitor.classifyDiLink5PowerMode("IVI_PM_STARTUP"));
        assertEquals(0, AccMonitor.classifyDiLink5PowerMode("1"));
        assertEquals(0, AccMonitor.classifyDiLink5PowerMode("12"));
        assertEquals(0, AccMonitor.classifyDiLink5PowerMode("4"));
        assertEquals(1, AccMonitor.classifyDiLink5PowerMode("current = 2"));
        assertEquals(-1, AccMonitor.classifyDiLink5PowerMode("previous = unknown"));

        assertEquals(AccMonitor.DILINK5_POWER_MODE_STARTUP,
                AccMonitor.classifyDiLink5PowerModeKind(
                        "current = 2=PowerMode StartUp"));
        assertEquals(AccMonitor.DILINK5_POWER_MODE_IVI_AWAKE,
                AccMonitor.classifyDiLink5PowerModeKind(
                        "current = 10=PowerMode DisPlay on"));
        assertEquals(AccMonitor.DILINK5_POWER_MODE_IVI_AWAKE,
                AccMonitor.classifyDiLink5PowerModeKind(
                        "current = 3=PowerMode Degraded"));
        assertEquals(AccMonitor.DILINK5_POWER_MODE_OFF,
                AccMonitor.classifyDiLink5PowerModeKind(
                        "current = 4=PowerMode Standby"));
    }

    @Test
    public void weakIviWakeCannotDisarmAConfirmedParkedState() {
        AccMonitor.DiLink5AccObservation weak =
                AccMonitor.DiLink5AccObservation.weakOn(
                        "IVI PowerMode awake",
                        "sysPower=10=PowerMode DisPlay on");
        AccMonitor.DiLink5AccObservation strongOn =
                AccMonitor.DiLink5AccObservation.strong(
                        true, "sys.accanim.status", "accanim=0");
        AccMonitor.DiLink5AccObservation strongOff =
                AccMonitor.DiLink5AccObservation.strong(
                        false, "sys.accanim.status", "accanim=1");
        AccMonitor.DiLink5AccObservation unknown =
                AccMonitor.DiLink5AccObservation.unknown(
                        "conflict", "power sources disagree");

        assertNull(AccMonitor.admitDiLink5AccObservation(
                weak, false));
        assertEquals(Boolean.TRUE,
                AccMonitor.admitDiLink5AccObservation(
                        weak, true));
        assertEquals(Boolean.TRUE,
                AccMonitor.admitDiLink5AccObservation(
                        strongOn, false));
        assertEquals(Boolean.FALSE,
                AccMonitor.admitDiLink5AccObservation(
                        strongOff, true));
        assertNull(AccMonitor.admitDiLink5AccObservation(
                unknown, false));
    }

    @Test
    public void undecidedDiLink5ProbePreservesOnlyAuthoritativeState() {
        // Authoritative parked state remains parked during a cloud/IVI wake.
        assertTrue(AccMonitor.diLink5UndecidedAccOffFallback(
                true, false));
        // Authoritative driving state remains driving during a transient gap.
        assertFalse(AccMonitor.diLink5UndecidedAccOffFallback(
                true, true));
        // A cold process has no OFF authority and therefore stays fail-drive-safe.
        assertFalse(AccMonitor.diLink5UndecidedAccOffFallback(
                false, false));
        assertFalse(AccMonitor.diLink5UndecidedAccOffFallback(
                false, true));
    }

    @Test
    public void sourceResolutionIsDriveSafeAndRejectsLifecycleConflicts() {
        AccMonitor.DiLink5AccObservation accAnimOn =
                AccMonitor.resolveDiLink5AccObservation(
                        1, false, false, true, "test");
        assertEquals(Boolean.TRUE, accAnimOn.accOn);
        assertTrue(accAnimOn.strong);

        AccMonitor.DiLink5AccObservation settlingConflict =
                AccMonitor.resolveDiLink5AccObservation(
                        0, true, false, false, "test");
        assertNull(settlingConflict.accOn);

        AccMonitor.DiLink5AccObservation parkedIviWake =
                AccMonitor.resolveDiLink5AccObservation(
                        0, false, true, false, "test");
        assertEquals(Boolean.FALSE, parkedIviWake.accOn);
        assertTrue(parkedIviWake.strong);

        AccMonitor.DiLink5AccObservation startup =
                AccMonitor.resolveDiLink5AccObservation(
                        -1, true, false, false, "test");
        assertEquals(Boolean.TRUE, startup.accOn);
        assertTrue(startup.strong);

        AccMonitor.DiLink5AccObservation displayOnly =
                AccMonitor.resolveDiLink5AccObservation(
                        -1, false, true, false, "test");
        assertEquals(Boolean.TRUE, displayOnly.accOn);
        assertFalse(displayOnly.strong);

        AccMonitor.DiLink5AccObservation lifecycleConflict =
                AccMonitor.resolveDiLink5AccObservation(
                        -1, false, true, true, "test");
        assertNull(lifecycleConflict.accOn);

        AccMonitor.DiLink5AccObservation pendingSources =
                AccMonitor.resolveDiLink5AccObservation(
                        -1, false, false, false,
                        "accanim=<empty>, sysPower=<empty>, dumpPower=current 4=PowerMode Standby");
        assertNull(pendingSources.accOn);
        assertTrue(pendingSources.detail.contains("all local power sources unavailable"));
        assertTrue(pendingSources.detail.contains("dumpPower=current 4=PowerMode Standby"));
        assertTrue(pendingSources.summary().contains("raw={all local power sources unavailable; "));
    }

    private static DiLink5IgnitionEvidence.Sample vehicleSample(int power, int ready, int evReady, long atMs) {
        return vehicleSample(power, ready, evReady, atMs, "");
    }

    private static DiLink5IgnitionEvidence.Sample driverReadySample(long atMs) {
        String brake = "event count:56, lastEvent:Property:0x21403a05,status: 0,timestamp:"
                + (atMs * 1_000_000L) + ",zone:0x0,floatValues: [],int32Values: [1]\n";
        return vehicleSample(3, 1, 1, atMs, brake);
    }

    private static DiLink5IgnitionEvidence.Sample vehicleSample(
            int power, int ready, int evReady, long atMs, String brake) {
        String dump = brake;
        String[] ids = {"0f", "65", "91"};
        int[] values = {power, ready, evReady};
        for (int i = 0; i < ids.length; i++) {
            dump += "event count:1, lastEvent:Property:0x214050" + ids[i]
                    + ",status: 0,timestamp:" + (atMs * 1_000_000L + i)
                    + ",zone:0x0,floatValues: [],int32Values: [" + values[i] + "]\n";
        }
        return new DiLink5IgnitionEvidence.History().admit(
                DiLink5IgnitionEvidence.parse(dump, atMs * 1_000_000L + 3, "10=PowerMode Display on"));
    }

    @Test public void parkedReadySequenceUsesExistingTransitionAdmissionEvenWhenAlreadyOnAtStart() {
        AccMonitor.IgnitionEdges edges = new AccMonitor.IgnitionEdges();
        AccMonitor.DiLink5AccObservation awake =
                AccMonitor.resolveDiLink5AccObservation(-1, false, true, false, "display on");
        DiLink5IgnitionEvidence.Sample initial = driverReadySample(500);
        assertEquals(Boolean.TRUE, AccMonitor.admitDiLink5AccObservation(
                edges.resolveSealion7(awake, initial, false, 10_000, false),
                false));
        assertEquals(500L, edges.resolveSealion7(awake, initial, false, 10_000, false).vehicleEventMs);
        edges.admit(true, 500);
        assertEquals(Boolean.FALSE, AccMonitor.admitDiLink5AccObservation(
                edges.resolveSealion7(awake, vehicleSample(1, 0, 0, 15_100), false, 20_000, false), true));
        long completedOffAt = edges.resolveSealion7(awake, vehicleSample(1, 0, 0, 15_100), false, 20_000, false).vehicleEventMs;
        edges.admit(false, completedOffAt);
        // READY completed while the OFF dump and IPC were still in flight.
        assertEquals(Boolean.TRUE, vehicleSample(3, 1, 1, 15_150)
                .accOnAfter(edges.opposingEdge(true, false, 20_050)));
        assertNull(AccMonitor.admitDiLink5AccObservation(edges.resolveSealion7(awake, initial, true, 25_000, false), false));

        DiLink5IgnitionEvidence.Sample delayed = vehicleSample(3, 0, 0, 29_000);
        AccMonitor.DiLink5AccObservation settling = edges.resolveSealion7(awake, delayed, true, 30_000, false);
        assertFalse(settling.strong);
        assertNull(AccMonitor.admitDiLink5AccObservation(settling, false));
        // An independent, recent ACC_ON broadcast still corroborates the existing weak IVI source.
        assertEquals(Boolean.TRUE, AccMonitor.admitDiLink5AccObservation(settling, true));

        DiLink5IgnitionEvidence.Sample restarted = driverReadySample(34_000);
        assertEquals(Boolean.TRUE, AccMonitor.admitDiLink5AccObservation(edges.resolveSealion7(awake, restarted, true, 35_000, false), false));
        AccMonitor.DiLink5AccObservation stopped =
                AccMonitor.resolveDiLink5AccObservation(0, false, true, false, "accanim=1");
        assertEquals(Boolean.FALSE, edges.resolveSealion7(stopped, restarted, false, 35_000, true).accOn);
        AccMonitor.DiLink5AccObservation conflict =
                AccMonitor.resolveDiLink5AccObservation(-1, false, true, true, "awake + stopped");
        assertNull(edges.resolveSealion7(conflict, restarted, false, 35_000, true).accOn);
    }

    @Test public void originalOffTimeSurvivesIpcReplayAndColdParkMarkerCannotRenewItsOwnAge() {
        AccMonitor.IgnitionEdges camera = new AccMonitor.IgnitionEdges();
        // Both processes cold, legacy marker present. Its wall timestamp can jump at ignition.
        assertEquals(4_000, camera.opposingEdge(true, true, 4_000));
        assertEquals(4_000, camera.opposingEdge(true, true, 9_000));
        // A surviving sentry replays the actual earlier OFF. The first READY tuple is
        // newer than that OFF even if the camera receives the replay after switching on.
        camera.admit(false, 1_000);
        assertEquals(1_000, camera.opposingEdge(true, true, 10_000));
        assertEquals(Boolean.TRUE, vehicleSample(3, 1, 1, 2_000)
                .accOnAfter(camera.opposingEdge(true, true, 11_000)));
        camera.admit(false, 12_000);
        assertEquals(1_000, camera.since(false));
        camera.admit(true, 13_000);
        camera.opposingEdge(true, false, 14_000);
        assertEquals(15_000, camera.opposingEdge(true, true, 15_000));
    }

    @Test
    public void accAnimUsesExistingZeroOnOneOffContract() {
        assertEquals(1, AccMonitor.classifyAccAnim("0"));
        assertEquals(0, AccMonitor.classifyAccAnim("1"));
        assertEquals(0, AccMonitor.classifyAccAnim("2"));
        assertEquals(-1, AccMonitor.classifyAccAnim(""));
    }

    @Test
    public void safetyFreshnessExpiresWithoutChangingLegacyAuthorityState() {
        assertTrue(AccMonitor.isStateFresh(20_000L, 10_000L, 15_000L));
        assertTrue(AccMonitor.isStateFresh(25_000L, 10_000L, 15_000L));
        assertFalse(AccMonitor.isStateFresh(25_001L, 10_000L, 15_000L));
        assertFalse(AccMonitor.isStateFresh(10_000L, 0L, 15_000L));
        assertEquals(25_000L, AccMonitor.stateFreshUntil(10_000L, 15_000L));
        assertEquals(0L, AccMonitor.stateFreshUntil(0L, 15_000L));
        assertEquals(0L, AccMonitor.stateFreshUntil(
                Long.MAX_VALUE - 10L, 15_000L));
    }

    @Test
    public void dumpPowerSourceAdmitsOffOnlyAfterConsecutiveConfirmations() {
        AccMonitor.resetDiLink5DumpAdmissionForTest();
        // ON is always immediate.
        assertEquals(Boolean.TRUE,
                AccMonitor.admitDiLink5DumpPowerReading(true, 1_000L));
        // First OFF observation is pending, not admitted.
        assertNull(AccMonitor.admitDiLink5DumpPowerReading(false, 2_000L));
        // A rapid re-read (<3s, e.g. CameraDaemon's 200ms retry loop) of the
        // same observation must not count as an independent confirmation.
        assertNull(AccMonitor.admitDiLink5DumpPowerReading(false, 2_200L));
        // An independent reading >=3s later confirms ACC OFF.
        assertEquals(Boolean.FALSE,
                AccMonitor.admitDiLink5DumpPowerReading(false, 7_000L));
        // Confirmed OFF holds steady past the confirmation window — a parked
        // car must not flap back to indeterminate every 30s.
        assertEquals(Boolean.FALSE,
                AccMonitor.admitDiLink5DumpPowerReading(false, 60_000L));
        // A genuine ON resets the streak entirely.
        assertEquals(Boolean.TRUE,
                AccMonitor.admitDiLink5DumpPowerReading(true, 61_000L));
        assertNull(AccMonitor.admitDiLink5DumpPowerReading(false, 62_000L));
        AccMonitor.resetDiLink5DumpAdmissionForTest();
    }

    @Test
    public void dumpPowerSourceStaleStreakRestartsInsteadOfCombiningParkingEvents() {
        AccMonitor.resetDiLink5DumpAdmissionForTest();
        assertNull(AccMonitor.admitDiLink5DumpPowerReading(false, 10_000L));
        // 31s later the partial streak is stale — restart, still pending, so
        // OFF glimpses from different parking events never combine.
        assertNull(AccMonitor.admitDiLink5DumpPowerReading(false, 41_100L));
        // A fresh confirmation inside the new window admits OFF.
        assertEquals(Boolean.FALSE,
                AccMonitor.admitDiLink5DumpPowerReading(false, 46_000L));
        AccMonitor.resetDiLink5DumpAdmissionForTest();
    }

    @Test
    public void onlyLiveMotionSignalsOverrideThePowerMode() {
        assertTrue(AccMonitor.hasDrivingTelemetry(0.1, GearMonitor.GEAR_P));
        assertTrue(AccMonitor.hasDrivingTelemetry(
                Double.NaN, GearMonitor.GEAR_D));
        assertFalse(AccMonitor.hasDrivingTelemetry(
                0.0, GearMonitor.GEAR_P));
        assertFalse(AccMonitor.hasDrivingTelemetry(Double.NaN, -1));
    }
}
