package com.overdrive.app.safekeep;

/**
 * Monotonic elapsed-interval checks for SafeKeep's thermal refresh and recorder
 * backpressure hold. Callers supply Android's elapsedRealtime(), never wall time.
 * A negative prior timestamp means the event has not occurred yet.
 */
final class SafeKeepTiming {

    private SafeKeepTiming() {}

    /** True for the initial sample, a completed interval, or a reset clock. */
    static boolean intervalDue(long nowElapsedMs, long previousElapsedMs, long intervalMs) {
        return previousElapsedMs < 0
                || nowElapsedMs < previousElapsedMs
                || nowElapsedMs - previousElapsedMs >= intervalMs;
    }

    /**
     * True while the recorder is recovering, including when the elapsed clock
     * unexpectedly moves backwards (fail closed until a new healthy observation).
     */
    static boolean withinHold(long nowElapsedMs, long unhealthyElapsedMs, long holdMs) {
        if (unhealthyElapsedMs < 0) return false;
        return nowElapsedMs < unhealthyElapsedMs
                || nowElapsedMs - unhealthyElapsedMs < holdMs;
    }
}
