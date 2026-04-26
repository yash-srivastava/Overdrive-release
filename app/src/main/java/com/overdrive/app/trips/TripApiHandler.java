package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.monitor.VehicleDataMonitor;
import com.overdrive.app.storage.StorageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone handler for /api/trips/* HTTP requests.
 *
 * Processes trip analytics API endpoints and returns JSONObject responses.
 * Called from HttpServer's serve() method when the URI starts with "/api/trips".
 *
 * <p><b>HttpServer wiring note:</b> To integrate this handler, add the following
 * to HttpServer.routeToHandlers():
 * <pre>
 *   // Trip Analytics API
 *   if (path.startsWith("/api/trips")) {
 *       return tripApiHandler.handle(method, path, body, out);
 *   }
 * </pre>
 * where tripApiHandler is an instance created with the TripAnalyticsManager reference.
 * The actual HttpServer modification is done in a separate task.</p>
 */
public class TripApiHandler {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripApiHandler");

    // URI patterns for extracting trip IDs
    private static final Pattern TRIP_ID_PATTERN = Pattern.compile("^/api/trips/(\\d+)$");
    private static final Pattern TRIP_TELEMETRY_PATTERN = Pattern.compile("^/api/trips/(\\d+)/telemetry$");
    private static final Pattern TRIP_SIMILAR_PATTERN = Pattern.compile("^/api/trips/(\\d+)/similar$");
    private static final Pattern TRIP_GPS_PATTERN = Pattern.compile("^/api/trips/(\\d+)/gps$");

    private final TripAnalyticsManager manager;

    public TripApiHandler(TripAnalyticsManager manager) {
        this.manager = manager;
    }

