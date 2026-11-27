package com.overdrive.app.monitor;

import org.junit.Test;

import static org.junit.Assert.*;

/** VehicleHal events recorded on a parked Sealion 7 on 27 November 2026. */
public class Sealion7SentryBrakeTest {
    private static final String POWER = "500f", READY = "5065", EV_READY = "5091", BRAKE = "3a05";
    private static final String REMOTE_BRAKE = row(BRAKE, 53, 1018983526172L, 0);
    private static final String SEAT_HEAT = row(POWER, 4, 4767343626721L, 3)
            + row(READY, 6, 4767587591877L, 1) + row(EV_READY, 4, 4767793857138L, 1);
    private static final String WHEEL_HEAT = row(POWER, 6, 5016660738449L, 3)
            + row(READY, 8, 5016932314282L, 1) + row(EV_READY, 6, 5017662036053L, 1);
    private static final String PARKED = row(POWER, 7, 5344264416345L, 1)
            + row(READY, 9, 5344349769001L, 0) + row(EV_READY, 7, 5345793543375L, 0);
    private static final String NORMAL_READY = row(POWER, 8, 5423432654752L, 3)
            + row(READY, 10, 5502016118785L, 1) + row(EV_READY, 8, 5502338237326L, 1);
    private static final String NORMAL_RELEASED_BASELINE = row(BRAKE, 55, 5254173534035L, 0);
    private static final String NORMAL_PRESS = row(BRAKE, 56, 5501636238785L, 1);
    private static final String NORMAL_RELEASE = row(BRAKE, 57, 5507325029095L, 0);

    private final DiLink5IgnitionEvidence.History history = new DiLink5IgnitionEvidence.History();
    private final AccMonitor.IgnitionEdges edges = new AccMonitor.IgnitionEdges();

    private static String row(String id, int count, long atNanos, int value) {
        return "event count:" + count + ", lastEvent:Property:0x2140" + id
                + ",status: 0,timestamp:" + atNanos + ",zone:0x0,floatValues: [],int32Values: ["
                + value + "],int64Values: []\n";
    }

    private AccMonitor.DiLink5AccObservation read(String tuple, String brake, long nowMs) {
        return read(tuple, brake, nowMs, false);
    }

    private AccMonitor.DiLink5AccObservation read(String tuple, String brake, long nowMs, boolean parkedMarker) {
        DiLink5IgnitionEvidence.Sample sample = history.admit(DiLink5IgnitionEvidence.parse(
                tuple + brake, nowMs * 1_000_000L, "10=PowerMode Display on"));
        AccMonitor.DiLink5AccObservation awake =
                AccMonitor.resolveDiLink5AccObservation(-1, false, true, false, "display on");
        return edges.resolveSealion7(awake, sample, parkedMarker, nowMs, false);
    }

    private void assertParked(String tuple, String brake, long nowMs) {
        assertNull(AccMonitor.admitDiLink5AccObservation(read(tuple, brake, nowMs), false));
    }

    private void admit(boolean on, String tuple, String brake, long nowMs) {
        AccMonitor.DiLink5AccObservation result = read(tuple, brake, nowMs);
        assertTrue(result.strong);
        assertEquals(Boolean.valueOf(on), result.accOn);
        edges.admit(on, result.vehicleEventMs);
    }

    @Test public void remoteHeatStaysParkedUntilDriverUsesBrakeWithReadyAlreadyHigh() {
        assertParked(SEAT_HEAT, REMOTE_BRAKE, 4_768_500);
        assertParked(WHEEL_HEAT, REMOTE_BRAKE, 5_019_800);
        // Unlock, door opening and the cluster's green OK all preceded the brake.
        assertParked(WHEEL_HEAT, REMOTE_BRAKE, 5_240_000);
        AccMonitor.DiLink5AccObservation takeover = read(WHEEL_HEAT, row(BRAKE, 54, 5248431571590L, 1), 5_248_680);
        assertTrue(takeover.strong);
        assertEquals(Boolean.TRUE, takeover.accOn);
        assertEquals(5_248_431L, takeover.vehicleEventMs);
        edges.admit(true, takeover.vehicleEventMs);
        admit(true, WHEEL_HEAT, NORMAL_RELEASED_BASELINE, 5_255_520);
        admit(true, WHEEL_HEAT, "", 5_283_330);
        admit(false, PARKED, NORMAL_RELEASED_BASELINE, 5_346_000);
        assertFalse(edges.isOn());
        assertParked(NORMAL_READY, NORMAL_RELEASED_BASELINE, 5_504_000);
    }

