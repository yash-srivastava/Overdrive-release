package com.overdrive.app.server;

import com.overdrive.app.byd.RadioControl;
import com.overdrive.app.monitor.BoundedShell;
import com.overdrive.app.monitor.NetworkMonitor;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * The car's Wi-Fi client radio for the web dashboard (not the hotspot): GET /api/network/wifi
 * reports on/off, POST {enabled} switches it through {@link RadioControl}, which also keeps the
 * Wi-Fi keep-alive in step so an "off" isn't turned back on.
 */
final class WifiClientApiHandler {

    private static final long SHELL_DEADLINE_MS = 1500L;

    private WifiClientApiHandler() {}

    static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (!"/api/network/wifi".equals(path)) return false;
        if ("GET".equals(method)) {
            NetworkMonitor.getNetworkInfo(); // Refresh stale cache before reading the network type.
            boolean connected = "wifi".equals(NetworkMonitor.getNetworkType());
            Boolean enabled = connected ? Boolean.TRUE : readWifiOn();
            HttpResponse.sendJsonNoCors(out, new JSONObject()
                    .put("success", true)
                    .put("connected", connected)
                    .put("enabled", enabled == null ? JSONObject.NULL : enabled).toString());
            return true;
        }
        if ("POST".equals(method)) {
            Object requested = null;
            try {
                requested = new JSONObject(body == null ? "" : body).opt("enabled");
            } catch (Exception ignored) {}
            if (!(requested instanceof Boolean)) {
                HttpResponse.sendJsonNoCors(out, 400,
                        "{\"success\":false,\"error\":\"enabled must be true or false\"}");
                return true;
            }
            boolean ok = RadioControl.set(RadioControl.Radio.WIFI, (Boolean) requested);
            HttpResponse.sendJsonNoCors(out, ok ? 200 : 500, new JSONObject()
                    .put("success", ok)
                    .put("enabled", requested).toString());
            return true;
        }
        HttpResponse.sendJsonNoCors(out, 405, "{\"success\":false,\"error\":\"Method not allowed\"}");
        return true;
    }

    /** settings global wifi_on: 1/2 on, 0 off; null when it can't be read. */
    private static Boolean readWifiOn() {
        BoundedShell.Result result = BoundedShell.run("settings get global wifi_on", SHELL_DEADLINE_MS);
        if (result == null || !result.ok() || result.output == null) return null;
        String value = result.output.trim();
        if ("1".equals(value) || "2".equals(value)) return Boolean.TRUE;
        if ("0".equals(value)) return Boolean.FALSE;
        return null;
    }
}
