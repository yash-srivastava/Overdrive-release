package com.overdrive.app.server.hls;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Process-wide LRU of parsed indexes keyed by path + length + mtime, capped by entry count and by
 * approximate table bytes. Concurrent misses on one key share a parse; failures are kept for 60 s.
 */
public final class Mp4IndexCache {
    private static final int CAPACITY = 24;
    static final long MAX_BYTES = 16L << 20;
    // Per sample: decode time and file offset (8 bytes each), size and cts offset (4 each).
    static final long BYTES_PER_SAMPLE = 24L;
    static final long BYTES_PER_GOP = 32L;
    private static final int FAILURE_CAPACITY = 64;
    private static final long FAILURE_TTL_NANOS = 60_000_000_000L;

    private static final Object LOCK = new Object();
    // Access order; trimmed in put() so the byte total stays exact.
    private static final LinkedHashMap<String, Entry> INDEXES = new LinkedHashMap<>(32, 0.75f, true);
    private static final Map<String, FutureTask<Mp4Index>> IN_FLIGHT = new HashMap<>();
    private static final Map<String, Failure> FAILURES = new LinkedHashMap<String, Failure>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Failure> eldest) {
            return size() > FAILURE_CAPACITY;
        }
    };
    private static long totalBytes;

    private static final class Entry {
        final Mp4Index index;
        final long bytes;

        Entry(Mp4Index index, long bytes) {
            this.index = index;
            this.bytes = bytes;
        }
    }

    private static final class Failure {
        final String reason;
        final long atNanos;

        Failure(String reason, long atNanos) {
            this.reason = reason;
            this.atNanos = atNanos;
        }
    }

    private Mp4IndexCache() {}

    public static Mp4Index get(File f) throws Mp4Exception {
        if (f == null) throw new Mp4Exception("no file");
        RemovableStorageFs.FileStamp stamp = RemovableStorageFs.fileStamp(f);
        long len = stamp.length;
        long mtime = stamp.mtime;
        String key = f.getAbsolutePath() + '|' + len + '|' + mtime;
        FutureTask<Mp4Index> task;
        boolean owner = false;
        synchronized (LOCK) {
            Entry hit = INDEXES.get(key);
            if (hit != null) return hit.index;
            Failure fail = FAILURES.get(key);
            if (fail != null) {
                if (System.nanoTime() - fail.atNanos < FAILURE_TTL_NANOS) throw new Mp4Exception(fail.reason);
                FAILURES.remove(key);
            }
            task = IN_FLIGHT.get(key);
            if (task == null) {
                task = new FutureTask<>(() -> Mp4Index.parse(f));
                IN_FLIGHT.put(key, task);
                owner = true;
            }
        }
        if (owner) parse(task, key, len, mtime);
        try {
            return task.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Mp4Exception) throw (Mp4Exception) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for a parse");
        }
    }

    // Outside the lock; the result is published before the in-flight entry is dropped.
    private static void parse(FutureTask<Mp4Index> task, String key, long len, long mtime) {
        long started = System.nanoTime();
        task.run();
        synchronized (LOCK) {
            try {
                Mp4Index idx = task.get();
                if (idx.fileLength() == len && idx.lastModified() == mtime) put(key, idx);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Mp4Exception) {
                    FAILURES.put(key, new Failure(e.getCause().getMessage(), started));
                }
            } catch (InterruptedException e) {
                // Unreachable: the task has completed.
                Thread.currentThread().interrupt();
            } finally {
                IN_FLIGHT.remove(key);
            }
        }
    }

    private static void put(String key, Mp4Index idx) {
        Entry entry = new Entry(idx, bytes(idx));
        Entry old = INDEXES.put(key, entry);
        if (old != null) totalBytes -= old.bytes;
        totalBytes += entry.bytes;
        // Eldest first; the newest stays even alone over budget, its segments are about to be served.
        Iterator<Entry> it = INDEXES.values().iterator();
        while ((INDEXES.size() > CAPACITY || totalBytes > MAX_BYTES) && INDEXES.size() > 1) {
            totalBytes -= it.next().bytes;
            it.remove();
        }
    }

    static long bytes(Mp4Index idx) {
        long samples = (idx.video != null ? idx.video.count : 0) + (idx.audio != null ? idx.audio.count : 0);
        return samples * BYTES_PER_SAMPLE + (long) (idx.gopFirst.length) * BYTES_PER_GOP;
    }
}
