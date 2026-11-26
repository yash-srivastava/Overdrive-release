package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;

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
    private static final DaemonIpcHttpProxy ipc = new DaemonIpcHttpProxy(logger, "");

    /**
     * Handle MQTT API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        // GET /api/mqtt/connections — list all
        if (path.equals("/api/mqtt/connections") && method.equals("GET")) {
            ipc.send(out, "GET_MQTT_CONNECTIONS", null, null);
            return true;
        }

        // POST /api/mqtt/connections — add new
        if (path.equals("/api/mqtt/connections") && method.equals("POST")) {
            if (!StepUpGate.requireBundle("config", "Change MQTT connections", out)) return true;
            handleConnectionChange(out, "ADD_MQTT_CONNECTION", null, body);
            return true;
        }

        // PUT /api/mqtt/connections/{id} — update
        if (path.startsWith("/api/mqtt/connections/") && method.equals("PUT")) {
            String id = path.substring("/api/mqtt/connections/".length());
            if (!id.matches("[a-f0-9\\-]{4,36}")) {
                HttpResponse.sendJsonError(out, Messages.get("errors.mqtt_invalid_connection_id"));
                return true;
            }
            if (!StepUpGate.requireBundle("config", "Change MQTT connections", out)) return true;
            handleConnectionChange(out, "UPDATE_MQTT_CONNECTION", id, body);
            return true;
        }

        // DELETE /api/mqtt/connections/{id} — delete
        if (path.startsWith("/api/mqtt/connections/") && method.equals("DELETE")) {
            String id = path.substring("/api/mqtt/connections/".length());
            if (!id.matches("[a-f0-9\\-]{4,36}")) {
                HttpResponse.sendJsonError(out, Messages.get("errors.mqtt_invalid_connection_id"));
                return true;
            }
            handleDeleteConnection(out, id);
            return true;
        }

        // GET /api/mqtt/status — all connections with live stats
        if (path.equals("/api/mqtt/status") && method.equals("GET")) {
            ipc.send(out, "GET_MQTT_STATUS", null, null);
            return true;
        }

        // GET /api/mqtt/telemetry — latest telemetry snapshot
        if (path.equals("/api/mqtt/telemetry") && method.equals("GET")) {
            ipc.send(out, "GET_MQTT_TELEMETRY", null, null);
            return true;
        }

        return false;
    }

    private static void handleConnectionChange(OutputStream out, String command, String id,
                                               String body) throws Exception {
        try {
            JSONObject bodyJson = new JSONObject(body);
            if (id != null) bodyJson.put("id", id);
            ipc.send(out, command, bodyJson, null);
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, Messages.get("errors.invalid_request_body_with_detail", e.getMessage()));
        }
    }

    private static void handleDeleteConnection(OutputStream out, String id) throws Exception {
        JSONObject extra = new JSONObject();
        extra.put("id", id);
        ipc.send(out, "DELETE_MQTT_CONNECTION", extra, null);
    }
}
