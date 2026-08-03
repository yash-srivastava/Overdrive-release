package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.PerformanceMonitor;
import com.overdrive.app.monitor.SocHistoryDatabase;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * API handler for performance monitoring endpoints.
 * 
 * SOTA On-Demand Architecture:
 * - Performance monitoring only runs when clients are actively viewing the page
 * - Clients must call /connect when opening the page and /disconnect when leaving
 * - Heartbeat mechanism ensures stale clients are cleaned up
 * 
 * Endpoints:
 * - GET /api/performance         - Get current performance snapshot
 * - GET /api/performance/history - Get performance history (60 samples)
 * - GET /api/performance/full    - Get full report (current + history)
 * - POST /api/performance/connect    - Register client connection (starts monitoring)
 * - POST /api/performance/disconnect - Unregister client (stops monitoring if last)
 * - POST /api/performance/heartbeat  - Keep client connection alive
 * - GET /api/performance/status  - Get monitoring status and client count
 * - POST /api/performance/start  - Manual start (legacy)
 * - POST /api/performance/stop   - Manual stop (legacy)
 */
public class PerformanceApiHandler {
    
    private static final String TAG = "PerformanceApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    /**
     * Handle performance API requests.
     * 
     * @return true if request was handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        
        // GET /api/performance - Current snapshot
        if (path.equals("/api/performance") && method.equals("GET")) {
            return handleGetCurrent(out);
        }
        
        // GET /api/performance/history - Historical data
        if (path.equals("/api/performance/history") && method.equals("GET")) {
            return handleGetHistory(out);
        }
        
        // GET /api/performance/full - Full report
        if (path.equals("/api/performance/full") && method.equals("GET")) {
            return handleGetFull(out);
        }
        
        // POST /api/performance/connect - Client connection (SOTA on-demand)
        if (path.equals("/api/performance/connect") && method.equals("POST")) {
            return handleConnect(body, out);
        }
        
        // POST /api/performance/disconnect - Client disconnection (SOTA on-demand)
        if (path.equals("/api/performance/disconnect") && method.equals("POST")) {
            return handleDisconnect(body, out);
        }
        
        // POST /api/performance/heartbeat - Keep connection alive (SOTA on-demand)
        if (path.equals("/api/performance/heartbeat") && method.equals("POST")) {
            return handleHeartbeat(body, out);
        }
        
        // POST /api/performance/start - Start monitoring (legacy)
        if (path.equals("/api/performance/start") && method.equals("POST")) {
            return handleStart(out);
        }
        
        // POST /api/performance/stop - Stop monitoring (legacy)
        if (path.equals("/api/performance/stop") && method.equals("POST")) {
            return handleStop(out);
        }
        
        // GET /api/performance/status - Monitoring status
        if (path.equals("/api/performance/status") && method.equals("GET")) {
            return handleStatus(out);
        }
        
        // GET /api/performance/discover - Discover available system paths (diagnostic)
        if (path.equals("/api/performance/discover") && method.equals("GET")) {
            return handleDiscover(out);
        }
        
        // GET /api/performance/parking-delta - Most recent completed park-and-return cycle.
        // Routed BEFORE the /soc prefix match below since both share the /soc-adjacent
        // SocHistoryDatabase, but this endpoint is a single-row JSON, not history.
        if (path.startsWith("/api/performance/parking-delta") && method.equals("GET")) {
            return handleParkingDelta(path, out);
        }

        // GET /api/performance/last-charge - Most recent completed charging session.
        if (path.startsWith("/api/performance/last-charge") && method.equals("GET")) {
            return handleLastCharge(path, out);
        }

        // GET /api/performance/soc - SOC history
        if (path.startsWith("/api/performance/soc") && method.equals("GET")) {
            return handleSocHistory(path, out);
        }
        
        // GET /api/performance/battery - Battery health report
        if (path.startsWith("/api/performance/battery") && method.equals("GET")) {
            return handleBatteryHealth(path, out);
        }
        
