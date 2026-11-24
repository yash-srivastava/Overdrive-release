package com.overdrive.app.server;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * API handler for the Network &amp; Hotspot screen.
 *
 * The daemon runs in a separate process from {@code HotspotManager} (which owns
 * the radio in the app process), so writes are persisted here and the manager
 * picks them up from the unified config. Actuation requests are forwarded to
 * the app process, which is the only one that can drive the tethering binder.
 *
 * Routes use exact {@code path.equals} so a suffix can never shadow a sibling.
 *
 * Endpoints:
 * - GET  /api/hotspot          - status + settings snapshot
 * - POST /api/hotspot/enable   - request hotspot on
 * - POST /api/hotspot/disable  - request hotspot off
 * - POST /api/hotspot/settings - persist settings (ssid/password/cap/toggles)
 * - POST /api/hotspot/reset-usage - zero the cumulative data counter
 */
public class HotspotApiHandler {

    private static final String TAG = "HotspotApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String APP_PACKAGE = "com.overdrive.app";
    private static final String BRIDGE_SERVICE =
            APP_PACKAGE + "/.services.HotspotBridgeService";

    public static boolean handle(String method, String path, String body, OutputStream out)
            throws Exception {

        if (path.equals("/api/hotspot") && method.equals("GET")) {
            return handleStatus(out);
        }
        if (path.equals("/api/hotspot/enable") && method.equals("POST")) {
            return handleToggle(true, out);
        }
        if (path.equals("/api/hotspot/disable") && method.equals("POST")) {
            return handleToggle(false, out);
        }
        if (path.equals("/api/hotspot/settings") && method.equals("POST")) {
            return handleSettings(body, out);
        }
        if (path.equals("/api/hotspot/reset-usage") && method.equals("POST")) {
            return handleResetUsage(out);
        }
        return false;
    }

