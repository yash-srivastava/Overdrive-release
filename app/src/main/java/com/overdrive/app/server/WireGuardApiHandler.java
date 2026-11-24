package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.wireguard.WireGuardConfig;
import com.overdrive.app.wireguard.WireGuardStore;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * WireGuard tunnel config API. Runs in the daemon process, which owns the
 * config directory. The private key is write-only: no response ever contains it.
 *
 * Endpoints:
 * - GET    /api/wireguard         → {configured, summary, running, status}
 * - POST   /api/wireguard/config  → store a config ({"config": "<wg-quick text>"})
 * - DELETE /api/wireguard/config  → remove the stored config
 * - POST   /api/wireguard/expose  → {"enabled": bool}, dashboard opt-in (applied on the next wgproxy start)
 *
 * A saved config is picked up by a running wgproxy on its own. Starting and
 * stopping the daemon stays with the app (Daemons screen).
 */
public class WireGuardApiHandler {

    private static final String TAG = "WireGuardApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** @return true if handled */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        int q = path.indexOf('?');
        String p = q >= 0 ? path.substring(0, q) : path;

        if (p.equals("/api/wireguard") && method.equals("GET")) {
            handleGet(out);
            return true;
        }
        if (p.equals("/api/wireguard/config") && method.equals("POST")) {
            // A re-pointed tunnel could carry the car's MQTT traffic, which never asks.
            if (!StepUpGate.requireBundle("config", "Change WireGuard", out)) return true;
            handleSave(out, body);
            return true;
        }
        if (p.equals("/api/wireguard/config") && method.equals("DELETE")) {
            if (!StepUpGate.requireBundle("config", "Change WireGuard", out)) return true;
            handleDelete(out);
            return true;
        }
        if (p.equals("/api/wireguard/expose") && method.equals("POST")) {
            if (!StepUpGate.requireBundle("config", "Change WireGuard", out)) return true;
            handleExpose(out, body);
            return true;
        }
        return false;
    }

    private static void handleExpose(OutputStream out, String body) throws Exception {
        boolean enabled;
        try {
            JSONObject json = new JSONObject(body);
            if (!(json.opt("enabled") instanceof Boolean)) throw new IllegalArgumentException();
            enabled = json.getBoolean("enabled");
        } catch (Exception e) {
            sendError(out, 400, Messages.get("errors.wireguard_body_required"));
            return;
        }
        try {
            WireGuardStore.setDashboardExposed(enabled);
            logger.info("WireGuard dashboard exposure " + (enabled ? "enabled" : "disabled"));
            HttpResponse.sendJsonNoCors(out, new JSONObject()
                    .put("success", true).put("expose_dashboard", enabled).toString());
        } catch (Exception e) {
            logger.error("WireGuard expose flag failed: " + e.getMessage());
            sendError(out, 500, Messages.get("errors.wireguard_save_failed", e.getMessage()));
        }
    }

    private static void handleGet(OutputStream out) throws Exception {
        WireGuardConfig.Summary summary = WireGuardStore.readSummary();
        JSONObject status = WireGuardStore.readStatus();
        JSONObject r = new JSONObject();
        r.put("configured", WireGuardStore.hasConfig());
        r.put("summary", summary != null ? summary.toJson() : JSONObject.NULL);
        r.put("running", WireGuardStore.isRunning());
        r.put("expose_dashboard", WireGuardStore.isDashboardExposed());
        r.put("status", status != null ? status : JSONObject.NULL);
        HttpResponse.sendJsonNoCors(out, r.toString());
    }

    private static void handleSave(OutputStream out, String body) throws Exception {
        String text;
        try {
            text = new JSONObject(body).optString("config", "");
        } catch (Exception e) {
            text = "";
        }
        if (text.isEmpty()) {
            sendError(out, 400, Messages.get("errors.wireguard_body_required"));
            return;
        }
        try {
            WireGuardConfig.Summary summary = WireGuardStore.save(text);
            logger.info("WireGuard config saved (" + summary.peerCount + " peer(s))");
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("summary", summary.toJson());
            HttpResponse.sendJsonNoCors(out, r.toString());
        } catch (IllegalArgumentException e) {
            // Validation message only; it never echoes config contents beyond a line number.
            sendError(out, 400, e.getMessage());
        } catch (Exception e) {
            logger.error("WireGuard config save failed: " + e.getMessage());
            sendError(out, 500, Messages.get("errors.wireguard_save_failed", e.getMessage()));
        }
    }

    private static void handleDelete(OutputStream out) throws Exception {
        if (WireGuardStore.delete()) {
            logger.info("WireGuard config deleted");
            HttpResponse.sendJsonNoCors(out, "{\"success\":true}");
        } else {
            sendError(out, 500, Messages.get("errors.wireguard_delete_failed"));
        }
    }

    private static void sendError(OutputStream out, int status, String message) throws Exception {
        JSONObject r = new JSONObject();
        r.put("success", false);
        r.put("error", message);
        HttpResponse.sendJsonNoCors(out, status, r.toString());
    }
}
