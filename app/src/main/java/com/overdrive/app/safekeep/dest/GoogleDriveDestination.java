package com.overdrive.app.safekeep.dest;

import com.overdrive.app.safekeep.Ledger;
import com.overdrive.app.safekeep.SafeKeepSecrets;
import com.overdrive.app.safekeep.auth.OAuthTokens;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Google Drive with the non-sensitive {@code drive.file} scope (the app sees only files it
 * created). Resumable sessions persist in the ledger before the first byte; a finished
 * session answers the status query with the file, so a lost final response is adopted.
 */
final class GoogleDriveDestination implements Destination {

    private static final String API = "https://www.googleapis.com/drive/v3";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final MediaType JSON = MediaType.parse("application/json; charset=UTF-8");

    private final String destId;
    private final OkHttpClient http;
    private final OAuthTokens tokens;
    private final Ledger ledger;

    GoogleDriveDestination(String destId, OkHttpClient http, Ledger ledger) {
        this.destId = destId;
        this.http = http;
        this.ledger = ledger;
        JSONObject s = SafeKeepSecrets.get(destId);
        this.tokens = new OAuthTokens(destId, "https://oauth2.googleapis.com/token",
                s.optString("clientId", ""), s.optString("clientSecret", ""), null);
    }

    @Override
    public Caps caps() {
        return new Caps("md5", true, true, true, true);
    }

    /** Non-upload API call with one transparent retry after a 401. */
    private Response call(UploadJob job, Request.Builder b) throws DestError {
        return Http.authorized(http, tokens, job, b);
    }

