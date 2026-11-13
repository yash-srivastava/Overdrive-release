package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.navmap.NavMapConfig;
import com.overdrive.app.navmap.google.GoogleApi;
import com.overdrive.app.navmap.google.GoogleApiState;
import com.overdrive.app.navmap.google.GoogleKeyValidator;
import com.overdrive.app.navmap.google.GoogleMapsBudget;
import com.overdrive.app.navmap.google.GoogleMapsConfig;
import com.overdrive.app.navmap.google.GoogleMapsSettings;
import com.overdrive.app.navmap.google.GooglePricing;
import com.overdrive.app.navmap.google.GoogleSku;
import com.overdrive.app.navmap.google.GoogleUsageMirror;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * RoadSense Map routing (BYOK) HTTP API — backs the "Routing" config card on the
 * road-sense.html settings page. Runs in the daemon process (UID 2000) where
 * {@link com.overdrive.app.config.UnifiedConfigManager} and
 * {@link com.overdrive.app.byd.cloud.crypto.CredentialCipher} live, so it calls
 * {@link NavMapConfig} directly (no IPC hop). Mirrors {@link BydCloudApiHandler}'s
 * structure — the secret (routing API key) is stored encrypted and is NEVER returned
 * to the UI; status only exposes a {@code hasKey} boolean (exactly how byd-cloud status
 * never returns the password, only {@code hasLoginKey}).
 *
 * <p>Note: the WebView in this app drops XHR POST bodies, so the web side MUST use
 * {@code fetch()} for the setup/clear POSTs — the handler simply reads {@code body}.
 *
 * Endpoints:
 *  - GET  /api/navmap/routing/status → {success, configured, enabled, endpoint, hasKey}
 *  - POST /api/navmap/routing/setup  → {endpoint, apiKey} → NavMapConfig.saveRouting(...)
 *  - POST /api/navmap/routing/clear  → NavMapConfig.clearRouting()
 */
public class NavMapApiHandler {

    private static final String TAG = "NavMapApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * Handle RoadSense Map API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;

