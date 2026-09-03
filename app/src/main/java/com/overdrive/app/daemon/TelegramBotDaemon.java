package com.overdrive.app.daemon;

import android.os.Looper;

import com.overdrive.app.daemon.telegram.CommandContext;
import com.overdrive.app.daemon.telegram.CommandRouter;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.LocaleManager;
import com.overdrive.app.telegram.TelegramMessages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import com.overdrive.app.daemon.proxy.Safe;

/**
 * Telegram Bot Daemon - runs as shell user (UID 2000) via ADB shell.
 * 
 * Uses long polling (not webhooks) for NAT compatibility behind 4G.
 * Commands are handled by modular handlers in the telegram package.
 * 
 * IPC server on port 19877 accepts notification requests from app process.
 */
public class TelegramBotDaemon {
    
    private static final String TAG = "TelegramBotDaemon";
    private static DaemonLogger logger;

    private static String tr(String key, Object... args) {
        return TelegramMessages.get(key, args);
    }
    
    // ==================== ENCRYPTED CONSTANTS (SOTA Java obfuscation) ====================
    // Decrypted at runtime via Safe.s() - AES-256-CBC with stack-based key reconstruction
    /** /data/local/tmp */
    private static String PATH_DATA_LOCAL_TMP() { return Safe.s("vuaMjrmBGBFh07qqnUuL8w=="); }
    /** /data/local/tmp/telegram_config.properties */
    private static String PATH_TELEGRAM_CONFIG() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxzwQSn0P1N0jxHygc8N+4Ft+9mlR8XQ+WvEw0ktanrtNx"); }
    /** /data/local/tmp/tunnel_url.txt */
    private static String PATH_TELEGRAM_URL_FILE() { return Safe.s("ZHx6IP38aGV/Q7iMCCcxz/kVx51CDNRiQ/Mc5+npiPo="); }
    /** https://api.telegram.org/bot */
    private static String TELEGRAM_API_BASE() { return Safe.s("FS7R/5I0wopp0qBqyJXzvDKg6eI9UXmD/Oei3NbaaGQ="); }
    
    // Port allocations across daemons:
    //   19876 — CameraDaemon control TCP (Constants.kt)
    //   19877 — SurveillanceIpcServer (IPC for the surveillance engine)
    //   19878 — BydEventDaemon TCP push (vehicle door / charge / radar events)
    //   19879 — SentryDaemon control
    //   19880 — Telegram bot IPC (this daemon)
    // Was previously 19878 which collides with BydEventDaemon — whichever
    // daemon started second silently failed to bind. Moved to 19880.
    private static final int IPC_PORT = 19880;
    
    // Singleton lock (same pattern as CameraDaemon / AccSentryDaemon)
    private static final String LOCK_FILE = "/data/local/tmp/telegram_bot_daemon.lock";
    private static java.io.RandomAccessFile lockFileHandle;
    private static java.nio.channels.FileLock fileLock;
    
    private static volatile boolean running = true;
    private static final AtomicBoolean polling = new AtomicBoolean(false);
    
    // volatile: written by the TelegramPoll thread (refreshConfigFromUnified)
    // and read by the IPC_WORKERS threads in processIpcCommand. Without
    // volatile there is no happens-before edge between the two, so a worker
    // could observe a stale value beyond the intended one-cycle window.
    private static volatile String botToken;
    private static volatile long ownerChatId = -1;
    private static volatile boolean videoUploadsEnabled = false;  // Default to OFF - user must enable
    // Notification-category gates. Defaults match UnifiedTelegramConfig getters
    // (criticalAlerts/motionText default ON, connectivity default OFF) so a
    // fresh install behaves the way the UI's checked-by-default toggles imply.
    // Refreshed once per long-poll cycle by refreshConfigFromUnified() AND
    // re-read fresh per IPC command by freshSnapshotForIpc() so a toggle or
    // unpair in the web UI takes effect on the very next event, not ~30s later.
    private static volatile boolean criticalAlertsEnabled = true;
    private static volatile boolean connectivityAlertsEnabled = false;
    private static volatile boolean motionTextEnabled = true;
    // volatile + refresh under a monitor: reassigned by the poll thread
    // (refreshHttpClient) and read by IPC worker threads (send*). Without
    // volatile a worker could keep using a stale client after a proxy switch;
    // without the monitor two threads could rebuild concurrently and orphan a
    // client's idle pool threads. Same happens-before rationale as the volatile
    // gate statics above.
    private static volatile OkHttpClient httpClient;
    // Dedicated client for heavy multipart uploads (sendVideo/sendDocument). A
    // multi-MB clip pushed over the car's constrained/park-boundary uplink
    // routinely needs longer than the 30s write / 60s read the polling+text+
    // photo client uses — the log showed the hero PHOTO succeeding and the
    // clip then timing out on the SAME link seconds later. Derived from
    // httpClient via newBuilder() so it (a) tracks every proxy switch for free
    // and (b) SHARES the connection pool + dispatcher — no orphaned idle
    // threads. Rebuilt in lockstep inside refreshHttpClient() under the same
    // monitor. Only the write/read timeouts are widened; connect stays 30s (a
    // real connect either lands quickly or the host is unreachable) and no
    // callTimeout is set, so a legitimately-slow-but-progressing upload is
    // bounded per-socket-operation rather than hard-capped mid-flight.
    private static volatile OkHttpClient uploadHttpClient;

    // ---- Fix #2: retry + spool on a definitively-undelivered send ----
    // A send that fails because the network never carried a byte (DNS didn't
    // resolve, connect refused, connect timed out) is dup-SAFE to replay: the
    // request provably never reached Telegram. A POST-connect read/write
    // timeout is AMBIGUOUS (Telegram may have received + acted on it) and, with
    // no outbound idempotency key, must NOT be replayed. sendMessage/sendPhoto
    // set this per-thread flag in their catch so the enclosing IPC handler can
    // decide whether to spool the original command. ThreadLocal because each
    // IPC command runs start-to-finish on one worker thread; the send is
    // synchronous on that thread, so the flag is set-then-read with no cross-
    // thread visibility concern. Cleared before every send so a stale value
    // from a prior command can never leak in.
    private static final ThreadLocal<Boolean> lastSendUndelivered =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    // Set while draining the disk spool so the daemon-side spool-on-failure
    // path is SUPPRESSED for replays. Without this, a replay that fails again
    // during a still-ongoing outage would re-offer the command (new file, reset
    // age stamp) and could ping-pong across drains, defeating the age cap and
    // TelegramSpool's at-most-once contract. ThreadLocal: the drain runs on its
    // own "TelegramSpoolDrain" thread and calls processIpcCommand synchronously.
    private static final ThreadLocal<Boolean> replayingSpooledCmd =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    // down→up edge detector for the poll loop. Set true whenever a poll or send
    // hits a network failure; when a poll next SUCCEEDS we flip it and kick a
    // spool drain so alerts spooled during the outage go out the moment
    // connectivity returns — not only on the next daemon restart.
    private static volatile boolean connectivityWasDown = false;
    // Guards against overlapping drains (startup drain + a recovery drain, or
    // two rapid recovery edges) running the same spool dir concurrently.
    private static final AtomicBoolean spoolDrainInProgress = new AtomicBoolean(false);

    private static long lastUpdateId = 0;

    // Poll-error backoff. getUpdates errors (409 Conflict, 401 Unauthorized,
    // 429 rate-limit, 5xx) return a normal HTTP response — NOT an exception — so
    // they bypass the catch-block's 5s sleep in the poll loop. Without a backoff
    // here a persistent error (very common: a 409 when two pollers share one
    // token, or a revoked token 401) spins getUpdates every network RTT, 24/7
    // while parked — hundreds of MB/day of mobile data. This backoff sleeps on
    // every error return, doubling from BASE up to MAX, and resets to 0 on the
    // next successful poll. Read/written only on the single "TelegramPoll" thread.
    private static long pollErrorBackoffMs = 0;
    private static final long POLL_BACKOFF_BASE_MS = 5_000;
    private static final long POLL_BACKOFF_MAX_MS = 300_000;   // 5 min ceiling
    // 409/401 are terminal for the current token — a fixed poll cadence can never
    // clear them (they need operator action: kill the other poller / fix token).
    // Poll at this slow rate so we still notice when the condition resolves
    // (token replaced, other poller stopped) without burning data meanwhile.
    private static final long POLL_TERMINAL_ERROR_MS = 60_000;

