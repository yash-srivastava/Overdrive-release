package com.overdrive.app.server.timeline;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Fills recording_timeline spans and recording_marks for index rows whose file fingerprint
 * changed. File I/O runs outside the index monitor; writes are short guarded transactions.
 */
public final class TimelineEnricher {

    private static final DaemonLogger logger = DaemonLogger.getInstance("RecordingsTimeline");

    static final int BATCH = 20;
    static final long PERIOD_MS = 60_000L;
    static final long FULL_SWEEP_MS = 3_600_000L;
    static final long ORPHAN_SWEEP_MS = 3_600_000L;
    static final long PRUNE_MS = 6L * 3_600_000L;
    static final long RECENT_WINDOW_MS = 6L * 3_600_000L;
    static final long STARTUP_RECENT_MS = 3L * 86_400_000L;
    static final long WARMUP_WAIT_MAX_MS = 15L * 60_000L;
    static final long SCHEMA_RETRY_MS = 10L * 60_000L;
    static final long BATCH_PAUSE_MS = 20L;
    // While a camera records: at most one clip per this long, so the card and CPU stay the recorder's.
    // One clip a second while a recording is open: the moov and sidecar reads share the card with the muxer.
    static final long RECORDING_PACE_MS = 1_000L;
    static final long RECORDING_CHECK_MS = 1_000L;
    static final int PENDING_MAX = 1024;
    static final int SPAN_CACHE_MAX = 4096;
    static final int RECENT_MAX = 500;
    static final int SWEEP_PAGE = 500;

    private static final String COLUMNS = "r.recording_id, r.filename, r.abs_path, r.type, r.ts_ms,"
            + " r.size_bytes, r.mp4_mtime, r.sidecar_mtime, r.is_available, t.tl_fp, t.tl_src,"
            + " t.tl_start_ms";
    private static final String JOIN = " LEFT JOIN recording_timeline t"
            + " ON t.recording_id = r.recording_id";
    // No span yet, or one computed for another revision of the row. A failed span counts as current.
    private static final String STALE = "(t.tl_fp IS NULL OR t.tl_fp <> CONCAT(r.size_bytes, '-',"
            + " r.mp4_mtime, '-', r.sidecar_mtime))";

    private static volatile Thread thread;
    private static volatile boolean kicked;
    private static final ConcurrentLinkedQueue<String> PENDING = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_SIZE = new AtomicInteger();
    // Fingerprints whose write failed this session; the side table keeps them across restarts.
    private static final Map<String, String> FAILED = new HashMap<>();
    private static int clipsDone;
    private static boolean recordingChecked;
    private static boolean recordingOpen;
    private static long recordingCheckedAt;

