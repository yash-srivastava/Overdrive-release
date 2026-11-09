package com.overdrive.app.camera;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.GpuMosaicRecorder;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dedicated render thread for the recorder's encoder-blocking GL work
 * (decoupled encoder lane, camera.decoupledEncoderLane).
 *
 * <p>On the legacy path, {@code GpuMosaicRecorder.drawFrame} — makeCurrent on
 * the MediaCodec input surface, mosaic draw, overlay, eglSwapBuffers — runs
 * inline on the camera acquisition thread (GL-RenderLoop). eglSwapBuffers is
 * the encoder's native backpressure valve: under MediaCodec stalls it blocks
 * for 100–300 ms (field-logged), during which the acquisition thread neither
 * drains the camera ImageReader nor releases the bound gralloc buffer — the
 * mechanism behind the BYD native AVM losing its feed in reverse.
 *
 * <p>This lane moves that entire pass onto its own {@link HandlerThread} with
 * a child {@link EGLCore} in the parent's share group (same pattern as
 * {@code AiLaneGl}; recordable=true like the OEM dashcam pipeline's shared
 * encoder context). The acquisition thread hands over frames as
 * {@link Frame} packets referencing app-owned {@link CopiedFrameRing} slots —
 * never camera-owned buffers — via a latest-wins mailbox: if the encoder
 * stalls, packets are simply superseded (fewer encoded frames, wider PTS
 * deltas; the recorder's monotonic clamp already tolerates that) and the
 * camera side never waits.
 *
 * <p>Ownership parity with the legacy path is deliberate:
 * <ul>
 *   <li>The recorder/encoder objects stay owned by GpuSurveillancePipeline;
 *       this lane only runs their GL on the correct thread. Shutdown releases
 *       the lane's own EGL core, not the recorder (whose GL dies with the
 *       share group exactly as it did with the render thread's context).</li>
 *   <li>The encoder-surface-loss recovery block is ported verbatim in
 *       behavior from renderLoop's inline version, including the
 *       release-verdict abort and the trip-safe process restart
 *       escalation.</li>
 * </ul>
 */
public final class EncoderLane {

    private static final DaemonLogger logger = DaemonLogger.getInstance("EncoderLane");

    /** One published camera frame. Slots reference CopiedFrameRing textures. */
    public static final class Frame {
        final int camSlot;
        final int camTex;
        final int wsSlot;
        final int wsTex;
        final boolean wsReady;
        final long ptsNs;
        final long seq;

        public Frame(int camSlot, int camTex, int wsSlot, int wsTex,
                     boolean wsReady, long ptsNs, long seq) {
            this.camSlot = camSlot;
            this.camTex = camTex;
            this.wsSlot = wsSlot;
            this.wsTex = wsTex;
            this.wsReady = wsReady;
            this.ptsNs = ptsNs;
            this.seq = seq;
        }
    }

    private final EGLCore parentCore;
    // Suppliers, not direct refs: the windshield ring is created lazily on the
    // acquisition thread after this lane already exists.
    private final java.util.function.Supplier<CopiedFrameRing> camRingSupplier;
    private final java.util.function.Supplier<CopiedFrameRing> wsRingSupplier;
    private final java.util.function.BooleanSupplier recorderLaneEnabledSupplier;
    private final java.util.function.IntSupplier strideSupplier;
    private final java.util.function.BooleanSupplier contentionProbe;
    private final AtomicBoolean restartInProgress;

    private HandlerThread thread;
    private Handler handler;
    private EGLCore laneCore;
    private android.opengl.EGLSurface lanePbuffer;

    private volatile GpuMosaicRecorder recorder;
    private volatile HardwareEventRecorderGpu encoder;

    // Latest-wins mailbox + single-post coalescing (AiLaneGl's postQueued
    // pattern): a submit while a drain is queued/in-flight just replaces the
    // packet; the queued drain picks up the newest.
    private final AtomicReference<Frame> mailbox = new AtomicReference<>();
    private final AtomicBoolean postQueued = new AtomicBoolean(false);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    // Stride phase — computed from the CAMERA FRAME SEQ carried in each
    // packet, not from dequeued-mailbox counts: the latest-wins mailbox
    // coalesces packets under lane lag, and a local counter would then run
    // slower than camera cadence, silently raising the effective record rate
    // above the requested stride. strideBaseSeq re-anchors whenever the
    // stride VALUE changes so a stride change resumes on a drawn frame
    // (parity with setRecorderFrameStride's counter reset on the legacy
    // path). Lane thread only.
    private long strideBaseSeq = 0;
    private int lastStrideValue = 1;

    // Diagnostics: last completed draw + a throttled stall warning armed by
    // submit(). The lane deliberately has no watchdog-with-teeth in v1 — a
    // wedged encoder swap here no longer freezes the camera, and the
    // recording-side wedge detectors (RMM resync, writer-abort bridge)
    // already own recovery for a dead recording lane.
    private volatile long lastDrawCompleteMs = 0;
    private volatile long lastStallLogMs = 0;
    private static final long STALL_WARN_INTERVAL_MS = 10_000L;

    // A single outstanding GPU read may hold one slot in each ring. The
    // encoder lane never submits a second draw until these reads retire.
    // All fields below are accessed only on the lane thread.
    private long readFence;
    private CopiedFrameRing pendingCamRing;
    private CopiedFrameRing pendingWsRing;
    private long deferredGpuFrames;
    private long completedGpuFences;
    private boolean fenceUnavailable;

    public EncoderLane(EGLCore parentCore,
                       java.util.function.Supplier<CopiedFrameRing> camRingSupplier,
                       java.util.function.Supplier<CopiedFrameRing> wsRingSupplier,
                       java.util.function.BooleanSupplier recorderLaneEnabledSupplier,
                       java.util.function.IntSupplier strideSupplier,
                       java.util.function.BooleanSupplier contentionProbe,
                       AtomicBoolean restartInProgress) {
        this.parentCore = parentCore;
        this.camRingSupplier = camRingSupplier;
        this.wsRingSupplier = wsRingSupplier;
        this.recorderLaneEnabledSupplier = recorderLaneEnabledSupplier;
        this.strideSupplier = strideSupplier;
        this.contentionProbe = contentionProbe;
        this.restartInProgress = restartInProgress;
    }

    /**
     * Starts the lane thread (idempotent) and initializes {@code recorder}
     * against the lane's child EGL context. Mirrors
     * {@code initRecorderOnGlThread}: init + contention-probe wiring +
     * ready callback, all on the owning GL thread.
     */
    public void initRecorder(GpuMosaicRecorder newRecorder,
                             HardwareEventRecorderGpu newEncoder,
                             Runnable onReady) {
        if (shutdownRequested.get()) {
            logger.warn("initRecorder ignored — lane already shut down");
            return;
        }
        ensureThreadStarted();
        Handler h = handler;
        if (h == null) {
            logger.error("initRecorder: lane thread unavailable");
            return;
        }
        h.post(() -> {
            try {
                if (!ensureLaneContextLocked()) {
                    logger.error("initRecorder: lane EGL context unavailable");
                    return;
                }
                newRecorder.init(laneCore, newEncoder);
                newRecorder.setHalContentionProbe(contentionProbe);
                recorder = newRecorder;
                encoder = newEncoder;
                logger.info("Recorder initialized on EncoderLane thread");
                if (onReady != null) {
                    onReady.run();
                }
            } catch (Exception e) {
                logger.error("Failed to initialize recorder on EncoderLane", e);
            }
        });
    }

    /** Hands the newest published frame to the lane. Acquisition thread. */
    public void submit(Frame f) {
        if (f == null || shutdownRequested.get()) return;
        mailbox.set(f);
        maybeWarnStalled();
        Handler h = handler;
        if (h != null && postQueued.compareAndSet(false, true)) {
            if (!h.post(this::drainOnce)) {
                postQueued.set(false);
            }
        }
    }

    /** True once the lane thread is up and the recorder was initialized. */
    public boolean isReady() {
        return recorder != null && handler != null;
    }

    /** Last wall-clock ms a drawFrame round-trip completed (diagnostics). */
    public long lastDrawCompleteMs() {
        return lastDrawCompleteMs;
    }

    /**
     * Stops the lane: rejects new work, drains the in-flight draw (handler is
     * FIFO), releases the child EGL core on the lane thread, then joins.
     * The recorder itself is NOT released — its GL objects belong to the
     * share group and its lifecycle to the pipeline, exactly as on the
     * legacy path where the render thread never released it either.
     *
     * <p>WEDGE-HONEST: on a failed cleanup/join the thread, handler and child
     * EGL core references are KEPT — nulling them would advertise "lane gone"
     * while a wedged thread still pins the child context and may still be
     * mid-eglSwapBuffers against the codec the caller is about to tear down.
     * shutdownRequested stays latched so no replacement work can start; the
     * caller must treat false as a wedged teardown and escalate (the stop()
     * path aborts and requests the trip-safe restart, mirroring the encoder
     * drainer guard).
     *
     * @return true only when cleanup ran AND the thread verifiably exited.
     */
    public boolean shutdown(long timeoutMs) {
        shutdownRequested.set(true);
        HandlerThread t = thread;
        Handler h = handler;
        if (t == null || h == null) {
            return true;
        }
        final CountDownLatch cleaned = new CountDownLatch(1);
        boolean cleanupRan = false;
        boolean posted = h.post(() -> {
            try {
                releaseLaneGlLocked();
            } finally {
                cleaned.countDown();
            }
        });
        if (posted) {
            try {
                cleanupRan = cleaned.await(timeoutMs, TimeUnit.MILLISECONDS);
                if (!cleanupRan) {
                    logger.error("shutdown: lane GL cleanup did not finish in "
                        + timeoutMs + "ms (encoder swap wedged?)");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        t.quitSafely();
        boolean exited = true;
        if (Thread.currentThread() != t) {
            final boolean[] interrupted = { Thread.interrupted() };
            try {
                exited = com.overdrive.app.util.ThreadJoins
                    .joinFullDeadline(t, Math.max(250, timeoutMs), interrupted);
            } finally {
                if (interrupted[0]) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (!exited || !cleanupRan) {
            logger.error("shutdown: EncoderLane wedged (cleanupRan=" + cleanupRan
                + ", exited=" + exited + ") — keeping thread/context references; "
                + "child EGL context stays pinned until process restart");
            return false;
        }
        thread = null;
        handler = null;
        recorder = null;
        encoder = null;
        mailbox.set(null);
        return true;
    }

    // ==================== synchronous lane operations ====================

    /**
     * Runs {@code task} synchronously on the lane thread with the lane EGL
     * context ensured current. Used by the pipeline's live encoder
     * reconfiguration so recorder-surface release and recorder re-init happen
     * on the thread and context that own the recorder's GL — the exact
     * operations that raced the lane when they were posted to the render
     * thread. Serialized with draws by the handler's FIFO ordering.
     *
     * @return true when the task ran to completion within the deadline.
     */
    public boolean runOnLane(Runnable task, long timeoutMs) {
        if (task == null || shutdownRequested.get()) {
            return false;
        }
        ensureThreadStarted();
        Handler h = handler;
        if (h == null) {
            return false;
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean ran = new AtomicBoolean(false);
        final AtomicBoolean abandoned = new AtomicBoolean(false);
        boolean posted = h.post(() -> {
            // ABANDONED GUARD (review round 2, finding 1): if the caller
            // already timed out, this task must become a no-op — executing it
            // LATE would act on state the caller has since replaced or torn
            // down (e.g. a delayed releaseEncoderSurface destroying the NEW
            // surface after a reinit). A task that already began cannot be
            // recalled, which is exactly why callers MUST abort on false
            // rather than proceed.
            if (abandoned.get()) {
                done.countDown();
                return;
            }
            try {
                if (ensureLaneContextLocked()) {
                    task.run();
                    ran.set(true);
                }
            } catch (Throwable t) {
                logger.error("runOnLane task failed: " + t.getMessage());
            } finally {
                done.countDown();
            }
        });
        if (!posted) {
            return false;
        }
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                abandoned.set(true);
                logger.error("runOnLane timed out after " + timeoutMs
                    + "ms — task marked abandoned (no-op if not yet started); "
                    + "caller must abort");
                return false;
            }
        } catch (InterruptedException ie) {
            abandoned.set(true);
            Thread.currentThread().interrupt();
            return false;
        }
        return ran.get();
    }

    /**
     * Synchronous recorder (re)initialization against the lane context, for
     * the pipeline's encoder reconfiguration path: init + contention-probe
     * wiring + adoption of the new recorder/encoder refs, all as ONE
     * serialized lane operation so no draw can interleave with the swap.
     *
     * @return null on success; the init exception or a timeout/unavailable
     *         IllegalStateException otherwise.
     */
    public Exception initRecorderAndWait(GpuMosaicRecorder newRecorder,
                                         HardwareEventRecorderGpu newEncoder,
                                         long timeoutMs) {
        if (shutdownRequested.get()) {
            return new IllegalStateException("EncoderLane already shut down");
        }
        ensureThreadStarted();
        Handler h = handler;
        if (h == null) {
            return new IllegalStateException("EncoderLane thread unavailable");
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Exception> error = new AtomicReference<>();
        final AtomicBoolean abandoned = new AtomicBoolean(false);
        boolean posted = h.post(() -> {
            // Same abandoned guard as runOnLane: a timed-out init must not
            // execute late against a codec the aborting caller tore down.
            if (abandoned.get()) {
                done.countDown();
                return;
            }
            try {
                if (!ensureLaneContextLocked()) {
                    error.set(new IllegalStateException(
                        "EncoderLane EGL context unavailable"));
                    return;
                }
                newRecorder.init(laneCore, newEncoder);
                newRecorder.setHalContentionProbe(contentionProbe);
                recorder = newRecorder;
                encoder = newEncoder;
                logger.info("Recorder reinitialized on EncoderLane thread");
            } catch (Exception e) {
                error.set(e);
            } finally {
                done.countDown();
            }
        });
        if (!posted) {
            return new IllegalStateException("EncoderLane rejected the init post");
        }
        try {
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                abandoned.set(true);
                return new IllegalStateException(
                    "EncoderLane recorder reinit timed out after " + timeoutMs
                    + "ms (task marked abandoned)");
            }
        } catch (InterruptedException ie) {
            abandoned.set(true);
            Thread.currentThread().interrupt();
            return new IllegalStateException("Interrupted awaiting lane reinit", ie);
        }
        return error.get();
    }

    // ==================== lane thread internals ====================

    private synchronized void ensureThreadStarted() {
        if (thread != null) return;
        HandlerThread t = new HandlerThread("GL-EncoderLane",
            Process.THREAD_PRIORITY_DISPLAY);
        t.start();
        thread = t;
        handler = new Handler(t.getLooper());
        logger.info("EncoderLane thread started");
    }

    /** Lane thread only. Creates the child core + pbuffer lazily. */
    private boolean ensureLaneContextLocked() {
        if (laneCore != null) return true;
        try {
            // recordable=true: this context presents to the MediaCodec input
            // surface (same requirement as the OEM dashcam shared context).
            laneCore = EGLCore.createShared(parentCore, true);
            lanePbuffer = laneCore.createPbufferSurface(1, 1);
            laneCore.makeCurrent(lanePbuffer);
            logger.info("EncoderLane EGL context created (shared, recordable)");
            return true;
        } catch (Throwable t) {
            logger.error("EncoderLane EGL context creation failed: " + t.getMessage());
            releaseLaneGlLocked();
            return false;
        }
    }

    /** Lane thread only. */
    private void releaseLaneGlLocked() {
        // Teardown must not release ring pins while the GPU still samples
        // their textures, including when shutdown races a pending fence.
        if (readFence != 0) {
            android.opengl.GLES20.glFinish();
            releasePendingReadFence();
        }
        try {
            CopiedFrameRing cr = camRingSupplier.get();
            if (cr != null) cr.unpinRead();
            CopiedFrameRing wr = wsRingSupplier.get();
            if (wr != null) wr.unpinRead();
        } catch (Throwable ignored) { }
        try {
            if (laneCore != null && lanePbuffer != null) {
                laneCore.destroySurface(lanePbuffer);
            }
        } catch (Throwable t) {
            logger.warn("lane pbuffer destroy: " + t.getMessage());
        }
        lanePbuffer = null;
        try {
            if (laneCore != null) {
                laneCore.release();
            }
        } catch (Throwable t) {
            logger.warn("lane core release: " + t.getMessage());
        }
        laneCore = null;
    }

    /**
     * Nonblocking completion check for the previous draw. A timeout retains
     * both pins and drops this mailbox packet; the next camera submission
     * retries. No busy-wait or blocking GPU drain on the normal path.
     */
    private boolean previousReadComplete() {
        if (readFence == 0) return true;
        int status;
        try {
            status = android.opengl.GLES30.glClientWaitSync(readFence, 0, 0);
        } catch (Throwable t) {
            status = android.opengl.GLES30.GL_WAIT_FAILED;
        }
        if (status == android.opengl.GLES30.GL_ALREADY_SIGNALED
                || status == android.opengl.GLES30.GL_CONDITION_SATISFIED) {
            completedGpuFences++;
            if (completedGpuFences % 300 == 0) {
                logger.info("Encoder GPU fences completed=" + completedGpuFences
                    + ", deferred mailbox drains=" + deferredGpuFrames);
            }
            releasePendingReadFence();
            return true;
        }
        if (status == android.opengl.GLES30.GL_TIMEOUT_EXPIRED) {
            deferredGpuFrames++;
            return false;
        }
        // Driver/context errors must never cause an early texture reuse.
        android.opengl.GLES20.glFinish();
        fenceUnavailable = true;
        releasePendingReadFence();
        logger.warn("Encoder GPU fence failed; falling back to glFinish");
        return true;
    }

    private void releasePendingReadFence() {
        if (readFence != 0) {
            try { android.opengl.GLES30.glDeleteSync(readFence); }
            catch (Throwable ignored) { }
            readFence = 0;
        }
        if (pendingCamRing != null) pendingCamRing.unpinRead();
        if (pendingWsRing != null) pendingWsRing.unpinRead();
        pendingCamRing = null;
        pendingWsRing = null;
    }

    private void drainOnce() {
        postQueued.set(false);
        // Preserve pinned textures until the preceding GPU read completes.
        // This check happens before consuming the latest mailbox frame.
        if (!previousReadComplete()) {
            // Poll again even if camera submissions stop. Only one retry is
            // queued at a time; the latest-wins mailbox remains untouched.
            Handler h = handler;
            if (h != null && postQueued.compareAndSet(false, true)) {
                if (!h.postDelayed(this::drainOnce, 5)) postQueued.set(false);
            }
            return;
        }
        Frame f = mailbox.getAndSet(null);
        if (f == null || shutdownRequested.get()) {
            return;
        }
        GpuMosaicRecorder localRecorder = recorder;
        HardwareEventRecorderGpu localEncoder = encoder;
        if (localRecorder == null) {
            return;
        }
        try {
            // Parity with renderLoop PASS 1A: master gate (recorder lane
            // enabled OR a clip actually recording — the same safety override
            // the legacy gate carries), then the stride gate. The stride
            // counter advances per submitted camera frame so the effective
            // cadence matches the legacy recorderStrideCounter.
            if (!recorderLaneEnabledSupplier.getAsBoolean()
                    && !localRecorder.isRecording()) {
                return;
            }
            int stride = strideSupplier.getAsInt();
            if (stride != lastStrideValue) {
                // Stride changed — re-anchor so THIS frame draws (legacy
                // reset-on-change parity: resume on a drawn frame).
                lastStrideValue = stride;
                strideBaseSeq = f.seq;
            }
            boolean drawThisFrame = stride <= 1
                || ((f.seq - strideBaseSeq) % stride) == 0;
            if (!drawThisFrame) {
                return;
            }
            if (!ensureLaneContextLocked()) {
                return;
            }

            // Pin the slots we are about to sample so the writer can't rewrite
            // them mid-draw, no matter how long eglSwapBuffers blocks. A pin
            // REFUSAL means the writer is blitting into that slot right now
            // (this packet went stale while the lane lagged) — SKIP the frame
            // entirely; sampling unpinned would race the rewrite, and the
            // latest-wins mailbox delivers a fresh packet within one frame.
            CopiedFrameRing camRing = camRingSupplier.get();
            if (camRing == null) {
                return;
            }
            if (!camRing.pinForRead(f.camSlot)) {
                return;
            }
            boolean camPinned = true;
            // Windshield pin refusal only demotes the frame to no-windshield
            // (recorder falls back to the 360 front slice) — the camera slot
            // is pinned and safe, so dropping the whole frame would be worse.
            CopiedFrameRing wsRing = f.wsReady ? wsRingSupplier.get() : null;
            boolean wsPinned = wsRing != null && wsRing.pinForRead(f.wsSlot);
            boolean wsUse = f.wsReady && wsPinned;
            try {
                localRecorder.drawFrame(f.camTex,
                    wsUse ? f.wsTex : 0, wsUse, f.ptsNs);
                // Keep the sampled slots pinned until the GPU finishes its
                // reads. The next mailbox drain polls this fence without
                // blocking. A GLES2/driver fallback retains the old barrier.
                if (!fenceUnavailable) {
                    try {
                        readFence = android.opengl.GLES30.glFenceSync(
                            android.opengl.GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                        if (readFence != 0) {
                            android.opengl.GLES20.glFlush();
                            pendingCamRing = camRing;
                            pendingWsRing = wsPinned ? wsRing : null;
                            camPinned = false;
                            wsPinned = false;
                        } else {
                            fenceUnavailable = true;
                        }
                    } catch (Throwable t) {
                        fenceUnavailable = true;
                    }
                }
                if (fenceUnavailable) {
                    android.opengl.GLES20.glFinish();
                }
            } finally {
                if (camPinned && camRing != null) camRing.unpinRead();
                if (wsPinned && wsRing != null) wsRing.unpinRead();
            }
            lastDrawCompleteMs = System.currentTimeMillis();

            // Encoder-surface-loss recovery, ported from the inline renderLoop
            // block (same CAS guard, same release-verdict abort, same
            // escalation) — it must run HERE now because the recorder's GL
            // lives on this thread.
            if (localRecorder.needsReinit() && localEncoder != null) {
                logger.warn("Encoder surface lost - reinitializing encoder on lane...");
                if (!restartInProgress.compareAndSet(false, true)) {
                    return;
                }
                try {
                    localRecorder.release();
                    if (!localEncoder.release()) {
                        logger.error("Encoder reinit ABORTED — worker wedged during "
                            + "release (trip-safe restart pending); refusing to "
                            + "build a replacement codec over the wedged one");
                        return;
                    }
                    localEncoder.init();
                    localRecorder.init(laneCore, localEncoder);
                    localRecorder.setHalContentionProbe(contentionProbe);
                    localRecorder.clearReinitFlag();
                    logger.info("Encoder reinitialized successfully after surface loss (lane)");
                } catch (Exception reinitEx) {
                    logger.error("Encoder reinit failed: " + reinitEx.getMessage());
                    logger.error("CRITICAL: Encoder reinit failed, forcing process restart");
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                    com.overdrive.app.daemon.CameraDaemon.requestProcessRestartPreservingTrip(
                        "encoder surface reinitialization failed (lane)");
                } finally {
                    restartInProgress.set(false);
                }
            }
        } catch (Throwable t) {
            // Never let a draw error kill the lane looper — parity with
            // renderLoop's catch. Transient EGL errors during a concurrent
            // pipeline-driven encoder reinit land here and self-heal via the
            // recorder's needsReinit machinery.
            logger.error("EncoderLane draw error: " + t.getMessage());
        }
    }

    /**
     * Throttled diagnostic: submits keep arriving but no draw has completed
     * for a while EVEN THOUGH the gate is open. On the legacy path this state
     * froze the camera and tripped the GL watchdog; here it is survivable by
     * design, but it still means recording is stalled — surface it.
     */
    private void maybeWarnStalled() {
        long last = lastDrawCompleteMs;
        if (last == 0) return;
        long now = System.currentTimeMillis();
        if (now - last > STALL_WARN_INTERVAL_MS
                && now - lastStallLogMs > STALL_WARN_INTERVAL_MS
                && recorderLaneEnabledSupplier.getAsBoolean()) {
            lastStallLogMs = now;
            logger.warn("EncoderLane stalled: no completed draw for "
                + (now - last) + "ms while gate open (encoder backpressure "
                + "or wedge — camera unaffected by design)");
        }
    }
}
