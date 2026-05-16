package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.surveillance.SafeLocation;
import com.overdrive.app.surveillance.SafeLocationManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.List;

/**
 * Safe Location API Handler — REST endpoints for geofence zone management.
 *
 * GET  /api/surveillance/safe-locations          — list all zones + status
 * POST /api/surveillance/safe-locations          — add a new zone
 * PUT  /api/surveillance/safe-locations?id=xxx   — update a zone
 * DELETE /api/surveillance/safe-locations?id=xxx — remove a zone
 * POST /api/surveillance/safe-locations/toggle   — toggle feature on/off
 */
public class SafeLocationApiHandler {

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        SafeLocationManager mgr = SafeLocationManager.getInstance();
        
        // Strip query params for path matching
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        // POST /api/surveillance/safe-locations/toggle
        if (pathOnly.equals("/api/surveillance/safe-locations/toggle") && method.equals("POST")) {
            JSONObject req = new JSONObject(body);
            boolean enabled = req.optBoolean("enabled", !mgr.isFeatureEnabled());
            mgr.setFeatureEnabled(enabled);
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("enabled", mgr.isFeatureEnabled());
            HttpResponse.sendJson(out, resp.toString());
            return true;
        }

        if (!pathOnly.equals("/api/surveillance/safe-locations")) return false;

        switch (method) {
            case "GET":
                HttpResponse.sendJson(out, mgr.getStatusJson().toString());
                return true;

            case "POST": {
                JSONObject req = new JSONObject(body);
                String name = req.optString("name", "Unnamed");
                double lat = req.optDouble("lat", 0);
                double lng = req.optDouble("lng", 0);
                int radiusM = req.optInt("radiusM", 150);

                if (lat == 0 && lng == 0) {
                    HttpResponse.sendJsonError(out, Messages.get("errors.safelocation_invalid_coordinates"));
                    return true;
                }

                SafeLocation zone = mgr.addZone(name, lat, lng, radiusM);
                if (zone == null) {
                    HttpResponse.sendJsonError(out, Messages.get("errors.safelocation_max_zones"));
                    return true;
                }

                JSONObject resp = new JSONObject();
                resp.put("success", true);
                resp.put("zone", zone.toJson());
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }

            case "PUT": {
                // Extract id from query string or body
                String id = extractQueryParam(path, "id");
                JSONObject req = new JSONObject(body);
                if (id == null) id = req.optString("id", null);

                if (id == null) {
                    HttpResponse.sendJsonError(out, Messages.get("errors.safelocation_missing_zone_id"));
                    return true;
                }

                boolean updated = mgr.updateZone(id, req);
                JSONObject resp = new JSONObject();
                resp.put("success", updated);
                if (!updated) resp.put("error", Messages.get("errors.safelocation_zone_not_found"));
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }

            case "DELETE": {
                String id = extractQueryParam(path, "id");
                JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
                if (id == null) id = req.optString("id", null);

                if (id == null) {
                    HttpResponse.sendJsonError(out, Messages.get("errors.safelocation_missing_zone_id"));
                    return true;
                }

                boolean removed = mgr.removeZone(id);
                JSONObject resp = new JSONObject();
                resp.put("success", removed);
                if (!removed) resp.put("error", Messages.get("errors.safelocation_zone_not_found"));
                HttpResponse.sendJson(out, resp.toString());
                return true;
            }

            default:
                return false;
        }
    }

    private static String extractQueryParam(String path, String param) {
        int qIdx = path.indexOf('?');
        if (qIdx < 0) return null;
        String query = path.substring(qIdx + 1);
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(param)) return kv[1];
        }
        return null;
    }
}
