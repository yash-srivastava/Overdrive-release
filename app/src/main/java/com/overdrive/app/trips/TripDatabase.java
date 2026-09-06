package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * H2 embedded database for trip catalog, rollups, and consumption buckets.
 * Follows the same pattern as SocHistoryDatabase but uses a separate DB file.
 */
public class TripDatabase {

    private static final String TAG = "TripDatabase";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String DB_PATH = "/data/local/tmp/overdrive_trips_h2";
    // DB_CLOSE_ON_EXIT=FALSE: avoid H2's JVM shutdown hook racing the
    // daemon's explicit close path. Without this we hit the same orphaned-
    // lock-file pattern as SocHistoryDatabase, which blocks the next
    // CameraDaemon start with "Locked by another process".
    //
    // AUTO_SERVER omitted — incompatible with DB_CLOSE_ON_EXIT=FALSE
    // (H2 throws JdbcSQLFeatureNotSupportedException at init). Single-
    // process architecture: only CameraDaemon writes, TripApiHandler reads
    // from the same JVM. FILE_LOCK=SOCKET handles cross-process safety.
    // AUTO_COMPACT_FILL_RATE=50: idle-CPU tuning shared by all seven H2 stores
    // (see SocHistoryDatabase.JDBC_URL for the full rationale).
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
            ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE" +
            ";AUTO_COMPACT_FILL_RATE=50";

    // volatile: reassigned by reconnect() (now synchronized); the fence gives a
    // happens-before edge so any reader sees the freshly-swapped connection.
    private volatile Connection connection;
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

