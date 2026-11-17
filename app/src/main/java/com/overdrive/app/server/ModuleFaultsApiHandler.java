package com.overdrive.app.server;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatteryPowerData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Read-only, partial vehicle-fault overview built from telemetry OverDrive already reads.
 *
 * <p>This deliberately does not claim to scan ECU diagnostic trouble-code memory. The current
 * public compile-time BYD SDK surface does not provide a verified DTC reader contract, so this
 * handler never sends UDS/CAN requests and never clears, resets, or writes ECU state. It reports
 * TPMS, auxiliary-battery and charging indicators from the collector snapshot, plus the
 * instrument cluster's warning lamps and fault table on legacy cars ({@code getMalfunctionInfo},
 * {@code getMalfunctionInfo2}). Without a
 * readable lamp a module is returned as {@code not_scanned} or unavailable, never as healthy.
 */
public final class ModuleFaultsApiHandler {
    private static final String PATH = "/api/diagnostics/module-faults";
    private static final DaemonLogger logger = DaemonLogger.getInstance("ModuleFaultsApi");

    private static final String[] TYRE_IDS = {"fl", "fr", "rl", "rr"};
    private static final String[] TYRE_LABELS = {
            "Front left", "Front right", "Rear left", "Rear right"
    };

    private ModuleFaultsApiHandler() {}

    public static boolean handle(String method, String path, OutputStream out) throws Exception {
        if (!PATH.equals(path)) return false;
        if (!"GET".equalsIgnoreCase(method)) {
            HttpResponse.sendJson(out, 405,
                    new JSONObject().put("success", false)
                            .put("error", "This read-only endpoint accepts GET only.").toString());
            return true;
        }

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("readOnly", true);
        response.put("diagnosticMode", "existing_vehicle_telemetry");
        response.put("dtcReadAvailable", false);
        response.put("timestamp", System.currentTimeMillis());
        response.put("notice", "This is a partial live-warning overview, not an ECU DTC scan. No diagnostic commands are sent and no vehicle state is changed.");

        VehicleDataMonitor.ChargingSnapshot snapshot = null;
        BydVehicleData data = null;
        ChargingStateData charging = null;
        try {
            snapshot = VehicleDataMonitor.getInstance().getChargingSnapshot();
            if (snapshot != null) {
                data = snapshot.getVehicleData();
                charging = snapshot.getChargingState();
            } else {
                data = VehicleDataMonitor.getInstance().getVd();
            }
        } catch (Throwable t) {
            logger.debug("Could not read vehicle snapshot for module-fault overview: "
                    + t.getClass().getSimpleName());
        }

        response.put("vehicleDataAvailable", data != null);
        if (data != null && data.timestamp > 0L) {
            response.put("vehicleDataTimestamp", data.timestamp);
        }

        // Cluster warning lamps (legacy cars only; empty on DiLink 5, which keeps the placeholders).
        java.util.Map<String, Integer> lamps = readLamps();
        JSONArray modules = new JSONArray();
        modules.put(withLamps(buildTyreModule(data), lamps, TYRE_LAMPS));
        modules.put(withLamps(buildAuxiliaryBatteryModule(data), lamps, AUX_BATTERY_LAMPS));
        modules.put(withLamps(buildChargingModule(charging), lamps, CHARGING_LAMPS));
        modules.put(lampModule(lamps, "brakes", "Brakes / ABS",
                "Brake ECU DTC retrieval is not implemented.", BRAKE_LAMPS));
        modules.put(lampModule(lamps, "steering", "Steering",
                "Steering ECU DTC retrieval is not implemented.", STEERING_LAMPS));
        modules.put(lampModule(lamps, "airbags", "Airbags / SRS",
                "Airbag and restraint ECU DTC retrieval is not implemented.", AIRBAG_LAMPS));
        modules.put(lampModule(lamps, "adas", "Driver assistance / ADAS",
                "ADAS ECU DTC retrieval is not implemented.", ADAS_LAMPS));
        modules.put(lampModule(lamps, "traction_battery", "Traction battery / BMS",
                "Battery-management ECU DTC retrieval is not implemented.", TRACTION_LAMPS));
        modules.put(lampModule(lamps, "motor_cooling", "Drive system and cooling",
                "Motor and cooling ECU DTC retrieval is not implemented.", DRIVE_COOLING_LAMPS));
        modules.put(lampModule(lamps, "body_control", "Body, lights and network",
                "Body-control ECU DTC retrieval is not implemented.", BODY_LAMPS));
        response.put("modules", modules);
        response.put("summary", buildSummary(modules));

        HttpResponse.sendJson(out, response.toString());
        return true;
    }

