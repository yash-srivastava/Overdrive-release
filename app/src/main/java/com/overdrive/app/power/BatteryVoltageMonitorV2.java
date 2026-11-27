package com.overdrive.app.power;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.PowerManager;

import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.logging.DaemonLogger;


/**
 * 12V-voltage-driven MCU sleep/wake state machine.
 *
 * <p>Direct port of the sibling-app routine. The contract:
 *
 * <ul>
 *   <li>Listens for {@code AbsBYDAutoOtaListener.onBatteryPowerVoltageChanged(double)}
 *       on the runtime {@code BYDAutoOtaDevice} (12V battery voltage in volts).</li>
 *   <li>Hysteresis: voltage {@code > 12.5V} after a period of being below it
 *       counts as "high" — schedules an MCU sleep request after {@code 15 min}.
 *       Voltage {@code <= 12.0V} after MCU was sleeping counts as "low recover" —
 *       wakes the MCU and re-arms the cycle.</li>
 *   <li>Holds a {@code PARTIAL_WAKE_LOCK} tagged {@code "RemoteMonitorWakeLock"}
 *       for up to {@code 600s} during the wake-recover window.</li>
 *   <li>Re-arm cadence is {@code 60s}. The MCU sleep request is deferred by
 *       {@code 15 min} from the last wake event so the head unit has a chance
 *       to do work before the next sleep.</li>
 * </ul>
 *
 * <p>This replaces the older MCU pulse loop, which woke the MCU every
 * 45 s while ACC=OFF — counterproductive on a parked car (no alternator load,
 * just drains the 12V faster). The new model lets the MCU stay asleep when
 * the 12V is healthy and only intervenes on sustained low voltage.
 *
 * <p>Lifecycle: {@link #startMonitor(Context)} from {@code enterSentryMode},
 * {@link #stopMonitor()} from {@code exitSentryMode}.
 */
public final class BatteryVoltageMonitorV2 {

    private static final String TAG = "BatteryVoltageMonitorV2";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Voltage above which we consider 12V healthy enough to allow MCU sleep. */
    private static final double SLEEP_ALLOW_VOLTAGE = 12.5;

    /** Voltage at-or-below which we wake the MCU to recover. */
    private static final double WAKE_TRIGGER_VOLTAGE = 12.0;

    /** Re-arm cadence for the periodic monitor tick. */
    private static final long REARM_INTERVAL_MS = 60_000L;

    /** Defer interval for the next MCU sleep request after a wake. */
    private static final long MCU_SLEEP_DEFER_MS = 15 * 60_000L;

    /** Wake-lock acquisition window. */
    private static final long WAKE_LOCK_TIMEOUT_MS = 600_000L;

    /** Wake-lock tag — kept to match the sibling-app trace for log-correlation. */
    private static final String WAKE_LOCK_TAG = "RemoteMonitorWakeLock";

    // Handler messages
    private static final int MSG_SCHEDULE_MONITOR = 1;
    private static final int MSG_DEFERRED_MCU_SLEEP = 3;
    private static final int MSG_WAKEUP_LOOP = 7;
    private static final int MSG_VOLTAGE_SAMPLE = 8;

    private static volatile boolean running = false;
    private static volatile boolean isWakeupMcu = true;
    private static volatile double lastPowerVoltage = -1.0;
    private static volatile double highPowerVoltage = -1.0;
    private static volatile long lastSleepTime = 0L;
    private static volatile long lastWakeAttemptElapsedMs = -REARM_INTERVAL_MS;
    private static volatile long lastWakeEvaluationElapsedMs = -REARM_INTERVAL_MS;
    private static volatile boolean lowVoltageEpisodeWakeIssued = false;

    /**
     * Live HAL callbacks can arrive on collector/Binder threads while the
     * periodic seed runs on this class's handler. Keep the state machine
     * single-threaded by coalescing callback samples into one handler message.
     * The high-water sample is retained separately so coalescing cannot erase
     * a healthy-voltage observation.
     */
    private static final Object voltageQueueLock = new Object();
    private static boolean voltageMessageQueued = false;
    private static double pendingLatestVoltage = Double.NaN;
    private static double pendingHighVoltage = -1.0;
    private static boolean pendingNonHealthyVoltage = false;
    private static final java.util.concurrent.atomic.AtomicLong
            nonHealthyVoltageEpoch =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private static volatile long deferredSleepNonHealthyEpoch = 0L;