    @Override
    public void begin(UploadJob job) throws DestError {
        if (!job.state.optString("sessionUri", "").isEmpty()) return;
        // Same logical object → same Drive file: a regenerated trip row, a same-day settings
        // snapshot or a retry after a checksum mismatch updates in place instead of duplicating.
        String existing = findByKey(job.logicalKey);
        String parentId = existing == null ? ensureFolders(job, Http.parent(job.remotePath)) : null;
        try {
            JSONObject meta = new JSONObject().put("name", Http.name(job.remotePath));
            if (existing == null) {
                meta.put("parents", new JSONArray().put(parentId))
                        .put("appProperties", new JSONObject().put("odk", keyTag(destId, job.logicalKey)));
            }
            String url = existing == null
                    ? UPLOAD + "?uploadType=resumable&fields=id,size,md5Checksum,webViewLink"
                    : UPLOAD + "/" + existing + "?uploadType=resumable&fields=id,size,md5Checksum,webViewLink";
            RequestBody metaBody = RequestBody.create(meta.toString(), JSON);
            Request.Builder b = new Request.Builder()
                    .url(url)
                    .header("X-Upload-Content-Type", job.contentType)
                    .header("X-Upload-Content-Length", Long.toString(job.length))
                    .method(existing == null ? "POST" : "PATCH", metaBody);
            try (Response r = call(job, b)) {
                String body = Http.body(r);
                if (!r.isSuccessful()) {
                    if (r.code() == 404 && parentId != null && body.contains(parentId)) ledger.dropFolderIds(destId);
                    throw Http.error("Drive session", r, body);
                }
                String loc = r.header("Location");
                if (loc == null || loc.isEmpty()) throw new DestError(DestError.Kind.NETWORK, "Drive: no session URI");
                job.state.put("sessionUri", loc);
                job.saveState(0);
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public long confirmed(UploadJob job) throws DestError {
        if (job.state.optJSONObject("result") != null) return job.length;
        String uri = job.state.optString("sessionUri", "");
        Request req = new Request.Builder().url(uri)
                .header("Content-Range", "bytes */" + job.length)
                .put(RequestBody.create(new byte[0], null)).build();
        try (Response r = job.execute(http, req)) {
            return handleProgress(job, r, 0);
        }
    }

    private long handleProgress(UploadJob job, Response r, long fallback) throws DestError {
        String body = Http.body(r);
        if (r.code() == 308) {
            String range = r.header("Range");
            if (range == null || range.isEmpty()) return 0;
            int dash = range.lastIndexOf('-');
            try {
                return Long.parseLong(range.substring(dash + 1).trim()) + 1;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        if (r.code() == 200 || r.code() == 201) {
            try {
                job.state.put("result", new JSONObject(body));
                job.saveState(job.length);
            } catch (Exception e) {
                throw new DestError(DestError.Kind.NETWORK, "Drive: unreadable completion");
            }
            return job.length;
        }
        if (r.code() == 404 || r.code() == 410) {
            throw new DestError(DestError.Kind.SESSION_GONE, "Drive upload session expired", r.code(), 0);
        }
        throw Http.error("Drive upload", r, body);
    }

    @Override
    public int chunkSize(UploadJob job, long offset, int suggested) {
        return suggested;
    }

    @Override
    public long put(UploadJob job, long offset, int len) throws DestError {
        String uri = job.state.optString("sessionUri", "");
        long end = offset + len - 1;
        Request req = new Request.Builder().url(uri)
                .header("Content-Range", "bytes " + offset + "-" + end + "/" + job.length)
                .put(job.body(offset, len, null)).build();
        try (Response r = job.execute(http, req)) {
            return handleProgress(job, r, offset);
        }
    }

    @Override
    public RemoteRef finish(UploadJob job) throws DestError {
        JSONObject res = job.state.optJSONObject("result");
        if (res == null) {
            confirmed(job);
            res = job.state.optJSONObject("result");
        }
        if (res == null) throw new DestError(DestError.Kind.NETWORK, "Drive: upload not complete");
        String id = res.optString("id", "");
        long size = res.has("size") ? Long.parseLong(res.optString("size", "-1")) : -1;
        String web = res.optString("webViewLink", id.isEmpty() ? "" : "https://drive.google.com/file/d/" + id + "/view");
        return new RemoteRef(id, size, res.optString("md5Checksum", null), null, web);
    }

    @Override
    public void abandon(UploadJob job) {
        // Sessions expire on their own after a week; nothing to clean.
    }

    @Override
    public void delete(String remoteRef) throws DestError {
        try (Response r = call(null, new Request.Builder().url(API + "/files/" + remoteRef).delete())) {
            if (!r.isSuccessful() && r.code() != 404) throw Http.error("Drive delete", r, Http.body(r));
        }
    }

    @Override
    public JSONObject test() throws DestError {
        long t0 = System.currentTimeMillis();
        JSONObject out = new JSONObject();
        try (Response r = call(null, new Request.Builder()
                .url(API + "/about?fields=user(displayName,emailAddress),storageQuota"))) {
            String body = Http.body(r);
            if (!r.isSuccessful()) throw Http.error("Drive", r, body);
            JSONObject j = new JSONObject(body);
            JSONObject user = j.optJSONObject("user");
            if (user != null) out.put("account", user.optString("emailAddress", user.optString("displayName", "")));
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
        ensureFolders(null, "OverDrive");
        try {
            out.put("latencyMs", System.currentTimeMillis() - t0);
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public Quota quota() throws DestError {
        try (Response r = call(null, new Request.Builder().url(API + "/about?fields=storageQuota"))) {
            String body = Http.body(r);
            if (!r.isSuccessful()) return null;
            JSONObject q = new JSONObject(body).optJSONObject("storageQuota");
            if (q == null || !q.has("limit")) return null;
            return new Quota(Long.parseLong(q.optString("usage", "0")), Long.parseLong(q.optString("limit", "0")));
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String shareLink(String remoteRef, boolean anyone) throws DestError {
        if (anyone) {
            try {
                JSONObject perm = new JSONObject().put("role", "reader").put("type", "anyone");
                try (Response r = call(null, new Request.Builder().url(API + "/files/" + remoteRef + "/permissions")
                        .post(RequestBody.create(perm.toString(), JSON)))) {
                    if (!r.isSuccessful()) throw Http.error("Drive share", r, Http.body(r));
                }
            } catch (DestError e) {
                throw e;
            } catch (Exception e) {
                throw DestError.network(e);
            }
        }
        return "https://drive.google.com/file/d/" + remoteRef + "/view";
    }

    @Override
    public List<RemoteEntry> list(String dirPath) throws DestError {
        List<RemoteEntry> out = new ArrayList<>();
        String folderId = findFolder(dirPath);
        if (folderId == null) return out;
        String page = null;
        do {
            HttpUrl.Builder u = HttpUrl.parse(API + "/files").newBuilder()
                    .addQueryParameter("q", "'" + folderId + "' in parents and trashed=false")
                    .addQueryParameter("fields", "nextPageToken,files(id,name,size,mimeType)")
                    .addQueryParameter("pageSize", "1000");
            if (page != null) u.addQueryParameter("pageToken", page);
            try (Response r = call(null, new Request.Builder().url(u.build()))) {
                String body = Http.body(r);
                if (!r.isSuccessful()) throw Http.error("Drive list", r, body);
                JSONObject j = new JSONObject(body);
                JSONArray files = j.optJSONArray("files");
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    out.add(new RemoteEntry(f.optString("name"), f.optString("id"),
                            Long.parseLong(f.optString("size", "0")), FOLDER_MIME.equals(f.optString("mimeType"))));
                }
                page = j.optString("nextPageToken", null);
            } catch (DestError e) {
                throw e;
            } catch (Exception e) {
                throw DestError.network(e);
            }
        } while (page != null && !page.isEmpty() && out.size() < 20000);
        return out;
    }

    @Override
    public byte[] download(String remoteRef, long maxBytes) throws DestError {
        try (Response r = call(null, new Request.Builder().url(API + "/files/" + remoteRef + "?alt=media"))) {
            if (!r.isSuccessful()) throw Http.error("Drive download", r, Http.body(r));
            return Http.bytes(r, maxBytes);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    // ── folders ───────────────────────────────────────────────────────────

    /** Serialized: two concurrent uploads into a new event folder must not create it twice. */
    private synchronized String ensureFolders(UploadJob job, String path) throws DestError {
        String parent = "root";
        StringBuilder cur = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (cur.length() > 0) cur.append('/');
            cur.append(seg);
            String cached = ledger.folderId(destId, cur.toString());
            if (cached != null) {
                parent = cached;
                continue;
            }
            String found = queryFolder(parent, seg);
            if (found == null) found = createFolder(parent, seg);
            ledger.putFolderId(destId, cur.toString(), found);
            parent = found;
        }
        return parent;
    }

    /** The app's own file already holding this logical key, or null. */
    private String findByKey(String logicalKey) throws DestError {
        String q = "appProperties has { key='odk' and value='" + keyTag(destId, logicalKey) + "' } and trashed=false";
        HttpUrl u = HttpUrl.parse(API + "/files").newBuilder().addQueryParameter("q", q)
                .addQueryParameter("fields", "files(id)").addQueryParameter("pageSize", "5").build();
        try (Response r = call(null, new Request.Builder().url(u))) {
            String body = Http.body(r);
            if (!r.isSuccessful()) throw Http.error("Drive lookup", r, body);
            JSONArray files = new JSONObject(body).optJSONArray("files");
            return files != null && files.length() > 0 ? files.getJSONObject(0).optString("id", null) : null;
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    private String findFolder(String path) throws DestError {
        String parent = "root";
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            parent = queryFolder(parent, seg);
            if (parent == null) return null;
        }
        return parent;
    }

    private String queryFolder(String parent, String name) throws DestError {
        String q = "name='" + qEscape(name) + "' and '" + parent + "' in parents and mimeType='" + FOLDER_MIME
                + "' and trashed=false";
        // Oldest first: if a create was ever duplicated, every caller settles on the same twin.
        HttpUrl u = HttpUrl.parse(API + "/files").newBuilder().addQueryParameter("q", q)
                .addQueryParameter("orderBy", "createdTime")
                .addQueryParameter("fields", "files(id)").addQueryParameter("pageSize", "10").build();
        try (Response r = call(null, new Request.Builder().url(u))) {
            String body = Http.body(r);
            if (!r.isSuccessful()) throw Http.error("Drive folder lookup", r, body);
            JSONArray files = new JSONObject(body).optJSONArray("files");
            return files != null && files.length() > 0 ? files.getJSONObject(0).optString("id", null) : null;
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    private String createFolder(String parent, String name) throws DestError {
        try {
            JSONObject meta = new JSONObject().put("name", name).put("mimeType", FOLDER_MIME)
                    .put("parents", new JSONArray().put(parent));
            try (Response r = call(null, new Request.Builder().url(API + "/files?fields=id")
                    .post(oneShot(RequestBody.create(meta.toString(), JSON))))) {
                String body = Http.body(r);
                if (!r.isSuccessful()) throw Http.error("Drive folder create", r, body);
                return new JSONObject(body).getString("id");
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    /** A body OkHttp never resends once sent (a replayed create makes a duplicate folder). */
    private static RequestBody oneShot(RequestBody b) {
        return new RequestBody() {
            @Override public MediaType contentType() { return b.contentType(); }
            @Override public long contentLength() throws java.io.IOException { return b.contentLength(); }
            @Override public void writeTo(okio.BufferedSink sink) throws java.io.IOException { b.writeTo(sink); }
            @Override public boolean isOneShot() { return true; }
        };
    }

    private static String qEscape(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** Scoped to the destination: two Drive destinations on one account never share a file. */
    private static String keyTag(String destId, String logicalKey) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((destId + "|" + logicalKey).getBytes("UTF-8"));
            return com.overdrive.app.safekeep.util.StreamDigests.hex(d).substring(0, 24);
        } catch (Exception e) {
            return URLEncoder.encode(logicalKey).substring(0, Math.min(24, logicalKey.length()));
        }
    }
}