    private static JSONObject buildTyreModule(BydVehicleData data) throws Exception {
        JSONObject module = baseModule(
                "tpms", "Tyre pressure monitoring", "unavailable",
                "BYD TPMS telemetry", "No tyre readings are currently available.");
        JSONArray readings = new JSONArray();
        int knownCorners = 0;
        int clearCorners = 0;
        int warningCorners = 0;
        JSONObject limits = readTyreLimits();

        for (int i = 0; i < TYRE_IDS.length; i++) {
            int pressure = arrayValue(data != null ? data.tyrePressure : null, i);
            int pressureState = arrayValue(data != null ? data.tyrePressureState : null, i);
            int leakState = arrayValue(data != null ? data.tyreAirLeakState : null, i);
            int signalState = arrayValue(data != null ? data.tyreSignalState : null, i);
            boolean pressureValid = pressure > 0 && pressure != BydVehicleData.UNAVAILABLE;
            boolean pressureStateKnown = pressureState >= 0 && pressureState <= 2;
            boolean leakStateKnown = leakState >= 0 && leakState <= 2;
            boolean signalStateKnown = signalState == 0 || signalState == 1;

            boolean warning = false;
            java.util.ArrayList<String> issues = new java.util.ArrayList<>();
            int low = limits.optInt(i < 2 ? "frontLow" : "rearLow", 234);
            int high = limits.optInt(i < 2 ? "frontHigh" : "rearHigh", 310);
            int critical = limits.optInt("criticalLow", 152);

            boolean signalError = signalState == 1;   // shown as "No signal" elsewhere, not a fault
            if (leakState == 2) {
                warning = true;
                issues.add("Fast air leak reported");
            } else if (leakState == 1) {
                warning = true;
                issues.add("Slow air leak reported");
            }
            if (pressureState == BydVehicleData.TYRE_PRESSURE_STATE_UNDERPRESSURE) {
                warning = true;
                issues.add("Vehicle reports under-pressure");
            } else if (pressureState == BydVehicleData.TYRE_PRESSURE_STATE_OVERPRESSURE) {
                warning = true;
                issues.add("Vehicle reports over-pressure");
            }
            if (pressureValid) {
                if (pressure <= critical) {
                    warning = true;
                    issues.add("Critical low pressure");
                } else if (pressure < low) {
                    warning = true;
                    issues.add("Pressure below configured limit");
                } else if (pressure > high) {
                    warning = true;
                    issues.add("Pressure above configured limit");
                }
            }

            // "Clear" needs a real pressure reading: BYD enum states often sit at 0 with no data.
            boolean conditionKnown = pressureValid && !signalError;
            boolean anyKnown = pressureValid || pressureStateKnown || leakStateKnown || signalStateKnown;
            String status;
            if (warning) {
                status = "warning";
                warningCorners++;
                knownCorners++;
            } else if (conditionKnown) {
                status = "clear";
                clearCorners++;
                knownCorners++;
            } else if (anyKnown) {
                status = "partial";
                knownCorners++;
            } else {
                status = "unavailable";
            }

            JSONObject reading = new JSONObject();
            reading.put("id", TYRE_IDS[i]);
            reading.put("label", TYRE_LABELS[i]);
            reading.put("status", status);
            if (pressureValid) reading.put("pressureKpa", pressure);
            if (pressureState != BydVehicleData.UNAVAILABLE) reading.put("pressureStateRaw", pressureState);
            if (leakState != BydVehicleData.UNAVAILABLE) reading.put("airLeakStateRaw", leakState);
            if (signalState != BydVehicleData.UNAVAILABLE) reading.put("signalStateRaw", signalState);
            if (!issues.isEmpty()) {
                reading.put("summary", join(issues, "; "));
            } else if ("clear".equals(status)) {
                reading.put("summary", "No tyre-pressure or leak warning is currently reported.");
            } else if ("partial".equals(status)) {
                reading.put("summary", signalError
                        ? "No signal from this wheel's TPMS sensor."
                        : "Some TPMS data is present, but the pressure condition is incomplete.");
            } else {
                reading.put("summary", "No reading available for this wheel.");
            }
            readings.put(reading);
        }

        String status;
        String summary;
        if (warningCorners > 0) {
            status = "warning";
            summary = warningCorners + " wheel(s) have a reported TPMS warning.";
        } else if (clearCorners == TYRE_IDS.length) {
            status = "clear";
            summary = "All four wheels report readings without a current TPMS warning.";
        } else if (knownCorners > 0) {
            status = "partial";
            summary = "TPMS data is incomplete; only " + knownCorners + " of four wheel(s) have usable readings.";
        } else {
            status = "unavailable";
            summary = "The vehicle has not provided usable TPMS readings.";
        }
        module.put("status", status);
        module.put("summary", summary);
        module.put("readings", readings);
        module.put("knownWheelCount", knownCorners);
        module.put("warningWheelCount", warningCorners);
        module.put("scope", "Current TPMS pressure, leak, and sensor-signal indicators; not stored ECU DTCs.");
        return module;
    }

