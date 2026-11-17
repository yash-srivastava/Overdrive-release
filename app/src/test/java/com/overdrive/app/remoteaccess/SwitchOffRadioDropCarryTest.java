package com.overdrive.app.remoteaccess;

import org.junit.Test;
import static org.junit.Assert.*;

/** Passive recovery, including revocation between the signal and command boundary. */
public class SwitchOffRadioDropCarryTest {
    private static final class Scenario {
        final SwitchOffRadioDropCarry carry = new SwitchOffRadioDropCarry();
        String instance = "collector-a", generation = "mode-a";
        long revision = 1, time, lastDoor = -1;

        boolean read(long at, int power, boolean healthy, boolean connected,
                boolean radioDown, boolean closed) {
            time = at;
            return carry.observe(at, at, power, true, instance, generation, revision,
                    healthy, connected, radioDown, closed, lastDoor, 5_000);
        }

        boolean away(long at) { return read(at, 0, true, false, false, true); }

        void prepare() {
            read(0, 2, true, true, false, false);
            read(1_000, 0, false, false, true, false);
            assertFalse(away(4_000));
        }

        boolean until(long target) {
            boolean adopted = false;
            while (time < target) adopted |= away(Math.min(target, time + 3_000));
            return adopted;
        }
    }

    @Test public void healthyPassiveDisconnectionRestoresAfterThirtySecondsWithoutOurReconnect() {
        Scenario s = new Scenario();
        s.prepare();
        assertFalse(s.until(33_999));
        assertTrue(s.away(34_000));
        assertTrue(s.carry.lockEvidenceValid(34_000, -1));
        assertFalse(s.away(37_000)); // An earned history is not repeatedly adopted.
    }

    @Test public void missedRadioBroadcastStillAllowsRecoveryFromPreShutdownHistory() {
        Scenario s = new Scenario();
        s.read(0, 2, true, true, false, false);
        assertFalse(s.away(1_000));
        assertFalse(s.until(30_999));
        assertTrue(s.away(31_000));
    }

    @Test public void neverConnectedPhoneCannotInventSwitchOffHistory() {
        Scenario s = new Scenario();
        s.read(0, 2, true, false, false, false);
        assertFalse(s.away(1_000));
        assertFalse(s.until(70_000));
        assertFalse(s.carry.requiresLockGuard());
        assertFalse(s.carry.lockEvidenceValid(70_000, -1));
    }

    @Test public void persistentRadioOffOrUnknownCannotRunTheGrace() {
        for (boolean radioDown : new boolean[]{false, true}) {
            Scenario s = new Scenario();
            s.read(0, 2, true, true, false, false);
            for (long at = 1_000; at <= 70_000; at += 3_000)
                assertFalse(s.read(at, 0, false, false, radioDown, true));
            assertFalse(s.carry.lockEvidenceValid(s.time, -1));
        }
    }

    @Test public void unhealthyObservationRequiresAnEntireNewRadioGrace() {
        Scenario s = new Scenario();
        s.prepare();
        assertFalse(s.until(31_000));
        assertFalse(s.read(34_000, 0, false, false, false, true));
        assertFalse(s.away(37_000));
        assertFalse(s.until(66_999));
        assertTrue(s.away(67_000));
    }

    @Test public void longNativeReadGapDoesNotCountAsContinuousDisconnection() {
        Scenario s = new Scenario();
        s.prepare();
        assertFalse(s.until(31_000));
        assertFalse(s.away(41_000));
        assertFalse(s.until(70_999));
        assertTrue(s.away(71_000));
    }

    @Test public void duplicateAndReplayedProofsCannotAdvanceOrRenewRecovery() {
        Scenario s = new Scenario();
        s.prepare();
        for (long now = 5_000; now <= 40_000; now += 1_000) {
            assertFalse(s.carry.observe(4_000, now, 0, true, s.instance, s.generation,
                    s.revision, true, false, false, true, -1, 5_000));
        }
        assertFalse(s.carry.lockEvidenceValid(40_000, -1));
        assertFalse(s.away(41_000));
        assertFalse(s.until(70_999));
        assertTrue(s.away(71_000));
    }

    @Test public void openOrUnknownClosuresRequireFifteenSecondsOfVerifiedClosure() {
        Scenario s = new Scenario();
        s.prepare();
        assertFalse(s.until(31_000));
        s.lastDoor = 34_000;
        assertFalse(s.read(34_000, 0, true, false, false, false));
        assertFalse(s.away(37_000));
        assertFalse(s.until(51_999));
        assertTrue(s.away(52_000));
    }

