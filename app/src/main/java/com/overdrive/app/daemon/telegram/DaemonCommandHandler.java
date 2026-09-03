package com.overdrive.app.daemon.telegram;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;
import java.util.Properties;
import com.overdrive.app.util.ScratchPaths;

/**
 * Handles /daemon commands for starting/stopping daemons.
 * 
 * Uses the same process names and kill approach as the UI daemon controllers.
 * 
 * User stops are persisted through the same cross-UID .disabled sentinels
 * used by the app UI. The properties file is kept only for legacy readers.
 */
public class DaemonCommandHandler implements TelegramCommandHandler {
    
    private static final String TAG = "DaemonCmd";
    private static final String STATE_FILE = ScratchPaths.path("daemon_telegram_state.properties");
    
    // Debounce duplicate commands
    private long lastCommandTime = 0;
    private String lastCommandKey = "";
    private static final long DEBOUNCE_MS = 3000;
    
    // Daemon definitions: name -> [processName, className, displayNameKey, startable]
    // startable: "yes" if can be started via app_process or shell, "no" if can't be started remotely
    private static final String[][] DAEMONS = {
        {"camera", "byd_cam_daemon", "CameraDaemon", "daemon_names.camera", "yes"},
        {"acc", "acc_sentry_daemon", "AccSentryDaemon", "daemon_names.acc_sentry", "yes"},
        {"sentry", "sentry_daemon", "SentryDaemon", "daemon_names.sentry", "yes"},
        {"telegram", "telegram_bot_daemon", "TelegramBotDaemon", "daemon_names.telegram", "yes"},
        {"cloudflared", "cloudflared", "shell", "daemon_names.cloudflare_tunnel", "yes"},
        {"zrok", "zrok", "shell", "daemon_names.zrok_tunnel", "yes"},
        {"tailscale", "tailscaled", "shell", "daemon_names.tailscale_tunnel", "yes"},
        {"singbox", "sing-box", "shell", "daemon_names.sing_box", "yes"},
    };
    
    private static final String AVAILABLE_DAEMONS = "camera, acc, sentry, cloudflared, zrok, tailscale, singbox";
    
    @Override
    public boolean canHandle(String command) {
        return "/daemon".equals(command);
    }
    
    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        if (args.length < 3) {
            ctx.sendMessage(chatId, ctx.tr("daemon.usage", AVAILABLE_DAEMONS));
            return;
        }
        
        String name = args[1].toLowerCase(Locale.ROOT);
        String action = args[2].toLowerCase(Locale.ROOT);
        
        // Debounce
        String cmdKey = name + ":" + action;
        long now = System.currentTimeMillis();
        if (cmdKey.equals(lastCommandKey) && (now - lastCommandTime) < DEBOUNCE_MS) {
            ctx.log("Ignoring duplicate command: " + cmdKey);
            return;
        }
        lastCommandKey = cmdKey;
        lastCommandTime = now;
        
        // Find daemon
        String[] daemon = findDaemon(name);
        if (daemon == null) {
            ctx.sendMessage(chatId, ctx.tr("daemon.unknown", name, AVAILABLE_DAEMONS));
            return;
        }
        
        String processName = daemon[1];
        String displayName = ctx.tr(daemon[3]);
        boolean isStartable = "yes".equals(daemon[4]);
        
        // Can't control telegram from telegram
        if ("telegram".equals(name)) {
            ctx.sendMessage(chatId, ctx.tr("daemon.cannot_control_telegram"));
            return;
        }
        
        ctx.log("Daemon command: " + displayName + " (" + processName + ") action=" + action);
        
        boolean isRunning = isDaemonRunning(processName, ctx);
        
