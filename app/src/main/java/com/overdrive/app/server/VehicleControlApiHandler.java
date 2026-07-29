package com.overdrive.app.server;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.light.LightConstants;
import com.overdrive.app.byd.routing.DrivingSafetyGuard;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.byd.routing.VehicleCommandRouter.CommandResult;
import com.overdrive.app.byd.routing.VehicleCommandRouter.VehicleCommand;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * API handler for the Vehicle Control page. All write endpoints route
 * through {@link VehicleCommandRouter}, which decides per command whether to
 * attempt cloud first, fall back to SDK, or treat as cloud-only / SDK-only.
 *
 * Endpoints:
 *   GET  /api/vehicle/state         — current door/window/trunk/lock state
 *   GET  /api/vehicle/cloud-status  — BYD Cloud connection status
 *   GET  /api/vehicle/cloud-lock    — cached cloud lock state (REST refresh if stale)
 *   POST /api/vehicle/lock          — CLOUD_FIRST
 *   POST /api/vehicle/unlock        — CLOUD_FIRST
 *   POST /api/vehicle/trunk         — open=cloud-unlock+SDK, close/stop=SDK
 *   POST /api/vehicle/window        — area=0+close=CLOUD_FIRST, others SDK_ONLY
 *   POST /api/vehicle/flash         — CLOUD_FIRST (cloud-only on this gen)
 *   POST /api/vehicle/find-car      — CLOUD_FIRST (cloud-only on this gen)
 *   POST /api/vehicle/climate       — power=CLOUD_FIRST, set_temp/set_fan=SDK_ONLY
 *   POST /api/vehicle/seat          — SDK_ONLY
 *   POST /api/vehicle/lights        — SDK_ONLY
 *   POST /api/vehicle/adas          — SDK_ONLY
 *   POST /api/vehicle/setting       — SDK_ONLY
 *   POST /api/vehicle/media         — media volume (AudioManager) + screen brightness (setting HAL)
 *   POST /api/vehicle/battery-heat  — CLOUD_ONLY
 *   GET  /api/vehicle/charging-schedule  — local mirror { enabled, startChargeTime, endChargeTime, chargeWay }
 *   POST /api/vehicle/charging-schedule  — { startChargeTime, endChargeTime, chargeWay, enabled } CLOUD_ONLY
 *   GET  /api/vehicle/charge-cap         — { percent, enabled, supported } SDK_ONLY (SOC-target getSOCTarget, legacy getChargeStopCapacityState fallback)
 *   POST /api/vehicle/charge-cap         — { percent? 15..100, enabled? } SDK_ONLY (setSOCTarget+setSocSaveSwitch, legacy setChargeStop* fallback)
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

        // GET /api/vehicle/adas — read-only ADAS state (currently ESP), for
        // on-device verification of the ESP feature id before trusting the toggle.
        if (cleanPath.equals("/api/vehicle/adas") && method.equals("GET")) {
            handleAdasState(out);
            return true;
        }

        // POST /api/vehicle/adas
        if (cleanPath.equals("/api/vehicle/adas") && method.equals("POST")) {
            handleAdas(out, body);
            return true;
        }

        // POST /api/vehicle/setting
        if (cleanPath.equals("/api/vehicle/setting") && method.equals("POST")) {
            handleSetting(out, body);
            return true;
        }

        // POST /api/vehicle/media — media volume + screen brightness. These are
        // Android-level controls (AudioManager / BYD setting HAL), not cloud/CAN.
        if (cleanPath.equals("/api/vehicle/media") && method.equals("POST")) {
            handleMedia(out, body);
            return true;
        }

        // POST /api/vehicle/play-audio — play a user file (MP3/WAV/MP4) on a channel
        // through the daemon MediaPlayer. POST /api/vehicle/stop-audio — stop it.
        // Under the already-allowlisted /api/vehicle/ prefix so automation + keymap
        // reach it without widening the bypass surface.
        if (cleanPath.equals("/api/vehicle/play-audio") && method.equals("POST")) {
            handlePlayAudio(out, body);
            return true;
        }
        if (cleanPath.equals("/api/vehicle/stop-audio") && method.equals("POST")) {
            com.overdrive.app.byd.AudioPlaybackController.stop();
            JSONObject r = new JSONObject();
            r.put("success", true);
            HttpResponse.sendJson(out, r.toString());
            return true;
        }

        // POST /api/vehicle/speak — speak text aloud via TextToSpeech (app-process,
        // same bridge as play-audio; the daemon can't run TTS). Body { text, channel? }.
        if (cleanPath.equals("/api/vehicle/speak") && method.equals("POST")) {
            handleSpeak(out, body);
            return true;
        }

        // POST /api/vehicle/message — show an on-screen toast or dialog (app-process
        // overlay; the daemon has no UI surface). Body { kind:toast|dialog, message,
        // title?, button?, duration?, position?, severity?, timeoutSec? }.
        if (cleanPath.equals("/api/vehicle/message") && method.equals("POST")) {
            handleMessage(out, body);
            return true;
        }

        // POST /api/vehicle/system — UI navigation + screenshot + move-app-to-display,
        // all via daemon shell as UID 2000 (input keyevent / screencap / am start). On
        // this API-29 device the a11y takeScreenshot()/GLOBAL_ACTION_TAKE_SCREENSHOT are
        // unavailable (API 30+), so screencap is the reachable path.
        if (cleanPath.equals("/api/vehicle/system") && method.equals("POST")) {
            handleSystem(out, body);
            return true;
        }

        // POST /api/vehicle/find-car
        if (cleanPath.equals("/api/vehicle/find-car") && method.equals("POST")) {
            handleFindCar(out);
            return true;
        }

        // POST /api/vehicle/battery-heat
        if (cleanPath.equals("/api/vehicle/battery-heat") && method.equals("POST")) {
            handleBatteryHeat(out, body);
            return true;
        }

        // GET /api/vehicle/charging-schedule
        if (cleanPath.equals("/api/vehicle/charging-schedule") && method.equals("GET")) {
            handleGetChargingSchedule(out);
            return true;
        }

        // POST /api/vehicle/charging-schedule
        if (cleanPath.equals("/api/vehicle/charging-schedule") && method.equals("POST")) {
            handleChargingSchedule(out, body);
            return true;
        }

        // GET /api/vehicle/charge-cap
        if (cleanPath.equals("/api/vehicle/charge-cap") && method.equals("GET")) {
            handleGetChargeCap(out);
            return true;
        }

        // POST /api/vehicle/charge-cap
        if (cleanPath.equals("/api/vehicle/charge-cap") && method.equals("POST")) {
            handleChargeCap(out, body);
            return true;
        }

        // ── Projection screen (driver-cluster cast + live mirror + touch relay) ─────
        // GET /api/vehicle/cluster-apps — launchable apps for the cast picker
        if (cleanPath.equals("/api/vehicle/cluster-apps") && method.equals("GET")) {
            handleClusterApps(out);
            return true;
        }
        // POST /api/vehicle/cluster-cast — cast a package onto the cluster
        if (cleanPath.equals("/api/vehicle/cluster-cast") && method.equals("POST")) {
            handleClusterCast(out, body);
            return true;
        }
        // POST /api/vehicle/cluster-stop — stop the cast + mirror, restore gauges
        if (cleanPath.equals("/api/vehicle/cluster-stop") && method.equals("POST")) {
            handleClusterStop(out);
            return true;
        }
        // POST /api/vehicle/cluster-mirror — { action: start|stop|rect, x,y,w,h }
        if (cleanPath.equals("/api/vehicle/cluster-mirror") && method.equals("POST")) {
            handleClusterMirror(out, body);
            return true;
        }
        // GET /api/vehicle/cluster-mirror-status — current mirror mode + fission info
        if (cleanPath.equals("/api/vehicle/cluster-mirror-status") && method.equals("GET")) {
            handleClusterMirrorStatus(out);
            return true;
        }
        // POST /api/vehicle/cluster-touch — { type: tap|swipe, x,y[,x2,y2,ms] } normalized 0..1
        if (cleanPath.equals("/api/vehicle/cluster-touch") && method.equals("POST")) {
            handleClusterTouch(out, body);
            return true;
        }

        return false;
    }

    // ── Projection screen handlers ──────────────────────────────────────────────────

    /** List launchable apps for the cast picker (reuses the shared AppLauncher enum). */
    private static void handleClusterApps(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONArray apps = com.overdrive.app.launcher.AppLauncher.listLaunchableApps();
            response.put("success", true);
            response.put("apps", apps);
        } catch (Exception e) {
            logger.warn("cluster-apps failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /** Cast a package onto the driver cluster (acquires projection, resolves the live
     *  fission id, launches fullscreen, holds it open). Reuses the proven ClusterCast. */
    private static void handleClusterCast(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String pkg = req.optString("package", "");
            // Distinguish "app no longer installed" (so the UI can say so + refresh the
            // picker) from a generic failure, before attempting the cast.
            boolean installed = com.overdrive.app.launcher.AppLauncher.isLaunchable(pkg);
            boolean ok = installed && com.overdrive.app.launcher.ClusterCast.start(pkg);
            response.put("success", ok);
            if (!ok) {
                response.put("reason", installed ? "cast_failed" : "not_installed");
                response.put("error", installed
                        ? "could not cast (unresolved component or projection failed)"
                        : "app is not installed");
            }
        } catch (Exception e) {
            logger.warn("cluster-cast failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /** Stop casting + tear down the mirror; the controller restores the gauges when no
     *  other consumer (map / blind-spot) still wants the projection. */
    private static void handleClusterStop(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            com.overdrive.app.surveillance.ClusterMirrorController.getInstance().stop();
            com.overdrive.app.launcher.ClusterCast.stop();
            response.put("success", true);
        } catch (Exception e) {
            logger.warn("cluster-stop failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /** Start / move / stop the live head-unit mirror of the cluster. Body:
     *  { "action": "start"|"rect"|"stop", "x":px,"y":px,"w":px,"h":px } (head-unit px). */
    private static void handleClusterMirror(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String action = req.optString("action", "start");
            com.overdrive.app.surveillance.ClusterMirrorController ctl =
                    com.overdrive.app.surveillance.ClusterMirrorController.getInstance();
            // Optional scaling mode (fit=0 default / fill=1 / zoom=2), sent on start/rect and
            // also settable on its own via action=mode. Absent → FIT (unchanged behaviour).
            boolean hasMode = req.has("mode");
            int scaleMode = req.optInt("mode",
                    com.overdrive.app.surveillance.ClusterMirrorController.SCALE_FIT);
            if ("stop".equals(action)) {
                ctl.stop();
            } else if ("diag".equals(action)) {
                // Device debugging only: resize + capture the mirror layer's actual
                // SurfaceFlinger geometry to a file. No behaviour change on any normal path.
                String path = ctl.captureResizeDiag(req.optInt("w", 0), req.optInt("h", 0));
                response.put("success", true);
                response.put("action", action);
                response.put("diag", path);
                HttpResponse.sendJson(out, response.toString());
                return;
            } else if ("mode".equals(action)) {
                ctl.setScaleMode(scaleMode);
            } else if ("rect".equals(action)) {
                if (hasMode) ctl.setScaleMode(scaleMode);
                ctl.setRect(req.optInt("x", 0), req.optInt("y", 0),
                        req.optInt("w", 0), req.optInt("h", 0));
            } else { // start
                ctl.start(req.optInt("x", 0), req.optInt("y", 0),
                        req.optInt("w", 0), req.optInt("h", 0), scaleMode);
            }
            // Mode is resolved asynchronously on the controller's exec thread; the client
            // polls cluster-mirror-status for the settled mode. Accept here.
            response.put("success", true);
            response.put("action", action);
        } catch (Exception e) {
            logger.warn("cluster-mirror failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /** Current mirror mode (stopped/direct/still/no-projection/unsupported) + fission info. */
    private static void handleClusterMirrorStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            com.overdrive.app.surveillance.ClusterMirrorController ctl =
                    com.overdrive.app.surveillance.ClusterMirrorController.getInstance();
            response.put("success", true);
            response.put("mode", ctl.currentMode());
            response.put("scaleMode", ctl.currentScaleMode());
            response.put("fissionDisplayId", ctl.currentFissionDisplayId());
            response.put("panelW", ctl.panelWidth());
            response.put("panelH", ctl.panelHeight());
            response.put("casting", com.overdrive.app.launcher.ClusterCast.isActive());
        } catch (Exception e) {
            logger.warn("cluster-mirror-status failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /** Relay a tap/swipe (normalized 0..1 of the mirror pane) into the projected app. */
    private static void handleClusterTouch(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String type = req.optString("type", "tap");
            boolean ok;
            if ("swipe".equals(type)) {
                ok = com.overdrive.app.surveillance.ClusterInputRelay.swipe(
                        req.optDouble("x", 0), req.optDouble("y", 0),
                        req.optDouble("x2", 0), req.optDouble("y2", 0),
                        req.optInt("ms", 0));
            } else {
                ok = com.overdrive.app.surveillance.ClusterInputRelay.tap(
                        req.optDouble("x", 0), req.optDouble("y", 0));
            }
            response.put("success", ok);
            if (!ok) response.put("error", "no safe cluster display target");
        } catch (Exception e) {
            logger.warn("cluster-touch failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
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
        // BYD bodywork SDK area numbering swaps L↔R on the FRONT axis vs the
        // physical doors: array index 0 (SDK "LEFT_FRONT") is physically
        // right-front, index 1 is left-front. The REAR axis on this car
        // matches the SDK declaration as-is — see DoorEventNotifier for the
        // open/close-event side of this mapping. The rear pair below is a
        // pre-existing assumption from this code path and has not yet been
        // field-verified for lock state; if a single-door bench test on a
        // real car shows rear lock state arriving with the same asymmetric
        // pattern, swap [2]↔[3] back to SDK order ([2]=lr, [3]=rr).
        // Lock state — three layers, in priority order:
        //
        //   1. OTA fast-path: BYDAutoOtaDevice.getLFDoorLockState(). LF only,
        //      verified live ACC=OFF on DiLink 3.0 with ~1.5s latency.
        //      Overlays SDK and cloud for the LF cell.
        //   2. Cloud snapshot: full 4-door state via MQTT (fills RF/LR/RR).
        //      Lags 1-2s vs OTA on this trim, so for LF the OTA value wins
        //      when both are available.
        //   3. Local SDK device array (data.doorLockStatus[]): typically all
        //      INVALID(0) ACC=OFF on this trim. Kept as the base layer in
        //      case some firmware exposes any door state.
        //
        // The web UI consumes 1=locked, 2=unlocked, -1=unknown. Cloud's raw
        // semantics (1=unlocked, 2=locked) are inverted via cloudLockToApi.
        // The OTA layer reports SDK semantics (1=UNLOCK, 2=LOCK) which we
        // also invert on output.
        JSONObject doors = new JSONObject();
        int sdkOverall = -1;
        if (data.doorLockStatus != null && data.doorLockStatus.length >= 7) {
            doors.put("rf", data.doorLockStatus[0]);
            doors.put("lf", data.doorLockStatus[1]);
            doors.put("rr", data.doorLockStatus[2]);
            doors.put("lr", data.doorLockStatus[3]);
            doors.put("trunk", data.doorLockStatus[4]);
            doors.put("hood", data.doorLockStatus[5]);
            doors.put("overall", data.doorLockStatus[6]);
            if (data.doorLockStatus[6] == 1 || data.doorLockStatus[6] == 2) {
                sdkOverall = data.doorLockStatus[6];
                doors.put("source", "sdk");
                doors.put("scope", "vehicle");
            }
        }

        // Track which source authoritatively set LF so we can derive `overall`
        // correctly when cloud is missing. -1 = no authoritative LF yet.
        int otaLf = -1;
        int cloudOverall = -1;
        boolean cloudAvailable = false;

        // Layer 1: OTA LF fast-path. Reads via the same readLfLockStateFromOta
        // contract used by the lock-gate poller — see CameraDaemon.readDoorLockStatus.
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx != null) {
                Object otaDevice = com.overdrive.app.byd.BydDeviceHelper.getDevice(
                    "android.hardware.bydauto.ota.BYDAutoOtaDevice", ctx);
                if (otaDevice != null) {
                    Object v = com.overdrive.app.byd.BydDeviceHelper.callGetter(
                        otaDevice, "getLFDoorLockState");
                    if (v instanceof Number) {
                        int sdkState = ((Number) v).intValue();
                        // 1=UNLOCK, 2=LOCK in SDK convention → API: 2=unlocked, 1=locked.
                        if (sdkState == 2) otaLf = 1;       // LOCKED → API=1
                        else if (sdkState == 1) otaLf = 2;  // UNLOCKED → API=2
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("ota-lock overlay failed: " + t.getMessage());
        }

        // Layer 2: cloud overlay (full 4-door).
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
                cloudAvailable = true;
                int lf = cloudLockToApi(cs.leftFrontDoorLock);
                int rf = cloudLockToApi(cs.rightFrontDoorLock);
                int lr = cloudLockToApi(cs.leftRearDoorLock);
                int rr = cloudLockToApi(cs.rightRearDoorLock);
                if (lf != -1) doors.put("lf", lf);
                if (rf != -1) doors.put("rf", rf);
                if (lr != -1) doors.put("lr", lr);
                if (rr != -1) doors.put("rr", rr);
                if (cs.isAnyUnlocked()) cloudOverall = 2;
                else if (cs.isAllLocked()) cloudOverall = 1;
                if (cloudOverall != -1) {
                    doors.put("overall", cloudOverall);
                    doors.put("scope", "vehicle");
                }
                doors.put("source", "cloud");
            }
        } catch (Exception e) {
            logger.debug("cloud-lock overlay failed: " + e.getMessage());
        }

        // Layer 3 (top of stack): OTA LF wins for the LF cell. Computed
        // last so it overrides both cloud and SDK.
        if (otaLf != -1) {
            doors.put("lf", otaLf);
            // If cloud was unavailable, an OTA reading describes the driver
            // door only. Keep publishing it as `overall` for compatibility
            // with the surveillance lock gate, but label the scope honestly
            // so presentation layers never call it whole-vehicle state.
            if (!cloudAvailable) {
                if (sdkOverall == -1) {
                    doors.put("overall", otaLf);
                    doors.put("scope", "driver_door");
                    doors.put("source", "ota");
                } else {
                    doors.put("scope", "vehicle");
                    doors.put("source", "sdk+ota");
                }
            }
            if (cloudAvailable) {
                // Cloud sees all four doors, so its overall state remains the
                // whole-vehicle answer even when the faster OTA LF cell differs.
                doors.put("scope", "vehicle");
                doors.put("source", "ota+cloud");
            }
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
        lights.put("ambientColour", data.ambientColour);
        lights.put("ambientOptions", new JSONArray(LightConstants.AMBIENT_COLOURS));
        response.put("lights", lights);

        // ADAS
        JSONObject adas = new JSONObject();
        adas.put("speedLimitWarning", data.speedLimitWarning);
        response.put("adas", adas);

        // Setting
        JSONObject setting = new JSONObject();
        // SDK value: 1=on, 2=off, 3=delay. Treat on(1) and delay(3) as enabled; anything else —
        // off(2) or the unpopulated default 0 on vehicles that don't report CPD — reads as off, so
        // the UI toggle doesn't show "on" for an unknown state.
        setting.put("childPresenceDetection",
                data.childPresenceDetection == 1 || data.childPresenceDetection == 3);
        response.put("setting", setting);

        // Seats — heating/cooling levels for driver/passenger ([0-2], 0=off)
        JSONObject seats = new JSONObject();
        if (data.seatHeat != null && data.seatHeat.length > 0) {
            JSONArray heat = new JSONArray();
            for (int v : data.seatHeat) heat.put(v);
            seats.put("heat", heat);
        }
        if (data.seatCool != null && data.seatCool.length > 0) {
            JSONArray cool = new JSONArray();
            for (int v : data.seatCool) cool.put(v);
            seats.put("cool", cool);
        }
        // ventilatedSeats: hardware capability. Cars without ventilated seats
        // (Atto 3 base, certain Seal trims) report hasFeature("SEAT_VENTILATING")=0
        // and the BYD cloud returns 1001 on VENTILATIONHEATING. JS uses this
        // to grey out the cool buttons.
        seats.put("ventilatedSupported", BydDataCollector.getInstance().isSeatVentilationSupported());
        response.put("seats", seats);

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
                    // PSI = kPa * 0.1450377 (matches the the OEM firmware
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
        // readings, raw 28/254 oil dipstick that the OEM firmware itself
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
     * Lock the car via the routing layer (cloud-first → SDK fallback).
     */
    private static void handleLock(OutputStream out) throws Exception {
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.LockCommand());
        logger.info("Lock: routed result=" + r.outcome + " path=" + r.path);
        HttpResponse.sendJson(out, routedResponse(r, "lock").toString());
    }

    /**
     * Unlock the car via the routing layer.
     */
    private static void handleUnlock(OutputStream out) throws Exception {
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.UnlockCommand());
        logger.info("Unlock: routed result=" + r.outcome + " path=" + r.path);
        HttpResponse.sendJson(out, routedResponse(r, "unlock").toString());
    }

    /**
     * Find car (horn + lights) — cloud-only on this BYD generation.
     */
    private static void handleFindCar(OutputStream out) throws Exception {
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.FindCarCommand());
        logger.info("FindCar: routed result=" + r.outcome + " path=" + r.path);
        HttpResponse.sendJson(out, routedResponse(r, "find-car").toString());
    }

    /**
     * Battery preconditioning heat — cloud-only.
     * Body: { "enabled": bool }
     */
    private static void handleBatteryHeat(OutputStream out, String body) throws Exception {
        boolean enabled = false;
        if (body != null && !body.isEmpty()) {
            try { enabled = new JSONObject(body).optBoolean("enabled", false); } catch (Exception ignored) {}
        }
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.BatteryHeatCommand(enabled));
        logger.info("BatteryHeat: routed result=" + r.outcome + " enabled=" + enabled);
        JSONObject resp = routedResponse(r, "battery-heat");
        try { resp.put("enabled", enabled); } catch (Exception ignored) {}
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * Trunk control routed via the command router.
     * Open: cloud unlock → SDK tailgate (router enforces the safety: motor only fires on unlock SUCCESS).
     * Close / stop: SDK direct.
     * Body: { "action": "open" | "close" | "stop" }
     */
    private static void handleTrunk(OutputStream out, String body) throws Exception {
        String action = "open";
        if (body != null && !body.isEmpty()) {
            try { action = new JSONObject(body).optString("action", "open"); }
            catch (Exception ignored) {}
        }
        VehicleCommand cmd;
        if ("close".equals(action)) cmd = new VehicleCommandRouter.TrunkCloseCommand();
        else if ("stop".equals(action)) cmd = new VehicleCommandRouter.TrunkStopCommand();
        else cmd = new VehicleCommandRouter.TrunkOpenCommand();

        CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
        logger.info("Trunk: action=" + action + " routed result=" + r.outcome + " path=" + r.path);
        JSONObject resp = routedResponse(r, action);
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * Window control routed through the command router.
     * Body: one of:
     *   { "area": 1-4 (LF/RF/LR/RR) or 0 for all, "command": 1=open, 2=close, 3=stop }
     *   { "area": 1-4,                              "targetPercent": 0..100 }
     *   { "area": 5-6, (Sunroof and Sunshade),      "targetPercent": 0..100 }
     *
     * area=0 + command=2 routes through CloseAllWindowsCommand (CLOUD_FIRST,
     * with cloud CLOSEWINDOW). All other paths are SDK_ONLY.
     */
    private static void handleWindow(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            int area = req.optInt("area", 0);

            // targetPercent → SDK closed-loop positioning
            if (req.has("targetPercent")) {
                if (area < 1 || area > 6) {
                    response.put("success", false);
                    response.put("error", Messages.get("errors.vehicle_window_target_requires_area"));
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                int target = req.getInt("targetPercent");
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.WindowMoveCommand(area, 0, target));
                logger.info("Window: area=" + areaName(area) + " target=" + target + "% " + r.outcome);
                JSONObject resp = routedResponse(r, "window-target");
                resp.put("area", area);
                resp.put("targetPercent", target);
                HttpResponse.sendJson(out, resp.toString());
                return;
            }

            int command = req.optInt("command", 2); // default close
            VehicleCommand cmd;
            // "Close all" gets the cloud CLOSEWINDOW path (works while car is asleep).
            if (area == 0 && command == 2) {
                cmd = new VehicleCommandRouter.CloseAllWindowsCommand();
            } else {
                cmd = new VehicleCommandRouter.WindowMoveCommand(area, command, null);
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Window: area=" + areaName(area) + " cmd=" + windowCmdName(command) + " " + r.outcome);
            JSONObject resp = routedResponse(r, "window");
            resp.put("area", area);
            resp.put("command", command);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Window command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Flash lights routed via the router.
     */
    private static void handleFlash(OutputStream out) throws Exception {
        CommandResult r = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.FlashLightsCommand());
        logger.info("Flash: routed result=" + r.outcome + " path=" + r.path);
        HttpResponse.sendJson(out, routedResponse(r, "flash").toString());
    }

    /**
     * Climate control routed through the command router.
     * power_on / power_off → CLOUD_FIRST (OPENAIR / CLOSEAIR with SDK fallback).
     * set_temp / set_fan   → SDK_ONLY (no granular cloud command exposed).
     * Body: { "action": "power_on"|"power_off"|"set_temp"|"set_fan",
     *         "zone": 1|2, "temp": 17-33, "fan": 1-7 }
     */
    private static void handleClimate(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String action = req.optString("action", "");
            VehicleCommand cmd;
            switch (action) {
                case "power_on": {
                    double t = req.optDouble("temp", 22);
                    cmd = new VehicleCommandRouter.ClimateOnCommand(t);
                    break;
                }
                case "power_off":
                    cmd = new VehicleCommandRouter.ClimateOffCommand();
                    break;
                case "set_temp": {
                    int zone = req.optInt("zone", 1);
                    double t = req.optDouble("temp", 22);
                    cmd = new VehicleCommandRouter.ClimateSetTempCommand(zone, t);
                    break;
                }
                case "set_fan": {
                    int fan = req.optInt("fan", 3);
                    cmd = new VehicleCommandRouter.ClimateSetFanCommand(fan);
                    break;
                }
                case "auto_on":  cmd = new VehicleCommandRouter.AcAutoModeCommand(true);  break;
                case "auto_off": cmd = new VehicleCommandRouter.AcAutoModeCommand(false); break;
                case "fan_only_on":  cmd = new VehicleCommandRouter.FanOnlyModeCommand(true);  break;
                case "fan_only_off": cmd = new VehicleCommandRouter.FanOnlyModeCommand(false); break;
                case "steering_heat_on":  cmd = new VehicleCommandRouter.SteeringWheelHeatCommand(true);  break;
                case "steering_heat_off": cmd = new VehicleCommandRouter.SteeringWheelHeatCommand(false); break;
                case "recirculate_on":  cmd = new VehicleCommandRouter.AcRecirculationCommand(true);  break;   // recirculation
                case "recirculate_off": cmd = new VehicleCommandRouter.AcRecirculationCommand(false); break;   // fresh air
                case "defrost_front_on":  cmd = new VehicleCommandRouter.FrontDefrostCommand(true);  break;
                case "defrost_front_off": cmd = new VehicleCommandRouter.FrontDefrostCommand(false); break;
                case "defrost_rear_on":  cmd = new VehicleCommandRouter.RearDefrostCommand(true);  break;
                case "defrost_rear_off": cmd = new VehicleCommandRouter.RearDefrostCommand(false); break;
                default:
                    logger.warn("Climate: unknown action '" + action + "'");
                    response.put("success", false);
                    response.put("error", Messages.get("errors.vehicle_unknown_action_with_action", action));
                    HttpResponse.sendJson(out, response.toString());
                    return;
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Climate: action=" + action + " " + r.outcome + " path=" + r.path);
            JSONObject resp = routedResponse(r, action);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Climate command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Seat heating / ventilation / memory-recall — cloud-first (VENTILATIONHEATING)
     * with SDK fallback. The cloud command is stateful, so heat+vent commands need
     * the FULL state of driver+passenger seats. The JS keeps that state and sends
     * it on every seat command.
     *
     * Body: { "action": "heating"|"ventilation"|"position"|"save",
     *         "position": 1-4, "level": 0-3,
     *         "driverHeat": 0-2, "driverVent": 0-2,
     *         "passengerHeat": 0-2, "passengerVent": 0-2 }
     *
     * <p>"position" recalls a stored driver-seat memory slot (1-2); "save" stores
     * the seat's current physical position into that slot. Both are driver-only,
     * SDK-only (no BYD cloud equivalent for seat memory).
     */
    private static void handleSeat(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String action = req.optString("action", "heating");
            int position = req.optInt("position", 1);
            int level = req.optInt("level", 0);
            int dh = req.optInt("driverHeat", 0);
            int dv = req.optInt("driverVent", 0);
            int ph = req.optInt("passengerHeat", 0);
            int pv = req.optInt("passengerVent", 0);
            VehicleCommand cmd;
            if ("ventilation".equals(action)) {
                cmd = new VehicleCommandRouter.SeatVentCommand(position, level, dh, dv, ph, pv);
            } else if ("position".equals(action)) {
                cmd = new VehicleCommandRouter.SeatMemoryCommand(position, false);
            } else if ("save".equals(action)) {
                cmd = new VehicleCommandRouter.SeatMemoryCommand(position, true);
            } else {
                cmd = new VehicleCommandRouter.SeatHeatCommand(position, level, dh, dv, ph, pv);
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Seat: action=" + action + " pos=" + seatPosName(position)
                    + " level=" + level + " " + r.outcome);
            JSONObject resp = routedResponse(r, action);
            resp.put("position", position);
            resp.put("level", level);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Seat command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Light controls — SDK_ONLY routed.
     * Body: { "target": "dayTimeLight", "enable": true|false }
     * Body: { "target": "ambientColour", "value": 1-31 }
     */
    private static void handleLights(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String target = req.optString("target", null);
            VehicleCommand cmd;
            if ("dayTimeLight".equals(target)) {
                boolean enable = req.optBoolean("enable", true);
                cmd = new VehicleCommandRouter.LightsCommand(enable);
            } else if ("ambientColour".equals(target)) {
                int value = req.optInt("value", 1);
                cmd = new VehicleCommandRouter.AmbientColourCommand(value);
            } else if ("welcomeLight".equals(target)) {
                cmd = new VehicleCommandRouter.WelcomeLightCommand(req.optBoolean("enable", true));
            } else if ("readingLight".equals(target)) {
                cmd = new VehicleCommandRouter.ReadingLightCommand(req.optBoolean("enable", true));
            } else if ("ambientMusic".equals(target)) {
                cmd = new VehicleCommandRouter.AmbientMusicModeCommand(req.optBoolean("enable", true));
            } else if ("headlightLevel".equals(target)) {
                cmd = new VehicleCommandRouter.HeadlightLevelCommand(req.optInt("value", 1));
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unsupported_target_with_target", target));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Lights: target=" + target + " " + r.outcome);
            JSONObject resp = routedResponse(r, "lights");
            resp.put("target", target);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Light command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * ADAS controls — SDK_ONLY routed.
     * Body: { "target": "speedLimitWarning"|"esp", "enable": true|false }
     * ESP (Electronic Stability Program) is a SAFETY control; enable=false disables
     * stability control. Many vehicles re-enable it at the next ignition cycle.
     */
    private static void handleAdas(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String target = req.optString("target", null);
            boolean enable = req.optBoolean("enable", true);
            VehicleCommand cmd;
            if ("speedLimitWarning".equals(target)) {
                cmd = new VehicleCommandRouter.AdasSpeedLimitWarningCommand(enable);
            } else if ("esp".equals(target)) {
                cmd = new VehicleCommandRouter.AdasEspCommand(enable);
            } else if ("laneAssist".equals(target)) {
                // Multi-mode (not on/off): 0=Off, 1=LDW, 2=LDP, 3=LDW+LDP. Accept an
                // explicit "mode" int; fall back to mapping the on/off "enable" to
                // Off(0) / LDW+LDP(3) so a plain toggle still does something sensible.
                int mode = req.has("mode") ? req.optInt("mode", 0) : (enable ? 3 : 0);
                cmd = new VehicleCommandRouter.AdasLaneAssistCommand(mode);
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unsupported_target_with_target", target));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Adas: target=" + target + " enable=" + enable + " " + r.outcome);
            JSONObject resp = routedResponse(r, "adas");
            resp.put("target", target);
            resp.put("enable", enable);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Adas command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Read-only ADAS state — currently the raw ESP/ESC readback, so the (guessed)
     * ESP feature id can be verified on-car before the toggle is trusted. Returns
     * the raw SDK int plus a best-effort parsed on/off (1=on, 0=off; -1=unavailable).
     */
    private static void handleAdasState(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            int espRaw = collector.getEspState();
            int itacRaw = collector.getItacState();
            // success if either readback yielded a usable value
            response.put("success", espRaw >= 0 || itacRaw >= 0);
            JSONObject esp = new JSONObject();
            esp.put("raw", espRaw);
            // ESP uses the OEM SDK's INVERTED convention on adasDevice: raw 0 = ON, 1 = OFF
            // (matches setEspState / readEspOn). Reporting it the old 1=on way would show
            // stability control backwards on the verification endpoint.
            if (espRaw == 0) esp.put("on", true);
            else if (espRaw == 1) esp.put("on", false);
            // any other value (incl. -1) → "on" omitted: unavailable / unknown encoding
            response.put("esp", esp);
            JSONObject itac = new JSONObject();
            itac.put("raw", itacRaw);
            if (itacRaw == 1) itac.put("on", true);
            else if (itacRaw == 0) itac.put("on", false);
            response.put("itac", itac);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            logger.warn("Adas state read failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Setting controls — SDK_ONLY routed.
     * Body: { "target": "childPresenceDetection", "value": 1|2|3 }
     * The value 1 is for on, 2 is for off and 3 is for delay
     */
    private static void handleSetting(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String target = req.optString("target", null);
            int value = req.optInt("value", 1);
            if (!"childPresenceDetection".equals(target)) {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unsupported_target_with_target", target));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            CommandResult r = VehicleCommandRouter.getInstance()
                    .execute(new VehicleCommandRouter.SettingChildPresenceDetectionCommand(value));
            logger.info("Adas: target=childPresenceDetection value=" + value + " " + r.outcome);
            JSONObject resp = routedResponse(r, "setting");
            resp.put("target", target);
            resp.put("value", value);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("Setting command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Media controls — Android-level, not cloud/CAN.
     * Body: { "target": "volume", "value": 0-100, "channel": "media" }
     *          → volume on the chosen audio channel as a percentage
     *       { "target": "brightness", "value": 0-100 } → infotainment screen brightness
     *       { "target": "cluster_brightness", "value": 0-100 } → driver-cluster brightness
     *       { "target": "hud_brightness", "value": 0-100 } → head-up-display brightness
     *
     * Volume is applied via AudioManager on the daemon's app context, mapping the
     * 0-100 percentage onto the chosen stream's real max index so it is
     * device-independent. The optional "channel" selects the Android stream
     * (media/navigation/voice/phone/system/alarm/ring); default "media" (STREAM_MUSIC)
     * preserves the original single-channel behaviour. Brightness targets reuse the
     * proven dedicated BydAutoSettingDevice setters (setInfotainmentBrightness /
     * setDriverDisplayBrightness / setHUDBrightness), all 0-100.
     */
    private static void handleMedia(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String target = req.optString("target", null);
            int value = req.optInt("value", -1);
            // Volume is an ABSOLUTE step index (0..stream max, ~40 for media on this head
            // unit — matching the car's own volume button), NOT a 0-100 percentage; its
            // upper bound is the real stream max, enforced by clamping in
            // setChannelVolumeIndex. Brightness stays a 0-100 percentage. So only
            // brightness targets are range-checked to 0-100 here; volume just needs >= 0.
            // Media transport keys (play/pause/next/prev) carry no numeric value, and
            // relative volume steps allow a signed value — so exclude both from the
            // ">= 0" / "0-100" numeric guards below.
            boolean isMediaKey = "media_key".equals(target);
            boolean isVolumeStep = "volume_step".equals(target);
            boolean isVolume = "volume".equals(target);
            if (!isMediaKey && !isVolumeStep && (value < 0 || (!isVolume && value > 100))) {
                response.put("success", false);
                response.put("error", isVolume ? "value must be >= 0" : "value must be 0-100");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            // Every screen/brightness target (infotainment, cluster, HUD, screen
            // power) removes or dims safety-relevant display information — gate
            // them all the same way. Volume is excluded: a separate, lower-severity
            // issue (no ceiling on remote volume changes), not a driving-state gate.
            boolean screenTarget = !isVolume;
            if (screenTarget && DrivingSafetyGuard.isMovementBlocked()) {
                response.put("success", false);
                response.put("outcome", "blocked_driving");
                response.put("error", Messages.get("errors.vehicle_blocked_in_motion"));
                HttpResponse.sendJson(out, 409, response.toString());
                return;
            }
            boolean ok;
            if (isMediaKey) {
                // Transport control via AudioManager.dispatchMediaKeyEvent — a Binder
                // call the daemon CAN make (unlike a MediaPlayer track). key = play_pause
                // / play / pause / next / previous.
                ok = dispatchMediaKey(req.optString("key", ""));
            } else if (isVolumeStep) {
                // Relative volume: read-modify-write ±1 step on the chosen channel.
                String channel = req.optString("channel", "media");
                int dir = req.optInt("value", 0); // +1 up, -1 down
                ok = stepChannelVolume(channel, dir);
            } else if (isVolume) {
                // Optional channel; default "media" keeps the pre-existing behaviour.
                String channel = req.optString("channel", "media");
                ok = setChannelVolumeIndex(channel, value);
            } else if ("ambient_brightness".equals(target)) {
                ok = BydDataCollector.getInstance().setAmbientBrightness(value);
            } else if ("brightness".equals(target)) {
                ok = BydDataCollector.getInstance().setInfotainmentBrightness(value);
            } else if ("cluster_brightness".equals(target)) {
                ok = BydDataCollector.getInstance().setDriverDisplayBrightness(value);
            } else if ("hud_brightness".equals(target)) {
                ok = BydDataCollector.getInstance().setHudBrightness(value);
            } else if ("hud_power".equals(target)) {
                // This platform has no dedicated HUD on/off switch (confirmed against
                // the OEM firmware — only setHUDBrightness exists). So "off" = brightness
                // 0 (dims the HUD out), "on" = full brightness. The action sends value=0
                // for off and value=100 for on; anything >0 is treated as on-at-that-level.
                ok = BydDataCollector.getInstance().setHudBrightness(value);
            } else if ("screen_power".equals(target)) {
                // Turn the infotainment (centre) screen fully on/off via the proven
                // backlight path (PowerManager.turnBacklightOn/Off → BYDAutoSettingDevice
                // → shell WAKEUP/SLEEP keyevent). NOT goToSleep — the car's ACC-on
                // keep-awake logic fights a real sleep. value=0 → off, anything >0 → on.
                ok = BydDataCollector.getInstance().setScreenPower(value > 0);
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unsupported_target_with_target", target));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            logger.info("Media: target=" + target + " value=" + value + " ok=" + ok);
            response.put("success", ok);
            response.put("target", target);
            response.put("value", value);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            logger.warn("Media command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Speak text aloud via TextToSpeech. Body: { "text": "...", "channel": "voice" }.
     * Dispatches to the app-process MediaPlaybackService (TTS needs a real Context +
     * Looper the headless daemon lacks). Returns as soon as the request is queued.
     */
    private static void handleSpeak(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String text = req.optString("text", null);
            String channel = req.optString("channel", "voice");
            if (text == null || text.trim().isEmpty()) {
                response.put("success", false);
                response.put("error", "text is required");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            boolean ok = com.overdrive.app.byd.AudioPlaybackController.speak(text, channel);
            response.put("success", ok);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            logger.warn("speak failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Show an on-screen toast or dialog via the app-process {@code MessageOverlayService}
     * (the daemon has no UI surface, same reason speak/play-video are bridged out). Body:
     * { "kind":"toast"|"dialog", "message":"…", ["title":"…"], ["button":"OK"],
     *   ["duration":"short"|"long"], ["position":"top"|"center"|"bottom"],
     *   ["severity":"info"|"warning"|"alert"], ["timeoutSec":N] }.
     * Fire-and-forget dispatch; returns as soon as the launch is queued.
     */
    private static void handleMessage(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String kind = req.optString("kind", "toast");
            String message = req.optString("message", "");
            String severity = req.optString("severity", "info");
            boolean ok;
            if ("dialog".equalsIgnoreCase(kind)) {
                ok = com.overdrive.app.byd.MessageOverlayController.showDialog(
                        req.optString("title", ""),
                        message,
                        req.optString("button", "OK"),
                        severity,
                        req.optInt("timeoutSec", 0));
            } else {
                ok = com.overdrive.app.byd.MessageOverlayController.showToast(
                        message,
                        req.optString("duration", "short"),
                        req.optString("position", "bottom"),
                        severity);
            }
            response.put("success", ok);
            if (!ok) response.put("error", "message is required");
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            logger.warn("message failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /** Where screenshots are written (world-readable, same tree as other daemon files). */
    private static final String SCREENSHOT_DIR = "/data/local/tmp/.overdrive/screenshots";

    /**
     * UI navigation + screenshot + move-to-display, run as the UID-2000 daemon via
     * shell. Body: { "target": "home|back|recents|screenshot|move_display",
     *   ["display": 0|1], ["package": "com.x/.Act"] }.
     * All are fire-and-forget shell execs (never block the request thread on a hung
     * child). Nav uses `input keyevent`; screenshot uses `screencap` (the a11y route is
     * API 30+, unavailable on this API-29 head unit); move uses `am start-activity`.
     */
    private static void handleSystem(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String target = req.optString("target", "");
            String cmd;
            switch (target) {
                case "home":     cmd = "input keyevent 3"; break;   // KEYCODE_HOME
                case "back":     cmd = "input keyevent 4"; break;   // KEYCODE_BACK
                case "recents":  cmd = "input keyevent 187"; break; // KEYCODE_APP_SWITCH
                case "screenshot": {
                    // screencap to a timestamped file under our world-readable tree.
                    // Uptime-based name (no wall clock needed) keeps successive shots unique.
                    String dir = SCREENSHOT_DIR;
                    String file = dir + "/shot_" + android.os.SystemClock.uptimeMillis() + ".png";
                    int display = req.optInt("display", -1);
                    String disp = display >= 0 ? ("-d " + display + " ") : "";
                    cmd = "mkdir -p " + dir + " && screencap " + disp + "-p " + file
                            + " && chmod 644 " + file;
                    break;
                }
                case "move_display": {
                    // Resolve the launcher component + validate the package inside
                    // AppLauncher / ClusterCast (reuses openApp's trusted resolver), NOT raw shell.
                    String pkg = req.optString("package", "");
                    int display = req.optInt("display", 0);
                    boolean moved;
                    if (display == 1) {
                        // Driver cluster: the fission display doesn't exist until the OEM
                        // projection is opened, and its logical id is assigned live (never
                        // a blind --display 1). Route through ClusterCast, which acquires
                        // the projection, resolves the real fission id, launches fullscreen,
                        // and holds the projection open (gauges restore on stop / ACC-off).
                        moved = com.overdrive.app.launcher.ClusterCast.start(pkg);
                    } else {
                        // Head unit: a normal launch. If an app was cast to the cluster,
                        // moving back to the head unit releases that hold so the gauges
                        // are restored (the cluster is no longer showing the cast app).
                        // stop(true): reparent the cast task to display 0 WHILE the fission
                        // display is still live (before releaseSustained closes it), so the
                        // app isn't orphaned on a torn-down display.
                        com.overdrive.app.launcher.ClusterCast.stop(true);
                        moved = com.overdrive.app.launcher.AppLauncher.launchOnDisplay(pkg, display);
                    }
                    response.put("success", moved);
                    response.put("target", target);
                    if (!moved) response.put("error", "could not move app (bad package or unresolved component)");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                case "cluster_cast_stop": {
                    // Stop casting any app to the driver cluster — releases the projection
                    // hold; the controller restores the gauges when no other consumer
                    // (map / blind-spot) still wants it. Idempotent.
                    com.overdrive.app.launcher.ClusterCast.stop();
                    response.put("success", true);
                    response.put("target", target);
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                default:
                    response.put("success", false);
                    response.put("error", "unknown system target: " + target);
                    HttpResponse.sendJson(out, response.toString());
                    return;
            }
            boolean ok = runDetachedShell(cmd);
            response.put("success", ok);
            response.put("target", target);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            logger.warn("system command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /** Run a shell command as the daemon (UID 2000), bounded so a hang can't park us. */
    private static boolean runDetachedShell(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
            // Bound the wait; screencap on a big panel can take ~1s, nav is instant.
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            logger.warn("runDetachedShell failed: " + e.getMessage());
            return false;
        }
    }

    /** Minimal shell single-quote wrap for a component/package token. */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // Audio library dir (mirror of AudioApiHandler.AUDIO_DIR) — where uploaded
    // sounds picked by the "Play Audio" action live. A "name" payload resolves here.
    private static final String AUDIO_LIBRARY_DIR = "/data/local/tmp/.overdrive/audio";

    /**
     * Play an uploaded sound (by library {@code name}) or an explicit {@code path} on
     * a chosen channel via the daemon MediaPlayer. Body:
     * { "name": "alert.mp3", "channel": "media" }  — library file (the normal path,
     * chosen by the AudioType picker), or
     * { "path": "/storage/emulated/0/Music/x.mp3", "channel": "media" } — explicit
     * path (advanced). Channel defaults to "media". The controller validates the file
     * (exists, readable, under an allowed media root) and plays asynchronously, so
     * this returns as soon as playback is queued.
     */
    private static void handlePlayAudio(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String channel = req.optString("channel", "media");
            boolean loop = req.optBoolean("loop", false);
            // "display": "screen" shows an MP4's picture on the head-unit SurfaceControl
            // lane; anything else (default) is audio-only (speakers). Audio files ignore it.
            boolean onScreen = "screen".equalsIgnoreCase(req.optString("display", "speakers"));
            if (onScreen && DrivingSafetyGuard.isMovementBlocked()) {
                response.put("success", false);
                response.put("outcome", "blocked_driving");
                response.put("error", Messages.get("errors.vehicle_blocked_in_motion"));
                HttpResponse.sendJson(out, 409, response.toString());
                return;
            }
            // Prefer a library "name"; fall back to an explicit "path".
            String name = req.optString("name", null);
            String path = req.optString("path", null);
            String resolved = null;
            if (name != null && !name.trim().isEmpty()) {
                // Resolve the library name to its path. Guard against traversal by
                // taking only the basename before joining to the library dir.
                String base = new java.io.File(name.trim()).getName();
                resolved = new java.io.File(AUDIO_LIBRARY_DIR, base).getAbsolutePath();
            } else if (path != null && !path.trim().isEmpty()) {
                resolved = path.trim();
            }
            if (resolved == null) {
                response.put("success", false);
                response.put("error", "name or path is required");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            boolean ok = onScreen
                    ? com.overdrive.app.byd.AudioPlaybackController.playVideoOnScreen(resolved, channel, loop)
                    : com.overdrive.app.byd.AudioPlaybackController.play(resolved, channel, loop);
            response.put("success", ok);
            response.put("path", resolved);
            response.put("channel", channel);
            if (!ok) response.put("error", "could not play (missing/unreadable file, or playback unavailable)");
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            logger.warn("play-audio failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Map a channel name to its Android {@code AudioManager.STREAM_*} type. Channel→
     * stream mapping per the OEM firmware's per-channel volume setters (media =
     * STREAM_MUSIC(3), navigation ≈ STREAM_NAVI(14), voice(16/17), phone =
     * STREAM_VOICE_CALL(0)). We use only the stable public STREAM constants so
     * behaviour is deterministic across SDK levels; the OEM-extended navi/voice
     * streams are approximated by the closest public stream (navigation→
     * STREAM_MUSIC-adjacent is unreliable, so navigation maps to the public
     * STREAM_NOTIFICATION-independent choice below is avoided — see mapping). Unknown
     * channel → STREAM_MUSIC.
     */
    private static int streamForChannel(String channel) {
        if (channel == null) return android.media.AudioManager.STREAM_MUSIC;
        switch (channel.trim().toLowerCase()) {
            case "phone":
            case "call":        return android.media.AudioManager.STREAM_VOICE_CALL;
            case "system":      return android.media.AudioManager.STREAM_SYSTEM;
            case "alarm":       return android.media.AudioManager.STREAM_ALARM;
            case "ring":        return android.media.AudioManager.STREAM_RING;
            case "navigation":  return 14; // STREAM_NAVI — OEM nav-guidance stream (OEM firmware setBroadcastVolume uses 14)
            case "voice":       return 16; // OEM voice stream (OEM firmware setVoiceVolume uses 16)
                // These OEM-extended stream ints ARE settable via setStreamVolume on this HU
                // family (OEM firmware does exactly this), so the "navigation volume" / "voice
                // volume" controls now move the SAME stream playback uses (MediaPlaybackService
                // .streamForChannel), keeping the slider and the played audio consistent.
            case "media":
            default:            return android.media.AudioManager.STREAM_MUSIC;
        }
    }

    /**
     * Set the given audio channel's volume to an ABSOLUTE step index via AudioManager
     * on the daemon's app context — the same 0..max scale as the car's own volume
     * button (media max is 40 on this head unit). The index is clamped to the stream's
     * real max so a too-high value pins to max rather than failing. Returns false when
     * no context / AudioManager is available.
     */
    private static boolean setChannelVolumeIndex(String channel, int index) {
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx == null) {
                logger.warn("setChannelVolumeIndex: no context available");
                return false;
            }
            android.media.AudioManager am = (android.media.AudioManager)
                    ctx.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am == null) {
                logger.warn("setChannelVolumeIndex: AudioManager unavailable");
                return false;
            }
            int stream = streamForChannel(channel);
            int max = am.getStreamMaxVolume(stream);
            if (max <= 0) return false;
            int clamped = Math.max(0, Math.min(max, index));
            am.setStreamVolume(stream, clamped, 0);
            // OEM parameter write for the MEDIA channel. On some BYD trims a plain
            // setStreamVolume updates the Android stream index WITHOUT moving the
            // amplifier — the head unit's real knob is the "volume_music" AudioManager
            // parameter. The OEM firmware writes setStreamVolume LAST, behind this
            // parameter, which is strong evidence it's the authoritative path. We issue
            // both (belt-and-suspenders): setStreamVolume above for trims where it works,
            // and the volume_music parameter here for trims where it's the real lever.
            // "8" is the OEM's media stream id; the three forms match the firmware's own
            // variants. Best-effort — parameter writes never throw fatally.
            if (stream == android.media.AudioManager.STREAM_MUSIC) {
                setMediaVolumeParameter(am, clamped);
            }
            logger.info("setChannelVolumeIndex: channel=" + channel + " index=" + clamped + "/" + max);
            return true;
        } catch (Exception e) {
            logger.warn("setChannelVolumeIndex failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Write the OEM "volume_music" AudioManager parameter for the media stream — the
     * lever the head-unit firmware itself uses for media volume (mirrors the OEM
     * firmware's setMediaVolumeViaParameters, which tries these three forms). The "8"
     * is the OEM media stream id. Best-effort: setParameters is a fire-and-forget OEM
     * hook that may be a no-op on trims that don't recognise it, so failures are
     * swallowed — the setStreamVolume write already ran as the standard-Android path.
     */
    private static void setMediaVolumeParameter(android.media.AudioManager am, int level) {
        String[] forms = {
                "volume_music=" + level,
                "volume_music=8," + level,
                "volume_music=" + level + ",8",
        };
        for (String form : forms) {
            try { am.setParameters(form); } catch (Throwable ignored) { /* OEM hook may reject */ }
        }
    }

    /**
     * Step the given channel's volume by one index (dir &gt; 0 up, &lt; 0 down) via a
     * read-modify-write on {@link #setChannelVolumeIndex} (so the OEM volume_music
     * parameter write happens for media too). Absolute setStreamVolume rather than
     * adjustStreamVolume so the same authoritative path as the absolute action is used.
     */
    private static boolean stepChannelVolume(String channel, int dir) {
        try {
            android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (ctx == null) return false;
            android.media.AudioManager am = (android.media.AudioManager)
                    ctx.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am == null) return false;
            int stream = streamForChannel(channel);
            int cur = am.getStreamVolume(stream);
            int next = cur + (dir >= 0 ? 1 : -1);
            return setChannelVolumeIndex(channel, next); // clamps to [0,max] itself
        } catch (Exception e) {
            logger.warn("stepChannelVolume failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Dispatch a media transport key by INJECTING it at the input layer via
     * {@code input keyevent <code>} run as the UID-2000 daemon shell — the same privileged
     * injection the keymap replay ({@code KeymapApiHandler}/{@code KeyMapDispatcher}) and
     * {@code handleSystem} (HOME/BACK/APP_SWITCH) already use successfully.
     *
     * <p>The previous {@link android.media.AudioManager#dispatchMediaKeyEvent} path failed
     * for the real sources (Android Auto / Bluetooth / DAB radio): dispatched from the
     * daemon's synthetic, non-foreground app context it is not delivered to the media
     * session that owns audio focus. A system-level {@code input keyevent} injection is
     * routed by the OS to the focused/audio-focus owner regardless of our caller identity.
     *
     * <p>Uses explicit PLAY(126)/PAUSE(127) rather than the PLAY_PAUSE toggle for the
     * play/pause action's underlying media codes only where a fixed intent is known; the
     * "play_pause" action keeps the toggle keycode (85) since it is an explicit toggle
     * request. Codes match the OEM firmware (mediaNext=87, mediaPrevious=88). Returns false on
     * unknown key.
     */
    private static boolean dispatchMediaKey(String key) {
        int code;
        switch (key == null ? "" : key.trim().toLowerCase()) {
            case "play_pause":
            case "toggle":     code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break; // 85
            case "play":       code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY; break;        // 126
            case "pause":      code = android.view.KeyEvent.KEYCODE_MEDIA_PAUSE; break;       // 127
            case "next":       code = android.view.KeyEvent.KEYCODE_MEDIA_NEXT; break;        // 87
            case "previous":
            case "prev":       code = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;    // 88
            default:           return false;
        }
        // input keyevent injects a complete DOWN+UP press at the input layer; no context /
        // AudioManager needed. runDetachedShell bounds the wait so a hung `input` can't park us.
        boolean ok = runDetachedShell("input keyevent " + code);
        logger.info("dispatchMediaKey: " + key + " (code=" + code + ") injected ok=" + ok);
        return ok;
    }

    /**
     * Charging schedule — CLOUD_ONLY. Wraps BYD's saveOrUpdate (window + repeat)
     * and changeChargeStatue (master switch). Payload mirrors pyBYD:
     * <pre>
     *   { startChargeTime: "HH:MM",
     *     endChargeTime:   "HH:MM" | "full",
     *     chargeWay:       "s" | "e" | "0,1,2,3,4",
     *     enabled:         boolean }
     * </pre>
     * If only {@code enabled} is provided, the master toggle runs alone.
     */
    private static void handleChargingSchedule(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            boolean hasStart = req.has("startChargeTime");
            boolean hasEnd = req.has("endChargeTime");
            boolean hasWay = req.has("chargeWay");
            boolean hasEnabled = req.has("enabled");
            boolean scheduleFields = hasStart || hasEnd || hasWay;
            if (!scheduleFields && !hasEnabled) {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unknown_action_with_action", "charging-schedule"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            if (scheduleFields && !(hasStart && hasEnd && hasWay)) {
                response.put("success", false);
                response.put("error", "startChargeTime, endChargeTime, and chargeWay must be provided together");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // Toggle-only request — just hit changeChargeStatue.
            if (!scheduleFields) {
                boolean enabled = req.getBoolean("enabled");
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.SmartChargingToggleCommand(enabled));
                logger.info("ChargingSchedule: toggle enabled=" + enabled + " " + r.outcome);
                JSONObject resp = routedResponse(r, "smart-charging-toggle");
                resp.put("enabled", enabled);
                HttpResponse.sendJson(out, resp.toString());
                return;
            }

            // Full save — saveOrUpdate carries its own status, no pre-toggle needed.
            String start = req.getString("startChargeTime");
            String end = req.getString("endChargeTime");
            String way = req.getString("chargeWay");
            boolean enabled = hasEnabled ? req.getBoolean("enabled") : true;
            CommandResult r = VehicleCommandRouter.getInstance()
                    .execute(new VehicleCommandRouter.ChargeScheduleCommand(start, end, way, enabled));
            logger.info("ChargingSchedule: save start=" + start + " end=" + end
                    + " way=" + way + " enabled=" + enabled + " " + r.outcome);
            JSONObject resp = routedResponse(r, "charge-schedule");
            resp.put("startChargeTime", start);
            resp.put("endChargeTime", end);
            resp.put("chargeWay", way);
            resp.put("enabled", enabled);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("ChargingSchedule command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Charging-schedule state. BYD's smartCharge/homePage endpoint returns
     * telemetry only (no echo of the configured schedule), so our source of
     * truth is {@link SmartChargeCache}, a local mirror updated on every
     * successful saveOrUpdate / changeChargeStatue.
     */
    private static void handleGetChargingSchedule(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            BydCloudConfig cfg = BydCloudConfig.fromUnifiedConfig();
            if (!cfg.isConfigured() || cfg.vin == null || cfg.vin.isEmpty()) {
                resp.put("success", true);
                resp.put("supported", false);
                resp.put("reason", "cloud_not_configured");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            com.overdrive.app.byd.cloud.BydCloudClient client =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().getSharedClient();
            if (client == null) {
                resp.put("success", true);
                resp.put("supported", false);
                resp.put("reason", "cloud_client_unavailable");
                HttpResponse.sendJson(out, resp.toString());
                return;
            }
            Boolean enabled = com.overdrive.app.byd.cloud.SmartChargeCache.getEnabled();
            String start = com.overdrive.app.byd.cloud.SmartChargeCache.getStartChargeTime();
            String end = com.overdrive.app.byd.cloud.SmartChargeCache.getEndChargeTime();
            String way = com.overdrive.app.byd.cloud.SmartChargeCache.getChargeWay();
            resp.put("success", true);
            resp.put("supported", true);
            if (enabled == null) resp.put("enabled", JSONObject.NULL);
            else resp.put("enabled", enabled.booleanValue());
            resp.put("startChargeTime", start == null ? JSONObject.NULL : start);
            resp.put("endChargeTime", end == null ? JSONObject.NULL : end);
            resp.put("chargeWay", way == null ? JSONObject.NULL : way);
            logger.info("ChargingSchedule GET (local cache) → enabled=" + enabled
                    + " start=" + start + " end=" + end + " way=" + way);
        } catch (Exception e) {
            logger.warn("ChargingSchedule read failed: " + e.getMessage());
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * BEV charge cap — SDK_ONLY via BYDAutoChargingDevice
     * setChargeStopCapacityState + setChargeStopSwitchState. The Seal HAL
     * historically reports getChargeStopSupportConfig=0; the collector probes
     * via write-then-read-back on the first successful POST and the GET
     * returns supported=false on no-op trims so the UI can hide the section.
     *
     * <p>Body: {@code { percent?: 50..100, enabled?: bool }}.
     * When both are present the toggle runs first so a freshly-enabled cap
     * picks up the new percent.
     */
    private static void handleChargeCap(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            boolean hasPercent = req.has("percent");
            boolean hasEnabled = req.has("enabled");
            if (!hasPercent && !hasEnabled) {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unknown_action_with_action", "charge-cap"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            CommandResult last = null;
            String action = null;

            if (hasEnabled) {
                boolean enabled = req.getBoolean("enabled");
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.ChargeCapToggleCommand(enabled));
                logger.info("ChargeCap: toggle enabled=" + enabled + " " + r.outcome);
                last = r;
                action = "charge-cap-toggle";
                if (r.outcome != VehicleCommandRouter.Outcome.SUCCESS && hasPercent) {
                    JSONObject resp = routedResponse(r, action);
                    resp.put("enabled", enabled);
                    HttpResponse.sendJson(out, resp.toString());
                    return;
                }
            }

            if (hasPercent) {
                int percent = req.getInt("percent");
                // Accept 15..100: the primary SOC-target path floors at 15/25 and
                // caps at 70; the legacy fallback covers 50..100. The collector
                // clamps to whichever path applies.
                if (percent < 15 || percent > 100) {
                    response.put("success", false);
                    response.put("error", "percent must be 15..100 (got " + percent + ")");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.ChargeCapPercentCommand(percent));
                logger.info("ChargeCap: percent=" + percent + " " + r.outcome);
                last = r;
                action = "charge-cap-percent";
            }

            JSONObject resp = routedResponse(last, action);
            if (hasPercent) {
                // Echo the EFFECTIVE value the vehicle actually holds — the primary
                // SOC-target path caps at 70, so a requested 90 applies as 70. Use
                // the value the collector recorded as applied (race-free; avoids a
                // stale immediate SDK read-back).
                int effective = BydDataCollector.getInstance().getLastAppliedCapPercent();
                resp.put("percent", (effective >= 15 && effective <= 100) ? effective : req.getInt("percent"));
            }
            if (hasEnabled) resp.put("enabled", req.getBoolean("enabled"));
            // Surface the probe result so the UI can hide on the next paint.
            Boolean supported = BydDataCollector.getInstance().isChargeCapSupported();
            if (supported != null) resp.put("supported", supported.booleanValue());
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("ChargeCap command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * BEV charge cap state — SDK reads. Returns last-known target percent and
     * on/off, plus a {@code supported} flag derived from the write-read-back
     * probe (null until the user has saved at least once).
     */
    private static void handleGetChargeCap(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            int percent = collector.getChargeCapPercent();
            int enabled = collector.getChargeCapEnabled();
            Boolean supported = collector.isChargeCapSupported();
            resp.put("success", true);
            // A valid BEV cap is 15..100 % (primary SOC-target path floors at
            // 15/25 and caps at 70; legacy fallback covers 50..100). Anything
            // outside that window is a HAL sentinel (the Seal getter returns
            // 0xFFFF=65535) — surface null so the UI shows "--", not "65535%".
            resp.put("percent", (percent >= 15 && percent <= 100) ? percent : JSONObject.NULL);
            if (enabled == 0) resp.put("enabled", false);
            else if (enabled == 1) resp.put("enabled", true);
            else resp.put("enabled", JSONObject.NULL);
            // Tri-state: null = not yet probed (show optimistically),
            //           true/false = probe result from last write.
            if (supported == null) resp.put("supported", JSONObject.NULL);
            else resp.put("supported", supported.booleanValue());
            logger.info("ChargeCap GET → percent=" + percent + " enabled=" + enabled
                    + " supported=" + supported);
        } catch (Exception e) {
            logger.warn("ChargeCap read failed: " + e.getMessage());
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, resp.toString());
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

    /**
     * Build the response JSON shape the new vehicle-control UI expects:
     *   { success, path, latencyMs, message, action, outcome, commandSuccess }
     * — `success` is true on routed SUCCESS,
     * — `path` is "cloud" / "local" / "cloud-then-local" / "none",
     * — `message` is a localized user-facing string,
     * — `commandSuccess` mirrors `success` so legacy UI branches still work.
     */
    private static JSONObject routedResponse(CommandResult r, String action) {
        JSONObject resp = new JSONObject();
        try {
            boolean success = r.outcome == VehicleCommandRouter.Outcome.SUCCESS;
            resp.put("success", success);
            resp.put("commandSuccess", success);
            resp.put("path", r.pathString());
            resp.put("latencyMs", r.latencyMs);
            resp.put("message", r.displayMessage);
            resp.put("outcome", r.outcome.name().toLowerCase());
            resp.put("action", action);
            if (!success && r.error != null && r.error.getMessage() != null) {
                resp.put("error", r.error.getMessage());
            } else if (!success) {
                resp.put("error", r.displayMessage);
            }
        } catch (Exception ignored) {}
        return resp;
    }
}
