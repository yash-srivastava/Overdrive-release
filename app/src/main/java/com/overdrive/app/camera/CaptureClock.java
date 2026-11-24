package com.overdrive.app.camera;

/**
 * Frame times from the camera's own capture stamps, so a frame keeps its capture
 * time however late the camera thread picks it up. The stamp unit (ms, µs or ns on
 * the System.nanoTime() clock) is matched against pickup time on the first frames.
 * A stamp that doesn't fit (stuck, ahead of pickup, over 2 s old) falls back to
 * pickup time; a stream whose stamps never or no longer fit stays on pickup time.
 * Consecutive stamps must also be at least 4 ms apart.
 * Camera thread only.
 */
final class CaptureClock {
    static final int UNDECIDED = 0;
    static final int ON = 1;
    static final int OFF = 2;

    static final long MAX_LAG_NS = 2_000_000_000L;
    private static final long AHEAD_SLACK_NS = 2_000_000L;
    // Real frames are at least this far apart (cameras run <= 250 fps); a stamp that
    // only micro-advances (DiLink 5's PRIVATE reader) is not a capture time.
    private static final long MIN_SPACING_NS = 4_000_000L;
    private static final long[] UNITS_NS = {1_000_000L, 1_000L, 1L};
    private static final int DECIDE_FRAMES = 30;
    private static final int MAX_STRAIGHT_MISSES = 30;

    private long unitNs;  // 0 = undecided
    private boolean off;
    private int undecided;
    private int straightMisses;
    private long lastRaw;
    private long fitted;
    private long missed;

    /** A new camera stream: match the stamp unit again. */
    void reset() {
        unitNs = 0;
        off = false;
        undecided = 0;
        straightMisses = 0;
        lastRaw = 0;
        fitted = 0;
        missed = 0;
    }

    /** Capture time on the System.nanoTime() clock, or {@code pickupNs} when the stamp doesn't fit. */
    long frameTimeNs(long raw, long pickupNs) {
        if (off) return pickupNs;
        if (unitNs == 0) {
            unitNs = raw > 0 ? matchUnit(raw, pickupNs) : 0;
            if (unitNs == 0) {
                if (++undecided >= DECIDE_FRAMES) off = true;
                return pickupNs;
            }
        }
        long t = raw * unitNs;
        if (raw <= lastRaw || (lastRaw > 0 && (raw - lastRaw) * unitNs < MIN_SPACING_NS)
                || !fits(t, pickupNs)) {
            missed++;
            if (++straightMisses >= MAX_STRAIGHT_MISSES) off = true;
            return pickupNs;
        }
        lastRaw = raw;
        straightMisses = 0;
        fitted++;
        return t;
    }

    int state() {
        return off ? OFF : unitNs == 0 ? UNDECIDED : ON;
    }

    long unitNs() {
        return unitNs;
    }

    long fitted() {
        return fitted;
    }

    long missed() {
        return missed;
    }

    private static long matchUnit(long raw, long pickupNs) {
        for (long unit : UNITS_NS) {
            if (raw <= Long.MAX_VALUE / unit && fits(raw * unit, pickupNs)) return unit;
        }
        return 0;
    }

    private static boolean fits(long t, long pickupNs) {
        long lag = pickupNs - t;
        return lag >= -AHEAD_SLACK_NS && lag <= MAX_LAG_NS;
    }
}
