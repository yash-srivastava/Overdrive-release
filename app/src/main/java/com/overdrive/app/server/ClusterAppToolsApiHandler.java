package com.overdrive.app.server;

import com.overdrive.app.camera.dilink5.ClusterTransport;
import com.overdrive.app.launcher.ClusterAppDensity;
import com.overdrive.app.launcher.DiLink5ClusterCast;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.URLDecoder;

/** Uses HttpServer's normal authenticated routing; never a local-auth bypass. */
public final class ClusterAppToolsApiHandler {
    private ClusterAppToolsApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out)
            throws Exception {
        String clean = path.split("\\?", 2)[0];
        if (clean.equals("/api/cluster/apps/session") && method.equals("GET")) {
            HttpResponse.sendJson(out, sessionSnapshot().toString());
            return true;
        }
        if (!clean.equals("/api/cluster/apps/density")) return false;
        String pkg;
        if (method.equals("GET")) {
            pkg = queryPackage(path);
            if (!ClusterAppDensity.isValidPreferencePackage(pkg)) {
                HttpResponse.sendJsonError(out, "Invalid package");
                return true;
            }
            HttpResponse.sendJson(out, new JSONObject()
                    .put("success", true).put("package", pkg)
                    .put("dpi", ClusterAppDensity.preferredDpi(pkg))
                    .put("density", ClusterAppDensity.status())
                    .put("appliesOnNextCast", true).toString());
            return true;
        }
        if (method.equals("POST")) {
            JSONObject request = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            pkg = request.optString("package", "");
            Object raw = request.opt("dpi");
            // Reject fractional/coerced numbers rather than silently storing a different value.
            if (!(raw instanceof Number) || !Double.isFinite(((Number) raw).doubleValue())
                    || ((Number) raw).doubleValue() != ((Number) raw).intValue()) {
                HttpResponse.sendJsonError(out, "DPI must be 0 (unchanged) or an integer from 96 to 480");
                return true;
            }
            int dpi = ((Number) raw).intValue();
            boolean saved = ClusterAppDensity.savePreference(pkg, dpi);
            HttpResponse.sendJson(out, new JSONObject().put("success", saved)
                    .put("package", pkg).put("dpi", dpi).put("appliesOnNextCast", true)
                    .put("reason", saved ? "" : "Invalid density preference or save failed").toString());
            return true;
        }
        return false;
    }

    public static JSONObject sessionSnapshot() throws Exception {
        boolean di5 = ClusterTransport.usesHostedProjectionService();
        long generation = di5 ? DiLink5ClusterCast.currentSessionGeneration() : 0L;
        String pkg = di5 ? DiLink5ClusterCast.getCastPackage() : null;
        int id = di5 ? DiLink5ClusterCast.currentTargetDisplayId() : -1;
        boolean casting = di5 && generation > 0L && pkg != null && id > 0
                && DiLink5ClusterCast.isProjectionSourceActive()
                && generation == DiLink5ClusterCast.currentSessionGeneration()
                && id == DiLink5ClusterCast.currentTargetDisplayId()
                && pkg.equals(DiLink5ClusterCast.getCastPackage());
        return new JSONObject().put("success", true).put("di5", di5)
                .put("casting", casting).put("displayId", casting ? id : -1)
                .put("package", casting ? pkg : "").put("generation", casting ? generation : 0L);
    }

    private static String queryPackage(String path) throws Exception {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String field : path.substring(q + 1).split("&")) {
            String[] parts = field.split("=", 2);
            if (parts.length == 2 && parts[0].equals("package")) {
                return URLDecoder.decode(parts[1], "UTF-8");
            }
        }
        return "";
    }
}