        switch (action) {
            case "start":
                if (!isStartable) {
                    ctx.sendMessage(chatId, ctx.tr("daemon.must_start_from_app", displayName));
                } else {
                    // A manual start re-arms auto-management even if the
                    // process is already alive or this launch attempt fails.
                    String startSentinel = sentinelForProcess(processName);
                    if (startSentinel != null) {
                        ctx.execShell("rm -f " + startSentinel + " 2>/dev/null");
                    }
                    clearDaemonStoppedState(name);

                    // Cloudflared and Zrok are mutually exclusive. Starting one
                    // manually is the Telegram equivalent of enabling it in the
                    // UI, which durably disables the other tunnel even if its
                    // process happened to be down when this command arrived.
                    if ("cloudflared".equals(name)) {
                        ctx.log("Stopping Zrok (mutually exclusive with Cloudflared)");
                        stopDaemon("zrok", ctx);
                        saveDaemonState("zrok", false, ctx);
                    } else if ("zrok".equals(name)) {
                        ctx.log("Stopping Cloudflared (mutually exclusive with Zrok)");
                        stopDaemon("cloudflared", ctx);
                        saveDaemonState("cloudflared", false, ctx);
                    }

                    if (isRunning) {
                        saveDaemonState(name, true, ctx);
                        ctx.sendMessage(chatId, ctx.tr("daemon.already_running", displayName));
                        break;
                    }
                    
                    boolean ok;
                    if ("shell".equals(daemon[2])) {
                        // External binary - start via shell command
                        ok = startShellDaemon(name, ctx);
                    } else {
                        // Java daemon - start via app_process
                        ok = startDaemon(daemon[2], processName, ctx);
                    }
                    
                    if (ok) {
                        saveDaemonState(name, true, ctx);
                    }
                    ctx.sendMessage(chatId, ctx.tr(ok ? "daemon.started" : "daemon.start_failed", displayName));
                }
                break;
                
            case "stop":
                // Stop is idempotent. Even if the main process is already
                // down, run the cleanup so an orphan watchdog cannot respawn
                // it, and persist the same durable intent as a UI stop.
                boolean ok = stopDaemon(processName, ctx);
                saveDaemonState(name, false, ctx);
                ctx.sendMessage(chatId, ctx.tr(ok ? "daemon.stopped" : "daemon.stop_failed", displayName));
                break;
                
            case "status":
                ctx.sendMessage(chatId, ctx.tr(
                        isRunning ? "daemon.status_running" : "daemon.status_stopped",
                        displayName));
                break;
                
            default:
                ctx.sendMessage(chatId, ctx.tr("daemon.action_usage", name));
        }
    }
    
    private String[] findDaemon(String name) {
        for (String[] d : DAEMONS) {
            if (d[0].equals(name)) return d;
        }
        return null;
    }

    /**
     * Map a daemon process name to its durable "user stopped it" sentinel
     * path. Mirrors DaemonType.sentinelPath on the app side (filenames are
     * historical and don't all match the process name). This is the ONE
     * cross-UID signal honored by both the watchdog scripts and the app's
     * health-check; the legacy daemon_telegram_state.properties file is
     * written 0600 by this UID-2000 process and the app simply cannot read
     * it, so the sentinel is what actually prevents auto-restart.
     *
     * @return the sentinel path, or null for daemons we don't gate this way.
     */
    private static String sentinelForProcess(String processName) {
        switch (processName) {
            case "byd_cam_daemon":     return ScratchPaths.path("camera_daemon.disabled");
            case "sentry_daemon":      return ScratchPaths.path("sentry_daemon.disabled");
            case "acc_sentry_daemon":  return ScratchPaths.path("acc_sentry_daemon.disabled");
            case "sing-box":           return ScratchPaths.path("singbox.disabled");
            case "cloudflared":        return ScratchPaths.path("cloudflared.disabled");
            case "zrok":               return ScratchPaths.path("zrok.disabled");
            case "tailscaled":         return ScratchPaths.path("tailscale.disabled");
            case "telegram_bot_daemon": return ScratchPaths.path("telegram_bot_daemon.disabled");
            default:                   return null;
        }
    }
    
    /**
     * Best-effort graceful pre-kill flush for the camera daemon.
     *
     * <p>Retries a refusal the daemon marks transient (a just-ended trip still
     * draining), but never blocks the stop: the user asked for it, so a final
     * refusal proceeds to the kill anyway.
     */
    private void prepareCameraDaemonForKill(CommandContext ctx) {
        for (int attempt = 1; attempt <= 4; attempt++) {
            java.net.HttpURLConnection connection = null;
            try {
                connection = com.overdrive.app.util.DaemonHttpClient.open(
                        "/api/surveillance/prepare-restart", "POST", 3000, 10000);
                connection.setDoOutput(true);
                try (java.io.OutputStream body = connection.getOutputStream()) {
                    body.write(new byte[0]);
                }
                int code = connection.getResponseCode();
                if (code >= 200 && code < 300) {
                    ctx.log("Camera daemon checkpointed its active trip before stop");
                    return;
                }
                boolean retryable = code == 503;
                ctx.log("prepare-restart before camera stop returned HTTP " + code);
                if (!retryable || attempt == 4) return;
            } catch (Exception e) {
                // The daemon may already be down, or have no HTTP server — the
                // kill below is still correct.
                ctx.log("prepare-restart before camera stop failed: "
                        + e.getMessage());
                return;
            } finally {
                if (connection != null) connection.disconnect();
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Check if daemon is running using process name.
     * Same approach as AccSentryDaemonController.
     */
    private boolean isDaemonRunning(String processName, CommandContext ctx) {
        // Use grep -F for fixed string matching (handles hyphens in process names like sing-box)
        String output = ctx.execShell("ps -A | " + processMatcher(processName));
        return output != null && !output.trim().isEmpty();
    }

    static String processMatcher(String processName) {
        String matcher = "grep -F '" + processName + "' | grep -v grep";
        return "sentry_daemon".equals(processName)
                ? matcher + " | grep -v acc_sentry_daemon"
                : matcher;
    }
    
    /**
     * Stop daemon using killall -9.
     * Same approach as AccSentryDaemonController.
     */
    private boolean stopDaemon(String processName, CommandContext ctx) {
        ctx.log("Stopping daemon: " + processName);

        // Plant the durable, cross-UID disable sentinel for EVERY daemon up
        // front (chmod 666 so the app's health-check probe can read it). This
        // covers every daemon, including those that previously had no stop
        // marker and were resurrected by the app health-check. See
        // sentinelForProcess.
        String sentinel = sentinelForProcess(processName);
        if (sentinel != null) {
            ctx.execShell("echo \"disabled by telegram at $(date)\" > " + sentinel
                + "; chmod 666 " + sentinel + " 2>/dev/null");
        }

        // For camera daemon, also kill the restart wrapper script and delete it
        if ("byd_cam_daemon".equals(processName)) {
            // Ask the daemon to durably checkpoint its active trip before the
            // SIGKILL below. `kill -9` never runs the JVM shutdown hook, so
            // without this everything buffered since the last periodic flush is
            // lost. Best-effort by design: a Telegram stop is an explicit user
            // instruction and must not be blocked by a refusal, unlike the
            // update path which aborts. The trip still survives as a
            // recoverable telemetry file either way.
            prepareCameraDaemonForKill(ctx);
            // Kill watchdog FIRST so it doesn't respawn the daemon.
            //
            // pkill -f matches FULL argv (including any variable-assignment
            // text). execShell wraps the command in `sh -c "<cmd>"`, so even
            // the `P=start_cam_daemon` form puts the literal pattern in the
            // wrapper's argv → pkill self-matches and SIGKILLs its parent.
            // ps+awk+kill filters by PID list and excludes the calling
            // shell's own PID — same pattern used by
            // TelegramBotDaemon.killOldInstances.
            ctx.execShell(
                "MY_PID=$$; ps -A -o PID,ARGS | grep -F start_cam_daemon "
                + "| grep -v grep | awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
            );
            // Also kill via PID file
            ctx.execShell("if [ -f " + ScratchPaths.getDir() + "/cam_watchdog.pid ]; then kill -9 $(cat " + ScratchPaths.getDir() + "/cam_watchdog.pid) 2>/dev/null; fi");
            ctx.execShell("rm -f " + ScratchPaths.getDir() + "/start_cam_daemon.sh 2>/dev/null");
            ctx.execShell("rm -f " + ScratchPaths.getDir() + "/cam_watchdog.pid 2>/dev/null");
            // Wait for watchdog to fully die before killing daemon
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            ctx.execShell("rm -f " + ScratchPaths.getDir() + "/camera_daemon.lock 2>/dev/null");
        }

        // For acc sentry daemon, also kill the watchdog script; the sentinel
        // above makes any surviving loop exit cleanly.
        if ("acc_sentry_daemon".equals(processName)) {
            // ps+awk+kill — see cam case above for why pkill -f / variable
            // hop is not self-match safe.
            ctx.execShell(
                "MY_PID=$$; ps -A -o PID,ARGS | grep -F start_acc_sentry "
                + "| grep -v grep | awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
            );
            ctx.execShell("rm -f " + ScratchPaths.getDir() + "/start_acc_sentry.sh 2>/dev/null");
            ctx.execShell("rm -f " + ScratchPaths.getDir() + "/acc_sentry_daemon.lock 2>/dev/null");
        }

        // Mirror SentryDaemonController: request a clean stop, then remove
        // the PID file so a later manual start cannot inherit stale state.
        if ("sentry_daemon".equals(processName)) {
            ctx.execShell(
                "echo 'STOP' | nc -w 1 127.0.0.1 19879 2>/dev/null; "
                + "if [ -f " + ScratchPaths.getDir() + "/sentry_daemon.pid ]; then "
                + "kill -9 $(cat " + ScratchPaths.getDir() + "/sentry_daemon.pid) 2>/dev/null; "
                + "rm -f " + ScratchPaths.getDir() + "/sentry_daemon.pid; fi"
            );
        }

        // For zrok, also nuke the watchdog script so start_zrok.sh exits and
        // stays gone. `pkill -f 'zrok'`
        // below catches both start_zrok.sh and the zrok share binary, but
        // without the sentinel the watchdog can re-exec the share between
        // our pkill and the next health-check tick. Mirrors the cam_daemon
        // sentinel handshake.
        if ("zrok".equals(processName)) {
            ctx.execShell("rm -f " + ScratchPaths.getDir() + "/start_zrok.sh 2>/dev/null");
        }

        // Sing-box is launched by the sentry_proxy parent in the UI flow.
        // Kill that parent too and clear the same global proxy settings as
        // SingboxController, otherwise a Telegram stop leaks the wrapper and
        // its wakelock even though sing-box itself is down.
        if ("sing-box".equals(processName)) {
            ctx.execShell(
                "MY_PID=$$; ps -A -o PID,ARGS | " + processMatcher("sentry_proxy") + " "
                + "| awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done; "
                + "settings delete global http_proxy 2>/dev/null; "
                + "settings put global global_http_proxy_host '' 2>/dev/null; "
                + "settings put global global_http_proxy_port '' 2>/dev/null; "
                + "settings delete global global_http_proxy_exclusion_list 2>/dev/null"
            );
        }

        // For any tunnel stop, clear the daemon-side notify-tunnel throttle
        // stamp. The throttle exists to suppress cloudflared restart-loop
        // spam — but a user-initiated stop+start should always re-notify.
        if ("cloudflared".equals(processName)
                || "zrok".equals(processName)
                || "tailscaled".equals(processName)) {
            ctx.execShell("rm -f " + ScratchPaths.getDir() + "/.tunnel_last_notified 2>/dev/null");
        }
        
        // Kill via ps+awk+kill rather than pkill -f. pkill -f matches the
        // FULL argv (including any "P=…" variable assignment text), and
        // execShell wraps in `sh -c "<cmd>"` whose argv contains the
        // literal processName. Even the variable-hop trick lets pkill
        // self-match the assignment text → calling shell exits with 137,
        // not 0. ps+awk+kill filters by PID and excludes $$ → no
        // self-match.
        ctx.execShell(
            "MY_PID=$$; ps -A -o PID,ARGS | " + processMatcher(processName) + " "
            + "| awk '{print $1}' | while read pid; do "
            + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done"
        );
        
        // Clean up lock file for daemons that use processName-based lock files
        if (!"byd_cam_daemon".equals(processName) && !"acc_sentry_daemon".equals(processName)) {
            ctx.execShell("rm -f " + ScratchPaths.getDir() + "/" + processName + ".lock 2>/dev/null");
        }
        
        // Wait and verify
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        
        boolean stopped = !isDaemonRunning(processName, ctx);
        ctx.log("Daemon " + (stopped ? "stopped" : "STILL RUNNING") + ": " + processName);
        
        if (!stopped) {
            // Retry with killall as fallback
            ctx.execShell("killall -9 " + processName + " 2>/dev/null");
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            stopped = !isDaemonRunning(processName, ctx);
        }
        
        return stopped;
    }
    
    /**
     * Start daemon using the same flow as DaemonLauncher.kt.
     * For CameraDaemon: deploys watchdog script with bmmcamera.jar, native libs, proxy args.
     * For other daemons: uses the appropriate launch pattern.
     */
    private boolean startDaemon(
            String className, String processName, CommandContext ctx) {
        ctx.log("Starting daemon: " + className);
        
        // Get APK path
        String apkPath = ctx.execShell("pm path com.overdrive.app | head -1 | cut -d: -f2");
        if (apkPath == null || apkPath.trim().isEmpty()) {
            ctx.log("Cannot find APK path");
            return false;
        }
        apkPath = apkPath.trim();
        
        if ("CameraDaemon".equals(className)) {
            return startCameraDaemonWithWatchdog(apkPath, ctx);
        } else if ("AccSentryDaemon".equals(className)) {
            return startAccSentryDaemonWithWatchdog(apkPath, ctx);
        } else {
            // Generic daemon launch (SentryDaemon etc.).
            // spawnDetached, NOT execShell — execShell would drain stdout
            // until the grandchild app_process exits (i.e. forever) and
            // freeze the polling thread.
            String fullClass = "com.overdrive.app.daemon." + className;
            String cmd = String.format(
                "CLASSPATH=%s app_process /system/bin --nice-name=%s %s >> " + ScratchPaths.getDir() + "/%s.log 2>&1",
                apkPath, processName, fullClass, processName);
            ctx.spawnDetached(cmd);
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
            return isDaemonRunning(processName, ctx);
        }
    }
    
    /**
     * Replicates DaemonLauncher.launchCameraDaemonInternal() exactly.
     * Step 1: Kill old processes and clean up
     * Step 2: Write watchdog script with bmmcamera.jar, native libs, proxy args
     * Step 3: Launch watchdog script
     * Step 4: Verify daemon is running
     */
    private boolean startCameraDaemonWithWatchdog(String apkPath, CommandContext ctx) {
        String scriptPath = ScratchPaths.path("start_cam_daemon.sh");
        String logFile = ScratchPaths.path("cam_daemon.log");
        String processName = "byd_cam_daemon";
        String outputDir = "/sdcard/DCIM/BYDCam";
        
        // Detect native lib directory from APK path
        String nativeLibDir = apkPath.replace("/base.apk", "/lib/arm64");
        String libCheck = ctx.execShell("test -d '" + nativeLibDir + "' && echo yes || echo no");
        if (libCheck == null || !libCheck.trim().equals("yes")) {
            // Try common fallback paths
            String[] fallbacks = {
                "/data/app/~~*/com.overdrive.app-*/lib/arm64",
                "/data/app/com.overdrive.app-1/lib/arm64",
                "/data/app/com.overdrive.app-2/lib/arm64"
            };
            for (String fb : fallbacks) {
                String found = ctx.execShell("ls -d " + fb + " 2>/dev/null | head -1");
                if (found != null && !found.trim().isEmpty()) {
                    nativeLibDir = found.trim();
                    break;
                }
            }
        }
        ctx.log("Native lib dir: " + nativeLibDir);
        
        // Detect proxy (same as DaemonLauncher.getProxyArgs())
        String proxyArgs = "";
        String proxyCheck = ctx.execShell("settings get global http_proxy 2>/dev/null");
        if (proxyCheck != null && !proxyCheck.trim().isEmpty() && !"null".equals(proxyCheck.trim())) {
            String[] parts = proxyCheck.trim().split(":");
            if (parts.length >= 1) {
                String host = parts[0];
                String port = parts.length > 1 ? parts[1] : "8080";
                proxyArgs = "-Dhttp.proxyHost=" + host + " " +
                           "-Dhttp.proxyPort=" + port + " " +
                           "-Dhttps.proxyHost=" + host + " " +
                           "-Dhttps.proxyPort=" + port + " " +
                           "-Dhttp.nonProxyHosts=\"localhost|127.*|[::1]\" ";
                ctx.log("Proxy: " + host + ":" + port);
            }
        }
        
        // Step 1: Kill old processes and clean up. Combine into ONE shell
        // round-trip via spawnDetached + a wait-for-death loop, instead of
        // 6+ separate execShell forks (each ~30-50ms on 6125f). This
        // saves ~250ms of bot polling-thread blockage and — critically —
        // moves lock-rm AFTER the daemon dies so the daemon can't write
        // its PID back into the lockfile after we rm it (the
        // "lockfile resurrection" race A2 fixes for UI/update paths).
        //
        // NOTE: this multi-command payload contains "cam_daemon" literally,
        // so a `sh -c "..."` form would self-suicide on the first pkill.
        // ctx.execShell uses Runtime.exec with String[] argv so the
        // calling shell's argv[2] does contain the pattern — same self-
        // match risk. Workaround: write to a tmp file via heredoc, then
        // run from the file. The script's argv when executed is
        // `sh /data/local/tmp/.cam_kill.sh` — pattern not visible.
        ctx.log("Cleaning up old processes (single round-trip)...");
        String cleanupScript =
            "#!/system/bin/sh\n" +
            "# Telegram-side cam_daemon stop sequence\n" +
            "rm -f " + ScratchPaths.getDir() + "/camera_daemon.disabled 2>/dev/null\n" +
            "rm -f " + scriptPath + " " + ScratchPaths.getDir() + "/cam_watchdog.pid 2>/dev/null\n" +
            "if [ -f " + ScratchPaths.getDir() + "/cam_watchdog.pid ]; then kill -9 $(cat " + ScratchPaths.getDir() + "/cam_watchdog.pid) 2>/dev/null; fi\n" +
            "MY_PID=$$; ps -A -o PID,ARGS | grep -F 'cam_daemon' | grep -v grep "
                + "| awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n" +
            "killall -9 " + processName + " 2>/dev/null\n" +
            // Wait-for-death: poll up to 5s for the daemon to actually
            // exit. Without this, lock-rm runs before SIGKILL is fully
            // processed and the daemon can rewrite the lockfile post-rm.
            "for i in 1 2 3 4 5; do\n" +
            "  if ! ps -A | grep -F '" + processName + "' | grep -v grep > /dev/null; then break; fi\n" +
            "  sleep 1\n" +
            "  MY_PID=$$; ps -A -o PID,ARGS | grep -F '" + processName + "' | grep -v grep "
                + "| awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n" +
            "done\n" +
            // Now safe to rm the lock — daemon is gone.
            "rm -f " + ScratchPaths.getDir() + "/camera_daemon.lock 2>/dev/null\n" +
            "echo done\n";
        // Write via heredoc — body comes from stdin not argv, no
        // self-match. Then exec the file (argv = `sh <path>` only).
        String cleanupTmpPath = ScratchPaths.path(".tg_cam_kill_") + System.nanoTime() + ".sh";
        ctx.execShell(
            "cat > " + cleanupTmpPath + " <<'__TG_CAM_KILL_EOF__'\n" +
            cleanupScript +
            "__TG_CAM_KILL_EOF__\n" +
            "chmod 755 " + cleanupTmpPath
        );
        ctx.execShell("sh " + cleanupTmpPath);
        ctx.execShell("rm -f " + cleanupTmpPath);

        boolean stillRunning = isDaemonRunning(processName, ctx);
        if (stillRunning) {
            ctx.log("WARNING: Daemon still running after kill+wait — proceeding anyway");
        }
        
        // Step 2: Write the SAME watchdog script the UI deploys, by calling
        // DaemonLauncher.buildCamDaemonWatchdogScript (single source of truth).
        // Use a single heredoc instead of 50+ separate `echo "..." >> path`
        // execShell calls. On Snapdragon 6125f cold-fork is ~30-50ms per
        // execShell, so the per-line approach was burning ~2s of bot
        // polling-thread time per cam deploy. Heredoc form is one fork.
        ctx.log("Writing watchdog script (shared with UI flow, single fork)...");
        java.util.List<String> camLines =
            com.overdrive.app.launcher.DaemonLauncher.Companion.buildCamDaemonWatchdogScript(
                apkPath, nativeLibDir, outputDir, proxyArgs);
        StringBuilder camBody = new StringBuilder();
        for (String line : camLines) {
            camBody.append(line).append('\n');
        }
        // Heredoc body comes from stdin not argv — no self-match risk
        // even though watchdog body contains daemon patterns. The
        // delimiter is a unique marker that must not appear in the body.
        ctx.execShell(
            "cat > " + scriptPath + " <<'__CAM_WATCHDOG_EOF__'\n" +
            camBody.toString() +
            "__CAM_WATCHDOG_EOF__\n" +
            "chmod 755 " + scriptPath
        );
        
        // Verify script exists
        String verify = ctx.execShell("test -f " + scriptPath + " && wc -l < " + scriptPath);
        if (verify == null || verify.trim().isEmpty() || "0".equals(verify.trim())) {
            ctx.log("Failed to write watchdog script");
            return false;
        }
        ctx.log("Script written (" + verify.trim() + " lines)");
        
        // Step 3: Launch watchdog script (same as DaemonLauncher.launchCamDaemonScript).
        // spawnDetached — the watchdog re-spawns the daemon forever, so its
        // stdio never EOFs. Using execShell here would freeze the polling
        // thread for the lifetime of the watchdog (i.e. until reboot).
        ctx.log("Launching watchdog...");
        ctx.spawnDetached("sh " + scriptPath);

        // Step 4: Verify daemon is running
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        boolean running = isDaemonRunning(processName, ctx);
        ctx.log("CameraDaemon " + (running ? "started with watchdog ✓" : "FAILED to start"));
        return running;
    }
    
    /**
     * Replicates DaemonLauncher.launchAccSentryDaemon() flow.
     */
    private boolean startAccSentryDaemonWithWatchdog(String apkPath, CommandContext ctx) {
        String scriptPath = ScratchPaths.path("start_acc_sentry.sh");
        String processName = "acc_sentry_daemon";

        // Step 1: Kill old processes via tmpfile script (no self-match
        // risk) + wait-for-death + post-pkill lock-rm. Same pattern as
        // cam-daemon stop above. One round-trip instead of 4 forks.
        ctx.log("Cleaning up old processes (single round-trip)...");
        String accCleanupScript =
            "#!/system/bin/sh\n" +
            "# Telegram-side acc_sentry_daemon stop sequence\n" +
            "rm -f " + ScratchPaths.getDir() + "/acc_sentry_daemon.disabled 2>/dev/null\n" +
            "rm -f " + scriptPath + " 2>/dev/null\n" +
            "MY_PID=$$; ps -A -o PID,ARGS | grep -F 'acc_sentry' | grep -v grep "
                + "| awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n" +
            "for i in 1 2 3 4 5; do\n" +
            "  if ! ps -A | grep -F '" + processName + "' | grep -v grep > /dev/null; then break; fi\n" +
            "  sleep 1\n" +
            "  MY_PID=$$; ps -A -o PID,ARGS | grep -F '" + processName + "' | grep -v grep "
                + "| awk '{print $1}' | while read pid; do "
                + "if [ \"$pid\" != \"$MY_PID\" ]; then kill -9 $pid 2>/dev/null; fi; done\n" +
            "done\n" +
            "rm -f " + ScratchPaths.getDir() + "/acc_sentry_daemon.lock 2>/dev/null\n" +
            "echo done\n";
        String accCleanupTmpPath = ScratchPaths.path(".tg_acc_kill_") + System.nanoTime() + ".sh";
        ctx.execShell(
            "cat > " + accCleanupTmpPath + " <<'__TG_ACC_KILL_EOF__'\n" +
            accCleanupScript +
            "__TG_ACC_KILL_EOF__\n" +
            "chmod 755 " + accCleanupTmpPath
        );
        ctx.execShell("sh " + accCleanupTmpPath);
        ctx.execShell("rm -f " + accCleanupTmpPath);

        // Step 2: Write the SAME watchdog the UI uses (sentinel-gated,
        // uncapped — see [[feedback_acc_sentry_uncapped_immortal]]). Single
        // source: DaemonLauncher.buildAccSentryWatchdogScript. Heredoc
        // form = one fork instead of N (one per script line).
        ctx.log("Writing watchdog script (shared with UI flow, single fork)...");
        java.util.List<String> accLines =
            com.overdrive.app.launcher.DaemonLauncher.Companion.buildAccSentryWatchdogScript(apkPath, "");
        StringBuilder accBody = new StringBuilder();
        for (String line : accLines) {
            accBody.append(line).append('\n');
        }
        ctx.execShell(
            "cat > " + scriptPath + " <<'__ACC_WATCHDOG_EOF__'\n" +
            accBody.toString() +
            "__ACC_WATCHDOG_EOF__\n" +
            "chmod 755 " + scriptPath
        );
        
        // Step 3: Launch — spawnDetached, see CameraDaemon launch above for why.
        ctx.log("Launching watchdog...");
        ctx.spawnDetached("sh " + scriptPath);

        // Step 4: Verify
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        boolean running = isDaemonRunning(processName, ctx);
        ctx.log("AccSentryDaemon " + (running ? "started with watchdog ✓" : "FAILED to start"));
        return running;
    }
    
    /**
     * Start external binary daemon via shell command.
     */
    private boolean startShellDaemon(String name, CommandContext ctx) {
        ctx.log("Starting shell daemon: " + name);
        
        String cmd;
        String processName;

        boolean useProxy = com.overdrive.app.mqtt.ProxyHelper.probePort(8119);
        switch (name) {
            case "cloudflared":
                // Cloudflared tunnel - match UI version (TunnelLauncher.kt)
                StringBuilder cfCmd = new StringBuilder();
                cfCmd.append("nohup sh -c '");
                
                if (useProxy) {
                    ctx.log("Using sing-box proxy for cloudflared...");
                    String proxyUrl = "http://127.0.0.1:8119";
                    cfCmd.append("export http_proxy=").append(proxyUrl).append(" && ");
                    cfCmd.append("export https_proxy=").append(proxyUrl).append(" && ");
                    cfCmd.append("export HTTP_PROXY=").append(proxyUrl).append(" && ");
                    cfCmd.append("export HTTPS_PROXY=").append(proxyUrl).append(" && ");
                    cfCmd.append("export no_proxy=\"localhost,127.0.0.1,::1\" && ");
                    cfCmd.append("export NO_PROXY=\"localhost,127.0.0.1,::1\" && ");
                } else {
                    ctx.log("Direct connection (no proxy)...");
                }
                
                // Same flags as UI version
                cfCmd.append(ScratchPaths.path("cloudflared ")).append(com.overdrive.app.config.CloudflaredPaidConfig.getArgs());
                cfCmd.append("' > " + ScratchPaths.getDir() + "/cloudflared.log 2>&1 &");
                
                cmd = cfCmd.toString();
                processName = "cloudflared";
                break;
                
            case "zrok":
                // Clear the disable sentinel — user is explicitly starting
                // the tunnel via Telegram. Without this, /daemon zrok stop
                // followed by /daemon zrok start would silently no-op
                // because the new watchdog would see the stale sentinel and exit.
                // Clear the old log too so public-mode URL discovery cannot return
                // a URL from the previous share session.
                ctx.execShell("rm -f " + ScratchPaths.getDir() + "/zrok.disabled " + ScratchPaths.getDir() + "/zrok.log 2>/dev/null");

                // Zrok tunnel — use RESERVED mode with saved token (same as app UI)
                // Falls back to public mode only if no reserved token exists
                String identityCheck = ctx.execShell("test -f " + ScratchPaths.getDir() + "/.zrok/environment.json && echo yes || echo no");
                if (identityCheck == null || !identityCheck.trim().equals("yes")) {
                    // Need to enable — read token from saved file (set via app UI).
                    // The file is encrypted at rest (CredentialCipher) since it's
                    // written by ZrokLauncher.saveEnableToken(); decrypt() passes
                    // plaintext through unchanged for tokens saved before that.
                    String enableToken = ctx.execShell("cat " + ScratchPaths.getDir() + "/.zrok/enable_token 2>/dev/null");
                    if (enableToken == null || enableToken.trim().isEmpty() || enableToken.contains("No such file")) {
                        ctx.log("❌ No zrok enable token found. Set it from the app UI first.");
                        return false;
                    }
                    enableToken = com.overdrive.app.byd.cloud.crypto.CredentialCipher.decrypt(enableToken.trim());
                    if (enableToken.isEmpty()) {
                        ctx.log("❌ Zrok enable token could not be decrypted. Re-set it from the app UI.");
                        return false;
                    }
                    if (!useProxy) {
                        useProxy = com.overdrive.app.launcher.ZrokRuntimeProbe.waitForProxy(5_000L);
                    }
                    ctx.log("⚠️ Device not enabled. Registering now (uses 1 of 5 slots)...");
                    String zrokProxyEnv = useProxy
                        ? "ALL_PROXY=socks5://127.0.0.1:8119 " +
                          "HTTP_PROXY=socks5://127.0.0.1:8119 " +
                          "HTTPS_PROXY=socks5://127.0.0.1:8119 " +
                          "NO_PROXY=localhost,127.0.0.1 "
                        : "";
                    String enableCmd = "HOME=" + ScratchPaths.getDir() + " " +
                        zrokProxyEnv +
                        ScratchPaths.path("zrok enable ") +
                        com.overdrive.app.launcher.ZrokRuntimeProbe.shellQuote(enableToken) +
                        " --headless 2>&1";
                    String enableResult = ctx.execShell(enableCmd);
                    if (enableResult == null) {
                        ctx.log("❌ Zrok enable command failed without output.");
                        return false;
                    }
                    String enableError =
                        com.overdrive.app.launcher.ZrokRuntimeProbe.extractErrorMessage(enableResult);
                    if (!enableError.isEmpty()) {
                        ctx.log("❌ Failed to enable zrok: " + enableError);
                        return false;
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    String enabledCheck = ctx.execShell(
                        "test -f " + ScratchPaths.getDir() + "/.zrok/environment.json && echo yes || echo no");
                    if (enabledCheck == null || !"yes".equals(enabledCheck.trim())) {
                        ctx.log("❌ Zrok enable did not create an environment identity.");
                        return false;
                    }
                    ctx.log("✅ Zrok environment enabled.");
                } else {
                    ctx.log("✅ Device already enabled.");
                }
                
                // Check for saved reserved token (from app UI's zrok reserve).
                // Encrypted at rest by ZrokLauncher.saveReservedToken(); decrypt()
                // passes plaintext through unchanged for pre-existing tokens.
                String reservedToken = null;
                String tokenRead = ctx.execShell("cat " + ScratchPaths.getDir() + "/.zrok/reserved_token 2>/dev/null");
                if (tokenRead != null && !tokenRead.trim().isEmpty() && !tokenRead.contains("No such file")) {
                    String decrypted = com.overdrive.app.byd.cloud.crypto.CredentialCipher.decrypt(tokenRead.trim());
                    if (!decrypted.isEmpty()) {
                        reservedToken = decrypted;
                    }
                }
                
                // Also read saved unique name for logging
                String savedName = null;
                String nameRead = ctx.execShell("cat " + ScratchPaths.getDir() + "/.zrok/unique_name 2>/dev/null");
                if (nameRead != null && !nameRead.trim().isEmpty() && !nameRead.contains("No such file")) {
                    savedName = nameRead.trim();
                }
                
                // Deploy the SAME watchdog script (start_zrok.sh) the UI uses,
                // not a bare `nohup zrok share`. Without the watchdog, the
                // share crashes once and the tunnel dies forever — and the
                // health-check sees `daemon_telegram_state.properties=running`
                // so it skips relaunch. Mirrors writeAndLaunchWatchdog in
                // ZrokLauncher.kt; script body comes from
                // ZrokLauncher.buildZrokWatchdogScriptStatic.
                boolean reservedMode = (reservedToken != null);
                String tokenForScript = reservedMode ? reservedToken : "";
                if (reservedMode) {
                    ctx.log("Using saved reserved zrok share.");
                    if (savedName != null) {
                        ctx.log("Permanent URL: https://" + savedName + ".share.zrok.io");
                    }
                } else {
                    ctx.log("⚠️ No reserved token found — using public mode (random URL)");
                }
                java.util.List<String> watchdogLines =
                    com.overdrive.app.launcher.ZrokLauncher.Companion.buildZrokWatchdogScriptStatic(
                        reservedMode, tokenForScript, useProxy);
                String zrokScriptPath = ScratchPaths.path("start_zrok.sh");
                // Heredoc-based write: one fork instead of N (where N is the
                // number of script lines). Heredoc body comes from stdin so
                // the daemon-pattern in the body never enters argv → no
                // pkill self-match risk.
                StringBuilder zrokBody = new StringBuilder();
                for (String line : watchdogLines) {
                    zrokBody.append(line).append('\n');
                }
                ctx.execShell(
                    "cat > " + zrokScriptPath + " <<'__ZROK_WATCHDOG_EOF__'\n" +
                    zrokBody.toString() +
                    "__ZROK_WATCHDOG_EOF__\n" +
                    "chmod 755 " + zrokScriptPath
                );
                cmd = "nohup sh " + zrokScriptPath + " > /dev/null 2>&1 &";
                processName = "zrok";
                break;

            case "tailscale":
                // Tailscale tunnel - match UI version (TailscaleLauncher.kt)
                // Check if tailscale proxy should be enabled
                String proxyEnabledCheck = ctx.execShell("cat " + ScratchPaths.getDir() + "/.tailscale/proxy_enabled");
                boolean enableProxy = proxyEnabledCheck != null && proxyEnabledCheck.trim().equals("true");

                StringBuilder tailscaleCmd = new StringBuilder();
                tailscaleCmd.append("nohup sh -c '");

                if (useProxy) {
                    ctx.log("Using sing-box proxy for tailscale...");
                    String proxyUrl = "http://127.0.0.1:8119";
                    tailscaleCmd.append("export http_proxy=").append(proxyUrl).append(" && ");
                    tailscaleCmd.append("export https_proxy=").append(proxyUrl).append(" && ");
                    tailscaleCmd.append("export HTTP_PROXY=").append(proxyUrl).append(" && ");
                    tailscaleCmd.append("export HTTPS_PROXY=").append(proxyUrl).append(" && ");
                    tailscaleCmd.append("export no_proxy=\"localhost,127.0.0.1,::1\" && ");
                    tailscaleCmd.append("export NO_PROXY=\"localhost,127.0.0.1,::1\" && ");
                } else {
                    ctx.log("Direct connection (no proxy)...");
                }

                // Same flags as UI version
                tailscaleCmd.append(ScratchPaths.path(".tailscale/tailscaled --tun userspace-networking "));
                tailscaleCmd.append("--statedir " + ScratchPaths.getDir() + "/.tailscale ");
                if (enableProxy) {
                    tailscaleCmd.append("--socks5-server 127.0.0.1:8539 ");
                }
                tailscaleCmd.append("--socket 127.0.0.1:8532");
                tailscaleCmd.append("' > " + ScratchPaths.getDir() + "/.tailscale/tailscaled.log 2>&1 &");

                cmd = tailscaleCmd.toString();
                processName = "tailscaled";
                break;

            case "singbox":
                // Sing-box proxy
                cmd = "nohup " + ScratchPaths.getDir() + "/sing-box run -c " + ScratchPaths.getDir() + "/singbox_config.json " +
                      "> " + ScratchPaths.getDir() + "/singbox.log 2>&1 &";
                processName = "sing-box";
                break;

            default:
                ctx.log("Unknown shell daemon: " + name);
                return false;
        }

        // Each per-daemon `cmd` above is shaped as `nohup … > log 2>&1 &`.
        // spawnDetached wraps in `(<inner> </dev/null &)`, so we strip the
        // outer `nohup` prefix and trailing ` &` to avoid double-backgrounding
        // syntax noise. The `> log 2>&1` redirect stays — that's the
        // daemon's own log file. Reparenting to init inside the (...&)
        // wrapper handles SIGHUP, so dropping `nohup` is safe.
        String inner = cmd.startsWith("nohup ") ? cmd.substring(6) : cmd;
        if (inner.endsWith(" &")) inner = inner.substring(0, inner.length() - 2);
        ctx.spawnDetached(inner);

        // Wait and verify
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        
        boolean started = isDaemonRunning(processName, ctx);
        ctx.log("Shell daemon " + (started ? "started" : "FAILED") + ": " + name);
        
        // For cloudflared, wait longer and try to get the URL
        if (started && "cloudflared".equals(name)) {
            ctx.log("Waiting for tunnel URL...");
            String tunnelUrl = null;
            for (int i = 0; i < 15; i++) { // Wait up to 15 seconds
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                // SOTA FIX: Use grep instead of cat to avoid loading entire log into memory
                boolean isPaid = com.overdrive.app.config.CloudflaredPaidConfig.isPaidVersion();
                String token = com.overdrive.app.config.CloudflaredPaidConfig.getToken();

                if (isPaid && !token.isEmpty()) {
                    String grepResult = ctx.execShell("grep -iE 'ingress|hostname' " + ScratchPaths.getDir() + "/cloudflared.log 2>/dev/null | grep -oE '[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}' | grep -vE '127\\.0\\.0\\.1' | tail -1");
                    // execShell returns "" (not null) on no match — require a real host
                    // (non-empty + contains a dot) so we never persist a bare "https://".
                    if (grepResult != null && !grepResult.trim().isEmpty() && grepResult.contains(".")) {
                        tunnelUrl = "https://" + grepResult.trim();
                        ctx.log("Tunnel URL (Paid): " + tunnelUrl);
                        // Save URL to file for /url command
                        saveTunnelUrl(tunnelUrl, ctx);
                        break;
                    }
                } else {
                    String grepResult = ctx.execShell("grep -o 'https://[a-z0-9-]*\\.trycloudflare\\.com' " + ScratchPaths.getDir() + "/cloudflared.log 2>/dev/null | grep -v 'api\\.' | head -1");
                    if (grepResult != null && grepResult.startsWith("https://") && grepResult.contains("-")) {
                        tunnelUrl = grepResult.trim();
                        ctx.log("Tunnel URL (Free): " + tunnelUrl);
                        // Save URL to file for /url command
                        saveTunnelUrl(tunnelUrl, ctx);
                        break;
                    }
                }
                // Check for errors (only read last few lines)
                String tailLog = ctx.execShell("tail -5 " + ScratchPaths.getDir() + "/cloudflared.log 2>/dev/null");
                if (tailLog != null) {
                    if (tailLog.contains("proxyconnect") || 
                        (tailLog.contains("proxy") && tailLog.contains("refused"))) {
                        ctx.log("Proxy error - is sing-box running?");
                        return false;
                    }
                }
            }
            if (tunnelUrl == null) {
                // Check if process is still running
                if (!isDaemonRunning(processName, ctx)) {
                    ctx.log("Cloudflared exited - check " + ScratchPaths.getDir() + "/cloudflared.log");
                    return false;
                }
                ctx.log("Tunnel started but URL not yet available");
            }
        }
        
        // For zrok, wait and try to get the URL (similar to cloudflared)
        if (started && "zrok".equals(name)) {
            ctx.log("Waiting for Zrok URL...");
            String zrokUrl = null;
            for (int i = 0; i < 15; i++) { // Wait up to 15 seconds
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                // SOTA FIX: Use grep instead of cat to avoid loading entire log into memory
                String grepResult = ctx.execShell("grep -o 'https://[a-z0-9-]*\\.share\\.zrok\\.io' " + ScratchPaths.getDir() + "/zrok.log 2>/dev/null | tail -1");
                if (grepResult != null && grepResult.startsWith("https://")) {
                    zrokUrl = grepResult.trim();
                    ctx.log("Zrok URL: " + zrokUrl);
                    // Save URL to file for /url command and send notification
                    saveTunnelUrl(zrokUrl, ctx);
                    break;
                }
                // Check for errors (only read last few lines)
                String tailLog = ctx.execShell("tail -5 " + ScratchPaths.getDir() + "/zrok.log 2>/dev/null");
                if (tailLog != null && (tailLog.contains("error") || tailLog.contains("failed"))) {
                    ctx.log("Zrok error detected in log");
                    // Don't return false - zrok might still be starting
                }
            }
            if (zrokUrl == null) {
                // Check if process is still running
                if (!isDaemonRunning("zrok", ctx)) {
                    ctx.log("Zrok exited - check " + ScratchPaths.getDir() + "/zrok.log");
                    return false;
                }
                ctx.log("Zrok started but URL not yet available");
            }
        }

        // For tailscale get the URL
        if (started && "tailscale".equals(name)) {
            String getIpResult = ctx.execShell(ScratchPaths.path(".tailscale/tailscale --socket 127.0.0.1:8532 ip --1"));
            if (getIpResult != null) {
                String tailscaleUrl = "http://" + getIpResult.trim() + ":8080";
                ctx.log("Tailscale URL: " + tailscaleUrl);
                saveTunnelUrl(tailscaleUrl, ctx);
            }
        }
        
        return started;
    }
    
    /**
     * Save tunnel URL to file for /url command and send notification message.
     */
    private void saveTunnelUrl(String url, CommandContext ctx) {
        try {
            // Save to file for /url command
            ctx.execShell("echo '" + url + "' > " + ScratchPaths.getDir() + "/tunnel_url.txt");
            ctx.log("Tunnel URL saved to file");
            
            // Send notification message to owner — read from the unified
            // config (single source of truth shared with the app).
            long ownerChatId = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getOwnerChatId();
            if (ownerChatId > 0) {
                ctx.sendMessage(ownerChatId, ctx.tr("daemon.tunnel_url", url));
                ctx.log("Tunnel URL notification sent to owner");
            }
        } catch (Exception e) {
            ctx.log("Error saving tunnel URL: " + e.getMessage());
        }
    }
    
    /**
     * Save the legacy Telegram daemon-state file.
     * 
     * @param daemonName The daemon name (e.g., "cloudflared", "singbox")
     * @param running true if daemon was started, false if stopped
     * @param ctx Command context for logging
     */
    private void saveDaemonState(String daemonName, boolean running, CommandContext ctx) {
        try {
            File stateFile = new File(STATE_FILE);
            Properties props = new Properties();
            
            // Load existing state
            if (stateFile.exists()) {
                try (FileInputStream fis = new FileInputStream(stateFile)) {
                    props.load(fis);
                }
            }
            
            // Update state for this daemon
            // Format: daemon_name=running|stopped
            props.setProperty(daemonName, running ? "running" : "stopped");
            props.setProperty(daemonName + "_timestamp", String.valueOf(System.currentTimeMillis()));
            
            // Save state
            try (FileOutputStream fos = new FileOutputStream(stateFile)) {
                props.store(fos, "Daemon state from Telegram commands - DO NOT EDIT");
            }
            
            ctx.log("Saved daemon state: " + daemonName + "=" + (running ? "running" : "stopped"));
        } catch (Exception e) {
            ctx.log("Error saving daemon state: " + e.getMessage());
        }
    }
    
    /**
     * Check if a daemon was stopped via Telegram (should not be auto-restarted).
     * This is a static method so health checks can call it.
     * 
     * @param daemonName The daemon name to check
     * @return true if daemon was explicitly stopped via Telegram
     */
    public static boolean isDaemonStoppedViaTelegram(String daemonName) {
        try {
            File stateFile = new File(STATE_FILE);
            if (!stateFile.exists()) return false;
            
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(stateFile)) {
                props.load(fis);
            }
            
            String state = props.getProperty(daemonName, "");
            return "stopped".equals(state);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Clear the stopped state for a daemon (e.g., when user starts it from UI).
     * 
     * @param daemonName The daemon name to clear
     */
    public static void clearDaemonStoppedState(String daemonName) {
        try {
            File stateFile = new File(STATE_FILE);
            if (!stateFile.exists()) return;
            
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(stateFile)) {
                props.load(fis);
            }
            
            props.remove(daemonName);
            props.remove(daemonName + "_timestamp");
            
            try (FileOutputStream fos = new FileOutputStream(stateFile)) {
                props.store(fos, "Daemon state from Telegram commands - DO NOT EDIT");
            }
        } catch (Exception e) {
            // Ignore errors
        }
    }
}
