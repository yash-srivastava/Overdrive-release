package com.overdrive.app.services;

/** Wall-clock checkpoints survive process restarts, reconnection and ordinary IVI sleep. */
final class AutoStartCheckpointPolicy {
    static final long FIRST_DELAY_MS = 60 * 60_000L;
    static final long FINAL_DELAY_MS = 24 * FIRST_DELAY_MS;

    private AutoStartCheckpointPolicy() {}

    static long delayUntilNextCheckpoint(long firstConfirmedAt, long now, boolean firstFollowUpDone) {
        long target = firstFollowUpDone ? FINAL_DELAY_MS : FIRST_DELAY_MS;
        return Math.max(0L, target - elapsed(firstConfirmedAt, now));
    }

    /** If the first eligible session is already a day later, one check covers both checkpoints. */
    static boolean completesFinalCheckpoint(long firstConfirmedAt, long now, boolean firstFollowUpDone) {
        return delayUntilNextCheckpoint(firstConfirmedAt, now, firstFollowUpDone) == 0L
                && (firstFollowUpDone || elapsed(firstConfirmedAt, now) >= FINAL_DELAY_MS);
    }

    private static long elapsed(long firstConfirmedAt, long now) {
        // A backwards clock correction must not defer a check indefinitely.
        return firstConfirmedAt <= 0L || now < firstConfirmedAt
                ? Long.MAX_VALUE : now - firstConfirmedAt;
    }
}
