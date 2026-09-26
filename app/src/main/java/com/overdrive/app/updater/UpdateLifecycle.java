package com.overdrive.app.updater;
import com.overdrive.app.util.ScratchPaths;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.overdrive.app.launcher.AdbDaemonLauncher;

import java.io.File;

/**
 * Coordinates the post-update launch path so the new app process always wins
 * over zombie daemons / watchdogs left behind by the previous install.
 *
 * The contract:
 *   - Old process (AppUpdater.stopAllDaemons) writes updateInProgressFile()
 *     and postUpdateFile() to /data/local/tmp before pm install.
 *   - New process (MainActivity) detects either sentinel + the post_update
 *     intent extra + PREF_JUST_UPDATED. If any is set, it runs hardResetDaemons
 *     before DaemonStartupManager so old daemons can't outlive the install.
 *   - hardResetDaemons clears the sentinels on completion.
 */
public final class UpdateLifecycle {

    private static final String TAG = "UpdateLifecycle";

    /**
     * Build a ps+awk+kill snippet for {@code pattern}, excluding the calling
     * shell's own PID via {@code $$}. Replaces every {@code pkill -9 -f
     * '<pattern>'} site — pkill -f matches the calling shell's argv on the
     * literal pattern and SIGKILLs it before subsequent commands run.
     */
    private static String psAwkKillLine(String pattern) {
        return "MY_PID=$$; ps -A -o PID,ARGS | grep -F '" + pattern + "' | grep -v grep "
            + "| awk '{print $1}' | while read pid; do "
            + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n";
    }

    private static String diLink5CaptureKillScript() {
        try {
            com.overdrive.app.camera.dilink5.DiLink5Platform
                    .refreshActiveMode();
            if (!com.overdrive.app.camera.dilink5.DiLink5Platform
                    .isSelected()) {
                return "";
            }
        } catch (Throwable ignored) {
            return "";
        }
        return psAwkKillLine("fast_cam_capture")
                + psAwkKillLine("qcarcam_test")
                + "killall -9 fast_cam_capture 2>/dev/null\n"
                + "killall -9 qcarcam_test 2>/dev/null\n";
    }

    public static String updateInProgressFile() {
        return ScratchPaths.path("overdrive_update_in_progress");
    }
    public static String postUpdateFile() {
        return ScratchPaths.path("overdrive_post_update");
    }
    /**
     * One-shot marker read by TelegramBotDaemon's notifyTunnel handler so the
     * first post-update tunnel-URL message can include the new version (and a
     * "this is why your URL changed" hint) instead of the generic "URL changed"
     * copy. Contains the version string (e.g. "alpha-v11.4"). Deleted by the
     * daemon after consuming.
     */
    public static String telegramPostUpdateHintFile() {
        return ScratchPaths.path("overdrive_post_update_pending_telegram");
    }

    public static String telegramInstallFailedHintFile() {
        return ScratchPaths.path("overdrive_install_failed_pending_telegram");
    }

    /** @deprecated Use {@link #telegramPostUpdateHintFile()} */
    public static String TELEGRAM_POST_UPDATE_HINT_FILE() {
        return telegramPostUpdateHintFile();
    }

    /** @deprecated Use {@link #telegramInstallFailedHintFile()} */
    public static String TELEGRAM_INSTALL_FAILED_HINT_FILE() {
        return telegramInstallFailedHintFile();
    }
    public static final String EXTRA_POST_UPDATE = "post_update";

    private UpdateLifecycle() {}

