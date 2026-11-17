package com.overdrive.app.remoteaccess;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Status reads must revoke action evidence without extending or destroying fresh native recovery. */
public class ProximitySignalAgingPolicyTest {
    private static final String MODE = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER_MODE = "00000000-0000-0000-0000-000000000002";

    private static JSONObject nativeSignal(long at, boolean connected) throws Exception {
        return new JSONObject().put("connectionReadKnown", true)
                .put("connectionState", connected ? "connected" : "disconnected")
                .put("radioReadKnown", true).put("radioOn", true)
                .put("connectionObservedAtElapsedMs", at).put("connectionModeGeneration", MODE)
                .put("connectionKnown", true).put("connected", connected)
                .put("observedAtElapsedMs", at);
    }

    private static JSONObject unknownAction() throws Exception {
        return nativeSignal(10_000, false).put("connectionKnown", false).put("radioOn", false)
                .put("observedAtElapsedMs", -1).put("reason", "disconnected_without_connection_history");
    }

    private static BluetoothConnectionEvidence healthyProof(long at) throws Exception {
        return BluetoothConnectionEvidence.parse(nativeSignal(at, false));
    }

    private static ProximitySignalAgingPolicy.Revocation age(JSONObject signal,
            BluetoothConnectionEvidence proof, boolean accepted, long now) {
        return ProximitySignalAgingPolicy.evaluate(signal, true, true, proof, accepted, MODE, now, 5_000);
    }

    @Test public void unknownActionIsRevokedWhileFreshAcceptedNativeRecoverySurvives() throws Exception {
        ProximitySignalAgingPolicy.Revocation result = age(unknownAction(), healthyProof(10_000), true, 11_000);
        assertTrue(result.required());
        assertEquals("disconnected_without_connection_history", result.reason);
        assertFalse(result.invalidateRecovery);
    }

    @Test public void pollingDoesNotRenewTheNativeTimestamp() throws Exception {
        BluetoothConnectionEvidence proof = healthyProof(10_000);
        for (long now = 11_000; now <= 15_000; now += 1_000)
            assertFalse(age(unknownAction(), proof, true, now).invalidateRecovery);
        assertTrue(age(unknownAction(), proof, true, 15_001).invalidateRecovery);
        assertEquals(10_000, proof.observedAtElapsedMs);
    }

    @Test public void nativeProofMustHavePassedTheAuthenticatedObservationChecks() throws Exception {
        assertTrue(age(unknownAction(), healthyProof(10_000), false, 11_000).invalidateRecovery);
    }

    @Test public void missingOrUnknownNativeReadCannotPreserveRecovery() throws Exception {
        assertTrue(age(unknownAction(), null, true, 11_000).invalidateRecovery);
        JSONObject unknown = new JSONObject().put("connectionReadKnown", false)
                .put("connectionState", "unknown").put("radioReadKnown", false).put("radioOn", false)
                .put("connectionObservedAtElapsedMs", -1).put("connectionModeGeneration", "");
        assertTrue(age(unknownAction(), BluetoothConnectionEvidence.parse(unknown), true, 11_000)
                .invalidateRecovery);
    }

    @Test public void radioOffCannotPreserveRecoveryEvenWithAConnectionFlag() throws Exception {
        BluetoothConnectionEvidence off = BluetoothConnectionEvidence.parse(
                nativeSignal(10_000, true).put("radioOn", false));
        assertTrue(age(unknownAction(), off, true, 11_000).invalidateRecovery);
    }

    @Test public void futureNativeProofCannotPreserveRecovery() throws Exception {
        assertTrue(age(unknownAction(), healthyProof(12_000), true, 11_000).invalidateRecovery);
    }

    @Test public void changingOrLosingTheCommittedModeInvalidatesRecovery() throws Exception {
        for (String mode : new String[]{OTHER_MODE, "", null})
            assertTrue(ProximitySignalAgingPolicy.evaluate(unknownAction(), true, true,
                    healthyProof(10_000), true, mode, 11_000, 5_000).invalidateRecovery);
    }

    @Test public void disablingMonitoringInvalidatesBothEvidenceChannels() throws Exception {
        ProximitySignalAgingPolicy.Revocation result = ProximitySignalAgingPolicy.evaluate(
                nativeSignal(10_000, true), false, true, healthyProof(10_000), true, MODE, 11_000, 5_000);
        assertTrue(result.required());
        assertEquals("disabled", result.reason);
        assertTrue(result.invalidateRecovery);
    }

    @Test public void disablingOffModeRecoveryCannotRetainABootstrap() throws Exception {
        assertTrue(ProximitySignalAgingPolicy.evaluate(unknownAction(), true, false,
                healthyProof(10_000), true, MODE, 11_000, 5_000).invalidateRecovery);
    }

    @Test public void staleActionRevokesItsLeaseWithoutDiscardingANewerAcceptedNativeRead() throws Exception {
        ProximitySignalAgingPolicy.Revocation result = age(
                nativeSignal(1_000, true), healthyProof(10_000), true, 11_000);
        assertTrue(result.required());
        assertEquals("stale", result.reason);
        assertFalse(result.invalidateRecovery);
    }

    @Test public void freshHealthyNativeActionRequiresNoRevocation() throws Exception {
        ProximitySignalAgingPolicy.Revocation result = age(
                nativeSignal(10_000, true), healthyProof(10_000), true, 11_000);
        assertFalse(result.required());
        assertFalse(result.invalidateRecovery);
    }

    @Test public void freshLegacyRssiActionStillAgesNormally() throws Exception {
        JSONObject legacy = new JSONObject().put("observedAtElapsedMs", 10_000).put("rawRssi", -8);
        assertFalse(age(legacy, null, false, 11_000).required());
        ProximitySignalAgingPolicy.Revocation expired = age(legacy, null, false, 15_001);
        assertTrue(expired.required());
        assertEquals("stale", expired.reason);
        assertTrue(expired.invalidateRecovery);
    }

    @Test public void unhealthyActionMetadataCannotBecomeActionAuthorityThroughFreshNativeCache()
            throws Exception {
        JSONObject action = unknownAction().put("observedAtElapsedMs", 10_000);
        ProximitySignalAgingPolicy.Revocation result = age(action, healthyProof(10_000), true, 11_000);
        assertTrue(result.required());
        assertEquals("connection_proof_unavailable", result.reason);
        assertFalse(result.invalidateRecovery);
    }

    @Test public void invalidFreshnessWindowCannotPreserveRecovery() throws Exception {
        for (long staleMs : new long[]{0, -1})
            assertTrue(ProximitySignalAgingPolicy.evaluate(unknownAction(), true, true,
                    healthyProof(10_000), true, MODE, 10_000, staleMs).invalidateRecovery);
    }
}
