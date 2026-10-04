package com.overdrive.app.parking;

import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.server.Messages;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;

/**
 * Builds the two parking notifications and publishes them on the daemon's
 * notification bus, so delivery is decided by the user's EXISTING settings:
 *
 * <ul>
 *   <li>Notification Log — always (HistorySink).</li>
 *   <li>Web push — per-device category mutes / severity floor / quiet hours
 *       (PushSink) for {@code parking.session.started} / {@code .ended}. The
 *       banner image is the four-camera composite served from
 *       {@code /parking/asset/<session>/<file>?t=<signed>} — the same
 *       signed-token pattern the surveillance hero uses.</li>
 *   <li>Telegram — TelegramSink's {@code parking.*} branch, gated by
 *       {@code telegram.parkingMessages}. The sink reads
 *       {@code data.telegramPhotoPath} and {@code data.telegramButtons}
 *       because the generic bus event has no photo/button fields.</li>
 * </ul>
 *
 * Nothing in these messages identifies third parties: place, garage level,
 * counts, and one photo of the owner's own surroundings.
 */
public final class ParkingNotifier {

    public static final String CATEGORY_STARTED = "parking.session.started";
    public static final String CATEGORY_ENDED = "parking.session.ended";
    /** Web page for a session; the PWA hash-routes to the session view. */
    public static final String SESSION_URL_PREFIX = "/parking.html#/session/";
    /** Token lifetime for the push banner image (banners are looked at within minutes). */
    static final long ASSET_TOKEN_TTL_SEC = 6L * 3600L;

    private final ParkingEnvironment env;

    public ParkingNotifier(ParkingEnvironment env) {
        this.env = env;
    }

    /** "Parked · <place> · <level>" with the arrived composite (may be null). */
    public void publishStarted(ParkingSession s, File composite, String levelLabel) {
        if (s == null) return;
        String title = msg("parking.notify.started_title", "Parked");
        String body = startedBody(s, levelLabel);
        JSONObject data = baseData(s, "started");
        try {
            if (composite != null && composite.isFile()) {
                attachImage(data, s, composite);
            }
            JSONArray buttons = new JSONArray();
            if (s.hasFix()) {
                buttons.put(button(msg("parking.notify.btn_walk", "Walk me back"),
                        "https://maps.google.com/?q=" + fmt(s.lat) + "," + fmt(s.lng)));
            }
            data.put("telegramButtons", buttons);
        } catch (Exception ignored) {}
        publish(CATEGORY_STARTED, title, body, s, data);
    }

    /**
     * "Back at car · away 3 h 07 m · N events · M vehicles came or went".
     * {@code events < 0} = the recordings index was unavailable: the count is
     * omitted rather than reported as zero. A start stamped by an unset clock
     * has no meaningful duration, so "Away …" is dropped too.
     */
    public void publishEnded(ParkingSession s, int events, int neighboursMoved, boolean anyCritical) {
        if (s == null) return;
        String title = msg("parking.notify.ended_title", "Back at car");
        boolean watched = ParkingSession.SENTRY_ARMED.equals(s.sentryState)
                || ParkingSession.SENTRY_LOCK_WAIT.equals(s.sentryState)
                || ParkingSession.SENTRY_UNKNOWN.equals(s.sentryState);
        String body;
        if (!watched) {
            // Sentry never armed (surveillance off, safe zone, schedule, camera
            // down): "0 events · Nothing critical" would read as "watched and saw
            // nothing". Say what actually happened instead.
            body = s.clockTrusted()
                    ? msg("parking.notify.ended_body_unwatched", "Away {0} · Sentry not armed ({1})",
                            formatDuration(s.durationMs(env.nowMs())), sentryLabel(s.sentryState))
                    : msg("parking.notify.sentry_off", "Sentry not armed ({0})", sentryLabel(s.sentryState));
        } else if (events >= 0 && s.clockTrusted()) {
            String away = formatDuration(s.durationMs(env.nowMs()));
            body = msg("parking.notify.ended_body",
                    "Away {0} · {1} events · {2} vehicles came or went", away, events, neighboursMoved);
        } else {
            body = msg("parking.notify.ended_body_short",
                    "{0} vehicles came or went", neighboursMoved);
            if (events >= 0) {
                body = msg("parking.notify.ended_events", "{0} events", events) + " · " + body;
            }
        }
        if (watched && !anyCritical && events >= 0) {
            body = body + " · " + msg("parking.notify.nothing_critical", "Nothing critical");
        }
        String energy = energySegment(s);
        if (energy != null) body = body + " · " + energy;
        JSONObject data = baseData(s, "ended");
        try {
            if (events >= 0) data.put("events", events);
            data.put("neighboursMoved", neighboursMoved);
            if (s.hasSocBookends()) {
                data.put("socDelta", Math.round(s.socDeltaPercent() * 100) / 100.0);
            }
            double kwh = s.energyDeltaKwh();
            if (!Double.isNaN(kwh)) {
                data.put("energyKwh", Math.round(kwh * 100) / 100.0);
                data.put("energySource", s.energySource());
            }
            data.put("telegramButtons", new JSONArray());
        } catch (Exception ignored) {}
        publish(CATEGORY_ENDED, title, body, s, data);
    }

