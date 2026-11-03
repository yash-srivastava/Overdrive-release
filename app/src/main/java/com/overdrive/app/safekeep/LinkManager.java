package com.overdrive.app.safekeep;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.safekeep.auth.OAuthApps;
import com.overdrive.app.safekeep.net.HttpFactory;

import org.json.JSONObject;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sign-in flows that work on a head unit without a browser: RFC 8628 device codes (Google,
 * Microsoft), Dropbox PKCE with a pasted code, Nextcloud Login Flow v2. The daemon polls, so
 * tokens land where the parked daemon can read them.
 */
final class LinkManager {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SafeKeepLink");
    static final String GOOGLE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String MS_SCOPE = "Files.ReadWrite.AppFolder offline_access";
    static final String MS_BASE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/";

    static final class Session {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final String type;
        volatile String state = "pending";
        volatile String error;
        volatile String userCode;
        volatile String verifyUrl;
        volatile String qrUrl;
        volatile long expiresAt;
        volatile long intervalMs = 5000;
        volatile String destId;
        String deviceCode;
        String pkceVerifier;
        String pollToken;
        String pollEndpoint;
        String server;
        OAuthApps.App app;
        JSONObject params = new JSONObject();
        volatile ScheduledFuture<?> poller;
        // Guarded by stateLock; a claimed commit is irrevocable, but stays publicly pending.
        boolean commitClaimed;
        boolean pasteInFlight;

