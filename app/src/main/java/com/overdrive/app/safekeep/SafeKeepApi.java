package com.overdrive.app.safekeep;

import com.overdrive.app.config.ConfigBackupService;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.safekeep.auth.OAuthApps;
import com.overdrive.app.safekeep.crypto.AgeCodec;
import com.overdrive.app.safekeep.dest.DestError;
import com.overdrive.app.safekeep.dest.Destination;
import com.overdrive.app.safekeep.net.HttpFactory;
import com.overdrive.app.safekeep.util.QrPng;
import com.overdrive.app.server.HttpResponse;
import com.overdrive.app.trips.TripDatabase;
import com.overdrive.app.trips.TripRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * /api/safekeep/* — exact path matching only. Secrets are never echoed. When SafeKeep is OFF
 * the handler still answers status/config/enable and on-demand trip exports; everything that
 * would start background work requires the master toggle.
 */
public final class SafeKeepApi {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeepApi");
    private static final String P = "/api/safekeep/";
    /** Restore downloads and inflation are bounded: the daemon must never OOM on a bad file. */
    private static final long TRIP_ROW_MAX_BYTES = 4L * 1024 * 1024;
    private static final long SETTINGS_MAX_BYTES = 8L * 1024 * 1024;
    private static final long SETTINGS_INFLATED_MAX_BYTES = 16L * 1024 * 1024;

    private SafeKeepApi() {}

