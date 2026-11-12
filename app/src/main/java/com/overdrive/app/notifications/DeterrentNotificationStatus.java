package com.overdrive.app.notifications;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot evidence that a deterrent actually executed during the current
 * surveillance notification window. Configuration/enqueue attempts never set
 * these flags; only confirmed execution paths do.
 */
public final class DeterrentNotificationStatus {
    private static final AtomicBoolean SCREEN_FIRED = new AtomicBoolean(false);
    private static final AtomicBoolean CLOUD_FIRED = new AtomicBoolean(false);

    private DeterrentNotificationStatus() {}

    public static void markScreenFired() { SCREEN_FIRED.set(true); }
    public static void markCloudFired() { CLOUD_FIRED.set(true); }

    /** Consume once while building the final event so all sinks share the same data. */
    public static org.json.JSONObject consume() {
        org.json.JSONObject result = new org.json.JSONObject();
        try {
            if (SCREEN_FIRED.getAndSet(false)) result.put("screenDeterrentFired", true);
            if (CLOUD_FIRED.getAndSet(false)) result.put("cloudDeterrentFired", true);
        } catch (Exception ignored) {}
        return result;
    }
}