    /**
     * "1.24 kWh used (−1.5% SoC)" / "+8.60 kWh charged (+10.5% SoC)" — the BMS
     * kWh leads when the pack reports it (trips' direct measurement); a
     * SoC-only trim gets "≈0.8 kWh used (−1% SoC)" from the capacity estimate.
     * Null when no bookends exist or the change sits under the source's noise
     * floor, so a short stop adds no noise.
     */
    String energySegment(ParkingSession s) {
        if (s == null || !s.hasEnergyBookends() || !s.hasMeasurableEnergyChange()) return null;
        double kwh = s.energyDeltaKwh();
        double soc = s.socDeltaPercent();
        boolean charged = s.chargedWhileParked;
        // The SoC suffix only when the gauge itself moved: the BMS kWh can show a
        // real draw while the whole-percent SoC sits flat, and "(−0% SoC)" is noise.
        String socPart = Double.isNaN(soc) || Math.abs(soc) < ParkingSession.MIN_SOC_DELTA_PERCENT ? ""
                : " (" + (soc > 0 ? "+" : "−") + fmtSoc(Math.abs(soc)) + "% SoC)";
        if (Double.isNaN(kwh)) {
            // SoC moved but no kWh from either channel (pack capacity unknown).
            return charged
                    ? msg("parking.notify.energy_charged_soc", "+{0}% SoC (charged)", fmtSoc(Math.abs(soc)))
                    : msg("parking.notify.energy_used_soc", "−{0}% SoC", fmtSoc(Math.abs(soc)));
        }
        // "≈" leads for the SoC-derived estimate and the sign follows it:
        // "≈+0.66 kWh charged", "+9.85 kWh charged", "1.24 kWh used".
        String kwhTxt = (ParkingSession.ENERGY_SRC_SOC.equals(s.energySource()) ? "≈" : "")
                + (charged ? "+" : "")
                + String.format(Locale.US, "%.2f", Math.abs(kwh));
        return charged
                ? msg("parking.notify.energy_charged", "{0} kWh charged{1}", kwhTxt, socPart)
                : msg("parking.notify.energy_used", "{0} kWh used{1}", kwhTxt, socPart);
    }

    /** SoC with one decimal, two when the source carries them ("1.5", "0.75"). */
    private static String fmtSoc(double v) {
        String two = String.format(Locale.US, "%.2f", v);
        return two.endsWith("0") ? two.substring(0, two.length() - 1) : two;
    }

    // ==================== BUILDING BLOCKS ====================

    String startedBody(ParkingSession s, String levelLabel) {
        StringBuilder b = new StringBuilder();
        String place = s.placeShort != null && !s.placeShort.isEmpty() ? s.placeShort
                : (s.placeDisplay != null && !s.placeDisplay.isEmpty() ? s.placeDisplay : null);
        if (s.safeZone != null && !s.safeZone.isEmpty()) {
            b.append(s.safeZone);
        } else if (place != null) {
            b.append(place);
        } else if (s.hasFix()) {
            // Geocoding is async (or disabled for both flows): the fix itself is
            // still the honest answer, and it matches the "Walk me back" button.
            b.append(String.format(Locale.US, "%.5f, %.5f", s.lat, s.lng));
        } else {
            b.append(msg("parking.notify.no_place", "Location unavailable"));
        }
        if (levelLabel != null && !levelLabel.isEmpty()) {
            b.append(" · ").append(levelLabel);
        }
        b.append(" · ").append(gpsLabel(s.gpsQuality));
        if (!ParkingSession.SENTRY_ARMED.equals(s.sentryState)
                && !ParkingSession.SENTRY_LOCK_WAIT.equals(s.sentryState)) {
            b.append(" · ").append(msg("parking.notify.sentry_off",
                    "Sentry not armed ({0})", sentryLabel(s.sentryState)));
        }
        return b.toString();
    }

