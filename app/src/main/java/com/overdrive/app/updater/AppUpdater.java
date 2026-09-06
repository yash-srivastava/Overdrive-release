package com.overdrive.app.updater;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.overdrive.app.BuildConfig;
import com.overdrive.app.launcher.AdbShellExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Checks GitHub Releases for app updates and handles download + silent install.
 *
 * Release model:
 * - Fixed tags: "alpha", "debug", "prod" (future)
 * - APK is replaced in-place on the same release
 * - Update detection: compare asset updated_at vs last installed timestamp
 * - Debug tag is ignored in release builds
 *
 * API: https://api.github.com/repos/yash-srivastava/Overdrive-release/releases/tags/{channel}
 */
public class AppUpdater {

    private static final String TAG = "AppUpdater";
    private static final String GITHUB_REPO = "yash-srivastava/Overdrive-release";
    private static final String PREFS_NAME = "app_updater";
    // LEGACY (pre-channel) baseline key/file. Still read once by
    // migrateBaseline() to seed the per-channel "alpha" slot, then unused.
    // The live baseline is per-channel — see prefKeyForChannel /
    // timestampFileForChannel — so a braveheart asset timestamp can never
    // false-suppress against a stored alpha timestamp (or vice-versa).
    private static final String PREF_LAST_UPDATE_TIME = "last_update_timestamp";
    private static final String PREF_JUST_UPDATED = "just_updated";
    private static final String PREF_UPDATED_VERSION = "updated_version";
    // The `ts` of the last failed-install progress record this app process has
    // already surfaced. consumeFailedUpdateError leaves the phase=error record
    // in /data/local/tmp/overdrive_update_progress.json IN PLACE (the
    // app-process unlink in a sticky shell-owned dir is a no-op anyway, and the
    // record must survive for the web's reconnect re-read once the daemon is
    // back up — otherwise the web falls through to a false "Updated to X").
    // This flag is the one-shot guard so a later NORMAL launch doesn't re-toast
    // the same failure; a NEW failure carries a different `ts` and re-arms.
    private static final String PREF_LAST_CONSUMED_FAILURE_TS = "last_consumed_failure_ts";
    // Also persist to filesystem (survives app reinstall, unlike SharedPreferences)
    private static final String UPDATE_TIMESTAMP_FILE = "/data/local/tmp/overdrive_update_timestamp";

    /** Per-channel SharedPreferences baseline key. */
    private static String prefKeyForChannel(String channel) {
        return PREF_LAST_UPDATE_TIME + "_" + channel;
    }
    /** Per-channel filesystem baseline path (survives app reinstall). */
    private static String timestampFileForChannel(String channel) {
        return UPDATE_TIMESTAMP_FILE + "_" + channel;
    }

    /** Channels the app understands. Used to validate runtime selection. */
    public static final String CHANNEL_ALPHA = "alpha";
    public static final String CHANNEL_BRAVEHEART = "braveheart";

    // ==================== SHARED INSTALL GATE ====================
    //
    // One install at a time, ACROSS all three trigger surfaces (web HTTP,
    // app IPC, Telegram IPC) — they all run downloadAndInstall in the SAME
    // camera-daemon JVM, so a single static gate serializes them. Before this
    // existed, only the web path (UpdateApiHandler) had an installInFlight
    // flag; the IPC path (app + Telegram) ignored it, so two near-simultaneous
    // installs could each spawn the detached install script + pkill cascade,
    // and a queued second downloadAndInstall could rm the APK the first was
    // about to pm-install (spurious INSTALL_PARSE_FAILED + bogus rollback).
    //
    // tryBeginInstall() CAS-acquires; endInstall() releases. The detached
    // install path deliberately never calls onSuccess (the process dies first),
    // so the flag would latch — INSTALL_STALE_MS self-recovers it, mirroring the
    // progress-file staleness window. Callers MUST endInstall() on every
    // pre-spawn bail and on onError so a failed install before process death
    // doesn't wedge the gate.
    private static volatile boolean installInFlight = false;
    private static volatile long installStartedAt = 0;
    private static final long INSTALL_STALE_MS = 5 * 60 * 1000L;

    /**
     * Try to acquire the single-install gate. Returns true if the caller now
     * owns the install (must pair with {@link #endInstall()} on failure paths),
     * false if an install is already in flight and not yet stale.
     */
    public static synchronized boolean tryBeginInstall() {
        if (installInFlight) {
            // Self-recover a wedged flag: if the prior install started more than
            // INSTALL_STALE_MS ago and this process is still alive, the kill
            // cascade clearly missed us — let a new attempt through.
            if (installStartedAt > 0
                    && System.currentTimeMillis() - installStartedAt > INSTALL_STALE_MS) {
                Log.w(TAG, "Clearing stale installInFlight (started "
                        + (System.currentTimeMillis() - installStartedAt) + "ms ago)");
            } else {
                return false;
            }
        }
        installInFlight = true;
        installStartedAt = System.currentTimeMillis();
        return true;
    }

    /** Release the install gate (call on pre-spawn bail or onError). */
    public static synchronized void endInstall() {
        installInFlight = false;
    }
    // Version file readable by daemon process (SharedPreferences are per-process)
    public static final String VERSION_FILE = "/data/local/tmp/overdrive_version";
    // Sentinels for the post-update handshake (see UpdateLifecycle).
    private static final String UPDATE_IN_PROGRESS_FILE = UpdateLifecycle.UPDATE_IN_PROGRESS_FILE;

    /**
     * Build a ps+awk+kill snippet that kills processes whose argv contains
     * {@code pattern}, excluding the calling shell's own PID. Replaces
     * every {@code pkill -9 -f '<pattern>'} that used to live in shell
     * payloads — pkill -f matches against /proc/&lt;pid&gt;/cmdline, so the
     * calling sh -c wrapper (whose cmdline contains the literal pattern)
     * self-matches and gets SIGKILLed before subsequent commands run.
     * ps+awk+kill keys on PID instead, with a {@code $$} guard.
     */
    private static String psAwkKillLine(String pattern) {
        return "MY_PID=$$; ps -A -o PID,ARGS | grep -F '" + pattern + "' | grep -v grep "
            + "| awk '{print $1}' | while read pid; do "
            + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n";
    }
    private static final String POST_UPDATE_FILE = UpdateLifecycle.POST_UPDATE_FILE;

