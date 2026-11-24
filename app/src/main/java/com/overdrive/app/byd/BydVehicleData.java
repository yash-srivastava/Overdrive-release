package com.overdrive.app.byd;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Immutable snapshot of all BYD vehicle data.
 * Thread-safe — created via Builder, read from any thread.
 */
public class BydVehicleData {

    // Sentinel for unavailable numeric values
    public static final double NaN = Double.NaN;
    public static final int UNAVAILABLE = Integer.MIN_VALUE;
    public static final int LIGHT_KNOWN_NONE = 0;
    public static final int LIGHT_KNOWN_LOW_BEAM = 1;
    public static final int LIGHT_KNOWN_HIGH_BEAM = 1 << 1;
    public static final int LIGHT_KNOWN_FRONT_FOG = 1 << 2;
    public static final int LIGHT_KNOWN_REAR_FOG = 1 << 3;
    public static final int LIGHT_KNOWN_TURN_HAZARD = 1 << 4;
    public static final int LIGHT_KNOWN_DRL = 1 << 5;
    public static final int LIGHT_KNOWN_ALL =
            LIGHT_KNOWN_LOW_BEAM
                    | LIGHT_KNOWN_HIGH_BEAM
                    | LIGHT_KNOWN_FRONT_FOG
                    | LIGHT_KNOWN_REAR_FOG
                    | LIGHT_KNOWN_TURN_HAZARD
                    | LIGHT_KNOWN_DRL;
    // Connected-unit BYDAutoTyreDevice contract.
    public static final int TYRE_PRESSURE_STATE_NORMAL = 0;
    public static final int TYRE_PRESSURE_STATE_OVERPRESSURE = 1;
    public static final int TYRE_PRESSURE_STATE_UNDERPRESSURE = 2;

    public static boolean isTyreOverpressureState(int state) {
        return state == TYRE_PRESSURE_STATE_OVERPRESSURE;
    }

    public static boolean isTyreUnderpressureState(int state) {
        return state == TYRE_PRESSURE_STATE_UNDERPRESSURE;
    }

    // ==================== IDENTITY ====================
    public final String vin;

    // ==================== BATTERY ====================
    public final double socPercent;       // 0-100
    public final double socHevPercent;    // HEV SOC
    public final int socTargetPercent;    // configured SOC target, 15-70
    public final double capacityAh;
    public final double remainKwh;        // remaining energy
    public final double voltage12v;       // 12V battery volts
    /** Wall-clock time when {@link #voltage12v} was last read successfully; 0 when never. */
    public final long voltage12vAtMs;
    public final int voltageLevelRaw;     // LOW/NORMAL/INVALID

    // ==================== THERMAL ====================
    public final double highCellTempC;    // highest cell temp (°C)
    public final double lowCellTempC;     // lowest cell temp (°C)
    public final double avgCellTempC;     // average pack temp (°C)
    public final double waterTempC;       // coolant temp
    public final double outsideTempC;     // external temp
    public final double insideTempC;      // cabin temp
    public final double bodyworkBattTempC; // battery temp from bodywork device

    // ==================== CELL VOLTAGE ====================
    public final double highCellVoltage;  // V
    public final double lowCellVoltage;   // V

    // ==================== SPEED ====================
    public final double speedKmh;
    public final int accelPercent;
    public final int brakePercent;

    // ==================== MOTOR ====================
    public final int frontMotorSpeed;     // RPM
    public final int rearMotorSpeed;      // RPM
    public final double frontMotorTorque; // Nm (negated from SDK)
    public final int engineSpeedRpm;
    public final double enginePowerKw;
    /**
     * Wall-clock ms when {@link #enginePowerKw} was last written by a LIVE read (either
     * collectEngine path, or the generic-event listener). 0 = never / not applicable.
     *
     * <p>Exists because the Builder is seeded via {@code toBuilder()}, so the value itself is
     * non-NaN forever after the first successful read — which made every downstream freshness
     * check meaningless. {@code ChargingDetector} used to stamp its own clock on any non-NaN
     * push, so a value of arbitrary age looked 0 ms old and its 15 s freshness window could
     * never expire. Carrying the age WITH the value is the only way a consumer can tell a live
     * reading from a carried-forward one; consumers must prefer this over their own clock.
     */
    public final long enginePowerAtMs;
    /**
     * When {@link #clusterChargePowerKw} was last written by a LIVE read; 0 when never.
     *
     * <p>Needed because {@code build()} restamps {@link #timestamp} on EVERY snapshot, including ones
     * produced by unrelated callbacks — while {@code toBuilder()} carries this field forward untouched.
     * Anything measuring how long the cluster value has held steady must use this, or an unrelated
     * callback makes a frozen value look freshly observed.
     */
    public final long clusterChargePowerAtMs;

    // ==================== ENERGY ====================
    public final int energyMode;          // EV/HEV
    public final int operationMode;       // ECO/SPORT/NORMAL
    public final double totalElecCon;     // total electricity consumed
    public final double totalFuelCon;     // total fuel consumed (litres, lifetime)
    // Average petrol consumption in L/100km straight from the statistic HAL
    // (getTotalFuelConPHMValue, SDK range 0.0-51.1, no scaling). Lifetime
    // average — the vehicle's own figure, so it agrees with the cluster.
    // NaN on BEVs / trims that don't report it.
    public final double avgFuelConPer100Km;
    // Electricity-consumption rates straight from the vehicle, in kWh/100km, so a
    // displayed average matches the cluster instead of being re-derived from a
    // coarse SoC delta. NaN when the trim doesn't report them.
    public final double avgElecConPer100Km;   // lifetime average
    public final double lastElecConPer100Km;  // previous trip's average

    // ==================== RANGE ====================
    public final int elecRangeKm;
    public final int fuelRangeKm;
    public final int bodyworkRangeKm;     // range from bodywork device
    public final double fuelPercent;      // fuel tank level % (PHEV only, -1 = unavailable)

    // ==================== MILEAGE ====================
    public final int totalMileageKm;
    public final int evMileageKm;
    // Lifetime distance driven with the engine contributing (PHEV). The EV/HEV
    // split of totalMileageKm; UNAVAILABLE on a BEV or a trim without it.
    public final int hevMileageKm;

    // ==================== CHARGING ====================
    public final int chargingState;
    /** Manual traction-battery preheating feedback. 0 off, 1 heating, 2 completed. */
    public final int batteryPreheatState;
    public final long batteryPreheatObservedAtMs;

    public static boolean isBatteryPreheatState(int state) {
        return state >= 0 && state <= 2;
    }

    public boolean hasFreshBatteryPreheat(long nowMs) {
        long age = nowMs - batteryPreheatObservedAtMs;
        return isBatteryPreheatState(batteryPreheatState) && batteryPreheatObservedAtMs > 0L
                && age >= 0L && age < 2 * BydDataCollector.POLL_INTERVAL_PARKED_MS;
    }
    /** Wall-clock time when {@link #chargingState} last changed; 0 when never observed. */
    public final long chargingStateAtMs;
    public final int chargingGunState;
    public final int chargerWorkState;
    public final int chargingMode;        // SDK getChargingMode() raw (AC vs DC vs wireless — model-specific)
    /** Dedicated charging-device property 740506, already normalized by the framework in kW. */
    public final double chargingPowerKw;
    /** Observation time carried with {@link #chargingPowerKw}; 0 means unavailable/unknown age. */
    public final long chargingPowerAtMs;
    /** Last material value movement, distinct from repeated callback/poll observations. */
    public final long chargingPowerChangedAtMs;
    public final double externalChargingPowerKw;
    /** Observation time carried with {@link #externalChargingPowerKw}; 0 means unavailable/unknown age. */
    public final long externalChargingPowerAtMs;
    public final long externalChargingPowerChangedAtMs;
    /**
     * Raw {@code InstrumentDevice.getChargePower()} value.
     *
     * <p>Firmware-dependent: it may already be kW or may require a session-proved divisor. Consumers
     * must use {@code VehicleDataMonitor}'s resolved {@code ChargingStateData}, never this field
     * directly as measured power.
     */
    public final double chargePowerKw;
    /** Observation time carried with {@link #chargePowerKw}; 0 means unavailable/unknown age. */
    public final long chargePowerAtMs;
    public final long chargePowerChangedAtMs;
    /** Charge power as the instrument cluster reports it, read from feature id 0x32300018
     *  (Instrument.CHARGING_CHARGE_POWER_DD). It is a guarded fallback behind the dedicated
     *  charging-device kW signal because this field can retain an idle value and its scale varies
     *  by firmware. Kept separate from {@link #chargePowerKw} and
     *  {@link #externalChargingPowerKw} so runtime classification can evaluate each source
     *  independently without one raw read clobbering another. */
    public final double clusterChargePowerKw;
    public final long clusterChargePowerChangedAtMs;
    final double chargingPowerLastObservedKw;
    final double externalChargingPowerLastObservedKw;
    final double chargePowerLastObservedKw;
    final double clusterChargePowerLastObservedKw;
    public final double hvPackVoltage;    // HV battery pack voltage (V), from CAN event
    public final double hvPackCurrentAmps; // Positive=discharge, negative=regen/charging
    public final double hvBatteryPowerKw;  // Signed V*A/1000 from the same observation pair

    // ==================== GEAR ====================
    public final int gearMode;

    // ==================== TYRES ====================
    public final int[] tyrePressure;      // [FL, FR, RL, RR] in kPa (raw int from BYDAutoTyreDevice.getTyrePressureValue)
    public final int[] tyrePressureState; // [FL, FR, RL, RR] — BYDAutoTyreDevice state constants above
    public final int[] tyreAirLeakState;  // [FL, FR, RL, RR] — 0=Normal, 1=Slow leak, 2=Fast leak
    public final int[] tyreSignalState;   // [FL, FR, RL, RR] — 0=Signal OK, 1=Signal Error
    public final int[] tyreTemperature;   // [FL, FR, RL, RR] in °C; UNAVAILABLE until TPMS fires
    public final int tyreSystemState;     // overall TPMS health (raw enum)
    public final int tyreTemperatureState;// overall TPMS temperature warning (raw enum)

    // ==================== DOORS ====================
    public final int[] doorLockStatus;    // [1-7]

    // ==================== WINDOWS ====================
    public final int[] windowOpenPercent; // [1-6]

    // ==================== LIGHTS ====================
    public final int leftTurnState;
    public final int rightTurnState;
    public final boolean lowBeam;
    public final boolean highBeam;
    public final boolean rearFog;
    public final boolean frontFog;
    public final boolean hazard;
    public final boolean dayTimeLight;
    private final int lightKnownMask;
    // Interior ambient (atmosphere) light colour: 1-based index into the fixed
    // 31-colour palette (LightConstants.AMBIENT_COLOURS).
    public final int ambientColour;
    public final boolean ambientColourKnown;
    // Interior ambient main switch: 1 = on, 0 = off, UNAVAILABLE when this trim reports
    // neither the Light-device status feature nor the atmosphere_lamp provider flag. Kept as
    // a tri-state int (not a boolean) so an unreadable switch cannot masquerade as "off" —
    // consumers skip UNAVAILABLE rather than publishing a wrong state.
    public final int ambientEnabled;
    // Cabin (reading) light: 1 = on, 0 = off, UNAVAILABLE when the trim does not report it.
    public final int readingLight;

    // ==================== ADAS ====================
    public final boolean speedLimitWarning;
    public final boolean speedLimitWarningKnown;

    // ==================== Setting ====================
    public final int childPresenceDetection;

    // ==================== SEATBELTS ====================
    public final int[] seatbeltStatus;    // [1-5]

    // ==================== SEATS ====================
    public final int[] seatHeat;    // [driver, passenger] — 0=off, 1=low, 2=high
    public final int[] seatCool;    // [driver, passenger] — 0=off, 1=low, 2=high
    /** Per-seat ventilation capability: 1=supported, 0=unsupported, UNAVAILABLE=unknown. */
    public final int[] seatVentilationSupport;
    /** Steering-wheel heater, raw setting-HAL domain: 2=on, 1=off, UNAVAILABLE=not read. */
    public final int steeringWheelHeat;
    /**
     * Wall-clock ms when every seat heat/vent channel was read successfully in
     * one collector pass. Zero means fallback zeros may be present and the
     * values must not be used for a cloud composite command.
     */
    public final long seatClimateAtMs;

    // ==================== CLIMATE ====================
    public final int acStartState;
    public final int acCycleMode;
    public final int acWindMode;
    public final int acFanLevel;
    public final int tempUnit;
    // AC temperature SETPOINT (the dial), in whatever unit tempUnit says — NOT insideTempC,
    // which is the MEASURED cabin air. Driver = area 1, passenger = area 2.
    public final int acSetpointDriver;
    public final int acSetpointPassenger;

    // ==================== SENSOR ====================
    public final double slopeDegrees;

    // ==================== POWER ====================
    public final int powerLevel;
    public final int mcuStatus;

