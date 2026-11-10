package com.overdrive.app.safekeep.dest.s3;

import com.overdrive.app.safekeep.SafeKeepSecrets;
import com.overdrive.app.safekeep.dest.DestError;
import com.overdrive.app.safekeep.dest.Destination;
import com.overdrive.app.safekeep.dest.Http;
import com.overdrive.app.safekeep.dest.UploadJob;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * S3-compatible object storage (R2, B2, AWS, Wasabi, iDrive e2, Hetzner, MinIO, Garage…).
 * Hand-rolled SigV4 with UNSIGNED-PAYLOAD over HTTPS; multipart state (upload id + part
 * ETags) is the resume point. No x-amz-checksum headers are ever sent (B2 rejects them).
 */
public final class S3Destination implements Destination {

    private static final int SINGLE_PUT_MAX = 8 * 1024 * 1024;
    private static final int MIN_PART = 5 * 1024 * 1024;
    private static final Pattern XML_CODE = Pattern.compile("<Code>([^<]+)</Code>");
    private static final Pattern UPLOAD_ID = Pattern.compile("<UploadId>([^<]+)</UploadId>");
    // Reuse compiled expressions across paginated listings; Matcher instances remain per response.
    private static final Pattern LIST_CONTENTS = Pattern.compile(
            "<Contents>.*?<Key>([^<]+)</Key>.*?<Size>(\\d+)</Size>.*?</Contents>", Pattern.DOTALL);
    private static final Pattern LIST_PREFIXES = Pattern.compile(
            "<CommonPrefixes>\\s*<Prefix>([^<]+)</Prefix>");
    private static final Pattern LIST_NEXT_TOKEN = Pattern.compile(
            "<NextContinuationToken>([^<]+)</NextContinuationToken>");

    private final OkHttpClient http;
    private final String scheme;
    private final String endpointHost;
    private final String bucket;
    private final String region;
    private final boolean pathStyle;
    private final boolean writeOnly;
    private final String accessKey;
    private final String secretKey;
    private volatile long skewMs;

    public S3Destination(String destId, JSONObject params, OkHttpClient http) throws Exception {
        this.http = http;
        URI ep = new URI(params.optString("endpoint", "").trim().replaceAll("/+$", ""));
        if (ep.getHost() == null) throw new IllegalArgumentException("S3 endpoint URL required");
        this.scheme = ep.getScheme() == null ? "https" : ep.getScheme().toLowerCase(Locale.ROOT);
        int port = ep.getPort();
        boolean defaultPort = port <= 0 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        // Must match the Host header OkHttp sends, which omits a default port.
        // Lowercase: OkHttp sends a lowercased Host, and the signature must match it byte for byte.
        this.endpointHost = ep.getHost().toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        this.bucket = params.optString("bucket", "").trim();
        if (bucket.isEmpty()) throw new IllegalArgumentException("S3 bucket required");
        this.region = params.optString("region", "us-east-1").trim().isEmpty() ? "us-east-1"
                : params.optString("region").trim();
        this.pathStyle = params.optBoolean("pathStyle", true);
        this.writeOnly = params.optBoolean("writeOnly", false);
        JSONObject s = SafeKeepSecrets.get(destId);
        this.accessKey = s.optString("accessKeyId", "");
        this.secretKey = s.optString("secretAccessKey", "");
    }

    @Override
    public Caps caps() {
        return new Caps("size", true, !writeOnly, !writeOnly, !writeOnly);
    }

    // ── signing ────────────────────────────────────────────────────────────

    private String host() {
        return pathStyle ? endpointHost : bucket + "." + endpointHost;
    }

    private String uri(String key) {
        String enc = SigV4.uriEncode(key, false);
        return pathStyle ? "/" + bucket + "/" + enc : "/" + enc;
    }

    private Request.Builder signed(String method, String key, Map<String, String> query, String payloadHash,
                                   RequestBody body, Map<String, String> extra) {
        String canonicalUri = key == null ? (pathStyle ? "/" + bucket + "/" : "/") : uri(key);
        String q = SigV4.canonicalQuery(query);
        Map<String, String> signedHeaders = new HashMap<>();
        if (extra != null) signedHeaders.putAll(extra);
        Map<String, String> h = SigV4.sign(method, host(), canonicalUri, q, signedHeaders, payloadHash,
                accessKey, secretKey, region, System.currentTimeMillis() + skewMs);
        Request.Builder b = new Request.Builder().url(scheme + "://" + host() + canonicalUri + (q.isEmpty() ? "" : "?" + q));
        for (Map.Entry<String, String> e : h.entrySet()) b.header(e.getKey(), e.getValue());
        b.method(method, body);
        return b;
    }

