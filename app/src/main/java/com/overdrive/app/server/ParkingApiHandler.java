package com.overdrive.app.server;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.parking.ParkingConfig;
import com.overdrive.app.parking.ParkingController;
import com.overdrive.app.parking.ParkingNeighbour;
import com.overdrive.app.parking.ParkingSession;
import com.overdrive.app.parking.ParkingStore;
import com.overdrive.app.parking.signage.TfliteTextOcrBackend;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Parking Intelligence HTTP API.
 *
 * <pre>
 * GET    /api/parking/status                 feature state + open / latest session
 * GET    /api/parking/current                open session (null when driving) + latest closed
 * GET    /api/parking/config                 parking section
 * POST   /api/parking/config                 merge {enabled, snapshots, neighbours, signage, retentionDays, storageCapMb}
 * GET    /api/parking/sessions?from&to&limit&offset
 * GET    /api/parking/sessions/{id}          session + neighbours + sentry events + still URLs
 * DELETE /api/parking/sessions/{id}
 * POST   /api/parking/sessions/{id}/signage  re-read garage signage (v2)
 * GET    /parking/asset/{id}/{file}.jpg      stills / frames (JWT, or ?t= signed token for push banners)
 * </pre>
 *
 * Read paths work whether or not the feature is running: with the controller
 * stopped the store is opened transiently for the request, so history stays
 * browsable after the master switch is turned off.
 */