    private static JSONObject buildAuxiliaryBatteryModule(BydVehicleData data) throws Exception {
        int level = BydVehicleData.UNAVAILABLE;
        if (data != null) {
            level = data.battery12vLevel != BydVehicleData.UNAVAILABLE
                    ? data.battery12vLevel : data.voltageLevelRaw;
        }
        String levelStatus = "unavailable";
        String levelName = "Unavailable";
        if (level == 0) {
            levelStatus = "warning";
            levelName = "Low";
        } else if (level == 1) {
            levelStatus = "clear";
            levelName = "Normal";
        } else if (level == 255) {
            levelName = "Invalid / unavailable";
        }

        BatteryPowerData voltageData = null;
        if (data != null && Double.isFinite(data.voltage12v)) {
            BatteryPowerData candidate = new BatteryPowerData(data.voltage12v);
            if (candidate.isValidRange()) voltageData = candidate;
        }
        // A LOW level contradicted by a healthy measured voltage is not shown as a fault.
        if (level == 0 && voltageData != null && !voltageData.isWarning
                && data.voltage12v >= 11.5) {
            levelStatus = "unavailable";
            levelName = "Low (not confirmed by voltage)";
        }
        String voltageStatus = "unavailable";
        String voltageSummary = "No valid 12V voltage reading is available.";
        if (voltageData != null) {
            voltageStatus = voltageData.isWarning ? "warning" : "clear";
            voltageSummary = voltageData.isCritical
                    ? "Critical low voltage reported."
                    : voltageData.isWarning
                            ? "Low voltage reported."
                            : "Voltage is above the configured warning threshold.";
        }

        boolean warning = "warning".equals(levelStatus) || "warning".equals(voltageStatus);
        boolean levelKnown = "clear".equals(levelStatus) || "warning".equals(levelStatus);
        boolean voltageKnown = "clear".equals(voltageStatus) || "warning".equals(voltageStatus);
        String status;
        String summary;
        if (warning) {
            status = "warning";
            summary = "The vehicle reports a low 12V battery level or voltage.";
        } else if (levelKnown && voltageKnown) {
            status = "clear";
            summary = "The vehicle-reported level and measured voltage show no current warning.";
        } else if (levelKnown || voltageKnown) {
            status = "partial";
            summary = "Some 12V battery indicators are available; other readings are unavailable.";
        } else {
            status = "unavailable";
            summary = "The vehicle has not provided a usable auxiliary-battery status.";
        }

        JSONObject module = baseModule(
                "aux_battery_12v", "12V auxiliary battery", status,
                "BYD bodywork / battery telemetry", summary);
        module.put("level", levelName);
        if (level != BydVehicleData.UNAVAILABLE) module.put("levelRaw", level);

        JSONArray readings = new JSONArray();
        readings.put(new JSONObject()
                .put("id", "aux_battery_level")
                .put("label", "Vehicle-reported battery level")
                .put("status", levelStatus)
                .put("summary", levelName));
        JSONObject voltageReading = new JSONObject()
                .put("id", "aux_battery_voltage")
                .put("label", "Measured 12V voltage")
                .put("status", voltageStatus)
                .put("summary", voltageSummary);
        if (voltageData != null) {
            double voltage = Math.round(voltageData.voltageVolts * 100.0) / 100.0;
            module.put("voltage", voltage);
            module.put("voltageUnit", "V");
            voltageReading.put("voltage", voltage);
            voltageReading.put("voltageUnit", "V");
            if (data.voltage12vAtMs > 0L) {
                voltageReading.put("observedAtMs", data.voltage12vAtMs);
            }
        }
        readings.put(voltageReading);
        module.put("readings", readings);
        module.put("scope", "Vehicle-reported 12V battery level and measured voltage; not a battery ECU DTC read.");
        return module;
    }