    private Response exec(UploadJob job, Request req) throws DestError {
        if (job != null) return job.execute(http, req);
        try {
            return http.newCall(req).execute();
        } catch (java.io.IOException e) {
            throw DestError.network(e);
        }
    }

    /** Maps S3 XML errors; learns the clock offset from a skew error so the retry signs correctly. */
    private DestError error(String ctx, Response r, String body) {
        Matcher m = XML_CODE.matcher(body);
        String code = m.find() ? m.group(1) : "";
        if ("RequestTimeTooSkewed".equals(code)) {
            String date = r.header("Date");
            try {
                SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                skewMs = f.parse(date).getTime() - System.currentTimeMillis();
            } catch (Exception ignored) {
            }
            return new DestError(DestError.Kind.CLOCK_SKEW, ctx + ": car clock is off; corrected", r.code(), 0);
        }
        if ("NoSuchUpload".equals(code)) return new DestError(DestError.Kind.SESSION_GONE, ctx + ": " + code, r.code(), 0);
        if ("NoSuchBucket".equals(code) || "AccessDenied".equals(code) || "AllAccessDisabled".equals(code)) {
            return new DestError(DestError.Kind.PERMANENT, ctx + ": " + code
                    + ("AccessDenied".equals(code) ? " (check the key's bucket permissions)" : ""), r.code(), 0);
        }
        if ("InvalidAccessKeyId".equals(code) || "SignatureDoesNotMatch".equals(code)) {
            return new DestError(DestError.Kind.AUTH_EXPIRED, ctx + ": " + code + " (check access key / secret)", r.code(), 0);
        }
        if ("SlowDown".equals(code) || "ServiceUnavailable".equals(code)) {
            return new DestError(DestError.Kind.RATE_LIMITED, ctx + ": " + code, r.code(), Http.retryAfterMs(r));
        }
        // Transient by definition (idle socket on a slow link, server hiccup, 200-with-Error on complete).
        if ("RequestTimeout".equals(code) || "InternalError".equals(code) || "IncompleteBody".equals(code)
                || r.code() == 200) {
            return new DestError(DestError.Kind.NETWORK, ctx + ": " + (code.isEmpty() ? "HTTP " + r.code() : code),
                    r.code(), Http.retryAfterMs(r));
        }
        return Http.error(ctx, r, body);
    }

    // ── upload ──────────────────────────────────────────────────────────────

    private static String key(UploadJob job) {
        return job.remotePath.replaceAll("^/+", "");
    }

