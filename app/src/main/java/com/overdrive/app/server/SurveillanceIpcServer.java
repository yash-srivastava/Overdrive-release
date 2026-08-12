package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IPC server for surveillance configuration.
 * Listens on port 19877 for surveillance config commands from the app UI.
 * 
 * SOTA: Uses thread pool to prevent thread exhaustion under load.
 */
public class SurveillanceIpcServer implements Runnable {
    private static final String TAG = "SurveillanceIPC";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private final int port;
    // volatile: written by the run() thread on every (re)bind, read+closed by
    // stop() on the shutdown thread. Without volatile, stop() could see a stale
    // reference and fail to close the LIVE listen socket — leaving accept()
    // blocked and the server thread leaked on shutdown. The rebind loop
    // reassigns this more often than the old single-shot bind, so safe
    // publication matters.
    private volatile ServerSocket serverSocket;
    private volatile boolean running = true;
    
    // Cached thread pool (not fixed): persistent clients (IMU + GPS sidecars,
    // peer daemons) now hold a worker blocked in readLine() for the life of
    // their connection, so a fixed-8 pool could be saturated by a handful of
    // long-lived holders + a burst of one-shot callers, starving the accept
    // dispatch. A cached pool grows on demand and reaps idle threads after 60s,
    // so steady state is ~one thread per live persistent connection plus
    // transient threads for one-shot commands — no artificial ceiling, no idle
    // cost when nothing is connected. Localhost JSON traffic, so thread count
    // stays small in practice (a few persistent + brief one-shots).
    private final ExecutorService threadPool = Executors.newCachedThreadPool();

    // ABRP integration references (set by CameraDaemon)
    private static volatile com.overdrive.app.abrp.AbrpConfig abrpConfig;
    private static volatile com.overdrive.app.abrp.AbrpTelemetryService abrpService;

    public static void setAbrpReferences(com.overdrive.app.abrp.AbrpConfig config, com.overdrive.app.abrp.AbrpTelemetryService service) {
        abrpConfig = config;
        abrpService = service;
    }

    /**
     * Reset path used by the bulk Reset Data feature: deletes the ABRP token,
     * persists the change, and stops the running telemetry service so cached
     * RAM credentials don't keep uploading after the user wiped them.
     * Mirrors the proven sequence in {@link #handleDeleteAbrpToken}.
     *
     * @return true if the reset ran (token cleared + persisted)
     */
    public static boolean resetAbrpForBulkWipe() {
        if (abrpConfig == null) return false;
        abrpConfig.deleteToken();
        abrpConfig.save();
        if (abrpService != null && abrpService.isRunning()) {
            abrpService.stop();
        }
        return true;
    }

    // MQTT integration reference (set by CameraDaemon)
    private static volatile com.overdrive.app.mqtt.MqttConnectionManager mqttManager;

    public static void setMqttManager(com.overdrive.app.mqtt.MqttConnectionManager manager) {
        mqttManager = manager;
    }
    
    public SurveillanceIpcServer(int port) {
        this.port = port;
    }
    
