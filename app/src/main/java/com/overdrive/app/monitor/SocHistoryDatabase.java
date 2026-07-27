package com.overdrive.app.monitor;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SOTA SocHistoryDatabase - Uses H2 embedded database (100% pure Java).
 * 
 * H2 advantages over SQLite/SQLDroid:
 * - Zero native dependencies (no .so files, no UnsatisfiedLinkError)
 * - Zero Android framework dependency (no Context, no package verification)
 * - Full SQL support with SQLite compatibility mode
 * - Works perfectly for UID 2000 daemon processes
 */
public class SocHistoryDatabase {
    
    private static final String TAG = "SocHistoryDatabase";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // H2 JDBC URL - file-based embedded database
    // FILE_LOCK=SOCKET uses socket-based locking (more reliable than file locks on Android)
    // AUTO_SERVER=TRUE allows multiple processes to connect via TCP fallback
    private static final String DB_PATH = "/data/local/tmp/overdrive_soc_h2";
    // DB_CLOSE_ON_EXIT=FALSE: we drive shutdown ourselves from CameraDaemon.shutdown().
    // Without it, H2's JVM shutdown hook runs concurrently with our explicit
    // stop() and our last in-flight 2-minute SOC tick, producing the
    // "Database is already closed" + "Could not save properties …lock.db"
    // pair that orphans the lock file across daemon restarts.
    //
    // AUTO_SERVER intentionally omitted — H2 throws
    // "AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE is not supported" if both
    // are set. We're single-process anyway (only the camera daemon writes;
    // HTTP reads happen in the same JVM via NotificationApiHandler). The
    // FILE_LOCK=SOCKET is the actual cross-process safety net.
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
        ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE";
    
    // Table names
    private static final String TABLE_SOC = "soc_history";
    private static final String TABLE_CHARGING = "charging_sessions";
    private static final String TABLE_ACC_EVENTS = "acc_events";
    private static final String TABLE_CPS = "charging_power_samples";  // per-session ramp curves
    private static final String TABLE_CHARGING_DAILY = "charging_daily"; // permanent rollup
    private static final String TABLE_SOC_DAILY = "soc_daily";           // permanent rollup

    // Retention periods (per-table — see cleanupOldData()).
    //
    // The Charging feature treats discrete charging sessions and the daily
    // rollups as PERMANENT history (a few hundred tiny rows/year), while the
    // bulky time-series tables stay bounded:
    //   - charging_sessions / charging_daily / soc_daily : forever
    //   - soc_history           : 30 days (rolled into soc_daily before prune)
    //   - charging_power_samples: 60 days
    //   - acc_events            : 90 days (previously never pruned — growth bug)
    private static final long SOC_RETENTION_DAYS = 30;
    private static final long CPS_RETENTION_DAYS = 60;
    private static final long ACC_RETENTION_DAYS = 90;
    private static final long SAMPLE_INTERVAL_MS = 120_000;  // 2 minutes - SOTA interval for daemon recording

    // Charging-session merge window. A charging "start" whose gap to the most
    // recent session's last activity is within this window is treated as a
    // RESUME of that session, not a new one. Covers daemon restarts and brief
    // charging-state flickers (gun reseat, BMS handshake re-negotiation) that
    // would otherwise fragment one physical charge into several rows. 15 min
    // is long enough to ride out a restart + reconnect, short enough that two
    // genuinely separate charges in the same spot aren't glued together.
    private static final long CHARGING_MERGE_GAP_MS = 15 * 60 * 1000L;
    
    // Singleton
    private static SocHistoryDatabase instance;
    private static final Object lock = new Object();
    
    // H2 Connection (kept open for performance)
    private Connection connection;
    
    private ScheduledExecutorService scheduler;
    private volatile boolean isRunning = false;
    private volatile boolean isInitialized = false;
    
    // Charging session tracking
    private volatile boolean wasCharging = false;
    private volatile long chargingStartTime = 0;
    private volatile double chargingStartSoc = 0;
    // Opt-in flag for Charging Analytics, pushed by ChargingSessionManager from
    // ChargingConfig. Default false (matches ChargingConfig's opt-in default);
    // when false, trackChargingSession records nothing. Starting disabled ensures
    // any SoC ticks that fire before ChargingSessionManager.init() pushes the real
    // config value cannot record sessions for a feature the user never enabled.
    // volatile: set from the manager thread, read on the SoC sampler thread.
    private volatile boolean chargingAnalyticsEnabled = false;
    // Running aggregates accumulated across the live session (reset on each START).
    // peakPower was previously frozen at the start-instant power and never
    // updated; it is now a true running max persisted mid-session and at end.
    private volatile double chargingPeakPower = 0;
    private volatile double chargingPowerSum = 0;
    private volatile int chargingPowerCount = 0;
    private volatile int chargingStartRange = -1;
    // Vehicle odometer (km) snapshotted at charge START. -1 = not yet captured;
    // like chargingStartRange it's backfilled on a later mid-session tick if the
    // HAL wasn't reporting mileage at the exact start instant (common during
    // ACC-off parked charging), and preserved across a daemon-restart resume.
    private volatile int chargingStartOdometer = -1;
    private volatile int chargingGunState = -1;
    // Latched estimated time-to-full (minutes). The BYD rest-time field is only
    // meaningful WHILE charging — at session end it reads ~0 / stale. We capture
    // the latest plausible value on each mid-session tick and persist that.
    private volatile int chargingTimeToFullMin = -1;
    // Location of the current charge (snapshot at START). 0/0 = unavailable.
    private volatile double chargingStartLat = 0;
    private volatile double chargingStartLng = 0;

    // Last recorded values for deduplication
    private long lastRecordTime = 0;
    private double lastRecordedSoc = -1;
    private double lastRecordedKwh = -1;
    
    // SohEstimator reference (set externally)
    private volatile com.overdrive.app.abrp.SohEstimator sohEstimator;
    
    private SocHistoryDatabase() {
        // Load the H2 JDBC driver (pure Java - always works)
        try {
            Class.forName("org.h2.Driver");
            logger.info("H2 JDBC Driver loaded successfully");
        } catch (ClassNotFoundException e) {
            logger.error("H2 Driver not found! Check gradle dependencies.", e);
        } catch (Exception e) {
            logger.error("Failed to load H2 Driver: " + e.getMessage(), e);
        }
    }
    
