package com.overdrive.app.telemetry;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.GpsMonitor;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls BYD device APIs at 5 Hz via reflection to collect vehicle telemetry.
 * Produces immutable {@link TelemetrySnapshot} objects consumed by the overlay renderer.
 * Uses last-known-good fallback per field on device API failure.
 */
public class TelemetryDataCollector {

    private static final String TAG = "TelemetryDataCollector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // 2 Hz overlay poll. Dropped from 5 Hz → 2 Hz to cut CPU during ACC-ON
    // recording: every fast tick drives a reflective BYD-HAL sweep plus an
    // overlay bitmap re-raster + GL re-upload, so the rate is a direct CPU
    // multiplier on the recording path. Overlay text (speed/gear/pedals/turn/
    // belt) does not change meaningfully faster than ~2 Hz to a viewer, so 500ms
    // is visually indistinguishable while roughly halving the per-second
    // reflective-poll + raster cost. The tick-count constants below are
    // re-derived from this period so their WALL-CLOCK behavior is unchanged.
    private static final long POLL_INTERVAL_MS = 500; // 2 Hz — only used when overlay recording is active
    private static final long SLOW_POLL_INTERVAL_MS = 1000; // 1 Hz fallback when not recording

    // Slow-path sub-polling: seatbelts and brake-pedal state don't change fast.
    // Poll them at ~1Hz to save reflection calls per cycle.
    private static final int SLOW_FIELD_DIVISOR = 2; // every 2nd poll = 1Hz at 500ms base

    // Turn-signal sticky window: how many fast-path ticks to hold "on" after the
    // last observed flash, to bridge the off-phase of the ~1.5Hz blink cycle.
    // 2 ticks = ~1000ms at 2Hz — spans an off-frame (~333ms) with margin while
    // still clearing a cancelled indicator from the overlay within ~1s.
    private static final int TURN_STICKY_TICKS = 2;

    // BYDAutoSpeedDevice
    private Object speedDevice;
    private Method getCurrentSpeedMethod;
    private Method getAccelerateDeepnessMethod;
    private Method getBrakeDeepnessMethod;

    // BYDAutoGearboxDevice
    private Object gearboxDevice;
    private Method getGearboxAutoModeTypeMethod;
    private Method getBrakePedalStateMethod;

    // DiLink 5.0 / TS CarAdapterManager
    private Object carBodyManager;
    private Method getShiftModeMethod;

    // Turn signal detection via getTurnLightFlashState()
    // Returns: 0=off, 1=left, 2=right, 3=hazard (model-dependent)
    private Object lightDevice;
    private java.lang.reflect.Method getTurnLightFlashStateMethod;

    // Seatbelt via BYDAutoInstrumentDevice.getSafetyBeltStatus(int)
    // Fallback: BYDAutoSafetyBeltDevice.getPassengerStatus(int)
    private Object safetyBeltDevice;
    private java.lang.reflect.Method getPassengerStatusMethod;
    private Object instrumentDeviceForBelt;
    private java.lang.reflect.Method getSafetyBeltStatusMethod;

    // Polling
    private ScheduledExecutorService executor;
    private volatile TelemetrySnapshot latestSnapshot;
    private final Object pollExecutionLock = new Object();
    private long nextPollingGeneration = 0L;
    private volatile long activePollingGeneration = 0L;

    // Lifecycle monitor. The singleton collector is shared across concurrent
    // callers — pano (GpuSurveillancePipeline), OEM dashcam (OemDashcamPipeline),
    // the trip recorder (TripAnalyticsManager) and the daemon all drive
    // startPolling()/setOverlayRecordingActive()/stopPolling() from different
    // threads. The (refcount, executor) pair plus overlayRecordingActive must
    // mutate atomically: without this, two callers can both observe
    // executor==null in startPolling()'s check-then-act and each create a
    // "TelemetryPoller" — the second write to `executor` orphans the first
    // thread (still scheduled, never shut down), so two pollers run forever,
    // doubling the reflective BYD-HAL sweep and contending on the same
    // non-thread-safe device handles. All five lifecycle methods take this
    // monitor. Polling uses a separate execution lock, then briefly takes this
    // monitor only to verify its generation and publish a snapshot atomically
    // with lifecycle transitions.
    private final Object pollingLock = new Object();

    // Recording mode: when true, polls at POLL_INTERVAL_MS (2 Hz) for the
    // video overlay. When false, polls at SLOW_POLL_INTERVAL_MS (1 Hz) for
    // trip telemetry / ABRP only. Read on the executor thread; mutated under
    // pollingLock via setOverlayRecordingActive().
    private volatile boolean overlayRecordingActive = false;

    // ── Overlay-only field demand ────────────────────────────────────────
    // Turn signals and seatbelts are polled ONLY to feed the burn-in overlay:
    //   • Automations read them via their OWN dedicated fast polls
    //     (TurnSignalEvent / SeatbeltEvent), gated on an enabled automation.
    //   • Trips / ABRP / GearMonitor never read snapshot.leftTurnSignal /
    //     rightTurnSignal / seatbeltBuckled (verified: no other consumer).
    // So when no ACTIVE overlay flow selects them, the per-tick reflective HAL
    // reads (1× getTurnLightFlashState, 2× getSafetyBeltStatus) are pure waste.
    // Each overlay consumer ("pano", "oem") publishes its current need here via
    // setOverlayFieldDemand; the poll reads only the union volatiles below.
    // Keyed map keeps set idempotent (no start/stop pairing to get wrong).
    //
    // SAFE DEFAULT (no-regression): when NO overlay consumer has published
    // demand (map empty), both flags stay TRUE — i.e. exactly today's behavior
    // (poll turn+seatbelt on every tick). The optimization only narrows the
    // reads once a live overlay explicitly reports its resolved field set. So
    // an install that never touches this, or any code path that doesn't wire
    // demand, behaves bit-for-bit as before.
    private final java.util.concurrent.ConcurrentHashMap<String, boolean[]>
        overlayFieldDemand = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean demandTurnSignals = true;
    private volatile boolean demandSeatbelts = true;

    // Reference counting: polling stays alive as long as any consumer needs it
    // (pipeline overlay, trip recorder, etc.). Mutated under pollingLock so the
    // count and the executor lifecycle stay coherent.
    private final java.util.concurrent.atomic.AtomicInteger pollingRefCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    // Last-known-good values (used as fallback when a device call fails)
    private int lastSpeedKmh = 0;
    private int lastAccelPercent = 0;
    private int lastBrakePercent = 0;
    private boolean lastSpeedValid = false;
    private long lastSpeedReadElapsedRealtimeMs = -1;
    private boolean lastAccelValid = false;
    private long lastAccelReadElapsedRealtimeMs = -1;
    private boolean lastBrakeValid = false;
    private long lastBrakeReadElapsedRealtimeMs = -1;
    private boolean lastBrakePedalPressed = false;
    private boolean lastBrakePedalPressedValid = false;
    private long lastBrakePedalPressedReadElapsedRealtimeMs = -1;
    private int lastGearMode = 1; // P
    private boolean lastGearValid = false;
    private long lastGearReadElapsedRealtimeMs = -1;
    private boolean lastLeftTurn = false;
    private boolean lastRightTurn = false;
    // NOT buckled-by-default. This array is what the overlay renders when the seatbelt read
    // has never succeeded (the reflective invoke throws on every tick from boot, so
    // seatbeltScratch is never written and the catch falls through to "use defaults"). A true
    // here painted a green ALL-CLEAR on a safety glyph — burned into the recording — from zero
    // real data. false renders the honest "not confirmed buckled" instead, matching the
    // (raw & 0xFFFF) == 1 rule below. A trim that reports properly overwrites this on the
    // first successful poll, so a working vehicle is unaffected.
    private boolean[] lastSeatbelts = new boolean[]{false, false};
    private long pollCount = 0;
    private int leftTurnStickyCount = 0;
    private int rightTurnStickyCount = 0;
    // One-time seatbelt-API probe guard. Deferred to first seatbelt demand
    // (was pollCount==0) so an overlay that never draws belts skips the probe's
    // reflective device sweep entirely. Set on the executor thread only.
    private boolean seatbeltProbed = false;