        if (cleanPath.equals("/api/navmap/routing/status") && method.equals("GET")) {
            handleStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/navmap/routing/setup") && method.equals("POST")) {
            handleSetup(out, body);
            return true;
        }
        if (cleanPath.equals("/api/navmap/routing/clear") && method.equals("POST")) {
            handleClear(out);
            return true;
        }
        // Cluster projection: start / stop / status for projecting the map onto
        // the driver-cluster display (a SUSTAINED holder of ClusterProjectionController).
        if (cleanPath.equals("/api/navmap/cluster/start") && method.equals("POST")) {
            if (com.overdrive.app.navmap.AndroidAutoClusterBridge.isActive()) {
                JSONObject response = new JSONObject();
                response.put("success", false);
                response.put("projecting", false);
                response.put("error", "Stop the Android Auto cluster bridge before projecting the RoadSense map.");
                HttpResponse.sendJson(out, response.toString());
            } else {
                com.overdrive.app.navmap.ClusterMapProjector.start();
                sendClusterStatus(out);
            }
            return true;
        }
        if (cleanPath.equals("/api/navmap/cluster/stop") && method.equals("POST")) {
            com.overdrive.app.navmap.ClusterMapProjector.stop();
            sendClusterStatus(out);
            return true;
        }
        if (cleanPath.equals("/api/navmap/cluster/status") && method.equals("GET")) {
            sendClusterStatus(out);
            return true;
        }
        // Optional Android Auto secondary CLUSTER video stream. The factory
        // receiver is not modified; a compatible receiver pushes H.264/RTP to
        // the loopback listener owned by AndroidAutoClusterBridge.
        if (cleanPath.equals("/api/navmap/android-auto-cluster/start") && method.equals("POST")) {
            boolean started = com.overdrive.app.navmap.AndroidAutoClusterBridge.start();
            sendAndroidAutoClusterStatus(out, started);
            return true;
        }
        if (cleanPath.equals("/api/navmap/android-auto-cluster/launch-receiver") && method.equals("POST")) {
            boolean launched = com.overdrive.app.navmap.AndroidAutoClusterBridge.launchReceiver();
            sendAndroidAutoClusterStatus(out, launched);
            return true;
        }
        if (cleanPath.equals("/api/navmap/android-auto-cluster/stop") && method.equals("POST")) {
            com.overdrive.app.navmap.AndroidAutoClusterBridge.stop();
            sendAndroidAutoClusterStatus(out, true);
            return true;
        }
        if (cleanPath.equals("/api/navmap/android-auto-cluster/status") && method.equals("GET")) {
            sendAndroidAutoClusterStatus(out, true);
            return true;
        }
        if (cleanPath.startsWith(GOOGLE_PREFIX)) {
            return handleGoogle(method, cleanPath.substring(GOOGLE_PREFIX.length()), body, out);
        }
        return false;
    }

    /** Current cluster-projection state. */
    private static void sendClusterStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("projecting", com.overdrive.app.navmap.ClusterMapProjector.isActive());
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /** Current Android Auto cluster bridge state. */
    private static void sendAndroidAutoClusterStatus(OutputStream out, boolean operationSucceeded)
            throws Exception {
        JSONObject response = com.overdrive.app.navmap.AndroidAutoClusterBridge.status();
        response.put("success", operationSucceeded);
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * GET /api/navmap/routing/status — current routing config state. NEVER returns the
     * actual key — only {@code hasKey} (mirrors byd-cloud status never returning the password).
     */
    private static void handleStatus(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            NavMapConfig config = NavMapConfig.fromUnifiedConfig();
            response.put("success", true);
            response.put("configured", config.isRoutingConfigured());
            response.put("enabled", config.enabled);
            response.put("endpoint", config.routingEndpoint);
            // Never return the key itself — only whether one is set.
            response.put("hasKey", !config.routingApiKey.isEmpty());
        } catch (Exception e) {
            logger.warn(TAG + ": status failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/navmap/routing/setup — save the BYOK routing endpoint + key.
     *
     * Request body:
     * {
     *   "endpoint": "https://valhalla1.openstreetmap.de/route",  // optional, defaults
     *   "apiKey":   "the-secret-key"                             // required (encrypted at rest)
     * }
     */
    private static void handleSetup(OutputStream out, String body) throws Exception {
        JSONObject response = new JSONObject();
        try {
            JSONObject req = new JSONObject(body == null ? "" : body);
            String endpoint = req.optString("endpoint", "").trim();
            String apiKey = req.optString("apiKey", "").trim();

            if (apiKey.isEmpty()) {
                response.put("success", false);
                response.put("error", "Routing API key is required");
                HttpResponse.sendJson(out, response.toString());
                return;
            }

            // Empty/blank endpoint falls back to the default inside saveRouting.
            NavMapConfig.saveRouting(endpoint, apiKey);
            logger.info(TAG + ": saved routing config (endpoint set, key encrypted)");

            response.put("success", true);
        } catch (Exception e) {
            // JSONException messages quote the request body, which carries the key.
            logger.warn(TAG + ": setup failed: " + e.getClass().getSimpleName());
            response.put("success", false);
            response.put("error", "setup_failed");
        }
        HttpResponse.sendJson(out, response.toString());
    }

    /**
     * POST /api/navmap/routing/clear — clear the stored routing credential.
     */
    private static void handleClear(OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        try {
            NavMapConfig.clearRouting();
            logger.info(TAG + ": cleared routing config");
            response.put("success", true);
        } catch (Exception e) {
            logger.warn(TAG + ": clear failed: " + e.getMessage());
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        HttpResponse.sendJson(out, response.toString());
    }

    // ==================== Google Maps (BYOK) ====================

    private static final String GOOGLE_PREFIX = "/api/navmap/google/";
    private static final int GOOGLE_MAX_BODY_BYTES = 16 * 1024;
    private static final long GOOGLE_MAX_QUOTA = 1_000_000L;
    private static final long GOOGLE_MIN_BUDGET_CENTS = 100L;
    private static final long GOOGLE_MAX_BUDGET_CENTS = 1_000_000L;
    private static final String[][] GOOGLE_FLAG_KEYS = {
            {"basemap", "googleBasemap"}, {"traffic", "googleTraffic"}, {"routing", "googleRouting"},
            {"routeTraffic", "googleRouteTraffic"}, {"search", "googleSearch"}, {"enabled", "googleEnabled"}
    };
    private static final String[][] GOOGLE_QUOTA_KEYS = {
            {"quotaTilesPerDay", "googleQuotaTilesPerDay"},
            {"quotaRoutesPerDay", "googleQuotaRoutesPerDay"},
            {"quotaPlacesPerDay", "googleQuotaPlacesPerDay"},
            {"quotaGeocodePerDay", "googleQuotaGeocodePerDay"}
    };
    // Serialises key probes with the writes that depend on them (setup, test, clear).
    private static final Object GOOGLE_LOCK = new Object();
    private static volatile GoogleProbeMemo googleLastProbe;

    /** Per-API reasons from the last stored probe, with the states they explain. */
    private static final class GoogleProbeMemo {
        final JSONObject states;
        final JSONObject reasons;

        GoogleProbeMemo(JSONObject states, JSONObject reasons) {
            this.states = states;
            this.reasons = reasons;
        }
    }

    /**
     * /api/navmap/google/{status,setup,test,settings,clear,usage}. The API key is write-only: it is
     * never part of a response, log line or error, and responses are same-origin only.
     */
    private static boolean handleGoogle(String method, String route, String body, OutputStream out)
            throws Exception {
        boolean get = method.equals("GET") && route.equals("status");
        boolean post = method.equals("POST") && (route.equals("setup") || route.equals("test")
                || route.equals("settings") || route.equals("clear") || route.equals("usage"));
        if (!get && !post) return false;
        if (post && isOverGoogleBodyLimit(body)) {
            sendGoogleJson(out, 413, googleError("body_too_large"));
            return true;
        }
        JSONObject response;
        try {
            switch (route) {
                case "status": response = googleStatus(); break;
                case "setup": response = googleSetup(body); break;
                case "test": response = googleTest(); break;
                case "settings": response = googleSettings(body); break;
                case "clear": response = googleClear(); break;
                default: response = googleUsage(body); break;
            }
        } catch (Exception | LinkageError e) {
            // Exception text can quote the request body, so only the type is logged.
            logger.warn(TAG + ": google " + route + " failed: " + e.getClass().getSimpleName());
            response = googleError(route + "_failed");
        }
        sendGoogleJson(out, 200, response);
        return true;
    }

    private static JSONObject googleStatus() throws Exception {
        GoogleMapsSettings settings = GoogleMapsConfig.load();
        GooglePricing pricing = settings.getPricing();
        JSONObject features = new JSONObject();
        features.put("basemap", settings.getBasemap());
        features.put("traffic", settings.getTraffic());
        features.put("routing", settings.getRouting());
        features.put("routeTraffic", settings.getRouteTraffic());
        features.put("search", settings.getSearch());
        JSONObject quotas = new JSONObject();
        quotas.put("tiles", settings.getQuotaTilesPerDay());
        quotas.put("routes", settings.getQuotaRoutesPerDay());
        quotas.put("places", settings.getQuotaPlacesPerDay());
        quotas.put("geocode", settings.getQuotaGeocodePerDay());
        boolean paid = settings.getPaid();
        int budgetCents = settings.getMonthlyBudgetCents();
        JSONObject recommended = new JSONObject();
        JSONObject recommendedPaid = new JSONObject();
        JSONObject caps = new JSONObject();
        JSONObject priceMicros = new JSONObject();
        for (GoogleSku sku : GoogleSku.values()) {
            recommended.put(sku.getWire(), GoogleMapsBudget.recommendedDailyQuota(pricing, sku));
            caps.put(sku.getWire(), GoogleMapsBudget.monthlyFreeCap(pricing, sku));
            priceMicros.put(sku.getWire(), GoogleMapsBudget.unitPriceMicros(pricing, sku));
            if (paid) {
                recommendedPaid.put(sku.getWire(),
                        GoogleMapsBudget.recommendedDailyQuotaPaid(pricing, sku, budgetCents));
            }
        }
        JSONObject apiState = googleStateJson(settings.getApiState());
        JSONObject usage = GoogleUsageMirror.latest();
        long spendMicros = usage != null ? Math.max(0L, usage.optLong("spendMicros", 0L)) : 0L;

        JSONObject response = new JSONObject();
        response.put("success", true);
        response.put("hasKey", settings.getHasKey());
        response.put("enabled", settings.getEnabled());
        response.put("termsAccepted", settings.getTermsAcceptedMs() > 0);
        response.put("features", features);
        response.put("pricing", googleWire(pricing));
        response.put("quotas", quotas);
        response.put("recommended", recommended);
        response.put("caps", caps);
        response.put("apiState", apiState);
        response.put("apiReason", googleReasonsFor(apiState));
        response.put("validatedMs", settings.getValidatedMs());
        response.put("usage", usage != null ? usage : JSONObject.NULL);
        response.put("resetAtMs", GoogleMapsBudget.nextResetMs());
        response.put("suggestedPricing", googleSuggestedPricing());
        response.put("spendMode", paid ? "paid" : "free");
        response.put("monthlyBudgetCents", budgetCents);
        response.put("estimatedSpendCents", spendMicros / 10_000L);
        response.put("priceMicros", priceMicros);
        if (paid) response.put("recommendedPaid", recommendedPaid);
        return response;
    }

    /** POST setup {apiKey, acceptTerms, pricing?}: probes the new key and stores it unless Google rejects it outright. */
    private static JSONObject googleSetup(String body) throws Exception {
        JSONObject req = parseGoogleBody(body);
        if (req == null) return googleError("bad_request");
        Object rawKey = req.opt("apiKey");
        String apiKey = rawKey instanceof String ? ((String) rawKey).trim() : "";
        boolean acceptTerms = Boolean.TRUE.equals(req.opt("acceptTerms"));
        String pricing = null;
        if (req.has("pricing")) {
            pricing = googlePricingWire(req.opt("pricing"));
            if (pricing == null) return googleError("invalid_pricing");
        }
        synchronized (GOOGLE_LOCK) {
            GoogleMapsSettings current = GoogleMapsConfig.load();
            if (current.getTermsAcceptedMs() <= 0 && !acceptTerms) return googleError("terms_required");
            if (!GoogleKeyValidator.isValidFormat(apiKey)) return googleError("invalid_format");

            GoogleKeyValidator.ValidationResult result = GoogleKeyValidator.validate(apiKey);
            JSONObject response = googleProbeJson(result);
            if (result.getKeyRejected()) {
                response.put("success", false);
                response.put("error", "invalid_key");
                return response;
            }
            long now = System.currentTimeMillis();
            JSONObject delta = new JSONObject();
            delta.put("googleTermsAcceptedMs", acceptTerms ? now : current.getTermsAcceptedMs());
            if (pricing != null) delta.put("googlePricing", pricing);
            if (!GoogleMapsConfig.saveSettings(delta) || !GoogleMapsConfig.saveKey(apiKey)) {
                response.put("success", false);
                response.put("error", "save_failed");
                return response;
            }
            if (!rememberGoogleProbe(result, response, now)) {
                response.put("success", false);
                response.put("error", "save_failed");
                return response;
            }
            logger.info(TAG + ": saved Google key (encrypted)");
            response.put("success", true);
            return response;
        }
    }

    /** POST test: probes the stored key and records the per-API states. */
    private static JSONObject googleTest() throws Exception {
        synchronized (GOOGLE_LOCK) {
            GoogleMapsSettings current = GoogleMapsConfig.load();
            if (!current.getHasKey()) return googleError("no_key");
            GoogleKeyValidator.ValidationResult result = GoogleKeyValidator.validate(current.getApiKey());
            JSONObject response = googleProbeJson(result);
            boolean saved = rememberGoogleProbe(result, response, System.currentTimeMillis());
            response.put("success", saved);
            if (!saved) response.put("error", "save_failed");
            return response;
        }
    }

    /** POST settings: feature flags, pricing, daily quotas, spend mode and budget (non-secret keys only). */
    private static JSONObject googleSettings(String body) throws Exception {
        JSONObject req = parseGoogleBody(body);
        if (req == null) return googleError("bad_request");
        JSONObject delta = new JSONObject();
        for (String[] flag : GOOGLE_FLAG_KEYS) {
            if (!req.has(flag[0])) continue;
            Object value = req.opt(flag[0]);
            if (!(value instanceof Boolean)) return googleError("invalid_value");
            delta.put(flag[1], value);
        }
        if (req.has("pricing")) {
            String pricing = googlePricingWire(req.opt("pricing"));
            if (pricing == null) return googleError("invalid_value");
            delta.put("googlePricing", pricing);
        }
        for (String[] quota : GOOGLE_QUOTA_KEYS) {
            if (!req.has(quota[0])) continue;
            Object value = req.opt(quota[0]);
            if (!(value instanceof Number)) return googleError("invalid_value");
            double requested = ((Number) value).doubleValue();
            if (Double.isNaN(requested) || Double.isInfinite(requested)) return googleError("invalid_value");
            long clamped = Math.max(0L, Math.min(GOOGLE_MAX_QUOTA, Math.round(requested)));
            delta.put(quota[1], (int) clamped);
        }
        String spendMode = null;
        if (req.has("spendMode")) {
            spendMode = googleSpendModeWire(req.opt("spendMode"));
            if (spendMode == null) return googleError("invalid_value");
            delta.put("googleSpendMode", spendMode);
        }
        long budgetCents = -1L;
        if (req.has("monthlyBudgetCents")) {
            Object value = req.opt("monthlyBudgetCents");
            if (!(value instanceof Number)) return googleError("invalid_value");
            double requested = ((Number) value).doubleValue();
            if (Double.isNaN(requested) || Double.isInfinite(requested) || requested <= 0) {
                return googleError("invalid_value");
            }
            if (Math.round(requested) < GOOGLE_MIN_BUDGET_CENTS) return googleError("budget_min");
            budgetCents = Math.min(GOOGLE_MAX_BUDGET_CENTS, Math.round(requested));
            delta.put("googleMonthlyBudgetCents", (int) budgetCents);
        }
        // Paid mode always carries a budget: from this request or the one already stored.
        if ("paid".equals(spendMode) && budgetCents < 0 && GoogleMapsConfig.load().getMonthlyBudgetCents() <= 0) {
            return googleError("budget_required");
        }
        if (delta.length() == 0) return googleError("no_settings");
        boolean saved = GoogleMapsConfig.saveSettings(delta);
        JSONObject response = new JSONObject();
        response.put("success", saved);
        if (!saved) response.put("error", "save_failed");
        return response;
    }

    private static JSONObject googleClear() throws Exception {
        synchronized (GOOGLE_LOCK) {
            boolean cleared = GoogleMapsConfig.clear();
            googleLastProbe = null;
            if (cleared) logger.info(TAG + ": cleared Google key");
            JSONObject response = new JSONObject();
            response.put("success", cleared);
            if (!cleared) response.put("error", "clear_failed");
            return response;
        }
    }

    /** POST usage: the app's budget snapshot, mirrored for the settings page. */
    private static JSONObject googleUsage(String body) throws Exception {
        boolean stored = GoogleUsageMirror.store(body);
        JSONObject response = new JSONObject();
        response.put("success", stored);
        if (!stored) response.put("error", "invalid_snapshot");
        return response;
    }

    private static boolean rememberGoogleProbe(GoogleKeyValidator.ValidationResult result,
                                               JSONObject probe, long validatedMs) throws Exception {
        boolean saved = GoogleMapsConfig.saveApiState(result.getStates(), validatedMs);
        googleLastProbe = saved
                ? new GoogleProbeMemo(probe.getJSONObject("apiState"), probe.getJSONObject("apiReason"))
                : null;
        if (!saved) logger.warn(TAG + ": Google API state not saved");
        return saved;
    }

    private static JSONObject googleProbeJson(GoogleKeyValidator.ValidationResult result) throws Exception {
        JSONObject reasons = new JSONObject();
        for (GoogleApi api : GoogleApi.values()) {
            String reason = result.getReasons().get(api);
            reasons.put(googleWire(api), reason != null ? reason : GoogleKeyValidator.REASON_UNEXPECTED);
        }
        JSONObject response = new JSONObject();
        response.put("apiState", googleStateJson(result.getStates()));
        response.put("apiReason", reasons);
        return response;
    }

    private static JSONObject googleStateJson(Map<GoogleApi, GoogleApiState> states) throws Exception {
        JSONObject json = new JSONObject();
        for (GoogleApi api : GoogleApi.values()) {
            GoogleApiState state = states != null ? states.get(api) : null;
            json.put(googleWire(api), state != null ? googleWire(state) : "unknown");
        }
        return json;
    }

    // Reasons live only in memory, so they are served only while the saved state still matches.
    private static JSONObject googleReasonsFor(JSONObject apiState) throws Exception {
        JSONObject reasons = new JSONObject();
        GoogleProbeMemo memo = googleLastProbe;
        if (memo == null) return reasons;
        for (GoogleApi api : GoogleApi.values()) {
            String wire = googleWire(api);
            if (memo.reasons.has(wire) && memo.states.optString(wire).equals(apiState.optString(wire))) {
                reasons.put(wire, memo.reasons.optString(wire));
            }
        }
        return reasons;
    }

    private static JSONObject parseGoogleBody(String body) {
        try {
            return new JSONObject(body == null || body.trim().isEmpty() ? "{}" : body);
        } catch (Exception | StackOverflowError e) {
            // Deeply nested input recurses in the parser; treat it as a bad request.
            return null;
        }
    }

    private static boolean isOverGoogleBodyLimit(String body) {
        if (body == null) return false;
        return body.length() > GOOGLE_MAX_BODY_BYTES
                || body.getBytes(StandardCharsets.UTF_8).length > GOOGLE_MAX_BODY_BYTES;
    }

    private static String googleWire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String googlePricingWire(Object value) {
        if (!(value instanceof String)) return null;
        String pricing = ((String) value).trim().toLowerCase(Locale.ROOT);
        return pricing.equals("global") || pricing.equals("india") ? pricing : null;
    }

    private static String googleSpendModeWire(Object value) {
        if (!(value instanceof String)) return null;
        String mode = ((String) value).trim().toLowerCase(Locale.ROOT);
        return mode.equals("free") || mode.equals("paid") ? mode : null;
    }

    // India when the network or SIM country list (one entry per slot) includes "in".
    private static String googleSuggestedPricing() {
        String[] props = {"gsm.operator.iso-country", "gsm.sim.operator.iso-country"};
        for (String prop : props) {
            for (String country : systemProperty(prop).split(",")) {
                if (country.trim().equalsIgnoreCase("in")) return "india";
            }
        }
        return "global";
    }

    private static String systemProperty(String key) {
        try {
            Object value = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return value instanceof String ? (String) value : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static JSONObject googleError(String code) throws Exception {
        JSONObject response = new JSONObject();
        response.put("success", false);
        response.put("error", code);
        return response;
    }

    private static void sendGoogleJson(OutputStream out, int status, JSONObject response) throws Exception {
        HttpResponse.sendJsonNoCors(out, status, response.toString());
    }
}
