package com.overdrive.app.byd;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

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
    private Object radarDevice;
    private Object powerDevice;

    // Unit conversion: BYD APIs return values in the user's configured unit.
    // If the user set miles on the instrument cluster, mileage/speed/range come back in miles/mph.
    // We detect this once at init and convert everything to km at the ingestion boundary.
    private static final double MILES_TO_KM = 1.60934;
    private double distanceToKmFactor = 1.0;  // 1.0 = already km, 1.60934 = miles→km
    private boolean unitDetected = false;

    private final List<String> availableDevices = new ArrayList<>();
    private final List<String> unavailableDevices = new ArrayList<>();

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
    public void init(Context context) {
        this.context = context;
        logger.info("=== BYD Data Collector Initializing ===");
        long start = System.currentTimeMillis();

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
        powerDevice = initDevice("android.hardware.bydauto.power.BYDAutoPowerDevice", "Power");
        safetyBeltDevice = initDevice("android.hardware.bydauto.safetybelt.BYDAutoSafetyBeltDevice", "SafetyBelt");
        tyreDevice = initDevice("android.hardware.bydauto.tyre.BYDAutoTyreDevice", "Tyre");
        doorLockDevice = initDevice("android.hardware.bydauto.doorlock.BYDAutoDoorLockDevice", "DoorLock");
        sensorDevice = initDevice("android.hardware.bydauto.sensor.BYDAutoSensorDevice", "Sensor");
        energyDevice = initDevice("android.hardware.bydauto.energy.BYDAutoEnergyDevice", "Energy");
        radarDevice = initDevice("android.hardware.bydauto.radar.BYDAutoRadarDevice", "Radar");

        logger.info("Devices available: " + availableDevices.size() + "/" + 
            (availableDevices.size() + unavailableDevices.size()));
        if (!unavailableDevices.isEmpty()) {
            logger.info("Unavailable: " + String.join(", ", unavailableDevices));
        }

        // Detect mileage unit from instrument cluster
        detectMileageUnit();

        // Read initial values (full collection including display-only devices)
        collectAllFull();

        // Dump all battery/energy related getter methods on key devices
        // to discover the correct remaining kWh API at runtime
        // Discovery methods removed — getBatteryRemainPowerEV() confirmed as correct BEV API.
        // BYD light/setting APIs have no write access from UID 2000.

        // Register listeners
        registerAllListeners();

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

        // DRIVING ONLY: skip when ACC is off (values are always 0/stale when parked)
        if (accIsOn) {
            collectSpeed(b);        // speed, accel, brake
            collectEngine(b);       // enginePower, motorSpeed/torque
            collectGearbox(b);      // gearMode
        }

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
        collectPower(b);
        collectSafetyBelt(b);
        collectTyre(b);
        collectDoorLock(b);
        collectSensor(b);
        collectEnergy(b);
        collectRadar(b);

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
            
            // Priority 3: BodyworkDevice.getBatteryPowerHEV() — fallback, unreliable on some BEVs
            Object hev = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerHEV");
            if (hev instanceof Number) {
                double hevVal = ((Number) hev).doubleValue();
                if (hevVal >= 0) {
                    b.socHevPercent(hevVal);
                    if (hevVal > 1 && hevVal < 120 && Double.isNaN(b.remainKwh)) {
                        b.remainKwh(hevVal);
                    }
                }
            }

            // getBatteryCapacity() — semantics vary by model:
            // - Newer models: returns Ah rating (fixed, e.g. 150 for Atto 3)
            // - Older models: returns remaining energy in 0.1 kWh units (changes with SOC)
            // Used as fallback when getBatteryPowerHEV() returns negative.
            Object cap = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryCapacity");
            if (cap instanceof Number) {
                double capVal = ((Number) cap).doubleValue();
                if (capVal > 0) b.capacityAh(capVal);

                // Fallback for older models where getBatteryPowerHEV() returned negative:
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
            Object rpm = BydDeviceHelper.callGetter(engineDevice, "getEngineSpeed");
            if (rpm instanceof Number) {
                int rpmVal = ((Number) rpm).intValue();
                if (rpmVal >= 0 && rpmVal <= 8000) b.engineSpeedRpm(rpmVal);
            }
            Object power = BydDeviceHelper.callGetter(engineDevice, "getEnginePower");
            if (power instanceof Number) b.enginePowerKw(((Number) power).doubleValue());

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
            Object mileage = BydDeviceHelper.callGetter(statisticDevice, "getTotalMileageValue");
            if (mileage instanceof Number) {
                int raw = ((Number) mileage).intValue();
                b.totalMileageKm((int) Math.round(raw * distanceToKmFactor));
            }

            Object evMileage = BydDeviceHelper.callGetter(statisticDevice, "getEVMileageValue");
            if (evMileage instanceof Number) {
                int raw = ((Number) evMileage).intValue();
                b.evMileageKm((int) Math.round(raw * distanceToKmFactor));
            }

            Object elecPct = BydDeviceHelper.callGetter(statisticDevice, "getElecPercentageValue");
            // This is the primary SOC source — StatisticDevice.getElecPercentageValue() returns display SOC %
            if (elecPct instanceof Number) {
                double soc = ((Number) elecPct).doubleValue();
                if (soc >= 0 && soc <= 100) b.socPercent(soc);
            }

            Object waterTemp = BydDeviceHelper.callGetter(statisticDevice, "getWaterTemperature");
            if (waterTemp instanceof Number) b.waterTempC(((Number) waterTemp).intValue());

            Object totalElec = BydDeviceHelper.callGetter(statisticDevice, "getTotalElecConValue");
            if (totalElec instanceof Number) b.totalElecCon(((Number) totalElec).doubleValue());

            Object totalFuel = BydDeviceHelper.callGetter(statisticDevice, "getTotalFuelConValue");
            if (totalFuel instanceof Number) b.totalFuelCon(((Number) totalFuel).doubleValue());

            Object elecRange = BydDeviceHelper.callGetter(statisticDevice, "getElecDrivingRangeValue");
            if (elecRange instanceof Number) {
                int raw = ((Number) elecRange).intValue();
                b.elecRangeKm((int) Math.round(raw * distanceToKmFactor));
            }

            Object fuelRange = BydDeviceHelper.callGetter(statisticDevice, "getFuelDrivingRangeValue");
            if (fuelRange instanceof Number) {
                int raw = ((Number) fuelRange).intValue();
                b.fuelRangeKm((int) Math.round(raw * distanceToKmFactor));
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
            // Battery charging state (0=ready, 1=charging, 2=finished, 3=discharging, etc.)
            Object battState = BydDeviceHelper.callGetter(chargingDevice, "getBatteryManagementDeviceState");
            if (battState instanceof Number) {
                int state = ((Number) battState).intValue();
                b.chargingState(state);
                
                // Clear stale charging power when not actively charging
                // Phantom 0.1 kW values persist on the CAN bus after unplugging
                if (state != 1) {  // Not CHARGING_BATTERY_STATE_CHARGING
                    b.chargingPowerKw(Double.NaN);
                    b.externalChargingPowerKw(Double.NaN);
                }
            }
            // Gun connection state (0=none, 1=AC, 2=AC fast, 3=DC, 4=V2L)
            Object gunState = BydDeviceHelper.callGetter(chargingDevice, "getChargingGunState");
            if (gunState instanceof Number) b.chargingGunState(((Number) gunState).intValue());
            // Charger work state
            Object charger = BydDeviceHelper.callGetter(chargingDevice, "getChargerWorkState");
            if (charger instanceof Number) b.chargerWorkState(((Number) charger).intValue());
            // Charging power — only use getter value if non-zero.
            // The getter often returns 0 on BYD models even while charging.
            // The real value comes from the onDataEventChanged callback (event 666894360).
            // Writing 0 here would overwrite the callback's valid value.
            Object power = BydDeviceHelper.callGetter(chargingDevice, "getChargingPower");
            if (power instanceof Number) {
                double kw = ((Number) power).doubleValue();
                if (Math.abs(kw) > 0.01 && Math.abs(kw) < 500) {
                    b.chargingPowerKw(kw);
                    logger.debug("ChargingDevice.getChargingPower() = " + kw + " kW");
                }
            }
        } catch (Exception e) {
            logger.debug("collectCharging error: " + e.getMessage());
        }
    }

    private void collectInstrument(BydVehicleData.Builder b) {
        if (instrumentDevice == null) return;
        try {
            Object extTemp = BydDeviceHelper.callGetter(instrumentDevice, "getOutCarTemperature");
            if (extTemp instanceof Number) {
                int t = ((Number) extTemp).intValue();
                if (t >= -50 && t <= 60) b.outsideTempC(t);
            }
            // External charging power — only use getter value if non-zero.
            // Same issue as ChargingDevice: getter returns 0 but callback delivers real value.
            Object extPower = BydDeviceHelper.callGetter(instrumentDevice, "getExternalChargingPower");
            if (extPower instanceof Number) {
                double p = ((Number) extPower).doubleValue();
                if (Math.abs(p) > 0.01 && p >= -500 && p <= 500) {
                    b.externalChargingPowerKw(p);
                    logger.debug("InstrumentDevice.getExternalChargingPower() = " + p + " kW");
                }
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
            if (dayTime instanceof Number) b.dayTimeLight(((Number) dayTime).intValue());
        } catch (Exception e) {
            logger.debug("collectLight error: " + e.getMessage());
        }
    }

    private int getLightStatus(int position) {
        Object val = BydDeviceHelper.callGetter(lightDevice, "getLightStatus", position);
        return (val instanceof Number) ? ((Number) val).intValue() : 0;
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
            // values when the ICE is running. The BodyworkDevice path (getBatteryPowerHEV +
            // onBatteryPowerHEVChanged listener) is the correct CAN bus path for kWh on
            // both BEVs and PHEVs — matching the OEM Diplus app's approach.
        } catch (Exception e) {
            logger.debug("collectPower error: " + e.getMessage());
        }
    }
    
    /**
     * Force-update a BYD device singleton's internal context field.
     * BYD singletons store context from the first getInstance() call.
     * If another daemon initialized it first with a null/stale context, methods NPE.
     */
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
            int[] pressures = new int[4];
            for (int i = 0; i < 4; i++) {
                Object p = BydDeviceHelper.callGetter(tyreDevice, "getTyrePressureValue", i + 1);
                pressures[i] = (p instanceof Number) ? ((Number) p).intValue() : -1;
            }
            b.tyrePressure(pressures);
        } catch (Exception e) {
            logger.debug("collectTyre error: " + e.getMessage());
        }
    }

    private void collectDoorLock(BydVehicleData.Builder b) {
        if (doorLockDevice == null) return;
        try {
            int[] locks = new int[7];
            for (int i = 0; i < 7; i++) {
                Object s = BydDeviceHelper.callGetter(doorLockDevice, "getDoorLockStatus", i + 1);
                locks[i] = (s instanceof Number) ? ((Number) s).intValue() : -1;
            }
            b.doorLockStatus(locks);
        } catch (Exception e) {
            logger.debug("collectDoorLock error: " + e.getMessage());
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

    // ==================== LISTENER REGISTRATION ====================

    private void registerAllListeners() {
        logger.info("Registering listeners...");
        int count = 0;

        if (BydDeviceHelper.registerListener(bodyworkDevice, this::onBodyworkCallback)) {
            logger.info("  Bodywork listener registered");
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
        if (BydDeviceHelper.registerListener(instrumentDevice, this::onInstrumentCallback)) {
            logger.info("  Instrument listener registered (external charging power)");
            count++;
        }
        if (BydDeviceHelper.registerListener(statisticDevice, this::onGenericCallback)) {
            logger.info("  Statistic listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(lightDevice, this::onGenericCallback)) {
            logger.info("  Light listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(radarDevice, this::onGenericCallback)) {
            logger.info("  Radar listener registered");
            count++;
        }

        // Display-only devices — no periodic polling, listener-driven only.
        // These update the snapshot when BYD HAL pushes CAN bus state changes.
        if (BydDeviceHelper.registerListener(doorLockDevice, this::onDisplayCallback)) {
            logger.info("  DoorLock listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(tyreDevice, this::onDisplayCallback)) {
            logger.info("  Tyre listener registered");
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
        if (snapshot.get() != null) {
            BydVehicleData.Builder b = snapshot.get().toBuilder();
            collectBodywork(b);
            snapshot.set(b.build());
        }
    }

    /**
     * Callback for display-only devices (DoorLock, Tyre, AC, Sensor, Energy, Power).
     * 
     * These listeners exist solely to keep the BYD device singletons' internal caches
     * fresh. We do NOT re-poll devices here — the snapshot is updated on-demand when
     * the HTTP API calls collectAllFull(), or when the bodywork listener fires.
     * 
     * This avoids the 10Hz SensorDevice postEvent from triggering expensive
     * full display sweeps (door×7, tyre×4, seatbelt×5, AC×5, light×8, radar, etc.)
     */
    private void onDisplayCallback(String method, Object[] args) {
        // No-op: listener registration keeps BYD HAL singletons' caches alive.
        // Actual data is read on-demand via collectAllFull().
    }

    // Throttle for generic listener callbacks (StatisticDevice fires at ~10Hz on CAN bus)
    private volatile long lastGenericCallbackTime = 0;

    private void onGenericCallback(String method, Object[] args) {
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

    private void onChargingCallback(String method, Object[] args) {
        // Handle the new-style BYDAutoEvent callbacks
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                double dVal = BydDeviceHelper.getDoubleValue(eventValue);
                
                // If this looks like a charging power value (reasonable kW range)
                // Only accept if the car is actually charging — phantom 0.1 kW values
                // come from the CAN bus even when the charger is unplugged.
                if (!Double.isNaN(dVal) && Math.abs(dVal) > 0.1 && Math.abs(dVal) < 500) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        boolean isCharging = current.chargingState == 1;  // CHARGING_BATTERY_STATE_CHARGING
                        if (isCharging) {
                            snapshot.set(current.toBuilder().chargingPowerKw(dVal).build());
                            long now = System.currentTimeMillis();
                            if (now - lastChargingPowerLogTime > 30_000) {
                                lastChargingPowerLogTime = now;
                                logger.info("Charging power: " + String.format("%.1f", dVal) + " kW (event " + eventId + ")");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Charging event parse error: " + e.getMessage());
            }
        }
        if ("onChargingPowerChanged".equals(method) && args != null && args.length > 0) {
            try {
                double power = ((Number) args[0]).doubleValue();
                if (Math.abs(power) < 500 && power != 0) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().chargingPowerKw(power).build());
                        logger.info("Charging power via typed callback: " + power + " kW");
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        // Listener-driven: the specific event value was already captured above.
        // Skip full device re-collection — the 5s polling timer handles periodic refresh.
    }

    private void onInstrumentCallback(String method, Object[] args) {
        // Handle the new-style BYDAutoEvent callbacks
        if ("onDataEventChanged".equals(method) && args != null && args.length >= 2) {
            try {
                int eventId = ((Number) args[0]).intValue();
                Object eventValue = args[1];
                double dVal = BydDeviceHelper.getDoubleValue(eventValue);
                int iVal = BydDeviceHelper.getIntValue(eventValue);
                
                // Only store non-zero values as external charging power.
                // Event 315621436 on some models always returns 0 — don't let it
                // overwrite a valid chargingPowerKw from the ChargingDevice callback.
                if (!Double.isNaN(dVal) && dVal > 0.1 && dVal < 500) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().externalChargingPowerKw(dVal).build());
                        logger.info("External charging power from event " + eventId + ": " + dVal + " kW");
                    }
                }
            } catch (Exception e) {
                logger.debug("Instrument event parse error: " + e.getMessage());
            }
        }
        if ("onExternalChargingPowerChanged".equals(method) && args != null && args.length > 0) {
            try {
                double power = ((Number) args[0]).doubleValue();
                if (power > 0.1 && power <= 500) {
                    BydVehicleData current = snapshot.get();
                    if (current != null) {
                        snapshot.set(current.toBuilder().externalChargingPowerKw(power).build());
                        logger.info("External charging power via typed callback: " + power + " kW");
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
        // Listener-driven: the specific event value was already captured above.
        // Skip full device re-collection — the 5s polling timer handles periodic refresh.
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
