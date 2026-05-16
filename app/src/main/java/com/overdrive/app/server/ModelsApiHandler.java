package com.overdrive.app.server;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Models API Handler — manages 3D vehicle models for the vehicle-control page.
 *
 * The default model (seal.glb) ships inside the APK. Other BYD models are
 * downloaded on demand from a public GitHub release and persisted to
 * {@link #MODELS_DIR} so subsequent loads are offline.
 *
 * Endpoints:
 *   GET  /api/models/list              — manifest entries + per-model download status
 *   POST /api/models/download?id=ID    — start a background download (idempotent)
 *   GET  /api/models/status?id=ID      — poll progress for an in-flight download
 *
 * Downloads run on a small dedicated pool. Progress state lives in {@link #downloads}
 * and is keyed by model id. Files are written to a .tmp sibling first and renamed
 * atomically only after the SHA-256 matches the manifest, so a half-download never
 * pollutes the cache and a hash mismatch never replaces a good file.
 */
public class ModelsApiHandler {

    private static final String TAG = "ModelsApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final String MODELS_DIR = "/data/local/tmp/overdrive/models";

    // Manifest path inside the extracted web assets — bundled copy ships with the APK
    // and is the offline-safe baseline.
    private static final String MANIFEST_BUNDLED_PATH = "/data/local/tmp/web/shared/models/manifest.json";
    // Cached remote manifest. Persisted across app updates so an offline boot still
    // shows the most recently-seen model list. Promoted in front of the bundled copy
    // by readManifest() whenever its top-level "version" is newer.
    private static final String MANIFEST_REMOTE_CACHE = "/data/local/tmp/overdrive/models/manifest.json";
    // GitHub release manifest URL — same baseUrl convention as the GLBs themselves.
    private static final String MANIFEST_REMOTE_URL =
            "https://github.com/yash-srivastava/Overdrive-release/releases/download/models-v1/manifest.json";

    // Two concurrent downloads is plenty — the BYD AVN's storage and LTE both serialize anyway.
    private static final ExecutorService downloadExec = Executors.newFixedThreadPool(2);

    // Per-model download state. Concurrent because handler threads read while downloader writes.
    private static final ConcurrentHashMap<String, DownloadState> downloads = new ConcurrentHashMap<>();

    // Last successful manifest fetch's ETag — used to send If-None-Match on subsequent
    // refreshes. In-memory only; lost on process restart, which just means one extra
    // full fetch on the next refresh (still cheap, ~3KB body). Volatile because the
    // refresh handler can run concurrently from multiple WebView clients.
    private static volatile String lastManifestEtag = null;
    private static volatile long lastSuccessfulRefreshMs = 0;

    private static class DownloadState {
        volatile String state = "idle";        // idle | downloading | done | error
        volatile long bytesDownloaded = 0;
        volatile long totalBytes = 0;
        volatile String error = null;

        JSONObject toJson() throws Exception {
            JSONObject j = new JSONObject();
            j.put("state", state);
            j.put("bytesDownloaded", bytesDownloaded);
            j.put("totalBytes", totalBytes);
            int pct = totalBytes > 0 ? (int) ((bytesDownloaded * 100) / totalBytes) : 0;
            j.put("percent", pct);
            if (error != null) j.put("error", error);
            return j;
        }
    }

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        // Strip query string for matching, keep raw `path` for parsing ?id=
        String pathOnly = path.contains("?") ? path.substring(0, path.indexOf("?")) : path;

        if (pathOnly.equals("/api/models/list") && method.equals("GET")) {
            handleList(out);
            return true;
        }
        // Accept GET as well as POST. The endpoint is idempotent (in-flight
        // downloads return {ok:true,inFlight:true} on subsequent calls) and
        // making it method-agnostic protects against stale cached JS, browser
        // prefetchers, and ad-hoc curl debugging.
        if (pathOnly.equals("/api/models/download")
                && (method.equals("POST") || method.equals("GET"))) {
            handleDownload(out, queryParam(path, "id"));
            return true;
        }
        if (pathOnly.equals("/api/models/status") && method.equals("GET")) {
            handleStatus(out, queryParam(path, "id"));
            return true;
        }
        // User's persisted model + color selection. Stored in unified config so the
        // same selection follows the user across AVN and phone-over-tunnel access.
        if (pathOnly.equals("/api/models/selected") && method.equals("GET")) {
            handleGetSelected(out);
            return true;
        }
        if (pathOnly.equals("/api/models/selected") && method.equals("POST")) {
            handleSetSelected(out, body);
            return true;
        }
        // Effective manifest (cached-remote if newer, else bundled). The JS calls this
        // instead of reading the bundled .json directly so users with a remote-cached
        // copy see the up-to-date model list across reloads.
        if (pathOnly.equals("/api/models/manifest") && method.equals("GET")) {
            handleGetManifest(out);
            return true;
        }
        // Fire-and-forget revalidate. JS calls this on every page load; we fetch
        // the remote manifest, replace the cache if version > current, and respond
        // with whichever wins. Network failures fall through to the cached/bundled
        // copy so this never blocks the UI.
        if (pathOnly.equals("/api/models/manifest/refresh") && method.equals("POST")) {
            handleRefreshManifest(out);
            return true;
        }
        return false;
    }

    private static void handleGetManifest(OutputStream out) throws Exception {
        JSONObject manifest = readManifest();
        if (manifest == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_no_manifest"));
            return;
        }
        HttpResponse.sendJson(out, manifest.toString());
    }

    /** Outcome of one remote-manifest fetch attempt. */
    private static class RemoteFetchResult {
        enum Status { OK, NOT_MODIFIED, FAILED }
        final Status status;
        final JSONObject manifest;  // populated only on OK
        final String etag;          // populated when server returned one (OK or NOT_MODIFIED)
        RemoteFetchResult(Status s, JSONObject m, String e) { status = s; manifest = m; etag = e; }
    }

    private static void handleRefreshManifest(OutputStream out) throws Exception {
        JSONObject before = readManifest();
        int beforeVersion = before != null ? before.optInt("version", 0) : 0;
        RemoteFetchResult result = fetchRemoteManifest(lastManifestEtag);
        boolean updated = false;
        boolean stale = false;

        if (result.status == RemoteFetchResult.Status.OK && result.manifest != null) {
            int remoteVersion = result.manifest.optInt("version", 0);
            // Strictly-newer rule: equal versions mean no-op, older means someone rolled
            // the release back and we shouldn't clobber a locally-cached newer copy.
            if (remoteVersion > beforeVersion) {
                if (writeRemoteCache(result.manifest)) {
                    updated = true;
                    logger.info(TAG + ": manifest updated v" + beforeVersion + " -> v" + remoteVersion);
                }
            }
            if (result.etag != null) lastManifestEtag = result.etag;
            lastSuccessfulRefreshMs = System.currentTimeMillis();
        } else if (result.status == RemoteFetchResult.Status.NOT_MODIFIED) {
            // 304 — server confirmed our cached copy is current. No body transferred,
            // no cache write. Successful refresh, just nothing to update.
            lastSuccessfulRefreshMs = System.currentTimeMillis();
        } else {
            // FAILED — network down, GitHub unreachable, malformed response, etc.
            // Surface to the JS so the dropdown can show a stale indicator. The user
            // still has a working app; this just means new models may not be visible.
            stale = true;
        }

        JSONObject response = new JSONObject();
        response.put("updated", updated);
        response.put("stale", stale);
        response.put("version", updated ? result.manifest.optInt("version", 0) : beforeVersion);
        response.put("lastSuccessfulRefreshMs", lastSuccessfulRefreshMs);
        // Return the manifest only when it actually changed — saves payload on the
        // common no-change path. JS already has the current manifest from /api/models/manifest.
        if (updated) response.put("manifest", result.manifest);
        HttpResponse.sendJson(out, response.toString());
    }

    private static RemoteFetchResult fetchRemoteManifest(String ifNoneMatch) {
        HttpURLConnection conn = null;
        try {
            conn = openWithRedirects(MANIFEST_REMOTE_URL, 5, ifNoneMatch);
            int code = conn.getResponseCode();

            if (code == 304) {
                // Caller's cached copy is current. No body to read.
                return new RemoteFetchResult(RemoteFetchResult.Status.NOT_MODIFIED, null, ifNoneMatch);
            }
            if (code / 100 != 2) {
                logger.warn(TAG + ": remote manifest HTTP " + code);
                return new RemoteFetchResult(RemoteFetchResult.Status.FAILED, null, null);
            }

            String etag = conn.getHeaderField("ETag");
            try (InputStream in = conn.getInputStream();
                 java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                String json = new String(baos.toByteArray(), "UTF-8");
                JSONObject parsed = (JSONObject) new JSONTokener(json).nextValue();
                if (!parsed.has("version") || parsed.optJSONArray("models") == null) {
                    logger.warn(TAG + ": remote manifest missing required fields");
                    return new RemoteFetchResult(RemoteFetchResult.Status.FAILED, null, null);
                }
                return new RemoteFetchResult(RemoteFetchResult.Status.OK, parsed, etag);
            }
        } catch (Exception e) {
            logger.warn(TAG + ": remote manifest fetch failed: " + e.getMessage());
            return new RemoteFetchResult(RemoteFetchResult.Status.FAILED, null, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean writeRemoteCache(JSONObject manifest) {
        File cache = new File(MANIFEST_REMOTE_CACHE);
        File parent = cache.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        File tmp = new File(parent, cache.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(manifest.toString(2).getBytes("UTF-8"));
            fos.getFD().sync();
            if (cache.exists()) cache.delete();
            if (!tmp.renameTo(cache)) {
                tmp.delete();
                return false;
            }
            cache.setReadable(true, false);
            return true;
        } catch (Exception e) {
            logger.warn(TAG + ": writeRemoteCache failed: " + e.getMessage());
            tmp.delete();
            return false;
        }
    }

    private static void handleGetSelected(OutputStream out) throws Exception {
        JSONObject vehicle = UnifiedConfigManager.getVehicle();
        // Validate modelId against the manifest — if a previously-saved model has been
        // removed in a later release, fall back to the manifest's default rather than
        // returning a dead id that will fail at load time.
        JSONObject manifest = readManifest();
        String defaultId = manifest != null ? manifest.optString("default", "seal") : "seal";
        String modelId = vehicle.optString("modelId", defaultId);
        if (manifest != null && findModel(manifest, modelId) == null) {
            modelId = defaultId;
        }
        JSONObject response = new JSONObject();
        response.put("modelId", modelId);
        response.put("color", vehicle.optString("color", "#E8E8EC"));
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleSetSelected(OutputStream out, String body) throws Exception {
        if (body == null || body.isEmpty()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_empty_body"));
            return;
        }
        JSONObject incoming;
        try {
            incoming = (JSONObject) new JSONTokener(body).nextValue();
        } catch (Exception e) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_invalid_json"));
            return;
        }
        JSONObject patch = new JSONObject();
        if (incoming.has("modelId")) {
            String id = incoming.optString("modelId");
            // Validate against manifest so a typo or stale client can't poison the config.
            JSONObject manifest = readManifest();
            if (manifest != null && findModel(manifest, id) == null) {
                HttpResponse.sendJsonError(out, Messages.get("errors.models_unknown_id_with_id", id));
                return;
            }
            patch.put("modelId", id);
        }
        if (incoming.has("color")) {
            String color = incoming.optString("color", "");
            // Sanity-check: must be a 7-char hex color (#RRGGBB). Anything else gets rejected
            // so the value going to localStorage callers is always renderable.
            if (!color.matches("^#[0-9A-Fa-f]{6}$")) {
                HttpResponse.sendJsonError(out, Messages.get("errors.models_invalid_color"));
                return;
            }
            patch.put("color", color);
        }
        if (patch.length() == 0) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_nothing_to_update"));
            return;
        }
        boolean ok = UnifiedConfigManager.setVehicle(patch);
        if (!ok) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_persist_failed"));
            return;
        }
        HttpResponse.sendJson(out, "{\"ok\":true}");
    }

    /** Returns true when the GLB exists in the persistent download cache. */
    public static File cachedModelFile(String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("..")) return null;
        File f = new File(MODELS_DIR, fileName);
        return f.exists() && f.isFile() ? f : null;
    }

    private static void handleList(OutputStream out) throws Exception {
        JSONObject manifest = readManifest();
        if (manifest == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_manifest_unavailable"));
            return;
        }
        JSONArray models = manifest.optJSONArray("models");
        if (models == null) models = new JSONArray();

        JSONArray result = new JSONArray();
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            JSONObject o = new JSONObject();
            String id = m.optString("id");
            String file = m.optString("file");
            o.put("id", id);
            o.put("name", m.optString("name", id));
            o.put("file", file);
            o.put("sizeBytes", m.optLong("sizeBytes", 0));
            o.put("bundled", m.optBoolean("bundled", false));

            File cached = cachedModelFile(file);
            o.put("downloaded", m.optBoolean("bundled", false) || cached != null);
            o.put("cachedSizeBytes", cached != null ? cached.length() : 0);

            DownloadState ds = downloads.get(id);
            if (ds != null) o.put("status", ds.toJson());

            result.put(o);
        }

        JSONObject response = new JSONObject();
        response.put("models", result);
        response.put("default", manifest.optString("default", "seal"));
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleStatus(OutputStream out, String id) throws Exception {
        if (id == null || id.isEmpty()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_id_required"));
            return;
        }
        DownloadState ds = downloads.get(id);
        JSONObject response = new JSONObject();
        if (ds == null) {
            // No record — either never started or evicted; report as idle.
            response.put("state", "idle");
            response.put("percent", 0);
        } else {
            response = ds.toJson();
        }
        // Always include up-to-date downloaded flag so JS doesn't have to call /list separately.
        JSONObject manifest = readManifest();
        if (manifest != null) {
            JSONObject entry = findModel(manifest, id);
            if (entry != null) {
                File cached = cachedModelFile(entry.optString("file"));
                response.put("downloaded", entry.optBoolean("bundled", false) || cached != null);
            }
        }
        HttpResponse.sendJson(out, response.toString());
    }

    private static void handleDownload(OutputStream out, String id) throws Exception {
        if (id == null || id.isEmpty()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_id_required"));
            return;
        }
        JSONObject manifest = readManifest();
        if (manifest == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_manifest_unavailable"));
            return;
        }
        JSONObject entry = findModel(manifest, id);
        if (entry == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.models_unknown_id_with_id", id));
            return;
        }

        // Already-cached short-circuit — surface as "done" so the JS poller can resolve immediately
        // even if it bypassed the /list check (e.g. user mashed the dropdown).
        File cached = cachedModelFile(entry.optString("file"));
        if (cached != null || entry.optBoolean("bundled", false)) {
            DownloadState ds = new DownloadState();
            ds.state = "done";
            ds.totalBytes = cached != null ? cached.length() : entry.optLong("sizeBytes", 0);
            ds.bytesDownloaded = ds.totalBytes;
            downloads.put(id, ds);
            HttpResponse.sendJson(out, "{\"ok\":true,\"alreadyCached\":true}");
            return;
        }

        // Idempotency: if a download is already in flight for this id, just acknowledge.
        DownloadState existing = downloads.get(id);
        if (existing != null && "downloading".equals(existing.state)) {
            HttpResponse.sendJson(out, "{\"ok\":true,\"inFlight\":true}");
            return;
        }

        DownloadState fresh = new DownloadState();
        fresh.state = "downloading";
        fresh.totalBytes = entry.optLong("sizeBytes", 0);
        downloads.put(id, fresh);

        String baseUrl = manifest.optString("baseUrl", "");
        String fileName = entry.optString("file");
        String expectedSha = entry.optString("sha256", "");
        long expectedSize = entry.optLong("sizeBytes", 0);

        downloadExec.execute(() -> doDownload(id, baseUrl + fileName, fileName, expectedSize, expectedSha, fresh));

        HttpResponse.sendJson(out, "{\"ok\":true,\"started\":true}");
    }

    private static void doDownload(String id, String url, String fileName, long expectedSize,
                                   String expectedSha, DownloadState ds) {
        File modelsDir = new File(MODELS_DIR);
        if (!modelsDir.exists() && !modelsDir.mkdirs()) {
            ds.state = "error";
            ds.error = "Cannot create " + MODELS_DIR;
            logger.warn(TAG + ": mkdir failed for " + MODELS_DIR);
            return;
        }

        File tmp = new File(modelsDir, fileName + ".tmp");
        File dest = new File(modelsDir, fileName);

        HttpURLConnection conn = null;
        try {
            // Follow GitHub's redirect (releases hand off to objects.githubusercontent.com).
            conn = openWithRedirects(url, 5);
            if (conn.getResponseCode() / 100 != 2) {
                ds.state = "error";
                ds.error = "HTTP " + conn.getResponseCode();
                return;
            }
            long contentLength = conn.getContentLengthLong();
            if (contentLength > 0) ds.totalBytes = contentLength;

            MessageDigest md = MessageDigest.getInstance("SHA-256");

            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    md.update(buf, 0, n);
                    ds.bytesDownloaded += n;
                }
                fos.getFD().sync();
            }

            // Verify size + hash before promoting the tmp file. Either failure is treated
            // as a corrupted download — the partial file is dropped and the user can retry.
            if (expectedSize > 0 && tmp.length() != expectedSize) {
                ds.state = "error";
                ds.error = "Size mismatch (got " + tmp.length() + ", expected " + expectedSize + ")";
                tmp.delete();
                return;
            }
            if (expectedSha != null && !expectedSha.isEmpty()) {
                String actualSha = bytesToHex(md.digest());
                if (!actualSha.equalsIgnoreCase(expectedSha)) {
                    ds.state = "error";
                    ds.error = "SHA-256 mismatch";
                    logger.warn(TAG + ": hash mismatch for " + id + ": got=" + actualSha + " want=" + expectedSha);
                    tmp.delete();
                    return;
                }
            }

            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) {
                ds.state = "error";
                ds.error = "Rename failed";
                tmp.delete();
                return;
            }
            dest.setReadable(true, false);
            ds.state = "done";
            logger.info(TAG + ": downloaded " + id + " -> " + dest.getAbsolutePath() + " (" + dest.length() + " bytes)");
        } catch (Exception e) {
            ds.state = "error";
            ds.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            logger.warn(TAG + ": download failed for " + id + ": " + e.getMessage());
            tmp.delete();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static HttpURLConnection openWithRedirects(String urlStr, int maxRedirects) throws Exception {
        return openWithRedirects(urlStr, maxRedirects, null);
    }

    /**
     * Manual redirect chain. We resend If-None-Match on every hop because GitHub
     * 302s to release-assets.githubusercontent.com which is the host that actually
     * checks the ETag — the github.com origin doesn't see that header otherwise.
     */
    private static HttpURLConnection openWithRedirects(String urlStr, int maxRedirects,
                                                       String ifNoneMatch) throws Exception {
        // HttpURLConnection only follows redirects within the same protocol;
        // GitHub releases redirect from https://github.com/... to
        // https://release-assets.githubusercontent.com/... — same protocol, but
        // some Android versions still drop the hop. Follow them manually for safety.
        for (int i = 0; i < maxRedirects; i++) {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Overdrive/1.0");
            if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) {
                conn.setRequestProperty("If-None-Match", ifNoneMatch);
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new Exception("Redirect with no Location");
                }
                urlStr = loc;
                continue;
            }
            return conn;
        }
        throw new Exception("Too many redirects");
    }

    /**
     * Effective manifest = whichever has the higher "version" between the
     * APK-bundled copy and the remote-cached copy. This way an APK update that
     * ships a newer bundled manifest still wins over an older cache, and a
     * remote update wins over the bundled baseline. If both fail to parse we
     * return null so callers know to fail gracefully.
     */
    private static JSONObject readManifest() {
        JSONObject bundled = readManifestFile(new File(MANIFEST_BUNDLED_PATH));
        JSONObject cached  = readManifestFile(new File(MANIFEST_REMOTE_CACHE));
        if (bundled == null) return cached;
        if (cached  == null) return bundled;
        int bv = bundled.optInt("version", 0);
        int cv = cached.optInt("version", 0);
        return cv > bv ? cached : bundled;
    }

    private static JSONObject readManifestFile(File f) {
        if (!f.exists()) return null;
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int totalRead = 0;
            while (totalRead < buf.length) {
                int n = fis.read(buf, totalRead, buf.length - totalRead);
                if (n == -1) break;
                totalRead += n;
            }
            String json = new String(buf, 0, totalRead, "UTF-8");
            JSONObject parsed = (JSONObject) new JSONTokener(json).nextValue();
            // Reject manifests that don't declare a version — without one we can't
            // make the bundled-vs-cached precedence call.
            if (!parsed.has("version") || parsed.optJSONArray("models") == null) {
                logger.warn(TAG + ": manifest at " + f.getAbsolutePath() + " missing required fields");
                return null;
            }
            return parsed;
        } catch (Exception e) {
            logger.warn(TAG + ": failed to read manifest at " + f.getAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }

    private static JSONObject findModel(JSONObject manifest, String id) {
        JSONArray arr = manifest.optJSONArray("models");
        if (arr == null) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null && id.equals(m.optString("id"))) return m;
        }
        return null;
    }

    private static String queryParam(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (key.equals(pair.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
