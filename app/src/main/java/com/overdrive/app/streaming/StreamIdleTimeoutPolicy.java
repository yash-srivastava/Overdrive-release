package com.overdrive.app.streaming;

/**
 * Monotonic, one-shot idle timeout policy shared by the direct WebSocket port
 * and the HTTP /ws subscriber path. No Android dependencies for JVM checks.
 */
final class StreamIdleTimeoutPolicy {
    private StreamIdleTimeoutPolicy() { }

    static boolean shouldArm(boolean stopped, int directClients,
            int externalClients, boolean shutdownTriggered) {
        return !stopped && !shutdownTriggered
                && directClients == 0 && externalClients == 0;
    }

    /** Milliseconds until the last-disconnect deadline; zero when due. */
    static long remainingMs(long nowMs, long disconnectedAtMs, long timeoutMs) {
        if (timeoutMs <= 0L) return 0L;
        // Clock resets or an unexpectedly future disconnect timestamp must not
        // force an early shutdown. elapsedRealtime normally cannot go back.
        if (nowMs < disconnectedAtMs) return timeoutMs;
        long elapsed = nowMs - disconnectedAtMs;
        return elapsed >= timeoutMs ? 0L : timeoutMs - elapsed;
    }
}
