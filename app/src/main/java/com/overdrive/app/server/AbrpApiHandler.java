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

    /**
     * Handle ABRP API requests.
     * @return true if handled
     */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/abrp/config") && method.equals("GET")) {
            handleGetConfig(out);
            return true;
        }
        if (path.equals("/api/abrp/config") && method.equals("POST")) {
            handleSetConfig(out, body);
            return true;
        }
        if (path.equals("/api/abrp/status") && method.equals("GET")) {
            handleGetStatus(out);
            return true;
        }
        if (path.equals("/api/abrp/token") && method.equals("DELETE")) {
            handleDeleteToken(out);
            return true;
        }
        return false;
    }

    private static void handleGetConfig(OutputStream out) throws Exception {
        JSONObject ipcResponse = sendIpcCommand("GET_ABRP_CONFIG", null);
        if (ipcResponse != null) {
            HttpResponse.sendJson(out, ipcResponse.toString());
        } else {
            logger.warn(TAG + ": Failed to get ABRP config via IPC");
            HttpResponse.sendJsonError(out, Messages.get("errors.ipc_communication_failed"));
        }
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

            JSONObject ipcResponse = sendIpcCommand("SET_ABRP_CONFIG", extraFields);
            if (ipcResponse != null) {
                HttpResponse.sendJson(out, ipcResponse.toString());
            } else {
                logger.warn(TAG + ": Failed to set ABRP config via IPC");
                HttpResponse.sendJsonError(out, Messages.get("errors.ipc_communication_failed"));
            }
        } catch (Exception e) {
            logger.error(TAG + ": Error parsing ABRP config request: " + e.getMessage());
            HttpResponse.sendJsonError(out, Messages.get("errors.invalid_request_body_with_detail", e.getMessage()));
        }
    }

    private static void handleGetStatus(OutputStream out) throws Exception {
        JSONObject ipcResponse = sendIpcCommand("GET_ABRP_STATUS", null);
        if (ipcResponse != null) {
            HttpResponse.sendJson(out, ipcResponse.toString());
        } else {
            logger.warn(TAG + ": Failed to get ABRP status via IPC");
            HttpResponse.sendJsonError(out, Messages.get("errors.ipc_communication_failed"));
        }
    }

    private static void handleDeleteToken(OutputStream out) throws Exception {
        JSONObject ipcResponse = sendIpcCommand("DELETE_ABRP_TOKEN", null);
        if (ipcResponse != null) {
            HttpResponse.sendJson(out, ipcResponse.toString());
        } else {
            logger.warn(TAG + ": Failed to delete ABRP token via IPC");
            HttpResponse.sendJsonError(out, Messages.get("errors.ipc_communication_failed"));
        }
    }

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
            logger.error(TAG + ": IPC communication error: " + e.getMessage());
            return null;
        }
    }
}
