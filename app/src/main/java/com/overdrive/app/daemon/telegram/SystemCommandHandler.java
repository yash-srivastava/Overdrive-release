package com.overdrive.app.daemon.telegram;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import static com.overdrive.app.launcher.TunnelLauncherKt.CLOUDFLARED_TUNNEL_URL;

import com.overdrive.app.ui.util.PreferencesManager;


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
        String cmd = args[0].toLowerCase();
        
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
        sb.append("🤖 *Daemons*\n\n");
        
        // All known daemons: {cmdName, processName, displayName, canStart, canStop}
        // cmdName is used for /daemon <name> start|stop
        // canStart: "yes" if can be started via telegram, "no" if must use app UI
        // canStop: "yes" if can be stopped via telegram, "no" if should not be stopped remotely
        String[][] allDaemons = {
            {"camera", "byd_cam_daemon", "Camera", "yes", "yes"},
            {"acc", "acc_sentry_daemon", "ACC Sentry", "yes", "yes"},
            {"sentry", "sentry_daemon", "Sentry", "yes", "yes"},
            {"telegram", "telegram_bot_daemon", "Telegram", "no", "no"},
            {"cloudflared", "cloudflared", "Cloudflare Tunnel", "yes", "yes"},
            {"zrok", "zrok", "Zrok Tunnel", "yes", "yes"},
            {"tailscale", "tailscaled", "Tailscale Tunnel", "yes", "yes"},
            {"singbox", "sing-box", "Sing-Box", "yes", "no"}
        };
        
        java.util.List<String[][]> buttonRows = new java.util.ArrayList<>();
        int runningCount = 0;
        int stoppedCount = 0;
        
        // First pass: show running daemons
        for (String[] d : allDaemons) {
            String cmdName = d[0];
            String processName = d[1];
            String displayName = d[2];
            boolean canStart = "yes".equals(d[3]);
            boolean canStop = "yes".equals(d[4]);
            boolean running = isDaemonRunning(processName, ctx);
            
            if (running) {
                sb.append("✅ ").append(displayName).append("\n");
                runningCount++;
                
                // Add stop button if allowed
                if (canStop) {
                    buttonRows.add(new String[][]{{"⛔ Stop " + displayName, "dm:" + cmdName + ":stop"}});
                }
            } else {
                sb.append("⛔ ").append(displayName).append("\n");
                stoppedCount++;
                
                // Add start button for startable daemons
                if (canStart) {
                    buttonRows.add(new String[][]{{"✅ Start " + displayName, "dm:" + cmdName + ":start"}});
                }
            }
        }
        
        // Add refresh button
        buttonRows.add(new String[][]{{"🔄 Refresh", "cmd:/daemons"}});
        
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
                ctx.sendMessage(chatId, "⚠️ No tunnel running\n\nStart one with:\n`/daemon cloudflared start`\n`/daemon zrok start`\n`/daemon tailscale start`");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("🌐 *Tunnel URLs*\n\n");
            int resolved = 0;
            int pending = 0;
            boolean isPaid = PreferencesManager.isCloudflarePaid();
            String token = PreferencesManager.getCloudflareToken();
            if (cfUp) {

                if (isPaid && !token.isEmpty()) {
                    if(!CLOUDFLARED_TUNNEL_URL.isEmpty()) {
                        sb.append("• *Cloudflared:* ").append(CLOUDFLARED_TUNNEL_URL).append("\n");
                        resolved++;
                    }
                    else{
                            sb.append("• *Cloudflared:* _starting, URL not available yet_\n");
                            pending++;
                    }
                }
                else{
                    String url = null;
                    String grepResult = ctx.execShell("grep -o 'https://[a-z0-9-]*\\.trycloudflare\\.com' /data/local/tmp/cloudflared.log 2>/dev/null | grep -v 'api\\.' | head -1");
                    if (grepResult != null && grepResult.startsWith("https://") && grepResult.contains("-")) {
                        url = grepResult.trim();
                    }
                    if (url != null) {
                        sb.append("• *Cloudflared:* ").append(url).append("\n");
                        resolved++;
                    } else {
                        sb.append("• *Cloudflared:* _starting, URL not available yet_\n");
                        pending++;
                    }
                }


            }

            if (zrokUp) {
                String url = null;
                String grepResult = ctx.execShell("grep -o 'https://[a-z0-9]*\\.share\\.zrok\\.io' /data/local/tmp/zrok.log 2>/dev/null | head -1");
                if (grepResult != null && grepResult.startsWith("https://")) {
                    url = grepResult.trim();
                }
                if (url != null) {
                    sb.append("• *Zrok:* ").append(url).append("\n");
                    resolved++;
                } else {
                    sb.append("• *Zrok:* _starting, URL not available yet_\n");
                    pending++;
                }
            }

            if (tailscaleUp) {
                String url = null;
                String getIpResult = ctx.execShell("/data/local/tmp/.tailscale/tailscale --socket 127.0.0.1:8532 ip --1");
                if (getIpResult != null && !getIpResult.trim().isEmpty()) {
                    url = "http://" + getIpResult.trim() + ":8080";
                }
                if (url != null) {
                    sb.append("• *Tailscale:* ").append(url).append("\n");
                    resolved++;
                } else {
                    sb.append("• *Tailscale:* _starting, URL not available yet_\n");
                    pending++;
                }
            }

            // Last-resort fallback: if nothing resolved from logs, try the saved URL file.
            if (resolved == 0) {
                File urlFile = new File("/data/local/tmp/tunnel_url.txt");
                if (urlFile.exists()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(urlFile)));
                    String saved = reader.readLine();
                    reader.close();
                    if (saved != null && !saved.isEmpty()) {
                        sb.append("\n_Last known:_ ").append(saved.trim()).append("\n");
                    }
                }
            }

            if (pending > 0) {
                sb.append("\n_Try again in a few seconds for pending URLs._");
            }

            ctx.sendMessage(chatId, sb.toString());
        } catch (Exception e) {
            ctx.sendMessage(chatId, "⚠️ Error: " + e.getMessage());
        }
    }
    
    private void handleHelp(long chatId, CommandContext ctx) {
        // Read the persisted app version. AppUpdater writes /data/local/tmp/overdrive_version
        // on every check/install — this method reads from that file and falls
        // back to BuildConfig.VERSION_NAME when the file is missing.
        String version = com.overdrive.app.updater.AppUpdater.getDisplayVersionFromFile();

        String text = "📖 *Commands*\n" +
                "_OverDrive " + version + "_\n\n" +
                "*Surveillance*\n" +
                "`/start` - Start surveillance\n" +
                "`/stop` - Stop surveillance\n" +
                "`/status` - System status\n\n" +
                "*Events*\n" +
                "`/events [hours] [page]` - List recordings\n" +
                "`/download <file>` - Download video\n\n" +
                "*Daemons*\n" +
                "`/daemons` - List all daemons\n" +
                "`/daemon <name> start|stop`\n\n" +
                "*System*\n" +
                "`/url` - Tunnel URL\n" +
                "`/help` - This message";
        
        String[][][] buttons = {
            {{"📊 Status", "cmd:/status"}, {"📹 Events", "cmd:/events"}},
            {{"✅ Start Surveillance", "cmd:/start"}, {"⛔ Stop Surveillance", "cmd:/stop"}},
            {{"🤖 Daemons", "cmd:/daemons"}, {"🌐 Tunnel URL", "cmd:/url"}}
        };
        
        ctx.sendMessageWithButtons(chatId, text, buttons);
    }
    
    private boolean isDaemonRunning(String processName, CommandContext ctx) {
        // Use grep -F for fixed string matching (handles hyphens in process names like sing-box)
        String output = ctx.execShell("ps -A | grep -F '" + processName + "' | grep -v grep");
        return output != null && !output.trim().isEmpty();
    }
}