        Session(String type) {
            this.type = type;
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("linkId", id).put("type", type).put("state", state).put("expiresAt", expiresAt);
                if (userCode != null) o.put("userCode", userCode);
                if (verifyUrl != null) o.put("verifyUrl", verifyUrl);
                if (error != null) o.put("error", error);
                if (destId != null) o.put("destId", destId);
                o.put("mode", "dropbox".equals(type) ? "paste" : ("nextcloud".equals(type) ? "browser" : "device"));
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private final Engine engine;
    // Linearizes cancellation against commit admission, never against storage or network I/O.
    // In particular cancelAll is called under SafeKeep's lifecycle monitor and must not wait
    // for a commit that can itself call SafeKeepAdmin -> SafeKeep.converge.
    private final Object stateLock = new Object();
    private final Map<String, Session> sessions = new HashMap<>();
    private boolean closed;

    LinkManager(Engine engine) {
        this.engine = engine;
    }

    private OkHttpClient http(JSONObject params) {
        return HttpFactory.client(HttpFactory.ProxyMode.parse(params.optString("proxyMode", "auto")),
                params.optString("certSha256", ""));
    }

    /**
     * Throws IllegalStateException with a user-facing code on configuration errors. A start that
     * outlives {@code deadlineMs} (the request already timed out) is discarded, never polled.
     */
    JSONObject start(String type, JSONObject params, long deadlineMs) throws Exception {
        pruneSessions();
        synchronized (stateLock) {
            if (closed) throw new IllegalStateException("disabled");
        }
        Session s = new Session(type);
        s.params = params == null ? new JSONObject() : params;
        reuseOwnApp(s);
        switch (type) {
            case "gdrive":
                s.app = OAuthApps.get("gdrive", s.params.optJSONObject("app"));
                if (!s.app.configured()) throw new IllegalStateException("oauth_app_missing");
                startGoogle(s);
                break;
            case "onedrive":
                s.app = OAuthApps.get("onedrive", s.params.optJSONObject("app"));
                if (!s.app.configured()) throw new IllegalStateException("oauth_app_missing");
                startMicrosoft(s);
                break;
            case "dropbox":
                s.app = OAuthApps.get("dropbox", s.params.optJSONObject("app"));
                if (!s.app.configured()) throw new IllegalStateException("oauth_app_missing");
                startDropbox(s);
                break;
            case "nextcloud":
                startNextcloud(s);
                break;
            default:
                throw new IllegalStateException("unsupported_type");
        }
        synchronized (stateLock) {
            // A start can complete its HTTP request after Engine.stop has closed this manager.
            if (closed) throw new IllegalStateException("disabled");
            if (System.currentTimeMillis() > deadlineMs) throw new IllegalStateException("timeout");
            sessions.put(s.id, s);
            if (!"dropbox".equals(type)) schedulePollLocked(s);
            return s.json();
        }
    }

    /** Reconnect keeps the user's own app registration: no need to paste the client ID again. */
    private static void reuseOwnApp(Session s) throws Exception {
        JSONObject app = s.params.optJSONObject("app");
        String relink = s.params.optString("relinkId", "");
        if (relink.isEmpty() || (app != null && !app.optString("clientId", "").trim().isEmpty())) return;
        JSONObject old = SafeKeepSecrets.get(relink);
        if (old.optBoolean("ownApp", false) && !old.optString("clientId", "").isEmpty()) {
            s.params.put("app", new JSONObject().put("clientId", old.optString("clientId"))
                    .put("clientSecret", old.optString("clientSecret", "")));
        }
    }

    JSONObject status(String id) {
        synchronized (stateLock) {
            Session s = sessions.get(id);
            if (s == null) {
                JSONObject o = new JSONObject();
                try {
                    o.put("linkId", id).put("state", "expired");
                } catch (Exception ignored) {
                }
                return o;
            }
            if (!s.commitClaimed && "pending".equals(s.state) && System.currentTimeMillis() > s.expiresAt) finishLocked(s, "expired", "code expired");
            return s.json();
        }
    }

    String qrText(String id) {
        synchronized (stateLock) {
            Session s = sessions.get(id);
            return s == null ? null : (s.qrUrl != null ? s.qrUrl : s.verifyUrl);
        }
    }

    /** False means commit already won: callers must not report successful cancellation. */
    boolean cancel(String id) {
        synchronized (stateLock) {
            Session s = sessions.get(id);
            if (s == null) return true; // Idempotent cancellation of absent/revoked sessions.
            if (s.commitClaimed || "done".equals(s.state)) return false;
            sessions.remove(id);
            finishLocked(s, "expired", null);
            return true;
        }
    }

    void cancelAll() {
        synchronized (stateLock) {
            closed = true;
            Iterator<Session> it = sessions.values().iterator();
            while (it.hasNext()) {
                Session s = it.next();
                if (s.commitClaimed || "done".equals(s.state)) continue;
                finishLocked(s, "expired", null);
                it.remove();
            }
            // Already accepted commits finish outside this lock; shutdown never waits for them.
        }
    }

    private void pruneSessions() {
        synchronized (stateLock) {
            long now = System.currentTimeMillis();
            Iterator<Session> it = sessions.values().iterator();
            while (it.hasNext()) {
                Session s = it.next();
                if (!s.commitClaimed && now > s.expiresAt + 10 * 60_000L) {
                    if ("pending".equals(s.state)) finishLocked(s, "expired", "code expired");
                    it.remove();
                }
            }
        }
    }

    // ── providers ─────────────────────────────────────────────────────────

    private void startGoogle(Session s) throws Exception {
        JSONObject j = postForm(http(s.params), "https://oauth2.googleapis.com/device/code",
                new FormBody.Builder().add("client_id", s.app.clientId).add("scope", GOOGLE_SCOPE).build());
        s.deviceCode = j.getString("device_code");
        s.userCode = j.getString("user_code");
        s.verifyUrl = webUrl(j.optString("verification_url", j.optString("verification_uri", "https://www.google.com/device")), false);
        s.expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 1800) * 1000L;
        s.intervalMs = Math.max(5, j.optInt("interval", 5)) * 1000L;
    }

    private void startMicrosoft(Session s) throws Exception {
        JSONObject j = postForm(http(s.params), MS_BASE + "devicecode",
                new FormBody.Builder().add("client_id", s.app.clientId).add("scope", MS_SCOPE).build());
        s.deviceCode = j.getString("device_code");
        s.userCode = j.getString("user_code");
        s.verifyUrl = webUrl(j.optString("verification_uri", "https://microsoft.com/devicelogin"), false);
        s.expiresAt = System.currentTimeMillis() + j.optLong("expires_in", 900) * 1000L;
        s.intervalMs = Math.max(5, j.optInt("interval", 5)) * 1000L;
    }

