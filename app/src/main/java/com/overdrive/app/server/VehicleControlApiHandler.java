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
 *   POST /api/vehicle/lock          — CLOUD_ONLY, terminally confirmed
 *   POST /api/vehicle/unlock        — CLOUD_ONLY, terminally confirmed
 *   POST /api/vehicle/trunk         — SDK first with safe cloud fallback when parked
 *   POST /api/vehicle/window        — area=0 open/close SDK_FIRST with cloud fallback
 *   POST /api/vehicle/flash         — CLOUD_ONLY, terminally confirmed
 *   POST /api/vehicle/find-car      — CLOUD_ONLY, terminally confirmed
 *   POST /api/vehicle/climate       — power=SDK_FIRST with cloud fallback, set_temp/set_fan=SDK_ONLY
 *   GET  /api/vehicle/climate-schedule — BOOKINGAIR booking list (cloud reported)
 *   POST /api/vehicle/climate-schedule — BOOKINGAIR create/update/delete, CLOUD_ONLY
 *   POST /api/vehicle/seat          — SDK_FIRST with guarded cloud fallback
 *   POST /api/vehicle/lights        — SDK_ONLY
 *   POST /api/vehicle/adas          — SDK_ONLY
 *   POST /api/vehicle/setting       — SDK_ONLY
 *   POST /api/vehicle/media         — media volume (AudioManager) + screen brightness (setting HAL)
 *   POST /api/vehicle/battery-heat  — CLOUD_ONLY
 *   GET  /api/vehicle/charging-schedule  — cloud state with local last-known fallback
 *   POST /api/vehicle/charging-schedule  — { startChargeTime, endChargeTime, chargeWay, enabled } CLOUD_ONLY
 *   POST /api/vehicle/start-charging      — CLOUD_ONLY, terminally confirmed
 *   GET  /api/vehicle/charge-cap         — { percent, enabled, supported } SDK_ONLY (verified charge-stop backend)
 *   POST /api/vehicle/charge-cap         — { percent? 50..100, enabled? } SDK_ONLY (verified charge-stop backend)
 *   GET  /api/vehicle/ac-charge-current-limit  — { state, label, supported } SDK_ONLY
 *   POST /api/vehicle/ac-charge-current-limit  — { state: 1..5 } SDK_ONLY, readback verified
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

        // App-process actuator/activity final-boundary safety check. The caller fails
        // closed if this endpoint cannot be reached or returns malformed data.
        if (cleanPath.startsWith("/api/vehicle/driving-safety/")
                && method.equals("GET")) {
            String key = cleanPath.substring("/api/vehicle/driving-safety/".length());
            if (!DrivingSafetyGuard.isKnownGuard(key)) {
                HttpResponse.sendJsonError(out, "Unknown driving-safety guard");
                return true;
            }
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("guard", key);
            response.put("blocked", DrivingSafetyGuard.isActionBlocked(key));
            HttpResponse.sendJson(out, response.toString());
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

        // GET /api/vehicle/climate-schedule
        if (cleanPath.equals("/api/vehicle/climate-schedule") && method.equals("GET")) {
            handleGetClimateSchedule(out);
            return true;
        }

        // POST /api/vehicle/climate-schedule
        if (cleanPath.equals("/api/vehicle/climate-schedule") && method.equals("POST")) {
            handleClimateSchedule(out, body);
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

        // POST /api/vehicle/system — UI navigation + screenshot + move-app-to-display.
        // Nav / move shell out as UID 2000 (input keyevent / am start); screenshot captures
        // in-process by layer stack (the a11y takeScreenshot route is API 30+, and screencap's
        // -d takes a PhysicalDisplayId that cannot address the cluster's virtual display).
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

        // POST /api/vehicle/start-charging
        if (cleanPath.equals("/api/vehicle/start-charging") && method.equals("POST")) {
            handleStartCharging(out);
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

        // GET/POST /api/vehicle/ac-charge-current-limit
        if (cleanPath.equals("/api/vehicle/ac-charge-current-limit") && method.equals("GET")) {
            handleGetAcChargeCurrentLimit(out);
            return true;
        }
        if (cleanPath.equals("/api/vehicle/ac-charge-current-limit") && method.equals("POST")) {
            handleAcChargeCurrentLimit(out, body);
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
        // POST /api/vehicle/cluster-resize — { l,t,r,b } in CLUSTER px. Resize the LIVE cast
        // app ON the cluster to those bounds (freeform). No-op unless the feature flag is on
        // and a cast is active (then the app stays fullscreen). Distinct from cluster-mirror,
        // which only moves the head-unit PREVIEW pane.
        if (cleanPath.equals("/api/vehicle/cluster-resize") && method.equals("POST")) {
            handleClusterResize(out, body);
            return true;
        }
        // POST /api/vehicle/cluster-window — { package, l,t,r,b } as panel FRACTIONS (0..1).
        // SAVES a per-app box without touching any live cast, so a cast started with no
        // Projection screen in the loop (ACC-on auto-start, key mapping, automation) can still
        // place the app at the user's chosen scale.
        if (cleanPath.equals("/api/vehicle/cluster-window") && method.equals("POST")) {
            handleClusterWindow(out, body);
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
        // POST /api/vehicle/cluster-touch — { type: tap|swipe, sx,sy[,sx2,sy2,ms] } in
        // mirror-SURFACE px (NOT normalized); the relay inverts them to cluster px.
        if (cleanPath.equals("/api/vehicle/cluster-touch") && method.equals("POST")) {
            handleClusterTouch(out, body);
            return true;
        }

        return false;
    }

    // ── Projection screen handlers ──────────────────────────────────────────────────

    /**
     * List launchable apps for the cast picker (reuses the shared AppLauncher enum), MINUS
     * OverDrive itself.
     *
     * <p>OverDrive declares a LAUNCHER activity, so the shared enumeration includes our own
     * package — but casting ourselves onto the cluster is never what the user wants and is
     * actively hazardous: {@code com.overdrive.app} also owns the head-unit UI task AND the
     * nav-map cluster task ({@code .navmap.RoadSenseClusterMapActivity}), so a self-cast makes
     * "the cast app's task" ambiguous for the freeform resize path. That path is display-scoped
     * and so resolves correctly regardless, but removing the entry eliminates the ambiguity at
     * the source rather than relying on the downstream guard. Filtered HERE (not in
     * {@code listLaunchableApps}) so the automation / key-mapping pickers, where launching our own
     * UI is legitimate, keep their existing behaviour.
     */
    private static void handleClusterApps(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONArray all = com.overdrive.app.launcher.AppLauncher.listLaunchableApps();
            String self = com.overdrive.app.BuildConfig.APPLICATION_ID;
            JSONArray apps = new JSONArray();
            for (int i = 0; i < all.length(); i++) {
                JSONObject app = all.optJSONObject(i);
                if (app == null) continue;
                if (self.equals(app.optString("package", ""))) continue;
                apps.put(app);
            }
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

    /** Resize the LIVE cast app ON the cluster to the given CLUSTER-px bounds (freeform).
     *  Returns {@code applied=false} (not an error) when the feature is off or no cast is
     *  active — the caller treats that as "app stays fullscreen".
     *
     *  <p>{@code commit} (default true) selects the apply strategy: true = gesture release /
     *  preset / restore, running the full escalation ladder and VERIFYING that AMS honoured the
     *  rect; false = a live drag frame, issuing a cheap bounds-only update so the window tracks
     *  the finger without a stack rescan or a shell fallback. */
    private static void handleClusterResize(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            int l = req.optInt("l", 0), t = req.optInt("t", 0);
            int r = req.optInt("r", 0), b = req.optInt("b", 0);
            boolean commit = req.optBoolean("commit", true);
            boolean applied = com.overdrive.app.launcher.ClusterCast.resize(l, t, r, b, commit);
            response.put("success", true);
            response.put("applied", applied);
        } catch (Exception e) {
            logger.warn("cluster-resize failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /** Persist a per-app cluster box (panel fractions 0..1) for later UI-less casts. Purely a
     *  config write — it never resizes a live cast (that is {@code cluster-resize}), so the
     *  Projection screen can call it while nothing is being cast. {@code saved=false} means the
     *  rect was rejected as malformed or the write did not land. */
    private static void handleClusterWindow(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String pkg = req.optString("package", "");
            boolean saved = com.overdrive.app.launcher.ClusterCast.saveWindowFractions(
                    pkg, req.optDouble("l", Double.NaN), req.optDouble("t", Double.NaN),
                    req.optDouble("r", Double.NaN), req.optDouble("b", Double.NaN));
            response.put("success", true);
            response.put("saved", saved);
        } catch (Exception e) {
            logger.warn("cluster-window failed: " + e.getMessage());
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

    /** Cast state + real cluster panel size for the Projection screen. The mirror itself is
     *  driven over the ClusterViewMirrorService Binder channel (a Surface can't ride HTTP),
     *  so this endpoint only reports the cast flag + the panel aspect the UI locks its box to. */
    private static void handleClusterMirrorStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("casting", com.overdrive.app.launcher.ClusterCast.isActive());
            // WHICH package is on the cluster. The UI needs this because the resize box acts on the
            // CAST app, while the spinner reflects the user's next PICK — the two diverge as soon as
            // the user browses the list during a live cast. Without it the UI would key its geometry
            // (and its restore-on-select) to the spinner and reshape the cast app to another app's
            // remembered rect. Empty string when nothing is cast.
            String castPkg = com.overdrive.app.launcher.ClusterCast.getCastPackage();
            response.put("castPackage", castPkg != null ? castPkg : "");
            // Real cluster panel size: prefer the live view-mirror's resolved value, else
            // resolve it directly (so the box aspect-locks correctly even before the first
            // mirror attach).
            int pw = 0, ph = 0;
            try {
                com.overdrive.app.surveillance.ClusterViewMirrorService vm =
                        com.overdrive.app.surveillance.ClusterViewMirrorService.getInstance();
                pw = vm.currentClusterW();
                ph = vm.currentClusterH();
            } catch (Throwable ignored) {}
            if (pw <= 1 || ph <= 1) {
                try {
                    android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                    if (ctx != null) {
                        android.graphics.Point p =
                                com.overdrive.app.surveillance.BsNativeLayer.clusterDisplaySize(ctx);
                        pw = p.x; ph = p.y;
                    }
                } catch (Throwable ignored) {}
            }
            response.put("panelW", pw);
            response.put("panelH", ph);
        } catch (Exception e) {
            logger.warn("cluster-mirror-status failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /** Relay a tap/swipe (mirror-SURFACE px, sx/sy) into the projected app; the relay inverts
     *  the live view-mirror projection to reach the right cluster pixel. */
    private static void handleClusterTouch(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String type = req.optString("type", "tap");
            // Coordinates are mirror-SURFACE px (sx/sy); the relay inverts them through the
            // live view-mirror projection mapping to cluster px.
            boolean ok;
            if ("swipe".equals(type)) {
                ok = com.overdrive.app.surveillance.ClusterInputRelay.swipe(
                        req.optDouble("sx", 0), req.optDouble("sy", 0),
                        req.optDouble("sx2", 0), req.optDouble("sy2", 0),
                        req.optInt("ms", 0));
            } else {
                ok = com.overdrive.app.surveillance.ClusterInputRelay.tap(
                        req.optDouble("sx", 0), req.optDouble("sy", 0));
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
            // Trigger a full data collection in the background
            try {
                new Thread(() -> collector.collectAllFull(), "EarlyCollectState").start();
            } catch (Throwable ignored) {}

            // Check if cloud data is available to populate initial state
            try {
                com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                        com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
                com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
                BydVehicleData.Builder b = new BydVehicleData.Builder();
                if (cs != null) {
                    if (cs.hasSoc()) b.socPercent(cs.socPercent);
                    if (cs.hasElecRange()) b.elecRangeKm(cs.elecRangeKm);
                    if (cs.hasChargingState()) b.chargingState(cs.getChargingStateAsSdk());
                }
                data = b.build();
            } catch (Throwable ignored) {}
        }

        if (data == null) {
            data = new BydVehicleData.Builder().build();
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
            doors.put("rf", cloudLockToApi(data.doorLockStatus[0]));
            doors.put("lf", cloudLockToApi(data.doorLockStatus[1]));
            doors.put("rr", cloudLockToApi(data.doorLockStatus[2]));
            doors.put("lr", cloudLockToApi(data.doorLockStatus[3]));
            doors.put("trunk", cloudLockToApi(data.doorLockStatus[4]));
            doors.put("hood", cloudLockToApi(data.doorLockStatus[5]));
            int mappedOverall = cloudLockToApi(data.doorLockStatus[6]);
            doors.put("overall", mappedOverall);
            if (mappedOverall != -1) {
                sdkOverall = mappedOverall;
                doors.put("source", "sdk");
                doors.put("scope", "vehicle");
            }
        }

        // Track which source authoritatively set LF so we can derive `overall`
        // correctly when cloud is missing. -1 = no authoritative LF yet.
        int otaLf = -1;
        int cloudOverall = -1;
        boolean cloudAvailable = false;

        // PRIMARY: OTA LF fast-path — the same live SDK read the surveillance
        // lock gate trusts (CameraDaemon.readDoorLockStatus). Works ACC=OFF
        // with ~1.5s latency; this is the freshest signal we have.
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

        // FALLBACK: cloud overlay (full 4-door), gated on freshness. A cloud
        // snapshot older than LOCK_STATE_MAX_AGE_MS is skipped entirely —
        // hasValidLockState() stays true forever once any door has ever
        // reported, so without the isLockStateFresh() gate an hours-old
        // snapshot kept painting all four pills AND won `overall` over the
        // live OTA read (glance showed "Locked" while the driver door was
        // open-and-unlocked). Every other lock consumer already pairs the
        // two checks (BydCloudDataProvider.isLockStateFresh, the listener
        // replay, refreshLockStateIfStale); this was the odd one out.
        try {
            com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
            // Trigger an on-demand REST refresh if our cached snapshot is
            // stale. The call is internally rate-limited (30s cooldown) and
            // runs asynchronously; the *current* snapshot is used to render
            // this response, but the next request will see fresh data.
            new Thread(provider::refreshLockStateIfStale, "CloudLockRefresh").start();
            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
            if (cs != null && cs.isLockStateFresh() && cs.hasValidLockState()) {
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

        // Merge: the live OTA read is the primary signal (same priority the
        // surveillance lock gate uses — OTA first, cloud secondary), so it
        // always wins the LF cell, and an OTA "unlocked" also wins `overall`:
        // the driver door being unlocked makes the vehicle unlocked no matter
        // what the (up to 5-min-old) cloud snapshot claims. Cloud keeps
        // `overall` only for states OTA cannot see — e.g. all-locked
        // confirmation or a REAR door left unlocked while LF is locked.
        if (otaLf != -1) {
            doors.put("lf", otaLf);
            if (cloudAvailable) {
                // Derive `overall` from the MERGED per-door cells, not from
                // cloud's own unmerged view: cloud's LF cell can lag the OTA
                // read by seconds (MQTT) to minutes (REST snapshot), and
                // deriving from cloud's cells let its stale LF flip `overall`
                // to "unlocked" after the user had already locked up (payload
                // read lf=locked, overall=unlocked — self-contradictory).
                int mLf = doors.optInt("lf", -1);
                int mRf = doors.optInt("rf", -1);
                int mLr = doors.optInt("lr", -1);
                int mRr = doors.optInt("rr", -1);
                boolean anyUnlocked = mLf == 2 || mRf == 2 || mLr == 2 || mRr == 2;
                boolean allLocked = mLf == 1 && mRf == 1 && mLr == 1 && mRr == 1;
                if (anyUnlocked) {
                    // Definitive even with some cells unknown: one unlocked
                    // door makes the vehicle unlocked.
                    doors.put("overall", 2);
                    doors.put("scope", "vehicle");
                } else if (allLocked) {
                    doors.put("overall", 1);
                    doors.put("scope", "vehicle");
                } else {
                    // Some doors unknown, none unlocked: OTA still knows the
                    // driver door — publish that rather than nothing, scoped
                    // honestly so the UI never claims whole-car state.
                    doors.put("overall", otaLf);
                    doors.put("scope", "driver_door");
                }
                doors.put("source", "ota+cloud");
            } else {
                // No fresh cloud: an OTA reading describes the driver door
                // only. Keep publishing it as `overall` for compatibility
                // with the surveillance lock gate, but label the scope
                // honestly so presentation layers never call it whole-car.
                if (sdkOverall == -1) {
                    doors.put("overall", otaLf);
                    doors.put("scope", "driver_door");
                    doors.put("source", "ota");
                } else if (sdkOverall != otaLf) {
                    // The two layers disagree: OTA is the fresher read, so it wins
                    // for `overall` — but only the driver door is actually known.
                    // Keeping the SDK value under scope "vehicle" made the payload
                    // self-contradictory (lf unlocked, overall locked).
                    doors.put("overall", otaLf);
                    doors.put("scope", "driver_door");
                    doors.put("source", "sdk+ota");
                } else {
                    doors.put("scope", "vehicle");
                    doors.put("source", "sdk+ota");
                }
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
        } else {
            // Check cloud fallback
            try {
                com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                        com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
                com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
                if (cs != null && cs.hasWindows()) {
                    int[] w = cs.getWindowOpenPercentAsArray();
                    windows.put("lf", w[0]);
                    windows.put("rf", w[1]);
                    windows.put("lr", w[2]);
                    windows.put("rr", w[3]);
                    windows.put("sunroof", w[4]);
                    windows.put("sunshade", w[5]);
                }
            } catch (Exception ignored) {}
        }
        response.put("windows", windows);

        // Trunk/tailgate status from extended bodywork or cloud
        JSONObject trunk = new JSONObject();
        int trunkLock = -1;
        if (data.doorLockStatus != null && data.doorLockStatus.length >= 5) {
            trunkLock = data.doorLockStatus[4];
        }
        if (trunkLock == -1) {
            try {
                com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                        com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
                com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
                if (cs != null && cs.trunkLid != -1) {
                    trunk.put("doorStatus", cs.trunkLid == 1 ? "OPEN" : "CLOSED");
                }
            } catch (Exception ignored) {}
        }
        trunk.put("lockStatus", trunkLock);
        response.put("trunk", trunk);

        // Sunroof
        JSONObject sunroof = new JSONObject();
        if (data.sunroofState != BydVehicleData.UNAVAILABLE) {
            sunroof.put("state", data.sunroofState);
        }
        if (data.sunroofPosition != BydVehicleData.UNAVAILABLE) {
            sunroof.put("position", data.sunroofPosition);
        }
        if (!sunroof.has("state")) {
            try {
                com.overdrive.app.byd.cloud.BydCloudDataProvider provider =
                        com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
                com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs = provider.getSnapshot();
                if (cs != null && cs.skylight != -1) {
                    sunroof.put("state", cs.skylight == 2 ? 1 : 0);
                }
            } catch (Exception ignored) {}
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
        // Ambient main switch as a tri-state: true / false / omitted-when-unreadable, so a UI
        // can tell "off" apart from "this trim doesn't report it".
        if (data.ambientEnabled != BydVehicleData.UNAVAILABLE) {
            lights.put("ambientEnabled", data.ambientEnabled == 1);
        }
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
        // Steering-wheel heater. Local read first (2=on/1=off), then the cloud snapshot's
        // own wire domain. The key is omitted entirely when neither source answered, so the
        // UI shows an unknown toggle rather than asserting "off".
        int wheelHeat = data.steeringWheelHeat;
        if (wheelHeat == 1 || wheelHeat == 2) {
            seats.put("steeringHeat", wheelHeat == 2);
        } else {
            try {
                com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs =
                        com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().getSnapshot();
                if (cs != null && cs.isTelemetryFresh() && cs.hasSteeringWheelHeatState()) {
                    // steeringWheelHeatWireState(): 1=on, 3=off.
                    seats.put("steeringHeat", cs.steeringWheelHeatWireState() == 1);
                }
            } catch (Exception e) {
                logger.debug("steering-heat cloud overlay failed: " + e.getMessage());
            }
        }
        response.put("seats", seats);

        // Traction-battery preconditioning. Cloud-only in both directions — there is no
        // SDK readback — so the key is omitted unless a fresh snapshot reported it. Without
        // this the UI had no state at all: after a reload every tap re-sent "on", leaving
        // no way to switch battery heat off.
        try {
            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cs =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().getSnapshot();
            if (cs != null && cs.isTelemetryFresh() && cs.hasBatteryHeatState()) {
                response.put("batteryHeat", cs.batteryHeatState > 0);
            }
        } catch (Exception e) {
            logger.debug("battery-heat cloud read failed: " + e.getMessage());
        }

        // Climate — only report AC state if vehicle power is on (powerLevel >= 2)
        // Otherwise stale cached data shows AC on when car is actually off
        JSONObject climate = new JSONObject();
        boolean vehiclePoweredOn = (data.powerLevel != BydVehicleData.UNAVAILABLE && data.powerLevel >= 2);
        if (data.acStartState != BydVehicleData.UNAVAILABLE) {
            climate.put("acOn", vehiclePoweredOn && data.acStartState == 1);
        }
        if (data.hasFreshCabinTemperature() && !Double.isNaN(data.insideTempC)) {
            climate.put("insideTempC", data.insideTempC);
        }
        if (data.acWindMode != BydVehicleData.UNAVAILABLE) climate.put("windMode", data.acWindMode);
        if (data.acFanLevel != BydVehicleData.UNAVAILABLE && vehiclePoweredOn) climate.put("fanLevel", data.acFanLevel);
        Boolean remoteClimateActive = remoteClimateActive();
        if (remoteClimateActive != null) {
            climate.put("remoteClimateActive", remoteClimateActive.booleanValue());
        }
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
                if (kPa != BydVehicleData.UNAVAILABLE && kPa > 0 && kPa < 1000 && kPa != 4095 && kPa != 2047 && kPa != 255) {
                    t.put("kPa", kPa);
                    // PSI = kPa * 0.1450377 (matches the OEM vehicle-control app
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
        // The user's configured limits ride along with the readings so the web
        // UI colours corners against the SAME numbers that drive notifications
        // instead of its own hardcoded PSI literals. Always emitted (even when
        // no tyre data is available) so the client can paint the limits in its
        // legend regardless. kPa, already clamped + invariant-checked.
        try {
            tyres.put("limits",
                    com.overdrive.app.config.UnifiedConfigManager.getTyreThresholds());
        } catch (Throwable ignored) {
            // Non-fatal: the client falls back to its built-in defaults.
        }
        response.put("tyres", tyres);

        // Engine telemetry block was removed: the BYD Auto SDK's
        // engineCoolantLevel / oilLevel / waterTempC / gearMode feeds
        // were producing unreliable values on the test PHEV
        // (cold-engine sentinels, conflicting Engine vs Setting device
        // readings, raw 28/254 oil dipstick that the OEM vehicle-control app itself
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
     * Lock the car via the terminally confirmed cloud routing path.
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
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            Boolean enabled = jsonBoolean(req, "enabled");
            if (enabled == null) {
                response.put("success", false);
                response.put("error", "enabled must be boolean");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            CommandResult r = VehicleCommandRouter.getInstance()
                    .execute(new VehicleCommandRouter.BatteryHeatCommand(enabled.booleanValue()));
            logger.info("BatteryHeat: routed result=" + r.outcome + " enabled=" + enabled);
            JSONObject resp = routedResponse(r, "battery-heat");
            resp.put("enabled", enabled.booleanValue());
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("BatteryHeat command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /**
     * Trunk control routed via the command router.
     * Open: cloud unlock → SDK tailgate (router enforces the safety: motor only fires on unlock SUCCESS).
     * Close / stop: SDK direct.
     * Body: { "action": "open" | "close" | "stop" }
     */
    private static void handleTrunk(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        if (body == null || body.isEmpty()) {
            response.put("success", false);
            response.put("error", "trunk action is required");
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        String action;
        try {
            JSONObject req = new JSONObject(body);
            if (!req.has("action") || req.isNull("action")) {
                response.put("success", false);
                response.put("error", "trunk action is required");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            action = req.getString("action").trim().toLowerCase();
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "invalid trunk request");
            HttpResponse.sendJson(out, response.toString());
            return;
        }
        VehicleCommand cmd;
        if ("close".equals(action)) cmd = new VehicleCommandRouter.TrunkCloseCommand();
        else if ("stop".equals(action)) cmd = new VehicleCommandRouter.TrunkStopCommand();
        else if ("open".equals(action)) cmd = new VehicleCommandRouter.TrunkOpenCommand();
        else {
            response.put("success", false);
            response.put("error", "unknown trunk action: " + action);
            HttpResponse.sendJson(out, response.toString());
            return;
        }

        CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
        logger.info("Trunk: action=" + action + " routed result=" + r.outcome + " path=" + r.path);
        JSONObject resp = routedResponse(r, action);
        HttpResponse.sendJson(out, resp.toString());
    }

    /**
     * Window control routed through the command router.
     * Body: one of:
     *   { "action": "vent" }                         cloud OPENWINDOW ventilation crack
     *   { "area": 1-4 (LF/RF/LR/RR) or 0 for all, "command": 1=open, 2=close, 3=stop }
     *   { "area": 1-4,                              "targetPercent": 0..100 }
     *   { "area": 5-6, (Sunroof and Sunshade),      "targetPercent": 0..100 }
     *
     * Full opening stays SDK-only. The cloud's OPENWINDOW ventilation crack is
     * exposed separately so a successful remote vent is never reported as full open.
     */
    private static void handleWindow(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            if (req.has("action")) {
                String action = jsonString(req, "action");
                if (!"vent".equals(action)) {
                    response.put("success", false);
                    response.put("error", "window action must be vent");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.VentAllWindowsCommand());
                logger.info("Window: vent " + r.outcome + " path=" + r.path);
                HttpResponse.sendJson(out, routedResponse(r, "window-vent").toString());
                return;
            }
            if (!req.has("area") || req.isNull("area")) {
                response.put("success", false);
                response.put("error", "window area is required");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            Integer areaValue = jsonInteger(req, "area");
            if (areaValue == null) {
                response.put("success", false);
                response.put("error", "window area must be an integer");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            int area = areaValue.intValue();
            if (area < 0 || area > 6) {
                response.put("success", false);
                response.put("error", "window area out of range (0-6): " + area);
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // targetPercent → SDK closed-loop positioning
            if (req.has("targetPercent")) {
                if (area < 1 || area > 6) {
                    response.put("success", false);
                    response.put("error", Messages.get("errors.vehicle_window_target_requires_area"));
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                Integer targetValue = jsonInteger(req, "targetPercent");
                if (targetValue == null) {
                    response.put("success", false);
                    response.put("error", "targetPercent must be an integer");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                int target = targetValue.intValue();
                if (target < 0 || target > 100) {
                    response.put("success", false);
                    response.put("error", "targetPercent out of range (0-100): " + target);
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.WindowMoveCommand(area, 0, target));
                logger.info("Window: area=" + areaName(area) + " target=" + target + "% " + r.outcome);
                JSONObject resp = routedResponse(r, "window-target");
                resp.put("area", area);
                resp.put("targetPercent", target);
                HttpResponse.sendJson(out, resp.toString());
                return;
            }

            if (!req.has("command") || req.isNull("command")) {
                response.put("success", false);
                response.put("error", "window command is required");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            Integer commandValue = jsonInteger(req, "command");
            if (commandValue == null) {
                response.put("success", false);
                response.put("error", "window command must be an integer");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            int command = commandValue.intValue();
            // 1=open, 2=close, 3=stop. Nothing downstream bounds this: WindowMoveCommand does not
            // validate and setAllWindowsCommand has no range check, so command=0 wrote the HAL's own
            // "no command for this area" filler — a guaranteed no-op that still answered
            // success:true — and an arbitrary int reached the 4-window write undefined.
            if (command < 1 || command > 3) {
                response.put("success", false);
                response.put("error", "window command out of range (1=open, 2=close, 3=stop): " + command);
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            VehicleCommand cmd;
            if (area == 0 && command == 1) {
                cmd = new VehicleCommandRouter.OpenAllWindowsCommand();
            } else if (area == 0 && command == 2) {
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
     * power_on / power_off → SDK_FIRST (local HVAC, then OPENAIR / CLOSEAIR fallback).
     * set_temp / set_fan   → SDK_ONLY (no granular cloud command exposed).
     * Body: { "action": "power_on"|"power_off"|"set_temp"|"set_fan",
     *         "zone": 0|1|2, "temp": 15-33, "remoteDurationMinutes": 10|15|20|25|30,
     *         "fan": 1-7 }
     */
    private static void handleClimate(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String action = jsonString(req, "action");
            if (action == null) action = "";
            VehicleCommand cmd;
            Integer powerOnAutoOffMinutes = null;
            switch (action) {
                case "power_on": {
                    double t = 22;
                    if (req.has("temp")) {
                        Double suppliedTemp = jsonNumber(req, "temp");
                        if (suppliedTemp == null
                                || !isValidClimateStartTemperature(suppliedTemp.doubleValue())) {
                            sendClimateInputError(out, response,
                                    "temp must be a number in range 15..33 C");
                            return;
                        }
                        t = suppliedTemp.doubleValue();
                    }
                    Integer remoteDuration = req.has("remoteDurationMinutes")
                            ? jsonInteger(req, "remoteDurationMinutes") : Integer.valueOf(20);
                    if (remoteDuration == null
                            || !isValidRemoteClimateDuration(remoteDuration.intValue())) {
                        sendClimateInputError(out, response,
                                "remoteDurationMinutes must be 10, 15, 20, 25, or 30");
                        return;
                    }
                    powerOnAutoOffMinutes = optionalAutoOffMinutes(req);
                    if (req.has("autoOffMinutes") && powerOnAutoOffMinutes == null) {
                        sendClimateInputError(out, response, "autoOffMinutes must be an integer from 0 to "
                                + com.overdrive.app.byd.AcAutoOffTimer.MAX_MINUTES);
                        return;
                    }
                    cmd = new VehicleCommandRouter.ClimateOnCommand(t, remoteDuration.intValue());
                    break;
                }
                case "power_off":
                    cmd = new VehicleCommandRouter.ClimateOffCommand();
                    break;
                // Arm/cancel the "run for N minutes then switch off" timer WITHOUT touching
                // the AC's current power state, so it can be set independently of the on
                // command (from the UI, HA, or a second automation step). Zero cancels; positive
                // values arm a timer. See AcAutoOffTimer for the single-timer / last-write-wins rules.
                case "auto_off_timer": {
                    Integer minutesValue = optionalAutoOffMinutes(req);
                    if (minutesValue == null) {
                        sendClimateInputError(out, response, "autoOffMinutes must be an integer from 0 to "
                                + com.overdrive.app.byd.AcAutoOffTimer.MAX_MINUTES);
                        return;
                    }
                    int minutes = minutesValue.intValue();
                    boolean armed = com.overdrive.app.byd.AcAutoOffTimer.arm(minutes);
                    // Log like every other climate action — this branch returns early and so
                    // never reaches the shared log line below, which previously left a
                    // "cancel when nothing was armed" call with no record at all.
                    logger.info("Climate: action=auto_off_timer minutes=" + minutes
                            + " armed=" + armed);
                    JSONObject timerResp = new JSONObject();
                    timerResp.put("success", true);
                    timerResp.put("armed", armed);
                    // Report the countdown ONLY for an actually-armed timer, so the two fields
                    // can never contradict each other (a failed arm used to be able to answer
                    // armed=false alongside a positive secondsRemaining).
                    timerResp.put("secondsRemaining",
                            armed ? com.overdrive.app.byd.AcAutoOffTimer.secondsRemaining() : -1);
                    HttpResponse.sendJson(out, timerResp.toString());
                    return;
                }
                case "set_temp": {
                    Integer zoneValue = jsonInteger(req, "zone");
                    if (zoneValue == null || zoneValue.intValue() < 0 || zoneValue.intValue() > 2) {
                        sendClimateInputError(out, response, "zone must be an integer from 0 to 2");
                        return;
                    }
                    Double suppliedTemp = jsonNumber(req, "temp");
                    if (suppliedTemp == null) {
                        sendClimateInputError(out, response, "temp must be a number in range "
                                + com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MIN_C + ".."
                                + com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MAX_C + " C");
                        return;
                    }
                    int zone = zoneValue.intValue();
                    double t = suppliedTemp.doubleValue();
                    // Reject an out-of-range request rather than clamping it. setAcTemperature
                    // now clamps (it must, so a °F conversion lands in-band), which on this
                    // endpoint would turn "set 40" into a SUCCESS that actually set 33 — a
                    // silent wrong answer. The old code failed such a request, so keeping the
                    // rejection here also preserves that contract for existing callers.
                    //
                    // NaN is checked EXPLICITLY: optDouble coerces "NaN" (and any unparseable
                    // string) to Double.NaN, and every NaN comparison is false — so a bare
                    // range test passes it through, Math.round(NaN) yields 0, and the clamp
                    // turns that into "AC set to minimum, success". Comparisons alone cannot
                    // catch this; only isNaN can.
                    if (!isValidClimateTemperature(t)) {
                        sendClimateInputError(out, response, "temp must be a number in range "
                                + com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MIN_C + ".."
                                + com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MAX_C + " C");
                        return;
                    }
                    cmd = new VehicleCommandRouter.ClimateSetTempCommand(zone, t);
                    break;
                }
                // RELATIVE step: "delta" dial notches (±1 = one degree in whatever unit the
                // head unit displays). Answers with the new setpoint so a caller can show it.
                // "area" selects which dial is READ (1=driver, 2=passenger); "zone" is the
                // write target (0 = both dials, matching the OEM's own step).
                case "step_temp": {
                    Integer zoneValue = req.has("zone") ? jsonInteger(req, "zone") : Integer.valueOf(0);
                    if (zoneValue == null || zoneValue.intValue() < 0 || zoneValue.intValue() > 2) {
                        sendClimateInputError(out, response, "zone must be an integer from 0 to 2");
                        return;
                    }
                    int zone = zoneValue.intValue();
                    // "area" is which dial to READ, and must be a real dial (1 or 2) — a step
                    // reads the current setpoint before adding the delta. Default it from the
                    // write zone: passenger writes read the passenger dial, and both driver and
                    // "both" (zone 0) read the driver dial (the OEM's own choice). An explicitly
                    // supplied area must still name a real dial, so invalid payloads never become
                    // a write using a silently substituted source value.
                    int areaDefault = (zone == 2)
                            ? com.overdrive.app.byd.BydDataCollector.AC_TEMP_AREA_PASSENGER
                            : com.overdrive.app.byd.BydDataCollector.AC_TEMP_AREA_DRIVER;
                    Integer areaValue = req.has("area") ? jsonInteger(req, "area")
                            : Integer.valueOf(areaDefault);
                    if (areaValue == null
                            || (areaValue.intValue() != com.overdrive.app.byd.BydDataCollector.AC_TEMP_AREA_DRIVER
                            && areaValue.intValue() != com.overdrive.app.byd.BydDataCollector.AC_TEMP_AREA_PASSENGER)) {
                        sendClimateInputError(out, response, "area must be an integer from 1 to 2");
                        return;
                    }
                    int area = areaValue.intValue();
                    Integer deltaValue = jsonInteger(req, "delta");
                    if (deltaValue == null) {
                        sendClimateInputError(out, response, "delta must be a non-zero integer");
                        return;
                    }
                    int delta = deltaValue.intValue();
                    if (delta == 0) {
                        response.put("success", false);
                        response.put("error", "delta must be non-zero");
                        HttpResponse.sendJson(out, response.toString());
                        return;
                    }
                    // Bound the step so a hand-crafted request can't sweep beyond one full range
                    // in a single call. Derived from the WIDER band (Fahrenheit spans 27 notches,
                    // 64..91, vs Celsius' 16) so the limit doesn't silently truncate a legitimate
                    // full-range step on a °F car; the clamp still stops it at the real end.
                    final int maxDelta = com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MAX_F
                            - com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MIN_F;
                    if (delta < -maxDelta || delta > maxDelta) {
                        response.put("success", false);
                        response.put("error", "delta out of range (-" + maxDelta + ".." + maxDelta + ")");
                        HttpResponse.sendJson(out, response.toString());
                        return;
                    }
                    VehicleCommandRouter.ClimateStepTempCommand step =
                            new VehicleCommandRouter.ClimateStepTempCommand(zone, area, delta);
                    CommandResult sr = VehicleCommandRouter.getInstance().execute(step);
                    logger.info("Climate: action=step_temp delta=" + delta + " " + sr.outcome
                            + " path=" + sr.path + " setpoint=" + step.resultSetpoint);
                    JSONObject stepResp = routedResponse(sr, "climate");
                    if (step.resultSetpoint != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) {
                        stepResp.put("setpoint", step.resultSetpoint);
                    }
                    HttpResponse.sendJson(out, stepResp.toString());
                    return;
                }
                case "set_fan": {
                    Integer fanValue = jsonInteger(req, "fan");
                    if (fanValue == null || fanValue.intValue() < 1 || fanValue.intValue() > 7) {
                        sendClimateInputError(out, response, "fan must be an integer from 1 to 7");
                        return;
                    }
                    int fan = fanValue.intValue();
                    cmd = new VehicleCommandRouter.ClimateSetFanCommand(fan);
                    break;
                }
                case "auto_on":  cmd = new VehicleCommandRouter.AcAutoModeCommand(true);  break;
                case "auto_off": cmd = new VehicleCommandRouter.AcAutoModeCommand(false); break;
                case "sync_on":  cmd = new VehicleCommandRouter.AcTemperatureSyncCommand(true);  break;
                case "sync_off": cmd = new VehicleCommandRouter.AcTemperatureSyncCommand(false); break;
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
            // Auto-off timer bookkeeping, applied only when the command actually took effect
            // so a refused/failed write never leaves a timer that would switch off an AC this
            // request never managed to switch on.
            if (r.outcome == VehicleCommandRouter.Outcome.SUCCESS) {
                if ("power_on".equals(action)) {
                    // "on for N minutes". Only a POSITIVE value acts; 0 deliberately leaves any
                    // pending window untouched rather than cancelling it.
                    //
                    // It is tempting to treat an explicit 0 as "Stay on → cancel" (so this agrees
                    // with the standalone timer action, where 0 does cancel), but it must NOT:
                    // the setAc template ALWAYS emits this key, and an automation saved before
                    // the field existed gets the retrofit default 0. So a legacy "when unlocked →
                    // AC on" rule would silently cancel a 30-minute window the user had just
                    // armed from the UI. `req.has()` cannot tell "the user chose Stay on" from
                    // "this row defaulted to 0" — both arrive as 0. Use the dedicated
                    // AC Switch-off Timer action (or auto_off_timer with 0) to cancel.
                    if (powerOnAutoOffMinutes != null && powerOnAutoOffMinutes.intValue() > 0) {
                        com.overdrive.app.byd.AcAutoOffTimer.arm(powerOnAutoOffMinutes.intValue());
                    }
                }
                // NOTE: power_off does NOT need to cancel the window here — VehicleCommandRouter
                // retires it centrally on any successful ClimateOffCommand, so every surface (this
                // endpoint, Home Assistant/MQTT, key mapping) behaves identically.
            }
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
     * Schedule remote preconditioning through BYD cloud's BOOKINGAIR command.
     *
     * <p>Body: {@code { action: "create"|"update"|"delete", bookingId?,
     * bookingTime?, temp?, durationMinutes? }}. Times are epoch seconds. Create/update require a
     * future booking time, 15..31 C whole-degree temperature, and one of the OEM's duration
     * values (10/15/20/25/30 minutes); delete requires only the booking id.
     */
    private static void handleClimateSchedule(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            String action = jsonString(req, "action");
            if (!"create".equals(action) && !"update".equals(action) && !"delete".equals(action)) {
                response.put("success", false);
                response.put("error", "action must be create, update, or delete");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            Long bookingId = req.has("bookingId") ? jsonLong(req, "bookingId") : null;
            if (("update".equals(action) || "delete".equals(action))
                    && (bookingId == null || bookingId.longValue() <= 0L)) {
                response.put("success", false);
                response.put("error", "bookingId must be a positive integer for update or delete");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            Long bookingTime = null;
            Double temp = null;
            Integer duration = null;
            if (!"delete".equals(action)) {
                bookingTime = jsonLong(req, "bookingTime");
                long nowSeconds = System.currentTimeMillis() / 1000L;
                if (bookingTime == null || bookingTime.longValue() <= nowSeconds + 30L) {
                    response.put("success", false);
                    response.put("error", "bookingTime must be an epoch-second time at least 30 seconds in the future");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                temp = req.has("temp") ? jsonNumber(req, "temp") : Double.valueOf(22D);
                if (temp == null || temp.doubleValue() != Math.rint(temp.doubleValue())
                        || temp.doubleValue() < 15D || temp.doubleValue() > 31D) {
                    response.put("success", false);
                    response.put("error", "temp must be a whole number from 15 to 31 C");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                duration = req.has("durationMinutes") ? jsonInteger(req, "durationMinutes")
                        : Integer.valueOf(20);
                if (duration == null || !isValidRemoteClimateDuration(duration.intValue())) {
                    response.put("success", false);
                    response.put("error", "durationMinutes must be 10, 15, 20, 25, or 30");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
            }

            int mode = "create".equals(action) ? VehicleCommandRouter.ClimateScheduleCommand.CREATE
                    : "update".equals(action) ? VehicleCommandRouter.ClimateScheduleCommand.MODIFY
                    : VehicleCommandRouter.ClimateScheduleCommand.REMOVE;
            CommandResult result = VehicleCommandRouter.getInstance().execute(
                    new VehicleCommandRouter.ClimateScheduleCommand(
                            mode, bookingId, bookingTime, temp, duration));
            logger.info("ClimateSchedule: action=" + action + " bookingId=" + bookingId
                    + " result=" + result.outcome + " path=" + result.path);
            JSONObject resp = routedResponse(result, "climate-schedule-" + action);
            if (result.outcome == VehicleCommandRouter.Outcome.SUCCESS) {
                // Cloud booking IDs exceed JavaScript's precise integer range. Keep them
                // decimal text on every response path so callers can safely reuse them.
                if (bookingId != null) resp.put("bookingId", String.valueOf(bookingId.longValue()));
                if (bookingTime != null) resp.put("bookingTime", bookingTime.longValue());
                if (temp != null) resp.put("temp", temp.doubleValue());
                if (duration != null) resp.put("durationMinutes", duration.intValue());
            } else {
                resp.put("requestedAction", action);
                if (bookingId != null) {
                    resp.put("requestedBookingId", String.valueOf(bookingId.longValue()));
                }
                if (bookingTime != null) resp.put("requestedBookingTime", bookingTime.longValue());
            }
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("ClimateSchedule command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /** Cloud-reported BOOKINGAIR schedules. An empty list is explicitly not a deletion proof. */
    private static void handleGetClimateSchedule(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            com.overdrive.app.byd.cloud.BydCloudConfig cfg =
                    com.overdrive.app.byd.cloud.BydCloudConfig.fromUnifiedConfig();
            if (!cfg.isConfigured() || cfg.vin == null || cfg.vin.isEmpty()) {
                response.put("success", true);
                response.put("supported", false);
                response.put("reason", "cloud_not_configured");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            com.overdrive.app.byd.cloud.BydCloudClient client =
                    com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().getSharedClient();
            if (client == null) {
                response.put("success", true);
                response.put("supported", false);
                response.put("reason", "cloud_client_unavailable");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            // Capability is decided by CONFIGURATION, not by this read succeeding. The list
            // call needs the car reachable, so an asleep vehicle, a rate-limit or a network
            // blip used to return success=false and leave the caller unable to tell
            // "feature absent" from "couldn't ask right now" — which hid the scheduling UI
            // outright. Report supported=true first, then attach the bookings if we got them.
            response.put("success", true);
            response.put("supported", true);
            response.put("source", "cloud");
            try {
                JSONObject bookings = client.fetchClimateBookingList(cfg.vin);
                response.put("bookings", bookingIdsAsDecimalStrings(bookings));
                response.put("emptyBookingsMayBeStale", true);
            } catch (Exception readFailed) {
                // No booking list this time. Omit `bookings` entirely rather than sending an
                // empty one, and keep the stale flag set so an absent list is never read as
                // proof that no schedule exists.
                logger.warn("ClimateSchedule list read failed: " + readFailed.getMessage());
                response.put("bookingsUnavailable", true);
                response.put("emptyBookingsMayBeStale", true);
                response.put("reason", "vehicle_unreachable");
            }
        } catch (Exception e) {
            logger.warn("ClimateSchedule read failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * BYD booking IDs are 64-bit and commonly exceed JavaScript's exact-integer range. Return
     * them as decimal text so a list response can be fed straight back into update/delete without
     * losing low bits in a browser, Home Assistant template, or other JSON client.
     */
    private static JSONObject bookingIdsAsDecimalStrings(JSONObject bookings) {
        if (bookings == null) return new JSONObject();
        try {
            JSONObject safe = new JSONObject(bookings.toString());
            org.json.JSONArray entries = safe.optJSONArray("listInfo");
            if (entries == null) return safe;
            for (int i = 0; i < entries.length(); i++) {
                Object entry = entries.opt(i);
                if (!(entry instanceof JSONObject)) continue;
                JSONObject booking = (JSONObject) entry;
                Long bookingId = jsonLong(booking, "bookingId");
                if (bookingId != null && bookingId.longValue() > 0L) {
                    booking.put("bookingId", String.valueOf(bookingId.longValue()));
                }
            }
            return safe;
        } catch (Exception ignored) {
            // Preserve the cloud payload if it is not in the expected list shape.
            return bookings;
        }
    }

    /**
     * Seat heating / ventilation / memory-recall — SDK-first. The optional cloud
     * fallback is stateful. The router dispatches it only after obtaining a
     * fresh complete driver/passenger state from local telemetry or BYD cloud.
     *
     * Body: { "action": "heating"|"ventilation"|"position"|"save",
     *         "position": 1|2, "level": 0-2,
     *         "driverHeat": 0-2, "driverVent": 0-2,
     *         "passengerHeat": 0-2, "passengerVent": 0-2 }
     *
     * <p>{@code level} is 0=off / 1=low / 2=high — the HAL has three states. The doc previously
     * advertised 0-3; a 3 was accepted, silently collapsed to 2, and echoed back as 3, so a caller
     * was told a level the seat never reached. Out-of-range is now refused.
     *
     * <p>"position" recalls a stored driver-seat memory slot (1-2); "save" stores
     * the seat's current physical position into that slot. Both are driver-only,
     * SDK-only (no BYD cloud equivalent for seat memory).
     */
    private static void handleSeat(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            Object actionValue = req.opt("action");
            String action = actionValue instanceof String
                    ? ((String) actionValue).trim().toLowerCase() : "";
            if (!"heating".equals(action) && !"ventilation".equals(action)
                    && !"position".equals(action) && !"save".equals(action)) {
                response.put("success", false);
                response.put("error", "seat action must be heating, ventilation, position, or save");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            Integer positionValue = jsonInteger(req, "position");
            if (positionValue == null || positionValue.intValue() < 1 || positionValue.intValue() > 2) {
                response.put("success", false);
                response.put("error", "seat position must be 1 or 2");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            int position = positionValue.intValue();
            VehicleCommand cmd;
            Integer levelValue = jsonInteger(req, "level");
            Integer driverHeat = optionalSeatLevel(req, "driverHeat");
            Integer driverVent = optionalSeatLevel(req, "driverVent");
            Integer passengerHeat = optionalSeatLevel(req, "passengerHeat");
            Integer passengerVent = optionalSeatLevel(req, "passengerVent");
            boolean hasAnySeatState = req.has("driverHeat") || req.has("driverVent")
                    || req.has("passengerHeat") || req.has("passengerVent");
            boolean hasCompleteSeatState = driverHeat != null && driverVent != null
                    && passengerHeat != null && passengerVent != null;
            if (hasAnySeatState && !hasCompleteSeatState) {
                response.put("success", false);
                response.put("error", "seat climate state must include all four levels (0-2)");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            if ("position".equals(action)) {
                cmd = new VehicleCommandRouter.SeatMemoryCommand(position, false);
            } else if ("save".equals(action)) {
                cmd = new VehicleCommandRouter.SeatMemoryCommand(position, true);
            } else {
                if (levelValue == null || levelValue.intValue() < 0 || levelValue.intValue() > 2) {
                    response.put("success", false);
                    response.put("error", "level must be an integer from 0 to 2");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                int level = levelValue.intValue();
                BydVehicleData snap = BydDataCollector.getInstance().getData();
                // The SDK writes an individual seat, while the cloud command overwrites all
                // four zones. Client state may be stale or merely a UI default, so it is
                // validation-only and never authorizes a composite cloud fallback.
                boolean localSeatStateFresh = hasFreshCompleteSeatState(snap);
                int[] cloudSeatState = localSeatStateFresh
                        ? seatStateWithTarget(snap.seatHeat, snap.seatCool,
                                "ventilation".equals(action), position, level)
                        : new int[] { 0, 0, 0, 0 };
                if ("ventilation".equals(action)) {
                    cmd = new VehicleCommandRouter.SeatVentCommand(position, level,
                            cloudSeatState[0], cloudSeatState[1],
                            cloudSeatState[2], cloudSeatState[3], true,
                            localSeatStateFresh ? snap.seatClimateAtMs : 0L);
                } else {
                    cmd = new VehicleCommandRouter.SeatHeatCommand(position, level,
                            cloudSeatState[0], cloudSeatState[1],
                            cloudSeatState[2], cloudSeatState[3], true,
                            localSeatStateFresh ? snap.seatClimateAtMs : 0L);
                }
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Seat: action=" + action + " pos=" + seatPosName(position)
                    + " level=" + levelValue + " " + r.outcome);
            JSONObject resp = routedResponse(r, action);
            resp.put("position", position);
            if (levelValue != null) resp.put("level", levelValue.intValue());
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
                Boolean enabled = jsonBoolean(req, "enable");
                if (enabled == null) {
                    response.put("success", false);
                    response.put("error", "enable must be boolean");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                cmd = new VehicleCommandRouter.LightsCommand(enabled.booleanValue());
            } else if ("ambientColour".equals(target)) {
                Integer value = jsonInteger(req, "value");
                if (value == null || value.intValue() < 1 || value.intValue() > 31) {
                    response.put("success", false);
                    response.put("error", "ambientColour value must be 1-31");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                // Optional zone (front/rear/both); default both keeps prior whole-cabin behaviour.
                String zone = req.has("zone") ? jsonString(req, "zone") : "both";
                if (!"front".equals(zone) && !"rear".equals(zone) && !"both".equals(zone)) {
                    response.put("success", false);
                    response.put("error", "ambientColour zone must be front, rear, or both");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                cmd = new VehicleCommandRouter.AmbientColourCommand(value.intValue(), zone);
            } else if ("welcomeLight".equals(target)) {
                Boolean enabled = jsonBoolean(req, "enable");
                if (enabled == null) {
                    response.put("success", false);
                    response.put("error", "enable must be boolean");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                cmd = new VehicleCommandRouter.WelcomeLightCommand(enabled.booleanValue());
            } else if ("readingLight".equals(target)) {
                Boolean enabled = jsonBoolean(req, "enable");
                if (enabled == null) {
                    response.put("success", false);
                    response.put("error", "enable must be boolean");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                cmd = new VehicleCommandRouter.ReadingLightCommand(enabled.booleanValue());
            } else if ("ambientMusic".equals(target)) {
                Boolean enabled = jsonBoolean(req, "enable");
                if (enabled == null) {
                    response.put("success", false);
                    response.put("error", "enable must be boolean");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                cmd = new VehicleCommandRouter.AmbientMusicModeCommand(enabled.booleanValue());
            } else if ("headlightLevel".equals(target)) {
                // REJECT out of range rather than let the setter clamp it: a request for 50 was
                // clamped to 11 and answered success:true, so the caller was told a level it never
                // got. Same reject-not-clamp policy the AC temperature endpoint uses.
                Integer value = jsonInteger(req, "value");
                if (value == null || value.intValue() < 1 || value.intValue() > 11) {
                    response.put("success", false);
                    response.put("error", "headlightLevel value must be 1-11");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                cmd = new VehicleCommandRouter.HeadlightLevelCommand(value.intValue());
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
            VehicleCommand cmd;
            Boolean enabled = null;
            Integer mode = null;
            if ("speedLimitWarning".equals(target)) {
                enabled = jsonBoolean(req, "enable");
                if (enabled == null) {
                    response.put("success", false);
                    response.put("error", "enable must be boolean");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                cmd = new VehicleCommandRouter.AdasSpeedLimitWarningCommand(enabled.booleanValue());
            } else if ("esp".equals(target)) {
                enabled = jsonBoolean(req, "enable");
                if (enabled == null) {
                    response.put("success", false);
                    response.put("error", "enable must be boolean");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                cmd = new VehicleCommandRouter.AdasEspCommand(enabled.booleanValue());
            } else if ("laneAssist".equals(target)) {
                mode = jsonInteger(req, "mode");
                if (mode == null || mode.intValue() < 0 || mode.intValue() > 3) {
                    response.put("success", false);
                    response.put("error", "laneAssist mode must be an integer from 0 to 3");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                cmd = new VehicleCommandRouter.AdasLaneAssistCommand(mode.intValue());
            } else {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unsupported_target_with_target", target));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            CommandResult r = VehicleCommandRouter.getInstance().execute(cmd);
            logger.info("Adas: target=" + target + " enable=" + enabled + " mode=" + mode
                    + " " + r.outcome);
            JSONObject resp = routedResponse(r, "adas");
            resp.put("target", target);
            if (enabled != null) resp.put("enable", enabled.booleanValue());
            if (mode != null) resp.put("mode", mode.intValue());
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
            if (!"childPresenceDetection".equals(target)) {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unsupported_target_with_target", target));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            Integer value = jsonInteger(req, "value");
            if (value == null || value.intValue() < 1 || value.intValue() > 3) {
                response.put("success", false);
                response.put("error", "childPresenceDetection value must be 1, 2, or 3");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            CommandResult r = VehicleCommandRouter.getInstance()
                    .execute(new VehicleCommandRouter.SettingChildPresenceDetectionCommand(value.intValue()));
            logger.info("Adas: target=childPresenceDetection value=" + value + " " + r.outcome);
            JSONObject resp = routedResponse(r, "setting");
            resp.put("target", target);
            resp.put("value", value.intValue());
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
    private static String drivingSafetyGuardForDisplayTarget(String target) {
        if ("brightness".equals(target)
                || "cluster_brightness".equals(target)
                || "hud_brightness".equals(target)) {
            return DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS;
        }
        if ("hud_power".equals(target) || "screen_power".equals(target)) {
            return DrivingSafetyGuard.GUARD_DISPLAY_POWER;
        }
        return null;
    }

    private static void handleMedia(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String target = jsonString(req, "target");
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
            boolean isAmbientBrightness = "ambient_brightness".equals(target);
            boolean isAmbientPower = "ambient_power".equals(target);
            boolean isBrightness = "brightness".equals(target);
            boolean isClusterBrightness = "cluster_brightness".equals(target);
            boolean isHudBrightness = "hud_brightness".equals(target);
            boolean isHudPower = "hud_power".equals(target);
            boolean isScreenPower = "screen_power".equals(target);
            boolean isNumericTarget = isVolumeStep || isVolume || isAmbientBrightness
                    || isAmbientPower || isBrightness || isClusterBrightness || isHudBrightness
                    || isHudPower || isScreenPower;
            if (!isMediaKey && !isNumericTarget) {
                response.put("success", false);
                response.put("error", Messages.get("errors.vehicle_unsupported_target_with_target", target));
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            int value = -1;
            if (isNumericTarget) {
                Integer suppliedValue = jsonInteger(req, "value");
                if (suppliedValue == null) {
                    response.put("success", false);
                    response.put("error", "value must be an integer");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                value = suppliedValue.intValue();
            }
            if (isVolumeStep && value != -1 && value != 1) {
                response.put("success", false);
                response.put("error", "volume_step value must be -1 or 1");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            if (!isMediaKey && !isVolumeStep && (value < 0 || (!isVolume && value > 100))) {
                response.put("success", false);
                response.put("error", isVolume ? "value must be >= 0" : "value must be 0-100");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            String volumeChannel = null;
            if (isVolume || isVolumeStep) {
                volumeChannel = optionalVolumeChannel(req);
                if (volumeChannel == null) {
                    response.put("success", false);
                    response.put("error", "volume channel must be media, navigation, voice, phone, call, system, alarm, or ring");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
            }
            String displaySafetyGuard = drivingSafetyGuardForDisplayTarget(target);
            if (displaySafetyGuard != null
                    && !(isHudPower && value > 0)
                    && DrivingSafetyGuard.isActionBlocked(displaySafetyGuard)) {
                response.put("success", false);
                response.put("outcome", "blocked_driving");
                response.put("error", Messages.get("vehicle_control.blocked_driving"));
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
                ok = stepChannelVolume(volumeChannel, value);
            } else if (isVolume) {
                // Optional channel; default "media" keeps the pre-existing behaviour.
                ok = setChannelVolumeIndex(volumeChannel, value);
            } else if (isAmbientBrightness) {
                // Optional zone (front/rear/both); default both preserves prior whole-cabin
                // behaviour for callers that don't send a zone.
                String zone = optionalAmbientZone(req);
                if (zone == null) {
                    response.put("success", false);
                    response.put("error", "ambient zone must be front, rear, or both");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                ok = BydDataCollector.getInstance().setAmbientBrightnessZoned(zone, value);
            } else if (isAmbientPower) {
                // Zoned interior-ambient on/off. value 0 → off, >0 → on. "both" uses the real
                // global main switch (three-tier chain); a single zone has no dedicated switch, so
                // "off" zeroes that zone and "on" restores its PRE-OFF level, not full — see
                // setAmbientLightEnabledZoned.
                String zone = optionalAmbientZone(req);
                if (zone == null) {
                    response.put("success", false);
                    response.put("error", "ambient zone must be front, rear, or both");
                    HttpResponse.sendJson(out, response.toString());
                    return;
                }
                ok = BydDataCollector.getInstance().setAmbientLightEnabledZoned(zone, value > 0);
            } else if (isBrightness) {
                ok = BydDataCollector.getInstance().setInfotainmentBrightness(value);
            } else if (isClusterBrightness) {
                ok = BydDataCollector.getInstance().setDriverDisplayBrightness(value);
            } else if (isHudBrightness) {
                ok = BydDataCollector.getInstance().setHudBrightness(value);
            } else if (isHudPower) {
                // HUD on/off: value 0 → off, any value > 0 → on. This is the DEDICATED HUD
                // power switch (SET_HUD_SWITCH_SET, 1=on/2=off), NOT brightness — driving
                // brightness to 0 does not turn the HUD off. setHudPower actuates via the
                // app-process VehicleActuatorService. The action sends value=0 / value=100.
                ok = BydDataCollector.getInstance().setHudPower(value > 0);
            } else if (isScreenPower) {
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
            String message = req.optString("message", "");
            String validation =
                    com.overdrive.app.communication.RemoteCommunicationPolicy
                            .validateMessage(message);
            if (validation != null) {
                response.put("success", false);
                response.put("error", validation);
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            String requestedKind =
                    com.overdrive.app.communication.RemoteCommunicationPolicy
                            .normalizeKind(req.optString("kind", "toast"));
            String kind =
                    com.overdrive.app.communication.RemoteCommunicationPolicy
                            .effectiveKind(
                                    requestedKind,
                                    com.overdrive.app.communication
                                            .VehicleCommunicationSafety.isParked());
            String severity =
                    com.overdrive.app.communication.RemoteCommunicationPolicy
                            .normalizeSeverity(req.optString("severity", "info"));
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
            response.put("renderedKind", kind);
            response.put("downgraded",
                    "dialog".equals(requestedKind) && !"dialog".equals(kind));
            if (!ok) response.put("error", "message is required");
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            logger.warn("message failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /** User-visible screenshot folder; the daemon runs as shell and can write shared storage. */
    private static final String SCREENSHOT_DIR = "/storage/sdcard/OverDrive/screenshots";

    /**
     * UI navigation + screenshot + move-to-display, run as the UID-2000 daemon via
     * shell. Body: { "target": "home|back|recents|screenshot|move_display",
     *   ["display": 0|1], ["package": "com.x/.Act"] }.
     * Nav is a fire-and-forget shell exec (`input keyevent`, never blocking the request thread
     * on a hung child); screenshot captures in-process via {@link
     * com.overdrive.app.surveillance.DisplayScreenshot}; move uses `am start-activity`.
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
                    // Capture via SurfaceControl, NOT `screencap -d <id>`: on API 29 that -d is a
                    // PhysicalDisplayId (the internal panel is used only when -d is OMITTED), so
                    // `-d 0` did not reliably mean "head unit" and the cluster — a VIRTUAL display
                    // with no physical token — could never be captured that way. Both reported
                    // failures. DisplayScreenshot addresses displays by SurfaceFlinger layer stack,
                    // the mechanism the cluster mirror already proves on this device.
                    int display = req.optInt("display", 0);
                    java.io.File shot = new java.io.File(SCREENSHOT_DIR,
                            "shot_d" + display + "_" + android.os.SystemClock.uptimeMillis() + ".png");
                    android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                    com.overdrive.app.surveillance.DisplayScreenshot.Result shotResult =
                            (display == 1)
                                    ? com.overdrive.app.surveillance.DisplayScreenshot.captureCluster(ctx, shot)
                                    : com.overdrive.app.surveillance.DisplayScreenshot.captureHeadUnit(ctx, shot);
                    response.put("success", shotResult.ok);
                    response.put("target", target);
                    if (shotResult.ok) {
                        response.put("path", shotResult.path);
                        if (ctx != null) {
                            try {
                                android.media.MediaScannerConnection.scanFile(
                                        ctx,
                                        new String[] { shotResult.path },
                                        new String[] { "image/png" },
                                        null);
                            } catch (Throwable scanError) {
                                logger.debug("screenshot media scan failed: "
                                        + scanError.getMessage());
                            }
                        }
                        logger.info("screenshot: display=" + display + " -> " + shotResult.path);
                    } else {
                        response.put("error", shotResult.error);
                        logger.warn("screenshot: display=" + display + " FAILED — " + shotResult.error);
                    }
                    HttpResponse.sendJson(out, response.toString());
                    return;
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
            // "display": "screen" shows an MP4's picture fullscreen via the app-process
            // VideoPlaybackActivity (a TextureView-backed player). Its MediaPlayer must keep
            // the default Media attributes or this DiLink build can play audio with no video
            // frames, so screen playback deliberately ignores any supplied channel and uses
            // Media. Anything else (default) is audio-only and honours its chosen channel.
            boolean onScreen = "screen".equalsIgnoreCase(req.optString("display", "speakers"));
            if (onScreen && DrivingSafetyGuard.isActionBlocked(
                    DrivingSafetyGuard.GUARD_SCREEN_MEDIA)) {
                response.put("success", false);
                response.put("outcome", "blocked_driving");
                response.put("error", Messages.get("vehicle_control.blocked_driving"));
                HttpResponse.sendJson(out, 409, response.toString());
                return;
            }
            String channel = onScreen ? "media" : req.optString("channel", "media");
            boolean loop = req.optBoolean("loop", false);
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
            // Verify the file exists before dispatching. AudioPlaybackController
            // is fire-and-forget (it shells `am` and returns true unconditionally,
            // because the daemon UID can't stat the app's view), so an automation
            // still referencing a sound the user has DELETED reported success while
            // nothing played. Same for an automation saved with no sound picked,
            // whose "${name}" placeholder resolves to a literal filename. Report
            // the real reason so a broken automation is diagnosable from the UI.
            java.io.File resolvedFile = new java.io.File(resolved);
            if (!resolvedFile.isFile() || resolvedFile.length() == 0) {
                response.put("success", false);
                response.put("error", "sound not found (deleted, or never picked): " + resolved);
                response.put("path", resolved);
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            boolean ok = onScreen
                    ? com.overdrive.app.byd.AudioPlaybackController.playVideoOnScreen(resolved, loop)
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
            case "navigation":  return 14; // STREAM_NAVI — OEM nav-guidance stream (the OEM app setBroadcastVolume uses 14)
            case "voice":       return 16; // OEM voice stream (the OEM app setVoiceVolume uses 16)
                // These OEM-extended stream ints ARE settable via setStreamVolume on this HU
                // family (the OEM app does exactly this), so the "navigation volume" / "voice
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
     * request. Codes match the OEM vehicle-control app (mediaNext=87, mediaPrevious=88). Returns false on
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
            Boolean requestedEnabled = hasEnabled ? jsonBoolean(req, "enabled") : Boolean.TRUE;
            if (requestedEnabled == null) {
                response.put("success", false);
                response.put("error", "enabled must be boolean");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // Toggle-only request — just hit changeChargeStatue.
            if (!scheduleFields) {
                boolean enabled = requestedEnabled.booleanValue();
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.SmartChargingToggleCommand(enabled));
                logger.info("ChargingSchedule: toggle enabled=" + enabled + " " + r.outcome);
                JSONObject resp = routedResponse(r, "smart-charging-toggle");
                if (r.outcome == VehicleCommandRouter.Outcome.SUCCESS) {
                    resp.put("enabled", enabled);
                } else {
                    resp.put("requestedEnabled", enabled);
                }
                HttpResponse.sendJson(out, resp.toString());
                return;
            }

            // Full save — saveOrUpdate carries its own status, no pre-toggle needed.
            String start = jsonString(req, "startChargeTime");
            String end = jsonString(req, "endChargeTime");
            String way = jsonString(req, "chargeWay");
            if (!isValidChargingTime(start, false)) {
                response.put("success", false);
                response.put("error", "startChargeTime must be an exact HH:mm time");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            if (!isValidChargingTime(end, true)) {
                response.put("success", false);
                response.put("error", "endChargeTime must be an exact HH:mm time or full");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            if (!isValidChargeWay(way)) {
                response.put("success", false);
                response.put("error", "chargeWay must be s, e, or a unique comma-separated weekday set (0-6)");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            boolean enabled = requestedEnabled.booleanValue();
            CommandResult r = VehicleCommandRouter.getInstance()
                    .execute(new VehicleCommandRouter.ChargeScheduleCommand(start, end, way, enabled));
            logger.info("ChargingSchedule: save start=" + start + " end=" + end
                    + " way=" + way + " enabled=" + enabled + " " + r.outcome);
            JSONObject resp = routedResponse(r, "charge-schedule");
            if (r.outcome == VehicleCommandRouter.Outcome.SUCCESS) {
                resp.put("startChargeTime", start);
                resp.put("endChargeTime", end);
                resp.put("chargeWay", way);
                resp.put("enabled", enabled);
            } else {
                resp.put("requestedStartChargeTime", start);
                resp.put("requestedEndChargeTime", end);
                resp.put("requestedChargeWay", way);
                resp.put("requestedEnabled", enabled);
            }
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            logger.warn("ChargingSchedule command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
    }

    /** Start charging immediately through BYD cloud's confirmed status="1" flow. */
    private static void handleStartCharging(OutputStream out) throws Exception {
        CommandResult result = VehicleCommandRouter.getInstance()
                .execute(new VehicleCommandRouter.StartChargingNowCommand());
        logger.info("StartCharging: routed result=" + result.outcome + " path=" + result.path);
        HttpResponse.sendJson(out, routedResponse(result, "start-charging").toString());
    }

    /**
     * Charging-schedule state. homePage's smartChargeDto/smartJourneyDto are
     * authoritative when available. The persisted cache is only a last-known
     * fallback for a temporarily unavailable cloud connection.
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
            boolean cloudRead = false;
            try {
                JSONObject homePage = client.fetchSmartChargingStatus(cfg.vin);
                com.overdrive.app.byd.cloud.SmartChargeCache.updateFromCloud(cfg.vin, homePage);
                cloudRead = true;
            } catch (com.overdrive.app.byd.cloud.BydCloudClient.SmartChargeNotSupportedException e) {
                resp.put("success", true);
                resp.put("supported", false);
                resp.put("reason", "smart_charge_unsupported");
                HttpResponse.sendJson(out, resp.toString());
                return;
            } catch (Exception e) {
                logger.info("ChargingSchedule homePage unavailable; using cache: " + e.getMessage());
            }
            JSONObject cached = com.overdrive.app.byd.cloud.SmartChargeCache.getSnapshot(cfg.vin);
            Boolean enabled = cached.has("enabled") && !cached.isNull("enabled")
                    ? Boolean.valueOf(cached.optBoolean("enabled")) : null;
            String start = cached.optString("startChargeTime", null);
            String end = cached.optString("endChargeTime", null);
            String way = cached.optString("chargeWay", null);
            resp.put("success", true);
            resp.put("supported", true);
            resp.put("source", cloudRead ? "cloud" : "cache");
            if (enabled == null) resp.put("enabled", JSONObject.NULL);
            else resp.put("enabled", enabled.booleanValue());
            resp.put("startChargeTime", start == null ? JSONObject.NULL : start);
            resp.put("endChargeTime", end == null ? JSONObject.NULL : end);
            resp.put("chargeWay", way == null ? JSONObject.NULL : way);
            JSONObject journey = cached.optJSONObject("smartJourneyDto");
            if (journey != null) resp.put("smartJourneyDto", journey);
            logger.info("ChargingSchedule GET source=" + (cloudRead ? "cloud" : "cache")
                    + " enabled=" + enabled + " start=" + start + " end=" + end + " way=" + way);
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
     * via write-then-read-back on every successful POST and the GET
     * returns supported=false on no-op trims so the UI can hide the section.
     *
     * <p>Body: {@code { percent?: 50..100, enabled?: bool }}.
     * When both are present the verified capacity write runs first, then the
     * master switch is changed only if that write succeeded.
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
            Boolean requestedEnabled = hasEnabled ? jsonBoolean(req, "enabled") : null;
            if (hasEnabled && requestedEnabled == null) {
                response.put("success", false);
                response.put("error", "enabled must be boolean");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            Integer requestedPercent = hasPercent ? jsonInteger(req, "percent") : null;
            if (hasPercent && (requestedPercent == null
                    || requestedPercent.intValue() < 50 || requestedPercent.intValue() > 100)) {
                response.put("success", false);
                response.put("error", "percent must be an integer from 50 to 100");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            CommandResult last = null;
            String action = null;
            BydDataCollector.ChargeCapUpdateResult combinedResult = null;

            // A genuine charge-stop backend must be capacity-verified before
            // its master switch is used. A dual-field request owns one
            // collector transaction, preventing another API or MQTT update
            // from interleaving its capacity or switch write between these legs.
            if (hasEnabled && hasPercent) {
                int percent = requestedPercent.intValue();
                boolean enabled = requestedEnabled.booleanValue();
                long startedAt = System.currentTimeMillis();
                combinedResult = BydDataCollector.getInstance()
                        .setChargeCapPercentAndEnabledWithResult(percent, enabled);
                long elapsed = System.currentTimeMillis() - startedAt;
                last = combinedResult.fullyApplied
                        ? CommandResult.success(VehicleCommandRouter.Path.SDK,
                                Messages.get("vehicle_control.local_sent"), elapsed)
                        : CommandResult.failed(VehicleCommandRouter.Path.SDK,
                                Messages.get("vehicle_control.not_supported"), elapsed, null);
                logger.info("ChargeCap: combined percent=" + percent + " enabled=" + enabled
                        + " " + last.outcome);
                action = "charge-cap-toggle";
            }

            if (hasEnabled && !hasPercent) {
                boolean enabled = requestedEnabled.booleanValue();
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.ChargeCapToggleCommand(enabled));
                logger.info("ChargeCap: toggle enabled=" + enabled + " " + r.outcome);
                last = r;
                action = "charge-cap-toggle";
            }

            if (hasPercent && !hasEnabled) {
                int percent = requestedPercent.intValue();
                CommandResult r = VehicleCommandRouter.getInstance()
                        .execute(new VehicleCommandRouter.ChargeCapPercentCommand(percent));
                logger.info("ChargeCap: percent=" + percent + " " + r.outcome);
                last = r;
                action = "charge-cap-percent";
                if (r.outcome != VehicleCommandRouter.Outcome.SUCCESS) {
                    JSONObject resp = routedResponse(r, action);
                    Boolean supported = BydDataCollector.getInstance().isChargeCapSupported();
                    if (supported != null) resp.put("supported", supported.booleanValue());
                    appendChargeCapConstraints(resp, BydDataCollector.getInstance());
                    HttpResponse.sendJson(out, resp.toString());
                    return;
                }
            }

            JSONObject resp = routedResponse(last, action);
            if (hasPercent) {
                // Report a direct charge-stop register read, not the request
                // body or a PHEV SOC-hold value. A combined request exposes
                // the sample read while it still held the transaction lock.
                int effective = combinedResult != null ? combinedResult.capacityPercent
                        : BydDataCollector.getInstance().getChargeCapPercent();
                resp.put("percent", (effective >= 50 && effective <= 100) ? effective : JSONObject.NULL);
            }
            if (hasEnabled) {
                int effectiveEnabled = combinedResult != null ? combinedResult.enabledState
                        : BydDataCollector.getInstance().getChargeCapEnabled();
                resp.put("enabled", effectiveEnabled == 1 ? true
                        : effectiveEnabled == 0 ? false : JSONObject.NULL);
            }
            if (combinedResult != null && !combinedResult.fullyApplied) {
                resp.put("partialApplied", combinedResult.partiallyApplied(
                        requestedPercent.intValue(), requestedEnabled.booleanValue()));
            }
            // Surface the probe result so the UI can hide on the next paint.
            Boolean supported = BydDataCollector.getInstance().isChargeCapSupported();
            if (supported != null) resp.put("supported", supported.booleanValue());
            appendChargeCapConstraints(resp, BydDataCollector.getInstance());
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
            // A valid generic charge-stop limit is 50..100 %. Anything
            // outside that window is a HAL sentinel (the Seal getter returns
            // 0xFFFF=65535) — surface null so the UI shows "--", not "65535%".
            // Getter values are hints until a capacity write has proved that
            // this trim applies them. Do not publish a plausible no-op trim
            // value as vehicle state before that probe succeeds.
            boolean verified = Boolean.TRUE.equals(supported);
            resp.put("percent", verified && percent >= 50 && percent <= 100
                    ? percent : JSONObject.NULL);
            if (verified && enabled == 0) resp.put("enabled", false);
            else if (verified && enabled == 1) resp.put("enabled", true);
            else resp.put("enabled", JSONObject.NULL);
            // Tri-state: null = not yet probed (show optimistically),
            //           true/false = probe result from last write.
            if (supported == null) resp.put("supported", JSONObject.NULL);
            else resp.put("supported", supported.booleanValue());
            appendChargeCapConstraints(resp, collector);
            logger.info("ChargeCap GET → percent=" + percent + " enabled=" + enabled
                    + " supported=" + supported);
        } catch (Exception e) {
            logger.warn("ChargeCap read failed: " + e.getMessage());
            resp.put("success", false);
            resp.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, resp.toString());
    }

    /** Attach only verified SDK constraints so the UI never advertises an unproven range. */
    private static void appendChargeCapConstraints(JSONObject response, BydDataCollector collector)
            throws Exception {
        int min = collector.getChargeCapMinimumPercent();
        int max = collector.getChargeCapMaximumPercent();
        if (min >= 50 && max >= min && max <= 100) {
            response.put("minimumPercent", min);
            response.put("maximumPercent", max);
        }
        String kind = collector.getChargeCapControlKind();
        if (kind != null && !"unknown".equals(kind)) response.put("controlKind", kind);
    }

    private static void handleGetAcChargeCurrentLimit(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            BydDataCollector.AcChargingCurrentLimitStatus status =
                    collector.getAcChargingCurrentLimitStatus();
            int state = status.state;
            response.put("success", true);
            response.put("supported", status.supported != null
                    ? status.supported.booleanValue() : JSONObject.NULL);
            response.put("available", status.available);
            response.put("configState", status.configState);
            response.put("state", state >= BydDataCollector.AC_CHARGE_CURRENT_6A
                    && state <= BydDataCollector.AC_CHARGE_CURRENT_MAX
                    ? state : JSONObject.NULL);
            String label = BydDataCollector.acChargingCurrentLimitLabel(state);
            response.put("label", label != null ? label : JSONObject.NULL);
        } catch (Exception e) {
            logger.warn("AC charge current limit read failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleAcChargeCurrentLimit(
            OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject request = body == null || body.isEmpty()
                    ? new JSONObject() : new JSONObject(body);
            Integer state = jsonInteger(request, "state");
            if (state == null
                    || state.intValue() < BydDataCollector.AC_CHARGE_CURRENT_6A
                    || state.intValue() > BydDataCollector.AC_CHARGE_CURRENT_MAX) {
                response.put("success", false);
                response.put("error", "state must be an integer from 1 to 5");
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            BydDataCollector collector = BydDataCollector.getInstance();
            BydDataCollector.AcChargingCurrentLimitStatus status =
                    collector.getAcChargingCurrentLimitStatus();
            if (status.supported == null || !status.available) {
                response.put("success", false);
                response.put("supported", status.supported != null
                        ? status.supported.booleanValue() : JSONObject.NULL);
                response.put("available", false);
                response.put("configState", status.configState);
                response.put("error", Messages.get("vehicle_data_unavailable"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            if (!status.supported.booleanValue()) {
                response.put("success", false);
                response.put("supported", false);
                response.put("available", true);
                response.put("configState", status.configState);
                response.put("error", Messages.get("vehicle_control.not_supported"));
                HttpResponse.sendJson(out, response.toString());
                return;
            }
            CommandResult result = VehicleCommandRouter.getInstance().execute(
                    new VehicleCommandRouter.AcChargeCurrentLimitCommand(state.intValue()));
            JSONObject routed = routedResponse(result, "ac-charge-current-limit");
            int readBack = collector.getAcChargingCurrentLimitState();
            boolean confirmed = readBack == state.intValue();
            // The final HAL state is the automation/manual command contract. This also handles a
            // delayed app-process retry: it may apply after the daemon-side route timed out, or a
            // newer command may supersede this one before the response is serialized.
            routed.put("success", confirmed);
            routed.put("commandSuccess", confirmed);
            routed.put("outcome", confirmed ? "success" : "failed");
            if (confirmed) {
                routed.put("path", "sdk");
                routed.put("message", Messages.get("vehicle_control.local_sent"));
                routed.remove("error");
            } else {
                String error = Messages.get("vehicle_control.local_failed");
                routed.put("message", error);
                routed.put("error", error);
            }
            routed.put("supported", true);
            routed.put("available", readBack >= BydDataCollector.AC_CHARGE_CURRENT_6A
                    && readBack <= BydDataCollector.AC_CHARGE_CURRENT_MAX);
            routed.put("state", readBack >= BydDataCollector.AC_CHARGE_CURRENT_6A
                    && readBack <= BydDataCollector.AC_CHARGE_CURRENT_MAX
                    ? readBack : JSONObject.NULL);
            String label = BydDataCollector.acChargingCurrentLimitLabel(readBack);
            routed.put("label", label != null ? label : JSONObject.NULL);
            HttpResponse.sendJson(out, routed.toString());
        } catch (Exception e) {
            logger.warn("AC charge current limit command failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
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

    /** Strict JSON boolean: missing, string, number, and null are all rejected. */
    private static Boolean jsonBoolean(JSONObject request, String key) {
        if (request == null || !request.has(key) || request.isNull(key)) return null;
        Object value = request.opt(key);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    /** Strict JSON string: missing, non-string, and null are all rejected. */
    private static String jsonString(JSONObject request, String key) {
        if (request == null || !request.has(key) || request.isNull(key)) return null;
        Object value = request.opt(key);
        return value instanceof String ? (String) value : null;
    }

    /** Strict finite JSON number: no strings, nulls, NaN, or infinities. */
    private static Double jsonNumber(JSONObject request, String key) {
        if (request == null || !request.has(key) || request.isNull(key)) return null;
        Object value = request.opt(key);
        if (!(value instanceof Number)) return null;
        double raw = ((Number) value).doubleValue();
        return Double.isNaN(raw) || Double.isInfinite(raw) ? null : Double.valueOf(raw);
    }

    /** Strict JSON integer: numeric JSON only, with no coercion or fractional values. */
    private static Integer jsonInteger(JSONObject request, String key) {
        if (request == null || !request.has(key) || request.isNull(key)) return null;
        Object value = request.opt(key);
        if (!(value instanceof Number)) return null;
        double raw = ((Number) value).doubleValue();
        if (Double.isNaN(raw) || Double.isInfinite(raw)
                || raw != Math.rint(raw)
                || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
            return null;
        }
        return Integer.valueOf((int) raw);
    }

    /**
     * Strict JSON long: integral JSON values or canonical decimal strings only.
     *
     * <p>Automation form fields are strings, and a 64-bit BOOKINGAIR id cannot safely pass
     * through a browser JSON number. Accepting a digits-only string preserves every bit without
     * permitting coercion, fractions, exponent notation, whitespace, or a JavaScript double.
     */
    private static Long jsonLong(JSONObject request, String key) {
        if (request == null || !request.has(key) || request.isNull(key)) return null;
        Object value = request.opt(key);
        if (value instanceof String) {
            String decimal = (String) value;
            if (!decimal.matches("-?(0|[1-9][0-9]*)")) return null;
            try {
                return Long.valueOf(decimal);
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        if (!(value instanceof Number)) return null;
        // JSONObject parses integer JSON literals as integral Number types. Preserve those values
        // directly: booking ids are 64-bit and routing them through double would round their low
        // bits before a create/update/delete request ever reaches BYD.
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return Long.valueOf(((Number) value).longValue());
        }
        double raw = ((Number) value).doubleValue();
        if (Double.isNaN(raw) || Double.isInfinite(raw) || raw != Math.rint(raw)
                || raw < Long.MIN_VALUE || raw > Long.MAX_VALUE
                // A JSON floating point value above 2^53 cannot represent every integer exactly.
                || Math.abs(raw) > 9_007_199_254_740_991D) {
            return null;
        }
        return Long.valueOf((long) raw);
    }

    /**
     * Optional ambient zones default to both only when absent. A malformed value must never map
     * to the collector's catch-all area, which would unexpectedly control the entire cabin.
     */
    private static String optionalAmbientZone(JSONObject request) {
        if (request == null || !request.has("zone")) return "both";
        String zone = jsonString(request, "zone");
        return isValidAmbientZone(zone) ? zone : null;
    }

    private static boolean isValidAmbientZone(String zone) {
        return "front".equals(zone) || "rear".equals(zone) || "both".equals(zone);
    }

    /**
     * Volume controls default to media only when channel is absent. Unknown channels must not
     * reach streamForChannel's legacy media default, which would control the wrong stream.
     */
    private static String optionalVolumeChannel(JSONObject request) {
        if (request == null || !request.has("channel")) return "media";
        String channel = jsonString(request, "channel");
        return isValidVolumeChannel(channel) ? channel : null;
    }

    private static boolean isValidVolumeChannel(String channel) {
        return "media".equals(channel) || "navigation".equals(channel)
                || "voice".equals(channel) || "phone".equals(channel)
                || "call".equals(channel) || "system".equals(channel)
                || "alarm".equals(channel) || "ring".equals(channel);
    }

    /**
     * The router may expose this optional state while server and router artifacts are updated
     * independently. It is read-only and omitted when unavailable.
     */
    private static Boolean remoteClimateActive() {
        for (String methodName : new String[] { "isRemoteClimateActive", "getRemoteClimateActive" }) {
            try {
                java.lang.reflect.Method accessor = VehicleCommandRouter.class.getMethod(methodName);
                Object value = accessor.invoke(VehicleCommandRouter.getInstance());
                return value instanceof Boolean ? (Boolean) value : null;
            } catch (NoSuchMethodException ignored) {
                // The router build does not expose this optional state yet.
            } catch (Exception e) {
                logger.debug("remote climate state unavailable: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    /** Return a valid 0..2 seat level only when the field is supplied and valid. */
    private static Integer optionalSeatLevel(JSONObject request, String key) {
        if (request == null || !request.has(key) || request.isNull(key)) return null;
        Integer value = jsonInteger(request, key);
        return value != null && value.intValue() >= 0 && value.intValue() <= 2 ? value : null;
    }

    private static boolean isValidClimateTemperature(double temp) {
        return !Double.isNaN(temp) && !Double.isInfinite(temp)
                && temp >= com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MIN_C
                && temp <= com.overdrive.app.byd.BydDataCollector.AC_SETPOINT_MAX_C;
    }

    /**
     * Remote OPENAIR reaches 15/16 C while the local dial starts at 17 C.
     * The union lets an explicit 15/16 request route cloud-only and 32/33 route
     * SDK-only, without ever silently clamping the caller's target.
     */
    private static boolean isValidClimateStartTemperature(double temp) {
        return !Double.isNaN(temp) && !Double.isInfinite(temp) && temp >= 15D && temp <= 33D;
    }

    private static boolean isValidRemoteClimateDuration(int minutes) {
        return minutes == 10 || minutes == 15 || minutes == 20
                || minutes == 25 || minutes == 30;
    }

    /** Returns a valid supplied timer value, or null for missing/invalid input. */
    private static Integer optionalAutoOffMinutes(JSONObject request) {
        Integer value = jsonInteger(request, "autoOffMinutes");
        return value != null && value.intValue() >= 0
                && value.intValue() <= com.overdrive.app.byd.AcAutoOffTimer.MAX_MINUTES
                ? value : null;
    }

    private static void sendClimateInputError(OutputStream out, JSONObject response, String error)
            throws Exception {
        response.put("success", false);
        response.put("error", error);
        HttpResponse.sendJson(out, response.toString());
    }

    private static boolean isValidChargingTime(String value, boolean allowFull) {
        if (allowFull && "full".equals(value)) return true;
        if (value == null || !value.matches("\\d{2}:\\d{2}")) return false;
        int hour = (value.charAt(0) - '0') * 10 + value.charAt(1) - '0';
        int minute = (value.charAt(3) - '0') * 10 + value.charAt(4) - '0';
        return hour <= 23 && minute <= 59;
    }

    private static boolean isValidChargeWay(String value) {
        if ("s".equals(value) || "e".equals(value)) return true;
        if (value == null || value.isEmpty()) return false;
        String[] days = value.split(",", -1);
        boolean[] seen = new boolean[7];
        for (String day : days) {
            if (day.length() != 1 || day.charAt(0) < '0' || day.charAt(0) > '6') return false;
            int index = day.charAt(0) - '0';
            if (seen[index]) return false;
            seen[index] = true;
        }
        return true;
    }

    /**
     * The cloud seat command writes all four zones in one request. A partial or
     * stale snapshot would turn untouched zones off, so it is never a cloud
     * fallback source. Client-supplied levels are input-validated but are not a
     * fallback source.
     */
    private static boolean hasFreshCompleteSeatState(BydVehicleData snapshot) {
        return VehicleCommandRouter.hasFreshCompleteSeatState(snapshot);
    }

    /**
     * Start from the collector's full state and change exactly the requested zone. Callers use
     * this only after {@link #hasFreshCompleteSeatState(BydVehicleData)} succeeds; request-body
     * sibling fields deliberately never enter the composite cloud payload.
     */
    private static int[] seatStateWithTarget(int[] heat, int[] cool, boolean ventilation,
                                             int position, int level) {
        int[] state = {
                heat[0], cool[0],
                heat[1], cool[1]
        };
        int index = position == 1 ? (ventilation ? 1 : 0) : (ventilation ? 3 : 2);
        state[index] = level;
        return state;
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
    static JSONObject routedResponse(CommandResult r, String action) {
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
