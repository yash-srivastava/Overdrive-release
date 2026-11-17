package com.overdrive.app.notifications;

import com.overdrive.app.server.Messages;

import org.json.JSONObject;

/**
 * Tells the owner about web sign-ins on /auth/token: every new session, and failed or
 * rate-limited attempts. Failures alert at most once a minute, plus one critical alert when ten
 * or more land inside that minute. Never throws, so a notification problem can't affect login.
 */
public final class LoginAttemptNotifier {

    public static final String CATEGORY_SUCCESS = "vehicle.security.login.success";
    public static final String CATEGORY_FAILED = "vehicle.security.login.failed";

    static final long WINDOW_MS = 60_000L;
    static final int BURST = 10;
    private static final int MAX_SOURCES = 32;

    private static final Object LOCK = new Object();
    private static long windowStart = -1;
    private static int windowCount;
    private static boolean burstSent;
    private static final java.util.LinkedHashSet<String> windowSources = new java.util.LinkedHashSet<>();

    private LoginAttemptNotifier() {}

    public static void loginSucceeded(String clientIdentity, String socketIp, String deviceLabel) {
        try {
            String from = address(clientIdentity, socketIp);
            String device = deviceLabel == null || deviceLabel.isEmpty()
                    ? Messages.get("notifications.login_unknown_browser") : deviceLabel;
            JSONObject data = new JSONObject().put("outcome", "success").put("address", from)
                    .put("socketIp", clean(socketIp)).put("device", device);
            NotificationBus.get().publish(new NotificationEvent(CATEGORY_SUCCESS,
                    NotificationEvent.Severity.WARN,
                    Messages.get("notifications.login_success_title"),
                    Messages.get("notifications.login_success_body", device, from),
                    "login-success-" + System.currentTimeMillis(), "/sessions.html", data));
        } catch (Throwable ignored) {
            // Never affects login.
        }
    }

    public static void loginFailed(String clientIdentity, String socketIp, boolean rateLimited) {
        try {
            String from = address(clientIdentity, socketIp);
            int alert = onFailure(System.currentTimeMillis(), from);
            if (alert == 0) return;
            JSONObject data = new JSONObject().put("outcome", rateLimited ? "blocked" : "failed")
                    .put("address", from).put("socketIp", clean(socketIp));
            NotificationEvent event;
            if (alert == 1) {
                event = new NotificationEvent(CATEGORY_FAILED, NotificationEvent.Severity.WARN,
                        Messages.get("notifications.login_failed_title"),
                        Messages.get(rateLimited ? "notifications.login_blocked_body"
                                : "notifications.login_failed_body", from),
                        "login-failed", null, data);
            } else {
                int count, sources;
                synchronized (LOCK) { count = windowCount; sources = windowSources.size(); }
                data.put("attempts", count).put("sources", sources);
                event = new NotificationEvent(CATEGORY_FAILED, NotificationEvent.Severity.CRITICAL,
                        Messages.get("notifications.login_burst_title"),
                        Messages.get("notifications.login_burst_body", count, sources),
                        "login-burst", null, data);
            }
            NotificationBus.get().publish(event);
        } catch (Throwable ignored) {
            // Never affects login.
        }
    }

    /** 1 = first failure of a new window, 2 = the window just reached {@link #BURST}, 0 = stay quiet. */
    static int onFailure(long now, String source) {
        synchronized (LOCK) {
            boolean fresh = windowStart < 0 || now < windowStart || now - windowStart >= WINDOW_MS;
            if (fresh) {
                windowStart = now;
                windowCount = 0;
                windowSources.clear();
                burstSent = false;
            }
            windowCount++;
            if (windowSources.size() < MAX_SOURCES) windowSources.add(source);
            if (fresh) return 1;
            if (!burstSent && windowCount >= BURST) {
                burstSent = true;
                return 2;
            }
            return 0;
        }
    }

    static void resetForTest() {
        synchronized (LOCK) {
            windowStart = -1;
            windowCount = 0;
            windowSources.clear();
            burstSent = false;
        }
    }

    /** The forwarded address when one was sent (shown with the socket peer, since it can be spoofed). */
    static String address(String clientIdentity, String socketIp) {
        String peer = clean(socketIp);
        String reported = clean(clientIdentity);
        if (reported.startsWith("/")) reported = reported.substring(1);
        int colon = reported.lastIndexOf(':');
        if (colon > 0 && reported.indexOf(':') == colon) reported = reported.substring(0, colon);
        if (reported.isEmpty() || reported.equals(peer)) return peer.isEmpty() ? "unknown" : peer;
        return peer.isEmpty() ? reported : reported + " (via " + peer + ")";
    }

    private static String clean(String value) {
        if (value == null) return "";
        String text = value.replaceAll("\\p{Cntrl}", " ").trim();
        return text.length() <= 64 ? text : text.substring(0, 64);
    }
}
