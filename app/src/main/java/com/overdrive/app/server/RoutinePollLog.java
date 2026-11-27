package com.overdrive.app.server;

/**
 * Request-log policy for the Custom Layouts cluster's own polls (heartbeat 1 Hz, config): the first one of
 * a run logs as usual, the rest are counted into one line a minute, and any non-200 answer logs.
 */
final class RoutinePollLog {

    static final int NONE = -1;
    static final int HEARTBEAT = 0;
    static final int CONFIG = 1;
    private static final String[] NAMES = {"heartbeat", "config"};
    /** A poll after this long without one starts a new run, which logs its first line. */
    private static final long WINDOW_MS = 60_000L;

    private static final long[] lastAt = new long[NAMES.length];
    private static final int[] counted = new int[NAMES.length];
    private static long windowStart;
    private static long lastAny;

    private RoutinePollLog() {}

    /** Which routine poll [requestLine] is, or {@link #NONE}. */
    static int kind(String requestLine) {
        if (isPath(requestLine, "POST /api/custom-layouts/heartbeat")) return HEARTBEAT;
        if (isPath(requestLine, "GET /api/custom-layouts/config")) return CONFIG;
        return NONE;
    }

    private static boolean isPath(String line, String prefix) {
        if (!line.startsWith(prefix)) return false;
        if (line.length() == prefix.length()) return true;
        char c = line.charAt(prefix.length());
        return c == ' ' || c == '?';
    }

    /** True when this request's line should be logged as any other; otherwise it was counted. */
    static boolean onRequest(int kind, long nowMs) {
        String summary = null;
        boolean first;
        synchronized (RoutinePollLog.class) {
            first = lastAt[kind] == 0L || nowMs - lastAt[kind] > WINDOW_MS;
            // a new run first closes the last one's count, at the time its last poll came
            if (first && windowStart != 0L) summary = takeSummary(Math.max(windowStart, lastAny));
            lastAt[kind] = nowMs;
            lastAny = nowMs;
            if (!first) counted[kind]++;
            if (windowStart == 0L || summary != null) windowStart = nowMs;
            if (summary == null && nowMs - windowStart >= WINDOW_MS) {
                summary = takeSummary(nowMs);
                windowStart = nowMs;
            }
        }
        if (summary != null) com.overdrive.app.daemon.CameraDaemon.log(summary);
        return first;
    }

    /** After the response: a non-200 answer always logs. */
    static void onResponse(int kind, String requestLine, int status) {
        if (kind == NONE || status == 200) return;
        com.overdrive.app.daemon.CameraDaemon.log("HTTP: " + requestLine + " -> "
                + (status < 0 ? "no response" : String.valueOf(status)));
    }

    // caller holds the class lock; counts from windowStart to endMs
    private static String takeSummary(long endMs) {
        StringBuilder sb = null;
        for (int i = 0; i < counted.length; i++) {
            if (counted[i] == 0) continue;
            if (sb == null) sb = new StringBuilder("HTTP: Custom Layouts polls over ")
                    .append((endMs - windowStart) / 1000L).append(" s:");
            sb.append(' ').append(NAMES[i]).append('=').append(counted[i]);
            counted[i] = 0;
        }
        return sb != null ? sb.toString() : null;
    }
}
