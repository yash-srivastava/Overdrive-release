package com.overdrive.app.monitor;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * Gear Monitor — polling-based gear position monitoring.
 * 
 * Uses polling instead of AbsBYDAutoGearboxListener because the BYD framework's
 * internal learningEPB() method crashes with a UID mismatch when running as shell
 * (UID 2000). The crash kills the BYD device manager's HandlerThread and cascades
 * into daemon restart loops.
 * 
 * Polls getGearboxAutoModeType() every 200ms — fast enough for gear change detection
 * while avoiding the listener crash path entirely.
 */
public class GearMonitor {
    private static final DaemonLogger logger = DaemonLogger.getInstance("GearMonitor");
    
    // Gear constants
    public static final int GEAR_P = 1;
    public static final int GEAR_R = 2;
    public static final int GEAR_N = 3;
    public static final int GEAR_D = 4;
    public static final int GEAR_M = 5;
    public static final int GEAR_S = 6;
    
    private static final long POLL_INTERVAL_MS = 200;  // 5 Hz polling
    private static final long CACHED_GEAR_MAX_AGE_MS = 1000L;
    // A reused telemetry snapshot must stay valid past the next poll, or fresh-sample readers see a gap every second.
    private static final long SNAPSHOT_REUSE_MAX_AGE_MS = CACHED_GEAR_MAX_AGE_MS - 2 * POLL_INTERVAL_MS;
    private static final long DILINK5_BRIDGE_GEAR_MAX_AGE_MS = 5000L;
    private static final long DILINK5_CARSVC_GEAR_MAX_AGE_MS =
            com.overdrive.app.byd.CarSvcTelemetry.DUMP_TTL_MS + 1_000L;
    private static final long DILINK5_GEAR_PROBE_THROTTLE_MS =
            CACHED_GEAR_MAX_AGE_MS;
    
    private static GearMonitor instance;
    
    private Context context;
    // Volatile because the poll thread reads these without holding the
    // singleton's monitor; concurrent stop() (synchronized) nullifies them.
    // Volatile gives the poll iteration a consistent snapshot per loop turn.
    private volatile Object gearboxDevice;
    private volatile Method getGearMethod;
    private Thread pollThread;
    private volatile boolean isRunning = false;
    private volatile GearSample observation = new GearSample(GEAR_P, 0L, CACHED_GEAR_MAX_AGE_MS);
    private volatile long lastDiLink5ProbeTime = 0;
    private volatile int cachedDiLink5ProbeGear = BydVehicleData.UNAVAILABLE;
    private volatile long cachedDiLink5ProbeObservedAt = 0;
    private volatile long cachedDiLink5ProbeMaxAgeMs =
            CACHED_GEAR_MAX_AGE_MS;

    /** Whether the 200ms poll thread is active — i.e. getCurrentGear() is fresh
     *  to within ~POLL_INTERVAL_MS rather than a cold initial value. */
    public boolean isActive() {
        return isRunning
                && (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                    || getCurrentGearIfFresh() != BydVehicleData.UNAVAILABLE);
    }
    
    // TelemetryDataCollector reference — when set, read gear from its cached snapshot
    // instead of polling the BYD device directly (avoids duplicate CAN bus reads)
    private volatile com.overdrive.app.telemetry.TelemetryDataCollector telemetrySource = null;
    
    private GearMonitor() {}
    
    public static synchronized GearMonitor getInstance() {
        if (instance == null) {
            instance = new GearMonitor();
        }
        return instance;
    }
    
    /**
     * Initialize with context.
     */
    public void init(Context context) {
        this.context = context;
        logger.info("GearMonitor initialized");
    }
    
    /**
     * Set the TelemetryDataCollector as the gear data source.
     * When set and its poller is running, GearMonitor reads gear from the cached
     * snapshot instead of polling the BYD device directly — eliminating duplicate
     * CAN bus reads.
     */
    public void setTelemetrySource(com.overdrive.app.telemetry.TelemetryDataCollector source) {
        this.telemetrySource = source;
    }
    
    /**
     * Start monitoring gear changes via polling.
     *
     * <p>Synchronized: the round-3 RecordingModeManager change made
     * {@code resyncFromHardware} call this every 30s when the monitor isn't
     * running. Without this lock, two concurrent callers (resync ticker +
     * cold-start retry) can both pass the {@code !isRunning} guard, both
     * complete the reflection, and both spawn their own {@code GearPoll}
     * thread — leaking a permanent second thread that double-reports every
     * gear change. The duplicate {@code onGearChanged} deliveries then
     * cancel each other in RMM (gear==currentGear short-circuit) but still
     * waste CPU on every 200ms tick.
     */
    public synchronized void start() {
        if (isRunning) {
            logger.warn("Already running");
            return;
        }

        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            startDiLink5();
            return;
        }