    private static JSONObject buildChargingModule(ChargingStateData charging) throws Exception {
        String status = "unavailable";
        String summary = "The vehicle has not provided a usable charging-system state.";
        JSONObject module;
        if (charging != null && charging.status != ChargingStateData.ChargingStatus.UNKNOWN) {
            boolean fault = charging.isError
                    || charging.status == ChargingStateData.ChargingStatus.TIMEOUT;
            status = fault ? "warning" : "clear";
            summary = fault
                    ? "The vehicle reports " + charging.stateName + "."
                    : "No charging-system warning is currently reported (" + charging.stateName + ").";
        }
        module = baseModule("charging", "Charging system", status,
                "BYD charging-state telemetry", summary);
        if (charging != null) {
            module.put("state", charging.stateName);
            module.put("stateCode", charging.stateCode);
            module.put("stateCategory", charging.status.name());
            if (charging.errorType != null) module.put("errorType", charging.errorType);
        }
        module.put("scope", "Current charging-state indicator; not stored charger or BMS DTCs.");
        return module;
    }

    // One reading per system: {id field, label, other fields for the same system}. MALFUNCTION_*
    // is the cluster lamp table, B_MALFUNCTION_* the cluster fault table (see readMalfunctionLamps).
    // Indicators that aren't faults (OK, EV/HEV mode, belt, parking brake applied) are left out.
    private static final String[][] TYRE_LAMPS = {
            {"MALFUNCTION_TYRE_PRESSURE", "Tyre pressure lamp"},
            {"B_MALFUNCTION_TIRE_PRESSURE_DETECTING_SYSTEM", "Tyre pressure monitoring system"},
            {"MALFUNCTION_QUICK_AIR_LEAK", "Fast air leak lamp"}};
    private static final String[][] AUX_BATTERY_LAMPS = {
            {"MALFUNCTION_LOW_VOLTAGE_BATTERY_SYSTEM", "Low-voltage battery system lamp",
                    "B_MALFUNCTION_LOW_VOLTAGE_POWER_SUPPLY_SYSTEM"},
            {"B_MALFUNCTION_IRON_BATTERY", "12V battery"},
            {"B_MALFUNCTION_IRON_BATTERY_POWER_LOW", "12V battery low charge"}};
    private static final String[][] CHARGING_LAMPS = {
            {"MALFUNCTION_CHARGING_SYSTEM", "Charging system lamp",
                    "B_MALFUNCTION_CHARGING_SYSTEM", "B_MALFUNCTION_VEHICLE_CHARGING_SYSTEM"},
            {"MALFUNCTION_CAR_CHARGER", "On-board charger lamp"},
            {"MALFUNCTION_AC_CHARGING_EQUIPMENT", "AC charging equipment lamp",
                    "B_MALFUNCTION_CHARGING_EQUIPMENT"},
            {"MALFUNCTION_CHARGING_GUN_CONNECTION", "Charging gun connection lamp"},
            {"MALFUNCTION_CHARGING_GUN_NOT_DISCONNECT", "Charging gun not released lamp"}};
    private static final String[][] BRAKE_LAMPS = {
            {"MALFUNCTION_ABS_SYSTEM", "ABS", "B_MALFUNCTION_ABS"},
            {"MALFUNCTION_ESP", "Stability control (ESP)", "B_MALFUNCTION_ESP"},
            {"MALFUNCTION_ELECTRIC_PARKING_BRAKE", "Electric parking brake",
                    "B_MALFUNCTION_ELECTRONIC_PARKING_SYSTEM"},
            {"B_MALFUNCTION_BRAKE_SYSTEM", "Brake system", "B_MALFUNCTION_BRAKE_SYSTEM_1"},
            {"B_MALFUNCTION_BRAKE_FLUID_LEVEL_LOW", "Brake fluid level"},
            {"B_MALFUNCTION_BRAKE_PAD", "Brake pads"},
            {"B_MALFUNCTION_HDC", "Hill descent control"}};
    private static final String[][] STEERING_LAMPS = {
            {"MALFUNCTION_EPS", "Power steering (EPS)", "B_MALFUNCTION_STEERING_SYSTEM"}};
    private static final String[][] AIRBAG_LAMPS = {
            {"MALFUNCTION_SRS", "Airbags (SRS)", "B_MALFUNCTION_SRS"}};
    private static final String[][] ADAS_LAMPS = {
            {"B_MALFUNCTION_ACC_SYSTEM", "Adaptive cruise control",
                    "MALFUNCTION_ADAPTIVE_CRUISE_CONTROL_SYSTEM"},
            {"B_MALFUNCTION_LKS", "Lane keeping assist", "MALFUNCTION_LANE_ASSISTANCE_SYSTEM"},
            {"B_MALFUNCTION_LDW", "Lane departure warning"},
            {"B_MALFUNCTION_BSD_SYSTEM", "Blind-spot detection",
                    "MALFUNCTION_BLIND_AREA_DETECTION_SYSTEM"},
            {"B_MALFUNCTION_FCW_SYSTEM", "Forward collision warning"},
            {"B_MALFUNCTION_AEB_SYSTEM", "Automatic emergency braking",
                    "MALFUNCTION_PREDICTIVE_BRAKE_SYSTEM"},
            {"B_MALFUNCTION_PEDESTRIAN_DETECTION_SYSTEM", "Pedestrian detection"},
            {"B_MALFUNCTION_TRAFFIC_SIGN_RECOGNITION_SYSTEM", "Traffic sign recognition",
                    "MALFUNCTION_TRAFFIC_SIGN_RECOGNITION_SYSTEM"},
            {"B_MALFUNCTION_HMA_SYSTEM", "High-beam assist",
                    "MALFUNCTION_INTELLIGENT_FAR_LIGHT_LAMP_SYSTEM"},
            {"B_MALFUNCTION_PRE_MILLIMETER_WAVE_RADAR", "Front radar"},
            {"B_MALFUNCTION_MULTIFUNCTION_VIDEO_CONTROLLER", "Front camera",
                    "B_MALFUNCTION_MULTI_VIDEO_CON_FUNCTION_LIMIT"}};
    private static final String[][] TRACTION_LAMPS = {
            {"MALFUNCTION_BATTERY", "Battery system"},
            {"MALFUNCTION_HIGH_BATTERY_TEMPERATURE", "Battery temperature"},
            {"MALFUNCTION_POWER_SYSTEM", "Power system", "B_MALFUNCTION_POWER_SYSTEM"},
            {"B_MALFUNCTION_EV_FUNCTION_LIMIT", "EV function limited"},
            {"MALFUNCTION_HIGH_VOLTAGE_SYSTEM_LEAKAGE", "High-voltage insulation"}};
    private static final String[][] DRIVE_COOLING_LAMPS = {
            {"MALFUNCTION_HIGH_MOTOR_TEMPERATURE", "Motor temperature"},
            {"B_MALFUNCTION_GEAR_SYSTEM", "Gear system"},
            {"MALFUNCTION_HIGH_WATER_TEMPERATURE", "Coolant temperature"},
            {"MALFUNCTION_MOTOR_COOLANT_TEMP_OVER_HIGH", "Motor coolant temperature"},
            {"MALFUNCTION_ENGINE_COOLANT_LOW", "Coolant level", "B_MALFUNCTION_COOLANT_LEVEL_LOW"},
            {"B_MALFUNCTION_AC_HEAT_MANAGEMENT", "Thermal management"},
            {"MALFUNCTION_SVS", "Service vehicle soon"}};
    private static final String[][] BODY_LAMPS = {
            {"MALFUNCTION_SMART_KEY", "Smart key system"},
            {"MALFUNCTION_INSTRUMENT_DISPLAY", "Instrument display"},
            {"MALFUNCTION_NETWORK_SYSTEM", "Vehicle network", "B_MALFUNCTION_AUTO_NETWORK"},
            {"B_MALFUNCTION_LEFT_HEADLAMP_SYSTEM", "Left headlamp"},
            {"B_MALFUNCTION_RIGHT_HEADLAMP_SYSTEM", "Right headlamp"},
            {"B_MALFUNCTION_MEMORY_SYSTEM", "Memory system"}};
    private static final String[] LAMP_FIELDS = lampFields(TYRE_LAMPS, AUX_BATTERY_LAMPS,
            CHARGING_LAMPS, BRAKE_LAMPS, STEERING_LAMPS, AIRBAG_LAMPS, ADAS_LAMPS, TRACTION_LAMPS,
            DRIVE_COOLING_LAMPS, BODY_LAMPS);

