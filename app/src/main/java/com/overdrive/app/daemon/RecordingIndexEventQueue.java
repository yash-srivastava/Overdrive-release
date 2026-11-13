package com.overdrive.app.daemon;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Bounded, path-coalescing handoff from filesystem notifications to index IO.
 *
 * <p>Only one drain task is scheduled per burst. The executor must run tasks
 * serially; neither filesystem reads nor database work run in submit(). A
 * distinct-path overflow requests a full index reconciliation instead of
 * silently leaving recordings invisible until the hourly maintenance pass.
 *
 * <p>This class has no Android dependencies so its ordering, overflow and
 * lifecycle behavior can be tested independently of FileObserver and H2.
 */
final class RecordingIndexEventQueue {
    enum Change { REFRESH, REMOVE }

    interface Listener {
        void apply(String mp4Path, Change change);
        void reconcile();
    }

    private final Executor executor;
    private final Listener listener;
    private final int maxPending;
    private final LinkedHashMap<String, Change> pending = new LinkedHashMap<>();
    private boolean workerScheduled;
    private boolean reconciliationNeeded;
    private boolean closed;

    RecordingIndexEventQueue(Executor executor, Listener listener, int maxPending) {
        if (executor == null || listener == null || maxPending < 1) {
            throw new IllegalArgumentException("executor, listener, and positive capacity required");
        }
        this.executor = executor;
        this.listener = listener;
        this.maxPending = maxPending;
    }

    /** Never waits for IO, even when the worker is blocked on removable storage. */
    void submit(String mp4Path, Change change) {
        if (mp4Path == null || mp4Path.isEmpty() || change == null) return;
        boolean schedule = false;
        synchronized (this) {
            if (closed) return;
            if (pending.containsKey(mp4Path) || pending.size() < maxPending) {
                // Last event for a path wins, while insertion order remains stable.
                // An event arriving after dequeue becomes a fresh work item.
                pending.put(mp4Path, change);
            } else {
                reconciliationNeeded = true;
            }
            if (!workerScheduled) {
                workerScheduled = true;
                schedule = true;
            }
        }
        if (!schedule) return;
        try {
            executor.execute(this::drain);
        } catch (RuntimeException failure) {
            // The executor may be shutting down. A rejected drain must not
            // strand the scheduled bit or silently lose the whole batch.
            boolean reconcile;
            synchronized (this) {
                reconcile = !closed && (!pending.isEmpty() || reconciliationNeeded);
                pending.clear();
                reconciliationNeeded = false;
                workerScheduled = false;
            }
            if (reconcile) safeReconcile();
        }
    }

    /** Reject late FileObserver callbacks and drop work not yet dispatched. */
    synchronized void close() {
        closed = true;
        pending.clear();
        reconciliationNeeded = false;
    }

    private void drain() {
        while (true) {
            String path = null;
            Change change = null;
            boolean reconcile = false;
            synchronized (this) {
                if (closed) {
                    workerScheduled = false;
                    return;
                }
                if (!pending.isEmpty()) {
                    Iterator<Map.Entry<String, Change>> iterator = pending.entrySet().iterator();
                    Map.Entry<String, Change> next = iterator.next();
                    path = next.getKey();
                    change = next.getValue();
                    iterator.remove();
                } else if (reconciliationNeeded) {
                    reconciliationNeeded = false;
                    reconcile = true;
                } else {
                    workerScheduled = false;
                    return;
                }
            }
            if (reconcile) {
                safeReconcile();
            } else {
                try {
                    listener.apply(path, change);
                } catch (Throwable failure) {
                    // Keep draining after a failed file/index operation;
                    // request an eventual repair for this and other paths.
                    synchronized (this) {
                        if (!closed) reconciliationNeeded = true;
                    }
                }
            }
        }
    }

    private void safeReconcile() {
        synchronized (this) {
            if (closed) return;
        }
        try {
            listener.reconcile();
        } catch (Throwable ignored) {
            // The index's normal periodic reconcile is the final backstop.
            // Never wedge the drain gate on a failing error handler.
        }
    }
}