    // ==================== ALARM ====================
    public final int emergencyAlarmState;

    // ==================== RADAR ====================
    public final int[] radarDistances;    // 9 sensors

    // ==================== EXTENDED BATTERY ====================
    public final double sohPercent;           // OEM SOH from STATISTIC_BATTERY_HEALTHY_INDEX
    public final int keyBatteryLevel;         // 0=low, 1=normal
    public final int battery12vLevel;         // LOW/NORMAL/INVALID from bodywork

    // ==================== KEY PROXIMITY ====================
    // Discrete proximity / authentication signals from the BYD body controller.
    // Semantics are model-specific; values are the raw int from the SDK.
    public final int keyStartState;            // SettingDevice.getStartKeyState() — key recognized for start
    public final int keyMissingInd;            // SettingDevice.getMissKeyInd() — "key missing" indicator
    public final int keyBtLowPowerMode;        // SettingDevice.getIKEYBTLowPowerMode() — BLE-key low-power flag
    public final int keyPowerLowInd;           // SettingDevice.getKeyPowerLowInd() — fob battery low
    public final int keyDetectionReminder;    // InstrumentDevice.getKeyDetectionReminder()
    public final int smartKeyWarnState;        // InstrumentDevice.getSmartKeySysWarnLightState()

    // ==================== EXTENDED THERMAL ====================
    public final double insideTempCelsius;    // Cabin temp from AC_TEMP_INSIDE

    // ==================== EXTENDED CHARGING ====================
    public final int chargingRestTimeHours;
    public final int chargingRestTimeMinutes;
    public final int chargingPercent;         // charging session progress % (from chargingDevice or instrument feature ID 842006544)
    public final int chargingType;            // 0=DEFAULT, 3=VTOG (vehicle-to-grid)
    public final boolean vtolCharging;        // V2L/V2G active
    public final double chargingCapacityKwh;  // from chargingDevice.getChargingCapacity()
    public final int wirelessChargingLeftState;
    public final int wirelessChargingRightState;

    // ==================== EXTENDED DRIVING ====================
    public final double drivingTimeHours;
    public final double last50KmConsumption;  // kWh/100km
    public final double steeringAngleDegrees;
    public final int autoSystemState;         // 0=normal, 1=set_secure, 2=start_secure

    // ==================== EXTENDED TRIP ====================
    public final double currentTripMileageKm;
    public final double currentTripTimeHours;
    public final double currentTripConsumptionKwh;

    // ==================== EXTENDED ENGINE ====================
    public final int engineCoolantLevel;      // 0=normal, 1=low
    public final int oilLevel;                // 0-254
    public final String engineCode;           // e.g. "BYD473QF"

    // ==================== EXTENDED BODYWORK ====================
    public final int wiperState;
    // Auto-wiper (rain-sensing) engaged: 1=on, 0=off. Closest "it's raining" proxy —
    // no rain-intensity sensor exists on this platform. Read from the bodywork device.
    public final int autoWiperState;
    // Auto-headlight (light-sensor-driven) engaged: 1=on, 0=off. The usable "it's dark"
    // proxy; there is no lux value on this platform. Read from the light device.
    public final int lightAutoStatus;
    public final int sunroofState;
    public final int sunroofPosition;
    public final int sunshadePercent;
    public final int wirelessChargingStatus;
    public final boolean driftModeEnabled;
    public final boolean driftModeKnown;

    // ==================== EXTENDED SAFETY ====================
    // Seat occupancy. ONE slot: index 0 = FRONT PASSENGER (getPassengerStatus area 1, off the
    // safety-belt device — not OMS/ADAS). 1=occupied / 0=empty; never a sentinel (the producer
    // returns null instead). There is no driver slot — that seat has no occupancy sensor.
    public final int[] passengerDetection;

    // ==================== EXTENDED AIR QUALITY ====================
    public final int pm25Inside;
    public final int pm25Outside;

    // ==================== META ====================
    /** Four missed 90-second parked polls before a cabin observation is considered stale. */
    public static final long CABIN_TEMP_MAX_AGE_MS = 6 * 60_000L;

    /**
     * Source observation time for {@link #insideTempC} (epoch ms), or 0 if never observed.
     * Needed because insideTempC is carried forward by {@link #toBuilder()} and never reset to
     * NaN, so its presence alone cannot distinguish a current HAL/cloud observation from an older
     * value. Consumers that must not act on stale cabin data compare against this.
     */
    public final long insideTempReadAt;
    public final long timestamp;
    public final String[] availableDevices;
    public final String[] unavailableDevices;

    private BydVehicleData(Builder b) {
        this.vin = b.vin;
        this.socPercent = b.socPercent;
        this.socHevPercent = b.socHevPercent;
        this.socTargetPercent = b.socTargetPercent;
        this.capacityAh = b.capacityAh;
        this.remainKwh = b.remainKwh;
        this.voltage12v = b.voltage12v;
        this.voltage12vAtMs = b.voltage12vAtMs;
        this.voltageLevelRaw = b.voltageLevelRaw;
        this.highCellTempC = b.highCellTempC;
        this.lowCellTempC = b.lowCellTempC;
        this.avgCellTempC = b.avgCellTempC;
        this.waterTempC = b.waterTempC;
        this.outsideTempC = b.outsideTempC;
        this.insideTempC = b.insideTempC;
        this.bodyworkBattTempC = b.bodyworkBattTempC;
        this.highCellVoltage = b.highCellVoltage;
        this.lowCellVoltage = b.lowCellVoltage;
        this.speedKmh = b.speedKmh;
        this.accelPercent = b.accelPercent;
        this.brakePercent = b.brakePercent;
        this.frontMotorSpeed = b.frontMotorSpeed;
        this.rearMotorSpeed = b.rearMotorSpeed;
        this.frontMotorTorque = b.frontMotorTorque;
        this.engineSpeedRpm = b.engineSpeedRpm;
        this.enginePowerKw = b.enginePowerKw;
        this.enginePowerAtMs = b.enginePowerAtMs;
        this.clusterChargePowerAtMs = b.clusterChargePowerAtMs;
        this.energyMode = b.energyMode;
        this.operationMode = b.operationMode;
        this.totalElecCon = b.totalElecCon;
        this.totalFuelCon = b.totalFuelCon;
        this.avgFuelConPer100Km = b.avgFuelConPer100Km;
        this.avgElecConPer100Km = b.avgElecConPer100Km;
        this.lastElecConPer100Km = b.lastElecConPer100Km;
        this.elecRangeKm = b.elecRangeKm;
        this.fuelRangeKm = b.fuelRangeKm;
        this.fuelPercent = b.fuelPercent;
        this.bodyworkRangeKm = b.bodyworkRangeKm;
        this.totalMileageKm = b.totalMileageKm;
        this.evMileageKm = b.evMileageKm;
        this.hevMileageKm = b.hevMileageKm;
        this.chargingState = b.chargingState;
        this.batteryPreheatState = b.batteryPreheatState;
        this.batteryPreheatObservedAtMs = b.batteryPreheatObservedAtMs;
        this.chargingStateAtMs = b.chargingStateAtMs;
        this.chargingGunState = b.chargingGunState;
        this.chargerWorkState = b.chargerWorkState;
        this.chargingMode = b.chargingMode;
        this.chargingPowerKw = b.chargingPowerKw;
        this.chargingPowerAtMs = b.chargingPowerAtMs;
        this.chargingPowerChangedAtMs = b.chargingPowerChangedAtMs;
        this.externalChargingPowerKw = b.externalChargingPowerKw;
        this.externalChargingPowerAtMs = b.externalChargingPowerAtMs;
        this.externalChargingPowerChangedAtMs = b.externalChargingPowerChangedAtMs;
        this.chargePowerKw = b.chargePowerKw;
        this.chargePowerAtMs = b.chargePowerAtMs;
        this.chargePowerChangedAtMs = b.chargePowerChangedAtMs;
        this.clusterChargePowerKw = b.clusterChargePowerKw;
        this.clusterChargePowerChangedAtMs = b.clusterChargePowerChangedAtMs;
        this.chargingPowerLastObservedKw = b.chargingPowerLastObservedKw;
        this.externalChargingPowerLastObservedKw = b.externalChargingPowerLastObservedKw;
        this.chargePowerLastObservedKw = b.chargePowerLastObservedKw;
        this.clusterChargePowerLastObservedKw = b.clusterChargePowerLastObservedKw;
        this.hvPackVoltage = b.hvPackVoltage;
        this.hvPackCurrentAmps = b.hvPackCurrentAmps;
        this.hvBatteryPowerKw = b.hvBatteryPowerKw;
        this.gearMode = b.gearMode;
        this.tyrePressure = b.tyrePressure;
        this.tyrePressureState = b.tyrePressureState;
        this.tyreAirLeakState = b.tyreAirLeakState;
        this.tyreSignalState = b.tyreSignalState;
        this.tyreTemperature = b.tyreTemperature;
        this.tyreSystemState = b.tyreSystemState;
        this.tyreTemperatureState = b.tyreTemperatureState;
        this.doorLockStatus = b.doorLockStatus;
        this.windowOpenPercent = b.windowOpenPercent;
        this.leftTurnState = b.leftTurnState;
        this.rightTurnState = b.rightTurnState;
        this.lowBeam = b.lowBeam;
        this.highBeam = b.highBeam;
        this.rearFog = b.rearFog;
        this.frontFog = b.frontFog;
        this.hazard = b.hazard;
        this.dayTimeLight = b.dayTimeLight;
        this.lightKnownMask = b.lightKnownMask;
        this.ambientColour = b.ambientColour;
        this.ambientColourKnown = b.ambientColourKnown;
        this.ambientEnabled = b.ambientEnabled;
        this.readingLight = b.readingLight;
        this.speedLimitWarning = b.speedLimitWarning;
        this.speedLimitWarningKnown = b.speedLimitWarningKnown;
        this.childPresenceDetection = b.childPresenceDetection;
        this.seatbeltStatus = b.seatbeltStatus;
        this.seatHeat = b.seatHeat;
        this.seatCool = b.seatCool;
        this.seatVentilationSupport = b.seatVentilationSupport;
        this.steeringWheelHeat = b.steeringWheelHeat;
        this.seatClimateAtMs = b.seatClimateAtMs;
        this.acStartState = b.acStartState;
        this.acCycleMode = b.acCycleMode;
        this.acWindMode = b.acWindMode;
        this.acFanLevel = b.acFanLevel;
        this.tempUnit = b.tempUnit;
        this.acSetpointDriver = b.acSetpointDriver;
        this.acSetpointPassenger = b.acSetpointPassenger;
        this.slopeDegrees = b.slopeDegrees;
        this.powerLevel = b.powerLevel;
        this.mcuStatus = b.mcuStatus;
        this.emergencyAlarmState = b.emergencyAlarmState;
        this.radarDistances = b.radarDistances;
        // Extended fields
        this.sohPercent = b.sohPercent;
        this.keyBatteryLevel = b.keyBatteryLevel;
        this.battery12vLevel = b.battery12vLevel;
        this.keyStartState = b.keyStartState;
        this.keyMissingInd = b.keyMissingInd;
        this.keyBtLowPowerMode = b.keyBtLowPowerMode;
        this.keyPowerLowInd = b.keyPowerLowInd;
        this.keyDetectionReminder = b.keyDetectionReminder;
        this.smartKeyWarnState = b.smartKeyWarnState;
        this.insideTempCelsius = b.insideTempCelsius;
        this.chargingRestTimeHours = b.chargingRestTimeHours;
        this.chargingRestTimeMinutes = b.chargingRestTimeMinutes;
        this.chargingPercent = b.chargingPercent;
        this.chargingType = b.chargingType;
        this.vtolCharging = b.vtolCharging;
        this.chargingCapacityKwh = b.chargingCapacityKwh;
        this.wirelessChargingLeftState = b.wirelessChargingLeftState;
        this.wirelessChargingRightState = b.wirelessChargingRightState;
        this.drivingTimeHours = b.drivingTimeHours;
        this.last50KmConsumption = b.last50KmConsumption;
        this.steeringAngleDegrees = b.steeringAngleDegrees;
        this.autoSystemState = b.autoSystemState;
        this.currentTripMileageKm = b.currentTripMileageKm;
        this.currentTripTimeHours = b.currentTripTimeHours;
        this.currentTripConsumptionKwh = b.currentTripConsumptionKwh;
        this.engineCoolantLevel = b.engineCoolantLevel;
        this.oilLevel = b.oilLevel;
        this.engineCode = b.engineCode;
        this.wiperState = b.wiperState;
        this.autoWiperState = b.autoWiperState;
        this.lightAutoStatus = b.lightAutoStatus;
        this.sunroofState = b.sunroofState;
        this.sunroofPosition = b.sunroofPosition;
        this.sunshadePercent = b.sunshadePercent;
        this.wirelessChargingStatus = b.wirelessChargingStatus;
        this.driftModeEnabled = b.driftModeEnabled;
        this.driftModeKnown = b.driftModeKnown;
        this.passengerDetection = b.passengerDetection;
        this.pm25Inside = b.pm25Inside;
        this.pm25Outside = b.pm25Outside;
        this.insideTempReadAt = b.insideTempReadAt;
        this.timestamp = b.timestamp;
        this.availableDevices = b.availableDevices;
        this.unavailableDevices = b.unavailableDevices;
    }

