package com.overdrive.app.parking;

import com.overdrive.app.logging.DaemonLogger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable H2 store for parking sessions and their neighbours.
 *
 * <p>Own store file (not the rebuildable recordings index) so a session
 * survives index rebuilds, and opened LAZILY by {@link ParkingController#start}
 * — a disabled feature never opens a database (the same posture the other
 * daemon stores take: one store per feature, {@code AUTO_COMPACT_FILL_RATE=50}
 * for idle CPU, {@code DB_CLOSE_ON_EXIT=FALSE} so the JVM shutdown hook can't
 * race the daemon's explicit close, {@code FILE_LOCK=SOCKET} for cross-process
 * safety).
 *
 * <p>Single-writer (the parking worker thread); the HTTP handler reads through
 * the same synchronized methods. The JDBC URL is injectable so JVM tests can
 * run against {@code jdbc:h2:mem:}.
 */
public final class ParkingStore {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ParkingStore");

    public static final String DEFAULT_DB_PATH = com.overdrive.app.util.DaemonStorage.rebase("/data/local/tmp/overdrive_parking_h2");

    public static String defaultJdbcUrl() {
        return "jdbc:h2:file:" + DEFAULT_DB_PATH
                + ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE"
                + ";AUTO_COMPACT_FILL_RATE=50";
    }

    private final String jdbcUrl;
    private volatile Connection connection;

    public ParkingStore() { this(defaultJdbcUrl()); }

    public ParkingStore(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }

    // ==================== LIFECYCLE ====================

    public synchronized boolean open() {
        if (isOpen()) return true;
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            logger.error("H2 driver not found", e);
            return false;
        }
        try {
            connection = DriverManager.getConnection(jdbcUrl, "sa", "");
            try (Statement st = connection.createStatement()) {
                st.execute("SET CACHE_SIZE 2048");
            }
            createTables();
            return true;
        } catch (Exception e) {
            logger.error("Parking store open failed: " + e.getMessage());
            closeQuietly();
            return false;
        }
    }

    public synchronized void close() {
        closeQuietly();
    }

    public synchronized boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (Exception e) {
            return false;
        }
    }

    private void closeQuietly() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    private Connection conn() throws Exception {
        Connection c = connection;
        if (c == null || c.isClosed()) {
            throw new IllegalStateException("parking store not open");
        }
        return c;
    }

    private void createTables() throws Exception {
        try (Statement st = conn().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS parking_sessions ("
                    + "session_id VARCHAR(48) PRIMARY KEY,"
                    + "started_ms BIGINT NOT NULL,"
                    + "ended_ms BIGINT DEFAULT 0,"
                    + "transition_gen BIGINT DEFAULT 0,"
                    + "end_trigger VARCHAR(16),"
                    + "lat DOUBLE,"
                    + "lng DOUBLE,"
                    + "accuracy_m REAL DEFAULT 0,"
                    + "fix_age_ms BIGINT DEFAULT -1,"
                    + "fix_from_cache BOOLEAN DEFAULT FALSE,"
                    + "gps_quality VARCHAR(16),"
                    + "place_short VARCHAR(128),"
                    + "place_display VARCHAR(256),"
                    + "place_source VARCHAR(32),"
                    + "safe_zone VARCHAR(64),"
                    + "sentry_state VARCHAR(32),"
                    + "arrived_snapshot_ms BIGINT DEFAULT 0,"
                    + "arrived_snapshot_ok BOOLEAN DEFAULT FALSE,"
                    + "returned_snapshot_ms BIGINT DEFAULT 0,"
                    + "returned_snapshot_ok BOOLEAN DEFAULT FALSE,"
                    + "rectify_strength INT DEFAULT 0,"
                    + "signage_json CLOB,"
                    + "signage_state VARCHAR(16),"
                    + "notified_started BOOLEAN DEFAULT FALSE,"
                    + "notified_ended BOOLEAN DEFAULT FALSE,"
                    + "event_count INT DEFAULT 0,"
                    + "neighbour_count INT DEFAULT 0,"
                    + "created_ms BIGINT DEFAULT 0,"
                    + "start_soc_pct DOUBLE,"
                    + "end_soc_pct DOUBLE,"
                    + "charged_while_parked BOOLEAN DEFAULT FALSE,"
                    + "energy_est_kwh DOUBLE,"
                    + "start_remain_kwh DOUBLE,"
                    + "end_remain_kwh DOUBLE"
                    + ")");
            // The energy bookends were added after the first store files existed.
            // IF NOT EXISTS makes these no-ops on a fresh table (and keeps the
            // migration path exercised by every test run against :mem:).
            st.execute("ALTER TABLE parking_sessions ADD COLUMN IF NOT EXISTS start_soc_pct DOUBLE");
            st.execute("ALTER TABLE parking_sessions ADD COLUMN IF NOT EXISTS end_soc_pct DOUBLE");
            st.execute("ALTER TABLE parking_sessions ADD COLUMN IF NOT EXISTS charged_while_parked BOOLEAN DEFAULT FALSE");
            st.execute("ALTER TABLE parking_sessions ADD COLUMN IF NOT EXISTS energy_est_kwh DOUBLE");
            st.execute("ALTER TABLE parking_sessions ADD COLUMN IF NOT EXISTS start_remain_kwh DOUBLE");
            st.execute("ALTER TABLE parking_sessions ADD COLUMN IF NOT EXISTS end_remain_kwh DOUBLE");
            st.execute("CREATE INDEX IF NOT EXISTS idx_parking_sessions_started"
                    + " ON parking_sessions(started_ms DESC)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_parking_sessions_ended"
                    + " ON parking_sessions(ended_ms)");

            st.execute("CREATE TABLE IF NOT EXISTS parking_neighbours ("
                    + "id IDENTITY PRIMARY KEY,"
                    + "session_id VARCHAR(48) NOT NULL,"
                    + "neighbour_key VARCHAR(64),"
                    + "side INT NOT NULL,"
                    + "kind VARCHAR(16) NOT NULL,"
                    + "class_group VARCHAR(16),"
                    + "status VARCHAR(24),"
                    + "confirmed BOOLEAN DEFAULT FALSE,"
                    + "first_seen_ms BIGINT DEFAULT 0,"
                    + "arrived_ms BIGINT DEFAULT 0,"
                    + "departed_ms BIGINT DEFAULT 0,"
                    + "last_seen_ms BIGINT DEFAULT 0,"
                    + "cx REAL DEFAULT 0, cy REAL DEFAULT 0, w REAL DEFAULT 0, h REAL DEFAULT 0,"
                    + "proximity VARCHAR(16),"
                    + "arrival_event VARCHAR(256),"
                    + "departure_event VARCHAR(256),"
                    + "actor_ids VARCHAR(256),"
                    + "frames_json CLOB,"
                    + "updated_ms BIGINT DEFAULT 0"
                    + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_parking_neighbours_session"
                    + " ON parking_neighbours(session_id)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_parking_neighbours_key"
                    + " ON parking_neighbours(session_id, neighbour_key)");
        }
    }

    // ==================== SESSIONS ====================

    public synchronized boolean insertSession(ParkingSession s) {
        String sql = "INSERT INTO parking_sessions (session_id, started_ms, ended_ms,"
                + " transition_gen, end_trigger, lat, lng, accuracy_m, fix_age_ms,"
                + " fix_from_cache, gps_quality, place_short, place_display, place_source,"
                + " safe_zone, sentry_state, arrived_snapshot_ms, arrived_snapshot_ok,"
                + " returned_snapshot_ms, returned_snapshot_ok, rectify_strength,"
                + " signage_json, signage_state, notified_started, notified_ended,"
                + " event_count, neighbour_count, created_ms, start_soc_pct,"
                + " end_soc_pct, charged_while_parked, energy_est_kwh,"
                + " start_remain_kwh, end_remain_kwh) VALUES ("
                + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            bindSession(ps, s, 1);
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            logger.warn("insertSession failed: " + e.getMessage());
            return false;
        }
    }

    public synchronized boolean updateSession(ParkingSession s) {
        String sql = "UPDATE parking_sessions SET started_ms=?, ended_ms=?, transition_gen=?,"
                + " end_trigger=?, lat=?, lng=?, accuracy_m=?, fix_age_ms=?, fix_from_cache=?,"
                + " gps_quality=?, place_short=?, place_display=?, place_source=?, safe_zone=?,"
                + " sentry_state=?, arrived_snapshot_ms=?, arrived_snapshot_ok=?,"
                + " returned_snapshot_ms=?, returned_snapshot_ok=?, rectify_strength=?,"
                + " signage_json=?, signage_state=?, notified_started=?, notified_ended=?,"
                + " event_count=?, neighbour_count=?, created_ms=?, start_soc_pct=?,"
                + " end_soc_pct=?, charged_while_parked=?, energy_est_kwh=?,"
                + " start_remain_kwh=?, end_remain_kwh=? WHERE session_id=?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            int i = bindSessionBody(ps, s, 1);
            ps.setString(i, s.sessionId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            logger.warn("updateSession failed: " + e.getMessage());
            return false;
        }
    }

    private static int bindSession(PreparedStatement ps, ParkingSession s, int i) throws Exception {
        ps.setString(i++, s.sessionId);
        return bindSessionBody(ps, s, i);
    }

    private static int bindSessionBody(PreparedStatement ps, ParkingSession s, int i) throws Exception {
        ps.setLong(i++, s.startedMs);
        ps.setLong(i++, s.endedMs);
        ps.setLong(i++, s.transitionGeneration);
        setStr(ps, i++, s.endTrigger);
        if (s.hasFix()) { ps.setDouble(i++, s.lat); ps.setDouble(i++, s.lng); }
        else { ps.setNull(i++, java.sql.Types.DOUBLE); ps.setNull(i++, java.sql.Types.DOUBLE); }
        ps.setFloat(i++, s.accuracyM);
        ps.setLong(i++, s.fixAgeMs);
        ps.setBoolean(i++, s.fixFromCache);
        setStr(ps, i++, s.gpsQuality);
        setStr(ps, i++, clamp(s.placeShort, 128));
        setStr(ps, i++, clamp(s.placeDisplay, 256));
        setStr(ps, i++, clamp(s.placeSource, 32));
        setStr(ps, i++, clamp(s.safeZone, 64));
        setStr(ps, i++, s.sentryState);
        ps.setLong(i++, s.arrivedSnapshotMs);
        ps.setBoolean(i++, s.arrivedSnapshotOk);
        ps.setLong(i++, s.returnedSnapshotMs);
        ps.setBoolean(i++, s.returnedSnapshotOk);
        ps.setInt(i++, s.rectifyStrength);
        setStr(ps, i++, s.signageJson);
        setStr(ps, i++, s.signageState);
        ps.setBoolean(i++, s.notifiedStarted);
        ps.setBoolean(i++, s.notifiedEnded);
        ps.setInt(i++, s.eventCount);
        ps.setInt(i++, s.neighbourCount);
        ps.setLong(i++, s.createdMs);
        setNullableDouble(ps, i++, s.startSocPercent);
        setNullableDouble(ps, i++, s.endSocPercent);
        ps.setBoolean(i++, s.chargedWhileParked);
        setNullableDouble(ps, i++, s.energyEstKwh);
        setNullableDouble(ps, i++, s.startRemainKwh);
        setNullableDouble(ps, i++, s.endRemainKwh);
        return i;
    }

    public synchronized ParkingSession getSession(String sessionId) {
        if (sessionId == null) return null;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT * FROM parking_sessions WHERE session_id=?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readSession(rs) : null;
            }
        } catch (Exception e) {
            logger.warn("getSession failed: " + e.getMessage());
            return null;
        }
    }

    /** The most recent session that has not been closed, or null. */
    public synchronized ParkingSession getOpenSession() {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT * FROM parking_sessions WHERE ended_ms <= 0"
                        + " ORDER BY started_ms DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? readSession(rs) : null;
        } catch (Exception e) {
            logger.warn("getOpenSession failed: " + e.getMessage());
            return null;
        }
    }

    /** Newest session regardless of state (for /where and the dashboard tile). */
    public synchronized ParkingSession getLatestSession() {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT * FROM parking_sessions ORDER BY started_ms DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? readSession(rs) : null;
        } catch (Exception e) {
            logger.warn("getLatestSession failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Sessions overlapping [fromMs, toMs] (either bound may be 0 = unbounded),
     * newest first.
     */
    public synchronized List<ParkingSession> listSessions(long fromMs, long toMs, int limit, int offset) {
        List<ParkingSession> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM parking_sessions WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (fromMs > 0) {
            // overlap: session ended after from (or still open)
            sql.append(" AND (ended_ms <= 0 OR ended_ms >= ?)");
            args.add(fromMs);
        }
        if (toMs > 0) {
            sql.append(" AND started_ms <= ?");
            args.add(toMs);
        }
        sql.append(" ORDER BY started_ms DESC LIMIT ? OFFSET ?");
        try (PreparedStatement ps = conn().prepareStatement(sql.toString())) {
            int i = 1;
            for (Object a : args) ps.setLong(i++, (Long) a);
            ps.setInt(i++, Math.max(1, Math.min(limit, 500)));
            ps.setInt(i, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readSession(rs));
            }
        } catch (Exception e) {
            logger.warn("listSessions failed: " + e.getMessage());
        }
        return out;
    }

    public synchronized int countSessions() {
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM parking_sessions")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public synchronized boolean deleteSession(String sessionId) {
        if (sessionId == null) return false;
        try {
            try (PreparedStatement ps = conn().prepareStatement(
                    "DELETE FROM parking_neighbours WHERE session_id=?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn().prepareStatement(
                    "DELETE FROM parking_sessions WHERE session_id=?")) {
                ps.setString(1, sessionId);
                return ps.executeUpdate() > 0;
            }
        } catch (Exception e) {
            logger.warn("deleteSession failed: " + e.getMessage());
            return false;
        }
    }

    /** Ids of closed sessions that started before {@code beforeMs} (retention). */
    /** Closed sessions started before {@code beforeMs}, oldest first (retention). */
    public synchronized List<String> listSessionIdsStartedBefore(long beforeMs, int limit) {
        return listSessionIdsStartedBetween(Long.MIN_VALUE, beforeMs, limit);
    }

    /**
     * Closed sessions with {@code floorMs <= started_ms < beforeMs}, oldest
     * first. The floor lets retention skip rows stamped by an unset clock.
     */
    public synchronized List<String> listSessionIdsStartedBetween(long floorMs, long beforeMs, int limit) {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT session_id FROM parking_sessions WHERE started_ms >= ? AND started_ms < ?"
                        + " AND ended_ms > 0 ORDER BY started_ms ASC LIMIT ?")) {
            ps.setLong(1, floorMs);
            ps.setLong(2, beforeMs);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (Exception e) {
            logger.warn("listSessionIdsStartedBetween failed: " + e.getMessage());
        }
        return out;
    }

    /** Every open row, newest first (normally zero or one; more after a crash). */
    public synchronized List<ParkingSession> listOpenSessions() {
        List<ParkingSession> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT * FROM parking_sessions WHERE ended_ms <= 0 ORDER BY started_ms DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(readSession(rs));
        } catch (Exception e) {
            logger.warn("listOpenSessions failed: " + e.getMessage());
        }
        return out;
    }

    /** Closed sessions whose signage read is still pending (v2 deferred OCR). */
    public synchronized List<ParkingSession> listSessionsWithSignageState(String state, int limit) {
        List<ParkingSession> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT * FROM parking_sessions WHERE signage_state=? AND ended_ms > 0"
                        + " ORDER BY started_ms DESC LIMIT ?")) {
            ps.setString(1, state);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readSession(rs));
            }
        } catch (Exception e) {
            logger.warn("listSessionsWithSignageState failed: " + e.getMessage());
        }
        return out;
    }

    private static ParkingSession readSession(ResultSet rs) throws Exception {
        ParkingSession s = new ParkingSession();
        s.sessionId = rs.getString("session_id");
        s.startedMs = rs.getLong("started_ms");
        s.endedMs = rs.getLong("ended_ms");
        s.transitionGeneration = rs.getLong("transition_gen");
        s.endTrigger = rs.getString("end_trigger");
        double lat = rs.getDouble("lat");
        boolean latNull = rs.wasNull();
        double lng = rs.getDouble("lng");
        boolean lngNull = rs.wasNull();
        if (!latNull && !lngNull) { s.lat = lat; s.lng = lng; }
        s.accuracyM = rs.getFloat("accuracy_m");
        s.fixAgeMs = rs.getLong("fix_age_ms");
        s.fixFromCache = rs.getBoolean("fix_from_cache");
        s.gpsQuality = rs.getString("gps_quality");
        if (s.gpsQuality == null) s.gpsQuality = ParkingSession.GPS_UNKNOWN;
        s.placeShort = rs.getString("place_short");
        s.placeDisplay = rs.getString("place_display");
        s.placeSource = rs.getString("place_source");
        s.safeZone = rs.getString("safe_zone");
        s.sentryState = rs.getString("sentry_state");
        if (s.sentryState == null) s.sentryState = ParkingSession.SENTRY_UNKNOWN;
        s.arrivedSnapshotMs = rs.getLong("arrived_snapshot_ms");
        s.arrivedSnapshotOk = rs.getBoolean("arrived_snapshot_ok");
        s.returnedSnapshotMs = rs.getLong("returned_snapshot_ms");
        s.returnedSnapshotOk = rs.getBoolean("returned_snapshot_ok");
        s.rectifyStrength = rs.getInt("rectify_strength");
        s.signageJson = rs.getString("signage_json");
        s.signageState = rs.getString("signage_state");
        if (s.signageState == null) s.signageState = ParkingSession.SIGNAGE_PENDING;
        s.notifiedStarted = rs.getBoolean("notified_started");
        s.notifiedEnded = rs.getBoolean("notified_ended");
        s.eventCount = rs.getInt("event_count");
        s.neighbourCount = rs.getInt("neighbour_count");
        s.createdMs = rs.getLong("created_ms");
        double startSoc = rs.getDouble("start_soc_pct");
        if (!rs.wasNull()) s.startSocPercent = startSoc;
        double endSoc = rs.getDouble("end_soc_pct");
        if (!rs.wasNull()) s.endSocPercent = endSoc;
        s.chargedWhileParked = rs.getBoolean("charged_while_parked");
        double estKwh = rs.getDouble("energy_est_kwh");
        if (!rs.wasNull()) s.energyEstKwh = estKwh;
        double startKwh = rs.getDouble("start_remain_kwh");
        if (!rs.wasNull()) s.startRemainKwh = startKwh;
        double endKwh = rs.getDouble("end_remain_kwh");
        if (!rs.wasNull()) s.endRemainKwh = endKwh;
        return s;
    }

    // ==================== NEIGHBOURS ====================

    /** Insert or update by (session, key). Returns the row id (0 on failure). */
    public synchronized long upsertNeighbour(ParkingNeighbour n) {
        if (n == null || n.sessionId == null || n.neighbourKey == null) return 0L;
        try {
            ParkingNeighbour existing = findNeighbourByKey(n.sessionId, n.neighbourKey);
            if (existing == null) {
                String sql = "INSERT INTO parking_neighbours (session_id, neighbour_key, side, kind,"
                        + " class_group, status, confirmed, first_seen_ms, arrived_ms, departed_ms,"
                        + " last_seen_ms, cx, cy, w, h, proximity, arrival_event, departure_event,"
                        + " actor_ids, frames_json, updated_ms) VALUES ("
                        + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                try (PreparedStatement ps = conn().prepareStatement(sql,
                        Statement.RETURN_GENERATED_KEYS)) {
                    bindNeighbour(ps, n, 1, true);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) n.id = keys.getLong(1);
                    }
                }
            } else {
                n.id = existing.id;
                String sql = "UPDATE parking_neighbours SET side=?, kind=?, class_group=?, status=?,"
                        + " confirmed=?, first_seen_ms=?, arrived_ms=?, departed_ms=?, last_seen_ms=?,"
                        + " cx=?, cy=?, w=?, h=?, proximity=?, arrival_event=?, departure_event=?,"
                        + " actor_ids=?, frames_json=?, updated_ms=? WHERE id=?";
                try (PreparedStatement ps = conn().prepareStatement(sql)) {
                    int i = bindNeighbour(ps, n, 1, false);
                    ps.setLong(i, n.id);
                    ps.executeUpdate();
                }
            }
            return n.id;
        } catch (Exception e) {
            logger.warn("upsertNeighbour failed: " + e.getMessage());
            return 0L;
        }
    }

    private static int bindNeighbour(PreparedStatement ps, ParkingNeighbour n, int i,
                                     boolean includeIdentity) throws Exception {
        if (includeIdentity) {
            ps.setString(i++, n.sessionId);
            ps.setString(i++, n.neighbourKey);
        }
        ps.setInt(i++, n.side);
        ps.setString(i++, n.kind == null ? ParkingNeighbour.KIND_NEIGHBOUR : n.kind);
        setStr(ps, i++, n.classGroup);
        setStr(ps, i++, n.status);
        ps.setBoolean(i++, n.confirmed);
        ps.setLong(i++, n.firstSeenMs);
        ps.setLong(i++, n.arrivedMs);
        ps.setLong(i++, n.departedMs);
        ps.setLong(i++, n.lastSeenMs);
        ps.setFloat(i++, n.cx); ps.setFloat(i++, n.cy); ps.setFloat(i++, n.w); ps.setFloat(i++, n.h);
        setStr(ps, i++, n.proximity);
        setStr(ps, i++, clamp(n.arrivalEvent, 256));
        setStr(ps, i++, clamp(n.departureEvent, 256));
        setStr(ps, i++, clamp(n.actorIds, 256));
        setStr(ps, i++, n.framesJson);
        ps.setLong(i++, n.updatedMs);
        return i;
    }

    public synchronized ParkingNeighbour findNeighbourByKey(String sessionId, String key) {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT * FROM parking_neighbours WHERE session_id=? AND neighbour_key=?")) {
            ps.setString(1, sessionId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readNeighbour(rs) : null;
            }
        } catch (Exception e) {
            logger.warn("findNeighbourByKey failed: " + e.getMessage());
            return null;
        }
    }

    public synchronized List<ParkingNeighbour> listNeighbours(String sessionId) {
        List<ParkingNeighbour> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT * FROM parking_neighbours WHERE session_id=?"
                        + " ORDER BY side ASC, first_seen_ms ASC")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readNeighbour(rs));
            }
        } catch (Exception e) {
            logger.warn("listNeighbours failed: " + e.getMessage());
        }
        return out;
    }

    /** Drop the frame lists of a session whose asset folder was reclaimed by the storage cap. */
    public synchronized int clearNeighbourFrames(String sessionId) {
        if (sessionId == null) return 0;
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE parking_neighbours SET frames_json=NULL WHERE session_id=? AND frames_json IS NOT NULL")) {
            ps.setString(1, sessionId);
            return ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("clearNeighbourFrames failed: " + e.getMessage());
            return 0;
        }
    }

    public synchronized int countNeighbours(String sessionId, boolean confirmedOnly) {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT COUNT(*) FROM parking_neighbours WHERE session_id=? AND kind=?"
                        + (confirmedOnly ? " AND confirmed=TRUE" : ""))) {
            ps.setString(1, sessionId);
            ps.setString(2, ParkingNeighbour.KIND_NEIGHBOUR);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static ParkingNeighbour readNeighbour(ResultSet rs) throws Exception {
        ParkingNeighbour n = new ParkingNeighbour();
        n.id = rs.getLong("id");
        n.sessionId = rs.getString("session_id");
        n.neighbourKey = rs.getString("neighbour_key");
        n.side = rs.getInt("side");
        n.kind = rs.getString("kind");
        n.classGroup = rs.getString("class_group");
        n.status = rs.getString("status");
        n.confirmed = rs.getBoolean("confirmed");
        n.firstSeenMs = rs.getLong("first_seen_ms");
        n.arrivedMs = rs.getLong("arrived_ms");
        n.departedMs = rs.getLong("departed_ms");
        n.lastSeenMs = rs.getLong("last_seen_ms");
        n.cx = rs.getFloat("cx"); n.cy = rs.getFloat("cy");
        n.w = rs.getFloat("w"); n.h = rs.getFloat("h");
        n.proximity = rs.getString("proximity");
        n.arrivalEvent = rs.getString("arrival_event");
        n.departureEvent = rs.getString("departure_event");
        n.actorIds = rs.getString("actor_ids");
        n.framesJson = rs.getString("frames_json");
        n.updatedMs = rs.getLong("updated_ms");
        return n;
    }

    // ==================== HELPERS ====================

    private static void setStr(PreparedStatement ps, int idx, String v) throws Exception {
        if (v == null) ps.setNull(idx, java.sql.Types.VARCHAR);
        else ps.setString(idx, v);
    }

    /** NaN is the in-memory "unknown"; NULL is its column representation. */
    private static void setNullableDouble(PreparedStatement ps, int idx, double v) throws Exception {
        if (Double.isNaN(v)) ps.setNull(idx, java.sql.Types.DOUBLE);
        else ps.setDouble(idx, v);
    }

    private static String clamp(String v, int max) {
        if (v == null) return null;
        return v.length() <= max ? v : v.substring(0, max);
    }
}
