package com.overdrive.app.server.timeline;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.util.ScratchRoot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Timeline state journal (surveillance / power / daemon / storage transitions and road hits).
 * Every process appends to a shared spool file; the camera daemon moves it into the index store.
 */
public final class TimelineJournal {

    public static final String KIND_SURVEILLANCE = "surveillance";
    public static final String KIND_POWER = "power";
    public static final String KIND_DAEMON = "daemon";
    public static final String KIND_STORAGE = "storage";
    public static final String KIND_ROAD = "road";

    static final int QUEUE_CAP = 256;
    static final long SYNC_BUDGET_MS = 300L;
    static final long LOCK_WAIT_MS = 200L;
    static final long SPOOL_MAX_BYTES = 1L << 20;
    static final long PRUNE_AGE_MS = 90L * 86_400_000L;
    static final long HINT_TTL_MS = 5_000L;
    static final long HEARTBEAT_MS = 60_000L;
    // A user_off disable this close to a power stop came from that power stop's command.
    static final long RELABEL_WINDOW_MS = 15_000L;
    static final String SPOOL_NAME = "timeline_journal.spool";
    static final String ALIVE_NAME = "timeline_alive";
    // A forward wall-clock step larger than this (measured on the boot clock) blocks the prune.
    static final long MAX_CLOCK_JUMP_MS = 30L * 86_400_000L;

    private static final ArrayDeque<Entry> QUEUE = new ArrayDeque<>();
    private static final Object SPOOL_LOCK = new Object();
    private static final ThreadLocal<Object[]> REASON_HINT = new ThreadLocal<>();
    private static Thread writer;
    private static boolean started;
    // Boot-clock time of the last heartbeat.
    private static volatile long lastHeartbeatMs;
    private static final Object CLOCK_LOCK = new Object();
    // Last wall reading trusted for pruning, and the boot-clock time it was taken at.
    private static long saneWallMs = -1L;
    private static long saneAtElapsedMs;

    private TimelineJournal() {}

    static final class Entry {
        final long ts;
        final String kind;
        final String state;
        final String reason;
        final String extra;

        Entry(long ts, String kind, String state, String reason, String extra) {
            this.ts = ts;
            this.kind = TimelineDb.clamp(kind, 16);
            this.state = TimelineDb.clamp(state, 16);
            this.reason = TimelineDb.clamp(reason, 32);
            this.extra = TimelineDb.clamp(extra, 128);
        }
    }

    public static final class Interval {
        public final long start;
        public final Long end;
        public final String kind;
        public final String state;
        public final String reason;