    @Override
    public void begin(UploadJob job) throws DestError {
        if (!job.state.optString("mode", "").isEmpty()) return;
        try {
            if (job.length <= SINGLE_PUT_MAX) {
                job.state.put("mode", "single");
                job.saveState(0);
                return;
            }
            Map<String, String> q = new HashMap<>();
            q.put("uploads", null);
            Request req = signed("POST", key(job), q, SigV4.EMPTY_SHA256, RequestBody.create(new byte[0], null),
                    null).header("Content-Type", job.contentType).build();
            try (Response r = exec(job, req)) {
                String body = Http.body(r);
                if (!r.isSuccessful()) throw error("S3 multipart start", r, body);
                Matcher m = UPLOAD_ID.matcher(body);
                if (!m.find()) throw new DestError(DestError.Kind.NETWORK, "S3: no UploadId in response");
                job.state.put("mode", "multipart").put("uploadId", m.group(1)).put("parts", new JSONArray());
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
        if (!"multipart".equals(job.state.optString("mode"))) return 0;
        JSONArray parts = job.state.optJSONArray("parts");
        long partSize = job.state.optLong("partSize", 0);
        if (parts == null || partSize <= 0) return 0;
        return Math.min(job.length, parts.length() * partSize);
    }

    @Override
    public int chunkSize(UploadJob job, long offset, int suggested) {
        if (!"multipart".equals(job.state.optString("mode"))) return (int) job.length;
        long partSize = job.state.optLong("partSize", 0);
        if (partSize <= 0) {
            long minForCount = (job.length + 9_999) / 10_000;
            long mib = 1024 * 1024;
            partSize = Math.max(MIN_PART, Math.max(suggested, minForCount));
            partSize = ((partSize + mib - 1) / mib) * mib;
            try {
                job.state.put("partSize", partSize);
                job.saveState(offset);
            } catch (Exception ignored) {
            }
        }
        return (int) partSize;
    }

    @Override
    public long put(UploadJob job, long offset, int len) throws DestError {
        try {
            if (!"multipart".equals(job.state.optString("mode"))) {
                UploadJob.ChunkBody body = job.body(0, job.length, job.contentType);
                Request req = signed("PUT", key(job), null, SigV4.UNSIGNED_PAYLOAD, body, null).build();
                try (Response r = exec(job, req)) {
                    String text = Http.body(r);
                    if (!r.isSuccessful()) throw error("S3 upload", r, text);
                    checkEtag(r, body);
                    job.state.put("etag", clean(r.header("ETag")));
                    job.saveState(job.length);
                    return job.length;
                }
            }
            long partSize = job.state.getLong("partSize");
            int partNo = (int) (offset / partSize) + 1;
            Map<String, String> q = new HashMap<>();
            q.put("partNumber", Integer.toString(partNo));
            q.put("uploadId", job.state.getString("uploadId"));
            UploadJob.ChunkBody body = job.body(offset, len, "application/octet-stream");
            Request req = signed("PUT", key(job), q, SigV4.UNSIGNED_PAYLOAD, body, null).build();
            try (Response r = exec(job, req)) {
                String text = Http.body(r);
                if (!r.isSuccessful()) throw error("S3 part " + partNo, r, text);
                checkEtag(r, body);
                JSONArray parts = job.state.optJSONArray("parts");
                if (parts == null) parts = new JSONArray();
                // Parts are sequential; a re-sent part replaces its slot.
                while (parts.length() >= partNo) parts.remove(parts.length() - 1);
                parts.put(new JSONObject().put("n", partNo).put("etag", clean(r.header("ETag"))));
                job.state.put("parts", parts);
                job.saveState(offset + len);
                return offset + len;
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    private static void checkEtag(Response r, UploadJob.ChunkBody body) throws DestError {
        // SSE-KMS and SSE-C ETags are not the MD5 of the data, though they look like one.
        String sse = r.header("x-amz-server-side-encryption");
        if (sse != null && sse.toLowerCase(Locale.ROOT).startsWith("aws:kms")) return;
        if (r.header("x-amz-server-side-encryption-customer-algorithm") != null) return;
        String etag = clean(r.header("ETag"));
        String md5 = body.md5Hex();
        if (etag != null && etag.matches("[0-9a-fA-F]{32}") && md5 != null && !etag.equalsIgnoreCase(md5)) {
            throw new DestError(DestError.Kind.NETWORK, "S3 checksum mismatch on upload; retrying");
        }
    }

    private static String clean(String etag) {
        return etag == null ? null : etag.replace("\"", "").trim();
    }

    @Override
    public RemoteRef finish(UploadJob job) throws DestError {
        String k = key(job);
        if (!"multipart".equals(job.state.optString("mode"))) return new RemoteRef(k, job.length, null, null, null);
        try {
            JSONArray parts = job.state.getJSONArray("parts");
            StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.getJSONObject(i);
                xml.append("<Part><PartNumber>").append(p.getInt("n")).append("</PartNumber><ETag>\"")
                        .append(p.getString("etag")).append("\"</ETag></Part>");
            }
            xml.append("</CompleteMultipartUpload>");
            byte[] payload = xml.toString().getBytes(StandardCharsets.UTF_8);
            Map<String, String> q = new HashMap<>();
            q.put("uploadId", job.state.getString("uploadId"));
            Request req = signed("POST", k, q, SigV4.sha256Hex(payload),
                    RequestBody.create(payload, MediaType.parse("application/xml")), null).build();
            try (Response r = exec(job, req)) {
                String text = Http.body(r);
                // S3 can return 200 with an <Error> body for CompleteMultipartUpload.
                if (!r.isSuccessful() || text.contains("<Error>")) {
                    DestError err = error("S3 complete", r, text);
                    if (err.kind == DestError.Kind.SESSION_GONE && !writeOnly && existsWithSize(k, job.length)) {
                        return new RemoteRef(k, job.length, null, null, null);
                    }
                    throw err;
                }
                return new RemoteRef(k, job.length, null, null, null);
            }
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    private boolean existsWithSize(String k, long len) {
        try (Response r = exec(null, signed("HEAD", k, null, SigV4.EMPTY_SHA256, null, null).build())) {
            String cl = r.header("Content-Length");
            return r.isSuccessful() && cl != null && Long.parseLong(cl) == len;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void abandon(UploadJob job) {
        if (writeOnly || !"multipart".equals(job.state.optString("mode"))) return;
        try {
            Map<String, String> q = new HashMap<>();
            q.put("uploadId", job.state.getString("uploadId"));
            exec(null, signed("DELETE", key(job), q, SigV4.EMPTY_SHA256, null, null).build()).close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void delete(String remoteRef) throws DestError {
        try (Response r = exec(null, signed("DELETE", remoteRef, null, SigV4.EMPTY_SHA256, null, null).build())) {
            if (!r.isSuccessful() && r.code() != 404) throw error("S3 delete", r, Http.body(r));
        }
    }

    @Override
    public JSONObject test() throws DestError {
        long t0 = System.currentTimeMillis();
        String probe = ".overdrive-probe-" + System.currentTimeMillis() + ".txt";
        byte[] data = "OverDrive SafeKeep connection test".getBytes(StandardCharsets.UTF_8);
        try (Response r = exec(null, signed("PUT", probe, null, SigV4.sha256Hex(data),
                RequestBody.create(data, MediaType.parse("text/plain")), null).build())) {
            if (!r.isSuccessful()) throw error("S3 write test", r, Http.body(r));
        }
        JSONObject out = new JSONObject();
        try {
            out.put("write", true);
            if (!writeOnly) {
                try (Response r = exec(null, signed("GET", probe, null, SigV4.EMPTY_SHA256, null, null).build())) {
                    out.put("read", r.isSuccessful());
                }
                try (Response r = exec(null, signed("DELETE", probe, null, SigV4.EMPTY_SHA256, null, null).build())) {
                    out.put("delete", r.isSuccessful());
                }
            } else {
                out.put("note", "write-only key: the probe object stays (delete it or let a lifecycle rule expire it)");
            }
            out.put("latencyMs", System.currentTimeMillis() - t0);
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public Quota quota() {
        return null;
    }

    @Override
    public String shareLink(String remoteRef, boolean anyone) {
        if (writeOnly) return null;
        return SigV4.presignGet(scheme, host(), uri(remoteRef), accessKey, secretKey, region,
                System.currentTimeMillis() + skewMs, 7 * 24 * 3600);
    }

    @Override
    public List<RemoteEntry> list(String dirPath) throws DestError {
        List<RemoteEntry> out = new ArrayList<>();
        String prefix = dirPath.replaceAll("^/+|/+$", "") + "/";
        String token = null;
        do {
            Map<String, String> q = new HashMap<>();
            q.put("list-type", "2");
            q.put("prefix", prefix);
            q.put("delimiter", "/");
            if (token != null) q.put("continuation-token", token);
            try (Response r = exec(null, signed("GET", null, q, SigV4.EMPTY_SHA256, null, null).build())) {
                String body = Http.body(r);
                if (!r.isSuccessful()) throw error("S3 list", r, body);
                Matcher c = LIST_CONTENTS.matcher(body);
                while (c.find()) {
                    String k = xmlUnescape(c.group(1));
                    out.add(new RemoteEntry(k.substring(k.lastIndexOf('/') + 1), k, Long.parseLong(c.group(2)), false));
                }
                Matcher p = LIST_PREFIXES.matcher(body);
                while (p.find()) {
                    String k = xmlUnescape(p.group(1)).replaceAll("/+$", "");
                    out.add(new RemoteEntry(k.substring(k.lastIndexOf('/') + 1), k, 0, true));
                }
                Matcher t = LIST_NEXT_TOKEN.matcher(body);
                token = body.contains("<IsTruncated>true</IsTruncated>") && t.find() ? xmlUnescape(t.group(1)) : null;
            }
        } while (token != null && out.size() < 20000);
        return out;
    }

    @Override
    public byte[] download(String remoteRef, long maxBytes) throws DestError {
        try (Response r = exec(null, signed("GET", remoteRef, null, SigV4.EMPTY_SHA256, null, null).build())) {
            if (!r.isSuccessful()) throw error("S3 download", r, Http.body(r));
            return Http.bytes(r, maxBytes);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    private static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
