package com.overdrive.app.server.timeline;

/** Clip wall-clock span: writer hint, then sidecar anchor, then the filename rule. */
public final class SpanRules {

    public static final String SRC_WRITER = "writer";
    public static final String SRC_SIDECAR = "sidecar";
    public static final String SRC_PROBE = "probe";
    public static final String SRC_EST = "est";

    static final long MAX_DURATION_MS = 6L * 3_600_000L;
    // A hint or anchor further than this from the filename stamp is not this clip's.
    static final long MAX_ANCHOR_SKEW_MS = 6L * 3_600_000L;
    // 2024-01-01 UTC. Earlier stamps come from a head unit that booted before its clock was set.
    static final long SANE_WALL_MS = 1_704_067_200_000L;

    enum Kind { NORMAL, OEM, OEM_PARKED, EVENT, PROXIMITY, REPLAY, UNKNOWN }

    private SpanRules() {}

    public static final class Span {
        public final long start;
        /** null when the duration is unknown. */
        public final Long end;
        public final String src;

        Span(long start, Long end, String src) {
            this.start = start;
            this.end = end;
            this.src = src;
        }

        @Override
        public String toString() {
            return "Span{" + start + ".." + end + " " + src + "}";
        }
    }

    static Kind kindOf(String fileName, String type) {
        if (fileName != null) {
            if (fileName.startsWith("dvr_p_")) return Kind.OEM_PARKED;
            if (fileName.startsWith("dvr_")) return Kind.OEM;
            if (fileName.startsWith("event_")) return Kind.EVENT;
            if (fileName.startsWith("proximity_")) return Kind.PROXIMITY;
            if (fileName.startsWith("replay_")) return Kind.REPLAY;
            if (fileName.startsWith("cam")) return Kind.NORMAL;
            return Kind.UNKNOWN;
        }
        if ("sentry".equals(type)) return Kind.EVENT;
        if ("proximity".equals(type)) return Kind.PROXIMITY;
        if ("replay".equals(type)) return Kind.REPLAY;
        if ("oemDashcam".equals(type)) return Kind.OEM;
        return Kind.NORMAL;
    }

    /** Duration within (0, 6 h], else -1. */
    static long usableDuration(long ms) {
        return ms > 0 && ms <= MAX_DURATION_MS ? ms : -1L;
    }

    /**
     * @param filenameTsMs the index ts_ms (filename stamp; file mtime for unknown names)
     * @param mp4DurMs     {@link Mp4Header#durationMs} result, -1 when unknown
     * @param writerHintStartMs wall time of the first frame recorded by the writer, or -1
     */
    public static Span compute(String type, String fileName, long filenameTsMs, long mp4DurMs,
                               SidecarTimeline.Facts sidecar, long writerHintStartMs) {
        return compute(type, fileName, filenameTsMs, mp4DurMs, sidecar, writerHintStartMs, -1L);
    }

    /** @param fileMtimeMs last write of the file, used when the filename stamp predates a set clock */
    public static Span compute(String type, String fileName, long filenameTsMs, long mp4DurMs,
                               SidecarTimeline.Facts sidecar, long writerHintStartMs,
                               long fileMtimeMs) {
        long headerDur = usableDuration(mp4DurMs);
        long sidecarDur = sidecar != null ? usableDuration(sidecar.durationMs) : -1L;
        long duration = headerDur > 0 ? headerDur : sidecarDur;
        boolean stampWrong = filenameTsMs > 0 && filenameTsMs < SANE_WALL_MS;

        long start;
        String src;
        if (writerHintStartMs > 0 && (stampWrong ? writerHintStartMs >= SANE_WALL_MS
                : near(writerHintStartMs, filenameTsMs))) {
            start = writerHintStartMs;
            src = SRC_WRITER;
        } else if (sidecar != null && sidecar.anchorMs > 0 && (stampWrong
                ? sidecar.anchorMs >= SANE_WALL_MS && agreesWithMtime(sidecar.anchorMs, fileMtimeMs, duration)
                : near(sidecar.anchorMs, filenameTsMs))) {
            start = sidecar.anchorMs;
            src = SRC_SIDECAR;
        } else if (stampWrong && duration > 0 && fileMtimeMs >= SANE_WALL_MS + duration) {
            // The clock was set before the file was finalized: its last write marks the end.
            start = fileMtimeMs - duration;
            src = SRC_EST;
        } else {
            start = filenameStart(kindOf(fileName, type), filenameTsMs, duration, sidecar);
            src = headerDur > 0 ? SRC_PROBE : SRC_EST;
        }
        return new Span(start, duration > 0 ? Long.valueOf(start + duration) : null, src);
    }

    private static long filenameStart(Kind kind, long ts, long duration,
                                      SidecarTimeline.Facts sidecar) {
        switch (kind) {
            case EVENT:
            case PROXIMITY: {
                long pre = sidecar != null ? sidecar.preRecordMs : -1L;
                return pre >= 0 && pre <= MAX_DURATION_MS ? ts - pre : ts;
            }
            case REPLAY:
            case UNKNOWN:
                // replay_ is stamped at save time; unknown names carry the file mtime.
                return duration > 0 ? ts - duration : ts;
            case NORMAL:
            case OEM:
            case OEM_PARKED:
            default:
                return ts;
        }
    }

    /** A sidecar median can blend detections from before and after the clock was set: check it against the file's end. */
    private static boolean agreesWithMtime(long anchorMs, long fileMtimeMs, long duration) {
        if (duration <= 0 || fileMtimeMs < SANE_WALL_MS + duration) return true;
        return Math.abs(anchorMs - (fileMtimeMs - duration)) <= MAX_ANCHOR_SKEW_MS;
    }

    private static boolean near(long a, long b) {
        return b <= 0 || Math.abs(a - b) <= MAX_ANCHOR_SKEW_MS;
    }
}
