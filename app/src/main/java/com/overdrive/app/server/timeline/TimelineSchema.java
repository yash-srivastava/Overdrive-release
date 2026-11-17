package com.overdrive.app.server.timeline;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Timeline DDL on the recordings index store. Idempotent; never bumps the index schema version.
 * Spans live in recording_timeline keyed by recording_id, so the recordings table is never altered.
 * Dev-build tl_* columns on recordings are left in place and ignored: every query qualifies them.
 */
public final class TimelineSchema {

    private static volatile boolean ready;

    private TimelineSchema() {}

    /** True once {@link #ensure} completed on the current store. */
    public static boolean isReady() {
        return ready;
    }

    public static void ensure(Connection c) throws Exception {
        ready = false;
        try (Statement st = c.createStatement()) {
            // tl_fp is the row fingerprint the span was computed for; tl_failed marks it as unusable.
            st.execute("CREATE TABLE IF NOT EXISTS recording_timeline ("
                    + "recording_id VARCHAR(64) PRIMARY KEY, tl_start_ms BIGINT, tl_end_ms BIGINT,"
                    + " tl_src VARCHAR(8), tl_fp VARCHAR(96), tl_layout VARCHAR(12),"
                    + " tl_failed BOOLEAN DEFAULT FALSE NOT NULL)");
            // Not idx_rec_tl_start: dev stores already use that name on recordings(tl_start_ms).
            st.execute("CREATE INDEX IF NOT EXISTS idx_rtl_start ON recording_timeline(tl_start_ms)");
            st.execute("CREATE TABLE IF NOT EXISTS recording_marks ("
                    + "recording_id VARCHAR(64), t0 BIGINT, t1 BIGINT, kind VARCHAR(12),"
                    + " conf DOUBLE, cam VARCHAR(8), alert BOOLEAN)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_marks_t0 ON recording_marks(t0)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_marks_rec ON recording_marks(recording_id)");
            st.execute("CREATE TABLE IF NOT EXISTS timeline_journal ("
                    + "ts BIGINT, kind VARCHAR(16), state VARCHAR(16), reason VARCHAR(32),"
                    + " extra VARCHAR(128))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tl_journal_ts ON timeline_journal(ts)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tl_journal_kind_ts"
                    + " ON timeline_journal(kind, ts)");
            st.execute("CREATE TABLE IF NOT EXISTS recordings_reaped ("
                    + "recording_id VARCHAR(64), type VARCHAR(16), start_ms BIGINT, end_ms BIGINT,"
                    + " reaped_at BIGINT, reason VARCHAR(16))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_reaped_start ON recordings_reaped(start_ms)");
        }
        ready = true;
    }
}
