package com.overdrive.app.server;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.customlayouts.daemon.feed.CustomLayoutsFeed;
import com.overdrive.app.navmap.cluster.ClusterGuidanceDaemon;
import com.overdrive.app.navmap.cluster.ClusterGuidanceFrame;

import org.json.JSONObject;
import java.io.OutputStream;

/** Auth is enforced by HttpServer before these routes are dispatched. */
public final class ClusterGuidanceApiHandler {
    private ClusterGuidanceApiHandler() {}
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String route = path.split("\\?", 2)[0];
        if (route.equals("/api/cluster/guidance/settings") && method.equals("POST")) {
            JSONObject input = new JSONObject(body), updates = new JSONObject();
            if (input.has("enabled")) updates.put("clusterGuidanceEnabled", input.getBoolean("enabled"));
            if (input.has("di5StripEnabled")) updates.put("clusterGuidanceDi5StripEnabled", input.getBoolean("di5StripEnabled"));
            if (updates.length() == 0) { HttpResponse.sendError(out, 400, "missing guidance setting"); return true; }
            if (!UnifiedConfigManager.updateSection("projection", updates)) {
                HttpResponse.sendError(out, 503, "guidance setting could not be saved"); return true;
            }
            ClusterGuidanceDaemon.settingsChanged();
            HttpResponse.sendJson(out, ClusterGuidanceDaemon.status().toString());
            return true;
        }
        if (!route.equals("/api/cluster/guidance")) return false;
        if (method.equals("GET")) {
            HttpResponse.sendJson(out, ClusterGuidanceDaemon.status().toString());
        } else if (method.equals("POST") || method.equals("DELETE")) {
            try {
                JSONObject input = new JSONObject(body);
                String source = input.getString("source");
                long sequence = input.getLong("sequence");
                if (!ClusterGuidanceFrame.validSource(source) || sequence <= 0)
                    throw new IllegalArgumentException("invalid source or sequence");
                boolean accepted = true;
                if (method.equals("DELETE") || input.optBoolean("clear", false)) {
                    ClusterGuidanceDaemon.clear(source, sequence);
                    CustomLayoutsFeed.onGuidanceClear(source, sequence);
                } else {
                    ClusterGuidanceFrame frame = new ClusterGuidanceFrame(source,
                            input.getInt("maneuverType"), input.getInt("distanceMeters"),
                            input.optString("roadName", ""), input.optInt("remainingDistanceMeters", -1),
                            input.optInt("remainingTimeSeconds", -1), input.optInt("roundaboutExit", 0),
                            input.optInt("canIconOverride", 0));
                    accepted = ClusterGuidanceDaemon.submit(frame, sequence);
                    // Custom Layouts draws the turn itself, whether or not the vendor cluster takes it.
                    CustomLayoutsFeed.onGuidance(frame, sequence, input);
                }
                HttpResponse.sendJson(out, new JSONObject().put("accepted", accepted).toString());
            } catch (IllegalArgumentException | org.json.JSONException invalid) {
                HttpResponse.sendError(out, 400, "invalid guidance frame");
            }
        } else HttpResponse.sendError(out, 405, "Method Not Allowed");
        return true;
    }
}
