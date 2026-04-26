package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Iterator;

/**
 * MQTT API Handler - proxies HTTP API requests to IPC commands on port 19877.
 *
 * Endpoints:
 * - GET    /api/mqtt/connections          → GET_MQTT_CONNECTIONS (list all)
 * - POST   /api/mqtt/connections          → ADD_MQTT_CONNECTION (add new)
 * - PUT    /api/mqtt/connections/{id}     → UPDATE_MQTT_CONNECTION (update by ID)
 * - DELETE /api/mqtt/connections/{id}     → DELETE_MQTT_CONNECTION (delete by ID)
 * - GET    /api/mqtt/status               → GET_MQTT_STATUS (all connections with live stats)
 * - GET    /api/mqtt/telemetry            → GET_MQTT_TELEMETRY (latest telemetry snapshot)
 */
public class MqttApiHandler {

    private static final String TAG = "MqttApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * Handle MQTT API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        // GET /api/mqtt/connections — list all
        if (path.equals("/api/mqtt/connections") && method.equals("GET")) {
            handleGetConnections(out);
            return true;
        }

        // POST /api/mqtt/connections — add new
        if (path.equals("/api/mqtt/connections") && method.equals("POST")) {
            handleAddConnection(out, body);
            return true;
        }

        // PUT /api/mqtt/connections/{id} — update
        if (path.startsWith("/api/mqtt/connections/") && method.equals("PUT")) {
            String id = path.substring("/api/mqtt/connections/".length());
            if (!id.matches("[a-f0-9\\-]{4,36}")) {
                HttpResponse.sendJsonError(out, "Invalid connection id");
                return true;
            }
            handleUpdateConnection(out, id, body);
            return true;
        }

        // DELETE /api/mqtt/connections/{id} — delete
        if (path.startsWith("/api/mqtt/connections/") && method.equals("DELETE")) {
            String id = path.substring("/api/mqtt/connections/".length());
            if (!id.matches("[a-f0-9\\-]{4,36}")) {
                HttpResponse.sendJsonError(out, "Invalid connection id");
                return true;
            }
            handleDeleteConnection(out, id);
            return true;
        }

        // GET /api/mqtt/status — all connections with live stats
        if (path.equals("/api/mqtt/status") && method.equals("GET")) {
            handleGetStatus(out);
            return true;
        }

        // GET /api/mqtt/telemetry — latest telemetry snapshot
        if (path.equals("/api/mqtt/telemetry") && method.equals("GET")) {
            handleGetTelemetry(out);
            return true;
        }

        return false;
    }

    private static void handleGetConnections(OutputStream out) throws Exception {
        JSONObject ipcResponse = sendIpcCommand("GET_MQTT_CONNECTIONS", null);
        if (ipcResponse != null) {
            HttpResponse.sendJson(out, ipcResponse.toString());
        } else {
            HttpResponse.sendJsonError(out, "Failed to communicate with daemon");
        }
    }

    private static void handleAddConnection(OutputStream out, String body) throws Exception {
        try {
            JSONObject bodyJson = new JSONObject(body);
            JSONObject ipcResponse = sendIpcCommand("ADD_MQTT_CONNECTION", bodyJson);
            if (ipcResponse != null) {
                HttpResponse.sendJson(out, ipcResponse.toString());
            } else {
                HttpResponse.sendJsonError(out, "Failed to communicate with daemon");
            }
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid request body: " + e.getMessage());
        }
    }

    private static void handleUpdateConnection(OutputStream out, String id, String body) throws Exception {
        try {
            JSONObject bodyJson = new JSONObject(body);
            bodyJson.put("id", id);
            JSONObject ipcResponse = sendIpcCommand("UPDATE_MQTT_CONNECTION", bodyJson);
            if (ipcResponse != null) {
                HttpResponse.sendJson(out, ipcResponse.toString());
            } else {
                HttpResponse.sendJsonError(out, "Failed to communicate with daemon");
            }
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, "Invalid request body: " + e.getMessage());
        }
    }

    private static void handleDeleteConnection(OutputStream out, String id) throws Exception {
        JSONObject extra = new JSONObject();
        extra.put("id", id);
        JSONObject ipcResponse = sendIpcCommand("DELETE_MQTT_CONNECTION", extra);
        if (ipcResponse != null) {
            HttpResponse.sendJson(out, ipcResponse.toString());
        } else {
            HttpResponse.sendJsonError(out, "Failed to communicate with daemon");
        }
    }

    private static void handleGetStatus(OutputStream out) throws Exception {
        JSONObject ipcResponse = sendIpcCommand("GET_MQTT_STATUS", null);
        if (ipcResponse != null) {
            HttpResponse.sendJson(out, ipcResponse.toString());
        } else {
            HttpResponse.sendJsonError(out, "Failed to communicate with daemon");
        }
    }

    private static void handleGetTelemetry(OutputStream out) throws Exception {
        JSONObject ipcResponse = sendIpcCommand("GET_MQTT_TELEMETRY", null);
        if (ipcResponse != null) {
            HttpResponse.sendJson(out, ipcResponse.toString());
        } else {
            HttpResponse.sendJsonError(out, "Failed to communicate with daemon");
        }
    }

    /**
     * Send an IPC command to the daemon on port 19877.
     */
    private static JSONObject sendIpcCommand(String command, JSONObject extraFields) {
        try {
            Socket socket = new Socket("127.0.0.1", 19877);
            socket.setSoTimeout(3000);
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            JSONObject request = new JSONObject();
            request.put("command", command);
            if (extraFields != null) {
                Iterator<String> keys = extraFields.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    request.put(key, extraFields.get(key));
                }
            }
            writer.println(request.toString());

            String response = reader.readLine();
            socket.close();
            return response != null ? new JSONObject(response) : null;
        } catch (Exception e) {
            logger.error("IPC communication error: " + e.getMessage());
            return null;
        }
    }
}
