package com.overdrive.app.byd;

/**
 * Admits a car_service charging session. The gun latch is primary; pack inflow
 * is the fallback when that property flickers or is missing.
 *
 * <p>Always requires {@code CHARGE_AND_DISCHARGE_SYSTEM_STATE==2} and not
 * driving — regen is high kW, so a power floor without those gates would
 * reopen the ghost-session bug. Zero-kW state==2 is not enough: that is
 * idle / stuck / discharge and must not open a row.
 *
 * <ul>
 *   <li>Gun connected → admit immediately (including negotiate at 0 kW).</li>
 *   <li>≥ {@link #STRONG_KW} while parked → admit immediately (DC / typical AC).</li>
 *   <li>≥ {@link #WEAK_KW} for {@link #SUSTAIN_MS} → admit (slow 6 A AC).</li>
 *   <li>Once latched, hold across a gun drop while power stays ≥ weak.</li>
 * </ul>
 */
final class CarSvcChargeSessionAdmit {

    /** Slowest household AC we still treat as real inflow (~6 A). */
    static final float WEAK_KW = 1.0f;

    /** Instant fallback: above 12 V / HVAC noise, below a 7 kW wallbox. */
    static final float STRONG_KW = 3.0f;

    /** Wait for weak inflow to look like a charge, not a one-poll spike. */
    static final long SUSTAIN_MS = 45_000L;

    private boolean latched;
    private long weakSinceMs;

    synchronized boolean admit(
            boolean rawCharging,
            boolean notDriving,
            boolean gunConnected,
            float powerKw,
            long nowMs) {
        if (!rawCharging || !notDriving) {
            reset();
            return false;
        }
        float p = inflowKw(powerKw);
        boolean strong = p >= STRONG_KW;
        boolean weak = p >= WEAK_KW;
        if (gunConnected || strong) {
            latched = true;
            weakSinceMs = 0L;
            return true;
        }
        if (latched && weak) return true;
        if (weak) {
            if (weakSinceMs == 0L) weakSinceMs = nowMs;
            if (nowMs - weakSinceMs >= SUSTAIN_MS) {
                latched = true;
                return true;
            }
            return false;
        }
        reset();
        return false;
    }

    private void reset() {
        latched = false;
        weakSinceMs = 0L;
    }

    private static float inflowKw(float powerKw) {
        if (!Float.isFinite(powerKw) || powerKw < 0f) return 0f;
        return powerKw;
    }
}
