package com.overdrive.app.notifications.sinks;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.telegram.TelegramNotifier;

/**
 * Bridges {@link NotificationBus} vehicle events to Telegram.
 *
 * <p>Before this sink, charging / door / tyre / battery-health events reached
 * Web Push only (PushSink) — a user paired to Telegram but without a browser
 * subscription got nothing for, say, a charging fault. This sink closes that
 * gap by forwarding the relevant events to {@link TelegramNotifier}.
 *
 * <h3>What is and isn't forwarded (regression guards)</h3>
 * <ul>
 *   <li><b>surveillance.*</b> is EXCLUDED — surveillance motion (the two-stage
 *       {@code sendRichMotionNotifications}/{@code sendFinalTelegramNotification}
 *       flow) and proximity ({@code ProximityRecordingHandler}) already deliver
 *       to Telegram directly. Forwarding them here too would DOUBLE-SEND.</li>
 *   <li>Only <b>WARN</b> and <b>CRITICAL</b> severities are forwarded. Routine
 *       INFO events (charging started/stopped, door closed) would be Telegram
 *       spam — they stay Web-Push-only. WARN/CRITICAL (charging full/fault,
 *       tyre alarm/leak, SOH mismatch) are the ones worth a chat message.</li>
 *   <li><b>vehicle.security.door.*</b> is EXCLUDED — door open/close stays
 *       Web-Push-only. It would otherwise ride the criticalAlerts toggle (a
 *       blunt master switch shared with proximity/battery/tyre alerts), which
 *       can't silence doors alone; a parked door edge is a push-tier event.</li>
 *   <li><b>vehicle.health.tyre.*</b> is gated on its OWN {@code tyreAlerts}
 *       toggle (default ON) so tyre pressure/leak messages can be turned off
 *       without silencing every other critical vehicle alert.</li>
 * </ul>
 *
 * <p>Delivery rides the {@code CRITICAL} Telegram category (the {@code
 * criticalAlerts} toggle, default ON), so a user can silence vehicle alerts by
 * turning critical alerts off — the same control surface the rest of the
 * critical path uses. {@code TelegramNotifier} applies its own fresh category
 * gate and spools on daemon-down, so a vehicle alert fired while the bot is
 * down (e.g. parked charging fault) is delivered when it next starts.
 */
public final class TelegramSink implements NotificationBus.Sink {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TelegramSink");