    private static String[] lampFields(String[][]... groups) {
        java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<>();
        for (String[][] group : groups) {
            for (String[] item : group) {
                for (int i = 0; i < item.length; i++) if (i != 1) fields.add(item[i]);
            }
        }
        return fields.toArray(new String[0]);
    }

    /** A system's raw state across its fields: any 1 wins, then any 0, else the first error. */
    private static Integer lampValue(java.util.Map<String, Integer> lamps, String[] item) {
        Integer first = null;
        boolean clear = false;
        for (int i = 0; i < item.length; i++) {
            if (i == 1) continue;
            Integer raw = lamps.get(item[i]);
            if (raw == null) continue;
            if (raw == 1) return raw;
            if (raw == 0) clear = true;
            if (first == null) first = raw;
        }
        return clear ? Integer.valueOf(0) : first;
    }

    /** False when this firmware has none of the system's fields, so the row is left out. */
    private static boolean onFirmware(java.util.Map<String, Integer> lamps, String[] item) {
        for (int i = 0; i < item.length; i++) {
            if (i != 1 && lamps.containsKey(item[i])) return true;
        }
        return false;
    }

    private static java.util.Map<String, Integer> readLamps() {
        try {
            return com.overdrive.app.byd.BydDataCollector.getInstance().readMalfunctionLamps(LAMP_FIELDS);
        } catch (Throwable t) {
            return java.util.Collections.emptyMap();
        }
    }

