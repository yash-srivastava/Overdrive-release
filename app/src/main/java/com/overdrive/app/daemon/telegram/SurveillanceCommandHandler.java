package com.overdrive.app.daemon.telegram;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Handles surveillance commands: /start, /stop, /status
 */
public class SurveillanceCommandHandler implements TelegramCommandHandler {
    
    private static final int SURVEILLANCE_IPC_PORT = 19877;
    
    @Override
    public boolean canHandle(String command) {
        return "/start".equals(command) || "/stop".equals(command) || "/status".equals(command);
    }
    
    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        String cmd = args[0].toLowerCase(Locale.ROOT);
        
        switch (cmd) {
            case "/start":
                handleStart(chatId, ctx);
                break;
            case "/stop":
                handleStop(chatId, ctx);
                break;
            case "/status":
                handleStatus(chatId, ctx);
                break;
        }
    }
    
    private void handleStart(long chatId, CommandContext ctx) {
        JSONObject response = sendSurveillanceCommand("START", ctx);
        if (response != null && response.optBoolean("success", false)) {
            String[][][] buttons = {{{ctx.tr("buttons.stop"), "cmd:/stop"}, {ctx.tr("buttons.status"), "cmd:/status"}}};
            // deferred: saved while the car is on (or ACC not yet known) — nothing armed yet.
            String message = response.optBoolean("deferred", false)
                    ? "surveillance.start_deferred" : "surveillance.started";
            ctx.sendMessageWithButtons(chatId, ctx.tr(message), buttons);
        } else {
            ctx.sendMessage(chatId, ctx.tr("surveillance.start_failed"));
        }
    }
    
    private void handleStop(long chatId, CommandContext ctx) {
        JSONObject response = sendSurveillanceCommand("STOP", ctx);
        if (response != null && response.optBoolean("success", false)) {
            String[][][] buttons = {{{ctx.tr("buttons.start"), "cmd:/start"}, {ctx.tr("buttons.status"), "cmd:/status"}}};
            ctx.sendMessageWithButtons(chatId, ctx.tr("surveillance.stopped"), buttons);
        } else {
            ctx.sendMessage(chatId, ctx.tr("surveillance.stop_failed"));
        }
    }
    
    private void handleStatus(long chatId, CommandContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(ctx.tr("surveillance.status_title"));
        
        // Surveillance status
        JSONObject survStatus = sendSurveillanceCommand("STATUS", ctx);
        boolean survEnabled = survStatus != null && survStatus.optBoolean("enabled", false);
        sb.append(ctx.tr(survEnabled
                ? "surveillance.status_active"
                : "surveillance.status_inactive"));
        
        // Temperature
        sb.append(ctx.tr("surveillance.temperature", getTemperature(ctx)));
        
        // All daemons - check all known process names
        sb.append(ctx.tr("surveillance.daemons_heading"));
        String[][] allDaemons = {
            {"byd_cam_daemon", "daemon_names.camera"},
            {"acc_sentry_daemon", "daemon_names.acc_sentry"},
            {"sentry_daemon", "daemon_names.sentry"},
            {"telegram_bot_daemon", "daemon_names.telegram"},
            {"SurveillanceDaemon", "daemon_names.surveillance"},
            {"cloudflared", "daemon_names.cloudflare_tunnel"},
            {"zrok", "daemon_names.zrok_tunnel"},
            {"sing-box", "daemon_names.sing_box"}
        };
        
        int runningCount = 0;
        for (String[] d : allDaemons) {
            if (isDaemonRunning(d[0], ctx)) {
                sb.append(ctx.tr("surveillance.daemon_running", ctx.tr(d[1])));
                runningCount++;
            }
        }
        
        if (runningCount == 0) {
            sb.append(ctx.tr("surveillance.no_daemons_running"));
        }
        
        // Buttons
        String[][][] buttons;
        if (survEnabled) {
            buttons = new String[][][]{{{ctx.tr("buttons.stop_surveillance"), "cmd:/stop"}, {ctx.tr("buttons.events"), "cmd:/events"}}};
        } else {
            buttons = new String[][][]{{{ctx.tr("buttons.start_surveillance"), "cmd:/start"}, {ctx.tr("buttons.events"), "cmd:/events"}}};
        }
        
        ctx.sendMessageWithButtons(chatId, sb.toString(), buttons);
    }
    
    private JSONObject sendSurveillanceCommand(String command, CommandContext ctx) {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("command", command);
            return ctx.sendIpcCommand(SURVEILLANCE_IPC_PORT, cmd);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static final String[] CPU_THERMAL_KEYWORDS = {
        "cpu", "soc", "cluster", "little", "big", "prime",
        "cpu-thermal", "cpu_thermal", "cpuss", "cpuss-0", "cpuss-1",
        "cpu-0-0", "cpu-0-1", "cpu-1-0", "cpu-1-1", "tsens_tz_sensor"
    };

    private String getTemperature(CommandContext ctx) {
        double c = readCpuTemperatureCelsius();
        if (c <= 0) return ctx.tr("surveillance.not_available");
        String emoji = c > 60 ? "🔥" : (c > 45 ? "🌡️" : "✅");
        return String.format("%s %.0f°C", emoji, c);
    }

    private double readCpuTemperatureCelsius() {
        for (int i = 0; i < 30; i++) {
            try {
                java.io.File typeFile = new java.io.File("/sys/class/thermal/thermal_zone" + i + "/type");
                if (!RemovableStorageFs.exists(typeFile)) continue;
                String type;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(typeFile))) {
                    type = br.readLine();
                }
                if (type == null) continue;
                String typeLower = type.toLowerCase().trim();
                boolean match = false;
                for (String kw : CPU_THERMAL_KEYWORDS) {
                    if (typeLower.contains(kw)) { match = true; break; }
                }
                if (!match) continue;

                java.io.File tempFile = new java.io.File("/sys/class/thermal/thermal_zone" + i + "/temp");
                if (!RemovableStorageFs.exists(tempFile)) continue;
                String tempLine;
                try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(tempFile))) {
                    tempLine = br.readLine();
                }
                if (tempLine == null || tempLine.trim().isEmpty()) continue;
                double raw = Double.parseDouble(tempLine.trim());
                double result = raw > 1000 ? raw / 1000.0 : raw;
                if (result >= 10 && result <= 120) return result;
            } catch (Exception ignored) {}
        }
        return 0;
    }
    
    private boolean isDaemonRunning(String processName, CommandContext ctx) {
        // Use grep -F for fixed string matching (handles hyphens in process names like sing-box)
        String output = ctx.execShell(
                "ps -A | " + DaemonCommandHandler.processMatcher(processName));
        return output != null && !output.trim().isEmpty();
    }
}
