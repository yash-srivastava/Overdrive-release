package com.overdrive.app.monitor;

import java.util.Locale;

/**
 * Parser for the output of {@code cmd wifi status}, the framework's non-privileged Wi-Fi
 * shell command on Android 11.
 *
 * <p>Expected shape (connected):
 * <pre>
 * Wifi is enabled
 * Wifi scanning is always available
 * Wifi is connected to "MyNet"
 * WifiInfo: SSID: "MyNet", BSSID: aa:bb:cc:dd:ee:ff, ..., Metered hint: false, score: 60
 * NetworkCapabilities: ...
 * </pre>
 * or {@code Wifi is not connected} / {@code Wifi is disabled} when there is no link.
 *
 * <p>Unlike the Wi-Fi service's dump command, this command does not run the service's dump
 * path, so it forks no log captures inside system_server. Whether the SSID is redacted for the
 * shell uid is firmware-specific: {@code <unknown ssid>}, {@code <none>}, empty values and
 * unparseable text all yield an unknown SSID ({@code null}) rather than a placeholder — the
 * caller owns the placeholder.
 */
public final class WifiStatusParser {

    private static final String CONNECTED_PREFIX = "Wifi is connected to ";
    private static final String NOT_CONNECTED_PREFIX = "Wifi is not connected";
    private static final String DISABLED_PREFIX = "Wifi is disabled";
    private static final String WIFI_INFO_PREFIX = "WifiInfo:";

    /** Result of one parse. Unknown pieces are {@code null}. */
    public static final class Parsed {
        /** Network name without surrounding quotes, or null when unknown, hidden or absent. */
        public final String ssid;
        /** {@code Metered hint} from the WifiInfo line, or null when that line is absent. */
        public final Boolean meteredHint;
        /** True only when the output states a connection. */
        public final boolean connected;

        Parsed(String ssid, Boolean meteredHint, boolean connected) {
            this.ssid = ssid;
            this.meteredHint = meteredHint;
            this.connected = connected;
        }
    }

    static final Parsed UNKNOWN = new Parsed(null, null, false);

    private WifiStatusParser() {}

    /** Never throws; anything unrecognisable parses as {@link #UNKNOWN}. */
    public static Parsed parse(String output) {
        if (output == null || output.trim().isEmpty()) {
            return UNKNOWN;
        }
        String ssid = null;
        Boolean metered = null;
        boolean connected = false;
        for (String raw : output.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith(NOT_CONNECTED_PREFIX) || line.startsWith(DISABLED_PREFIX)) {
                return UNKNOWN;
            }
            if (line.startsWith(CONNECTED_PREFIX)) {
                connected = true;
                ssid = normalizeSsid(line.substring(CONNECTED_PREFIX.length()));
                continue;
            }
            if (line.startsWith(WIFI_INFO_PREFIX)) {
                // The framework prints this line only in the connected branch.
                connected = true;
                if (ssid == null) {
                    ssid = normalizeSsid(field(line, "SSID: ", ", BSSID:"));
                }
                String hint = field(line, "Metered hint: ", ",");
                if ("true".equalsIgnoreCase(hint)) {
                    metered = Boolean.TRUE;
                } else if ("false".equalsIgnoreCase(hint)) {
                    metered = Boolean.FALSE;
                }
            }
        }
        if (!connected) {
            return UNKNOWN;
        }
        return new Parsed(ssid, metered, true);
    }

    /**
     * Strip a pair of surrounding quotes and reject exact framework redaction markers.
     * An SSID is user-controlled: a real name such as "Unknown-Guest" must not be
     * rejected just because it contains the word "unknown".
     */
    static String normalizeSsid(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (s.isEmpty() || "<none>".equals(lower)
                || "<unknown ssid>".equals(lower)
                || "<unknown>".equals(lower)
                || "unknown ssid".equals(lower)
                || "unknown".equals(lower)) {
            return null;
        }
        return s;
    }

    /** Substring after {@code key} up to {@code terminator} (or end of line); null when absent. */
    private static String field(String line, String key, String terminator) {
        int idx = line.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        int end = line.indexOf(terminator, start);
        if (end < 0) {
            end = line.length();
        }
        return line.substring(start, end).trim();
    }
}
