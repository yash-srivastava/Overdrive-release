package com.overdrive.app.camera;

import java.util.concurrent.TimeUnit;

/**
 * Pure one-use proof policy for a completed ACC prewarm. A PID alone is never
 * sufficient: the caller must have recorded the end of a real HAL settle and
 * must perform its ordinary cold-open process probe before asking to reuse it.
 * The policy has no Android calls, making its boundary cases executable in JVM
 * tests. The owning {@link AvcHalWarmup} serializes all accesses on WARMUP_LANE.
 */
final class AvcHandoffSettlePolicy {
    static final long MAX_AGE_NANOS = TimeUnit.MILLISECONDS.toNanos(30_000L);

    private AvcHandoffSettlePolicy() {}

    // Preserve cold-open fail-open: an UNKNOWN pidof is not evidence that AVC
    // is running, so it still follows the explicit launcher path.
    static boolean requiresLaunch(boolean recoveryLaunch, boolean probeRunning) {
        return recoveryLaunch || !probeRunning;
    }

    static boolean cachedSettleIsFresh(long nowNanos, long completedNanos,
                                       long reuseWindowNanos) {
        if (completedNanos == Long.MIN_VALUE) return false;
        long age = nowNanos - completedNanos;
        return age >= 0 && age <= reuseWindowNanos;
    }

    /** Vendor open errors invalidate one-use proof, not an unrelated completed settle. */
    static void onVendorOpenFailure(Receipt receipt) {
        if (receipt != null) receipt.retire();
    }

    static final class Receipt {
        private long completedNanos = Long.MIN_VALUE;
        private int settledPid = -1;
        private boolean consumed;

        /** Called exclusively by the handoff background worker after post-settle RUNNING. */
        void issueFromCompletedPrewarm(long completedAtNanos, int pid) {
            if (!consumed && completedAtNanos != Long.MIN_VALUE && pid > 0) {
                completedNanos = completedAtNanos;
                settledPid = pid;
            }
        }

        /**
         * Returns true at most once. Rejects missing, stale, superseded,
         * recovered or inconclusive proof while preserving the caller's
         * separate original launch decision.
         */
        boolean consumeIfEligible(long nowNanos, long latestCompletedNanos,
                                  int currentPid, boolean running,
                                  boolean recoveryPending) {
            if (consumed) return false;
            consumed = true;
            if (!running || recoveryPending || settledPid <= 0
                    || settledPid != currentPid
                    || completedNanos == Long.MIN_VALUE
                    || completedNanos != latestCompletedNanos) {
                return false;
            }
            long ageNanos = nowNanos - completedNanos;
            return ageNanos >= 0 && ageNanos <= MAX_AGE_NANOS;
        }

        void retire() {
            consumed = true;
        }
    }
}
