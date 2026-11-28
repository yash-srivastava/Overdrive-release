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
 * Dropbox app-folder access. Upload sessions resume from the persisted offset; an
 * {@code incorrect_offset} reply carries the server's offset, which the core re-feeds.
 * Verification uses Dropbox's block content hash.
 */
final class DropboxDestination implements Destination {

    private static final String API = "https://api.dropboxapi.com/2";
    private static final String CONTENT = "https://content.dropboxapi.com/2";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final MediaType OCTET = MediaType.parse("application/octet-stream");

    private final OkHttpClient http;
    private final OAuthTokens tokens;

    DropboxDestination(String destId, OkHttpClient http) {
        this.http = http;
        JSONObject s = SafeKeepSecrets.get(destId);
        this.tokens = new OAuthTokens(destId, "https://api.dropboxapi.com/oauth2/token",
                s.optString("clientId", ""), null, null);
    }

    @Override
    public Caps caps() {
        return new Caps("dropbox", true, true, true, true);
    }

    private Request.Builder authed(Request.Builder b) throws DestError {
        return b.header("Authorization", "Bearer " + tokens.accessToken(http));
    }

    /**
     * Content call with one retry after a 401: the token is refreshed and the body rebuilt.
     * {@code chunk} bodies stream file bytes, so their digests are rewound before the resend.
     */
    private Response content(UploadJob job, String url, JSONObject arg, boolean chunk, BodyFactory bodies)
            throws DestError {
        for (int attempt = 0; ; attempt++) {
            Request req = authed(new Request.Builder().url(url)
                    .header("Dropbox-API-Arg", apiArg(arg))
                    .post(bodies.create())).build();
            Response r = job.execute(http, req);
            if (r.code() != 401 || attempt > 0) return r;
            r.close();
            tokens.invalidate();
            if (chunk) job.rewindChunk();
        }
    }

    private interface BodyFactory {
        RequestBody create();
    }

    private Response rpc(String url, JSONObject arg) throws DestError {
        RequestBody body = RequestBody.create(arg == null ? "null" : arg.toString(), JSON);
        return Http.authorized(http, tokens, null, new Request.Builder().url(url).post(body));
    }

