package com.overdrive.app.server.timeline;

import com.overdrive.app.server.RecordingsIndex;

/** Short critical sections on the recordings index connection. */
final class TimelineDb {

    private TimelineDb() {}

    /** {@code fail} when the index is unavailable or the body failed; the body may run twice. */
    static <T> T sql(String op, T fail, RecordingsIndex.TimelineSqlBody<T> body) {
        try {
            return RecordingsIndex.getInstance().timelineSql(op, fail, body);
        } catch (Throwable t) {
            return fail;
        }
    }

    static boolean warmupComplete() {
        try {
            return RecordingsIndex.getInstance().warmupState().complete;
        } catch (Throwable t) {
            return false;
        }
    }

    static String clamp(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