    @Override
    public void onNotification(NotificationEvent event) {
        if (event == null) return;
        try {
            // Proximity access owns explicit channel consent. It must not ride
            // an unrelated alert toggle or fall through into a second send.
            if (com.overdrive.app.remoteaccess.RemoteAccessNotifications.isRemoteAccess(event)) {
                if (com.overdrive.app.remoteaccess.RemoteAccessNotifications
                        .canDeliver(event, "telegram")) {
                    String action = event.data != null ? event.data.optString("action", "") : "";
                    String lead = "lock".equals(action) ? "🔒🔴 " : "🔑🟢 ";
                    TelegramNotifier.sendRemoteAccess(lead + "*" + md(event.title) + "*\n"
                            + md(event.body), event.data);
                }
                return;
            }
            // Already delivered to Telegram via the direct path — don't dup.
            if (event.category != null && event.category.startsWith("surveillance.")) {
                return;
            }
            // Door open/close stays Web-Push-only. This sink rides the
            // criticalAlerts toggle, a blunt master switch (proximity,
            // low-battery, tyre, charging faults all ride it too), so it can't
            // silence ONLY doors — and a parked door edge is a push-tier event,
            // not worth a Telegram buzz. Web Push still delivers it, gated by
            // the door category's own per-device enable/mute. Mirrors the
            // surveillance.* exclusion above.
            if (event.category != null && event.category.startsWith("vehicle.security.door.")) {
                return;
            }
            // Tyre pressure + leak have their own dedicated toggle (default ON),
            // so they can be silenced without also losing charging faults /
            // proximity / battery-health, which all share criticalAlerts.
            if (event.category != null && event.category.startsWith("vehicle.health.tyre.")
                    && !com.overdrive.app.telegram.config.UnifiedTelegramConfig.isTyreAlerts()) {
                return;
            }
            // New sign-ins have their own toggle; wrong access codes stay on Critical alerts.
            if ("vehicle.security.login.success".equals(event.category)
                    && !com.overdrive.app.telegram.config.UnifiedTelegramConfig.isLoginAlerts()) {
                return;
            }
            // Parking Intelligence ("Parked", "Back at car") publishes at INFO
            // on purpose — it is a summary, not an alert — yet it is the one
            // INFO stream a Telegram-only user explicitly asked for by turning
            // the feature on. Own toggle (parkingMessages, default ON), own
            // photo+URL-button path, never the criticalAlerts lane. Handled
            // BEFORE the INFO drop below.
            if (event.category != null && event.category.startsWith("parking.")) {
                forwardParking(event);
                return;
            }
            // INFO is Web-Push-only; only escalate WARN/CRITICAL to Telegram — EXCEPT a
            // user-authored automation "Send notification" action, which publishes at INFO
            // (NotificationAction hardcodes Severity.INFO) yet is an intentional, explicitly
            // configured message, not routine telemetry. Without this exception the INFO
            // filter silently swallowed every automation notification, so it never reached
            // Telegram (the reported "send notification via telegram from automation doesn't
            // work"). Routine INFO telemetry (charging started, door closed) still stays
            // Web-Push-only. Rides the same CRITICAL lane / criticalAlerts gate as the rest.
            boolean userAuthored = "automation.action".equals(event.category);
            if (event.severity == NotificationEvent.Severity.INFO && !userAuthored) {
                return;
            }

            // 🚨 critical, ⚠️ warn, 🔔 the user-authored automation INFO message (a plain
            // notification, not an alert — a warning icon would misrepresent it).
            String icon = event.severity == NotificationEvent.Severity.CRITICAL ? "🚨"
                    : (event.severity == NotificationEvent.Severity.INFO ? "🔔" : "⚠️");
            StringBuilder msg = new StringBuilder();
            msg.append(icon).append(" *").append(md(event.title)).append("*");
            if (event.body != null && !event.body.isEmpty()) {
                msg.append("\n").append(md(event.body));
            }
            // CRITICAL category → criticalAlerts gate (default ON). sendMessage
            // is async + spools on daemon-down for the CRITICAL category.
            TelegramNotifier.sendMessage(msg.toString(), "CRITICAL");
        } catch (Throwable t) {
            logger.warn("TelegramSink forward failed: " + t.getMessage());
        }
    }

    /**
     * parking.session.started / .ended → {@link TelegramNotifier#notifyParking}.
     * The generic bus event has no photo or button fields, so the parking
     * notifier tucks them into {@code data.telegramPhotoPath} and
     * {@code data.telegramButtons}; the toggle gate lives in TelegramNotifier
     * (fresh config read) exactly like the other categories.
     */
    private static void forwardParking(NotificationEvent event) {
        if (!com.overdrive.app.telegram.config.UnifiedTelegramConfig.isParkingMessages()) return;
        String photo = null;
        String buttons = null;
        String body = event.body;
        if (event.data != null) {
            String telegramBody = event.data.optString("telegramBody", "");
            if (!telegramBody.isEmpty()) body = telegramBody;
            photo = event.data.optString("telegramPhotoPath", null);
            org.json.JSONArray b = event.data.optJSONArray("telegramButtons");
            if (b != null && b.length() > 0) buttons = b.toString();
        }
        String icon = "parking.session.ended".equals(event.category) ? "🚗" : "🅿️";
        TelegramNotifier.notifyParking(icon + " " + (event.title == null ? "" : event.title),
                body, photo, buttons);
    }

    /**
     * Escape legacy-Markdown metacharacters. The daemon's sendMessage hardcodes
     * parse_mode=Markdown, and these titles/bodies carry user-set content
     * (e.g. a safe-zone name like "Home_Garage") — an unescaped {@code _} would
     * 400 ("can't parse entities") and silently drop the whole message. Matches
     * the daemon-side mdEscape character set.
     */
    private static String md(String s) {
        if (s == null || s.isEmpty()) return s == null ? "" : s;
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '*' || c == '`' || c == '[') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }
}
