package com.overdrive.app.geo;

/**
 * A single-token interval gate driven by a caller-supplied monotonic clock.
 *
 * <p>{@code System.nanoTime()} has an arbitrary origin: its value can be zero
 * or negative. Track whether a token was granted rather than treating zero as
 * an initial absolute deadline. Calculate elapsed time by subtraction, which
 * also handles signed {@code nanoTime} wraparound for short intervals.
 *
 * <p>Not thread-safe by itself; NominatimRateLimiter holds its class monitor
 * when calling either method.
 */
final class NominatimTokenGate {

    private final long intervalNs;
    private boolean hasGranted;
    private long lastGrantNs;

    NominatimTokenGate(long intervalNs) {
        if (intervalNs <= 0) {
            throw new IllegalArgumentException("intervalNs must be positive");
        }
        this.intervalNs = intervalNs;
    }

    /** Consume a token when the gate is open; otherwise leave state unchanged. */
    boolean tryAcquire(long nowNs) {
        if (isThrottled(nowNs)) return false;
        lastGrantNs = nowNs;
        hasGranted = true;
        return true;
    }

    /** Read the gate state without consuming its token. */
    boolean isThrottled(long nowNs) {
        return hasGranted && nowNs - lastGrantNs < intervalNs;
    }
}