    public static boolean handle(String method, String fullPath, String body, OutputStream out) throws Exception {
        int q = fullPath.indexOf('?');
        String path = q >= 0 ? fullPath.substring(0, q) : fullPath;
        String query = q >= 0 ? fullPath.substring(q + 1) : "";
        if (!path.startsWith(P)) return false;
        String op = path.substring(P.length());
        boolean get = "GET".equals(method);
        boolean post = "POST".equals(method);
        try {
            if (get && op.equals("status")) return json(out, status());
            if (get && op.equals("config")) return json(out, config());
            if (post && op.equals("enable")) return enable(body, out);
            if (post && op.equals("config")) return patchConfig(body, out);
            if (get && op.equals("export/trip")) return exportTrip(query, out);
            if (get && op.equals("export/trips.csv")) return exportTripsCsv(query, out);
            if (post && op.equals("keys/generate")) return generateKey(out);
            // Editing or removing storage (and so revoking its sign-in) must not require turning uploads on.
            if (post && op.equals("dest/update")) return updateDestination(body, out);
            if (post && op.equals("dest/remove")) return removeDestination(body, out);

            Engine e = SafeKeep.engine();
            if (e == null) {
                HttpResponse.sendJson(out, 409, err("disabled", "Turn SafeKeep on first").toString());
                return true;
            }
            switch (op) {
                case "dest/add":
                    return post && addDestination(body, out);
                case "dest/cert":
                    return post && fetchCert(e, body, out);
                case "dest/test":
                    return post && testDestination(e, body, out);
                case "link/start":
                    return post && linkStart(e, body, out);
                case "link/status":
                    return get && json(out, e.links.status(param(query, "id")));
                case "link/finish":
                    return post && linkFinish(e, body, out);
                case "link/cancel":
                    if (!post) return false;
                    if (!e.links.cancel(new JSONObject(safe(body)).optString("id"))) {
                        HttpResponse.sendJson(out, 409, err("link_commit_started",
                                "Sign-in is already being saved; check its status").toString());
                        return true;
                    }
                    return json(out, ok());
                case "link/qr.png":
                    return get && qr(e, param(query, "id"), out);
                case "queue":
                    return get && json(out, queue(e, query));
                case "queue/retry":
                    return post && retry(e, body, out);
                case "queue/skip": {
                    if (!post) return false;
                    long id = new JSONObject(safe(body)).optLong("id");
                    Ledger.Item it = e.ledger.get(id);
                    if (e.ledger.skipItem(id) > 0 && it != null) {
                        e.abandonSession(it);
                        e.retention.unpinIfIdle(it.localPath, e.ledger);
                    }
                    e.wake();
                    return json(out, ok());
                }
                case "run":
                    if (!post) return false;
                    e.syncNow();
                    return json(out, ok());
                case "pause":
                    if (!post) return false;
                    e.pause(new JSONObject(safe(body)).optInt("minutes", 60));
                    return json(out, ok());
                case "resume":
                    if (!post) return false;
                    e.resume();
                    return json(out, ok());
                case "dryrun":
                    return post && json(out, dryRun(e));
                case "upload":
                    return post && uploadNow(e, body, out);
                case "lock":
                    return post && lock(e, body, out);
                case "clips":
                    return post && clips(e, body, out);
                case "backfill/trips":
                    return post && backfillTrips(e, body, out);
                case "share":
                    return post && share(e, body, out);
                case "restore/list":
                    return post && restoreList(e, body, out);
                case "restore/trips":
                    return post && restoreTrips(e, body, out);
                case "restore/settings":
                    return post && restoreSettings(e, body, out);
                case "keys/check":
                    return post && checkKey(body, out);
                case "webhook/secret":
                    return post && webhookSecret(body, out);
                default:
                    return false;
            }
        } catch (IllegalStateException ise) {
            HttpResponse.sendJson(out, 400, err(ise.getMessage(), ise.getMessage()).toString());
            return true;
        } catch (org.json.JSONException je) {
            // org.json messages quote the input, which may hold passwords or keys: never log or echo them.
            HttpResponse.sendJson(out, 400, err("bad_json", "Malformed request").toString());
            return true;
        } catch (DestError de) {
            logger.warn("SafeKeep API " + op + " failed: " + de.kind);
            HttpResponse.sendJson(out, 502, err(de.kind.name().toLowerCase(Locale.ROOT), de.getMessage()).toString());
            return true;
        } catch (Exception ex) {
            logger.warn("SafeKeep API " + op + " failed: " + ex.getClass().getSimpleName());
            HttpResponse.sendJson(out, 500, err("internal", "Internal error").toString());
            return true;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean json(OutputStream out, JSONObject o) throws Exception {
        HttpResponse.sendJson(out, o.toString());
        return true;
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("success", true);
    }

    private static JSONObject err(String code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("success", false).put("error", code == null ? "error" : code).put("message", message);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static String safe(String body) {
        return body == null || body.trim().isEmpty() ? "{}" : body;
    }

    static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            String k = eq >= 0 ? kv.substring(0, eq) : kv;
            if (!k.equals(key)) continue;
            try {
                return URLDecoder.decode(eq >= 0 ? kv.substring(eq + 1) : "", "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }

    /** Runs request work on the request pool (never behind background chores) with a deadline. */
    private static <T> T onAux(Engine e, Callable<T> c, long timeoutMs) throws Exception {
        Future<T> f = e.req.submit(c);
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            throw new IllegalStateException("timeout");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException(String.valueOf(cause));
        }
    }

    // ── status & config ──────────────────────────────────────────────────────

    private static JSONObject status() throws Exception {
        Engine e = SafeKeep.engine();
        if (e == null) {
            return new JSONObject().put("enabled", false).put("state", "off")
                    .put("destinations", new JSONArray());
        }
        return e.status();
    }

    /** Config without secrets, plus which sign-in apps this build has and the destination facts the UI needs. */
    private static JSONObject config() throws Exception {
        JSONObject s = SafeKeepAdmin.section();
        JSONArray dests = s.optJSONArray("destinations");
        if (dests != null) {
            for (int i = 0; i < dests.length(); i++) {
                JSONObject d = dests.optJSONObject(i);
                if (d == null) continue;
                JSONObject sec = SafeKeepSecrets.get(d.optString("id"));
                d.put("hasCredential", sec.length() > 0);
                if (sec.has("username")) d.put("username", sec.optString("username"));
                if (sec.has("accessKeyId")) d.put("accessKeyHint", hint(sec.optString("accessKeyId")));
            }
        }
        JSONObject apps = new JSONObject()
                .put("gdrive", OAuthApps.get("gdrive", null).configured())
                .put("onedrive", OAuthApps.get("onedrive", null).configured())
                .put("dropbox", OAuthApps.get("dropbox", null).configured());
        return new JSONObject().put("config", s).put("oauthApps", apps)
                .put("conditionTypes", new JSONArray(java.util.Arrays.asList(UploadSignals.OWNED_TYPES)));
    }

    private static String hint(String key) {
        if (key == null || key.length() < 6) return "••••";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 2);
    }

    private static boolean enable(String body, OutputStream out) throws Exception {
        boolean on = new JSONObject(safe(body)).optBoolean("enabled", false);
        boolean ok = SafeKeepAdmin.setEnabled(on);
        return json(out, new JSONObject().put("success", ok).put("enabled", ok ? on : SafeKeep.isActive()));
    }

    private static boolean patchConfig(String body, OutputStream out) throws Exception {
        JSONObject patch = new JSONObject(safe(body));
        if (patch.has("routes")) validateRoutes(patch.getJSONArray("routes"));
        if (patch.has("vehicleName")) patch.put("vehicleName", SafeKeepConfig.sanitizeName(patch.optString("vehicleName")));
        JSONObject secretUpdates = null;
        if (patch.has("webhooks")) {
            JSONArray w = patch.getJSONArray("webhooks");
            secretUpdates = new JSONObject();
            java.util.Set<String> kept = new java.util.HashSet<>();
            for (int i = 0; i < w.length(); i++) {
                JSONObject hook = w.getJSONObject(i);
                String url = hook.optString("url", "");
                if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IllegalStateException("bad_webhook_url");
                kept.add(url);
                // A secret travels with Save and is stored encrypted, never in the config section.
                if (hook.has("secret")) {
                    String sec = hook.optString("secret", "");
                    secretUpdates.put(url, sec.isEmpty() ? JSONObject.NULL : sec);
                    hook.remove("secret");
                }
            }
            for (SafeKeepConfig.Webhook old : SafeKeepConfig.load().webhooks) {
                if (!kept.contains(old.url)) secretUpdates.put(old.url, JSONObject.NULL);
            }
        }
        if (secretUpdates != null && secretUpdates.length() > 0 && !SafeKeepSecrets.merge("webhooks", secretUpdates)) {
            throw new IllegalStateException("secure_store_failed");
        }
        return json(out, new JSONObject().put("success", SafeKeepAdmin.patch(patch)));
    }

    private static void validateRoutes(JSONArray routes) {
        for (int i = 0; i < routes.length(); i++) {
            JSONObject r = routes.optJSONObject(i);
            if (r == null || SafeKeepConfig.Lane.parse(r.optString("lane")) == null) {
                throw new IllegalStateException("bad_route");
            }
            if (r.has("backfillDays") && !inRange(r.opt("backfillDays"), 0, 3650)) throw new IllegalStateException("bad_route");
            JSONObject rule = r.optJSONObject("rule");
            if (rule != null && rule.has("maxMbps") && !inRange(rule.opt("maxMbps"), 0, 10000)) {
                throw new IllegalStateException("bad_route");
            }
            JSONObject w = rule == null ? null : rule.optJSONObject("window");
            if (w != null && ((w.has("from") && !inRange(w.opt("from"), 0, 1440))
                    || (w.has("to") && !inRange(w.opt("to"), 0, 1440)))) {
                throw new IllegalStateException("bad_route");
            }
            JSONObject adv = rule == null ? null : rule.optJSONObject("advanced");
            if (adv != null && Policy.parseAdvanced(adv) == null) throw new IllegalStateException("bad_conditions");
        }
    }

    private static boolean inRange(Object v, double lo, double hi) {
        if (!(v instanceof Number)) return false;
        double d = ((Number) v).doubleValue();
        return !Double.isNaN(d) && d >= lo && d <= hi;
    }

    // ── destinations ─────────────────────────────────────────────────────────

    private static boolean addDestination(String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        String type = j.optString("type", "").toLowerCase(Locale.ROOT);
        if (!"s3".equals(type) && !"webdav".equals(type) && !"nextcloud".equals(type) && !"sftp".equals(type)) {
            throw new IllegalStateException("use_link_flow");
        }
        JSONObject params = j.optJSONObject("params");
        JSONObject secrets = j.optJSONObject("secrets");
        if (params == null) params = new JSONObject();
        if (secrets == null) secrets = new JSONObject();
        if ("nextcloud".equals(type)) params.put("mode", "nextcloud");
        String id = type + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        if (!SafeKeepSecrets.put(id, secrets)) throw new IllegalStateException("secure_store_failed");
        String name = j.optString("name", defaultName(type, params));
        boolean ok = SafeKeepAdmin.addDestination(id, "nextcloud".equals(type) ? "webdav" : type, name, params,
                j.optString("proxyMode", "auto"));
        if (!ok) {
            SafeKeepSecrets.remove(id);
            throw new IllegalStateException("save_failed");
        }
        return json(out, new JSONObject().put("success", true).put("id", id));
    }

    private static String defaultName(String type, JSONObject params) {
        String host = params.optString("host", "");
        String url = params.optString("url", params.optString("endpoint", ""));
        try {
            if (host.isEmpty() && !url.isEmpty()) host = new java.net.URI(url).getHost();
        } catch (Exception ignored) {
        }
        String label = "s3".equals(type) ? "S3" : "sftp".equals(type) ? "SFTP" : "nextcloud".equals(type) ? "Nextcloud" : "WebDAV";
        String bucket = params.optString("bucket", "");
        return label + (host == null || host.isEmpty() ? "" : " · " + host) + (bucket.isEmpty() ? "" : "/" + bucket);
    }

    private static boolean updateDestination(String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        String id = j.optString("id", "");
        if (id.isEmpty()) throw new IllegalStateException("id_required");
        JSONObject secrets = j.optJSONObject("secrets");
        if (secrets != null && secrets.length() > 0 && !SafeKeepSecrets.merge(id, secrets)) {
            throw new IllegalStateException("secure_store_failed");
        }
        JSONObject patch = new JSONObject();
        for (String k : new String[]{"name", "enabled", "proxyMode", "layout", "retention", "encryption", "params"}) {
            if (j.has(k)) patch.put(k, j.get(k));
        }
        JSONObject enc = patch.optJSONObject("encryption");
        if (enc != null) {
            JSONArray rs = enc.optJSONArray("recipients");
            if (rs != null) for (int i = 0; i < rs.length(); i++) AgeCodec.parseRecipient(rs.getString(i));
        }
        boolean ok = SafeKeepAdmin.updateDestination(id, patch);
        Engine e = SafeKeep.engine();
        if (ok && e != null && secrets != null && secrets.length() > 0) {
            e.runCtl(() -> e.setDestState(id, "ok", null, 0));
        }
        return json(out, new JSONObject().put("success", ok));
    }

    private static boolean removeDestination(String body, OutputStream out) throws Exception {
        String id = new JSONObject(safe(body)).optString("id", "");
        return json(out, new JSONObject().put("success", !id.isEmpty() && SafeKeepAdmin.removeDestination(id)));
    }

    private static boolean testDestination(Engine e, String body, OutputStream out) throws Exception {
        String id = new JSONObject(safe(body)).optString("id", "");
        Destination drv = e.dests.get(id);
        if (drv == null) {
            e.applyConfig(SafeKeepConfig.load());
            drv = e.dests.get(id);
        }
        if (drv == null) throw new IllegalStateException("destination_unavailable");
        final Destination d = drv;
        try {
            JSONObject r = onAux(e, d::test, 30_000);
            e.noteDestOk(id);
            e.setDestState(id, "ok", null, 0);
            try {
                Destination.Quota qt = onAux(e, d::quota, 15_000);
                e.noteQuota(id, qt);
                if (qt != null) r.put("quota", new JSONObject().put("used", qt.used).put("total", qt.total));
            } catch (Exception ignored) {
            }
            r.put("route", HttpFactory.lastRoute());
            return json(out, new JSONObject().put("success", true).put("result", r));
        } catch (DestError de) {
            if (de.kind == DestError.Kind.AUTH_EXPIRED) e.setDestState(id, "needs_auth", de.getMessage(), 0);
            return json(out, err(de.kind.name().toLowerCase(Locale.ROOT), de.getMessage()));
        }
    }

    private static boolean fetchCert(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        String url = j.optString("url", "");
        if (!url.startsWith("https://")) throw new IllegalStateException("https_required");
        HttpFactory.ProxyMode mode = HttpFactory.ProxyMode.parse(j.optString("proxyMode", "auto"));
        String[] leaf = onAux(e, () -> HttpFactory.fetchLeafCert(url, mode), 20_000);
        return json(out, new JSONObject().put("success", true).put("sha256", leaf[0]).put("subject", leaf[1]));
    }

    // ── sign-in ──────────────────────────────────────────────────────────────

    private static boolean linkStart(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        long deadline = System.currentTimeMillis() + 25_000;
        JSONObject r = onAux(e, () -> e.links.start(j.optString("type"), j.optJSONObject("params"), deadline), 25_000);
        r.put("success", true);
        return json(out, r);
    }

    private static boolean linkFinish(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        JSONObject r = onAux(e, () -> e.links.finishPaste(j.optString("id"), j.optString("code")), 25_000);
        r.put("success", "done".equals(r.optString("state")));
        return json(out, r);
    }

    private static boolean qr(Engine e, String id, OutputStream out) throws Exception {
        String text = e.links.qrText(id);
        if (text == null) {
            HttpResponse.sendError(out, 404, "link expired");
            return true;
        }
        HttpResponse.sendBinaryNoStore(out, "image/png", QrPng.render(text, 8), null);
        return true;
    }

    // ── queue ────────────────────────────────────────────────────────────────

    private static JSONObject queue(Engine e, String query) throws Exception {
        String state = param(query, "state");
        String lane = param(query, "lane");
        int limit = 100;
        try {
            limit = Math.max(1, Math.min(500, Integer.parseInt(param(query, "limit"))));
        } catch (Exception ignored) {
        }
        JSONArray rows = new JSONArray();
        for (Ledger.Item it : e.ledger.listQueue(state.isEmpty() ? null : state.toUpperCase(Locale.ROOT),
                lane.isEmpty() ? null : lane, limit)) {
            String name = it.localPath != null ? new File(it.localPath).getName()
                    : it.remotePath.substring(it.remotePath.lastIndexOf('/') + 1);
            JSONObject o = new JSONObject().put("id", it.id).put("name", name).put("lane", it.lane)
                    .put("dest", it.destId).put("state", it.state).put("size", it.sizeBytes)
                    .put("sent", it.bytesConfirmed).put("attempts", it.attempts).put("manual", it.manual)
                    .put("encrypted", it.encrypted);
            if (it.errorClass != null) o.put("errorClass", it.errorClass);
            if (it.lastError != null) o.put("error", it.lastError);
            if (it.nextAttemptAt > System.currentTimeMillis()) o.put("nextAttemptAt", it.nextAttemptAt);
            rows.put(o);
        }
        return new JSONObject().put("success", true).put("items", rows);
    }

    private static boolean retry(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        int n;
        if (j.optBoolean("all", false)) n = e.ledger.retryFailed(j.optString("dest", "").isEmpty() ? null : j.optString("dest"));
        else n = e.ledger.retryItem(j.optLong("id"));
        e.wake();
        return json(out, new JSONObject().put("success", true).put("count", n));
    }

    /** What would upload right now, and why the rest waits (the planner's own checks, in its order). */
    private static JSONObject dryRun(Engine e) throws Exception {
        SafeKeepConfig c = e.cfg;
        UploadSignals.Snap s = e.signals.captureFresh();
        long now = System.currentTimeMillis();
        JSONArray lanes = new JSONArray();
        Map<String, Map<String, long[]>> backlog = e.ledger.backlog();
        Policy.Verdict guards = e.signals.guards(c, s);
        String tinyBp = e.governor.backpressure(c, s, 0);
        String mediaBp = e.governor.backpressure(c, s, 1);
        boolean capHit = !s.unmetered() && e.mobileCapHitNow();
        for (SafeKeepConfig.Route r : c.routes) {
            for (String destId : r.destIds) {
                SafeKeepConfig.Destination d = c.destination(destId);
                if (d == null) continue;
                JSONObject eff = Policy.effective(r.rule);
                boolean lan = e.lanReachable(d);
                Policy.Verdict media = Policy.rule(eff, c, s, 2, lan, false, false);
                Policy.Verdict tiny = Policy.rule(eff, c, s, 0, lan, false, false);
                String hold = null;
                if (e.pausedUntil > now) hold = "paused_by_user";
                else if (!d.enabled) hold = "disabled";
                else if (!e.dests.containsKey(d.id)) hold = "dest_unavailable";
                else hold = Engine.destHold(e.destState(d.id), now);
                if (hold == null && !guards.ok) hold = guards.reason;
                if (hold == null && capHit) hold = "mobile_cap";
                if (hold == null) hold = tinyBp;
                Map<String, long[]> m = backlog.get(r.lane.id);
                long[] pend = m == null ? null : m.get(Ledger.PENDING);
                JSONObject o = new JSONObject().put("lane", r.lane.id).put("dest", destId).put("destName", d.name)
                        .put("pendingCount", pend == null ? 0 : pend[0]).put("pendingBytes", pend == null ? 0 : pend[1])
                        .put("mediaAllowed", hold == null && media.ok && mediaBp == null)
                        .put("smallAllowed", hold == null && tiny.ok);
                String why = hold != null ? hold : (!media.ok ? media.reason : mediaBp);
                if (why != null) o.put("reason", why);
                lanes.put(o);
            }
        }
        JSONObject net = new JSONObject().put("transport", s.net == null ? "none"
                : s.net.transport.name().toLowerCase(Locale.ROOT)).put("metered", !s.unmetered())
                .put("phoneHotspot", s.phoneHotspot).put("parked", s.parked).put("charging", s.charging);
        if (s.ssid != null && !s.ssid.isEmpty()) net.put("ssid", Policy.normalizeSsid(s.ssid));
        return new JSONObject().put("success", true).put("routes", lanes).put("now", net)
                .put("mobileUsedBytes", e.mobileUsed());
    }

    // ── per-clip actions ─────────────────────────────────────────────────────

    private static boolean uploadNow(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        String error = e.enqueuer.uploadNow(j.optString("file"), j.optBoolean("mobileOk", false));
        if (error != null) return json(out, err(error, error));
        return json(out, ok());
    }

    private static boolean lock(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        String error = e.enqueuer.lock(j.optString("file"), j.optBoolean("locked", true));
        if (error != null) return json(out, err(error, error));
        return json(out, ok());
    }

    private static boolean clips(Engine e, String body, OutputStream out) throws Exception {
        JSONArray files = new JSONObject(safe(body)).optJSONArray("files");
        List<String> names = new ArrayList<>();
        if (files != null) for (int i = 0; i < files.length() && i < 200; i++) names.add(files.optString(i));
        JSONObject states = new JSONObject();
        for (Map.Entry<String, Object> en : e.clipStates(names).entrySet()) states.put(en.getKey(), en.getValue());
        return json(out, new JSONObject().put("success", true).put("states", states));
    }

    private static boolean backfillTrips(Engine e, String body, OutputStream out) throws Exception {
        int days = new JSONObject(safe(body)).optInt("days", 0);
        e.runCtl(() -> e.enqueuer.backfillTrips(days));
        return json(out, ok());
    }

    private static boolean share(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        String file = j.optString("file", "");
        boolean anyone = j.optBoolean("anyone", false);
        for (Ledger.Item it : e.ledger.byLogicalKey(Naming.fileKey(file))) {
            if (!Ledger.DONE.equals(it.state) || it.remoteRef == null) continue;
            Destination d = e.dests.get(it.destId);
            if (d == null || !d.caps().canShare && !anyone) {
                String link = e.webLink(it);
                if (link != null) return json(out, new JSONObject().put("success", true).put("url", link));
                continue;
            }
            final Ledger.Item item = it;
            String url = onAux(e, () -> d.shareLink(item.remoteRef, anyone), 20_000);
            if (url == null) url = e.webLink(it);
            if (url != null) return json(out, new JSONObject().put("success", true).put("url", url));
        }
        return json(out, err("no_link", "No shareable copy yet"));
    }

    // ── restore ──────────────────────────────────────────────────────────────

    private static Destination destFor(Engine e, String id) {
        Destination d = e.dests.get(id);
        if (d == null) throw new IllegalStateException("destination_unavailable");
        if (!d.caps().canList) throw new IllegalStateException("destination_cannot_list");
        return d;
    }

    private static boolean restoreList(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        SafeKeepConfig c = e.cfg;
        SafeKeepConfig.Destination dc = c.destination(j.optString("dest"));
        if (dc == null) throw new IllegalStateException("destination_unavailable");
        Destination d = destFor(e, dc.id);
        String dir = "settings".equals(j.optString("what")) ? Naming.settingsDir(c, dc) : Naming.tripsDir(c, dc);
        List<Destination.RemoteEntry> entries = onAux(e, () -> d.list(dir), 60_000);
        JSONArray arr = new JSONArray();
        List<Destination.RemoteEntry> files = new ArrayList<>();
        for (Destination.RemoteEntry en : entries) if (!en.folder) files.add(en);
        Collections.sort(files, (a, b) -> b.name.compareTo(a.name));
        for (Destination.RemoteEntry en : files) {
            arr.put(new JSONObject().put("name", en.name).put("ref", en.ref).put("size", en.size)
                    .put("encrypted", en.name.endsWith(".age")));
        }
        return json(out, new JSONObject().put("success", true).put("files", arr));
    }

    private static byte[] fetchPlain(Engine e, Destination d, String ref, String name, String identity,
                                     long maxBytes) throws Exception {
        byte[] data = onAux(e, () -> d.download(ref, maxBytes), 120_000);
        if (name.endsWith(".age")) {
            if (identity == null || identity.trim().isEmpty()) throw new IllegalStateException("identity_required");
            data = AgeCodec.decrypt(data, identity.trim());
        }
        return data;
    }

    private static boolean restoreTrips(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        SafeKeepConfig c = e.cfg;
        SafeKeepConfig.Destination dc = c.destination(j.optString("dest"));
        if (dc == null) throw new IllegalStateException("destination_unavailable");
        Destination d = destFor(e, dc.id);
        TripDatabase db = Staging.tripDb();
        if (db == null) throw new IllegalStateException("trips_unavailable");
        String identity = j.optString("identity", "");
        List<Destination.RemoteEntry> entries = onAux(e, () -> d.list(Naming.tripsDir(c, dc)), 60_000);
        JSONArray rows = new JSONArray();
        int failed = 0;
        for (Destination.RemoteEntry en : entries) {
            if (en.folder || !(en.name.endsWith(".json") || en.name.endsWith(".json.age"))) continue;
            try {
                if (en.size > TRIP_ROW_MAX_BYTES) {
                    failed++;
                    continue;
                }
                byte[] plain = fetchPlain(e, d, en.ref, en.name, identity, TRIP_ROW_MAX_BYTES);
                JSONObject wrap = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                JSONObject trip = wrap.optJSONObject("trip");
                if (trip != null) rows.put(trip);
            } catch (IllegalStateException ise) {
                throw ise;
            } catch (Exception ex) {
                failed++;
            }
        }
        int inserted = db.importTripsJson(rows);
        return json(out, new JSONObject().put("success", true).put("found", rows.length())
                .put("imported", inserted).put("failed", failed));
    }

    private static boolean restoreSettings(Engine e, String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        SafeKeepConfig.Destination dc = e.cfg.destination(j.optString("dest"));
        if (dc == null) throw new IllegalStateException("destination_unavailable");
        Destination d = destFor(e, dc.id);
        String ref = j.optString("ref", "");
        String name = j.optString("name", "");
        if (ref.isEmpty()) throw new IllegalStateException("file_required");
        byte[] plain = fetchPlain(e, d, ref, name, j.optString("identity", ""), SETTINGS_MAX_BYTES);
        byte[] json;
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(plain))) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = gz.read(buf)) > 0) {
                if (bos.size() + n > SETTINGS_INFLATED_MAX_BYTES) throw new IllegalStateException("file_too_large");
                bos.write(buf, 0, n);
            }
            json = bos.toByteArray();
        }
        JSONObject bundle = new JSONObject(new String(json, StandardCharsets.UTF_8));
        // Anyone who can write to the storage could plant a file: apply only what an upload could carry.
        Staging.stripForCloud(bundle);
        String version = Staging.appVersion();
        String model = android.os.Build.MODEL;
        if (!j.optBoolean("apply", false)) {
            ConfigBackupService.ApplyResult v = ConfigBackupService.validateBundle(bundle, version, model);
            return json(out, new JSONObject().put("success", v.getSuccess()).put("message", v.getMessage())
                    .put("warnings", new JSONArray(v.getWarnings())).put("preview", true));
        }
        ConfigBackupService.ApplyResult r = ConfigBackupService.applyBundle(bundle, version, model);
        return json(out, new JSONObject().put("success", r.getSuccess()).put("message", r.getMessage())
                .put("warnings", new JSONArray(r.getWarnings())).put("restartRequired", r.getRestartRequired()));
    }

    // ── keys & webhooks ──────────────────────────────────────────────────────

    /** The identity is returned once for the recovery kit and never stored on the car. */
    private static boolean generateKey(OutputStream out) throws Exception {
        AgeCodec.KeyPair kp = AgeCodec.generate();
        return json(out, new JSONObject().put("success", true).put("identity", kp.identity)
                .put("recipient", kp.recipient));
    }

    private static boolean checkKey(String body, OutputStream out) throws Exception {
        String identity = new JSONObject(safe(body)).optString("identity", "");
        return json(out, new JSONObject().put("success", true).put("recipient", AgeCodec.recipientOf(identity)));
    }

    private static boolean webhookSecret(String body, OutputStream out) throws Exception {
        JSONObject j = new JSONObject(safe(body));
        String url = j.optString("url", "");
        if (url.isEmpty()) throw new IllegalStateException("url_required");
        String secret = j.optString("secret", "");
        boolean ok = SafeKeepSecrets.merge("webhooks", new JSONObject().put(url, secret.isEmpty() ? JSONObject.NULL : secret));
        return json(out, new JSONObject().put("success", ok));
    }

    // ── on-demand trip exports (work with SafeKeep OFF) ─────────────────────────

    private static boolean exportTrip(String query, OutputStream out) throws Exception {
        long id;
        try {
            id = Long.parseLong(param(query, "id"));
        } catch (NumberFormatException nfe) {
            throw new IllegalStateException("id_required");
        }
        String format = param(query, "format").toLowerCase(Locale.ROOT);
        TripRecord t = Staging.trip(id);
        if (t == null) {
            HttpResponse.sendError(out, 404, "trip not found");
            return true;
        }
        File tel = t.telemetryFilePath == null ? null : new File(t.telemetryFilePath);
        String base = "trip-" + Naming.fmt("yyyy-MM-dd-HHmm", t.startTime);
        String text, type, name;
        switch (format) {
            case "kml":
                text = TripExporter.kml(t, tel);
                type = "application/vnd.google-earth.kml+xml";
                name = base + ".kml";
                break;
            case "csv":
                text = TripExporter.csvHeader() + TripExporter.csvRow(t);
                type = "text/csv; charset=utf-8";
                name = base + ".csv";
                break;
            default:
                text = TripExporter.gpx(t, tel);
                type = "application/gpx+xml";
                name = base + ".gpx";
                break;
        }
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Content-Disposition", "attachment; filename=\"" + name + "\"");
        HttpResponse.sendBinaryNoStore(out, type, text.getBytes(StandardCharsets.UTF_8), h);
        return true;
    }

    private static boolean exportTripsCsv(String query, OutputStream out) throws Exception {
        int days = 30;
        try {
            days = Math.max(1, Math.min(36500, Integer.parseInt(param(query, "days"))));
        } catch (Exception ignored) {
        }
        TripDatabase db = Staging.tripDb();
        if (db == null) {
            HttpResponse.sendError(out, 503, "trips unavailable");
            return true;
        }
        List<TripRecord> all = new ArrayList<>();
        int offset = 0;
        while (all.size() < 20000) {
            List<TripRecord> page = db.getTrips(days, 500, offset);
            if (page.isEmpty()) break;
            for (TripRecord t : page) if (t.endTime > 0) all.add(t);
            offset += page.size();
            if (page.size() < 500) break;
        }
        Collections.reverse(all);
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("Content-Disposition", "attachment; filename=\"trips-last-" + days + "-days.csv\"");
        HttpResponse.sendBinaryNoStore(out, "text/csv; charset=utf-8",
                TripExporter.csv(all).getBytes(StandardCharsets.UTF_8), h);
        return true;
    }
}
