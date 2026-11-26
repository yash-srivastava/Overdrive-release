package com.overdrive.app.server.timeline;

import com.overdrive.app.parking.ParkingSession;
import com.overdrive.app.trips.TripRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Timeline read model over the recordings index: window, days, clip lookup, playback plan. */
public final class TimelineData {

    public static final class ClipRef {
        public String id;
        public String type;
        public String absPath;
        public String layout;
        public long start;
        /** null when the duration is unknown. */
        public Long end;
        public long sizeBytes;
        public long mp4Mtime;
        public boolean available;

        public ClipRef() {}

        public ClipRef(String id, String type, String absPath, String layout, long start, Long end,
                       long sizeBytes, long mp4Mtime, boolean available) {
            this.id = id;
            this.type = type;
            this.absPath = absPath;
            this.layout = layout;
            this.start = start;
            this.end = end;
            this.sizeBytes = sizeBytes;
            this.mp4Mtime = mp4Mtime;
            this.available = available;
        }

        public String fp() {
            return sizeBytes + "-" + mp4Mtime;
        }
    }

    public static final class PlanItem {
        public ClipRef clip;
        public long playFromWall;
        public long playToWall;

        public PlanItem() {}

        public PlanItem(ClipRef clip, long playFromWall, long playToWall) {
            this.clip = clip;
            this.playFromWall = playFromWall;
            this.playToWall = playToWall;
        }
    }

    interface Sources {
        List<ParkingSession> sessions(long from, long to);

        List<TripRecord> trips(long from, long to);
    }

    static volatile Sources sources = new DaemonSources();

    static final List<String> ALL_TYPES =
            Arrays.asList("normal", "sentry", "proximity", "replay", "oemDashcam");
    static final List<String> ALL_STORAGES = Arrays.asList("INTERNAL", "SD_CARD", "USB");
    static final List<String> SENTRY_STATES = Arrays.asList("armed", "surveillance_off",
            "vehicle_on_only", "suppressed_safe_zone", "suppressed_schedule", "pipeline_down",
            "lock_wait", "unknown");
    static final List<String> STATE_KINDS = Arrays.asList(TimelineJournal.KIND_SURVEILLANCE,
            TimelineJournal.KIND_POWER, TimelineJournal.KIND_DAEMON, TimelineJournal.KIND_STORAGE);
    static final long MAX_CLIP_MS = SpanRules.MAX_DURATION_MS;
    static final long END_UNKNOWN_OVERLAP_MS = 1000L;
    static final long JOURNAL_LOOKBACK_MS = TimelineJournal.PRUNE_AGE_MS;
    // A state still open at a past window's end is closed by rows up to this far past it.
    static final long JOURNAL_LOOKAHEAD_MS = 3L * 86_400_000L;
    static final long TRIP_LOOKBACK_MS = 6L * 3_600_000L;
    static final long OPEN_TRIP_MAX_AGE_MS = 24L * 3_600_000L;
    static final int MAX_MARKS = 4000;
    static final int MAX_ROAD = 4000;
    static final int MAX_SEGMENTS = 20_000;

    private TimelineData() {}

    // ------------------------------------------------------------------ rows

    static final class SegmentRow {
        String id;
        String fileName;
        String absPath;
        String type;
        int cameraId;
        long tsMs;
        long sizeBytes;
        long mp4Mtime;
        boolean available;
        String storage;
        String peakSeverity;
        int person;
        int vehicle;
        int bike;
        int animal;
        String classes;
        Double peakConfidence;
        String cameras;
        String place;
        String parkingSessionId;
        Long tlStart;
        Long tlEnd;
        String tlSrc;
        String tlLayout;
        boolean tlFailed;
        boolean watched;
        long start;
        Long end;
        String spanSrc;
        String layout;

        ClipRef toClip() {
            return new ClipRef(id, type, absPath, layout, start, end, sizeBytes, mp4Mtime, available);
        }
    }

