package com.overdrive.app.server;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * API handler for the Vehicle Control page.
 * 
 * Endpoints:
 *   GET  /api/vehicle/state       — current door/window/trunk/lock state from BydVehicleData
 *   POST /api/vehicle/lock        — lock via BYD Cloud
 *   POST /api/vehicle/unlock      — unlock via BYD Cloud
 *   POST /api/vehicle/trunk       — open/close/stop trunk (local HAL)
 *   POST /api/vehicle/window      — window control (local HAL)
 *   POST /api/vehicle/flash       — flash lights via BYD Cloud
 *   GET  /api/vehicle/cloud-status — BYD Cloud connection status
 *   GET  /api/vehicle/cloud-lock   — cached cloud lock state, refreshes via REST if stale
 */
public class VehicleControlApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("VehicleControlApi");

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        // GET /api/vehicle/state
        if (cleanPath.equals("/api/vehicle/state") && method.equals("GET")) {
            handleGetState(out);
            return true;
        }

        // GET /api/vehicle/cloud-status
        if (cleanPath.equals("/api/vehicle/cloud-status") && method.equals("GET")) {
            handleCloudStatus(out);
            return true;
        }

        // GET /api/vehicle/cloud-lock
        if (cleanPath.equals("/api/vehicle/cloud-lock") && method.equals("GET")) {
            handleCloudLock(out);
            return true;
        }

        // POST /api/vehicle/lock
        if (cleanPath.equals("/api/vehicle/lock") && method.equals("POST")) {
            handleLock(out);
            return true;
        }

        // POST /api/vehicle/unlock
        if (cleanPath.equals("/api/vehicle/unlock") && method.equals("POST")) {
            handleUnlock(out);
            return true;
        }

        // POST /api/vehicle/trunk
        if (cleanPath.equals("/api/vehicle/trunk") && method.equals("POST")) {
            handleTrunk(out, body);
            return true;
        }

        // POST /api/vehicle/window
        if (cleanPath.equals("/api/vehicle/window") && method.equals("POST")) {
            handleWindow(out, body);
            return true;
        }

        // POST /api/vehicle/flash
        if (cleanPath.equals("/api/vehicle/flash") && method.equals("POST")) {
            handleFlash(out);
            return true;
        }

        // POST /api/vehicle/climate
        if (cleanPath.equals("/api/vehicle/climate") && method.equals("POST")) {
            handleClimate(out, body);
            return true;
        }

        // POST /api/vehicle/seat
        if (cleanPath.equals("/api/vehicle/seat") && method.equals("POST")) {
            handleSeat(out, body);
            return true;
        }

        // POST /api/vehicle/lights
        if (cleanPath.equals("/api/vehicle/lights") && method.equals("POST")) {
            handleLights(out, body);
            return true;
        }

        // POST /api/vehicle/adas
        if (cleanPath.equals("/api/vehicle/adas") && method.equals("POST")) {
            handleAdas(out, body);
            return true;
        }

        return false;
    }

    /**
     * Returns current vehicle state relevant to the control page:
     * doors, windows, trunk, lock status, SOC, range.
     */
    private static void handleGetState(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        BydDataCollector collector = BydDataCollector.getInstance();
        BydVehicleData data = collector.getData();

        if (data == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.vehicle_data_unavailable"));
            HttpResponse.sendJson(out, response.toString());
            return;
        }

        response.put("success", true);

        // Door lock status: 1=locked, 2=unlocked, -1=unknown
        // Index: 0=LF, 1=RF, 2=LR, 3=RR, 4=trunk, 5=unused, 6=overall(derived)
        //
        // The BYDAutoDoorLockDevice service does not expose lock state to user-UID
        // processes on most BYD firmwares (returns INVALID(0) for every area).
        // So we overlay the BYD cloud snapshot's per-door lock fields here. If
        // both the SDK and cloud are unavailable, values stay at -1.
        JSONObject doors = new JSONObject();
        if (data.doorLockStatus != null && data.doorLockStatus.length >= 7) {
            doors.put("lf", data.doorLockStatus[0]);
            doors.put("rf", data.doorLockStatus[1]);
            doors.put("lr", data.doorLockStatus[2]);
            doors.put("rr", data.doorLockStatus[3]);
            doors.put("trunk", data.doorLockStatus[4]);
            doors.put("hood", data.doorLockStatus[5]);
            doors.put("overall", data.doorLockStatus[6]);
        }
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            // Trigger an on-demand REST refresh if our cached snapshot is
            // stale. The call is internally rate-limited (30s cooldown) and
            // runs asynchronously; the *current* snapshot is used to render
            // this response, but the next request will see fresh data.
            new Thread(provider::refreshLockStateIfStale, "CloudLockRefresh").start();
            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
            if (cs != null && cs.hasValidLockState()) {
                // Cloud snapshot semantics:
                //   leftFrontDoorLock etc.: 1=UNLOCKED, 2=LOCKED (per pyBYD)
                // API contract semantics: 1=locked, 2=unlocked (inverted).
                int lf = cloudLockToApi(cs.leftFrontDoorLock);
                int rf = cloudLockToApi(cs.rightFrontDoorLock);
                int lr = cloudLockToApi(cs.leftRearDoorLock);
                int rr = cloudLockToApi(cs.rightRearDoorLock);
                if (lf != -1) doors.put("lf", lf);
                if (rf != -1) doors.put("rf", rf);
                if (lr != -1) doors.put("lr", lr);
                if (rr != -1) doors.put("rr", rr);
                int overall;
                if (cs.isAnyUnlocked()) overall = 2;
                else if (cs.isAllLocked()) overall = 1;
                else overall = -1;
                if (overall != -1) doors.put("overall", overall);
                doors.put("source", "cloud");
            }
        } catch (Exception e) {
            logger.debug("cloud-lock overlay failed: " + e.getMessage());
        }
        response.put("doors", doors);

        // Window open percent [1-6]: 0=closed, 100=fully open, -1=unknown
        // Index: 0=LF, 1=RF, 2=LR, 3=RR, 4=sunroof, 5=sunshade
        JSONObject windows = new JSONObject();
        if (data.windowOpenPercent != null && data.windowOpenPercent.length >= 4) {
            windows.put("lf", data.windowOpenPercent[0]);
            windows.put("rf", data.windowOpenPercent[1]);
            windows.put("lr", data.windowOpenPercent[2]);
            windows.put("rr", data.windowOpenPercent[3]);
            if (data.windowOpenPercent.length >= 5) windows.put("sunroof", data.windowOpenPercent[4]);
            if (data.windowOpenPercent.length >= 6) windows.put("sunshade", data.windowOpenPercent[5]);
        }
        response.put("windows", windows);

        // Trunk/tailgate status from extended bodywork
        JSONObject trunk = new JSONObject();
        // Back door status from feature ID (if available in toJson)
        // We use doorLockStatus[4] for trunk lock, and check body door status flags
        if (data.doorLockStatus != null && data.doorLockStatus.length >= 5) {
            trunk.put("lockStatus", data.doorLockStatus[4]);
        }
        response.put("trunk", trunk);

        // Sunroof
        JSONObject sunroof = new JSONObject();
        if (data.sunroofState != BydVehicleData.UNAVAILABLE) {
            sunroof.put("state", data.sunroofState);
        }
        if (data.sunroofPosition != BydVehicleData.UNAVAILABLE) {
            sunroof.put("position", data.sunroofPosition);
        }
        response.put("sunroof", sunroof);

        // Battery info for display
        JSONObject battery = new JSONObject();
        if (!Double.isNaN(data.socPercent)) battery.put("soc", data.socPercent);
        if (data.elecRangeKm != BydVehicleData.UNAVAILABLE) battery.put("rangeKm", data.elecRangeKm);
        if (data.bodyworkRangeKm != BydVehicleData.UNAVAILABLE) battery.put("bodyworkRangeKm", data.bodyworkRangeKm);
        response.put("battery", battery);

        // Lights
        JSONObject lights = new JSONObject();
        lights.put("lowBeam", data.lowBeam);
        lights.put("highBeam", data.highBeam);
        lights.put("hazard", data.hazard);
        lights.put("dayTimeLight", data.dayTimeLight);
        response.put("lights", lights);

        // ADAS
        JSONObject adas = new JSONObject();
        adas.put("speedLimitWarning", data.speedLimitWarning);
        response.put("adas", adas);

        // Climate — only report AC state if vehicle power is on (powerLevel >= 2)
        // Otherwise stale cached data shows AC on when car is actually off
        JSONObject climate = new JSONObject();
        boolean vehiclePoweredOn = (data.powerLevel != BydVehicleData.UNAVAILABLE && data.powerLevel >= 2);
        if (data.acStartState != BydVehicleData.UNAVAILABLE) {
            climate.put("acOn", vehiclePoweredOn && data.acStartState == 1);
        }
        if (!Double.isNaN(data.insideTempC)) climate.put("insideTempC", data.insideTempC);
        if (data.acWindMode != BydVehicleData.UNAVAILABLE) climate.put("windMode", data.acWindMode);
        if (data.acFanLevel != BydVehicleData.UNAVAILABLE && vehiclePoweredOn) climate.put("fanLevel", data.acFanLevel);
        response.put("climate", climate);

        // Tyres — per-corner pressure (kPa + PSI), temperature, and the three
        // independent state enums (pressure under/over, slow/fast leak, signal
        // lost). Indexed [FL, FR, RL, RR]. The web UI's tyre callouts read this
        // block directly; if any required source is missing the corner falls
        // back to {available:false} so the UI shows a grey "no signal" state.
        JSONObject tyres = new JSONObject();
        boolean anyTyreData = data.tyrePressure != null
                || data.tyrePressureState != null
                || data.tyreAirLeakState != null
                || data.tyreSignalState != null
                || data.tyreTemperature != null;
        if (anyTyreData) {
            String[] keys = { "fl", "fr", "rl", "rr" };
            for (int i = 0; i < keys.length; i++) {
                JSONObject t = new JSONObject();
                int kPa = (data.tyrePressure != null && i < data.tyrePressure.length)
                        ? data.tyrePressure[i] : BydVehicleData.UNAVAILABLE;
                if (kPa != BydVehicleData.UNAVAILABLE && kPa > 0) {
                    t.put("kPa", kPa);
                    // PSI = kPa * 0.1450377 (matches the AutoCommander
                    // UnitFormatter conversion). One decimal place is
                    // enough to distinguish ±3 kPa steps the BYD TPMS
                    // actually reports — integer rounding collapses
                    // 247/250/253 kPa all to 36 psi, hiding real change.
                    double psi = kPa * 0.1450377;
                    t.put("psi", Math.round(psi * 10.0) / 10.0);
                }
                if (data.tyreTemperature != null && i < data.tyreTemperature.length
                        && data.tyreTemperature[i] != BydVehicleData.UNAVAILABLE) {
                    t.put("temperatureC", data.tyreTemperature[i]);
                }
                if (data.tyrePressureState != null && i < data.tyrePressureState.length) {
                    t.put("pressureState", data.tyrePressureState[i]);
                }
                if (data.tyreAirLeakState != null && i < data.tyreAirLeakState.length) {
                    t.put("airLeakState", data.tyreAirLeakState[i]);
                }
                if (data.tyreSignalState != null && i < data.tyreSignalState.length) {
                    t.put("signalState", data.tyreSignalState[i]);
                }
                // Available = we got at least one valid pressure reading.
                t.put("available", t.has("kPa"));
                tyres.put(keys[i], t);
            }
            tyres.put("available", true);
        } else {
            tyres.put("available", false);
        }
        response.put("tyres", tyres);

        // Engine telemetry block was removed: the BYD Auto SDK's
        // engineCoolantLevel / oilLevel / waterTempC / gearMode feeds
        // were producing unreliable values on the test PHEV
        // (cold-engine sentinels, conflicting Engine vs Setting device
        // readings, raw 28/254 oil dipstick that AutoCommander itself
        // refuses to display). Don't reintroduce without verifying each
        // field against the cluster's own readout first.

        response.put("timestamp", data.timestamp);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Returns BYD Cloud connection status.
     */
    private static void handleCloudStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        response.put("success", true);
        response.put("configured", config.isConfigured());
        response.put("verified", config.isVerified());
        response.put("enabled", config.enabled);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Returns the cloud-derived lock state. Triggers a one-shot REST refresh
     * on the data-provider thread if MQTT data is stale or unavailable.
     * The refresh is rate-limited inside the provider to protect BYD's API.
     */
    private static void handleCloudLock(OutputStream out) throws Exception {
        com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();

        // Kick off the refresh in the background — don't block the HTTP
        // response on a BYD round-trip (REST + login can take seconds).
        // The provider applies its own staleness check + cooldown.
        new Thread(provider::refreshLockStateIfStale, "CloudLockRefresh").start();

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("status", provider.getStatusJson());
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Lock the car via BYD Cloud API.
     */
    private static void handleLock(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydCloudClient client = getCloudClient();
            boolean success = client.lock(getVin());
            logger.info("Lock: cloud lock result=" + success);
            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", "lock");
        } catch (Exception e) {
            logger.warn("Lock command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Unlock the car via BYD Cloud API.
     */
    private static void handleUnlock(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydCloudClient client = getCloudClient();
            boolean success = client.unlock(getVin());
            logger.info("Unlock: cloud unlock result=" + success);
            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", "unlock");
        } catch (Exception e) {
            logger.warn("Unlock command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Trunk control.
     * Open: Unlocks car first (via cloud) to avoid alarm, then opens trunk (local HAL).
     * Close: Locks car (via cloud) which also closes the trunk.
     * Body: { "action": "open" | "close" | "stop" }
     */
    private static void handleTrunk(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            String action = "open";
            if (body != null && !body.isEmpty()) {
                JSONObject req = new JSONObject(body);
                action = req.optString("action", "open");
            }

            BydDataCollector collector = BydDataCollector.getInstance();
            boolean success;
            switch (action) {
                case "close":
                    // Close trunk via local HAL (direct motor command)
                    success = collector.closeTailgate();
                    logger.info("Trunk close: local HAL closeTailgate result=" + success);
                    break;
                case "stop":
                    success = collector.stopTailgate();
                    break;
                case "open":
                default:
                    // ALWAYS unlock before opening trunk. The CAN bus lock status is unreliable
                    // (often returns -1/unknown), so we cannot trust it to skip the unlock step.
                    // Sending unlock when already unlocked is harmless on most BYD models —
                    // the cloud API returns success and the body controller ignores the redundant command.
                    // The alternative (skipping unlock and triggering the alarm) is far worse.
                    response.put("step", "unlocking");
                    boolean unlockSuccess = false;
                    try {
                        BydCloudClient client = getCloudClient();
                        unlockSuccess = client.unlock(getVin());
                        logger.info("Trunk open: cloud unlock result=" + unlockSuccess);
                        response.put("unlocked", unlockSuccess);
                        if (unlockSuccess) {
                            // Wait for unlock to take effect before opening trunk
                            Thread.sleep(2000);
                        }
                    } catch (Exception e) {
                        logger.warn("Trunk open: cloud unlock failed: " + e.getMessage());
                        response.put("unlockError", e.getMessage());
                        unlockSuccess = false;
                    }

                    // SAFETY: Do NOT open trunk if unlock failed — this triggers the alarm.
                    // The car may still be locked; opening the tailgate motor while locked
                    // causes the body controller to fire the anti-theft alarm.
                    if (!unlockSuccess) {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.vehicle_trunk_unlock_failed"));
                        response.put("action", action);
                        HttpResponse.sendJson(out, response.toString());
                        return;
                    }

                    response.put("step", "opening");
                    success = collector.openTailgate();
                    break;
            }

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", action);
        } catch (Exception e) {
            logger.warn("Trunk command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Window control via local HAL (BydDataCollector).
     * Body: one of:
     *   { "area": 1-4 (LF/RF/LR/RR) or 0 for all, "command": 1=open, 2=close, 3=stop }
     *   { "area": 1-4,                              "targetPercent": 0..100 }
     *   { "area": 5-6, (Sunroof and Sunshade),      "targetPercent": 0..100 }
     *
     * targetPercent triggers closed-loop positioning: backend drives the
     * window and auto-stops at the target. Returns immediately; the motion
     * continues on a background thread.
     */
    private static void handleWindow(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            int area = req.optInt("area", 0);
            BydDataCollector collector = BydDataCollector.getInstance();

            if (req.has("targetPercent")) {
                if (area < 1 || area > 6) {
                    response.put("success", false);
                    response.put("error", Messages.get("errors.vehicle_window_target_requires_area"));
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                int target = req.getInt("targetPercent");
                boolean scheduled = collector.moveWindowToPercent(area, target);
                logger.info("Window: area=" + areaName(area) + " target=" + target
                    + "% scheduled=" + scheduled);
                response.put("success", true);
                response.put("scheduled", scheduled);
                response.put("area", area);
                response.put("targetPercent", target);
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            int command = req.optInt("command", 2); // default close
            boolean success;
            if (area == 0) {
                success = collector.setAllWindowsCommand(command);
            } else {
                success = collector.setWindowCommand(area, command);
            }
            logger.info("Window: area=" + areaName(area) + " cmd=" + windowCmdName(command)
                + " result=" + success);

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("area", area);
            response.put("command", command);
        } catch (Exception e) {
            logger.warn("Window command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Flash lights via BYD Cloud API.
     */
    private static void handleFlash(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydCloudClient client = getCloudClient();
            boolean success = client.flashLightsNoWait(getVin());
            logger.info("Flash: cloud flashLights dispatched=" + success);
            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", "flash");
        } catch (Exception e) {
            logger.warn("Flash command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Climate control via local HAL.
     * Body: { "action": "power_on"|"power_off"|"set_temp"|"set_fan",
     *         "zone": 1|2, "temp": 17-33, "fan": 1-7 }
     */
    private static void handleClimate(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String action = req.optString("action", "");
            BydDataCollector collector = BydDataCollector.getInstance();
            boolean success = false;

            String detail;
            switch (action) {
                case "power_on":
                    success = collector.setAcPower(true);
                    detail = "";
                    break;
                case "power_off":
                    success = collector.setAcPower(false);
                    detail = "";
                    break;
                case "set_temp":
                    int zone = req.optInt("zone", 1);
                    double temp = req.optDouble("temp", 22);
                    success = collector.setAcTemperature(zone, temp);
                    detail = " zone=" + zone + " temp=" + temp + "°C";
                    break;
                case "set_fan":
                    int fan = req.optInt("fan", 3);
                    success = collector.setAcFanLevel(fan);
                    detail = " fan=" + fan;
                    break;
                default:
                    logger.warn("Climate: unknown action '" + action + "'");
                    response.put("success", false);
                    response.put("error", Messages.get("errors.vehicle_unknown_action_with_action", action));
                    HttpResponse.sendJson(out, response.toString());
                    return;
            }
            logger.info("Climate: action=" + action + detail + " result=" + success);

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", action);
        } catch (Exception e) {
            logger.warn("Climate command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Seat heating/ventilation/memory-recall via local HAL.
     * Body: { "action": "heating"|"ventilation"|"position", "position": 1-4, "level": 0-3 }
     * Position: 1=driver, 2=passenger, 3=rear-left, 4=rear-right
     * Level: 0=off, 1=low, 2=medium, 3=high (clamped to 0-2 internally)
     * For action="position", `position` is the stored memory slot (1-2).
     */
    private static void handleSeat(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String action = req.optString("action", "heating");
            int position = req.optInt("position", 1);
            int level = req.optInt("level", 0);
            BydDataCollector collector = BydDataCollector.getInstance();
            boolean success;

            if ("ventilation".equals(action)) {
                success = collector.setSeatVentilation(position, level);
            } else if ("position".equals(action)) {
                success = collector.setSeatMemoryPosition(position);
            } else {
                success = collector.setSeatHeating(position, level);
            }
            logger.info("Seat: action=" + action + " pos=" + seatPosName(position)
                + " level=" + level + " result=" + success);

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("action", action);
            response.put("position", position);
            response.put("level", level);
        } catch (Exception e) {
            logger.warn("Seat command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * Light controls.
     * Body: { "target": "dayTimeLight", "enable": true|false }
     */
    private static void handleLights(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String target = req.optString("target", null);
            boolean enable = req.optBoolean("enable", true);
            BydDataCollector collector = BydDataCollector.getInstance();
            boolean success = false;
            String errorKey = null;

            if ("dayTimeLight".equals(target)) {
                success = collector.setDayTimeLight(enable);
                if (!success) errorKey = "errors.vehicle_drl_set_failed";
            } else {
                errorKey = "errors.vehicle_unsupported_target_with_target";
            }
            logger.info("Lights: target=" + target + " enable=" + enable
                    + " result=" + success);

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("target", target);
            response.put("enable", enable);
            if (errorKey != null) {
                response.put("error", "errors.vehicle_unsupported_target_with_target".equals(errorKey)
                        ? Messages.get(errorKey, target)
                        : Messages.get(errorKey));
            }
        } catch (Exception e) {
            logger.warn("Light command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * ADAS controls.
     * Body: { "target": "speedLimitWarning", "enable": true|false }
     */
    private static void handleAdas(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String target = req.optString("target", null);
            boolean enable = req.optBoolean("enable", true);
            BydDataCollector collector = BydDataCollector.getInstance();
            boolean success = false;
            String errorKey = null;

            if ("speedLimitWarning".equals(target)) {
                success = collector.setSpeedLimitWarning(enable);
                if (!success) errorKey = "errors.vehicle_slw_set_failed";
            } else {
                errorKey = "errors.vehicle_unsupported_target_with_target";
            }
            logger.info("Adas: target=" + target + " enable=" + enable
                    + " result=" + success);

            response.put("success", true);
            response.put("commandSuccess", success);
            response.put("target", target);
            response.put("enable", enable);
            if (errorKey != null) {
                response.put("error", "errors.vehicle_unsupported_target_with_target".equals(errorKey)
                        ? Messages.get(errorKey, target)
                        : Messages.get(errorKey));
            }
        } catch (Exception e) {
            logger.warn("Adas command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    // ==================== LOG HELPERS ====================

    private static String areaName(int area) {
        switch (area) {
            case 0: return "all";
            case 1: return "LF";
            case 2: return "RF";
            case 3: return "LR";
            case 4: return "RR";
            case 5: return "Sunroof";
            case 6: return "Sunshade";
            default: return "?(" + area + ")";
        }
    }

    private static String windowCmdName(int cmd) {
        switch (cmd) {
            case 1: return "open";
            case 2: return "close";
            case 3: return "stop";
            default: return "?(" + cmd + ")";
        }
    }

    private static String seatPosName(int pos) {
        switch (pos) {
            case 1: return "driver";
            case 2: return "passenger";
            case 3: return "rear-left";
            case 4: return "rear-right";
            default: return "?(" + pos + ")";
        }
    }

    // ==================== HELPERS ====================

    /**
     * Convert BYD cloud per-door lock value to API contract.
     *   pyBYD reports: 1=UNLOCKED, 2=LOCKED on each *DoorLock field.
     *   API contract publishes: 1=locked, 2=unlocked (inverted, historical).
     * VehicleCloudSnapshot.LOCK_UNAVAILABLE / LOCK_UNKNOWN both map to -1.
     */
    private static int cloudLockToApi(int cloud) {
        if (cloud == 2) return 1; // LOCKED
        if (cloud == 1) return 2; // UNLOCKED
        return -1;
    }

    private static String getVin() throws Exception {
        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        if (!config.isConfigured() || config.vin.isEmpty()) {
            throw new Exception("BYD Cloud not configured or VIN missing");
        }
        return config.vin;
    }

    private static BydCloudClient getCloudClient() throws Exception {
        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        if (!config.isConfigured()) {
            throw new Exception("BYD Cloud not configured. Set up credentials in Settings → BYD Cloud.");
        }

        // Reuse the shared client owned by BydCloudDataProvider — creating a
        // fresh client here would race the MQTT subscriber's login() and
        // invalidate its session token (visible as code=1005 from the EMQ
        // broker endpoint). The shared client's session is already verified.
        BydCloudClient client = com.overdrive.app.byd.cloud.BydCloudDataProvider
                .getInstance().getSharedClient();
        if (client == null) {
            throw new Exception("BYD Cloud client not initialized. Verify credentials in Settings → BYD Cloud.");
        }
        // Re-verify the control PIN if needed — a no-op if already done.
        if (!config.vin.isEmpty()) {
            client.verifyControlPassword(config.vin);
        }
        return client;
    }
}
