package com.overdrive.app.monitor;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Coalesces concurrent cache readers without throttling explicit network-change probes. */
final class NetworkRefreshGate {
    private volatile long completedAttempts;

    <T> T read(BooleanSupplier stale, Runnable probe, Supplier<T> snapshot) {
        long observed = completedAttempts;
        synchronized (this) {
            // Recheck freshness after taking the lock. If an overlapping probe failed,
            // share its result as well; a later independent read can retry normally.
            if (completedAttempts == observed && stale.getAsBoolean()) {
                runProbe(probe);
            }
            // JSON must not observe fields halfway through a concurrent forced refresh.
            return snapshot.get();
        }
    }

    synchronized void force(Runnable probe) {
        runProbe(probe);
    }

    private void runProbe(Runnable probe) {
        try {
            probe.run();
        } finally {
            completedAttempts++;
        }
    }
}
