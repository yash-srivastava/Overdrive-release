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
    private static final Pattern TRIP_RESCORE_PATTERN = Pattern.compile("^/api/trips/(\\d+)/rescore$");

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

            // Route: GET /api/trips/bootstrap — composite first-paint payload.
            // Must precede the more general routes so it isn't shadowed.
            if (path.equals("/api/trips/bootstrap") && "GET".equals(method)) {
                return handleGetBootstrap(params);
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

            // Route: POST /api/trips/recover — rebuild trip rows from surviving
            // .jsonl.gz telemetry files whose DB row was lost (e.g. the SD-drop
            // reaper bug). User-triggered from the Trips page, NOT automatic.
            // Runs on a background thread (the scan can take minutes on a large
            // FUSE-bridged SD) and returns immediately; the UI polls
            // GET /api/trips/recover/status for progress/result.
            if (path.equals("/api/trips/recover") && "POST".equals(method)) {
                return handleStartRecoverTrips();
            }
            if (path.equals("/api/trips/recover/status") && "GET".equals(method)) {
                return handleRecoverStatus();
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

            // Route: POST /api/trips/{id}/rescore — recompute the DNA scores
            // of a finalized trip from its telemetry file (rows that recovery
            // closed before it learned to score, or after an engine change).
            Matcher rescoreMatcher = TRIP_RESCORE_PATTERN.matcher(path);
            if (rescoreMatcher.matches() && "POST".equals(method)) {
                long tripId = Long.parseLong(rescoreMatcher.group(1));
                return handleRescoreTrip(tripId);
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
     * GET /api/trips/bootstrap — composite first-paint payload for trips.html.
     *
     * <p>Returns {@code {success, bootstrap: {config, storage, dna, summary,
     * range, trips}}} in a single response by internally invoking the six
     * existing per-section handlers. This saves 6 sequential RTTs on
     * trips.html init; in practice this means the storage card paints in
     * &lt;100ms instead of the 10-20 minute window it took while
     * {@code getTripsSize()} walked the FUSE-mounted SD card. (The DB-backed
     * size accounting fixes the underlying walk; this endpoint cuts the
     * round-trip cost on top.)
     *
     * <p>Inner responses are stripped of {@code success} (the outer wrapper
     * carries it) and {@code _status} (an internal HTTP-status hint, not
     * client data). Other keys are preserved verbatim so the JS-side
     * {@code _applyXPayload} helpers can consume the same shape they get
     * from a direct fetch of each section.
     *
     * <p>If any inner handler errors out, the corresponding section is
     * replaced with {@code {error: "..."}} and the rest of the payload
     * still ships — the JS bootstrap path tolerates missing sections by
     * skipping the matching {@code _apply}.
     *
     * <p>Defaults match the JS loader call sites: {@code days=30} for DNA,
     * {@code days=7} for summary, {@code days=7&limit=20&offset=0} for the
     * trip list (matches {@code TRIPS.pageSize}).
     */
    private JSONObject handleGetBootstrap(Map<String, String> params) {
        JSONObject bootstrap = new JSONObject();
        JSONObject response = new JSONObject();
        try {
            bootstrap.put("config", invokeSectionStripped(this::handleGetConfig));
            bootstrap.put("storage", invokeSectionStripped(this::handleGetStorage));

            Map<String, String> dnaParams = new HashMap<>();
            dnaParams.put("days", "30");
            bootstrap.put("dna", invokeSectionStripped(() -> handleGetDna(dnaParams)));

            Map<String, String> summaryParams = new HashMap<>();
            summaryParams.put("days", "7");
            bootstrap.put("summary", invokeSectionStripped(() -> handleGetSummary(summaryParams)));

            bootstrap.put("range", invokeSectionStripped(this::handleGetRange));

            Map<String, String> tripsParams = new HashMap<>();
            tripsParams.put("days", "7");
            tripsParams.put("limit", "20");
            tripsParams.put("offset", "0");
            bootstrap.put("trips", invokeSectionStripped(() -> handleListTrips(tripsParams)));

            response.put("success", true);
            response.put("bootstrap", bootstrap);
        } catch (Exception e) {
            logger.error("Error building bootstrap response", e);
            // Ensure caller still gets a valid envelope even on partial failure.
            try {
                if (!response.has("success")) response.put("success", false);
                if (!response.has("bootstrap")) response.put("bootstrap", bootstrap);
                response.put("error", e.getMessage() != null ? e.getMessage() : "bootstrap failed");
            } catch (Exception ignored) {}
        }
        return response;
    }

    /**
     * Functional helper for {@link #handleGetBootstrap}. Invokes the given
     * section handler, removes the {@code success} and {@code _status}
     * fields, and surfaces handler exceptions as a {@code {error: "..."}}
     * stub so the bootstrap response always has a value for every section.
     */
    private JSONObject invokeSectionStripped(java.util.function.Supplier<JSONObject> handler) {
        JSONObject section;
        try {
            section = handler.get();
        } catch (Exception e) {
            logger.warn("Bootstrap section failed: " + e.getMessage());
            JSONObject err = new JSONObject();
            try { err.put("error", e.getMessage() != null ? e.getMessage() : "section failed"); }
            catch (Exception ignored) {}
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
     * GET /api/trips — list trips.
     * Query: days (default 7), limit (default 50), offset (default 0).
     *
     * <p>Pagination via offset. A client doing Load More keeps the same
     * {@code days} and increments {@code offset} by the size of the previous
     * response. The server returns up to {@code limit} rows starting at
     * {@code offset}; a short page (length &lt; limit) signals end-of-data.
     */
    private JSONObject handleListTrips(Map<String, String> params) {
        int days = getIntParam(params, "days", 7);
        int limit = getIntParam(params, "limit", 50);
        int offset = getIntParam(params, "offset", 0);
        // Custom date range (epoch ms). When either bound is present we take
        // the explicit-range path; otherwise fall back to the "last N days"
        // window so existing callers (and the day-preset filters) are unchanged.
        long fromMs = getLongParam(params, "from", 0L);
        long toMs = getLongParam(params, "to", 0L);

        TripDatabase db = manager.getDatabase();
        if (db == null) {
            return errorResponse("Trip database not available", 500);
        }

        List<TripRecord> trips = (fromMs > 0 || toMs > 0)
                ? db.getTripsBetween(fromMs, toMs, limit, offset)
                : db.getTripsWithoutMoments(days, limit, offset);
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

    // Background-recovery state. The scan can take minutes on a large FUSE SD,
    // so POST /api/trips/recover validates + starts a worker and returns
    // immediately; the UI polls GET /api/trips/recover/status. Single global
    // state is fine — recovery is single-flighted in TripDatabase anyway.
    private static final java.util.concurrent.atomic.AtomicBoolean recoverRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile JSONObject lastRecoverResult = null;   // null until first run completes

    /**
     * POST /api/trips/recover — START a background rebuild of trip rows from
     * surviving {@code <id>.jsonl.gz} telemetry files whose DB row is missing
     * (history lost to the SD-drop reaper bug; the files were never touched).
     * Validates synchronously (fast), then runs the scan off the HTTP thread.
     * Returns {started:true} or {started:false,running:true} if already going.
     */
    private JSONObject handleStartRecoverTrips() {
        final TripDatabase db = manager.getDatabase();
        if (db == null) {
            return errorResponse("Trip database not available", 500);
        }

        final com.overdrive.app.storage.StorageManager sm;
        try {
            sm = com.overdrive.app.storage.StorageManager.getInstance();
        } catch (Throwable e) {
            return errorResponse("Trips storage not available", 500);
        }

        // Configured trips volume is external but not mounted → can't read the
        // files; return the "insert the card" 409 FIRST (don't gate on the dir
        // scan: getAllTripsDirs always includes the internal fallback dir).
        boolean configuredExternalButUnmounted = false;
        try {
            com.overdrive.app.storage.StorageManager.StorageType configured = sm.getTripsStorageType();
            if (configured == com.overdrive.app.storage.StorageManager.StorageType.SD_CARD && !sm.isSdCardAvailable()) {
                configuredExternalButUnmounted = true;
            } else if (configured == com.overdrive.app.storage.StorageManager.StorageType.USB && !sm.isUsbAvailable()) {
                configuredExternalButUnmounted = true;
            }
        } catch (Throwable ignored) {}
        if (configuredExternalButUnmounted) {
            return errorResponse(
                "Trip storage isn't available. Your trips are saved on a removable "
                + "card that isn't detected right now — insert it and try again.", 409);
        }

        final java.util.List<java.io.File> dirs = new java.util.ArrayList<>();
        try {
            java.util.List<java.io.File> resolved = sm.getAllTripsDirs();
            if (resolved != null) dirs.addAll(resolved);
        } catch (Throwable ignored) {}
        // The in-flight journal lives on internal storage now; a crash leaves
        // its file there, so the manual recover must scan that dir as well.
        try {
            java.io.File journalDir = sm.getTripJournalDir();
            if (journalDir != null && !dirs.contains(journalDir)) dirs.add(journalDir);
        } catch (Throwable ignored) {}
        boolean haveAnyDir = false;
        if (dirs != null) {
            for (java.io.File d : dirs) { if (d != null && d.isDirectory()) { haveAnyDir = true; break; } }
        }
        if (!haveAnyDir) {
            return errorResponse("Trip storage isn't available right now.", 409);
        }

        // Single-flight at the handler level too (in addition to TripDatabase's
        // own guard) so we don't spawn a redundant worker.
        if (!recoverRunning.compareAndSet(false, true)) {
            JSONObject busy = new JSONObject();
            try { busy.put("started", false); busy.put("running", true);
                  busy.put("message", "Recovery is already running…"); } catch (Exception ignored) {}
            return busy;
        }
        lastRecoverResult = null;

        Thread worker = new Thread(() -> {
            try {
                TripDatabase.RecoveryResult r = db.recoverTripsFromDisk(dirs);
                if (r.recovered > 0) {
                    try { sm.ensureTripsSpace(0); }   // re-enforce limit + refresh size cache
                    catch (Exception ex) { logger.warn("Async trips cleanup after recovery failed: " + ex.getMessage()); }
                }
                // Also fill in DNA scores for finalized rows that recovery closed
                // before it learned to score them (all five sub-scores at 0). The
                // user pressed "Recover" because a trip looks incomplete — this
                // is the row they are looking at.
                int rescored = 0;
                try { rescored = db.rescoreUnscoredTrips(50); }
                catch (Throwable ex) { logger.warn("Rescore sweep after recovery failed: " + ex.getMessage()); }
                lastRecoverResult = buildRecoverResult(r, rescored);
            } catch (Throwable t) {
                logger.error("Trips recovery worker failed", t);
                JSONObject err = new JSONObject();
                try { err.put("success", false); err.put("done", true);
                      err.put("message", "Recovery failed: " + t.getMessage()); } catch (Exception ignored) {}
                lastRecoverResult = err;
            } finally {
                recoverRunning.set(false);
            }
        }, "TripsRecover");
        // start() can throw (OutOfMemoryError "unable to create native thread"
        // under thread-count/ulimit pressure) BEFORE the runnable's finally can
        // reset recoverRunning. Without this guard the single-flight latch would
        // stay true for the daemon's life, wedging ALL future recovery (every
        // POST loses the CAS, status forever reports running). Catch Throwable
        // (the OOM case is an Error, not an Exception) and release the latch.
        try {
            worker.start();
        } catch (Throwable t) {
            recoverRunning.set(false);
            logger.error("Failed to start TripsRecover worker", t);
            return errorResponse("Could not start recovery — try again.", 500);
        }

        JSONObject started = new JSONObject();
        try { started.put("started", true); started.put("running", true); } catch (Exception ignored) {}
        return started;
    }

    /**
     * GET /api/trips/recover/status — poll the background recovery. Returns
     * {running:true} while in progress, or the final {done:true, success,
     * scanned, recovered, skipped, message} once complete.
     */
    private JSONObject handleRecoverStatus() {
        JSONObject resp = new JSONObject();
        try {
            if (recoverRunning.get()) {
                resp.put("running", true);
                resp.put("done", false);
            } else if (lastRecoverResult != null) {
                return lastRecoverResult;   // already carries done:true
            } else {
                resp.put("running", false);
                resp.put("done", false);    // no run yet this session
            }
        } catch (Exception e) {
            logger.error("Error building recover status", e);
        }
        return resp;
    }

    private JSONObject buildRecoverResult(TripDatabase.RecoveryResult r, int rescored) {
        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("done", true);
            response.put("scanned", r.scanned);
            response.put("recovered", r.recovered);
            response.put("skipped", r.skipped);
            response.put("rescored", rescored);
            String msg;
            if (r.recovered > 0) {
                msg = "Recovered " + r.recovered + " trip"
                        + (r.recovered == 1 ? "" : "s")
                        + " from telemetry files. End-of-trip battery, energy and cost "
                        + "details aren't available for recovered trips.";
                if (rescored > 0) {
                    msg += " Driving scores were also filled in for " + rescored
                            + " earlier trip" + (rescored == 1 ? "" : "s") + ".";
                }
            } else if (rescored > 0) {
                msg = "No missing trips found. Driving scores were filled in for " + rescored
                        + " trip" + (rescored == 1 ? "" : "s") + " that had none.";
            } else if (r.scanned == 0) {
                msg = "No telemetry files found in trip storage.";
            } else {
                msg = "No missing trips to recover — your history is already complete.";
            }
            response.put("message", msg);
        } catch (Exception e) {
            logger.error("Error building recover response", e);
        }
        return response;
    }

    /**
     * POST /api/trips/{id}/rescore — recompute a finalized trip's DNA scores
     * from its telemetry file and return the updated trip. 409 while the trip
     * is still in flight; 410 when there is no readable telemetry for it.
     */
    private JSONObject handleRescoreTrip(long tripId) {
        TripDatabase db = manager.getDatabase();
        if (db == null) {
            return errorResponse("Trip database not available", 500);
        }
        TripRecord existing = db.getTrip(tripId);
        if (existing == null) {
            return errorResponse("Trip not found", 404);
        }
        TripRecord active = manager.getActiveTrip();
        if (existing.endTime == 0 || (active != null && active.id == tripId)) {
            return errorResponse("Trip is still in progress", 409);
        }
        if (existing.telemetryFilePath == null || existing.telemetryFilePath.isEmpty()
                || TripDatabase.isImportedPath(existing.telemetryFilePath)
                || !new File(existing.telemetryFilePath).isFile()) {
            return errorResponse("Telemetry data unavailable", 410);
        }
        TripRecord updated = db.rescoreTripFromTelemetry(tripId);
        if (updated == null) {
            return errorResponse("Not enough telemetry to score this trip", 422);
        }
        enrichTripEnergy(updated);
        JSONObject response = new JSONObject();
        try {
            response.put("success", true);
            response.put("trip", updated.toJson());
        } catch (Exception e) {
            logger.error("Error building rescore response", e);
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

        // Delete the database record FIRST, then the file. This ordering is
        // self-healing across a crash between the two steps: an orphaned FILE
        // (row gone, file left) is reaped by the storage disk-walk reaper, but an
        // orphaned ROW (file gone, row left) would keep the trips size SUM inflated
        // and wedge the limit gate (it fires every 30s freeing nothing). The
        // previous file-then-row order created exactly that orphan-row window.
        boolean deleted = db.deleteTrip(tripId);
        if (!deleted) {
            return errorResponse("Failed to delete trip", 500);
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
            response.put("period", buildPeriodTotals(db, params, days));
        } catch (Exception e) {
            logger.error("Error building summary response", e);
        }
        return response;
    }

    /** Upper bound on the trips one period total reads (years of daily driving). */
    private static final int PERIOD_TOTALS_MAX_TRIPS = 5000;

    /**
     * Totals for exactly the trips GET /api/trips lists for the same window — the last
     * {@code days} days, or {@code from}/{@code to} — so the summary tiles agree with the list.
     * The weekly rollups only describe calendar weeks. Each trip is enriched and summed the way
     * the web page sums the trips it has loaded.
     */
    private JSONObject buildPeriodTotals(TripDatabase db, Map<String, String> params, int days)
            throws Exception {
        long fromMs = getLongParam(params, "from", 0L);
        long toMs = getLongParam(params, "to", 0L);
        boolean range = fromMs > 0 || toMs > 0;
        long from = range ? fromMs : System.currentTimeMillis() - ((long) days * 86400000L);
        long to = range ? toMs : Long.MAX_VALUE;
        List<TripRecord> trips = db.getTripsBetweenWithoutMoments(from, to, PERIOD_TOTALS_MAX_TRIPS);

        // The page's SoC fallback for trips without a kWh figure uses this pack size.
        double nominalKwh = 0;
        try {
            com.overdrive.app.abrp.SohEstimator soh =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (soh != null) nominalKwh = soh.getNominalCapacityKwh();
        } catch (Throwable ignored) {}

        double distance = 0, energy = 0, cost = 0, socDelta = 0, litres = 0, fuelCost = 0;
        long duration = 0, scoreSum = 0;
        int scored = 0;
        boolean anyPhev = false;
        for (TripRecord t : trips) {
            enrichTripEnergy(t);
            distance += t.distanceKm;
            duration += t.durationSeconds;
            double e = t.getEnergyUsedKwh();
            if (e <= 0 && t.socEnd > 0 && t.socStart > t.socEnd) {
                double pack = (t.kwhStart > 0 && t.socStart > 5) ? t.kwhStart / (t.socStart / 100.0)
                        : (nominalKwh > 0 ? nominalKwh : 82.56);
                e = ((t.socStart - t.socEnd) / 100.0) * pack;
            }
            energy += e;
            cost += t.tripCost;
            if (t.socEnd > 0 && t.socStart > t.socEnd) socDelta += t.socStart - t.socEnd;
            int a = t.anticipationScore, s = t.smoothnessScore, sd = t.speedDisciplineScore,
                    ef = t.efficiencyScore, c = t.consistencyScore;
            if (a != 0 || s != 0 || sd != 0 || ef != 0 || c != 0) {
                scoreSum += (a + s + sd + ef + c) / 5;
                scored++;
            }
            if (t.isPhev) anyPhev = true;
            litres += t.litresUsed;
            fuelCost += t.fuelCost;
        }

        JSONObject period = new JSONObject();
        period.put("tripCount", trips.size());
        period.put("totalDistanceKm", distance);
        period.put("totalDurationSeconds", duration);
        period.put("scoredTrips", scored);
        if (scored > 0) period.put("avgScore", scoreSum / scored);
        period.put("totalEnergyKwh", energy);
        period.put("totalSocDelta", socDelta);
        period.put("totalCost", cost);
        period.put("totalLitres", litres);
        period.put("totalFuelCost", fuelCost);
        period.put("anyPhev", anyPhev);
        period.put("truncated", trips.size() >= PERIOD_TOTALS_MAX_TRIPS);
        return period;
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
                // Distinguish "feature is off" from "not driven enough yet".
                // These are null precisely because initComponents() never ran,
                // which is only ever caused by tripAnalytics.enabled=false.
                // Reporting "Not enough data" for a disabled subsystem is what
                // made this state undiagnosable in the field: the UI showed an
                // innocuous empty state and the daemon logged no error.
                boolean disabled = !manager.isEnabled();
                response.put("enabled", !disabled);
                response.put("message", disabled
                    ? "Trip analytics is disabled"
                    : "Not enough data");
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
            boolean extTempFromSnapshot = false;
            try {
                // NORMALIZED TELEMETRY FIRST (R25). The previous code linked
                // android.hardware.bydauto.instrument.BYDAutoInstrumentDevice
                // as a DIRECT compile-time reference: on firmware without the
                // class the first execution throws NoClassDefFoundError — an
                // Error, not an Exception — straight through the old catch and
                // out of this handler. The snapshot read has no SDK linkage,
                // and the legacy fallback below is reflection-based so it can
                // only fail with catchable exceptions.
                com.overdrive.app.byd.BydDataCollector collector =
                        com.overdrive.app.byd.BydDataCollector.getInstance();
                if (collector.isInitialized()) {
                    com.overdrive.app.byd.BydVehicleData data = collector.getData();
                    if (data != null && !Double.isNaN(data.outsideTempC)) {
                        extTemp = (int) Math.round(data.outsideTempC);
                        extTempFromSnapshot = true;
                    }
                }
            } catch (Throwable t) {
                logger.debug("Could not read external temp from snapshot: " + t.getMessage());
            }
            if (!extTempFromSnapshot) {
                try {
                    // Legacy fallback: same source as before, linkage-safe.
                    Class<?> instrumentClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
                    java.lang.reflect.Method getInst = instrumentClass.getMethod("getInstance", android.content.Context.class);
                    Object instrumentDevice = getInst.invoke(null, (android.content.Context) null);
                    if (instrumentDevice != null) {
                        java.lang.reflect.Method getTemp = instrumentClass.getMethod("getOutCarTemperature");
                        extTemp = (Integer) getTemp.invoke(instrumentDevice);
                    }
                } catch (Throwable t) {
                    logger.debug("Could not read external temp: " + t.getMessage());
                }
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

            // PHEV — petrol leg. The LEARNED estimate needs a user-set tank
            // capacity AND enough fuel-mode trips to seed a bucket, so the
            // vehicle's own HAL fuel range ships alongside it as an immediate
            // fallback. Without it a PHEV shows no petrol range at all until
            // both preconditions are met — same precedence the dashboard uses.
            // Keys are simply absent on BEVs.
            try {
                com.overdrive.app.monitor.VehicleDataMonitor vdm =
                        com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
                if (vdm.isPhev()) {
                    com.overdrive.app.monitor.DrivingRangeData rangeData = vdm.getDrivingRange();
                    TripConfig cfg = manager.getConfig();
                    double halFuelKm = (rangeData != null) ? rangeData.fuelRangeKm : 0;
                    if (halFuelKm > 0) response.put("halFuelRangeKm", halFuelKm);
                    if (rangeData != null && rangeData.hasFuelPercent()) {
                        response.put("fuelPercent", rangeData.fuelPercent);
                    }

                    RangeEstimate fuelEst = null;
                    if (rangeData != null && rangeData.hasFuelPercent()
                            && cfg != null && cfg.getTankCapacityL() > 0) {
                        fuelEst = estimator.estimateFuelRange(
                                rangeData.fuelPercent, cfg.getTankCapacityL(),
                                currentSpeed, extTemp, dnaOverall);
                        if (fuelEst != null) {
                            fuelEst.builtInRangeKm = rangeData.fuelRangeKm;
                            response.put("fuelRange", fuelEst.toJson());
                        }
                    }

                    // Combined headline: learned figure per leg, else that leg's
                    // HAL value. Each leg falls back independently — using 0 for
                    // an unseeded electric leg would report a "combined" range
                    // smaller than the petrol leg alone.
                    double petrolKm = (fuelEst != null) ? fuelEst.predictedRangeKm : halFuelKm;
                    double evKm = (estimate != null) ? estimate.predictedRangeKm
                            : ((rangeData != null) ? rangeData.elecRangeKm : 0);
                    if (petrolKm > 0 && evKm > 0) {
                        response.put("totalRangeKm", evKm + petrolKm);
                    }
                }
            } catch (Exception e) {
                logger.debug("Fuel range estimate skipped: " + e.getMessage());
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
            JSONObject configJson;
            if (config != null) {
                // Re-read from UnifiedConfigManager before serving so a
                // rate/currency edit made on the Charging page (which writes the
                // shared tripAnalytics section) is reflected here without a
                // daemon restart. Without this, the in-memory TripConfig cached
                // at init serves a stale value and the two pages disagree.
                config.load();
                configJson = config.toJson();
            } else {
                configJson = new JSONObject();
                configJson.put("enabled", false);
            }
            // Surface the SOH-estimator's current nominal capacity so the
            // web summary/cost paths fall back to the user's pack rather
            // than the hard-coded 82.56 kWh default. Read defensively —
            // any failure leaves the field absent and JS falls through.
            try {
                com.overdrive.app.abrp.SohEstimator soh =
                    com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                if (soh != null) {
                    double nominal = soh.getNominalCapacityKwh();
                    if (nominal > 0) {
                        configJson.put("nominalKwh", nominal);
                        configJson.put("nominalSource", soh.getNominalSource());
                    }
                }
            } catch (Throwable t) {
                logger.debug("nominalKwh enrichment skipped: " + t.getMessage());
            }
            // Drivetrain probe — gates PHEV-only UI rows (tank capacity,
            // fuel price, l/100 km capsule on trip cards). Cached for 60 s
            // so this is effectively free on every config refresh.
            try {
                configJson.put("isPhev",
                        com.overdrive.app.monitor.VehicleDataMonitor.getInstance().isPhev());
            } catch (Throwable t) {
                logger.debug("isPhev probe skipped: " + t.getMessage());
            }
            // The rate the NEXT trip will actually be costed at: the last
            // charge's tariff when one exists, else the configured rate above.
            // Surfaced so the settings note can name the real number instead of
            // describing the rule abstractly — and so the UI can stay silent
            // when there's nothing to report (no charge history yet).
            try {
                org.json.JSONObject lastCharge = com.overdrive.app.monitor.SocHistoryDatabase
                        .getInstance().getLastChargeRate(60);
                if (lastCharge != null && lastCharge.optDouble("rate", 0) > 0) {
                    configJson.put("lastChargeRate", lastCharge.optDouble("rate", 0));
                    configJson.put("lastChargeCurrency", lastCharge.optString("currency", ""));
                    configJson.put("lastChargeTariffLabel", lastCharge.optString("tariffLabel", ""));
                    configJson.put("lastChargeAt", lastCharge.optLong("endTime", 0));
                }
            } catch (Throwable t) {
                logger.debug("lastChargeRate enrichment skipped: " + t.getMessage());
            }
            response.put("config", configJson);
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
                if (bodyJson.has("tankCapacityL")) {
                    config.setTankCapacityL(bodyJson.getDouble("tankCapacityL"));
                }
                if (bodyJson.has("fuelPricePerL")) {
                    config.setFuelPricePerL(bodyJson.getDouble("fuelPricePerL"));
                }
                if (bodyJson.has("fuelUnit")) {
                    config.setFuelUnit(bodyJson.getString("fuelUnit"));
                }
                if (bodyJson.has("distanceUnit")) {
                    String unit = bodyJson.getString("distanceUnit");
                    config.setDistanceUnit(unit);
                    // Propagate to BydDataCollector so the conversion factor updates immediately
                    try {
                        com.overdrive.app.byd.BydDataCollector collector =
                                com.overdrive.app.byd.BydDataCollector.getInstance();
                        if (collector != null) {
                            collector.setDistanceUnitOverride("mi".equals(unit) ? "mi" : "km");
                        }
                    } catch (Exception ignored) {}
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
            storage.put("storageTypeActive", sm.getActiveTripsStorageType().name());
            storage.put("limitMb", sm.getTripsLimitMb());
            // Per-volume max = the FULL usable volume (total − headroom). No
            // per-category /N division (removed 2026-06) — the slider tops out at
            // the real volume capacity, accurate across card swaps / SD/USB/internal.
            // Both names emitted: `maxLimitMb` matches the recording/surveillance
            // response (internal volume's ceiling); `maxLimitMbInternal` is kept
            // for older clients that consumed the trips-only naming.
            long maxInternal = sm.getEffectiveMaxLimitMb(StorageManager.StorageType.INTERNAL);
            storage.put("maxLimitMb",         maxInternal);
            storage.put("maxLimitMbInternal", maxInternal);
            storage.put("maxLimitMbSdCard",   sm.getEffectiveMaxLimitMb(StorageManager.StorageType.SD_CARD));
            storage.put("maxLimitMbUsb",      sm.getEffectiveMaxLimitMb(StorageManager.StorageType.USB));
            // Effective enforced limit = configured clamped to the active volume's
            // capacity (differs from limitMb only during a fallback to internal).
            storage.put("effectiveLimitMb",   sm.getEffectiveLimitMb("trips"));
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
            storage.put("usbAvailable", sm.isUsbAvailable());
            if (sm.isSdCardAvailable()) {
                storage.put("sdCardTotalSpace", sm.getSdCardTotalSpace());
                storage.put("sdCardFreeSpace", sm.getSdCardFreeSpace());
            }
            if (sm.isUsbAvailable()) {
                storage.put("usbTotalSpace", sm.getUsbTotalSpace());
                storage.put("usbFreeSpace", sm.getUsbFreeSpace());
            }
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

            // Track per-field rejections so the UI can show "we kept the
            // old value for X" instead of the prior silent-failure pattern
            // where setTripsStorageType returned false (SD/USB not mounted)
            // but the handler returned {success:true} regardless.
            org.json.JSONArray rejected = new org.json.JSONArray();
            String requestedType = null;

            if (bodyJson.has("storageType")) {
                String typeStr = bodyJson.getString("storageType");
                requestedType = typeStr;
                StorageManager.StorageType type;
                if ("SD_CARD".equalsIgnoreCase(typeStr))      type = StorageManager.StorageType.SD_CARD;
                else if ("USB".equalsIgnoreCase(typeStr))     type = StorageManager.StorageType.USB;
                else                                           type = StorageManager.StorageType.INTERNAL;
                boolean accepted = sm.setTripsStorageType(type);
                if (!accepted) {
                    String reason = (type == StorageManager.StorageType.SD_CARD)
                        ? "SD card not available — kept previous storage type"
                        : (type == StorageManager.StorageType.USB)
                            ? "USB drive not available — kept previous storage type"
                            : "could not switch to internal storage";
                    rejected.put(new JSONObject()
                        .put("field", "storageType")
                        .put("value", typeStr)
                        .put("reason", reason));
                    logger.warn("Trips storage type change rejected: " + typeStr + " (" + reason + ")");
                }
            }

            boolean limitChanged = false;
            long appliedLimit = -1;
            if (bodyJson.has("storageLimitMb")) {
                long limitMb = bodyJson.getLong("storageLimitMb");
                sm.setTripsLimitMb(limitMb);
                appliedLimit = sm.getTripsLimitMb();
                limitChanged = true;
                if (appliedLimit != limitMb) {
                    rejected.put(new JSONObject()
                        .put("field", "storageLimitMb")
                        .put("value", limitMb)
                        .put("reason", "clamped to per-volume max " + appliedLimit + "MB"));
                }
            }

            // Mirror the recordings/surveillance handler: enforce the new limit
            // immediately so the user sees usage drop within seconds, instead of
            // waiting for the 30s periodic sweep. Async to keep the HTTP response
            // fast.
            if (limitChanged) {
                final StorageManager smFinal = sm;
                new Thread(() -> {
                    try {
                        smFinal.ensureTripsSpace(0);
                    } catch (Exception ex) {
                        logger.warn("Async trips cleanup after limit change failed: " + ex.getMessage());
                    }
                }, "TripsLimitCleanup").start();
            }

            JSONObject response = new JSONObject();
            response.put("success", true);
            // Echo the live state so the UI can re-sync after a partial-rejection
            // save (the slider may have snapped to a different value, the
            // storage type may have stayed at INTERNAL).
            response.put("appliedType", sm.getTripsStorageType().name());
            response.put("appliedTypeActive", sm.getActiveTripsStorageType().name());
            response.put("appliedLimitMb", sm.getTripsLimitMb());
            if (rejected.length() > 0) {
                response.put("rejected", rejected);
            }
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
        int scoredCount = 0;   // trips with a real DNA score (recovered trips have 0)

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
            // Only fold REAL scores into the average. Recovered-from-telemetry
            // trips carry a 0 overall score (no DNA data), which would drag
            // avgScore down — exclude them via a separate scoredCount, matching
            // how getAverageDna and the rollups treat unscored rows.
            int overall = t.getOverallScore();
            if (overall > 0) { sumScore += overall; scoredCount++; }
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
                stats.put("avgScore", scoredCount > 0 ? sumScore / scoredCount : 0);
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
        TripConfig config = manager.getConfig();
        boolean costNeedsRecompute = false;

        // Electric leg back-fill: estimate energy from SoC delta when BMS
        // kWh wasn't available at the time. Only persists to in-memory record
        // so the API response shows a useful value; DB stays untouched.
        // Mirrors the finalize-time cascade in TripAnalyticsManager: when the
        // vehicle's own consumption counter measured this trip and reported zero,
        // that is an answer, not a gap — estimating from SoC here would resurrect
        // the phantom electric cost the pipeline deliberately declined to book
        // (e.g. a PHEV leg driven on the engine, where a 1% HV dip is parasitic
        // draw, not propulsion). Only an unmistakable SoC drop overrides it, using
        // the same threshold as the pipeline so the card can never contradict what
        // was stored.
        boolean meteredZero = trip.hasMeteredEnergy()
                && (trip.socStart - trip.socEnd) <= TripAnalyticsManager.SOC_OVERRIDE_MIN_DROP_PCT;
        if (trip.getEnergyUsedKwh() <= 0 && !meteredZero
                && trip.socStart > 0 && trip.socEnd > 0 && trip.socStart > trip.socEnd) {
            try {
                com.overdrive.app.abrp.SohEstimator soh =
                    com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                if (soh != null && soh.getNominalCapacityKwh() > 0) {
                    double nominal = soh.getNominalCapacityKwh();
                    // Displayed (capped, anchored) SOH — same frame as the UI.
                    double sohPercent = soh.hasDisplaySoh() ? soh.getDisplaySoh() : 100.0;
                    double usableKwh = nominal * (sohPercent / 100.0);
                    double socDelta = trip.socStart - trip.socEnd;
                    double estimatedEnergy = (socDelta / 100.0) * usableKwh;

                    trip.kwhStart = (trip.socStart / 100.0) * usableKwh;
                    trip.kwhEnd = (trip.socEnd / 100.0) * usableKwh;
                    if (trip.distanceKm > 0) {
                        trip.energyPerKm = estimatedEnergy / trip.distanceKm;
                    }
                    if (config != null && config.getElectricityRate() > 0) {
                        // Only fill rate/currency snapshot when the trip has
                        // none stored — preserves the rate at-time-of-trip
                        // for already-priced trips (audit MEDIUM #4).
                        if (trip.electricityRate <= 0) {
                            trip.electricityRate = config.getElectricityRate();
                        }
                        if (trip.currency == null || trip.currency.isEmpty()) {
                            trip.currency = config.getCurrency();
                        }
                        trip.electricCost = estimatedEnergy * trip.electricityRate;
                        costNeedsRecompute = true;
                    }
                }
            } catch (Exception e) {
                // SohEstimator not available — leave as-is
            }
        } else if (trip.electricCost <= 0 && trip.electricityRate > 0) {
            // Trip has BMS kWh but no electricCost field stored (pre-PHEV-build
            // trip). Fill from existing energy + rate.
            trip.electricCost = trip.getEnergyUsedKwh() * trip.electricityRate;
            costNeedsRecompute = true;
        }

        // Fuel leg back-fill for PHEV trips. Recomputes when the user has set
        // tankCapacityL/fuelPricePerL after the trip was logged (or changed
        // them since). Only the in-memory copy is updated; persisted trip
        // record reflects what the values were at trip end.
        //
        // Invariant: trip.fuelCost is never mutated to zero by this method.
        // The DB load path preserves any previously-stored fuelCost via
        // readTripFromResultSet, and the `trip.fuelCost <= 0` guard below
        // means we only fill from current config when the field is empty.
        if (trip.isPhev && config != null) {
            // Resolve litres the same way computeSummary does: metered HAL
            // accumulator delta first (independent of tank size), then the
            // legacy fuelPct×tank estimate. A reset/rollover (end < start)
            // falls through to the estimate.
            double litres = 0;
            if (trip.fuelConStart >= 0 && trip.fuelConEnd >= 0
                    && trip.fuelConEnd >= trip.fuelConStart) {
                litres = trip.fuelConEnd - trip.fuelConStart;
            } else if (trip.fuelPctStart >= 0 && trip.fuelPctEnd >= 0
                    && trip.fuelPctStart >= trip.fuelPctEnd
                    && (trip.fuelPctStart - trip.fuelPctEnd) >= 1.0
                    && config.getTankCapacityL() > 0) {
                litres = ((trip.fuelPctStart - trip.fuelPctEnd) / 100.0) * config.getTankCapacityL();
            }

            if (litres > 0) {
                double pricePerL = config.getFuelPricePerL();
                if (trip.litresUsed <= 0) trip.litresUsed = litres;
                if (pricePerL > 0 && trip.fuelCost <= 0) {
                    // Preserve the at-trip-end snapshot if one was stored,
                    // only fill from current config when missing.
                    if (trip.fuelPricePerL <= 0) trip.fuelPricePerL = pricePerL;
                    trip.fuelCost = litres * trip.fuelPricePerL;
                    costNeedsRecompute = true;
                }
            }
        }

        if (costNeedsRecompute) {
            // Preserve a non-zero pre-PHEV stored tripCost when the new
            // electric+fuel sum is itself zero (e.g. config was wiped) —
            // otherwise the recompute would silently zero out a previously
            // valid display value on legacy rows.
            double recomputed = trip.electricCost + trip.fuelCost;
            if (recomputed > 0 || trip.tripCost <= 0) {
                trip.tripCost = recomputed;
            }
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
     * Get a long query parameter with a default value. Used for epoch-ms
     * range bounds, which overflow the int range {@link #getIntParam} parses.
     */
    private long getLongParam(Map<String, String> params, String key, long defaultValue) {
        String value = params.get(key);
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(value);
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
