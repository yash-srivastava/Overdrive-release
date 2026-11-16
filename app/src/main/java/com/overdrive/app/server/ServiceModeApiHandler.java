package com.overdrive.app.server;

import com.overdrive.app.launcher.AppLauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/** JWT-only HTTP API for the Android Settings and WebUI Service Mode page. */
public final class ServiceModeApiHandler {
    private ServiceModeApiHandler() {}

    public static boolean handle(
            String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?")
                ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/service-mode/status") && "GET".equals(method)) {
            HttpResponse.sendJsonNoCors(out, ServiceModeManager.getStatus().toString());
            return true;
        }

        if (cleanPath.equals("/api/service-mode/apps") && "GET".equals(method)) {
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("apps", AppLauncher.listUserLaunchableApps());
            HttpResponse.sendJsonNoCors(out, response.toString());
            return true;
        }

        if (cleanPath.equals("/api/service-mode/start") && "POST".equals(method)) {
            try {
                JSONObject request = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
                String endDate = request.optString("endDate", "");
                boolean hideOverdrive = request.optBoolean("hideOverdrive", true);
                JSONArray packages = request.optJSONArray("hiddenPackages");
                JSONArray features = request.optJSONArray("disabledFeatures");
                JSONObject response = ServiceModeManager.startServiceMode(
                        endDate, hideOverdrive, packages, features);
                HttpResponse.sendJsonNoCors(
                        out, response.optBoolean("success", false) ? 200 : 400,
                        response.toString());
            } catch (IllegalArgumentException invalid) {
                HttpResponse.sendJsonNoCors(out, 400, new JSONObject()
                        .put("success", false)
                        .put("error", invalid.getMessage() == null
                                ? "Invalid Service Mode request." : invalid.getMessage())
                        .toString());
            } catch (Throwable error) {
                HttpResponse.sendJsonNoCors(out, 503, new JSONObject()
                        .put("success", false)
                        .put("error", error.getMessage() == null
                                ? "Could not start Service Mode." : error.getMessage())
                        .toString());
            }
            return true;
        }

        if (cleanPath.equals("/api/service-mode/end") && "POST".equals(method)) {
            try {
                JSONObject response = ServiceModeManager.endServiceMode();
                HttpResponse.sendJsonNoCors(
                        out, response.optBoolean("success", false) ? 200 : 503,
                        response.toString());
            } catch (Throwable error) {
                HttpResponse.sendJsonNoCors(out, 503, new JSONObject()
                        .put("success", false)
                        .put("error", error.getMessage() == null
                                ? "Could not restore Service Mode yet." : error.getMessage())
                        .toString());
            }
            return true;
        }

        if (cleanPath.startsWith("/api/service-mode/")) {
            HttpResponse.sendJsonNoCors(out, 404, new JSONObject()
                    .put("success", false)
                    .put("error", "Unknown Service Mode route.")
                    .toString());
            return true;
        }
        return false;
    }
}