    /** 1 = lamp on, 0 = off (SDK DEVICE_HAS / NOT_HAS_THE_MALFUNCTION); anything else is unknown. */
    private static String lampStatus(Integer raw) {
        if (raw == null) return "unavailable";
        if (raw == 1) return "warning";
        return raw == 0 ? "clear" : "unavailable";
    }

    private static JSONObject lampReading(String field, String label, Integer raw) throws Exception {
        String status = lampStatus(raw);
        JSONObject reading = new JSONObject()
                .put("id", field.toLowerCase(java.util.Locale.ROOT))
                .put("label", label)
                .put("status", status)
                .put("summary", "warning".equals(status) ? "Warning lamp is on."
                        : "clear".equals(status) ? "Warning lamp is off."
                        : "Not reported by this car right now.");
        if (raw != null) reading.put("lampRaw", raw);
        return reading;
    }

    /** A module made of cluster warning lamps; the original placeholder when no lamp can be read. */
    private static JSONObject lampModule(java.util.Map<String, Integer> lamps, String id, String name,
            String notScannedReason, String[][] items) throws Exception {
        if (lamps.isEmpty()) return notScanned(id, name, notScannedReason);
        JSONArray readings = new JSONArray();
        java.util.ArrayList<String> on = new java.util.ArrayList<>();
        int shown = 0;
        int known = 0;
        for (String[] item : items) {
            if (!onFirmware(lamps, item)) continue;
            shown++;
            Integer raw = lampValue(lamps, item);
            String status = lampStatus(raw);
            if ("warning".equals(status)) on.add(item[1]);
            if (!"unavailable".equals(status)) known++;
            readings.put(lampReading(item[0], item[1], raw));
        }
        String status;
        String summary;
        if (!on.isEmpty()) {
            status = "warning";
            summary = "Warning lamp on: " + join(on, ", ") + ".";
        } else if (shown > 0 && known == shown) {
            status = "clear";
            summary = "No warning lamp is on.";
        } else if (known > 0) {
            status = "partial";
            summary = "No warning lamp is on, but some could not be read.";
        } else {
            status = "unavailable";
            summary = "This car is not reporting these warning lamps right now.";
        }
        JSONObject module = baseModule(id, name, status, "Instrument cluster warning lamps", summary);
        module.put("readings", readings);
        module.put("scope", "The warning lamps the instrument cluster shows; not stored ECU fault codes.");
        return module;
    }

