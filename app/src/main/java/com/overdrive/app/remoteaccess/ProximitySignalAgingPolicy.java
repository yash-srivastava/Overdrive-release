package com.overdrive.app.remoteaccess;

import org.json.JSONObject;

/**
 * Ages action evidence separately from accepted native connection evidence.
 * A status read cannot advance or renew either channel.
 */
public final class ProximitySignalAgingPolicy {
    public static final class Revocation {
        public final String reason;
        public final boolean invalidateRecovery;

        private Revocation(String reason, boolean invalidateRecovery) {
            this.reason = reason;
            this.invalidateRecovery = invalidateRecovery;
        }

        public boolean required() { return reason != null; }
    }

    private static final Revocation NONE = new Revocation(null, false);

    private ProximitySignalAgingPolicy() {}

    public static Revocation evaluate(JSONObject signal, boolean enabled, boolean recoveryEnabled,
            BluetoothConnectionEvidence latestNativeProof, boolean acceptedNativeProof,
            String activeModeGeneration, long now, long staleMs) {
        if (!enabled) return new Revocation("disabled", true);
        long observed = signal.optLong("observedAtElapsedMs", -1);
        String reason;
        if (!BluetoothProximityDecisionGuard.fresh(observed, now, staleMs))
            reason = observed < 0 || observed > now ? signal.optString("reason", "missing_signal") : "stale";
        else if (BluetoothConnectionEvidence.hasAny(signal)
                && !BluetoothProximityDecisionGuard.nativeProofHealthy(
                        signal, now, staleMs, activeModeGeneration))
            reason = "connection_proof_unavailable";
        else return NONE;

        // Before connection history is restored the action signal is Unknown, even when
        // the independently accepted native read is healthy and says Disconnected.
        boolean nativeFresh = recoveryEnabled && acceptedNativeProof && staleMs > 0 && latestNativeProof != null
                && latestNativeProof.healthyAt(now, staleMs)
                && activeModeGeneration != null
                && activeModeGeneration.equals(latestNativeProof.modeGeneration);
        return new Revocation(reason, !nativeFresh);
    }
}
