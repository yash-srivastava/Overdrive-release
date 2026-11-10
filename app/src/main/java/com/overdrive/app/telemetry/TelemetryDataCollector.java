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

    // Backstop for a stalled reader: a healthy DI5 observation ages up to 2 s dump TTL + 1 s poll +
    // 2 x a dump that still succeeds (4 s process timeout) before the next one lands.
    private static final long DILINK5_DYNAMICS_MAX_AGE_MS = 12_000L;
    // A dump lands with its sample at most 4 s timeout + 1 s poll old; older means the reader stalled.
    private static final long DILINK5_FROZEN_SAMPLE_MAX_AGE_MS = 6_000L;
    private static final long DILINK5_DIRECT_MAX_AGE_MS = 3_000L;
    // DI5: the car's speed value held since it last changed, checked against GPS over a recent window
    // of the hold: its live fixes (with Doppler km so far), when one last came, since when P has read.
    private static final float DILINK5_HOLD_FIX_MAX_ACCURACY_M = 50f;
    private int diLink5HoldKmh = -1;
    private long diLink5HoldSeenAtMs = -1L;
    private long diLink5HoldParkedSinceMs = -1L;
    // DI5, across holds: when GPS last showed the car moving at the value, when a live fix last came, the
    // last fix with Doppler, and the fix before this one.
    private long diLink5HoldMovingSeenAtMs = -1L;
    private long diLink5LiveFixAtMs = -1L;
    private long diLink5FixesResumedAtMs = -1L;
    private long diLink5DopplerFixId = 0L;
    private double diLink5DopplerKmh = 0;
    private long diLink5PrevFixId = 0L;
    private double diLink5PrevFixLat = 0, diLink5PrevFixLon = 0;
    private float diLink5PrevFixAccuracyM = 0f;
    private long diLink5HoldLastFixId = 0L;
    private final java.util.ArrayDeque<HoldFix> diLink5HoldFixes = new java.util.ArrayDeque<>();
    // The hold's last 60 s of Doppler from accurate fixes, each with its trailing 20 s median.
    private final java.util.ArrayDeque<HoldDoppler> diLink5HoldDoppler = new java.util.ArrayDeque<>();
    // The hold's current run of standing fixes (Doppler under 3, within their accuracy of its first), and
    // whether one outlasted what the value could stand.
    private long diLink5HoldStillFromId = 0L;
    private double diLink5HoldStillLat = 0, diLink5HoldStillLon = 0;
    private float diLink5HoldStillAccuracyM = 0f;
    private boolean diLink5HoldStoodThrough = false;
    private long diLink5HoldContradictedSinceFixId = 0L;
    private int diLink5HoldContradictingFixes = 0;
    private boolean diLink5HoldContradicted = false;
    // One reader thread per process, started on the first DI5 read, however many collectors exist.
    private static final java.util.concurrent.ExecutorService DILINK5_READER =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "DI5Dynamics");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.atomic.AtomicBoolean diLink5ReadPending =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile com.overdrive.app.byd.BydDataCollector.DiLink5Dynamics diLink5Dynamics;

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
                // setOverlayRecordingActive() already restarts the executor when
                // the requested rate changes. A consumer releasing a reference
                // does not change that rate; restarting here only discards the
                // current schedule and spins up an unnecessary poller thread.
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
        final boolean diLink5 =
                com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
        if (diLink5) {
            applyDiLink5Dynamics();
            speedKmh = lastSpeedValid ? lastSpeedKmh : 0;
            accelPercent = lastAccelValid ? lastAccelPercent : 0;
            brakePercent = lastBrakeValid ? lastBrakePercent : 0;
        } else if (polledSpeedDevice != null
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
        // DI5 speed is a car_service snapshot that can stop updating, a valid 0 included.
        boolean sustainedSpeedContradiction = diLink5
                ? diLink5HeldSpeedContradicted(speedKmh, pollElapsedRealtimeMs)
                : updateSpeedContradiction(
                        speedKmh,
                        gpsSpeedEvidence,
                        pollElapsedRealtimeMs);
        if (diLink5) {
            // DI5 has no SDK device to rebind: a speed that live GPS keeps contradicting is quarantined
            // while the source still delivers it; a sample that stopped being refreshed is only unknown.
            if (sustainedSpeedContradiction) {
                if (pollElapsedRealtimeMs - lastSpeedReadElapsedRealtimeMs
                        <= DILINK5_FROZEN_SAMPLE_MAX_AGE_MS) {
                    invalidateFrozenSpeed(speedKmh);
                } else {
                    lastSpeedValid = false;
                }
                speedKmh = 0;
                accelPercent = 0;
                brakePercent = 0;
                lastAccelValid = false;
                lastBrakeValid = false;
            }
        } else if (sustainedSpeedContradiction
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
        if (gearboxDevice != null
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
                // That is decode parity, NOT source parity: automation prefers the dedicated
                // belt device. Only when that is unavailable does it use this Instrument value,
                // with extra evidence required before trusting an ambiguous passenger-side 1.
                // This overlay draws the raw Instrument value because boolean[] cannot express
                // unknown.
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
     * canonical km/h. The speed factor is independent of the distance factor:
     * some unit=2 firmware exposes imperial distance registers while this speed
     * getter already returns km/h. Sourced from {@link BydDataCollector}, the single
     * place that interprets the cluster's mileage-unit code. Defensive: any failure (or
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

    /**
     * DI5 has no SDK speed device in the daemon. Applies the newest DI5 observation, stamped with
     * its source time, and queues the next read off this thread.
     */
    private void applyDiLink5Dynamics() {
        requestDiLink5Dynamics();
        long now = SystemClock.elapsedRealtime();
        com.overdrive.app.byd.BydDataCollector.DiLink5Dynamics d = diLink5Dynamics;
        // The newest read decides: a failed read (null) or a field it lacks is unknown, not held.
        boolean fresh = d != null && isFreshDiLink5(now, d.observedAtElapsedMs);
        long at = fresh ? d.observedAtElapsedMs : 0L;
        if (fresh && d.speedKmh >= 0 && d.speedKmh <= 300) {
            if (at >= lastSpeedReadElapsedRealtimeMs) {
                acceptSpeedCandidate((int) Math.round(d.speedKmh), at);
            }
        } else {
            lastSpeedValid = false;
        }
        if (fresh && isValidPedalPercent(d.accelPercent)) {
            if (at >= lastAccelReadElapsedRealtimeMs) {
                lastAccelPercent = d.accelPercent;
                lastAccelValid = true;
                lastAccelReadElapsedRealtimeMs = at;
            }
        } else {
            lastAccelValid = false;
        }
        if (fresh && isValidPedalPercent(d.brakePercent)) {
            if (at >= lastBrakeReadElapsedRealtimeMs) {
                lastBrakePercent = d.brakePercent;
                lastBrakeValid = true;
                lastBrakeReadElapsedRealtimeMs = at;
            }
        } else {
            lastBrakeValid = false;
        }
        // A speed quarantined as frozen discredits the pedals from the same feed.
        if (speedSourceInvalidated) {
            lastAccelValid = false;
            lastBrakeValid = false;
        }
    }

    /** One read in flight, off the poll thread, and only while the daemon treats the car as on. */
    private void requestDiLink5Dynamics() {
        boolean carOn = com.overdrive.app.monitor.GearMonitor.getInstance().isRunning()
                || com.overdrive.app.monitor.AccMonitor.isAccOn();
        if (!carOn || !diLink5ReadPending.compareAndSet(false, true)) {
            return;
        }
        try {
            DILINK5_READER.execute(() -> {
                com.overdrive.app.byd.BydDataCollector.DiLink5Dynamics read = null;
                try {
                    read = com.overdrive.app.byd.BydDataCollector.getInstance().readDiLink5DynamicsNow();
                } catch (Throwable t) {
                    logger.debug("DI5 dynamics read failed: " + t.getMessage());
                } finally {
                    // A failed read keeps the last observation while young (3 s on DIRECT, 6 s on the dump):
                    // one glitch is not an outage. A read that lacks a field still makes it unknown.
                    com.overdrive.app.byd.BydDataCollector.DiLink5Dynamics prev = diLink5Dynamics;
                    long bridgeMs = com.overdrive.app.byd.DiLink5DirectTelemetry.ownsDynamics()
                            ? DILINK5_DIRECT_MAX_AGE_MS : DILINK5_FROZEN_SAMPLE_MAX_AGE_MS;
                    diLink5Dynamics = read != null ? read
                            : prev != null && SystemClock.elapsedRealtime() - prev.observedAtElapsedMs <= bridgeMs
                                    ? prev : null;
                    diLink5ReadPending.set(false);
                }
            });
        } catch (Throwable t) {
            diLink5ReadPending.set(false);
        }
    }

    private static boolean isFreshDiLink5(
            long nowElapsedRealtimeMs,
            long observedAtElapsedRealtimeMs) {
        long age = nowElapsedRealtimeMs - observedAtElapsedRealtimeMs;
        // The binder is read every poll, so only the car_service dump cycle needs the long backstop.
        long maxAge = com.overdrive.app.byd.DiLink5DirectTelemetry.ownsDynamics()
                ? DILINK5_DIRECT_MAX_AGE_MS : DILINK5_DYNAMICS_MAX_AGE_MS;
        return observedAtElapsedRealtimeMs > 0L
                && age >= 0L
                && age <= maxAge;
    }

    /**
     * DI5: whether the car's speed value, held since it last changed, is frozen. Over a recent window
     * of the hold (live fixes at most 5 s apart) GPS must show about the value: Doppler averaged over
     * the fixes that carry it (the sidecar sends 0.0 for a fix without speed; a 0.0 the car could have
     * braked to is a stop), and the displacement against the fixes' accuracy. It contradicts when the
     * car is slower by max(a sixth, 10% + 5 km/h) of the value (a dashboard speed may over-read that
     * much; half at most) or faster by 5% plus the accuracy margin; positions alone decide where
     * Doppler covers under half the window, more strictly. The window is as long as that needs at the
     * fixes' accuracy. From 20 km/h it also contradicts when the hold's Doppler varies (20 s medians
     * spread by max(10 km/h, 8%) over 30 s+), which a live value would have followed, and from 8 km/h
     * when held through a standstill it could not have stood. The value is frozen once the contradiction
     * has lasted 10 s, longer than the dump's lag. In P a non-zero value held 10 s (20 s while live
     * fixes come) is frozen unless GPS showed the car moving at it (or the value before) in the last
     * 20 s (30 s without fixes: a tunnel): the gear can misread P. Polls without a valid speed pause the
     * hold, and restart its P clock.
     */
    private boolean diLink5HeldSpeedContradicted(int speedKmh, long nowElapsedMs) {
        GpsMonitor.GpsFixSnapshot fix = GpsMonitor.getInstance().getFixSnapshot();
        if (isLiveDiLink5Fix(fix, nowElapsedMs)) {
            // Fixes back after a gap (a tunnel; sparse fixes are not one): motion could not be watched in
            // it, so quiet time starts again here.
            if (diLink5LiveFixAtMs >= 0L && nowElapsedMs - diLink5LiveFixAtMs > SPEED_CONTRADICTION_MIN_DURATION_MS) {
                diLink5FixesResumedAtMs = nowElapsedMs;
            }
            diLink5LiveFixAtMs = nowElapsedMs;
        }
        if (!lastSpeedValid) {
            return false;
        }
        // A pause in the hold (no valid speed) restarts its P clock and standing run: neither was watched.
        if (nowElapsedMs - diLink5HoldSeenAtMs > 2_000L) {
            diLink5HoldParkedSinceMs = -1L;
            diLink5HoldStillFromId = 0L;
        }
        if (speedKmh != diLink5HoldKmh
                || nowElapsedMs - diLink5HoldSeenAtMs > SPEED_CONTRADICTION_MIN_DURATION_MS) {
            diLink5HoldKmh = speedKmh;
            diLink5HoldParkedSinceMs = -1L;
            diLink5HoldFixes.clear();
            diLink5HoldDoppler.clear();
            diLink5HoldStillFromId = 0L;
            diLink5HoldStoodThrough = false;
            diLink5HoldContradicted = false;
        }
        diLink5HoldSeenAtMs = nowElapsedMs;
        if (com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear()
                != com.overdrive.app.monitor.GearMonitor.GEAR_P) {
            diLink5HoldParkedSinceMs = -1L;
        } else if (diLink5HoldParkedSinceMs < 0L) {
            diLink5HoldParkedSinceMs = nowElapsedMs;
        }
        boolean fixesCome = diLink5LiveFixAtMs >= 0L && nowElapsedMs - diLink5LiveFixAtMs <= 5_000L;
        // While fixes come, as long as the value needs to clear twice their jitter: up to 60 s for a creep
        // (6 km/h or less), 30 s above, so a value frozen in P is not kept long.
        long showMs = speedKmh > 0 ? Math.round(2 * 2 * Math.max(1f, (float) Math.ceil(fix.accuracy))
                / 1000.0 / speedKmh * 3_600_000.0) + 5_000L : 0L;
        long parkedWaitMs = fixesCome ? Math.max(2 * SPEED_CONTRADICTION_MIN_DURATION_MS,
                Math.min(speedKmh <= 6 ? 60_000L : 30_000L, showMs)) : SPEED_CONTRADICTION_MIN_DURATION_MS;
        long motionQuietMs = fixesCome ? parkedWaitMs : 3 * SPEED_CONTRADICTION_MIN_DURATION_MS;
        long quietSinceMs = Math.max(diLink5HoldMovingSeenAtMs, diLink5FixesResumedAtMs);
        if (speedKmh > 0 && diLink5HoldParkedSinceMs >= 0L
                && nowElapsedMs - diLink5HoldParkedSinceMs >= parkedWaitMs
                && nowElapsedMs - quietSinceMs >= motionQuietMs) {
            return true;
        }
        long fixId = fix.fixElapsedMs > 0L ? fix.fixElapsedMs : fix.lastUpdate;
        if (fixId == diLink5HoldLastFixId || !isLiveDiLink5Fix(fix, nowElapsedMs)) {
            return diLink5HoldContradicted;
        }
        float accuracyM = Math.max(1f, (float) Math.ceil(fix.accuracy));
        double rawKmh = fix.speed >= 0f && !Float.isInfinite(fix.speed) ? fix.speed * 3.6 : 0.0;
        boolean carriesDoppler = carriesDiLink5Doppler(fixId, fix, accuracyM, rawKmh);
        HoldFix last = diLink5HoldFixes.peekLast();
        if (last == null || fixId < last.id || fixId - last.id > 5_000L) {
            diLink5HoldFixes.clear();
            diLink5HoldDoppler.clear();
            diLink5HoldFixes.addLast(new HoldFix(fixId, fix.latitude, fix.longitude, accuracyM, 0, 0L));
            addHoldDoppler(fixId, accuracyM, rawKmh);
            diLink5HoldStillFromId = 0L;
            trackHoldStill(fixId, fix, accuracyM, rawKmh, speedKmh);
            diLink5HoldContradictedSinceFixId = 0L;
            diLink5HoldContradicted = false;
        } else {
            // Doppler under 3 km/h is a standstill's noise, which a long stop would otherwise add up.
            long stepMs = fixId - last.id;
            HoldFix now = new HoldFix(fixId, fix.latitude, fix.longitude, accuracyM,
                    last.dopplerKm + (carriesDoppler && rawKmh >= 3.0 ? rawKmh * (stepMs / 3_600_000.0) : 0),
                    last.dopplerMs + (carriesDoppler ? stepMs : 0L));
            diLink5HoldFixes.addLast(now);
            addHoldDoppler(fixId, accuracyM, rawKmh);
            trackHoldStill(fixId, fix, accuracyM, rawKmh, speedKmh);
            double boundEstKm = 2 * accuracyM / 1000.0;
            double marginEstKm = Math.max(2 * boundEstKm, 0.025);
            double shortKmh = Math.min(speedKmh / 2.0, Math.max(speedKmh / 6.0, speedKmh / 10.0 + 5.0));
            double windowH = speedKmh > 0
                    ? Math.max(Math.max(Math.max(0.080, 4 * boundEstKm) / speedKmh, 2 * boundEstKm / shortKmh),
                            marginEstKm / Math.max(speedKmh / 20.0, 3.0))
                    : marginEstKm / 5.0;
            long windowMs = speedKmh > 0
                    ? Math.max(15_000L, Math.min(300_000L, Math.round(windowH * 3_600_000.0)))
                    : Math.max(30_000L, Math.min(150_000L, Math.round(windowH * 3_600_000.0)));
            while (diLink5HoldFixes.size() > 2) {
                java.util.Iterator<HoldFix> it = diLink5HoldFixes.iterator();
                it.next();
                if (it.next().id > fixId - windowMs) break;
                diLink5HoldFixes.pollFirst();
            }
            HoldFix base = diLink5HoldFixes.peekFirst();
            double hours = (fixId - base.id) / 3_600_000.0;
            double expectedKm = speedKmh * hours;
            double movedKm = haversineKm(base.lat, base.lon, fix.latitude, fix.longitude);
            double boundKm = (base.accuracyM + accuracyM) / 1000.0;
            double dopplerHours = (now.dopplerMs - base.dopplerMs) / 3_600_000.0;
            boolean hasDoppler = dopplerHours >= hours / 2.0;
            double dopplerKmh = hasDoppler ? (now.dopplerKm - base.dopplerKm) / dopplerHours : 0.0;
            double reachKm = (speedKmh - shortKmh) * hours;
            // Doppler under 3 km/h reads 0, so it can only judge speeds above that.
            boolean slower = speedKmh > 0 && (hasDoppler && speedKmh - shortKmh >= 3.0
                    ? dopplerKmh < speedKmh - shortKmh && movedKm < reachKm + boundKm
                    : movedKm + boundKm < reachKm);
            boolean faster = hasDoppler
                    ? dopplerKmh >= 1.05 * speedKmh + 3.0
                            && movedKm >= 1.05 * expectedKm + Math.max(2 * boundKm, 0.025)
                    : movedKm >= 1.05 * expectedKm + Math.max(4 * boundKm, 0.050);
            boolean varies = speedKmh >= 20 && heldDopplerVaries(speedKmh);
            if (speedKmh > 0 && movedRecentlyAtDiLink5Speed(speedKmh, now, boundEstKm)) {
                diLink5HoldMovingSeenAtMs = nowElapsedMs;
            }
            if (!slower && !faster && !varies && !diLink5HoldStoodThrough) {
                diLink5HoldContradictedSinceFixId = 0L;
            } else if (diLink5HoldContradictedSinceFixId == 0L) {
                diLink5HoldContradictedSinceFixId = fixId;
                diLink5HoldContradictingFixes = 1;
            } else {
                diLink5HoldContradictingFixes++;
            }
            diLink5HoldContradicted = diLink5HoldContradictedSinceFixId > 0L
                    && diLink5HoldContradictingFixes >= SPEED_CONTRADICTION_MIN_FIXES
                    && fixId - diLink5HoldContradictedSinceFixId >= SPEED_CONTRADICTION_MIN_DURATION_MS;
        }
        diLink5HoldLastFixId = fixId;
        return diLink5HoldContradicted;
    }

    /**
     * DI5: whether a live fix's Doppler counts. A 0.0 counts as a stop within 8 s of the last fix with
     * Doppler, if the car could have braked to it (at most 25 km/h per second) and the step from the
     * fix before is one of standing or the last of braking (5 m of jitter, or 12.5 km/h); else it is a
     * fix without speed, which a long stop's positions judge instead. Spans holds.
     */
    private boolean carriesDiLink5Doppler(long fixId, GpsMonitor.GpsFixSnapshot fix, float accuracyM, double rawKmh) {
        boolean carries = rawKmh > 0.0;
        if (!carries && diLink5DopplerFixId > 0L && fixId > diLink5DopplerFixId
                && fixId - diLink5DopplerFixId <= 8_000L
                && diLink5DopplerKmh <= 25.0 * ((fixId - diLink5DopplerFixId) / 1000.0)
                && diLink5PrevFixId > 0L && fixId > diLink5PrevFixId) {
            double stepKm = haversineKm(diLink5PrevFixLat, diLink5PrevFixLon, fix.latitude, fix.longitude);
            double allowKm = Math.max(Math.min((diLink5PrevFixAccuracyM + accuracyM) / 1000.0, 0.005),
                    12.5 * ((fixId - diLink5PrevFixId) / 3_600_000.0));
            carries = stepKm <= allowKm;
        }
        if (rawKmh > 0.0) {
            diLink5DopplerFixId = fixId;
            diLink5DopplerKmh = rawKmh;
        }
        diLink5PrevFixId = fixId;
        diLink5PrevFixLat = fix.latitude;
        diLink5PrevFixLon = fix.longitude;
        diLink5PrevFixAccuracyM = accuracyM;
        return carries;
    }

    /**
     * DI5: whether the car was seen moving at the value lately (so a P is a misread), over the last 10-60
     * s (long enough for the value to clear 3x the fixes' accuracy) and at least 5 s: Doppler at half the
     * value (3 km/h at least) with the positions not standing, or the positions clear of twice the
     * accuracy.
     */
    private boolean movedRecentlyAtDiLink5Speed(int speedKmh, HoldFix now, double boundEstKm) {
        long spanMs = Math.max(10_000L, Math.min(60_000L,
                Math.round(3 * boundEstKm / speedKmh * 3_600_000.0)));
        HoldFix from = null;
        for (HoldFix f : diLink5HoldFixes) {
            if (f.id >= now.id - spanMs) {
                from = f;
                break;
            }
        }
        if (from == null || now.id - from.id < 5_000L) return false;
        double hours = (now.id - from.id) / 3_600_000.0;
        double expectedKm = speedKmh * hours;
        double movedKm = haversineKm(from.lat, from.lon, now.lat, now.lon);
        double boundKm = (from.accuracyM + now.accuracyM) / 1000.0;
        double dopplerHours = (now.dopplerMs - from.dopplerMs) / 3_600_000.0;
        if (dopplerHours >= hours / 2.0
                && (now.dopplerKm - from.dopplerKm) / dopplerHours >= Math.max(3.0, speedKmh / 2.0)
                && movedKm >= expectedKm / 4.0) {
            return true;
        }
        // Doppler reads 0 under 3 km/h: the positions can still show a creep.
        return movedKm >= Math.max(2 * boundKm, expectedKm / 2.0);
    }

    /**
     * DI5: tracks the hold's run of standing fixes. A live value follows a stop within its lag (12 s at
     * most, failed reads bridged), so a value from 8 km/h held through a standstill longer than 15 s, and
     * than even its fully over-read speed needs to clear twice the jitter, is frozen (a jam's short stops).
     */
    private void trackHoldStill(long fixId, GpsMonitor.GpsFixSnapshot fix, float accuracyM, double rawKmh,
            int speedKmh) {
        if (rawKmh >= 3.0) {
            diLink5HoldStillFromId = 0L;
            return;
        }
        if (diLink5HoldStillFromId == 0L
                || haversineKm(diLink5HoldStillLat, diLink5HoldStillLon, fix.latitude, fix.longitude)
                        > (diLink5HoldStillAccuracyM + accuracyM) / 1000.0) {
            diLink5HoldStillFromId = fixId;
            diLink5HoldStillLat = fix.latitude;
            diLink5HoldStillLon = fix.longitude;
            diLink5HoldStillAccuracyM = accuracyM;
            return;
        }
        double realMinKmh = (speedKmh - 4.0) / 1.1;
        if (speedKmh < 8 || realMinKmh <= 0) return;
        long needMs = Math.max(15_000L, Math.round(2 * (diLink5HoldStillAccuracyM + accuracyM) / 1000.0
                / realMinKmh * 3_600_000.0));
        if (fixId - diLink5HoldStillFromId >= needMs) diLink5HoldStoodThrough = true;
    }

    /**
     * DI5: keeps the hold's last 60 s of Doppler (fixes within 20 m, 3 km/h+), each with the median of a
     * full trailing 20 s (16+ samples over 18 s+), which a stray reading or seconds of noise cannot move.
     */
    private void addHoldDoppler(long fixId, float accuracyM, double rawKmh) {
        while (!diLink5HoldDoppler.isEmpty() && diLink5HoldDoppler.peekFirst().id < fixId - 60_000L) {
            diLink5HoldDoppler.pollFirst();
        }
        if (accuracyM > GPS_SPEED_MAX_ACCURACY_M || rawKmh < 3.0) return;
        java.util.ArrayList<Double> recent = new java.util.ArrayList<>();
        recent.add(rawKmh);
        long oldestId = fixId;
        for (java.util.Iterator<HoldDoppler> it = diLink5HoldDoppler.descendingIterator(); it.hasNext(); ) {
            HoldDoppler d = it.next();
            if (d.id <= fixId - 20_000L) break;
            recent.add(d.kmh);
            oldestId = d.id;
        }
        double medianKmh = Double.NaN;
        int n = recent.size();
        if (n >= 16 && fixId - oldestId >= 18_000L) {
            java.util.Collections.sort(recent);
            medianKmh = n % 2 == 1 ? recent.get(n / 2) : (recent.get(n / 2 - 1) + recent.get(n / 2)) / 2.0;
        }
        diLink5HoldDoppler.addLast(new HoldDoppler(fixId, rawKmh, medianKmh));
    }

    /** DI5: whether the hold's 20 s Doppler medians spread by max(10 km/h, 8%) over 30 s or more of it. */
    private boolean heldDopplerVaries(int speedKmh) {
        if (diLink5HoldDoppler.size() < 20
                || diLink5HoldDoppler.peekLast().id - diLink5HoldDoppler.peekFirst().id < 30_000L) {
            return false;
        }
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (HoldDoppler d : diLink5HoldDoppler) {
            if (Double.isNaN(d.medianKmh)) continue;
            lo = Math.min(lo, d.medianKmh);
            hi = Math.max(hi, d.medianKmh);
        }
        return hi - lo >= Math.max(10.0, 0.08 * speedKmh);
    }

    private static final class HoldDoppler {
        final long id;
        final double kmh, medianKmh;

        HoldDoppler(long id, double kmh, double medianKmh) {
            this.id = id;
            this.kmh = kmh;
            this.medianKmh = medianKmh;
        }
    }

    private static final class HoldFix {
        final long id;
        final double lat, lon;
        final float accuracyM;
        // Since the hold's window began: Doppler km, and the time covered by fixes that carry Doppler.
        final double dopplerKm;
        final long dopplerMs;

        HoldFix(long id, double lat, double lon, float accuracyM, double dopplerKm, long dopplerMs) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.accuracyM = accuracyM;
            this.dopplerKm = dopplerKm;
            this.dopplerMs = dopplerMs;
        }
    }

    /** DI5: a fix from the live sidecar, at most 5 s old, within the hold check's accuracy gate. */
    private static boolean isLiveDiLink5Fix(GpsMonitor.GpsFixSnapshot fix, long nowElapsedMs) {
        if (fix == null || fix.loadedFromCache
                || !(fix.accuracy > 0f) || fix.accuracy > DILINK5_HOLD_FIX_MAX_ACCURACY_M) {
            return false;
        }
        long ageMs = fix.fixElapsedMs > 0L
                ? nowElapsedMs - fix.fixElapsedMs
                : fix.lastUpdate > 0L ? System.currentTimeMillis() - fix.lastUpdate : -1L;
        return ageMs >= 0L && ageMs <= GPS_SPEED_MAX_AGE_MS;
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
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
            logger.warn(com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                    ? "Invalidating frozen DI5 speed " + frozenSpeedKmh
                            + "km/h after sustained live GPS disagreement"
                    : "Invalidating frozen CAN speed "
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
