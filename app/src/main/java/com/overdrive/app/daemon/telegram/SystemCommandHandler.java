package com.overdrive.app.daemon.telegram;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import com.overdrive.app.util.ScratchPaths;

/**
 * Handles system commands: /daemons, /url, /help
 */
public class SystemCommandHandler implements TelegramCommandHandler {
    
    @Override
    public boolean canHandle(String command) {
        return "/daemons".equals(command) || "/url".equals(command) || "/help".equals(command);
    }
    
    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String cmd = args[0].toLowerCase(Locale.ROOT);
        
        switch (cmd) {
            case "/daemons":
                handleDaemons(chatId, ctx);
                break;
            case "/url":
                handleUrl(chatId, ctx);
                break;
            case "/help":
                handleHelp(chatId, ctx);
                break;
        }
    }
    
    private void handleDaemons(long chatId, CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.tr("daemons.title"));
        
        // All known daemons: {cmdName, processName, displayName, canStart, canStop}
        // cmdName is used for /daemon <name> start|stop
        // canStart: "yes" if can be started via telegram, "no" if must use app UI
        // canStop: "yes" if can be stopped via telegram, "no" if should not be stopped remotely
        String[][] allDaemons = {
            {"camera", "byd_cam_daemon", "daemon_names.camera", "yes", "yes"},
            {"acc", "acc_sentry_daemon", "daemon_names.acc_sentry", "yes", "yes"},
            {"sentry", "sentry_daemon", "daemon_names.sentry", "yes", "yes"},
            {"telegram", "telegram_bot_daemon", "daemon_names.telegram", "no", "no"},
            {"cloudflared", "cloudflared", "daemon_names.cloudflare_tunnel", "yes", "yes"},
            {"zrok", "zrok", "daemon_names.zrok_tunnel", "yes", "yes"},
            {"tailscale", "tailscaled", "daemon_names.tailscale_tunnel", "yes", "yes"},
            {"singbox", "sing-box", "daemon_names.sing_box", "yes", "no"}
        };
        
        java.util.List<String[][]> buttonRows = new java.util.ArrayList<>();
        int runningCount = 0;
        int stoppedCount = 0;
        
        // First pass: show running daemons
        for (String[] d : allDaemons) {
            String cmdName = d[0];
            String processName = d[1];
            String displayName = ctx.tr(d[2]);
            boolean canStart = "yes".equals(d[3]);
            boolean canStop = "yes".equals(d[4]);
            boolean running = isDaemonRunning(processName, ctx);
            
            if (running) {
                sb.append("✅ ").append(displayName).append("\n");
                runningCount++;
                
                // Add stop button if allowed
                if (canStop) {
                    buttonRows.add(new String[][]{{ctx.tr("buttons.stop_named", displayName), "dm:" + cmdName + ":stop"}});
                }
            } else {
                sb.append("⛔ ").append(displayName).append("\n");
                stoppedCount++;
                
                // Add start button for startable daemons
                if (canStart) {
                    buttonRows.add(new String[][]{{ctx.tr("buttons.start_named", displayName), "dm:" + cmdName + ":start"}});
                }
            }
        }
        
        // Add refresh button
        buttonRows.add(new String[][]{{ctx.tr("buttons.refresh"), "cmd:/daemons"}});
        
        String[][][] buttons = buttonRows.toArray(new String[0][][]);
        ctx.sendMessageWithButtons(chatId, sb.toString(), buttons);
    }
    
    private void handleUrl(long chatId, CommandContext ctx) {
        try {
            // Check each tunnel independently and collect URLs for any that are running.
            String cloudflaredRunning = ctx.execShell("pgrep -f cloudflared");
            String zrokRunning = ctx.execShell("pgrep -f zrok");
            String tailscaleRunning = ctx.execShell("pgrep -f tailscaled");

            boolean cfUp = cloudflaredRunning != null && !cloudflaredRunning.trim().isEmpty();
            boolean zrokUp = zrokRunning != null && !zrokRunning.trim().isEmpty();
            boolean tailscaleUp = tailscaleRunning != null && !tailscaleRunning.trim().isEmpty();

            if (!cfUp && !zrokUp && !tailscaleUp) {
                ctx.sendMessage(chatId, ctx.tr("url.none_running"));
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(ctx.tr("url.title"));
            int resolved = 0;
            int pending = 0;

            if (cfUp) {
                String url = null;
                boolean isPaid = com.overdrive.app.config.CloudflaredPaidConfig.isPaidVersion();
                String token = com.overdrive.app.config.CloudflaredPaidConfig.getToken();
                if (isPaid && !token.isEmpty()) {
                    String grepResult = ctx.execShell("grep -iE 'ingress|hostname' " + ScratchPaths.getDir() + "/cloudflared.log 2>/dev/null | grep -oE '[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}' | grep -vE '127\\.0\\.0\\.1' | tail -1");
                    // execShell returns "" (not null) on no match — require a real host.
                    if (grepResult != null && !grepResult.trim().isEmpty() && grepResult.contains(".")) {
                        url = "https://" + grepResult.trim();
                    }
                } else {
                    String grepResult = ctx.execShell("grep -o 'https://[a-z0-9-]*\\.trycloudflare\\.com' " + ScratchPaths.getDir() + "/cloudflared.log 2>/dev/null | grep -v 'api\\.' | head -1");
                    if (grepResult != null && grepResult.startsWith("https://") && grepResult.contains("-")) {
                        url = grepResult.trim();
                    }
                }
                if (url != null) {
                    sb.append(ctx.tr("url.resolved_line",
                            ctx.tr("daemon_names.cloudflare_tunnel"), url));
                    resolved++;
                } else {
                    sb.append(ctx.tr("url.pending_line",
                            ctx.tr("daemon_names.cloudflare_tunnel")));
                    pending++;
                }
            }

            if (zrokUp) {
                String url = null;
                String grepResult = ctx.execShell("grep -o 'https://[a-z0-9-]*\\.share\\.zrok\\.io' " + ScratchPaths.getDir() + "/zrok.log 2>/dev/null | tail -1");
                if (grepResult != null && grepResult.startsWith("https://")) {
                    url = grepResult.trim();
                }
                if (url != null) {
                    sb.append(ctx.tr("url.resolved_line",
                            ctx.tr("daemon_names.zrok_tunnel"), url));
                    resolved++;
                } else {
                    sb.append(ctx.tr("url.pending_line",
                            ctx.tr("daemon_names.zrok_tunnel")));
                    pending++;
                }
            }

            if (tailscaleUp) {
                String url = null;
                String getIpResult = ctx.execShell(ScratchPaths.path(".tailscale/tailscale --socket 127.0.0.1:8532 ip --1"));
                if (getIpResult != null && !getIpResult.trim().isEmpty()) {
                    url = "http://" + getIpResult.trim() + ":8080";
                }
                if (url != null) {
                    sb.append(ctx.tr("url.resolved_line",
                            ctx.tr("daemon_names.tailscale_tunnel"), url));
                    resolved++;
                } else {
                    sb.append(ctx.tr("url.pending_line",
                            ctx.tr("daemon_names.tailscale_tunnel")));
                    pending++;
                }
            }

            // Last-resort fallback: if nothing resolved from logs, try the saved URL file.
            if (resolved == 0) {
                File urlFile = new File(ScratchPaths.path("tunnel_url.txt"));
                if (urlFile.exists()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(urlFile)));
                    String saved = reader.readLine();
                    reader.close();
                    if (saved != null && !saved.isEmpty()) {
                        sb.append(ctx.tr("url.last_known", saved.trim()));
                    }
                }
            }

            if (pending > 0) {
                sb.append(ctx.tr("url.retry_pending"));
            }

            ctx.sendMessage(chatId, sb.toString());
        } catch (Exception e) {
            ctx.sendMessage(chatId, ctx.tr("url.error",
                    ctx.technicalDetail(e.getMessage())));
        }
    }
    
    private void handleHelp(long chatId, CommandContext ctx) {
        // App display version: getDisplayVersionFromFile() prefers the persisted
        // GitHub label from /data/local/tmp/overdrive_version (the cross-UID
        // source of truth), falling back to the BuildConfig identity
        // (getInstalledVersion = channel + "-v" + versionName) when the file is
        // absent, malformed, or a stale cross-channel label.
        String version = com.overdrive.app.updater.AppUpdater.getDisplayVersionFromFile();

        String text = ctx.tr("help.text", version);

        String[][][] buttons = {
            {{ctx.tr("buttons.status"), "cmd:/status"}, {ctx.tr("buttons.events"), "cmd:/events"}},
            {{ctx.tr("buttons.start_surveillance"), "cmd:/start"}, {ctx.tr("buttons.stop_surveillance"), "cmd:/stop"}},
            {{ctx.tr("buttons.daemons"), "cmd:/daemons"}, {ctx.tr("buttons.tunnel_url"), "cmd:/url"}},
            {{ctx.tr("buttons.check_update"), "cmd:/update"}, {ctx.tr("buttons.backup"), "cmd:/backup"}}
        };
        
        ctx.sendMessageWithButtons(chatId, text, buttons);
    }
    
    private boolean isDaemonRunning(String processName, CommandContext ctx) {
        // Use grep -F for fixed string matching (handles hyphens in process names like sing-box)
        String output = ctx.execShell(
                "ps -A | " + DaemonCommandHandler.processMatcher(processName));
        return output != null && !output.trim().isEmpty();
    }
}
