package com.overdrive.app.server.timeline;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.surveillance.LensTag;
import com.overdrive.app.util.VideoPreviewFrame;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Timeline scrub stills: one decoder thread, at most four queued clips (a newer request for a
 * queued clip replaces it), JPEGs cached under thumbs/tl-frames. Callers pass index-resolved files.
 */
public final class FrameService {

    public static final int OK = 0;
    public static final int BUSY = 1;
    public static final int FAILED = 2;

    public static final class Frame {
        public final int status;
        public final byte[] jpeg;

        Frame(int status, byte[] jpeg) {
            this.status = status;
            this.jpeg = jpeg;
        }
    }

    private static final Frame BUSY_FRAME = new Frame(BUSY, null);
    private static final Frame FAILED_FRAME = new Frame(FAILED, null);

    private static final int QUEUE_MAX = 4;
    private static final long WAIT_MS = 3000;
    private static final long BUCKET_MS = 1000;
    private static final int JPEG_QUALITY = 80;
    private static final long CACHE_MAX_BYTES = 64L * 1024 * 1024;
    private static final long CACHE_TRIM_TO_BYTES = 48L * 1024 * 1024;
    private static final long CACHE_FILE_MAX_BYTES = 8L * 1024 * 1024;
    private static final long STRAY_FILE_AGE_MS = 60_000;
    private static final long FAILURE_TTL_NS = TimeUnit.SECONDS.toNanos(60);
    private static final long SWEEP_EVERY_NS = TimeUnit.MINUTES.toNanos(10);
    // Do not retain a decoder thread or wake the storage volume after the viewer goes away.
    private static final long WORKER_IDLE_NS = TimeUnit.SECONDS.toNanos(30);
    private static final int FAILURES_MAX = 256;