    /** Whether the cabin value came from a recent HAL or cloud observation. */
    public boolean hasFreshCabinTemperature() {
        if (Double.isNaN(insideTempC) || insideTempReadAt <= 0L) return false;
        return System.currentTimeMillis() - insideTempReadAt <= CABIN_TEMP_MAX_AGE_MS;
    }

    /** Cell voltage delta (imbalance indicator) */
    public double getCellVoltageDelta() {
        if (Double.isNaN(highCellVoltage) || Double.isNaN(lowCellVoltage)) return Double.NaN;
        return highCellVoltage - lowCellVoltage;
    }

    /** Cell temperature delta */
    public double getCellTempDelta() {
        if (Double.isNaN(highCellTempC) || Double.isNaN(lowCellTempC)) return Double.NaN;
        return highCellTempC - lowCellTempC;
    }

    /** Best available battery temperature */
    public double getBestBatteryTemp() {
        if (!Double.isNaN(avgCellTempC)) return avgCellTempC;
        if (!Double.isNaN(highCellTempC)) return highCellTempC;
        if (!Double.isNaN(bodyworkBattTempC)) return bodyworkBattTempC;
        if (!Double.isNaN(waterTempC)) return waterTempC;
        return Double.NaN;
    }

    public boolean isLightKnown(int light) {
        return (lightKnownMask & light) == light;
    }

    int lightKnownMask() {
        return lightKnownMask;
    }

    /** Convert to JSON for API responses */
    public JSONObject toJson() {
        return toJsonForPlatform(
                com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected());
    }

    JSONObject toJsonForPlatform(boolean diLink5) {
        return toJson(true, diLink5);
    }

    private JSONObject toJson(boolean roundDoubles, boolean includeDiLink5Fields) {
        JSONObject j = new JSONObject();
        try {
            if (vin != null) j.put("vin", vin);

            // Battery
            JSONObject batt = new JSONObject();
            putIfValid(batt, "socPercent", socPercent, roundDoubles);
            putIfValid(batt, "socHevPercent", socHevPercent, roundDoubles);
            if (socTargetPercent != UNAVAILABLE) {
                batt.put("socTargetPercent", socTargetPercent);
            }
            putIfValid(batt, "capacityAh", capacityAh, roundDoubles);
            putIfValid(batt, "remainKwh", remainKwh, roundDoubles);
            putIfValid(batt, "voltage12v", voltage12v, roundDoubles);
            if (includeDiLink5Fields) {
                putIfValid(batt, "hvPackVoltage", hvPackVoltage, roundDoubles);
                putIfValid(batt, "hvPackCurrentAmps", hvPackCurrentAmps, roundDoubles);
                putIfValid(batt, "hvBatteryPowerKw", hvBatteryPowerKw, roundDoubles);
            }
            if (voltage12vAtMs > 0L) {
                batt.put("voltage12vAtMs", voltage12vAtMs);
            }
            if (voltageLevelRaw != UNAVAILABLE) batt.put("voltageLevelRaw", voltageLevelRaw);
            j.put("battery", batt);

            // Thermal
            JSONObject therm = new JSONObject();
            putIfValid(therm, "highCellTempC", highCellTempC, roundDoubles);
            putIfValid(therm, "lowCellTempC", lowCellTempC, roundDoubles);
            putIfValid(therm, "avgCellTempC", avgCellTempC, roundDoubles);
            putIfValid(therm, "waterTempC", waterTempC, roundDoubles);
            putIfValid(therm, "outsideTempC", outsideTempC, roundDoubles);
            if (hasFreshCabinTemperature()) {
                putIfValid(therm, "insideTempC", insideTempC, roundDoubles);
            }
            putIfValid(therm, "bodyworkBattTempC", bodyworkBattTempC, roundDoubles);
            putIfValid(therm, "bestBatteryTempC", getBestBatteryTemp(), roundDoubles);
            j.put("thermal", therm);

            // Cell voltage
            JSONObject cellV = new JSONObject();
            putIfValid(cellV, "highV", highCellVoltage, roundDoubles);
            putIfValid(cellV, "lowV", lowCellVoltage, roundDoubles);
            putIfValid(cellV, "deltaV", getCellVoltageDelta(), roundDoubles);
            j.put("cellVoltage", cellV);

            // Speed
            JSONObject spd = new JSONObject();
            putIfValid(spd, "kmh", speedKmh, roundDoubles);
            if (accelPercent != UNAVAILABLE) spd.put("accelPercent", accelPercent);
            if (brakePercent != UNAVAILABLE) spd.put("brakePercent", brakePercent);
            j.put("speed", spd);

            // Motor
            JSONObject mot = new JSONObject();
            if (frontMotorSpeed != UNAVAILABLE) mot.put("frontSpeed", frontMotorSpeed);
            if (rearMotorSpeed != UNAVAILABLE) mot.put("rearSpeed", rearMotorSpeed);
            putIfValid(mot, "frontTorque", frontMotorTorque, roundDoubles);
            if (engineSpeedRpm != UNAVAILABLE) mot.put("engineRpm", engineSpeedRpm);
            putIfValid(mot, "enginePowerKw", enginePowerKw, roundDoubles);
            j.put("motor", mot);

            // Energy
            JSONObject eng = new JSONObject();
            if (energyMode != UNAVAILABLE) eng.put("mode", energyMode);
            if (operationMode != UNAVAILABLE) eng.put("operationMode", operationMode);
            putIfValid(eng, "totalElecCon", totalElecCon, roundDoubles);
            putIfValid(eng, "totalFuelCon", totalFuelCon, roundDoubles);
            putIfValid(eng, "avgFuelConPer100Km", avgFuelConPer100Km, roundDoubles);
            putIfValid(eng, "avgElecConPer100Km", avgElecConPer100Km, roundDoubles);
            putIfValid(eng, "lastElecConPer100Km", lastElecConPer100Km, roundDoubles);
            j.put("energy", eng);

            // Range
            JSONObject rng = new JSONObject();
            if (elecRangeKm != UNAVAILABLE) rng.put("elecKm", elecRangeKm);
            if (fuelRangeKm != UNAVAILABLE) rng.put("fuelKm", fuelRangeKm);
            if (!Double.isNaN(fuelPercent) && fuelPercent >= 0) rng.put("fuelPercent", fuelPercent);
            if (bodyworkRangeKm != UNAVAILABLE) rng.put("bodyworkKm", bodyworkRangeKm);
            j.put("range", rng);

            // Mileage
            JSONObject mil = new JSONObject();
            if (totalMileageKm != UNAVAILABLE) mil.put("totalKm", totalMileageKm);
            if (evMileageKm != UNAVAILABLE) mil.put("evKm", evMileageKm);
            if (hevMileageKm != UNAVAILABLE) mil.put("hevKm", hevMileageKm);
            j.put("mileage", mil);

            // Charging
            JSONObject chg = new JSONObject();
            if (chargingState != UNAVAILABLE) chg.put("state", chargingState);
            if (isBatteryPreheatState(batteryPreheatState)) {
                chg.put("batteryPreheatState", batteryPreheatState);
                chg.put("batteryPreheatObservedAtMs", batteryPreheatObservedAtMs);
            }
            if (chargingGunState != UNAVAILABLE) chg.put("gunState", chargingGunState);
            if (chargerWorkState != UNAVAILABLE) chg.put("chargerState", chargerWorkState);
            if (chargingMode != UNAVAILABLE) chg.put("mode", chargingMode);
            // Keep the legacy diagnostic key for compatibility. This first value is the dedicated
            // framework kW property; the external accessor below remains unit-ambiguous and is
            // classified downstream before it can become a published rate.
            putIfValid(chg, "powerRaw", chargingPowerKw, roundDoubles);
            putIfValid(chg, "externalPowerRaw", externalChargingPowerKw, roundDoubles);
            // DC pack-side (getChargePower). Only emit an in-band value: the getter
            // returns ~359 garbage when idle, which would otherwise mislead anyone
            // reading this diagnostic JSON during a charge test. Same band the
            // consumers (getChargingState / MQTT / ABRP) gate on.
            if (!Double.isNaN(chargePowerKw) && chargePowerKw > 0.1 && chargePowerKw <= 300) {
                putIfValid(chg, "chargePowerKw", chargePowerKw, roundDoubles);
            }
            // Also classifier-managed and unit-unknown here, so name it as a raw diagnostic
            // rather than implying it is the resolved power used by downstream consumers.
            if (!Double.isNaN(clusterChargePowerKw)
                    && clusterChargePowerKw > 0.1 && clusterChargePowerKw <= 300) {
                putIfValid(chg, "clusterChargePowerRaw", clusterChargePowerKw, roundDoubles);
            }
            j.put("charging", chg);

            // Gear
            if (gearMode != UNAVAILABLE) j.put("gearMode", gearMode);

            // Tyres
            if (tyrePressure != null) {
                JSONArray tp = new JSONArray();
                for (int p : tyrePressure) {
                    tp.put(tyreJsonValue(p, includeDiLink5Fields));
                }
                j.put("tyrePressure", tp);
                j.put("tyrePressureUnit", "kPa");
            }
            if (tyrePressureState != null) {
                JSONArray a = new JSONArray();
                for (int v : tyrePressureState) {
                    a.put(tyreJsonValue(v, includeDiLink5Fields));
                }
                j.put("tyrePressureState", a);
            }
            if (tyreAirLeakState != null) {
                JSONArray a = new JSONArray();
                for (int v : tyreAirLeakState) {
                    a.put(tyreJsonValue(v, includeDiLink5Fields));
                }
                j.put("tyreAirLeakState", a);
            }
            if (tyreSignalState != null) {
                JSONArray a = new JSONArray();
                for (int v : tyreSignalState) {
                    a.put(tyreJsonValue(v, includeDiLink5Fields));
                }
                j.put("tyreSignalState", a);
            }
            if (tyreTemperature != null) {
                JSONArray a = new JSONArray();
                for (int v : tyreTemperature) {
                    a.put(v == UNAVAILABLE ? JSONObject.NULL : (Object) v);
                }
                j.put("tyreTemperature", a);
                j.put("tyreTemperatureUnit", "C");
            }
            if (tyreSystemState != UNAVAILABLE) j.put("tyreSystemState", tyreSystemState);
            if (tyreTemperatureState != UNAVAILABLE) j.put("tyreTemperatureState", tyreTemperatureState);

            // Doors
            if (doorLockStatus != null) {
                JSONArray dl = new JSONArray();
                for (int s : doorLockStatus) dl.put(s);
                j.put("doorLockStatus", dl);
            }

            // Windows
            if (windowOpenPercent != null) {
                JSONArray wp = new JSONArray();
                for (int p : windowOpenPercent) wp.put(p);
                j.put("windowOpenPercent", wp);
            }

            // Lights
            JSONObject lt = new JSONObject();
            if ((!includeDiLink5Fields
                    || isLightKnown(LIGHT_KNOWN_TURN_HAZARD))
                    && leftTurnState != UNAVAILABLE) {
                lt.put("leftTurn", leftTurnState);
            }
            if ((!includeDiLink5Fields
                    || isLightKnown(LIGHT_KNOWN_TURN_HAZARD))
                    && rightTurnState != UNAVAILABLE) {
                lt.put("rightTurn", rightTurnState);
            }
            if (!includeDiLink5Fields || isLightKnown(LIGHT_KNOWN_LOW_BEAM)) {
                lt.put("lowBeam", lowBeam);
            }
            if (!includeDiLink5Fields || isLightKnown(LIGHT_KNOWN_HIGH_BEAM)) {
                lt.put("highBeam", highBeam);
            }
            if (!includeDiLink5Fields || isLightKnown(LIGHT_KNOWN_REAR_FOG)) {
                lt.put("rearFog", rearFog);
            }
            if (!includeDiLink5Fields || isLightKnown(LIGHT_KNOWN_FRONT_FOG)) {
                lt.put("frontFog", frontFog);
            }
            if (!includeDiLink5Fields || isLightKnown(LIGHT_KNOWN_TURN_HAZARD)) {
                lt.put("hazard", hazard);
            }
            if (includeDiLink5Fields && isLightKnown(LIGHT_KNOWN_DRL)) {
                lt.put("dayTimeLight", dayTimeLight);
            }
            j.put("lights", lt);

            // Seatbelts
            if (seatbeltStatus != null) {
                // Per-element UNAVAILABLE → null, same as tyreTemperature above. The producer
                // (readSeatbeltPair) returns null only when BOTH seats are unreadable, so a pair
                // like {UNAVAILABLE, 0} IS stored and published — dumping it raw emitted
                // -2147483648 as a seat state, which a consumer reads as a garbage/truthy
                // "buckled" on a safety signal.
                JSONArray sb = new JSONArray();
                for (int s : seatbeltStatus) sb.put(s == UNAVAILABLE ? JSONObject.NULL : (Object) s);
                j.put("seatbeltStatus", sb);
            }
            if (seatHeat != null) {
                JSONArray sh = new JSONArray();
                for (int s : seatHeat) {
                    sh.put(s == UNAVAILABLE ? JSONObject.NULL : (Object) s);
                }
                j.put("seatHeat", sh);
            }
            if (seatCool != null) {
                JSONArray sc = new JSONArray();
                for (int s : seatCool) {
                    sc.put(s == UNAVAILABLE ? JSONObject.NULL : (Object) s);
                }
                j.put("seatCool", sc);
            }
            if (includeDiLink5Fields) {
                if (seatVentilationSupport != null) {
                    JSONArray support = new JSONArray();
                    for (int s : seatVentilationSupport) {
                        support.put(s == UNAVAILABLE ? JSONObject.NULL : (Object) s);
                    }
                    j.put("seatVentilationSupport", support);
                }
                if (steeringWheelHeat != UNAVAILABLE) {
                    j.put("steeringWheelHeat", steeringWheelHeat);
                }
                if (seatClimateAtMs > 0L) j.put("seatClimateAtMs", seatClimateAtMs);
                if (ambientColourKnown) j.put("ambientColour", ambientColour);
                if (ambientEnabled != UNAVAILABLE) j.put("ambientEnabled", ambientEnabled);
                if (readingLight != UNAVAILABLE) j.put("readingLight", readingLight);
                if (speedLimitWarningKnown) {
                    j.put("speedLimitWarning", speedLimitWarning);
                }
                if (childPresenceDetection >= 1
                        && childPresenceDetection <= 3) {
                    j.put("childPresenceDetection", childPresenceDetection);
                }
            }

            // Climate
            JSONObject clim = new JSONObject();
            if (acStartState != UNAVAILABLE) clim.put("acOn", acStartState);
            if (acCycleMode != UNAVAILABLE) clim.put("cycleMode", acCycleMode);
            if (acWindMode != UNAVAILABLE) clim.put("windMode", acWindMode);
            if (acFanLevel != UNAVAILABLE) clim.put("fanLevel", acFanLevel);
            if (tempUnit != UNAVAILABLE) clim.put("tempUnit", tempUnit);
            if (acSetpointDriver != UNAVAILABLE) clim.put("setpointDriver", acSetpointDriver);
            if (acSetpointPassenger != UNAVAILABLE) clim.put("setpointPassenger", acSetpointPassenger);
            j.put("climate", clim);

            // Sensor
            putIfValid(j, "slopeDegrees", slopeDegrees, roundDoubles);

            // Power
            if (powerLevel != UNAVAILABLE) j.put("powerLevel", powerLevel);
            if (mcuStatus != UNAVAILABLE) j.put("mcuStatus", mcuStatus);
            if (emergencyAlarmState != UNAVAILABLE) j.put("emergencyAlarm", emergencyAlarmState);

            // Radar
            if (radarDistances != null) {
                JSONArray rd = new JSONArray();
                for (int d : radarDistances) rd.put(d);
                j.put("radarDistances", rd);
            }

            // Meta
            j.put("timestamp", timestamp);
            if (availableDevices != null) {
                JSONArray ad = new JSONArray();
                for (String d : availableDevices) ad.put(d);
                j.put("availableDevices", ad);
            }

            // ==================== EXTENDED SUB-OBJECTS ====================

            // Extended Battery
            JSONObject extBatt = new JSONObject();
            putIfValid(extBatt, "sohPercent", sohPercent, roundDoubles);
            if (keyBatteryLevel != UNAVAILABLE) extBatt.put("keyBatteryLevel", keyBatteryLevel);
            if (battery12vLevel != UNAVAILABLE) extBatt.put("battery12vLevel", battery12vLevel);
            if (extBatt.length() > 0) j.put("extendedBattery", extBatt);

            // Key proximity
            JSONObject keyJson = new JSONObject();
            if (keyStartState != UNAVAILABLE) keyJson.put("startState", keyStartState);
            if (keyMissingInd != UNAVAILABLE) keyJson.put("missingInd", keyMissingInd);
            if (keyBtLowPowerMode != UNAVAILABLE) keyJson.put("btLowPowerMode", keyBtLowPowerMode);
            if (keyPowerLowInd != UNAVAILABLE) keyJson.put("powerLowInd", keyPowerLowInd);
            if (keyDetectionReminder != UNAVAILABLE) keyJson.put("detectionReminder", keyDetectionReminder);
            if (smartKeyWarnState != UNAVAILABLE) keyJson.put("smartKeyWarnState", smartKeyWarnState);
            if (keyJson.length() > 0) j.put("key", keyJson);

            // Extended Thermal (insideTempCelsius)
            // Note: insideTempCelsius is separate from the existing insideTempC in thermal
            JSONObject extTherm = new JSONObject();
            if (hasFreshCabinTemperature()) {
                putIfValid(extTherm, "insideTempCelsius",
                        insideTempCelsius, roundDoubles);
            }
            if (extTherm.length() > 0) j.put("extendedThermal", extTherm);

            // Extended Charging
            JSONObject extChg = new JSONObject();
            if (chargingRestTimeHours != UNAVAILABLE) extChg.put("restTimeHours", chargingRestTimeHours);
            if (chargingRestTimeMinutes != UNAVAILABLE) extChg.put("restTimeMinutes", chargingRestTimeMinutes);
            if (chargingPercent != UNAVAILABLE) extChg.put("chargingPercent", chargingPercent);
            if (chargingType != UNAVAILABLE) extChg.put("chargingType", chargingType);
            if (vtolCharging) extChg.put("vtolCharging", true);
            putIfValid(extChg, "chargingCapacityKwh",
                    chargingCapacityKwh, roundDoubles);
            if (wirelessChargingLeftState != UNAVAILABLE) extChg.put("wirelessChargingLeftState", wirelessChargingLeftState);
            if (wirelessChargingRightState != UNAVAILABLE) extChg.put("wirelessChargingRightState", wirelessChargingRightState);
            if (extChg.length() > 0) j.put("extendedCharging", extChg);

            // Extended Driving
            JSONObject extDrv = new JSONObject();
            putIfValid(extDrv, "drivingTimeHours", drivingTimeHours, roundDoubles);
            putIfValid(extDrv, "last50KmConsumption",
                    last50KmConsumption, roundDoubles);
            putIfValid(extDrv, "steeringAngleDegrees",
                    steeringAngleDegrees, roundDoubles);
            if (autoSystemState != UNAVAILABLE) extDrv.put("autoSystemState", autoSystemState);
            if (extDrv.length() > 0) j.put("extendedDriving", extDrv);

            // Extended Trip
            JSONObject extTrip = new JSONObject();
            putIfValid(extTrip, "currentTripMileageKm",
                    currentTripMileageKm, roundDoubles);
            putIfValid(extTrip, "currentTripTimeHours",
                    currentTripTimeHours, roundDoubles);
            putIfValid(extTrip, "currentTripConsumptionKwh",
                    currentTripConsumptionKwh, roundDoubles);
            if (extTrip.length() > 0) j.put("extendedTrip", extTrip);

            // Extended Engine
            JSONObject extEng = new JSONObject();
            if (engineCoolantLevel != UNAVAILABLE) extEng.put("engineCoolantLevel", engineCoolantLevel);
            if (oilLevel != UNAVAILABLE) extEng.put("oilLevel", oilLevel);
            if (engineCode != null) extEng.put("engineCode", engineCode);
            if (extEng.length() > 0) j.put("extendedEngine", extEng);

            // Extended Bodywork
            JSONObject extBody = new JSONObject();
            if (wiperState != UNAVAILABLE) extBody.put("wiperState", wiperState);
            if (autoWiperState != UNAVAILABLE) extBody.put("autoWiperState", autoWiperState);
            if (lightAutoStatus != UNAVAILABLE) extBody.put("lightAutoStatus", lightAutoStatus);
            if (sunroofState != UNAVAILABLE) extBody.put("sunroofState", sunroofState);
            if (sunroofPosition != UNAVAILABLE) extBody.put("sunroofPosition", sunroofPosition);
            if (sunshadePercent != UNAVAILABLE) extBody.put("sunshadePercent", sunshadePercent);
            if (wirelessChargingStatus != UNAVAILABLE) extBody.put("wirelessChargingStatus", wirelessChargingStatus);
            if (driftModeEnabled || (includeDiLink5Fields && driftModeKnown)) {
                extBody.put("driftModeEnabled", driftModeEnabled);
            }
            if (extBody.length() > 0) j.put("extendedBodywork", extBody);

            // Extended Safety
            if (passengerDetection != null) {
                JSONObject extSafety = new JSONObject();
                JSONArray pd = new JSONArray();
                for (int p : passengerDetection) pd.put(p);
                extSafety.put("passengerDetection", pd);
                j.put("extendedSafety", extSafety);
            }

            // Extended Air Quality
            JSONObject extAir = new JSONObject();
            if (pm25Inside != UNAVAILABLE) extAir.put("pm25Inside", pm25Inside);
            if (pm25Outside != UNAVAILABLE) extAir.put("pm25Outside", pm25Outside);
            if (extAir.length() > 0) j.put("extendedAir", extAir);
        } catch (Exception ignored) {}
        return j;
    }

