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
                // User explicitly requested stop - force stop even if recording
                boolean forceStop = cmd.optBoolean("force", true);  // Default to force for backward compat
                JSONArray camsToStop = cmd.optJSONArray("cameras");
                if (camsToStop != null) {
                    for (int i = 0; i < camsToStop.length(); i++) {
                        CameraDaemon.stopCamera(camsToStop.getInt(i), forceStop);
                    }
                } else {
                    CameraDaemon.stopAllCameras(forceStop);
                }
                response.put("status", "ok");
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
            
            case "enableSurveillance":
                com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true);
                if (!com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                    CameraDaemon.enableSurveillance();
                }
                response.put("status", "ok");
                response.put("surveillance", CameraDaemon.getSurveillanceStatus());
                break;

            case "disableSurveillance":
                CameraDaemon.disableSurveillance();
                com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false);
                response.put("status", "ok");
                response.put("surveillance", CameraDaemon.getSurveillanceStatus());
                break;

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
                // Set recordings storage type: INTERNAL or SD_CARD
                String recStorageTypeValue = cmd.optString("value", "").toUpperCase();
                if (recStorageTypeValue.equals("INTERNAL") || recStorageTypeValue.equals("SD_CARD")) {
                    com.overdrive.app.storage.StorageManager storageManager =
                        com.overdrive.app.storage.StorageManager.getInstance();
                    com.overdrive.app.storage.StorageManager.StorageType recType =
                        "SD_CARD".equals(recStorageTypeValue) ?
                            com.overdrive.app.storage.StorageManager.StorageType.SD_CARD :
                            com.overdrive.app.storage.StorageManager.StorageType.INTERNAL;
                    boolean recSuccess = storageManager.setRecordingsStorageType(recType);
                    if (recSuccess) {
                        response.put("status", "ok");
                        response.put("storageType", recStorageTypeValue);
                        response.put("path", storageManager.getRecordingsPath());
                        response.put("message", "Recordings storage set to " + recStorageTypeValue);
                        CameraDaemon.log("Recordings storage type set to " + recStorageTypeValue + " via TCP IPC");
                    } else {
                        response.put("status", "error");
                        response.put("message", "SD card not available");
                    }
                } else {
                    response.put("status", "error");
                    response.put("message", "Invalid storage type. Use INTERNAL or SD_CARD");
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
                    java.io.File unifiedFile = new java.io.File("/data/local/tmp/overdrive_config.json");
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

            case "shell":
                // Execute shell command (used by SentryDaemon to run commands as UID 2000)
                String shellCmd = cmd.optString("command", "");
                CameraDaemon.log("Shell command received: " + shellCmd);
                if (!shellCmd.isEmpty()) {
                    try {
                        Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", shellCmd});
                        int exitCode = process.waitFor();
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()));
                        StringBuilder output = new StringBuilder();
                        String outputLine;
                        while ((outputLine = reader.readLine()) != null) {
                            output.append(outputLine).append("\n");
                        }
                        // Also read stderr
                        java.io.BufferedReader errReader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getErrorStream()));
                        StringBuilder errOutput = new StringBuilder();
                        while ((outputLine = errReader.readLine()) != null) {
                            errOutput.append(outputLine).append("\n");
                        }
                        
                        response.put("status", "ok");
                        response.put("output", output.toString().trim());
                        response.put("stderr", errOutput.toString().trim());
                        response.put("exitCode", exitCode);
                        CameraDaemon.log("Shell command completed: exitCode=" + exitCode + ", output=" + output.toString().trim());
                    } catch (Exception e) {
                        CameraDaemon.log("Shell command failed: " + e.getMessage());
                        response.put("status", "error");
                        response.put("message", "Shell exec failed: " + e.getMessage());
                    }
                } else {
                    response.put("status", "error");
                    response.put("message", "No command specified");
                }
                break;

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