        // GET /api/performance/soh - SOH detailed status
        if (path.equals("/api/performance/soh") && method.equals("GET")) {
            return handleSohStatus(out);
        }
        
        // POST /api/performance/soh/reset - Reset SOH estimation
        if (path.equals("/api/performance/soh/reset") && method.equals("POST")) {
            return handleSohReset(out);
        }
        
        // GET /api/performance/soh/nominal - Get current nominal capacity + source
        if (path.equals("/api/performance/soh/nominal") && method.equals("GET")) {
            return handleSohGetNominal(out);
        }

        // POST /api/performance/soh/nominal - Set or clear user nominal capacity
        if (path.equals("/api/performance/soh/nominal") && method.equals("POST")) {
            return handleSohSetNominal(body, out);
        }

        // POST /api/performance/reset - Bulk reset of selected categories
        if (path.equals("/api/performance/reset") && method.equals("POST")) {
            return handleResetCategories(body, out);
        }

        // GET /api/performance/data-usage?days=30 - per-day WiFi/mobile + app/system
        if (path.startsWith("/api/performance/data-usage") && method.equals("GET")) {
            return handleDataUsageGet(path, out);
        }

        // POST /api/performance/data-usage/toggle {enabled:bool} - arm/disarm sampler
        if (path.equals("/api/performance/data-usage/toggle") && method.equals("POST")) {
            return handleDataUsageToggle(body, out);
        }

        // POST /api/performance/data-usage/reset - clear recorded history
        if (path.equals("/api/performance/data-usage/reset") && method.equals("POST")) {
            return handleDataUsageReset(out);
        }

