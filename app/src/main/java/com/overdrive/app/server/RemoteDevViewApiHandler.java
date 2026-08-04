package com.overdrive.app.server;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authenticated HTTP surface for Remote Overdrive Dev View.
 *
 * HttpServer invokes this only after AuthMiddleware. A second, short-lived
 * in-memory session capability is required on every frame/input request so a
 * stale browser tab cannot retain control indefinitely.
 */
public final class RemoteDevViewApiHandler {
    private static final RemoteDevViewSessionManager SESSIONS =
        new RemoteDevViewSessionManager();
    private static final RemoteDevViewBridgeClient BRIDGE =
        new RemoteDevViewBridgeClient();

    private RemoteDevViewApiHandler() {}

    /** Package-private hooks used only by the authenticated WebSocket path. */
    static boolean validateStreamSession(String session) {
        return SESSIONS.validateAndTouch(session) != null;
    }

    static RemoteDevViewBridgeClient.Response captureStreamFrame(int maxWidth, int quality) {
        try {
            return BRIDGE.send(new JSONObject()
                .put("command", "capture")
                .put("maxWidth", maxWidth)
                .put("quality", quality));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean handle(String method, String path, String body, OutputStream out)
            throws Exception {
        if (path.equals("/api/dev-view/session") && method.equals("POST")) {
            JSONObject request = parseBody(body);
            if (!"I UNDERSTAND".equals(request.optString("confirm", ""))) {
                HttpResponse.sendJson(out, 400, new JSONObject()
                    .put("success", false)
                    .put("error", "Explicit confirmation is required")
                    .toString());
                return true;
            }
            if (!BRIDGE.start(true)) {
                unavailable(out, "App-process bridge could not be started");
                return true;
            }
            RemoteDevViewBridgeClient.Response ready = BRIDGE.awaitReady();
            if (ready == null) {
                unavailable(out, "App-process bridge did not become ready");
                return true;
            }
            RemoteDevViewSessionManager.Session session = SESSIONS.start();
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("session", session.token);
            response.put("idleTimeoutMs", RemoteDevViewSessionManager.IDLE_TIMEOUT_MS);
            response.put("maxLifetimeMs", RemoteDevViewSessionManager.MAX_LIFETIME_MS);
            response.put("activityReady", ready.metadata.optBoolean("success", false));
            response.put("activity", ready.metadata.opt("activity"));
            response.put("physicalDisplayChanged", false);
            HttpResponse.sendJson(out, response.toString());
            return true;
        }

        if (path.equals("/api/dev-view/session") && method.equals("DELETE")) {
            JSONObject request = parseBody(body);
            if (!SESSIONS.end(request.optString("session", ""))) {
                invalidSession(out);
                return true;
            }
            BRIDGE.shutdown();
            HttpResponse.sendJsonSuccess(out);
            return true;
        }

        if (path.equals("/api/dev-view/status") && method.equals("POST")) {
            JSONObject request = requireSession(body, out);
            if (request == null) return true;
            RemoteDevViewBridgeClient.Response bridgeResponse = BRIDGE.send(
                new JSONObject().put("command", "status"));
            sendBridgeJson(out, bridgeResponse);
            return true;
        }

        if (path.equals("/api/dev-view/frame") && method.equals("POST")) {
            JSONObject request = requireSession(body, out);
            if (request == null) return true;
            JSONObject bridgeRequest = new JSONObject();
            bridgeRequest.put("command", "capture");
            bridgeRequest.put("maxWidth", request.optInt("maxWidth", 1280));
            bridgeRequest.put("quality", request.optInt("quality", 72));
            RemoteDevViewBridgeClient.Response bridgeResponse = BRIDGE.send(bridgeRequest);
            if (bridgeResponse == null) {
                unavailable(out, "App-process bridge is unavailable");
                return true;
            }
            JSONObject metadata = bridgeResponse.metadata;
            if (!metadata.optBoolean("success", false) || bridgeResponse.payload.length == 0) {
                HttpResponse.sendJson(out, 503, metadata.toString());
                return true;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Overdrive-Width", Integer.toString(metadata.optInt("width", 0)));
            headers.put("X-Overdrive-Height", Integer.toString(metadata.optInt("height", 0)));
            headers.put("X-Overdrive-PixelCopy-Code",
                Integer.toString(metadata.optInt("pixelCopyCode", -1)));
            headers.put("X-Overdrive-PixelCopy-Result",
                metadata.optString("pixelCopyResult", "UNKNOWN"));
            headers.put("X-Overdrive-Activity", metadata.optString("activity", ""));
            HttpResponse.sendBinaryNoStore(out, "image/jpeg", bridgeResponse.payload, headers);
            return true;
        }

        if (path.equals("/api/dev-view/input") && method.equals("POST")) {
            JSONObject request = requireSession(body, out);
            if (request == null) return true;
            String type = request.optString("type", "");
            JSONObject bridgeRequest = new JSONObject();
            if ("touch".equals(type)) {
                bridgeRequest.put("command", "touch");
                bridgeRequest.put("phase", request.optString("phase", ""));
                bridgeRequest.put("x", request.optDouble("x", Double.NaN));
                bridgeRequest.put("y", request.optDouble("y", Double.NaN));
            } else if ("key".equals(type)) {
                bridgeRequest.put("command", "key");
                bridgeRequest.put("key", request.optString("key", ""));
            } else if ("text".equals(type)) {
                bridgeRequest.put("command", "text");
                bridgeRequest.put("text", request.optString("text", ""));
            } else {
                HttpResponse.sendJson(out, 400, new JSONObject()
                    .put("success", false).put("error", "Unknown input type").toString());
                return true;
            }
            sendBridgeJson(out, BRIDGE.send(bridgeRequest));
            return true;
        }

        if (path.startsWith("/api/dev-view/")) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }
        return false;
    }

    private static JSONObject requireSession(String body, OutputStream out) throws Exception {
        JSONObject request = parseBody(body);
        if (SESSIONS.validateAndTouch(request.optString("session", "")) == null) {
            invalidSession(out);
            return null;
        }
        return request;
    }

    private static JSONObject parseBody(String body) {
        try { return new JSONObject(body == null || body.isEmpty() ? "{}" : body); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static void sendBridgeJson(OutputStream out,
                                       RemoteDevViewBridgeClient.Response response) throws Exception {
        if (response == null) {
            unavailable(out, "App-process bridge is unavailable");
        } else if (response.metadata.optBoolean("success", false)) {
            HttpResponse.sendJson(out, response.metadata.toString());
        } else {
            HttpResponse.sendJson(out, 409, response.metadata.toString());
        }
    }

    private static void invalidSession(OutputStream out) throws Exception {
        HttpResponse.sendJson(out, 403, new JSONObject()
            .put("success", false)
            .put("error", "Developer-view session is invalid or expired")
            .toString());
    }

    private static void unavailable(OutputStream out, String detail) throws Exception {
        HttpResponse.sendJson(out, 503, new JSONObject()
            .put("success", false).put("error", detail).toString());
    }
}
