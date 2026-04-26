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

    // ==================== IDENTITY ====================
    public final String vin;

    // ==================== BATTERY ====================
    public final double socPercent;       // 0-100
    public final double socHevPercent;    // HEV SOC
    public final double capacityAh;
    public final double remainKwh;        // remaining energy
    public final double voltage12v;       // 12V battery volts
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
    public final int frontMotorSpeed;     // RPM (negated from SDK)
    public final int rearMotorSpeed;      // RPM
    public final double frontMotorTorque; // Nm (negated from SDK)
    public final int engineSpeedRpm;
    public final double enginePowerKw;

    // ==================== ENERGY ====================
    public final int energyMode;          // EV/HEV
    public final int operationMode;       // ECO/SPORT/NORMAL
    public final double totalElecCon;     // total electricity consumed
    public final double totalFuelCon;     // total fuel consumed

    // ==================== RANGE ====================
    public final int elecRangeKm;
    public final int fuelRangeKm;
    public final int bodyworkRangeKm;     // range from bodywork device

    // ==================== MILEAGE ====================
    public final int totalMileageKm;
    public final int evMileageKm;

    // ==================== CHARGING ====================
    public final int chargingState;
    public final int chargingGunState;
    public final int chargerWorkState;
    public final double chargingPowerKw;
    public final double externalChargingPowerKw;
    public final double hvPackVoltage;    // HV battery pack voltage (V), from CAN event

    // ==================== GEAR ====================
    public final int gearMode;

    // ==================== TYRES ====================
    public final int[] tyrePressure;      // [FL, FR, RL, RR]

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
    public final int dayTimeLight;

    // ==================== SEATBELTS ====================
    public final int[] seatbeltStatus;    // [1-5]

    // ==================== CLIMATE ====================
    public final int acStartState;
    public final int acCycleMode;
    public final int acWindMode;
    public final int tempUnit;

    // ==================== SENSOR ====================
    public final double slopeDegrees;

    // ==================== POWER ====================
    public final int powerLevel;
    public final int mcuStatus;

    // ==================== ALARM ====================
    public final int emergencyAlarmState;

    // ==================== RADAR ====================
    public final int[] radarDistances;    // 9 sensors

    // ==================== META ====================
    public final long timestamp;
    public final String[] availableDevices;
    public final String[] unavailableDevices;

    private BydVehicleData(Builder b) {
        this.vin = b.vin;
        this.socPercent = b.socPercent;
        this.socHevPercent = b.socHevPercent;
        this.capacityAh = b.capacityAh;
        this.remainKwh = b.remainKwh;
        this.voltage12v = b.voltage12v;
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
        this.energyMode = b.energyMode;
        this.operationMode = b.operationMode;
        this.totalElecCon = b.totalElecCon;
        this.totalFuelCon = b.totalFuelCon;
        this.elecRangeKm = b.elecRangeKm;
        this.fuelRangeKm = b.fuelRangeKm;
        this.bodyworkRangeKm = b.bodyworkRangeKm;
        this.totalMileageKm = b.totalMileageKm;
        this.evMileageKm = b.evMileageKm;
        this.chargingState = b.chargingState;
        this.chargingGunState = b.chargingGunState;
        this.chargerWorkState = b.chargerWorkState;
        this.chargingPowerKw = b.chargingPowerKw;
        this.externalChargingPowerKw = b.externalChargingPowerKw;
        this.hvPackVoltage = b.hvPackVoltage;
        this.gearMode = b.gearMode;
        this.tyrePressure = b.tyrePressure;
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
        this.seatbeltStatus = b.seatbeltStatus;
        this.acStartState = b.acStartState;
        this.acCycleMode = b.acCycleMode;
        this.acWindMode = b.acWindMode;
        this.tempUnit = b.tempUnit;
        this.slopeDegrees = b.slopeDegrees;
        this.powerLevel = b.powerLevel;
        this.mcuStatus = b.mcuStatus;
        this.emergencyAlarmState = b.emergencyAlarmState;
        this.radarDistances = b.radarDistances;
        this.timestamp = b.timestamp;
        this.availableDevices = b.availableDevices;
        this.unavailableDevices = b.unavailableDevices;
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

    /** Convert to JSON for API responses */
    public JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            if (vin != null) j.put("vin", vin);

            // Battery
            JSONObject batt = new JSONObject();
            putIfValid(batt, "socPercent", socPercent);
            putIfValid(batt, "socHevPercent", socHevPercent);
            putIfValid(batt, "capacityAh", capacityAh);
            putIfValid(batt, "remainKwh", remainKwh);
            putIfValid(batt, "voltage12v", voltage12v);
            if (voltageLevelRaw != UNAVAILABLE) batt.put("voltageLevelRaw", voltageLevelRaw);
            j.put("battery", batt);

            // Thermal
            JSONObject therm = new JSONObject();
            putIfValid(therm, "highCellTempC", highCellTempC);
            putIfValid(therm, "lowCellTempC", lowCellTempC);
            putIfValid(therm, "avgCellTempC", avgCellTempC);
            putIfValid(therm, "waterTempC", waterTempC);
            putIfValid(therm, "outsideTempC", outsideTempC);
            putIfValid(therm, "insideTempC", insideTempC);
            putIfValid(therm, "bodyworkBattTempC", bodyworkBattTempC);
            putIfValid(therm, "bestBatteryTempC", getBestBatteryTemp());
            j.put("thermal", therm);

            // Cell voltage
            JSONObject cellV = new JSONObject();
            putIfValid(cellV, "highV", highCellVoltage);
            putIfValid(cellV, "lowV", lowCellVoltage);
            putIfValid(cellV, "deltaV", getCellVoltageDelta());
            j.put("cellVoltage", cellV);

            // Speed
            JSONObject spd = new JSONObject();
            putIfValid(spd, "kmh", speedKmh);
            if (accelPercent != UNAVAILABLE) spd.put("accelPercent", accelPercent);
            if (brakePercent != UNAVAILABLE) spd.put("brakePercent", brakePercent);
            j.put("speed", spd);

            // Motor
            JSONObject mot = new JSONObject();
            if (frontMotorSpeed != UNAVAILABLE) mot.put("frontSpeed", frontMotorSpeed);
            if (rearMotorSpeed != UNAVAILABLE) mot.put("rearSpeed", rearMotorSpeed);
            putIfValid(mot, "frontTorque", frontMotorTorque);
            if (engineSpeedRpm != UNAVAILABLE) mot.put("engineRpm", engineSpeedRpm);
            putIfValid(mot, "enginePowerKw", enginePowerKw);
            j.put("motor", mot);

            // Energy
            JSONObject eng = new JSONObject();
            if (energyMode != UNAVAILABLE) eng.put("mode", energyMode);
            if (operationMode != UNAVAILABLE) eng.put("operationMode", operationMode);
            putIfValid(eng, "totalElecCon", totalElecCon);
            putIfValid(eng, "totalFuelCon", totalFuelCon);
            j.put("energy", eng);

            // Range
            JSONObject rng = new JSONObject();
            if (elecRangeKm != UNAVAILABLE) rng.put("elecKm", elecRangeKm);
            if (fuelRangeKm != UNAVAILABLE) rng.put("fuelKm", fuelRangeKm);
            if (bodyworkRangeKm != UNAVAILABLE) rng.put("bodyworkKm", bodyworkRangeKm);
            j.put("range", rng);

            // Mileage
            JSONObject mil = new JSONObject();
            if (totalMileageKm != UNAVAILABLE) mil.put("totalKm", totalMileageKm);
            if (evMileageKm != UNAVAILABLE) mil.put("evKm", evMileageKm);
            j.put("mileage", mil);

            // Charging
            JSONObject chg = new JSONObject();
            if (chargingState != UNAVAILABLE) chg.put("state", chargingState);
            if (chargingGunState != UNAVAILABLE) chg.put("gunState", chargingGunState);
            if (chargerWorkState != UNAVAILABLE) chg.put("chargerState", chargerWorkState);
            putIfValid(chg, "powerKw", chargingPowerKw);
            putIfValid(chg, "externalPowerKw", externalChargingPowerKw);
            j.put("charging", chg);

            // Gear
            if (gearMode != UNAVAILABLE) j.put("gearMode", gearMode);

            // Tyres
            if (tyrePressure != null) {
                JSONArray tp = new JSONArray();
                for (int p : tyrePressure) tp.put(p);
                j.put("tyrePressure", tp);
            }

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
            if (leftTurnState != UNAVAILABLE) lt.put("leftTurn", leftTurnState);
            if (rightTurnState != UNAVAILABLE) lt.put("rightTurn", rightTurnState);
            lt.put("lowBeam", lowBeam);
            lt.put("highBeam", highBeam);
            lt.put("rearFog", rearFog);
            lt.put("frontFog", frontFog);
            lt.put("hazard", hazard);
            j.put("lights", lt);

            // Seatbelts
            if (seatbeltStatus != null) {
                JSONArray sb = new JSONArray();
                for (int s : seatbeltStatus) sb.put(s);
                j.put("seatbeltStatus", sb);
            }

            // Climate
            JSONObject clim = new JSONObject();
            if (acStartState != UNAVAILABLE) clim.put("acOn", acStartState);
            if (acCycleMode != UNAVAILABLE) clim.put("cycleMode", acCycleMode);
            if (acWindMode != UNAVAILABLE) clim.put("windMode", acWindMode);
            if (tempUnit != UNAVAILABLE) clim.put("tempUnit", tempUnit);
            j.put("climate", clim);

            // Sensor
            putIfValid(j, "slopeDegrees", slopeDegrees);

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
        } catch (Exception ignored) {}
        return j;
    }

    private static void putIfValid(JSONObject j, String key, double val) throws org.json.JSONException {
        if (!Double.isNaN(val)) j.put(key, Math.round(val * 100) / 100.0);
    }

    /** Create a new builder pre-filled with this snapshot's values */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.vin = vin; b.socPercent = socPercent; b.socHevPercent = socHevPercent;
        b.capacityAh = capacityAh; b.remainKwh = remainKwh; b.voltage12v = voltage12v;
        b.voltageLevelRaw = voltageLevelRaw; b.highCellTempC = highCellTempC;
        b.lowCellTempC = lowCellTempC; b.avgCellTempC = avgCellTempC;
        b.waterTempC = waterTempC; b.outsideTempC = outsideTempC; b.insideTempC = insideTempC;
        b.bodyworkBattTempC = bodyworkBattTempC; b.highCellVoltage = highCellVoltage;
        b.lowCellVoltage = lowCellVoltage; b.speedKmh = speedKmh; b.accelPercent = accelPercent;
        b.brakePercent = brakePercent; b.frontMotorSpeed = frontMotorSpeed;
        b.rearMotorSpeed = rearMotorSpeed; b.frontMotorTorque = frontMotorTorque;
        b.engineSpeedRpm = engineSpeedRpm; b.enginePowerKw = enginePowerKw;
        b.energyMode = energyMode; b.operationMode = operationMode;
        b.totalElecCon = totalElecCon; b.totalFuelCon = totalFuelCon;
        b.elecRangeKm = elecRangeKm; b.fuelRangeKm = fuelRangeKm;
        b.bodyworkRangeKm = bodyworkRangeKm; b.totalMileageKm = totalMileageKm;
        b.evMileageKm = evMileageKm; b.chargingState = chargingState;
        b.chargingGunState = chargingGunState; b.chargerWorkState = chargerWorkState;
        b.chargingPowerKw = chargingPowerKw; b.externalChargingPowerKw = externalChargingPowerKw;
        b.hvPackVoltage = hvPackVoltage;
        b.gearMode = gearMode; b.tyrePressure = tyrePressure; b.doorLockStatus = doorLockStatus;
        b.windowOpenPercent = windowOpenPercent; b.leftTurnState = leftTurnState;
        b.rightTurnState = rightTurnState; b.lowBeam = lowBeam; b.highBeam = highBeam;
        b.rearFog = rearFog; b.frontFog = frontFog; b.hazard = hazard;
        b.dayTimeLight = dayTimeLight; b.seatbeltStatus = seatbeltStatus;
        b.acStartState = acStartState; b.acCycleMode = acCycleMode; b.acWindMode = acWindMode;
        b.tempUnit = tempUnit; b.slopeDegrees = slopeDegrees; b.powerLevel = powerLevel;
        b.mcuStatus = mcuStatus; b.emergencyAlarmState = emergencyAlarmState;
        b.radarDistances = radarDistances; b.timestamp = timestamp;
        b.availableDevices = availableDevices; b.unavailableDevices = unavailableDevices;
        return b;
    }

    public static class Builder {
        String vin;
        double socPercent = NaN, socHevPercent = NaN, capacityAh = NaN, remainKwh = NaN;
        double voltage12v = NaN; int voltageLevelRaw = UNAVAILABLE;
        double highCellTempC = NaN, lowCellTempC = NaN, avgCellTempC = NaN;
        double waterTempC = NaN, outsideTempC = NaN, insideTempC = NaN, bodyworkBattTempC = NaN;
        double highCellVoltage = NaN, lowCellVoltage = NaN;
        double speedKmh = NaN; int accelPercent = UNAVAILABLE, brakePercent = UNAVAILABLE;
        int frontMotorSpeed = UNAVAILABLE, rearMotorSpeed = UNAVAILABLE;
        double frontMotorTorque = NaN; int engineSpeedRpm = UNAVAILABLE; double enginePowerKw = NaN;
        int energyMode = UNAVAILABLE, operationMode = UNAVAILABLE;
        double totalElecCon = NaN, totalFuelCon = NaN;
        int elecRangeKm = UNAVAILABLE, fuelRangeKm = UNAVAILABLE, bodyworkRangeKm = UNAVAILABLE;
        int totalMileageKm = UNAVAILABLE, evMileageKm = UNAVAILABLE;
        int chargingState = UNAVAILABLE, chargingGunState = UNAVAILABLE, chargerWorkState = UNAVAILABLE;
        double chargingPowerKw = NaN, externalChargingPowerKw = NaN, hvPackVoltage = NaN;
        int gearMode = UNAVAILABLE;
        int[] tyrePressure, doorLockStatus, windowOpenPercent, seatbeltStatus, radarDistances;
        int leftTurnState = UNAVAILABLE, rightTurnState = UNAVAILABLE;
        boolean lowBeam, highBeam, rearFog, frontFog, hazard; int dayTimeLight = UNAVAILABLE;
        int acStartState = UNAVAILABLE, acCycleMode = UNAVAILABLE, acWindMode = UNAVAILABLE, tempUnit = UNAVAILABLE;
        double slopeDegrees = NaN;
        int powerLevel = UNAVAILABLE, mcuStatus = UNAVAILABLE, emergencyAlarmState = UNAVAILABLE;
        long timestamp = System.currentTimeMillis();
        String[] availableDevices, unavailableDevices;

        public Builder vin(String v) { vin = v; return this; }
        public Builder socPercent(double v) { socPercent = v; return this; }
        public Builder socHevPercent(double v) { socHevPercent = v; return this; }
        public Builder capacityAh(double v) { capacityAh = v; return this; }
        public Builder remainKwh(double v) { remainKwh = v; return this; }
        public Builder voltage12v(double v) { voltage12v = v; return this; }
        public Builder voltageLevelRaw(int v) { voltageLevelRaw = v; return this; }
        public Builder highCellTempC(double v) { highCellTempC = v; return this; }
        public Builder lowCellTempC(double v) { lowCellTempC = v; return this; }
        public Builder avgCellTempC(double v) { avgCellTempC = v; return this; }
        public Builder waterTempC(double v) { waterTempC = v; return this; }
        public Builder outsideTempC(double v) { outsideTempC = v; return this; }
        public Builder insideTempC(double v) { insideTempC = v; return this; }
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
        public Builder enginePowerKw(double v) { enginePowerKw = v; return this; }
        public Builder energyMode(int v) { energyMode = v; return this; }
        public Builder operationMode(int v) { operationMode = v; return this; }
        public Builder totalElecCon(double v) { totalElecCon = v; return this; }
        public Builder totalFuelCon(double v) { totalFuelCon = v; return this; }
        public Builder elecRangeKm(int v) { elecRangeKm = v; return this; }
        public Builder fuelRangeKm(int v) { fuelRangeKm = v; return this; }
        public Builder bodyworkRangeKm(int v) { bodyworkRangeKm = v; return this; }
        public Builder totalMileageKm(int v) { totalMileageKm = v; return this; }
        public Builder evMileageKm(int v) { evMileageKm = v; return this; }
        public Builder chargingState(int v) { chargingState = v; return this; }
        public Builder chargingGunState(int v) { chargingGunState = v; return this; }
        public Builder chargerWorkState(int v) { chargerWorkState = v; return this; }
        public Builder chargingPowerKw(double v) { chargingPowerKw = v; return this; }
        public Builder externalChargingPowerKw(double v) { externalChargingPowerKw = v; return this; }
        public Builder hvPackVoltage(double v) { hvPackVoltage = v; return this; }
        public Builder gearMode(int v) { gearMode = v; return this; }
        public Builder tyrePressure(int[] v) { tyrePressure = v; return this; }
        public Builder doorLockStatus(int[] v) { doorLockStatus = v; return this; }
        public Builder windowOpenPercent(int[] v) { windowOpenPercent = v; return this; }
        public Builder leftTurnState(int v) { leftTurnState = v; return this; }
        public Builder rightTurnState(int v) { rightTurnState = v; return this; }
        public Builder lowBeam(boolean v) { lowBeam = v; return this; }
        public Builder highBeam(boolean v) { highBeam = v; return this; }
        public Builder rearFog(boolean v) { rearFog = v; return this; }
        public Builder frontFog(boolean v) { frontFog = v; return this; }
        public Builder hazard(boolean v) { hazard = v; return this; }
        public Builder dayTimeLight(int v) { dayTimeLight = v; return this; }
        public Builder seatbeltStatus(int[] v) { seatbeltStatus = v; return this; }
        public Builder acStartState(int v) { acStartState = v; return this; }
        public Builder acCycleMode(int v) { acCycleMode = v; return this; }
        public Builder acWindMode(int v) { acWindMode = v; return this; }
        public Builder tempUnit(int v) { tempUnit = v; return this; }
        public Builder slopeDegrees(double v) { slopeDegrees = v; return this; }
        public Builder powerLevel(int v) { powerLevel = v; return this; }
        public Builder mcuStatus(int v) { mcuStatus = v; return this; }
        public Builder emergencyAlarmState(int v) { emergencyAlarmState = v; return this; }
        public Builder radarDistances(int[] v) { radarDistances = v; return this; }
        public Builder availableDevices(String[] v) { availableDevices = v; return this; }
        public Builder unavailableDevices(String[] v) { unavailableDevices = v; return this; }

        public BydVehicleData build() {
            timestamp = System.currentTimeMillis();
            return new BydVehicleData(this);
        }
    }
}
