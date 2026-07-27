package com.overdrive.app.charging;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.ChargingDetector;
import com.overdrive.app.monitor.ChargingStateData;
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
    private static final Pattern SESSION_DELETE_PATTERN = Pattern.compile("^/api/charging/(\\d+)/delete$");
    private static final Pattern SESSION_RATE_PATTERN = Pattern.compile("^/api/charging/(\\d+)/rate$");

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

            // POST /api/charging/{id}/rate — update rate
            Matcher rateMatcher = SESSION_RATE_PATTERN.matcher(path);
            if (rateMatcher.matches() && "POST".equals(method)) {
                long id = Long.parseLong(rateMatcher.group(1));
                return handleUpdateSessionRate(id, body);
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
            return successResponse();
        } catch (Exception e) {
            logger.error("Error deleting charging session " + id, e);
            return errorResponse("Failed to delete session", 500);
        }
    }

    /** POST /api/charging/{id}/rate — update electricity rate of a completed session. */
    private JSONObject handleUpdateSessionRate(long id, String body) {
        try {
            JSONObject bodyJson;
            try {
                bodyJson = new JSONObject(body != null ? body : "{}");
            } catch (org.json.JSONException je) {
                return errorResponse("Invalid JSON payload", 400);
            }
            if (!bodyJson.has("rate")) {
                return errorResponse("Missing 'rate' parameter", 400);
            }
            double rate = bodyJson.getDouble("rate");
            boolean ok = db().updateChargingSessionRate(id, rate);
            if (!ok) return errorResponse("Failed to update session rate (session may be in progress or not found)", 500);
            return successResponse();
        } catch (Exception e) {
            logger.error("Error updating rate for session " + id, e);
            return errorResponse("Failed to update rate: " + e.getMessage(), 500);
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
            double powerKw = 0, socPct = -1;
            int timeToFullMin = -1;
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
            }

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
                    timeToFullMin = db().getOpenChargingSessionTimeToFullMin();
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
            live.put("sessionKwh", sessionKwh > 0 ? sessionKwh : JSONObject.NULL);
            live.put("timeToFullMin", timeToFullMin > 0 ? timeToFullMin : JSONObject.NULL);
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
            ChargingConfig config = manager.getConfig();
            if (config != null) {
                if (bodyJson.has("enabled")) config.setEnabled(bodyJson.getBoolean("enabled"));
                if (bodyJson.has("dcRate")) config.setDcRate(bodyJson.getDouble("dcRate"));
                if (bodyJson.has("fastSampleSec")) config.setFastSampleSec(bodyJson.getInt("fastSampleSec"));
                // Rate/currency are the shared Trips value (read-through). Allow
                // editing here too; ChargingConfig.save() mirrors them back.
                if (bodyJson.has("electricityRate")) config.setElectricityRate(bodyJson.getDouble("electricityRate"));
                if (bodyJson.has("currency")) config.setCurrency(bodyJson.getString("currency"));
                config.save();
                manager.onConfigChanged();
            }
            JSONObject response = new JSONObject();
            response.put("success", true);
            return response;
        } catch (Exception e) {
            logger.error("Error saving charging config", e);
            return errorResponse("Failed to save config: " + e.getMessage(), 400);
        }
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

    private JSONObject successResponse() {
        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
        } catch (Exception ignored) {}
        return response;
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
