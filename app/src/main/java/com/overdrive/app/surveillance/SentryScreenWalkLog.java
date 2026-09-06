package com.overdrive.app.surveillance;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Walk-around diagnostic: writes when sentry <b>should</b> wake or darken
 * the HU screen. Bypasses {@code DaemonLogger} so release R8 cannot strip it.
 *
 * <p>File: {@code /data/local/tmp/sentry_screen.log}
 */
final class SentryScreenWalkLog {

    static final String PATH = "/data/local/tmp/sentry_screen.log";
    private static final File FILE = new File(PATH);
    private static final long OFF_DEBOUNCE_MS = 2000L;
    private static final long HEARTBEAT_MS = 1000L;
    private static final long IDLE_HEARTBEAT_MS = 10000L;

    private static final Object lock = new Object();
    private static boolean headerWritten = false;
    private static Boolean lastShould;
    private static Boolean lastActual;
    private static String lastActualLine = "";
    private static long clearSinceMs;
    private static long lastWriteMs;
    private static boolean approachWakeConsumed;
    private static long approachClearSinceMs;

    private SentryScreenWalkLog() {}

    /** Screen wakes on approach/loiter, not for the whole recording. */
    static boolean shouldScreenOn(int maxThreat) {
        return maxThreat >= MotionPipelineV2.THREAT_MEDIUM;
    }

    /**
     * One 8s panel burst per approach. Does not retrigger while the same
     * MEDIUM+ presence continues (including while a clip is still rolling).
     * Rearms after threat stays below MEDIUM for {@link #OFF_DEBOUNCE_MS}.
     */
    static boolean consumeNewApproach(int maxThreat) {
        return consumeNewApproach(maxThreat, System.currentTimeMillis());
    }

    static boolean consumeNewApproach(int maxThreat, long nowMs) {
        synchronized (lock) {
            if (shouldScreenOn(maxThreat)) {
                approachClearSinceMs = 0L;
                if (approachWakeConsumed) return false;
                approachWakeConsumed = true;
                return true;
            }
            if (approachWakeConsumed) {
                if (approachClearSinceMs == 0L) approachClearSinceMs = nowMs;
                if (nowMs - approachClearSinceMs >= OFF_DEBOUNCE_MS) {
                    approachWakeConsumed = false;
                    approachClearSinceMs = 0L;
                }
            }
            return false;
        }
    }

    static void resetForTest() {
        synchronized (lock) {
            lastShould = null;
            lastActual = null;
            lastActualLine = "";
            clearSinceMs = 0L;
            lastWriteMs = 0L;
            approachWakeConsumed = false;
            approachClearSinceMs = 0L;
            headerWritten = false;
        }
    }

    static void tick(boolean rawShouldOn, boolean actualOn, boolean deterrentEnabled,
                     String camera, String threat, String prox,
                     boolean recording, boolean anyMotion) {
        long now = System.currentTimeMillis();
        boolean shouldOn;
        synchronized (lock) {
            if (rawShouldOn) {
                clearSinceMs = 0L;
                shouldOn = true;
            } else if (clearSinceMs == 0L) {
                clearSinceMs = now;
                shouldOn = lastShould != null && lastShould;
            } else {
                shouldOn = (now - clearSinceMs) < OFF_DEBOUNCE_MS
                        && lastShould != null && lastShould;
            }
            boolean changed = lastShould == null
                    || lastShould != shouldOn
                    || lastActual == null
                    || lastActual != actualOn;
            long gap = now - lastWriteMs;
            boolean heartbeat = shouldOn || anyMotion
                    ? gap >= HEARTBEAT_MS
                    : gap >= IDLE_HEARTBEAT_MS;
            if (!changed && !heartbeat) return;
            lastShould = shouldOn;
            lastActual = actualOn;
            lastWriteMs = now;
            writeLine(now, "should=" + (shouldOn ? "ON " : "OFF")
                    + " actual=" + (actualOn ? "ON " : "OFF")
                    + " cam=" + camera
                    + " threat=" + threat
                    + " prox=" + prox
                    + " rec=" + recording
                    + " deterrentEnabled=" + deterrentEnabled
                    + (changed ? "  <-- CHANGE" : ""));
        }
    }

    static void actual(String onOff, String reason) {
        synchronized (lock) {
            String line = "ACTUAL screen " + onOff + "  (" + reason + ")";
            if (line.equals(lastActualLine) && (System.currentTimeMillis() - lastWriteMs) < HEARTBEAT_MS) {
                return;
            }
            lastActualLine = line;
            writeLine(System.currentTimeMillis(), line);
        }
    }

    private static void writeLine(long nowMs, String body) {
        try {
            if (!headerWritten) {
                headerWritten = true;
                try (FileWriter boot = new FileWriter(FILE, false)) {
                    boot.write("=== sentry screen walk log "
                            + format(nowMs)
                            + " ===\n");
                    boot.write("Walk around the car. should=ON means the HU screen should wake.\n");
                }
            }
            try (FileWriter w = new FileWriter(FILE, true)) {
                w.write(format(nowMs) + "  " + body + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private static String format(long nowMs) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(nowMs));
    }
}
