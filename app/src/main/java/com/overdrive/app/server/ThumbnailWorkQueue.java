package com.overdrive.app.server;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Deduplicates and caps the outstanding thumbnail work before executor submission. */
final class ThumbnailWorkQueue {
    private final int maxPending;
    private final Executor executor;
    private final Set<String> pending = new HashSet<>();

    ThumbnailWorkQueue(int maxPending, Executor executor) {
        if (maxPending <= 0) throw new IllegalArgumentException("maxPending must be positive");
        this.maxPending = maxPending;
        this.executor = executor;
    }

    /** Returns false for a duplicate or a request that can retry after saturation. */
    boolean submit(String key, Runnable work) {
        synchronized (pending) {
            if (pending.size() >= maxPending || !pending.add(key)) return false;
        }
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } finally {
                    release(key);
                }
            });
            return true;
        } catch (RejectedExecutionException ignored) {
            release(key);
            return false;
        }
    }

    private void release(String key) {
        synchronized (pending) {
            pending.remove(key);
        }
    }
}
