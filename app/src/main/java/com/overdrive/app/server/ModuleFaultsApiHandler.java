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
 * only explicit TPMS, auxiliary-battery, and charging-state indicators already present in the
 * collector snapshot. Other modules are returned as {@code not_scanned}, never as healthy.
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

        JSONArray modules = new JSONArray();
        modules.put(buildTyreModule(data));
        modules.put(buildAuxiliaryBatteryModule(data));
        modules.put(buildChargingModule(charging));
        modules.put(notScanned("brakes", "Brakes / ABS", "Brake ECU DTC retrieval is not implemented."));
        modules.put(notScanned("steering", "Steering", "Steering ECU DTC retrieval is not implemented."));
        modules.put(notScanned("airbags", "Airbags / SRS", "Airbag and restraint ECU DTC retrieval is not implemented."));
        modules.put(notScanned("adas", "Driver assistance / ADAS", "ADAS ECU DTC retrieval is not implemented."));
        modules.put(notScanned("traction_battery", "Traction battery / BMS", "Battery-management ECU DTC retrieval is not implemented."));
        modules.put(notScanned("body_control", "Body control", "Body-control ECU DTC retrieval is not implemented."));
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

            if (signalState == 1) {
                warning = true;
                issues.add("TPMS sensor signal error");
            }
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

            boolean conditionKnown = pressureValid || pressureStateKnown || leakStateKnown;
            boolean anyKnown = conditionKnown || signalStateKnown;
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
                reading.put("summary", "Some TPMS data is present, but the pressure condition is incomplete.");
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
