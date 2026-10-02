package com.overdrive.app.power;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.json.JSONObject;

/**
 * DiLink 5 parked keep-alive lease (Experimental).
 *
 * <p>Holds the MCU/sentry rails, optionally the panorama producer, and — only
 * after a recorded on-car pre-flight — a vendor shutdown-critical token, while
 * the vehicle is parked and the user has opted in. Runs inside
 * {@code acc_sentry_daemon} and is driven by two callers only:
 * <ul>
 *   <li>{@link #requestForTransition(long, boolean)} from a generation-fenced
 *       {@code LatestBooleanReconciler} on every sentry transition (ACC OFF →
 *       desired, ACC ON / shutdown → released);</li>
 *   <li>{@link #tick(long)} from the daemon's 10 s keep-alive loop, which
 *       re-reads the configuration, samples the 12 V battery and re-asserts or
 *       releases the levers.</li>
 * </ul>
 *
 * <p>Invariants:
 * <ul>
 *   <li>Nothing is written while the master switch is off, the kill switch is
 *       set, or no fresh 12 V sample above the cutoff exists (fail closed).</li>
 *   <li>Deliberately NOT gated on the camera-mode selection: DiLink 5 head units
 *       exist with and without the QCarCam camera stack, so the user's opt-in
 *       toggle is the platform declaration. The lease is installed on every
 *       platform and stays inert until that toggle is ON.</li>
 *   <li>The {@link Di5KeepAliveOwnershipMarker} is written BEFORE the first HAL
 *       write and removed AFTER the last release; start-up hygiene releases only
 *       what the marker records.</li>
 *   <li>The 12 V cutoff latches until ACC ON or confirmed external charging.</li>
 *   <li>Sentry flags are written only when {@code getMcuStatus()} reports the MCU
 *       awake ({@code 1} or {@code 10}); otherwise {@code wakeUpMcu()} is polled,
 *       bounded, and the flags wait for the next attempt.</li>
 *   <li>The MCU power hold ({@code -1442840502 ← 1}, OEM-app V1 parity) is
 *       written unconditionally at assert time — the 1 IS the wake request, so
 *       it must not be gated on {@code getMcuStatus()} (the documented DiLink 4
 *       rail-collapse mistake). The release-side {@code ← 0} ("request MCU
 *       sleep") is issued ONLY when this install asserted the 1 or a marker
 *       recorded it — never as a bare cleanup.</li>
 * </ul>
 *
 * <p>All hardware and I/O go through {@link Hardware} so the state machine is
 * unit-testable on the JVM.
 */
public final class Di5ParkedPowerHold {

    /** Cross-process status snapshot consumed by {@code /api/vehicle/di5-keepalive}. */
    public static final String DEFAULT_STATUS_PATH =
            com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/overdrive_di5_keepalive.status");

    /**
     * Token registered in {@code vendor.peripheral.shutdown_critical_list}.
     * Deliberately our own name: a unit that also runs the reference app must
     * not have the two apps stripping each other's token.
     */
    public static final String AP_HOLD_TOKEN = "ovdrv";
    static final String AP_HOLD_LIST_PROPERTY = "vendor.peripheral.shutdown_critical_list";
    static final String AP_HOLD_STATE_PROPERTY = "vendor.peripheral." + AP_HOLD_TOKEN + ".state";

    /** MCU status values that mean "awake" (reference: getMcuStatus() ∈ {1, 10}). */
    static final int MCU_STATUS_ACTIVE = 1;
    static final int MCU_STATUS_ACTIVE_ALT = 10;

    /** Bounded per-attempt wait for the MCU to come up after wakeUpMcu(). */
    static final long WAKE_POLL_INTERVAL_MS = 1_000L;
    static final long WAKE_POLL_MAX_MS = 10_000L;
    /** After this many failed wake attempts, retry only on the re-assert cadence. */
    static final int WAKE_FAST_RETRY_ATTEMPTS = 6;

    /** Heartbeat cadence for PANORAMA_WORK_MODE_SET=1 (reference: 2 s). */
    static final long HEARTBEAT_INTERVAL_MS = 2_000L;

    /** After an AP-hold permission denial, re-probe only every N ticks. */
    static final int AP_DENIED_RECHECK_TICKS = 10;

    /** Hardware / I/O seam. Production glue lives in AccSentryDaemon. */
    public interface Hardware {
        /** MCU status code for the parked generation, or -1 when unavailable. */
        int getMcuStatus(long generation);
        /** BYDAutoPowerDevice.wakeUpMcu() for the parked generation; true when the HAL returned 0. */
        boolean wakeUpMcu(long generation);
        /**
         * Sentry flags via the lease-only McuPowerHal path. Asserting requires
         * {@code generation} to still be the current parked generation; releasing
         * is always permitted (it is the safe direction).
         * @return {@code int[]{rcEnter, rcState}} raw return codes (0 = confirmed);
         *         {@code Integer.MIN_VALUE} when the device is unavailable.
         */
        int[] writeSentryFlags(long generation, boolean assertFlags);
        /**
         * MCU power hold via the lease-only McuPowerHal path:
         * {@code -1442840502 ← 1} on hold, {@code ← 0} on release. OEM-app
         * parity — written unconditionally at assert time (no MCU-status
         * precondition; the 1 IS the wake request). The controller issues the
         * release-side 0 only when it asserted (or a marker recorded) the 1.
         * @return the HAL's raw return code (0 = confirmed);
         *         {@code Integer.MIN_VALUE} when unavailable.
         */
        int writeMcuPowerHold(long generation, boolean hold);
        /** PANORAMA_WORK_MODE_SET write; raw rc, {@link PanoramaWorkModeOwner#RC_UNAVAILABLE} if none. */
        int writePanoramaWorkMode(int value);
        /** Optional PANORAMA_WORK_MODE read-back; null when unsupported. */
        Integer readPanoramaWorkMode();
        /** 12 V reading from BYDAutoOtaDevice.getBatteryPowerVoltage(); null when unavailable. */
        Double readBatteryVoltage();
        /** Shell as the daemon's own identity. */
        ShellOutcome shell(String command);
        /** External charging confirmed by ChargingDetector. */
        boolean isChargingConfirmed();
        /** Diagnostic power-mode line (car_service dump / sys.byd.power_mode); may be null. */
        String powerModeLine();
        long nowMs();
        int pid();
        void sleepMs(long ms) throws InterruptedException;
        void log(String message);
    }

