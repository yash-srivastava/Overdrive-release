package com.overdrive.app.charging;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.ChargingDetector;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.DrivingRangeData;
import com.overdrive.app.monitor.SocHistoryDatabase;
import com.overdrive.app.monitor.VehicleDataMonitor;
import com.overdrive.app.trips.TripAnalyticsManager;
import com.overdrive.app.trips.TripConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
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
    private static final Pattern SESSION_COST_PATTERN = Pattern.compile("^/api/charging/(\\d+)/cost$");

    private final ChargingSessionManager manager;
    private final Supplier<TripAnalyticsManager>
            tripAnalyticsManagerSupplier;

    public ChargingApiHandler(ChargingSessionManager manager) {
        this(manager, (Supplier<TripAnalyticsManager>) null);
    }

    public ChargingApiHandler(
            ChargingSessionManager manager,
            TripAnalyticsManager tripAnalyticsManager) {
        this(manager, () -> tripAnalyticsManager);
    }

    public ChargingApiHandler(
            ChargingSessionManager manager,
            Supplier<TripAnalyticsManager>
                    tripAnalyticsManagerSupplier) {
        this.manager = manager;
        this.tripAnalyticsManagerSupplier =
                tripAnalyticsManagerSupplier;
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

            if (path.equals("/api/charging/overview") && "GET".equals(method)) {
                return handleGetOverview(params);
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

            // POST /api/charging/{id}/cost — update a completed session's cost.
            Matcher costMatcher = SESSION_COST_PATTERN.matcher(path);
            if (costMatcher.matches() && "POST".equals(method)) {
                long id = Long.parseLong(costMatcher.group(1));
                return handleUpdateSessionCost(id, body);
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
            // Config and tariffs share pricing fields. Capture one durable root
            // so first paint cannot combine two config revisions.
            JSONObject configRoot =
                    TariffManager.loadVerifiedConfig();
            bootstrap.put("config", invokeSectionStripped(
                    () -> handleGetConfigFromRoot(configRoot)));

            Map<String, String> summaryParams =
                    bootstrapPeriodParams(params);
            bootstrap.put("summary", invokeSectionStripped(() -> handleGetSummary(summaryParams)));

            Map<String, String> socParams = new HashMap<>();
            socParams.put("hours",
                    bootstrapParam(params, "hours", "168"));
            socParams.put("points",
                    bootstrapParam(params, "points", "300"));
            bootstrap.put("soc", invokeSectionStripped(() -> handleGetSoc(socParams)));

            Map<String, String> sessionsParams =
                    bootstrapPeriodParams(params);
            sessionsParams.put("limit",
                    bootstrapParam(params, "limit", "20"));
            sessionsParams.put("offset", "0");
            bootstrap.put("sessions", invokeSectionStripped(() -> handleListSessions(sessionsParams)));

            // Tariffs ride first paint so the settings tab renders its list
            // without a second round-trip (the page is often opened straight
            // to Settings after a charge).
            bootstrap.put("tariffs", invokeSectionStripped(
                    () -> handleGetTariffsFromRoot(configRoot)));

            // The sections above are intentionally independent and can take long enough for a
            // physical stop to land between the summary and session reads. Make one final fenced
            // publication authoritative for every live field in this composite response.
            LivePublication finalPublication = readLivePublication(db());
            overwriteBootstrapLiveState(
                    bootstrap, finalPublication.toLiveJson());

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

    /**
     * One refresh payload for the independently stored summary and session list.
     * A final live read fences both sections before either reaches the client.
     */
    private JSONObject handleGetOverview(Map<String, String> params) {
        try {
            Map<String, String> summaryParams =
                    bootstrapPeriodParams(params);
            JSONObject summaryResponse = handleGetSummary(summaryParams);
            if (!summaryResponse.optBoolean("success", false)) {
                return errorResponse("Failed to build charging overview", 500);
            }

            Map<String, String> sessionsParams =
                    bootstrapPeriodParams(params);
            sessionsParams.put("limit",
                    bootstrapParam(params, "limit", "20"));
            sessionsParams.put("offset", "0");
            JSONObject sessionsResponse =
                    handleListSessions(sessionsParams);
            if (!sessionsResponse.optBoolean("success", false)) {
                return errorResponse("Failed to build charging overview", 500);
            }

            JSONObject summary =
                    summaryResponse.getJSONObject("summary");
            JSONArray sessions =
                    sessionsResponse.getJSONArray("sessions");
            LivePublication finalPublication =
                    readLivePublication(db());
            JSONObject finalLive = finalPublication.toLiveJson();
            summary.put("live", finalLive);
            overwriteSessionLiveState(sessions, finalLive);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("summary", summary);
            response.put("sessions", sessions);
            return response;
        } catch (Exception e) {
            logger.error("Error building charging overview", e);
            return errorResponse("Failed to build charging overview", 500);
        }
    }

    static void overwriteBootstrapLiveState(
            JSONObject bootstrap, JSONObject finalLive) throws Exception {
        if (bootstrap == null || finalLive == null) return;

        JSONObject summarySection = bootstrap.optJSONObject("summary");
        JSONObject summary = summarySection != null
                ? summarySection.optJSONObject("summary") : null;
        if (summary != null) {
            summary.put("live", finalLive);
        }

        JSONObject sessionsSection = bootstrap.optJSONObject("sessions");
        JSONArray sessions = sessionsSection != null
                ? sessionsSection.optJSONArray("sessions") : null;
        overwriteSessionLiveState(sessions, finalLive);
    }

    static void overwriteSessionLiveState(
            JSONArray sessions, JSONObject finalLive) throws Exception {
        if (sessions == null || finalLive == null) return;
        boolean charging = finalLive.optBoolean("charging", false);
        int liveIndex = -1;
        if (charging) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.optJSONObject(i);
                if (session != null
                        && session.optBoolean("inProgress", false)
                        && session.optBoolean("chargingNow", false)) {
                    liveIndex = i;
                    break;
                }
            }
            if (liveIndex < 0) {
                for (int i = 0; i < sessions.length(); i++) {
                    JSONObject session = sessions.optJSONObject(i);
                    if (session != null
                            && session.optBoolean("inProgress", false)) {
                        liveIndex = i;
                        break;
                    }
                }
            }
        }

        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            if (session == null) continue;
            boolean inProgress =
                    session.optBoolean("inProgress", false);
            boolean chargingNow = inProgress && i == liveIndex;
            session.put("chargingNow", chargingNow);
            if (!inProgress) continue;
            if (!chargingNow) {
                session.put("livePowerKw", JSONObject.NULL);
                session.put("timeToFullMin", JSONObject.NULL);
                session.put("isEstimated", false);
                continue;
            }
            double powerKw = finalLive.optDouble("powerKw", 0.0);
            int timeToFullMin =
                    finalLive.optInt("timeToFullMin", -1);
            session.put("livePowerKw",
                    powerKw > 0 ? powerKw : JSONObject.NULL);
            session.put("timeToFullMin",
                    timeToFullMin > 0
                            ? timeToFullMin : JSONObject.NULL);
            session.put("isEstimated",
                    finalLive.optBoolean("isEstimated", false));
        }
    }

    static Map<String, String> bootstrapPeriodParams(
            Map<String, String> params) {
        Map<String, String> period = new HashMap<>();
        if (params != null
                && (params.containsKey("from")
                || params.containsKey("to"))) {
            if (params.containsKey("from")) {
                period.put("from", params.get("from"));
            }
            if (params.containsKey("to")) {
                period.put("to", params.get("to"));
            }
        } else {
            period.put("days",
                    bootstrapParam(params, "days", "7"));
        }
        return period;
    }

    static String bootstrapParam(
            Map<String, String> params,
            String key,
            String fallback) {
        if (params == null) return fallback;
        String value = params.get(key);
        return value != null && !value.isEmpty()
                ? value : fallback;
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
                sessions = db().getChargingSessionsV2RangeStrict(
                        fromMs, toMs, limit, offset);
            } else {
                int days = getIntParam(params, "days", 30);
                // days<=0 = ALL TIME (epoch→now), else the rolling window.
                if (days <= 0) {
                    sessions = db().getChargingSessionsV2RangeStrict(
                            0, Long.MAX_VALUE, limit, offset);
                } else {
                    sessions = db().getChargingSessionsV2Strict(
                            days, limit, offset);
                }
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
            JSONObject session = db().getChargingSessionByIdStrict(id);
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

    /** POST /api/charging/{id}/cost — update or reset a completed session's cost. */
    private JSONObject handleUpdateSessionCost(long id, String body) {
        try {
            JSONObject bodyJson;
            try {
                bodyJson = new JSONObject(body != null ? body : "{}");
            } catch (org.json.JSONException e) {
                return errorResponse("Invalid JSON payload", 400);
            }
            if (!bodyJson.has("cost")) {
                return errorResponse("Missing 'cost' parameter", 400);
            }

            Object rawCost = bodyJson.get("cost");
            if (!(rawCost instanceof Number)) {
                return errorResponse("'cost' must be a number", 400);
            }
            double cost = ((Number) rawCost).doubleValue();
            if (!Double.isFinite(cost)
                    || !Float.isFinite((float) cost)
                    || (cost < 0 && cost != -1)) {
                return errorResponse(
                        "'cost' must be finite and non-negative, or -1 to reset",
                        400);
            }

            if (!db().updateChargingSessionCost(id, cost)) {
                return errorResponse(
                        "Failed to update session cost (session may be in progress or not found)",
                        500);
            }
            JSONObject response = new JSONObject();
            response.put("success", true);
            return response;
        } catch (Exception e) {
            logger.error("Error updating cost for session " + id, e);
            return errorResponse("Failed to update cost", 500);
        }
    }

    /** GET /api/charging/{id}/samples — per-session ramp curve. */
    private JSONObject handleGetSamples(long id) {
        try {
            JSONArray samples = db().getChargingSamplesStrict(id);
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
            JSONArray soc = db().getSocHistoryStrict(hours, points);
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
                summary = db().getChargingSummaryRangeStrict(
                        fromMs, toMs);
            } else {
                int days = getIntParam(params, "days", 30);
                if (days <= 0) {
                    summary = db().getChargingSummaryRangeStrict(
                            0, Long.MAX_VALUE);
                } else {
                    summary = db().getChargingSummaryStrict(days);
                }
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
        return readLivePublication(db()).toLiveJson();
    }

    /**
     * One generation-consistent charging view shared by both HTTP surfaces. Reads are retried once
     * when a collector/detector/taper mutation overlaps the compound snapshot.
     */
    public static final class LivePublication {
        public final ChargingStateData state;
        public final boolean charging;
        public final boolean plugged;
        public final boolean full;
        public final boolean fault;
        public final double socPercent;
        public final double sessionKwh;
        public final boolean sessionEnergyIncomplete;
        public final boolean sessionEnergyEstimated;
        public final String sessionEnergySource;
        public final int timeToFullMin;
        public final PowerPublication power;
        public final double rangeKm;
        public final double sohPercent;
        private final ChargingDetector.StateSnapshot verifiedAt;

        private LivePublication(
                ChargingStateData state,
                boolean charging, boolean plugged, boolean full, boolean fault,
                double socPercent, double sessionKwh,
                boolean sessionEnergyIncomplete,
                boolean sessionEnergyEstimated,
                String sessionEnergySource,
                int timeToFullMin,
                PowerPublication power,
                ChargingDetector.StateSnapshot verifiedAt) {
            this(state, charging, plugged, full, fault,
                    socPercent, sessionKwh,
                    sessionEnergyIncomplete, sessionEnergyEstimated,
                    sessionEnergySource, timeToFullMin, power,
                    -1.0, -1.0, verifiedAt);
        }

        private LivePublication(
                ChargingStateData state,
                boolean charging, boolean plugged, boolean full, boolean fault,
                double socPercent, double sessionKwh,
                boolean sessionEnergyIncomplete,
                boolean sessionEnergyEstimated,
                String sessionEnergySource,
                int timeToFullMin,
                PowerPublication power,
                double rangeKm,
                double sohPercent,
                ChargingDetector.StateSnapshot verifiedAt) {
            this.state = state;
            this.charging = charging;
            this.plugged = plugged;
            this.full = full;
            this.fault = fault;
            this.socPercent = socPercent;
            this.sessionKwh = charging ? sessionKwh : -1.0;
            boolean hasSessionEnergy = charging && sessionKwh > 0;
            this.sessionEnergyIncomplete =
                    hasSessionEnergy && sessionEnergyIncomplete;
            this.sessionEnergyEstimated =
                    hasSessionEnergy
                            && (sessionEnergyEstimated
                            || sessionEnergyIncomplete);
            this.sessionEnergySource = hasSessionEnergy
                    && sessionEnergySource != null
                    && !sessionEnergySource.isEmpty()
                    ? sessionEnergySource
                    : SessionEnergyResolver.SRC_NONE;
            this.timeToFullMin = charging ? timeToFullMin : -1;
            this.power = power;
            this.rangeKm = rangeKm;
            this.sohPercent = sohPercent;
            this.verifiedAt = verifiedAt;
        }

        private static LivePublication cleared(
                ChargingDetector.StateSnapshot verifiedAt) {
            return new LivePublication(
                    null, false, false, false, false,
                    -1.0, -1.0,
                    false, false, SessionEnergyResolver.SRC_NONE,
                    -1,
                    normalizePowerPublication(false, null), verifiedAt);
        }

        public boolean hasPositivePresentation() {
            return charging || plugged || full || power.powerKw > 0
                    || sessionKwh > 0 || timeToFullMin > 0;
        }

        /** Revalidate immediately before a delayed response is serialized. */
        public boolean isStillCurrent() {
            if (!hasPositivePresentation()) return true;
            ChargingDetector.StateSnapshot current = null;
            try {
                current = ChargingDetector.getInstance().getStateSnapshot();
            } catch (Exception ignored) {}
            return ChargingDetector.isPublicationWindowStable(
                    verifiedAt, current);
        }

        public JSONObject toLiveJson() {
            JSONObject live = new JSONObject();
            try {
                live.put("charging", charging);
                live.put("plugged", plugged);
                live.put("full", full);
                live.put("fault", fault);
                putPower(live, power, false);
                live.put("socPercent",
                        socPercent >= 0 ? socPercent : JSONObject.NULL);
                live.put("sessionKwh",
                        sessionKwh > 0 ? sessionKwh : JSONObject.NULL);
                live.put("sessionEnergyIncomplete",
                        sessionEnergyIncomplete);
                live.put("sessionEnergyEstimated",
                        sessionEnergyEstimated);
                live.put("sessionEnergySource",
                        sessionKwh > 0
                                ? sessionEnergySource
                                : JSONObject.NULL);
                live.put("timeToFullMin",
                        timeToFullMin > 0 ? timeToFullMin : JSONObject.NULL);
                live.put("rangeKm",
                        rangeKm >= 0 ? rangeKm : JSONObject.NULL);
                live.put("sohPercent",
                        sohPercent > 0 ? sohPercent : JSONObject.NULL);
            } catch (Exception e) {
                logger.debug("Could not serialize live charging state: "
                        + e.getMessage());
            }
            return live;
        }

        public JSONObject toStatusJson() {
            JSONObject status = new JSONObject();
            try {
                status.put("stateName",
                        state != null ? state.stateName : "Unavailable");
                status.put("status",
                        state != null
                                ? state.status.name()
                                : ChargingStateData.ChargingStatus.UNKNOWN.name());
                status.put("isDischarging",
                        state != null && state.isDischarging);
                status.put("isError",
                        state != null && state.isError);
                putPower(status, power, true);
                status.put("charging", charging);
                status.put("plugged", plugged);
                status.put("full", full);
                status.put("fault", fault);
                if (socPercent >= 0) status.put("socPercent", socPercent);
                status.put("sessionKwh",
                        sessionKwh > 0 ? sessionKwh : JSONObject.NULL);
                status.put("sessionEnergyIncomplete",
                        sessionEnergyIncomplete);
                status.put("sessionEnergyEstimated",
                        sessionEnergyEstimated);
                status.put("sessionEnergySource",
                        sessionKwh > 0
                                ? sessionEnergySource
                                : JSONObject.NULL);
                if (timeToFullMin > 0) {
                    status.put("timeToFullMin", timeToFullMin);
                }
            } catch (Exception e) {
                return buildClearedStatusJson();
            }
            return status;
        }
    }

    public static LivePublication readLivePublication(
            SocHistoryDatabase database) {
        ChargingDetector.StateSnapshot lastSnapshot = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            ChargingDetector.StateSnapshot before = null;
            try {
                before = ChargingDetector.getInstance().getStateSnapshot();
            } catch (Exception ignored) {}

            ChargingStateData state = null;
            double socPercent = -1.0;
            double rangeKm = -1.0;
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            if (monitor != null) {
                try {
                    state = monitor.getChargingState();
                } catch (Exception ignored) {}
                try {
                    BatterySocData soc = monitor.getBatterySoc();
                    if (soc != null) socPercent = soc.socPercent;
                } catch (Exception ignored) {}
                try {
                    DrivingRangeData range = monitor.getDrivingRange();
                    if (range != null && range.isValidRange()) {
                        rangeKm = range.elecRangeKm;
                    }
                } catch (Exception ignored) {}
            }

            int gunState = BydVehicleData.UNAVAILABLE;
            boolean vtolCharging = false;
            BydVehicleData vehicleData = null;
            try {
                vehicleData = BydDataCollector.getInstance().getData();
                if (vehicleData != null) {
                    gunState = vehicleData.chargingGunState;
                    vtolCharging = vehicleData.vtolCharging;
                }
            } catch (Exception ignored) {}

            double sessionKwh = -1.0;
            boolean sessionEnergyIncomplete = false;
            boolean sessionEnergyEstimated = false;
            String sessionEnergySource = SessionEnergyResolver.SRC_NONE;
            int timeToFullMin = -1;
            double sohPercent = -1.0;
            if (database != null) {
                try {
                    if (database.getOpenChargingSessionStart() > 0) {
                        SocHistoryDatabase.OpenChargingSessionEnergy energy =
                                database.getOpenChargingSessionEnergy();
                        sessionKwh = energy.energyKwh;
                        sessionEnergyIncomplete = energy.incomplete;
                        sessionEnergyEstimated = energy.estimated;
                        sessionEnergySource = energy.source;
                        timeToFullMin =
                                database.getOpenChargingSessionTimeToFullMin();
                    }
                } catch (Exception ignored) {}
                try {
                    SohEstimator soh = database.getSohEstimator();
                    if (soh != null && soh.hasDisplaySoh()) {
                        double value = soh.getDisplaySoh();
                        if (value > 0 && value <= 100) {
                            sohPercent =
                                    Math.round(value * 10.0) / 10.0;
                        }
                    }
                } catch (Exception ignored) {}
            }
            if (sohPercent <= 0 && vehicleData != null
                    && Double.isFinite(vehicleData.sohPercent)
                    && vehicleData.sohPercent > 0
                    && vehicleData.sohPercent <= 100) {
                sohPercent =
                        Math.round(vehicleData.sohPercent * 10.0) / 10.0;
            }
            if (timeToFullMin <= 0 && vehicleData != null) {
                int unavailable = BydVehicleData.UNAVAILABLE;
                int hours = vehicleData.chargingRestTimeHours;
                int minutes = vehicleData.chargingRestTimeMinutes;
                if (hours != unavailable || minutes != unavailable) {
                    timeToFullMin =
                            (hours != unavailable ? hours * 60 : 0)
                            + (minutes != unavailable ? minutes : 0);
                }
            }

            com.overdrive.app.byd.cloud.VehicleCloudSnapshot cloudSnap = null;
            try {
                com.overdrive.app.byd.cloud.BydCloudDataProvider cp = com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance();
                if (cp != null) cloudSnap = cp.getSnapshot();
            } catch (Exception ignored) {}

            if (timeToFullMin <= 0 && cloudSnap != null && (cloudSnap.remainingHours >= 0 || cloudSnap.remainingMinutes >= 0)) {
                timeToFullMin = Math.max(0, cloudSnap.remainingHours * 60 + Math.max(0, cloudSnap.remainingMinutes));
            }

            ChargingDetector.StateSnapshot after = null;
            try {
                after = ChargingDetector.getInstance().getStateSnapshot();
            } catch (Exception ignored) {}
            lastSnapshot = after;
            if (!ChargingDetector.isPublicationWindowStable(before, after)) {
                continue;
            }

            boolean isCloudCharging = cloudSnap != null && cloudSnap.getChargingStateAsSdk() == 1;
            boolean isCloudPlugged = isCloudCharging || (cloudSnap != null && cloudSnap.chargingState == 15);

            boolean hasLocalDetector = after != null && after.observedAtMs > 0;
            boolean effectiveCharging = hasLocalDetector ? after.charging : (isCloudCharging && gunState != 1 && gunState != BydVehicleData.UNAVAILABLE);

            ChargingStateData.ChargingStatus status = state != null
                    ? state.status : (effectiveCharging ? ChargingStateData.ChargingStatus.CHARGING : ChargingStateData.ChargingStatus.UNKNOWN);
            LiveStateFlags flags = normalizeLiveState(
                    effectiveCharging,
                    status,
                    state != null && state.isTaperCharging,
                    gunState,
                    vtolCharging);
            if (!hasLocalDetector && (isCloudCharging || isCloudPlugged)) {
                flags = new LiveStateFlags(
                    flags.charging || isCloudCharging,
                    flags.plugged || isCloudPlugged,
                    flags.full
                );
            }
            PowerPublication power =
                    normalizePowerPublication(flags.charging, state);
            if (!flags.charging) {
                // An open row may remain during the bounded final-counter drain. It is persistence
                // state, not proof that power is still flowing.
                sessionKwh = -1.0;
                sessionEnergyIncomplete = false;
                sessionEnergyEstimated = false;
                sessionEnergySource = SessionEnergyResolver.SRC_NONE;
                timeToFullMin = -1;
            }
            if (state == null && (isCloudCharging || isCloudPlugged)) {
                state = new ChargingStateData(isCloudCharging ? 1 : 0);
            }
            return new LivePublication(
                    state,
                    flags.charging,
                    flags.plugged,
                    flags.full,
                    state != null && state.isError,
                    socPercent,
                    sessionKwh,
                    sessionEnergyIncomplete,
                    sessionEnergyEstimated,
                    sessionEnergySource,
                    timeToFullMin,
                    power,
                    rangeKm,
                    sohPercent,
                    after);
        }
        return LivePublication.cleared(lastSnapshot);
    }

    private static void putPower(
            JSONObject target, PowerPublication power,
            boolean includeLegacyKey) throws Exception {
        if (includeLegacyKey) {
            target.put("chargingPowerKW", power.powerKw);
        }
        target.put("powerKw", power.powerKw);
        target.put("isEstimated", power.isEstimated);
        target.put("powerSource", power.source);
        target.put("powerObservedAtMs", power.observedAtMs);
        target.put("powerQuality", power.quality);
        target.put("powerConfidence", power.confidence);
    }

    private static JSONObject buildClearedStatusJson() {
        JSONObject status = new JSONObject();
        try {
            status.put("stateName", "Unavailable");
            status.put("status", "UNKNOWN");
            status.put("chargingPowerKW", 0);
            status.put("isDischarging", false);
            status.put("isError", false);
            status.put("isEstimated", false);
            status.put("powerSource", "none");
            status.put("powerObservedAtMs", 0);
            status.put("powerQuality",
                    ChargingStateData.PowerQuality.UNKNOWN.name());
            status.put("powerConfidence", 0);
            status.put("charging", false);
            status.put("plugged", false);
            status.put("full", false);
            status.put("fault", false);
            status.put("powerKw", 0);
            status.put("sessionKwh", JSONObject.NULL);
            status.put("sessionEnergyIncomplete", false);
            status.put("sessionEnergyEstimated", false);
            status.put("sessionEnergySource", JSONObject.NULL);
        } catch (Exception ignored) {}
        return status;
    }

    /** Canonical power/provenance payload shared by charging summary and /status. */
    public static final class PowerPublication {
        public final double powerKw;
        public final boolean isEstimated;
        public final String source;
        public final long observedAtMs;
        public final String quality;
        public final double confidence;

        PowerPublication(double powerKw, boolean isEstimated,
                         String source, long observedAtMs,
                         String quality, double confidence) {
            this.powerKw = powerKw;
            this.isEstimated = isEstimated;
            this.source = source;
            this.observedAtMs = observedAtMs;
            this.quality = quality;
            this.confidence = confidence;
        }
    }

    public static PowerPublication normalizePowerPublication(
            boolean charging, ChargingStateData state) {
        if (!charging || state == null
                || !Double.isFinite(state.chargingPowerKW)
                || state.chargingPowerKW <= 0.0
                || state.chargingPowerKW
                        > ChargingStateData.MAX_ABSOLUTE_POWER_KW) {
            return new PowerPublication(
                    0.0, false, "none", 0L,
                    ChargingStateData.PowerQuality.UNKNOWN.name(), 0.0);
        }
        ChargingStateData.PowerQuality quality =
                state.powerQuality != null
                        ? state.powerQuality
                        : ChargingStateData.PowerQuality.UNKNOWN;
        return new PowerPublication(
                state.chargingPowerKW,
                state.isEstimated,
                state.powerSource != null && !state.powerSource.isEmpty()
                        ? state.powerSource : "none",
                Math.max(0L, state.powerObservedAtMs),
                quality.name(),
                Math.max(0.0, Math.min(1.0, state.powerConfidence)));
    }

    static final class LiveStateFlags {
        final boolean charging;
        final boolean plugged;
        final boolean full;

        LiveStateFlags(boolean charging, boolean plugged, boolean full) {
            this.charging = charging;
            this.plugged = plugged;
            this.full = full;
        }
    }

    static LiveStateFlags normalizeLiveState(
            boolean charging,
            ChargingStateData.ChargingStatus status,
            boolean taperCharging,
            int gunState,
            boolean vtolCharging) {
        boolean exporting = vtolCharging
                || gunState == 5
                || status == ChargingStateData.ChargingStatus.DISCHARGING;
        boolean terminal = status == ChargingStateData.ChargingStatus.READY
                || status == ChargingStateData.ChargingStatus.FINISHED
                || status == ChargingStateData.ChargingStatus.TERMINATED
                || status == ChargingStateData.ChargingStatus.TIMEOUT
                || status == ChargingStateData.ChargingStatus.ERROR;
        boolean normalizedCharging = terminal
                ? taperCharging
                    && status == ChargingStateData.ChargingStatus.FINISHED
                : charging;
        boolean full = status == ChargingStateData.ChargingStatus.FINISHED
                && !taperCharging;
        if (exporting || gunState == 1) {
            normalizedCharging = false;
            full = false;
        }
        return new LiveStateFlags(
                normalizedCharging,
                resolvePlugged(
                        normalizedCharging, status, gunState, vtolCharging),
                full);
    }

    public static boolean resolvePlugged(boolean charging,
                                         ChargingStateData.ChargingStatus status,
                                         int gunState,
                                         boolean vtolCharging) {
        if (gunState == 1 || gunState == 5 || vtolCharging
                || status == ChargingStateData.ChargingStatus.DISCHARGING) {
            return false;
        }
        if (gunState == 2 || gunState == 3 || gunState == 4) return true;
        return charging
                || status == ChargingStateData.ChargingStatus.FINISHED
                || status == ChargingStateData.ChargingStatus.READY
                || status == ChargingStateData.ChargingStatus.SCHEDULED;
    }

    private JSONObject handleGetConfig() {
        JSONObject response = new JSONObject();
        try {
            ChargingConfig liveConfig = manager.getConfig();
            ChargingConfig config = liveConfig != null
                    ? liveConfig.loadSnapshot() : null;
            if (liveConfig != null && config == null) {
                return errorResponse("Failed to load config", 500);
            }
            JSONObject configJson =
                    config != null ? config.toJson() : new JSONObject();
            enrichConfigResponse(configJson);
            response.put("success", true);
            response.put("config", configJson);
        } catch (Exception e) {
            logger.error("Error building charging config response", e);
            return errorResponse("Failed to get config", 500);
        }
        return response;
    }

    private JSONObject handleGetConfigFromRoot(JSONObject root) {
        JSONObject response = new JSONObject();
        try {
            ChargingConfig config = new ChargingConfig();
            config.loadFromRoot(root);
            JSONObject configJson = config.toJson();
            enrichConfigResponse(configJson);
            response.put("success", true);
            response.put("config", configJson);
        } catch (Exception e) {
            logger.error("Error building charging config response", e);
            return errorResponse("Failed to get config", 500);
        }
        return response;
    }

    private void enrichConfigResponse(JSONObject configJson) {
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
    }

    /** Serializes concurrent config POSTs (read-modify-write of one file). */
    private static final Object CONFIG_UPDATE_LOCK = new Object();

    /** A rate is either unset (0) or a sane positive tariff. */
    private static boolean isValidElectricityRate(double rate) {
        return !Double.isNaN(rate) && !Double.isInfinite(rate)
                && rate >= 0 && rate <= 100000;
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
                if (!isValidElectricityRate(rv)) {
                    return errorResponse(rk + " must be between 0 and 100000", 400);
                }
            }
            String invalidCurrency = validateCurrency(bodyJson);
            if (invalidCurrency != null) return errorResponse(invalidCurrency, 400);

            // Serialize config writes against each other so two concurrent
            // POSTs cannot interleave a read-modify-write of the same file.
            synchronized (CONFIG_UPDATE_LOCK) {
                return persistConfigUpdate(bodyJson);
            }
        } catch (Exception e) {
            logger.error("Error saving charging config", e);
            return errorResponse("Failed to save config: " + e.getMessage(), 400);
        }
    }

    private JSONObject persistConfigUpdate(JSONObject bodyJson)
            throws Exception {
        ChargingConfig liveConfig = manager.getConfig();
        if (liveConfig != null) {
            // Stage from fresh durable state. Omitted fields therefore cannot
            // replay a stale manager cache over a newer Trips update.
            ChargingConfig staged = liveConfig.loadSnapshot();
            if (staged == null) {
                return errorResponse(
                        "Could not load charging settings", 500);
            }
            if (bodyJson.has("enabled")) {
                staged.setEnabled(bodyJson.getBoolean("enabled"));
            }
            if (bodyJson.has("dcRate")) {
                staged.setDcRate(bodyJson.getDouble("dcRate"));
            }
            if (bodyJson.has("fastSampleSec")) {
                staged.setFastSampleSec(
                        bodyJson.getInt("fastSampleSec"));
            }
            // Rate/currency are the shared Trips value (read-through). Allow
            // editing here too; ChargingConfig.save() mirrors them back.
            if (bodyJson.has("electricityRate")) {
                staged.setElectricityRate(
                        bodyJson.getDouble("electricityRate"));
            }
            if (bodyJson.has("currency")) {
                staged.setCurrency(bodyJson.getString("currency"));
            }
            if (!staged.save(
                    bodyJson.has("enabled"),
                    bodyJson.has("dcRate"),
                    bodyJson.has("fastSampleSec"),
                    bodyJson.has("electricityRate"),
                    bodyJson.has("currency"))) {
                return errorResponse(
                        "Could not save charging settings", 500);
            }

            ChargingConfig persisted = liveConfig.loadSnapshot();
            if (persisted == null) {
                return errorResponse(
                        "Could not reload charging settings", 500);
            }

            TripAnalyticsManager tripAnalyticsManager =
                    tripAnalyticsManagerSupplier != null
                            ? tripAnalyticsManagerSupplier.get()
                            : null;
            // Mirror the charging tariff into the trips config so trip costs
            // use the rate the user just saved.
            if (tripAnalyticsManager != null) {
                com.overdrive.app.trips.TripConfig tripConfig =
                        tripAnalyticsManager.getConfig();
                if (tripConfig != null) {
                    tripConfig.setElectricityRate(
                            persisted.getElectricityRate());
                    tripConfig.setCurrency(persisted.getCurrency());
                    tripConfig.save();
                }
            }
            // Keep the old charging-manager values until this callback:
            // onConfigChanged() compares old enabled state with the durable
            // state it reloads.
            manager.onConfigChanged();
        }
        JSONObject response = new JSONObject();
        response.put("success", true);
        // Deliberately NO re-price here. Sessions priced by the global rate
        // keep the rate snapshotted when they closed. Only named location
        // tariffs are re-priceable.
        return response;
    }

    // ==================== TARIFFS ====================

    /**
     * List tariffs (usage-ordered) plus the current GPS fix and which profile
     * matches it, so the UI can flag "auto-applies here" without a second call.
     */
    private JSONObject handleGetTariffs() {
        try {
            return handleGetTariffsFromRoot(
                    TariffManager.loadVerifiedConfig());
        } catch (Exception e) {
            logger.error("Error reading tariff config", e);
            return errorResponse("Failed to list tariffs", 500);
        }
    }

    private JSONObject handleGetTariffsFromRoot(JSONObject root) {
        JSONObject response = new JSONObject();
        try {
            double[] loc = currentLocation();
            JSONObject payload = TariffManager.getInstance()
                    .toStatusJson(root, loc[0], loc[1]);
            // Global fallbacks, so the UI can render "otherwise X/kWh" without
            // also fetching /config.
            ChargingConfig cfg = new ChargingConfig();
            cfg.loadFromRoot(root);
            payload.put("globalRate", cfg.getElectricityRate());
            payload.put("globalDcRate", cfg.getDcRate());
            payload.put("currency", cfg.getCurrency());
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
                TariffManager.RepriceIntent intent;
                synchronized (mgr) {
                    if (!mgr.update(id, b)) {
                        return errorResponse("Could not save tariff", 500);
                    }
                    intent = mgr.pendingRepriceIntent(id);
                }
                RepriceOutcome repricing =
                        repriceHistory(intent);
                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("tariff", tariffJson(mgr, id));
                appendRepricing(response, repricing);
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
            if (!ChargingConfig.isValidCurrency(fallbackCurrency)) fallbackCurrency = "";
            TariffProfile p;
            TariffManager.RepriceIntent intent;
            synchronized (mgr) {
                p = mgr.add(
                        b.optString("label", ""),
                        lat, lng,
                        b.optInt("radiusM", TariffProfile.DEFAULT_RADIUS_M),
                        b.optDouble("acRate", 0),
                        b.optDouble("dcRate", 0),
                        b.optString("currency", fallbackCurrency));
                if (p == null) {
                    return errorResponse("Could not save tariff", 500);
                }
                intent = mgr.pendingRepriceIntent(p.getId());
            }

            // A brand-new tariff can retroactively own past charges at this place.
            RepriceOutcome repricing =
                    repriceHistory(intent);
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("tariff", p.toJson());
            appendRepricing(response, repricing);
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
            TariffManager.RepriceIntent intent;
            synchronized (mgr) {
                if (!mgr.remove(id)) {
                    return errorResponse("Could not save tariff", 500);
                }
                intent = mgr.pendingRepriceIntent("");
            }
            // Sessions this tariff priced must fall back to whatever now applies —
            // another profile, or the global rate. Pass "" to re-evaluate every
            // session, since the deleted id is gone from the list and can no
            // longer be matched on. (Sessions that were already on the global
            // rate are skipped inside repriceSessionsForTariff — their historical
            // cost is never restated.)
            RepriceOutcome repricing =
                    repriceHistory(intent);
            JSONObject response = new JSONObject();
            response.put("success", true);
            appendRepricing(response, repricing);
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
            TariffManager.RepriceIntent intent;
            synchronized (mgr) {
                if (!mgr.setDefault(id)) {
                    return errorResponse("Could not save tariff", 500);
                }
                intent = mgr.pendingRepriceIntent("");
            }
            // The default prices any charge that matched no circle, so changing it
            // changes which tariff owns those sessions. Re-evaluate all; sessions
            // that are on the global rate before AND after are left untouched.
            RepriceOutcome repricing =
                    repriceHistory(intent);
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("defaultTariffId", TariffManager.getInstance().getDefaultId());
            appendRepricing(response, repricing);
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
        String invalidCurrency = validateCurrency(b);
        if (invalidCurrency != null) return invalidCurrency;

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
        // A complete zero-rate update can be rejected without reading state.
        // Partial updates are validated against the fresh durable profile by
        // TariffManager.update() under the config lock.
        if (!isCreate && b.has("acRate") && b.has("dcRate")
                && !(ac > 0) && !(dc > 0)) {
            return "Enter an AC or DC rate";
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

    static String validateCurrency(JSONObject body) {
        if (body == null || !body.has("currency")) return null;
        Object raw = body.opt("currency");
        if (!(raw instanceof String)) return "Currency must be text";
        if (!ChargingConfig.isValidCurrency((String) raw)) {
            return "Currency must be at most " + ChargingConfig.MAX_CURRENCY_LENGTH + " characters";
        }
        return null;
    }

    private JSONObject tariffJson(TariffManager mgr, String id) {
        TariffProfile p = mgr.findById(id);
        return p != null ? p.toJson() : new JSONObject();
    }

    static final class RepriceOutcome {
        final int changedCount;
        final String status;
        final boolean confirmed;
        final boolean durable;
        final String error;

        private RepriceOutcome(
                int changedCount, String status,
                boolean confirmed, boolean durable,
                String error) {
            this.changedCount = Math.max(0, changedCount);
            this.status = status;
            this.confirmed = confirmed;
            this.durable = durable;
            this.error = error != null ? error : "";
        }
    }

    private static final String REPRICE_PENDING_MESSAGE =
            "Tariff repricing is pending durable replay";

    static RepriceOutcome completedReprice(int changedCount) {
        return new RepriceOutcome(
                changedCount, "complete", true, true, "");
    }

    static RepriceOutcome classifyRepriceFailure(
            Throwable failure) {
        if (failure instanceof IllegalStateException
                && REPRICE_PENDING_MESSAGE.equals(
                        failure.getMessage())) {
            return new RepriceOutcome(
                    0, "pending", false, true, "");
        }
        return new RepriceOutcome(
                0, "failed", false, false,
                "Tariff was saved, but history repricing could not be queued");
    }

    static void appendRepricing(
            JSONObject response, RepriceOutcome outcome)
            throws Exception {
        response.put("repriced",
                outcome.confirmed
                        ? outcome.changedCount : JSONObject.NULL);
        response.put("repricingStatus", outcome.status);
        response.put("repricingConfirmed", outcome.confirmed);
        response.put("repricingDurable", outcome.durable);
        if ("pending".equals(outcome.status)) {
            response.put("repricingPending", true);
        } else if ("failed".equals(outcome.status)) {
            // The tariff config committed before repricing was attempted. Report
            // that partial commit explicitly so clients refresh instead of
            // retrying the tariff mutation and creating duplicate state.
            response.put("success", false);
            response.put("tariffSaved", true);
            response.put("error", outcome.error);
            response.put("_status", 500);
        }
    }

    /**
     * Re-price history after the tariff config commit. The tariff mutation remains
     * successful when history is unavailable, but the response explicitly marks
     * the independent database outcome.
     */
    private RepriceOutcome repriceHistory(
            TariffManager.RepriceIntent intent) {
        if (intent == null) {
            return classifyRepriceFailure(
                    new IllegalStateException(
                            "tariff repricing intent is missing"));
        }
        try {
            SocHistoryDatabase database = db();
            if (database == null) {
                return pendingReprice();
            }
            // Do not pre-check isAvailable(). The database writes the durable
            // repricing intent before probing H2; an unavailable database must
            // therefore become "pending", not an unjournaled local no-op.
            int changed =
                    database.repriceSessionsForTariff(
                            intent.tariffId());
            if (!TariffManager.getInstance()
                    .completePendingReprice(intent)) {
                return pendingReprice();
            }
            return completedReprice(changed);
        } catch (Throwable t) {
            // Every API tariff mutation committed its config-side intent in the
            // same write. Database/journal failures therefore remain durably
            // replayable even when the database's own queue was unavailable.
            RepriceOutcome outcome = pendingReprice();
            logger.warn("Tariff repricing "
                    + outcome.status + ": " + t.getMessage());
            return outcome;
        }
    }

    private static RepriceOutcome pendingReprice() {
        return new RepriceOutcome(
                0, "pending", false, true, "");
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
            return clearHistoryResponse(removed);
        } catch (Exception e) {
            logger.error("Error clearing charging history", e);
            return errorResponse("Failed to clear history", 500);
        }
    }

    static JSONObject clearHistoryResponse(long removed) {
        JSONObject response = new JSONObject();
        try {
            if (removed < 0) {
                response.put("success", false);
                response.put("error", "Failed to clear history");
                response.put("_status", 500);
                return response;
            }
            response.put("success", true);
            response.put("removed", removed);
        } catch (Exception e) {
            logger.error("Error building clear history response", e);
        }
        return response;
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