    @Test public void missingClosureProofNeverRestoresHistory() {
        Scenario s = new Scenario();
        s.prepare();
        for (long at = 7_000; at <= 70_000; at += 3_000)
            assertFalse(s.read(at, 0, true, false, false, false));
        assertFalse(s.carry.lockEvidenceValid(s.time, -1));
    }

    @Test public void doorEdgeAfterAdoptionImmediatelyBlocksThePendingCommand() {
        Scenario s = new Scenario();
        s.prepare();
        assertTrue(s.until(34_000));
        assertTrue(s.carry.lockEvidenceValid(34_001, -1));
        assertFalse(s.carry.lockEvidenceValid(34_001, 34_001));
        s.lastDoor = 34_001;
        assertFalse(s.away(37_000));
        assertTrue(s.carry.takeHistoryRevocation());
        assertFalse(s.carry.takeHistoryRevocation());
        assertFalse(s.carry.lockEvidenceValid(37_000, s.lastDoor));
    }

    @Test public void signalRevocationRemovesGrantedHistoryAndStartsOver() {
        Scenario s = new Scenario();
        s.prepare();
        assertTrue(s.until(34_000));
        s.carry.invalidateProof();
        assertTrue(s.carry.takeHistoryRevocation());
        assertFalse(s.carry.lockEvidenceValid(34_000, -1));
        assertFalse(s.away(37_000));
        assertFalse(s.until(66_999));
        assertTrue(s.away(67_000));
    }

    @Test public void collectorRestartKeepsBoundedHistoryButCannotReuseTheGrace() {
        Scenario s = new Scenario();
        s.prepare();
        assertFalse(s.until(31_000));
        s.instance = "collector-b";
        assertFalse(s.away(34_000));
        assertFalse(s.until(63_999));
        assertTrue(s.away(64_000));
    }

    @Test public void platformOrSettingsChangeCancelsRecoveryAndRevokesHistory() {
        for (boolean changeMode : new boolean[]{false, true}) {
            Scenario s = new Scenario();
            s.prepare();
            assertTrue(s.until(34_000));
            if (changeMode) s.generation = "mode-b"; else s.revision++;
            assertFalse(s.away(37_000));
            assertTrue(s.carry.takeHistoryRevocation());
            assertFalse(s.carry.requiresLockGuard());
            assertFalse(s.until(70_000));
        }
    }

    @Test public void realReconnectOrVehicleOnCancelsTheFallback() {
        for (int power : new int[]{0, 2}) {
            Scenario s = new Scenario();
            s.prepare();
            assertTrue(s.until(34_000));
            assertFalse(s.read(37_000, power, true, true, false, true));
            assertEquals(power > 0, s.carry.takeHistoryRevocation());
            assertFalse(s.carry.requiresLockGuard());
        }
    }

    @Test public void unknownPowerCannotPreserveAnElapsedGrace() {
        Scenario s = new Scenario();
        s.prepare();
        assertFalse(s.until(31_000));
        assertFalse(s.read(34_000, -1, true, false, false, true));
        assertFalse(s.away(37_000));
        assertFalse(s.until(66_999));
        assertTrue(s.away(67_000));
    }

    @Test public void staleFutureAndOutOfOrderEvidenceCannotRestoreHistory() {
        for (int kind = 0; kind < 3; kind++) {
            Scenario s = new Scenario();
            s.prepare();
            assertFalse(s.until(31_000));
            long observed = kind == 0 ? 25_000 : kind == 1 ? 35_000 : 30_999;
            assertFalse(s.carry.observe(observed, 34_000, 0, true, s.instance, s.generation,
                    s.revision, true, false, false, true, -1, 5_000));
            assertFalse(s.carry.lockEvidenceValid(34_000, -1));
            assertFalse(s.away(37_000));
            assertFalse(s.until(66_999));
            assertTrue(s.away(67_000));
        }
    }

    @Test public void TTLAndCollectorRestartsCannotExtendTheOriginalSwitchOffEpisode() {
        Scenario s = new Scenario();
        s.prepare();
        assertTrue(s.until(34_000));
        s.carry.evaluated(true, "confirmed_disconnection");
        assertTrue(s.carry.lockEvidenceValid(34_000, -1));
        assertFalse(s.carry.lockEvidenceValid(301_001, -1));
        s.instance = "collector-b";
        assertFalse(s.away(301_001));
        assertTrue(s.carry.takeHistoryRevocation());
        assertFalse(s.until(340_000));
    }

