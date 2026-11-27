package com.overdrive.app.client;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TCP client for communicating with CameraDaemon.
 * Uses TCP on localhost to avoid SELinux cross-context restrictions.
 */
public class CameraDaemonClient {
    
    private static final String TAG = "CameraDaemonClient";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 19876;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    
    private volatile boolean connected = false;
    
    public interface ResponseCallback {
        void onResponse(JSONObject response);
        void onError(String error);
    }

    /**
     * Connect to CameraDaemon via TCP socket on localhost.
     */
    public synchronized boolean connect() {
        // A socket left behind by a read timeout is still open; release it
        // before replacing it (the daemon parks a handler thread on it).
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(30000); // 30 second read timeout
            
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            
            connected = true;
            Log.d(TAG, "Connected to CameraDaemon on " + HOST + ":" + PORT);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to " + HOST + ":" + PORT + ": " + e.getMessage());
            connected = false;
            // Release partially initialized resources if setup failed after
            // creating the socket or either stream.
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (writer != null) writer.close(); } catch (Exception ignored) {}
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            reader = null;
            writer = null;
            socket = null;
            return false;
        }
    }

    /**
     * Disconnect from daemon (daemon keeps running).
     */
    public void disconnect() {
        connected = false;
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null) socket.close();
        } catch (Exception e) {
            Log.d(TAG, "Disconnect: " + e.getMessage());
        }
        socket = null;
        reader = null;
        writer = null;
        Log.d(TAG, "Disconnected from CameraDaemon (daemon still running)");
    }

    /**
     * Check if connected.
     */
    public boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Send command and get response (blocking).
     */
    public JSONObject sendCommand(JSONObject command) throws Exception {
        synchronized (this) {
            // Serialize connection recovery and the complete request/response
            // exchange so concurrent callers cannot replace shared streams.
            if (!isConnected()) {
                if (!connect()) {
                    throw new Exception("Not connected to CameraDaemon");
                }
            }

            try {
                writer.println(command.toString());
                writer.flush();
                if (writer.checkError()) {
                    connected = false;
                    throw new java.io.IOException("Failed to write command to CameraDaemon");
                }

                String response = reader.readLine();
                if (response == null) {
                    connected = false;
                    throw new Exception("CameraDaemon disconnected");
                }
                return new JSONObject(response);
            } catch (Exception e) {
                connected = false;
                throw e;
            }
        }
    }

    /**
     * Send command async.
     */
    public void sendCommandAsync(JSONObject command, ResponseCallback callback) {
        executor.submit(() -> {
            try {
                JSONObject response = sendCommand(command);
                if (callback != null) {
                    callback.onResponse(response);
                }
            } catch (Exception e) {
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    // ==================== CONVENIENCE METHODS ====================

    /**
     * Start recording specified cameras.
     */
    public void startRecording(Set<Integer> cameraIds, boolean enableStreaming, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "start");
            cmd.put("cameras", new JSONArray(cameraIds));
            cmd.put("stream", enableStreaming);
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Stop recording specified cameras (or all if null).
     */
    public void stopRecording(Set<Integer> cameraIds, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "stop");
            cmd.put("explicit", cameraIds == null);
            if (cameraIds != null) {
                cmd.put("cameras", new JSONArray(cameraIds));
            }
            sendCommandAsync(cmd, callback == null ? null : new ResponseCallback() {
                @Override
                public void onResponse(JSONObject response) {
                    if ("ok".equals(response.optString("status"))) {
                        callback.onResponse(response);
                    } else {
                        callback.onError(response.optString(
                                "message", "Camera stop was not confirmed"));
                    }
                }

                @Override
                public void onError(String error) {
                    callback.onError(error);
                }
            });
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Get daemon status.
     */
    public void getStatus(ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "status");
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Parse recording cameras from response.
     */
    public static Set<Integer> parseRecordingCameras(JSONObject response) {
        Set<Integer> cameras = new HashSet<>();
        try {
            JSONArray arr = response.optJSONArray("recording");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    cameras.add(arr.getInt(i));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
        }
        return cameras;
    }

    /**
     * Cleanup client resources (daemon keeps running).
     */
    public void destroy() {
        disconnect();
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    // ==================== AUTH COMMANDS ====================

    /**
     * Invalidate auth cache in daemon (blocking).
     * @return true if successful
     */
    public boolean invalidateAuthCacheSync() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "auth_invalidate");
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "invalidateAuthCache error: " + e.getMessage());
            return false;
        }
    }
}
