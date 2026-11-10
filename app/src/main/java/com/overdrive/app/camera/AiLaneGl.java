package com.overdrive.app.camera;

import android.opengl.EGL14;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.FoveatedCropper;
import com.overdrive.app.surveillance.GpuDownscaler;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AiLaneGl — dedicated GL thread for the AI lane (mosaic readback + foveated
 * crops) running on a separate EGL context that <i>shares</i> the encoder's
 * texture/program/PBO objects via the share group.
 *
 * <p><b>Why this exists.</b> Before this class, the encoder GL thread was
 * responsible for both {@code eglSwapBuffers} (which gates MediaCodec input)
 * and {@code glReadPixels} into AI buffers. On Adreno 610 the OpenCL
 * inference (TFLite GPU delegate) and OpenGL command stream share the same
 * hardware queue, and {@code glReadPixels} against an FBO that the GPU is
 * mid-OpenCL on inserts an implicit pipeline barrier. The encoder swap
 * stalls behind that barrier — visible in the recorded MP4 as freeze+skip
 * exactly during event windows where YOLO inference is busy. Putting the
 * AI-lane reads on a different GL thread (and different EGL context, but
 * same share group) means the encoder thread's swap cadence is decoupled
 * from any AI-induced GPU stalls.
 *
 * <p><b>Shared share group.</b> The encoder thread creates the camera OES
 * texture (via {@link HardwareBufferTextureBinder#bindHardwareBufferToTextureNative})
 * and is the sole writer to it. This thread reads it. EGL spec guarantees
 * that textures created in one context are observable from another context
 * in the same share group; we serialize cross-context visibility by having
 * the encoder thread call {@code GLES.glFlush()} before notifying us, and
 * having us start each frame's work with a no-op state setup that ensures
 * we observe the latest texImage uploaded from the encoder thread.
 *
 * <p><b>Drop policy.</b> If a frame arrives while we're still processing
 * the previous one, the new arrival is collapsed (latest wins). The AI
 * lane already throttles itself internally to ~10 Hz via
 * {@code MOTION_PROCESS_INTERVAL_MS}, so missed AI frames are invisible.
 * What matters is that the encoder thread never blocks on us.
 *
 * <p><b>Lifecycle.</b> {@link #start} brings up the thread and shared
 * context. {@link #setConsumers} wires the downscaler and (after lazy init)
 * the foveated cropper. {@link #notifyFrame} is the per-frame entry point
 * the encoder thread calls. {@link #shutdown} drains in-flight work and
 * releases GL resources.
 */
public final class AiLaneGl {

    private static final DaemonLogger logger = DaemonLogger.getInstance("AiLaneGl");

    /**
     * Camera state visible to this lane. Carries the shared OES texture id
     * the encoder thread bound the latest HAL frame to, plus a per-frame
     * counter the encoder thread bumps on every successful bind.
     *
     * <p>The counter lets {@code processOnce()} cheaply detect "did we
     * already process this frame?" without a synchronized handshake. If the
     * counter hasn't advanced since our last run, there's nothing new to
     * read back — skip.
     */
    public interface CameraState {
        /** OES texture id holding the most recently bound HAL frame. */
        int getCameraTextureId();
        /** Monotonically increasing on each successful HAL frame bind. */
        long getFrameSeq();
        /**
         * False while the camera surface is being rebound/torn down on the
         * encoder GL thread (restart / yield / closed). The camera OES texture's
         * backing EGLImage is freed during that window, so the AI lane MUST NOT
         * sample it — doing so faults inside the Adreno driver (use-after-free
         * SIGSEGV in libGLESv2_adreno during readback). Default true keeps any
         * other CameraState impl byte-identical.
         */
        default boolean isCameraTextureValid() { return true; }

        /**
         * Monitor that serializes mutation of the shared camera OES texture
         * (the encoder thread's updateTexImage / SurfaceTexture release) against
         * the AI lane's readback that SAMPLES it. The two run on different
         * threads + EGL contexts but share one EXTERNAL_OES texname; a
         * check-then-use validity flag cannot close the window where
         * updateTexImage swaps the backing EGLImage mid-readback (or the HAL
         * abandons the BufferQueue), which faults in the Adreno driver. BOTH
         * sides synchronize on this object so the texture is never rebound/freed
         * while a sample is in flight. Default = a per-instance lock (no
         * cross-thread contract) so other CameraState impls stay correct.
         */
        default Object cameraTextureLock() { return this; }
    }

    private final EGLCore parentCore;
    private CameraState cameraState;

    private EGLCore aiCore;                 // shared with parentCore
    private android.opengl.EGLSurface aiPbuffer;
    private android.os.HandlerThread thread;
    private android.os.Handler handler;

    // Pre-existing AI infrastructure that we now own GL-thread-wise.
    private GpuDownscaler downscaler;       // Owned externally, GL ops on us.
    private FoveatedCropper foveatedCropper;
    /** Rate-limit for the lazy-wire diagnostic (logs why setFoveatedCropper didn't fire). */
    private long lastFoveatedWireDiagMs = 0L;
    private SurveillanceEngineGpu sentry;
    private AiLaneWorker aiLaneWorker;

    // Last frame seq we serviced. The notify path bumps pendingSeq; the
    // handler runnable compares against this to decide whether to do work.
    private final AtomicInteger pendingSeq = new AtomicInteger(0);
    private long lastServicedSeq = -1;
    private final AtomicBoolean running = new AtomicBoolean(false);
    // Re-entrancy guard for the handler post — if a `notifyFrame` arrives
    // while a previous post is queued or in flight, we don't queue another
    // post. The pending one (or the in-flight one when it completes) will
    // observe the new pendingSeq and pick up the latest.
    private final AtomicBoolean postQueued = new AtomicBoolean(false);

    // Frame-modulo gate for the readback. Mirrors the prior
    // AI_READBACK_FRAME_MODULO=3 behaviour: AI cadence scales with HAL rate
    // independent of wall-clock throttling. Visible to the encoder thread
    // via setReadbackModulo() if surveillance config changes it.
    private static final int DEFAULT_READBACK_MODULO = 3;
    private volatile int readbackModulo = DEFAULT_READBACK_MODULO;

    // Wall-clock readback pacing (parked-idle throttle). When > 0 it SUPERSEDES
    // the frame-count modulo for PASS A (motion readback): PASS A fires only if
    // at least this many ms elapsed since the last readback, so the motion
    // cadence is pinned to a fixed rate INDEPENDENT of the HAL delivery rate.
    // This is what preserves detection parity when the HAL fps is dropped for
    // idle power: at survFps=15 the target is 200ms (= today's 15/3 = 5 Hz), and
    // holding 200ms means motion still runs at 5 Hz whether the HAL delivers 5
    // or 15 fps — no ramp-transition undersample. 0 = disabled ⇒ pure modulo
    // (byte-identical to today). Single-writer (control thread) / single-reader
    // (GL handler thread); volatile is sufficient.
    private volatile long readbackMinIntervalMs = 0L;
    // GL-handler-thread confined: last time PASS A actually ran.
    private long lastReadbackWallMs = 0L;

    /**
     * @param parentCore the encoder/main {@link EGLCore}. We create our
     *                   context from {@link EGLCore#createShared(EGLCore)}
     *                   so we observe the same camera texture and any
     *                   shaders the parent compiled.
     * @param cameraState read-only handle the encoder thread updates each
     *                    frame; we sample it on our handler.
     */
    public AiLaneGl(EGLCore parentCore, CameraState cameraState) {
        if (parentCore == null) throw new IllegalArgumentException("parentCore is null");
        if (cameraState == null) throw new IllegalArgumentException("cameraState is null");
        this.parentCore = parentCore;
        this.cameraState = cameraState;
    }

    /** Wire the AI consumers. Must be called before {@link #start}. */
    public void setConsumers(GpuDownscaler downscaler,
                             FoveatedCropper foveatedCropper,
                             SurveillanceEngineGpu sentry,
                             AiLaneWorker aiLaneWorker) {
        this.downscaler = downscaler;
        this.foveatedCropper = foveatedCropper;
        this.sentry = sentry;
        this.aiLaneWorker = aiLaneWorker;
    }

    /** Adjust the GL-thread frame cadence at which we trigger AI readback. */
    public void setReadbackModulo(int modulo) {
        this.readbackModulo = Math.max(1, modulo);
    }

    /**
     * Pin the motion-readback (PASS A) cadence to a fixed wall-clock interval,
     * independent of the HAL delivery rate. Pass the target inter-sample period
     * in ms (e.g. 200 for 5 Hz); pass 0 to disable and fall back to the
     * frame-count modulo. Used by the parked-idle throttle so dropping the HAL
     * fps does not change how often motion detection actually runs.
     */
    public void setReadbackMinIntervalMs(long ms) {
        this.readbackMinIntervalMs = Math.max(0L, ms);
    }

    /**
     * Bring up the dedicated thread + shared EGL context. Must be called
     * with the parent EGL context current on the calling thread (so the
     * share-group create observes the parent context as alive).
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            logger.warn("start() called twice — ignoring");
            return;
        }
        thread = new android.os.HandlerThread("AiLaneGl",
                android.os.Process.THREAD_PRIORITY_DEFAULT);
        thread.start();
        handler = new android.os.Handler(thread.getLooper());

        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        final Throwable[] failure = new Throwable[1];
        handler.post(() -> {
            try {
                aiCore = EGLCore.createShared(parentCore);
                // Pbuffer is just a 1x1 dummy — we never present to it; all
                // our work is FBO-based.
                aiPbuffer = aiCore.createPbufferSurface(1, 1);
                aiCore.makeCurrent(aiPbuffer);
                logger.info("AI-lane GL thread up (shared context, gles3, separate hardware queue submission)");
            } catch (Throwable t) {
                failure[0] = t;
                logger.error("AI-lane GL init failed: " + t.getMessage());
            } finally {
                ready.countDown();
            }
        });

        try {
            if (!ready.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                running.set(false);
                throw new IllegalStateException("AiLaneGl init timed out");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            running.set(false);
            throw new IllegalStateException("AiLaneGl init interrupted", ie);
        }
        if (failure[0] != null) {
            running.set(false);
            throw new RuntimeException("AiLaneGl init failed", failure[0]);
        }
    }

    /**
     * Encoder-thread entry point. Cheap to call (atomic int + maybe one
     * handler post). Returns immediately. The actual AI work happens on
     * our thread.
     *
     * <p>Caller MUST have rendered to its encoder surface and either
     * swapped or called {@code glFlush()} BEFORE this point. The flush
     * publishes the OES texture's contents to the share group so we
     * observe the right bytes when we sample on our thread.
     */
    public void notifyFrame(long frameSeq) {
        if (!running.get()) return;
        // We use a 31-bit truncation since AtomicInteger; full 64-bit
        // wrap-around would need 2.4 billion frames at 30 fps ≈ 2.6 years
        // of continuous recording, far beyond any practical session, and
        // the comparison is "different from last serviced" not
        // "ordered greater than", so wrap is benign anyway.
        pendingSeq.lazySet((int) frameSeq);
        if (postQueued.compareAndSet(false, true)) {
            handler.post(this::processOnce);
        }
    }

    /** Handler-thread body. Picks up the latest seq and runs one tick. */
    private void processOnce() {
        // Clear postQueued FIRST so a notify() that races with us can
        // still queue a follow-up post if its seq lands after we sample.
        postQueued.set(false);

        int seq = pendingSeq.get();
        if (seq == lastServicedSeq) return;       // nothing new
        lastServicedSeq = seq;

        if (cameraState == null) return;
        int textureId = cameraState.getCameraTextureId();
        if (textureId <= 0) return;
        // CRITICAL (use-after-free guard): never sample the camera OES texture
        // while the encoder thread is rebinding/tearing down the camera surface —
        // its backing EGLImage is freed in that window and the Adreno driver
        // null-derefs inside the readback draw (SIGSEGV in libGLESv2_adreno). This
        // mirrors the encoder thread's own renderLoop guard. Gates BOTH PASS A
        // (readback) and PASS B (foveated) since both bind/sample the OES texture.
        if (!cameraState.isCameraTextureValid()) return;

        // ALL GL sampling of the shared camera OES texture happens UNDER the
        // camera-texture lock so the encoder thread cannot run updateTexImage()
        // (swap the backing EGLImage) or release the SurfaceTexture mid-sample —
        // the use-after-free that SIGSEGVs the Adreno driver. We also re-check
        // isCameraTextureValid() INSIDE the lock: a teardown that began before we
        // acquired it will have cleared the flag, and once we hold the lock the
        // encoder cannot start a new teardown until we release.
        final Object camLock = cameraState.cameraTextureLock();
        synchronized (camLock) {
            if (!cameraState.isCameraTextureValid()) return;

            // Readback pacing gate. Two mutually-exclusive modes:
            //  (1) wall-clock (readbackMinIntervalMs > 0, parked-idle throttle):
            //      PASS A fires only every N ms, pinning motion cadence to a
            //      fixed rate regardless of the HAL delivery rate. This is what
            //      keeps detection identical when the HAL fps is dropped/ramped.
            //  (2) frame-count modulo (legacy default): AI cadence scales with
            //      HAL rate. Byte-identical to pre-throttle behaviour.
            final long minIntervalMs = readbackMinIntervalMs;
            final boolean skipReadback;
            if (minIntervalMs > 0L) {
                long nowMs = android.os.SystemClock.uptimeMillis();
                // First tick after enabling (lastReadbackWallMs==0) always runs.
                skipReadback = lastReadbackWallMs != 0L
                        && (nowMs - lastReadbackWallMs) < minIntervalMs;
                if (!skipReadback) {
                    lastReadbackWallMs = nowMs;
                }
            } else {
                skipReadback = readbackModulo > 1 && (seq % readbackModulo) != 0;
            }
            if (skipReadback) {
                // Even though we skip readback, still let the foveated mailbox
                // drain — the foveated cropper has its own 150ms throttle and
                // is what carries event-correlated work.
                // The fence is needed only if a foveated crop actually
                // submitted GL work. An idle tick must not synchronize the GPU.
                if (serviceFoveated(textureId)) {
                    gpuSampleBarrier();
                }
                return;
            }

            // Track submitted GL work, not whether a CPU-side result was
            // returned. The async PBO readback can return null after issuing
            // an OES-sampling draw, which still requires the lifetime barrier.
            boolean sampledCameraTexture = false;

            // PASS A — mosaic 640×480 readback for V2 motion + actor pipeline.
            // PBO + fence-sync inside readPixelsDirect makes this non-blocking
            // even when the GPU's command queue is busy. With YOLO migrated
            // off the GPU (CPU-only XNNPACK), the previous "Adreno mid-OpenCL"
            // gate is no longer meaningful — the only contention left here is
            // the AI worker still processing the prior frame, which the
            // isBusy() check covers.
            try {
                if (sentry != null && sentry.isActive()
                        && downscaler != null && aiLaneWorker != null) {
                    if (!aiLaneWorker.isBusy()) {
                        // Set before invoking: an exception or null result does
                        // not establish that no GPU commands were queued.
                        sampledCameraTexture = true;
                        byte[] smallFrame = downscaler.readPixelsDirect(textureId);
                        if (smallFrame != null) {
                            // Lazy-wire foveated cropper into the sentry on
                            // first valid frame so it picks up the same OES tex.
                            if (foveatedCropper != null && foveatedCropper.isInitialized()
                                    && sentry.getFoveatedCropper() == null) {
                                sentry.setFoveatedCropper(foveatedCropper, textureId);
                                logger.info("FOVEATED-DIAG: lazy-wired cropper into engine (textureId="
                                        + textureId + ")");
                            } else if (sentry.getFoveatedCropper() == null
                                    && System.currentTimeMillis() - lastFoveatedWireDiagMs > 5000) {
                                // DIAGNOSTIC: the wire is reachable but a sub-term genuinely
                                // blocked it (cropper still null/uninitialized). The
                                // already-wired case (engineAlreadyWired=true) is SUCCESS, not
                                // a blocker, so it is excluded here — otherwise this logged
                                // every 5s for the daemon's life describing the healthy state.
                                lastFoveatedWireDiagMs = System.currentTimeMillis();
                                logger.info("FOVEATED-DIAG: lazy-wire NOT firing: cropperNull="
                                        + (foveatedCropper == null) + " cropperInit="
                                        + (foveatedCropper != null && foveatedCropper.isInitialized())
                                        + " engineAlreadyWired=" + (sentry.getFoveatedCropper() != null));
                            }
                            aiLaneWorker.submitFrame(smallFrame);
                        }
                    }
                }
            } catch (Throwable t) {
                logger.warn("AI lane readback error: " + t.getMessage());
            }

            // PASS B — service the foveated mailbox. Inside the lock: it samples
            // the same OES texture (foveated crop binds GL_TEXTURE_EXTERNAL_OES),
            // so it must be serialized against updateTexImage/release too.
            sampledCameraTexture |= serviceFoveated(textureId);

            // CRASH FIX (GPU-timeline barrier): the OES-sampling draws above only
            // QUEUE GPU work; the Java lock serializes CPU command ISSUANCE, not
            // GPU EXECUTION. Without a barrier, this lock releases while the GPU
            // sample of the camera EXTERNAL_OES texture is still pending, and the
            // encoder thread then rebinds/frees that texture's backing buffer
            // (consumeLatestImageAndBind) → use-after-free in libGLESv2_adreno.
            // The barrier forces THIS lane's sample to COMPLETE on the GPU before
            // we release camLock, so the encoder can never recycle a buffer that's
            // still being read. See gpuSampleBarrier() for why a fence (not a full
            // glFinish) is used. No fence is needed when neither pass submitted
            // any OES-sampling draw (e.g. no pending crop and worker busy).
            if (sampledCameraTexture) {
                gpuSampleBarrier();
            }
        }
    }

    /**
     * Wait for THIS AI lane's just-queued OES-sampling draws to finish on the GPU
     * before the caller releases cameraTextureLock — the barrier that prevents the
     * encoder from recycling the camera buffer mid-sample (use-after-free).
     *
     * <p>Uses a GLES3 fence ({@code glFenceSync} + {@code glClientWaitSync}) rather
     * than {@code glFinish()}. Both EGL contexts share one group on a single Adreno
     * 610, so {@code glFinish} drains the ENTIRE share-group queue — including the
     * encoder's heavy 5120×960 mosaic draw that the render thread just queued
     * OUTSIDE this lock. Because the render thread re-acquires cameraTextureLock
     * every frame around updateTexImage, a glFinish here parks the render/encoder
     * thread for a full GPU drain on every AI tick → recording-fps drop + PTS
     * jitter (the exact failure the engine's history documents and deliberately
     * removed). A fence waits only until THIS lane's draws signal, leaving the
     * encoder's queued mosaic untouched.
     *
     * <p>HARD-SAFETY FALLBACK: if the fence can't be created (driver fell back to
     * GLES2 — EGLCore does this on some Adreno builds) OR the wait times out, we
     * fall back to {@code glFinish()} so the use-after-free guarantee is NEVER
     * weakened. Correctness first; the fence is purely a throughput optimization.
     */
    private void gpuSampleBarrier() {
        try {
            long fence = android.opengl.GLES30.glFenceSync(
                    android.opengl.GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            if (fence != 0) {
                int r = android.opengl.GLES30.glClientWaitSync(fence,
                        android.opengl.GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 50_000_000L /* 50ms */);
                android.opengl.GLES30.glDeleteSync(fence);
                // TIMEOUT / lapsed → the sample may NOT have completed; fall back
                // to a hard finish so we never release the lock early. (ALREADY/
                // CONDITION/SIGNALED mean the wait succeeded.)
                if (r == android.opengl.GLES30.GL_TIMEOUT_EXPIRED
                        || r == android.opengl.GLES30.GL_WAIT_FAILED) {
                    android.opengl.GLES20.glFinish();
                }
            } else {
                // No fence (GLES2 fallback context) → hard finish.
                android.opengl.GLES20.glFinish();
            }
        } catch (Throwable t) {
            // Any failure in the fence path → guarantee correctness with glFinish.
            try { android.opengl.GLES20.glFinish(); } catch (Throwable ignored) {}
        }
    }

    private boolean serviceFoveated(int textureId) {
        if (sentry == null || !sentry.isActive()) return false;
        // True means a crop was ATTEMPTED, not necessarily completed. On
        // exceptions, the draw may already be queued and still needs a fence.
        try {
            return sentry.serviceFoveatedRequestsOnGlThread();
        } catch (Throwable t) {
            logger.warn("Foveated service error: " + t.getMessage());
            return true; // Conservatively preserve the GPU lifetime barrier.
        }
    }

    /**
     * Mark the cropper for late attach. Cropper's GL resources allocate
     * lazily when first used; we just store the reference.
     */
    public void attachFoveatedCropper(FoveatedCropper cropper) {
        this.foveatedCropper = cropper;
    }

    /**
     * Run a Runnable on this lane's GL thread, blocking until done. Used by
     * the encoder thread during init / shutdown to allocate or free GL
     * resources on this context.
     */
    public boolean runOnGlThreadBlocking(Runnable r, long timeoutMs) {
        if (!running.get() || handler == null) return false;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        handler.post(() -> {
            try { r.run(); } catch (Throwable t) {
                logger.warn("runOnGlThreadBlocking task error: " + t.getMessage());
            } finally { latch.countDown(); }
        });
        try { return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Shutdown — drains in-flight work then releases GL resources. */
    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;
        if (handler != null) {
            final java.util.concurrent.CountDownLatch done =
                    new java.util.concurrent.CountDownLatch(1);
            handler.post(() -> {
                try {
                    // Release foveated cropper FIRST while the AI-lane EGL
                    // context is still current — its FBO / PBO ring lives
                    // in this context.
                    if (foveatedCropper != null) {
                        try { foveatedCropper.release(); } catch (Throwable ignored) {}
                    }
                    // Release the downscaler's direct-path GL state from
                    // THIS thread/context. readPixelsDirect was called from
                    // here (PanoramicCameraGpu wires the AI lane to call
                    // it), so its FBO/PBO/sync/program objects all live in
                    // this share-group context. Calling release() from any
                    // other thread (as the previous code did via the
                    // legacy renderHandler) silently no-ops because the GL
                    // object names aren't visible there → resource leak on
                    // every pipeline restart.
                    if (downscaler != null) {
                        try { downscaler.releaseDirectResources(); } catch (Throwable ignored) {}
                    }
                    if (aiCore != null) {
                        try { aiCore.makeNothingCurrent(); } catch (Throwable ignored) {}
                        if (aiPbuffer != null) {
                            try { aiCore.destroySurface(aiPbuffer); } catch (Throwable ignored) {}
                            aiPbuffer = null;
                        }
                        try { aiCore.release(); } catch (Throwable ignored) {}
                        aiCore = null;
                    }
                } finally {
                    done.countDown();
                }
            });
            try { done.await(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            thread.quitSafely();
            try { thread.join(1500); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            handler = null;
            thread = null;
        }
    }

    /** True if start() succeeded and shutdown() hasn't run. */
    public boolean isRunning() { return running.get(); }
}