    String gpsLabel(String quality) {
        if (ParkingSession.GPS_FRESH.equals(quality)) return msg("parking.notify.gps_fresh", "GPS fresh");
        if (ParkingSession.GPS_RECENT.equals(quality)) return msg("parking.notify.gps_recent", "GPS recent");
        if (ParkingSession.GPS_STALE.equals(quality)) return msg("parking.notify.gps_stale", "GPS stale — garage?");
        return msg("parking.notify.gps_unknown", "No GPS fix");
    }

    String sentryLabel(String state) {
        if (state == null) state = ParkingSession.SENTRY_UNKNOWN;
        switch (state) {
            case ParkingSession.SENTRY_SUPPRESSED_SAFE_ZONE:
                return msg("parking.notify.sentry_safe_zone", "safe zone");
            case ParkingSession.SENTRY_SUPPRESSED_SCHEDULE:
                return msg("parking.notify.sentry_schedule", "outside schedule");
            case ParkingSession.SENTRY_SURVEILLANCE_OFF:
                return msg("parking.notify.sentry_disabled", "surveillance off");
            case ParkingSession.SENTRY_VEHICLE_ON_ONLY:
                return msg("parking.notify.sentry_vehicle_on_only", "vehicle-on-only mode");
            case ParkingSession.SENTRY_PIPELINE_DOWN:
                return msg("parking.notify.sentry_pipeline_down", "camera not running");
            default:
                return msg("parking.notify.sentry_unknown", "unknown");
        }
    }

    private JSONObject baseData(ParkingSession s, String stage) {
        JSONObject data = new JSONObject();
        try {
            data.put("sessionId", s.sessionId);
            data.put("stage", stage);
            data.put("startedMs", s.startedMs);
            if (s.placeShort != null) data.put("place", s.placeShort);
            data.put("gpsQuality", s.gpsQuality);
            data.put("sentryState", s.sentryState);
            if (s.hasFix()) { data.put("lat", s.lat); data.put("lng", s.lng); }
        } catch (Exception ignored) {}
        return data;
    }

    private void attachImage(JSONObject data, ParkingSession s, File composite) throws Exception {
        // Push banner: unauthenticated fetch → signed token whose subject is
        // "<sessionId>/<file>" (validated by AuthMiddleware for /parking/asset/).
        String subject = s.sessionId + "/" + composite.getName();
        String url = "/parking/asset/" + s.sessionId + "/" + composite.getName();
        String tok = env.signAssetToken(subject, ASSET_TOKEN_TTL_SEC);
        if (tok != null) url += "?t=" + tok;
        data.put("snapshot", url);
        // Telegram: the bot daemon reads the file directly (separate UID, hence
        // world-readable writes in ParkingSnapshotter).
        data.put("telegramPhotoPath", composite.getAbsolutePath());
    }

    private void publish(String category, String title, String body, ParkingSession s, JSONObject data) {
        try {
            NotificationEvent ev = new NotificationEvent(
                    category,
                    NotificationEvent.Severity.INFO,
                    title,
                    body,
                    "parking:" + s.sessionId,
                    SESSION_URL_PREFIX + s.sessionId,
                    data);
            env.publish(ev);
        } catch (Throwable t) {
            env.log("Parking notify failed: " + t.getMessage());
        }
    }

    private static JSONObject button(String text, String url) throws Exception {
        JSONObject b = new JSONObject();
        b.put("text", text);
        b.put("url", url);
        return b;
    }

    /** Localized lookup with an English fallback when the key is not in the catalog. */
    static String msg(String key, String fallback, Object... args) {
        String raw;
        try {
            raw = Messages.get(key, args);
        } catch (Throwable t) {
            raw = null;
        }
        if (raw == null || raw.equals(key)) {
            try {
                return args == null || args.length == 0
                        ? fallback
                        : java.text.MessageFormat.format(fallback, args);
            } catch (Throwable t) {
                return fallback;
            }
        }
        return raw;
    }

    static String formatDuration(long ms) {
        long mins = Math.max(0L, ms / 60_000L);
        long h = mins / 60, m = mins % 60;
        String unit = Messages.get("units.minute_compact");
        if (unit == null || unit.isEmpty() || "units.minute_compact".equals(unit)) unit = "m";
        String suffix = "m".equals(unit) ? " m" : " " + unit;
        String hour = Messages.get("units.hour_compact");
        if (hour == null || hour.isEmpty() || "units.hour_compact".equals(hour)) hour = "h";
        String hourGap = "h".equals(hour) ? " h " : " " + hour + " ";
        if (h > 0) return h + hourGap + String.format(Locale.US, "%02d", m) + suffix;
        return m + suffix;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
