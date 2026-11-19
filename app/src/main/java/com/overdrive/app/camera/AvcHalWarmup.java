package com.overdrive.app.camera;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.AccMonitor;

/**
 * Legacy AVC HAL warmup.
 *
 * <p>On older BYD camera stacks, production cold opens launch
 * {@code com.byd.avc}, wait for the HAL to settle, then open our camera. A
 * bounded keep-alive can relaunch that package if it is conclusively absent.
 *
 * <p><b>DiLink 4 is deliberately excluded.</b> DIPlus beta18 does not launch
 * AVC as part of its panorama path. On DI4, {@link #warmupAndWait()} returns
 * immediately and {@link #ensureAvcAlive()} is a presence-only diagnostic:
 * it never starts, stops or props up AVC. DI4 frame recovery belongs to
 * {@link PanoramicCameraGpu}'s callback/rebind/reopen ladder.
 *
 * <p>DiLink 5 also bypasses this legacy facility.
 */
public class AvcHalWarmup {

    private static final String TAG = "AvcHalWarmup";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * True when the active camera mode is "dilink4". DI4 uses this fence to
     * bypass every AVC launch/force-stop path while still allowing a harmless
     * presence probe. A staged mode does not affect the running camera stack.
     */
    private static boolean isDilink4Mode() {
        try {
            return com.overdrive.app.camera.dilink5.DiLink5Platform
                    .isDiLink4Selected();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isDiLink5Selected() {
        return com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
    }

    // Camera2 360 mode never uses the BYD AVC app as a camera-HAL warmup.
    private static boolean isCamera2Selected() {
        return com.overdrive.app.camera.camera2.Camera2Platform.isSelected();
    }

    // BYD100F DiLink 100: AVMCamera streams without the BYD 360 app, so it is never relaunched
    // periodically there (the cold-open warmup still runs as before).
    private static boolean isDiLink100AvmPath() {
        try {
            return com.overdrive.app.camera.DiLink100Platform.isAvmPathActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Time to wait after launching com.byd.avc before opening our camera. */
    private static final long HAL_WARMUP_DELAY_MS = 4000;

    /**
     * A high-level activation may warm AVC immediately before the camera's
     * own cold-open gate runs. Reuse that completed warmup briefly so moving
     * the invariant to the real open boundary does not add a second 4-second
     * delay to existing startup paths.
     */
    private static final long WARMUP_REUSE_WINDOW_MS = 15_000L;

    /**
     * Worst-case watchdog allowance while a cold-open warmup is running:
     * bounded am-start, optional recovery restart, pid probe, and the 4-second
     * HAL settle delay.
     */
    private static final long COLD_OPEN_WARMUP_TIMEOUT_MS = 35_000L;

    /** Interval for keep-alive pokes to prevent system from killing com.byd.avc. */
    private static final long KEEP_ALIVE_INTERVAL_MS = 60_000;

    /**
     * Resolve the package's MAIN/LAUNCHER activity instead of hardcoding a
     * firmware-specific class. Known BYD images expose AutoVideoActivity;
     * older code incorrectly targeted a non-existent .MainActivity.
     */
    private static final String[] AVC_LAUNCH_CMD = new String[]{
        "am", "start",
        "--user", "0",
        "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER",
        "-p", "com.byd.avc",
        // 0x10000000 FLAG_ACTIVITY_NEW_TASK | 0x00010000 FLAG_ACTIVITY_NO_ANIMATION.
        // NOT 0x00020000 — that is FLAG_ACTIVITY_REORDER_TO_FRONT, the OPPOSITE of what this
        // silent warmup wants: it moves an existing com.byd.avc task to the front of its stack,
        // popping the OEM camera UI over whatever the driver is looking at on every keep-alive
        // tick, and with no NO_ANIMATION it does so with the full window animation.
        "-f", "0x10010000"
    };

    private volatile Thread keepAliveThread;
    private boolean keepAliveDesired;
    private long keepAliveGeneration;
    private static final long KEEP_ALIVE_STOP_TIMEOUT_MS = 7000L;
    private static final long KEEP_ALIVE_RETRY_INITIAL_MS = 250L;
    private static final long KEEP_ALIVE_RETRY_MAX_MS = 5000L;
    private long keepAliveRetryDelayMs = KEEP_ALIVE_RETRY_INITIAL_MS;
    private boolean keepAliveHandoffScheduled;
    private Thread keepAliveHandoffOwner;

    /**
     * Tracks consecutive failed launch attempts (instance scope — warmupAndWait
     * + keep-alive ticks). After {@link #LAUNCH_FAILURE_ESCALATE_THRESHOLD}
     * consecutive failures we escalate to a force-stop + restart of
     * com.byd.avc to recover from a wedged HAL co-consumer state.
     *
     * <p>Reset to 0 after a successful launch OR after an escalation runs.
     * Legacy fleet only — dilink4 doesn't launch AVC at all.
     */
    private int consecutiveLaunchFailures = 0;

    /**
     * Counter for static {@link #ensureAvcAlive()} keep-alive callers
     * (AccSentryDaemon, CameraDaemon mode tick). Static because the method
     * is static and may be invoked from contexts that don't own an
     * AvcHalWarmup instance. Same semantics as the instance counter.
     */
    private static int staticConsecutiveLaunchFailures = 0;

    /** Threshold for escalating to force-stop + restart. */
    private static final int LAUNCH_FAILURE_ESCALATE_THRESHOLD = 3;
    private static final long AVC_COMMAND_TIMEOUT_SECONDS = 5L;
    private static final long AVC_PROBE_TIMEOUT_SECONDS = 2L;
    private static final long AVC_ABSENCE_CONFIRM_DELAY_MS = 250L;
    private static final long AVC_POST_LAUNCH_VERIFY_DELAY_MS = 1_000L;
    private static final long AVC_RELAUNCH_BACKOFF_INITIAL_MS =
            KEEP_ALIVE_INTERVAL_MS;
    private static final long AVC_RELAUNCH_BACKOFF_MAX_MS = 15L * 60_000L;
    private static final Object AVC_PROCESS_LANE = new Object();
    private static final Object WARMUP_LANE = new Object();
    private static volatile boolean coldOpenWarmupInProgress;
    private static volatile long lastWarmupCompletedNanos = Long.MIN_VALUE;
    // Frame-dead camera: bring a running com.byd.avc to the front once, parked, at most every
    // 10 min. elapsedRealtime ms; 0 = none. The last launch survives daemon restarts.
    private static final java.util.concurrent.atomic.AtomicLong recoveryLaunchRequestedMs =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private static long lastRecoveryLaunchMs = 0L;            // WARMUP_LANE
    private static boolean lastRecoveryLaunchLoaded = false;  // WARMUP_LANE
    private static final String RECOVERY_LAUNCH_MARKER =
            "/data/local/tmp/overdrive_avc_recovery_launch";
    private static final long RECOVERY_LAUNCH_REQUEST_TTL_MS = 5L * 60_000L;
    private static final long RECOVERY_LAUNCH_MIN_INTERVAL_MS = 10L * 60_000L;
    private static final AvcHalWarmup COLD_OPEN_WARMUP = new AvcHalWarmup();
    private static final java.util.concurrent.ScheduledExecutorService
            KEEP_ALIVE_HANDOFF_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "AvcKeepAliveHandoff");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Minimum interval between consecutive force-stop + restart escalations
     * (audit avc-yield "forceRestartAvc self-evicts legitimate co-consumer").
     * On low-mem-killer pressure, am-start can flake every 180s. Without a
     * cooldown each escalation force-stops com.byd.avc, which cascades into
     * pano onCameraError + full close+reopen. 5min cap keeps recovery
     * possible while preventing churn against a structural flake.
     */
    private static final long FORCE_RESTART_COOLDOWN_MS = 5L * 60_000L;

    /** Last forceRestartAvc time (instance scope). 0 = never. */
    private long lastForceRestartMs = 0L;
    private long nextKeepAliveLaunchAllowedElapsedMs = 0L;
    private long keepAliveLaunchBackoffMs =
            AVC_RELAUNCH_BACKOFF_INITIAL_MS;
    private boolean keepAliveLaunchAwaitingStableTick = false;

    /** Last forceRestartAvcStatic time (static scope). 0 = never. */
    private static volatile long lastForceRestartStaticMs = 0L;
    private static long nextStaticLaunchAllowedElapsedMs = 0L;
    private static long staticLaunchBackoffMs =
            AVC_RELAUNCH_BACKOFF_INITIAL_MS;
    private static boolean staticLaunchAwaitingStableTick = false;

    private enum AvcProbeState {
        RUNNING,
        CONFIRMED_ABSENT,
        UNKNOWN
    }

    private static final class AvcProbeResult {
        final AvcProbeState state;
        final int pid;
        final String detail;

        AvcProbeResult(AvcProbeState state, int pid, String detail) {
            this.state = state;
            this.pid = pid;
            this.detail = detail;
        }

        static AvcProbeResult running(int pid) {
            return new AvcProbeResult(AvcProbeState.RUNNING, pid, "pid=" + pid);
        }

        static AvcProbeResult absent() {
            return new AvcProbeResult(
                    AvcProbeState.CONFIRMED_ABSENT, -1, "pidof exit=1");
        }

        static AvcProbeResult unknown(String detail) {
            return new AvcProbeResult(AvcProbeState.UNKNOWN, -1, detail);
        }
    }

    public AvcHalWarmup() {
    }

    /**
     * A one-use receipt tied to a single ACC camera handoff. Only a completed
     * full HAL settle may populate this receipt; process presence alone cannot.
     * No receipt is used for DiLink 100 cold opens.
     */
    public static final class HandoffSettleReceipt {
        // All accesses are serialized by WARMUP_LANE. Only the ACC handoff's
        // *prewarm worker* can issue; an open or retry can only consume.
        private final AvcHandoffSettlePolicy.Receipt proof =
                new AvcHandoffSettlePolicy.Receipt();
    }

    /** Allocate a receipt before starting the overlapping ACC handoff warmup. */
    public static HandoffSettleReceipt newHandoffSettleReceipt() {
        return new HandoffSettleReceipt();
    }

    // ==================== One-Shot Warmup ====================

    /**
     * Authoritative precondition for creating a new production AVMCamera handle.
     * Every production cold-open boundary calls this method; legacy high-level
     * callers may still call {@link #warmupAndWait()} and are coalesced by the
     * shared warmup lane.
     */
    public static boolean warmupBeforeColdOpen() {
        return COLD_OPEN_WARMUP.warmupAndWait();
    }

    /**
     * Cancellation-aware cold-open boundary. The owner predicate is sampled
     * before every launcher/recovery decision so a superseded camera request
     * cannot perform a late foreground AVC relaunch.
     */
    public static boolean warmupBeforeColdOpen(
            java.util.function.BooleanSupplier stillRequired) {
        return COLD_OPEN_WARMUP.warmupAndWait(stillRequired);
    }

    /**
     * Complete the normal four-second settle in the background while the OEM
     * camera ownership handoff runs. Only this entry point may issue a receipt.
     */
    public static boolean prewarmForAccHandoff(
            java.util.function.BooleanSupplier stillRequired,
            HandoffSettleReceipt receipt) {
        return COLD_OPEN_WARMUP.warmupAndWait(stillRequired, receipt, true);
    }

    /** Consume a completed prewarm, if still valid, at this ACC open only. */
    public static boolean warmupBeforeColdOpen(
            java.util.function.BooleanSupplier stillRequired,
            HandoffSettleReceipt receipt) {
        return COLD_OPEN_WARMUP.warmupAndWait(stillRequired, receipt, false);
    }

    /**
     * A failed ACC vendor open must not inherit the first attempt's cached
     * settle or its one-use receipt. A retry performs a new cold-open settle.
     */
    public static void requireFreshWarmupForAccRetry(HandoffSettleReceipt receipt) {
        synchronized (WARMUP_LANE) {
            if (receipt != null) receipt.proof.retire();
            lastWarmupCompletedNanos = Long.MIN_VALUE;
        }
    }

    /** True only while a legacy AVC cold-open warmup is actively blocking. */
    public static boolean isColdOpenWarmupInProgress() {
        return coldOpenWarmupInProgress;
    }

    /** Watchdog budget for the bounded warmup sequence. */
    public static long coldOpenWarmupTimeoutMs() {
        return COLD_OPEN_WARMUP_TIMEOUT_MS;
    }

    /**
     * Launches com.byd.avc and blocks for HAL_WARMUP_DELAY_MS.
     * Call this BEFORE opening the camera on ACC ON transitions.
     *
     * This is a blocking call — run it on a background thread.
     *
     * @return true if warmup completed, false if interrupted
     */
    public boolean warmupAndWait() {
        return warmupAndWait(() -> true);
    }

    public boolean warmupAndWait(
            java.util.function.BooleanSupplier stillRequired) {
        return warmupAndWait(stillRequired, null, false);
    }

    private boolean warmupAndWait(
            java.util.function.BooleanSupplier stillRequired,
            HandoffSettleReceipt receipt, boolean prewarmWorker) {
        // BYD100F cold open is intentionally unchanged, even if a caller
        // supplies a receipt while swapping modes.
        if (isDiLink100AvmPath()) receipt = null;
        if (isDiLink5Selected()) {
            logger.info("DiLink 5: skipping legacy AVC warmup");
            return true;
        }
        if (isCamera2Selected()) {
            return true;
        }
        boolean dilink4 = isDilink4Mode();
        if (dilink4) {
            // OEM-PARITY: oem does NOT launch com.byd.avc anywhere in its
            // panorama-camera flow. The 4 s blocking sleep plus launching a
            // hardcoded AVC activity was OverDrive-specific and suspected of
            // stealing the HAL's mosaic mode (PANORAMA_OUTPUT_STATE=7).
            // Skip the warmup entirely on dilink4. ensureAvcAlive() may still
            // probe process presence for diagnostics, but it never launches or
            // restarts AVC on this path.
            logger.info("dilink4: skipping warmupAndWait (oem-parity — oem never launches com.byd.avc explicitly)");
            return true;
        }

        synchronized (WARMUP_LANE) {
            if (!isWarmupStillRequired(stillRequired)) {
                logger.info("AVC warmup cancelled before shared lane work");
                return false;
            }
            long nowNanos = System.nanoTime();
            // The normal zero-probe 15 s cache wins first. No new IPC, and
            // there is no additional settle to avoid in this common case.
            long completedNanos = lastWarmupCompletedNanos;
            if (completedNanos != Long.MIN_VALUE) {
                long ageNanos = nowNanos - completedNanos;
                long reuseNanos = java.util.concurrent.TimeUnit.MILLISECONDS
                        .toNanos(WARMUP_REUSE_WINDOW_MS);
                if (ageNanos >= 0 && ageNanos <= reuseNanos) {
                    if (receipt != null && !prewarmWorker) receipt.proof.retire();
                    logger.info("Reusing completed AVC warmup (age="
                        + java.util.concurrent.TimeUnit.NANOSECONDS
                            .toMillis(ageNanos)
                        + "ms)");
                    return true;
                }
            }

            // A fresh, single pre-open pidof is required even with a receipt;
            // use the *same* probe for both receipt validation and the normal
            // launch decision. Rejected/expired receipts cannot cause a third
            // pidof or change the cold-open UNKNOWN -> launch policy.
            final boolean recoveryRequested =
                    isRecoveryLaunchDue(android.os.SystemClock.elapsedRealtime());
            coldOpenWarmupInProgress = true;
            try {
                logger.info("Warming up camera HAL via com.byd.avc (waiting " +
                    HAL_WARMUP_DELAY_MS + "ms)...");
                AvcProbeResult beforeLaunch = probeAvcProcess();
                if (!isWarmupStillRequired(stillRequired)) {
                    logger.info("AVC warmup cancelled after process probe; "
                        + "suppressing launcher Activity");
                    return false;
                }
                // Only the prewarm worker may mint the receipt. On a delayed
                // handoff, the open can spend this proof once without a second
                // four-second wait. A rejected proof falls through unchanged.
                if (receipt != null && !prewarmWorker
                        && receipt.proof.consumeIfEligible(
                                System.nanoTime(), lastWarmupCompletedNanos,
                                beforeLaunch.pid,
                                beforeLaunch.state == AvcProbeState.RUNNING,
                                recoveryLaunchRequestedMs.get() != 0L)) {
                    // startCamera() immediately performs its own boundary check.
                    // Admit that nested check through the ordinary 15 s cache;
                    // retry invalidates it before another vendor open.
                    lastWarmupCompletedNanos = System.nanoTime();
                    logger.info("Reusing completed 4 s AVC settle for the "
                            + "same delayed ACC camera handoff");
                    return true;
                }
                // Re-checked after probe: the car may have left P meanwhile.
                final boolean recoveryLaunch = recoveryRequested && isParkedForRecoveryLaunch();
                boolean launchAttempted = AvcHandoffSettlePolicy.requiresLaunch(
                        recoveryLaunch, beforeLaunch.state == AvcProbeState.RUNNING);
                boolean launched = false;
                if (launchAttempted) {
                    if (beforeLaunch.state == AvcProbeState.UNKNOWN) {
                        // Cold-open is the one load-bearing launch boundary.
                        // Preserve its historical fail-open behavior when
                        // process visibility is unavailable; periodic
                        // watchdogs remain fail-closed on the same evidence.
                        logger.warn("AVC pre-warmup probe inconclusive ("
                                + beforeLaunch.detail
                                + "); retaining explicit cold-open launch");
                    }
                    if (recoveryLaunch && beforeLaunch.state == AvcProbeState.RUNNING) {
                        logger.warn("Camera delivered no frames across reopens — "
                                + "bringing com.byd.avc to the front (" + beforeLaunch.detail + ")");
                    }
                    launched = launchAvc();
                    if (recoveryLaunch && launched) {
                        noteRecoveryLaunch(android.os.SystemClock.elapsedRealtime());
                    }
                } else {
                    logger.info("AVC already running (" + beforeLaunch.detail
                            + "); skipping foreground Activity launch");
                    consecutiveLaunchFailures = 0;
                }
                if (!isWarmupStillRequired(stillRequired)) {
                    logger.info("AVC warmup owner retired after launch decision");
                    return false;
                }

                try {
                    Thread.sleep(HAL_WARMUP_DELAY_MS);
                    if (!isWarmupStillRequired(stillRequired)) {
                        logger.info("AVC warmup cancelled during HAL settle; "
                            + "suppressing recovery relaunch");
                        return false;
                    }
                    AvcProbeResult postWarmup = probeAvcProcess();
                    boolean avcReady =
                            postWarmup.state == AvcProbeState.RUNNING;
                    boolean recoveryRestartAttempted = false;
                    if (avcReady) {
                        consecutiveLaunchFailures = 0;
                    } else if (launchAttempted
                            && postWarmup.state
                                    == AvcProbeState.CONFIRMED_ABSENT) {
                        consecutiveLaunchFailures++;
                        if (consecutiveLaunchFailures
                                >= LAUNCH_FAILURE_ESCALATE_THRESHOLD) {
                            if (!isWarmupStillRequired(stillRequired)) {
                                logger.info("AVC warmup owner retired before "
                                    + "force-restart escalation");
                                return false;
                            }
                            logger.warn("warmupAndWait: "
                                + consecutiveLaunchFailures
                                + " confirmed AVC launch failures — escalating "
                                + "to force-stop+restart");
                            recoveryRestartAttempted =
                                forceRestartAvc(stillRequired);
                            consecutiveLaunchFailures = 0;
                        }
                    }
                    if (recoveryRestartAttempted) {
                        if (!isWarmupStillRequired(stillRequired)) {
                            logger.info("AVC warmup owner retired after "
                                + "recovery relaunch");
                            return false;
                        }
                        // The normal launch path waits before AVMCamera opens;
                        // the escalation must honor the same HAL-settle
                        // invariant after replacing AVC. Opening immediately
                        // after force-stop+restart turns a recovery attempt into
                        // another cold-open/stall/restart cycle.
                        Thread.sleep(HAL_WARMUP_DELAY_MS);
                        if (!isWarmupStillRequired(stillRequired)) {
                            logger.info("AVC warmup cancelled during recovery "
                                + "settle");
                            return false;
                        }
                        postWarmup = probeAvcProcess();
                        avcReady =
                                postWarmup.state == AvcProbeState.RUNNING;
                    }
                    if (!isWarmupStillRequired(stillRequired)) {
                        logger.info("AVC warmup owner retired before completion");
                        return false;
                    }
                    lastWarmupCompletedNanos = System.nanoTime();
                    if (prewarmWorker && receipt != null && avcReady
                            && !recoveryRequested
                            && recoveryLaunchRequestedMs.get() == 0L) {
                        receipt.proof.issueFromCompletedPrewarm(
                                lastWarmupCompletedNanos, postWarmup.pid);
                    }
                    if (avcReady) {
                        logger.info("HAL warmup complete — safe to open camera");
                    } else {
                        logger.warn("HAL warmup delay complete, but com.byd.avc "
                            + "could not be confirmed (" + postWarmup.detail
                            + (launched ? "" : ", launch not confirmed")
                            + ") — proceeding fail-open");
                    }
                    // Preserve the existing fail-open behaviour: a transient
                    // ActivityManager failure must not permanently remove all
                    // camera recording. Cache the completed attempt briefly so
                    // a high-level warmup and its immediate open-boundary check
                    // do not repeat the same failed command and 4-second wait.
                    return true;
                } catch (InterruptedException e) {
                    logger.warn("HAL warmup interrupted");
                    Thread.currentThread().interrupt();
                    return false;
                }
            } finally {
                coldOpenWarmupInProgress = false;
            }
        }
    }

    private static boolean isWarmupStillRequired(
            java.util.function.BooleanSupplier stillRequired) {
        try {
            return stillRequired != null && stillRequired.getAsBoolean();
        } catch (Throwable predicateFailure) {
            logger.warn("AVC warmup owner check failed — cancelling: "
                + predicateFailure.getMessage());
            return false;
        }
    }

    /** Camera proven frame-dead: the next fresh legacy warmup brings com.byd.avc to the front. */
    public static void requestRecoveryLaunch(String reason) {
        long now = Math.max(1L, android.os.SystemClock.elapsedRealtime());
        if (recoveryLaunchRequestedMs.getAndSet(now) == 0L) {
            logger.info("AVC recovery relaunch requested (" + reason + ")");
        }
    }

    /** The camera delivered a frame: drop any pending recovery relaunch. */
    public static void cancelRecoveryLaunch() {
        if (recoveryLaunchRequestedMs.get() != 0L) {
            recoveryLaunchRequestedMs.set(0L);
        }
    }

    /** WARMUP_LANE. A live request, outside the rate limit, with the car parked. */
    private static boolean isRecoveryLaunchDue(long nowMs) {
        long requested = recoveryLaunchRequestedMs.get();
        if (requested == 0L) return false;
        if (nowMs - requested > RECOVERY_LAUNCH_REQUEST_TTL_MS || nowMs < requested) {
            recoveryLaunchRequestedMs.compareAndSet(requested, 0L);
            return false;
        }
        long last = lastRecoveryLaunchMs();
        if (last > 0L && last <= nowMs && nowMs - last < RECOVERY_LAUNCH_MIN_INTERVAL_MS) {
            return false;
        }
        return isParkedForRecoveryLaunch();
    }

    /** WARMUP_LANE. The marker holds "bootId elapsedMs" of the last launch. */
    private static long lastRecoveryLaunchMs() {
        if (!lastRecoveryLaunchLoaded) {
            lastRecoveryLaunchLoaded = true;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(RECOVERY_LAUNCH_MARKER))) {
                String line = reader.readLine();
                String[] parts = line != null ? line.trim().split(" ") : new String[0];
                long stored = parts.length == 2 && parts[0].equals(bootId())
                        ? Long.parseLong(parts[1]) : 0L;
                if (stored > 0L && stored <= android.os.SystemClock.elapsedRealtime()) {
                    lastRecoveryLaunchMs = stored;
                }
            } catch (Exception ignored) {
            }
        }
        return lastRecoveryLaunchMs;
    }

    private static String bootId() {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String id = reader.readLine();
            return id != null && !id.trim().isEmpty() ? id.trim() : "-";
        } catch (Exception e) {
            return "-";
        }
    }