        return false;
    }

    /** GET the last N days of data usage (default 30). See DataUsageMonitor.getUsage. */
    private static boolean handleDataUsageGet(String path, OutputStream out) throws Exception {
        try {
            int days = parseIntQueryParam(path, "days", 30);
            if (days < 1) days = 1;
            if (days > 90) days = 90;   // sane cap; retention is 30d raw anyway
            JSONObject usage = com.overdrive.app.monitor.DataUsageMonitor.getInstance().getUsage(days);
            HttpResponse.sendJson(out, usage.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to get data usage", e);
            HttpResponse.sendJson(out, "{\"enabled\":false,\"available\":false,\"days\":[],\"total\":0}");
            return true;
        }
    }

    /**
     * Toggle the data-usage feature. Persists dataUsage.enabled and then arms or
     * disarms the sampler on THIS (daemon) process via startIfEnabled() — so the
     * enable edge starts collecting immediately and the disable edge frees the
     * sampler thread (zero overhead when off). App-UID callers reach this over
     * the daemon's HTTP server, so the persist + arm both happen daemon-side.
     */
    private static boolean handleDataUsageToggle(String body, OutputStream out) throws Exception {
        try {
            JSONObject json = (body == null || body.trim().isEmpty())
                    ? new JSONObject() : new JSONObject(body);
            boolean enabled = json.optBoolean("enabled", false);
            boolean ok = com.overdrive.app.config.UnifiedConfigManager.INSTANCE
                    .setDataUsageEnabled(enabled);
            // Reflect the change on the sampler now (startIfEnabled re-reads config
            // with forceReload and starts or stops accordingly).
            try {
                com.overdrive.app.monitor.DataUsageMonitor dum =
                        com.overdrive.app.monitor.DataUsageMonitor.getInstance();
                android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (ctx != null) dum.resolveAppUid(ctx);
                dum.startIfEnabled();
            } catch (Throwable t) {
                logger.warn("Data-usage sampler arm/disarm after toggle failed: " + t.getMessage());
            }
            JSONObject r = new JSONObject();
            r.put("success", ok);
            r.put("enabled", enabled);
            HttpResponse.sendJson(out, r.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to toggle data usage", e);
            HttpResponse.sendJson(out, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            return true;
        }
    }

    /** Clear all recorded data-usage history (daily rows + delta baseline). */
    private static boolean handleDataUsageReset(OutputStream out) throws Exception {
        try {
            boolean ok = com.overdrive.app.monitor.DataUsageMonitor.getInstance().resetHistory();
            HttpResponse.sendJson(out, "{\"success\":" + ok + "}");
            return true;
        } catch (Exception e) {
            logger.error("Failed to reset data usage", e);
            HttpResponse.sendJson(out, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    private static boolean handleGetCurrent(OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            JSONObject data = monitor.getLatestAsJson();
            
            if (data.length() == 0) {
                // No data yet - return empty with status
                JSONObject response = new JSONObject();
                response.put("status", "no_data");
                response.put("message", Messages.get("errors.performance_no_data"));
                response.put("monitoring", monitor.isRunning());
                HttpResponse.sendJson(out, response.toString());
            } else {
                HttpResponse.sendJson(out, data.toString());
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to get current performance data", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    private static boolean handleGetHistory(OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            String history = monitor.getHistoryAsJson().toString();
            HttpResponse.sendJson(out, history);
            return true;
        } catch (Exception e) {
            logger.error("Failed to get performance history", e);
            HttpResponse.sendJson(out, "[]");
            return true;
        }
    }
    
    private static boolean handleGetFull(OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            JSONObject report = monitor.getFullReport();
            HttpResponse.sendJson(out, report.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to get full performance report", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    // ==================== SOTA ON-DEMAND CLIENT MANAGEMENT ====================
    
    /**
     * Handle client connection request.
     * Registers the client and starts monitoring if this is the first client.
     */
    private static boolean handleConnect(String body, OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            
            // Parse client ID from body (optional)
            String clientId = null;
            if (body != null && !body.isEmpty()) {
                try {
                    JSONObject json = new JSONObject(body);
                    clientId = json.optString("clientId", null);
                } catch (Exception ignored) {}
            }
            
            // Generate client ID if not provided
            if (clientId == null || clientId.isEmpty()) {
                clientId = "client-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 10000);
            }
            
            monitor.clientConnected(clientId);
            
            JSONObject response = new JSONObject();
            response.put("status", "ok");
            response.put("clientId", clientId);
            response.put("monitoring", monitor.isRunning());
            response.put("activeClients", monitor.getActiveClientCount());
            response.put("heartbeatIntervalMs", 5000);  // Client should heartbeat every 5s
            HttpResponse.sendJson(out, response.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to handle client connect", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    /**
     * Handle client disconnection request.
     * Unregisters the client and stops monitoring if this was the last client.
     */
    private static boolean handleDisconnect(String body, OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            
            String clientId = null;
            if (body != null && !body.isEmpty()) {
                try {
                    JSONObject json = new JSONObject(body);
                    clientId = json.optString("clientId", null);
                } catch (Exception ignored) {}
            }
            
            if (clientId != null) {
                monitor.clientDisconnected(clientId);
            }
            
            JSONObject response = new JSONObject();
            response.put("status", "ok");
            response.put("monitoring", monitor.isRunning());
            response.put("activeClients", monitor.getActiveClientCount());
            HttpResponse.sendJson(out, response.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to handle client disconnect", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    /**
     * Handle client heartbeat request.
     * Updates the client's last-seen timestamp to prevent timeout.
     */
    private static boolean handleHeartbeat(String body, OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            
            String clientId = null;
            if (body != null && !body.isEmpty()) {
                try {
                    JSONObject json = new JSONObject(body);
                    clientId = json.optString("clientId", null);
                } catch (Exception ignored) {}
            }
            
            if (clientId != null) {
                monitor.clientHeartbeat(clientId);
            }
            
            JSONObject response = new JSONObject();
            response.put("status", "ok");
            response.put("monitoring", monitor.isRunning());
            response.put("activeClients", monitor.getActiveClientCount());
            HttpResponse.sendJson(out, response.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to handle heartbeat", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    // ==================== LEGACY MANUAL CONTROL ====================
    
    private static boolean handleStart(OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            monitor.start();
            
            JSONObject response = new JSONObject();
            response.put("status", "ok");
            response.put("message", Messages.get("messages.performance_monitoring_started"));
            response.put("monitoring", true);
            HttpResponse.sendJson(out, response.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to start performance monitoring", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    private static boolean handleStop(OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            monitor.stop();
            
            JSONObject response = new JSONObject();
            response.put("status", "ok");
            response.put("message", Messages.get("messages.performance_monitoring_stopped"));
            response.put("monitoring", false);
            HttpResponse.sendJson(out, response.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to stop performance monitoring", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    private static boolean handleStatus(OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            
            JSONObject response = new JSONObject();
            response.put("monitoring", monitor.isRunning());
            HttpResponse.sendJson(out, response.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to get monitoring status", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    /**
     * Handle system path discovery request.
     * Returns available thermal zones and GPU paths for diagnostics.
     */
    private static boolean handleDiscover(OutputStream out) throws Exception {
        try {
            PerformanceMonitor monitor = PerformanceMonitor.getInstance();
            JSONObject discovery = monitor.discoverSystemPaths();
            HttpResponse.sendJson(out, discovery.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to discover system paths", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    /**
     * GET /api/performance/parking-delta?maxAgeHours=72
     *
     * Proxies SocHistoryDatabase.getLastParkingDelta() to UI-process callers
     * (DashboardInsightProvider) which can't open the H2 file themselves —
     * the daemon's FILE_LOCK=SOCKET excludes other JVMs, and the UI-side
     * singleton is never init()'d anyway because init() only runs in
     * CameraDaemon.main().
     *
     * Response: the JSON object returned by getLastParkingDelta(), or
     *           {"available": false} when the DB is offline / no qualifying cycle.
     */
    private static boolean handleParkingDelta(String path, OutputStream out) throws Exception {
        try {
            int maxAgeHours = parseIntQueryParam(path, "maxAgeHours", 72);
            SocHistoryDatabase socDb = SocHistoryDatabase.getInstance();
            JSONObject delta = socDb.getLastParkingDelta(maxAgeHours);
            if (delta == null) {
                HttpResponse.sendJson(out, "{\"available\": false}");
            } else {
                HttpResponse.sendJson(out, delta.toString());
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to get parking delta", e);
            HttpResponse.sendJson(out, "{\"available\": false, \"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }

    /**
     * GET /api/performance/last-charge?hoursBack=24
     *
     * Proxies SocHistoryDatabase.getMostRecentCompletedChargingSession() for the
     * same cross-process reason as parking-delta above.
     */
    private static boolean handleLastCharge(String path, OutputStream out) throws Exception {
        try {
            int hoursBack = parseIntQueryParam(path, "hoursBack", 24);
            SocHistoryDatabase socDb = SocHistoryDatabase.getInstance();
            JSONObject session = socDb.getMostRecentCompletedChargingSession(hoursBack);
            if (session == null) {
                HttpResponse.sendJson(out, "{\"available\": false}");
            } else {
                HttpResponse.sendJson(out, session.toString());
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to get last charge", e);
            HttpResponse.sendJson(out, "{\"available\": false, \"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }

    private static int parseIntQueryParam(String path, String name, int defaultValue) {
        if (path == null || !path.contains("?")) return defaultValue;
        try {
            String query = path.substring(path.indexOf("?") + 1);
            for (String param : query.split("&")) {
                String[] kv = param.split("=");
                if (kv.length == 2 && kv[0].equals(name)) {
                    return Integer.parseInt(kv[1]);
                }
            }
        } catch (Exception ignored) {}
        return defaultValue;
    }

    /**
     * Handle SOC history requests.
     *
     * Endpoints:
     * - GET /api/performance/soc?hours=24&points=200 - Get SOC history
     * - GET /api/performance/soc/stats?hours=24 - Get SOC statistics
     * - GET /api/performance/soc/sessions?days=7 - Get charging sessions
     * - GET /api/performance/soc/full?hours=72&points=300 - Full report
     */
    private static boolean handleSocHistory(String path, OutputStream out) throws Exception {
        try {
            SocHistoryDatabase socDb = SocHistoryDatabase.getInstance();
            
            // Parse query parameters
            int hours = 24;
            int points = 200;
            int days = 7;
            
            if (path.contains("?")) {
                String query = path.substring(path.indexOf("?") + 1);
                for (String param : query.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length == 2) {
                        switch (kv[0]) {
                            case "hours": hours = Integer.parseInt(kv[1]); break;
                            case "points": points = Integer.parseInt(kv[1]); break;
                            case "days": days = Integer.parseInt(kv[1]); break;
                        }
                    }
                }
            }
            
            String basePath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
            
            if (basePath.equals("/api/performance/soc/stats")) {
                HttpResponse.sendJson(out, socDb.getSocStats(hours).toString());
            } else if (basePath.equals("/api/performance/soc/sessions")) {
                HttpResponse.sendJson(out, socDb.getChargingSessions(days).toString());
            } else if (basePath.equals("/api/performance/soc/full")) {
                HttpResponse.sendJson(out, socDb.getFullReport(hours, points).toString());
            } else {
                // Default: /api/performance/soc - history only
                HttpResponse.sendJson(out, socDb.getSocHistory(hours, points).toString());
            }
            
            return true;
        } catch (Exception e) {
            logger.error("Failed to get SOC history", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }
    
    /**
     * Handle battery health requests.
     * GET /api/performance/battery?hours=72&points=200 - Full battery health report
     * GET /api/performance/battery/voltage?hours=24&points=200 - 12V voltage history
     * GET /api/performance/battery/thermal?hours=24&points=200 - HV thermal history
     */
    private static boolean handleBatteryHealth(String path, OutputStream out) throws Exception {
        try {
            SocHistoryDatabase socDb = SocHistoryDatabase.getInstance();
            
            int hours = 72;
            int points = 200;
            
            if (path.contains("?")) {
                String query = path.substring(path.indexOf("?") + 1);
                for (String param : query.split("&")) {
                    String[] kv = param.split("=");
                    if (kv.length == 2) {
                        switch (kv[0]) {
                            case "hours": hours = Integer.parseInt(kv[1]); break;
                            case "points": points = Integer.parseInt(kv[1]); break;
                        }
                    }
                }
            }
            
            String basePath = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
            
            if (basePath.equals("/api/performance/battery/voltage")) {
                HttpResponse.sendJson(out, socDb.getBatteryVoltageHistory(hours, points).toString());
            } else if (basePath.equals("/api/performance/battery/thermal")) {
                HttpResponse.sendJson(out, socDb.getThermalHistory(hours, points).toString());
            } else {
                // Default: full battery health report
                HttpResponse.sendJson(out, socDb.getBatteryHealthReport(hours, points).toString());
            }
            
            return true;
        } catch (Exception e) {
            logger.error("Failed to get battery health", e);
            HttpResponse.sendJson(out, "{\"error\": \"" + e.getMessage() + "\"}");
            return true;
        }
    }

    // ==================== SOH STATUS & RESET ====================

    /**
     * GET /api/performance/soh - Detailed SOH status with source, confidence, capacity info.
     */
    private static boolean handleSohStatus(OutputStream out) throws Exception {
        try {
            SocHistoryDatabase socDb = SocHistoryDatabase.getInstance();
            com.overdrive.app.abrp.SohEstimator sohEst = socDb != null ? socDb.getSohEstimator() : null;

            if (sohEst != null) {
                JSONObject status = sohEst.getStatus();
                status.put("success", true);
                HttpResponse.sendJson(out, status.toString());
            } else {
                JSONObject response = new JSONObject();
                response.put("success", false);
                response.put("error", Messages.get("errors.soh_not_initialized"));
                HttpResponse.sendJson(out, response.toString());
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to get SOH status", e);
            HttpResponse.sendJson(out, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            return true;
        }
    }

    /**
     * POST /api/performance/soh/reset - Reset SOH estimation.
     * Clears all persisted SOH data and forces re-estimation from scratch.
     * Re-runs capacity detection and initial seeding immediately so the user
     * doesn't need to restart the daemon.
     */
    private static boolean handleSohReset(OutputStream out) throws Exception {
        try {
            SocHistoryDatabase socDb = SocHistoryDatabase.getInstance();
            com.overdrive.app.abrp.SohEstimator sohEst = socDb != null ? socDb.getSohEstimator() : null;

            if (sohEst != null) {
                sohEst.reset();

                // Re-run capacity detection and seed immediately from live data.
                // Passing null context disables every HAL probe — getEnergyMode,
                // getFuelPercentageValue, getBatteryCapacity all need it — which
                // means dumpPhevDiagnostics can't infer drivetrain and the
                // exact-Ah path can't fire. Use the daemon's app context.
                android.content.Context appCtx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                sohEst.autoDetectCarModel(appCtx);
                sohEst.seedInitialEstimate();

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", Messages.get("messages.soh_reset_complete"));
                com.overdrive.app.abrp.SohEstimator.ResolvedSoh resolvedSoh =
                    sohEst.getResolvedSoh();
                if (resolvedSoh.getPercent() > 0) {
                    response.put("newSoh", resolvedSoh.getPercent());
                    response.put("newSohSource", resolvedSoh.getSource());
                    response.put("nominalCapacityKwh", sohEst.getNominalCapacityKwh());
                }
                HttpResponse.sendJson(out, response.toString());
            } else {
                JSONObject response = new JSONObject();
                response.put("success", false);
                response.put("error", Messages.get("errors.soh_not_initialized"));
                HttpResponse.sendJson(out, response.toString());
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to reset SOH", e);
            HttpResponse.sendJson(out, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            return true;
        }
    }

    /**
     * POST /api/performance/reset — bulk reset of user-selected data categories.
     *
     * Body: {"categories": ["trips","socHistory","soh","abrpToken",
     *                       "bydCloud","mediaRecordings","mediaSurveillance",
     *                       "mediaProximity","mediaTrips"]}
     *
     * Each requested category runs independently — a partial failure on one
     * does not abort the others. Response includes per-category result so the
     * UI can show which wipes succeeded.
     */
    private static boolean handleResetCategories(String body, OutputStream out) throws Exception {
        JSONObject response = new JSONObject();
        JSONObject results = new JSONObject();
        try {
            JSONObject req = (body == null || body.isEmpty())
                ? new JSONObject()
                : new JSONObject(body);
            org.json.JSONArray cats = req.optJSONArray("categories");
            if (cats == null || cats.length() == 0) {
                response.put("success", false);
                response.put("error", Messages.get("errors.reset_no_categories"));
                HttpResponse.sendJson(out, response.toString());
                return true;
            }

            for (int i = 0; i < cats.length(); i++) {
                String cat = cats.optString(i, "");
                JSONObject r = new JSONObject();
                try {
                    switch (cat) {
                        case "trips": {
                            com.overdrive.app.trips.TripAnalyticsManager mgr =
                                com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
                            // Refuse if a trip is being recorded right now —
                            // wiping mid-trip would leave the in-memory
                            // TripBuilder writing to a freshly-empty DB and
                            // create a phantom one-row history. User can
                            // turn the car off and try again.
                            if (mgr != null && mgr.isTripActive()) {
                                r.put("success", false);
                                r.put("error", Messages.get("errors.reset_trip_in_progress"));
                                break;
                            }
                            com.overdrive.app.trips.TripDatabase db =
                                (mgr != null) ? mgr.getDatabase() : null;
                            long n = (db != null) ? db.resetAll() : -1;
                            r.put("success", n >= 0);
                            r.put("rowsDeleted", n);
                            break;
                        }
                        case "socHistory": {
                            long n = SocHistoryDatabase.getInstance().resetAll();
                            r.put("success", n >= 0);
                            r.put("rowsDeleted", n);
                            break;
                        }
                        case "soh": {
                            com.overdrive.app.abrp.SohEstimator sohEst =
                                SocHistoryDatabase.getInstance().getSohEstimator();
                            if (sohEst != null) {
                                sohEst.reset();
                                // Pass app context — null disables HAL probes
                                // and forces SOC-heuristic-only re-detection.
                                android.content.Context appCtx =
                                    com.overdrive.app.daemon.CameraDaemon.getAppContext();
                                sohEst.autoDetectCarModel(appCtx);
                                sohEst.seedInitialEstimate();
                                r.put("success", true);
                            } else {
                                r.put("success", false);
                                r.put("error", Messages.get("errors.soh_not_initialized"));
                            }
                            break;
                        }
                        case "abrpToken": {
                            // Use the shared singleton + service references held by
                            // SurveillanceIpcServer. Constructing a fresh AbrpConfig
                            // here would only modify a throwaway in-memory copy and
                            // leave the running service still using its cached
                            // token until the daemon restarted.
                            boolean ok = SurveillanceIpcServer.resetAbrpForBulkWipe();
                            r.put("success", ok);
                            if (!ok) r.put("error", Messages.get("errors.reset_abrp_not_initialized"));
                            break;
                        }
                        case "bydCloud": {
                            com.overdrive.app.byd.cloud.BydCloudConfig.clearCredentials();
                            r.put("success", true);
                            break;
                        }
                        case "mediaRecordings": {
                            com.overdrive.app.storage.StorageManager sm =
                                com.overdrive.app.storage.StorageManager.getInstance();
                            // Don't wipe the dir while the encoder is writing
                            // to it — at best you'd delete the still-open file
                            // descriptor; at worst, corrupt the active MP4.
                            if (sm.isRecordingActive()) {
                                r.put("success", false);
                                r.put("error", Messages.get("errors.reset_recording_in_progress"));
                                break;
                            }
                            long n = sm.wipeMediaCategory("recordings");
                            r.put("success", n >= 0);
                            r.put("filesDeleted", n);
                            break;
                        }
                        case "mediaSurveillance": {
                            com.overdrive.app.storage.StorageManager sm =
                                com.overdrive.app.storage.StorageManager.getInstance();
                            if (sm.isSurveillanceActive()) {
                                r.put("success", false);
                                r.put("error", Messages.get("errors.reset_surveillance_in_progress"));
                                break;
                            }
                            long n = sm.wipeMediaCategory("surveillance");
                            r.put("success", n >= 0);
                            r.put("filesDeleted", n);
                            break;
                        }
                        case "mediaProximity": {
                            long n = com.overdrive.app.storage.StorageManager.getInstance()
                                .wipeMediaCategory("proximity");
                            r.put("success", n >= 0);
                            r.put("filesDeleted", n);
                            break;
                        }
                        case "mediaTrips": {
                            long n = com.overdrive.app.storage.StorageManager.getInstance()
                                .wipeMediaCategory("trips");
                            r.put("success", n >= 0);
                            r.put("filesDeleted", n);
                            break;
                        }
                        case "dataUsage": {
                            boolean ok = com.overdrive.app.monitor.DataUsageMonitor
                                .getInstance().resetHistory();
                            r.put("success", ok);
                            break;
                        }
                        default:
                            r.put("success", false);
                            r.put("error", Messages.get("errors.reset_unknown_category"));
                    }
                } catch (Exception inner) {
                    r.put("success", false);
                    r.put("error", inner.getMessage());
                    logger.warn("Reset category " + cat + " failed: " + inner.getMessage());
                }
                results.put(cat, r);
            }

            response.put("success", true);
            response.put("results", results);
            HttpResponse.sendJson(out, response.toString());
        } catch (Exception e) {
            logger.error("Reset request failed", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            HttpResponse.sendJson(out, response.toString());
        }
        return true;
    }

    /**
     * GET /api/performance/soh/nominal - Returns current nominal kWh, source.
     */
    private static boolean handleSohGetNominal(OutputStream out) throws Exception {
        try {
            SocHistoryDatabase socDb = SocHistoryDatabase.getInstance();
            com.overdrive.app.abrp.SohEstimator sohEst = socDb != null ? socDb.getSohEstimator() : null;

            JSONObject response = new JSONObject();
            if (sohEst != null) {
                double kwh = sohEst.getNominalCapacityKwh();
                response.put("nominalKwh", kwh > 0 ? kwh : JSONObject.NULL);
                response.put("nominalSource", sohEst.getNominalSource());
            } else {
                response.put("nominalKwh", JSONObject.NULL);
                response.put("nominalSource", "unset");
            }
            HttpResponse.sendJson(out, response.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to get SOH nominal", e);
            HttpResponse.sendJson(out, "{\"error\":\"" + e.getMessage() + "\"}");
            return true;
        }
    }

    /**
     * POST /api/performance/soh/nominal — set or clear the user-set nominal kWh.
     * Body: {"nominalKwh": 82.5}  → sets user override
     *       {"nominalKwh": null}  → clears override, re-runs auto-detect
     * Validates 15-120 kWh range.
     */
    private static boolean handleSohSetNominal(String body, OutputStream out) throws Exception {
        try {
            SocHistoryDatabase socDb = SocHistoryDatabase.getInstance();
            com.overdrive.app.abrp.SohEstimator sohEst = socDb != null ? socDb.getSohEstimator() : null;

            if (sohEst == null) {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("error", Messages.get("errors.soh_not_initialized"));
                HttpResponse.sendJson(out, err.toString());
                return true;
            }

            JSONObject req = (body == null || body.isEmpty())
                ? new JSONObject() : new JSONObject(body);

            // null clears the override; otherwise validate & set.
            boolean changed;
            if (req.isNull("nominalKwh") || !req.has("nominalKwh")) {
                changed = true;
                sohEst.clearUserNominal();
            } else {
                double kwh = req.getDouble("nominalKwh");
                // Floor is drivetrain-aware: 8 kWh on PHEV (Blade DM-i users
                // who want to enter a usable-frame value like ~12.9 kWh on
                // Tang DM-i), 15 kWh on BEV. SohEstimator does the strict
                // per-drivetrain validation; we just gate the API range so
                // an obviously implausible value bounces with a clear error.
                if (kwh < 8.0 || kwh > 120.0) {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("error", "nominalKwh must be between 8 and 120");
                    HttpResponse.sendJson(out, err.toString());
                    return true;
                }
                changed = true;
                double prevNominal = sohEst.getNominalCapacityKwh();
                sohEst.setNominalCapacityKwhFromUser(kwh);
                // setNominalCapacityKwhFromUser silently rejects values below
                // its drivetrain-aware floor (e.g. 12.9 kWh on a misclassified
                // BEV). Surface that to the client so it doesn't show a
                // "saved" toast for a no-op.
                if (Math.abs(sohEst.getNominalCapacityKwh() - kwh) > 0.01
                        && Math.abs(sohEst.getNominalCapacityKwh() - prevNominal) < 0.01) {
                    JSONObject err = new JSONObject();
                    err.put("success", false);
                    err.put("error", "nominalKwh rejected — outside drivetrain-specific plausible range");
                    HttpResponse.sendJson(out, err.toString());
                    return true;
                }
            }

            // Whenever the capacity baseline changes, the previously
            // recorded SOH/calibration are no longer meaningful (they were
            // computed against the old nominal). Reset live data and
            // re-seed against the new baseline so the user immediately
            // sees an SOH that reflects their current pack assumption.
            // setNominalCapacityKwhFromUser() inside reset() will be
            // re-applied automatically because it persists to UnifiedConfig.
            if (changed) {
                sohEst.reset();
                android.content.Context appCtx =
                    com.overdrive.app.daemon.CameraDaemon.getAppContext();
                sohEst.autoDetectCarModel(appCtx);
                sohEst.seedInitialEstimate();
            }

            JSONObject response = sohEst.getStatus();
            response.put("success", true);
            HttpResponse.sendJson(out, response.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to set SOH nominal", e);
            HttpResponse.sendJson(out, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            return true;
        }
    }
}
