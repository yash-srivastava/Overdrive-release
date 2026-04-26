package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * H2 embedded database for trip catalog, rollups, and consumption buckets.
 * Follows the same pattern as SocHistoryDatabase but uses a separate DB file.
 */
public class TripDatabase {

    private static final String TAG = "TripDatabase";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String DB_PATH = "/data/local/tmp/overdrive_trips_h2";
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
            ";AUTO_SERVER=TRUE;FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0";

    private Connection connection;
    private volatile boolean isInitialized = false;

    // ==================== LIFECYCLE ====================

    public void init() {
        if (isInitialized) return;

        logger.info("Initializing H2 trip database at: " + DB_PATH);

        // Load H2 JDBC driver
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            logger.error("H2 Driver not found! Check gradle dependencies.", e);
            return;
        }

        int maxRetries = 3;
        int retryDelayMs = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                logger.info("H2 connection established");

                // Tune H2 for embedded daemon use
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("SET CACHE_SIZE 8192");
                }

                createTables();
                isInitialized = true;
                logger.info("Trip Database initialized via H2 (Pure Java): " + DB_PATH);
                return;

            } catch (Exception e) {
                String msg = e.getMessage();
                boolean isLockError = msg != null && (msg.contains("Locked by another process") ||
                        msg.contains("lock.db") || msg.contains("already in use"));

                if (isLockError && attempt < maxRetries) {
                    logger.warn("Database locked (attempt " + attempt + "/" + maxRetries + "), cleaning up stale locks...");
                    cleanupStaleLocks();
                    try {
                        Thread.sleep(retryDelayMs * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    logger.error("Failed to initialize trip database: " + e.getClass().getName() + " - " + msg, e);
                    break;
                }
            }
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
                logger.info("Trip database connection closed");
            } catch (Exception e) {
                logger.error("Failed to close trip database connection", e);
            }
            connection = null;
        }
        isInitialized = false;
    }

    private void reconnect() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                isInitialized = true;
                logger.info("H2 trip database connection re-established");
            }
        } catch (Exception e) {
            logger.error("Failed to reconnect to H2 trip database", e);
        }
    }

    /**
     * Ensure the database connection is alive. Returns true if ready.
     * Attempts reconnection if the connection is closed.
     */
    private boolean ensureConnection() {
        if (!isInitialized && connection == null) return false;
        try {
            if (connection == null || connection.isClosed()) {
                logger.info("Database connection closed, reconnecting...");
                reconnect();
                return connection != null && !connection.isClosed();
            }
            return true;
        } catch (Exception e) {
            logger.error("Connection check failed", e);
            reconnect();
            try {
                return connection != null && !connection.isClosed();
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private void cleanupStaleLocks() {
        try {
            java.io.File lockFile = new java.io.File(DB_PATH + ".lock.db");
            if (lockFile.exists()) {
                long ageMs = System.currentTimeMillis() - lockFile.lastModified();
                if (ageMs > 5 * 60 * 1000) {
                    if (lockFile.delete()) {
                        logger.info("Deleted stale lock file (age: " + (ageMs / 1000) + "s)");
                    }
                }
            }
            java.io.File traceFile = new java.io.File(DB_PATH + ".trace.db");
            if (traceFile.exists()) {
                traceFile.delete();
            }
        } catch (Exception e) {
            logger.debug("Lock cleanup failed: " + e.getMessage());
        }
    }

    private void createTables() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            // Trip catalog
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS trips (" +
                "id IDENTITY PRIMARY KEY," +
                "start_time BIGINT NOT NULL," +
                "end_time BIGINT NOT NULL," +
                "distance_km REAL NOT NULL," +
                "duration_seconds INTEGER NOT NULL," +
                "avg_speed_kmh REAL," +
                "max_speed_kmh INTEGER," +
                "soc_start REAL," +
                "soc_end REAL," +
                "kwh_start REAL DEFAULT 0," +
                "kwh_end REAL DEFAULT 0," +
                "energy_per_km REAL DEFAULT 0," +
                "electricity_rate REAL DEFAULT 0," +
                "currency VARCHAR(8) DEFAULT ''," +
                "trip_cost REAL DEFAULT 0," +
                "kinematic_state VARCHAR(32) DEFAULT ''," +
                "efficiency_soc_per_km REAL," +
                "start_lat REAL," +
                "start_lon REAL," +
                "end_lat REAL," +
                "end_lon REAL," +
                "ext_temp_c INTEGER," +
                "anticipation_score INTEGER," +
                "smoothness_score INTEGER," +
                "speed_discipline_score INTEGER," +
                "efficiency_score INTEGER," +
                "consistency_score INTEGER," +
                "micro_moments_json CLOB," +
                "telemetry_file_path VARCHAR(512)" +
                ")"
            );

            // Indexes on trips
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_trips_start ON trips(start_time)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_trips_end ON trips(end_time)");

            // Migration: add kWh columns if they don't exist (for existing databases)
            try {
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS kwh_start REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS kwh_end REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS energy_per_km REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS electricity_rate REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS currency VARCHAR(8) DEFAULT ''");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS trip_cost REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS kinematic_state VARCHAR(32) DEFAULT ''");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS gradient_profile VARCHAR(16) DEFAULT ''");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS elevation_gain_m REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS elevation_loss_m REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS avg_gradient_pct REAL DEFAULT 0");
            } catch (Exception e) {
                // Columns already exist or H2 version doesn't support IF NOT EXISTS
                logger.debug("trips kWh column migration: " + e.getMessage());
            }

            // Routes table for O(1) similar-trip lookups
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS routes (" +
                "id IDENTITY PRIMARY KEY," +
                "start_lat REAL NOT NULL," +
                "start_lon REAL NOT NULL," +
                "end_lat REAL NOT NULL," +
                "end_lon REAL NOT NULL," +
                "avg_distance_km REAL DEFAULT 0," +
                "trip_count INTEGER DEFAULT 0" +
                ")"
            );

            // Migration: add route_id to trips
            try {
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS route_id BIGINT DEFAULT NULL");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_trips_route ON trips(route_id)");
                // Clean up any sentinel rows from previous migrations
                stmt.execute("DELETE FROM routes WHERE trip_count < 0");
            } catch (Exception e) {
                logger.debug("route_id migration: " + e.getMessage());
            }

            // Weekly rollups
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS weekly_rollups (" +
                "\"year\" INTEGER NOT NULL," +
                "week_number INTEGER NOT NULL," +
                "trip_count INTEGER DEFAULT 0," +
                "total_distance_km REAL DEFAULT 0," +
                "total_duration_seconds INTEGER DEFAULT 0," +
                "avg_efficiency REAL DEFAULT 0," +
                "total_energy_kwh REAL DEFAULT 0," +
                "total_cost REAL DEFAULT 0," +
                "avg_energy_per_km REAL DEFAULT 0," +
                "avg_anticipation INTEGER DEFAULT 0," +
                "avg_smoothness INTEGER DEFAULT 0," +
                "avg_speed_discipline INTEGER DEFAULT 0," +
                "avg_efficiency_score INTEGER DEFAULT 0," +
                "avg_consistency INTEGER DEFAULT 0," +
                "PRIMARY KEY (\"year\", week_number)" +
                ")"
            );

            // Weekly rollups migration (for databases created before energy columns were added)
            try {
                stmt.execute("ALTER TABLE weekly_rollups ADD COLUMN IF NOT EXISTS total_energy_kwh REAL DEFAULT 0");
                stmt.execute("ALTER TABLE weekly_rollups ADD COLUMN IF NOT EXISTS total_cost REAL DEFAULT 0");
                stmt.execute("ALTER TABLE weekly_rollups ADD COLUMN IF NOT EXISTS avg_energy_per_km REAL DEFAULT 0");
            } catch (Exception e) {
                logger.debug("weekly_rollups energy migration: " + e.getMessage());
            }

            // Monthly rollups
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS monthly_rollups (" +
                "\"year\" INTEGER NOT NULL," +
                "month_number INTEGER NOT NULL," +
                "trip_count INTEGER DEFAULT 0," +
                "total_distance_km REAL DEFAULT 0," +
                "total_duration_seconds INTEGER DEFAULT 0," +
                "avg_efficiency REAL DEFAULT 0," +
                "total_energy_kwh REAL DEFAULT 0," +
                "total_cost REAL DEFAULT 0," +
                "avg_energy_per_km REAL DEFAULT 0," +
                "avg_anticipation INTEGER DEFAULT 0," +
                "avg_smoothness INTEGER DEFAULT 0," +
                "avg_speed_discipline INTEGER DEFAULT 0," +
                "avg_efficiency_score INTEGER DEFAULT 0," +
                "avg_consistency INTEGER DEFAULT 0," +
                "PRIMARY KEY (\"year\", month_number)" +
                ")"
            );

            // Monthly rollups migration (for databases created before energy columns were added)
            try {
                stmt.execute("ALTER TABLE monthly_rollups ADD COLUMN IF NOT EXISTS total_energy_kwh REAL DEFAULT 0");
                stmt.execute("ALTER TABLE monthly_rollups ADD COLUMN IF NOT EXISTS total_cost REAL DEFAULT 0");
                stmt.execute("ALTER TABLE monthly_rollups ADD COLUMN IF NOT EXISTS avg_energy_per_km REAL DEFAULT 0");
            } catch (Exception e) {
                logger.debug("monthly_rollups energy migration: " + e.getMessage());
            }

            // Consumption buckets
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS consumption_buckets (" +
                "bucket_key VARCHAR(64) PRIMARY KEY," +
                "sample_count INTEGER DEFAULT 0," +
                "sum_kwh_per_km REAL DEFAULT 0," +
                "sum_squared_kwh_per_km REAL DEFAULT 0" +
                ")"
            );
        }
    }

    // ==================== TRIP CRUD (Task 7.2) ====================

    /**
     * Insert a new trip record and return the auto-generated id.
     */
    public long insertTrip(TripRecord trip) {
        if (!ensureConnection()) return -1;

        String sql = "INSERT INTO trips (start_time, end_time, distance_km, duration_seconds, " +
                "avg_speed_kmh, max_speed_kmh, soc_start, soc_end, kwh_start, kwh_end, energy_per_km, " +
                "electricity_rate, currency, trip_cost, kinematic_state, " +
                "gradient_profile, elevation_gain_m, elevation_loss_m, avg_gradient_pct, " +
                "efficiency_soc_per_km, " +
                "start_lat, start_lon, end_lat, end_lon, ext_temp_c, " +
                "anticipation_score, smoothness_score, speed_discipline_score, " +
                "efficiency_score, consistency_score, micro_moments_json, telemetry_file_path, route_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setTripParams(pstmt, trip);
            pstmt.setObject(33, trip.routeId > 0 ? trip.routeId : null);
            pstmt.executeUpdate();

            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    trip.id = id;
                    logger.info("Inserted trip id=" + id);
                    return id;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to insert trip", e);
            reconnect();
        }
        return -1;
    }

    /**
     * Update all fields of an existing trip by id.
     */
    public void updateTrip(TripRecord trip) {
        if (!ensureConnection()) return;

        String sql = "UPDATE trips SET start_time=?, end_time=?, distance_km=?, duration_seconds=?, " +
                "avg_speed_kmh=?, max_speed_kmh=?, soc_start=?, soc_end=?, kwh_start=?, kwh_end=?, energy_per_km=?, " +
                "electricity_rate=?, currency=?, trip_cost=?, kinematic_state=?, " +
                "gradient_profile=?, elevation_gain_m=?, elevation_loss_m=?, avg_gradient_pct=?, " +
                "efficiency_soc_per_km=?, " +
                "start_lat=?, start_lon=?, end_lat=?, end_lon=?, ext_temp_c=?, " +
                "anticipation_score=?, smoothness_score=?, speed_discipline_score=?, " +
                "efficiency_score=?, consistency_score=?, micro_moments_json=?, telemetry_file_path=?, " +
                "route_id=? " +
                "WHERE id=?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            setTripParams(pstmt, trip);
            pstmt.setObject(33, trip.routeId > 0 ? trip.routeId : null);
            pstmt.setLong(34, trip.id);
            pstmt.executeUpdate();
            logger.debug("Updated trip id=" + trip.id);
        } catch (Exception e) {
            logger.error("Failed to update trip id=" + trip.id, e);
            reconnect();
        }
    }

    /**
     * Get a single trip by id, or null if not found.
     */
    public TripRecord getTrip(long id) {
        if (!ensureConnection()) return null;

        String sql = "SELECT * FROM trips WHERE id=?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return readTripFromResultSet(rs);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get trip id=" + id, e);
            reconnect();
        }
        return null;
    }

    /**
     * Get recent trips within the given number of days, limited to the given count.
     * Sorted by start_time descending (newest first).
     */
    public List<TripRecord> getTrips(int days, int limit) {
        List<TripRecord> trips = new ArrayList<>();
        if (!ensureConnection()) return trips;

        long cutoff = System.currentTimeMillis() - ((long) days * 86400000L);
        String sql = "SELECT * FROM trips WHERE start_time >= ? ORDER BY start_time DESC LIMIT ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, cutoff);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    trips.add(readTripFromResultSet(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get trips", e);
            reconnect();
        }
        return trips;
    }

    /**
     * Delete a trip by id. Returns true if a row was deleted.
     */
    public boolean deleteTrip(long id) {
        if (!ensureConnection()) return false;

        String sql = "DELETE FROM trips WHERE id=?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            int rows = pstmt.executeUpdate();
            if (rows > 0) {
                logger.info("Deleted trip id=" + id);
                return true;
            }
        } catch (Exception e) {
            logger.error("Failed to delete trip id=" + id, e);
            reconnect();
        }
        return false;
    }

    /**
     * Get the total number of trips in the database.
     */
    public int getTripCount() {
        if (!ensureConnection()) return 0;

        String sql = "SELECT COUNT(*) FROM trips";
        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (Exception e) {
            logger.error("Failed to get trip count", e);
            reconnect();
        }
        return 0;
    }

    // ==================== ROUTE MATCHING ====================

    /**
     * Find or create a route for the given trip coordinates.
     * Scans the routes table (small — typically 10-30 routes) for a match.
     * Returns the route_id.
     */
    public long findOrCreateRoute(double startLat, double startLon, double endLat, double endLon, double distanceKm) {
        if (!ensureConnection()) return -1;

        try {
            // Scan routes table for a match (geofence 0.01° ≈ 1.1km)
            String sql = "SELECT id, avg_distance_km, trip_count FROM routes " +
                    "WHERE ABS(start_lat - ?) < 0.01 AND ABS(start_lon - ?) < 0.01 " +
                    "AND ABS(end_lat - ?) < 0.01 AND ABS(end_lon - ?) < 0.01";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setDouble(1, startLat);
                pstmt.setDouble(2, startLon);
                pstmt.setDouble(3, endLat);
                pstmt.setDouble(4, endLon);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        double avgDist = rs.getDouble("avg_distance_km");
                        // Match found — update stats (running average for coordinates and distance)
                        long routeId = rs.getLong("id");
                        int count = rs.getInt("trip_count");
                        double newAvgDist = (avgDist * count + distanceKm) / (count + 1);
                        String update = "UPDATE routes SET trip_count = trip_count + 1, " +
                                "avg_distance_km = ?, " +
                                "start_lat = (start_lat * trip_count + ?) / (trip_count + 1), " +
                                "start_lon = (start_lon * trip_count + ?) / (trip_count + 1), " +
                                "end_lat = (end_lat * trip_count + ?) / (trip_count + 1), " +
                                "end_lon = (end_lon * trip_count + ?) / (trip_count + 1) " +
                                "WHERE id = ?";
                        try (PreparedStatement upd = connection.prepareStatement(update)) {
                            upd.setDouble(1, newAvgDist);
                            upd.setDouble(2, startLat);
                            upd.setDouble(3, startLon);
                            upd.setDouble(4, endLat);
                            upd.setDouble(5, endLon);
                            upd.setLong(6, routeId);
                            upd.executeUpdate();
                        }
                        return routeId;
                    }
                }
            }

            // No match — create new route
            String insert = "INSERT INTO routes (start_lat, start_lon, end_lat, end_lon, avg_distance_km, trip_count) " +
                    "VALUES (?, ?, ?, ?, ?, 1)";
            try (PreparedStatement pstmt = connection.prepareStatement(insert, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setDouble(1, startLat);
                pstmt.setDouble(2, startLon);
                pstmt.setDouble(3, endLat);
                pstmt.setDouble(4, endLon);
                pstmt.setDouble(5, distanceKm);
                pstmt.executeUpdate();
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to find/create route", e);
        }
        return -1;
    }

    /**
     * Get trips by route_id — O(1) indexed lookup.
     */
    public java.util.List<TripRecord> getTripsByRoute(long routeId, int limit) {
        java.util.List<TripRecord> trips = new java.util.ArrayList<>();
        if (!ensureConnection()) return trips;

        String sql = "SELECT * FROM trips WHERE route_id = ? ORDER BY start_time DESC LIMIT ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, routeId);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    trips.add(readTripFromResultSet(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get trips by route", e);
        }
        return trips;
    }

    /**
     * Backfill route_id for all existing trips that don't have one.
     * Called once after migration. Scans trips without route_id and assigns them.
     */
    public void backfillRouteIds() {
        if (!ensureConnection()) return;

        String sql = "SELECT id, start_lat, start_lon, end_lat, end_lon, distance_km FROM trips " +
                "WHERE route_id IS NULL AND start_lat != 0 ORDER BY start_time ASC";
        int assigned = 0;
        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong("id");
                double sLat = rs.getDouble("start_lat");
                double sLon = rs.getDouble("start_lon");
                double eLat = rs.getDouble("end_lat");
                double eLon = rs.getDouble("end_lon");
                double dist = rs.getDouble("distance_km");

                long routeId = findOrCreateRoute(sLat, sLon, eLat, eLon, dist);
                if (routeId > 0) {
                    try (PreparedStatement upd = connection.prepareStatement(
                            "UPDATE trips SET route_id = ? WHERE id = ?")) {
                        upd.setLong(1, routeId);
                        upd.setLong(2, id);
                        upd.executeUpdate();
                        assigned++;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to backfill route IDs", e);
        }
        if (assigned > 0) {
            logger.info("Backfilled route_id for " + assigned + " existing trips");
        }
    }

    // ==================== ROLLUPS (Task 7.3) ====================

    /**
     * Update the weekly rollup for the ISO week of the given trip.
     * Uses MERGE with running average: new_avg = (old_avg * old_count + new_value) / (old_count + 1)
     */
    public void updateWeeklyRollup(TripRecord trip) {
        if (!ensureConnection()) return;

        Calendar cal = Calendar.getInstance(Locale.US);
        cal.setTimeInMillis(trip.startTime);
        cal.setMinimalDaysInFirstWeek(4); // ISO week
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        int year = cal.get(Calendar.YEAR);
        int week = cal.get(Calendar.WEEK_OF_YEAR);

        try {
            // First try to read existing rollup
            WeeklyRollup existing = getWeeklyRollup(year, week);

            if (existing == null) {
                // Insert new rollup
                String sql = "INSERT INTO weekly_rollups (\"year\", week_number, trip_count, " +
                        "total_distance_km, total_duration_seconds, avg_efficiency, " +
                        "total_energy_kwh, total_cost, avg_energy_per_km, " +
                        "avg_anticipation, avg_smoothness, avg_speed_discipline, " +
                        "avg_efficiency_score, avg_consistency) " +
                        "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setInt(1, year);
                    pstmt.setInt(2, week);
                    pstmt.setDouble(3, trip.distanceKm);
                    pstmt.setInt(4, trip.durationSeconds);
                    pstmt.setDouble(5, trip.efficiencySocPerKm);
                    pstmt.setDouble(6, trip.getEnergyUsedKwh());
                    pstmt.setDouble(7, trip.tripCost);
                    pstmt.setDouble(8, trip.energyPerKm);
                    pstmt.setInt(9, trip.anticipationScore);
                    pstmt.setInt(10, trip.smoothnessScore);
                    pstmt.setInt(11, trip.speedDisciplineScore);
                    pstmt.setInt(12, trip.efficiencyScore);
                    pstmt.setInt(13, trip.consistencyScore);
                    pstmt.executeUpdate();
                }
            } else {
                // Update with running averages
                int oldCount = existing.tripCount;
                int newCount = oldCount + 1;

                String sql = "UPDATE weekly_rollups SET trip_count=?, " +
                        "total_distance_km=?, total_duration_seconds=?, avg_efficiency=?, " +
                        "total_energy_kwh=?, total_cost=?, avg_energy_per_km=?, " +
                        "avg_anticipation=?, avg_smoothness=?, avg_speed_discipline=?, " +
                        "avg_efficiency_score=?, avg_consistency=? " +
                        "WHERE \"year\"=? AND week_number=?";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setInt(1, newCount);
                    pstmt.setDouble(2, existing.totalDistanceKm + trip.distanceKm);
                    pstmt.setInt(3, existing.totalDurationSeconds + trip.durationSeconds);
                    pstmt.setDouble(4, runningAvg(existing.avgEfficiency, oldCount, trip.efficiencySocPerKm));
                    pstmt.setDouble(5, existing.totalEnergyKwh + trip.getEnergyUsedKwh());
                    pstmt.setDouble(6, existing.totalCost + trip.tripCost);
                    pstmt.setDouble(7, runningAvg(existing.avgEnergyPerKm, oldCount, trip.energyPerKm));
                    pstmt.setInt(8, (int) runningAvg(existing.avgAnticipation, oldCount, trip.anticipationScore));
                    pstmt.setInt(9, (int) runningAvg(existing.avgSmoothness, oldCount, trip.smoothnessScore));
                    pstmt.setInt(10, (int) runningAvg(existing.avgSpeedDiscipline, oldCount, trip.speedDisciplineScore));
                    pstmt.setInt(11, (int) runningAvg(existing.avgEfficiencyScore, oldCount, trip.efficiencyScore));
                    pstmt.setInt(12, (int) runningAvg(existing.avgConsistency, oldCount, trip.consistencyScore));
                    pstmt.setInt(13, year);
                    pstmt.setInt(14, week);
                    pstmt.executeUpdate();
                }
            }
            logger.debug("Updated weekly rollup year=" + year + " week=" + week);
        } catch (Exception e) {
            logger.error("Failed to update weekly rollup", e);
            reconnect();
        }
    }

    /**
     * Update the monthly rollup for the month of the given trip.
     * Uses MERGE with running average: new_avg = (old_avg * old_count + new_value) / (old_count + 1)
     */
    public void updateMonthlyRollup(TripRecord trip) {
        if (!ensureConnection()) return;

        Calendar cal = Calendar.getInstance(Locale.US);
        cal.setTimeInMillis(trip.startTime);
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1; // Calendar.MONTH is 0-based

        try {
            // First try to read existing rollup
            MonthlyRollup existing = getMonthlyRollup(year, month);

            if (existing == null) {
                // Insert new rollup
                String sql = "INSERT INTO monthly_rollups (\"year\", month_number, trip_count, " +
                        "total_distance_km, total_duration_seconds, avg_efficiency, " +
                        "total_energy_kwh, total_cost, avg_energy_per_km, " +
                        "avg_anticipation, avg_smoothness, avg_speed_discipline, " +
                        "avg_efficiency_score, avg_consistency) " +
                        "VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setInt(1, year);
                    pstmt.setInt(2, month);
                    pstmt.setDouble(3, trip.distanceKm);
                    pstmt.setInt(4, trip.durationSeconds);
                    pstmt.setDouble(5, trip.efficiencySocPerKm);
                    pstmt.setDouble(6, trip.getEnergyUsedKwh());
                    pstmt.setDouble(7, trip.tripCost);
                    pstmt.setDouble(8, trip.energyPerKm);
                    pstmt.setInt(9, trip.anticipationScore);
                    pstmt.setInt(10, trip.smoothnessScore);
                    pstmt.setInt(11, trip.speedDisciplineScore);
                    pstmt.setInt(12, trip.efficiencyScore);
                    pstmt.setInt(13, trip.consistencyScore);
                    pstmt.executeUpdate();
                }
            } else {
                // Update with running averages
                int oldCount = existing.tripCount;
                int newCount = oldCount + 1;

                String sql = "UPDATE monthly_rollups SET trip_count=?, " +
                        "total_distance_km=?, total_duration_seconds=?, avg_efficiency=?, " +
                        "total_energy_kwh=?, total_cost=?, avg_energy_per_km=?, " +
                        "avg_anticipation=?, avg_smoothness=?, avg_speed_discipline=?, " +
                        "avg_efficiency_score=?, avg_consistency=? " +
                        "WHERE \"year\"=? AND month_number=?";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setInt(1, newCount);
                    pstmt.setDouble(2, existing.totalDistanceKm + trip.distanceKm);
                    pstmt.setInt(3, existing.totalDurationSeconds + trip.durationSeconds);
                    pstmt.setDouble(4, runningAvg(existing.avgEfficiency, oldCount, trip.efficiencySocPerKm));
                    pstmt.setDouble(5, existing.totalEnergyKwh + trip.getEnergyUsedKwh());
                    pstmt.setDouble(6, existing.totalCost + trip.tripCost);
                    pstmt.setDouble(7, runningAvg(existing.avgEnergyPerKm, oldCount, trip.energyPerKm));
                    pstmt.setInt(8, (int) runningAvg(existing.avgAnticipation, oldCount, trip.anticipationScore));
                    pstmt.setInt(9, (int) runningAvg(existing.avgSmoothness, oldCount, trip.smoothnessScore));
                    pstmt.setInt(10, (int) runningAvg(existing.avgSpeedDiscipline, oldCount, trip.speedDisciplineScore));
                    pstmt.setInt(11, (int) runningAvg(existing.avgEfficiencyScore, oldCount, trip.efficiencyScore));
                    pstmt.setInt(12, (int) runningAvg(existing.avgConsistency, oldCount, trip.consistencyScore));
                    pstmt.setInt(13, year);
                    pstmt.setInt(14, month);
                    pstmt.executeUpdate();
                }
            }
            logger.debug("Updated monthly rollup year=" + year + " month=" + month);
        } catch (Exception e) {
            logger.error("Failed to update monthly rollup", e);
            reconnect();
        }
    }

    /**
     * Get a weekly rollup by year and ISO week number.
     */
    public WeeklyRollup getWeeklyRollup(int year, int week) {
        if (!ensureConnection()) return null;

        String sql = "SELECT * FROM weekly_rollups WHERE \"year\"=? AND week_number=?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, year);
            pstmt.setInt(2, week);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return readWeeklyRollupFromResultSet(rs);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get weekly rollup year=" + year + " week=" + week, e);
            reconnect();
        }
        return null;
    }

    /**
     * Get the most recent N weekly rollups, ordered by year/week descending.
     */
    public List<WeeklyRollup> getRecentWeeklyRollups(int weeks) {
        List<WeeklyRollup> rollups = new ArrayList<>();
        if (!ensureConnection()) return rollups;

        String sql = "SELECT * FROM weekly_rollups ORDER BY \"year\" DESC, week_number DESC LIMIT ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, weeks);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    rollups.add(readWeeklyRollupFromResultSet(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get recent weekly rollups", e);
            reconnect();
        }
        return rollups;
    }

    /**
     * Get the most recent N monthly rollups, ordered by year/month descending.
     */
    public List<MonthlyRollup> getRecentMonthlyRollups(int months) {
        List<MonthlyRollup> rollups = new ArrayList<>();
        if (!ensureConnection()) return rollups;

        String sql = "SELECT * FROM monthly_rollups ORDER BY \"year\" DESC, month_number DESC LIMIT ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, months);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    rollups.add(readMonthlyRollupFromResultSet(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get recent monthly rollups", e);
            reconnect();
        }
        return rollups;
    }

    /**
     * Compute average DNA scores over the given number of days.
     */
    public DnaScores getAverageDna(int days) {
        if (!ensureConnection()) return null;

        long cutoff = System.currentTimeMillis() - ((long) days * 86400000L);
        String sql = "SELECT AVG(anticipation_score) as avg_ant, AVG(smoothness_score) as avg_smo, " +
                "AVG(speed_discipline_score) as avg_spd, AVG(efficiency_score) as avg_eff, " +
                "AVG(consistency_score) as avg_con, COUNT(*) as cnt " +
                "FROM trips WHERE start_time >= ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, cutoff);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next() && rs.getInt("cnt") > 0) {
                    DnaScores scores = new DnaScores();
                    scores.anticipation = (int) Math.round(rs.getDouble("avg_ant"));
                    scores.smoothness = (int) Math.round(rs.getDouble("avg_smo"));
                    scores.speedDiscipline = (int) Math.round(rs.getDouble("avg_spd"));
                    scores.efficiency = (int) Math.round(rs.getDouble("avg_eff"));
                    scores.consistency = (int) Math.round(rs.getDouble("avg_con"));
                    return scores;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get average DNA scores", e);
            reconnect();
        }
        return null;
    }

    // ==================== CONSUMPTION BUCKETS (Task 7.4) ====================

    /**
     * Update a consumption bucket by incrementing sampleCount, adding to sum, and adding square to sumSquared.
     * Uses MERGE semantics: insert if not exists, update if exists.
     */
    public void updateConsumptionBucket(String bucketKey, double consumptionKwhPerKm) {
        if (!ensureConnection()) return;

        try {
            // Try to read existing bucket
            ConsumptionBucket existing = getBucket(bucketKey);

            if (existing == null) {
                // Insert new bucket
                String sql = "INSERT INTO consumption_buckets (bucket_key, sample_count, sum_kwh_per_km, sum_squared_kwh_per_km) " +
                        "VALUES (?, 1, ?, ?)";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setString(1, bucketKey);
                    pstmt.setDouble(2, consumptionKwhPerKm);
                    pstmt.setDouble(3, consumptionKwhPerKm * consumptionKwhPerKm);
                    pstmt.executeUpdate();
                }
            } else {
                // Update existing bucket: increment count, add to sum, add square to sumSquared
                String sql = "UPDATE consumption_buckets SET sample_count = sample_count + 1, " +
                        "sum_kwh_per_km = sum_kwh_per_km + ?, " +
                        "sum_squared_kwh_per_km = sum_squared_kwh_per_km + ? " +
                        "WHERE bucket_key = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setDouble(1, consumptionKwhPerKm);
                    pstmt.setDouble(2, consumptionKwhPerKm * consumptionKwhPerKm);
                    pstmt.setString(3, bucketKey);
                    pstmt.executeUpdate();
                }
            }
            logger.debug("Updated consumption bucket: " + bucketKey);
        } catch (Exception e) {
            logger.error("Failed to update consumption bucket: " + bucketKey, e);
            reconnect();
        }
    }

    /**
     * Get a consumption bucket by key, or null if not found.
     */
    public ConsumptionBucket getBucket(String bucketKey) {
        if (!ensureConnection()) return null;

        String sql = "SELECT * FROM consumption_buckets WHERE bucket_key = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, bucketKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return readBucketFromResultSet(rs);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get consumption bucket: " + bucketKey, e);
            reconnect();
        }
        return null;
    }

    /**
     * Clear all consumption buckets. Called when nominal capacity changes
     * significantly (e.g., wrong capacity was detected previously) to prevent
     * poisoned consumption rates from corrupting range estimates.
     */
    public void clearConsumptionBuckets() {
        if (!ensureConnection()) return;
        try (Statement stmt = connection.createStatement()) {
            int deleted = stmt.executeUpdate("DELETE FROM consumption_buckets");
            logger.info("Cleared " + deleted + " consumption buckets (capacity changed)");
        } catch (Exception e) {
            logger.error("Failed to clear consumption buckets", e);
        }
    }

    /**
     * Get the overall average across all consumption buckets.
     * Aggregates: sum all sums / sum all counts.
     */
    public ConsumptionBucket getOverallAverage() {
        if (!ensureConnection()) return null;

        String sql = "SELECT SUM(sample_count) as total_count, " +
                "SUM(sum_kwh_per_km) as total_sum, " +
                "SUM(sum_squared_kwh_per_km) as total_sum_sq " +
                "FROM consumption_buckets";

        try (PreparedStatement pstmt = connection.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                int totalCount = rs.getInt("total_count");
                if (totalCount == 0) return null;

                ConsumptionBucket overall = new ConsumptionBucket();
                overall.bucketKey = "overall";
                overall.sampleCount = totalCount;
                overall.sumKwhPerKm = rs.getDouble("total_sum");
                overall.sumSquaredKwhPerKm = rs.getDouble("total_sum_sq");
                return overall;
            }
        } catch (Exception e) {
            logger.error("Failed to get overall consumption average", e);
            reconnect();
        }
        return null;
    }

    // ==================== HELPERS ====================

    /**
     * Read a TripRecord from a ResultSet row.
     */
    private TripRecord readTripFromResultSet(ResultSet rs) throws Exception {
        TripRecord trip = new TripRecord();
        trip.id = rs.getLong("id");
        trip.startTime = rs.getLong("start_time");
        trip.endTime = rs.getLong("end_time");
        trip.distanceKm = rs.getDouble("distance_km");
        trip.durationSeconds = rs.getInt("duration_seconds");
        trip.avgSpeedKmh = rs.getDouble("avg_speed_kmh");
        trip.maxSpeedKmh = rs.getInt("max_speed_kmh");
        trip.socStart = rs.getDouble("soc_start");
        trip.socEnd = rs.getDouble("soc_end");
        trip.kwhStart = rs.getDouble("kwh_start");
        trip.kwhEnd = rs.getDouble("kwh_end");
        trip.energyPerKm = rs.getDouble("energy_per_km");
        trip.electricityRate = rs.getDouble("electricity_rate");
        trip.currency = rs.getString("currency");
        trip.tripCost = rs.getDouble("trip_cost");
        trip.kinematicState = rs.getString("kinematic_state");
        try { trip.gradientProfile = rs.getString("gradient_profile"); } catch (Exception e) { trip.gradientProfile = ""; }
        try { trip.elevationGainM = rs.getDouble("elevation_gain_m"); } catch (Exception e) { trip.elevationGainM = 0; }
        try { trip.elevationLossM = rs.getDouble("elevation_loss_m"); } catch (Exception e) { trip.elevationLossM = 0; }
        try { trip.avgGradientPercent = rs.getDouble("avg_gradient_pct"); } catch (Exception e) { trip.avgGradientPercent = 0; }
        trip.efficiencySocPerKm = rs.getDouble("efficiency_soc_per_km");
        trip.startLat = rs.getDouble("start_lat");
        trip.startLon = rs.getDouble("start_lon");
        trip.endLat = rs.getDouble("end_lat");
        trip.endLon = rs.getDouble("end_lon");
        trip.extTempC = rs.getInt("ext_temp_c");
        trip.anticipationScore = rs.getInt("anticipation_score");
        trip.smoothnessScore = rs.getInt("smoothness_score");
        trip.speedDisciplineScore = rs.getInt("speed_discipline_score");
        trip.efficiencyScore = rs.getInt("efficiency_score");
        trip.consistencyScore = rs.getInt("consistency_score");
        trip.microMomentsJson = rs.getString("micro_moments_json");
        trip.telemetryFilePath = rs.getString("telemetry_file_path");
        try { trip.routeId = rs.getLong("route_id"); } catch (Exception e) { trip.routeId = -1; }
        return trip;
    }

    /**
     * Set PreparedStatement parameters for a TripRecord (positions 1-28).
     */
    private void setTripParams(PreparedStatement pstmt, TripRecord trip) throws Exception {
        pstmt.setLong(1, trip.startTime);
        pstmt.setLong(2, trip.endTime);
        pstmt.setDouble(3, trip.distanceKm);
        pstmt.setInt(4, trip.durationSeconds);
        pstmt.setDouble(5, trip.avgSpeedKmh);
        pstmt.setInt(6, trip.maxSpeedKmh);
        pstmt.setDouble(7, trip.socStart);
        pstmt.setDouble(8, trip.socEnd);
        pstmt.setDouble(9, trip.kwhStart);
        pstmt.setDouble(10, trip.kwhEnd);
        pstmt.setDouble(11, trip.energyPerKm);
        pstmt.setDouble(12, trip.electricityRate);
        pstmt.setString(13, trip.currency != null ? trip.currency : "");
        pstmt.setDouble(14, trip.tripCost);
        pstmt.setString(15, trip.kinematicState != null ? trip.kinematicState : "");
        pstmt.setString(16, trip.gradientProfile != null ? trip.gradientProfile : "");
        pstmt.setDouble(17, trip.elevationGainM);
        pstmt.setDouble(18, trip.elevationLossM);
        pstmt.setDouble(19, trip.avgGradientPercent);
        pstmt.setDouble(20, trip.efficiencySocPerKm);
        pstmt.setDouble(21, trip.startLat);
        pstmt.setDouble(22, trip.startLon);
        pstmt.setDouble(23, trip.endLat);
        pstmt.setDouble(24, trip.endLon);
        pstmt.setInt(25, trip.extTempC);
        pstmt.setInt(26, trip.anticipationScore);
        pstmt.setInt(27, trip.smoothnessScore);
        pstmt.setInt(28, trip.speedDisciplineScore);
        pstmt.setInt(29, trip.efficiencyScore);
        pstmt.setInt(30, trip.consistencyScore);
        pstmt.setString(31, trip.microMomentsJson);
        pstmt.setString(32, trip.telemetryFilePath);
    }

    /**
     * Read a WeeklyRollup from a ResultSet row.
     */
    private WeeklyRollup readWeeklyRollupFromResultSet(ResultSet rs) throws Exception {
        WeeklyRollup rollup = new WeeklyRollup();
        rollup.year = rs.getInt("year");
        rollup.weekNumber = rs.getInt("week_number");
        rollup.tripCount = rs.getInt("trip_count");
        rollup.totalDistanceKm = rs.getDouble("total_distance_km");
        rollup.totalDurationSeconds = rs.getInt("total_duration_seconds");
        rollup.avgEfficiency = rs.getDouble("avg_efficiency");
        rollup.totalEnergyKwh = rs.getDouble("total_energy_kwh");
        rollup.totalCost = rs.getDouble("total_cost");
        rollup.avgEnergyPerKm = rs.getDouble("avg_energy_per_km");
        rollup.avgAnticipation = rs.getInt("avg_anticipation");
        rollup.avgSmoothness = rs.getInt("avg_smoothness");
        rollup.avgSpeedDiscipline = rs.getInt("avg_speed_discipline");
        rollup.avgEfficiencyScore = rs.getInt("avg_efficiency_score");
        rollup.avgConsistency = rs.getInt("avg_consistency");
        return rollup;
    }

    /**
     * Read a MonthlyRollup from a ResultSet row.
     */
    private MonthlyRollup readMonthlyRollupFromResultSet(ResultSet rs) throws Exception {
        MonthlyRollup rollup = new MonthlyRollup();
        rollup.year = rs.getInt("year");
        rollup.month = rs.getInt("month_number");
        rollup.tripCount = rs.getInt("trip_count");
        rollup.totalDistanceKm = rs.getDouble("total_distance_km");
        rollup.totalDurationSeconds = rs.getInt("total_duration_seconds");
        rollup.avgEfficiency = rs.getDouble("avg_efficiency");
        rollup.totalEnergyKwh = rs.getDouble("total_energy_kwh");
        rollup.totalCost = rs.getDouble("total_cost");
        rollup.avgEnergyPerKm = rs.getDouble("avg_energy_per_km");
        rollup.avgAnticipation = rs.getInt("avg_anticipation");
        rollup.avgSmoothness = rs.getInt("avg_smoothness");
        rollup.avgSpeedDiscipline = rs.getInt("avg_speed_discipline");
        rollup.avgEfficiencyScore = rs.getInt("avg_efficiency_score");
        rollup.avgConsistency = rs.getInt("avg_consistency");
        return rollup;
    }

    /**
     * Read a ConsumptionBucket from a ResultSet row.
     */
    private ConsumptionBucket readBucketFromResultSet(ResultSet rs) throws Exception {
        ConsumptionBucket bucket = new ConsumptionBucket();
        bucket.bucketKey = rs.getString("bucket_key");
        bucket.sampleCount = rs.getInt("sample_count");
        bucket.sumKwhPerKm = rs.getDouble("sum_kwh_per_km");
        bucket.sumSquaredKwhPerKm = rs.getDouble("sum_squared_kwh_per_km");
        return bucket;
    }

    /**
     * Get a monthly rollup by year and month (internal helper for updateMonthlyRollup).
     */
    private MonthlyRollup getMonthlyRollup(int year, int month) {
        String sql = "SELECT * FROM monthly_rollups WHERE \"year\"=? AND month_number=?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, year);
            pstmt.setInt(2, month);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return readMonthlyRollupFromResultSet(rs);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get monthly rollup year=" + year + " month=" + month, e);
        }
        return null;
    }

    /**
     * Compute running average: (oldAvg * oldCount + newValue) / (oldCount + 1)
     */
    private double runningAvg(double oldAvg, int oldCount, double newValue) {
        return (oldAvg * oldCount + newValue) / (oldCount + 1);
    }

    /**
     * Delete orphaned trips — trips with end_time == 0 or duration_seconds == 0
     * that are older than the given cutoff. These are leftovers from daemon crashes
     * mid-trip. Returns the number of deleted rows.
     */
    public int deleteOrphanedTrips(long olderThanMs) {
        if (!ensureConnection()) return 0;
        try (PreparedStatement pstmt = connection.prepareStatement(
                "DELETE FROM trips WHERE (end_time = 0 OR duration_seconds = 0) AND start_time < ?")) {
            pstmt.setLong(1, olderThanMs);
            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                logger.info("Cleaned up " + deleted + " orphaned trip(s)");
            }
            return deleted;
        } catch (Exception e) {
            logger.error("Failed to delete orphaned trips: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Check if the database is initialized and available.
     */
    public boolean isAvailable() {
        return isInitialized && connection != null;
    }
}