    public static SocHistoryDatabase getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    instance = new SocHistoryDatabase();
                }
            }
        }
        return instance;
    }
    
    // ==================== LIFECYCLE ====================
    
    public void init() {
        if (isInitialized) return;
        
        synchronized (lock) {
            if (isInitialized) return;  // Double-check after acquiring lock
            
            logger.info("Initializing H2 database at: " + DB_PATH);
            
            int maxRetries = 3;
            int retryDelayMs = 1000;
            
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    // Open H2 connection (pure Java - no native code)
                    connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                    logger.info("H2 connection established");
                    
                    // Tune H2 for embedded daemon use
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute("SET CACHE_SIZE 8192");  // 8MB cache
                    }
                    
                    // Create tables
                    createTables();
                    
                    isInitialized = true;
                    logger.info("SOC History Database initialized via H2 (Pure Java): " + DB_PATH);
                    return;  // Success - exit
                    
                } catch (Exception e) {
                    String msg = e.getMessage();
                    boolean isLockError = msg != null && (msg.contains("Locked by another process") || 
                        msg.contains("lock.db") || msg.contains("already in use"));
                    
                    if (isLockError && attempt < maxRetries) {
                        logger.warn("Database locked (attempt " + attempt + "/" + maxRetries + "), cleaning up stale locks...");
                        cleanupStaleLocks();
                        try {
                            Thread.sleep(retryDelayMs * attempt);  // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        logger.error("Failed to initialize SOC database: " + e.getClass().getName() + " - " + msg, e);
                        break;
                    }
                }
            }
        }
    }
    
    /**
     * Clean up stale lock files that may have been left by crashed processes.
     */
    private void cleanupStaleLocks() {
        try {
            java.io.File lockFile = new java.io.File(DB_PATH + ".lock.db");
            if (lockFile.exists()) {
                // Check if the lock file is stale (older than 5 minutes with no active process)
                long ageMs = System.currentTimeMillis() - lockFile.lastModified();
                if (ageMs > 5 * 60 * 1000) {  // 5 minutes
                    if (lockFile.delete()) {
                        logger.info("Deleted stale lock file (age: " + (ageMs / 1000) + "s)");
                    }
                }
            }
            
            // Also try to clean up trace files
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
            // SOC history table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_SOC + " (" +
                "id IDENTITY PRIMARY KEY," +
                "timestamp BIGINT NOT NULL," +
                "soc_percent REAL NOT NULL," +
                "is_charging INTEGER DEFAULT 0," +
                "charging_power_kw REAL DEFAULT 0," +
                "voltage_v REAL DEFAULT 0," +
                "range_km INTEGER DEFAULT 0," +
                "remaining_kwh REAL DEFAULT 0" +
                ");"
            );
            
            // Add remaining_kwh column if it doesn't exist (migration for existing DBs)
            try {
                stmt.execute("ALTER TABLE " + TABLE_SOC + " ADD COLUMN IF NOT EXISTS remaining_kwh REAL DEFAULT 0;");
            } catch (Exception ignored) {
                // Column may already exist
            }
            
            // Migration: add battery health columns
            String[] newColumns = {
                "hv_temp_high REAL DEFAULT -999",
                "hv_temp_low REAL DEFAULT -999",
                "hv_temp_avg REAL DEFAULT -999",
                "cell_volt_high REAL DEFAULT -999",
                "cell_volt_low REAL DEFAULT -999",
                "soh_percent REAL DEFAULT -999"
            };
            for (String col : newColumns) {
                try {
                    stmt.execute("ALTER TABLE " + TABLE_SOC + " ADD COLUMN IF NOT EXISTS " + col + ";");
                } catch (Exception ignored) {}
            }
            
            // Index for fast time-based queries
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_soc_timestamp ON " + TABLE_SOC + "(timestamp);"
            );
            
            // Charging sessions table
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_CHARGING + " (" +
                "id IDENTITY PRIMARY KEY," +
                "start_time BIGINT NOT NULL," +
                "end_time BIGINT," +
                "start_soc REAL NOT NULL," +
                "end_soc REAL," +
                "energy_added_kwh REAL," +
                "peak_power_kw REAL" +
                ");"
            );

            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_charging_start ON " + TABLE_CHARGING + "(start_time);"
            );

            // charging_sessions v2 migration (idempotent — same shape as the
            // soc_history migration above). peak_power_kw is NOT here: it already
            // exists from v1; only its write semantics change (true running max).
            // Numeric sentinels (-1 / -999) match the soc_history convention so
            // readers gate on `col > -1` / `col > -999`.
            String[] newChargingCols = {
                "avg_power_kw REAL DEFAULT -1",        // running mean over session ticks
                "range_gained_km INTEGER DEFAULT -1",  // elecRangeKm(end) - elecRangeKm(start)
                "gun_state INTEGER DEFAULT -1",        // chargingGunState at start: 2=AC 3=DC 4=AC_DC 5=V2L
                "is_dc INTEGER DEFAULT -1",            // 1=DC, 0=AC, -1=unknown (derived from gun_state)
                "electricity_rate REAL DEFAULT -1",    // rate snapshot at session end
                "currency VARCHAR(8) DEFAULT ''",      // currency snapshot at session end
                "session_cost REAL DEFAULT -1",        // energy_added_kwh * electricity_rate
                "time_to_full_min INTEGER DEFAULT -1", // restTimeHours*60 + restTimeMinutes at end
                "hv_temp_high REAL DEFAULT -999",      // pack thermal at session end
                "hv_temp_low REAL DEFAULT -999",
                "hv_temp_avg REAL DEFAULT -999",
                // v3: where the charge happened. lat/lng snapshot at session
                // start; place_label is filled async by GeocodingResolver
                // (SafeLocation "Home"/"Office" name first, else reverse-geocode).
                "start_lat DOUBLE DEFAULT 0",
                "start_lng DOUBLE DEFAULT 0",
                "place_label VARCHAR(96) DEFAULT ''",
                // v4: elecRangeKm at session start. Persisted so a session
                // RESUMED after a daemon restart can still compute range_gained
                // against its true origin (was lost before — resume reset it).
                "start_range_km INTEGER DEFAULT -1",
                // v5: vehicle odometer (km) at session start. Snapshotted once
                // at the charge edge (backfilled on a later tick if unavailable
                // at start). Shown on the charging card/detail. -1 = unknown.
                "start_odometer_km INTEGER DEFAULT -1"
            };
            for (String col : newChargingCols) {
                try {
                    stmt.execute("ALTER TABLE " + TABLE_CHARGING + " ADD COLUMN IF NOT EXISTS " + col + ";");
                } catch (Exception ignored) {}
            }

            // Per-session power/SoC/temp samples for true ramp curves. Keyed on
            // session_start_time (the key trackChargingSession already uses for
            // the open-session UPDATE), not the IDENTITY id.
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_CPS + " (" +
                "id IDENTITY PRIMARY KEY," +
                "session_start_time BIGINT NOT NULL," +
                "t BIGINT NOT NULL," +
                "power_kw REAL," +
                "soc REAL," +
                "temp REAL" +          // avg cell temp (kept for back-compat)
                ");"
            );
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_cps_session ON " + TABLE_CPS + "(session_start_time);"
            );
            // v2: the pack reports HIGH/LOW/AVG cell temps, not one number. Store
            // the spread so the detail temp chart can draw a high–low band with
            // the avg line, instead of a single ambiguous "battery temp" trace.
            for (String tcol : new String[]{ "temp_high REAL DEFAULT -999", "temp_low REAL DEFAULT -999" }) {
                try { stmt.execute("ALTER TABLE " + TABLE_CPS + " ADD COLUMN IF NOT EXISTS " + tcol + ";"); }
                catch (Exception ignored) {}
            }

            // Permanent daily rollups so long-term cost / SOH-degradation trends
            // survive the soc_history / charging_power_samples prune. day_epoch is
            // the UTC-midnight bucket key (timestamp/86400000 * 86400000).
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_CHARGING_DAILY + " (" +
                "day_epoch BIGINT PRIMARY KEY," +
                "session_count INTEGER DEFAULT 0," +
                "energy_kwh REAL DEFAULT 0," +
                "cost REAL DEFAULT 0," +
                "dc_count INTEGER DEFAULT 0," +
                "ac_count INTEGER DEFAULT 0," +
                "peak_power_kw REAL DEFAULT 0," +
                "soh_at_day REAL DEFAULT -999," +
                "range_gained_km INTEGER DEFAULT 0" +
                ");"
            );
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_SOC_DAILY + " (" +
                "day_epoch BIGINT PRIMARY KEY," +
                "min_soc REAL," +
                "max_soc REAL," +
                "avg_soc REAL," +
                "soh_percent REAL DEFAULT -999," +
                "hv_temp_avg REAL DEFAULT -999," +
                "sample_count INTEGER DEFAULT 0" +
                ");"
            );

            // ACC events table — every ACC ON/OFF transition is logged here so
            // the dashboard "parking delta" insight can compute changes across
            // a real park-and-return cycle (not inferred from SOC sample gaps).
            // Snapshot fields are nullable: if BydDataCollector is not yet
            // initialized at the moment of the event we still record the
            // transition so future correlation is possible.
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_ACC_EVENTS + " (" +
                "id IDENTITY PRIMARY KEY," +
                "timestamp BIGINT NOT NULL," +
                "event_type VARCHAR(8) NOT NULL," +    // 'ON' or 'OFF'
                "soc_percent REAL," +                  // nullable if read failed
                "remaining_kwh REAL," +                // nullable
                "voltage_v REAL," +                    // nullable
                "range_km INTEGER" +                   // nullable
                ");"
            );

            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_acc_events_ts ON " + TABLE_ACC_EVENTS + "(timestamp DESC);"
            );

            logger.info("acc_events table ready (migration idempotent)");
        }
    }

    public void start() {
        if (isRunning) return;
        
        if (!isInitialized) {
            init();
        }
        
        if (!isInitialized) {
            logger.error("Cannot start SOC history - database init failed");
            return;
        }
        
        isRunning = true;
        
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SocHistoryDB");
            t.setPriority(Thread.MIN_PRIORITY);
            // Set uncaught exception handler to prevent silent death
            t.setUncaughtExceptionHandler((thread, ex) -> {
                logger.error("Uncaught exception in SocHistoryDB thread: " + ex.getMessage(), ex);
            });
            return t;
        });
        
        // Record SOC every minute - wrap in Runnable that catches all exceptions
        scheduler.scheduleAtFixedRate(() -> {
            try {
                recordCurrentSoc();
            } catch (Throwable t) {
                // Catch everything including Errors to prevent scheduler death
                logger.error("Critical error in SOC recording task: " + t.getMessage(), 
                    t instanceof Exception ? (Exception) t : new Exception(t));
            }
        }, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // Cleanup old data daily
        scheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupOldData();
            } catch (Throwable t) {
                logger.error("Critical error in cleanup task: " + t.getMessage(),
                    t instanceof Exception ? (Exception) t : new Exception(t));
            }
        }, 1, 24, TimeUnit.HOURS);
        
        logger.info("SOC history recording started (interval: " + SAMPLE_INTERVAL_MS + "ms)");
    }
    
    public void stop() {
        isRunning = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                // Give an in-flight tick a moment to finish so we don't close
                // the connection out from under it. shutdownNow() interrupts
                // the worker but doesn't wait — and the H2 write isn't
                // interruptible, so the tick still hits the JDBC layer with
                // a closed connection.
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException ie) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }

        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {}
            connection = null;
        }
        isInitialized = false;

        logger.info("SOC history recording stopped");
    }
    
    private void reconnect() {
        // After stop() flips isRunning=false the connection is intentionally
        // closed. Re-opening here would re-acquire the lock file just before
        // the JVM exits, leaving an orphaned .lock.db that blocks the next
        // daemon start. Same defense in TripDatabase.reconnect.
        if (!isRunning) return;
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                logger.debug("H2 connection re-established");
            }
        } catch (Exception e) {
            logger.error("Failed to reconnect to H2", e);
        }
    }
    
    // ==================== DATA RECORDING ====================
    
    private void recordCurrentSoc() {
        // Wrap entire method in try-catch to prevent scheduler death
        try {
            // Bail out cleanly when stop() has already begun — otherwise we
            // race connection.close() and trip H2's "already closed" path,
            // which re-opens the DB on reconnect() and orphans the lock file.
            if (!isRunning) return;
            if (!isInitialized || connection == null) {
                logger.debug("SOC recording skipped: not initialized or no connection");
                reconnect();
                return;
            }
            
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            if (monitor == null) {
                logger.debug("SOC recording skipped: VehicleDataMonitor not available");
                return;
            }
            
            BatterySocData socData = monitor.getBatterySoc();
            ChargingStateData chargingData = monitor.getChargingState();
            DrivingRangeData rangeData = monitor.getDrivingRange();
            BatteryPowerData powerData = monitor.getBatteryPower();
            
            if (socData == null) {
                logger.debug("SOC recording skipped: no SOC data available");
                return;
            }
            
            double soc = socData.socPercent;
            boolean isCharging = chargingData != null &&
                chargingData.status == ChargingStateData.ChargingStatus.CHARGING;
            // Use the charger power ONLY when it's a real reading. getChargingState()
            // substitutes a nominal PLACEHOLDER (3.3 kW PHEV / 7.0 kW BEV, flagged
            // isEstimated) before the BMS reports real kW. Feeding that into the
            // session tracker seeded peak_power at 7.0 → the session mis-classified
            // as "AC fast" (>=7 kW) when it was really a 6 kW AC slow charge. Pass 0
            // for estimated reads so peak/avg only ever reflect measured power.
            double chargingPower = (chargingData != null && !chargingData.isEstimated)
                ? chargingData.chargingPowerKW : 0;
            double voltage = powerData != null ? powerData.voltageVolts : 0;
            int range = rangeData != null ? rangeData.elecRangeKm : 0;
            
            // SOTA: Get remaining battery power in kWh from BYDAutoPowerDevice
            double remainingKwh = 0;
            try {
                remainingKwh = monitor.getBatteryRemainPowerKwh();
            } catch (Exception e) {
                logger.debug("Failed to get remaining kWh: " + e.getMessage());
            }
            
            // Shape B: live formula drives SOH directly from this tick's
            // remainKwh + SOC. Feed RAW vd.remainKwh, never the synthesized
            // value from getBatteryRemainPowerKwh — the synthesizer falls
            // back to (soc/100 × nominal × currentSoh/100) on PHEV / bad-BMS
            // paths, which would loop currentSoh into itself and freeze
            // the formula at its initial seed forever.
            try {
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                if (sohEst != null) {
                    if (sohEst.getNominalCapacityKwh() <= 0) {
                        sohEst.autoDetectCarModel(
                            com.overdrive.app.daemon.CameraDaemon.getAppContext());
                    }
                    if (sohEst.getNominalCapacityKwh() > 0 && !sohEst.hasEstimate()) {
                        sohEst.seedInitialEstimate();
                    }

                    double rawRemainKwh = Double.NaN;
                    double highCellV = Double.NaN;
                    try {
                        com.overdrive.app.byd.BydDataCollector col = com.overdrive.app.byd.BydDataCollector.getInstance();
                        if (col != null && col.isInitialized()) {
                            com.overdrive.app.byd.BydVehicleData vd = col.getData();
                            if (vd != null) {
                                if (!Double.isNaN(vd.remainKwh)) rawRemainKwh = vd.remainKwh;
                                if (!Double.isNaN(vd.highCellVoltage)) highCellV = vd.highCellVoltage;
                            }
                        }
                    } catch (Exception ignored) { /* leave NaN */ }

                    // Drivetrain gate: the live `remainKwh / SOC` SOH formula and
                    // the peak-charge frame anchor BOTH consume the raw BYD getter,
                    // which on PHEV is unreliable (half-scale / stale-when-ICE-runs /
                    // frame-ambiguous — a single sample cannot tell half from gross).
                    // Feeding them on PHEV produced the noisy 92-99% SOH and the
                    // frozen-22.4 → 110% rail. So on PHEV we drive SOH ONLY from the
                    // independent anchors below (capacity-Ah coulomb count + the
                    // calibration charge-cycle integration); currentSoh stays at its
                    // honest 100% default until one of those proves real degradation.
                    // BEV keeps the live formula — its getBatteryRemainPowerEV is
                    // authoritative.
                    boolean isPhevForSoh = false;
                    try {
                        com.overdrive.app.byd.BydDataCollector pcol =
                            com.overdrive.app.byd.BydDataCollector.getInstance();
                        isPhevForSoh = pcol != null && pcol.isInitialized() && pcol.isPhevPublic();
                    } catch (Throwable ignored) {}

                    if (!isPhevForSoh && rawRemainKwh > 0 && soc > 0
                            && sohEst.getNominalCapacityKwh() > 0) {
                        double impliedCap = rawRemainKwh / (soc / 100.0);
                        double nominal = sohEst.getNominalCapacityKwh();
                        double ratio = impliedCap / nominal;
                        // BEV: trust the raw reading within a plausible band (pack
                        // can't exceed nameplate → 1.12; degraded reads below → 0.5).
                        if (ratio >= 0.5 && ratio <= 1.12) {
                            boolean atRest = isVehicleAtRest();
                            sohEst.updateFromEnergy(rawRemainKwh, soc, highCellV, atRest);
                        }
                    }

                    // PHEV SOH is intentionally NOT driven by the BMS capacity-Ah
                    // anchor. On DM-i firmware getBatteryCapacity() returns the
                    // STATIC factory nameplate Ah (observed constant across SOC
                    // swings), not a live coulomb count — feeding it made a healthy
                    // pack read phantom degradation (field capture: reported 54 "Ah"
                    // vs a derived ~71 Ah nominal → 76% SOH on a ~7k-km car). Because
                    // the value never moves, no cell-count correction can turn it into
                    // a real health signal. A real ≥25% charge-session calibration is
                    // the only trusted PHEV degradation signal; until one lands,
                    // getDisplaySoh() reports the honest 100% default. BEV is
                    // unaffected — it never fed this anchor (updateFromCapacityAh
                    // early-returns on !isPhev).
                }
            } catch (Exception e) {
                logger.debug("SOH update failed: " + e.getMessage());
            }
            
            // HV battery thermal data — from BydDataCollector (has real cell temps via Integer.TYPE)
            double hvTempHigh = -999, hvTempLow = -999, hvTempAvg = -999;
            double cellVoltHigh = -999, cellVoltLow = -999;
            try {
                com.overdrive.app.byd.BydDataCollector collector = com.overdrive.app.byd.BydDataCollector.getInstance();
                if (collector.isInitialized()) {
                    com.overdrive.app.byd.BydVehicleData vd = collector.getData();
                    if (vd != null) {
                        if (!Double.isNaN(vd.highCellTempC)) hvTempHigh = vd.highCellTempC;
                        if (!Double.isNaN(vd.lowCellTempC)) hvTempLow = vd.lowCellTempC;
                        if (!Double.isNaN(vd.avgCellTempC)) hvTempAvg = vd.avgCellTempC;
                        if (!Double.isNaN(vd.highCellVoltage)) cellVoltHigh = vd.highCellVoltage;
                        if (!Double.isNaN(vd.lowCellVoltage)) cellVoltLow = vd.lowCellVoltage;
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to get collector data: " + e.getMessage());
            }
            // Fallback to VehicleDataMonitor if collector didn't have temps
            if (hvTempHigh == -999 && hvTempLow == -999 && hvTempAvg == -999) {
                BatteryThermalData thermalData = monitor.getBatteryThermal();
                if (thermalData != null && thermalData.hasData()) {
                    if (!Double.isNaN(thermalData.highestTempC)) hvTempHigh = thermalData.highestTempC;
                    if (!Double.isNaN(thermalData.lowestTempC)) hvTempLow = thermalData.lowestTempC;
                    if (!Double.isNaN(thermalData.averageTempC)) hvTempAvg = thermalData.averageTempC;
                }
            }
            
            // SOH from SohEstimator (via AbrpTelemetryService)
            double sohPercent = -999;
            try {
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                if (sohEst != null && sohEst.hasDisplaySoh()) {
                    // Displayed (capped, anchored) SOH so stored history agrees with
                    // every live surface.
                    sohPercent = sohEst.getDisplaySoh();
                    logger.debug("SOH from estimator: " + String.format("%.1f", sohPercent) + "%");
                } else {
                    // Fallback: read from persisted file
                    logger.info("SOH estimator " + (sohEst == null ? "is null" : "has no estimate") + ", trying persisted file fallback");
                    java.io.File sohFile = new java.io.File("/data/local/tmp/abrp_soh_estimate.properties");
                    if (sohFile.exists()) {
                        java.util.Properties props = new java.util.Properties();
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(sohFile)) {
                            props.load(fis);
                        }
                        String sohStr = props.getProperty("soh_percent");
                        if (sohStr != null) {
                            double soh = Double.parseDouble(sohStr);
                            if (soh > 0 && soh <= 100) {
                                sohPercent = soh;
                                logger.info("SOH from persisted file fallback: " + soh + "%");
                            }
                        }
                    } else {
                        logger.info("SOH persisted file not found at /data/local/tmp/abrp_soh_estimate.properties");
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to get SOH: " + e.getMessage());
            }
            
            long now = System.currentTimeMillis();

            // Record at least once every 10 minutes regardless of SOC change
            // This ensures continuous data even when parked (5x the 2-min interval)
            long maxInterval = SAMPLE_INTERVAL_MS * 5; // 10 minutes
            boolean forceRecord = (now - lastRecordTime) >= maxInterval;

            // Always record on charging-state transitions so the chart's charging
            // band and the charging_sessions table both see the start/end edges
            // even when SOC hasn't moved 0.5% yet (typical for the first minutes
            // of AC charging on a PHEV, and for any unplug while at 100%).
            boolean stateTransition = (isCharging != wasCharging);

            // BEV BMS reports remainKwh independently of SOC and can drift while
            // SOC stays in the same percent bucket — record those updates too.
            boolean kwhMoved = lastRecordedKwh >= 0 && remainingKwh > 0
                && Math.abs(remainingKwh - lastRecordedKwh) >= 0.5;

            // Skip only if nothing meaningful changed AND we recorded recently
            if (!forceRecord && !stateTransition && !kwhMoved
                    && lastRecordedSoc >= 0 && Math.abs(soc - lastRecordedSoc) < 0.5) {
                return;
            }
            
            // Check connection is still valid
            try {
                if (connection.isClosed()) {
                    logger.info("Connection closed, reconnecting...");
                    reconnect();
                    if (connection == null || connection.isClosed()) {
                        logger.error("Failed to reconnect to database");
                        return;
                    }
                }
            } catch (Exception e) {
                logger.error("Connection check failed", e);
                reconnect();
                return;
            }
            
            // Insert with all battery health columns
            String sql = "INSERT INTO " + TABLE_SOC + 
                " (timestamp, soc_percent, is_charging, charging_power_kw, voltage_v, range_km, remaining_kwh," +
                " hv_temp_high, hv_temp_low, hv_temp_avg, cell_volt_high, cell_volt_low, soh_percent) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, now);
                pstmt.setDouble(2, soc);
                pstmt.setInt(3, isCharging ? 1 : 0);
                pstmt.setDouble(4, chargingPower);
                pstmt.setDouble(5, voltage);
                pstmt.setInt(6, range);
                pstmt.setDouble(7, remainingKwh);
                pstmt.setDouble(8, hvTempHigh);
                pstmt.setDouble(9, hvTempLow);
                pstmt.setDouble(10, hvTempAvg);
                pstmt.setDouble(11, cellVoltHigh);
                pstmt.setDouble(12, cellVoltLow);
                pstmt.setDouble(13, sohPercent);
                pstmt.executeUpdate();
            }
            
            lastRecordTime = now;
            lastRecordedSoc = soc;
            if (remainingKwh > 0) lastRecordedKwh = remainingKwh;

            logger.debug("Recorded SOC: " + soc + "% (charging: " + isCharging + ")");
            
            // Track charging sessions
            trackChargingSession(isCharging, soc, chargingPower, now);
            
        } catch (Exception e) {
            // Log but don't rethrow - scheduler must continue running
            logger.error("Failed to record SOC: " + e.getMessage(), e);
            try {
                reconnect();
            } catch (Exception re) {
                logger.error("Reconnect also failed: " + re.getMessage());
            }
        }
    }
    
    private void trackChargingSession(boolean isCharging, double soc, double power, long now) {
        if (!isInitialized || connection == null) return;

        // Opt-in gate: Charging Analytics is an opt-out-able feature. When the
        // user has disabled it (chargingAnalytics.enabled=false, pushed here by
        // ChargingSessionManager), record NOTHING — no session rows, no config
        // reads, no rollups — so a disabled feature costs zero extra work on
        // the always-on 2-minute SoC tick. If a session was mid-flight when the
        // user disabled, reset the live state so we don't leave a dangling open
        // row to "end" later.
        if (!chargingAnalyticsEnabled) {
            if (wasCharging) {
                // Close any open session that was mid-flight when feature was disabled
                try {
                    if (connection != null && !connection.isClosed()) {
                        // Update the open row with end values and mark it as ended.
                        // Persist ALL v2 columns (range/isDc/rate/currency/cost/ttf/
                        // thermal) just like the normal SESSION END flow, so an
                        // interrupted session isn't left with sentinel defaults.
                        String sql = "UPDATE " + TABLE_CHARGING +
                            " SET end_time = ?, end_soc = ?, energy_added_kwh = ?, avg_power_kw = ?, peak_power_kw = ?, " +
                            "range_gained_km = ?, is_dc = ?, electricity_rate = ?, currency = ?, session_cost = ?, " +
                            "time_to_full_min = ?, hv_temp_high = ?, hv_temp_low = ?, hv_temp_avg = ? " +
                            "WHERE start_time = ? AND end_time IS NULL;";
                        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                            // Prefer ∫P·dt over recorded samples (same basis as the
                            // normal SESSION END path), only falling back to SOC-delta
                            // when integration yields nothing — a slow charge that never
                            // moved a whole percent would otherwise blank energy/cost.
                            double nominalKwh = getSohEstimator() != null ? getSohEstimator().getNominalCapacityKwh() : 0;
                            double energyAdded = integrateSessionEnergyKwh(chargingStartTime);
                            if (energyAdded <= 0) {
                                energyAdded = (soc - chargingStartSoc) / 100.0 *
                                    (nominalKwh > 0 ? nominalKwh : 60);
                            }
                            double avgPower = chargingPowerCount > 0 ? chargingPowerSum / chargingPowerCount : -1;

                            // End-time / daily-bucket key: prefer the last recorded
                            // power sample over `now`. A long parked charge whose
                            // last activity was hours/days ago must NOT be folded
                            // into TODAY's daily rollup (it would skew period
                            // aggregates). Mirrors finalizeOneStaleSession, which
                            // keys on the last sample time. Falls back to the
                            // session start when no samples exist.
                            long lastSampleT = getLastChargingSampleTime(chargingStartTime);
                            long closeTime = (lastSampleT > 0) ? lastSampleT : chargingStartTime;

                            // Compute additional values needed for daily rollup and session record.
                            // Peak-guarded so a misread DC gun on a low-power charge isn't stored as DC.
                            int isDc = deriveIsDc(chargingGunState, chargingPeakPower);
                            int rangeGained = rangeGainedFromEnergy(energyAdded);
                            // DC sessions bill at the separate DC tariff when set.
                            double rate = effectiveRate(isDc);
                            String curr = getCurrencySymbol();
                            double cost = (rate > 0 && energyAdded > 0) ? energyAdded * rate : -1;
                            int ttf = chargingTimeToFullMin;

                            // Battery temperature at session end
                            double tHi = -999, tLo = -999, tAvg = -999;
                            try {
                                VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
                                BatteryThermalData thermal = monitor.getBatteryThermal();
                                if (thermal != null && thermal.hasData()) {
                                    if (!Double.isNaN(thermal.highestTempC)) tHi = thermal.highestTempC;
                                    if (!Double.isNaN(thermal.lowestTempC)) tLo = thermal.lowestTempC;
                                    if (!Double.isNaN(thermal.averageTempC)) tAvg = thermal.averageTempC;
                                }
                            } catch (Exception e) { /* use defaults */ }

                            pstmt.setLong(1, closeTime);
                            pstmt.setDouble(2, soc);
                            pstmt.setDouble(3, energyAdded);
                            pstmt.setDouble(4, avgPower);
                            pstmt.setDouble(5, chargingPeakPower);
                            pstmt.setInt(6, rangeGained);
                            pstmt.setInt(7, isDc);
                            pstmt.setDouble(8, rate);
                            pstmt.setString(9, curr);
                            pstmt.setDouble(10, cost);
                            pstmt.setInt(11, ttf);
                            pstmt.setDouble(12, tHi);
                            pstmt.setDouble(13, tLo);
                            pstmt.setDouble(14, tAvg);
                            pstmt.setLong(15, chargingStartTime);
                            pstmt.executeUpdate();

                            // Fold this interrupted session into the permanent daily
                            // rollup, keyed on the actual session-end day (closeTime),
                            // not the wall-clock disable moment.
                            foldSessionIntoDaily(closeTime, energyAdded, cost, isDc, chargingPeakPower, rangeGained);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to close charging session on feature disable: " + e.getMessage());
                }
                wasCharging = false;
                // Reset all session aggregates to initial state so a re-enable
                // doesn't mix stale values from the interrupted session into the next one
                chargingStartTime = 0;
                chargingStartSoc = 0;
                chargingPeakPower = 0;
                chargingPowerSum = 0;
                chargingPowerCount = 0;
                chargingStartRange = -1;
                chargingGunState = -1;
                chargingTimeToFullMin = -1;
            }
            return;
        }

        try {
            if (isCharging && !wasCharging) {
                // ---- SESSION START (or RESUME) ----
                // A daemon restart resets wasCharging to false. Without this,
                // an UNINTERRUPTED charge that spans a restart (or a brief
                // charging-state flicker) would open a brand-new session row,
                // fragmenting one physical charge into several. Before inserting,
                // look for a session to RESUME: the most recent one whose gap to
                // `now` is within CHARGING_MERGE_GAP_MS. If found, re-adopt it
                // (restore start time/soc/aggregates) instead of creating a row.
                if (tryResumeChargingSession(now, soc)) {
                    wasCharging = isCharging;
                    return;
                }

                chargingStartTime = now;
                chargingStartSoc = soc;
                // Seed the running max/mean only from a REAL power reading. `power`
                // is 0 here when the start tick had an estimated/placeholder kW
                // (see recordCurrentSoc) — seeding 0 keeps the average honest and
                // lets the first measured tick set the true peak.
                chargingPeakPower = power > 0 ? power : 0;
                chargingPowerSum = power > 0 ? power : 0;
                chargingPowerCount = power > 0 ? 1 : 0;
                chargingStartRange = snapshotRangeKm();
                chargingStartOdometer = snapshotOdometerKm();  // -1 if HAL not reporting yet → backfilled mid-session
                chargingGunState = snapshotGunState();
                chargingTimeToFullMin = snapshotTimeToFullMin();  // first live reading
                // Snapshot where the charge began (0/0 if no GPS fix yet).
                double[] loc = snapshotLocation();
                chargingStartLat = loc[0];
                chargingStartLng = loc[1];

                String sql = "INSERT INTO " + TABLE_CHARGING +
                    " (start_time, start_soc, peak_power_kw, avg_power_kw, gun_state, start_lat, start_lng, start_range_km, start_odometer_km) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);";

                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setLong(1, now);
                    pstmt.setDouble(2, soc);
                    pstmt.setDouble(3, power);
                    pstmt.setDouble(4, power);
                    pstmt.setInt(5, chargingGunState);
                    pstmt.setDouble(6, chargingStartLat);
                    pstmt.setDouble(7, chargingStartLng);
                    pstmt.setInt(8, chargingStartRange);
                    pstmt.setInt(9, chargingStartOdometer);
                    pstmt.executeUpdate();
                }

                // Resolve a human place label asynchronously (SafeLocation
                // "Home"/"Office" name first, else reverse-geocode) and write it
                // back to this session row when it arrives. Best-effort.
                resolvePlaceLabelAsync(chargingStartTime, chargingStartLat, chargingStartLng);

                logger.info("Charging session started at " + soc + "% (gun=" + chargingGunState
                    + ", loc=" + chargingStartLat + "," + chargingStartLng + ")");

            } else if (isCharging && wasCharging) {
                // ---- MID-SESSION TICK ----
                // v1 did nothing here, freezing peak_power_kw at the start
                // instant. Advance the true running max + mean and persist them
                // so an interrupted session still has a meaningful peak/avg.
                if (power > chargingPeakPower) chargingPeakPower = power;
                if (power > 0) { chargingPowerSum += power; chargingPowerCount++; }
                double avgSoFar = chargingPowerCount > 0 ? chargingPowerSum / chargingPowerCount : power;
                // Latch the latest plausible time-to-full while still charging
                // (rest-time reads ~0 once charging stops, so capturing at end
                // would be useless). Keep the most recent positive reading.
                int liveTtf = snapshotTimeToFullMin();
                if (liveTtf > 0) chargingTimeToFullMin = liveTtf;
                // Backfill the start range if it wasn't available at session START
                // (range is often -1 during ACC-off parked charging until the
                // instrument cluster wakes). Capturing the FIRST valid reading and
                // persisting it means range_gained survives a daemon restart —
                // otherwise resume re-anchored to the current range and reset the
                // gain to ~0 every restart.
                boolean backfillRange = false;
                if (chargingStartRange < 0) {
                    int r = snapshotRangeKm();
                    if (r >= 0) { chargingStartRange = r; backfillRange = true; }
                }
                // Backfill the start odometer for the same reason: the cached
                // mileage is often UNAVAILABLE (-1) at the ACC-off charge edge and
                // only appears once the vehicle wakes. Capturing the first valid
                // reading and persisting it means the odometer survives a daemon
                // restart (resume re-reads it from this column, not the live HAL).
                boolean backfillOdo = false;
                if (chargingStartOdometer < 0) {
                    int o = snapshotOdometerKm();
                    if (o >= 0) { chargingStartOdometer = o; backfillOdo = true; }
                }
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING +
                        " SET peak_power_kw = ?, avg_power_kw = ?" +
                        (backfillRange ? ", start_range_km = ?" : "") +
                        (backfillOdo ? ", start_odometer_km = ?" : "") +
                        " WHERE start_time = ? AND end_time IS NULL;")) {
                    pstmt.setDouble(1, chargingPeakPower);
                    pstmt.setDouble(2, avgSoFar);
                    int idx = 3;
                    if (backfillRange) pstmt.setInt(idx++, chargingStartRange);
                    if (backfillOdo) pstmt.setInt(idx++, chargingStartOdometer);
                    pstmt.setLong(idx, chargingStartTime);
                    pstmt.executeUpdate();
                }

            } else if (!isCharging && wasCharging) {
                // ---- SESSION END ----
                double socDelta = soc - chargingStartSoc;

                // Compute energy added using nominal capacity if available,
                // otherwise fall back to rough estimate
                double energyAdded = 0;
                double packTemp = 25.0;    // Default — updated below if available

                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                double nominalKwh = sohEst != null ? sohEst.getNominalCapacityKwh() : 0;

                // Prefer ∫P·dt over the recorded samples — SOC-delta reads ~0 for
                // a slow charge that didn't move a whole percent, which blanked
                // energy/cost/range. Same basis as the live in-progress card.
                energyAdded = integrateSessionEnergyKwh(chargingStartTime);
                if (energyAdded <= 0) {
                    if (nominalKwh > 0 && socDelta > 0) {
                        // Energy added ≈ socDelta% × nominalKwh (LFP: displayed
                        // 0–100% ≈ 100% usable, no 0.95 fudge — updateFromCalibration
                        // applies the chemistry-aware scale internally).
                        energyAdded = (socDelta / 100.0) * nominalKwh;
                    } else {
                        energyAdded = socDelta * 0.6; // Rough fallback
                    }
                }

                // Battery temperature at session end (for calibration + chart).
                double tHi = -999, tLo = -999, tAvg = -999;
                try {
                    VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
                    BatteryThermalData thermal = monitor.getBatteryThermal();
                    if (thermal != null && thermal.hasData()) {
                        if (!Double.isNaN(thermal.highestTempC)) tHi = thermal.highestTempC;
                        if (!Double.isNaN(thermal.lowestTempC)) tLo = thermal.lowestTempC;
                        if (!Double.isNaN(thermal.averageTempC)) { tAvg = thermal.averageTempC; packTemp = tAvg; }
                    }
                } catch (Exception e) { /* use defaults */ }

                // AC/DC from gun state, peak-guarded against a HAL gun misread
                // (a DC flag on a sub-DC-power charge is downgraded to unknown).
                // gun: 2=AC 3=DC 4=AC_DC 5=V2L; AC_DC/V2L/unknown -> -1.
                int isDc = deriveIsDc(chargingGunState, chargingPeakPower);
                boolean isAcCharge = (isDc == 0);

                // Range gained derived from energy × the car's efficiency — the
                // elecRangeKm delta was unavailable during parked charging (always
                // blank) and noisy. Same basis as the live in-progress card.
                int rangeGained = rangeGainedFromEnergy(energyAdded);
                // DC sessions bill at the separate DC tariff when set; AC/unknown
                // fall back to the base rate (see effectiveRate).
                double rate = effectiveRate(isDc);
                String curr = getCurrencySymbol();
                double cost = (rate > 0 && energyAdded > 0) ? energyAdded * rate : -1;
                // Use the value latched WHILE charging — re-reading now would
                // get ~0 since charging just stopped.
                int ttf = chargingTimeToFullMin;
                double avgPower = chargingPowerCount > 0 ? chargingPowerSum / chargingPowerCount : -1;

                String sql = "UPDATE " + TABLE_CHARGING +
                    " SET end_time = ?, end_soc = ?, energy_added_kwh = ?, avg_power_kw = ?, peak_power_kw = ?, " +
                    "range_gained_km = ?, is_dc = ?, electricity_rate = ?, currency = ?, session_cost = ?, " +
                    "time_to_full_min = ?, hv_temp_high = ?, hv_temp_low = ?, hv_temp_avg = ? " +
                    "WHERE start_time = ? AND end_time IS NULL;";

                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setLong(1, now);
                    pstmt.setDouble(2, soc);
                    pstmt.setDouble(3, energyAdded);
                    pstmt.setDouble(4, avgPower);
                    pstmt.setDouble(5, chargingPeakPower);
                    pstmt.setInt(6, rangeGained);
                    pstmt.setInt(7, isDc);
                    pstmt.setDouble(8, rate);
                    pstmt.setString(9, curr);
                    pstmt.setDouble(10, cost);
                    pstmt.setInt(11, ttf);
                    pstmt.setDouble(12, tHi);
                    pstmt.setDouble(13, tLo);
                    pstmt.setDouble(14, tAvg);
                    pstmt.setLong(15, chargingStartTime);
                    pstmt.executeUpdate();
                }

                // Fold this session into the permanent daily rollup.
                foldSessionIntoDaily(now, energyAdded, cost, isDc, chargingPeakPower, rangeGained);

                logger.info("Charging session ended at " + soc + "% (+" +
                    String.format("%.1f", socDelta) + "%, ~" +
                    String.format("%.1f", energyAdded) + " kWh, peak " +
                    String.format("%.1f", chargingPeakPower) + " kW, " +
                    (isDc == 1 ? "DC" : isDc == 0 ? "AC" : "?") + ", " +
                    String.format("%.0f", packTemp) + "°C)");

                // Feed calibration data to SohEstimator for ongoing SOH tracking.
                // Pass the highest cell voltage observed at session end so
                // updateFromCalibration() can pick LFP vs NMC chemistry scale.
                if (sohEst != null && socDelta > 0 && energyAdded > 0) {
                    double highCellV = Double.NaN;
                    try {
                        com.overdrive.app.byd.BydDataCollector col =
                            com.overdrive.app.byd.BydDataCollector.getInstance();
                        if (col != null && col.isInitialized()) {
                            com.overdrive.app.byd.BydVehicleData vd = col.getData();
                            if (vd != null && !Double.isNaN(vd.highCellVoltage)) {
                                highCellV = vd.highCellVoltage;
                            }
                        }
                    } catch (Exception ignored) { /* keep NaN → defaults to LFP */ }
                    try {
                        sohEst.updateFromCalibration(energyAdded, socDelta, packTemp, isAcCharge, highCellV);
                    } catch (Exception e) {
                        logger.debug("SOH calibration update failed: " + e.getMessage());
                    }
                }
            }

            wasCharging = isCharging;

        } catch (Exception e) {
            logger.error("Failed to track charging session", e);
        }
    }

    /**
     * Latest {@code soc_history} charging-heartbeat timestamp within
     * {@code (startExclusiveFloor, upperInclusive]} — i.e. the most recent
     * is_charging=1 row that belongs to a session starting at
     * {@code startExclusiveFloor}. Returns 0 when none.
     *
     * <p>This is the activity signal that survives on models with no
     * charging_power_samples: the SOC scheduler writes an is_charging=1 row every
     * &lt;=2 min while charging, independent of any power signal or ACC state. The
     * query is bounded above by the next-newer session's start so one charge's
     * heartbeat can never be attributed to an older session.
     */
    private long maxChargingHeartbeat(long startInclusive, long upperInclusive) {
        if (!isInitialized || connection == null) return 0L;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT MAX(timestamp) FROM " + TABLE_SOC +
                " WHERE is_charging = 1 AND timestamp >= ? AND timestamp <= ?;")) {
            ps.setLong(1, startInclusive);
            ps.setLong(2, upperInclusive);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? 0L : v;
                }
            }
        } catch (Exception e) {
            logger.debug("maxChargingHeartbeat failed: " + e.getMessage());
        }
        return 0L;
    }

    /**
     * Attempt to RESUME (and consolidate) a recent charging session chain instead
     * of opening a new row. Returns true if a session was adopted (caller skips
     * the INSERT); false if nothing is resumable and a fresh row is needed.
     *
     * <p>A daemon kill leaves the session OPEN (SESSION END never runs), so its
     * "last activity" is its most recent {@code charging_power_samples} row — NOT
     * end_time (null) and NOT start_time (which would put a long charge outside
     * the window). Several restarts during one charge therefore leave several
     * open rows; this walks them newest→oldest, links any whose gap (by last
     * sample time) is within {@link #CHARGING_MERGE_GAP_MS}, then folds the whole
     * chain into its EARLIEST row: re-keys every other member's samples to the
     * canonical start_time, reverses their daily folds (if they had closed), and
     * deletes them. Finally it re-opens the canonical row and rehydrates the
     * in-memory aggregates so peak/avg/start-soc/range continue seamlessly.
     */
    private boolean tryResumeChargingSession(long now, double soc) {
        if (!isInitialized || connection == null) return false;
        try {
            // Pull recent sessions (bounded to a day) newest-first. We then
            // measure each session's "last activity" from the MAX of:
            //   (a) its last charging_power_samples row,
            //   (b) its charging heartbeat in soc_history (is_charging=1 rows,
            //       written every <=2 min by the independent SOC scheduler even
            //       when NO power samples exist — the exact case that broke resume
            //       on models reporting no charging-power signal, fragmenting one
            //       physical charge into a new row every daemon restart / detector
            //       flip and resetting energy/range/cost to ~0),
            //   (c) close time, (d) start.
            // The heartbeat (b) is scoped to [start, nextNewerSessionStart] (or
            // [start, now] for the newest) so a LATER charge's heartbeat can never
            // bleed onto an older session and wrongly merge two distinct charges.
            java.util.List<long[]> rows = new java.util.ArrayList<>(); // [start, end, lastActivity]
            java.util.List<Double> startSocs = new java.util.ArrayList<>();
            java.util.List<Double> endSocs = new java.util.ArrayList<>(); // NaN = open / unknown
            java.util.List<long[]> raw = new java.util.ArrayList<>();   // [start, end, cpsLastT(0=none)]
            long sinceTs = now - 24L * 60 * 60 * 1000L;
            try (PreparedStatement sel = connection.prepareStatement(
                    "SELECT c.start_time, c.end_time, c.start_soc, c.end_soc, " +
                    "  (SELECT MAX(t) FROM " + TABLE_CPS + " s WHERE s.session_start_time = c.start_time) AS last_t " +
                    "FROM " + TABLE_CHARGING + " c WHERE c.start_time >= ? ORDER BY c.start_time DESC;")) {
                sel.setLong(1, sinceTs);
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) {
                        long st = rs.getLong("start_time");
                        long en = rs.getLong("end_time");
                        long lt = rs.getLong("last_t");
                        boolean ltNull = rs.wasNull();
                        double endSoc = rs.getDouble("end_soc");
                        if (rs.wasNull()) endSoc = Double.NaN;
                        raw.add(new long[]{ st, en, (!ltNull && lt > 0) ? lt : 0L });
                        startSocs.add(rs.getDouble("start_soc"));
                        endSocs.add(endSoc);
                    }
                }
            }
            if (raw.isEmpty()) return false;

            // Second pass: fold in the per-session-scoped charging heartbeat.
            for (int i = 0; i < raw.size(); i++) {
                long st = raw.get(i)[0];
                long en = raw.get(i)[1];
                long cpsLast = raw.get(i)[2];
                // Upper bound = just BEFORE the next-NEWER session's start (rows
                // are DESC), or `now` for the newest. The session-start tick writes
                // an is_charging=1 row at exactly the newer session's start_time;
                // excluding it (upper-1) keeps that boundary row attributed to the
                // newer session only, not double-counted onto this older one.
                long upper = (i == 0) ? now : raw.get(i - 1)[0] - 1;
                long hb = maxChargingHeartbeat(st, upper);
                // start (st) is the floor; cpsLast/hb/en are 0 when absent.
                long act = Math.max(Math.max(cpsLast, hb), Math.max(en, st));
                rows.add(new long[]{ st, en, act });
            }
            if (rows.isEmpty()) return false;

            // The newest session must itself be recent enough to belong to this
            // charge (its last activity within the window of now).
            if (now - rows.get(0)[2] > CHARGING_MERGE_GAP_MS || now - rows.get(0)[2] < 0) return false;

            // Unplug→short-drive→replug separator. The heartbeat keeps `act` fresh
            // across a brief unplug+drive, so the within-window check above (and
            // the canonical-start SOC guard below) is no longer sufficient to tell
            // "one charge interrupted by a daemon restart" from "two distinct
            // charges 10 min apart". Distinguishing signal: a genuine
            // restart-mid-charge leaves the NEWEST session still OPEN (SESSION END
            // never ran → end_soc NULL). An unplug→drive→replug leaves the newest
            // session CLOSED (end_soc set) AND current SOC has DROPPED since that
            // close (the drive consumed energy). So: if the newest candidate is
            // closed and SOC fell below its end_soc, this is a new charge — decline
            // resume and let the caller INSERT a fresh row. (Open newest → genuine
            // resume, untouched. Charging never lowers SOC, so for a true continued
            // charge soc >= end_soc always holds.)
            double newestEndSoc = endSocs.get(0);
            if (!Double.isNaN(newestEndSoc) && soc + 1.0 < newestEndSoc) return false;

            // Walk newest→oldest, extending the chain while consecutive gaps
            // (older.lastActivity → newer.start) stay within the window.
            int chainEnd = 0; // index of the EARLIEST member (inclusive)
            for (int i = 1; i < rows.size(); i++) {
                long newerStart = rows.get(i - 1)[0];
                long olderActivity = rows.get(i)[2];
                long gap = newerStart - olderActivity;
                if (gap < 0 || gap > CHARGING_MERGE_GAP_MS) break;
                chainEnd = i;
            }

            // Canonical = earliest in the chain; its start anchors the merge.
            long canonStart = rows.get(chainEnd)[0];
            double canonStartSoc = startSocs.get(chainEnd);
            // SOC must not have DROPPED below the canonical start — a lower SOC
            // means a drive happened (genuinely new charge), so don't merge.
            if (soc + 1.0 < canonStartSoc) return false;

            // Fold every newer chain member into the canonical row, then drop it.
            for (int i = 0; i < chainEnd; i++) {
                long memberStart = rows.get(i)[0];
                long memberEnd = rows.get(i)[1];
                if (memberStart == canonStart) continue;
                // Read the member's closed contribution so we can reverse its fold.
                if (memberEnd > 0) {
                    double mE = 0, mC = 0; int mDc = -1, mR = 0;
                    try (PreparedStatement r = connection.prepareStatement(
                            "SELECT energy_added_kwh, session_cost, is_dc, range_gained_km FROM " +
                            TABLE_CHARGING + " WHERE start_time = ?;")) {
                        r.setLong(1, memberStart);
                        try (ResultSet rs = r.executeQuery()) {
                            if (rs.next()) {
                                double e = rs.getDouble(1); mE = rs.wasNull() ? 0 : e;
                                double c = rs.getDouble(2); mC = rs.wasNull() ? 0 : c;
                                int dcVal = rs.getInt(3); mDc = rs.wasNull() ? -1 : dcVal;
                                int rg = rs.getInt(4); mR = rs.wasNull() ? 0 : rg;
                            }
                        }
                    }
                    reverseDailyFoldForSession(memberEnd, mE, mC, mDc, mR);
                }
                // Re-key the member's ramp samples onto the canonical session.
                try (PreparedStatement rk = connection.prepareStatement(
                        "UPDATE " + TABLE_CPS + " SET session_start_time = ? WHERE session_start_time = ?;")) {
                    rk.setLong(1, canonStart);
                    rk.setLong(2, memberStart);
                    rk.executeUpdate();
                }
                // Insert a CHARGING-STOPPED boundary sentinel (power_kw = -1) just
                // after this member's last sample. Once re-keyed, the member's
                // samples are indistinguishable from the canonical run, so the
                // energy integrator would otherwise bridge the inter-session idle
                // gap into a spurious trapezoid (over-counting energy by that gap).
                // integrateSessionEnergyKwh resets its trapezoid chain on any
                // power_kw <= 0 row, so this sentinel cleanly severs the two
                // physically distinct charges. (Fast-sampler skips ≤0 power, so a
                // -1 row never collides with a real sample.) The member's samples
                // all fall at/after memberStart, so a sentinel placed just BEFORE
                // memberStart breaks the chain before the member segment begins.
                try (PreparedStatement ins = connection.prepareStatement(
                        "INSERT INTO " + TABLE_CPS +
                        " (session_start_time, t, power_kw, soc, temp, temp_high, temp_low) " +
                        "VALUES (?, ?, -1, 0, -999, -999, -999);")) {
                    ins.setLong(1, canonStart);
                    // Sever immediately BEFORE the member segment's first sample so
                    // no trapezoid bridges the canonical run (or a prior segment)
                    // into this member segment.
                    ins.setLong(2, memberStart - 1);
                    ins.executeUpdate();
                }
                // Delete the now-empty member row.
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
                    del.setLong(1, memberStart);
                    del.executeUpdate();
                }
            }

            // Read canonical row fields for rehydration.
            double canonPeak = 0; int canonGun = -1, canonStartRange = -1, canonStartOdo = -1;
            double canonLat = 0, canonLng = 0;
            double canonEnergy = 0, canonCost = 0; int canonIsDc = -1, canonRange = 0; long canonEnd = 0;
            try (PreparedStatement r = connection.prepareStatement(
                    "SELECT end_time, peak_power_kw, gun_state, start_range_km, start_odometer_km, start_lat, start_lng, " +
                    "energy_added_kwh, session_cost, is_dc, range_gained_km FROM " +
                    TABLE_CHARGING + " WHERE start_time = ?;")) {
                r.setLong(1, canonStart);
                try (ResultSet rs = r.executeQuery()) {
                    if (rs.next()) {
                        canonEnd = rs.getLong("end_time");
                        double pk = rs.getDouble("peak_power_kw"); canonPeak = rs.wasNull() ? 0 : pk;
                        canonGun = rs.getInt("gun_state");
                        canonStartRange = rs.getInt("start_range_km");
                        canonStartOdo = rs.getInt("start_odometer_km");
                        canonLat = rs.getDouble("start_lat");
                        canonLng = rs.getDouble("start_lng");
                        double e = rs.getDouble("energy_added_kwh"); canonEnergy = rs.wasNull() ? 0 : e;
                        double c = rs.getDouble("session_cost");     canonCost = rs.wasNull() ? 0 : c;
                        canonIsDc = rs.getInt("is_dc");
                        int rg = rs.getInt("range_gained_km");       canonRange = rs.wasNull() ? 0 : rg;
                    }
                }
            }
            // If the canonical row had itself closed, reverse its fold too.
            if (canonEnd > 0) reverseDailyFoldForSession(canonEnd, canonEnergy, canonCost, canonIsDc, canonRange);

            // Re-open the canonical row (clear all end columns).
            try (PreparedStatement upd = connection.prepareStatement(
                    "UPDATE " + TABLE_CHARGING + " SET end_time = NULL, end_soc = NULL, " +
                    "energy_added_kwh = NULL, session_cost = -1, range_gained_km = -1 " +
                    "WHERE start_time = ?;")) {
                upd.setLong(1, canonStart);
                upd.executeUpdate();
            }

            // Rehydrate in-memory aggregates from the canonical row + ALL samples
            // (including the just-re-keyed ones) so peak/mean continue honestly.
            chargingStartTime = canonStart;
            chargingStartSoc = canonStartSoc;
            chargingGunState = canonGun;
            // Keep the ORIGINAL start range. Do NOT re-anchor to the current
            // range on resume — that reset range_gained to ~0 on every restart.
            // If it was never captured (-1), leave it -1 so the next mid-session
            // tick backfills the first valid reading.
            chargingStartRange = canonStartRange;
            // Same as range: keep the ORIGINAL start odometer; if it was never
            // captured (-1) leave it -1 so the next mid-session tick backfills it.
            chargingStartOdometer = canonStartOdo;
            chargingStartLat = canonLat;
            chargingStartLng = canonLng;
            chargingTimeToFullMin = snapshotTimeToFullMin();
            double peak = canonPeak, sum = 0; int count = 0;
            try (PreparedStatement sp = connection.prepareStatement(
                    "SELECT power_kw FROM " + TABLE_CPS + " WHERE session_start_time = ? AND power_kw > 0;")) {
                sp.setLong(1, canonStart);
                try (ResultSet rs = sp.executeQuery()) {
                    while (rs.next()) {
                        double p = rs.getDouble(1);
                        if (p > peak) peak = p;
                        sum += p; count++;
                    }
                }
            }
            chargingPeakPower = peak;
            chargingPowerSum = sum;
            chargingPowerCount = count;

            // Re-trigger geocoding for the canonical row. The original START tried
            // once, but if there was no GPS fix yet (or the place_label is still
            // empty) the card falls back to raw lat/lng. Snapshot a fresh fix if
            // the stored one is 0/0, then resolve a place name best-effort.
            if (chargingStartLat == 0 && chargingStartLng == 0) {
                double[] loc = snapshotLocation();
                if (loc[0] != 0 || loc[1] != 0) {
                    chargingStartLat = loc[0];
                    chargingStartLng = loc[1];
                    try (PreparedStatement up = connection.prepareStatement(
                            "UPDATE " + TABLE_CHARGING + " SET start_lat = ?, start_lng = ? WHERE start_time = ?;")) {
                        up.setDouble(1, chargingStartLat);
                        up.setDouble(2, chargingStartLng);
                        up.setLong(3, canonStart);
                        up.executeUpdate();
                    }
                }
            }
            resolvePlaceLabelAsync(canonStart, chargingStartLat, chargingStartLng);

            logger.info("Resumed+consolidated charging session start=" + canonStart
                + " (merged " + chainEnd + " orphan row(s), soc=" + soc + "%, samples=" + count + ")");
            return true;
        } catch (Exception e) {
            logger.debug("tryResumeChargingSession failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Close any STALE open session left behind when a charge ended while the
     * daemon was down/restarting (the SESSION END tick never fired, so end_time
     * stayed NULL and the row shows blank energy/cost/range with only start
     * values). Called at init when NOT currently charging. Reconstructs the end
     * values from the recorded {@code charging_power_samples}: energy by ∫P·dt,
     * end_soc/temp/peak/avg from the last + max samples, then folds into the
     * daily rollup. Skips a row whose last sample is too recent (< 2 min) — that
     * could be a charge the resume path is about to re-adopt.
     */
    public void finalizeStaleOpenSessions() {
        if (!isInitialized || connection == null) return;
        try {
            java.util.List<Long> staleStarts = new java.util.ArrayList<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement sel = connection.prepareStatement(
                    "SELECT start_time FROM " + TABLE_CHARGING + " WHERE end_time IS NULL ORDER BY start_time ASC;")) {
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) staleStarts.add(rs.getLong(1));
                }
            }
            for (Long startObj : staleStarts) {
                long start = startObj;
                // Skip the live session if one is genuinely open in memory.
                if (wasCharging && start == chargingStartTime) continue;
                finalizeOneStaleSession(start, now);
            }
        } catch (Exception e) {
            logger.debug("finalizeStaleOpenSessions failed: " + e.getMessage());
        }
    }

    private void finalizeOneStaleSession(long start, long now) {
        try {
            // Aggregate the recorded samples for this session.
            long lastT = -1; double lastSoc = Double.NaN, lastTemp = -999;
            double peak = 0, sum = 0; int count = 0;
            double startSoc = 0;
            boolean rowFound = false;
            try (PreparedStatement r = connection.prepareStatement(
                    "SELECT start_soc, peak_power_kw FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
                r.setLong(1, start);
                try (ResultSet rs = r.executeQuery()) {
                    if (rs.next()) {
                        rowFound = true;
                        startSoc = rs.getDouble(1);
                        double pk = rs.getDouble(2); peak = rs.wasNull() ? 0 : pk;
                    }
                }
            }
            // Defensive: if the charging_sessions row is gone (corrupted state),
            // do NOT proceed — folding here would create a ghost session (0 energy
            // but +1 session_count) in the daily rollup.
            if (!rowFound) {
                logger.debug("finalizeOneStaleSession(" + start + ") skipped: session row missing");
                return;
            }
            try (PreparedStatement sp = connection.prepareStatement(
                    "SELECT t, power_kw, soc, temp FROM " + TABLE_CPS +
                    " WHERE session_start_time = ? AND power_kw >= 0 ORDER BY t ASC;")) {
                sp.setLong(1, start);
                try (ResultSet rs = sp.executeQuery()) {
                    while (rs.next()) {
                        long t = rs.getLong(1);
                        double p = rs.getDouble(2);
                        double s = rs.getDouble(3);
                        double tp = rs.getDouble(4);
                        if (p > 0) { if (p > peak) peak = p; sum += p; count++; }
                        lastT = t;
                        if (!rs.wasNull()) lastSoc = s;  // last non-null soc
                        if (tp > -999) lastTemp = tp;
                    }
                }
            }
            // Activity = last power sample, else last charging heartbeat in
            // soc_history (the only activity signal on models with no power
            // samples), else start.
            long heartbeat = maxChargingHeartbeat(start, now);
            long lastActivity = Math.max(lastT, heartbeat);
            // No samples/heartbeat → nothing to reconstruct; close with start
            // values so it stops showing as a dangling open row.
            long endTime = (lastActivity > 0) ? lastActivity : start;
            // Guard: if the last activity is very recent, a charge may still be
            // live (resume will adopt it) — leave it for now. Honor the heartbeat
            // too, else a no-power-sample live charge gets prematurely closed at
            // init and then churns through a fold/reverse-fold on resume.
            if (lastActivity > 0 && (now - lastActivity) < 120_000L) return;

            double energyAdded = integrateSessionEnergyKwh(start);
            double endSoc = !Double.isNaN(lastSoc) ? lastSoc : startSoc;
            if (energyAdded <= 0) {
                // Fallback to SOC-delta if integration yielded nothing.
                double nominal = getSohEstimator() != null ? getSohEstimator().getNominalCapacityKwh() : 0;
                if (nominal > 0 && endSoc > startSoc) energyAdded = (endSoc - startSoc) / 100.0 * nominal;
            }
            double avgPower = count > 0 ? sum / count : -1;
            int rangeGained = rangeGainedFromEnergy(energyAdded);
            // Determine AC/DC first — the DC tariff selection depends on it.
            int gun = -1;
            try (PreparedStatement r = connection.prepareStatement(
                    "SELECT gun_state FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
                r.setLong(1, start);
                try (ResultSet rs = r.executeQuery()) { if (rs.next()) gun = rs.getInt(1); }
            }
            int isDc = deriveIsDc(gun, peak);  // peak-guarded against a misread DC gun
            // DC sessions bill at the separate DC tariff when set.
            double rate = effectiveRate(isDc);
            double cost = (rate > 0 && energyAdded > 0) ? energyAdded * rate : -1;
            String curr = getCurrencySymbol();
            double tAvg = lastTemp > -999 ? lastTemp : -999;

            try (PreparedStatement upd = connection.prepareStatement(
                    "UPDATE " + TABLE_CHARGING + " SET end_time = ?, end_soc = ?, energy_added_kwh = ?, " +
                    "avg_power_kw = ?, peak_power_kw = ?, range_gained_km = ?, is_dc = ?, " +
                    "electricity_rate = ?, currency = ?, session_cost = ?, hv_temp_avg = ? " +
                    "WHERE start_time = ? AND end_time IS NULL;")) {
                upd.setLong(1, endTime);
                upd.setDouble(2, endSoc);
                upd.setDouble(3, energyAdded);
                upd.setDouble(4, avgPower);
                upd.setDouble(5, peak);
                upd.setInt(6, rangeGained);
                upd.setInt(7, isDc);
                upd.setDouble(8, rate);
                upd.setString(9, curr);
                upd.setDouble(10, cost);
                upd.setDouble(11, tAvg);
                upd.setLong(12, start);
                upd.executeUpdate();
            }
            foldSessionIntoDaily(endTime, energyAdded, cost, isDc, peak, rangeGained);
            logger.info("Finalized stale open charging session start=" + start +
                " (samples=" + count + ", energy=" + String.format("%.1f", energyAdded) +
                " kWh, end_soc=" + String.format("%.0f", endSoc) + "%)");
        } catch (Exception e) {
            logger.debug("finalizeOneStaleSession(" + start + ") failed: " + e.getMessage());
        }
    }

    /**
     * Subtract a single closed session's contribution from its day's rollup so
     * a resume→re-close doesn't double count. Mirrors the reversal in
     * {@link #deleteChargingSession} (count + energy + cost + dc/ac + range),
     * clamped at zero. {@code endTime} is the session's prior close time (its
     * day bucket key).
     */
    private void reverseDailyFoldForSession(long endTime, double energy, double cost,
                                            int isDc, int rangeGained) {
        if (!isInitialized || connection == null) return;
        try {
            long day = (endTime / 86_400_000L) * 86_400_000L;
            try (PreparedStatement upd = connection.prepareStatement(
                    "UPDATE " + TABLE_CHARGING_DAILY + " SET session_count = GREATEST(session_count - 1, 0), " +
                    "energy_kwh = GREATEST(energy_kwh - ?, 0), cost = GREATEST(cost - ?, 0), " +
                    "dc_count = GREATEST(dc_count - ?, 0), ac_count = GREATEST(ac_count - ?, 0), " +
                    "range_gained_km = GREATEST(range_gained_km - ?, 0) WHERE day_epoch = ?;")) {
                upd.setDouble(1, energy > 0 ? energy : 0);
                upd.setDouble(2, cost > 0 ? cost : 0);
                upd.setInt(3, isDc == 1 ? 1 : 0);
                upd.setInt(4, isDc == 0 ? 1 : 0);
                upd.setInt(5, rangeGained > 0 ? rangeGained : 0);
                upd.setLong(6, day);
                upd.executeUpdate();
            }
        } catch (Exception e) {
            logger.debug("reverseDailyFoldForSession failed: " + e.getMessage());
        }
    }

    // ==================== CHARGING SESSION HELPERS ====================

    /** Current electric driving range in km, or -1 if unavailable. */
    private int snapshotRangeKm() {
        try {
            DrivingRangeData r = VehicleDataMonitor.getInstance().getDrivingRange();
            if (r != null && r.elecRangeKm > 0) return r.elecRangeKm;
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Current vehicle odometer (km), or -1 if unavailable. Reads the cached
     * {@code totalMileageKm} maintained by BydDataCollector's snapshot (already
     * miles→km normalised) — the same collector-access idiom {@link
     * #rangeGainedFromEnergy} uses. Cached rather than a live reflection call so
     * it still returns a value during ACC-off parked charging, where a fresh HAL
     * read is often unavailable. UNAVAILABLE sentinel maps to -1.
     */
    private int snapshotOdometerKm() {
        try {
            com.overdrive.app.byd.BydDataCollector col = com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) {
                com.overdrive.app.byd.BydVehicleData vd = col.getData();
                if (vd != null && vd.totalMileageKm != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                        && vd.totalMileageKm > 0) {
                    return vd.totalMileageKm;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** Fallback EV efficiency when the car reports none: 15 kWh/100km ≈ 6.7 km/kWh. */
    private static final double DEFAULT_CONSUMPTION_KWH_PER_100KM = 15.0;

    /**
     * Range gained (km) DERIVED from energy added × the car's efficiency, rounded.
     * The car's elecRangeKm estimate is unavailable during parked/ACC-off charging
     * (so endRange−startRange was always blank) and is noisy anyway. Instead:
     *   km = energyKwh / (consumption_kWh_per_100km / 100)
     * Uses the car's last-50km consumption when available, else a sane default.
     * Returns -1 when energy is unknown/non-positive.
     */
    private int rangeGainedFromEnergy(double energyKwh) {
        if (Double.isNaN(energyKwh) || energyKwh <= 0) return -1;
        double consumption = DEFAULT_CONSUMPTION_KWH_PER_100KM;
        try {
            com.overdrive.app.byd.BydDataCollector col = com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) {
                com.overdrive.app.byd.BydVehicleData vd = col.getData();
                // Plausible passenger-EV band 8–40 kWh/100km; ignore sentinels.
                if (vd != null && !Double.isNaN(vd.last50KmConsumption)
                        && vd.last50KmConsumption >= 8 && vd.last50KmConsumption <= 40) {
                    consumption = vd.last50KmConsumption;
                }
            }
        } catch (Exception ignored) {}
        int km = (int) Math.round(energyKwh / (consumption / 100.0));
        return km > 0 ? km : -1;
    }

    /** Current GPS [lat, lng], or {0,0} if no fix. */
    private double[] snapshotLocation() {
        try {
            com.overdrive.app.monitor.GpsMonitor gps = com.overdrive.app.monitor.GpsMonitor.getInstance();
            if (gps != null && gps.hasLocation()) {
                return new double[] { gps.getLatitude(), gps.getLongitude() };
            }
        } catch (Exception ignored) {}
        return new double[] { 0, 0 };
    }

    /**
     * Resolve a human place label for a charging session and write it to the
     * session row when it arrives. Tiered (SafeLocation name → Android geocoder
     * → Nominatim) via the shared GeocodingResolver; fully async + best-effort,
     * so a slow/failed geocode never blocks or breaks session recording.
     */
    private void resolvePlaceLabelAsync(final long sessionStart, double lat, double lng) {
        if (lat == 0 && lng == 0) return;
        try {
            // Reuse the "recording" geocoding flow — the same gate the dashcam
            // clips use for place-name tagging. There is no separate "charging"
            // flow in the geocoding config (only recording/surveillance), so
            // passing "charging" here made isFlowEnabled() always fail-closed
            // and charging sessions only ever showed raw lat/lng. Routing
            // through "recording" means a user who enabled location tags on
            // their video clips gets them on charging sessions too.
            com.overdrive.app.geo.GeocodingResolver.getInstance().resolveAsync(lat, lng, "recording",
                new com.overdrive.app.geo.GeocodingResolver.ResolveCallback() {
                    @Override
                    public void onResolved(com.overdrive.app.geo.PlaceResult result) {
                        if (result == null) return;
                        String label = result.mediumLabel();
                        if (label == null || label.isEmpty()) return;
                        if (!isInitialized || connection == null) return;
                        try (PreparedStatement p = connection.prepareStatement(
                                "UPDATE " + TABLE_CHARGING + " SET place_label = ? WHERE start_time = ?;")) {
                            // Clamp to the column width (96) defensively.
                            p.setString(1, label.length() > 96 ? label.substring(0, 96) : label);
                            p.setLong(2, sessionStart);
                            p.executeUpdate();
                        } catch (Exception e) {
                            logger.debug("place_label update failed: " + e.getMessage());
                        }
                    }
                });
        } catch (Exception e) {
            logger.debug("resolvePlaceLabelAsync failed: " + e.getMessage());
        }
    }

    /** Charging gun state (2=AC 3=DC 4=AC_DC 5=V2L), or -1 if unavailable. */
    /**
     * Derive the is_dc column (1=DC, 0=AC, -1=unknown) from the gun state, with a
     * PHYSICAL sanity guard against a HAL gun-state misread. DC fast-charging is
     * fundamentally high-power; a session whose measured peak never approached a DC
     * rate is not DC, whatever the gun byte said. Observed: a PHEV AC charge at
     * ~1.7 kW (≈7 kW peak) reported gun=3 → was labelled "DC fast". So a gun==3
     * (DC) verdict is only honoured when the session peak is DC-plausible; otherwise
     * we downgrade to unknown (-1) and let the power-based classifier bucket it as
     * AC. gun==2 (AC) is trusted as-is. The 15 kW floor mirrors charging.js
     * DC_MIN_PEAK_KW — comfortably above any AC wallbox, well below a real DC ramp.
     */
    private static final double DC_MIN_PEAK_KW = 15.0;
    private int deriveIsDc(int gunState, double peakKw) {
        if (gunState == 3) {
            return (peakKw >= DC_MIN_PEAK_KW) ? 1 : -1;  // DC flag needs DC-plausible power
        }
        return (gunState == 2) ? 0 : -1;
    }

    private int snapshotGunState() {
        try {
            com.overdrive.app.byd.BydDataCollector col = com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) {
                com.overdrive.app.byd.BydVehicleData vd = col.getData();
                if (vd != null && vd.chargingGunState != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE) {
                    return vd.chargingGunState;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Estimated time-to-full in minutes. Prefers the BYD HAL rest-time countdown
     * (the vehicle's own estimate, most accurate); when the HAL doesn't report it
     * (dead getter on some trims — the field stays UNAVAILABLE), falls back to a
     * COMPUTED estimate: remaining energy to full ÷ current charging power.
     * Returns -1 when neither is available.
     */
    private int snapshotTimeToFullMin() {
        try {
            com.overdrive.app.byd.BydDataCollector col = com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) {
                com.overdrive.app.byd.BydVehicleData vd = col.getData();
                if (vd != null) {
                    int h = vd.chargingRestTimeHours, m = vd.chargingRestTimeMinutes;
                    int UNAVAIL = com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
                    if (h != UNAVAIL || m != UNAVAIL) {
                        return (h != UNAVAIL ? h * 60 : 0) + (m != UNAVAIL ? m : 0);
                    }
                }
            }
        } catch (Exception ignored) {}
        // FALLBACK: HAL rest-time is absent on this trim — compute it ourselves from
        // remaining-energy-to-full ÷ charging power. remaining = (100−SOC)/100 ×
        // nominal × SOH; power = the resolved getChargingState().chargingPowerKW (on
        // PHEV this is the SOC-derived estimator, i.e. the true ~kW). Coarse (bounded
        // by the same SOC quantisation as the power estimate) and only as good as the
        // power reading — but far better than a blank "--". Requires a live SOC, a
        // known pack, and a positive power; else -1 (UI shows "--").
        try {
            com.overdrive.app.abrp.SohEstimator soh = getSohEstimator();
            double nominal = (soh != null) ? soh.getNominalCapacityKwh() : 0;
            if (nominal > 0) {
                VehicleDataMonitor vm = VehicleDataMonitor.getInstance();
                BatterySocData sd = (vm != null) ? vm.getBatterySoc() : null;
                double soc = (sd != null) ? sd.socPercent : Double.NaN;
                ChargingStateData cs = (vm != null) ? vm.getChargingState() : null;
                double powerKw = (cs != null) ? cs.chargingPowerKW : Double.NaN;
                double sohFrac = (soh != null && soh.hasDisplaySoh()) ? soh.getDisplaySoh() / 100.0 : 1.0;
                if (sohFrac <= 0) sohFrac = 1.0;
                if (!Double.isNaN(soc) && soc >= 0 && soc < 100
                        && !Double.isNaN(powerKw) && powerKw > 0.1) {
                    double remainingToFullKwh = ((100.0 - soc) / 100.0) * nominal * sohFrac;
                    if (remainingToFullKwh > 0) {
                        int mins = (int) Math.round(remainingToFullKwh / powerKw * 60.0);
                        // Clamp to a sane band (0 < ttf ≤ 48h) so a tiny power or a
                        // near-full pack can't emit an absurd or zero value.
                        if (mins > 0 && mins <= 48 * 60) return mins;
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Electricity rate snapshot. Read-through to the Trips analytics config
     * section so the per-kWh cost matches the Trips page (single source of
     * truth — see ChargingConfig). Returns -1 when unset.
     */
    private double getElectricityRate() {
        try {
            org.json.JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig();
            org.json.JSONObject trips = cfg != null ? cfg.optJSONObject("tripAnalytics") : null;
            if (trips != null) {
                double r = trips.optDouble("electricityRate", -1);
                if (r > 0) return r;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Optional separate DC-fast tariff (per kWh), read-through to the
     * {@code chargingAnalytics} config section (its owner — see ChargingConfig).
     * DC fast-charging is often billed at a higher public rate than home AC, so
     * a user can set this to price DC sessions correctly. Returns 0 when unset,
     * which the caller treats as "fall back to the base rate".
     */
    private double getDcRate() {
        try {
            org.json.JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig();
            org.json.JSONObject charging = cfg != null ? cfg.optJSONObject("chargingAnalytics") : null;
            if (charging != null) {
                double r = charging.optDouble("dcRate", 0);
                if (r > 0) return r;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * Effective per-kWh rate for a session's cost. Uses the separate DC tariff
     * ONLY when the session is confidently DC ({@code isDc == 1}) and a DC rate
     * is configured; otherwise the base (AC/home) rate. A tri-state {@code isDc}
     * of -1 (unknown / peak-downgraded gun misread) or 0 (AC) both use the base
     * rate — the safe default. This is the single rate-selection point shared by
     * every cost-writing path so DC and AC sessions can't diverge.
     */
    private double effectiveRate(int isDc) {
        if (isDc == 1) {
            double dc = getDcRate();
            if (dc > 0) return dc;
        }
        return getElectricityRate();
    }

    private String getCurrencySymbol() {
        try {
            org.json.JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig();
            org.json.JSONObject trips = cfg != null ? cfg.optJSONObject("tripAnalytics") : null;
            if (trips != null) {
                String c = trips.optString("currency", "");
                if (c != null && !c.isEmpty()) return c;
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Upsert one completed session into the permanent {@code charging_daily}
     * rollup so lifetime / monthly-cost trends survive the soc_history prune.
     * H2 MERGE accumulates per-day counters.
     */
    private void foldSessionIntoDaily(long endTime, double energyKwh, double cost,
                                      int isDc, double peakKw, int rangeGained) {
        if (!isInitialized || connection == null) return;
        try {
            long day = (endTime / 86_400_000L) * 86_400_000L;
            double soh = -999;
            try {
                com.overdrive.app.abrp.SohEstimator est = getSohEstimator();
                if (est != null && est.hasEstimate()) soh = est.getDisplaySoh();
            } catch (Exception ignored) {}

            // Read current row (if any), accumulate, then MERGE the new totals.
            int sessionCount = 0, dcCount = 0, acCount = 0, rangeSum = 0;
            double energySum = 0, costSum = 0, peakMax = 0, sohDay = soh;
            try (PreparedStatement sel = connection.prepareStatement(
                    "SELECT session_count, energy_kwh, cost, dc_count, ac_count, peak_power_kw, " +
                    "soh_at_day, range_gained_km FROM " + TABLE_CHARGING_DAILY + " WHERE day_epoch = ?;")) {
                sel.setLong(1, day);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        sessionCount = rs.getInt(1);
                        energySum = rs.getDouble(2);
                        costSum = rs.getDouble(3);
                        dcCount = rs.getInt(4);
                        acCount = rs.getInt(5);
                        peakMax = rs.getDouble(6);
                        double prevSoh = rs.getDouble(7);
                        rangeSum = rs.getInt(8);
                        if (soh <= 0 && prevSoh > 0) sohDay = prevSoh; // keep last known if no fresh reading
                    }
                }
            }
            sessionCount += 1;
            if (energyKwh > 0) energySum += energyKwh;
            if (cost > 0) costSum += cost;
            if (isDc == 1) dcCount += 1; else if (isDc == 0) acCount += 1;
            if (peakKw > peakMax) peakMax = peakKw;
            if (rangeGained > 0) rangeSum += rangeGained;

            try (PreparedStatement merge = connection.prepareStatement(
                    "MERGE INTO " + TABLE_CHARGING_DAILY +
                    " (day_epoch, session_count, energy_kwh, cost, dc_count, ac_count, peak_power_kw, soh_at_day, range_gained_km) KEY(day_epoch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);")) {
                merge.setLong(1, day);
                merge.setInt(2, sessionCount);
                merge.setDouble(3, energySum);
                merge.setDouble(4, costSum);
                merge.setInt(5, dcCount);
                merge.setInt(6, acCount);
                merge.setDouble(7, peakMax);
                merge.setDouble(8, sohDay);
                merge.setInt(9, rangeSum);
                merge.executeUpdate();
            }
        } catch (Exception e) {
            logger.debug("foldSessionIntoDaily failed: " + e.getMessage());
        }
    }

    /**
     * Append a fine-grained in-session sample (driven by ChargingSessionManager's
     * fast sampler while ChargingDetector.isCharging()). Best-effort; never throws.
     */
    public void recordChargingSample(long sessionStartTime, long t, double powerKw, double soc,
                                     double temp, double tempHigh, double tempLow) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return;
        if (Double.isNaN(powerKw)) return;
        try {
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "INSERT INTO " + TABLE_CPS + " (session_start_time, t, power_kw, soc, temp, temp_high, temp_low) VALUES (?, ?, ?, ?, ?, ?, ?);")) {
                pstmt.setLong(1, sessionStartTime);
                pstmt.setLong(2, t);
                pstmt.setDouble(3, powerKw);
                pstmt.setDouble(4, soc);
                pstmt.setDouble(5, temp);
                pstmt.setDouble(6, tempHigh);
                pstmt.setDouble(7, tempLow);
                pstmt.executeUpdate();
            }
        } catch (Exception e) {
            logger.debug("recordChargingSample failed: " + e.getMessage());
        }
    }

    /** Start time of the currently-open charging session, or -1 if none. */
    public long getOpenChargingSessionStart() {
        return wasCharging ? chargingStartTime : -1;
    }

    /** Latched estimated time-to-full (minutes) for the open session, or -1 if none. */
    public int getOpenChargingSessionTimeToFullMin() {
        return wasCharging ? chargingTimeToFullMin : -1;
    }

    /** SoC% at the start of the currently-open charging session, or -1 if none. */
    public double getOpenChargingSessionStartSoc() {
        return wasCharging ? chargingStartSoc : -1;
    }

    /**
     * Energy (kWh) added so far in the currently-open session, or -1 if none.
     * Integrates the recorded power samples (robust for slow charges where SOC
     * hasn't moved a whole percent), falling back to the SOC-delta estimate.
     * Single source of truth for the dashboard "Session" + stats "Added this
     * session" metrics so they can't read 0 early in a slow charge.
     */
    public double getOpenChargingSessionEnergyKwh() {
        if (!wasCharging || chargingStartTime <= 0) return -1;
        double e = integrateSessionEnergyKwh(chargingStartTime);
        if (e > 0) return e;
        try {
            double nominal = getSohEstimator() != null ? getSohEstimator().getNominalCapacityKwh() : 0;
            double liveSoc = Double.NaN;
            BatterySocData sd = VehicleDataMonitor.getInstance().getBatterySoc();
            // Validate SOC range [0,100] to match chargingRowToJson — an
            // out-of-range BMS read (e.g. 101) must NOT drive the live energy
            // estimate, or /status would emit a value while the detail endpoint
            // emits NULL for the same in-progress session (visible inconsistency).
            if (sd != null && sd.socPercent >= 0 && sd.socPercent <= 100) liveSoc = sd.socPercent;
            if (nominal > 0 && !Double.isNaN(liveSoc) && liveSoc > chargingStartSoc) {
                return (liveSoc - chargingStartSoc) / 100.0 * nominal;
            }
        } catch (Exception ignored) {}
        return -1;
    }


    // ==================== DATA RETRIEVAL ====================
    
    /**
     * Get SOC history for charting.
     * Uses time-based bucketing for efficient downsampling - larger windows = larger buckets.
     * Returns data in ASC order (oldest first) for time-series chart rendering.
     */
    public JSONArray getSocHistory(int hoursBack, int maxPoints) {
        JSONArray results = new JSONArray();
        
        if (!isInitialized || connection == null) {
            logger.debug("Database not initialized for getSocHistory");
            return results;
        }
        
        try {
            long now = System.currentTimeMillis();
            int hours = Math.min(hoursBack, 168);
            long startTime = now - (hours * 60 * 60 * 1000L);
            
            // Calculate bucket size based on time window
            // Goal: ~maxPoints buckets across the time range
            // Minimum bucket: 2 minutes (one sample), Maximum: 30 minutes for week view
            long timeRangeMs = hours * 60 * 60 * 1000L;
            long bucketMs = Math.max(120_000L, timeRangeMs / maxPoints); // At least 2 min
            bucketMs = Math.min(bucketMs, 30 * 60 * 1000L); // Cap at 30 min
            // The 30-min cap can yield MORE buckets than maxPoints (e.g. 168h /
            // 30min = 336 > 300). With "ORDER BY t ASC LIMIT maxPoints" that
            // dropped the most RECENT buckets — so a current charge vanished from
            // the SoC chart at 7d/30d but showed at 24h (only 48 buckets there).
            // Raise the row cap to cover the real bucket count so the tail (now)
            // is never truncated.
            int bucketCount = (int) Math.ceil((double) timeRangeMs / bucketMs) + 1;
            int rowLimit = Math.max(maxPoints, bucketCount);
            
            // Time-bucketed query - takes first sample from each bucket
            // Much more efficient than row numbering for large datasets
            String querySql = 
                "SELECT MIN(timestamp) as t, " +
                "  AVG(soc_percent) as soc, " +
                "  MAX(is_charging) as charging, " +
                "  AVG(CASE WHEN charging_power_kw > 0 THEN charging_power_kw END) as power, " +
                "  AVG(range_km) as range, " +
                "  AVG(CASE WHEN remaining_kwh > 0 THEN remaining_kwh END) as kwh, " +
                "  AVG(CASE WHEN voltage_v > 0 THEN voltage_v END) as volt, " +
                "  AVG(CASE WHEN hv_temp_avg > -999 THEN hv_temp_avg END) as temp, " +
                "  AVG(CASE WHEN soh_percent > 0 THEN soh_percent END) as soh " +
                "FROM " + TABLE_SOC + " " +
                "WHERE timestamp >= ? " +
                "GROUP BY (timestamp / ?) " +
                "ORDER BY t ASC " +
                "LIMIT ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(querySql)) {
                pstmt.setLong(1, startTime);
                pstmt.setLong(2, bucketMs);
                pstmt.setInt(3, rowLimit);

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("t", rs.getLong("t"));
                        row.put("soc", Math.round(rs.getDouble("soc") * 10) / 10.0); // 1 decimal
                        row.put("charging", rs.getInt("charging") == 1);
                        double power = rs.getDouble("power");
                        row.put("power", rs.wasNull() ? 0 : Math.round(power * 100) / 100.0);
                        row.put("range", (int) rs.getDouble("range"));
                        double kwh = rs.getDouble("kwh");
                        if (!rs.wasNull()) row.put("kwh", Math.round(kwh * 10) / 10.0);
                        double volt = rs.getDouble("volt");
                        if (!rs.wasNull() && volt > 0) row.put("volt", Math.round(volt * 100) / 100.0);
                        double temp = rs.getDouble("temp");
                        if (!rs.wasNull()) row.put("temp", Math.round(temp * 10) / 10.0);
                        double soh = rs.getDouble("soh");
                        if (!rs.wasNull() && soh > 0) row.put("soh", Math.round(soh * 10) / 10.0);
                        results.put(row);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to get SOC history", e);
            reconnect();
        }
        
        return results;
    }
    
    /**
     * Get charging sessions.
     */
    public JSONArray getChargingSessions(int daysBack) {
        JSONArray results = new JSONArray();
        
        if (!isInitialized || connection == null) {
            return results;
        }
        
        try {
            long startTime = System.currentTimeMillis() - (daysBack * 24 * 60 * 60 * 1000L);
            
            String sql = "SELECT start_time as startTime, end_time as endTime, start_soc as startSoc, " +
                "end_soc as endSoc, energy_added_kwh as energyAdded, peak_power_kw as peakPower " +
                "FROM " + TABLE_CHARGING + " WHERE start_time >= ? ORDER BY start_time DESC;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, startTime);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("startTime", rs.getLong("startTime"));
                        row.put("endTime", rs.getLong("endTime"));
                        row.put("startSoc", rs.getDouble("startSoc"));
                        row.put("endSoc", rs.getDouble("endSoc"));
                        row.put("energyAdded", rs.getDouble("energyAdded"));
                        row.put("peakPower", rs.getDouble("peakPower"));
                        results.put(row);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to get charging sessions", e);
            reconnect();
        }

        return results;
    }

    // ==================== CHARGING ANALYTICS (v2) ====================

    /** Column list shared by the v2 session readers. */
    private static final String CHARGING_V2_COLS =
        "id, start_time, end_time, start_soc, end_soc, energy_added_kwh, peak_power_kw, avg_power_kw, " +
        "range_gained_km, gun_state, is_dc, electricity_rate, currency, session_cost, time_to_full_min, " +
        "hv_temp_high, hv_temp_low, hv_temp_avg, start_lat, start_lng, place_label, start_odometer_km";

    private JSONObject chargingRowToJson(ResultSet rs) throws Exception {
        JSONObject o = new JSONObject();
        long start = rs.getLong("start_time");
        long end = rs.getLong("end_time");
        o.put("id", rs.getLong("id"));
        o.put("startTime", start);
        o.put("endTime", end);
        o.put("startSoc", rs.getDouble("start_soc"));
        double endSoc = rs.getDouble("end_soc");
        o.put("endSoc", rs.wasNull() ? JSONObject.NULL : endSoc);
        double energy = rs.getDouble("energy_added_kwh");
        o.put("energyAdded", rs.wasNull() ? JSONObject.NULL : energy);
        double peak = rs.getDouble("peak_power_kw");
        o.put("peakPower", rs.wasNull() ? JSONObject.NULL : peak);
        double avg = rs.getDouble("avg_power_kw");
        o.put("avgPower", avg > -1 ? avg : JSONObject.NULL);
        int range = rs.getInt("range_gained_km");
        o.put("rangeGained", range > -1 ? range : JSONObject.NULL);
        int gunState = rs.getInt("gun_state");
        o.put("gunState", rs.wasNull() ? -1 : gunState);
        int isDc = rs.getInt("is_dc");
        o.put("isDc", isDc == 1 ? Boolean.TRUE : isDc == 0 ? Boolean.FALSE : JSONObject.NULL);
        double rate = rs.getDouble("electricity_rate");
        o.put("electricityRate", rate > 0 ? rate : JSONObject.NULL);
        double cost = rs.getDouble("session_cost");
        o.put("cost", cost > -1 ? cost : JSONObject.NULL);
        String curr = rs.getString("currency");
        o.put("currency", curr != null ? curr : "");
        int ttf = rs.getInt("time_to_full_min");
        o.put("timeToFullMin", ttf > -1 ? ttf : JSONObject.NULL);
        double tHi = rs.getDouble("hv_temp_high");
        double tLo = rs.getDouble("hv_temp_low");
        double tAvg = rs.getDouble("hv_temp_avg");
        o.put("tempHigh", tHi > -999 ? tHi : JSONObject.NULL);
        o.put("tempLow", tLo > -999 ? tLo : JSONObject.NULL);
        o.put("tempAvg", tAvg > -999 ? tAvg : JSONObject.NULL);
        o.put("durationMinutes", (end > 0 && end > start) ? Math.round((end - start) / 60000.0) : JSONObject.NULL);
        // Location of the charge. lat/lng are 0/0 when no GPS fix; placeLabel is
        // filled async by the geocoder (may be empty on early reads).
        double lat = rs.getDouble("start_lat");
        double lng = rs.getDouble("start_lng");
        boolean hasLoc = !(lat == 0 && lng == 0);
        o.put("lat", hasLoc ? lat : JSONObject.NULL);
        o.put("lng", hasLoc ? lng : JSONObject.NULL);
        String place = rs.getString("place_label");
        o.put("placeLabel", (place != null && !place.isEmpty()) ? place : JSONObject.NULL);
        // Odometer at charge start (km). -1 sentinel → null so the UI shows "--".
        int odo = rs.getInt("start_odometer_km");
        o.put("startOdometerKm", odo > -1 ? odo : JSONObject.NULL);

        // ---- Live enrichment for the OPEN (in-progress) session ----
        // The end_soc / energy / range / cost / ttf / temp columns are only
        // written at SESSION END. While a charge is still running they sit at
        // their sentinel defaults (end_soc NULL -> 0.0, the rest -1/-999), which
        // is why an in-progress session reads "20% -> 0%" with blank stats and
        // only avg/peak power filled (those ARE written by the mid-session tick).
        // Fill them from the live monitor + running aggregates so the card and
        // detail view reflect the charge so far. Only the row matching the
        // currently-open session is touched.
        boolean isOpen = (end == 0 || end <= start);
        if (isOpen && wasCharging && start == chargingStartTime) {
            o.put("inProgress", true);
            long nowMs = System.currentTimeMillis();
            o.put("durationMinutes", Math.max(0, Math.round((nowMs - start) / 60000.0)));
            try {
                VehicleDataMonitor vm = VehicleDataMonitor.getInstance();
                double liveSoc = Double.NaN;
                BatterySocData sd = vm != null ? vm.getBatterySoc() : null;
                if (sd != null && sd.socPercent >= 0 && sd.socPercent <= 100) liveSoc = sd.socPercent;
                if (!Double.isNaN(liveSoc)) o.put("endSoc", liveSoc);
                // Energy added so far. SOC-delta is useless early in a slow
                // charge (SOC can read "29% -> 29%" for the first 10-20 min, so
                // the delta is 0 and every dependent field blanked). Integrate
                // the recorded power samples instead (∫P·dt over the ramp) — it's
                // non-zero from the first sample. Fall back to SOC-delta only if
                // there are too few samples to integrate.
                double nominal = getSohEstimator() != null ? getSohEstimator().getNominalCapacityKwh() : 0;
                double e = integrateSessionEnergyKwh(start);
                if (e <= 0 && nominal > 0 && !Double.isNaN(liveSoc) && liveSoc > chargingStartSoc) {
                    e = (liveSoc - chargingStartSoc) / 100.0 * nominal;
                }
                if (e > 0) {
                    o.put("energyAdded", e);
                    // DC sessions bill at the separate DC tariff when set — same
                    // classifier + rate selection as the SESSION END path, so this
                    // live card cost matches the value persisted when it closes.
                    double rate2 = effectiveRate(deriveIsDc(chargingGunState, chargingPeakPower));
                    if (rate2 > 0) o.put("cost", e * rate2);
                    // Range gained derived from energy × efficiency (the car's
                    // elecRangeKm delta is unavailable while parked/charging).
                    int rg = rangeGainedFromEnergy(e);
                    if (rg > 0) o.put("rangeGained", rg);
                }
                if (chargingTimeToFullMin > 0) o.put("timeToFullMin", chargingTimeToFullMin);
                BatteryThermalData th = vm != null ? vm.getBatteryThermal() : null;
                if (th != null && th.hasData()) {
                    if (!Double.isNaN(th.averageTempC)) o.put("tempAvg", th.averageTempC);
                    if (!Double.isNaN(th.highestTempC)) o.put("tempHigh", th.highestTempC);
                    if (!Double.isNaN(th.lowestTempC)) o.put("tempLow", th.lowestTempC);
                }
                // Live MEASURED power for the card chip. The stored peak_power_kw
                // may be a stale estimate (7.0 placeholder written before the
                // estimate-skip fix); prefer the current real reading and the
                // sample-derived peak so the card matches the actual charger.
                ChargingStateData cs = vm != null ? vm.getChargingState() : null;
                if (cs != null) {
                    // Carry the estimated flag the dashboard card honors so the
                    // session list / detail drill-in suppress a placeholder power
                    // reading the same way (index.html dashChargePower gates on it).
                    o.put("isEstimated", cs.isEstimated);
                    if (!cs.isEstimated && cs.chargingPowerKW > 0) {
                        o.put("livePowerKw", cs.chargingPowerKW);
                    }
                }
                double samplePeak = peakSampleKw(start);
                if (samplePeak > 0) o.put("peakPower", samplePeak);
            } catch (Exception ignored) {}
        }
        return o;
    }

    /** Max measured power (kW) across a session's recorded samples, or 0. */
    private double peakSampleKw(long sessionStartTime) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return 0;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT MAX(power_kw) FROM " + TABLE_CPS + " WHERE session_start_time = ? AND power_kw > 0;")) {
            p.setLong(1, sessionStartTime);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) { double v = rs.getDouble(1); return rs.wasNull() ? 0 : v; }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /**
     * Energy added so far (kWh) for a session, by trapezoidal integration of its
     * recorded power samples: Σ (P_i + P_{i+1})/2 · Δt. Returns 0 when there are
     * fewer than 2 samples. This is robust for slow charges where SOC hasn't
     * ticked a whole percent yet (the SOC-delta estimate would read 0).
     */
    /**
     * Timestamp (epoch-ms) of the most recent recorded power sample for the
     * session, or -1 if none. Used to bucket a force-closed session into the
     * day it actually ended rather than the wall-clock close moment.
     */
    private long getLastChargingSampleTime(long sessionStartTime) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return -1;
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT MAX(t) FROM " + TABLE_CPS + " WHERE session_start_time = ?;")) {
            pstmt.setLong(1, sessionStartTime);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long t = rs.getLong(1);
                    return rs.wasNull() ? -1 : t;
                }
            }
        } catch (Exception e) {
            logger.debug("getLastChargingSampleTime failed: " + e.getMessage());
        }
        return -1;
    }

    private double integrateSessionEnergyKwh(long sessionStartTime) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return 0;
        // Pull ALL rows (NOT just power_kw > 0) ordered by time. A power_kw <= 0
        // row is a CHARGING-STOPPED boundary: either a fast-sampler tick that read
        // ≤0 power, or an explicit merge-boundary sentinel (power_kw = -1) written
        // by tryResumeChargingSession when it consolidates two physically distinct
        // sessions onto one canonical start_time. We must RESET the trapezoid
        // chain at each such boundary so the gap between two separate charges is
        // not bridged into a spurious trapezoid — that would over-count energy by
        // the inter-session idle gap. (A within-session daemon-restart gap has NO
        // boundary row, so it is still bridged, capped at 10 min, as intended.)
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT t, power_kw FROM " + TABLE_CPS +
                " WHERE session_start_time = ? ORDER BY t ASC;")) {
            pstmt.setLong(1, sessionStartTime);
            try (ResultSet rs = pstmt.executeQuery()) {
                double kwh = 0; long prevT = -1; double prevP = 0; int n = 0;
                while (rs.next()) {
                    long t = rs.getLong(1);
                    double p = rs.getDouble(2);
                    if (p <= 0) {
                        // Charging-stopped boundary: break the trapezoid chain so
                        // the next live sample starts a fresh segment.
                        prevT = -1; prevP = 0;
                        continue;
                    }
                    if (prevT > 0 && t > prevT) {
                        double dtHours = (t - prevT) / 3_600_000.0;
                        // Guard against a long gap (daemon restart) inflating the
                        // integral — cap any single interval at 10 min.
                        if (dtHours > 0 && dtHours <= (10.0 / 60.0)) {
                            kwh += (prevP + p) / 2.0 * dtHours;
                        }
                    }
                    prevT = t; prevP = p; n++;
                }
                return n >= 2 ? kwh : 0;
            }
        } catch (Exception e) {
            logger.debug("integrateSessionEnergyKwh failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Paginated v2 session list (all enriched columns). Returns up to {@code limit}
     * rows so the caller can detect "has more" by a full page (Trips convention).
     */
    public JSONArray getChargingSessionsV2(int daysBack, int limit, int offset) {
        long from = System.currentTimeMillis() - (daysBack * 24L * 60 * 60 * 1000L);
        return getChargingSessionsV2Range(from, Long.MAX_VALUE, limit, offset);
    }

    /**
     * Range variant: sessions with {@code fromMs <= start_time <= toMs}. Used by
     * the date-range picker (charging history is permanent, so this can span
     * well beyond the 90-day quick filters). {@code toMs}=Long.MAX_VALUE = no
     * upper bound. start_time is epoch-ms.
     */
    public JSONArray getChargingSessionsV2Range(long fromMs, long toMs, int limit, int offset) {
        JSONArray results = new JSONArray();
        if (!isInitialized || connection == null) return results;
        try {
            String sql = "SELECT " + CHARGING_V2_COLS + " FROM " + TABLE_CHARGING +
                " WHERE start_time >= ? AND start_time <= ? ORDER BY start_time DESC LIMIT ? OFFSET ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, fromMs);
                pstmt.setLong(2, toMs);
                pstmt.setInt(3, limit);
                pstmt.setInt(4, offset);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) results.put(chargingRowToJson(rs));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get charging sessions v2 (range)", e);
            reconnect();
        }
        return results;
    }

    /** Single session by its IDENTITY id, or null. */
    public JSONObject getChargingSessionById(long id) {
        if (!isInitialized || connection == null) return null;
        try {
            String sql = "SELECT " + CHARGING_V2_COLS + " FROM " + TABLE_CHARGING + " WHERE id = ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) return chargingRowToJson(rs);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get charging session " + id, e);
        }
        return null;
    }

    /** Per-session fine-grained ramp samples (ASC by time) for the given session id. */
    public JSONArray getChargingSamples(long id) {
        JSONArray results = new JSONArray();
        if (!isInitialized || connection == null) return results;
        try {
            // Resolve id -> start_time (the FK used by charging_power_samples).
            long start = -1;
            try (PreparedStatement sel = connection.prepareStatement(
                    "SELECT start_time FROM " + TABLE_CHARGING + " WHERE id = ?;")) {
                sel.setLong(1, id);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) start = rs.getLong(1);
                }
            }
            if (start <= 0) return results;
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "SELECT t, power_kw, soc, temp, temp_high, temp_low FROM " + TABLE_CPS +
                    " WHERE session_start_time = ? AND power_kw >= 0 ORDER BY t ASC;")) {
                pstmt.setLong(1, start);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject o = new JSONObject();
                        o.put("t", rs.getLong("t"));
                        o.put("power", rs.getDouble("power_kw"));
                        o.put("soc", rs.getDouble("soc"));
                        double temp = rs.getDouble("temp");
                        o.put("temp", temp > -999 ? temp : JSONObject.NULL);
                        double tHi = rs.getDouble("temp_high");
                        o.put("tempHigh", (!rs.wasNull() && tHi > -999) ? tHi : JSONObject.NULL);
                        double tLo = rs.getDouble("temp_low");
                        o.put("tempLow", (!rs.wasNull() && tLo > -999) ? tLo : JSONObject.NULL);
                        results.put(o);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get charging samples for " + id, e);
        }
        return results;
    }

    /**
     * Rollup summary for the charging dashboard/stats: period totals from the
     * permanent charging_daily, lifetime totals (survive pruning), SOH trend
     * from soc_daily, and the per-day series for the cost chart.
     */
    public JSONObject getChargingSummary(int daysBack) {
        long from = System.currentTimeMillis() - (daysBack * 24L * 60 * 60 * 1000L);
        return getChargingSummaryRange(from, Long.MAX_VALUE);
    }

    /**
     * Range variant of the rollup summary: period totals over day buckets in
     * [fromMs, toMs]. Lifetime totals + SOH trend remain all-time. toMs=
     * Long.MAX_VALUE = no upper bound.
     */
    public JSONObject getChargingSummaryRange(long fromMs, long toMs) {
        JSONObject out = new JSONObject();
        if (!isInitialized || connection == null) return out;
        try {
            long sinceDay = (fromMs / 86_400_000L) * 86_400_000L;
            long untilDay = (toMs == Long.MAX_VALUE) ? Long.MAX_VALUE : (toMs / 86_400_000L) * 86_400_000L;

            // Period aggregates from charging_daily.
            JSONArray daily = new JSONArray();
            double periodEnergy = 0, periodCost = 0;
            int periodSessions = 0, periodDc = 0, periodAc = 0, periodRange = 0;
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "SELECT day_epoch, session_count, energy_kwh, cost, dc_count, ac_count, range_gained_km " +
                    "FROM " + TABLE_CHARGING_DAILY + " WHERE day_epoch >= ? AND day_epoch <= ? ORDER BY day_epoch ASC;")) {
                pstmt.setLong(1, sinceDay);
                pstmt.setLong(2, untilDay);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject d = new JSONObject();
                        d.put("day", rs.getLong("day_epoch"));
                        d.put("sessions", rs.getInt("session_count"));
                        d.put("energy", rs.getDouble("energy_kwh"));
                        d.put("cost", rs.getDouble("cost"));
                        daily.put(d);
                        periodSessions += rs.getInt("session_count");
                        periodEnergy += rs.getDouble("energy_kwh");
                        periodCost += rs.getDouble("cost");
                        periodDc += rs.getInt("dc_count");
                        periodAc += rs.getInt("ac_count");
                        periodRange += rs.getInt("range_gained_km");
                    }
                }
            }
            out.put("daily", daily);
            out.put("periodSessions", periodSessions);
            out.put("periodEnergyKwh", periodEnergy);
            out.put("periodCost", periodCost);
            out.put("periodDcCount", periodDc);
            out.put("periodAcCount", periodAc);
            out.put("periodRangeGained", periodRange);
            out.put("avgCostPerKwh", periodEnergy > 0 && periodCost > 0 ? periodCost / periodEnergy : JSONObject.NULL);

            // Lifetime totals (entire charging_daily — survives the prune).
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(SUM(session_count),0), COALESCE(SUM(energy_kwh),0), COALESCE(SUM(cost),0) " +
                     "FROM " + TABLE_CHARGING_DAILY + ";")) {
                if (rs.next()) {
                    out.put("lifetimeSessions", rs.getInt(1));
                    out.put("lifetimeEnergyKwh", rs.getDouble(2));
                    out.put("lifetimeCost", rs.getDouble(3));
                }
            }

            // SOH-degradation trend from soc_daily.
            JSONArray sohTrend = new JSONArray();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT day_epoch, soh_percent FROM " + TABLE_SOC_DAILY +
                     " WHERE soh_percent > 0 ORDER BY day_epoch ASC;")) {
                while (rs.next()) {
                    JSONObject p = new JSONObject();
                    p.put("day", rs.getLong(1));
                    p.put("soh", rs.getDouble(2));
                    sohTrend.put(p);
                }
            }
            out.put("sohTrend", sohTrend);
        } catch (Exception e) {
            logger.error("Failed to build charging summary", e);
        }
        return out;
    }

    /** Wipe only the charging-related tables (user "Clear charging history"). Returns rows deleted. */
    public long clearChargingHistory() {
        if (!isInitialized || connection == null) return -1;
        long total = 0;
        try (Statement stmt = connection.createStatement()) {
            total += stmt.executeUpdate("DELETE FROM " + TABLE_CHARGING);
            total += stmt.executeUpdate("DELETE FROM " + TABLE_CPS);
            total += stmt.executeUpdate("DELETE FROM " + TABLE_CHARGING_DAILY);
            logger.info("clearChargingHistory: removed " + total + " rows");
            // Reset live session state so a charge in progress starts clean.
            wasCharging = false;
            chargingStartTime = 0;
            chargingStartSoc = 0;
            chargingPeakPower = 0;
            chargingPowerSum = 0;
            chargingPowerCount = 0;
            chargingStartRange = -1;
            chargingStartOdometer = -1;
            chargingGunState = -1;
            chargingTimeToFullMin = -1;
            return total;
        } catch (Exception e) {
            logger.error("clearChargingHistory failed", e);
            return -1;
        }
    }

    /**
     * Delete a single charging session (and its fine-grained samples), and
     * decrement the {@code charging_daily} rollup so lifetime/period totals
     * stay consistent. Mirrors the Trips per-trip delete. Returns true on
     * success (also true if the row was already gone).
     */
    public boolean deleteChargingSession(long id) {
        if (!isInitialized || connection == null) return false;
        try {
            // Read the row first so we can reverse its contribution to the daily rollup.
            long startTime = -1, endTime = 0;
            double energy = 0, cost = 0, peak = 0;
            int isDc = -1, rangeGained = 0;
            try (PreparedStatement sel = connection.prepareStatement(
                    "SELECT start_time, end_time, energy_added_kwh, session_cost, is_dc, range_gained_km " +
                    "FROM " + TABLE_CHARGING + " WHERE id = ?;")) {
                sel.setLong(1, id);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        startTime = rs.getLong("start_time");
                        endTime = rs.getLong("end_time");
                        double energyRead = rs.getDouble("energy_added_kwh");
                        energy = rs.wasNull() ? -1 : energyRead;
                        double costRead = rs.getDouble("session_cost");
                        cost = rs.wasNull() ? -1 : costRead;
                        isDc = rs.getInt("is_dc");
                        if (rs.wasNull()) isDc = -1;
                        int rangeGainedRead = rs.getInt("range_gained_km");
                        rangeGained = rs.wasNull() ? -1 : rangeGainedRead;
                    } else {
                        return true; // already gone
                    }
                }
            }

            try (PreparedStatement del = connection.prepareStatement(
                    "DELETE FROM " + TABLE_CHARGING + " WHERE id = ?;")) {
                del.setLong(1, id);
                del.executeUpdate();
            }
            if (startTime > 0) {
                try (PreparedStatement delS = connection.prepareStatement(
                        "DELETE FROM " + TABLE_CPS + " WHERE session_start_time = ?;")) {
                    delS.setLong(1, startTime);
                    delS.executeUpdate();
                }
            }

            // Reverse this session's contribution to its day's rollup. Use the
            // end-time day to match foldSessionIntoDaily (which keys on endTime).
            long dayBasis = endTime > 0 ? endTime : startTime;
            if (dayBasis > 0) {
                long day = (dayBasis / 86_400_000L) * 86_400_000L;
                try (PreparedStatement upd = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING_DAILY + " SET session_count = GREATEST(session_count - 1, 0), " +
                        "energy_kwh = GREATEST(energy_kwh - ?, 0), cost = GREATEST(cost - ?, 0), " +
                        "dc_count = GREATEST(dc_count - ?, 0), ac_count = GREATEST(ac_count - ?, 0), " +
                        "range_gained_km = GREATEST(range_gained_km - ?, 0) WHERE day_epoch = ?;")) {
                    upd.setDouble(1, energy >= 0 ? energy : 0);
                    upd.setDouble(2, cost >= 0 ? cost : 0);
                    upd.setInt(3, isDc == 1 ? 1 : 0);
                    upd.setInt(4, isDc == 0 ? 1 : 0);
                    upd.setInt(5, rangeGained > 0 ? rangeGained : 0);
                    upd.setLong(6, day);
                    int updatedRows = upd.executeUpdate();
                    if (updatedRows == 0) {
                        logger.warn("deleteChargingSession: daily rollup row for day=" + day + " not found; session " + id +
                                    " may not have been folded into daily (foldSessionIntoDaily may have failed earlier)");
                    }
                }
                // Drop a now-empty day bucket so it doesn't linger at zero.
                try (PreparedStatement clean = connection.prepareStatement(
                        "DELETE FROM " + TABLE_CHARGING_DAILY + " WHERE day_epoch = ? AND session_count <= 0;")) {
                    clean.setLong(1, day);
                    clean.executeUpdate();
                }
            }
            logger.info("Deleted charging session " + id);
            return true;
        } catch (Exception e) {
            logger.error("deleteChargingSession failed for " + id, e);
            return false;
        }
    }

    /**
     * Update the electricity rate of a single charging session, recalculate its
     * cost, and adjust the daily rollup so lifetime / monthly-cost totals stay consistent.
     */
    public boolean updateChargingSessionRate(long id, double newRate) {
        if (!isInitialized || connection == null) return false;
        try {
            long startTime = -1, endTime = 0;
            double energyAdded = 0;
            double oldCost = 0;
            try (PreparedStatement sel = connection.prepareStatement(
                    "SELECT start_time, end_time, energy_added_kwh, session_cost FROM " + TABLE_CHARGING + " WHERE id = ?;")) {
                sel.setLong(1, id);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        startTime = rs.getLong("start_time");
                        endTime = rs.getLong("end_time");
                        if (endTime == 0 || endTime <= startTime) {
                            logger.warn("updateChargingSessionRate: cannot edit rate of an in-progress session " + id);
                            return false; // cannot edit in-progress session
                        }
                        double energyRead = rs.getDouble("energy_added_kwh");
                        energyAdded = (rs.wasNull() || energyRead < 0) ? 0 : energyRead;
                        double costRead = rs.getDouble("session_cost");
                        oldCost = (rs.wasNull() || costRead < 0) ? 0 : costRead;
                    } else {
                        return false; // not found
                    }
                }
            }

            // Calculate the new cost
            double newCost = (newRate >= 0 && energyAdded > 0) ? (energyAdded * newRate) : -1;

            // Update the session rate and cost in charging_sessions
            try (PreparedStatement updSession = connection.prepareStatement(
                    "UPDATE " + TABLE_CHARGING + " SET electricity_rate = ?, session_cost = ? WHERE id = ?;")) {
                updSession.setDouble(1, newRate);
                updSession.setDouble(2, newCost);
                updSession.setLong(3, id);
                updSession.executeUpdate();
            }

            // Adjust the daily rollup if there's a non-zero change
            long dayBasis = endTime > 0 ? endTime : startTime;
            if (dayBasis > 0) {
                double delta = (newCost >= 0 ? newCost : 0) - oldCost;
                if (delta != 0) {
                    long day = (dayBasis / 86_400_000L) * 86_400_000L;
                    try (PreparedStatement updDaily = connection.prepareStatement(
                            "UPDATE " + TABLE_CHARGING_DAILY + " SET cost = GREATEST(cost + ?, 0) WHERE day_epoch = ?;")) {
                        updDaily.setDouble(1, delta);
                        updDaily.setLong(2, day);
                        updDaily.executeUpdate();
                    }
                }
            }
            logger.info("Updated charging session " + id + " to rate " + newRate + ", calculated cost " + newCost);
            return true;
        } catch (Exception e) {
            logger.error("updateChargingSessionRate failed for " + id, e);
            return false;
        }
    }

    /**
     * Get SOC statistics.
     */
    public JSONObject getSocStats(int hoursBack) {
        JSONObject stats = new JSONObject();
        
        try {
            // Always get current SOC from VehicleDataMonitor
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            BatterySocData currentSoc = monitor.getBatterySoc();
            if (currentSoc != null) {
                stats.put("currentSoc", currentSoc.socPercent);
                stats.put("isLow", currentSoc.isLow);
                stats.put("isCritical", currentSoc.isCritical);
            }
            
            if (!isInitialized || connection == null) {
                return stats;
            }
            
            long startTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L);
            
            // Get min/max/avg/count
            String statsSql = "SELECT MIN(soc_percent), MAX(soc_percent), AVG(soc_percent), COUNT(*) " +
                "FROM " + TABLE_SOC + " WHERE timestamp >= ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(statsSql)) {
                pstmt.setLong(1, startTime);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        stats.put("minSoc", rs.getDouble(1));
                        stats.put("maxSoc", rs.getDouble(2));
                        stats.put("avgSoc", rs.getDouble(3));
                        stats.put("sampleCount", rs.getInt(4));
                    }
                }
            }
            
            // Get charging session count
            String chargingSql = "SELECT COUNT(*) FROM " + TABLE_CHARGING + " WHERE start_time >= ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(chargingSql)) {
                pstmt.setLong(1, startTime);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        stats.put("chargingSessions", rs.getInt(1));
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to get SOC stats", e);
        }
        
        return stats;
    }
    
    /**
     * Get full report for dashboard.
     * Always includes current SOC from VehicleDataMonitor even if no history exists.
     */
    public JSONObject getFullReport(int hoursBack, int maxPoints) {
        JSONObject report = new JSONObject();
        
        try {
            JSONArray history = getSocHistory(hoursBack, maxPoints);
            JSONObject stats = getSocStats(hoursBack);
            
            // Always ensure current SOC is available from live monitor
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            BatterySocData currentSocData = monitor.getBatterySoc();
            DrivingRangeData rangeData = monitor.getDrivingRange();
            ChargingStateData chargingData = monitor.getChargingState();
            
            // Always append a live data point at the end so the "current" kWh/SOC
            // display is fresh from the monitor, not averaged from old DB records
            if (currentSocData != null) {
                JSONObject livePoint = new JSONObject();
                livePoint.put("t", System.currentTimeMillis());
                livePoint.put("soc", currentSocData.socPercent);
                livePoint.put("charging", chargingData != null && 
                    chargingData.status == ChargingStateData.ChargingStatus.CHARGING);
                livePoint.put("power", chargingData != null ? chargingData.chargingPowerKW : 0);
                livePoint.put("range", rangeData != null ? rangeData.elecRangeKm : 0);
                double liveKwh = monitor.getBatteryRemainPowerKwh();
                if (liveKwh > 0) livePoint.put("kwh", Math.round(liveKwh * 10) / 10.0);
                
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                if (sohEst != null && sohEst.hasDisplaySoh()) {
                    // Use the headline display chain (frame_anchor > capacity_ah
                    // > live > calibration on PHEV) so this last "live" point
                    // matches the chip / detail card the user sees, instead
                    // of the raw live formula that often diverges from the
                    // higher-priority anchors on PHEV trims.
                    livePoint.put("soh", Math.round(sohEst.getDisplaySoh() * 10) / 10.0);
                }

                history.put(livePoint);
            }
            
            // Ensure stats has current SOC even if DB query returned nothing
            if (!stats.has("currentSoc") && currentSocData != null) {
                stats.put("currentSoc", currentSocData.socPercent);
                stats.put("isLow", currentSocData.isLow);
                stats.put("isCritical", currentSocData.isCritical);
            }
            
            report.put("history", history);
            report.put("stats", stats);
            report.put("chargingSessions", getChargingSessions(hoursBack / 24));
            report.put("hoursBack", hoursBack);
            report.put("maxPoints", maxPoints);
            report.put("timestamp", System.currentTimeMillis());
            
            // Add live data flag so frontend knows data is fresh
            report.put("hasLiveData", currentSocData != null);
            
        } catch (Exception e) {
            logger.error("Failed to create full report", e);
        }
        
        return report;
    }
    
    /**
     * Set the SohEstimator reference for recording SOH alongside battery data.
     */
    public void setSohEstimator(com.overdrive.app.abrp.SohEstimator estimator) {
        this.sohEstimator = estimator;
    }

    /**
     * Opt-in gate for Charging Analytics session recording. Pushed by
     * {@link com.overdrive.app.charging.ChargingSessionManager} from
     * {@code ChargingConfig.enabled} at init and whenever the user toggles it.
     * When false, {@link #trackChargingSession} records nothing.
     */
    public void setChargingAnalyticsEnabled(boolean enabled) {
        this.chargingAnalyticsEnabled = enabled;
    }

    /**
     * Clean up old remaining_kwh records that have a stuck/stale value.
     * Called after PHEV capacity is correctly detected to fix historical data.
     * Updates records where remaining_kwh doesn't match SOC x nominal within 30%.
     */
    public void fixStaleRemainingKwh(double nominalCapacityKwh) {
        if (!isInitialized || connection == null || nominalCapacityKwh <= 0) return;
        try {
            // Effective per-row energy uses the SAME frame as the live store and
            // display: (soc/100) × nominal × (SOH/100). Including the SOH factor
            // (was omitted) means a migrated row and a freshly-written row for the
            // same SOC match once SOH<100, removing the ~8% step at the boundary.
            double sohFrac = 1.0;
            try {
                if (sohEstimator != null && sohEstimator.hasDisplaySoh()) {
                    sohFrac = sohEstimator.getDisplaySoh() / 100.0;
                }
            } catch (Throwable ignored) {}
            double effPerSoc = nominalCapacityKwh * sohFrac;   // kWh per 100% SOC
            // Rewrite rows deviating >12% from the SOC-derived value (matches the
            // display gate's tolerance; was a looser 30%).
            String sql = "UPDATE " + TABLE_SOC +
                " SET remaining_kwh = (soc_percent / 100.0) * ? " +
                "WHERE soc_percent > 0 AND remaining_kwh > 0 " +
                "AND ABS(remaining_kwh - (soc_percent / 100.0) * ?) / ((soc_percent / 100.0) * ?) > 0.12";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setDouble(1, effPerSoc);
                pstmt.setDouble(2, effPerSoc);
                pstmt.setDouble(3, effPerSoc);
                int updated = pstmt.executeUpdate();
                if (updated > 0) {
                    logger.info("Fixed " + updated + " stale remaining_kwh records (nominal=" +
                        String.format("%.1f", nominalCapacityKwh) + " kWh, SOH="
                        + String.format("%.0f", sohFrac * 100) + "%)");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to fix stale remaining_kwh: " + e.getMessage());
        }
    }
    
    public com.overdrive.app.abrp.SohEstimator getSohEstimator() {
        return sohEstimator;
    }

    /**
     * Conservative rest-state check used to gate the energy-based SOH source.
     *
     * "At rest" means: speed=0, gear in P, AC compressor off, not charging,
     * and (when available) cell voltage spread within 30 mV. Each individual
     * sample is OK to be missing; we treat missing data as "fail-safe not at
     * rest" because populating an active SOH from an indeterminate state is
     * worse than waiting for the next 2-minute tick to give us a clean read.
     *
     * Returns false if BydDataCollector isn't initialized or any of the
     * checks fail. Returns true only when every required signal positively
     * indicates rest.
     */
    private boolean isVehicleAtRest() {
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col == null || !col.isInitialized()) return false;

            com.overdrive.app.byd.BydVehicleData vd = col.getData();
            if (vd == null) return false;

            // Speed must be reported and effectively zero.
            if (Double.isNaN(vd.speedKmh) || vd.speedKmh > 0.5) return false;

            // Gear must be Park (1). UNAVAILABLE counts as "not confirmed."
            if (vd.gearMode != 1) return false;

            // Charging would inflate remainingKwh as the pack absorbs current.
            // chargingState convention: 0/1=idle/disconnected, 2+=charging.
            if (vd.chargingState >= 2) return false;

            // AC compressor on → measurable accessory load → reading drifts low.
            // acStartState: 1=on, 0=off, UNAVAILABLE=unknown. Treat unknown as off
            // (the BMS already accounts for the always-on 12V DC-DC drain).
            if (vd.acStartState == 1) return false;

            // Cell spread > 30 mV usually means the BMS is mid-balancing and
            // SOC isn't trustworthy. Skip the check if we don't have both
            // values — most BYD firmwares only expose min/max sample cells,
            // not a true pack-wide spread.
            if (!Double.isNaN(vd.highCellVoltage) && !Double.isNaN(vd.lowCellVoltage)) {
                double spread = vd.highCellVoltage - vd.lowCellVoltage;
                if (spread > 0.030) return false;
            }

            return true;
        } catch (Exception e) {
            logger.debug("isVehicleAtRest: probe failed (" + e.getMessage() + ")");
            return false;
        }
    }
    
    // ==================== BATTERY HEALTH QUERIES ====================
    
    /**
     * Get 12V battery voltage history for charting.
     */
    public JSONArray getBatteryVoltageHistory(int hoursBack, int maxPoints) {
        JSONArray results = new JSONArray();
        if (!isInitialized || connection == null) return results;
        
        try {
            long now = System.currentTimeMillis();
            int hours = Math.min(hoursBack, 168);
            long startTime = now - (hours * 60 * 60 * 1000L);
            long timeRangeMs = hours * 60 * 60 * 1000L;
            long bucketMs = Math.max(120_000L, timeRangeMs / maxPoints);
            
            String sql = 
                "SELECT MIN(timestamp) as t, AVG(voltage_v) as voltage, " +
                "  MAX(is_charging) as charging " +
                "FROM " + TABLE_SOC + " WHERE timestamp >= ? AND voltage_v > 0 " +
                "GROUP BY (timestamp / ?) ORDER BY t ASC LIMIT ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, startTime);
                pstmt.setLong(2, bucketMs);
                pstmt.setInt(3, maxPoints);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("t", rs.getLong("t"));
                        row.put("voltage", Math.round(rs.getDouble("voltage") * 100) / 100.0);
                        row.put("charging", rs.getInt("charging") == 1);
                        results.put(row);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get voltage history", e);
            reconnect();
        }
        return results;
    }
    
    /**
     * Get HV battery thermal history for charting.
     */
    public JSONArray getThermalHistory(int hoursBack, int maxPoints) {
        JSONArray results = new JSONArray();
        if (!isInitialized || connection == null) return results;
        
        try {
            long now = System.currentTimeMillis();
            int hours = Math.min(hoursBack, 168);
            long startTime = now - (hours * 60 * 60 * 1000L);
            long timeRangeMs = hours * 60 * 60 * 1000L;
            long bucketMs = Math.max(120_000L, timeRangeMs / maxPoints);
            
            String sql = 
                "SELECT MIN(timestamp) as t, " +
                "  AVG(CASE WHEN hv_temp_high > -999 THEN hv_temp_high END) as temp_high, " +
                "  AVG(CASE WHEN hv_temp_low > -999 THEN hv_temp_low END) as temp_low, " +
                "  AVG(CASE WHEN hv_temp_avg > -999 THEN hv_temp_avg END) as temp_avg, " +
                "  MAX(is_charging) as charging " +
                "FROM " + TABLE_SOC + " WHERE timestamp >= ? " +
                "AND (hv_temp_high > -999 OR hv_temp_low > -999 OR hv_temp_avg > -999) " +
                "GROUP BY (timestamp / ?) ORDER BY t ASC LIMIT ?;";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, startTime);
                pstmt.setLong(2, bucketMs);
                pstmt.setInt(3, maxPoints);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("t", rs.getLong("t"));
                        double h = rs.getDouble("temp_high");
                        boolean hNull = rs.wasNull();
                        double l = rs.getDouble("temp_low");
                        boolean lNull = rs.wasNull();
                        double a = rs.getDouble("temp_avg");
                        boolean aNull = rs.wasNull();
                        if (!hNull) row.put("high", Math.round(h * 10) / 10.0);
                        if (!lNull) row.put("low", Math.round(l * 10) / 10.0);
                        if (!aNull) row.put("avg", Math.round(a * 10) / 10.0);
                        row.put("charging", rs.getInt("charging") == 1);
                        results.put(row);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get thermal history", e);
            reconnect();
        }
        return results;
    }
    
    /**
     * Get battery health report — current state + historical stats.
     */
    public JSONObject getBatteryHealthReport(int hoursBack, int maxPoints) {
        JSONObject report = new JSONObject();
        
        try {
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            
            // Current live data
            JSONObject current = new JSONObject();
            
            BatteryPowerData powerData = monitor.getBatteryPower();
            if (powerData != null) {
                current.put("voltage12v", powerData.voltageVolts);
                current.put("voltageStatus", powerData.getHealthStatus());
            }
            
            BatterySocData socData = monitor.getBatterySoc();
            if (socData != null) {
                current.put("soc", socData.socPercent);
            }
            
            BatteryThermalData thermalData = monitor.getBatteryThermal();
            if (thermalData != null && thermalData.hasData()) {
                if (!Double.isNaN(thermalData.highestTempC)) current.put("tempHigh", thermalData.highestTempC);
                if (!Double.isNaN(thermalData.lowestTempC)) current.put("tempLow", thermalData.lowestTempC);
                if (!Double.isNaN(thermalData.averageTempC)) current.put("tempAvg", thermalData.averageTempC);
                if (!Double.isNaN(thermalData.deltaC)) current.put("tempDelta", thermalData.deltaC);
                current.put("thermalStatus", thermalData.getStatus());
            }
            
            com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
            if (sohEst != null && sohEst.hasDisplaySoh()) {
                // Headline display chain (frame_anchor > capacity_ah > live >
                // calibration on PHEV) — keeps the battery-health card and
                // the SoH detail card in lockstep instead of showing two
                // different numbers on PHEV trims where capacity_ah outranks
                // the live formula.
                current.put("soh", Math.round(sohEst.getDisplaySoh() * 10) / 10.0);
                current.put("estimatedCapacityKwh", Math.round(sohEst.getEstimatedCapacityKwh() * 10) / 10.0);
                current.put("nominalCapacityKwh", sohEst.getNominalCapacityKwh());
            } else {
                // Fallback: read persisted SOH from file if estimator reference not wired yet
                logger.info("SOH estimator " + (sohEst == null ? "is null" : "has no estimate") + " for health report, trying persisted file fallback");
                try {
                    java.io.File sohFile = new java.io.File("/data/local/tmp/abrp_soh_estimate.properties");
                    if (sohFile.exists()) {
                        java.util.Properties props = new java.util.Properties();
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(sohFile)) {
                            props.load(fis);
                        }
                        String sohStr = props.getProperty("soh_percent");
                        if (sohStr != null) {
                            double soh = Double.parseDouble(sohStr);
                            if (soh > 0 && soh <= 100) {
                                current.put("soh", Math.round(soh * 10) / 10.0);
                                logger.info("SOH from persisted file fallback (health report): " + soh + "%");
                            }
                        }
                    } else {
                        logger.info("SOH persisted file not found for health report");
                    }
                } catch (Exception e) {
                    logger.debug("Failed to read persisted SOH for health report: " + e.getMessage());
                }
            }
            
            double remainingKwh = monitor.getBatteryRemainPowerKwh();
            if (remainingKwh > 0) current.put("remainingKwh", Math.round(remainingKwh * 10) / 10.0);
            
            DrivingRangeData rangeData = monitor.getDrivingRange();
            if (rangeData != null) current.put("rangeKm", rangeData.elecRangeKm);
            
            report.put("current", current);
            
            // Historical data
            report.put("voltageHistory", getBatteryVoltageHistory(hoursBack, maxPoints));
            report.put("thermalHistory", getThermalHistory(hoursBack, maxPoints));
            
            // 12V voltage stats
            if (isInitialized && connection != null) {
                long startTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L);
                String statsSql = "SELECT MIN(voltage_v), MAX(voltage_v), AVG(voltage_v) " +
                    "FROM " + TABLE_SOC + " WHERE timestamp >= ? AND voltage_v > 0;";
                try (PreparedStatement pstmt = connection.prepareStatement(statsSql)) {
                    pstmt.setLong(1, startTime);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            JSONObject voltStats = new JSONObject();
                            voltStats.put("min", Math.round(rs.getDouble(1) * 100) / 100.0);
                            voltStats.put("max", Math.round(rs.getDouble(2) * 100) / 100.0);
                            voltStats.put("avg", Math.round(rs.getDouble(3) * 100) / 100.0);
                            report.put("voltageStats", voltStats);
                        }
                    }
                }
                
                // SOH history (last N samples where soh > 0)
                String sohSql = "SELECT MIN(timestamp) as t, AVG(soh_percent) as soh " +
                    "FROM " + TABLE_SOC + " WHERE timestamp >= ? AND soh_percent > 0 " +
                    "GROUP BY (timestamp / ?) ORDER BY t ASC LIMIT ?;";
                long sohBucketMs = Math.max(120_000L, (long)(hoursBack) * 60 * 60 * 1000L / maxPoints);
                JSONArray sohHistory = new JSONArray();
                try (PreparedStatement pstmt = connection.prepareStatement(sohSql)) {
                    pstmt.setLong(1, startTime);
                    pstmt.setLong(2, sohBucketMs);
                    pstmt.setInt(3, maxPoints);
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            JSONObject row = new JSONObject();
                            row.put("t", rs.getLong("t"));
                            row.put("soh", Math.round(rs.getDouble("soh") * 10) / 10.0);
                            sohHistory.put(row);
                        }
                    }
                }
                report.put("sohHistory", sohHistory);
            }
            
            report.put("hoursBack", hoursBack);
            report.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            logger.error("Failed to create battery health report", e);
        }
        
        return report;
    }
    
    // ==================== MAINTENANCE ====================

    /**
     * Wipes every row from soc_history and charging_sessions. Used by the
     * user-initiated "Reset Data" feature to clear SOC graphs and 12V history.
     * Returns total rows deleted, or -1 on failure. Tables remain so inserts
     * continue to work.
     */
    public long resetAll() {
        if (!isInitialized || connection == null) return -1;
        long total = 0;
        try (Statement stmt = connection.createStatement()) {
            int n1 = stmt.executeUpdate("DELETE FROM " + TABLE_SOC);
            int n2 = stmt.executeUpdate("DELETE FROM " + TABLE_CHARGING);
            int n3 = 0;
            try {
                n3 = stmt.executeUpdate("DELETE FROM " + TABLE_ACC_EVENTS);
            } catch (Exception ignored) {
                // Table may not exist on very old installs that haven't yet
                // run the migration — ignore so SOC/charging still wipe.
            }
            // New charging-analytics tables — ignore individually if a very old
            // install hasn't migrated them yet.
            int n4 = 0;
            try { n4 += stmt.executeUpdate("DELETE FROM " + TABLE_CPS); } catch (Exception ignored) {}
            try { n4 += stmt.executeUpdate("DELETE FROM " + TABLE_CHARGING_DAILY); } catch (Exception ignored) {}
            try { n4 += stmt.executeUpdate("DELETE FROM " + TABLE_SOC_DAILY); } catch (Exception ignored) {}
            total = n1 + n2 + n3 + n4;
            logger.info("resetAll: cleared " + n1 + " from " + TABLE_SOC
                + ", " + n2 + " from " + TABLE_CHARGING
                + ", " + n3 + " from " + TABLE_ACC_EVENTS
                + ", " + n4 + " from charging-analytics rollups/samples");
            return total;
        } catch (Exception e) {
            logger.error("resetAll failed", e);
            return -1;
        }
    }

    private void cleanupOldData() {
        if (!isInitialized || connection == null) return;

        try {
            long now = System.currentTimeMillis();
            long socCutoff = now - (SOC_RETENTION_DAYS * 24 * 60 * 60 * 1000L);
            long cpsCutoff = now - (CPS_RETENTION_DAYS * 24 * 60 * 60 * 1000L);
            long accCutoff = now - (ACC_RETENTION_DAYS * 24 * 60 * 60 * 1000L);

            // ROLLUP-ON-PRUNE: fold soc_history rows about to be deleted into the
            // permanent soc_daily table so the SOH/temp degradation trend outlives
            // the 30-day raw-sample window. MERGE upserts one row per UTC day.
            try {
                String rollup =
                    "MERGE INTO " + TABLE_SOC_DAILY +
                    " (day_epoch, min_soc, max_soc, avg_soc, soh_percent, hv_temp_avg, sample_count) KEY(day_epoch) " +
                    "SELECT (timestamp/86400000)*86400000 AS d, MIN(soc_percent), MAX(soc_percent), AVG(soc_percent), " +
                    "MAX(CASE WHEN soh_percent > 0 THEN soh_percent ELSE NULL END), " +
                    "AVG(CASE WHEN hv_temp_avg > -999 THEN hv_temp_avg ELSE NULL END), COUNT(*) " +
                    "FROM " + TABLE_SOC + " WHERE timestamp < ? GROUP BY (timestamp/86400000);";
                try (PreparedStatement pstmt = connection.prepareStatement(rollup)) {
                    pstmt.setLong(1, socCutoff);
                    pstmt.executeUpdate();
                }
            } catch (Exception e) {
                logger.warn("soc_daily rollup failed (continuing with prune): " + e.getMessage());
            }

            // Prune soc_history (now safely rolled up) — was 7 days, now 30.
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "DELETE FROM " + TABLE_SOC + " WHERE timestamp < ?;")) {
                pstmt.setLong(1, socCutoff);
                int deleted = pstmt.executeUpdate();
                if (deleted > 0) {
                    logger.info("Pruned " + deleted + " soc_history rows (rolled into soc_daily)");
                }
            }

            // Prune per-session ramp samples (the session summary columns remain
            // on the permanent charging_sessions row).
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "DELETE FROM " + TABLE_CPS + " WHERE t < ?;")) {
                pstmt.setLong(1, cpsCutoff);
                pstmt.executeUpdate();
            }

            // Prune acc_events (previously NEVER pruned — unbounded-growth fix).
            try (PreparedStatement pstmt = connection.prepareStatement(
                    "DELETE FROM " + TABLE_ACC_EVENTS + " WHERE timestamp < ?;")) {
                pstmt.setLong(1, accCutoff);
                pstmt.executeUpdate();
            }

            // charging_sessions / charging_daily / soc_daily are NOT auto-pruned.
            // The v1 "DELETE FROM charging_sessions WHERE start_time < cutoff"
            // is intentionally removed; individual sessions may still be deleted via
            // deleteChargingSession() or clearChargingHistory(), which properly
            // decrement the daily rollup so lifetime totals remain consistent.

        } catch (Exception e) {
            logger.error("Failed to cleanup old data", e);
        }
    }
    
    /**
     * Get database file size.
     */
    public long getDatabaseSize() {
        try {
            java.io.File dbFile = new java.io.File(DB_PATH + ".mv.db");
            return dbFile.exists() ? dbFile.length() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Get record count.
     */
    public int getRecordCount() {
        if (!isInitialized || connection == null) return 0;
        
        try {
            String sql = "SELECT COUNT(*) FROM " + TABLE_SOC + ";";
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get record count", e);
        }
        return 0;
    }
    
    public boolean isRunning() {
        return isRunning;
    }
    
    public boolean isAvailable() {
        return isInitialized && connection != null;
    }

    // ==================== ACC EVENTS ====================

    /**
     * Record a single ACC transition. Called synchronously from
     * CameraDaemon.onAccStateChanged() so the snapshot is captured BEFORE
     * the daemon tears down BydDataCollector.
     *
     * @param eventType "ON" or "OFF" (case-insensitive — normalized to upper).
     * @param data the BydVehicleData snapshot at the moment of the event.
     *             Pass null if the snapshot is unavailable; nullable fields
     *             will be persisted as SQL NULL.
     *
     * Best-effort: any exception is caught and logged; never propagates.
     */
    public void recordAccEvent(String eventType, com.overdrive.app.byd.BydVehicleData data) {
        try {
            if (eventType == null) return;
            String type = eventType.trim().toUpperCase();
            if (!"ON".equals(type) && !"OFF".equals(type)) return;

            if (!isAvailable()) {
                logger.debug("recordAccEvent skipped: DB not available (type=" + type + ")");
                return;
            }

            long now = System.currentTimeMillis();

            // Pull snapshot fields defensively. Use SQL NULL when the value
            // is missing or sentinel — never a fake zero.
            Double socPercent = null;
            Double remainingKwh = null;
            Double voltageV = null;
            Integer rangeKm = null;
            if (data != null) {
                if (!Double.isNaN(data.socPercent) && data.socPercent >= 0 && data.socPercent <= 100) {
                    socPercent = data.socPercent;
                }
                // Single source of truth — NOT raw data.remainKwh (unreliable/frozen
                // on PHEV). Keeps acc-event deltas in the same frame as the live
                // store + display, so parked-delta math can't surface a phantom.
                try {
                    double k = VehicleDataMonitor.getInstance().getBatteryRemainPowerKwh();
                    if (k > 0) remainingKwh = k;
                } catch (Throwable ignored) {
                    if (!Double.isNaN(data.remainKwh) && data.remainKwh > 0) remainingKwh = data.remainKwh;
                }
                if (!Double.isNaN(data.voltage12v) && data.voltage12v > 0) {
                    voltageV = data.voltage12v;
                }
                if (data.elecRangeKm != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                        && data.elecRangeKm >= 0) {
                    rangeKm = data.elecRangeKm;
                }
            }

            String sql = "INSERT INTO " + TABLE_ACC_EVENTS +
                " (timestamp, event_type, soc_percent, remaining_kwh, voltage_v, range_km) " +
                "VALUES (?, ?, ?, ?, ?, ?);";

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, now);
                pstmt.setString(2, type);
                if (socPercent != null) pstmt.setDouble(3, socPercent);
                else pstmt.setNull(3, java.sql.Types.REAL);
                if (remainingKwh != null) pstmt.setDouble(4, remainingKwh);
                else pstmt.setNull(4, java.sql.Types.REAL);
                if (voltageV != null) pstmt.setDouble(5, voltageV);
                else pstmt.setNull(5, java.sql.Types.REAL);
                if (rangeKm != null) pstmt.setInt(6, rangeKm);
                else pstmt.setNull(6, java.sql.Types.INTEGER);
                pstmt.executeUpdate();
            }

            logger.debug("ACC event recorded: " + type +
                " soc=" + (socPercent == null ? "null" : socPercent) +
                " kWh=" + (remainingKwh == null ? "null" : remainingKwh));
        } catch (Exception e) {
            // Never propagate — must not break the daemon's ACC state machine.
            logger.error("recordAccEvent failed: " + e.getMessage(), e);
        }
    }

    /**
     * Compute the most recent completed park-and-return cycle.
     *
     * Algorithm (NO inference, only real events):
     *   1. Find the most recent OFF event in the table.
     *   2. Find the most recent ON event whose timestamp > that OFF's timestamp.
     *      (i.e. the matching return event).
     *   3. If both exist with usable SOC values, compute delta and return it.
     *   4. Anything else → return null.
     *
     * Edge cases (ALL return null, never fake data):
     *   - DB unavailable / not initialized.
     *   - No OFF events ever recorded (just installed, never parked yet).
     *   - Most recent OFF has no subsequent ON (currently parked — delta unknown).
     *   - Either bracket has soc_percent IS NULL or NaN.
     *   - soc_percent &lt; 0 or &gt; 100 on either bracket.
     *   - |deltaSoc| &gt; 100 (sanity floor for bad data).
     *   - The OFF was older than `maxAgeHours` hours ago (stale).
     *
     * Returned JSON shape on success:
     *   { offTs, onTs, idleMinutes, deltaSoc, deltaKwh?, isCharging }
     *   deltaKwh present only when both samples have remaining_kwh &gt; 0.
     *   isCharging=true if deltaSoc &gt; 0.5 (battery gained energy parked = plugged in).
     */
    public JSONObject getLastParkingDelta(int maxAgeHours) {
        // Edge case: DB unavailable / not initialized.
        if (!isAvailable()) return null;
        if (maxAgeHours <= 0) return null;
        try {
            // Step 1: find the most recent OFF event.
            long offTs;
            Double offSoc;
            Double offKwh;
            String offSql = "SELECT timestamp, soc_percent, remaining_kwh " +
                "FROM " + TABLE_ACC_EVENTS + " WHERE event_type = 'OFF' " +
                "ORDER BY timestamp DESC LIMIT 1";
            try (PreparedStatement pstmt = connection.prepareStatement(offSql)) {
                try (ResultSet rs = pstmt.executeQuery()) {
                    // Edge case: no OFF events ever recorded.
                    if (!rs.next()) return null;
                    offTs = rs.getLong(1);
                    double s = rs.getDouble(2);
                    offSoc = rs.wasNull() ? null : s;
                    double k = rs.getDouble(3);
                    offKwh = rs.wasNull() ? null : k;
                }
            }

            // Edge case: OFF older than maxAgeHours → stale, skip.
            long now = System.currentTimeMillis();
            long ageMs = now - offTs;
            long maxAgeMs = (long) maxAgeHours * 60L * 60L * 1000L;
            if (ageMs < 0 || ageMs > maxAgeMs) return null;

            // Step 2: find the most recent ON event after that OFF.
            long onTs;
            Double onSoc;
            Double onKwh;
            String onSql = "SELECT timestamp, soc_percent, remaining_kwh " +
                "FROM " + TABLE_ACC_EVENTS + " WHERE event_type = 'ON' AND timestamp > ? " +
                "ORDER BY timestamp DESC LIMIT 1";
            try (PreparedStatement pstmt = connection.prepareStatement(onSql)) {
                pstmt.setLong(1, offTs);
                try (ResultSet rs = pstmt.executeQuery()) {
                    // Edge case: most recent OFF has no subsequent ON
                    // (currently parked — delta unknown).
                    if (!rs.next()) return null;
                    onTs = rs.getLong(1);
                    double s = rs.getDouble(2);
                    onSoc = rs.wasNull() ? null : s;
                    double k = rs.getDouble(3);
                    onKwh = rs.wasNull() ? null : k;
                }
            }

            // Edge case: either bracket has soc_percent IS NULL or NaN.
            if (offSoc == null || onSoc == null) return null;
            if (Double.isNaN(offSoc) || Double.isNaN(onSoc)) return null;

            // Edge case: soc out of valid range on either bracket.
            if (offSoc < 0 || offSoc > 100) return null;
            if (onSoc < 0 || onSoc > 100) return null;

            double deltaSoc = onSoc - offSoc;

            // Edge case: |deltaSoc| > 100 sanity floor for bad data.
            if (Double.isNaN(deltaSoc) || Math.abs(deltaSoc) > 100) return null;

            // Edge case: onTs must be after offTs (already enforced by query
            // but defend against clock skew on the host).
            if (onTs <= offTs) return null;

            JSONObject out = new JSONObject();
            out.put("offTs", offTs);
            out.put("onTs", onTs);
            out.put("idleMinutes", (onTs - offTs) / 60_000L);
            out.put("deltaSoc", Math.round(deltaSoc * 10) / 10.0);

            // deltaKwh present only when both samples have remaining_kwh > 0.
            if (offKwh != null && onKwh != null
                    && !Double.isNaN(offKwh) && !Double.isNaN(onKwh)
                    && offKwh > 0 && onKwh > 0) {
                double deltaKwh = onKwh - offKwh;
                if (!Double.isNaN(deltaKwh) && Math.abs(deltaKwh) < 500) {
                    out.put("deltaKwh", Math.round(deltaKwh * 10) / 10.0);
                }
            }

            // isCharging: positive SOC delta > 0.5 means the pack gained
            // energy while parked — i.e. plugged in.
            out.put("isCharging", deltaSoc > 0.5);

            return out;
        } catch (Exception e) {
            logger.debug("getLastParkingDelta failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the most recent completed charging session within the last `hoursBack`
     * hours. Returns null if none, if values are garbage, or if DB is closed.
     *
     * Returned JSON shape:
     *  { startTime, endTime, durationMinutes, energyAddedKwh, startSoc, endSoc }
     */
    public JSONObject getMostRecentCompletedChargingSession(int hoursBack) {
        if (!isAvailable()) return null;
        if (hoursBack <= 0) return null;
        try {
            long cutoff = System.currentTimeMillis() - (hoursBack * 60L * 60L * 1000L);
            String sql = "SELECT start_time, end_time, start_soc, end_soc, energy_added_kwh " +
                "FROM " + TABLE_CHARGING +
                " WHERE end_time IS NOT NULL AND start_time >= ? " +
                "ORDER BY end_time DESC LIMIT 1";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, cutoff);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) return null;
                    long start = rs.getLong(1);
                    long end = rs.getLong(2);
                    double startSoc = rs.getDouble(3);
                    double endSoc = rs.getDouble(4);
                    double energy = rs.getDouble(5);
                    if (end <= start) return null;
                    if (Double.isNaN(energy) || energy <= 0 || energy > 500) return null;
                    long durationMin = (end - start) / 60_000L;
                    if (durationMin <= 0 || durationMin > 7 * 24 * 60) return null;
                    JSONObject out = new JSONObject();
                    out.put("startTime", start);
                    out.put("endTime", end);
                    out.put("durationMinutes", durationMin);
                    out.put("energyAddedKwh", Math.round(energy * 10) / 10.0);
                    out.put("startSoc", Math.round(startSoc * 10) / 10.0);
                    out.put("endSoc", Math.round(endSoc * 10) / 10.0);
                    return out;
                }
            }
        } catch (Exception e) {
            logger.debug("getMostRecentCompletedChargingSession failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Compute the SOC change rate in %/hour from recent samples (last 10 minutes).
     * Returns a positive value if SOC is rising (charging), negative if falling,
     * or 0 if insufficient data, samples are too close together, or too old.
     */
    public double getSocChangeRatePerHour() {
        if (!isAvailable()) return 0;
        try {
            // Only use samples from the last 10 minutes to avoid stale cross-session data
            long cutoff = System.currentTimeMillis() - 10 * 60 * 1000;
            try (java.sql.PreparedStatement stmt = connection.prepareStatement(
                    "SELECT timestamp, soc_percent FROM " + TABLE_SOC +
                    " WHERE timestamp > ? ORDER BY timestamp DESC LIMIT 2")) {
                stmt.setLong(1, cutoff);
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    double soc1 = Double.NaN, soc2 = Double.NaN;
                    long t1 = 0, t2 = 0;
                    if (rs.next()) { t1 = rs.getLong(1); soc1 = rs.getDouble(2); }
                    if (rs.next()) { t2 = rs.getLong(1); soc2 = rs.getDouble(2); }

                    if (Double.isNaN(soc1) || Double.isNaN(soc2)) return 0;
                    long deltaMs = t1 - t2;
                    if (deltaMs < 60_000) return 0;  // Need at least 60s between samples
                    double deltaSoc = soc1 - soc2;
                    if (Math.abs(deltaSoc) < 0.1) return 0;  // SOC hasn't changed meaningfully
                    double deltaHours = deltaMs / 3_600_000.0;
                    return deltaSoc / deltaHours;
                }
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
