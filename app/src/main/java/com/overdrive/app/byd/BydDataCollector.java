package com.overdrive.app.byd;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Universal BYD Data Collector — singleton that initializes ALL BYD device types,
 * reads initial values, registers listeners for live updates, and exposes a
 * thread-safe BydVehicleData snapshot.
 * 
 * Every device init and every method call is individually try/caught — one device
 * failing never affects others. Never crashes.
 */
public class BydDataCollector {

    private static final String TAG = "BydDataCollector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static BydDataCollector instance;
    private static final Object lock = new Object();

    private final AtomicReference<BydVehicleData> snapshot = new AtomicReference<>();
    private Context context;
    private volatile boolean initialized = false;

    // Device references (all nullable)
    private Object bodyworkDevice;
    private Object speedDevice;
    private Object engineDevice;
    private Object statisticDevice;
    private Object energyDevice;
    private Object tyreDevice;
    private Object chargingDevice;
    private Object doorLockDevice;
    private Object instrumentDevice;
    private Object otaDevice;
    private Object sensorDevice;
    private Object gearboxDevice;
    private Object safetyBeltDevice;
    private Object acDevice;
    private Object lightDevice;
    private Object adasDevice;
    private Object radarDevice;
    private Object powerDevice;
    private Object settingDevice;
    private Object multimediaDevice;

    // Unit conversion: BYD APIs return values in the user's configured unit.
    // If the user set miles on the instrument cluster, mileage/speed/range come back in miles/mph.
    // We detect this once at init and convert everything to km at the ingestion boundary.
    private static final double MILES_TO_KM = 1.60934;
    private double distanceToKmFactor = 1.0;  // 1.0 = already km, 1.60934 = miles→km
    private boolean unitDetected = false;

    private final List<String> availableDevices = new ArrayList<>();
    private final List<String> unavailableDevices = new ArrayList<>();

    // ==================== EVENT LISTENERS ====================
    // Subscribers receive door/lock events from the typed BYD HAL listeners.
    // Use these instead of polling the snapshot when you need immediate
    // notification of state transitions (e.g. surveillance arming gates).

    /** Raw SDK door-open/close events from the bodywork HAL. */
    public interface DoorStateListener {
        /** @param area BYD area constant. @param state 0=closed,1=open per SDK. */
        void onDoorStateChanged(int area, int state);
    }

    /** Raw SDK lock events from the doorlock HAL. */
    public interface DoorLockListener {
        /** @param area BYD area constant. @param sdkState SDK semantics: INVALID=0,UNLOCK=1,LOCK=2. */
        void onDoorLockStatusChanged(int area, int sdkState);
    }

    /** Snapshot-level lock summary listener — called on every snapshot update
     *  whose lock data may have changed. Use this when you want a single
     *  cohesive view of all areas rather than per-area events. */
    public interface LockSnapshotListener {
        void onLockSnapshotUpdated(BydVehicleData snapshot);
    }

    /** Raw BMS charging-state edges from the charging HAL. Fires only on
     *  transitions (current != previous), not on every poll. State values
     *  match {@code ChargingStateData.CHARGING_BATTERY_STATE_*}. */
    public interface ChargingStateListener {
        void onChargingStateChanged(int previousState, int newState);
    }

    private final java.util.concurrent.CopyOnWriteArrayList<DoorStateListener> doorStateListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<DoorLockListener> doorLockListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<LockSnapshotListener> lockSnapshotListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<ChargingStateListener> chargingStateListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addDoorStateListener(DoorStateListener l) { if (l != null) doorStateListeners.addIfAbsent(l); }
    public void removeDoorStateListener(DoorStateListener l) { doorStateListeners.remove(l); }
    public void addDoorLockListener(DoorLockListener l) { if (l != null) doorLockListeners.addIfAbsent(l); }
    public void removeDoorLockListener(DoorLockListener l) { doorLockListeners.remove(l); }
    public void addLockSnapshotListener(LockSnapshotListener l) { if (l != null) lockSnapshotListeners.addIfAbsent(l); }
    public void removeLockSnapshotListener(LockSnapshotListener l) { lockSnapshotListeners.remove(l); }
    public void addChargingStateListener(ChargingStateListener l) { if (l != null) chargingStateListeners.addIfAbsent(l); }
    public void removeChargingStateListener(ChargingStateListener l) { chargingStateListeners.remove(l); }

    private void notifyDoorStateListeners(int area, int state) {
        for (DoorStateListener l : doorStateListeners) {
            try { l.onDoorStateChanged(area, state); }
            catch (Exception e) { logger.debug("DoorStateListener error: " + e.getMessage()); }
        }
    }

    private void notifyDoorLockListeners(int area, int sdkState) {
        for (DoorLockListener l : doorLockListeners) {
            try { l.onDoorLockStatusChanged(area, sdkState); }
            catch (Exception e) { logger.debug("DoorLockListener error: " + e.getMessage()); }
        }
    }

    private void notifyLockSnapshotListeners(BydVehicleData snap) {
        for (LockSnapshotListener l : lockSnapshotListeners) {
            try { l.onLockSnapshotUpdated(snap); }
            catch (Exception e) { logger.debug("LockSnapshotListener error: " + e.getMessage()); }
        }
    }

    private void notifyChargingStateListeners(int previousState, int newState) {
        for (ChargingStateListener l : chargingStateListeners) {
            try { l.onChargingStateChanged(previousState, newState); }
            catch (Exception e) { logger.debug("ChargingStateListener error: " + e.getMessage()); }
        }
    }

    private BydDataCollector() {}