    private static final Map<String, long[]> SPANS =
            new LinkedHashMap<String, long[]>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                    return size() > SPAN_CACHE_MAX;
                }
            };

    private TimelineEnricher() {}

    /** Starts the "RecordingsTimeline" thread once; it waits for the index warmup first. */
    public static synchronized void start() {
        if (thread != null) return;
        Thread t = new Thread(TimelineEnricher::loop, "RecordingsTimeline");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 2);
        thread = t;
        t.start();
    }

    /** Non-blocking; safe under the index monitor. */
    public static void kick() {
        kicked = true;
        Thread t = thread;
        if (t != null) LockSupport.unpark(t);
    }

    public static void kick(String recordingId) {
        enqueue(recordingId);
        kick();
    }

    // A kick dropped when the queue is full is picked up by the next full sweep.
    private static void enqueue(String recordingId) {
        if (recordingId == null) return;
        if (PENDING_SIZE.incrementAndGet() > PENDING_MAX) {
            PENDING_SIZE.decrementAndGet();
        } else {
            PENDING.offer(recordingId);
        }
    }

    /** Last span computed this session for a file: {start, end or -1}; null when unknown. */
    static long[] spanFor(String absPath) {
        if (absPath == null) return null;
        synchronized (SPANS) {
            long[] span = SPANS.get(absPath);
            return span != null ? span.clone() : null;
        }
    }

    // ------------------------------------------------------------------ loop

    private static void loop() {
        long waitStart = RecordingSpanHints.elapsedMs();
        while (!TimelineDb.warmupComplete()
                && RecordingSpanHints.elapsedMs() - waitStart < WARMUP_WAIT_MAX_MS) {
            TimelineJournal.heartbeat();
            LockSupport.parkNanos(2_000_000_000L);
        }
        // The startup sweep covers everything queued so far.
        PENDING.clear();
        PENDING_SIZE.set(0);
        boolean startup = true;
        // Intervals run on the boot clock; only span and prune cut-offs use wall time.
        long begin = RecordingSpanHints.elapsedMs();
        long lastFull = begin;
        long lastRecent = begin;
        long lastOrphans = begin;
        long lastPrune = begin - PRUNE_MS;
        long lastSchemaTry = begin;
        while (true) {
            kicked = false;
            long el = RecordingSpanHints.elapsedMs();
            long wall = System.currentTimeMillis();
            try {
                TimelineJournal.heartbeat();
                if (!TimelineSchema.isReady()) {
                    if (el - lastSchemaTry >= SCHEMA_RETRY_MS) {
                        lastSchemaTry = el;
                        TimelineDb.sql("timeline schema", Boolean.FALSE, c -> {
                            TimelineSchema.ensure(c);
                            return Boolean.TRUE;
                        });
                    }
                } else {
                    TimelineJournal.ingest();
                    requeueMisdated(wall);
                    processPending();
                    // The startup pass, then at most hourly; measured from the end of the last sweep.
                    if (startup || el - lastFull >= FULL_SWEEP_MS) {
                        if (startup) sweepRecent(wall - STARTUP_RECENT_MS);
                        int done = sweepAll();
                        if (startup && done > 0) logger.info("Timeline startup sweep: " + done);
                        startup = false;
                        lastFull = RecordingSpanHints.elapsedMs();
                        lastRecent = lastFull;
                    } else if (el - lastRecent >= PERIOD_MS) {
                        sweepRecent(wall - RECENT_WINDOW_MS);
                        lastRecent = RecordingSpanHints.elapsedMs();
                    }
                    if (el - lastOrphans >= ORPHAN_SWEEP_MS) {
                        deleteOrphanMarks();
                        deleteOrphanSpans();
                        lastOrphans = RecordingSpanHints.elapsedMs();
                    }
                    // A skipped prune (untrusted wall clock, store down) is retried next pass.
                    if (el - lastPrune >= PRUNE_MS && prune(System.currentTimeMillis())) lastPrune = el;
                }
            } catch (Throwable t) {
                logger.warn("Timeline pass failed: " + t);
            }
            if (!kicked) LockSupport.parkNanos(PERIOD_MS * 1_000_000L);
        }
    }

    /** One synchronous pass without the thread (startup-equivalent when {@code full}). */
    static int runOnce(boolean full) {
        TimelineJournal.ingest();
        requeueMisdated(System.currentTimeMillis());
        int done = processPending();
        if (full) done += sweepAll();
        else done += sweepRecent(System.currentTimeMillis() - RECENT_WINDOW_MS);
        return done;
    }

    // ------------------------------------------------------------------ candidates

    static final class Candidate {
        String id;
        String fileName;
        String absPath;
        String type;
        long tsMs;
        long sizeBytes;
        long mp4Mtime;
        long sidecarMtime;
        boolean available;
        String tlFp;
        String tlSrc;
        Long tlStart;

        String fingerprint() {
            return sizeBytes + "-" + mp4Mtime + "-" + sidecarMtime;
        }

        boolean stale() {
            return tlFp == null || !tlFp.equals(fingerprint());
        }

        /** Placed before the clock was set; worth one more look now that it is (a writer hint may re-anchor it). */
        boolean misdated() {
            return tlStart != null && tlStart < SpanRules.SANE_WALL_MS
                    && System.currentTimeMillis() >= SpanRules.SANE_WALL_MS;
        }
    }

    private static Candidate read(ResultSet rs) throws Exception {
        Candidate c = new Candidate();
        c.id = rs.getString(1);
        c.fileName = rs.getString(2);
        c.absPath = rs.getString(3);
        c.type = rs.getString(4);
        c.tsMs = rs.getLong(5);
        c.sizeBytes = rs.getLong(6);
        c.mp4Mtime = rs.getLong(7);
        c.sidecarMtime = rs.getLong(8);
        c.available = rs.getBoolean(9);
        c.tlFp = rs.getString(10);
        c.tlSrc = rs.getString(11);
        long start = rs.getLong(12);
        c.tlStart = rs.wasNull() ? null : start;
        return c;
    }

    private static int processPending() {
        int done = 0;
        int budget = PENDING_MAX;
        while (budget > 0) {
            final List<String> ids = new ArrayList<>(BATCH);
            String id;
            while (ids.size() < BATCH && (id = PENDING.poll()) != null) {
                PENDING_SIZE.decrementAndGet();
                if (!ids.contains(id)) ids.add(id);
            }
            if (ids.isEmpty()) break;
            budget -= ids.size();
            List<Candidate> rows = TimelineDb.sql("timeline pending", null, c -> {
                StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM recordings r"
                        + JOIN + " WHERE r.recording_id IN (");
                for (int i = 0; i < ids.size(); i++) sql.append(i == 0 ? "?" : ", ?");
                sql.append(')');
                List<Candidate> out = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                    for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(read(rs));
                    }
                }
                return out;
            });
            if (rows == null) break;
            for (Candidate row : rows) {
                if (row.available && needsWork(row) && enrich(row)) {
                    noteDone(row);
                    done++;
                }
            }
        }
        return done;
    }

    /**
     * Stale available rows in recording_id order. Each page is SWEEP_PAGE primary-key rows, tested
     * in SQL, so only rows needing work leave the store and no statement scans more than one page.
     */
    static int sweepAll() {
        int done = 0;
        String after = "";
        while (true) {
            final String from = after;
            Object[] page = TimelineDb.sql("timeline sweep", null, c -> {
                String last;
                try (PreparedStatement ps = c.prepareStatement("SELECT MAX(k.recording_id) FROM"
                        + " (SELECT recording_id FROM recordings WHERE recording_id > ?"
                        + " ORDER BY recording_id LIMIT " + SWEEP_PAGE + ") k")) {
                    ps.setString(1, from);
                    try (ResultSet rs = ps.executeQuery()) {
                        last = rs.next() ? rs.getString(1) : null;
                    }
                }
                List<Candidate> out = new ArrayList<>();
                if (last == null) return new Object[]{null, out};
                // The derived page bounds the scan whatever plan H2 picks for the stale test.
                try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM"
                        + " (SELECT recording_id, filename, abs_path, type, ts_ms, size_bytes,"
                        + " mp4_mtime, sidecar_mtime, is_available FROM recordings"
                        + " WHERE recording_id > ? ORDER BY recording_id LIMIT " + SWEEP_PAGE + ") r"
                        + JOIN + " WHERE r.is_available = TRUE AND " + STALE
                        + " ORDER BY r.recording_id")) {
                    ps.setString(1, from);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(read(rs));
                    }
                }
                return new Object[]{last, out};
            });
            if (page == null || page[0] == null) break;
            @SuppressWarnings("unchecked")
            List<Candidate> rows = (List<Candidate>) page[1];
            for (Candidate row : rows) {
                if (needsWork(row) && enrich(row)) {
                    noteDone(row);
                    done++;
                }
            }
            after = (String) page[0];
            // A long (paced) sweep must not hold back the heartbeat, the journal or fresh clips.
            TimelineJournal.heartbeat();
            TimelineJournal.ingest();
            done += processPending();
            pause();
        }
        return done;
    }

    static int sweepRecent(final long sinceMs) {
        List<Candidate> rows = TimelineDb.sql("timeline recent", null, c -> {
            List<Candidate> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM recordings r"
                    + JOIN + " WHERE r.is_available = TRUE AND r.ts_ms >= ? AND " + STALE
                    + " LIMIT " + RECENT_MAX)) {
                ps.setLong(1, sinceMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(read(rs));
                }
            }
            return out;
        });
        if (rows == null) return 0;
        int done = 0;
        for (Candidate row : rows) {
            if (enrich(row)) done++;
        }
        return done;
    }

    // Misdated rows re-anchored this session (by fingerprint), so each is redone once.
    private static final Map<String, String> MISDATED_DONE = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > SPAN_CACHE_MAX;
        }
    };

    // Rows given a pre-2024 start this session; only these can have a writer hint to re-anchor them.
    private static final Map<String, Boolean> MISDATED = new LinkedHashMap<String, Boolean>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > SPAN_CACHE_MAX;
        }
    };

    /**
     * Stale rows, plus misdated ones that a writer hint can now re-anchor (only the in-memory hint
     * carries the monotonic time; without it nothing new can be learned, so the row is left alone).
     */
    static boolean needsWork(Candidate row) {
        if (row.stale()) return true;
        if (!row.misdated()) return false;
        if (RecordingSpanHints.get(row.fileName) < SpanRules.SANE_WALL_MS) return false;
        synchronized (MISDATED_DONE) {
            return !row.fingerprint().equals(MISDATED_DONE.get(row.id));
        }
    }

    private static void noteDone(Candidate row) {
        if (!row.misdated()) return;
        synchronized (MISDATED_DONE) {
            MISDATED_DONE.put(row.id, row.fingerprint());
        }
    }

    /** Once the wall clock is sane, queues this session's misdated rows for one more look. */
    private static void requeueMisdated(long wallMs) {
        if (wallMs < SpanRules.SANE_WALL_MS) return;
        List<String> ids;
        synchronized (MISDATED) {
            if (MISDATED.isEmpty()) return;
            ids = new ArrayList<>(MISDATED.keySet());
            MISDATED.clear();
        }
        for (String id : ids) enqueue(id);
    }

    private static void pause() {
        LockSupport.parkNanos(BATCH_PAUSE_MS * 1_000_000L);
    }

    /** True while a camera writes a segment; re-checked at most once a second. */
    static boolean recording() {
        long el = RecordingSpanHints.elapsedMs();
        if (!recordingChecked || el - recordingCheckedAt >= RECORDING_CHECK_MS) {
            recordingOpen = !RecordingSpanHints.openSegments().isEmpty();
            recordingCheckedAt = el;
            recordingChecked = true;
        }
        return recordingOpen;
    }

    // After each clip's file I/O.
    private static void pace() {
        if (recording()) LockSupport.parkNanos(RECORDING_PACE_MS * 1_000_000L);
        else if (++clipsDone % BATCH == 0) pause();
    }

    // ------------------------------------------------------------------ enrich

    /** File I/O here, outside the index monitor; one short guarded write. */
    static boolean enrich(Candidate row) {
        String fingerprint = row.fingerprint();
        synchronized (FAILED) {
            if (fingerprint.equals(FAILED.get(row.id))) return false;
        }
        if (row.absPath == null) return false;
        File mp4 = new File(row.absPath);
        // A vanished or rewritten file is re-queued by its own index refresh.
        if (!mp4.isFile() || mp4.length() != row.sizeBytes) return false;
        try {
            return enrichFile(row, mp4, fingerprint);
        } finally {
            pace();
        }
    }

    private static boolean enrichFile(Candidate row, File mp4, String fingerprint) {
        long durationMs = Mp4Header.durationMs(mp4);
        SidecarTimeline.Facts facts = SidecarTimeline.read(mp4);
        long hint = RecordingSpanHints.get(row.fileName);
        if (hint <= 0 && SpanRules.SRC_WRITER.equals(row.tlSrc) && row.tlStart != null
                && row.tlFp != null && row.tlFp.startsWith(row.sizeBytes + "-" + row.mp4Mtime + "-")) {
            hint = row.tlStart;
        }
        SpanRules.Span span = SpanRules.compute(row.type, row.fileName, row.tsMs, durationMs,
                facts, hint, mp4.lastModified());
        String layout = SidecarTimeline.layoutFor(row.type, row.fileName, facts);
        List<SidecarTimeline.Mark> marks = SidecarTimeline.marks(facts, span.start);

        Integer written = TimelineDb.sql("timeline enrich", null,
                c -> write(c, row, span, layout, fingerprint, marks));
        if (written == null) return false;
        if (written == WRITE_REJECTED) {
            synchronized (FAILED) {
                if (FAILED.size() > SPAN_CACHE_MAX) FAILED.clear();
                FAILED.put(row.id, fingerprint);
            }
            return false;
        }
        if (written == WRITE_DONE) {
            synchronized (SPANS) {
                SPANS.put(row.absPath, new long[]{span.start, span.end != null ? span.end : -1L});
            }
            if (span.start < SpanRules.SANE_WALL_MS) {
                synchronized (MISDATED) {
                    MISDATED.put(row.id, Boolean.TRUE);
                }
            }
        }
        return written == WRITE_DONE;
    }

    static final int WRITE_DONE = 1;
    static final int WRITE_STALE = 0;
    static final int WRITE_REJECTED = -1;

    private static Integer write(Connection c, Candidate row, SpanRules.Span span, String layout,
                                 String fingerprint, List<SidecarTimeline.Mark> marks)
            throws Exception {
        boolean autoCommit = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            if (!unchanged(c, row)) {
                c.commit();
                return WRITE_STALE;
            }
            try (PreparedStatement ps = c.prepareStatement("MERGE INTO recording_timeline"
                    + " (recording_id, tl_start_ms, tl_end_ms, tl_src, tl_fp, tl_layout, tl_failed)"
                    + " KEY(recording_id) VALUES (?, ?, ?, ?, ?, ?, FALSE)")) {
                ps.setString(1, row.id);
                ps.setLong(2, span.start);
                if (span.end != null) ps.setLong(3, span.end);
                else ps.setNull(3, java.sql.Types.BIGINT);
                ps.setString(4, span.src);
                ps.setString(5, TimelineDb.clamp(fingerprint, 96));
                ps.setString(6, layout);
                ps.executeUpdate();
            }
            deleteMarks(c, row.id);
            if (!marks.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO recording_marks"
                        + " (recording_id, t0, t1, kind, conf, cam, alert)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    for (SidecarTimeline.Mark m : marks) {
                        ps.setString(1, row.id);
                        ps.setLong(2, m.t0);
                        ps.setLong(3, m.t1);
                        ps.setString(4, m.kind);
                        if (m.conf != null) ps.setDouble(5, m.conf);
                        else ps.setNull(5, java.sql.Types.DOUBLE);
                        ps.setString(6, TimelineDb.clamp(m.cam, 8));
                        ps.setBoolean(7, m.alert);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            c.commit();
            return WRITE_DONE;
        } catch (Error err) {
            // Restoring autoCommit below would otherwise commit the partial batch.
            rollbackQuietly(c);
            throw err;
        } catch (Exception e) {
            rollbackQuietly(c);
            // Data / constraint errors belong to this row; anything else is the store's.
            if (!rowError(e)) throw e;
            markFailed(c, row, fingerprint);
            return WRITE_REJECTED;
        } finally {
            try {
                c.setAutoCommit(autoCommit);
            } catch (Exception ignored) {
                // Connection already failed.
            }
        }
    }

    /** The row is still the revision the span was computed from (same monitor hold as the write). */
    private static boolean unchanged(Connection c, Candidate row) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM recordings"
                + " WHERE recording_id = ? AND size_bytes = ? AND mp4_mtime = ? AND sidecar_mtime = ?")) {
            ps.setString(1, row.id);
            ps.setLong(2, row.sizeBytes);
            ps.setLong(3, row.mp4Mtime);
            ps.setLong(4, row.sidecarMtime);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Persists the rejected fingerprint with no span, so restarts don't retry it until the file changes. */
    private static void markFailed(Connection c, Candidate row, String fingerprint) {
        try {
            try (PreparedStatement ps = c.prepareStatement("MERGE INTO recording_timeline"
                    + " (recording_id, tl_start_ms, tl_end_ms, tl_src, tl_fp, tl_layout, tl_failed)"
                    + " KEY(recording_id) VALUES (?, NULL, NULL, NULL, ?, NULL, TRUE)")) {
                ps.setString(1, row.id);
                ps.setString(2, TimelineDb.clamp(fingerprint, 96));
                ps.executeUpdate();
            }
            deleteMarks(c, row.id);
            c.commit();
        } catch (Exception e) {
            // The in-memory FAILED entry still stops retries this session.
            rollbackQuietly(c);
        }
    }

    private static void deleteMarks(Connection c, String recordingId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM recording_marks WHERE recording_id = ?")) {
            ps.setString(1, recordingId);
            ps.executeUpdate();
        }
    }

    private static boolean rowError(Exception e) {
        if (!(e instanceof java.sql.SQLException)) return false;
        String state = ((java.sql.SQLException) e).getSQLState();
        return state != null && (state.startsWith("22") || state.startsWith("23"));
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (Exception ignored) {
            // Connection already failed.
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** Marks whose recording row is gone, in short chunks. */
    static int deleteOrphanMarks() {
        return deleteOrphans("recording_marks", "timeline orphan marks");
    }

    /** Spans whose recording row is gone; the row delete normally drops them first. */
    static int deleteOrphanSpans() {
        return deleteOrphans("recording_timeline", "timeline orphan spans");
    }

    private static int deleteOrphans(final String table, String op) {
        int deleted = 0;
        String after = "";
        while (true) {
            final String from = after;
            Object[] result = TimelineDb.sql(op, null, c -> {
                List<String> ids = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT recording_id"
                        + " FROM " + table + " WHERE recording_id > ?"
                        + " ORDER BY recording_id LIMIT 200")) {
                    ps.setString(1, from);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) ids.add(rs.getString(1));
                    }
                }
                if (ids.isEmpty()) return new Object[]{null, 0};
                Set<String> live = new HashSet<>();
                StringBuilder sql = new StringBuilder(
                        "SELECT recording_id FROM recordings WHERE recording_id IN (");
                for (int i = 0; i < ids.size(); i++) sql.append(i == 0 ? "?" : ", ?");
                sql.append(')');
                try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                    for (int i = 0; i < ids.size(); i++) ps.setString(i + 1, ids.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) live.add(rs.getString(1));
                    }
                }
                int n = 0;
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM " + table + " WHERE recording_id = ?")) {
                    for (String id : ids) {
                        if (live.contains(id)) continue;
                        ps.setString(1, id);
                        n += ps.executeUpdate();
                    }
                }
                return new Object[]{ids.get(ids.size() - 1), n};
            });
            if (result == null || result[0] == null) break;
            deleted += (Integer) result[1];
            after = (String) result[0];
            pause();
        }
        return deleted;
    }

    /** False when skipped: the wall clock is not trusted, or the store is down. */
    static boolean prune(final long now) {
        if (!TimelineJournal.wallClockSane(now)) return false;
        return TimelineDb.sql("timeline prune", Boolean.FALSE, c -> {
            TimelineJournal.prune(c, now);
            TimelineLedger.prune(c, now);
            return Boolean.TRUE;
        });
    }
}