    /** Adds cluster lamps to an existing module; a lit lamp makes it a warning. */
    private static JSONObject withLamps(JSONObject module, java.util.Map<String, Integer> lamps,
            String[][] items) throws Exception {
        if (lamps.isEmpty()) return module;
        JSONArray readings = module.optJSONArray("readings");
        if (readings == null) readings = new JSONArray();
        java.util.ArrayList<String> on = new java.util.ArrayList<>();
        boolean anyKnown = false;
        for (String[] item : items) {
            if (!onFirmware(lamps, item)) continue;
            Integer raw = lampValue(lamps, item);
            String status = lampStatus(raw);
            if ("warning".equals(status)) on.add(item[1]);
            if (!"unavailable".equals(status)) anyKnown = true;
            readings.put(lampReading(item[0], item[1], raw));
        }
        module.put("readings", readings);
        String current = module.optString("status", "unavailable");
        if (!on.isEmpty()) {
            module.put("status", "warning");
            String prefix = "warning".equals(current) ? module.optString("summary", "") + " " : "";
            module.put("summary", prefix + "Cluster warning lamp on: " + join(on, ", ") + ".");
        } else if (anyKnown && "unavailable".equals(current)) {
            module.put("status", "partial");
        }
        return module;
    }

    private static JSONObject notScanned(String id, String name, String reason) throws Exception {
        JSONObject module = baseModule(id, name, "not_scanned", "Not available in this release", reason);
        module.put("scope", "No ECU query is attempted for this module.");
        return module;
    }

    private static JSONObject baseModule(
            String id, String name, String status, String source, String summary) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("status", status)
                .put("source", source)
                .put("summary", summary);
    }

    private static JSONObject buildSummary(JSONArray modules) throws Exception {
        int warning = 0;
        int clear = 0;
        int partial = 0;
        int unavailable = 0;
        int notScanned = 0;
        for (int i = 0; i < modules.length(); i++) {
            String status = modules.getJSONObject(i).optString("status", "unavailable");
            if ("warning".equals(status)) warning++;
            else if ("clear".equals(status)) clear++;
            else if ("partial".equals(status)) partial++;
            else if ("not_scanned".equals(status)) notScanned++;
            else unavailable++;
        }
        return new JSONObject()
                .put("warningModules", warning)
                .put("clearModules", clear)
                .put("partialModules", partial)
                .put("unavailableModules", unavailable)
                .put("notScannedModules", notScanned);
    }

    private static JSONObject readTyreLimits() {
        try {
            JSONObject limits = UnifiedConfigManager.getTyreThresholds();
            return limits != null ? limits : new JSONObject();
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private static int arrayValue(int[] values, int index) {
        if (values == null || index < 0 || index >= values.length) {
            return BydVehicleData.UNAVAILABLE;
        }
        return values[index];
    }

    private static String join(java.util.List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(separator);
            result.append(value);
        }
        return result.toString();
    }
}
