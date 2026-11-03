package com.overdrive.app.server;

import com.overdrive.app.notifications.CategoryRegistry;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.notifications.NotificationStore;
import com.overdrive.app.notifications.push.PushSubscription;
import com.overdrive.app.notifications.push.SubscriptionStore;
import com.overdrive.app.notifications.push.VapidKeyStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP routes for the Web Push subsystem.
 *
 * Endpoints:
 * <ul>
 *   <li>GET  /api/notifications/categories — registry JSON + VAPID public key</li>
 *   <li>POST /api/push/subscribe           — register a phone subscription</li>
 *   <li>POST /api/push/unsubscribe         — remove this device's subscription</li>
 *   <li>GET  /api/push/subscriptions       — list registered devices for settings UI</li>
 *   <li>POST /api/push/preferences         — update muted categories / quiet hours</li>
 *   <li>POST /api/push/test                — fire a test notification to the requester</li>
 *   <li>GET    /api/notifications/log         — paginated notification history (date/category/severity filters)</li>
 *   <li>DELETE /api/notifications/log/{id}    — delete one logged notification (POST .../delete fallback)</li>
 *   <li>POST   /api/notifications/log/bulk-delete — delete many by id array</li>
 *   <li>DELETE /api/notifications/log         — clear the whole log (POST .../clear fallback)</li>
 * </ul>
 *
 * <p>All routes require auth (handled by the caller before dispatch).
 */
public final class NotificationApiHandler {

    private static volatile CategoryRegistry registry;
    private static volatile SubscriptionStore subStore;
    private static volatile VapidKeyStore keyStore;
    private static volatile NotificationStore logStore;

    /** Max ids accepted in one bulk-delete request. */
    private static final int MAX_BULK_IDS = 1000;

    /** Wire the dependencies once at daemon startup. */
    public static void init(CategoryRegistry r, SubscriptionStore s, VapidKeyStore k) {
        registry = r;
        subStore = s;
        keyStore = k;
        logStore = NotificationStore.getInstance();
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (registry == null || subStore == null || keyStore == null) {
            HttpResponse.sendError(out, 503, "Notifications not initialized");
            return true;
        }

        // Notification-log routes. These carry a query string (list filters) or a
        // trailing id, so match on the query-stripped path and handle them before
        // the exact-equals push routes below.
        String logPath = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        if (logPath.equals("/api/notifications/log")) {
            if (method.equals("GET")) return listLog(path, out);
            if (method.equals("DELETE")) return clearLog(path, out);
            HttpResponse.sendError(out, 405, "method not allowed");
            return true;
        }
        if (logPath.equals("/api/notifications/log/clear")) {
            if (method.equals("POST")) return clearLog(path, out);
            HttpResponse.sendError(out, 405, "method not allowed");
            return true;
        }
        if (logPath.equals("/api/notifications/log/bulk-delete")) {
            if (method.equals("POST")) return bulkDeleteLog(body, out);
            HttpResponse.sendError(out, 405, "method not allowed");
            return true;
        }
        if (logPath.startsWith("/api/notifications/log/")) {
            String rest = logPath.substring("/api/notifications/log/".length());
            // POST /api/notifications/log/{id}/delete — DELETE fallback for the WebView.
            if (rest.endsWith("/delete") && method.equals("POST")) {
                return deleteLog(rest.substring(0, rest.length() - "/delete".length()), out);
            }
            if (method.equals("DELETE")) {
                return deleteLog(rest, out);
            }
            HttpResponse.sendError(out, 405, "method not allowed");
            return true;
        }

        if (path.equals("/api/notifications/categories") && method.equals("GET")) {
            return getCategories(out);
        }
        if (path.equals("/api/push/subscribe") && method.equals("POST")) {
            return subscribe(body, out);
        }
        if (path.equals("/api/push/unsubscribe") && method.equals("POST")) {
            return unsubscribe(body, out);
        }
        if (path.equals("/api/push/subscriptions") && method.equals("GET")) {
            return listSubscriptions(out);
        }
        if (path.equals("/api/push/preferences") && method.equals("POST")) {
            return updatePreferences(body, out);
        }
        if (path.equals("/api/push/test") && method.equals("POST")) {
            return sendTest(body, out);
        }
        return false;
    }

    // ==================== HANDLERS ====================

    private static boolean getCategories(OutputStream out) throws Exception {
        JSONObject root = new JSONObject(registry.rawJson());
        root.put("vapidPublicKey", keyStore.publicKeyB64Url());
        HttpResponse.sendJson(out, root.toString());
        return true;
    }