    // FIX H2: per-tick reusable scratch to avoid allocation churn at 5 Hz.
    // We can NOT mutate lastSeatbelts in place because the published snapshot
    // shares the reference with the consumer. Use a 2-slot scratch and only
    // promote into lastSeatbelts (which becomes the published reference) when
    // the values actually changed — typical case is "no change", so the
    // snapshot reuses the same array forever.
    private final boolean[] seatbeltScratch = new boolean[2];

    // FIX H2: log a WARN when a poll tick exceeds this budget. 50 ms at 5 Hz
    // is 25% of the period — anything above that and we're risking missed
    // ticks plus regressing the overlay's frame freshness.
    private static final long SLOW_TICK_LOG_BUDGET_MS = 50L;
    private long lastSlowTickWarnElapsedRealtimeMs = 0L;

    // 750ms heartbeat at 1Hz idle polling. The poll itself fires every 1000ms
    // (SLOW_POLL_INTERVAL_MS) but ScheduledExecutorService.scheduleAtFixedRate
    // can fire 1-2ms early due to Linux timer slack — at 1000ms the heartbeat
    // gate would then slip to the NEXT tick (~2000ms total gap), exceeding
    // GearMonitor's 1000ms freshness window (GearMonitor.java:133) and the
    // 1000ms threshold of any other consumer. 750ms guarantees the heartbeat
    // always fires before the next poll tick, keeping the snapshot freshness
    // well under 1 second.
    private static final long HEARTBEAT_INTERVAL_MS = 750L;
    private long lastPublishedElapsedRealtimeMs = -1L;

    // Speed-device recovery is intentionally monotonic and rate limited. A
    // failed boot-time bind must not disable speed/pedal telemetry for the
    // rest of a long drive, while a dead HAL must not be rebound every tick.
    private static final long SPEED_RECONNECT_INITIAL_BACKOFF_MS = 10_000L;
    private static final long SPEED_RECONNECT_MAX_BACKOFF_MS = 60_000L;
    private final Object speedReconnectLock = new Object();
    private long nextSpeedReconnectElapsedRealtimeMs = 0L;
    private long speedReconnectBackoffMs =
            SPEED_RECONNECT_INITIAL_BACKOFF_MS;

    // An unchanged non-zero speed is valid during constant-speed cruising.
    // Quarantine it only when several distinct, live, accurate GPS fixes
    // disagree for a sustained period and a device rebind does not recover a
    // plausible speed.
    private static final long GPS_SPEED_MAX_AGE_MS = 5_000L;
    private static final float GPS_SPEED_MAX_ACCURACY_M = 20.0f;
    private static final double GPS_SPEED_MAX_KMH = 300.0;
    private static final double SPEED_CONTRADICTION_DELTA_KMH = 20.0;
    private static final long SPEED_CONTRADICTION_MIN_DURATION_MS = 10_000L;
    private static final int SPEED_CONTRADICTION_MIN_FIXES = 3;
    private long speedContradictionSinceElapsedRealtimeMs = -1L;
    private long lastContradictingGpsFixId = -1L;
    private int distinctContradictingGpsFixes = 0;
    private boolean speedSourceInvalidated = false;
    private int invalidatedFrozenSpeedKmh = -1;

    /**
     * Initialize BYD device handles via reflection using PermissionBypassContext.
     * Each device is initialized independently — if one fails, others still work.
     */
    public void init(Context context) {
        logger.info("Initializing telemetry device access...");

        Context permissiveContext = new PermissionBypassContext(context);
        this.savedContext = permissiveContext;

        // BYDAutoSpeedDevice — getCurrentSpeed(), getAccelerateDeepness(), getBrakeDeepness()
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object newSpeedDevice =
                    getInstance.invoke(null, permissiveContext);
            if (newSpeedDevice == null) {
                throw new IllegalStateException(
                        "BYDAutoSpeedDevice.getInstance returned null");
            }
            Method newGetCurrentSpeedMethod =
                    cls.getMethod("getCurrentSpeed");
            Method newGetAccelerateDeepnessMethod =
                    cls.getMethod("getAccelerateDeepness");
            Method newGetBrakeDeepnessMethod =
                    cls.getMethod("getBrakeDeepness");
            synchronized (speedReconnectLock) {
                speedDevice = newSpeedDevice;
                getCurrentSpeedMethod =
                        newGetCurrentSpeedMethod;
                getAccelerateDeepnessMethod =
                        newGetAccelerateDeepnessMethod;
                getBrakeDeepnessMethod =
                        newGetBrakeDeepnessMethod;
                nextSpeedReconnectElapsedRealtimeMs = 0L;
                speedReconnectBackoffMs =
                        SPEED_RECONNECT_INITIAL_BACKOFF_MS;
            }
            logger.info("BYDAutoSpeedDevice initialized");
        } catch (Exception e) {
            logger.warn("BYDAutoSpeedDevice unavailable: " + e.getMessage());
        }

