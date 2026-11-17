package com.overdrive.app.camera;

/**
 * Caps the recorder's frame admission at the target rate when the camera HAL
 * delivers faster than requested. Inactive (admits every frame) unless the
 * smoothed delivery interval is more than ~15% shorter than the target interval.
 * Render-thread confined.
 */
final class RecorderFramePacer {

    private static final int ENGAGE_BELOW_PCT = 87;

    private long lastFrameNs;
    private long smoothedIntervalNs;
    private long nextDueNs;
    private int lastTargetFps;
    private long admitted;
    private long seen;

    /** @return true when this camera frame should be drawn into the recorder. */
    boolean admit(long nowNs, int targetFps) {
        seen++;
        if (lastFrameNs != 0L) {
            long delta = nowNs - lastFrameNs;
            if (delta > 0L) {
                smoothedIntervalNs = smoothedIntervalNs == 0L
                        ? delta : (smoothedIntervalNs * 7L + delta) / 8L;
            }
        }
        lastFrameNs = nowNs;
        if (targetFps <= 0) {
            admitted++;
            return true;
        }
        if (targetFps != lastTargetFps) {
            lastTargetFps = targetFps;
            nextDueNs = 0L;
        }
        long intervalNs = 1_000_000_000L / targetFps;
        if (smoothedIntervalNs == 0L
                || smoothedIntervalNs * 100L >= intervalNs * ENGAGE_BELOW_PCT) {
            nextDueNs = 0L;
            admitted++;
            return true;
        }
        if (nextDueNs != 0L && nowNs < nextDueNs - intervalNs / 4L) {
            return false;
        }
        nextDueNs = (nextDueNs == 0L || nowNs - nextDueNs > intervalNs)
                ? nowNs + intervalNs
                : nextDueNs + intervalNs;
        admitted++;
        return true;
    }

    /** Returns {admitted, seen} since the last call and resets both. */
    long[] drainCounts() {
        long[] out = {admitted, seen};
        admitted = 0L;
        seen = 0L;
        return out;
    }
}
