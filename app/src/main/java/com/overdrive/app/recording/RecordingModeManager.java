package com.overdrive.app.recording;

import android.content.Context;

import com.overdrive.app.camera.AvcHalWarmup;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.proximity.ProximityGuardController;
import com.overdrive.app.surveillance.GpuSurveillancePipeline;

import org.json.JSONObject;

/**
 * Recording Mode Manager
 * 
 * Coordinates all recording modes with mutual exclusivity.
 * 
 * Modes:
 * - NONE: No recording, pipeline stopped (DEFAULT)
 * - CONTINUOUS: Always recording when ACC ON
 * - DRIVE_MODE: Recording when in driving gears (D/R/S/M/N), stops in P
 * - PROXIMITY_GUARD: ACC-ON in ALL gears (incl P); low-power pre-record ring + radar-triggered clips
 *
 * Features:
 * - Mutual exclusivity enforcement
 * - Proper cleanup when switching modes
 * - Resource management (stops pipeline when NONE)
 * - Gear state awareness for DRIVE_MODE (PROXIMITY_GUARD ignores gear)
 * - ACC state awareness for CONTINUOUS and PROXIMITY_GUARD modes
 */
public class RecordingModeManager {
    private static final DaemonLogger logger = DaemonLogger.getInstance("RecordingModeManager");
    
    // Gear constants (from BYDAutoGearboxDevice)
    public static final int GEAR_P = 1;
    public static final int GEAR_R = 2;
    public static final int GEAR_N = 3;
    public static final int GEAR_D = 4;
    public static final int GEAR_M = 5;
    public static final int GEAR_S = 6;
    
    /**
     * Recording modes for ACC ON state.
     */
    public enum Mode {
        NONE,            // No recording - saves resources (DEFAULT)
        CONTINUOUS,      // Always recording when ACC ON
        DRIVE_MODE,      // Recording when driving (gear D/R/N/S/M, stops in P)
        PROXIMITY_GUARD  // ACC-ON in ALL gears; low-power ring + record on radar triggers
    }
    
    private final Context context;
    private final GpuSurveillancePipeline pipeline;
    private final ProximityGuardController proximityController;
    private final AvcHalWarmup avcWarmup;
    
    private volatile Mode currentMode = Mode.NONE;  // Default: no recording
    private volatile boolean accIsOn = false;  // Default: ACC OFF — wait for AccSentryDaemon to confirm
    private volatile int currentGear = GEAR_P;  // Default: Park

    // True once we've received at least one real ACC state change IPC (vs. only
    // the constructor's hardware probe). Used to keep the "wasOn=" log field
    // honest: on the first IPC after boot, accIsOn may already be true from the
    // probe, but there was no prior IPC, so reporting "wasOn=true" is misleading.
    private volatile boolean accIpcSeen = false;

    // True if the current mode's pipeline/recording state is actually live.
    // Used to distinguish "ACC is on AND mode is running" from "ACC was set to
    // on but activation failed silently (pipeline init threw, etc.)".
    // Without this, a duplicate-event guard keyed only on accIsOn locks us out
    // of retrying activation on the next ACC ON IPC.
    private volatile boolean modeActive = false;

    // True when the resync ticker has determined the current CONTINUOUS/DRIVE
    // activation is WEDGED (pipeline running + modeActive true, but the encoder
    // never publishes frames / the pending-record prefix is stuck — see
    // pendingPrefixStuck/encoderStalled in the resync tick). modeActive alone
    // can't express this: a stuck activation re-affirms modeActive=true on every
    // wedge-retry, so a status surface gated only on modeActive would paint a
    // false "recording" forever. Exported via isRecordingWedged() so the status
    // overlay / web card can fall back to the RED fault state instead of GREEN.
    // PROXIMITY_GUARD is excluded (it records on triggers, so not-recording is
    // its normal state). Recomputed every resync tick; retains its last value
    // between ticks (slow-moving signal).
    private volatile boolean recordingWedged = false;

    // True while activateMode is bringing up a camera-owning mode (CONTINUOUS /
    // DRIVE_MODE / PROXIMITY_GUARD) — the window between pipeline.start() and
    // modeActive=true. A concurrent BS turn-signal reconcile (turn-tick thread)
    // would otherwise see modeActive==false + bsKeepWarmActive==true and disable
    // the recorder lane / drop fps out from under the activating recorder.
    // reconcileCameraProfileLocked treats this as "recording owns the camera."
    // Set/cleared only inside activateMode (which runs under activationLock).
    private volatile boolean activatingCameraOwner = false;

