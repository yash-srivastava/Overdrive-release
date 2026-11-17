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
 * DiLink 5 parked keep-alive lease.
 *
 * <p>Holds the local parked transport state, MCU/sentry rails, optionally the
 * panorama producer, and — only after a recorded on-car pre-flight — a vendor
 * shutdown-critical token, while the vehicle is parked and the user has opted in. Runs inside
 * {@code acc_sentry_daemon} and is driven by two callers only:
 * <ul>
 *   <li>{@link #requestForTransition(long, boolean)} from a generation-fenced
 *       {@code LatestBooleanReconciler} on every sentry transition (ACC OFF →
 *       desired, ACC ON → locally detached without parked link STOP);</li>
 *   <li>{@link #tick(long)} from the daemon's 10 s keep-alive loop, which
 *       re-reads the configuration, samples the 12 V battery when available and
 *       re-asserts or releases the levers.</li>
 * </ul>
 *
 * <p>Invariants:
 * <ul>
 *   <li>Nothing is written while the master switch is off or the kill switch is
 *       set. Because the DiLink 5 OTA voltage API is unavailable from the shell
 *       daemon on some vehicles, a missing/stale sample is diagnostic-only once
 *       the user explicitly enables this feature. A fresh,
 *       confirmed low-voltage sequence still releases and latches every lever.</li>
 *   <li>Deliberately NOT gated on the camera-mode selection: DiLink 5 head units
 *       exist with and without the QCarCam camera stack, so the user's opt-in
 *       toggle is the platform declaration. The lease is installed on every
 *       platform and stays inert until that toggle is ON.</li>
 *   <li>The {@link Di5KeepAliveOwnershipMarker} is written BEFORE the first HAL
 *       write and removed AFTER the last release; start-up hygiene releases only
 *       what the marker records.</li>
 *   <li>parked link START is callback-confirmed and periodically re-asserted.
 *       ACC ON cancels local ownership and unbinds without sending global STOP;
 *       STOP is reserved for a deliberate parked-session end (toggle/kill switch,
 *       voltage cutoff, or daemon exit while still parked).</li>
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
            com.overdrive.app.util.ScratchRoot.path("overdrive_di5_keepalive.status");

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
    /** No status at all: SL7 firmware's getMcuStatus()/wakeUpMcu() are hard-coded -1 stubs. */
    static final int MCU_STATUS_UNAVAILABLE = -1;
    /** Status/wake polling stops for the park after the MCU has answered nothing for this long... */
    static final long MCU_UNAVAILABLE_GIVE_UP_MS = 10L * 60_000L;
    /** ...and for at least this many attempts, so a wall-clock step cannot shorten it. */
    static final int MCU_UNAVAILABLE_MIN_ATTEMPTS = 15;

    /** Bounded per-attempt wait for the MCU to come up after wakeUpMcu(). */
    static final long WAKE_POLL_INTERVAL_MS = 1_000L;
    static final long WAKE_POLL_MAX_MS = 10_000L;
    /** After this many failed wake attempts, retry only on the re-assert cadence. */
    static final int WAKE_FAST_RETRY_ATTEMPTS = 6;

    /** Heartbeat cadence for PANORAMA_WORK_MODE_SET=1 (reference: 2 s). */
    static final long HEARTBEAT_INTERVAL_MS = 2_000L;

    /** After an AP-hold permission denial, re-probe only every N ticks. */
    static final int AP_DENIED_RECHECK_TICKS = 10;

    static final int PARKED_LINK_RESULT_CONFIRMED = ParkedLinkContract.RESULT_CONFIRMED;
    static final int PARKED_LINK_RESULT_DISPATCHED_UNCONFIRMED =
            ParkedLinkContract.RESULT_DISPATCHED_UNCONFIRMED;

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
        /**
         * Local parked hold through the permission-protected app-process receiver.
         * {@code start=true} acquires/reasserts; {@code false} releases.
         *
         * @return 1 when asynchronously confirmed, 2 when dispatched but the
         *         callback timed out, 0 on an asynchronous parked link rejection, or a
         *         negative local transport/binding error.
         */
        int writeParkedLink(long generation, boolean start);
        /**
         * ACC-ON logical release: detach the app-process transport without
         * issuing a remote release.
         */
        int detachParkedLink(long generation);
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
        releaseForProcessExit(reason, true);
    }

    /**
     * Process-exit release with an explicit ACC verdict. A parked exit sends the
     * parked link STOP; an ACC-ON exit only detaches the app-process binding.
     */
    public static void releaseForProcessExit(String reason, boolean vehicleParked) {
        Di5ParkedPowerHold h = installed;
        if (h != null) {
            try {
                if (vehicleParked) {
                    h.release(reason);
                } else {
                    h.releaseForAccOn(reason);
                }
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
    /** START was confirmed or at least dispatched and has not had a confirmed STOP. */
    private boolean parkedLinkHeld;
    private boolean parkedLinkConfirmed;
    private String parkedLinkStatus = "idle";
    private int parkedLinkLastResult = Integer.MIN_VALUE;
    private long lastParkedLinkAssertAtMs = Long.MIN_VALUE;
    private int parkedLinkReasserts;
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
    /** Consecutive attempts where the MCU reported no status and refused the wake. */
    private int mcuUnavailableStreak;
    private long mcuUnavailableSinceMs;
    /** The MCU answered this generation: a real status, an accepted wake, or confirmed flags. */
    private boolean mcuHalAnswered;
    /** Parked generation whose status/wake polling stopped because the MCU never answered. */
    private long mcuUnavailableGeneration = -1L;
    private String mcuStatus = "idle";
    /**
     * Set by {@link #release(String)} before it waits for {@link #lock}, so a tick
     * that is inside the bounded MCU wake poll gives the lock up within one poll
     * interval instead of finishing the full wait first.
     */
    private volatile boolean releaseRequested;
    /** ACC-ON transition requested while an assertion is inside bounded I/O. */
    private volatile boolean accOnRequested;
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
        if (!desired) accOnRequested = true;
        try {
            synchronized (lock) {
                if (generation < requestedGeneration) {
                    return true; // stale request
                }
                boolean newGeneration = generation != requestedGeneration;
                requestedGeneration = generation;
                this.desired = desired;
                if (!desired) {
                    // ACC ON ends local scheduling and clears the voltage latch.
                    // The parked link command is global and has no owner/source
                    // correlation, so do NOT send STOP on the ignition edge.
                    voltageGuard.releaseLatch("ACC ON / lease end");
                    releaseLocked("transition:" + generation, false);
                    voltageGuard.resetSamples();
                    writeStatusLocked();
                    return true;
                }
                if (newGeneration) {
                    voltageGuard.resetSamples();
                    mcuWakeAttempts = 0;
                    resetMcuAvailabilityLocked(true);
                }
                evaluateLocked(generation, "transition");
                return true;
            }
        } finally {
            if (!desired) accOnRequested = false;
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
                releaseLocked(reason, true);
                writeStatusLocked();
            }
        } finally {
            releaseRequested = false;
        }
    }

    /**
     * ACC-ON logical release. Every local hardware lever is released, but the
     * app-process OTA binding is merely detached and parked link STOP is not sent.
     */
    public void releaseForAccOn(String reason) {
        accOnRequested = true;
        try {
            synchronized (lock) {
                desired = false;
                voltageGuard.releaseLatch("ACC ON / lease end");
                releaseLocked(reason, false);
                voltageGuard.resetSamples();
                writeStatusLocked();
            }
        } finally {
            accOnRequested = false;
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
                    + " and ACC reads ON — releasing local levers and detaching parked link"
                    + " without STOP");
            if (rec.has(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK)) {
                detachParkedLinkLocked(rec.generation, "startup hygiene");
            }
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
                releaseLocked(
                        "generation " + generation + " no longer parked (" + source + ")",
                        false);
            }
            writeStatusLocked();
            return;
        }

        Di5ParkedKeepAliveSettings s = safeSettings();
        boolean settingsChanged = !s.toString().equals(settings.toString());
        settings = s;
        voltageGuard.reconfigure(s.cutoffVoltage, s.cutoffSamples, s.voltageMaxAgeMillis());

        if (!s.isEnabled() || !s.hasAnyEffectiveLever()) {
            if (isActiveLocked() || marker.exists()) {
                releaseLocked(
                        s.killSwitchActive ? "kill switch" : "disabled in config",
                        true);
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
            releaseLocked("voltage cutoff", true);
            try { com.overdrive.app.server.timeline.TimelineJournal.note("power", "stopped", "lv_low", null); } catch (Throwable ignored) { }
            writeStatusLocked();
            return;
        }
        switch (decision) {
            case CUTOFF_LATCHED:
                if (isActiveLocked()) {
                    releaseLocked("voltage cutoff latched", true);
                }
                writeStatusLocked();
                return;
            case NO_SAMPLE:
                if (ticks % 6 == 1 || "transition".equals(source)) {
                    hardware.log("Di5 keep-alive: 12V sample unavailable — continuing because "
                            + "Parked keep-alive is explicitly enabled");
                }
                break;
            case STALE:
                if (ticks % 6 == 0) {
                    hardware.log("Di5 keep-alive: 12V sample stale ("
                            + voltageGuard.sampleAgeMs(now) + " ms) — continuing because "
                            + "Parked keep-alive is explicitly enabled");
                }
                break;
            case ALLOW:
            default:
                break;
        }

        // Ownership marker BEFORE the first HAL write. A parked link START that was
        // definitively rejected is omitted during its retry cooldown; otherwise
        // a crash between ticks could later authorize a global STOP for a START
        // that never reached parked link.
        Di5KeepAliveOwnershipMarker.Record existing = marker.read();
        boolean existingLink = existing != null
                && existing.has(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK);
        List<String> wanted = new ArrayList<>();
        if (s.isParkedLinkEffective()
                && (parkedLinkHeld
                        || existingLink
                        || isParkedLinkAssertDue(now, s))) {
            wanted.add(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK);
        }
        if (s.isMcuHoldEffective()) wanted.add(Di5KeepAliveOwnershipMarker.LEVER_MCU);
        if (s.isMcuPowerHoldEffective()) wanted.add(Di5KeepAliveOwnershipMarker.LEVER_MCU_POWER);
        if (s.isCameraHeartbeatEffective()) wanted.add(Di5KeepAliveOwnershipMarker.LEVER_CAMERA);
        if (s.isApHoldEffective()) wanted.add(Di5KeepAliveOwnershipMarker.LEVER_AP);
        boolean markerCurrent = existing == null
                ? wanted.isEmpty()
                : existing.generation == generation
                        && existing.levers.containsAll(wanted)
                        && wanted.containsAll(existing.levers);
        if (!markerCurrent) {
            // Union with anything already held so a lever that is being turned
            // off stays recorded until it is actually released below.
            List<String> toRecord = new ArrayList<>(wanted);
            if ((parkedLinkHeld || existingLink)
                    && !toRecord.contains(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK)) {
                toRecord.add(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK);
            }
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

        // Levers that are no longer wanted come off first. parked link STOP is sent
        // while the AP/MCU are still available; the sentry pair then comes off
        // before the power-hold 0.
        if (!s.isParkedLinkEffective()
                && (parkedLinkHeld || existingLink)) {
            if (releaseParkedLinkLocked(generation, "lever disabled")) {
                if (wanted.isEmpty()) {
                    marker.clear();
                } else if (!marker.record(
                        generation, wanted, now, hardware.pid())) {
                    hardware.log("Di5 keep-alive: parked link STOP landed but ownership "
                            + "marker could not be narrowed to remaining levers");
                }
            }
        }
        if (!s.isCameraHeartbeatEffective() && cameraHeld) releaseCameraLocked("lever disabled");
        if (!s.isMcuHoldEffective() && mcuHeld) releaseMcuLocked("lever disabled");
        if (!s.isMcuPowerHoldEffective() && mcuPowerHoldAsserted) {
            releaseMcuPowerLocked("lever disabled");
        }
        if (!s.isApHoldEffective() && apHeld) releaseApLocked("lever disabled");

        // Then assert / re-assert the wanted ones. parked link first, before a bounded
        // MCU wake poll can consume the remaining ACC-OFF transition window.
        // A concurrent release/ACC-ON request wins:
        // stop adding levers and let it take the lock.
        if (s.isParkedLinkEffective()) {
            assertParkedLinkLocked(generation, now, s, existingLink);
        }
        if (isReleaseRequested()) {
            writeStatusLocked();
            return;
        }
        if (s.isMcuHoldEffective()) assertMcuLocked(generation, now, s);
        if (isReleaseRequested()) {
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

    // ── parked transport network hold ───────────────────────────────────────

    private void assertParkedLinkLocked(
            long generation, long now, Di5ParkedKeepAliveSettings s,
            boolean previouslyRecorded) {
        if (!isParkedLinkAssertDue(now, s)) return;
        if (!generationCheck.isCurrentParkedGeneration(generation)
                || isReleaseRequested()) {
            return;
        }

        boolean alreadyHeld = parkedLinkHeld;
        lastParkedLinkAssertAtMs = now;
        int result = safeWriteParkedLink(generation, true);
        parkedLinkLastResult = result;
        if (result == PARKED_LINK_RESULT_CONFIRMED
                || result == PARKED_LINK_RESULT_DISPATCHED_UNCONFIRMED) {
            parkedLinkHeld = true;
            parkedLinkConfirmed =
                    result == PARKED_LINK_RESULT_CONFIRMED;
            if (alreadyHeld) parkedLinkReasserts++;
            parkedLinkStatus = parkedLinkConfirmed
                    ? "held"
                    : "start dispatched; callback unconfirmed";
            hardware.log("Di5 keep-alive: parked link START "
                    + (parkedLinkConfirmed
                            ? "confirmed" : "dispatched without callback")
                    + " for generation " + generation);
        } else if (alreadyHeld) {
            parkedLinkStatus =
                    "held; reassert failed (result=" + result + ")";
            hardware.log("Di5 keep-alive: parked link START reassert failed result="
                    + result + " — retaining prior hold state");
        } else {
            boolean unavailable = result == ParkedLinkContract.RESULT_SERVICE_UNAVAILABLE
                    || result == ParkedLinkContract.RESULT_UNAUTHORIZED
                    || result == ParkedLinkContract.RESULT_LIBRARY_UNAVAILABLE
                    || result == ParkedLinkContract.RESULT_LIBRARY_INCOMPATIBLE;
            parkedLinkStatus = (unavailable ? "unavailable" : "start failed")
                    + " (result=" + result + ")";
            hardware.log("Di5 keep-alive: parked link START failed result="
                    + result + " — retrying on cadence");
            // A failed new assertion proves nothing about ownership recorded
            // before this attempt (including an earlier app/daemon process).
            if (!previouslyRecorded && linkStartDefinitelyNotApplied(result)) {
                dropDefiniteLinkOwnershipLocked(generation, result);
            }
        }
    }

    private boolean isParkedLinkAssertDue(
            long now, Di5ParkedKeepAliveSettings s) {
        return lastParkedLinkAssertAtMs == Long.MIN_VALUE
                || now - lastParkedLinkAssertAtMs >= s.reassertMillis();
    }

    /**
     * Remove the pre-write marker only when the transport contract proves that
     * START did not land. Transaction failures and missing broadcast results
     * stay recorded because the remote side may have accepted the command.
     */
    private void dropDefiniteLinkOwnershipLocked(
            long generation, int result) {
        Di5KeepAliveOwnershipMarker.Record rec = marker.read();
        if (rec == null
                || rec.generation != generation
                || !rec.has(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK)) {
            return;
        }
        List<String> remaining = new ArrayList<>(rec.levers);
        remaining.remove(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK);
        boolean updated = remaining.isEmpty()
                ? marker.clear()
                : marker.record(
                        generation, remaining,
                        hardware.nowMs(), hardware.pid());
        if (!updated) {
            hardware.log("Di5 keep-alive: could not remove definite non-dispatched "
                    + "parked link START from ownership marker (result=" + result + ")");
        }
    }

    private static boolean linkStartDefinitelyNotApplied(int result) {
        return ParkedLinkContract.definitelyNotApplied(result);
    }

    /**
     * Sends the global parked link STOP only for an intentional parked-session release.
     * A failed/unconfirmed STOP keeps ownership live so the marker and next tick
     * retry it rather than silently leaving a battery-consuming hold behind.
     */
    private boolean releaseParkedLinkLocked(
            long generation, String reason) {
        int result = safeWriteParkedLink(generation, false);
        parkedLinkLastResult = result;
        if (result == PARKED_LINK_RESULT_CONFIRMED) {
            parkedLinkHeld = false;
            parkedLinkConfirmed = false;
            parkedLinkStatus = "released";
            lastParkedLinkAssertAtMs = Long.MIN_VALUE;
            hardware.log("Di5 keep-alive: parked link STOP confirmed — "
                    + reason);
            return true;
        }

        // A dispatched-but-unconfirmed STOP may already have landed, but
        // retrying the idempotent STOP is safer than dropping ownership.
        parkedLinkHeld = true;
        parkedLinkConfirmed = false;
        parkedLinkStatus =
                result == PARKED_LINK_RESULT_DISPATCHED_UNCONFIRMED
                        ? "stop dispatched; callback unconfirmed"
                        : "stop failed (result=" + result + ")";
        hardware.log("Di5 keep-alive: parked link STOP not confirmed result="
                + result + " — retaining marker for retry (" + reason + ")");
        return false;
    }

    /** ACC-ON path: detach the app-side Binder without sending global parked link STOP. */
    private void detachParkedLinkLocked(
            long generation, String reason) {
        int result = safeDetachParkedLink(generation);
        parkedLinkLastResult = result;
        parkedLinkHeld = false;
        parkedLinkConfirmed = false;
        parkedLinkStatus = result == PARKED_LINK_RESULT_CONFIRMED
                ? "detached on ACC ON; STOP skipped"
                : "detach failed (result=" + result + "); STOP skipped";
        lastParkedLinkAssertAtMs = Long.MIN_VALUE;
        hardware.log("Di5 keep-alive: parked link binding detached; "
                + "STOP intentionally skipped — " + reason
                + " result=" + result);
    }

    // ── MCU hold ───────────────────────────────────────────────────

    private void assertMcuLocked(long generation, long now, Di5ParkedKeepAliveSettings s) {
        boolean statusGivenUp = mcuUnavailableGeneration == generation;
        boolean due = !mcuHeld
                || lastMcuAssertAtMs == Long.MIN_VALUE
                || now - lastMcuAssertAtMs >= s.reassertMillis();
        if (!due) {
            if (statusGivenUp) return;
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
            } else if (!mcuPowerHoldAsserted && !statusGivenUp) {
                hardware.log("Di5 keep-alive: MCU power hold write rc=" + holdRc
                        + " — logged no-op, retry on cadence");
            }
        }
        // The power hold keeps its cadence; status/wake polling has stopped for this park.
        if (statusGivenUp) return;
        int status = safeMcuStatus(generation);
        mcuStatusBefore = status;
        if (!isMcuAwake(status)) {
            boolean statusAnswered = status != MCU_STATUS_UNAVAILABLE;
            mcuWakeAttempts++;
            boolean requested = false;
            try {
                requested = hardware.wakeUpMcu(generation);
            } catch (Throwable t) {
                hardware.log("Di5 keep-alive: wakeUpMcu threw: " + t.getMessage());
            }
            hardware.log("Di5 keep-alive: MCU status " + status
                    + (status == MCU_STATUS_UNAVAILABLE ? " (unavailable)" : " (asleep)")
                    + " — wakeUpMcu() "
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
                if (isReleaseRequested()) {
                    mcuStatus = "release requested during wake";
                    return;
                }
                if (!generationCheck.isCurrentParkedGeneration(generation)) {
                    mcuStatus = "generation changed during wake";
                    return;
                }
                status = safeMcuStatus(generation);
                if (status != MCU_STATUS_UNAVAILABLE) statusAnswered = true;
                if (isMcuAwake(status)) break;
            }
            if (!isMcuAwake(status)) {
                mcuStatus = "mcu asleep (status=" + status + ", attempts=" + mcuWakeAttempts + ")";
                mcuStatusAfter = status;
                hardware.log("Di5 keep-alive: MCU still asleep after " + waited
                        + " ms — flags NOT written; will retry");
                noteMcuUnavailableAttempt(generation, now, statusAnswered || requested);
                return;
            }
        }
        mcuHalAnswered = true;
        mcuUnavailableStreak = 0;
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

    /**
     * Stops status/wake polling for this park once the MCU has answered nothing at all (every
     * status read -1, wake refused) for {@link #MCU_UNAVAILABLE_GIVE_UP_MS} and
     * {@link #MCU_UNAVAILABLE_MIN_ATTEMPTS}; any real answer in this parked generation prevents it.
     */
    private void noteMcuUnavailableAttempt(long generation, long now, boolean answered) {
        if (answered) {
            mcuHalAnswered = true;
            mcuUnavailableStreak = 0;
            return;
        }
        if (mcuUnavailableStreak++ == 0) mcuUnavailableSinceMs = now;
        if (mcuHalAnswered
                || mcuUnavailableStreak < MCU_UNAVAILABLE_MIN_ATTEMPTS
                || now - mcuUnavailableSinceMs < MCU_UNAVAILABLE_GIVE_UP_MS) {
            return;
        }
        mcuUnavailableGeneration = generation;
        mcuStatus = "unavailable (status -1, wakeUpMcu refused " + mcuUnavailableStreak + "x)";
        hardware.log("Di5 keep-alive: MCU HAL unavailable (getMcuStatus -1, wakeUpMcu refused "
                + mcuUnavailableStreak + "x over " + ((now - mcuUnavailableSinceMs) / 60_000L)
                + " min) — no more status/wake polling this park; power hold and parked link hold continue");
    }

    /** Answers seen earlier in the same parked generation are kept across an in-park release. */
    private void resetMcuAvailabilityLocked(boolean newGeneration) {
        mcuUnavailableStreak = 0;
        mcuUnavailableGeneration = -1L;
        if (newGeneration) mcuHalAnswered = false;
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

    private void releaseLocked(String reason, boolean sendLinkStop) {
        boolean anything = isActiveLocked();
        Di5KeepAliveOwnershipMarker.Record rec = marker.read();
        boolean recLink = rec != null
                && rec.has(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK);
        boolean recCamera = rec != null && rec.has(Di5KeepAliveOwnershipMarker.LEVER_CAMERA);
        boolean recMcu = rec != null && rec.has(Di5KeepAliveOwnershipMarker.LEVER_MCU);
        boolean recMcuPower = rec != null
                && rec.has(Di5KeepAliveOwnershipMarker.LEVER_MCU_POWER);
        boolean recAp = rec != null && rec.has(Di5KeepAliveOwnershipMarker.LEVER_AP);
        boolean releaseLink = parkedLinkHeld || recLink;
        long releaseGeneration = heldGeneration >= 0L
                ? heldGeneration
                : rec != null ? rec.generation : requestedGeneration;
        if (anything || rec != null) {
            hardware.log("Di5 keep-alive RELEASE (" + reason + ") held={parkedLink="
                    + parkedLinkHeld + ", mcu=" + mcuHeld
                    + ", mcuPower=" + mcuPowerHoldAsserted
                    + ", camera=" + cameraHeld + ", ap=" + apHeld + "} recorded="
                    + (rec == null ? "none" : rec.levers)
                    + " linkAction=" + (sendLinkStop ? "STOP" : "DETACH"));
        }

        boolean linkReleaseConfirmed = true;
        if (releaseLink) {
            if (sendLinkStop) {
                linkReleaseConfirmed =
                        releaseParkedLinkLocked(releaseGeneration, reason);
            } else {
                detachParkedLinkLocked(releaseGeneration, reason);
            }
        }
        releaseLeversLocked(cameraHeld || recCamera, mcuHeld || recMcu,
                mcuPowerHoldAsserted || recMcuPower, apHeld || recAp);

        if (sendLinkStop && releaseLink && !linkReleaseConfirmed) {
            List<String> pending =
                    new ArrayList<>();
            pending.add(Di5KeepAliveOwnershipMarker.LEVER_PARKED_LINK);
            if (!marker.record(
                    releaseGeneration, pending,
                    hardware.nowMs(), hardware.pid())) {
                hardware.log("Di5 keep-alive: could not narrow ownership marker "
                        + "to pending parked link STOP; retaining prior marker");
            }
        } else if (rec != null || marker.exists()) {
            if (!marker.clear()) {
                hardware.log("Di5 keep-alive: ownership marker could not be removed after release");
            }
        }
        if (anything || rec != null) {
            lastStopReason = reason;
            lastStopAtMs = hardware.nowMs();
        }
        heldGeneration =
                sendLinkStop && releaseLink && !linkReleaseConfirmed
                        ? releaseGeneration : -1L;
        // A lease re-activated later in the same park polls the MCU afresh.
        resetMcuAvailabilityLocked(false);
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
        return parkedLinkHeld || mcuHeld || mcuPowerHoldAsserted
                || cameraHeld || apHeld;
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

            JSONObject link = new JSONObject();
            link.put("held", parkedLinkHeld);
            link.put("confirmed", parkedLinkConfirmed);
            link.put("status", parkedLinkStatus);
            link.put("lastResult", parkedLinkLastResult);
            link.put("reasserts", parkedLinkReasserts);
            link.put("lastAssertAt",
                    lastParkedLinkAssertAtMs == Long.MIN_VALUE
                            ? 0L : lastParkedLinkAssertAtMs);
            o.put("parkedLink", link);
            // Preserve the diagnostic alias used by earlier clients.
            o.put(ParkedLinkContract.LEGACY_STATUS_KEY, link);

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
            mcu.put("halUnavailable", mcuUnavailableGeneration >= 0L
                    && mcuUnavailableGeneration == heldGeneration);
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
                + heldGeneration + '|' + parkedLinkHeld + '|'
                + parkedLinkConfirmed + '|' + parkedLinkStatus + '|'
                + parkedLinkLastResult + '|' + mcuHeld + '|' + mcuStatus + '|'
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

    private int safeWriteParkedLink(
            long generation, boolean start) {
        try {
            return hardware.writeParkedLink(generation, start);
        } catch (Throwable t) {
            hardware.log("Di5 keep-alive: parked link "
                    + (start ? "START" : "STOP")
                    + " threw: " + t.getMessage());
            return Integer.MIN_VALUE;
        }
    }

    private int safeDetachParkedLink(long generation) {
        try {
            return hardware.detachParkedLink(generation);
        } catch (Throwable t) {
            hardware.log("Di5 keep-alive: parked link detach threw: "
                    + t.getMessage());
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

    private boolean isReleaseRequested() {
        return releaseRequested || accOnRequested;
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
