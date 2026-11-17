package com.overdrive.app.server.timeline;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writer-recorded wall time of each clip's first frame, keyed by base file name.
 * Closed hints stay readable for the enricher until evicted. Never blocks or throws.
 */
public final class RecordingSpanHints {

    static final int MAX_ENTRIES = 512;
    // An open hint older than the longest clip is a writer that never reported its close.
    static final long MAX_OPEN_AGE_MS = 6L * 3_600_000L;
    // A writer whose .tmp has not grown for this long is no longer recording.
    static final long STALL_MS = 120_000L;
    static final long GRACE_MS = 30_000L;

    private static final Map<String, Hint> HINTS =
            new LinkedHashMap<String, Hint>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Hint> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private RecordingSpanHints() {}

    private static volatile int elapsedSource; // 0 unknown, 1 SystemClock, 2 nanoTime

    /** Time since boot including deep sleep (SystemClock); nanoTime off-device. */
    static long elapsedMs() {
        if (elapsedSource != 2) {
            try {
                long v = android.os.SystemClock.elapsedRealtime();
                elapsedSource = 1;
                return v;
            } catch (Throwable t) {
                elapsedSource = 2;
            }
        }
        return System.nanoTime() / 1_000_000L;
    }

    static {
        // The first call resolves the clock (reflection): not between a hint's paired wall/elapsed reads.
        elapsedMs();
    }

    public static final class Live {
        public final String fileName;
        public final String type;
        public final long since;

        Live(String fileName, String type, long since) {
            this.fileName = fileName;
            this.type = type;
            this.since = since;
        }
    }

    private static final class Hint {
        final String type;
        final long wallStartMs;
        final long openedAtNs;
        // Wall and boot-elapsed time read at open(): together they re-derive the start after a clock step.
        final long openWallMs;
        final long openElapsedMs;
        final String path;
        boolean open = true;
        // startNow() when the clip closed: a later clock step must not move a finished clip.
        long closedStartMs = Long.MIN_VALUE;
        long lastLen = -1L;
        long lastSeenMs;
        long lastGrowMs;

        Hint(String type, long wallStartMs, long openedAtNs, long openWallMs, String path) {
            this.type = type;
            this.wallStartMs = wallStartMs;
            this.openedAtNs = openedAtNs;
            this.openWallMs = openWallMs;
            this.openElapsedMs = elapsedMs();
            this.path = path;
        }

        /** The first frame's wall time on the current clock: survives the clock being set or stepped, and sleep. */
        long startNow(long nowWallMs) {
            long fixed = nowWallMs - (elapsedMs() - openElapsedMs) - (openWallMs - wallStartMs);
            // No clock step since open (two clocks drift by ms): the recorded value stands exactly.
            return Math.abs(fixed - wallStartMs) < 1_000L ? wallStartMs : fixed;
        }
    }

    /** @param type recording type, or null to derive it from the name */
    public static void open(String fileName, String type, long wallStartMs) {
        put(fileName, type, wallStartMs, true);
    }

    /** A finished clip's start (e.g. a replay export): readable by the enricher, never live. */
    public static void record(String fileName, String type, long wallStartMs) {
        put(fileName, type, wallStartMs, false);
    }

    private static void put(String fileName, String type, long wallStartMs, boolean open) {
        try {
            String key = key(fileName);
            if (key == null || wallStartMs <= 0) return;
            Hint hint = new Hint(type != null ? type : typeOf(key), wallStartMs,
                    System.nanoTime(), System.currentTimeMillis(), fileName);
            synchronized (HINTS) {
                if (!open) closeLocked(hint);
                HINTS.remove(key);
                HINTS.put(key, hint);
            }
        } catch (Throwable ignored) {
            // Hints are advisory.
        }
    }

    public static void close(String fileName) {
        try {
            String key = key(fileName);
            if (key == null) return;
            synchronized (HINTS) {
                Hint hint = HINTS.get(key);
                if (hint != null && hint.open) closeLocked(hint);
            }
        } catch (Throwable ignored) {
            // Hints are advisory.
        }
    }