    // ==================== NOTIFICATION LOG ====================

    /**
     * GET /api/notifications/log — newest-first, paginated, optional filters:
     *   from/to (epoch-ms), days (fallback window), group (category prefix,
     *   e.g. "vehicle.charging"), severity (info/warn/critical),
     *   page (1-based) / pageSize.
     */
    private static boolean listLog(String path, OutputStream out) throws Exception {
        if (logStore == null) { HttpResponse.sendError(out, 503, "log unavailable"); return true; }
        Map<String, String> q = parseQuery(path);

        long now = System.currentTimeMillis();
        long fromMs, toMs;
        if (q.containsKey("from")) {
            fromMs = Math.max(0L, parseLong(q.get("from"), 0));
            toMs = q.containsKey("to") ? parseLong(q.get("to"), Long.MAX_VALUE) : Long.MAX_VALUE;
        } else {
            // Clamp days to [0, 36525] (~100y) so a garbage value can't wrap the
            // (int) cast negative (→ future fromMs → empty log) or underflow.
            long daysRaw = parseLong(q.get("days"), 0);
            long days = Math.max(0L, Math.min(daysRaw, 36525L));   // 0 = all time
            fromMs = days > 0 ? now - (days * 24L * 60 * 60 * 1000L) : 0;
            toMs = Long.MAX_VALUE;
        }

        String group = emptyToNull(q.get("group"));
        String severity = emptyToNull(q.get("severity"));

        int pageSize = (int) parseLong(q.get("pageSize"), 20);
        pageSize = Math.max(1, Math.min(pageSize, 200));
        int page = (int) parseLong(q.get("page"), 1);
        page = Math.max(1, page);
        int offset = (page - 1) * pageSize;

        // Atomic page+count in one lock acquisition so totalPages can't disagree
        // with the returned rows under a concurrent insert/prune.
        NotificationStore.Page pg = logStore.listWithCount(fromMs, toMs, group, severity, pageSize, offset);
        JSONArray items = pg.items;
        long total = pg.total;

        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("notifications", items);
        resp.put("total", total);
        resp.put("page", page);
        resp.put("pageSize", pageSize);
        resp.put("totalPages", (int) Math.ceil(total / (double) pageSize));
        // Ship the category registry so the client can label/color/group rows
        // without a second request (registry is small and already in memory).
        try { resp.put("categories", new JSONObject(registry.rawJson()).optJSONArray("categories")); }
        catch (Exception ignored) {}
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    private static boolean deleteLog(String idStr, OutputStream out) throws Exception {
        if (logStore == null) { HttpResponse.sendError(out, 503, "log unavailable"); return true; }
        long id;
        try { id = Long.parseLong(idStr.trim()); }
        catch (Exception e) { HttpResponse.sendError(out, 400, "invalid id"); return true; }
        boolean ok = logStore.deleteById(id);
        JSONObject resp = new JSONObject();
        resp.put("success", ok);
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    private static boolean bulkDeleteLog(String body, OutputStream out) throws Exception {
        if (logStore == null) { HttpResponse.sendError(out, 503, "log unavailable"); return true; }
        if (body == null || body.isEmpty()) { HttpResponse.sendError(out, 400, "missing body"); return true; }
        try {
            JSONObject j = new JSONObject(body);
            JSONArray arr = j.getJSONArray("ids");
            // Hard cap so a hostile/oversized body can't drive a giant delete.
            if (arr.length() > MAX_BULK_IDS) {
                HttpResponse.sendError(out, 400, "too many ids (max " + MAX_BULK_IDS + ")");
                return true;
            }
            // Robust per-element parse: skip non-integer elements rather than
            // failing the whole batch (one bad id shouldn't strand the good ones).
            java.util.ArrayList<Long> parsed = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                long v = arr.optLong(i, Long.MIN_VALUE);
                if (v != Long.MIN_VALUE) parsed.add(v);
            }
            long[] ids = new long[parsed.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = parsed.get(i);
            int removed = logStore.deleteBulk(ids);
            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("removed", removed);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            HttpResponse.sendError(out, 400, "invalid ids: " + e.getMessage());
        }
        return true;
    }

    /** DELETE /api/notifications/log[?from=&to=] (or POST .../log/clear) — clear all or a window. */
    private static boolean clearLog(String path, OutputStream out) throws Exception {
        if (logStore == null) { HttpResponse.sendError(out, 503, "log unavailable"); return true; }
        Map<String, String> q = parseQuery(path);
        long fromMs = parseLong(q.get("from"), 0);
        long toMs = q.containsKey("to") ? parseLong(q.get("to"), Long.MAX_VALUE) : Long.MAX_VALUE;
        int removed = logStore.clear(fromMs, toMs);
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("removed", removed);
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    // ---- tiny query helpers (org.json only; no external deps) ----
    private static Map<String, String> parseQuery(String path) {
        Map<String, String> m = new HashMap<>();
        int qi = path.indexOf('?');
        if (qi < 0 || qi == path.length() - 1) return m;
        String[] pairs = path.substring(qi + 1).split("&");
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) {
                    m.put(java.net.URLDecoder.decode(pair, "UTF-8"), "");
                } else {
                    String k = java.net.URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String v = java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    m.put(k, v);
                }
            } catch (Exception ignored) { /* skip malformed pair */ }
        }
        return m;
    }

