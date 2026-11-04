package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

/**
 * TCP Command Server - handles JSON commands from DaemonClient.
 * Listens on localhost:19876 for security.
 */
public class TcpCommandServer {

    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = true;

    /** Consecutive bind failures, reset on a successful bind. */
    private int bindFailures;

    public TcpCommandServer(int port) {
        this.port = port;
    }

    public void start() {
        CameraDaemon.log("TCP server starting on port " + port);
        
        while (running && CameraDaemon.isRunning()) {
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try { serverSocket.close(); } catch (Exception e) {}
                }
                
                serverSocket = new ServerSocket(port, 5, InetAddress.getByName("127.0.0.1"));
                serverSocket.setReuseAddress(true);
                bindFailures = 0;
                CameraDaemon.log("TCP server listening on 127.0.0.1:" + port);

                while (running && CameraDaemon.isRunning() && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        CameraDaemon.log("TCP client connected: " + client.getRemoteSocketAddress());
                        new Thread(() -> handleClient(client), "TcpClient-" + System.currentTimeMillis()).start();
                    } catch (java.net.SocketException e) {
                        if (running) {
                            CameraDaemon.log("WARN: TCP socket error: " + e.getMessage());
                        }
                        break;
                    }
                }
                
                if (running) {
                    CameraDaemon.log("TCP server restarting...");
                    Thread.sleep(2000);
                }
                
            } catch (java.net.BindException e) {
                CameraDaemon.log("ERROR: TCP port " + port + " in use, retrying...");
                // A second failure is not TIME_WAIT — name the holder once per episode.
                if (++bindFailures == 2) {
                    CameraDaemon.log("ERROR: TCP port " + port + " held by "
                            + PortOwner.describe(port));
                }
                try { Thread.sleep(5000); } catch (InterruptedException ie) {}
            } catch (Exception e) {
                CameraDaemon.log("ERROR: TCP server error: " + e.getMessage());
                if (running) {
                    try { Thread.sleep(3000); } catch (InterruptedException ie) {}
                }
            }
        }
        
        CameraDaemon.log("TCP server stopped");
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {}
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(client.getOutputStream()), true);

            String line;
            while ((line = reader.readLine()) != null) {
                CameraDaemon.log("TCP received: " + line);
                
                try {
                    JSONObject cmd = new JSONObject(line);
                    // SECURITY (run-2 fix): signed envelope required for everything
                    // except read-only probes; loopback alone is not authentication.
                    if (cmd.has("signature") && cmd.has("request")) {
                        com.overdrive.app.remote.RemoteDevViewBridgeAuth.AuthenticatedRequest auth =
                                com.overdrive.app.remote.RemoteDevViewBridgeAuth.verify(line);
                        if (!rememberNonce(auth.nonce, auth.timestampMs)) {
                            throw new SecurityException("IPC replay rejected");
                        }
                        cmd = auth.request;
                    } else {
                        String action = cmd.optString("cmd", "");
                        if (!"status".equals(action) && !"ping".equals(action)) {
                            throw new SecurityException("unsigned IPC command rejected: " + action);
                        }
                    }
                    JSONObject response = processCommand(cmd);
                    writer.println(response.toString());
                } catch (Exception e) {
                    JSONObject error = new JSONObject();
                    error.put("status", "error");
                    error.put("message", e.getMessage());
                    writer.println(error.toString());
                }
            }
        } catch (Exception e) {
            CameraDaemon.log("TCP client disconnected: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception e) {}
        }
    }

    // SECURITY (run-2 fix): nonce replay cache for signed envelopes.
    private static final java.util.Map<String, Long> NONCE_SEEN = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean rememberNonce(String nonce, long timestampMs) {
        long now = System.currentTimeMillis();
        NONCE_SEEN.entrySet().removeIf(e -> now - e.getValue() > 60_000L);
        return NONCE_SEEN.putIfAbsent(nonce, timestampMs) == null;
    }

    private JSONObject processCommand(JSONObject cmd) throws Exception {
        String action = cmd.optString("cmd", "");
        JSONObject response = new JSONObject();
        
        CameraDaemon.log("Processing command: " + action);

        switch (action) {
            case "start":
                // Start recording - streaming is now independent and NOT started by default
                JSONArray camsToStart = cmd.optJSONArray("cameras");
                boolean enableStream = cmd.optBoolean("stream", false);  // Default to false - streaming is separate
                if (camsToStart != null) {
                    for (int i = 0; i < camsToStart.length(); i++) {
                        int camId = camsToStart.getInt(i);
                        CameraDaemon.startCamera(camId, enableStream, false);
                    }
                }
                response.put("status", "ok");
                response.put("recording", getRecordingCameras());
                break;

            case "stop":
                boolean forceStop = cmd.optBoolean("force", true);  // Default to force for backward compat
                JSONArray camsToStop = cmd.optJSONArray("cameras");
                boolean explicitStop = cmd.optBoolean(
                        "explicit", camsToStop == null);
                boolean stopConfirmed = true;
                if (explicitStop) {
                    stopConfirmed =
                            CameraDaemon.stopAllCamerasForExplicitUserStop();
                } else if (camsToStop != null) {
                    stopConfirmed = camsToStop.length() > 0
                            && CameraDaemon.stopAllCameras(forceStop);
                } else {
                    stopConfirmed = false;
                }
                response.put("status", stopConfirmed ? "ok" : "error");
                if (!stopConfirmed) {
                    response.put(
                            "message", "Camera stop was not confirmed");
                }
                response.put("recording", getRecordingCameras());
                break;

            case "status":
                response.put("status", "ok");
                response.put("recording", getRecordingCameras());
                response.put("viewing", getViewOnlyCameras());
                response.put("active", getActiveCameras());
                response.put("streaming", getStreamingCameras());
                response.put("available", getAvailableCameras());
                break;

            case "ping":
                response.put("status", "ok");
                response.put("message", "pong");
                break;

            case "getFrame":
                int frameViewId = cmd.optInt("camera", 1);
                // GPU pipeline: get frame from GPU camera extractor
                com.overdrive.app.surveillance.GpuSurveillancePipeline gpuPipeline = CameraDaemon.getGpuPipeline();
                if (gpuPipeline != null && gpuPipeline.getCamera() != null) {
                    byte[] jpegFrame = gpuPipeline.getCamera().getLatestJpegFrame(frameViewId);
                    if (jpegFrame != null) {
                        String base64Frame = android.util.Base64.encodeToString(jpegFrame, android.util.Base64.NO_WRAP);
                        response.put("status", "ok");
                        response.put("frame", base64Frame);
                        response.put("timestamp", System.currentTimeMillis());
                    } else {
                        response.put("status", "error");
                        response.put("message", "No frame available for view " + frameViewId);
                    }
                } else {
                    response.put("status", "error");
                    response.put("message", "GPU pipeline not available");
                }
                break;

            case "setOutput":
                response.put("status", "ok");
                response.put("outputDir", CameraDaemon.getOutputDir());
                break;

            case "shutdown":
                response.put("status", "ok");
                new Thread(() -> {
                    try { Thread.sleep(300); } catch (InterruptedException e) {}
                    CameraDaemon.shutdown();
                }, "ShutdownThread").start();
                break;

            // ==================== STREAMING COMMANDS ====================
            
            case "startStream":
                JSONArray streamsToStart = cmd.optJSONArray("cameras");
                if (streamsToStart != null) {
                    for (int i = 0; i < streamsToStart.length(); i++) {
                        CameraDaemon.startStreaming(streamsToStart.getInt(i));
                    }
                } else {
                    // Start all if no cameras specified
                    CameraDaemon.startAllStreaming();
                }
                response.put("status", "ok");
                response.put("streaming", getStreamingCameras());
                break;

            case "stopStream":
                JSONArray streamsToStop = cmd.optJSONArray("cameras");
                if (streamsToStop != null) {
                    for (int i = 0; i < streamsToStop.length(); i++) {
                        CameraDaemon.stopStreaming(streamsToStop.getInt(i));
                    }
                } else {
                    CameraDaemon.stopAllStreaming();
                }
                response.put("status", "ok");
                response.put("streaming", getStreamingCameras());
                break;

            case "setRtmpUrl":
                // VPS streaming removed - this command is now a no-op
                response.put("status", "ok");
                response.put("message", "VPS streaming removed - use local HTTP streaming");
                break;

            case "streamStatus":
                response.put("status", "ok");
                Map<String, Object> streamInfo = CameraDaemon.getStreamingStatus();
                response.put("enabled", streamInfo.get("enabled"));
                response.put("deviceId", streamInfo.get("deviceId"));
                response.put("streaming", getStreamingCameras());
                response.put("publisherCount", streamInfo.get("publisherCount"));
                response.put("mode", streamInfo.get("mode"));
                response.put("note", "VPS streaming removed - use local HTTP streaming");
                break;

            case "setStreamMode":
                String mode = cmd.optString("mode", "");
                if (mode.equals("private") || mode.equals("public")) {
                    CameraDaemon.setStreamMode(mode);
                    response.put("status", "ok");
                    response.put("mode", CameraDaemon.getStreamMode());
                    response.put("message", "Stream mode set to " + mode + " (both use tunnel URLs now)");
                } else {
                    response.put("status", "error");
                    response.put("message", "Invalid mode. Use 'private' or 'public'");
                }
                break;

            case "getStreamMode":
                response.put("status", "ok");
                response.put("mode", CameraDaemon.getStreamMode());
                response.put("isPublic", CameraDaemon.isPublicMode());
                break;

            // ==================== SURVEILLANCE COMMANDS ====================
            
            case "enableSurveillance": {
                // Persist result is load-bearing: the ACC-OFF arm dispatch re-reads
                // it, so a failed write means surveillance never arms. Report it.
                boolean accOn = com.overdrive.app.monitor.AccMonitor.isAccOn();
                if (!com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true)) {
                    response.put("status", "error");
                    response.put("message", "Failed to save the surveillance setting");
                    break;
                }
                if (!accOn) {
                    CameraDaemon.enableSurveillance();   // fires OEM recalc
                } else {
                    // OEM resolver: survSuppressed depends on master toggle.
                    try { com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                    catch (Throwable ignored) {}
                }
                response.put("status", "ok");
                response.put("deferred", accOn || !CameraDaemon.isAccOffAdmitted());
                response.put("surveillance", CameraDaemon.getSurveillanceStatus());
                break;
            }

            case "disableSurveillance": {
                // ACC-gated teardown — nothing is armed while ACC is ON, and the
                // pipeline call would re-apply the dashcam layout under a live
                // recording. See SurveillanceApiHandler.handleDisable.
                boolean accOn = com.overdrive.app.monitor.AccMonitor.isAccOn();
                if (!com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false)) {
                    response.put("status", "error");
                    response.put("message", "Failed to save the surveillance setting");
                    break;
                }
                if (!accOn) {
                    CameraDaemon.disableSurveillance();   // fires OEM recalc
                } else {
                    try { com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc(); }
                    catch (Throwable ignored) {}
                }
                response.put("status", "ok");
                response.put("deferred", accOn);
                response.put("surveillance", CameraDaemon.getSurveillanceStatus());
                break;
            }

            case "surveillanceStatus":
                response.put("status", "ok");
                response.put("surveillance", CameraDaemon.getSurveillanceStatus());
                break;

            case "setAccState":
                boolean accOff = cmd.optBoolean("accOff", false);
                CameraDaemon.onAccStateChanged(accOff);
                response.put("status", "ok");
                response.put("accOff", accOff);
                response.put("surveillance", CameraDaemon.getSurveillanceStatus());
                break;
            
            // ==================== RECORDING MODE COMMANDS ====================
            
            case "setRecordingMode":
                String recordingMode = cmd.optString("mode", "");
                if (!recordingMode.isEmpty()) {
                    CameraDaemon.setRecordingMode(recordingMode);
                    response.put("status", "ok");
                    response.put("mode", CameraDaemon.getRecordingMode());
                } else {
                    response.put("status", "error");
                    response.put("message", "No mode specified");
                }
                break;
            
            case "getRecordingMode":
                response.put("status", "ok");
                response.put("mode", CameraDaemon.getRecordingMode());
                break;

            // ==================== QUALITY SETTINGS COMMANDS ====================
            
            case "setBitrate":
                // Set recording bitrate: LOW (2Mbps), MEDIUM (3Mbps), HIGH (6Mbps)
                String bitrateValue = cmd.optString("value", "").toUpperCase();
                if (bitrateValue.equals("LOW") || bitrateValue.equals("MEDIUM") || bitrateValue.equals("HIGH")) {
                    CameraDaemon.setRecordingBitrate(bitrateValue);
                    HttpServer.setRecordingBitrate(bitrateValue);
                    response.put("status", "ok");
                    response.put("bitrate", bitrateValue);
                    response.put("message", "Bitrate set to " + bitrateValue + " - applied immediately");
                } else {
                    response.put("status", "error");
                    response.put("message", "Invalid bitrate. Use LOW, MEDIUM, or HIGH");
                }
                break;

            case "setCodec":
                // Set recording codec: H264 or H265
                String codecValue = cmd.optString("value", "").toUpperCase();
                if (codecValue.equals("H264") || codecValue.equals("H265")) {
                    CameraDaemon.setRecordingCodec(codecValue);
                    HttpServer.setRecordingCodec(codecValue);
                    response.put("status", "ok");
                    response.put("codec", codecValue);
                    response.put("message", "Codec set to " + codecValue + " - restart recording to apply");
                } else {
                    response.put("status", "error");
                    response.put("message", "Invalid codec. Use H264 or H265");
                }
                break;

            case "setRecordingsStorageType":
                // Set recordings storage type: INTERNAL, SD_CARD, or USB
                String recStorageTypeValue = cmd.optString("value", "").toUpperCase();
                com.overdrive.app.storage.StorageManager.StorageType recType = null;
                switch (recStorageTypeValue) {
                    case "INTERNAL": recType = com.overdrive.app.storage.StorageManager.StorageType.INTERNAL; break;
                    case "SD_CARD":  recType = com.overdrive.app.storage.StorageManager.StorageType.SD_CARD;  break;
                    case "USB":      recType = com.overdrive.app.storage.StorageManager.StorageType.USB;      break;
                }
                if (recType != null) {
                    com.overdrive.app.storage.StorageManager storageManager =
                        com.overdrive.app.storage.StorageManager.getInstance();
                    boolean recSuccess = storageManager.setRecordingsStorageType(recType);
                    if (recSuccess) {
                        response.put("status", "ok");
                        response.put("storageType", recStorageTypeValue);
                        response.put("path", storageManager.getRecordingsPath());
                        response.put("message", "Recordings storage set to " + recStorageTypeValue);
                        CameraDaemon.log("Recordings storage type set to " + recStorageTypeValue + " via TCP IPC");
                    } else {
                        response.put("status", "error");
                        response.put("message", recType.name() + " not available");
                    }
                } else {
                    response.put("status", "error");
                    response.put("message", "Invalid storage type. Use INTERNAL, SD_CARD, or USB");
                }
                break;

            case "setRecordingsLimitMb":
                // Set recordings storage limit in MB
                long recLimitMb = cmd.optLong("value", -1);
                if (recLimitMb > 0) {
                    com.overdrive.app.storage.StorageManager recLimitStorage =
                        com.overdrive.app.storage.StorageManager.getInstance();
                    recLimitStorage.setRecordingsLimitMb(recLimitMb);
                    response.put("status", "ok");
                    response.put("limitMb", recLimitStorage.getRecordingsLimitMb());
                    response.put("message", "Recordings limit set to " + recLimitStorage.getRecordingsLimitMb() + " MB");
                    CameraDaemon.log("Recordings limit set to " + recLimitStorage.getRecordingsLimitMb() + " MB via TCP IPC");
                    // Trigger async cleanup
                    new Thread(() -> recLimitStorage.ensureRecordingsSpace(0), "RecLimitCleanup").start();
                } else {
                    response.put("status", "error");
                    response.put("message", "Invalid limit value");
                }
                break;

            case "getQualitySettings":
                // Get current quality settings - read directly from unified config for cross-UID sync
                response.put("status", "ok");
                
                // Read from unified config file (source of truth for cross-UID access)
                String bitrate = "MEDIUM";
                String codec = "H264";
                try {
                    java.io.File unifiedFile = new java.io.File(com.overdrive.app.util.ScratchRoot.path("overdrive_config.json"));
                    if (unifiedFile.exists()) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(unifiedFile));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();
                        
                        JSONObject unified = new JSONObject(sb.toString());
                        JSONObject recording = unified.optJSONObject("recording");
                        if (recording != null) {
                            bitrate = recording.optString("bitrate", "MEDIUM");
                            codec = recording.optString("codec", "H264");
                        }
                    }
                } catch (Exception e) {
                    CameraDaemon.log("getQualitySettings: Could not read unified config: " + e.getMessage());
                    // Fall back to HttpServer static vars
                    bitrate = HttpServer.getRecordingBitrate();
                    codec = HttpServer.getRecordingCodec();
                }
                
                response.put("bitrate", bitrate);
                response.put("codec", codec);
                response.put("bitrateOptions", new JSONObject()
                    .put("LOW", "2 Mbps")
                    .put("MEDIUM", "3 Mbps")
                    .put("HIGH", "6 Mbps"));
                response.put("codecOptions", new JSONObject()
                    .put("H264", "H.264/AVC (Compatible)")
                    .put("H265", "H.265/HEVC (50% smaller)"));
                break;

            case "auth_invalidate":
                // Invalidate cached auth state - called when app regenerates token
                // This forces daemon to reload auth state from file on next JWT validation
                com.overdrive.app.auth.AuthManager.invalidateCache();
                CameraDaemon.log("Auth cache invalidated via IPC");
                response.put("status", "ok");
                response.put("message", "Auth cache invalidated");
                break;

            case "telenavUse":
                // Sent by the app before it launches or binds Telenav; replies once tracking is armed.
                com.overdrive.app.telenav.DeferredNavManager.noteTelenavUse();
                response.put("status", "ok");
                break;

            // REMOVED: "shell" — ran an arbitrary command as UID 2000 with no auth on
            // this port. The legacy SentryDaemon caller it was written for is gone, so
            // nothing in-tree sent it; it now falls through to "Unknown command".

            default:
                response.put("status", "error");
                response.put("message", "Unknown command: " + action);
        }
        
        return response;
    }

    // ==================== STATUS HELPERS ====================
    
    public static JSONArray getRecordingCameras() {
        JSONArray arr = new JSONArray();
        // GPU pipeline: Only show as recording if in recording mode AND actually recording
        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline != null && pipeline.isRecordingMode() && pipeline.isRecording()) {
            // Mosaic recording = all 4 cameras
            arr.put(1);
            arr.put(2);
            arr.put(3);
            arr.put(4);
        }
        return arr;
    }

    public static JSONArray getViewOnlyCameras() {
        JSONArray arr = new JSONArray();
        // GPU pipeline: Show as viewing if running but not in recording mode
        com.overdrive.app.surveillance.GpuSurveillancePipeline pipeline = CameraDaemon.getGpuPipeline();
        if (pipeline != null && pipeline.isRunning() && !pipeline.isRecordingMode()) {
            // Viewing all 4 cameras
            arr.put(1);
            arr.put(2);
            arr.put(3);
            arr.put(4);
        }
        return arr;
    }

    public static JSONArray getActiveCameras() {
        JSONArray arr = new JSONArray();
        // GPU pipeline: all 4 cameras active together
        if (CameraDaemon.isSurveillanceActive()) {
            arr.put(1);
            arr.put(2);
            arr.put(3);
            arr.put(4);
        }
        return arr;
    }

    public static JSONArray getStreamingCameras() {
        JSONArray arr = new JSONArray();
        for (Integer camId : CameraDaemon.getStreamingCameras()) {
            arr.put(camId);
        }
        return arr;
    }

    public static JSONArray getAvailableCameras() {
        JSONArray arr = new JSONArray();
        for (int i = 1; i <= 4; i++) {
            arr.put(i);
        }
        return arr;
    }
}