    public RecordingModeManager(Context context, GpuSurveillancePipeline pipeline) {
        this.context = context;
        this.pipeline = pipeline;
        this.proximityController = new ProximityGuardController(context, pipeline);
        this.avcWarmup = new AvcHalWarmup();

        // Tell the pipeline to consult us before idle-shutdown tears it down.
        // PROXIMITY_GUARD MONITORING needs the pipeline alive even when no
        // recording is in flight — without this hook a 30s WebSocket idle
        // would kill the camera between trigger windows.
        //
        // Blind-spot is an INDEPENDENT keep-warm reason: if BS is enabled it
        // holds the camera/rails warm regardless of recording mode or gear (so
        // the turn-signal blind-spot view is instant), at its OWN low recorder
        // profile (see applyRecorderProfileForState). modeActive being false
        // (e.g. proximity parked in P) must NOT tear the camera down while BS
        // wants it.
        pipeline.setKeepAlivePredicate(() -> {
            if (bsKeepWarmActive()) return true;
            // Camera-view (show-camera overlay) is an independent keep-warm reason, same as
            // blind-spot: while it is active the camera/rails stay up regardless of recording
            // mode or gear so the overlay actually renders frames.
            if (camViewKeepWarmActive()) return true;
            if (!modeActive) return false;
            Mode m = currentMode;
            return m == Mode.CONTINUOUS || m == Mode.DRIVE_MODE || m == Mode.PROXIMITY_GUARD;
        });

        // When the blind-spot view shows/hides (turn signal) AND BS is the sole
        // reason the camera is up, ramp the GLOBAL camera fps: BS active fps on
        // show, BS idle fps on hide. reconcileCameraProfile no-ops the fps change
        // when a recording mode owns the camera (recording fps wins — BS just
        // rides along at the recording rate). Fired off the BS turn-tick thread;
        // reconcileCameraProfile only does cheap volatile/sub-frame writes and is
        // internally guarded, so no lock is needed here.
        pipeline.setBsVisibilityListener(this::reconcileCameraProfile);

        // When live-view streaming turns on/off (incl. WS idle auto-close, which
        // the HTTP handlers don't see), re-reconcile the global camera fps floor
        // so the shared camera tracks max(recording/BS-idle, stream) and drops
        // back when the stream goes away. Idempotent; self-serialized.
        pipeline.setStreamStateListener(this::reconcileCameraProfile);

        // Load persisted mode from config
        loadPersistedMode();

        logger.info("RecordingModeManager initialized: mode=" + currentMode);
        
        // Sync ACC state from AccMonitor if it's already been set by AccSentryDaemon
        boolean monitorAccState = queryAccStateFromHardware();
        if (monitorAccState) {
            accIsOn = true;
            logger.info("ACC state from hardware: ON");
        }

        // Sync gear from GearMonitor if it has already started polling. Without
        // this the field stays at the GEAR_P default, and DRIVE_MODE /
        // PROXIMITY_GUARD auto-activate below silently no-ops if the daemon
        // restarted while the car was already in a driving gear. (GearMonitor
        // is started later in CameraDaemon init, so on cold start this often
        // returns GEAR_P regardless — that's fine; onGearChanged() will
        // activate the mode when GearMonitor delivers its first real gear.)
        try {
            com.overdrive.app.monitor.GearMonitor gm =
                com.overdrive.app.monitor.GearMonitor.getInstance();
            if (gm.isRunning()) {
                int gearNow = gm.getCurrentGear();
                if (gearNow != currentGear) {
                    logger.info("Constructor gear sync from GearMonitor: "
                        + gearToString(currentGear) + " -> " + gearToString(gearNow));
                    currentGear = gearNow;
                }
            }
        } catch (Exception e) {
            logger.debug("Constructor GearMonitor sync skipped: " + e.getMessage());
        }

        // Schedule the auto-activate on a deferred worker rather than
        // running it synchronously here. Reason: the constructor's
        // queryAccStateFromHardware() can return a stale ACC=ON read
        // (HAL hadn't propagated the power-down yet) on the same boot
        // where CameraDaemon's recovery probe queues a pendingAccOff.
        // Synchronous auto-activate races the drain — produces a 2-3s
        // phantom cam_*.mp4 then a tear-down.
        //
        // We now await {@link #bootStableSignal}, which is released by the
        // first ACC IPC (handler at the top of onAccStateChanged) OR by the
        // first GearMonitor delivery (top of onGearChanged), whichever
        // comes first. That way we don't activate against stale state, and
        // we don't sleep longer than necessary on a clean boot. Hard cap is
        // {@link #BOOT_AUTO_ACTIVATE_HARD_CAP_MS} so a daemon that never
        // receives any IPC (e.g. AccSentry crashed) still falls through to
        // the cold-start resync at +8s.
        //
        // CRITICAL: route through activateModeWithWarmup() instead of calling
        // activateMode() directly. After a hard reboot the BYD camera HAL has
        // not been poked by com.byd.avc yet, so opening the camera before
        // warmup leaves it in a wedged state where pipeline.start() fails
        // silently. The result was that CONTINUOUS recording never started
        // until the user cycled ACC OFF → ON (the IPC path runs warmup).
        new Thread(() -> {
            boolean signalled;
            try {
                signalled = bootStableSignal.await(
                    BOOT_AUTO_ACTIVATE_HARD_CAP_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (shuttingDown) {
                logger.info("Boot auto-activate skipped — manager shutting down");
                return;
            }
            // Re-snapshot under the monitor: accIsOn / currentGear / currentMode
            // may have moved since the constructor read them (an ACC IPC may
            // have landed during the wait). The activate path itself runs
            // through runActivateGuarded which re-validates again — so this
            // is a soft-pre-check that just decides whether to spawn the
            // warmup in the first place.
            //
            // One last hardware probe: the latch may have been released by
            // a stale GearMonitor delivery before the AccSentry IPC landed,
            // in which case accIsOn still reflects the constructor's
            // possibly-stale read. Pulling fresh from the bodywork HAL here
            // prevents auto-activate from racing against an ACC OFF that
            // hasn't propagated to the field yet.
            //
            // FIX (rmm: BootAutoActivate post-latch HW probe pinned by slow
            // binder): wrap the HW probe in a Future with 1.5s timeout. If
            // the BYDAuto binder is wedged the probe could otherwise hang
            // past BOOT_AUTO_ACTIVATE_HARD_CAP_MS. On timeout, fall through
            // to the existing accIsOn (which carries the AccMonitor IPC
            // value or constructor probe).
            boolean hwAccPostLatch;
            java.util.concurrent.ExecutorService probeExec =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread th = new Thread(r, "BootAutoActivate-HwProbe");
                    th.setDaemon(true);
                    return th;
                });
            try {
                java.util.concurrent.Future<Boolean> probeFut =
                    probeExec.submit(() -> queryAccStateFromHardware());
                try {
                    hwAccPostLatch = probeFut.get(1500L,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    probeFut.cancel(true);
                    logger.warn("Boot auto-activate HW probe timed out (>1.5s) — "
                        + "falling back to accIsOn=" + accIsOn);
                    hwAccPostLatch = accIsOn;
                } catch (Exception e) {
                    logger.warn("Boot auto-activate HW probe failed: " + e.getMessage()
                        + " — falling back to accIsOn=" + accIsOn);
                    hwAccPostLatch = accIsOn;
                }
            } finally {
                probeExec.shutdownNow();
            }
            Mode targetMode;
            int gearNow;
            boolean accNow;
            synchronized (RecordingModeManager.this) {
                if (hwAccPostLatch != accIsOn) {
                    logger.info("Boot auto-activate: HW probe corrects accIsOn "
                        + accIsOn + " -> " + hwAccPostLatch);
                    accIsOn = hwAccPostLatch;
                }
                targetMode = currentMode;
                gearNow = currentGear;
                accNow = accIsOn;
            }
            if (!accNow) {
                logger.info("Boot auto-activate skipped — ACC OFF (signalled="
                    + signalled + ")");
                return;
            }

            boolean isChargingNow = false;
            try {
                isChargingNow = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
            } catch (Throwable ignored) {}

            if (isChargingNow && (targetMode == Mode.CONTINUOUS || targetMode == Mode.DRIVE_MODE)) {
                logger.info("Boot auto-activate skipped — vehicle is CHARGING (signalled="
                    + signalled + ")");
                return;
            }
            if (targetMode == Mode.CONTINUOUS) {
                logger.info("Boot auto-activate: CONTINUOUS (signalled=" + signalled + ")");
                activateModeWithWarmup(targetMode, "boot-auto-activate");
            } else if (targetMode == Mode.DRIVE_MODE && isDrivingGear(gearNow)) {
                logger.info("Boot auto-activate: DRIVE_MODE (gear=" + gearToString(gearNow)
                    + ", signalled=" + signalled + ")");
                activateModeWithWarmup(targetMode, "boot-auto-activate");
            } else if (targetMode == Mode.PROXIMITY_GUARD) {
                // PROXIMITY_GUARD is ACC-gated in ALL gears (incl P) — the
                // low-power ring stays warm parked so the first event has
                // pre-roll. ACC-on is implied here (boot auto-activate only runs
                // the recording-mode branch when ACC was probed ON).
                logger.info("Boot auto-activate: PROXIMITY_GUARD (gear=" + gearToString(gearNow)
                    + ", signalled=" + signalled + ")");
                activateModeWithWarmup(targetMode, "boot-auto-activate");
            }
        }, "BootAutoActivate").start();

        // Belt-and-suspenders re-sync. Catches all the cold-start failure
        // modes uniformly: GearMonitor not yet running, AccSentryDaemon hasn't
        // pushed initial state yet, pipeline init still in flight, IPC server
        // not yet listening when AccSentryDaemon tried to push, etc. Runs once
        // a few seconds after construction; idempotent if mode is already
        // active (modeActive guard in onAccStateChanged + onGearChanged).
        scheduleColdStartResync();

        // Hardware interlock: listen for charging state to stop driving dashcam immediately
        try {
            com.overdrive.app.monitor.ChargingDetector.getInstance().addFusedStateListener((isCharging, source) -> {
                if (isCharging) {
                    logger.info("Charging started (source=" + source + ") — forcing gear P and stopping driving recording");
                    onGearChanged(com.overdrive.app.monitor.GearMonitor.GEAR_P);
                    synchronized (lifecycleSerializer) {
                        Mode toStop = null;
                        synchronized (RecordingModeManager.this) {
                            if (modeActive && (currentMode == Mode.CONTINUOUS || currentMode == Mode.DRIVE_MODE)) {
                                toStop = currentMode;
                            }
                        }
                        if (toStop != null) {
                            runDeactivateGuarded(toStop);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            logger.warn("Could not register ChargingDetector listener: " + t.getMessage());
        }
    }

    // Hard upper bound on the boot auto-activate wait. The latch is released
    // by the first ACC IPC or first GearMonitor delivery, whichever comes
    // first; this cap is the fallback when neither arrives (e.g., AccSentry
    // crashed AND GearMonitor failed to start). Set to 5s — comfortably above
    // observed AccSentry IPC latency (2-3s) and the cold-start resync's 8s
    // tick that picks up the slack if even this fails.
    private static final long BOOT_AUTO_ACTIVATE_HARD_CAP_MS = 5_000L;

    /**
     * Run a periodic re-sync ticker. Initial cold-start re-sync at +8s
     * catches the case where construction ran before AccSentryDaemon /
     * GearMonitor delivered first state. Subsequent ticks every 30s
     * catch the "pipeline mid-teardown" silent-skip race in
     * activateMode(): if pipeline.start(false) returns while stopping=true
     * (concurrent surveillance teardown or stale-FPS stop), the activation
     * silently sets modeActive=false and the only retry trigger is
     * "next mode trigger" — but for CONTINUOUS, ACC is already ON and
     * gear is irrelevant, so no further trigger fires. The user would
     * have to cycle ACC OFF→ON manually.
     *
     * The periodic resync calls resyncFromHardware which already has a
     * "retry activation if mode set but !modeActive" branch that picks
     * this case up cleanly. Idempotent — modeActive guard prevents
     * redundant activations.
     *
     * Costs at 30s tick:
     *   - 1 reflective hardware-probe call (already in queryAccState path)
     *   - 1 GearMonitor.getCurrentGear() volatile read
     * Negligible.
     */
    private volatile Thread resyncTickerThread;
    private volatile boolean resyncTickerRunning;

    private void scheduleColdStartResync() {
        resyncTickerRunning = true;
        resyncTickerThread = new Thread(() -> {
            // Initial cold-start delay matches construction's warmup-then-
            // activate window (~4s warmup + ~2s pipeline init + slack).
            //
            // FIX (rmm low: ticker silently exits on InterruptedException
            // leaving wedge detection permanently dead until daemon restart).
            // If the interrupt is not paired with a real shutdown signal
            // (resyncTickerRunning=false), keep ticking — clear the
            // interrupted flag and continue the outer loop instead of
            // bare `return`. shutdown() always sets resyncTickerRunning=false
            // BEFORE interrupting, so the post-sleep guard correctly exits
            // on real shutdown.
            try {
                Thread.sleep(COLD_START_RESYNC_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.interrupted();  // clear flag
                if (!resyncTickerRunning) {
                    Thread.currentThread().interrupt();
                    return;
                }
                logger.warn("Cold-start sleep interrupted but ticker still running — continuing");
            }
            if (!resyncTickerRunning) return;
            try {
                resyncFromHardware("cold-start");
            } catch (Exception e) {
                logger.warn("Cold-start re-sync error: " + e.getMessage());
            }

            // Periodic ticker. Catches the silent-skip race between
            // activateMode() and a concurrent pipeline teardown — without
            // this, CONTINUOUS-mode users could see no recording until they
            // cycle ACC OFF→ON. 30s is a balance between recovery latency
            // and overhead; the ticker is mostly no-op (modeActive guard
            // skips when state matches expected).
            while (resyncTickerRunning) {
                try {
                    Thread.sleep(PERIODIC_RESYNC_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.interrupted();  // clear flag
                    if (!resyncTickerRunning) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Spurious interrupt with shutdown not requested — keep
                    // ticking. Without this, an unexpected interrupt would
                    // permanently kill wedge detection.
                    logger.warn("Periodic sleep interrupted but ticker still running — continuing");
                    continue;
                }
                if (!resyncTickerRunning) return;
                try {
                    resyncFromHardware("periodic");
                } catch (Exception e) {
                    logger.warn("Periodic re-sync error: " + e.getMessage());
                }
            }
        }, "RecordingModeResync");
        resyncTickerThread.setDaemon(true);
        resyncTickerThread.start();
    }

    private static final long PERIODIC_RESYNC_INTERVAL_MS = 30_000L;

    // FIX (false-GREEN): how long isRecording() may be true with NO video
    // sample reaching disk before we treat the session as wedged. Must exceed
    // a normal between-segments gap and any brief writer backpressure, but be
    // short enough that a real SD-unmount / ENOSPC stall surfaces as a fault
    // rather than a sticky false-GREEN. 8s: longer than the 5s rotation grace,
    // shorter than the 30s resync tick so a stall is caught on the first tick
    // after it crosses the threshold.
    private static final long DISK_WRITE_STALL_THRESHOLD_MS = 8_000L;

    /**
     * Re-query authoritative ACC + gear from hardware/monitors and re-drive
     * mode activation if state has drifted from what we currently believe.
     * Used both for cold-start re-sync and for any later resync hook.
     */
    public void resyncFromHardware(String reason) {
        // FIX (rmm medium: stuck-warmup watchdog) — if a warmup worker has
        // been pinned for >WARMUP_STUCK_THRESHOLD_MS (e.g. AvcHalWarmup
        // launchAvc blocking forever in Process.waitFor() under
        // system_server flap / package respawn / binder backpressure),
        // force-clear the in-flight flag so the wedge-retry / gear-change /
        // mode-select paths can spawn a fresh warmup. Without this backstop
        // the daemon would have to be restarted to recover. We can't kill
        // the stuck worker thread itself (no safe interrupt path through
        // ProcessBuilder.waitFor()), but releasing the gate unblocks every
        // future activate-with-warmup caller, and the pendingRetrigger
        // backstop in the eventually-arriving stuck worker's finally is
        // benign (pending will already be cleared by the fresh worker).
        long warmupSince = warmupInFlightSinceMs;
        if (warmupInFlight.get() && warmupSince > 0L
            && (System.currentTimeMillis() - warmupSince) > WARMUP_STUCK_THRESHOLD_MS) {
            logger.warn("Stuck warmup detected (in-flight for "
                + (System.currentTimeMillis() - warmupSince) + "ms, threshold "
                + WARMUP_STUCK_THRESHOLD_MS + "ms) — force-clearing warmupInFlight ("
                + reason + ")");
            warmupInFlightSinceMs = 0L;
            warmupInFlight.set(false);
            // Surface a pending re-trigger so the eventually-arriving
            // stuck worker's finally doesn't drop the signal — the new
            // CAS-winner may still want this resync's intent honored.
            warmupPendingRetrigger.set(true);
        }

        boolean hwAcc = queryAccStateFromHardware();
        int hwGear;
        boolean accChanged;
        boolean gearChanged;
        boolean isCharging = false;
        boolean shouldRetryActivation;
        Mode retryMode = null;
        boolean wedgeRetryDriven = false;

        // Try to (re)start GearMonitor before reading. If GearMonitor.start()
        // failed at cold boot (BYD HAL momentarily unavailable, binder service
        // mid-restart, etc.), CameraDaemon's catch only logs — without this
        // resync-driven retry, a hardware-failure-at-boot would leave the
        // gear monitor permanently inert and DRIVE_MODE/PROXIMITY_GUARD
        // would never auto-activate until the user cycled ACC OFF→ON.
        // Done OUTSIDE the manager monitor: GearMonitor.start() can take
        // seconds (reflective HAL bind), and we don't want to pin our
        // monitor across that.
        //
        // Bounded retry: a permanently-broken HAL (BYDAutoGearboxDevice
        // unreachable until reboot) would otherwise log "GearMonitor start
        // retry failed" every 30s = 2880 lines/day. Stop attempting after
        // {@link #GEAR_MONITOR_RETRY_CAP} failures; reset on a successful
        // start so a transient outage that recovers later is still picked up.
        try {
            com.overdrive.app.monitor.GearMonitor gm =
                com.overdrive.app.monitor.GearMonitor.getInstance();
            if (gm.isRunning()) {
                gearMonitorRetryFailures = 0;  // healthy — reset counter
                gearMonitorCapResetCounter = 0;
            } else if (accIsOn && gearMonitorRetryFailures < GEAR_MONITOR_RETRY_CAP) {
                logger.info("GearMonitor not running — attempting start (" + reason
                    + ", attempt " + (gearMonitorRetryFailures + 1)
                    + "/" + GEAR_MONITOR_RETRY_CAP + ")");
                try {
                    gm.start();
                    if (gm.isRunning()) {
                        gearMonitorRetryFailures = 0;
                        gearMonitorCapResetCounter = 0;
                    } else {
                        gearMonitorRetryFailures++;
                    }
                } catch (Exception startErr) {
                    gearMonitorRetryFailures++;
                    logger.warn("GearMonitor start retry failed (" + reason + "): "
                        + startErr.getMessage());
                }
                if (gearMonitorRetryFailures == GEAR_MONITOR_RETRY_CAP) {
                    logger.warn("GearMonitor retry cap reached — suppressing further attempts "
                        + "until next ACC ON cycle or " + GEAR_MONITOR_CAP_RESET_TICKS
                        + "-tick timer reset. HAL is likely permanently unavailable.");
                    gearMonitorCapResetCounter = 0;
                }
            } else if (accIsOn && gearMonitorRetryFailures >= GEAR_MONITOR_RETRY_CAP) {
                // Cap held but ACC still on (e.g., daemon respawned
                // mid-drive — no ACC ON edge will ever fire to reset).
                // Bleed back into retry territory every Nth tick so a
                // transient HAL outage that recovers eventually resumes
                // gear-driven mode activation without forcing a key cycle.
                gearMonitorCapResetCounter++;
                if (gearMonitorCapResetCounter >= GEAR_MONITOR_CAP_RESET_TICKS) {
                    int newFailures = GEAR_MONITOR_RETRY_CAP - 1;
                    logger.info("GearMonitor cap timer fired (" + reason + ") — "
                        + "decrementing retry counter " + gearMonitorRetryFailures
                        + " -> " + newFailures + " to allow one more attempt next tick");
                    gearMonitorRetryFailures = newFailures;
                    gearMonitorCapResetCounter = 0;
                }
            }
        } catch (Exception ignored) {
            // GearMonitor instance unavailable entirely — fall through.
        }

        synchronized (this) {
            hwGear = currentGear;
            try {
                com.overdrive.app.monitor.GearMonitor gm =
                    com.overdrive.app.monitor.GearMonitor.getInstance();
                if (gm.isRunning()) {
                    hwGear = gm.getCurrentGear();
                }
            } catch (Exception ignored) {
                // GearMonitor unavailable — keep our current value
            }

            // Hardware interlock: check if vehicle is charging
            isCharging = false;
            try {
                isCharging = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
            } catch (Throwable ignored) {}

            if (isCharging) {
                hwGear = com.overdrive.app.monitor.GearMonitor.GEAR_P;
            } else if (hwAcc && (hwGear == com.overdrive.app.monitor.GearMonitor.GEAR_P || hwGear == com.overdrive.app.monitor.GearMonitor.GEAR_N)) {
                // Fallback: If ACC is ON and GPS speed >= 3 km/h or moving, promote gear to D
                try {
                    com.overdrive.app.monitor.GpsMonitor gps = com.overdrive.app.monitor.GpsMonitor.getInstance();
                    if (gps != null && (gps.getSpeed() * 3.6f >= 3.0f || gps.isMoving())) {
                        hwGear = com.overdrive.app.monitor.GearMonitor.GEAR_D;
                    }
                } catch (Throwable ignored) {}
            }

            accChanged = hwAcc != accIsOn;
            gearChanged = hwGear != currentGear;
            // Steady-state quiet logging: at 30s ticker interval, an info log
            // every call = 2880 lines/day on a parked car for nothing useful.
            // Log info only when something actually changed or a retry will
            // fire; the no-change case stays at debug.
            //
            // Wedge detection: the !modeActive arm catches activations that
            // never set modeActive=true (pipeline.start() returned with
            // running=false, mid-teardown race, etc.). The recordingHealthy
            // arm catches the inverse — activation set modeActive=true
            // optimistically but the pipeline is no longer actually
            // recording AND no deferred-record path is in flight. Without
            // the second arm, a mid-drive recorder failure leaves
            // modeActive=true forever and the resync ticker is inert.
            // PROXIMITY_GUARD intentionally has no continuous recording
            // (it records on triggers only) so we must NOT mark it wedged
            // simply because pipeline.isRecording()==false.
            boolean recordingHealthy = pipeline.isRunning()
                    && (pipeline.isRecording() || pipeline.getPendingRecordingPrefix() != null);
            // FIX (pipeline: Segment-rotation grace window is plumbed in
            // pipeline but never consumed by RMM wedge ticker): if a segment
            // just rotated (within 5s), the pipeline.isRecording()=false read
            // is the sub-second between-segments flicker on a normal close —
            // not a wedge. Honor the grace window the pipeline already
            // tracks (GpuSurveillancePipeline:131-139, getLastSegmentRotateMs).
            // Without this, a 30s-tick that lands inside the rotation window
            // burns a wedge retry slot AND triggers a stop()+start() cycle
            // that produces a 2-5s recording gap.
            long rotateAgeMs = System.currentTimeMillis() - pipeline.getLastSegmentRotateMs();
            boolean inRotationGrace = rotateAgeMs >= 0L && rotateAgeMs < 5000L;
            // FIX (rmm: wedgeDetected blind to stuck pendingRecordingPrefix):
            // a stuck pendingRecordingPrefix (encoder format never available)
            // would otherwise mask a wedge forever — recordingHealthy=true
            // because pendingPrefix!=null. Track stuck-pending age and after
            // PENDING_PREFIX_STUCK_TICKS ticks (60s at 30s tick), treat as
            // wedged regardless. Self-resets when pendingPrefix clears
            // (encoder format arrived, recording is now actually running).
            String pendingPrefixNow = pipeline.getPendingRecordingPrefix();
            boolean pendingPrefixStuck = false;
            if (pendingPrefixNow != null && pipeline.isRunning() && !pipeline.isRecording()) {
                if (lastObservedPendingPrefix == null
                        || !lastObservedPendingPrefix.equals(pendingPrefixNow)) {
                    lastObservedPendingPrefix = pendingPrefixNow;
                    pendingPrefixStuckTicks = 1;
                } else {
                    pendingPrefixStuckTicks++;
                }
                if (pendingPrefixStuckTicks >= PENDING_PREFIX_STUCK_TICKS) {
                    pendingPrefixStuck = true;
                    logger.warn("Pending recording prefix stuck for "
                        + pendingPrefixStuckTicks + " ticks (~"
                        + (pendingPrefixStuckTicks * PERIODIC_RESYNC_INTERVAL_MS / 1000L)
                        + "s) — treating as wedged regardless of pendingPrefix; "
                        + "prefix=" + pendingPrefixNow);
                }
            } else {
                if (lastObservedPendingPrefix != null) {
                    logger.info("Pending recording prefix cleared (was stuck="
                        + pendingPrefixStuckTicks + " ticks) — wedge tracking reset");
                }
                lastObservedPendingPrefix = null;
                pendingPrefixStuckTicks = 0;
            }
            // FIX (rmm: Wedge ticker blind to encoder hangs that don't surface
            // in isRunning/isRecording). Probe lastEncodedFrameMs — if the
            // encoder hasn't successfully dequeued an output buffer in 15s
            // while we believe modeActive AND we're outside a rotation grace
            // window, that's a wedge regardless of what isRunning/isRecording
            // claim. Hides behind PROXIMITY_GUARD's no-continuous-recording
            // semantics: that mode legitimately goes long stretches with no
            // encoded frames between radar triggers, so don't apply the
            // probe to it.
            long lastEncodedAgeMs = -1L;
            try {
                long lastEncodedMs = pipeline.getLastEncodedFrameMs();
                if (lastEncodedMs > 0L) {
                    lastEncodedAgeMs = System.currentTimeMillis() - lastEncodedMs;
                }
            } catch (Throwable ignored) {
                // Older pipeline build without the accessor — fall through.
            }
            boolean encoderStalled = modeActive
                    && currentMode != Mode.PROXIMITY_GUARD
                    && !inRotationGrace
                    && lastEncodedAgeMs > 15_000L;
            if (encoderStalled) {
                logger.warn("Encoder appears stalled (" + reason + ") — "
                    + lastEncodedAgeMs + "ms since last encoded frame "
                    + "(threshold 15000ms); treating as wedged");
            }
            // FIX (false-GREEN: "REC/MIC green but no video file"): the encoder
            // can be alive (lastEncodedFrameMs fresh, isRecording()==true) while
            // NOTHING reaches disk — SD unmounted mid-segment, ENOSPC, or every
            // muxer write failing below the 5-strike abort. encoderStalled
            // CANNOT see this (the encoder always runs for the pre-record ring),
            // and recordingHealthy is true (isRecording()==true), so without a
            // disk-write probe the pill stays GREEN over a dead writer. Probe
            // the last-disk-write timestamp: if we believe we're recording but
            // no VIDEO sample has been muxed in DISK_WRITE_STALL_THRESHOLD_MS,
            // that's a wedge. Gated on isRecording() (a muxer is supposedly
            // open) and lastDiskWrittenMs>0 (a muxer actually opened) so the
            // deferred/pending window — where isRecording() is false and the
            // pendingPrefixStuck arm already covers it — is not double-counted.
            // PROXIMITY_GUARD excluded (same no-continuous-recording rationale).
            long lastDiskWrittenAgeMs = -1L;
            try {
                long lastDiskMs = pipeline.getLastDiskWrittenMs();
                if (lastDiskMs > 0L) {
                    lastDiskWrittenAgeMs = System.currentTimeMillis() - lastDiskMs;
                }
            } catch (Throwable ignored) {
                // Older pipeline build without the accessor — fall through.
            }
            boolean diskStalled = modeActive
                    && currentMode != Mode.PROXIMITY_GUARD
                    && !inRotationGrace
                    && pipeline.isRecording()
                    && lastDiskWrittenAgeMs > DISK_WRITE_STALL_THRESHOLD_MS;
            if (diskStalled) {
                logger.warn("Recording disk-write stalled (" + reason + ") — "
                    + lastDiskWrittenAgeMs + "ms since last sample written to disk "
                    + "(threshold " + DISK_WRITE_STALL_THRESHOLD_MS + "ms) while "
                    + "isRecording()==true; treating as wedged (likely SD unmount / "
                    + "ENOSPC / write failures — recording is NOT being saved)");
            }
            boolean wedgeDetected = modeActive
                    && (!recordingHealthy || pendingPrefixStuck || encoderStalled || diskStalled)
                    && !inRotationGrace
                    && currentMode != Mode.PROXIMITY_GUARD;
            // Publish the wedge truth for status surfaces (overlay pill / web
            // card). This is the SAME signal that drives the wedge-retry below,
            // so the dashcam indicator can never show a false "recording" while
            // the encoder is structurally stuck. Cleared as soon as the wedge
            // condition no longer holds (recording actually latched, or mode
            // stopped). Don't latch during the rotation grace window — a normal
            // between-segments flicker is not a wedge.
            if (!inRotationGrace) {
                recordingWedged = wedgeDetected;
            }
            if (inRotationGrace && modeActive && !recordingHealthy
                    && currentMode != Mode.PROXIMITY_GUARD) {
                logger.info("Wedge check skipped (" + reason + ") — segment rotated "
                    + rotateAgeMs + "ms ago (within 5s grace window)");
            }

            // FIX (rmm: Resync wedgeDetected has no backoff or per-cycle cap).
            // Gate wedge-driven retries on backoff schedule + per-cycle cap.
            // Non-wedge retries (modeActive=false from cold-start) still fire
            // unthrottled — those are harmless and self-resolve in one tick.
            //
            // FIX (rmm Round 3, Finding 1: Wedge retry per-cycle cap permanently
            // silences self-heal mid-drive). Once cap is reached, fall back to
            // a long fixed post-cap backoff (WEDGE_RETRY_POST_CAP_BACKOFF_MS =
            // 30 min) instead of suppressing forever. Each post-cap attempt
            // updates the next-attempt timestamp WITHOUT incrementing the
            // failure counter — so a transient HAL glitch that resolves later
            // still self-heals during a long drive without an ACC cycle.
            boolean wedgeRetryAllowed = true;
            boolean wedgePostCapAttempt = false;
            if (wedgeDetected) {
                long nowMs = System.currentTimeMillis();
                if (wedgeRetryFailures >= WEDGE_RETRY_MAX_PER_CYCLE) {
                    if (nowMs >= wedgeRetryNextAttemptMs) {
                        // Cap reached but post-cap backoff window has elapsed
                        // — allow one more attempt every 30 min.
                        wedgeRetryAllowed = true;
                        wedgePostCapAttempt = true;
                        logger.warn("Wedge retry post-cap attempt (" + reason
                            + ") — cap " + WEDGE_RETRY_MAX_PER_CYCLE
                            + " was reached but " + (WEDGE_RETRY_POST_CAP_BACKOFF_MS / 60_000L)
                            + "-min backoff elapsed; firing one recovery attempt "
                            + "(failures stay at " + wedgeRetryFailures + ", post-cap retries do NOT increment)");
                    } else {
                        wedgeRetryAllowed = false;
                        long remainingMs = wedgeRetryNextAttemptMs - nowMs;
                        logger.warn("Wedge retry suppressed (" + reason + ") — cap "
                            + WEDGE_RETRY_MAX_PER_CYCLE + " reached, "
                            + remainingMs + "ms until next post-cap recovery attempt. "
                            + "Will reset on next ACC ON edge.");
                    }
                } else if (nowMs < wedgeRetryNextAttemptMs) {
                    wedgeRetryAllowed = false;
                    long remainingMs = wedgeRetryNextAttemptMs - nowMs;
                    logger.info("Wedge retry deferred (" + reason + ") — "
                        + remainingMs + "ms remaining in backoff window "
                        + "(failures=" + wedgeRetryFailures + ")");
                }
            }

            shouldRetryActivation = !accChanged && !gearChanged && accIsOn
                    && (!modeActive || (wedgeDetected && wedgeRetryAllowed))
                    && !isCharging
                    && (currentMode == Mode.CONTINUOUS
                        || (currentMode == Mode.DRIVE_MODE && isDrivingGear(currentGear))
                        // PROXIMITY_GUARD is ACC-gated in ALL gears (incl P) — retry
                        // whenever ACC is on and it's not active, regardless of gear.
                        || currentMode == Mode.PROXIMITY_GUARD);
            // FIX (rmm Round 3, Finding 2): give the wedge budget back when a
            // tick observes healthy recording AND the previous tick was also
            // healthy (or this is the first observation). This way only
            // CONSECUTIVE failures count toward the cap; an isolated transient
            // flicker that the next activate fully resolves doesn't burn a
            // slot. PROXIMITY_GUARD never records continuously, so we only
            // count it as healthy when modeActive=true (it's a control mode,
            // not a recording mode — wedge tracking is suppressed for it
            // upstream by the wedgeDetected gate anyway).
            // Decouple tickHealthy from recordingHealthy alone — when
            // pendingPrefixStuck fires, recordingHealthy is true (because
            // pendingPrefix!=null) but the wedge is real. If we let
            // recordingHealthy drive tickHealthy, the per-cycle wedge cap
            // gets reset on the very tick that detected the stuck-prefix
            // wedge — defeating the cap. Treat a tick as healthy ONLY
            // when there is no stuck-prefix wedge in flight.
            boolean tickHealthy = modeActive
                    && (currentMode == Mode.PROXIMITY_GUARD
                        || (recordingHealthy && !pendingPrefixStuck));
            if (tickHealthy && lastTickWasHealthy
                    && (wedgeRetryFailures > 0 || wedgeRetryNextAttemptMs > 0L)) {
                logger.info("Wedge budget restored (" + reason
                    + ") — two consecutive healthy ticks; resetting failures="
                    + wedgeRetryFailures + " -> 0");
                wedgeRetryFailures = 0;
                wedgeRetryNextAttemptMs = 0L;
            }
            lastTickWasHealthy = tickHealthy;

            if (wedgeDetected && wedgeRetryAllowed) {
                long nextBackoff;
                if (wedgePostCapAttempt) {
                    // Post-cap recovery: stay at cap, schedule next attempt
                    // 30 min out. Do NOT increment wedgeRetryFailures further.
                    nextBackoff = WEDGE_RETRY_POST_CAP_BACKOFF_MS;
                } else {
                    int idx = Math.min(wedgeRetryFailures, WEDGE_RETRY_BACKOFF_MS.length - 1);
                    nextBackoff = WEDGE_RETRY_BACKOFF_MS[idx];
                    wedgeRetryFailures++;
                }
                wedgeRetryNextAttemptMs = System.currentTimeMillis() + nextBackoff;
                logger.warn("Re-sync wedge detected (" + reason + "): mode=" + currentMode
                        + " modeActive=true but pipeline.isRunning=" + pipeline.isRunning()
                        + " isRecording=" + pipeline.isRecording()
                        + " pendingPrefix=" + (pipeline.getPendingRecordingPrefix() != null)
                        + " — forcing re-activation (attempt " + wedgeRetryFailures
                        + "/" + WEDGE_RETRY_MAX_PER_CYCLE
                        + (wedgePostCapAttempt ? " [post-cap]" : "")
                        + ", next-allowed-in=" + nextBackoff + "ms)");
            }
            String resyncMsg = "Re-sync (" + reason + "): hwAcc=" + hwAcc + " accIsOn=" + accIsOn
                + ", hwGear=" + gearToString(hwGear) + " currentGear=" + gearToString(currentGear)
                + ", mode=" + currentMode + ", modeActive=" + modeActive;
            if (accChanged || gearChanged || shouldRetryActivation) {
                logger.info(resyncMsg);
            } else {
                logger.debug(resyncMsg);
            }

            if (shouldRetryActivation) {
                retryMode = currentMode;
                // Mark wedge-driven retries (encoderStalled or pendingPrefixStuck
                // branch) so the call site forces past activateModeWithWarmup's
                // already-active supplier guard. Cold-start retries (modeActive
                // false) leave wedgeRetryDriven=false — the supplier guard is
                // a no-op for them anyway since modeActive=false.
                wedgeRetryDriven = wedgeDetected && wedgeRetryAllowed;
            }
        }

        // onGearChanged / onAccStateChanged take their own synchronized blocks
        // and dispatch I/O outside the manager monitor — call them outside our
        // own synchronized scope so we don't pin the monitor unnecessarily.
        if (gearChanged) {
            onGearChanged(hwGear);
        }
        if (accChanged) {
            // If queryAccStateFromHardware() above handed this exact edge to
            // CameraDaemon's full ACC chain, that chain calls our
            // onAccStateChanged() itself — calling it again here would run the
            // ACC transition twice. For ACC-OFF that is not merely redundant:
            // the chain arms surveillance (pipeline running) and a duplicate
            // local acc-off teardown landing afterwards would stop the pipeline
            // it just armed. Consume the one-shot flag and skip only the
            // matching edge; anything else falls through to normal handling.
            Boolean handedOff = probeEdgeHandedOffToDaemon;
            probeEdgeHandedOffToDaemon = null;
            if (handedOff != null && handedOff.booleanValue() == !hwAcc) {
                logger.info("Re-sync (" + reason + "): ACC edge handed to CameraDaemon "
                    + "ACC chain (it drives our onAccStateChanged) — skipping duplicate "
                    + "local dispatch");
                return;
            }
            onAccStateChanged(hwAcc);
            return;
        }
        // No ACC edge on this tick — drop any stale handoff marker so it can
        // never suppress a genuine future edge.
        probeEdgeHandedOffToDaemon = null;

        // If vehicle is charging, driving recordings must be deactivated
        if (isCharging) {
            Mode deactCharging = null;
            synchronized (this) {
                if (modeActive && (currentMode == Mode.CONTINUOUS || currentMode == Mode.DRIVE_MODE)) {
                    logger.info("Re-sync (" + reason + "): vehicle is CHARGING — deactivating driving recording (" + currentMode + ")");
                    deactCharging = currentMode;
                }
            }
            if (deactCharging != null) {
                runDeactivateGuarded(deactCharging);
            }
        }

        // ACC state unchanged but mode might have failed to start at construction.
        // Retry activation if conditions are met and modeActive is false. Use
        // the warmup path so a retried activation that follows a failed
        // cold-start (camera HAL wedged, pipeline.start() returned with
        // isRunning()==false) actually pokes com.byd.avc this time around.
        if (retryMode != null) {
            logger.info("Re-sync retry: activating " + retryMode
                + (wedgeRetryDriven ? " (wedge-driven, force=true)" : ""));
            activateModeWithWarmup(retryMode, "resync-retry", wedgeRetryDriven);
        }

        // CONVERGENCE BACKSTOP: reconcile the camera profile every tick (cheap —
        // idempotent volatile/sub-frame writes, no-op when the profile already
        // matches; early-returns if the pipeline isn't running). The reactive
        // triggers (BS show/hide, stream enable/disable, lifecycle edges) keep
        // latency low, but they each fire on ONE edge — so any state reached
        // WITHOUT an edge (BS disabled while its view was hidden so no visibility
        // edge fired; an activation rejected after a kept-alive mode switch; a
        // debugPreview pin) would otherwise strand the profile until the next
        // happenstance edge. Driving reconcile from this 30s cadence makes the
        // single desiredCameraState() authority self-healing: the worst-case
        // staleness for any un-triggered profile drift is one tick. This is why
        // the design no longer needs a new special-case patch for each newly
        // discovered no-edge path — the ladder + the tick cover them by
        // construction. (Profile only; teardown stays with the existing owners.)
        try {
            reconcileCameraProfile();
        } catch (Throwable t) {
            logger.warn("Periodic reconcile error: " + t.getMessage());
        }
    }

    /**
     * Force a warmup-routed restart of the current camera-owning mode.
     *
     * <p>Unlike {@link #resyncFromHardware}, this does NOT gate on the
     * disk/encoder wedge heuristics — those are blind to a camera that is
     * delivering ZERO frames while the encoder keeps ticking on its pre-record
     * ring (getLastEncodedFrameMs() advances even with no live frames). This is
     * the escalation entrypoint for PanoramicCameraGpu's zero-frame-reopen loop
     * (CameraYieldListener.onHalRecoveryNeeded): the camera layer has already
     * proven the HAL is wedged (N consecutive reopens, no frames), so we
     * unconditionally route through activateModeWithWarmup(force=true) — full
     * teardown + com.byd.avc warmup + pipeline rebuild, the only sequence
     * observed to recover a wedged AVM HAL co-consumer state.
     *
     * <p>No-op when ACC is off or no camera-owning mode is active (nothing to
     * restart). The warmup worker's own CAS (warmupInFlight) coalesces this
     * against any concurrent activation.
     */
    public void forceWarmupRestart(String reason) {
        final Mode mode;
        final boolean acc;
        synchronized (this) {
            mode = currentMode;
            acc = accIsOn;
        }
        if (!acc || mode == Mode.NONE) {
            logger.info("forceWarmupRestart(" + reason + ") — no-op (accIsOn=" + acc
                + ", mode=" + mode + "); nothing to restart");
            return;
        }
        logger.warn("forceWarmupRestart(" + reason + ") — full teardown + com.byd.avc "
            + "warmup to recover wedged AVM HAL co-consumer state, then re-activate " + mode);
        // CRITICAL: tear the pipeline DOWN first. The wedged-HAL case has the
        // GL pipeline still running (camera dead, encoder ticking on its
        // pre-record ring), so pipeline.isRunning()==true. activateModeWithWarmup's
        // worker SKIPS avcWarmup.warmupAndWait() when the pipeline is already
        // running (RecordingModeManager:1243) — and warmupAndWait (the `am start
        // com.byd.avc` + settle) is the ONLY thing that recovers the wedged AVM
        // HAL co-consumer state. So without an explicit stop() here the
        // "warmup restart" would do a bare recording stop/start on the SAME dead
        // camera and never recover — exactly the bare-reopen loop we are
        // escalating away from. The field recovery that worked was setMode(NONE)
        // -> pipeline.stop() (full GL teardown) -> setMode(CONTINUOUS) (warmup).
        // pipeline.stop() is synchronized + idempotent (no-op if already stopped).
        try {
            pipeline.stop();
            logger.info("forceWarmupRestart: pipeline stopped — warmup will now run on cold open");
        } catch (Throwable t) {
            logger.warn("forceWarmupRestart: pipeline.stop() failed: " + t.getMessage()
                + " — proceeding to warmup-activate anyway");
        }
        activateModeWithWarmup(mode, "force-warmup-" + reason, true);
    }

    /**
     * Force an immediate re-activation of the current recording mode WITHOUT
     * the full pipeline teardown of {@link #forceWarmupRestart} (the camera
     * is healthy here) and WITHOUT {@link #resyncFromHardware}'s wedge
     * gating.
     *
     * <p>Entry point for StorageManager's post-mount migration hook: after it
     * has pushed the external output-dir override and called
     * {@code pipeline.stopRecording()} to finalize an internal-fallback
     * session, the restart must be driven HERE, not left to the resync
     * ticker. The stop it just performed stamps the pipeline's 5s
     * segment-rotation grace window, and the ticker's wedge detector
     * deliberately refuses to fire inside that window
     * ({@code inRotationGrace}) — so an ordinary {@code resyncFromHardware}
     * kick would observe modeActive=true + not-recording + in-grace, do
     * nothing, and leave the restart to a LATER 30s tick (which would then
     * also burn a wedge-budget slot for what is an intentional stop, not a
     * fault).
     *
     * <p>Routes through {@code activateModeWithWarmup(force=true)}: force
     * bypasses only the "already active" supplier short-circuit;
     * {@code activateMode}'s CONTINUOUS/DRIVE_MODE branches then see
     * {@code !pipeline.isNormalRecordingMode()} (the caller just stopped it)
     * and re-issue {@code pipeline.startRecording()}, which consumes the
     * freshly-pushed output-dir override. Warmup itself is skipped while the
     * pipeline is running, and the warmupInFlight CAS coalesces concurrent
     * activations, so the healthy path restarts in well under a second.
     * DRIVE_MODE's driving-gear gate still applies — if the car shifted out
     * of a driving gear meanwhile, the activation correctly declines and the
     * latched override is consumed at the next legitimate start instead.
     *
     * <p>No-op when ACC is off or no mode is configured (nothing to restart).
     */
    public void forceModeReactivation(String reason) {
        final Mode mode;
        final boolean acc;
        synchronized (this) {
            mode = currentMode;
            acc = accIsOn;
        }
        if (!acc || mode == Mode.NONE) {
            logger.info("forceModeReactivation(" + reason + ") — no-op (accIsOn=" + acc
                + ", mode=" + mode + "); nothing to restart");
            return;
        }
        logger.info("forceModeReactivation(" + reason + ") — re-activating " + mode
            + " (force=true, no pipeline teardown)");
        activateModeWithWarmup(mode, reason, true);
    }

    /**
     * Single-flight serializer for {@link #activateMode}/{@link #deactivateMode}.
     * Held INSTEAD of the manager monitor across the heavy I/O inside those
     * methods (camera/encoder init, storage cleanup pre-flight, etc.) so the
     * manager monitor stays free for the resync ticker, gear/ACC IPC handlers,
     * and HTTP introspection endpoints.
     *
     * <p>Why this matters: a single shared cleanupLock in StorageManager once
     * caused the recorder's pre-flight {@code ensureRecordingsSpace(100MB)}
     * to block for ~7 minutes behind the boot startup reap. While that I/O
     * stalled, {@code activateMode} held the manager monitor, which in turn
     * starved the periodic resync ticker — its {@code synchronized}
     * {@code resyncFromHardware} call queued up and the user's drive was
     * silently never recorded. The storage-side fix (per-category locks) is
     * the primary remediation; this monitor split is the belt-and-suspenders
     * counterpart so future I/O stalls in the recording-start path can never
     * starve the rest of the manager.
     *
     * <p>Lock ordering invariant: when both locks are needed, ALWAYS take
     * activationLock OUTER, then the manager monitor INNER. Never the
     * reverse — that would deadlock against {@code activateModeWithWarmup}'s
     * pattern (snapshot under monitor, then I/O under activationLock).
     */
    private final Object activationLock = new Object();

    /**
     * In-flight guard for {@link #activateModeWithWarmup}. The warmup worker
     * sleeps ~4s before activating; the resync ticker fires every 30s and
     * can see modeActive=false (because warmup hasn't finished yet) and
     * spawn a duplicate warmup thread that pays its own 4s sleep. Both
     * serialise on activationLock, so functionally only one wins, but the
     * second pays a wasted 4s warmup before its supplier returns false at
     * runActivateGuarded's "already active" check. Coalesce here.
     */
    private final java.util.concurrent.atomic.AtomicBoolean warmupInFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * FIX (rmm medium: stuck warmup pins warmupInFlight=true → all subsequent
     * activations and resync wedge retries coalesce indefinitely).
     *
     * <p>Set to {@link System#currentTimeMillis()} when warmupInFlight CAS-wins,
     * cleared (=0) when warmupInFlight is cleared in the worker's finally.
     * resyncFromHardware uses this to detect a stuck worker (e.g. AvcHalWarmup
     * blocking forever inside Process.waitFor()) and force-clear the in-flight
     * flag so future activations can spawn a fresh warmup. Without this
     * backstop, a single blocked am-start permanently disables every
     * activate-with-warmup path (gear-change, mode-change, wedge retry, user
     * setMode) until daemon restart.
     */
    private volatile long warmupInFlightSinceMs = 0L;
    /** ~4s warmup + 2s pipeline init + generous slack. Anything beyond this
     * is structurally stuck (AvcHalWarmup launchAvc has no waitFor timeout). */
    private static final long WARMUP_STUCK_THRESHOLD_MS = 30_000L;

    /**
     * Set whenever a {@link #activateModeWithWarmup} call is coalesced
     * because a worker is already in-flight. The in-flight worker checks
     * this in its finally block; if set, it spawns one re-trigger so the
     * newer state (gear change, mode change) eventually drives an
     * activation. Without this, the coalesced trigger would be silently
     * dropped and the user would wait up to 30s for the next resync to
     * pick it up.
     *
     * <p>AtomicBoolean so the read-then-clear in the in-flight worker's
     * finally block is atomic (getAndSet) — without atomicity, a peer that
     * arrives between the worker's read and clear would set pending=true and
     * have its signal silently wiped by the worker's clear. With getAndSet,
     * the worker reads-and-clears in a single CAS-equivalent op, and a peer
     * that loses the race observes the post-clear state and CAS-spawns
     * its own warmup via warmupInFlight instead.
     */
    private final java.util.concurrent.atomic.AtomicBoolean warmupPendingRetrigger =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Number of consecutive failed GearMonitor.start() attempts in
     * {@link #resyncFromHardware}. Capped at {@link #GEAR_MONITOR_RETRY_CAP}
     * to prevent unbounded log spam on a permanently-broken HAL. Reset
     * to 0 on a successful start AND on an ACC ON edge (transient HAL
     * failure during a previous ACC cycle should not block recovery).
     */
    // Volatile because read by HTTP threads via getGearMonitorRetryFailures()
    // for /api/status diagnostics, while writes happen under the manager
    // monitor inside resyncFromHardware. Without volatile, the HTTP reader
    // has no happens-before with the writer and can observe a stale 0
    // forever — defeating the observability the field exists for.
    private volatile int gearMonitorRetryFailures = 0;
    private static final int GEAR_MONITOR_RETRY_CAP = 5;

    /**
     * Tick counter used to slowly bleed back into retry attempts after
     * {@link #gearMonitorRetryFailures} hits the cap. Resets to 0 every
     * time a retry is allowed; advances every resync tick while the cap
     * is held. The cap-only path lights up after a daemon respawn that
     * happened mid-drive (ACC was already ON, no edge-trigger fires) —
     * without this slow re-enabler DRIVE_MODE / PROXIMITY_GUARD would be
     * permanently dead until the user cycled ACC OFF→ON.
     *
     * <p>At 30s tick interval, {@link #GEAR_MONITOR_CAP_RESET_TICKS}=10
     * yields a 5-minute backoff between fresh attempts. Volatile because
     * it shares a reader/writer fence with the cap field; both are
     * written under the manager monitor inside resyncFromHardware.
     */
    private volatile int gearMonitorCapResetCounter = 0;
    private static final int GEAR_MONITOR_CAP_RESET_TICKS = 10;

    /**
     * FIX (rmm: Resync wedgeDetected has no backoff or per-cycle cap).
     *
     * <p>Number of consecutive wedge-driven re-activation attempts inside the
     * current ACC cycle. Reset to 0 on each ACC ON edge in
     * {@link #onAccStateChangedLocked}. Capped at
     * {@link #WEDGE_RETRY_MAX_PER_CYCLE} so a structural wedge can't burn
     * forever — each retry pays a ~4s am-start cost and on the 3rd
     * consecutive can cascade into forceRestartAvc.
     *
     * <p>Backoff schedule (ticks at 30s interval): 60s → 2m → 5m → 5m → 5m
     * → post-cap fixed 5min retries (counter does NOT increment further).
     * Last-attempt timestamp drives the gate so a backoff window persists
     * even when the resync ticker keeps firing.
     */
    private volatile int wedgeRetryFailures = 0;
    private volatile long wedgeRetryNextAttemptMs = 0L;
    /**
     * FIX (rmm Round 3, Finding 2: wedgeRetryFailures increments on every retry
     * attempt, including those that succeed — burns budget on transient flickers).
     *
     * <p>Tracks whether the previous resync tick observed a healthy
     * (modeActive=true AND pipeline running AND pipeline recording-or-pending)
     * state. When two consecutive ticks are healthy, the previous wedge has
     * clearly resolved, so we zero the per-cycle wedge budget — only
     * consecutive failures count toward the cap, transient flickers don't burn
     * slots. ACC ON edge still resets fully.
     */
    private volatile boolean lastTickWasHealthy = false;
    private static final int WEDGE_RETRY_MAX_PER_CYCLE = 6;
    // Backoff schedule indexed by attempt count [0..MAX-1]; ms.
    private static final long[] WEDGE_RETRY_BACKOFF_MS = new long[] {
        0L,           // first wedge: fire immediately
        60_000L,      // 60s
        120_000L,     // 2m
        300_000L,     // 5m
        300_000L,     // 5m
        300_000L      // 5m
    };
    // FIX (rmm: Wedge per-cycle cap can leave structural wedge un-recovered
    // until ACC cycle): instead of permanent suppression after the cap, fall
    // back to a fixed backoff so a transient HAL glitch that resolves
    // later still self-heals during a long drive. ACC ON edge still resets
    // the counter; this just keeps the door cracked for slow recovery.
    //
    // FIX (rmm Round 6: Wedge post-cap backoff was 30 min — long recovery
    // latency for transient HAL recovery mid-drive). Shortened to 5 min so
    // a HAL/encoder flake that self-resolves at minute 20 of a drive is
    // picked up at minute 25 worst-case instead of minute 50. Each post-cap
    // attempt does NOT increment the failure counter, so a permanently
    // wedged system still pays the same fixed cost per attempt (one
    // ~4s warmup) every 5 min — well within budget for a head unit that
    // is only running this code path while ACC is ON.
    private static final long WEDGE_RETRY_POST_CAP_BACKOFF_MS = 5L * 60_000L; // 5 min

    /**
     * FIX (rmm: wedgeDetected blind to stuck pendingRecordingPrefix).
     *
     * <p>Tracks how many consecutive resync ticks have observed a non-null
     * pipeline.getPendingRecordingPrefix() while pipeline.isRunning() AND
     * !pipeline.isRecording(). A stuck encoder-format-not-available state
     * keeps pendingPrefix non-null indefinitely, which would otherwise
     * masquerade as recordingHealthy=true and mask the wedge forever.
     *
     * <p>{@link #lastObservedPendingPrefix} is the prefix string we saw on
     * the previous tick — we only count ticks where the SAME prefix is
     * still pending. A new prefix means a fresh deferred record (encoder
     * format arrived for one segment, deferred again for the next) — that's
     * not stuck, just slow.
     *
     * <p>At {@link #PERIODIC_RESYNC_INTERVAL_MS}=30s tick interval,
     * {@link #PENDING_PREFIX_STUCK_TICKS}=2 yields ~60s tolerance before
     * treating as wedged — comfortably above any normal encoder format
     * latency (~200-500ms) but well under user-perceptible recording gaps.
     */
    private volatile String lastObservedPendingPrefix = null;
    private volatile int pendingPrefixStuckTicks = 0;
    private static final int PENDING_PREFIX_STUCK_TICKS = 2;

    /**
     * Released by the first ACC IPC ({@link #onAccStateChanged}) AND by the
     * first GearMonitor delivery ({@link #onGearChanged}). The boot
     * auto-activate worker awaits this with a hard cap so it doesn't fire
     * until either:
     * <ul>
     *   <li>AccSentry pushes its first ACC state IPC (so we know the daemon-
     *       side hardware probe + pendingAccOff drain has run), OR</li>
     *   <li>GearMonitor delivers its first gear update (so currentGear is no
     *       longer the stale GEAR_P default), OR</li>
     *   <li>{@link #BOOT_AUTO_ACTIVATE_HARD_CAP_MS} has elapsed.</li>
     * </ul>
     * Without this, a fixed delay either (a) too short → HAL-stale-ACC race
     * produces phantom recordings, or (b) too long → user driving when
     * daemon spawns sees an 8s recording gap until the cold-start resync.
     */
    private final java.util.concurrent.CountDownLatch bootStableSignal =
        new java.util.concurrent.CountDownLatch(1);

    /**
     * Serializes setMode / onAccStateChanged so concurrent callers can't
     * interleave their (1) state-snapshot, (2) I/O phases. Without this,
     * two concurrent setMode(A)/setMode(B) calls each see the world in
     * different states, both queue activate calls behind activationLock,
     * and the resulting pipeline state can disagree with currentMode.
     *
     * <p>Held for the entire duration of setMode / onAccStateChanged. The
     * manager monitor is acquired briefly inside for state reads; the
     * activationLock is acquired for the I/O. Holding this lock does NOT
     * pin the manager monitor, so it doesn't starve the resync ticker.
     */
    private final Object lifecycleSerializer = new Object();

    /**
     * Set by {@link #shutdown()} so any peer caller queued on
     * {@link #lifecycleSerializer} or {@link #activationLock} aborts instead
     * of resurrecting the just-torn-down pipeline. Volatile so the queued
     * worker observes the flag the moment shutdown sets it.
     */
    private volatile boolean shuttingDown = false;

    /**
     * Run {@code activateMode(mode)} under {@link #activationLock} with a
     * post-acquire re-validation under the manager monitor. The re-validation
     * catches the case where the gating condition changed between the
     * caller's snapshot and our acquisition of activationLock — e.g., the
     * caller saw {@code accIsOn=true}, released the monitor, an ACC OFF IPC
     * fired and stopped the pipeline via the OFF path, then we'd reacquire
     * activationLock and start a recording with ACC physically off.
     *
     * <p>{@code revalidate} returns {@code true} when the activation should
     * still proceed. If it returns {@code false}, we no-op and log.
     */
    private void runActivateGuarded(Mode mode, String reason,
                                    java.util.function.Supplier<Boolean> revalidate) {
        if (shuttingDown) {
            logger.info("Activation aborted (" + reason + ") — manager is shutting down");
            return;
        }
        synchronized (activationLock) {
            if (shuttingDown) {
                logger.info("Activation aborted (" + reason + ") — manager is shutting down");
                return;
            }
            boolean stillValid;
            synchronized (this) {
                stillValid = revalidate.get();
            }
            if (!stillValid) {
                // Re-snapshot under the monitor JUST for logging — keeps
                // the success path off the string-concat hot path. The
                // values may have moved since the supplier evaluation but
                // they're still informative ("here's roughly where we were
                // when we aborted").
                String snapshot;
                synchronized (this) {
                    snapshot = "mode=" + currentMode + " accIsOn=" + accIsOn
                        + " gear=" + gearToString(currentGear) + " modeActive=" + modeActive;
                }
                logger.info("Activation aborted (" + reason + ", target=" + mode
                    + ") — gating state changed after monitor release: " + snapshot);
                // A rejected activation can leave the pipeline running with NO
                // owner — e.g. a mode switch kept it alive (nextModeWillOwnCamera)
                // expecting this activate to take over, but the gate moved (gear
                // shifted out of the driving range in the warmup window). Without
                // a reconcile the camera would sit at whatever profile the
                // previous state left (possibly recorder lane ON at full fps with
                // nothing recording). reconcile lands it in the correct regime
                // (no-owner baseline, or BS-only if BS is keeping it warm).
                reconcileCameraProfile();
                return;
            }
            activateMode(mode);
        }
    }

    /**
     * Run {@code deactivateMode(mode)} under {@link #activationLock}.
     * No re-validation needed today (deactivate is idempotent —
     * pipeline.stopRecording / pipeline.stop are safe to call repeatedly),
     * but we still skip when {@code shuttingDown} is set so future
     * non-idempotent additions can't silently bypass the abort gate.
     * Centralised so future tooling (timing, audit logs) lives in one place.
     */
    private void runDeactivateGuarded(Mode mode) {
        if (shuttingDown) {
            // shutdown() does its own teardown directly; peer deactivates
            // queued behind us are redundant.
            return;
        }
        synchronized (activationLock) {
            if (shuttingDown) return;
            deactivateMode(mode);
        }
    }

    /**
     * Run the AVC HAL warmup on a background thread, then call
     * {@link #activateMode} under {@link #activationLock} (NOT the manager
     * monitor). Mirrors the warmup-then-activate path used by
     * onAccStateChanged() so cold-start auto-activation doesn't race with
     * com.byd.avc's HAL initialization.
     *
     * <p>Skips warmup if the pipeline is already running (camera is open, no
     * need to poke com.byd.avc) — same heuristic the IPC path uses.
     */
    private void activateModeWithWarmup(final Mode mode, final String reason) {
        activateModeWithWarmup(mode, reason, false);
    }

    /**
     * Force-aware overload. When {@code force=true}, the warmup worker's
     * "already active" short-circuit at the modeActive/isRunning/
     * isNormalRecordingMode supplier check is bypassed so the supplier
     * returns true and activateMode() runs — which internally cycles
     * pipeline.stopRecording() + pipeline.startRecording() and unwedges
     * a stalled encoder or stuck pendingRecordingPrefix. Used by
     * {@link #resyncFromHardware} when wedgeDetected.
     *
     * <p>The CAS gate (warmupInFlight) and retrigger logic are unchanged;
     * only the supplier's already-active short-circuit honours force.
     */
    private void activateModeWithWarmup(final Mode mode, final String reason, final boolean force) {
        if (mode == Mode.NONE) {
            return;
        }
        if (shuttingDown) {
            logger.info("Skipping warmup (" + reason + ") — manager is shutting down");
            return;
        }
        // Coalesce duplicate warmups. If a worker is already in its 4s
        // sleep (e.g., from a prior gear-change activate), spawning a
        // second one means both pay the warmup cost, both serialise on
        // activationLock, and the second's runActivateGuarded supplier
        // returns false at the "already active" check — wasted work. The
        // CAS gate skips spawning when one is already in flight.
        //
        // Setting warmupPendingRetrigger tells the in-flight worker to
        // re-spawn after it finishes, so a coalesced trigger with newer
        // state (e.g., user shifted gear during a slow warmup) is not
        // silently dropped — it just gets serialised behind the current
        // attempt instead of paralleling it.
        if (!warmupInFlight.compareAndSet(false, true)) {
            warmupPendingRetrigger.set(true);
            logger.debug("Warmup already in flight — coalescing + scheduling re-trigger ("
                + reason + ")");
            return;
        }
        // FIX (rmm medium: stuck-warmup watchdog) — record CAS-win time so
        // resyncFromHardware can detect a permanently-stuck worker and
        // force-clear the in-flight flag.
        warmupInFlightSinceMs = System.currentTimeMillis();
        // Don't clear warmupPendingRetrigger here. A peer trigger arriving
        // between our CAS-win above and any clear here would see
        // inFlight=true, set pending=true, and a clear at this point would
        // silently wipe the peer's signal. The previous cycle's finally
        // already cleared pending under inFlight=true (see "shouldRetrigger"
        // logic below), so on first-ever entry the field is already false
        // (initial value), and on re-entry it's either still cleared or
        // genuinely set by a peer that won the race against the previous
        // finally — in either case we want to leave it alone.
        new Thread(() -> {
            try {
                // Only warmup if pipeline isn't already running.
                if (!pipeline.isRunning()) {
                    if (!avcWarmup.warmupAndWait()) {
                        logger.warn("AVC warmup interrupted (" + reason + ") — skipping mode activation");
                        return;
                    }
                }

                // Route through runActivateGuarded so the same re-validation +
                // shuttingDown check that protects setMode/onAccStateChanged
                // also protects this path. Without it, a peer setMode(NONE)
                // (or shutdown()) that lands during the 4s warmup sleep would
                // be ignored — the worker would resurrect the user-cancelled
                // mode after warmup completes.
                runActivateGuarded(mode, "warmup-" + reason, () -> {
                if (!accIsOn) {
                    logger.info("ACC turned OFF during warmup (" + reason + ") — skipping mode activation");
                    return false;
                }
                if (currentMode != mode) {
                    logger.info("Mode changed during warmup (" + currentMode + " != " + mode
                        + ", " + reason + ") — skipping mode activation");
                    return false;
                }
                int gearNow = currentGear;
                if (mode == Mode.DRIVE_MODE && !isDrivingGear(gearNow)) {
                    logger.info("DRIVE_MODE waiting for driving gear (current="
                        + gearToString(gearNow) + ") — " + reason);
                    return false;
                }
                // PROXIMITY_GUARD: no gear gate — ACC-on in ALL gears (incl P).
                // (Previously returned false when gear==P; removed so the
                // pre-record ring is warmed while parked, giving the first event
                // pre-roll. ACC re-validation below still applies.)
                if (!force && modeActive && pipeline.isRunning() && pipeline.isNormalRecordingMode()) {
                    logger.info("Mode " + mode + " already active — skipping re-activation ("
                        + reason + ")");
                    return false;
                }
                if (force && modeActive && pipeline.isRunning() && pipeline.isNormalRecordingMode()) {
                    logger.info("Wedge recovery: forcing activation despite modeActive — reason=" + reason);
                }
                return true;
            });
            } finally {
                // FIX (rmm Round 6: warmupPendingRetrigger signal can be
                // silently dropped between getAndSet and warmupInFlight.set(false)).
                // Reordered: clear warmupInFlight FIRST so any peer that lands
                // between the read-and-clear of pending and the inFlight
                // clear (the previous race window) CAS-succeeds on inFlight
                // and starts its OWN fresh warmup directly — no signal
                // dropped. Then read-and-clear pending. As a belt-and-
                // suspenders backstop, re-check pending one more time after
                // the read to catch a peer that landed in the same instant.
                //
                // Note: the previous concern was that a peer landing AFTER
                // we cleared pending but BEFORE we cleared inFlight would
                // see inFlight=true, set pending=true, and have it wiped by
                // our clear. The reordering eliminates that window: if a
                // peer lands in that order now, it sees inFlight=false
                // (already cleared), CAS-succeeds, and runs its own warmup.
                //
                // Always clear the in-flight flag so a later trigger (gear
                // change, mode change, resync retry) can spawn a new
                // warmup. Done in finally so a thrown exception or an
                // early return from runActivateGuarded doesn't leak the
                // flag and lock out future activations forever.
                // FIX (rmm medium: stuck-warmup watchdog) — clear the
                // since-time alongside the in-flight flag so a fresh
                // CAS-winner is correctly tracked.
                warmupInFlightSinceMs = 0L;
                warmupInFlight.set(false);

                // Atomic read+clear of pending. Combined with the post-check
                // below, this honors any signal a peer left for us.
                boolean pendingNow = warmupPendingRetrigger.getAndSet(false);
                // Backstop: a peer that called activateModeWithWarmup AFTER
                // our inFlight clear above will have CAS-won inFlight on its
                // own and started a fresh warmup — no need for us to
                // retrigger. But a peer that called BETWEEN the inFlight
                // clear and the pending getAndSet is the rare residual race;
                // re-check once more.
                if (!pendingNow) {
                    pendingNow = warmupPendingRetrigger.getAndSet(false);
                    if (pendingNow) {
                        logger.debug("Backstop pending re-check picked up a late peer signal");
                    }
                }
                boolean shouldRetrigger = pendingNow && !shuttingDown;

                // Re-spawn ONE recovery via resyncFromHardware so the
                // newer state (gear change, ACC change, mode change) is
                // read FRESH from authoritative sources. Previously we
                // re-armed activateModeWithWarmup(mode,...) using the
                // captured mode parameter — but if the user switched mode
                // (e.g., CONTINUOUS -> DRIVE_MODE) during the in-flight
                // warmup, the retrigger would activate the stale captured
                // mode and runActivateGuarded's supplier would reject it
                // (currentMode != mode), silently losing the user-visible
                // edge. resyncFromHardware reads currentMode/accIsOn/gear
                // freshly under the manager monitor and dispatches whatever
                // is currently appropriate.
                //
                // The recursion is bounded: each cycle reads-and-clears
                // pending atomically under in-flight=true, so any peer
                // landing during the new cycle's warmup gets queued for
                // the cycle AFTER. Worst case under sustained flapping:
                // one warmup running, one queued. The 30s resync ticker
                // is the eventual backstop for any missed transition.
                if (shouldRetrigger) {
                    logger.info("Coalesced trigger — re-driving via resyncFromHardware "
                        + "(reads fresh state instead of stale mode=" + mode + ")");
                    new Thread(() -> {
                        try {
                            resyncFromHardware("warmup-retrigger-" + reason);
                        } catch (Exception e) {
                            logger.warn("Warmup retrigger resync failed: " + e.getMessage());
                        }
                    }, "WarmupRetriggerResync-" + reason).start();
                }
            }
        }, "ModeWarmup-" + reason).start();
    }

    // 8s gives the constructor's warmup-then-activate (≈4s warmup + ~2s pipeline
    // init) time to finish before the resync second-guesses it. With the prior
    // 5s value the resync would frequently fire while warmup was still sleeping,
    // see modeActive=false, and queue a redundant retry.
    private static final long COLD_START_RESYNC_DELAY_MS = 8_000L;
    
    /**
     * Set recording mode.
     * Enforces mutual exclusivity by deactivating current mode before activating new.
     *
     * <p>Held under {@link #lifecycleSerializer} for the duration so concurrent
     * setMode/onAccStateChanged calls can't interleave their snapshot+I/O
     * phases. Decisions (gear/ACC sync, mode transition, persistence) run
     * under the manager monitor; the actual {@link #deactivateMode}/
     * {@link #activateMode} I/O runs under {@link #activationLock} with a
     * post-acquire re-validation so a state change between the snapshot and
     * the I/O (e.g., ACC OFF arriving on a sibling thread) is honored.
     */
    public void setMode(Mode mode) {
        synchronized (lifecycleSerializer) {
            Mode oldMode;
            final Mode targetMode = mode;
            boolean shouldActivate;
            // FIX (rmm Round 6: setMode same-mode early return blocks user-
            // initiated recovery when modeActive=false but currentMode unchanged).
            // If user re-selects the same mode while ACC is on but the mode is
            // not active OR pipeline is wedged (running but not recording with
            // no pending), treat the re-select as an explicit user recovery
            // request: skip the deactivate/persist work but re-run activation,
            // and reset the wedge backoff so the user's manual click is
            // honored immediately rather than deferred up to 5 min by the
            // post-cap backoff schedule.
            boolean userRecoveryReselect = false;
            synchronized (this) {
                if (mode == currentMode) {
                    boolean recordingHealthy = pipeline.isRunning()
                            && (pipeline.isRecording()
                                || pipeline.getPendingRecordingPrefix() != null);
                    boolean wedgedOrInactive = accIsOn && mode != Mode.NONE
                            && (!modeActive
                                || (mode != Mode.PROXIMITY_GUARD && !recordingHealthy));
                    if (!wedgedOrInactive) {
                        logger.debug("Mode already set to: " + mode);
                        return;
                    }
                    logger.info("Mode already set to " + mode + " but state looks wedged "
                        + "(modeActive=" + modeActive
                        + ", running=" + pipeline.isRunning()
                        + ", recording=" + pipeline.isRecording()
                        + ", pendingPrefix=" + (pipeline.getPendingRecordingPrefix() != null)
                        + ") — treating user re-select as recovery trigger");
                    userRecoveryReselect = true;
                    if (wedgeRetryFailures > 0 || wedgeRetryNextAttemptMs > 0L) {
                        logger.info("User re-select — clearing wedge retry counter (was "
                            + wedgeRetryFailures + ") and backoff window");
                        wedgeRetryFailures = 0;
                        wedgeRetryNextAttemptMs = 0L;
                    }
                }
                if (userRecoveryReselect) {
                    // FIX (rmm Round 8 low: queryAccStateFromHardware called
                    // under manager monitor in setMode userRecoveryReselect
                    // path). queryAccStateFromHardware does reflective IPC to
                    // BYDAutoBodyworkDevice.getPowerLevel() with a write-through
                    // to AccMonitor.setAccState; if the BYD binder is wedged,
                    // the manager monitor was previously pinned for the
                    // duration of the binder hang. Mirrors the
                    // lifecycleSerializer/manager-monitor split already used
                    // in onAccStateChangedLocked / setMode normal-path: drop
                    // the manager monitor, do the HW probe + gear sync
                    // unlocked, then jump straight to the warmup helper (also
                    // unlocked — activateModeWithWarmup takes its own monitor
                    // briefly inside). lifecycleSerializer still serialises
                    // against peer setMode/onAccStateChanged invocations.
                    logger.info("Re-select recovery — exiting manager monitor before HW probe");
                    // Fall through past the inner synchronized(this) close.
                    // userRecoveryReselect-specific recovery runs below.
                }
            }
            if (userRecoveryReselect) {
                // Sync hardware-authoritative ACC unlocked.
                boolean actualAccState = queryAccStateFromHardware();
                boolean shouldActivateReselect;
                synchronized (this) {
                    if (actualAccState != accIsOn) {
                        logger.info("Re-select syncing ACC state: " + accIsOn + " -> " + actualAccState);
                        accIsOn = actualAccState;
                    }
                    if (!accIsOn) {
                        logger.info("Re-select aborted — ACC turned OFF in HW probe");
                        return;
                    }
                    try {
                        com.overdrive.app.monitor.GearMonitor gm =
                            com.overdrive.app.monitor.GearMonitor.getInstance();
                        if (gm.isRunning()) {
                            int actualGear = gm.getCurrentGear();
                            if (actualGear != currentGear) {
                                logger.info("Re-select syncing gear: "
                                    + gearToString(currentGear) + " -> " + gearToString(actualGear));
                                currentGear = actualGear;
                            }
                        }
                    } catch (Exception ignored) {}
                    // Re-evaluate against fresh gear under the monitor.
                    if (mode == Mode.DRIVE_MODE) {
                        shouldActivateReselect = isDrivingGear(currentGear);
                    } else if (mode == Mode.PROXIMITY_GUARD) {
                        // ACC-gated in ALL gears (incl P) — no gear condition.
                        shouldActivateReselect = true;
                    } else {
                        shouldActivateReselect = true;
                    }
                }
                if (!shouldActivateReselect) {
                    logger.info("Re-select waiting for appropriate gear (mode=" + mode
                        + ", gear=" + currentGear + ")");
                    return;
                }
                logger.info("Re-select activating " + mode + " via warmup");
                activateModeWithWarmup(mode, "user-reselect-" + mode);
                return;
            }
            // Sync ACC state — query hardware directly for authoritative state.
            // Done OUTSIDE the manager monitor (mirrors the userRecoveryReselect Round-8
            // refactor above): queryAccStateFromHardware may now sleep up to ~360ms in
            // the rare contradicting-OFF confirm path (confirmHardwareAccOff), and we
            // must not pin the manager monitor across that — it would briefly stall the
            // resync ticker, gear/ACC IPC snapshots, and HTTP readers.
            boolean actualAccState = queryAccStateFromHardware();
            synchronized (this) {

                logger.info("Changing recording mode: " + currentMode + " -> " + mode);

                if (actualAccState != accIsOn) {
                    logger.info("Syncing ACC state: " + accIsOn + " -> " + actualAccState);
                    accIsOn = actualAccState;
                }

                // Sync gear state from GearMonitor (authoritative source)
                try {
                    com.overdrive.app.monitor.GearMonitor gearMonitor = com.overdrive.app.monitor.GearMonitor.getInstance();
                    if (gearMonitor.isRunning()) {
                        int actualGear = gearMonitor.getCurrentGear();
                        if (actualGear != currentGear) {
                            logger.info("Syncing gear from GearMonitor: " + gearToString(currentGear) + " -> " + gearToString(actualGear));
                            currentGear = actualGear;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Could not sync gear: " + e.getMessage());
                }

                oldMode = currentMode;
                currentMode = mode;

                // FIX (rmm: persistMode runs UnifiedConfig full-JSON write under
                // manager monitor). Don't call persistMode here — UnifiedConfig
                // updateSection rewrites the entire JSON document and grabs
                // its own monitor + filesystem lock. With the manager monitor
                // pinned, the resync ticker, gear/ACC IPC handlers, and HTTP
                // introspection endpoints all stall behind the disk write.
                // Defer to OUTSIDE the monitor below.

                // Decide whether the new mode should activate now (mode/gear/ACC gates).
                if (mode == Mode.DRIVE_MODE) {
                    shouldActivate = isDrivingGear(currentGear);
                    if (!shouldActivate) {
                        logger.info("Gear is " + gearToString(currentGear) + " - DRIVE_MODE will activate when in D/R/S/M");
                    }
                } else if (mode == Mode.PROXIMITY_GUARD) {
                    // PROXIMITY_GUARD is ACC-gated in ALL gears (incl. P). The
                    // pre-record ring must be fed continuously while monitoring
                    // so the FIRST radar event — typically while parked in P —
                    // still has pre-event footage. The encode cost is kept low
                    // by the adaptive low-power monitor profile (ProximityGuard
                    // Controller: ~4fps/1.5Mbps ring, snap to full on trigger),
                    // not by withholding the pipeline. (Was: gear != GEAR_P,
                    // which left the ring empty in P → zero pre-roll on the
                    // first parked event, defeating a "guard.")
                    shouldActivate = accIsOn;
                    if (!shouldActivate) {
                        logger.info("ACC is OFF - PROXIMITY_GUARD will activate when ACC turns ON");
                    }
                } else if (mode == Mode.NONE) {
                    // NONE while ACC is OFF: do NOT call activateMode(NONE) — that
                    // would tear down a pipeline that may be servicing an active
                    // surveillance recording. CameraDaemon owns the pipeline
                    // lifecycle during ACC OFF; let the next ACC ON tear it down
                    // via the normal NONE-mode path (pipeline only kept alive by
                    // CONTINUOUS/DRIVE_MODE/PROXIMITY_GUARD recording, and any of
                    // those would have called deactivateMode first via setMode).
                    //
                    // NONE while ACC is ON: tear it down now — the user changed
                    // mode mid-drive and expects "stop recording" to take effect.
                    shouldActivate = accIsOn;
                    if (!shouldActivate) {
                        logger.info("ACC is OFF and mode set to NONE — pipeline stays under "
                            + "CameraDaemon control (surveillance) until next ACC ON");
                    }
                } else {
                    // CONTINUOUS — gated only on ACC.
                    shouldActivate = accIsOn;
                    if (!shouldActivate) {
                        logger.info("ACC is OFF - mode will activate when ACC turns ON");
                    }
                }
            }

            // Persist new mode to config OUTSIDE the manager monitor — the
            // UnifiedConfig write is a full JSON rewrite on the calling thread
            // and we don't want to pin the manager monitor across it (see
            // monitor-acquisition note above). Still done EARLY (before
            // activation) so a crash during activation doesn't lose the
            // user's persisted setting.
            try {
                persistMode(mode);
            } catch (Exception persistErr) {
                logger.warn("persistMode failed for " + mode + ": " + persistErr.getMessage());
            }

            // Deactivate runs unconditionally with no re-validation.
            runDeactivateGuarded(oldMode);

            // Activate with re-validation: if ACC flipped or gear moved
            // between the snapshot above and this lock acquisition, abort.
            //
            // FIX (rmm Round 3, Finding 5: setMode mid-cold-boot bypasses AVC
            // HAL warmup). Route non-NONE activations through
            // activateModeWithWarmup so a setMode invocation within the
            // ~4s post-boot window before com.byd.avc has primed the BYD
            // camera HAL doesn't open the camera against a wedged HAL.
            // The warmup helper:
            //   - short-circuits warmup when pipeline.isRunning() is already
            //     true (warm-already case pays no extra cost),
            //   - coalesces against in-flight warmups (boot auto-activate
            //     worker, gear-change activate),
            //   - re-validates gating state under runActivateGuarded's
            //     supplier before opening the camera.
            // NONE stays on the direct runActivateGuarded path — no warmup
            // needed for tear-down.
            if (shouldActivate) {
                if (targetMode == Mode.NONE) {
                    runActivateGuarded(targetMode, "setMode-" + targetMode, () -> {
                        if (currentMode != targetMode) return false;
                        return accIsOn;
                    });
                } else {
                    logger.info("setMode " + targetMode
                        + " — routing through warmup to protect cold-boot HAL window");
                    activateModeWithWarmup(targetMode, "setMode-" + targetMode);
                }
            }

            logger.info("Recording mode changed: " + oldMode + " -> " + mode);
        }
    }
    
    /**
     * Get current recording mode.
     */
    public Mode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * Notify of ACC state change.
     * Activates/deactivates modes that depend on ACC state.
     *
     * <p>Lock hierarchy (outermost first):
     * <ol>
     *   <li>{@link #lifecycleSerializer} — held for the entire body so
     *       concurrent setMode/onGearChanged/onAccStateChanged calls can't
     *       interleave their decisions and produce a state-machine vs
     *       pipeline mismatch.</li>
     *   <li>Manager monitor ({@code synchronized(this)}) — held only for
     *       the brief snapshot of {@code accIsOn}/{@code currentMode}/
     *       {@code currentGear} mutations. Released across the I/O.</li>
     *   <li>{@link #activationLock} (inside {@link #runActivateGuarded}) —
     *       held across the heavy I/O (camera/encoder init, storage
     *       cleanup pre-flight). Re-validates gating state under the
     *       manager monitor before calling {@code activateMode}.</li>
     * </ol>
     *
     * <p>Without this split, a slow first-recording-after-boot path can
     * pin the manager monitor for minutes (reproduced in
     * cam_daemon_20260602_151424.log). Without the
     * {@code lifecycleSerializer} envelope, concurrent setMode/onAcc/onGear
     * calls can race and leave {@code currentMode} disagreeing with the
     * actually-activated pipeline.
     */
    public void onAccStateChanged(boolean isOn) {
        // Release the boot-stable latch BEFORE taking lifecycleSerializer.
        // We want the boot worker to wake up the moment any ACC IPC arrives,
        // even if a peer setMode is currently inside lifecycleSerializer
        // doing slow I/O. countDown is a no-op after the first call so
        // subsequent IPCs are free.
        bootStableSignal.countDown();
        synchronized (lifecycleSerializer) {
            onAccStateChangedLocked(isOn);
        }
    }

    private void onAccStateChangedLocked(boolean isOn) {
        boolean shouldStopAccOff = false;
        Mode modeToActivate = null;
        boolean stopPipelineOnAccOn = false;
        boolean bsKeepWarmOnAccOn = false;
        synchronized (this) {
            // wasOn reflects "was ACC observed ON via a *prior IPC*?" — not the
            // hardware probe value seeded in the constructor. Without this guard,
            // the very first ACC IPC after boot logs "wasOn=true" (because the
            // probe set it) and triggers the "retrying activation" path, which is
            // misleading: there was no prior activation to retry.
            boolean wasOn = accIpcSeen && accIsOn;
            boolean firstIpc = !accIpcSeen;
            accIpcSeen = true;

            logger.info("ACC state changed: " + (isOn ? "ON" : "OFF") + " (mode=" + currentMode
                + ", wasOn=" + wasOn + (firstIpc ? " [first IPC after boot]" : "")
                + ", modeActive=" + modeActive + ")");

            accIsOn = isOn;

            if (isOn) {
                boolean isChargingNow = false;
                try {
                    isChargingNow = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging();
                } catch (Throwable ignored) {}

                if (isChargingNow && (currentMode == Mode.CONTINUOUS || currentMode == Mode.DRIVE_MODE)) {
                    logger.info("ACC ON edge but vehicle is CHARGING — suppressing driving recording (" + currentMode + ")");
                    return;
                }

                if (wasOn && modeActive) {
                    logger.debug("ACC already ON and mode active, ignoring duplicate notification");
                    return;
                }
                if (wasOn && !modeActive) {
                    logger.info("ACC was already ON but mode not active — retrying activation");
                }
                // Reset GearMonitor retry counter on ACC ON edge — a HAL
                // outage during the previous ACC cycle shouldn't permanently
                // suppress retries once the user re-engages the system.
                gearMonitorRetryFailures = 0;
                gearMonitorCapResetCounter = 0;
                // FIX (rmm: Resync wedgeDetected has no backoff or per-cycle
                // cap): reset wedge retry counter + backoff window. Each
                // ACC cycle gets a fresh budget; structural wedges that
                // exhausted the previous cycle's cap can attempt recovery
                // again now that the HAL has been kicked.
                if (wedgeRetryFailures > 0 || wedgeRetryNextAttemptMs > 0L) {
                    logger.info("ACC ON edge — resetting wedge retry counter (was "
                        + wedgeRetryFailures + ")");
                    wedgeRetryFailures = 0;
                    wedgeRetryNextAttemptMs = 0L;
                }

                if (currentMode == Mode.NONE) {
                    // OEM-PARITY: on dilink4 the pipeline is started at daemon
                    // boot (CameraDaemon Dilink4BootPipelineStart) and must
                    // STAY ALIVE across the entire daemon lifetime — oem's
                    // PanoCameraRecordService never gets stopped on any ACC
                    // edge. Suppressing the legacy "tear down on mode=NONE"
                    // path here.
                    //
                    // Legacy fleet keeps the original tear-down because the
                    // pipeline is only started on demand and must release
                    // its camera+GL+encoder when no mode is active.
                    boolean dilink4 = false;
                    try {
                        dilink4 = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
                    } catch (Throwable ignored) {}
                    if (dilink4) {
                        logger.info("ACC ON with mode=NONE — keeping pipeline alive (dilink4 oem-parity)");
                    } else if (bsKeepWarmActive()) {
                        // Blind-spot is enabled and ACC is on: keep the camera
                        // WARM for the BS lane even though no recording mode is
                        // active. The app-side BlindSpotControl.sync() also POSTs
                        // /api/bs/enable on this ACC edge (which would start the
                        // pano anyway); marking keep-warm here prevents a
                        // teardown/restart fight and lets reconcileCameraProfile
                        // park the camera at the cheap BS-only profile (recorder
                        // lane OFF, global fps = BS idle). If the pipeline isn't
                        // up yet, the BS arm path cold-starts it; we just must
                        // NOT tear it down.
                        logger.info("ACC ON with mode=NONE — keeping pipeline warm for blind-spot");
                        bsKeepWarmOnAccOn = true;
                    } else {
                        // CRITICAL legacy path: don't bare-return — without an
                        // explicit teardown, camera+GL+encoder stay allocated
                        // indefinitely with no recording, burning ~70% CPU.
                        logger.info("ACC ON with mode=NONE — ensuring pipeline is stopped");
                        stopPipelineOnAccOn = true;
                    }
                } else {
                    modeToActivate = currentMode;
                }
            } else {
                shouldStopAccOff = true;
            }
        }

        // Heavy I/O outside the manager monitor (still under lifecycleSerializer).
        if (bsKeepWarmOnAccOn) {
            // ACC on, mode NONE, but blind-spot wants the camera warm. Don't
            // tear down. If the pano is already up (e.g. survived from a prior
            // cycle), reconcile it to the cheap BS-only profile now (recorder
            // lane OFF, global fps = BS idle); the on-screen show/hide ramp is
            // driven by the BS turn-tick. If the pano isn't up yet, the app's
            // BlindSpotControl.sync() ARM path (POST /api/bs/enable) cold-starts
            // it, and ensurePanoStartedNonBlocking's completion reconcile applies
            // the same profile — so either way we converge without a teardown.
            reconcileCameraProfile();
            return;
        }
        if (stopPipelineOnAccOn) {
            // Final guard: re-read accIsOn — if a sibling ACC IPC flipped
            // the state between snapshot and now, honor the latest.
            runActivateGuarded(Mode.NONE, "acc-on-mode-none", () -> currentMode == Mode.NONE);
            return;
        }

        if (shouldStopAccOff) {
            // stopAvcKeepAlive runs unconditionally (even if pipeline isn't
            // running, the keep-alive watchdog might be) — preserve that.
            CameraDaemon.stopAvcKeepAlive();

            // OEM-PARITY DILINK4 PATH (oem MainService.java:677-689 +
            // FlameoutService p111dh/C4995i.java:371-411): oem never closes
            // AVMCamera on ACC-OFF, period. PanoCameraRecord stays alive
            // for the entire MainService lifetime. ACC-OFF only schedules
            // the secondary auto-sentry-record consumer (separate C5920a)
            // 60 s later via mTaskExecutor — non-blocking, doesn't touch
            // the live camera handle.
            //
            // For dilink4, ALWAYS take the keep-alive path. Drop every
            // surveillance / safe-zone / schedule gate the prior wiring
            // had — none of those gates exist in oem. If the user has
            // surveillance off, oem still keeps the camera open for the
            // user's preview path; OverDrive must do the same.
            //
            // Legacy mode (non-dilink4) keeps the original teardown
            // behaviour — the close+reopen race is dilink4-firmware-
            // specific.
            boolean dilinkKeepAlive = false;
            try {
                dilinkKeepAlive = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic()
                        || com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported();
            } catch (Throwable t) {
                logger.warn("dilink mode probe failed: " + t.getMessage());
            }

            if (dilinkKeepAlive) {
                if (pipeline.isRunning()) {
                    logger.info("ACC OFF (dilink4) — finalize recording, keep camera alive (oem-parity, unconditional)");
                    try {
                        pipeline.stopRecording();
                    } catch (Throwable t) {
                        logger.warn("dilink4 stopRecording failed: " + t.getMessage());
                    }
                } else {
                    // Pipeline not running yet at ACC-OFF: nothing to keep
                    // alive here. CameraDaemon's boot path / onAccStateChanged
                    // will start it; we just record state.
                    logger.info("ACC OFF (dilink4) — pipeline not running, deferring to CameraDaemon");
                }
                // Mark mode=NONE so the manager state machine matches
                // "no active recording mode". Pipeline keeps running.
                synchronized (this) {
                    currentMode = Mode.NONE;
                }
                return;
            }

            // Legacy fleet (non-dilink4): route teardown through
            // runActivateGuarded(NONE) — original behaviour preserved.
            runActivateGuarded(Mode.NONE, "acc-off", () -> !accIsOn);
            return;
        }

        if (modeToActivate == null) return;

        final Mode mta = modeToActivate;
        // FIX (rmm Round 8 medium: AccOnReacquire warmup thread bypasses
        // warmupInFlight CAS and stuck-warmup watchdog). Previously this
        // path spawned a raw `new Thread()` that called avcWarmup.warmupAndWait()
        // directly without going through activateModeWithWarmup. The
        // warmupInFlight AtomicBoolean and warmupInFlightSinceMs timestamp
        // were never set, so resyncFromHardware's stuck-warmup watchdog
        // (keyed on warmupInFlight.get()) could not detect or force-clear
        // a wedged AVC warmup on this path; it could also race in parallel
        // with peer warmups (boot/gear/setMode) — both paying the ~4s sleep.
        // activateModeWithWarmup short-circuits warmup when pipeline.isRunning(),
        // sets warmupInFlight + since-time for watchdog visibility, coalesces
        // against parallel warmups via CAS+pendingRetrigger, and re-validates
        // the same gates inside runActivateGuarded.
        logger.info("ACC ON reacquire — routing " + mta + " through activateModeWithWarmup "
            + "(watchdog-tracked, CAS-coalesced)");
        activateModeWithWarmup(mta, "acc-on-reacquire-" + mta);
    }
    
    /**
     * Notify of gear state change.
     * - DRIVE_MODE: activates on D/R/S/M, deactivates on P
     * - PROXIMITY_GUARD: ignores gear (ACC-gated in all gears; see onAccStateChanged)
     *
     * @param gear The new gear position (GEAR_P, GEAR_R, GEAR_N, GEAR_D, etc.)
     */
    public void onGearChanged(int gear) {
        // Release the boot-stable latch — see onAccStateChanged for rationale.
        // GearMonitor's start() fires this with the initial gear immediately
        // after a successful HAL bind, so this is one of the fastest signals
        // that "real state has arrived; boot auto-activate is safe to run."
        bootStableSignal.countDown();

        // Wrapped in lifecycleSerializer for parity with setMode/onAccStateChanged.
        // Without this, a concurrent setMode(NONE) and onGearChanged(D) can
        // interleave: setMode mutates currentMode=NONE while gear handler still
        // sees the old PROXIMITY_GUARD/DRIVE_MODE and queues an activate that
        // contradicts the user's mode change. Same shape as the original
        // CRITICAL-2 race.
        synchronized (lifecycleSerializer) {
            onGearChangedLocked(gear);
        }
    }

    private void onGearChangedLocked(int gear) {
        Mode toDeactivate = null;
        Runnable activateAfter = null;
        synchronized (this) {
            // Suppress no-op notifications — GearMonitor.start() calls onGearChanged()
            // once with the initial gear so the rest of the system gets primed, but
            // for RecordingModeManager that often matches the constructor default
            // and there's nothing to do. Logging it as a "P -> P" change is just noise.
            if (gear == currentGear) {
                return;
            }

            String gearName = gearToString(gear);
            logger.info("Gear changed: " + gearToString(currentGear) + " -> " + gearName + " (mode=" + currentMode + ")");

            int previousGear = currentGear;
            currentGear = gear;

            // Only DRIVE_MODE responds to gear changes. PROXIMITY_GUARD is now
            // ACC-gated in ALL gears (the low-power ring stays warm parked in P
            // so the first event has pre-roll), so it ignores gear entirely —
            // its start/stop is driven solely by ACC edges (onAccStateChanged).
            if (currentMode != Mode.DRIVE_MODE) {
                logger.debug("Mode " + currentMode + " does not respond to gear changes");
                return;
            }

            // DRIVE_MODE: record when driving (D/R/S/M) AND ACC is ON
            boolean wasDriving = isDrivingGear(previousGear);
            boolean nowDriving = isDrivingGear(gear);

            // Use modeActive (not just gear edge) so cold-start — where
            // GearMonitor's first real reading arrives after construction with
            // currentGear default GEAR_P — also activates DRIVE_MODE on the
            // first delivered driving gear, even though the "edge" condition
            // (wasDriving=false → nowDriving=true) only fires once.
            if (nowDriving && accIsOn && !modeActive) {
                logger.info("Driving gear with mode not yet active - activating DRIVE_MODE recording");
                // Route through warmup. If the user shifts D within the 4s
                // AVC warmup window after ACC ON, calling activateMode()
                // directly would open the camera before com.byd.avc finished
                // initializing the HAL → wedged camera, no recording. The
                // warmup helper short-circuits when the pipeline is already
                // running, so it's a no-op cost when not needed.
                activateAfter = () -> activateModeWithWarmup(Mode.DRIVE_MODE, "gear-to-driving");
            } else if (!nowDriving && (wasDriving || modeActive)) {
                logger.info("Shifted to parked gear - deactivating DRIVE_MODE recording");
                toDeactivate = Mode.DRIVE_MODE;
            } else if (nowDriving && !accIsOn) {
                logger.info("Driving gear but ACC OFF - DRIVE_MODE will activate when ACC turns ON");
            }
        }

        if (toDeactivate != null) {
            runDeactivateGuarded(toDeactivate);
        }
        if (activateAfter != null) {
            // activateModeWithWarmup spawns its own worker thread that takes
            // activationLock — fire-and-forget here.
            activateAfter.run();
        }
    }
    
    /**
     * Check if ACC is ON.
     */
    public boolean isAccOn() {
        return accIsOn;
    }

    /**
     * Check if the configured mode's pipeline/recording is genuinely live.
     *
     * <p>Distinguishes "user picked CONTINUOUS, ACC is ON, recording IS
     * happening" from "user picked CONTINUOUS, ACC is ON, but a silent
     * activation failure left modeActive=false." Surface this in status
     * UIs so a stuck activation is observable rather than indistinguishable
     * from a normal cold-start delay.
     */
    public boolean isModeActive() {
        return modeActive;
    }

    /**
     * True when the configured CONTINUOUS/DRIVE_MODE activation is wedged —
     * the pipeline is running and {@link #modeActive} is true, but the encoder
     * is structurally stuck (pending-record prefix never resolves / no encoded
     * frames for &gt;15s). Status surfaces use this to distinguish "recording is
     * spinning up" (show active) from "we think we're recording but nothing is
     * being written" (show fault). Always false for PROXIMITY_GUARD. See the
     * resync tick's {@code wedgeDetected} computation.
     */
    public boolean isRecordingWedged() {
        return recordingWedged;
    }

    /**
     * Number of consecutive failed GearMonitor.start() retries from the
     * resync ticker. 0 = healthy or never attempted; &gt;0 = HAL flaky;
     * == {@link #GEAR_MONITOR_RETRY_CAP} = retries suppressed until next
     * ACC ON edge. Surface in status for diagnostics.
     */
    public int getGearMonitorRetryFailures() {
        return gearMonitorRetryFailures;
    }

    /**
     * Get current gear position.
     */
    public int getCurrentGear() {
        return currentGear;
    }
    
    /**
     * Check if gear is a driving gear (D/R/S/M/N).
     * N is included because BYD Auto Hold reports N while the car is stopped at a
     * traffic light with the driver's foot off the brake. Excluding N would cause
     * DRIVE_MODE recording to stop/start on every Auto Hold engage/release cycle.
     * This matches TripDetector.isDrivingGear which also includes N.
     */
    public static boolean isDrivingGear(int gear) {
        return gear == GEAR_D || gear == GEAR_R || gear == GEAR_N || gear == GEAR_S || gear == GEAR_M;
    }
    
    /**
     * Check if gear is a parked gear (P only).
     * N is NOT parked — see isDrivingGear comment about Auto Hold.
     */
    public static boolean isParkedGear(int gear) {
        return gear == GEAR_P;
    }
    
    /**
     * Convert gear constant to string.
     */
    public static String gearToString(int gear) {
        switch (gear) {
            case GEAR_P: return "P";
            case GEAR_R: return "R";
            case GEAR_N: return "N";
            case GEAR_D: return "D";
            case GEAR_M: return "M";
            case GEAR_S: return "S";
            default: return "UNKNOWN(" + gear + ")";
        }
    }
    
    // ==================== MODE ACTIVATION ====================
    
    private void activateMode(Mode mode) {
        // Mark a camera-owning activation in flight so a racing BS turn-signal
        // reconcile (turn-tick thread) doesn't disable the recorder lane / drop
        // fps in the window before modeActive is set true. Cleared in finally.
        boolean ownerActivation = (mode == Mode.CONTINUOUS
                || mode == Mode.DRIVE_MODE || mode == Mode.PROXIMITY_GUARD);
        if (ownerActivation) activatingCameraOwner = true;
        try {
            activateModeBody(mode);
        } finally {
            if (ownerActivation) activatingCameraOwner = false;
        }
    }

    private void activateModeBody(Mode mode) {
        logger.info("Activating mode: " + mode);

        // SOTA: Stop any manual recording before activating a mode
        // This ensures mode-managed recording takes precedence over manual recording
        if (pipeline.isNormalRecordingMode()) {
            logger.info("Stopping manual recording before activating mode: " + mode);
            pipeline.stopRecording();
        }

        // If user changed cameraFps in config since the encoder was built, force
        // a clean stop here so the per-mode start() below runs through init() and
        // picks up the new FPS via loadTargetFps(). Without this, FPS changes
        // applied while the pipeline stayed alive across ACC OFF (sentry mode)
        // wouldn't reach the encoder until the next full app restart.
        //
        // Safe at this exact moment: ACC has just turned ON, surveillance was
        // already disabled by CameraDaemon.onAccOn() before this thread runs,
        // and CONTINUOUS/DRIVE_MODE recording hasn't started yet — there is no
        // active recording state to lose.
        // OEM-PARITY: skip the FPS-stale teardown on dilink4. oem only
        // restarts the camera for resolution/quality changes (config-diff
        // at PanoCameraRecordService.m19844Z); FPS changes don't close the
        // AVMCamera. On dilink4 we live with stale FPS until the next
        // legitimate restart (process exit / fatal HAL event).
        boolean dilink4FpsSkip = false;
        try {
            dilink4FpsSkip = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
        } catch (Throwable ignored) {}
        if (mode != Mode.NONE && pipeline.isFpsConfigStale() && !dilink4FpsSkip) {
            logger.info("Camera FPS config changed — restarting pipeline to apply");
            pipeline.stop();
        } else if (mode != Mode.NONE && pipeline.isFpsConfigStale() && dilink4FpsSkip) {
            logger.info("Camera FPS config stale — keeping pipeline alive (dilink4 oem-parity)");
        }

        switch (mode) {
            case NONE:
                // Stop the proximity controller's ADAS listener if it was
                // wired (PROXIMITY_GUARD active before this NONE transition).
                // Without this, the controller's ADAS callbacks keep firing
                // across the entire ACC-OFF window — chewing CPU and
                // logging spurious proximity events while the user is parked.
                // Idempotent: stop() is a no-op when state == IDLE.
                if (proximityController != null) {
                    proximityController.stop();
                }
                // OEM-PARITY: dilink4 keeps pipeline alive on user-initiated
                // mode=NONE; only legacy tears down for resource saving.
                {
                    boolean dilink4None = false;
                    try {
                        dilink4None = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
                    } catch (Throwable ignored) {}
                    // Blind-spot keep-warm is an INDEPENDENT consumer: if BS wants
                    // the camera (enabled/debugPreview + ACC on), switching the
                    // RECORDING mode to NONE must NOT blank the blind-spot lane.
                    // Mirror the CONTINUOUS/DRIVE/PROXIMITY deactivate guards —
                    // keep the pipeline alive and let reconcileCameraProfile park
                    // it at the cheap BS-only profile. (Without this, mode→NONE
                    // while BS is on tore the camera down until the next BS arm /
                    // ACC edge cold-restarted the pano — a BS-availability gap.)
                    if (pipeline.isRunning() && !dilink4None && !bsKeepWarmActive()) {
                        logger.info("Stopping pipeline for NONE mode (resource saving)");
                        pipeline.stop();
                        CameraDaemon.stopAvcKeepAlive();
                    } else if (dilink4None) {
                        logger.info("NONE mode requested — keeping pipeline alive (dilink4 oem-parity)");
                    } else if (pipeline.isRunning()) {
                        logger.info("NONE mode requested — keeping pipeline alive for blind-spot keep-warm");
                    }
                }
                modeActive = false;
                // Reconcile so a BS-only keep-warm camera is parked at the BS
                // profile (recorder lane OFF, fps idle/active) rather than left at
                // the just-ended recording mode's full profile.
                reconcileCameraProfile();
                break;
                
            case CONTINUOUS:
                // Start pipeline and recording
                try {
                    if (!pipeline.isRunning()) {
                        logger.info("Starting pipeline for CONTINUOUS mode");
                        pipeline.start(false);
                    }
                    // Re-check isRunning AFTER start(): pipeline.start() can
                    // silently return without starting if it observes stopping=true
                    // (mid-teardown from surveillance disable) or already-running
                    // — we must not call startRecording on a non-running pipeline.
                    if (!pipeline.isRunning()) {
                        logger.warn("CONTINUOUS: pipeline.start() returned but pipeline isn't running"
                            + " — likely mid-teardown; will retry on next mode trigger");
                        modeActive = false;
                        break;
                    }
                    // Pipeline.start() blocks ~2s for GL init. Recorder should be ready.
                    //
                    // Gate skip on isNormalRecordingMode() (NORMAL_RECORDING vs
                    // SURVEILLANCE) instead of raw isRecording() — when ACC ON
                    // arrives during a surveillance segment finalize, the
                    // recorder.isRecording() probe returns true (segment still
                    // closing) but the pipeline is in SURVEILLANCE mode. If
                    // we skipped startRecording on that signal, currentMode
                    // would stay SURVEILLANCE and the user's CONTINUOUS dashcam
                    // wouldn't actually start until the surveillance segment
                    // closed (up to 5min). pipeline.startRecording() handles
                    // the SURVEILLANCE->NORMAL transition correctly.
                    // Restore the FULL recording baseline BEFORE startRecording —
                    // if the camera was warm ONLY for blind-spot, its recorder
                    // lane was OFF and global fps was dropped to ~1, so the first
                    // recorded frames would otherwise be BS-idle quality. Lane ON
                    // + stride 1 + configured bitrate + recording fps; all
                    // sub-frame (no codec reconfigure).
                    applyFullRecordingProfile();
                    if (!pipeline.isNormalRecordingMode()) {
                        if (pipeline.isRecording()) {
                            logger.info("CONTINUOUS: pipeline recording but not in NORMAL mode "
                                + "(likely SURVEILLANCE finalize) — calling startRecording to "
                                + "drive transition");
                        }
                        pipeline.startRecording();
                        OemDashcamMirror.onPanoRecordingStarted();
                    }
                    // Start AVC keep-alive (pipeline is now running with ACC ON)
                    CameraDaemon.startAvcKeepAliveIfNeeded();
                    // Don't optimistically lie about modeActive: a true
                    // pipeline.isRunning() with isRecording()==false AND no
                    // deferred-record in flight is a wedged activation —
                    // mark it inactive so the resync ticker retries instead
                    // of believing the pipeline is healthy. Pending prefix
                    // covers the brief startRecording() window where the
                    // recorder hasn't latched yet.
                    boolean continuousHealthy = pipeline.isRunning()
                            && (pipeline.isRecording()
                                || pipeline.getPendingRecordingPrefix() != null);
                    modeActive = continuousHealthy;
                    if (!continuousHealthy) {
                        logger.warn("CONTINUOUS: pipeline running=" + pipeline.isRunning()
                                + " recording=" + pipeline.isRecording()
                                + " pendingPrefix=" + (pipeline.getPendingRecordingPrefix() != null)
                                + " — leaving modeActive=false for resync retry");
                    }
                } catch (Exception e) {
                    logger.error("Failed to start CONTINUOUS mode: " + e.getMessage());
                    modeActive = false;
                }
                break;

            case DRIVE_MODE:
                // Start recording when driving (gear is D/R/S/M)
                try {
                    if (!pipeline.isRunning()) {
                        logger.info("Starting pipeline for DRIVE_MODE");
                        pipeline.start(false);
                    }
                    if (!pipeline.isRunning()) {
                        logger.warn("DRIVE_MODE: pipeline.start() returned but pipeline isn't running"
                            + " — likely mid-teardown; will retry on next gear change");
                        modeActive = false;
                        break;
                    }
                    // Pipeline.start() blocks ~2s for GL init. Recorder should be ready.
                    // Gate on isNormalRecordingMode() so a surveillance->normal
                    // transition during gear-change activation actually drives
                    // startRecording (mirrors CONTINUOUS branch — see comment
                    // there for the surveillance-finalize race rationale).
                    // Restore FULL recording baseline before startRecording (see
                    // CONTINUOUS branch) so a BS-only keep-warm state (recorder
                    // lane off, fps 1) doesn't bleed into the first recorded
                    // frames.
                    applyFullRecordingProfile();
                    if (!pipeline.isNormalRecordingMode()) {
                        if (pipeline.isRecording()) {
                            logger.info("DRIVE_MODE: pipeline recording but not in NORMAL mode "
                                + "(likely SURVEILLANCE finalize) — calling startRecording");
                        } else {
                            logger.info("Starting DRIVE_MODE recording");
                        }
                        pipeline.startRecording();
                        OemDashcamMirror.onPanoRecordingStarted();
                    }
                    // Start AVC keep-alive (pipeline is now running with ACC ON)
                    CameraDaemon.startAvcKeepAliveIfNeeded();
                    // Same wedge-honest accounting as CONTINUOUS — see that
                    // branch for rationale. PROXIMITY_GUARD below intentionally
                    // keeps the optimistic isRunning() check because it doesn't
                    // continuously record; recording fires on radar triggers.
                    boolean driveHealthy = pipeline.isRunning()
                            && (pipeline.isRecording()
                                || pipeline.getPendingRecordingPrefix() != null);
                    modeActive = driveHealthy;
                    if (!driveHealthy) {
                        logger.warn("DRIVE_MODE: pipeline running=" + pipeline.isRunning()
                                + " recording=" + pipeline.isRecording()
                                + " pendingPrefix=" + (pipeline.getPendingRecordingPrefix() != null)
                                + " — leaving modeActive=false for resync retry");
                    }
                } catch (Exception e) {
                    logger.error("Failed to start DRIVE_MODE: " + e.getMessage());
                    modeActive = false;
                }
                break;

            case PROXIMITY_GUARD:
                // Start pipeline (without recording) and proximity controller
                try {
                    if (!pipeline.isRunning()) {
                        logger.info("Starting pipeline for PROXIMITY_GUARD mode");
                        pipeline.start(false);  // Don't auto-start recording
                    }
                    if (!pipeline.isRunning()) {
                        // Don't start the proximity controller against a
                        // pipeline that's mid-teardown — the controller would
                        // wire its ADAS listener thinking the camera is up,
                        // but the pipeline tears down moments later and the
                        // proximity-trigger recordings would silently fail.
                        logger.warn("PROXIMITY_GUARD: pipeline.start() returned but pipeline isn't running"
                            + " — refusing to start proximity controller; will retry on next gear change");
                        modeActive = false;
                        break;
                    }
                    // Restore the FULL recording baseline (lane ON + stride 1 +
                    // configured bitrate + recording fps) BEFORE starting the
                    // proximity controller. Critical: the controller's MONITORING
                    // profile derives its draw stride as round(cameraFps /
                    // monitorFps); if the camera were still at the BS-idle ~1 fps
                    // (camera came up only for blind-spot), that math would
                    // compute stride 1 against 1 fps and starve the pre-record
                    // ring. applyFullRecordingProfile sets the camera to the real
                    // recording fps first; the controller then layers its own
                    // low-power MONITORING stride/bitrate on top.
                    applyFullRecordingProfile();
                    proximityController.start();
                    // Start AVC keep-alive (pipeline is now running with ACC ON)
                    CameraDaemon.startAvcKeepAliveIfNeeded();
                    modeActive = pipeline.isRunning();
                } catch (Exception e) {
                    logger.error("Failed to start PROXIMITY_GUARD mode: " + e.getMessage());
                    modeActive = false;
                }
                break;
        }
    }
    
    private void deactivateMode(Mode mode) {
        logger.info("Deactivating mode: " + mode);

        // Whatever was active is no longer active. Set this up front so the
        // duplicate-event guard in onAccStateChanged() will allow re-activation.
        modeActive = false;
        // A deactivated mode can't be wedged — clear immediately rather than
        // waiting for the next resync tick, so the status surfaces don't show
        // a stale fault after a clean stop (mode change / gear-to-P / ACC off).
        recordingWedged = false;

        // Check if surveillance should be preserved — don't stop pipeline during ACC OFF
        // (surveillance/sentry mode needs the pipeline running).
        //
        // OEM-PARITY: dilink4 keeps the pipeline alive across user mode-
        // switch deactivations (CONTINUOUS → other, PROXIMITY_GUARD → other).
        // oem's mode toggles never close the AVMCamera handle.
        boolean dilink4Persistent = false;
        try {
            dilink4Persistent = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
        } catch (Throwable ignored) {}
        boolean keepPipelineRunning = !accIsOn || dilink4Persistent;

        // MODE-SWITCH NO-CHURN: setMode sets currentMode=NEW before deactivating
        // the OLD mode here. If the NEW mode also owns the camera right now
        // (CONTINUOUS / DRIVE-while-driving / PROXIMITY, ACC on), tearing the
        // pipeline down only to immediately cold-restart it (~4-6s AVC warmup +
        // GL init, and the pre-record ring is lost) is pure churn. Keep it alive
        // and let the NEW mode's activate (applyFullRecordingProfile) re-profile
        // sub-frame. Guard on currentMode != mode so this ONLY applies to a real
        // A→B switch — a gear→P DRIVE deactivate (currentMode still DRIVE, gear P
        // ⇒ modeWouldOwnCameraNow false anyway) and an ACC-off deactivate fall
        // through to the normal teardown logic.
        boolean nextModeWillOwnCamera = (currentMode != mode) && modeWouldOwnCameraNow(currentMode);

        if (keepPipelineRunning) {
            if (dilink4Persistent && accIsOn) {
                logger.info("dilink4 + ACC ON — keeping pipeline alive across deactivate (oem-parity)");
            } else {
                logger.info("ACC is OFF — keeping pipeline running for surveillance");
            }
        } else if (nextModeWillOwnCamera) {
            logger.info("Mode switch " + mode + " -> " + currentMode
                + " (both own camera) — keeping pipeline alive, re-profiling sub-frame (no cold restart)");
        }
        
        switch (mode) {
            case NONE:
                // Already stopped
                break;
                
            case CONTINUOUS:
                // Stop recording but keep pipeline if ACC is OFF (surveillance
                // running), dilink4 (oem-parity), blind-spot keep-warm, or a
                // switch to another camera-owning mode (no cold-restart churn).
                pipeline.stopRecording();
                OemDashcamMirror.onPanoRecordingStopped();
                if (pipeline.isRunning() && !keepPipelineRunning && !bsKeepWarmActive()
                        && !nextModeWillOwnCamera) {
                    pipeline.stop();
                    CameraDaemon.stopAvcKeepAlive();
                }
                break;

            case DRIVE_MODE:
                // Stop recording AND tear down the pipeline when no consumer
                // still needs the camera. Previously this kept the camera warm
                // unconditionally for "quick resume" — but a parked-in-P car
                // (DRIVE_MODE shifted to P) then burned full-quality GPU+encode
                // for a recording that can't happen until a driving gear. Now we
                // stop unless ACC is off (surveillance owns it), dilink4 (oem-
                // parity), or blind-spot is keeping the rails warm. When BS keeps
                // it warm, throttle the recorder lane to the BS profile so it
                // isn't running at full recording quality (applyRecorderProfileForState).
                pipeline.stopRecording();
                OemDashcamMirror.onPanoRecordingStopped();
                if (pipeline.isRunning() && !keepPipelineRunning && !bsKeepWarmActive()
                        && !nextModeWillOwnCamera) {
                    pipeline.stop();
                    CameraDaemon.stopAvcKeepAlive();
                }
                break;

            case PROXIMITY_GUARD:
                // Stop proximity controller but keep pipeline if ACC is OFF (surveillance running)
                proximityController.stop();
                // FIX (false-GREEN PROX pill at next ACC-ON): finalize any
                // in-flight / leftover proximity clip, symmetric with the
                // CONTINUOUS/DRIVE_MODE cases above. proximityController.stop()
                // halts the radar monitor but does NOT itself drain the
                // recorder's `recording` flag if a triggered clip (or its
                // POST_RECORD tail) is still open. Without this, the stale
                // recorder.recording=true survives the ACC-OFF→ON transition
                // and /status reports isRecording=true while the next session
                // is merely armed-idle → the overlay paints a false GREEN
                // "PROX" until the first real trigger's stop clears it.
                // Idempotent: stopRecording() is a no-op when not recording.
                pipeline.stopRecording();
                if (pipeline.isRunning() && !keepPipelineRunning && !bsKeepWarmActive()
                        && !nextModeWillOwnCamera) {
                    pipeline.stop();
                    CameraDaemon.stopAvcKeepAlive();
                }
                break;
        }

        // If we kept the pipeline warm ONLY for blind-spot (recording mode just
        // deactivated but BS still wants the camera), reconcile the camera
        // profile: switch the H.265 recorder lane OFF (BS has no encoder) and
        // drop global camera fps to the BS idle/active rate. No-op if the
        // pipeline actually stopped above (camera gone) or if a recording mode
        // is still active (in which case it gets the full-rate baseline).
        reconcileCameraProfile();
    }

    /**
     * True when blind-spot should keep the shared camera/rails warm independently
     * of any recording mode or gear. BS renders to its own SurfaceControl layer
     * (NO encoder), so this only governs pipeline LIFETIME — when BS is the SOLE
     * reason the camera is up, the H.265 recorder lane is switched OFF and the
     * global camera fps is dropped (see {@link #reconcileCameraProfile}).
     * Runs in the daemon process (same UID that writes blindspot config), so a
     * plain read is fresh enough; gated on ACC so a powered-down car never keeps
     * the camera up.
     */
    private boolean bsKeepWarmActive() {
        try {
            // Mirror the ARM authority (StreamingApiHandler.resolveBlindSpotLifecycle
            // + BlindSpotControl.sync), which arm the lane on (enabled OR
            // debugPreview). The profile/lifetime authority MUST agree: a
            // debugPreview-only calibration session (enabled=false) genuinely wants
            // the camera warm for the BS lane, so it should be parked at the cheap
            // BS-only profile (recorder lane OFF, fps=active) and kept alive — not
            // classified as no-owner (which would run the H.265 recorder lane at
            // full fps for a feature that records nothing) nor torn down by the
            // WS-idle shutdown. Reading debugPreview off the same getBlindSpot()
            // object keeps it a single mtime-cached read.
            org.json.JSONObject bs = UnifiedConfigManager.getBlindSpot();
            boolean enabled = bs != null && (bs.optBoolean("enabled", false)
                    || bs.optBoolean("debugPreview", false));
            return accIsOn && enabled;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Camera-view keep-warm: the head-unit/cluster "show camera view" overlay
     * (StreamingApiHandler /api/camview) renders the stitched pano onto the same native
     * SurfaceControl lane the blind-spot view uses. It is an INDEPENDENT camera consumer —
     * exactly like {@link #bsKeepWarmActive()} — so it must hold the camera warm and be a
     * recognised rung in {@link #desiredCameraState()}. Without this, cam-view was invisible
     * to the single-authority camera ladder: the overlay chrome (the ✕ close button) opened
     * but no frames were produced, because no rung kept the pano rail delivering at a usable
     * fps (idle rung sits at ~1fps and the keep-alive predicate would tear the camera down
     * when nothing else wanted it). Gated on ACC-on, mirroring BS. */
    private boolean camViewKeepWarmActive() {
        try {
            return accIsOn && pipeline.isCamViewActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Public entry point invoked when the pano pipeline was started OUTSIDE the
     * recording-mode lifecycle — specifically the blind-spot cold-start path
     * (StreamingApiHandler.ensurePanoStartedNonBlocking → pano.start()). That
     * path warms the camera at the pipeline's default full profile; if BS is the
     * only consumer we must immediately reconcile it down to the cheap BS-only
     * profile (recorder lane OFF, global fps = BS idle/active). reconcileCameraProfile
     * self-serializes on reconcileLock. No-op if a recording mode is active (its
     * own activate path already set the full-recording baseline) or if the
     * pipeline isn't running.
     */
    public void onPipelineStartedExternally() {
        reconcileCameraProfile();
    }

    /**
     * Pushed by the surveillance engine when motion activity begins (first
     * motion) or ends (recording stops) so the parked-idle throttle can ramp
     * the camera fps between idle and full promptly, rather than waiting for the
     * 30s resync tick. reconcileCameraProfile self-serializes on reconcileLock;
     * a no-op when the throttle is off (desiredCameraState returns the same
     * intent). Cheap and idempotent — safe to call on every motion edge.
     */
    public void onSurveillanceActivityChanged() {
        reconcileCameraProfile();
    }

    /** True when an active recording mode is the OWNER of the camera (it wants the
     *  H.265 recorder lane running at full rate). PROXIMITY_GUARD counts: it owns
     *  the pre-record ring even between triggers (its own controller refines the
     *  monitor-window stride/bitrate on top of this). */
    private boolean recordingModeOwnsCamera() {
        return modeActive
                && (currentMode == Mode.CONTINUOUS
                    || currentMode == Mode.DRIVE_MODE
                    || currentMode == Mode.PROXIMITY_GUARD);
    }

    /** Whether the given mode WOULD own the camera under the CURRENT ACC/gear
     *  state right now, independent of modeActive (which the new mode hasn't set
     *  yet during a mode switch). Used by deactivateMode to decide whether a
     *  switch to {@code m} should keep the pipeline alive (no cold-restart churn):
     *  CONTINUOUS needs ACC on; DRIVE needs ACC on AND a driving gear;
     *  PROXIMITY_GUARD needs ACC on (any gear). NONE never owns. */
    private boolean modeWouldOwnCameraNow(Mode m) {
        if (!accIsOn) return false;
        switch (m) {
            case CONTINUOUS:      return true;
            case DRIVE_MODE:      return isDrivingGear(currentGear);
            case PROXIMITY_GUARD: return true;
            default:              return false;
        }
    }

    /**
     * Single authority for the camera's runtime profile (global fps + recorder
     * lane on/off), reconciled against who actually needs the camera. Called
     * after every lifecycle transition (activate/deactivate) and on BS
     * show/hide. No-op when the pipeline isn't running.
     *
     * <p>Two regimes:
     * <ul>
     *   <li><b>A recording mode owns the camera</b> → recorder lane ON, stride 1,
     *       configured recording bitrate, and global camera fps restored to the
     *       user's configured recording fps. Recording ALWAYS wins the rate.
     *       (ProximityGuardController layers its own low-power MONITORING stride
     *       /bitrate on top — see {@link #applyRecorderProfileForState}-era note;
     *       this method only sets the full-rate baseline it snaps back to.)</li>
     *   <li><b>Camera up ONLY for blind-spot keep-warm</b> → recorder lane OFF
     *       (BS has no encoder; no ring to feed), and global camera fps set to
     *       the BS idle/active fps depending on whether the BS view is currently
     *       shown (turn signal). BS's sole cost lever is the shared camera fps.</li>
     * </ul>
     *
     * <p>Serialized by callers under lifecycleSerializer; the underlying pipeline
     * setters are lockless volatile writes consumed by the GL thread.
     */
    // Dedicated lock for reconcileCameraProfile. NOT lifecycleSerializer and NOT
    // activationLock: reconcile is called both from the BS turn-tick thread
    // (bsVisibilityListener, holds no lock) and from inside deactivateMode (which
    // already holds activationLock) — routing reconcile through lifecycleSerializer
    // would create activationLock→lifecycleSerializer ordering against the normal
    // lifecycleSerializer→activationLock order and could deadlock. A dedicated
    // lock participates in no other ordering: it only guarantees the read-decide-
    // write sequence of one reconcile is atomic w.r.t. a peer reconcile (the
    // pipeline setters are volatile + idempotent, so cross-reconcile coherence is
    // the only requirement). Always innermost — reconcile takes no other lock.
    private final Object reconcileLock = new Object();

    private void reconcileCameraProfile() {
        // Serialize against peer reconciles so the setter group (lane/stride/
        // bitrate/fps) is applied coherently — never interleaved into an
        // incoherent combo (lane OFF + recording fps, or lane ON + 1 fps) when a
        // BS show/hide edge races a lifecycle transition. See reconcileLock.
        synchronized (reconcileLock) {
            reconcileCameraProfileLocked();
        }
    }

    // ==================== CAMERA STATE: SINGLE AUTHORITY ====================
    // The camera's runtime PROFILE (recorder lane on/off, global fps, and — for
    // continuous-style recording — stride/bitrate) is decided in ONE place:
    // desiredCameraState() computes the intent from a single priority ladder, and
    // reconcileCameraProfileLocked() is the ONLY writer that applies it. Every
    // trigger (BS show/hide, stream enable/disable, lifecycle edges, the 30s
    // resync tick) routes through reconcileCameraProfile() → this pair. This
    // replaced a set of ad-hoc reactive branches that each re-derived partial
    // state and drifted out of agreement (the "every audit round finds a new
    // combo" problem). Adding a new consumer = add ONE rung to the ladder here.
    //
    // SCOPE: this owns the PROFILE only, not pipeline START/STOP. Teardown stays
    // with the existing owners (mode deactivate gates + WS idle-shutdown +
    // ACC-off), which already gate on bsKeepWarmActive()/keepAlivePredicate — so
    // a profile reconcile can never tear down a live recording or surveillance.

    /** Immutable camera profile intent produced by {@link #desiredCameraState}. */
    private static final class CameraIntent {
        final boolean laneEnabled;   // recorder PASS 1A on/off
        final int fps;               // global camera HAL fps
        final boolean ownStrideBitrate; // true = set stride 1 + recording bitrate;
                                         // false = leave them to whoever owns them
                                         // (ProximityGuardController, or nobody for BS-only)
        final String why;            // for the log line
        CameraIntent(boolean laneEnabled, int fps, boolean ownStrideBitrate, String why) {
            this.laneEnabled = laneEnabled; this.fps = fps;
            this.ownStrideBitrate = ownStrideBitrate; this.why = why;
        }
    }

    /**
     * THE single source of truth for what the camera profile should be right now.
     * Pure (no side effects) — reads state, returns intent. Priority ladder, most
     * authoritative first:
     *
     *  1. RECORDING owns the camera — any RMM recording mode active, OR a live/
     *     deferred recording of any origin (manual, sentry, OEM via the shared
     *     recorder), OR an activation in flight. Lane ON, fps = max(recordingFps,
     *     streamFps). PROXIMITY_GUARD is special: its controller owns stride/bitrate
     *     (the low-power ring), so ownStrideBitrate=false for it.
     *  2. BLIND-SPOT keep-warm (enabled/debugPreview + ACC) — lane OFF (BS has no
     *     encoder), fps = max(bsIdle/Active, streamFps).
     *  3. STREAM-ONLY — a live-view stream is the only consumer. Lane OFF, fps =
     *     streamFps.
     *  4. NO OWNER — pipeline is up but nobody needs it (transient: BS just
     *     disabled, or an activation was rejected after a kept-alive switch).
     *     Restore a sane baseline (lane ON, recording fps) so the next consumer
     *     isn't starved; teardown of a truly idle pipeline is the other owners' job.
     */
    /**
     * Quiet-tier decision (issue #174): true when the parked-idle throttle is
     * active AND surveillance has been idle (no motion, not CONTINUOUS) for at
     * least {@code surveillanceQuietTierMinutes}. In the quiet tier the AI
     * motion-readback cadence steps down to {@code surveillanceQuietTierFps} Hz
     * and the idle-HAL parity floor relaxes to match, so a long parked-idle tail
     * stops paying the full ~10 Hz readback rate.
     *
     * <p>Single source of truth consulted by BOTH throttle sites
     * (desiredCameraState's parity floor and reconcileCameraProfileLocked's AI
     * interval) so they can never disagree within a reconcile pass. Because it
     * requires {@code !hasActiveSurveillanceMotion()}, the first motion edge
     * makes it false in the SAME reconcile that the engine pushes — so HAL fps
     * and AI cadence ramp back to full together, atomically.
     *
     * <p>{@code surveillanceQuietTierMinutes == 0} disables the tier (returns
     * false always), preserving the pre-#174 constant cadence.
     */
    private boolean isQuietTierActive() {
        int quietMin = UnifiedConfigManager.getSurveillanceQuietTierMinutes();
        if (quietMin <= 0) return false;
        if (!UnifiedConfigManager.isSurveillanceIdleThrottle()) return false;
        if (!pipeline.isSurveillanceMode() || pipeline.isContinuousSurveillance()) return false;
        if (pipeline.hasActiveSurveillanceMotion()) return false;
        return pipeline.getSurveillanceQuietDurationMs() >= quietMin * 60_000L;
    }

    /** Quiet-tier AI motion cadence (Hz), clamped so it can never exceed the idle
     *  HAL fps (the readback gate can only drop delivered frames, never create
     *  them). Shared by the parity floor and the AI-interval computation. */
    private int quietTierAiFps() {
        return Math.min(
                UnifiedConfigManager.getSurveillanceQuietTierFps(),
                UnifiedConfigManager.getSurveillanceIdleFps());
    }

    private CameraIntent desiredCameraState() {
        int streamFps = activeStreamFps();
        // Rung 1: recording (broadest ownership — see pipelineIsRecording()).
        boolean activationInFlight = activatingCameraOwner
                && (currentMode == Mode.CONTINUOUS
                    || currentMode == Mode.DRIVE_MODE
                    || currentMode == Mode.PROXIMITY_GUARD);
        if (recordingModeOwnsCamera() || pipelineIsRecording() || activationInFlight) {
            // PROXIMITY_GUARD claims fps ownership ONLY when its controller is
            // genuinely running (isActive() == not IDLE). Two independent config
            // keys can disagree: recording.mode can be PROXIMITY_GUARD while
            // proximityGuard.enabled is false — in which case the controller never
            // start()s, stays IDLE, and desiredCameraFps() returns 0. Without the
            // isActive() guard, a parked ACC-off SENTRY unit with mode=PROXIMITY_GUARD
            // would enter this branch, get proxFps=0 → configuredRecordingFps()
            // (full 15fps), and RETURN — shadowing the surveillance idle-throttle
            // below (surveillanceIdleFps) that would otherwise drop the HAL to the
            // low idle rate. The old comment "PROXIMITY_GUARD never runs parked" was
            // the false assumption behind this: a stale/disabled proximity mode DOES
            // sit here parked. Gating on isActive() lets that case fall through to
            // the idle throttle while a truly-running proximity controller still
            // owns the fps as before.
            boolean proximity = (currentMode == Mode.PROXIMITY_GUARD)
                    && proximityController != null
                    && proximityController.isActive();
            // activeConfiguredFps() picks the surveillance fps while the pipeline
            // sits in ACC-off SENTRY (pipelineIsRecording() counts surveillance as
            // an owner), else the ACC-on recording fps.
            int fps = Math.max(activeConfiguredFps(), streamFps);
            if (proximity) {
                // PROXIMITY detection is RADAR-driven, not camera-driven. While
                // the controller is MONITORING (the common parked-idle state) it
                // only needs the camera lightly warm to keep the pre-record ring
                // filling — so drop the GLOBAL camera HAL fps to the monitor rate
                // (measured: the OEM camera stack mm-qcamera-daemon was pinned at
                // ~50% of a little core capturing 15fps while ~3/4 of those frames
                // were stride-skipped anyway). The controller returns its desired
                // HAL fps: monitorFps (~4) while MONITORING, 0 ("no opinion = full
                // rate") while RECORDING/POST_RECORD so an event clip snaps back to
                // the configured recording fps. We still honor an active live-view
                // stream's floor. Leave stride/bitrate to the controller; it
                // re-derives its stride against this HAL fps via
                // reapplyMonitorProfileIfMonitoring (driven below on an fps change).
                int proxWant = (proximityController != null) ? proximityController.desiredCameraFps() : 0;
                int proxFps = (proxWant > 0) ? proxWant : configuredRecordingFps();
                // The shared camera must satisfy EVERY consumer, not just
                // proximity. While proximity sits MONITORING at the low ~4fps
                // monitor rate, a SHOWN blind-spot view (driver flicked the turn
                // signal) needs the BS active fps — otherwise the safety view the
                // driver is actively looking at would render at the choppy 4fps
                // monitor rate. Likewise a live-view stream needs its rate. So
                // take the max of all live demands. (When a radar event fires the
                // controller returns 0 => configuredRecordingFps, already >= BS.)
                int bsDemand = (bsKeepWarmActive() && bsViewShown())
                        ? UnifiedConfigManager.getBlindSpotActiveFps() : 0;
                int camFps = Math.max(proxFps, Math.max(streamFps, bsDemand));
                return new CameraIntent(true, camFps, false,
                        "recording:PROXIMITY (cam fps=" + camFps
                                + (proxWant > 0 ? " monitor" : " full")
                                + (bsDemand > 0 ? "+BS" : "") + (streamFps > 0 ? "+stream" : "")
                                + ", controller owns stride/bitrate)");
            }
            // Parked-idle surveillance throttle (default-OFF opt-in): while ARMED
            // surveillance sits idle (no active motion, not CONTINUOUS mode) drop
            // the shared camera HAL to the low idle fps to cut capture-rail +
            // compose/encode power. The recorder lane stays ON at stride 1 so the
            // pre-record ring keeps filling; detection parity is preserved because
            // reconcileCameraProfileLocked() co-sets the AI-lane wall-clock readback
            // interval so the motion cadence is held at today's rate regardless of
            // this HAL fps. On first-motion the engine pushes a reconcile
            // (onSurveillanceActivityChanged) → hasActiveSurveillanceMotion() flips
            // true → this branch is skipped → HAL ramps back to the full fps below.
            // streamFps floor is preserved so an open live-view is never starved.
            if (!proximity
                    && UnifiedConfigManager.isSurveillanceIdleThrottle()
                    && pipeline.isSurveillanceMode()
                    && !pipeline.isContinuousSurveillance()
                    && !pipeline.hasActiveSurveillanceMotion()) {
                // Self-enforcing parity floor: the wall-clock readback gate can only
                // DROP frames the HAL delivers, never manufacture them — so if the
                // idle HAL fps were below the motion cadence (survFps/aiReadbackModulo,
                // e.g. 15/3 = 5 Hz), motion detection would silently undersample vs
                // today. Clamp the idle HAL floor to that cadence so a user's
                // aggressive surveillanceIdleFps saves a little less power but NEVER
                // regresses detection. At the default 5/15 this floor is exactly 5,
                // matching the configured idle fps (no change).
                //
                // Quiet tier (issue #174): after a sustained no-motion period the AI
                // cadence steps down to quietTierAiFps(), so the floor relaxes to
                // that lower cadence — letting the HAL actually fall toward
                // surveillanceIdleFps. The invariant still holds (HAL fps ≥ AI
                // cadence): quietTierAiFps() is clamped ≤ surveillanceIdleFps, and
                // idleFps is ≥ surveillanceIdleFps below. On first motion isQuietTierActive()
                // flips false in this same pass, so the floor and the AI interval
                // both restore to the full cadence together.
                final int aiReadbackModulo = 3;  // == AiLaneGl.DEFAULT_READBACK_MODULO
                int motionCadenceFloor = isQuietTierActive()
                        ? quietTierAiFps()
                        : (int) Math.ceil(configuredSurveillanceFps() / (double) aiReadbackModulo);
                int idleFps = Math.max(
                        Math.max(UnifiedConfigManager.getSurveillanceIdleFps(), motionCadenceFloor),
                        streamFps);
                // Only actually throttle if idle fps is below the full rate;
                // otherwise fall through so we don't log a misleading "idle".
                if (idleFps < fps) {
                    return new CameraIntent(true, idleFps, true, "recording:surveillance-idle");
                }
            }
            // Continuous-style: own stride/bitrate (stride 1 + full bitrate).
            return new CameraIntent(true, fps, true, "recording:continuous-style");
        }
        // Rung 2: blind-spot keep-warm.
        if (bsKeepWarmActive()) {
            // A camera-view can now hold the shared lane WHILE blind-spot is enabled but
            // idle (the arbiter yields the lane when no turn signal is active, or when the
            // conditional speed/reverse gate suppresses the card). Those frames go to the
            // screen, so they need a real rate: without this the BS idle floor (~1fps)
            // would starve a visible camera-view down to a frozen image — the same
            // "overlay opens but shows nothing" failure rung 2b below exists to prevent.
            // Take the max of both demands rather than reordering the rungs, so a SHOWN
            // blind-spot card is never slowed down either.
            int bsFps = bsViewShown()
                    ? UnifiedConfigManager.getBlindSpotActiveFps()
                    : UnifiedConfigManager.getBlindSpotIdleFps();
            int camDemand = camViewKeepWarmActive()
                    ? UnifiedConfigManager.getBlindSpotActiveFps() : 0;
            int want = Math.max(bsFps, Math.max(camDemand, streamFps));
            return new CameraIntent(false, want, false,
                    "blind-spot keep-warm (view " + (bsViewShown() ? "SHOWN" : "hidden") + ")"
                    + (camDemand > 0 ? "+camview" : ""));
        }
        // Rung 2b: camera-view overlay (show-camera) is the consumer. Renders the pano onto
        // the native SurfaceControl lane exactly like a shown blind-spot view, so it needs a
        // real fps (BS active rate) — NOT the ~1fps idle rung, which is why the overlay
        // opened (✕ chrome) but showed no image. Recorder lane OFF (nothing records; the
        // frames go straight to the SurfaceControl layer).
        if (camViewKeepWarmActive()) {
            int camFps = UnifiedConfigManager.getBlindSpotActiveFps();
            return new CameraIntent(false, Math.max(camFps, streamFps), false, "camera-view overlay");
        }
        // Rung 3: live-view stream is the only consumer.
        if (streamFps > 0) {
            return new CameraIntent(false, streamFps, false, "stream-only");
        }
        // Rung 4: NO owner — the pipeline is up but nothing records, surveillance
        // isn't armed, blind-spot isn't keep-warm, no stream, no activation in
        // flight. Reachable transiently when BS was the sole consumer and got
        // disabled (its lane torn down but the pipeline kept alive), or an
        // activation was rejected after a kept-alive mode switch. Keep the
        // recorder lane OFF at a low idle fps: running PASS 1A (H.265 encode) for
        // a camera nobody consumes is pure Venus/GPU burn on the shared bus, and
        // there is NOT always a teardown owner for a BS-only-cold-started pipeline
        // (no WS idle timer exists unless a stream was opened), so a lane-ON
        // baseline would burn until the next ACC-off. The black-frame backstop is
        // preserved regardless: startRecording() unconditionally re-enables the
        // lane, and every RMM activation runs applyFullRecordingProfile first — so
        // the instant ANY consumer (record/sentry/stream/BS) appears, the
        // appropriate higher rung re-profiles the camera. Idle = cheapest safe
        // state for "nobody needs it."
        return new CameraIntent(false, UnifiedConfigManager.getBlindSpotIdleFps(), false,
                "no active consumer (idle)");
    }

    /** The ONLY writer of the camera profile. Always run under {@link #reconcileLock}.
     *  Applies {@link #desiredCameraState()}. No START/STOP here (see SCOPE note). */
    private void reconcileCameraProfileLocked() {
        try {
            if (!pipeline.isRunning()) return;
            CameraIntent want = desiredCameraState();
            // fps change detection for the proximity stride re-derive (below).
            int prevFps = pipeline.getCameraTargetFps();
            pipeline.setRecorderLaneEnabled(want.laneEnabled);
            pipeline.setCameraTargetFps(want.fps);
            // Parked-idle throttle: pin the AI motion-readback cadence to today's
            // rate (survFps/3 Hz → period 3000/survFps ms) via a wall-clock gate,
            // so the frame-counted native motion pipeline sees IDENTICAL cadence
            // whether the HAL is at the idle fps or the full fps — and across the
            // async ramp between them (no undersample transient). Applied for all
            // surveillance states (idle AND active) when the throttle is on;
            // cleared to 0 (legacy frame-count modulo) otherwise, keeping the OFF
            // path byte-identical. Excludes CONTINUOUS (no AI lane).
            if (UnifiedConfigManager.isSurveillanceIdleThrottle()
                    && pipeline.isSurveillanceMode()
                    && !pipeline.isContinuousSurveillance()) {
                int survFps = configuredSurveillanceFps();
                // Today's motion rate is survFps / AiLaneGl's readback modulo (3);
                // the target inter-sample period is its inverse in ms. The 3 must
                // stay in sync with AiLaneGl.DEFAULT_READBACK_MODULO (kept private
                // there; inlined here to avoid a cross-package import for one int).
                final int aiReadbackModulo = 3;
                // Apply ~20% jitter tolerance: at idle the HAL delivers frames at
                // ~the same period as the target (e.g. 200ms @5fps vs 200ms
                // target), so a strict >= would drop a frame that arrives a hair
                // early and halve the effective rate on that beat. Undershooting
                // the gate by 20% lets near-period frames through — the cadence
                // is then paced by the HAL delivery (5 Hz idle) as intended, and
                // at full fps the gate still yields ~5 Hz (200ms/66ms ≈ every 3rd).
                long targetMs = (survFps > 0)
                        ? Math.round(1000.0 * aiReadbackModulo / survFps * 0.8)
                        : 0L;
                // Quiet tier (issue #174): after a sustained no-motion period, widen
                // the gate to quietTierAiFps() Hz (e.g. 3 Hz → ~267ms with the same
                // 20% tolerance) so the AI lane stops doing ~10 Hz PBO readbacks all
                // night parked. Cadence stays CONSTANT within the tier (invariant
                // preserved); the first motion event clears isQuietTierActive() in the
                // same reconcile, restoring the full-rate targetMs above alongside the
                // HAL ramp-up. Accepted tradeoff: worst-case first-motion detection
                // latency rises to ~1/quietTierAiFps() for that first frame only.
                if (isQuietTierActive()) {
                    int quietFps = quietTierAiFps();
                    if (quietFps > 0) {
                        targetMs = Math.round(1000.0 / quietFps * 0.8);
                    }
                }
                pipeline.setAiReadbackMinIntervalMs(targetMs);
            } else {
                pipeline.setAiReadbackMinIntervalMs(0L);
            }
            if (want.ownStrideBitrate) {
                pipeline.setRecorderFrameStride(1);
                // activeConfiguredBitrate() = surveillance tier while parked in
                // SENTRY, else the ACC-on recording tier. ownStrideBitrate is set
                // by the "recording:continuous-style" rung, which surveillance
                // reaches (via pipelineIsRecording); this is what keeps the live
                // bitrate on the surveillance tier even after a stream open/close
                // reconcile fires mid-parked-recording.
                pipeline.setRecordingBitrate(activeConfiguredBitrate());
            } else if (currentMode == Mode.PROXIMITY_GUARD && want.fps != prevFps
                    && proximityController != null) {
                // Proximity owns its stride, computed as round(cameraFps/monitorFps)
                // at MONITORING-entry. If we just CHANGED the camera fps (a stream
                // opened/closed), that stride is stale — ask the controller to
                // re-derive it (no-op unless it is currently MONITORING).
                proximityController.reapplyMonitorProfileIfMonitoring();
            }
            logger.info("Camera profile: " + want.why + " — lane "
                    + (want.laneEnabled ? "ON" : "OFF") + ", fps=" + want.fps
                    + (activeStreamFps() > 0 ? " (stream " + activeStreamFps() + ")" : ""));
        } catch (Throwable t) {
            logger.warn("reconcileCameraProfile failed: " + t.getMessage());
        }
    }

    /** True if the pipeline currently has ANY live OR DESIRED recording (RMM mode,
     *  manual, sentry, OEM, or a DEFERRED start waiting on encoder format/storage)
     *  feeding the recorder lane — so reconcile must never disable the lane or drop
     *  below recording fps. The pending/desired check matters because a manual or
     *  TCP start that DEFERS (encoder format not ready yet — the usual case when
     *  the camera was BS-only-warmed with the lane off, so the encoder was never
     *  fed) has recordingMode=true + pendingRecordingPrefix!=null but isRecording()
     *  is still false; without recognizing that, a BS-hide reconcile in the
     *  deferral window would drop the camera back to BS-idle ~1 fps and the
     *  deferred clip would record at 1 fps. Guarded against transient nulls. */
    private boolean pipelineIsRecording() {
        try {
            return pipeline.isRecording()
                    || pipeline.isNormalRecordingMode()
                    || pipeline.isRecordingMode()
                    || pipeline.getPendingRecordingPrefix() != null
                    // ARMED-IDLE SURVEILLANCE (ACC-off sentry between motion
                    // events): the sentry shares the primary recorder and relies
                    // on PASS 1A continuously feeding the pre-record ring so a
                    // motion trigger has its pre-event lead-in. It is NOT
                    // isRecording()/NORMAL_RECORDING while armed-idle, so without
                    // this term a live-view stream opened for remote monitoring
                    // would drop the camera to the stream-only rung (lane OFF) and
                    // starve the ring → empty pre-roll on the next event. Treating
                    // SURVEILLANCE as recording-owns keeps the lane ON.
                    || pipeline.isSurveillanceMode();
        } catch (Throwable t) {
            return false;
        }
    }

    /** The fps a currently-enabled live-view stream needs, or 0 if streaming is
     *  off. Used as a floor for the BS-only global-fps drop so the shared camera
     *  never starves an active stream lane. */
    private int activeStreamFps() {
        try {
            return pipeline.getActiveStreamFps();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Assert the FULL recording baseline on the shared camera: recorder lane ON,
     * draw stride 1, configured recording bitrate, and global camera fps at the
     * user's configured recording rate. Called at the start of every recording-
     * mode activation BEFORE startRecording (CONTINUOUS/DRIVE) or BEFORE
     * proximityController.start() (PROXIMITY) so that:
     *   - if the camera was kept warm ONLY for blind-spot, the recorder lane
     *     (which BS turned OFF) and the global fps (which BS dropped to ~1) are
     *     restored before any frame is recorded — otherwise the first frames are
     *     BS-idle quality, AND proximity's monitor-stride math (cameraFps /
     *     monitorFps) would be computed against the stale 1 fps and under-feed
     *     the pre-record ring;
     *   - proximity then layers its own low-power MONITORING stride/bitrate on
     *     top of this full-rate baseline.
     * No-op-safe: all setters are cheap volatile writes / sub-frame MediaCodec
     * params, no codec reconfigure. Guarded so a missing camera can't throw.
     */
    private void applyFullRecordingProfile() {
        try {
            pipeline.setRecorderLaneEnabled(true);
            pipeline.setRecorderFrameStride(1);
            // Mode-aware: surveillance tier while parked in SENTRY, else the
            // ACC-on recording tier. This method's callers are ACC-on activation
            // paths (isSurveillanceMode() == false there), so this is
            // byte-identical for them; the branch just keeps it correct if ever
            // reached under surveillance.
            pipeline.setRecordingBitrate(activeConfiguredBitrate());
            // Floor at the live-view stream fps: the stream (PASS 1B) shares the
            // one camera, so the camera HAL rate must satisfy max(recording,
            // stream) or a SMOOTH(25)/MAX(30) stream opened during recording (incl.
            // PROXIMITY, whose reconcile branch leaves fps to the controller) would
            // play at the lower recording rate. The recorder draw-stride still
            // sub-samples the recorder lane to the record rate; proximity then
            // layers its MONITORING stride on top (recomputed against this fps).
            int recFps = Math.max(activeConfiguredFps(), activeStreamFps());
            pipeline.setCameraTargetFps(recFps);
        } catch (Throwable t) {
            logger.warn("applyFullRecordingProfile failed: " + t.getMessage());
        }
    }

    /** User-configured recording fps from unified config (BYD-supported {8,15,25},
     *  clamped to 15 by the settings API). Falls back to 15. */
    private int configuredRecordingFps() {
        try {
            org.json.JSONObject cam = UnifiedConfigManager.loadConfig().optJSONObject("camera");
            if (cam != null) return cam.optInt("targetFps", 15);
        } catch (Throwable ignored) {}
        return 15;
    }

    /** User-configured ACC-off SURVEILLANCE fps (camera.surveillanceTargetFps).
     *  Falls back to the ACC-on targetFps when unset — byte-identical to the
     *  pre-split world. Only consulted when the pipeline is in surveillance
     *  mode; every ACC-on rung uses {@link #configuredRecordingFps()}. */
    private int configuredSurveillanceFps() {
        try {
            org.json.JSONObject cam = UnifiedConfigManager.loadConfig().optJSONObject("camera");
            if (cam != null) {
                int accOnFallback = cam.optInt("targetFps", 15);
                return cam.optInt("surveillanceTargetFps", accOnFallback);
            }
        } catch (Throwable ignored) {}
        return 15;
    }

    /** The active recording flow's configured fps: surveillance fps while the
     *  pipeline sits in ACC-off SENTRY, else the ACC-on recording fps. This is
     *  the single chokepoint both the reconcile ladder and the full-profile
     *  baseline read, so the two never disagree about which fps the current
     *  mode wants. */
    private int activeConfiguredFps() {
        return pipeline.isSurveillanceMode()
            ? configuredSurveillanceFps()
            : configuredRecordingFps();
    }

    /** The active recording flow's effective bitrate: surveillance tier while
     *  the pipeline sits in ACC-off SENTRY, else the ACC-on recording tier.
     *  Both honor an active thermal/network customBitrate. Mirrors
     *  {@link #activeConfiguredFps()} so fps and bitrate always describe the
     *  SAME mode. */
    private int activeConfiguredBitrate() {
        return pipeline.isSurveillanceMode()
            ? pipeline.getEffectiveSurveillanceBitrate()
            : pipeline.getConfiguredRecordingBitrate();
    }

    /** Whether the blind-spot view is currently being shown on screen (turn
     *  signal active / debug preview). Drives the BS idle↔active fps ramp. */
    private boolean bsViewShown() {
        try {
            return pipeline.isBlindSpotLayerVisible();
        } catch (Throwable t) {
            return false;
        }
    }
    
    // ==================== CONFIG PERSISTENCE ====================
    
    // Cached reflection for queryAccStateFromHardware. The periodic resync
    // calls this every 30s; without caching, each call does Class.forName +
    // 2x Class.getMethod (linear scans of the method table) on a constrained
    // head-unit. Resolving once at class-init and reusing keeps the per-tick
    // cost to two reflective Method.invoke() calls. Volatile for safe
    // publication; we accept the rare double-resolve race (idempotent).
    private static volatile Class<?> bodyworkDeviceClass;
    private static volatile java.lang.reflect.Method bodyworkGetInstanceMethod;
    private static volatile java.lang.reflect.Method bodyworkGetPowerLevelMethod;
    private static volatile boolean bodyworkReflectionResolved = false;
    private static volatile boolean bodyworkReflectionFailed = false;

    private static void resolveBodyworkReflection() {
        if (bodyworkReflectionResolved || bodyworkReflectionFailed) return;
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            java.lang.reflect.Method getInstance = cls.getMethod("getInstance", android.content.Context.class);
            java.lang.reflect.Method getPowerLevel = cls.getMethod("getPowerLevel");
            bodyworkDeviceClass = cls;
            bodyworkGetInstanceMethod = getInstance;
            bodyworkGetPowerLevelMethod = getPowerLevel;
            bodyworkReflectionResolved = true;
        } catch (Exception e) {
            // Permanent failure (class or method genuinely not present) —
            // record so we skip the lookup on every subsequent call. The
            // BYD HAL surface is fixed at boot; if it's missing now, it'll
            // still be missing in 30s.
            bodyworkReflectionFailed = true;
            logger.debug("BYDAutoBodyworkDevice reflection unavailable: " + e.getMessage());
        }
    }

    /**
     * Query ACC state directly from BYD hardware.
     * Falls back to AccMonitor if hardware query fails or returns a
     * sentinel value indicating "state is unreliable".
     *
     * BYDAutoBodyworkDevice power-level constants (from the HAL header):
     *   0 = OFF
     *   1 = ACC (key in accessory position)
     *   2 = ON  (engine off, ignition on)
     *   3 = OK  (engine running)
     *   4 = FAKE_OK     (HAL is bluffing — treat as untrustworthy)
     *   255 = INVALID   (HAL doesn't know — treat as untrustworthy)
     *
     * The previous `level >= 2` check classified BOTH FAKE_OK and INVALID as
     * ACC=ON, which would incorrectly auto-activate recording when the HAL
     * was reporting "I don't know." Tightened to accept only the four
     * legitimate values; anything else falls through to AccMonitor (which
     * holds whatever AccSentryDaemon last pushed via IPC — the most
     * reliable cross-process source of truth on this head-unit).
     *
     * Note: the HAL singleton itself is queried fresh on every call — only
     * the JVM reflection metadata (Class + Method refs) is cached. So if
     * the BYD service binder restarts between ticks, we get the freshly
     * reconnected device on the next tick automatically.
     */
    private boolean queryAccStateFromHardware() {
        if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            boolean isAccOff = com.overdrive.app.monitor.AccMonitor.probeAccState(context);
            return !isAccOff;
        }
        resolveBodyworkReflection();
        if (bodyworkReflectionResolved) {
            try {
                int level = readPowerLevelOnce();
                // Only trust the four legitimate states. INVALID (255),
                // FAKE_OK (4), or any unexpected value → fall through to
                // the IPC-backed AccMonitor.
                if (level >= 0 && level <= 3) {
                    boolean isOn = level >= 2;
                    logger.debug("Hardware power level: " + level + " (ACC " + (isOn ? "ON" : "OFF") + ")");
                    // Write-through to AccMonitor so the daemon-wide
                    // static reflects the truth: peer code paths that
                    // read AccMonitor.isAccOn() (e.g., NotificationGate)
                    // would otherwise see a stale value if AccSentry
                    // crashed and never delivered an IPC. Definitive
                    // hardware reads (0..3) are authoritative; sentinel
                    // values (4 / 255) intentionally fall through to
                    // the existing AccMonitor read below.
                    //
                    // FIX (rmm: queryAccStateFromHardware write-through
                    // races AccSentry IPC + acc-sentry: accOnAuthoritative
                    // flag is dead code): gate the write-through on
                    // (a) AccMonitor not yet authoritative (no IPC has
                    // landed) OR (b) probe disagrees with current
                    // AccMonitor value. If AccSentry has spoken AND we
                    // agree, skip the write — avoids stamping a stale
                    // level=0 mid-edge over a just-written true. Wires
                    // the previously-dead accOnAuthoritative gate.
                    boolean ipcAuthoritative =
                        com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative();
                    boolean ipcCurrent =
                        com.overdrive.app.monitor.AccMonitor.isAccOn();

                    // FIX (spurious projection close — "projection ends on its
                    // own"): a SINGLE transient genuine-low power-level read
                    // (level 0/1) that CONTRADICTS an authoritative ACC=ON must
                    // NOT dispatch an ACC-OFF edge. setAccState(false) here
                    // flows through AccMonitor.notifyAccEdge → ClusterProjection
                    // Controller.forceCloseIfActive("acc-off"), which tears down
                    // a SUSTAINED cluster-map projection (and a held turn-signal
                    // projection) and restores the gauges — a manually-launched
                    // map is then NOT re-acquired, so it stays dead for the rest
                    // of the drive. The HAL power level momentarily dips during
                    // normal driving; the authoritative ACC source is AccSentry's
                    // sys.accanim.status (shutdown-animation flag, which does not
                    // flap), so this resync probe is the only flapping ACC-edge
                    // source. Confirm a contradicting OFF across a couple of
                    // re-reads before trusting it; a REAL power-down stays low,
                    // so the gauge-restore on a genuine ACC-off is delayed only
                    // by ~ACC_OFF_CONFIRM_RETRIES × ACC_OFF_CONFIRM_SPACING_MS
                    // (≤400ms). Only the DISAGREES-with-authoritative-ON case is
                    // debounced; an agreeing read, a non-authoritative AccMonitor
                    // (post-restart stale-false, the NotificationGate case the
                    // write-through was added for), and a confirmed OFF all
                    // write through unchanged. NEVER applied to the AccSentry IPC
                    // path (CameraDaemon.onAccStateChanged), which stays immediate.
                    if (ipcAuthoritative && ipcCurrent && !isOn) {
                        if (!confirmHardwareAccOff()) {
                            logger.info("HW probe read ACC-OFF (level=" + level
                                + ") contradicting authoritative ACC-ON, but it did "
                                + "NOT confirm across re-reads — treating as a "
                                + "transient dip, keeping ACC-ON (no spurious edge)");
                            return true;   // authoritative ON stands
                        }
                        logger.info("HW probe ACC-OFF CONFIRMED across re-reads "
                            + "(was authoritative ON) — write-through");
                        com.overdrive.app.monitor.AccMonitor.setAccState(false);
                        dispatchProbedAccEdgeIfMeaningful(true, "rmm-hw-probe-confirmed-off");
                        return false;
                    }

                    if (!ipcAuthoritative || ipcCurrent != isOn) {
                        if (ipcAuthoritative) {
                            logger.info("HW probe disagrees with AccMonitor (ipc="
                                + ipcCurrent + ", hw=" + isOn + ", level=" + level
                                + ") — write-through");
                        }
                        com.overdrive.app.monitor.AccMonitor.setAccState(isOn);
                    } else {
                        logger.debug("HW probe agrees with authoritative AccMonitor — skipping write-through");
                    }
                    // FIX (surveillance never auto-arms on park, observed 2026-07-28
                    // log_X7RYXG6B; retryability hardened per audit R1 2026-07-29):
                    // AccMonitor.setAccState alone updates the static + fires
                    // notifyAccEdge (cluster/mirror teardown) but does NOT run
                    // CameraDaemon's ACC chain, which is where the ENTIRE
                    // sentry-arming path lives (safe-zone / schedule gates,
                    // startSentryPipeline, arm-mode branch, door-lock gate, schedule
                    // checker). When AccSentryDaemon stops delivering the ACC-OFF
                    // IPC, this probe is the only component that still sees the park
                    // — without this dispatch RMM tears the pipeline down for
                    // mode=NONE and surveillance never re-arms for the whole park.
                    //
                    // CRITICAL PLACEMENT: this runs on EVERY definitive reading,
                    // OUTSIDE the write-through branches above — deliberately
                    // including the "HW probe agrees with AccMonitor" case. The
                    // write-through only fires while AccMonitor disagrees with
                    // hardware, and setAccState immediately erases that
                    // disagreement; so a dispatch attached to the write-through was
                    // attempted exactly once and, if it was dropped (dispatch
                    // already in flight — the ACC chain can hold that gate for tens
                    // of seconds), the edge was lost FOREVER, with
                    // lastDispatchedAccIsOff latched to the opposite state so the
                    // IPC dedup then suppressed every later edge too. Hanging the
                    // call here instead makes each 30s tick a fresh retry:
                    // dispatchProbedAccEdge gates on lastDispatchedAccIsOff (what
                    // the CHAIN has processed), so it self-heals and is a cheap
                    // no-op on every steady-state tick.
                    dispatchProbedAccEdgeIfMeaningful(!isOn,
                        ipcAuthoritative ? "rmm-hw-probe" : "rmm-hw-probe-no-ipc-yet");
                    return isOn;
                }
                // Sentinel / out-of-range — log and fall through.
                logger.debug("Hardware power level=" + level
                        + " (sentinel/unknown — falling back to AccMonitor)");
            } catch (Exception e) {
                // Per-call failure (e.g., device service died briefly) — fall
                // through to AccMonitor. Don't mark reflection failed; the
                // resolution worked, this is a runtime invocation problem.
                logger.debug("Hardware ACC query failed: " + e.getMessage());
            }
        }

        // Fallback to AccMonitor (last IPC-pushed value from AccSentryDaemon)
        return com.overdrive.app.monitor.AccMonitor.isAccOn();
    }

    /**
     * Hand a probe-discovered ACC edge to {@code CameraDaemon}'s full ACC chain
     * (the only place sentry arming lives), unless this probe is the
     * constructor's own boot seed.
     *
     * <p>CONSTRUCTOR GUARD — the reason this wrapper exists rather than calling
     * {@code CameraDaemon.dispatchProbedAccEdge} directly: our constructor calls
     * {@code queryAccStateFromHardware()} to seed {@code accIsOn} BEFORE
     * {@code CameraDaemon.recordingModeManager} has been assigned (see
     * CameraDaemon:363 / :2948 — the field is written with the constructor's
     * return value). Dispatching then would drive the ACC chain, which calls
     * {@code recordingModeManager.onAccStateChanged(...)}, against a null field
     * (side-effects silently skipped, plus a "rmm null" warn) or — worse, if the
     * ordering ever changed — against a half-constructed manager whose
     * {@code pipeline}/{@code proximityController} refs aren't wired yet.
     *
     * <p>Requiring our own instance to be the published one makes the boot probe
     * a no-op and keeps CameraDaemon's existing boot {@code RECOVERY:} probe
     * (CameraDaemon:1255) the single owner of the cold-start dispatch — it runs
     * AFTER construction and already handles the ACC-ON / rmm-null cases.
     * Steady-state probes (resync ticker, setMode) all run post-publication and
     * dispatch normally.
     */
    private void dispatchProbedAccEdgeIfMeaningful(boolean accIsOff, String reason) {
        try {
            if (CameraDaemon.getRecordingModeManager() != this) {
                // Boot seed from our own constructor (field not yet assigned), or
                // a superseded manager instance after a context recreate. Either
                // way this instance must not drive daemon-wide lifecycle.
                logger.debug("Probe ACC edge (" + reason + ") not dispatched — "
                    + "manager not published yet (boot seed) or superseded");
                return;
            }
            if (CameraDaemon.dispatchProbedAccEdge(accIsOff, reason)) {
                // The daemon chain now owns this edge and will call our own
                // onAccStateChanged() as part of it. Record that so
                // resyncFromHardware does NOT additionally dispatch its local
                // ACC handler for the same edge — on ACC-OFF that duplicate is
                // actively harmful: the chain arms sentry (pipeline running),
                // then a late local runActivateGuarded(NONE, "acc-off") would
                // stop the pipeline we just armed, reproducing the very
                // "surveillance not enabled" symptom this fix targets.
                probeEdgeHandedOffToDaemon = accIsOff ? Boolean.TRUE : Boolean.FALSE;
            }
        } catch (Throwable t) {
            // Never let the dispatch hook break the probe's return value — the
            // caller still needs the ACC reading it asked for.
            logger.warn("Probe ACC edge dispatch failed (" + reason + "): " + t.getMessage());
        }
    }

    /**
     * Set by {@link #dispatchProbedAccEdgeIfMeaningful} when CameraDaemon's ACC
     * chain accepted a probe-discovered edge and will therefore invoke our
     * {@link #onAccStateChanged} itself. Consumed (and cleared) by
     * {@link #resyncFromHardware} so the same edge isn't handled twice.
     *
     * <p>Holds the {@code accIsOff} value that was handed off so a stale flag
     * can never suppress a DIFFERENT, later edge. Volatile: written on the
     * probing thread, read on the resync ticker (usually the same thread, but
     * setMode probes from HTTP threads too).
     */
    private volatile Boolean probeEdgeHandedOffToDaemon = null;

    /** Single raw power-level read via the cached bodywork reflection, or -1 on
     *  failure. Caller interprets 0..3 as definitive (isOn = level>=2) and
     *  4/255/other as sentinel. Split out so {@link #confirmHardwareAccOff} can
     *  re-poll without re-running the dispatch/write-through logic. */
    private int readPowerLevelOnce() throws Exception {
        Object device = bodyworkGetInstanceMethod.invoke(null, context);
        if (device == null) return -1;
        return (Integer) bodyworkGetPowerLevelMethod.invoke(device);
    }

    /** Re-read the HAL power level a few times to confirm a genuine ACC-OFF that
     *  contradicts an authoritative ACC-ON, rejecting a single transient dip
     *  (the "projection ends on its own" cause). Returns true only if a
     *  definitive OFF read (level 0/1) persists across the re-reads; a single ON
     *  (level≥2) or a recovered/sentinel read short-circuits to false (keep ON).
     *  Total added latency ≤ {@link #ACC_OFF_CONFIRM_RETRIES} ×
     *  {@link #ACC_OFF_CONFIRM_SPACING_MS}. */
    private boolean confirmHardwareAccOff() {
        for (int i = 0; i < ACC_OFF_CONFIRM_RETRIES; i++) {
            try {
                Thread.sleep(ACC_OFF_CONFIRM_SPACING_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;   // don't dispatch OFF on an interrupt — keep ON
            }
            try {
                int level = readPowerLevelOnce();
                if (level >= 0 && level <= 3) {
                    if (level >= 2) {
                        logger.debug("confirmHardwareAccOff: re-read " + (i + 1)
                            + " came back ON (level=" + level + ") — transient dip");
                        return false;
                    }
                    // still a definitive OFF — keep confirming
                } else {
                    // sentinel / unreadable — inconclusive; do not confirm OFF
                    logger.debug("confirmHardwareAccOff: re-read " + (i + 1)
                        + " sentinel/unreadable (level=" + level + ") — inconclusive");
                    return false;
                }
            } catch (Exception e) {
                logger.debug("confirmHardwareAccOff: re-read failed (" + e.getMessage()
                    + ") — inconclusive, keeping ON");
                return false;
            }
        }
        return true;   // OFF persisted across every re-read
    }

    /** Confirmation re-reads for a contradicting ACC-OFF (see {@link
     *  #confirmHardwareAccOff}). 2 re-reads × 180ms ≈ 360ms worst-case added
     *  latency on a GENUINE ACC-off — short enough that gauge-restore stays
     *  prompt, long enough to ride out a momentary HAL power-level dip. */
    private static final int ACC_OFF_CONFIRM_RETRIES = 2;
    private static final long ACC_OFF_CONFIRM_SPACING_MS = 180L;

    private void loadPersistedMode() {
        try {
            JSONObject recording = UnifiedConfigManager.getRecording();
            String modeStr = recording.optString("mode", "NONE");
            
            try {
                currentMode = Mode.valueOf(modeStr.toUpperCase());
                logger.info("Loaded persisted mode: " + currentMode);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid persisted mode: " + modeStr + ", using NONE");
                currentMode = Mode.NONE;
            }
        } catch (Exception e) {
            logger.error("Failed to load persisted mode: " + e.getMessage());
            currentMode = Mode.NONE;
        }
    }
    
    private void persistMode(Mode mode) {
        try {
            JSONObject recording = UnifiedConfigManager.getRecording();
            recording.put("mode", mode.name());
            UnifiedConfigManager.setRecording(recording);
            logger.debug("Persisted mode: " + mode);
        } catch (Exception e) {
            logger.error("Failed to persist mode: " + e.getMessage());
        }
    }
    
    /**
     * Reload configuration (call when config changes).
     *
     * <p>Holds the manager monitor only while reading/writing internal mode
     * fields; the proximityController callback runs unlocked so a long
     * settings reload (UnifiedConfigManager hits disk) can't pin the
     * monitor and starve the resync ticker.
     *
     * <p>Bails early if {@link #shuttingDown} — a late HTTP settings POST
     * after shutdown shouldn't reach into a torn-down proximityController.
     */
    public void reloadConfig() {
        if (shuttingDown) {
            logger.info("reloadConfig skipped — manager is shutting down");
            return;
        }
        synchronized (this) {
            loadPersistedMode();
        }
        if (proximityController != null) {
            proximityController.reloadConfig();
        }
        logger.info("Config reloaded: mode=" + currentMode);
    }
    
    /**
     * Shutdown and cleanup resources.
     */
    public void shutdown() {
        logger.info("Shutting down RecordingModeManager...");

        // Set the abort flag BEFORE acquiring lifecycleSerializer. Any peer
        // setMode/onAccStateChanged/onGearChanged that's already queued on
        // lifecycleSerializer or activationLock will see this flag the
        // moment it acquires the lock, abort, and not resurrect the
        // pipeline we're about to tear down.
        shuttingDown = true;

        // Release the boot-stable latch so the BootAutoActivate worker
        // (if it's still in its 5s wait) wakes up promptly, sees
        // shuttingDown, and exits — instead of sleeping out the full cap.
        bootStableSignal.countDown();

        // Stop the periodic resync ticker first so it can't fire one more
        // resyncFromHardware() against a torn-down pipeline. The thread
        // is daemon-flagged so it won't block process exit anyway, but
        // signaling cleanly avoids a noisy "pipeline already stopped"
        // log on shutdown.
        resyncTickerRunning = false;
        Thread t = resyncTickerThread;
        if (t != null) {
            t.interrupt();
            resyncTickerThread = null;
        }

        // Take lifecycleSerializer so any in-flight setMode/onAcc/onGear
        // call (which holds it) finishes — its already-staged activate
        // will be aborted by the shuttingDown check inside runActivateGuarded.
        synchronized (lifecycleSerializer) {
            Mode toDeactivate = currentMode;
            // Direct activationLock acquisition — runDeactivateGuarded would
            // also work, but we want to deactivate even if shuttingDown is
            // set (which it is). The deactivate path doesn't gate on it.
            synchronized (activationLock) {
                deactivateMode(toDeactivate);
                // Stop AVC keep-alive INSIDE the activationLock block so a
                // peer activate that was queued behind us can't run
                // startAvcKeepAliveIfNeeded() AFTER our stop and leave the
                // keep-alive watchdog running post-shutdown. The peer is
                // already gated by `shuttingDown` and will abort, but
                // ordering this stop inside the lock makes the invariant
                // hold even if a future change relaxes that gate.
                CameraDaemon.stopAvcKeepAlive();
            }
        }
        if (proximityController != null) {
            proximityController.shutdown();
        }
        logger.info("RecordingModeManager shutdown complete");
    }
}
