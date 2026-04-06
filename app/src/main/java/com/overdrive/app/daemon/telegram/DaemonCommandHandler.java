package com.overdrive.app.daemon.telegram;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * Handles /daemon commands for starting/stopping daemons.
 * 
 * Uses the same process names and kill approach as the UI daemon controllers.
 * 
 * Writes daemon state to /data/local/tmp/daemon_telegram_state.properties
 * so health checks can honor Telegram-initiated stops.
 */
public class DaemonCommandHandler implements TelegramCommandHandler {
    
    private static final String TAG = "DaemonCmd";
    private static final String STATE_FILE = "/data/local/tmp/daemon_telegram_state.properties";
    
    // Debounce duplicate commands
    private long lastCommandTime = 0;
    private String lastCommandKey = "";
    private static final long DEBOUNCE_MS = 3000;
    
    // Daemon definitions: name -> [processName, className, displayName, startable]
    // startable: "yes" if can be started via app_process or shell, "no" if can't be started remotely
    private static final String[][] DAEMONS = {
        {"camera", "byd_cam_daemon", "CameraDaemon", "Camera", "yes"},
        {"acc", "acc_sentry_daemon", "AccSentryDaemon", "ACC Sentry", "yes"},
        {"sentry", "sentry_daemon", "SentryDaemon", "Sentry", "yes"},
        {"telegram", "telegram_bot_daemon", "TelegramBotDaemon", "Telegram", "yes"},
        {"cloudflared", "cloudflared", "shell", "Cloudflare Tunnel", "yes"},
        {"zrok", "zrok", "shell", "Zrok Tunnel", "yes"},
        {"singbox", "sing-box", "shell", "Sing-Box", "yes"},
    };
    
    private static final String AVAILABLE_DAEMONS = "camera, acc, sentry, cloudflared, zrok, singbox";
    
    @Override
    public boolean canHandle(String command) {
        return "/daemon".equals(command);
    }
    
    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        if (args.length < 3) {
            ctx.sendMessage(chatId, "Usage: /daemon <name> start|stop|status\n\nAvailable: " + AVAILABLE_DAEMONS);
            return;
        }
        