    private void startDropbox(Session s) throws Exception {
        byte[] rnd = new byte[48];
        new SecureRandom().nextBytes(rnd);
        s.pkceVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(s.pkceVerifier.getBytes("US-ASCII")));
        s.verifyUrl = "https://www.dropbox.com/oauth2/authorize?client_id=" + enc(s.app.clientId)
                + "&response_type=code&token_access_type=offline&code_challenge=" + challenge
                + "&code_challenge_method=S256";
        s.expiresAt = System.currentTimeMillis() + 30 * 60_000L;
    }

    /** Dropbox: the user pastes the code shown by dropbox.com. */
    JSONObject finishPaste(String id, String code) throws Exception {
        if (code == null || code.trim().isEmpty()) throw new IllegalStateException("code_required");
        Session s;
        String verifier;
        synchronized (stateLock) {
            s = sessions.get(id);
            if (s == null || !"dropbox".equals(s.type) || closed || !"pending".equals(s.state)) throw new IllegalStateException("link_expired");
            if (s.commitClaimed || s.pasteInFlight) throw new IllegalStateException("link_in_progress");
            if (System.currentTimeMillis() > s.expiresAt) {
                finishLocked(s, "expired", "code expired");
                throw new IllegalStateException("link_expired");
            }
            s.pasteInFlight = true;
            verifier = s.pkceVerifier;
        }
        try {
            JSONObject j = postForm(http(s.params), "https://api.dropboxapi.com/oauth2/token",
                    new FormBody.Builder().add("code", code.trim()).add("grant_type", "authorization_code")
                            .add("client_id", s.app.clientId).add("code_verifier", verifier).build());
            JSONObject secrets = tokens(j, s);
            created(s, "Dropbox", new JSONObject(), secrets);
            synchronized (stateLock) { return s.json(); }
        } finally {
            synchronized (stateLock) { s.pasteInFlight = false; }
        }
    }

    private void startNextcloud(Session s) throws Exception {
        String server = s.params.optString("server", "").trim().replaceAll("/+$", "");
        if (!server.startsWith("https://") && !server.startsWith("http://")) {
            throw new IllegalStateException("server_url_required");
        }
        s.server = server;
        Request req = new Request.Builder().url(server + "/index.php/login/v2")
                .header("User-Agent", "OverDrive SafeKeep").post(RequestBody.create(new byte[0], null)).build();
        try (Response r = http(s.params).newCall(req).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            if (!r.isSuccessful()) throw new IllegalStateException("nextcloud_http_" + r.code());
            JSONObject j = new JSONObject(body);
            JSONObject poll = j.getJSONObject("poll");
            s.pollToken = poll.getString("token");
            // The server picks these URLs: poll only our own server, and show only a web link.
            s.pollEndpoint = poll.getString("endpoint");
            if (!hostOf(s.pollEndpoint).equals(hostOf(server))) throw new IllegalStateException("nextcloud_bad_poll_url");
            s.verifyUrl = webUrl(j.getString("login"), server.startsWith("http://"));
        }
        s.expiresAt = System.currentTimeMillis() + 20 * 60_000L;
        s.intervalMs = 2000;
    }

