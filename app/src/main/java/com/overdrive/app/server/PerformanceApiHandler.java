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
        
        // POST /api/performance/soh/source - Set preferred SOH source
        if (path.equals("/api/performance/soh/source") && method.equals("POST")) {
            return handleSohSetSource(body, out);
        }

        // POST /api/performance/reset - Bulk reset of selected categories
        if (path.equals("/api/performance/reset") && method.equals("POST")) {
            return handleResetCategories(body, out);
        }

        return false;
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

                // Re-run capacity detection and seed immediately from live data
                sohEst.autoDetectCarModel(null);
                sohEst.seedInitialEstimate();

                JSONObject response = new JSONObject();
                response.put("success", true);
                response.put("message", Messages.get("messages.soh_reset_complete"));
                if (sohEst.hasEstimate()) {
                    response.put("newSoh", sohEst.getCurrentSoh());
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
                                sohEst.autoDetectCarModel(null);
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
     * POST /api/performance/soh/source - Set preferred SOH source.
     * Body: {"source": "auto"|"oem"|"capacity_ah"|"calibration"|"energy"}
     */
    private static boolean handleSohSetSource(String body, OutputStream out) throws Exception {
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

            String source = "auto";
            if (body != null && !body.isEmpty()) {
                JSONObject json = new JSONObject(body);
                source = json.optString("source", "auto");
            }

            sohEst.setPreferredSource(source);

            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("preferredSource", sohEst.getPreferredSource());
            response.put("activeSoh", Math.round(sohEst.getCurrentSoh() * 10) / 10.0);
            HttpResponse.sendJson(out, response.toString());
            return true;
        } catch (Exception e) {
            logger.error("Failed to set SOH source", e);
            HttpResponse.sendJson(out, "{\"success\":false,\"error\":\"" + e.getMessage() + "\"}");
            return true;
        }
    }
}
