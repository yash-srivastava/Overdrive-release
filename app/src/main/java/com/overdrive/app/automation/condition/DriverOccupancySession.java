package com.overdrive.app.automation.condition;

/**
 * Driver presence across door openings. No seat sensor exists: buckle/reminder prove presence,
 * their absence proves nothing, so only sustained loss while the door is open clears it to unknown.
 * Serialized by BydEvent's driver-occupancy lock.
 */
final class DriverOccupancySession {
    static final int UNKNOWN = -1;
    static final int CLOSED = 0;
    static final int OPEN = 1;
    // Same 2-read debounce as the driver belt: one transient read must not re-arm a seated driver.
    static final int LOSS_SAMPLES = 2;
    // DiLink 5 relays reminder presence the daemon poll cannot read; it arrives about every 1 s.
    static final long RELAY_EVIDENCE_MS = 2500L;

    enum Sample { IGNORE, INVALIDATE, OCCUPIED }

    private int lastDoorState = UNKNOWN;
    private boolean awaitingEvidenceLoss;
    private int lossStreak;
    private boolean relaySeen;
    private long relayPositiveAtMs;

    void observeDoor(int doorState) {
        if (doorState != OPEN && doorState != CLOSED) return;
        if (lastDoorState == CLOSED && doorState == OPEN) {
            awaitingEvidenceLoss = true;
            lossStreak = 0;
        } else if (doorState == CLOSED) {
            awaitingEvidenceLoss = false;
            lossStreak = 0;
        }
        lastDoorState = doorState;
    }

    void observeRelayPositive(long nowMs) {
        relaySeen = true;
        relayPositiveAtMs = nowMs;
    }

    Sample observePresence(int presence, long nowMs) {
        if (lastDoorState == OPEN) {
            if (!awaitingEvidenceLoss) return Sample.IGNORE;
            boolean relayFresh = relaySeen && nowMs - relayPositiveAtMs <= RELAY_EVIDENCE_MS;
            if (presence == 1 || relayFresh) {
                lossStreak = 0;
                return Sample.IGNORE;
            }
            if (++lossStreak < LOSS_SAMPLES) return Sample.IGNORE;
            awaitingEvidenceLoss = false; // one invalidation per physical opening
            lossStreak = 0;
            return Sample.INVALIDATE;
        }
        return presence == 1 ? Sample.OCCUPIED : Sample.IGNORE;
    }

    boolean canPublishPositive() {
        return lastDoorState != OPEN;
    }
}
