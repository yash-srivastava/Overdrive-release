package com.overdrive.app.camera;

import android.os.SystemClock;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AiLaneWorker — runs sentry.processFrame() on a dedicated thread so the
 * GL render loop doesn't block on V2 motion native + downstream AI work.
 *
 * Flow:
 *   GL thread: downscaler.readPixelsDirect(textureId) → byte[]
 *   GL thread: aiWorker.submitFrame(byte[]) — non-blocking, drops frame if busy
 *   Worker thread: sentry.processFrame(byte[]) — V2 motion native +
 *                  any further async dispatch (YOLO already runs on its
 *                  own aiExecutor inside SurveillanceEngineGpu)
 *
 * Drop-not-queue policy: at fixed AI cadence (V2 motion is throttled to
 * 10 fps internally via MOTION_PROCESS_INTERVAL_MS) we never want to build
 * up a backlog. If the worker is mid-processing when GL submits a new
 * frame, we recycle the new frame back to the downscaler pool and skip.
 * The next render loop iteration will get a fresh frame anyway.
 *
 * The recycle-on-drop is essential: downscaler.readPixelsDirect borrows
 * from a fixed pool; failing to recycle leaks the buffer.
 */
public final class AiLaneWorker {

    private static final DaemonLogger logger = DaemonLogger.getInstance("AiLaneWorker");

    public interface FrameRecycler {
        /** Called from worker when a submitted frame can't be processed (busy/shutdown). */
        void recycle(byte[] frame);
    }

    private final ExecutorService executor;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile long busySinceMs = 0;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private volatile SurveillanceEngineGpu sentry;
    private final FrameRecycler recycler;

    private volatile long droppedFrames = 0;
    private volatile long processedFrames = 0;

    public AiLaneWorker(FrameRecycler recycler) {
        this.recycler = recycler;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AiLaneWorker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
    }

    /** Bind/unbind the sentry. Null detaches; safe to call from GL thread. */
    public void setSentry(SurveillanceEngineGpu sentry) {
        this.sentry = sentry;
    }

    /**
     * Submit an RGB frame for AI processing. Non-blocking. If the worker is
     * still processing the previous frame, the new frame is recycled and
     * dropped. Returns true if the frame was queued, false if dropped.
     */
    public boolean submitFrame(byte[] rgbFrame) {
        if (rgbFrame == null) return false;
        if (shutdown.get()) {
            recycler.recycle(rgbFrame);
            return false;
        }
        SurveillanceEngineGpu s = sentry;
        if (s == null || !s.isActive()) {
            recycler.recycle(rgbFrame);
            return false;
        }
        if (!busy.compareAndSet(false, true)) {
            // Worker still processing previous frame; check watchdog in case of stall.
            long busyStart = busySinceMs;
            if (busyStart > 0 && (SystemClock.elapsedRealtime() - busyStart) > 3000L) {
                logger.warn("AiLaneWorker busy state held >3s — force-clearing watchdog");
                busy.set(false);
                busySinceMs = 0;
            }
            droppedFrames++;
            recycler.recycle(rgbFrame);
            return false;
        }
        busySinceMs = SystemClock.elapsedRealtime();
        // Stage the Runnable so we can deregister it from inFlightFrames
        // when it actually runs OR recycle the captured frame on forced
        // shutdown via shutdownNow.
        final Runnable[] taskRef = new Runnable[1];
        Runnable task = () -> {
            // Mark as started — shutdownNow's "not yet run" list will not
            // contain us once we're past this point.
            inFlightFrames.remove(taskRef[0]);
            try {
                SurveillanceEngineGpu sNow = sentry;
                if (sNow != null && sNow.isActive()) {
                    // sentry.processFrame recycles the buffer in its own
                    // finally block. We do NOT recycle here on success.
                    sNow.processFrame(rgbFrame);
                    processedFrames++;
                } else {
                    recycler.recycle(rgbFrame);
                }
            } catch (Throwable t) {
                logger.warn("AI lane processing error: " + t.getMessage());
                // sentry.processFrame normally recycles in finally; if it
                // threw before reaching the recycle, we recycle here as
                // a safety net (double-recycle is bounded by the pool's
                // ArrayBlockingQueue offer policy).
                try { recycler.recycle(rgbFrame); } catch (Throwable ignored) {}
            } finally {
                busySinceMs = 0;
                busy.set(false);
            }
        };
        taskRef[0] = task;
        try {
            inFlightFrames.put(task, rgbFrame);
            executor.execute(task);
            return true;
        } catch (Throwable t) {
            // Executor rejected (post-shutdown). Recycle and clear tracking + busy.
            inFlightFrames.remove(task);
            busySinceMs = 0;
            busy.set(false);
            recycler.recycle(rgbFrame);
            return false;
        }
    }

    /** Diagnostic counters for the periodic Stats log. */
    public long getDroppedFrames() {
        return droppedFrames;
    }

    public long getProcessedFrames() {
        return processedFrames;
    }

    /**
     * Returns true when the worker is mid-processFrame and a new submit
     * would be dropped. The GL render loop uses this to skip the expensive
     * readPixelsDirect path entirely on frames that would be dropped anyway,
     * avoiding 1.2 MB readback + Java RGBA→RGB Y-flip + the GPU pipeline
     * flush that glReadPixels forces. V2 motion is throttled to ~10 fps
     * internally (MOTION_PROCESS_INTERVAL_MS=100), so at 30 fps camera the
     * worker is busy ~67% of frames — skipping readback on those frames
     * cuts GL-thread load by ~2/3 without losing any motion detection.
     *
     * Cheap atomic read; safe to call from the GL thread every iteration.
     */
    public boolean isBusy() {
        if (!busy.get()) return false;
        long busyStart = busySinceMs;
        if (busyStart > 0 && (SystemClock.elapsedRealtime() - busyStart) > 3000L) {
            logger.warn("AiLaneWorker isBusy held >3s — force-clearing watchdog");
            busy.set(false);
            busySinceMs = 0;
            return false;
        }
        return true;
    }

    public void resetCounters() {
        droppedFrames = 0;
        processedFrames = 0;
    }

    // Track frames that have been queued to the executor but not yet run, so
    // we can recycle them on forced shutdown. Without this, a shutdownNow()
    // path discards the Runnable (and its captured byte[] frame) and leaks
    // one downscaler-pool buffer per shutdown.
    private final java.util.concurrent.ConcurrentHashMap<Runnable, byte[]> inFlightFrames =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Shutdown — drains in-flight work, then refuses new submissions.
     *  Any unrun Runnables (frames queued but not yet processed) have their
     *  byte[] payloads returned to the recycler so the downscaler buffer
     *  pool isn't leaked. */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        executor.shutdown();
        java.util.List<Runnable> notRun = java.util.Collections.emptyList();
        try {
            if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                notRun = executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            notRun = executor.shutdownNow();
        }
        // Recycle byte[] frames captured by any Runnable that was queued
        // but never started. inFlightFrames is keyed by the Runnable itself.
        for (Runnable r : notRun) {
            byte[] f = inFlightFrames.remove(r);
            if (f != null) {
                try { recycler.recycle(f); } catch (Throwable ignored) {}
            }
        }
        // Anything else in the map represents Runnables that started but
        // didn't finish (e.g., interrupted). Their try/finally already calls
        // recycle on exception paths, so just clear the tracking map.
        inFlightFrames.clear();
    }
}