    /**
     * Monotonic lifecycle fence for delayed/in-flight sleep writes.
     *
     * <p>{@link HandlerThread#quitSafely()} cannot cancel a callback that is
     * already inside a vendor Binder call. A stopped session must therefore
     * remain distinguishable from a rapidly-started replacement session; a
     * boolean {@link #running} alone has an ABA window (true → false → true).
     */
    private static final java.util.concurrent.atomic.AtomicLong
            monitorLifecycleEpoch =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private static volatile long deferredSleepLifecycleEpoch = -1L;
    private static volatile long uncompensatedTerminalSleepEpoch = -1L;

    private static HandlerThread handlerThread;
    private static Handler handler;
    private static Object otaListener;       // AbsBYDAutoOtaListener instance
    private static Object powerListener;     // AbsBYDAutoPowerListener instance
    private static PowerManager.WakeLock wakeLock;

    /**
     * Caller-supplied context kept for re-acquiring the wake-lock during
     * {@link #forceWake()}. Don't reach into another daemon's static
     * accessor — V2 must work in whichever process boots it.
     */
    private static volatile Context appContext;

    private BatteryVoltageMonitorV2() {}

    /**
     * "Keep USB powered while parked" toggle (surveillance.keepUsbPowerOnAccOff,
     * default true). Mirrors {@code AccSentryDaemon.isKeepUsbPowerOnAccOff()}
     * EXACTLY — same key, same safe-default-true on any read failure — so the
     * two subsystems can never disagree about the user's intent. Read fresh on
     * each sleep decision (cheap, mtime-gated loadConfig; the value can't change
     * mid-park anyway since the daemon reads it once at ACC-OFF setup).
     *
     * <p>WHY THIS GATES MCU SLEEP: on the affected hardware the USB-bridged SD
     * reader's power rail follows the MCU/AP wake state. When the user asks to
     * keep USB powered, this monitor MUST NOT issue its voltage-driven MCU sleep
     * (the healthy-battery {@code doMcuSleep} path) — doing so drops USB/SD power
     * ~15 min into a park even though the toggle is ON. This restored the
     * pre-v22.2 behaviour where parked USB/SD stayed powered. The low-voltage
     * {@link #forceWake} recovery and the separate {@code SocCutoffMonitor}
     * (&le;10% SoC) remain the battery-safety floor regardless of this toggle.
     */
    private static boolean isKeepUsbPowerOnAccOff() {
        try {
            org.json.JSONObject s = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("surveillance");
            if (s == null) return true;
            return s.optBoolean("keepUsbPowerOnAccOff", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * True when the user has selected DiLink 4 (byd_apa) camera mode.
     *
     * <p>Uses the cross-process active-mode fence rather than the newly saved
     * configuration, so an aborted mode restart cannot arm legacy MCU sleep.
     */
    private static boolean isDilink4CameraMode() {
        try {
            return com.overdrive.app.camera.dilink5.DiLink5Platform
                    .isDiLink4Selected();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isDilink5Mode() {
        return com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
    }

    public static synchronized void startMonitor(Context context) {
        // GATE (defense-in-depth): in "Vehicle ON only" mode this voltage-driven
        // MCU sleep/wake monitor — a purely post-vehicle-OFF battery-management loop
        // that holds its own recovery-window wakelock — must never arm. Today the only
        // caller is AccSentryDaemon's SentrySetup worker, which G1 already skips in
        // onOnly; this guard makes the monitor self-safe against any future/respawn
        // caller. Placed before the `running` check so no wakelock is ever acquired.
        if (com.overdrive.app.config.UnifiedConfigManager.isVehicleOnOnlyMode()) {
            logger.info("startMonitor: onOnly mode — voltage-driven keep-awake/MCU-sleep monitor disabled");
            return;
        }
        if (running) {
            logger.info("startMonitor: already running");
            return;
        }
        appContext = context;
        // Hand the context to McuPowerHal so its sentry-mode writes
        // (BYDAutoSpecialDevice 1901/1902) can resolve the device.
        McuPowerHal.setAppContext(context);

        handlerThread = new HandlerThread("BatteryVoltageMonitorV2");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper(), BatteryVoltageMonitorV2::onMessage);
        resetSessionState();
        monitorLifecycleEpoch.incrementAndGet();
        // Publish running only after the handler and all session state exist.
        // notifyBatteryPowerVoltage() reads this volatile before reading handler.
        running = true;
        logger.info("startMonitor: BEGIN");

        registerOtaListener();
        registerPowerListener();

        // Seed and arm from the handler thread. Device getters are synchronous
        // Binder calls and must never run on a HAL callback or lifecycle thread.
        handler.sendEmptyMessage(MSG_SCHEDULE_MONITOR);

        // Acquire the recovery-window wake-lock.
        acquireWakeLock(context);
    }

    public static synchronized void stopMonitor() {
        stopMonitorInternal(false);
    }

    /**
     * Terminal variant used only when the caller is deliberately putting the
     * head unit to sleep and terminating every daemon. A sleep Binder write
     * already in flight for this exact monitor epoch must not be followed by
     * the normal compensating wake after {@code goToSleep()}.
     */
    public static synchronized void stopMonitorForShutdown() {
        stopMonitorInternal(true);
    }

    private static void stopMonitorInternal(boolean terminalSleep) {
        if (!running) return;
        running = false;
        long stoppedEpoch = monitorLifecycleEpoch.get();
        if (terminalSleep) {
            uncompensatedTerminalSleepEpoch = stoppedEpoch;
        }
        // Invalidate any vendor write that was already in flight before we
        // tear down the handler or allow a replacement session to start.
        monitorLifecycleEpoch.incrementAndGet();
        logger.info("stopMonitor: END"
                + (terminalSleep ? " (terminal sleep)" : ""));

        try { releaseWakeLock(); } catch (Throwable ignored) {}
        try { unregisterOtaListener(); } catch (Throwable ignored) {}
        try { unregisterPowerListener(); } catch (Throwable ignored) {}

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        handler = null;
        otaListener = null;
        powerListener = null;
        cachedOtaDevice = null;
        appContext = null;
        synchronized (voltageQueueLock) {
            voltageMessageQueued = false;
            pendingLatestVoltage = Double.NaN;
            pendingHighVoltage = -1.0;
            pendingNonHealthyVoltage = false;
        }
        nonHealthyVoltageEpoch.set(0L);
        deferredSleepNonHealthyEpoch = 0L;
        deferredSleepLifecycleEpoch = -1L;
    }

    // ── Public callback hook ────────────────────────────────────────

    /**
     * Entry point for live voltage callbacks from {@code BydDataCollector}'s
     * OTA listener hub. The collector already registers the (subclassed)
     * {@code AbsBYDAutoOtaListener} once and dispatches to its own
     * {@code onOtaCallback}; we piggyback on that registration instead of
     * trying to subclass an abstract class via {@code Proxy} (which throws).
     *
     * <p>No-op when the monitor isn't running. Cheap to call from every
     * voltage tick — the collector's onOta hub fires at the rate the HAL
     * delivers, no extra throttling needed here.
     */
    public static void notifyBatteryPowerVoltage(double voltage) {
        if (!running) return;
        enqueueVoltageSample(voltage);
    }

    // ── Voltage / MCU state machine ─────────────────────────────────

    private static void resetSessionState() {
        isWakeupMcu = true;
        lastPowerVoltage = -1.0;
        highPowerVoltage = -1.0;
        lastSleepTime = 0L;
        lastWakeAttemptElapsedMs = -REARM_INTERVAL_MS;
        lastWakeEvaluationElapsedMs = -REARM_INTERVAL_MS;
        lowVoltageEpisodeWakeIssued = false;
        synchronized (voltageQueueLock) {
            voltageMessageQueued = false;
            pendingLatestVoltage = Double.NaN;
            pendingHighVoltage = -1.0;
            pendingNonHealthyVoltage = false;
        }
        nonHealthyVoltageEpoch.set(0L);
        deferredSleepNonHealthyEpoch = 0L;
        deferredSleepLifecycleEpoch = -1L;
    }

    private static void enqueueVoltageSample(double voltage) {
        if (!running || Double.isNaN(voltage) || Double.isInfinite(voltage)) {
            return;
        }
        Handler target = handler;
        if (target == null) return;

        boolean shouldPost = false;
        synchronized (voltageQueueLock) {
            if (!running || handler != target) return;
            pendingLatestVoltage = voltage;
            if (voltage > pendingHighVoltage) {
                pendingHighVoltage = voltage;
            }
            if (voltage <= SLEEP_ALLOW_VOLTAGE) {
                pendingNonHealthyVoltage = true;
                nonHealthyVoltageEpoch.incrementAndGet();
            }
            if (!voltageMessageQueued) {
                voltageMessageQueued = true;
                shouldPost = true;
            }
        }
        if (shouldPost && !target.sendEmptyMessage(MSG_VOLTAGE_SAMPLE)) {
            synchronized (voltageQueueLock) {
                if (handler == target) {
                    voltageMessageQueued = false;
                }
            }
        }
    }

    private static void drainVoltageSample() {
        final double voltage;
        final double observedHigh;
        final boolean observedNonHealthy;
        synchronized (voltageQueueLock) {
            voltage = pendingLatestVoltage;
            observedHigh = pendingHighVoltage;
            observedNonHealthy = pendingNonHealthyVoltage;
            pendingLatestVoltage = Double.NaN;
            pendingHighVoltage = -1.0;
            pendingNonHealthyVoltage = false;
            voltageMessageQueued = false;
        }
        if (!Double.isNaN(voltage)) {
            onBatteryPowerVoltageChanged(
                    voltage, observedHigh, observedNonHealthy);
        }
    }

    private static void onBatteryPowerVoltageChanged(
            double voltage,
            double observedHigh,
            boolean observedNonHealthy) {
        if (!running) return;
        lastPowerVoltage = voltage;
        if (observedHigh > highPowerVoltage) {
            highPowerVoltage = observedHigh;
        } else if (voltage > highPowerVoltage) {
            highPowerVoltage = voltage;
        }

        // A genuinely healthy sample ends the current low-voltage episode.
        // Only then (or after a deliberate sleep below) may another episode
        // issue a wake command.
        if (Math.max(voltage, observedHigh) > SLEEP_ALLOW_VOLTAGE) {
            lowVoltageEpisodeWakeIssued = false;
            lastWakeAttemptElapsedMs = -REARM_INTERVAL_MS;
            lastWakeEvaluationElapsedMs = -REARM_INTERVAL_MS;
        }

        // A deferred sleep is valid only while the latest voltage remains in
        // the healthy range. Without this cancellation, a sleep armed at a
        // healthy peak can fire up to 15 minutes after voltage has fallen,
        // immediately undoing low-voltage recovery and creating a sleep/wake
        // rail cycle. Reset the high-water mark to the latest state so a later
        // genuine recovery can arm a fresh full defer window.
        if (observedNonHealthy || voltage <= SLEEP_ALLOW_VOLTAGE) {
            if (handler != null) {
                handler.removeMessages(MSG_DEFERRED_MCU_SLEEP);
            }
        }
        if (voltage <= SLEEP_ALLOW_VOLTAGE) {
            highPowerVoltage = voltage;
        }

        // High-voltage path: schedule MCU sleep after the defer window.
        if (allowSleep(voltage)) {
            scheduleDeferredMcuSleep();
        }

        // Low-voltage path: wake MCU and reset the high-water mark.
        if (shouldEvaluateWake(voltage)) {
            evaluateLowVoltageWake();
        }
    }

    private static boolean allowSleep(double voltage) {
        return voltage >= highPowerVoltage && voltage > SLEEP_ALLOW_VOLTAGE;
    }

    private static boolean shouldEvaluateWake(double voltage) {
        if (voltage > WAKE_TRIGGER_VOLTAGE
                || lowVoltageEpisodeWakeIssued) {
            return false;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long lastGate = Math.max(
                lastWakeEvaluationElapsedMs, lastWakeAttemptElapsedMs);
        return now - lastGate >= REARM_INTERVAL_MS;
    }

    /**
     * Runs only on the V2 handler thread. The physical-status getter is a
     * synchronous vendor Binder call, so keeping it here prevents a slow HAL
     * response from blocking the OTA/collector callback thread.
     */
    private static void evaluateLowVoltageWake() {
        if (!running || lowVoltageEpisodeWakeIssued) return;
        long now = android.os.SystemClock.elapsedRealtime();
        long lastGate = Math.max(
                lastWakeEvaluationElapsedMs, lastWakeAttemptElapsedMs);
        if (now - lastGate < REARM_INTERVAL_MS) {
            return;
        }
        lastWakeEvaluationElapsedMs = now;

        Integer status = null;
        try {
            if (appContext != null) {
                status = AccOffReaders.getMcuStatus(appContext);
            }
        } catch (Throwable t) {
            logger.debug("low-voltage MCU status read failed: " + t.getMessage());
        }

        // Field-observed ready values. A ready MCU needs no write; re-probe
        // after the normal cadence in case the BCM later auto-sleeps it.
        if (status != null && (status == 1 || status == 10)) {
            isWakeupMcu = true;
            logger.debug("low-voltage recovery skipped: MCU already ready (status="
                    + status + ")");
            return;
        }

        boolean committed = forceWake();
        if (committed) {
            lowVoltageEpisodeWakeIssued = true;
        } else {
            logger.warn("low-voltage wake was not confirmed; retry remains armed after "
                    + REARM_INTERVAL_MS + "ms");
        }
    }

    private static void scheduleDeferredMcuSleep() {
        // DiLink 4 gate. The reference app's DEFAULT flameout mode is 0 → V1
        // (kh/a.c() switching on vj/a.m(), which defaults the
        // key.flameout.wakeup.mode preference to 0), and V1 (kh/b) has NO sleep
        // path at all — its entire ACC-off behaviour is one unconditional
        // "-1442840502 = 1" hold. We ported V2 (kh/d) instead and wired it up as
        // if it were the shipping default, so on byd_apa we would, after 15 min of
        // healthy 12 V, request an MCU sleep that collapses the very AVM/ISP rail
        // the pano cameras feed from — black-framing surveillance for the rest of
        // the park, which is exactly the reported symptom.
        //
        // Suppress the healthy-battery sleep on dilink4 to match V1. The battery
        // floor is unaffected: the low-voltage forceWake() recovery and
        // SocCutoffMonitor (<=10% SoC self-shutdown) still run. Every other
        // variant keeps the full V2 hysteresis model byte-for-byte.
        if (isDilink4CameraMode() || isDilink5Mode()) {
            if (handler != null) handler.removeMessages(MSG_DEFERRED_MCU_SLEEP);
            logger.info("scheduleDeferredMcuSleep: SUPPRESSED for selected platform");
            return;
        }
        // Keep-USB-powered gate. When the user opted to keep USB powered while
        // parked, we never schedule the healthy-battery MCU sleep — that sleep
        // would drop the USB/SD rail. The low-voltage forceWake() path and the
        // SoC cutoff monitor still protect the 12V battery. Read fresh so a
        // toggle flip on a later ACC-OFF cycle takes effect.
        if (isKeepUsbPowerOnAccOff()) {
            // Cancel any sleep already armed from before the toggle was read
            // (defensive — the value can't change mid-park, but a pending
            // message from a prior tick must not survive).
            if (handler != null) handler.removeMessages(MSG_DEFERRED_MCU_SLEEP);
            logger.info("scheduleDeferredMcuSleep: SUPPRESSED — Keep-USB-powered is ON "
                    + "(MCU stays awake so USB/SD rail holds; low-voltage recovery + SoC cutoff still active)");
            return;
        }
        if (!isWakeupMcu) {
            // We already issued a sleep request this session — don't re-issue
            // until {@link #forceWake} flips the flag back to true.
            return;
        }
        if (handler == null) return;
        // Only arm if no sleep is already pending. Re-arming on every poll
        // would push the timer out indefinitely while voltage stays in the
        // sleep-allow range (steady-state) — meaning the deferred sleep
        // would never actually fire on a parked car with a healthy battery.
        if (handler.hasMessages(MSG_DEFERRED_MCU_SLEEP)) {
            return;
        }
        Message msg = handler.obtainMessage(MSG_DEFERRED_MCU_SLEEP);
        deferredSleepNonHealthyEpoch = nonHealthyVoltageEpoch.get();
        deferredSleepLifecycleEpoch = monitorLifecycleEpoch.get();
        handler.sendMessageDelayed(msg, MCU_SLEEP_DEFER_MS);
        logger.info("scheduleDeferredMcuSleep: in " + MCU_SLEEP_DEFER_MS + " ms");
    }

    private static void doMcuSleep() {
        if (!running) return;
        // Belt-and-suspenders to the dilink4 gate in scheduleDeferredMcuSleep: a
        // sleep message queued before that gate was evaluated must ALSO be refused
        // at execution time, or the AVM/ISP rail still collapses mid-park. Same
        // execution-time-honouring pattern as the keep-USB check below.
        if (isDilink4CameraMode() || isDilink5Mode()) {
            logger.info("doMcuSleep: SKIPPED for selected platform");
            return;
        }
        // Belt-and-suspenders to scheduleDeferredMcuSleep's gate: if a sleep
        // message was already queued before the toggle was evaluated (e.g. a
        // tick that fired in the 35s window before this monitor started), do
        // NOT execute it while Keep-USB-powered is ON. The scheduling gate
        // normally prevents us reaching here, but a queued message must also
        // be honoured at execution time so the USB/SD rail is never dropped.
        if (isKeepUsbPowerOnAccOff()) {
            logger.info("doMcuSleep: SKIPPED — Keep-USB-powered is ON (queued sleep cancelled at execution)");
            return;
        }
        // Execution-time voltage guard. The delayed message may have been
        // dequeued just before a newer sample tried to remove it, so message
        // cancellation alone cannot prove the original healthy condition is
        // still true.
        if (!Double.isFinite(lastPowerVoltage)
                || lastPowerVoltage <= SLEEP_ALLOW_VOLTAGE) {
            logger.info("doMcuSleep: SKIPPED — latest voltage is no longer healthy ("
                    + lastPowerVoltage + "V)");
            return;
        }
        long expectedNonHealthyEpoch = deferredSleepNonHealthyEpoch;
        long expectedLifecycleEpoch = deferredSleepLifecycleEpoch;
        if (!isDeferredSleepStillCurrent(
                expectedLifecycleEpoch, expectedNonHealthyEpoch)) {
            logger.info("doMcuSleep: SKIPPED — the armed sleep is no longer "
                    + "current (lifecycle or voltage changed)");
            return;
        }
        boolean ok = McuPowerHal.requestMcuSleep();
        if (!isDeferredSleepStillCurrent(
                expectedLifecycleEpoch, expectedNonHealthyEpoch)) {
            compensateStaleSleepWrite(
                    "after MCU sleep write",
                    expectedLifecycleEpoch,
                    expectedNonHealthyEpoch);
            return;
        }
        // sentry-mode mirror — sleep variant
        boolean sentryOk = McuPowerHal.requestSentrySleep();
        if (!isDeferredSleepStillCurrent(
                expectedLifecycleEpoch, expectedNonHealthyEpoch)) {
            compensateStaleSleepWrite(
                    "after sentry sleep write",
                    expectedLifecycleEpoch,
                    expectedNonHealthyEpoch);
            return;
        }
        boolean committed = ok || sentryOk;
        if (!committed) {
            // Neither HAL surface confirmed the sleep. Keep the logical MCU
            // state awake and arm one fresh full defer window; otherwise one
            // transient Binder failure suppresses every later sleep attempt
            // for the entire park and can leave CPU/rails powered indefinitely.
            isWakeupMcu = true;
            highPowerVoltage = lastPowerVoltage;
            logger.warn("doMcuSleep: neither sleep write was confirmed; "
                    + "retrying after a fresh defer window");
            scheduleDeferredMcuSleep();
            return;
        }
        lastSleepTime = System.currentTimeMillis();
        isWakeupMcu = false;
        highPowerVoltage = -1.0;
        lowVoltageEpisodeWakeIssued = false;
        lastWakeEvaluationElapsedMs = android.os.SystemClock.elapsedRealtime();
        logger.info("doMcuSleep: power=" + ok + " sentry=" + sentryOk);
    }

    private static boolean isDeferredSleepStillCurrent(
            long expectedLifecycleEpoch,
            long expectedNonHealthyEpoch) {
        return running
                && monitorLifecycleEpoch.get() == expectedLifecycleEpoch
                && nonHealthyVoltageEpoch.get() == expectedNonHealthyEpoch;
    }

    private static void compensateStaleSleepWrite(
            String stage,
            long expectedLifecycleEpoch,
            long expectedNonHealthyEpoch) {
        logger.warn("doMcuSleep: armed sleep became stale " + stage
                + " (lifecycle=" + expectedLifecycleEpoch + "->"
                + monitorLifecycleEpoch.get()
                + ", voltageEpoch=" + expectedNonHealthyEpoch + "->"
                + nonHealthyVoltageEpoch.get()
                + "); issuing compensating wake");

        if (expectedLifecycleEpoch == uncompensatedTerminalSleepEpoch) {
            // SocCutoffMonitor has intentionally stopped this exact monitor
            // epoch immediately before PowerManager.goToSleep + process exit.
            // Re-waking here would undo the battery-safety shutdown and can
            // produce the same visible boot-like flash this monitor prevents
            // during ordinary park transitions.
            logger.info("doMcuSleep: terminal shutdown owns stale sleep epoch "
                    + expectedLifecycleEpoch
                    + " — compensating wake suppressed");
            return;
        }

        // Do not call forceWake(): stopMonitor() deliberately flips running
        // before a blocked Binder write returns. The physical wake must still
        // be sent to undo that stale sleep, while monitor bookkeeping and
        // wakelock re-arming remain limited to a live matching session.
        boolean ok = McuPowerHal.requestMcuWake();
        boolean sentryOk = McuPowerHal.requestSentryWake();
        boolean committed = ok || sentryOk;
        logger.info("compensateStaleSleepWrite: power=" + ok
                + " sentry=" + sentryOk + " committed=" + committed);

        if (running
                && monitorLifecycleEpoch.get() == expectedLifecycleEpoch) {
            isWakeupMcu = committed;
            if (!committed) {
                return;
            }
            lowVoltageEpisodeWakeIssued = true;
            lastWakeAttemptElapsedMs =
                    android.os.SystemClock.elapsedRealtime();
            if (appContext != null) acquireWakeLock(appContext);
            ensureMonitorTickScheduled();
        }
    }

    /**
     * One-shot MCU wake for a remote-key actuation (lock/unlock/power). Wake only — this never
     * asserts a hold the caller would then have to release; V2 keeps ownership of the re-sleep
     * cadence. When this monitor is running in the current process we use {@link #forceWake()}
     * so {@code isWakeupMcu} stays coherent and V2 re-sleeps on its own schedule; otherwise we
     * fall straight to the HAL wake. On DiLink 5 the wake is skipped (returns false): the only
     * DI5 wake path is the parked-generation lease, whose token is not obtainable here, and a
     * skipped wake simply makes the readiness gate proceed (it fails open).
     *
     * @return true only when a wake was actually issued/confirmed.
     */
    public static boolean wakeForActuation() {
        if (isDilink5Mode()) {
            return false;
        }
        synchronized (BatteryVoltageMonitorV2.class) {
            if (running) {
                return forceWake();
            }
        }
        return McuPowerHal.requestMcuWake();
    }

    private static boolean forceWake() {
        if (!running) return false;
        // A wake decision supersedes any healthy-voltage sleep which may have
        // been armed earlier in the same monitor session.
        if (handler != null) {
            handler.removeMessages(MSG_DEFERRED_MCU_SLEEP);
        }
        // Stamp before either Binder write. A re-entrant callback or another
        // queued sample cannot issue a duplicate wake while these calls run.
        lastWakeAttemptElapsedMs = android.os.SystemClock.elapsedRealtime();
        boolean ok = McuPowerHal.requestMcuWake();
        boolean sentryOk = McuPowerHal.requestSentryWake();
        boolean committed = ok || sentryOk;
        isWakeupMcu = committed;
        logger.info("forceWake: power=" + ok + " sentry=" + sentryOk
                + " committed=" + committed);
        // Re-arm the wake-lock window since we just had to recover.
        // Use the caller-supplied context — V2 may run in any daemon process.
        if (appContext != null) acquireWakeLock(appContext);
        // The recurring monitor tick already performs this same seed. Ensure
        // one exists instead of adding a second permanent 60-second polling
        // loop after the first low-voltage recovery.
        ensureMonitorTickScheduled();
        return committed;
    }

    // ── Handler loop ────────────────────────────────────────────────

    private static boolean onMessage(Message msg) {
        switch (msg.what) {
            case MSG_SCHEDULE_MONITOR:
                if (running) {
                    seedFromCurrentReadings();
                    scheduleMonitor(REARM_INTERVAL_MS);
                }
                return true;
            case MSG_DEFERRED_MCU_SLEEP:
                doMcuSleep();
                return true;
            case MSG_WAKEUP_LOOP:
                if (running) {
                    seedFromCurrentReadings();
                    // Drain any message armed by an older session/build into
                    // the single canonical monitor cadence.
                    ensureMonitorTickScheduled();
                }
                return true;
            case MSG_VOLTAGE_SAMPLE:
                if (running) {
                    drainVoltageSample();
                }
                return true;
            default:
                return false;
        }
    }

    private static void scheduleMonitor(long delayMs) {
        if (handler == null) return;
        handler.removeMessages(MSG_SCHEDULE_MONITOR);
        handler.sendMessageDelayed(
                handler.obtainMessage(MSG_SCHEDULE_MONITOR), delayMs);
    }

    private static void ensureMonitorTickScheduled() {
        Handler target = handler;
        if (target == null || !running) return;
        target.removeMessages(MSG_WAKEUP_LOOP);
        if (!target.hasMessages(MSG_SCHEDULE_MONITOR)) {
            target.sendMessageDelayed(
                    target.obtainMessage(MSG_SCHEDULE_MONITOR),
                    REARM_INTERVAL_MS);
        }
    }

    /**
     * Cached OTA device resolved from {@link #appContext}. We do NOT go
     * through {@code BydDataCollector.getInstance()} for this — that's
     * the cam_daemon process's collector and returns a fresh empty
     * instance with no devices in any other process (e.g. acc_sentry).
     */
    private static volatile Object cachedOtaDevice;

    private static Object resolveOtaDevice() {
        if (cachedOtaDevice != null) return cachedOtaDevice;
        if (appContext == null) {
            logger.debug("resolveOtaDevice: no appContext");
            return null;
        }
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.ota.BYDAutoOtaDevice");
            cachedOtaDevice = BydDeviceHelper.invokeGetInstance(cls, appContext);
            if (cachedOtaDevice != null) {
                logger.info("resolveOtaDevice: " + cachedOtaDevice.getClass().getName());
            }
        } catch (Throwable t) {
            logger.debug("resolveOtaDevice failed: " + t.getMessage());
        }
        return cachedOtaDevice;
    }

    private static void seedFromCurrentReadings() {
        try {
            Object ota = resolveOtaDevice();
            if (ota == null) {
                logger.debug("seed: ota device unresolved (process-local resolve failed)");
                return;
            }
            Object v = BydDeviceHelper.callGetter(ota, "getBatteryPowerVoltage");
            if (v instanceof Number) {
                double voltage = ((Number) v).doubleValue();
                boolean nonHealthy = voltage <= SLEEP_ALLOW_VOLTAGE;
                if (nonHealthy) {
                    nonHealthyVoltageEpoch.incrementAndGet();
                }
                onBatteryPowerVoltageChanged(
                        voltage, voltage, nonHealthy);
            } else {
                logger.debug("seed: getBatteryPowerVoltage returned " + v);
            }
        } catch (Throwable t) {
            logger.debug("seed: getBatteryPowerVoltage failed: " + t.getMessage());
        }
    }

    // ── BYD listener registration via reflection ────────────────────

    private static void registerOtaListener() {
        // Live voltage callbacks land via BydDataCollector.onOtaCallback,
        // which fans out to {@link #notifyBatteryPowerVoltage}. No direct
        // listener registration here — Proxy can't implement an abstract
        // class, and we don't want to duplicate the collector's listener.
        logger.info("registerOtaListener: piggybacking on BydDataCollector OTA hub");
    }

    private static void unregisterOtaListener() {
        // No direct listener; nothing to release.
    }

    private static void registerPowerListener() {
        // No direct listener is registered. Physical MCU status is read only
        // when a low-voltage sample needs a recovery decision, and that read is
        // serialized on this class's handler thread.
        logger.info("registerPowerListener: handler-serialized status polling armed");
    }

    private static void unregisterPowerListener() {
        // No-op — we only seed-read getMcuStatus, no listener attached above.
    }

    // ── WakeLock ────────────────────────────────────────────────────

    private static synchronized void acquireWakeLock(Context context) {
        try {
            if (wakeLock != null && wakeLock.isHeld()) return;
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            logger.info("acquireWakeLock: " + WAKE_LOCK_TAG
                    + " (" + WAKE_LOCK_TIMEOUT_MS + "ms)");
        } catch (Throwable t) {
            logger.warn("acquireWakeLock failed: " + t.getMessage());
        }
    }

    private static synchronized void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    // resolveDevice(...) by collector-field is intentionally absent — V2
    // resolves devices directly from appContext (process-local). Going
    // through BydDataCollector.getInstance() returns a fresh empty collector
    // in any process other than cam_daemon.
}