    /**
     * Handle a /api/trips/* request.
     *
     * @param uri    The full request URI (e.g., "/api/trips?days=7", "/api/trips/123/telemetry")
     * @param method HTTP method (GET, POST, DELETE)
     * @param params Query parameters parsed from the URI (may be empty)
     * @param body   Request body (for POST requests), may be null
     * @return JSONObject response with "success" field; error responses include "_status" field
     */
    public JSONObject handleRequest(String uri, String method, Map<String, String> params, String body) {
        try {
            // Strip query string from URI for path matching
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf("?")) : uri;

            // Parse query params from URI if not provided externally
            if (params == null) {
                params = new HashMap<>();
            }
            if (uri.contains("?")) {
                parseQueryParams(uri.substring(uri.indexOf("?") + 1), params);
            }

            // Route: GET /api/trips/summary
            if (path.equals("/api/trips/summary") && "GET".equals(method)) {
                return handleGetSummary(params);
            }

            // Route: GET /api/trips/dna
            if (path.equals("/api/trips/dna") && "GET".equals(method)) {
                return handleGetDna(params);
            }

            // Route: GET /api/trips/range
            if (path.equals("/api/trips/range") && "GET".equals(method)) {
                return handleGetRange();
            }

            // Route: GET/POST /api/trips/config
            if (path.equals("/api/trips/config")) {
                if ("GET".equals(method)) return handleGetConfig();
                if ("POST".equals(method)) return handlePostConfig(body);
            }

            // Route: GET/POST /api/trips/storage
            if (path.equals("/api/trips/storage")) {
                if ("GET".equals(method)) return handleGetStorage();
                if ("POST".equals(method)) return handlePostStorage(body);
            }

            // Route: GET /api/trips/{id}/telemetry
            Matcher telemetryMatcher = TRIP_TELEMETRY_PATTERN.matcher(path);
            if (telemetryMatcher.matches() && "GET".equals(method)) {
                long tripId = Long.parseLong(telemetryMatcher.group(1));
                return handleGetTelemetry(tripId);
            }

            // Route: GET /api/trips/{id}/similar
            Matcher similarMatcher = TRIP_SIMILAR_PATTERN.matcher(path);
            if (similarMatcher.matches() && "GET".equals(method)) {
                long tripId = Long.parseLong(similarMatcher.group(1));
                return handleGetSimilarTrips(tripId);
            }

            // Route: GET /api/trips/{id}/gps
            Matcher gpsMatcher = TRIP_GPS_PATTERN.matcher(path);
            if (gpsMatcher.matches() && "GET".equals(method)) {
                long tripId = Long.parseLong(gpsMatcher.group(1));
                return handleGetGpsTrace(tripId);
            }

            // Route: GET/DELETE /api/trips/{id}
            Matcher tripIdMatcher = TRIP_ID_PATTERN.matcher(path);
            if (tripIdMatcher.matches()) {
                long tripId = Long.parseLong(tripIdMatcher.group(1));
                if ("GET".equals(method)) return handleGetTrip(tripId);
                if ("DELETE".equals(method)) return handleDeleteTrip(tripId);
            }

            // Route: GET /api/trips (list)
            if ((path.equals("/api/trips") || path.equals("/api/trips/")) && "GET".equals(method)) {
                return handleListTrips(params);
            }

            // No matching route
            return errorResponse("Not found", 404);

        } catch (Exception e) {
            logger.error("Error handling request: " + uri, e);
            return errorResponse("Internal error: " + e.getMessage(), 500);
        }
    }

    // ==================== ENDPOINT HANDLERS ====================

    /**
     * GET /api/trips — list trips.
     * Query: days (default 7), limit (default 50).
     */
    private JSONObject handleListTrips(Map<String, String> params) {
        int days = getIntParam(params, "days", 7);
        int limit = getIntParam(params, "limit", 50);

        TripDatabase db = manager.getDatabase();
        if (db == null) {
            return errorResponse("Trip database not available", 500);
        }

        List<TripRecord> trips = db.getTrips(days, limit);
        JSONArray tripsArray = new JSONArray();
        for (TripRecord trip : trips) {
            enrichTripEnergy(trip);
            tripsArray.put(trip.toSummaryJson());
        }

        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("trips", tripsArray);
        } catch (Exception e) {
            logger.error("Error building trips list response", e);
        }
        return response;
    }

    /**
     * GET /api/trips/{id} — single trip with micro-moments.
     */
    private JSONObject handleGetTrip(long tripId) {
        TripDatabase db = manager.getDatabase();
        if (db == null) {
            return errorResponse("Trip database not available", 500);
        }

        TripRecord trip = db.getTrip(tripId);
        if (trip == null) {
            return errorResponse("Trip not found", 404);
        }

        enrichTripEnergy(trip);

        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("trip", trip.toJson());
        } catch (Exception e) {
            logger.error("Error building trip detail response", e);
        }
        return response;
    }

    /**
     * GET /api/trips/{id}/telemetry — decompress + return telemetry array.
     */
    private JSONObject handleGetTelemetry(long tripId) {
        TripDatabase db = manager.getDatabase();
        if (db == null) {
            return errorResponse("Trip database not available", 500);
        }

        TripRecord trip = db.getTrip(tripId);
        if (trip == null) {
            return errorResponse("Trip not found", 404);
        }

        // Read telemetry file
        String filePath = trip.telemetryFilePath;
        if (filePath == null || filePath.isEmpty()) {
            return errorResponse("Telemetry data unavailable", 410);
        }

        File telemetryFile = new File(filePath);
        if (!telemetryFile.exists()) {
            return errorResponse("Telemetry data unavailable", 410);
        }

        List<TelemetrySample> samples = TelemetryStore.readFromFile(telemetryFile);
        JSONArray telemetryArray = new JSONArray();
        for (TelemetrySample sample : samples) {
            telemetryArray.put(sample.toJson());
        }

        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("telemetry", telemetryArray);
        } catch (Exception e) {
            logger.error("Error building telemetry response", e);
        }
        return response;
    }

    /**
     * DELETE /api/trips/{id} — delete trip record + telemetry file.
     */
    private JSONObject handleDeleteTrip(long tripId) {
        TripDatabase db = manager.getDatabase();
        if (db == null) {
            return errorResponse("Trip database not available", 500);
        }

        TripRecord trip = db.getTrip(tripId);
        if (trip == null) {
            return errorResponse("Trip not found", 404);
        }

        // Delete telemetry file if it exists
        if (trip.telemetryFilePath != null && !trip.telemetryFilePath.isEmpty()) {
            File telemetryFile = new File(trip.telemetryFilePath);
            if (telemetryFile.exists()) {
                if (telemetryFile.delete()) {
                    logger.info("Deleted telemetry file: " + telemetryFile.getName());
                } else {
                    logger.warn("Failed to delete telemetry file: " + telemetryFile.getName());
                }
            }
        }

        // Delete database record
        boolean deleted = db.deleteTrip(tripId);
        if (!deleted) {
            return errorResponse("Failed to delete trip", 500);
        }

        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
        } catch (Exception e) {
            logger.error("Error building delete response", e);
        }
        return response;
    }

    /**
     * GET /api/trips/summary — weekly rollup.
     * Query: days (default 7).
     */
    private JSONObject handleGetSummary(Map<String, String> params) {
        int days = getIntParam(params, "days", 7);
        // Convert days to approximate weeks (round up)
        int weeks = Math.max(1, (days + 6) / 7);

        TripDatabase db = manager.getDatabase();
        if (db == null) {
            return errorResponse("Trip database not available", 500);
        }

        List<WeeklyRollup> rollups = db.getRecentWeeklyRollups(weeks);
        JSONArray rollupsArray = new JSONArray();
        for (WeeklyRollup rollup : rollups) {
            rollupsArray.put(rollup.toJson());
        }

        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("summary", rollupsArray);
        } catch (Exception e) {
            logger.error("Error building summary response", e);
        }
        return response;
    }

    /**
     * GET /api/trips/dna — average DNA scores.
     * Query: days (default 30).
     */
    private JSONObject handleGetDna(Map<String, String> params) {
        int days = getIntParam(params, "days", 30);

        TripDatabase db = manager.getDatabase();
        if (db == null) {
            return errorResponse("Trip database not available", 500);
        }

        DnaScores scores = db.getAverageDna(days);
        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            if (scores != null) {
                response.put("dna", scores.toJson());
            } else {
                response.put("dna", JSONObject.NULL);
            }
        } catch (Exception e) {
            logger.error("Error building DNA response", e);
        }
        return response;
    }

    /**
     * GET /api/trips/range — personalized range estimate.
     * Reads current SoC from VehicleDataMonitor, speed from GpsMonitor,
     * temp from VehicleDataMonitor, DNA from database.
     */
    private JSONObject handleGetRange() {
        RangeEstimator estimator = manager.getRangeEstimator();
        TripDatabase db = manager.getDatabase();

        if (estimator == null || db == null) {
            JSONObject response = new JSONObject();
            try {
                response.put("success", true);
                response.put("range", JSONObject.NULL);
                response.put("message", "Not enough data");
            } catch (Exception e) {
                logger.error("Error building range response", e);
            }
            return response;
        }

        try {
            // Read current conditions from existing monitors
            double currentSoc = 0;
            try {
                com.overdrive.app.monitor.BatterySocData socData =
                        VehicleDataMonitor.getInstance().getBatterySoc();
                if (socData != null) {
                    currentSoc = socData.socPercent;
                }
            } catch (Exception e) {
                logger.debug("Could not read SoC: " + e.getMessage());
            }

            double currentSpeed = 0;
            try {
                currentSpeed = GpsMonitor.getInstance().getSpeed() * 3.6; // m/s to km/h
            } catch (Exception e) {
                logger.debug("Could not read speed: " + e.getMessage());
            }

            int extTemp = 20; // Default mild temperature
            try {
                // Read external temperature from BYD instrument device
                android.hardware.bydauto.instrument.BYDAutoInstrumentDevice instrumentDevice =
                        android.hardware.bydauto.instrument.BYDAutoInstrumentDevice.getInstance(null);
                if (instrumentDevice != null) {
                    extTemp = instrumentDevice.getOutCarTemperature();
                }
            } catch (Exception e) {
                logger.debug("Could not read external temp: " + e.getMessage());
            }

            int dnaOverall = 50; // Default mid-range
            try {
                DnaScores dna = db.getAverageDna(30);
                if (dna != null) {
                    dnaOverall = dna.getOverall();
                }
            } catch (Exception e) {
                logger.debug("Could not read DNA scores: " + e.getMessage());
            }

            RangeEstimate estimate = estimator.estimate(currentSoc, currentSpeed, extTemp, dnaOverall);

            JSONObject response = new JSONObject();
            response.put("success", true);
            if (estimate != null) {
                // Add built-in range for comparison
                try {
                    com.overdrive.app.monitor.DrivingRangeData rangeData =
                            VehicleDataMonitor.getInstance().getDrivingRange();
                    if (rangeData != null) {
                        estimate.builtInRangeKm = rangeData.elecRangeKm;
                    }
                } catch (Exception e) {
                    logger.debug("Could not read built-in range: " + e.getMessage());
                }
                response.put("range", estimate.toJson());
            } else {
                response.put("range", JSONObject.NULL);
                response.put("message", "Not enough data");
            }
            return response;

        } catch (Exception e) {
            logger.error("Error computing range estimate", e);
            JSONObject response = new JSONObject();
            try {
                response.put("success", true);
                response.put("range", JSONObject.NULL);
                response.put("message", "Not enough data");
            } catch (Exception ex) {
                // ignore
            }
            return response;
        }
    }

    /**
     * GET /api/trips/config — get config state.
     */
    private JSONObject handleGetConfig() {
        TripConfig config = manager.getConfig();
        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            if (config != null) {
                response.put("config", config.toJson());
            } else {
                JSONObject defaultConfig = new JSONObject();
                defaultConfig.put("enabled", false);
                response.put("config", defaultConfig);
            }
        } catch (Exception e) {
            logger.error("Error building config response", e);
        }
        return response;
    }

    /**
     * POST /api/trips/config — set config.
     * Body: { enabled: bool }
     */
    private JSONObject handlePostConfig(String body) {
        try {
            JSONObject bodyJson = new JSONObject(body != null ? body : "{}");

            if (bodyJson.has("enabled")) {
                boolean enabled = bodyJson.getBoolean("enabled");
                manager.onConfigChanged(enabled);
            }

            // Electricity rate and currency
            TripConfig config = manager.getConfig();
            if (config != null) {
                if (bodyJson.has("electricityRate")) {
                    config.setElectricityRate(bodyJson.getDouble("electricityRate"));
                }
                if (bodyJson.has("currency")) {
                    config.setCurrency(bodyJson.getString("currency"));
                }
                config.save();
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            return response;

        } catch (Exception e) {
            logger.error("Error setting config: " + e.getMessage());
            return errorResponse("Invalid request body: " + e.getMessage(), 400);
        }
    }

    /**
     * GET /api/trips/storage — get storage settings.
     * Returns: { storageType, limitMb, usedMb, sdCardAvailable, tripsCount }
     */
    private JSONObject handleGetStorage() {
        StorageManager sm = StorageManager.getInstance();
        TripDatabase db = manager.getDatabase();

        JSONObject storage = new JSONObject();
        try {
            storage.put("storageType", sm.getTripsStorageType().name());
            storage.put("limitMb", sm.getTripsLimitMb());
            double usedBytes = sm.getTripsSize();
            double usedMb = usedBytes / (1024.0 * 1024.0);
            if (usedMb < 0.1 && usedBytes > 0) {
                // Show in KB for small sizes
                storage.put("usedMb", Math.round(usedBytes / 1024.0 * 10.0) / 10.0);
                storage.put("usedUnit", "KB");
            } else {
                storage.put("usedMb", Math.round(usedMb * 10.0) / 10.0);
                storage.put("usedUnit", "MB");
            }
            storage.put("sdCardAvailable", sm.isSdCardAvailable());
            storage.put("tripsCount", db != null ? db.getTripCount() : 0);
            storage.put("storagePath", sm.getTripsPath());
        } catch (Exception e) {
            logger.error("Error reading storage settings", e);
        }

        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("storage", storage);
        } catch (Exception e) {
            logger.error("Error building storage response", e);
        }
        return response;
    }

    /**
     * POST /api/trips/storage — set storage settings.
     * Body: { storageType?: "INTERNAL"|"SD_CARD", storageLimitMb?: number }
     */
    private JSONObject handlePostStorage(String body) {
        try {
            JSONObject bodyJson = new JSONObject(body != null ? body : "{}");
            StorageManager sm = StorageManager.getInstance();

            if (bodyJson.has("storageType")) {
                String typeStr = bodyJson.getString("storageType");
                StorageManager.StorageType type = "SD_CARD".equalsIgnoreCase(typeStr)
                        ? StorageManager.StorageType.SD_CARD
                        : StorageManager.StorageType.INTERNAL;
                sm.setTripsStorageType(type);
            }

            if (bodyJson.has("storageLimitMb")) {
                long limitMb = bodyJson.getLong("storageLimitMb");
                sm.setTripsLimitMb(limitMb);
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            return response;

        } catch (Exception e) {
            logger.error("Error setting storage: " + e.getMessage());
            return errorResponse("Invalid request body: " + e.getMessage(), 400);
        }
    }

    // ==================== ROUTE COMPARISON ENDPOINTS ====================

    /**
     * GET /api/trips/{id}/similar — find trips on the same route.
     * Matches: start/end within ~1.1km (0.01°), distance ±25%.
     */
    private JSONObject handleGetSimilarTrips(long tripId) {
        TripDatabase db = manager.getDatabase();
        if (db == null) return errorResponse("Trip database not available", 500);

        TripRecord trip = db.getTrip(tripId);
        if (trip == null) return errorResponse("Trip not found", 404);

        double startLat = trip.startLat, startLon = trip.startLon;
        double endLat = trip.endLat, endLon = trip.endLon;
        double dist = trip.distanceKm;

        if (startLat == 0 && startLon == 0) {
            return errorResponse("Trip has no GPS data", 400);
        }

        // Fast path: use route_id index if available
        List<TripRecord> candidates;
        boolean usingRouteFastPath = false;
        if (trip.routeId > 0) {
            candidates = db.getTripsByRoute(trip.routeId, 100);
            // If route only has this trip, fall back to full scan
            // (backfill may have split similar trips into different routes)
            if (candidates.size() <= 1) {
                candidates = db.getTrips(365, 500);
            } else {
                usingRouteFastPath = true;
            }
        } else {
            candidates = db.getTrips(365, 500);
        }

        JSONArray similar = new JSONArray();
        double bestEff = Double.MAX_VALUE;
        double worstEff = 0;
        long bestId = -1, worstId = -1;
        double sumEff = 0;
        int sumScore = 0, sumDuration = 0;
        double sumSpeed = 0, sumCost = 0;
        int count = 0;

        for (TripRecord t : candidates) {
            if (t.id == tripId) continue;

            // Apply geofence filter when doing full scan (not using route fast path)
            if (!usingRouteFastPath) {
                double sLat = t.startLat, sLon = t.startLon;
                double eLat = t.endLat, eLon = t.endLon;
                if (Math.abs(sLat - startLat) > 0.01 || Math.abs(sLon - startLon) > 0.01) continue;
                if (Math.abs(eLat - endLat) > 0.01 || Math.abs(eLon - endLon) > 0.01) continue;
            }

            double eff = t.efficiencySocPerKm;
            similar.put(t.toSummaryJson());
            sumEff += eff;
            sumScore += t.getOverallScore();
            sumDuration += t.durationSeconds;
            sumSpeed += t.avgSpeedKmh;
            sumCost += t.tripCost;
            count++;
            if (eff > 0 && eff < bestEff) { bestEff = eff; bestId = t.id; }
            if (eff > worstEff) { worstEff = eff; worstId = t.id; }
        }

        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("similar", similar);
            response.put("count", count);
            // Debug info
            response.put("_debug_routeId", trip.routeId);
            response.put("_debug_startLat", startLat);
            response.put("_debug_endLat", endLat);
            response.put("_debug_candidatesScanned", candidates.size());
            if (count > 0) {
                JSONObject stats = new JSONObject();
                stats.put("avgEfficiency", sumEff / count);
                stats.put("avgScore", sumScore / count);
                stats.put("avgDurationSeconds", sumDuration / count);
                stats.put("avgSpeedKmh", sumSpeed / count);
                stats.put("avgCost", sumCost / count);
                stats.put("bestTripId", bestId);
                stats.put("bestEfficiency", bestEff == Double.MAX_VALUE ? 0 : bestEff);
                stats.put("worstTripId", worstId);
                stats.put("worstEfficiency", worstEff);
                response.put("stats", stats);
            }
        } catch (Exception e) {
            logger.error("Error building similar trips response", e);
        }
        return response;
    }

    /**
     * GET /api/trips/{id}/gps — lightweight GPS-only trace for map overlay.
     * Returns [[lat,lon], ...] array — much smaller than full telemetry.
     */
    private JSONObject handleGetGpsTrace(long tripId) {
        TripDatabase db = manager.getDatabase();
        if (db == null) return errorResponse("Trip database not available", 500);

        TripRecord trip = db.getTrip(tripId);
        if (trip == null) return errorResponse("Trip not found", 404);

        String filePath = trip.telemetryFilePath;
        if (filePath == null || filePath.isEmpty()) {
            return errorResponse("Telemetry data unavailable", 410);
        }

        File telemetryFile = new File(filePath);
        if (!telemetryFile.exists()) {
            return errorResponse("Telemetry data unavailable", 410);
        }

        List<TelemetrySample> samples = TelemetryStore.readFromFile(telemetryFile);
        JSONArray gps = new JSONArray();
        for (TelemetrySample s : samples) {
            if (s.lat != 0 && s.lon != 0) {
                try {
                    JSONArray point = new JSONArray();
                    point.put(s.lat);
                    point.put(s.lon);
                    gps.put(point);
                } catch (Exception ignored) {}
            }
        }

        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("gps", gps);
        } catch (Exception e) {
            logger.error("Error building GPS trace response", e);
        }
        return response;
    }

    // ==================== UTILITY METHODS ====================

    /**
     * Parse query parameters from a query string (e.g., "days=7&limit=50").
     */
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

    /**
     * Enrich a trip record with estimated energy if BMS kWh data wasn't available.
     * Uses the SohEstimator's calibrated nominal capacity (same as VehicleDataMonitor).
     * This ensures old trips without kWh readings still show cost in the UI.
     */
    private void enrichTripEnergy(TripRecord trip) {
        // Already has energy data — nothing to do
        if (trip.getEnergyUsedKwh() > 0) return;
        
        // Need SoC delta to estimate
        if (trip.socStart <= 0 || trip.socEnd <= 0 || trip.socStart <= trip.socEnd) return;
        
        try {
            com.overdrive.app.abrp.SohEstimator soh = 
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (soh != null && soh.getNominalCapacityKwh() > 0) {
                double nominal = soh.getNominalCapacityKwh();
                double sohPercent = soh.hasEstimate() ? soh.getCurrentSoh() : 100.0;
                double usableKwh = nominal * (sohPercent / 100.0);
                double socDelta = trip.socStart - trip.socEnd;
                double estimatedEnergy = (socDelta / 100.0) * usableKwh;
                
                // Update the in-memory record (not persisted to DB — just for API response)
                trip.kwhStart = (trip.socStart / 100.0) * usableKwh;
                trip.kwhEnd = (trip.socEnd / 100.0) * usableKwh;
                
                // Compute cost if rate is available
                TripConfig config = manager.getConfig();
                if (config != null && config.getElectricityRate() > 0 && trip.tripCost <= 0) {
                    trip.electricityRate = config.getElectricityRate();
                    trip.currency = config.getCurrency();
                    trip.tripCost = estimatedEnergy * trip.electricityRate;
                }
                
                if (trip.distanceKm > 0) {
                    trip.energyPerKm = estimatedEnergy / trip.distanceKm;
                }
            }
        } catch (Exception e) {
            // SohEstimator not available — leave as-is
        }
    }

    /**
     * Get an integer query parameter with a default value.
     */
    private int getIntParam(Map<String, String> params, String key, int defaultValue) {
        String value = params.get(key);
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Build an error response with the given message and HTTP status code.
     */
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
