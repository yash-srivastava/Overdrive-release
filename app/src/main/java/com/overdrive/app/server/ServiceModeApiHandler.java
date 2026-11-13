package com.overdrive.app.server;

import com.overdrive.app.launcher.AppLauncher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/** Authenticated HTTP API for the shared Android/WebUI Service Mode page. */
public final class ServiceModeApiHandler {
    private ServiceModeApiHandler() {}

    public static boolean handle(
            String method, String path, String body, OutputStream out) throws Exception {
        String cleanPath = path.contains("?")
                ? path.substring(0, path.indexOf("?")) : path;

        if (cleanPath.equals("/api/service-mode/status") && "GET".equals(method)) {
            HttpResponse.sendJson(out, ServiceModeManager.getStatus().toString());
            return true;
        }

        if (cleanPath.equals("/api/service-mode/apps") && "GET".equals(method)) {
            JSONObject response = new JSONObject();
            response.put("success", true);
            response.put("apps", AppLauncher.listUserLaunchableApps());
            HttpResponse.sendJson(out, response.toString());
            return true;
        }

        if (cleanPath.equals("/api/service-mode/start") && "POST".equals(method)) {
            try {
                JSONObject request = new JSONObject(body == null ? "{}" : body);
                String endDate = request.optString("endDate", "");
                boolean hideOverdrive = request.optBoolean("hideOverdrive", true);
                JSONArray packages = request.optJSONArray("hiddenPackages");
                JSONArray features = request.optJSONArray("disabledFeatures");
                JSONObject response = ServiceModeManager.startServiceMode(
                        endDate, hideOverdrive, packages, features);
                HttpResponse.sendJson(out, response.toString());
            } catch (Throwable t) {
                JSONObject response = new JSONObject();
                response.put("success", false);
                response.put("error", t.getMessage() == null
                        ? "Could not start Service Mode." : t.getMessage());
                HttpResponse.sendJson(out, response.toString());
            }
            return true;
        }

        if (cleanPath.equals("/api/service-mode/end") && "POST".equals(method)) {
            try {
                HttpResponse.sendJson(out, ServiceModeManager.endServiceMode().toString());
            } catch (Throwable t) {
                JSONObject response = new JSONObject();
                response.put("success", false);
                response.put("error", t.getMessage() == null
                        ? "Could not end Service Mode." : t.getMessage());
                HttpResponse.sendJson(out, response.toString());
            }
            return true;
        }

        return false;
    }
}