    public static BydDataCollector getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) instance = new BydDataCollector();
            }
        }
        return instance;
    }

    /** Get the latest vehicle data snapshot. Thread-safe. */
    public BydVehicleData getData() {
        return snapshot.get();
    }

    /** Check if the collector has been initialized. */
    public boolean isInitialized() {
        return initialized;
    }

    // ==================== INITIALIZATION ====================

    /**
     * Initialize all BYD devices. Each device is independent — failures are logged and skipped.
     */
    public synchronized void init(Context context) {
        if (initialized && this.context == context) {
            return;
        }
        this.context = context;
        logger.info("=== BYD Data Collector Initializing ===");
        long start = System.currentTimeMillis();

        // Re-init: tear down state that would otherwise accumulate.
        availableDevices.clear();
        unavailableDevices.clear();
        if (pollScheduler != null) {
            pollScheduler.shutdownNow();
            pollScheduler = null;
        }

        // Initialize each device type
        bodyworkDevice = initDevice("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice", "Bodywork");
        speedDevice = initDevice("android.hardware.bydauto.speed.BYDAutoSpeedDevice", "Speed");
        engineDevice = initDevice("android.hardware.bydauto.engine.BYDAutoEngineDevice", "Engine");
        statisticDevice = initDevice("android.hardware.bydauto.statistic.BYDAutoStatisticDevice", "Statistic");
        chargingDevice = initDevice("android.hardware.bydauto.charging.BYDAutoChargingDevice", "Charging");
        instrumentDevice = initDevice("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", "Instrument");
        otaDevice = initDevice("android.hardware.bydauto.ota.BYDAutoOtaDevice", "OTA");
        gearboxDevice = initDevice("android.hardware.bydauto.gearbox.BYDAutoGearboxDevice", "Gearbox");
        acDevice = initDevice("android.hardware.bydauto.ac.BYDAutoAcDevice", "AC");
        lightDevice = initDevice("android.hardware.bydauto.light.BYDAutoLightDevice", "Light");
        adasDevice = initDevice("android.hardware.bydauto.adas.BYDAutoADASDevice", "ADAS");
        powerDevice = initDevice("android.hardware.bydauto.power.BYDAutoPowerDevice", "Power");
        safetyBeltDevice = initDevice("android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice", "SafetyBelt");
        tyreDevice = initDevice("android.hardware.bydauto.tyre.BYDAutoTyreDevice", "Tyre");
        doorLockDevice = initDevice("android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice", "DoorLock");
        sensorDevice = initDevice("android.hardware.bydauto.sensor.BYDAutoSensorDevice", "Sensor");
        energyDevice = initDevice("android.hardware.bydauto.energy.BYDAutoEnergyDevice", "Energy");
        radarDevice = initDevice("android.hardware.bydauto.radar.BYDAutoRadarDevice", "Radar");
        settingDevice = initDevice("android.hardware.bydauto.setting.BYDAutoSettingDevice", "Setting");
        multimediaDevice = initMultimediaDevice();

        logger.info("Devices available: " + availableDevices.size() + "/" + 
            (availableDevices.size() + unavailableDevices.size()));
        if (!unavailableDevices.isEmpty()) {
            logger.info("Unavailable: " + String.join(", ", unavailableDevices));
        }

        // Detect mileage unit from instrument cluster
        detectMileageUnit();

        // If auto-detection failed, fall back to user's persisted preference
        if (!unitDetected) {
            try {
                com.overdrive.app.trips.TripConfig tripConfig = new com.overdrive.app.trips.TripConfig();
                tripConfig.load();
                String savedUnit = tripConfig.getDistanceUnit();
                if ("mi".equals(savedUnit)) {
                    distanceToKmFactor = MILES_TO_KM;
                    unitDetected = true;
                    logger.info("Mileage unit: MILES (from user config override, factor=" + MILES_TO_KM + ")");
                }
            } catch (Exception e) {
                logger.info("Could not load distance unit from TripConfig: " + e.getMessage());
            }
        }

        // Read initial values (full collection including display-only devices)
        collectAllFull();

        // Dump all battery/energy related getter methods on key devices
        // to discover the correct remaining kWh API at runtime
        // Discovery methods removed — getBatteryRemainPowerEV() confirmed as correct BEV API.
        // BYD light/setting APIs have no write access from UID 2000.

        // Register listeners
        registerAllListeners();

        // Bridge BYD door-state events to push notifications. Safe to start
        // here — the door listener is only invoked once the bodywork HAL
        // fires onDoorStateChanged, which requires registerAllListeners to
        // have run first.
        com.overdrive.app.notifications.DoorEventNotifier.start();
        com.overdrive.app.notifications.ChargingEventNotifier.start();

        // Start periodic polling to keep data fresh (listeners may not fire for all values)
        startPolling();

        long elapsed = System.currentTimeMillis() - start;
        logger.info("=== BYD Data Collector Ready (" + elapsed + "ms) ===");
        initialized = true;
    }

    /**
     * Detect whether the BYD instrument cluster is configured for miles or km.
     * getMileageUnit() returns 1 for km, 0 for miles.
     * If detection fails, defaults to km (factor = 1.0).
     */
    private void detectMileageUnit() {
        if (instrumentDevice == null) {
            logger.info("Mileage unit: defaulting to km (no instrument device)");
            return;
        }
        try {
            Object unitVal = BydDeviceHelper.callGetter(instrumentDevice, "getMileageUnit");
            if (unitVal instanceof Number) {
                int unit = ((Number) unitVal).intValue();
                if (unit == 0) {
                    // Miles mode
                    distanceToKmFactor = MILES_TO_KM;
                    unitDetected = true;
                    logger.info("Mileage unit: MILES detected (factor=" + MILES_TO_KM + ")");
                } else {
                    // km mode (unit == 1 or any other value)
                    distanceToKmFactor = 1.0;
                    unitDetected = true;
                    logger.info("Mileage unit: KM detected (factor=1.0)");
                }
            } else {
                logger.info("Mileage unit: defaulting to km (getMileageUnit returned null)");
            }
        } catch (Exception e) {
            logger.info("Mileage unit: defaulting to km (detection failed: " + e.getMessage() + ")");
        }
    }

    /**
     * Get the distance-to-km conversion factor.
     * Returns 1.0 if km, 1.60934 if miles.
     * Used by OdometerReader and other components that read BYD distance values directly.
     */
    public double getDistanceToKmFactor() {
        return distanceToKmFactor;
    }

    /**
     * Override the distance unit from user settings. Called when the user
     * explicitly selects km or miles in the Trip Settings UI. This fixes the
     * case where auto-detection via getMileageUnit() fails (instrumentDevice
     * null, SDK returns null, etc.) and the raw miles values pass through
     * unconverted.
     *
     * @param unit "mi" for miles (factor=1.60934), "km" for km (factor=1.0)
     */
    public void setDistanceUnitOverride(String unit) {
        if ("mi".equals(unit)) {
            distanceToKmFactor = MILES_TO_KM;
            unitDetected = true;
            logger.info("Distance unit OVERRIDE: MILES (factor=" + MILES_TO_KM + ")");
        } else {
            distanceToKmFactor = 1.0;
            unitDetected = true;
            logger.info("Distance unit OVERRIDE: KM (factor=1.0)");
        }
    }

    /**
     * Returns true if the vehicle's instrument cluster is configured for miles.
     * Used by the /status API to tell the web UI which display unit to use.
     */
    public boolean isMilesMode() {
        return distanceToKmFactor > 1.0;
    }

    private java.util.concurrent.ScheduledExecutorService pollScheduler;
    private static final long POLL_INTERVAL_MS = 5000; // 5 seconds when ACC on
    private static final long POLL_INTERVAL_PARKED_MS = 30000; // 30 seconds when ACC off
    private String lastSummaryHash = "";

    private void startPolling() {
        pollScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BydDataPoll");
            t.setDaemon(true);
            return t;
        });
        pollScheduler.scheduleAtFixedRate(() -> {
            try {
                collectAll();
                // Log when data actually changes
                BydVehicleData d = snapshot.get();
                if (d != null) {
                    String hash = String.format("%.1f|%.2f|%.1f/%.1f/%.1f|%.3f/%.3f",
                        d.socPercent, d.voltage12v, d.highCellTempC, d.lowCellTempC, d.avgCellTempC,
                        d.highCellVoltage, d.lowCellVoltage);
                    if (!hash.equals(lastSummaryHash)) {
                        logger.info("Data changed: SOC=" + d.socPercent + "% 12V=" + d.voltage12v + "V" +
                            " Temp=" + d.highCellTempC + "/" + d.lowCellTempC + "/" + d.avgCellTempC + "°C" +
                            " CellV=" + d.highCellVoltage + "/" + d.lowCellVoltage + "V");
                        lastSummaryHash = hash;
                    }
                }
            } catch (Throwable t) {
                logger.debug("Poll error: " + t.getMessage());
            }
        }, POLL_INTERVAL_MS, POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (pollScheduler != null) {
            pollScheduler.shutdownNow();
            pollScheduler = null;
        }
        initialized = false;
    }

    private Object initDevice(String className, String shortName) {
        Object device = BydDeviceHelper.getDevice(className, context);
        if (device != null) {
            availableDevices.add(shortName);
        } else {
            unavailableDevices.add(shortName);
        }
        return device;
    }

    /**
     * Initialize the multimedia device with multiple context strategies.
     * BYDAutoMultimediaDevice does NOT extend AbsBYDAutoDevice — it's a separate class
     * that connects to a binder service and may require a specific package identity.
     */
    private Object initMultimediaDevice() {
        String className = "android.hardware.bydauto.multimedia.BYDAutoMultimediaDevice";

        // Strategy 1: Use our normal context (works for all other devices)
        Object device = BydDeviceHelper.getDevice(className, context);
        if (device != null) {
            availableDevices.add("Multimedia");
            return device;
        }

        // Strategy 2: Try with a proper app context for com.overdrive.app
        // The daemon runs via app_process with a synthetic context. But the actual app
        // is installed — createPackageContext gives us a real app context with proper
        // service bindings that the multimedia device might need.
        try {
            android.content.Context appPkgCtx = context.createPackageContext(
                "com.overdrive.app",
                android.content.Context.CONTEXT_INCLUDE_CODE | android.content.Context.CONTEXT_IGNORE_SECURITY);
            if (appPkgCtx != null) {
                device = BydDeviceHelper.getDevice(className, appPkgCtx);
                if (device != null) {
                    logger.info("Multimedia device OK via com.overdrive.app package context");
                    availableDevices.add("Multimedia");
                    return device;
                }
            }
        } catch (Exception e) {
            logger.debug("Multimedia strategy 2 (overdrive package context) failed: " + e.getMessage());
        }

        // Strategy 3: Try with system context directly (with timeout — can deadlock)
        try {
            final Object[] result = new Object[1];
            Thread t = new Thread(() -> {
                try {
                    Class<?> atClass = Class.forName("android.app.ActivityThread");
                    java.lang.reflect.Method currentAt = atClass.getMethod("currentActivityThread");
                    Object at = currentAt.invoke(null);
                    if (at != null) {
                        java.lang.reflect.Method getSystemContext = atClass.getMethod("getSystemContext");
                        android.content.Context sysCtx = (android.content.Context) getSystemContext.invoke(at);
                        if (sysCtx != null) {
                            result[0] = BydDeviceHelper.getDevice(className, sysCtx);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Multimedia strategy 3 inner: " + e.getMessage());
                }
            }, "MultimediaInit-SysCtx");
            t.setDaemon(true);
            t.start();
            t.join(3000); // 3s timeout — abort if it hangs
            if (t.isAlive()) {
                logger.warn("Multimedia strategy 3 timed out (3s) — skipping to avoid freeze");
                t.interrupt();
            } else if (result[0] != null) {
                device = result[0];
                logger.info("Multimedia device OK via system context");
                availableDevices.add("Multimedia");
                return device;
            }
        } catch (Exception e) {
            logger.debug("Multimedia strategy 3 (system context) failed: " + e.getMessage());
        }

        // Strategy 4: Try with getApplicationContext() directly
        try {
            android.content.Context appCtx = context.getApplicationContext();
            if (appCtx != null && appCtx != context) {
                device = BydDeviceHelper.getDevice(className, appCtx);
                if (device != null) {
                    logger.info("Multimedia device OK via getApplicationContext()");
                    availableDevices.add("Multimedia");
                    return device;
                }
            }
        } catch (Exception e) {
            logger.debug("Multimedia strategy 4 (app context) failed: " + e.getMessage());
        }

        unavailableDevices.add("Multimedia");
        return null;
    }

    // ==================== DATA COLLECTION ====================

    // Core data polled every 5s. Display-only data updated via listeners only (no polling).
    // Core = fields consumed by ABRP, MQTT, trip analytics, SOC history.
    // Display = fields only shown on the web dashboard — updated by BYD HAL listener callbacks
    //           or on-demand via collectAllFull() when the HTTP API is queried.

    // Hard throttle: never poll devices more frequently than this, even if listeners fire.
    // Listener callbacks update individual values directly in the snapshot without polling.
    // This guard prevents any code path from triggering a full device sweep within the interval.
    private volatile long lastCoreCollectTime = 0;
    private static final long MIN_COLLECT_INTERVAL_MS = 5000; // 5 seconds

    // ACC state: when off, skip polling speed/engine/gearbox (always 0 when parked)
    private volatile boolean accIsOn = true;

    /** Called by CameraDaemon when ACC state changes. Adjusts poll rate accordingly. */
    public void setAccState(boolean isOn) {
        this.accIsOn = isOn;
        // Restart poll scheduler at the appropriate rate
        if (pollScheduler != null && !pollScheduler.isShutdown()) {
            pollScheduler.shutdownNow();
            long interval = isOn ? POLL_INTERVAL_MS : POLL_INTERVAL_PARKED_MS;
            pollScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BydDataPoll");
                t.setDaemon(true);
                return t;
            });
            pollScheduler.scheduleAtFixedRate(() -> {
                try {
                    collectAll();
                    BydVehicleData d = snapshot.get();
                    if (d != null) {
                        String hash = String.format("%.1f|%.2f|%.1f/%.1f/%.1f|%.3f/%.3f",
                            d.socPercent, d.voltage12v, d.highCellTempC, d.lowCellTempC, d.avgCellTempC,
                            d.highCellVoltage, d.lowCellVoltage);
                        if (!hash.equals(lastSummaryHash)) {
                            logger.info("Data changed: SOC=" + d.socPercent + "% 12V=" + d.voltage12v + "V" +
                                " Temp=" + d.highCellTempC + "/" + d.lowCellTempC + "/" + d.avgCellTempC + "°C" +
                                " CellV=" + d.highCellVoltage + "/" + d.lowCellVoltage + "V");
                            lastSummaryHash = hash;
                        }
                    }
                } catch (Throwable t) {
                    logger.debug("Poll error: " + t.getMessage());
                }
            }, 0, interval, java.util.concurrent.TimeUnit.MILLISECONDS);
            logger.info("BydDataPoll rate changed to " + (interval / 1000) + "s (ACC " + (isOn ? "ON" : "OFF") + ")");
        }
    }

    /**
     * Collect core telemetry data from devices into the snapshot.
     * Safe to call from any thread.
     * 
     * Hard-throttled: will not poll devices if called within 5 seconds of the last poll.
     * 
     * Only polls CORE devices (used by ABRP, MQTT, trips, SOC history).
     * When ACC is off, skips speed/engine/gearbox (always 0 when parked).
     * Display-only devices are NOT polled — updated via listeners or on-demand.
     */
    public void collectAll() {
        long now = System.currentTimeMillis();

        // Hard throttle: skip if called within MIN_COLLECT_INTERVAL_MS of last poll.
        if (now - lastCoreCollectTime < MIN_COLLECT_INTERVAL_MS) {
            return;
        }
        lastCoreCollectTime = now;

        BydVehicleData.Builder b = (snapshot.get() != null) ? snapshot.get().toBuilder() : new BydVehicleData.Builder();
        b.availableDevices(availableDevices.toArray(new String[0]));
        b.unavailableDevices(unavailableDevices.toArray(new String[0]));

        // ALWAYS needed: battery, SOC, charging, temperature, 12V
        collectBodywork(b);     // SOC, 12V, remainKwh, powerLevel
        collectStatistic(b);    // SOC, mileage, range, cellTemps, cellVoltages
        collectCharging(b);     // chargingState, gunState, chargingPower
        collectInstrument(b);   // outsideTemp, externalChargingPower
        collectOta(b);          // 12V voltage (precise)
        collectTyre(b);         // pressure (kPa), pressure/leak/signal state per wheel

        // DRIVING ONLY: skip most when ACC is off (values are always 0/stale when parked).
        // EXCEPTION: enginePower remains meaningful when the car is plugged in and
        // charging — current flowing into the pack reads negative on the engine
        // bus and is the most authoritative charging signal we have on PHEVs
        // (where chargingGunState is often UNAVAILABLE and chargingState is
        // stuck at 15=IDLE due to firmware bugs). Detect "probably charging"
        // from the listener-delivered chargingPower / externalChargingPower
        // values populated from typed callbacks even while ACC is off.
        if (accIsOn) {
            collectSpeed(b);        // speed, accel, brake
            collectEngine(b);       // enginePower, motorSpeed/torque
            collectGearbox(b);      // gearMode
        } else {
            boolean possiblyCharging =
                (!Double.isNaN(b.chargingPowerKw) && Math.abs(b.chargingPowerKw) > 0.1)
                || (!Double.isNaN(b.externalChargingPowerKw) && b.externalChargingPowerKw > 0.1)
                || b.chargingState == 1   // BMS explicitly says CHARGING
                || b.chargingGunState == 2 || b.chargingGunState == 3
                || b.chargingGunState == 4 || b.chargingGunState == 5;
            if (possiblyCharging) {
                collectEngine(b);   // adds enginePowerKw → confirms direction
            }
        }

        // Extended data consumed by ABRP/MQTT/trips
        collectStatisticExtended(b);   // SOH, driving time, key battery
        collectInstrumentExtended(b);  // cabin temp, trip data, consumption

        // Key proximity probe — runs every poll (ACC on or off) so we keep observing
        // fob state across the parked-charging window and any "approach unlock" event.
        collectKeyProximity(b);

        // Cloud data merge (when toggle enabled and data is fresh)
        mergeCloudData(b);

        snapshot.set(b.build());
    }

    /**
     * Force a full collection of ALL data including display-only fields.
     * Bypasses the 5-second throttle. Called by the HTTP API when a client
     * explicitly requests the full vehicle data, or during init().
     */
    public void collectAllFull() {
        lastCoreCollectTime = 0;  // Bypass throttle

        BydVehicleData.Builder b = (snapshot.get() != null) ? snapshot.get().toBuilder() : new BydVehicleData.Builder();
        b.availableDevices(availableDevices.toArray(new String[0]));
        b.unavailableDevices(unavailableDevices.toArray(new String[0]));

        // Core devices
        collectBodywork(b);
        collectSpeed(b);
        collectEngine(b);
        collectStatistic(b);
        collectCharging(b);
        collectInstrument(b);
        collectOta(b);
        collectGearbox(b);

        // Display-only devices (normally listener-driven, polled here on-demand)
        collectAc(b);
        collectLight(b);
        collectAdas(b);
        collectPower(b);
        collectSafetyBelt(b);
        collectTyre(b);
        collectDoorLock(b);
        collectSensor(b);
        collectEnergy(b);
        collectRadar(b);

        // Extended data — core + display-only
        collectStatisticExtended(b);   // SOH, driving time, key battery
        collectInstrumentExtended(b);  // cabin temp, trip data, consumption
        collectChargingExtended(b);    // charging rest time
        collectBodyworkExtended(b);    // steering, auto system, 12V level, sunroof, sunshade
        collectEngineExtended(b);      // coolant, oil, engine code

        // Cloud data merge (when toggle enabled and data is fresh)
        mergeCloudData(b);

        snapshot.set(b.build());
        lastCoreCollectTime = System.currentTimeMillis();
    }

    private void collectBodywork(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;
        try {
            // VIN
            Object vin = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoVIN");
            if (vin instanceof String) b.vin((String) vin);

            // 12V auxiliary battery voltage (0-255 → 0-25.5V)
            // NOTE: getBatteryPowerValue() returns 12V battery voltage, NOT traction battery SOC.
            // SOC comes from StatisticDevice.getElecPercentageValue() — see collectStatistic().
            Object battPowerRaw = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerValue");
            if (battPowerRaw instanceof Number) {
                double rawVal = ((Number) battPowerRaw).doubleValue();
                double voltage12v = rawVal > 100 ? rawVal / 10.0 : rawVal;
                // Only treat as 12V voltage if it's in a plausible range (8-16V)
                if (voltage12v >= 8.0 && voltage12v <= 16.0 && Double.isNaN(b.voltage12v)) {
                    b.voltage12v(voltage12v);
                }
            }

            // Battery remaining energy — try multiple APIs in priority order.
            // Priority 1: PowerDevice.getBatteryRemainPowerEV() — most accurate for BEVs.
            // On PHEVs this may return stale values when ICE is running — validate against SOC.
            if (Double.isNaN(b.remainKwh) && powerDevice != null) {
                try {
                    Object evKwh = BydDeviceHelper.callGetter(powerDevice, "getBatteryRemainPowerEV");
                    if (evKwh instanceof Number) {
                        double evVal = ((Number) evKwh).doubleValue();
                        if (evVal > 1 && evVal < 120) {
                            // Validate: implied capacity should be within 50-150% of any BYD pack
                            double soc = b.socPercent;
                            if (!Double.isNaN(soc) && soc > 5) {
                                double impliedCap = evVal / (soc / 100.0);
                                if (impliedCap >= 10 && impliedCap <= 130) {
                                    b.remainKwh(evVal);
                                    logger.debug("remainKwh from getBatteryRemainPowerEV: " + 
                                        String.format("%.1f", evVal));
                                } else {
                                    logger.debug("getBatteryRemainPowerEV rejected: " + 
                                        String.format("%.1f", evVal) + " kWh at " + 
                                        String.format("%.0f", soc) + "% SOC → implied " + 
                                        String.format("%.1f", impliedCap) + " kWh");
                                }
                            } else {
                                b.remainKwh(evVal);  // No SOC to validate, accept
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getBatteryRemainPowerEV failed: " + e.getMessage());
                }
            }
            
            // Priority 2: StatisticDevice.getRemainingBatteryPower() — returns int (0.1 kWh units)
            if (Double.isNaN(b.remainKwh) && statisticDevice != null) {
                try {
                    Object rawPower = BydDeviceHelper.callGetter(statisticDevice, "getRemainingBatteryPower");
                    if (rawPower instanceof Number) {
                        int rawVal = ((Number) rawPower).intValue();
                        if (rawVal > 10 && rawVal < 1200) {  // 1-120 kWh in 0.1 units
                            double kwh = rawVal / 10.0;
                            // Validate against SOC
                            double soc = b.socPercent;
                            if (!Double.isNaN(soc) && soc > 5) {
                                double impliedCap = kwh / (soc / 100.0);
                                if (impliedCap >= 10 && impliedCap <= 130) {
                                    b.remainKwh(kwh);
                                    logger.debug("remainKwh from getRemainingBatteryPower: " + 
                                        String.format("%.1f", kwh) + " (raw=" + rawVal + ")");
                                }
                            } else {
                                b.remainKwh(kwh);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("getRemainingBatteryPower failed: " + e.getMessage());
                }
            }
            
            // getBatteryCapacity() — semantics vary by model:
            // - Newer models: returns Ah rating (fixed, e.g. 150 for Atto 3)
            // - Older models: returns remaining energy in 0.1 kWh units (changes with SOC)
            // Used as remainKwh fallback when prior priorities haven't filled it.
            Object cap = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryCapacity");
            if (cap instanceof Number) {
                double capVal = ((Number) cap).doubleValue();
                if (capVal > 0) b.capacityAh(capVal);

                // SOTA: Feed capacity-Ah SOH estimation.
                // Only valid if capVal looks like an Ah rating (50-350 range) and we have pack voltage.
                // If it's in 0.1 kWh units (older models), it'll be <120 and change with SOC — skip those.
                if (capVal >= 50 && capVal <= 350) {
                    try {
                        com.overdrive.app.abrp.SohEstimator sohEst =
                            com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                        if (sohEst != null && sohEst.getNominalCapacityKwh() > 0) {
                            // Derive cell count from pack voltage (same logic as autoDetectFromPackVoltage)
                            BydVehicleData currentVd = getData();
                            int cellCount = 0;
                            if (currentVd != null && !Double.isNaN(currentVd.hvPackVoltage) && currentVd.hvPackVoltage > 200) {
                                double cellV = 3.2;
                                if (!Double.isNaN(currentVd.highCellVoltage) && currentVd.highCellVoltage > 2.5 && currentVd.highCellVoltage < 3.7) {
                                    cellV = currentVd.highCellVoltage;
                                } else if (!Double.isNaN(currentVd.lowCellVoltage) && currentVd.lowCellVoltage > 2.5 && currentVd.lowCellVoltage < 3.7) {
                                    cellV = currentVd.lowCellVoltage;
                                }
                                cellCount = (int) Math.round(currentVd.hvPackVoltage / cellV);
                            }
                            
                            // Fallback: derive cell count from nominal capacity and Ah rating.
                            // On PHEVs, hvPackVoltage may not be reported but we know the pack
                            // size from BMS capacity detection. Formula:
                            //   nominalKwh = cellCount × cellVoltage × Ah / 1000
                            //   cellCount = nominalKwh × 1000 / (Ah × cellVoltage)
                            if (cellCount < 90 || cellCount > 200) {
                                double cellV = 3.2;
                                if (currentVd != null && !Double.isNaN(currentVd.highCellVoltage) && currentVd.highCellVoltage > 2.5 && currentVd.highCellVoltage < 3.7) {
                                    cellV = currentVd.highCellVoltage;
                                }
                                cellCount = (int) Math.round(sohEst.getNominalCapacityKwh() * 1000.0 / (capVal * cellV));
                            }
                            
                            if (cellCount >= 90 && cellCount <= 200) {
                                sohEst.updateFromCapacityAh(capVal, cellCount);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Capacity-Ah SOH update failed: " + e.getMessage());
                    }
                }

                // Fallback for older models where the prior priorities didn't fill remainKwh:
                // getBatteryCapacity() / 10.0 gives remaining kWh
                if (Double.isNaN(b.remainKwh) && capVal > 0) {
                    double kwhFromCap = capVal / 10.0;
                    // Plausible remaining energy range for any BYD model: 1-120 kWh
                    if (kwhFromCap > 1.0 && kwhFromCap < 120.0) {
                        b.remainKwh(kwhFromCap);
                    }
                }
            }

            // Power level
            Object pl = BydDeviceHelper.callGetter(bodyworkDevice, "getPowerLevel");
            if (pl instanceof Number) b.powerLevel(((Number) pl).intValue());

            // Energy type — drivetrain discriminator (BEV/PHEV/HEV/FCEV).
            // Mapping is OEM-specific; we store the raw int and let the consumer interpret.
            // Suspected mapping (validate per model): 1=BEV, 2=PHEV, 3=HEV, 4=FCEV.
            Object et = BydDeviceHelper.callGetter(bodyworkDevice, "getEnergyType");
            if (et instanceof Number) {
                int rawType = ((Number) et).intValue();
                // Filter sentinels (BMS_UNAVAILABLE=65535, INVALID_VALUE=-10011 etc.)
                if (rawType >= 0 && rawType < 100) {
                    b.energyType(rawType);
                    long now = System.currentTimeMillis();
                    if (now - lastEnergyTypeLogMs > 300_000) {
                        lastEnergyTypeLogMs = now;
                        logger.info("getEnergyType=" + rawType + " (1=PHEV, 2=BEV, 3=HEV)");
                    }
                }
            }

            // Battery temp from bodywork (feature ID 300941320, Double.TYPE)
            Object battTemp = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODYWORK_BATTERY_METRIC, Double.class);
            if (battTemp != null) {
                double tempVal = BydDeviceHelper.getDoubleValue(battTemp);
                if (!Double.isNaN(tempVal) && tempVal > -50 && tempVal < 80) b.bodyworkBattTempC(tempVal);
            }

            // Battery range from bodywork (feature ID 300941336, Double.TYPE → intValue)
            Object battRange = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODYWORK_BATTERY_RANGE, Double.class);
            if (battRange != null) {
                int rangeVal = BydDeviceHelper.getIntValue(battRange);
                if (rangeVal >= 0 && rangeVal <= 1016) b.bodyworkRangeKm((int) Math.round(rangeVal * distanceToKmFactor));
            }

            // Window open percent (positions 1-6)
            int[] windows = new int[6];
            for (int i = 0; i < 6; i++) {
                Object wp = BydDeviceHelper.callGetter(bodyworkDevice, "getWindowOpenPercent", i + 1);
                windows[i] = (wp instanceof Number) ? ((Number) wp).intValue() : -1;
            }
            b.windowOpenPercent(windows);

            // Emergency alarm
            Object alarm = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODYWORK_EMERGENCY_ALARM, Integer.class);
            if (alarm != null) b.emergencyAlarmState(BydDeviceHelper.getIntValue(alarm));

        } catch (Exception e) {
            logger.debug("collectBodywork error: " + e.getMessage());
        }
    }

    private void collectSpeed(BydVehicleData.Builder b) {
        if (speedDevice == null) return;
        try {
            Object speed = BydDeviceHelper.callGetter(speedDevice, "getCurrentSpeed");
            if (speed instanceof Number) {
                double v = ((Number) speed).doubleValue();
                if (v != BydFeatureIds.SDK_NOT_AVAILABLE) b.speedKmh(v * distanceToKmFactor);
            }
            Object accel = BydDeviceHelper.callGetter(speedDevice, "getAccelerateDeepness");
            if (accel instanceof Number) b.accelPercent(((Number) accel).intValue());
            Object brake = BydDeviceHelper.callGetter(speedDevice, "getBrakeDeepness");
            if (brake instanceof Number) b.brakePercent(((Number) brake).intValue());
        } catch (Exception e) {
            logger.debug("collectSpeed error: " + e.getMessage());
        }
    }

    private void collectEngine(BydVehicleData.Builder b) {
        if (engineDevice == null) return;
        try {
            // ==================== ENGINE SPEED ====================
            // Feature ID path first — try ENGINE_SPEED (339738642), then ENGINE_SPEED_GB (282066952)
            try {
                Object val = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_SPEED, Integer.class);
                if (val != null) {
                    int raw = BydDeviceHelper.getIntValue(val);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                        && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0 && raw <= 8000) {
                        b.engineSpeedRpm(raw);
                    }
                }
                // Try alternate signal if primary didn't populate
                if (b.engineSpeedRpm == BydVehicleData.UNAVAILABLE) {
                    Object altVal = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_SPEED_ALT, Integer.class);
                    if (altVal != null) {
                        int altRaw = BydDeviceHelper.getIntValue(altVal);
                        if (altRaw != BydFeatureIds.BMS_UNAVAILABLE && altRaw != BydFeatureIds.INVALID_VALUE
                            && altRaw != BydFeatureIds.INVALID_VALUE_2 && altRaw >= 0 && altRaw <= 8000) {
                            b.engineSpeedRpm(altRaw);
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("collectEngine engineSpeed feature ID error: " + e.getMessage());
            }
            // Fallback to typed getter if feature ID didn't populate
            if (b.engineSpeedRpm == BydVehicleData.UNAVAILABLE) {
                Object rpm = BydDeviceHelper.callGetter(engineDevice, "getEngineSpeed");
                if (rpm instanceof Number) {
                    int rpmVal = ((Number) rpm).intValue();
                    if (rpmVal >= 0 && rpmVal <= 8000) b.engineSpeedRpm(rpmVal);
                }
            }

            // ==================== ENGINE POWER ====================
            // Net HV-bus power: positive = motor draw, negative = into battery (regen
            // when driving, plug-in charging when parked).
            //
            // Feature ID path returns a Double in mixed units across firmware:
            //   - On most models: kW (range roughly -200..400)
            //   - On some models: deciwatts × 10 (raw > 100 → scale ×0.1)
            // Range-check excludes sentinels (BMS_UNAVAILABLE etc.) and bogus values.
            try {
                Object val = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_POWER, Double.class);
                if (val != null) {
                    double raw = BydDeviceHelper.getDoubleValue(val);
                    if (!Double.isNaN(raw) && raw >= -200.0 && raw <= 400.0) {
                        double kw = (Math.abs(raw) > 100.0) ? raw * 0.1 : raw;
                        b.enginePowerKw(kw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectEngine enginePower feature ID error: " + e.getMessage());
            }
            // Fallback to typed getter if feature ID didn't populate
            if (Double.isNaN(b.enginePowerKw)) {
                Object power = BydDeviceHelper.callGetter(engineDevice, "getEnginePower");
                if (power instanceof Number) {
                    double kw = ((Number) power).doubleValue();
                    if (kw >= -200.0 && kw <= 400.0) b.enginePowerKw(kw);
                }
            }

            // Front motor speed (negated)
            Object fms = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_FRONT_MOTOR_SPEED, Integer.class);
            if (fms != null) b.frontMotorSpeed(-BydDeviceHelper.getIntValue(fms));

            // Rear motor speed
            Object rms = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_REAR_MOTOR_SPEED, Integer.class);
            if (rms != null) b.rearMotorSpeed(BydDeviceHelper.getIntValue(rms));

            // Front motor torque (negated double)
            Object fmt = BydDeviceHelper.callGet(engineDevice, BydFeatureIds.ENGINE_FRONT_MOTOR_TORQUE, Double.class);
            if (fmt != null) b.frontMotorTorque(-BydDeviceHelper.getDoubleValue(fmt));
        } catch (Exception e) {
            logger.debug("collectEngine error: " + e.getMessage());
        }
    }

    private void collectStatistic(BydVehicleData.Builder b) {
        if (statisticDevice == null) return;
        try {
            // ==================== TOTAL MILEAGE ====================
            // Named getter primary, feature ID fallback
            Object mileage = BydDeviceHelper.callGetter(statisticDevice, "getTotalMileageValue");
            if (mileage instanceof Number) {
                int raw = ((Number) mileage).intValue();
                if (raw > 0) b.totalMileageKm((int) Math.round(raw * distanceToKmFactor));
            }
            if (b.totalMileageKm == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_TOTAL_MILEAGE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.totalMileageKm((int) Math.round(raw * distanceToKmFactor));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic totalMileage feature ID error: " + e.getMessage());
                }
            }

            // ==================== EV MILEAGE ====================
            // Named getter primary, feature ID fallback
            Object evMileage = BydDeviceHelper.callGetter(statisticDevice, "getEVMileageValue");
            if (evMileage instanceof Number) {
                int raw = ((Number) evMileage).intValue();
                if (raw > 0) b.evMileageKm((int) Math.round(raw * distanceToKmFactor));
            }
            if (b.evMileageKm == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_MILEAGE_EV, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.evMileageKm((int) Math.round(raw * distanceToKmFactor));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic evMileage feature ID error: " + e.getMessage());
                }
            }

            // ==================== SOC (ELEC PERCENTAGE) ====================
            // Named getter primary, then feature ID fallback
            Object elecPct = BydDeviceHelper.callGetter(statisticDevice, "getElecPercentageValue");
            if (elecPct instanceof Number) {
                double soc = ((Number) elecPct).doubleValue();
                if (soc >= 0 && soc <= 100) b.socPercent(soc);
            }
            if (Double.isNaN(b.socPercent)) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_ELEC_PERCENTAGE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0 && raw <= 100) {
                            b.socPercent((double) raw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic socPercent feature ID error: " + e.getMessage());
                }
            }

            // ==================== WATER TEMP ====================
            Object waterTemp = BydDeviceHelper.callGetter(statisticDevice, "getWaterTemperature");
            if (waterTemp instanceof Number) b.waterTempC(((Number) waterTemp).intValue());

            // Fallback: try Engine device if Statistic didn't provide coolant temp.
            // Some firmware only exposes coolant temperature via the Engine device.
            if (b.waterTempC == BydVehicleData.UNAVAILABLE && engineDevice != null) {
                try {
                    String[] coolantGetters = {
                        "getWaterTemperature", "getCoolantTemperature",
                        "getEngineCoolantTemperature", "getEngineWaterTemperature",
                        "getEngineCoolantTemp", "getWaterTemp"
                    };
                    for (String getter : coolantGetters) {
                        Object engineCoolant = BydDeviceHelper.callGetter(engineDevice, getter);
                        if (engineCoolant instanceof Number) {
                            int tempC = ((Number) engineCoolant).intValue();
                            if (tempC >= -50 && tempC <= 200) {
                                b.waterTempC(tempC);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            // ==================== TOTAL ELEC CONSUMPTION ====================
            Object totalElec = BydDeviceHelper.callGetter(statisticDevice, "getTotalElecConValue");
            if (totalElec instanceof Number) b.totalElecCon(((Number) totalElec).doubleValue());

            // ==================== TOTAL FUEL CONSUMPTION ====================
            Object totalFuel = BydDeviceHelper.callGetter(statisticDevice, "getTotalFuelConValue");
            if (totalFuel instanceof Number) b.totalFuelCon(((Number) totalFuel).doubleValue());

            // ==================== ELECTRIC DRIVING RANGE ====================
            // Named getter primary, feature ID fallback
            Object elecRange = BydDeviceHelper.callGetter(statisticDevice, "getElecDrivingRangeValue");
            if (elecRange instanceof Number) {
                int raw = ((Number) elecRange).intValue();
                if (raw > 0) b.elecRangeKm((int) Math.round(raw * distanceToKmFactor));
            }
            if (b.elecRangeKm == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_ELEC_DRIVING_RANGE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                            b.elecRangeKm((int) Math.round(raw * distanceToKmFactor));
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectStatistic elecRange feature ID error: " + e.getMessage());
                }
            }

            // ==================== FUEL PERCENTAGE & FUEL RANGE (PHEV only) ====================
            // BEVs return bogus CAN bus values for fuel (e.g. constant 62% on a Seal).
            boolean isPhev = isPhev(b);

            // ==================== FUEL DRIVING RANGE (PHEV only) ====================
            if (isPhev) {
                // Named getter primary, feature ID fallback
                Object fuelRange = BydDeviceHelper.callGetter(statisticDevice, "getFuelDrivingRangeValue");
                if (fuelRange instanceof Number) {
                    int raw = ((Number) fuelRange).intValue();
                    if (raw > 0) b.fuelRangeKm((int) Math.round(raw * distanceToKmFactor));
                }
                if (b.fuelRangeKm == BydVehicleData.UNAVAILABLE) {
                    try {
                        Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_FUEL_DRIVING_RANGE, Integer.class);
                        if (val != null) {
                            int raw = BydDeviceHelper.getIntValue(val);
                            if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                                && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0) {
                                b.fuelRangeKm((int) Math.round(raw * distanceToKmFactor));
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("collectStatistic fuelRange feature ID error: " + e.getMessage());
                    }
                }
            }

            // ==================== FUEL PERCENTAGE (PHEV only) ====================
            if (isPhev) {
                // Named getter primary
                Object fuelPct = BydDeviceHelper.callGetter(statisticDevice, "getFuelPercentageValue");
                if (fuelPct instanceof Number) {
                    int pct = ((Number) fuelPct).intValue();
                    if (pct > 0 && pct <= 100) {
                        b.fuelPercent(pct);
                    }
                }
                // Feature ID fallback
                if (Double.isNaN(b.fuelPercent)) {
                    try {
                        Object val = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_FUEL_PERCENTAGE, Integer.class);
                        if (val != null) {
                            int raw = BydDeviceHelper.getIntValue(val);
                            if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                                && raw != BydFeatureIds.INVALID_VALUE_2 && raw > 0 && raw <= 100) {
                                b.fuelPercent(raw);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Fuel percentage feature ID failed: " + e.getMessage());
                    }
                }
            }

            // Battery temps via get() — intValue - 40 = °C
            collectStatTemp(b, BydFeatureIds.STAT_HIGHEST_BATTERY_TEMP, "high");
            collectStatTemp(b, BydFeatureIds.STAT_LOWEST_BATTERY_TEMP, "low");
            collectStatTemp(b, BydFeatureIds.STAT_AVERAGE_BATTERY_TEMP, "avg");

            // Cell voltages via get() — intValue / 1000.0 = V
            collectStatVoltage(b, BydFeatureIds.STAT_HIGHEST_BATTERY_VOLTAGE, "high");
            collectStatVoltage(b, BydFeatureIds.STAT_LOWEST_BATTERY_VOLTAGE, "low");
        } catch (Exception e) {
            logger.debug("collectStatistic error: " + e.getMessage());
        }
    }

    private void collectStatTemp(BydVehicleData.Builder b, int featureId, String which) {
        Object val = BydDeviceHelper.callGet(statisticDevice, featureId, Integer.TYPE);
        if (val == null) val = BydDeviceHelper.callGet(statisticDevice, featureId, Integer.class);
        if (val == null) return;
        int raw = BydDeviceHelper.getIntValue(val);
        if (raw == BydFeatureIds.BMS_UNAVAILABLE || raw == BydFeatureIds.INVALID_VALUE
            || raw == BydFeatureIds.INVALID_VALUE_2 || raw == Integer.MIN_VALUE) return;
        if (raw < 0 || raw > 120) return;
        double tempC = raw - 40;
        switch (which) {
            case "high": b.highCellTempC(tempC); break;
            case "low": b.lowCellTempC(tempC); break;
            case "avg": b.avgCellTempC(tempC); break;
        }
    }

    private void collectStatVoltage(BydVehicleData.Builder b, int featureId, String which) {
        Object val = BydDeviceHelper.callGet(statisticDevice, featureId, Integer.TYPE);
        if (val == null) val = BydDeviceHelper.callGet(statisticDevice, featureId, Integer.class);
        if (val == null) return;
        int raw = BydDeviceHelper.getIntValue(val);
        if (raw == BydFeatureIds.BMS_UNAVAILABLE || raw == BydFeatureIds.INVALID_VALUE
            || raw == BydFeatureIds.INVALID_VALUE_2 || raw == Integer.MIN_VALUE || raw <= 0) return;
        double volts = raw / 1000.0;
        if (volts < 1.0 || volts > 5.0) return;
        switch (which) {
            case "high": b.highCellVoltage(volts); break;
            case "low": b.lowCellVoltage(volts); break;
        }
    }

    private void collectCharging(BydVehicleData.Builder b) {
        if (chargingDevice == null) return;
        try {
            // Named getters for init read
            Object gunState = BydDeviceHelper.callGetter(chargingDevice, "getChargingGunState");
            if (gunState instanceof Number) b.chargingGunState(((Number) gunState).intValue());

            Object charger = BydDeviceHelper.callGetter(chargingDevice, "getChargerWorkState");
            if (charger instanceof Number) b.chargerWorkState(((Number) charger).intValue());

            // Feature ID for battery device state, fallback to named getter
            try {
                Object val = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_BATTERY_DEVICE_STATE, Integer.class);
                if (val != null) {
                    int raw = BydDeviceHelper.getIntValue(val);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                        && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0) {
                        b.chargingState(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging batteryState feature ID error: " + e.getMessage());
            }
            if (b.chargingState == BydVehicleData.UNAVAILABLE) {
                Object battState = BydDeviceHelper.callGetter(chargingDevice, "getBatteryManagementDeviceState");
                if (battState instanceof Number) {
                    b.chargingState(((Number) battState).intValue());
                }
            }

            // Charging power from the BYD ChargingDevice SDK getter.
            // Sentinel filter: SDK reports up to ±500 kW; reject anything beyond.
            // Listener callbacks (onChargingPowerChanged) keep this fresh between polls.
            Object power = BydDeviceHelper.callGetter(chargingDevice, "getChargingPower");
            if (power instanceof Number) {
                double kw = ((Number) power).doubleValue();
                if (Math.abs(kw) > 0.01 && Math.abs(kw) < 500) {
                    b.chargingPowerKw(kw);
                }
            }

            // Targeted clear: when the BMS reports an EXPLICIT non-charging
            // terminal state (READY=0, FINISHED=2, TERMINATED=4, DISCHARG_FINISH=12),
            // we know the previous charging session is over. Clear sticky listener-
            // delivered power so the inference layer in VehicleDataMonitor can't
            // false-trigger from leftover values. We do NOT clear on IDLE (15)
            // because that's the buggy reading some PHEV firmwares give while
            // actually charging — clearing there would break detection again.
            // We do NOT clear on disconnect-only signals (gunState==1) without a
            // BMS state agreeing, because PHEVs often leave gunState UNAVAILABLE.
            if (b.chargingState == 0 || b.chargingState == 2
                    || b.chargingState == 4 || b.chargingState == 12) {
                b.chargingPowerKw(Double.NaN);
                b.externalChargingPowerKw(Double.NaN);
            }

            // Charging mode — getChargingMode() raw value (AC vs DC vs wireless, model-specific).
            // Stored on the snapshot; logged once on first sight then throttled at 5min.
            Object mode = BydDeviceHelper.callGetter(chargingDevice, "getChargingMode");
            if (mode instanceof Number) {
                int rawMode = ((Number) mode).intValue();
                // Filter sentinels (BMS_UNAVAILABLE=65535, INVALID values)
                if (rawMode >= 0 && rawMode < 100) {
                    b.chargingMode(rawMode);
                    long now = System.currentTimeMillis();
                    if (now - lastChargingModeLogMs > 300_000) {
                        lastChargingModeLogMs = now;
                        logger.info("getChargingMode=" + rawMode);
                    }
                }
            }

            // SDK getChargingState() — distinct from getBatteryManagementDeviceState() above.
            // Diagnostic only for now: log to verify the value space against our existing
            // chargingState (which may come from a different source).
            Object chState = BydDeviceHelper.callGetter(chargingDevice, "getChargingState");
            if (chState instanceof Number) {
                int rawState = ((Number) chState).intValue();
                if (rawState >= 0 && rawState < 100) {
                    long now = System.currentTimeMillis();
                    if (now - lastChargingStateRawLogMs > 300_000) {
                        lastChargingStateRawLogMs = now;
                        logger.debug("getChargingState=" + rawState + " (collector chargingState=" + b.chargingState + ")");
                    }
                }
            }

            // Charging type (0=DEFAULT, 3=VTOG)
            Object type = BydDeviceHelper.callGetter(chargingDevice, "getChargingType");
            if (type instanceof Number) b.chargingType(((Number) type).intValue());

            // VTOL detection — gunState==5 OR chargingType==3
            boolean isVtol = false;
            if (b.chargingGunState == 5) isVtol = true;
            if (b.chargingType == 3) isVtol = true;
            b.vtolCharging(isVtol);

            // Charging capacity (kWh)
            Object cap = BydDeviceHelper.callGetter(chargingDevice, "getChargingCapacity");
            if (cap instanceof Number) {
                double capKwh = ((Number) cap).doubleValue();
                if (capKwh > 0) b.chargingCapacityKwh(capKwh);
            }

            // Charging percent from chargingDevice
            Object pct = BydDeviceHelper.callGetter(chargingDevice, "getChargingPercent");
            if (pct instanceof Number) {
                int chgPct = ((Number) pct).intValue();
                if (chgPct >= 0 && chgPct <= 100) b.chargingPercent(chgPct);
            }

            // Charger work state via feature ID fallback
            if (b.chargerWorkState == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_CHARGER_WORK_STATE, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE
                            && raw != BydFeatureIds.INVALID_VALUE_2 && raw >= 0) {
                            b.chargerWorkState(raw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectCharging chargerWorkState feature ID error: " + e.getMessage());
                }
            }

            // Wireless charging states via feature IDs
            try {
                Object wlLeft = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_LEFT_STATE, Integer.class);
                if (wlLeft != null) {
                    int raw = BydDeviceHelper.getIntValue(wlLeft);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE) {
                        b.wirelessChargingLeftState(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging wirelessLeft error: " + e.getMessage());
            }
            try {
                Object wlRight = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_RIGHT_STATE, Integer.class);
                if (wlRight != null) {
                    int raw = BydDeviceHelper.getIntValue(wlRight);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE) {
                        b.wirelessChargingRightState(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging wirelessRight error: " + e.getMessage());
            }
            try {
                Object wlState = BydDeviceHelper.callGet(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_STATE, Integer.class);
                if (wlState != null) {
                    int raw = BydDeviceHelper.getIntValue(wlState);
                    if (raw != BydFeatureIds.BMS_UNAVAILABLE && raw != BydFeatureIds.INVALID_VALUE) {
                        b.wirelessChargingStatus(raw);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectCharging wirelessState error: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.debug("collectCharging error: " + e.getMessage());
        }
    }

    private void collectInstrument(BydVehicleData.Builder b) {
        if (instrumentDevice == null) return;
        try {
            // Named getter for outside temperature
            Object extTemp = BydDeviceHelper.callGetter(instrumentDevice, "getOutCarTemperature");
            if (extTemp instanceof Number) {
                int t = ((Number) extTemp).intValue();
                if (t >= -50 && t <= 60) b.outsideTempC(t);
            }

            // External charging power. Two scaling regimes seen across BYD firmware:
            //
            //   - Listener path (onExternalChargingPowerChanged) — SDK pre-scales
            //     to kW. This matches BYDCarController in autocommander, which
            //     treats the listener arg as kW directly.
            //   - Polled getter — some firmware (Seal U DM-i PHEV, build 1124xxx)
            //     returns the raw CAN value in hectowatts (centiKW). Observed:
            //     221.7 raw for a real ~1.9 kW charger (221.7/100 = 2.217 kW,
            //     which is the wall-side handshake before AC→DC conversion loss).
            //     Same firmware family is the one that has the feature ID
            //     fallback below also delivering hectowatts (189.5 raw → 1.8 kW
            //     charger, per the comment on that path).
            //
            // Heuristic: kW values are bounded by physical reality (AC charging
            // tops at ~22 kW 3-phase, PHEV onboard charger maxes at 7 kW).
            // Anything above 50 from a getter that's supposed to be kW is the
            // hectowatt scale — divide. Below 50, trust the value as-is.
            // The 104857.5 BYD sentinel falls cleanly above the 50000 cap.
            Object extPower = BydDeviceHelper.callGetter(instrumentDevice, "getExternalChargingPower");
            if (extPower instanceof Number) {
                double raw = ((Number) extPower).doubleValue();
                double kw;
                if (raw > 50.0 && raw < 50000.0) {
                    kw = raw / 100.0; // hectowatts → kW
                } else {
                    kw = raw;         // already kW (BEV firmware default)
                }
                if (kw > 0.1 && kw <= 500) {
                    b.externalChargingPowerKw(kw);
                    if (!loggedExtChargePowerScale) {
                        loggedExtChargePowerScale = true;
                        logger.info("getExternalChargingPower: raw=" + raw + " → " + kw
                                + " kW (scale=" + (raw > 50.0 && raw < 50000.0 ? "hectowatts/100" : "kW")
                                + "). Cross-check against the cluster's charging readout to confirm.");
                    }
                }
            }

            // Feature ID fallback (842006552). Returns raw CAN value in hectowatts
            // (value/100 = kW); evidence: 1.8 kW charger reports 189.5 raw.
            // Used only when the typed getter above returned nothing useful.
            if (Double.isNaN(b.externalChargingPowerKw)
                    && (Double.isNaN(b.chargingPowerKw) || b.chargingPowerKw == 0)) {
                try {
                    Object val = BydDeviceHelper.callGet(instrumentDevice,
                            BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_POWER_DD, Double.class);
                    if (val != null) {
                        double raw = BydDeviceHelper.getDoubleValue(val);
                        if (!Double.isNaN(raw) && Math.abs(raw) > 1.0 && Math.abs(raw) < 35000) {
                            // Convert from hectowatts to kW
                            double kw = raw / 100.0;
                            b.chargingPowerKw(kw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectInstrument chargingPower feature ID error: " + e.getMessage());
                }
            }

            // Charging percent via instrument feature ID (842006544) — read
            // unconditionally as fallback when the chargingDevice path didn't
            // populate it. Gating on a BMS-derived "may be charging" flag here
            // creates the same circular dependency we removed from the power
            // reads above; the safe-clear in collectCharging() wipes stale
            // values when the vehicle is genuinely idle (BMS not charging AND
            // gun disconnected).
            if (b.chargingPercent == BydVehicleData.UNAVAILABLE) {
                try {
                    Object val = BydDeviceHelper.callGet(instrumentDevice,
                            BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_PERCENT_DD, Integer.class);
                    if (val != null) {
                        int raw = BydDeviceHelper.getIntValue(val);
                        if (raw >= 0 && raw <= 100) {
                            b.chargingPercent(raw);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("collectInstrument chargingPercent feature ID error: " + e.getMessage());
                }
            }

            // Charging rest time via instrument feature IDs (primary path)
            // Fallback to chargingDevice.getChargingRestTime() is in collectChargingExtended()
            // Validates: 255 = not available, hours 0-23, minutes 0-59
            try {
                Object hourVal = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_REST_HOUR_DD, Integer.class);
                Object minVal = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_CHARGING_CHARGE_REST_MINUTE_DD, Integer.class);
                if (hourVal != null && minVal != null) {
                    int hours = BydDeviceHelper.getIntValue(hourVal);
                    int minutes = BydDeviceHelper.getIntValue(minVal);
                    if (hours != 255 && minutes != 255 && hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 59) {
                        b.chargingRestTimeHours(hours);
                        b.chargingRestTimeMinutes(minutes);
                    }
                }
            } catch (Exception e) {
                logger.debug("collectInstrument chargingRestTime feature ID error: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.debug("collectInstrument error: " + e.getMessage());
        }
    }

    private void collectOta(BydVehicleData.Builder b) {
        if (otaDevice == null) return;
        try {
            Object voltage = BydDeviceHelper.callGetter(otaDevice, "getBatteryPowerVoltage");
            if (voltage instanceof Number) {
                double v = ((Number) voltage).doubleValue();
                if (v > 0 && v < 20) b.voltage12v(v);
            }
        } catch (Exception e) {
            logger.debug("collectOta error: " + e.getMessage());
        }
    }

    private void collectGearbox(BydVehicleData.Builder b) {
        if (gearboxDevice == null) return;
        try {
            Object gear = BydDeviceHelper.callGetter(gearboxDevice, "getGearboxAutoModeType");
            if (gear instanceof Number) b.gearMode(((Number) gear).intValue());
        } catch (Exception e) {
            logger.debug("collectGearbox error: " + e.getMessage());
        }
    }

    private void collectAc(BydVehicleData.Builder b) {
        if (acDevice == null) return;
        try {
            Object acState = BydDeviceHelper.callGetter(acDevice, "getAcStartState");
            if (acState instanceof Number) b.acStartState(((Number) acState).intValue());
            Object cycle = BydDeviceHelper.callGetter(acDevice, "getAcCycleMode");
            if (cycle instanceof Number) b.acCycleMode(((Number) cycle).intValue());
            Object wind = BydDeviceHelper.callGetter(acDevice, "getAcWindMode");
            if (wind instanceof Number) b.acWindMode(((Number) wind).intValue());
            Object fanLevel = BydDeviceHelper.callGetter(acDevice, "getAcWindLevel");
            if (fanLevel instanceof Number) {
                int level = ((Number) fanLevel).intValue();
                if (level >= 0 && level <= 7) b.acFanLevel(level);
            }
            Object unit = BydDeviceHelper.callGetter(acDevice, "getTemperatureUnit");
            if (unit instanceof Number) b.tempUnit(((Number) unit).intValue());
            // Inside temp (position 1)
            Object insideTemp = BydDeviceHelper.callGetter(acDevice, "getTemprature", 1);
            if (insideTemp instanceof Number) {
                int t = ((Number) insideTemp).intValue();
                if (t >= -50 && t <= 60) b.insideTempC(t);
            }
        } catch (Exception e) {
            logger.debug("collectAc error: " + e.getMessage());
        }
    }

    private void collectLight(BydVehicleData.Builder b) {
        if (lightDevice == null) return;
        try {
            Object left = BydDeviceHelper.callGetter(lightDevice, "getTurnLightState", 1);
            if (left instanceof Number) b.leftTurnState(((Number) left).intValue());
            Object right = BydDeviceHelper.callGetter(lightDevice, "getTurnLightState", 2);
            if (right instanceof Number) b.rightTurnState(((Number) right).intValue());
            // Light status: 1=low, 2=high, 3=position, 6=rearFog, 7=frontFog, 8=hazard
            b.lowBeam(getLightStatus(1) == 1);
            b.highBeam(getLightStatus(2) == 1);
            b.rearFog(getLightStatus(6) == 1);
            b.frontFog(getLightStatus(7) == 1);
            b.hazard(getLightStatus(8) == 1);
            Object dayTime = BydDeviceHelper.callGetter(lightDevice, "getDayTimeLightState");
            if (dayTime instanceof Number) b.dayTimeLight(((Number) dayTime).intValue() == 1);
        } catch (Exception e) {
            logger.debug("collectLight error: " + e.getMessage());
        }
    }

    private int getLightStatus(int position) {
        Object val = BydDeviceHelper.callGetter(lightDevice, "getLightStatus", position);
        return (val instanceof Number) ? ((Number) val).intValue() : 0;
    }

    private void collectAdas(BydVehicleData.Builder b) {
        if (adasDevice == null) return;
        try {
            int speedLimitWarning = BydDeviceHelper.callGetSingle(adasDevice, BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE);
            if (speedLimitWarning >= 0) {
                b.speedLimitWarning(speedLimitWarning == 2);
            }
        } catch (Exception e) {
            logger.debug("collectAdas error: " + e.getMessage());
        }
    }

    private void collectPower(BydVehicleData.Builder b) {
        if (powerDevice == null) return;
        try {
            // BYDAutoPowerDevice is a singleton that may have been initialized by another daemon
            // with a null/stale context. Force-update the internal context before calling methods.
            ensureDeviceContext(powerDevice);
            
            Object mcu = BydDeviceHelper.callGetter(powerDevice, "getMcuStatus");
            if (mcu instanceof Number) b.mcuStatus(((Number) mcu).intValue());
            // NOTE: getBatteryRemainPowerEV() intentionally NOT called here.
            // On PHEVs (Sealion 6 DM-i), the PowerDevice EV subsystem returns stale kWh
            // values when the ICE is running. We rely on Statistic/Bodywork paths for
            // remaining kWh on both BEVs and PHEVs.
        } catch (Exception e) {
            logger.debug("collectPower error: " + e.getMessage());
        }
    }
    
    /**
     * Force-update a BYD device singleton's internal context field.
     * BYD singletons store context from the first getInstance() call.
     * If another daemon initialized it first with a null/stale context, methods NPE.
     */
    /**
     * PHEV detection. Primary signal: bodywork getEnergyType() — typed value
     * surfaced on the snapshot/builder. Suspected mapping is 1=BEV, 2=PHEV, 3=HEV;
     * we treat 2 and 3 as PHEV/HEV (both have a fuel tank). Falls back to the
     * SohEstimator nominal-capacity heuristic when energyType is unavailable
     * or holds a sentinel value, preserving prior behavior.
     */
    private boolean isPhev(BydVehicleData.Builder b) {
        return isPhevFromEnergyType(b.energyType);
    }

    private boolean isPhev(BydVehicleData snapshot) {
        return isPhevFromEnergyType(snapshot.energyType);
    }

    private boolean isPhevFromEnergyType(int energyType) {
        // Mapping confirmed against BYD-DiLink commander field data:
        //   1 = PHEV (DM-i, DM-p — has both fuel and battery)
        //   2 = BEV  (pure electric)
        //   3 = HEV  (rare)
        if (energyType == 1 || energyType == 3) return true;
        if (energyType == 2) return false;
        // Fallback only when getEnergyType returned a sentinel or the device
        // wasn't ready: SohEstimator nominal capacity (PHEVs < 30 kWh).
        try {
            com.overdrive.app.abrp.SohEstimator sohEst =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (sohEst != null && sohEst.getNominalCapacityKwh() > 0) {
                return sohEst.getNominalCapacityKwh() < 30.0;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void ensureDeviceContext(Object device) {
        if (device == null || context == null) return;
        try {
            // Walk up to AbsBYDAutoDevice and set mContext
            Class<?> cls = device.getClass();
            while (cls != null && cls != Object.class) {
                try {
                    java.lang.reflect.Field contextField = cls.getDeclaredField("mContext");
                    contextField.setAccessible(true);
                    Object currentCtx = contextField.get(device);
                    if (currentCtx == null) {
                        contextField.set(device, context);
                        logger.info("Fixed null context on " + device.getClass().getSimpleName());
                    }
                    return;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
        } catch (Exception e) {
            logger.debug("ensureDeviceContext failed: " + e.getMessage());
        }
    }

    private void collectSafetyBelt(BydVehicleData.Builder b) {
        if (safetyBeltDevice == null) return;
        try {
            int[] belts = new int[5];
            for (int i = 0; i < 5; i++) {
                Object s = BydDeviceHelper.callGetter(safetyBeltDevice, "getSafetyBeltStatus", i + 1);
                belts[i] = (s instanceof Number) ? ((Number) s).intValue() : -1;
            }
            b.seatbeltStatus(belts);
        } catch (Exception e) {
            logger.debug("collectSafetyBelt error: " + e.getMessage());
        }
    }

    private void collectTyre(BydVehicleData.Builder b) {
        if (tyreDevice == null) return;
        try {
            // Pressure value (kPa, raw int — confirmed by BYD-DiLink commander app's
            // UnitFormatter.formatPressure(): no scaling for kPa, *0.1450377 for psi,
            // /100 for bar). Areas: 1=FL, 2=FR, 3=RL, 4=RR.
            int[] pressures = new int[4];
            int[] pressureStates = new int[4];
            int[] airLeakStates = new int[4];
            int[] signalStates = new int[4];
            for (int i = 0; i < 4; i++) {
                Object p = BydDeviceHelper.callGetter(tyreDevice, "getTyrePressureValue", i + 1);
                pressures[i] = (p instanceof Number) ? ((Number) p).intValue() : -1;
                Object s = BydDeviceHelper.callGetter(tyreDevice, "getTyrePressureState", i + 1);
                pressureStates[i] = (s instanceof Number) ? ((Number) s).intValue() : -1;
                Object leak = BydDeviceHelper.callGetter(tyreDevice, "getTyreAirLeakState", i + 1);
                airLeakStates[i] = (leak instanceof Number) ? ((Number) leak).intValue() : -1;
                Object sig = BydDeviceHelper.callGetter(tyreDevice, "getTyreSignalState", i + 1);
                signalStates[i] = (sig instanceof Number) ? ((Number) sig).intValue() : -1;

                // Poll per-wheel temperature via the matching SDK getter.
                // The async onTyreBatteryValueChanged callback is dormant on
                // some firmwares (PHEV models on this fleet, confirmed by
                // log capture), but a small subset of those firmwares still
                // answer getTyreBatteryValue(area) with the same temperature
                // value the cluster reads. callGetter is null-safe so this
                // is a no-op on firmwares that don't expose the getter.
                pollPerWheelTyreTemp(i);
            }
            b.tyrePressure(pressures);
            b.tyrePressureState(pressureStates);
            b.tyreAirLeakState(airLeakStates);
            b.tyreSignalState(signalStates);
            b.tyreTemperature(snapshotTyreTemperatures());

            Object sys = BydDeviceHelper.callGetter(tyreDevice, "getTyreSystemState");
            if (sys instanceof Number) b.tyreSystemState(((Number) sys).intValue());
            Object temp = BydDeviceHelper.callGetter(tyreDevice, "getTyreTemperatureState");
            if (temp instanceof Number) b.tyreTemperatureState(((Number) temp).intValue());

            // Per-tyre temperature has three possible channels:
            //   1. Async listener: AbsBYDAutoTyreListener.onTyreBatteryValueChanged
            //      — fires on BEV firmware when registered via the two-arg
            //      registerListener(listener, int[]) overload. Dormant on some
            //      PHEV firmware with single-arg registration only.
            //   2. Polled getter: pollPerWheelTyreTemp() above tries
            //      getTyreBatteryValue / getTyreTemperatureValue /
            //      getTyreTemperature / getTyreTemperatureState.
            //   3. InstrumentDevice feature IDs: polled in
            //      collectInstrumentExtended() using the LF/RF/LB/RB
            //      tyre temperature feature IDs from BydFeatureIds.
            // If all three channels stay silent, tyre temperature is not
            // available on this firmware via any known SDK path.

            logTyreAlertsIfChanged(pressures, pressureStates, airLeakStates, signalStates,
                    sys instanceof Number ? ((Number) sys).intValue() : Integer.MIN_VALUE,
                    temp instanceof Number ? ((Number) temp).intValue() : Integer.MIN_VALUE);
        } catch (Exception e) {
            logger.debug("collectTyre error: " + e.getMessage());
        }
    }

    // Last-seen tyre alert state — change-only logging so a developing slow leak
    // surfaces at info, but a healthy car doesn't spam the log every poll.
    private volatile int[] lastTyrePressuresKpa = null;
    private volatile int[] lastTyrePressureStates = null;
    private volatile int[] lastTyreAirLeakStates = null;
    private volatile int[] lastTyreSignalStates = null;
    private volatile int lastTyreSystemState = Integer.MIN_VALUE;
    private volatile int lastTyreTemperatureState = Integer.MIN_VALUE;

    private final int[] tyreTemperatureCache = new int[]{
            BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE,
            BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE
    };
    private volatile boolean loggedTyreSlot0 = false;
    private volatile boolean loggedInstrumentTyreTemp = false;
    // Per-wheel one-shot log. We surface the FIRST onTyreBatteryValueChanged
    // arrival for each wheel at info level so it's obvious from a single log
    // pull whether the BYD HAL is delivering temperature events at all on
    // this vehicle. Without this, a silent firmware looks identical to a
    // working firmware where we just haven't received an event yet.
    private final boolean[] loggedTyreFirstEvent = new boolean[]{false, false, false, false};
    private volatile boolean loggedTyreOutOfRange = false;

    private void logTyreAlertsIfChanged(int[] pressuresKpa, int[] pressureStates,
                                        int[] airLeakStates, int[] signalStates,
                                        int sysState, int tempState) {
        // Pressures fluctuate constantly (heat, drive cycle). Only treat as
        // "changed" if any wheel moves more than 5 kPa; otherwise the log
        // would fire every poll on a moving car.
        boolean pressureChanged = lastTyrePressuresKpa == null;
        if (!pressureChanged) {
            for (int i = 0; i < pressuresKpa.length; i++) {
                if (Math.abs(pressuresKpa[i] - lastTyrePressuresKpa[i]) > 5) {
                    pressureChanged = true;
                    break;
                }
            }
        }
        boolean alertChanged =
                !java.util.Arrays.equals(pressureStates, lastTyrePressureStates)
                || !java.util.Arrays.equals(airLeakStates, lastTyreAirLeakStates)
                || !java.util.Arrays.equals(signalStates, lastTyreSignalStates)
                || sysState != lastTyreSystemState
                || tempState != lastTyreTemperatureState;
        if (!pressureChanged && !alertChanged) return;

        // Notification emit on TPMS state transitions. We only fire on
        // 0 (NORMAL) -> non-zero edges so a stuck-non-zero alarm doesn't
        // re-notify on every poll. The TPMS firmware itself is the source
        // of truth — we don't threshold kPa ourselves (matches the cluster's
        // own private-binder calibration).
        if (lastTyrePressureStates != null && lastTyreAirLeakStates != null) {
            String[] wheelLabels = {"Front-left", "Front-right", "Rear-left", "Rear-right"};
            for (int i = 0; i < 4 && i < pressureStates.length; i++) {
                int prevP = lastTyrePressureStates[i];
                int curP = pressureStates[i];
                if (prevP == 0 && curP != 0) {
                    try {
                        org.json.JSONObject data = new org.json.JSONObject();
                        data.put("wheel", i);
                        data.put("kPa", pressuresKpa[i]);
                        data.put("state", curP);
                        com.overdrive.app.notifications.NotificationBus.get().publish(
                                new com.overdrive.app.notifications.NotificationEvent(
                                        "vehicle.health.tyre.pressure",
                                        com.overdrive.app.notifications.NotificationEvent.Severity.WARN,
                                        curP == 1 ? "Underpressure" : "Overpressure",
                                        wheelLabels[i] + " — " + pressuresKpa[i] + " kPa",
                                        "tyre-pressure-" + i,
                                        null,
                                        data));
                    } catch (Throwable t) {
                        logger.debug("tyre.pressure notify failed: " + t.getMessage());
                    }
                }

                int prevL = lastTyreAirLeakStates[i];
                int curL = airLeakStates[i];
                if (prevL == 0 && curL != 0) {
                    try {
                        org.json.JSONObject data = new org.json.JSONObject();
                        data.put("wheel", i);
                        data.put("leakState", curL);
                        data.put("kPa", pressuresKpa[i]);
                        com.overdrive.app.notifications.NotificationEvent.Severity sev =
                                curL == 2
                                        ? com.overdrive.app.notifications.NotificationEvent.Severity.CRITICAL
                                        : com.overdrive.app.notifications.NotificationEvent.Severity.WARN;
                        com.overdrive.app.notifications.NotificationBus.get().publish(
                                new com.overdrive.app.notifications.NotificationEvent(
                                        "vehicle.health.tyre.leak",
                                        sev,
                                        curL == 2 ? "Fast leak detected" : "Slow leak detected",
                                        wheelLabels[i] + " (" + pressuresKpa[i] + " kPa)",
                                        "tyre-leak-" + i,
                                        null,
                                        data));
                    } catch (Throwable t) {
                        logger.debug("tyre.leak notify failed: " + t.getMessage());
                    }
                }
            }
        }

        lastTyrePressuresKpa = pressuresKpa.clone();
        lastTyrePressureStates = pressureStates.clone();
        lastTyreAirLeakStates = airLeakStates.clone();
        lastTyreSignalStates = signalStates.clone();
        lastTyreSystemState = sysState;
        lastTyreTemperatureState = tempState;
        // Per-wheel readout: kPa, alarm-state enum (0=NORMAL/1=UNDER/2=OVER),
        // leak-state enum (0=Normal/1=Slow/2=Fast), signal-state enum (0=OK/1=Err)
        StringBuilder sb = new StringBuilder("Tyre:");
        String[] labels = {" FL", " FR", " RL", " RR"};
        for (int i = 0; i < 4; i++) {
            sb.append(labels[i]).append("=").append(pressuresKpa[i]).append("kPa");
            sb.append("/alarm=").append(pressureStates[i]);
            sb.append("/leak=").append(airLeakStates[i]);
            sb.append("/sig=").append(signalStates[i]);
        }
        sb.append(" sys=").append(sysState == Integer.MIN_VALUE ? "n/a" : sysState);
        sb.append(" temp=").append(tempState == Integer.MIN_VALUE ? "n/a" : tempState);
        logger.info(sb.toString());
    }

    // Per-wheel temperature poll: candidate method names, in order. On the
    // first poll we look up each via reflection; from then on we go straight
    // to the surviving method (or short-circuit if none exist on this
    // firmware). This means a sensor that wakes up later still gets a chance
    // to report — we only lock out based on method-existence, not on whether
    // a value was in range.
    private static final String[] TYRE_TEMP_GETTER_CANDIDATES = {
            "getTyreBatteryValue",      // matches the async callback name
            "getTyreTemperatureValue",  // some HEV variants
            "getTyreTemperature",       // legacy / wrapper variants
            "getTyreTemperatureState"   // indexed state getter that returns °C on some firmware
    };
    // null = not resolved yet (first cycle still running),
    // != null and != NO_TYRE_TEMP_GETTER = the resolved Method,
    // == NO_TYRE_TEMP_GETTER sentinel = no candidate exists on this firmware.
    private static final java.lang.reflect.Method NO_TYRE_TEMP_GETTER;
    static {
        java.lang.reflect.Method m = null;
        try { m = Object.class.getDeclaredMethod("toString"); }
        catch (NoSuchMethodException e) { /* impossible */ }
        NO_TYRE_TEMP_GETTER = m;
    }
    private volatile java.lang.reflect.Method resolvedTyreTempMethod = null;
    // Index into TYRE_TEMP_GETTER_CANDIDATES: which candidate is currently resolved.
    // When the resolved method consistently returns out-of-range values, we advance
    // to the next candidate. This ensures getTyreBatteryValue returning battery voltage
    // doesn't permanently block getTyreTemperatureState from being tried.
    private volatile int resolvedTyreTempCandidateIdx = 0;
    private volatile int tyreTempOutOfRangeCount = 0;
    private static final int TYRE_TEMP_OUT_OF_RANGE_THRESHOLD = 5; // after 5 bad reads, try next
    private final boolean[] loggedTyrePollHit = new boolean[]{false, false, false, false};
    // Diagnostics: surface the FIRST observation per failure mode so a single
    // log capture tells us which path the BYD HAL is taking.
    private volatile boolean loggedTyrePollNullReturn = false;
    private volatile boolean loggedTyrePollNonNumber = false;
    private volatile boolean loggedTyrePollThrew = false;

    /**
     * Try to read per-wheel temperature via a getter call. On firmwares where
     * the {@code onTyreBatteryValueChanged} async callback never fires, a
     * polled getter is the only remaining channel.
     *
     * <p>The first invocation looks up each candidate method by name; the
     * first one that exists is cached and used from then on. We do NOT
     * require a value in range to "resolve" the getter — a sensor that's
     * temporarily out of signal can still come online later.
     */
    private void pollPerWheelTyreTemp(int idx) {
        if (tyreDevice == null) return;
        int wheel = idx + 1; // BYD area: LF=1, RF=2, LR=3, RR=4

        java.lang.reflect.Method method = resolvedTyreTempMethod;
        if (method == NO_TYRE_TEMP_GETTER) return;
        if (method == null) {
            method = resolveTyreTempMethod();
            resolvedTyreTempMethod = method;
            if (method == NO_TYRE_TEMP_GETTER) return;
        }

        Object raw;
        try {
            raw = method.invoke(tyreDevice, wheel);
        } catch (Throwable t) {
            if (!loggedTyrePollThrew) {
                loggedTyrePollThrew = true;
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") threw " + cause.getClass().getSimpleName()
                        + ": " + cause.getMessage());
            }
            return; // transient failure; method stays resolved for next cycle
        }
        if (raw == null) {
            if (!loggedTyrePollNullReturn) {
                loggedTyrePollNullReturn = true;
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") returned null — getter exists but firmware has no value");
            }
            return;
        }
        if (!(raw instanceof Number)) {
            if (!loggedTyrePollNonNumber) {
                loggedTyrePollNonNumber = true;
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") returned " + raw.getClass().getSimpleName() + " = " + raw);
            }
            return;
        }
        double v = ((Number) raw).doubleValue();
        if (!(v >= -40.0 && v <= 125.0)) {
            tyreTempOutOfRangeCount++;
            if (tyreTempOutOfRangeCount == 1) {
                // Log on first occurrence
                logger.info("Tyre temp poll: " + method.getName() + "(" + wheel
                        + ") returned " + v + " — outside temperature range, "
                        + "this firmware reports battery voltage via this getter. "
                        + "Will try next candidate after " + TYRE_TEMP_OUT_OF_RANGE_THRESHOLD + " bad reads.");
            }
            if (tyreTempOutOfRangeCount >= TYRE_TEMP_OUT_OF_RANGE_THRESHOLD) {
                // This method consistently returns garbage — advance to next candidate
                tyreTempOutOfRangeCount = 0;
                resolvedTyreTempCandidateIdx++;
                resolvedTyreTempMethod = null; // force re-resolution from next candidate
                logger.info("Tyre temp poll: " + method.getName()
                        + " returned out-of-range " + TYRE_TEMP_OUT_OF_RANGE_THRESHOLD
                        + " times — advancing to next candidate (idx="
                        + resolvedTyreTempCandidateIdx + ")");
            }
            return;
        }
        // Valid reading — reset the out-of-range counter
        tyreTempOutOfRangeCount = 0;

        int tempC = (int) Math.round(v);
        synchronized (tyreTemperatureCache) {
            tyreTemperatureCache[idx] = tempC;
        }
        if (!loggedTyrePollHit[idx]) {
            loggedTyrePollHit[idx] = true;
            logger.info("Tyre temp poll FIRST: wheel=" + wheel
                    + " (" + new String[]{"FL","FR","RL","RR"}[idx] + ") via "
                    + method.getName() + " = " + tempC + "°C");
        }
    }

    private java.lang.reflect.Method resolveTyreTempMethod() {
        Class<?> cls = tyreDevice.getClass();
        for (int i = resolvedTyreTempCandidateIdx; i < TYRE_TEMP_GETTER_CANDIDATES.length; i++) {
            String name = TYRE_TEMP_GETTER_CANDIDATES[i];
            try {
                java.lang.reflect.Method m = cls.getMethod(name, int.class);
                resolvedTyreTempCandidateIdx = i;
                logger.info("Tyre temp poll: using " + name + "(int) on "
                        + cls.getSimpleName() + " (candidate idx=" + i + ")");
                return m;
            } catch (NoSuchMethodException ignored) { /* try next */ }
        }
        logger.info("Tyre temp poll: no getter on this firmware "
                + "(tried " + java.util.Arrays.toString(TYRE_TEMP_GETTER_CANDIDATES)
                + " starting from idx=" + resolvedTyreTempCandidateIdx + ")");
        return NO_TYRE_TEMP_GETTER;
    }

    private int[] snapshotTyreTemperatures() {
        synchronized (tyreTemperatureCache) {
            return new int[]{
                    tyreTemperatureCache[0], tyreTemperatureCache[1],
                    tyreTemperatureCache[2], tyreTemperatureCache[3]
            };
        }
    }

    // Diagnostic: log each unknown tyre feature ID at most once, capped at 32
    // unique IDs total. The cap prevents a chatty HAL (some emit a feature ID
    // every 100ms for trip metrics) from flooding the log if an unknown one
    // happens to slip through the listener filter.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> loggedUnknownTyreIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_UNKNOWN_TYRE_IDS = 32;

    private void logUnknownTyreEventOnce(int eventId, int rawInt, double rawDbl) {
        if (loggedUnknownTyreIds.size() >= MAX_UNKNOWN_TYRE_IDS) return;
        if (loggedUnknownTyreIds.putIfAbsent(eventId, Boolean.TRUE) != null) return;
        logger.info("Tyre event UNKNOWN id=" + eventId
                + " intValue=" + (rawInt == Integer.MIN_VALUE ? "n/a" : Integer.toString(rawInt))
                + " doubleValue=" + (Double.isNaN(rawDbl) ? "n/a" : Double.toString(rawDbl))
                + " — if this looks like a temperature, add it to BydFeatureIds.INSTRUMENT_*_TYRE_TEMPERATURE");
    }

    // Per-wheel out-of-range counters for the known LF/RF/LB/RB feature IDs.
    // Logged once per wheel so a sleeping TPMS sensor doesn't spam.
    private final boolean[] loggedTyreEventOutOfRange = new boolean[]{false, false, false, false};

    private void logTyreEventOutOfRangeOnce(int eventId, int wheelIdx, int rawInt, double rawDbl) {
        if (wheelIdx < 0 || wheelIdx > 3 || loggedTyreEventOutOfRange[wheelIdx]) return;
        loggedTyreEventOutOfRange[wheelIdx] = true;
        logger.info("Tyre event OUT-OF-RANGE: " + new String[]{"FL","FR","RL","RR"}[wheelIdx]
                + " (id=" + eventId + ") intValue="
                + (rawInt == Integer.MIN_VALUE ? "n/a" : Integer.toString(rawInt))
                + " doubleValue=" + (Double.isNaN(rawDbl) ? "n/a" : Double.toString(rawDbl))
                + " — sensor likely asleep; will retry silently on next event.");
    }

    private void onTyreCallback(String method, Object[] args) {
        if (args == null) return;
        try {
            // Generic feature-ID event from the 2-arg listener registration.
            // Per-wheel temperature on this firmware family arrives here keyed
            // on the LF/RF/LB/RB Instrument feature IDs. We accept either
            // intValue (some firmwares emit °C as an integer) or doubleValue
            // (others emit a fractional °C).
            if ("onDataEventChanged".equals(method) && args.length >= 2) {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int idx = -1;
                if (eventId == BydFeatureIds.INSTRUMENT_LF_TYRE_TEMPERATURE) idx = 0;
                else if (eventId == BydFeatureIds.INSTRUMENT_RF_TYRE_TEMPERATURE) idx = 1;
                else if (eventId == BydFeatureIds.INSTRUMENT_LB_TYRE_TEMPERATURE) idx = 2;
                else if (eventId == BydFeatureIds.INSTRUMENT_RB_TYRE_TEMPERATURE) idx = 3;

                if (idx < 0) {
                    // Unknown feature ID — log once per ID so the next log
                    // capture surfaces real per-wheel temperature IDs we
                    // can add to BydFeatureIds.
                    if (eventValue != null) {
                        int rawInt = BydDeviceHelper.getIntValue(eventValue);
                        double rawDbl = BydDeviceHelper.getDoubleValue(eventValue);
                        logUnknownTyreEventOnce(eventId, rawInt, rawDbl);
                    }
                    return;
                }

                // Known wheel — extract value, prefer the int slot.
                int rawInt = BydDeviceHelper.getIntValue(eventValue);
                double rawDbl = BydDeviceHelper.getDoubleValue(eventValue);
                Double tempC = null;
                if (rawInt != Integer.MIN_VALUE && rawInt >= -40 && rawInt <= 125) {
                    tempC = (double) rawInt;
                } else if (!Double.isNaN(rawDbl) && rawDbl >= -40.0 && rawDbl <= 125.0) {
                    tempC = rawDbl;
                }
                if (tempC == null) {
                    // Sentinel — TPMS hasn't reported this wheel yet, or
                    // the value lives in a slot we don't know about.
                    logTyreEventOutOfRangeOnce(eventId, idx, rawInt, rawDbl);
                    return;
                }
                int tempCi = (int) Math.round(tempC);
                synchronized (tyreTemperatureCache) {
                    tyreTemperatureCache[idx] = tempCi;
                }
                if (!loggedTyreFirstEvent[idx]) {
                    loggedTyreFirstEvent[idx] = true;
                    logger.info("Tyre event FIRST: " + new String[]{"FL","FR","RL","RR"}[idx]
                            + " (id=" + eventId + ") = " + tempCi + "°C");
                }
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    snapshot.set(current.toBuilder().tyreTemperature(snapshotTyreTemperatures()).build());
                }
                return;
            }

            if ("onTyreBatteryValueChanged".equals(method) && args.length >= 2) {
                int wheel = ((Number) args[0]).intValue();
                double value = ((Number) args[1]).doubleValue();
                if (wheel == 0) {
                    if (!loggedTyreSlot0) {
                        loggedTyreSlot0 = true;
                        logger.info("Tyre slot 0 raw event observed (value=" + value + ") — ignoring further slot 0");
                    }
                    return;
                }
                if (wheel < 1 || wheel > 4) {
                    logger.info("Tyre battery callback: unexpected wheel=" + wheel + " value=" + value);
                    return;
                }
                int idx = wheel - 1;
                if (!loggedTyreFirstEvent[idx]) {
                    loggedTyreFirstEvent[idx] = true;
                    logger.info("Tyre battery callback FIRST: wheel=" + wheel
                            + " (" + new String[]{"FL","FR","RL","RR"}[idx] + ") value=" + value);
                }
                if (!(value >= -40.0 && value <= 125.0)) {
                    if (!loggedTyreOutOfRange) {
                        loggedTyreOutOfRange = true;
                        logger.info("Tyre battery callback: value " + value
                                + " outside temperature range — likely battery voltage on this firmware");
                    }
                    return;
                }
                int tempC = (int) Math.round(value);
                synchronized (tyreTemperatureCache) {
                    tyreTemperatureCache[idx] = tempC;
                }
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    snapshot.set(current.toBuilder().tyreTemperature(snapshotTyreTemperatures()).build());
                }
                return;
            }

            if ("onTyrePressureValueChanged".equals(method)
                    || "onTyrePressureStateChanged".equals(method)
                    || "onTyreAirLeakStateChanged".equals(method)
                    || "onTyreSignalStateChanged".equals(method)
                    || "onTyreSystemStateChanged".equals(method)
                    || "onTyreTemperatureStateChanged".equals(method)
                    || "onIndirectTyreSystemStateChanged".equals(method)) {
                BydVehicleData current = snapshot.get();
                if (current == null) return;
                BydVehicleData.Builder b = current.toBuilder();
                collectTyre(b);
                snapshot.set(b.build());
            }
        } catch (Exception e) {
            logger.debug("onTyreCallback error (" + method + "): " + e.getMessage());
        }
    }

    private void collectDoorLock(BydVehicleData.Builder b) {
        // The BYDAutoDoorLockDevice service does not expose lock state to
        // user-UID processes on most BYD firmwares — every getDoorLockStatus(area)
        // call returns INVALID(0) and onDoorLockStatusChanged never fires.
        // Field testing confirmed this on Sealion 6 / Atto 3 / others.
        //
        // Lock state is sourced exclusively from the BYD cloud REST/MQTT path
        // via BydCloudDataProvider. The vehicle-control page calls the cloud
        // API directly on load; the lock-gate uses CloudLockStateListener.
        //
        // We still publish a doorLockStatus[] array on the snapshot for
        // compatibility with downstream consumers, but with all-UNAVAILABLE
        // values — the cloud-lock fields on the JSON response carry the
        // authoritative state.
        if (b.doorLockStatus == null) {
            int[] locks = new int[7];
            for (int i = 0; i < 7; i++) locks[i] = -1;
            b.doorLockStatus(locks);
        }
    }

    private void collectSensor(BydVehicleData.Builder b) {
        if (sensorDevice == null) return;
        try {
            Object slope = BydDeviceHelper.callGetter(sensorDevice, "getSlope");
            if (slope instanceof Number) {
                int raw = ((Number) slope).intValue();
                double degrees = Math.toDegrees(Math.atan(raw / 100.0));
                if (degrees >= -60 && degrees <= 60) b.slopeDegrees(degrees);
            }
        } catch (Exception e) {
            logger.debug("collectSensor error: " + e.getMessage());
        }
    }

    private void collectEnergy(BydVehicleData.Builder b) {
        if (energyDevice == null) return;
        try {
            Object mode = BydDeviceHelper.callGetter(energyDevice, "getEnergyMode");
            if (mode instanceof Number) b.energyMode(((Number) mode).intValue());
            Object opMode = BydDeviceHelper.callGetter(energyDevice, "getOperationMode");
            if (opMode instanceof Number) b.operationMode(((Number) opMode).intValue());
            
            // SOC fallback: EnergyDevice.getElecPercentageValue() — try if statistic didn't provide SOC
            if (Double.isNaN(b.socPercent)) {
                Object elecPct = BydDeviceHelper.callGetter(energyDevice, "getElecPercentageValue");
                if (elecPct instanceof Number) {
                    double soc = ((Number) elecPct).doubleValue();
                    if (soc > 0 && soc <= 100) {
                        b.socPercent(soc);
                        logger.debug("SOC from EnergyDevice: " + soc + "%");
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("collectEnergy error: " + e.getMessage());
        }
    }

    private void collectRadar(BydVehicleData.Builder b) {
        if (radarDevice == null) return;
        try {
            Object distances = BydDeviceHelper.callGetter(radarDevice, "getAllRadarDistance");
            if (distances instanceof int[]) b.radarDistances((int[]) distances);
        } catch (Exception e) {
            logger.debug("collectRadar error: " + e.getMessage());
        }
    }

    // ==================== EXTENDED GETTERS ====================

    /**
     * Extended statistic data: OEM SOH, driving time, key battery level.
     * Called from collectAll() (core telemetry consumers need SOH).
     */
    private void collectStatisticExtended(BydVehicleData.Builder b) {
        if (statisticDevice == null) return;

        // SOH: typed getter, then feature ID fallback. Validated 0-100.
        // Once both paths are confirmed unavailable for this firmware, we
        // latch in SohEstimator and skip these reflection calls forever.
        try {
            com.overdrive.app.abrp.SohEstimator sohEst =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();

            if (sohEst != null && sohEst.isOemSohUnavailable()) {
                // Skip — already determined this firmware doesn't have it.
            } else {
                Integer sohValue = null;
                boolean methodMissing = false;
                boolean featureMissing = false;

                try {
                    Object result = BydDeviceHelper.callGetter(statisticDevice, "getStatisticBatteryHealthyIndex");
                    if (result instanceof Integer) {
                        sohValue = (Integer) result;
                    } else if (result instanceof Double) {
                        sohValue = (int) ((Double) result).doubleValue();
                    } else if (result instanceof Float) {
                        sohValue = (int) ((Float) result).floatValue();
                    }
                } catch (NoSuchMethodError nsme) {
                    methodMissing = true;
                } catch (Exception e) {
                    if (e.getCause() instanceof NoSuchMethodError) {
                        methodMissing = true;
                    } else {
                        logger.debug("SOH getter failed: " + e.getMessage());
                    }
                }

                if (sohValue == null || sohValue < 0 || sohValue > 100) {
                    try {
                        Object sohVal = BydDeviceHelper.callGet(statisticDevice, BydFeatureIds.STAT_BATTERY_HEALTHY_INDEX, Integer.class);
                        if (sohVal != null) {
                            int raw = BydDeviceHelper.getIntValue(sohVal);
                            if (raw >= 0 && raw <= 100) {
                                sohValue = raw;
                            } else {
                                featureMissing = true;
                            }
                        } else {
                            featureMissing = true;
                        }
                    } catch (Exception e) {
                        featureMissing = true;
                        logger.debug("SOH feature ID failed: " + e.getMessage());
                    }
                }

                if (sohValue != null && sohValue >= 0 && sohValue <= 100) {
                    b.sohPercent(sohValue);
                    if (sohEst != null) {
                        try {
                            sohEst.updateFromOem(sohValue);
                        } catch (Exception e) {
                            logger.debug("collectStatisticExtended SohEstimator update error: " + e.getMessage());
                        }
                    }
                } else if (methodMissing && featureMissing && sohEst != null) {
                    // Both paths confirmed missing — stop polling on this firmware.
                    sohEst.markOemSohUnavailable();
                }
            }
        } catch (Exception e) {
            logger.debug("collectStatisticExtended SOH error: " + e.getMessage());
        }

        // Driving time
        try {
            Object drivingTime = BydDeviceHelper.callGetter(statisticDevice, "getDrivingTimeValue");
            if (drivingTime instanceof Number) {
                double hours = ((Number) drivingTime).doubleValue();
                if (hours >= 0) b.drivingTimeHours(hours);
            }
        } catch (Exception e) {
            logger.debug("collectStatisticExtended drivingTime error: " + e.getMessage());
        }

        // Key battery level
        try {
            Object keyBatt = BydDeviceHelper.callGetter(statisticDevice, "getKeyBatteryLevel");
            if (keyBatt instanceof Number) {
                b.keyBatteryLevel(((Number) keyBatt).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectStatisticExtended keyBattery error: " + e.getMessage());
        }
    }

    /**
     * Extended instrument data: cabin temp, trip data, consumption.
     * Called from collectAll() (ABRP/MQTT/trips consume these).
     */
    private void collectInstrumentExtended(BydVehicleData.Builder b) {
        // Inside cabin temperature from AC device
        try {
            if (acDevice != null) {
                Object insideTemp = BydDeviceHelper.callGet(acDevice, BydFeatureIds.AC_TEMP_INSIDE, Integer.class);
                if (insideTemp != null) {
                    int raw = BydDeviceHelper.getIntValue(insideTemp);
                    if (raw >= -40 && raw <= 60) b.insideTempCelsius(raw);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended insideTemp error: " + e.getMessage());
        }

        // Per-tyre temperature from InstrumentDevice via feature ID get() calls.
        // Slot mapping from BYDAutoFeatureIds.Instrument:
        //   LF_TYRE_TEMPERATURE, RF_TYRE_TEMPERATURE, LB_TYRE_TEMPERATURE, RB_TYRE_TEMPERATURE
        // These may return null on some firmware but are the correct channel on others.
        try {
            if (instrumentDevice != null) {
                int[] featureIds = BydFeatureIds.INSTRUMENT_TYRE_TEMP_IDS;
                // Order: LF=0, RF=1, LB(RL)=2, RB(RR)=3
                int[] tempResults = new int[4];
                boolean anyValid = false;
                for (int i = 0; i < featureIds.length; i++) {
                    Object result = BydDeviceHelper.callGet(instrumentDevice, featureIds[i], Integer.class);
                    if (result != null) {
                        int raw = BydDeviceHelper.getIntValue(result);
                        if (raw >= -40 && raw <= 125) {
                            tempResults[i] = raw;
                            anyValid = true;
                        } else {
                            tempResults[i] = Integer.MIN_VALUE;
                        }
                    } else {
                        tempResults[i] = Integer.MIN_VALUE;
                    }
                }
                if (anyValid) {
                    // Map: index 0=LF, 1=RF, 2=LB(RL), 3=RB(RR)
                    synchronized (tyreTemperatureCache) {
                        if (tempResults[0] != Integer.MIN_VALUE) tyreTemperatureCache[0] = tempResults[0];
                        if (tempResults[1] != Integer.MIN_VALUE) tyreTemperatureCache[1] = tempResults[1];
                        if (tempResults[2] != Integer.MIN_VALUE) tyreTemperatureCache[2] = tempResults[2];
                        if (tempResults[3] != Integer.MIN_VALUE) tyreTemperatureCache[3] = tempResults[3];
                    }
                    b.tyreTemperature(snapshotTyreTemperatures());
                    if (!loggedInstrumentTyreTemp) {
                        loggedInstrumentTyreTemp = true;
                        logger.info("Tyre temp from InstrumentDevice feature IDs: LF=" + tempResults[0]
                            + " RF=" + tempResults[1] + " RL=" + tempResults[2] + " RR=" + tempResults[3] + "°C");
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tyreTemp error: " + e.getMessage());
        }

        // Current trip mileage
        try {
            if (instrumentDevice != null) {
                Object tripMileage = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE, Double.class);
                if (tripMileage != null) {
                    double val = BydDeviceHelper.getDoubleValue(tripMileage);
                    if (!Double.isNaN(val) && val >= 0) b.currentTripMileageKm(val * distanceToKmFactor);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tripMileage error: " + e.getMessage());
        }

        // Current trip time
        try {
            if (instrumentDevice != null) {
                Object tripTime = BydDeviceHelper.callGet(instrumentDevice,
                        BydFeatureIds.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_TIME, Double.class);
                if (tripTime != null) {
                    double val = BydDeviceHelper.getDoubleValue(tripTime);
                    if (!Double.isNaN(val) && val >= 0) b.currentTripTimeHours(val);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tripTime error: " + e.getMessage());
        }

        // This trip electricity consumption from statistic device
        try {
            if (statisticDevice != null) {
                Object tripElec = BydDeviceHelper.callGet(statisticDevice,
                        BydFeatureIds.STAT_THIS_TRIP_ELEC_CONSUMPTION, Double.class);
                if (tripElec != null) {
                    double val = BydDeviceHelper.getDoubleValue(tripElec);
                    if (!Double.isNaN(val) && val >= 0) b.currentTripConsumptionKwh(val);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended tripElecConsumption error: " + e.getMessage());
        }

        // Last 50km power consumption
        try {
            if (instrumentDevice != null) {
                Object last50km = BydDeviceHelper.callGetter(instrumentDevice, "getLast50KmPowerConsume");
                if (last50km instanceof Number) {
                    double val = ((Number) last50km).doubleValue();
                    if (val >= 0) b.last50KmConsumption(val);
                }
            }
        } catch (Exception e) {
            logger.debug("collectInstrumentExtended last50km error: " + e.getMessage());
        }
    }

    // ==================== KEY PROXIMITY ====================
    // Discrete key/fob proximity & authentication state probed from SettingDevice and
    // InstrumentDevice. Methods are read reflectively so missing ones (model variation)
    // don't break collection. Each value is the raw int from the SDK; UNAVAILABLE means
    // the method returned a sentinel (BMS unavailable / invalid) or wasn't present.
    //
    // Logged on every state transition and at most once per 5 minutes regardless,
    // so the log captures fob behaviour during parked / charging / approach windows.

    private volatile int lastKeyStartState = Integer.MIN_VALUE;
    private volatile int lastKeyMissingInd = Integer.MIN_VALUE;
    private volatile int lastKeyBtLowPowerMode = Integer.MIN_VALUE;
    private volatile int lastKeyPowerLowInd = Integer.MIN_VALUE;
    private volatile int lastKeyDetectionReminder = Integer.MIN_VALUE;
    private volatile int lastSmartKeyWarnState = Integer.MIN_VALUE;
    private volatile long lastKeyProbeLogMs = 0;

    private void collectKeyProximity(BydVehicleData.Builder b) {
        int startState = readKeyInt(settingDevice, "getStartKeyState");
        int missingInd = readKeyInt(settingDevice, "getMissKeyInd");
        int btLowPower = readKeyInt(settingDevice, "getIKEYBTLowPowerMode");
        int powerLow = readKeyInt(settingDevice, "getKeyPowerLowInd");
        int detectionRem = readKeyInt(instrumentDevice, "getKeyDetectionReminder");
        int warnState = readKeyInt(instrumentDevice, "getSmartKeySysWarnLightState");

        if (startState != BydVehicleData.UNAVAILABLE) b.keyStartState(startState);
        if (missingInd != BydVehicleData.UNAVAILABLE) b.keyMissingInd(missingInd);
        if (btLowPower != BydVehicleData.UNAVAILABLE) b.keyBtLowPowerMode(btLowPower);
        if (powerLow != BydVehicleData.UNAVAILABLE) b.keyPowerLowInd(powerLow);
        if (detectionRem != BydVehicleData.UNAVAILABLE) b.keyDetectionReminder(detectionRem);
        if (warnState != BydVehicleData.UNAVAILABLE) b.smartKeyWarnState(warnState);

        boolean changed =
            startState != lastKeyStartState
            || missingInd != lastKeyMissingInd
            || btLowPower != lastKeyBtLowPowerMode
            || powerLow != lastKeyPowerLowInd
            || detectionRem != lastKeyDetectionReminder
            || warnState != lastSmartKeyWarnState;

        long now = System.currentTimeMillis();
        boolean heartbeat = now - lastKeyProbeLogMs > 300_000;

        if (changed || heartbeat) {
            lastKeyStartState = startState;
            lastKeyMissingInd = missingInd;
            lastKeyBtLowPowerMode = btLowPower;
            lastKeyPowerLowInd = powerLow;
            lastKeyDetectionReminder = detectionRem;
            lastSmartKeyWarnState = warnState;
            lastKeyProbeLogMs = now;
            logger.info("KeyProbe: startState=" + fmtKeyVal(startState)
                + " missingInd=" + fmtKeyVal(missingInd)
                + " btLowPower=" + fmtKeyVal(btLowPower)
                + " powerLow=" + fmtKeyVal(powerLow)
                + " detectionReminder=" + fmtKeyVal(detectionRem)
                + " smartKeyWarn=" + fmtKeyVal(warnState)
                + " accIsOn=" + accIsOn
                + (changed ? " [CHANGE]" : " [hb]"));
        }
    }

    /**
     * Reflective single-int getter that filters BYD sentinel values
     * (BMS_UNAVAILABLE=65535, INVALID_VALUE=-10011, INVALID_VALUE_2=-10013).
     * Returns BydVehicleData.UNAVAILABLE if the method is missing, the device is
     * null, or the value is a known sentinel.
     */
    private static int readKeyInt(Object device, String methodName) {
        if (device == null) return BydVehicleData.UNAVAILABLE;
        try {
            java.lang.reflect.Method m = device.getClass().getMethod(methodName);
            Object result = m.invoke(device);
            if (!(result instanceof Number)) return BydVehicleData.UNAVAILABLE;
            int v = ((Number) result).intValue();
            if (v == 65535 || v == -10011 || v == -10013) return BydVehicleData.UNAVAILABLE;
            return v;
        } catch (NoSuchMethodException e) {
            return BydVehicleData.UNAVAILABLE;
        } catch (Exception e) {
            return BydVehicleData.UNAVAILABLE;
        }
    }

    private static String fmtKeyVal(int v) {
        return v == BydVehicleData.UNAVAILABLE ? "n/a" : Integer.toString(v);
    }

    /**
     * Extended charging data: charging rest time.
     * Called from collectAllFull() only (display-only, on-demand).
     */
    private void collectChargingExtended(BydVehicleData.Builder b) {
        if (chargingDevice == null) return;

        // Fallback: chargingDevice.getChargingRestTime() when instrument feature IDs
        // didn't populate in collectInstrument(). Checks gun state first — if NONE, skip.
        if (b.chargingRestTimeHours == BydVehicleData.UNAVAILABLE) {
            try {
                if (b.chargingGunState != 1) {
                    Object restTime = BydDeviceHelper.callGetter(chargingDevice, "getChargingRestTime");
                    if (restTime instanceof int[]) {
                        int[] times = (int[]) restTime;
                        if (times.length >= 2) {
                            int hours = times[0];
                            int minutes = times[1];
                            if (hours != 255 && minutes != 255 && hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 59) {
                                b.chargingRestTimeHours(hours);
                                b.chargingRestTimeMinutes(minutes);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("collectChargingExtended restTime error: " + e.getMessage());
            }
        }
    }

    /**
     * Extended bodywork data: steering angle, auto system state, 12V level, sunroof, sunshade.
     * Called from collectAllFull() only (display-only, on-demand).
     */
    private void collectBodyworkExtended(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;

        // Steering wheel angle
        try {
            Object steering = BydDeviceHelper.callGetter(bodyworkDevice, "getSteeringWheelValue", 1);
            if (steering instanceof Number) {
                double angle = ((Number) steering).doubleValue();
                b.steeringAngleDegrees(angle);
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended steering error: " + e.getMessage());
        }

        // Auto system state (0=normal, 1=set_secure, 2=start_secure)
        try {
            Object autoState = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoSystemState");
            if (autoState instanceof Number) {
                b.autoSystemState(((Number) autoState).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended autoSystemState error: " + e.getMessage());
        }

        // 12V battery voltage level (LOW/NORMAL/INVALID)
        try {
            Object battLevel = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryVoltageLevel");
            if (battLevel instanceof Number) {
                b.battery12vLevel(((Number) battLevel).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended battery12vLevel error: " + e.getMessage());
        }

        // Sunroof state (if available)
        try {
            Object sunroof = BydDeviceHelper.callGetter(bodyworkDevice, "getSunroofState");
            if (sunroof instanceof Number) {
                b.sunroofState(((Number) sunroof).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended sunroofState error: " + e.getMessage());
        }

        // Sunroof position (if available)
        try {
            Object sunroofPos = BydDeviceHelper.callGetter(bodyworkDevice, "getSunroofPosition");
            if (sunroofPos instanceof Number) {
                b.sunroofPosition(((Number) sunroofPos).intValue());
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended sunroofPosition error: " + e.getMessage());
        }

        // Sunshade panel percent
        try {
            Object sunshade = BydDeviceHelper.callGet(bodyworkDevice, BydFeatureIds.BODY_SUNSHADE_PANEL_PERCENT, Integer.class);
            if (sunshade != null) {
                int val = BydDeviceHelper.getIntValue(sunshade);
                if (val >= 0 && val <= 100) b.sunshadePercent(val);
            }
        } catch (Exception e) {
            logger.debug("collectBodyworkExtended sunshade error: " + e.getMessage());
        }
    }

    /**
     * Extended engine data: coolant level, oil level, engine code.
     * Called from collectAllFull() only (display-only, on-demand).
     */
    private void collectEngineExtended(BydVehicleData.Builder b) {
        if (engineDevice == null) return;

        // Engine coolant level. BYD SDK constants: 0=NORMAL, 1=LOW.
        // Some firmwares return -1 or sentinel when the value is unavailable.
        Integer coolantRaw = null;
        try {
            Object coolant = BydDeviceHelper.callGetter(engineDevice, "getEngineCoolantLevel");
            if (coolant instanceof Number) {
                coolantRaw = ((Number) coolant).intValue();
                b.engineCoolantLevel(coolantRaw);
            }
        } catch (Exception e) {
            logger.debug("collectEngineExtended coolant error: " + e.getMessage());
        }

        // Oil level from Engine device. SDK range 0-254 (dipstick scale).
        // 0 may be a "no value" sentinel rather than empty tank — needs
        // verification against the cluster's own oil-level UI.
        Integer engineOilRaw = null;
        try {
            Object oil = BydDeviceHelper.callGetter(engineDevice, "getOilLevel");
            if (oil instanceof Number) {
                engineOilRaw = ((Number) oil).intValue();
                b.oilLevel(engineOilRaw);
            }
        } catch (Exception e) {
            logger.debug("collectEngineExtended oilLevel error: " + e.getMessage());
        }

        // Parallel reading from the Setting device (different code path).
        // Setting.getEngineOilLevel exists on most BYD firmwares — pulling it
        // alongside the Engine device version lets us cross-check which one
        // is actually populated on this car. Logged for diagnostics only;
        // not surfaced on the snapshot until we know which is canonical.
        Integer settingOilRaw = null;
        try {
            if (settingDevice != null) {
                Object oil = BydDeviceHelper.callGetter(settingDevice, "getEngineOilLevel");
                if (oil instanceof Number) settingOilRaw = ((Number) oil).intValue();
            }
        } catch (Exception ignored) {}

        // "Low oil indicator" lamp from Setting device — when this is set, the
        // dashboard is already showing the warning. Useful as a sanity check.
        Integer lowOilIndRaw = null;
        try {
            if (settingDevice != null) {
                Object ind = BydDeviceHelper.callGetter(settingDevice, "getLowOilInd");
                if (ind instanceof Number) lowOilIndRaw = ((Number) ind).intValue();
            }
        } catch (Exception ignored) {}

        logEngineFluidsIfChanged(coolantRaw, engineOilRaw, settingOilRaw, lowOilIndRaw);

        // Engine code (e.g. "BYD473QF")
        try {
            Object code = BydDeviceHelper.callGetter(engineDevice, "getEngineCode");
            if (code instanceof String) {
                b.engineCode((String) code);
            } else if (code != null) {
                String codeStr = BydDeviceHelper.getStringValue(code);
                if (codeStr != null && !codeStr.isEmpty()) b.engineCode(codeStr);
            }
        } catch (Exception e) {
            logger.debug("collectEngineExtended engineCode error: " + e.getMessage());
        }
    }

    // Last-seen engine-fluid readings — change-only logging plus a 5-min
    // heartbeat so a healthy car doesn't spam the log but transitions
    // (e.g. coolant drops to LOW after a leak develops) surface immediately.
    private volatile Integer lastCoolantRaw = null;
    private volatile Integer lastEngineOilRaw = null;
    private volatile Integer lastSettingOilRaw = null;
    private volatile Integer lastLowOilIndRaw = null;
    private volatile long lastEngineFluidsLogMs = 0;
    private volatile boolean firstEngineFluidsLog = true;

    private void logEngineFluidsIfChanged(Integer coolantRaw, Integer engineOilRaw,
                                          Integer settingOilRaw, Integer lowOilIndRaw) {
        boolean changed = firstEngineFluidsLog
                || !java.util.Objects.equals(coolantRaw, lastCoolantRaw)
                || !java.util.Objects.equals(engineOilRaw, lastEngineOilRaw)
                || !java.util.Objects.equals(settingOilRaw, lastSettingOilRaw)
                || !java.util.Objects.equals(lowOilIndRaw, lastLowOilIndRaw);
        long now = System.currentTimeMillis();
        boolean heartbeat = now - lastEngineFluidsLogMs > 300_000;
        if (!changed && !heartbeat) return;
        firstEngineFluidsLog = false;
        lastCoolantRaw = coolantRaw;
        lastEngineOilRaw = engineOilRaw;
        lastSettingOilRaw = settingOilRaw;
        lastLowOilIndRaw = lowOilIndRaw;
        lastEngineFluidsLogMs = now;
        logger.info("EngineFluids: coolant=" + fmtFluid(coolantRaw)
                + " (0=NORMAL,1=LOW)"
                + " engineOil=" + fmtFluid(engineOilRaw) + " (0-254)"
                + " settingOil=" + fmtFluid(settingOilRaw)
                + " lowOilInd=" + fmtFluid(lowOilIndRaw)
                + (changed ? " [CHANGE]" : " [hb]"));
    }

    private static String fmtFluid(Integer v) {
        return v == null ? "n/a" : v.toString();
    }

    // ==================== CLOUD DATA MERGE ====================

    /**
     * Merge cloud data as FALLBACK — only fills fields where SDK returned no value.
     * SDK is always primary (real-time 5s poll). Cloud fills gaps only.
     */
    private void mergeCloudData(BydVehicleData.Builder b) {
        try {
            com.overdrive.app.byd.cloud.BydCloudConfig config =
                    com.overdrive.app.byd.cloud.BydCloudConfig.fromUnifiedConfig();
            if (!config.cloudDataMerge) return;

            com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            if (!provider.isTelemetryFresh()) return;

            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
            if (cs == null) return;

            // SOC — only if SDK didn't provide it
            if (Double.isNaN(b.socPercent) && cs.hasSoc()) b.socPercent(cs.socPercent);

            // EV range — only if SDK returned UNAVAILABLE
            if (b.elecRangeKm == BydVehicleData.UNAVAILABLE && cs.hasElecRange()) b.elecRangeKm(cs.elecRangeKm);

            // Fuel range / percent (PHEV) — only if SDK has nothing
            if (b.fuelRangeKm == BydVehicleData.UNAVAILABLE && cs.hasFuelRange()) b.fuelRangeKm(cs.fuelRangeKm);
            if (Double.isNaN(b.fuelPercent) && cs.hasFuelPercent()) b.fuelPercent(cs.fuelPercent);

            // Charging state — only if SDK returned UNAVAILABLE
            if (b.chargingState == BydVehicleData.UNAVAILABLE && cs.hasChargingState()) {
                int sdkState = cs.getChargingStateAsSdk();
                if (sdkState >= 0) b.chargingState(sdkState);
            }

            // Charge ETA — only if SDK has nothing
            if (b.chargingRestTimeHours == BydVehicleData.UNAVAILABLE && cs.hasRemainingHours())
                b.chargingRestTimeHours(cs.remainingHours);
            if (b.chargingRestTimeMinutes == BydVehicleData.UNAVAILABLE && cs.hasRemainingMinutes())
                b.chargingRestTimeMinutes(cs.remainingMinutes);

            // Temperatures — only if SDK returned NaN
            if (Double.isNaN(b.insideTempC) && cs.hasInsideTemp()) b.insideTempC(cs.insideTempC);
            if (Double.isNaN(b.outsideTempC) && cs.hasOutsideTemp()) b.outsideTempC(cs.outsideTempC);

            // Odometer — only if SDK returned UNAVAILABLE
            if (b.totalMileageKm == BydVehicleData.UNAVAILABLE && cs.hasTotalMileage())
                b.totalMileageKm(cs.totalMileageKm);

            // Air quality — only if SDK returned UNAVAILABLE
            if (b.pm25Inside == BydVehicleData.UNAVAILABLE && cs.hasPm25Inside())
                b.pm25Inside((int) cs.pm25Inside);
            if (b.pm25Outside == BydVehicleData.UNAVAILABLE && cs.hasPm25Outside())
                b.pm25Outside((int) cs.pm25Outside);

        } catch (Exception e) {
            logger.debug("Cloud data merge error: " + e.getMessage());
        }
    }

    // ==================== LISTENER REGISTRATION ====================

    private void registerAllListeners() {
        logger.info("Registering listeners...");
        int count = 0;

        // Bodywork: use the typed listener so onDoorStateChanged /
        // onWindowStateChanged / onWindowOpenPercentChanged actually dispatch.
        // The generic IBYDAutoListener registration succeeds but never fires
        // those device-specific callbacks.
        if (BydDeviceHelper.registerBodyworkListener(bodyworkDevice, this::onBodyworkCallback)) {
            logger.info("  Bodywork listener registered (typed)");
            count++;
        } else if (BydDeviceHelper.registerListener(bodyworkDevice, this::onBodyworkCallback)) {
            // Fallback for stub/older firmwares that only expose the generic interface.
            logger.info("  Bodywork listener registered (generic fallback — door/window callbacks may not fire)");
            count++;
        }
        if (BydDeviceHelper.registerListener(speedDevice, this::onGenericCallback)) {
            logger.info("  Speed listener registered");
            count++;
        }
        // SKIP gearbox listener — BYDAutoGearboxDevice.learningEPB() crashes with
        // "Given calling package android does not match caller's uid 2000" when running
        // as shell (UID 2000). The crash kills the BYD device manager's HandlerThread,
        // which cascades into GL thread hang → watchdog kill → daemon restart loop.
        // Gear data is collected via polling (collectAll) and GearMonitor handles gear changes.
        // if (BydDeviceHelper.registerListener(gearboxDevice, this::onGenericCallback)) {
        //     logger.info("  Gearbox listener registered");
        //     count++;
        // }
        if (BydDeviceHelper.registerListener(chargingDevice, this::onChargingCallback)) {
            logger.info("  Charging listener registered");
            count++;
        }
        // Engine listener: typed for onEngineCoolantLevelChanged /
        // onOilLevelChanged. Without this, engine fluid status is only
        // refreshed by the one-shot collectAllFull at init.
        if (BydDeviceHelper.registerEngineListener(engineDevice, this::onEngineCallback)) {
            logger.info("  Engine listener registered (typed)");
            count++;
        } else if (BydDeviceHelper.registerListener(engineDevice, this::onEngineCallback)) {
            logger.info("  Engine listener registered (generic fallback)");
            count++;
        }
        if (BydDeviceHelper.registerListener(instrumentDevice, this::onInstrumentCallback)) {
            logger.info("  Instrument listener registered (external charging power)");
            count++;
        }
        if (BydDeviceHelper.registerListener(statisticDevice, this::onGenericCallback)) {
            logger.info("  Statistic listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(lightDevice, this::onLightsCallback)) {
            logger.info("  Light listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(adasDevice, this::onAdasCallback)) {
            logger.info("  Adas listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(radarDevice, this::onGenericCallback)) {
            logger.info("  Radar listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(otaDevice, this::onOtaCallback)) {
            logger.info("  OTA listener registered");
            count++;
        }

        // Display-only devices — no periodic polling, listener-driven only.
        // These update the snapshot when BYD HAL pushes CAN bus state changes.
        //
        // DoorLock requires the typed AbsBYDAutoDoorLockListener — the generic
        // IBYDAutoListener registration succeeds but never receives
        // onDoorLockStatusChanged. This was the root cause of stale lock data.
        if (BydDeviceHelper.registerDoorLockListener(doorLockDevice, this::onDoorLockCallback)) {
            logger.info("  DoorLock listener registered (typed)");
            count++;
        } else if (BydDeviceHelper.registerListener(doorLockDevice, this::onDoorLockCallback)) {
            logger.info("  DoorLock listener registered (generic fallback — lock callbacks may not fire)");
            count++;
        }
        if (BydDeviceHelper.registerTyreListener(tyreDevice, this::onTyreCallback)) {
            logger.info("  Tyre listener registered (typed)");
            count++;
        } else if (BydDeviceHelper.registerListener(tyreDevice, this::onDisplayCallback)) {
            logger.info("  Tyre listener registered (generic fallback)");
            count++;
        }
        if (BydDeviceHelper.registerListener(acDevice, this::onDisplayCallback)) {
            logger.info("  AC listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(sensorDevice, this::onDisplayCallback)) {
            logger.info("  Sensor listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(energyDevice, this::onDisplayCallback)) {
            logger.info("  Energy listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(powerDevice, this::onDisplayCallback)) {
            logger.info("  Power listener registered");
            count++;
        }

        logger.info("Listeners registered: " + count);
    }

    private void onBodyworkCallback(String method, Object[] args) {
        BydVehicleData current = snapshot.get();
        if (current == null) return;
        BydVehicleData.Builder b = current.toBuilder();
        // Bodywork events also affect window/door-open state (separate from
        // lock state) and trunk position. Refresh both the bodywork view and
        // the lock view — door open/close on the bodywork bus is often the
        // first signal of an upcoming lock event, and refreshing locks here
        // means consumers see consistent state regardless of which side fires.
        collectBodywork(b);
        collectDoorLock(b);
        BydVehicleData updated = b.build();
        snapshot.set(updated);

        // If a typed onDoorStateChanged event arrived, fan it out specifically
        // so consumers that want raw door-open events (not lock state) can
        // subscribe without polling the snapshot.
        if ("onDoorStateChanged".equals(method) && args != null && args.length >= 2) {
            int area = (args[0] instanceof Integer) ? (Integer) args[0] : -1;
            int state = (args[1] instanceof Integer) ? (Integer) args[1] : -1;
            notifyDoorStateListeners(area, state);
        }
        notifyLockSnapshotListeners(updated);
    }

    /**
     * Callback for DoorLock device — re-reads lock status on CAN bus state change.
     * Unlike other display-only devices, door lock state is critical for the
     * vehicle control page and must be updated immediately when the HAL reports
     * a change.
     *
     * The typed AbsBYDAutoDoorLockListener delivers onDoorLockStatusChanged(area,state)
     * with raw SDK semantics (UNLOCK=1, LOCK=2). We refresh the snapshot (which
     * uses inverted API contract for backwards compat) and forward the raw
     * SDK-semantic event to door-lock listeners.
     */
    private void onDoorLockCallback(String method, Object[] args) {
        BydVehicleData current = snapshot.get();
        if (current == null) return;
        BydVehicleData.Builder b = current.toBuilder();
        collectDoorLock(b);
        BydVehicleData updated = b.build();
        snapshot.set(updated);

        if ("onDoorLockStatusChanged".equals(method) && args != null && args.length >= 2) {
            int area = (args[0] instanceof Integer) ? (Integer) args[0] : -1;
            int sdkState = (args[1] instanceof Integer) ? (Integer) args[1] : -1;
            notifyDoorLockListeners(area, sdkState);
        }
        notifyLockSnapshotListeners(updated);
    }

    /**
     * Callback for display-only devices (Tyre, AC, Sensor, Energy, Power).
     * 
     * These listeners exist solely to keep the BYD device singletons' internal caches
     * fresh. We do NOT re-poll devices here — the snapshot is updated on-demand when
     * the HTTP API calls collectAllFull(), or when the bodywork listener fires.
     * 
     * This avoids the 10Hz SensorDevice postEvent from triggering expensive
     * full display sweeps (tyre×4, seatbelt×5, AC×5, light×8, radar, etc.)
     */
    private void onDisplayCallback(String method, Object[] args) {
        // No-op: listener registration keeps BYD HAL singletons' caches alive.
        // Actual data is read on-demand via collectAllFull().
    }

    // Throttle for generic listener callbacks (StatisticDevice fires at ~10Hz on CAN bus)
    private volatile long lastGenericCallbackTime = 0;

    private void onGenericCallback(String method, Object[] args) {
        // Typed callbacks for real-time updates
        if ("onElecPercentageChanged".equals(method) && args != null && args.length > 0) {
            try {
                double soc = ((Number) args[0]).doubleValue();
                if (soc >= 0 && soc <= 100) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().socPercent(soc).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onFuelPercentageChanged".equals(method) && args != null && args.length > 0) {
            try {
                int fuel = ((Number) args[0]).intValue();
                if (fuel > 0 && fuel <= 100) {
                    BydVehicleData current = snapshot.get();
                    if (current != null && isPhev(current)) {
                        snapshot.set(current.toBuilder().fuelPercent(fuel).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onSpeedChanged".equals(method) && args != null && args.length > 0) {
            try {
                double speed = ((Number) args[0]).doubleValue();
                if (speed != BydFeatureIds.SDK_NOT_AVAILABLE) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().speedKmh(speed * distanceToKmFactor).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onEngineSpeedChanged".equals(method) && args != null && args.length > 0) {
            try {
                int rpm = ((Number) args[0]).intValue();
                if (rpm >= 0 && rpm <= 8000) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().engineSpeedRpm(rpm).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onBatteryPowerVoltageChanged".equals(method) && args != null && args.length > 0) {
            try {
                double voltage = ((Number) args[0]).doubleValue();
                if (voltage > 0 && voltage < 20) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().voltage12v(voltage).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        if ("onChargingGunStateChanged".equals(method) && args != null && args.length > 0) {
            try {
                int gunState = ((Number) args[0]).intValue();
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    snapshot.set(current.toBuilder().chargingGunState(gunState).build());
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }

        // Capture HV pack voltage from statistic device event.
        // BYD CAN bus fires StatisticDevice events at ~10Hz — throttle to 1Hz max.
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            long now = System.currentTimeMillis();
            if (now - lastGenericCallbackTime < 1000) return;
            lastGenericCallbackTime = now;

            try {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int iVal = BydDeviceHelper.getIntValue(eventValue);
                
                // Event 1151336480: HV pack voltage in decivolts (e.g., 4955 = 495.5V)
                if (eventId == 1151336480 && iVal > 2000 && iVal < 9000) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        double volts = iVal / 10.0;
                        boolean isFirst = Double.isNaN(current.hvPackVoltage);
                        if (isFirst || Math.abs(current.hvPackVoltage - volts) > 0.5) {
                            snapshot.set(current.toBuilder().hvPackVoltage(volts).build());
                            
                            if (isFirst) {
                                logger.info("HV pack voltage: " + String.format("%.1f", volts) + "V");
                                try {
                                    com.overdrive.app.abrp.SohEstimator soh = 
                                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                                    if (soh != null) {
                                        soh.autoDetectFromPackVoltage(volts, current);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Charging device callback — captures onChargingPowerChanged directly.
     * On many BYD models, getChargingPower() returns 0 but the callback delivers
     * the real value. We store it in the snapshot for VehicleDataMonitor to pick up.
     */
    // Throttle charging power log to once per 30 seconds
    private volatile long lastChargingPowerLogTime = 0;
    private volatile long lastEnergyTypeLogMs = 0;
    private volatile long lastChargingModeLogMs = 0;
    private volatile long lastChargingStateRawLogMs = 0;
    // One-shot: log the raw vs scaled getExternalChargingPower value the first
    // time we successfully publish a value, so the next field log capture can
    // confirm the hectowatt vs kW scaling against the cluster's own readout.
    private volatile boolean loggedExtChargePowerScale = false;

    private void onChargingCallback(String method, Object[] args) {
        // Typed callbacks for real-time charging updates
        if ("onChargingGunStateChanged".equals(method) && args != null && args.length > 0) {
            try {
                int gunState = ((Number) args[0]).intValue();
                BydVehicleData current = snapshot.get();
                if (current != null) {
                    snapshot.set(current.toBuilder().chargingGunState(gunState).build());
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        // Real-time BMS state change — critical for detecting AC charging start/stop promptly
        if ("onBatteryManagementDeviceStateChanged".equals(method) && args != null && args.length > 0) {
            try {
                int state = ((Number) args[0]).intValue();
                if (state >= 0 && state <= 15) {
                    BydVehicleData current = snapshot.get();
                    if (current != null && current.chargingState != state) {
                        int previous = current.chargingState;
                        snapshot.set(current.toBuilder().chargingState(state).build());
                        logger.info("BMS state changed: " + state + " (" +
                                (state == 0 ? "READY" : state == 1 ? "CHARGING" : state == 2 ? "FINISHED" :
                                 state == 3 ? "DISCHARGING" : state == 15 ? "IDLE" : "OTHER") + ")");
                        notifyChargingStateListeners(previous, state);
                    }
                }
            } catch (Exception e) { /* ignore */ }
            return;
        }
        // Handle the new-style BYDAutoEvent callbacks from ChargingDevice.
        // IMPORTANT: Do NOT blindly interpret onDataEventChanged values as charging power.
        // The ChargingDevice fires events for many different metrics (voltage, current,
        // capacity, temperature, etc.) and we cannot reliably distinguish power from other
        // values without knowing the specific event ID mapping.
        // The commander app does NOT use onDataEventChanged for power — it only uses
        // onExternalChargingPowerChanged from InstrumentDevice (see onInstrumentCallback).
        // We skip this path entirely to avoid misinterpreting non-power values as kW.
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            // Intentionally not processing — see comment above.
            // Power comes from onExternalChargingPowerChanged (InstrumentDevice) or
            // onChargingPowerChanged (typed callback below).
            return;
        }
        if ("onChargingPowerChanged".equals(method) && args != null && args.length > 0) {
            try {
                double power = ((Number) args[0]).doubleValue();
                // Listener callback delivers kW directly. SDK docs: range -500 to 500 kW.
                if (Math.abs(power) > 0.1 && Math.abs(power) < 500) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().chargingPowerKw(power).build());
                        long now = System.currentTimeMillis();
                        if (now - lastChargingPowerLogTime > 30_000) {
                            lastChargingPowerLogTime = now;
                            logger.info("Charging power via callback: " + String.format("%.1f", power) + " kW");
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        // Listener-driven: the specific event value was already captured above.
        // Skip full device re-collection — the 5s polling timer handles periodic refresh.
    }

    private void onOtaCallback(String method, Object[] args) {
        if ("onBatteryPowerVoltageChanged".equals(method) && args != null && args.length > 0) {
            try {
                double voltage = ((Number) args[0]).doubleValue();
                if (voltage > 0 && voltage < 20) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().voltage12v(voltage).build());
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
    }

    private void onInstrumentCallback(String method, Object[] args) {
        // Handle the new-style BYDAutoEvent callbacks
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            // NOTE: Do NOT blindly interpret all instrument events as charging power.
            // The instrument device fires events for trip odometer, nav data,
            // and dozens of other metrics. Only the typed onExternalChargingPowerChanged
            // callback (below) reliably delivers charging power.
            // Previously, events like INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE
            // (event 1246801948, value=18.7 km) were misinterpreted as 18.7 kW charging.
        }
        if ("onExternalChargingPowerChanged".equals(method) && args != null && args.length > 0) {
            try {
                double power = ((Number) args[0]).doubleValue();
                // Listener callback delivers kW directly (SDK converts from CAN bus internally).
                if (power > 0.1 && power <= 500) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().externalChargingPowerKw(power).build());
                        long now = System.currentTimeMillis();
                        if (now - lastChargingPowerLogTime > 30_000) {
                            lastChargingPowerLogTime = now;
                            logger.info("External charging power: " + String.format("%.1f", power) + " kW");
                        }
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        // Listener-driven: the specific event value was already captured above.
        // Skip full device re-collection — the 5s polling timer handles periodic refresh.
    }

    private void onLightsCallback(String method, Object[] args) {
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int iVal = BydDeviceHelper.getIntValue(eventValue);

                if (eventId == BydFeatureIds.LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE && iVal > 0 && iVal < 3) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().dayTimeLight(iVal == 1).build());
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void onAdasCallback(String method, Object[] args) {
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int iVal = BydDeviceHelper.getIntValue(eventValue);

                if (eventId == BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE && iVal > 0 && iVal < 3) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().speedLimitWarning(iVal == 2).build());
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // ==================== EXTENDED LISTENER HANDLERS ====================
    // These handler methods exist for future use. To activate, add a registerListener() call
    // in registerAllListeners() or registerBodyworkExtendedListeners() etc.

    private void handleSteeringAngleChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                double angle = BydDeviceHelper.getDoubleValue(args[0]);
                if (!Double.isNaN(angle) && angle >= -780 && angle <= 780) {
                    snapshot.set(snapshot.get().toBuilder().steeringAngleDegrees(angle).build());
                }
            } catch (Exception e) { logger.debug("handleSteeringAngleChanged error: " + e.getMessage()); }
        }
    }

    private void handleAutoSystemStateChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int state = BydDeviceHelper.getIntValue(args[0]);
                if (state >= 0 && state <= 2) {
                    snapshot.set(snapshot.get().toBuilder().autoSystemState(state).build());
                }
            } catch (Exception e) { logger.debug("handleAutoSystemStateChanged error: " + e.getMessage()); }
        }
    }

    private void handleSunroofStateChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int state = BydDeviceHelper.getIntValue(args[0]);
                if (state >= 0 && state <= 255) {
                    snapshot.set(snapshot.get().toBuilder().sunroofState(state).build());
                }
            } catch (Exception e) { logger.debug("handleSunroofStateChanged error: " + e.getMessage()); }
        }
    }

    private void handleSunroofPositionChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int position = BydDeviceHelper.getIntValue(args[0]);
                if (position >= 0 && position <= 100) {
                    snapshot.set(snapshot.get().toBuilder().sunroofPosition(position).build());
                }
            } catch (Exception e) { logger.debug("handleSunroofPositionChanged error: " + e.getMessage()); }
        }
    }

    private void handleChargingCapacityChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                double capacity = BydDeviceHelper.getDoubleValue(args[0]);
                if (!Double.isNaN(capacity) && capacity >= 0 && capacity <= 200) {
                    snapshot.set(snapshot.get().toBuilder().remainKwh(capacity).build());
                }
            } catch (Exception e) { logger.debug("handleChargingCapacityChanged error: " + e.getMessage()); }
        }
    }

    private void handleDrivingTimeChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                double hours = BydDeviceHelper.getDoubleValue(args[0]);
                if (!Double.isNaN(hours) && hours >= 0 && hours <= 10000) {
                    snapshot.set(snapshot.get().toBuilder().drivingTimeHours(hours).build());
                }
            } catch (Exception e) { logger.debug("handleDrivingTimeChanged error: " + e.getMessage()); }
        }
    }

    private void handleKeyBatteryLevelChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int level = BydDeviceHelper.getIntValue(args[0]);
                if (level >= 0 && level <= 1) {
                    snapshot.set(snapshot.get().toBuilder().keyBatteryLevel(level).build());
                }
            } catch (Exception e) { logger.debug("handleKeyBatteryLevelChanged error: " + e.getMessage()); }
        }
    }

    /**
     * Dispatcher for the typed engine listener. Routes the three known
     * device-specific callbacks to existing handle* methods, and forwards
     * unrecognised feature-ID events to the discovery logger so we can
     * extend BydFeatureIds.Engine with whatever fluid temperature IDs this
     * firmware happens to publish.
     */
    private void onEngineCallback(String method, Object[] args) {
        if (args == null) return;
        try {
            if ("onEngineCoolantLevelChanged".equals(method)) {
                handleEngineCoolantLevelChanged(args);
                if (!loggedEngineCoolantEvent && args.length > 0) {
                    loggedEngineCoolantEvent = true;
                    int level = BydDeviceHelper.getIntValue(args[0]);
                    logger.info("Engine event FIRST: coolantLevel=" + level
                            + " (0=NORMAL,1=LOW)");
                }
                return;
            }
            if ("onOilLevelChanged".equals(method)) {
                handleOilLevelChanged(args);
                if (!loggedEngineOilEvent && args.length > 0) {
                    loggedEngineOilEvent = true;
                    int level = BydDeviceHelper.getIntValue(args[0]);
                    logger.info("Engine event FIRST: oilLevel=" + level + " (0-254)");
                }
                return;
            }
            if ("onEngineSpeedChanged".equals(method) && args.length > 0) {
                int rpm = ((Number) args[0]).intValue();
                if (rpm >= 0 && rpm <= 8000) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().engineSpeedRpm(rpm).build());
                    }
                }
                return;
            }
            if ("onDataEventChanged".equals(method) && args.length >= 2) {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                int rawInt = BydDeviceHelper.getIntValue(eventValue);
                double rawDbl = BydDeviceHelper.getDoubleValue(eventValue);

                // Known engine feature IDs — these are declared in
                // BYDAutoFeatureIds.Engine but the typed callbacks
                // (onEngineSpeedChanged etc.) are dormant on PHEV firmware,
                // so consume them off the generic event channel instead.
                // Apply the same sentinel filters and scaling as collectEngine.
                if (eventId == BydFeatureIds.ENGINE_SPEED
                        || eventId == BydFeatureIds.ENGINE_SPEED_ALT) {
                    // 8191 (0x1FFF) is a PHEV "engine off" sentinel observed on this
                    // firmware family; the standard BMS_UNAVAILABLE family covers the rest.
                    if (rawInt != BydFeatureIds.BMS_UNAVAILABLE
                            && rawInt != BydFeatureIds.INVALID_VALUE
                            && rawInt != BydFeatureIds.INVALID_VALUE_2
                            && rawInt != 8191
                            && rawInt >= 0 && rawInt <= 8000) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            snapshot.set(current.toBuilder().engineSpeedRpm(rawInt).build());
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_POWER) {
                    // The event's intValue carries the raw CAN signal; doubleValue is
                    // typically 0.0 on the listener path. Same dual-scale heuristic as
                    // collectEngine: |raw| > 100 implies deciwatts (×0.1 → kW),
                    // otherwise treat as kW directly. Range-filter excludes sentinels.
                    double raw = (rawInt != Integer.MIN_VALUE) ? (double) rawInt : rawDbl;
                    if (!Double.isNaN(raw) && raw >= -200.0 && raw <= 400.0) {
                        double kw = (Math.abs(raw) > 100.0) ? raw * 0.1 : raw;
                        // After scaling, re-check the kW range so a hectowatt value
                        // like 3095 (→ 309.5) gets rejected instead of mis-stored.
                        if (kw >= -200.0 && kw <= 400.0) {
                            BydVehicleData current = snapshot.get();
                            if (current != null) {
                                snapshot.set(current.toBuilder().enginePowerKw(kw).build());
                            }
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_FRONT_MOTOR_SPEED) {
                    // Front motor RPM is negated to match the cluster's display
                    // convention (forward motion = positive). Filter sentinels.
                    if (rawInt != Integer.MIN_VALUE
                            && rawInt != BydFeatureIds.BMS_UNAVAILABLE
                            && rawInt != BydFeatureIds.INVALID_VALUE
                            && rawInt != BydFeatureIds.INVALID_VALUE_2
                            && Math.abs(rawInt) <= 25000) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            snapshot.set(current.toBuilder().frontMotorSpeed(-rawInt).build());
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_REAR_MOTOR_SPEED) {
                    if (rawInt != Integer.MIN_VALUE
                            && rawInt != BydFeatureIds.BMS_UNAVAILABLE
                            && rawInt != BydFeatureIds.INVALID_VALUE
                            && rawInt != BydFeatureIds.INVALID_VALUE_2
                            && Math.abs(rawInt) <= 25000) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            snapshot.set(current.toBuilder().rearMotorSpeed(rawInt).build());
                        }
                    }
                    return;
                }
                if (eventId == BydFeatureIds.ENGINE_FRONT_MOTOR_TORQUE) {
                    // Negated to match cluster convention (same as collectEngine).
                    if (!Double.isNaN(rawDbl) && Math.abs(rawDbl) <= 1000.0) {
                        BydVehicleData current = snapshot.get();
                        if (current != null) {
                            snapshot.set(current.toBuilder().frontMotorTorque(-rawDbl).build());
                        }
                    }
                    return;
                }

                // Unknown ID — log once for discovery (capped at 32 unique IDs).
                logUnknownEngineEventOnce(eventId, rawInt, rawDbl);
            }
        } catch (Exception e) {
            logger.debug("onEngineCallback error (" + method + "): " + e.getMessage());
        }
    }

    // One-shot diagnostic flags for the typed engine callbacks.
    private volatile boolean loggedEngineCoolantEvent = false;
    private volatile boolean loggedEngineOilEvent = false;

    // Log each unknown engine feature ID once, capped at 32 unique IDs total.
    // Engine fluids on some firmware (coolant temp, oil temp on PHEVs) arrive
    // here keyed on IDs that aren't in BYDAutoFeatureIds.Engine — surface the
    // first sighting of each so we can extend the constant table empirically.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> loggedUnknownEngineIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_UNKNOWN_ENGINE_IDS = 32;

    private void logUnknownEngineEventOnce(int eventId, int rawInt, double rawDbl) {
        if (loggedUnknownEngineIds.size() >= MAX_UNKNOWN_ENGINE_IDS) return;
        if (loggedUnknownEngineIds.putIfAbsent(eventId, Boolean.TRUE) != null) return;
        logger.info("Engine event UNKNOWN id=" + eventId
                + " intValue=" + (rawInt == Integer.MIN_VALUE ? "n/a" : Integer.toString(rawInt))
                + " doubleValue=" + (Double.isNaN(rawDbl) ? "n/a" : Double.toString(rawDbl))
                + " — if this looks like a coolant/oil temp, add it to BydFeatureIds.Engine");
    }

    private void handleEngineCoolantLevelChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int level = BydDeviceHelper.getIntValue(args[0]);
                if (level >= 0 && level <= 1) {
                    snapshot.set(snapshot.get().toBuilder().engineCoolantLevel(level).build());
                }
            } catch (Exception e) { logger.debug("handleEngineCoolantLevelChanged error: " + e.getMessage()); }
        }
    }

    private void handleOilLevelChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int level = BydDeviceHelper.getIntValue(args[0]);
                if (level >= 0 && level <= 254) {
                    snapshot.set(snapshot.get().toBuilder().oilLevel(level).build());
                }
            } catch (Exception e) { logger.debug("handleOilLevelChanged error: " + e.getMessage()); }
        }
    }

    private void handleSafetyBeltStatusChanged(Object[] args) {
        if (args != null && args.length > 0 && snapshot.get() != null) {
            try {
                int status = BydDeviceHelper.getIntValue(args[0]);
                if (status >= 0) {
                    // Safety belt status is a bitmask — store raw value
                    // Individual seat belt states are decoded by consumers
                    snapshot.set(snapshot.get().toBuilder().build());
                }
            } catch (Exception e) { logger.debug("handleSafetyBeltStatusChanged error: " + e.getMessage()); }
        }
    }

    // ==================== VEHICLE CONTROL SETTERS ====================
    // All setters call BydDeviceHelper directly from UID 2000.
    // If a setter fails due to UID permissions, it logs the error and returns false.
    // These methods are public and always callable — no config gate needed.

    // --- Climate Control ---

    public boolean setAcPower(boolean on) {
        // Use the named start()/stop() methods on BYDAutoAcDevice — these actually
        // turn the AC system on/off. The previous implementation used AC_AUTO_MODE_SET
        // which only toggles AUTO mode (automatic climate control) without stopping the
        // AC compressor/blower. This caused "turn off" to merely disable auto mode
        // while the AC kept running in manual mode.
        //
        // Reference: BYDCarController.setAcState() calls acDevice.start(0) / acDevice.stop(0)
        // Parameter 0 = default zone (all zones).
        // Return value: 0 = success, 1 = failed, 2 = timeout, 3 = busy, 4 = invalid value
        try {
            String methodName = on ? "start" : "stop";
            Object result = BydDeviceHelper.callGetter(acDevice, methodName, 0);
            boolean success = (result instanceof Integer && ((Integer) result).intValue() == 0);
            
            if (!success && result instanceof Integer) {
                int code = ((Integer) result).intValue();
                // Retry once on BUSY (3) — AC controller may be processing a previous command
                if (code == 3) {
                    logger.info("AC " + methodName + " returned BUSY, retrying in 500ms...");
                    Thread.sleep(500);
                    result = BydDeviceHelper.callGetter(acDevice, methodName, 0);
                    success = (result instanceof Integer && ((Integer) result).intValue() == 0);
                }
                if (!success) {
                    logger.warn("AC " + methodName + " failed: result=" + result +
                        " (0=ok, 1=fail, 2=timeout, 3=busy, 4=invalid)");
                }
            }
            
            return success;
        } catch (Exception e) {
            logger.debug("setAcPower(" + on + ") via start/stop failed: " + e.getMessage());
            // Fallback: try the feature ID approach (less reliable but works on some older firmware)
            try {
                // AC_AUTO_MODE_SET with value 0 doesn't truly stop AC on most models,
                // but on some older DiLink 3.0 firmware it's the only available method.
                return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, on ? 1 : 0);
            } catch (Exception e2) {
                logger.debug("setAcPower fallback also failed: " + e2.getMessage());
                return false;
            }
        }
    }

    public boolean setAcTemperature(int zone, double tempCelsius) {
        try {
            // Temperature is sent as int (degrees × 1 for most BYD models)
            int tempInt = (int) Math.round(tempCelsius);
            if (tempInt < 17 || tempInt > 33) return false;
            // SDK method: acDevice.setAcTemperature(zone, temp, 0, 1)
            Object result = BydDeviceHelper.callMethod(acDevice, "setAcTemperature", zone, tempInt, 0, 1);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAcTemperature failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAcFanLevel(int level) {
        try {
            if (level < 1 || level > 7) return false;
            // SDK method: acDevice.setAcWindLevel(0, level)
            Object result = BydDeviceHelper.callMethod(acDevice, "setAcWindLevel", 0, level);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAcFanLevel failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAcWindMode(int mode) {
        try {
            // SDK method: acDevice.setAcWindMode(0, mode)
            Object result = BydDeviceHelper.callMethod(acDevice, "setAcWindMode", 0, mode);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAcWindMode failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setFrontDefrost(boolean on) {
        try {
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_DEFROST_FRONT_SET, on ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setFrontDefrost failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setRearDefrost(boolean on) {
        try {
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_DEFROST_REAR_SET, on ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setRearDefrost failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAcCycleMode(int mode) {
        try {
            return BydDeviceHelper.sendSetCommand(acDevice, BydFeatureIds.AC_CYCLE_MODE_SET, mode);
        } catch (Exception e) {
            logger.debug("setAcCycleMode failed: " + e.getMessage());
            return false;
        }
    }

    // --- Windows ---
    public boolean setSunWindowCommand(int area, int command) {
        try {
            // area: 5=Sunroof, 6=Sunshade
            if (area < 5 || area > 6) return false;
            // incoming command: 1=open, 2=close, 3=stop, 4=half, (5=breath only for sunroof)
            // Remap to these values to match windows (3 and 4 are swapped)
            // SDK command: 1=open, 2=close, 3=half, 4=stop, (5=breath only for sunroof)
            if (command == 3) {
                command = 4;
            } else if (command == 4) {
                command = 3;
            }
            // SDK method: bodyworkDevice.voiceCtlMoonRoof(cmd) or bodyworkDevice.voiceCtlSunshadePanel(cmd)
            String cmd = area == 5 ? "voiceCtlMoonRoof" : "voiceCtlSunshadePanel";
            Object result = BydDeviceHelper.callMethod(bodyworkDevice, cmd, command);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("Set " + (area == 5 ? "Sunroof" : "Sunshade") +  " failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setWindowCommand(int area, int command) {
        try {
            // area: 1=LF, 2=RF, 3=LR, 4=RR, 5=Sunroof, 6=Sunshade
            // command: 1=open, 2=close, 3=stop, 4=half, 5=breath
            // Sunshade and Sunroof have different command for set
            if (area >= 5 && area <= 6) return setSunWindowCommand(area, command);
            if (area < 1 || area > 4) return false;
            // SDK method: bodyworkDevice.setAllWindowState(lf, rf, lr, rr)
            // Only the target area gets the command, others get 0
            int lf = area == 1 ? command : 0;
            int rf = area == 2 ? command : 0;
            int lr = area == 3 ? command : 0;
            int rr = area == 4 ? command : 0;
            Object result = BydDeviceHelper.callMethod(bodyworkDevice, "setAllWindowState", lf, rf, lr, rr);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setWindowCommand failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAllWindowsCommand(int command) {
        try {
            // command: 1=open, 2=close, 3=stop
            // SDK method: bodyworkDevice.setAllWindowState(cmd, cmd, cmd, cmd)
            Object result = BydDeviceHelper.callMethod(bodyworkDevice, "setAllWindowState", command, command, command, command);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setAllWindowsCommand failed: " + e.getMessage());
            return false;
        }
    }

    // Per-area executor so a new target on one window cancels its prior
    // motion without affecting the others. Lazy-init.
    private final java.util.concurrent.ExecutorService[] windowExecutors =
            new java.util.concurrent.ExecutorService[6];
    private final java.util.concurrent.Future<?>[] windowMotionTasks =
            new java.util.concurrent.Future<?>[6];

    private synchronized java.util.concurrent.ExecutorService getWindowExecutor(int areaIdx) {
        java.util.concurrent.ExecutorService ex = windowExecutors[areaIdx];
        if (ex == null) {
            ex = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "WinMove-" + (areaIdx + 1));
                t.setDaemon(true);
                return t;
            });
            windowExecutors[areaIdx] = ex;
        }
        return ex;
    }

    private int readWindowPercent(int area) {
        try {
            Object wp = BydDeviceHelper.callGetter(bodyworkDevice, "getWindowOpenPercent", area);
            if (wp instanceof Number) return ((Number) wp).intValue();
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Closed-loop window positioning: drives the window towards {@code targetPercent}
     * and stops when it reaches the target (within tolerance), the motor stalls,
     * or a safety timeout elapses. Returns immediately; motion runs on a
     * per-window background thread so a fresh target cancels the previous one.
     *
     * @param area     1=LF, 2=RF, 3=LR, 4=RR
     * @param targetPercent 0 (closed) through 100 (fully open)
     * @return true if motion was scheduled, false if inputs were invalid or
     *         the window is already at the target.
     */
    public boolean moveWindowToPercent(int area, int targetPercent) {
        if (area < 1 || area > 6) return false;
        if (targetPercent < 0 || targetPercent > 100) return false;
        int areaIdx = area - 1;

        final int target = targetPercent;
        // Set the tolerance to 0 when fully open or closed requested to prevent windows being slightly open
        final int tolerance = (targetPercent == 100 || targetPercent == 0) ? 0 : 5; // ±5 % is the realistic floor (motor coast)
        final long pollIntervalMs = 200;  // SDK getter is cheap; tight loop = clean stop
        final long maxRunMs = 12_000;     // window full-travel ≈ 4–6 s; cap at 12 s
        final long stallWindowMs = 1_200; // no progress for this long → stall / pinch

        int initial = readWindowPercent(area);
        if (initial >= 0 && Math.abs(initial - target) <= tolerance) {
            logger.debug("Window " + area + " already near target (" + initial + "% vs " + target + "%)");
            return false;
        }

        // Cancel any in-flight motion for this window.
        java.util.concurrent.Future<?> prev = windowMotionTasks[areaIdx];
        if (prev != null && !prev.isDone()) prev.cancel(true);

        Runnable task = () -> {
            try {
                int start = readWindowPercent(area);
                if (start < 0) start = 50; // unknown — assume mid; stall-detect handles oddities

                int direction = target > start ? 1 : 2; // 1=open, 2=close
                if (!setWindowCommand(area, direction)) {
                    logger.warn("Window " + area + ": initial command failed");
                    return;
                }

                long startMs = System.currentTimeMillis();
                long lastProgressMs = startMs;
                int lastSeenPercent = start;
                boolean stopped = false;

                while (!Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(pollIntervalMs); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    int now = readWindowPercent(area);
                    long elapsed = System.currentTimeMillis() - startMs;

                    if (now >= 0) {
                        // Stop once we've crossed the target in the direction we
                        // were moving. Crossing-based comparison avoids stopping
                        // early on a noisy reading near the boundary.
                        boolean reached = direction == 1
                                ? now >= target - tolerance
                                : now <= target + tolerance;
                        if (reached) {
                            setWindowCommand(area, 3);
                            stopped = true;
                            logger.info("Window " + area + " reached target=" + target
                                    + "% (final=" + now + "%)");
                            break;
                        }

                        if (Math.abs(now - lastSeenPercent) >= 1) {
                            lastSeenPercent = now;
                            lastProgressMs = System.currentTimeMillis();
                        } else if (System.currentTimeMillis() - lastProgressMs > stallWindowMs) {
                            setWindowCommand(area, 3);
                            stopped = true;
                            logger.warn("Window " + area + " stalled at " + now
                                    + "% (target=" + target + "%) — stopped");
                            break;
                        }
                    }

                    if (elapsed > maxRunMs) {
                        setWindowCommand(area, 3);
                        stopped = true;
                        logger.warn("Window " + area + " motion timed out at "
                                + (now >= 0 ? now : -1) + "% — stopped");
                        break;
                    }
                }

                if (!stopped) setWindowCommand(area, 3);
            } catch (Exception e) {
                logger.warn("Window " + area + " motion task error: " + e.getMessage());
                try { setWindowCommand(area, 3); } catch (Exception ignored) {}
            }
        };

        windowMotionTasks[areaIdx] = getWindowExecutor(areaIdx).submit(task);
        return true;
    }

    // --- Tailgate ---

    public boolean openTailgate() {
        // Method 1: SettingDevice.voiceCtlBackDoor(1) — official BYD AutoCommander method
        if (settingDevice != null) {
            try {
                Object result = BydDeviceHelper.callGetter(settingDevice, "voiceCtlBackDoor", 1);
                logger.info("openTailgate voiceCtlBackDoor(1) result: " + result);
                if (result == null || (result instanceof Integer && ((Integer) result).intValue() == 0)) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("openTailgate voiceCtlBackDoor failed: " + e.getMessage());
            }
        }
        // Method 2: Bodywork BACK_DOOR_TRIGGER
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_BACK_DOOR_TRIGGER, 1);
        } catch (Exception e) {
            logger.debug("openTailgate BACK_DOOR_TRIGGER failed: " + e.getMessage());
            return false;
        }
    }

    public boolean closeTailgate() {
        // SOTA FIX: Commander app uses value 3 for close via SETTING_VOICE_CTRL_BACK_DOOR_SET
        // Values: 1=open, 2=stop, 3=close (confirmed from AutoCommander decompilation)
        
        // Method 1: SettingDevice sendSetCommand with value 3 (close)
        if (settingDevice != null) {
            try {
                boolean result = BydDeviceHelper.sendSetCommand(settingDevice, 
                    BydFeatureIds.SETTING_VOICE_CTRL_BACK_DOOR_SET, 3);
                logger.info("closeTailgate sendSetCommand(VOICE_CTRL_BACK_DOOR, 3) result: " + result);
                if (result) return true;
            } catch (Exception e) {
                logger.debug("closeTailgate sendSetCommand failed: " + e.getMessage());
            }
            
            // Method 1b: Try voiceCtlBackDoor(3) directly
            try {
                Object result = BydDeviceHelper.callGetter(settingDevice, "voiceCtlBackDoor", 3);
                logger.info("closeTailgate voiceCtlBackDoor(3) result: " + result);
                if (result == null || (result instanceof Integer && ((Integer) result).intValue() == 0)) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("closeTailgate voiceCtlBackDoor(3) failed: " + e.getMessage());
            }
        }
        
        // Method 2: Bodywork BACK_DOOR_TRIGGER with value 3 (close)
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_BACK_DOOR_TRIGGER, 3);
        } catch (Exception e) {
            logger.debug("closeTailgate BACK_DOOR_TRIGGER(3) failed: " + e.getMessage());
            return false;
        }
    }

    public boolean stopTailgate() {
        // SOTA FIX: Commander app uses value 2 for stop
        // Values: 1=open, 2=stop, 3=close
        
        // Method 1: SettingDevice sendSetCommand with value 2 (stop)
        if (settingDevice != null) {
            try {
                boolean result = BydDeviceHelper.sendSetCommand(settingDevice,
                    BydFeatureIds.SETTING_VOICE_CTRL_BACK_DOOR_SET, 2);
                logger.info("stopTailgate sendSetCommand(VOICE_CTRL_BACK_DOOR, 2) result: " + result);
                if (result) return true;
            } catch (Exception e) {
                logger.debug("stopTailgate sendSetCommand failed: " + e.getMessage());
            }
            
            // Fallback: voiceCtlBackDoor(2)
            try {
                Object result = BydDeviceHelper.callGetter(settingDevice, "voiceCtlBackDoor", 2);
                if (result == null || (result instanceof Integer && ((Integer) result).intValue() == 0)) {
                    return true;
                }
            } catch (Exception e) {
                logger.debug("stopTailgate voiceCtlBackDoor(2) failed: " + e.getMessage());
            }
        }
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.BODY_BACK_DOOR_TRIGGER, 0);
        } catch (Exception e) {
            logger.debug("stopTailgate BACK_DOOR_TRIGGER failed: " + e.getMessage());
            return false;
        }
    }

    // --- AVAS / Exterior Speaker ---

    /** Get the multimedia device (for direct access by audio test handler). */
    public Object getMultimediaDevice() {
        return multimediaDevice;
    }

    /** Get exterior speaker state: 1=enabled, 0=disabled, null=unavailable or unsupported. */
    public Integer getExteriorSpeakerState() {
        if (multimediaDevice == null) return null;
        try {
            Method m = multimediaDevice.getClass().getMethod("getExteriorSpeakerState");
            Object result = m.invoke(multimediaDevice);
            return (result instanceof Integer) ? (Integer) result : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            logger.debug("getExteriorSpeakerState failed: " + e.getMessage());
            return null;
        }
    }

    /** Set exterior speaker state: 1=enable, 0=disable. Returns false if unsupported on this device. */
    public boolean setExteriorSpeakerState(int state) {
        if (multimediaDevice == null) return false;
        try {
            Method m = multimediaDevice.getClass().getMethod("setExteriorSpeakerState", int.class);
            m.invoke(multimediaDevice, state);
            return true;
        } catch (NoSuchMethodException e) {
            logger.warn("setExteriorSpeakerState: method not present on multimedia device — exterior speaker routing unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setExteriorSpeakerState failed: " + e.getMessage());
            return false;
        }
    }

    /** Get AVAS sound source type. Returns null if unsupported. */
    public Integer getAVASSoundSource() {
        if (multimediaDevice == null) return null;
        try {
            Method m = multimediaDevice.getClass().getMethod("getAVASSoundSource");
            Object result = m.invoke(multimediaDevice);
            return (result instanceof Integer) ? (Integer) result : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            logger.debug("getAVASSoundSource failed: " + e.getMessage());
            return null;
        }
    }

    /** Set AVAS sound source type. Returns false if unsupported on this device. */
    public boolean setAVASSoundSource(int sourceType) {
        if (multimediaDevice == null) return false;
        try {
            Method m = multimediaDevice.getClass().getMethod("setAVASSoundSource", int.class);
            m.invoke(multimediaDevice, sourceType);
            return true;
        } catch (NoSuchMethodException e) {
            logger.warn("setAVASSoundSource: method not present on multimedia device — AVAS routing unsupported on this OEM build");
            return false;
        } catch (Exception e) {
            logger.warn("setAVASSoundSource failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Probe the multimedia device for any AVAS / exterior-speaker / outside-sound related methods.
     * Returns a list of method signatures (name + param types) whose name matches the regex.
     * Used by the audio test handler to discover what the OEM build actually exposes.
     */
    public java.util.List<String> probeMultimediaMethods(String regex) {
        java.util.List<String> matches = new java.util.ArrayList<>();
        if (multimediaDevice == null) return matches;
        java.util.regex.Pattern p;
        try {
            p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            return matches;
        }
        Class<?> cls = multimediaDevice.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (!p.matcher(m.getName()).find()) continue;
                StringBuilder sig = new StringBuilder();
                sig.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sig.append(", ");
                    sig.append(params[i].getSimpleName());
                }
                sig.append(')');
                matches.add(sig.toString());
            }
            cls = cls.getSuperclass();
        }
        java.util.Collections.sort(matches);
        return matches;
    }

    /** Check if multimedia device is available. */
    public boolean isMultimediaAvailable() {
        return multimediaDevice != null;
    }

    // --- Charging ---

    public boolean setChargeStopCapacity(int percent) {
        try {
            if (percent < 50 || percent > 100) return false;
            // Try typed method first, no feature ID alternative for charge stop capacity
            Object result = BydDeviceHelper.callGetter(chargingDevice, "setChargeStopCapacityState", percent);
            if (result instanceof Integer && ((Integer) result).intValue() == 0) return true;
            // Fallback: no known feature ID for charge stop capacity, typed method is the only path
            return false;
        } catch (Exception e) {
            logger.debug("setChargeStopCapacity failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setChargeStopSwitch(boolean enabled) {
        try {
            // Try typed method first (matches SDK), fallback to sendSetCommand
            Object result = BydDeviceHelper.callGetter(chargingDevice, "setChargeStopSwitchState", enabled ? 1 : 0);
            if (result instanceof Integer && ((Integer) result).intValue() == 0) return true;
            return false;
        } catch (Exception e) {
            logger.debug("setChargeStopSwitch failed: " + e.getMessage());
            return false;
        }
    }

    // --- Ambient Lighting ---

    public boolean setAmbientLightEnabled(boolean on) {
        try {
            return BydDeviceHelper.sendSetCommand(lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_MAIN_SWITCH_SET, on ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setAmbientLightEnabled failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAmbientBrightness(int level) {
        try {
            if (level < 0 || level > 100) return false;
            return BydDeviceHelper.sendSetCommand(lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_CUSTOM_BRIGHTNESS_SET, level);
        } catch (Exception e) {
            logger.debug("setAmbientBrightness failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setAmbientColor(int colorValue) {
        try {
            return BydDeviceHelper.sendSetCommand(lightDevice, BydFeatureIds.LIGHT_ATMOSPHERE_CUSTOM_COLOR_SET, colorValue);
        } catch (Exception e) {
            logger.debug("setAmbientColor failed: " + e.getMessage());
            return false;
        }
    }

    // --- Seats ---

    public boolean setSeatHeating(int position, int level) {
        try {
            if (position < 1 || position > 4) return false;
            if (level < 0 || level > 3) return false;
            // SDK method: settingDevice.setSeatHeatingState(position, normalizedLevel)
            // Level normalization: coerceIn(level, 0, 2) + 1 → 0→1(off), 1→2(low), 2→3(high)
            int normalizedLevel = Math.min(level, 2) + 1;
            Object result = BydDeviceHelper.callMethod(settingDevice, "setSeatHeatingState", position, normalizedLevel);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setSeatHeating failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setSeatVentilation(int position, int level) {
        try {
            if (position < 1 || position > 4) return false;
            if (level < 0 || level > 3) return false;
            // Level normalization: coerceIn(level, 0, 2) + 1 → 0→1(off), 1→2(low), 2→3(high).
            // Matches Commander's BYDCarController.normalizeSeatLevel().
            int normalizedLevel = Math.min(level, 2) + 1;

            // Capability gate via BYDAutoSettingDevice.hasFeature(). The
            // canonical SDK exposes this for hardware detection — if it
            // returns DEVICE_NOT_HAS_THE_FEATURE we know the vehicle (e.g.
            // Atto 3 base trim) doesn't have ventilated seats wired and we
            // shouldn't pretend the SDK accepting the call means anything.
            // Probed once per session and cached.
            if (!seatVentFeatureProbed) {
                seatVentFeatureProbed = true;
                seatVentFeatureSupported = probeHasFeature(settingDevice, "SEAT_VENTILATING");
                if (!seatVentFeatureSupported) {
                    logger.warn("Seat ventilation: hasFeature(\"SEAT_VENTILATING\") returned 0. "
                        + "Vehicle hardware lacks ventilated seats. UI should grey out the control.");
                }
            }

            // Use the canonical SDK method directly. Commander uses the same
            // call (BYDCarController.setSeatVentilationInternal at line 3017
            // of the decompile) and the BYD stub SDK at
            // android/hardware/bydauto/setting/BYDAutoSettingDevice.java only
            // defines this name. The previous "fallback chain" of
            // setSeatBlowingState / setSeatCoolingState / etc. was guesswork
            // — none of those exist in either Commander's reference or the
            // stub SDK. Removed.
            Method m;
            try {
                m = settingDevice.getClass().getMethod("setSeatVentilatingState", int.class, int.class);
            } catch (NoSuchMethodException nsme) {
                logger.warn("Seat ventilation: setSeatVentilatingState not present on this firmware "
                    + "(framework-side gap, not hardware) — cannot control ventilation.");
                return false;
            }
            Object result = m.invoke(settingDevice, position, normalizedLevel);
            boolean accepted = result instanceof Integer && ((Integer) result).intValue() == 0;
            if (!accepted) {
                logger.debug("setSeatVentilatingState(" + position + ", " + normalizedLevel
                    + ") returned " + result);
                return false;
            }
            // Honest result: only return true when the hardware actually
            // exists. Otherwise the SDK accepts the call but nothing happens
            // physically and the UI would mislead the user with a green
            // toast.
            return seatVentFeatureSupported;
        } catch (Exception e) {
            logger.debug("setSeatVentilation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Recall a stored driver-side seat memory position (1 or 2).
     * SDK feature lives on settingDevice — Adas.* IDs do not accept this set.
     */
    public boolean setSeatMemoryPosition(int position) {
        try {
            if (position < 1 || position > 2) return false;
            int result = BydDeviceHelper.callSetSingle(settingDevice, BydFeatureIds.SETTING_LF_MEMORY_LOCATION_WAKE_SET, position);
            return result == 0;
        } catch (Exception e) {
            logger.debug("setSeatMemoryPosition failed: " + e.getMessage());
        }
        return false;
    }

    /** Cached BYDAutoSettingDevice.hasFeature("SEAT_VENTILATING") result; probed once. */
    private volatile boolean seatVentFeatureProbed = false;
    private volatile boolean seatVentFeatureSupported = false;

    /**
     * Capability probe via BYDAutoSettingDevice.hasFeature(String).
     * Returns DEVICE_HAS_THE_FEATURE (1) on supported vehicles per the
     * canonical SDK. Treat any result == 1 as supported.
     */
    private static boolean probeHasFeature(Object settingDevice, String feature) {
        if (settingDevice == null || feature == null) return false;
        try {
            Method m = settingDevice.getClass().getMethod("hasFeature", String.class);
            Object result = m.invoke(settingDevice, feature);
            if (result instanceof Number) {
                return ((Number) result).intValue() == 1;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Lights ---

    public boolean setDayTimeLight(boolean enable) {
        try {
            Object result = BydDeviceHelper.callMethod(lightDevice, "setDayTimeLightState", enable ? 1 : 2);
            return result instanceof Integer && ((Integer) result).intValue() == 0;
        } catch (Exception e) {
            logger.debug("setDayTimeLight failed: " + e.getMessage());
        }
        return false;
    }

    // --- ADAS ---

    public boolean setSpeedLimitWarning(boolean enable) {
        try {
            int result = BydDeviceHelper.callSetSingle(adasDevice, BydFeatureIds.ADAS_SLW_FUNC_SWITCH_STATE_SET, enable ? 2 : 1);
            return result == 0;
        } catch (Exception e) {
            logger.debug("setSpeedLimitWarning failed: " + e.getMessage());
        }
        return false;
    }

    public boolean setFcwLevel(int level) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_FCW_LEVEL_SET, level);
        } catch (Exception e) {
            logger.debug("setFcwLevel failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setLaneAssistMode(int mode) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_ELKA_SWITCH_SET, mode);
        } catch (Exception e) {
            logger.debug("setLaneAssistMode failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setBlindSpotDetection(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_DOW_STATE_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setBlindSpotDetection failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setEmergencyBraking(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_ECTB_STATE_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setEmergencyBraking failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setRearCrossTrafficAlert(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_RCTA_STATE_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setRearCrossTrafficAlert failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setFrontCrossTrafficAlert(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_FCTA_SWITCH_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setFrontCrossTrafficAlert failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setFrontCrossTrafficBraking(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_FCTB_SWITCH_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setFrontCrossTrafficBraking failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setSpeedLimitRecognition(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_SLR_STATUS_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setSpeedLimitRecognition failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setTrafficLightAttention(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_TLA_SWITCH_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setTrafficLightAttention failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setOpenDoorWarning(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_DOW_STATE_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setOpenDoorWarning failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setRearCollisionWarning(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_RCW_STATE_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setRearCollisionWarning failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setEspState(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_ESP_STATE_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setEspState failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setIslaSwitch(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_ISLA_SWITCH_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setIslaSwitch failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setIslcSwitch(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.ADAS_ISLC_SWITCH_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setIslcSwitch failed: " + e.getMessage());
            return false;
        }
    }

    // --- Media ---

    /**
     * Send media info (artist + title) to the instrument cluster display.
     * Encodes the string as UTF-16LE bytes for the BYD instrument cluster.
     */
    public boolean sendMediaInfo(String artistAndTitle) {
        try {
            if (artistAndTitle == null) return false;
            String formatted = "  " + artistAndTitle + "  ";
            byte[] bytes = formatted.getBytes("UTF-16LE");
            byte[] finalBytes;
            if (bytes.length > 255) {
                // Truncate to 253 bytes + 2-byte null terminator
                finalBytes = new byte[255];
                System.arraycopy(bytes, 0, finalBytes, 0, 253);
                finalBytes[253] = 0;
                finalBytes[254] = 0;
            } else {
                finalBytes = bytes;
            }
            int result = BydDeviceHelper.callSetBuffer(instrumentDevice, 1140527112, finalBytes);
            return result >= 0;
        } catch (Exception e) {
            logger.debug("sendMediaInfo failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setMusicSource(int source) {
        try {
            if (source < 0 || source > 14) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_MUSIC_SOURCE_SET, source);
        } catch (Exception e) {
            logger.debug("setMusicSource failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setMusicState(int state) {
        try {
            if (state < 1 || state > 2) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_MUSIC_STATE_SET, state);
        } catch (Exception e) {
            logger.debug("setMusicState failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setMusicPlaybackProgress(int currentSeconds, int totalSeconds) {
        try {
            if (currentSeconds < 0 || totalSeconds < 0) return false;
            // Pack current and total into the feature ID call
            // Progress is sent as a single int: current seconds (the cluster calculates percentage)
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_MUSIC_PLAYBACK_PROGRESS_SET, currentSeconds);
        } catch (Exception e) {
            logger.debug("setMusicPlaybackProgress failed: " + e.getMessage());
            return false;
        }
    }

    // --- Display ---

    public boolean setInfotainmentBrightness(int level) {
        try {
            if (level < 0 || level > 100) return false;
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_BRIGHTNESS_GEAR_SET, level);
        } catch (Exception e) {
            logger.debug("setInfotainmentBrightness failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setDriverDisplayBrightness(int level) {
        try {
            if (level < 0 || level > 100) return false;
            // Driver display brightness uses the same feature ID — the instrument cluster
            // adjusts both displays together on most BYD models
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_BRIGHTNESS_GEAR_SET, level);
        } catch (Exception e) {
            logger.debug("setDriverDisplayBrightness failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setHudBrightness(int level) {
        try {
            if (level < 0 || level > 100) return false;
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_BRIGHTNESS_GEAR_SET, level);
        } catch (Exception e) {
            logger.debug("setHudBrightness failed: " + e.getMessage());
            return false;
        }
    }

    // --- Miscellaneous ---

    public boolean setMirrorsFolded(boolean folded) {
        try {
            return BydDeviceHelper.sendSetCommand(bodyworkDevice, BydFeatureIds.MIRROR_REARVIEW_SET, folded ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setMirrorsFolded failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setChildLock(boolean left, boolean enable) {
        try {
            int featureId = left ? BydFeatureIds.DOORLOCK_CHILDLOCK_LEFT_SET : BydFeatureIds.DOORLOCK_CHILDLOCK_RIGHT_SET;
            return BydDeviceHelper.sendSetCommand(doorLockDevice, featureId, enable ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setChildLock failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setWirelessCharging(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(chargingDevice, BydFeatureIds.CHARGING_WIRELESS_SWITCH_SET, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setWirelessCharging failed: " + e.getMessage());
            return false;
        }
    }

    public boolean wakeUpMcu() {
        try {
            Object result = BydDeviceHelper.callGetter(powerDevice, "wakeUpMcu");
            return result instanceof Number && ((Number) result).intValue() >= 0;
        } catch (Exception e) {
            logger.debug("wakeUpMcu failed: " + e.getMessage());
            return false;
        }
    }

    public boolean rotatePad() {
        try {
            return BydDeviceHelper.sendSetCommand(settingDevice, BydFeatureIds.SETTING_PAD_ROTATION_SET, 1);
        } catch (Exception e) {
            logger.debug("rotatePad failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setDriftMode(boolean enabled) {
        try {
            return BydDeviceHelper.sendSetCommand(engineDevice, BydFeatureIds.ENGINE_DRIFT_MODE_SWITCH_CONFIG, enabled ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setDriftMode failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setNavigationActive(boolean active) {
        try {
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_NAVIGATION_ACTIVATED_SET, active ? 1 : 0);
        } catch (Exception e) {
            logger.debug("setNavigationActive failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setNavigationETA(int minutes) {
        try {
            if (minutes < 0) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_NAVI_ESTIMATED_TIME_SET, minutes);
        } catch (Exception e) {
            logger.debug("setNavigationETA failed: " + e.getMessage());
            return false;
        }
    }

    public boolean setNavigationDistance(int meters) {
        try {
            if (meters < 0) return false;
            return BydDeviceHelper.sendSetCommand(instrumentDevice, BydFeatureIds.INSTRUMENT_NAVI_ESTIMATED_MILEAGE_SET, meters);
        } catch (Exception e) {
            logger.debug("setNavigationDistance failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Log a summary of all current values (for debugging).
     */
    public void logSummary() {
        BydVehicleData d = snapshot.get();
        if (d == null) {
            logger.info("No data collected yet");
            return;
        }
        logger.info("=== BYD Vehicle Data Summary ===");
        if (d.vin != null) logger.info("  VIN: " + d.vin);
        if (!Double.isNaN(d.socPercent)) logger.info("  SOC: " + d.socPercent + "%");
        else logger.warn("  SOC: UNAVAILABLE (statistic/energy devices returned blank)");
        if (!Double.isNaN(d.voltage12v)) logger.info("  12V: " + d.voltage12v + "V");
        if (!Double.isNaN(d.remainKwh)) logger.info("  Remaining: " + d.remainKwh + " kWh");
        if (!Double.isNaN(d.speedKmh)) logger.info("  Speed: " + d.speedKmh + " km/h");
        if (d.gearMode != BydVehicleData.UNAVAILABLE) logger.info("  Gear: " + d.gearMode);
        if (d.totalMileageKm != BydVehicleData.UNAVAILABLE) logger.info("  Odometer: " + d.totalMileageKm + " km");
        if (d.elecRangeKm != BydVehicleData.UNAVAILABLE) logger.info("  EV Range: " + d.elecRangeKm + " km");
        if (!Double.isNaN(d.highCellTempC)) logger.info("  Cell Temp: " + d.highCellTempC + "/" + d.lowCellTempC + "/" + d.avgCellTempC + "°C");
        if (!Double.isNaN(d.highCellVoltage)) logger.info("  Cell Voltage: " + d.highCellVoltage + "/" + d.lowCellVoltage + "V");
        if (!Double.isNaN(d.outsideTempC)) logger.info("  Outside: " + d.outsideTempC + "°C");
        if (d.tyrePressure != null) logger.info("  Tyres: FL=" + d.tyrePressure[0] + " FR=" + d.tyrePressure[1] + " RL=" + d.tyrePressure[2] + " RR=" + d.tyrePressure[3]);
        if (d.powerLevel != BydVehicleData.UNAVAILABLE) logger.info("  Power Level: " + d.powerLevel);
        logger.info("  Devices: " + d.availableDevices.length + " available");
        logger.info("================================");
    }
}