        // BYDAutoGearboxDevice — getGearboxAutoModeType(), getBrakePedalState()
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            gearboxDevice = getInstance.invoke(null, permissiveContext);
            getGearboxAutoModeTypeMethod = cls.getMethod("getGearboxAutoModeType");
            getBrakePedalStateMethod = cls.getMethod("getBrakePedalState");
            logger.info("BYDAutoGearboxDevice initialized");
        } catch (Exception e) {
            logger.warn("BYDAutoGearboxDevice unavailable: " + e.getMessage());
        }

        // DiLink 5.0 / TS CarAdapterManager — CarBodyManager.getShiftMode()
        try {
            Class<?> camCls = Class.forName("com.ts.lib.caradapter.CarAdapterManager");
            Method getInst = camCls.getMethod("getInstance", Context.class);
            Object cam = getInst.invoke(null, permissiveContext);
            if (cam != null) {
                Method getMgr = camCls.getMethod("getCarAdapterManager", String.class);
                carBodyManager = getMgr.invoke(cam, "body");
                if (carBodyManager != null) {
                    getShiftModeMethod = carBodyManager.getClass().getMethod("getShiftMode");
                    logger.info("CarBodyManager.getShiftMode initialized for DiLink 5.0 gear telemetry");
                }
            }
        } catch (Throwable t) {
            logger.debug("CarBodyManager reflection unavailable: " + t.getMessage());
        }

        // BYDAutoLightDevice — getTurnLightFlashState() (more reliable than getLightStatus)
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.light.BYDAutoLightDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            lightDevice = getInstance.invoke(null, permissiveContext);
            getTurnLightFlashStateMethod = cls.getMethod("getTurnLightFlashState");
            logger.info("BYDAutoLightDevice initialized (using getTurnLightFlashState)");
        } catch (Exception e) {
            logger.warn("BYDAutoLightDevice unavailable: " + e.getMessage());
        }

        // Seatbelt: Try InstrumentDevice.getSafetyBeltStatus(int) first, fallback to SafetyBeltDevice
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            instrumentDeviceForBelt = getInstance.invoke(null, permissiveContext);
            getSafetyBeltStatusMethod = cls.getMethod("getSafetyBeltStatus", int.class);
            logger.info("Using InstrumentDevice for seatbelt status");
        } catch (Exception e) {

            try {
                Class<?> cls2 = Class.forName("android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice");
                Method getInstance2 = cls2.getMethod("getInstance", Context.class);
                safetyBeltDevice = getInstance2.invoke(null, permissiveContext);
                getPassengerStatusMethod = cls2.getMethod("getPassengerStatus", int.class);
                logger.info("BYDAutoSafetyBeltDevice initialized (fallback)");
            } catch (Exception e2) {
                logger.warn("No seatbelt device available: " + e2.getMessage());
            }
        }

        // Initialize with safe defaults
        latestSnapshot = TelemetrySnapshot.createDefault();

        logger.info("Telemetry device initialization complete");
    }

    /**
     * Start polling BYD device APIs on a background thread.
     * Rate depends on overlayRecordingActive:
     *   true  → 200ms (5Hz) for video overlay — only polls speed/accel/brake/gear fast
     *   false → 1000ms (1Hz) for trip telemetry / ABRP
     * Uses reference counting — multiple callers can request polling,
     * and it only stops when ALL callers have called stopPolling().
     */
    public void startPolling() {
        synchronized (pollingLock) {
            int refs = pollingRefCount.incrementAndGet();
            if (executor != null && !executor.isShutdown()) {
                logger.info("Polling already running (refCount=" + refs + ")");
                return;
            }
            startExecutorLocked();
            logger.info("Telemetry polling started at "
                + (1000 / currentIntervalMs()) + " Hz (overlay="
                + overlayRecordingActive + ", refCount=" + refs + ")");
        }
    }

    /**
     * Set overlay recording mode. When active, polling runs at 2Hz.
     * When inactive, drops to 1Hz. Restarts the scheduler if the rate changes.
     */
    public void setOverlayRecordingActive(boolean active) {
        synchronized (pollingLock) {
            if (this.overlayRecordingActive == active) return;
            this.overlayRecordingActive = active;
            logger.info("Overlay recording " + (active ? "ACTIVE (2Hz)" : "INACTIVE (1Hz)"));
            // Restart scheduler at new rate if currently running
            restartAtCurrentRateLocked();
        }
    }

    /**
     * Publish which overlay-only fields a given consumer currently needs, so
     * the poll can skip the reflective turn-signal / seatbelt HAL reads when no
     * active overlay flow draws them. {@code key} identifies the consumer
     * ("pano" / "oem"); pass all-false (or drop demand) when that consumer's
     * overlay is off or its selection excludes these fields. Idempotent and
     * thread-safe; recomputes the union immediately.
     *
     * <p>Deliberately independent of {@link #setOverlayRecordingActive} /
     * refcount: rate and lifecycle are one axis, per-field demand another. The
     * poll still runs for speed/gear/pedals (trips, GearMonitor) regardless;
     * this only gates the two overlay-exclusive reflective reads.
     */
    public void setOverlayFieldDemand(String key, boolean needTurnSignals, boolean needSeatbelts) {
        if (key == null) return;
        // Always STORE (even all-false) so "active overlay that draws neither"
        // is distinct from "no active overlay" (empty map → legacy fallback).
        // Consumers call clearOverlayFieldDemand(key) when their overlay stops.
        overlayFieldDemand.put(key, new boolean[]{ needTurnSignals, needSeatbelts });
        recomputeFieldDemand();
    }

    /**
     * Drop a consumer's overlay field demand entirely (its overlay went
     * inactive). When the last consumer clears, the map is empty and demand
     * falls back to the legacy TRUE/TRUE default. Idempotent.
     */
    public void clearOverlayFieldDemand(String key) {
        if (key == null) return;
        overlayFieldDemand.remove(key);
        recomputeFieldDemand();
    }

    /**
     * Recompute the union of per-consumer overlay field demand into the
     * volatiles. When NO consumer has published demand (map empty), fall back
     * to TRUE/TRUE — the pre-feature behavior — so the reflective reads are
     * only ever narrowed by an explicit, live selection, never by the mere
     * absence of wiring.
     */
    private void recomputeFieldDemand() {
        if (overlayFieldDemand.isEmpty()) {
            demandTurnSignals = true;
            demandSeatbelts = true;
            return;
        }
        boolean turn = false, belt = false;
        for (boolean[] d : overlayFieldDemand.values()) {
            if (d.length > 0 && d[0]) turn = true;
            if (d.length > 1 && d[1]) belt = true;
            if (turn && belt) break;
        }
        demandTurnSignals = turn;
        demandSeatbelts = belt;
    }

    /**
     * Restarts the polling scheduler at the rate matching the current
     * overlayRecordingActive state. No-op if the scheduler is not running.
     *
     * <p>Caller MUST hold {@link #pollingLock}.
     */
    private void restartAtCurrentRateLocked() {
        if (executor == null || executor.isShutdown()) return;
        activePollingGeneration = 0L;
        executor.shutdown();
        executor = null;
        startExecutorLocked();
        logger.info("Telemetry polling restarted at " + (1000 / currentIntervalMs()) + " Hz");
    }

    /**
     * Create the single-threaded scheduled executor and arm the poll task at
     * the rate matching the current {@link #overlayRecordingActive} state.
     *
     * <p>Caller MUST hold {@link #pollingLock} and MUST have already ensured
     * {@code executor} is null (or shut down) — this method overwrites it
     * unconditionally. Centralising executor creation here is what makes the
     * check-then-act in every lifecycle method race-free: only code holding
     * the monitor can ever construct a "TelemetryPoller".
     */
    private void startExecutorLocked() {
        long interval = currentIntervalMs();
        long generation = ++nextPollingGeneration;
        activePollingGeneration = generation;
        ScheduledExecutorService newExecutor =
                Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TelemetryPoller");
            t.setDaemon(true);
            return t;
        });
        executor = newExecutor;
        newExecutor.scheduleAtFixedRate(
                () -> poll(generation),
                0,
                interval,
                TimeUnit.MILLISECONDS);
    }

    private long currentIntervalMs() {
        return overlayRecordingActive ? POLL_INTERVAL_MS : SLOW_POLL_INTERVAL_MS;
    }

    /**
     * Request to stop polling. Only actually stops when all consumers have released.
     * If overlay recording was deactivated but other consumers remain, downgrades to 1Hz.
     */
    public void stopPolling() {
        synchronized (pollingLock) {
            int refs = pollingRefCount.decrementAndGet();
            if (refs < 0) {
                pollingRefCount.set(0);
                refs = 0;
            }
            if (refs > 0) {
                logger.info("Polling stop requested but still needed (refCount=" + refs + ")");
                // If overlay just stopped but trip recorder still needs polling, downgrade rate
                if (!overlayRecordingActive) {
                    restartAtCurrentRateLocked();
                }
                return;
            }
            activePollingGeneration = 0L;
            if (executor != null) {
                executor.shutdown();
                executor = null;
                logger.info("Telemetry polling stopped (refCount=0)");
            }
        }
    }

    /**
     * Force stop polling regardless of reference count.
     * Used during daemon shutdown.
     */
    public void forceStopPolling() {
        synchronized (pollingLock) {
            pollingRefCount.set(0);
            activePollingGeneration = 0L;
            if (executor != null) {
                executor.shutdown();
                executor = null;
                logger.info("Telemetry polling force-stopped");
            }
        }
    }

    /**
     * Returns the latest telemetry snapshot (thread-safe via volatile reference).
     */
    public TelemetrySnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    /**
     * Poll all BYD devices and produce a new TelemetrySnapshot.
     * On failure for any individual field, uses the last-known-good value.
     */
    private void poll(long generation) {
        if (!isCurrentPollingGeneration(generation)) {
            return;
        }
        synchronized (pollExecutionLock) {
            if (!isCurrentPollingGeneration(generation)) {
                return;
            }
            // FIX H2: instrument tick duration. The reflective Method.invoke()
            // calls are the dominant polling cost; rate-limit slow-HAL logs.
            long startNanos = System.nanoTime();
            try {
                pollInner(generation);
            } catch (Throwable t) {
                // A scheduled executor suppresses future runs if a task throws.
                logger.error("Poll error (keeping alive): " + t.getMessage());
            }
            long elapsedMs =
                    (System.nanoTime() - startNanos) / 1_000_000L;
            if (elapsedMs > SLOW_TICK_LOG_BUDGET_MS) {
                long nowElapsedRealtimeMs =
                        SystemClock.elapsedRealtime();
                if (lastSlowTickWarnElapsedRealtimeMs <= 0
                        || nowElapsedRealtimeMs
                        - lastSlowTickWarnElapsedRealtimeMs > 60_000L) {
                    lastSlowTickWarnElapsedRealtimeMs =
                            nowElapsedRealtimeMs;
                    logger.warn("Telemetry poll tick took " + elapsedMs
                            + " ms (budget " + SLOW_TICK_LOG_BUDGET_MS
                            + " ms) — BYD HAL may be slow");
                }
            }
        }
    }

    private boolean isCurrentPollingGeneration(long generation) {
        return generation != 0L
                && activePollingGeneration == generation;
    }

    private void pollInner(long generation) {
        if (!isCurrentPollingGeneration(generation)) {
            return;
        }
        long pollElapsedRealtimeMs = SystemClock.elapsedRealtime();
        int speedKmh = lastSpeedKmh;
        int accelPercent = lastAccelPercent;
        int brakePercent = lastBrakePercent;
        boolean brakePedalPressed = lastBrakePedalPressed;
        int gearMode = lastGearMode;
        boolean leftTurn = lastLeftTurn;
        boolean rightTurn = lastRightTurn;
        boolean[] seatbelts = lastSeatbelts;

        // ── FAST PATH: speed, accel, brake, gear (every poll) ──
        // These are the only fields that change rapidly during driving
        // and are needed for the video overlay at 5Hz.

        // Speed device: getCurrentSpeed(), getAccelerateDeepness(), getBrakeDeepness()
        Object polledSpeedDevice = speedDevice;
        Method polledGetCurrentSpeedMethod =
                getCurrentSpeedMethod;
        Method polledGetAccelerateDeepnessMethod =
                getAccelerateDeepnessMethod;
        Method polledGetBrakeDeepnessMethod =
                getBrakeDeepnessMethod;
        if (polledSpeedDevice != null
                && polledGetCurrentSpeedMethod != null
                && polledGetAccelerateDeepnessMethod != null
                && polledGetBrakeDeepnessMethod != null) {
            boolean deviceFailed = false;
            try {
                double rawSpeed =
                        (double) polledGetCurrentSpeedMethod.invoke(
                                polledSpeedDevice);
                // BYDAutoSpeedDevice.getCurrentSpeed() returns the value in the
                // cluster's configured unit (mph on imperial trims), so normalize
                // to canonical km/h here — matching the contract every downstream
                // consumer assumes (overlay display, trip scoring). On km trims the
                // factor is 1.0, so this is a no-op. Mirrors the conversion
                // BydDataCollector already applies on its own speed path.
                //
                // Guard the SDK sentinel (-2.147482624E9, returned without throwing
                // when the value is unavailable) and any negative/NaN glitch BEFORE
                // the multiply: sentinel × the imperial factor overflows int32 into
                // a bogus +838M km/h that would poison the overlay and the trip's
                // max/avg/histogram. On a bad read we keep the last-known-good speed.
                if (isValidRawSpeed(rawSpeed)) {
                    int convertedSpeed =
                            (int) Math.round(
                                    rawSpeed * speedToKmhFactor());
                    if (convertedSpeed >= 0
                            && convertedSpeed <= 300) {
                        acceptSpeedCandidate(
                                convertedSpeed,
                                pollElapsedRealtimeMs);
                        speedKmh = lastSpeedKmh;
                    } else {
                        deviceFailed = true;
                    }
                } else {
                    deviceFailed = true;
                }
            } catch (Exception e) {
                logger.warn("Failed to read speed: " + e.getMessage());
                deviceFailed = true;
            }
            try {
                int candidate =
                        (int) polledGetAccelerateDeepnessMethod.invoke(
                                polledSpeedDevice);
                if (isValidPedalPercent(candidate)) {
                    accelPercent = candidate;
                    lastAccelPercent = candidate;
                    lastAccelValid = true;
                    lastAccelReadElapsedRealtimeMs =
                            pollElapsedRealtimeMs;
                } else {
                    deviceFailed = true;
                }
            } catch (Exception e) {
                logger.warn("Failed to read accel pedal: " + e.getMessage());
                deviceFailed = true;
            }
            try {
                int candidate =
                        (int) polledGetBrakeDeepnessMethod.invoke(
                                polledSpeedDevice);
                if (isValidPedalPercent(candidate)) {
                    brakePercent = candidate;
                    lastBrakePercent = candidate;
                    lastBrakeValid = true;
                    lastBrakeReadElapsedRealtimeMs =
                            pollElapsedRealtimeMs;
                } else {
                    deviceFailed = true;
                }
            } catch (Exception e) {
                logger.warn("Failed to read brake depth: " + e.getMessage());
                deviceFailed = true;
            }
            // If any read failed, try to re-obtain the device reference
            if (deviceFailed) {
                SpeedReconnectResult reconnect =
                        maybeReconnectSpeedDevice(
                                generation,
                                pollElapsedRealtimeMs,
                                "read failure");
                if (reconnect.anyValidRead) {
                    speedKmh = lastSpeedKmh;
                    accelPercent = lastAccelPercent;
                    brakePercent = lastBrakePercent;
                }
            }
        } else {
            SpeedReconnectResult reconnect =
                    maybeReconnectSpeedDevice(
                            generation,
                            pollElapsedRealtimeMs,
                            "device unavailable");
            if (reconnect.anyValidRead) {
                speedKmh = lastSpeedKmh;
                accelPercent = lastAccelPercent;
                brakePercent = lastBrakePercent;
            }
        }

        GpsSpeedEvidence gpsSpeedEvidence =
                captureTrustworthyGpsSpeed(
                        pollElapsedRealtimeMs,
                        System.currentTimeMillis());
        boolean sustainedSpeedContradiction =
                updateSpeedContradiction(
                        speedKmh,
                        gpsSpeedEvidence,
                        pollElapsedRealtimeMs);
        if (sustainedSpeedContradiction
                || speedSourceInvalidated) {
            SpeedReconnectResult reconnect =
                    maybeReconnectSpeedDevice(
                            generation,
                            pollElapsedRealtimeMs,
                            speedSourceInvalidated
                                    ? "invalidated speed source"
                                    : "GPS/CAN speed contradiction");
            if (reconnect.anyValidRead) {
                speedKmh = lastSpeedKmh;
                accelPercent = lastAccelPercent;
                brakePercent = lastBrakePercent;
            }
            if (sustainedSpeedContradiction
                    && reconnect.attempted) {
                boolean recovered =
                        reconnect.speedReadValid
                        && !isSpeedContradictory(
                                reconnect.speedKmh,
                                gpsSpeedEvidence);
                if (recovered) {
                    resetSpeedContradiction();
                } else {
                    invalidateFrozenSpeed(speedKmh);
                }
            }
        }

        // Gearbox: gear mode (every poll — changes on shift)
        boolean gearAcquired = false;
        if (carBodyManager != null && getShiftModeMethod != null) {
            try {
                Object shiftObj = getShiftModeMethod.invoke(carBodyManager);
                if (shiftObj instanceof Number) {
                    int shift = ((Number) shiftObj).intValue();
                    // Shift values: 0=parked/charging, 1=P, 2=R, 3=N, 4=D, 5=M, 6=S
                    int mapped = -1;
                    switch (shift) {
                        case 0:
                        case 1: mapped = 1; break; // GEAR_P
                        case 2: mapped = 2; break; // GEAR_R
                        case 3: mapped = 3; break; // GEAR_N
                        case 4: mapped = 4; break; // GEAR_D
                        case 5: mapped = 5; break; // GEAR_M
                        case 6: mapped = 6; break; // GEAR_S
                    }
                    if (isValidGearMode(mapped)) {
                        gearMode = mapped;
                        lastGearMode = mapped;
                        lastGearValid = true;
                        lastGearReadElapsedRealtimeMs = pollElapsedRealtimeMs;
                        gearAcquired = true;
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to read shift mode from CarBodyManager: " + e.getMessage());
            }
        }

        if (!gearAcquired && gearboxDevice != null
                && getGearboxAutoModeTypeMethod != null) {
            try {
                int candidate =
                        (int) getGearboxAutoModeTypeMethod.invoke(
                                gearboxDevice);
                if (isValidGearMode(candidate)) {
                    gearMode = candidate;
                    lastGearMode = candidate;
                    lastGearValid = true;
                    lastGearReadElapsedRealtimeMs =
                            pollElapsedRealtimeMs;
                }
            } catch (Exception e) {
                logger.warn("Failed to read gear mode: " + e.getMessage());
            }
        }

        // Turn signals (every poll = 5Hz). Read on the fast path so a cancelled
        // indicator clears from the overlay within ~600ms instead of lingering
        // for up to 10s. Sticky counter bridges the off-phase of the blink cycle.
        //
        // Overlay-only: skipped entirely when no active overlay flow selects the
        // turn-signal field (demandTurnSignals=false). Automations read turn
        // lamps via their own TurnSignalEvent poll; no other consumer reads
        // snapshot.leftTurnSignal/rightTurnSignal. When demand is off we leave
        // leftTurn/rightTurn at their carried-forward last-known values (cheap,
        // no reflection) — they simply aren't drawn.
        if (demandTurnSignals && lightDevice != null && getTurnLightFlashStateMethod != null) {
            try {
                int flashState = (int) getTurnLightFlashStateMethod.invoke(lightDevice);

                boolean leftNow = (flashState == 2 || flashState == 3);
                boolean rightNow = (flashState == 4 || flashState == 5);
                boolean hazardNow = (flashState == 6 || flashState == 7);

                if (hazardNow) { leftNow = true; rightNow = true; }

                if (leftNow) leftTurnStickyCount = TURN_STICKY_TICKS;
                if (rightNow) rightTurnStickyCount = TURN_STICKY_TICKS;

                leftTurn = leftTurnStickyCount > 0;
                rightTurn = rightTurnStickyCount > 0;

                if (leftTurnStickyCount > 0) leftTurnStickyCount--;
                if (rightTurnStickyCount > 0) rightTurnStickyCount--;

                lastLeftTurn = leftTurn;
                lastRightTurn = rightTurn;
            } catch (Exception e) {
                logger.warn("Failed to read turn signal: " + e.getMessage());
            }
        }

        // Seatbelt status (every poll = 5Hz). Drawn on the overlay every frame,
        // so a buckle/unbuckle must reflect within one frame instead of up to 1s.
        //
        // Overlay-only: skipped entirely when no active overlay flow selects a
        // seatbelt field (demandSeatbelts=false). Seatbelt automations use their
        // own SeatbeltEvent poll; no other consumer reads snapshot.seatbeltBuckled.
        // The one-time probeSeatbeltApis() is also deferred until first demand so
        // an overlay that never draws belts pays zero probe cost.
        if (demandSeatbelts && !seatbeltProbed) {
            probeSeatbeltApis(savedContext);
            seatbeltProbed = true;
        }
        if (demandSeatbelts && instrumentDeviceForBelt != null && getSafetyBeltStatusMethod != null) {
            try {
                // FIX H2: reuse a 2-slot scratch instead of allocating a fresh
                // boolean[2] every tick. We only publish a NEW array when the
                // values actually changed — the steady-state path (driver +
                // passenger both buckled, unchanging) never allocates.
                int driverRaw = (int) getSafetyBeltStatusMethod.invoke(instrumentDeviceForBelt, 1);
                int passengerRaw = (int) getSafetyBeltStatusMethod.invoke(instrumentDeviceForBelt, 2);
                // BUCKLED is (raw & 0xFFFF) == 1 — the same rule as
                // BydDataCollector.sanitizeSeatbelt and the OEM firmware's own
                // sanitizeSeatbeltState, so the raw HAL value is never DECODED differently here.
                //
                // That is decode parity, NOT end-to-end parity: the automation/MQTT path layers a
                // passenger-session tracker and an occupancy gate on top. On affected firmware an
                // empty seat idles at the same raw 1 as a real buckle, so automation withholds that
                // value until a closed-door 0 establishes the session; opening the passenger door
                // ends it before the empty-seat rebound. This overlay can therefore show passenger
                // green while a "passenger buckled" automation has not fired. Deliberate: the
                // automation path suppresses an ambiguous edge, whereas the overlay draws the raw
                // sensor. Do not copy the tracker here.
                //
                // Two separate hazards, hence neither a bare "!= 0" nor a bare "== 1":
                //  - "!= 0" read every HAL failure code (-1, the -21474826xx family,
                //    Integer.MIN_VALUE) and every not-available sentinel as BUCKLED — a green
                //    all-clear painted onto a SAFETY glyph burned into the recording, on a trim
                //    that actually reported nothing.
                //  - a mask-less "== 1" would call a genuinely buckled belt UNBUCKLED on any trim
                //    that packs flags/counters into the high 16 bits (exactly what the OEM's mask
                //    defends against), turning a correct green into a permanent red false alarm.
                // The failure codes all mask to something other than 1, so masking first is safe
                // AND keeps them reading unbuckled. This boolean[] cannot express "unknown", so
                // "not definitely buckled" is the honest rendering.
                seatbeltScratch[0] = ((driverRaw & 0xFFFF) == 1);
                seatbeltScratch[1] = ((passengerRaw & 0xFFFF) == 1);
                if (lastSeatbelts == null
                        || lastSeatbelts.length != 2
                        || lastSeatbelts[0] != seatbeltScratch[0]
                        || lastSeatbelts[1] != seatbeltScratch[1]) {
                    // State changed — allocate a fresh array so prior
                    // snapshots holding the old reference don't see a
                    // mid-flight mutation.
                    lastSeatbelts = new boolean[]{seatbeltScratch[0], seatbeltScratch[1]};
                }
                seatbelts = lastSeatbelts;
            } catch (Exception e) {
                // Use defaults
            }
        }

        // ── SLOW PATH: brake pedal pressed state (every 5th poll = 1Hz) ──
        // Not drawn on the overlay (renderer uses brakePercent), so 1Hz is fine.
        boolean doSlowFields = (pollCount % SLOW_FIELD_DIVISOR == 0);

        if (doSlowFields) {
            // Brake pedal pressed state (binary, not the depth %)
            if (gearboxDevice != null) {
                try {
                    int brakeState = (int) getBrakePedalStateMethod.invoke(gearboxDevice);
                    if (brakeState == 0 || brakeState == 1) {
                        brakePedalPressed = brakeState == 1;
                        lastBrakePedalPressed =
                                brakePedalPressed;
                        lastBrakePedalPressedValid = true;
                        lastBrakePedalPressedReadElapsedRealtimeMs =
                                pollElapsedRealtimeMs;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to read brake pedal state: " + e.getMessage());
                }
            }
        }

        // FIX H2: skip the snapshot allocation when none of the consumed
        // fields have changed since the last published one. The overlay
        // renderer keys on the published reference; the timestampMs delta
        // alone is not user-visible and not worth the allocation+clone of
        // the seatbelt array on every 200 ms tick. At 5 Hz with a parked
        // car this collapses ~5 allocations/s into 0.
        TelemetrySnapshot prev = latestSnapshot;
        boolean fieldsChanged = (prev == null)
                || prev.speedKmh != speedKmh
                || prev.accelPedalPercent != accelPercent
                || prev.brakePedalPercent != brakePercent
                || prev.brakePedalPressed != brakePedalPressed
                || prev.gearMode != gearMode
                || prev.gearValid != lastGearValid
                || prev.leftTurnSignal != leftTurn
                || prev.rightTurnSignal != rightTurn
                || prev.speedValid != lastSpeedValid
                || prev.accelPedalValid != lastAccelValid
                || prev.brakePedalValid != lastBrakeValid
                || prev.brakePedalPressedValid
                != lastBrakePedalPressedValid
                || prev.seatbeltBuckled == null
                || prev.seatbeltBuckled.length != (seatbelts == null ? 0 : seatbelts.length)
                || (seatbelts != null
                        && (prev.seatbeltBuckled[0] != seatbelts[0]
                            || prev.seatbeltBuckled[1] != seatbelts[1]));
        // FIX L1: heartbeat — even with no field change, publish at 1 Hz so
        // freshness-checking consumers (GearMonitor, TripTelemetryRecorder)
        // don't see a stale timestampMs.
        long now = System.currentTimeMillis();
        boolean heartbeatDue =
                lastPublishedElapsedRealtimeMs < 0
                || pollElapsedRealtimeMs
                < lastPublishedElapsedRealtimeMs
                || pollElapsedRealtimeMs
                - lastPublishedElapsedRealtimeMs
                >= HEARTBEAT_INTERVAL_MS;
        if (fieldsChanged || heartbeatDue) {
            TelemetrySnapshot nextSnapshot = new TelemetrySnapshot(
                    speedKmh, accelPercent, brakePercent,
                    brakePedalPressed, gearMode,
                    leftTurn, rightTurn,
                    seatbelts, now,
                    pollElapsedRealtimeMs,
                    lastSpeedValid,
                    lastSpeedReadElapsedRealtimeMs,
                    lastAccelValid,
                    lastAccelReadElapsedRealtimeMs,
                    lastBrakeValid,
                    lastBrakeReadElapsedRealtimeMs,
                    lastBrakePedalPressedValid,
                    lastBrakePedalPressedReadElapsedRealtimeMs,
                    lastGearValid,
                    lastGearReadElapsedRealtimeMs
            );
            synchronized (pollingLock) {
                if (!isCurrentPollingGeneration(generation)) {
                    return;
                }
                latestSnapshot = nextSnapshot;
                lastPublishedElapsedRealtimeMs =
                        pollElapsedRealtimeMs;
                pollCount++;
            }
        } else {
            synchronized (pollingLock) {
                if (!isCurrentPollingGeneration(generation)) {
                    return;
                }
                pollCount++;
            }
        }
    }

    /**
     * Factor to convert a raw BYDAutoSpeedDevice.getCurrentSpeed() reading into
     * canonical km/h. On imperial trims this is MILES_TO_KM (~1.609); on metric
     * trims it is 1.0 (no-op). Sourced from {@link BydDataCollector}, the single
     * place that detects the cluster's mileage unit. Defensive: any failure (or
     * an uninitialized collector, where the factor still defaults to 1.0) falls
     * back to 1.0 so a metric trim — the overwhelming majority — is never altered.
     */
    private double speedToKmhFactor() {
        try {
            com.overdrive.app.byd.BydDataCollector collector =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
            if (collector != null) {
                // Use the HARDWARE unit factor, not the app's display override:
                // the raw getCurrentSpeed() reading is in the cluster's unit, which
                // is fixed by getMileageUnit() and can diverge from the km/mi DISPLAY
                // preference. Scaling by the display override inflates speed ~1.6×
                // when the two disagree (e.g. km cluster + user picks mi).
                double f = collector.getSpeedToKmhFactor();
                if (f > 0) return f;
            }
        } catch (Throwable ignored) {
            // BydDataCollector unavailable — treat as metric (factor 1.0)
        }
        return 1.0;
    }

    /**
     * Whether a raw getCurrentSpeed() reading is a usable value rather than the
     * SDK's "not available" sentinel (-2.147482624E9, returned without throwing),
     * a negative glitch, or NaN. The {@code >= 0} floor covers all three (NaN
     * comparisons are false), and crucially prevents the sentinel from being
     * scaled by the imperial factor into an int32 overflow.
     */
    private static boolean isValidRawSpeed(double rawSpeed) {
        return rawSpeed >= 0
                && rawSpeed != com.overdrive.app.byd.BydFeatureIds.SDK_NOT_AVAILABLE;
    }

    private static boolean isValidPedalPercent(int percent) {
        return percent >= 0 && percent <= 100;
    }

    private static boolean isValidGearMode(int gearMode) {
        return gearMode >= 1 && gearMode <= 6;
    }

    private boolean acceptSpeedCandidate(
            int candidateSpeedKmh,
            long readElapsedRealtimeMs) {
        if (speedSourceInvalidated) {
            if (candidateSpeedKmh == invalidatedFrozenSpeedKmh) {
                lastSpeedValid = false;
                return false;
            }
            logger.info("Speed source changed after quarantine; accepting live data");
            speedSourceInvalidated = false;
            invalidatedFrozenSpeedKmh = -1;
            resetSpeedContradiction();
        }
        lastSpeedKmh = candidateSpeedKmh;
        lastSpeedValid = true;
        lastSpeedReadElapsedRealtimeMs =
                readElapsedRealtimeMs;
        return true;
    }

    private SpeedReconnectResult maybeReconnectSpeedDevice(
            long generation,
            long nowElapsedRealtimeMs,
            String reason) {
        synchronized (speedReconnectLock) {
            if (!isCurrentPollingGeneration(generation)
                    || nowElapsedRealtimeMs
                    < nextSpeedReconnectElapsedRealtimeMs) {
                return SpeedReconnectResult.notAttempted();
            }
            SpeedReconnectResult result =
                    reconnectSpeedDevice(
                            generation,
                            nowElapsedRealtimeMs);
            if (!result.attempted) {
                return result;
            }
            if (result.anyValidRead) {
                speedReconnectBackoffMs =
                        SPEED_RECONNECT_INITIAL_BACKOFF_MS;
            } else {
                speedReconnectBackoffMs = Math.min(
                        SPEED_RECONNECT_MAX_BACKOFF_MS,
                        Math.max(
                                SPEED_RECONNECT_INITIAL_BACKOFF_MS,
                                speedReconnectBackoffMs * 2L));
            }
            long attemptCompletedElapsedRealtimeMs =
                    Math.max(
                            nowElapsedRealtimeMs,
                            SystemClock.elapsedRealtime());
            nextSpeedReconnectElapsedRealtimeMs =
                    attemptCompletedElapsedRealtimeMs
                    + speedReconnectBackoffMs;
            if (result.anyValidRead) {
                logger.info("Re-obtained BYDAutoSpeedDevice after "
                        + reason);
            } else {
                logger.warn("BYDAutoSpeedDevice reconnect after "
                        + reason + " failed; retrying in "
                        + (speedReconnectBackoffMs / 1000L) + "s");
            }
            return result;
        }
    }

    /**
     * Rebuild all reflection handles as well as the device object. This is
     * required when init ran before the BYD service or its classes were ready.
     */
    private SpeedReconnectResult reconnectSpeedDevice(
            long generation,
            long readElapsedRealtimeMs) {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.speed.BYDAutoSpeedDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object newDevice = getInstance.invoke(null, savedContext);
            if (newDevice == null) {
                return SpeedReconnectResult.failed();
            }
            Method newGetCurrentSpeedMethod =
                    cls.getMethod("getCurrentSpeed");
            Method newGetAccelerateDeepnessMethod =
                    cls.getMethod("getAccelerateDeepness");
            Method newGetBrakeDeepnessMethod =
                    cls.getMethod("getBrakeDeepness");
            int reconnectSpeedKmh = lastSpeedKmh;
            boolean speedReadValid = false;
            int reconnectAccelPercent = lastAccelPercent;
            boolean accelReadValid = false;
            int reconnectBrakePercent = lastBrakePercent;
            boolean brakeReadValid = false;
            boolean anyValidRead = false;
            try {
                double testSpeed =
                        (double) newGetCurrentSpeedMethod.invoke(
                                newDevice);
                if (isValidRawSpeed(testSpeed)) {
                    int convertedSpeed =
                            (int) Math.round(
                                    testSpeed * speedToKmhFactor());
                    if (convertedSpeed >= 0
                            && convertedSpeed <= 300) {
                        reconnectSpeedKmh = convertedSpeed;
                        speedReadValid = true;
                        anyValidRead = true;
                    }
                }
            } catch (Exception e) {
                logger.warn("Speed reconnect read failed: "
                        + e.getMessage());
            }
            try {
                int testAccel =
                        (int) newGetAccelerateDeepnessMethod.invoke(
                                newDevice);
                if (isValidPedalPercent(testAccel)) {
                    reconnectAccelPercent = testAccel;
                    accelReadValid = true;
                    anyValidRead = true;
                }
            } catch (Exception e) {
                logger.warn("Accel reconnect read failed: "
                        + e.getMessage());
            }
            try {
                int testBrake =
                        (int) newGetBrakeDeepnessMethod.invoke(
                                newDevice);
                if (isValidPedalPercent(testBrake)) {
                    reconnectBrakePercent = testBrake;
                    brakeReadValid = true;
                    anyValidRead = true;
                }
            } catch (Exception e) {
                logger.warn("Brake reconnect read failed: "
                        + e.getMessage());
            }
            if (!isCurrentPollingGeneration(generation)) {
                return SpeedReconnectResult.notAttempted();
            }
            speedDevice = newDevice;
            getCurrentSpeedMethod =
                    newGetCurrentSpeedMethod;
            getAccelerateDeepnessMethod =
                    newGetAccelerateDeepnessMethod;
            getBrakeDeepnessMethod =
                    newGetBrakeDeepnessMethod;
            if (speedReadValid) {
                acceptSpeedCandidate(
                        reconnectSpeedKmh,
                        readElapsedRealtimeMs);
            }
            if (accelReadValid) {
                lastAccelPercent = reconnectAccelPercent;
                lastAccelValid = true;
                lastAccelReadElapsedRealtimeMs =
                        readElapsedRealtimeMs;
            }
            if (brakeReadValid) {
                lastBrakePercent = reconnectBrakePercent;
                lastBrakeValid = true;
                lastBrakeReadElapsedRealtimeMs =
                        readElapsedRealtimeMs;
            }
            return new SpeedReconnectResult(
                    true,
                    anyValidRead,
                    speedReadValid,
                    reconnectSpeedKmh);
        } catch (Exception e) {
            logger.warn("Speed device reconnect failed: " + e.getMessage());
            return SpeedReconnectResult.failed();
        }
    }

    private GpsSpeedEvidence captureTrustworthyGpsSpeed(
            long nowElapsedRealtimeMs,
            long nowEpochMs) {
        try {
            GpsMonitor gps = GpsMonitor.getInstance();
            for (int attempt = 0; attempt < 2; attempt++) {
                long fixElapsedBefore = gps.getFixElapsedMs();
                long updateBefore = gps.getLastUpdate();
                boolean cachedBefore = gps.isLoadedFromCache();
                float speedMps = gps.getSpeed();
                float accuracyM = gps.getAccuracy();
                long fixElapsedAfter = gps.getFixElapsedMs();
                long updateAfter = gps.getLastUpdate();
                boolean cachedAfter = gps.isLoadedFromCache();
                if (fixElapsedBefore != fixElapsedAfter
                        || updateBefore != updateAfter
                        || cachedBefore != cachedAfter) {
                    continue;
                }
                if (cachedAfter
                        || Float.isNaN(speedMps)
                        || Float.isInfinite(speedMps)
                        || speedMps < 0.0f
                        || Float.isNaN(accuracyM)
                        || Float.isInfinite(accuracyM)
                        || accuracyM <= 0.0f
                        || accuracyM > GPS_SPEED_MAX_ACCURACY_M) {
                    return null;
                }
                long fixId;
                long ageMs;
                if (fixElapsedAfter > 0L) {
                    if (fixElapsedAfter > nowElapsedRealtimeMs) {
                        return null;
                    }
                    fixId = fixElapsedAfter;
                    ageMs = nowElapsedRealtimeMs - fixElapsedAfter;
                } else {
                    if (updateAfter <= 0L
                            || updateAfter > nowEpochMs) {
                        return null;
                    }
                    fixId = updateAfter;
                    ageMs = nowEpochMs - updateAfter;
                }
                double speedKmh = speedMps * 3.6;
                if (ageMs > GPS_SPEED_MAX_AGE_MS
                        || speedKmh > GPS_SPEED_MAX_KMH) {
                    return null;
                }
                return new GpsSpeedEvidence(fixId, speedKmh);
            }
        } catch (Throwable ignored) {
            // GPS is optional; unavailable evidence must never invalidate CAN.
        }
        return null;
    }

    private boolean updateSpeedContradiction(
            int canSpeedKmh,
            GpsSpeedEvidence gps,
            long nowElapsedRealtimeMs) {
        if (gps == null
                || canSpeedKmh <= 0
                || !isSpeedContradictory(canSpeedKmh, gps)) {
            resetSpeedContradiction();
            return false;
        }
        if (gps.fixId != lastContradictingGpsFixId) {
            if (lastContradictingGpsFixId < 0L
                    || gps.fixId < lastContradictingGpsFixId) {
                speedContradictionSinceElapsedRealtimeMs =
                        nowElapsedRealtimeMs;
                distinctContradictingGpsFixes = 1;
            } else {
                distinctContradictingGpsFixes++;
            }
            lastContradictingGpsFixId = gps.fixId;
        }
        return speedContradictionSinceElapsedRealtimeMs >= 0L
                && distinctContradictingGpsFixes
                >= SPEED_CONTRADICTION_MIN_FIXES
                && nowElapsedRealtimeMs
                - speedContradictionSinceElapsedRealtimeMs
                >= SPEED_CONTRADICTION_MIN_DURATION_MS;
    }

    private static boolean isSpeedContradictory(
            int canSpeedKmh,
            GpsSpeedEvidence gps) {
        return gps != null
                && Math.abs(canSpeedKmh - gps.speedKmh)
                >= SPEED_CONTRADICTION_DELTA_KMH;
    }

    private void invalidateFrozenSpeed(int frozenSpeedKmh) {
        if (!speedSourceInvalidated) {
            logger.warn("Invalidating frozen CAN speed "
                    + frozenSpeedKmh
                    + "km/h after sustained live GPS disagreement"
                    + " and failed source recovery");
        }
        speedSourceInvalidated = true;
        invalidatedFrozenSpeedKmh = frozenSpeedKmh;
        lastSpeedValid = false;
    }

    private void resetSpeedContradiction() {
        speedContradictionSinceElapsedRealtimeMs = -1L;
        lastContradictingGpsFixId = -1L;
        distinctContradictingGpsFixes = 0;
    }

    private static final class GpsSpeedEvidence {
        final long fixId;
        final double speedKmh;

        GpsSpeedEvidence(long fixId, double speedKmh) {
            this.fixId = fixId;
            this.speedKmh = speedKmh;
        }
    }

    private static final class SpeedReconnectResult {
        final boolean attempted;
        final boolean anyValidRead;
        final boolean speedReadValid;
        final int speedKmh;

        SpeedReconnectResult(
                boolean attempted,
                boolean anyValidRead,
                boolean speedReadValid,
                int speedKmh) {
            this.attempted = attempted;
            this.anyValidRead = anyValidRead;
            this.speedReadValid = speedReadValid;
            this.speedKmh = speedKmh;
        }

        static SpeedReconnectResult notAttempted() {
            return new SpeedReconnectResult(
                    false, false, false, 0);
        }

        static SpeedReconnectResult failed() {
            return new SpeedReconnectResult(
                    true, false, false, 0);
        }
    }

    // Seatbelt alarm detection (discovered at runtime)
    private Object seatbeltAlarmDevice;
    private Method seatbeltAlarmMethod;
    private Context savedContext;
    
    /**
     * Probe multiple BYD devices for any seatbelt-related method.
     * Tries: BodyworkDevice alarm, InstrumentDevice malfunction indicators,
     * SafetyBeltDevice with various seat IDs.
     */
    private void probeSeatbeltApis(Context ctx) {
        logger.info("Probing BYD devices for seatbelt API...");
        
        // 1. Try BYDAutoBodyworkDevice — getAlarmState() or similar
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, ctx);
            
            // Probe all methods that might relate to seatbelt
            String[] methodNames = {
                "getAlarmState", "getSafetyBeltAlarm", "getSeatBeltWarning",
                "getSafetyBeltState", "getBeltAlarmState", "getAutoSystemState"
            };
            for (String name : methodNames) {
                try {
                    Method m = cls.getMethod(name);
                    int val = (int) m.invoke(device);
                    logger.info("Bodywork." + name + "() = " + val);
                    // If we find a working method, save it
                    if (val >= 0 && val < 100) {
                        seatbeltAlarmDevice = device;
                        seatbeltAlarmMethod = m;
                        logger.info("Using Bodywork." + name + "() for seatbelt alarm");
                    }
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist, try next
                } catch (Exception e) {

                }
            }
        } catch (Exception e) {

        }
        
        // 2. Try BYDAutoInstrumentDevice — getMalfunctionState() or seatbelt-specific
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, ctx);
            
            String[] methodNames = {
                "getSafetyBeltStatus", "getSeatBeltAlarm", "getSafetyBeltAlarmState",
                "getMalfunctionState"
            };
            for (String name : methodNames) {
                // Try no-arg version
                try {
                    Method m = cls.getMethod(name);
                    int val = (int) m.invoke(device);
                    logger.info("Instrument." + name + "() = " + val);
                    if (seatbeltAlarmDevice == null && val >= 0 && val < 100) {
                        seatbeltAlarmDevice = device;
                        seatbeltAlarmMethod = m;
                        logger.info("Using Instrument." + name + "() for seatbelt alarm");
                    }
                } catch (NoSuchMethodException e) {
                    // Try int-arg version
                    try {
                        Method m = cls.getMethod(name, int.class);
                        StringBuilder sb = new StringBuilder("Instrument." + name + "(int):");
                        for (int i = 0; i <= 5; i++) {
                            try {
                                int val = (int) m.invoke(device, i);
                                sb.append(" [").append(i).append("]=").append(val);
                            } catch (Exception ex) {
                                sb.append(" [").append(i).append("]=ERR");
                            }
                        }
                        logger.info(sb.toString());
                    } catch (NoSuchMethodException e2) {
                        // Neither version exists
                    }
                } catch (Exception e) {

                }
            }
            
            // Also try MALFUNCTION_ELECTRIC_PARKING_BRAKE constant area
            // getMalfunctionState(int) with various malfunction IDs
            try {
                Method m = cls.getMethod("getMalfunctionState", int.class);
                StringBuilder sb = new StringBuilder("Instrument.getMalfunctionState(int):");
                // Try common malfunction IDs (0-20)
                for (int i = 0; i <= 20; i++) {
                    try {
                        int val = (int) m.invoke(device, i);
                        if (val != 0 && val != -2147482645) {
                            sb.append(" [").append(i).append("]=").append(val);
                        }
                    } catch (Exception ex) { /* skip */ }
                }
                logger.info(sb.toString());
            } catch (Exception e) { /* no getMalfunctionState(int) */ }
            
        } catch (Exception e) {

        }
        
        if (seatbeltAlarmDevice == null) {
            logger.warn("No working seatbelt API found — seatbelt status will show as buckled");
        }
    }

    /**
     * Context wrapper that bypasses BYD permission checks.
     * Required for accessing BYD hardware services without signature permissions from UID 2000.
     */
    private static class PermissionBypassContext extends android.content.ContextWrapper {
        public PermissionBypassContext(Context base) {
            super(base);
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {}

        @Override
        public void enforcePermission(String permission, int pid, int uid, String message) {}

        @Override
        public void enforceCallingPermission(String permission, String message) {}

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public int checkSelfPermission(String permission) {
            return android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
    }
}