    private static long parseLong(String s, long dflt) {
        if (s == null || s.isEmpty()) return dflt;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return dflt; }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static boolean subscribe(String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "missing body");
            return true;
        }
        try {
            JSONObject j = new JSONObject(body);
            String endpoint = j.getString("endpoint");
            JSONObject keys = j.getJSONObject("keys");
            byte[] p256dh = android.util.Base64.decode(keys.getString("p256dh"),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            byte[] auth = android.util.Base64.decode(keys.getString("auth"),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            String label = j.optString("label", null);
            // The client explicitly opts in to "re-enable a previously-removed
            // device" by setting force=true. Any other path (silent resubscribe
            // from pwa-init.js, an enable click that re-uses a stale browser
            // sub) honors the tombstone and gets 410.
            boolean force = j.optBoolean("force", false);

            String id = SubscriptionStore.idForEndpoint(endpoint);

            // Tombstone gate. Without this, any path that POSTs /subscribe
            // (e.g. an enable click after the user just removed the row,
            // or the silent self-heal in pwa-init.js when the browser
            // still has a live PushSubscription) would silently re-create
            // the row the user just deleted — exactly the "notifications
            // keep flowing" symptom.
            if (!force && subStore.isTombstoned(id)) {
                JSONObject err = new JSONObject();
                err.put("success", false);
                err.put("error", "subscription was removed; pass force=true to re-enable");
                err.put("tombstoned", true);
                HttpResponse.sendJson(out, 410, err.toString());
                return true;
            }
            // Force path: clear the tombstone so the next /unsubscribe in this
            // session is the authoritative source of truth again.
            if (force) subStore.clearTombstone(id);

            PushSubscription existing = subStore.get(id);
            PushSubscription sub;
            if (existing != null) {
                // Re-subscribe — keep prefs, refresh keys
                sub = new PushSubscription(id, endpoint, p256dh, auth,
                        label != null ? label : existing.label, existing.createdAt);
                sub.lastSeenAt = System.currentTimeMillis();
                sub.minSeverity = existing.minSeverity;
                sub.mutedCategories.addAll(existing.mutedCategories);
                sub.quietHours = existing.quietHours;
            } else {
                sub = new PushSubscription(id, endpoint, p256dh, auth, label,
                        System.currentTimeMillis());
                // Brand-new device: seed the mute set from the registry so
                // categories marked defaultEnabled:false (surveillance notice,
                // legacy motion, door open/close) start OFF, as the registry
                // has always claimed. Before this, a fresh subscription's empty
                // mute set meant every default-off category delivered until the
                // user muted it by hand — the "notice notifications arrive even
                // though the tier is off" report. Only new devices are seeded;
                // the re-subscribe branch above preserves explicit user choices.
                sub.mutedCategories.addAll(
                        PushSubscription.defaultMutedCategories(registry));
            }
            subStore.put(sub);

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("id", id);
            HttpResponse.sendJson(out, resp.toString());
        } catch (Exception e) {
            HttpResponse.sendError(out, 400, "invalid subscription: " + e.getMessage());
        }
        return true;
    }

    private static boolean unsubscribe(String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "missing body");
            return true;
        }
        JSONObject j = new JSONObject(body);
        String id = j.optString("id", null);
        if (id == null) {
            String endpoint = j.optString("endpoint", null);
            if (endpoint != null) id = SubscriptionStore.idForEndpoint(endpoint);
        }
        if (id == null) {
            HttpResponse.sendError(out, 400, "id or endpoint required");
            return true;
        }
        boolean removed = subStore.remove(id);
        JSONObject resp = new JSONObject();
        resp.put("success", removed);
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    private static boolean listSubscriptions(OutputStream out) throws Exception {
        JSONArray arr = new JSONArray();
        for (PushSubscription s : subStore.all()) {
            JSONObject j = new JSONObject();
            j.put("id", s.id);
            j.put("label", s.label == null ? "" : s.label);
            j.put("createdAt", s.createdAt);
            j.put("lastSeenAt", s.lastSeenAt);
            j.put("minSeverity", s.minSeverity.name().toLowerCase(java.util.Locale.US));
            JSONArray muted = new JSONArray();
            for (String c : s.mutedCategories) muted.put(c);
            j.put("mutedCategories", muted);
            if (s.quietHours != null) {
                JSONObject qh = new JSONObject();
                qh.put("startMin", s.quietHours.startMin);
                qh.put("endMin", s.quietHours.endMin);
                qh.put("allowCritical", s.quietHours.allowCritical);
                j.put("quietHours", qh);
            }
            arr.put(j);
        }
        JSONObject resp = new JSONObject();
        resp.put("success", true);
        resp.put("subscriptions", arr);
        HttpResponse.sendJson(out, resp.toString());
        return true;
    }

    private static boolean updatePreferences(String body, OutputStream out) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendError(out, 400, "missing body");
            return true;
        }
        JSONObject j = new JSONObject(body);
        String id = j.getString("id");
        PushSubscription sub = subStore.get(id);
        if (sub == null) {
            HttpResponse.sendError(out, 404, "subscription not found");
            return true;
        }

        if (j.has("mutedCategories")) {
            JSONArray muted = j.getJSONArray("mutedCategories");
            sub.mutedCategories.clear();
            for (int i = 0; i < muted.length(); i++) {
                sub.mutedCategories.add(muted.getString(i));
            }
        }
        if (j.has("minSeverity")) {
            try {
                sub.minSeverity = NotificationEvent.Severity.valueOf(
                        j.getString("minSeverity").toUpperCase(java.util.Locale.US));
            } catch (Exception ignored) {}
        }
        if (j.has("quietHours")) {
            Object qhRaw = j.get("quietHours");
            if (qhRaw == JSONObject.NULL) {
                sub.quietHours = null;
            } else {
                JSONObject qh = (JSONObject) qhRaw;
                sub.quietHours = new PushSubscription.QuietHours(
                        qh.getInt("startMin"),
                        qh.getInt("endMin"),
                        qh.optBoolean("allowCritical", true));
            }
        }

        // re-persist — store mutates in place but file write is via put()
        subStore.put(sub);
        HttpResponse.sendJsonSuccess(out);
        return true;
    }

    private static boolean sendTest(String body, OutputStream out) throws Exception {
        String category = "surveillance.motion";
        String severityStr = "info";
        String targetId = null;
        if (body != null && !body.isEmpty()) {
            try {
                JSONObject j = new JSONObject(body);
                category = j.optString("category", category);
                severityStr = j.optString("severity", severityStr);
                // isNull guards the JSON literal null → org.json's optString
                // would otherwise hand back the string "null", which matches no
                // subscription and would deliver the test to nobody.
                targetId = j.isNull("id") ? null : emptyToNull(j.optString("id", null));
            } catch (Exception ignored) {}
        }
        NotificationEvent.Severity severity;
        try {
            severity = NotificationEvent.Severity.valueOf(severityStr.toUpperCase(java.util.Locale.US));
        } catch (Exception e) {
            severity = NotificationEvent.Severity.INFO;
        }
        NotificationEvent event = new NotificationEvent(
                category,
                severity,
                text("notifications.test_title", "Test notification"),
                text("notifications.test_body", "If you're seeing this, push delivery works."),
                "test-" + System.currentTimeMillis(),
                null,
                new JSONObject().put("test", true));
        // A user-initiated test must reach the REQUESTING device regardless of
        // its muted categories / severity floor / quiet hours — otherwise the
        // button looks broken on a fresh device (default category muted by the
        // new registry-seed) or when the user raised the floor. The bypass is
        // applied ONLY when we can scope it to the requester's own id, so it can
        // never fan an unfiltered push to OTHER phones (e.g. waking a sleeping
        // device in its quiet hours). An older PWA that sends no id keeps the
        // previous behaviour exactly: broadcast to all, each device's prefs
        // still honoured.
        if (targetId != null) {
            event.targetSubscription(targetId).bypassPreferences();
        }
        NotificationBus.get().publish(event);
        HttpResponse.sendJsonSuccess(out);
        return true;
    }

    /** Localized push text; falls back to English when the key is missing. */
    private static String text(String key, String english) {
        String v = Messages.get(key);
        return v == null || v.isEmpty() || v.equals(key) ? english : v;
    }
}