    /**
     * Status is assembled from the persisted settings plus the observed-state
     * section the app-process manager publishes; the daemon never touches the
     * radio itself.
     */
    /** Read a system property; empty string when unavailable. */
    private static String readProp(String name) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String value = (String) properties
                    .getMethod("get", String.class, String.class)
                    .invoke(null, name, "");
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Throwable ignored) {
            // Hidden API access varies by firmware; use the native binary below.
        }

        java.lang.Process p = null;
        try {
            p = new ProcessBuilder("/system/bin/getprop", name)
                    .redirectErrorStream(true)
                    .start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            p.waitFor();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Read the framework's saved SoftAP config as the shell-UID fallback. */
    private static String[] readFrameworkCredentials() {
        String[] out = {"", ""};
        try {
            android.content.Context context =
                    com.overdrive.app.daemon.CameraDaemon.getAppContext();
            android.net.wifi.WifiManager manager = context == null ? null
                    : (android.net.wifi.WifiManager) context.getSystemService(
                            android.content.Context.WIFI_SERVICE);
            if (manager == null) return out;

            java.lang.reflect.Method getter =
                    manager.getClass().getMethod("getWifiApConfiguration");
            android.net.wifi.WifiConfiguration config =
                    (android.net.wifi.WifiConfiguration) getter.invoke(manager);
            if (config == null) return out;

            out[0] = cleanCredential(config.SSID);
            out[1] = cleanCredential(config.preSharedKey);
        } catch (Throwable ignored) {
            // Some OEM builds deny even the shell UID; properties remain authoritative.
        }
        return out;
    }

    private static String cleanCredential(String value) {
        if (value == null) return "";
        String clean = value.trim();
        if ("*".equals(clean)) return "";
        if (clean.length() >= 2 && clean.startsWith("\"") && clean.endsWith("\"")) {
            return clean.substring(1, clean.length() - 1);
        }
        return clean;
    }

    private static boolean handleStatus(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            UnifiedConfigManager.forceReload();
            JSONObject cfg = UnifiedConfigManager.getHotspot();
            JSONObject state = UnifiedConfigManager.getHotspotState();

            int apState = state.optInt("apState", 11);
            long startedAt = state.optLong("startedAt", 0L);

            JSONObject status = new JSONObject();
            status.put("apState", apState);
            status.put("enabled", apState == 13);
            status.put("transitioning", apState == 12);
            status.put("iface", state.optString("iface", ""));
            status.put("uptimeSeconds",
                    startedAt > 0L ? (System.currentTimeMillis() - startedAt) / 1000L : 0L);
            status.put("rxBytes", state.optLong("rxBytes", 0L));
            status.put("txBytes", state.optLong("txBytes", 0L));
            status.put("sessionBytes", state.optLong("sessionBytes", 0L));
            status.put("clientCount", state.optInt("clients", 0));
            org.json.JSONArray roster = state.optJSONArray("clientList");
            status.put("clients", roster != null ? roster : new org.json.JSONArray());
            status.put("lastError", state.optString("lastError", ""));
            status.put("stateAgeSeconds", state.optLong("updatedAt", 0L) > 0L
                    ? (System.currentTimeMillis() - state.optLong("updatedAt", 0L)) / 1000L
                    : -1L);

            // Vehicle-owned credentials, mirrored by the app process; the persisted
            // ssid is only a request and is normally empty on this firmware.
            String activeSsid = state.optString("activeSsid", "");
            String activePassword = state.optString("activePassword", "");
            // The app process publishes these, but it only runs once the Network
            // screen is opened or the hotspot is used. Read the OEM properties
            // directly as a fallback so the web page can always show what to join
            // (the daemon is UID 2000, so getprop is available to it).
            if (activeSsid.isEmpty()) activeSsid = readProp("persist.sys.ap.ssid");
            if (activePassword.isEmpty()) activePassword = readProp("persist.sys.ap.password");
            if (activeSsid.isEmpty() || activePassword.isEmpty()) {
                String[] frameworkCredentials = readFrameworkCredentials();
                if (activeSsid.isEmpty()) activeSsid = frameworkCredentials[0];
                if (activePassword.isEmpty()) activePassword = frameworkCredentials[1];
            }
            // Live value wins: the persisted `ssid` is only what was requested and is
            // never applied on this firmware, so preferring it showed a stale name.
            status.put("ssid", !activeSsid.isEmpty() ? activeSsid : cfg.optString("ssid", ""));
            status.put("activeSsid", activeSsid);
            status.put("activePassword", activePassword);
            status.put("hasPassword", !activePassword.isEmpty()
                    || cfg.optString("password", "").length() > 0);
            status.put("dataCapMb", cfg.optLong("dataCapMb", 0L));
            status.put("dataUsedBytes", cfg.optLong("dataUsedBytes", 0L));
            status.put("proxySystemWide", cfg.optBoolean("proxySystemWide", false));
            status.put("proxyForClients", cfg.optBoolean("proxyForClients", false));
            status.put("clientTunnel", cfg.optBoolean("clientTunnel", false));
            status.put("relayPort", com.overdrive.app.network.CellularRelay.PORT);
            status.put("clientTunnelPort",
                    com.overdrive.app.daemon.proxy.ProxyConfiguration.CLIENT_TUNNEL_PORT);
            status.put("autoStartBoot", cfg.optBoolean("autoStartBoot", false));
            status.put("keepAlive", cfg.optBoolean("keepAlive", false));
            status.put("warnAck", cfg.optBoolean("warnAck", false));
            // Live value mirrored by the app process; the constant is only a fallback
            // for a state section written before the gateway was published.
            String gateway = state.optString("gateway", "");
            status.put("gateway", gateway.isEmpty() ? "192.168.43.1" : gateway);
            status.put("proxyPort", 8119);

            resp.put("success", true);
            resp.put("status", status);
        } catch (Throwable t) {
            logger.error("status failed", t);
            resp.put("success", false);
            resp.put("error", String.valueOf(t.getMessage()));
        }
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * Persist the requested state and hand actuation to the app process. The
     * request is acknowledged as "requested" (not "done") because the radio
     * transition is asynchronous and observable via GET /api/hotspot.
     */
    private static boolean handleToggle(boolean enable, OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            UnifiedConfigManager.updateHotspot(java.util.Collections.singletonMap(
                    "requested", enable ? "on" : "off"));
            boolean dispatched = dispatchToApp(enable ? "enable" : "disable");
            resp.put("success", true);
            resp.put("requested", enable);
            resp.put("dispatched", dispatched);
        } catch (Throwable t) {
            logger.error("toggle failed", t);
            resp.put("success", false);
            resp.put("error", String.valueOf(t.getMessage()));
        }
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    private static boolean handleSettings(String body, OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            JSONObject in = (body == null || body.isEmpty()) ? new JSONObject() : new JSONObject(body);
            java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();

            if (in.has("ssid")) {
                String ssid = in.optString("ssid", "").trim();
                if (ssid.length() > 32) {
                    throw new IllegalArgumentException("network name is limited to 32 characters");
                }
                values.put("ssid", ssid);
            }
            if (in.has("password")) {
                String pw = in.optString("password", "");
                // WPA2-PSK range. An empty string clears it back to the AP default.
                if (pw.length() > 0 && (pw.length() < 8 || pw.length() > 63)) {
                    throw new IllegalArgumentException("password must be 8-63 characters");
                }
                values.put("password", pw);
            }
            if (in.has("dataCapMb")) {
                long cap = in.optLong("dataCapMb", 0L);
                if (cap < 0L) cap = 0L;
                values.put("dataCapMb", cap);
            }
            if (in.has("proxySystemWide")) values.put("proxySystemWide", in.optBoolean("proxySystemWide", false));
            if (in.has("proxyForClients")) values.put("proxyForClients", in.optBoolean("proxyForClients", false));
            if (in.has("clientTunnel")) values.put("clientTunnel", in.optBoolean("clientTunnel", false));
            if (in.has("autoStartBoot")) values.put("autoStartBoot", in.optBoolean("autoStartBoot", false));
            if (in.has("keepAlive")) values.put("keepAlive", in.optBoolean("keepAlive", false));
            if (in.has("warnAck")) values.put("warnAck", in.optBoolean("warnAck", false));

            if (!values.isEmpty()) {
                UnifiedConfigManager.updateHotspot(values);
                // The manager owns the side effects (AP config push, proxy
                // settings) so tell it which keys moved.
                dispatchToApp("settings");
            }
            resp.put("success", true);
            resp.put("applied", values.keySet().size());
        } catch (IllegalArgumentException bad) {
            resp.put("success", false);
            resp.put("error", bad.getMessage());
        } catch (Throwable t) {
            logger.error("settings failed", t);
            resp.put("success", false);
            resp.put("error", String.valueOf(t.getMessage()));
        }
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    private static boolean handleResetUsage(OutputStream out) throws Exception {
        JSONObject resp = new JSONObject();
        try {
            UnifiedConfigManager.updateHotspot(
                    java.util.Collections.singletonMap("dataUsedBytes", 0L));
            dispatchToApp("reset-usage");
            resp.put("success", true);
        } catch (Throwable t) {
            logger.error("reset-usage failed", t);
            resp.put("success", false);
            resp.put("error", String.valueOf(t.getMessage()));
        }
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    /**
     * Wake the app-process bridge service so HotspotManager applies the request.
     * Best-effort and bounded: a wedged {@code am} must not stall an HTTP worker.
     */
    private static boolean dispatchToApp(String action) {
        String cmd = "am start-foreground-service -n " + BRIDGE_SERVICE
                + " --es action " + action;
        try {
            Process p = new ProcessBuilder("sh", "-c", com.overdrive.app.util.ScratchRoot.remap(cmd)).redirectErrorStream(true).start();
            java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[1024];
                try { while (is.read(buf) != -1) { /* discard */ } } catch (Throwable ignored) {}
            }, "hotspot-dispatch-drain");
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            drain.join(300);
            try { is.close(); } catch (Throwable ignored) {}
            return done;
        } catch (Throwable t) {
            logger.warn("dispatch to app failed: " + t.getMessage());
            return false;
        }
    }
}