    /** Dropbox-API-Arg must be ASCII; JSON-escape everything else. */
    static String apiArg(JSONObject o) {
        String s = o.toString();
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x7f) sb.append(c);
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }

    private static String path(String remotePath) {
        return "/" + remotePath.replaceAll("^/+", "");
    }

    @Override
    public void begin(UploadJob job) throws DestError {
        if (!job.state.optString("sessionId", "").isEmpty()) return;
        try {
            try (Response r = content(job, CONTENT + "/files/upload_session/start",
                    new JSONObject().put("close", false), false, () -> RequestBody.create(new byte[0], OCTET))) {
                String body = Http.body(r);
                if (!r.isSuccessful()) throw mapError("Dropbox session", r, body);
                job.state.put("sessionId", new JSONObject(body).getString("session_id"));
                job.state.put("offset", 0);
                job.saveState(0);
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public long confirmed(UploadJob job) {
        return job.state.optLong("offset", 0);
    }

    @Override
    public int chunkSize(UploadJob job, long offset, int suggested) {
        return Math.min(suggested, 100 * 1024 * 1024);
    }

    @Override
    public long put(UploadJob job, long offset, int len) throws DestError {
        try {
            JSONObject arg = new JSONObject()
                    .put("cursor", new JSONObject().put("session_id", job.state.getString("sessionId")).put("offset", offset))
                    .put("close", false);
            try (Response r = content(job, CONTENT + "/files/upload_session/append_v2", arg, true,
                    () -> job.body(offset, len, "application/octet-stream"))) {
                String body = Http.body(r);
                if (r.isSuccessful()) {
                    job.state.put("offset", offset + len);
                    job.saveState(offset + len);
                    return offset + len;
                }
                if (r.code() == 409 && body.contains("incorrect_offset")) {
                    long correct = new JSONObject(body).optJSONObject("error").optLong("correct_offset", offset);
                    job.state.put("offset", correct);
                    job.saveState(correct);
                    return correct;
                }
                throw mapError("Dropbox upload", r, body);
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public RemoteRef finish(UploadJob job) throws DestError {
        try {
            JSONObject arg = new JSONObject()
                    .put("cursor", new JSONObject().put("session_id", job.state.getString("sessionId"))
                            .put("offset", job.length))
                    .put("commit", new JSONObject().put("path", path(job.remotePath)).put("mode", "overwrite")
                            .put("autorename", false).put("mute", true));
            try (Response r = content(job, CONTENT + "/files/upload_session/finish", arg, false,
                    () -> RequestBody.create(new byte[0], OCTET))) {
                String body = Http.body(r);
                if (!r.isSuccessful()) {
                    // A lost commit response: the session is closed but the file may already be there.
                    if (r.code() == 409 && (body.contains("lookup_failed") || body.contains("closed")
                            || body.contains("not_found"))) {
                        RemoteRef adopted = adoptCommitted(job);
                        if (adopted != null) return adopted;
                    }
                    throw mapError("Dropbox commit", r, body);
                }
                JSONObject j = new JSONObject(body);
                return new RemoteRef(j.optString("id", j.optString("path_lower")), j.optLong("size", -1), null,
                        j.optString("content_hash", null), null);
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    private RemoteRef adoptCommitted(UploadJob job) {
        try (Response r = rpc(API + "/files/get_metadata", new JSONObject().put("path", path(job.remotePath)))) {
            if (!r.isSuccessful()) return null;
            JSONObject j = new JSONObject(Http.body(r));
            String hash = j.optString("content_hash", "");
            if (j.optLong("size", -1) == job.length && !hash.isEmpty() && job.digests != null
                    && hash.equalsIgnoreCase(job.digests.dropboxHex())) {
                return new RemoteRef(j.optString("id", j.optString("path_lower")), job.length, null, hash, null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static DestError mapError(String ctx, Response r, String body) {
        if (r.code() == 409 && (body.contains("not_found") || body.contains("closed"))) {
            return new DestError(DestError.Kind.SESSION_GONE, ctx + ": session expired", 409, 0);
        }
        if (body.contains("insufficient_space")) return new DestError(DestError.Kind.QUOTA_FULL, ctx + ": Dropbox is full", r.code(), 0);
        return Http.error(ctx, r, body);
    }

    @Override
    public void abandon(UploadJob job) {
    }

    @Override
    public void delete(String remoteRef) throws DestError {
        try {
            try (Response r = rpc(API + "/files/delete_v2", new JSONObject().put("path", remoteRef))) {
                String body = Http.body(r);
                if (!r.isSuccessful() && !body.contains("not_found")) throw Http.error("Dropbox delete", r, body);
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public JSONObject test() throws DestError {
        long t0 = System.currentTimeMillis();
        try (Response r = rpc(API + "/users/get_current_account", null)) {
            String body = Http.body(r);
            if (!r.isSuccessful()) throw Http.error("Dropbox", r, body);
            JSONObject j = new JSONObject(body);
            JSONObject name = j.optJSONObject("name");
            return new JSONObject().put("account", j.optString("email", name == null ? "" : name.optString("display_name")))
                    .put("latencyMs", System.currentTimeMillis() - t0);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public Quota quota() throws DestError {
        try (Response r = rpc(API + "/users/get_space_usage", null)) {
            if (!r.isSuccessful()) return null;
            JSONObject j = new JSONObject(Http.body(r));
            JSONObject alloc = j.optJSONObject("allocation");
            long total = alloc == null ? 0 : alloc.optLong("allocated", 0);
            return total > 0 ? new Quota(j.optLong("used", 0), total) : null;
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String shareLink(String remoteRef, boolean anyone) throws DestError {
        if (!anyone) return null;
        try (Response r = rpc(API + "/sharing/create_shared_link_with_settings",
                new JSONObject().put("path", remoteRef))) {
            String body = Http.body(r);
            if (r.isSuccessful()) return new JSONObject(body).optString("url", null);
            if (body.contains("shared_link_already_exists")) {
                JSONObject err = new JSONObject(body).optJSONObject("error");
                JSONObject inner = err == null ? null : err.optJSONObject("shared_link_already_exists");
                JSONObject meta = inner == null ? null : inner.optJSONObject("metadata");
                return meta == null ? null : meta.optString("url", null);
            }
            throw Http.error("Dropbox share", r, body);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public List<RemoteEntry> list(String dirPath) throws DestError {
        List<RemoteEntry> out = new ArrayList<>();
        try {
            Response r = rpc(API + "/files/list_folder", new JSONObject().put("path", path(dirPath)).put("limit", 2000));
            while (true) {
                String body = Http.body(r);
                r.close();
                if (!r.isSuccessful()) {
                    if (body.contains("not_found")) return out;
                    throw Http.error("Dropbox list", r, body);
                }
                JSONObject j = new JSONObject(body);
                JSONArray entries = j.optJSONArray("entries");
                if (entries != null) for (int i = 0; i < entries.length(); i++) {
                    JSONObject e = entries.getJSONObject(i);
                    out.add(new RemoteEntry(e.optString("name"), e.optString("id", e.optString("path_lower")),
                            e.optLong("size", 0), "folder".equals(e.optString(".tag"))));
                }
                if (!j.optBoolean("has_more", false) || out.size() > 20000) break;
                r = rpc(API + "/files/list_folder/continue", new JSONObject().put("cursor", j.getString("cursor")));
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
        return out;
    }

    @Override
    public byte[] download(String remoteRef, long maxBytes) throws DestError {
        try {
            Request req = authed(new Request.Builder().url(CONTENT + "/files/download")
                    .header("Dropbox-API-Arg", apiArg(new JSONObject().put("path", remoteRef)))
                    .post(RequestBody.create(new byte[0], null))).build();
            try (Response r = Http.execute(http, null, req)) {
                if (!r.isSuccessful()) throw Http.error("Dropbox download", r, Http.body(r));
                return Http.bytes(r, maxBytes);
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }
}
