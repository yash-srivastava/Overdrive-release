package com.overdrive.app.safekeep.dest;

import com.overdrive.app.safekeep.SafeKeepSecrets;
import com.overdrive.app.safekeep.auth.OAuthTokens;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OneDrive (personal) inside the app's own folder ({@code Files.ReadWrite.AppFolder}).
 * Upload sessions resume from {@code nextExpectedRanges}; fragment PUTs carry no
 * Authorization header, as Graph requires.
 */
final class OneDriveDestination implements Destination {

    private static final String GRAPH = "https://graph.microsoft.com/v1.0";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final long KEEPALIVE_MS = 30L * 24 * 3600_000L;

    private final OkHttpClient http;
    private final OAuthTokens tokens;

    OneDriveDestination(String destId, OkHttpClient http) {
        this.http = http;
        JSONObject s = SafeKeepSecrets.get(destId);
        this.tokens = new OAuthTokens(destId,
                "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                s.optString("clientId", ""), null, "Files.ReadWrite.AppFolder offline_access");
    }

    @Override
    public Caps caps() {
        return new Caps("size", true, true, true, true);
    }

    private Response call(Request.Builder b) throws DestError {
        return Http.authorized(http, tokens, null, b);
    }

    private static String itemUrl(String path) {
        return GRAPH + "/me/drive/special/approot:/" + Http.encodePath(path) + ":";
    }

    @Override
    public void begin(UploadJob job) throws DestError {
        if (!job.state.optString("uploadUrl", "").isEmpty()) return;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                // fileSize makes OneDrive refuse up front when the drive can't hold the file.
                JSONObject body = new JSONObject().put("item",
                        new JSONObject().put("@microsoft.graph.conflictBehavior", "replace")
                                .put("fileSize", job.length));
                try (Response r = call(new Request.Builder().url(itemUrl(job.remotePath) + "/createUploadSession")
                        .post(RequestBody.create(body.toString(), JSON)))) {
                    String text = Http.body(r);
                    if (r.isSuccessful()) {
                        // Server clock, so the freshness check in adoptIfComplete ignores car clock skew.
                        long serverNow = parseHttpDate(r.header("Date"));
                        job.state.put("uploadUrl", new JSONObject(text).getString("uploadUrl"))
                                .put("startedAt", serverNow > 0 ? serverNow : System.currentTimeMillis());
                        job.saveState(0);
                        return;
                    }
                    if ((r.code() == 404 || r.code() == 409) && attempt == 0) {
                        ensureFolders(Http.parent(job.remotePath));
                        continue;
                    }
                    throw Http.error("OneDrive session", r, text);
                }
            } catch (DestError e) {
                throw e;
            } catch (Exception e) {
                throw DestError.network(e);
            }
        }
    }

    @Override
    public long confirmed(UploadJob job) throws DestError {
        if (job.state.optJSONObject("result") != null) return job.length;
        Request req = new Request.Builder().url(job.state.optString("uploadUrl")).get().build();
        try (Response r = job.execute(http, req)) {
            String body = Http.body(r);
            if (r.code() == 404) return adoptIfComplete(job);
            if (!r.isSuccessful()) throw Http.error("OneDrive status", r, body);
            // No ranges left: every byte arrived and only the commit is outstanding.
            return nextExpected(new JSONObject(body), job.length);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    /**
     * A 404 session may simply be finished (lost final response): adopt a same-size item, but
     * only one written by this session (an older same-size version must not pass as the upload).
     */
    private long adoptIfComplete(UploadJob job) throws DestError {
        try (Response r = call(new Request.Builder().url(itemUrl(job.remotePath)))) {
            String body = Http.body(r);
            if (r.isSuccessful()) {
                JSONObject item = new JSONObject(body);
                long modified = parseTime(item.optString("lastModifiedDateTime", ""));
                long started = job.state.optLong("startedAt", 0);
                boolean fresh = started <= 0 || modified >= started - 120_000L;
                if (item.optLong("size", -1) == job.length && fresh) {
                    job.state.put("result", item);
                    job.saveState(job.length);
                    return job.length;
                }
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception ignored) {
        }
        throw new DestError(DestError.Kind.SESSION_GONE, "OneDrive upload session expired", 404, 0);
    }

    private static long parseHttpDate(String h) {
        if (h == null || h.isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
            java.util.Date d = f.parse(h);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseTime(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private static long nextExpected(JSONObject j, long fallback) {
        JSONArray ranges = j.optJSONArray("nextExpectedRanges");
        if (ranges == null || ranges.length() == 0) return fallback;
        String first = ranges.optString(0, "");
        int dash = first.indexOf('-');
        try {
            return Long.parseLong(dash >= 0 ? first.substring(0, dash) : first);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public int chunkSize(UploadJob job, long offset, int suggested) {
        return Math.min(suggested, 50 * 1024 * 1024); // < 60 MiB and a multiple of 320 KiB
    }

    @Override
    public long put(UploadJob job, long offset, int len) throws DestError {
        long end = offset + len - 1;
        Request req = new Request.Builder().url(job.state.optString("uploadUrl"))
                .header("Content-Range", "bytes " + offset + "-" + end + "/" + job.length)
                .put(job.body(offset, len, "application/octet-stream")).build();
        try (Response r = job.execute(http, req)) {
            String body = Http.body(r);
            if (r.code() == 200 || r.code() == 201) {
                job.state.put("result", new JSONObject(body));
                job.saveState(job.length);
                return job.length;
            }
            if (r.code() == 202) return nextExpected(new JSONObject(body), offset + len);
            if (r.code() == 404) return adoptIfComplete(job);
            if (r.code() == 416) return confirmed(job);
            throw Http.error("OneDrive upload", r, body);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public RemoteRef finish(UploadJob job) throws DestError {
        JSONObject res = job.state.optJSONObject("result");
        if (res == null) res = commit(job);
        return new RemoteRef(res.optString("id"), res.optLong("size", -1), null, null, res.optString("webUrl", null));
    }

    /** All bytes are in but no item came back: commit the session explicitly (OneDrive personal). */
    private JSONObject commit(UploadJob job) throws DestError {
        try {
            JSONObject body = new JSONObject().put("name", Http.name(job.remotePath))
                    .put("@microsoft.graph.conflictBehavior", "replace")
                    .put("@microsoft.graph.sourceUrl", job.state.optString("uploadUrl"));
            try (Response r = call(new Request.Builder().url(itemUrl(job.remotePath))
                    .put(RequestBody.create(body.toString(), JSON)))) {
                String text = Http.body(r);
                if (r.isSuccessful()) {
                    JSONObject item = new JSONObject(text);
                    job.state.put("result", item);
                    job.saveState(job.length);
                    return item;
                }
                if (r.code() == 404) {
                    adoptIfComplete(job);
                    return job.state.getJSONObject("result");
                }
                throw Http.error("OneDrive commit", r, text);
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public void abandon(UploadJob job) {
        String url = job.state.optString("uploadUrl", "");
        if (url.isEmpty()) return;
        try {
            http.newCall(new Request.Builder().url(url).delete().build()).execute().close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void delete(String remoteRef) throws DestError {
        try (Response r = call(new Request.Builder().url(GRAPH + "/me/drive/items/" + remoteRef).delete())) {
            if (!r.isSuccessful() && r.code() != 404) throw Http.error("OneDrive delete", r, Http.body(r));
        }
    }

    @Override
    public JSONObject test() throws DestError {
        long t0 = System.currentTimeMillis();
        JSONObject out = new JSONObject();
        try (Response r = call(new Request.Builder().url(GRAPH + "/me/drive/special/approot"))) {
            String body = Http.body(r);
            if (!r.isSuccessful()) throw Http.error("OneDrive", r, body);
            JSONObject j = new JSONObject(body);
            JSONObject by = j.optJSONObject("createdBy");
            JSONObject user = by == null ? null : by.optJSONObject("user");
            if (user != null) out.put("account", user.optString("displayName", ""));
            out.put("folder", j.optString("name", ""));
            out.put("latencyMs", System.currentTimeMillis() - t0);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
        tokens.keepAlive(http, KEEPALIVE_MS);
        return out;
    }

    @Override
    public Quota quota() throws DestError {
        tokens.keepAlive(http, KEEPALIVE_MS);
        try (Response r = call(new Request.Builder().url(GRAPH + "/me/drive?$select=quota"))) {
            if (!r.isSuccessful()) return null;
            JSONObject q = new JSONObject(Http.body(r)).optJSONObject("quota");
            if (q == null || q.optLong("total", 0) <= 0) return null;
            return new Quota(q.optLong("used", 0), q.optLong("total", 0));
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String shareLink(String remoteRef, boolean anyone) throws DestError {
        try {
            if (!anyone) {
                try (Response r = call(new Request.Builder().url(GRAPH + "/me/drive/items/" + remoteRef + "?$select=webUrl"))) {
                    return r.isSuccessful() ? new JSONObject(Http.body(r)).optString("webUrl", null) : null;
                }
            }
            JSONObject body = new JSONObject().put("type", "view").put("scope", "anonymous");
            try (Response r = call(new Request.Builder().url(GRAPH + "/me/drive/items/" + remoteRef + "/createLink")
                    .post(RequestBody.create(body.toString(), JSON)))) {
                String text = Http.body(r);
                if (!r.isSuccessful()) throw Http.error("OneDrive share", r, text);
                JSONObject link = new JSONObject(text).optJSONObject("link");
                return link == null ? null : link.optString("webUrl", null);
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public List<RemoteEntry> list(String dirPath) throws DestError {
        List<RemoteEntry> out = new ArrayList<>();
        String url = itemUrl(dirPath) + "/children?$select=id,name,size,folder&$top=999";
        while (url != null && out.size() < 20000) {
            try (Response r = call(new Request.Builder().url(url))) {
                String body = Http.body(r);
                if (r.code() == 404) return out;
                if (!r.isSuccessful()) throw Http.error("OneDrive list", r, body);
                JSONObject j = new JSONObject(body);
                JSONArray v = j.optJSONArray("value");
                if (v != null) for (int i = 0; i < v.length(); i++) {
                    JSONObject it = v.getJSONObject(i);
                    out.add(new RemoteEntry(it.optString("name"), it.optString("id"), it.optLong("size", 0),
                            it.has("folder")));
                }
                url = j.optString("@odata.nextLink", null);
            } catch (DestError e) {
                throw e;
            } catch (Exception e) {
                throw DestError.network(e);
            }
        }
        return out;
    }

    @Override
    public byte[] download(String remoteRef, long maxBytes) throws DestError {
        try (Response r = call(new Request.Builder().url(GRAPH + "/me/drive/items/" + remoteRef + "/content"))) {
            if (!r.isSuccessful()) throw Http.error("OneDrive download", r, Http.body(r));
            return Http.bytes(r, maxBytes);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    private void ensureFolders(String path) throws DestError {
        String parent = "";
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            String url = parent.isEmpty()
                    ? GRAPH + "/me/drive/special/approot/children"
                    : itemUrl(parent) + "/children";
            try {
                JSONObject body = new JSONObject().put("name", seg).put("folder", new JSONObject())
                        .put("@microsoft.graph.conflictBehavior", "fail");
                try (Response r = call(new Request.Builder().url(url).post(RequestBody.create(body.toString(), JSON)))) {
                    if (!r.isSuccessful() && r.code() != 409) throw Http.error("OneDrive folder", r, Http.body(r));
                }
            } catch (DestError e) {
                throw e;
            } catch (Exception e) {
                throw DestError.network(e);
            }
            parent = parent.isEmpty() ? seg : parent + "/" + seg;
        }
    }
}