    private static Object tyreJsonValue(int value, boolean diLink5) {
        return diLink5 && value == UNAVAILABLE
                ? JSONObject.NULL : Integer.valueOf(value);
    }

    /**
     * Freshness and internal-source metadata layered onto the existing wire
     * shape for the local cross-process telemetry handoff. Public API responses
     * continue using {@link #toJson()} unchanged.
     */
    public JSONObject toBridgeJson() {
        JSONObject json = toJson(false, true);
        try {
            JSONObject meta = new JSONObject();
            if (!Double.isNaN(insideTempC)) meta.put("insideTempC", insideTempC);
            if (!Double.isNaN(insideTempCelsius)) {
                meta.put("insideTempCelsius", insideTempCelsius);
            }
            if (!Double.isNaN(chargingPowerKw)) {
                meta.put("chargingPowerKw", chargingPowerKw);
            }
            if (!Double.isNaN(externalChargingPowerKw)) {
                meta.put("externalChargingPowerKw", externalChargingPowerKw);
            }
            if (!Double.isNaN(chargePowerKw)) meta.put("chargePowerKw", chargePowerKw);
            if (!Double.isNaN(clusterChargePowerKw)) {
                meta.put("clusterChargePowerKw", clusterChargePowerKw);
            }
            if (!Double.isNaN(chargingPowerLastObservedKw)) {
                meta.put("chargingPowerLastObservedKw", chargingPowerLastObservedKw);
            }
            if (!Double.isNaN(externalChargingPowerLastObservedKw)) {
                meta.put("externalChargingPowerLastObservedKw",
                        externalChargingPowerLastObservedKw);
            }
            if (!Double.isNaN(chargePowerLastObservedKw)) {
                meta.put("chargePowerLastObservedKw", chargePowerLastObservedKw);
            }
            if (!Double.isNaN(clusterChargePowerLastObservedKw)) {
                meta.put("clusterChargePowerLastObservedKw",
                        clusterChargePowerLastObservedKw);
            }
            meta.put("insideTempReadAt", insideTempReadAt);
            meta.put("enginePowerAtMs", enginePowerAtMs);
            meta.put("clusterChargePowerAtMs", clusterChargePowerAtMs);
            meta.put("chargingStateAtMs", chargingStateAtMs);
            meta.put("chargingPowerAtMs", chargingPowerAtMs);
            meta.put("chargingPowerChangedAtMs", chargingPowerChangedAtMs);
            meta.put("externalChargingPowerAtMs", externalChargingPowerAtMs);
            meta.put("externalChargingPowerChangedAtMs",
                    externalChargingPowerChangedAtMs);
            meta.put("chargePowerAtMs", chargePowerAtMs);
            meta.put("chargePowerChangedAtMs", chargePowerChangedAtMs);
            meta.put("clusterChargePowerChangedAtMs",
                    clusterChargePowerChangedAtMs);
            meta.put("lightKnownMask", lightKnownMask);
            if (unavailableDevices != null) {
                JSONArray unavailable = new JSONArray();
                for (String device : unavailableDevices) unavailable.put(device);
                meta.put("unavailableDevices", unavailable);
            }
            json.put("_snapshotMeta", meta);
        } catch (Exception ignored) {}
        return json;
    }

    private static void putIfValid(
            JSONObject j, String key, double val, boolean round)
            throws org.json.JSONException {
        if (!Double.isNaN(val)) {
            j.put(key, round ? Math.round(val * 100) / 100.0 : val);
        }
    }

