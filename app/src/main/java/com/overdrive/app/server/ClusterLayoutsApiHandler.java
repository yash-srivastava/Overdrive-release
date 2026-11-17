package com.overdrive.app.server;

import com.overdrive.app.launcher.ClusterLayouts;
import com.overdrive.app.launcher.layout.ClusterLayoutStore;
import com.overdrive.app.launcher.layout.LayoutPreset;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.URLDecoder;

/** Authenticated by HttpServer before dispatch; accepts saved definitions, never shell commands. */
public final class ClusterLayoutsApiHandler {
    private ClusterLayoutsApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String clean = path.split("\\?", 2)[0];
        if (clean.equals("/api/cluster/layouts/status") && method.equals("GET")) {
            HttpResponse.sendJson(out, ClusterLayouts.status().toString());
            return true;
        }
        if (clean.equals("/api/cluster/layouts") && method.equals("GET")) {
            JSONObject status = ClusterLayouts.status();
            status.put("success", true).put("presets", ClusterLayoutStore.jsonList());
            HttpResponse.sendJson(out, status.toString());
            return true;
        }
        if (clean.equals("/api/cluster/layouts/stop") && method.equals("POST")) {
            boolean completed = ClusterLayouts.stop();
            HttpResponse.sendJson(out, ClusterLayouts.status().put("success", true)
                    .put("accepted", true).put("completed", completed).toString());
            return true;
        }
        if (clean.equals("/api/cluster/layouts/presets") && method.equals("DELETE")) {
            String id = query(path, "id");
            if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
                HttpResponse.sendJsonError(out, "Invalid layout ID");
            } else {
                HttpResponse.sendJson(out, new JSONObject().put("success", ClusterLayoutStore.delete(id)).toString());
            }
            return true;
        }
        if (!method.equals("POST") || (!clean.equals("/api/cluster/layouts/start")
                && !clean.equals("/api/cluster/layouts/presets"))) return false;
        try {
            if (body == null || body.length() > 16_384) throw new IllegalArgumentException("Invalid layout request");
            JSONObject request = new JSONObject(body);
            if (clean.endsWith("/presets")) {
                LayoutPreset preset = ClusterLayoutStore.fromJson(request);
                boolean saved = ClusterLayoutStore.save(preset);
                HttpResponse.sendJson(out, new JSONObject().put("success", saved)
                        .put("reason", saved ? "" : "Could not save the layout; at most 16 presets are supported")
                        .put("preset", ClusterLayoutStore.toJson(preset)).toString());
            } else {
                Object rawId = request.opt("presetId");
                LayoutPreset preset = rawId instanceof String ? ClusterLayoutStore.find((String) rawId) : null;
                if (preset == null) throw new IllegalArgumentException("Choose a saved layout");
                boolean accepted = ClusterLayouts.start(preset);
                if (!accepted) com.overdrive.app.daemon.CameraDaemon.log(
                        "Cluster layouts: start rejected reason=" + ClusterLayouts.status().optString("reason"));
                HttpResponse.sendJson(out, ClusterLayouts.status().put("success", accepted)
                        .put("accepted", accepted).put("physicalRenderingVerified", false).toString());
            }
        } catch (Exception invalid) {
            HttpResponse.sendJsonError(out, invalid.getMessage() == null ? "Invalid layout request" : invalid.getMessage());
        }
        return true;
    }

    private static String query(String path, String key) throws Exception {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String field : path.substring(q + 1).split("&")) {
            String[] pair = field.split("=", 2);
            if (pair.length == 2 && pair[0].equals(key)) return URLDecoder.decode(pair[1], "UTF-8");
        }
        return "";
    }
}
