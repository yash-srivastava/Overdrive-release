package com.overdrive.app.remoteaccess;

import org.json.JSONObject;

/** Pure checks for a Bluetooth action's original lease and the latest phone evidence. */
public final class BluetoothProximityDecisionGuard {
    public static final long MAX_ACTION_LEASE_MS = 5_000;

    private BluetoothProximityDecisionGuard() {}

    public static boolean fresh(long observed, long now, long freshnessMs) {
        return now >= 0 && freshnessMs > 0 && observed >= 0 && observed <= now
                && now - observed <= freshnessMs;
    }

    public static boolean nativeProofHealthy(JSONObject signal, long now, long staleMs,
            String activeModeGeneration) {
        if (signal == null || activeModeGeneration == null || activeModeGeneration.isEmpty())
            return false;
        try {
            BluetoothConnectionEvidence proof = BluetoothConnectionEvidence.parse(signal);
            return proof.healthyAt(now, staleMs)
                    && activeModeGeneration.equals(proof.modeGeneration)
                    && Boolean.TRUE.equals(signal.opt("connectionKnown"))
                    && signal.opt("connected") instanceof Boolean
                    && ((Boolean) signal.opt("connected"))
                        == (proof.state == BluetoothConnectionEvidence.State.CONNECTED);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    public static boolean confirmedDisconnection(JSONObject signal, long now, long staleMs,
            String activeModeGeneration) {
        return nativeProofHealthy(signal, now, staleMs, activeModeGeneration)
                && "confirmed_disconnection".equals(signal.optString("evidenceKind"))
                && "disconnected".equals(signal.optString("connectionState"))
                && signal.optBoolean("connectionKnown") && signal.optBoolean("radioOn")
                && !signal.optBoolean("connected")
                && signal.has("rawRssi") && signal.isNull("rawRssi")
                && signal.has("departureFilteredRssi") && signal.isNull("departureFilteredRssi")
                && "AWAY".equals(signal.optString("state"))
                && "UNKNOWN".equals(signal.optString("candidate"))
                && signal.optBoolean("departureEligible")
                // The evaluator's own rule (two reads over two seconds since 20 Oct); the old 3 / 6 s
                // here rejected departures the evaluator had already confirmed.
                && signal.optInt("disconnectionSampleCount") >= ProximityEvaluator.DISCONNECTION_CONFIRM_READS
                && signal.optLong("disconnectedForMs") >= ProximityEvaluator.DISCONNECTION_CONFIRM_SPAN_MS
                && signal.optLong("observedAtElapsedMs", -1)
                    == signal.optLong("connectionObservedAtElapsedMs", -2);
    }

    public static boolean allows(String action, long originalObserved, long originalEpisode,
            String originalInstance, long originalSerial, String originalModeGeneration,
            JSONObject latest, String latestInstance, long latestSerial,
            String activeModeGeneration, long now, long staleMs, int nearThreshold,
            int awayThreshold) {
        if (!("unlock".equals(action) || "lock".equals(action)) || latest == null
                || originalEpisode <= 0 || originalSerial <= 0
                || originalInstance == null || originalInstance.isEmpty()
                || !originalInstance.equals(latestInstance) || latestSerial < originalSerial
                || originalEpisode != latest.optLong("episodeId")
                || latest.optBoolean("actionSatisfied")
                || !same(originalModeGeneration, activeModeGeneration)
                || !fresh(originalObserved, now, Math.min(staleMs, MAX_ACTION_LEASE_MS))
                || !fresh(latest.optLong("observedAtElapsedMs", -1), now, staleMs)
                || !"UNKNOWN".equals(latest.optString("candidate"))) return false;
        boolean nativeProof = BluetoothConnectionEvidence.hasAny(latest);
        if (nativeProof && !nativeProofHealthy(latest, now, staleMs, activeModeGeneration))
            return false;
        Double raw = number(latest.opt("rawRssi"));
        if ("unlock".equals(action)) {
            return "NEAR".equals(latest.optString("state"))
                    && latest.optBoolean("arrivalCandidate") && latest.optBoolean("connected")
                    && "rssi".equals(latest.optString("evidenceKind"))
                    && raw != null && raw >= nearThreshold && raw >= -127 && raw <= 20;
        }
        if (!"AWAY".equals(latest.optString("state"))
                || !latest.optBoolean("lockCandidate")
                || !latest.optBoolean("departureEligible")) return false;
        if (confirmedDisconnection(latest, now, staleMs, activeModeGeneration)) return true;
        Double filtered = number(latest.opt("departureFilteredRssi"));
        return latest.optBoolean("connected") && "rssi".equals(latest.optString("evidenceKind"))
                && raw != null && raw >= -127 && raw <= awayThreshold
                && filtered != null && filtered <= awayThreshold;
    }

    /**
     * Cloud fallback re-validation (17 Oct product decision): everything {@link #allows} checks
     * EXCEPT the age of the ORIGINAL observation. That lease proves the reading was fresh when the
     * first (local) command was dispatched; the cloud leg continues that already-dispatched decision,
     * so only revocation counts here: the same collector instance, serial order and episode, the same
     * active mode, the action not yet satisfied, a fresh LATEST reading with no pending candidate,
     * healthy native proof, and the latest reading still supporting the direction. Never used before
     * the first dispatch, which keeps {@link #allows} as its guard.
     */
    public static boolean allowsIgnoringObservationAge(String action, long originalEpisode,
            String originalInstance, long originalSerial, String originalModeGeneration,
            JSONObject latest, String latestInstance, long latestSerial,
            String activeModeGeneration, long now, long staleMs, int nearThreshold,
            int awayThreshold) {
        if (!("unlock".equals(action) || "lock".equals(action)) || latest == null
                || originalEpisode <= 0 || originalSerial <= 0
                || originalInstance == null || originalInstance.isEmpty()
                || !originalInstance.equals(latestInstance) || latestSerial < originalSerial
                || originalEpisode != latest.optLong("episodeId")
                || latest.optBoolean("actionSatisfied")
                || !same(originalModeGeneration, activeModeGeneration)
                || !fresh(latest.optLong("observedAtElapsedMs", -1), now, staleMs)
                || !"UNKNOWN".equals(latest.optString("candidate"))) return false;
        boolean nativeProof = BluetoothConnectionEvidence.hasAny(latest);
        if (nativeProof && !nativeProofHealthy(latest, now, staleMs, activeModeGeneration))
            return false;
        Double raw = number(latest.opt("rawRssi"));
        if ("unlock".equals(action)) {
            return "NEAR".equals(latest.optString("state"))
                    && latest.optBoolean("arrivalCandidate") && latest.optBoolean("connected")
                    && "rssi".equals(latest.optString("evidenceKind"))
                    && raw != null && raw >= nearThreshold && raw >= -127 && raw <= 20;
        }
        if (!"AWAY".equals(latest.optString("state"))
                || !latest.optBoolean("lockCandidate")
                || !latest.optBoolean("departureEligible")) return false;
        if (confirmedDisconnection(latest, now, staleMs, activeModeGeneration)) return true;
        Double filtered = number(latest.opt("departureFilteredRssi"));
        return latest.optBoolean("connected") && "rssi".equals(latest.optString("evidenceKind"))
                && raw != null && raw >= -127 && raw <= awayThreshold
                && filtered != null && filtered <= awayThreshold;
    }

    private static Double number(Object value) {
        if (!(value instanceof Number)) return null;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) ? number : null;
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }
}
