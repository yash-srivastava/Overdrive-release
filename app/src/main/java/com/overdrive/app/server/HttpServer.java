package com.overdrive.app.server;

import android.content.res.AssetManager;
import android.util.Base64;

import com.overdrive.app.auth.AuthManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.monitor.AccMonitor;
import com.overdrive.app.monitor.BatteryMonitor;
import com.overdrive.app.surveillance.GpuPipelineConfig;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HTTP Server - serves web UI and WebSocket H.264 streaming.
 * Listens on 0.0.0.0:8080 for tunnel access.
 * 
 * Single-port WebSocket: /ws endpoint upgrades to WebSocket for H.264 streaming
 * This allows Cloudflare tunnel to work (only one port needed).
 * 
 * API handlers are modularized into separate classes:
 * - RecordingsApiHandler: /api/recordings, /video/*
 * - SurveillanceApiHandler: /api/surveillance/*
 * - StreamingApiHandler: /api/stream/*
 * - GpsApiHandler: /api/gps/*
 * - QualitySettingsApiHandler: /api/settings/quality
 */
public class HttpServer {

    private static final String WEB_ROOT = "/data/local/tmp/web";
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = true;
    
    // Thread Pool to prevent server clogging (max 32 concurrent connections)
    private final ExecutorService threadPool = Executors.newFixedThreadPool(32);

    public HttpServer(int port) {
        this.port = port;
    }

    /**
     * Extracts web assets from APK to filesystem.
     * Call this during initialization with a valid AssetManager.
     */
    public static void extractWebAssets(AssetManager assetManager) {
        if (assetManager == null) {
            CameraDaemon.log("AssetManager is null, skipping web asset extraction");
            return;
        }
        
        try {
            File webRoot = new File(WEB_ROOT);
            
            // Always delete and recreate to ensure fresh files on app update
            if (webRoot.exists()) {
                deleteRecursive(webRoot);
                CameraDaemon.log("Deleted existing web assets for fresh extraction");
            }
            webRoot.mkdirs();
            
            // Extract web/local and web/shared directories
            extractAssetDir(assetManager, "web/local", new File(WEB_ROOT, "local"));
            extractAssetDir(assetManager, "web/shared", new File(WEB_ROOT, "shared"));
            
            // Extract overlay icons for telemetry overlay
            extractAssetDir(assetManager, "overlay", new File("/data/local/tmp/overlay"));
            
            CameraDaemon.log("Web assets extracted to " + WEB_ROOT);
        } catch (Exception e) {
            CameraDaemon.log("Failed to extract web assets: " + e.getMessage());
        }
    }
    
    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
    
    private static void extractAssetDir(AssetManager assetManager, String assetPath, File destDir) throws Exception {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }
        
        String[] files = assetManager.list(assetPath);
        if (files == null || files.length == 0) {
            CameraDaemon.log("No files found in assets/" + assetPath);
            return;
        }
        
        for (String fileName : files) {
            String assetFilePath = assetPath + "/" + fileName;
            File destFile = new File(destDir, fileName);
            
            String[] subFiles = assetManager.list(assetFilePath);
            if (subFiles != null && subFiles.length > 0) {
                extractAssetDir(assetManager, assetFilePath, destFile);
            } else {
                try (InputStream in = assetManager.open(assetFilePath);
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                CameraDaemon.log("Extracted: " + assetFilePath + " -> " + destFile.getAbsolutePath());
            }
        }
    }

    public void start() {
        CameraDaemon.log("HTTP server starting on port " + port);
        
        // Initialize auth system
        AuthManager.initialize();
        CameraDaemon.log("Auth system initialized");
        
        while (running && CameraDaemon.isRunning()) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try { serverSocket.close(); } catch (Exception e) {}
                }
                
                serverSocket = new ServerSocket(port, 10, InetAddress.getByName("127.0.0.1"));
                serverSocket.setReuseAddress(true);
                CameraDaemon.log("HTTP server listening on 127.0.0.1:" + port);

