package com.overdrive.app.daemon.telegram;

import com.overdrive.app.server.Messages;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Handles {@code /where} — "where did I park?". Asks the camera daemon for the
 * open (or most recent) Parking Intelligence session over IPC and answers
 * with place, garage level / zone / bay when read, GPS freshness, time
 * parked and a maps link. Read-only; the daemon owns the data.
 */
public class WhereCommandHandler implements TelegramCommandHandler {

    private static final int CAMERA_IPC_PORT = 19877;

    @Override
    public boolean canHandle(String command) {
        return "/where".equals(command) || "/parking".equals(command);
    }

    @Override
    public void handle(long chatId, String[] args, CommandContext ctx) {
        JSONObject req = new JSONObject();
        try { req.put("command", "PARKING_STATUS"); } catch (Exception ignored) {}
        JSONObject resp = ctx.sendIpcCommand(CAMERA_IPC_PORT, req);
        if (resp == null || !resp.optBoolean("success", false)) {
            ctx.sendMessage(chatId, ctx.tr("parking.where.unavailable"));
            return;
        }
        if (!resp.optBoolean("enabled", false)) {
            ctx.sendMessage(chatId, ctx.tr("parking.where.disabled"));
            return;
        }
        JSONObject s = resp.optJSONObject("current");
        boolean open = s != null;
        if (s == null) s = resp.optJSONObject("latest");
        if (s == null) {
            ctx.sendMessage(chatId, ctx.tr("parking.where.none"));
            return;
        }
        ctx.sendMessage(chatId, format(s, open, ctx));
    }

    /** Package-visible for tests. */
    static String format(JSONObject s, boolean open, CommandContext ctx) {
        StringBuilder b = new StringBuilder();
        b.append(open ? ctx.tr("parking.where.title_open") : ctx.tr("parking.where.title_last")).append("\n");

        String place = null;
        if (s.has("safeZone")) place = s.optString("safeZone", null);
        JSONObject p = s.optJSONObject("place");
        if (place == null && p != null) {
            place = p.optString("short", null);
            if (place == null || place.isEmpty()) place = p.optString("displayName", null);
        }
        if (place != null && !place.isEmpty()) b.append("📍 ").append(esc(place)).append("\n");

        JSONObject signage = s.optJSONObject("signage");
        if (signage != null && signage.optBoolean("found", false)) {
            String label = signage.optString("label", "");
            if (!label.isEmpty()) b.append("🅿️ ").append(esc(label)).append("\n");
        }

        JSONObject gps = s.optJSONObject("gps");
        String quality = gps == null ? "UNKNOWN" : gps.optString("quality", "UNKNOWN");
        b.append("🛰 ").append(gpsLabel(quality, ctx)).append("\n");

        long started = s.optLong("startedMs", 0L);
        if (started > 0) {
            long end = s.optLong("endedMs", 0L);
            long ref = end > 0 ? end : System.currentTimeMillis();
            b.append("⏱ ").append(ctx.tr(open ? "parking.where.parked_for" : "parking.where.was_away",
                    duration(Math.max(0L, ref - started)))).append("\n");
        }
        int events = s.optInt("eventCount", 0);
        int neighbours = s.optInt("neighbourCount", 0);
        b.append("👀 ").append(ctx.tr("parking.where.counts", events, neighbours)).append("\n");

        if (gps != null && gps.has("lat") && gps.has("lng")) {
            b.append(String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f",
                    gps.optDouble("lat"), gps.optDouble("lng")));
        }
        return b.toString().trim();
    }

    private static String gpsLabel(String quality, CommandContext ctx) {
        switch (quality) {
            case "FRESH": return ctx.tr("parking.where.gps_fresh");
            case "RECENT": return ctx.tr("parking.where.gps_recent");
            case "STALE": return ctx.tr("parking.where.gps_stale");
            default: return ctx.tr("parking.where.gps_unknown");
        }
    }

    static String duration(long ms) {
        long mins = ms / 60_000L;
        long h = mins / 60, m = mins % 60;
        String hour = Messages.get("units.hour_compact");
        if (hour == null || hour.isEmpty() || "units.hour_compact".equals(hour)) hour = "h";
        String hourTail = "h".equals(hour) ? " h" : " " + hour;
        if (h >= 48) return (h / 24) + " d " + (h % 24) + hourTail;
        String unit = Messages.get("units.minute_compact");
        if (unit == null || unit.isEmpty() || "units.minute_compact".equals(unit)) unit = "m";
        String suffix = "m".equals(unit) ? " m" : " " + unit;
        String hourGap = "h".equals(hour) ? " h " : " " + hour + " ";
        if (h > 0) return h + hourGap + String.format(Locale.US, "%02d", m) + suffix;
        return m + suffix;
    }

    /** Legacy-Markdown escape (the daemon sends parse_mode=Markdown). */
    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '*' || c == '`' || c == '[') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }
}