    /** Generation fence supplied by the daemon. */
    public interface GenerationCheck {
        /** True while {@code generation} is the current parked (sentry) generation. */
        boolean isCurrentParkedGeneration(long generation);
    }

    /** Result of a shell command. */
    public static final class ShellOutcome {
        public final int exitCode;
        public final String output;

        public ShellOutcome(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    private static volatile Di5ParkedPowerHold installed;

    /** Register the daemon's instance so process-exit paths can release it. */
    public static void install(Di5ParkedPowerHold hold) {
        installed = hold;
    }

    /** The daemon's instance, or null before {@link #install}. */
    public static Di5ParkedPowerHold installedInstance() {
        return installed;
    }

    /**
     * Release everything from a process-exit path (SoC cutoff, daemon shutdown).
     * Safe to call when nothing is installed or held.
     */
    public static void releaseForProcessExit(String reason) {
        Di5ParkedPowerHold h = installed;
        if (h != null) {
            try {
                h.release(reason);
            } catch (Throwable t) {
                h.hardware.log("Di5 keep-alive release on exit failed: " + t.getMessage());
            }
        }
    }

    private final Hardware hardware;
    private final GenerationCheck generationCheck;
    private final Supplier<Di5ParkedKeepAliveSettings> settingsSupplier;
    private final Di5KeepAliveOwnershipMarker marker;
    private final File statusFile;
    private final Di5KeepAliveVoltageGuard voltageGuard;

    private final Object lock = new Object();

    // Lease state (guarded by lock).
    private Di5ParkedKeepAliveSettings settings = Di5ParkedKeepAliveSettings.disabled();
    private long requestedGeneration = -1L;
    private boolean desired;
    private long heldGeneration = -1L;
    private boolean mcuHeld;
    private boolean cameraHeld;
    private boolean apHeld;
    private String apStatus = "idle";
    private int apDeniedTicks;
    private String apListBefore = "";
    private String apListAfter = "";
    private int apLastExit = Integer.MIN_VALUE;
    private long lastMcuAssertAtMs = Long.MIN_VALUE;
    private int mcuStatusBefore = Integer.MIN_VALUE;
    private int mcuStatusAfter = Integer.MIN_VALUE;
    private int mcuWakeAttempts;
    private int mcuReasserts;
    private int mcuLastRcEnter = Integer.MIN_VALUE;
    private int mcuLastRcState = Integer.MIN_VALUE;
    /** Last sentry-flag write was rejected (rc != 0): retry on the re-assert cadence only. */
    private boolean mcuLastWriteRejected;
    /** MCU power hold (-1442840502←1) asserted by THIS instance; release writes 0 only then. */
    private boolean mcuPowerHoldAsserted;
    private int mcuPowerHoldLastRc = Integer.MIN_VALUE;
    private String mcuStatus = "idle";
    /**
     * Set by {@link #release(String)} before it waits for {@link #lock}, so a tick
     * that is inside the bounded MCU wake poll gives the lock up within one poll
     * interval instead of finishing the full wait first.
     */
    private volatile boolean releaseRequested;
    private String voltageDecision = "unknown";
    private String lastStopReason = "";
    private long lastStopAtMs;
    private long lastTickAtMs;
    private long ticks;

    // Heartbeat thread state.
    private volatile Thread heartbeatThread;
    /** Most recently started heartbeat thread, kept for liveness checks/tests. */
    private volatile Thread lastHeartbeatThread;
    private volatile boolean heartbeatRunning;
    private volatile long heartbeatGeneration = -1L;
    private volatile long heartbeatTicks;
    private volatile int heartbeatLastRc = Integer.MIN_VALUE;
    private volatile Integer heartbeatLastReadBack;

    public Di5ParkedPowerHold(
            Hardware hardware,
            GenerationCheck generationCheck,
            Supplier<Di5ParkedKeepAliveSettings> settingsSupplier) {
        this(hardware, generationCheck, settingsSupplier,
                new Di5KeepAliveOwnershipMarker(), new File(DEFAULT_STATUS_PATH));
    }

    /** Test seam: custom marker + status file locations. */
    public Di5ParkedPowerHold(
            Hardware hardware,
            GenerationCheck generationCheck,
            Supplier<Di5ParkedKeepAliveSettings> settingsSupplier,
            Di5KeepAliveOwnershipMarker marker,
            File statusFile) {
        this.hardware = hardware;
        this.generationCheck = generationCheck;
        this.settingsSupplier = settingsSupplier;
        this.marker = marker;
        this.statusFile = statusFile;
        Di5ParkedKeepAliveSettings initial = safeSettings();
        this.settings = initial;
        this.voltageGuard = new Di5KeepAliveVoltageGuard(
                initial.cutoffVoltage, initial.cutoffSamples, initial.voltageMaxAgeMillis());
    }

    // ── Entry points ───────────────────────────────────────────────

    /**
     * Reconciler entry. {@code desired=true} on a parked (ACC OFF, onAndOff)
     * transition, {@code false} on ACC ON / shutdown / onOnly.
     *
     * @return true always: transient hardware conditions are retried by
     *         {@link #tick(long)}, not by the reconciler's backoff.
     */
    public boolean requestForTransition(long generation, boolean desired) {
        synchronized (lock) {
            if (generation < requestedGeneration) {
                return true; // stale request
            }
            boolean newGeneration = generation != requestedGeneration;
            requestedGeneration = generation;
            this.desired = desired;
            if (!desired) {
                // ACC ON (or exit): the only legitimate latch release besides
                // confirmed charging.
                voltageGuard.releaseLatch("ACC ON / lease end");
                releaseLocked("transition:" + generation);
                voltageGuard.resetSamples();
                writeStatusLocked();
                return true;
            }
            if (newGeneration) {
                voltageGuard.resetSamples();
                mcuWakeAttempts = 0;
            }
            evaluateLocked(generation, "transition");
            return true;
        }
    }

    /** 10 s keep-alive tick for the current parked generation. */
    public void tick(long generation) {
        synchronized (lock) {
            ticks++;
            lastTickAtMs = hardware.nowMs();
            if (!desired || generation != requestedGeneration) {
                return;
            }
            evaluateLocked(generation, "tick");
        }
    }

    /** Release everything now (shutdown, SoC cutoff, mode change). Idempotent. */
    public void release(String reason) {
        releaseRequested = true;
        try {
            synchronized (lock) {
                desired = false;
                releaseLocked(reason);
                writeStatusLocked();
            }
        } finally {
            releaseRequested = false;
        }
    }

    /**
     * Start-up hygiene. If a previous process died holding levers and the
     * vehicle now reads ACC ON, release exactly the recorded levers. With ACC
     * OFF the marker is kept: the sentry transition that follows re-asserts and
     * re-records. Never touches flags this install did not record.
     *
     * @return true when a hygiene release ran
     */
    public boolean onDaemonStart(boolean accReadsOn) {
        return onDaemonStart(() -> accReadsOn);
    }

    /**
     * Same as {@link #onDaemonStart(boolean)}, but the ACC probe is only run
     * when a marker actually exists — installs that never enabled the feature
     * pay nothing at start-up. A probe that throws reads as ACC ON (the safe
     * direction: releasing flags a dead predecessor recorded can be re-done by
     * the next parked transition, leaving them set cannot).
     */
    public boolean onDaemonStart(BooleanSupplier accReadsOn) {
        synchronized (lock) {
            Di5KeepAliveOwnershipMarker.Record rec = marker.read();
            if (rec == null) {
                return false;
            }
            boolean on;
            try {
                on = accReadsOn.getAsBoolean();
            } catch (Throwable t) {
                hardware.log("Di5 keep-alive: start-up ACC probe failed (" + t.getMessage()
                        + ") — treating as ACC ON for hygiene");
                on = true;
            }
            if (!on) {
                hardware.log("Di5 keep-alive: ownership marker from a previous process present "
                        + "(gen=" + rec.generation + ", levers=" + rec.levers
                        + "); ACC reads OFF — leaving it for the next parked transition");
                return false;
            }
            hardware.log("Di5 keep-alive HYGIENE: previous process (pid=" + rec.pid
                    + ", gen=" + rec.generation + ") left levers " + rec.levers
                    + " and ACC reads ON — releasing exactly those");
            releaseLeversLocked(rec.has(Di5KeepAliveOwnershipMarker.LEVER_CAMERA),
                    rec.has(Di5KeepAliveOwnershipMarker.LEVER_MCU),
                    rec.has(Di5KeepAliveOwnershipMarker.LEVER_MCU_POWER),
                    rec.has(Di5KeepAliveOwnershipMarker.LEVER_AP));
            if (!marker.clear()) {
                hardware.log("Di5 keep-alive HYGIENE: marker could not be removed");
            }
            lastStopReason = "hygiene";
            lastStopAtMs = hardware.nowMs();
            writeStatusLocked();
            return true;
        }
    }

    /** True while the most recently started heartbeat thread is still alive. */
    boolean heartbeatThreadAlive() {
        Thread t = lastHeartbeatThread;
        return t != null && t.isAlive();
    }

    /** True while at least one lever is held. */
    public boolean isActive() {
        synchronized (lock) {
            return isActiveLocked();
        }
    }

    /** Snapshot for diagnostics. */
    public JSONObject status() {
        synchronized (lock) {
            return statusLocked();
        }
    }

    // ── Core evaluation ────────────────────────────────────────────

    private void evaluateLocked(long generation, String source) {
        if (!generationCheck.isCurrentParkedGeneration(generation)) {
            if (heldGeneration == generation || isActiveLocked()) {
                releaseLocked("generation " + generation + " no longer parked (" + source + ")");
            }
            writeStatusLocked();
            return;
        }

        Di5ParkedKeepAliveSettings s = safeSettings();
        boolean settingsChanged = !s.toString().equals(settings.toString());
        settings = s;
        voltageGuard.reconfigure(s.cutoffVoltage, s.cutoffSamples, s.voltageMaxAgeMillis());

        if (!s.isEnabled() || !s.hasAnyEffectiveLever()) {
            if (isActiveLocked()) {
                releaseLocked(s.killSwitchActive ? "kill switch" : "disabled in config");
            }
            voltageDecision = "n/a";
            writeStatusLocked();
            return;
        }

        // 12 V guard: sample first, then decide.
        long now = hardware.nowMs();
        Double volts = null;
        try {
            volts = hardware.readBatteryVoltage();
        } catch (Throwable t) {
            hardware.log("Di5 keep-alive: voltage read failed: " + t.getMessage());
        }
        boolean tripped = volts != null && voltageGuard.observe(volts, now);
        if (voltageGuard.isLatched() && !tripped) {
            try {
                if (hardware.isChargingConfirmed()) {
                    voltageGuard.releaseLatch("external charging confirmed");
                    hardware.log("Di5 keep-alive: 12V latch released — external charging confirmed");
                }
            } catch (Throwable ignored) {
            }
        }
        Di5KeepAliveVoltageGuard.Decision decision = voltageGuard.evaluate(now);
        voltageDecision = decision.name();
        if (tripped) {
            hardware.log("Di5 keep-alive: 12V CUTOFF — " + voltageGuard.latchReason()
                    + "; releasing every lever and latching off until ACC ON or charging");
            releaseLocked("voltage cutoff");
            writeStatusLocked();
            return;
        }
        switch (decision) {
            case CUTOFF_LATCHED:
                if (isActiveLocked()) {
                    releaseLocked("voltage cutoff latched");
                }
                writeStatusLocked();
                return;
            case NO_SAMPLE:
                if (!isActiveLocked()) {
                    if (ticks % 6 == 1 || "transition".equals(source)) {
                        hardware.log("Di5 keep-alive: waiting for a valid 12V sample before holding");
                    }
                    writeStatusLocked();
                    return;
                }
                // Holding with no sample cannot happen (we never assert without
                // one) — fall through defensively to a release.
                releaseLocked("voltage sample lost");
                writeStatusLocked();
                return;
            case STALE:
                // Keep what is held, but do not re-assert or add levers until a
                // fresh sample arrives.
                if (ticks % 6 == 0) {
                    hardware.log("Di5 keep-alive: 12V sample stale ("
                            + voltageGuard.sampleAgeMs(now) + " ms) — not re-asserting");
                }
                writeStatusLocked();
                return;
            case ALLOW:
            default:
                break;
        }

        // Ownership marker BEFORE the first HAL write.
        List<String> wanted = new ArrayList<>();
        if (s.isMcuHoldEffective()) wanted.add(Di5KeepAliveOwnershipMarker.LEVER_MCU);
        if (s.isMcuPowerHoldEffective()) wanted.add(Di5KeepAliveOwnershipMarker.LEVER_MCU_POWER);
        if (s.isCameraHeartbeatEffective()) wanted.add(Di5KeepAliveOwnershipMarker.LEVER_CAMERA);
        if (s.isApHoldEffective()) wanted.add(Di5KeepAliveOwnershipMarker.LEVER_AP);
        Di5KeepAliveOwnershipMarker.Record existing = marker.read();
        boolean markerCurrent = existing != null
                && existing.generation == generation
                && existing.levers.containsAll(wanted)
                && wanted.containsAll(existing.levers);
        if (!markerCurrent) {
            // Union with anything already held so a lever that is being turned
            // off stays recorded until it is actually released below.
            List<String> toRecord = new ArrayList<>(wanted);
            if (mcuHeld && !toRecord.contains(Di5KeepAliveOwnershipMarker.LEVER_MCU)) {
                toRecord.add(Di5KeepAliveOwnershipMarker.LEVER_MCU);
            }
            if (mcuPowerHoldAsserted
                    && !toRecord.contains(Di5KeepAliveOwnershipMarker.LEVER_MCU_POWER)) {
                toRecord.add(Di5KeepAliveOwnershipMarker.LEVER_MCU_POWER);
            }
            if (cameraHeld && !toRecord.contains(Di5KeepAliveOwnershipMarker.LEVER_CAMERA)) {
                toRecord.add(Di5KeepAliveOwnershipMarker.LEVER_CAMERA);
            }
            if (apHeld && !toRecord.contains(Di5KeepAliveOwnershipMarker.LEVER_AP)) {
                toRecord.add(Di5KeepAliveOwnershipMarker.LEVER_AP);
            }
            if (!marker.record(generation, toRecord, now, hardware.pid())) {
                hardware.log("Di5 keep-alive: ownership marker could not be written to "
                        + marker.file() + " — refusing to write HAL flags (fail closed)");
                writeStatusLocked();
                return;
            }
        }
        if (heldGeneration != generation) {
            hardware.log("Di5 keep-alive ACTIVATING for parked generation " + generation
                    + " (" + source + ") settings=" + s
                    + " volts=" + voltageGuard.lastVoltage()
                    + " powerMode=" + safePowerModeLine());
            heldGeneration = generation;
        } else if (settingsChanged) {
            hardware.log("Di5 keep-alive: settings changed while parked → " + s);
        }

        // Levers that are no longer wanted come off first. Same order as
        // releaseLeversLocked: sentry pair before the power-hold 0, so the
        // pair release is written while the MCU is still being held awake.
        if (!s.isCameraHeartbeatEffective() && cameraHeld) releaseCameraLocked("lever disabled");
        if (!s.isMcuHoldEffective() && mcuHeld) releaseMcuLocked("lever disabled");
        if (!s.isMcuPowerHoldEffective() && mcuPowerHoldAsserted) {
            releaseMcuPowerLocked("lever disabled");
        }
        if (!s.isApHoldEffective() && apHeld) releaseApLocked("lever disabled");

        // Then assert / re-assert the wanted ones. A concurrent release() wins:
        // stop adding levers and let it take the lock.
        if (s.isMcuHoldEffective()) assertMcuLocked(generation, now, s);
        if (releaseRequested) {
            writeStatusLocked();
            return;
        }
        if (s.isCameraHeartbeatEffective()) assertCameraLocked(generation);
        if (s.isApHoldEffective()) assertApLocked();

        if (!isActiveLocked() && marker.exists() && wanted.isEmpty()) {
            marker.clear();
        }
        writeStatusLocked();
    }

    // ── MCU hold ───────────────────────────────────────────────────

    private void assertMcuLocked(long generation, long now, Di5ParkedKeepAliveSettings s) {
        boolean due = !mcuHeld
                || lastMcuAssertAtMs == Long.MIN_VALUE
                || now - lastMcuAssertAtMs >= s.reassertMillis();
        if (!due) {
            // Cheap liveness check between cadences: a sleeping MCU means the
            // flags need re-asserting now, not at the next cadence.
            int status = safeMcuStatus(generation);
            if (isMcuAwake(status)) return;
            hardware.log("Di5 keep-alive: MCU status " + status + " between cadences — re-asserting");
        } else if (!mcuHeld
                && lastMcuAssertAtMs != Long.MIN_VALUE
                && now - lastMcuAssertAtMs < s.reassertMillis()
                && (mcuLastWriteRejected || mcuWakeAttempts >= WAKE_FAST_RETRY_ATTEMPTS)) {
            // A rejected write, or exhausted fast wake retries: stay on the
            // re-assert cadence instead of hammering the HAL every tick.
            return;
        }
        lastMcuAssertAtMs = now;
        // MCU power hold (-1442840502 <- 1) FIRST and UNCONDITIONALLY — no
        // MCU-status precondition. OEM-app parity: its default (V1) mode writes
        // exactly this on ACC OFF; the 1 IS the wake request, so gating it on
        // getMcuStatus() would skip it precisely when it is needed (the
        // documented DiLink 4 rail-collapse mistake). Idempotent on re-asserts.
        if (s.isMcuPowerHoldEffective()) {
            int holdRc = safeWriteMcuPowerHold(generation, true);
            mcuPowerHoldLastRc = holdRc;
            if (holdRc == 0) {
                if (!mcuPowerHoldAsserted) {
                    hardware.log("Di5 keep-alive: MCU power hold -1442840502<-1 confirmed"
                            + " (OEM-parity hold)");
                }
                mcuPowerHoldAsserted = true;
            } else if (!mcuPowerHoldAsserted) {
                hardware.log("Di5 keep-alive: MCU power hold write rc=" + holdRc
                        + " — logged no-op, retry on cadence");
            }
        }
        int status = safeMcuStatus(generation);
        mcuStatusBefore = status;
        if (!isMcuAwake(status)) {
            mcuWakeAttempts++;
            boolean requested = false;
            try {
                requested = hardware.wakeUpMcu(generation);
            } catch (Throwable t) {
                hardware.log("Di5 keep-alive: wakeUpMcu threw: " + t.getMessage());
            }
            hardware.log("Di5 keep-alive: MCU status " + status + " (asleep) — wakeUpMcu() "
                    + (requested ? "accepted" : "not accepted") + ", attempt " + mcuWakeAttempts);
            long waited = 0L;
            while (waited < WAKE_POLL_MAX_MS) {
                try {
                    hardware.sleepMs(WAKE_POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    mcuStatus = "wake interrupted";
                    return;
                }
                waited += WAKE_POLL_INTERVAL_MS;
                if (releaseRequested) {
                    mcuStatus = "release requested during wake";
                    return;
                }
                if (!generationCheck.isCurrentParkedGeneration(generation)) {
                    mcuStatus = "generation changed during wake";
                    return;
                }
                status = safeMcuStatus(generation);
                if (isMcuAwake(status)) break;
            }
            if (!isMcuAwake(status)) {
                mcuStatus = "mcu asleep (status=" + status + ", attempts=" + mcuWakeAttempts + ")";
                mcuStatusAfter = status;
                hardware.log("Di5 keep-alive: MCU still asleep after " + waited
                        + " ms — flags NOT written; will retry");
                return;
            }
        }
        mcuStatusAfter = status;
        int[] rcs = safeWriteSentryFlags(generation, true);
        mcuLastRcEnter = rcs[0];
        mcuLastRcState = rcs[1];
        boolean confirmed = rcs[0] == 0 && rcs[1] == 0;
        if (confirmed) {
            if (!mcuHeld) {
                hardware.log("Di5 keep-alive: sentry flags 782237711=1, 782237728=1 confirmed"
                        + " (mcuStatus " + mcuStatusBefore + "→" + status + ")");
            } else {
                mcuReasserts++;
            }
            mcuHeld = true;
            mcuWakeAttempts = 0;
            mcuLastWriteRejected = false;
            mcuStatus = "held";
        } else {
            mcuLastWriteRejected = true;
            mcuStatus = "rejected (rc " + rcs[0] + "/" + rcs[1] + ")";
            hardware.log("Di5 keep-alive: sentry flag write NOT confirmed rc=" + rcs[0]
                    + "/" + rcs[1] + " — logged no-op, retry on cadence");
            // A partially landed pair is still "ours" to clean up.
            if (rcs[0] == 0 || rcs[1] == 0) mcuHeld = true;
        }
    }

    private void releaseMcuLocked(String reason) {
        int[] rcs = safeWriteSentryFlags(heldGeneration, false);
        mcuLastRcEnter = rcs[0];
        mcuLastRcState = rcs[1];
        hardware.log("Di5 keep-alive: sentry flags released (782237711=0, 782237728=2) rc="
                + rcs[0] + "/" + rcs[1] + " — " + reason);
        mcuHeld = false;
        mcuLastWriteRejected = false;
        mcuStatus = "released";
        lastMcuAssertAtMs = Long.MIN_VALUE;
    }

    /**
     * Release the MCU power hold ({@code -1442840502 ← 0}). Called only when
     * this install asserted the 1 (or a marker recorded it): the 0 means
     * "request MCU sleep", so it must never be issued as a bare cleanup.
     */
    private void releaseMcuPowerLocked(String reason) {
        int rc = safeWriteMcuPowerHold(heldGeneration, false);
        mcuPowerHoldLastRc = rc;
        hardware.log("Di5 keep-alive: MCU power hold released (-1442840502=0) rc="
                + rc + " — " + reason);
        mcuPowerHoldAsserted = false;
    }

    // ── Camera heartbeat ───────────────────────────────────────────

    private void assertCameraLocked(long generation) {
        if (cameraHeld && heartbeatRunning && heartbeatGeneration == generation) {
            return;
        }
        int rc = safeWritePanorama(1);
        heartbeatLastRc = rc;
        hardware.log("Di5 keep-alive: PANORAMA_WORK_MODE_SET=1 rc=" + rc
                + " — starting 2 s heartbeat for generation " + generation);
        cameraHeld = true;
        PanoramaWorkModeOwner.setParkedHoldActive(true);
        startHeartbeatLocked(generation);
    }

    private void releaseCameraLocked(String reason) {
        stopHeartbeatLocked();
        int rc = safeWritePanorama(0);
        heartbeatLastRc = rc;
        hardware.log("Di5 keep-alive: PANORAMA_WORK_MODE_SET=0 rc=" + rc + " — " + reason);
        cameraHeld = false;
        PanoramaWorkModeOwner.setParkedHoldActive(false);
    }

    private void startHeartbeatLocked(long generation) {
        stopHeartbeatLocked();
        heartbeatGeneration = generation;
        heartbeatRunning = true;
        Thread t = new Thread(() -> runHeartbeat(generation), "Di5KeepAliveHeartbeat");
        t.setDaemon(true);
        heartbeatThread = t;
        lastHeartbeatThread = t;
        try {
            t.start();
        } catch (Throwable failure) {
            heartbeatRunning = false;
            heartbeatThread = null;
            hardware.log("Di5 keep-alive: heartbeat thread could not start: " + failure.getMessage());
        }
    }

    private void stopHeartbeatLocked() {
        heartbeatRunning = false;
        Thread t = heartbeatThread;
        heartbeatThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
        }
    }

    private void runHeartbeat(long generation) {
        while (heartbeatRunning && heartbeatGeneration == generation) {
            try {
                hardware.sleepMs(HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
            if (!heartbeatRunning || heartbeatGeneration != generation) break;
            if (!generationCheck.isCurrentParkedGeneration(generation)) {
                hardware.log("Di5 keep-alive: heartbeat generation " + generation
                        + " no longer parked — stopping");
                break;
            }
            int rc = safeWritePanorama(1);
            heartbeatLastRc = rc;
            heartbeatTicks++;
            if (heartbeatTicks % 30 == 0) {
                try {
                    heartbeatLastReadBack = hardware.readPanoramaWorkMode();
                } catch (Throwable ignored) {
                }
                hardware.log("Di5 keep-alive: heartbeat tick " + heartbeatTicks
                        + " rc=" + rc + " readBack=" + heartbeatLastReadBack);
            }
        }
        if (heartbeatThread == Thread.currentThread()) {
            heartbeatThread = null;
        }
    }

    // ── AP hold (experimental) ─────────────────────────────────────

    static String apHoldApplyScript() {
        return "CUR=$(getprop " + AP_HOLD_LIST_PROPERTY + "); echo \"BEFORE=$CUR\"; "
                + "case \" $CUR \" in *\" " + AP_HOLD_TOKEN + " \"*) ;; *) "
                + "setprop " + AP_HOLD_LIST_PROPERTY + " \"${CUR:+$CUR }" + AP_HOLD_TOKEN
                + "\" || exit 2;; esac; "
                + "setprop " + AP_HOLD_STATE_PROPERTY + " ONLINE || exit 3; "
                + "[ \"$(getprop " + AP_HOLD_STATE_PROPERTY + ")\" = ONLINE ] || exit 4; "
                + "NEW=$(getprop " + AP_HOLD_LIST_PROPERTY + "); echo \"AFTER=$NEW\"; "
                + "case \" $NEW \" in *\" " + AP_HOLD_TOKEN + " \"*) exit 0;; *) exit 5;; esac";
    }

    static String apHoldCheckScript() {
        return "[ \"$(getprop " + AP_HOLD_STATE_PROPERTY + ")\" = ONLINE ] || exit 4; "
                + "case \" $(getprop " + AP_HOLD_LIST_PROPERTY + ") \" in *\" "
                + AP_HOLD_TOKEN + " \"*) exit 0;; *) exit 5;; esac";
    }

    /** Strips ONLY our token; every other entry is preserved verbatim. */
    static String apHoldReleaseScript() {
        return "setprop " + AP_HOLD_STATE_PROPERTY + " OFFLINE; "
                + "CUR=$(getprop " + AP_HOLD_LIST_PROPERTY + "); NEW=\"\"; "
                + "for t in $CUR; do [ \"$t\" = \"" + AP_HOLD_TOKEN + "\" ] || NEW=\"${NEW:+$NEW }$t\"; done; "
                + "[ \"$NEW\" = \"$CUR\" ] || setprop " + AP_HOLD_LIST_PROPERTY + " \"$NEW\"; "
                + "case \" $(getprop " + AP_HOLD_LIST_PROPERTY + ") \" in *\" "
                + AP_HOLD_TOKEN + " \"*) exit 5;; *) exit 0;; esac";
    }

    private void assertApLocked() {
        if (apHeld) {
            ShellOutcome check = safeShell(apHoldCheckScript());
            if (check.ok()) return;
            hardware.log("Di5 keep-alive: AP hold token missing (exit " + check.exitCode
                    + ") — re-applying");
            apHeld = false;
        }
        if ("denied".equals(apStatus)) {
            apDeniedTicks++;
            if (apDeniedTicks % AP_DENIED_RECHECK_TICKS != 0) return;
        }
        ShellOutcome r = safeShell(apHoldApplyScript());
        apLastExit = r.exitCode;
        apListBefore = extract(r.output, "BEFORE=");
        apListAfter = extract(r.output, "AFTER=");
        if (r.ok()) {
            apHeld = true;
            apStatus = "held";
            apDeniedTicks = 0;
            hardware.log("Di5 keep-alive: AP hold applied — " + AP_HOLD_LIST_PROPERTY
                    + " \"" + apListBefore + "\" → \"" + apListAfter + "\", "
                    + AP_HOLD_STATE_PROPERTY + "=ONLINE");
        } else if (r.exitCode == 2 || r.exitCode == 3) {
            apStatus = "denied";
            hardware.log("Di5 keep-alive: AP hold setprop DENIED (exit " + r.exitCode
                    + ", " + r.output.trim() + ") — logged no-op; re-probing every "
                    + AP_DENIED_RECHECK_TICKS + " ticks");
        } else {
            apStatus = "failed (exit " + r.exitCode + ")";
            hardware.log("Di5 keep-alive: AP hold not verified (exit " + r.exitCode + ")");
        }
    }

    private void releaseApLocked(String reason) {
        ShellOutcome r = safeShell(apHoldReleaseScript());
        apLastExit = r.exitCode;
        hardware.log("Di5 keep-alive: AP hold released (exit " + r.exitCode + ") — " + reason);
        apHeld = false;
        apStatus = r.ok() ? "released" : "release failed (exit " + r.exitCode + ")";
    }

    // ── Release ────────────────────────────────────────────────────

    private void releaseLocked(String reason) {
        boolean anything = isActiveLocked();
        Di5KeepAliveOwnershipMarker.Record rec = marker.read();
        boolean recCamera = rec != null && rec.has(Di5KeepAliveOwnershipMarker.LEVER_CAMERA);
        boolean recMcu = rec != null && rec.has(Di5KeepAliveOwnershipMarker.LEVER_MCU);
        boolean recMcuPower = rec != null
                && rec.has(Di5KeepAliveOwnershipMarker.LEVER_MCU_POWER);
        boolean recAp = rec != null && rec.has(Di5KeepAliveOwnershipMarker.LEVER_AP);
        if (anything || rec != null) {
            hardware.log("Di5 keep-alive RELEASE (" + reason + ") held={mcu=" + mcuHeld
                    + ", mcuPower=" + mcuPowerHoldAsserted
                    + ", camera=" + cameraHeld + ", ap=" + apHeld + "} recorded="
                    + (rec == null ? "none" : rec.levers));
        }
        releaseLeversLocked(cameraHeld || recCamera, mcuHeld || recMcu,
                mcuPowerHoldAsserted || recMcuPower, apHeld || recAp);
        if (rec != null && !marker.clear()) {
            hardware.log("Di5 keep-alive: ownership marker could not be removed after release");
        }
        if (anything) {
            lastStopReason = reason;
            lastStopAtMs = hardware.nowMs();
        }
        heldGeneration = -1L;
    }

    private void releaseLeversLocked(boolean camera, boolean mcu, boolean mcuPower, boolean ap) {
        // Order: heartbeat first (stop writing 1), then sentry flags, then the
        // MCU power hold (0 only when asserted/recorded), then AP.
        if (camera) releaseCameraLocked("release");
        else stopHeartbeatLocked();
        if (mcu) releaseMcuLocked("release");
        if (mcuPower) releaseMcuPowerLocked("release");
        if (ap) releaseApLocked("release");
    }

    private boolean isActiveLocked() {
        return mcuHeld || mcuPowerHoldAsserted || cameraHeld || apHeld;
    }

    // ── Status ─────────────────────────────────────────────────────

    private JSONObject statusLocked() {
        JSONObject o = new JSONObject();
        try {
            long now = hardware.nowMs();
            o.put("present", true);
            o.put("settings", settings.toJson());
            o.put("desired", desired);
            o.put("active", isActiveLocked());
            o.put("requestedGeneration", requestedGeneration);
            o.put("heldGeneration", heldGeneration);
            o.put("ticks", ticks);
            o.put("lastTickAt", lastTickAtMs);
            o.put("updatedAt", now);
            o.put("pid", hardware.pid());

            JSONObject mcu = new JSONObject();
            mcu.put("held", mcuHeld);
            mcu.put("status", mcuStatus);
            mcu.put("statusBefore", mcuStatusBefore);
            mcu.put("statusAfter", mcuStatusAfter);
            mcu.put("wakeAttempts", mcuWakeAttempts);
            mcu.put("reasserts", mcuReasserts);
            mcu.put("lastRcEnter", mcuLastRcEnter);
            mcu.put("lastRcState", mcuLastRcState);
            mcu.put("powerHoldAsserted", mcuPowerHoldAsserted);
            mcu.put("powerHoldLastRc", mcuPowerHoldLastRc);
            mcu.put("lastAssertAt", lastMcuAssertAtMs == Long.MIN_VALUE ? 0L : lastMcuAssertAtMs);
            o.put("mcu", mcu);

            JSONObject cam = new JSONObject();
            cam.put("held", cameraHeld);
            cam.put("heartbeatRunning", heartbeatRunning);
            cam.put("heartbeatTicks", heartbeatTicks);
            cam.put("lastRc", heartbeatLastRc);
            cam.put("lastReadBack", heartbeatLastReadBack == null ? JSONObject.NULL : heartbeatLastReadBack);
            o.put("camera", cam);

            JSONObject ap = new JSONObject();
            ap.put("held", apHeld);
            ap.put("status", apStatus);
            ap.put("listBefore", apListBefore);
            ap.put("listAfter", apListAfter);
            ap.put("lastExit", apLastExit);
            o.put("ap", ap);

            JSONObject v = new JSONObject();
            v.put("volts", voltageGuard.hasSample() ? voltageGuard.lastVoltage() : JSONObject.NULL);
            v.put("ageMs", voltageGuard.sampleAgeMs(now));
            v.put("decision", voltageDecision);
            v.put("consecutiveLow", voltageGuard.consecutiveLowSamples());
            v.put("latched", voltageGuard.isLatched());
            v.put("latchReason", voltageGuard.latchReason() == null ? "" : voltageGuard.latchReason());
            v.put("cutoffVoltage", voltageGuard.cutoffVoltage());
            v.put("cutoffSamples", voltageGuard.cutoffSamples());
            o.put("voltage", v);

            JSONObject stop = new JSONObject();
            stop.put("reason", lastStopReason);
            stop.put("at", lastStopAtMs);
            o.put("lastStop", stop);

            Di5KeepAliveOwnershipMarker.Record rec = marker.read();
            o.put("marker", rec == null ? JSONObject.NULL : rec.toJson());
            String pm = safePowerModeLine();
            o.put("powerMode", pm == null ? JSONObject.NULL : pm);
        } catch (Exception e) {
            try {
                o.put("error", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    /** Refresh cadence for the status file while a lever is held (volts/age). */
    static final long STATUS_REFRESH_MS = 60_000L;
    private String lastStatusSignature;
    private long lastStatusWriteAtMs = Long.MIN_VALUE;

    /**
     * Compact state fingerprint. The status file is rewritten only when this
     * changes (or once a minute while a lever is held), so a parked DI5 unit with
     * the feature OFF costs one write per state change, not one per 10 s tick.
     */
    private String statusSignatureLocked() {
        return settings.toString() + '|' + desired + '|' + requestedGeneration + '|'
                + heldGeneration + '|' + mcuHeld + '|' + mcuStatus + '|'
                + mcuPowerHoldAsserted + '|' + cameraHeld + '|'
                + heartbeatRunning + '|' + apHeld + '|' + apStatus + '|' + voltageDecision
                + '|' + voltageGuard.isLatched() + '|' + lastStopReason;
    }

    private void writeStatusLocked() {
        if (statusFile == null) return;
        String signature = statusSignatureLocked();
        long now = hardware.nowMs();
        if (signature.equals(lastStatusSignature)
                && (!isActiveLocked() || now - lastStatusWriteAtMs < STATUS_REFRESH_MS)) {
            return;
        }
        try {
            byte[] bytes = statusLocked().toString().getBytes(StandardCharsets.UTF_8);
            File dir = statusFile.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return;
            File tmp = new File(statusFile.getAbsolutePath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(bytes);
            }
            try {
                Files.move(tmp.toPath(), statusFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicFailure) {
                Files.move(tmp.toPath(), statusFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            lastStatusSignature = signature;
            lastStatusWriteAtMs = now;
        } catch (Throwable ignored) {
            // Status is diagnostic only; never let it disturb the lease.
        }
    }

    // ── Safe hardware wrappers ─────────────────────────────────────

    private Di5ParkedKeepAliveSettings safeSettings() {
        try {
            Di5ParkedKeepAliveSettings s = settingsSupplier.get();
            return s != null ? s : Di5ParkedKeepAliveSettings.disabled();
        } catch (Throwable t) {
            return Di5ParkedKeepAliveSettings.disabled();
        }
    }

    private int safeMcuStatus(long generation) {
        try {
            return hardware.getMcuStatus(generation);
        } catch (Throwable t) {
            hardware.log("Di5 keep-alive: getMcuStatus threw: " + t.getMessage());
            return -1;
        }
    }

    private int[] safeWriteSentryFlags(long generation, boolean assertFlags) {
        try {
            int[] rcs = hardware.writeSentryFlags(generation, assertFlags);
            if (rcs == null || rcs.length < 2) {
                return new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE};
            }
            return rcs;
        } catch (Throwable t) {
            hardware.log("Di5 keep-alive: sentry flag write threw: " + t.getMessage());
            return new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE};
        }
    }

    private int safeWriteMcuPowerHold(long generation, boolean hold) {
        try {
            return hardware.writeMcuPowerHold(generation, hold);
        } catch (Throwable t) {
            hardware.log("Di5 keep-alive: MCU power hold write threw: " + t.getMessage());
            return Integer.MIN_VALUE;
        }
    }

    private int safeWritePanorama(int value) {
        try {
            return hardware.writePanoramaWorkMode(value);
        } catch (Throwable t) {
            hardware.log("Di5 keep-alive: panorama write threw: " + t.getMessage());
            return PanoramaWorkModeOwner.RC_UNAVAILABLE;
        }
    }

    private ShellOutcome safeShell(String command) {
        try {
            ShellOutcome r = hardware.shell(command);
            return r != null ? r : new ShellOutcome(-1, "no result");
        } catch (Throwable t) {
            return new ShellOutcome(-1, String.valueOf(t.getMessage()));
        }
    }

    private String safePowerModeLine() {
        try {
            return hardware.powerModeLine();
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean isMcuAwake(int status) {
        return status == MCU_STATUS_ACTIVE || status == MCU_STATUS_ACTIVE_ALT;
    }

    private static String extract(String output, String prefix) {
        if (output == null) return "";
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith(prefix)) return t.substring(prefix.length()).trim();
        }
        return "";
    }
}