                while (running && CameraDaemon.isRunning() && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        CameraDaemon.log("HTTP client: " + client.getRemoteSocketAddress());
                        threadPool.execute(() -> handleClient(client));
                    } catch (java.net.SocketException e) {
                        if (running) {
                            CameraDaemon.log("WARN: HTTP socket error: " + e.getMessage());
                        }
                        break;
                    }
                }
                
                if (running) {
                    CameraDaemon.log("HTTP server restarting...");
                    Thread.sleep(2000);
                }
                
            } catch (java.net.BindException e) {
                CameraDaemon.log("ERROR: HTTP port " + port + " in use, retrying...");
                try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            } catch (Exception e) {
                CameraDaemon.log("ERROR: HTTP server error: " + e.getMessage());
                if (running) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) {}
                }
            }
        }
        
        CameraDaemon.log("HTTP server stopped");
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {}
        threadPool.shutdownNow();
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = new BufferedOutputStream(client.getOutputStream());

            String requestLine = reader.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }
            
            CameraDaemon.log("HTTP: " + requestLine);
            
            // Parse headers
            String line;
            int contentLength = 0;
            String websocketKey = null;
            String upgradeHeader = null;
            String rangeHeader = null;
            String cookieHeader = null;
            String authHeader = null;
            
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } else if (lower.startsWith("sec-websocket-key:")) {
                    websocketKey = line.substring(18).trim();
                } else if (lower.startsWith("upgrade:")) {
                    upgradeHeader = line.substring(8).trim();
                } else if (lower.startsWith("range:")) {
                    rangeHeader = line.substring(6).trim();
                } else if (lower.startsWith("cookie:")) {
                    cookieHeader = line.substring(7).trim();
                } else if (lower.startsWith("authorization:")) {
                    authHeader = line.substring(14).trim();
                }
            }
            
            // Read POST body if present
            // SOTA: Loop read for large payloads (e.g., base64 image uploads)
            // BufferedReader.read() may return fewer chars than requested in a single call
            String body = null;
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int read = reader.read(bodyChars, totalRead, contentLength - totalRead);
                    if (read == -1) break;  // EOF
                    totalRead += read;
                }
                body = new String(bodyChars, 0, totalRead);
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                HttpResponse.sendError(out, 400, "Bad Request");
                client.close();
                return;
            }

            String method = parts[0];
            String path = parts[1];
            
            // WebSocket upgrade on /ws path (check auth first for non-public paths)
            if (path.equals("/ws") && websocketKey != null && "websocket".equalsIgnoreCase(upgradeHeader)) {
                // Check auth for WebSocket
                if (!AuthMiddleware.checkAuth(path, cookieHeader, authHeader, out)) {
                    client.close();
                    return;
                }
                handleWebSocketUpgrade(client, websocketKey);
                return;
            }
            
            // Route auth endpoints first (before auth check - they're public)
            if (path.startsWith("/auth/")) {
                AuthApiHandler.handle(method, path, body, out);
                client.close();
                return;
            }
            
            // Serve login page (public) - strip query string for matching
            String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;
            if (pathOnly.equals("/login") || pathOnly.equals("/login.html")) {
                if (!serveStaticFile(out, "local/login.html")) {
                    HttpResponse.sendError(out, 404, "login.html not found");
                }
                client.close();
                return;
            }
            
            // Check authentication for all other paths
            if (!AuthMiddleware.checkAuth(path, cookieHeader, authHeader, out)) {
                client.close();
                return;
            }
            
            // Route to modular handlers first
            if (routeToHandlers(method, path, body, rangeHeader, out)) {
                // Handled by a modular handler
            }
            // Static pages
            else if (path.equals("/") || path.equals("/index.html")) {
                if (!serveStaticFile(out, "local/index.html")) {
                    HttpResponse.sendError(out, 404, "index.html not found");
                }
            } else if (path.equals("/recording.html") || path.equals("/recording")) {
                if (!serveStaticFile(out, "local/recording.html")) {
                    HttpResponse.sendError(out, 404, "recording.html not found");
                }
            } else if (path.equals("/surveillance.html") || path.equals("/surveillance")) {
                if (!serveStaticFile(out, "local/surveillance.html")) {
                    HttpResponse.sendError(out, 404, "surveillance.html not found");
                }
            } else if (path.equals("/events.html") || path.equals("/events") || 
                       path.startsWith("/events.html?") || path.startsWith("/events?")) {
                if (!serveStaticFile(out, "local/events.html")) {
                    HttpResponse.sendError(out, 404, "events.html not found");
                }
            } else if (path.equals("/performance.html") || path.equals("/performance")) {
                if (!serveStaticFile(out, "local/performance.html")) {
                    HttpResponse.sendError(out, 404, "performance.html not found");
                }
            } else if (path.equals("/abrp.html") || path.equals("/abrp")) {
                if (!serveStaticFile(out, "local/abrp.html")) {
                    HttpResponse.sendError(out, 404, "abrp.html not found");
                }
            } else if (path.equals("/mqtt.html") || path.equals("/mqtt")) {
                if (!serveStaticFile(out, "local/mqtt.html")) {
                    HttpResponse.sendError(out, 404, "mqtt.html not found");
                }
            } else if (path.equals("/trips.html") || path.equals("/trips")) {
                if (!serveStaticFile(out, "local/trips.html")) {
                    HttpResponse.sendError(out, 404, "trips.html not found");
                }
            } else if (path.startsWith("/shared/") || path.startsWith("/local/")) {
                String filePath = path.substring(1);
                if (!serveStaticFile(out, filePath)) {
                    HttpResponse.sendError(out, 404, "Not Found: " + path);
                }
            }
            // Core camera APIs (kept inline for simplicity)
            else if (path.startsWith("/snapshot/")) {
                int camId = Integer.parseInt(path.substring(10));
                sendSnapshot(out, camId);
            } else if (path.equals("/status")) {
                sendStatus(out);
            } else if (path.startsWith("/api/start/")) {
                int camId = Integer.parseInt(path.substring(11));
                CameraDaemon.startCamera(camId, true, false);
                HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"start\",\"camera\":" + camId + "}");
            } else if (path.startsWith("/api/view/")) {
                int camId = Integer.parseInt(path.substring(10));
                CameraDaemon.startCamera(camId, true, true);
                HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"view\",\"camera\":" + camId + "}");
            } else if (path.startsWith("/api/stop/")) {
                int camId = Integer.parseInt(path.substring(10));
                CameraDaemon.stopCamera(camId);
                HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"stop\",\"camera\":" + camId + "}");
            } else if (path.equals("/api/stopall")) {
                CameraDaemon.stopAllCameras();
                HttpResponse.sendJson(out, "{\"status\":\"ok\",\"action\":\"stopall\"}");
            } else if (path.equals("/api/recording/mode")) {
                // Get/Set recording mode
                if (method.equals("GET")) {
                    String currentMode = CameraDaemon.getRecordingMode();
                    HttpResponse.sendJson(out, "{\"status\":\"ok\",\"mode\":\"" + currentMode + "\"}");
                } else if (method.equals("POST")) {
                    JSONObject json = new JSONObject(body);
                    String mode = json.optString("mode", "");
                    if (!mode.isEmpty()) {
                        CameraDaemon.setRecordingMode(mode);
                        HttpResponse.sendJson(out, "{\"status\":\"ok\",\"mode\":\"" + mode + "\"}");
                    } else {
                        HttpResponse.sendJson(out, "{\"status\":\"error\",\"message\":\"No mode specified\"}");
                    }
                } else {
                    HttpResponse.sendError(out, 405, "Method Not Allowed");
                }
            } else if (path.startsWith("/h264/")) {
                // Deprecated HTTP streaming
                JSONObject response = new JSONObject();
                response.put("error", "HTTP streaming deprecated. Use WebSocket on port 8887");
                response.put("wsUrl", "ws://" + client.getLocalAddress().getHostAddress() + ":8887");
                HttpResponse.sendJson(out, response.toString());
            } else if (path.startsWith("/view/")) {
                // Legacy view page - redirect
                HttpResponse.sendHtml(out, "<html><head><meta http-equiv='refresh' content='0;url=/'></head></html>");
            } else {
                HttpResponse.sendError(out, 404, "Not Found");
            }
        } catch (Exception e) {
            CameraDaemon.log("HTTP error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception e) {}
        }
    }

    /**
     * Routes requests to modular API handlers.
     * @return true if handled by a handler
     */
    private boolean routeToHandlers(String method, String path, String body, String rangeHeader, OutputStream out) throws Exception {
        // Recordings API (with Range header support for video seeking) + thumbnails + event timelines
        if (path.startsWith("/api/recordings") || path.startsWith("/video/") || 
            path.startsWith("/thumb/") || path.startsWith("/api/events/")) {
            return RecordingsApiHandler.handleWithRange(method, path, rangeHeader, out);
        }
        
        // Surveillance API
        if (path.startsWith("/api/surveillance/safe-locations")) {
            return SafeLocationApiHandler.handle(method, path, body, out);
        }
        if (path.startsWith("/api/surveillance")) {
            return SurveillanceApiHandler.handle(method, path, body, out);
        }
        
        // Streaming API
        if (path.startsWith("/api/stream")) {
            return StreamingApiHandler.handle(method, path, body, out);
        }
        
        // GPS API
        if (path.startsWith("/api/gps")) {
            return GpsApiHandler.handle(method, path, body, out);
        }
        
        // Quality Settings API (includes storage settings)
        if (path.startsWith("/api/settings/")) {
            return QualitySettingsApiHandler.handle(method, path, body, out);
        }
        
        // ABRP API
        if (path.startsWith("/api/abrp/")) {
            return AbrpApiHandler.handle(method, path, body, out);
        }
        
        // MQTT API
        if (path.startsWith("/api/mqtt/")) {
            return MqttApiHandler.handle(method, path, body, out);
        }
        
        // Trip Analytics API
        if (path.startsWith("/api/trips")) {
            com.overdrive.app.trips.TripAnalyticsManager tam = CameraDaemon.getTripAnalyticsManager();
            if (tam != null) {
                com.overdrive.app.trips.TripApiHandler handler = new com.overdrive.app.trips.TripApiHandler(tam);
                org.json.JSONObject result = handler.handleRequest(path, method, null, body);
                if (result != null) {
                    int status = result.optInt("_status", 200);
                    result.remove("_status");
                    if (status == 200) {
                        HttpResponse.sendJson(out, result.toString());
                    } else {
                        HttpResponse.sendError(out, status, result.toString());
                    }
                    return true;
                }
            } else {
                HttpResponse.sendJsonError(out, "Trip analytics not initialized");
                return true;
            }
        }
        
        // Performance API
        if (path.startsWith("/api/performance")) {
            return PerformanceApiHandler.handle(method, path, body, out);
        }
        
        // External Storage API (SD card and CDR cleanup)
        if (path.startsWith("/api/storage/external")) {
            return ExternalStorageApiHandler.handle(path, method, body, out);
        }
        
        return false;
    }
    
    private void sendSnapshot(OutputStream out, int viewId) throws Exception {
        com.overdrive.app.surveillance.GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
        if (gpuPipeline == null || gpuPipeline.getCamera() == null) {
            HttpResponse.sendError(out, 404, "GPU pipeline not available for view " + viewId);
            return;
        }

        byte[] frame = gpuPipeline.getCamera().getLatestJpegFrame(viewId);
        if (frame == null) {
            HttpResponse.sendError(out, 404, "No frame available for view " + viewId);
            return;
        }

        String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + frame.length + "\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n";
        out.write(headers.getBytes());
        out.flush();
        
        int offset = 0;
        while (offset < frame.length) {
            int count = Math.min(frame.length - offset, 1024);
            out.write(frame, offset, count);
            out.flush();
            offset += count;
        }
    }

    private void sendStatus(OutputStream out) throws Exception {
        JSONObject status = new JSONObject();
        status.put("status", "ok");
        status.put("deviceId", CameraDaemon.getDeviceId());
        status.put("recording", TcpCommandServer.getRecordingCameras());
        status.put("viewing", TcpCommandServer.getViewOnlyCameras());
        status.put("active", TcpCommandServer.getActiveCameras());
        status.put("streaming", TcpCommandServer.getStreamingCameras());
        status.put("available", TcpCommandServer.getAvailableCameras());
        status.put("battery", BatteryMonitor.getBatteryInfo());
        status.put("acc", AccMonitor.isAccOn());
        
        // Safe zone status (so UI can show suppressed state)
        com.overdrive.app.surveillance.SafeLocationManager safeMgr =
            com.overdrive.app.surveillance.SafeLocationManager.getInstance();
        status.put("safeZoneSuppressed", CameraDaemon.isSafeZoneSuppressed());
        status.put("inSafeZone", safeMgr.isInSafeZone());
        if (safeMgr.getCurrentZoneName() != null) {
            status.put("safeZoneName", safeMgr.getCurrentZoneName());
        }
        
        // Vehicle data (charging state and power)
        try {
            com.overdrive.app.monitor.VehicleDataMonitor vehicleMonitor =
                com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            
            com.overdrive.app.monitor.ChargingStateData chargingState = vehicleMonitor.getChargingState();
            if (chargingState != null) {
                JSONObject charging = new JSONObject();
                charging.put("stateName", chargingState.stateName);
                charging.put("status", chargingState.status.name());
                charging.put("chargingPowerKW", chargingState.chargingPowerKW);
                charging.put("isDischarging", chargingState.isDischarging);
                charging.put("isError", chargingState.isError);
                status.put("charging", charging);
            }
            
            com.overdrive.app.monitor.BatterySocData socData = vehicleMonitor.getBatterySoc();
            if (socData != null) {
                JSONObject soc = new JSONObject();
                soc.put("percent", socData.socPercent);
                soc.put("isLow", socData.isLow);
                soc.put("isCritical", socData.isCritical);
                soc.put("status", socData.getStatus());
                status.put("soc", soc);
            }
            
            com.overdrive.app.monitor.DrivingRangeData rangeData = vehicleMonitor.getDrivingRange();
            if (rangeData != null) {
                JSONObject range = new JSONObject();
                range.put("elecRangeKm", rangeData.elecRangeKm);
                range.put("fuelRangeKm", rangeData.fuelRangeKm);
                range.put("totalRangeKm", rangeData.totalRangeKm);
                range.put("isLow", rangeData.isLow);
                range.put("isCritical", rangeData.isCritical);
                range.put("status", rangeData.getStatus());
                status.put("range", range);
            }
        } catch (Exception e) {
            // Vehicle data not available
        }
        
        // SOH from SohEstimator (persisted file fallback if estimator not available)
        try {
            JSONObject soh = new JSONObject();
            boolean hasSoh = false;
            
            com.overdrive.app.monitor.SocHistoryDatabase socDb = com.overdrive.app.monitor.SocHistoryDatabase.getInstance();
            com.overdrive.app.abrp.SohEstimator sohEst = socDb != null ? socDb.getSohEstimator() : null;
            if (sohEst != null && sohEst.hasEstimate()) {
                soh.put("percent", Math.round(sohEst.getCurrentSoh() * 10) / 10.0);
                soh.put("estimatedCapacityKwh", Math.round(sohEst.getEstimatedCapacityKwh() * 10) / 10.0);
                soh.put("nominalCapacityKwh", sohEst.getNominalCapacityKwh());
                hasSoh = true;
            } else {
                // Fallback: read from persisted file
                java.io.File sohFile = new java.io.File("/data/local/tmp/abrp_soh_estimate.properties");
                if (sohFile.exists()) {
                    java.util.Properties props = new java.util.Properties();
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(sohFile)) {
                        props.load(fis);
                    }
                    String sohStr = props.getProperty("soh_percent");
                    if (sohStr != null) {
                        double sohVal = Double.parseDouble(sohStr);
                        if (sohVal > 0 && sohVal <= 110) {
                            soh.put("percent", Math.round(sohVal * 10) / 10.0);
                            hasSoh = true;
                        }
                    }
                }
            }
            
            if (hasSoh) status.put("soh", soh);
        } catch (Exception e) {
            // SOH not available
        }
        
        // GPU surveillance status — only true when actually in sentry/surveillance mode,
        // not when pipeline is running for normal recording (CONTINUOUS, PROXIMITY_GUARD)
        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        status.put("gpuSurveillance", pipeline != null && pipeline.isSurveillanceMode());
        
        // Recording mode details (for status overlay)
        try {
            JSONObject recordingStatus = new JSONObject();
            com.overdrive.app.recording.RecordingModeManager rmm = CameraDaemon.getRecordingModeManager();
            if (rmm != null) {
                recordingStatus.put("configuredMode", rmm.getCurrentMode().name());
                recordingStatus.put("isRecording", pipeline != null && pipeline.isRecording());
                recordingStatus.put("pipelineRunning", pipeline != null && pipeline.isRunning());
                recordingStatus.put("gear", com.overdrive.app.recording.RecordingModeManager.gearToString(rmm.getCurrentGear()));
                recordingStatus.put("accOn", rmm.isAccOn());
            } else {
                recordingStatus.put("configuredMode", "UNKNOWN");
                recordingStatus.put("isRecording", false);
                recordingStatus.put("pipelineRunning", false);
            }
            status.put("recordingStatus", recordingStatus);
        } catch (Exception e) {
            // Recording status not available
        }
        
        // Trip analytics status (for status overlay)
        try {
            JSONObject tripStatus = new JSONObject();
            com.overdrive.app.trips.TripAnalyticsManager tam = CameraDaemon.getTripAnalyticsManager();
            if (tam != null) {
                tripStatus.put("enabled", tam.isEnabled());
                tripStatus.put("tripActive", tam.isTripActive());
                com.overdrive.app.trips.TripRecord activeTrip = tam.getActiveTrip();
                if (activeTrip != null) {
                    tripStatus.put("tripStartTime", activeTrip.startTime);
                    tripStatus.put("tripDurationSec", (System.currentTimeMillis() - activeTrip.startTime) / 1000);
                }
            } else {
                tripStatus.put("enabled", false);
                tripStatus.put("tripActive", false);
            }
            status.put("tripStatus", tripStatus);
        } catch (Exception e) {
            // Trip status not available
        }
        
        // GPS location
        com.overdrive.app.monitor.GpsMonitor gps = com.overdrive.app.monitor.GpsMonitor.getInstance();
        status.put("gps", gps.getLocationJson());
        
        // Network info (WiFi SSID + IP or Mobile Data)
        status.put("network", com.overdrive.app.monitor.NetworkMonitor.getNetworkInfo());
        
        HttpResponse.sendJson(out, status.toString());
    }

    /**
     * Serves static files from WEB_ROOT with streaming for large files.
     */
    private boolean serveStaticFile(OutputStream out, String relativePath) {
        if (relativePath.contains("..")) {
            return false;
        }
        
        File file = new File(WEB_ROOT, relativePath);
        if (!file.exists() || !file.isFile()) {
            return false;
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            String contentType = getContentType(relativePath);
            
            // HTML pages must always revalidate so the user gets the latest UI logic.
            // Shared static assets (JS/CSS/fonts/images) ship inside the APK and never
            // change without an app update, so we let the browser cache them to avoid
            // re-downloading ~360KB on every page load.
            String cacheControl;
            if (relativePath.endsWith(".html")) {
                cacheControl = "no-store, no-cache, must-revalidate, max-age=0";
            } else {
                cacheControl = "public, max-age=86400";
            }
            
            StringBuilder headers = new StringBuilder();
            headers.append("HTTP/1.1 200 OK\r\n")
                   .append("Content-Type: ").append(contentType).append("\r\n")
                   .append("Content-Length: ").append(file.length()).append("\r\n")
                   .append("Cache-Control: ").append(cacheControl).append("\r\n");
            if (relativePath.endsWith(".html")) {
                headers.append("Pragma: no-cache\r\n")
                       .append("Expires: 0\r\n");
            }
            headers.append("Connection: close\r\n\r\n");
            out.write(headers.toString().getBytes());
            
            // Stream in 16KB chunks
            byte[] buffer = new byte[16384];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            out.flush();
            
            CameraDaemon.log("Served static: " + relativePath + " (" + file.length() + " bytes)");
            return true;
            
        } catch (Exception e) {
            CameraDaemon.log("Static file error: " + relativePath + " - " + e.getMessage());
            return false;
        }
    }
    
    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".wasm")) return "application/wasm";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    // ==================== WEBSOCKET STREAMING ====================
    
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    
    /**
     * Handles WebSocket upgrade on /ws path for single-port streaming.
     */
    private void handleWebSocketUpgrade(Socket client, String websocketKey) {
        try {
            CameraDaemon.log("WebSocket upgrade requested");
            
            String acceptKey = computeWebSocketAccept(websocketKey);
            
            OutputStream out = client.getOutputStream();
            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            out.write(response.getBytes());
            out.flush();
            
            CameraDaemon.log("WebSocket handshake complete");
            streamH264ToWebSocket(client);
            
        } catch (Exception e) {
            CameraDaemon.log("WebSocket upgrade error: " + e.getMessage());
        }
    }
    
    private String computeWebSocketAccept(String key) throws Exception {
        String concat = key + WS_MAGIC;
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest(concat.getBytes("UTF-8"));
        return Base64.encodeToString(hash, Base64.NO_WRAP);
    }

    /**
     * SOTA: Streams H.264 frames over WebSocket with zero-restart attach.
     *
     * Instead of force-restarting the encoder on every client connect (which causes
     * a 700ms gap and corrupt first frames), we:
     * 1. Reuse the existing encoder if streaming is already enabled
     * 2. Request an IDR keyframe via MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME
     * 3. Send cached SPS/PPS immediately so the decoder can initialize
     * 4. Wait for the IDR to arrive before sending P-frames
     *
     * This gives instant stream start with no encoder restart, no frame corruption,
     * and no broken pipe from the client timing out during restart.
     */
    private void streamH264ToWebSocket(Socket client) {
        CameraDaemon.log("Starting H.264 WebSocket stream");
        
        final BlockingQueue<byte[]> frameQueue = new ArrayBlockingQueue<>(60);
        final boolean[] running = {true};
        
        try {
            client.setSoTimeout(0);
            client.setTcpNoDelay(true);
            client.setSendBufferSize(256 * 1024);
            final OutputStream out = new java.io.BufferedOutputStream(
                client.getOutputStream(), 128 * 1024);
            
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
            if (pipeline == null) {
                CameraDaemon.log("WS: Pipeline not available");
                sendWebSocketClose(out, 1011, "Pipeline not available");
                return;
            }
            
            // Auto-start pipeline if needed
            if (!pipeline.isRunning()) {
                CameraDaemon.log("WS: Auto-starting pipeline");
                pipeline.start();
                Thread.sleep(500);
            }
            
            GpuPipelineConfig.StreamingQuality q = GpuPipelineConfig.StreamingQuality.fromString(
                StreamingApiHandler.getStreamingQuality());
            
            int savedViewMode = pipeline.getStreamViewMode();
            if (savedViewMode < 0) savedViewMode = 0;
            
            // SOTA: Reuse existing encoder if streaming is already enabled at same quality.
            // Only restart if not enabled or quality changed.
            boolean needsRestart = !pipeline.isStreamingEnabled();
            
            // Check if quality changed — need restart for new resolution
            if (!needsRestart && pipeline.isStreamingEnabled()) {
                HardwareEventRecorderGpu existingEncoder = pipeline.getStreamEncoder();
                if (existingEncoder != null) {
                    // Compare current encoder resolution with requested quality
                    com.overdrive.app.streaming.GpuStreamScaler scaler = pipeline.getStreamScaler();
                    if (scaler != null) {
                        int currentWidth = scaler.getWidth();
                        int currentHeight = scaler.getHeight();
                        if (currentWidth != q.width || currentHeight != q.height) {
                            CameraDaemon.log("WS: Quality changed (" + currentWidth + "x" + currentHeight + 
                                " → " + q.width + "x" + q.height + ") — restarting encoder");
                            needsRestart = true;
                            pipeline.disableStreaming();
                            Thread.sleep(200);
                        }
                    }
                }
            }
            
            if (needsRestart) {
                CameraDaemon.log("WS: Enabling streaming - " + q.displayName);
                pipeline.enableStreaming(q.width, q.height, q.fps, q.bitrate);
                Thread.sleep(500);
            } else {
                CameraDaemon.log("WS: Reusing existing stream encoder (no restart)");
            }
            
            if (savedViewMode > 0) {
                pipeline.setStreamViewMode(savedViewMode);
                CameraDaemon.log("WS: View mode " + savedViewMode);
            }
            
            HardwareEventRecorderGpu encoder = pipeline.getStreamEncoder();
            if (encoder == null) {
                CameraDaemon.log("WS: Stream encoder not available");
                sendWebSocketClose(out, 1011, "Encoder not available");
                return;
            }
            
            // SOTA: Send cached SPS/PPS immediately from WebSocketStreamServer
            // so the client decoder can initialize before the first frame arrives.
            com.overdrive.app.streaming.WebSocketStreamServer wsServer = pipeline.getWebSocketServer();
            boolean spsPpsSent = false;
            if (wsServer != null) {
                byte[] cachedSpsPps = wsServer.getCachedSpsPps();
                if (cachedSpsPps != null && cachedSpsPps.length > 0) {
                    try {
                        sendWebSocketBinaryFrame(out, cachedSpsPps);
                        spsPpsSent = true;
                        CameraDaemon.log("WS: Sent cached SPS/PPS (" + cachedSpsPps.length + " bytes)");
                    } catch (Exception e) {
                        CameraDaemon.log("WS: Failed to send cached SPS/PPS: " + e.getMessage());
                    }
                }
            }
            
            // SOTA: Request IDR keyframe so client gets a clean decode start.
            // This is instant — no encoder restart needed.
            encoder.requestSyncFrame();
            CameraDaemon.log("WS: IDR keyframe requested");
            
            // Also request SPS/PPS re-send if we didn't have cached ones
            if (!spsPpsSent) {
                // The encoder will send SPS/PPS before the next IDR via the callback
                CameraDaemon.log("WS: Waiting for SPS/PPS from encoder");
            }
            
            // Stream callback with congestion control
            final boolean[] gotKeyframe = {spsPpsSent};  // Skip waiting if we already sent SPS/PPS
            HardwareEventRecorderGpu.StreamCallback callback = new HardwareEventRecorderGpu.StreamCallback() {
                @Override
                public void onSpsPps(ByteBuffer sps, ByteBuffer pps) {
                    int spsSize = sps.remaining();
                    int ppsSize = pps.remaining();
                    byte[] combined = new byte[spsSize + ppsSize];
                    sps.get(combined, 0, spsSize);
                    pps.get(combined, spsSize, ppsSize);
                    frameQueue.offer(combined);
                    gotKeyframe[0] = true;
                    CameraDaemon.log("WS: Queued SPS/PPS (" + combined.length + " bytes)");
                }
                
                @Override
                public void onH264Packet(ByteBuffer data, android.media.MediaCodec.BufferInfo info) {
                    // SOTA: Drop P-frames until we've sent SPS/PPS + IDR
                    // Sending P-frames before the decoder has SPS/PPS causes decode failure
                    if (!gotKeyframe[0]) {
                        boolean isKeyframe = (info.flags & android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        if (!isKeyframe) return;  // Drop P-frames before first keyframe
                        gotKeyframe[0] = true;
                    }
                    
                    if (frameQueue.remainingCapacity() > 0) {
                        byte[] frame = new byte[info.size];
                        data.position(info.offset);
                        data.get(frame);
                        frameQueue.offer(frame);
                    }
                    // If queue is full, drop frame (congestion control)
                }
            };
            
            encoder.setStreamCallback(callback);
            CameraDaemon.log("WS: Stream callback registered");
            
            if (wsServer != null) {
                wsServer.registerExternalClient();
            }
            
            long lastFrameTime = System.currentTimeMillis();
            int frameCount = 0;
            
            try {
                while (running[0] && !client.isClosed()) {
                    byte[] frame = frameQueue.poll(5, TimeUnit.SECONDS);
                    
                    if (frame != null) {
                        try {
                            // Log first few frames for debugging
                            if (frameCount < 5) {
                                CameraDaemon.log("WS: Frame " + frameCount + " size=" + frame.length + " bytes");
                            }
                            sendWebSocketBinaryFrame(out, frame);
                            lastFrameTime = System.currentTimeMillis();
                            frameCount++;
                            
                            if (frameCount % 300 == 0) {
                                CameraDaemon.log("WS: Sent " + frameCount + " frames");
                            }
                        } catch (java.net.SocketException e) {
                            CameraDaemon.log("WS: Client disconnected (" + e.getMessage() + ")");
                            break;
                        } catch (java.io.IOException e) {
                            CameraDaemon.log("WS: Write error (" + e.getMessage() + ")");
                            break;
                        }
                    } else {
                        // No frame for 5 seconds — send ping to keep alive
                        try {
                            out.write(new byte[]{(byte)0x89, 0x00});
                            out.flush();
                        } catch (Exception e) {
                            CameraDaemon.log("WS: Ping failed, client gone");
                            break;
                        }
                        
                        if (System.currentTimeMillis() - lastFrameTime > 60000) {
                            CameraDaemon.log("WS: Idle timeout (60s) - closing");
                            break;
                        }
                    }
                }
            } finally {
                if (wsServer != null) {
                    wsServer.unregisterExternalClient();
                }
            }
            
            encoder.clearStreamCallback();
            CameraDaemon.log("WS: Stream ended (" + frameCount + " frames sent)");
            
        } catch (Exception e) {
            CameraDaemon.log("WS stream error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception e) {}
        }
    }

    /**
     * SOTA: Send binary data as WebSocket frame(s) with fragmentation for large frames.
     * Frames larger than MAX_WS_FRAME_SIZE are split into continuation frames
     * to prevent TCP buffer overflow on constrained networks (BYD WiFi AP).
     */
    private static final int MAX_WS_FRAME_SIZE = 32768;  // 32KB per WebSocket frame
    
    private void sendWebSocketBinaryFrame(OutputStream out, byte[] data) throws Exception {
        if (data.length <= MAX_WS_FRAME_SIZE) {
            // Small frame — send as single message
            sendWebSocketRawFrame(out, data, 0, data.length, 0x82, true);
        } else {
            // Large frame — fragment into continuation frames
            int offset = 0;
            boolean first = true;
            while (offset < data.length) {
                int chunkSize = Math.min(MAX_WS_FRAME_SIZE, data.length - offset);
                boolean last = (offset + chunkSize >= data.length);
                int opcode = first ? 0x02 : 0x00;  // binary for first, continuation for rest
                sendWebSocketRawFrame(out, data, offset, chunkSize, opcode, last);
                offset += chunkSize;
                first = false;
            }
        }
        out.flush();
    }
    
    private void sendWebSocketRawFrame(OutputStream out, byte[] data, int offset, int len, 
                                        int opcode, boolean fin) throws Exception {
        int firstByte = (fin ? 0x80 : 0x00) | opcode;
        out.write(firstByte);
        
        if (len <= 125) {
            out.write(len);
        } else if (len <= 65535) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }
        
        out.write(data, offset, len);
    }
    
    private void sendWebSocketClose(OutputStream out, int code, String reason) {
        try {
            byte[] reasonBytes = reason.getBytes("UTF-8");
            int len = 2 + reasonBytes.length;
            
            out.write(0x88);  // FIN + close opcode
            out.write(len);
            out.write((code >> 8) & 0xFF);
            out.write(code & 0xFF);
            out.write(reasonBytes);
            out.flush();
        } catch (Exception e) {
            // Ignore
        }
    }
    
    // ==================== STATIC ACCESSORS (for backward compatibility) ====================
    
    /**
     * Loads persisted settings. Delegates to QualitySettingsApiHandler.
     */
    public static void loadPersistedSettings() {
        QualitySettingsApiHandler.loadPersistedSettings();
    }
    
    // Getters delegate to handlers
    public static String getRecordingQuality() { return QualitySettingsApiHandler.getRecordingQuality(); }
    public static String getStreamingQuality() { return StreamingApiHandler.getStreamingQuality(); }
    public static String getRecordingBitrate() { return QualitySettingsApiHandler.getRecordingBitrate(); }
    public static String getRecordingCodec() { return QualitySettingsApiHandler.getRecordingCodec(); }
    
    // Setters delegate to handlers
    public static void setRecordingQuality(String quality) { QualitySettingsApiHandler.setRecordingQuality(quality); }
    public static void setStreamingQuality(String quality) { StreamingApiHandler.setStreamingQuality(quality); }
    public static void setRecordingBitrate(String bitrate) { QualitySettingsApiHandler.setRecordingBitrate(bitrate); }
    public static void setRecordingCodec(String codec) { QualitySettingsApiHandler.setRecordingCodec(codec); }
    
    // Static setters for IPC server
    public static void setRecordingBitrateStatic(String bitrate) { QualitySettingsApiHandler.setRecordingBitrateStatic(bitrate); }
    public static void setRecordingCodecStatic(String codec) { QualitySettingsApiHandler.setRecordingCodecStatic(codec); }
    public static void persistSettingsStatic() { QualitySettingsApiHandler.persistSettings(); }
}