    @Override
    public void run() {
        // DURABILITY: bind in a RETRY loop, never let a bind failure kill the
        // server thread. This is the config-write + sidecar lifeline (port 19877):
        // app-process config writes route through it for atomic durability, and
        // the IMU/GPS sidecars + peer daemons hold persistent connections to it.
        // If the daemon crashes and respawns into a transient EADDRINUSE (the old
        // listen socket / a client connection still in TIME_WAIT from the killed
        // predecessor), a single-shot bind would throw, the thread would return,
        // and 19877 would stay DEAD for the entire life of this daemon process —
        // every app config write would silently fall back to the truncation-prone
        // local path and the sidecars could never reconnect. So we mirror the
        // proven TcpCommandServer/HttpServer pattern: outer while(running) loop,
        // setReuseAddress(true) BEFORE bind (the immediate-bind constructor can't
        // do that), BindException → sleep+retry, and a listen-socket error breaks
        // the inner accept loop to rebind rather than dying. Idempotent: the next
        // iteration closes any half-open serverSocket before re-binding.
        while (running) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try { serverSocket.close(); } catch (Exception ignored) {}
                }
                ServerSocket ss = new ServerSocket();   // unbound — lets us set reuse first
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), port), 50);
                serverSocket = ss;
                logger.info("Surveillance IPC server listening on 127.0.0.1:" + port);

                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        // Offload to the cached thread pool (persistent clients
                        // hold a worker for their connection lifetime). If stop()
                        // raced us and called threadPool.shutdownNow(), execute()
                        // throws RejectedExecutionException — close the just-
                        // accepted client so its FD isn't leaked (matters only if
                        // stop() is ever used for an in-process restart; on the
                        // current SIGKILL/process-exit shutdown the kernel reclaims
                        // it anyway, but this keeps the pattern correct).
                        try {
                            threadPool.execute(() -> handleClient(client));
                        } catch (java.util.concurrent.RejectedExecutionException rej) {
                            try { client.close(); } catch (Exception ignored) {}
                        }
                    } catch (java.net.SocketException se) {
                        // Listen socket closed/errored (shutdown, or transient) —
                        // leave the accept loop and let the outer loop rebind
                        // (unless we're shutting down).
                        if (running) {
                            logger.warn("IPC accept socket error — rebinding: " + se.getMessage());
                        }
                        break;
                    } catch (Exception e) {
                        // Per-accept transient (e.g. EMFILE) — log and keep accepting.
                        if (running) {
                            logger.error("Error accepting client", e);
                        }
                    }
                }
                // Inner accept loop exited via a listen-socket SocketException
                // (not shutdown). Back off before the outer loop rebinds so a
                // recurring transient listener error can't hot-spin a core
                // (close→bind→accept→throw→repeat). Mirrors TcpCommandServer's
                // post-inner-loop sleep. Skipped on shutdown (running==false).
                if (running) {
                    sleepQuietly(1000);
                }
            } catch (java.net.BindException be) {
                // Port still held by a just-killed predecessor's TIME_WAIT, or a
                // racing respawn. Retry — do NOT die. The watchdog's respawn delay
                // plus this backoff comfortably exceeds localhost TIME_WAIT.
                if (running) {
                    logger.warn("IPC port " + port + " in use — retrying in 3s: " + be.getMessage());
                    sleepQuietly(3000);
                }
            } catch (Exception e) {
                if (running) {
                    logger.error("IPC server bind/loop error — retrying in 3s", e);
                    sleepQuietly(3000);
                }
            }
        }
        // Clean shutdown exit.
        if (serverSocket != null && !serverSocket.isClosed()) {
            try { serverSocket.close(); } catch (Exception ignored) {}
        }
        logger.info("Surveillance IPC server stopped");
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void handleClient(Socket client) {
        try {
            // Read timeout: bounds how long a worker waits for the NEXT line on
            // an idle-but-open connection. A client that opens the socket and
            // never sends would otherwise pin a worker forever. 30s (raised
            // from 5s) accommodates persistent clients — the hot sidecars (IMU
            // ~10/s, GPS ~1/s) now hold ONE connection and stream many commands
            // over it instead of reconnecting per message, so the worker legitimately
            // blocks between bursts; 30s is well above their inter-message gap
            // yet still reaps a truly dead half-open socket. Per-message clients
            // are unaffected — they send one line, read the reply, close, and
            // our readLine() then returns null (EOF) so we exit the loop at once.
            client.setSoTimeout(30_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter out = new PrintWriter(client.getOutputStream(), true);

            // Loop over newline-delimited commands so a single persistent socket
            // can carry a stream of requests. Backward-compatible with the old
            // one-shot clients: they close after one exchange → readLine() == null
            // → loop ends. This converts ~10 TCP connect/teardown per second
            // (IMU) + ~1/s (GPS) into ZERO once the connection is established.
            String line;
            while ((line = in.readLine()) != null) {
                // Skip blank/whitespace lines instead of feeding them to the
                // JSON parser. A connect-then-send-empty-line probe (and the
                // brief window between a client's connect and its first write)
                // yields "" here; new JSONObject("") throws
                // "End of input at character 0", which under the per-connection
                // loop was logged ~3x/sec as "Error handling client". Treat an
                // empty line as a keep-alive no-op, not a malformed command.
                if (line.trim().isEmpty()) {
                    continue;
                }
                // A SINGLE malformed command must not tear down the whole
                // (now persistent) connection or spam the log — catch per-line,
                // reply with an error, and keep serving the next command.
                try {
                    JSONObject request = new JSONObject(line);
                    JSONObject response = handleCommand(request);
                    // handleCommand returns null for fire-and-forget streaming
                    // commands (IMU_BATCH ~10/s, UPDATE_GPS ~1/s) whose sidecar
                    // clients never read the reply — skip the per-message
                    // JSONObject build + toString + write syscall on the hot path,
                    // and stop accumulating unread ack bytes in the socket buffer.
                    if (response != null) {
                        out.println(response.toString());
                    }
                } catch (org.json.JSONException je) {
                    logger.debug("IPC: ignoring malformed command line: " + je.getMessage());
                }
            }

            client.close();
        } catch (java.net.SocketTimeoutException ste) {
            // Idle persistent client (no command within the window) or a stuck
            // half-open socket — close and free the worker. The client's
            // reconnect-on-failure path re-establishes when it next has data.
            try { client.close(); } catch (Exception ignored) {}
        } catch (Exception e) {
            logger.error("Error handling client", e);
            try { client.close(); } catch (Exception ignored) {}
        }
    }
    
    private JSONObject handleCommand(JSONObject request) {
        JSONObject response = new JSONObject();
        
        try {
            String command = request.optString("command", "");
            
            switch (command) {
                // ==================== TELEGRAM DAEMON COMMANDS ====================
                // These commands are sent by TelegramBotDaemon for remote control
                
                case "START":
                    // Start surveillance (from Telegram /start command)
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true);
                    if (!com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                        CameraDaemon.enableSurveillance();   // fires OEM recalc
                        logger.info("Surveillance started via Telegram IPC");
                    } else {
                        logger.info("Surveillance preference saved via Telegram — will activate on ACC OFF");
                        // OEM resolver reads isSurveillanceEnabled into survSuppressed
                        try { com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                        catch (Throwable ignored) {}
                    }
                    response.put("success", true);
                    response.put("enabled", true);
                    response.put("message", "Surveillance enabled");
                    break;

                case "STOP":
                    // Stop surveillance (from Telegram /stop command)
                    CameraDaemon.disableSurveillance();   // fires OEM recalc
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false);
                    // Second recalc post-write so resolver sees the new master toggle.
                    try { com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                    catch (Throwable ignored) {}
                    logger.info("Surveillance stopped via Telegram IPC");
                    response.put("success", true);
                    response.put("enabled", false);
                    response.put("message", "Surveillance stopped");
                    break;
                    
                case "STATUS": {
                    // Get surveillance status (from Telegram /status command)
                    // Read from persisted config (not in-memory flag which can get stale)
                    boolean enabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
                    boolean active = CameraDaemon.isSurveillanceActive();
                    response.put("success", true);
                    response.put("enabled", enabled);
                    response.put("active", active);
                    response.put("recording", active);
                    logger.info("Status requested via Telegram IPC: enabled=" + enabled + ", active=" + active);
                    break;
                }
                
                // ==================== APP UI COMMANDS ====================
                // These commands are sent by the app UI for configuration
                
                case "ENABLE_SURVEILLANCE":
                    // Persist preference only — surveillance will auto-start on next ACC OFF
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true);
                    // OEM resolver: survSuppressed depends on master toggle.
                    try { com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                    catch (Throwable ignored) {}
                    logger.info("Surveillance preference set to ENABLED (will activate on ACC OFF)");
                    response.put("success", true);
                    response.put("enabled", true);
                    break;

                case "DISABLE_SURVEILLANCE":
                    // Persist preference and stop if currently running
                    com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false);
                    CameraDaemon.disableSurveillance();   // fires OEM recalc internally
                    logger.info("Surveillance preference set to DISABLED and stopped");
                    response.put("success", true);
                    response.put("enabled", false);
                    break;
                
                case "GET_CONFIG":
                    response.put("success", true);
                    response.put("config", getDefaultConfig());
                    break;
                    
                case "SET_CONFIG":
                    JSONObject config = request.optJSONObject("config");
                    if (config != null) {
                        applyConfig(config);
                    }
                    response.put("success", true);
                    response.put("message", "Config updated");
                    break;

                // Generic config write forwarded by the APP process (UID 10xxx)
                // via UnifiedConfigManager.routeWriteIfApp. The app cannot do an
                // atomic tmp+rename in sticky /data/local/tmp/, so it hands the
                // write to us (shell UID 2000) and we apply it in-process, where
                // saveConfigInternal takes the atomic path. This is the fix for
                // the truncation-on-kill total-settings-wipe.
                case "UPDATE_SECTION": {
                    String s = request.optString("section", "");
                    JSONObject data = request.optJSONObject("data");
                    boolean ok = false;
                    if (!s.isEmpty() && data != null) {
                        synchronized (CONFIG_LOCK) {
                            // forceReload BEFORE merge so an app-forwarded delta
                            // merges against fresh on-disk state, preserving the
                            // forceReload-before-merge invariant the cross-UID
                            // writers (RoadSenseOverlayService, UcmVisualSink)
                            // rely on — ext4 mtime has 1s granularity and our
                            // cache could otherwise hide a peer daemon's write.
                            com.overdrive.app.config.UnifiedConfigManager.forceReload();
                            ok = com.overdrive.app.config.UnifiedConfigManager.updateSection(s, data);
                        }
                    }
                    response.put("success", ok);
                    break;
                }

                case "UPDATE_VALUES": {
                    String s = request.optString("section", "");
                    JSONObject values = request.optJSONObject("values");
                    boolean ok = false;
                    if (!s.isEmpty() && values != null) {
                        java.util.Map<String, Object> map = new java.util.HashMap<>();
                        java.util.Iterator<String> it = values.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            map.put(k, values.get(k));
                        }
                        synchronized (CONFIG_LOCK) {
                            com.overdrive.app.config.UnifiedConfigManager.forceReload();
                            ok = com.overdrive.app.config.UnifiedConfigManager.updateValues(s, map);
                        }
                    }
                    response.put("success", ok);
                    break;
                }

                // ==================== CONFIG BACKUP / RESTORE ====================
                // Single core lives in ConfigBackupService; web + app + Telegram
                // all reach it through these two commands (no duplicated logic).

                // Build a backup bundle (settings + device-id snapshot). Read-only.
                // NOT public-mode gated (unlike update install): the owner opted to
                // allow backup/restore over the tunnel, relying on JWT auth alone —
                // /api/backup/* is a non-public path so AuthMiddleware enforces a
                // token in every mode. The bundle carries credentials, so the UI
                // warns to keep the file private.
                case "EXPORT_CONFIG": {
                    // includeTrips opts the (location-bearing) trip history into
                    // the bundle; default settings-only.
                    boolean includeTrips = request.optBoolean("includeTrips", false);
                    JSONObject bundle = com.overdrive.app.config.ConfigBackupService.buildBundle(
                            com.overdrive.app.updater.AppUpdater.getInstalledVersion(),
                            deviceModelString(),
                            System.currentTimeMillis(),
                            includeTrips);
                    response.put("success", true);
                    response.put("bundle", bundle);
                    break;
                }

                // Transactional whole-config restore. The atomic write + single
                // coordinated listener reload live in ConfigBackupService /
                // UnifiedConfigManager.saveConfig. CONFIG_LOCK serialises against
                // peer UPDATE_SECTION/UPDATE_VALUES writers in this JVM; the
                // file lock inside saveConfig serialises across daemon JVMs.
                case "REPLACE_CONFIG": {
                    // NOT public-mode gated — owner opted to allow remote restore
                    // over the tunnel (JWT-gated; confirm=true still required by the
                    // HTTP handler).
                    JSONObject bundle = request.optJSONObject("bundle");
                    if (bundle == null) {
                        response.put("success", false);
                        response.put("error", "missing bundle");
                        break;
                    }
                    com.overdrive.app.config.ConfigBackupService.ApplyResult res;
                    synchronized (CONFIG_LOCK) {
                        res = com.overdrive.app.config.ConfigBackupService.applyBundle(
                                bundle,
                                com.overdrive.app.updater.AppUpdater.getInstalledVersion(),
                                deviceModelString());
                    }
                    response.put("success", res.getSuccess());
                    response.put("message", res.getMessage());
                    response.put("warnings", new org.json.JSONArray(res.getWarnings()));
                    break;
                }

                case "GET_STATUS":
                    response.put("success", true);
                    response.put("status", getSurveillanceStatus());
                    break;
                    
                // ==================== VEHICLE DATA COMMANDS ====================
                // These commands provide access to BYD vehicle telemetry
                
                case "GET_VEHICLE_DATA":
                    response.put("success", true);
                    response.put("data", getVehicleData());
                    break;
                    
                case "GET_BATTERY_VOLTAGE":
                    response.put("success", true);
                    response.put("data", getBatteryVoltageData());
                    break;
                    
                case "GET_BATTERY_POWER":
                    response.put("success", true);
                    response.put("data", getBatteryPowerData());
                    break;
                    
                case "GET_BATTERY_SOC":
                    response.put("success", true);
                    response.put("data", getBatterySocData());
                    break;
                    
                case "GET_CHARGING_STATE":
                    response.put("success", true);
                    response.put("data", getChargingStateData());
                    break;
                    
                case "GET_CHARGING_POWER":
                    response.put("success", true);
                    response.put("data", getChargingPowerData());
                    break;
                    
                case "GET_DRIVING_RANGE":
                    response.put("success", true);
                    response.put("data", getDrivingRangeData());
                    break;
                    
                case "GET_ROI":
                    response.put("success", true);
                    response.put("roi", getRoiData());
                    break;
                    
                case "SET_ROI":
                    JSONObject roiData = request.optJSONObject("roi");
                    if (roiData != null) {
                        applyRoi(roiData);
                    }
                    response.put("success", true);
                    response.put("message", "ROI updated");
                    break;
                
                // ==================== SAFE LOCATION COMMANDS ====================
                
                case "GET_SAFE_LOCATIONS":
                    response = com.overdrive.app.surveillance.SafeLocationManager.getInstance().getStatusJson();
                    response.put("success", true);
                    break;
                    
                case "ADD_SAFE_LOCATION": {
                    JSONObject zoneData = request.optJSONObject("zone");
                    if (zoneData != null) {
                        com.overdrive.app.surveillance.SafeLocation zone =
                            com.overdrive.app.surveillance.SafeLocationManager.getInstance().addZone(
                                zoneData.optString("name", "Unnamed"),
                                zoneData.optDouble("lat", 0),
                                zoneData.optDouble("lng", 0),
                                zoneData.optInt("radiusM", 150));
                        response.put("success", zone != null);
                        if (zone != null) response.put("zone", zone.toJson());
                        else response.put("error", Messages.get("errors.zones_max_reached", 10));
                    } else {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.zones_missing_data"));
                    }
                    break;
                }
                    
                case "UPDATE_SAFE_LOCATION": {
                    String zoneId = request.optString("id", null);
                    JSONObject updates = request.optJSONObject("updates");
                    if (zoneId != null && updates != null) {
                        boolean updated = com.overdrive.app.surveillance.SafeLocationManager.getInstance()
                            .updateZone(zoneId, updates);
                        response.put("success", updated);
                    } else {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.missing_id_or_updates"));
                    }
                    break;
                }
                    
                case "DELETE_SAFE_LOCATION": {
                    String zoneId = request.optString("id", null);
                    if (zoneId != null) {
                        boolean removed = com.overdrive.app.surveillance.SafeLocationManager.getInstance()
                            .removeZone(zoneId);
                        response.put("success", removed);
                    } else {
                        response.put("success", false);
                        response.put("error", Messages.get("errors.missing_id"));
                    }
                    break;
                }
                    
                case "TOGGLE_SAFE_LOCATIONS": {
                    boolean enabled = request.optBoolean("enabled", true);
                    com.overdrive.app.surveillance.SafeLocationManager.getInstance().setFeatureEnabled(enabled);
                    response.put("success", true);
                    response.put("enabled", enabled);
                    break;
                }
                
                // ==================== GPS SIDECAR COMMANDS ====================
                // GPS data from LocationSidecarService (app writes via IPC, daemon writes to file)
                
                case "UPDATE_GPS":
                    handleGpsUpdate(request);
                    // Fire-and-forget: LocationSidecarService writes the fix and
                    // never reads the ack. Return null so handleClient skips the
                    // reply write (saves a serialize + write syscall ~1/s).
                    return null;

                // ==================== ROADSENSE ====================
                // Batched IMU frames from the app-side RoadSense sidecar (D-023).
                // Feed straight into the daemon-side RoadSenseController pipeline.
                case com.overdrive.app.roadsense.detect.ImuFrameCodec.COMMAND: { // "IMU_BATCH"
                    com.overdrive.app.roadsense.RoadSenseController rs =
                            com.overdrive.app.daemon.CameraDaemon.getRoadSense();
                    if (rs != null) {
                        // Pass the ALREADY-PARSED request straight to the codec —
                        // no request.toString() + re-parse round-trip of the
                        // nested-array batch (~10/s on the IPC reader thread).
                        rs.onImuBatch(request);
                    }
                    // Fire-and-forget: RoadSenseImuSidecarService never reads the
                    // ack. Return null so handleClient skips the reply write
                    // (saves a JSONObject build + serialize + write ~10/s).
                    return null;
                }

                // ==================== ABRP COMMANDS ====================
                case "SET_ABRP_CONFIG":
                    handleSetAbrpConfig(request, response);
                    break;

                case "GET_ABRP_CONFIG":
                    handleGetAbrpConfig(response);
                    break;

                case "GET_ABRP_STATUS":
                    handleGetAbrpStatus(response);
                    break;

                case "DELETE_ABRP_TOKEN":
                    handleDeleteAbrpToken(response);
                    break;

                // ==================== MQTT COMMANDS ====================
                case "GET_MQTT_CONNECTIONS":
                    handleGetMqttConnections(response);
                    break;

                case "ADD_MQTT_CONNECTION":
                    handleAddMqttConnection(request, response);
                    break;

                case "UPDATE_MQTT_CONNECTION":
                    handleUpdateMqttConnection(request, response);
                    break;

                case "DELETE_MQTT_CONNECTION":
                    handleDeleteMqttConnection(request, response);
                    break;

                case "GET_MQTT_STATUS":
                    handleGetMqttStatus(response);
                    break;

                case "GET_MQTT_TELEMETRY":
                    handleGetMqttTelemetry(response);
                    break;

                // ==================== TELEMETRY OVERLAY COMMANDS ====================

                case "SET_TELEMETRY_OVERLAY": {
                    boolean enabled = request.optBoolean("enabled", false);
                    JSONObject overlayConfig = new JSONObject();
                    overlayConfig.put("enabled", enabled);
                    com.overdrive.app.config.UnifiedConfigManager.setTelemetryOverlay(overlayConfig);
                    // Notify pipeline
                    com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
                    if (pipeline != null) {
                        pipeline.setOverlayEnabled(enabled);
                    }
                    response.put("success", true);
                    response.put("enabled", enabled);
                    break;
                }

                case "GET_TELEMETRY_OVERLAY": {
                    JSONObject overlayConfig = com.overdrive.app.config.UnifiedConfigManager.getTelemetryOverlay();
                    response.put("success", true);
                    response.put("enabled", overlayConfig.optBoolean("enabled", false));
                    break;
                }

                // ==================== UPDATE COMMANDS ====================
                // Telegram daemon delegates here because AppUpdater needs the
                // app Context (PackageManager, SharedPreferences) which only
                // exists in CameraDaemon's process.

                case "CHECK_UPDATE":
                    handleCheckUpdate(response);
                    break;

                case "LIST_VERSIONS":
                    handleListVersions(response);
                    break;

                case "GET_CHANNEL":
                    handleGetChannel(response);
                    break;

                case "SET_CHANNEL":
                    handleSetChannel(request, response);
                    break;

                case "INSTALL_UPDATE":
                    handleInstallUpdate(request, response);
                    break;

                case "GET_UPDATE_PROGRESS":
                    handleGetUpdateProgress(response);
                    break;

                case "UPLOAD_LOG":
                    handleUploadLog(request, response);
                    break;

                default:
                    logger.warn("Unknown IPC command: " + command);
                    response.put("success", false);
                    response.put("error", Messages.get("errors.unknown_command", command));
            }
        } catch (Exception e) {
            logger.error("Error handling IPC command", e);
            try {
                response.put("success", false);
                response.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        
        return response;
    }
    
    /**
     * Apply configuration changes to surveillance system.
     * Updates both the running engine (if available) AND persists to config file.
     * Config is ALWAYS persisted even if surveillance is not running.
     *
     * synchronized: the IPC server runs an 8-thread pool; without serialization,
     * two concurrent SET_CONFIG requests (web UI + Telegram daemon, say) could
     * each load + mutate + save their own snapshot, losing the other's update.
     * Lock granularity is class-wide (CONFIG_LOCK) because the persisted file
     * is shared state, not instance state.
     */
    private static final Object CONFIG_LOCK = new Object();

    private void applyConfig(JSONObject config) {
        synchronized (CONFIG_LOCK) { applyConfigLocked(config); }
    }

    private void applyConfigLocked(JSONObject config) {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            
            // Sentry may be null if surveillance is not running - that's OK
            com.overdrive.app.surveillance.SurveillanceEngineGpu sentry = null;
            if (pipeline != null) {
                sentry = pipeline.getSentry();
            }
            
            // Get or create SurveillanceConfig for persistence
            // Even if sentry is null, we still want to persist the config
            com.overdrive.app.surveillance.SurveillanceConfig sentryConfig = null;
            if (sentry != null) {
                sentryConfig = sentry.getConfig();
            }
            if (sentryConfig == null) {
                // Load from file or create new
                try {
                    com.overdrive.app.surveillance.SurveillanceConfigManager configManager =
                        new com.overdrive.app.surveillance.SurveillanceConfigManager();
                    if (configManager.configExists()) {
                        sentryConfig = configManager.loadConfig();
                        logger.info("Loaded existing config from file for update");
                    } else {
                        sentryConfig = new com.overdrive.app.surveillance.SurveillanceConfig();
                        logger.info("Created new config for persistence");
                    }
                } catch (Exception e) {
                    sentryConfig = new com.overdrive.app.surveillance.SurveillanceConfig();
                    logger.error("Failed to load config, using defaults", e);
                }
            }
            
            boolean configChanged = false;
            
            // Handle surveillance storage type change (INTERNAL, SD_CARD, USB)
            if (config.has("surveillanceStorageType")) {
                String typeStr = config.getString("surveillanceStorageType").toUpperCase();
                com.overdrive.app.storage.StorageManager storageManager =
                    com.overdrive.app.storage.StorageManager.getInstance();
                com.overdrive.app.storage.StorageManager.StorageType type;
                switch (typeStr) {
                    case "SD_CARD": type = com.overdrive.app.storage.StorageManager.StorageType.SD_CARD; break;
                    case "USB":     type = com.overdrive.app.storage.StorageManager.StorageType.USB;     break;
                    default:        type = com.overdrive.app.storage.StorageManager.StorageType.INTERNAL;
                }
                boolean success = storageManager.setSurveillanceStorageType(type);
                if (success) {
                    logger.info("Surveillance storage type set to " + type + " via IPC");
                    if (sentry != null) {
                        sentry.setEventOutputDir(storageManager.getSurveillanceDir());
                        logger.info("Updated sentry output dir: " + storageManager.getSurveillanceDir().getAbsolutePath());
                    }
                } else {
                    logger.warn("Failed to set surveillance storage to " + type + " - not available");
                }
            }
            
            // Handle surveillance storage limit change
            if (config.has("surveillanceLimitMb")) {
                long limitMb = config.getLong("surveillanceLimitMb");
                com.overdrive.app.storage.StorageManager storageManager =
                    com.overdrive.app.storage.StorageManager.getInstance();
                storageManager.setSurveillanceLimitMb(limitMb);
                logger.info("Surveillance limit set to " + storageManager.getSurveillanceLimitMb() + " MB via IPC");
                // Trigger async cleanup
                new Thread(() -> storageManager.ensureSurveillanceSpace(0), "SurvLimitCleanup").start();
            }
            
            // Check if enabled state changed
            if (config.has("enabled")) {
                boolean enabled = config.getBoolean("enabled");
                // Persist to unified config so ACC OFF respects user preference
                com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(enabled);
                if (enabled) {
                    // RACE CONDITION FIX: Only enable surveillance if ACC is actually OFF.
                    // AccSentryDaemon's retry loop may send this IPC after ACC turned ON.
                    if (!com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                        CameraDaemon.enableSurveillance();   // fires OEM recalc
                        logger.info("Surveillance enabled via IPC");
                    } else {
                        logger.info("Surveillance preference saved via IPC — but ACC is ON, not activating");
                        try { com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                        catch (Throwable ignored) {}
                    }
                } else {
                    CameraDaemon.disableSurveillance();   // fires OEM recalc
                    logger.info("Surveillance disabled via IPC");
                }
            }
            
            // Stop surveillance without persisting preference (battery protection, session stop)
            if (config.has("stopSurveillance") && config.getBoolean("stopSurveillance")) {
                CameraDaemon.disableSurveillance();
                logger.info("Surveillance stopped via IPC (preference preserved)");
            }
            
            // Handle ACC state if provided
            if (config.has("accOff")) {
                boolean accOff = config.getBoolean("accOff");
                CameraDaemon.onAccStateChanged(accOff);
                logger.info("ACC state changed via IPC: " + (accOff ? "OFF" : "ON"));
            }
            
            // Handle gear state if provided
            if (config.has("gear")) {
                int gear = config.getInt("gear");
                CameraDaemon.onGearChanged(gear);
                logger.info("Gear changed via IPC: " + com.overdrive.app.recording.RecordingModeManager.gearToString(gear));
            }
            
            // Handle sensitivity setting (maps to minObjectSize)
            if (config.has("sensitivity")) {
                String sensitivity = config.optString("sensitivity", "MEDIUM").toUpperCase();
                float minSize;
                switch (sensitivity) {
                    case "LOW":
                        minSize = 0.02f;  // 2% - detect distant objects
                        break;
                    case "HIGH":
                        minSize = 0.15f;  // 15% - only close objects
                        break;
                    case "MEDIUM":
                    default:
                        minSize = 0.08f;  // 8% - balanced
                        break;
                }
                float confidence = (float) config.optDouble("aiConfidence", sentryConfig.getAiConfidence());
                boolean detectPerson = config.optBoolean("detectPerson", sentryConfig.isDetectPerson());
                boolean detectCar = config.optBoolean("detectCar", sentryConfig.isDetectCar());
                boolean detectBike = config.optBoolean("detectBike", sentryConfig.isDetectBike());
                boolean detectAnimal = config.optBoolean("detectAnimal", sentryConfig.isDetectAnimal());

                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setObjectFilters(minSize, confidence, detectPerson, detectCar, detectBike, detectAnimal);
                }

                // Update config object for persistence
                sentryConfig.setMinObjectSize(minSize);
                sentryConfig.setAiConfidence(confidence);
                sentryConfig.setDetectPerson(detectPerson);
                sentryConfig.setDetectCar(detectCar);
                sentryConfig.setDetectBike(detectBike);
                sentryConfig.setDetectAnimal(detectAnimal);
                configChanged = true;

                logger.info("Sensitivity set to " + sensitivity + " (minObjectSize=" + minSize + ")");
            }
            
            // Apply object detection filters (direct minObjectSize override)
            if (config.has("minObjectSize") || config.has("aiConfidence") ||
                config.has("detectPerson") || config.has("detectCar") || config.has("detectBike") ||
                config.has("detectAnimal")) {

                float minSize = (float) config.optDouble("minObjectSize", sentryConfig.getMinObjectSize());
                float confidence = (float) config.optDouble("aiConfidence", sentryConfig.getAiConfidence());
                boolean detectPerson = config.optBoolean("detectPerson", sentryConfig.isDetectPerson());
                boolean detectCar = config.optBoolean("detectCar", sentryConfig.isDetectCar());
                boolean detectBike = config.optBoolean("detectBike", sentryConfig.isDetectBike());
                boolean detectAnimal = config.optBoolean("detectAnimal", sentryConfig.isDetectAnimal());

                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setObjectFilters(minSize, confidence, detectPerson, detectCar, detectBike, detectAnimal);
                }

                // Update config object for persistence
                sentryConfig.setMinObjectSize(minSize);
                sentryConfig.setAiConfidence(confidence);
                sentryConfig.setDetectPerson(detectPerson);
                sentryConfig.setDetectCar(detectCar);
                sentryConfig.setDetectBike(detectBike);
                sentryConfig.setDetectAnimal(detectAnimal);
                configChanged = true;

                logger.info("Object filters applied (sentry " + (sentry != null ? "running" : "not running") + ")");
            }
            
            // Handle pre/post record seconds
            if (config.has("preRecordSeconds") || config.has("preEventBufferSeconds")) {
                int preRecordSeconds = config.has("preRecordSeconds") 
                    ? config.optInt("preRecordSeconds", 5)
                    : config.optInt("preEventBufferSeconds", 5);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setPreRecordSeconds(preRecordSeconds);
                }
                
                sentryConfig.setPreRecordSeconds(preRecordSeconds);
                configChanged = true;
                logger.info("Pre-record seconds set to: " + preRecordSeconds);
            }
            
            if (config.has("postRecordSeconds") || config.has("postEventBufferSeconds")) {
                int postRecordSeconds = config.has("postRecordSeconds")
                    ? config.optInt("postRecordSeconds", 10)
                    : config.optInt("postEventBufferSeconds", 10);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setPostRecordSeconds(postRecordSeconds);
                }
                
                sentryConfig.setPostRecordSeconds(postRecordSeconds);
                configChanged = true;
                logger.info("Post-record seconds set to: " + postRecordSeconds);
            }
            
            // Handle recording quality / legacy bitrate setting.
            // Prefer the canonical `recordingQuality` (ECONOMY..MAX); accept the
            // legacy `bitrate` (LOW/MEDIUM/HIGH) only as a fallback.
            String tierIn = null;
            if (config.has("recordingQuality")) {
                tierIn = config.optString("recordingQuality", "").toUpperCase();
            } else if (config.has("bitrate")) {
                String legacy = config.optString("bitrate", "").toUpperCase();
                switch (legacy) {
                    case "LOW":    tierIn = "ECONOMY"; break;
                    case "MEDIUM": tierIn = "STANDARD"; break;
                    case "HIGH":   tierIn = "HIGH"; break;
                    default:       tierIn = null; break;
                }
            }
            if (tierIn != null && !tierIn.isEmpty()) {
                CameraDaemon.setRecordingQuality(tierIn);
                HttpServer.setRecordingBitrateStatic(tierIn);  // legacy alias setter; takes any string
                logger.info("Recording quality set to: " + tierIn);
            }
            
            // Handle codec setting
            if (config.has("codec")) {
                String codec = config.optString("codec", "H264").toUpperCase();
                if (codec.equals("H264") || codec.equals("H265")) {
                    CameraDaemon.setRecordingCodec(codec);
                    // Also update HttpServer's static setting for web UI sync
                    HttpServer.setRecordingCodecStatic(codec);
                    logger.info("Recording codec set to: " + codec);
                }
            }
            
            // Persist recording settings to file so web UI can read them
            if (config.has("recordingQuality") || config.has("bitrate") || config.has("codec")) {
                HttpServer.persistSettingsStatic();
            }
            
            // SOTA: Handle flash immunity setting (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
            if (config.has("flashImmunity")) {
                int flashImmunity = config.optInt("flashImmunity", 2);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setFlashImmunity(flashImmunity);
                }
                
                sentryConfig.setFlashImmunity(flashImmunity);
                configChanged = true;
                logger.info("Flash immunity set to: " + flashImmunity);
            }
            
            // SOTA: Handle distance preset (1-5 slider value)
            // Distance ONLY controls minObjectSize (AI detection range)
            // Block size is LOCKED at 32. Motion sensitivity is handled separately.
            if (config.has("distance")) {
                int distance = config.optInt("distance", 3);
                
                // Map slider value to minObjectSize for AI detection
                // 1 = Close (~3m, 25%), 2 = Near (~5m, 18%), 3 = Medium (~8m, 12%), 
                // 4 = Far (~10m, 8%), 5 = Very Far (~15m, 5%)
                float minObjectSize;
                String distanceLabel;
                switch (distance) {
                    case 1: minObjectSize = 0.25f; distanceLabel = "CLOSE (~3m)"; break;
                    case 2: minObjectSize = 0.18f; distanceLabel = "NEAR (~5m)"; break;
                    case 3: minObjectSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                    case 4: minObjectSize = 0.08f; distanceLabel = "FAR (~10m)"; break;
                    case 5: minObjectSize = 0.05f; distanceLabel = "VERY_FAR (~15m)"; break;
                    default: minObjectSize = 0.12f; distanceLabel = "MEDIUM (~8m)"; break;
                }
                
                // Only update minObjectSize - don't touch motion sensitivity settings
                sentryConfig.setMinObjectSize(minObjectSize);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    float confidence = sentryConfig.getAiConfidence();
                    boolean detectPerson = sentryConfig.isDetectPerson();
                    boolean detectCar = sentryConfig.isDetectCar();
                    boolean detectBike = sentryConfig.isDetectBike();
                    boolean detectAnimal = sentryConfig.isDetectAnimal();
                    sentry.setObjectFilters(minObjectSize, confidence, detectPerson, detectCar, detectBike, detectAnimal);
                }
                
                logger.info(String.format("Distance set via IPC: %s (minObjectSize=%.0f%%)",
                    distanceLabel, minObjectSize * 100));
            }
            
            // SOTA: Handle motion sensitivity slider (1-5) - SEPARATE from distance
            // Controls requiredBlocks and densityThreshold for motion detection
            // Block size is LOCKED at 32
            if (config.has("sensitivity") && config.optInt("sensitivity", -1) >= 1 && config.optInt("sensitivity", -1) <= 5) {
                int sensitivityLevel = config.optInt("sensitivity", 3);
                
                // Map slider value to motion detection thresholds
                // Production table with block size locked at 32:
                // 1=Strict (req=4, density=48), 2=Conservative (req=3, density=40), 
                // 3=Default (req=2, density=32), 4=Sensitive (req=2, density=16), 5=Aggressive (req=1, density=12)
                int requiredBlocks;
                
                switch (sensitivityLevel) {
                    case 1:  // Strict - large objects only
                        requiredBlocks = 4;
                        break;
                    case 2:  // Conservative - solid objects
                        requiredBlocks = 3;
                        break;
                    case 3:  // Default - balanced
                        requiredBlocks = 2;
                        break;
                    case 4:  // Sensitive - catches motion quickly
                        requiredBlocks = 2;
                        break;
                    case 5:  // Aggressive - any motion
                        requiredBlocks = 1;
                        break;
                    default:
                        requiredBlocks = 2;
                        break;
                }
                
                // Convert slider 1-5 to percentage 20-100 for unified sensitivity
                int sensitivityPercent = sensitivityLevel * 20;
                
                // Update config for persistence
                sentryConfig.setRequiredBlocks(requiredBlocks);
                sentryConfig.setUnifiedSensitivity(sensitivityPercent);
                configChanged = true;
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setUnifiedSensitivity(sensitivityPercent);
                    sentry.setRequiredActiveBlocks(requiredBlocks);
                }
                
                logger.info(String.format("Motion sensitivity set to level %d (%d%%, alarm=%d blocks)",
                    sensitivityLevel, sensitivityPercent, requiredBlocks));
            }
            
            // Handle block sensitivity (grid motion detection) - skip if distance was set
            if (config.has("blockSensitivity") && !config.has("distance")) {
                float blockSens = (float) config.optDouble("blockSensitivity", 0.04);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setBlockSensitivity(blockSens);
                }
                
                sentryConfig.setSensitivity(blockSens);
                configChanged = true;
                logger.info("Block sensitivity set to: " + blockSens);
            }
            
            // Handle required active blocks - skip if distance was set
            if (config.has("requiredActiveBlocks") && !config.has("distance")) {
                int reqBlocks = config.optInt("requiredActiveBlocks", 2);
                
                // Apply to running engine if available
                if (sentry != null) {
                    sentry.setRequiredActiveBlocks(reqBlocks);
                }
                
                sentryConfig.setRequiredBlocks(reqBlocks);
                configChanged = true;
                logger.info("Required active blocks set to: " + reqBlocks);
            }
            
            // Apply updated config to engine (if running) and ALWAYS persist to file
            if (configChanged) {
                // Apply config to running engine (syncs internal state like preRecordMs)
                if (sentry != null) {
                    sentry.setConfig(sentryConfig);
                }
                
                // ALWAYS persist to file - this is critical for config to survive restarts
                try {
                    com.overdrive.app.surveillance.SurveillanceConfigManager configManager =
                        new com.overdrive.app.surveillance.SurveillanceConfigManager();
                    configManager.saveConfig(sentryConfig);
                    logger.info("Surveillance config persisted to file (sentry " + 
                        (sentry != null ? "running" : "not running") + ")");
                } catch (Exception e) {
                    logger.error("Failed to persist surveillance config", e);
                }
            }
            
            // Note: minObjectHeight filter is now applied in C++ (yolo_detector.cpp)
            // Height filter (10% of frame) is applied BEFORE NMS in native code for efficiency
            
            // ========================================================================
            // V2 Pipeline Settings
            // ========================================================================
            
            // Environment preset (outdoor/garage/street) — sets slider defaults
            if (config.has("environmentPreset")) {
                String preset = config.optString("environmentPreset", "outdoor").toLowerCase();
                sentryConfig.setEnvironmentPreset(preset);
                if (sentry != null) {
                    sentry.applyV2EnvironmentPreset(preset);
                }
                configChanged = true;
                logger.info("V2 environment preset: " + preset);
            }
            
            // Sensitivity level (1-5)
            if (config.has("sensitivityLevel")) {
                int level = config.optInt("sensitivityLevel", 3);
                sentryConfig.setSensitivityLevel(level);
                if (sentry != null) {
                    sentry.applyV2Sensitivity(level);
                }
                configChanged = true;
                logger.info("V2 sensitivity level: " + level);
            }
            
            // Detection zone (close/normal/extended)
            if (config.has("detectionZone")) {
                String zone = config.optString("detectionZone", "normal").toLowerCase();
                sentryConfig.setDetectionZone(zone);
                configChanged = true;
                logger.info("V2 detection zone: " + zone);
            }
            
            // Loitering time (seconds)
            if (config.has("loiteringTime")) {
                int seconds = config.optInt("loiteringTime", 3);
                sentryConfig.setLoiteringTimeSeconds(seconds);
                if (sentry != null) {
                    sentry.setV2LoiteringTime(seconds);
                }
                configChanged = true;
                logger.info("V2 loitering time: " + seconds + "s");
            }
            
            // Shadow filter mode (0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE)
            if (config.has("shadowFilter")) {
                int mode = config.optInt("shadowFilter", 2);
                sentryConfig.setShadowFilterMode(mode);
                if (sentry != null) {
                    sentry.setV2ShadowFilterMode(mode);
                }
                configChanged = true;
                String[] modeNames = {"OFF", "LIGHT", "NORMAL", "AGGRESSIVE"};
                logger.info("V2 shadow filter: " + (mode >= 0 && mode <= 3 ? modeNames[mode] : "invalid"));
            }
            
            // Per-camera enable/disable
            // Quadrant mapping: Q0=front, Q1=right, Q2=rear, Q3=left
            if (config.has("cameraFront")) {
                boolean enabled = config.optBoolean("cameraFront", true);
                sentryConfig.setCameraEnabled(0, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(0, enabled);
                configChanged = true;
            }
            if (config.has("cameraRight")) {
                boolean enabled = config.optBoolean("cameraRight", true);
                sentryConfig.setCameraEnabled(1, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(1, enabled);
                configChanged = true;
            }
            if (config.has("cameraRear")) {
                boolean enabled = config.optBoolean("cameraRear", true);
                sentryConfig.setCameraEnabled(2, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(2, enabled);
                configChanged = true;
            }
            if (config.has("cameraLeft")) {
                boolean enabled = config.optBoolean("cameraLeft", true);
                sentryConfig.setCameraEnabled(3, enabled);
                if (sentry != null) sentry.setV2QuadrantEnabled(3, enabled);
                configChanged = true;
            }
            
            // Developer toggles
            if (config.has("motionHeatmap")) {
                sentryConfig.setMotionHeatmapEnabled(config.optBoolean("motionHeatmap", false));
                configChanged = true;
            }
            if (config.has("filterDebugLog")) {
                boolean debugEnabled = config.optBoolean("filterDebugLog", false);
                sentryConfig.setFilterDebugLogEnabled(debugEnabled);
                if (sentry != null) {
                    sentry.setFilterDebugEnabled(debugEnabled);
                }
                configChanged = true;
            }
            if (config.has("telegramSendStartPing")) {
                sentryConfig.setTelegramSendStartPing(
                        config.optBoolean("telegramSendStartPing", false));
                configChanged = true;
            }
            // Per-tier filter is persisted on the telegram section now — see
            // UnifiedTelegramConfig.K_TIER_*. Don't flip configChanged: the
            // surveillance config is unaffected, and writing through
            // setBoolean already triggers an updateSection write that the
            // gate's forceReload() will pick up on the next event.
            if (config.has("telegramNotices")) {
                com.overdrive.app.telegram.config.UnifiedTelegramConfig.setBoolean(
                        com.overdrive.app.telegram.config.UnifiedTelegramConfig.K_TIER_NOTICES,
                        config.optBoolean("telegramNotices", false));
            }
            if (config.has("telegramAlerts")) {
                com.overdrive.app.telegram.config.UnifiedTelegramConfig.setBoolean(
                        com.overdrive.app.telegram.config.UnifiedTelegramConfig.K_TIER_ALERTS,
                        config.optBoolean("telegramAlerts", true));
            }
            if (config.has("telegramCritical")) {
                com.overdrive.app.telegram.config.UnifiedTelegramConfig.setBoolean(
                        com.overdrive.app.telegram.config.UnifiedTelegramConfig.K_TIER_CRITICAL,
                        config.optBoolean("telegramCritical", true));
            }

        } catch (Exception e) {
            logger.error("Failed to apply config", e);
        }
    }
    
    /**
     * Apply ROI configuration to surveillance system.
     */
    private void applyRoi(JSONObject roiData) {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
                CameraDaemon.getGpuPipeline();
            
            if (pipeline == null || pipeline.getSentry() == null) {
                logger.warn("Cannot apply ROI - surveillance not initialized");
                return;
            }
            
            // Parse polygon points
            org.json.JSONArray pointsArray = roiData.optJSONArray("points");
            if (pointsArray == null || pointsArray.length() < 3) {
                // Clear ROI
                pipeline.getSentry().setRoiMask(null);
                logger.info("ROI cleared");
                return;
            }
            
            // Convert to float array
            float[][] points = new float[pointsArray.length()][2];
            for (int i = 0; i < pointsArray.length(); i++) {
                org.json.JSONArray point = pointsArray.getJSONArray(i);
                points[i][0] = (float) point.getDouble(0);
                points[i][1] = (float) point.getDouble(1);
            }
            
            // Apply to surveillance engine
            pipeline.getSentry().setRoiFromPolygon(points);
            logger.info("ROI applied with " + points.length + " vertices");
            
        } catch (Exception e) {
            logger.error("Failed to apply ROI", e);
        }
    }
    
    /**
     * Get current ROI data.
     */
    private JSONObject getRoiData() {
        JSONObject roi = new JSONObject();
        try {
            // For now, return empty - would need to store ROI points
            roi.put("enabled", false);
            roi.put("points", new org.json.JSONArray());
        } catch (Exception e) {
            logger.error("Failed to get ROI data", e);
        }
        return roi;
    }
    
    private JSONObject getDefaultConfig() throws Exception {
        JSONObject config = new JSONObject();
        
        // Read persisted preference (not runtime state) for the UI toggle
        boolean enabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
        
        config.put("enabled", enabled);
        config.put("noiseThreshold", 0.0001);
        config.put("lightThreshold", 0.4);
        config.put("aiEnabled", true);
        config.put("scheduleEnabled", false);
        config.put("recordingQuality", CameraDaemon.getRecordingQuality());
        config.put("codec", CameraDaemon.getRecordingCodec());
        
        // Get actual values from sentry config if available
        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline =
            CameraDaemon.getGpuPipeline();
        
        com.overdrive.app.surveillance.SurveillanceConfig sentryConfig = null;
        
        if (pipeline != null && pipeline.getSentry() != null) {
            com.overdrive.app.surveillance.SurveillanceEngineGpu sentry = pipeline.getSentry();
            sentryConfig = sentry.getConfig();
        }
        
        // If sentry not running, try to load from file
        if (sentryConfig == null) {
            try {
                com.overdrive.app.surveillance.SurveillanceConfigManager configManager =
                    new com.overdrive.app.surveillance.SurveillanceConfigManager();
                if (configManager.configExists()) {
                    sentryConfig = configManager.loadConfig();
                    logger.info("Loaded config from file for GET_CONFIG (sentry not running)");
                }
            } catch (Exception e) {
                logger.error("Failed to load config from file", e);
            }
        }
        
        if (sentryConfig != null) {
            // Read from config object
            config.put("minObjectSize", sentryConfig.getMinObjectSize());
            config.put("aiConfidence", sentryConfig.getAiConfidence());
            config.put("detectPerson", sentryConfig.isDetectPerson());
            config.put("detectCar", sentryConfig.isDetectCar());
            config.put("detectBike", sentryConfig.isDetectBike());
            config.put("detectAnimal", sentryConfig.isDetectAnimal());
            config.put("flashImmunity", sentryConfig.getFlashImmunity());
            config.put("preEventBufferSeconds", sentryConfig.getPreRecordSeconds());
            config.put("postEventBufferSeconds", sentryConfig.getPostRecordSeconds());
            config.put("blockSensitivity", sentryConfig.getSensitivity());
            config.put("requiredActiveBlocks", sentryConfig.getRequiredBlocks());
            
            // SOTA: Return sensitivity as slider value (1-5) based on requiredBlocks
            // Maps: 1=Strict(req=4), 2=Conservative(req=3), 3=Default(req=2), 4=Sensitive(req=2,density=16), 5=Aggressive(req=1)
            int reqBlocks = sentryConfig.getRequiredBlocks();
            int sensitivityLevel;
            if (reqBlocks >= 4) {
                sensitivityLevel = 1;  // Strict
            } else if (reqBlocks == 3) {
                sensitivityLevel = 2;  // Conservative
            } else if (reqBlocks == 2) {
                sensitivityLevel = 3;  // Default (could be 3 or 4, default to 3)
            } else {
                sensitivityLevel = 5;  // Aggressive
            }
            config.put("sensitivity", sensitivityLevel);
            
            // SOTA: Return distance as slider value (1-5) based on minObjectSize
            float minSize = sentryConfig.getMinObjectSize();
            int distanceLevel;
            if (minSize >= 0.22f) {
                distanceLevel = 1;  // ~3m (near)
            } else if (minSize >= 0.15f) {
                distanceLevel = 2;  // ~5m
            } else if (minSize >= 0.10f) {
                distanceLevel = 3;  // ~8m
            } else if (minSize >= 0.06f) {
                distanceLevel = 4;  // ~10m
            } else {
                distanceLevel = 5;  // ~15m (far)
            }
            config.put("distance", distanceLevel);
        } else {
            // Defaults when no config available
            config.put("sensitivity", 3);  // Default (slider value 1-5)
            config.put("distance", 3);     // ~8m (slider value 1-5)
            config.put("minObjectSize", 0.08);
            config.put("aiConfidence", 0.6);
            config.put("detectPerson", true);
            config.put("detectCar", true);
            config.put("detectBike", true);
            config.put("detectAnimal", false);
            config.put("flashImmunity", 2);  // Default MEDIUM
            config.put("preEventBufferSeconds", 5);
            config.put("postEventBufferSeconds", 10);
            config.put("blockSensitivity", 0.04);
            config.put("requiredActiveBlocks", 2);
        }
        
        // SOTA: Add lastModified timestamp for web UI sync detection
        config.put("lastModified", com.overdrive.app.config.UnifiedConfigManager.getLastModified());
        
        return config;
    }
    
    /** Device model for the backup-bundle manifest (same-device advisory). */
    private static String deviceModelString() {
        try {
            String m = android.os.Build.MODEL;
            return (m == null || m.isEmpty()) ? "unknown" : m;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private JSONObject getSurveillanceStatus() throws Exception {
        JSONObject status = new JSONObject();
        
        // Read from persisted config (not in-memory flag)
        boolean enabled = com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled();
        boolean active = CameraDaemon.isSurveillanceActive();
        
        status.put("enabled", enabled);
        status.put("active", active);
        status.put("recording", false);
        
        return status;
    }
    
    // ==================== VEHICLE DATA HELPERS ====================
    
    private JSONObject getVehicleData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        return monitor.getAllData();
    }
    
    private JSONObject getBatteryVoltageData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.BatteryVoltageData data = monitor.getBatteryVoltage();
        
        if (data == null) throw new Exception("Battery voltage data not available");
        
        JSONObject json = new JSONObject();
        json.put("level", data.level);
        json.put("levelName", data.levelName);
        json.put("isWarning", data.isWarning);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getBatteryPowerData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.BatteryPowerData data = monitor.getBatteryPower();
        
        if (data == null) throw new Exception("Battery power data not available");
        
        JSONObject json = new JSONObject();
        json.put("voltageVolts", data.voltageVolts);
        json.put("isWarning", data.isWarning);
        json.put("isCritical", data.isCritical);
        json.put("healthStatus", data.getHealthStatus());
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getBatterySocData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.BatterySocData data = monitor.getBatterySoc();
        
        if (data == null) throw new Exception("Battery SOC data not available");
        
        JSONObject json = new JSONObject();
        json.put("socPercent", data.socPercent);
        json.put("isLow", data.isLow);
        json.put("isCritical", data.isCritical);
        json.put("status", data.getStatus());
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getChargingStateData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.ChargingStateData data = monitor.getChargingState();
        
        if (data == null) throw new Exception("Charging state data not available");
        
        JSONObject json = new JSONObject();
        json.put("stateCode", data.stateCode);
        json.put("stateName", data.stateName);
        json.put("status", data.status.name());
        json.put("isError", data.isError);
        json.put("errorType", data.errorType);
        json.put("chargingPowerKW", data.chargingPowerKW);
        json.put("isDischarging", data.isDischarging);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getChargingPowerData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.ChargingStateData data = monitor.getChargingState();
        
        if (data == null) throw new Exception("Charging power data not available");
        
        JSONObject json = new JSONObject();
        json.put("chargingPowerKW", data.chargingPowerKW);
        json.put("isDischarging", data.isDischarging);
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    private JSONObject getDrivingRangeData() throws Exception {
        com.overdrive.app.monitor.VehicleDataMonitor monitor =
            com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
        com.overdrive.app.monitor.DrivingRangeData data = monitor.getDrivingRange();
        
        if (data == null) throw new Exception("Driving range data not available");
        
        JSONObject json = new JSONObject();
        json.put("elecRangeKm", data.elecRangeKm);
        json.put("fuelRangeKm", data.fuelRangeKm);
        json.put("totalRangeKm", data.totalRangeKm);
        json.put("isLow", data.isLow);
        json.put("isCritical", data.isCritical);
        json.put("status", data.getStatus());
        json.put("isPureEV", data.isPureEV());
        if (data.hasFuelPercent()) {
            json.put("fuelPercent", data.fuelPercent);
        }
        json.put("timestamp", data.timestamp);
        return json;
    }
    
    // ==================== GPS SIDECAR HANDLER ====================
    
    /**
     * Handle GPS update from LocationSidecarService.
     * Directly updates GpsMonitor's cached values - no file needed.
     */
    private void handleGpsUpdate(JSONObject request) {
        try {
            double lat = request.optDouble("lat", 0.0);
            double lng = request.optDouble("lng", 0.0);
            float speed = (float) request.optDouble("speed", 0.0);
            float heading = (float) request.optDouble("heading", 0.0);
            float accuracy = (float) request.optDouble("accuracy", 0.0);
            double altitude = request.optDouble("altitude", 0.0);
            long time = request.optLong("time", System.currentTimeMillis());
            // Monotonic since-boot fix timestamp for geo-tagging staleness — distinct
            // from "time" (send-time). Older sidecars omit it → sentinel 0, which the
            // daemon-side gate treats as "no monotonic basis" and falls back to the
            // send-time age (prior behavior, no regression).
            long fixElapsedMs = request.optLong("fixElapsedMs", 0L);

            // Directly update GpsMonitor
            com.overdrive.app.monitor.GpsMonitor.getInstance()
                .updateFromIpc(lat, lng, speed, heading, accuracy, time, altitude, fixElapsedMs);
            
        } catch (Exception e) {
            logger.error("Failed to update GPS", e);
        }
    }

    // ==================== ABRP COMMAND HANDLERS ====================

    private void handleSetAbrpConfig(JSONObject request, JSONObject response) throws Exception {
        if (abrpConfig == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.abrp_not_initialized"));
            return;
        }

        String token = request.optString("token", null);
        if (token != null && !token.isEmpty()) {
            abrpConfig.setUserToken(token);
        }

        if (request.has("car_model")) {
            abrpConfig.setCarModel(request.optString("car_model", null));
        }

        boolean wasEnabled = abrpConfig.isEnabled();
        if (request.has("enabled")) {
            abrpConfig.setEnabled(request.optBoolean("enabled", false));
        }

        // Data-saving + app-gate options (applied live; the service reads config each cycle)
        if (request.has("change_only")) abrpConfig.setChangeOnly(request.optBoolean("change_only", true));
        if (request.has("min_interval_seconds")) abrpConfig.setMinIntervalSeconds(request.optInt("min_interval_seconds", 5));
        if (request.has("max_interval_seconds")) abrpConfig.setMaxIntervalSeconds(request.optInt("max_interval_seconds", 120));
        if (request.has("gate_on_app")) abrpConfig.setGateOnApp(request.optBoolean("gate_on_app", false));
        if (request.has("app_package")) abrpConfig.setAppPackage(request.optString("app_package", "com.iternio.abrpapp"));
        if (request.has("app_active_mode")) abrpConfig.setAppActiveMode(request.optString("app_active_mode", "foreground"));
        if (request.has("app_grace_seconds")) abrpConfig.setAppGraceSeconds(request.optInt("app_grace_seconds", 90));

        abrpConfig.save();

        // Start or stop service if enabled state changed
        boolean nowEnabled = abrpConfig.isEnabled();
        if (abrpService != null && wasEnabled != nowEnabled) {
            if (nowEnabled && abrpConfig.isConfigured()) {
                abrpService.start();
                logger.info("ABRP service started via IPC");
            } else {
                abrpService.stop();
                logger.info("ABRP service stopped via IPC");
            }
        }

        response.put("success", true);
        response.put("message", "ABRP config updated");
    }

    private void handleGetAbrpConfig(JSONObject response) throws Exception {
        if (abrpConfig == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.abrp_not_initialized"));
            return;
        }

        response.put("success", true);
        response.put("config", abrpConfig.toJson());
    }

    private void handleGetAbrpStatus(JSONObject response) throws Exception {
        if (abrpService == null) {
            // Return basic status when service is not initialized
            JSONObject status = new JSONObject();
            status.put("running", false);
            status.put("totalUploads", 0);
            status.put("failedUploads", 0);
            status.put("lastUploadTime", 0);
            status.put("consecutiveFailures", 0);
            status.put("currentInterval", 0);
            response.put("success", true);
            response.put("status", status);
            return;
        }

        response.put("success", true);
        response.put("status", abrpService.getStatus());
    }

    private void handleDeleteAbrpToken(JSONObject response) throws Exception {
        if (abrpConfig == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.abrp_not_initialized"));
            return;
        }

        abrpConfig.deleteToken();
        abrpConfig.save();

        if (abrpService != null && abrpService.isRunning()) {
            abrpService.stop();
            logger.info("ABRP service stopped after token deletion");
        }

        response.put("success", true);
        response.put("message", "ABRP token deleted");
    }

    // ==================== MQTT HANDLER METHODS ====================

    private void handleGetMqttConnections(JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }
        response.put("success", true);
        response.put("connections", mqttManager.getAllStatus());
        response.put("maxConnections", com.overdrive.app.mqtt.MqttConnectionStore.MAX_CONNECTIONS);
    }

    private void handleAddMqttConnection(JSONObject request, JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }

        com.overdrive.app.mqtt.MqttConnectionConfig added = mqttManager.addConnection(request);
        if (added != null) {
            response.put("success", true);
            response.put("connection", added.toSafeJson());
            response.put("message", "MQTT connection added");
        } else {
            response.put("success", false);
            response.put("error", Messages.get("errors.max_connections_reached", com.overdrive.app.mqtt.MqttConnectionStore.MAX_CONNECTIONS));
        }
    }

    private void handleUpdateMqttConnection(JSONObject request, JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }

        String id = request.optString("id", null);
        if (id == null || id.isEmpty()) {
            response.put("success", false);
            response.put("error", Messages.get("errors.missing_connection_id"));
            return;
        }

        boolean updated = mqttManager.updateConnection(id, request);
        response.put("success", updated);
        if (updated) {
            response.put("message", "MQTT connection updated");
        } else {
            response.put("error", Messages.get("errors.connection_not_found", id));
        }
    }

    private void handleDeleteMqttConnection(JSONObject request, JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }

        String id = request.optString("id", null);
        if (id == null || id.isEmpty()) {
            response.put("success", false);
            response.put("error", Messages.get("errors.missing_connection_id"));
            return;
        }

        boolean deleted = mqttManager.deleteConnection(id);
        response.put("success", deleted);
        if (deleted) {
            response.put("message", "MQTT connection deleted");
        } else {
            response.put("error", Messages.get("errors.connection_not_found", id));
        }
    }

    private void handleGetMqttStatus(JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }
        response.put("success", true);
        response.put("connections", mqttManager.getAllStatus());
    }

    private void handleGetMqttTelemetry(JSONObject response) throws Exception {
        if (mqttManager == null) {
            response.put("success", false);
            response.put("error", Messages.get("errors.mqtt_not_initialized"));
            return;
        }
        response.put("success", true);
        response.put("telemetry", mqttManager.getLatestTelemetry());
    }

    // ==================== UPDATE HANDLERS ====================
    // Mirror UpdateApiHandler.handleCheck/handleInstall but invoked over IPC
    // (port 19877) so other-process daemons can drive an update without going
    // through the loopback-trusted HTTP path.

    private void handleCheckUpdate(JSONObject response) throws Exception {
        android.content.Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            response.put("success", false);
            response.put("error", "App context not ready");
            return;
        }

        // Channel-aware, mirroring UpdateApiHandler.handleCheck. Alpha is a
        // pick-any archive — there's no single "the update", so report
        // not-available + the channel marker; the Telegram handler then lists
        // versions (LIST_VERSIONS) instead of offering a single Install button.
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        String channel = com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel();
        response.put("channel", channel);
        if (com.overdrive.app.updater.AppUpdater.CHANNEL_ALPHA.equals(channel)) {
            response.put("success", true);
            response.put("available", false);
            response.put("currentVersion",
                    com.overdrive.app.updater.AppUpdater.getDisplayVersionFromFile());
            return;
        }

        com.overdrive.app.updater.AppUpdater updater =
                new com.overdrive.app.updater.AppUpdater(ctx);
        final Object lock = new Object();
        final boolean[] done = {false};
        final JSONObject[] resultRef = {null};
        updater.checkForUpdate(new com.overdrive.app.updater.AppUpdater.UpdateCallback() {
            @Override public void onUpdateAvailable(String currentVersion, String newVersion, String releaseNotes) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", true);
                    r.put("currentVersion", currentVersion);
                    r.put("remoteVersion", newVersion);
                    r.put("releaseNotes", releaseNotes != null ? releaseNotes : "");
                } catch (Exception ignored) {}
                resultRef[0] = r;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
            @Override public void onNoUpdate(String currentVersion) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", false);
                    r.put("currentVersion", currentVersion);
                    r.put("remoteVersion", currentVersion);
                } catch (Exception ignored) {}
                resultRef[0] = r;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
            @Override public void onError(String error) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", false);
                    r.put("error", error != null ? error : "unknown");
                    r.put("currentVersion", com.overdrive.app.updater.AppUpdater.getDisplayVersionFromFile());
                } catch (Exception ignored) {}
                resultRef[0] = r;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
        });
        try {
            synchronized (lock) {
                if (!done[0]) lock.wait(12_000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // Release the updater's per-instance executor + tunnel-poll scheduler.
        // Without close() each /api/update/check via IPC stranded one
        // non-daemon thread for the JVM lifetime. Idempotent.
        try { updater.close(); } catch (Exception ignored) {}
        if (resultRef[0] == null) {
            response.put("success", false);
            response.put("error", "Update check timed out");
            return;
        }
        // Merge result fields into response
        java.util.Iterator<String> keys = resultRef[0].keys();
        while (keys.hasNext()) {
            String k = keys.next();
            response.put(k, resultRef[0].opt(k));
        }
        // success reflects whether the check actually got a verdict — a
        // result that came back via onError carries an "error" field, so
        // surface that as success=false instead of leaving callers to
        // guess from a separate key.
        response.put("success", !resultRef[0].has("error"));
    }

    /**
     * Enumerate the alpha archive over IPC (mirrors UpdateApiHandler /versions).
     * Returns {success, channel, currentVersion, versions:[{version,tag,
     * relation,publishedAt}]}. Used by the Telegram /update handler on the
     * alpha channel to present a pick-any list.
     */
    private void handleListVersions(JSONObject response) throws Exception {
        android.content.Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            response.put("success", false);
            response.put("error", "App context not ready");
            return;
        }
        com.overdrive.app.updater.AppUpdater updater =
                new com.overdrive.app.updater.AppUpdater(ctx);
        final Object lock = new Object();
        final boolean[] done = {false};
        final JSONObject[] resultRef = {null};
        updater.listVersions(new com.overdrive.app.updater.AppUpdater.VersionListCallback() {
            @Override public void onResult(java.util.List<com.overdrive.app.updater.AppUpdater.VersionEntry> versions, String currentVersion) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", true);
                    r.put("currentVersion", currentVersion);
                    org.json.JSONArray arr = new org.json.JSONArray();
                    for (com.overdrive.app.updater.AppUpdater.VersionEntry v : versions) arr.put(v.toJson());
                    r.put("versions", arr);
                } catch (Exception ignored) {}
                resultRef[0] = r;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
            @Override public void onError(String error) {
                JSONObject r = new JSONObject();
                try {
                    r.put("error", error != null ? error : "unknown");
                    r.put("currentVersion", com.overdrive.app.updater.AppUpdater.getDisplayVersionFromFile());
                } catch (Exception ignored) {}
                resultRef[0] = r;
                synchronized (lock) { done[0] = true; lock.notify(); }
            }
        });
        try {
            synchronized (lock) {
                if (!done[0]) lock.wait(12_000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try { updater.close(); } catch (Exception ignored) {}
        if (resultRef[0] == null) {
            response.put("success", false);
            response.put("error", "Version list timed out");
            return;
        }
        java.util.Iterator<String> keys = resultRef[0].keys();
        while (keys.hasNext()) {
            String k = keys.next();
            response.put(k, resultRef[0].opt(k));
        }
        response.put("success", !resultRef[0].has("error"));
    }

    /** Return the resolved update channel. */
    private void handleGetChannel(JSONObject response) throws Exception {
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        response.put("success", true);
        response.put("channel", com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel());
    }

    /**
     * Switch the update channel from Telegram. Validates against the
     * {alpha, braveheart} allowlist (the bot is owner-paired, but keep the
     * policy uniform with the HTTP endpoint).
     */
    private void handleSetChannel(JSONObject request, JSONObject response) throws Exception {
        // Public-mode block mirrors UpdateApiHandler.handleSetChannel and the
        // sibling IPC handleInstallUpdate — keeps the channel-write policy
        // uniform across all three surfaces (defense-in-depth; this IPC server
        // is loopback-only, but a client must not flip the owner's channel in
        // public mode any more than the HTTP twin lets a tunnel visitor do so).
        if (CameraDaemon.isPublicMode()) {
            response.put("success", false);
            response.put("error", "Update disabled in public mode");
            return;
        }
        String value = request.optString("channel", "");
        if (!com.overdrive.app.updater.AppUpdater.CHANNEL_ALPHA.equals(value)
                && !com.overdrive.app.updater.AppUpdater.CHANNEL_BRAVEHEART.equals(value)) {
            response.put("success", false);
            response.put("error", "Invalid update channel");
            return;
        }
        boolean ok = com.overdrive.app.config.UnifiedConfigManager.setUpdateChannel(value);
        response.put("success", ok);
        if (ok) {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            response.put("channel", value);
        } else {
            response.put("error", "Could not save the update channel");
        }
    }

    /**
     * Two-stage install:
     *   1. Sync check to populate latestDownloadUrl (so /install can't be used
     *      as a blind download trigger, mirroring UpdateApiHandler).
     *   2. Plant the Telegram post-update hint (so the new process's first
     *      tunnel notification frames as "updated to X" instead of generic
     *      "URL changed"), then kick off downloadAndInstall on a background
     *      thread. The IPC reply returns immediately because the daemon dies
     *      mid-install.
     *
     * Progress reporting reuses /data/local/tmp/overdrive_update_progress.json
     * via UpdateApiHandler-style writes so the same polling path serves both
     * the webapp and Telegram.
     */
    private void handleInstallUpdate(JSONObject request, JSONObject response) throws Exception {
        android.content.Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            response.put("success", false);
            response.put("error", "App context not ready");
            return;
        }

        // Public-mode block mirrors UpdateApiHandler.handleInstall — the bot
        // is already owner-paired but this keeps the policy uniform.
        if (CameraDaemon.isPublicMode()) {
            response.put("success", false);
            response.put("error", "Update disabled in public mode");
            return;
        }

        // Shared single-install gate (covers web + app-IPC + Telegram-IPC, all
        // in this daemon JVM). Acquire BEFORE prepareInstall/network so a
        // concurrent trigger is rejected early. endInstall() on every pre-spawn
        // bail below; the success path leaves it held (process dies during
        // install, INSTALL_STALE_MS self-recovers).
        if (!com.overdrive.app.updater.AppUpdater.tryBeginInstall()) {
            response.put("success", false);
            response.put("error", "Update already in progress");
            return;
        }

        com.overdrive.app.updater.AppUpdater updater =
                new com.overdrive.app.updater.AppUpdater(ctx);

        final String[] versionRef = {null};

        // Targeted (alpha pick): a "version" tag selects a specific archived
        // release. Resolve it SERVER-SIDE (prepareInstall, never a client URL)
        // and SKIP the braveheart available-gate — alpha never returns
        // onUpdateAvailable, so the gate would dead-on-arrival every alpha
        // install. Mirrors UpdateApiHandler.handleInstall.
        String version = request.optString("version", "");
        if (version != null && !version.isEmpty()) {
            // Channel/tag guard (mirrors UpdateApiHandler): an alpha* targeted
            // install is only valid on the alpha channel — reject it on
            // braveheart so the wrong per-channel baseline can't be corrupted.
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            String activeChannel = com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel();
            // Strict tag validation (not a loose prefix) + channel match.
            if (!com.overdrive.app.updater.AppUpdater.isValidAlphaTag(version)
                    || !com.overdrive.app.updater.AppUpdater.CHANNEL_ALPHA.equals(activeChannel)) {
                com.overdrive.app.updater.AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                response.put("success", false);
                response.put("error", "Version is not on the active channel");
                return;
            }
            // Bound the alpha resolve so it can't run past the app's IPC read
            // budget. prepareInstall hits the GitHub API via buildClient(15,15)
            // (connect 15s + read 15s, NO callTimeout), and a slow proxy probe
            // can stack on top — a worst case ~30s, well past MainActivity's
            // hard 25s socket read. Past that the app's DaemonIpcClient.send
            // returns null and the app shows a FALSE "head unit unreachable"
            // error, then is surprised by the restart when the install lands
            // anyway. The braveheart path below already caps its pre-install
            // wait at 20s (lock.wait(20_000)); cap the alpha path the same way
            // so BOTH channels reply within ~20s and the app's 25s margin holds.
            // (The web path has no such socket cutoff, so it just waited — this
            // closes the app-vs-web divergence on slow networks.)
            final Object aLock = new Object();
            final boolean[] aDone = {false};
            final String[] aResolved = {null};
            final String[] aErr = {null};
            new Thread(() -> {
                try {
                    String r = updater.prepareInstall(version);
                    synchronized (aLock) { aResolved[0] = r; aDone[0] = true; aLock.notify(); }
                } catch (Exception e) {
                    synchronized (aLock) {
                        aErr[0] = e.getMessage() != null ? e.getMessage() : "resolve failed";
                        aDone[0] = true; aLock.notify();
                    }
                }
            }, "AlphaPrepareInstall").start();
            try {
                synchronized (aLock) {
                    if (!aDone[0]) aLock.wait(20_000);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            boolean resolved;
            synchronized (aLock) { resolved = aDone[0]; }
            if (!resolved) {
                // Cap exceeded — reply an error within budget rather than let the
                // app's socket read time out and mis-report "daemon down". The
                // orphaned resolve thread may finish later, but we never read
                // versionRef[0] / start the install thread on this path, and
                // close() below tears down the updater's resources, so the late
                // return is inert.
                com.overdrive.app.updater.AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                response.put("success", false);
                response.put("error", "Pre-install check failed: resolve timed out");
                return;
            }
            if (aErr[0] != null) {
                com.overdrive.app.updater.AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                response.put("success", false);
                response.put("error", "Pre-install check failed: " + aErr[0]);
                return;
            }
            versionRef[0] = aResolved[0];
        } else {
            final Object lock = new Object();
            final boolean[] done = {false};
            final boolean[] available = {false};
            final String[] err = {null};

            updater.checkForUpdate(new com.overdrive.app.updater.AppUpdater.UpdateCallback() {
                @Override public void onUpdateAvailable(String c, String n, String rn) {
                    available[0] = true;
                    versionRef[0] = n;
                    synchronized (lock) { done[0] = true; lock.notify(); }
                }
                @Override public void onNoUpdate(String c) {
                    synchronized (lock) { done[0] = true; lock.notify(); }
                }
                @Override public void onError(String e) {
                    err[0] = e;
                    synchronized (lock) { done[0] = true; lock.notify(); }
                }
            });
            try {
                synchronized (lock) {
                    if (!done[0]) lock.wait(20_000);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (err[0] != null) {
                com.overdrive.app.updater.AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                response.put("success", false);
                response.put("error", "Pre-install check failed: " + err[0]);
                return;
            }
            if (!available[0]) {
                com.overdrive.app.updater.AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                response.put("success", false);
                response.put("error", "No update available");
                return;
            }
        }

        // Pre-spawn synchronous region: must release the shared gate on ANY
        // throw before the install thread starts. handleCommand's outer catch
        // only sets response.error — it does NOT call AppUpdater.endInstall().
        // So a JSONException from response.put, or an OOM on Thread spawn, would
        // otherwise wedge the shared gate (web + app + Telegram) for
        // INSTALL_STALE_MS with no install running. Guard it ourselves and
        // rethrow so handleCommand still surfaces the error to the caller. The
        // gate must only be released for throws BEFORE Thread.start() succeeds —
        // once the install thread is running the held gate is correct (its own
        // onError/onSuccess/crash handlers own it).
        try {
            // Plant the Telegram post-update hint so the new process's first
            // notifyTunnel handler frames the message as "Overdrive updated to X".
            // Best-effort: failure here just falls back to the generic "URL
            // changed" copy. We're already in the daemon process (UID 2000), and
            // /data/local/tmp/ is world-writable for shell, so a direct
            // FileWriter is both simpler and avoids the AdbDaemonLauncher path
            // (which would EACCES on the app's adbkey when called from here).
            try {
                // Only write a canonical "<channel>-v<semver>" hint. If the
                // resolved label is non-canonical (e.g. the literal "unknown"
                // sentinel from a version-less APK on a bare tag), fall back to
                // the running build's BuildConfig identity so the Telegram
                // message never reads "Overdrive updated to unknown".
                String hintVersion =
                        com.overdrive.app.updater.AppUpdater.channelOfLabel(versionRef[0]) != null
                                ? versionRef[0]
                                : com.overdrive.app.updater.AppUpdater.getInstalledVersion();
                try (java.io.FileWriter fw = new java.io.FileWriter(
                        com.overdrive.app.updater.UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE)) {
                    fw.write(hintVersion);
                    fw.write('\n');
                }
            } catch (Exception ignored) {}

            // Write the "queued" record SYNCHRONOUSLY, BEFORE replying scheduled —
            // mirrors UpdateApiHandler. If we only wrote it on the bg thread (after
            // the reply), the app's first GET_UPDATE_PROGRESS poll could race in and
            // read a PRIOR install's persisted terminal record (e.g. a stale
            // phase=error or installing@100), then falsely abort/short-circuit a
            // perfectly healthy install. Seeding queued first guarantees the first
            // poll sees THIS install's state.
            writeInstallProgress("queued", 0, "Update queued", null);

            // Start install on a background thread. The IPC reply returns now
            // because the daemon dies mid-install; Telegram polls progress via a
            // separate IPC request (CHECK_UPDATE-style polling isn't needed —
            // /data/local/tmp/overdrive_update_progress.json is already tailed by
            // the webapp; the Telegram bot reads it on demand instead).
            new Thread(() -> {
                try {
                    updater.downloadAndInstall(new com.overdrive.app.updater.AppUpdater.InstallCallback() {
                    @Override public void onProgress(String message) {
                        String m = message == null ? "" : message;
                        String phase = "downloading";
                        if (m.contains("Verifying")) phase = "verifying";
                        else if (m.contains("Stopping daemons")) phase = "stopping_daemons";
                        else if (m.contains("Installing") || m.contains("installed")) phase = "installing";
                        writeInstallProgress(phase, -1, m, null);
                    }
                    @Override public void onDownloadProgress(int percent) {
                        // Coalesce: AppUpdater can fire this every percent;
                        // writeInstallProgress is a full-file JSON rewrite.
                        // Throttle to ≥2% steps or 500ms cadence so disk
                        // write latency doesn't cap download throughput.
                        long now = System.currentTimeMillis();
                        boolean atEdge = percent < 0 || percent >= 99
                                || dlLastPct < 0;
                        boolean stepEnough = (percent - dlLastPct) >= 2;
                        boolean timeEnough = (now - dlLastAt) >= 500;
                        if (!(atEdge || stepEnough || timeEnough)) return;
                        dlLastPct = percent;
                        dlLastAt = now;
                        writeInstallProgress("downloading", percent,
                                percent < 0 ? "Downloading…" : "Downloading " + percent + "%",
                                null);
                    }
                    private int dlLastPct = -1;
                    private long dlLastAt = 0;
                    @Override public void onSuccess() {
                        writeInstallProgress("installing", 100, "Update installed, restarting…", null);
                        // Leave the gate held — pm install kills us; INSTALL_STALE_MS
                        // self-recovers. close() is idempotent/defensive.
                        try { updater.close(); } catch (Exception ignored) {}
                    }
                    @Override public void onError(String error) {
                        writeInstallProgress("error", -1, "Install failed", error);
                        // Install failed before process death — release the gate so
                        // a retry isn't blocked for INSTALL_STALE_MS.
                        com.overdrive.app.updater.AppUpdater.endInstall();
                        // Pre-kill failure (download/verify): this daemon is still
                        // alive, so the detached install script — the only code
                        // that cleans up the Telegram success hint and surfaces
                        // the failure to the owner — is NEVER reached. Do both
                        // here: drop the stale success hint (so an unrelated tunnel
                        // rotation can't later fire a false "updated to X") and tell
                        // the owner now, symmetric with the app/web pollers that
                        // already read phase=error from this still-alive daemon.
                        surfaceIpcInstallFailure(error);
                        try { updater.close(); } catch (Exception ignored) {}
                    }
                    });
                } catch (Exception e) {
                    writeInstallProgress("error", -1, "Install crashed", e.getMessage());
                    com.overdrive.app.updater.AppUpdater.endInstall();
                    // Same pre-kill cleanup + owner notify as the onError branch.
                    surfaceIpcInstallFailure(e.getMessage());
                    try { updater.close(); } catch (Exception ignored) {}
                }
            }, "TelegramUpdate-Install").start();
        } catch (Throwable t) {
            // Threw BEFORE the install thread started (e.g. OOM on spawn) — the
            // thread's own handlers never run, so release the shared gate here.
            com.overdrive.app.updater.AppUpdater.endInstall();
            try { updater.close(); } catch (Exception ignored) {}
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }

        response.put("success", true);
        response.put("status", "scheduled");
        response.put("remoteVersion", versionRef[0] != null ? versionRef[0] : "");
    }

    private static void writeInstallProgress(String phase, int percent, String message, String error) {
        JSONObject r = new JSONObject();
        try {
            r.put("phase", phase);
            r.put("percent", percent);
            r.put("message", message != null ? message : "");
            if (error != null) r.put("error", error);
            r.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {}
        try (java.io.FileWriter fw = new java.io.FileWriter(
                "/data/local/tmp/overdrive_update_progress.json")) {
            fw.write(r.toString());
        } catch (Exception ignored) {}
    }

    /**
     * Pre-kill (download/verify) install-failure cleanup for an IPC-triggered
     * install. handleInstallUpdate plants TELEGRAM_POST_UPDATE_HINT_FILE (the
     * "updated to X" success hint) BEFORE the background download starts — the
     * web path does NOT. A failure that bails out before runDetachedInstall
     * leaves this daemon alive, so the detached script (the only code that
     * deletes the success hint and surfaces the failure) never runs. That left
     * two defects on this path: (1) the stale success hint persisted for up to
     * 24h, so the NEXT unrelated tunnel rotation made the reborn bot send a
     * FALSE "Overdrive updated to X"; (2) the Telegram owner was never told the
     * install failed (the app/web pollers see phase=error, but the bot already
     * replied "scheduled" and waits silently for a hint). Fix both here:
     *   1. Delete the success hint so it can't later fire a false success.
     *   2. Notify the owner directly via TelegramNotifier (IPC to the bot
     *      daemon on 19880) — symmetric with the app/web failure surfaces.
     * Gate on the success hint having existed so a web-triggered install (which
     * never plants it) doesn't message the Telegram owner about an install they
     * didn't start from Telegram, matching the detached script's failure gate.
     */
    private static void surfaceIpcInstallFailure(String error) {
        java.io.File hint = new java.io.File(
                com.overdrive.app.updater.UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE);
        boolean wasIpcTriggered = hint.exists();
        if (wasIpcTriggered) {
            try { hint.delete(); } catch (Exception ignored) {}
        }
        if (!wasIpcTriggered) return;
        String raw = (error != null && !error.trim().isEmpty())
                ? error.trim() : Messages.get("telegram.update.unknown_reason");
        // Localize known backend failures and Markdown-escape English details
        // before handing the copy to the bot's legacy Markdown send path.
        String reason = com.overdrive.app.telegram.TelegramMessages
                .technicalDetail(raw);
        // TelegramNotifier runs the IPC on its own background executor + gates
        // on the criticalAlerts toggle (matching the bot-side failure message
        // category). Copy follows the app locale through TelegramMessages.
        try {
            com.overdrive.app.telegram.TelegramNotifier.sendMessage(
                    com.overdrive.app.telegram.TelegramMessages.get(
                            "update.install_failed", reason),
                    "CRITICAL");
        } catch (Exception ignored) {}
    }

    /**
     * Read the install-progress file and return it to the app process (UID
     * 10xxx can't read /data/local/tmp/ directly). Mirrors
     * UpdateApiHandler.handleProgress: returns an "idle" sentinel when no
     * install has run, and self-recovers a stale non-terminal state (daemon
     * killed mid-download leaves the JSON frozen) older than 5 min back to
     * "idle" so the app's poll loop doesn't spin forever on a dead transfer.
     * response always carries success=true plus {phase, percent, message[, error]}.
     */
    private void handleGetUpdateProgress(JSONObject response) throws Exception {
        response.put("success", true);
        java.io.File f = new java.io.File("/data/local/tmp/overdrive_update_progress.json");
        if (!f.exists()) {
            response.put("phase", "idle");
            response.put("percent", -1);
            response.put("message", "");
            return;
        }
        String json = null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            json = sb.toString().trim();
        } catch (Exception ignored) {}
        if (json == null || json.isEmpty()) {
            response.put("phase", "idle");
            response.put("percent", -1);
            response.put("message", "");
            return;
        }
        try {
            JSONObject parsed = new JSONObject(json);
            long ts = parsed.optLong("ts", 0);
            String phase = parsed.optString("phase", "");
            boolean terminal = "error".equals(phase) || "idle".equals(phase)
                    || ("installing".equals(phase) && parsed.optInt("percent", -1) == 100);
            if (!terminal && ts > 0 && (System.currentTimeMillis() - ts) > 5 * 60 * 1000L) {
                response.put("phase", "idle");
                response.put("percent", -1);
                response.put("message", "");
                return;
            }
            response.put("phase", phase);
            response.put("percent", parsed.optInt("percent", -1));
            response.put("message", parsed.optString("message", ""));
            if (parsed.has("error")) response.put("error", parsed.optString("error"));
        } catch (Exception e) {
            // Malformed JSON → report idle rather than wedge the app poll.
            response.put("phase", "idle");
            response.put("percent", -1);
            response.put("message", "");
        }
    }

    /**
     * Upload a single daemon's log to the Cloudflare Worker and return the
     * short retrieval code. Braveheart-only (LogUploader.isUploadConfigured()
     * is false otherwise). Runs synchronously on the IPC worker thread — the
     * upload itself is network-bound but capped by LogUploader's timeouts.
     * request: { daemon: "<key>" }   response: { success, code } | { error }
     */
    private void handleUploadLog(JSONObject request, JSONObject response) throws Exception {
        if (!com.overdrive.app.logging.LogUploader.isUploadConfigured()) {
            response.put("success", false);
            response.put("error", "Log upload is not available in this build");
            return;
        }
        String daemon = request.optString("daemon", "");
        String path = com.overdrive.app.logging.DaemonLogPaths.pathFor(daemon);
        if (path == null) {
            response.put("success", false);
            response.put("error", "Unknown daemon. Try: "
                    + com.overdrive.app.logging.DaemonLogPaths.keyList());
            return;
        }
        String version = com.overdrive.app.updater.AppUpdater.getDisplayVersionFromFile();
        com.overdrive.app.logging.LogUploader.Result res =
                com.overdrive.app.logging.LogUploader.upload(path, daemon, version);
        response.put("success", res.ok);
        if (res.ok) {
            response.put("code", res.code);
            response.put("daemon", daemon);
        } else {
            response.put("error", res.error != null ? res.error : "upload failed");
        }
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {
            logger.error("Error stopping IPC server", e);
        }
        // SOTA FIX: Kill all active connections immediately on shutdown
        threadPool.shutdownNow();
    }
}