    // Every column is qualified: dev-build stores still carry unused tl_* columns on recordings.
    private static final String BASE_COLUMNS = "r.recording_id, r.filename, r.abs_path, r.type,"
            + " r.camera_id, r.ts_ms, r.size_bytes, r.mp4_mtime, r.is_available, t.tl_start_ms,"
            + " t.tl_end_ms, t.tl_src, t.tl_layout, t.tl_failed";
    private static final String DETAIL_COLUMNS = ", r.storage, r.peak_severity, r.person_count,"
            + " r.vehicle_count, r.bike_count, r.animal_count, r.actor_classes, r.peak_confidence,"
            + " r.event_cameras, r.place_short, r.parking_session_id,"
            + " EXISTS (SELECT 1 FROM recordings_watched w"
            + " WHERE w.recording_id = r.recording_id"
            + " AND w.size_bytes = r.size_bytes"
            + " AND w.mp4_mtime = r.mp4_mtime) AS watched";
    // Spans first: the outer join keeps H2 on idx_rtl_start even with a type filter.
    private static final String FROM_SPANS = " FROM recording_timeline t LEFT JOIN recordings r"
            + " ON r.recording_id = t.recording_id";
    private static final String FROM_ROWS = " FROM recordings r LEFT JOIN recording_timeline t"
            + " ON t.recording_id = r.recording_id";
    static final List<String> EVENT_TYPES = Arrays.asList("sentry", "proximity", "replay");

    private static SegmentRow readRow(ResultSet rs, boolean details) throws Exception {
        SegmentRow r = new SegmentRow();
        int i = 1;
        r.id = rs.getString(i++);
        r.fileName = rs.getString(i++);
        r.absPath = rs.getString(i++);
        r.type = rs.getString(i++);
        r.cameraId = rs.getInt(i++);
        r.tsMs = rs.getLong(i++);
        r.sizeBytes = rs.getLong(i++);
        r.mp4Mtime = rs.getLong(i++);
        r.available = rs.getBoolean(i++);
        long s = rs.getLong(i++);
        r.tlStart = rs.wasNull() ? null : s;
        long e = rs.getLong(i++);
        r.tlEnd = rs.wasNull() ? null : e;
        r.tlSrc = rs.getString(i++);
        r.tlLayout = rs.getString(i++);
        r.tlFailed = rs.getBoolean(i++);
        if (details) {
            r.storage = rs.getString(i++);
            r.peakSeverity = rs.getString(i++);
            r.person = rs.getInt(i++);
            r.vehicle = rs.getInt(i++);
            r.bike = rs.getInt(i++);
            r.animal = rs.getInt(i++);
            r.classes = rs.getString(i++);
            double pc = rs.getDouble(i++);
            r.peakConfidence = rs.wasNull() ? null : pc;
            r.cameras = rs.getString(i++);
            r.place = rs.getString(i++);
            r.parkingSessionId = rs.getString(i++);
            r.watched = rs.getBoolean(i++);
        }
        return r;
    }

    /** Spans from recording_timeline when enriched; otherwise the no-I/O filename estimate (end unknown). */
    static void resolveSpan(SegmentRow r) {
        if (r.tlStart != null) {
            r.start = r.tlStart;
            r.end = r.tlEnd;
            r.spanSrc = r.tlSrc != null ? r.tlSrc : SpanRules.SRC_EST;
            r.layout = r.tlLayout;
        } else {
            SpanRules.Span span = SpanRules.compute(r.type, r.fileName, r.tsMs, -1L, null,
                    RecordingSpanHints.get(r.fileName));
            r.start = span.start;
            r.end = null;
            r.spanSrc = span.src;
            r.layout = "single".equals(SidecarTimeline.layoutFor(r.type, r.fileName, null))
                    ? "single" : null;
        }
    }

    static boolean overlaps(long start, Long end, long from, long to) {
        long effectiveEnd = end != null ? end : start + END_UNKNOWN_OVERLAP_MS;
        return start < to && effectiveEnd > from;
    }

    private static boolean filtering(Set<String> values, List<String> all) {
        return values != null && !values.isEmpty() && !values.containsAll(all);
    }