    @Test public void ordinaryBrakeStartSurvivesReleaseBeforeTheNextPoll() {
        admit(false, PARKED, NORMAL_RELEASED_BASELINE, 5_346_000);
        // Power rose on entry while both READY bits were still zero.
        assertParked(row(POWER, 8, 5423432654752L, 3)
                + row(READY, 9, 5344349769001L, 0) + row(EV_READY, 7, 5345793543375L, 0),
                NORMAL_RELEASED_BASELINE, 5_424_370);
        // The decision at 20:15:52 saw both brake channels already released.
        admit(true, NORMAL_READY, NORMAL_RELEASE, 5_508_000);
        admit(true, NORMAL_READY, NORMAL_RELEASE, 5_900_000);
    }

    @Test public void observedPressThenReleaseCanCorroborateReadyArrivingAfterThePress() {
        admit(false, PARKED, NORMAL_RELEASED_BASELINE, 5_346_000);
        String settling = row(POWER, 8, 5423432654752L, 3)
                + row(READY, 9, 5344349769001L, 0) + row(EV_READY, 7, 5345793543375L, 0);
        assertParked(settling, NORMAL_PRESS, 5_502_000);
        admit(true, NORMAL_READY, NORMAL_RELEASE, 5_508_000);
    }

    @Test public void coldReleaseAbsentMalformedOrStaleBrakeCannotDisarm() {
        assertParked(NORMAL_READY, NORMAL_RELEASE, 5_508_000);
        assertParked(NORMAL_READY, "", 5_508_001);
        assertParked(NORMAL_READY, NORMAL_PRESS.replace("status: 0", "status: 2"), 5_508_002);
        assertParked(NORMAL_READY, NORMAL_PRESS.replace("zone:0x0", "zone:0x1"), 5_508_003);
        assertParked(NORMAL_READY, row(BRAKE, 99, 6_000_000_000_000L, 1), 5_508_004);
    }

    @Test public void freshBrakeCanTakeOverOlderReadyAfterFirstSeeingAParkedMarker() {
        assertNull(AccMonitor.admitDiLink5AccObservation(
                read(WHEEL_HEAT, REMOTE_BRAKE, 5_240_000, true), false));
        AccMonitor.DiLink5AccObservation result = read(WHEEL_HEAT, row(BRAKE, 54, 5248431571590L, 1),
                5_248_680, true);
        assertTrue(result.strong);
        assertEquals(Boolean.TRUE, result.accOn);
    }

    @Test public void anEarlierDriveCannotDisarmANewerParkedMarker() {
        admit(true, WHEEL_HEAT, row(BRAKE, 54, 5248431571590L, 1), 5_248_680);
        assertNull(AccMonitor.admitDiLink5AccObservation(
                read(WHEEL_HEAT, NORMAL_RELEASED_BASELINE, 5_260_000, true), false));
    }

    @Test public void freshBrakeCannotReviveATupleOlderThanAConfirmedOff() {
        edges.admit(false, 5_100_000);
        assertParked(WHEEL_HEAT, row(BRAKE, 54, 5248431571590L, 1), 5_248_680);
    }

    @Test public void hiddenPressAndReleaseAcrossAnOffBoundaryCannotProveANewDrive() {
        read(WHEEL_HEAT, REMOTE_BRAKE, 5_200_000);
        edges.admit(false, 5_345_793);
        assertParked(NORMAL_READY, row(BRAKE, 55, 5507325029095L, 0), 5_508_000);
    }

    @Test public void aColdProcessCannotTurnAnOldHeldPressIntoAFreshDriverStart() {
        assertParked(NORMAL_READY, NORMAL_PRESS, 5_600_000);
        assertParked(NORMAL_READY, NORMAL_PRESS, 5_601_000);
    }

    @Test public void previousDrivePressAndRestartedCounterCannotCorroborateANewPark() {
        admit(false, PARKED, NORMAL_RELEASED_BASELINE, 5_346_000);
        // The brake count advanced, but its event preceded the known OFF.
        assertParked(NORMAL_READY, row(BRAKE, 56, 5345600000000L, 1), 5_508_000);
        assertParked(NORMAL_READY, row(BRAKE, 1, 5507325029095L, 0), 5_508_001);
    }
}
