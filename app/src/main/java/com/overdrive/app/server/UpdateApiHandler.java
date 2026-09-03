package com.overdrive.app.server;

import android.content.Context;
import com.overdrive.app.util.ScratchPaths;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.updater.AppUpdater;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Update API — exposes app-update operations to the webapp so the user can
 * check for and trigger an OTA from any browser tab.
 *
 * Endpoints:
 *   GET  /api/update/check    → {available, currentVersion, remoteVersion, releaseNotes}
 *   GET  /api/update/preview  → {tunnelType, tunnelUrlMayChange, localIpAddresses,
 *                                 estimatedDowntimeSeconds, recommendInApp}
 *   POST /api/update/install?confirm=true → {status:"scheduled"}; runs the install on
 *                                            a bg thread, daemons die mid-stream.
 *   GET  /api/update/progress → {phase, percent, message, version, error?}
 *
 * Public-mode is rejected for /install — anyone with a tunnel link must NOT
 * be able to push updates remotely. Check + preview + progress are read-only,
 * so they're allowed in either access mode (still gated by AuthMiddleware).
 *
 * Progress is written as JSON to /data/local/tmp/overdrive_update_progress.json
 * so it survives the inevitable daemon-restart mid-install. The webapp polls
 * this endpoint every 1-2s; when polling fails (daemon dead), the webapp
 * switches to "reconnecting" mode and retries /api/status until appVersion
 * advances.
 */
public class UpdateApiHandler {

    private static final String TAG = "UpdateApi";
    private static final String PROGRESS_FILE = ScratchPaths.path("overdrive_update_progress.json");

