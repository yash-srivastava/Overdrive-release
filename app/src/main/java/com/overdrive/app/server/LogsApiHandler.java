package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogPaths;
import com.overdrive.app.logging.LogUploader;
import com.overdrive.app.updater.AppUpdater;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Diagnostics log API — lets the webapp upload a single daemon's log to the
 * Cloudflare Worker and show the customer a short retrieval code.
 *
 * Only useful in the braveheart build: {@link LogUploader#isUploadConfigured()}
 * is false on plain release/debug (no Worker URL baked in), so the web UI hides
 * the feature and this endpoint reports unavailable.
 *
 * Endpoints:
 *   GET  /api/logs/available           → {available, daemons:[key,…], descriptions:{key:{name,component,sendWhen}}}
 *   POST /api/logs/upload?daemon=camera → {code} | {error}
 *   GET|POST /api/logs/level            → {minLevel, savedLevel} (also /api/settings/logging)
 *
 * Public-mode is hard-blocked for /upload (mirrors /api/update/install): a
 * tunnel visitor must not be able to exfiltrate logs. /available is read-only.
 * Both remain behind AuthMiddleware.
 */
public class LogsApiHandler {

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.startsWith("/api/logs/available") && method.equals("GET")) {
            handleAvailable(out);
            return true;
        }
        if (path.startsWith("/api/logs/upload") && method.equals("POST")) {
            handleUpload(path, out);
            return true;
        }
        if (path.split("\\?", 2)[0].equals("/api/logs/level")) {
            return handleLevel(method, body, out);
        }
        return false;
    }

    /**
     * GET/POST /api/logs/level, mirrored at /api/settings/logging: the daemons' minimum log level.
     * POST saves it; this daemon applies it at once through the config listener, the other daemons
     * on their next start. POST is blocked in public mode, like /upload.
     */
    static boolean handleLevel(String method, String body, OutputStream out) throws Exception {
        if (method.equals("GET")) {
            String saved = com.overdrive.app.config.UnifiedConfigManager.getPersistedLogLevel();
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("minLevel", com.overdrive.app.logging.DaemonLogger.getConfig().minLevel.name());
            r.put("savedLevel", saved != null ? saved : JSONObject.NULL);
            r.put("availableLevels", new JSONArray().put("DEBUG").put("INFO").put("WARN").put("ERROR"));
            HttpResponse.sendJson(out, 200, r.toString());
            return true;
        }
        if (method.equals("POST")) {
            if (CameraDaemon.isPublicMode()) {
                HttpResponse.sendJsonError(out, Messages.get("errors.update_disabled_in_public_mode"));
                return true;
            }
            JSONObject req;
            try {
                req = new JSONObject(body == null ? "" : body);
            } catch (Exception e) {
                req = new JSONObject();
            }
            String level = com.overdrive.app.config.UnifiedConfigManager.canonicalLogLevel(
                    req.optString("minLevel", req.optString("level", "")));
            if (level == null) {
                HttpResponse.sendJson(out, 400, "{\"success\":false,\"error\":\"Invalid level. Expected DEBUG, INFO, WARN, or ERROR.\"}");
                return true;
            }
            boolean ok = com.overdrive.app.config.UnifiedConfigManager.setLogLevel(level);
            JSONObject r = new JSONObject();
            r.put("success", ok);
            r.put("minLevel", level);
            HttpResponse.sendJson(out, ok ? 200 : 500, r.toString());
            return true;
        }
        return false;
    }

    private static void handleAvailable(OutputStream out) throws Exception {
        JSONObject r = new JSONObject();
        try {
            r.put("available", LogUploader.isUploadConfigured());
            JSONArray arr = new JSONArray();
            JSONObject descriptions = new JSONObject();
            for (String k : DaemonLogPaths.keys()) {
                arr.put(k);
                DaemonLogPaths.LogDescription description = DaemonLogPaths.descriptionFor(k);
                if (description != null) {
                    descriptions.put(k, new JSONObject()
                            .put("name", description.name)
                            .put("component", description.component)
                            .put("sendWhen", description.sendWhen));
                }
            }
            r.put("daemons", arr);
            // Additive API field: older clients can continue consuming daemons[].
            r.put("descriptions", descriptions);
        } catch (Exception ignored) {}
        HttpResponse.sendJson(out, r.toString());
    }

    private static void handleUpload(String path, OutputStream out) throws Exception {
        // A tunnel visitor must not be able to pull logs off the head unit.
        if (CameraDaemon.isPublicMode()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_disabled_in_public_mode"));
            return;
        }
        if (!LogUploader.isUploadConfigured()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.logs_upload_unavailable"));
            return;
        }
        String daemon = queryParam(path, "daemon");
        String logPath = DaemonLogPaths.pathFor(daemon);
        if (logPath == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.logs_unknown_daemon", DaemonLogPaths.keyList()));
            return;
        }
        String version = AppUpdater.getDisplayVersionFromFile();
        LogUploader.Result res = LogUploader.upload(logPath, daemon, version);
        if (res.ok) {
            JSONObject r = new JSONObject();
            r.put("code", res.code);
            r.put("daemon", daemon);
            HttpResponse.sendJson(out, r.toString());
        } else {
            HttpResponse.sendJsonError(out, res.error != null ? res.error : "upload failed");
        }
    }

    /** Extract a query param value (URL-decoded), or null. */
    private static String queryParam(String path, String key) {
        if (path == null) return null;
        int q = path.indexOf('?');
        if (q < 0 || q == path.length() - 1) return null;
        for (String pair : path.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (k.equals(key)) {
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                try { return java.net.URLDecoder.decode(v, "UTF-8"); }
                catch (Exception e) { return v; }
            }
        }
        return null;
    }
}