    private static void closeLocked(Hint hint) {
        hint.open = false;
        hint.closedStartMs = hint.startNow(System.currentTimeMillis());
    }

    /** Wall time of the first frame, or -1 when no writer reported this file. */
    public static long get(String fileName) {
        try {
            String key = key(fileName);
            if (key == null) return -1L;
            synchronized (HINTS) {
                Hint hint = HINTS.get(key);
                if (hint == null) return -1L;
                // The clock stepped during the clip: the start the live band showed, frozen when it closed.
                if (hint.closedStartMs >= SpanRules.SANE_WALL_MS) return hint.closedStartMs;
                // Closed before the clock was set, or still open: re-derived from the monotonic clock.
                long now = System.currentTimeMillis();
                long fixed = hint.startNow(now);
                if (now >= SpanRules.SANE_WALL_MS && fixed >= SpanRules.SANE_WALL_MS) {
                    // The first sane value of a finished clip stands: a later correction must not move it.
                    if (!hint.open) hint.closedStartMs = fixed;
                    return fixed;
                }
                return hint.wallStartMs;
            }
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** Segments being written now, oldest first. */
    public static List<Live> openSegments() {
        List<Live> out = new ArrayList<>();
        try {
            List<String> keys = new ArrayList<>();
            List<Hint> open = new ArrayList<>();
            synchronized (HINTS) {
                Iterator<Map.Entry<String, Hint>> it = HINTS.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Hint> e = it.next();
                    Hint hint = e.getValue();
                    if (!hint.open) continue;
                    if (elapsedMs() - hint.openElapsedMs > MAX_OPEN_AGE_MS) {
                        closeLocked(hint);
                        continue;
                    }
                    keys.add(e.getKey());
                    open.add(hint);
                }
            }
            // File checks run outside the lock so a stalled card never blocks the writer.
            for (int i = 0; i < open.size(); i++) {
                Hint hint = open.get(i);
                // since from the monotonic clock: a stepped wall clock can't push it into the future or 2020.
                // Wall time read after the (possibly slow) file check, next to startNow's elapsed read.
                if (writing(hint)) out.add(new Live(keys.get(i), hint.type, hint.startNow(System.currentTimeMillis())));
            }
        } catch (Throwable ignored) {
            // Advisory.
        }
        return out;
    }

    /** False once the writer's .tmp is gone or has stopped growing (an abort that never reported). */
    static boolean writing(Hint hint) {
        String path = hint.path;
        long nowEl = elapsedMs();
        if (path == null || !path.startsWith("/") || nowEl - hint.openElapsedMs < GRACE_MS) return true;
        File tmp = new File(path.endsWith(".tmp") ? path : path + ".tmp");
        long len = tmp.length();
        if (len <= 0L && !tmp.exists()) return false;
        long mtime = tmp.lastModified();
        long age = System.currentTimeMillis() - mtime;
        // A future mtime (clock stepped back) proves nothing; growth decides then.
        boolean fresh = mtime > 0L && age >= -2_000L && age < STALL_MS;
        synchronized (hint) {
            // Growth counts only against a length seen within the stall window.
            boolean recent = hint.lastLen >= 0L && nowEl - hint.lastSeenMs < STALL_MS;
            if (recent && len != hint.lastLen) hint.lastGrowMs = nowEl;
            hint.lastLen = len;
            hint.lastSeenMs = nowEl;
            return fresh || (hint.lastGrowMs != 0L && nowEl - hint.lastGrowMs < STALL_MS);
        }
    }

    static String key(String fileName) {
        if (fileName == null) return null;
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.endsWith(".tmp")) name = name.substring(0, name.length() - 4);
        return name.isEmpty() ? null : name;
    }

    /** Index type for a recording file name (same grammar as the recordings index). */
    public static String typeOf(String fileName) {
        String name = key(fileName);
        if (name == null) return "normal";
        if (name.startsWith("event_") || name.startsWith("dvr_p_")) return "sentry";
        if (name.startsWith("proximity_")) return "proximity";
        if (name.startsWith("replay_")) return "replay";
        if (name.startsWith("dvr_")) return "oemDashcam";
        return "normal";
    }
}