    /** Detects whether this launch came right after a package install. */
    public static boolean isPostUpdateLaunch(Context ctx, Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_POST_UPDATE, false)) return true;
        if (new File(postUpdateFile()).exists()) return true;
        if (new File(updateInProgressFile()).exists()) return true;
        try {
            return ctx.getSharedPreferences("app_updater", Context.MODE_PRIVATE)
                    .getBoolean("just_updated", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Hard-kill every known daemon + watchdog, wipe lock/sentinel files, then
     * invoke onComplete on the same thread that the underlying launcher uses.
     * Safe to call when no update is in progress — it's just a sweep.
     */
    public static void hardResetDaemons(Context ctx, Runnable onComplete) {
        Log.i(TAG, "post-update detected — hard-resetting daemons");
        long start = System.currentTimeMillis();
        String diLink5CaptureCleanup = diLink5CaptureKillScript();
        // We allocate a fresh AdbDaemonLauncher here because hardResetDaemons
        // is static and runs early in MainActivity.runDaemonStartup, before
        // the per-Activity DaemonStartupManager's launcher is necessarily
        // initialized. To avoid leaking the launcher's executor + nested
        // tunnel-poll scheduler thread, we explicitly call
        // closePersistentConnection() inside the completion paths.
        AdbDaemonLauncher launcher = new AdbDaemonLauncher(ctx);

        // Single script-via-tmp-file invocation — `executeShellScript` writes
        // the body to /data/local/tmp/<id>.sh and runs it. The running shell's
        // argv is `sh /data/local/tmp/<id>.sh`, so toybox `pkill -f` cannot
        // match the calling shell on a daemon pattern. This replaces the
        // earlier 3-phase split that was needed to defend against pkill
        // self-suicide on a `sh -c "..."` payload.
        //
        // Order within the script:
        //   1. Plant per-daemon disable sentinels (defense for any watchdog
        //      we might miss with the pkill — gate-1/gate-2 in the watchdog
        //      loop catches them on the next iteration).
        //   2. Remove watchdog scripts so the kernel can't re-exec them.
        //   3. pkill / killall cascade — daemon binaries first, then
        //      ancillary binaries (cloudflared/zrok/sing-box/tailscaled).
        //      No self-match risk now.
        //   4. Wait briefly for processes to settle, THEN remove lock files.
        //      Doing this AFTER the kills (rather than before) prevents the
        //      "phase-2 lockfile resurrection" race: previously phase 1 rm'd
        //      *_daemon.lock, then between phases the still-alive daemon
        //      wrote its PID back into the lock, then phase 2 pkilled it,
        //      leaving an orphaned lock that the new daemon refused to
        //      overwrite.
        //   5. Clear per-daemon disable sentinels so the new MainActivity
        //      doesn't see them and refuse to start. Post-update sentinels
        //      (updateInProgressFile(), postUpdateFile()) are KEPT; the
        //      new process consumes them via isPostUpdateLaunch.
        // Plant disable sentinels ONLY for CORE daemons (camera + sentry +
        // acc-sentry). Machine-written markers are wiped again at the end —
        // the transient sentinel just keeps any watchdog we miss with the pkill
        // from re-exec'ing the daemon between our kill and the new process'
        // launch. OPTIONAL daemons (telegram / zrok / tailscale / singbox) are
        // deliberately NOT touched here: their disable sentinel is a DURABLE
        // user-stop signal that must PERSIST across an app update. Planting
        // (and then wiping) the telegram/zrok sentinels here would resurrect a
        // user-stopped tunnel/bot — and would also destroy the only cross-UID
        // record of a UI stop that AccSentryDaemon's ACC-on auto-start gate
        // relies on. Write-if-absent also preserves a manual CORE-daemon stop.
        String script =
                "[ -f " + ScratchPaths.path("camera_daemon.disabled") + " ] || " +
                "echo \"disabled by post-update reset at $(date)\" > " + ScratchPaths.path("camera_daemon.disabled") + "\n" +
                "chmod 666 " + ScratchPaths.path("camera_daemon.disabled") + " 2>/dev/null\n" +
                "[ -f " + ScratchPaths.path("sentry_daemon.disabled") + " ] || " +
                "echo \"disabled by post-update reset at $(date)\" > " + ScratchPaths.path("sentry_daemon.disabled") + "\n" +
                "chmod 666 " + ScratchPaths.path("sentry_daemon.disabled") + " 2>/dev/null\n" +
                "[ -f " + ScratchPaths.path("acc_sentry_daemon.disabled") + " ] || " +
                "echo \"disabled by post-update reset at $(date)\" > " + ScratchPaths.path("acc_sentry_daemon.disabled") + "\n" +
                "chmod 666 " + ScratchPaths.path("acc_sentry_daemon.disabled") + " 2>/dev/null\n" +
                "rm -f " + ScratchPaths.path("cam_watchdog.pid") + " 2>/dev/null\n" +
                "rm -f " + ScratchPaths.path("start_cam_daemon.sh") + " "
                + ScratchPaths.path("start_acc_sentry.sh") + " "
                + ScratchPaths.path("start_zrok.sh") + " "
                + ScratchPaths.path("start_telegram.sh") + " 2>/dev/null\n" +
                // ps+awk+kill cascade — single-source-of-truth process
                // names below. Each pattern walks /proc and SIGKILLs the
                // matching PIDs except the calling shell's own PID.
                psAwkKillLine("start_cam_daemon") +
                psAwkKillLine("start_acc_sentry") +
                psAwkKillLine("start_telegram") +
                psAwkKillLine("byd_cam_daemon") +
                diLink5CaptureCleanup +
                psAwkKillLine("cam_daemon") +
                psAwkKillLine("sentry_daemon") +
                psAwkKillLine("acc_sentry_daemon") +
                psAwkKillLine("telegram_bot_daemon") +
                psAwkKillLine("sentry_proxy") +
                psAwkKillLine("cloudflared") +
                psAwkKillLine("zrok") +
                psAwkKillLine("sing-box") +
                psAwkKillLine("tailscaled") +
                psAwkKillLine("fast_cam_capture") +
                psAwkKillLine("libfast_cam_capture") +
                "killall -9 cloudflared 2>/dev/null\n" +
                "killall -9 zrok 2>/dev/null\n" +
                "killall -9 tailscaled 2>/dev/null\n" +
                "killall -9 sing-box 2>/dev/null\n" +
                "killall -9 fast_cam_capture 2>/dev/null\n" +
                "pkill -9 -f fast_cam_capture 2>/dev/null\n" +
                // Brief settle so SIGKILL'd daemons release their lockfiles
                // before we rm the lock files. Without this delay, a daemon
                // mid-shutdown could still rewrite the lock between our
                // pkill and our rm.
                "sleep 1\n" +
                // Watchdog ownership locks intentionally do not match
                // *_daemon.lock. SIGKILL bypasses the watchdog EXIT trap, so
                // these must be removed explicitly after the old wrappers are
                // dead or the replacement watchdog will fail closed.
                "rm -rf " + ScratchPaths.path("cam_watchdog.lock") + " "
                + ScratchPaths.path("acc_sentry_watchdog.lock") + " 2>/dev/null\n" +
                "rm -f " + ScratchPaths.getDir() + "/*_daemon.lock 2>/dev/null\n" +
                // The detached install script has finished executing by the
                // time the new MainActivity runs this reset — remove it so it
                // doesn't linger in scratch (hygiene; it's rewritten via
                // atomic tmp+rename each install so it never blocks recovery).
                "rm -f " + ScratchPaths.path("overdrive_install.sh") + " 2>/dev/null\n" +
                // Clear only known machine-written CORE markers. A pre-existing
                // "disabled by ui/telegram" sentinel is durable manual intent
                // and must survive the update.
                // The post-update markers (updateInProgressFile() /
                // postUpdateFile()) survive — new process owns them.
                "for S in " + ScratchPaths.path("camera_daemon.disabled") + " " +
                ScratchPaths.path("sentry_daemon.disabled") + " " +
                ScratchPaths.path("acc_sentry_daemon.disabled") + "; do\n" +
                "  R=$(head -1 \"$S\" 2>/dev/null)\n" +
                "  case \"$R\" in " +
                "'disabled by post-update reset'*|'disabled for update'*|" +
                "'disabled by stopAllDaemons sweep'*|'disabled by killDaemon'*) " +
                "rm -f \"$S\" 2>/dev/null;; esac\n" +
                "done\n" +
                // Stale daemon-log cleanup: only when the NEWLY-INSTALLED build
                // does NOT capture logs (BuildConfig.LOG_CAPTURE is false — i.e.
                // a braveheart→alpha/stable downgrade). braveheart keeps file
                // logging on for every tag, and NOTHING ages those *.log files
                // out (DaemonLogger.cleanupOldLogs has no callers; the Kotlin
                // LogCleaner only touches the app cache dir), so after a
                // downgrade they'd linger forever. We must NOT run this on a
                // braveheart→braveheart update or we'd wipe the very logs the
                // user wants to send. Excludes the install log + sentinels.
                (com.overdrive.app.BuildConfig.LOG_CAPTURE ? "" :
                    "for lf in " + ScratchPaths.getDir() + "/*.log " + ScratchPaths.getDir() + "/*.log.[0-9]*; do " +
                    "case \"$lf\" in *overdrive_install.log) ;; *) rm -f \"$lf\" 2>/dev/null;; esac; " +
                    "done\n") +
                "rm -f " + updateInProgressFile() + " " + postUpdateFile() + " 2>/dev/null\n" +
                "echo done\n";

        launcher.executeShellScript(script, new AdbDaemonLauncher.LaunchCallback() {
            @Override public void onLog(String m) {}
            @Override public void onLaunched() {
                // releasePerInstanceResources() — NOT closePersistentConnection.
                // The latter closes the process-wide shared Dadb that the
                // long-lived daemonStartupManager.adbLauncher is concurrently
                // using; that would surface as spurious onError on its
                // in-flight tasks. We only own this launcher's executor
                // and tunnel-poll scheduler — release just those.
                try { launcher.releasePerInstanceResources(); } catch (Exception ignored) {}
                finishHardReset(onComplete, start, false);
            }
            @Override public void onError(String e) {
                Log.w(TAG, "hard reset error (continuing): " + e);
                try { launcher.releasePerInstanceResources(); } catch (Exception ignored) {}
                finishHardReset(onComplete, start, true);
            }
        });
    }

    private static void finishHardReset(Runnable onComplete, long start, boolean withError) {
        long ms = System.currentTimeMillis() - start;
        Log.i(TAG, "hard reset complete in " + ms + "ms" + (withError ? " (with warning)" : ""));
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        if (onComplete != null) onComplete.run();
    }
}
