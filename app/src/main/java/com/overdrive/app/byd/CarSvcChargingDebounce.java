package com.overdrive.app.byd;

/**
 * Holds a charging=true feed across a short true→false blip (unlock can drop
 * CHARGE_AND_DISCHARGE_SYSTEM_STATE for a poll or two).
 *
 * <p>Must not invent charging=true on a process that has never seen a real
 * charging=true. The previous "first false still returns true" behaviour
 * opened a ghost session on every daemon start / first {@code /status} poll.
 */
final class CarSvcChargingDebounce {

    private final long holdMs;
    private boolean armed;
    private long notChargingSinceMs;

    CarSvcChargingDebounce(long holdMs) {
        this.holdMs = holdMs;
    }

    synchronized boolean apply(boolean rawCharging, long nowMs) {
        if (rawCharging) {
            armed = true;
            notChargingSinceMs = 0L;
            return true;
        }
        if (!armed) return false;
        if (notChargingSinceMs == 0L) {
            notChargingSinceMs = nowMs;
            return true;
        }
        if (nowMs - notChargingSinceMs < holdMs) return true;
        armed = false;
        notChargingSinceMs = 0L;
        return false;
    }
}
