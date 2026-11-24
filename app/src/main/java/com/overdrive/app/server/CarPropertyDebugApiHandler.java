package com.overdrive.app.server;

import com.overdrive.app.byd.CarPropertyBridge;
import com.overdrive.app.daemon.CameraDaemon;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Debug endpoints for {@link CarPropertyBridge} — read DiCarServer's
 * {@code ICarPropertyService} via the local {@code CarServiceProvider},
 * skipping the BYD cloud entirely.
 *
 * <p>Endpoints (all GET, JSON):
 * <ul>
 *   <li>{@code /api/debug/car-property/status} — bridge wiring + service alive</li>
 *   <li>{@code /api/debug/car-property/get?key=0xHHHHHHHH} — read any property</li>
 *   <li>{@code /api/debug/car-property/config?key=0xHHHHHHHH} — read property
 *       config (access flags + read/write permission strings) without a value
 *       round-trip; use this to learn what permission a property is gated on
 *       before attempting a write.</li>
 *   <li>{@code /api/debug/car-property/set?key=0xHHHHHHHH&value=N&confirm=YES}
 *       — write any int property. Will be rejected by DiCarServer if the
 *       property's writePermission is one our APK signature doesn't grant
 *       (e.g. {@code BYDAUTO_BODYWORK_SET}).</li>
 * </ul>
 *
 * <p>Write paths require {@code confirm=YES}.
 */
public final class CarPropertyDebugApiHandler {

    private static final String TAG = "CarPropertyDebug";

    private CarPropertyDebugApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (!"GET".equals(method)) {
            HttpResponse.sendError(out, 405, "Method Not Allowed");
            return true;
        }