    // One install at a time. AtomicReference so we don't hold an updater past
    // the install (it's GC'd along with the dying process anyway).
    private static final AtomicReference<AppUpdater> activeUpdater = new AtomicReference<>();
    // Concurrency guard moved to AppUpdater.tryBeginInstall()/endInstall() so
    // the web (this handler), app-IPC, and Telegram-IPC install entry points —
    // all running in this same daemon JVM — share ONE in-flight gate. Previously
    // this flag was private here, so the IPC path could start a second install
    // racing a web install (double pkill cascade + APK rm). See AppUpdater.

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/update/check") && method.equals("GET")) {
            handleCheck(out);
            return true;
        }
        if (path.equals("/api/update/preview") && method.equals("GET")) {
            handlePreview(out);
            return true;
        }
        // GET → return resolved channel; POST → switch channel (allowlist +
        // public-mode gated). Check the path PREFIX so query params on POST
        // (?value=…) still route here.
        if (path.startsWith("/api/update/channel")) {
            if (method.equals("GET")) { handleGetChannel(out); return true; }
            if (method.equals("POST")) { handleSetChannel(path, out); return true; }
        }
        if (path.startsWith("/api/update/versions") && method.equals("GET")) {
            handleVersions(out);
            return true;
        }
        if (path.startsWith("/api/update/install") && method.equals("POST")) {
            handleInstall(path, out);
            return true;
        }
        if (path.equals("/api/update/progress") && method.equals("GET")) {
            handleProgress(out);
            return true;
        }
        return false;
    }

    // ================== /api/update/check ==================

    private static void handleCheck(OutputStream out) throws Exception {
        // Outer try wraps the entire handler so a caller waiting on a tunnel
        // (Cloudflared/zrok) ALWAYS gets a JSON body back — even on
        // unexpected exceptions. Without this, an exception inside the
        // AppUpdater chain bubbles up to HttpServer.handleClient's catch
        // which only logs and closes the socket, leaving the client to
        // parse an empty body ("JSON.parse: unexpected end of data").
        try {
        Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_app_context_not_ready"));
            return;
        }

        final String channel = com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel();

        // Alpha is a browse-and-pick archive — there is no single "the update"
        // to push. Tell the client to open the catalog instead of an install
        // prompt. (forceReload first so a freshly-toggled channel is seen.)
        if (AppUpdater.CHANNEL_ALPHA.equals(channel)) {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            JSONObject r = new JSONObject();
            r.put("available", false);
            r.put("channel", channel);
            r.put("currentVersion", AppUpdater.getDisplayVersionFromFile());
            r.put("catalogEndpoint", "/api/update/versions");
            HttpResponse.sendJson(out, r.toString());
            return;
        }

        // Run synchronously by blocking on a callback latch. AppUpdater.checkForUpdate
        // dispatches to its own executor + posts to mainHandler, so we wait here.
        // Cap wait at 12s so the tunnel timeout (typically 30s) has plenty
        // of headroom to deliver the response. Public tunnels (zrok, free
        // Cloudflared) sometimes terminate idle connections aggressively
        // around the 20-30s mark, leaving the user with the dreaded empty
        // body. 12s + a couple seconds for the server to format and send
        // is well inside that window.
        final Object lock = new Object();
        final boolean[] done = {false};
        final JSONObject[] resultRef = {null};

        AppUpdater updater = new AppUpdater(ctx);
        updater.checkForUpdate(new AppUpdater.UpdateCallback() {
            @Override public void onUpdateAvailable(String currentVersion, String newVersion, String releaseNotes) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", true);
                    r.put("channel", channel);
                    r.put("currentVersion", currentVersion);
                    r.put("remoteVersion", newVersion);
                    r.put("releaseNotes", releaseNotes != null ? releaseNotes : "");
                } catch (Exception ignored) {}
                resultRef[0] = r;
                signal(lock, done);
            }
            @Override public void onNoUpdate(String currentVersion) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", false);
                    r.put("channel", channel);
                    r.put("currentVersion", currentVersion);
                    r.put("remoteVersion", currentVersion);
                    r.put("releaseNotes", "");
                } catch (Exception ignored) {}
                resultRef[0] = r;
                signal(lock, done);
            }
            @Override public void onError(String error) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", false);
                    r.put("channel", channel);
                    r.put("error", error != null ? error : "unknown");
                    r.put("currentVersion", AppUpdater.getDisplayVersionFromFile());
                } catch (Exception ignored) {}
                resultRef[0] = r;
                signal(lock, done);
            }
        });

        synchronized (lock) {
            if (!done[0]) lock.wait(12_000);
        }
        // Release per-instance executor + tunnel-poll scheduler. The
        // updater is a one-shot for this request — without close() its
        // AdbDaemonLauncher's executor stays parked for the JVM. Idempotent.
        try { updater.close(); } catch (Exception ignored) {}

        if (resultRef[0] == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_check_timed_out"));
            return;
        }
        HttpResponse.sendJson(out, resultRef[0].toString());
        } catch (Throwable t) {
            // Final safety net: any exception that reaches here means we
            // never sent a response. Log it and emit a JSON error so the
            // client doesn't get an empty body and crash on parse.
            CameraDaemon.log("update/check failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            try {
                HttpResponse.sendJsonError(out,
                        "Update check failed: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            } catch (Exception ignored) { /* socket already gone */ }
        }
    }

    // ================== /api/update/channel ==================

    /** Return the resolved update channel. Read-only; allowed in PUBLIC mode. */
    private static void handleGetChannel(OutputStream out) throws Exception {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            String channel = com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel();
            JSONObject r = new JSONObject();
            r.put("channel", channel);
            HttpResponse.sendJson(out, r.toString());
        } catch (Throwable t) {
            HttpResponse.sendJsonError(out, "Channel read failed: "
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
        }
    }

    /**
     * Switch the update channel. Gated by the SAME public-mode hard-block as
     * /install — a tunnel visitor must not be able to flip the owner's
     * channel. Validates the value against the {alpha, braveheart} allowlist
     * so a crafted request can't set an arbitrary GitHub tag as the channel.
     */
    private static void handleSetChannel(String path, OutputStream out) throws Exception {
        if (CameraDaemon.isPublicMode()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_disabled_in_public_mode"));
            return;
        }
        String value = queryParam(path, "value");
        if (!AppUpdater.CHANNEL_ALPHA.equals(value)
                && !AppUpdater.CHANNEL_BRAVEHEART.equals(value)) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_invalid_channel"));
            return;
        }
        // Daemon HTTP worker thread (not the looper) — a full-JSON rewrite
        // here is fine. setUpdateChannel writes locally + atomically in the
        // daemon process.
        boolean ok = com.overdrive.app.config.UnifiedConfigManager.setUpdateChannel(value);
        if (!ok) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_channel_write_failed"));
            return;
        }
        com.overdrive.app.config.UnifiedConfigManager.forceReload();
        JSONObject r = new JSONObject();
        r.put("channel", value);
        HttpResponse.sendJson(out, r.toString());
    }

    // ================== /api/update/versions ==================

    /**
     * Channel-aware version catalog. Alpha returns the full pick-any archive;
     * braveheart returns a single-element list (the rolling head) so web AND
     * native share one render path. Read-only; allowed in PUBLIC mode.
     *
     * Same 12s-latch + outer try-catch + always-JSON discipline as
     * handleCheck so a flaky tunnel never receives an empty body.
     */
    private static void handleVersions(OutputStream out) throws Exception {
        try {
            Context ctx = CameraDaemon.getAppContext();
            if (ctx == null) {
                HttpResponse.sendJsonError(out, Messages.get("errors.update_app_context_not_ready"));
                return;
            }
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            final String channel = com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel();

            final Object lock = new Object();
            final boolean[] done = {false};
            final JSONObject[] resultRef = {null};

            AppUpdater updater = new AppUpdater(ctx);

            if (AppUpdater.CHANNEL_ALPHA.equals(channel)) {
                updater.listVersions(new AppUpdater.VersionListCallback() {
                    @Override public void onResult(java.util.List<AppUpdater.VersionEntry> versions, String currentVersion) {
                        JSONObject r = new JSONObject();
                        try {
                            r.put("channel", channel);
                            r.put("currentVersion", currentVersion);
                            JSONArray arr = new JSONArray();
                            for (AppUpdater.VersionEntry v : versions) arr.put(v.toJson());
                            r.put("versions", arr);
                        } catch (Exception ignored) {}
                        resultRef[0] = r;
                        signal(lock, done);
                    }
                    @Override public void onError(String error) {
                        JSONObject r = new JSONObject();
                        try {
                            r.put("channel", channel);
                            r.put("error", error != null ? error : "unknown");
                            r.put("currentVersion", AppUpdater.getDisplayVersionFromFile());
                        } catch (Exception ignored) {}
                        resultRef[0] = r;
                        signal(lock, done);
                    }
                });
            } else {
                // Braveheart (rolling): one entry derived from the timestamp
                // check, so the client renders it in the same catalog shape.
                updater.checkForUpdate(new AppUpdater.UpdateCallback() {
                    @Override public void onUpdateAvailable(String currentVersion, String newVersion, String releaseNotes) {
                        resultRef[0] = braveheartCatalog(channel, currentVersion, newVersion, releaseNotes, true);
                        signal(lock, done);
                    }
                    @Override public void onNoUpdate(String currentVersion) {
                        resultRef[0] = braveheartCatalog(channel, currentVersion, currentVersion, "", false);
                        signal(lock, done);
                    }
                    @Override public void onError(String error) {
                        JSONObject r = new JSONObject();
                        try {
                            r.put("channel", channel);
                            r.put("error", error != null ? error : "unknown");
                            r.put("currentVersion", AppUpdater.getDisplayVersionFromFile());
                        } catch (Exception ignored) {}
                        resultRef[0] = r;
                        signal(lock, done);
                    }
                });
            }

            synchronized (lock) {
                if (!done[0]) lock.wait(12_000);
            }
            try { updater.close(); } catch (Exception ignored) {}

            if (resultRef[0] == null) {
                HttpResponse.sendJsonError(out, Messages.get("errors.update_check_timed_out"));
                return;
            }
            HttpResponse.sendJson(out, resultRef[0].toString());
        } catch (Throwable t) {
            CameraDaemon.log("update/versions failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            try {
                HttpResponse.sendJsonError(out, "Version list failed: "
                        + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            } catch (Exception ignored) {}
        }
    }

    /** Build a single-element braveheart catalog payload. */
    private static JSONObject braveheartCatalog(String channel, String currentVersion,
                                                String remoteVersion, String releaseNotes,
                                                boolean available) {
        JSONObject r = new JSONObject();
        try {
            r.put("channel", channel);
            r.put("currentVersion", currentVersion);
            JSONArray arr = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("version", remoteVersion);
            entry.put("tag", AppUpdater.CHANNEL_BRAVEHEART);
            entry.put("downloadUrl", "");          // install path uses no &version for braveheart
            entry.put("releaseNotes", releaseNotes != null ? releaseNotes : "");
            entry.put("relation", available ? "newer" : "current");
            entry.put("available", available);
            arr.put(entry);
            r.put("versions", arr);
        } catch (Exception ignored) {}
        return r;
    }

    // ================== /api/update/preview ==================

    /**
     * Pre-install context for the confirmation modal. Tells the webapp:
     *   - which tunnel is active and whether its URL will change
     *   - the local LAN IP(s) (faster recovery path)
     *   - estimated downtime
     *   - whether to recommend the in-car app over the webapp (always true:
     *     BYD wipes the autostart whitelist on every install, and only the
     *     in-car SetupGuideDialog can deep-link to com.byd.appstartmanagement
     *     to re-enable it)
     */
    private static void handlePreview(OutputStream out) throws Exception {
        JSONObject r = new JSONObject();

        // Detect active tunnel. Two signals:
        //   1. /data/local/tmp/tunnel_url.txt — written by TelegramBotDaemon's
        //      saveTunnelUrl helper, but only if the user has Telegram set up.
        //   2. Live process probe via `pgrep` — works regardless of Telegram.
        // We prefer the URL-based signal (more specific — distinguishes free vs.
        // named cloudflared tunnels) and fall back to process probe.
        // AdbDaemonLauncher.tunnelType is intentionally NOT used; it's a default
        // value that's never reassigned at runtime.
        String lastUrl = readTextFile(ScratchPaths.path("tunnel_url.txt"));
        String tunnelType = "none";
        boolean tunnelUrlMayChange = false;
        if (lastUrl != null && !lastUrl.isEmpty()) {
            if (lastUrl.contains(".trycloudflare.com")) {
                tunnelType = "cloudflared";
                tunnelUrlMayChange = true;          // free quick-tunnel rotates
            } else if (lastUrl.contains("cfargotunnel.com")) {
                tunnelType = "cloudflared";
                tunnelUrlMayChange = false;          // named tunnel = stable
            } else if (lastUrl.contains(".share.zrok.io")) {
                tunnelType = "zrok";                 // reserved-token URL is stable
                tunnelUrlMayChange = false;
            } else if (lastUrl.contains(".ts.net") || lastUrl.matches(".*100\\.[0-9.]+.*")) {
                tunnelType = "tailscale";
                tunnelUrlMayChange = false;
            } else {
                tunnelType = "unknown";
            }
        }

        // Process-probe fallback for users without Telegram (so tunnel_url.txt
        // doesn't exist). The URL pattern path is preferred because it can
        // distinguish free-quick from named cloudflared tunnels; this fallback
        // assumes free-quick (worst case) when only cloudflared is detected.
        if ("none".equals(tunnelType)) {
            if (isProcessRunning("cloudflared")) {
                tunnelType = "cloudflared";
                tunnelUrlMayChange = true;  // assume free quick-tunnel without URL evidence
            } else if (isProcessRunning("zrok")) {
                tunnelType = "zrok";
                tunnelUrlMayChange = false;
            } else if (isProcessRunning("tailscaled")) {
                tunnelType = "tailscale";
                tunnelUrlMayChange = false;
            }
        }

        r.put("tunnelType", tunnelType);
        r.put("tunnelUrlMayChange", tunnelUrlMayChange);
        if (lastUrl != null) r.put("currentTunnelUrl", lastUrl);

        // Local LAN IPs (non-loopback IPv4). Useful so the webapp can suggest
        // "switch to 192.168.x.x for faster recovery."
        JSONArray ips = new JSONArray();
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (java.net.InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        ips.put(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        r.put("localIpAddresses", ips);

        // Realistic downtime: hard-reset (~5s) + APK install (~10s) + new
        // process boot (~5s) + 45s system stabilization + tunnel handshake
        // (~15s) ≈ 2 to 2.5 minutes. Local network recovers ~90s sooner.
        r.put("estimatedDowntimeSeconds", 150);
        r.put("localRecoverySeconds", 60);

        // Always recommend the in-car app: BYD's auto-start whitelist needs
        // re-checking after every install, and only the in-car SetupGuideDialog
        // deep-links to com.byd.appstartmanagement.
        r.put("recommendInApp", true);
        r.put("recommendInAppReason",
                "BYD clears the auto-start whitelist on every install. " +
                "The in-car app prompts you to re-enable it; the webapp can't.");

        HttpResponse.sendJson(out, r.toString());
    }

    // ================== /api/update/install ==================

    private static void handleInstall(String path, OutputStream out) throws Exception {
        // Public-mode hard-block: refuse install endpoint entirely if streaming
        // is in PUBLIC mode. Anyone with a sharing link should not be able to
        // push an APK to the head unit.
        if (CameraDaemon.isPublicMode()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_disabled_in_public_mode"));
            return;
        }

        // Require explicit ?confirm=true to prevent any accidental fetch from
        // pushing an install. The webapp passes this only after the user clicks
        // "Install Anyway" in the confirmation modal.
        if (!path.contains("confirm=true")) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_missing_confirm"));
            return;
        }

        // Shared gate (AppUpdater) — covers web + app-IPC + Telegram-IPC. We
        // acquire AFTER the public-mode + confirm checks but BEFORE the
        // pre-install check/network so a concurrent trigger is rejected early.
        // On any pre-spawn bail below we must endInstall(); the success path
        // leaves it held (process dies; INSTALL_STALE_MS self-recovers).
        if (!AppUpdater.tryBeginInstall()) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_already_in_progress"));
            return;
        }

        Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            AppUpdater.endInstall();
            HttpResponse.sendJsonError(out, Messages.get("errors.update_app_context_not_ready"));
            return;
        }

        AppUpdater updater = new AppUpdater(ctx);
        activeUpdater.set(updater);

        // Targeted (alpha pick): a &version=<tag> selects a specific archived
        // release. Resolve it SERVER-SIDE (never a client-supplied URL) and
        // SKIP the braveheart "is an update available?" gate — alpha never
        // returns onUpdateAvailable, so that gate would dead-on-arrival every
        // alpha install. prepareInstall seeds latestDownloadUrl/remoteVersion/
        // remoteUpdatedAt, then the unchanged downloadAndInstall takes over.
        String version = queryParam(path, "version");
        if (version != null && !version.isEmpty()) {
            // Channel/tag consistency guard: a &version=alpha* targeted install
            // is ONLY valid on the alpha channel. Reject it on braveheart so an
            // alpha APK can't be installed on a braveheart device and silently
            // corrupt the braveheart per-channel baseline. Resolve fresh (the
            // toggle may have just changed it).
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            String activeChannel = com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel();
            // Strict tag validation (not a loose prefix) — rejects a crafted
            // tag before it can reach prepareInstall / the shell-interpolated
            // install script. The only supported targeted tags are alpha /
            // alpha-v<semver>.
            if (!AppUpdater.isValidAlphaTag(version)) {
                AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                HttpResponse.sendJsonError(out, Messages.get("errors.update_channel_tag_mismatch"));
                return;
            }
            if (!AppUpdater.CHANNEL_ALPHA.equals(activeChannel)) {
                AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                HttpResponse.sendJsonError(out, Messages.get("errors.update_channel_tag_mismatch"));
                return;
            }
            try {
                updater.prepareInstall(version);
            } catch (Exception e) {
                AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                HttpResponse.sendJsonError(out,
                        Messages.get("errors.update_pre_install_failed_with_detail",
                                e.getMessage() != null ? e.getMessage() : "resolve failed"));
                return;
            }
        } else {
            // Braveheart (rolling): first check (synchronous) so /install isn't
            // usable to download a random APK without the matching /check
            // having resolved. Also populates latestDownloadUrl + remoteVersion.
            final Object lock = new Object();
            final boolean[] done = {false};
            final boolean[] available = {false};
            final String[] err = {null};

            updater.checkForUpdate(new AppUpdater.UpdateCallback() {
                @Override public void onUpdateAvailable(String c, String n, String rn) {
                    available[0] = true;
                    signal(lock, done);
                }
                @Override public void onNoUpdate(String c) {
                    signal(lock, done);
                }
                @Override public void onError(String e) {
                    err[0] = e;
                    signal(lock, done);
                }
            });
            try {
                synchronized (lock) {
                    if (!done[0]) lock.wait(20_000);
                }
            } catch (InterruptedException ignored) {
                // HttpServer runs handleClient on a fixed thread pool and tears
                // it down with threadPool.shutdownNow() (server restart /
                // reconfigure), which interrupts every worker. Object.wait()
                // throws the checked InterruptedException, and handleInstall is
                // `throws Exception`, so an unguarded interrupt would escape to
                // HttpServer.handleClient's `catch (Exception e) {}` — which only
                // logs + closes the socket and NEVER calls AppUpdater.endInstall().
                // That wedges the shared install gate (web + app + Telegram) for
                // INSTALL_STALE_MS (5 min) with no install running. Catch it here
                // (restoring the interrupt flag) and fall through to the gated
                // `!available[0]` bail below, matching the IPC twin
                // (SurveillanceIpcServer.handleInstallUpdate).
                Thread.currentThread().interrupt();
            }
            if (err[0] != null) {
                // Update check errored — release the gate + per-instance executor.
                AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                HttpResponse.sendJsonError(out, Messages.get("errors.update_pre_install_failed_with_detail", err[0]));
                return;
            }
            if (!available[0]) {
                // No update — release the gate + per-instance executor before bail.
                AppUpdater.endInstall();
                try { updater.close(); } catch (Exception ignored) {}
                HttpResponse.sendJsonError(out, Messages.get("errors.update_no_update_available"));
                return;
            }
        }

        // Reply to the webapp BEFORE kicking the install. Once daemons start
        // dying, the response would never make it back. From here on, the
        // webapp polls /api/update/progress. The gate is already held (acquired
        // at the top via tryBeginInstall); it stays held through the install and
        // self-recovers via INSTALL_STALE_MS if the kill cascade misses us.
        //
        // CRITICAL: this synchronous pre-spawn region must release the gate on
        // ANY throw. sendJson() declares `throws Exception` and writes over a
        // tunnel socket that free cloudflared/zrok links routinely drop right
        // around the confirm-POST reply — if that throws, HttpServer.handleClient
        // (the only caller) merely logs + closes the socket and NEVER calls
        // endInstall(). The install thread below would never start, leaving the
        // shared gate wedged for INSTALL_STALE_MS (5 min) with nothing installing
        // — blocking the next web/app/Telegram attempt. Guard it ourselves: on a
        // throw before Thread.start(), release the gate + close the updater, then
        // rethrow (the caller still closes the socket). Once the thread is
        // running the held gate is correct — the thread's own onError/onSuccess/
        // crash handlers own it.
        try {
            writeProgress("queued", 0, Messages.get("messages.update_queued"), null);

            JSONObject r = new JSONObject();
            r.put("status", "scheduled");
            r.put("estimatedDowntimeSeconds", 150);
            HttpResponse.sendJson(out, r.toString());
        } catch (Exception e) {
            AppUpdater.endInstall();
            try { updater.close(); } catch (Exception ignored) {}
            throw e;
        }

        // Background install on a fresh thread. The current thread returns
        // to the HttpServer worker pool.
        new Thread(() -> {
            try {
                updater.downloadAndInstall(new AppUpdater.InstallCallback() {
                    @Override public void onProgress(String message) {
                        // Phase classification from message text — best-effort.
                        // The AppUpdater progress messages we'll see in order:
                        //   "Downloading update..."
                        //   "Verifying download..."
                        //   "Stopping daemons..."
                        //   "Installing..."
                        //   "✅ Update installed! Restarting..."
                        String m = message == null ? "" : message;
                        String phase = "downloading";
                        if (m.contains("Verifying")) phase = "verifying";
                        else if (m.contains("Stopping daemons")) phase = "stopping_daemons";
                        else if (m.contains("Installing")) phase = "installing";
                        else if (m.contains("installed")) phase = "installing";
                        writeProgress(phase, -1, m, null);
                    }
                    @Override public void onDownloadProgress(int percent) {
                        // Coalesce: AppUpdater fires this on every percent
                        // change (up to 100 times) but writeProgress is a
                        // synchronous full-file rewrite. Skipping smaller
                        // steps keeps the download throughput from being
                        // gated by /data/local/tmp write latency. Keep all
                        // edge transitions (-1 → first-real, 99 → 100,
                        // first-real after 0).
                        long now = System.currentTimeMillis();
                        boolean atEdge = percent < 0 || percent >= 99
                                || lastWrittenPercent < 0;
                        boolean stepEnough = (percent - lastWrittenPercent) >= 2;
                        boolean timeEnough = (now - lastWrittenAt) >= 500;
                        if (!(atEdge || stepEnough || timeEnough)) return;
                        lastWrittenPercent = percent;
                        lastWrittenAt = now;
                        writeProgress("downloading", percent,
                                percent < 0
                                    ? Messages.get("messages.update_downloading_indeterminate")
                                    : Messages.get("messages.update_downloading_with_percent", percent),
                                null);
                    }
                    private int lastWrittenPercent = -1;
                    private long lastWrittenAt = 0;
                    @Override public void onSuccess() {
                        writeProgress("installing", 100,
                                Messages.get("messages.update_installing_finishing"), null);
                        // Process should die before this matters, but defensive.
                        AppUpdater.endInstall();
                        // pm install kills the process so leak doesn't materialize, but
                        // close() is idempotent and cheap — defensive.
                        try { updater.close(); } catch (Exception ignored) {}
                    }
                    @Override public void onError(String error) {
                        writeProgress("error", -1, Messages.get("messages.update_install_failed"), error);
                        AppUpdater.endInstall();
                        // Install failed; release per-instance executor.
                        try { updater.close(); } catch (Exception ignored) {}
                    }
                });
            } catch (Exception e) {
                writeProgress("error", -1, Messages.get("messages.update_install_crashed"), e.getMessage());
                AppUpdater.endInstall();
            }
        }, "UpdateApi-Install").start();
    }

    // ================== /api/update/progress ==================

    private static void handleProgress(OutputStream out) throws Exception {
        File f = new File(PROGRESS_FILE);
        if (!f.exists()) {
            // No install ever started, or the file was cleaned up after a
            // long-completed install. Return a sentinel "idle".
            JSONObject r = new JSONObject();
            r.put("phase", "idle");
            r.put("percent", -1);
            r.put("message", "");
            HttpResponse.sendJson(out, r.toString());
            return;
        }
        String json = readTextFile(PROGRESS_FILE);
        if (json == null || json.isEmpty()) {
            // Empty/zero-byte read: the non-atomic writeProgress (new FileWriter
            // truncates-then-writes) leaves a momentary zero-length window a poll
            // can land in. Return the idle sentinel — same as the malformed-JSON
            // catch below and the IPC reader (SurveillanceIpcServer
            // .handleGetUpdateProgress) — rather than {success:false,error} (which
            // has no phase, so the web renderer drew a blank label / indeterminate
            // bar for that tick). Keeps the empty-read contract identical on both
            // surfaces and self-heals on the next write.
            JSONObject idle = new JSONObject();
            idle.put("phase", "idle");
            idle.put("percent", -1);
            idle.put("message", "");
            HttpResponse.sendJson(out, idle.toString());
            return;
        }
        // Stale-recovery: if the daemon was killed mid-download (low memory,
        // crash) the progress file's last writer is gone but the JSON is
        // frozen at "phase=downloading, percent=42". The webapp would poll
        // forever showing the wrong state. Detect by comparing ts; anything
        // older than 5 min in a non-terminal phase is a stuck remnant.
        // Terminal phases (error, installing@100) are kept as-is since the
        // user still wants to see the outcome.
        try {
            JSONObject parsed = new JSONObject(json);
            long ts = parsed.optLong("ts", 0);
            String phase = parsed.optString("phase", "");
            boolean terminal = "error".equals(phase) || "idle".equals(phase)
                    || ("installing".equals(phase) && parsed.optInt("percent", -1) == 100);
            if (!terminal && ts > 0 && (System.currentTimeMillis() - ts) > 5 * 60 * 1000L) {
                JSONObject idle = new JSONObject();
                idle.put("phase", "idle");
                idle.put("percent", -1);
                idle.put("message", "");
                HttpResponse.sendJson(out, idle.toString());
                return;
            }
        } catch (Exception ignored) {
            // Malformed JSON (torn read from the non-atomic writeProgress
            // rewrite) → return the idle sentinel, mirroring the IPC reader
            // (SurveillanceIpcServer.handleGetUpdateProgress). Passing the raw
            // broken string through made r.json() throw client-side, which
            // update-flow.js counts as a CONNECTION failure (consecutiveFailures
            // ++) and nudges the web poller toward false "lost daemon" / give-up
            // — whereas the same torn read is a harmless keep-polling no-op on
            // the app/IPC poller. Sentinel-on-malformed keeps both surfaces
            // self-healing on a single torn read.
            JSONObject idle = new JSONObject();
            idle.put("phase", "idle");
            idle.put("percent", -1);
            idle.put("message", "");
            HttpResponse.sendJson(out, idle.toString());
            return;
        }
        HttpResponse.sendJson(out, json);
    }

    // ================== Helpers ==================

    private static void writeProgress(String phase, int percent, String message, String error) {
        JSONObject r = new JSONObject();
        try {
            r.put("phase", phase);
            r.put("percent", percent);
            r.put("message", message != null ? message : "");
            if (error != null) r.put("error", error);
            r.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {}
        try (FileWriter fw = new FileWriter(PROGRESS_FILE)) {
            fw.write(r.toString());
        } catch (Exception e) {
            CameraDaemon.log("UpdateApi: progress write failed: " + e.getMessage());
        }
    }

    /**
     * Best-effort process probe via pgrep. The daemon runs as UID 2000 which
     * can spawn pgrep (a toybox applet on BYD ROMs). Returns false on any
     * error so a missing pgrep doesn't crash the preview endpoint.
     */
    private static boolean isProcessRunning(String name) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "pgrep -f '" + name + "' >/dev/null 2>&1"});
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readTextFile(String path) {
        File f = new File(path);
        if (!f.exists()) return null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static void signal(Object lock, boolean[] done) {
        synchronized (lock) {
            done[0] = true;
            lock.notify();
        }
    }

    /**
     * Extract a query-string parameter from a request path
     * ({@code /api/update/install?confirm=true&version=alpha-v25.4}).
     * Returns the URL-decoded value, or {@code null} when absent. No external
     * URI parser — the daemon's request path is a plain String and the values
     * we read (confirm flag, version tag, channel enum) are simple tokens.
     */
    private static String queryParam(String path, String key) {
        if (path == null) return null;
        int q = path.indexOf('?');
        if (q < 0 || q == path.length() - 1) return null;
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (k.equals(key)) {
                String v = eq >= 0 ? pair.substring(eq + 1) : "";
                try {
                    return java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Exception e) {
                    return v;
                }
            }
        }
        return null;
    }
}
