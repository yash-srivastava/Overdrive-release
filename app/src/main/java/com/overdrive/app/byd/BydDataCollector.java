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

        // Read initial values
        collectAll();

        // Register listeners
        registerAllListeners();

        // Start periodic polling to keep data fresh (listeners may not fire for all values)
        startPolling();

        long elapsed = System.currentTimeMillis() - start;
        logger.info("=== BYD Data Collector Ready (" + elapsed + "ms) ===");
        initialized = true;
    }

    private java.util.concurrent.ScheduledExecutorService pollScheduler;
    private static final long POLL_INTERVAL_MS = 5000; // 5 seconds
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

    /**
     * Collect all data from all devices into a new snapshot.
     * Safe to call from any thread.
     */
    public void collectAll() {
        BydVehicleData.Builder b = (snapshot.get() != null) ? snapshot.get().toBuilder() : new BydVehicleData.Builder();
        b.availableDevices(availableDevices.toArray(new String[0]));
        b.unavailableDevices(unavailableDevices.toArray(new String[0]));

        collectBodywork(b);
        collectSpeed(b);
        collectEngine(b);
        collectStatistic(b);
        collectCharging(b);
        collectInstrument(b);
        collectOta(b);
        collectGearbox(b);
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
    }

    private void collectBodywork(BydVehicleData.Builder b) {
        if (bodyworkDevice == null) return;
        try {
            // VIN
            Object vin = BydDeviceHelper.callGetter(bodyworkDevice, "getAutoVIN");
            if (vin instanceof String) b.vin((String) vin);

            // Battery SOC (raw / 10.0)
            Object socRaw = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerValue");
            if (socRaw instanceof Number) {
                double rawVal = ((Number) socRaw).doubleValue();
                // Some models return raw * 10 (e.g., 410 = 41%), others return direct percentage
                double soc = rawVal > 100 ? rawVal / 10.0 : rawVal;
                if (soc > 0 && soc <= 100) b.socPercent(soc);
            }

            // Battery SOC HEV
            Object hev = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryPowerHEV");
            if (hev instanceof Number) {
                double hevVal = ((Number) hev).doubleValue();
                if (hevVal >= 0) b.socHevPercent(hevVal);
            }

            // Battery capacity
            Object cap = BydDeviceHelper.callGetter(bodyworkDevice, "getBatteryCapacity");
            if (cap instanceof Number) {
                double capVal = ((Number) cap).doubleValue();
                if (capVal > 0) b.capacityAh(capVal);
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
                if (rangeVal >= 0 && rangeVal <= 1016) b.bodyworkRangeKm(rangeVal);
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
                if (v != BydFeatureIds.SDK_NOT_AVAILABLE) b.speedKmh(v);
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
            if (mileage instanceof Number) b.totalMileageKm(((Number) mileage).intValue());

            Object evMileage = BydDeviceHelper.callGetter(statisticDevice, "getEVMileageValue");
            if (evMileage instanceof Number) b.evMileageKm(((Number) evMileage).intValue());

            Object elecPct = BydDeviceHelper.callGetter(statisticDevice, "getElecPercentageValue");
            // This is the SOC from statistic device — use if bodywork didn't provide it
            if (elecPct instanceof Number && Double.isNaN(b.socPercent)) {
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
            if (elecRange instanceof Number) b.elecRangeKm(((Number) elecRange).intValue());

            Object fuelRange = BydDeviceHelper.callGetter(statisticDevice, "getFuelDrivingRangeValue");
            if (fuelRange instanceof Number) b.fuelRangeKm(((Number) fuelRange).intValue());

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
            if (battState instanceof Number) b.chargingState(((Number) battState).intValue());
            // Gun connection state (0=none, 1=AC, 2=AC fast, 3=DC, 4=V2L)
            Object gunState = BydDeviceHelper.callGetter(chargingDevice, "getChargingGunState");
            if (gunState instanceof Number) b.chargingGunState(((Number) gunState).intValue());
            // Charger work state
            Object charger = BydDeviceHelper.callGetter(chargingDevice, "getChargerWorkState");
            if (charger instanceof Number) b.chargerWorkState(((Number) charger).intValue());
            // Charging power
            Object power = BydDeviceHelper.callGetter(chargingDevice, "getChargingPower");
            if (power instanceof Number) {
                double kw = ((Number) power).doubleValue();
                if (Math.abs(kw) < 500) b.chargingPowerKw(kw);
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
            Object extPower = BydDeviceHelper.callGetter(instrumentDevice, "getExternalChargingPower");
            if (extPower instanceof Number) {
                double p = ((Number) extPower).doubleValue();
                if (p >= -500 && p <= 500) b.externalChargingPowerKw(p);
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
            Object remain = BydDeviceHelper.callGetter(powerDevice, "getBatteryRemainPowerEV");
            if (remain instanceof Number) {
                double kwh = ((Number) remain).doubleValue();
                if (kwh > 0 && kwh < 200) b.remainKwh(kwh);
            }
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
            
            // SOC fallback: EnergyDevice.getElecPercentageValue() — try if bodywork and statistic didn't provide SOC
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
        if (BydDeviceHelper.registerListener(gearboxDevice, this::onGenericCallback)) {
            logger.info("  Gearbox listener registered");
            count++;
        }
        if (BydDeviceHelper.registerListener(chargingDevice, this::onGenericCallback)) {
            logger.info("  Charging listener registered");
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

        logger.info("Listeners registered: " + count);
    }

    private void onBodyworkCallback(String method, Object[] args) {
        if (snapshot.get() != null) {
            BydVehicleData.Builder b = snapshot.get().toBuilder();
            collectBodywork(b);
            snapshot.set(b.build());
        }
    }

    private void onGenericCallback(String method, Object[] args) {
        // Listeners trigger collectAll on data changes — no logging needed (polling handles freshness)
        if (method.contains("Changed") || method.contains("changed")) {
            collectAll();
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
        else logger.warn("  SOC: UNAVAILABLE (bodywork/statistic/energy all returned blank)");
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
