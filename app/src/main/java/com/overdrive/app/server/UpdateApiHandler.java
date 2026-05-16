package com.overdrive.app.server;

import android.content.Context;

import com.overdrive.app.BuildConfig;
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
    private static final String PROGRESS_FILE = "/data/local/tmp/overdrive_update_progress.json";

    // One install at a time. AtomicReference so we don't hold an updater past
    // the install (it's GC'd along with the dying process anyway).
    private static final AtomicReference<AppUpdater> activeUpdater = new AtomicReference<>();
    private static volatile boolean installInFlight = false;

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        if (path.equals("/api/update/check") && method.equals("GET")) {
            handleCheck(out);
            return true;
        }
        if (path.equals("/api/update/preview") && method.equals("GET")) {
            handlePreview(out);
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
        Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_app_context_not_ready"));
            return;
        }

        // Run synchronously by blocking on a callback latch. AppUpdater.checkForUpdate
        // dispatches to its own executor + posts to mainHandler, so we wait here.
        final Object lock = new Object();
        final boolean[] done = {false};
        final JSONObject[] resultRef = {null};

        AppUpdater updater = new AppUpdater(ctx);
        updater.checkForUpdate(new AppUpdater.UpdateCallback() {
            @Override public void onUpdateAvailable(String currentVersion, String newVersion, String releaseNotes) {
                JSONObject r = new JSONObject();
                try {
                    r.put("available", true);
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
                    r.put("error", error != null ? error : "unknown");
                    r.put("currentVersion", BuildConfig.VERSION_NAME);
                } catch (Exception ignored) {}
                resultRef[0] = r;
                signal(lock, done);
            }
        });

        synchronized (lock) {
            if (!done[0]) lock.wait(20_000);
        }

        if (resultRef[0] == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_check_timed_out"));
            return;
        }
        HttpResponse.sendJson(out, resultRef[0].toString());
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
        String lastUrl = readTextFile("/data/local/tmp/tunnel_url.txt");
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

        if (installInFlight) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_already_in_progress"));
            return;
        }

        Context ctx = CameraDaemon.getAppContext();
        if (ctx == null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_app_context_not_ready"));
            return;
        }

        // First check (synchronous) so /install isn't usable to download a
        // random APK without the matching /check having been resolved. Also
        // gives us latestDownloadUrl + remoteVersion populated on the updater.
        AppUpdater updater = new AppUpdater(ctx);
        activeUpdater.set(updater);

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
        synchronized (lock) {
            if (!done[0]) lock.wait(20_000);
        }
        if (err[0] != null) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_pre_install_failed_with_detail", err[0]));
            return;
        }
        if (!available[0]) {
            HttpResponse.sendJsonError(out, Messages.get("errors.update_no_update_available"));
            return;
        }

        // Reply to the webapp BEFORE kicking the install. Once daemons start
        // dying, the response would never make it back. From here on, the
        // webapp polls /api/update/progress.
        installInFlight = true;
        writeProgress("queued", 0, Messages.get("messages.update_queued"), null);

        JSONObject r = new JSONObject();
        r.put("status", "scheduled");
        r.put("estimatedDowntimeSeconds", 150);
        HttpResponse.sendJson(out, r.toString());

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
                        writeProgress("downloading", percent,
                                percent < 0
                                    ? Messages.get("messages.update_downloading_indeterminate")
                                    : Messages.get("messages.update_downloading_with_percent", percent),
                                null);
                    }
                    @Override public void onSuccess() {
                        writeProgress("installing", 100,
                                Messages.get("messages.update_installing_finishing"), null);
                        // Process should die before this matters, but defensive.
                        installInFlight = false;
                    }
                    @Override public void onError(String error) {
                        writeProgress("error", -1, Messages.get("messages.update_install_failed"), error);
                        installInFlight = false;
                    }
                });
            } catch (Exception e) {
                writeProgress("error", -1, Messages.get("messages.update_install_crashed"), e.getMessage());
                installInFlight = false;
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
            HttpResponse.sendJsonError(out, Messages.get("errors.update_progress_unreadable"));
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
}
