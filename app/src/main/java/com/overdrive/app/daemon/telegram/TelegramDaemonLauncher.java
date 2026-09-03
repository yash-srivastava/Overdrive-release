package com.overdrive.app.daemon.telegram;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Shell-side launcher for {@code TelegramBotDaemon}.
 *
 * Both {@code AccSentryDaemon} (auto-start on ACC OFF) and the web
 * {@code TelegramApiHandler} (run after a successful /token POST) need to
 * spawn the daemon. Both run as UID 2000, so they can {@code app_process}
 * directly without an ADB-shell hop.
 *
 * The native settings fragment uses {@code AdbDaemonLauncher} instead — it
 * runs as the app UID and has to bounce through ADB.
 */
public final class TelegramDaemonLauncher {

    private static final String TAG = "TelegramDaemonLauncher";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    public static final String DAEMON_PROCESS = "telegram_bot_daemon";
    private static final String DAEMON_CLASS =
            "com.overdrive.app.daemon.TelegramBotDaemon";
    private static final String DAEMON_LOG =
            "/data/local/tmp/telegrambotdaemon.log";

    private TelegramDaemonLauncher() {}

    /** Returns true if a process named {@code DAEMON_PROCESS} or watchdog script is running. */
    public static boolean isRunning() {
        // Check if either the daemon process itself or its watchdog script is alive
        String out = execShell("ps -A -o ARGS | grep -E 'telegram_bot_daemon|start_telegram\\.sh' | grep -v grep");
        return out != null && !out.trim().isEmpty();
    }

    /**
     * Launch the daemon if it isn't already running. No-op when the process
     * is already alive — the daemon's own singleton lock would refuse a
     * second instance anyway, so we shouldn't waste a fork.
     *
     * @return true if a launch was attempted, false if it was already running
     *         or the APK path could not be discovered.
     */
    public static boolean launchIfNotRunning() {
        if (isRunning()) {
            logger.debug("Telegram daemon already running, skip launch");
            return false;
        }

        String apkPath = resolveApkPath();
        if (apkPath == null) {
            logger.warn("Could not find APK path for com.overdrive.app");
            return false;
        }

        String innerCmd = "CLASSPATH=" + apkPath + " " +
                "app_process /system/bin " +
                "--nice-name=" + DAEMON_PROCESS + " " +
                DAEMON_CLASS +
                " >> " + DAEMON_LOG + " 2>&1";

        // Detached fire-and-forget — same recipe AppUpdater.runDetachedInstall
        // uses (AppUpdater.java:741-745). Three tricks make this safe:
        //   1. (... &) wraps the inner command in a subshell that backgrounds
        //      and exits immediately, reparenting the grandchild to init.
        //   2. </dev/null >/dev/null 2>&1 on the OUTER shell closes our stdio
        //      so no descriptor is held open after start() returns.
        //   3. No waitFor() / no readLine() — the parent shell exits in ms,
        //      and we don't try to drain a pipe whose far end is held open
        //      by the long-lived app_process daemon.
        //
        // The previous version used waitFor() + readLine(). That blocked the
        // HTTP worker thread until the daemon exited (i.e. forever), holding
        // /api/telegram/token open and hanging the web tab.
        String detached = "(" + innerCmd + " </dev/null &)";

        logger.info("Launching telegram daemon: " + innerCmd);
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", detached);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
            pb.redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
            pb.start();
        } catch (java.io.IOException e) {
            logger.warn("Telegram daemon spawn failed: " + e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Discover the on-disk APK path. {@code pm path} is the most reliable —
     * it survives app updates that change the random hash directory under
     * {@code /data/app}. Falls back to a glob if the package manager is
     * unreachable for some reason.
     */
    private static String resolveApkPath() {
        String apkPath = execShell("pm path com.overdrive.app 2>/dev/null | head -1 | cut -d: -f2");
        if (isNonBlank(apkPath)) return apkPath.trim();

        apkPath = execShell("ls /data/app/*/com.overdrive.app*/base.apk 2>/dev/null | head -1");
        if (isNonBlank(apkPath)) return apkPath.trim();

        apkPath = execShell("ls /data/app/com.overdrive.app*/base.apk 2>/dev/null | head -1");
        if (isNonBlank(apkPath)) return apkPath.trim();

        return null;
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * Bounded shell exec for the short-lived commands (ps, pm path, ls)
     * this class runs. Five-second hard ceiling so a hung binary can't
     * stall the HTTP worker that called {@link #launchIfNotRunning}.
     */
    private static String execShell(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            }
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warn("execShell timeout: " + command);
                p.destroyForcibly();
                return null;
            }
            return out.toString();
        } catch (IOException | InterruptedException e) {
            logger.warn("execShell error: " + e.getMessage());
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            return null;
        }
    }
}
