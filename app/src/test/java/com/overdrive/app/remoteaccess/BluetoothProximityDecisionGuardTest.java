package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BluetoothProximityDecisionGuardTest {
    private static final String MODE = "d2bc2d28-48ed-4c9d-b860-ab7319711650";
    private static final String OTHER_MODE = "2c6e6bfd-46ce-4c1c-9d57-e58db3dc1f25";

    private JSONObject rssi(boolean arrival) throws Exception {
        return new JSONObject().put("observedAtElapsedMs", 1_000).put("episodeId", 7)
                .put("connected", true).put("rawRssi", arrival ? -8 : -32)
                .put("departureFilteredRssi", -32).put("state", arrival ? "NEAR" : "AWAY")
                .put("candidate", "UNKNOWN").put("arrivalCandidate", arrival)
                .put("lockCandidate", !arrival).put("departureEligible", !arrival)
                .put("evidenceKind", "rssi");
    }

    private JSONObject absence() throws Exception {
        return rssi(false).put("connected", false).put("rawRssi", JSONObject.NULL)
                .put("departureFilteredRssi", JSONObject.NULL)
                .put("evidenceKind", "confirmed_disconnection")
                .put("connectionReadKnown", true).put("connectionKnown", true)
                .put("connectionState", "disconnected").put("radioReadKnown", true)
                .put("radioOn", true).put("connectionObservedAtElapsedMs", 1_000)
                .put("connectionModeGeneration", MODE).put("disconnectedForMs", 6_000)
                .put("disconnectionSampleCount", 3);
    }

    private boolean allows(String action, JSONObject latest, long now) {
        return BluetoothProximityDecisionGuard.allows(action, 1_000, 7, "collector", 11,
                MODE, latest, "collector", 11, MODE, now, 10_000, -12, -24);
    }

    @Test public void freshCandidatesAuthorizeLegacyRssiInBothDirections() throws Exception {
        assertTrue(allows("unlock", rssi(true), 1_000));
        assertTrue(allows("lock", rssi(false), 1_000));
    }

    @Test public void newerReadingInSameEpisodeCanPreservePendingAction() throws Exception {
        JSONObject latest = rssi(true).put("observedAtElapsedMs", 3_500);
        assertTrue(BluetoothProximityDecisionGuard.allows("unlock", 1_000, 7,
                "collector", 11, MODE, latest, "collector", 14, MODE,
                4_000, 10_000, -12, -24));
    }

    @Test public void newEpisodeCannotReviveOldAction() throws Exception {
        assertFalse(allows("unlock", rssi(true).put("episodeId", 8), 1_100));
        assertFalse(allows("lock", absence().put("episodeId", 8), 1_100));
    }

    @Test public void collectorReplacementAndSerialRegressionRevokeAction() throws Exception {
        assertFalse(BluetoothProximityDecisionGuard.allows("unlock", 1_000, 7,
                "collector", 11, MODE, rssi(true), "replacement", 12, MODE,
                1_100, 10_000, -12, -24));
        assertFalse(BluetoothProximityDecisionGuard.allows("unlock", 1_000, 7,
                "collector", 11, MODE, rssi(true), "collector", 10, MODE,
                1_100, 10_000, -12, -24));
    }

    @Test public void heartbeatCannotExtendOriginalFiveSecondLease() throws Exception {
        JSONObject latest = rssi(true).put("observedAtElapsedMs", 6_001);
        assertFalse(allows("unlock", latest, 6_001));
        assertTrue(allows("unlock", latest.put("observedAtElapsedMs", 6_000), 6_000));
    }

    @Test public void configuredStalenessCanShortenOriginalActionLease() throws Exception {
        assertFalse(BluetoothProximityDecisionGuard.allows("unlock", 1_000, 7,
                "collector", 11, MODE, rssi(true).put("observedAtElapsedMs", 4_001),
                "collector", 12, MODE, 4_001, 3_000, -12, -24));
    }

    @Test public void arrivalNeedsCurrentStrongRawAndRenewedCandidate() throws Exception {
        assertFalse(allows("unlock", rssi(true).put("rawRssi", -20), 1_100));
        assertFalse(allows("unlock", rssi(true).put("arrivalCandidate", false), 1_100));
        assertFalse(allows("unlock", rssi(true).put("candidate", "NEAR"), 1_100));
        assertFalse(allows("unlock", rssi(true).put("connected", false), 1_100));
    }

    @Test public void departureNeedsBothCurrentRawAwayAndFilterAway() throws Exception {
        assertFalse(allows("lock", rssi(false).put("rawRssi", -8), 1_100));
        assertFalse(allows("lock", rssi(false).put("rawRssi", -20), 1_100));
        assertFalse(allows("lock", rssi(false).put("departureFilteredRssi", -20), 1_100));
        assertFalse(allows("lock", rssi(false).put("lockCandidate", false), 1_100));
    }

    @Test public void nativeConfirmedDisconnectionAuthorizesNullRssiDeparture() throws Exception {
        assertTrue(allows("lock", absence(), 1_100));
        assertTrue(BluetoothProximityDecisionGuard.confirmedDisconnection(absence(), 1_100, 10_000, MODE));
    }

    @Test public void positiveReconnectImmediatelyVetoesOldAbsence() throws Exception {
        assertFalse(allows("lock", absence().put("connected", true), 1_100));
        assertFalse(allows("lock", absence().put("connected", true)
                .put("connectionState", "connected"), 1_100));
    }

    @Test public void absenceNeedsKnownPoweredRadioAndTwoUniqueProofsWithDwell() throws Exception {
        assertFalse(allows("lock", absence().put("connectionKnown", false), 1_100));
        assertFalse(allows("lock", absence().put("radioReadKnown", false), 1_100));
        assertFalse(allows("lock", absence().put("radioOn", false), 1_100));
        assertFalse(allows("lock", absence().put("disconnectionSampleCount", 1), 1_100));
        assertFalse(allows("lock", absence().put("disconnectedForMs", 1_999), 1_100));
        // The evaluator's two reads over two seconds is enough (it was 3 / 6 s before 20 Oct).
        assertTrue(allows("lock", absence().put("disconnectionSampleCount", 2)
                .put("disconnectedForMs", 2_000), 1_100));
        assertFalse(allows("lock", absence().put("rawRssi", -32), 1_100));
    }

    @Test public void oldNativeProofCannotBorrowFreshEnvelopeTimestamp() throws Exception {
        JSONObject latest = absence().put("observedAtElapsedMs", 4_001);
        assertFalse(BluetoothProximityDecisionGuard.confirmedDisconnection(latest, 4_001, 3_000, MODE));
        assertFalse(allows("lock", latest, 4_001));
    }

    @Test public void modeSwitchOrMissingCurrentModeRevokesNativeAuthority() throws Exception {
        assertFalse(BluetoothProximityDecisionGuard.nativeProofHealthy(absence(), 1_100, 10_000, null));
        assertFalse(BluetoothProximityDecisionGuard.nativeProofHealthy(absence(), 1_100, 10_000, OTHER_MODE));
        assertFalse(BluetoothProximityDecisionGuard.allows("unlock", 1_000, 7,
                "collector", 11, MODE, rssi(true), "collector", 11, OTHER_MODE,
                1_100, 10_000, -12, -24));
    }

    @Test public void partialProofCannotFallBackToLegacyRssi() throws Exception {
        assertFalse(allows("unlock", rssi(true).put("radioOn", true), 1_100));
        JSONObject connected = absence().put("connected", true).put("connectionState", "connected")
                .put("rawRssi", -8).put("state", "NEAR").put("evidenceKind", "rssi")
                .put("arrivalCandidate", true);
        assertTrue(allows("unlock", connected, 1_100));
        assertFalse(allows("unlock", connected.put("connectionKnown", false), 1_100));
    }

    @Test public void invalidClockIdentityAndActionFailClosed() throws Exception {
        assertFalse(allows("unlock", rssi(true), 999));
        assertFalse(allows("invalid", rssi(true), 1_100));
        assertFalse(allows("unlock", rssi(true).put("episodeId", 0), 1_100));
        assertFalse(BluetoothProximityDecisionGuard.fresh(-1, 1_000, 5_000));
        assertFalse(BluetoothProximityDecisionGuard.fresh(1_000, -1, 5_000));
        assertFalse(BluetoothProximityDecisionGuard.fresh(1_000, 1_000, 0));
    }

    @Test public void satisfiedEpisodeCannotRetryOrUndoLaterManualFobAction() throws Exception {
        assertFalse(allows("unlock", rssi(true).put("actionSatisfied", true), 1_100));
        assertFalse(allows("lock", absence().put("actionSatisfied", true), 1_100));
        assertTrue(allows("unlock", rssi(true).put("actionSatisfied", false), 1_100));
    }

    // ── Cloud fallback (17 Oct): the revocation-only variant used by the cloud leg ──────────────────
    private boolean current(String action, JSONObject latest, long now) {
        return BluetoothProximityDecisionGuard.allowsIgnoringObservationAge(action, 7, "collector", 11,
                MODE, latest, "collector", 11, MODE, now, 10_000, -12, -24);
    }
    /** An 8 s-old decision whose phone is still Near and reporting: the cloud leg may fire; allows() may not. */
    @Test public void eightSecondOldDecisionWithNoNewerSignalIsStillCurrent() throws Exception {
        JSONObject latest = rssi(true).put("observedAtElapsedMs", 9_000);
        assertFalse(allows("unlock", latest, 9_000));          // the original 5 s lease is spent …
        assertTrue(current("unlock", latest, 9_000));          // … but nothing revoked the decision.
        JSONObject away = rssi(false).put("observedAtElapsedMs", 9_000);
        assertFalse(allows("lock", away, 9_000));
        assertTrue(current("lock", away, 9_000));
        JSONObject gone = absence().put("observedAtElapsedMs", 9_000).put("connectionObservedAtElapsedMs", 9_000);
        assertFalse(allows("lock", gone, 9_000));
        assertTrue(current("lock", gone, 9_000));
        // Minutes later it is the same answer: only revocation and the LATEST reading's freshness count.
        assertTrue(current("unlock", rssi(true).put("observedAtElapsedMs", 120_000), 120_000));
    }
    /** A newer signal in the other direction, a stale latest reading or a satisfied action revoke it. */
    @Test public void newerSignalStaleLatestOrSatisfiedActionRevokesTheCurrentDecision() throws Exception {
        long now = 9_000;
        // Arrival then departure: the phone is Away / a departure candidate → the unlock is revoked.
        assertFalse(current("unlock", rssi(false).put("observedAtElapsedMs", now), now));
        assertFalse(current("unlock", rssi(true).put("observedAtElapsedMs", now).put("candidate", "AWAY"), now));
        assertFalse(current("unlock", rssi(true).put("observedAtElapsedMs", now).put("arrivalCandidate", false), now));
        // Departure then arrival: the phone is back → the lock is revoked.
        assertFalse(current("lock", rssi(true).put("observedAtElapsedMs", now), now));
        assertFalse(current("lock", rssi(false).put("observedAtElapsedMs", now).put("candidate", "NEAR"), now));
        // The LATEST reading must still be fresh (staleMs = 10 s here): a silent collector revokes.
        assertFalse(current("unlock", rssi(true).put("observedAtElapsedMs", 20_000 - 10_001), 20_000));
        assertTrue(current("unlock", rssi(true).put("observedAtElapsedMs", 20_000 - 10_000), 20_000));
        // Satisfied, new episode, replaced collector, serial regression, other mode: revoked as in allows().
        assertFalse(current("unlock", rssi(true).put("observedAtElapsedMs", now).put("actionSatisfied", true), now));
        assertFalse(current("unlock", rssi(true).put("observedAtElapsedMs", now).put("episodeId", 8), now));
        assertFalse(BluetoothProximityDecisionGuard.allowsIgnoringObservationAge("unlock", 7, "collector", 11,
                MODE, rssi(true).put("observedAtElapsedMs", now), "replacement", 12, MODE, now, 10_000, -12, -24));
        assertFalse(BluetoothProximityDecisionGuard.allowsIgnoringObservationAge("unlock", 7, "collector", 11,
                MODE, rssi(true).put("observedAtElapsedMs", now), "collector", 10, MODE, now, 10_000, -12, -24));
        assertFalse(BluetoothProximityDecisionGuard.allowsIgnoringObservationAge("unlock", 7, "collector", 11,
                MODE, rssi(true).put("observedAtElapsedMs", now), "collector", 11, OTHER_MODE, now, 10_000, -12, -24));
        // Native proof must stay healthy; a weak raw reading or a disconnected phone revokes an unlock.
        JSONObject connected = absence().put("connected", true).put("connectionState", "connected")
                .put("rawRssi", -8).put("state", "NEAR").put("evidenceKind", "rssi")
                .put("arrivalCandidate", true).put("observedAtElapsedMs", now)
                .put("connectionObservedAtElapsedMs", now);
        assertTrue(current("unlock", connected, now));
        assertFalse(current("unlock", new JSONObject(connected.toString()).put("connectionKnown", false), now));
        assertFalse(current("unlock", rssi(true).put("observedAtElapsedMs", now).put("rawRssi", -20), now));
        assertFalse(current("unlock", rssi(true).put("observedAtElapsedMs", now).put("connected", false), now));
        assertFalse(current("invalid", rssi(true).put("observedAtElapsedMs", now), now));
        assertFalse(current("unlock", null, now));
    }
    /** Whenever the original lease still holds, the two predicates agree exactly. */
    @Test public void currentAgreesWithAllowsWhileTheOriginalLeaseHolds() throws Exception {
        JSONObject[] readings = {rssi(true), rssi(false), absence(), rssi(true).put("candidate", "AWAY"),
                rssi(true).put("rawRssi", -20), rssi(false).put("lockCandidate", false),
                rssi(true).put("actionSatisfied", true), absence().put("disconnectionSampleCount", 2),
                rssi(false).put("departureFilteredRssi", -20), rssi(true).put("episodeId", 8)};
        for (JSONObject reading : readings) {
            for (String action : new String[] {"unlock", "lock"}) {
                for (long now = 1_000; now <= 6_000; now += 500) {
                    JSONObject latest = new JSONObject(reading.toString()).put("observedAtElapsedMs", now);
                    if (latest.has("connectionObservedAtElapsedMs")) latest.put("connectionObservedAtElapsedMs", now);
                    assertEquals(action + "@" + now + " " + latest, allows(action, latest, now), current(action, latest, now));
                }
            }
        }
    }
}
