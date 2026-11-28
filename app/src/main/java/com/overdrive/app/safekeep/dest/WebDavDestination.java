package com.overdrive.app.safekeep.dest;

import com.overdrive.app.safekeep.Ledger;
import com.overdrive.app.safekeep.SafeKeepSecrets;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * WebDAV (Synology, QNAP, Hetzner Storage Box, Koofr, pCloud, ownCloud…) and Nextcloud.
 * Generic servers get PUT to a hidden temp name + MOVE, so a truncated file never appears
 * under the final name. Nextcloud large files use chunking v2 (resumable across restarts).
 */
final class WebDavDestination implements Destination {

    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final int NC_CHUNK_MIN = 5 * 1024 * 1024;
    private static final String PROPFIND_SIZE = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>"
            + "<d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>";
    private static final String PROPFIND_QUOTA = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>"
            + "<d:quota-available-bytes/><d:quota-used-bytes/></d:prop></d:propfind>";

    private final String destId;
    private final OkHttpClient http;
    private final Ledger ledger;
    private final String base;
    private final String authHeader;
    private final boolean nextcloud;
    private final String uploadsRoot;

    WebDavDestination(String destId, JSONObject params, OkHttpClient http, Ledger ledger) throws Exception {
        this.destId = destId;
        this.http = http;
        this.ledger = ledger;
        String url = params.optString("url", "").trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IllegalArgumentException("WebDAV URL required");
        this.base = url.endsWith("/") ? url : url + "/";
        JSONObject s = SafeKeepSecrets.get(destId);
        String user = s.optString("username", params.optString("username", ""));
        String pass = s.optString("password", "");
        this.authHeader = user.isEmpty() ? null : "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        this.nextcloud = "nextcloud".equals(params.optString("mode", ""));
        String root = null;
        if (nextcloud) {
            int i = base.indexOf("/remote.php/dav/files/");
            if (i > 0) root = base.substring(0, i) + "/remote.php/dav/uploads/"
                    + base.substring(i + "/remote.php/dav/files/".length()).replaceAll("/+$", "") + "/";
        }
        this.uploadsRoot = root;
    }

    @Override
    public Caps caps() {
        return new Caps("size", nextcloud, true, false, true);
    }

    private String url(String path) {
        return base + Http.encodePath(path.replaceAll("^/+", ""));
    }

    private Request.Builder req(String url) {
        Request.Builder b = new Request.Builder().url(url).header("User-Agent", "OverDrive-SafeKeep");
        if (authHeader != null) b.header("Authorization", authHeader);
        return b;
    }

    // ── upload ──────────────────────────────────────────────────────────────

