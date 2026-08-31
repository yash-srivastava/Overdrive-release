package com.overdrive.app.logging;

import com.overdrive.app.util.ScratchPaths;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth mapping a stable daemon KEY (used in API/IPC/Telegram
 * requests) to its on-disk log file path. Mirrors the native
 * DaemonAdapter.getLogFilePath table, but lives in the logging package so it's
 * reachable from the daemon process (UID 2000, no Android UI classes) too.
 *
 * Keys are short, lowercase, stable identifiers — safe to type into Telegram
 * (`/sendlog camera`) and to pass as a query param (`?daemon=camera`).
 */
public final class DaemonLogPaths {

    private DaemonLogPaths() {}

    // Relative names under daemon scratch — resolved via ScratchPaths at lookup time.
    private static final Map<String, String> RELATIVE = new LinkedHashMap<>();
    static {
        RELATIVE.put("camera",     "cam_daemon.log");
        RELATIVE.put("accsentry",  "acc_sentry_daemon.log");
        RELATIVE.put("sentry",     "sentry_daemon.log");
        RELATIVE.put("telegram",   "telegrambotdaemon.log");
        RELATIVE.put("cloudflared","cloudflared.log");
        RELATIVE.put("zrok",       "zrok.log");
        RELATIVE.put("tailscale",  ".tailscale/tailscale.log");
        RELATIVE.put("singbox",    "singbox.log");
    }

    /** @return the log path for a daemon key, or null if unknown. */
    public static String pathFor(String key) {
        if (key == null) return null;
        String rel = RELATIVE.get(key.trim().toLowerCase());
        return rel != null ? ScratchPaths.path(rel) : null;
    }

    /** Stable, ordered set of known daemon keys. */
    public static java.util.Set<String> keys() {
        return RELATIVE.keySet();
    }

    /** Comma-joined key list for help text / error messages. */
    public static String keyList() {
        return String.join(", ", RELATIVE.keySet());
    }
}