    @Test public void pendingCompanionPhoneHasItsOwnGraceAndCannotBorrowAnotherPhonesHistory() {
        Scenario first = new Scenario(), companion = new Scenario();
        first.prepare();
        companion.prepare();
        assertTrue(first.until(34_000));
        assertFalse(companion.until(31_000));
        companion.carry.invalidateProof();
        assertFalse(companion.away(34_000));
        assertTrue(first.carry.lockEvidenceValid(34_000, -1));
        assertTrue(companion.carry.requiresLockGuard());
        assertFalse(companion.carry.lockEvidenceValid(34_000, -1));
    }

    @Test public void aFreshNativeConnectionWhileParkedCanRecoverFromALaterRadioOutage() {
        Scenario s = new Scenario();
        s.read(1_000, 0, true, true, false, false);
        assertFalse(s.carry.requiresLockGuard());
        s.read(2_000, 0, false, false, true, false);
        assertTrue(s.carry.requiresLockGuard());
        assertFalse(s.away(5_000));
        assertFalse(s.until(34_999));
        assertTrue(s.away(35_000));
    }

    @Test public void ordinaryHealthyDisconnectionDoesNotAddARecoveryDelay() {
        Scenario s = new Scenario();
        s.read(1_000, 0, true, true, false, false);
        assertFalse(s.away(4_000));
        assertFalse(s.carry.requiresLockGuard());
        // A later outage cannot borrow a contact that was already observed disconnected.
        s.read(5_000, 0, false, false, true, false);
        assertFalse(s.carry.requiresLockGuard());
        assertFalse(s.until(40_000));
    }

    @Test public void expiredParkedContactCannotInventAnOutageRecovery() {
        Scenario s = new Scenario();
        s.read(1_000, 0, true, true, false, false);
        s.read(31_001, 0, false, false, true, false);
        assertFalse(s.carry.requiresLockGuard());
        assertFalse(s.until(70_000));
    }

    @Test public void parkedContactCannotCrossSourceModeOrSettingsChanges() {
        for (int change = 0; change < 3; change++) {
            Scenario s = new Scenario();
            s.read(1_000, 0, true, true, false, false);
            if (change == 0) s.instance = "collector-b";
            else if (change == 1) s.generation = "mode-b";
            else s.revision++;
            s.read(2_000, 0, false, false, true, false);
            assertFalse(s.carry.requiresLockGuard());
            assertFalse(s.until(40_000));
        }
    }

    @Test public void recoveryConsentOrVehicleOnClearsParkedContact() {
        for (boolean vehicleOn : new boolean[]{false, true}) {
            Scenario s = new Scenario();
            s.read(1_000, 0, true, true, false, false);
            s.carry.observe(2_000, 2_000, vehicleOn ? 2 : 0, false,
                    s.instance, s.generation, s.revision, false, false, true, false, -1, 5_000);
            s.read(3_000, 0, false, false, true, false);
            assertFalse(s.carry.requiresLockGuard());
            assertFalse(s.until(40_000));
        }
    }

    @Test public void repeatedUnknownReadsCannotRenewAConsumedParkedContact() {
        Scenario s = new Scenario();
        s.read(1_000, 0, true, true, false, false);
        s.read(2_000, 0, false, false, true, false);
        assertTrue(s.carry.requiresLockGuard());
        for (long at = 5_000; at <= 305_000; at += 3_000)
            assertFalse(s.read(at, 0, false, false, true, false));
        assertFalse(s.carry.requiresLockGuard());
        assertFalse(s.until(340_000));
    }

    @Test public void aFreshConnectedReadResolvesAnInterruptionBeforeOrdinaryDisconnection() {
        Scenario s = new Scenario();
        s.read(1_000, 0, true, true, false, false);
        s.carry.invalidateProof();
        s.read(2_000, 0, true, true, false, false);
        assertFalse(s.away(3_000));
        assertFalse(s.carry.requiresLockGuard());
    }

    @Test public void nativeSamplingGapRearmsRecentParkedContactWithoutAStatusPoll() {
        Scenario s = new Scenario();
        s.read(1_000, 0, true, true, false, false);
        assertFalse(s.away(7_000));
        assertTrue(s.carry.requiresLockGuard());
        assertFalse(s.until(36_999));
        assertTrue(s.away(37_000));
    }

    @Test public void aNativeSamplingGapCannotRearmExpiredParkedContact() {
        Scenario s = new Scenario();
        s.read(1_000, 0, true, true, false, false);
        assertFalse(s.away(31_001));
        assertFalse(s.carry.requiresLockGuard());
        assertFalse(s.until(70_000));
    }
}