public final class ParkingApiHandler {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_\\-]{1,80}");
    private static final Pattern SAFE_ASSET = Pattern.compile("[A-Za-z0-9_\\-]{1,120}\\.jpg");
    /** Base dir for assets; mirrors DaemonParkingEnvironment.PARKING_DIR. */
    private static final String PARKING_DIR = "/storage/emulated/0/Overdrive/parking";

    private ParkingApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String[] pq = splitPathAndQuery(path);
        String p = pq[0];
        Map<String, String> q = parseQuery(pq[1]);
        try {
            if (p.startsWith("/parking/asset/")) {
                return serveAsset(p.substring("/parking/asset/".length()), out);
            }
            if (p.equals("/api/parking/status") && "GET".equals(method)) {
                JSONObject r = new JSONObject();
                fillStatus(r);
                r.put("success", true);
                HttpResponse.sendJson(out, r.toString());
                return true;
            }
            if (p.equals("/api/parking/current") && "GET".equals(method)) {
                JSONObject r = new JSONObject();
                fillStatus(r);
                r.put("success", true);
                HttpResponse.sendJson(out, r.toString());
                return true;
            }
            if (p.equals("/api/parking/config")) {
                if ("POST".equals(method) || "PUT".equals(method)) return updateConfig(body, out);
                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("config", readConfig().toJson());
                HttpResponse.sendJson(out, r.toString());
                return true;
            }
            if (p.equals("/api/parking/sessions") && "GET".equals(method)) {
                return listSessions(q, out);
            }
            if (p.startsWith("/api/parking/sessions/")) {
                String rest = p.substring("/api/parking/sessions/".length());
                String[] parts = rest.split("/");
                String id = URLDecoder.decode(parts[0], "UTF-8");
                if (!SAFE_SEGMENT.matcher(id).matches()) {
                    HttpResponse.sendError(out, 400, "Bad session id");
                    return true;
                }
                if (parts.length == 1) {
                    if ("GET".equals(method)) return sessionDetail(id, out);
                    if ("DELETE".equals(method)) return deleteSession(id, out);
                } else if (parts.length == 2 && "signage".equals(parts[1]) && "POST".equals(method)) {
                    return requeueSignage(id, out);
                }
            }
            HttpResponse.sendError(out, 404, "Not found");
            return true;
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, e.getMessage() == null ? "error" : e.getMessage());
            return true;
        }
    }

    // ==================== STATUS (shared with the IPC /where path) ====================

    /** Fill {@code enabled, running, config, current, latest, signageModels} into {@code r}. */
    public static void fillStatus(JSONObject r) throws Exception {
        ParkingConfig cfg = readConfig();
        ParkingController c = controller();
        r.put("enabled", cfg.enabled);
        r.put("running", c != null && c.isStarted());
        r.put("config", cfg.toJson());
        try {
            // Same asset resolution as the daemon's loader (APK-backed assets
            // first), so the UI's "models present" hint matches what a read sees.
            r.put("signageModels", TfliteTextOcrBackend.modelsPresent(
                    com.overdrive.app.parking.DaemonParkingEnvironment.modelContext(CameraDaemon.getAppContext())));
        } catch (Throwable t) {
            r.put("signageModels", false);
        }
        // One store round-trip (a disabled feature answers from a transient
        // store: open it once per request, not three times).
        ParkingSession[] pair = withStore(st -> {
            ParkingSession cur = st.getOpenSession();
            ParkingSession prior = null;
            for (ParkingSession s : st.listSessions(0, 0, 2, 0)) {
                if (cur == null || !s.sessionId.equals(cur.sessionId)) { prior = s; break; }
            }
            return new ParkingSession[] {cur, prior};
        });
        ParkingSession current = pair == null ? null : pair[0];
        ParkingSession latest = pair == null ? null : pair[1];
        r.put("current", current == null ? JSONObject.NULL : withLiveEnergy(current, current.toJson()));
        r.put("latest", latest == null ? JSONObject.NULL : latest.toJson());
    }

    /**
     * Attach the live SoC/energy view to an OPEN session's JSON. Display-only
     * enrichment (replaces the start-bookend-only "energy" object with one that
     * carries startSoc + liveSoc + socDelta); the DB row is untouched, and a
     * stopped controller leaves the JSON as stored.
     */
    private static JSONObject withLiveEnergy(ParkingSession s, JSONObject json) {
        try {
            ParkingController c = controller();
            if (c != null && s != null && s.isOpen()) {
                JSONObject live = c.liveEnergyJson(s);
                if (live != null) json.put("energy", live);
            }
        } catch (Throwable ignored) {}
        return json;
    }

    // ==================== CONFIG ====================

    private static ParkingConfig readConfig() {
        try {
            JSONObject cfg = UnifiedConfigManager.loadConfig();
            return ParkingConfig.fromSection(cfg == null ? null : cfg.optJSONObject(ParkingConfig.SECTION));
        } catch (Throwable t) {
            return ParkingConfig.disabled();
        }
    }

    private static boolean updateConfig(String body, OutputStream out) throws Exception {
        JSONObject req = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        Map<String, Object> values = new HashMap<>();
        for (String k : new String[] {"enabled", "snapshots", "neighbours", "signage"}) {
            if (req.has(k)) values.put(k, req.optBoolean(k, false));
        }
        if (req.has("retentionDays")) values.put("retentionDays", Math.max(7, Math.min(730, req.optInt("retentionDays", 90))));
        if (req.has("storageCapMb")) values.put("storageCapMb", Math.max(50, Math.min(4096, req.optInt("storageCapMb", 300))));
        if (req.has("endTrigger")) values.put("endTrigger",
                ParkingConfig.normalizeEndTrigger(req.optString("endTrigger", ParkingConfig.END_TRIGGER_RETURN)));
        if (values.isEmpty()) {
            HttpResponse.sendJsonError(out, "No recognised keys");
            return true;
        }
        boolean ok = UnifiedConfigManager.updateValues(ParkingConfig.SECTION, values);
        JSONObject r = new JSONObject();
        r.put("success", ok);
        r.put("config", readConfig().toJson());
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    // ==================== SESSIONS ====================

    private static boolean listSessions(Map<String, String> q, OutputStream out) throws Exception {
        final long from = optLong(q.get("from"), 0L);
        final long to = optLong(q.get("to"), 0L);
        final int limit = (int) Math.max(1, Math.min(200, optLong(q.get("limit"), 50L)));
        final int offset = (int) Math.max(0, optLong(q.get("offset"), 0L));
        List<ParkingSession> list = withStore(st -> st.listSessions(from, to, limit, offset));
        Integer total = withStore(ParkingStore::countSessions);
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("sessions", ParkingSession.toJsonArray(list));
        r.put("total", total == null ? 0 : total);
        r.put("enabled", readConfig().enabled);
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    private static boolean sessionDetail(String id, OutputStream out) throws Exception {
        ParkingSession s = withStore(st -> st.getSession(id));
        if (s == null) {
            HttpResponse.sendError(out, 404, "Session not found");
            return true;
        }
        List<ParkingNeighbour> neighbours = withStore(st -> st.listNeighbours(id));
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("session", withLiveEnergy(s, s.toJson()));
        JSONArray n = new JSONArray();
        if (neighbours != null) for (ParkingNeighbour x : neighbours) n.put(x.toJson());
        r.put("neighbours", n);
        r.put("events", eventsFor(s));
        r.put("assets", assetsFor(s));
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    /** Sentry recordings stamped with the session id; time-window fallback for unstamped clips. */
    private static JSONArray eventsFor(ParkingSession s) {
        JSONArray arr = new JSONArray();
        try {
            RecordingsIndex idx = RecordingsIndex.getInstance();
            if (!idx.isAvailable()) return arr;
            RecordingsIndex.Filter f = new RecordingsIndex.Filter();
            f.type = "sentry";
            f.parkingSessionId = s.sessionId;
            List<JSONObject> rows = idx.queryRecordings(f, 300, 0);
            if (rows.isEmpty()) {
                RecordingsIndex.Filter t = new RecordingsIndex.Filter();
                t.type = "sentry";
                t.fromMs = s.startedMs;
                t.toMs = s.endedMs > 0 ? s.endedMs : System.currentTimeMillis();
                rows = idx.queryRecordings(t, 300, 0);
            }
            for (JSONObject row : rows) arr.put(row);
        } catch (Throwable ignored) {}
        return arr;
    }

    /** Which stills exist on disk, as asset URLs (fetched with the page's JWT). */
    private static JSONObject assetsFor(ParkingSession s) throws Exception {
        JSONObject a = new JSONObject();
        File dir = new File(PARKING_DIR, s.sessionId);
        for (String prefix : new String[] {"arrived", "returned"}) {
            JSONObject set = new JSONObject();
            boolean any = false;
            for (String side : new String[] {"front", "right", "rear", "left", "mosaic"}) {
                File f = new File(dir, prefix + "_" + side + ".jpg");
                if (RemovableStorageFs.isFile(f)) {
                    set.put(side, "/parking/asset/" + s.sessionId + "/" + f.getName());
                    any = true;
                }
            }
            if (any) a.put(prefix, set);
        }
        return a;
    }

    private static boolean deleteSession(String id, OutputStream out) throws Exception {
        ParkingController c = controller();
        boolean ok;
        if (c != null && c.isStarted()) {
            ok = c.deleteSession(id);
        } else {
            ParkingController.deleteAssets(new File(PARKING_DIR, id));
            Boolean del = withStore(st -> st.deleteSession(id));
            ok = del != null && del;
        }
        JSONObject r = new JSONObject();
        r.put("success", ok);
        if (!ok) r.put("error", "Session is open or unknown");
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    private static boolean requeueSignage(String id, OutputStream out) throws Exception {
        ParkingController c = controller();
        boolean ok = c != null && c.isStarted() && c.requestSignage(id);
        JSONObject r = new JSONObject();
        r.put("success", ok);
        if (!ok) r.put("error", "Parking Intelligence is not running");
        HttpResponse.sendJson(out, r.toString());
        return true;
    }

    // ==================== ASSETS ====================

    private static boolean serveAsset(String rest, OutputStream out) throws Exception {
        String[] parts = rest.split("/");
        if (parts.length != 2) { HttpResponse.sendError(out, 404, "Not found"); return true; }
        String sid = URLDecoder.decode(parts[0], "UTF-8");
        String name = URLDecoder.decode(parts[1], "UTF-8");
        if (!SAFE_SEGMENT.matcher(sid).matches() || !SAFE_ASSET.matcher(name).matches()) {
            HttpResponse.sendError(out, 400, "Bad asset path");
            return true;
        }
        File base = new File(PARKING_DIR).getCanonicalFile();
        File f = new File(new File(base, sid), name).getCanonicalFile();
        if (!f.getPath().startsWith(base.getPath() + File.separator) || !RemovableStorageFs.isFile(f)) {
            HttpResponse.sendError(out, 404, "Not found");
            return true;
        }
        HttpResponse.sendImage(out, f, "image/jpeg", "private, max-age=3600");
        return true;
    }

    // ==================== PLUMBING ====================

    private static ParkingController controller() {
        try { return CameraDaemon.getParkingController(); } catch (Throwable t) { return null; }
    }

    /**
     * Run a read against the live store when the controller is started, else
     * against a transient store opened for this call. Returns null on failure.
     */
    private static <T> T withStore(Function<ParkingStore, T> fn) {
        ParkingController c = controller();
        ParkingStore live = c == null ? null : c.store();
        if (live != null && live.isOpen()) {
            try { return fn.apply(live); } catch (Throwable t) { return null; }
        }
        // Feature off: read the history from a transient store — but never CREATE
        // the database from a GET. No file ⇒ there is no history to show.
        if (!RemovableStorageFs.isFile(new File(ParkingStore.DEFAULT_DB_PATH + ".mv.db"))) return null;
        ParkingStore tmp = new ParkingStore();
        try {
            if (!tmp.open()) return null;
            return fn.apply(tmp);
        } catch (Throwable t) {
            return null;
        } finally {
            tmp.close();
        }
    }

    private static long optLong(String v, long def) {
        if (v == null || v.isEmpty()) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String[] splitPathAndQuery(String path) {
        int i = path.indexOf('?');
        return i < 0 ? new String[] {path, ""} : new String[] {path.substring(0, i), path.substring(i + 1)};
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            try {
                if (eq < 0) m.put(URLDecoder.decode(pair, "UTF-8"), "");
                else m.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return m;
    }
}
