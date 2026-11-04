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
    public boolean connect() {
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
        if (!isConnected()) {
            // Try to reconnect
            if (!connect()) {
                throw new Exception("Not connected to CameraDaemon");
            }
        }
        
        synchronized (this) {
            try {
                writer.println(com.overdrive.app.remote.RemoteDevViewBridgeAuth.sign(command));
                writer.flush();
                
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
     * Send command string and get response (blocking).
     */
    public JSONObject sendCommand(String commandJson) {
        try {
            return sendCommand(new JSONObject(commandJson));
        } catch (Exception e) {
            Log.e(TAG, "sendCommand error: " + e.getMessage());
            return null;
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
     * Ping daemon to check if it's alive.
     */
    public boolean ping() {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "ping");
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "Ping failed: " + e.getMessage());
            return false;
        }
    }

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
     * Set output directory.
     */
    public void setOutputDir(String path, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setOutput");
            cmd.put("path", path);
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Enable/disable streaming for a camera.
     */
    public void setStreaming(int cameraId, boolean enable, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "stream");
            cmd.put("camera", cameraId);
            cmd.put("enable", enable);
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Get latest frame from a camera (blocking).
     * Returns base64-encoded JPEG or null on error.
     */
    public byte[] getFrame(int cameraId) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "getFrame");
            cmd.put("camera", cameraId);
            JSONObject response = sendCommand(cmd);
            if ("ok".equals(response.optString("status"))) {
                String base64 = response.optString("frame");
                if (base64 != null && !base64.isEmpty()) {
                    return android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getFrame error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Get frame dimensions for a camera.
     */
    public int[] getFrameDimensions(int cameraId) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "getFrame");
            cmd.put("camera", cameraId);
            JSONObject response = sendCommand(cmd);
            if ("ok".equals(response.optString("status"))) {
                return new int[] {
                    response.optInt("width", 0),
                    response.optInt("height", 0)
                };
            }
        } catch (Exception e) {
            Log.e(TAG, "getFrameDimensions error: " + e.getMessage());
        }
        return null;
    }

    /**
     * Shutdown the daemon.
     */
    public void shutdown(ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "shutdown");
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    // ==================== QUALITY SETTINGS ====================

    /**
     * Set recording bitrate.
     * @param bitrate LOW (2 Mbps), MEDIUM (3 Mbps), or HIGH (6 Mbps)
     */
    public void setRecordingBitrate(String bitrate, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setBitrate");
            cmd.put("value", bitrate.toUpperCase());
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Set recording codec.
     * @param codec H264 or H265
     */
    public void setRecordingCodec(String codec, ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setCodec");
            cmd.put("value", codec.toUpperCase());
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Get current quality settings from daemon.
     */
    public void getQualitySettings(ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "getQualitySettings");
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Set recording bitrate (blocking).
     * @param bitrate LOW, MEDIUM, or HIGH
     * @return true if successful
     */
    public boolean setRecordingBitrateSync(String bitrate) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setBitrate");
            cmd.put("value", bitrate.toUpperCase());
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "setRecordingBitrate error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set recording codec (blocking).
     * @param codec H264 or H265
     * @return true if successful
     */
    public boolean setRecordingCodecSync(String codec) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setCodec");
            cmd.put("value", codec.toUpperCase());
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "setRecordingCodec error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Set recording mode (blocking).
     * @param mode NONE, CONTINUOUS, DRIVE_MODE, or PROXIMITY_GUARD
     * @return true if successful
     */
    public boolean setRecordingModeSync(String mode) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setRecordingMode");
            cmd.put("mode", mode.toUpperCase());
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "setRecordingMode error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set recordings storage type (blocking).
     * @param type INTERNAL or SD_CARD
     * @return true if successful
     */
    public boolean setRecordingsStorageTypeSync(String type) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setRecordingsStorageType");
            cmd.put("value", type.toUpperCase());
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "setRecordingsStorageType error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Set recordings storage limit (blocking).
     * @param limitMb limit in megabytes
     * @return true if successful
     */
    public boolean setRecordingsLimitMbSync(long limitMb) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "setRecordingsLimitMb");
            cmd.put("value", limitMb);
            JSONObject response = sendCommand(cmd);
            return "ok".equals(response.optString("status"));
        } catch (Exception e) {
            Log.e(TAG, "setRecordingsLimitMb error: " + e.getMessage());
            return false;
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
     * Invalidate auth cache in daemon.
     * Call this after regenerating device token to force daemon to reload auth state.
     * This ensures old JWTs signed with the previous secret are rejected.
     */
    public void invalidateAuthCache(ResponseCallback callback) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("cmd", "auth_invalidate");
            sendCommandAsync(cmd, callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

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
