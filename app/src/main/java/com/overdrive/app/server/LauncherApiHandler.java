package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

/**
 * Launcher aggregation API — the stable, public {@code /api/launcher/v1/*} face
 * over OverDrive core's internal handlers.
 *
 * <p>This is Work-Package A of the OverDrive Launcher (see
 * {@code docs/LAUNCHER_SPEC.md} §2). The launcher is a SEPARATE thin APK that
 * reads data over localhost; it must never re-derive vehicle state. These
 * endpoints DO NOT expose internal handlers directly — internal shapes change,
 * whereas the v1 shapes here are frozen. Everything is a thin, defensive
 * aggregation over existing core getters; nothing here collects new data.
 *
 * <p><b>Design invariants (spec §0):</b>
 * <ul>
 *   <li><b>Fail soft.</b> Every sub-aggregation is wrapped so one failing
 *       subsystem yields {@code null} for that sub-object, never a 500. The
 *       whole {@link #handle} is wrapped so nothing throws to the caller
 *       (spec §2.8 error contract).</li>
 *   <li><b>Never fake.</b> A value that isn't readily available from an
 *       existing core method is returned as {@code null} (JSON null), never
 *       omitted and never invented.</li>
 *   <li><b>Core is the source of truth.</b> Vehicle model/colour/driveSide,
 *       trips, charging, range, roadsense, locale, theme all come FROM core.</li>
 * </ul>
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/launcher/v1/summary}     — first-paint aggregate (§2.1)</li>
 *   <li>{@code GET  /api/launcher/v1/traffic}     — live actors for drive view (§2.2)</li>
 *   <li>{@code GET  /api/launcher/v1/apps}        — proxy of {@link AppsApiHandler} list (§2.3)</li>
 *   <li>{@code POST /api/launcher/v1/apps/launch} — proxy of app launch (§2.3)</li>
 *   <li>{@code POST /api/launcher/v1/vehicle/<cmd>} — passthrough to {@link VehicleControlApiHandler} (§2.4)</li>
 *   <li>{@code GET  /api/launcher/v1/appearance}  — theme + locale + material + launcherMode (§2.5)</li>
 * </ul>
 *
 * <p>The WebSocket push channel (spec §2.7) is intentionally NOT implemented in
 * this wave — {@code summary} (~5s) + {@code traffic} (~1s) polling is enough
 * for v1 wave-1. See the report / follow-up note.
 *
 * <p><b>Auth:</b> none required. Core's {@link AuthMiddleware} already trusts a
 * loopback request from {@code 127.0.0.1} with no proxy headers (Tier-2). The
 * launcher runs on the head unit and hits localhost, so it is trusted without a
 * token — exactly like the in-app WebView. No new auth surface is added here.
 */
