package com.overdrive.app.remoteaccess;

/**
 * Coalesces native adapter events into one immediate check and one settling check.
 * Scheduling belongs to the existing Bluetooth watcher; this class owns no resources.
 */
public final class BluetoothRadioRecoveryEvents {
    public static final long SETTLE_DELAY_MS = 500;

    public interface Cancellation { void cancel(); }
    public interface Scheduler {
        /** Enqueues work; implementations must not execute it inline. */
        Cancellation schedule(Runnable task, long delayMs);
    }

    private final Scheduler scheduler;
    private final Runnable reconcile;
    private long generation;
    private boolean immediatePending, checking;
    private Cancellation settling;

    public BluetoothRadioRecoveryEvents(Scheduler scheduler, Runnable reconcile) {
        this.scheduler = scheduler;
        this.reconcile = reconcile;
    }

    public synchronized void onAdapterChanged() {
        long current = ++generation;
        if (!immediatePending && !checking) {
            immediatePending = true;
            try {
                scheduler.schedule(this::checkImmediately, 0);
            } catch (RuntimeException rejected) {
                immediatePending = false;
                throw rejected;
            }
        }
        if (settling != null) settling.cancel();
        settling = scheduler.schedule(() -> checkSettled(current), SETTLE_DELAY_MS);
    }

    private void checkImmediately() {
        synchronized (this) {
            immediatePending = false;
            if (checking) return;
            checking = true;
        }
        check();
    }

    private void checkSettled(long expectedGeneration) {
        synchronized (this) {
            if (expectedGeneration != generation) return;
            settling = null;
            if (checking) {
                // A new event arrived during a slow native check; it still needs
                // one check of the fresh state after that operation completes.
                settling = scheduler.schedule(
                        () -> checkSettled(expectedGeneration), SETTLE_DELAY_MS);
                return;
            }
            checking = true;
        }
        check();
    }

    private void check() {
        try {
            reconcile.run();
        } finally {
            synchronized (this) { checking = false; }
        }
    }
}