        Interval(long start, Long end, String kind, String state, String reason) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.state = state;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return kind + ":" + state + "/" + reason + "[" + start + ".." + end + ")";
        }
    }

    // ------------------------------------------------------------------ writers

    /** Async, bounded (drop-oldest), never throws. */
    public static void note(String kind, String state, String reason, String extra) {
        note(System.currentTimeMillis(), kind, state, reason, extra);
    }

    static void note(long ts, String kind, String state, String reason, String extra) {
        try {
            if (kind == null || state == null) return;
            Entry entry = new Entry(ts, kind, state, reason, extra);
            synchronized (QUEUE) {
                while (QUEUE.size() >= QUEUE_CAP) QUEUE.pollFirst();
                QUEUE.addLast(entry);
                if (writer == null) startWriterLocked();
                QUEUE.notifyAll();
            }
        } catch (Throwable ignored) {
            // Journal is advisory.
        }
    }

    /** For paths that exit the process: flushes pending notes plus this one, waiting at most 300 ms. */
    public static void noteSync(String kind, String state, String reason, String extra) {
        try {
            final List<Entry> batch = new ArrayList<>();
            synchronized (QUEUE) {
                batch.addAll(QUEUE);
                QUEUE.clear();
            }
            if (kind != null && state != null) {
                batch.add(new Entry(System.currentTimeMillis(), kind, state, reason, extra));
            }
            Thread sync = new Thread(() -> appendToSpool(batch, true), "TimelineJournalSync");
            sync.setDaemon(true);
            sync.start();
            sync.join(SYNC_BUDGET_MS);
            TimelineEnricher.kick();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
            // Exit path must not be delayed further.
        }
    }

    /** Next disableSurveillance on this thread carries {@code reason}. */
    public static void reasonHint(String reason) {
        try {
            REASON_HINT.set(new Object[]{reason, RecordingSpanHints.elapsedMs()});
        } catch (Throwable ignored) {
            // Advisory.
        }
    }

    /** Surveillance disarmed; reason from {@link #reasonHint} on this thread, else user_off. */
    public static void surveillanceOff(String caller) {
        try {
            Object[] hint = REASON_HINT.get();
            REASON_HINT.remove();
            String reason = null;
            if (hint != null && hint[0] instanceof String && hint[1] instanceof Long
                    && RecordingSpanHints.elapsedMs() - (Long) hint[1] <= HINT_TTL_MS) {
                reason = (String) hint[0];
            }
            note(KIND_SURVEILLANCE, "off", reason != null ? reason : "user_off", caller);
        } catch (Throwable ignored) {
            // Advisory.
        }
    }

    public static void surveillanceOn() {
        note(KIND_SURVEILLANCE, "on", null, null);
    }

    /** One road detection; UNKNOWN hazards are skipped. */
    public static void noteRoad(String hazardType, String severity, long tsMs) {
        try {
            String kind = roadKind(hazardType);
            String level = roadSeverity(severity);
            if (kind == null || level == null) return;
            note(tsMs > 0 ? tsMs : System.currentTimeMillis(), KIND_ROAD, kind, level, null);
        } catch (Throwable ignored) {
            // Advisory.
        }
    }

    /** Records the down interval since the previous daemon's last heartbeat, then the start. */
    public static void daemonStarted() {
        synchronized (TimelineJournal.class) {
            if (started) return;
            started = true;
        }
        try {
            long now = System.currentTimeMillis();
            File alive = aliveFile();
            long last = RemovableStorageFs.isFile(alive) ? RemovableStorageFs.lastModified(alive) : 0L;
            if (last > 0 && last < now) note(last, KIND_DAEMON, "stopped", "daemon_stop", null);
            note(now, KIND_DAEMON, "on", "daemon_start", null);
            // The previous daemon's last heartbeat is the reference a boot-time clock is checked against.
            seedSaneClock(last);
            touchAlive(now);
            lastHeartbeatMs = RecordingSpanHints.elapsedMs();
        } catch (Throwable ignored) {
            // Advisory.
        }
    }

    static void heartbeat() {
        boolean first;
        synchronized (TimelineJournal.class) {
            first = !started;
        }
        if (first) daemonStarted();
        long el = RecordingSpanHints.elapsedMs();
        if (el - lastHeartbeatMs < HEARTBEAT_MS) return;
        lastHeartbeatMs = el;
        touchAlive(System.currentTimeMillis());
    }

    /** False before 2024, or when the wall clock ran far ahead of the boot clock since the last sane reading. */
    static boolean wallClockSane(long wallMs) {
        if (wallMs < SpanRules.SANE_WALL_MS) return false;
        long el = RecordingSpanHints.elapsedMs();
        synchronized (CLOCK_LOCK) {
            if (saneWallMs > 0 && wallMs - saneWallMs - (el - saneAtElapsedMs) > MAX_CLOCK_JUMP_MS) {
                return false;
            }
            saneWallMs = wallMs;
            saneAtElapsedMs = el;
            return true;
        }
    }

    private static void seedSaneClock(long wallMs) {
        if (wallMs < SpanRules.SANE_WALL_MS) return;
        synchronized (CLOCK_LOCK) {
            if (saneWallMs > 0) return;
            saneWallMs = wallMs;
            saneAtElapsedMs = RecordingSpanHints.elapsedMs();
        }
    }

    static String roadKind(String hazardType) {
        if (hazardType == null) return null;
        switch (hazardType.toUpperCase(Locale.US)) {
            case "POTHOLE":
                return "pothole";
            case "BREAKER":
                return "breaker";
            case "ROUGH_SECTION":
            case "ROUGH":
                return "rough";
            default:
                return null;
        }
    }

    static String roadSeverity(String severity) {
        if (severity == null) return null;
        switch (severity.toUpperCase(Locale.US)) {
            case "MINOR":
                return "minor";
            case "MODERATE":
                return "moderate";
            case "SEVERE":
                return "severe";
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ spool

    private static void startWriterLocked() {
        Thread t = new Thread(TimelineJournal::writerLoop, "TimelineJournal");
        t.setDaemon(true);
        t.start();
        writer = t;
    }

    private static void writerLoop() {
        while (true) {
            try {
                List<Entry> batch = new ArrayList<>();
                synchronized (QUEUE) {
                    while (QUEUE.isEmpty()) {
                        try {
                            QUEUE.wait();
                        } catch (InterruptedException ignored) {
                            // Never interrupted by this package; keep draining.
                        }
                    }
                    batch.addAll(QUEUE);
                    QUEUE.clear();
                }
                appendToSpool(batch, false);
                TimelineEnricher.kick();
            } catch (Throwable ignored) {
                // The writer outlives any single bad batch.
            }
        }
    }

    static File spoolFile() {
        return new File(ScratchRoot.path(SPOOL_NAME));
    }

    static File aliveFile() {
        return new File(ScratchRoot.path(ALIVE_NAME));
    }

    private static void touchAlive(long now) {
        try {
            File alive = aliveFile();
            if (!RemovableStorageFs.exists(alive)) RemovableStorageFs.openOutput(alive, true).close();
            RemovableStorageFs.setLastModified(alive, now);
        } catch (Throwable ignored) {
            // Advisory.
        }
    }

    static boolean appendToSpool(List<Entry> batch, boolean force) {
        if (batch == null || batch.isEmpty()) return true;
        byte[] bytes = encode(batch);
        synchronized (SPOOL_LOCK) {
            try {
                File file = spoolFile();
                if (RemovableStorageFs.length(file) + bytes.length > SPOOL_MAX_BYTES) return false;
                try (FileOutputStream out = RemovableStorageFs.openOutput(file, true)) {
                    FileChannel channel = out.getChannel();
                    FileLock lock = lock(channel);
                    if (lock == null) return false;
                    try {
                        out.write(bytes);
                        if (force) channel.force(false);
                    } finally {
                        release(lock);
                    }
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** Takes every spooled entry and empties the spool. */
    static List<Entry> drainSpool() {
        byte[] bytes = readAndClearSpool();
        if (bytes == null) return Collections.emptyList();
        // UTF-8 decoding and entry parsing can be expensive for a full spool.
        // Do that work after both the cross-process file lock and SPOOL_LOCK are released,
        // so other processes and the async writer can append new events immediately.
        try {
            return decode(bytes);
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** Snapshot and truncate atomically under the same locks used by appendToSpool. */
    private static byte[] readAndClearSpool() {
        synchronized (SPOOL_LOCK) {
            File file = spoolFile();
            if (!RemovableStorageFs.isFile(file) || RemovableStorageFs.length(file) == 0) return null;
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                FileChannel channel = raf.getChannel();
                FileLock lock = lock(channel);
                if (lock == null) return null;
                try {
                    long size = Math.min(channel.size(), SPOOL_MAX_BYTES * 2);
                    byte[] buf = new byte[(int) size];
                    raf.seek(0);
                    raf.readFully(buf);
                    channel.truncate(0);
                    return buf;
                } finally {
                    release(lock);
                }
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static FileLock lock(FileChannel channel) {
        long deadline = RecordingSpanHints.elapsedMs() + LOCK_WAIT_MS;
        while (true) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException e) {
                // Held by this process; SPOOL_LOCK makes this transient.
            } catch (Throwable t) {
                return null;
            }
            if (RecordingSpanHints.elapsedMs() >= deadline) return null;
            try {
                Thread.sleep(5);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static void release(FileLock lock) {
        try {
            lock.release();
        } catch (Throwable ignored) {
            // Closing the channel releases it too.
        }
    }

    static byte[] encode(List<Entry> batch) {
        StringBuilder sb = new StringBuilder(batch.size() * 48);
        for (Entry e : batch) {
            sb.append(e.ts).append('\t').append(clean(e.kind)).append('\t').append(clean(e.state))
                    .append('\t').append(clean(e.reason)).append('\t').append(clean(e.extra))
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static List<Entry> decode(byte[] bytes) {
        List<Entry> out = new ArrayList<>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 5) continue;
            long ts;
            try {
                ts = Long.parseLong(parts[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (ts <= 0 || parts[1].isEmpty() || parts[2].isEmpty()) continue;
            out.add(new Entry(ts, parts[1], parts[2], emptyToNull(parts[3]), emptyToNull(parts[4])));
        }
        return out;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    // ------------------------------------------------------------------ store

    /** Moves spooled entries into timeline_journal; puts them back when the store is down. */
    static int ingest() {
        List<Entry> entries = drainSpool();
        if (entries.isEmpty()) return 0;
        Boolean ok = TimelineDb.sql("timeline journal ingest", Boolean.FALSE, c -> insert(c, entries));
        if (!Boolean.TRUE.equals(ok)) {
            appendToSpool(entries, false);
            return 0;
        }
        return entries.size();
    }

    private static Boolean insert(Connection c, List<Entry> entries) throws Exception {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO timeline_journal"
                + " (ts, kind, state, reason, extra) VALUES (?, ?, ?, ?, ?)")) {
            for (Entry e : entries) {
                ps.setLong(1, e.ts);
                ps.setString(2, e.kind);
                ps.setString(3, e.state);
                ps.setString(4, e.reason);
                ps.setString(5, e.extra);
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            return Boolean.TRUE;
        } catch (Exception e) {
            try {
                c.rollback();
            } catch (Exception ignored) {
                // Connection already failed.
            }
            throw e;
        } finally {
            try {
                c.setAutoCommit(autoCommit);
            } catch (Exception ignored) {
                // Connection already failed.
            }
        }
    }

    /** Callers check {@link #wallClockSane} first: a bad clock would delete the whole journal. */
    static int prune(Connection c, long now) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM timeline_journal WHERE ts < ?")) {
            ps.setLong(1, now - PRUNE_AGE_MS);
            return ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ intervals

    private static boolean isStateKind(String kind) {
        return KIND_SURVEILLANCE.equals(kind) || KIND_POWER.equals(kind)
                || KIND_DAEMON.equals(kind) || KIND_STORAGE.equals(kind);
    }

    private static boolean isGapState(String state) {
        return "off".equals(state) || "paused".equals(state) || "stopped".equals(state)
                || "unavailable".equals(state);
    }

    private static final class Open {
        final long start;
        final String state;
        final String reason;

        Open(long start, String state, String reason) {
            this.start = start;
            this.state = state;
            this.reason = reason;
        }
    }

    /**
     * Journal rows (any order) to state intervals. "on" closes its kind; surveillance on and
     * the next trip start also close an open power interval.
     */
    static List<Interval> intervals(List<Entry> rows, List<Long> tripStarts) {
        List<Entry> sorted = new ArrayList<>(rows);
        Collections.sort(sorted, Comparator.comparingLong(e -> e.ts));
        Map<String, Open> open = new HashMap<>();
        List<Interval> out = new ArrayList<>();
        for (Entry row : sorted) {
            if (!isStateKind(row.kind)) continue;
            if ("on".equals(row.state)) {
                close(open, out, row.kind, row.ts);
                if (KIND_SURVEILLANCE.equals(row.kind)) close(open, out, KIND_POWER, row.ts);
                continue;
            }
            if (!isGapState(row.state)) continue;
            String reason = row.reason != null ? row.reason : "unknown";
            Open current = open.get(row.kind);
            if (current != null && current.state.equals(row.state)
                    && current.reason.equals(reason)) {
                continue;
            }
            close(open, out, row.kind, row.ts);
            open.put(row.kind, new Open(row.ts, row.state, reason));
        }
        for (Map.Entry<String, Open> e : open.entrySet()) {
            Open o = e.getValue();
            out.add(new Interval(o.start, null, e.getKey(), o.state, o.reason));
        }

        List<Long> trips = tripStarts != null ? new ArrayList<>(tripStarts) : new ArrayList<Long>();
        Collections.sort(trips);
        List<Interval> powerStarts = new ArrayList<>();
        // Each kind has at most one open interval. Its starts appear in
        // chronological order in out (closed intervals, then the open tail),
        // even though intervals of different kinds may be interleaved.
        int tripCursor = 0;
        for (int i = 0; i < out.size(); i++) {
            Interval iv = out.get(i);
            if (!KIND_POWER.equals(iv.kind)) continue;
            while (tripCursor < trips.size()) {
                Long t = trips.get(tripCursor);
                if (t != null && t > iv.start) break;
                tripCursor++;
            }
            if (tripCursor < trips.size()) {
                long t = trips.get(tripCursor);
                if (iv.end == null || t < iv.end) {
                    iv = new Interval(iv.start, t, iv.kind, iv.state, iv.reason);
                    out.set(i, iv);
                }
            }
            powerStarts.add(iv);
        }
        int powerCursor = 0;
        for (int i = 0; i < out.size(); i++) {
            Interval iv = out.get(i);
            if (!KIND_SURVEILLANCE.equals(iv.kind) || !"user_off".equals(iv.reason)) continue;
            // For real (nonnegative) wall timestamps, subtraction cannot
            // overflow. Preserve the original Math.abs behavior on unusual
            // negative timestamps, including signed-long overflow cases.
            if (iv.start < 0 || (!powerStarts.isEmpty() && powerStarts.get(0).start < 0)) {
                for (Interval p : powerStarts) {
                    if (Math.abs(p.start - iv.start) <= RELABEL_WINDOW_MS) {
                        out.set(i, new Interval(iv.start, iv.end, iv.kind, iv.state, p.reason));
                        break;
                    }
                }
                continue;
            }
            while (powerCursor < powerStarts.size()
                    && powerStarts.get(powerCursor).start < iv.start - RELABEL_WINDOW_MS) {
                powerCursor++;
            }
            if (powerCursor < powerStarts.size()) {
                Interval p = powerStarts.get(powerCursor);
                if (p.start - iv.start <= RELABEL_WINDOW_MS) {
                    out.set(i, new Interval(iv.start, iv.end, iv.kind, iv.state, p.reason));
                }
            }
        }
        List<Interval> result = new ArrayList<>();
        for (Interval iv : out) {
            if (iv.end == null || iv.end > iv.start) result.add(iv);
        }
        Collections.sort(result, Comparator.comparingLong(iv -> iv.start));
        return result;
    }

    private static void close(Map<String, Open> open, List<Interval> out, String kind, long at) {
        Open o = open.remove(kind);
        if (o != null) out.add(new Interval(o.start, at, kind, o.state, o.reason));
    }
}