    public static BydVehicleData fromJson(JSONObject json) {
        if (json == null) return null;
        Builder b = new Builder();
        try {
            b.vin = json.optString("vin", null);
            b.timestamp = json.optLong("timestamp", System.currentTimeMillis());

            JSONObject battery = json.optJSONObject("battery");
            if (battery != null) {
                b.socPercent = battery.optDouble("socPercent", NaN);
                b.socHevPercent = battery.optDouble("socHevPercent", NaN);
                b.socTargetPercent = battery.optInt("socTargetPercent", UNAVAILABLE);
                b.capacityAh = battery.optDouble("capacityAh", NaN);
                b.remainKwh = battery.optDouble("remainKwh", NaN);
                b.voltage12v = battery.optDouble("voltage12v", NaN);
                b.voltage12vAtMs = battery.optLong("voltage12vAtMs", 0L);
                b.voltageLevelRaw = battery.optInt("voltageLevelRaw", UNAVAILABLE);
                b.hvPackVoltage = battery.optDouble("hvPackVoltage", NaN);
                b.hvPackCurrentAmps =
                        battery.optDouble("hvPackCurrentAmps", NaN);
                b.hvBatteryPowerKw =
                        battery.optDouble("hvBatteryPowerKw", NaN);
            }
            JSONObject thermal = json.optJSONObject("thermal");
            if (thermal != null) {
                b.highCellTempC = thermal.optDouble("highCellTempC", NaN);
                b.lowCellTempC = thermal.optDouble("lowCellTempC", NaN);
                b.avgCellTempC = thermal.optDouble("avgCellTempC", NaN);
                b.waterTempC = thermal.optDouble("waterTempC", NaN);
                b.outsideTempC = thermal.optDouble("outsideTempC", NaN);
                b.insideTempC = thermal.optDouble("insideTempC", NaN);
                b.insideTempReadAt = Double.isNaN(b.insideTempC)
                        ? 0L : b.timestamp;
                b.bodyworkBattTempC =
                        thermal.optDouble("bodyworkBattTempC", NaN);
            }
            JSONObject cellVoltage = json.optJSONObject("cellVoltage");
            if (cellVoltage != null) {
                b.highCellVoltage = cellVoltage.optDouble("highV", NaN);
                b.lowCellVoltage = cellVoltage.optDouble("lowV", NaN);
            }
            JSONObject speed = json.optJSONObject("speed");
            if (speed != null) {
                b.speedKmh = speed.optDouble("kmh", NaN);
                b.accelPercent = speed.optInt("accelPercent", UNAVAILABLE);
                b.brakePercent = speed.optInt("brakePercent", UNAVAILABLE);
            }
            JSONObject motor = json.optJSONObject("motor");
            if (motor != null) {
                b.frontMotorSpeed = motor.optInt("frontSpeed", UNAVAILABLE);
                b.rearMotorSpeed = motor.optInt("rearSpeed", UNAVAILABLE);
                b.frontMotorTorque = motor.optDouble("frontTorque", NaN);
                b.engineSpeedRpm = motor.optInt("engineRpm", UNAVAILABLE);
                b.enginePowerKw = motor.optDouble("enginePowerKw", NaN);
            }
            JSONObject energy = json.optJSONObject("energy");
            if (energy != null) {
                b.energyMode = energy.optInt("mode", UNAVAILABLE);
                b.operationMode = energy.optInt("operationMode", UNAVAILABLE);
                b.totalElecCon = energy.optDouble("totalElecCon", NaN);
                b.totalFuelCon = energy.optDouble("totalFuelCon", NaN);
                b.avgFuelConPer100Km =
                        energy.optDouble("avgFuelConPer100Km", NaN);
                b.avgElecConPer100Km =
                        energy.optDouble("avgElecConPer100Km", NaN);
                b.lastElecConPer100Km =
                        energy.optDouble("lastElecConPer100Km", NaN);
            }
            b.gearMode = json.optInt("gearMode", UNAVAILABLE);
            JSONObject range = json.optJSONObject("range");
            if (range != null) {
                b.elecRangeKm = range.optInt("elecKm", UNAVAILABLE);
                b.fuelRangeKm = range.optInt("fuelKm", UNAVAILABLE);
                b.fuelPercent = range.optDouble("fuelPercent", NaN);
                b.bodyworkRangeKm = range.optInt("bodyworkKm", UNAVAILABLE);
            }
            JSONObject mileage = json.optJSONObject("mileage");
            if (mileage != null) {
                b.totalMileageKm = mileage.optInt("totalKm", UNAVAILABLE);
                b.evMileageKm = mileage.optInt("evKm", UNAVAILABLE);
                b.hevMileageKm = mileage.optInt("hevKm", UNAVAILABLE);
            }
            JSONObject charging = json.optJSONObject("charging");
            if (charging != null) {
                b.chargingState = charging.optInt("state", UNAVAILABLE);
                b.batteryPreheat(charging.optInt("batteryPreheatState", UNAVAILABLE),
                        charging.optLong("batteryPreheatObservedAtMs", 0L));
                b.chargingGunState = charging.optInt("gunState", UNAVAILABLE);
                b.chargerWorkState =
                        charging.optInt("chargerState", UNAVAILABLE);
                b.chargingMode = charging.optInt("mode", UNAVAILABLE);
                b.chargingPowerKw = charging.optDouble("powerRaw", NaN);
                b.externalChargingPowerKw =
                        charging.optDouble("externalPowerRaw", NaN);
                b.chargePowerKw =
                        charging.optDouble("chargePowerKw", NaN);
                b.clusterChargePowerKw =
                        charging.optDouble("clusterChargePowerRaw", NaN);
            }
            JSONArray pressures = json.optJSONArray("tyrePressure");
            if (pressures != null) b.tyrePressure = intArray(pressures);
            JSONArray pressureStates = json.optJSONArray("tyrePressureState");
            if (pressureStates != null) b.tyrePressureState = intArray(pressureStates);
            JSONArray leakStates = json.optJSONArray("tyreAirLeakState");
            if (leakStates != null) b.tyreAirLeakState = intArray(leakStates);
            JSONArray signalStates = json.optJSONArray("tyreSignalState");
            if (signalStates != null) b.tyreSignalState = intArray(signalStates);
            JSONArray temperatures = json.optJSONArray("tyreTemperature");
            if (temperatures != null) b.tyreTemperature = intArray(temperatures);
            b.tyreSystemState = json.optInt("tyreSystemState", UNAVAILABLE);
            b.tyreTemperatureState =
                    json.optInt("tyreTemperatureState", UNAVAILABLE);
            JSONArray locks = json.optJSONArray("doorLockStatus");
            if (locks != null) b.doorLockStatus = intArray(locks);
            JSONArray windows = json.optJSONArray("windowOpenPercent");
            if (windows != null) b.windowOpenPercent = intArray(windows);
            JSONObject lights = json.optJSONObject("lights");
            if (lights != null) {
                b.leftTurnState = lights.optInt("leftTurn", UNAVAILABLE);
                b.rightTurnState = lights.optInt("rightTurn", UNAVAILABLE);
                b.lowBeam = lights.optBoolean("lowBeam", false);
                b.highBeam = lights.optBoolean("highBeam", false);
                b.rearFog = lights.optBoolean("rearFog", false);
                b.frontFog = lights.optBoolean("frontFog", false);
                b.hazard = lights.optBoolean("hazard", false);
                b.dayTimeLight = lights.optBoolean("dayTimeLight", false);
            }
            JSONArray seatbelts = json.optJSONArray("seatbeltStatus");
            if (seatbelts != null) b.seatbeltStatus = intArray(seatbelts);
            JSONArray heat = json.optJSONArray("seatHeat");
            if (heat != null) b.seatHeat = intArray(heat);
            JSONArray cool = json.optJSONArray("seatCool");
            if (cool != null) b.seatCool = intArray(cool);
            JSONArray ventilationSupport =
                    json.optJSONArray("seatVentilationSupport");
            if (ventilationSupport != null) {
                b.seatVentilationSupport = intArray(ventilationSupport);
            }
            b.steeringWheelHeat =
                    json.optInt("steeringWheelHeat", UNAVAILABLE);
            b.seatClimateAtMs = json.optLong("seatClimateAtMs", 0L);
            if (json.has("ambientColour") && !json.isNull("ambientColour")) {
                int colour = json.optInt("ambientColour", UNAVAILABLE);
                if (colour >= 1 && colour <= 31) {
                    b.ambientColour = colour;
                    b.ambientColourKnown = true;
                }
            }
            b.ambientEnabled = json.optInt("ambientEnabled", UNAVAILABLE);
            b.readingLight = json.optInt("readingLight", UNAVAILABLE);
            if (json.has("speedLimitWarning")
                    && !json.isNull("speedLimitWarning")) {
                b.speedLimitWarning =
                        json.optBoolean("speedLimitWarning", false);
                b.speedLimitWarningKnown = true;
            }
            b.childPresenceDetection =
                    json.optInt("childPresenceDetection", UNAVAILABLE);
            JSONObject climate = json.optJSONObject("climate");
            if (climate != null) {
                b.acStartState = climate.optInt("acOn", UNAVAILABLE);
                b.acCycleMode = climate.optInt("cycleMode", UNAVAILABLE);
                b.acWindMode = climate.optInt("windMode", UNAVAILABLE);
                b.acFanLevel = climate.optInt("fanLevel", UNAVAILABLE);
                b.tempUnit = climate.optInt("tempUnit", UNAVAILABLE);
                b.acSetpointDriver =
                        climate.optInt("setpointDriver", UNAVAILABLE);
                b.acSetpointPassenger =
                        climate.optInt("setpointPassenger", UNAVAILABLE);
            }
            b.slopeDegrees = json.optDouble("slopeDegrees", NaN);
            b.powerLevel = json.optInt("powerLevel", UNAVAILABLE);
            b.mcuStatus = json.optInt("mcuStatus", UNAVAILABLE);
            b.emergencyAlarmState =
                    json.optInt("emergencyAlarm", UNAVAILABLE);
            JSONArray radar = json.optJSONArray("radarDistances");
            if (radar != null) b.radarDistances = intArray(radar);
            JSONArray devices = json.optJSONArray("availableDevices");
            if (devices != null) b.availableDevices = stringArray(devices);

            JSONObject extendedBattery = json.optJSONObject("extendedBattery");
            if (extendedBattery != null) {
                b.sohPercent =
                        extendedBattery.optDouble("sohPercent", NaN);
                b.keyBatteryLevel =
                        extendedBattery.optInt("keyBatteryLevel", UNAVAILABLE);
                b.battery12vLevel =
                        extendedBattery.optInt("battery12vLevel", UNAVAILABLE);
            }
            JSONObject key = json.optJSONObject("key");
            if (key != null) {
                b.keyStartState = key.optInt("startState", UNAVAILABLE);
                b.keyMissingInd = key.optInt("missingInd", UNAVAILABLE);
                b.keyBtLowPowerMode =
                        key.optInt("btLowPowerMode", UNAVAILABLE);
                b.keyPowerLowInd = key.optInt("powerLowInd", UNAVAILABLE);
                b.keyDetectionReminder =
                        key.optInt("detectionReminder", UNAVAILABLE);
                b.smartKeyWarnState =
                        key.optInt("smartKeyWarnState", UNAVAILABLE);
            }
            JSONObject extendedThermal =
                    json.optJSONObject("extendedThermal");
            if (extendedThermal != null) {
                b.insideTempCelsius = extendedThermal.optDouble(
                        "insideTempCelsius", NaN);
            }
            JSONObject extendedCharging =
                    json.optJSONObject("extendedCharging");
            if (extendedCharging != null) {
                b.chargingRestTimeHours =
                        extendedCharging.optInt("restTimeHours", UNAVAILABLE);
                b.chargingRestTimeMinutes =
                        extendedCharging.optInt("restTimeMinutes", UNAVAILABLE);
                b.chargingPercent =
                        extendedCharging.optInt("chargingPercent", UNAVAILABLE);
                b.chargingType =
                        extendedCharging.optInt("chargingType", UNAVAILABLE);
                b.vtolCharging =
                        extendedCharging.optBoolean("vtolCharging", false);
                b.chargingCapacityKwh = extendedCharging.optDouble(
                        "chargingCapacityKwh", NaN);
                b.wirelessChargingLeftState = extendedCharging.optInt(
                        "wirelessChargingLeftState", UNAVAILABLE);
                b.wirelessChargingRightState = extendedCharging.optInt(
                        "wirelessChargingRightState", UNAVAILABLE);
            }
            JSONObject extendedDriving =
                    json.optJSONObject("extendedDriving");
            if (extendedDriving != null) {
                b.drivingTimeHours =
                        extendedDriving.optDouble("drivingTimeHours", NaN);
                b.last50KmConsumption =
                        extendedDriving.optDouble("last50KmConsumption", NaN);
                b.steeringAngleDegrees = extendedDriving.optDouble(
                        "steeringAngleDegrees", NaN);
                b.autoSystemState =
                        extendedDriving.optInt("autoSystemState", UNAVAILABLE);
            }
            JSONObject extendedTrip = json.optJSONObject("extendedTrip");
            if (extendedTrip != null) {
                b.currentTripMileageKm = extendedTrip.optDouble(
                        "currentTripMileageKm", NaN);
                b.currentTripTimeHours = extendedTrip.optDouble(
                        "currentTripTimeHours", NaN);
                b.currentTripConsumptionKwh = extendedTrip.optDouble(
                        "currentTripConsumptionKwh", NaN);
            }
            JSONObject extendedEngine = json.optJSONObject("extendedEngine");
            if (extendedEngine != null) {
                b.engineCoolantLevel = extendedEngine.optInt(
                        "engineCoolantLevel", UNAVAILABLE);
                b.oilLevel = extendedEngine.optInt("oilLevel", UNAVAILABLE);
                b.engineCode = extendedEngine.optString("engineCode", null);
            }
            JSONObject extendedBodywork =
                    json.optJSONObject("extendedBodywork");
            if (extendedBodywork != null) {
                b.wiperState =
                        extendedBodywork.optInt("wiperState", UNAVAILABLE);
                b.autoWiperState =
                        extendedBodywork.optInt("autoWiperState", UNAVAILABLE);
                b.lightAutoStatus =
                        extendedBodywork.optInt("lightAutoStatus", UNAVAILABLE);
                b.sunroofState =
                        extendedBodywork.optInt("sunroofState", UNAVAILABLE);
                b.sunroofPosition =
                        extendedBodywork.optInt("sunroofPosition", UNAVAILABLE);
                b.sunshadePercent =
                        extendedBodywork.optInt("sunshadePercent", UNAVAILABLE);
                b.wirelessChargingStatus = extendedBodywork.optInt(
                        "wirelessChargingStatus", UNAVAILABLE);
                if (extendedBodywork.has("driftModeEnabled")
                        && !extendedBodywork.isNull("driftModeEnabled")) {
                    b.driftModeEnabled =
                            extendedBodywork.optBoolean("driftModeEnabled", false);
                    b.driftModeKnown = true;
                }
            }
            JSONObject extendedSafety =
                    json.optJSONObject("extendedSafety");
            if (extendedSafety != null) {
                JSONArray occupants =
                        extendedSafety.optJSONArray("passengerDetection");
                if (occupants != null) b.passengerDetection = intArray(occupants);
            }
            JSONObject extendedAir = json.optJSONObject("extendedAir");
            if (extendedAir != null) {
                b.pm25Inside =
                        extendedAir.optInt("pm25Inside", UNAVAILABLE);
                b.pm25Outside =
                        extendedAir.optInt("pm25Outside", UNAVAILABLE);
            }
            JSONObject meta = json.optJSONObject("_snapshotMeta");
            if (meta != null) {
                b.insideTempC = meta.optDouble("insideTempC", b.insideTempC);
                b.insideTempCelsius =
                        meta.optDouble("insideTempCelsius", b.insideTempCelsius);
                b.insideTempReadAt =
                        meta.optLong("insideTempReadAt", b.insideTempReadAt);
                b.enginePowerAtMs =
                        meta.optLong("enginePowerAtMs", b.enginePowerAtMs);
                b.clusterChargePowerAtMs =
                        meta.optLong("clusterChargePowerAtMs",
                                b.clusterChargePowerAtMs);
                b.chargingStateAtMs =
                        meta.optLong("chargingStateAtMs", b.chargingStateAtMs);
                b.chargingPowerKw =
                        meta.optDouble("chargingPowerKw", b.chargingPowerKw);
                b.chargingPowerAtMs =
                        meta.optLong("chargingPowerAtMs", b.chargingPowerAtMs);
                b.chargingPowerChangedAtMs =
                        meta.optLong("chargingPowerChangedAtMs",
                                b.chargingPowerChangedAtMs);
                b.externalChargingPowerKw =
                        meta.optDouble("externalChargingPowerKw",
                                b.externalChargingPowerKw);
                b.externalChargingPowerAtMs =
                        meta.optLong("externalChargingPowerAtMs",
                                b.externalChargingPowerAtMs);
                b.externalChargingPowerChangedAtMs =
                        meta.optLong("externalChargingPowerChangedAtMs",
                                b.externalChargingPowerChangedAtMs);
                b.chargePowerKw =
                        meta.optDouble("chargePowerKw", b.chargePowerKw);
                b.chargePowerAtMs =
                        meta.optLong("chargePowerAtMs", b.chargePowerAtMs);
                b.chargePowerChangedAtMs =
                        meta.optLong("chargePowerChangedAtMs",
                                b.chargePowerChangedAtMs);
                b.clusterChargePowerKw =
                        meta.optDouble("clusterChargePowerKw",
                                b.clusterChargePowerKw);
                b.clusterChargePowerChangedAtMs =
                        meta.optLong("clusterChargePowerChangedAtMs",
                                b.clusterChargePowerChangedAtMs);
                b.chargingPowerLastObservedKw =
                        meta.optDouble("chargingPowerLastObservedKw",
                                b.chargingPowerLastObservedKw);
                b.externalChargingPowerLastObservedKw =
                        meta.optDouble("externalChargingPowerLastObservedKw",
                                b.externalChargingPowerLastObservedKw);
                b.chargePowerLastObservedKw =
                        meta.optDouble("chargePowerLastObservedKw",
                                b.chargePowerLastObservedKw);
                b.clusterChargePowerLastObservedKw =
                        meta.optDouble("clusterChargePowerLastObservedKw",
                                b.clusterChargePowerLastObservedKw);
                b.lightKnownMask = meta.optInt(
                        "lightKnownMask", b.lightKnownMask);
                JSONArray unavailable = meta.optJSONArray("unavailableDevices");
                if (unavailable != null) {
                    b.unavailableDevices = stringArray(unavailable);
                }
            }
            return new BydVehicleData(b);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int[] intArray(JSONArray values) throws org.json.JSONException {
        int[] result = new int[values.length()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.isNull(i) ? UNAVAILABLE : values.getInt(i);
        }
        return result;
    }

    private static String[] stringArray(JSONArray values)
            throws org.json.JSONException {
        String[] result = new String[values.length()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.getString(i);
        }
        return result;
    }

    /** Create a new builder pre-filled with this snapshot's values */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.vin = vin; b.socPercent = socPercent; b.socHevPercent = socHevPercent;
        b.socTargetPercent = socTargetPercent;
        b.capacityAh = capacityAh; b.remainKwh = remainKwh; b.voltage12v = voltage12v;
        b.voltage12vAtMs = voltage12vAtMs;
        b.voltageLevelRaw = voltageLevelRaw;
        b.highCellTempC = highCellTempC;
        b.lowCellTempC = lowCellTempC; b.avgCellTempC = avgCellTempC;
        // insideTempReadAt rides along with insideTempC: the value is carried forward, and so is
        // the age of the read that produced it (NOT refreshed here — that would make every stale
        // carry-forward look brand new, defeating the whole point of the stamp).
        b.waterTempC = waterTempC; b.outsideTempC = outsideTempC; b.insideTempC = insideTempC;
        b.insideTempReadAt = insideTempReadAt;
        b.bodyworkBattTempC = bodyworkBattTempC; b.highCellVoltage = highCellVoltage;
        b.lowCellVoltage = lowCellVoltage; b.speedKmh = speedKmh; b.accelPercent = accelPercent;
        b.brakePercent = brakePercent; b.frontMotorSpeed = frontMotorSpeed;
        b.rearMotorSpeed = rearMotorSpeed; b.frontMotorTorque = frontMotorTorque;
        b.engineSpeedRpm = engineSpeedRpm; b.enginePowerKw = enginePowerKw;
        b.enginePowerAtMs = enginePowerAtMs;
        b.clusterChargePowerAtMs = clusterChargePowerAtMs;
        b.energyMode = energyMode; b.operationMode = operationMode;
        b.totalElecCon = totalElecCon; b.totalFuelCon = totalFuelCon;
        b.avgFuelConPer100Km = avgFuelConPer100Km;
        b.avgElecConPer100Km = avgElecConPer100Km; b.lastElecConPer100Km = lastElecConPer100Km;
        b.elecRangeKm = elecRangeKm; b.fuelRangeKm = fuelRangeKm;
        b.fuelPercent = fuelPercent;
        b.bodyworkRangeKm = bodyworkRangeKm; b.totalMileageKm = totalMileageKm;
        b.evMileageKm = evMileageKm; b.hevMileageKm = hevMileageKm; b.chargingState = chargingState;
        b.chargingStateAtMs = chargingStateAtMs;
        b.batteryPreheat(batteryPreheatState, batteryPreheatObservedAtMs);
        b.chargingGunState = chargingGunState; b.chargerWorkState = chargerWorkState;
        b.chargingMode = chargingMode;
        b.chargingPowerKw = chargingPowerKw; b.chargingPowerAtMs = chargingPowerAtMs;
        b.chargingPowerChangedAtMs = chargingPowerChangedAtMs;
        b.chargingPowerLastObservedKw = chargingPowerLastObservedKw;
        b.externalChargingPowerKw = externalChargingPowerKw;
        b.externalChargingPowerAtMs = externalChargingPowerAtMs;
        b.externalChargingPowerChangedAtMs = externalChargingPowerChangedAtMs;
        b.externalChargingPowerLastObservedKw = externalChargingPowerLastObservedKw;
        b.chargePowerKw = chargePowerKw;
        b.chargePowerAtMs = chargePowerAtMs;
        b.chargePowerChangedAtMs = chargePowerChangedAtMs;
        b.chargePowerLastObservedKw = chargePowerLastObservedKw;
        b.clusterChargePowerKw = clusterChargePowerKw;
        b.clusterChargePowerChangedAtMs = clusterChargePowerChangedAtMs;
        b.clusterChargePowerLastObservedKw = clusterChargePowerLastObservedKw;
        b.hvPackVoltage = hvPackVoltage;
        b.hvPackCurrentAmps = hvPackCurrentAmps;
        b.hvBatteryPowerKw = hvBatteryPowerKw;
        b.gearMode = gearMode; b.tyrePressure = tyrePressure;
        b.tyrePressureState = tyrePressureState; b.tyreAirLeakState = tyreAirLeakState;
        b.tyreSignalState = tyreSignalState; b.tyreTemperature = tyreTemperature;
        b.tyreSystemState = tyreSystemState;
        b.tyreTemperatureState = tyreTemperatureState;
        b.doorLockStatus = doorLockStatus;
        b.windowOpenPercent = windowOpenPercent; b.leftTurnState = leftTurnState;
        b.rightTurnState = rightTurnState; b.lowBeam = lowBeam; b.highBeam = highBeam;
        b.rearFog = rearFog; b.frontFog = frontFog; b.hazard = hazard;
        b.dayTimeLight = dayTimeLight; b.lightKnownMask = lightKnownMask;
        b.seatbeltStatus = seatbeltStatus;
        b.ambientColour = ambientColour;
        b.ambientColourKnown = ambientColourKnown;
        b.ambientEnabled = ambientEnabled;
        b.readingLight = readingLight;
        b.seatHeat = seatHeat; b.seatCool = seatCool;
        b.seatVentilationSupport = seatVentilationSupport;
        b.steeringWheelHeat = steeringWheelHeat;
        b.seatClimateAtMs = seatClimateAtMs;
        b.speedLimitWarning = speedLimitWarning;
        b.speedLimitWarningKnown = speedLimitWarningKnown;
        b.childPresenceDetection = childPresenceDetection;
        b.acStartState = acStartState; b.acCycleMode = acCycleMode; b.acWindMode = acWindMode; b.acFanLevel = acFanLevel;
        b.tempUnit = tempUnit; b.slopeDegrees = slopeDegrees; b.powerLevel = powerLevel;
        b.acSetpointDriver = acSetpointDriver; b.acSetpointPassenger = acSetpointPassenger;
        b.mcuStatus = mcuStatus; b.emergencyAlarmState = emergencyAlarmState;
        b.radarDistances = radarDistances; b.timestamp = timestamp;
        b.availableDevices = availableDevices; b.unavailableDevices = unavailableDevices;
        // Extended fields
        b.sohPercent = sohPercent; b.keyBatteryLevel = keyBatteryLevel;
        b.battery12vLevel = battery12vLevel; b.insideTempCelsius = insideTempCelsius;
        b.keyStartState = keyStartState; b.keyMissingInd = keyMissingInd;
        b.keyBtLowPowerMode = keyBtLowPowerMode; b.keyPowerLowInd = keyPowerLowInd;
        b.keyDetectionReminder = keyDetectionReminder; b.smartKeyWarnState = smartKeyWarnState;
        b.chargingRestTimeHours = chargingRestTimeHours;
        b.chargingRestTimeMinutes = chargingRestTimeMinutes;
        b.chargingPercent = chargingPercent;
        b.chargingType = chargingType;
        b.vtolCharging = vtolCharging;
        b.chargingCapacityKwh = chargingCapacityKwh;
        b.wirelessChargingLeftState = wirelessChargingLeftState;
        b.wirelessChargingRightState = wirelessChargingRightState;
        b.drivingTimeHours = drivingTimeHours; b.last50KmConsumption = last50KmConsumption;
        b.steeringAngleDegrees = steeringAngleDegrees; b.autoSystemState = autoSystemState;
        b.currentTripMileageKm = currentTripMileageKm;
        b.currentTripTimeHours = currentTripTimeHours;
        b.currentTripConsumptionKwh = currentTripConsumptionKwh;
        b.engineCoolantLevel = engineCoolantLevel; b.oilLevel = oilLevel;
        b.engineCode = engineCode; b.wiperState = wiperState;
        b.autoWiperState = autoWiperState; b.lightAutoStatus = lightAutoStatus;
        b.sunroofState = sunroofState; b.sunroofPosition = sunroofPosition;
        b.sunshadePercent = sunshadePercent; b.wirelessChargingStatus = wirelessChargingStatus;
        b.driftModeEnabled = driftModeEnabled;
        b.driftModeKnown = driftModeKnown;
        b.passengerDetection = passengerDetection;
        b.pm25Inside = pm25Inside; b.pm25Outside = pm25Outside;
        return b;
    }

    public static class Builder {
        String vin;
        double socPercent = NaN, socHevPercent = NaN, capacityAh = NaN, remainKwh = NaN;
        int socTargetPercent = UNAVAILABLE;
        double voltage12v = NaN;
        long voltage12vAtMs = 0L;
        int voltageLevelRaw = UNAVAILABLE;
        double highCellTempC = NaN, lowCellTempC = NaN, avgCellTempC = NaN;
        double waterTempC = NaN, outsideTempC = NaN, insideTempC = NaN, bodyworkBattTempC = NaN;
        double highCellVoltage = NaN, lowCellVoltage = NaN;
        double speedKmh = NaN; int accelPercent = UNAVAILABLE, brakePercent = UNAVAILABLE;
        int frontMotorSpeed = UNAVAILABLE, rearMotorSpeed = UNAVAILABLE;
        double frontMotorTorque = NaN; int engineSpeedRpm = UNAVAILABLE; double enginePowerKw = NaN;
        long enginePowerAtMs = 0L;
        long clusterChargePowerAtMs = 0L;
        int energyMode = UNAVAILABLE, operationMode = UNAVAILABLE;
        double totalElecCon = NaN, totalFuelCon = NaN;
        double avgFuelConPer100Km = NaN;
        double avgElecConPer100Km = NaN, lastElecConPer100Km = NaN;
        int elecRangeKm = UNAVAILABLE, fuelRangeKm = UNAVAILABLE, bodyworkRangeKm = UNAVAILABLE;
        double fuelPercent = NaN;
        int totalMileageKm = UNAVAILABLE, evMileageKm = UNAVAILABLE, hevMileageKm = UNAVAILABLE;
        int chargingState = UNAVAILABLE, chargingGunState = UNAVAILABLE, chargerWorkState = UNAVAILABLE;
        int batteryPreheatState = UNAVAILABLE;
        long batteryPreheatObservedAtMs;
        long chargingStateAtMs = 0L;
        int chargingMode = UNAVAILABLE;
        double chargingPowerKw = NaN, externalChargingPowerKw = NaN, chargePowerKw = NaN;
        double hvPackVoltage = NaN, hvPackCurrentAmps = NaN, hvBatteryPowerKw = NaN;
        long chargingPowerAtMs = 0L, externalChargingPowerAtMs = 0L, chargePowerAtMs = 0L;
        long chargingPowerChangedAtMs = 0L, externalChargingPowerChangedAtMs = 0L;
        long chargePowerChangedAtMs = 0L, clusterChargePowerChangedAtMs = 0L;
        double chargingPowerLastObservedKw = NaN;
        double externalChargingPowerLastObservedKw = NaN;
        double chargePowerLastObservedKw = NaN;
        double clusterChargePowerLastObservedKw = NaN;
        double clusterChargePowerKw = NaN;
        int gearMode = UNAVAILABLE;
        int[] tyrePressure, doorLockStatus, windowOpenPercent, seatbeltStatus, radarDistances;
        int[] seatHeat, seatCool, seatVentilationSupport;
        int steeringWheelHeat = UNAVAILABLE;
        long seatClimateAtMs = 0L;
        int[] tyrePressureState, tyreAirLeakState, tyreSignalState, tyreTemperature;
        int tyreSystemState = UNAVAILABLE, tyreTemperatureState = UNAVAILABLE;
        int leftTurnState = UNAVAILABLE, rightTurnState = UNAVAILABLE;
        boolean lowBeam, highBeam, rearFog, frontFog, hazard, dayTimeLight;
        int lightKnownMask = LIGHT_KNOWN_ALL;
        int ambientColour = 1;
        boolean ambientColourKnown;
        int ambientEnabled = UNAVAILABLE;
        int readingLight = UNAVAILABLE;
        boolean speedLimitWarning;
        boolean speedLimitWarningKnown;
        int childPresenceDetection = UNAVAILABLE;
        int acStartState = UNAVAILABLE, acCycleMode = UNAVAILABLE, acWindMode = UNAVAILABLE, acFanLevel = UNAVAILABLE, tempUnit = UNAVAILABLE;
        int acSetpointDriver = UNAVAILABLE, acSetpointPassenger = UNAVAILABLE;
        double slopeDegrees = NaN;
        int powerLevel = UNAVAILABLE, mcuStatus = UNAVAILABLE, emergencyAlarmState = UNAVAILABLE;
        long timestamp = System.currentTimeMillis();
        long insideTempReadAt = 0L;
        String[] availableDevices, unavailableDevices;

        // Extended fields
        double sohPercent = NaN;
        int keyBatteryLevel = UNAVAILABLE;
        int battery12vLevel = UNAVAILABLE;
        int keyStartState = UNAVAILABLE;
        int keyMissingInd = UNAVAILABLE;
        int keyBtLowPowerMode = UNAVAILABLE;
        int keyPowerLowInd = UNAVAILABLE;
        int keyDetectionReminder = UNAVAILABLE;
        int smartKeyWarnState = UNAVAILABLE;
        double insideTempCelsius = NaN;
        int chargingRestTimeHours = UNAVAILABLE;
        int chargingRestTimeMinutes = UNAVAILABLE;
        int chargingPercent = UNAVAILABLE;
        int chargingType = UNAVAILABLE;
        boolean vtolCharging;
        double chargingCapacityKwh = NaN;
        int wirelessChargingLeftState = UNAVAILABLE;
        int wirelessChargingRightState = UNAVAILABLE;
        double drivingTimeHours = NaN;
        double last50KmConsumption = NaN;
        double steeringAngleDegrees = NaN;
        int autoSystemState = UNAVAILABLE;
        double currentTripMileageKm = NaN;
        double currentTripTimeHours = NaN;
        double currentTripConsumptionKwh = NaN;
        int engineCoolantLevel = UNAVAILABLE;
        int oilLevel = UNAVAILABLE;
        String engineCode;
        int wiperState = UNAVAILABLE;
        int autoWiperState = UNAVAILABLE;
        int lightAutoStatus = UNAVAILABLE;
        int sunroofState = UNAVAILABLE;
        int sunroofPosition = UNAVAILABLE;
        int sunshadePercent = UNAVAILABLE;
        int wirelessChargingStatus = UNAVAILABLE;
        boolean driftModeEnabled;
        boolean driftModeKnown;
        int[] passengerDetection;
        int pm25Inside = UNAVAILABLE;
        int pm25Outside = UNAVAILABLE;

        public Builder vin(String v) { vin = v; return this; }
        public Builder socPercent(double v) { socPercent = v; return this; }
        public Builder socHevPercent(double v) { socHevPercent = v; return this; }
        public Builder socTargetPercent(int v) { socTargetPercent = v; return this; }
        public Builder capacityAh(double v) { capacityAh = v; return this; }
        public Builder remainKwh(double v) { remainKwh = v; return this; }
        /** Publish a local 12V observation using its arrival time. */
        public Builder voltage12v(double v) {
            return voltage12v(v, System.currentTimeMillis());
        }
        /** Publish a 12V observation while preserving the source observation time. */
        public Builder voltage12v(double v, long observedAtMs) {
            voltage12v = v;
            voltage12vAtMs = Double.isNaN(v) ? 0L : observedAtMs;
            return this;
        }
        public Builder voltage12vAtMs(long v) { voltage12vAtMs = v; return this; }
        public Builder voltageLevelRaw(int v) { voltageLevelRaw = v; return this; }
        public Builder highCellTempC(double v) { highCellTempC = v; return this; }
        public Builder lowCellTempC(double v) { lowCellTempC = v; return this; }
        public Builder avgCellTempC(double v) { avgCellTempC = v; return this; }
        public Builder waterTempC(double v) { waterTempC = v; return this; }
        public Builder outsideTempC(double v) { outsideTempC = v; return this; }
        /** Publish one cabin observation to both legacy fields with a local observation time. */
        public Builder insideTempC(double v) {
            return insideTempC(v, System.currentTimeMillis());
        }
        /**
         * Publish one cabin observation to both legacy fields while preserving its source time.
         * Cloud fallback passes the cloud snapshot's receive time instead of making carried data
         * look newly observed at every local poll.
         */
        public Builder insideTempC(double v, long observedAtMs) {
            insideTempC = v;
            insideTempCelsius = v;
            insideTempReadAt = observedAtMs;
            return this;
        }
        public Builder bodyworkBattTempC(double v) { bodyworkBattTempC = v; return this; }
        public Builder highCellVoltage(double v) { highCellVoltage = v; return this; }
        public Builder lowCellVoltage(double v) { lowCellVoltage = v; return this; }
        public Builder speedKmh(double v) { speedKmh = v; return this; }
        public Builder accelPercent(int v) { accelPercent = v; return this; }
        public Builder brakePercent(int v) { brakePercent = v; return this; }
        public Builder frontMotorSpeed(int v) { frontMotorSpeed = v; return this; }
        public Builder rearMotorSpeed(int v) { rearMotorSpeed = v; return this; }
        public Builder frontMotorTorque(double v) { frontMotorTorque = v; return this; }
        public Builder engineSpeedRpm(int v) { engineSpeedRpm = v; return this; }
        /** Sets the value AND its freshness stamp together, so the two can never drift.
         *  A NaN write clears the stamp (nothing live to age). */
        public Builder enginePowerKw(double v) {
            enginePowerKw = v;
            enginePowerAtMs = Double.isNaN(v) ? 0L : System.currentTimeMillis();
            return this;
        }
        /** Preserve the source observation time when merging a concurrent snapshot update. */
        public Builder enginePowerAtMs(long v) { enginePowerAtMs = v; return this; }
        public Builder energyMode(int v) { energyMode = v; return this; }
        public Builder operationMode(int v) { operationMode = v; return this; }
        public Builder totalElecCon(double v) { totalElecCon = v; return this; }
        public Builder totalFuelCon(double v) { totalFuelCon = v; return this; }
        public Builder avgFuelConPer100Km(double v) { avgFuelConPer100Km = v; return this; }
        public Builder avgElecConPer100Km(double v) { avgElecConPer100Km = v; return this; }
        public Builder lastElecConPer100Km(double v) { lastElecConPer100Km = v; return this; }
        public Builder elecRangeKm(int v) { elecRangeKm = v; return this; }
        public Builder fuelRangeKm(int v) { fuelRangeKm = v; return this; }
        public Builder fuelPercent(double v) { fuelPercent = v; return this; }
        public Builder bodyworkRangeKm(int v) { bodyworkRangeKm = v; return this; }
        public Builder totalMileageKm(int v) { totalMileageKm = v; return this; }
        public Builder evMileageKm(int v) { evMileageKm = v; return this; }
        public Builder hevMileageKm(int v) { hevMileageKm = v; return this; }
        public Builder chargingState(int v) {
            if (chargingState != v) chargingStateAtMs = System.currentTimeMillis();
            chargingState = v;
            return this;
        }
        /** Preserve the source observation time when copying a newer charging edge. */
        public Builder chargingStateAtMs(long v) { chargingStateAtMs = v; return this; }
        public Builder batteryPreheat(int state, long observedAtMs) {
            batteryPreheatState = isBatteryPreheatState(state) && observedAtMs > 0L
                    ? state : UNAVAILABLE;
            batteryPreheatObservedAtMs = batteryPreheatState == UNAVAILABLE ? 0L : observedAtMs;
            return this;
        }
        public Builder chargingGunState(int v) { chargingGunState = v; return this; }
        public Builder chargerWorkState(int v) { chargerWorkState = v; return this; }
        public Builder chargingMode(int v) { chargingMode = v; return this; }
        public Builder chargingPowerKw(double v) {
            if (Double.isFinite(v)) {
                chargingPowerChangedAtMs = movementTimeForObservation(
                        chargingPowerLastObservedKw, v, chargingPowerChangedAtMs);
                chargingPowerLastObservedKw = v;
            }
            chargingPowerAtMs = observationTimeForWrite(
                    chargingPowerKw, v, chargingPowerAtMs);
            chargingPowerKw = v;
            return this;
        }
        public Builder chargingPowerAtMs(long v) { chargingPowerAtMs = v; return this; }
        public Builder chargingPowerChangedAtMs(long v) {
            chargingPowerChangedAtMs = v;
            return this;
        }
        public Builder chargingPowerLastObservedKw(double v) {
            chargingPowerLastObservedKw = v;
            return this;
        }
        public Builder externalChargingPowerKw(double v) {
            if (Double.isFinite(v)) {
                externalChargingPowerChangedAtMs = movementTimeForObservation(
                        externalChargingPowerLastObservedKw, v,
                        externalChargingPowerChangedAtMs);
                externalChargingPowerLastObservedKw = v;
            }
            externalChargingPowerAtMs = observationTimeForWrite(
                    externalChargingPowerKw, v, externalChargingPowerAtMs);
            externalChargingPowerKw = v;
            return this;
        }
        public Builder externalChargingPowerAtMs(long v) {
            externalChargingPowerAtMs = v;
            return this;
        }
        public Builder externalChargingPowerChangedAtMs(long v) {
            externalChargingPowerChangedAtMs = v;
            return this;
        }
        public Builder externalChargingPowerLastObservedKw(double v) {
            externalChargingPowerLastObservedKw = v;
            return this;
        }
        public Builder chargePowerKw(double v) {
            if (Double.isFinite(v)) {
                chargePowerChangedAtMs = movementTimeForObservation(
                        chargePowerLastObservedKw, v, chargePowerChangedAtMs);
                chargePowerLastObservedKw = v;
            }
            chargePowerAtMs = observationTimeForWrite(chargePowerKw, v, chargePowerAtMs);
            chargePowerKw = v;
            return this;
        }
        public Builder chargePowerAtMs(long v) { chargePowerAtMs = v; return this; }
        public Builder chargePowerChangedAtMs(long v) {
            chargePowerChangedAtMs = v;
            return this;
        }
        public Builder chargePowerLastObservedKw(double v) {
            chargePowerLastObservedKw = v;
            return this;
        }
        public Builder clusterChargePowerKw(double v) {
            if (Double.isFinite(v)) {
                clusterChargePowerChangedAtMs = movementTimeForObservation(
                        clusterChargePowerLastObservedKw, v,
                        clusterChargePowerChangedAtMs);
                clusterChargePowerLastObservedKw = v;
            }
            clusterChargePowerKw = v;
            // Stamp only a LIVE reading. A NaN write is a reset/clear, which carries no observation.
            clusterChargePowerAtMs = Double.isNaN(v) ? 0L : System.currentTimeMillis();
            return this;
        }
        /** Preserve the source observation time when merging a concurrent snapshot update. */
        public Builder clusterChargePowerAtMs(long v) {
            clusterChargePowerAtMs = v;
            return this;
        }
        public Builder clusterChargePowerChangedAtMs(long v) {
            clusterChargePowerChangedAtMs = v;
            return this;
        }
        public Builder clusterChargePowerLastObservedKw(double v) {
            clusterChargePowerLastObservedKw = v;
            return this;
        }
        public Builder clearChargingRateMovement() {
            chargingPowerChangedAtMs = 0L;
            externalChargingPowerChangedAtMs = 0L;
            chargePowerChangedAtMs = 0L;
            clusterChargePowerChangedAtMs = 0L;
            chargingPowerLastObservedKw = NaN;
            externalChargingPowerLastObservedKw = NaN;
            chargePowerLastObservedKw = NaN;
            clusterChargePowerLastObservedKw = NaN;
            return this;
        }
        public Builder hvPackVoltage(double v) { hvPackVoltage = v; return this; }
        public Builder hvPackCurrentAmps(double v) { hvPackCurrentAmps = v; return this; }
        public Builder hvBatteryPowerKw(double v) { hvBatteryPowerKw = v; return this; }
        public Builder gearMode(int v) { gearMode = v; return this; }
        public Builder tyrePressure(int[] v) { tyrePressure = v; return this; }
        public Builder tyrePressureState(int[] v) { tyrePressureState = v; return this; }
        public Builder tyreAirLeakState(int[] v) { tyreAirLeakState = v; return this; }
        public Builder tyreSignalState(int[] v) { tyreSignalState = v; return this; }
        public Builder tyreTemperature(int[] v) { tyreTemperature = v; return this; }
        public Builder tyreSystemState(int v) { tyreSystemState = v; return this; }
        public Builder tyreTemperatureState(int v) { tyreTemperatureState = v; return this; }
        public Builder doorLockStatus(int[] v) { doorLockStatus = v; return this; }
        public Builder windowOpenPercent(int[] v) { windowOpenPercent = v; return this; }
        public Builder leftTurnState(int v) { leftTurnState = v; return this; }
        public Builder rightTurnState(int v) { rightTurnState = v; return this; }
        public Builder lowBeam(boolean v) { lowBeam = v; return this; }
        public Builder highBeam(boolean v) { highBeam = v; return this; }
        public Builder rearFog(boolean v) { rearFog = v; return this; }
        public Builder frontFog(boolean v) { frontFog = v; return this; }
        public Builder hazard(boolean v) { hazard = v; return this; }
        public Builder dayTimeLight(boolean v) { dayTimeLight = v; return this; }
        public Builder lightKnownMask(int v) {
            lightKnownMask = v & LIGHT_KNOWN_ALL;
            return this;
        }
        Builder markLightKnown(int v) {
            lightKnownMask |= v & LIGHT_KNOWN_ALL;
            return this;
        }
        public Builder ambientColour(int v) {
            ambientColour = v;
            ambientColourKnown = true;
            return this;
        }
        public Builder ambientColourKnown(boolean v) {
            ambientColourKnown = v;
            return this;
        }
        public Builder ambientEnabled(int v) { ambientEnabled = v; return this; }
        public Builder readingLight(int v) { readingLight = v; return this; }
        public Builder speedLimitWarning(boolean v) {
            speedLimitWarning = v;
            speedLimitWarningKnown = true;
            return this;
        }
        public Builder speedLimitWarningKnown(boolean v) {
            speedLimitWarningKnown = v;
            return this;
        }
        public Builder childPresenceDetection(int v) { childPresenceDetection = v; return this; }
        public Builder seatbeltStatus(int[] v) { seatbeltStatus = v; return this; }
        public Builder seatHeat(int[] v) { seatHeat = v; return this; }
        public Builder seatCool(int[] v) { seatCool = v; return this; }
        public Builder seatVentilationSupport(int[] v) {
            seatVentilationSupport = v;
            return this;
        }
        public Builder steeringWheelHeat(int v) { steeringWheelHeat = v; return this; }
        public Builder seatClimateAtMs(long v) { seatClimateAtMs = v; return this; }
        public Builder acStartState(int v) { acStartState = v; return this; }
        public Builder acCycleMode(int v) { acCycleMode = v; return this; }
        public Builder acWindMode(int v) { acWindMode = v; return this; }
        public Builder acFanLevel(int v) { acFanLevel = v; return this; }
        public Builder tempUnit(int v) { tempUnit = v; return this; }
        public Builder acSetpointDriver(int v) { acSetpointDriver = v; return this; }
        public Builder acSetpointPassenger(int v) { acSetpointPassenger = v; return this; }
        public Builder slopeDegrees(double v) { slopeDegrees = v; return this; }
        public Builder powerLevel(int v) { powerLevel = v; return this; }
        public Builder mcuStatus(int v) { mcuStatus = v; return this; }
        public Builder emergencyAlarmState(int v) { emergencyAlarmState = v; return this; }
        public Builder radarDistances(int[] v) { radarDistances = v; return this; }
        public Builder availableDevices(String[] v) { availableDevices = v; return this; }
        public Builder unavailableDevices(String[] v) { unavailableDevices = v; return this; }

        // Extended field setters
        public Builder sohPercent(double v) { sohPercent = v; return this; }
        public Builder keyBatteryLevel(int v) { keyBatteryLevel = v; return this; }
        public Builder battery12vLevel(int v) { battery12vLevel = v; return this; }
        public Builder keyStartState(int v) { keyStartState = v; return this; }
        public Builder keyMissingInd(int v) { keyMissingInd = v; return this; }
        public Builder keyBtLowPowerMode(int v) { keyBtLowPowerMode = v; return this; }
        public Builder keyPowerLowInd(int v) { keyPowerLowInd = v; return this; }
        public Builder keyDetectionReminder(int v) { keyDetectionReminder = v; return this; }
        public Builder smartKeyWarnState(int v) { smartKeyWarnState = v; return this; }
        public Builder insideTempCelsius(double v) { insideTempCelsius = v; return this; }
        public Builder chargingRestTimeHours(int v) { chargingRestTimeHours = v; return this; }
        public Builder chargingRestTimeMinutes(int v) { chargingRestTimeMinutes = v; return this; }
        public Builder chargingPercent(int v) { chargingPercent = v; return this; }
        public Builder chargingType(int v) { chargingType = v; return this; }
        public Builder vtolCharging(boolean v) { vtolCharging = v; return this; }
        public Builder chargingCapacityKwh(double v) { chargingCapacityKwh = v; return this; }
        public Builder wirelessChargingLeftState(int v) { wirelessChargingLeftState = v; return this; }
        public Builder wirelessChargingRightState(int v) { wirelessChargingRightState = v; return this; }
        public Builder drivingTimeHours(double v) { drivingTimeHours = v; return this; }
        public Builder last50KmConsumption(double v) { last50KmConsumption = v; return this; }
        public Builder steeringAngleDegrees(double v) { steeringAngleDegrees = v; return this; }
        public Builder autoSystemState(int v) { autoSystemState = v; return this; }
        public Builder currentTripMileageKm(double v) { currentTripMileageKm = v; return this; }
        public Builder currentTripTimeHours(double v) { currentTripTimeHours = v; return this; }
        public Builder currentTripConsumptionKwh(double v) { currentTripConsumptionKwh = v; return this; }
        public Builder engineCoolantLevel(int v) { engineCoolantLevel = v; return this; }
        public Builder oilLevel(int v) { oilLevel = v; return this; }
        public Builder engineCode(String v) { engineCode = v; return this; }
        public Builder wiperState(int v) { wiperState = v; return this; }
        public Builder autoWiperState(int v) { autoWiperState = v; return this; }
        public Builder lightAutoStatus(int v) { lightAutoStatus = v; return this; }
        public Builder sunroofState(int v) { sunroofState = v; return this; }
        public Builder sunroofPosition(int v) { sunroofPosition = v; return this; }
        public Builder sunshadePercent(int v) { sunshadePercent = v; return this; }
        public Builder wirelessChargingStatus(int v) { wirelessChargingStatus = v; return this; }
        public Builder driftModeEnabled(boolean v) {
            driftModeEnabled = v;
            driftModeKnown = true;
            return this;
        }
        public Builder driftModeKnown(boolean v) {
            driftModeKnown = v;
            return this;
        }
        public Builder passengerDetection(int[] v) { passengerDetection = v; return this; }
        public Builder pm25Inside(int v) { pm25Inside = v; return this; }
        public Builder pm25Outside(int v) { pm25Outside = v; return this; }

        /**
         * Stamp a newly observed value, clear the stamp with NaN, and preserve age on a same-value
         * assignment. Collector merge/copy paths call these setters while moving existing snapshot
         * values; treating those writes as reads would make stale power look fresh.
         */
        private static long observationTimeForWrite(double currentValue, double nextValue,
                                                    long currentAtMs) {
            if (Double.isNaN(nextValue)) return 0L;
            if (Double.doubleToLongBits(currentValue) == Double.doubleToLongBits(nextValue)) {
                return currentAtMs;
            }
            return System.currentTimeMillis();
        }

        private static long movementTimeForObservation(double previousValue, double nextValue,
                                                       long currentChangedAtMs) {
            if (!Double.isFinite(previousValue) || !Double.isFinite(nextValue)) {
                return currentChangedAtMs;
            }
            double threshold = Math.max(
                    0.05, Math.max(Math.abs(previousValue), Math.abs(nextValue)) * 0.01);
            return Math.abs(nextValue - previousValue) >= threshold
                    ? System.currentTimeMillis() : currentChangedAtMs;
        }

        public BydVehicleData build() {
            timestamp = System.currentTimeMillis();
            return new BydVehicleData(this);
        }

        BydVehicleData buildPreservingTimestamp() {
            return new BydVehicleData(this);
        }
    }
}
