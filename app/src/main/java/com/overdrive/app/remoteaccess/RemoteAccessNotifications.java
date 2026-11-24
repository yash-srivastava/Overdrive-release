package com.overdrive.app.remoteaccess;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.server.Messages;

import org.json.JSONObject;

/**
 * Event-only delivery for completed proximity lock/unlock commands.
 * This class owns no monitor, executor, listener or timer.
 */
public final class RemoteAccessNotifications {
    public static final String CATEGORY = "vehicle.security.remote_access";
    public static final String TELEGRAM_CATEGORY = "REMOTE_ACCESS";

    private RemoteAccessNotifications() {}

    /**
     * Call once after a real command completes, using the settings revision
     * under which that command was issued. Learning, skipped commands and
     * already-satisfied targets must never call this method.
     */
    public static boolean publish(long expectedRevision, String action, String outcome,
                                  String reason, String deviceName) {
        return publish(expectedRevision, action, outcome, reason, deviceName, "bluetooth_proximity");
    }

    public static boolean publish(long expectedRevision, String action, String outcome,
                                  String reason, String deviceName, String source) {
        try {
            UnifiedConfigManager.forceReload();
            NotificationEvent event = eventFor(RemoteAccessSettings.read(), expectedRevision,
                    action, outcome, reason, deviceName, source);
            if (event == null) return false;
            NotificationBus.get().publish(event);
            return true;
        } catch (Exception unavailable) {
            // A notification error must not affect the vehicle command.
            return false;
        }
    }

    public static boolean isRemoteAccess(NotificationEvent event) {
        return event != null && CATEGORY.equals(event.category);
    }

    /** Recheck at asynchronous delivery boundaries, including retry/replay. */
    public static boolean canDeliver(NotificationEvent event, String channel) {
        return isRemoteAccess(event) && canDeliver(event.data, channel);
    }

    /** Telegram IPC keeps these data so spool replay cannot outlive consent. */
    public static boolean canDeliver(JSONObject data, String channel) {
        try {
            UnifiedConfigManager.forceReload();
            return allows(RemoteAccessSettings.read(), data, channel);
        } catch (Exception unavailable) {
            return false;
        }
    }

    static NotificationEvent eventFor(JSONObject settings, long expectedRevision,
                                      String action, String outcome, String reason,
                                      String deviceName) throws Exception {
        return eventFor(settings, expectedRevision, action, outcome, reason, deviceName, "bluetooth_proximity");
    }

    static NotificationEvent eventFor(JSONObject settings, long expectedRevision,
                                      String action, String outcome, String reason,
                                      String deviceName, String source) throws Exception {
        if (!validAction(action) || !validOutcome(outcome)) return null;
        JSONObject data = new JSONObject()
                .put("revision", expectedRevision)
                .put("action", action)
                .put("outcome", outcome)
                .put("source", source)
                .put("deviceName", clean(deviceName, 120))
                .put("reason", clean(reason, 300));
        boolean push = allows(settings, data, "push");
        boolean telegram = allows(settings, data, "telegram");
        if (!push && !telegram) return null;

        boolean lock = "lock".equals(action);
        String verb = lock ? "lock" : "unlock";
        String title;
        String body;
        if ("confirmed".equals(outcome)) {
            title = text(lock ? "notifications.remote_access_lock_confirmed_title"
                    : "notifications.remote_access_unlock_confirmed_title",
                    "Proximity " + verb + " confirmed");
            body = text(lock ? "notifications.remote_access_lock_confirmed_body"
                    : "notifications.remote_access_unlock_confirmed_body",
                    "Driver-door lock state is " + (lock ? "locked" : "unlocked") + ".");
        } else if ("unconfirmed".equals(outcome)) {
            title = text(lock ? "notifications.remote_access_lock_unconfirmed_title"
                    : "notifications.remote_access_unlock_unconfirmed_title",
                    "Proximity " + verb + " unconfirmed");
            body = text(lock ? "notifications.remote_access_lock_unconfirmed_body"
                    : "notifications.remote_access_unlock_unconfirmed_body",
                    "The " + verb + " command was accepted; the vehicle state was not confirmed.");
        } else {
            title = text(lock ? "notifications.remote_access_lock_failed_title"
                    : "notifications.remote_access_unlock_failed_title",
                    "Proximity " + verb + " failed");
            body = text(lock ? "notifications.remote_access_lock_failed_body"
                    : "notifications.remote_access_unlock_failed_body",
                    "The vehicle " + verb + " command failed.");
        }
        String name = data.optString("deviceName", "");
        String detail = data.optString("reason", "");
        if (!name.isEmpty()) {
            boolean fob = isKeyFobSource(source);
            body += " " + text(fob ? "notifications.remote_access_device_key_suffix"
                    : "notifications.remote_access_device_phone_suffix",
                    (fob ? "Key: " : "Phone: ") + name + ".", name);
        }
        if (!detail.isEmpty()) body += " " + RemoteAccessReasonText.localize(detail);
        NotificationEvent event = new NotificationEvent(CATEGORY,
                NotificationEvent.Severity.CRITICAL, title, body,
                "remote-access-" + action, "/remote-access.html", data);
        if (!push) event.suppressPush();
        return event;
    }

    static boolean allows(JSONObject settings, JSONObject data, String channel) {
        if (settings == null || data == null || !settings.optBoolean("enabled", false)
                || settings.optBoolean("dryRun", true)) return false;
        if (!data.has("revision") || data.optLong("revision", -1L) < 0L
                || settings.optLong("revision", -2L) != data.optLong("revision", -1L)) return false;
        String action = data.optString("action", "");
        if (!validAction(action) || !validOutcome(data.optString("outcome", ""))) return false;
        String source = data.optString("source", "");
        if (isKeyFobSource(source)) {
            if ("key_fob_presence".equals(source) && !"lock".equals(action)) return false;
            if (!settings.optBoolean("keyFobEnabled", false)
                    || ("lock".equals(action) && !settings.optBoolean("keyFobLockEnabled", false)))
                return false;
        } else if (!"bluetooth_proximity".equals(source)) return false;
        if (!settings.optBoolean("lock".equals(action) ? "lockEnabled" : "unlockEnabled", false))
            return false;
        if ("push".equals(channel)) return settings.optBoolean("notifyPush", false);
        if ("telegram".equals(channel)) return settings.optBoolean("notifyTelegram", false);
        return false;
    }

    // Messages returns the key itself when no catalog is readable; keep the English text then.
    private static String text(String key, String english, Object... args) {
        String value = Messages.get(key, args);
        return value == null || value.isEmpty() || value.equals(key) ? english : value;
    }

    private static boolean isKeyFobSource(String source) {
        return "key_fob_proximity".equals(source) || "key_fob_presence".equals(source);
    }

    private static boolean validAction(String action) {
        return "lock".equals(action) || "unlock".equals(action);
    }

    private static boolean validOutcome(String outcome) {
        return "confirmed".equals(outcome) || "unconfirmed".equals(outcome)
                || "failed".equals(outcome);
    }

    private static String clean(String value, int limit) {
        if (value == null) return "";
        String text = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