    private static final Pattern RECORDING_ID = Pattern.compile("[0-9a-f]{32}");
    // Recordings that left the index: their stills go too (a deleted clip leaves no frames behind).
    private static final java.util.Set<String> FORGET = new java.util.HashSet<>();
    private static boolean forgetting;
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9-]{1,64}");

    private static final Object LOCK = new Object();
    /** Queued jobs by recording id, oldest first. Guarded by LOCK. */
    private static final LinkedHashMap<String, Job> PENDING = new LinkedHashMap<>();
    /** Recent render failures (frame key or clip key) by System.nanoTime. Guarded by LOCK. */
    private static final LinkedHashMap<String, Long> FAILURES =
            new LinkedHashMap<String, Long>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > FAILURES_MAX;
                }
            };
    private static Job running;
    private static Thread worker;
    private static long runningSinceNs;
    // Guarded by LOCK. Retaining the deadline across idle worker retirement
    // prevents a full cache sweep on every reopen of the timeline.
    private static long nextSweepNs;
    private static int generation;
    private static final java.util.List<Thread> ABANDONED = new java.util.ArrayList<>();
    private static final long STUCK_NS = TimeUnit.SECONDS.toNanos(10);
    private static final int MAX_ABANDONED = 2;

    // Worker thread only.
    private static long cacheBytes = -1;
    private static boolean cacheOff;
    private static long cacheOffSinceNs;

    private FrameService() {}

    private static int lensStrength() {
        try {
            return com.overdrive.app.config.UnifiedConfigManager.getRectifyStrength();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** JPEG of the keyframe at or before {@code atMs} (rounded down to the second), {@code width} wide. */
    public static Frame frame(String id, String fp, File mp4, long atMs, int width) {
        if (id == null || fp == null || mp4 == null || width <= 0
                || !RECORDING_ID.matcher(id).matches() || !FINGERPRINT.matcher(fp).matches()) {
            return FAILED_FRAME;
        }
        long bucket = Math.max(0, atMs) / BUCKET_MS * BUCKET_MS;
        String key = id + "_" + fp + "_" + bucket + "_" + width + "_s" + lensStrength();
        String clipKey = "clip_" + id + "_" + fp;
        File dir = cacheDir();
        File cached = dir == null ? null : new File(dir, key + ".jpg");
        byte[] hit = readCached(cached);
        if (hit != null) return new Frame(OK, hit);

        Job job;
        synchronized (LOCK) {
            long now = System.nanoTime();
            if (failedRecently(key, now) || failedRecently(clipKey, now)) return FAILED_FRAME;
            if (running != null) ensureWorker();
            Job queued = PENDING.get(id);
            if (running != null && running.key.equals(key)) {
                job = running;
            } else if (queued != null && queued.key.equals(key)) {
                job = queued;
            } else {
                if (queued == null && PENDING.size() >= QUEUE_MAX) return BUSY_FRAME;
                job = new Job(key, clipKey, mp4, bucket, width, cached);
                PENDING.put(id, job);
                if (queued != null) queued.finish(BUSY_FRAME);
                ensureWorker();
                LOCK.notifyAll();
            }
        }
        return job.await(WAIT_MS);
    }

    private static boolean failedRecently(String key, long nowNs) {
        Long at = FAILURES.get(key);
        if (at == null) return false;
        if (nowNs - at < FAILURE_TTL_NS) return true;
        FAILURES.remove(key);
        return false;
    }

    private static void ensureWorker() {
        if (worker != null && worker.isAlive()) {
            if (running == null || System.nanoTime() - runningSinceNs < STUCK_NS) return;
            // A decode stuck in native code cannot be interrupted: retire that thread and start a fresh one.
            for (java.util.Iterator<Thread> it = ABANDONED.iterator(); it.hasNext(); ) {
                if (!it.next().isAlive()) it.remove();
            }
            if (ABANDONED.size() >= MAX_ABANDONED) return;
            ABANDONED.add(worker);
            generation++;
            fail(running.clipKey);
            running.finish(FAILED_FRAME);
            running = null;
            worker = null;
            CameraDaemon.log("Timeline: frame decode stuck > 10 s, worker replaced");
        }
        if (running != null) {
            running.finish(FAILED_FRAME);
            running = null;
        }
        Thread thread = new Thread(FrameService::loop, "TimelineFrames");
        thread.setDaemon(true);
        try {
            thread.start();
            worker = thread;
        } catch (Throwable t) {
            // Jobs stay queued; the next request retries the start.
            worker = null;
            CameraDaemon.log("Timeline: frame worker start failed: " + t);
        }
    }

    private static void loop() {
        final int myGeneration;
        synchronized (LOCK) {
            myGeneration = generation;
        }
        long lastJobNs = System.nanoTime();
        while (true) {
            Job job = null;
            boolean sweep = false;
            synchronized (LOCK) {
                if (myGeneration != generation) return;
                if (nextSweepNs == 0) nextSweepNs = System.nanoTime();
                if (PENDING.isEmpty()) {
                    long now = System.nanoTime();
                    long idleLeftNs = WORKER_IDLE_NS - (now - lastJobNs);
                    if (idleLeftNs <= 0) {
                        // The null assignment and the empty-queue check share LOCK
                        // with enqueue. A new request therefore starts a new worker
                        // rather than stranding a job on an exiting thread.
                        if (worker == Thread.currentThread()) worker = null;
                        return;
                    }
                    long sweepLeftNs = Math.max(0L, nextSweepNs - now);
                    long waitNs = Math.min(idleLeftNs, sweepLeftNs);
                    if (waitNs > 0) {
                        try {
                            LOCK.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(waitNs)));
                        } catch (InterruptedException e) {
                            if (worker == Thread.currentThread()) worker = null;
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                if (!PENDING.isEmpty()) {
                    Iterator<Job> it = PENDING.values().iterator();
                    job = it.next();
                    it.remove();
                    running = job;
                    runningSinceNs = System.nanoTime();
                    lastJobNs = runningSinceNs;
                } else {
                    sweep = System.nanoTime() - nextSweepNs >= 0;
                }
            }
            if (job != null) {
                Frame result = FAILED_FRAME;
                try {
                    result = render(job);
                } catch (Throwable t) {
                    fail(job.clipKey);
                } finally {
                    synchronized (LOCK) {
                        if (myGeneration != generation) return;
                        if (running == job) running = null;
                    }
                    job.finish(result);
                }
            } else if (sweep) {
                try {
                    sweep();
                } catch (Throwable ignored) {
                }
                synchronized (LOCK) {
                    // A retired worker must not modify the new worker's clock.
                    if (myGeneration != generation) return;
                    nextSweepNs = System.nanoTime() + SWEEP_EVERY_NS;
                }
            }
        }
    }

    private static Frame render(Job job) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap scaled = null;
        Bitmap corrected = null;
        try (FileInputStream in = RemovableStorageFs.openInput(job.mp4)) {
            try {
                // The String overload needs an Application, which the daemon does not have.
                if (RemovableStorageFs.needsMediaDataSource(job.mp4)) RemovableStorageFs.setDataSource(retriever, job.mp4);
                else retriever.setDataSource(in.getFD());
            } catch (RuntimeException e) {
                fail(job.clipKey);
                return FAILED_FRAME;
            }
            scaled = VideoPreviewFrame.extract(retriever, job.atMs * 1000L,
                    MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, job.width, 0);
            if (scaled == null) {
                fail(job.key);
                return FAILED_FRAME;
            }
            corrected = LensTag.dewarpFrameForClip(scaled, job.mp4);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(64 * 1024);
            if (corrected == null
                    || !corrected.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpeg)
                    || jpeg.size() == 0) {
                fail(job.key);
                return FAILED_FRAME;
            }
            byte[] data = jpeg.toByteArray();
            store(job.cacheFile, data);
            return new Frame(OK, data);
        } catch (Throwable t) {
            fail(job.clipKey);
            return FAILED_FRAME;
        } finally {
            recycle(corrected);
            recycle(scaled);
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private static void fail(String key) {
        synchronized (LOCK) {
            FAILURES.put(key, System.nanoTime());
        }
    }

    /** Same base as the recordings thumbnail cache (recordings dir parent / thumbs). */
    private static File cacheDir() {
        try {
            File base = new File(StorageManager.getInstance().getRecordingsPath()).getParentFile();
            return base == null ? null : new File(new File(base, "thumbs"), "tl-frames");
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readCached(File file) {
        if (file == null) return null;
        long length = RemovableStorageFs.length(file);
        if (length <= 0 || length > CACHE_FILE_MAX_BYTES) return null;
        try {
            byte[] data = RemovableStorageFs.readAllBytes(file);
            if (data.length == 0) return null;
            // Reading cached scrub frames is common during seeking. Throttle the
            // on-disk LRU timestamp update rather than writing metadata on every hit.
            CacheFileTouch.onHit(file, System.currentTimeMillis());
            return data;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void store(File target, byte[] data) {
        if (target == null) return;
        if (cacheOff && System.nanoTime() - cacheOffSinceNs < SWEEP_EVERY_NS) return;
        cacheOff = false;
        File dir = target.getParentFile();
        if (dir == null || (!RemovableStorageFs.isDirectory(dir) && !RemovableStorageFs.mkdirs(dir))) return;
        File part = new File(dir, target.getName() + ".part");
        try (FileOutputStream out = RemovableStorageFs.openOutput(part)) {
            out.write(data);
        } catch (IOException e) {
            RemovableStorageFs.delete(part);
            return;
        }
        if (!RemovableStorageFs.renameTo(part, target)) {
            RemovableStorageFs.delete(part);
            return;
        }
        if (cacheBytes >= 0) cacheBytes += data.length;
        if (cacheBytes < 0 || cacheBytes > CACHE_MAX_BYTES) trim(dir);
    }

    /** Oldest-first eviction down to 48 MB once over 64 MB; no listing means no further writes for 10 min. */
    private static void trim(File dir) {
        File[] files = RemovableStorageFs.listFiles(dir);
        if (files == null) {
            cacheOff = true;
            cacheOffSinceNs = System.nanoTime();
            return;
        }
        long total = 0;
        long[] sizes = new long[files.length];
        long[] stamps = new long[files.length];
        Integer[] order = new Integer[files.length];
        for (int i = 0; i < files.length; i++) {
            sizes[i] = RemovableStorageFs.length(files[i]);
            stamps[i] = RemovableStorageFs.lastModified(files[i]);
            order[i] = i;
            total += sizes[i];
        }
        if (total > CACHE_MAX_BYTES) {
            Arrays.sort(order, (a, b) -> Long.compare(stamps[a], stamps[b]));
            for (Integer i : order) {
                if (total <= CACHE_TRIM_TO_BYTES) break;
                if (RemovableStorageFs.delete(files[i])) total -= sizes[i];
            }
        }
        cacheBytes = total;
    }

    /** Deletes a removed recording's cached stills off the caller's thread; bulk deletes batch into one pass. */
    public static void forget(String recordingId) {
        if (recordingId == null || !RECORDING_ID.matcher(recordingId).matches()) return;
        synchronized (FORGET) {
            FORGET.add(recordingId);
            if (forgetting) return;
            forgetting = true;
        }
        try {
            Thread t = new Thread(FrameService::forgetPending, "TlFramesForget");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            synchronized (FORGET) {
                forgetting = false;
            }
        }
    }

    private static void forgetPending() {
        boolean clean = false;
        try {
            forgetLoop();
            clean = true;
        } finally {
            // The loop clears the flag itself; only a throw leaves it set.
            if (!clean) {
                synchronized (FORGET) {
                    forgetting = false;
                }
            }
        }
    }

    private static void forgetLoop() {
        while (true) {
            // Lets a bulk delete batch up and keeps a long one from re-listing the folder back to back.
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
            }
            java.util.Set<String> ids;
            synchronized (FORGET) {
                if (FORGET.isEmpty()) {
                    forgetting = false;
                    return;
                }
                ids = new java.util.HashSet<>(FORGET);
                FORGET.clear();
            }
            try {
                File dir = cacheDir();
                File[] files = dir == null ? null : RemovableStorageFs.listFiles(dir);
                if (files == null) continue;
                for (File f : files) {
                    String n = f.getName();
                    if (n.length() > 33 && n.charAt(32) == '_' && ids.contains(n.substring(0, 32))) RemovableStorageFs.delete(f);
                }
            } catch (Throwable ignored) {
                // The periodic sweep catches anything left.
            }
        }
    }

    /** Drops stills of clips that left the index or changed, plus stray partial files. */
    private static void sweep() {
        File dir = cacheDir();
        if (dir == null || !RemovableStorageFs.isDirectory(dir)) return;
        File[] files = RemovableStorageFs.listFiles(dir);
        if (files == null) return;
        long now = System.currentTimeMillis();
        Map<String, Boolean> live = new HashMap<>();
        for (File file : files) {
            String name = file.getName();
            int idEnd = name.indexOf('_');
            int fpEnd = idEnd < 0 ? -1 : name.indexOf('_', idEnd + 1);
            if (idEnd != 32 || fpEnd < 0 || !name.endsWith(".jpg")) {
                if (now - RemovableStorageFs.lastModified(file) > STRAY_FILE_AGE_MS) RemovableStorageFs.delete(file);
                continue;
            }
            String id = name.substring(0, idEnd);
            String fp = name.substring(idEnd + 1, fpEnd);
            String clip = id + "_" + fp;
            Boolean keep = live.get(clip);
            if (keep == null) {
                TimelineData.ClipRef ref;
                try {
                    ref = TimelineData.clip(id);
                } catch (Throwable t) {
                    return;
                }
                keep = ref != null && fp.equals(ref.fp());
                live.put(clip, keep);
            }
            if (!keep) RemovableStorageFs.delete(file);
        }
        trim(dir);
    }

    private static final class Job {
        final String key;
        final String clipKey;
        final File mp4;
        final long atMs;
        final int width;
        final File cacheFile;
        private final CountDownLatch done = new CountDownLatch(1);
        private Frame result;

        Job(String key, String clipKey, File mp4, long atMs, int width, File cacheFile) {
            this.key = key;
            this.clipKey = clipKey;
            this.mp4 = mp4;
            this.atMs = atMs;
            this.width = width;
            this.cacheFile = cacheFile;
        }

        void finish(Frame frame) {
            synchronized (this) {
                if (result != null) return;
                result = frame;
            }
            done.countDown();
        }

        Frame await(long timeoutMs) {
            try {
                if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    synchronized (this) {
                        return result;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return BUSY_FRAME;
        }
    }
}