    // Track processed update IDs to prevent duplicate processing
    private static final java.util.Set<Long> processedUpdateIds = 
        java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<Long, Boolean>() {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                return size() > 100; // Keep last 100 update IDs
            }
        });
    
    // Command router for modular command handling
    private static CommandRouter commandRouter;
    
    public static void main(String[] args) {
        int myUid = android.os.Process.myUid();
        int myPid = android.os.Process.myPid();
        
        // Configure DaemonLogger for daemon context (enable stdout for app_process)
        DaemonLogger.configure(DaemonLogger.Config.defaults()
            .withStdoutLog(true)
            .withFileLog(true)
            .withConsoleLog(true));
        
        logger = DaemonLogger.getInstance(TAG, PATH_DATA_LOCAL_TMP());
        
        log("=== Telegram Bot Daemon Starting ===");
        log("UID: " + myUid + " (expected: 2000 shell)");
        log("PID: " + myPid);
        
        // Kill any old instances using pkill -f (same pattern as DaemonLauncher.kt)
        killOldInstances(myPid);
        
        // CRITICAL: Acquire singleton lock - exit if another instance survived
        if (!acquireSingletonLock()) {
            log("ERROR: Another TelegramBotDaemon instance is already running. Exiting.");
            System.exit(1);
            return;
        }
        
        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        
        try {
            if (!loadConfig()) {
                // No token configured. Don't exit — that triggers the
                // watchdog's clean-exit branch, which has a hardcoded 10s
                // sleep with no exponential backoff. At ~360 starts/hour,
                // each one forking app_process + JVM init + class load,
                // that's a real CPU/disk drain on a parked car.
                //
                // Instead, park the daemon and poll for a token. When the
                // user pastes one into the web UI, refreshConfigFromUnified
                // picks it up and we proceed normally. This keeps the JVM
                // alive (cheap) and avoids the watchdog spawn churn.
                //
                // We still want IPC + greeting once the token arrives, so
                // we hand control to a token-wait loop and re-enter the
                // normal path on success.
                if (!waitForBotTokenAndReload()) {
                    // Looper was interrupted or daemon-shutdown signaled —
                    // exit cleanly so the watchdog respawns us. This is
                    // not the spam path; this is "user explicitly cleared
                    // token AND wants daemon disabled" which the watchdog
                    // sentinel handshake covers.
                    log("Token wait aborted; exiting");
                    return;
                }
                log("Bot token now available; proceeding with startup");
            }

            initHttpClient();
            initCommandRouter();
            startIpcServer();  // Start IPC server for app notifications
            startPolling();

            log("Daemon running, polling for updates...");
            Looper.loop();

        } catch (Exception e) {
            log("FATAL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Sit in the daemon waiting for a bot token to be configured. Polls
     * UnifiedConfigManager every 30s. Returns true once a token is present
     * (so main() can proceed with normal startup), false if interrupted.
     *
     * Stays inside the same JVM so we don't trigger the watchdog clean-exit
     * spawn-burn loop. 30s poll is cheap — just a forceReload + getter
     * call, no IPC.
     */
    private static boolean waitForBotTokenAndReload() {
        log("Bot token not configured — parking daemon (will poll every 30s)");
        long startMs = System.currentTimeMillis();
        long lastLogMs = startMs;
        while (running) {
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }

            try {
                com.overdrive.app.config.UnifiedConfigManager.forceReload();
                String tok = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getBotToken();
                if (tok != null && !tok.isEmpty()) {
                    botToken = tok;
                    ownerChatId = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getOwnerChatId();
                    videoUploadsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isVideoUploads();
                    criticalAlertsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isCriticalAlerts();
                    connectivityAlertsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isConnectivity();
                    motionTextEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isMotionText();
                    log("Token-wait: token detected after " + ((System.currentTimeMillis() - startMs) / 1000) + "s");
                    return true;
                }
            } catch (Exception e) {
                // Don't log per-iteration — config may not exist yet on a
                // very fresh install and that's expected. Surface every
                // 5 minutes so a permanent failure (filesystem error) is
                // visible.
                if (System.currentTimeMillis() - lastLogMs > 300_000L) {
                    log("Token-wait: config read error: " + e.getMessage());
                    lastLogMs = System.currentTimeMillis();
                }
            }
        }
        return false;
    }
    
    // ==================== DUPLICATE INSTANCE CLEANUP ====================
    
    /**
     * Kill old daemon instances using pkill (same approach as DaemonLauncher.kt).
     * Excludes our own PID to avoid killing ourselves.
     * Also cleans up stale lock file left by SIGKILL'd processes.
     */
    private static void killOldInstances(int myPid) {
        try {
            // Find and kill other telegram_bot_daemon processes, excluding our own PID.
            // pkill -9 -f would match our own process too (command line contains the pattern),
            // so we use ps + grep + awk to filter by PID instead.
            String killCmd = "ps -A -o PID,ARGS | grep -F telegram_bot_daemon | grep -v grep | awk '{print $1}' | while read pid; do " +
                    "if [ \"$pid\" != \"" + myPid + "\" ]; then kill -9 $pid 2>/dev/null; fi; done";
            execShell(killCmd);
            Thread.sleep(500);
            
            // Clean up stale lock file (SIGKILL doesn't trigger shutdown hooks)
            new java.io.File(LOCK_FILE).delete();
            
            log("Old instance cleanup complete (my PID: " + myPid + ")");
        } catch (Exception e) {
            log("Error killing old instances: " + e.getMessage());
        }
    }
    
    // ==================== SINGLETON LOCK ====================
    
    /**
     * Acquire a file lock to ensure only one daemon instance runs at a time.
     * Mirrors the CameraDaemon pattern with PID-cmdline validation:
     *   - empty / corrupt PID file       → stale, clean up + retry
     *   - holder PID is our own PID      → stale, clean up + retry
     *   - /proc/PID gone                 → dead PID, stale, retry
     *   - /proc/PID/cmdline NOT us       → recycled PID, stale, retry
     *   - /proc/PID/cmdline IS us        → real conflict, refuse
     *   - /proc/PID/cmdline UNREADABLE   → assume conflict (don't steal lock
     *                                      from a legit daemon under
     *                                      different UID / hidepid=2)
     *
     * The previous version was a single tryLock+exit-on-failure, which on
     * a clean SIGKILL of the prior incarnation could race the kernel's
     * lock-release: killOldInstances did kill -9 + 500ms sleep, but a
     * D-state I/O hang on slow flash can hold the inode lock past that.
     * Result: every subsequent retry succeeded, but the first 1–N retries
     * after a kill burned 3–60s of watchdog backoff per instance.
     * The retry-after-stale-cleanup loop here collapses that to a single
     * start.
     */
    private static boolean acquireSingletonLock() {
        try {
            java.io.File lockFileObj = new java.io.File(LOCK_FILE);
            lockFileHandle = new java.io.RandomAccessFile(lockFileObj, "rw");
            java.nio.channels.FileChannel channel = lockFileHandle.getChannel();

            fileLock = channel.tryLock();

            if (fileLock == null) {
                boolean stale = false;
                String reason = null;
                try {
                    lockFileHandle.seek(0);
                    String pidStr = lockFileHandle.readLine();
                    int myPid = android.os.Process.myPid();
                    if (pidStr == null || pidStr.trim().isEmpty()) {
                        stale = true;
                        reason = "empty lock file";
                    } else {
                        int pid = Integer.parseInt(pidStr.trim());
                        if (pid == myPid) {
                            stale = true;
                            reason = "lock held by our own PID (previous crash)";
                        } else if (!new java.io.File("/proc/" + pid).exists()) {
                            stale = true;
                            reason = "dead PID " + pid;
                        } else {
                            CmdlineMatch match = classifyCmdline(pid);
                            if (match == CmdlineMatch.MATCH) {
                                log("Singleton: live daemon PID " + pid
                                        + " holds the lock (cmdline="
                                        + readProcCmdline(pid) + ")");
                                try { lockFileHandle.close(); } catch (Exception ignored) {}
                                return false;
                            }
                            if (match == CmdlineMatch.UNKNOWN) {
                                // Different UID / hidepid=2 — refuse to
                                // steal. Watchdog backoff handles the retry.
                                log("Singleton: PID " + pid + " holds the lock"
                                        + " but cmdline is unreadable — refusing");
                                try { lockFileHandle.close(); } catch (Exception ignored) {}
                                return false;
                            }
                            stale = true;
                            reason = "PID " + pid + " is alive but not a TelegramBotDaemon"
                                    + " (cmdline=" + readProcCmdline(pid) + ")";
                        }
                    }
                } catch (NumberFormatException nfe) {
                    stale = true;
                    reason = "corrupt PID in lock file";
                } catch (Exception e) {
                    log("Singleton: lock-file inspection failed: " + e.getMessage());
                    try { lockFileHandle.close(); } catch (Exception ignored) {}
                    return false;
                }

                if (stale) {
                    log("Singleton: stale lock (" + reason + ") — cleaning up");
                    try { lockFileHandle.close(); } catch (Exception ignored) {}
                    lockFileObj.delete();

                    // Bounded retry loop. The kernel may not have released
                    // the inode-level lock the instant the holder process
                    // dies — D-state I/O hangs on slow flash can hold it
                    // for up to a few hundred ms past SIGKILL. Try 5 times
                    // with 200ms backoff before giving up.
                    boolean acquired = false;
                    for (int attempt = 0; attempt < 5; attempt++) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                        try {
                            lockFileHandle = new java.io.RandomAccessFile(lockFileObj, "rw");
                            channel = lockFileHandle.getChannel();
                            fileLock = channel.tryLock();
                            if (fileLock != null) {
                                acquired = true;
                                break;
                            }
                            try { lockFileHandle.close(); } catch (Exception ignored) {}
                        } catch (Exception e) {
                            // Filesystem error / lock-on-deleted-inode race —
                            // try again. On exhausting attempts we return false
                            // and let the watchdog back off.
                        }
                    }
                    if (!acquired) {
                        log("Singleton: retry-after-stale-cleanup exhausted (5x 200ms)");
                        return false;
                    }
                }
            }

            lockFileHandle.seek(0);
            lockFileHandle.setLength(0);
            lockFileHandle.writeBytes(String.valueOf(android.os.Process.myPid()));

            log("Acquired singleton lock (PID: " + android.os.Process.myPid() + ")");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                releaseSingletonLock();
            }));

            return true;

        } catch (java.nio.channels.OverlappingFileLockException e) {
            log("Lock already held by this process");
            return false;
        } catch (Exception e) {
            log("Failed to acquire singleton lock: " + e.getMessage());
            return false;
        }
    }

    private enum CmdlineMatch { MATCH, NO_MATCH, UNKNOWN }

    /**
     * Classify a PID's /proc/<pid>/cmdline. Distinguishes "definitely not
     * us" (recycled PID) from "we can't tell" (different UID / hidepid=2).
     * The distinction matters because we MUST NOT steal a lock from a
     * legitimately-running daemon under a different UID.
     */
    private static CmdlineMatch classifyCmdline(int pid) {
        java.io.File f = new java.io.File("/proc/" + pid + "/cmdline");
        if (!f.exists()) return CmdlineMatch.NO_MATCH; // PID gone
        if (!f.canRead()) return CmdlineMatch.UNKNOWN; // hidepid / EACCES
        String cmdline = readProcCmdline(pid);
        if (cmdline.isEmpty()) return CmdlineMatch.NO_MATCH; // kernel thread / race
        return isTelegramDaemonCmdline(cmdline) ? CmdlineMatch.MATCH : CmdlineMatch.NO_MATCH;
    }

    /**
     * Read /proc/<pid>/cmdline streaming-to-EOF. /proc/.../cmdline reports
     * stat()-size=0 on most kernels even when populated, so size-hinted
     * reads short-read. NUL bytes are turned into spaces.
     */
    private static String readProcCmdline(int pid) {
        java.io.File f = new java.io.File("/proc/" + pid + "/cmdline");
        if (!f.exists()) return "";
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while (total < buf.length && (n = fis.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            if (total == 0) return "";
            StringBuilder sb = new StringBuilder(total);
            for (int i = 0; i < total; i++) {
                byte b = buf[i];
                sb.append(b == 0 ? ' ' : (char) (b & 0xff));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Anchored cmdline match. The legitimate ways this daemon shows up:
     *   - argv[0] after kernel applies nice-name: "telegram_bot_daemon"
     *   - app_process invocation: "...--nice-name=telegram_bot_daemon..."
     *   - some launchers append the FQCN as entry-point arg
     */
    private static boolean isTelegramDaemonCmdline(String cmdline) {
        if (cmdline == null || cmdline.isEmpty()) return false;
        return cmdline.contains("telegram_bot_daemon")
                || cmdline.contains("com.overdrive.app.daemon.TelegramBotDaemon");
    }
    
    /**
     * Release the singleton lock on shutdown.
     */
    private static void releaseSingletonLock() {
        try {
            if (fileLock != null) {
                fileLock.release();
                fileLock = null;
            }
            if (lockFileHandle != null) {
                lockFileHandle.close();
                lockFileHandle = null;
            }
            new java.io.File(LOCK_FILE).delete();
        } catch (Exception e) {
            log("Error releasing singleton lock: " + e.getMessage());
        }
    }
    
    private static void log(String msg) {
        if (logger != null) {
            logger.info(msg);
        }
        // Note: System.out.println is now handled by DaemonLogger when enableStdoutLog is true
    }
    
    // ==================== INITIALIZATION ====================
    
    private static boolean loadConfig() {
        try {
            // Ensure UnifiedConfigManager is initialized in this process —
            // the daemon is launched fresh via app_process and doesn't share
            // the app's static state. init() is a no-op when the file already
            // exists and triggers legacy migration if it doesn't.
            com.overdrive.app.config.UnifiedConfigManager.init();
            // Pull any state still in /data/local/tmp/telegram_config.properties
            // into the unified store. Idempotent — no-op after first call.
            com.overdrive.app.telegram.config.UnifiedTelegramConfig.migrateLegacyIfNeeded();

            // One-shot: re-encrypt a legacy firmware-fingerprint-bound token
            // under the stable device-id-only key so the NEXT OTA can't strand
            // it. Runs from this UID-2000 process which owns the config write.
            // No-op once stable. Must happen before getBotToken so this run
            // already reads the re-encrypted value.
            try {
                if (com.overdrive.app.telegram.config.UnifiedTelegramConfig.reEncryptBotTokenIfLegacy()) {
                    log("Bot token re-encrypted under stable key (was legacy fingerprint-bound)");
                }
            } catch (Exception e) {
                log("Bot token re-encrypt check failed: " + e.getMessage());
            }

            botToken = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getBotToken();
            if (botToken == null || botToken.isEmpty()) {
                // Distinguish "ciphertext present but undecryptable" (firmware
                // changed before re-encrypt, or device-id file missing) from
                // "genuinely not configured" so the silent failure stops being
                // invisible. Either way we park in waitForBotTokenAndReload —
                // re-entering the token from the UI rewrites it under the
                // stable key and recovers.
                if (com.overdrive.app.telegram.config.UnifiedTelegramConfig.botTokenPresentButUndecryptable()) {
                    log("ERROR: bot token is stored but could NOT be decrypted "
                            + "(firmware/OTA changed the key, or "
                            + "/data/local/tmp/.byd_device_id is missing/unreadable). "
                            + "Re-enter the token in the Telegram settings to recover.");
                } else {
                    log("bot_token not set in unified config");
                }
                return false;
            }

            ownerChatId = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getOwnerChatId();
            videoUploadsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isVideoUploads();
            criticalAlertsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isCriticalAlerts();
            connectivityAlertsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isConnectivity();
            motionTextEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isMotionText();

            log("Config loaded: token=***" + botToken.substring(Math.max(0, botToken.length() - 6)));
            log("Owner chat ID: " + (ownerChatId > 0 ? ownerChatId : "not set"));
            log("Video uploads: " + (videoUploadsEnabled ? "enabled" : "disabled")
                + ", critical: " + criticalAlertsEnabled
                + ", connectivity: " + connectivityAlertsEnabled
                + ", motionText: " + motionTextEnabled);

            return true;
        } catch (Exception e) {
            log("Config load error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Re-read shared state from the unified config. Called once per long-poll
     * cycle so a token clear, owner unpair, or preference toggle made by the
     * app/web UI takes effect within a single poll interval rather than
     * requiring a daemon restart. forceReload bypasses the in-process mtime
     * cache so a write made by the OTHER UID is picked up immediately.
     *
     * Logs only on transitions to keep the log file readable — every poll
     * cycle would otherwise emit identical "token / owner / video_uploads"
     * lines forever.
     */
    private static void refreshConfigFromUnified() {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();

            // Same corruption-recovery guard as freshSnapshotForIpc: a
            // missing/corrupt config makes loadConfig() return defaults (empty
            // telegram section) WITHOUT throwing. Reading the getters off that
            // would log "Owner changed → cleared" and zero ownerChatId — and if
            // the daemon (UID 2000) repair-writes those defaults with no usable
            // .bak, the owner is lost permanently. Skip the refresh when the
            // section is empty; the cached values stand until a real read.
            org.json.JSONObject section =
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.load();
            if (section == null || section.length() == 0) {
                return;
            }

            String newToken = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getBotToken();
            long newOwner = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getOwnerChatId();
            boolean newVideo = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isVideoUploads();
            boolean newCritical = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isCriticalAlerts();
            boolean newConnectivity = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isConnectivity();
            boolean newMotionText = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isMotionText();

            if (newToken != null && !newToken.isEmpty() && !newToken.equals(botToken)) {
                log("Bot token changed from unified config; updating");
                botToken = newToken;
            } else if ((newToken == null || newToken.isEmpty())
                    && botToken != null && !botToken.isEmpty()) {
                // Token was CLEARED in the UI (clearAll) while the daemon runs.
                // Without this the stale token persisted in memory and the bot
                // kept long-polling the revoked token, answering /pair and
                // re-binding a new owner — the user thinks the integration is
                // wiped but it stays reachable until process restart. Clearing
                // the in-memory token lets the pollUpdates empty-token sleep
                // guard take over so the daemon stops hitting the revoked token.
                log("Bot token cleared from unified config; stopping polling against revoked token");
                botToken = "";
            }
            if (newOwner != ownerChatId) {
                log("Owner changed: " + ownerChatId + " → " + (newOwner > 0 ? newOwner : "cleared"));
                ownerChatId = newOwner;
            }
            if (newVideo != videoUploadsEnabled) {
                log("Video uploads changed: " + videoUploadsEnabled + " → " + newVideo);
                videoUploadsEnabled = newVideo;
            }
            if (newCritical != criticalAlertsEnabled) {
                log("Critical alerts changed: " + criticalAlertsEnabled + " → " + newCritical);
                criticalAlertsEnabled = newCritical;
            }
            if (newConnectivity != connectivityAlertsEnabled) {
                log("Connectivity alerts changed: " + connectivityAlertsEnabled + " → " + newConnectivity);
                connectivityAlertsEnabled = newConnectivity;
            }
            if (newMotionText != motionTextEnabled) {
                log("Motion text alerts changed: " + motionTextEnabled + " → " + newMotionText);
                motionTextEnabled = newMotionText;
            }
        } catch (Exception e) {
            // Don't kill the poll loop on a config read blip.
            log("refreshConfigFromUnified error: " + e.getMessage());
        }
    }

    // volatile: read/written by the poll thread and IPC worker threads (via
    // onHttpFailure in a send catch-block). The rebuild itself is serialized by
    // the monitor on refreshHttpClient/onHttpFailure so two threads can't
    // concurrently build + orphan a client.
    private static volatile long lastProxyCheckTime = 0;
    private static volatile boolean lastProxyState = false; // true = proxy was available

    private static void initHttpClient() {
        refreshHttpClient();
    }

    /**
     * Refresh HTTP client with current proxy settings.
     * Called on init and after connection failures to pick up proxy changes.
     * synchronized so a poll-thread proxy switch and a worker-thread
     * onHttpFailure can't both rebuild + race the httpClient assignment
     * (orphaning the loser's idle connection-pool threads).
     */
    private static synchronized void refreshHttpClient() {
        lastProxyCheckTime = System.currentTimeMillis();

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);

        // Check for global proxy settings
        java.net.Proxy proxy = getGlobalProxy();
        boolean proxyAvailable = (proxy != null);

        if (proxyAvailable) {
            builder.proxy(proxy);
            if (!lastProxyState) {
                log("HTTP client switched to proxy: " + proxy.address());
            }
        } else {
            if (lastProxyState) {
                log("HTTP client switched to direct connection (proxy gone)");
            }
        }

        lastProxyState = proxyAvailable;
        OkHttpClient base = builder.build();
        httpClient = base;
        // Upload client: same proxy + shared pool/dispatcher (newBuilder reuses
        // them), only the write/read windows widened for slow multi-MB pushes.
        // Rebuilt here (not lazily) so a mid-session proxy switch can never
        // leave the upload path pinned to a stale proxy.
        uploadHttpClient = base.newBuilder()
                .writeTimeout(UPLOAD_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(UPLOAD_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * Write/read timeouts for the heavy-upload client. A 40s clip at a poor
     * uplink can take minutes to push; 30s (the base client's writeTimeout)
     * guaranteed a mid-upload SocketTimeout and a dropped video. These bound a
     * single stalled socket operation (no progress for this long ⇒ give up),
     * NOT total upload duration — a slow-but-steady upload keeps going. The
     * upload still runs on the isolated IPC_MEDIA_WORKERS lane, so a long push
     * never delays a text/photo/critical alert.
     */
    private static final long UPLOAD_WRITE_TIMEOUT_SEC = 180L;
    private static final long UPLOAD_READ_TIMEOUT_SEC = 120L;

    /**
     * Invalidate HTTP client so next request re-checks proxy and flushes stale pool.
     * Called on connection failures.
     */
    private static synchronized void onHttpFailure() {
        try {
            if (httpClient != null) {
                httpClient.connectionPool().evictAll();
            }
            if (uploadHttpClient != null) {
                uploadHttpClient.connectionPool().evictAll();
            }
        } catch (Throwable ignored) {}

        long elapsed = System.currentTimeMillis() - lastProxyCheckTime;
        if (elapsed > 5_000) {
            refreshHttpClient();
        }
    }

    /**
     * Classify a send-path exception as DEFINITIVELY-UNDELIVERED (true) — the
     * request provably never reached Telegram, so replaying it cannot
     * duplicate — versus AMBIGUOUS/other (false), where the POST may already
     * have been delivered and a replay would double-send.
     *
     * <p>Undelivered ⇔ the failure happened before any request byte left the
     * device: DNS didn't resolve ({@link java.net.UnknownHostException}) or the
     * TCP connect was refused/timed out ({@link java.net.ConnectException}, or a
     * {@link java.net.SocketTimeoutException} whose message names the CONNECT
     * phase). A plain read/write {@code SocketTimeoutException} after connect is
     * AMBIGUOUS and returns false — the exact seam the emit-side spool already
     * refuses to cross (TelegramNotifier's {@code connected} flag). This mirrors
     * that boundary daemon-side so retry/spool inherits the same no-dup safety.
     */
    private static boolean isDefinitivelyUndelivered(Throwable e) {
        if (e instanceof java.net.UnknownHostException) return true;
        if (e instanceof java.net.ConnectException) return true;
        if (e instanceof java.net.SocketTimeoutException) {
            // OkHttp labels a pre-connect timeout with a message that mentions
            // "connect" (e.g. "failed to connect to api.telegram.org/… after
            // 30000ms"); a post-connect stall reads "timeout"/"Read timed
            // out"/"timeout ... after Nms" WITHOUT "connect". Only the connect
            // phase is dup-safe to replay.
            String m = e.getMessage();
            return m != null && m.toLowerCase(java.util.Locale.US).contains("connect");
        }
        return false;
    }

    /**
     * IPC actions whose ORIGINAL command is dup-safe to spool for later replay
     * when a live send fails undelivered. Restricted to owner-gated TEXT/PHOTO
     * alerts (door/critical/proximity/motion). Deliberately EXCLUDES:
     * <ul>
     *   <li>sendVideo / sendDocument — a stale multi-MB clip replayed after the
     *       car has driven off is undesirable (matches the emit-side rule that
     *       fire-and-forget video is never spooled); the widened upload timeout
     *       (Fix #1) is the video path's resilience.</li>
     *   <li>notifyTunnel — throttled + tied to a live URL that rotates; a
     *       replayed old URL would be worse than useless.</li>
     *   <li>ping / query commands — no user-facing payload.</li>
     * </ul>
     * The replay re-enters {@link #processIpcCommand}, which re-applies the
     * category/owner gate and file-existence check, so a since-muted category
     * or since-deleted hero still drops correctly.
     */
    private static boolean isSpoolableOnUndelivered(String action) {
        switch (action) {
            case "sendMessage":
            case "notifyMotion":
            case "notifyMotionFinalized":
            case "notifyCritical":
                return true;
            default:
                return false;
        }
    }

    /** Backoff before the single in-send retry on an undelivered failure. */
    private static final long SEND_RETRY_BACKOFF_MS = 1500L;

    /**
     * Record that the current thread's send failed DEFINITIVELY-UNDELIVERED
     * (safe to replay). Read by {@link #processIpcCommand} to decide whether to
     * spool the original command. The connectivity-recovery edge is armed
     * separately — only AFTER an entry is actually spooled (see the spool block
     * in processIpcCommand) — so a poll succeeding between this flag and the
     * offer() write can't drain-then-miss the not-yet-written entry, and a
     * drain is never armed when there was nothing to spool.
     */
    private static void markSendUndelivered() {
        lastSendUndelivered.set(Boolean.TRUE);
    }

    /**
     * Get global HTTP proxy from Android settings.
     * Reads from: settings get global http_proxy (format: host:port)
     */
    private static java.net.Proxy getGlobalProxy() {
        try {
            if (com.overdrive.app.mqtt.ProxyHelper.isProxyAvailable()) {
                java.net.Proxy proxy = com.overdrive.app.mqtt.ProxyHelper.getHttpProxy();
                if (proxy != null) {
                    log("Using sing-box/Tailscale proxy for Telegram: " + proxy.address());
                    return proxy;
                }
            }
        } catch (Throwable t) {
            log("ProxyHelper probe error: " + t.getMessage());
        }

        try {
            String proxyStr = execShell("settings get global http_proxy 2>/dev/null");
            if (proxyStr == null || proxyStr.trim().isEmpty() || proxyStr.trim().equals("null") || proxyStr.trim().equals(":0")) {
                return null;
            }
            
            proxyStr = proxyStr.trim();
            String[] parts = proxyStr.split(":");
            if (parts.length != 2) {
                return null;
            }
            
            String host = parts[0];
            int port;
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
            
            if (host.isEmpty() || port <= 0) {
                return null;
            }
            
            log("Found global proxy: " + host + ":" + port);
            return new java.net.Proxy(java.net.Proxy.Type.HTTP, new java.net.InetSocketAddress(host, port));
        } catch (Exception e) {
            log("Error reading proxy settings: " + e.getMessage());
            return null;
        }
    }
    
    private static void initCommandRouter() {
        commandRouter = new CommandRouter(new CommandContext() {
            @Override
            public boolean sendMessage(long chatId, String text) {
                return TelegramBotDaemon.sendMessage(chatId, text);
            }
            
            @Override
            public boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) {
                return TelegramBotDaemon.sendMessageWithButtons(chatId, text, buttons);
            }
            
            @Override
            public boolean sendVideo(long chatId, String videoPath, String caption) {
                return TelegramBotDaemon.sendVideo(chatId, videoPath, caption);
            }

            @Override
            public boolean sendDocument(long chatId, String filePath, String caption) {
                return TelegramBotDaemon.sendDocument(chatId, filePath, caption);
            }

            @Override
            public JSONObject sendIpcCommand(int port, JSONObject command) {
                return TelegramBotDaemon.sendIpcCommand(port, command);
            }

            @Override
            public JSONObject sendIpcCommand(int port, JSONObject command, int timeoutMs) {
                return TelegramBotDaemon.sendIpcCommand(port, command, timeoutMs);
            }


            @Override
            public String execShell(String command) {
                return TelegramBotDaemon.execShell(command);
            }

            @Override
            public boolean spawnDetached(String command) {
                return TelegramBotDaemon.spawnDetached(command);
            }

            @Override
            public void log(String message) {
                TelegramBotDaemon.log(message);
            }
        });
    }

    /**
     * Spawn a long-lived process detached. Mirrors AppUpdater.runDetachedInstall
     * (AppUpdater.java:741-745). Use this — never execShell — for any command
     * that backgrounds a daemon (app_process, cloudflared, zrok, tailscaled,
     * sing-box, watchdog scripts). execShell drains stdout to EOF, which
     * blocks forever when the grandchild keeps the inherited fd open.
     *
     * Returns true on successful spawn (the inner command may still fail —
     * we don't wait to find out), false if the parent shell couldn't start.
     */
    private static boolean spawnDetached(String command) {
        // (... &) wrapper backgrounds inside a subshell that exits in ms,
        // reparenting the grandchild to init.
        // </dev/null on the inner command + ProcessBuilder Redirects close
        // every stdio descriptor so no pipe is held open by the parent.
        String wrapped = "(" + command + " </dev/null &)";
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", wrapped);
            pb.redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
            pb.redirectError(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
            pb.start();
            return true;
        } catch (java.io.IOException e) {
            log("spawnDetached failed: " + e.getMessage());
            return false;
        }
    }
    
    // ==================== IPC SERVER ====================
    
    /**
     * Start IPC server to receive notification requests from app process.
     * Listens on localhost:19877 for JSON commands.
     */
    /**
     * IPC concurrency is split into three lanes so a slow handler can never
     * starve a fast one (head-of-line isolation):
     *
     * <ul>
     *   <li>{@link #IPC_WORKERS} — the accept-dispatched READ/ROUTE lane. Its
     *       only job is to read the (single-line) request and hand the parsed
     *       command to the right processing lane. Reads are sub-millisecond, so
     *       2 threads are plenty and this lane never runs a blocking send.</li>
     *   <li>{@link #IPC_TEXT_WORKERS} — text/control commands (sendMessage,
     *       notifyMotion, notifyCritical, notifyTunnel, ping). These can now
     *       sleep up to 30s on a 429 retry, so they get their own pool.</li>
     *   <li>{@link #IPC_PHOTO_WORKERS} — the finalized motion PHOTO
     *       (notifyMotionFinalized). The hero JPEG is small/fast vs a clip and
     *       is the user's primary "what happened" alert, so it gets its OWN lane
     *       — never queued behind video uploads (the bug that made the photo of
     *       a subsequent event arrive seconds-to-minutes stale when video is on)
     *       nor behind motion-start text.</li>
     *   <li>{@link #IPC_MEDIA_WORKERS} — heavy uploads (sendVideo, sendDocument)
     *       which can sleep up to 60s on 429. A stuck clip upload here cannot
     *       delay a motion-text, photo, or critical alert.</li>
     * </ul>
     *
     * The chosen processing lane owns the socket lifecycle (writes the response
     * and closes). All daemon threads so they don't pin shutdown.
     */
    private static final java.util.concurrent.ExecutorService IPC_WORKERS =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "TelegramIPCWorker");
                t.setDaemon(true);
                return t;
            });

    private static final java.util.concurrent.ExecutorService IPC_TEXT_WORKERS =
            java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "TelegramIPCText");
                t.setDaemon(true);
                return t;
            });

    private static final java.util.concurrent.ExecutorService IPC_PHOTO_WORKERS =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "TelegramIPCPhoto");
                t.setDaemon(true);
                return t;
            });

    private static final java.util.concurrent.ExecutorService IPC_MEDIA_WORKERS =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "TelegramIPCMedia");
                t.setDaemon(true);
                return t;
            });

    /** Heavy file uploads (clip/document) — the slow lane. */
    private static boolean isMediaIpcCommand(String action) {
        return "sendVideo".equals(action)
                || "sendDocument".equals(action);
    }

    /** The finalized hero PHOTO — its own lane, isolated from clips and text. */
    private static boolean isPhotoIpcCommand(String action) {
        return "notifyMotionFinalized".equals(action);
    }

    private static void startIpcServer() {
        Thread ipcThread = new Thread(() -> {
            log("IPC server starting on port " + IPC_PORT);

            while (running) {
                ServerSocket serverSocket = null;
                try {
                    // setReuseAddress MUST precede bind() to take effect — the
                    // old `new ServerSocket(port,...)` binds immediately, so the
                    // subsequent setReuseAddress was a no-op and a fast
                    // SIGKILL→respawn (AccSentry kills on ACC-ON) could hit a
                    // TIME_WAIT BindException and burn the 5s retry window
                    // (during which the daemon is alive but NOT listening, so
                    // notifications drop/spool-late). Create unbound → reuse →
                    // bind, matching SurveillanceIpcServer/AacIngestServer.
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new InetSocketAddress(
                            InetAddress.getByName("127.0.0.1"), IPC_PORT), 5);
                    log("IPC server listening on 127.0.0.1:" + IPC_PORT);

                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            // Dispatch off the accept thread so a slow handler
                            // (e.g. sendPhoto sleeping 30s on 429 retry) can't
                            // block subsequent IPC commands.
                            IPC_WORKERS.execute(() -> handleIpcClient(client));
                        } catch (Exception e) {
                            if (running) {
                                log("IPC accept error: " + e.getMessage());
                            }
                        }
                    }
                } catch (java.net.BindException e) {
                    log("IPC port " + IPC_PORT + " in use, retrying...");
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                } catch (Exception e) {
                    log("IPC server error: " + e.getMessage());
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                } finally {
                    if (serverSocket != null) {
                        try { serverSocket.close(); } catch (Exception ignored) {}
                    }
                }
            }
            
            log("IPC server stopped");
        }, "TelegramIPC");
        ipcThread.setDaemon(true);
        ipcThread.start();
    }
    
    /**
     * Read+route step (runs on {@link #IPC_WORKERS}). Reads the one-line request
     * and hands processing to the text or media lane. The processing step owns
     * writing the response and closing the socket, so a slow upload occupies a
     * media thread — never the read lane or a text thread.
     */
    private static void handleIpcClient(Socket client) {
        String line;
        try {
            client.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            line = reader.readLine();
        } catch (Exception e) {
            log("IPC read error: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
            return;
        }

        if (line == null) {
            try { client.close(); } catch (Exception ignored) {}
            return;
        }

        log("IPC received: " + line);
        final String reqLine = line;
        final JSONObject cmd;
        String action;
        try {
            cmd = new JSONObject(reqLine);
            action = cmd.optString("cmd", "");
        } catch (Exception e) {
            log("IPC parse error: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
            return;
        }

        java.util.concurrent.ExecutorService lane =
                isMediaIpcCommand(action) ? IPC_MEDIA_WORKERS
                        : isPhotoIpcCommand(action) ? IPC_PHOTO_WORKERS
                        : IPC_TEXT_WORKERS;
        try {
            lane.execute(() -> processAndRespond(client, cmd));
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            // The lane pools are fixed-size with UNBOUNDED queues and are never
            // shutdown() in normal operation, so execute() does not reject on
            // saturation — under a wedged worker, commands QUEUE (head-of-line
            // growth) rather than reject. This catch therefore only fires post-
            // shutdown; handling inline there avoids dropping the command. (We
            // intentionally do NOT bound the queues + CallerRunsPolicy: that
            // would run a 60s upload inline on the read/route lane and stall new
            // command parsing — worse than queueing. The client side already has
            // a connect timeout + spool fallback for the wedged-daemon case.)
            processAndRespond(client, cmd);
        }
    }

    /** Process step (runs on a text/media lane): handle + write response + close. */
    private static void processAndRespond(Socket client, JSONObject cmd) {
        try {
            PrintWriter writer = new PrintWriter(client.getOutputStream(), true);
            JSONObject response = processIpcCommand(cmd);
            writer.println(response.toString());
        } catch (Exception e) {
            log("IPC client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }
    
    /**
     * Map a `category` string from a `sendMessage` IPC payload onto the
     * matching pref toggle. Unknown categories are treated as CRITICAL —
     * the safest default for ad-hoc engine messages whose author hasn't
     * been categorised yet (proximity alerts, manual sends, etc.). The
     * flags are refreshed once per long-poll cycle by
     * {@link #refreshConfigFromUnified()}, so a UI toggle takes effect
     * within ~30s without restarting the daemon.
     */
    private static boolean isCategoryEnabled(String category) {
        if (category == null) return criticalAlertsEnabled;
        switch (category) {
            case "MOTION":       return motionTextEnabled;
            case "CONNECTIVITY": return connectivityAlertsEnabled;
            case "VIDEO":        return videoUploadsEnabled;
            case "CRITICAL":
            default:             return criticalAlertsEnabled;
        }
    }

    /**
     * Refresh the gate state + owner from the unified config for a single IPC
     * command. Unlike {@link #refreshConfigFromUnified()} (poll-cycle cadence,
     * logs transitions) this is silent and runs per command so a just-flipped
     * toggle or unpair is honoured immediately. Best-effort: on any read error
     * the existing cached statics stand, so a transient config-read blip never
     * silences the daemon. The fields are volatile so the update is visible to
     * the rest of this handler (same thread) and to peers.
     */
    private static void freshSnapshotForIpc() {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            // Guard against a corruption/missing-file recovery: loadConfig()
            // returns createDefaultConfig() WITHOUT throwing, which seeds an
            // EMPTY telegram section. If we blindly read the getters off that,
            // ownerChatId would silently reset to -1 and every owner-gated send
            // would drop — the exact "silence the daemon" outcome we want to
            // avoid. Only adopt the fresh values when the reloaded section
            // actually carries real data (≥1 key); otherwise keep the cached
            // statics (which the poll-cycle refresh and startup load populated).
            org.json.JSONObject section =
                    com.overdrive.app.telegram.config.UnifiedTelegramConfig.load();
            if (section == null || section.length() == 0) {
                log("freshSnapshotForIpc: empty/recovery telegram section — keeping cached gate state");
                return;
            }
            ownerChatId = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getOwnerChatId();
            videoUploadsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isVideoUploads();
            criticalAlertsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isCriticalAlerts();
            connectivityAlertsEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isConnectivity();
            motionTextEnabled = com.overdrive.app.telegram.config.UnifiedTelegramConfig.isMotionText();
        } catch (Exception e) {
            // Keep the last-known cached values; do not silence on a blip.
            log("freshSnapshotForIpc read error (using cached gate state): " + e.getMessage());
        }
    }

    private static JSONObject processIpcCommand(JSONObject cmd) {
        JSONObject response = new JSONObject();
        // Reset the per-thread undelivered flag at command entry so it can only
        // ever reflect a send made WITHIN this command — never a stale value
        // from a prior command handled on the same reused worker thread (which
        // could otherwise trigger a false spool on a command that errored for a
        // non-network reason, e.g. "Missing chatId").
        lastSendUndelivered.set(Boolean.FALSE);
        try {
            String action = cmd.optString("cmd", "");

            // Re-read the gate state + owner FRESH before evaluating any gate.
            // The poll-cycle cache (refreshConfigFromUnified) lags up to ~30s,
            // which (a) dropped the first event after a category was just
            // enabled and (b) on unpair could deliver to the just-removed
            // owner's chat. A single cross-UID forceReload + getter here bounds
            // staleness to one read — the same cheap pattern the emit side
            // (TelegramNotifier.isEnabled) already relies on. Falls through to
            // the cached statics on any read error so a config blip can't
            // silence the daemon.
            freshSnapshotForIpc();

            switch (action) {
                case "sendMessage":
                    // Engine-issued ad-hoc messages (proximity alerts, manual
                    // sends from app code). Default to the criticalAlerts
                    // category — turning that toggle off should silence
                    // everything except the typed command paths below, which
                    // declare their own category. Callers who want a different
                    // category can pass an explicit `category` field.
                    long chatId = cmd.optLong("chatId", ownerChatId);
                    String text = cmd.optString("text", "");
                    String msgCategory = cmd.optString("category", "CRITICAL");
                    if (!isCategoryEnabled(msgCategory)) {
                        log("sendMessage skipped - " + msgCategory + " disabled");
                        response.put("status", "skipped");
                        response.put("message", msgCategory + " disabled");
                        break;
                    }
                    if (chatId > 0 && !text.isEmpty()) {
                        boolean ok = sendMessage(chatId, text);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Missing chatId or text");
                    }
                    break;

                case "sendVideo":
                    // Check if video uploads are enabled
                    if (!videoUploadsEnabled) {
                        log("Video upload skipped - auto-upload disabled in settings");
                        response.put("status", "skipped");
                        response.put("message", "Video uploads disabled");
                        break;
                    }

                    long videoChatId = cmd.optLong("chatId", ownerChatId);
                    String videoPath = cmd.optString("path", "");
                    String caption = cmd.optString("caption", "");
                    if (videoChatId > 0 && !videoPath.isEmpty()) {
                        boolean ok = sendVideo(videoChatId, videoPath, caption);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Missing chatId or path");
                    }
                    break;

                case "notifyTunnel":
                    if (!connectivityAlertsEnabled) {
                        log("notifyTunnel skipped - connectivity updates disabled");
                        response.put("status", "skipped");
                        response.put("message", "Connectivity updates disabled");
                        break;
                    }
                    String url = cmd.optString("url", "");
                    boolean isNew = cmd.optBoolean("isNew", true);
                    log("IPC notifyTunnel: url=" + url + ", isNew=" + isNew);
                    if (!url.isEmpty() && ownerChatId > 0) {
                        // Critical section: serialize peek→throttle→consume→
                        // send→stamp so two parallel IPC workers cannot both
                        // bypass the post-update throttle and double-send.
                        // See TUNNEL_NOTIFY_LOCK comment for rationale.
                        synchronized (TUNNEL_NOTIFY_LOCK) {
                            // Throttle: when cloudflared health-check is in a
                            // restart loop (process keeps dying — bad network,
                            // edge issue, OOM), each respawn rotates the
                            // trycloudflare.com hostname → new IPC notify →
                            // would otherwise be one Telegram message per
                            // restart cycle (worst case ~120/hour). Skip if
                            // we sent a tunnel-URL message in the last 10 min,
                            // UNLESS this is the post-update rotation (user
                            // genuinely wants the "URL changed because of
                            // update" explanation NOW, not delayed by 10 min).
                            // Peek the hint file FIRST so we can decide whether
                            // to bypass; consume only on actual send so a
                            // throttle-suppressed call doesn't silently swallow
                            // the hint and deny the next legit notify its
                            // post-update framing.
                            boolean postUpdatePresent = new File(
                                    "/data/local/tmp/overdrive_post_update_pending_telegram"
                                ).exists();
                            // A FAILED install (Telegram-triggered) leaves the
                            // failure hint instead of the success hint — bypass
                            // the throttle for that too so the owner learns the
                            // install failed NOW, not 10 min later (symmetric
                            // with the post-update success bypass above).
                            boolean installFailedPresent = new File(
                                    "/data/local/tmp/overdrive_install_failed_pending_telegram"
                                ).exists();
                            if (!postUpdatePresent
                                    && !installFailedPresent
                                    && shouldThrottleTunnelNotify()) {
                                log("notifyTunnel throttled (URL-rotation loop suppression): "
                                        + url);
                                // Still update tunnel_url.txt so /url command
                                // returns the current URL even when throttled.
                                saveTunnelUrl(url);
                                response.put("status", "skipped");
                                response.put("message", "throttled");
                                break;
                            }

                            // Save URL to file for /url command
                            saveTunnelUrl(url);

                            // Now consume the hint (if present). After
                            // consumption, the hint is gone and subsequent
                            // tunnel restarts go back to the generic copy.
                            // Success and failure hints are mutually exclusive
                            // (the detached install script never leaves both).
                            String postUpdateVersion = consumePostUpdateHint();
                            String installFailedReason = consumeInstallFailedHint();

                            // Surface a Telegram-triggered install FAILURE first,
                            // then fall through to the generic URL-changed copy so
                            // the owner still gets the (still-old-version) tunnel
                            // URL. Mirrors the web (toast install_failed) and app
                            // (showError) failure surfaces.
                            if (installFailedReason != null) {
                                String failMsg = tr("update.install_failed",
                                        TelegramMessages.technicalDetail(installFailedReason));
                                boolean failOk = sendMessage(ownerChatId, failMsg);
                                log("notifyTunnel install-failed message sent: " + failOk);
                            }

                            String msg;
                            if (postUpdateVersion != null) {
                                // Cloudflared free quick-tunnels rotate their URL on
                                // every restart (*.trycloudflare.com); zrok/tailscale/
                                // named cloudflared tunnels keep the same URL. Only
                                // call out the rotation when it actually happened.
                                boolean rotates = url.contains(".trycloudflare.com");
                                msg = rotates
                                        ? tr("update.installed_new_tunnel",
                                                mdEscape(postUpdateVersion), url)
                                        : tr("update.installed_tunnel_online",
                                                mdEscape(postUpdateVersion), url);
                            } else if (isNew) {
                                msg = tr("tunnel.notification_url", url);
                            } else {
                                msg = tr("tunnel.notification_url_changed", url);
                            }
                            boolean ok = sendMessage(ownerChatId, msg);
                            log("notifyTunnel message sent: " + ok +
                                    (postUpdateVersion != null ? " (post-update)" : ""));
                            if (ok) {
                                // Stamp only on send success; failed delivery
                                // (network blip) shouldn't consume the throttle
                                // window and silently drop the next legitimate
                                // notify.
                                stampTunnelNotify();
                            }
                            response.put("status", ok ? "ok" : "error");
                        }
                    } else {
                        response.put("status", "error");
                        response.put("message", "No URL or owner not set");
                    }
                    break;
                    
                case "notifyMotion":
                    if (!motionTextEnabled) {
                        log("notifyMotion skipped - motion text alerts disabled");
                        response.put("status", "skipped");
                        response.put("message", "Motion text alerts disabled");
                        break;
                    }
                    // No per-tier re-gate here: the engine already made the
                    // authoritative per-severity OR-decision at emit time
                    // (sendFinalTelegramNotification: send if EITHER snapshot OR
                    // latched-peak tier passes). Re-gating on the single
                    // collapsed severity field would gate-on-the-max and drop
                    // sends the engine legitimately allowed. A tier the user
                    // muted mid-drive may thus over-deliver once on park —
                    // benign over-delivery, never a wrong-drop.
                    if (ownerChatId > 0) {
                        String motionText = formatMotionMessage(cmd, /*finalized=*/false);
                        boolean ok = sendMessage(ownerChatId, motionText);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Owner not set");
                    }
                    break;

                case "notifyMotionFinalized":
                    // Recording closed and the hero JPEG has been written. Send a
                    // PHOTO (with rich caption) when the path is available; fall
                    // back to text-only if the photo upload fails or there's no
                    // hero — never silently drop.
                    if (!motionTextEnabled) {
                        log("notifyMotionFinalized skipped - motion text alerts disabled");
                        response.put("status", "skipped");
                        response.put("message", "Motion text alerts disabled");
                        break;
                    }
                    // No per-tier re-gate — see notifyMotion above (engine's
                    // emit-time OR-decision is authoritative).
                    if (ownerChatId > 0) {
                        String finalCaption = formatMotionMessage(cmd, /*finalized=*/true);
                        String heroPath = cmd.optString("heroPhotoPath", "");
                        boolean ok;
                        if (!heroPath.isEmpty() && new java.io.File(heroPath).exists()) {
                            ok = sendPhoto(ownerChatId, heroPath, finalCaption);
                            if (!ok) {
                                // Photo upload failed — fall back to text so the
                                // user still gets the alert.
                                ok = sendMessage(ownerChatId, finalCaption);
                            }
                        } else {
                            ok = sendMessage(ownerChatId, finalCaption);
                        }
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Owner not set");
                    }
                    break;
                    
                case "notifyCritical":
                    if (!criticalAlertsEnabled) {
                        log("notifyCritical skipped - critical alerts disabled");
                        response.put("status", "skipped");
                        response.put("message", "Critical alerts disabled");
                        break;
                    }
                    String criticalType = cmd.optString("type", "");
                    String details = cmd.optString("details", "");
                    if (ownerChatId > 0) {
                        String localizedType = criticalTypeLabel(criticalType);
                        String msg = details.isEmpty()
                                ? tr("critical.message", localizedType)
                                : tr("critical.message_with_details", localizedType,
                                        TelegramMessages.technicalDetail(details));
                        boolean ok = sendMessage(ownerChatId, msg);
                        response.put("status", ok ? "ok" : "error");
                    } else {
                        response.put("status", "error");
                        response.put("message", "Owner not set");
                    }
                    break;
                    
                case "ping":
                    response.put("status", "ok");
                    response.put("ownerChatId", ownerChatId);
                    break;
                    
                default:
                    response.put("status", "error");
                    response.put("message", "Unknown command: " + action);
            }

            // Fix #2: if the send failed DEFINITIVELY-UNDELIVERED (DNS/connect,
            // set by markSendUndelivered on the send path) AND this action is a
            // dup-safe owner-gated text/photo alert, spool the ORIGINAL command
            // for later replay so a transient outage doesn't silently eat a
            // door/critical/motion alert. Guarded so it never runs for a replay
            // (would loop / reset the age cap) or when the send actually
            // reached Telegram (delivered-ambiguous timeouts, HTTP errors, and
            // gate "skipped" outcomes all leave the flag false ⇒ no dup).
            if (Boolean.TRUE.equals(lastSendUndelivered.get())
                    && !Boolean.TRUE.equals(replayingSpooledCmd.get())
                    && isSpoolableOnUndelivered(action)) {
                try {
                    // Strip a caller-supplied chatId so the replay re-resolves
                    // to the CURRENT owner inside processIpcCommand (defense-in-
                    // depth: a spooled entry can never target a stale chat), and
                    // drop the transient status we just set — the drainer stamps
                    // its own K_SPOOLED_AT.
                    JSONObject spoolCmd = new JSONObject(cmd.toString());
                    spoolCmd.remove("chatId");
                    boolean spooled =
                            com.overdrive.app.telegram.TelegramSpool.offer(spoolCmd);
                    if (spooled) {
                        // Arm the recovery edge ONLY once an entry is on disk, so
                        // the next successful poll drains it. Set after offer()
                        // returns (file published) → no drain-before-write race.
                        connectivityWasDown = true;
                        response.put("status", "spooled");
                    }
                    log("Send undelivered for '" + action + "'; "
                            + (spooled ? "spooled for retry on reconnect"
                                        : "spool failed, dropped"));
                } catch (Exception spoolEx) {
                    log("Spool-on-undelivered error: " + spoolEx.getMessage());
                }
            }
        } catch (Exception e) {
            try {
                response.put("status", "error");
                response.put("message", e.getMessage());
            } catch (Exception ignored) {}
        }
        return response;
    }
    
    // ==================== LONG POLLING ====================
    
    private static void startPolling() {
        if (polling.getAndSet(true)) {
            log("Already polling");
            return;
        }
        
        Thread pollThread = new Thread(() -> {
            log("Polling thread started");
            
            // Skip old messages by getting latest update ID first
            flushOldUpdates();

            // Surface the outcome of a Telegram/IPC-triggered install that
            // completed (or failed) at pm-install time in the previous process.
            // This rides the criticalAlerts gate (default ON), NOT the
            // connectivity gate (default OFF) that frames the tunnel-URL copy —
            // so the owner learns the result under the same default settings the
            // web/app surfaces already use. Runs once, unconditionally, on the
            // reborn bot's first poll.
            surfaceInstallResultOnStartup();

            // Drain any notifications that were spooled while this daemon was
            // down (proximity alerts fired during ACC ON, or the closing
            // surveillance photo lost in the ACC-on teardown race) on a SEPARATE
            // thread. The drain replays serially and each send can sleep up to
            // 30-60s on a 429 backoff; running it inline here would withhold the
            // startup greeting and block the long-poll loop (user /commands) for
            // minutes on a full spool. Off-thread, the bot stays responsive
            // while the backlog drains. Replays go through processIpcCommand so
            // the per-tier/category/owner gate + file-existence checks re-apply.
            Thread drainThread = new Thread(
                    TelegramBotDaemon::drainSpooledNotifications, "TelegramSpoolDrain");
            drainThread.setDaemon(true);
            drainThread.start();

            // Send greeting to owner if paired
            sendStartupGreeting();
            
            while (running && polling.get()) {
                try {
                    pollUpdates();
                } catch (Exception e) {
                    log("Poll error: " + e.getMessage());
                    onHttpFailure(); // Re-check proxy on connection failure
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                }
            }
            
            log("Polling thread stopped");
        }, "TelegramPoll");
        pollThread.setDaemon(true);
        pollThread.start();
    }
    
    /**
     * Flush old updates by getting the latest update ID without processing.
     * This prevents the bot from responding to messages sent while it was offline.
     */
    private static void flushOldUpdates() {
        try {
            log("Flushing old updates...");
            
            // Get updates with offset -1 to get only the latest update
            String url = TELEGRAM_API_BASE() + botToken + "/getUpdates?timeout=1&offset=-1";
            
            Request request = new Request.Builder().url(url).get().build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log("Flush HTTP error: " + response.code());
                    return;
                }
                
                String body = response.body() != null ? response.body().string() : "";
                JSONObject json = new JSONObject(body);
                
                if (!json.optBoolean("ok", false)) {
                    log("Flush API error: " + json.optString("description"));
                    return;
                }
                
                JSONArray updates = json.optJSONArray("result");
                if (updates != null && updates.length() > 0) {
                    // Get the last update ID and set our offset past it
                    JSONObject lastUpdate = updates.getJSONObject(updates.length() - 1);
                    lastUpdateId = lastUpdate.getLong("update_id");
                    log("Flushed old updates, starting from update_id: " + (lastUpdateId + 1));
                } else {
                    log("No old updates to flush");
                }
            }
        } catch (Exception e) {
            log("Flush error: " + e.getMessage());
        }
    }

    /**
     * On the reborn bot's first poll, surface the outcome of a Telegram/IPC-
     * triggered install that finished in the previous process.
     *
     * Both hints are planted by the detached install script (AppUpdater.
     * runDetachedInstall — the daemon UID-2000 path ALL surfaces take, since
     * only that process can write /data/local/tmp): a SUCCESS leaves
     * TELEGRAM_POST_UPDATE_HINT_FILE, a pm-install FAILURE leaves
     * TELEGRAM_INSTALL_FAILED_HINT_FILE (the most common failure — signature
     * mismatch, downgrade refusal, ENOSPC). They are mutually exclusive.
     *
     * Previously these were consumed ONLY inside the connectivity-gated
     * notifyTunnel handler, so a default-configured owner (connectivity OFF) or
     * any owner on a no-/stable-tunnel never learned a Telegram-triggered
     * install failed — while web (toast install_failed) and app (showError +
     * post-relaunch toast) always surface it. This hook reaches the owner on the
     * criticalAlerts gate (default ON), matching the pre-kill surfaceIpcInstallFailure
     * twin (SurveillanceIpcServer) so both Telegram failure paths use one
     * consistent gate. The success confirmation rides the same gate for parity
     * with the app/web "updated to X" surfaces.
     *
     * Synchronized on TUNNEL_NOTIFY_LOCK so a concurrent first notifyTunnel IPC
     * can't double-consume/double-send (consume*Hint() deletes the file, so
     * whichever path runs first wins and the other no-ops).
     */
    private static void surfaceInstallResultOnStartup() {
        if (ownerChatId <= 0) return;
        try {
            synchronized (TUNNEL_NOTIFY_LOCK) {
                String installFailedReason = consumeInstallFailedHint();
                if (installFailedReason != null) {
                    if (criticalAlertsEnabled) {
                        String failMsg = tr("update.install_failed",
                                TelegramMessages.technicalDetail(installFailedReason));
                        boolean ok = sendMessage(ownerChatId, failMsg);
                        log("Startup install-failed message sent: " + ok);
                    } else {
                        log("Startup install-failed hint consumed but criticalAlerts off");
                    }
                    // Failure and success hints are mutually exclusive; done.
                    return;
                }
                String postUpdateVersion = consumePostUpdateHint();
                if (postUpdateVersion != null) {
                    if (criticalAlertsEnabled) {
                        String msg = tr("update.installed",
                                mdEscape(postUpdateVersion));
                        boolean ok = sendMessage(ownerChatId, msg);
                        log("Startup update-success message sent: " + ok);
                    } else {
                        log("Startup update-success hint consumed but criticalAlerts off");
                    }
                }
            }
        } catch (Exception e) {
            log("Startup install-result surfacing error: " + e.getMessage());
        }
    }

    /**
     * Drain notifications that emitters spooled to disk while this daemon was
     * not listening (it only runs during ACC OFF). Each spooled command is
     * replayed through {@link #processIpcCommand}, so the live category/owner
     * gate and file-existence checks re-apply exactly as for a fresh send — a
     * since-unpaired owner or since-disabled category correctly drops, and a
     * since-deleted clip/hero is skipped. The spool is bounded and
     * age-capped by {@link com.overdrive.app.telegram.TelegramSpool} so a long
     * drive can't produce a wall of stale alerts on park. Best-effort; never
     * throws into startup.
     */
    private static void drainSpooledNotifications() {
        // Single-flight: the startup drain and a poll-triggered reconnect drain
        // (or two rapid reconnect edges) must not run the same spool dir at
        // once — concurrent drains would race the .inflight claim and could
        // double-attempt an entry. TelegramSpool.drain is itself synchronized,
        // but that would only SERIALISE two drains (the second re-scanning a
        // dir the first already emptied); this flag skips the redundant pass
        // entirely. Reset in finally so a crashed drain never wedges the gate.
        if (!spoolDrainInProgress.compareAndSet(false, true)) {
            log("Spool drain already in progress; skipping");
            return;
        }
        try {
            int n = com.overdrive.app.telegram.TelegramSpool.drain(cmd -> {
                // Defense-in-depth: a spooled entry must only ever reach the
                // CURRENT owner. Strip any caller-supplied chatId so the replay
                // falls back to ownerChatId inside processIpcCommand — a crafted
                // entry (were the spool dir ever writable) can't redirect to an
                // attacker chat.
                cmd.remove("chatId");
                // Mark this as a REPLAY so processIpcCommand's spool-on-
                // undelivered path is suppressed — a replay that fails again
                // during a still-ongoing outage must NOT re-offer itself (that
                // would reset the age stamp and could ping-pong forever,
                // defeating both the age cap and at-most-once). TelegramSpool
                // already consumes the entry after this one attempt.
                replayingSpooledCmd.set(Boolean.TRUE);
                try {
                    JSONObject resp = processIpcCommand(cmd);
                    // Advisory return only (drained-count log). The entry is
                    // consumed after this one attempt regardless of outcome
                    // (at-most-once) — re-arming on a false return risked
                    // duplicating an ambiguous-but-actually-delivered send (no
                    // outbound idempotency).
                    String status = (resp != null) ? resp.optString("status", "") : "";
                    return "ok".equals(status);
                } finally {
                    replayingSpooledCmd.set(Boolean.FALSE);
                }
            });
            if (n > 0) log("Replayed " + n + " spooled notification(s)");
        } catch (Throwable t) {
            log("Spool drain error: " + t.getMessage());
        } finally {
            spoolDrainInProgress.set(false);
        }
    }

    /**
     * Send a startup greeting message to the owner.
     *
     * Throttled across daemon restarts via a persisted timestamp file. Without
     * this, every fresh process — watchdog crash-loop respawn, lock-collision
     * retry after zombie holder dies, lmkd reap, post-update hardReset double-
     * fire — would emit one greeting. Worst case (clean-exit branch in
     * start_telegram.sh: 10s sleep) is ~360/hour. The throttle window matches
     * "user genuinely cares about a bot-online ping" — once per hour is
     * generous for actual uptime feedback while collapsing crash-loop noise
     * to a single message.
     */
    private static final String GREETING_STAMP_FILE = "/data/local/tmp/.tg_last_greeted";
    private static final long GREETING_THROTTLE_MS = 60L * 60L * 1000L; // 1 hour

    /**
     * Read the kernel boot_id, a UUID that changes on every boot. Used to
     * detect "stamp file was written under a previous boot" so we can
     * bypass the greeting throttle after a hard reboot. /data/local/tmp/
     * lives on persistent storage (ext4 on /data partition), so a plain
     * mtime stamp would survive reboot and incorrectly suppress the
     * "back online" greeting if the user rebooted within the throttle
     * window. boot_id changes on every kernel boot, so the comparison
     * captures the reboot signal cleanly.
     *
     * Returns "" if the file isn't readable (unusual but not impossible
     * on hardened devices). On "" we fall back to mtime-only behavior —
     * one extra greeting after reboot is preferable to silently dropping.
     */
    private static String readBootId() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(
                        "/proc/sys/kernel/random/boot_id")))) {
            String line = r.readLine();
            return line != null ? line.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    // Tunnel-URL notification throttle. Lives on the daemon side because
    // the app UID (10xxx) cannot create files in /data/local/tmp/ — only
    // the daemon UID (2000 / shell) can. The cloudflared free tier
    // rotates its *.trycloudflare.com URL on every restart, so every
    // health-check restart loop produces a "URL changed" Telegram message
    // unless throttled. 10 min window matches "user-meaningful URL
    // change" — anything more frequent IS the loop, not real change.
    private static final String TUNNEL_NOTIFY_STAMP_FILE = "/data/local/tmp/.tunnel_last_notified";
    private static final long TUNNEL_NOTIFY_THROTTLE_MS = 10L * 60L * 1000L; // 10 min

    // Serializes the notifyTunnel IPC critical section. IPC_WORKERS is a
    // 2-thread pool so two notifyTunnel calls can race in the post-update
    // window: both peek the hint file present, both bypass the throttle,
    // both consume the hint via consumePostUpdateHint() (which delete()s
    // the file). First call sends the "Overdrive updated to vX.Y" message,
    // second call sees postUpdateVersion=null and sends a generic "URL
    // changed" — TWO user-facing notifications instead of one. Locking the
    // peek+throttle+consume+send+stamp sequence under one mutex makes the
    // second IPC observe the first's stamp file and throttle correctly.
    // The critical section is dominated by the Telegram sendMessage HTTP
    // call (~200-1000ms), which is acceptable: notifyTunnel is bursty
    // (one per tunnel start) not high-frequency.
    private static final Object TUNNEL_NOTIFY_LOCK = new Object();

    private static boolean shouldThrottleTunnelNotify() {
        try {
            File stamp = new File(TUNNEL_NOTIFY_STAMP_FILE);
            if (!stamp.exists()) return false;
            long age = System.currentTimeMillis() - stamp.lastModified();
            if (age < 0 || age >= TUNNEL_NOTIFY_THROTTLE_MS) return false;

            // Boot-id check: same rationale as the greeting throttle.
            // /data is ext4 (persistent) — a reboot within 10min would
            // otherwise eat the legit "tunnel back online" notification.
            String currentBootId = readBootId();
            String stampedBootId = "";
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(stamp)))) {
                String line = r.readLine();
                if (line != null) stampedBootId = line.trim();
            } catch (Exception ignored) {}

            if (currentBootId.isEmpty()
                    || stampedBootId.isEmpty()
                    || !currentBootId.equals(stampedBootId)) {
                // Different boot or unreadable boot_id — let the notify
                // through. The send path will rewrite the stamp with
                // the current boot_id.
                return false;
            }
            return true;
        } catch (Exception e) {
            // Fail-open — never silently drop a notification on stat error.
            return false;
        }
    }

    private static void stampTunnelNotify() {
        try {
            File stamp = new File(TUNNEL_NOTIFY_STAMP_FILE);
            String body = readBootId();
            try (java.io.FileWriter fw = new java.io.FileWriter(stamp)) {
                fw.write(body);
            }
            // chmod 666 so the app process (UID 10xxx) can stat the
            // file for diagnostics. The throttle write itself is
            // daemon-only; this is just for observability.
            try { stamp.setReadable(true, false); } catch (Exception ignored) {}
            try { stamp.setWritable(true, false); } catch (Exception ignored) {}
        } catch (Exception e) {
            // Worst case: one extra notification on the next event.
            log("Tunnel stamp write error: " + e.getMessage());
        }
    }

    private static void sendStartupGreeting() {
        if (ownerChatId <= 0) {
            log("No owner paired, skipping startup greeting");
            return;
        }

        // Honor the connectivity-updates toggle: the startup greeting is
        // semantically a connectivity event (bot just came online, same
        // flavor as "Tunnel back online" at notifyTunnel). Skipping before
        // the throttle stamp so a disabled toggle doesn't consume the
        // 1-hour window and silently suppress a future re-enable.
        if (!connectivityAlertsEnabled) {
            log("Startup greeting skipped — connectivity updates disabled");
            return;
        }

        // NOTE: there is deliberately no post-update bypass here. An earlier
        // revision peeked overdrive_post_update_pending_telegram to force a
        // greeting after an update, but that peek was already dead code:
        // startPolling calls surfaceInstallResultOnStartup() first, which
        // CONSUMES (deletes) the hint before this method runs. It was also
        // redundant — that same call sends the owner an explicit
        // "Overdrive updated to X" message under criticalAlerts (default ON),
        // so the update is surfaced either way, and forcing an extra
        // bot-online greeting alongside it is exactly the duplication this
        // throttle exists to prevent.
        //
        // Cross-process throttle: skip if we greeted within the last hour
        // AND we cannot prove a reboot happened. File lives in /data/local/tmp
        // (writable by UID 2000 shell that runs this daemon) so it survives
        // SIGKILL, lock-collision exit(1), and watchdog clean/error
        // respawns. /data is ext4 (persistent), so it ALSO survives reboot
        // — we compare the stamped boot_id against the current one to
        // detect that case and bypass.
        //
        // User-initiated stop clears the stamp via DaemonLauncher
        // .stopTelegramDaemon, so toggle-off-then-on also bypasses without
        // needing a special signal here.
        String currentBootId = readBootId();
        {
            try {
                File stamp = new File(GREETING_STAMP_FILE);
                if (stamp.exists()) {
                    // Clamp a future mtime to "just now" instead of treating it as
                    // un-throttled. The head unit boots with a wrong RTC and
                    // corrects it seconds later — exactly the window in which the
                    // restart triggers cluster — so a backwards clock step made
                    // `age` negative and re-announced the greeting.
                    long age = System.currentTimeMillis() - stamp.lastModified();
                    if (age < 0) age = 0;
                    if (age < GREETING_THROTTLE_MS) {
                        String stampedBootId = "";
                        try (java.io.BufferedReader r = new java.io.BufferedReader(
                                new java.io.InputStreamReader(new java.io.FileInputStream(stamp)))) {
                            String line = r.readLine();
                            if (line != null) stampedBootId = line.trim();
                        } catch (Exception ignored) {}

                        // Only a PROVEN reboot bypasses the throttle: both ids
                        // readable AND different. When either is empty we cannot
                        // prove a reboot, so we fall back to mtime-only and
                        // SUPPRESS.
                        //
                        // The previous condition required both ids non-empty to
                        // suppress, which inverted that: on any device where
                        // /proc/sys/kernel/random/boot_id is unreadable,
                        // currentBootId is "" forever, every restart bypassed the
                        // throttle, and the stamp was rewritten as "" — a stable
                        // always-bypass state that reproduced the duplicate
                        // "bot ready" flood the throttle exists to stop.
                        boolean provenReboot = !currentBootId.isEmpty()
                                && !stampedBootId.isEmpty()
                                && !currentBootId.equals(stampedBootId);
                        if (!provenReboot) {
                            log("Startup greeting throttled (last sent " + (age / 1000)
                                + "s ago; boot_id "
                                + (currentBootId.isEmpty() || stampedBootId.isEmpty()
                                    ? "unavailable, mtime-only" : "unchanged") + ")");
                            return;
                        }
                        log("Greeting throttle bypassed (reboot detected: stamped="
                                + stampedBootId + ", current=" + currentBootId + ")");
                    }
                }
            } catch (Exception e) {
                // Fail-open: a stat blip shouldn't suppress the greeting.
                log("Greeting throttle check error: " + e.getMessage());
            }
        }

        try {
            String greeting = tr("greeting.online");

            String[][][] buttons = {
                {{tr("buttons.status"), "cmd:/status"},
                        {tr("buttons.daemons"), "cmd:/daemons"}},
                {{tr("buttons.events"), "cmd:/events"},
                        {tr("buttons.tunnel_url"), "cmd:/url"}}
            };

            // CLAIM BEFORE SEND. Stamping only after a successful send left two
            // windows where the greeting re-fired on every respawn:
            //   (1) a competing daemon's killOldInstances SIGKILLs us between
            //       the send and the stamp write — the watchdog sees exit 137,
            //       respawns, and the un-stamped throttle greets again. Stacked
            //       watchdogs make this a loop, one "bot ready" per cycle.
            //   (2) an AMBIGUOUS send failure (post-connect read timeout) —
            //       Telegram may well have delivered it, but `sent` was false so
            //       no stamp was written and the next start re-announced.
            // Writing the stamp first makes the throttle hold in both cases. If
            // the send then fails DEFINITIVELY-undelivered (DNS/connect — the
            // request provably never left the device) we roll the stamp back so
            // a genuine network blip still retries on the next start.
            writeGreetingStamp(currentBootId);

            int outcome = sendMessageWithButtonsClassified(ownerChatId, greeting, buttons);
            if (outcome == SEND_NOT_DELIVERED) {
                // Provably never reached Telegram — release the claim so the
                // next start retries. A replay cannot duplicate.
                log("Startup greeting not delivered — clearing stamp so the next start retries");
                try { new File(GREETING_STAMP_FILE).delete(); } catch (Exception ignored) {}
            } else if (outcome == SEND_AMBIGUOUS) {
                log("Startup greeting outcome ambiguous — keeping stamp to avoid a duplicate");
            } else {
                log("Startup greeting sent");
            }
        } catch (Exception e) {
            log("Startup greeting error: " + e.getMessage());
        }
    }

    /**
     * Persist the greeting throttle stamp with the current boot_id as its body,
     * so a post-reboot throttle check can detect the boot transition. chmod 666
     * so the app-side process can stat it for diagnostics.
     */
    private static void writeGreetingStamp(String currentBootId) {
        try {
            File stamp = new File(GREETING_STAMP_FILE);
            try (java.io.FileWriter fw = new java.io.FileWriter(stamp)) {
                fw.write(currentBootId == null || currentBootId.isEmpty() ? "" : currentBootId);
            }
            try { stamp.setReadable(true, false); } catch (Exception ignored) {}
            try { stamp.setWritable(true, false); } catch (Exception ignored) {}
        } catch (Exception e) {
            // Worst case is one extra greeting on the next restart.
            log("Greeting stamp write error: " + e.getMessage());
        }
    }
    
    private static void pollUpdates() throws Exception {
        // Refresh shared state from the unified config every poll cycle so
        // a clear/unpair/preference-toggle made by the app or web UI takes
        // effect within one long-poll interval (~30s) rather than requiring
        // a daemon restart. forceReload() bypasses the in-process mtime
        // cache to pick up cross-UID writes immediately.
        refreshConfigFromUnified();

        if (botToken == null || botToken.isEmpty()) {
            // Token cleared from the UI — sleep instead of hitting the
            // Telegram API with an empty token (which 404s in a tight loop
            // and floods the log). The next refresh that finds a token
            // resumes polling automatically.
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            return;
        }

        String url = TELEGRAM_API_BASE() + botToken + "/getUpdates?timeout=30&offset=" + (lastUpdateId + 1);
        
        Request request = new Request.Builder().url(url).get().build();
        
        // Backoff delay decided inside the response block but SLEPT after it closes
        // (see backoffSleep below). Sleeping while the Response is still open would
        // pin an OkHttp connection out of the pool for up to POLL_BACKOFF_MAX_MS.
        long pendingBackoffMs = 0L;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                int code = response.code();
                log("Poll HTTP error: " + code);
                onHttpFailure();
                // Backoff so an error response (which is NOT an exception and so
                // skips the poll loop's catch-sleep) can't spin getUpdates every
                // RTT. 429 → honor Telegram's retry_after; 409/401 are terminal
                // for this token → slow fixed cadence; everything else → doubling
                // backoff. Reads the body here (small error JSON) to extract
                // retry_after; the success path re-reads below only on 2xx.
                if (code == 429) {
                    long retryAfterSec = 0;
                    try {
                        String eb = response.body() != null ? response.body().string() : "";
                        if (!eb.isEmpty()) {
                            JSONObject ej = new JSONObject(eb);
                            retryAfterSec = ej.optJSONObject("parameters") != null
                                    ? ej.getJSONObject("parameters").optLong("retry_after", 0)
                                    : ej.optLong("retry_after", 0);
                        }
                    } catch (Exception ignored) {}
                    // Cap a server-supplied retry_after so a hostile/absurd value
                    // can't park the poll thread indefinitely.
                    pendingBackoffMs = retryAfterSec > 0
                            ? Math.min(retryAfterSec * 1000L, POLL_BACKOFF_MAX_MS)
                            : nextPollBackoffMs();
                } else if (code == 409 || code == 401) {
                    pendingBackoffMs = POLL_TERMINAL_ERROR_MS;
                } else {
                    pendingBackoffMs = nextPollBackoffMs();
                }
            } else {
            // NOTE: the error-backoff reset is NOT here. A 2xx only proves the
            // transport works; the body can still say ok:false. Resetting here made
            // the ok:false path restart from BASE every time (a flat 5s floor,
            // ~17k requests/day) instead of escalating. The reset now lives in the
            // ok:true branch below.

            // A successful poll (2xx) is the authoritative "connectivity is
            // back" signal. If a prior poll or send saw the network go down and
            // spooled alerts, drain now — the moment we can reach Telegram
            // again — instead of waiting for the next daemon restart. Runs on a
            // separate thread because the drain replays serially and each send
            // can sleep up to 30-60s on a 429, which must not stall the
            // long-poll loop. The down→up flag is flipped BEFORE spawning so a
            // burst of successful polls can't launch a drain per cycle;
            // spoolDrainInProgress single-flights any overlap with the startup
            // drain.
            if (connectivityWasDown) {
                connectivityWasDown = false;
                log("Connectivity restored — draining spooled notifications");
                Thread recoverDrain = new Thread(
                        TelegramBotDaemon::drainSpooledNotifications,
                        "TelegramSpoolDrainReconnect");
                recoverDrain.setDaemon(true);
                recoverDrain.start();
            }

            String body = response.body() != null ? response.body().string() : "";
            JSONObject json = new JSONObject(body);
            
            if (!json.optBoolean("ok", false)) {
                log("Poll API error: " + json.optString("description"));
                // ok:false with a 2xx status still means the poll FAILED, so back off
                // — a 200 body of {"ok":false,...} would otherwise loop with no delay,
                // the same spin as the non-2xx path. This ESCALATES (5s→10s→…→300s)
                // because the reset below only runs on a genuinely successful poll
                // (2xx AND ok:true). A persistent body-level error (e.g. a token that
                // authenticates but is rejected for this method) would otherwise sit
                // at a flat 5s floor forever ≈ 17k requests/day.
                pendingBackoffMs = nextPollBackoffMs();
            } else {
                // Genuine success (2xx + ok) — only now is the transport AND the API
                // call proven healthy, so clear the accumulated error backoff.
                pollErrorBackoffMs = 0;
                JSONArray updates = json.optJSONArray("result");
                if (updates != null && updates.length() > 0) {
                    for (int i = 0; i < updates.length(); i++) {
                        JSONObject update = updates.getJSONObject(i);
                        long updateId = update.getLong("update_id");

                        // Skip if already processed (deduplication)
                        if (processedUpdateIds.contains(updateId)) {
                            log("Skipping duplicate update: " + updateId);
                            continue;
                        }
                        processedUpdateIds.add(updateId);

                        lastUpdateId = Math.max(lastUpdateId, updateId);
                        processUpdate(update);
                    }
                }
            }
            }
        }

        // Sleep AFTER the Response (and its connection) has been released, and in
        // interruptible slices that re-check `running` — a single Thread.sleep of up
        // to POLL_BACKOFF_MAX_MS would have delayed daemon shutdown by that long.
        backoffSleep(pendingBackoffMs);
    }

    /**
     * Sleeps for {@code totalMs} in short slices, bailing out early if the daemon is
     * shutting down or polling was stopped. Keeps a 5-minute backoff from blocking a
     * stop request for 5 minutes.
     */
    private static void backoffSleep(long totalMs) {
        if (totalMs <= 0) return;
        final long slice = 1000L;
        long remaining = totalMs;
        while (remaining > 0 && running && polling.get()) {
            long chunk = Math.min(slice, remaining);
            try {
                Thread.sleep(chunk);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining -= chunk;
        }
    }

    /**
     * Returns the next poll-error backoff delay (ms) and advances the counter:
     * BASE on the first error, doubling each subsequent error, capped at MAX.
     * Reset to 0 by the poll loop on the next successful (2xx + ok) poll. Called
     * only on the single TelegramPoll thread, so the read-modify-write is safe
     * without synchronization.
     */
    private static long nextPollBackoffMs() {
        long next = (pollErrorBackoffMs <= 0)
                ? POLL_BACKOFF_BASE_MS
                : Math.min(pollErrorBackoffMs * 2, POLL_BACKOFF_MAX_MS);
        pollErrorBackoffMs = next;
        return next;
    }

    // ==================== UPDATE PROCESSING ====================
    
    private static void processUpdate(JSONObject update) {
        try {
            // Handle callback queries (button presses)
            JSONObject callbackQuery = update.optJSONObject("callback_query");
            if (callbackQuery != null) {
                processCallbackQuery(callbackQuery);
                return;
            }
            
            JSONObject message = update.optJSONObject("message");
            if (message == null) return;
            
            JSONObject chat = message.optJSONObject("chat");
            if (chat == null) return;
            
            long chatId = chat.getLong("id");
            String text = message.optString("text", "");
            
            JSONObject from = message.optJSONObject("from");
            String username = from != null ? from.optString("username", "") : "";
            String firstName = from != null ? from.optString("first_name", "") : "";
            
            log("Message from " + chatId + " (@" + username + "): " + text);
            
            // Handle /pair command (allowed even without owner)
            if (text.startsWith("/pair ")) {
                handlePairCommand(chatId, username, firstName, text.substring(6).trim());
                return;
            }
            
            // Owner-only commands
            if (ownerChatId <= 0) {
                sendMessage(chatId, tr("pair.no_owner"));
                return;
            }
            
            if (chatId != ownerChatId) {
                log("Ignoring message from non-owner: " + chatId);
                return;
            }
            
            // Route commands via modular handlers
            if (text.startsWith("/")) {
                commandRouter.route(chatId, text);
            }
            
        } catch (Exception e) {
            log("Update processing error: " + e.getMessage());
        }
    }
    
    /**
     * Process callback query from inline keyboard button press.
     */
    private static void processCallbackQuery(JSONObject callbackQuery) {
        try {
            String callbackId = callbackQuery.getString("id");
            String data = callbackQuery.optString("data", "");
            
            JSONObject from = callbackQuery.optJSONObject("from");
            long userId = from != null ? from.getLong("id") : 0;
            
            log("Callback from " + userId + ": " + data);
            
            // Only allow owner
            if (userId != ownerChatId) {
                answerCallbackQuery(callbackId, tr("callback.not_authorized"));
                return;
            }
            
            // Handle download callback: "dl:filename.mp4"
            if (data.startsWith("dl:")) {
                String filename = data.substring(3);
                answerCallbackQuery(callbackId, tr("callback.downloading"));
                commandRouter.route(ownerChatId, "/download " + filename);
            }
            // Handle events pagination: "ev:hours:page"
            else if (data.startsWith("ev:")) {
                String[] parts = data.substring(3).split(":");
                if (parts.length == 2) {
                    answerCallbackQuery(callbackId, null);
                    commandRouter.route(ownerChatId, "/events " + parts[0] + " " + parts[1]);
                }
            }
            // Handle command shortcut: "cmd:/command"
            else if (data.startsWith("cmd:")) {
                String command = data.substring(4);
                answerCallbackQuery(callbackId, null);
                commandRouter.route(ownerChatId, command);
            }
            // Handle daemon control: "dm:name:action"
            else if (data.startsWith("dm:")) {
                String[] parts = data.substring(3).split(":");
                if (parts.length == 2) {
                    String action = parts[1];
                    String callbackText;
                    if ("start".equals(action)) {
                        callbackText = tr("callback.starting");
                    } else if ("stop".equals(action)) {
                        callbackText = tr("callback.stopping");
                    } else {
                        callbackText = tr("callback.processing");
                    }
                    answerCallbackQuery(callbackId, callbackText);
                    commandRouter.route(ownerChatId, "/daemon " + parts[0] + " " + parts[1]);
                }
            }
            else {
                answerCallbackQuery(callbackId, tr("callback.unknown_action"));
            }
            
        } catch (Exception e) {
            log("Callback processing error: " + e.getMessage());
        }
    }
    
    /**
     * Answer a callback query (acknowledge button press).
     */
    private static void answerCallbackQuery(String callbackId, String text) {
        try {
            String url = TELEGRAM_API_BASE() + botToken + "/answerCallbackQuery";
            
            JSONObject body = new JSONObject();
            body.put("callback_query_id", callbackId);
            if (text != null && !text.isEmpty()) {
                body.put("text", text);
            }
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                    .build();
            
            httpClient.newCall(request).execute().close();
        } catch (Exception e) {
            log("answerCallbackQuery error: " + e.getMessage());
        }
    }
    
    private static void handlePairCommand(long chatId, String username, String firstName, String pin) {
        if (ownerChatId > 0) {
            sendMessage(chatId, tr("pair.already_paired"));
            return;
        }
        
        if (pin.length() != 6 || !pin.matches("\\d+")) {
            sendMessage(chatId, tr("pair.invalid_format"));
            return;
        }
        
        // Validate PIN against the one written by the app UI / web tab.
        // forceReload bypasses the in-process cache so a PIN that was just
        // written by the app process (different UID, different mtime tick)
        // is visible to us immediately.
        String expectedPin = "";
        long pinExpiry = 0;
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            expectedPin = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getPairPin();
            pinExpiry = com.overdrive.app.telegram.config.UnifiedTelegramConfig.getPairPinExpiry();
        } catch (Exception e) {
            log("Error reading pair PIN from config: " + e.getMessage());
        }
        
        if (expectedPin == null || expectedPin.isEmpty()) {
            sendMessage(chatId, tr("pair.no_pin"));
            return;
        }
        
        if (System.currentTimeMillis() > pinExpiry) {
            sendMessage(chatId, tr("pair.expired"));
            // Clear expired PIN from config
            clearPairPinFromConfig();
            return;
        }
        
        if (!pin.equals(expectedPin)) {
            sendMessage(chatId, tr("pair.invalid"));
            return;
        }
        
        // PIN valid — pair owner
        // Persist to disk BEFORE the in-memory set. freshSnapshotForIpc() runs
        // on the IPC worker pool and re-reads ownerChatId from disk per command;
        // if it ran between an in-memory set and the disk commit it would read
        // the stale -1 and clobber the just-paired owner. Committing first means
        // any concurrent fresh read sees the new owner (or the old, never -1).
        boolean persisted = saveOwnerToConfig(chatId, username, firstName);
        if (!persisted) {
            // The disk write failed, so disk still says owner=-1. Setting the
            // in-memory owner anyway would only "work" until the next
            // freshSnapshotForIpc / poll re-read clobbers it back to -1, then
            // every alert would silently drop. Surface the failure instead of
            // claiming a pairing that won't survive — the user can retry.
            ownerChatId = chatId;  // best-effort for THIS session's reply
            sendMessage(chatId, tr("pair.storage_error"));
            log("handlePairCommand: owner persist FAILED for " + chatId + " — pairing not durable");
            return;
        }
        ownerChatId = chatId;
        clearPairPinFromConfig();

        sendMessage(chatId, firstName == null || firstName.isEmpty()
                ? tr("pair.success_no_name")
                : tr("pair.success", mdEscape(firstName)));
    }
    
    private static void clearPairPinFromConfig() {
        try {
            com.overdrive.app.telegram.config.UnifiedTelegramConfig.clearPairPin();
        } catch (Exception e) {
            log("Error clearing pair PIN: " + e.getMessage());
        }
    }
    
    // ==================== MESSAGING ====================
    
    private static boolean sendMessage(long chatId, String text) {
        // Clear the per-thread undelivered flag before we start: a stale value
        // from a prior send on this worker thread must never be read by the
        // enclosing IPC handler as if it were this send's outcome.
        lastSendUndelivered.set(Boolean.FALSE);
        String url = TELEGRAM_API_BASE() + botToken + "/sendMessage";

        JSONObject body = new JSONObject();
        try {
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");
        } catch (Exception e) {
            // JSON build can't realistically fail here, but if it does the
            // failure is local (not a network drop) — don't mark undelivered.
            log("sendMessage payload build error: " + e.getMessage());
            return false;
        }
        String payload = body.toString();

        // OUTER loop: one extra whole-send retry when the failure was
        // DEFINITIVELY-UNDELIVERED (DNS/connect) — a transient blip (the log's
        // dominant "Unable to resolve host" / "failed to connect" pattern)
        // often clears within a second or two, and one dup-safe retry recovers
        // the alert without waiting for the spool. Delivered-ambiguous timeouts
        // do NOT enter this loop (they return from the catch immediately).
        for (int netAttempt = 0; netAttempt < 2; netAttempt++) {
            try {
                // Single retry on 429, matching the media send paths (sendPhoto/
                // sendVideo/sendDocument). Text alerts (motion-start, critical,
                // proximity, tunnel) are the highest-priority surface, so they
                // should get at least the same rate-limit budget as media — a
                // burst-induced 429 must not silently drop the alert.
                for (int attempt = 0; attempt < 2; attempt++) {
                    Request request = new Request.Builder()
                            .url(url)
                            .post(RequestBody.create(payload, MediaType.parse("application/json")))
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful()) {
                            return true;
                        }
                        if (response.code() == 429 && attempt == 0) {
                            long sleepSec = parseRetryAfter(response, 1L);
                            log("sendMessage 429 — sleeping " + sleepSec + "s before retry");
                            try { Thread.sleep(Math.min(sleepSec * 1000L, 30_000L)); }
                            catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                            continue;  // retry
                        }
                        // Surface 4xx/5xx so silent failures (e.g. 400
                        // "can't parse entities" from Markdown content the
                        // bot helper didn't escape) stop being invisible. An
                        // HTTP-level response means the request WAS delivered —
                        // not spoolable (would duplicate).
                        String respBody = response.body() != null
                                ? response.body().string() : "";
                        log("sendMessage HTTP " + response.code() + ": " + respBody);
                        return false;
                    }
                }
                return false;
            } catch (Exception e) {
                onHttpFailure();
                boolean undelivered = isDefinitivelyUndelivered(e);
                if (undelivered && netAttempt == 0) {
                    log("sendMessage undelivered (" + e.getMessage()
                            + ") — retrying once in " + SEND_RETRY_BACKOFF_MS + "ms");
                    try { Thread.sleep(SEND_RETRY_BACKOFF_MS); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        markSendUndelivered();  // let the handler spool it
                        return false;
                    }
                    continue;  // one dup-safe whole-send retry
                }
                log("sendMessage error: " + e.getMessage());
                if (undelivered) markSendUndelivered();
                return false;
            }
        }
        return false;
    }
    
    /**
     * Send a message with inline keyboard buttons.
     * @param buttons Array of button rows, each row is array of [text, callbackData] pairs
     */
    public static boolean sendMessageWithButtons(long chatId, String text, String[][][] buttons) {
        return sendMessageWithButtonsClassified(chatId, text, buttons) == SEND_OK;
    }

    /** Message was accepted by Telegram. */
    static final int SEND_OK = 0;
    /**
     * Provably NOT delivered — the request never left the device (DNS/connect
     * failure) or Telegram answered with a client-error/rate-limit rejection.
     * Safe to retry: a replay cannot duplicate.
     */
    static final int SEND_NOT_DELIVERED = 1;
    /**
     * Unknown outcome — a post-connect read/write timeout, or a Telegram 5xx.
     * The message MAY already have been delivered, so a replay risks a
     * duplicate. Callers that dedupe must treat this as "sent".
     */
    static final int SEND_AMBIGUOUS = 2;

    /**
     * Same send as {@link #sendMessageWithButtons} but reports WHY it failed, so
     * a caller holding a de-duplication stamp can tell a dup-safe retry from a
     * possible double-send. See the SEND_* constants.
     */
    static int sendMessageWithButtonsClassified(long chatId, String text, String[][][] buttons) {
        try {
            String url = TELEGRAM_API_BASE() + botToken + "/sendMessage";

            JSONObject body = new JSONObject();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "Markdown");
            
            // Build inline keyboard
            JSONArray keyboard = new JSONArray();
            for (String[][] row : buttons) {
                JSONArray rowArray = new JSONArray();
                for (String[] button : row) {
                    JSONObject btn = new JSONObject();
                    btn.put("text", button[0]);
                    btn.put("callback_data", button[1]);
                    rowArray.put(btn);
                }
                keyboard.put(rowArray);
            }
            
            JSONObject replyMarkup = new JSONObject();
            replyMarkup.put("inline_keyboard", keyboard);
            body.put("reply_markup", replyMarkup);
            String payload = body.toString();

            // Single retry on 429, matching the other send paths.
            for (int attempt = 0; attempt < 2; attempt++) {
                Request request = new Request.Builder()
                        .url(url)
                        .post(RequestBody.create(payload, MediaType.parse("application/json")))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        return SEND_OK;
                    }
                    if (response.code() == 429 && attempt == 0) {
                        long sleepSec = parseRetryAfter(response, 1L);
                        log("sendMessageWithButtons 429 — sleeping " + sleepSec + "s before retry");
                        try { Thread.sleep(Math.min(sleepSec * 1000L, 30_000L)); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return SEND_NOT_DELIVERED;
                        }
                        continue;  // retry
                    }
                    String respBody = response.body() != null
                            ? response.body().string() : "";
                    log("sendMessageWithButtons HTTP " + response.code() + ": " + respBody);
                    // Telegram answered, so we know the verdict: a 4xx rejected
                    // the message outright (not delivered), while a 5xx may have
                    // been applied server-side before the error — ambiguous.
                    return response.code() >= 500 ? SEND_AMBIGUOUS : SEND_NOT_DELIVERED;
                }
            }
            return SEND_NOT_DELIVERED;
        } catch (Exception e) {
            log("sendMessageWithButtons error: " + e.getMessage());
            return isDefinitivelyUndelivered(e) ? SEND_NOT_DELIVERED : SEND_AMBIGUOUS;
        }
    }
    
    /**
     * Send a photo to the chat with an optional caption (Markdown).
     * Used by the surveillance pipeline to ship the hero JPEG of the recording
     * with the threat summary as the caption — the Telegram analogue of the
     * PWA notification's hero image.
     */
    public static boolean sendPhoto(long chatId, String photoPath, String caption) {
        // Clear the per-thread undelivered flag before starting (see sendMessage).
        lastSendUndelivered.set(Boolean.FALSE);
        File photoFile = new File(photoPath);
        if (!photoFile.exists()) {
            log("Photo file not found: " + photoPath);
            return false;  // local miss, not a network drop — don't mark undelivered
        }

        String url = TELEGRAM_API_BASE() + botToken + "/sendPhoto";

        // OUTER loop: one dup-safe whole-send retry on a DEFINITIVELY-UNDELIVERED
        // (DNS/connect) failure — same rationale as sendMessage. The multipart
        // body (and file handle) is rebuilt fresh per attempt below, so a retry
        // re-reads the file cleanly.
        for (int netAttempt = 0; netAttempt < 2; netAttempt++) {
            try {
                // Build the multipart request once per attempt; OkHttp consumes
                // the body exactly once, so it's re-created on 429 retry too.
                for (int attempt = 0; attempt < 2; attempt++) {
                    MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("chat_id", String.valueOf(chatId))
                            .addFormDataPart("photo", photoFile.getName(),
                                    RequestBody.create(photoFile, MediaType.parse("image/jpeg")));

                    if (caption != null && !caption.isEmpty()) {
                        bodyBuilder.addFormDataPart("caption", caption);
                        bodyBuilder.addFormDataPart("parse_mode", "Markdown");
                    }

                    Request request = new Request.Builder()
                            .url(url)
                            .post(bodyBuilder.build())
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful()) {
                            log("Photo sent: " + photoPath);
                            return true;
                        }
                        if (response.code() == 429 && attempt == 0) {
                            // Telegram rate limit. Body carries {"parameters":{"retry_after":N}}
                            long sleepSec = parseRetryAfter(response, 1L);
                            log("sendPhoto 429 — sleeping " + sleepSec + "s before retry");
                            try { Thread.sleep(Math.min(sleepSec * 1000L, 30_000L)); }
                            catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                            continue;  // retry
                        }
                        // HTTP-level response ⇒ delivered ⇒ not spoolable.
                        log("sendPhoto HTTP error: " + response.code());
                        return false;
                    }
                }
                return false;
            } catch (Exception e) {
                onHttpFailure();
                boolean undelivered = isDefinitivelyUndelivered(e);
                if (undelivered && netAttempt == 0) {
                    log("sendPhoto undelivered (" + e.getMessage()
                            + ") — retrying once in " + SEND_RETRY_BACKOFF_MS + "ms");
                    try { Thread.sleep(SEND_RETRY_BACKOFF_MS); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        markSendUndelivered();
                        return false;
                    }
                    continue;
                }
                log("sendPhoto error: " + e.getMessage());
                if (undelivered) markSendUndelivered();
                return false;
            }
        }
        return false;
    }

    /**
     * Parse Telegram's retry_after hint from a 429 response. The API returns
     * {@code {"ok":false,"error_code":429,"description":"Too Many Requests:
     * retry after 30","parameters":{"retry_after":30}}}. Falls back to the
     * provided default (seconds) if parsing fails.
     */
    private static long parseRetryAfter(Response response, long defaultSec) {
        try {
            String body = response.peekBody(1024).string();
            JSONObject json = new JSONObject(body);
            JSONObject params = json.optJSONObject("parameters");
            if (params != null && params.has("retry_after")) {
                return Math.max(1L, params.getLong("retry_after"));
            }
            // Some proxies expose Retry-After as a header instead.
            String hdr = response.header("Retry-After");
            if (hdr != null) {
                try { return Math.max(1L, Long.parseLong(hdr.trim())); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return defaultSec;
    }

    /**
     * Format a Telegram motion message from the IPC command's actor metadata.
     * Used by both notifyMotion (start) and notifyMotionFinalized (recording
     * close) so the wording is consistent. Uses Markdown.
     *
     * Output examples (final stage):
     *   🚨 *CRITICAL · Person at front*
     *   Very close
     *   📹 `event_20260514_223124.mp4`
     *
     *   🚨 *CRITICAL · Person at front*
     *   Very close · 1 person, 2 vehicles
     *   📹 `event_20260514_223124.mp4`
     *
     * Start stage:
     *   👁 *Motion at front*
     *   _Recording in progress_
     *   📹 `event_20260514_223124.mp4`
     */
    private static String formatMotionMessage(JSONObject cmd, boolean finalized) {
        String severity = cmd.optString("severity", "");
        String videoFilename = cmd.optString("videoFilename", "");
        String camera = cmd.optString("camera", "");
        String closestProximity = cmd.optString("closestProximity", "");
        int personCount  = cmd.optInt("personCount", 0);
        int vehicleCount = cmd.optInt("vehicleCount", 0);
        int bikeCount    = cmd.optInt("bikeCount", 0);
        int animalCount  = cmd.optInt("animalCount", 0);
        int totalActors  = personCount + vehicleCount + bikeCount + animalCount;

        StringBuilder msg = new StringBuilder();

        // ---- Title line: "<icon> *<TIER> · <Class> at <camera>*" ----
        boolean haveActorInfo = totalActors > 0;
        String primary = haveActorInfo
                ? chooseTelegramPrimary(personCount, vehicleCount, bikeCount, animalCount)
                : null;
        String tierIcon;
        String tierWord;
        if ("CRITICAL".equalsIgnoreCase(severity)) {
            tierIcon = "🚨";
            tierWord = tr("motion.tier.critical");
        }
        else if ("ALERT".equalsIgnoreCase(severity)) {
            tierIcon = "⚠️";
            tierWord = tr("motion.tier.alert");
        }
        else                                          { tierIcon = "👁"; tierWord = null; }

        // Camera is the only free-form interpolation outside backticks. Today
        // it's enum-bounded ("front"/"right"/"rear"/"left") so safe, but
        // mdEscape it defensively so a future user-supplied label can't break
        // Markdown rendering with a stray *, _, `, or [.
        String safeCamera = camera.isEmpty() ? "" : mdEscape(cameraLabel(camera));
        String title;
        if (tierWord != null && haveActorInfo) {
            title = safeCamera.isEmpty()
                    ? tr("motion.title.tier_actor", tierIcon, tierWord, primary)
                    : tr("motion.title.tier_actor_camera", tierIcon, tierWord,
                            primary, safeCamera);
        } else if (tierWord != null) {
            title = safeCamera.isEmpty()
                    ? tr("motion.title.tier", tierIcon, tierWord)
                    : tr("motion.title.tier_camera", tierIcon, tierWord, safeCamera);
        } else if (haveActorInfo) {
            title = safeCamera.isEmpty()
                    ? tr("motion.title.actor", tierIcon, primary)
                    : tr("motion.title.actor_camera", tierIcon, primary, safeCamera);
        } else if (!safeCamera.isEmpty()) {
            title = tr("motion.title.camera", tierIcon, safeCamera);
        } else {
            title = tr("motion.title.detected", tierIcon);
        }
        msg.append(title).append("\n");

        // ---- Body line ----
        if (finalized) {
            String prox = proximityPhraseTelegram(closestProximity);
            StringBuilder body = new StringBuilder();
            if (!prox.isEmpty()) body.append(prox);
            if (totalActors > 1) {
                if (body.length() > 0) body.append(" · ");
                body.append(formatTelegramCounts(personCount, vehicleCount, bikeCount, animalCount));
            }
            if (body.length() > 0) {
                msg.append(body).append("\n");
            }
        } else {
            // Start-stage: minimal "in progress" line, mirrors the PWA
            // "Recording in progress" body.
            msg.append(tr("motion.recording_in_progress")).append("\n");
        }

        // ---- Footer ----
        if (!videoFilename.isEmpty()) {
            msg.append("📹 `").append(videoFilename).append("`");
            if (!finalized) {
                msg.append("\n📥 `/download ").append(videoFilename).append("`");
            }
        }

        // ---- Staleness footer ----
        // If the alert was delayed in the send pipeline (a video-upload backlog
        // can push a photo behind it), stamp the original detection time so the
        // user can tell a late alert from a live one. Only shown when the lag is
        // meaningful (> STALE_NOTICE_MS) so fresh alerts stay uncluttered.
        long eventTimeMs = cmd.optLong("eventTimeMs", 0L);
        if (eventTimeMs > 0) {
            long lagMs = System.currentTimeMillis() - eventTimeMs;
            if (lagMs > STALE_NOTICE_MS) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "HH:mm:ss", java.util.Locale.forLanguageTag(LocaleManager.get()));
                // Separate from the preceding line only when there IS one and it
                // doesn't already end in a newline — avoids a leading blank line
                // when the body + filename were both empty.
                if (msg.length() > 0 && msg.charAt(msg.length() - 1) != '\n') {
                    msg.append("\n");
                }
                msg.append(tr("motion.detected_at",
                        sdf.format(new java.util.Date(eventTimeMs))));
            }
        }
        return msg.toString();
    }

    /** Lag above which a motion alert carries a "Detected HH:MM:SS" stamp. */
    private static final long STALE_NOTICE_MS = 20_000L;

    /**
     * Escape Markdown legacy parse_mode metacharacters in free-form text that
     * lands outside backticks. Underscores in filenames are the most common
     * offender (the .mp4 timestamp format itself is rich in {@code _}).
     * Preserves readability — no zero-width characters, just backslash-escapes.
     */
    private static String mdEscape(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '*' || c == '`' || c == '[') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    private static String chooseTelegramPrimary(int p, int v, int b, int a) {
        // Match the engine-side classRank: PERSON > BIKE > VEHICLE > ANIMAL.
        // Single-actor case shows just the class word; the body line carries
        // the count breakdown when there's more than one actor.
        if (p > 0) return tr("motion.actor.person");
        if (b > 0) return tr("motion.actor.bike");
        if (v > 0) return tr("motion.actor.vehicle");
        if (a > 0) return tr("motion.actor.animal");
        return tr("motion.actor.motion");
    }

    private static String cameraLabel(String camera) {
        if (camera == null || camera.isEmpty()) return "";
        switch (camera.toLowerCase(java.util.Locale.ROOT)) {
            case "front": return tr("motion.camera.front");
            case "right": return tr("motion.camera.right");
            case "rear":  return tr("motion.camera.rear");
            case "left":  return tr("motion.camera.left");
            default:      return camera;
        }
    }

    /** Capitalised proximity phrase for the body's lead clause. */
    private static String proximityPhraseTelegram(String enumName) {
        if (enumName == null) return "";
        switch (enumName) {
            case "VERY_CLOSE": return tr("motion.proximity.very_close");
            case "CLOSE":      return tr("motion.proximity.close");
            case "MID":        return tr("motion.proximity.mid");
            case "FAR":        return tr("motion.proximity.far");
            default:           return "";
        }
    }

    /** Pluralised count list: "1 person, 2 vehicles" — drops "× n" formatter. */
    private static String formatTelegramCounts(int p, int v, int b, int a) {
        java.util.List<String> parts = new java.util.ArrayList<>(4);
        if (p > 0) parts.add(tr(p == 1 ? "motion.count.person_one" : "motion.count.person_many",
                String.valueOf(p)));
        if (v > 0) parts.add(tr(v == 1 ? "motion.count.vehicle_one" : "motion.count.vehicle_many",
                String.valueOf(v)));
        if (b > 0) parts.add(tr(b == 1 ? "motion.count.bike_one" : "motion.count.bike_many",
                String.valueOf(b)));
        if (a > 0) parts.add(tr(a == 1 ? "motion.count.animal_one" : "motion.count.animal_many",
                String.valueOf(a)));
        return String.join(", ", parts);
    }

    private static String criticalTypeLabel(String type) {
        if (type == null || type.isEmpty()) return tr("critical.type.system_error");
        switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "LOW_BATTERY": return tr("critical.type.low_battery");
            case "STORAGE_FULL": return tr("critical.type.storage_full");
            case "DAEMON_CRASH": return tr("critical.type.daemon_crash");
            case "SYSTEM_REBOOT": return tr("critical.type.system_reboot");
            case "SYSTEM_ERROR": return tr("critical.type.system_error");
            default: return TelegramMessages.isPortuguese()
                    ? tr("critical.type.unknown") : mdEscape(type);
        }
    }

    /** Telegram bot API file-upload ceiling. Above this, sendVideo/sendDocument 400. */
    private static final long TELEGRAM_MAX_UPLOAD_BYTES = 50L * 1000L * 1000L;  // 50 MB

    public static boolean sendVideo(long chatId, String videoPath, String caption) {
        try {
            File videoFile = new File(videoPath);
            if (!videoFile.exists()) {
                log("Video file not found: " + videoPath);
                return false;
            }
            long len = videoFile.length();
            if (len == 0L) {
                log("Video file is 0 bytes, skipping upload: " + videoPath);
                return false;
            }
            if (len > TELEGRAM_MAX_UPLOAD_BYTES) {
                // Over Telegram's bot upload limit — the multipart POST would
                // 400 "file is too big" and silently drop. Send a text notice
                // instead so the user learns the clip exists but is too large
                // to push, and where to find it.
                long mb = len / (1000L * 1000L);
                log("Video " + videoFile.getName() + " is " + mb
                        + "MB > 50MB Telegram limit; sending text notice instead");
                String notice = tr("media.video_too_large",
                        videoFile.getName(), String.valueOf(mb));
                return sendMessage(chatId, notice);
            }

            String url = TELEGRAM_API_BASE() + botToken + "/sendVideo";

            for (int attempt = 0; attempt < 2; attempt++) {
                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("chat_id", String.valueOf(chatId))
                        .addFormDataPart("supports_streaming", "true")
                        .addFormDataPart("video", videoFile.getName(),
                                RequestBody.create(videoFile, MediaType.parse("video/mp4")));

                if (caption != null && !caption.isEmpty()) {
                    bodyBuilder.addFormDataPart("caption", caption);
                }

                Request request = new Request.Builder()
                        .url(url)
                        .post(bodyBuilder.build())
                        .build();

                // Heavy-upload client: widened write/read timeouts so a
                // multi-MB clip on a slow uplink isn't cut off at 30s.
                try (Response response = uploadHttpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        log("Video sent: " + videoPath);
                        return true;
                    }
                    if (response.code() == 429 && attempt == 0) {
                        long sleepSec = parseRetryAfter(response, 5L);
                        log("sendVideo 429 — sleeping " + sleepSec + "s before retry");
                        try { Thread.sleep(Math.min(sleepSec * 1000L, 60_000L)); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        continue;
                    }
                    log("sendVideo HTTP error: " + response.code());
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            log("sendVideo error: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Send a file as a Telegram document. Used by the backup feature to deliver
     * an exported settings bundle. Mirrors {@link #sendVideo}'s multipart +
     * single-429-retry shape.
     */
    public static boolean sendDocument(long chatId, String filePath, String caption) {
        try {
            File docFile = new File(filePath);
            if (!docFile.exists()) {
                log("Document file not found: " + filePath);
                return false;
            }
            long len = docFile.length();
            if (len == 0L) {
                log("Document is 0 bytes, skipping upload: " + filePath);
                return false;
            }
            if (len > TELEGRAM_MAX_UPLOAD_BYTES) {
                long mb = len / (1000L * 1000L);
                log("Document " + docFile.getName() + " is " + mb
                        + "MB > 50MB Telegram limit; sending text notice instead");
                return sendMessage(chatId, tr("media.document_too_large",
                        docFile.getName(), String.valueOf(mb)));
            }

            String url = TELEGRAM_API_BASE() + botToken + "/sendDocument";

            for (int attempt = 0; attempt < 2; attempt++) {
                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("chat_id", String.valueOf(chatId))
                        .addFormDataPart("document", docFile.getName(),
                                RequestBody.create(docFile, MediaType.parse("application/json")));

                if (caption != null && !caption.isEmpty()) {
                    bodyBuilder.addFormDataPart("caption", caption);
                }

                Request request = new Request.Builder()
                        .url(url)
                        .post(bodyBuilder.build())
                        .build();

                // Heavy-upload client (same widened timeouts as sendVideo).
                try (Response response = uploadHttpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        log("Document sent: " + filePath);
                        return true;
                    }
                    if (response.code() == 429 && attempt == 0) {
                        long sleepSec = parseRetryAfter(response, 5L);
                        log("sendDocument 429 — sleeping " + sleepSec + "s before retry");
                        try { Thread.sleep(Math.min(sleepSec * 1000L, 60_000L)); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                        continue;
                    }
                    log("sendDocument HTTP error: " + response.code());
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            log("sendDocument error: " + e.getMessage());
            return false;
        }
    }

    // ==================== UTILITIES ====================

    private static JSONObject sendIpcCommand(int port, JSONObject command) {
        return sendIpcCommand(port, command, 5000);
    }

    private static JSONObject sendIpcCommand(int port, JSONObject command, int timeoutMs) {
        Socket socket = null;
        try {
            // Bound the CONNECT independently of the read. This runs on the
            // single TelegramPoll thread (command handlers query peer daemons);
            // a bound-but-not-accepting peer would otherwise block the connect
            // for the OS SYN-retry budget (~127s), stalling the long-poll loop
            // and the per-cycle gate refresh. Mirrors the inbound path's 1.5s
            // connect cap. setSoTimeout still bounds the read after connect.
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            socket.setSoTimeout(timeoutMs);

            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            writer.println(command.toString());
            String response = reader.readLine();

            return response != null ? new JSONObject(response) : null;
        } catch (Exception e) {
            log("IPC error (port " + port + "): " + e.getMessage());
            return null;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * Bounded shell exec: 30 s ceiling on waitFor + an explicit destroyForcibly
     * on timeout. Use ONLY for short-lived commands (ps, pgrep, cat, pm path,
     * ls, echo, rm, kill). For long-lived spawns use {@link #spawnDetached}.
     *
     * The 30 s budget is generous for the read-only commands that actually
     * call this; the point is a hard ceiling so a hung binary or zombie pipe
     * can't permanently freeze the polling thread (which would lock /pair,
     * /events, and every other handler until reboot).
     */
    private static String execShell(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append("\n");
                output.append(line);
            }
            reader.close();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                log("Shell timeout after 30s, destroying: "
                        + com.overdrive.app.launcher.ZrokRuntimeProbe.redactCommand(command));
                p.destroyForcibly();
                return null;
            }
            return output.toString();
        } catch (Exception e) {
            log("Shell error: " + e.getMessage());
            if (p != null) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            return null;
        }
    }
    
    /** @return true if the owner was durably persisted to the unified config. */
    private static boolean saveOwnerToConfig(long chatId, String username, String firstName) {
        try {
            boolean ok = com.overdrive.app.telegram.config.UnifiedTelegramConfig.setOwner(
                    chatId, username, firstName, System.currentTimeMillis());
            if (ok) {
                log("Owner saved to unified config: " + chatId);
            } else {
                log("Owner save returned false (write not durable): " + chatId);
            }
            return ok;
        } catch (Exception e) {
            log("Save owner error: " + e.getMessage());
            return false;
        }
    }
    
    private static void saveTunnelUrl(String url) {
        try {
            File urlFile = new File(PATH_TELEGRAM_URL_FILE());
            try (java.io.FileWriter fw = new java.io.FileWriter(urlFile)) {
                fw.write(url);
            }
            log("Tunnel URL saved to file: " + url);
        } catch (Exception e) {
            log("Save tunnel URL error: " + e.getMessage());
        }
    }

    /**
     * One-shot read of the post-update hint file. If present, returns the
     * trimmed version string and deletes the file so the next tunnel restart
     * uses the generic message. Returns null if the hint isn't there or the
     * read fails.
     *
     * Path is duplicated (not pulled from UpdateLifecycle) because this code
     * runs in the daemon process which loads classes lazily and we want to
     * avoid pulling the whole updater package transitively.
     */
    private static String consumePostUpdateHint() {
        File hint = new File("/data/local/tmp/overdrive_post_update_pending_telegram");
        if (!hint.exists()) return null;
        String version = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(hint)))) {
            String line = r.readLine();
            if (line != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) version = trimmed;
            }
        } catch (Exception e) {
            log("Post-update hint read error: " + e.getMessage());
        }
        // Delete unconditionally — even if the read failed we don't want a
        // stale hint to keep flagging unrelated tunnel restarts.
        try { hint.delete(); } catch (Exception ignored) {}
        return version;
    }

    /**
     * Failure twin of {@link #consumePostUpdateHint()}. Reads + deletes the
     * install-failed hint planted by the detached install script's FAILURE
     * branch (only when the install was Telegram/IPC-triggered). Returns the
     * pm-install error text (e.g. "Failure [INSTALL_PARSE_FAILED…]") or null if
     * no failure hint is present. Lets the reborn bot tell the owner the
     * scheduled install failed, symmetric with the web/app failure surfaces.
     *
     * Path is duplicated (not pulled from UpdateLifecycle) for the same reason
     * as consumePostUpdateHint — this runs in the lazily-classloaded daemon
     * process and we avoid pulling the whole updater package transitively.
     */
    private static String consumeInstallFailedHint() {
        File hint = new File("/data/local/tmp/overdrive_install_failed_pending_telegram");
        if (!hint.exists()) return null;
        String reason = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(hint)))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(line);
            }
            String trimmed = sb.toString().trim();
            if (!trimmed.isEmpty()) reason = trimmed;
        } catch (Exception e) {
            log("Install-failed hint read error: " + e.getMessage());
        }
        // Delete unconditionally so a stale hint can't re-fire on a later
        // unrelated tunnel restart. Fall back to a generic reason if the file
        // existed but was empty/unreadable — the user should still learn the
        // install failed.
        try { hint.delete(); } catch (Exception ignored) {}
        if (reason == null) reason = "unknown (see head unit)";
        return reason;
    }
    
    // ==================== PUBLIC API FOR NOTIFICATIONS ====================
    
    public static long getOwnerChatId() {
        return ownerChatId;
    }
    
    public static String getBotToken() {
        return botToken;
    }
}
