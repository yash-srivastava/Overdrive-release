package com.overdrive.app.remoteaccess;

/**
 * One phone's switch-off Near carry-over. Two Near readings in a row with the car on stand in for the
 * parked Near confirmation that the power-off transition wipes, so the first fresh Away confirmation
 * after switch-off can depart. Pure state; the caller feeds every accepted reading in order.
 */
public final class SwitchOffNearCarry {
    /** The latest car-on Near pair, measured to the first parked reading. */
    public static final long EDGE_WINDOW_MS = 30_000;
    /** Two consecutive car-on Near readings at most this far apart form a Near pair. */
    public static final long NEAR_PAIR_MS = 5_000;
    public static final long CARRY_TTL_MS = 60_000;

    private enum Stage { IDLE, ARMED, DONE }

    private Stage stage = Stage.IDLE;
    private boolean vehicleWasOn;
    private long previousNearMs = -1, nearPairMs = -1, edgeMs = -1;
    private long revision;
    private final StringBuilder transitions = new StringBuilder();

    /**
     * @param power vehicle power with the reading: 0 off, above 0 on, anything else unknown
     * @param connected the reading's connection, already false when its native proof is unhealthy
     * @return true when the caller must adopt the Near visit before evaluating this reading
     */
    public boolean observe(long observedMs, int power, boolean eligible, boolean connected, Integer rssi,
            int nearThreshold, long settingsRevision) {
        transitions.setLength(0);
        if (power > 0) {
            if (stage == Stage.ARMED) mark("cancelled_vehicle_on");
            stage = Stage.IDLE;
            vehicleWasOn = true;
            if (connected && rssi != null && rssi >= nearThreshold) {
                if (previousNearMs >= 0 && observedMs > previousNearMs
                        && observedMs - previousNearMs <= NEAR_PAIR_MS)
                    nearPairMs = observedMs;
                previousNearMs = observedMs;
            } else {
                previousNearMs = -1;
            }
            return false;
        }
        if (power != 0) return false;
        if (!eligible) {
            if (stage == Stage.ARMED) mark("cancelled_ineligible");
            stage = Stage.DONE;
            vehicleWasOn = false;
            previousNearMs = nearPairMs = -1;
            return false;
        }
        if (vehicleWasOn) {
            vehicleWasOn = false;
            boolean armed = nearPairMs >= 0 && observedMs >= nearPairMs
                    && observedMs - nearPairMs <= EDGE_WINDOW_MS;
            stage = armed ? Stage.ARMED : Stage.DONE;
            edgeMs = observedMs;
            revision = settingsRevision;
            previousNearMs = nearPairMs = -1;
            if (armed) mark("armed");
        }
        if (stage != Stage.ARMED) return false;
        if (revision != settingsRevision) return finish("cancelled_source_changed");
        if (observedMs < edgeMs || observedMs - edgeMs > CARRY_TTL_MS) return finish("expired");
        return true;
    }

    /** The evaluator's verdict on the reading just observed. */
    public void evaluated(boolean departure) {
        if (stage == Stage.ARMED && departure) finish("departure");
    }

    /** Comma-separated transitions of the latest reading, empty when nothing is worth logging. */
    public String takeTransitions() {
        String value = transitions.toString();
        transitions.setLength(0);
        return value;
    }

    public long sinceOffMs(long observedMs) {
        return edgeMs < 0 ? -1 : Math.max(0, observedMs - edgeMs);
    }

    private boolean finish(String reason) {
        if (stage == Stage.ARMED) mark(reason);
        stage = Stage.DONE;
        return false;
    }

    private void mark(String transition) {
        if (transitions.length() > 0) transitions.append(',');
        transitions.append(transition);
    }
}