public final class LauncherApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("LauncherApi");

    /** Manifest locations, mirroring {@link ModelsApiHandler} (whose readers are
     *  private). Bundled ships in the APK; the remote cache is written by the
     *  updater. Higher {@code version} wins — same precedence as core. */
    private static final String MANIFEST_BUNDLED_PATH = "/data/local/tmp/web/shared/models/manifest.json";
    private static final String MANIFEST_REMOTE_CACHE = "/data/local/tmp/overdrive/models/manifest.json";

    private LauncherApiHandler() {}

    // ==================== DISPATCH ====================

    /**
     * Route a {@code /api/launcher/v1/*} request. Always returns {@code true}
     * for any {@code /api/launcher/} path (so it never falls through to the
     * static-file 404 path). Never throws — on any failure it emits the §2.8
     * error envelope ({@code {ok:false,error}}).
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
        try {
            if (cleanPath.equals("/api/launcher/v1/summary") && "GET".equals(method)) {
                sendSummary(out);
                return true;
            }
            if (cleanPath.equals("/api/launcher/v1/traffic") && "GET".equals(method)) {
                sendTraffic(out);
                return true;
            }
            // Apps list — thin proxy of AppsApiHandler (/api/apps/list).
            if (cleanPath.equals("/api/launcher/v1/apps") && "GET".equals(method)) {
                return AppsApiHandler.handle("GET", "/api/apps/list", body, out);
            }
            // Apps launch — proxy with {package, mode:full|split|pip} → {package, split}.
            if (cleanPath.equals("/api/launcher/v1/apps/launch") && "POST".equals(method)) {
                sendAppLaunch(out, body);
                return true;
            }
            // Vehicle command passthrough — /api/launcher/v1/vehicle/<cmd>.
            if (cleanPath.startsWith("/api/launcher/v1/vehicle/") && "POST".equals(method)) {
                sendVehicleCommand(out, cleanPath, body);
                return true;
            }
            if (cleanPath.equals("/api/launcher/v1/appearance") && "GET".equals(method)) {
                sendAppearance(out);
                return true;
            }
            // Glance history (§8 configurable widgets): compact trip + charging
            // lists for the launcher's history tiles. Detail stays in the core
            // app (the launcher deep-links via the navigate_to extra).
            if (cleanPath.equals("/api/launcher/v1/history") && "GET".equals(method)) {
                sendHistory(out);
                return true;
            }
            // Any other /api/launcher path: honest 404 in the launcher envelope.
            HttpResponse.sendJson(out, 404, "{\"ok\":false,\"error\":\"unknown launcher endpoint\"}");
            return true;
        } catch (Throwable t) {
            // Spec §2.8: never throw to the caller. Emit the error envelope.
            logger.warn("launcher api failed for " + cleanPath + ": " + t.getMessage());
            try {
                HttpResponse.sendJson(out, "{\"ok\":false,\"error\":\"internal error\"}");
            } catch (Exception ignored) {}
            return true;
        }
    }

    // ==================== §2.1 SUMMARY ====================

    private static void sendSummary(OutputStream out) throws Exception {
        JSONObject root = new JSONObject();
        root.put("ts", System.currentTimeMillis());
        root.put("vehicle",   buildVehicle());
        root.put("state",     buildState());
        root.put("range",     buildRange());
        root.put("battery",   buildBattery());
        root.put("charging",  buildCharging());
        root.put("trip",      buildTrip());
        root.put("roadsense", buildRoadsense());
        root.put("tyres",     buildTyres());
        root.put("air",       buildAir());
        root.put("media",     JSONObject.NULL);   // no daemon-side MediaSession source (see report)
        root.put("env",       buildEnv());
        HttpResponse.sendJson(out, root.toString());
    }

    /** vehicle: modelId/color/driveSide from UnifiedConfigManager.getVehicle();
     *  name/isPhev from the models manifest entry for the selected modelId. */
    private static Object buildVehicle() {
        try {
            JSONObject v = com.overdrive.app.config.UnifiedConfigManager.getVehicle();
            String modelId = v.optString("modelId", "seal");
            JSONObject o = new JSONObject();
            o.put("modelId", modelId);
            o.put("color", v.optString("color", "#E8E8EC"));
            o.put("driveSide", v.optString("driveSide", "rhd"));
            JSONObject model = findManifestModel(modelId);
            if (model != null) {
                o.put("name", model.optString("name", modelId));
                o.put("isPhev", model.optBoolean("phev", false));
            } else {
                // Manifest unreadable — name unknown, but probe live drivetrain.
                o.put("name", JSONObject.NULL);
                boolean phev = false;
                try { phev = com.overdrive.app.monitor.VehicleDataMonitor.getInstance().isPhev(); }
                catch (Throwable ignored) {}
                o.put("isPhev", phev);
            }
            return o;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /** state.acc from AccMonitor + ChargingDetector; gear from GearMonitor;
     *  locked from the local SDK door array (base layer only — no cloud refresh
     *  on this hot poll); parked = gear==P. */
    private static Object buildState() {
        try {
            JSONObject o = new JSONObject();

            // acc: charging > on > off.
            boolean charging = false;
            try { charging = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging(); }
            catch (Throwable ignored) {}
            // Include the CV taper, so this agrees with charging.active in the same response. Reporting
            // acc="off" while charging.active=true is an internal contradiction the launcher then has to
            // arbitrate, and it picked the wrong one.
            try {
                com.overdrive.app.monitor.VehicleDataMonitor vm =
                        com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
                if (!charging && vm != null) {
                    com.overdrive.app.monitor.ChargingStateData cs = vm.getChargingState();
                    if (cs != null && cs.isTaperCharging) charging = true;
                }
            } catch (Throwable ignored) {}
            boolean accOn = false;
            try { accOn = com.overdrive.app.monitor.AccMonitor.isAccOn(); } catch (Throwable ignored) {}
            o.put("acc", charging ? "charging" : (accOn ? "on" : "off"));

            // gear: prefer the live GearMonitor when its poll thread is running,
            // else the last BydVehicleData snapshot, else null.
            String gear = null;
            try {
                com.overdrive.app.monitor.GearMonitor gm = com.overdrive.app.monitor.GearMonitor.getInstance();
                if (gm != null && gm.isRunning()) {
                    gear = com.overdrive.app.monitor.GearMonitor.gearToString(gm.getCurrentGear());
                }
            } catch (Throwable ignored) {}
            if (gear == null) {
                try {
                    com.overdrive.app.byd.BydVehicleData vd =
                            com.overdrive.app.byd.BydDataCollector.getInstance().getData();
                    if (vd != null && vd.gearMode != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) {
                        gear = com.overdrive.app.monitor.GearMonitor.gearToString(vd.gearMode);
                    }
                } catch (Throwable ignored) {}
            }
            o.put("gear", gear == null ? JSONObject.NULL : gear);

            // locked: base SDK door-lock array only (index 6 = derived overall;
            // 1=locked, 2=unlocked, else unknown). Deliberately does NOT trigger
            // the cloud-lock REST refresh VehicleControlApiHandler.handleGetState
            // does — that spawns threads and is too heavy for a ~5s poll. Often
            // null on trims that report INVALID(0) at ACC-off; the launcher tile
            // renders an unknown state (honest).
            Object locked = JSONObject.NULL;
            try {
                com.overdrive.app.byd.BydVehicleData vd =
                        com.overdrive.app.byd.BydDataCollector.getInstance().getData();
                if (vd != null && vd.doorLockStatus != null && vd.doorLockStatus.length >= 7) {
                    int overall = vd.doorLockStatus[6];
                    if (overall == 2) locked = Boolean.TRUE;
                    else if (overall == 1) locked = Boolean.FALSE;
                }
            } catch (Throwable ignored) {}
            o.put("locked", locked);

            // parked: gear == P.
            o.put("parked", gear != null && "P".equals(gear));
            return o;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /** range: total/elec/fuel km + socPct + fuelPct from VehicleDataMonitor. */
    private static Object buildRange() {
        try {
            com.overdrive.app.monitor.VehicleDataMonitor vm =
                    com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            com.overdrive.app.monitor.DrivingRangeData r = vm.getDrivingRange();
            com.overdrive.app.monitor.BatterySocData soc = vm.getBatterySoc();
            if (r == null && soc == null) return JSONObject.NULL;
            JSONObject o = new JSONObject();
            if (r != null) {
                o.put("totalKm", r.totalRangeKm);
                o.put("elecKm", r.elecRangeKm);
                o.put("fuelKm", r.fuelRangeKm);
                o.put("fuelPct", r.hasFuelPercent() ? round1(r.fuelPercent) : (Object) JSONObject.NULL);
            } else {
                o.put("totalKm", JSONObject.NULL);
                o.put("elecKm", JSONObject.NULL);
                o.put("fuelKm", JSONObject.NULL);
                o.put("fuelPct", JSONObject.NULL);
            }
            o.put("socPct", soc != null ? round1(soc.socPercent) : (Object) JSONObject.NULL);
            return o;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /** battery: socPct + usableKwh (nominal × displaySoh) + healthPct (SOH) +
     *  tempC (pack thermal). */
    private static Object buildBattery() {
        try {
            com.overdrive.app.monitor.VehicleDataMonitor vm =
                    com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            JSONObject o = new JSONObject();
            boolean any = false;

            com.overdrive.app.monitor.BatterySocData soc = vm.getBatterySoc();
            if (soc != null) { o.put("socPct", round1(soc.socPercent)); any = true; }
            else o.put("socPct", JSONObject.NULL);

            // usableKwh + healthPct from the SOH estimator: usable = nominal × SOH.
            Object usableKwh = JSONObject.NULL;
            Object healthPct = JSONObject.NULL;
            try {
                com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                if (soh != null) {
                    double nominal = soh.getNominalCapacityKwh();
                    boolean hasSoh = soh.hasDisplaySoh();
                    double sohPct = hasSoh ? soh.getDisplaySoh() : 100.0;
                    if (nominal > 0) {
                        usableKwh = round1(nominal * (sohPct / 100.0));
                        any = true;
                    }
                    if (hasSoh) { healthPct = (int) Math.round(sohPct); any = true; }
                }
            } catch (Throwable ignored) {}
            o.put("usableKwh", usableKwh);
            o.put("healthPct", healthPct);

            // tempC: pack thermal (avg / best available cell temp).
            Object tempC = JSONObject.NULL;
            try {
                com.overdrive.app.monitor.BatteryThermalData th = vm.getBatteryThermal();
                if (th != null) {
                    double t = th.getBestTemperature();
                    if (!Double.isNaN(t)) { tempC = (int) Math.round(t); any = true; }
                }
            } catch (Throwable ignored) {}
            o.put("tempC", tempC);

            return any ? o : JSONObject.NULL;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /** charging: active/kw from the fused detector + charging state; targetPct
     *  from the BEV charge cap; etaMin from the open session; lossPct null (no
     *  source); schedule from the BYD smart-charge cache. */
    private static Object buildCharging() {
        try {
            JSONObject o = new JSONObject();

            boolean active = false;
            try { active = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging(); }
            catch (Throwable ignored) {}
            // A CV taper IS charging. The fused verdict is intentionally false during it (the BMS calls
            // the session FINISHED while current still flows), so on its own this published
            // active=false alongside a positive MEASURED kw in the same object. Fifth outbound surface
            // to need this — the dashboard, ABRP, MQTT and the charging API all honour it.
            try {
                com.overdrive.app.monitor.VehicleDataMonitor vm =
                        com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
                if (!active && vm != null) {
                    com.overdrive.app.monitor.ChargingStateData cs = vm.getChargingState();
                    if (cs != null && cs.isTaperCharging) active = true;
                }
            } catch (Throwable ignored) {}
            o.put("active", active);

            // MEASURED only, matching every other outbound surface. getChargingState() substitutes a
            // nominal placeholder (3.3/7.0 kW) flagged isEstimated whenever the detector says
            // CHARGING but nothing has resolved a real rate yet — routine at the start of a charge,
            // and permanent on a trim where no rate source resolves. Publishing it here showed the
            // launcher widget a confident round number that has nothing to do with the actual charge.
            // MEASURED only, matching every other outbound surface. getChargingState() substitutes a
            // nominal placeholder (3.3/7.0 kW) flagged isEstimated whenever the detector says
            // CHARGING but nothing has resolved a real rate yet — routine early in a charge, and
            // permanent on a trim where no accessor ever resolves. Publishing it showed the widget a
            // confident round number unrelated to the actual charge.
            //
            // `kwEstimated` is published alongside so the widget can render a dash rather than a
            // fabricated figure. Without it, suppressing the value would make the widget read
            // "0.0 kW" while charging, which looks broken rather than unknown.
            double kw = 0;
            boolean kwEstimated = false;
            try {
                com.overdrive.app.monitor.ChargingStateData cs =
                        com.overdrive.app.monitor.VehicleDataMonitor.getInstance().getChargingState();
                if (cs != null && Double.isFinite(cs.chargingPowerKW)
                        && cs.chargingPowerKW >= 0
                        && cs.chargingPowerKW <= 500) {
                    if (cs.isEstimated) {
                        kwEstimated = true;
                    } else {
                        kw = cs.chargingPowerKW;
                    }
                }
            } catch (Throwable ignored) {}
            o.put("kw", round1(kw));
            o.put("kwEstimated", kwEstimated);

            // targetPct: verified generic charge-stop limit (valid 50..100), else null.
            Object targetPct = JSONObject.NULL;
            try {
                com.overdrive.app.byd.BydDataCollector collector =
                        com.overdrive.app.byd.BydDataCollector.getInstance();
                int pct = collector.getChargeCapPercent();
                if (Boolean.TRUE.equals(collector.isChargeCapSupported())
                        && pct >= 50 && pct <= 100) {
                    targetPct = pct;
                }
            } catch (Throwable ignored) {}
            o.put("targetPct", targetPct);

            // etaMin: time-to-full of the open charging session, else null.
            Object etaMin = JSONObject.NULL;
            try {
                com.overdrive.app.monitor.SocHistoryDatabase db =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
                if (db != null && db.getOpenChargingSessionStart() > 0) {
                    int ttf = db.getOpenChargingSessionTimeToFullMin();
                    if (ttf > 0) etaMin = ttf;
                }
            } catch (Throwable ignored) {}
            o.put("etaMin", etaMin);

            // lossPct: no charge-loss accounting source exists in core → null.
            o.put("lossPct", JSONObject.NULL);

            // schedule: BYD smart-charge cache (enabled + start time). startHhmm
            // is the cache's stored start time string (HH:mm) when present.
            JSONObject schedule = new JSONObject();
            try {
                String vin = com.overdrive.app.byd.cloud.BydCloudConfig
                        .fromUnifiedConfig().vin;
                JSONObject cached = com.overdrive.app.byd.cloud.SmartChargeCache.getSnapshot(vin);
                Boolean en = cached.has("enabled") && !cached.isNull("enabled")
                        ? Boolean.valueOf(cached.optBoolean("enabled")) : null;
                String start = cached.optString("startChargeTime", null);
                schedule.put("enabled", en == null ? JSONObject.NULL : en.booleanValue());
                schedule.put("startHhmm", (start == null || start.isEmpty()) ? JSONObject.NULL : start);
            } catch (Throwable ignored) {
                schedule.put("enabled", JSONObject.NULL);
                schedule.put("startHhmm", JSONObject.NULL);
            }
            o.put("schedule", schedule);

            return o;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /** trip: the live trip when one is active, else the most recent completed
     *  trip. dna from the trip's own 5-axis scores (pace = speedDiscipline),
     *  falling back to the 30-day average when the trip has no scored axes yet.
     *  route is null (TripRecord carries no place-name endpoints). */
    private static Object buildTrip() {
        try {
            com.overdrive.app.trips.TripAnalyticsManager tam =
                    com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
            if (tam == null) return JSONObject.NULL;
            com.overdrive.app.trips.TripDatabase db = tam.getDatabase();

            boolean live = false;
            com.overdrive.app.trips.TripRecord trip = null;
            try {
                live = tam.isTripActive();
                if (live) trip = tam.getActiveTrip();
            } catch (Throwable ignored) {}
            if (trip == null && db != null) {
                try {
                    java.util.List<com.overdrive.app.trips.TripRecord> recent = db.getTrips(365, 1, 0);
                    if (recent != null && !recent.isEmpty()) trip = recent.get(0);
                } catch (Throwable ignored) {}
            }
            if (trip == null) return JSONObject.NULL;

            JSONObject o = new JSONObject();
            o.put("phase", live ? "live" : "past");
            o.put("distanceKm", round1(trip.distanceKm));
            o.put("durationMin", trip.durationSeconds / 60);
            // whPerKm from kWh/km × 1000; null when no energy figure exists.
            o.put("whPerKm", trip.energyPerKm > 0 ? (int) Math.round(trip.energyPerKm * 1000.0) : (Object) JSONObject.NULL);
            o.put("cost", trip.tripCost > 0 ? round1(trip.tripCost) : (Object) JSONObject.NULL);
            o.put("currency", (trip.currency != null && !trip.currency.isEmpty()) ? trip.currency : (Object) JSONObject.NULL);
            // route: no start/end place-name fields on TripRecord → null (honest).
            o.put("route", JSONObject.NULL);

            // dna: the trip's own axis scores; if unscored (live/recovered trip),
            // fall back to the 30-day average DNA.
            JSONObject dna = new JSONObject();
            int overall = trip.getOverallScore();
            if (overall > 0) {
                dna.put("overall", overall);
                dna.put("anticipation", trip.anticipationScore);
                dna.put("smoothness", trip.smoothnessScore);
                dna.put("efficiency", trip.efficiencyScore);
                dna.put("consistency", trip.consistencyScore);
                dna.put("pace", trip.speedDisciplineScore);
                o.put("dna", dna);
            } else {
                Object avg = JSONObject.NULL;
                try {
                    if (db != null) {
                        com.overdrive.app.trips.DnaScores s = db.getAverageDna(30);
                        if (s != null) {
                            dna.put("overall", s.getOverall());
                            dna.put("anticipation", s.anticipation);
                            dna.put("smoothness", s.smoothness);
                            dna.put("efficiency", s.efficiency);
                            dna.put("consistency", s.consistency);
                            dna.put("pace", s.speedDiscipline);
                            avg = dna;
                        }
                    }
                } catch (Throwable ignored) {}
                o.put("dna", avg);
            }
            return o;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /** roadsense: active from the user toggle (roadSense.enabled in the unified
     *  config); cameras from the available-camera set. alerts has no live
     *  counter in core → 0 (see report). */
    private static Object buildRoadsense() {
        try {
            JSONObject o = new JSONObject();
            boolean enabled = false;
            try {
                JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                        .optJSONObject("roadSense");
                enabled = cfg != null && cfg.optBoolean("enabled", false);
            } catch (Throwable ignored) {}
            o.put("active", enabled);
            // alerts: core exposes no live "current alert count" accessor; the
            // RoadSense store holds persisted hazards, not a live alert tally.
            // Report 0 (neutral) rather than inventing a number.
            o.put("alerts", 0);
            // cameras: number of cameras the surveillance layer can serve (1..4).
            int cams = 0;
            try {
                JSONArray avail = com.overdrive.app.server.TcpCommandServer.getAvailableCameras();
                if (avail != null) cams = avail.length();
            } catch (Throwable ignored) {}
            o.put("cameras", cams);
            return o;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /**
     * tyres: per-corner pressure in bar (kPa / 100), indices [FL,FR,RL,RR],
     * plus the user's configured advisory band so the launcher widget tints
     * against the SAME limits as notifications and Vehicle Control instead of
     * its own hardcoded 2.1–2.9 bar defaults.
     *
     * <p>The widget shows one band for all four wheels, so we send the widest
     * span across both axles: min(lows) .. max(highs). Narrowing it would tint
     * an in-spec wheel as out-of-band on a car with different front/rear
     * placard pressures.
     *
     * <p>The BAND is emitted at full precision — deliberately NOT through
     * {@link #round1}, which is a 1-decimal DISPLAY rounder for readings. The
     * default low of 234 kPa would round to 2.3 bar, and since wheel readings
     * round the same way, wheels at 230-233 kPa (2.3 bar) would compare as
     * in-band against a 2.3 low while the notification path warns on them
     * (230 < 234). That dead zone made the widget silently under-warn on the
     * safety-relevant under-inflation side, and was strictly worse than the
     * omit-the-band fallback (2.34). Both keys are ALWAYS emitted (JSON null on
     * failure) per the launcher's never-omit wire contract.
     */
    private static Object buildTyres() {
        try {
            com.overdrive.app.byd.BydVehicleData vd =
                    com.overdrive.app.byd.BydDataCollector.getInstance().getData();
            if (vd == null || vd.tyrePressure == null || vd.tyrePressure.length < 4) return JSONObject.NULL;
            int[] p = vd.tyrePressure;
            JSONObject o = new JSONObject();
            o.put("flBar", barOrNull(p[0]));
            o.put("frBar", barOrNull(p[1]));
            o.put("rlBar", barOrNull(p[2]));
            o.put("rrBar", barOrNull(p[3]));
            // All-unknown → whole object null so the tile shows an empty state.
            if (o.get("flBar") == JSONObject.NULL && o.get("frBar") == JSONObject.NULL
                    && o.get("rlBar") == JSONObject.NULL && o.get("rrBar") == JSONObject.NULL) {
                return JSONObject.NULL;
            }
            Object loBar = JSONObject.NULL;
            Object hiBar = JSONObject.NULL;
            try {
                JSONObject th = com.overdrive.app.config.UnifiedConfigManager.getTyreThresholds();
                int low = Math.min(th.optInt("frontLow"), th.optInt("rearLow"));
                int high = Math.max(th.optInt("frontHigh"), th.optInt("rearHigh"));
                // Exact kPa/100 — see the band-precision note in the javadoc.
                loBar = low / 100.0;
                hiBar = high / 100.0;
            } catch (Throwable ignored) {
                // Leave both null — the client falls back to its own defaults.
            }
            o.put("loBar", loBar);
            o.put("hiBar", hiBar);
            return o;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    private static Object buildAir() {
        try {
            com.overdrive.app.byd.BydVehicleData vd =
                    com.overdrive.app.byd.BydDataCollector.getInstance().getData();
            if (vd == null) return JSONObject.NULL;
            JSONObject o = new JSONObject();
            // Always emit every key (never-omit contract); JSON null when unavailable.
            o.put("pm25Inside", vd.pm25Inside != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                    ? vd.pm25Inside : JSONObject.NULL);
            o.put("pm25Outside", vd.pm25Outside != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                    ? vd.pm25Outside : JSONObject.NULL);
            // No PM2.5 reading available → whole object null so the tile shows an empty state.
            if (o.get("pm25Inside") == JSONObject.NULL && o.get("pm25Outside") == JSONObject.NULL) {
                return JSONObject.NULL;
            }
            return o;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    /**
     * kPa → bar for the launcher tyre tile. Exact (kPa/100), NOT round1'd: the
     * widget compares this against the advisory band and formats its own display
     * string via formatOneDecimal, so rounding here only corrupts the
     * comparison. With round1 on both sides, 230-233 kPa collapsed to 2.3 and
     * compared as in-band against a 2.3 low (under-warn); with round1 on the
     * reading alone, 234 kPa (2.3) compared as BELOW an exact 2.34 low
     * (over-warn). Reading and band must share the same precision.
     */
    private static Object barOrNull(int kPa) {
        if (kPa == com.overdrive.app.byd.BydVehicleData.UNAVAILABLE || kPa <= 0) return JSONObject.NULL;
        return kPa / 100.0;   // 1 bar = 100 kPa
    }

    /** env: tempC from weather cache (fallback: cabin-external instrument temp);
     *  condition derived from precip probability (clear|rain only — no snow/fog
     *  classifier); place from the reverse-geocode cache; area null (no OSM
     *  landuse classifier reachable); isNight from the solar calculator. */
    private static Object buildEnv() {
        try {
            JSONObject o = new JSONObject();
            boolean any = false;

            // Location first — several env fields depend on it.
            double lat = 0, lon = 0;
            boolean hasLoc = false;
            try {
                com.overdrive.app.monitor.GpsMonitor gps = com.overdrive.app.monitor.GpsMonitor.getInstance();
                if (gps != null && gps.hasLocation()) {
                    lat = gps.getLatitude();
                    lon = gps.getLongitude();
                    hasLoc = true;
                }
            } catch (Throwable ignored) {}

            // tempC: shared weather cache, else the BYD instrument external temp.
            Object tempC = JSONObject.NULL;
            try {
                double wt = com.overdrive.app.weather.WeatherTemperature.getCached();
                if (!Double.isNaN(wt)) { tempC = (int) Math.round(wt); any = true; }
                else if (hasLoc) {
                    // Nudge a background refresh so the next poll has a value; never blocks.
                    com.overdrive.app.weather.WeatherTemperature.refreshAsync(lat, lon);
                }
            } catch (Throwable ignored) {}
            if (tempC == JSONObject.NULL) {
                try {
                    android.hardware.bydauto.instrument.BYDAutoInstrumentDevice inst =
                            android.hardware.bydauto.instrument.BYDAutoInstrumentDevice.getInstance(null);
                    if (inst != null) {
                        int t = inst.getOutCarTemperature();
                        if (t > -60 && t < 80) { tempC = t; any = true; }
                    }
                } catch (Throwable ignored) {}
            }
            o.put("tempC", tempC);

            // condition: only clear/rain are derivable (from precip probability).
            // snow/fog need a real weather classifier core doesn't have → left
            // out of the mapping (never faked).
            Object condition = JSONObject.NULL;
            try {
                int precip = com.overdrive.app.weather.WeatherTemperature.getCachedPrecipProbability();
                if (precip >= 0) { condition = precip >= 50 ? "rain" : "clear"; any = true; }
            } catch (Throwable ignored) {}
            o.put("condition", condition);

            // place: reverse-geocode cache (no network on this path). Null when
            // nothing is cached for the current location.
            Object place = JSONObject.NULL;
            if (hasLoc) {
                try {
                    com.overdrive.app.geo.PlaceResult pr =
                            com.overdrive.app.geo.GeocodingResolver.getInstance().resolveCachedOnly(lat, lon);
                    if (pr != null) {
                        String label = pr.mediumLabel();
                        if (label != null && !label.isEmpty()) { place = label; any = true; }
                    }
                } catch (Throwable ignored) {}
            }
            o.put("place", place);

            // area: urban|residential|highway|rural needs an OSM landuse lookup
            // core does not expose → null (never faked). See report.
            o.put("area", JSONObject.NULL);

            // isNight: solar calculator against the current location + local time.
            Object isNight = JSONObject.NULL;
            if (hasLoc) {
                try {
                    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
                    java.time.LocalDate today = java.time.LocalDate.now(zone);
                    com.overdrive.app.automation.condition.SolarCalculator.SunTimes st =
                            com.overdrive.app.automation.condition.SolarCalculator.compute(today, lat, lon, zone);
                    if (st != null) {
                        if (st.alwaysUp) { isNight = Boolean.FALSE; }
                        else if (st.alwaysDown) { isNight = Boolean.TRUE; }
                        else {
                            java.time.LocalTime now = java.time.LocalTime.now(zone);
                            int nowMin = now.getHour() * 60 + now.getMinute();
                            isNight = (nowMin < st.sunriseMinute || nowMin >= st.sunsetMinute)
                                    ? Boolean.TRUE : Boolean.FALSE;
                        }
                        any = true;
                    }
                } catch (Throwable ignored) {}
            }
            o.put("isNight", isNight);

            return any ? o : JSONObject.NULL;
        } catch (Throwable t) {
            return JSONObject.NULL;
        }
    }

    // ==================== §2.2 TRAFFIC ====================

    /** Live actors from the surveillance engine's last actor snapshot, mapped to
     *  the honest quad + proximity-band + trend shape (NO metric X/Z — verified
     *  {@code Actor.java}: "no metric distance"). Traffic-light signals are
     *  empty: core's YOLO tracker collapses COCO into 5 groups (person / vehicle
     *  / bike / animal / unknown) with no traffic-light class, so there is no
     *  presence source and {@code state} would be unknown at best. */
    // ==================== §8 GLANCE HISTORY ====================

    /**
     * {@code GET /api/launcher/v1/history} — compact recent trip + charging
     * lists for the launcher's glance widgets. Deliberately SMALL (5 rows each,
     * pre-rounded display fields only): the launcher shows a glance, then
     * deep-links into the core app for full detail. Same fail-soft rules as the
     * summary — a broken store yields an empty array, never an error.
     *
     * Shape:
     * {@code { ts, trips:[{startTime, distanceKm, durationMin, whPerKm, cost,
     *   currency, score}], charging:[{startTime, endSoc, startSoc, kwh, kw,
     *   cost, currency, durationMin, dc}] }}
     */
    private static void sendHistory(OutputStream out) throws Exception {
        JSONObject root = new JSONObject();
        root.put("ts", System.currentTimeMillis());

        JSONArray trips = new JSONArray();
        try {
            com.overdrive.app.trips.TripAnalyticsManager tam =
                    com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
            com.overdrive.app.trips.TripDatabase db = (tam != null) ? tam.getDatabase() : null;
            if (db != null) {
                java.util.List<com.overdrive.app.trips.TripRecord> recent = db.getTrips(365, 5, 0);
                if (recent != null) {
                    for (com.overdrive.app.trips.TripRecord t : recent) {
                        if (t == null) continue;
                        JSONObject j = new JSONObject();
                        j.put("startTime", t.startTime);
                        j.put("distanceKm", round1(t.distanceKm));
                        j.put("durationMin", t.durationSeconds / 60);
                        j.put("whPerKm", t.energyPerKm > 0
                                ? (int) Math.round(t.energyPerKm * 1000.0) : (Object) JSONObject.NULL);
                        j.put("cost", t.tripCost > 0 ? round1(t.tripCost) : (Object) JSONObject.NULL);
                        j.put("currency", (t.currency != null && !t.currency.isEmpty())
                                ? t.currency : (Object) JSONObject.NULL);
                        int score = t.getOverallScore();
                        j.put("score", score > 0 ? score : (Object) JSONObject.NULL);
                        trips.put(j);
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("history trips unavailable: " + t.getMessage());
        }
        root.put("trips", trips);

        JSONArray charging = new JSONArray();
        try {
            com.overdrive.app.monitor.SocHistoryDatabase db =
                    com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
            JSONArray sessions = (db != null) ? db.getChargingSessionsV2(365, 5, 0) : null;
            if (sessions != null) {
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject s = sessions.optJSONObject(i);
                    if (s == null) continue;
                    JSONObject j = new JSONObject();
                    j.put("startTime", s.optLong("startTime"));
                    j.put("startSoc", s.opt("startSoc"));
                    j.put("endSoc", s.opt("endSoc"));
                    j.put("kwh", s.opt("energyAdded"));
                    j.put("energyIncomplete",
                            s.optBoolean(
                                    "energyIncomplete",
                                    false));
                    j.put("energyEstimated",
                            s.optBoolean(
                                    "energyEstimated",
                                    !s.has("energySource")
                                            || s.isNull("energySource")));
                    j.put("energySource",
                            s.opt("energySource"));
                    j.put("kw", s.opt("avgPower"));
                    j.put("cost", s.opt("cost"));
                    j.put("currency", s.opt("currency"));
                    j.put("durationMin", s.opt("durationMinutes"));
                    j.put("dc", s.has("isDc")
                            ? s.opt("isDc") : JSONObject.NULL);
                    charging.put(j);
                }
            }
        } catch (Throwable t) {
            logger.debug("history charging unavailable: " + t.getMessage());
        }
        root.put("charging", charging);

        HttpResponse.sendJson(out, root.toString());
    }

    private static void sendTraffic(OutputStream out) throws Exception {
        JSONObject root = new JSONObject();
        root.put("ts", System.currentTimeMillis());
        JSONArray actors = new JSONArray();
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipe =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            com.overdrive.app.surveillance.SurveillanceEngineGpu engine =
                    (pipe != null) ? pipe.getSentry() : null;
            if (engine != null) {
                java.util.List<com.overdrive.app.surveillance.Actor> live = engine.getLastActors();
                if (live != null) {
                    for (com.overdrive.app.surveillance.Actor a : live) {
                        if (a == null) continue;
                        String cls = classLabel(a.classGroup);
                        if (cls == null) continue;   // UNKNOWN group — skip, don't invent
                        JSONObject j = new JSONObject();
                        j.put("id", a.actorId);
                        j.put("cls", cls);
                        j.put("quad", quadLabel(a.lastCamera));
                        j.put("prox", a.lastProximity != null ? a.lastProximity.name() : "UNKNOWN");
                        j.put("trend", trendLabel(a.trend));
                        j.put("laneHint", -1);   // no lane-matching in v1 (spec §13) → sentinel
                        actors.put(j);
                    }
                }
            }
        } catch (Throwable t) {
            // Fail soft: on any error the actors list stays empty.
            logger.debug("traffic actors unavailable: " + t.getMessage());
        }
        root.put("actors", actors);
        // signals: no traffic-light presence/colour source (see doc above).
        root.put("signals", new JSONArray());
        HttpResponse.sendJson(out, root.toString());
    }

    /** Actor.ClassGroup → spec {@code cls}. VEHICLE maps to "car" (the coarse
     *  group can't separate car from truck — honest widening). UNKNOWN → null so
     *  the caller skips it rather than mislabelling. */
    private static String classLabel(com.overdrive.app.surveillance.Actor.ClassGroup g) {
        if (g == null) return null;
        switch (g) {
            case PERSON:  return "person";
            case VEHICLE: return "car";
            case BIKE:    return "bike";
            case ANIMAL:  return "animal";
            default:      return null;   // UNKNOWN
        }
    }

    /** Quadrant index (0=front,1=right,2=rear,3=left — per Actor.cameraMask). */
    private static String quadLabel(int cameraIdx) {
        switch (cameraIdx) {
            case 0: return "front";
            case 1: return "right";
            case 2: return "rear";
            case 3: return "left";
            default: return "front";
        }
    }

    private static String trendLabel(com.overdrive.app.surveillance.Actor.Trend t) {
        if (t == null) return "steady";
        switch (t) {
            case APPROACHING: return "approaching";
            case RECEDING:    return "receding";
            default:          return "steady";   // STABLE / UNKNOWN
        }
    }

    // ==================== §2.3 APPS (launch proxy) ====================

    /** Translate {@code {package, mode:full|split|pip}} → AppsApiHandler's
     *  {@code {package, split}} and delegate. PIP is delivered by WP-E; here it
     *  degrades to a full launch (split=false). */
    private static void sendAppLaunch(OutputStream out, String body) throws Exception {
        String pkg = null;
        String mode = "full";
        try {
            JSONObject req = new JSONObject(body == null ? "{}" : body);
            pkg = req.optString("package", null);
            mode = req.optString("mode", "full");
        } catch (Throwable ignored) {}
        JSONObject fwd = new JSONObject();
        fwd.put("package", pkg == null ? "" : pkg);
        fwd.put("split", "split".equals(mode));
        // Delegate to the real handler (writes the {success,...} response).
        AppsApiHandler.handle("POST", "/api/apps/launch", fwd.toString(), out);
    }

    // ==================== §2.4 VEHICLE (command passthrough) ====================

    /** Passthrough to {@link VehicleControlApiHandler}, reshaped to {@code {ok,
     *  reason}} (spec §2.4). Core still enforces all speed/gear gating; the
     *  launcher only shows the control disabled when gear != P. */
    private static void sendVehicleCommand(OutputStream out, String cleanPath, String body) throws Exception {
        String cmd = cleanPath.substring("/api/launcher/v1/vehicle/".length());
        String corePath = mapVehicleCmd(cmd);
        if (corePath == null) {
            JSONObject r = new JSONObject();
            r.put("ok", false);
            r.put("reason", "unsupported command: " + cmd);
            HttpResponse.sendJson(out, r.toString());
            return;
        }
        JSONObject resp = new JSONObject();
        try {
            // Capture the core handler's full HTTP response into a buffer, then
            // extract + re-shape its JSON body to {ok, reason}.
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            VehicleControlApiHandler.handle("POST", corePath, body == null ? "" : body, buf);
            JSONObject core = extractJsonBody(buf.toByteArray());
            boolean ok = false;
            String reason = "";
            if (core != null) {
                ok = core.optBoolean("success", core.optBoolean("ok", false));
                reason = core.optString("message",
                         core.optString("reason",
                         core.optString("error", "")));
            } else {
                reason = "no response from vehicle handler";
            }
            resp.put("ok", ok);
            resp.put("reason", reason);
        } catch (Throwable t) {
            resp.put("ok", false);
            resp.put("reason", t.getMessage() != null ? t.getMessage() : "vehicle command failed");
        }
        HttpResponse.sendJson(out, resp.toString());
    }

    /** cmd ∈ {lock,unlock,trunk,window,climate,flash,find} → core path. */
    private static String mapVehicleCmd(String cmd) {
        if (cmd == null) return null;
        switch (cmd) {
            case "lock":    return "/api/vehicle/lock";
            case "unlock":  return "/api/vehicle/unlock";
            case "trunk":   return "/api/vehicle/trunk";
            case "window":  return "/api/vehicle/window";
            case "climate": return "/api/vehicle/climate";
            case "flash":   return "/api/vehicle/flash";
            case "find":    return "/api/vehicle/find-car";
            default:        return null;
        }
    }

    // ==================== §2.5 APPEARANCE ====================

    /** theme + material + launcherMode from the web-shell appearance config;
     *  locale from LocaleManager. Core stores a web theme (dark/light/auto);
     *  the launcher's four named themes (polar/glacier/ember/signal) live in the
     *  launcher, so we only echo a value here if it already names a launcher
     *  theme, otherwise "auto" (follow core) — never a fabricated mapping. */
    private static void sendAppearance(OutputStream out) throws Exception {
        JSONObject o = new JSONObject();
        try {
            JSONObject app = com.overdrive.app.config.UnifiedConfigManager.getAppearance();

            // theme: accept a launcher theme name if one has been stored,
            // otherwise "auto".
            String theme = app.optString("theme", "auto");
            if (!("polar".equals(theme) || "glacier".equals(theme)
                    || "ember".equals(theme) || "signal".equals(theme) || "auto".equals(theme))) {
                theme = "auto";
            }
            o.put("theme", theme);

            // locale: the persisted user pick, "auto", or (unset) → "auto".
            String locale;
            try {
                String raw = com.overdrive.app.server.LocaleManager.getRaw();
                locale = (raw == null || raw.isEmpty()) ? "auto" : raw;
            } catch (Throwable t) {
                locale = "auto";
            }
            o.put("locale", locale);

            // material: crystal|smoke (glass blur/opacity), default crystal.
            String material = app.optString("material", "crystal");
            if (!("crystal".equals(material) || "smoke".equals(material))) material = "crystal";
            o.put("material", material);

            // launcherMode: whether the launcher HOME role is enabled. No core
            // side-effect tracks it; default false until the launcher sets it.
            o.put("launcherMode", app.optBoolean("launcherMode", false));
        } catch (Throwable t) {
            // Safe, spec-valid defaults on total failure.
            o = new JSONObject();
            o.put("theme", "auto");
            o.put("locale", "auto");
            o.put("material", "crystal");
            o.put("launcherMode", false);
        }
        HttpResponse.sendJson(out, o.toString());
    }

    // ==================== HELPERS ====================

    /** Round to one decimal place. */
    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Find the selected model's manifest entry (bundled vs remote-cache, higher
     *  {@code version} wins — mirrors {@link ModelsApiHandler}'s private reader). */
    private static JSONObject findManifestModel(String modelId) {
        if (modelId == null || modelId.isEmpty()) return null;
        JSONObject manifest = readBestManifest();
        if (manifest == null) return null;
        JSONArray arr = manifest.optJSONArray("models");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null && modelId.equals(m.optString("id"))) return m;
        }
        return null;
    }

    private static JSONObject readBestManifest() {
        JSONObject bundled = readManifestFile(new File(MANIFEST_BUNDLED_PATH));
        JSONObject cached = readManifestFile(new File(MANIFEST_REMOTE_CACHE));
        if (bundled == null) return cached;
        if (cached == null) return bundled;
        return cached.optInt("version", 0) > bundled.optInt("version", 0) ? cached : bundled;
    }

    private static JSONObject readManifestFile(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int total = 0;
            while (total < buf.length) {
                int n = fis.read(buf, total, buf.length - total);
                if (n == -1) break;
                total += n;
            }
            JSONObject parsed = (JSONObject) new JSONTokener(new String(buf, 0, total, "UTF-8")).nextValue();
            if (parsed.optJSONArray("models") == null) return null;
            return parsed;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Extract and parse the JSON body from a raw HTTP response the internal
     *  handlers write via {@link HttpResponse} (headers + CRLFCRLF + body). */
    private static JSONObject extractJsonBody(byte[] raw) {
        if (raw == null || raw.length == 0) return null;
        try {
            String s = new String(raw, "UTF-8");
            int sep = s.indexOf("\r\n\r\n");
            String body = (sep >= 0) ? s.substring(sep + 4) : s;
            body = body.trim();
            if (body.isEmpty()) return null;
            Object v = new JSONTokener(body).nextValue();
            return (v instanceof JSONObject) ? (JSONObject) v : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
