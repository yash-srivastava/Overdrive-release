package com.overdrive.app.charging;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.ChargingDetector;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.DrivingRangeData;
import com.overdrive.app.monitor.SocHistoryDatabase;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone handler for /api/charging/* HTTP requests, mirroring
 * {@link com.overdrive.app.trips.TripApiHandler}. Returns JSONObject responses;
 * error responses carry an {@code _status} field the HttpServer unwraps.
 *
 * <p>Data comes from {@link SocHistoryDatabase} (daemon-process, same JVM as the
 * HTTP server — no IPC). The SoC-over-time series reuses {@code getSocHistory},
 * the same data the existing {@code /api/performance/soc} exposes (no duplicate
 * query); only the v2 session list / per-session samples / lifetime rollups /
 * config are genuinely new here.
 */
public class ChargingApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ChargingApiHandler");

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^/api/charging/(\\d+)$");
    private static final Pattern SESSION_SAMPLES_PATTERN = Pattern.compile("^/api/charging/(\\d+)/samples$");
    // POST fallback for per-session delete (the in-app WebView can drop DELETE).
    private static final Pattern SESSION_DELETE_PATTERN = Pattern.compile("^/api/charging/(\\d+)/delete$");

    private final ChargingSessionManager manager;

    public ChargingApiHandler(ChargingSessionManager manager) {
        this.manager = manager;
    }

    public JSONObject handleRequest(String uri, String method, Map<String, String> params, String body) {
        try {
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf("?")) : uri;
            if (params == null) params = new HashMap<>();
            if (uri.contains("?")) parseQueryParams(uri.substring(uri.indexOf("?") + 1), params);

            // Composite first-paint payload (must precede the more general routes).
            if (path.equals("/api/charging/bootstrap") && "GET".equals(method)) {
                return handleGetBootstrap(params);
            }

            if (path.equals("/api/charging/summary") && "GET".equals(method)) {
                return handleGetSummary(params);
            }

            if (path.equals("/api/charging/soc") && "GET".equals(method)) {
                return handleGetSoc(params);
            }

            if (path.equals("/api/charging/config")) {
                if ("GET".equals(method)) return handleGetConfig();
                if ("POST".equals(method)) return handlePostConfig(body);
            }

            // Location-aware tariffs. POST doubles as create/update (an "id" in
            // the body means update) so the WebView never needs PUT, which it
            // drops on some head-unit builds — same reason /delete exists below.
            if (path.equals("/api/charging/tariffs")) {
                if ("GET".equals(method)) return handleGetTariffs();
                if ("POST".equals(method)) return handlePostTariff(body);
                if ("PUT".equals(method)) return handlePostTariff(body);
                if ("DELETE".equals(method)) return handleDeleteTariff(body);
            }
            // POST fallback for delete (the in-app WebView can drop DELETE bodies).
            if (path.equals("/api/charging/tariffs/delete") && "POST".equals(method)) {
                return handleDeleteTariff(body);
            }
            // Pin the fallback tariff used when a charge location matches nothing.
            if (path.equals("/api/charging/tariffs/default") && "POST".equals(method)) {
                return handleSetDefaultTariff(body);
            }

            if (path.equals("/api/charging/history") && "DELETE".equals(method)) {
                return handleClearHistory();
            }
            // Some WebViews drop DELETE bodies / methods; allow POST .../history/clear too.
            if (path.equals("/api/charging/history/clear") && "POST".equals(method)) {
                return handleClearHistory();
            }

            // GET /api/charging/{id}/samples
            Matcher samplesMatcher = SESSION_SAMPLES_PATTERN.matcher(path);
            if (samplesMatcher.matches() && "GET".equals(method)) {
                long id = Long.parseLong(samplesMatcher.group(1));
                return handleGetSamples(id);
            }

            // POST /api/charging/{id}/delete — DELETE fallback for the WebView.
            Matcher delMatcher = SESSION_DELETE_PATTERN.matcher(path);
            if (delMatcher.matches() && "POST".equals(method)) {
                long id = Long.parseLong(delMatcher.group(1));
                return handleDeleteSession(id);
            }

            // GET/DELETE /api/charging/{id}
            Matcher idMatcher = SESSION_ID_PATTERN.matcher(path);
            if (idMatcher.matches()) {
                long id = Long.parseLong(idMatcher.group(1));
                if ("GET".equals(method)) return handleGetSession(id);
                if ("DELETE".equals(method)) return handleDeleteSession(id);
            }

            // GET /api/charging (list)
            if ((path.equals("/api/charging") || path.equals("/api/charging/")) && "GET".equals(method)) {
                return handleListSessions(params);
            }

            return errorResponse("Not found", 404);
        } catch (Exception e) {
            logger.error("Error handling request: " + uri, e);
            return errorResponse("Internal error: " + e.getMessage(), 500);
        }
    }

    // ==================== ENDPOINT HANDLERS ====================

    private JSONObject handleGetBootstrap(Map<String, String> params) {
        JSONObject bootstrap = new JSONObject();
        JSONObject response = new JSONObject();
        try {
            bootstrap.put("config", invokeSectionStripped(this::handleGetConfig));

            Map<String, String> summaryParams = new HashMap<>();
            summaryParams.put("days", "30");
            bootstrap.put("summary", invokeSectionStripped(() -> handleGetSummary(summaryParams)));

            Map<String, String> socParams = new HashMap<>();
            socParams.put("hours", "72");
            socParams.put("points", "300");
            bootstrap.put("soc", invokeSectionStripped(() -> handleGetSoc(socParams)));

            Map<String, String> sessionsParams = new HashMap<>();
            sessionsParams.put("days", "30");
            sessionsParams.put("limit", "20");
            sessionsParams.put("offset", "0");
            bootstrap.put("sessions", invokeSectionStripped(() -> handleListSessions(sessionsParams)));

            // Tariffs ride first paint so the settings tab renders its list
            // without a second round-trip (the page is often opened straight
            // to Settings after a charge).
            bootstrap.put("tariffs", invokeSectionStripped(this::handleGetTariffs));

            response.put("success", true);
            response.put("bootstrap", bootstrap);
        } catch (Exception e) {
            logger.error("Error building charging bootstrap", e);
            try {
                if (!response.has("success")) response.put("success", false);
                if (!response.has("bootstrap")) response.put("bootstrap", bootstrap);
                response.put("error", e.getMessage() != null ? e.getMessage() : "bootstrap failed");
            } catch (Exception ignored) {}
        }
        return response;
    }

    private JSONObject invokeSectionStripped(java.util.function.Supplier<JSONObject> handler) {
        JSONObject section;
        try {
            section = handler.get();
        } catch (Exception e) {
            logger.warn("Bootstrap section failed: " + e.getMessage());
            JSONObject err = new JSONObject();
            try { err.put("error", e.getMessage() != null ? e.getMessage() : "section failed"); } catch (Exception ignored) {}
            return err;
        }
        if (section == null) {
            JSONObject err = new JSONObject();
            try { err.put("error", "empty section"); } catch (Exception ignored) {}
            return err;
        }
        section.remove("success");
        section.remove("_status");
        return section;
    }

    /**
     * GET /api/charging — paginated session list.
     * Query: limit(20), offset(0), and EITHER days(30) OR a custom range via
     * from/to (epoch-ms). Charging history is permanent, so from/to can span
     * well beyond the 90-day quick filters.
     */
    private JSONObject handleListSessions(Map<String, String> params) {
        JSONObject response = new JSONObject();
        try {
            int limit = getIntParam(params, "limit", 20);
            int offset = getIntParam(params, "offset", 0);
            if (limit < 1) limit = 1;
            if (limit > 200) limit = 200;
            if (offset < 0) offset = 0;
            JSONArray sessions;
            long from = getLongParam(params, "from", -1);
            long to = getLongParam(params, "to", -1);
            if (from >= 0 || to >= 0) {
                // Custom date range. Defaults: from=0 (epoch), to=now+1day slack.
                long fromMs = from >= 0 ? from : 0;
                long toMs = to >= 0 ? to : Long.MAX_VALUE;
                sessions = db().getChargingSessionsV2Range(fromMs, toMs, limit, offset);
            } else {
                int days = getIntParam(params, "days", 30);
                // days<=0 = ALL TIME (epoch→now), else the rolling window.
                if (days <= 0) sessions = db().getChargingSessionsV2Range(0, Long.MAX_VALUE, limit, offset);
                else sessions = db().getChargingSessionsV2(days, limit, offset);
            }
            response.put("success", true);
            response.put("sessions", sessions);
        } catch (Exception e) {
            logger.error("Error listing charging sessions", e);
            return errorResponse("Failed to list sessions", 500);
        }
        return response;
    }

    /** GET /api/charging/{id} — single session. */
    private JSONObject handleGetSession(long id) {
        try {
            JSONObject session = db().getChargingSessionById(id);
            if (session == null) return errorResponse("Session not found", 404);
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("session", session);
            return response;
        } catch (Exception e) {
            logger.error("Error getting charging session " + id, e);
            return errorResponse("Failed to get session", 500);
        }
    }

    /** DELETE /api/charging/{id} (or POST /api/charging/{id}/delete) — remove one session. */
    private JSONObject handleDeleteSession(long id) {
        try {
            boolean ok = db().deleteChargingSession(id);
            if (!ok) return errorResponse("Failed to delete session", 500);
            JSONObject response = new JSONObject();
            response.put("success", true);
            return response;
        } catch (Exception e) {
            logger.error("Error deleting charging session " + id, e);
            return errorResponse("Failed to delete session", 500);
        }
    }

    /** GET /api/charging/{id}/samples — per-session ramp curve. */
    private JSONObject handleGetSamples(long id) {
        try {
            JSONArray samples = db().getChargingSamples(id);
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("samples", samples);
            return response;
        } catch (Exception e) {
            logger.error("Error getting samples for session " + id, e);
            return errorResponse("Failed to get samples", 500);
        }
    }

    /** GET /api/charging/soc — SoC-over-time series (reuses getSocHistory). */
    private JSONObject handleGetSoc(Map<String, String> params) {
        try {
            int hours = getIntParam(params, "hours", 72);
            int points = getIntParam(params, "points", 300);
            // soc_history retains 30 days; cap the window so a stale client param
            // can't request beyond what exists.
            if (hours > 24 * 30) hours = 24 * 30;
            if (points < 10) points = 10;
            if (points > 1000) points = 1000;
            JSONArray soc = db().getSocHistory(hours, points);
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("soc", soc);
            return response;
        } catch (Exception e) {
            logger.error("Error getting soc history", e);
            return errorResponse("Failed to get SoC history", 500);
        }
    }

    /**
     * GET /api/charging/summary — period + lifetime rollups + SOH trend + live
     * state. Period honors days(30) OR a custom from/to range (epoch-ms).
     */
    private JSONObject handleGetSummary(Map<String, String> params) {
        JSONObject response = new JSONObject();
        try {
            long from = getLongParam(params, "from", -1);
            long to = getLongParam(params, "to", -1);
            JSONObject summary;
            if (from >= 0 || to >= 0) {
                long fromMs = from >= 0 ? from : 0;
                long toMs = to >= 0 ? to : Long.MAX_VALUE;
                summary = db().getChargingSummaryRange(fromMs, toMs);
            } else {
                int days = getIntParam(params, "days", 30);
                if (days <= 0) summary = db().getChargingSummaryRange(0, Long.MAX_VALUE);
                else summary = db().getChargingSummary(days);
            }
            summary.put("live", buildLiveBlock());
            response.put("success", true);
            response.put("summary", summary);
        } catch (Exception e) {
            logger.error("Error building charging summary", e);
            return errorResponse("Failed to build summary", 500);
        }
        return response;
    }

    /**
     * Live charging state for the dashboard card / hero. Sourced from
     * ChargingDetector (fused truth) + VehicleDataMonitor (power/soc/range) +
     * the open charging_sessions row.
     */
    JSONObject buildLiveBlock() {
        JSONObject live = new JSONObject();
        try {
            boolean charging = false, plugged = false, full = false, fault = false;
            boolean isEstimated = false;
            double powerKw = 0, socPct = -1, rangeKm = -1, sohPct = -1;
            try {
                charging = ChargingDetector.getInstance().isCharging();
            } catch (Exception ignored) {}

            VehicleDataMonitor vm = VehicleDataMonitor.getInstance();
            if (vm != null) {
                try {
                    ChargingStateData cs = vm.getChargingState();
                    if (cs != null) {
                        powerKw = cs.chargingPowerKW;
                        isEstimated = cs.isEstimated;
                        full = cs.status == ChargingStateData.ChargingStatus.FINISHED;
                        fault = cs.isError;
                        // READY/CHARGING/FINISHED all imply a connector is present.
                        plugged = full || charging
                                || cs.status == ChargingStateData.ChargingStatus.READY
                                || cs.status == ChargingStateData.ChargingStatus.SCHEDULED;
                    }
                } catch (Exception ignored) {}
                try {
                    BatterySocData soc = vm.getBatterySoc();
                    if (soc != null) socPct = soc.socPercent;
                } catch (Exception ignored) {}
                try {
                    DrivingRangeData range = vm.getDrivingRange();
                    if (range != null && range.isValidRange()) rangeKm = range.elecRangeKm;
                } catch (Exception ignored) {}
            }

            // The Stats hero mirrors the native Dashboard vehicle gauge. Read
            // the same central SohEstimator headline value so every consumer
            // agrees and no capacity arithmetic is duplicated in the web UI.
            try {
                com.overdrive.app.abrp.SohEstimator soh =
                        SocHistoryDatabase.getInstance().getSohEstimator();
                if (soh != null) {
                    double resolved = soh.getDisplaySoh();
                    if (resolved > 0 && resolved <= 100) sohPct = resolved;
                }
            } catch (Throwable ignored) {}

            // Energy added so far in the CURRENT (open) session — the dashboard
            // "Session" + stats "Added this session" metric. Integrates the power
            // samples (∫P·dt), with a SOC-delta fallback, via the DB accessor so
            // it's non-zero from the first minutes of a slow charge (SOC-delta
            // alone reads 0 until SOC ticks a whole percent). -1 when not in a
            // session (UI shows "--").
            double sessionKwh = -1;
            try {
                long openStart = db().getOpenChargingSessionStart();
                if (openStart > 0) {
                    sessionKwh = db().getOpenChargingSessionEnergyKwh();
                }
            } catch (Exception ignored) {}

            live.put("charging", charging);
            live.put("plugged", plugged);
            live.put("full", full);
            live.put("fault", fault);
            live.put("powerKw", powerKw);
            // Mirror the dashboard /status block: tell the frontend when powerKw
            // is a SOC-rate placeholder (not a real BMS reading) so it can
            // suppress the estimated value the same way index.html does.
            live.put("isEstimated", isEstimated);
            live.put("socPercent", socPct >= 0 ? socPct : JSONObject.NULL);
            live.put("rangeKm", rangeKm >= 0 ? rangeKm : JSONObject.NULL);
            live.put("sohPercent", sohPct > 0
                    ? Math.round(sohPct * 10.0) / 10.0
                    : JSONObject.NULL);
            live.put("sessionKwh", sessionKwh > 0 ? sessionKwh : JSONObject.NULL);
            // One live resolver feeds this endpoint and /status, so the dashboard,
            // sidebar and Charging Stats cannot show conflicting countdowns.
            ChargingCompletionEstimate.resolveLive(charging, full).putInto(live);
        } catch (Exception e) {
            logger.debug("buildLiveBlock failed: " + e.getMessage());
        }
        return live;
    }

    private JSONObject handleGetConfig() {
        JSONObject response = new JSONObject();
        try {
            ChargingConfig config = manager.getConfig();
            // Re-read from UnifiedConfigManager before serving so a rate/currency
            // edit made on the Trips page (shared tripAnalytics value) is reflected
            // here without a daemon restart — otherwise we serve the stale value
            // cached at init and the user sees "nothing saved/loaded".
            if (config != null) config.load();
            JSONObject configJson = config != null ? config.toJson() : new JSONObject();
            // Surface nominal pack + drivetrain so the JS can label estimates.
            try {
                com.overdrive.app.abrp.SohEstimator soh =
                        SocHistoryDatabase.getInstance().getSohEstimator();
                if (soh != null && soh.getNominalCapacityKwh() > 0) {
                    configJson.put("nominalKwh", soh.getNominalCapacityKwh());
                }
            } catch (Throwable t) {
                logger.debug("nominalKwh enrichment skipped: " + t.getMessage());
            }
            try {
                configJson.put("isPhev", VehicleDataMonitor.getInstance().isPhev());
            } catch (Throwable t) {
                logger.debug("isPhev probe skipped: " + t.getMessage());
            }
            response.put("success", true);
            response.put("config", configJson);
        } catch (Exception e) {
            logger.error("Error building charging config response", e);
            return errorResponse("Failed to get config", 500);
        }
        return response;
    }

    private JSONObject handlePostConfig(String body) {
        try {
            JSONObject bodyJson = new JSONObject(body != null ? body : "{}");
            // Validate before applying. The setters clamp out-of-range values to 0
            // ("unset"), so without this an absurd or non-numeric rate would be
            // accepted, silently stored as 0, and reported as a success.
            for (String rk : new String[]{ "electricityRate", "dcRate" }) {
                if (!bodyJson.has(rk)) continue;
                double rv = bodyJson.optDouble(rk, Double.NaN);
                if (Double.isNaN(rv) || rv < 0 || rv >= 100000) {
                    return errorResponse(rk + " must be between 0 and 100000", 400);
                }
            }
            ChargingConfig config = manager.getConfig();
            if (config != null) {
                if (bodyJson.has("enabled")) config.setEnabled(bodyJson.getBoolean("enabled"));
                if (bodyJson.has("dcRate")) config.setDcRate(bodyJson.getDouble("dcRate"));
                if (bodyJson.has("fastSampleSec")) config.setFastSampleSec(bodyJson.getInt("fastSampleSec"));
                // Rate/currency are the shared Trips value (read-through). Allow
                // editing here too; ChargingConfig.save() mirrors them back.
                if (bodyJson.has("electricityRate")) config.setElectricityRate(bodyJson.getDouble("electricityRate"));
                if (bodyJson.has("currency")) config.setCurrency(bodyJson.getString("currency"));
                // Report a persistence failure instead of claiming success: the
                // in-memory config would price charges with a rate that is not in
                // the file, and would silently revert on the next daemon start.
                if (!config.save()) {
                    return errorResponse("Could not save charging settings", 500);
                }
                manager.onConfigChanged();
            }
            JSONObject response = new JSONObject();
            response.put("success", true);
            // Deliberately NO re-price here. Sessions priced by the global rate
            // keep the rate snapshotted when they closed — that is the historical
            // record of what was actually paid, and restating it because the user
            // updated their current tariff would be wrong (and is the long-
            // standing behaviour). Only named location tariffs are re-priceable,
            // because there the user is explicitly correcting a labelled rate.
            return response;
        } catch (Exception e) {
            logger.error("Error saving charging config", e);
            return errorResponse("Failed to save config: " + e.getMessage(), 400);
        }
    }

    // ==================== TARIFFS ====================

    /**
     * List tariffs (usage-ordered) plus the current GPS fix and which profile
     * matches it, so the UI can flag "auto-applies here" without a second call.
     */
    private JSONObject handleGetTariffs() {
        JSONObject response = new JSONObject();
        try {
            double[] loc = currentLocation();
            JSONObject payload = TariffManager.getInstance().toStatusJson(loc[0], loc[1]);
            // Global fallbacks, so the UI can render "otherwise X/kWh" without
            // also fetching /config.
            ChargingConfig cfg = manager.getConfig();
            if (cfg != null) {
                cfg.load();
                payload.put("globalRate", cfg.getElectricityRate());
                payload.put("globalDcRate", cfg.getDcRate());
                payload.put("currency", cfg.getCurrency());
            }
            response.put("success", true);
            response.put("tariffs", payload.optJSONArray("tariffs"));
            response.put("meta", payload);
        } catch (Exception e) {
            logger.error("Error listing tariffs", e);
            return errorResponse("Failed to list tariffs", 500);
        }
        return response;
    }

    /**
     * Create or update a tariff. An {@code id} in the body updates that profile;
     * absent, a new one is created. When {@code lat}/{@code lng} are omitted on
     * create we snapshot the CURRENT position — that's the "save the rate for
     * where I am right now" flow, which is the common case.
     *
     * <p>A rate change re-prices the sessions this tariff owns (and adopts any
     * in-range sessions it should now own) so history and the period/lifetime
     * totals stay consistent instead of silently keeping the old price.
     */
    private JSONObject handlePostTariff(String body) {
        try {
            JSONObject b = new JSONObject(body != null ? body : "{}");
            TariffManager mgr = TariffManager.getInstance();
            String id = b.optString("id", "");

            // Validate BEFORE mutating. TariffProfile clamps silently (it has to —
            // it also parses a user-writable config file), so without an explicit
            // check here a nonsense rate would be accepted as 0 and the tariff
            // would match charges and then price them at nothing. Rejecting with a
            // reason lets the UI say what's wrong instead of appearing to succeed.
            String invalid = validateTariffBody(b, id.isEmpty());
            if (invalid != null) return errorResponse(invalid, 400);

            if (!id.isEmpty()) {
                if (mgr.findById(id) == null) return errorResponse("Tariff not found", 404);
                if (!mgr.update(id, b)) return errorResponse("Could not save tariff", 500);
                int repriced = repriceQuietly(id);
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("tariff", tariffJson(mgr, id));
                response.put("repriced", repriced);
                return response;
            }

            double lat = b.optDouble("lat", Double.NaN);
            double lng = b.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) {
                double[] loc = currentLocation();
                lat = loc[0];
                lng = loc[1];
            }
            if (lat == 0 && lng == 0) {
                // Without a position the profile could never match a charge, so
                // refuse rather than silently creating a dead entry at (0,0).
                return errorResponse("No location available — wait for a GPS fix or pass lat/lng", 400);
            }

            ChargingConfig cfg = manager.getConfig();
            String fallbackCurrency = cfg != null ? cfg.getCurrency() : "";
            // Pre-check the cap so add()'s null can be attributed to the real cause.
            // Reporting "Tariff limit reached" for a persistence failure told the
            // user to delete tariffs to fix a disk/lock problem.
            if (mgr.getProfiles().size() >= TariffManager.MAX_PROFILES) {
                return errorResponse("Tariff limit reached", 400);
            }
            TariffProfile p = mgr.add(
                    b.optString("label", ""),
                    lat, lng,
                    b.optInt("radiusM", TariffProfile.DEFAULT_RADIUS_M),
                    b.optDouble("acRate", 0),
                    b.optDouble("dcRate", 0),
                    b.optString("currency", fallbackCurrency));
            if (p == null) return errorResponse("Could not save tariff", 500);

            // A brand-new tariff can retroactively own past charges at this place.
            int repriced = repriceQuietly(p.getId());
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("tariff", p.toJson());
            response.put("repriced", repriced);
            return response;
        } catch (Exception e) {
            logger.error("Error saving tariff", e);
            return errorResponse("Failed to save tariff: " + e.getMessage(), 400);
        }
    }

    private JSONObject handleDeleteTariff(String body) {
        try {
            JSONObject b = new JSONObject(body != null ? body : "{}");
            String id = b.optString("id", "");
            if (id.isEmpty()) return errorResponse("Missing tariff id", 400);
            TariffManager mgr = TariffManager.getInstance();
            if (mgr.findById(id) == null) return errorResponse("Tariff not found", 404);
            if (!mgr.remove(id)) return errorResponse("Could not save tariff", 500);
            // Sessions this tariff priced must fall back to whatever now applies —
            // another profile, or the global rate. Pass "" to re-evaluate every
            // session, since the deleted id is gone from the list and can no
            // longer be matched on. (Sessions that were already on the global
            // rate are skipped inside repriceSessionsForTariff — their historical
            // cost is never restated.)
            int repriced = repriceQuietly("");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("repriced", repriced);
            return response;
        } catch (Exception e) {
            logger.error("Error deleting tariff", e);
            return errorResponse("Failed to delete tariff", 400);
        }
    }

    private JSONObject handleSetDefaultTariff(String body) {
        try {
            JSONObject b = new JSONObject(body != null ? body : "{}");
            String id = b.optString("id", "");
            TariffManager mgr = TariffManager.getInstance();
            if (!id.isEmpty() && mgr.findById(id) == null) {
                return errorResponse("Tariff not found", 404);
            }
            if (!mgr.setDefault(id)) return errorResponse("Could not save tariff", 500);
            // The default prices any charge that matched no circle, so changing it
            // changes which tariff owns those sessions. Re-evaluate all; sessions
            // that are on the global rate before AND after are left untouched.
            int repriced = repriceQuietly("");
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("defaultTariffId", TariffManager.getInstance().getDefaultId());
            response.put("repriced", repriced);
            return response;
        } catch (Exception e) {
            logger.error("Error setting default tariff", e);
            return errorResponse("Failed to set default tariff", 400);
        }
    }

    /**
     * Validate a tariff create/update body. Returns null when acceptable, else a
     * user-facing reason.
     *
     * <p>On UPDATE only the keys actually present are checked, so a partial edit
     * (say, renaming a label) isn't rejected for omitting rates. On CREATE at
     * least one usable rate is required — a rate-less tariff would match charges
     * and price them at zero, which reads as a bug rather than a setting.
     */
    private String validateTariffBody(JSONObject b, boolean isCreate) {
        double ac = b.has("acRate") ? b.optDouble("acRate", -1) : -1;
        double dc = b.has("dcRate") ? b.optDouble("dcRate", -1) : -1;

        if (b.has("acRate") && (Double.isNaN(ac) || ac < 0 || ac >= 100000)) {
            return "AC rate must be between 0 and 100000";
        }
        if (b.has("dcRate") && (Double.isNaN(dc) || dc < 0 || dc >= 100000)) {
            return "DC rate must be between 0 and 100000";
        }
        if (isCreate && !(ac > 0) && !(dc > 0)) {
            return "Enter an AC or DC rate";
        }
        // On update, evaluate the MERGED result: a body that zeroes only acRate
        // still leaves a rate-less tariff when the stored dcRate is already 0.
        if (!isCreate && (b.has("acRate") || b.has("dcRate"))) {
            TariffProfile cur = TariffManager.getInstance().findById(b.optString("id", ""));
            double mergedAc = b.has("acRate") ? ac : (cur != null ? cur.getAcRate() : 0);
            double mergedDc = b.has("dcRate") ? dc : (cur != null ? cur.getDcRate() : 0);
            if (!(mergedAc > 0) && !(mergedDc > 0)) return "Enter an AC or DC rate";
        }

        if (b.has("radiusM")) {
            int r = b.optInt("radiusM", -1);
            if (r < TariffProfile.MIN_RADIUS_M || r > TariffProfile.MAX_RADIUS_M) {
                return "Radius must be between " + TariffProfile.MIN_RADIUS_M
                        + " and " + TariffProfile.MAX_RADIUS_M + " m";
            }
        }
        // Validate each coordinate that was SUPPLIED. optDouble yields NaN for a
        // non-numeric value, and TariffProfile clamps NaN to 0 — which is the "no
        // location" sentinel — so without an explicit reject a garbage coordinate
        // would be accepted and produce a tariff that can never match.
        if (b.has("lat")) {
            double lat = b.optDouble("lat", Double.NaN);
            if (Double.isNaN(lat) || lat < -90 || lat > 90) return "Latitude out of range";
        }
        if (b.has("lng")) {
            double lng = b.optDouble("lng", Double.NaN);
            if (Double.isNaN(lng) || lng < -180 || lng > 180) return "Longitude out of range";
        }
        if (b.has("label") && b.optString("label", "").length() > 48) {
            return "Label is too long";
        }
        return null;
    }

    private JSONObject tariffJson(TariffManager mgr, String id) {
        TariffProfile p = mgr.findById(id);
        return p != null ? p.toJson() : new JSONObject();
    }

    /** Re-price history for a tariff change; never let it fail the request. */
    private int repriceQuietly(String tariffId) {
        try {
            SocHistoryDatabase db = db();
            if (db != null) return db.repriceSessionsForTariff(tariffId);
        } catch (Throwable t) {
            logger.warn("Reprice skipped: " + t.getMessage());
        }
        return 0;
    }

    /** Current GPS fix as {lat, lng}, or {0,0} when there's none. */
    private double[] currentLocation() {
        try {
            com.overdrive.app.monitor.GpsMonitor gps =
                    com.overdrive.app.monitor.GpsMonitor.getInstance();
            if (gps != null && gps.hasLocation()) {
                return new double[]{ gps.getLatitude(), gps.getLongitude() };
            }
        } catch (Throwable ignored) {}
        return new double[]{ 0, 0 };
    }

    private JSONObject handleClearHistory() {
        try {
            long removed = db().clearChargingHistory();
            JSONObject response = new JSONObject();
            response.put("success", removed >= 0);
            response.put("removed", removed);
            return response;
        } catch (Exception e) {
            logger.error("Error clearing charging history", e);
            return errorResponse("Failed to clear history", 500);
        }
    }

    // ==================== HELPERS ====================

    private SocHistoryDatabase db() {
        return manager.getSocDb();
    }

    private void parseQueryParams(String queryString, Map<String, String> params) {
        if (queryString == null || queryString.isEmpty()) return;
        String[] pairs = queryString.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String value = eq < pair.length() - 1 ? pair.substring(eq + 1) : "";
                params.put(key, value);
            }
        }
    }

    private int getIntParam(Map<String, String> params, String key, int defaultValue) {
        String value = params.get(key);
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long getLongParam(Map<String, String> params, String key, long defaultValue) {
        String value = params.get(key);
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private JSONObject errorResponse(String message, int status) {
        JSONObject response = new JSONObject();
        try {
            response.put("success", false);
            response.put("error", message);
            response.put("_status", status);
        } catch (Exception e) {
            logger.error("Error building error response", e);
        }
        return response;
    }
}
