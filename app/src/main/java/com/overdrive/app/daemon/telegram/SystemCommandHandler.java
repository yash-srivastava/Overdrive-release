package com.overdrive.app.daemon.telegram;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

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
            // Check which tunnel is running and get its URL
            String cloudflaredRunning = ctx.execShell("pgrep -f cloudflared");
            String zrokRunning = ctx.execShell("pgrep -f zrok");
            
            boolean cfUp = cloudflaredRunning != null && !cloudflaredRunning.trim().isEmpty();
            boolean zrokUp = zrokRunning != null && !zrokRunning.trim().isEmpty();
            
            if (!cfUp && !zrokUp) {
                ctx.sendMessage(chatId, "⚠️ No tunnel running\n\nStart one with:\n`/daemon cloudflared start`\n`/daemon zrok start`");
                return;
            }
            
            String url = null;
            String tunnelType = null;
            
            // Prefer cloudflared if both are running (shouldn't happen but just in case)
            if (cfUp) {
                tunnelType = "Cloudflared";
                // SOTA FIX: Use grep instead of cat to avoid loading entire log into memory
                String grepResult = ctx.execShell("grep -o 'https://[a-z0-9-]*\\.trycloudflare\\.com' /data/local/tmp/cloudflared.log 2>/dev/null | grep -v 'api\\.' | head -1");
                if (grepResult != null && grepResult.startsWith("https://") && grepResult.contains("-")) {
                    url = grepResult.trim();
                }
            } else if (zrokUp) {
                tunnelType = "Zrok";
                // SOTA FIX: Use grep instead of cat to avoid loading entire log into memory
                String grepResult = ctx.execShell("grep -o 'https://[a-z0-9]*\\.share\\.zrok\\.io' /data/local/tmp/zrok.log 2>/dev/null | head -1");
                // Does not handle self hosted zrok. Will fall back to tunnel_url for now
                if (grepResult != null && grepResult.startsWith("https://")) {
                    url = grepResult.trim();
                }
            }
            
            // Fallback to saved URL file if log parsing failed
            if (url == null) {
                File urlFile = new File("/data/local/tmp/tunnel_url.txt");
                if (urlFile.exists()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(urlFile)));
                    url = reader.readLine();
                    reader.close();
                }
            }
            
            if (url != null && !url.isEmpty()) {
                ctx.sendMessage(chatId, "🌐 *" + tunnelType + " Tunnel*\n" + url);
            } else {
                ctx.sendMessage(chatId, "⚠️ " + tunnelType + " is running but URL not available yet.\nTry again in a few seconds.");
            }
        } catch (Exception e) {
            ctx.sendMessage(chatId, "⚠️ Error: " + e.getMessage());
        }
    }
    
    private void handleHelp(long chatId, CommandContext ctx) {
        String text = "📖 *Commands*\n\n" +
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