    private final Context context;
    private volatile boolean cancelled = false;
    /**
     * Single-threaded executor SHARED across every {@link AppUpdater}
     * instance. MainActivity instantiates a fresh AppUpdater for the
     * periodic 6h check, manual checks, and the actual install — and the
     * daemon-process API handler does the same. Per-instance executors
     * leaked one non-daemon thread per check, accumulating across days.
     * One static executor with a daemon thread keeps the process clean
     * across instance churn AND serializes concurrent install attempts
     * naturally (the second one waits behind the first).
     */
    private static final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "AppUpdater");
                t.setDaemon(true);
                return t;
            });
    // Null in the daemon process — Looper.getMainLooper() returns null when no
    // thread has been designated as the main looper (the daemon's main() only
    // does Looper.prepare(), not prepareMainLooper()). Callbacks fall back to
    // inline execution; see runCallback().
    private final Handler mainHandler = resolveMainHandler();

    private static Handler resolveMainHandler() {
        Looper looper = Looper.getMainLooper();
        return looper != null ? new Handler(looper) : null;
    }

    private void runCallback(Runnable r) {
        if (mainHandler != null) mainHandler.post(r);
        else r.run();
    }

    /**
     * "Are we in a process that can write {@code /data/local/tmp}?"
     * Used to decide between the direct OkHttp path and the
     * AdbDaemonLauncher-tunnelled shell path.
     *
     * UID 2000 (shell) is the canonical answer — that's what the daemon
     * runs as via app_process. But some vendor-modded BYD ROMs launch the
     * daemon as system (UID 1000) or root (UID 0). A bare `myUid() == 2000`
     * check would silently route those into the slow shell-tunnel path
     * even though they have direct write access. Probe the actual
     * permission once and cache.
     */
    private static volatile Boolean canWriteTmpCached = null;

    private static boolean canWriteLocalTmp() {
        Boolean cached = canWriteTmpCached;
        if (cached != null) return cached;
        File probe = new File("/data/local/tmp/.overdrive_updater_probe");
        boolean ok;
        try {
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write((byte) 0);
                fos.flush();
            }
            ok = true;
        } catch (Exception e) {
            ok = false;
        } finally {
            try { probe.delete(); } catch (Exception ignored) {}
        }
        canWriteTmpCached = ok;
        return ok;
    }
    private AdbShellExecutor adb; // Lazy — only created when install is triggered
    private com.overdrive.app.launcher.AdbDaemonLauncher adbLauncher; // For daemon management

    private String latestDownloadUrl;
    private String releaseNotes;
    private String remoteVersion;
    private String remoteUpdatedAt;
    /**
     * Channel the pending install BELONGS to, captured at the SEED point
     * (checkForUpdate = the resolved channel; prepareInstall = alpha, since the
     * catalog only surfaces alpha tags). downloadAndInstall keys its
     * per-channel baseline advance/rollback on THIS, not a re-resolve at
     * install start — otherwise a channel toggle racing an in-flight install,
     * or an alpha-pick whose tag's channel differs from the live channel, would
     * advance/roll back the WRONG channel's baseline.
     */
    private volatile String pendingChannel;

    /**
     * Build an OkHttpClient that routes through whichever proxy the rest of
     * the app is using — sing-box on 8119 first, Tailscale on 8539 as a
     * fallback. Delegates to {@link com.overdrive.app.mqtt.ProxyHelper} so
     * we share a single 60s probe cache with MQTT, ABRP, BYD Cloud, and
     * the Telegram daemon (no separate timing windows where one consumer
     * thinks the proxy is up and another doesn't).
     *
     * Both timeouts are in seconds. Pass {@code 0} for {@code readTimeout}
     * when streaming a large body — we still want a connect deadline but
     * we don't want OkHttp to abort a healthy slow download as "read
     * timed out". For tiny JSON fetches (release metadata) keep both
     * non-zero.
     */
    private static OkHttpClient buildClient(long connectTimeout, long readTimeout) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .followRedirects(true);

        java.net.Proxy proxy = com.overdrive.app.mqtt.ProxyHelper.getHttpProxy();
        if (proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT) {
            builder.proxy(proxy);
            Log.d(TAG, "AppUpdater HTTP via proxy " + proxy.address());
        }
        return builder.build();
    }

    public interface UpdateCallback {
        void onUpdateAvailable(String currentVersion, String newVersion, String releaseNotes);
        void onNoUpdate(String currentVersion);
        void onError(String error);
    }

    public interface InstallCallback {
        void onProgress(String message);
        void onDownloadProgress(int percent);
        void onSuccess();
        void onError(String error);
    }

    public AppUpdater(Context context) {
        this.context = context;
        // Cleanup runs without ADB — just deletes from app's own external files dir
        cleanupLeftoverApk();
    }

    private AdbShellExecutor getAdb() {
        if (adb == null) {
            adb = new AdbShellExecutor(context);
        }
        return adb;
    }

    private com.overdrive.app.launcher.AdbDaemonLauncher getAdbLauncher() {
        if (adbLauncher == null) {
            adbLauncher = new com.overdrive.app.launcher.AdbDaemonLauncher(context);
        }
        return adbLauncher;
    }

    /**
     * Release per-instance ADB executor + tunnel-poll scheduler that this
     * AppUpdater lazily allocated. AppUpdater is NOT a process singleton
     * — it's `new`'d at every call site (MainActivity manual-check, the
     * 6h periodic check, UpdateApiHandler, SettingsAboutFragment,
     * SurveillanceIpcServer). Without this method, each instance that
     * touched the ADB-tunnel path stranded one non-daemon executor
     * thread + one tunnel-poll scheduler thread for the life of the
     * process. After a week of parking that's ~50+ zombie threads in
     * /proc/self/status.
     *
     * Callers SHOULD invoke close() in a finally block once they're done
     * with the AppUpdater instance. Idempotent.
     *
     * Uses releasePerInstanceResources (NOT closePersistentConnection)
     * so we don't null the process-wide shared Dadb that other launchers
     * are using.
     */
    public void close() {
        try {
            if (adbLauncher != null) {
                adbLauncher.releasePerInstanceResources();
                adbLauncher = null;
            }
        } catch (Exception ignored) {}
        try {
            if (adb != null) {
                adb.shutdown();
                adb = null;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Run a shell command, picking the right executor for the current process.
     *
     * The app process (UID 10xxx) needs to elevate to UID 2000 to write
     * /data/local/tmp and call `pm install`, so it goes through the ADB-shell
     * tunnel ({@link com.overdrive.app.launcher.AdbDaemonLauncher}).
     *
     * The daemon process is ALREADY UID 2000 (it was launched via app_process
     * by the same ADB tunnel at startup), so it can — and must — execute
     * shell commands directly. Routing daemon-side calls through the ADB
     * tunnel fails on every BYD head unit because dadb tries to read the
     * app's adbkey at /data/user/0/com.overdrive.app/files/adbkey, and that
     * directory is mode 0700 owned by the app UID — UID 2000 can't open it
     * (EACCES). The user reported this as
     * "Install failed: Download failed: ERROR: Execution failed:
     *  /data/user/0/com.overdrive.app/files/adbkey: open failed:
     *  EACCES (Permission denied)".
     *
     * Direct exec is also faster (no socket round-trip per command).
     *
     * Callback semantics match {@link com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback}:
     * onLog gets the combined stdout/stderr, then onLaunched fires on success
     * (exit 0) or onError on a non-zero exit / spawn failure.
     */
    private void runShell(String command,
                          com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        // Tunnel through ADB if this process can't write /data/local/tmp
        // directly (the canonical app-process state). See canWriteLocalTmp
        // for why this beats a UID==2000 check.
        if (!canWriteLocalTmp()) {
            getAdbLauncher().executeShellCommand(command, callback);
            return;
        }
        // Daemon path — exec directly. Runs SYNCHRONOUSLY on the caller's
        // thread (don't bounce onto AppUpdater.executor — downloadAndInstall
        // already runs there and is single-threaded, so queueing more work
        // would deadlock against the wait() that follows each call site).
        // The synchronous callback fires before return, so the caller's
        // notify/wait pattern still works (the wait sees the done flag
        // already true and short-circuits).
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader stdout = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            java.io.BufferedReader stderr = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = stdout.readLine()) != null) {
                out.append(line).append('\n');
            }
            while ((line = stderr.readLine()) != null) {
                out.append(line).append('\n');
            }
            int exit = p.waitFor();
            String combined = out.toString().trim();
            if (!combined.isEmpty()) callback.onLog(combined);
            if (exit == 0) {
                callback.onLaunched();
            } else {
                callback.onError("Exit " + exit + ": " + combined);
            }
        } catch (Exception e) {
            callback.onError("Execution failed: " + e.getMessage());
        }
    }

    /**
     * Execute a multi-command shell payload via a temp script file. Use this
     * for any payload containing `pkill -f '<pattern>'` whose pattern also
     * appears as a literal substring in earlier commands of the same payload
     * — `sh -c "<payload>"` puts the whole payload in argv[2], and toybox
     * `pkill -f` would match the calling shell itself, SIGKILLing it before
     * later commands run.
     *
     * Routes through ADB or direct exec depending on the canonical
     * /data/local/tmp writability check used by runShell.
     */
    private void runShellScript(String scriptBody,
                                com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback callback) {
        if (!canWriteLocalTmp()) {
            getAdbLauncher().executeShellScript(scriptBody, callback);
            return;
        }
        // Direct path — write the script to a tmp file ourselves and exec it.
        // Same self-match defense as the ADB path: the running shell's argv
        // is just `sh <path>`, no daemon pattern visible to pkill.
        String scriptPath = "/data/local/tmp/.appupdater_script_" + System.nanoTime() + ".sh";
        try {
            java.io.File scriptFile = new java.io.File(scriptPath);
            try (java.io.FileWriter fw = new java.io.FileWriter(scriptFile)) {
                fw.write(scriptBody);
            }
            scriptFile.setExecutable(true, false);

            Process p = Runtime.getRuntime().exec(new String[]{"sh", scriptPath});
            StringBuilder out = new StringBuilder();
            java.io.BufferedReader stdout = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            java.io.BufferedReader stderr = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream()));
            String line;
            while ((line = stdout.readLine()) != null) out.append(line).append('\n');
            while ((line = stderr.readLine()) != null) out.append(line).append('\n');
            int exit = p.waitFor();
            String combined = out.toString().trim();
            if (!combined.isEmpty()) callback.onLog(combined);
            if (exit == 0) {
                callback.onLaunched();
            } else {
                callback.onError("Exit " + exit + ": " + combined);
            }
        } catch (Exception e) {
            callback.onError("Script execution failed: " + e.getMessage());
        } finally {
            try { new java.io.File(scriptPath).delete(); } catch (Exception ignored) {}
        }
    }

    /**
     * Currently-active OkHttp call, held so {@link #cancel()} can tear
     * down the socket immediately instead of waiting for the read loop
     * to observe the cancelled flag on its next iteration. Volatile so
     * the cancel thread sees the most recent reference set by the
     * download thread.
     */
    private volatile okhttp3.Call activeCall = null;

    /**
     * Cancel an in-progress download/install.
     */
    public void cancel() {
        cancelled = true;
        // Tear the socket down NOW so a stalled-mid-read transfer can't
        // hold for another 15 seconds before the read loop notices.
        okhttp3.Call call = activeCall;
        if (call != null) {
            try { call.cancel(); } catch (Exception ignored) {}
        }
    }

    private static final String APK_PATH = "/data/local/tmp/overdrive_update.apk";

    private String getApkPath() {
        return APK_PATH;
    }

    private void cleanupLeftoverApk() {
        try {
            // Also age out a stale Telegram post-update hint older than 24h —
            // if Telegram never came back online to consume it, the user has
            // already noticed the URL change through other means and a "you
            // were just updated" message would be confusing days later.
            String cmd = "rm -f " + APK_PATH + "; " +
                    "find " + UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE +
                    " -mmin +1440 -delete 2>/dev/null; echo done";
            runShell(cmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() { Log.i(TAG, "Cleaned up leftover APK"); }
                @Override public void onError(String e) {}
            });
        } catch (Exception ignored) {}
    }

    /**
     * Locate the first {@code .apk} asset in a release's {@code assets} array.
     * Returns {@code {browser_download_url, name, updated_at}} or {@code null}
     * when the release carries no APK (notes-only / draft). Shared by
     * {@link #checkForUpdate} (braveheart) and {@link #listVersions} (alpha)
     * so the single-asset find lives in exactly one place.
     */
    private static String[] firstApkAsset(JSONArray assets) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (name.endsWith(".apk")) {
                String url = asset.optString("browser_download_url", "");
                if (url.isEmpty()) continue;
                return new String[]{url, name, asset.optString("updated_at", "")};
            }
        }
        return null;
    }

    /**
     * Check GitHub Releases for a newer APK on the resolved channel.
     *
     * BRAVEHEART (and any future single-tag rolling channel): the historical
     * timestamp-based "is the asset newer?" detection, unchanged. This method
     * is the SOLE caller of the first-run / fresh-deploy suppression below —
     * keeping that single-asset assumption intact and regression-free.
     *
     * ALPHA: there is no single "the update" — alpha is a pick-any catalog
     * (see {@link #listVersions}). A passive check on alpha therefore always
     * reports {@code onNoUpdate}; the web/native entry points open the catalog
     * instead. Skips the check entirely when the channel is empty (debug).
     */
    public void checkForUpdate(UpdateCallback callback) {
        String channel = resolveChannel();
        if (channel == null || channel.isEmpty()) {
            runCallback(() -> callback.onNoUpdate(getDisplayVersion(context)));
            return;
        }
        // Alpha is browse-and-pick — no passive "an update is available".
        // Run the (one-time) baseline migration off the caller's thread so a
        // main-thread invoker never eats the SharedPreferences commit / shell.
        if (CHANNEL_ALPHA.equals(channel)) {
            executor.execute(() -> {
                migrateBaseline(channel);
                runCallback(() -> callback.onNoUpdate(getDisplayVersion(context)));
            });
            return;
        }

        executor.execute(() -> {
            try {
                migrateBaseline(channel);
                String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO +
                        "/releases/tags/" + channel;

                OkHttpClient client = buildClient(15, 15);

                Request request = new Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        postError(callback, "GitHub API error: HTTP " + response.code());
                        return;
                    }

                    String body = response.body().string();
                    JSONObject release = new JSONObject(body);

                    releaseNotes = release.optString("body", "Bug fixes and improvements.");

                    // Find the APK asset
                    String[] apk = firstApkAsset(release.optJSONArray("assets"));
                    if (apk == null) {
                        postError(callback, "No APK found in release");
                        return;
                    }
                    String apkUrl = apk[0];
                    String apkName = apk[1];
                    String updatedAt = apk[2];

                    latestDownloadUrl = apkUrl;
                    remoteUpdatedAt = updatedAt;
                    // Bind the pending install to THIS channel at the seed
                    // point so downloadAndInstall advances the right baseline
                    // even if the channel toggle flips mid-install.
                    pendingChannel = channel;

                    // Extract version, canonicalized to "<channel>-v<semver>" so a
                    // filename missing the channel prefix still persists a label
                    // the read-side shape guard trusts (else About/web revert to
                    // the BuildConfig identity). resolveRemoteLabel falls back from
                    // the APK filename to the release name/tag/body so a digit-less
                    // braveheart asset filename doesn't strand the label at
                    // "unknown" (which freezes the displayed version while the
                    // baseline still advances).
                    remoteVersion = resolveRemoteLabel(release, apkName, channel);
                    // Report what AppUpdater previously persisted so the
                    // user-facing "you're on version X" matches what
                    // SettingsAboutFragment shows. BuildConfig.VERSION_NAME
                    // is the gradle stub and unrelated to GitHub releases.
                    String currentVersion = getDisplayVersion(context);

                    // CROSS-CHANNEL OFFER (checked FIRST, before any baseline
                    // gate): if the build actually running belongs to a
                    // DIFFERENT channel than the one now selected (e.g. running
                    // alpha-v26, switched to braveheart), the remote build IS an
                    // update — offer it unconditionally. This must NOT depend on
                    // the per-channel timestamp baseline: a stale _braveheart
                    // baseline left by an earlier check would otherwise make the
                    // timestamp compare say "up to date" and suppress the offer.
                    // getInstalledVersion() is BuildConfig-derived (the true
                    // running identity), so this is reliable. Don't advance the
                    // baseline here — the install path does, on success.
                    String installedChannel = channelOfLabel(getInstalledVersion());
                    if (installedChannel != null && !installedChannel.equals(channel)) {
                        Log.i(TAG, "Channel switch (" + installedChannel + "→" + channel
                                + ") — offering " + remoteVersion);
                        runCallback(() -> callback.onUpdateAvailable(
                                currentVersion, remoteVersion, releaseNotes));
                        return;
                    }

                    // VERSION-NEWER OFFER (checked BEFORE the timestamp gate, like
                    // the cross-channel offer above). The timestamp baseline records
                    // "when we last CHECKED", not the updated_at of the build the
                    // user is actually running — so on a rolling tag (braveheart) a
                    // user who sideloaded/flashed an OLDER APK, or whose baseline got
                    // seeded/poisoned to the current-remote updated_at (first-run seed
                    // below, the out-of-band reseed, or a failed install that rolled
                    // the baseline forward to remote), is PERMANENTLY suppressed by
                    // the updated_at compare even though a strictly-newer build is
                    // live. Guard against that with a baseline-INDEPENDENT check: if
                    // the remote label parses to a strictly-higher numeric version
                    // than the running build, offer it unconditionally. This is
                    // positive-only (it can only ADD an offer, never suppress one) so
                    // it can't cause downgrade-spam; a same-or-lower remote version —
                    // including an in-place re-upload that keeps the same version but
                    // bumps updated_at — falls through to the timestamp path, which
                    // already handles that case correctly. Both numbers must parse
                    // (numericVersion returns "" for a bare/unknown label) and belong
                    // to THIS channel, or we skip the shortcut and defer to timestamp.
                    //
                    // Compare against getDisplayVersion() — the VERSION_FILE-first
                    // label — NOT getInstalledVersion() (raw BuildConfig). On a
                    // rolling tag the compiled versionName may stay pinned across
                    // re-uploads (it is pinned at 33.0 today), so BuildConfig would
                    // report a stale number and this shortcut would RE-OFFER the
                    // just-installed build forever. VERSION_FILE is advanced ONLY on
                    // pm-install success (persistVersionToFile on the rc==0 path) to
                    // the real filename-derived label, and restored on failure, so it
                    // faithfully tracks what actually landed. A fresh sideload (no
                    // VERSION_FILE) falls back to the BuildConfig identity, which is
                    // then the user's true flashed version — still correct for the
                    // strand case. getDisplayVersion always yields a label for the
                    // running channel (VERSION_FILE is channel-guarded to "" on a
                    // cross-channel value, then it drops to BuildConfig), so the
                    // channel check below holds.
                    String remoteNumeric = numericVersion(remoteVersion);
                    String installedLabel = getDisplayVersion(context);
                    String installedNumeric =
                            (channelOfLabel(installedLabel) != null
                                    && channel.equals(channelOfLabel(installedLabel)))
                                    ? numericVersion(installedLabel) : "";
                    if (!remoteNumeric.isEmpty() && !installedNumeric.isEmpty()
                            && isNewerVersion(installedNumeric, remoteNumeric)) {
                        Log.i(TAG, "Version-newer offer (installed " + installedNumeric
                                + " < remote " + remoteNumeric + ") — offering " + remoteVersion
                                + " regardless of timestamp baseline");
                        runCallback(() -> callback.onUpdateAvailable(
                                currentVersion, remoteVersion, releaseNotes));
                        return;
                    }

                    // Downgrade protection: if running build is strictly newer than remote, suppress offer
                    if (!remoteNumeric.isEmpty() && !installedNumeric.isEmpty()
                            && isNewerVersion(remoteNumeric, installedNumeric)) {
                        Log.i(TAG, "Installed version " + installedNumeric
                                + " is newer than remote " + remoteNumeric + " — suppressing downgrade offer");
                        runCallback(() -> callback.onNoUpdate(currentVersion));
                        return;
                    }

                    // Update detection: compare asset updated_at timestamp only.
                    // Version comparison is unreliable since versionName may not be bumped
                    // when the APK is replaced on the same release tag.
                    String lastInstalledTimestamp = getLastUpdateTimestamp(channel);
                    boolean apkUpdated = !updatedAt.isEmpty() && !updatedAt.equals(lastInstalledTimestamp);

                    // No baseline for THIS channel yet AND we're already running
                    // this channel (cross-channel was handled+returned above).
                    // Genuine first run / fresh sideload of this channel's build:
                    // the user already has it → seed the baseline + suppress.
                    // Capture the app's install/replace time (DEVICE clock). Used
                    // only for device-vs-device comparisons below — never compared
                    // against a GitHub server timestamp.
                    long appInstallTime = 0L;
                    try {
                        appInstallTime = context.getPackageManager()
                                .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
                    } catch (Exception e) {
                        Log.w(TAG, "Could not read lastUpdateTime: " + e.getMessage());
                    }

                    if (lastInstalledTimestamp.isEmpty()) {
                        saveLastUpdateTimestamp(channel, updatedAt);
                        // Seed the DEVICE-clock install-time baseline alongside the
                        // server-timestamp baseline so the next check can detect an
                        // out-of-band reinstall without crossing clock domains.
                        saveInstallTimeBaseline(context, channel, appInstallTime);
                        // Display label is BuildConfig-derived (getInstalledVersion),
                        // so a check NEVER needs to seed it — just record the
                        // per-channel baselines and report up-to-date.
                        Log.i(TAG, "First run — saved baseline timestamp: " + updatedAt + ", version: " + remoteVersion);
                        runCallback(() -> callback.onNoUpdate(currentVersion));
                        return;
                    }

                    // Out-of-band reinstall detection — DEVICE-CLOCK ONLY.
                    //
                    // The previous heuristic compared the device's lastUpdateTime
                    // against GitHub's server updated_at. Those are two unrelated
                    // clocks, so device-clock skew (BYD head units boot with a
                    // wrong RTC until GPS/NTP corrects it) could make a genuinely
                    // newer asset look like a "fresh deploy" and PERMANENTLY
                    // suppress the update — the user silently stranded on an old
                    // build. We now compare ONLY device-clock values: the app's
                    // current lastUpdateTime vs the lastUpdateTime we recorded when
                    // we last set a baseline for this channel. If it advanced, the
                    // APK was (re)installed since our baseline — either our own
                    // in-app update that just landed, or a manual/Studio sideload.
                    // In every case the running bytes are what the user most
                    // recently chose to install, so treat the device as current:
                    // re-seed BOTH baselines and suppress THIS check only. The next
                    // re-upload bumps updated_at again → apkUpdated=true → offered,
                    // so a real later update is never missed. Worst case (user
                    // manually sideloaded an OLD build while a newer one is live) is
                    // one suppressed check that self-corrects on the next publish —
                    // strictly safer than a clock-skew permanent strand.
                    long installTimeBaseline = getInstallTimeBaseline(context, channel);
                    // FAIL-OPEN on an UNKNOWN baseline (0). The install-time
                    // baseline is SharedPreferences-only (per-UID) with NO
                    // cross-UID file fallback — unlike getLastUpdateTimestamp,
                    // which syncs through a world-readable /data/local/tmp file.
                    // So the DAEMON process (UID 2000) reads 0 here even after the
                    // APP process (UID 10xxx) has seeded it. Without the
                    // `installTimeBaseline > 0` guard, the daemon's pre-install
                    // re-check (SurveillanceIpcServer.handleInstallUpdate) sees
                    // appInstallTime != 0 → fires this branch → onNoUpdate for
                    // EVERY genuine update. Symptom: the app's own check offers the
                    // update (Update button shows), the tap routes the install to
                    // the daemon, and the user gets "Couldn't start the update: No
                    // update available". Worse, the branch's saveLastUpdateTimestamp
                    // below would poison the SHARED cross-UID timestamp file, making
                    // the failure sticky across retries. A 0 baseline means "never
                    // recorded for this UID" → fall through to the normal apkUpdated
                    // check and offer it. This is the documented contract: an unseen
                    // stamp can NEVER falsely suppress a genuine update; the daemon
                    // simply degrades to timestamp-only detection (which IS cross-UID
                    // correct). Out-of-band sideload suppression stays intact on the
                    // app process, where the baseline is always seeded > 0.
                    if (apkUpdated && appInstallTime > 0 && installTimeBaseline > 0
                            && appInstallTime != installTimeBaseline) {
                        saveLastUpdateTimestamp(channel, updatedAt);
                        saveInstallTimeBaseline(context, channel, appInstallTime);
                        Log.i(TAG, "Out-of-band (re)install detected (lastUpdateTime "
                                + appInstallTime + " != baseline " + installTimeBaseline
                                + ") — re-seeded baselines, suppressing one check");
                        runCallback(() -> callback.onNoUpdate(currentVersion));
                        return;
                    }

                    Log.i(TAG, "Channel: " + channel + ", Current: " + currentVersion +
                            ", Remote: " + remoteVersion + ", APK updated: " + updatedAt +
                            ", Last installed: " + lastInstalledTimestamp);

                    if (apkUpdated) {
                        runCallback(() -> callback.onUpdateAvailable(
                                currentVersion, remoteVersion, releaseNotes));
                    } else {
                        runCallback(() -> callback.onNoUpdate(currentVersion));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Update check failed: " + e.getMessage());
                postError(callback, e.getMessage());
            }
        });
    }

    /**
     * Download APK, then stop daemons, then install silently.
     * Download happens first so user can cancel before daemons are killed.
     */
    public void downloadAndInstall(InstallCallback callback) {
        cancelled = false;
        executor.execute(() -> {
            boolean cameraRestartPrepared = false;
            String preparedChannel = null;
            String preparedPriorTimestamp = null;
            String preparedPriorDisplayVersion = null;
            try {
                if (latestDownloadUrl == null) {
                    postInstallError(callback, "No download URL");
                    return;
                }

                // Wipe any leftover APK from a previous attempt up front.
                // The constructor already runs cleanupLeftoverApk, but a
                // single AppUpdater instance can be reused across multiple
                // download attempts (cancel + retry), and a static executor
                // means the previous attempt's partial APK could still be
                // on disk. Re-running cleanup here is the cheap safe call.
                cleanupLeftoverApk();

                // Step 1: Download APK.
                //
                // Two paths:
                //   - Daemon process (UID 2000): direct OkHttp download. Uses
                //     ProxyHelper.getHttpProxy() so we go through sing-box on
                //     8119 (or Tailscale 8539) the same way every other
                //     network call in the project does. Streams the body to
                //     /data/local/tmp/ and emits real progress percent.
                //     Connect timeout caps wedged TCP at 15s; readTimeout is
                //     also 15s (per-read, not whole-download) so a stalled
                //     CDN throws SocketTimeoutException instead of hanging
                //     indefinitely. Healthy slow downloads keep flowing
                //     because each successful read resets the timer.
                //   - App process (UID 10xxx): falls back to the shell tunnel
                //     because the app UID can't write to /data/local/tmp/.
                //     Shell command now carries explicit timeout flags so a
                //     blocked CDN fails cleanly within ~30s instead of the
                //     5-minute Java waiter.
                //
                // The 5-minute wait below is now an outer ceiling — both
                // paths self-cap much earlier on failure.
                postProgress(callback, "Downloading update...");
                runCallback(() -> callback.onDownloadProgress(-1));

                final boolean[] dlDone = {false};
                final String[] dlResult = {null};

                if (canWriteLocalTmp()) {
                    // Direct OkHttp — runs synchronously on the executor thread.
                    try {
                        downloadApkOkHttp(latestDownloadUrl, APK_PATH, callback);
                        dlResult[0] = "OK";
                    } catch (Exception e) {
                        dlResult[0] = "ERROR: " + (e.getMessage() == null ? "download failed" : e.getMessage());
                    }
                    dlDone[0] = true;
                } else {
                    String downloadCmd = buildDownloadCommand(latestDownloadUrl, APK_PATH);
                    runShell(downloadCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String message) {
                            dlResult[0] = message;
                        }
                        @Override public void onLaunched() {
                            dlDone[0] = true;
                            synchronized (dlDone) { dlDone.notify(); }
                        }
                        @Override public void onError(String error) {
                            dlResult[0] = "ERROR: " + error;
                            dlDone[0] = true;
                            synchronized (dlDone) { dlDone.notify(); }
                        }
                    });

                    // Outer ceiling — only relevant for the shell path.
                    // OkHttp path already returned synchronously above.
                    synchronized (dlDone) {
                        if (!dlDone[0]) dlDone.wait(300000);
                    }
                }

                if (cancelled) {
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "Cancelled");
                    return;
                }

                String dlOutput = dlResult[0] != null ? dlResult[0] : "";
                if (dlOutput.startsWith("ERROR") || !dlOutput.contains("OK")) {
                    postInstallError(callback, "Download failed: " + dlOutput);
                    return;
                }

                runCallback(() -> callback.onDownloadProgress(100));

                // Step 2: Verify APK size via shell
                postProgress(callback, "Verifying download...");
                final boolean[] szDone = {false};
                final String[] szResult = {null};
                runShell("stat -c%s " + APK_PATH + " 2>/dev/null || echo 0",
                        new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String message) { szResult[0] = message.trim(); }
                    @Override public void onLaunched() {
                        szDone[0] = true;
                        synchronized (szDone) { szDone.notify(); }
                    }
                    @Override public void onError(String error) {
                        szResult[0] = "0";
                        szDone[0] = true;
                        synchronized (szDone) { szDone.notify(); }
                    }
                });
                synchronized (szDone) {
                    if (!szDone[0]) szDone.wait(10000);
                }

                long fileSize = 0;
                try { fileSize = Long.parseLong(szResult[0].trim()); } catch (Exception ignored) {}
                if (fileSize < 1_000_000) {
                    runShell("rm -f " + APK_PATH, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
                    postInstallError(callback, "Invalid APK (size: " + fileSize + ")");
                    return;
                }

                // The updater terminates CameraDaemon with SIGKILL below. Its
                // shutdown hook cannot protect an active trip in that case, so
                // require the daemon's restart contract to durably checkpoint
                // and quiesce trip sampling before either kill path is armed.
                // The endpoint intentionally succeeds without quiescing when
                // trip recording is disabled, preserving the existing updater
                // behavior for users who do not record trips.
                postProgress(callback, "Preparing trip data...");
                String prepareFailure = prepareCameraDaemonForUpdate();
                if (prepareFailure != null) {
                    // A transport failure can happen after the server prepared
                    // successfully but before its response reached us. Abort is
                    // therefore required even for an apparent prepare failure.
                    abortPreparedCameraRestart();
                    // The APK is deliberately KEPT. Preparation failures are
                    // retryable conditions, and the download is the expensive
                    // part — deleting it made every retry re-fetch the whole
                    // package. cleanupLeftoverApk() at the start of the next
                    // attempt still replaces it, and the constructor's cleanup
                    // removes it if the user gives up.
                    postInstallError(callback,
                            "Update stopped safely: " + prepareFailure
                                    + abortRestartWarning());
                    return;
                }
                cameraRestartPrepared = true;

                // Step 3: Save update info BEFORE we touch any daemon (the daemon
                // process — if we're running inside it — is about to die, and the
                // app process gets killed by `pm install -r`; either way the
                // SharedPreferences write must happen first).
                //
                // Snapshot the prior timestamp so we can roll back on
                // install failure. Without rollback, a failed install
                // permanently advances PREF_LAST_UPDATE_TIME to the failed
                // remote version, and the next checkForUpdate sees
                // "lastInstalled == latest" → reports no update available
                // until GitHub re-uploads the asset. The user is silently
                // stuck on the old build.
                // Use the channel bound at the SEED point (where
                // latestDownloadUrl was resolved) — NOT a re-resolve here. The
                // seed-bound value is the channel the installed APK actually
                // belongs to, so a channel toggle racing the install, or an
                // alpha-pick whose tag's channel differs from the live channel,
                // can't advance/roll back the wrong baseline. Falls back to a
                // live resolve only if a caller invoked downloadAndInstall
                // without going through checkForUpdate/prepareInstall (defensive).
                final String channel = pendingChannel != null ? pendingChannel : resolveChannel();
                final String priorUpdateTimestamp = getLastUpdateTimestamp(channel);
                // Snapshot the prior display label too, so a failed install can
                // restore VERSION_FILE / PREF_UPDATED_VERSION — otherwise the
                // About/web "current version" shows the build that DIDN'T land.
                final String priorDisplayVersion = getDisplayVersion(context);
                preparedChannel = channel;
                preparedPriorTimestamp = priorUpdateTimestamp;
                preparedPriorDisplayVersion = priorDisplayVersion;
                // Set the just-updated MARKER. Only store remoteVersion as the
                // label when it's canonical — a bare/version-less "unknown"
                // must not clobber a prior valid label.
                //
                // NOTE: VERSION_FILE (persistVersionToFile) is deliberately NOT
                // written here. It is the user-visible "current version" on every
                // surface, so it must only advance AFTER pm install returns rc==0
                // — otherwise a failed install (or a kill between this write and
                // the failed pm install) leaves About/status/toast showing a build
                // that never landed. The advance now lives on the success paths:
                // the sync onSuccess branch below, and the detached install
                // script's pm-install-success branch (runDetachedInstall). The
                // PREF_UPDATED_VERSION marker is safe to set pre-install because
                // it's consumed once on the post-update launch and is explicitly
                // rolled back on failure (consumeFailedUpdateError).
                android.content.SharedPreferences.Editor ie =
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit().putBoolean(PREF_JUST_UPDATED, true);
                if (!"unknown".equals(remoteVersion)) {
                    ie.putString(PREF_UPDATED_VERSION, remoteVersion);
                }
                ie.commit();
                saveLastUpdateTimestamp(channel, remoteUpdatedAt);

                // Step 4 & 5: Stop daemons + install + relaunch. The control flow
                // splits here based on which process we're in:
                //
                //   App process (UID 10xxx) — the existing synchronous flow works.
                //   The app talks to UID 2000 over the dadb tunnel; that tunnel
                //   outlives our app process when `pm install -r` replaces it,
                //   and the same tunnel runs `am start` after the install lands.
                //
                //   Daemon process (UID 2000) — we ARE one of the processes the
                //   stop step kills, so we cannot supervise the install ourselves.
                //   Instead, write a self-contained install script to
                //   /data/local/tmp/ and kick it off detached (subshell + closed
                //   stdio so init reparents it), then return. The script runs
                //   on its own; our death is fine.
                if (canWriteLocalTmp()) {
                    postProgress(callback, "Stopping daemons & installing...");
                    boolean detachedStarted = runDetachedInstall(
                            callback, channel, priorUpdateTimestamp,
                            priorDisplayVersion);
                    if (!detachedStarted) {
                        abortPreparedCameraRestart();
                        rollbackPreparedUpdateMetadata(
                                channel, priorUpdateTimestamp,
                                priorDisplayVersion);
                    }
                    // On success the detached script owns the imminent kill.
                    // On failure abortPreparedCameraRestart resumed sampling.
                    cameraRestartPrepared = false;
                    return;
                }

                postProgress(callback, "Stopping daemons...");
                boolean cameraStopped = stopAllDaemons();
                if (!cameraStopped) {
                    abortPreparedCameraRestart();
                    rollbackPreparedUpdateMetadata(
                            channel, priorUpdateTimestamp,
                            priorDisplayVersion);
                    cameraRestartPrepared = false;
                    postInstallError(callback,
                            "Update stopped safely: camera daemon did not stop"
                                    + abortRestartWarning());
                    return;
                }
                // stopAllDaemons has now issued both prepared camera kill
                // sweeps; this process can no longer cancel that transition.
                cameraRestartPrepared = false;
                Thread.sleep(3000);

                postProgress(callback, "Installing...");
                final boolean[] done = {false};
                final String[] result = {null};

                String installCmd = "pm install -r -d " + APK_PATH +
                    "; rm -f " + APK_PATH +
                    "; sleep 2; am start -n com.overdrive.app/.ui.MainActivity" +
                    " --ez " + UpdateLifecycle.EXTRA_POST_UPDATE + " true";

                runShell(installCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String message) {
                        Log.i(TAG, "Install: " + message);
                        result[0] = message;
                    }
                    @Override public void onLaunched() {
                        done[0] = true;
                        synchronized (done) { done.notify(); }
                    }
                    @Override public void onError(String error) {
                        result[0] = "ERROR: " + error;
                        done[0] = true;
                        synchronized (done) { done.notify(); }
                    }
                });

                synchronized (done) {
                    if (!done[0]) done.wait(60000);
                }

                // If we reach here, install may have failed (process should be dead on success)
                String output = result[0] != null ? result[0] : "";
                if (!output.toLowerCase().contains("success")) {
                    // Roll back the prefs we set pre-install. Critically,
                    // restore the PER-CHANNEL baseline — without this, a
                    // failed install silently advances the baseline and
                    // checkForUpdate would report "no update" forever.
                    android.content.SharedPreferences.Editor pe =
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_JUST_UPDATED, false)
                            .putString(prefKeyForChannel(channel), priorUpdateTimestamp);
                    // Mirror consumeFailedUpdateError: restore a real prior
                    // label, but REMOVE (not store the literal fallback) when
                    // there was no prior real build — keeps PREF_UPDATED_VERSION
                    // consistent across all three rollback paths.
                    if (priorDisplayVersion != null && !priorDisplayVersion.isEmpty()
                            && !DISPLAY_VERSION_FALLBACK.equals(priorDisplayVersion)) {
                        pe.putString(PREF_UPDATED_VERSION, priorDisplayVersion);
                    } else {
                        pe.remove(PREF_UPDATED_VERSION);
                    }
                    pe.commit();
                    // Restore the per-channel timestamp FILE too (the pref
                    // edit above only fixes the SharedPreferences half; the
                    // /data/local/tmp file would otherwise stay advanced and
                    // re-seed the stale baseline on the next reinstall-read).
                    if (priorUpdateTimestamp != null && !priorUpdateTimestamp.isEmpty()) {
                        saveLastUpdateTimestamp(channel, priorUpdateTimestamp);
                    } else {
                        // Empty prior = first install on this (freshly-toggled)
                        // channel. We advanced the file at install start, so we
                        // MUST delete it on failure — otherwise getLastUpdateTimestamp
                        // keeps returning the failed build's timestamp, the
                        // first-install branch never re-fires, and the channel is
                        // permanently stuck at onNoUpdate. Mirrors the detached
                        // path's empty-prior rm. App UID can't write
                        // /data/local/tmp directly, so route through runShell.
                        runShell("rm -f " + timestampFileForChannel(channel),
                                new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                                    @Override public void onLog(String m) {}
                                    @Override public void onLaunched() {}
                                    @Override public void onError(String e) {}
                                });
                    }
                    // Restore VERSION_FILE to the prior display label so the
                    // About/web "current version" doesn't show the build that
                    // failed to install. (persistVersionToFile skips empty.)
                    if (priorDisplayVersion != null && !DISPLAY_VERSION_FALLBACK.equals(priorDisplayVersion)) {
                        persistVersionToFile(priorDisplayVersion);
                    }
                    // Wipe the post-update sentinels + the leftover APK — install
                    // never landed, so there's nothing for the next launch to
                    // recover from (rm APK mirrors the cancel/size-fail paths).
                    runShell(
                            "rm -f " + UPDATE_IN_PROGRESS_FILE + " " + POST_UPDATE_FILE + " " + APK_PATH,
                            new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                                @Override public void onLog(String m) {}
                                @Override public void onLaunched() {}
                                @Override public void onError(String e) {}
                            });
                    postInstallError(callback, "Install failed: " + output);
                } else {
                    // Install succeeded — NOW advance the persisted display label
                    // (the pre-install write at step 3 was removed so a failed
                    // install can never leave VERSION_FILE pointing at a build
                    // that didn't land). Guarded out for the "unknown" sentinel
                    // by persistVersionToFile itself.
                    persistVersionToFile(remoteVersion);
                    postProgress(callback, "✅ Update installed! Restarting...");
                    runCallback(callback::onSuccess);
                }
            } catch (Exception e) {
                if (cameraRestartPrepared) {
                    abortPreparedCameraRestart();
                    if (preparedChannel != null) {
                        rollbackPreparedUpdateMetadata(
                                preparedChannel,
                                preparedPriorTimestamp,
                                preparedPriorDisplayVersion);
                    }
                }
                Log.e(TAG, "Install error: " + e.getMessage());
                postInstallError(callback, e.getMessage());
            }
        });
    }

    // ==================== COMPANION APK INSTALL (OverDrive Launcher, WP-H) ====================
    //
    // Silent install of a SEPARATE companion package (com.overdrive.launcher)
    // from its OWN GitHub release track. This is fully ADDITIVE and REUSES the
    // download + `pm install` primitives of the core self-update path
    // ({@link #firstApkAsset}, {@link #buildClient}, {@link #downloadApkOkHttp} /
    // {@link #buildDownloadCommand}, {@link #runShell}), but deliberately does
    // NOT touch ANY of core's self-update bookkeeping:
    //   - no {@link #stopAllDaemons()} / kill cascade (we are NOT replacing
    //     OURSELVES, so no core process needs to die),
    //   - no per-channel baseline / VERSION_FILE / PREF_JUST_UPDATED writes
    //     (those track CORE's version, not the companion's),
    //   - no `am start` relaunch of MainActivity.
    // `pm install -r` of a DIFFERENT package never kills core's process, so the
    // simple synchronous flow the pre-daemon app-process path used is sufficient
    // and safe from either UID (runShell tunnels through the ADB daemon launcher
    // when the app UID can't write /data/local/tmp). The core self-update
    // methods ({@link #downloadAndInstall} / {@link #runDetachedInstall}) are
    // left BYTE-IDENTICAL by this addition.
    private static final String COMPANION_APK_PATH =
            "/data/local/tmp/overdrive_companion.apk";

    /** No-op shell callback for fire-and-forget cleanup commands. */
    private static final com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback NOOP_SHELL =
            new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String e) {}
            };

    /**
     * Resolve, download, and silently install a companion APK from a GitHub
     * release track that is INDEPENDENT of core's update channels. Runs on the
     * shared {@link #executor}. The companion (e.g. {@code com.overdrive.launcher})
     * is a fully optional add-on: this method has no side effect on core's own
     * version/state, and core behaves identically whether or not it is ever
     * called.
     *
     * <p>Serializes against the core self-update via the SAME process-wide
     * {@link #tryBeginInstall()} gate so a companion install and a core update
     * can't race the shared {@code /data/local/tmp} staging + {@code pm install}.
     *
     * @param repo     {@code "owner/name"} GitHub repo of the companion release
     *                 track (parameterized — the companion ships separately from
     *                 core, so its repo/tag are supplied by the caller)
     * @param tag      release tag to install (e.g. {@code "prod"})
     * @param callback progress / success / error surface — SAME contract as the
     *                 core install ({@link InstallCallback})
     */
    public void installCompanionApk(String repo, String tag, InstallCallback callback) {
        cancelled = false;
        executor.execute(() -> {
            boolean gateHeld = false;
            try {
                if (repo == null || repo.isEmpty() || tag == null || tag.isEmpty()) {
                    postInstallError(callback, "No companion release configured");
                    return;
                }
                if (!tryBeginInstall()) {
                    postInstallError(callback, "Another install is already in progress");
                    return;
                }
                gateHeld = true;

                // Step 1: resolve the first APK asset on the companion release.
                // Reuses the exact GitHub-release asset resolution the core path
                // uses (firstApkAsset), just against the companion repo/tag.
                postProgress(callback, "Resolving launcher release...");
                String apiUrl = "https://api.github.com/repos/" + repo
                        + "/releases/tags/" + tag;
                OkHttpClient client = buildClient(15, 15);
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();
                String downloadUrl;
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        postInstallError(callback, "GitHub API error: HTTP " + response.code());
                        return;
                    }
                    JSONObject release = new JSONObject(response.body().string());
                    String[] apk = firstApkAsset(release.optJSONArray("assets"));
                    if (apk == null) {
                        postInstallError(callback, "No APK found in launcher release");
                        return;
                    }
                    downloadUrl = apk[0];
                }

                // Step 2: download to a companion-specific path (NEVER the core
                // APK_PATH, so a concurrent core update and this can't clobber
                // each other's staged bytes). Same two transfer paths as the
                // core install: direct OkHttp when we can write /data/local/tmp
                // (daemon UID), else the ADB-tunnelled shell download.
                postProgress(callback, "Downloading launcher...");
                runCallback(() -> callback.onDownloadProgress(-1));
                final String[] dlResult = {null};
                if (canWriteLocalTmp()) {
                    try {
                        downloadApkOkHttp(downloadUrl, COMPANION_APK_PATH, callback);
                        dlResult[0] = "OK";
                    } catch (Exception e) {
                        dlResult[0] = "ERROR: " + (e.getMessage() == null ? "download failed" : e.getMessage());
                    }
                } else {
                    final boolean[] dlDone = {false};
                    String downloadCmd = buildDownloadCommand(downloadUrl, COMPANION_APK_PATH);
                    runShell(downloadCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) { dlResult[0] = m; }
                        @Override public void onLaunched() { dlDone[0] = true; synchronized (dlDone) { dlDone.notify(); } }
                        @Override public void onError(String e) { dlResult[0] = "ERROR: " + e; dlDone[0] = true; synchronized (dlDone) { dlDone.notify(); } }
                    });
                    synchronized (dlDone) { if (!dlDone[0]) dlDone.wait(300000); }
                }

                if (cancelled) {
                    runShell("rm -f " + COMPANION_APK_PATH, NOOP_SHELL);
                    postInstallError(callback, "Cancelled");
                    return;
                }
                String dlOutput = dlResult[0] != null ? dlResult[0] : "";
                if (dlOutput.startsWith("ERROR") || !dlOutput.contains("OK")) {
                    postInstallError(callback, "Download failed: " + dlOutput);
                    return;
                }
                runCallback(() -> callback.onDownloadProgress(100));

                // Step 3: size sanity check (same >=1MB floor as the core path).
                final boolean[] szDone = {false};
                final String[] szResult = {null};
                runShell("stat -c%s " + COMPANION_APK_PATH + " 2>/dev/null || echo 0",
                        new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String m) { szResult[0] = m.trim(); }
                    @Override public void onLaunched() { szDone[0] = true; synchronized (szDone) { szDone.notify(); } }
                    @Override public void onError(String e) { szResult[0] = "0"; szDone[0] = true; synchronized (szDone) { szDone.notify(); } }
                });
                synchronized (szDone) { if (!szDone[0]) szDone.wait(10000); }
                long fileSize = 0;
                try { fileSize = Long.parseLong(szResult[0].trim()); } catch (Exception ignored) {}
                if (fileSize < 1_000_000) {
                    runShell("rm -f " + COMPANION_APK_PATH, NOOP_SHELL);
                    postInstallError(callback, "Invalid APK (size: " + fileSize + ")");
                    return;
                }

                // Step 4: `pm install -r` the companion. NO daemon kill, NO
                // relaunch, NO baseline writes — this replaces a DIFFERENT
                // package, so core keeps running untouched.
                postProgress(callback, "Installing launcher...");
                final boolean[] done = {false};
                final String[] result = {null};
                String installCmd = "pm install -r " + COMPANION_APK_PATH
                        + "; rm -f " + COMPANION_APK_PATH;
                runShell(installCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                    @Override public void onLog(String m) { Log.i(TAG, "Companion install: " + m); result[0] = m; }
                    @Override public void onLaunched() { done[0] = true; synchronized (done) { done.notify(); } }
                    @Override public void onError(String e) { result[0] = "ERROR: " + e; done[0] = true; synchronized (done) { done.notify(); } }
                });
                synchronized (done) { if (!done[0]) done.wait(120000); }

                String output = result[0] != null ? result[0] : "";
                if (output.toLowerCase().contains("success")) {
                    postProgress(callback, "Launcher installed.");
                    runCallback(callback::onSuccess);
                } else {
                    runShell("rm -f " + COMPANION_APK_PATH, NOOP_SHELL);
                    postInstallError(callback, "Install failed: " + output);
                }
            } catch (Exception e) {
                Log.e(TAG, "Companion install error: " + e.getMessage());
                postInstallError(callback, e.getMessage());
            } finally {
                if (gateHeld) endInstall();
            }
        });
    }

    /**
     * Build a shell command that downloads a URL to a file path.
     * Uses Java's URL class via a shell one-liner (no curl/wget dependency).
     */
    private String buildDownloadCommand(String url, String outputPath) {
        // Shell-tunnel path (app process). Direct download in the daemon
        // process goes through downloadApkOkHttp instead.
        //
        // Deadline enforcement: BYD ships toybox 0.7.x (Android 7.1) where
        // toybox `wget` is the bare single-file applet — it ignores
        // `--timeout` and rejects `--tries` outright. Wrap with toybox's
        // own `timeout` applet so the deadline holds regardless of which
        // wget flavor is installed (busybox-wget, toybox-wget, vendor
        // bundle). 600s caps a 60 MB APK on a 100 kbps SIM. `timeout`
        // sends SIGTERM at the deadline and SIGKILL 5s later, so wget
        // dies cleanly and the `&& echo OK` short-circuits → "Download
        // failed:" surfaces in the UI instead of the 5-minute Java
        // waiter sweeping the symptom under the rug.
        //
        // curl gets the granular flags directly because curl on every
        // toolbox/toybox version ships with full flag parsing.
        return "sh -c 'java_url=\"" + url + "\"; " +
               "output=\"" + outputPath + "\"; " +
               "rm -f \"$output\"; " +
               "if command -v wget >/dev/null 2>&1; then " +
               "  timeout 600 wget -q -O \"$output\" \"$java_url\" && echo OK; " +
               "elif command -v curl >/dev/null 2>&1; then " +
               "  curl -sL --connect-timeout 15 --max-time 600 -o \"$output\" \"$java_url\" && echo OK; " +
               "else " +
               "  echo \"ERROR: No download tool available\"; " +
               "fi'";
    }

    /**
     * Stream an APK download to disk via OkHttp, emitting real percent
     * progress to the install callback. Used by the daemon-process path
     * (UID 2000) where we have direct write access to /data/local/tmp/
     * and can route through ProxyHelper.getHttpProxy() — same proxy the
     * rest of the app uses for outbound HTTP.
     *
     * Timeout policy:
     *   - connectTimeout = 15s  (covers slow handshake / blocked egress)
     *   - readTimeout    = 15s per read, NOT whole download
     *
     * OkHttp's readTimeout fires only when no bytes arrive for that many
     * seconds. A healthy slow link that delivers a packet every few
     * seconds keeps progressing. A wedged CDN socket where bytes stop
     * flowing throws SocketTimeoutException within 15s, which we surface
     * as a clean "Download failed" instead of the 5-minute hang the old
     * shell-wget path produced.
     *
     * Throws on any failure; caller wraps in dlResult[]. Honors
     * {@link #cancelled} between reads so the dialog's Cancel button
     * stops the transfer within one buffer's worth.
     */
    private void downloadApkOkHttp(String url, String outputPath, InstallCallback callback)
            throws Exception {
        // Drop any stale APK from a previous attempt so a partial download
        // can't be confused with a complete one if we fail mid-stream.
        new File(outputPath).delete();

        java.net.Proxy proxy = com.overdrive.app.mqtt.ProxyHelper.getHttpProxy();
        boolean usedProxy = proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT;
        try {
            doStreamingDownload(url, outputPath, proxy, callback);
        } catch (Exception primary) {
            // If we tried via sing-box/Tailscale and failed, the proxy may
            // have died mid-flight (sing-box restart, Tailscale link drop).
            // Invalidate the probe cache so MQTT and friends re-detect on
            // their next call, then retry once direct. If the network
            // truly requires the proxy (CN-firmware on the SIM, GitHub
            // CDN blocked), the direct retry still fails and we surface
            // the original error — better than silently swallowing a
            // recoverable proxy blip.
            if (cancelled) throw primary;
            if (!usedProxy) throw primary;
            Log.w(TAG, "Download via proxy failed (" + primary.getMessage() + "); retrying direct");
            com.overdrive.app.mqtt.ProxyHelper.invalidateCache();
            try {
                doStreamingDownload(url, outputPath, java.net.Proxy.NO_PROXY, callback);
            } catch (Exception retry) {
                // Surface the proxy-attempt error since that's what the user
                // is more likely to recognize (sing-box / tunnel issue), but
                // append the direct-retry detail for diagnostics.
                throw new java.io.IOException(
                        primary.getMessage() + " (direct retry: " + retry.getMessage() + ")");
            }
        }
    }

    /**
     * Single-shot streaming download. Caller decides whether to retry.
     */
    private void doStreamingDownload(String url, String outputPath,
                                     java.net.Proxy proxy, InstallCallback callback)
            throws Exception {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true);
        if (proxy != null && proxy.type() != java.net.Proxy.Type.DIRECT) {
            builder.proxy(proxy);
        }
        OkHttpClient client = builder.build();

        Request req = new Request.Builder().url(url).build();
        okhttp3.Call call = client.newCall(req);
        activeCall = call;
        Response resp;
        try {
            resp = call.execute();
        } catch (Exception e) {
            activeCall = null;
            throw e;
        }
        try {
            if (!resp.isSuccessful()) {
                throw new java.io.IOException("HTTP " + resp.code() + " " + resp.message());
            }
            long total = resp.body() != null ? resp.body().contentLength() : -1;
            int lastReportedPct = -1;
            long bytesRead = 0;

            // Drop any partial bytes from a failed prior proxy attempt before
            // the retry rewrites the file from scratch.
            new File(outputPath).delete();

            try (InputStream in = resp.body().byteStream();
                 FileOutputStream fos = new FileOutputStream(outputPath)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled) {
                        throw new java.io.IOException("Cancelled");
                    }
                    fos.write(buf, 0, n);
                    bytesRead += n;
                    if (total > 0) {
                        int pct = (int) Math.min(99, (bytesRead * 100L) / total);
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct;
                            final int report = pct;
                            runCallback(() -> callback.onDownloadProgress(report));
                        }
                    }
                }
                fos.flush();
            }
            Log.i(TAG, "APK downloaded: " + bytesRead + " bytes → " + outputPath
                    + " (proxy=" + (proxy != null ? proxy.type() : "DIRECT") + ")");
        } finally {
            try { resp.close(); } catch (Exception ignored) {}
            // Release the cancel hook so a follow-up cancel() doesn't try
            // to tear down a finished call. Set to null AFTER close so a
            // racing cancel still hits Call.cancel on the live socket.
            activeCall = null;
        }
    }

    static boolean isSuccessfulCameraPrepareStatus(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    /**
     * Ask CameraDaemon to durably checkpoint active-trip state and stop its
     * media pipeline before the updater launches any SIGKILL command.
     *
     * @return null on confirmed preparation, otherwise a user-facing reason
     */
    /** Attempts for a refusal the daemon reports as transient. */
    private static final int CAMERA_PREPARE_MAX_ATTEMPTS = 6;
    /** Matches the daemon's 1s pending-persistence drain cadence. */
    private static final long CAMERA_PREPARE_RETRY_DELAY_MS = 1000;

    /**
     * Ask the daemon to prepare for the kill, retrying while it reports a
     * transient reason.
     *
     * <p>Nearly every refusal is a few-second condition — a just-ended trip
     * still draining to disk, trip analytics still starting on its own thread,
     * or a final flush in progress. A single attempt turned those into a failed
     * update, which is why this reported "rejected restart 503" so often when
     * updating shortly after parking.
     *
     * @return null on confirmed preparation, otherwise a user-facing reason
     */
    private String prepareCameraDaemonForUpdate() {
        String lastFailure = null;
        for (int attempt = 1; attempt <= CAMERA_PREPARE_MAX_ATTEMPTS; attempt++) {
            if (cancelled) return "Cancelled";
            CameraPrepareAttempt result = attemptCameraDaemonPrepare();
            if (result.prepared) {
                if (attempt > 1) {
                    Log.i(TAG, "Camera daemon prepared for restart on attempt "
                            + attempt);
                }
                return null;
            }
            lastFailure = result.failure;
            if (!result.retryable || attempt == CAMERA_PREPARE_MAX_ATTEMPTS) {
                break;
            }
            Log.i(TAG, "Camera daemon not ready for restart (attempt " + attempt
                    + "/" + CAMERA_PREPARE_MAX_ATTEMPTS + "): " + lastFailure
                    + " — retrying");
            try {
                Thread.sleep(CAMERA_PREPARE_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return lastFailure;
            }
        }
        return lastFailure;
    }

    /** Outcome of one prepare-restart round trip. */
    private static final class CameraPrepareAttempt {
        final boolean prepared;
        final boolean retryable;
        final String failure;

        CameraPrepareAttempt(boolean prepared, boolean retryable, String failure) {
            this.prepared = prepared;
            this.retryable = retryable;
            this.failure = failure;
        }
    }

    private CameraPrepareAttempt attemptCameraDaemonPrepare() {
        HttpURLConnection connection = null;
        try {
            connection = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/surveillance/prepare-restart",
                    "POST",
                    3000,
                    10000);
            connection.setDoOutput(true);
            try (java.io.OutputStream body = connection.getOutputStream()) {
                body.write(new byte[0]);
            }
            int statusCode = connection.getResponseCode();
            if (isSuccessfulCameraPrepareStatus(statusCode)) {
                return new CameraPrepareAttempt(true, false, null);
            }
            // The body names the actual precondition AND whether waiting can
            // clear it. Reporting only the status code made every distinct
            // cause look like the same failure.
            JSONObject error = readErrorBody(connection);
            String detail = error != null
                    ? error.optString("error", "").trim() : "";
            // A failed pipeline teardown is a 503 that retrying cannot fix, so
            // the daemon's own verdict decides — not the status code. An absent
            // or unparseable verdict defaults to retryable so a body we cannot
            // read never silently disables the retry.
            boolean retryable = statusCode == 503
                    && (error == null || error.optBoolean("retryable", true));
            String reason = !detail.isEmpty()
                    ? detail
                    : "camera daemon rejected restart (HTTP " + statusCode + ")";
            return new CameraPrepareAttempt(false, retryable, reason);
        } catch (Exception e) {
            String detail = e.getMessage();
            if (detail == null || detail.trim().isEmpty()) {
                detail = e.getClass().getSimpleName();
            }
            // A transport failure can mean the daemon prepared but the response
            // was lost, so this is not retried blindly.
            return new CameraPrepareAttempt(false, false,
                    "camera daemon preparation failed: " + detail);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Parse a failed daemon response body. Returns null when there is no
     * readable JSON object, so the caller can distinguish "no verdict" from
     * an explicit one.
     */
    private static JSONObject readErrorBody(HttpURLConnection connection) {
        InputStream stream = null;
        try {
            stream = connection.getErrorStream();
            if (stream == null) return null;
            java.io.ByteArrayOutputStream buffer =
                    new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int n;
            // Bounded: a daemon error body is a short JSON object. Guard the
            // WRITE, not the next read — testing after the read discarded the
            // chunk that crossed the cap and left truncated, unparseable JSON.
            while ((n = stream.read(chunk)) != -1) {
                if (buffer.size() + n > 8192) break;
                buffer.write(chunk, 0, n);
            }
            String body = buffer.toString("UTF-8").trim();
            if (body.isEmpty()) return null;
            return new JSONObject(body);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Set when abort-restart could not resume trip sampling, so the caller can
     * tell the user their trip is no longer recording. Null when the last abort
     * succeeded or none was attempted.
     */
    private volatile String lastAbortRestartFailure;

    /**
     * Suffix warning the user that trip recording did not resume, or "" when it
     * did. Appended to the safe-stop message so a frozen recorder is visible
     * instead of hidden behind "stopped safely".
     */
    private String abortRestartWarning() {
        String failure = lastAbortRestartFailure;
        return failure == null ? ""
                : " (warning: trip recording did not resume — " + failure
                        + "; restart the camera daemon to resume)";
    }

    /**
     * Best-effort cancellation for a prepare request whose subsequent install
     * handoff failed. The endpoint is idempotent when trips are disabled or no
     * active trip was quiesced.
     */
    private void abortPreparedCameraRestart() {
        HttpURLConnection connection = null;
        try {
            connection = com.overdrive.app.util.DaemonHttpClient.open(
                    "/api/surveillance/abort-restart",
                    "POST",
                    2000,
                    3000);
            connection.setDoOutput(true);
            try (java.io.OutputStream body = connection.getOutputStream()) {
                body.write(new byte[0]);
            }
            int statusCode = connection.getResponseCode();
            if (!isSuccessfulCameraPrepareStatus(statusCode)) {
                // A 503 here means trip sampling stayed FROZEN. The update
                // stopped safely, but the live trip records nothing until the
                // daemon restarts, so this must be loud rather than a warning
                // buried in the log.
                JSONObject error = readErrorBody(connection);
                String detail = error != null
                        ? error.optString("error", "").trim() : "";
                Log.e(TAG, "abort-restart returned HTTP " + statusCode
                        + (detail.isEmpty() ? "" : ": " + detail)
                        + " — trip sampling may still be frozen");
                lastAbortRestartFailure = detail.isEmpty()
                        ? "camera daemon could not resume trip recording"
                        : detail;
            } else {
                lastAbortRestartFailure = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not abort prepared camera restart: "
                    + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Restore state written immediately before a prepared kill when no kill
     * handoff occurred. This keeps a safe abort from suppressing the same
     * update on the next attempt.
     */
    private void rollbackPreparedUpdateMetadata(
            String channel,
            String priorUpdateTimestamp,
            String priorDisplayVersion) {
        try {
            SharedPreferences.Editor editor =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_JUST_UPDATED, false)
                            .putString(prefKeyForChannel(channel),
                                    priorUpdateTimestamp != null
                                            ? priorUpdateTimestamp : "");
            if (priorDisplayVersion != null
                    && !priorDisplayVersion.isEmpty()
                    && !DISPLAY_VERSION_FALLBACK.equals(priorDisplayVersion)) {
                editor.putString(PREF_UPDATED_VERSION, priorDisplayVersion);
            } else {
                editor.remove(PREF_UPDATED_VERSION);
            }
            editor.commit();

            if (priorUpdateTimestamp != null
                    && !priorUpdateTimestamp.isEmpty()) {
                saveLastUpdateTimestamp(channel, priorUpdateTimestamp);
            } else {
                cleanup(timestampFileForChannel(channel));
            }
            cleanup(UPDATE_IN_PROGRESS_FILE + " " + POST_UPDATE_FILE + " "
                    + APK_PATH + " /data/local/tmp/overdrive_install.sh "
                    + "/data/local/tmp/overdrive_install.sh.tmp");
            runShell(
                    "for S in /data/local/tmp/camera_daemon.disabled "
                            + "/data/local/tmp/acc_sentry_daemon.disabled; do "
                            + "R=$(head -1 \"$S\" 2>/dev/null); "
                            + "case \"$R\" in 'disabled for update'*|"
                            + "'disabled by stopAllDaemons sweep'*|"
                            + "'disabled by killDaemon'*) "
                            + "rm -f \"$S\" 2>/dev/null;; esac; done",
                    NOOP_SHELL);
        } catch (Exception e) {
            Log.w(TAG, "Could not roll back aborted update metadata: "
                    + e.getMessage());
        }
    }

    /**
     * Daemon-process install path: write a self-contained install script and
     * fire it off detached (setsid + null fds) so it survives our death.
     *
     * Why a script instead of a single `runShell(...)`: this very process is
     * one of the things `pkill -9 -f byd_cam_daemon` will kill. If the kill
     * runs in our own shell we'd suicide before `pm install` ever fires. The
     * script is launched with setsid into a new session with closed std fds,
     * so the kernel doesn't reap it when the daemon dies.
     *
     * The script: kills watchdogs first (so they can't respawn the daemons
     * we're about to kill), kills daemons (including us), runs `pm install
     * -r -d`, then `am start` to relaunch the app. Same sequence the
     * synchronous app-process flow uses, just packaged so the caller can
     * exit before it runs.
     *
     * The webapp tracks success via /api/update/progress + the app coming
     * back online; the SharedPreferences `PREF_JUST_UPDATED` flag we wrote
     * in step 3 is what the new MainActivity reads to confirm.
     *
     * @return true only after the detached script process was launched
     */
    private boolean runDetachedInstall(InstallCallback callback, String channel,
                                       String priorUpdateTimestamp,
                                       String priorDisplayVersion) {
        String scriptPath = "/data/local/tmp/overdrive_install.sh";
        String logPath = "/data/local/tmp/overdrive_install.log";

        StringBuilder script = new StringBuilder();
        script.append("#!/system/bin/sh\n");
        script.append("set +e\n");
        script.append("exec >").append(logPath).append(" 2>&1\n");
        script.append("echo \"[install] starting at $(date)\"\n");
        // Step 1: plant sentinels so the new MainActivity recovers correctly.
        script.append("echo 'update at '$(date) > ").append(UPDATE_IN_PROGRESS_FILE).append("\n");
        script.append("echo 'update at '$(date) > ").append(POST_UPDATE_FILE).append("\n");
        // Step 2: plant the camera disable sentinel so any watchdog we miss
        // exits on its next iteration, then kill everything in one syscall
        // per family. Single broad pkill on 'cam_daemon' / 'acc_sentry' takes
        // out watchdog + daemon together — no kill-order race, no sleep
        // window for one to respawn the other. Each `2>/dev/null` so a
        // "no such process" exit doesn't abort the script.
        script.append("[ -f /data/local/tmp/camera_daemon.disabled ] || "
                + "echo \"disabled for update at $(date)\" > /data/local/tmp/camera_daemon.disabled\n");
        script.append("chmod 666 /data/local/tmp/camera_daemon.disabled 2>/dev/null\n");
        // Plant the acc-sentry sentinel so its shell watchdog
        // (start_acc_sentry.sh) bails out on its next iteration if our pkill
        // misses a respawn race. Cleared below alongside camera_daemon.disabled.
        // World-readable so the watchdog (UID 2000) can stat the file when it
        // was written by the install script (running as the app UID).
        //
        // We deliberately do NOT plant the OPTIONAL-daemon sentinels
        // (zrok.disabled, telegram_bot_daemon.disabled, tailscale.disabled,
        // singbox.disabled): they encode a DURABLE user stop that must survive
        // the update, and overwriting them here (then rm-ing below) would
        // destroy that record and resurrect a user-stopped tunnel/bot. The
        // pkill cascade below takes those daemons out regardless. Mirrors
        // UpdateLifecycle's core-only sentinel handling.
        script.append("[ -f /data/local/tmp/acc_sentry_daemon.disabled ] || "
                + "echo \"disabled for update at $(date)\" > /data/local/tmp/acc_sentry_daemon.disabled\n");
        script.append("chmod 666 /data/local/tmp/acc_sentry_daemon.disabled 2>/dev/null\n");
        script.append(psAwkKillLine("cam_daemon"));
        script.append(psAwkKillLine("acc_sentry"));
        script.append(psAwkKillLine("start_telegram"));
        script.append("killall -9 byd_cam_daemon 2>/dev/null\n");
        script.append(psAwkKillLine("sentry_daemon"));
        script.append(psAwkKillLine("telegram_bot_daemon"));
        script.append(psAwkKillLine("sentry_proxy"));
        script.append(psAwkKillLine("cloudflared"));
        script.append("killall -9 cloudflared 2>/dev/null\n");
        script.append(psAwkKillLine("zrok"));
        script.append("killall -9 zrok 2>/dev/null\n");
        script.append(psAwkKillLine("sing-box"));
        script.append("killall -9 sing-box 2>/dev/null\n");
        script.append(psAwkKillLine("tailscaled"));
        script.append("killall -9 tailscaled 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/cam_watchdog.pid 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_acc_sentry.sh /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_zrok.sh 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/start_telegram.sh 2>/dev/null\n");

        // Per-daemon lock files (mirrors DaemonLauncher's killDaemonViaAdb
        // cleanup) so the relaunched MainActivity's daemon supervisor doesn't
        // refuse to start because a stale lock looks alive.
        script.append("rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/telegram_bot_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/*_daemon.lock 2>/dev/null\n");
        script.append("rm -f /data/local/tmp/cam_watchdog.pid 2>/dev/null\n");
        // Sweep ONLY the transient config staging siblings a daemon killed
        // mid-write may orphan (overdrive_config.json.tmp.<pid> from the atomic
        // write, and the .bak.tmp staging file). MUST NOT touch the live config,
        // its .bak, or .bad — those are the recovery copies. Explicit suffixes,
        // never a broad overdrive_config* / *.json glob.
        script.append("rm -f /data/local/tmp/overdrive_config.json.tmp.* "
                + "/data/local/tmp/overdrive_config.json.bak.tmp 2>/dev/null\n");
        // Clear only machine-written CORE markers. Pre-existing UI/Telegram
        // markers are durable manual stop intent and survive the update.
        // POST_UPDATE_FILE / UPDATE_IN_PROGRESS_FILE stay in place; the new
        // process consumes them via UpdateLifecycle.
        script.append("for S in /data/local/tmp/camera_daemon.disabled "
                + "/data/local/tmp/acc_sentry_daemon.disabled; do\n");
        script.append("  R=$(head -1 \"$S\" 2>/dev/null)\n");
        script.append("  case \"$R\" in 'disabled for update'*|"
                + "'disabled by stopAllDaemons sweep'*|'disabled by killDaemon'*) "
                + "rm -f \"$S\" 2>/dev/null;; esac\n");
        script.append("done\n");
        script.append("sleep 2\n");
        // Step 4: install. `pm install -r -d` allows downgrades (-d) so a
        // bad release doesn't strand the user, and replaces the existing app
        // (-r). Stdout is captured into PM_OUT so step 4b can include the
        // failure reason in the progress JSON if `pm install` exits non-zero.
        script.append("echo \"[install] running pm install\"\n");
        script.append("PM_OUT=$(pm install -r -d ").append(APK_PATH).append(" 2>&1)\n");
        script.append("INSTALL_RC=$?\n");
        script.append("echo \"$PM_OUT\"\n");
        script.append("rm -f ").append(APK_PATH).append("\n");
        // Step 4b: on `pm install` failure, write phase=error to the progress
        // JSON so the webapp's poller surfaces the failure instead of sitting
        // in reconnect-mode forever waiting for an upgraded daemon that will
        // never appear. Same shape as UpdateApiHandler.writeProgress so the
        // /api/update/progress endpoint and update-flow.js consume it without
        // changes. We do this before `am start` so the relaunched app reports
        // the error in its own UI, and the failure write happens BEFORE the
        // sentinels are cleared (the new MainActivity reads PROGRESS_FILE on
        // start and can roll back PREF_JUST_UPDATED if it sees phase=error).
        // `pm install` Success path skips this block entirely; the daemon
        // restart that follows `am start` re-writes phase=installing/100 and
        // eventually a fresh daemon overwrites with idle.
        script.append("if [ \"$INSTALL_RC\" != \"0\" ]; then\n");
        // Escape special chars in PM_OUT for JSON. toybox lacks `jq`, but we
        // can substitute backslashes, double-quotes, and newlines via
        // parameter expansion (POSIX) — covers the ~99% case of pm install's
        // single-line failure messages like "Failure [INSTALL_PARSE_FAILED…]".
        // Strip ALL control chars first (`tr -d '\000-\037'`) — a stray tab
        // or stack-trace embedded NUL would produce technically-invalid JSON
        // and JSONObject would throw, swallowing the error in the consumer.
        script.append("  PM_ESC=$(printf %s \"$PM_OUT\" | tr -d '\\000-\\037' | ");
        script.append("sed 's/\\\\/\\\\\\\\/g;s/\"/\\\\\"/g')\n");
        script.append("  TS=$(($(date +%s) * 1000))\n");
        // Embed the prior per-channel baseline + the channel itself so
        // consumeFailedUpdateError can roll back the CORRECT per-channel key.
        // Without rollback, a failed install permanently advances the
        // baseline timestamp and the next checkForUpdate reports "no update
        // available" — user is silently stuck on the old version until the
        // GitHub asset is re-uploaded. priorTs is an ISO timestamp like
        // 2026-05-29T10:00:00Z and channel is a fixed enum ("alpha" /
        // "braveheart") — no backslashes/quotes/control chars, so direct
        // interpolation is safe.
        // These values are interpolated into the printf FORMAT string literal,
        // so a stray '%' would be read as a directive and a quote would break
        // the literal. shellSafe() restricts them to [A-Za-z0-9._-]; the
        // values are already constrained (ISO ts, enum channel, strict-tag /
        // dotted version) so this only strips the space in the "Manually
        // Installed" fallback (harmless — that case has no real prior build).
        script.append("  printf '{\"phase\":\"error\",\"percent\":-1,");
        script.append("\"message\":\"Install failed\",\"error\":\"%s\",");
        script.append("\"priorUpdateTs\":\"")
              .append(isoSafe(priorUpdateTimestamp))   // colons preserved; "" if malformed
              .append("\",\"priorUpdateChannel\":\"")
              .append(shellSafe(channel))
              .append("\",\"priorDisplayVersion\":\"")
              .append(shellSafe(priorDisplayVersion))
              .append("\",\"ts\":%s}' ");
        script.append("\"$PM_ESC\" \"$TS\" > /data/local/tmp/overdrive_update_progress.json\n");
        script.append("  echo \"[install] FAILED rc=$INSTALL_RC\"\n");
        // Roll back the on-disk baseline + display files the daemon advanced
        // before pm install (the app-process consumeFailedUpdateError only
        // fixes the SharedPreferences half — these /data/local/tmp files need a
        // UID-2000 write, which we have right here). Restore the per-channel
        // timestamp file and VERSION_FILE to their pre-attempt values so a
        // later reinstall-read or daemon-side display doesn't surface the build
        // that didn't land. Empty prior → remove the file (the legitimate
        // "no prior install" / sentinel state).
        // shellSafe() on every interpolated value — defense-in-depth against a
        // quote/`%` breakout even though the inputs are already constrained
        // (ISO timestamp, dotted-numeric/strict-tag version, enum channel).
        String safeChannel = shellSafe(channel);
        // isoSafe (NOT shellSafe) for the timestamp — preserve the colons so
        // the restored baseline still matches the remote updated_at. Empty/
        // malformed → remove the file (the legitimate no-prior-baseline case).
        String safePriorTs = isoSafe(priorUpdateTimestamp);
        if (!safePriorTs.isEmpty()) {
            script.append("  echo '").append(safePriorTs).append("' > ")
                  .append(timestampFileForChannel(safeChannel)).append("\n");
            // World-readable so the app process (UID 10xxx) reads the same
            // restored baseline the daemon (UID 2000) just wrote — matches the
            // saveLastUpdateTimestamp / VERSION_FILE chmod contract.
            script.append("  chmod 644 ").append(timestampFileForChannel(safeChannel)).append(" 2>/dev/null\n");
        } else {
            script.append("  rm -f ").append(timestampFileForChannel(safeChannel)).append("\n");
        }
        if (priorDisplayVersion != null && !priorDisplayVersion.isEmpty()
                && !DISPLAY_VERSION_FALLBACK.equals(priorDisplayVersion)) {
            script.append("  echo '").append(shellSafe(priorDisplayVersion)).append("' > ")
                  .append(VERSION_FILE).append("\n");
            // World-readable so the app process can read it (cross-UID).
            script.append("  chmod 644 ").append(VERSION_FILE).append(" 2>/dev/null\n");
        } else {
            script.append("  rm -f ").append(VERSION_FILE).append("\n");
        }
        // Clear the in-progress sentinel so the new MainActivity doesn't run a
        // post-update hard-reset for an install that never landed. Keep
        // POST_UPDATE_FILE — its presence on a still-old-version app is the
        // signal MainActivity uses to read PROGRESS_FILE and show the error.
        script.append("  rm -f ").append(UPDATE_IN_PROGRESS_FILE).append("\n");
        // Telegram failure surfacing: if this was an IPC-triggered install
        // (handleInstallUpdate plants TELEGRAM_POST_UPDATE_HINT_FILE before pm
        // install — the web path does NOT), then on FAILURE we hand the reborn
        // Telegram bot a FAILURE hint so it tells the owner the install failed,
        // symmetric with the web (toast install_failed) and app (showError +
        // post-update toast) surfaces. Without this the bot would fall through
        // to the generic "Tunnel URL Changed" copy and the user — still on the
        // OLD version — would never learn the install they tapped failed.
        // Gate on the success hint's presence so a web/app-triggered failure
        // doesn't message the owner about an install they didn't trigger from
        // Telegram. Write the escaped pm error ($PM_ESC, control-chars already
        // stripped, single-line) so the bot can quote the reason. Plant the
        // failure hint BEFORE deleting the success hint (mutually exclusive:
        // the success "updated to X" message is then impossible for this run).
        script.append("  if [ -f ")
              .append(UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE)
              .append(" ]; then printf %s \"$PM_ESC\" > ")
              .append(UpdateLifecycle.TELEGRAM_INSTALL_FAILED_HINT_FILE)
              .append("; fi\n");
        // Delete the Telegram post-update hint planted at install-time (it's
        // written unconditionally BEFORE pm install in handleInstallUpdate).
        // On FAILURE it would otherwise survive, and the reborn Telegram bot's
        // consumePostUpdateHint() would send a FALSE "Overdrive updated to X"
        // confirmation for an install that never landed. Remove it so a failed
        // Telegram-triggered install stays silent on the SUCCESS channel (the
        // failure is instead surfaced via the FAILURE hint planted just above,
        // plus the app's PROGRESS_FILE path).
        script.append("  rm -f ").append(UpdateLifecycle.TELEGRAM_POST_UPDATE_HINT_FILE).append("\n");
        script.append("else\n");
        // SUCCESS path: on the detached (daemon UID-2000) flow AppUpdater never
        // calls onSuccess (it returns right after spawning this script — see
        // the "DO NOT call onSuccess here" note below), so the last on-disk
        // progress record is writeProgress("installing", -1) from
        // UpdateApiHandler.onProgress classifying the "Installing..." message
        // (installing@100 is only written by the SYNCHRONOUS app-process
        // onSuccess path, never reached here). Nothing else overwrites it until
        // the NEXT install's "queued" write, so delete it now — pure hygiene so
        // a fresh poll doesn't see a stale terminal record.
        script.append("  rm -f /data/local/tmp/overdrive_update_progress.json\n");
        // Advance the persisted display label NOW — and ONLY here, on pm-install
        // success. VERSION_FILE drives the user-visible "current version" on every
        // surface (About / status / toast), so it must move only after the new
        // bytes actually landed; the pre-install write was removed for exactly
        // this reason. Mirror the failure-branch restore shape: echo the canonical
        // label + chmod 644 so the OTHER UID can read it cross-process. Guarded
        // against the "unknown" sentinel (a version-less filename) so we never
        // clobber a valid prior label with junk the read-side rejects anyway.
        if (remoteVersion != null && !remoteVersion.isEmpty()
                && !"unknown".equals(remoteVersion)) {
            script.append("  echo '").append(shellSafe(remoteVersion)).append("' > ")
                  .append(VERSION_FILE).append("\n");
            script.append("  chmod 644 ").append(VERSION_FILE).append(" 2>/dev/null\n");
        }
        // Clear any stale FAILURE hint from a PRIOR failed install so the reborn
        // bot doesn't send a failure message on top of this success. (The
        // success hint is intentionally KEPT here so notifyTunnel frames the
        // "Overdrive updated to X" message.)
        script.append("  rm -f ").append(UpdateLifecycle.TELEGRAM_INSTALL_FAILED_HINT_FILE).append("\n");
        script.append("fi\n");
        // Step 5: relaunch. Runs in both success and failure cases so the user
        // gets the app back either way (with the new APK on success, or with
        // the old APK + an error toast on failure).
        script.append("sleep 2\n");
        script.append("am start -n com.overdrive.app/.ui.MainActivity --ez ");
        script.append(UpdateLifecycle.EXTRA_POST_UPDATE).append(" true\n");
        script.append("echo \"[install] done rc=$INSTALL_RC at $(date)\"\n");

        try {
            // Atomic write: stream into a .tmp sibling, fsync, then rename
            // onto the final path. If the daemon is killed mid-write (very
            // small window between the write and the spawn below), the
            // partial .tmp file is harmless on its own — only after the
            // rename completes does the launcher see a valid script. The
            // previous direct-write approach could leave a partially-formed
            // script that fired pkill but never reached the pm install
            // section, leaving the device with daemons dead and no
            // recovery on the same boot.
            String tmpPath = scriptPath + ".tmp";
            java.io.File tmpFile = new java.io.File(tmpPath);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmpFile)) {
                fos.write(script.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            tmpFile.setExecutable(true, false);
            java.io.File finalFile = new java.io.File(scriptPath);
            // Java's File.renameTo is best-effort across filesystems but
            // /data/local/tmp/<a> → /data/local/tmp/<b> is intra-fs so
            // it's atomic on every Android kernel.
            if (!tmpFile.renameTo(finalFile)) {
                // Fallback: explicit delete + rename. Some BYD ROMs reject
                // renameTo if the target exists despite POSIX rename(2)
                // semantics — defensive.
                finalFile.delete();
                if (!tmpFile.renameTo(finalFile)) {
                    throw new java.io.IOException("Could not rename install script into place");
                }
            }

            // Detach: a subshell with `& exit 0` reparents the install script
            // to init (PID 1), so SIGTERM/SIGHUP from the daemon's death don't
            // reach it. We don't rely on `setsid` — toybox builds on BYD ROMs
            // are inconsistent about which applets ship. The script itself
            // does `exec >log 2>&1` to close all stdio, and `</dev/null` here
            // closes stdin so nothing keeps the parent waiting.
            ProcessBuilder pb = new ProcessBuilder(
                    "sh", "-c",
                    "(sh " + scriptPath + " </dev/null >/dev/null 2>&1 &)");
            pb.redirectErrorStream(true);
            pb.start();

            postProgress(callback, "Installing...");
            // DO NOT call onSuccess here. The detached script hasn't run
            // pm install yet — it's still in its 1-second sleep. Calling
            // onSuccess clears installInFlight in the API handler, which
            // lets a second concurrent install request slip through, and
            // it tells the UI "✅ Update installed!" before the install
            // has actually started. The script writes its own terminal
            // progress (phase=error on pm-install failure, daemon process
            // death on success), and the webapp's poller is the source of
            // truth from this point. The original comment ("Just return
            // and let the script + webapp poller take it from here") was
            // correct; the runCallback below contradicted it. Removed.
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Detached install failed: " + e.getMessage());
            postInstallError(callback, "Detached install failed: " + e.getMessage());
            return false;
        }
    }

    private void cleanup(String path) {
        try {
            runShell("rm -f " + path, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String e) {}
            });
        } catch (Exception ignored) {}
    }

    private boolean stopAllDaemons() {
        Log.i(TAG, "Stopping all daemons...");

        com.overdrive.app.launcher.AdbDaemonLauncher launcher = getAdbLauncher();

        // Step 0: Plant the post-update sentinels so the new process knows to
        // run a hard-reset before starting daemons (see UpdateLifecycle). The
        // BootReceiver path is intentionally inert on MY_PACKAGE_REPLACED, so
        // the new MainActivity is the sole daemon orchestrator after install.
        final boolean[] markerDone = {false};
        String markerCmd =
                "echo 'update at $(date)' > " + UPDATE_IN_PROGRESS_FILE + "; " +
                "echo 'update at $(date)' > " + POST_UPDATE_FILE + "; " +
                "echo done";
        runShell(markerCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                markerDone[0] = true;
                synchronized (markerDone) { markerDone.notify(); }
            }
            @Override public void onError(String e) {
                Log.w(TAG, "Sentinel write: " + e);
                markerDone[0] = true;
                synchronized (markerDone) { markerDone.notify(); }
            }
        });
        try {
            synchronized (markerDone) {
                if (!markerDone[0]) markerDone.wait(3000);
            }
        } catch (InterruptedException ignored) {}

        // Step 1: Hard-kill the cam_daemon and acc_sentry families in one
        // sweep — broad pkill -f matches both watchdog and daemon
        // simultaneously so neither survives to respawn the other. Plant the
        // camera disable sentinel first so any straggler watchdog exits on
        // its next iteration.
        //
        // CRITICAL: must use runShellScript (tmpfile-backed) rather than
        // runShell (`sh -c "<body>"`). With runShell, the calling shell's
        // argv contains the literal patterns 'cam_daemon' and 'acc_sentry',
        // so the very first `pkill -9 -f 'cam_daemon'` SIGKILLs the calling
        // shell — every command after the first pkill is silently dropped
        // (acc_sentry kill, lock rms, the echo done). Phase-3 sweep at
        // line 1242 below already used runShellScript for the same reason;
        // step-1 was the last `sh -c` survivor of the self-match family of
        // bugs.
        Log.i(TAG, "Killing daemons and watchdogs...");
        String killWatchdogsCmd =
                "[ -f /data/local/tmp/camera_daemon.disabled ] || " +
                "echo 'disabled for update at $(date)' > /data/local/tmp/camera_daemon.disabled\n" +
                psAwkKillLine("cam_daemon") +
                psAwkKillLine("acc_sentry") +
                "rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/cam_watchdog.pid /data/local/tmp/camera_daemon.lock 2>/dev/null\n" +
                "rm -f /data/local/tmp/start_acc_sentry.sh /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null\n" +
                "echo done\n";

        final boolean[] wdDone = {false};
        runShellScript(killWatchdogsCmd, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                Log.i(TAG, "Watchdog scripts killed");
                wdDone[0] = true;
                synchronized (wdDone) { wdDone.notify(); }
            }
            @Override public void onError(String e) {
                Log.w(TAG, "Watchdog kill: " + e);
                wdDone[0] = true;
                synchronized (wdDone) { wdDone.notify(); }
            }
        });
        try {
            synchronized (wdDone) {
                if (!wdDone[0]) wdDone.wait(5000);
            }
        } catch (InterruptedException ignored) {}
        
        // Step 2: Kill the rest of the daemon families. cam_daemon and
        // acc_sentry are already gone from step 1 — this loop covers the
        // standalone daemons (telegram, proxy) and native binaries
        // (cloudflared, zrok, sing-box, tailscaled).
        String[] daemons = {"sentry_daemon",
                "telegram_bot_daemon", "sentry_proxy", "cloudflared", "zrok", "sing-box",
                "tailscaled"};
        
        for (String daemon : daemons) {
            final boolean[] done = {false};
            launcher.killDaemon(daemon, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {
                    Log.i(TAG, "Stopped: " + daemon);
                    done[0] = true;
                    synchronized (done) { done.notify(); }
                }
                @Override public void onError(String e) {
                    Log.w(TAG, "Stop " + daemon + ": " + e);
                    done[0] = true;
                    synchronized (done) { done.notify(); }
                }
            });
            
            try {
                synchronized (done) {
                    if (!done[0]) done.wait(5000);
                }
            } catch (InterruptedException ignored) {}
        }
        
        // Step 3: Final sweep — broad pkill on every family pattern catches
        // any stragglers (orphaned shells, late-respawn races). One syscall
        // per family is enough; no need to re-list watchdog vs daemon
        // separately because the broad pattern covers both.
        // NOTE: we keep UPDATE_IN_PROGRESS_FILE / POST_UPDATE_FILE in place;
        // the new process clears them after its own hard-reset pass.
        Log.i(TAG, "Final sweep for remaining processes...");
        // Single script-via-tmp-file invocation — `executeShellScript` writes
        // the body to a temp file and `sh <path>`s it. Calling shell's argv
        // is just `sh <path>`, so toybox `pkill -f 'cam_daemon'` cannot
        // match the calling shell. This replaces the earlier 2-phase split
        // that was needed to defend against pkill self-suicide.
        //
        // Order: sentinels → rm scripts → pkill cascade → settle → rm locks.
        // Lock-rm is AFTER pkill (not before) to prevent the lockfile
        // resurrection race: a still-alive daemon can rewrite its PID into
        // the lock between rm and pkill.
        //
        // Per-daemon disable sentinels remain set after this returns; the
        // subsequent install script (buildInstallScript) clears the CORE ones
        // right before `am start`. The OPTIONAL ones (telegram, zrok, …) are
        // deliberately left in place so a durable user stop survives the update.
        //
        // The zrok + telegram sentinels are written ONLY IF ABSENT (`[ -f ] || echo`)
        // rather than with a plain `>`. A clobbering write destroyed the
        // distinction the post-update/ACC-off auto-start gates depend on: they read the
        // sentinel's TEXT to tell a machine stop ("stopAllDaemons sweep" — safe
        // to disregard) from a durable user stop ("disabled by ui"). Overwriting
        // a pre-existing "disabled by ui" made a real user stop indistinguishable
        // from our own. Preserving the original text keeps both readings correct
        // with no ambiguity to resolve downstream.
        String sweepScript =
                "[ -f /data/local/tmp/zrok.disabled ] || "
                + "echo \"disabled by stopAllDaemons sweep at $(date)\" > /data/local/tmp/zrok.disabled\n" +
                "chmod 666 /data/local/tmp/zrok.disabled 2>/dev/null\n" +
                "[ -f /data/local/tmp/acc_sentry_daemon.disabled ] || "
                + "echo \"disabled by stopAllDaemons sweep at $(date)\" > /data/local/tmp/acc_sentry_daemon.disabled\n" +
                "chmod 666 /data/local/tmp/acc_sentry_daemon.disabled 2>/dev/null\n" +
                "[ -f /data/local/tmp/telegram_bot_daemon.disabled ] || "
                + "echo \"disabled by stopAllDaemons sweep at $(date)\" > /data/local/tmp/telegram_bot_daemon.disabled\n" +
                "chmod 666 /data/local/tmp/telegram_bot_daemon.disabled 2>/dev/null\n" +
                "rm -f /data/local/tmp/cam_watchdog.pid 2>/dev/null\n" +
                "rm -f /data/local/tmp/start_cam_daemon.sh /data/local/tmp/start_acc_sentry.sh /data/local/tmp/start_zrok.sh /data/local/tmp/start_telegram.sh 2>/dev/null\n" +
                psAwkKillLine("cam_daemon") +
                psAwkKillLine("acc_sentry") +
                psAwkKillLine("sentry_daemon") +
                psAwkKillLine("telegram_bot_daemon") +
                psAwkKillLine("sentry_proxy") +
                psAwkKillLine("cloudflared") +
                psAwkKillLine("zrok") +
                psAwkKillLine("sing-box") +
                psAwkKillLine("tailscaled") +
                "killall -9 cloudflared 2>/dev/null\n" +
                "killall -9 zrok 2>/dev/null\n" +
                "killall -9 tailscaled 2>/dev/null\n" +
                "killall -9 sing-box 2>/dev/null\n" +
                "sleep 1\n" +
                "rm -f /data/local/tmp/*_daemon.lock 2>/dev/null\n" +
                "CAMERA_PIDS=$(ps -A -o PID,ARGS | grep -F 'cam_daemon' "
                + "| grep -v grep | awk -v self=$$ '$1 != self {print $1}')\n" +
                "if [ -n \"$CAMERA_PIDS\" ]; then "
                + "echo \"camera daemon still running: $CAMERA_PIDS\" >&2; "
                + "exit 1; fi\n" +
                "echo done\n";

        final boolean[] sweepDone = {false};
        final boolean[] cameraStopConfirmed = {false};
        runShellScript(sweepScript, new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                cameraStopConfirmed[0] = true;
                sweepDone[0] = true;
                synchronized (sweepDone) { sweepDone.notify(); }
            }
            @Override public void onError(String e) {
                Log.w(TAG, "stopAllDaemons sweep error: " + e);
                sweepDone[0] = true;
                synchronized (sweepDone) { sweepDone.notify(); }
            }
        });
        try {
            synchronized (sweepDone) {
                if (!sweepDone[0]) sweepDone.wait(5000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (cameraStopConfirmed[0]) {
            Log.i(TAG, "All daemons and watchdogs stopped");
        } else {
            Log.w(TAG, "Camera daemon stop was not confirmed; update aborted");
        }
        return cameraStopConfirmed[0];
    }

    /** Strict alpha tag allowlist: bare "alpha" or "alpha-v<semver>". */
    private static final java.util.regex.Pattern VALID_ALPHA_TAG =
            java.util.regex.Pattern.compile("^alpha(-v\\d+\\.\\d+(\\.\\d+)?)?$");

    public static boolean isValidAlphaTag(String tag) {
        return tag != null && VALID_ALPHA_TAG.matcher(tag).matches();
    }

    /**
     * Make a value safe to interpolate into a single-quoted shell string or a
     * printf format. Defense-in-depth: the tag is already strictly validated
     * and version labels come from extractVersion's dotted-numeric regex, but
     * any value that reaches `echo '<v>' > file` / printf in the detached
     * install script is scrubbed to [A-Za-z0-9._-] so a stray quote or '%'
     * can never break out. Drops everything else.
     */
    static String shellSafe(String s) {
        if (s == null) return "";
        return s.replaceAll("[^A-Za-z0-9._-]", "");
    }

    /** Strict ISO-8601 instant the GitHub asset updated_at uses. */
    private static final java.util.regex.Pattern ISO_INSTANT =
            java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$");

    /**
     * Return an ISO-8601 timestamp VERBATIM iff it matches the exact GitHub
     * updated_at shape, else "". Used instead of shellSafe() for timestamps:
     * shellSafe strips the ':' (its charset is for version labels), which would
     * mangle 2026-05-29T10:00:00Z → 2026-05-29T100000Z and permanently break
     * baseline .equals() matching + the SimpleDateFormat parse. A string that
     * matches ISO_INSTANT has no quote/backslash/% so it's safe to interpolate
     * into a single-quoted echo or a printf format literal as-is; anything else
     * is dropped (caller treats "" as the no-prior-baseline / remove-file case).
     */
    static String isoSafe(String ts) {
        if (ts == null) return "";
        return ISO_INSTANT.matcher(ts).matches() ? ts : "";
    }

    /**
     * Extract version from APK filename including channel.
     * "overdrive-release-alpha-v6.1.apk" → "alpha-v6.1"
     * "overdrive-release-prod-v2.0.1.apk" → "prod-v2.0.1"
     */
    static String extractVersion(String apkName) {
        if (apkName != null) {
            // Try to match channel-version pattern: alpha-v6.1, prod-v2.0
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(alpha|debug|prod|beta|braveheart)-v?(\\d+\\.\\d+(?:\\.\\d+)?)")
                    .matcher(apkName);
            if (m.find()) return m.group(1) + "-v" + m.group(2);

            // Fallback: just version number
            m = java.util.regex.Pattern.compile("v?(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(apkName);
            if (m.find()) return "v" + m.group(1);
        }
        return "unknown";
    }

    /**
     * Canonicalize an extractVersion() result to the "<channel>-v<semver>" form
     * the persisted-label shape guard (persistedGithubVersion) trusts. If the
     * extracted label already carries a channel prefix it's returned as-is;
     * if it's a bare "v<semver>" (APK filename lacked the channel) we prepend
     * the KNOWN install channel so the value we persist isn't silently rejected
     * on read (which would revert About/web to the BuildConfig identity). An
     * unparseable "unknown" stays "unknown".
     */
    static String canonicalVersionLabel(String extracted, String channel) {
        if (extracted == null || extracted.isEmpty() || "unknown".equals(extracted)) {
            return extracted;
        }
        if (channelOfLabel(extracted) != null) return extracted;          // already <channel>-v...
        if (channel == null || channel.isEmpty()) return extracted;       // no channel to add
        // bare "v26.1" (or "26.1") → "<channel>-v26.1"
        String num = numericVersion(extracted);
        return num.isEmpty() ? extracted : channel + "-v" + num;
    }

    /**
     * Resolve a canonical "<channel>-v<semver>" label for a release, trying
     * progressively weaker sources so a digit-less APK filename never freezes
     * the displayed version at "unknown" (which then can't be persisted, so the
     * baseline advances while About/toast show a stale older label forever).
     *
     * Order: APK asset filename → release {@code name} → release {@code tag_name}.
     * The first that yields a parseable dotted version wins. Falls through to
     * "unknown" only when NONE carry a version (truly unlabelled release) —
     * callers keep their existing unknown-sentinel handling for that genuine edge.
     *
     * The release {@code body} is deliberately NOT consulted: it's free-text
     * release notes, and extractVersion's bare-version regex would happily match
     * a number inside prose ("fixes regression from v25.1") and surface a WRONG
     * display label. The structured fields (filename / name / tag_name) cover
     * every well-formed release; a release missing a version in all three is
     * malformed and correctly stays "unknown".
     */
    static String resolveRemoteLabel(JSONObject release, String apkName, String channel) {
        String label = canonicalVersionLabel(extractVersion(apkName), channel);
        if (!"unknown".equals(label)) return label;
        if (release != null) {
            String[] fallbacks = {
                    release.optString("name", ""),
                    release.optString("tag_name", "")
            };
            for (String src : fallbacks) {
                if (src == null || src.isEmpty()) continue;
                String cand = canonicalVersionLabel(extractVersion(src), channel);
                if (!"unknown".equals(cand)) return cand;
            }
        }
        return "unknown";
    }

    /**
     * Channel a display label belongs to ("alpha-v25.4" → "alpha",
     * "braveheart-v26.0" → "braveheart"), or null when the label carries no
     * channel prefix (bare "v6.1", "unknown", or the "Manually Installed"
     * fallback). Used by checkForUpdate to tell a CHANNEL SWITCH apart from a
     * genuine first run — a null here means "can't tell", so we fall through to
     * the safe (suppress-and-seed) first-run path rather than risk a spurious
     * prompt.
     */
    public static String channelOfLabel(String label) {
        if (label == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(alpha|debug|prod|beta|braveheart)-v")
                .matcher(label);
        return m.find() ? m.group(1) : null;
    }

    static boolean isNewerVersion(String local, String remote) {
        try {
            String[] lp = local.split("\\.");
            String[] rp = remote.split("\\.");
            int len = Math.max(lp.length, rp.length);
            for (int i = 0; i < len; i++) {
                int l = i < lp.length ? Integer.parseInt(lp[i].replaceAll("[^0-9]", "")) : 0;
                int r = i < rp.length ? Integer.parseInt(rp[i].replaceAll("[^0-9]", "")) : 0;
                if (r > l) return true;
                if (r < l) return false;
            }
            return false;
        } catch (Exception e) {
            return !local.equals(remote);
        }
    }

    /**
     * Resolve the active update channel for THIS process.
     *
     * forceReload() before the read is mandatory: the web channel toggle is
     * written by the daemon (UID 2000) but checkForUpdate / listVersions may
     * run in the app process (UID 10xxx) and vice-versa, and the in-memory
     * config is per-UID. Falls back to the build-time seed
     * ({@link BuildConfig#UPDATE_CHANNEL}, "alpha") on any failure so a
     * config read error can never strand the updater with no channel.
     */
    private String resolveChannel() {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            String ch = com.overdrive.app.config.UnifiedConfigManager.getUpdateChannel();
            if (ch != null && !ch.isEmpty()) return ch;
        } catch (Throwable t) {
            Log.w(TAG, "resolveChannel fell back to BuildConfig: " + t.getMessage());
        }
        return BuildConfig.UPDATE_CHANNEL;
    }

    /**
     * One-time, idempotent: seed the per-channel "alpha" baseline from the
     * legacy unsuffixed baseline so a current user who updated to this build
     * via the bare "alpha" tag keeps their detection baseline. No-op for any
     * channel other than alpha (braveheart has no legacy baseline — its first
     * check hits the empty-baseline seed branch and stores a fresh one).
     *
     * SharedPreferences half runs in-process (both UIDs). The filesystem half
     * routes through runShell (the app UID cannot write /data/local/tmp
     * directly) and only copies when the legacy file exists AND the
     * per-channel file does not, so a re-run never clobbers a live baseline.
     */
    private void migrateBaseline(String channel) {
        if (!CHANNEL_ALPHA.equals(channel)) return;
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String perChannelKey = prefKeyForChannel(channel);
            if (!prefs.getString(perChannelKey, "").isEmpty()) return; // already migrated
            String legacy = prefs.getString(PREF_LAST_UPDATE_TIME, "");
            if (!legacy.isEmpty()) {
                prefs.edit().putString(perChannelKey, legacy).commit();
            }
            final String src = UPDATE_TIMESTAMP_FILE;
            final String dst = timestampFileForChannel(channel);
            runShell("[ -f " + src + " ] && [ ! -f " + dst + " ] && cp " + src + " " + dst
                            + " && chmod 644 " + dst + " 2>/dev/null; echo done",
                    new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                        @Override public void onLog(String m) {}
                        @Override public void onLaunched() {}
                        @Override public void onError(String e) {}
                    });
        } catch (Exception ignored) {}
    }

    /**
     * Per-channel DEVICE-CLOCK install-time baseline key. Stores the app's
     * {@code PackageManager.lastUpdateTime} (a device-monotonic-ish ms value)
     * captured the last time we recorded an update baseline for this channel.
     * Used to detect an OUT-OF-BAND reinstall (Studio/manual sideload) WITHOUT
     * comparing the device clock against GitHub's server clock — see
     * checkForUpdate. SharedPreferences-only (per-UID): the app process gets the
     * full correct behaviour; a daemon-process check that never sees the stamp
     * simply degrades to "offer the sideloaded build once" — it can NEVER
     * falsely suppress a genuine update.
     */
    private static String installTimePrefKey(String channel) {
        return "install_time_baseline_" + channel;
    }

    /** Read the per-channel device-clock install-time baseline (0 if unset). */
    private static long getInstallTimeBaseline(Context ctx, String channel) {
        if (ctx == null) return 0L;
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(installTimePrefKey(channel), 0L);
    }

    /** Persist the per-channel device-clock install-time baseline. */
    private static void saveInstallTimeBaseline(Context ctx, String channel, long t) {
        if (ctx == null || t <= 0) return;
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(installTimePrefKey(channel), t).commit();
    }

    private String getLastUpdateTimestamp(String channel) {
        String prefKey = prefKeyForChannel(channel);
        String tsFile = timestampFileForChannel(channel);

        // FILE-FIRST — the world-readable /data/local/tmp file is the single
        // cross-UID source of truth, exactly like persistedGithubVersion() does
        // for the display label. The daemon (UID 2000) owns the install and
        // advances this baseline; the app process (UID 10xxx) runs its OWN
        // MainActivity update check with its OWN per-UID SharedPreferences.
        //
        // The old order (SP-first, file only when SP empty) meant that after a
        // daemon-side install the app's stale-but-non-empty SP won, the freshly
        // advanced file was never consulted, apkUpdated stayed true, and the
        // popup re-offered the just-installed version on every check — forever.
        // Every path that writes SP also writes the file (first-run seed +
        // install both save both), so the file is always at least as current as
        // any per-UID SP. Read it first and reconcile SP from it.
        try {
            File f = new File(tsFile);
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                String ts = r.readLine();
                r.close();
                if (ts != null && !ts.isEmpty()) {
                    // Reconcile this process's SP cache so a same-process
                    // repeat read is fast and the two never diverge.
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(prefKey, ts).apply();
                    return ts;
                }
            }
        } catch (Exception ignored) {}

        // File absent/unreadable — fall back to this process's SP cache. This
        // covers the pre-first-install state and any ROM where the app UID
        // can't read the file; in both cases SP-only degrades to per-UID
        // detection, which is still correct within a single process.
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(prefKey, "");
    }


    private void saveLastUpdateTimestamp(String channel, String timestamp) {
        if (timestamp == null) return;
        String prefKey = prefKeyForChannel(channel);
        final String tsFile = timestampFileForChannel(channel);
        // Use commit() (synchronous) — process may be killed right after
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(prefKey, timestamp).commit();
        // Also save to filesystem via ADB shell (survives reinstall, app can't write /data/local/tmp directly).
        // isoSafe (NOT shellSafe) — this file is read back byte-for-byte and
        // compared via .equals() against the GitHub updated_at, so the colons
        // MUST survive. isoSafe emits a valid ISO instant verbatim (no shell
        // metachars) or "" if malformed. The SP value at L1645 is already raw,
        // so SP and file stay byte-identical for a valid timestamp.
        String safeTs = isoSafe(timestamp);
        if (safeTs.isEmpty()) return; // don't write a malformed/empty baseline file
        try {
            // chmod 644 so the OTHER UID can read it cross-process. The daemon
            // (UID 2000) owns the install and advances this baseline, but the
            // app process (UID 10xxx) runs its own MainActivity update check
            // and reads this file as the shared source of truth (see
            // getLastUpdateTimestamp). Without the chmod the daemon's write
            // lands mode 0600 (unreadable by the app UID), so the app keeps
            // reading its own stale per-UID SharedPreferences baseline and
            // re-offers the just-installed version forever. Mirrors the
            // VERSION_FILE writes, which chmod 644 for the same reason.
            runShell("echo '" + safeTs + "' > " + tsFile
                            + "; chmod 644 " + tsFile + " 2>/dev/null",
                    new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String error) {
                    Log.w(TAG, "Failed to save timestamp to file: " + error);
                }
            });
        } catch (Exception ignored) {}
    }

    /**
     * Persist version string to filesystem so the daemon process can read it.
     * SharedPreferences are per-process and may not be accessible from the daemon.
     */
    private void persistVersionToFile(String version) {
        // Reject empty AND the "unknown" sentinel — this is the single
        // chokepoint enforcing the "only canonical <channel>-v<semver> labels
        // get persisted" invariant. Writing "unknown" would clobber a
        // previously-valid label with a value the read side rejects anyway
        // (channelOfLabel("unknown")==null), silently degrading same-tag-
        // republish tracking. Matches the IPC-hint + rollback write guards.
        if (version == null || version.isEmpty() || "unknown".equals(version)) return;
        try {
            // shellSafe: version flows into a single-quoted echo; scrub a stray
            // quote/metachar (extractVersion output is already constrained, but
            // this is the value-write twin of the detached-script path).
            // chmod 644: the file is read CROSS-UID — written by ONE UID (daemon
            // 2000 or app 10xxx) and read by the OTHER for the version display.
            // Without world-read the reader EACCEs, readVersionFile() returns
            // "", and getDisplayVersion falls back to the BuildConfig identity
            // (correct, but loses the persisted GitHub label for same-tag
            // republishes). Same chmod precedent as the .byd_device_id file.
            runShell("echo '" + shellSafe(version) + "' > " + VERSION_FILE
                            + "; chmod 644 " + VERSION_FILE + " 2>/dev/null",
                    new com.overdrive.app.launcher.AdbDaemonLauncher.LaunchCallback() {
                @Override public void onLog(String m) {}
                @Override public void onLaunched() {}
                @Override public void onError(String error) {
                    Log.w(TAG, "Failed to save version to file: " + error);
                }
            });
        } catch (Exception ignored) {}
    }

    private void postError(UpdateCallback cb, String msg) {
        runCallback(() -> cb.onError(msg));
    }
    private void postInstallError(InstallCallback cb, String msg) {
        runCallback(() -> cb.onError(msg));
    }
    private void postProgress(InstallCallback cb, String msg) {
        runCallback(() -> cb.onProgress(msg));
    }

    /**
     * Check if app was just updated and return the version string.
     * Clears the flag after reading so it only shows once.
     *
     * Callers MUST call {@link #consumeFailedUpdateError(Context)} first.
     * That method clears PREF_JUST_UPDATED on a failed install (the flag is
     * set before `runDetachedInstall` so it's true on BOTH success and
     * failure paths; the progress JSON is the authoritative outcome
     * signal). After that, this method only returns a non-null version
     * when the install actually succeeded.
     */
    public static String consumeJustUpdatedVersion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_JUST_UPDATED, false)) {
            String version = prefs.getString(PREF_UPDATED_VERSION, "");
            // Only clear the flag, keep the version for display
            prefs.edit()
                    .putBoolean(PREF_JUST_UPDATED, false)
                    .apply();
            return version;
        }
        return null;
    }

    /**
     * Read and clear a failed-install error written by the daemon-side
     * install script. Returns the error message (e.g. "Failure
     * [INSTALL_PARSE_FAILED_NO_CERTIFICATES]") when the script's
     * `pm install` exited non-zero, or null when there's no failure to
     * report. Called on app launch — together with
     * {@link #consumeJustUpdatedVersion(Context)} — so the user sees a
     * concrete error toast instead of a misleading success message or
     * silence.
     *
     * Also clears PREF_JUST_UPDATED. The flag is set BEFORE the install
     * script runs (at line 471 in downloadAndInstall) so it's "true" on
     * BOTH success and failure paths; we use the progress JSON as the
     * authoritative success/failure signal. If we left the flag set on
     * failure, the immediately-following consumeJustUpdatedVersion call
     * would see PREF_JUST_UPDATED=true with no failure marker (because we
     * deleted it here) and would fire the misleading "Updated to vX" toast
     * alongside the error toast.
     */
    public static String consumeFailedUpdateError(Context context) {
        if (!hasFailedUpdateMarker()) return null;
        String err = null;
        String priorUpdateTs = null;
        String priorUpdateChannel = null;
        String priorDisplayVersion = null;
        long recordTs = 0;
        try {
            java.io.File f = new java.io.File("/data/local/tmp/overdrive_update_progress.json");
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject j = new JSONObject(sb.toString());
            err = j.optString("error", null);
            if (err == null || err.isEmpty()) err = j.optString("message", "Install failed");
            priorUpdateTs = j.optString("priorUpdateTs", null);
            priorUpdateChannel = j.optString("priorUpdateChannel", null);
            priorDisplayVersion = j.optString("priorDisplayVersion", null);
            recordTs = j.optLong("ts", 0);
        } catch (Exception ignored) {}
        // One-shot guard. The phase=error record is deliberately LEFT in place
        // below (so the web's reconnect re-read can observe it after the daemon
        // restarts — see the no-delete note further down), which means a later
        // NORMAL launch's onCreate consume call would otherwise re-surface the
        // same stale failure on every launch until the next install overwrites
        // it. Gate on the record's `ts`: if we've already surfaced this exact
        // failure, return null (no re-toast). A retry that fails again writes a
        // fresh record with a new `ts`, so this re-arms naturally. The marker
        // lives in SharedPreferences (always app-writable), NOT the progress
        // JSON (a UID-2000 0644 file the app process can't truncate).
        SharedPreferences guardPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (recordTs > 0 && guardPrefs.getLong(PREF_LAST_CONSUMED_FAILURE_TS, 0) == recordTs) {
            // Already surfaced this failure. Still clear the just-updated flag so
            // a (now impossible, but defensive) success toast can't fire, then
            // bail without re-toasting.
            guardPrefs.edit().putBoolean(PREF_JUST_UPDATED, false).apply();
            return null;
        }
        if (recordTs > 0) {
            guardPrefs.edit().putLong(PREF_LAST_CONSUMED_FAILURE_TS, recordTs).apply();
        }
        // Clear the just-updated flag and the post-update sentinel so the next
        // launch is clean. The progress JSON itself is intentionally KEPT (see
        // the no-delete note at the end of this method); the next retry's
        // writeProgress("queued") overwrites it.
        //
        // Roll back the PER-CHANNEL baseline so the failed-install retry path
        // sees the correct baseline. Without this, the daemon-process
        // detached install advances the baseline before pm install runs;
        // on failure the baseline is still pointing at the version that
        // didn't land, and checkForUpdate reports "no update available"
        // — user is silently stuck. Channel comes from the progress JSON the
        // install script embedded; fall back to the build seed for records
        // written before this field existed.
        if (priorUpdateChannel == null || priorUpdateChannel.isEmpty()) {
            priorUpdateChannel = BuildConfig.UPDATE_CHANNEL;
        }
        try {
            android.content.SharedPreferences.Editor e =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_JUST_UPDATED, false);
            // Restore PREF_UPDATED_VERSION (display label) to the prior build.
            // Only act when the progress record actually CARRIES prior-state
            // (priorDisplayVersion key present == the detached daemon path,
            // which advanced the label before pm install). A sync-path failure
            // record has NO prior* fields because the sync path already rolled
            // back PREF_UPDATED_VERSION + VERSION_FILE inline — so a null here
            // means "already handled", and we must NOT remove() (that would wipe
            // the label the sync path just restored). Within a carrying record:
            // a real prior label is restored; the fallback/empty sentinel means
            // "no prior real build" → remove.
            if (priorDisplayVersion != null) {
                if (!priorDisplayVersion.isEmpty()
                        && !DISPLAY_VERSION_FALLBACK.equals(priorDisplayVersion)) {
                    e.putString(PREF_UPDATED_VERSION, priorDisplayVersion);
                } else {
                    e.remove(PREF_UPDATED_VERSION);
                }
            }
            // Empty string is the legitimate "no prior install" sentinel
            // (saveLastUpdateTimestamp always writes a value after first
            // success). Restore even if empty so the field reverts to the
            // exact pre-attempt state. The SharedPreferences half is the
            // load-bearing rollback (getLastUpdateTimestamp reads it before
            // the /data/local/tmp file); the daemon re-writes the file on its
            // next successful check.
            if (priorUpdateTs != null) {
                e.putString(prefKeyForChannel(priorUpdateChannel), priorUpdateTs);
            }
            e.apply();
        } catch (Exception ignored) {}
        // Do NOT delete the phase=error progress record here. The web runs its
        // reconnect re-read of /api/update/progress only AFTER the camera daemon
        // is back online (it polls /status first), and on a post-kill failure
        // the relaunched MainActivity calls this method BEFORE it restarts the
        // daemon (showPostUpdateToasts runs before startDaemons.run()). Deleting
        // the record here would guarantee the web's re-read sees the idle
        // sentinel and falls through to a false "Updated to X". Leaving the
        // terminal phase=error record in place lets the web surface the real
        // failure (handleProgress treats phase=error as terminal and never ages
        // it out); the PREF_LAST_CONSUMED_FAILURE_TS guard above stops the app
        // from re-toasting it, and the next install's writeProgress("queued")
        // overwrites it. (This app-process unlink was also a no-op in practice
        // — the file is a UID-2000 0644 entry in a sticky shell-owned dir the
        // app UID can neither truncate nor unlink.)
        try { new java.io.File(POST_UPDATE_FILE).delete(); } catch (Exception ignored) {}
        return err;
    }

    /**
     * True when the progress JSON exists and reports phase=error. Used as a
     * gate so consumeJustUpdatedVersion doesn't toast "Updated to vX" for an
     * install that never landed.
     */
    private static boolean hasFailedUpdateMarker() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/overdrive_update_progress.json");
            if (!f.exists()) return false;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f)))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString()).optString("phase", "").equals("error");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fetch the latest published version label for the current channel from
     * GitHub Releases (best-effort, non-blocking). Returns the same label
     * format as the on-device "display version" (e.g. "alpha-v6.1") so the
     * UI can swap one for the other without further parsing.
     *
     * Used by the Settings → About surface so the user sees the truth-source
     * version (what's published) rather than what was last installed locally.
     * Falls back through {@link #onError} when offline / proxy down.
     */
    public interface RemoteVersionCallback {
        void onResult(String version);
        void onError(String error);
    }

    public void fetchLatestReleaseVersion(RemoteVersionCallback callback) {
        // The published label for the resolved channel. For alpha this is the
        // bare rolling-head tag (the legacy "alpha" release); for braveheart
        // it's the "braveheart" tag. Both expose a single .apk whose name
        // extractVersion parses into the display label.
        String channel = resolveChannel();
        if (channel == null || channel.isEmpty()) {
            runCallback(() -> callback.onError("No channel configured"));
            return;
        }
        executor.execute(() -> {
            try {
                String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO +
                        "/releases/tags/" + channel;

                OkHttpClient client = buildClient(10, 10);
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();

                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String err = "GitHub API HTTP " + response.code();
                        runCallback(() -> callback.onError(err));
                        return;
                    }
                    JSONObject release = new JSONObject(response.body().string());
                    JSONArray assets = release.optJSONArray("assets");
                    String label = null;
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.endsWith(".apk")) {
                                label = extractVersion(name);
                                break;
                            }
                        }
                    }
                    if (label == null || label.isEmpty() || "unknown".equals(label)) {
                        // Fallback to the release tag_name when the asset name
                        // doesn't expose a parseable version.
                        label = release.optString("tag_name", "");
                    }
                    final String result = label;
                    runCallback(() -> {
                        if (result == null || result.isEmpty()) {
                            callback.onError("No version in release");
                        } else {
                            callback.onResult(result);
                        }
                    });
                }
            } catch (Exception e) {
                String err = e.getMessage() != null ? e.getMessage() : "Unknown error";
                runCallback(() -> callback.onError(err));
            }
        });
    }

    // ==================== Alpha catalog (pick-any) ====================

    /** One selectable version in the alpha archive. */
    public static class VersionEntry {
        public final String version;       // display label, e.g. "alpha-v25.4"
        public final String tag;           // GitHub tag_name, e.g. "alpha-v25.4" or "alpha"
        public final String downloadUrl;   // asset browser_download_url
        public final String updatedAt;     // asset updated_at (ISO)
        public final String publishedAt;   // release published_at (ISO)
        public final String releaseNotes;  // capped release body
        public final String relation;      // "current" | "newer" | "older" | "unknown"

        VersionEntry(String version, String tag, String downloadUrl, String updatedAt,
                     String publishedAt, String releaseNotes, String relation) {
            this.version = version;
            this.tag = tag;
            this.downloadUrl = downloadUrl;
            this.updatedAt = updatedAt;
            this.publishedAt = publishedAt;
            this.releaseNotes = releaseNotes;
            this.relation = relation;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("version", version);
                o.put("tag", tag);
                o.put("downloadUrl", downloadUrl);
                o.put("updatedAt", updatedAt != null ? updatedAt : "");
                o.put("publishedAt", publishedAt != null ? publishedAt : "");
                o.put("releaseNotes", releaseNotes != null ? releaseNotes : "");
                o.put("relation", relation);
            } catch (Exception ignored) {}
            return o;
        }
    }

    public interface VersionListCallback {
        void onResult(java.util.List<VersionEntry> versions, String currentVersion);
        void onError(String error);
    }

    private static final int RELEASE_NOTES_CAP = 4096;

    /**
     * Enumerate the alpha archive: one immutable GitHub release per version
     * (tag {@code alpha-v<semver>}) plus the retained legacy {@code alpha}
     * rolling head. Newest-first, de-duped by parsed version so the legacy
     * "alpha" entry doesn't double up with a same-version "alpha-v*" release.
     *
     * This is alpha's SOLE detection entry point — {@link #checkForUpdate}
     * stays braveheart-only, so its first-run/fresh-deploy suppression keeps
     * exactly one caller. Selection is explicit (see {@link #prepareInstall});
     * the {@code relation} field is for LABELLING only and never gates a
     * prompt.
     *
     * Runs on the shared executor (network). Best-effort: a release with no
     * APK asset (notes-only/draft) is skipped rather than erroring.
     */
    public void listVersions(VersionListCallback callback) {
        executor.execute(() -> {
            try {
                String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO +
                        "/releases?per_page=100";
                OkHttpClient client = buildClient(15, 15);
                Request request = new Request.Builder()
                        .url(apiUrl)
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        String err = "GitHub API HTTP " + response.code();
                        runCallback(() -> callback.onError(err));
                        return;
                    }
                    JSONArray releases = new JSONArray(response.body().string());
                    String currentVersion = getDisplayVersion(context);

                    // De-dupe by parsed numeric version; prefer an explicit
                    // alpha-v* release over the legacy "alpha" rolling head
                    // when both resolve to the same version.
                    java.util.LinkedHashMap<String, VersionEntry> byVersion =
                            new java.util.LinkedHashMap<>();

                    for (int i = 0; i < releases.length(); i++) {
                        JSONObject rel = releases.optJSONObject(i);
                        if (rel == null) continue;
                        String tag = rel.optString("tag_name", "");
                        boolean isAlphaArchive = tag.startsWith("alpha-v");
                        boolean isLegacyAlpha = tag.equals("alpha");
                        if (!isAlphaArchive && !isLegacyAlpha) continue;

                        String[] apk = firstApkAsset(rel.optJSONArray("assets"));
                        if (apk == null) continue; // notes-only / draft — skip

                        String label = extractVersion(apk[1]);
                        if (label == null || label.isEmpty() || "unknown".equals(label)) {
                            label = tag;
                        }
                        // Numeric key for de-dupe (strip channel prefix).
                        String numeric = numericVersion(label);

                        String notes = rel.optString("body", "");
                        if (notes.length() > RELEASE_NOTES_CAP) {
                            notes = notes.substring(0, RELEASE_NOTES_CAP);
                        }
                        String relation = relationTo(currentVersion, label, numeric);
                        VersionEntry entry = new VersionEntry(
                                label, tag, apk[0], apk[2],
                                rel.optString("published_at", ""), notes, relation);

                        VersionEntry existing = byVersion.get(numeric);
                        // First wins, UNLESS the incumbent is the legacy bare
                        // "alpha" and this one is an explicit alpha-v* — prefer
                        // the immutable per-version release.
                        if (existing == null) {
                            byVersion.put(numeric, entry);
                        } else if (existing.tag.equals("alpha") && isAlphaArchive) {
                            byVersion.put(numeric, entry);
                        }
                    }

                    java.util.List<VersionEntry> out =
                            new java.util.ArrayList<>(byVersion.values());
                    // Newest-first by parsed semver (reuse isNewerVersion). A
                    // proper 3-way comparator (equal → 0) keeps the sort total
                    // and stable even if two entries ever share a numeric key.
                    java.util.Collections.sort(out, (a, b) -> {
                        String na = numericVersion(a.version), nb = numericVersion(b.version);
                        if (na.equals(nb)) return 0;
                        // isNewerVersion(local, remote) is true when remote>local.
                        // nb newer than na → a sorts AFTER b → newest first.
                        return isNewerVersion(na, nb) ? 1 : -1;
                    });

                    final java.util.List<VersionEntry> result = out;
                    final String cur = currentVersion;
                    runCallback(() -> callback.onResult(result, cur));
                }
            } catch (Exception e) {
                String err = e.getMessage() != null ? e.getMessage() : "Unknown error";
                runCallback(() -> callback.onError(err));
            }
        });
    }

    /**
     * Strip a channel prefix to the numeric version: "alpha-v25.4" → "25.4",
     * "braveheart-v26" → "26". Accepts a single token OR dotted form so a
     * non-dotted versionName ("26") still parses (isNewerVersion splits on "."
     * and zero-pads, so "26" vs "26.1" compares correctly) — this avoids
     * silently neutralizing every alpha catalog chip if a future release bumps
     * versionName to a bare integer. "26.0-rc1" parses to "26.0"; a bare word
     * yields "". relationTo() treats "" as "unknown" (neutral chip, never a
     * wrong "current"/"newer").
     */
    private static String numericVersion(String label) {
        if (label == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("v?(\\d+(?:\\.\\d+)*)").matcher(label);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Label-only relation of a catalog entry to the installed version.
     * Returns "unknown" when the installed version is unparseable (e.g. a
     * fresh sideload showing {@link #DISPLAY_VERSION_FALLBACK}) so the UI
     * shows a neutral chip rather than a misleading "older".
     */
    private static String relationTo(String installed, String label, String numeric) {
        if (installed == null || installed.isEmpty()
                || DISPLAY_VERSION_FALLBACK.equals(installed)) {
            return "unknown";
        }
        // listVersions only enumerates ALPHA entries. If the installed build is
        // NOT an alpha-channel label (e.g. "braveheart-v6.1" after a braveheart
        // install, then the user toggled to alpha and opened the catalog),
        // comparing only the dotted-numeric part would mislabel a same-number
        // alpha entry as "current"/"older". Channels are distinct builds, so
        // mark cross-channel as "unknown" (label-only; never gates anything).
        if (!installed.startsWith("alpha")) {
            return "unknown";
        }
        String instNum = numericVersion(installed);
        if (instNum.isEmpty()) return "unknown";
        if (instNum.equals(numeric)) return "current";
        return isNewerVersion(instNum, numeric) ? "newer" : "older";
    }

    /**
     * Resolve a specific alpha release tag SERVER-SIDE and seed the three
     * install fields ({@link #latestDownloadUrl}, {@link #remoteVersion},
     * {@link #remoteUpdatedAt}) so the EXISTING {@link #downloadAndInstall}
     * runs verbatim. Never trusts a client-supplied URL — the caller passes
     * only a tag, which we re-fetch and re-validate here.
     *
     * Runs SYNCHRONOUSLY on the calling thread (the API handler invokes this
     * inside its own latch, off the HTTP worker). Returns the resolved
     * version label on success, or throws on any failure so the caller can
     * surface a clean error before kicking the install.
     */
    public String prepareInstall(String tag) throws Exception {
        if (tag == null || tag.isEmpty()) {
            throw new IllegalArgumentException("No version tag");
        }
        // STRICT allowlist: bare "alpha" head or "alpha-v<semver>" only. The old
        // startsWith("alpha-v") prefix check left the suffix unbounded, so a tag
        // like alpha-v';reboot;' passed — and since it has no dotted-numeric
        // version, extractVersion returned "unknown" and the raw tag became
        // remoteVersion, which then flowed verbatim into `echo '<v>' > FILE`
        // (quote breakout) and a printf format string in the detached install
        // script. A strict regex closes that taint at the entry point.
        if (!isValidAlphaTag(tag)) {
            throw new IllegalArgumentException("Unsupported version tag: " + tag);
        }
        String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO +
                "/releases/tags/" + tag;
        OkHttpClient client = buildClient(15, 15);
        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new java.io.IOException("GitHub API HTTP " + response.code());
            }
            JSONObject release = new JSONObject(response.body().string());
            String[] apk = firstApkAsset(release.optJSONArray("assets"));
            if (apk == null) {
                throw new java.io.IOException("No APK in release " + tag);
            }
            releaseNotes = release.optString("body", "");
            latestDownloadUrl = apk[0];
            remoteUpdatedAt = apk[2];
            // Canonicalize to "alpha-v<semver>" (this path is alpha-only) so a
            // filename missing the channel prefix still persists a label the
            // read-side shape guard trusts. resolveRemoteLabel walks
            // filename → release name → tag_name → body before giving up.
            remoteVersion = resolveRemoteLabel(release, apk[1], CHANNEL_ALPHA);
            // Final guard: try the requested TAG explicitly (e.g. "alpha-v6.1").
            // A bare "alpha" tag canonicalizes to "unknown" and STAYS "unknown" —
            // the established no-persist sentinel — rather than writing junk
            // "alpha" that the read-side shape guard would just reject.
            if ("unknown".equals(remoteVersion)) {
                remoteVersion = canonicalVersionLabel(extractVersion(tag), CHANNEL_ALPHA);
            }
            // The catalog only surfaces alpha tags (alpha / alpha-v*), so a
            // prepared install always belongs to the alpha channel — bind it
            // here so the baseline keys on alpha regardless of the live channel.
            pendingChannel = CHANNEL_ALPHA;
            Log.i(TAG, "prepareInstall resolved " + tag + " → " + remoteVersion
                    + " (" + latestDownloadUrl + ")");
            return remoteVersion;
        }
    }

    /**
     * Last-resort sentinel for {@link #getInstalledVersion()} — only returned
     * if BuildConfig.VERSION_NAME is somehow empty (never in a normal build,
     * where versionName is a real per-release value bumped in build.gradle.kts).
     * Version identity is now BuildConfig-derived, so this fallback is
     * effectively unreachable for a built APK; it's kept as a defensive default.
     */
    public static final String DISPLAY_VERSION_FALLBACK = "Manually Installed";

    /**
     * The build's TRUE self-identity, derived from BuildConfig baked into the
     * running binary: "<channel>-v<versionName>" (e.g. "alpha-v26.0",
     * "braveheart-v26.0"). This is the only version string that is ALWAYS
     * correct for the build actually running — unlike the persisted
     * VERSION_FILE / PREF_UPDATED_VERSION, which are written only by the
     * in-app updater and survive reinstall/sideload/cross-channel flashes, so
     * they go STALE (the "About shows alpha-v26 on a braveheart build" +
     * "check says already-updated" bugs). versionName must be bumped per
     * release in build.gradle.kts.
     */
    public static String getInstalledVersion() {
        String channel = BuildConfig.UPDATE_CHANNEL;
        String ver = BuildConfig.VERSION_NAME;
        if (ver == null || ver.isEmpty()) return DISPLAY_VERSION_FALLBACK;
        if (channel == null || channel.isEmpty()) return "v" + ver;
        return channel + "-v" + ver;
    }

    /**
     * The GitHub release label that was actually downloaded+installed, as the
     * update flow recorded it (PREF_UPDATED_VERSION for the app process,
     * VERSION_FILE for the daemon — both written from {@link #extractVersion}
     * of the installed APK's filename, e.g. "alpha-v26.1"). This is the version
     * the USER cares about: when an APK is re-published on the same release tag
     * without bumping gradle's versionName, BuildConfig.VERSION_NAME goes stale
     * but this label tracks the real GitHub build. Returns "" when nothing has
     * been installed via the in-app updater yet (fresh sideload / Studio run).
     *
     * Staleness guard: VERSION_FILE / PREF_UPDATED_VERSION survive a
     * cross-channel flash, so we ONLY trust the persisted label when its channel
     * prefix matches the running build's channel (BuildConfig.UPDATE_CHANNEL).
     * On a mismatch (ran alpha, persisted label is braveheart, or vice-versa)
     * the persisted value is stale-for-this-build and we fall back to
     * BuildConfig — this is exactly the "About showed the wrong channel after a
     * cross-channel install" case the BuildConfig-only path was guarding.
     */
    private static String persistedGithubVersion(Context context) {
        // SINGLE cross-process source of truth: the world-readable FILE
        // /data/local/tmp/overdrive_version, written by EVERY install path
        // (chmod 644) regardless of which process ran it. We deliberately do
        // NOT consult per-UID SharedPreferences here: PREF_UPDATED_VERSION is
        // per-process, so a daemon-run install (web/Telegram) updates the
        // daemon's prefs + the file but NOT the app's prefs — reading the app's
        // prefs is exactly what made the About row show a stale label while the
        // webapp was correct. File-only ⇒ app, daemon, web, Telegram, IPC all
        // resolve identically. If the file is absent/unreadable we fall back to
        // the BuildConfig identity (caller), never to a per-UID pref.
        // (context is unused now; kept for call-site compatibility.)
        String label = readVersionFile();
        if (label == null || label.isEmpty()
                || DISPLAY_VERSION_FALLBACK.equals(label)) {
            return "";
        }
        // SHAPE GUARD: only trust a label in the canonical "<channel>-v<semver>"
        // form. A malformed value left by an OLD build — e.g. the historical
        // "v26.0alpha" (version-then-channel suffix) — would otherwise leak
        // straight to the About row, because channelOfLabel() returns null for
        // it so the cross-channel guard below never trips. Anything that
        // doesn't parse as channel-prefix + dotted version is rejected → caller
        // falls back to the always-correct BuildConfig identity.
        String labelChannel = channelOfLabel(label);   // matches ^<channel>-v
        if (labelChannel == null || numericVersion(label).isEmpty()) {
            return "";   // malformed/legacy label — fall back to BuildConfig
        }
        // Only trust it for the channel we're actually running (a stale
        // cross-channel label must not leak through).
        String runningChannel = BuildConfig.UPDATE_CHANNEL;
        if (runningChannel != null && !runningChannel.isEmpty()
                && !labelChannel.equals(runningChannel)) {
            return "";   // stale cross-channel label — let the caller fall back
        }
        return label;
    }

    /** Read VERSION_FILE (the daemon-readable persisted GitHub version). "" if absent. */
    private static String readVersionFile() {
        try {
            File f = new File(VERSION_FILE);
            if (!f.exists()) return "";
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Get the display version string shown to the user (About screen, "up to
     * date" toast, post-update banner, web/Telegram status). Prefers the GitHub
     * release label actually installed ({@link #persistedGithubVersion}); falls
     * back to the BuildConfig identity ({@link #getInstalledVersion}) only when
     * nothing has been installed via the updater yet, or the persisted label is
     * a stale cross-channel value. This way the number the user sees always
     * matches the GitHub build they're on, even when versionName wasn't bumped.
     */
    public static String getDisplayVersion(Context context) {
        String github = persistedGithubVersion(context);
        return !github.isEmpty() ? github : getInstalledVersion();
    }

    /**
     * Context-free display version (daemon process). Same resolution as
     * {@link #getDisplayVersion}: prefer the persisted GitHub label (read from
     * VERSION_FILE since the daemon has no SharedPreferences), else BuildConfig.
     */
    public static String getDisplayVersionFromFile() {
        return getDisplayVersion(null);
    }
}
