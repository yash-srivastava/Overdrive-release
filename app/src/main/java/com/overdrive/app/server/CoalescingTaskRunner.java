package com.overdrive.app.server;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

final class CoalescingTaskRunner {
    private final Executor executor;
    private final Runnable task;
    private final AtomicBoolean requested = new AtomicBoolean(false);
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);

    CoalescingTaskRunner(Executor executor, Runnable task) {
        this.executor = executor;
        this.task = task;
    }

    void request() {
        requested.set(true);
        scheduleIfNeeded();
    }

    private void scheduleIfNeeded() {
        if (!workerRunning.compareAndSet(false, true)) return;
        try {
            executor.execute(this::drainRequests);
        } catch (RuntimeException | Error failure) {
            workerRunning.set(false);
            throw failure;
        }
    }

    private void drainRequests() {
        try {
            while (requested.getAndSet(false)) {
                task.run();
            }
        } finally {
            workerRunning.set(false);
            // Covers a request that lands after the loop's final false read but
            // before workerRunning is cleared. Conditional scheduling keeps the
            // handoff race from losing that request.
            if (requested.get()) scheduleIfNeeded();
        }
    }
}