    /** Called only under stateLock, including slow_down rescheduling. */
    private void schedulePollLocked(Session s) {
        if (!pendingLocked(s)) return;
        try {
            s.poller = engine.aux.scheduleWithFixedDelay(() -> {
                try {
                    poll(s);
                } catch (Throwable t) {
                    logger.warn("Link poll failed: " + t.getMessage());
                }
            }, s.intervalMs, s.intervalMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            finishLocked(s, "error", "could not start polling");
        }
    }

    private void poll(Session s) throws Exception {
        String deviceCode;
        String pollToken;
        synchronized (stateLock) {
            if (!pendingLocked(s)) return;
            if (System.currentTimeMillis() > s.expiresAt) {
                finishLocked(s, "expired", "code expired");
                return;
            }
            // Cancellation can clear the session while an already-started HTTP call completes.
            deviceCode = s.deviceCode;
            pollToken = s.pollToken;
        }
        OkHttpClient http = http(s.params);
        if ("nextcloud".equals(s.type)) {
            Request req = new Request.Builder().url(s.pollEndpoint)
                    .post(new FormBody.Builder().add("token", pollToken).build()).build();
            try (Response r = http.newCall(req).execute()) {
                if (r.code() == 404) return;
                String body = r.body() == null ? "" : r.body().string();
                if (!r.isSuccessful()) return;
                JSONObject j = new JSONObject(body);
                String login = j.getString("loginName");
                String appPassword = j.getString("appPassword");
                String server = j.optString("server", s.server).replaceAll("/+$", "");
                if (!hostOf(server).equals(hostOf(s.server))) server = s.server;
                // The WebDAV path uses the account id, which differs from the login name for e-mail or LDAP logins.
                String userId = nextcloudUserId(http, server, login, appPassword);
                JSONObject params = new JSONObject()
                        .put("url", server + "/remote.php/dav/files/" + pathEnc(userId) + "/")
                        .put("mode", "nextcloud").put("username", login);
                if (!s.params.optString("certSha256", "").isEmpty()) params.put("certSha256", s.params.optString("certSha256"));
                String host = hostOf(server);
                created(s, "Nextcloud · " + host, params,
                        new JSONObject().put("username", login).put("password", appPassword));
            }
            return;
        }
        String tokenUrl = "gdrive".equals(s.type) ? "https://oauth2.googleapis.com/token" : MS_BASE + "token";
        FormBody.Builder fb = new FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("client_id", s.app.clientId)
                .add("device_code", deviceCode);
        if ("gdrive".equals(s.type)) fb.add("client_secret", s.app.clientSecret);
        Request req = new Request.Builder().url(tokenUrl).post(fb.build()).build();
        try (Response r = http.newCall(req).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            JSONObject j;
            try {
                j = new JSONObject(body);
            } catch (Exception e) {
                return;
            }
            if (r.isSuccessful() && j.has("access_token")) {
                String name = "gdrive".equals(s.type) ? "Google Drive" : "OneDrive";
                created(s, name, new JSONObject(), tokens(j, s));
                return;
            }
            String err = j.optString("error", "");
            switch (err) {
                case "authorization_pending":
                    return;
                case "slow_down":
                    synchronized (stateLock) {
                        if (!pendingLocked(s)) return;
                        s.intervalMs += 5000;
                        if (s.poller != null) s.poller.cancel(false);
                        schedulePollLocked(s);
                    }
                    return;
                case "access_denied":
                case "authorization_declined":
                    finish(s, "error", "access_denied");
                    return;
                case "expired_token":
                    finish(s, "expired", "code expired");
                    return;
                default:
                    if (r.code() >= 500) return;
                    finish(s, "error", err.isEmpty() ? ("HTTP " + r.code()) : err);
            }
        }
    }

    private static JSONObject tokens(JSONObject j, Session session) throws Exception {
        OAuthApps.App app = session.app;
        String refresh = j.optString("refresh_token", "");
        if (refresh.isEmpty()) throw new IllegalStateException("no_refresh_token");
        JSONObject own = session.params.optJSONObject("app");
        JSONObject s = new JSONObject()
                .put("accessToken", j.optString("access_token", ""))
                .put("refreshToken", refresh)
                .put("expiresAt", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L)
                .put("refreshedAt", System.currentTimeMillis())
                // Lets a later refresh failure be explained (e.g. a user's Google project left in Testing).
                .put("provider", session.type)
                .put("ownApp", own != null && !own.optString("clientId", "").trim().isEmpty())
                .put("linkedAt", System.currentTimeMillis())
                .put("clientId", app.clientId);
        if (!app.clientSecret.isEmpty()) s.put("clientSecret", app.clientSecret);
        return s;
    }

    /** Persists the new destination (secrets first, fail-closed) and wires default routes. */
    private void created(Session s, String name, JSONObject params, JSONObject secrets) throws Exception {
        if (!claimCommit(s)) return;
        try {
            String relink = s.params.optString("relinkId", "");
            if (!relink.isEmpty() && engine.cfg.destination(relink) != null) {
                // Reconnect: new tokens for the existing destination; its queue and history stay.
                if (!SafeKeepSecrets.put(relink, secrets)) {
                    finishCommit(s, "error", "could not store credentials securely");
                    return;
                }
                if (params.length() > 0) SafeKeepAdmin.updateDestination(relink, new JSONObject().put("params", params));
                engine.runCtl(() -> engine.setDestState(relink, "ok", null, 0));
                s.destId = relink;
                finishCommit(s, "done", null);
                return;
            }
            String destId = s.type + "-" + UUID.randomUUID().toString().substring(0, 6);
            if (!SafeKeepSecrets.put(destId, secrets)) {
                finishCommit(s, "error", "could not store credentials securely");
                return;
            }
            String proxy = s.params.optString("proxyMode", "auto");
            boolean ok = SafeKeepAdmin.addDestination(destId, s.type, s.params.optString("name", name), params, proxy);
            if (!ok) {
                SafeKeepSecrets.remove(destId);
                finishCommit(s, "error", "could not save destination");
                return;
            }
            s.destId = destId;
            finishCommit(s, "done", null);
        } finally {
            // Also publish a terminal state for an unexpected storage/lifecycle exception.
            synchronized (stateLock) {
                if (s.commitClaimed) finishLocked(s, "error", "could not save destination");
            }
        }
    }

    private boolean pendingLocked(Session s) {
        return !closed && sessions.get(s.id) == s && "pending".equals(s.state) && !s.commitClaimed;
    }

    /** Cancellation and the first persistence operation have one admission decision. */
    private boolean claimCommit(Session s) {
        synchronized (stateLock) {
            if (!pendingLocked(s)) return false;
            if (System.currentTimeMillis() > s.expiresAt) {
                finishLocked(s, "expired", "code expired");
                return false;
            }
            s.commitClaimed = true;
            if (s.poller != null) s.poller.cancel(false);
            return true;
        }
    }

    private void finishCommit(Session s, String state, String error) {
        synchronized (stateLock) {
            if (s.commitClaimed) finishLocked(s, state, error);
        }
    }

    private void finish(Session s, String state, String error) {
        synchronized (stateLock) {
            if (pendingLocked(s)) finishLocked(s, state, error);
        }
    }

    private void finishLocked(Session s, String state, String error) {
        s.state = state;
        s.error = error;
        s.commitClaimed = false;
        if (s.poller != null) s.poller.cancel(false);
        s.deviceCode = null;
        s.pkceVerifier = null;
        s.pollToken = null;
    }

    private static JSONObject postForm(OkHttpClient http, String url, FormBody body) throws Exception {
        Request req = new Request.Builder().url(url).post(body).build();
        try (Response r = http.newCall(req).execute()) {
            String text = r.body() == null ? "" : r.body().string();
            JSONObject j;
            try {
                j = new JSONObject(text);
            } catch (Exception e) {
                j = new JSONObject();
            }
            if (!r.isSuccessful()) {
                String err = j.optString("error_description", j.optString("error", "HTTP " + r.code()));
                throw new IllegalStateException("provider_error:" + err);
            }
            return j;
        }
    }

    /** Nextcloud account id for the app password (falls back to the login name). */
    private static String nextcloudUserId(OkHttpClient http, String server, String login, String appPassword) {
        Request req = new Request.Builder().url(server + "/ocs/v1.php/cloud/user?format=json")
                .header("OCS-APIRequest", "true")
                .header("Authorization", okhttp3.Credentials.basic(login, appPassword, java.nio.charset.StandardCharsets.UTF_8))
                .get().build();
        try (Response r = http.newCall(req).execute()) {
            if (r.isSuccessful() && r.body() != null) {
                JSONObject data = new JSONObject(r.body().string()).optJSONObject("ocs");
                data = data == null ? null : data.optJSONObject("data");
                String id = data == null ? "" : data.optString("id", "");
                if (!id.isEmpty()) return id;
            }
        } catch (Exception ignored) {
        }
        return login;
    }

    /** Only http(s) links reach the page (a javascript: link would run inside the local UI). */
    private static String webUrl(String url, boolean allowHttp) {
        String u = url == null ? "" : url.trim();
        String l = u.toLowerCase(Locale.ROOT);
        if (l.startsWith("https://") || (allowHttp && l.startsWith("http://"))) return u;
        throw new IllegalStateException("bad_provider_url");
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String pathEnc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
    }

    private static String hostOf(String url) {
        try {
            return new java.net.URI(url).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return url;
        }
    }
}
