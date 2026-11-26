package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * ABRP API Handler - proxies HTTP API requests to IPC commands on port 19877.
 *
 * Endpoints:
 * - GET  /api/abrp/config → GET_ABRP_CONFIG IPC command
 * - POST /api/abrp/config → SET_ABRP_CONFIG IPC command
 * - GET  /api/abrp/status → GET_ABRP_STATUS IPC command
 * - DELETE /api/abrp/token → DELETE_ABRP_TOKEN IPC command
 */
public class AbrpApiHandler {

    private static final String TAG = "AbrpApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final DaemonIpcHttpProxy ipc = new DaemonIpcHttpProxy(logger, TAG + ": ");

    /**
     * Handle ABRP API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/abrp/config") && method.equals("GET")) {
            ipc.send(out, "GET_ABRP_CONFIG", null, "Failed to get ABRP config via IPC");
            return true;
        }
        if (path.equals("/api/abrp/config") && method.equals("POST")) {
            handleSetConfig(out, body);
            return true;
        }
        if (path.equals("/api/abrp/status") && method.equals("GET")) {
            ipc.send(out, "GET_ABRP_STATUS", null, "Failed to get ABRP status via IPC");
            return true;
        }
        if (path.equals("/api/abrp/token") && method.equals("DELETE")) {
            ipc.send(out, "DELETE_ABRP_TOKEN", null, "Failed to delete ABRP token via IPC");
            return true;
        }
        return false;
    }

    private static void handleSetConfig(OutputStream out, String body) throws Exception {
        try {
            JSONObject bodyJson = new JSONObject(body);
            JSONObject extraFields = new JSONObject();

            if (bodyJson.has("token")) {
                extraFields.put("token", bodyJson.getString("token"));
            }
            if (bodyJson.has("enabled")) {
                extraFields.put("enabled", bodyJson.getBoolean("enabled"));
            }
            if (bodyJson.has("car_model")) {
                extraFields.put("car_model", bodyJson.getString("car_model"));
            }
            // Data-saving + app-gate options
            if (bodyJson.has("change_only")) extraFields.put("change_only", bodyJson.getBoolean("change_only"));
            if (bodyJson.has("min_interval_seconds")) extraFields.put("min_interval_seconds", bodyJson.getInt("min_interval_seconds"));
            if (bodyJson.has("max_interval_seconds")) extraFields.put("max_interval_seconds", bodyJson.getInt("max_interval_seconds"));
            if (bodyJson.has("gate_on_app")) extraFields.put("gate_on_app", bodyJson.getBoolean("gate_on_app"));
            if (bodyJson.has("app_package")) extraFields.put("app_package", bodyJson.getString("app_package"));
            if (bodyJson.has("app_active_mode")) extraFields.put("app_active_mode", bodyJson.getString("app_active_mode"));
            if (bodyJson.has("app_grace_seconds")) extraFields.put("app_grace_seconds", bodyJson.getInt("app_grace_seconds"));

            ipc.send(out, "SET_ABRP_CONFIG", extraFields, "Failed to set ABRP config via IPC");
        } catch (Exception e) {
            logger.error(TAG + ": Error parsing ABRP config request: " + e.getMessage());
            HttpResponse.sendJsonError(out, Messages.get("errors.invalid_request_body_with_detail", e.getMessage()));
        }
    }
}
