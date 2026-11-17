package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * OEM Dashcam settings API handler.
 *
 * Currently exposes the "Disable Native DVR" toggle for BYD's factory dashcam
 * package ({@code com.byd.cdr}). When the native DVR is disabled, our OEM
 * Dashcam pipeline owns the AVMCamera without contention — the factory app
 * stops opening AVMCamera on every ACC ON event and stops writing to
 * /sdcard/DCIM/BYDCam.
 *
 * <p><b>Permission model.</b> The HTTP server runs in the {@code CameraDaemon}
 * process which is launched via {@code adb shell ... app_process} and
 * therefore runs as UID 2000 (shell). UID shell is allowed to call
 * {@code pm disable-user --user 0 <pkg>} and {@code pm enable <pkg>}
 * directly, so this handler shells out via {@link Runtime#exec} without
 * needing an IPC roundtrip to AccSentryDaemon.
 * ({@code com.overdrive.app.byd.TrafficMonitorPolicy} applies the same contract
 * to com.byd.trafficmonitor — both run in a UID shell daemon.)
 *
 * <p>Endpoints:
 * <pre>
 *   GET  /api/oem-dashcam/native-dvr/status   — { state, package }
 *   POST /api/oem-dashcam/native-dvr/disable  — runs pm disable-user, persists UCM
 *   POST /api/oem-dashcam/native-dvr/enable   — runs pm enable, persists UCM
 * </pre>
 *
 * <p>{@code state} is one of:
 * <ul>
 *   <li>{@code "enabled"}    — package present and not disabled (factory DVR active)</li>
 *   <li>{@code "disabled"}   — package present and currently disabled (we own the camera)</li>
 *   <li>{@code "not_installed"} — no com.byd.cdr on this firmware (e.g. trims without OEM dashcam);
 *       UI hides the entire card on this state.</li>
 * </ul>
 */
public class OemDashcamApiHandler {

    private static final String TAG = "OemDashcamApi";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Factory dashcam package — see AvcHalWarmup.java header for context. */
    public static final String NATIVE_DVR_PACKAGE = "com.byd.cdr";

    // Serialize lifecycle transitions so two rapid POST /config calls don't
    // race start() and stop() against each other and leave the static
    // pipeline reference half-initialised.
    private static final Object LIFECYCLE_LOCK = new Object();

    // Lifecycle thread pool — single thread, dedup via in-flight flag with
    // a sticky re-arm so a click landing during execution still triggers
    // exactly one follow-up apply (in case state diverged between submit
    // and run). Replaces the per-call `new Thread(...).start()` pattern
    // that was queueing dozens of LIFECYCLE_LOCK contenders during
    // DVR-click hammering.
    // Package-private so peer handlers in com.overdrive.app.server (e.g.
    // QualitySettingsApiHandler's quality-mirror restart) can serialize their
    // own stop+start cycles against picker applies on the same executor —
    // otherwise rapid quality changes race the lifecycle worker and orphan
    // a half-built pipeline.
    static final java.util.concurrent.ExecutorService LIFECYCLE_EXEC =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OemDashcamLifecycle");
            t.setDaemon(true);
            return t;
        });
    private static final java.util.concurrent.atomic.AtomicBoolean lifecycleInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicBoolean lifecyclePending =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicLong lifecycleRevision =
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * True while the OEM lifecycle executor has a current or queued ownership
     * transition. Pano ownerless cleanup treats this as a temporary owner: pano
     * stop tears OEM down before EGL release, so closing pano in the middle of an
     * OEM start/restart would abort a valid recording or DVR-view request.
     */
    public static boolean isCameraLifecycleTransitionInFlight() {
        return lifecycleInFlight.get() || lifecyclePending.get();
    }

    /**
     * Schedule one trigger-lifecycle recalc on the dedicated executor.
     * Idempotent under burst — N concurrent calls land on at most 2 runs
     * (one in-flight, one re-armed). Use this from every place that
     * previously did {@code new Thread(applyTriggerLifecycleFromUcm).start()}.
     */
    public static void scheduleLifecycleRecalc() {
        // Invalidate a pass already blocked in HAL/encoder warmup. Its queued
        // follow-up will resolve fresh state instead of starting stale recording.
        lifecycleRevision.incrementAndGet();
        // Publish pending before checking ownership so a worker finishing at
        // this exact moment either sees the request or lets this caller submit it.
        lifecyclePending.set(true);
        if (lifecycleInFlight.compareAndSet(false, true)) {
            try {
                LIFECYCLE_EXEC.execute(LIFECYCLE_RUN);
            } catch (Throwable t) {
                // RejectedExecutionException post-shutdown / OOM / etc.
                // Without resetting the flag, no future scheduleLifecycleRecalc
                // would ever submit anything — the executor would be silently
                // dead. Log + reset.
                lifecycleInFlight.set(false);
                logger.warn("scheduleLifecycleRecalc: executor rejected: " + t.getMessage());
            }
        }
    }

    /** Single shared Runnable used by both the primary submission and the
     *  race-recovery path, so the do-while body lives in exactly one place. */
    private static final Runnable LIFECYCLE_RUN = new Runnable() {
        @Override public void run() {
            try {
                do {
                    lifecyclePending.set(false);
                    try {
                        applyTriggerLifecycleFromUcm();
                    } catch (Throwable t) {
                        com.overdrive.app.daemon.CameraDaemon.log(
                            "scheduleLifecycleRecalc body threw: " + t.getMessage());
                    }
                } while (lifecyclePending.get());
            } finally {
                lifecycleInFlight.set(false);
                // Race window — a caller may have flipped pending=true
                // between our last check and the flag clear. Re-enter
                // recursively (the top-level CAS handles maybe-in-flight).
                if (lifecyclePending.get()) scheduleLifecycleRecalc();
            }
        }
    };

    // ==================== PERIODIC SELF-HEAL TICKER ====================
    //
    // The OEM lifecycle is otherwise purely edge-driven (ACC IPC, surveillance
    // IPC, config POST, stream-view, safe-zone, gear, pano-ready). Pano's
    // RecordingModeManager has a 30s resync ticker that re-drives activation
    // when modeActive is false; the OEM pipeline had no equivalent, so a
    // start that raced or transiently failed (most visibly in the DashCam+Pano
    // dual-AVMCamera layout, where OEM and pano contend for the HAL handle at
    // ACC ON) stayed dead until some later incidental edge — the "recording
    // started ~2km into the drive" symptom.
    //
    // This ticker re-runs applyTriggerLifecycleFromUcm every
    // SELF_HEAL_INTERVAL_MS. The resolver is idempotent: when the resolved
    // desired-state already matches the live pipeline it returns without
    // touching anything (no encoder rebuild, no camera reopen). Only a drift
    // — desired-but-not-running, or running-but-should-stop — triggers work.
    // Bounds worst-case recovery latency to ~30s instead of an unbounded
    // wait for the next edge.
    private static final long SELF_HEAL_INTERVAL_MS = 30_000L;
    private static volatile Thread selfHealThread;
    private static volatile boolean selfHealRunning;

    // How long startPipeline() waits for pano's EGLCore before giving up and starting
    // OEM on an independent context. Must comfortably exceed a full pano cold start,
    // because we now REQUEST that start ourselves: GpuSurveillancePipeline.start()
    // includes a 1500ms encoder-warmup sleep plus AVMCamera open + EGL init (~2-4s
    // observed). The old 3s budget expired mid-start on a cold boot, which is how OEM
    // ended up unshared and DVR showed the AVM mosaic. 8s covers the slow case with
    // margin while still bounding the wait so a genuinely dead pano can never wedge
    // OEM recording (it proceeds on an independent context, which records fine).
    // This runs on the dedicated OEM lifecycle executor, never on an HTTP thread.
    private static final long PANO_EGL_WAIT_MS = 8_000L;

    // One-shot latch for the independent-EGL-context heal restart (view-6 streaming).
    // The restart is only useful when EGLCore.createShared then SUCCEEDS. If a driver
    // refuses share groups outright, the rebuilt pipeline comes back unshared too and
    // the condition (unshared + pano available + streaming wanted) stays true forever —
    // which would make the 30s self-heal ticker rebuild the camera + encoder every 30s
    // indefinitely. Latch so we attempt the heal AT MOST ONCE per daemon run; if it
    // didn't take, we leave the pipeline alone (recording keeps working) and view 6
    // reports not-ready instead of thrashing. Reset on a successful share so a later
    // legitimate restart can heal again.
    private static volatile boolean eglHealAttempted = false;

    // Legacy: a warm pipeline whose camera never delivered a frame is reopened on a
    // backoff (a handle opened while the cameras were unpowered never recovers).
    private static final long OEM_NO_FRAME_RESTART_AFTER_MS = 60_000L;
    private static final long[] OEM_NO_FRAME_RESTART_BACKOFF_MS = {60_000L, 120_000L, 300_000L};
    // Reset on every ACC change and pipeline teardown: a DVR that never streams costs at
    // most this many reopens in between.
    private static final int OEM_NO_FRAME_MAX_RESTARTS = 3;
    private static int oemNoFrameRestarts = 0;            // guarded by LIFECYCLE_LOCK
    private static long oemNoFrameNextRestartAtMs = 0L;   // guarded by LIFECYCLE_LOCK
    private static int oemNoFrameAccPhase = -1;           // guarded by LIFECYCLE_LOCK
    private static long oemNoFrameAccPhaseStartMs = 0L;   // guarded by LIFECYCLE_LOCK

    /** Caller holds LIFECYCLE_LOCK. */
    private static boolean shouldRestartNeverStreamedOem(
            com.overdrive.app.camera.OemDashcamPipeline pipeline) {
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                || com.overdrive.app.camera.camera2.Camera2Platform.isSelected()) {
            return false;
        }
        boolean accOn = com.overdrive.app.monitor.AccMonitor.isAccOn();
        long now = android.os.SystemClock.elapsedRealtime();
        int accPhase = accOn ? 1 : 0;
        if (accPhase != oemNoFrameAccPhase) {
            oemNoFrameAccPhaseStartMs = now;
        }
        if (pipeline.hasDeliveredFrame() || accPhase != oemNoFrameAccPhase) {
            oemNoFrameAccPhase = accPhase;
            oemNoFrameRestarts = 0;
            oemNoFrameNextRestartAtMs = 0L;
            if (pipeline.hasDeliveredFrame()) {
                return false;
            }
        }
        long noFrameMs = pipeline.getNoFrameSinceAttachMs();
        // DiLink 4: parked producers pause legitimately, and right after ACC ON byd_apa is still
        // re-powering cameras — only a DVR silent for a full window into the drive is a fault.
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isDiLink4Selected()) {
            if (!accOn) {
                return false;
            }
            noFrameMs = Math.min(noFrameMs, now - oemNoFrameAccPhaseStartMs);
        }
        if (noFrameMs < OEM_NO_FRAME_RESTART_AFTER_MS || pipeline.isRecording()
                || oemNoFrameRestarts >= OEM_NO_FRAME_MAX_RESTARTS
                || now < oemNoFrameNextRestartAtMs) {
            return false;
        }
        long backoffMs = OEM_NO_FRAME_RESTART_BACKOFF_MS[
            Math.min(oemNoFrameRestarts, OEM_NO_FRAME_RESTART_BACKOFF_MS.length - 1)];
        oemNoFrameRestarts++;
        oemNoFrameNextRestartAtMs = now + backoffMs;
        com.overdrive.app.daemon.CameraDaemon.log(
            "OemDashcam: camera id " + pipeline.getCameraId() + " delivered no frame "
            + (noFrameMs / 1000) + "s after attach — restarting the pipeline (attempt "
            + oemNoFrameRestarts + "/" + OEM_NO_FRAME_MAX_RESTARTS + ")");
        return true;
    }

    /**
     * Start the periodic self-heal ticker. Idempotent — a second call while
     * the thread is alive is a no-op. Call once at daemon boot.
     */
    public static synchronized void startSelfHealTicker() {
        if (selfHealThread != null && selfHealThread.isAlive()) return;
        selfHealRunning = true;
        selfHealThread = new Thread(() -> {
            com.overdrive.app.daemon.CameraDaemon.log(
                "OemDashcam: self-heal ticker started (" + (SELF_HEAL_INTERVAL_MS / 1000) + "s interval)");
            while (selfHealRunning) {
                try {
                    Thread.sleep(SELF_HEAL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    if (!selfHealRunning) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                if (!selfHealRunning) return;
                // A live DVR selection is also an active consumer. Without
                // this branch, a watchdog-stop in streaming-only mode stays
                // down forever because no recording trigger is armed.
                try {
                    if (com.overdrive.app.config.UnifiedConfigManager
                            .isAnyOemDashcamTriggerEnabled()
                            || isAnyStreamingViewerActive()) {
                        scheduleLifecycleRecalc();
                    }
                } catch (Throwable t) {
                    com.overdrive.app.daemon.CameraDaemon.log(
                        "OemDashcam: self-heal tick failed: " + t.getMessage());
                }
            }
        }, "OemDashcamSelfHeal");
        selfHealThread.setDaemon(true);
        selfHealThread.start();
    }

    /**
     * Drive the OEM pipeline lifecycle from the trigger union. The pipeline
     * runs whenever {@code recordingDesired} OR {@code streamingDesired}
     * is true — the camera+EGL+encoder open is shared. Recording is gated
     * separately on {@code recordingDesired} so a streaming-only viewer
     * never spawns a dvr_*.mp4 file.
     *
     * <p>Mirrors how pano keeps {@link com.overdrive.app.surveillance.GpuSurveillancePipeline}
     * running for streaming clients without writing event_*.mp4 unless the
     * surveillance / continuous trigger is hot.
     *
     * @param recordingDesired true if any oemDashcam.triggers.* is true.
     *                         The pipeline auto-rotates dvr_*.mp4 segments
     *                         while this is set.
     * @param streamingDesired true if a /api/stream/view/6 client is
     *                         actively asking for the OEM feed. Keeps the
     *                         pipeline warm without flipping recording on.
     */
    public static void applyTriggerLifecycle(boolean recordingDesired, boolean streamingDesired) {
        applyTriggerLifecycle(recordingDesired, streamingDesired, lifecycleRevision.get());
    }

    private static void applyTriggerLifecycle(
            boolean recordingDesired, boolean streamingDesired, long expectedRevision) {
        boolean shouldRun = recordingDesired || streamingDesired;
        com.overdrive.app.camera.OemDashcamPipeline existing;
        boolean needFormatWait;
        // Phase 1 (under lock): bring up pipeline + tear down. Both
        // mutate the daemon-static OemDashcamPipeline ref so they MUST
        // serialize. waitForEncoderFormat is read-only on the encoder
        // field — pulled outside the lock so other lifecycle callers
        // (ACC bounce, quality-mirror restart, view-6 click) don't
        // block on a 3-second poll while the encoder warms up.
        synchronized (LIFECYCLE_LOCK) {
            if (!isLifecycleRevisionCurrent(expectedRevision)) return;
            existing = com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            try {
                if (shouldRun) {
                    if (existing == null || !existing.isRunning()) {
                        existing = startPipeline(expectedRevision);
                        if (existing == null) return;
                    } else if (shouldRestartNeverStreamedOem(existing)) {
                        try { existing.stop(); } catch (Throwable ignored) {}
                        com.overdrive.app.daemon.CameraDaemon.setOemDashcamPipeline(null);
                        existing = startPipeline(expectedRevision);
                        if (existing == null) return;
                    } else if (streamingDesired
                            && !existing.isEglSharedWithPano()
                            && !existing.isRecording()
                            && !eglHealAttempted
                            && isPanoEglAvailable()) {
                        // SELF-HEAL (view-6 mosaic/black bug): this pipeline is warm but
                        // its GL context is INDEPENDENT of pano's — it lost the 3s race in
                        // startPipeline() and fell through to `new EGLCore()`. Recording is
                        // fine that way, but pano's stream scaler cannot sample our texture
                        // across contexts, so view 6 rendered the AVM mosaic / black. Pano
                        // is up NOW, so restart the pipeline to re-create its context in
                        // pano's share group — this is the case the old code explicitly
                        // skipped, leaving the manual OEM off/on as the only recovery.
                        //
                        // STRICTLY GUARDED so recording can never be harmed:
                        //   - streamingDesired: only when a view-6 viewer actually needs it.
                        //   - !isRecording(): never interrupt an open dvr_*.mp4 / event clip
                        //     (a recording session keeps its independent context and keeps
                        //     working; the heal happens on the next recalc once it closes).
                        //   - isPanoEglAvailable(): pointless to restart if pano still has
                        //     no EGLCore to parent us — we'd just get another independent
                        //     context and thrash.
                        com.overdrive.app.daemon.CameraDaemon.log(
                            "OemDashcam: warm pipeline is on an INDEPENDENT EGL context and "
                            + "view-6 streaming is requested — restarting to join pano's "
                            + "share group (recording idle, safe to restart)");
                        // Latch BEFORE the restart so a failure can't re-enter here every
                        // 30s (see eglHealAttempted). Cleared on success below so a future
                        // unshared bring-up can still be healed once.
                        eglHealAttempted = true;
                        try { existing.stop(); } catch (Throwable ignored) {}
                        com.overdrive.app.daemon.CameraDaemon.setOemDashcamPipeline(null);
                        existing = startPipeline(expectedRevision);
                        if (existing == null) return;
                        if (existing.isEglSharedWithPano()) {
                            eglHealAttempted = false;   // heal worked; re-arm for next time
                            com.overdrive.app.daemon.CameraDaemon.log(
                                "OemDashcam: EGL heal OK — now sharing pano's context, "
                                + "view-6 streaming can render");
                        } else {
                            com.overdrive.app.daemon.CameraDaemon.log(
                                "OemDashcam: EGL heal did NOT take (driver refused share "
                                + "group) — recording unaffected; view 6 stays unavailable. "
                                + "Not retrying this daemon run.");
                        }
                        // needFormatWait is computed for every shouldRun path below.
                    } else {
                        // Pipeline is ALREADY warm — start() (and its
                        // applyRecordingConfigFromUcm axis resolve) will NOT
                        // re-run. This is the ACC on↔off warm-handover case
                        // (e.g. recordingMode=continuous AND
                        // surveillanceMode=continuous, so recordingDesired never
                        // drops across the edge). Live-re-apply the fps/bitrate
                        // for the NOW-current axis so a drive→park transition
                        // switches OEM from the recording tier to the
                        // surveillance tier (and back) without a reinit. No-op
                        // when the resolved axis profile is unchanged.
                        try { existing.reapplyAxisProfileFromUcm(); } catch (Throwable ignored) {}
                    }
                    // Wedge if format isn't ready AND we need recording —
                    // we'll wait OUTSIDE the lock then re-enter to start
                    // recording (re-checking liveness post-relock).
                    needFormatWait = recordingDesired && !existing.isRecording();
                    // Only stop a recording the RESOLVER owns. A surveillance
                    // motion clip (opened via tryStartIfIdle, surv=smart) sets
                    // recordingEventOwned — its lifecycle belongs to
                    // SurveillanceEngineGpu (stopRecordingIfOwned on event end),
                    // NOT to this resolver. Without this guard the periodic
                    // self-heal recalc (every 30s) would truncate every
                    // surv=smart clip the instant a tick observed
                    // recordingDesired=false while a motion clip was open.
                    //
                    // The ownership check and the stop MUST be atomic w.r.t. the
                    // (recording, recordingEventOwned) pair: a separate
                    // isRecording()/isRecordingEventOwned()/stopRecording()
                    // sequence is a check-then-act race — an independent
                    // handleWriterAbort clearing `recording` then a surveillance
                    // tryStartIfIdle opening an event clip in the gap would let
                    // the ownership-blind stopRecording() truncate it.
                    // stopRecordingIfNotEventOwned() collapses both into one
                    // recordingStateLock critical section.
                    if (!recordingDesired) {
                        try { existing.stopRecordingIfNotEventOwned(); } catch (Throwable ignored) {}
                    }
                } else {
                    // No triggers, no streaming — tear down completely.
                    if (existing != null) {
                        try { existing.stopRecording(); } catch (Throwable ignored) {}
                        try { existing.stop(); } catch (Throwable ignored) {}
                        com.overdrive.app.daemon.CameraDaemon.setOemDashcamPipeline(null);
                        oemNoFrameRestarts = 0;
                        oemNoFrameNextRestartAtMs = 0L;
                        com.overdrive.app.daemon.CameraDaemon.log(
                            "OemDashcam: pipeline stopped (no triggers, no streaming clients)");
                    }
                    return;
                }
            } catch (Throwable t) {
                com.overdrive.app.daemon.CameraDaemon.log(
                    "OemDashcam: trigger lifecycle failed (phase 1): " + t.getMessage());
                return;
            }
        }

        // startPipeline() can spend several seconds in the factory HAL warmup.
        // If ACC/config changed meanwhile, a fresh pass is already queued.
        if (!isLifecycleRevisionCurrent(expectedRevision)) {
            com.overdrive.app.daemon.CameraDaemon.log(
                "OemDashcam: lifecycle superseded during pipeline warmup "
                + "— skipping stale recording start");
            return;
        }

        // The lifecycle worker owns recovery after an OEM watchdog stop.
        // Reattach a rebuilt, route-ready source and let its first real
        // SurfaceTexture frame promote view 6. This avoids requiring another
        // browser click or WebSocket reconnect to resume a live DVR view.
        if (streamingDesired && existing != null && existing.isRouteReady()) {
            try {
                com.overdrive.app.surveillance.GpuSurveillancePipeline pano =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                if (pano != null && pano.isStreamingEnabled()
                        && com.overdrive.app.daemon.CameraDaemon
                                .getOemDashcamPipeline() == existing
                        && com.overdrive.app.daemon.CameraDaemon
                                .routeStreamToOemDashcam()) {
                    pano.activateOemStreamViewWhenReady();
                }
            } catch (Throwable t) {
                com.overdrive.app.daemon.CameraDaemon.log(
                    "OemDashcam: lifecycle stream reattach failed: " + t.getMessage());
            }
        }

        // Phase 2 (lock-free): wait for encoder OUTPUT_FORMAT_CHANGED.
        // Without the wait, triggerEventRecording opens the muxer with
        // savedFormat=null and produces a 168 KB empty .mp4. Wait
        // outside the lock so concurrent lifecycle calls aren't pinned.
        if (!needFormatWait) return;
        boolean encoderReady = waitForEncoderFormat(existing, 3000);
        if (!encoderReady) {
            // R8-A #3: format never arrived. startRecording would build a
            // muxer with savedFormat=null → 168 KB empty .mp4 (the exact
            // pathology phase 2 was meant to avoid). Defer to next
            // lifecycle recalc — a future ACC/click/timer event will
            // retry.
            com.overdrive.app.daemon.CameraDaemon.log(
                "OemDashcam: encoder warmup timeout — deferring recording-start "
                + "to next lifecycle recalc");
            return;
        }

        // Phase 3 (under lock): re-acquire and start recording. State
        // may have changed during the unlocked window (a competing
        // teardown could have stopped + nulled the pipeline). Re-check
        // every gate before mutating. R8-A #2: startRecording's own
        // compareAndSet is the authoritative ownership gate; it returns
        // false if a concurrent watchdog/render-loop stop has already
        // flipped recording=false → running=false. The phase-3 isRunning
        // check below is a fast-path; startRecording's CAS catches the
        // residual race.
        synchronized (LIFECYCLE_LOCK) {
            try {
                if (!isLifecycleRevisionCurrent(expectedRevision)) {
                    com.overdrive.app.daemon.CameraDaemon.log(
                        "OemDashcam: lifecycle superseded during encoder warmup "
                        + "— skipping stale recording start");
                    return;
                }
                com.overdrive.app.camera.OemDashcamPipeline current =
                    com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
                if (current == existing
                    && current != null
                    && current.isRunning()
                    && !current.isRecording()) {
                    if (!current.startRecording()) {
                        com.overdrive.app.daemon.CameraDaemon.log(
                            "OemDashcam: startRecording refused (recording trigger active "
                            + "or pipeline torn down during phase-2 wait)");
                    }
                }
                // Else: state changed during the unlocked window — let
                // the next lifecycle recalc reconcile.
            } catch (Throwable t) {
                com.overdrive.app.daemon.CameraDaemon.log(
                    "OemDashcam: trigger lifecycle failed (phase 3): " + t.getMessage());
            }
        }
    }

    /**
     * Recompute the recording / streaming desire from UCM + live pipeline
     * state and apply. Resolution:
     * <ul>
     *   <li>{@code recordingMode == continuous} ⇒ recording always on.</li>
     *   <li>{@code recordingMode == smart} ⇒ recording iff pano dashcam is
     *       currently recording (cam_*.mp4 is being written).</li>
     *   <li>{@code surveillanceMode == continuous} ⇒ recording always on
     *       (effectively the same as recording.continuous from the
     *       pipeline's perspective, separate UI control).</li>
     *   <li>{@code surveillanceMode == smart} is event-driven — the actual
     *       recording starts inside SurveillanceEngineGpu when motion fires;
     *       this resolver only keeps the pipeline warm so the start latency
     *       is small.</li>
     * </ul>
     */
    public static void applyTriggerLifecycleFromUcm() {
        long expectedRevision = lifecycleRevision.get();
        boolean streamingDesired = isAnyStreamingViewerActive();

        // AccMonitor starts at accOn=false, but that cold default explicitly
        // means "unknown", not a trustworthy parked state. Never let a boot
        // resolver or the 30-second self-heal ticker turn that default into an
        // automatic OEM AVMCamera/encoder start. An authenticated live DVR
        // viewer remains an explicit owner and may start a non-recording
        // pipeline; when that viewer disappears, this same branch tears it
        // down. A later authoritative IPC/probe re-drives the normal axis
        // resolver through the ACC hooks or self-heal ticker.
        boolean accKnown =
            com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
            || com.overdrive.app.monitor.AccMonitor.wasLastProbeTrustworthy();
        if (!accKnown) {
            com.overdrive.app.daemon.CameraDaemon.log(
                "OemDashcam: ACC state unknown — suppressing automatic "
                + "recording/surveillance lifecycle"
                + (streamingDesired ? "; explicit DVR viewer remains active" : ""));
            applyTriggerLifecycle(false, streamingDesired, expectedRevision);
            return;
        }

        // Force-reload from disk: the picker may have been written by the app process
        // (settings activity), and our cache is mtime-gated to 1s — without a force
        // reload we can read state up to ~1s stale. Per project convention
        // (feedback_unified_config_force_reload.md), daemon-side reads of cross-UID
        // state must forceReload first.
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        String rec = com.overdrive.app.config.UnifiedConfigManager.getOemRecordingMode();
        String surv = com.overdrive.app.config.UnifiedConfigManager.getOemSurveillanceMode();

        boolean accOn = com.overdrive.app.monitor.AccMonitor.isAccOn();

        // Surveillance-axis suppression: the user's safe-zone / schedule /
        // master-surveillance toggles already gate pano sentry; the OEM
        // surveillance axis must honor the same gates so a parked-window
        // user-intent doesn't bypass them. Recording-axis suppression is
        // intentionally NOT applied — recording is the user's ACC-ON intent
        // and is independent of the surveillance master toggle.
        boolean survSuppressed = false;
        if (!accOn) {
            try {
                boolean userEnabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
                boolean inSafeZone = com.overdrive.app.surveillance.SafeLocationManager.getInstance().isInSafeZone();
                boolean outsideSchedule = false;
                com.overdrive.app.surveillance.SurveillanceSchedule schedule =
                    com.overdrive.app.config.UnifiedConfigManager.getSurveillanceSchedule();
                if (schedule != null && schedule.isEnabled() && !schedule.isActiveNow()) {
                    outsideSchedule = true;
                }
                survSuppressed = !userEnabled || inSafeZone || outsideSchedule;
            } catch (Throwable t) {
                // These are privacy/ownership gates. If their state cannot be
                // evaluated, defer the parked OEM camera rather than treating
                // the failure as permission to open it. The self-heal ticker
                // retries after the transient clears.
                survSuppressed = true;
                com.overdrive.app.daemon.CameraDaemon.log(
                    "OemDashcam: parked surveillance gate unavailable — "
                    + "suppressing lifecycle for this pass: " + t.getMessage());
            }
        }

        // GATE (G5): "Vehicle ON only" mode — the parked (surveillance) axis must be
        // fully suppressed so no OEM dvr_*.mp4 is written and no OEM pipeline is kept
        // warm after the vehicle powers off. Gated HERE inside the resolver (not at the
        // ACC-OFF call site) on purpose: applyTriggerLifecycleFromUcm() is ALSO re-run
        // every ~30s by startSelfHealTicker() independent of any ACC edge, so a call-site
        // gate would be bypassed by the ticker and keep the pipeline warm. survSuppressed
        // only feeds the !accOn (parked) clauses below, so forcing it true leaves the
        // ACC-ON recording axis (recordingDesired for rec=continuous&&accOn, keepWarmRec)
        // fully intact. Fail-open: false → normal parked behaviour.
        if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
            survSuppressed = true;
        }

        // The two axes are independent and ACC-gated symmetrically:
        //   recordingMode  = drive-time intent  (ACC ON  phase only)
        //   surveillanceMode = parked-time intent (ACC OFF phase only)
        // Pre-fix, rec=continuous bled into ACC OFF, so a user who picked
        // "rec=continuous, surv=off" got dvr_*.mp4 across parked windows
        // they never asked for — and conversely a "rec=off, surv=continuous"
        // user got nothing during ACC OFF if rec was the only path checked.
        boolean recordingDesired = false;
        if ("continuous".equals(rec)  &&  accOn) recordingDesired = true;
        if ("continuous".equals(surv) && !accOn && !survSuppressed) recordingDesired = true;
        if ("smart".equals(rec) && isPanoDashcamRecording()) recordingDesired = true;

        // DIAGNOSTIC (root-cause confirm for "dvr_ clips while the OEM Dashcam
        // UI shows Off"): the dvr_*.mp4 writer is reached ONLY when an OEM axis
        // is non-off here, yet the user reports both axes reading Off in the UI
        // — which means the PERSISTED rec/surv the daemon resolves may diverge
        // from what the web pickers display (e.g. a legacy-migrated mode, or a
        // page that rendered its 'off' default before /api/oem-dashcam/config
        // hydrated). Log the resolved truth on EVERY resolve (not only when
        // recordingDesired) so a single drive reveals exactly which persisted
        // axis is driving dvr_ — no repro needed. Only logged when an axis is
        // non-off OR recording is desired, so a genuinely-off vehicle stays
        // quiet (no per-tick spam from the 30s self-heal ticker).
        if (recordingDesired || !"off".equals(rec) || !"off".equals(surv)) {
            com.overdrive.app.daemon.CameraDaemon.log(
                "OemDashcam resolve: recordingDesired=" + recordingDesired
                + " (persisted rec=" + rec + " surv=" + surv + " accOn=" + accOn
                + " survSuppressed=" + survSuppressed
                + " panoRecording=" + isPanoDashcamRecording() + ")"
                + (recordingDesired ? " — will write dvr_*.mp4" : ""));
        }

        // Keep-warm follows the same axis split: rec=smart needs a warm pipeline
        // during ACC ON to mirror pano starts; surv=smart needs a warm pipeline
        // during ACC OFF to fire motion-triggered event clips. Outside its own
        // ACC phase each axis is dormant — keeping the pipeline up burns 12V
        // and contends with sentry for the AVMCamera handle on single-client HALs.
        boolean keepWarmRec  = "smart".equals(rec)  &&  accOn;
        boolean keepWarmSurv = "smart".equals(surv) && !accOn && !survSuppressed;
        boolean keepWarm = keepWarmRec
            || keepWarmSurv
            || streamingDesired;

        applyTriggerLifecycle(recordingDesired, keepWarm, expectedRevision);
    }

    private static boolean isLifecycleRevisionCurrent(long expectedRevision) {
        return lifecycleRevision.get() == expectedRevision;
    }

    private static boolean isPanoDashcamRecording() {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pano =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            return pano != null && pano.isRecording();
        } catch (Throwable t) { return false; }
    }

    /** True iff the pano pipeline currently has streaming enabled AND view 6 (OEM
     *  Dashcam) is the active or REQUESTED view. When the user navigates away from
     *  DVR view, this flips false and the pipeline can shut down if no other trigger
     *  is active.
     *
     *  <p>Two signals are accepted, and BOTH are needed:
     *  <ul>
     *    <li>the live scaler view-mode (6) — the steady state once the route landed;</li>
     *    <li>the persisted DESIRED view-mode (6) — the user's pick during the OEM
     *        warmup window. The scaler is deliberately NOT switched to 6 until the
     *        OEM bind succeeds (a view-6 scaler with uOemActive=0 falls through the
     *        shader's OEM gate and paints the 4-cam AVM mosaic). Without this second
     *        signal, streamingDesired would read false for the whole warmup and the
     *        lifecycle recalc would never bring the OEM pipeline up at all —
     *        deadlocking DVR permanently.</li>
     *  </ul> */
    public static boolean isAnyStreamingViewerActive() {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pano =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (pano == null || !pano.isStreamingEnabled()) return false;
            if (pano.getStreamViewMode() == 6) return true;
            return com.overdrive.app.server.StreamingApiHandler.getLastDesiredViewMode() == 6;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Start the OEM pipeline (camera + EGL + encoder). Does NOT open a
     * recording — caller is responsible for {@code startRecording()} when
     * a trigger demands it. Returns null on failure (UCM gets a
     * lastStartError).
     */
    /** True when pano is running AND already owns a live EGLCore we can parent the OEM
     *  context to. Used to gate the independent-context self-heal restart: without a
     *  parent to share with, a restart would just produce another independent context
     *  (thrash). Cheap, non-blocking — unlike startPipeline's 3s wait. */
    private static boolean isPanoEglAvailable() {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pano =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (pano == null || !pano.isRunning()) return false;
            com.overdrive.app.camera.PanoramicCameraGpu panoCam = pano.getCamera();
            return panoCam != null && panoCam.getEglCore() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static com.overdrive.app.camera.OemDashcamPipeline startPipeline(
            long expectedRevision) {
        final long cameraStartEpoch =
                com.overdrive.app.daemon.CameraDaemon.captureCameraStartEpoch();
        if (!isLifecycleRevisionCurrent(expectedRevision)
                || !com.overdrive.app.daemon.CameraDaemon
                    .isCameraStartEpochCurrent(cameraStartEpoch)) {
            return null;
        }
        int oemId = com.overdrive.app.config.UnifiedConfigManager.resolveOemDashcamId();
        if (oemId < 0) {
            com.overdrive.app.daemon.CameraDaemon.log(
                "OemDashcam: cannot start — id explicitly disabled");
            return null;
        }
        String outDir = com.overdrive.app.storage.StorageManager.getInstance()
            .getRecordingsPath();
        try {
            JSONObject clearErr = new JSONObject();
            clearErr.put("lastStartError", JSONObject.NULL);
            clearErr.put("lastStartErrorAt", 0);
            // Wipe any prior writer-abort marker too so the OEM card on the
            // surveillance page transitions from "errored" back to "armed"
            // when the user retries after an SD remount.
            clearErr.put("lastWriteError", JSONObject.NULL);
            clearErr.put("lastWriteErrorAt", 0);
            com.overdrive.app.config.UnifiedConfigManager.setOemDashcam(clearErr);
        } catch (Throwable ignored) {}
        com.overdrive.app.camera.OemDashcamPipeline p =
            new com.overdrive.app.camera.OemDashcamPipeline(outDir);
        try {
            com.overdrive.app.telemetry.TelemetryDataCollector tdc =
                com.overdrive.app.daemon.CameraDaemon.getTelemetryDataCollector();
            if (tdc != null) p.setTelemetryCollector(tdc);
        } catch (Throwable ignored) {}
        boolean oemOverlay = com.overdrive.app.config.UnifiedConfigManager
            .isTelemetryOverlayEnabledFor("oemDashcam");
        p.setOverlayEnabled(oemOverlay);
        // Apply the OEM dashcam's own field selection (independent of pano /
        // surveillance). Absent list resolves to the legacy default.
        p.setOverlayFields(com.overdrive.app.telemetry.TelemetryFields.fromJsonArray(
            com.overdrive.app.config.UnifiedConfigManager
                .getTelemetryOverlayFields("oemDashcam")));
        // ── Parent-EGL wiring ────────────────────────────────────────────────
        // OEM's GL context MUST be created in pano's EGL SHARE GROUP, otherwise
        // pano's stream scaler cannot sample our camera texture and view-6 (DVR)
        // streaming renders the 4-cam AVM mosaic / black while recording looks fine.
        // Getting this right at START is the only clean fix: the context is immutable
        // after start(), so the alternative is a restart — which is unacceptable once
        // a dvr_*.mp4 is open.
        //
        // Two defects in the previous bounded poll are fixed here:
        //  1) It waited on `pano.isRunning()` but needed the CAMERA's EGLCore. Wait on
        //     the ACTUAL precondition (a non-null EGLCore) so we can't exit the wait
        //     and then silently skip the parent because the handle wasn't published.
        //  2) It only WAITED for pano — it never asked pano to come up. When nothing
        //     else was starting pano (recording-trigger bring-up, ACC edge, boot race)
        //     it burned the timeout and fell through to an independent context. We now
        //     ACTIVELY ensure pano is starting before waiting, so the wait has a
        //     reason to succeed instead of being a coin flip on scheduling order.
        // The wait is capped so a genuinely broken pano can never wedge OEM: recording
        // still comes up (independent context records perfectly), only DVR preview is
        // deferred to the self-heal restart.
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pano =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            // Only PAY for the share group when DVR preview is actually wanted. On a
            // recording-only bring-up (parked sentry / dvr triggers, no view-6 client)
            // an independent context is perfectly fine, and starting pano here would
            // boot the whole 360 camera for nothing — real battery cost while parked.
            // So: request/await pano ONLY when a view-6 viewer is asking. Recording-only
            // keeps exactly the previous behaviour (no pano start, no wait).
            boolean dvrPreviewWanted = isAnyStreamingViewerActive();
            if (pano != null && dvrPreviewWanted) {
                // (2) Actively bring pano up if it isn't already — without this the wait
                // below is purely passive and loses the boot race. Failure is non-fatal:
                // we fall through to the capped wait and, worst case, an independent
                // context that the self-heal restart repairs later.
                if (!pano.isRunning()) {
                    try {
                        com.overdrive.app.daemon.CameraDaemon.log(
                            "OemDashcam: pano not up yet — requesting pano start so OEM can "
                            + "join its EGL share group (required for DVR preview)");
                        // start(false) = bring the pipeline up WITHOUT auto-recording —
                        // the same call enableStreaming() uses for a view-only client, so
                        // this cannot start an unwanted pano recording. It is idempotent
                        // (no-ops on running/starting, refuses while stopping), so a
                        // concurrent bring-up is harmless.
                        pano.start(false, cameraStartEpoch);
                    } catch (Throwable t) {
                        com.overdrive.app.daemon.CameraDaemon.log(
                            "OemDashcam: pano start request failed: " + t.getMessage());
                    } finally {
                        // The OEM pipeline is not published yet. If this
                        // lifecycle is superseded, cancelled, or fails later,
                        // there may be no setOemDashcamPipeline(null) edge to
                        // release the pano that this preview request started.
                        // Defer to pano's generation-pinned exhaustive owner
                        // verdict; while this OEM transition is in flight it
                        // waits, then either observes the published OEM owner
                        // (or any other owner) or retires the orphan.
                        try {
                            pano.auditOwnerlessPipelineAfterExternalRelease(
                                "OEM preview startup settlement");
                        } catch (Throwable auditFailure) {
                            com.overdrive.app.daemon.CameraDaemon.log(
                                "OemDashcam: could not arm pano owner audit: "
                                    + auditFailure.getMessage());
                        }
                    }
                }
                // (1) Wait on the REAL precondition: pano running AND its EGLCore
                // published. PanoramicCameraGpu publishes eglCore (initializeGl) before
                // its own running flag, and GpuSurveillancePipeline gates running on
                // camera.isRunning(), so in practice this resolves as soon as pano is
                // up — but polling the handle itself removes the ordering assumption.
                long deadline = android.os.SystemClock.elapsedRealtime()
                    + PANO_EGL_WAIT_MS;
                com.overdrive.app.camera.EGLCore parentEgl = null;
                while (android.os.SystemClock.elapsedRealtime() < deadline
                        && isLifecycleRevisionCurrent(expectedRevision)
                        && com.overdrive.app.daemon.CameraDaemon
                                .isCameraStartEpochCurrent(cameraStartEpoch)) {
                    if (pano.isRunning()) {
                        com.overdrive.app.camera.PanoramicCameraGpu panoCam = pano.getCamera();
                        if (panoCam != null && panoCam.getEglCore() != null) {
                            parentEgl = panoCam.getEglCore();
                            break;
                        }
                    }
                    try { Thread.sleep(50); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (parentEgl != null) {
                    p.setParentEglCore(parentEgl);
                } else {
                    com.overdrive.app.daemon.CameraDaemon.log(
                        "OemDashcam: pano EGL unavailable within " + (PANO_EGL_WAIT_MS / 1000)
                        + "s — starting on an independent context. RECORDING is unaffected; "
                        + "DVR preview will self-heal (restart into the share group) once "
                        + "pano is up and no recording is open.");
                }
            } else if (pano != null && pano.isRunning()) {
                // Recording-only bring-up, but pano happens to ALREADY be up (typical
                // mid-drive). Joining its share group is then FREE — no start, no wait —
                // so take it: if the user opens DVR later, the route just works instead
                // of needing a self-heal restart. Purely opportunistic; a null EGLCore
                // simply leaves us independent as before.
                try {
                    com.overdrive.app.camera.PanoramicCameraGpu panoCam = pano.getCamera();
                    if (panoCam != null && panoCam.getEglCore() != null) {
                        p.setParentEglCore(panoCam.getEglCore());
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        if (!isLifecycleRevisionCurrent(expectedRevision)
                || !com.overdrive.app.daemon.CameraDaemon
                    .isCameraStartEpochCurrent(cameraStartEpoch)) {
            return null;
        }
        try {
            p.start(
                cameraStartEpoch,
                () -> isLifecycleRevisionCurrent(expectedRevision));
        } catch (Throwable t) {
            com.overdrive.app.daemon.CameraDaemon.log(
                "OemDashcam: pipeline.start failed: " + t.getMessage());
            try {
                JSONObject rb = new JSONObject();
                rb.put("lastStartError",
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                rb.put("lastStartErrorAt", java.lang.System.currentTimeMillis());
                com.overdrive.app.config.UnifiedConfigManager.setOemDashcam(rb);
            } catch (Throwable ignored) {}
            return null;
        }
        if (!isLifecycleRevisionCurrent(expectedRevision)
                || !com.overdrive.app.daemon.CameraDaemon
                    .isCameraStartEpochCurrent(cameraStartEpoch)) {
            try { p.stop(); } catch (Throwable ignored) {}
            com.overdrive.app.daemon.CameraDaemon.log(
                "OemDashcam: completed startup was superseded before publish "
                    + "— replacement pipeline retired");
            return null;
        }
        com.overdrive.app.daemon.CameraDaemon.setOemDashcamPipeline(p);
        com.overdrive.app.daemon.CameraDaemon.log(
            "OemDashcam: pipeline ready (id=" + oemId + ", outDir=" + outDir + ")");
        return p;
    }

    // ---------------------------------------------------------------
    // Legacy single-flag entry kept so existing callers (CameraDaemon
    // boot path, ACC dispatch, /api/oem-dashcam/config restart hook)
    // keep compiling. Deprecated in favour of applyTriggerLifecycle.
    // The boolean carries "recordingDesired"; streamingDesired is
    // re-derived from pano state.
    public static void applyLifecycle(boolean enabled) {
        applyTriggerLifecycle(enabled, isAnyStreamingViewerActive());
    }

    /**
     * Rebuild the OEM camera and encoder from current persisted intent.
     * applyLifecycle(false) is not a restart when view 6 still keeps the
     * pipeline desired; it simply retains the old instance.
     */
    public static void forceRestartPipelineFromCurrentIntent() {
        synchronized (LIFECYCLE_LOCK) {
            com.overdrive.app.camera.OemDashcamPipeline existing =
                com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            if (existing != null) {
                try { existing.stopRecording(); } catch (Throwable ignored) {}
                try { existing.stop(); } catch (Throwable ignored) {}
                com.overdrive.app.daemon.CameraDaemon.setOemDashcamPipeline(null);
                com.overdrive.app.daemon.CameraDaemon.log(
                    "OemDashcam: force restart released current pipeline");
            }
        }
        applyTriggerLifecycleFromUcm();
    }

    public static boolean stopPipelineForExplicitUserStop() {
        return stopPipelineBeforePanoTeardown();
    }

    public static boolean stopPipelineBeforePanoTeardown() {
        lifecycleRevision.incrementAndGet();
        lifecyclePending.set(false);
        synchronized (LIFECYCLE_LOCK) {
            com.overdrive.app.camera.OemDashcamPipeline existing =
                com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            if (existing == null) return true;
            boolean stopped = true;
            try { existing.stopRecording(); } catch (Throwable failure) {
                stopped = false;
            }
            try { existing.stop(); } catch (Throwable failure) {
                stopped = false;
            }
            if (com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline()
                    == existing) {
                com.overdrive.app.daemon.CameraDaemon
                        .setOemDashcamPipeline(null);
            }
            try {
                return stopped && !existing.isRunning();
            } catch (Throwable failure) {
                return false;
            }
        }
    }

    /**
     * Poll the OEM pipeline's encoder until OUTPUT_FORMAT_CHANGED has
     * fired, so a subsequent {@code triggerEventRecording} can build a
     * muxer with a valid track. Returns false on timeout. Direct accessor
     * — no reflection on the hot path.
     */
    private static boolean waitForEncoderFormat(
            com.overdrive.app.camera.OemDashcamPipeline pipeline, long timeoutMs) {
        if (pipeline == null) return false;
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                if (pipeline.isEncoderFormatAvailable()) return true;
            } catch (Throwable ignored) {
                return false;
            }
            try { Thread.sleep(50); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Boot-time hook: if any oemDashcam trigger is on at boot, warm the
     * pipeline. Mirrors enforceStickyDisableIfRequested for the native-DVR
     * side. Runs the legacy-schema migration first so installs that
     * pre-date the trigger refactor pick up their old enabled+accOffMode
     * intent without forcing the user back into the dialog.
     */
    public static void enforceStickyEnableIfRequested() {
        try {
            com.overdrive.app.config.UnifiedConfigManager.migrateOemDashcamModes();
            if (com.overdrive.app.config.UnifiedConfigManager.isAnyOemDashcamTriggerEnabled()) {
                com.overdrive.app.daemon.CameraDaemon.log(
                    "OemDashcam: re-applying mirror triggers at boot");
                scheduleLifecycleRecalc();
            }
        } catch (Throwable t) {
            com.overdrive.app.daemon.CameraDaemon.log(
                "OemDashcam: sticky-enable check failed: " + t.getMessage());
        }
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/oem-dashcam/native-dvr/status") && method.equals("GET")) {
            handleStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/oem-dashcam/native-dvr/disable") && method.equals("POST")) {
            handleDisable(out);
            return true;
        }
        if (cleanPath.equals("/api/oem-dashcam/native-dvr/enable") && method.equals("POST")) {
            handleEnable(out);
            return true;
        }
        // OEM Dashcam feature toggle + ACC-off mode. The values land in the
        // oemDashcam UCM section; the daemon-side lifecycle (Phase B) reads
        // them at boot/start. GET returns current values; POST accepts
        // partial updates ({enabled?, accOffMode?}).
        if (cleanPath.equals("/api/oem-dashcam/config") && method.equals("GET")) {
            handleGetConfig(out);
            return true;
        }
        if (cleanPath.equals("/api/oem-dashcam/config") && method.equals("POST")) {
            handlePostConfig(out, body);
            return true;
        }
        return false;
    }

    private static void handleGetConfig(OutputStream out) throws Exception {
        JSONObject oem = com.overdrive.app.config.UnifiedConfigManager.getOemDashcam();
        JSONObject response = new JSONObject();
        response.put("success", true);
        // Two modes — one per page. Off | Continuous | Smart.
        // Smart on the recording page = follow pano dashcam mode
        //   (Continuous / Drive Mode / Proximity Guard).
        // Smart on the surveillance page = follow pano surveillance motion
        //   detection (record dvr_*.mp4 alongside event_*.mp4).
        response.put("recordingMode",
            com.overdrive.app.config.UnifiedConfigManager.getOemRecordingMode());
        response.put("surveillanceMode",
            com.overdrive.app.config.UnifiedConfigManager.getOemSurveillanceMode());
        response.put("recordingQuality", oem.optString("recordingQuality", "STANDARD"));
        response.put("codec", oem.optString("codec", "H264"));
        response.put("fps", oem.optInt("fps", 30));
        response.put("disableNativeDvr", oem.optBoolean("disableNativeDvr", false));
        if (oem.has("lastStartError") && !oem.isNull("lastStartError")) {
            response.put("lastStartError", oem.optString("lastStartError", ""));
            response.put("lastStartErrorAt", oem.optLong("lastStartErrorAt", 0));
        }
        // Disk-writer aborts (SD unmount / full volume) flow into UCM via
        // OemDashcamPipeline.handleWriterAbort. Surface the latest abort
        // reason so the UI can transition the OEM badge from "recording"
        // to "errored" instead of lying about a dead muxer.
        if (oem.has("lastWriteError") && !oem.isNull("lastWriteError")) {
            response.put("lastWriteError", oem.optString("lastWriteError", ""));
            response.put("lastWriteErrorAt", oem.optLong("lastWriteErrorAt", 0));
        }
        com.overdrive.app.camera.OemDashcamPipeline pipe =
            com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
        response.put("pipelineRunning", pipe != null && pipe.isRunning());
        response.put("recording", pipe != null && pipe.isRecording());
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handlePostConfig(OutputStream out, String body) throws Exception {
        JSONObject delta = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);

            // FIX M3: capture mode/quality before applying delta so we can
            // skip the (expensive) lifecycle recalc when nothing material
            // changed. Auto-save UIs hammer this endpoint on every keystroke
            // / scroll-tick; without the early-out, the LIFECYCLE_EXEC kept
            // submitting recalcs that triggered encoder rebuilds inside
            // applyLifecycle even though the resolved state was identical.
            JSONObject beforeOem = com.overdrive.app.config.UnifiedConfigManager.getOemDashcam();
            String oldRec = com.overdrive.app.config.UnifiedConfigManager.getOemRecordingMode();
            String oldSurv = com.overdrive.app.config.UnifiedConfigManager.getOemSurveillanceMode();
            String oldQuality = beforeOem == null ? "" : beforeOem.optString("recordingQuality", "");
            String oldCodec = beforeOem == null ? "" : beforeOem.optString("codec", "");
            int oldFps = beforeOem == null ? 0 : beforeOem.optInt("fps", 0);

            // The two mode pickers — recording page and surveillance page
            // post their own flag independently. updateSection merges
            // per-key so a partial post doesn't clobber the other.
            if (req.has("recordingMode")) {
                String m = req.optString("recordingMode", "off").toLowerCase();
                if (!"off".equals(m) && !"continuous".equals(m) && !"smart".equals(m)) {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("error", "recordingMode must be off|continuous|smart");
                    HttpResponse.sendJson(out, err.toString());
                    return;
                }
                delta.put("recordingMode", m);
            }
            if (req.has("surveillanceMode")) {
                String m = req.optString("surveillanceMode", "off").toLowerCase();
                if (!"off".equals(m) && !"continuous".equals(m) && !"smart".equals(m)) {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("error", "surveillanceMode must be off|continuous|smart");
                    HttpResponse.sendJson(out, err.toString());
                    return;
                }
                delta.put("surveillanceMode", m);
            }
            // Legacy single-flag from older web shells. enabled=true ⇒ smart.
            if (req.has("enabled") && !delta.has("recordingMode")) {
                delta.put("recordingMode", req.optBoolean("enabled", false) ? "smart" : "off");
            }
            // Per-OEM quality/codec/fps overrides (optional). Pano picker
            // already mirrors into oemDashcam.* via QualitySettingsApiHandler;
            // these accept explicit OEM-only overrides from the future
            // dedicated picker.
            if (req.has("recordingQuality")) {
                String t = req.optString("recordingQuality", "").toUpperCase();
                if (t.equals("ECONOMY") || t.equals("STANDARD") || t.equals("HIGH")
                        || t.equals("PREMIUM") || t.equals("MAX")) {
                    delta.put("recordingQuality", t);
                }
            }
            if (req.has("codec")) {
                String c = req.optString("codec", "").toUpperCase();
                if ("H264".equals(c) || "H265".equals(c)) delta.put("codec", c);
            }
            if (req.has("fps")) {
                int f = req.optInt("fps", -1);
                if (f >= 10 && f <= 60) delta.put("fps", f);
            }
            // Parked-idle encode throttle knobs (separate clause — the drive/
            // continuous fps floor above is untouched). idleFps is the effective
            // encode rate while parked-idle keep-warm (camera HAL stays >=15);
            // idleThrottleWhenParked is the master gate (default off).
            if (req.has("idleFps")) {
                int f = req.optInt("idleFps", -1);
                if (f >= 1 && f <= 15) delta.put("idleFps", f);
            }
            if (req.has("idleThrottleWhenParked")) {
                delta.put("idleThrottleWhenParked", req.optBoolean("idleThrottleWhenParked", false));
            }

            // Surveillance integration toggle. Web posts {"surveillance":
            // {"enabled":true|false}}. Audit found the handler silently
            // dropped this — the toggle in surveillance.html appeared to
            // save but UCM never changed and the toggle popped back. Merge
            // the nested object into the section delta so updateSection's
            // per-key merge (UnifiedConfigManager.kt:817-825) picks it up.
            //
            // AUTO-PROMOTE: when user enables surveillance integration,
            // automatically also flip oemDashcam.enabled=true. Without
            // this, surveillance event recording is a silent no-op
            // (SurveillanceEngineGpu.startEventRecording requires
            // oemPipe.isRunning() which requires the pipeline to be
            // started by the main toggle). The user opted in to OEM
            // event clips; respecting that intent means the pipeline
            // must actually run.
            // Legacy nested surveillance.enabled from older web shells.
            // Maps surveillanceMode = smart|off.
            if (req.has("surveillance") && !delta.has("surveillanceMode")) {
                JSONObject sIn = req.optJSONObject("surveillance");
                if (sIn != null && sIn.has("enabled")) {
                    boolean survOn = sIn.optBoolean("enabled", false);
                    delta.put("surveillanceMode", survOn ? "smart" : "off");
                }
            }
            // Mirror surveillanceMode != off into the legacy nested object so
            // SurveillanceEngineGpu (which reads oemDashcam.surveillance.enabled)
            // keeps working without a conversion sweep.
            if (delta.has("surveillanceMode")) {
                boolean survOn = !"off".equals(delta.optString("surveillanceMode", "off"));
                JSONObject sExisting = com.overdrive.app.config.UnifiedConfigManager
                    .getOemDashcam().optJSONObject("surveillance");
                JSONObject sOut = sExisting != null
                    ? new JSONObject(sExisting.toString())
                    : new JSONObject();
                sOut.put("enabled", survOn);
                delta.put("surveillance", sOut);
            }

            if (delta.length() > 0) {
                com.overdrive.app.config.UnifiedConfigManager.setOemDashcam(delta);
            }

            // Explicit restart knob — used by the camera-mapping dialog
            // when the OEM camera id changes. Without this, the live
            // pipeline keeps using the OLD id captured at start() and the
            // user's new pick is silent until next toggle off+on. Always
            // a stop+start cycle so the new HAL id is picked up.
            if (req.optBoolean("restart", false)) {
                // Route through the same single-threaded executor so rapid
                // restart-cfg POSTs serialize cleanly with ordinary trigger
                // recalcs. This also applies when a watchdog has already
                // cleared the pipeline: forceRestart resolves current intent
                // and starts the replacement immediately instead of waiting
                // for the periodic self-heal ticker.
                LIFECYCLE_EXEC.execute(() -> {
                    try {
                        forceRestartPipelineFromCurrentIntent();
                    } catch (Exception e) {
                        logger.warn("OEM dashcam restart failed: " + e.getMessage());
                    }
                });
                com.overdrive.app.daemon.CameraDaemon.log(
                    "OEM Dashcam: restart scheduled on lifecycle executor");
            }

            // Drive the pipeline lifecycle off the persisted state on the
            // dedicated single-threaded executor — pipeline.start() blocks
            // ~2-4s on warmup+camera+EGL, so the HTTP worker can't carry
            // it. The dedup logic ensures rapid POSTs collapse to at most
            // one in-flight + one re-armed run.
            //
            // FIX M3: skip the recalc when no field that affects pipeline
            // state actually changed. The picker UI auto-saves on every
            // keystroke; previously every save scheduled a recalc which
            // queued a no-op applyTriggerLifecycle pass. With the early-out
            // we only recalc on the actually-meaningful transitions.
            String newRec = com.overdrive.app.config.UnifiedConfigManager.getOemRecordingMode();
            String newSurv = com.overdrive.app.config.UnifiedConfigManager.getOemSurveillanceMode();
            JSONObject afterOem = com.overdrive.app.config.UnifiedConfigManager.getOemDashcam();
            String newQuality = afterOem == null ? "" : afterOem.optString("recordingQuality", "");
            String newCodec = afterOem == null ? "" : afterOem.optString("codec", "");
            int newFps = afterOem == null ? 0 : afterOem.optInt("fps", 0);
            boolean modesChanged = !oldRec.equals(newRec) || !oldSurv.equals(newSurv);
            boolean qualityChanged = !oldQuality.equals(newQuality)
                    || !oldCodec.equals(newCodec)
                    || oldFps != newFps;
            // The 'restart' branch above already scheduled an applyLifecycle
            // sequence; we still recalc on mode/quality changes regardless,
            // because the restart path resolves desired state from UCM and
            // benefits from the latest snapshot.
            if (modesChanged || qualityChanged) {
                scheduleLifecycleRecalc();
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("applied", delta);
            response.put("recordingMode",
                com.overdrive.app.config.UnifiedConfigManager.getOemRecordingMode());
            response.put("surveillanceMode",
                com.overdrive.app.config.UnifiedConfigManager.getOemSurveillanceMode());
            response.put("lifecycleInFlight",
                delta.has("recordingMode") || delta.has("surveillanceMode"));
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            err.put("success", false);
            err.put("error", e.getMessage());
            HttpResponse.sendJson(out, err.toString());
        }
    }

    private static void handleStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("package", NATIVE_DVR_PACKAGE);
        response.put("state", currentState());
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleDisable(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("package", NATIVE_DVR_PACKAGE);

        String pre = currentState();
        if ("not_installed".equals(pre)) {
            // Don't pretend to disable something that isn't there. UI hides
            // the card in this case anyway, but a misrouted request still
            // gets a structured answer instead of a confused pm error.
            response.put("success", false);
            response.put("state", "not_installed");
            response.put("error", com.overdrive.app.server.Messages.get("errors.oem_native_dvr_not_installed"));
            HttpResponse.sendJson(out, response.toString());
            return;
        }

        String shellOut = execShell("pm disable-user --user 0 " + NATIVE_DVR_PACKAGE + " 2>&1");
        logger.info("pm disable-user " + NATIVE_DVR_PACKAGE + " → " + shellOut);

        // FIX M5: drop the pm cache so the post-pm currentState() probe re-runs
        // the shell call and sees the just-applied transition.
        invalidatePmCache();
        String post = currentState();
        boolean ok = "disabled".equals(post);

        // Persist preference last so a failed shell doesn't flip the UCM
        // marker. The OTA-survives stickiness path in CameraDaemon reads
        // this flag at boot and re-applies pm disable-user when the pkg has
        // been silently re-enabled (factory reset, OTA, manual `pm enable`).
        // Runs on the HTTP worker thread per memory feedback_no_unified_writes_on_ui_thread.md.
        try {
            JSONObject delta = new JSONObject();
            delta.put("disableNativeDvr", ok);
            com.overdrive.app.config.UnifiedConfigManager.updateSection("oemDashcam", delta);
        } catch (Exception e) {
            logger.warn("Failed to persist oemDashcam.disableNativeDvr: " + e.getMessage());
        }

        response.put("success", ok);
        response.put("state", post);
        if (!ok) response.put("error", com.overdrive.app.server.Messages.get("errors.oem_native_dvr_disable_failed_with_detail", String.valueOf(shellOut)));
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleEnable(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        response.put("package", NATIVE_DVR_PACKAGE);

        String pre = currentState();
        if ("not_installed".equals(pre)) {
            response.put("success", false);
            response.put("state", "not_installed");
            response.put("error", com.overdrive.app.server.Messages.get("errors.oem_native_dvr_not_installed"));
            HttpResponse.sendJson(out, response.toString());
            return;
        }

        String shellOut = execShell("pm enable " + NATIVE_DVR_PACKAGE + " 2>&1");
        logger.info("pm enable " + NATIVE_DVR_PACKAGE + " → " + shellOut);

        // FIX M5: drop the pm cache so the post-pm currentState() probe re-runs
        // the shell call and sees the just-applied transition.
        invalidatePmCache();
        String post = currentState();
        boolean ok = "enabled".equals(post);

        try {
            JSONObject delta = new JSONObject();
            // Mirror the post-state: if pm enable succeeded the user's
            // intent is "don't keep re-disabling", so clear the sticky flag.
            delta.put("disableNativeDvr", !ok);
            com.overdrive.app.config.UnifiedConfigManager.updateSection("oemDashcam", delta);
        } catch (Exception e) {
            logger.warn("Failed to persist oemDashcam.disableNativeDvr: " + e.getMessage());
        }

        response.put("success", ok);
        response.put("state", post);
        if (!ok) response.put("error", com.overdrive.app.server.Messages.get("errors.oem_native_dvr_enable_failed_with_detail", String.valueOf(shellOut)));
        HttpResponse.sendJson(out, response.toString());
    }

    // ==================== Native DVR state probe ====================

    /**
     * Resolve the current state of {@link #NATIVE_DVR_PACKAGE}:
     *   - "enabled"       — installed, not currently disabled
     *   - "disabled"      — installed, in the disabled-user list
     *   - "not_installed" — no entry in `pm list packages -a` at all
     *
     * <p>Order of checks matters: we look at the disabled list first, then
     * the all-packages list. {@code pm list packages -d} only returns
     * disabled packages; an empty result is ambiguous (could be enabled or
     * uninstalled), which is why we follow up with {@code pm list packages -a}
     * to disambiguate.
     */
    public static String currentState() {
        // FIX M5: cache `pm list packages` output for PM_CACHE_TTL_MS. The
        // pm CLI call forks a shell (~30-80 ms on BYD's slow flash on bad
        // days) and the install state of NATIVE_DVR_PACKAGE doesn't change
        // unless the user explicitly toggles it via pm enable / disable-user.
        // We invalidate the cache from those code paths so a freshly-applied
        // toggle's currentState() read after the pm op still sees ground truth.
        String disabled = pmListPackages("-d");
        if (disabled != null && disabled.contains(NATIVE_DVR_PACKAGE)
                && !disabled.contains("NOT_DISABLED")) {
            return "disabled";
        }

        // Not disabled — is it installed at all? -a includes uninstalled-for-user
        // packages so we don't false-positive "not_installed" on a per-user
        // state that pm enable can recover.
        String all = pmListPackages("-a");
        if (all != null && all.contains(NATIVE_DVR_PACKAGE)
                && !all.contains("NOT_INSTALLED")) {
            return "enabled";
        }
        return "not_installed";
    }

    // FIX M5: cached pm list packages outputs, keyed by flag (-d / -a).
    // 10 s TTL — long enough to absorb a UI burst that polls /native-dvr-state
    // on every panel render, short enough that out-of-band pm changes (e.g.
    // user runs `pm enable` from adb manually) reflect within a UI cycle.
    private static volatile long pmCacheAtMs = 0L;
    private static volatile String pmDisabledCached = null;
    private static volatile String pmAllCached = null;
    private static final long PM_CACHE_TTL_MS = 10_000L;

    /** Force the next currentState() call to re-execute the pm CLI. Call after
     *  any code path that mutates pm state for NATIVE_DVR_PACKAGE so we don't
     *  return a stale "enabled" / "disabled" read across the toggle. */
    private static void invalidatePmCache() {
        pmCacheAtMs = 0L;
        pmDisabledCached = null;
        pmAllCached = null;
    }

    /** Returns a cached pm list packages output for the given flag (-d or -a),
     *  re-executing the shell call only when the cache is empty or older than
     *  PM_CACHE_TTL_MS. Both flags share a single timestamp so the disabled +
     *  installed views stay consistent within a single tick. */
    private static String pmListPackages(String flag) {
        long now = System.currentTimeMillis();
        if ((now - pmCacheAtMs) < PM_CACHE_TTL_MS) {
            String cached = "-d".equals(flag) ? pmDisabledCached : pmAllCached;
            if (cached != null) return cached;
        }
        String sentinel = "-d".equals(flag) ? "NOT_DISABLED" : "NOT_INSTALLED";
        String fresh = execShell(
            "pm list packages " + flag + " 2>/dev/null | grep -F " + NATIVE_DVR_PACKAGE
                + " || echo " + sentinel);
        if (fresh == null) fresh = "";
        if ("-d".equals(flag)) {
            pmDisabledCached = fresh;
        } else {
            pmAllCached = fresh;
        }
        pmCacheAtMs = now;
        return fresh;
    }

    /**
     * OTA-survives entry point. Called from {@link CameraDaemon#main} after
     * UCM init — if the user previously disabled the native DVR but a factory
     * reset / OTA / external `pm enable` resurrected it, re-apply the disable
     * silently. No-op when the user never opted in or the package isn't there.
     */
    public static void enforceStickyDisableIfRequested() {
        try {
            JSONObject oem = com.overdrive.app.config.UnifiedConfigManager.getOemDashcam();
            if (!oem.optBoolean("disableNativeDvr", false)) {
                return; // user never opted in
            }
            String state = currentState();
            if ("disabled".equals(state) || "not_installed".equals(state)) {
                return; // nothing to do
            }
            // state == "enabled" but UCM says "stay disabled" — re-apply.
            String result = execShell(
                "pm disable-user --user 0 " + NATIVE_DVR_PACKAGE + " 2>&1");
            logger.info("DVR disable: re-applied after OTA / factory reset / "
                + "external `pm enable` — pm output: " + result);
            // FIX M5: invalidate so the next currentState() probe sees the new state.
            invalidatePmCache();
        } catch (Exception e) {
            logger.warn("DVR sticky-disable check failed: " + e.getMessage());
        }
    }

    // ==================== Internal ====================

    /**
     * Execute a shell command and return trimmed stdout+stderr. Returns
     * empty string on any IO/interrupt error so callers can `.contains()`
     * safely without null-checking. Daemon runs as UID 2000 (shell), so
     * `pm` is on PATH and exec'able without ADB.
     */
    private static String execShell(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            p.waitFor();
            return out.toString().trim();
        } catch (Exception e) {
            logger.warn("execShell failed for '" + cmd + "': " + e.getMessage());
            return "";
        }
    }
}