        String name = args[1].toLowerCase();
        String action = args[2].toLowerCase();
        
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
            ctx.sendMessage(chatId, "❌ Unknown daemon: " + name + "\n\nAvailable: " + AVAILABLE_DAEMONS);
            return;
        }
        
        String processName = daemon[1];
        String displayName = daemon[3];
        boolean isStartable = "yes".equals(daemon[4]);
        
        // Can't control telegram from telegram
        if ("telegram".equals(name)) {
            ctx.sendMessage(chatId, "⚠️ Cannot control Telegram daemon from Telegram.");
            return;
        }
        
        ctx.log("Daemon command: " + displayName + " (" + processName + ") action=" + action);
        
        boolean isRunning = isDaemonRunning(processName, ctx);
        
        switch (action) {
            case "start":
                if (isRunning) {
                    ctx.sendMessage(chatId, "ℹ️ " + displayName + " is already running.");
                } else if (!isStartable) {
                    ctx.sendMessage(chatId, "⚠️ " + displayName + " must be started from the app UI.");
                } else {
                    // Cloudflared and Zrok are mutually exclusive
                    if ("cloudflared".equals(name)) {
                        if (isDaemonRunning("zrok", ctx)) {
                            ctx.log("Stopping Zrok (mutually exclusive with Cloudflared)");
                            stopDaemon("zrok", ctx);
                            saveDaemonState("zrok", false, ctx); // Mark zrok as stopped
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        }
                    } else if ("zrok".equals(name)) {
                        if (isDaemonRunning("cloudflared", ctx)) {
                            ctx.log("Stopping Cloudflared (mutually exclusive with Zrok)");
                            stopDaemon("cloudflared", ctx);
                            saveDaemonState("cloudflared", false, ctx); // Mark cloudflared as stopped
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        }
                    }
                    
                    boolean ok;
                    if ("shell".equals(daemon[2])) {
                        // External binary - start via shell command
                        ok = startShellDaemon(name, ctx);
                    } else {
                        // Java daemon - start via app_process
                        ok = startDaemon(daemon[2], ctx);
                    }
                    
                    if (ok) {
                        // Clear stopped state - daemon was started via Telegram
                        saveDaemonState(name, true, ctx);
                    }
                    ctx.sendMessage(chatId, ok ? "✅ " + displayName + " started." : "⚠️ Failed to start " + displayName);
                }
                break;
                
            case "stop":
                if (!isRunning) {
                    ctx.sendMessage(chatId, "ℹ️ " + displayName + " is not running.");
                } else {
                    boolean ok = stopDaemon(processName, ctx);
                    if (ok) {
                        // Mark as stopped via Telegram - health check should NOT auto-restart
                        saveDaemonState(name, false, ctx);
                    }
                    ctx.sendMessage(chatId, ok ? "⛔ " + displayName + " stopped." : "⚠️ Failed to stop " + displayName);
                }
                break;
                
            case "status":
                ctx.sendMessage(chatId, displayName + ": " + (isRunning ? "✅ Running" : "⛔ Stopped"));
                break;
                
            default:
                ctx.sendMessage(chatId, "Usage: /daemon " + name + " start|stop|status");
        }
    }
    
    private String[] findDaemon(String name) {
        for (String[] d : DAEMONS) {
            if (d[0].equals(name)) return d;
        }
        return null;
    }
    
    /**
     * Check if daemon is running using process name.
     * Same approach as AccSentryDaemonController.
     */
    private boolean isDaemonRunning(String processName, CommandContext ctx) {
        // Use grep -F for fixed string matching (handles hyphens in process names like sing-box)
        String output = ctx.execShell("ps -A | grep -F '" + processName + "' | grep -v grep");
        return output != null && !output.trim().isEmpty();
    }
    
    /**
     * Stop daemon using killall -9.
     * Same approach as AccSentryDaemonController.
     */
    private boolean stopDaemon(String processName, CommandContext ctx) {
        ctx.log("Stopping daemon: " + processName);
        
        // For camera daemon, also kill the restart wrapper script and delete it
        if ("byd_cam_daemon".equals(processName)) {
            ctx.execShell("pkill -9 -f start_cam_daemon 2>/dev/null");
            ctx.execShell("rm -f /data/local/tmp/start_cam_daemon.sh 2>/dev/null");
            ctx.execShell("rm -f /data/local/tmp/camera_daemon.lock 2>/dev/null");
        }
        
        // For acc sentry daemon, also kill the watchdog script
        if ("acc_sentry_daemon".equals(processName)) {
            ctx.execShell("pkill -9 -f start_acc_sentry 2>/dev/null");
            ctx.execShell("rm -f /data/local/tmp/start_acc_sentry.sh 2>/dev/null");
            ctx.execShell("rm -f /data/local/tmp/acc_sentry_daemon.lock 2>/dev/null");
        }
        
        // Use pkill -9 -f to match full command line (same as DaemonLauncher.kt)
        ctx.execShell("pkill -9 -f " + processName + " 2>/dev/null");
        
        // Clean up lock file for daemons that use processName-based lock files
        if (!"byd_cam_daemon".equals(processName) && !"acc_sentry_daemon".equals(processName)) {
            ctx.execShell("rm -f /data/local/tmp/" + processName + ".lock 2>/dev/null");
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
     * Start daemon using app_process.
     */
    private boolean startDaemon(String className, CommandContext ctx) {
        ctx.log("Starting daemon: " + className);
        
        // Get APK path
        String apkPath = ctx.execShell("pm path com.overdrive.app | head -1 | cut -d: -f2");
        if (apkPath == null || apkPath.trim().isEmpty()) {
            ctx.log("Cannot find APK path");
            return false;
        }
        apkPath = apkPath.trim();
        
        String fullClass = "com.overdrive.app.daemon." + className;
        String cmd = String.format("CLASSPATH=%s app_process / %s &", apkPath, fullClass);
        
        ctx.execShell(cmd);
        
        // Wait and verify
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        
        return true; // Assume success - verification is tricky for background processes
    }
    
    /**
     * Start external binary daemon via shell command.
     */
    private boolean startShellDaemon(String name, CommandContext ctx) {
        ctx.log("Starting shell daemon: " + name);
        
        String cmd;
        String processName;
        
        switch (name) {
            case "cloudflared":
                // Cloudflared tunnel - match UI version (TunnelLauncher.kt)
                // Check if sing-box proxy is running
                String singboxCheck = ctx.execShell("pgrep -f sing-box");
                boolean useProxy = singboxCheck != null && !singboxCheck.trim().isEmpty();
                
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
                cfCmd.append("/data/local/tmp/cloudflared tunnel --url http://127.0.0.1:8080 ");
                cfCmd.append("--edge-ip-version 4 --protocol http2 --no-autoupdate ");
                cfCmd.append("--retries 20 --grace-period 45s");
                cfCmd.append("' > /data/local/tmp/cloudflared.log 2>&1 &");
                
                cmd = cfCmd.toString();
                processName = "cloudflared";
                break;
                
            case "zrok":
                // Zrok tunnel - IMPORTANT: Only enable if identity file is missing!
                // Free tier has 5-device limit. `enable` uses a slot, `share` is unlimited.
                String identityCheck = ctx.execShell("test -f /data/local/tmp/.zrok/environment.json && echo yes || echo no");
                if (identityCheck == null || !identityCheck.trim().equals("yes")) {
                    // Need to enable - THIS COUNTS AGAINST THE 5-DEVICE LIMIT!
                    ctx.log("⚠️ Device not enabled. Registering now (uses 1 of 5 slots)...");
                    String enableCmd = "HOME=/data/local/tmp " +
                        "ALL_PROXY=socks5://127.0.0.1:8119 " +
                        "HTTP_PROXY=socks5://127.0.0.1:8119 " +
                        "HTTPS_PROXY=socks5://127.0.0.1:8119 " +
                        "NO_PROXY=localhost,127.0.0.1 " +
                        "/data/local/tmp/zrok enable 0QBZzB74VgX7 --headless 2>&1";
                    String enableResult = ctx.execShell(enableCmd);
                    ctx.log("Enable result: " + enableResult);
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                } else {
                    ctx.log("✅ Device already enabled. Skipping registration.");
                }
                // Start zrok share (SAFE - unlimited restarts)
                cmd = "nohup sh -c 'HOME=/data/local/tmp " +
                      "ALL_PROXY=socks5://127.0.0.1:8119 " +
                      "HTTP_PROXY=socks5://127.0.0.1:8119 " +
                      "HTTPS_PROXY=socks5://127.0.0.1:8119 " +
                      "NO_PROXY=localhost,127.0.0.1 " +
                      "/data/local/tmp/zrok share public http://localhost:8080 --headless' " +
                      "> /data/local/tmp/zrok.log 2>&1 &";
                processName = "zrok";
                break;
                
            case "singbox":
                // Sing-box proxy
                cmd = "nohup /data/local/tmp/sing-box run -c /data/local/tmp/singbox_config.json " +
                      "> /data/local/tmp/singbox.log 2>&1 &";
                processName = "sing-box";
                break;
                
            default:
                ctx.log("Unknown shell daemon: " + name);
                return false;
        }
        
        ctx.execShell(cmd);
        
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
                String grepResult = ctx.execShell("grep -o 'https://[a-z0-9-]*\\.trycloudflare\\.com' /data/local/tmp/cloudflared.log 2>/dev/null | grep -v 'api\\.' | head -1");
                if (grepResult != null && grepResult.startsWith("https://") && grepResult.contains("-")) {
                    tunnelUrl = grepResult.trim();
                    ctx.log("Tunnel URL: " + tunnelUrl);
                    // Save URL to file for /url command
                    saveTunnelUrl(tunnelUrl, ctx);
                    break;
                }
                // Check for errors (only read last few lines)
                String tailLog = ctx.execShell("tail -5 /data/local/tmp/cloudflared.log 2>/dev/null");
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
                    ctx.log("Cloudflared exited - check /data/local/tmp/cloudflared.log");
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
                String grepResult = ctx.execShell("grep -o 'https://[a-z0-9]*\\.share\\.zrok\\.io' /data/local/tmp/zrok.log 2>/dev/null | head -1");
                if (grepResult != null && grepResult.startsWith("https://")) {
                    zrokUrl = grepResult.trim();
                    ctx.log("Zrok URL: " + zrokUrl);
                    // Save URL to file for /url command and send notification
                    saveTunnelUrl(zrokUrl, ctx);
                    break;
                }
                // Check for errors (only read last few lines)
                String tailLog = ctx.execShell("tail -5 /data/local/tmp/zrok.log 2>/dev/null");
                if (tailLog != null && (tailLog.contains("error") || tailLog.contains("failed"))) {
                    ctx.log("Zrok error detected in log");
                    // Don't return false - zrok might still be starting
                }
            }
            if (zrokUrl == null) {
                // Check if process is still running
                if (!isDaemonRunning("zrok", ctx)) {
                    ctx.log("Zrok exited - check /data/local/tmp/zrok.log");
                    return false;
                }
                ctx.log("Zrok started but URL not yet available");
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
            ctx.execShell("echo '" + url + "' > /data/local/tmp/tunnel_url.txt");
            ctx.log("Tunnel URL saved to file");
            
            // Send notification message to owner
            // Read owner_chat_id from config file
            String ownerStr = ctx.execShell("grep owner_chat_id /data/local/tmp/telegram_config.properties 2>/dev/null | cut -d= -f2");
            if (ownerStr != null && !ownerStr.trim().isEmpty()) {
                try {
                    long ownerChatId = Long.parseLong(ownerStr.trim());
                    if (ownerChatId > 0) {
                        ctx.sendMessage(ownerChatId, "🌐 *Tunnel URL*\n" + url);
                        ctx.log("Tunnel URL notification sent to owner");
                    }
                } catch (NumberFormatException e) {
                    ctx.log("Invalid owner_chat_id in config");
                }
            }
        } catch (Exception e) {
            ctx.log("Error saving tunnel URL: " + e.getMessage());
        }
    }
    
    /**
     * Save daemon state to file so health checks can honor Telegram-initiated stops.
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
