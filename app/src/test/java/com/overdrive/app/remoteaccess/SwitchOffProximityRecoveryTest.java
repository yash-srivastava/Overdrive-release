package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.Event.*;
import static com.overdrive.app.remoteaccess.ProximityEvaluator.State.*;

/** Runs passive switch-off recovery through the real native proof parser and proximity evaluator. */
public class SwitchOffProximityRecoveryTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";
    private static final String MODE = "00000000-0000-0000-0000-000000000001";

    private static final class Scenario {
        final SwitchOffRadioDropCarry carry = new SwitchOffRadioDropCarry();
        final ProximityEvaluator evaluator = new ProximityEvaluator(
                new ProximityEvaluator.Config(-12, -24, 15_000, 2_000, 5_000));
        String instance = "collector-a";
        long lastDoor = -1;
        BluetoothConnectionEvidence lastProof;
        boolean lastProofHealthy;
        JSONObject lastSignal;

        ProximityEvaluator.Snapshot read(long at, int power, boolean radioOn,
                boolean connected, Integer raw) throws Exception {
            return read(at, power, radioOn, connected, raw, true);
        }

        ProximityEvaluator.Snapshot unknownNativeRead(long at, int power) throws Exception {
            return read(at, power, false, false, null, false);
        }

        private ProximityEvaluator.Snapshot read(long at, int power, boolean radioOn,
                boolean connected, Integer raw, boolean radioReadKnown) throws Exception {
            BluetoothConnectionEvidence proof = BluetoothConnectionEvidence.parse(new JSONObject()
                    .put("connectionReadKnown", radioOn)
                    .put("connectionState", !radioOn ? "unknown" : connected ? "connected" : "disconnected")
                    .put("radioReadKnown", radioReadKnown).put("radioOn", radioOn)
                    .put("connectionObservedAtElapsedMs", radioOn ? at : -1)
                    .put("connectionModeGeneration", radioOn ? MODE : ""));
            boolean eligible = power == 0;
            boolean healthy = proof.healthyAt(at, 5_000);
            // Unknown shutdown reads have no native mode tag; the committed car mode still scopes the episode.
            boolean adopt = carry.observe(healthy ? proof.observedAtElapsedMs : at, at, power,
                    eligible, instance, MODE, 1, healthy, connected, proof.radioReadKnown && !proof.radioOn,
                    eligible && healthy && !connected, lastDoor, 5_000);
            if (carry.takeHistoryRevocation()) evaluator.reset();
            if (adopt) evaluator.adoptConnectionHistory(PHONE);
            ProximityEvaluator.Snapshot result = evaluator.observe(PHONE, raw, at, connected,
                    eligible, healthy ? proof : null);
            carry.evaluated(result.event == DEPARTURE, result.reason);
            lastProof = proof;
            lastProofHealthy = healthy;
            lastSignal = new JSONObject()
                    .put("connected", connected).put("connectionKnown", result.connectionKnown)
                    .put("radioOn", result.radioOn).put("radioReadKnown", proof.radioReadKnown)
                    .put("connectionReadKnown", proof.connectionReadKnown)
                    .put("connectionState", proof.state.name().toLowerCase(java.util.Locale.US))
                    .put("connectionObservedAtElapsedMs", proof.observedAtElapsedMs)
                    .put("connectionModeGeneration", proof.modeGeneration)
                    .put("observedAtElapsedMs", result.freshnessMs < 0 ? -1 : at - result.freshnessMs)
                    .put("reason", result.reason);
            return result;
        }

        void poll(long at) throws Exception {
            ProximitySignalAgingPolicy.Revocation revocation = ProximitySignalAgingPolicy.evaluate(
                    lastSignal, true, true, lastProof, lastProofHealthy, MODE, at, 5_000);
            if (revocation.required()) {
                if (revocation.invalidateRecovery) carry.invalidateProof();
                evaluator.reset();
                lastSignal.put("observedAtElapsedMs", -1)
                        .put("connectionKnown", false).put("radioOn", false);
            }
        }

        ProximityEvaluator.Snapshot away(long at) throws Exception {
            return read(at, 0, true, false, null);
        }

        ProximityEvaluator.Snapshot departure() throws Exception {
            read(0, 2, true, true, -8);
            read(1_000, 0, false, false, null);
            for (long at = 4_000; at <= 31_000; at += 3_000) {
                ProximityEvaluator.Snapshot waiting = away(at);
                assertFalse(waiting.lockCandidate);
                assertEquals(NONE, waiting.event);
            }
            ProximityEvaluator.Snapshot confirming = away(34_000);
            assertEquals("confirming_disconnection", confirming.reason);
            assertFalse(confirming.lockCandidate);
            ProximityEvaluator.Snapshot departure = away(37_000);
            assertEquals(DEPARTURE, departure.event);
            assertTrue(departure.lockCandidate);
            return departure;
        }
    }

    @Test public void shutdownRecoveryProducesRealDepartureWithoutReconnectAttemptsOrRssi()
            throws Exception {
        Scenario s = new Scenario();
        ProximityEvaluator.Snapshot departure = s.departure();
        assertEquals(AWAY, departure.state);
        assertEquals("confirmed_disconnection", departure.evidenceKind);
        assertNull(departure.rawRssi);
        assertTrue(departure.connectionKnown);
        assertTrue(departure.radioOn);
        assertTrue(s.carry.lockEvidenceValid(37_000, -1));
    }

    @Test public void fastRadioRestartDoesNotNeedTheCollectorToObserveTheOffBroadcast()
            throws Exception {
        Scenario s = new Scenario();
        s.read(0, 2, true, true, -8);
        for (long at = 1_000; at <= 28_000; at += 3_000)
            assertFalse(s.away(at).lockCandidate);
        assertEquals("confirming_disconnection", s.away(31_000).reason);
        assertEquals(DEPARTURE, s.away(34_000).event);
    }

    @Test public void doorEdgeAtDispatchRevokesDepartureUntilQuietClosuresAreProvenAgain()
            throws Exception {
        Scenario s = new Scenario();
        long firstEpisode = s.departure().episodeId;
        s.lastDoor = 38_000;
        assertFalse(s.carry.lockEvidenceValid(38_000, s.lastDoor));
        for (long at = 40_000; at <= 52_000; at += 3_000)
            assertFalse(s.away(at).lockCandidate);
        assertEquals("confirming_disconnection", s.away(55_000).reason);
        ProximityEvaluator.Snapshot renewed = s.away(58_000);
        assertEquals(DEPARTURE, renewed.event);
        assertTrue(renewed.episodeId > firstEpisode);
        assertTrue(s.carry.lockEvidenceValid(58_000, s.lastDoor));
    }

    @Test public void collectorRestartRevokesThePendingEpisodeAndEarnsANewGrace()
            throws Exception {
        Scenario s = new Scenario();
        long firstEpisode = s.departure().episodeId;
        s.instance = "collector-b";
        for (long at = 40_000; at <= 67_000; at += 3_000)
            assertFalse(s.away(at).lockCandidate);
        assertEquals("confirming_disconnection", s.away(70_000).reason);
        ProximityEvaluator.Snapshot renewed = s.away(73_000);
        assertEquals(DEPARTURE, renewed.event);
        assertTrue(renewed.episodeId > firstEpisode);
    }

    @Test public void nativeReconnectCancelsLockAndRetainsNormalSignalStrengthArrival()
            throws Exception {
        Scenario s = new Scenario();
        s.departure();
        ProximityEvaluator.Snapshot reconnected = s.read(40_000, 0, true, true, -8);
        assertFalse(reconnected.lockCandidate);
        assertFalse(reconnected.arrivalCandidate);
        assertFalse(s.carry.requiresLockGuard());
        assertFalse(s.carry.lockEvidenceValid(40_000, -1));
        assertFalse(s.read(41_000, 0, true, true, -8).arrivalCandidate);
        ProximityEvaluator.Snapshot arrival = s.read(42_000, 0, true, true, -8);
        assertEquals(ARRIVAL, arrival.event);
        assertTrue(arrival.arrivalCandidate);
    }

    @Test public void continuouslyFreshConfirmedAbsenceRetainsArrivalAfterTheBootstrapExpires()
            throws Exception {
        Scenario s = new Scenario();
        s.departure();
        for (long at = 40_000; at <= 340_000; at += 3_000) {
            ProximityEvaluator.Snapshot absent = s.away(at);
            assertEquals(AWAY, absent.state);
            assertTrue(absent.armed);
        }
        assertFalse(s.read(343_000, 0, true, true, -8).lockCandidate);
        assertEquals(ARRIVAL, s.read(345_000, 0, true, true, -8).event);
    }

    @Test public void statusPollingEverySecondCannotPreventPassiveShutdownRecovery() throws Exception {
        Scenario s = new Scenario();
        s.read(0, 2, true, true, -8);
        s.read(1_000, 0, false, false, null);
        for (long at = 4_000; at <= 31_000; at += 3_000) {
            assertFalse(s.away(at).lockCandidate);
            s.poll(at + 1_000);
            s.poll(at + 2_000);
        }
        assertEquals("confirming_disconnection", s.away(34_000).reason);
        s.poll(35_000);
        s.poll(36_000);
        assertEquals(DEPARTURE, s.away(37_000).event);
    }

    @Test public void radioShutdownAfterTheEarlyDropWindowStillUsesFreshParkedConnectionHistory()
            throws Exception {
        Scenario s = new Scenario();
        s.read(0, 2, true, true, -8);
        for (long at = 1_000; at <= 25_000; at += 1_000)
            assertFalse(s.read(at, 0, true, true, -8).lockCandidate);
        s.read(26_000, 0, false, false, null);
        for (long at = 29_000; at <= 56_000; at += 3_000)
            assertFalse(s.away(at).lockCandidate);
        assertEquals("confirming_disconnection", s.away(59_000).reason);
        assertEquals(DEPARTURE, s.away(62_000).event);
    }

    @Test public void anotherRadioShutdownAfterNativeReconnectCanRecoverAgain() throws Exception {
        Scenario s = new Scenario();
        s.departure();
        s.read(40_000, 0, true, true, -8);
        assertEquals(ARRIVAL, s.read(42_000, 0, true, true, -8).event);
        for (long at = 43_000; at <= 46_000; at += 1_000)
            s.read(at, 0, true, true, -8);
        s.read(47_000, 0, false, false, null);
        for (long at = 50_000; at <= 77_000; at += 3_000)
            assertFalse(s.away(at).lockCandidate);
        assertEquals("confirming_disconnection", s.away(80_000).reason);
        assertEquals(DEPARTURE, s.away(83_000).event);
    }

    @Test public void normalHealthyDisconnectionAfterParkingRetainsItsNormalConfirmation()
            throws Exception {
        Scenario s = new Scenario();
        s.read(0, 2, true, true, -8);
        for (long at = 1_000; at <= 25_000; at += 1_000)
            s.read(at, 0, true, true, -8);
        assertEquals("confirming_disconnection", s.away(28_000).reason);
        assertFalse(s.carry.requiresLockGuard());
        assertEquals(DEPARTURE, s.away(31_000).event);
    }

    @Test public void statusPollingCannotKeepRecoveryAliveWhenNativeReadsStop() throws Exception {
        Scenario s = new Scenario();
        s.read(0, 2, true, true, -8);
        s.read(1_000, 0, false, false, null);
        for (long at = 4_000; at <= 31_000; at += 3_000)
            s.away(at);
        s.poll(37_000);
        assertFalse(s.carry.lockEvidenceValid(37_000, -1));
        for (long at = 40_000; at <= 67_000; at += 3_000)
            assertFalse(s.away(at).lockCandidate);
        assertEquals("confirming_disconnection", s.away(70_000).reason);
        assertEquals(DEPARTURE, s.away(73_000).event);
    }

    @Test public void unavailableRadioReadAfterALateShutdownWaitsForFreshNativeAbsence()
            throws Exception {
        Scenario s = new Scenario();
        s.read(0, 2, true, true, -8);
        for (long at = 1_000; at <= 25_000; at += 1_000)
            s.read(at, 0, true, true, -8);
        for (long at = 26_000; at <= 56_000; at += 3_000) {
            assertFalse(s.unknownNativeRead(at, 0).lockCandidate);
            assertFalse(s.carry.lockEvidenceValid(at, -1));
        }
        for (long at = 59_000; at <= 86_000; at += 3_000)
            assertFalse(s.away(at).lockCandidate);
        assertEquals("confirming_disconnection", s.away(89_000).reason);
        assertEquals(DEPARTURE, s.away(92_000).event);
    }

    @Test public void samplingPauseAfterAParkedConnectionRecoversWithoutARadioOffPacket()
            throws Exception {
        Scenario s = new Scenario();
        s.read(0, 2, true, true, -8);
        for (long at = 1_000; at <= 25_000; at += 1_000)
            s.read(at, 0, true, true, -8);
        // No UI status read or outage packet arrives during this native sampling gap.
        for (long at = 34_000; at <= 61_000; at += 3_000)
            assertFalse(s.away(at).lockCandidate);
        assertEquals("confirming_disconnection", s.away(64_000).reason);
        assertEquals(DEPARTURE, s.away(67_000).event);
    }

    @Test public void unavailableRadioReadRevokesAnEarnedDepartureAndRestartsTheWholeGrace()
            throws Exception {
        Scenario s = new Scenario();
        long firstEpisode = s.departure().episodeId;
        assertFalse(s.unknownNativeRead(40_000, 0).lockCandidate);
        assertFalse(s.carry.lockEvidenceValid(40_000, -1));
        for (long at = 43_000; at <= 70_000; at += 3_000)
            assertFalse(s.away(at).lockCandidate);
        assertEquals("confirming_disconnection", s.away(73_000).reason);
        ProximityEvaluator.Snapshot renewed = s.away(76_000);
        assertEquals(DEPARTURE, renewed.event);
        assertTrue(renewed.episodeId > firstEpisode);
    }

    @Test public void interruptionTimingSweepNeverLocksBeforeFreshRadioAndDoorWindows()
            throws Exception {
        for (boolean doorOnly : new boolean[]{false, true}) {
            for (long interruption = 7_000; interruption <= 55_000; interruption += 3_000) {
                Scenario s = new Scenario();
                s.read(0, 2, true, true, -8);
                s.unknownNativeRead(1_000, 0);
                long nativeAbsenceSince = 4_000, closureQuietSince = 4_000;
                boolean lockedAfterInterruption = false;
                for (long at = 4_000; at <= 100_000; at += 3_000) {
                    ProximityEvaluator.Snapshot result;
                    if (at == interruption) {
                        if (doorOnly) {
                            s.lastDoor = at;
                            closureQuietSince = at;
                            result = s.away(at);
                        } else {
                            nativeAbsenceSince = closureQuietSince = at + 3_000;
                            result = s.unknownNativeRead(at, 0);
                        }
                        assertFalse(result.lockCandidate);
                        assertFalse(s.carry.lockEvidenceValid(at, s.lastDoor));
                    } else result = s.away(at);
                    if (result.lockCandidate) {
                        assertTrue("radio grace at " + at + " after interruption " + interruption,
                                at - nativeAbsenceSince >= 30_000);
                        assertTrue("closure quiet at " + at + " after interruption " + interruption,
                                at - closureQuietSince >= 15_000);
                        assertTrue(s.carry.lockEvidenceValid(at, s.lastDoor));
                        if (at > interruption) lockedAfterInterruption = true;
                    }
                }
                assertTrue("eventual recovery after interruption " + interruption, lockedAfterInterruption);
            }
        }
    }
}
