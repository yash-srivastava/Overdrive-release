package com.overdrive.app.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Runtime Service Mode flags, without mutating each feature's saved configuration. */
public final class ServiceModeFeatureGate {
    private static volatile Set<String> paused = Collections.emptySet();
    private static volatile Set<String> appProcessPaused = Collections.emptySet();

    private ServiceModeFeatureGate() {}

    /** Daemon side: [features] of an active, unexpired visit, or null when none is. */
    public static void apply(JSONArray features) {
        Set<String> next = new HashSet<>();
        if (features != null) for (int i = 0; i < features.length(); i++) {
            String feature = features.optString(i, "");
            if (!feature.isEmpty()) next.add(feature);
        }
        paused = Collections.unmodifiableSet(next);
    }

    public static boolean isPaused(String feature) {
        return paused.contains(feature);
    }

    /**
     * The widgets and accessibility hooks run in the app process, not the daemon.
     * Read the shared durable section there; no daemon-only static flag crosses UIDs.
     * When the config is temporarily unreadable, retain this process's last gate.
     */
    public static boolean isPausedInConfig(String feature) {
        try {
            JSONObject state = UnifiedConfigManager.loadConfig().optJSONObject("serviceMode");
            Set<String> next = new HashSet<>();
            // A visit that expired while the daemon was down is over here too.
            if (state != null && state.optBoolean("enabled", false)
                    && !state.optBoolean("ending", false)
                    && state.optLong("endsAtEpochMs", 0L) > System.currentTimeMillis()) {
                JSONArray features = state.optJSONArray("disabledFeatures");
                if (features != null) for (int i = 0; i < features.length(); i++) {
                    String selected = features.optString(i, "");
                    if (!selected.isEmpty()) next.add(selected);
                }
            }
            appProcessPaused = Collections.unmodifiableSet(next);
            return next.contains(feature);
        } catch (Throwable ignored) {
            return appProcessPaused.contains(feature);
        }
    }
}