        try {
            logger.info("Starting gear monitor...");
            
            // Get gearbox device instance via reflection
            Class<?> gearboxClass = Class.forName("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice");
            Method getInstance = gearboxClass.getMethod("getInstance", Context.class);
            gearboxDevice = getInstance.invoke(null, context);
            
            if (gearboxDevice == null) {
                logger.error("BYDAutoGearboxDevice.getInstance() returned null");
                return;
            }
            
            // Cache the getter method
            getGearMethod = gearboxClass.getMethod("getGearboxAutoModeType");
            
            // Get initial gear state
            int initialGearRead =
                    (int) getGearMethod.invoke(gearboxDevice);
            if (!isValidGearMode(initialGearRead)) {
                logger.error("Invalid initial gear read: "
                        + initialGearRead);
                gearboxDevice = null;
                getGearMethod = null;
                return;
            }
            observation = new GearSample(initialGearRead, SystemClock.elapsedRealtime(), CACHED_GEAR_MAX_AGE_MS);
            logger.info("Initial gear: " + gearToString(initialGearRead));
            
            isRunning = true;
            
            // Build the poller first, but publish the hardware state before it
            // can run. Starting the thread first allowed a fast P -> D shift to
            // update currentGear before this initial callback, permanently
            // collapsing the P edge during async trip-manager startup.
            final int initialGear = initialGearRead;
            pollThread = new Thread(() -> {
                while (isRunning) {
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                        if (!isRunning) break;

                        int gear;
                        long gearObservedAtElapsedRealtimeMs;
                        // Prefer TelemetryDataCollector's cached snapshot to avoid
                        // duplicate CAN bus reads when the overlay poller is running
                        com.overdrive.app.telemetry.TelemetryDataCollector src = telemetrySource;
                        com.overdrive.app.telemetry.TelemetrySnapshot snap =
                            (src != null) ? src.getLatestSnapshot() : null;
                        long gearAgeMs = snap != null
                                && snap.gearReadElapsedRealtimeMs >= 0L
                                ? SystemClock.elapsedRealtime()
                                        - snap.gearReadElapsedRealtimeMs
                                : Long.MAX_VALUE;
                        if (snap != null
                                && snap.gearValid
                                && isValidGearMode(snap.gearMode)
                                && gearAgeMs >= 0L
                                && gearAgeMs
                                        < SNAPSHOT_REUSE_MAX_AGE_MS) {
                            // Only a recent successful gear read is cacheable.
                            gear = snap.gearMode;
                            gearObservedAtElapsedRealtimeMs =
                                    snap.gearReadElapsedRealtimeMs;
                        } else {
                            // Snapshot the reflection refs to locals: stop() is
                            // synchronized and nullifies these mid-iteration. Without
                            // local snapshot, getGearMethod.invoke would NPE and
                            // produce a bogus "Gear poll error: null" log on every
                            // race. Cleanly exit the loop on null instead.
                            Method getter = getGearMethod;
                            Object device = gearboxDevice;
                            if (getter == null || device == null) break;
                            gear = (int) getter.invoke(device);
                            gearObservedAtElapsedRealtimeMs =
                                    SystemClock.elapsedRealtime();
                        }

                        if (!isValidGearMode(gear)) {
                            logger.debug("Ignoring invalid gear read: "
                                    + gear);
                            continue;
                        }
                        GearSample previous = observation;
                        int previousGear = previous.gear;
                        if (previousGear != gear || previous.observedAtElapsedMs != gearObservedAtElapsedRealtimeMs) {
                            observation = new GearSample(gear, gearObservedAtElapsedRealtimeMs, CACHED_GEAR_MAX_AGE_MS);
                        }
                        if (gear != previousGear) {
                            logger.info("Gear changed: " + gearToString(previousGear) + " -> " + gearToString(gear));
                            CameraDaemon.onGearChanged(gear);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        // Don't crash the poll thread — just log and retry
                        logger.debug("Gear poll error: " + e.getMessage());
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                    }
                }
            }, "GearPoll");
            pollThread.setDaemon(true);
            // Notify initial state
            CameraDaemon.onGearChanged(initialGear);
            pollThread.start();
            
            logger.info("Gear monitor started successfully");
            
        } catch (Exception e) {
            logger.error("Failed to start gear monitor: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * DiLink5 keeps the BYD SDK producer in the app process. Consume that
     * timestamped bridge first and use a throttled car-service probe only as
     * a fallback. No unknown state is ever manufactured into Park.
     */
    private void startDiLink5() {
        logger.info("Starting DiLink5 gear monitor...");
        observation = new GearSample(observation.gear, 0L, CACHED_GEAR_MAX_AGE_MS);
        lastDiLink5ProbeTime = 0L;
        cachedDiLink5ProbeGear = BydVehicleData.UNAVAILABLE;
        cachedDiLink5ProbeObservedAt = 0L;
        cachedDiLink5ProbeMaxAgeMs = CACHED_GEAR_MAX_AGE_MS;
        isRunning = true;

        pollThread = new Thread(() -> {
            while (isRunning) {
                try {
                    GearSample sample = readDiLink5GearSample();
                    long now = SystemClock.elapsedRealtime();
                    if (sample != null
                            && isValidGearMode(sample.gear)
                            && isObservationFresh(
                                    true, now, sample.observedAtElapsedMs,
                                    sample.maxAgeMs)) {
                        GearSample previous = observation;
                        boolean firstObservation = previous.observedAtElapsedMs <= 0L;
                        int previousGear = previous.gear;
                        // Keep the source observation time. Polling a cached
                        // value must not make it fresh again.
                        observation = sample;
                        if (firstObservation || sample.gear != previousGear) {
                            logger.info("Gear changed: "
                                    + (firstObservation
                                        ? "UNKNOWN"
                                        : gearToString(previousGear))
                                    + " -> " + gearToString(sample.gear));
                            CameraDaemon.onGearChanged(sample.gear);
                        }
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    logger.debug("DiLink5 gear poll error: " + t.getMessage());
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException interrupted) {
                        break;
                    }
                }
            }
            logger.info("DiLink5 gear poll thread stopped");
        }, "GearPoll");
        pollThread.setDaemon(true);
        pollThread.start();
        logger.info("DiLink5 gear monitor started");
    }

    private GearSample readDiLink5GearSample() {
        long now = SystemClock.elapsedRealtime();

        try {
            com.overdrive.app.byd.BydDataCollector.DiLink5GearObservation
                    bridged = com.overdrive.app.byd.BydDataCollector
                            .getInstance().readDiLink5GearObservation();
            if (bridged != null
                    && isValidGearMode(bridged.gear)
                    && isObservationFresh(
                            true, now, bridged.observedAtElapsedMs,
                            DILINK5_BRIDGE_GEAR_MAX_AGE_MS)) {
                return new GearSample(
                        bridged.gear, bridged.observedAtElapsedMs,
                        DILINK5_BRIDGE_GEAR_MAX_AGE_MS);
            }
        } catch (Throwable ignored) {
        }

        return readDiLink5Probe(now);
    }

    private GearSample readDiLink5Probe(long now) {
        if (lastDiLink5ProbeTime > 0L
                && now - lastDiLink5ProbeTime
                        < DILINK5_GEAR_PROBE_THROTTLE_MS) {
            return cachedDiLink5ProbeSample();
        }
        lastDiLink5ProbeTime = now;

        int observedGear = BydVehicleData.UNAVAILABLE;
        try {
            com.overdrive.app.byd.CarPropertyBridge bridge =
                    com.overdrive.app.byd.CarPropertyBridge.getInstance();
            if (bridge != null) {
                com.overdrive.app.byd.CarPropertyBridge.ReadResult result =
                        bridge.readProperty("SHIFT_MODE");
                if (result != null && result.success
                        && result.statusCode
                                == com.byd.datasource.feature.Status.STATUS_SUCCESS
                        && result.intValue != null) {
                    observedGear = decodeShiftMode(result.intValue);
                }
            }
        } catch (Throwable ignored) {
        }

        if (!isValidGearMode(observedGear)) {
            try {
                com.overdrive.app.byd.CarSvcTelemetry.DynamicObservation
                        observation =
                        com.overdrive.app.byd.CarSvcTelemetry.INSTANCE
                                .currentDynamics();
                if (observation != null) {
                    observedGear = observation.gear;
                    long observedAt =
                            com.overdrive.app.byd.BydDataCollector
                                    .observedAtFromAge(
                                            now, observation.ageMs);
                    if (isValidGearMode(observedGear)
                            && observedAt > 0L) {
                        cachedDiLink5ProbeGear = observedGear;
                        cachedDiLink5ProbeObservedAt = observedAt;
                        cachedDiLink5ProbeMaxAgeMs =
                                DILINK5_CARSVC_GEAR_MAX_AGE_MS;
                        return cachedDiLink5ProbeSample();
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (isValidGearMode(observedGear)) {
            cachedDiLink5ProbeGear = observedGear;
            cachedDiLink5ProbeObservedAt = now;
            cachedDiLink5ProbeMaxAgeMs = CACHED_GEAR_MAX_AGE_MS;
        }
        return cachedDiLink5ProbeSample();
    }

    private GearSample cachedDiLink5ProbeSample() {
        return isValidGearMode(cachedDiLink5ProbeGear)
                && cachedDiLink5ProbeObservedAt > 0L
                ? new GearSample(
                        cachedDiLink5ProbeGear,
                        cachedDiLink5ProbeObservedAt,
                        cachedDiLink5ProbeMaxAgeMs)
                : null;
    }

    static int decodeShiftMode(int shift) {
        switch (shift) {
            case 0:
            case 1: return GEAR_P;
            case 2: return GEAR_R;
            case 3: return GEAR_N;
            case 4: return GEAR_D;
            case 5: return GEAR_M;
            case 6: return GEAR_S;
            default: return BydVehicleData.UNAVAILABLE;
        }
    }

    public static final class GearSample {
        public final int gear;
        public final long observedAtElapsedMs;
        public final long maxAgeMs;

        GearSample(int gear, long observedAtElapsedMs, long maxAgeMs) {
            this.gear = gear;
            this.observedAtElapsedMs = observedAtElapsedMs;
            this.maxAgeMs = maxAgeMs;
        }
    }
    
    /**
     * Stop monitoring.
     */
    public synchronized void stop() {
        if (!isRunning) {
            return;
        }

        isRunning = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        gearboxDevice = null;
        getGearMethod = null;
        logger.info("Gear monitor stopped");
    }
    
    /**
     * Get current gear.
     */
    public int getCurrentGear() {
        return com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                ? getCurrentGearIfFresh() : observation.gear;
    }

    /** Return only a gear that came from the currently active, fresh poller. */
    public int getCurrentGearIfFresh() {
        GearSample sample = getFreshSample();
        return sample == null ? BydVehicleData.UNAVAILABLE : sample.gear;
    }

    /** Gear, source time and source validity from the same poll. */
    public GearSample getFreshSample() {
        GearSample sample = observation;
        return isObservationFresh(isRunning, SystemClock.elapsedRealtime(), sample.observedAtElapsedMs, sample.maxAgeMs)
                ? sample : null;
    }

    static boolean isObservationFresh(
            boolean running, long nowElapsedMs, long observedAtElapsedMs) {
        return isObservationFresh(
                running, nowElapsedMs, observedAtElapsedMs,
                CACHED_GEAR_MAX_AGE_MS);
    }

    static boolean isObservationFresh(
            boolean running, long nowElapsedMs, long observedAtElapsedMs,
            long maxAgeMs) {
        long age = nowElapsedMs - observedAtElapsedMs;
        return running && maxAgeMs > 0L && observedAtElapsedMs > 0L
                && age >= 0L && age < maxAgeMs;
    }
    
    /**
     * The gear read from the car now where it can be (legacy: the gearbox device itself, not the telemetry copy that
     * can be a second old), else the current fresh reading; null when unknown. For a one-off check, not polling.
     */
    public GearReading readNow() {
        if (!isRunning) return null;
        if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            Method getter = getGearMethod;
            Object device = gearboxDevice;
            if (getter != null && device != null) {
                try {
                    int gear = (int) getter.invoke(device);
                    if (isValidGearMode(gear)) return new GearReading(gear, SystemClock.elapsedRealtime());
                } catch (Throwable ignored) {
                }
            }
        }
        GearSample sample = getFreshSample();
        return sample == null ? null : new GearReading(sample.gear, sample.observedAtElapsedMs);
    }

    /** A gear and when it was taken at its source (elapsedRealtime). */
    public static final class GearReading {
        public final int gear;
        public final long observedAtMs;

        GearReading(int gear, long observedAtMs) {
            this.gear = gear;
            this.observedAtMs = observedAtMs;
        }
    }

    /**
     * Get last update time.
     */
    public long getLastUpdateTime() {
        return observation.observedAtElapsedMs;
    }
    
    /**
     * Check if running.
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Convert gear to string.
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

    public static boolean isValidGearMode(int gear) {
        return gear >= GEAR_P && gear <= GEAR_S;
    }
}
