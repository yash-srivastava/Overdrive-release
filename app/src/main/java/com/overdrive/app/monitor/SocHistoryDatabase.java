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
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH + 
        ";AUTO_SERVER=TRUE;FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0";
    
    // Table names
    private static final String TABLE_SOC = "soc_history";
    private static final String TABLE_CHARGING = "charging_sessions";
    
    // Retention periods
    private static final long RETENTION_DAYS = 7;
    private static final long SAMPLE_INTERVAL_MS = 120_000;  // 2 minutes - SOTA interval for daemon recording
    
    // Singleton
    private static SocHistoryDatabase instance;
    private static final Object lock = new Object();
    
    // H2 Connection (kept open for performance)
    private Connection connection;
    
    private ScheduledExecutorService scheduler;
    private volatile boolean isRunning = false;
    private volatile boolean isInitialized = false;
    
    // Charging session tracking
    private boolean wasCharging = false;
    private long chargingStartTime = 0;
    private double chargingStartSoc = 0;
    
    // Last recorded values for deduplication
    private long lastRecordTime = 0;
    private double lastRecordedSoc = -1;
    
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
            scheduler.shutdownNow();
            scheduler = null;
        }
        
        // Close connection
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {}
            connection = null;
        }
        
        logger.info("SOC history recording stopped");
    }
    
    private void reconnect() {
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
            double chargingPower = chargingData != null ? chargingData.chargingPowerKW : 0;
            double voltage = powerData != null ? powerData.voltageVolts : 0;
            int range = rangeData != null ? rangeData.elecRangeKm : 0;
            
            // SOTA: Get remaining battery power in kWh from BYDAutoPowerDevice
            double remainingKwh = 0;
            try {
                remainingKwh = monitor.getBatteryRemainPowerKwh();
            } catch (Exception e) {
                logger.debug("Failed to get remaining kWh: " + e.getMessage());
            }
            
            // SOTA: Update SOH estimate from instantaneous readings
            // This ensures SOH is calculated even if ABRP is not enabled
            if (remainingKwh > 0 && soc > 0) {
                try {
                    com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                    if (sohEst != null) {
                        sohEst.updateFromInstantaneous(remainingKwh, soc);
                    }
                } catch (Exception e) {
                    logger.debug("SOH update failed: " + e.getMessage());
                }
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
                if (sohEst != null && sohEst.hasEstimate()) {
                    sohPercent = sohEst.getCurrentSoh();
                    logger.debug("SOH from estimator: " + sohPercent + "%");
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
                            if (soh > 0 && soh <= 110) {
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
            
            // Skip only if SOC hasn't changed AND we recorded recently (within 10 min)
            if (!forceRecord && lastRecordedSoc >= 0 && Math.abs(soc - lastRecordedSoc) < 0.5) {
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
        
        try {
            if (isCharging && !wasCharging) {
                // Charging started
                chargingStartTime = now;
                chargingStartSoc = soc;
                
                String sql = "INSERT INTO " + TABLE_CHARGING + 
                    " (start_time, start_soc, peak_power_kw) VALUES (?, ?, ?);";
                
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setLong(1, now);
                    pstmt.setDouble(2, soc);
                    pstmt.setDouble(3, power);
                    pstmt.executeUpdate();
                }
                
                logger.info("Charging session started at " + soc + "%");
                
            } else if (!isCharging && wasCharging) {
                // Charging ended — compute energy added and update SOH
                double socDelta = soc - chargingStartSoc;
                
                // Compute energy added using nominal capacity if available,
                // otherwise fall back to rough estimate
                double energyAdded = 0;
                boolean isAcCharge = true; // Assume AC unless peak power > 20 kW
                double packTemp = 25.0;    // Default — updated below if available
                
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                double nominalKwh = sohEst != null ? sohEst.getNominalCapacityKwh() : 0;
                
                if (nominalKwh > 0 && socDelta > 0) {
                    // Use nominal capacity for energy estimate: socDelta% × nominalKwh
                    // Apply display-to-absolute correction (BYD hides ~5% total)
                    energyAdded = (socDelta * 0.95 / 100.0) * nominalKwh;
                } else {
                    energyAdded = socDelta * 0.6; // Rough fallback
                }
                
                // Get battery temperature for calibration quality check
                try {
                    VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
                    BatteryThermalData thermal = monitor.getBatteryThermal();
                    if (thermal != null && thermal.hasData() && !Double.isNaN(thermal.averageTempC)) {
                        packTemp = thermal.averageTempC;
                    }
                } catch (Exception e) { /* use default */ }
                
                // Detect DC fast charging from peak power
                // AC charging is typically < 11 kW (single phase) or < 22 kW (three phase)
                if (power > 20) isAcCharge = false;
                
                String sql = "UPDATE " + TABLE_CHARGING + 
                    " SET end_time = ?, end_soc = ?, energy_added_kwh = ? " +
                    "WHERE start_time = ? AND end_time IS NULL;";
                
                try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                    pstmt.setLong(1, now);
                    pstmt.setDouble(2, soc);
                    pstmt.setDouble(3, energyAdded);
                    pstmt.setLong(4, chargingStartTime);
                    pstmt.executeUpdate();
                }
                
                logger.info("Charging session ended at " + soc + "% (+" + 
                    String.format("%.1f", socDelta) + "%, ~" +
                    String.format("%.1f", energyAdded) + " kWh, " +
                    (isAcCharge ? "AC" : "DC") + ", " +
                    String.format("%.0f", packTemp) + "°C)");
                
                // Feed calibration data to SohEstimator for ongoing SOH tracking
                if (sohEst != null && socDelta > 0 && energyAdded > 0) {
                    try {
                        sohEst.updateFromCalibration(energyAdded, socDelta, packTemp, isAcCharge);
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
            
            // Time-bucketed query - takes first sample from each bucket
            // Much more efficient than row numbering for large datasets
            String querySql = 
                "SELECT MIN(timestamp) as t, " +
                "  AVG(soc_percent) as soc, " +
                "  MAX(is_charging) as charging, " +
                "  AVG(charging_power_kw) as power, " +
                "  AVG(range_km) as range, " +
                "  AVG(remaining_kwh) as kwh, " +
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
                pstmt.setInt(3, maxPoints);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("t", rs.getLong("t"));
                        row.put("soc", Math.round(rs.getDouble("soc") * 10) / 10.0); // 1 decimal
                        row.put("charging", rs.getInt("charging") == 1);
                        row.put("power", Math.round(rs.getDouble("power") * 100) / 100.0);
                        row.put("range", (int) rs.getDouble("range"));
                        row.put("kwh", Math.round(rs.getDouble("kwh") * 10) / 10.0);
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
                livePoint.put("kwh", monitor.getBatteryRemainPowerKwh());
                
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                if (sohEst != null && sohEst.hasEstimate()) {
                    livePoint.put("soh", Math.round(sohEst.getCurrentSoh() * 10) / 10.0);
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
     * Clean up old remaining_kwh records that have a stuck/stale value.
     * Called after PHEV capacity is correctly detected to fix historical data.
     * Updates records where remaining_kwh doesn't match SOC x nominal within 30%.
     */
    public void fixStaleRemainingKwh(double nominalCapacityKwh) {
        if (!isInitialized || connection == null || nominalCapacityKwh <= 0) return;
        try {
            // Update records where remaining_kwh deviates >30% from SOC-derived value
            String sql = "UPDATE " + TABLE_SOC + 
                " SET remaining_kwh = (soc_percent / 100.0) * ? " +
                "WHERE soc_percent > 0 AND remaining_kwh > 0 " +
                "AND ABS(remaining_kwh - (soc_percent / 100.0) * ?) / ((soc_percent / 100.0) * ?) > 0.30";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setDouble(1, nominalCapacityKwh);
                pstmt.setDouble(2, nominalCapacityKwh);
                pstmt.setDouble(3, nominalCapacityKwh);
                int updated = pstmt.executeUpdate();
                if (updated > 0) {
                    logger.info("Fixed " + updated + " stale remaining_kwh records (nominal=" + 
                        String.format("%.1f", nominalCapacityKwh) + " kWh)");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to fix stale remaining_kwh: " + e.getMessage());
        }
    }
    
    public com.overdrive.app.abrp.SohEstimator getSohEstimator() {
        return sohEstimator;
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
            if (sohEst != null && sohEst.hasEstimate()) {
                current.put("soh", Math.round(sohEst.getCurrentSoh() * 10) / 10.0);
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
                            if (soh > 0 && soh <= 110) {
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
    
    private void cleanupOldData() {
        if (!isInitialized || connection == null) return;
        
        try {
            long cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L);
            
            String deleteSocSql = "DELETE FROM " + TABLE_SOC + " WHERE timestamp < ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(deleteSocSql)) {
                pstmt.setLong(1, cutoff);
                int deleted = pstmt.executeUpdate();
                if (deleted > 0) {
                    logger.info("Cleaned up " + deleted + " old SOC records");
                }
            }
            
            String deleteChargingSql = "DELETE FROM " + TABLE_CHARGING + " WHERE start_time < ?;";
            try (PreparedStatement pstmt = connection.prepareStatement(deleteChargingSql)) {
                pstmt.setLong(1, cutoff);
                pstmt.executeUpdate();
            }
            
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
            java.sql.PreparedStatement stmt = connection.prepareStatement(
                "SELECT timestamp, soc_percent FROM " + TABLE_SOC +
                " WHERE timestamp > ? ORDER BY timestamp DESC LIMIT 2");
            stmt.setLong(1, cutoff);
            java.sql.ResultSet rs = stmt.executeQuery();
            double soc1 = Double.NaN, soc2 = Double.NaN;
            long t1 = 0, t2 = 0;
            if (rs.next()) { t1 = rs.getLong(1); soc1 = rs.getDouble(2); }
            if (rs.next()) { t2 = rs.getLong(1); soc2 = rs.getDouble(2); }
            rs.close();
            stmt.close();

            if (Double.isNaN(soc1) || Double.isNaN(soc2)) return 0;
            long deltaMs = t1 - t2;
            if (deltaMs < 60_000) return 0;  // Need at least 60s between samples
            double deltaSoc = soc1 - soc2;
            if (Math.abs(deltaSoc) < 0.1) return 0;  // SOC hasn't changed meaningfully
            double deltaHours = deltaMs / 3_600_000.0;
            return deltaSoc / deltaHours;
        } catch (Exception e) {
            return 0;
        }
    }
}