        String pathOnly = path;
        Map<String, String> q = new LinkedHashMap<>();
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) {
            pathOnly = path.substring(0, qIdx);
            for (String pair : path.substring(qIdx + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) q.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }

        switch (pathOnly) {
            case "/api/debug/car-property/status":  return handleStatus(out);
            case "/api/debug/car-property/get":     return handleGet(out, q);
            case "/api/debug/car-property/config":  return handleConfig(out, q);
            case "/api/debug/car-property/set":     return handleSet(out, q);
            default:
                HttpResponse.sendError(out, 404, "Unknown car-property debug endpoint");
                return true;
        }
    }

    // ── Status ──

    private static boolean handleStatus(OutputStream out) throws Exception {
        JSONObject r = new JSONObject();
        CarPropertyBridge bridge = CarPropertyBridge.getInstance();
        r.put("bridgeAvailable", bridge != null);
        r.put("providerUri", "content://com.byd.car.server.provider.CarServiceProvider");
        r.put("serviceClass", "com.byd.car.property.ICarPropertyService");
        if (bridge == null) {
            r.put("error", "CarPropertyBridge.getInstance() returned null — Application context unavailable");
        } else {
            // Smoke-test the binder via a known-registered, harmless property.
            // 0x39400034 (LF door lock status) is registered when present and
            // returns STATUS_UNAVAILABLE (not SecurityException) when the body
            // bus is asleep — perfect smoke test.
            long t0 = System.nanoTime();
            CarPropertyBridge.ReadResult rr = bridge.readProperty("0x39400034");
            long latencyMs = (System.nanoTime() - t0) / 1_000_000;
            r.put("smokeTest", readResultJson(rr));
            r.put("smokeTestLatencyMs", latencyMs);
        }
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    // ── Generic property get/set/config ──

    private static boolean handleGet(OutputStream out, Map<String, String> q) throws Exception {
        String key = q.get("key");
        if (key == null || key.isEmpty()) {
            HttpResponse.sendJsonError(out, "Missing 'key' parameter (e.g. key=0x39400034)");
            return true;
        }
        CarPropertyBridge bridge = CarPropertyBridge.getInstance();
        if (bridge == null) {
            HttpResponse.sendJsonError(out, "CarPropertyBridge unavailable");
            return true;
        }
        long t0 = System.nanoTime();
        CarPropertyBridge.ReadResult rr = bridge.readProperty(key);
        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
        JSONObject r = new JSONObject();
        r.put("key", key);
        r.put("result", readResultJson(rr));
        r.put("latencyMs", latencyMs);
        HttpResponse.sendJson(out, r.toString());
        try { CameraDaemon.log(TAG + ": get " + key + " -> " + readResultJson(rr) + " in " + latencyMs + "ms"); } catch (Throwable ignore) {}
        return true;
    }

    private static boolean handleConfig(OutputStream out, Map<String, String> q) throws Exception {
        String key = q.get("key");
        if (key == null || key.isEmpty()) {
            HttpResponse.sendJsonError(out, "Missing 'key' parameter");
            return true;
        }
        CarPropertyBridge bridge = CarPropertyBridge.getInstance();
        if (bridge == null) { HttpResponse.sendJsonError(out, "CarPropertyBridge unavailable"); return true; }
        long t0 = System.nanoTime();
        CarPropertyBridge.ConfigResult cfg = bridge.readPropertyConfig(key);
        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
        JSONObject r = new JSONObject();
        r.put("key", key);
        r.put("config", configJson(cfg));
        r.put("latencyMs", latencyMs);
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    private static boolean handleSet(OutputStream out, Map<String, String> q) throws Exception {
        if (!StepUpGate.carOnly(out)) return true;
        String key = q.get("key");
        Integer value = parseIntParam(q, "value");
        if (key == null || key.isEmpty()) {
            HttpResponse.sendJsonError(out, "Missing 'key' parameter");
            return true;
        }
        if (value == null) {
            HttpResponse.sendJsonError(out, "Missing 'value' parameter (decimal or 0x-hex)");
            return true;
        }
        if (!"YES".equals(q.get("confirm"))) {
            HttpResponse.sendJsonError(out, "Refusing to write " + key + "=" + value + " without confirm=YES");
            return true;
        }
        CarPropertyBridge bridge = CarPropertyBridge.getInstance();
        if (bridge == null) {
            HttpResponse.sendJsonError(out, "CarPropertyBridge unavailable");
            return true;
        }
        long t0 = System.nanoTime();
        CarPropertyBridge.Result res = bridge.setIntProperty(key, value);
        long latencyMs = (System.nanoTime() - t0) / 1_000_000;
        JSONObject r = new JSONObject();
        r.put("key", key);
        r.put("value", value);
        r.put("result", resultJson(res));
        r.put("latencyMs", latencyMs);
        HttpResponse.sendJson(out, r.toString());
        try { CameraDaemon.log(TAG + ": set " + key + "=" + value + " -> " + resultJson(res) + " in " + latencyMs + "ms"); } catch (Throwable ignore) {}
        return true;
    }

    // ── helpers ──

    private static JSONObject readResultJson(CarPropertyBridge.ReadResult rr) throws Exception {
        JSONObject o = new JSONObject();
        if (rr == null) { o.put("success", false); o.put("error", "null"); return o; }
        o.put("success", rr.success);
        o.put("statusCode", rr.statusCode);
        o.put("statusHex", "0x" + String.format(Locale.ROOT, "%08X", rr.statusCode));
        if (rr.description != null && !rr.description.isEmpty()) o.put("description", rr.description);
        if (rr.intValue != null) o.put("intValue", rr.intValue.intValue());
        if (rr.stringValue != null) o.put("stringValue", rr.stringValue);
        if (rr.resultClass != null) o.put("resultClass", rr.resultClass);
        if (rr.error != null) o.put("error", rr.error);
        return o;
    }

    private static JSONObject configJson(CarPropertyBridge.ConfigResult cfg) throws Exception {
        JSONObject o = new JSONObject();
        if (cfg == null) { o.put("success", false); o.put("error", "null"); return o; }
        o.put("success", cfg.success);
        if (cfg.success) {
            o.put("access", cfg.access);
            o.put("accessLabel",
                    cfg.access == 1 ? "READ"
                  : cfg.access == 2 ? "WRITE"
                  : cfg.access == 3 ? "READ_WRITE" : "UNKNOWN");
            o.put("featureId", cfg.featureId);
            if (cfg.typeName != null) o.put("typeName", cfg.typeName);
            if (cfg.providerName != null) o.put("providerName", cfg.providerName);
            o.put("readPermission", cfg.readPermission == null ? "" : cfg.readPermission);
            o.put("writePermission", cfg.writePermission == null ? "" : cfg.writePermission);
        }
        if (cfg.error != null) o.put("error", cfg.error);
        return o;
    }

    private static JSONObject resultJson(CarPropertyBridge.Result res) throws Exception {
        JSONObject o = new JSONObject();
        if (res == null) { o.put("success", false); o.put("error", "null"); return o; }
        o.put("success", res.success);
        o.put("statusCode", res.statusCode);
        o.put("statusHex", "0x" + String.format(Locale.ROOT, "%08X", res.statusCode));
        if (res.description != null && !res.description.isEmpty()) o.put("description", res.description);
        if (res.error != null) o.put("error", res.error);
        return o;
    }

    private static Integer parseIntParam(Map<String, String> q, String name) {
        String s = q.get(name);
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return (int) Long.parseLong(s.substring(2), 16);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
