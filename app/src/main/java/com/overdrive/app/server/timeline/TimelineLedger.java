package com.overdrive.app.server.timeline;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Why recordings left the index, kept as reaped wall-time spans for the timeline. */
public final class TimelineLedger {

    static final long TTL_MS = 10L * 60_000L;
    static final int MAX_NOTES = 4096;
    static final long PRUNE_AGE_MS = 90L * 86_400_000L;
    static final String REASON_REMOVED = "removed";

    // Keyed by base name so storage path aliases of one clip share a note.
    private static final LinkedHashMap<String, Note> NOTES = new LinkedHashMap<>();

    private TimelineLedger() {}

    private static final class Note {
        final String reason;
        final long atMs;

        Note(String reason, long atMs) {
            this.reason = reason;
            this.atMs = atMs;
        }
    }

    /** O(1), no I/O; safe under storage locks. Call just before deleting the .mp4. */
    public static void noteDeleting(File mp4, String reason) {
        try {
            if (mp4 == null || reason == null) return;
            String name = mp4.getName();
            if (!name.endsWith(".mp4")) return;
            // Boot clock: a wall-clock step must not expire or pin every note.
            long now = RecordingSpanHints.elapsedMs();
            synchronized (NOTES) {
                NOTES.remove(name);
                NOTES.put(name, new Note(TimelineDb.clamp(reason, 16), now));
                Iterator<Map.Entry<String, Note>> it = NOTES.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, Note> eldest = it.next();
                    if (NOTES.size() > MAX_NOTES || now - eldest.getValue().atMs > TTL_MS) {
                        it.remove();
                    } else {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Advisory.
        }
    }

    static String takeReason(String fileName) {
        if (fileName == null) return null;
        synchronized (NOTES) {
            Note note = NOTES.remove(fileName);
            if (note == null) return null;
            return RecordingSpanHints.elapsedMs() - note.atMs <= TTL_MS ? note.reason : null;
        }
    }

    /**
     * Runs inside the index's row delete, before it: copies the row's span into
     * recordings_reaped and drops its recording_timeline row. Never throws.
     */
    public static void onRowRemoving(Connection c, String recordingId) {
        FrameService.forget(recordingId);
        try {
            if (c == null || recordingId == null) return;
            String absPath;
            String fileName;
            String type;
            Long start;
            Long end;
            try (PreparedStatement ps = c.prepareStatement("SELECT r.abs_path, r.filename, r.type,"
                    + " t.tl_start_ms, t.tl_end_ms FROM recordings r LEFT JOIN recording_timeline t"
                    + " ON t.recording_id = r.recording_id WHERE r.recording_id = ?")) {
                ps.setString(1, recordingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return;
                    absPath = rs.getString(1);
                    fileName = rs.getString(2);
                    type = rs.getString(3);
                    long s = rs.getLong(4);
                    start = rs.wasNull() ? null : s;
                    long e = rs.getLong(5);
                    end = rs.wasNull() ? null : e;
                }
            }
            String reason = takeReason(fileName);
            if (reason == null) reason = REASON_REMOVED;
            if (start == null) {
                long[] cached = TimelineEnricher.spanFor(absPath);
                if (cached != null) {
                    start = cached[0];
                    end = cached[1] >= 0 ? Long.valueOf(cached[1]) : null;
                }
            }
            if (start != null && !alreadyReaped(c, recordingId, start)) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO recordings_reaped"
                        + " (recording_id, type, start_ms, end_ms, reaped_at, reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, recordingId);
                    ps.setString(2, TimelineDb.clamp(type, 16));
                    ps.setLong(3, start);
                    if (end != null) ps.setLong(4, end);
                    else ps.setNull(4, java.sql.Types.BIGINT);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.setString(6, reason);
                    ps.executeUpdate();
                }
            }
            // If the row delete then fails, the missing span only makes the clip stale: it is redone.
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM recording_timeline WHERE recording_id = ?")) {
                ps.setString(1, recordingId);
                ps.executeUpdate();
            }
            // Marks stay until the orphan sweep: if the row delete fails, the clip keeps them.
        } catch (Throwable ignored) {
            // The row delete must proceed regardless.
        }
    }

    // The index may re-run its delete body after a reconnect.
    private static boolean alreadyReaped(Connection c, String recordingId, long start)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM recordings_reaped"
                + " WHERE start_ms = ? AND recording_id = ? LIMIT 1")) {
            ps.setLong(1, start);
            ps.setString(2, recordingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static int prune(Connection c, long now) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM recordings_reaped WHERE reaped_at < ?")) {
            ps.setLong(1, now - PRUNE_AGE_MS);
            return ps.executeUpdate();
        }
    }
}
