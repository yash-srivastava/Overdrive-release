package com.overdrive.app.automation.condition;

/**
 * Tracks the evidence boundary between successive driver-seat presence observations.
 *
 * <p>There is no driver pressure/occupancy getter on this BYD API. A buckled belt or an active
 * reminder confirms presence, but an unreadable or unbuckled belt does NOT confirm an empty
 * seat. Opening the driver's door is only a possible departure, not proof of one. We therefore
 * clear a previously confirmed presence to UNKNOWN only after a real closed-to-open door edge
 * AND a subsequent sample without positive presence evidence while that door is open.
 *
 * <p>Only the positive evidence is published to automations. The unknown transition is a silent
 * state invalidation (never a synthetic "empty" event). This allows a later independently
 * confirmed positive observation to generate another occupied edge after a genuine exit/entry,
 * without firing when a belted driver merely opens and closes their door.
 *
 * <p>All calls are serialized by {@link BydEvent}'s driver-occupancy lock.
 */
final class DriverOccupancySession {
    static final int UNKNOWN = -1;
    static final int CLOSED = 0;
    static final int OPEN = 1;

    enum Sample { IGNORE, INVALIDATE, OCCUPIED }

    private int lastDoorState = UNKNOWN;
    private boolean awaitingEvidenceLoss;

    void observeDoor(int doorState) {
        if (doorState != OPEN && doorState != CLOSED) return;
        if (lastDoorState == CLOSED && doorState == OPEN) {
            awaitingEvidenceLoss = true;
        } else if (doorState == CLOSED) {
            awaitingEvidenceLoss = false;
        }
        lastDoorState = doorState;
    }

    Sample observePresence(int presence) {
        if (lastDoorState == OPEN) {
            if (awaitingEvidenceLoss && presence != 1) {
                awaitingEvidenceLoss = false; // exactly one invalidation per physical opening
                return Sample.INVALIDATE;
            }
            return Sample.IGNORE; // a belt still buckled while the door is open is not a new entry
        }
        return presence == 1 ? Sample.OCCUPIED : Sample.IGNORE;
    }

    boolean canPublishPositive() {
        return lastDoorState != OPEN;
    }
}