                // Kick off the one-shot size_bytes backfill for legacy
                // rows. Runs on its own daemon thread, no-op when every
                // row already has a size, never blocks init.
                runBackfillIfNeeded();
                return;

            } catch (Exception e) {
                // Same invariant as reconnect()/forceReconnect(): SET CACHE_SIZE or
                // createTables() can throw AFTER the handle is live. Left assigned,
                // a table-less handle passes ensureConnection() (isClosed()==false,
                // SELECT 1 needs no table) and every DAO fails "table not found"
                // forever. On the retry path it also still holds the H2 file lock —
                // the very thing the retry is waiting out. Close and null it first.
                if (connection != null) {
                    try { connection.close(); } catch (Exception ignored) { }
                    connection = null;
                }
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

    // synchronized: this NULLS the shared `connection` field, exactly like
    // reconnect() reassigns it — so it must take the same monitor every CRUD
    // method and ensureConnection() hold. Without it, a worker that just passed
    // ensureConnection()==true could NPE on connection.prepareStatement, have its
    // catch call reconnect(), and RE-OPEN the store with isInitialized=true after
    // a deliberate close — leaving a half-dead state where writes are possible but
    // no trip will ever start.
    public synchronized void close() {
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

    // synchronized: reconnect() REASSIGNS the shared `connection` field. The
    // single-shared-connection invariant requires that swap be serialized
    // against every monitor-holding query, or a reconnect on one thread could
    // replace the connection under another thread's in-flight PreparedStatement/
    // ResultSet ("object is already closed"). The monitor is reentrant, so the
    // synchronized CRUD methods that call this while already holding it nest
    // fine, and callers that invoke ensureConnection() before their own
    // synchronized block now acquire the monitor for the check+swap.
    private synchronized void reconnect() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                // Idempotent (IF NOT EXISTS throughout). A reopen against a wiped
                // or replaced .mv.db would otherwise yield a table-less store that
                // probe() still calls healthy (SELECT 1 needs no table), turning
                // every DAO call into "table not found" instead of self-healing.
                createTables();
                isInitialized = true;
                logger.info("H2 trip database connection re-established");
            }
        } catch (Exception e) {
            logger.error("Failed to reconnect to H2 trip database", e);
            // createTables() can throw AFTER the handle is live (corrupt store,
            // full disk, read-only mount). Leaving that handle assigned would make
            // the next ensureConnection() pass — isClosed()==false and probe()'s
            // SELECT 1 needs no table — so every DAO would fail "table not found"
            // permanently with no further reopen attempt. Close and null it so
            // the next call retries from scratch instead of trusting a possibly
            // table-less store.
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { }
                connection = null;
            }
        }
    }

    // Liveness check that actually talks to the engine. Connection.isClosed()
    // reports only whether close() was called on THIS Connection object — it
    // knows nothing about the H2 engine behind it. If the engine shut down
    // underneath the handle (e.g. H2's own shutdown hook fired), isClosed()
    // keeps returning false while every real statement throws.
    private synchronized boolean probe() {
        if (connection == null) return false;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Drop a handle that failed probe() and reopen. Only reachable from the
    // already-synchronized ensureConnection(), AFTER its
    // `!isInitialized && connection == null` guard has passed — so this cannot
    // resurrect a deliberately close()d store (see close() above).
    private synchronized void forceReconnect() {
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) { }
            connection = null;
        }
        try {
            connection = DriverManager.getConnection(JDBC_URL, "sa", "");
            createTables();   // idempotent; see reconnect()
            isInitialized = true;
            logger.warn("H2 trip database force-reconnected after failed liveness probe");
        } catch (Exception e) {
            logger.error("Force reconnect failed", e);
            if (connection != null) {
                try { connection.close(); } catch (Exception ignored) { }
                connection = null;
            }
        }
    }

    /**
     * Ensure the database connection is alive. Returns true if ready.
     * Attempts reconnection if the connection is closed, and force-reconnects
     * if the handle claims to be open but fails a liveness {@link #probe()}.
     * synchronized for the same reason as {@link #reconnect()} — it reads +
     * (via reconnect/forceReconnect) writes the shared connection field.
     */
    private synchronized boolean ensureConnection() {
        if (!isInitialized && connection == null) return false;
        try {
            if (connection == null || connection.isClosed()) {
                logger.info("Database connection closed, reconnecting...");
                reconnect();
                return probe();
            }
            // isClosed()==false is NOT proof of life (see probe()). Verify with
            // a real statement; on failure drop the dead handle and reopen.
            if (probe()) return true;
            logger.warn("Connection reports open but failed liveness probe — force-reconnecting");
            forceReconnect();
            return probe();
        } catch (Exception e) {
            logger.error("Connection check failed", e);
            forceReconnect();
            return probe();
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
            // Live-trip checkpoint: periodically refreshed while a trip is
            // ACTIVE (see TripDetector's checkpoint timer), cleared on normal
            // completion. `trips` itself can't hold an open/in-progress row
            // (end_time is NOT NULL, and every reader assumes a completed
            // trip), so this is a separate, deliberately tiny table — a mid-
            // drive daemon crash previously left NOTHING durable except the
            // GPS telemetry file, so recovery could only reconstruct
            // distance/speed/elevation and never energy/SoC. One row per
            // in-progress trip; start_time is the same join key `trips` uses.
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS trip_checkpoints (" +
                "start_time BIGINT PRIMARY KEY," +
                "soc_start REAL," +
                "kwh_start REAL," +
                "elec_con_start REAL," +
                "soc_now REAL," +
                "kwh_now REAL," +
                "elec_con_now REAL," +
                "checkpoint_at_ms BIGINT" +
                ")"
            );

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
                "telemetry_file_path VARCHAR(512)," +
                "is_phev BOOLEAN DEFAULT FALSE," +
                "fuel_pct_start REAL DEFAULT -1," +
                "fuel_pct_end REAL DEFAULT -1," +
                "litres_used REAL DEFAULT 0," +
                "fuel_price_per_l REAL DEFAULT 0," +
                "fuel_cost REAL DEFAULT 0," +
                "electric_cost REAL DEFAULT 0," +
                "ice_seconds INTEGER DEFAULT 0," +
                "fuel_con_start REAL DEFAULT -1," +
                "fuel_con_end REAL DEFAULT -1," +
                // Absolute vehicle odometer (km) at trip start/end. distance_km
                // is their delta; these are the readings shown on the trip UI.
                // 0 = odometer was unavailable at that edge (recovered/legacy row).
                "odometer_start_km REAL DEFAULT 0," +
                "odometer_end_km REAL DEFAULT 0," +
                // Cumulative HAL electricity-consumption counter (kWh) at trip
                // start/end — the electric twin of fuel_con_*. Its delta is the
                // metered kWh drawn, the only electric-energy source with enough
                // resolution to measure a short trip. -1 = not captured.
                "elec_con_start REAL DEFAULT -1," +
                "elec_con_end REAL DEFAULT -1" +
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

            // Migration: PHEV / fuel-cost columns. Pre-PHEV trips read these
            // as defaults (is_phev=false, fuel_pct=-1, costs=0) which the
            // BEV path already treats as "no fuel data" — no regression.
            try {
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS is_phev BOOLEAN DEFAULT FALSE");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS fuel_pct_start REAL DEFAULT -1");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS fuel_pct_end REAL DEFAULT -1");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS litres_used REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS fuel_price_per_l REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS fuel_cost REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS electric_cost REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS ice_seconds INTEGER DEFAULT 0");
            } catch (Exception e) {
                logger.debug("trips fuel column migration: " + e.getMessage());
            }

            // Migration: cumulative HAL fuel-accumulator snapshots. Old rows
            // read these as -1 (sentinel "no accumulator"), so the fuel math
            // falls back to the legacy fuelPct×tank estimate — no regression.
            try {
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS fuel_con_start REAL DEFAULT -1");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS fuel_con_end REAL DEFAULT -1");
            } catch (Exception e) {
                logger.debug("trips fuel-accumulator column migration: " + e.getMessage());
            }

            // Migration: absolute odometer snapshots at trip start/end. Old rows
            // read these as 0 (sentinel "unavailable"), which the UI renders as
            // "--" — no regression, and distance_km (the delta) is unaffected.
            try {
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS odometer_start_km REAL DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS odometer_end_km REAL DEFAULT 0");
            } catch (Exception e) {
                logger.debug("trips odometer column migration: " + e.getMessage());
            }

            // Migration: cumulative HAL electricity-accumulator snapshots. Old
            // rows read these as -1 (sentinel "no accumulator"), so the energy
            // cascade falls back to the remaining-kWh delta exactly as before —
            // no regression, and no stored energy figure is rewritten.
            try {
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS elec_con_start REAL DEFAULT -1");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS elec_con_end REAL DEFAULT -1");
            } catch (Exception e) {
                logger.debug("trips elec-accumulator column migration: " + e.getMessage());
            }

            // Migration: provenance of electricity_rate. A trip is now priced at
            // the rate the LAST CHARGE was billed at (the energy it burned was
            // bought then), so we record which source supplied the rate and the
            // tariff label behind it. Old rows read '' — the UI treats that as
            // "no provenance chip" and shows cost exactly as it did before.
            try {
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS rate_source VARCHAR(16) DEFAULT ''");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS rate_label VARCHAR(64) DEFAULT ''");
            } catch (Exception e) {
                logger.debug("trips rate-source column migration: " + e.getMessage());
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

            // Migration: per-row file-size accounting. Lets StorageManager
            // answer getTripsSize() via SUM(size_bytes) instead of walking
            // every trips dir and stat()ing every .jsonl.gz file (which on
            // full storage with FUSE-bridged SD took 10-20 min). DEFAULT 0
            // is the cue for the one-shot backfill thread to fill the
            // column for legacy rows. sidecar_size_bytes is reserved —
            // current builds have no trip sidecars but the schema is
            // ready when one is introduced.
            try {
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS size_bytes BIGINT DEFAULT 0");
                stmt.execute("ALTER TABLE trips ADD COLUMN IF NOT EXISTS sidecar_size_bytes BIGINT DEFAULT 0");
            } catch (Exception e) {
                // Columns already exist or H2 version doesn't support IF NOT EXISTS
                logger.debug("trips size column migration: " + e.getMessage());
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

            // PHEV fuel-consumption buckets — same shape as the EV table but
            // stores litres-per-km. Populated only by trips classified as
            // FUEL-mode (ICE share ≥ 50%) with a non-zero litresUsed snapshot.
            // Table is empty on BEV vehicles; harmless. Idempotent CREATE so
            // re-runs after migration are no-ops.
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS fuel_consumption_buckets (" +
                "bucket_key VARCHAR(64) PRIMARY KEY," +
                "sample_count INTEGER DEFAULT 0," +
                "sum_litres_per_km REAL DEFAULT 0," +
                "sum_squared_litres_per_km REAL DEFAULT 0" +
                ")"
            );
        }
    }

    // ==================== TRIP CRUD (Task 7.2) ====================

    /**
     * Insert a new trip record and return the auto-generated id.
     */
    public synchronized long insertTrip(TripRecord trip) {
        if (!ensureConnection()) return -1;

        String sql = "INSERT INTO trips (start_time, end_time, distance_km, duration_seconds, " +
                "avg_speed_kmh, max_speed_kmh, soc_start, soc_end, kwh_start, kwh_end, energy_per_km, " +
                "electricity_rate, currency, trip_cost, kinematic_state, " +
                "gradient_profile, elevation_gain_m, elevation_loss_m, avg_gradient_pct, " +
                "efficiency_soc_per_km, " +
                "start_lat, start_lon, end_lat, end_lon, ext_temp_c, " +
                "anticipation_score, smoothness_score, speed_discipline_score, " +
                "efficiency_score, consistency_score, micro_moments_json, telemetry_file_path, route_id, " +
                "is_phev, fuel_pct_start, fuel_pct_end, litres_used, fuel_price_per_l, fuel_cost, " +
                "electric_cost, ice_seconds, fuel_con_start, fuel_con_end, size_bytes, sidecar_size_bytes, " +
                "odometer_start_km, odometer_end_km, rate_source, rate_label, " +
                "elec_con_start, elec_con_end) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            setTripParams(pstmt, trip);
            pstmt.setObject(33, trip.routeId > 0 ? trip.routeId : null);
            setTripFuelParams(pstmt, trip, 34);
            pstmt.setLong(44, trip.sizeBytes);
            pstmt.setLong(45, trip.sidecarSizeBytes);
            pstmt.setDouble(46, trip.odometerStartKm);
            pstmt.setDouble(47, trip.odometerEndKm);
            pstmt.setString(48, trip.rateSource != null ? trip.rateSource : "");
            pstmt.setString(49, trip.rateLabel != null ? trip.rateLabel : "");
            pstmt.setDouble(50, trip.elecConStart);
            pstmt.setDouble(51, trip.elecConEnd);
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

    // ==================== TRIP CHECKPOINT (crash recovery) ====================

    /**
     * Create or refresh the in-progress checkpoint for a live trip. Called once
     * at trip start and then periodically while ACTIVE (see TripDetector).
     * Cheap by design — six doubles and a timestamp, upserted on the trip's
     * start_time — so it can run every checkpoint tick without meaningfully
     * competing with the trip-file / telemetry I/O already happening live.
     */
    public synchronized void upsertTripCheckpoint(long startTime, double socStart, double kwhStart,
            double elecConStart, double socNow, double kwhNow, double elecConNow) {
        if (!ensureConnection() || startTime <= 0) return;
        try (PreparedStatement p = connection.prepareStatement(
                "MERGE INTO trip_checkpoints (start_time, soc_start, kwh_start, elec_con_start, " +
                "soc_now, kwh_now, elec_con_now, checkpoint_at_ms) KEY(start_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            p.setLong(1, startTime);
            p.setDouble(2, socStart);
            p.setDouble(3, kwhStart);
            p.setDouble(4, elecConStart);
            p.setDouble(5, socNow);
            p.setDouble(6, kwhNow);
            p.setDouble(7, elecConNow);
            p.setLong(8, System.currentTimeMillis());
            p.executeUpdate();
        } catch (Exception e) {
            logger.debug("upsertTripCheckpoint failed: " + e.getMessage());
        }
    }

    /** Remove a trip's checkpoint row. Called once the trip completes normally — no longer needed. */
    public synchronized void clearTripCheckpoint(long startTime) {
        if (!ensureConnection() || startTime <= 0) return;
        try (PreparedStatement p = connection.prepareStatement(
                "DELETE FROM trip_checkpoints WHERE start_time = ?")) {
            p.setLong(1, startTime);
            p.executeUpdate();
        } catch (Exception e) {
            logger.debug("clearTripCheckpoint failed: " + e.getMessage());
        }
    }

    /**
     * Checkpoint start_times with no matching row in `trips` — left behind by a
     * mid-trip daemon crash (normal completion always clears its own checkpoint
     * first). Matched on whole-second start_time, same tolerance the disk-file
     * recovery's dedup already uses for the identical reason (ms rounding).
     */
    public synchronized java.util.List<Long> orphanedTripCheckpointStartTimes() {
        java.util.List<Long> out = new java.util.ArrayList<>();
        if (!ensureConnection()) return out;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT c.start_time FROM trip_checkpoints c WHERE NOT EXISTS (" +
                "SELECT 1 FROM trips t WHERE t.start_time / 1000 = c.start_time / 1000)")) {
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        } catch (Exception e) {
            logger.debug("orphanedTripCheckpointStartTimes failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * {tripId, startTime} pairs where a checkpoint survives AND a `trips` row
     * now exists for that same start_time. Normal completion always clears its
     * checkpoint BEFORE insertTrip() runs (see TripAnalyticsManager.
     * handleTripEnded), so a checkpoint found alongside an existing row can
     * only mean that row was just created by disk-file recovery after a
     * mid-trip crash — the exact case that needs enrichment.
     */
    public synchronized java.util.List<long[]> checkpointedTripsNeedingEnrichment() {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        if (!ensureConnection()) return out;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT t.id, c.start_time FROM trip_checkpoints c " +
                "JOIN trips t ON t.start_time / 1000 = c.start_time / 1000")) {
            try (ResultSet rs = p.executeQuery()) {
                while (rs.next()) out.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
        } catch (Exception e) {
            logger.debug("checkpointedTripsNeedingEnrichment failed: " + e.getMessage());
        }
        return out;
    }

    /** One checkpoint's saved readings: {socStart, kwhStart, elecConStart, socNow, kwhNow, elecConNow}, or null. */
    public synchronized double[] getTripCheckpoint(long startTime) {
        if (!ensureConnection() || startTime <= 0) return null;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT soc_start, kwh_start, elec_con_start, soc_now, kwh_now, elec_con_now " +
                "FROM trip_checkpoints WHERE start_time = ?")) {
            p.setLong(1, startTime);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                return new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)};
            }
        } catch (Exception e) {
            logger.debug("getTripCheckpoint failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Best-effort enrichment for a just-recovered (GPS-only) trip row: if a
     * checkpoint survives for the same start_time, the crash happened after at
     * least one checkpoint tick, so real (if slightly stale) SoC/energy data
     * exists — fill it in instead of leaving the recovered row's energy fields
     * at the all-zero "recovered" default. Deliberately additive and separate
     * from the disk-file recovery pass itself (recoverTripsFromDiskLocked) —
     * that pass's dedup logic is already delicate, so this only ever runs
     * AFTER it, patching fields on a row that already exists, never touching
     * how that row got created or its dedup keys.
     */
    public synchronized void enrichRecoveredTripFromCheckpoint(long tripId, long startTime) {
        double[] cp = getTripCheckpoint(startTime);
        if (cp == null) return;
        double socStart = cp[0], kwhStart = cp[1], elecConStart = cp[2];
        double socNow = cp[3], kwhNow = cp[4], elecConNow = cp[5];
        double energyUsedKwh = -1;
        // Prefer the metered HAL counter delta (finer-grained than remaining-kWh,
        // which is derived from an integer SoC) — same preference order startTrip()
        // itself uses. Both sides must be real (>0) before trusting the delta.
        if (elecConStart > 0 && elecConNow > 0 && elecConNow >= elecConStart) {
            energyUsedKwh = elecConNow - elecConStart;
        } else if (kwhStart > 0 && kwhNow > 0 && kwhStart >= kwhNow) {
            energyUsedKwh = kwhStart - kwhNow;
        }
        if (!ensureConnection()) return;
        try (PreparedStatement p = connection.prepareStatement(
                "UPDATE trips SET soc_start = ?, soc_end = ?, kwh_start = ?, kwh_end = ?, " +
                "elec_con_start = ?, elec_con_end = ? WHERE id = ?")) {
            p.setDouble(1, socStart > 0 ? socStart : 0);
            p.setDouble(2, socNow > 0 ? socNow : 0);
            p.setDouble(3, kwhStart > 0 ? kwhStart : 0);
            p.setDouble(4, kwhNow > 0 ? kwhNow : 0);
            p.setDouble(5, elecConStart > 0 ? elecConStart : 0);
            p.setDouble(6, elecConNow > 0 ? elecConNow : 0);
            p.setLong(7, tripId);
            p.executeUpdate();
            logger.info("Enriched recovered trip id=" + tripId + " from checkpoint (energyUsedKwh~="
                    + (energyUsedKwh >= 0 ? String.format(java.util.Locale.US, "%.2f", energyUsedKwh) : "unknown") + ")");
        } catch (Exception e) {
            logger.debug("enrichRecoveredTripFromCheckpoint failed: " + e.getMessage());
        } finally {
            // Job done either way — a retry loop finding the same trip row
            // again next boot would just re-run this for no further benefit.
            clearTripCheckpoint(startTime);
        }
    }

    /**
     * Run after recoverTripsFromDisk(): enrich every just-recovered trip that
     * still has a surviving checkpoint with its real SoC/energy readings.
     * Safe to call unconditionally — a no-op when nothing needs enrichment.
     */
    public void enrichRecoveredTripsFromCheckpoints() {
        for (long[] pair : checkpointedTripsNeedingEnrichment()) {
            enrichRecoveredTripFromCheckpoint(pair[0], pair[1]);
        }
    }

    /**
     * Update all fields of an existing trip by id.
     */
    public synchronized void updateTrip(TripRecord trip) {
        if (!ensureConnection()) return;

        String sql = "UPDATE trips SET start_time=?, end_time=?, distance_km=?, duration_seconds=?, " +
                "avg_speed_kmh=?, max_speed_kmh=?, soc_start=?, soc_end=?, kwh_start=?, kwh_end=?, energy_per_km=?, " +
                "electricity_rate=?, currency=?, trip_cost=?, kinematic_state=?, " +
                "gradient_profile=?, elevation_gain_m=?, elevation_loss_m=?, avg_gradient_pct=?, " +
                "efficiency_soc_per_km=?, " +
                "start_lat=?, start_lon=?, end_lat=?, end_lon=?, ext_temp_c=?, " +
                "anticipation_score=?, smoothness_score=?, speed_discipline_score=?, " +
                "efficiency_score=?, consistency_score=?, micro_moments_json=?, telemetry_file_path=?, " +
                "route_id=?, " +
                "is_phev=?, fuel_pct_start=?, fuel_pct_end=?, litres_used=?, fuel_price_per_l=?, " +
                "fuel_cost=?, electric_cost=?, ice_seconds=?, fuel_con_start=?, fuel_con_end=?, " +
                "size_bytes=?, sidecar_size_bytes=?, odometer_start_km=?, odometer_end_km=?, " +
                "rate_source=?, rate_label=?, elec_con_start=?, elec_con_end=? " +
                "WHERE id=?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            setTripParams(pstmt, trip);
            pstmt.setObject(33, trip.routeId > 0 ? trip.routeId : null);
            setTripFuelParams(pstmt, trip, 34);
            pstmt.setLong(44, trip.sizeBytes);
            pstmt.setLong(45, trip.sidecarSizeBytes);
            pstmt.setDouble(46, trip.odometerStartKm);
            pstmt.setDouble(47, trip.odometerEndKm);
            pstmt.setString(48, trip.rateSource != null ? trip.rateSource : "");
            pstmt.setString(49, trip.rateLabel != null ? trip.rateLabel : "");
            pstmt.setDouble(50, trip.elecConStart);
            pstmt.setDouble(51, trip.elecConEnd);
            pstmt.setLong(52, trip.id);
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
    public synchronized TripRecord getTrip(long id) {
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
     *
     * <p>Backwards-compatible overload — equivalent to {@link #getTrips(int, int, int)}
     * with offset=0. Kept so callers that don't paginate (rollups, route detection)
     * continue to work without changes.
     */
    public List<TripRecord> getTrips(int days, int limit) {
        return getTrips(days, limit, 0);
    }

    /**
     * Paginated variant: skip the first {@code offset} rows of the time-ordered
     * result set, then return up to {@code limit} rows. Used by the trips list
     * UI's Load More button.
     *
     * <p>Pagination is offset-based, not cursor-based — fine for the trips
     * table where new rows are only appended (never inserted in the middle of
     * history). A cursor would only matter if a sibling client could insert
     * older rows during a paging session.
     */
    public synchronized List<TripRecord> getTrips(int days, int limit, int offset) {
        List<TripRecord> trips = new ArrayList<>();
        if (!ensureConnection()) return trips;
        if (offset < 0) offset = 0;

        long cutoff = System.currentTimeMillis() - ((long) days * 86400000L);
        String sql = "SELECT * FROM trips WHERE start_time >= ? ORDER BY start_time DESC LIMIT ? OFFSET ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, cutoff);
            pstmt.setInt(2, limit);
            pstmt.setInt(3, offset);
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
     * List trips whose start_time falls within an explicit [fromMs, toMs]
     * window (both epoch ms, inclusive), newest first. Backs the custom
     * date-range filter in the Trips UI, complementing the "last N days"
     * {@link #getTrips(int, int, int)} path.
     *
     * <p>Both bounds are clamped defensively: a swapped pair is reordered so
     * callers can't produce an empty window by accident, and a non-positive
     * upper bound falls back to "now" so an open-ended range still returns
     * recent trips. The {@code start_time} index makes the range scan cheap.
     */
    public synchronized List<TripRecord> getTripsBetween(long fromMs, long toMs, int limit, int offset) {
        List<TripRecord> trips = new ArrayList<>();
        if (!ensureConnection()) return trips;
        if (offset < 0) offset = 0;
        if (toMs <= 0) toMs = System.currentTimeMillis();
        if (fromMs > toMs) { long t = fromMs; fromMs = toMs; toMs = t; }

        String sql = "SELECT * FROM trips WHERE start_time >= ? AND start_time <= ? "
                + "ORDER BY start_time DESC LIMIT ? OFFSET ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, fromMs);
            pstmt.setLong(2, toMs);
            pstmt.setInt(3, limit);
            pstmt.setInt(4, offset);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    trips.add(readTripFromResultSet(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get trips in range", e);
            reconnect();
        }
        return trips;
    }

    /**
     * Delete a trip by id. Returns true if a row was deleted.
     *
     * <p>Reads {@code start_time} first so the weekly/monthly rollup for that
     * trip's period can be recomputed after the row is gone. Rollups are
     * additive on save and are not reversible, so the period is rebuilt from
     * remaining trips rather than subtracting this one.
     */
    public synchronized boolean deleteTrip(long id) {
        if (!ensureConnection()) return false;

        Long startTime = null;
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT start_time FROM trips WHERE id=?")) {
            pstmt.setLong(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) startTime = rs.getLong(1);
            }
        } catch (Exception e) {
            logger.error("Failed to read trip start_time before delete id=" + id, e);
            reconnect();
            return false;
        }
        if (startTime == null) return false;

        String sql = "DELETE FROM trips WHERE id=?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, id);
            int rows = pstmt.executeUpdate();
            if (rows > 0) {
                logger.info("Deleted trip id=" + id);
                List<Long> startTimes = new ArrayList<>();
                startTimes.add(startTime);
                recomputeRollupsForStartTimes(startTimes);
                return true;
            }
        } catch (Exception e) {
            logger.error("Failed to delete trip id=" + id, e);
            reconnect();
        }
        return false;
    }

    /**
     * Delete the trip row(s) whose telemetry file path matches {@code absPath}.
     * Returns the number of rows deleted.
     *
     * <p>Used by the StorageManager retention reaper: when ensureSpace deletes a
     * trips-category {@code .jsonl.gz} file off disk, the corresponding DB row
     * must also go, otherwise {@link #getTotalSizeBytes()} (the SUM the storage
     * limit gate reads) stays permanently inflated by the deleted trip's bytes
     * and the gate never converges. Mirrors the .mp4 → RecordingsIndex.remove()
     * call the reaper already makes for recordings.
     */
    public synchronized int deleteByTelemetryPath(String absPath) {
        if (absPath == null || !ensureConnection()) return 0;

        List<Long> startTimes = new ArrayList<>();
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT start_time FROM trips WHERE telemetry_file_path=?")) {
            pstmt.setString(1, absPath);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) startTimes.add(rs.getLong(1));
            }
        } catch (Exception e) {
            logger.error("Failed to read trip start_time before path delete: " + absPath, e);
            reconnect();
            return 0;
        }
        if (startTimes.isEmpty()) return 0;

        String sql = "DELETE FROM trips WHERE telemetry_file_path=?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, absPath);
            int rows = pstmt.executeUpdate();
            if (rows > 0) {
                logger.info("Deleted trip row(s) by telemetry path: " + absPath + " (" + rows + ")");
                recomputeRollupsForStartTimes(startTimes);
            }
            return rows;
        } catch (Exception e) {
            logger.error("Failed to delete trip by telemetry path: " + absPath, e);
            reconnect();
        }
        return 0;
    }

    /**
     * Reconcile DB rows against disk: delete any trip row whose
     * {@code telemetry_file_path} no longer exists on disk. Returns the number
     * of rows removed.
     *
     * <p>Closes a gap the disk-walking reaper can't: {@link #deleteByTelemetryPath}
     * is only called when {@code ensureSpace} finds the matching {@code .jsonl.gz}
     * file on disk and deletes it. If the file vanished out-of-band (file-manager
     * delete, SD/volume swap, or a crash between TripApiHandler's file-delete and
     * row-delete), the row survives, {@link #getTotalSizeBytes()} (the SUM the
     * storage limit gate reads) stays inflated, and the gate fires every 30s while
     * the disk walk frees nothing — a non-converging no-op. Dropping the orphan
     * rows here lets the gate size reflect only bytes that are actually reapable.
     *
     * <p>A null/blank path is treated as missing (no file backs it). O(rows) with
     * a {@code File.exists()} stat each; called only from the trips reap path when
     * the gate believes trips is over-limit, not on every tick.
     *
     * <p><b>CRITICAL — volume-availability guard:</b> a trip's {@code .jsonl.gz}
     * can live on the SD card (or USB). When that volume is unmounted /
     * undetected, EVERY file on it reads as missing via {@code File.exists()},
     * so a naive reconcile would mass-delete the user's entire trip history the
     * first time the gate runs while the card is dropped. The {@code volumeUp}
     * predicate (supplied by StorageManager, which owns mount state) gates each
     * deletion: a row is reaped only if its file is missing AND the volume that
     * should hold it is confirmed available. When {@code volumeUp} is null
     * (caller can't tell) we FAIL SAFE and only reap null/blank paths (rows that
     * never had a file at all) — never a path that points at a real volume.
     */
    public int deleteRowsWithMissingFiles() {
        return deleteRowsWithMissingFiles(null);
    }

    /**
     * @param volumeUp predicate: given a telemetry file path, returns true iff
     *                 the storage volume that path lives on is currently mounted
     *                 and readable. Null = unknown → fail safe (see class note).
     */
    public int deleteRowsWithMissingFiles(java.util.function.Predicate<String> volumeUp) {
        if (!ensureConnection()) return 0;

        synchronized (this) {
            // Two buckets: null/blank-path rows never had a file (always safe to
            // reap), vs file-missing-while-volume-up rows. The latter is the only
            // category vulnerable to a transient FUSE stat false-negative (a file
            // that exists() returns false for under SD/FUSE contention even though
            // the volume root probe is green), so we CAP how many of THOSE we
            // delete in a single pass: a true delete is incremental (one or a few
            // per 30s tick), whereas a contention storm flips many at once. If a
            // pass would reap more than the cap, that smells like a storm, so we
            // abort the risky deletions and preserve those rows (recovery can
            // rebuild a genuinely-gone one later; a wrongly-kept row just lingers).
            List<long[]> safeOrphans = new ArrayList<>();      // [id, start_time]
            List<long[]> missingFileOrphans = new ArrayList<>(); // file gone, vol up
            String sel = "SELECT id, telemetry_file_path, start_time FROM trips";
            try (PreparedStatement pstmt = connection.prepareStatement(sel);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String path = rs.getString("telemetry_file_path");
                    long[] row = new long[] { rs.getLong("id"), rs.getLong("start_time") };
                    // Imported (stats-only) rows legitimately have no on-disk
                    // telemetry file — they carry the IMPORTED_PATH sentinel.
                    // Do NOT treat them as orphans, or a restore would be wiped
                    // the next time the trips category goes over its limit.
                    if (isImportedPath(path)) continue;
                    // A null/blank path never had a backing file — always reapable.
                    if (path == null || path.isEmpty()) {
                        safeOrphans.add(row);
                        continue;
                    }
                    // A non-blank path: only reap if the file is genuinely gone
                    // AND its volume is up. If the volume is down (SD dropped),
                    // the file APPEARS missing but the row must be PRESERVED —
                    // it'll reappear when the card remounts. volumeUp==null →
                    // unknown → preserve (fail safe).
                    boolean volAvailable = (volumeUp == null) ? false : volumeUp.test(path);
                    if (volAvailable && !new java.io.File(path).exists()) {
                        missingFileOrphans.add(row);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to scan trips for missing files", e);
                reconnect();
                return 0;
            }

            // Storm guard: a missing-file sweep above MAX_MISSING_FILE_REAP_PER_PASS
            // is far more likely a FUSE-contention stat storm than that many trips
            // were genuinely deleted between two 30s ticks. Drop the whole risky
            // batch this pass (the null/blank-path orphans are still safe to reap).
            if (missingFileOrphans.size() > MAX_MISSING_FILE_REAP_PER_PASS) {
                logger.warn("Trips reconcile: " + missingFileOrphans.size()
                        + " files stat-missing while volume up — exceeds cap "
                        + MAX_MISSING_FILE_REAP_PER_PASS + "; treating as a transient "
                        + "stat storm and preserving those rows this pass");
                missingFileOrphans.clear();
            }

            List<long[]> orphanRows = new ArrayList<>(safeOrphans);
            orphanRows.addAll(missingFileOrphans);
            if (orphanRows.isEmpty()) return 0;

            int deleted = 0;
            String del = "DELETE FROM trips WHERE id=?";
            try (PreparedStatement pstmt = connection.prepareStatement(del)) {
                for (long[] row : orphanRows) {
                    pstmt.setLong(1, row[0]);
                    deleted += pstmt.executeUpdate();
                }
            } catch (Exception e) {
                logger.error("Failed to delete orphan trip rows", e);
                reconnect();
            }
            if (deleted > 0) {
                logger.info("Reconciled trips DB: removed " + deleted
                        + " row(s) whose telemetry file was gone from disk");
                List<Long> startTimes = new ArrayList<>(orphanRows.size());
                for (long[] row : orphanRows) startTimes.add(row[1]);
                recomputeRollupsForStartTimes(startTimes);
            }
            return deleted;
        }
    }

    /**
     * Get the total number of trips in the database.
     */
    public synchronized int getTripCount() {
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
    public synchronized long findOrCreateRoute(double startLat, double startLon, double endLat, double endLon, double distanceKm) {
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
    public synchronized java.util.List<TripRecord> getTripsByRoute(long routeId, int limit) {
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
    public synchronized void backfillRouteIds() {
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
    public synchronized void updateWeeklyRollup(TripRecord trip) {
        if (!ensureConnection()) return;

        Calendar cal = newTripPeriodCalendar();
        cal.setTimeInMillis(trip.startTime);
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
                    pstmt.setDouble(6, trip.getResolvedEnergyKwh());
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
                    pstmt.setDouble(5, existing.totalEnergyKwh + trip.getResolvedEnergyKwh());
                    pstmt.setDouble(6, existing.totalCost + trip.tripCost);
                    pstmt.setDouble(7, runningAvg(existing.avgEnergyPerKm, oldCount, trip.energyPerKm));
                    pstmt.setInt(8, (int) Math.round(runningAvg(existing.avgAnticipation, oldCount, trip.anticipationScore)));
                    pstmt.setInt(9, (int) Math.round(runningAvg(existing.avgSmoothness, oldCount, trip.smoothnessScore)));
                    pstmt.setInt(10, (int) Math.round(runningAvg(existing.avgSpeedDiscipline, oldCount, trip.speedDisciplineScore)));
                    pstmt.setInt(11, (int) Math.round(runningAvg(existing.avgEfficiencyScore, oldCount, trip.efficiencyScore)));
                    pstmt.setInt(12, (int) Math.round(runningAvg(existing.avgConsistency, oldCount, trip.consistencyScore)));
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
    public synchronized void updateMonthlyRollup(TripRecord trip) {
        if (!ensureConnection()) return;

        Calendar cal = newTripPeriodCalendar();
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
                    pstmt.setDouble(6, trip.getResolvedEnergyKwh());
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
                    pstmt.setDouble(5, existing.totalEnergyKwh + trip.getResolvedEnergyKwh());
                    pstmt.setDouble(6, existing.totalCost + trip.tripCost);
                    pstmt.setDouble(7, runningAvg(existing.avgEnergyPerKm, oldCount, trip.energyPerKm));
                    pstmt.setInt(8, (int) Math.round(runningAvg(existing.avgAnticipation, oldCount, trip.anticipationScore)));
                    pstmt.setInt(9, (int) Math.round(runningAvg(existing.avgSmoothness, oldCount, trip.smoothnessScore)));
                    pstmt.setInt(10, (int) Math.round(runningAvg(existing.avgSpeedDiscipline, oldCount, trip.speedDisciplineScore)));
                    pstmt.setInt(11, (int) Math.round(runningAvg(existing.avgEfficiencyScore, oldCount, trip.efficiencyScore)));
                    pstmt.setInt(12, (int) Math.round(runningAvg(existing.avgConsistency, oldCount, trip.consistencyScore)));
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
     * Rebuild the weekly rollup for {@code year}/{@code week} from remaining
     * trips. Used after a delete: rollups are purely additive on save, and the
     * incremental-mean formula is not cleanly reversible.
     *
     * <p>Aggregates with {@code COUNT}/{@code SUM}/{@code AVG} against
     * {@code trips} for the same ISO-week bucket {@link #updateWeeklyRollup}
     * uses. If no trips remain, the rollup row is deleted.
     */
    public synchronized void recomputeWeeklyRollup(int year, int week) {
        if (!ensureConnection()) return;

        List<long[]> ranges = weeklyRollupRangesMs(year, week);
        try {
            RollupAggregate agg = ranges.isEmpty()
                    ? RollupAggregate.EMPTY
                    : queryTripAggregate(ranges);
            if (agg.tripCount <= 0) {
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM weekly_rollups WHERE \"year\"=? AND week_number=?")) {
                    del.setInt(1, year);
                    del.setInt(2, week);
                    if (del.executeUpdate() > 0) {
                        logger.debug("Removed empty weekly rollup year=" + year + " week=" + week);
                    }
                }
                return;
            }
            String updateSql = "UPDATE weekly_rollups SET trip_count=?, " +
                    "total_distance_km=?, total_duration_seconds=?, avg_efficiency=?, " +
                    "total_energy_kwh=?, total_cost=?, avg_energy_per_km=?, " +
                    "avg_anticipation=?, avg_smoothness=?, avg_speed_discipline=?, " +
                    "avg_efficiency_score=?, avg_consistency=? " +
                    "WHERE \"year\"=? AND week_number=?";
            try (PreparedStatement pstmt = connection.prepareStatement(updateSql)) {
                bindRollupAggregate(pstmt, agg);
                pstmt.setInt(13, year);
                pstmt.setInt(14, week);
                if (pstmt.executeUpdate() == 0) {
                    String insertSql = "INSERT INTO weekly_rollups (\"year\", week_number, trip_count, " +
                            "total_distance_km, total_duration_seconds, avg_efficiency, " +
                            "total_energy_kwh, total_cost, avg_energy_per_km, " +
                            "avg_anticipation, avg_smoothness, avg_speed_discipline, " +
                            "avg_efficiency_score, avg_consistency) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ins = connection.prepareStatement(insertSql)) {
                        ins.setInt(1, year);
                        ins.setInt(2, week);
                        bindRollupAggregateFrom(ins, agg, 3);
                        ins.executeUpdate();
                    }
                }
            }
            logger.debug("Recomputed weekly rollup year=" + year + " week=" + week
                    + " trips=" + agg.tripCount);
        } catch (Exception e) {
            logger.error("Failed to recompute weekly rollup year=" + year + " week=" + week, e);
            reconnect();
        }
    }

    /**
     * Rebuild the monthly rollup for {@code year}/{@code month} (1-12) from
     * remaining trips. Same rationale as {@link #recomputeWeeklyRollup}.
     */
    public synchronized void recomputeMonthlyRollup(int year, int month) {
        if (!ensureConnection()) return;

        List<long[]> ranges = new ArrayList<>();
        ranges.add(monthlyRollupRangeMs(year, month));
        try {
            RollupAggregate agg = queryTripAggregate(ranges);
            if (agg.tripCount <= 0) {
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM monthly_rollups WHERE \"year\"=? AND month_number=?")) {
                    del.setInt(1, year);
                    del.setInt(2, month);
                    if (del.executeUpdate() > 0) {
                        logger.debug("Removed empty monthly rollup year=" + year + " month=" + month);
                    }
                }
                return;
            }
            String updateSql = "UPDATE monthly_rollups SET trip_count=?, " +
                    "total_distance_km=?, total_duration_seconds=?, avg_efficiency=?, " +
                    "total_energy_kwh=?, total_cost=?, avg_energy_per_km=?, " +
                    "avg_anticipation=?, avg_smoothness=?, avg_speed_discipline=?, " +
                    "avg_efficiency_score=?, avg_consistency=? " +
                    "WHERE \"year\"=? AND month_number=?";
            try (PreparedStatement pstmt = connection.prepareStatement(updateSql)) {
                bindRollupAggregate(pstmt, agg);
                pstmt.setInt(13, year);
                pstmt.setInt(14, month);
                if (pstmt.executeUpdate() == 0) {
                    String insertSql = "INSERT INTO monthly_rollups (\"year\", month_number, trip_count, " +
                            "total_distance_km, total_duration_seconds, avg_efficiency, " +
                            "total_energy_kwh, total_cost, avg_energy_per_km, " +
                            "avg_anticipation, avg_smoothness, avg_speed_discipline, " +
                            "avg_efficiency_score, avg_consistency) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ins = connection.prepareStatement(insertSql)) {
                        ins.setInt(1, year);
                        ins.setInt(2, month);
                        bindRollupAggregateFrom(ins, agg, 3);
                        ins.executeUpdate();
                    }
                }
            }
            logger.debug("Recomputed monthly rollup year=" + year + " month=" + month
                    + " trips=" + agg.tripCount);
        } catch (Exception e) {
            logger.error("Failed to recompute monthly rollup year=" + year + " month=" + month, e);
            reconnect();
        }
    }

    /**
     * Get a weekly rollup by year and ISO week number.
     */
    public synchronized WeeklyRollup getWeeklyRollup(int year, int week) {
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
    public synchronized List<WeeklyRollup> getRecentWeeklyRollups(int weeks) {
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
     * Period totals for the last {@code days} days, aggregated from the trips
     * table with the same cutoff as {@link #getTrips(int, int, int)}.
     *
     * <p>Used by GET /api/trips/summary so the Period Summary card matches the
     * trip list. Weekly rollup rows are ISO-week keyed and can describe a week
     * that does not overlap the selected day window (or a week whose trips
     * were already deleted).
     */
    public synchronized WeeklyRollup getPeriodSummary(int days) {
        if (!ensureConnection()) return null;
        if (days < 1) days = 1;

        long cutoff = System.currentTimeMillis() - ((long) days * 86400000L);
        List<long[]> ranges = new ArrayList<>();
        ranges.add(new long[] { cutoff, Long.MAX_VALUE });
        try {
            RollupAggregate agg = queryTripAggregate(ranges);
            if (agg.tripCount <= 0) return null;
            WeeklyRollup rollup = new WeeklyRollup();
            rollup.tripCount = agg.tripCount;
            rollup.totalDistanceKm = agg.totalDistanceKm;
            rollup.totalDurationSeconds = agg.totalDurationSeconds;
            rollup.avgEfficiency = agg.avgEfficiency;
            rollup.totalEnergyKwh = agg.totalEnergyKwh;
            rollup.totalCost = agg.totalCost;
            rollup.avgEnergyPerKm = agg.avgEnergyPerKm;
            rollup.avgAnticipation = agg.avgAnticipation;
            rollup.avgSmoothness = agg.avgSmoothness;
            rollup.avgSpeedDiscipline = agg.avgSpeedDiscipline;
            rollup.avgEfficiencyScore = agg.avgEfficiencyScore;
            rollup.avgConsistency = agg.avgConsistency;
            return rollup;
        } catch (Exception e) {
            logger.error("Failed to get period summary for days=" + days, e);
            reconnect();
            return null;
        }
    }

    /**
     * Get the most recent N monthly rollups, ordered by year/month descending.
     */
    public synchronized List<MonthlyRollup> getRecentMonthlyRollups(int months) {
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
    public synchronized DnaScores getAverageDna(int days) {
        if (!ensureConnection()) return null;

        long cutoff = System.currentTimeMillis() - ((long) days * 86400000L);
        // Exclude never-scored rows (all five DNA axes == 0) from the average.
        // Recovered-from-telemetry trips have no DNA data (scores stay 0), and
        // including them would drag every axis toward zero — the same dilution
        // the recovery path avoids by skipping the weekly/monthly rollups. A
        // genuinely-scored live trip has at least one non-zero axis, so this
        // only filters out recovered/blank rows, not real low-scoring trips.
        String sql = "SELECT AVG(anticipation_score) as avg_ant, AVG(smoothness_score) as avg_smo, " +
                "AVG(speed_discipline_score) as avg_spd, AVG(efficiency_score) as avg_eff, " +
                "AVG(consistency_score) as avg_con, COUNT(*) as cnt " +
                "FROM trips WHERE start_time >= ? AND (anticipation_score > 0 OR smoothness_score > 0 " +
                "OR speed_discipline_score > 0 OR efficiency_score > 0 OR consistency_score > 0)";

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
    public synchronized void updateConsumptionBucket(String bucketKey, double consumptionKwhPerKm) {
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
    public synchronized ConsumptionBucket getBucket(String bucketKey) {
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
     * Wipes every row from every trips table. Used by the user-initiated
     * "Reset Data" feature. Returns the total number of rows deleted across
     * all tables, or -1 on failure. Schema is left intact — DDL stays so
     * inserts continue to work without a reconnect.
     */
    public synchronized long resetAll() {
        if (!ensureConnection()) return -1;
        long total = 0;
        // Order matters only weakly here (no FK constraints in the schema),
        // but child-like tables before parent feels right and matches the
        // create-order idiom used elsewhere in this file.
        String[] tables = {"consumption_buckets", "fuel_consumption_buckets",
                           "monthly_rollups", "weekly_rollups", "routes", "trips"};
        try (Statement stmt = connection.createStatement()) {
            for (String t : tables) {
                int n = stmt.executeUpdate("DELETE FROM " + t);
                total += n;
                logger.info("resetAll: cleared " + n + " rows from " + t);
            }
            return total;
        } catch (Exception e) {
            logger.error("resetAll failed", e);
            return -1;
        }
    }

    // ==================== BACKUP EXPORT / IMPORT (stats-only) ====================
    //
    // Trip rows are portable; the per-trip .jsonl.gz telemetry files are NOT
    // exported (too large for the settings bundle). Imported trips therefore
    // carry a sentinel telemetry path (IMPORTED_PATH) so:
    //   (a) the storage reaper (deleteRowsWithMissingFiles / disk walk) can tell
    //       them apart from rows whose real file vanished, and skip them; and
    //   (b) the per-trip raw-telemetry detail view degrades gracefully (no file).
    // Import replays each row through the SAME ingestion path a live trip uses
    // (insertTrip → rollups → route) so routes/rollups/buckets rebuild correctly
    // and never PK-collide; ids are auto-assigned (never carried from the bundle).

    /** Sentinel telemetry path marking a row imported from a backup (no on-disk
     *  .jsonl.gz). Non-empty so reaper file-existence checks can special-case it
     *  instead of treating it as an orphan whose file was deleted. */
    public static final String IMPORTED_PATH = "imported://no-telemetry";

    // Minimum-trip thresholds, mirroring TripDetector.MIN_TRIP_DURATION_MS /
    // MIN_TRIP_DISTANCE_KM. Recovery applies the SAME floor the live path uses so
    // a tiny/idle telemetry file (e.g. a flushed but later-discarded short trip
    // whose delete was skipped on a dropped volume) doesn't resurrect as a
    // phantom trip in history + rollups + routes.
    private static final long MIN_TRIP_DURATION_MS = 60_000L;
    private static final double MIN_TRIP_DISTANCE_KM = 0.2;
    // Elevation reconstruction uses the SAME ElevationEstimator as the live
    // scoring path (TripScoreEngine) — one implementation, one set of noise/
    // accuracy constants, instead of a hand-synced duplicate deadband here.
    // Max file-missing-while-volume-up rows the orphan reconcile will delete in
    // ONE pass. Genuine deletions are incremental (a handful per 30s tick); a
    // larger batch signals a transient FUSE stat storm (files exist() returns
    // false under contention while the volume root probe stays green), so we
    // preserve them this pass rather than mass-delete on a false negative.
    private static final int MAX_MISSING_FILE_REAP_PER_PASS = 25;
    // Max physically-plausible vehicle speed for the GPS distance-integration
    // outlier gate. A segment is kept unless its distance/elapsed-time implies a
    // speed above this — rejecting teleport glitches while preserving legitimate
    // long GPS-dropout legs (tunnel/garage) that a flat distance cap would drop.
    private static final double MAX_PLAUSIBLE_KMH = 250.0;

    /** True if a trip row was restored from a backup (telemetry file absent). */
    public static boolean isImportedPath(String path) {
        return IMPORTED_PATH.equals(path);
    }

    // ==================== ORPHAN-FILE RECOVERY ====================
    //
    // Rebuild trip rows from surviving <tripId>.jsonl.gz telemetry files that
    // have no matching DB row. The SD-drop reaper bug (deleteRowsWithMissingFiles
    // deleting rows while the card was unmounted) removed ROWS but never the
    // FILES, so the raw per-second samples are still on disk and a trip's
    // time/distance/speed/GPS/elevation can be reconstructed. Energy/cost/DNA
    // scores were computed from live BMS data at trip-end and were never written
    // to the telemetry file, so recovered trips carry 0/empty for those.

    /** Result of a recovery scan. */
    public static final class RecoveryResult {
        public final int scanned;     // .jsonl.gz files seen in the dir
        public final int recovered;   // rows rebuilt + inserted
        public final int skipped;     // files that already had a row (or unparseable)
        public RecoveryResult(int scanned, int recovered, int skipped) {
            this.scanned = scanned; this.recovered = recovered; this.skipped = skipped;
        }
    }

    /**
     * Scan {@code tripsDir} for {@code <tripId>.jsonl.gz} telemetry files whose
     * trip row is missing from the DB, parse each, reconstruct a {@link TripRecord}
     * from the samples, insert it (preserving the file's existing path so the row
     * re-binds to the on-disk file), and replay rollups/route. Idempotent: a file
     * whose row already exists (by id OR content signature) is skipped, so it's
     * safe to run repeatedly.
     *
     * <p>Caller passes ALL currently-mounted trips directories (internal + SD +
     * USB) so recovery finds files even when the configured storage type differs
     * from where the surviving files actually live (e.g. user switched to
     * INTERNAL but the old trips are still on the SD). Returns counts for UI.
     *
     * <p>Concurrency: file decode (the slow part) runs OUTSIDE the connection
     * monitor — only the brief per-row insert+rollup writes take
     * {@code synchronized(this)}. This mirrors runBackfillIfNeeded so a long scan
     * over a FUSE-bridged SD never holds the monitor (which getTripsSize /
     * isBackfillComplete / ensureTripsSpace also need) for more than one row.
     */
    public RecoveryResult recoverTripsFromDisk(java.util.List<java.io.File> tripsDirs) {
        if (tripsDirs == null || tripsDirs.isEmpty() || !ensureConnection()) {
            return new RecoveryResult(0, 0, 0);
        }
        // Single-flight: the per-invocation dedup snapshot can't see another
        // concurrent recovery's in-flight inserts, so two simultaneous runs could
        // both insert the same file. The UI disables its button, but a second
        // tab / external API client could still race POST /api/trips/recover.
        // Reject the overlapping run rather than risk duplicate rows.
        if (!recoveryInProgress.compareAndSet(false, true)) {
            logger.warn("recoverTripsFromDisk: another recovery is in progress; skipping");
            return new RecoveryResult(0, 0, 0);
        }
        try {
            return recoverTripsFromDiskLocked(tripsDirs);
        } finally {
            recoveryInProgress.set(false);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean recoveryInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private RecoveryResult recoverTripsFromDiskLocked(java.util.List<java.io.File> tripsDirs) {

        // The trip CURRENTLY being recorded has NO DB row yet (the row is
        // inserted only at trip end), and its file is named <startTime>.jsonl.gz
        // — so none of the dedup keys match it. Recovering it mid-drive would
        // create a phantom row that the trip-end insert then duplicates. Skip
        // that exact file (and its .tmp sibling), mirroring the reaper's
        // protectedTripPath guard. Best-effort: null when no trip is active.
        String activePath = null, activeTmp = null;
        try {
            activePath = com.overdrive.app.storage.StorageManager.getInstance().getActiveTripFilePath();
            if (activePath != null) activeTmp = activePath + ".tmp";
        } catch (Throwable ignored) {}

        // De-dupe directories (the active dir is often also one of the per-volume
        // dirs) and collect every .jsonl.gz across all mounted volumes.
        java.util.LinkedHashSet<String> seenDirs = new java.util.LinkedHashSet<>();
        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        for (java.io.File dir : tripsDirs) {
            if (dir == null || !dir.isDirectory()) continue;
            if (!seenDirs.add(dir.getAbsolutePath())) continue;
            // BOUNDED listing: SD/USB trips dirs are FUSE-bridged and a bare
            // listFiles() can hang indefinitely on a flaky mount mid-write —
            // which would strand this worker AND leave the recovery in-progress
            // flags stuck true for the rest of the daemon's life. Route through
            // StorageManager.listTripFilesBounded, which falls back to a
            // deadline-bounded `ls` (destroyForcibly on timeout). Null = give up
            // on this volume rather than block.
            java.io.File[] fs;
            try {
                fs = com.overdrive.app.storage.StorageManager.getInstance().listTripFilesBounded(dir);
            } catch (Throwable t) {
                fs = null;
            }
            if (fs == null) continue;
            for (java.io.File ff : fs) {
                String n = ff.getName();
                if (!n.endsWith(".jsonl.gz")) continue;   // bounded list isn't pre-filtered
                String ap = ff.getAbsolutePath();
                if (ap.equals(activePath) || ap.equals(activeTmp)) continue;   // in-flight trip
                files.add(ff);
            }
        }
        if (files.isEmpty()) return new RecoveryResult(0, 0, 0);

        // Snapshot existing dedup keys under the lock (brief). We key on:
        //  - exact telemetry path  — the normal "row already points here" case
        //  - file BASENAME         — the PRIMARY, reliable key. A live trip's
        //    file keeps a stable basename through volume migration (copy keeps
        //    the name) and through the <startTime>→<dbId> rename, and recovery
        //    binds the same basename. This catches the cross-volume-copy and
        //    SD-reformat duplicates that the exact path misses.
        //  - content signature     — best-effort fallback only. It is NOT
        //    reliable across the live↔recovery boundary: the live row stores
        //    ODOMETER distance + wall-clock times while recovery computes GPS
        //    distance + sample times, so all three components usually differ.
        //    Kept as a weak extra guard, not the authority.
        final java.util.Set<String> existingSigs = new java.util.HashSet<>();
        final java.util.Set<String> existingPaths = new java.util.HashSet<>();
        final java.util.Set<String> existingBasenames = new java.util.HashSet<>();
        final java.util.Set<Long> existingStartSec = new java.util.HashSet<>();
        synchronized (this) {
            if (!ensureConnection()) return new RecoveryResult(0, 0, 0);
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "SELECT id, start_time, end_time, distance_km, telemetry_file_path FROM trips");
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    // Also register the row's CANONICAL "<id>.jsonl.gz" basename.
                    // The live flow inserts the row (path=<startTime>.jsonl.gz),
                    // renames the file to <dbId>.jsonl.gz, THEN updateTrip()s the
                    // path — a crash between rename and updateTrip leaves the disk
                    // file at <dbId>.jsonl.gz but the row path at <startTime>. The
                    // <id>.jsonl.gz key matches that orphaned-but-real file to its
                    // row, so recovery won't rebuild a degraded duplicate.
                    existingBasenames.add(rs.getLong(1) + ".jsonl.gz");
                    existingSigs.add(tripSignature(rs.getLong(2), rs.getLong(3), rs.getDouble(4)));
                    // START-TIME (whole-second) key. A trip's start_time is the
                    // same regardless of how the row was created — live, imported
                    // from a backup (IMPORTED_PATH row, no file/basename match),
                    // or reconstructed — because it equals the first telemetry
                    // sample's timestamp. This is the ONLY dedup key that catches
                    // the import-then-recover case (imported row carries the
                    // sentinel path + a fresh id, and its odometer distance won't
                    // match recovery's GPS distance, so path/basename/signature
                    // all miss). Second granularity tolerates ms rounding.
                    existingStartSec.add(rs.getLong(2) / 1000L);
                    String p = rs.getString(5);
                    if (p != null && !p.isEmpty()) {
                        existingPaths.add(p);
                        existingBasenames.add(basenameOf(p));
                    }
                }
            } catch (Exception e) {
                logger.error("recoverTripsFromDisk: preload failed", e);
                reconnect();
                return new RecoveryResult(0, 0, 0);
            }
        }

        int scanned = 0, recovered = 0, skipped = 0;
        for (java.io.File f : files) {
            scanned++;
            String absPath = f.getAbsolutePath();
            String baseName = f.getName();
            // Dedup by exact path, then by BASENAME (the reliable cross-volume /
            // post-rename key). We deliberately do NOT dedup on the parsed <id>:
            // the row reinserts with a fresh auto-id, and an id-based skip would
            // wrongly drop a genuinely-lost file when an id was reused (DB file
            // recreated + IDENTITY reseeded, or a foreign card). Basename is the
            // stable identity of a given trip's telemetry file.
            if (existingPaths.contains(absPath) || existingBasenames.contains(baseName)) {
                skipped++; continue;
            }

            // Decode + reconstruct OUTSIDE the lock — this is the slow FUSE read.
            TripRecord t = reconstructTripFromTelemetry(f);
            if (t == null) { skipped++; continue; }

            // Same minimum-trip thresholds the live path enforces (TripDetector
            // MIN_TRIP_DURATION_MS / MIN_TRIP_DISTANCE_KM), so a tiny/idle junk
            // file can't become a phantom trip polluting history + rollups.
            if (t.durationSeconds * 1000L < MIN_TRIP_DURATION_MS
                    || t.distanceKm < MIN_TRIP_DISTANCE_KM) { skipped++; continue; }

            // Start-time key catches the import-then-recover dup (imported row
            // shares this trip's start_time but no path/basename/distance match).
            // Check a ±1s band, NOT an exact floored second: the live/imported
            // row's start_time is the trip-start wall clock, while the recovered
            // startTime is the FIRST telemetry sample's timestamp (a separate
            // System.currentTimeMillis() read tens of ms later) — the two can
            // land in adjacent whole seconds, which an exact floor-divide match
            // would split. The band closes that boundary gap.
            long startSec = t.startTime / 1000L;
            if (existingStartSec.contains(startSec)
                    || existingStartSec.contains(startSec - 1)
                    || existingStartSec.contains(startSec + 1)) { skipped++; continue; }

            String sig = tripSignature(t.startTime, t.endTime, t.distanceKm);
            if (existingSigs.contains(sig)) { skipped++; continue; }   // weak content fallback

            // Bind the row back to the file already on disk so storage
            // accounting and the detail view work. size_bytes from the file.
            t.id = 0;                                  // auto-assign a fresh id
            t.routeId = -1;
            t.telemetryFilePath = absPath;
            // File.length() returns 0 (NOT throws) on a FUSE/SD stat blip. This
            // file is provably real + non-empty (reconstruct decoded ≥2 samples),
            // so a 0 here is a failed stat, not an empty file. Never persist 0:
            // isBackfillComplete() is "any size_bytes=0?" so one 0-row would pin
            // it false for the session and revert getTripsSize() to the slow FUSE
            // walk; and the backfill's "0 == orphan, walk past" logic would never
            // re-fix it. So floor a confirmed-real file at 1 byte.
            // NOTE: this 1-byte floor is PERMANENT for the row's life — the
            // size-backfill only re-stats size_bytes=0 rows, so it never revisits
            // a floored row. The only consequence is that getTotalSizeBytes()
            // undercounts this one trip's footprint by ~its file size, making the
            // trips limit gate marginally lenient for it. That's an accepted,
            // bounded leniency (rare stat-blip path, one trip, no data loss) —
            // far preferable to pinning isBackfillComplete=false (which would
            // revert the whole storage card to the multi-minute FUSE walk).
            long sz = 0;
            try { sz = f.length(); } catch (Throwable ignored) { sz = 0; }
            t.sizeBytes = sz > 0 ? sz : 1;
            t.sidecarSizeBytes = 0;

            // Brief per-row write under the lock (insertTrip/rollups/route are
            // each synchronized(this) — reentrant, so this groups them atomically
            // without holding the monitor across the next file's decode).
            long id;
            synchronized (this) {
                id = insertTrip(t);
                if (id <= 0) { skipped++; continue; }
                t.id = id;
                // Deliberately DO NOT replay weekly/monthly rollups for recovered
                // trips. A recovered trip has no DNA/efficiency/energy data (those
                // aren't in telemetry — all 0), and the rollups average those
                // across trip_count via runningAvg; feeding a 0-score trip would
                // drag a historical period's displayed driving-score/efficiency
                // toward zero (and bumping trip_count without a real score skews
                // every later average too). The recovered trip still appears in
                // the trip LIST (read straight from the trips table) and on the
                // map; only the score-summary tiles omit it — honest, since we
                // genuinely don't know its scores. Routes ARE updated below
                // (distance + coords are valid and useful for similar-trip maps).
                if (t.startLat != 0 && t.startLon != 0) {
                    long routeId = findOrCreateRoute(t.startLat, t.startLon, t.endLat, t.endLon, t.distanceKm);
                    if (routeId > 0) { t.routeId = routeId; updateTrip(t); }
                }
            }
            existingSigs.add(sig);
            existingPaths.add(absPath);
            existingBasenames.add(baseName);
            existingStartSec.add(t.startTime / 1000L);
            recovered++;
        }
        logger.info("recoverTripsFromDisk: scanned " + scanned + ", recovered " + recovered
                + ", skipped " + skipped);
        return new RecoveryResult(scanned, recovered, skipped);
    }

    /**
     * Rebuild a {@link TripRecord} from a telemetry file's samples. Derives
     * start/end time, duration, distance (GPS haversine integration), avg/max
     * speed, start/end GPS, and elevation gain/loss. Energy/SoC/cost/DNA-score
     * fields are NOT in the telemetry stream, so they stay at their defaults
     * (0 / empty) — recovered trips show in history + maps but without the
     * energy-analytics axes. Returns null if the file has too few usable samples.
     */
    private TripRecord reconstructTripFromTelemetry(java.io.File f) {
        java.util.List<TelemetrySample> samples;
        try {
            samples = TelemetryStore.readFromFile(f);
        } catch (Throwable e) {
            logger.warn("recover: unreadable telemetry " + f.getName() + ": " + e.getMessage());
            return null;
        }
        if (samples == null || samples.size() < 2) return null;

        // Trim the trailing park-debounce tail. The recorder keeps sampling for
        // the full ~120s park-debounce window (gear=P, speed=0) after the trip
        // really ended; the LIVE row trims this (endTime = parkStartTime), so we
        // must too, or recovered duration is inflated by up to ~120s and the
        // rollups inherit it. Walk back to the last MOVING sample. "Moving" is
        // speed>0, a non-Park gear (gearMode 1 == P, 0 == unknown), OR GPS
        // movement vs the next fix — the GPS clause matters for trips whose
        // speed channel was down (all speedKmh==0) but GPS was recorded, so the
        // whole trip isn't wrongly trimmed to zero-length and dropped.
        int lastIdx = samples.size() - 1;
        double nextGpsLat = 0, nextGpsLon = 0; boolean haveNextGps = false;
        while (lastIdx > 0) {
            TelemetrySample s = samples.get(lastIdx);
            boolean gpsMoved = false;
            if (s.lat != 0 || s.lon != 0) {
                if (haveNextGps && haversineKm(s.lat, s.lon, nextGpsLat, nextGpsLon) > 0.01) {
                    gpsMoved = true;   // >10 m between this fix and the later one
                }
                nextGpsLat = s.lat; nextGpsLon = s.lon; haveNextGps = true;
            }
            boolean moving = s.speedKmh > 0 || (s.gearMode != 0 && s.gearMode != 1) || gpsMoved;
            if (moving) break;
            lastIdx--;
        }

        TripRecord t = new TripRecord();
        t.startTime = samples.get(0).timestampMs;
        t.endTime = samples.get(lastIdx).timestampMs;
        if (t.endTime <= t.startTime) return null;
        t.durationSeconds = (int) ((t.endTime - t.startTime) / 1000L);

        double distKm = 0;
        int maxSpeed = 0;
        long speedSum = 0; int speedCount = 0;
        double elevGain = 0, elevLoss = 0;
        ElevationEstimator elevation = new ElevationEstimator();
        double prevLat = 0, prevLon = 0;
        long prevGpsTs = 0;
        boolean havePrevGps = false;
        double firstLat = 0, firstLon = 0, lastLat = 0, lastLon = 0;
        boolean haveFirstGps = false;

        // Iterate only through the moving portion [0..lastIdx] so the trimmed
        // tail doesn't dilute avg speed or extend distance/elevation either.
        for (int i = 0; i <= lastIdx; i++) {
            TelemetrySample s = samples.get(i);
            if (s.speedKmh > maxSpeed) maxSpeed = s.speedKmh;
            speedSum += s.speedKmh; speedCount++;

            boolean hasGps = s.lat != 0 || s.lon != 0;
            if (hasGps) {
                if (!haveFirstGps) { firstLat = s.lat; firstLon = s.lon; haveFirstGps = true; }
                lastLat = s.lat; lastLon = s.lon;
                if (havePrevGps) {
                    double seg = haversineKm(prevLat, prevLon, s.lat, s.lon);
                    // TIME-AWARE plausibility gate (not a flat 1km cap): keep a
                    // segment as long as the implied speed is physically possible
                    // for the elapsed time between the two fixes. This still
                    // rejects teleport outliers, but PRESERVES a legitimate long
                    // GPS-dropout leg (tunnel/garage: fixes go 0/0 for 60-120s
                    // then reacquire 1.5-3 km away) that a flat <1km reject would
                    // silently drop — which under-counts distance and could push
                    // a short recovered trip below MIN_TRIP_DISTANCE_KM. Dropout
                    // gaps make dt large, so a 3 km reacquire over 90 s = 120 km/h
                    // passes, while a same-second teleport implies absurd speed
                    // and is rejected.
                    long dtMs = s.timestampMs - prevGpsTs;
                    double dtHr = dtMs > 0 ? dtMs / 3_600_000.0 : (1.0 / 3600.0); // ≥1s floor
                    double impliedKmh = seg / dtHr;
                    if (impliedKmh <= MAX_PLAUSIBLE_KMH) distKm += seg;
                }
                prevLat = s.lat; prevLon = s.lon; prevGpsTs = s.timestampMs; havePrevGps = true;
            }
            // Elevation: SAME pipeline as the live scoring path (movement gate,
            // vertical-accuracy gate, 10s median smoothing, MSL-source-flip and
            // long-gap resets, hysteresis banking). Works at this file's ~1Hz
            // rate because the smoothing window is time-based. Old telemetry
            // files lack the "va"/"am" keys — they decode as 0/false, meaning
            // "no accuracy gate, no source flips" (graceful degradation).
            elevation.addSample(s.timestampMs, s.altitude, s.verticalAccuracyM,
                    s.altitudeIsMsl,
                    ElevationEstimator.isElevationEligible(s.gearMode, s.speedKmh));
        }
        elevGain = elevation.getGainM();
        elevLoss = elevation.getLossM();

        t.distanceKm = distKm;
        t.maxSpeedKmh = maxSpeed;
        // Avg speed: PREFER distance/duration. The telemetry file retains the
        // recorder's "synthetic zero" samples (speed=0 written when a dynamics
        // snapshot was stale, kept only for raw-timeline continuity) which the
        // LIVE recorder deliberately EXCLUDES from its own average — but that
        // real-vs-synthetic flag isn't persisted, so a sample-mean here would be
        // biased low. distance/duration is immune to that and matches the live
        // value much better. Fall back to the sample mean only when distance or
        // duration is unusable.
        double speedAvg;
        if (distKm > 0 && t.durationSeconds > 0) {
            speedAvg = distKm / (t.durationSeconds / 3600.0);
        } else {
            speedAvg = speedCount > 0 ? (double) speedSum / speedCount : 0;
        }
        t.avgSpeedKmh = speedAvg;
        if (haveFirstGps) { t.startLat = firstLat; t.startLon = firstLon; }
        t.endLat = lastLat; t.endLon = lastLon;
        t.elevationGainM = elevGain;
        t.elevationLossM = elevLoss;
        t.currency = "";
        t.kinematicState = "";
        t.gradientProfile = "";
        // Energy / SoC / cost / DNA scores unavailable from telemetry → defaults.
        return t;
    }

    /** Haversine great-circle distance in km. Mirrors TripDetector.haversineKm. */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /**
     * Export ALL trip rows as a JSON array (stats only — no telemetry files,
     * no derived rollup/route/bucket tables; those rebuild on import). Each
     * element carries the portable trip columns. Read-only.
     */
    public org.json.JSONArray exportTripsJson() {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (!ensureConnection()) return arr;
        synchronized (this) {
            String sql = "SELECT * FROM trips ORDER BY start_time ASC";
            try (PreparedStatement pstmt = connection.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    TripRecord t = readTripFromResultSet(rs);
                    arr.put(tripToJson(t));
                }
            } catch (Exception e) {
                logger.error("exportTripsJson failed", e);
                reconnect();
            }
        }
        return arr;
    }

    /**
     * Import trip rows from a backup array produced by {@link #exportTripsJson}.
     * Each row is replayed through the live ingestion path so derived tables
     * (routes, weekly/monthly rollups) rebuild consistently. Rows are deduped
     * by content signature (start+end+distance) against what's already present,
     * so a re-import or a merge never double-counts. Imported rows get the
     * IMPORTED_PATH sentinel and size_bytes=0.
     *
     * @return number of trips actually inserted (after dedup).
     */
    public int importTripsJson(org.json.JSONArray arr) {
        if (arr == null || !ensureConnection()) return 0;

        // Build the set of existing signatures once so dedup is O(1) per row.
        java.util.Set<String> existing = new java.util.HashSet<>();
        synchronized (this) {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "SELECT start_time, end_time, distance_km FROM trips");
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    existing.add(tripSignature(rs.getLong(1), rs.getLong(2), rs.getDouble(3)));
                }
            } catch (Exception e) {
                logger.error("importTripsJson: signature preload failed", e);
                reconnect();
                return 0;
            }
        }

        int inserted = 0;
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            TripRecord t = tripFromJson(o);
            if (t == null) continue;

            String sig = tripSignature(t.startTime, t.endTime, t.distanceKm);
            if (existing.contains(sig)) continue;   // already have this trip

            // Imported rows have no telemetry file on disk.
            t.id = 0;                       // force auto-assign, never carry bundle id
            t.routeId = -1;                 // re-derived below
            t.telemetryFilePath = IMPORTED_PATH;
            t.sizeBytes = 0;
            t.sidecarSizeBytes = 0;

            long id = insertTrip(t);
            if (id <= 0) continue;
            t.id = id;
            existing.add(sig);
            inserted++;

            // Replay the same derived-table updates a live trip triggers.
            updateWeeklyRollup(t);
            updateMonthlyRollup(t);
            if (t.startLat != 0 && t.startLon != 0) {
                long routeId = findOrCreateRoute(t.startLat, t.startLon, t.endLat, t.endLon, t.distanceKm);
                if (routeId > 0) {
                    t.routeId = routeId;
                    updateTrip(t);
                }
            }
        }
        logger.info("importTripsJson: inserted " + inserted + " of " + arr.length()
                + " trips (deduped " + (arr.length() - inserted) + ")");
        return inserted;
    }

    /** Content signature for dedup — start+end+distance uniquely identifies a
     *  trip across export/import (ids aren't stable). Distance rounded to whole
     *  metres so float round-trips through JSON can't split a match. */
    private static String tripSignature(long startMs, long endMs, double distanceKm) {
        return startMs + ":" + endMs + ":" + Math.round(distanceKm * 1000.0);
    }

    /** Final path segment of a stored telemetry path, handling both '/' and the
     *  rare backslash. Used as the reliable cross-volume dedup key. */
    private static String basenameOf(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Serialize the portable columns of a trip. Server-internal fields
     *  (size_bytes, telemetry path, route_id, db id) are deliberately omitted —
     *  they're re-derived on import. */
    private org.json.JSONObject tripToJson(TripRecord t) {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("startTime", t.startTime);
            o.put("endTime", t.endTime);
            o.put("distanceKm", t.distanceKm);
            o.put("durationSeconds", t.durationSeconds);
            o.put("avgSpeedKmh", t.avgSpeedKmh);
            o.put("maxSpeedKmh", t.maxSpeedKmh);
            o.put("socStart", t.socStart);
            o.put("socEnd", t.socEnd);
            o.put("kwhStart", t.kwhStart);
            o.put("kwhEnd", t.kwhEnd);
            o.put("energyPerKm", t.energyPerKm);
            o.put("electricityRate", t.electricityRate);
            o.put("currency", t.currency != null ? t.currency : "");
            o.put("rateSource", t.rateSource != null ? t.rateSource : "");
            o.put("rateLabel", t.rateLabel != null ? t.rateLabel : "");
            o.put("tripCost", t.tripCost);
            o.put("kinematicState", t.kinematicState != null ? t.kinematicState : "");
            o.put("gradientProfile", t.gradientProfile != null ? t.gradientProfile : "");
            o.put("elevationGainM", t.elevationGainM);
            o.put("elevationLossM", t.elevationLossM);
            o.put("avgGradientPercent", t.avgGradientPercent);
            o.put("efficiencySocPerKm", t.efficiencySocPerKm);
            o.put("startLat", t.startLat);
            o.put("startLon", t.startLon);
            o.put("endLat", t.endLat);
            o.put("endLon", t.endLon);
            o.put("extTempC", t.extTempC);
            o.put("anticipationScore", t.anticipationScore);
            o.put("smoothnessScore", t.smoothnessScore);
            o.put("speedDisciplineScore", t.speedDisciplineScore);
            o.put("efficiencyScore", t.efficiencyScore);
            o.put("consistencyScore", t.consistencyScore);
            o.put("microMomentsJson", t.microMomentsJson != null ? t.microMomentsJson : org.json.JSONObject.NULL);
            o.put("isPhev", t.isPhev);
            o.put("fuelPctStart", t.fuelPctStart);
            o.put("fuelPctEnd", t.fuelPctEnd);
            o.put("litresUsed", t.litresUsed);
            o.put("fuelPricePerL", t.fuelPricePerL);
            o.put("fuelCost", t.fuelCost);
            o.put("electricCost", t.electricCost);
            o.put("iceSeconds", t.iceSeconds());
            o.put("fuelConStart", t.fuelConStart);
            o.put("fuelConEnd", t.fuelConEnd);
            o.put("elecConStart", t.elecConStart);
            o.put("elecConEnd", t.elecConEnd);
            o.put("odometerStartKm", t.odometerStartKm);
            o.put("odometerEndKm", t.odometerEndKm);
        } catch (Exception e) {
            logger.warn("tripToJson failed for trip start=" + t.startTime + ": " + e.getMessage());
        }
        return o;
    }

    /** Inverse of {@link #tripToJson}. Returns null if the row lacks the minimum
     *  identity fields (start/end time). */
    private TripRecord tripFromJson(org.json.JSONObject o) {
        long start = o.optLong("startTime", 0);
        long end = o.optLong("endTime", 0);
        if (start <= 0 || end <= 0) return null;
        TripRecord t = new TripRecord();
        t.startTime = start;
        t.endTime = end;
        t.distanceKm = o.optDouble("distanceKm", 0);
        t.durationSeconds = o.optInt("durationSeconds", 0);
        t.avgSpeedKmh = o.optDouble("avgSpeedKmh", 0);
        t.maxSpeedKmh = o.optInt("maxSpeedKmh", 0);
        t.socStart = o.optDouble("socStart", 0);
        t.socEnd = o.optDouble("socEnd", 0);
        t.kwhStart = o.optDouble("kwhStart", 0);
        t.kwhEnd = o.optDouble("kwhEnd", 0);
        t.energyPerKm = o.optDouble("energyPerKm", 0);
        t.electricityRate = o.optDouble("electricityRate", 0);
        t.currency = o.optString("currency", "");
        t.tripCost = o.optDouble("tripCost", 0);
        t.kinematicState = o.optString("kinematicState", "");
        t.gradientProfile = o.optString("gradientProfile", "");
        t.elevationGainM = o.optDouble("elevationGainM", 0);
        t.elevationLossM = o.optDouble("elevationLossM", 0);
        t.avgGradientPercent = o.optDouble("avgGradientPercent", 0);
        t.efficiencySocPerKm = o.optDouble("efficiencySocPerKm", 0);
        t.startLat = o.optDouble("startLat", 0);
        t.startLon = o.optDouble("startLon", 0);
        t.endLat = o.optDouble("endLat", 0);
        t.endLon = o.optDouble("endLon", 0);
        t.extTempC = o.optInt("extTempC", 0);
        t.anticipationScore = o.optInt("anticipationScore", 0);
        t.smoothnessScore = o.optInt("smoothnessScore", 0);
        t.speedDisciplineScore = o.optInt("speedDisciplineScore", 0);
        t.efficiencyScore = o.optInt("efficiencyScore", 0);
        t.consistencyScore = o.optInt("consistencyScore", 0);
        t.microMomentsJson = o.isNull("microMomentsJson") ? null : o.optString("microMomentsJson", null);
        t.isPhev = o.optBoolean("isPhev", false);
        t.fuelPctStart = o.optDouble("fuelPctStart", -1);
        t.fuelPctEnd = o.optDouble("fuelPctEnd", -1);
        t.litresUsed = o.optDouble("litresUsed", 0);
        t.fuelPricePerL = o.optDouble("fuelPricePerL", 0);
        t.fuelCost = o.optDouble("fuelCost", 0);
        t.electricCost = o.optDouble("electricCost", 0);
        t.iceSecondsAtomic.set(o.optInt("iceSeconds", 0));
        t.fuelConStart = o.optDouble("fuelConStart", -1);
        t.fuelConEnd = o.optDouble("fuelConEnd", -1);
        t.elecConStart = o.optDouble("elecConStart", -1);
        t.elecConEnd = o.optDouble("elecConEnd", -1);
        t.odometerStartKm = o.optDouble("odometerStartKm", 0);
        t.odometerEndKm = o.optDouble("odometerEndKm", 0);
        t.rateSource = o.optString("rateSource", "");
        t.rateLabel = o.optString("rateLabel", "");
        return t;
    }

    /**
     * Clear all consumption buckets. Called when nominal capacity changes
     * significantly (e.g., wrong capacity was detected previously) to prevent
     * poisoned consumption rates from corrupting range estimates.
     */
    public synchronized void clearConsumptionBuckets() {
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
    public synchronized ConsumptionBucket getOverallAverage() {
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

    // ==================== FUEL CONSUMPTION BUCKETS (PHEV) ====================

    /**
     * Update a fuel consumption bucket — same MERGE-style semantics as the
     * EV bucket, but tracks litres-per-km instead of kWh-per-km. The
     * underlying {@link ConsumptionBucket} record is reused; the read path
     * fills {@code sumKwhPerKm} with the litres-per-km value so existing
     * bucket-resolution code can stay generic.
     */
    public synchronized void updateFuelConsumptionBucket(String bucketKey, double litresPerKm) {
        if (!ensureConnection()) return;

        try {
            ConsumptionBucket existing = getFuelBucket(bucketKey);

            if (existing == null) {
                String sql = "INSERT INTO fuel_consumption_buckets "
                        + "(bucket_key, sample_count, sum_litres_per_km, sum_squared_litres_per_km) "
                        + "VALUES (?, 1, ?, ?)";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setString(1, bucketKey);
                    pstmt.setDouble(2, litresPerKm);
                    pstmt.setDouble(3, litresPerKm * litresPerKm);
                    pstmt.executeUpdate();
                }
            } else {
                String sql = "UPDATE fuel_consumption_buckets SET sample_count = sample_count + 1, "
                        + "sum_litres_per_km = sum_litres_per_km + ?, "
                        + "sum_squared_litres_per_km = sum_squared_litres_per_km + ? "
                        + "WHERE bucket_key = ?";
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setDouble(1, litresPerKm);
                    pstmt.setDouble(2, litresPerKm * litresPerKm);
                    pstmt.setString(3, bucketKey);
                    pstmt.executeUpdate();
                }
            }
            logger.debug("Updated fuel bucket: " + bucketKey);
        } catch (Exception e) {
            logger.error("Failed to update fuel bucket: " + bucketKey, e);
            reconnect();
        }
    }

    /**
     * Get a fuel consumption bucket by key, or null if not found.
     * Returns the same {@link ConsumptionBucket} type with its sumKwhPerKm
     * field populated from the underlying litres-per-km column for
     * call-site uniformity with the EV path.
     */
    public synchronized ConsumptionBucket getFuelBucket(String bucketKey) {
        if (!ensureConnection()) return null;

        String sql = "SELECT bucket_key, sample_count, sum_litres_per_km, sum_squared_litres_per_km "
                + "FROM fuel_consumption_buckets WHERE bucket_key = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, bucketKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ConsumptionBucket b = new ConsumptionBucket();
                    b.bucketKey = rs.getString("bucket_key");
                    b.sampleCount = rs.getInt("sample_count");
                    b.sumKwhPerKm = rs.getDouble("sum_litres_per_km");
                    b.sumSquaredKwhPerKm = rs.getDouble("sum_squared_litres_per_km");
                    return b;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get fuel bucket: " + bucketKey, e);
            reconnect();
        }
        return null;
    }

    /**
     * Overall average across all fuel buckets, mirroring
     * {@link #getOverallAverage()} for the EV side.
     */
    public synchronized ConsumptionBucket getOverallFuelAverage() {
        if (!ensureConnection()) return null;

        String sql = "SELECT SUM(sample_count) as total_count, "
                + "SUM(sum_litres_per_km) as total_sum, "
                + "SUM(sum_squared_litres_per_km) as total_sum_sq "
                + "FROM fuel_consumption_buckets";

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
            logger.error("Failed to get overall fuel average", e);
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
        // PHEV columns — wrapped individually so a partial migration (one
        // column added, another not) doesn't void the whole row read.
        try { trip.isPhev         = rs.getBoolean("is_phev"); }       catch (Exception e) { trip.isPhev = false; }
        try { trip.fuelPctStart   = rs.getDouble("fuel_pct_start"); }  catch (Exception e) { trip.fuelPctStart = -1; }
        try { trip.fuelPctEnd     = rs.getDouble("fuel_pct_end"); }    catch (Exception e) { trip.fuelPctEnd = -1; }
        try { trip.litresUsed     = rs.getDouble("litres_used"); }     catch (Exception e) { trip.litresUsed = 0; }
        try { trip.fuelPricePerL  = rs.getDouble("fuel_price_per_l"); }catch (Exception e) { trip.fuelPricePerL = 0; }
        try { trip.fuelCost       = rs.getDouble("fuel_cost"); }       catch (Exception e) { trip.fuelCost = 0; }
        try { trip.electricCost   = rs.getDouble("electric_cost"); }   catch (Exception e) { trip.electricCost = 0; }
        try { trip.iceSecondsAtomic.set(rs.getInt("ice_seconds")); } catch (Exception e) { trip.iceSecondsAtomic.set(0); }
        // wasNull re-maps a SQL NULL to the -1 sentinel; getDouble would
        // otherwise return 0.0, which falsely satisfies the >=0 metered guard.
        try { trip.fuelConStart = rs.getDouble("fuel_con_start"); if (rs.wasNull()) trip.fuelConStart = -1; } catch (Exception e) { trip.fuelConStart = -1; }
        try { trip.fuelConEnd   = rs.getDouble("fuel_con_end");   if (rs.wasNull()) trip.fuelConEnd   = -1; } catch (Exception e) { trip.fuelConEnd = -1; }
        // Same wasNull re-mapping for the electricity accumulator: a NULL read as
        // 0.0 would look like a valid "meter says zero kWh" pair and suppress the
        // fallback tiers, zeroing energy on every legacy row.
        try { trip.elecConStart = rs.getDouble("elec_con_start"); if (rs.wasNull()) trip.elecConStart = -1; } catch (Exception e) { trip.elecConStart = -1; }
        try { trip.elecConEnd   = rs.getDouble("elec_con_end");   if (rs.wasNull()) trip.elecConEnd   = -1; } catch (Exception e) { trip.elecConEnd = -1; }
        // Storage accounting (added in size-backfill migration). 0 means
        // "not yet backfilled" — see runBackfillIfNeeded().
        try { trip.sizeBytes        = rs.getLong("size_bytes"); }         catch (Exception e) { trip.sizeBytes = 0; }
        try { trip.sidecarSizeBytes = rs.getLong("sidecar_size_bytes"); } catch (Exception e) { trip.sidecarSizeBytes = 0; }
        // Absolute odometer snapshots (0 = unavailable at that edge → UI shows "--").
        try { trip.odometerStartKm = rs.getDouble("odometer_start_km"); } catch (Exception e) { trip.odometerStartKm = 0; }
        try { trip.odometerEndKm   = rs.getDouble("odometer_end_km"); }   catch (Exception e) { trip.odometerEndKm = 0; }
        // Rate provenance ("charge"/"config" + tariff label). Empty on every trip
        // recorded before this column existed → UI omits the chip.
        try { String rs1 = rs.getString("rate_source"); trip.rateSource = rs1 != null ? rs1 : ""; } catch (Exception e) { trip.rateSource = ""; }
        try { String rl = rs.getString("rate_label");  trip.rateLabel  = rl != null ? rl : ""; }   catch (Exception e) { trip.rateLabel = ""; }
        return trip;
    }

    /**
     * Set PreparedStatement parameters for a TripRecord (positions 1-32).
     * Position 33 (route_id) is set by the caller; PHEV fuel columns at
     * positions 34-43 are bound by {@link #setTripFuelParams}.
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
     * Bind PHEV / fuel-cost params starting at {@code firstIdx}. Pulled into
     * its own helper so insert/update share one source of truth for the
     * fuel column ordering. Position is intentional (matches column list in
     * the calling SQL): is_phev, fuel_pct_start, fuel_pct_end, litres_used,
     * fuel_price_per_l, fuel_cost, electric_cost, ice_seconds,
     * fuel_con_start, fuel_con_end.
     */
    private void setTripFuelParams(PreparedStatement pstmt, TripRecord trip, int firstIdx) throws Exception {
        pstmt.setBoolean(firstIdx,     trip.isPhev);
        pstmt.setDouble (firstIdx + 1, trip.fuelPctStart);
        pstmt.setDouble (firstIdx + 2, trip.fuelPctEnd);
        pstmt.setDouble (firstIdx + 3, trip.litresUsed);
        pstmt.setDouble (firstIdx + 4, trip.fuelPricePerL);
        pstmt.setDouble (firstIdx + 5, trip.fuelCost);
        pstmt.setDouble (firstIdx + 6, trip.electricCost);
        pstmt.setInt    (firstIdx + 7, trip.iceSeconds());
        pstmt.setDouble (firstIdx + 8, trip.fuelConStart);
        pstmt.setDouble (firstIdx + 9, trip.fuelConEnd);
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
     * Calendar used to bucket a trip into weekly/monthly rollups.
     * ISO week: Monday first day, 4 minimal days in the first week.
     */
    private static Calendar newTripPeriodCalendar() {
        Calendar cal = Calendar.getInstance(Locale.US);
        cal.setMinimalDaysInFirstWeek(4); // ISO week
        cal.setFirstDayOfWeek(Calendar.MONDAY);
        return cal;
    }

    /**
     * Recompute weekly and monthly rollups for each distinct period covered by
     * {@code startTimes} (epoch millis of deleted trips). Dedupes so a batch
     * delete in the same week/month only rebuilds once.
     */
    private void recomputeRollupsForStartTimes(List<Long> startTimes) {
        if (startTimes == null || startTimes.isEmpty()) return;
        Set<Long> weeks = new HashSet<>();
        Set<Long> months = new HashSet<>();
        for (Long startTime : startTimes) {
            if (startTime == null) continue;
            Calendar cal = newTripPeriodCalendar();
            cal.setTimeInMillis(startTime);
            int year = cal.get(Calendar.YEAR);
            int week = cal.get(Calendar.WEEK_OF_YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            if (weeks.add((((long) year) << 16) | (week & 0xFFFF))) {
                recomputeWeeklyRollup(year, week);
            }
            if (months.add((((long) year) << 16) | (month & 0xFFFF))) {
                recomputeMonthlyRollup(year, month);
            }
        }
    }

    /**
     * Contiguous {@code [start, end)} millis ranges in calendar {@code year}
     * whose ISO {@code WEEK_OF_YEAR} equals {@code week}.
     *
     * <p>A single ISO week can split across two calendar years (late Dec /
     * early Jan), and week 1 can appear both in January and again in late
     * December of the same calendar year. Walking the year yields the 1–2
     * ranges that match {@link #updateWeeklyRollup}'s {@code YEAR} +
     * {@code WEEK_OF_YEAR} bucket — a single {@code BETWEEN} would be wrong.
     */
    private static List<long[]> weeklyRollupRangesMs(int year, int week) {
        List<long[]> ranges = new ArrayList<>();
        Calendar cal = newTripPeriodCalendar();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, Calendar.JANUARY);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        Calendar endOfYear = newTripPeriodCalendar();
        endOfYear.setTimeInMillis(cal.getTimeInMillis());
        endOfYear.add(Calendar.YEAR, 1);
        long yearEndMs = endOfYear.getTimeInMillis();

        long rangeStart = -1;
        while (cal.getTimeInMillis() < yearEndMs) {
            boolean match = cal.get(Calendar.YEAR) == year
                    && cal.get(Calendar.WEEK_OF_YEAR) == week;
            if (match) {
                if (rangeStart < 0) rangeStart = cal.getTimeInMillis();
            } else if (rangeStart >= 0) {
                ranges.add(new long[] { rangeStart, cal.getTimeInMillis() });
                rangeStart = -1;
            }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        if (rangeStart >= 0) {
            ranges.add(new long[] { rangeStart, yearEndMs });
        }
        return ranges;
    }

    /** {@code [start, end)} millis of calendar month {@code month} (1-12). */
    private static long[] monthlyRollupRangeMs(int year, int month) {
        Calendar cal = newTripPeriodCalendar();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month - 1);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long start = cal.getTimeInMillis();
        cal.add(Calendar.MONTH, 1);
        return new long[] { start, cal.getTimeInMillis() };
    }

    /**
     * Fresh COUNT/SUM/AVG over trips whose {@code start_time} falls in any of
     * {@code ranges} ({@code [start, end)} pairs). Energy uses the same
     * cascade as {@link TripRecord#getResolvedEnergyKwh()}.
     */
    private static final String ROLLUP_AGGREGATE_SQL =
            "SELECT COUNT(*) AS trip_count, " +
            "COALESCE(SUM(distance_km), 0) AS total_distance_km, " +
            "COALESCE(SUM(duration_seconds), 0) AS total_duration_seconds, " +
            "COALESCE(AVG(efficiency_soc_per_km), 0) AS avg_efficiency, " +
            "COALESCE(SUM(CASE " +
                "WHEN kwh_start > 0 AND kwh_end > 0 AND kwh_start > kwh_end THEN kwh_start - kwh_end " +
                "WHEN elec_con_start >= 0 AND elec_con_end >= 0 AND elec_con_end >= elec_con_start " +
                    "AND (kwh_start <= 0 OR kwh_end <= 0 OR kwh_start = kwh_end) " +
                    "AND (elec_con_end - elec_con_start) > 0 " +
                    "THEN elec_con_end - elec_con_start " +
                "WHEN energy_per_km > 0 AND distance_km > 0 THEN energy_per_km * distance_km " +
                "ELSE 0 END), 0) AS total_energy_kwh, " +
            "COALESCE(SUM(trip_cost), 0) AS total_cost, " +
            "COALESCE(AVG(energy_per_km), 0) AS avg_energy_per_km, " +
            "COALESCE(AVG(anticipation_score), 0) AS avg_anticipation, " +
            "COALESCE(AVG(smoothness_score), 0) AS avg_smoothness, " +
            "COALESCE(AVG(speed_discipline_score), 0) AS avg_speed_discipline, " +
            "COALESCE(AVG(efficiency_score), 0) AS avg_efficiency_score, " +
            "COALESCE(AVG(consistency_score), 0) AS avg_consistency " +
            "FROM trips";

    private RollupAggregate queryTripAggregate(List<long[]> ranges) throws Exception {
        if (ranges == null || ranges.isEmpty()) return RollupAggregate.EMPTY;

        StringBuilder sql = new StringBuilder(ROLLUP_AGGREGATE_SQL);
        sql.append(" WHERE ");
        for (int i = 0; i < ranges.size(); i++) {
            if (i > 0) sql.append(" OR ");
            sql.append("(start_time >= ? AND start_time < ?)");
        }

        try (PreparedStatement pstmt = connection.prepareStatement(sql.toString())) {
            int idx = 1;
            for (long[] range : ranges) {
                pstmt.setLong(idx++, range[0]);
                pstmt.setLong(idx++, range[1]);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) return RollupAggregate.EMPTY;
                RollupAggregate a = new RollupAggregate();
                a.tripCount = rs.getInt("trip_count");
                a.totalDistanceKm = rs.getDouble("total_distance_km");
                a.totalDurationSeconds = rs.getInt("total_duration_seconds");
                a.avgEfficiency = rs.getDouble("avg_efficiency");
                a.totalEnergyKwh = rs.getDouble("total_energy_kwh");
                a.totalCost = rs.getDouble("total_cost");
                a.avgEnergyPerKm = rs.getDouble("avg_energy_per_km");
                a.avgAnticipation = (int) Math.round(rs.getDouble("avg_anticipation"));
                a.avgSmoothness = (int) Math.round(rs.getDouble("avg_smoothness"));
                a.avgSpeedDiscipline = (int) Math.round(rs.getDouble("avg_speed_discipline"));
                a.avgEfficiencyScore = (int) Math.round(rs.getDouble("avg_efficiency_score"));
                a.avgConsistency = (int) Math.round(rs.getDouble("avg_consistency"));
                return a;
            }
        }
    }

    private static void bindRollupAggregate(PreparedStatement pstmt, RollupAggregate a)
            throws Exception {
        bindRollupAggregateFrom(pstmt, a, 1);
    }

    private static void bindRollupAggregateFrom(PreparedStatement pstmt, RollupAggregate a,
                                                int firstIdx) throws Exception {
        pstmt.setInt(firstIdx, a.tripCount);
        pstmt.setDouble(firstIdx + 1, a.totalDistanceKm);
        pstmt.setInt(firstIdx + 2, a.totalDurationSeconds);
        pstmt.setDouble(firstIdx + 3, a.avgEfficiency);
        pstmt.setDouble(firstIdx + 4, a.totalEnergyKwh);
        pstmt.setDouble(firstIdx + 5, a.totalCost);
        pstmt.setDouble(firstIdx + 6, a.avgEnergyPerKm);
        pstmt.setInt(firstIdx + 7, a.avgAnticipation);
        pstmt.setInt(firstIdx + 8, a.avgSmoothness);
        pstmt.setInt(firstIdx + 9, a.avgSpeedDiscipline);
        pstmt.setInt(firstIdx + 10, a.avgEfficiencyScore);
        pstmt.setInt(firstIdx + 11, a.avgConsistency);
    }

    private static final class RollupAggregate {
        static final RollupAggregate EMPTY = new RollupAggregate();
        int tripCount;
        double totalDistanceKm;
        int totalDurationSeconds;
        double avgEfficiency;
        double totalEnergyKwh;
        double totalCost;
        double avgEnergyPerKm;
        int avgAnticipation;
        int avgSmoothness;
        int avgSpeedDiscipline;
        int avgEfficiencyScore;
        int avgConsistency;
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
    public synchronized int deleteOrphanedTrips(long olderThanMs) {
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

    // ==================== STORAGE ACCOUNTING ====================

    /**
     * Sum of telemetry + sidecar bytes across every trip row.
     *
     * <p>Drop-in replacement for the FUSE-walking
     * {@code StorageManager.getTripsSize()} on full-storage devices where the
     * filesystem walk took 10-20 minutes. H2 keeps the trips table in its
     * 8MB CACHE_SIZE, so this is a sub-millisecond aggregate. Returns 0 on
     * connection failure so the caller can fall back to the slow walk.
     *
     * <p>Authoritativeness: only accurate once {@link #isBackfillComplete()}
     * is true. Until then, legacy rows still report 0 and a low total is
     * returned. {@link com.overdrive.app.storage.StorageManager} is expected
     * to gate on {@code isBackfillComplete()} before trusting this value.
     */
    public long getTotalSizeBytes() {
        if (!ensureConnection()) return 0;

        synchronized (this) {
            String sql = "SELECT COALESCE(SUM(size_bytes + sidecar_size_bytes), 0) FROM trips";
            try (PreparedStatement pstmt = connection.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            } catch (Exception e) {
                logger.error("Failed to compute total trips size", e);
                reconnect();
            }
        }
        return 0;
    }

    /**
     * True when every trips row has a non-zero {@code size_bytes}, i.e. the
     * one-shot backfill has filled all legacy rows. Until this returns true
     * {@link #getTotalSizeBytes()} undercounts and StorageManager should
     * fall back to the slow filesystem walk (cached for 30s).
     */
    public boolean isBackfillComplete() {
        if (!ensureConnection()) return false;

        synchronized (this) {
            // LIMIT 1 keeps this O(1) even with hundreds of legacy rows —
            // we only need to know "any zero?", not the exact count.
            String sql = "SELECT 1 FROM trips WHERE size_bytes = 0 LIMIT 1";
            try (PreparedStatement pstmt = connection.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                return !rs.next();
            } catch (Exception e) {
                logger.error("Failed to check backfill completeness", e);
                reconnect();
                return false;
            }
        }
    }

    /**
     * One-shot background backfill of {@code size_bytes} for legacy rows
     * (rows inserted before the size-accounting migration). Idempotent —
     * skipped entirely once every row has a non-zero size.
     *
     * <p>Runs on a single daemon thread named "TripsSizeBackfill". Each
     * UPDATE acquires {@code synchronized(this)} so it interleaves cleanly
     * with concurrent {@link #insertTrip} / {@link #updateTrip} calls
     * coming from the trip detector — H2 embedded mode shares one
     * connection across threads so the lock is required to keep the JDBC
     * cursor state consistent. Processed in 100-row batches with a 50ms
     * sleep between batches so the backfill never starves the API
     * handler thread on a database with thousands of legacy rows.
     *
     * <p>All file I/O (the {@code File.length()} stat) happens OUTSIDE the
     * lock so the FUSE walk doesn't block live insertTrip — this is the
     * whole reason we're moving the size accounting off the request path
     * in the first place.
     */
    private void runBackfillIfNeeded() {
        Thread t = new Thread(() -> {
            try {
                if (isBackfillComplete()) {
                    return; // No legacy rows; nothing to do.
                }

                long startedMs = System.currentTimeMillis();
                logger.info("Trips size backfill starting");

                int processed = 0;
                int updated = 0;
                final int batchSize = 100;
                long lastId = -1;

                while (true) {
                    // Pull a page of legacy rows. Re-running the query each
                    // batch (vs. holding one cursor open) lets us release
                    // the connection between batches so foreground inserts
                    // don't queue behind the backfill.
                    List<long[]> batch = new ArrayList<>(batchSize);
                    List<String> paths = new ArrayList<>(batchSize);

                    synchronized (this) {
                        if (!ensureConnection()) {
                            logger.warn("Trips size backfill aborted — DB connection unavailable");
                            return;
                        }
                        String sql = "SELECT id, telemetry_file_path FROM trips " +
                                "WHERE size_bytes = 0 AND id > ? ORDER BY id ASC LIMIT ?";
                        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                            pstmt.setLong(1, lastId);
                            pstmt.setInt(2, batchSize);
                            try (ResultSet rs = pstmt.executeQuery()) {
                                while (rs.next()) {
                                    long id = rs.getLong("id");
                                    String path = rs.getString("telemetry_file_path");
                                    batch.add(new long[]{id});
                                    paths.add(path);
                                    lastId = id;
                                }
                            }
                        } catch (Exception e) {
                            logger.error("Trips size backfill: batch fetch failed", e);
                            return;
                        }
                    }

                    if (batch.isEmpty()) break; // Done.

                    // Stat files OUTSIDE the lock — FUSE / SD walks must
                    // not block live inserts.
                    long[] sizes = new long[batch.size()];
                    for (int i = 0; i < batch.size(); i++) {
                        String p = paths.get(i);
                        long sz = 0;
                        if (p != null && !p.isEmpty()) {
                            try {
                                java.io.File f = new java.io.File(p);
                                if (f.exists() && f.isFile()) {
                                    sz = f.length();
                                }
                            } catch (Throwable ignored) {
                                // Permissions / FUSE blip — leave at 0 so
                                // we retry on the next daemon start.
                            }
                        }
                        sizes[i] = sz;
                    }

                    // Apply UPDATEs under the lock. We only write rows
                    // where the stat succeeded (size > 0). Orphan trips
                    // (file gone) stay at 0 and are skipped on every
                    // future backfill run; the WHERE size_bytes = 0
                    // pagination above means they'd be re-visited every
                    // restart, so we walk past them via lastId rather
                    // than re-statting on every iteration of the loop.
                    synchronized (this) {
                        if (!ensureConnection()) {
                            logger.warn("Trips size backfill aborted mid-batch — DB unavailable");
                            return;
                        }
                        try (PreparedStatement upd = connection.prepareStatement(
                                "UPDATE trips SET size_bytes = ? WHERE id = ?")) {
                            for (int i = 0; i < batch.size(); i++) {
                                if (sizes[i] <= 0) continue; // orphan — leave at 0
                                upd.setLong(1, sizes[i]);
                                upd.setLong(2, batch.get(i)[0]);
                                upd.executeUpdate();
                                updated++;
                            }
                        } catch (Exception e) {
                            logger.error("Trips size backfill: batch update failed", e);
                            // Continue — partial progress is fine, next
                            // restart will re-pick the un-updated rows.
                        }
                    }

                    processed += batch.size();
                    if (processed % 100 == 0 || processed < 100) {
                        logger.info("Trips size backfill: " + processed + " rows scanned, "
                                + updated + " updated");
                    }

                    // Tiny pause so the API handler thread always wins
                    // contention against the backfill on a busy DB.
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }

                long elapsedMs = System.currentTimeMillis() - startedMs;
                logger.info("Trips size backfill complete in " + elapsedMs + "ms ("
                        + processed + " scanned, " + updated + " updated)");
            } catch (Throwable t2) {
                // A backfill crash MUST NOT crash the daemon — the worst
                // case is the slow FUSE walk falls back into use.
                logger.error("Trips size backfill crashed", t2);
            }
        }, "TripsSizeBackfill");
        t.setDaemon(true);
        t.start();
    }
}
