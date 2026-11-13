package com.overdrive.app.logging;

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

    // LinkedHashMap so the order is stable for "list available daemons" UIs.
    private static final Map<String, String> PATHS = new LinkedHashMap<>();
    static {
        PATHS.put("camera",     com.overdrive.app.util.ScratchRoot.path("cam_daemon.log"));
        PATHS.put("accsentry",  com.overdrive.app.util.ScratchRoot.path("acc_sentry_daemon.log"));
        PATHS.put("sentry",     com.overdrive.app.util.ScratchRoot.path("sentry_daemon.log"));
        PATHS.put("telegram",   com.overdrive.app.util.ScratchRoot.path("telegrambotdaemon.log"));
        PATHS.put("cloudflared",com.overdrive.app.util.ScratchRoot.path("cloudflared.log"));
        PATHS.put("zrok",       com.overdrive.app.util.ScratchRoot.path("zrok.log"));
        PATHS.put("tailscale",  com.overdrive.app.util.ScratchRoot.path(".tailscale/tailscale.log"));
        PATHS.put("wireguard",  com.overdrive.app.wireguard.WireGuardPaths.LOG);
        PATHS.put("singbox",    com.overdrive.app.util.ScratchRoot.path("singbox.log"));
    }

    /**
     * Human-readable ownership and triage guidance for each log. Keep these keys
     * aligned with PATHS: the About page uses this metadata to explain which
     * OverDrive component a log covers and when it is useful to send.
     */
    private static final Map<String, LogDescription> DESCRIPTIONS = new LinkedHashMap<>();
    static {
        DESCRIPTIONS.put("camera", new LogDescription(
                "Camera",
                "Core OverDrive service — WebUI, HTTP/API, camera feeds and streaming, plus shared vehicle/app integrations.",
                "the WebUI or API fails, camera/video feeds fail, vehicle data is missing, or no other daemon clearly matches."
        ));
        DESCRIPTIONS.put("accsentry", new LogDescription(
                "ACC Sentry",
                "Vehicle ACC and power-state monitor — ignition transitions, parked state, Sentry-mode coordination, screen/backlight and MCU wake.",
                "ACC/ignition detection, park/wake transitions, screen/backlight control or Sentry-mode activation behaves incorrectly."
        ));
        DESCRIPTIONS.put("sentry", new LogDescription(
                "Sentry",
                "System keep-awake service — ACC lock, wake lock, and app/network allowlisting.",
                "the head unit sleeps unexpectedly, OverDrive is suspended while parked, or background/network access is lost."
        ));
        DESCRIPTIONS.put("telegram", new LogDescription(
                "Telegram Bot",
                "Telegram bot service — command polling, command execution and bot replies/notifications.",
                "the bot does not respond, commands fail, or Telegram notifications are missing."
        ));
        DESCRIPTIONS.put("cloudflared", new LogDescription(
                "Cloudflared Tunnel",
                "Cloudflare remote-access tunnel.",
                "the Cloudflare tunnel will not start, its URL is unavailable, or remote access through Cloudflare fails."
        ));
        DESCRIPTIONS.put("zrok", new LogDescription(
                "Zrok Tunnel",
                "zrok remote-sharing tunnel — reserved/public share and proxy startup.",
                "a zrok share or URL fails to start, cannot be reached, or disconnects."
        ));
        DESCRIPTIONS.put("tailscale", new LogDescription(
                "Tailscale",
                "Tailscale VPN/tailnet service — device connectivity, tunnel and optional proxy/ADB endpoints.",
                "the vehicle disappears from your tailnet, Tailscale connectivity fails, or its configured endpoint/proxy is unreachable."
        ));
        DESCRIPTIONS.put("wireguard", new LogDescription(
                "WireGuard",
                "WireGuard tunnel (wgproxy) — WireGuard interface and tunnel traffic.",
                "the WireGuard tunnel fails to start, handshake/connectivity fails, or traffic does not pass through it."
        ));
        DESCRIPTIONS.put("singbox", new LogDescription(
                "Sing-box Proxy",
                "sing-box proxy service — local proxy process and configured outbound routing.",
                "the proxy will not start, proxy routing is incorrect, or apps cannot reach the network through the proxy."
        ));
    }

    /** Immutable diagnostic guidance for one daemon log. */
    public static final class LogDescription {
        public final String name;
        public final String component;
        public final String sendWhen;

        private LogDescription(String name, String component, String sendWhen) {
            this.name = name;
            this.component = component;
            this.sendWhen = sendWhen;
        }
    }

    /** @return diagnostic guidance for a daemon key, or null if unknown. */
    public static LogDescription descriptionFor(String key) {
        if (key == null) return null;
        return DESCRIPTIONS.get(key.trim().toLowerCase());
    }

    /** @return the log path for a daemon key, or null if unknown. */
    public static String pathFor(String key) {
        if (key == null) return null;
        return PATHS.get(key.trim().toLowerCase());
    }

    /** Stable, ordered set of known daemon keys. */
    public static java.util.Set<String> keys() {
        return PATHS.keySet();
    }

    /** Comma-joined key list for help text / error messages. */
    public static String keyList() {
        return String.join(", ", PATHS.keySet());
    }
}