    /** Rows overlapping [from, to), start ASC; null when the index is unavailable. */
    static List<SegmentRow> querySegments(final long from, final long to, Set<String> types,
                                          final boolean details) {
        final List<String> typeList = filtering(types, ALL_TYPES)
                ? new ArrayList<>(types) : Collections.<String>emptyList();
        List<SegmentRow> rows = TimelineDb.sql("timeline segments", null, c -> {
            StringBuilder typeSql = new StringBuilder();
            if (!typeList.isEmpty()) {
                typeSql.append(" AND r.type IN (");
                for (int i = 0; i < typeList.size(); i++) typeSql.append(i == 0 ? "?" : ", ?");
                typeSql.append(')');
            }
            String select = "SELECT " + BASE_COLUMNS + (details ? DETAIL_COLUMNS : "");
            List<SegmentRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(select + FROM_SPANS
                    + " WHERE t.tl_start_ms >= ? AND t.tl_start_ms < ? AND r.recording_id IS NOT NULL"
                    + typeSql)) {
                ps.setLong(1, from - MAX_CLIP_MS);
                ps.setLong(2, to);
                for (int i = 0; i < typeList.size(); i++) ps.setString(3 + i, typeList.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && out.size() <= MAX_SEGMENTS) out.add(readRow(rs, details));
                }
            }
            // Types are filtered here: an IN list on idx_rec_type_ts scans every row of each type.
            try (PreparedStatement ps = c.prepareStatement(select + FROM_ROWS
                    + " WHERE r.ts_ms >= ? AND r.ts_ms < ? AND t.tl_start_ms IS NULL")) {
                ps.setLong(1, from - MAX_CLIP_MS);
                ps.setLong(2, to + MAX_CLIP_MS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && out.size() <= MAX_SEGMENTS) {
                        SegmentRow row = readRow(rs, details);
                        if (typeList.isEmpty() || typeList.contains(row.type)) out.add(row);
                    }
                }
            }
            return out;
        });
        if (rows == null) return null;
        List<SegmentRow> kept = new ArrayList<>(rows.size());
        for (SegmentRow r : rows) {
            resolveSpan(r);
            if (overlaps(r.start, r.end, from, to)) kept.add(r);
        }
        Collections.sort(kept, (a, b) -> a.start != b.start ? Long.compare(a.start, b.start)
                : a.id.compareTo(b.id));
        return kept;
    }

    /** Storage column, else classified from the path; the manager is resolved once per request. */
    private static final class StorageClassifier {
        private boolean resolved;
        private com.overdrive.app.storage.StorageManager manager;

        String of(SegmentRow r) {
            if (r.storage != null && !r.storage.isEmpty()) return r.storage;
            if (!resolved) {
                resolved = true;
                try {
                    manager = com.overdrive.app.storage.StorageManager.getInstance();
                } catch (Throwable t) {
                    manager = null;
                }
            }
            if (manager == null) return null;
            try {
                return manager.classifyStorageForPath(r.absPath);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ window

    /** Full /api/timeline body; null when the index is unavailable. */
    public static JSONObject windowJson(long from, long to, Set<String> types, Set<String> storages,
                                        Set<String> include) {
        try {
            if (to <= from) return null;
            List<SegmentRow> rows = querySegments(from, to, types, true);
            if (rows == null) return null;
            boolean storageFilter = filtering(storages, ALL_STORAGES);
            boolean truncated = false;
            JSONArray segments = new JSONArray();
            Set<String> ids = new HashSet<>();
            StorageClassifier classifier = new StorageClassifier();
            for (SegmentRow r : rows) {
                String storage = classifier.of(r);
                if (storageFilter && (storage == null || !storages.contains(storage))) continue;
                if (segments.length() >= MAX_SEGMENTS) {
                    truncated = true;
                    break;
                }
                segments.put(segmentJson(r, storage));
                ids.add(r.id);
            }
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            JSONObject body = new JSONObject();
            body.put("v", 1);
            body.put("from", from);
            body.put("to", to);
            body.put("now", now);
            body.put("tz", tz.getID());
            body.put("tzOffsetMin", tz.getOffset(now) / 60_000);
            body.put("segments", segments);

            JSONArray marks = new JSONArray();
            if (wants(include, "marks") && !ids.isEmpty()) truncated |= marks(from, to, ids, marks);
            body.put("marks", marks);

            JSONArray sessions = new JSONArray();
            if (wants(include, "sessions")) sessions(from, to, sessions);
            body.put("sessions", sessions);

            List<TripRecord> tripRecords = null;
            JSONArray trips = new JSONArray();
            if (wants(include, "trips") || wants(include, "states")) {
                tripRecords = trips(from, to, now);
            }
            if (wants(include, "trips")) {
                for (TripRecord t : tripRecords) trips.put(tripJson(t));
            }
            body.put("trips", trips);

            JSONArray states = new JSONArray();
            if (wants(include, "states")) states(from, to, tripRecords, states);
            body.put("states", states);

            JSONArray reaped = new JSONArray();
            if (wants(include, "reaped")) reaped(from, to, types, reaped);
            body.put("reaped", reaped);

            JSONArray road = new JSONArray();
            if (wants(include, "road")) truncated |= road(from, to, road);
            body.put("road", road);

            JSONArray live = new JSONArray();
            if (wants(include, "live")) live(to, types, live);
            body.put("live", live);

            body.put("truncated", truncated);
            if (!TimelineDb.warmupComplete()) body.put("warming", true);
            return body;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean wants(Set<String> include, String section) {
        return include == null || include.isEmpty() || include.contains(section);
    }

    static JSONObject segmentJson(SegmentRow r, String storage) throws Exception {
        String fp = r.sizeBytes + "-" + r.mp4Mtime;
        JSONObject s = new JSONObject();
        s.put("id", r.id);
        s.put("type", r.type);
        s.put("start", r.start);
        s.put("end", r.end != null ? (Object) r.end : JSONObject.NULL);
        s.put("spanSrc", r.spanSrc);
        s.put("available", r.available);
        s.put("storage", storage != null ? storage : JSONObject.NULL);
        s.put("cameraId", r.cameraId);
        s.put("layout", r.layout != null ? r.layout : JSONObject.NULL);
        s.put("sizeBytes", r.sizeBytes);
        s.put("mp4Mtime", r.mp4Mtime);
        s.put("fp", fp);
        s.put("videoUrl", "/video/id/" + r.id);
        s.put("thumbUrl", "/thumb/id/" + r.id);
        s.put("eventUrl", "/api/events/id/" + r.id);
        s.put("hls", "/hls/id/" + r.id + "/" + fp + "/main.m3u8");
        String peak = r.peakSeverity != null ? r.peakSeverity.toUpperCase(Locale.US) : null;
        s.put("peakSeverity", "ALERT".equals(peak) || "CRITICAL".equals(peak)
                ? peak : JSONObject.NULL);
        JSONArray classes = new JSONArray();
        if (r.classes != null) {
            for (String c : r.classes.split(",")) {
                if (!c.isEmpty()) classes.put(c);
            }
        }
        s.put("classes", classes);
        JSONObject counts = new JSONObject();
        counts.put("person", r.person);
        counts.put("vehicle", r.vehicle);
        counts.put("bike", r.bike);
        counts.put("animal", r.animal);
        s.put("counts", counts);
        s.put("peakConfidence", r.peakConfidence != null
                ? (Object) (Math.round(r.peakConfidence * 100) / 100.0) : JSONObject.NULL);
        s.put("cameras", r.cameras != null && !r.cameras.isEmpty() ? r.cameras : JSONObject.NULL);
        s.put("place", r.place != null && !r.place.isEmpty() ? r.place : JSONObject.NULL);
        s.put("parkingSessionId", r.parkingSessionId != null && !r.parkingSessionId.isEmpty()
                ? r.parkingSessionId : JSONObject.NULL);
        s.put("watched", r.watched);
        // Not analysed yet: the length is unknown for now, not because the clip is damaged.
        s.put("indexing", r.tlStart == null && !r.tlFailed);
        return s;
    }

    /** @return true when capped */
    private static boolean marks(final long from, final long to, final Set<String> ids,
                                 JSONArray out) throws Exception {
        List<Object[]> rows = TimelineDb.sql("timeline marks", null, c -> {
            List<Object[]> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT recording_id, t0, t1, kind,"
                    + " conf, cam, alert FROM recording_marks WHERE t0 >= ? AND t0 < ? AND t1 >= ?"
                    + " ORDER BY t0")) {
                ps.setLong(1, from - MAX_CLIP_MS);
                ps.setLong(2, to);
                ps.setLong(3, from);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String rec = rs.getString(1);
                        if (!ids.contains(rec)) continue;
                        if (list.size() > MAX_MARKS) break;
                        double conf = rs.getDouble(5);
                        Double confValue = rs.wasNull() ? null : conf;
                        list.add(new Object[]{rec, rs.getLong(2), rs.getLong(3), rs.getString(4),
                                confValue, rs.getString(6), rs.getBoolean(7)});
                    }
                }
            }
            return list;
        });
        if (rows == null) return false;
        boolean capped = rows.size() > MAX_MARKS;
        for (int i = 0; i < rows.size() && i < MAX_MARKS; i++) {
            Object[] m = rows.get(i);
            JSONObject o = new JSONObject();
            o.put("rec", m[0]);
            o.put("t0", m[1]);
            o.put("t1", m[2]);
            o.put("kind", m[3] != null ? m[3] : JSONObject.NULL);
            o.put("conf", m[4] != null ? m[4] : JSONObject.NULL);
            o.put("cam", m[5] != null ? m[5] : JSONObject.NULL);
            o.put("alert", m[6]);
            out.put(o);
        }
        return capped;
    }

    private static void sessions(long from, long to, JSONArray out) throws Exception {
        List<ParkingSession> list = new ArrayList<>();
        try {
            List<ParkingSession> fetched = sources.sessions(from, to);
            if (fetched != null) list.addAll(fetched);
        } catch (Throwable ignored) {
            // Parking feature off or its store is down.
        }
        Collections.sort(list, Comparator.comparingLong(s -> s.startedMs));
        for (ParkingSession s : list) {
            if (s == null || s.sessionId == null) continue;
            Long end = s.endedMs > 0 ? s.endedMs : null;
            if (!overlaps(s.startedMs, end != null ? end : Long.valueOf(Long.MAX_VALUE), from, to)) {
                continue;
            }
            out.put(sessionJson(s));
        }
    }

    static JSONObject sessionJson(ParkingSession s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", s.sessionId);
        o.put("start", s.startedMs);
        o.put("end", s.endedMs > 0 ? (Object) s.endedMs : JSONObject.NULL);
        String place = s.placeShort != null && !s.placeShort.isEmpty() ? s.placeShort
                : (s.placeDisplay != null && !s.placeDisplay.isEmpty() ? s.placeDisplay : null);
        o.put("place", place != null ? place : JSONObject.NULL);
        o.put("safeZone", s.safeZone != null && !s.safeZone.isEmpty() ? s.safeZone : JSONObject.NULL);
        String sentry = s.sentryState != null ? s.sentryState.toLowerCase(Locale.US) : "unknown";
        o.put("sentry", SENTRY_STATES.contains(sentry) ? sentry : "unknown");
        return o;
    }

    /** Finished trips overlapping the window plus the open drive, start ASC, deduped. */
    static List<TripRecord> trips(long from, long to, long now) {
        List<TripRecord> fetched = null;
        try {
            fetched = sources.trips(from, to);
        } catch (Throwable ignored) {
            // Trip analytics not running.
        }
        Map<Long, TripRecord> byId = new LinkedHashMap<>();
        if (fetched != null) {
            for (TripRecord t : fetched) {
                if (t == null || t.startTime <= 0) continue;
                boolean open = t.endTime <= 0;
                if (open && now - t.startTime > OPEN_TRIP_MAX_AGE_MS) continue;
                Long end = open ? null : t.endTime;
                if (!overlaps(t.startTime, end != null ? end : Long.valueOf(Long.MAX_VALUE), from, to)) {
                    continue;
                }
                byId.put(t.id, t);
            }
        }
        List<TripRecord> out = new ArrayList<>(byId.values());
        Collections.sort(out, Comparator.comparingLong(t -> t.startTime));
        return out;
    }

    static JSONObject tripJson(TripRecord t) throws Exception {
        JSONObject o = new JSONObject();
        boolean open = t.endTime <= 0;
        o.put("id", t.id);
        o.put("start", t.startTime);
        o.put("end", open ? JSONObject.NULL : (Object) t.endTime);
        boolean distanceKnown = !open && !Double.isNaN(t.distanceKm) && t.distanceKm >= 0;
        o.put("distanceKm", distanceKnown ? (Object) t.distanceKm : JSONObject.NULL);
        return o;
    }

    private static void states(final long from, final long to, List<TripRecord> trips,
                               JSONArray out) throws Exception {
        List<TimelineJournal.Entry> rows = TimelineDb.sql("timeline states", null, c -> {
            List<TimelineJournal.Entry> list = new ArrayList<>();
            // One equality per kind keeps H2 on an index range; IN (...) scans every road row.
            try (PreparedStatement ps = c.prepareStatement("SELECT ts, kind, state, reason"
                    + " FROM timeline_journal WHERE kind = ? AND ts >= ? AND ts < ? ORDER BY ts")) {
                for (String kind : STATE_KINDS) {
                    ps.setString(1, kind);
                    ps.setLong(2, from - JOURNAL_LOOKBACK_MS);
                    ps.setLong(3, to + JOURNAL_LOOKAHEAD_MS);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            list.add(new TimelineJournal.Entry(rs.getLong(1), rs.getString(2),
                                    rs.getString(3), rs.getString(4), null));
                        }
                    }
                }
            }
            return list;
        });
        if (rows == null) return;
        List<Long> tripStarts = new ArrayList<>();
        if (trips != null) {
            for (TripRecord t : trips) tripStarts.add(t.startTime);
        }
        for (TimelineJournal.Interval iv : TimelineJournal.intervals(rows, tripStarts)) {
            if (!overlaps(iv.start, iv.end != null ? iv.end : Long.valueOf(Long.MAX_VALUE), from, to)) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("start", iv.start);
            o.put("end", iv.end != null ? (Object) iv.end : JSONObject.NULL);
            o.put("kind", iv.kind != null ? iv.kind : JSONObject.NULL);
            o.put("state", iv.state != null ? iv.state : JSONObject.NULL);
            o.put("reason", iv.reason != null ? iv.reason : JSONObject.NULL);
            out.put(o);
        }
    }

    private static void reaped(final long from, final long to, Set<String> types, JSONArray out)
            throws Exception {
        final boolean typeFilter = filtering(types, ALL_TYPES);
        List<Object[]> rows = TimelineDb.sql("timeline reaped", null, c -> {
            List<Object[]> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT start_ms, end_ms, type, reason"
                    + " FROM recordings_reaped WHERE start_ms >= ? AND start_ms < ?"
                    + " AND NOT EXISTS (SELECT 1 FROM recordings r"
                    + " WHERE r.recording_id = recordings_reaped.recording_id)"
                    + " ORDER BY start_ms")) {
                ps.setLong(1, from - MAX_CLIP_MS);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long start = rs.getLong(1);
                        long end = rs.getLong(2);
                        Long endValue = rs.wasNull() ? null : end;
                        list.add(new Object[]{start, endValue, rs.getString(3), rs.getString(4)});
                    }
                }
            }
            return list;
        });
        if (rows == null) return;
        for (Object[] r : rows) {
            long start = (Long) r[0];
            Long end = (Long) r[1];
            String type = (String) r[2];
            if (typeFilter && (type == null || !types.contains(type))) continue;
            if (!overlaps(start, end, from, to)) continue;
            JSONObject o = new JSONObject();
            o.put("start", start);
            o.put("end", end != null ? end : start + END_UNKNOWN_OVERLAP_MS);
            o.put("type", type != null ? type : JSONObject.NULL);
            o.put("reason", r[3] != null ? r[3] : TimelineLedger.REASON_REMOVED);
            out.put(o);
        }
    }

    /** @return true when capped */
    private static boolean road(final long from, final long to, JSONArray out) throws Exception {
        List<Object[]> rows = TimelineDb.sql("timeline road", null, c -> {
            List<Object[]> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT ts, state, reason"
                    + " FROM timeline_journal WHERE kind = 'road' AND ts >= ? AND ts < ?"
                    + " ORDER BY ts LIMIT " + (MAX_ROAD + 1))) {
                ps.setLong(1, from);
                ps.setLong(2, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)});
                }
            }
            return list;
        });
        if (rows == null) return false;
        for (int i = 0; i < rows.size() && i < MAX_ROAD; i++) {
            Object[] r = rows.get(i);
            JSONObject o = new JSONObject();
            o.put("t", r[0]);
            o.put("kind", r[1] != null ? r[1] : JSONObject.NULL);
            o.put("severity", r[2] != null ? r[2] : JSONObject.NULL);
            out.put(o);
        }
        return rows.size() > MAX_ROAD;
    }

    private static void live(long to, Set<String> types, JSONArray out) throws Exception {
        boolean typeFilter = filtering(types, ALL_TYPES);
        for (RecordingSpanHints.Live l : RecordingSpanHints.openSegments()) {
            if (l.since >= to) continue;
            if (typeFilter && !types.contains(l.type)) continue;
            JSONObject o = new JSONObject();
            o.put("type", l.type);
            o.put("since", l.since);
            out.put(o);
        }
    }

    // ------------------------------------------------------------------ days

    /** Per local day of segment start: covered footage (overlaps counted once), events, alerts. */
    public static JSONObject daysJson(long from, long to) {
        try {
            if (to <= from) return null;
            final long qFrom = from;
            final long qTo = to;
            List<Object[]> rows = TimelineDb.sql("timeline days", null, c -> {
                List<Object[]> list = new ArrayList<>();
                String select = "SELECT r.type, r.filename, r.ts_ms, t.tl_start_ms, t.tl_end_ms,"
                        + " r.peak_severity";
                try (PreparedStatement ps = c.prepareStatement(select + FROM_SPANS
                        + " WHERE t.tl_start_ms >= ? AND t.tl_start_ms < ?"
                        + " AND r.recording_id IS NOT NULL")) {
                    ps.setLong(1, qFrom);
                    ps.setLong(2, qTo);
                    readDayRows(ps, list);
                }
                // Without a span a row adds no footage, only event counts: one index range per event type.
                try (PreparedStatement ps = c.prepareStatement(select + FROM_ROWS
                        + " WHERE r.type = ? AND r.ts_ms >= ? AND r.ts_ms < ? AND t.tl_start_ms IS NULL")) {
                    for (String type : EVENT_TYPES) {
                        ps.setString(1, type);
                        ps.setLong(2, qFrom - MAX_CLIP_MS);
                        ps.setLong(3, qTo + MAX_CLIP_MS);
                        readDayRows(ps, list);
                    }
                }
                return list;
            });
            if (rows == null) return null;

            TimeZone tz = TimeZone.getDefault();
            SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            dayFormat.setTimeZone(tz);
            Map<String, List<long[]>> footage = new HashMap<>();
            Map<String, int[]> counts = new HashMap<>();
            for (Object[] r : rows) {
                String type = (String) r[0];
                String fileName = (String) r[1];
                long ts = (Long) r[2];
                Long tlStart = (Long) r[3];
                Long tlEnd = (Long) r[4];
                long start = tlStart != null ? tlStart
                        : SpanRules.compute(type, fileName, ts, -1L, null,
                                RecordingSpanHints.get(fileName)).start;
                Long end = tlStart != null ? tlEnd : null;
                if (start < from || start >= to) continue;
                String day = dayFormat.format(new Date(start));
                if (end != null && end > start) {
                    List<long[]> spans = footage.get(day);
                    if (spans == null) footage.put(day, spans = new ArrayList<>());
                    spans.add(new long[]{start, end});
                }
                if (EVENT_TYPES.contains(type)) {
                    int[] n = counts.get(day);
                    if (n == null) counts.put(day, n = new int[2]);
                    n[0]++;
                    String peak = r[5] != null ? ((String) r[5]).toUpperCase(Locale.US) : null;
                    if ("ALERT".equals(peak) || "CRITICAL".equals(peak)) n[1]++;
                }
            }

            JSONArray days = new JSONArray();
            Calendar cal = Calendar.getInstance(tz, Locale.US);
            cal.setTimeInMillis(from);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            for (int guard = 0; cal.getTimeInMillis() < to && guard < 400; guard++) {
                String day = dayFormat.format(cal.getTime());
                int[] n = counts.get(day);
                JSONObject o = new JSONObject();
                o.put("day", day);
                o.put("footageMs", union(footage.get(day)));
                o.put("events", n != null ? n[0] : 0);
                o.put("alerts", n != null ? n[1] : 0);
                days.put(o);
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
            JSONObject body = new JSONObject();
            body.put("v", 1);
            body.put("tz", tz.getID());
            body.put("days", days);
            if (!TimelineDb.warmupComplete()) body.put("warming", true);
            return body;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void readDayRows(PreparedStatement ps, List<Object[]> list) throws Exception {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long s = rs.getLong(4);
                Long tlStart = rs.wasNull() ? null : s;
                long e = rs.getLong(5);
                Long tlEnd = rs.wasNull() ? null : e;
                list.add(new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), tlStart,
                        tlEnd, rs.getString(6)});
            }
        }
    }

    static long union(List<long[]> spans) {
        if (spans == null || spans.isEmpty()) return 0L;
        List<long[]> sorted = new ArrayList<>(spans);
        Collections.sort(sorted, Comparator.comparingLong(a -> a[0]));
        long total = 0L;
        long curStart = sorted.get(0)[0];
        long curEnd = sorted.get(0)[1];
        for (int i = 1; i < sorted.size(); i++) {
            long[] s = sorted.get(i);
            if (s[0] <= curEnd) {
                curEnd = Math.max(curEnd, s[1]);
            } else {
                total += curEnd - curStart;
                curStart = s[0];
                curEnd = s[1];
            }
        }
        return total + (curEnd - curStart);
    }

    // ------------------------------------------------------------------ clips + plan

    /** Clip by recording id; null when unknown. Throws when the index is unavailable. */
    public static ClipRef clip(final String recordingId) {
        if (recordingId == null || recordingId.isEmpty()) return null;
        final SegmentRow missing = new SegmentRow();
        SegmentRow row = TimelineDb.sql("timeline clip", null, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + BASE_COLUMNS + FROM_ROWS
                    + " WHERE r.recording_id = ?")) {
                ps.setString(1, recordingId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? readRow(rs, false) : missing;
                }
            }
        });
        if (row == null) throw new IllegalStateException("recordings index unavailable");
        if (row == missing) return null;
        resolveSpan(row);
        return row.toClip();
    }

    /** Playback plan over [from, to); throws when the index is unavailable. */
    public static List<PlanItem> playbackPlan(long from, long to, Set<String> types, String prefer) {
        if (to <= from) return new ArrayList<>();
        // Planned beyond each edge, then cut: edge pieces get the same seam and sliver rules as the head unit's
        // (a sliver needs its neighbours, which can sit a few seams away).
        long planFrom = from - 4 * TimelinePlan.SEAM_MS;
        long planTo = to + 4 * TimelinePlan.SEAM_MS;
        List<SegmentRow> rows = querySegments(planFrom, planTo, types, false);
        if (rows == null) throw new IllegalStateException("recordings index unavailable");
        List<ClipRef> clips = new ArrayList<>(rows.size());
        for (SegmentRow r : rows) clips.add(r.toClip());
        List<PlanItem> planned = TimelinePlan.build(clips, planFrom, planTo, prefer);
        List<PlanItem> out = new ArrayList<>(planned.size());
        for (PlanItem p : planned) {
            long a = Math.max(p.playFromWall, from);
            long b = Math.min(p.playToWall, to);
            if (b > a) out.add(new PlanItem(p.clip, a, b));
        }
        return out;
    }

    // ------------------------------------------------------------------ sources

    static final class DaemonSources implements Sources {
        @Override
        public List<ParkingSession> sessions(long from, long to) {
            try {
                com.overdrive.app.parking.ParkingController controller =
                        com.overdrive.app.daemon.CameraDaemon.getParkingController();
                com.overdrive.app.parking.ParkingStore store =
                        controller != null ? controller.store() : null;
                if (store == null) return Collections.emptyList();
                List<ParkingSession> list = store.listSessions(from, to, 500, 0);
                return list != null ? list : Collections.<ParkingSession>emptyList();
            } catch (Throwable t) {
                return Collections.emptyList();
            }
        }

        @Override
        public List<TripRecord> trips(long from, long to) {
            try {
                com.overdrive.app.trips.TripAnalyticsManager manager =
                        com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
                com.overdrive.app.trips.TripDatabase db =
                        manager != null ? manager.getDatabase() : null;
                if (db == null) return Collections.emptyList();
                List<TripRecord> out = new ArrayList<>();
                List<TripRecord> finished = db.getTripsBetweenWithoutMoments(
                        from - TRIP_LOOKBACK_MS, to, 500);
                if (finished != null) out.addAll(finished);
                List<TripRecord> open = db.getUnfinalizedTrips();
                if (open != null) out.addAll(open);
                return out;
            } catch (Throwable t) {
                return Collections.emptyList();
            }
        }
    }
}