    /** WARMUP_LANE. */
    private static void noteRecoveryLaunch(long nowMs) {
        lastRecoveryLaunchMs = nowMs;
        lastRecoveryLaunchLoaded = true;
        recoveryLaunchRequestedMs.set(0L);
        try (java.io.FileWriter writer = new java.io.FileWriter(RECOVERY_LAUNCH_MARKER, false)) {
            writer.write(bootId() + " " + nowMs);
        } catch (Exception e) {
            logger.warn("AVC recovery marker not written: " + e.getMessage());
        }
    }

    /** ACC known off, or a fresh gear reading of P: never over a reverse or driving view. */
    private static boolean isParkedForRecoveryLaunch() {
        try {
            if (AccMonitor.isAccStateAuthoritative() && !AccMonitor.isAccOn()) return true;
            return com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGearIfFresh()
                    == com.overdrive.app.monitor.GearMonitor.GEAR_P;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== Keep-Alive Watchdog ====================

    /**
     * Starts the 60-second keep-alive watchdog.
     * Periodically re-launches com.byd.avc to prevent the system from killing it.
     *
     * Only runs while ACC is ON and pipeline is active.
     * Call this after the pipeline has started successfully.
     */
    public synchronized void startKeepAlive() {
        if (isDiLink5Selected()) {
            keepAliveDesired = false;
            keepAliveGeneration++;
            if (keepAliveThread != null) keepAliveThread.interrupt();
            return;
        }
        if (isCamera2Selected()) {
            keepAliveDesired = false;
            keepAliveGeneration++;
            if (keepAliveThread != null) keepAliveThread.interrupt();
            return;
        }
        if (isDiLink100AvmPath()) {
            keepAliveDesired = false;
            keepAliveGeneration++;
            if (keepAliveThread != null) keepAliveThread.interrupt();
            return;
        }
        if (keepAliveDesired
                && keepAliveThread != null
                && keepAliveThread.isAlive()) {
            logger.info("Keep-alive already running");
            return;
        }

        keepAliveDesired = true;
        keepAliveGeneration++;
        if (keepAliveThread != null && keepAliveThread.isAlive()) {
            keepAliveThread.interrupt();
            logger.info("Keep-alive restart queued behind prior worker termination");
            return;
        }
        keepAliveThread = null;
        tryStartKeepAliveWorkerLocked(keepAliveGeneration);
    }

    private void tryStartKeepAliveWorkerLocked(final long generation) {
        try {
            startKeepAliveWorkerLocked(generation);
        } catch (Throwable constructionFailure) {
            keepAliveThread = null;
            logger.warn("AVC keep-alive worker construction failed: "
                + constructionFailure.getMessage());
            scheduleKeepAliveHandoffLocked(null);
        }
    }

    private void startKeepAliveWorkerLocked(final long generation) {
        final Thread worker = new Thread(() -> {
            logger.info("AVC keep-alive watchdog started (interval=" +
                KEEP_ALIVE_INTERVAL_MS / 1000 + "s)");

            try {
                while (isKeepAliveWorkerCurrent(
                        Thread.currentThread(), generation)
                        && !isDiLink5Selected()
                        && !Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(KEEP_ALIVE_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (!isKeepAliveWorkerCurrent(
                            Thread.currentThread(), generation)
                            || isDiLink5Selected()) {
                        break;
                    }

                    // OEM-PARITY: on dilink4 do NOT launch com.byd.avc.
                    if (isDilink4Mode()) {
                        try {
                            BydApaViewpointHelper.reassertIfHeld();
                        } catch (Throwable t) {
                            logger.warn("Keep-alive viewpoint re-assert failed: "
                                + t.getMessage());
                        }
                        logger.info("Keep-alive tick (dilink4): skipping AVC re-launch (oem-parity)");
                        continue;
                    }

                    // A missed/throwing teardown must not leave this watchdog
                    // relaunching an Activity after the legacy camera consumer
                    // is gone. Keep the worker dormant instead of terminating
                    // it, so a transient pipeline restart can resume without a
                    // second lifecycle race.
                    if (!isLegacyCameraConsumerActive()) {
                        logger.debug("Keep-alive: no active legacy camera "
                                + "consumer — skipping AVC probe/launch");
                        continue;
                    }

                    AvcProbeResult probe = probeAvcProcess();
                    if (!isKeepAliveWorkerCurrent(
                            Thread.currentThread(), generation)
                            || isDiLink5Selected()) {
                        break;
                    }
                    if (probe.state == AvcProbeState.RUNNING) {
                        if (keepAliveLaunchAwaitingStableTick) {
                            logger.info("Keep-alive: AVC survived the probation "
                                    + "watchdog tick; relaunch backoff reset");
                        }
                        consecutiveLaunchFailures = 0;
                        resetKeepAliveLaunchBackoff();
                        continue;
                    }
                    if (probe.state == AvcProbeState.UNKNOWN) {
                        logger.warn("Keep-alive: AVC probe inconclusive ("
                                + probe.detail + ") — not launching");
                        continue;
                    }
                    if (!confirmAvcAbsent(probe)) {
                        logger.info("Keep-alive: first AVC absence was not "
                                + "confirmed — not launching");
                        continue;
                    }
                    if (!isKeepAliveWorkerCurrent(
                            Thread.currentThread(), generation)
                            || isDiLink5Selected()
                            || !isLegacyCameraConsumerActive()) {
                        logger.info("Keep-alive: camera consumer retired during "
                                + "absence confirmation — suppressing AVC launch");
                        continue;
                    }
                    long nowElapsed =
                            android.os.SystemClock.elapsedRealtime();
                    if (nowElapsed < nextKeepAliveLaunchAllowedElapsedMs) {
                        logger.info("Keep-alive: AVC remains absent; relaunch "
                                + "backoff active for "
                                + (nextKeepAliveLaunchAllowedElapsedMs
                                - nowElapsed) + "ms");
                        continue;
                    }
                    logger.info("Keep-alive: com.byd.avc confirmed absent twice "
                        + "— re-launching (accOn=" + AccMonitor.isAccOn() + ")");
                    keepAliveLaunchAwaitingStableTick = false;
                    boolean launched = launchAvc();
                    AvcProbeResult postLaunch = probeAvcAfterLaunchDelay();
                    if (!isKeepAliveWorkerCurrent(
                            Thread.currentThread(), generation)
                            || isDiLink5Selected()
                            || !isLegacyCameraConsumerActive()) {
                        logger.info("Keep-alive: camera consumer retired during "
                                + "post-launch verification — suppressing "
                                + "recovery escalation");
                        continue;
                    }
                    if (postLaunch.state == AvcProbeState.RUNNING) {
                        consecutiveLaunchFailures = 0;
                        keepAliveLaunchAwaitingStableTick = true;
                        long probationMs = deferNextKeepAliveLaunch();
                        logger.info("Keep-alive: AVC relaunched and is running; "
                                + "requiring one stable watchdog tick before "
                                + "resetting backoff (probation="
                                + probationMs + "ms)");
                    } else {
                        long retryDelayMs = deferNextKeepAliveLaunch();
                        // UNKNOWN is fail-closed: back off, but do not
                        // force-stop a process whose state could not be read.
                        if (postLaunch.state
                                == AvcProbeState.CONFIRMED_ABSENT) {
                            consecutiveLaunchFailures++;
                        }
                        logger.warn("Keep-alive: AVC relaunch was not confirmed ("
                                + postLaunch.detail + "); next retry in "
                                + retryDelayMs + "ms"
                                + (launched ? "" : " (am start failed)"));
                        if (consecutiveLaunchFailures
                                >= LAUNCH_FAILURE_ESCALATE_THRESHOLD) {
                            logger.warn("Keep-alive: " + consecutiveLaunchFailures
                                + " consecutive AVC launch failures — escalating to force-stop+restart");
                            forceRestartAvc(
                                () -> isKeepAliveWorkerCurrent(
                                        Thread.currentThread(), generation)
                                    && !isDiLink5Selected()
                                    && isLegacyCameraConsumerActive());
                            consecutiveLaunchFailures = 0;
                        }
                    }
                }
            } finally {
                logger.info("AVC keep-alive watchdog stopped");
                synchronized (AvcHalWarmup.this) {
                    if (keepAliveThread == Thread.currentThread()) {
                        scheduleKeepAliveHandoffLocked(
                            Thread.currentThread());
                    }
                }
            }
        }, "AvcKeepAlive");

        worker.setDaemon(true);
        keepAliveThread = worker;
        try {
            worker.start();
            keepAliveRetryDelayMs = KEEP_ALIVE_RETRY_INITIAL_MS;
        } catch (Throwable failure) {
            logger.warn("AVC keep-alive worker failed to start: "
                + failure.getMessage());
            if (keepAliveThread == worker) {
                scheduleKeepAliveHandoffLocked(worker);
            }
        }
    }

    private void scheduleKeepAliveHandoffLocked(Thread owner) {
        keepAliveHandoffOwner = owner;
        if (keepAliveHandoffScheduled) {
            return;
        }

        final long delayMs = keepAliveRetryDelayMs;
        keepAliveRetryDelayMs = Math.min(
            keepAliveRetryDelayMs * 2L, KEEP_ALIVE_RETRY_MAX_MS);
        keepAliveHandoffScheduled = true;
        try {
            KEEP_ALIVE_HANDOFF_SCHEDULER.schedule(
                this::completeKeepAliveHandoff,
                delayMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable schedulingFailure) {
            logger.warn("AVC keep-alive handoff scheduling failed: "
                + schedulingFailure.getMessage());
            startKeepAliveHandoffFallbackLocked(delayMs);
        }
    }

    private void startKeepAliveHandoffFallbackLocked(long delayMs) {
        final Thread fallback;
        try {
            fallback = new Thread(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                completeKeepAliveHandoff();
            }, "AvcKeepAliveHandoffFallback");
            fallback.setDaemon(true);
            fallback.start();
        } catch (Throwable fallbackFailure) {
            keepAliveHandoffScheduled = false;
            logger.warn("AVC keep-alive handoff fallback failed to start: "
                + fallbackFailure.getMessage());
        }
    }

    private void completeKeepAliveHandoff() {
        synchronized (this) {
            if (isDiLink5Selected()) {
                keepAliveDesired = false;
                keepAliveGeneration++;
            }
            Thread owner = keepAliveHandoffOwner;
            keepAliveHandoffOwner = null;
            keepAliveHandoffScheduled = false;
            if (owner == null) {
                if (keepAliveDesired && keepAliveThread == null) {
                    tryStartKeepAliveWorkerLocked(keepAliveGeneration);
                }
                return;
            }
            if (keepAliveThread != owner) {
                return;
            }
            if (owner.isAlive()) {
                scheduleKeepAliveHandoffLocked(owner);
                return;
            }

            keepAliveThread = null;
            if (keepAliveDesired) {
                tryStartKeepAliveWorkerLocked(keepAliveGeneration);
            } else {
                keepAliveRetryDelayMs = KEEP_ALIVE_RETRY_INITIAL_MS;
            }
        }
    }

    /**
     * Stops the keep-alive watchdog.
     * Call when pipeline stops, ACC goes OFF, or daemon shuts down.
     */
    public void stopKeepAlive() {
        final Thread target;
        synchronized (this) {
            if (!keepAliveDesired && keepAliveThread == null) return;
            keepAliveDesired = false;
            keepAliveGeneration++;
            keepAliveRetryDelayMs = KEEP_ALIVE_RETRY_INITIAL_MS;
            target = keepAliveThread;
            if (target != null) {
                target.interrupt();
            }
        }

        if (target != null && target != Thread.currentThread()) {
            try {
                target.join(KEEP_ALIVE_STOP_TIMEOUT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (keepAliveThread == target
                    && (target == null || !target.isAlive())) {
                keepAliveThread = null;
            } else if (target != null && target.isAlive()) {
                logger.warn("AVC keep-alive worker still terminating; "
                    + "ownership retained and replacement suppressed");
            }
        }
        logger.info("AVC keep-alive stopped");
    }

    private synchronized boolean isKeepAliveWorkerCurrent(
            Thread worker, long generation) {
        return keepAliveDesired
                && keepAliveGeneration == generation
                && keepAliveThread == worker;
    }

    /**
     * Whether the keep-alive watchdog is currently running.
     */
    public synchronized boolean isActive() {
        return keepAliveDesired
                || keepAliveHandoffScheduled
                || keepAliveHandoffOwner != null
                || (keepAliveThread != null
                    && keepAliveThread.isAlive());
    }

    // ==================== Internal ====================

    /**
     * Launches com.byd.avc via {@code am start} when a cold-open or confirmed
     * absence requires it.
     * Runs as UID 2000 (shell) — has permission to launch activities.
     * {@code FLAG_ACTIVITY_NO_ANIMATION} suppresses the transition animation;
     * it does not make an Activity launch background-only. Callers therefore
     * probe first and use launch backoff rather than re-poking a live task.
     *
     * @return true if {@code am start} exited 0, false on any non-zero exit
     *         OR exception. Used by caller to drive the legacy
     *         force-stop+restart escalation when AVC wedges its HAL
     *         co-consumer state.
     */
    private boolean launchAvc() {
        synchronized (AVC_PROCESS_LANE) {
        if (isDiLink5Selected()) return false;
        // audit avc-yield (round 7, finding stuck-warmup-pins-warmupInFlight):
        // Process.waitFor() with no timeout can block forever if system_server /
        // ActivityManagerService is wedged or binder is back-pressured under
        // memory pressure. Because launchAvc is called inside the warmup
        // worker thread spawned by RecordingModeManager.activateModeWithWarmup,
        // a hung waitFor pins warmupInFlight=true forever (finally never runs)
        // → all subsequent activations / wedge-retries / gear handlers
        // CAS-coalesce and silently succeed-no-op. The only recovery is
        // daemon respawn — exactly the wedge type this audit hunts for.
        // Cap the wait at 5 s and destroyForcibly() on timeout. Worst case:
        // we report a launch failure (caller's existing failure counter
        // triggers the legacy force-restart escalation a few ticks later).
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                logger.warn("am start com.byd.avc did not exit within 5s — "
                    + "destroyForcibly + treating as failure (avoids "
                    + "warmupInFlight pin)");
                try {
                    process.destroyForcibly();
                } catch (Throwable th) {
                    logger.warn("destroyForcibly errored: " + th.getMessage());
                }
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warn("am start com.byd.avc exited with code " + exitCode);
                return false;
            }
            return true;
        } catch (InterruptedException interrupted) {
            terminateProcess(process);
            Thread.currentThread().interrupt();
            logger.warn("AVC launch interrupted");
            return false;
        } catch (Exception e) {
            logger.warn("Failed to launch com.byd.avc: " + e.getMessage());
            if (process != null) {
                try { process.destroyForcibly(); } catch (Throwable ignored) {}
            }
            return false;
        }
        }
    }

    /**
     * LEGACY-ONLY escalation: force-stop com.byd.avc, brief settle, then
     * relaunch. Used when {@link #consecutiveLaunchFailures} crosses the
     * threshold — re-poking a wedged process won't unstick it, but a
     * full kill+respawn forces the system to rebuild the HAL co-consumer
     * registration cleanly.
     *
     * <p>Self-gates on dilink4 because DI4 never owns AVC lifecycle.
     * ensureAvcAlive's dilink4 branch never increments the failure counter,
     * and the guard keeps this helper safe from any context.
     */
    private boolean forceRestartAvc(
            java.util.function.BooleanSupplier stillRequired) {
        synchronized (AVC_PROCESS_LANE) {
        if (isDiLink5Selected()) return false;
        boolean isDilink4 = false;
        try {
            isDilink4 = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
        } catch (Throwable ignored) {}
        if (isDilink4) {
            logger.warn("forceRestartAvc: skipped on dilink4 (AVC lifecycle is not ours)");
            return false;
        }
        if (!isWarmupStillRequired(stillRequired)) {
            logger.info("forceRestartAvc: owner retired before force-stop");
            return false;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long sinceLast = now - lastForceRestartMs;
        if (lastForceRestartMs > 0L && sinceLast < FORCE_RESTART_COOLDOWN_MS) {
            // audit avc-yield: prevent escalation churn under transient
            // low-mem-killer pressure. Force-stopping com.byd.avc cascades
            // into pano onCameraError + close+reopen on the live consumer.
            logger.warn("forceRestartAvc: COOLDOWN — skipped (last escalation "
                + sinceLast + "ms ago, cooldown="
                + FORCE_RESTART_COOLDOWN_MS + "ms)");
            return false;
        }
        lastForceRestartMs = now;
        logger.warn("forceRestartAvc: force-stopping com.byd.avc and restarting");
        Process forceStop = null;
        try {
            forceStop = Runtime.getRuntime().exec(
                new String[]{"sh", "-c", com.overdrive.app.util.ScratchRoot.remap("am force-stop com.byd.avc")});
            if (!forceStop.waitFor(
                    AVC_PROBE_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(forceStop);
                logger.warn("forceRestartAvc: force-stop timed out");
            }
            Thread.sleep(500);
        } catch (Throwable t) {
            terminateProcess(forceStop);
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
            logger.warn("force-stop com.byd.avc failed: " + t.getMessage());
        }
        if (!isWarmupStillRequired(stillRequired)) {
            logger.info("forceRestartAvc: owner retired during force-stop/settle; "
                + "suppressing recovery relaunch");
            return false;
        }
        launchAvc();  // restart
        return true;
        }
    }

    /**
     * Static counterpart of {@link #forceRestartAvc()} — same legacy-only
     * gating + force-stop + relaunch, but reachable from the static
     * {@link #ensureAvcAlive()} keep-alive path.
     */
    private static void forceRestartAvcStatic(
            java.util.function.BooleanSupplier stillRequired) {
        synchronized (AVC_PROCESS_LANE) {
        if (isDiLink5Selected()) return;
        boolean isDilink4 = false;
        try {
            isDilink4 = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
        } catch (Throwable ignored) {}
        if (isDilink4) {
            logger.warn("forceRestartAvcStatic: skipped on dilink4"
                + " (AVC lifecycle is not ours)");
            return;
        }
        if (!isWarmupStillRequired(stillRequired)) {
            logger.info("forceRestartAvcStatic: camera consumer retired before "
                + "force-stop");
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long sinceLast = now - lastForceRestartStaticMs;
        if (lastForceRestartStaticMs > 0L && sinceLast < FORCE_RESTART_COOLDOWN_MS) {
            // audit avc-yield: cooldown gate — see forceRestartAvc().
            logger.warn("forceRestartAvcStatic: COOLDOWN — skipped (last escalation "
                + sinceLast + "ms ago, cooldown="
                + FORCE_RESTART_COOLDOWN_MS + "ms)");
            return;
        }
        lastForceRestartStaticMs = now;
        logger.warn("forceRestartAvcStatic: force-stopping com.byd.avc and restarting");
        Process forceStop = null;
        try {
            forceStop = Runtime.getRuntime().exec(
                new String[]{"sh", "-c", com.overdrive.app.util.ScratchRoot.remap("am force-stop com.byd.avc")});
            if (!forceStop.waitFor(
                    AVC_PROBE_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(forceStop);
                logger.warn("forceRestartAvcStatic: force-stop timed out");
            }
            Thread.sleep(500);
        } catch (Throwable t) {
            terminateProcess(forceStop);
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            logger.warn("force-stop com.byd.avc failed: " + t.getMessage());
        }
        if (!isWarmupStillRequired(stillRequired)) {
            logger.info("forceRestartAvcStatic: camera consumer retired during "
                + "force-stop/settle; suppressing recovery relaunch");
            return;
        }
        Process relaunch = null;
        try {
            relaunch = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            if (!relaunch.waitFor(
                    AVC_COMMAND_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(relaunch);
                logger.warn("forceRestartAvcStatic: relaunch timed out");
                return;
            }
            int rc = relaunch.exitValue();
            if (rc != 0) {
                logger.warn("forceRestartAvcStatic: am start exited rc=" + rc);
            }
        } catch (Exception e) {
            terminateProcess(relaunch);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("forceRestartAvcStatic: relaunch failed: " + e.getMessage());
        }
        }
    }

    // ==================== AVC PROCESS PROBE / LEGACY KEEP-ALIVE ====================
    //
    // Static callers may invoke ensureAvcAlive() from long-running keep-alive
    // ticks. DI4 is presence-check-only for OEM parity: no launch, no relaunch,
    // no force-stop. Legacy modes retain the bounded confirmed-absence relaunch.

    /**
     * Tri-state AVC process probe. Only a completed {@code pidof} with its
     * standard "not found" result (exit 1, empty stdout/stderr) proves absence.
     * Timeouts, execution errors, unexpected exit codes, and malformed output
     * are UNKNOWN and must never authorize a launcher Activity.
     */
    private static AvcProbeResult probeAvcProcess() {
        synchronized (AVC_PROCESS_LANE) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[] { "pidof", "com.byd.avc" });
            if (!p.waitFor(
                    AVC_PROBE_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(p);
                return AvcProbeResult.unknown("pidof timeout");
            }
            int exitCode = p.exitValue();
            String stdout = readProcessOutput(p.getInputStream());
            String stderr = readProcessOutput(p.getErrorStream());
            String trimmed = stdout.trim();
            if (exitCode == 1 && trimmed.isEmpty() && stderr.trim().isEmpty()) {
                return AvcProbeResult.absent();
            }
            if (exitCode != 0 || trimmed.isEmpty()) {
                return AvcProbeResult.unknown(
                        "pidof exit=" + exitCode
                                + (stderr.trim().isEmpty()
                                ? "" : " stderr=" + stderr.trim()));
            }
            String[] parts = trimmed.split("\\s+");
            try {
                int pid = Integer.parseInt(parts[0]);
                return pid > 0
                        ? AvcProbeResult.running(pid)
                        : AvcProbeResult.unknown("pidof returned non-positive pid");
            } catch (NumberFormatException e) {
                return AvcProbeResult.unknown(
                        "pidof malformed output=" + trimmed);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AvcProbeResult.unknown("pidof interrupted");
        } catch (Exception e) {
            return AvcProbeResult.unknown(
                    "pidof failed=" + e.getClass().getSimpleName());
        } finally {
            terminateProcess(p);
        }
        }
    }

    private static boolean confirmAvcAbsent(AvcProbeResult firstProbe) {
        if (firstProbe == null
                || firstProbe.state != AvcProbeState.CONFIRMED_ABSENT) {
            return false;
        }
        try {
            Thread.sleep(AVC_ABSENCE_CONFIRM_DELAY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return probeAvcProcess().state == AvcProbeState.CONFIRMED_ABSENT;
    }

    private static AvcProbeResult probeAvcAfterLaunchDelay() {
        try {
            Thread.sleep(AVC_POST_LAUNCH_VERIFY_DELAY_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AvcProbeResult.unknown("post-launch verification interrupted");
        }
        return probeAvcProcess();
    }

    private static boolean isLegacyCameraConsumerActive() {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            return pipeline != null && pipeline.isRunning();
        } catch (Throwable unavailable) {
            // Ownership uncertainty must never authorize a foreground
            // Activity launch. A live pipeline can tolerate one skipped
            // keep-alive tick and will retry on the next interval.
            logger.warn("Keep-alive: camera consumer state unavailable — "
                    + "failing closed");
            return false;
        }
    }

    private void resetKeepAliveLaunchBackoff() {
        nextKeepAliveLaunchAllowedElapsedMs = 0L;
        keepAliveLaunchBackoffMs = AVC_RELAUNCH_BACKOFF_INITIAL_MS;
        keepAliveLaunchAwaitingStableTick = false;
    }

    private long deferNextKeepAliveLaunch() {
        long delayMs = keepAliveLaunchBackoffMs;
        nextKeepAliveLaunchAllowedElapsedMs =
                android.os.SystemClock.elapsedRealtime() + delayMs;
        keepAliveLaunchBackoffMs = Math.min(
                keepAliveLaunchBackoffMs * 2L,
                AVC_RELAUNCH_BACKOFF_MAX_MS);
        return delayMs;
    }

    private static void resetStaticLaunchBackoff() {
        nextStaticLaunchAllowedElapsedMs = 0L;
        staticLaunchBackoffMs = AVC_RELAUNCH_BACKOFF_INITIAL_MS;
        staticLaunchAwaitingStableTick = false;
    }

    private static long deferNextStaticLaunch() {
        long delayMs = staticLaunchBackoffMs;
        nextStaticLaunchAllowedElapsedMs =
                android.os.SystemClock.elapsedRealtime() + delayMs;
        staticLaunchBackoffMs = Math.min(
                staticLaunchBackoffMs * 2L,
                AVC_RELAUNCH_BACKOFF_MAX_MS);
        return delayMs;
    }

    private static String readProcessOutput(java.io.InputStream stream)
            throws java.io.IOException {
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(stream));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (output.length() > 0) output.append('\n');
            output.append(line);
            // pidof output is tiny. Bound diagnostics defensively in case a
            // vendor wrapper writes an unexpected stream.
            if (output.length() >= 4096) break;
        }
        return output.toString();
    }

    /**
     * Periodic AVC probe/legacy keep-alive. DI4 only reports process presence
     * and always returns false; it never launches AVC. Legacy modes relaunch
     * only after two conclusive absence probes.
     *
     * @return true only when a legacy AVC launch was issued
     */
    public static boolean ensureAvcAlive() {
        if (isDiLink5Selected()) return false;
        if (isCamera2Selected()) return false;
        if (isDiLink100AvmPath()) return false;
        synchronized (AVC_PROCESS_LANE) {
        if (isDiLink5Selected()) return false;
        // OEM-PARITY: oem never launches com.byd.avc. On dilink4 we make
        // this a presence-check-only — no `am start`, no relaunch. If AVC
        // is dead, it's dead; we report state and move on. The HAL on
        // calibrated dilink4 firmware delivers mosaic frames without AVC
        // being a live consumer. Suspect cause of black frames in field
        // logs is exactly the AVC `am start` flipping HAL out of mosaic
        // mode (PANORAMA_OUTPUT_STATE=7).
        if (isDilink4Mode()) {
            AvcProbeResult probe = probeAvcProcess();
            if (probe.state == AvcProbeState.RUNNING) {
                return false;
            }
            logger.info("AVC keep-alive (dilink4): probe=" + probe.state
                    + " — NOT relaunching (oem-parity)");
            return false;
        }

        // Legacy fleet: original behaviour — relaunch if absent.
        AvcProbeResult probe = probeAvcProcess();
        if (probe.state == AvcProbeState.RUNNING) {
            // AVC alive: probe success implies the process is at least
            // running. Reset the failure counter so a transient hiccup
            // doesn't accumulate toward an unrelated future escalation.
            if (staticLaunchAwaitingStableTick) {
                logger.info("AVC keep-alive: process survived the probation "
                        + "caller tick; relaunch backoff reset");
            }
            staticConsecutiveLaunchFailures = 0;
            resetStaticLaunchBackoff();
            return false;
        }
        if (probe.state == AvcProbeState.UNKNOWN) {
            logger.warn("AVC keep-alive: process probe inconclusive ("
                    + probe.detail + ") — not launching");
            return false;
        }
        if (!confirmAvcAbsent(probe)) {
            logger.info("AVC keep-alive: first absence was not confirmed "
                    + "— not launching");
            return false;
        }
        long nowElapsed = android.os.SystemClock.elapsedRealtime();
        if (nowElapsed < nextStaticLaunchAllowedElapsedMs) {
            logger.info("AVC keep-alive: confirmed absent; relaunch backoff "
                    + "active for "
                    + (nextStaticLaunchAllowedElapsedMs - nowElapsed) + "ms");
            return false;
        }
        staticLaunchAwaitingStableTick = false;
        boolean launched = false;
        Process launch = null;
        try {
            launch = Runtime.getRuntime().exec(AVC_LAUNCH_CMD);
            if (!launch.waitFor(
                    AVC_COMMAND_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)) {
                terminateProcess(launch);
                logger.warn("AVC keep-alive: am start timed out");
            } else {
                int rc = launch.exitValue();
                if (rc == 0) {
                    logger.info("AVC keep-alive: re-launched com.byd.avc "
                        + "(was not running)");
                    launched = true;
                } else {
                    logger.warn("AVC keep-alive: am start exited rc=" + rc);
                }
            }
        } catch (InterruptedException interrupted) {
            terminateProcess(launch);
            Thread.currentThread().interrupt();
            logger.warn("AVC keep-alive interrupted");
        } catch (Exception e) {
            terminateProcess(launch);
            logger.warn("AVC keep-alive: " + e.getMessage());
        }

        AvcProbeResult postLaunch = probeAvcAfterLaunchDelay();
        if (postLaunch.state == AvcProbeState.RUNNING) {
            staticConsecutiveLaunchFailures = 0;
            staticLaunchAwaitingStableTick = true;
            long probationMs = deferNextStaticLaunch();
            logger.info("AVC keep-alive: relaunched process is running; "
                    + "requiring one stable caller tick before resetting "
                    + "backoff (probation=" + probationMs + "ms)");
            return true;
        }

        long retryDelayMs = deferNextStaticLaunch();
        if (postLaunch.state == AvcProbeState.CONFIRMED_ABSENT) {
            staticConsecutiveLaunchFailures++;
        }
        logger.warn("AVC keep-alive: relaunch was not confirmed ("
                + postLaunch.detail + "); next retry in "
                + retryDelayMs + "ms"
                + (launched ? "" : " (am start failed)"));
        if (staticConsecutiveLaunchFailures >= LAUNCH_FAILURE_ESCALATE_THRESHOLD) {
            logger.warn("AVC keep-alive: " + staticConsecutiveLaunchFailures
                + " consecutive launch failures — escalating to force-stop+restart");
            forceRestartAvcStatic(
                AvcHalWarmup::isLegacyCameraConsumerActive);
            staticConsecutiveLaunchFailures = 0;
        }
        return false;
        }
    }

    private static void terminateProcess(Process process) {
        if (process == null || !process.isAlive()) return;
        try {
            process.destroy();
            if (!process.waitFor(
                    250L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(
                    250L, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            try { process.destroyForcibly(); } catch (Throwable ignored) {}
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            try { process.destroyForcibly(); } catch (Throwable ignoredAgain) {}
        }
    }
}