    @Override
    public void begin(UploadJob job) throws DestError {
        if (!job.state.optString("mode", "").isEmpty()) return;
        ensureCollections(job, Http.parent(job.remotePath));
        try {
            boolean chunked = nextcloud && uploadsRoot != null && job.length > NC_CHUNK_MIN * 2L;
            if (chunked) {
                String dir = uploadsRoot + "od-" + job.itemId + "-" + UUID.randomUUID().toString().substring(0, 8);
                Request mk = req(dir).header("Destination", url(job.remotePath))
                        .method("MKCOL", null).build();
                try (Response r = Http.execute(http, job, mk)) {
                    if (r.isSuccessful() || r.code() == 405) {
                        job.state.put("mode", "chunked").put("dir", dir);
                        job.saveState(0);
                        return;
                    }
                    // Chunking v2 unavailable (no Redis, old server): fall back to a single PUT.
                }
            }
            if (job.length > Integer.MAX_VALUE) {
                throw new DestError(DestError.Kind.PERMANENT, "This WebDAV server takes files up to 2 GB in one piece");
            }
            String tmp = Http.parent(job.remotePath) + "/." + Http.name(job.remotePath) + ".odpart";
            job.state.put("mode", "single").put("tmp", tmp);
            job.saveState(0);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    @Override
    public long confirmed(UploadJob job) throws DestError {
        // The final MOVE was sent but its answer was lost: a complete target means done.
        boolean committing = job.state.optBoolean("committing", false);
        if (!"chunked".equals(job.state.optString("mode"))) {
            return committing && size(job, job.remotePath) == job.length ? job.length : 0;
        }
        long chunk = job.state.optLong("chunk", 0);
        if (chunk <= 0) return 0;
        String dir = job.state.optString("dir");
        Request pf = req(dir + "/").header("Depth", "1").method("PROPFIND", RequestBody.create(PROPFIND_SIZE, XML)).build();
        try (Response r = Http.execute(http, job, pf)) {
            String body = Http.body(r);
            if (r.code() == 404) {
                if (committing && size(job, job.remotePath) == job.length) return job.length;
                throw new DestError(DestError.Kind.SESSION_GONE, "Nextcloud upload expired", 404, 0);
            }
            if (!r.isSuccessful()) throw Http.error("Nextcloud status", r, body);
            int contiguous = 0;
            java.util.Set<Integer> have = new java.util.HashSet<>();
            for (Entry e : parse(body)) {
                String n = e.href.replaceAll("/+$", "");
                n = n.substring(n.lastIndexOf('/') + 1);
                if (n.matches("\\d{5}") && e.size == chunk) have.add(Integer.parseInt(n));
            }
            while (have.contains(contiguous + 1)) contiguous++;
            return Math.min(job.length, contiguous * chunk);
        }
    }

    @Override
    public int chunkSize(UploadJob job, long offset, int suggested) {
        if (!"chunked".equals(job.state.optString("mode"))) return (int) Math.min(job.length, Integer.MAX_VALUE);
        long chunk = job.state.optLong("chunk", 0);
        if (chunk <= 0) {
            chunk = Math.max(NC_CHUNK_MIN, suggested);
            try {
                job.state.put("chunk", chunk);
                job.saveState(offset);
            } catch (Exception ignored) {
            }
        }
        return (int) chunk;
    }

    @Override
    public long put(UploadJob job, long offset, int len) throws DestError {
        String dest = url(job.remotePath);
        if ("chunked".equals(job.state.optString("mode"))) {
            long chunk = job.state.optLong("chunk");
            String name = String.format(Locale.US, "%05d", (int) (offset / chunk) + 1);
            Request r0 = req(job.state.optString("dir") + "/" + name)
                    .header("Destination", dest)
                    .header("OC-Total-Length", Long.toString(job.length))
                    .put(job.body(offset, len, "application/octet-stream")).build();
            try (Response r = Http.execute(http, job, r0)) {
                String body = Http.body(r);
                if (r.code() == 404) throw new DestError(DestError.Kind.SESSION_GONE, "Nextcloud upload expired", 404, 0);
                if (!r.isSuccessful()) throw Http.error("Nextcloud chunk", r, body);
                return offset + len;
            }
        }
        Request r0 = req(url(job.state.optString("tmp"))).put(job.body(0, job.length, job.contentType)).build();
        try (Response r = Http.execute(http, job, r0)) {
            String body = Http.body(r);
            if (r.code() == 409) {
                ledger.dropFolderIds(destId);
                throw new DestError(DestError.Kind.SESSION_GONE, "WebDAV folder missing; recreating", 409, 0);
            }
            if (!r.isSuccessful()) throw Http.error("WebDAV upload", r, body);
            return job.length;
        }
    }

    @Override
    public RemoteRef finish(UploadJob job) throws DestError {
        String dest = url(job.remotePath);
        try {
            job.state.put("committing", true);
            job.saveState(job.length);
        } catch (Exception ignored) {
        }
        Request mv;
        if ("chunked".equals(job.state.optString("mode"))) {
            mv = req(job.state.optString("dir") + "/.file").header("Destination", dest)
                    .header("OC-Total-Length", Long.toString(job.length)).header("Overwrite", "T")
                    .method("MOVE", null).build();
        } else {
            mv = req(url(job.state.optString("tmp"))).header("Destination", dest).header("Overwrite", "T")
                    .method("MOVE", null).build();
        }
        try (Response r = Http.execute(http, job, mv)) {
            String body = Http.body(r);
            if (!r.isSuccessful()) {
                // Only a vanished source means an earlier MOVE landed; any other refusal (locked,
                // full, forbidden) must not pass off the old target as this upload.
                if (r.code() == 404 && size(job, job.remotePath) == job.length) {
                    return new RemoteRef(job.remotePath, job.length, null, null, null);
                }
                throw Http.error("WebDAV finalize", r, body);
            }
        }
        return new RemoteRef(job.remotePath, size(job, job.remotePath), null, null, null);
    }

    private long size(UploadJob job, String path) throws DestError {
        Request pf = req(url(path)).header("Depth", "0").method("PROPFIND", RequestBody.create(PROPFIND_SIZE, XML)).build();
        try (Response r = Http.execute(http, job, pf)) {
            String body = Http.body(r);
            if (!r.isSuccessful()) return -1;
            List<Entry> e = parse(body);
            return e.isEmpty() ? -1 : e.get(0).size;
        }
    }

    @Override
    public void abandon(UploadJob job) {
        try {
            String target = "chunked".equals(job.state.optString("mode")) ? job.state.optString("dir")
                    : url(job.state.optString("tmp"));
            if (target != null && !target.isEmpty()) Http.execute(http, null, req(target).delete().build()).close();
        } catch (Exception ignored) {
        }
    }

    private void ensureCollections(UploadJob job, String path) throws DestError {
        StringBuilder cur = new StringBuilder();
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (cur.length() > 0) cur.append('/');
            cur.append(seg);
            String p = cur.toString();
            if (ledger.folderId(destId, p) != null) continue;
            try (Response r = Http.execute(http, job, req(url(p) + "/").method("MKCOL", null).build())) {
                // 201 created, 405 already exists; 409 means a parent is missing (handled by order).
                if (!r.isSuccessful() && r.code() != 405 && r.code() != 301) {
                    throw Http.error("WebDAV create folder " + p, r, Http.body(r));
                }
            }
            ledger.putFolderId(destId, p, "1");
        }
    }

    // ── management ────────────────────────────────────────────────────────

    @Override
    public void delete(String remoteRef) throws DestError {
        try (Response r = Http.execute(http, null, req(url(remoteRef)).delete().build())) {
            if (!r.isSuccessful() && r.code() != 404) throw Http.error("WebDAV delete", r, Http.body(r));
        }
    }

    @Override
    public JSONObject test() throws DestError {
        long t0 = System.currentTimeMillis();
        Request pf = req(base).header("Depth", "0").method("PROPFIND", RequestBody.create(PROPFIND_SIZE, XML)).build();
        try (Response r = Http.execute(http, null, pf)) {
            String body = Http.body(r);
            if (!r.isSuccessful()) throw Http.error("WebDAV", r, body);
        }
        String probe = "OverDrive/.overdrive-probe-" + System.currentTimeMillis() + ".txt";
        ensureCollections(null, "OverDrive");
        try (Response r = Http.execute(http, null, req(url(probe)).put(RequestBody.create("OverDrive SafeKeep test",
                MediaType.parse("text/plain"))).build())) {
            if (!r.isSuccessful()) throw Http.error("WebDAV write test", r, Http.body(r));
        }
        try (Response r = Http.execute(http, null, req(url(probe)).delete().build())) {
            // Best effort.
        }
        try {
            return new JSONObject().put("latencyMs", System.currentTimeMillis() - t0)
                    .put("chunked", nextcloud && uploadsRoot != null);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override
    public Quota quota() throws DestError {
        Request pf = req(base).header("Depth", "0").method("PROPFIND", RequestBody.create(PROPFIND_QUOTA, XML)).build();
        try (Response r = Http.execute(http, null, pf)) {
            String body = Http.body(r);
            if (!r.isSuccessful()) return null;
            long used = tag(body, "quota-used-bytes"), avail = tag(body, "quota-available-bytes");
            if (used < 0 || avail < 0) return null;
            return new Quota(used, used + avail);
        }
    }

    @Override
    public String shareLink(String remoteRef, boolean anyone) {
        return null;
    }

    @Override
    public List<RemoteEntry> list(String dirPath) throws DestError {
        List<RemoteEntry> out = new ArrayList<>();
        String u = url(dirPath) + "/";
        Request pf = req(u).header("Depth", "1").method("PROPFIND", RequestBody.create(PROPFIND_SIZE, XML)).build();
        try (Response r = Http.execute(http, null, pf)) {
            String body = Http.body(r);
            if (r.code() == 404) return out;
            if (!r.isSuccessful()) throw Http.error("WebDAV list", r, body);
            String selfPath;
            try {
                selfPath = new URI(u).getRawPath().replaceAll("/+$", "");
            } catch (Exception e) {
                selfPath = "";
            }
            for (Entry e : parse(body)) {
                String href = e.href.replaceAll("/+$", "");
                if (href.endsWith(selfPath) || href.equals(selfPath)) continue;
                String name;
                try {
                    name = java.net.URLDecoder.decode(href.substring(href.lastIndexOf('/') + 1), "UTF-8");
                } catch (Exception ex) {
                    name = href.substring(href.lastIndexOf('/') + 1);
                }
                out.add(new RemoteEntry(name, dirPath.replaceAll("/+$", "") + "/" + name, e.size, e.folder));
            }
        }
        return out;
    }

    @Override
    public byte[] download(String remoteRef, long maxBytes) throws DestError {
        try (Response r = Http.execute(http, null, req(url(remoteRef)).get().build())) {
            if (!r.isSuccessful()) throw Http.error("WebDAV download", r, Http.body(r));
            return Http.bytes(r, maxBytes);
        } catch (DestError e) {
            throw e;
        } catch (Exception e) {
            throw DestError.network(e);
        }
    }

    // ── XML ───────────────────────────────────────────────────────────────

    static final class Entry {
        String href = "";
        long size = -1;
        boolean folder;
    }

    static List<Entry> parse(String xml) {
        List<Entry> out = new ArrayList<>();
        try {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance();
            f.setNamespaceAware(true);
            XmlPullParser p = f.newPullParser();
            p.setInput(new StringReader(xml));
            Entry cur = null;
            String text = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if ("response".equals(n)) cur = new Entry();
                    else if ("collection".equals(n) && cur != null) cur.folder = true;
                    text = null;
                } else if (ev == XmlPullParser.TEXT) {
                    text = p.getText();
                } else if (ev == XmlPullParser.END_TAG && cur != null) {
                    String n = p.getName();
                    if ("href".equals(n) && text != null) cur.href = text.trim();
                    else if ("getcontentlength".equals(n) && text != null) {
                        try {
                            cur.size = Long.parseLong(text.trim());
                        } catch (NumberFormatException ignored) {
                        }
                    } else if ("response".equals(n)) {
                        out.add(cur);
                        cur = null;
                    }
                    text = null;
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static long tag(String xml, String local) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<(?:[A-Za-z0-9_]+:)?" + local + "[^>]*>\\s*(-?\\d+)\\s*<")
                .matcher(xml);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }
}
