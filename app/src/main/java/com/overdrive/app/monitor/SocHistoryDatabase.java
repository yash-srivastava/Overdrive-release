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
    // AUTO_COMPACT_FILL_RATE=50: idle-CPU tuning shared by all seven H2 stores.
    // The default fill-rate target of 90 keeps the MVStore background thread
    // rewriting chunks while the car idles (FileStore.rewriteChunks /
    // getChunksFillRate showed up in on-device profiles); 50 stops that without
    // touching durability. WRITE_DELAY is deliberately left at its 500ms default:
    // a head unit loses power abruptly with the car, and raising the delay widens
    // the delayed-write loss window on every one of these stores.
    private static final String JDBC_URL = "jdbc:h2:file:" + DB_PATH +
        ";FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE" +
        ";AUTO_COMPACT_FILL_RATE=50";
    
    // Table names
    private static final String TABLE_SOC = "soc_history";
    private static final String TABLE_CHARGING = "charging_sessions";
    private static final String TABLE_CHARGING_IDENTITY = "charging_identity_allocator";
    private static final String TABLE_ACC_EVENTS = "acc_events";
    private static final String TABLE_CPS = "charging_power_samples";  // per-session ramp curves
    private static final String TABLE_CHARGING_DAILY = "charging_daily"; // permanent rollup
    private static final String TABLE_SOC_DAILY = "soc_daily";           // permanent rollup
    private static final String TABLE_DATA_MIGRATIONS = "data_migrations";
    private static final String REMAINING_KWH_MIGRATION =
            "soc_history_remaining_kwh_frame";
    private static final int REMAINING_KWH_FORMAT_VERSION = 1;
    private static final int REMAINING_KWH_MIGRATION_ATTEMPTS = 3;
    private static final String CHARGING_LIFECYCLE_JOURNAL_PATH =
            "/data/local/tmp/overdrive_charging_lifecycle.json";
    private static final int CHARGING_LIFECYCLE_JOURNAL_VERSION = 1;

    /** Intentional physical/session boundary; it breaks integration but is not itself missing data. */
    public static final double STOP_BOUNDARY_POWER_KW = -1.0;
    /** Rate was unavailable while charging remained admitted; this interval is incomplete. */
    public static final double MISSING_RATE_BOUNDARY_POWER_KW = -2.0;
    /**
     * SoC/thermal sample captured during an already-marked missing-rate interval. It remains
     * non-positive so the energy integrator cannot bridge or price the unmeasured interval.
     */
    public static final double AUXILIARY_SAMPLE_POWER_KW = -3.0;

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
    /**
     * How far back to look for a prior session when deciding whether the charge now starting is a
     * CONTINUATION of one interrupted by a daemon outage.
     *
     * <p>Generous because the outage itself can be hours — that is the whole case — but finite so a
     * charge from days ago can never seed today's baseline. The counter's own value is the real
     * discriminator; this only bounds the search.
     */
    private static final long CONTINUATION_LOOKBACK_MS = 24 * 60 * 60 * 1000L;
    
    // Singleton
    private static SocHistoryDatabase instance;
    private static final Object lock = new Object();
    
    // H2 Connection (kept open for performance)
    /**
     * Shared H2 connection. Every operation that touches it is serialized on this instance's
     * monitor. H2 transaction state belongs to the connection, so even a reader must not run while
     * repricing has auto-commit disabled or it can observe data that is subsequently rolled back.
     */
    private volatile Connection connection;
    
    private ScheduledExecutorService scheduler;
    private volatile boolean isRunning = false;
    private volatile boolean isInitialized = false;
    
    // Charging session tracking
    private volatile boolean wasCharging = false;
    private volatile long chargingStartTime = 0;
    private volatile double chargingStartSoc = 0;
    // Opt-in flag for Charging Analytics, pushed by ChargingSessionManager from
    // ChargingConfig. Default false (matches ChargingConfig's opt-in default);
    // when false, trackChargingSession records nothing. The separate lifecycle-owner
    // readiness gate prevents a pre-manager scheduler tick from interpreting this
    // default as a user-selected opt-out for a restored open row.
    // volatile: set from the manager thread, read on the SoC sampler thread.
    private volatile boolean chargingAnalyticsEnabled = false;
    /**
     * The always-on SOC scheduler starts before ChargingSessionManager. Until the manager has loaded
     * analytics config, registered its detector listeners, and reconciled physical state, the
     * scheduler must not infer an OFF edge for a journal-restored open row.
     */
    private volatile boolean chargingLifecycleOwnerReady = false;
    /** Manager-pushed physical truth, independent of whether the persistence row is still open. */
    private volatile boolean physicalChargingStateKnown = false;
    private volatile boolean physicalChargingNow = false;
    /**
     * Whether live SOC/power/TTF enrichment is allowed for the open row. Normally follows physical
     * charging, but can be re-enabled for an admitted PHEV taper after FINISHED. It stays false while
     * a row is retained solely for final-counter persistence.
     */
    private volatile boolean chargingLiveEnrichmentAllowed = false;
    /**
     * Start of the current analytics-disabled interval. Initialized at construction so SOC heartbeats
     * written before ChargingSessionManager pushes its configuration cannot extend an old analytics row.
     * Zero means analytics is currently enabled.
     */
    private volatile long analyticsDisabledSinceMs = System.currentTimeMillis();
    /**
     * A disable boundary whose open row has not committed yet. The boundary SOC/counter are captured
     * exactly once while analytics is still transitioning off, so a later retry cannot absorb energy
     * delivered during the opted-out interval.
     */
    private volatile boolean optOutClosePending = false;
    private volatile long optOutBoundaryMs = 0L;
    private volatile double optOutBoundarySoc = Double.NaN;
    private volatile boolean optOutCounterCaptured = false;
    private volatile PricingDecision optOutClosePricing = null;
    private volatile int optOutCloseIsDc = -2;
    /**
     * True while ChargingSessionManager owns an open live session. The SoC recorder still stores the
     * observed FINISHED state, but it must not independently close the charging_sessions row while the
     * manager is waiting for a final counter callback or a parked-poll taper observation.
     */
    private volatile boolean chargingLifecycleHold = false;
    // Running aggregates accumulated across the live session (reset on each START).
    // peakPower was previously frozen at the start-instant power and never
    // updated; it is now a true running max persisted mid-session and at end.
    private volatile double chargingPeakPower = 0;
    private volatile double chargingPowerSum = 0;
    private volatile int chargingPowerCount = 0;
    private volatile int chargingStartRange = -1;
    /**
     * Wrap/reset-aware accumulator over the vehicle's charged-energy counter — the primary source
     * for energy_added_kwh. Its endpoints are persisted on the session row every tick, because the
     * counter advances while the daemon is DOWN and the stored baseline is the only way to
     * reconstruct a session that continued without us.
     */
    private final com.overdrive.app.charging.ChargeCounterAccumulator chargingCounter =
            new com.overdrive.app.charging.ChargeCounterAccumulator();
    /**
     * True between SESSION START and the first counter observation that is safe to baseline from.
     *
     * <p>Exists because the counter field is intentionally retained past gun-out (the close path
     * needs its final value), so at the instant a new session opens it may still hold the previous
     * charge's total. A baseline taken then is too high and silently discards the start of the new
     * charge. While pending, a candidate is only accepted once it looks like a freshly-reset counter
     * or is below the value we last saw.
     */
    private volatile boolean counterBaselinePending = false;
    /** Last counter value seen in the PREVIOUS session, used to recognise a stale reading. */
    private volatile double lastSessionCounterKwh = Double.NaN;
    /** When the baseline first became pending, for the bounded-wait escape hatch. 0 = not pending. */
    private volatile long counterBaselinePendingSinceMs = 0;
    /**
     * Lowest counter reading observed while NO session row was open, and when it was seen.
     *
     * <p>Charging detection lags the physical start (L3 needs three consecutive observations), so the
     * vehicle's post-start reset to 0 often arrives before the row exists. This carries that reading
     * forward so SESSION START can baseline at the true zero instead of at whatever the counter had
     * already climbed to. Bounded by {@link #PRE_SESSION_COUNTER_MAX_AGE_MS} so a value from a charge
     * hours ago can never be adopted.
     */
    /**
     * Which {@code SRC_*} source currently owns the single accumulator, or null before one claims it.
     * Reset per session — see {@link #onChargeCounterObserved}.
     */
    private volatile String counterOwner = null;
    /**
     * Earliest external-counter reading seen while its classifier verdict was still UNKNOWN, and when.
     * Committed as the session baseline if the verdict confirms COUNTER; discarded if it comes back RATE.
     */
    private volatile double provisionalExternalKwh = Double.NaN;
    private volatile long provisionalExternalAtMs = 0;
    /** Unit divisor in effect when {@link #provisionalExternalKwh} was captured. */
    private volatile double provisionalExternalUnitDivisor = 1.0;
    /** Earliest/latest admissible readings retained while a fresh-session baseline is unresolved. */
    private volatile double counterBaselineCandidateKwh = Double.NaN;
    private volatile long counterBaselineCandidateAtMs = 0L;
    private volatile double counterBaselineLatestKwh = Double.NaN;
    private volatile long counterBaselineLatestAtMs = 0L;
    /**
     * Full scale for the external counter, kWh. This is a separate, wider accessor than the dedicated
     * capacity counter. Sized to the SDK envelope for the accessor, which is the only documented bound
     * available.
     */
    private static final double EXTERNAL_COUNTER_FULL_SCALE_KWH = 500.0;
    /**
     * Largest outage energy a source-less legacy row may claim as a continuation, kWh.
     *
     * <p>Only used when the row records no counter source, where the pairing itself is what must be
     * validated. Generous enough for a genuinely long outage on a fast charger, but small enough that
     * differencing two UNRELATED counters — which typically differ by tens or hundreds of kWh — fails.
     */
    private static final double LEGACY_CONTINUATION_MAX_GAP_KWH = 60.0;
    private volatile double preSessionCounterLowKwh = Double.NaN;
    private volatile long preSessionCounterAtMs = 0;
    /**
     * Which {@code SRC_*} source produced {@link #preSessionCounterLowKwh}.
     *
     * <p>A bare number is not enough. The value is captured before any session exists, so ownership is
     * unbound; by the time SESSION START adopts it the snapshot path may have bound a DIFFERENT source,
     * and pairing an external reading with a capacity baseline (or the reverse) yields an added-energy
     * total computed across two unrelated series.
     */
    private volatile String preSessionCounterSource = null;
    /** External accessor values observed before a row opens while its semantics are still UNKNOWN. */
    private volatile double preSessionProvisionalExternalRaw = Double.NaN;
    private volatile long preSessionProvisionalExternalAtMs = 0L;
    private volatile double preSessionProvisionalExternalUnitDivisor = 1.0;
    /** Source of the continuation offer currently being evaluated or held by this session. */
    private volatile String continuationSource = null;
    /**
     * Row whose {@code closed_by_sweep} marker should be consumed IF the continuation value is applied,
     * or -1. Deferred so a session that declines the offer (wrong source, implausible pairing) does not
     * burn the one-shot token that the next session legitimately needs.
     */
    private volatile long pendingSweepMarkerRow = -1L;
    /**
     * Continuation endpoint claimed by the current durable session. The database token is consumed in
     * the same transaction as the new row INSERT; these fields retain the offer only for this session so
     * a counter that reports late can still attribute the outage.
     */
    private volatile long claimedContinuationSessionStart = 0L;
    private volatile long claimedContinuationRow = -1L;
    private volatile double claimedContinuationEndpointKwh = Double.NaN;
    private volatile String claimedContinuationSource = null;
    private volatile double claimedContinuationStartSoc = Double.NaN;
    private volatile double claimedContinuationFullScaleKwh = Double.NaN;
    /** Counter progress exists in memory that has not yet been confirmed durable. */
    private volatile boolean counterProgressDirty = false;
    /** How recent a pre-session counter reading must be to seed a baseline. */
    private static final long PRE_SESSION_COUNTER_MAX_AGE_MS = 10 * 60_000L;
    /**
     * How long to wait for the vehicle to re-zero its counter before taking the baseline anyway.
     * Two SoC ticks plus margin: long enough that a normal reset is always observed first, short
     * enough that a trim which never resets still gets metered energy for most of the session.
     */
    private static final long COUNTER_BASELINE_WAIT_MS = 6 * 60_000L;
    // Vehicle odometer (km) snapshotted at charge START. -1 = not yet captured;
    // like chargingStartRange it's backfilled on a later mid-session tick if the
    // HAL wasn't reporting mileage at the exact start instant (common during
    // ACC-off parked charging), and preserved across a daemon-restart resume.
    private volatile int chargingStartOdometer = -1;
    private volatile int chargingGunState = -1;
    /** Latched AC/DC verdict; estimated power may corroborate type but never enters accounting. */
    private volatile int chargingTypeVerdict = ChargingTypeClassifier.UNKNOWN;
    // Latched estimated time-to-full (minutes). The BYD rest-time field is only
    // meaningful WHILE charging — at session end it reads ~0 / stale. We capture
    // the latest plausible value on each mid-session tick and persist that.
    private volatile int chargingTimeToFullMin = -1;
    // Location of the current charge (snapshot at START). 0/0 = unavailable.
    private volatile double chargingStartLat = 0;
    private volatile double chargingStartLng = 0;

    /**
     * Frozen endpoint for a close that has not committed yet. Retries must close the same physical
     * generation at the same SOC/counter boundary; reading live values again can absorb the next charge.
     */
    private volatile long pendingCloseSessionStart = 0L;
    private volatile long pendingCloseAtMs = 0L;
    private volatile double pendingCloseSoc = Double.NaN;
    private volatile boolean pendingCloseCounterCaptured = false;
    private volatile PricingDecision pendingClosePricing = null;
    private volatile int pendingCloseIsDc = -2;
    private volatile boolean pendingCloseResumeBlocked = false;
    private volatile double pendingCloseTempHigh = -999;
    private volatile double pendingCloseTempLow = -999;
    private volatile double pendingCloseTempAvg = -999;
    /** Write-ahead timestamp for the CPS sentinel required after restoring a live row. */
    private volatile long recoveredActivePowerGapAtMs = 0L;

    /**
     * Physical charges that began while an earlier row was still retrying its close. Each generation
     * owns its own identity, counter, curve, metadata and close boundary. A queue is required because a
     * prolonged outage can contain complete B and C charges before A's close becomes writable.
     */
    private volatile boolean sessionInputsFenced = false;
    private final java.util.ArrayDeque<DeferredChargingGeneration> deferredPhysicalGenerations =
            new java.util.ArrayDeque<>();
    /** Highest in-memory charging identity allocated, including clear/reset replacement rows. */
    private volatile long lastAllocatedChargingStartMs = 0L;
    private final java.io.File chargingLifecycleJournalFile;
    private boolean chargingLifecycleJournalLoaded = false;
    /** A journal that could not be decoded must remain byte-for-byte untouched for recovery. */
    private boolean chargingLifecycleJournalReadFailed = false;
    /** An in-memory lifecycle mutation has not yet reached the atomic journal file. */
    private boolean chargingLifecycleJournalDirty = false;
    /** Tariff mutations whose historical repricing has not yet completed. "*" means all tariffs. */
    private final java.util.LinkedHashSet<String> pendingTariffReprices =
            new java.util.LinkedHashSet<>();
    /** Prevent metadata replay from recursively re-entering a queued repricing attempt. */
    private boolean replayingTariffReprices = false;

    private static final class DeferredChargingSample {
        final long t;
        final double powerKw;
        final double soc;
        final double temp;
        final double tempHigh;
        final double tempLow;

        DeferredChargingSample(long t, double powerKw, double soc,
                               double temp, double tempHigh, double tempLow) {
            this.t = t;
            this.powerKw = powerKw;
            this.soc = soc;
            this.temp = temp;
            this.tempHigh = tempHigh;
            this.tempLow = tempLow;
        }
    }

    private static final class DeferredChargingGeneration {
        long startMs;
        double startSoc;
        long endMs;
        double endSoc = Double.NaN;
        int startRange = -1;
        int startOdometer = -1;
        int gun = -1;
        int typeVerdict = ChargingTypeClassifier.UNKNOWN;
        int timeToFull = -1;
        double lat;
        double lng;
        double peakPower;
        double powerSum;
        int powerCount;
        double endTempHigh = -999;
        double endTempLow = -999;
        double endTempAvg = -999;
        final com.overdrive.app.charging.ChargeCounterAccumulator counter =
                new com.overdrive.app.charging.ChargeCounterAccumulator();
        String counterOwner;
        double previousCounterKwh = Double.NaN;
        boolean counterBaselinePending = true;
        long counterBaselinePendingSinceMs;
        double counterCandidateKwh = Double.NaN;
        long counterCandidateAtMs;
        double counterLatestKwh = Double.NaN;
        long counterLatestAtMs;
        double provisionalExternalKwh = Double.NaN;
        long provisionalExternalAtMs;
        double provisionalExternalUnitDivisor = 1.0;
        boolean integrationTruncated;
        PricingDecision closePricing;
        int closeIsDc = -1;
        boolean resumeBlocked;
        ContinuationOffer continuationOffer;
        final java.util.ArrayList<DeferredChargingSample> samples = new java.util.ArrayList<>();

        boolean isEnded() {
            return endMs > 0L;
        }
    }

    /**
     * Clear/reset transaction whose commit result could not yet be read back. All writes remain fenced
     * until either the replacement row or the pre-transaction row is proven durable.
     */
    private volatile ActiveChargingReplacement pendingActiveReplacement = null;
    private volatile long pendingActiveReplacementPreviousStart = 0L;
    /** Durable clear/reset intent; non-null fences all lifecycle writers until H2 is reconciled. */
    private volatile ChargingMaintenanceIntent pendingChargingMaintenanceIntent = null;
    /**
     * Exact maintenance-replacement aliases for a close already captured against an older row key.
     * Only pending-close replacements are entered, so an arbitrary stale A close can never target a
     * physically unrelated live B.
     */
    private final java.util.LinkedHashMap<Long, Long> chargingCloseTargetRemaps =
            new java.util.LinkedHashMap<>();

    // Last recorded values for deduplication
    private long lastRecordTime = 0;
    private double lastRecordedSoc = -1;
    private double lastRecordedKwh = -1;
    /** Last physical/presented charging state written to soc_history, independent of session hold. */
    private boolean lastRecordedObservedCharging = false;

    // SohEstimator reference (set externally)
    private volatile com.overdrive.app.abrp.SohEstimator sohEstimator;
    
    private SocHistoryDatabase() {
        this(new java.io.File(CHARGING_LIFECYCLE_JOURNAL_PATH));
    }

    SocHistoryDatabase(java.io.File chargingLifecycleJournalFile) {
        this.chargingLifecycleJournalFile = chargingLifecycleJournalFile;
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
    
    public synchronized void init() {
        if (isInitialized) return;
        
        synchronized (lock) {
            if (isInitialized) return;  // Double-check after acquiring lock
            
            logger.info("Initializing H2 database at: " + DB_PATH);
            // The sidecar owns lifecycle durability while H2 is unavailable. Load it before attempting
            // JDBC so a process that cannot open H2 at all can still journal its first physical ON edge.
            loadChargingLifecycleJournal();
            
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
                    reconcileChargingLifecycleJournalWithDatabase();
                    replayPendingChargingPostCommitMetadata();
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
                "remaining_kwh REAL DEFAULT 0," +
                "remaining_kwh_format_version INTEGER DEFAULT "
                    + REMAINING_KWH_FORMAT_VERSION +
                ");"
            );
            
            // Add remaining_kwh column if it doesn't exist (migration for existing DBs)
            try {
                stmt.execute("ALTER TABLE " + TABLE_SOC + " ADD COLUMN IF NOT EXISTS remaining_kwh REAL DEFAULT 0;");
            } catch (Exception ignored) {
                // Column may already exist
            }
            try {
                // Existing rows remain NULL and are the only rows eligible for the one-shot
                // frame repair. New writes bind the current version explicitly.
                stmt.execute("ALTER TABLE " + TABLE_SOC
                        + " ADD COLUMN IF NOT EXISTS remaining_kwh_format_version INTEGER DEFAULT NULL;");
            } catch (Exception ignored) {
                // Column may already exist.
            }
            try {
                // ALTERing the default does not rewrite the NULLs assigned to pre-column rows.
                stmt.execute("ALTER TABLE " + TABLE_SOC
                        + " ALTER COLUMN remaining_kwh_format_version SET DEFAULT "
                        + REMAINING_KWH_FORMAT_VERSION + ";");
            } catch (Exception ignored) {
                // Explicit INSERT binding still protects new rows on older H2 syntax.
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
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS " + TABLE_DATA_MIGRATIONS + " (" +
                "migration_name VARCHAR(96) PRIMARY KEY," +
                "version INTEGER NOT NULL," +
                "completed_at BIGINT NOT NULL" +
                ");"
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
                "start_odometer_km INTEGER DEFAULT -1",
                // v6: WHICH tariff priced this session. The rate itself is
                // already snapshotted in electricity_rate; these record its
                // provenance so the detail card can say "priced at Home rate"
                // and an edit can re-price only the sessions a given tariff
                // owns. Empty tariff_id = priced by the global rate (every
                // pre-v6 session, and any charge at an unmapped location).
                "tariff_id VARCHAR(16) DEFAULT ''",
                "tariff_label VARCHAR(64) DEFAULT ''",
                // v7: the vehicle's own charged-energy counter, which is now the PRIMARY source
                // for energy_added_kwh. Both endpoints are persisted rather than kept in memory,
                // because the counter keeps advancing while the daemon is DOWN: on restart the
                // stored baseline is the only way to recover a session that continued without us.
                // NULL on any pre-v7 row and on any trim where the counter never answers.
                "counter_start_kwh DOUBLE DEFAULT NULL",
                "counter_last_kwh DOUBLE DEFAULT NULL",
                // Wrap-corrected accumulation so far. Kept separately from the endpoints because a
                // counter that wrapped or reset cannot be reconstructed from them alone.
                "counter_energy_kwh DOUBLE DEFAULT NULL",
                // Which SRC_* counter owned this session's accumulator. Persisted rather than inferred:
                // restart recovery previously guessed from the GLOBAL classifier verdict, so a
                // capacity-owned session restored as external the moment the external source happened to
                // hold a COUNTER verdict — then reset and lost its pre-restart accumulation when capacity
                // next reported. The row is the only record of what this particular session was using.
                "counter_source VARCHAR(32) DEFAULT NULL",
                // Which estimate produced energy_added_kwh (SessionEnergyResolver.SRC_*). Support
                // needs to know whether a number was metered, integrated or SOC-derived without
                // re-deriving it, and it is the fastest signal that a trim's counter is dead.
                "energy_source VARCHAR(32) DEFAULT ''",
                // The independent SOC-derived estimate, retained alongside the chosen figure so a
                // divergence stays visible after the fact instead of being silently overwritten.
                "energy_soc_kwh DOUBLE DEFAULT NULL",
                // 1 when the session is known to be missing a segment (counter reset/saturated, or
                // the daemon was down and the gap could not be attributed). The UI marks the row
                // rather than presenting a partial total as complete.
                "energy_incomplete INTEGER DEFAULT 0",
                // 1 when this row was closed by the startup sweep rather than by a live SESSION END —
                // i.e. the daemon was absent when the charge ended. Distinct from energy_incomplete,
                // which a NORMAL close also sets whenever its energy figure is a floor. Only this one
                // means "interrupted", which is what continuation attribution must key on.
                "closed_by_sweep INTEGER DEFAULT 0",
                // One-shot continuation ownership. The next durable session claims an interrupted row
                // atomically with its own INSERT, even if no counter ever reports in that next session.
                "continuation_claimed INTEGER DEFAULT 0",
                // Persist the accumulator's NORMALIZED modulus. External counter readings may be divided
                // by a learned unit factor, so their raw SDK envelope is not necessarily their kWh scale.
                "counter_full_scale_kwh DOUBLE DEFAULT NULL",
                // Exact accumulator image. H2 and the lifecycle journal are separate durability domains;
                // persisting the complete image in both lets startup merge whichever copy reached disk
                // last without losing a wrap/reset/gap event or regressing a wrapped raw endpoint.
                "counter_last_at_ms BIGINT DEFAULT 0",
                "counter_observation_generation BIGINT DEFAULT 0",
                "counter_wrap_count INTEGER DEFAULT 0",
                "counter_reset_count INTEGER DEFAULT 0",
                "counter_ceiling_streak INTEGER DEFAULT 0",
                "counter_saturated INTEGER DEFAULT 0",
                "counter_abandoned_kwh DOUBLE DEFAULT 0",
                "counter_unattributed_gaps INTEGER DEFAULT 0",
                "counter_awaiting_gap INTEGER DEFAULT 0",
                "counter_gap_reconstructed INTEGER DEFAULT 0",
                "counter_gap_estimate_kwh DOUBLE DEFAULT NULL",
                "counter_recent_rate_kwh_per_h DOUBLE DEFAULT NULL",
                // Durable lifecycle fence. A row closed because the user opted out must never be
                // re-opened by the short-gap resume heuristic after analytics is enabled again.
                "resume_blocked INTEGER DEFAULT 0",
                // A graceful daemon stop deliberately breaks the power-sample trapezoid chain. Persist
                // that missing interval so the boundary cannot make a truncated integral look complete.
                "integration_truncated INTEGER DEFAULT 0",
                // Post-commit effects are replayable after an uncertain COMMIT. Tariff usage is
                // reconciled from authoritative rows; SOH keeps its exact calibration payload.
                "post_commit_tariff_applied INTEGER DEFAULT 0",
                "post_commit_soh_applied INTEGER DEFAULT 0",
                "soh_calibration_energy_kwh DOUBLE DEFAULT NULL",
                "soh_calibration_cell_v DOUBLE DEFAULT NULL",
                "soh_calibration_nominal_identity VARCHAR(192) DEFAULT NULL",
                "soh_calibration_estimator_generation BIGINT DEFAULT NULL",
                "soh_calibration_reset_model_epoch BIGINT DEFAULT NULL",
                "soh_calibration_prior_at_ms BIGINT DEFAULT NULL",
                "soh_calibration_rejected INTEGER DEFAULT 0"
            };
            for (String col : newChargingCols) {
                try {
                    stmt.execute("ALTER TABLE " + TABLE_CHARGING + " ADD COLUMN IF NOT EXISTS " + col + ";");
                } catch (Exception ignored) {}
            }
            repairDuplicateChargingStartTimes();
            stmt.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS uq_charging_start"
                            + " ON " + TABLE_CHARGING + "(start_time);");
            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS " + TABLE_CHARGING_IDENTITY + " ("
                            + "allocator_key INTEGER PRIMARY KEY,"
                            + "last_start BIGINT NOT NULL);");
            stmt.execute(
                    "INSERT INTO " + TABLE_CHARGING_IDENTITY
                            + " (allocator_key, last_start)"
                            + " SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM "
                            + TABLE_CHARGING_IDENTITY + " WHERE allocator_key = 1);");
            try (PreparedStatement allocator = connection.prepareStatement(
                    "UPDATE " + TABLE_CHARGING_IDENTITY
                            + " SET last_start = CASE WHEN last_start < ? THEN ? ELSE last_start END"
                            + " WHERE allocator_key = 1;")) {
                allocator.setLong(1, lastAllocatedChargingStartMs);
                allocator.setLong(2, lastAllocatedChargingStartMs);
                if (allocator.executeUpdate() != 1) {
                    throw new java.sql.SQLException("charging identity allocator row missing");
                }
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
                "range_gained_km INTEGER DEFAULT 0," +
                // How many of the day's folded sessions had an incomplete energy figure. Without this
                // the rollup and every lifetime total derived from it asserted a precision the
                // underlying rows did not have: a session flagged incomplete (counter reset, dropped
                // integration interval, unattributable restart gap) contributed its floor value and the
                // total then read as measured.
                "incomplete_count INTEGER DEFAULT 0" +
                ");"
            );
            // Migration for installs whose rollup predates the column.
            try {
                stmt.execute("ALTER TABLE " + TABLE_CHARGING_DAILY
                        + " ADD COLUMN IF NOT EXISTS incomplete_count INTEGER DEFAULT 0;");
            } catch (Exception ignored) {}
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

    /**
     * One-time repair for databases created before {@code start_time} became a true row identity.
     * Samples at an ambiguous duplicate key remain with the oldest row; assigning fresh keys to the
     * duplicate summaries prevents every future update/delete from affecting multiple sessions.
     */
    private void repairDuplicateChargingStartTimes() throws Exception {
        java.util.ArrayList<long[]> duplicates = new java.util.ArrayList<>();
        long maxStart = 0L;
        long priorStart = Long.MIN_VALUE;
        boolean firstAtStart = true;
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT id, start_time FROM " + TABLE_CHARGING
                        + " ORDER BY start_time ASC, id ASC;");
             ResultSet rs = read.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong(1);
                long start = rs.getLong(2);
                maxStart = Math.max(maxStart, start);
                if (start != priorStart) {
                    priorStart = start;
                    firstAtStart = true;
                } else if (firstAtStart) {
                    firstAtStart = false;
                    duplicates.add(new long[] { id, start });
                } else {
                    duplicates.add(new long[] { id, start });
                }
            }
        }
        if (duplicates.isEmpty()) {
            lastAllocatedChargingStartMs = Math.max(lastAllocatedChargingStartMs, maxStart);
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + TABLE_CHARGING + " SET start_time = ? WHERE id = ?;")) {
            for (long[] duplicate : duplicates) {
                if (maxStart == Long.MAX_VALUE) {
                    throw new java.sql.SQLException("charging start identity exhausted");
                }
                update.setLong(1, ++maxStart);
                update.setLong(2, duplicate[0]);
                if (update.executeUpdate() != 1) {
                    throw new java.sql.SQLException(
                            "could not repair duplicate charging identity " + duplicate[0]);
                }
            }
        }
        lastAllocatedChargingStartMs = Math.max(lastAllocatedChargingStartMs, maxStart);
        logger.warn("Re-keyed " + duplicates.size()
                + " charging row(s) that shared a legacy start_time");
    }

    public synchronized void start() {
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
    
    public synchronized void stop() {
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

        // This file is independent of H2 specifically so a failed A-close plus buffered B/C
        // survives after the database handle is closed.
        persistChargingLifecycleJournal();
        if (connection != null) {
            // ChargingSessionManager writes a power<=0 boundary before this method runs. That boundary
            // correctly prevents integration across daemon downtime, but on its own it also hides that
            // an interval was dropped. Persist the fact on the open row before closing the connection.
            if (wasCharging && chargingStartTime > 0) {
                try (PreparedStatement p = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING
                        + " SET integration_truncated = 1"
                        + " WHERE start_time = ? AND end_time IS NULL;")) {
                    p.setLong(1, chargingStartTime);
                    if (p.executeUpdate() != 1) {
                        logger.warn("Could not mark graceful-restart integration boundary for session "
                                + chargingStartTime);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to persist graceful-restart integration boundary: "
                            + e.getMessage());
                }
            }
            try {
                connection.close();
            } catch (Exception ignored) {}
            connection = null;
        }
        isInitialized = false;
        chargingLifecycleHold = false;

        logger.info("SOC history recording stopped");
    }
    
    /**
     * SYNCHRONIZED on the same instance monitor as {@link #recordCurrentSoc()}. Query paths run on
     * HTTP threads and can now reach this (they increment the escalation counter, so
     * isConnectionDead() can return true for them), which means a query thread could otherwise
     * close and swap the shared {@code connection} field while the writer was mid-statement on it —
     * and two concurrent reopens could leak a Connection by overwriting one another's handle.
     */
    private synchronized void reconnect() {
        // After stop() flips isRunning=false the connection is intentionally
        // closed. Re-opening here would re-acquire the lock file just before
        // the JVM exits, leaving an orphaned .lock.db that blocks the next
        // daemon start. Same defense in TripDatabase.reconnect.
        if (!isRunning) return;
        try {
            if (isConnectionDead()) {
                // Drop the dead handle first so the reopen isn't fighting a
                // half-closed session for the file lock.
                if (connection != null) {
                    try { connection.close(); } catch (Exception ignored) { /* already dead */ }
                    connection = null;
                }
                connection = DriverManager.getConnection(JDBC_URL, "sa", "");
                // Re-assert the schema BEFORE flagging ready. If the store
                // file was wiped or recreated, H2 hands back a fresh EMPTY
                // database — flagging initialized without this would leave
                // every write failing "Table SOC_HISTORY not found" in a
                // permanent 2-minute retry loop. createTables() is idempotent
                // (CREATE TABLE IF NOT EXISTS), matching what
                // RecordingsIndex.reopen() does for the same reason.
                createTables();
                // The store is back; without this, a connection that died
                // after init would stay flagged uninitialized-ish forever in
                // callers that check isInitialized.
                isInitialized = true;
                // MUST clear the escalation counter. isConnectionDead() returns true purely on the
                // counter once it saturates, so a fresh connection carrying a stale count would be
                // declared dead again on the very next call — an unbreakable reopen loop, strictly
                // worse than the bug this counter fixes.
                writeFailures.set(0);
                readFailures.set(0);
                logger.info("H2 SOC connection re-established");
            }
        } catch (Exception e) {
            logger.error("Failed to reconnect to H2", e);
        }
    }

    /**
     * True when the shared connection is unusable — null, or closed by H2
     * underneath us. Kept separate from the {@code == null} test because a
     * closed-but-non-null connection is the exact state that silently broke
     * SOC recording in the field.
     */
    private boolean isConnectionDead() {
        if (connection == null) return true;
        try {
            if (connection.isClosed()) return true;
        } catch (Exception e) {
            return true;   // can't even probe it — treat as dead
        }
        // BROKEN-BUT-OPEN escalation. isClosed() only catches a session H2 itself tore down.
        // An MVStore IO error, a full disk, or file corruption makes every statement throw while
        // leaving the connection OPEN — and reconnect() gates on this method, so the writer looped
        // "insert throws -> reconnect -> not dead -> return" every 2 min forever, with no new rows
        // and nothing but ERROR lines to show it. Once enough consecutive statements have failed,
        // declare it dead so reconnect() actually reopens.
        //
        // Counted rather than probed: a SELECT 1 on every call would add a query per write at the
        // 2-min cadence for a fault that is rare, whereas the counter costs nothing until things
        // are already failing. Any single success resets it (see noteStatementOk).
        return writeFailures.get() >= MAX_CONSECUTIVE_FAILURES
                || readFailures.get() >= MAX_CONSECUTIVE_FAILURES;
    }

    /**
     * Consecutive WRITE failures against the current connection; cleared by a successful write.
     * {@code AtomicInteger} because it is incremented from the SocHistoryDB executor and read from
     * HTTP query threads — a read-increment-write on a plain {@code volatile} could lose a count.
     */
    private final java.util.concurrent.atomic.AtomicInteger writeFailures =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /**
     * SEPARATE from {@link #writeFailures}. A single shared counter let a healthy READER zero it
     * between the writer's failing attempts: the UI polls the SOC graph every 60 s while the writer
     * only attempts an INSERT every 120 s (600 s parked), so on the canonical disk-full fault —
     * SELECT succeeds, INSERT fails — the count never reached the threshold and the store was never
     * flagged dead. That defeated the escalation for exactly the fault it targets. Keeping the two
     * apart means a read success can never clear a write escalation, or vice versa.
     */
    private final java.util.concurrent.atomic.AtomicInteger readFailures =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Failures tolerated before a still-open connection is treated as dead. 3 covers a transient
     * lock contention or one-off IO blip without letting a genuinely broken store persist for the
     * process lifetime.
     *
     * <p>Detection latency depends on which path is failing. A UI poll hits the query paths every
     * 60 s, so a visible graph escalates in ~3 min. The writer alone is slower: while SOC is moving
     * it attempts an INSERT every 2 min (~6 min), but on a parked car the dedup gate returns before
     * the INSERT and only the 10-min {@code forceRecord} heartbeat attempts one — worst case ~14 min
     * (one overdue heartbeat plus three 2-min ticks). Both are bounded, which is the point: before
     * this the broken-but-open state was never detected at all.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** Record a successful statement — clears the broken-but-open escalation. */
    private void noteWriteOk() {
        writeFailures.set(0);
    }

    /** A successful READ clears only the read counter — never the writer's escalation. */
    private void noteReadOk() {
        readFailures.set(0);
    }

    /**
     * True when {@code t} (or any cause) is a JDBC failure, i.e. real evidence that the statement
     * layer — not the HAL, not the SOH estimator — is broken. Matched by type where available and by
     * simple name through the cause chain, since a driver may wrap it.
     */
    private static boolean isSqlFailure(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof java.sql.SQLException) return true;
            String n = c.getClass().getSimpleName();
            if (n.startsWith("SQL") || n.startsWith("JdbcSQL")) return true;
            // H2 wraps store-level faults at the JDBC boundary in almost every path, but a raw
            // MVStoreException (a RuntimeException, no SQL prefix, no SQLException in its chain) is
            // exactly the disk-full / corruption case this escalation targets — so match it too
            // rather than let the one fault we care most about slip through uncounted.
            if (n.contains("MVStore") || n.contains("DbException")) return true;
        }
        return false;
    }

    /** Record a failed statement; at {@link #MAX_CONSECUTIVE_FAILURES} the connection is dead. */
    private void noteWriteFailed() {
        int n = writeFailures.incrementAndGet();
        if (n == MAX_CONSECUTIVE_FAILURES) {
            logger.error("SOC store: " + n + " consecutive WRITE failures on an OPEN"
                    + " connection — treating it as dead so the next call reopens it");
        }
    }

    /** Read-path sibling of {@link #noteWriteFailed()}; increments the read counter only. */
    private void noteReadFailed() {
        int n = readFailures.incrementAndGet();
        if (n == MAX_CONSECUTIVE_FAILURES) {
            logger.error("SOC store: " + n + " consecutive READ failures on an OPEN"
                    + " connection — treating it as dead so the next call reopens it");
        }
    }
    
    // ==================== DATA RECORDING ====================
    
    // synchronized: repriceSessionsForTariff runs an explicit transaction on this
    // SAME shared JDBC Connection (autoCommit is a CONNECTION-level property), so a
    // write landing from another thread mid-sweep would join that transaction and be
    // rolled back — or committed — with it. Serializing every writer on the DB
    // monitor keeps transaction scope to one thread.
    private synchronized void recordCurrentSoc() {
        // Wrap entire method in try-catch to prevent scheduler death
        try {
            // Bail out cleanly when stop() has already begun — otherwise we
            // race connection.close() and trip H2's "already closed" path,
            // which re-opens the DB on reconnect() and orphans the lock file.
            if (!isRunning) return;
            // A CLOSED-but-non-null connection must also trigger reconnect:
            // H2 can close the store underneath us (a thread interrupt during
            // an MVStore write surfaces as ClosedByInterruptException on the
            // backing FileChannel and takes the whole database down) while
            // this field stays non-null and isInitialized stays true. The
            // previous `connection == null` test therefore never fired, so
            // reconnect() was never reached and every subsequent write failed
            // with "The database has been closed [90098-224]" — observed in
            // the field for the rest of the daemon's uptime.
            if (!isInitialized || isConnectionDead()) {
                logger.debug("SOC recording skipped: not initialized or connection dead");
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
            // A constant-voltage taper counts as charging HERE even though the state code stays
            // FINISHED. Some firmware calls the session finished while the charger is still
            // delivering a declining current, and that tail is real energy the user pays for. The
            // state code is deliberately left untouched (so `full`/`plugged` keep reading true), so
            // the taper is carried by its own flag and has to be honoured explicitly — otherwise the
            // session closes at the FINISHED edge and the taper's energy, peak and duration are lost.
            boolean observedCharging = chargingData != null &&
                (chargingData.status == ChargingStateData.ChargingStatus.CHARGING
                 || chargingData.isTaperCharging);
            // Session-row ownership belongs to ChargingSessionManager once it has opened an analytics
            // session. A FINISHED callback can precede the final energy-counter callback or the next
            // 90-second parked poll that establishes a real PHEV taper. Keep the row open until the
            // manager explicitly closes it; the SOC history row itself still records the observed state.
            boolean sessionCharging = observedCharging || (chargingLifecycleHold && wasCharging);
            // Use the charger power ONLY when it's a real reading. getChargingState()
            // substitutes a nominal PLACEHOLDER (3.3 kW PHEV / 7.0 kW BEV, flagged
            // isEstimated) before the BMS reports real kW. Feeding that into the
            // session tracker seeded peak_power at 7.0 → the session mis-classified
            // as "AC fast" (>=7 kW) when it was really a 6 kW AC slow charge. Pass 0
            // for estimated reads so peak/avg only ever reflect measured power.
            double chargingPower = (chargingData != null && !chargingData.isEstimated
                    && isFinite(chargingData.chargingPowerKW)
                    && chargingData.chargingPowerKW > 0
                    && chargingData.chargingPowerKW <= 500)
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
            
            // SOH from the canonical resolver. The persisted soh_percent property is the
            // moving capacity estimate, not a presentation fallback.
            double sohPercent = -999;
            try {
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                        sohEst != null ? sohEst.getCapacitySohSnapshot() : null;
                if (capacitySoh != null && capacitySoh.hasDisplaySoh()) {
                    // Displayed (capped, anchored) SOH so stored history agrees with
                    // every live surface.
                    sohPercent = capacitySoh.getDisplaySoh();
                }
                if (sohPercent > 0) {
                    logger.debug("SOH from estimator: " + String.format("%.1f", sohPercent) + "%");
                } else {
                    logger.info("Canonical SOH unavailable; history sample omits SOH");
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
            boolean stateTransition = (sessionCharging != wasCharging)
                    || (observedCharging != lastRecordedObservedCharging);

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
                // Count a genuine JDBC failure so a broken-but-OPEN connection escalates to
                // dead and the reconnect below actually reopens it. Non-SQL throws are not
                // counted — they say nothing about the connection (see isSqlFailure).
                if (isSqlFailure(e)) noteWriteFailed();
                reconnect();
                return;
            }
            
            // Insert with all battery health columns
            String sql = "INSERT INTO " + TABLE_SOC +
                " (timestamp, soc_percent, is_charging, charging_power_kw, voltage_v, range_km, remaining_kwh," +
                " hv_temp_high, hv_temp_low, hv_temp_avg, cell_volt_high, cell_volt_low, soh_percent," +
                " remaining_kwh_format_version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";
            
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, now);
                pstmt.setDouble(2, soc);
                // The manager may hold the accounting row open briefly after the fused OFF edge for a
                // final counter/taper probe. That must not present as ongoing charging in SOC history.
                pstmt.setInt(3, observedCharging ? 1 : 0);
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
                pstmt.setInt(14, REMAINING_KWH_FORMAT_VERSION);
                pstmt.executeUpdate();
            }
            
            // The INSERT above completed, so the connection is demonstrably usable — clear any
            // broken-but-open escalation (see isConnectionDead).
            noteWriteOk();
            lastRecordTime = now;
            lastRecordedSoc = soc;
            lastRecordedObservedCharging = observedCharging;
            if (remainingKwh > 0) lastRecordedKwh = remainingKwh;

            logger.debug("Recorded SOC: " + soc + "% (charging: " + observedCharging + ")");
            
            // SocHistoryDatabase starts before ChargingSessionManager. Do not let its immediate first
            // tick manufacture an OFF edge for a restored row before config and detector ownership
            // have been established explicitly by the manager.
            if (chargingLifecycleOwnerReady) {
                if (!trackChargingSession(sessionCharging, soc, chargingPower, now)) {
                    logger.warn("Charging lifecycle write did not commit; state retained for retry");
                }
            } else {
                logger.debug("Charging lifecycle tick deferred until manager ownership is ready");
            }
            
        } catch (Exception e) {
            // Log but don't rethrow - scheduler must continue running
            logger.error("Failed to record SOC: " + e.getMessage(), e);
            // ONLY count it if the DATABASE failed. This try block also spans non-DB work — the
            // VehicleDataMonitor HAL reads above and the SohEstimator/BydDataCollector calls — and a
            // throw from any of those says nothing about the connection. Counting it would blame the
            // store for a HAL fault and force needless reopens (3 unlucky HAL throws would close and
            // reopen a perfectly healthy H2 file). SQLException is the only evidence that the
            // statement layer failed; anything else is left uncounted so isConnectionDead() keeps
            // reporting the truth.
            if (isSqlFailure(e)) {
                // Counted BEFORE reconnect(): once the threshold is reached isConnectionDead()
                // returns true, so this same call's reconnect() is the one that reopens the store.
                noteWriteFailed();
            }
            try {
                reconnect();
            } catch (Exception re) {
                logger.error("Reconnect also failed: " + re.getMessage());
            }
        }
    }
    
    private boolean trackChargingSession(boolean isCharging, double soc, double power, long now) {
        if (!isInitialized || connection == null) return false;
        if (!reconcilePendingActiveChargingReplacement()) return false;

        // Re-enable can happen before a failed disable-time close retries. The old row still belongs
        // only to the enabled segment that ended at optOutBoundaryMs; close that frozen segment before
        // processing any current charging observation. Counter callbacks and fast samples are fenced
        // separately while this flag is set, so no opted-out energy can enter its total or cost.
        if (chargingAnalyticsEnabled && optOutClosePending && wasCharging) {
            boolean enabled = chargingAnalyticsEnabled;
            boolean closed;
            try {
                chargingAnalyticsEnabled = false;
                closed = trackChargingSession(false,
                        !Double.isNaN(optOutBoundarySoc) ? optOutBoundarySoc : chargingStartSoc,
                        0, optOutBoundaryMs > 0 ? optOutBoundaryMs : now);
            } finally {
                chargingAnalyticsEnabled = enabled;
            }
            if (!closed) return false;
            analyticsDisabledSinceMs = 0L;
        }

        // Opt-in gate: Charging Analytics is an opt-out-able feature. When the
        // user has disabled it (chargingAnalytics.enabled=false, pushed here by
        // ChargingSessionManager), record NOTHING — no session rows, no config
        // reads, no rollups — so a disabled feature costs zero extra work on
        // the always-on 2-minute SoC tick. If a session was mid-flight when the
        // user disabled, reset the live state so we don't leave a dangling open
        // row to "end" later.
        if (!chargingAnalyticsEnabled) {
            if (wasCharging) {
                boolean closeCommitted = false;
                // Close any open session that was mid-flight when feature was disabled
                try {
                    if (connection != null && !connection.isClosed()) {
                        if (optOutBoundaryMs <= 0) {
                            optOutBoundaryMs = strictlyAfterChargingStart(
                                    chargingStartTime, now);
                        }
                        if (Double.isNaN(optOutBoundarySoc)) optOutBoundarySoc = soc;
                        if (!optOutCounterCaptured) {
                            double counterAtBoundary = snapshotChargeCounterKwh();
                            if (!Double.isNaN(counterAtBoundary)) {
                                observeFinalCounterForClose(counterAtBoundary, optOutBoundarySoc,
                                        optOutBoundaryMs);
                            }
                            // Do not sample again on retry: a later value belongs to the opted-out span.
                            optOutCounterCaptured = true;
                        }
                        if (!persistChargingLifecycleJournal()) {
                            throw new java.io.IOException(
                                    "opt-out close boundary was not durable");
                        }
                        final double closeSoc = !Double.isNaN(optOutBoundarySoc)
                                ? optOutBoundarySoc : soc;
                        // Update the open row with end values and mark it as ended.
                        // Persist ALL v2 columns (range/isDc/rate/currency/cost/ttf/
                        // thermal) just like the normal SESSION END flow, so an
                        // interrupted session isn't left with sentinel defaults.
                        String sql = "UPDATE " + TABLE_CHARGING +
                            " SET end_time = ?, end_soc = ?, energy_added_kwh = ?, avg_power_kw = ?, peak_power_kw = ?, " +
                            "range_gained_km = ?, is_dc = ?, electricity_rate = ?, currency = ?, session_cost = ?, " +
                            "time_to_full_min = ?, hv_temp_high = ?, hv_temp_low = ?, hv_temp_avg = ?, " +
                            "tariff_id = ?, tariff_label = ?, " +
                            "energy_source = ?, energy_soc_kwh = ?, energy_incomplete = ?, " +
                            "counter_energy_kwh = ?, counter_last_kwh = ?, counter_source = ?, " +
                            "counter_full_scale_kwh = ?, counter_last_at_ms = ?, " +
                            "counter_observation_generation = ?, counter_wrap_count = ?, " +
                            "counter_reset_count = ?, counter_ceiling_streak = ?, counter_saturated = ?, " +
                            "counter_abandoned_kwh = ?, counter_unattributed_gaps = ?, " +
                            "counter_awaiting_gap = ?, counter_gap_reconstructed = ?, " +
                            "counter_gap_estimate_kwh = ?, counter_recent_rate_kwh_per_h = ?, " +
                            "resume_blocked = 1, " +
                            "post_commit_tariff_applied = ?, post_commit_soh_applied = 1 " +
                            "WHERE start_time = ? AND end_time IS NULL;";
                        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                            // Same resolution as every other close path. The old code here fell back
                            // to `socDelta/100 * (nominal > 0 ? nominal : 60)` — a hardcoded 60 kWh
                            // pack, which is wrong on every PHEV (18-27 kWh) and on most BEVs in the
                            // range, and it was PRICED. When nothing credible exists the row now
                            // records 0 and is flagged, rather than inventing a figure.
                            com.overdrive.app.charging.SessionEnergyResolver.Result disRes =
                                    com.overdrive.app.charging.SessionEnergyResolver.resolve(
                                            meteredEnergyKwh(),
                                            chargingCounter.containsReconstructedGap(), chargingCounter.isIncomplete(),
                                            integrateSessionEnergyKwh(chargingStartTime),
                                            socEstimateForOpenSession(closeSoc),
                                            // Java evaluates arguments left-to-right, so the integrate
                                            // call above has already set this flag.
                                            lastIntegrationTruncated,
                                            counterScaleSuspect(counterOwner));
                            double energyAdded = disRes.isUsable() ? disRes.energyKwh : 0;

                            // The opt-out instant is an exact user-selected accounting boundary.
                            // Power samples may stop while SOC/counter energy still rises, so using
                            // the last sample truncates duration and can fold the charge into the
                            // wrong UTC day.
                            long closeTime = strictlyAfterChargingStart(
                                    chargingStartTime, optOutBoundaryMs);
                            double avgPower = resolveAveragePowerKw(
                                    chargingStartTime, chargingPowerSum,
                                    chargingPowerCount);

                            // Resolve the peak from BOTH series before the AC/DC verdict uses it, as
                            // the other three close paths do. The coarse 2-min running max can miss a
                            // ramp peak the 12-s sampler caught, and deriveIsDc needs a DC-plausible
                            // peak to honour a DC gun — so the unresolved figure both understated the
                            // stored peak and mispriced a genuine DC session at the AC rate.
                            chargingPeakPower = resolvePeakKw(chargingStartTime, chargingPeakPower);
                            // Peak-guarded so a misread DC gun on a low-power charge isn't stored as DC.
                            int isDc = optOutCloseIsDc >= -1
                                    ? optOutCloseIsDc
                                    : currentChargingTypeVerdict();
                            int rangeGained = rangeGainedFromEnergy(energyAdded);
                            // Price at the tariff for WHERE this charge happened,
                            // else the global DC/base rate (see priceSession).
                            if (optOutClosePricing == null) {
                                optOutClosePricing = priceSessionForClose(
                                        isDc, chargingStartLat, chargingStartLng);
                            }
                            // A successful retry-time config read becomes part of the frozen
                            // opt-out boundary before the H2 transaction is attempted. If H2 is
                            // still unavailable, restart must retry this exact price.
                            if (!persistChargingLifecycleJournal()) {
                                throw new java.io.IOException(
                                        "opt-out close snapshot was not durable");
                            }
                            PricingDecision pd = optOutClosePricing;
                            double rate = pd.rate;
                            String curr = pd.currency;
                            double cost = pd.costFor(energyAdded);
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

                            final double persistedAvgPower = avgPower;
                            final double persistedPeakPower = chargingPeakPower;
                            final double persistedTHi = tHi;
                            final double persistedTLo = tLo;
                            final double persistedTAvg = tAvg;
                            runInTransaction(() -> {
                                pstmt.setLong(1, closeTime);
                                pstmt.setDouble(2, closeSoc);
                                pstmt.setDouble(3, energyAdded);
                                pstmt.setDouble(4, persistedAvgPower);
                                pstmt.setDouble(5, persistedPeakPower);
                                pstmt.setInt(6, rangeGained);
                                pstmt.setInt(7, isDc);
                                pstmt.setDouble(8, rate);
                                pstmt.setString(9, curr);
                                pstmt.setDouble(10, cost);
                                pstmt.setInt(11, ttf);
                                pstmt.setDouble(12, persistedTHi);
                                pstmt.setDouble(13, persistedTLo);
                                pstmt.setDouble(14, persistedTAvg);
                                pstmt.setString(15, pd.tariffId);
                                pstmt.setString(16, pd.tariffLabel);
                                // Provenance, same as the other close paths. Omitting it here left an
                                // incomplete or SOC-derived total looking like a confident measurement:
                                // energy_incomplete stayed 0 so the UI showed no '~', and energy_source
                                // stayed empty so support could not tell what produced the figure.
                                pstmt.setString(17, disRes.source);
                                if (!Double.isNaN(disRes.socEstimateKwh)) {
                                    pstmt.setDouble(18, disRes.socEstimateKwh);
                                } else {
                                    pstmt.setNull(18, java.sql.Types.DOUBLE);
                                }
                                pstmt.setInt(19, disRes.incomplete ? 1 : 0);
                                if (chargingCounter.hasBaseline()) {
                                    pstmt.setDouble(20, chargingCounter.energyKwh());
                                    pstmt.setDouble(21, chargingCounter.lastRawKwh());
                                    if (counterOwner != null) pstmt.setString(22, counterOwner);
                                    else pstmt.setNull(22, java.sql.Types.VARCHAR);
                                    pstmt.setDouble(23, chargingCounter.fullScaleKwh());
                                } else {
                                    pstmt.setNull(20, java.sql.Types.DOUBLE);
                                    pstmt.setNull(21, java.sql.Types.DOUBLE);
                                    pstmt.setNull(22, java.sql.Types.VARCHAR);
                                    pstmt.setNull(23, java.sql.Types.DOUBLE);
                                }
                                int next = bindCounterState(
                                        pstmt, 24, chargingCounter.snapshotState());
                                pstmt.setInt(next++, pd.tariffId.isEmpty() ? 1 : 0);
                                pstmt.setLong(next, chargingStartTime);
                                if (pstmt.executeUpdate() != 1) {
                                    throw new java.sql.SQLException(
                                            "feature-disable close found no matching open session");
                                }

                                // Fold this interrupted session into the permanent daily
                                // rollup, keyed on the actual session-end day (closeTime),
                                // not the wall-clock disable moment.
                                foldSessionIntoDaily(closeTime, energyAdded, cost, isDc,
                                        persistedPeakPower, rangeGained, disRes.incomplete);
                            });
                            closeCommitted = true;
                            replayPendingChargingPostCommitMetadata();
                        }
                    }
                } catch (Exception e) {
                    // COMMIT has an uncertain-result failure mode: H2 may durably commit and then
                    // throw while returning the result. A retry updates zero rows because the row is
                    // already closed. Reconcile the exact identity before retaining live state.
                    closeCommitted = isSessionDurablyClosed(chargingStartTime);
                    if (!closeCommitted && isSqlFailure(e)) {
                        noteWriteFailed();
                        try { reconnect(); } catch (Exception ignored) {}
                        closeCommitted = isSessionDurablyClosed(chargingStartTime);
                    }
                    if (closeCommitted) {
                        replayPendingChargingPostCommitMetadata();
                    }
                    logger.debug("Feature-disable close threw"
                            + (closeCommitted ? " after the row was durably closed: " : ": ")
                            + e.getMessage());
                }
                // Retain both the live flag and all aggregates after a failed close. The disabled
                // recorder will retry on its next tick; clearing them here would strand the open row
                // and make a later retry impossible to account correctly.
                if (!closeCommitted) return false;
                resetLiveChargingState(true);
                if (chargingLifecycleJournalDirty
                        && !persistChargingLifecycleJournal()) {
                    return false;
                }
            }
            return true;
        }

        // A newer physical charge is being buffered while this row retries its frozen close. The
        // recorder can still present charging=true through its lifecycle hold, but none of those live
        // values belong to the old row.
        if (sessionInputsFenced && isCharging && wasCharging) {
            return !counterProgressDirty || persistCounterProgress();
        }

        final boolean lifecycleTransition = isCharging != wasCharging;
        SessionStartAttempt startAttempt = null;
        try {
            if (isCharging && !wasCharging) {
                // ---- SESSION START (or RESUME) ----
                final DeferredChargingGeneration deferredGeneration =
                        deferredPhysicalGenerations.peekFirst();
                final boolean deferredPhysicalStart = deferredGeneration != null;
                if (deferredPhysicalStart) {
                    now = deferredGeneration.startMs > 0 ? deferredGeneration.startMs : now;
                    if (!Double.isNaN(deferredGeneration.startSoc)) {
                        soc = deferredGeneration.startSoc;
                    }
                } else {
                    // Use a database-backed monotonic identity before resume/continuation lookups too.
                    // A wall-clock rollback must not hide the most recent row behind start_time < now.
                    now = allocateMonotonicChargingStart(now);
                }
                // A daemon restart resets wasCharging to false. Without this,
                // an UNINTERRUPTED charge that spans a restart (or a brief
                // charging-state flicker) would open a brand-new session row,
                // fragmenting one physical charge into several. Before inserting,
                // look for a session to RESUME: the most recent one whose gap to
                // `now` is within CHARGING_MERGE_GAP_MS. If found, re-adopt it
                // (restore start time/soc/aggregates) instead of creating a row.
                if (!deferredPhysicalStart && tryResumeChargingSession(now, soc)) {
                    // RESTORE the charged-energy accumulator from the row. The counter kept
                    // advancing while the daemon was down, so re-baselining at the CURRENT reading
                    // would silently discard everything delivered during the outage. The persisted
                    // endpoints are the only record of where we were.
                    wasCharging = isCharging;
                    return true;
                }

                chargingStartTime = now;
                chargingStartSoc = soc;
                // Seed the running max/mean only from a REAL power reading. `power`
                // is 0 here when the start tick had an estimated/placeholder kW
                // (see recordCurrentSoc) — seeding 0 keeps the average honest and
                // lets the first measured tick set the true peak.
                chargingPeakPower = deferredPhysicalStart
                        ? deferredGeneration.peakPower : power > 0 ? power : 0;
                chargingPowerSum = deferredPhysicalStart
                        ? deferredGeneration.powerSum : power > 0 ? power : 0;
                chargingPowerCount = deferredPhysicalStart
                        ? deferredGeneration.powerCount : power > 0 ? 1 : 0;
                if (deferredPhysicalStart) {
                    chargingStartRange = deferredGeneration.startRange;
                    chargingStartOdometer = deferredGeneration.startOdometer;
                    chargingGunState = deferredGeneration.gun;
                    chargingTimeToFullMin = deferredGeneration.timeToFull;
                    chargingStartLat = deferredGeneration.lat;
                    chargingStartLng = deferredGeneration.lng;
                } else {
                    chargingStartRange = snapshotRangeKm();
                    chargingStartOdometer = snapshotOdometerKm();
                    chargingGunState = snapshotGunState();
                    chargingTimeToFullMin = snapshotTimeToFullMin();
                    double[] loc = snapshotLocation();
                    chargingStartLat = loc[0];
                    chargingStartLng = loc[1];
                }
                chargingTypeVerdict = deferredPhysicalStart
                        ? deferredGeneration.typeVerdict
                        : ChargingTypeClassifier.classify(
                                chargingGunState, chargingPeakPower);
                if (chargingTypeVerdict != ChargingTypeClassifier.AC
                        && chargingTypeVerdict != ChargingTypeClassifier.DC) {
                    chargingTypeVerdict = ChargingTypeClassifier.classify(
                            chargingGunState, chargingPeakPower);
                }
                // Fresh accumulator for a genuinely new session, then capture the counter's
                // baseline. A stale accumulator here would attribute a previous charge's energy to
                // this one, so the reset is unconditional and precedes the first observation.
                chargingCounter.reset();
                counterOwner = null;   // a new charge chooses its own counter source
                boolean freshPreOpenExternal = !deferredPhysicalStart
                        && Double.isFinite(preSessionProvisionalExternalRaw)
                        && preSessionProvisionalExternalAtMs > 0L
                        && now - preSessionProvisionalExternalAtMs
                                <= PRE_SESSION_COUNTER_MAX_AGE_MS;
                if (freshPreOpenExternal) {
                    provisionalExternalUnitDivisor =
                            validCounterUnitDivisor(
                                    preSessionProvisionalExternalUnitDivisor);
                    provisionalExternalKwh =
                            preSessionProvisionalExternalRaw
                                    / provisionalExternalUnitDivisor;
                    provisionalExternalAtMs =
                            preSessionProvisionalExternalAtMs;
                } else {
                    provisionalExternalKwh = Double.NaN;
                    provisionalExternalAtMs = 0L;
                    provisionalExternalUnitDivisor = 1.0;
                }
                clearCounterBaselineCandidates();
                // The baseline is deliberately NOT seeded from the snapshot at this instant. The
                // counter field is intentionally not cleared at gun-out (it is an energy total, and
                // the close path needs its final value), so right after a session opens it may still
                // hold the PREVIOUS charge's figure — the vehicle resets it per session, but not
                // necessarily before our detector opens the row. Seeding from it would set a
                // too-high baseline and lose everything up to the vehicle's own reset: on a DC
                // session opened before the counter cleared, a stale 2.0 kWh baseline silently
                // discards the first 2 kWh. Leaving the baseline unset lets the first MID-SESSION
                // observation establish it from a value the vehicle has already re-zeroed.
                counterBaselinePending = true;
                counterBaselinePendingSinceMs = now;
                final ContinuationOffer continuationOffer = deferredPhysicalStart
                        ? deferredGeneration.continuationOffer
                        : findImmediateContinuationOffer(now);
                continuationSource = continuationOffer != null ? continuationOffer.source : null;
                pendingSweepMarkerRow = continuationOffer != null ? continuationOffer.rowStart : -1L;
                boolean continuationApplied = false;
                if (deferredPhysicalStart) {
                    finalizeDeferredCounterBaseline(deferredGeneration);
                    // This is an in-process/journal materialization, not a newly observed outage.
                    // Copy the exact accumulator image so zero-delta state, wrap/reset counters,
                    // saturation and an already-resolved gap cannot be reinterpreted by restore().
                    chargingCounter.restoreState(
                            deferredGeneration.counter.snapshotState());
                    counterOwner = deferredGeneration.counterOwner;
                    counterBaselinePending =
                            deferredGeneration.counterBaselinePending;
                    counterBaselinePendingSinceMs =
                            deferredGeneration.counterBaselinePendingSinceMs;
                    counterBaselineCandidateKwh =
                            deferredGeneration.counterCandidateKwh;
                    counterBaselineCandidateAtMs =
                            deferredGeneration.counterCandidateAtMs;
                    counterBaselineLatestKwh =
                            deferredGeneration.counterLatestKwh;
                    counterBaselineLatestAtMs =
                            deferredGeneration.counterLatestAtMs;
                    provisionalExternalKwh =
                            deferredGeneration.provisionalExternalKwh;
                    provisionalExternalAtMs =
                            deferredGeneration.provisionalExternalAtMs;
                    provisionalExternalUnitDivisor =
                            deferredGeneration.provisionalExternalUnitDivisor;
                    if (chargingCounter.hasBaseline()) {
                        lastSessionCounterKwh =
                                chargingCounter.lastRawKwh();
                    }
                }
                // PRE-SESSION READING. Detection lags the physical start, so a reading taken before
                // this row existed may already be the vehicle's post-reset zero — the true baseline.
                // Adopt it when it is recent and lower than what the counter reads now (lower proves it
                // predates this charge's accumulation, and equal adds nothing). Without this the
                // baseline waited for a MID-SESSION observation and everything delivered up to it was
                // absorbed and lost. Checked BEFORE the continuation test, which needs a clean pending
                // state to reason about.
                double preLow = preSessionCounterLowKwh;
                long preAt = preSessionCounterAtMs;
                String preSrc = preSessionCounterSource;
                long preAgeMs = now - preAt;
                if (!deferredPhysicalStart
                        && Double.isFinite(preLow) && preLow >= 0.0
                        && preAt > 0 && isValidCounterSource(preSrc, false)
                        && preAgeMs >= 0L
                        && preAgeMs <= PRE_SESSION_COUNTER_MAX_AGE_MS) {
                    // BIND THE BUFFERED SOURCE FIRST. Ownership is unbound at this point, so calling
                    // snapshotChargeCounterKwh() blind would let it bind whichever source it happens to
                    // prefer — and pairing that with a baseline captured from the OTHER source computes
                    // added energy across two unrelated series. Binding first makes the snapshot return
                    // the same source the buffered value came from, or NaN if it has gone quiet.
                    if (counterOwner == null) bindCounterOwner(preSrc);
                    double liveNow = preSrc.equals(counterOwner)
                            ? snapshotChargeCounterKwh() : Double.NaN;
                    // A PRE-DETECTION READING IS NOT PROOF OF A RESET. An interrupted row ending at 10,
                    // with pre-session readings of 12 -> 13, means the counter never reset and the 10 -> 12
                    // delivered during the outage belongs to this session — but baselining at 12 discards
                    // it and consumes the marker on the false premise that a reset occurred. Check the
                    // interrupted endpoint FIRST: if this reading sits at or above it and within a
                    // plausible outage, the endpoint is the better baseline.
                    double contEndpoint = Double.NaN;
                    if (!Double.isNaN(liveNow)) {
                        double cand = continuationOffer != null
                                ? continuationOffer.endpointKwh : Double.NaN;
                        boolean srcOk = (continuationSource != null)
                                ? continuationSource.equals(counterOwner)
                                : (com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY
                                        .equals(counterOwner) || counterOwner == null);
                        boolean gapOk = (continuationSource != null)
                                || (preLow - cand) <= LEGACY_CONTINUATION_MAX_GAP_KWH;
                        if (srcOk && !Double.isNaN(cand) && gapOk) {
                            contEndpoint = cand;
                        }
                    }
                    if (!Double.isNaN(contEndpoint)) {
                        // Continuation wins: seed at the interrupted endpoint so the outage is credited,
                        // then walk forward through the pre-detection reading and the live one.
                        continuationApplied = applyContinuationOffer(
                                continuationOffer, preLow, soc, preAt);
                        if (continuationApplied) {
                            if (liveNow != preLow) chargingCounter.observe(liveNow, now);
                            lastSessionCounterKwh = liveNow;
                        }
                    } else if (!Double.isNaN(liveNow) && preLow <= liveNow) {
                        counterBaselinePending = false;
                        counterBaselinePendingSinceMs = 0;
                        chargingCounter.observe(preLow, preAt);
                        if (liveNow > preLow) chargingCounter.observe(liveNow, now);
                        lastSessionCounterKwh = liveNow;
                        logger.info(String.format(java.util.Locale.US,
                                "Session %d baselined at a pre-detection counter reading %.3f kWh"
                                + " (counter now %.3f) — crediting %.3f kWh delivered before the"
                                + " session was detected",
                                now, preLow, liveNow, liveNow - preLow));
                    } else if (Double.isNaN(liveNow)) {
                        // The confirmed observation is the only durable opening endpoint available.
                        // Move it into the active accumulator before the write-ahead generation clears
                        // standalone ownership, so a transiently unavailable getter cannot lose the
                        // energy delivered before the next callback.
                        adoptConfirmedPreSessionCounterAsActiveBaseline(
                                preLow, preAt, preSrc);
                    }
                }
                // CONTINUATION CHECK. This SESSION START may not be a new charge at all: a restart
                // after an outage longer than the resume window declines the resume and lands here
                // while the SAME physical charge is still running. The old row is closed at whatever
                // it had persisted, and if this session then re-baselines at the counter's current
                // value, everything delivered during the outage falls between the two rows and is
                // credited to NEITHER.
                //
                // The counter itself tells us which case this is: if it reads at or above the value
                // the previous row last persisted, the vehicle never reset it, so this is a
                // continuation and that difference is real energy belonging to this session. Seed the
                // baseline at the persisted value to capture it. A LOWER reading means the vehicle
                // did reset, so the normal fresh-baseline path is correct.
                // Only when no baseline is established yet. The pre-session path above may already
                // have anchored this session at the vehicle's own reset, which is strictly better
                // evidence than a previous row's endpoint — and it also CONSUMES the sweep marker, so
                // running both would credit the outage twice and burn the one-shot token.
                // The offer was selected once, before any source binding, and is claimed atomically with
                // this row's INSERT below. It therefore belongs to this immediate next session even when
                // no usable counter ever arrives.
                double prevPersistedLast = continuationOffer != null
                        ? continuationOffer.endpointKwh : Double.NaN;
                boolean baselineAlreadySet = !counterBaselinePending;
                if (baselineAlreadySet && !Double.isNaN(prevPersistedLast)) {
                    prevPersistedLast = Double.NaN;
                }
                double counterNowAtStart = snapshotChargeCounterKwh();
                // SAME SOURCE ONLY. The persisted endpoint came from one specific counter; differencing
                // it against a reading from the other one spans two unrelated series and invents energy.
                // A legacy row with no recorded source is accepted only when this session is on the
                // default capacity counter, which is what such rows were written by.
                boolean sameSeries;
                if (continuationSource != null) {
                    sameSeries = continuationSource.equals(counterOwner);
                } else {
                    // LEGACY ROW WITH NO RECORDED SOURCE. Assuming capacity was a guess, and when it was
                    // wrong the old EXTERNAL endpoint got differenced against the capacity counter —
                    // two unrelated series, so the "outage energy" it credits is fabricated. There is a
                    // cheap consistency check available: the endpoint is the last value the OWNING
                    // counter reported, and a counter only rises within a session, so the live reading
                    // of the true owner must be at or above it and within a plausible outage's worth of
                    // it. A cross-series pairing typically fails both. Require that agreement instead of
                    // assuming the source.
                    // Also require the live SOURCE to be the one legacy rows were written by. Value
                    // plausibility alone still admits a coincidence: an old EXTERNAL endpoint and a
                    // current CAPACITY reading can land within the 60 kWh window by chance, and
                    // differencing them fabricates energy. Legacy rows predate external-counter support
                    // entirely, so only a capacity-owned session can legitimately continue one.
                    boolean ownerIsLegacyPlausible =
                            com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY
                                    .equals(counterOwner) || counterOwner == null;
                    boolean plausiblePairing = ownerIsLegacyPlausible
                            && !Double.isNaN(prevPersistedLast)
                            && !Double.isNaN(counterNowAtStart)
                            && counterNowAtStart >= prevPersistedLast
                            && (counterNowAtStart - prevPersistedLast)
                                    <= LEGACY_CONTINUATION_MAX_GAP_KWH
                            // A legacy row's endpoint came from the dedicated capacity counter, so a
                            // value beyond that ceiling cannot belong to the same series.
                            && prevPersistedLast <= com.overdrive.app.charging
                                    .ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH
                            && counterNowAtStart <= com.overdrive.app.charging
                                    .ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH;
                    sameSeries = plausiblePairing;   // owner check folded into plausiblePairing above
                    if (!plausiblePairing && !Double.isNaN(prevPersistedLast)
                            && !Double.isNaN(counterNowAtStart)) {
                        logger.info(String.format(java.util.Locale.US,
                                "Declining legacy continuation: endpoint %.3f vs live %.3f is not a"
                                + " plausible same-counter pairing, so the row's source cannot be"
                                + " confirmed and differencing them would invent energy",
                                prevPersistedLast, counterNowAtStart));
                    }
                }
                if (!deferredPhysicalStart && sameSeries
                        && !Double.isNaN(prevPersistedLast)
                        && !Double.isNaN(counterNowAtStart)) {
                    continuationApplied = applyContinuationOffer(
                            continuationOffer, counterNowAtStart, soc, now);
                }
                final DeferredChargingGeneration journaledStartGeneration =
                        deferredPhysicalStart
                                ? deferredGeneration
                                : journalCurrentChargingStart(continuationOffer);

                String sql = "INSERT INTO " + TABLE_CHARGING +
                    " (start_time, start_soc, peak_power_kw, avg_power_kw, gun_state, start_lat, start_lng,"
                    + " start_range_km, start_odometer_km, time_to_full_min, counter_start_kwh,"
                    + " counter_last_kwh, counter_energy_kwh, counter_source, counter_full_scale_kwh,"
                    + " counter_last_at_ms, counter_observation_generation,"
                    + " counter_wrap_count, counter_reset_count,"
                    + " counter_ceiling_streak, counter_saturated, counter_abandoned_kwh,"
                    + " counter_unattributed_gaps, counter_awaiting_gap,"
                    + " counter_gap_reconstructed, counter_gap_estimate_kwh,"
                    + " counter_recent_rate_kwh_per_h) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);";

                final boolean offerAppliedAtStart = continuationApplied;
                startAttempt = new SessionStartAttempt(now, continuationOffer,
                        offerAppliedAtStart || !counterBaselinePending, true);
                final long persistedStartTime = now;
                // applyContinuationOffer moves the accounting origin back to the interrupted row's
                // endpoint SOC. Persist that same origin so a restart does not silently revert the
                // continuation to the later detection SOC and lose the outage energy frame.
                final double persistedStartSoc = chargingStartSoc;
                final double persistedStartPeak = chargingPeakPower;
                final double persistedStartAvg = chargingPowerCount > 0
                        ? chargingPowerSum / chargingPowerCount : 0;
                runInTransaction(() -> {
                    try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                        pstmt.setLong(1, persistedStartTime);
                        pstmt.setDouble(2, persistedStartSoc);
                        pstmt.setDouble(3, persistedStartPeak);
                        pstmt.setDouble(4, persistedStartAvg);
                        pstmt.setInt(5, chargingGunState);
                        pstmt.setDouble(6, chargingStartLat);
                        pstmt.setDouble(7, chargingStartLng);
                        pstmt.setInt(8, chargingStartRange);
                        pstmt.setInt(9, chargingStartOdometer);
                        pstmt.setInt(10, chargingTimeToFullMin);
                        if (chargingCounter.hasBaseline()) {
                            pstmt.setDouble(11, chargingCounter.baselineKwh());
                            pstmt.setDouble(12, chargingCounter.lastRawKwh());
                            pstmt.setDouble(13, chargingCounter.energyKwh());
                            pstmt.setDouble(15, chargingCounter.fullScaleKwh());
                        } else {
                            pstmt.setNull(11, java.sql.Types.DOUBLE);
                            pstmt.setNull(12, java.sql.Types.DOUBLE);
                            pstmt.setNull(13, java.sql.Types.DOUBLE);
                            pstmt.setNull(15, java.sql.Types.DOUBLE);
                        }
                        if (counterOwner != null) pstmt.setString(14, counterOwner);
                        else pstmt.setNull(14, java.sql.Types.VARCHAR);
                        bindCounterState(
                                pstmt, 16, chargingCounter.snapshotState());
                        if (pstmt.executeUpdate() != 1) {
                            throw new java.sql.SQLException("charging session INSERT did not create one row");
                        }
                    }
                    if (journaledStartGeneration != null) {
                        insertDeferredChargingSamples(
                                journaledStartGeneration, persistedStartTime);
                    }
                    claimContinuationOffer(continuationOffer);
                });
                // The write-ahead generation remains durable until this active image can replace it.
                wasCharging = true;
                activateClaimedContinuationOffer(continuationOffer, now,
                        offerAppliedAtStart || !counterBaselinePending);
                preSessionCounterLowKwh = Double.NaN;
                preSessionCounterAtMs = 0;
                preSessionCounterSource = null;
                clearPreSessionProvisionalExternal();
                consumeDeferredPhysicalSessionAfterStart(now);
                counterProgressDirty = false;

                // Resolve a human place label asynchronously (SafeLocation
                // "Home"/"Office" name first, else reverse-geocode) and write it
                // back to this session row when it arrives. Best-effort.
                resolvePlaceLabelAsync(chargingStartTime, chargingStartLat, chargingStartLng);

                logger.info("Charging session started at " + soc + "% (gun=" + chargingGunState
                    + ", loc=" + chargingStartLat + "," + chargingStartLng + ")");

            } else if (isCharging && wasCharging) {
                // ---- MID-SESSION TICK ----
                if (power > 0 && !advancePendingCloseForAdmittedTaper(
                        chargingStartTime, now, soc, -999, -999, -999)) {
                    return false;
                }
                // v1 did nothing here, freezing peak_power_kw at the start
                // instant. Advance the true running max + mean and persist them
                // so an interrupted session still has a meaningful peak/avg.
                if (power > chargingPeakPower) chargingPeakPower = power;
                if (power > 0) { chargingPowerSum += power; chargingPowerCount++; }
                double avgSoFar = chargingPowerCount > 0 ? chargingPowerSum / chargingPowerCount : power;
                // Advance the charged-energy accumulator. Deliberately INDEPENDENT of `power`:
                // metered energy must keep accruing on a trim whose rate getters are dead, so this
                // is not nested under any power > 0 test. If the counter never answers here, the
                // accumulator simply stays empty and the resolver falls back.
                double counterNow = snapshotChargeCounterKwh();
                if (!Double.isNaN(counterNow)) {
                    // Keep the accumulator's independent estimate current. It uses this ONLY to veto
                    // an implausible wrap (a reset while the counter is high is otherwise
                    // indistinguishable from one); it never contributes to the total.
                    chargingCounter.setIndependentEstimate(socEstimateForOpenSession(soc));
                    if (counterBaselinePending) {
                        rememberCounterBaselineCandidate(counterNow, now);
                        // Accept a baseline only once the reading cannot be the previous session's
                        // leftover. STRICTLY below the last value we saw, not "at or below": a reading
                        // EQUAL to the previous session's final value is the most likely stale case
                        // (the vehicle has not re-zeroed yet), and accepting it is precisely the bug
                        // this guard exists to prevent. A genuinely re-zeroed counter reads lower.
                        boolean looksFresh = Double.isNaN(lastSessionCounterKwh)
                                || counterNow < lastSessionCounterKwh;
                        // ESCAPE HATCH. A trim that resets its counter only at plug-in — or one whose
                        // counter legitimately continues from where it stopped — would never satisfy
                        // the test above, leaving the baseline pending forever and the session with no
                        // metered energy at all. After a bounded wait, take the current reading and
                        // accept the risk of a slightly-high baseline: an approximate metered figure
                        // that the resolver will still cross-check beats no metered figure at all.
                        boolean waitedLongEnough = counterBaselinePendingSinceMs > 0
                                && (now - counterBaselinePendingSinceMs) > COUNTER_BASELINE_WAIT_MS;
                        if (looksFresh || waitedLongEnough) {
                            long baselineWaitMs = counterBaselinePendingSinceMs > 0
                                    ? now - counterBaselinePendingSinceMs : 0;
                            counterBaselinePending = false;
                            counterBaselinePendingSinceMs = 0;
                            // RETRY THE CONTINUATION. At SESSION START the counter may not have been
                            // reporting yet, so the continuation check had nothing to compare and the
                            // outage energy was left uncredited — permanently, because it was only ever
                            // attempted once. Now that a real reading exists, ask again.
                            if (tryLateContinuation(counterNow, now)) {
                                clearCounterBaselineCandidates();
                                lastSessionCounterKwh = counterNow;
                                return persistCounterProgress();
                            }
                            // Late continuation declined, and this baseline stands — so the offer can
                            // never apply to this session and must be retired rather than left claimable.
                            consumeSupersededSweepMarker("a mid-session baseline was established and the"
                                    + " late-continuation check declined the offer");
                            if (!looksFresh) {
                                logger.info("Counter baseline taken without an observed reset after "
                                        + (baselineWaitMs / 60000)
                                        + " min — metered energy may understate this session");
                            }
                            double baseline = looksFresh ? counterNow : counterBaselineCandidateKwh;
                            long baselineAt = looksFresh ? now : counterBaselineCandidateAtMs;
                            if (Double.isNaN(baseline)) {
                                baseline = counterNow;
                                baselineAt = now;
                            }
                            chargingCounter.observe(baseline, baselineAt > 0 ? baselineAt : now);
                            if (counterNow != baseline) chargingCounter.observe(counterNow, now);
                            clearCounterBaselineCandidates();
                            counterProgressDirty = true;
                            persistCounterProgress();
                        }
                        // else: still showing the old session's value — wait for the reset.
                    } else {
                        chargingCounter.observe(counterNow, now);
                        counterProgressDirty = true;
                        // Persist every tick so a crash loses at most one interval of attribution.
                        persistCounterProgress();
                    }
                    lastSessionCounterKwh = counterNow;
                }
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
                    if (pstmt.executeUpdate() != 1) {
                        throw new java.sql.SQLException(
                                "mid-session aggregate found no matching open row");
                    }
                }
                if (counterProgressDirty && !persistCounterProgress()) return false;

            } else if (!isCharging && wasCharging) {
                // ---- SESSION END ----
                if (pendingCloseSessionStart != chargingStartTime) {
                    capturePendingChargingClose();
                }
                if (pendingCloseSessionStart == chargingStartTime) {
                    if (pendingCloseAtMs > 0) now = pendingCloseAtMs;
                    if (!Double.isNaN(pendingCloseSoc)) soc = pendingCloseSoc;
                }
                now = strictlyAfterChargingStart(chargingStartTime, now);
                double socDelta = soc - chargingStartSoc;

                // Compute energy added using nominal capacity if available,
                // otherwise fall back to rough estimate
                double energyAdded = 0;
                double packTemp = 25.0;    // Default — updated below if available

                // Prefer ∫P·dt over the recorded samples — SOC-delta reads ~0 for
                // a slow charge that didn't move a whole percent, which blanked
                // energy/cost/range. Same basis as the live in-progress card.
                // ENERGY RESOLUTION. Three independent estimates, ranked, with the best one
                // cross-checked rather than trusted outright:
                //   1. the vehicle's own charged-energy counter (metered, wrap/reset-aware)
                //   2. the time integral of the sampled rate
                //   3. SOC delta x usable capacity
                // The counter wins when it agrees with the SOC estimate within a ratio band. It
                // can silently lose a segment (BMS pause/resume restarts it), and this figure is
                // priced, so no single source is trusted alone. The old code's SOC*capacity and
                // `socDelta * 0.6` guesses are gone: 0.6 encoded a 60 kWh pack, which is wrong on
                // every PHEV and on most BEVs in the range.
                // FINAL counter observation before resolving. The charge stopped between the last
                // mid-session tick and now (up to one tick period — 2 min, which at 150 kW is
                // 5 kWh), and the counter still holds that tail because it is an energy total and
                // is deliberately not cleared at the gun-out edge.
                if (!pendingCloseCounterCaptured) {
                    double counterAtEnd = snapshotChargeCounterKwh();
                    if (!Double.isNaN(counterAtEnd)) {
                        observeFinalCounterForClose(counterAtEnd, soc, now);
                    }
                    pendingCloseCounterCaptured = true;
                }

                double meteredKwh = meteredEnergyKwh();
                double socEstimate = socEstimateForOpenSession(soc);
                double integrated = integrateSessionEnergyKwh(chargingStartTime);
                com.overdrive.app.charging.SessionEnergyResolver.Result energyRes =
                        com.overdrive.app.charging.SessionEnergyResolver.resolve(
                                meteredKwh, chargingCounter.containsReconstructedGap(), chargingCounter.isIncomplete(),
                                integrated, socEstimate, lastIntegrationTruncated,
                                counterScaleSuspect(counterOwner));
                energyAdded = energyRes.isUsable() ? energyRes.energyKwh : 0;
                logger.info(String.format(java.util.Locale.US,
                        "Session energy resolved: %.3f kWh via %s (metered=%s integrated=%.3f"
                        + " soc=%s incomplete=%s)",
                        energyAdded, energyRes.source,
                        Double.isNaN(meteredKwh) ? "n/a" : String.format("%.3f", meteredKwh),
                        integrated,
                        Double.isNaN(socEstimate) ? "n/a" : String.format("%.3f", socEstimate),
                        energyRes.incomplete));

                // Battery temperature at session end (for calibration + chart).
                double tHi = pendingCloseTempHigh;
                double tLo = pendingCloseTempLow;
                double tAvg = pendingCloseTempAvg;
                if (tAvg > -999) packTemp = tAvg;

                // Resolve the peak BEFORE the AC/DC verdict consumes it: deriveIsDc requires a
                // DC-plausible peak to honour a DC gun, and the coarse 2-minute running max can
                // miss a ramp peak that the 12-second sampler recorded. Using the coarse figure
                // alone downgraded genuine DC sessions to the AC rate.
                chargingPeakPower = resolvePeakKw(chargingStartTime, chargingPeakPower);

                // AC/DC from gun state, peak-guarded against a HAL gun misread
                // (a DC flag on a sub-DC-power charge is downgraded to unknown).
                // gun: 2=AC 3=DC 4=AC_DC 5=V2L; AC_DC/V2L/unknown -> -1.
                int isDc = pendingCloseIsDc >= -1
                        ? pendingCloseIsDc : currentChargingTypeVerdict();

                // Range gained derived from energy × the car's efficiency — the
                // elecRangeKm delta was unavailable during parked charging (always
                // blank) and noisy. Same basis as the live in-progress card.
                int rangeGained = rangeGainedFromEnergy(energyAdded);
                // Price at the tariff registered for WHERE this charge happened;
                // absent a match, the global DC/base rate (see priceSession).
                if (pendingClosePricing == null) {
                    pendingClosePricing = priceSessionForClose(
                            isDc, chargingStartLat, chargingStartLng);
                }
                // If the first config read failed, the retry that succeeds must freeze its result
                // before H2 is attempted again. Otherwise another restart/config edit can price the
                // same physical boundary differently.
                if (!persistChargingLifecycleJournal()) {
                    throw new java.io.IOException(
                            "charging close snapshot was not durable");
                }
                PricingDecision pd = pendingClosePricing;
                double rate = pd.rate;
                String curr = pd.currency;
                double cost = pd.costFor(energyAdded);
                // Use the value latched WHILE charging — re-reading now would
                // get ~0 since charging just stopped.
                int ttf = chargingTimeToFullMin;
                double avgPower = resolveAveragePowerKw(
                        chargingStartTime, chargingPowerSum,
                        chargingPowerCount);

                final boolean sohCalibrationCandidate =
                        isFinite(socDelta)
                                && socDelta >= 25.0
                                && isFinite(tAvg)
                                && tAvg >= 15.0
                                && tAvg <= 35.0
                                && energyRes.canCalibrateSoh();
                final SohCalibrationFrame stagedSohFrame =
                        sohCalibrationCandidate
                                ? captureSohCalibrationFrame(getSohEstimator()) : null;
                final boolean stageSohCalibration =
                        sohCalibrationCandidate && stagedSohFrame != null;
                final double stagedSohEnergyKwh =
                        stageSohCalibration
                                ? energyRes.socIndependentKwh : Double.NaN;
                double stagedHighCellV = Double.NaN;
                if (stageSohCalibration) {
                    try {
                        com.overdrive.app.byd.BydDataCollector col =
                                com.overdrive.app.byd.BydDataCollector.getInstance();
                        if (col != null && col.isInitialized()) {
                            com.overdrive.app.byd.BydVehicleData vd = col.getData();
                            if (vd != null && !Double.isNaN(vd.highCellVoltage)) {
                                stagedHighCellV = vd.highCellVoltage;
                            }
                        }
                    } catch (Exception ignored) {
                        // Cell voltage is optional; temperature and SOC quality are not.
                    }
                }
                final double persistedHighCellV = stagedHighCellV;

                String sql = "UPDATE " + TABLE_CHARGING +
                    " SET end_time = ?, end_soc = ?, energy_added_kwh = ?, avg_power_kw = ?, peak_power_kw = ?, " +
                    "range_gained_km = ?, is_dc = ?, electricity_rate = ?, currency = ?, session_cost = ?, " +
                    "time_to_full_min = ?, hv_temp_high = ?, hv_temp_low = ?, hv_temp_avg = ?, " +
                    "tariff_id = ?, tariff_label = ?, " +
                    "energy_source = ?, energy_soc_kwh = ?, energy_incomplete = ?, " +
                    "counter_energy_kwh = ?, counter_last_kwh = ?, counter_source = ?, " +
                    "counter_full_scale_kwh = ?, counter_last_at_ms = ?, " +
                    "counter_observation_generation = ?, counter_wrap_count = ?, " +
                    "counter_reset_count = ?, counter_ceiling_streak = ?, counter_saturated = ?, " +
                    "counter_abandoned_kwh = ?, counter_unattributed_gaps = ?, " +
                    "counter_awaiting_gap = ?, counter_gap_reconstructed = ?, " +
                    "counter_gap_estimate_kwh = ?, counter_recent_rate_kwh_per_h = ?, " +
                    "resume_blocked = ?, " +
                    "post_commit_tariff_applied = ?, post_commit_soh_applied = ?, " +
                    "soh_calibration_energy_kwh = ?, soh_calibration_cell_v = ?, " +
                    "soh_calibration_nominal_identity = ?, " +
                    "soh_calibration_estimator_generation = ?, " +
                    "soh_calibration_reset_model_epoch = ?, " +
                    "soh_calibration_prior_at_ms = ?, soh_calibration_rejected = 0 " +
                    "WHERE start_time = ? AND end_time IS NULL;";

                final double persistedEnergyAdded = energyAdded;
                final double persistedAvgPower = avgPower;
                final double persistedPeakPower = chargingPeakPower;
                final double persistedTHi = tHi;
                final double persistedTLo = tLo;
                final double persistedTAvg = tAvg;
                final long persistedEndTime = now;
                final double persistedEndSoc = soc;
                runInTransaction(() -> {
                    try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                        pstmt.setLong(1, persistedEndTime);
                        pstmt.setDouble(2, persistedEndSoc);
                        pstmt.setDouble(3, persistedEnergyAdded);
                        pstmt.setDouble(4, persistedAvgPower);
                        pstmt.setDouble(5, persistedPeakPower);
                        pstmt.setInt(6, rangeGained);
                        pstmt.setInt(7, isDc);
                        pstmt.setDouble(8, rate);
                        pstmt.setString(9, curr);
                        pstmt.setDouble(10, cost);
                        pstmt.setInt(11, ttf);
                        pstmt.setDouble(12, persistedTHi);
                        pstmt.setDouble(13, persistedTLo);
                        pstmt.setDouble(14, persistedTAvg);
                        pstmt.setString(15, pd.tariffId);
                        pstmt.setString(16, pd.tariffLabel);
                        // Provenance: which estimate won, the independent SOC figure it was checked
                        // against, and whether the total is known to be missing a segment. Recorded so
                        // a support question can be answered without re-deriving anything.
                        pstmt.setString(17, energyRes.source);
                        if (!Double.isNaN(energyRes.socEstimateKwh)) {
                            pstmt.setDouble(18, energyRes.socEstimateKwh);
                        } else {
                            pstmt.setNull(18, java.sql.Types.DOUBLE);
                        }
                        pstmt.setInt(19, energyRes.incomplete ? 1 : 0);
                        if (chargingCounter.hasBaseline()) {
                            pstmt.setDouble(20, chargingCounter.energyKwh());
                            pstmt.setDouble(21, chargingCounter.lastRawKwh());
                            if (counterOwner != null) pstmt.setString(22, counterOwner);
                            else pstmt.setNull(22, java.sql.Types.VARCHAR);
                            pstmt.setDouble(23, chargingCounter.fullScaleKwh());
                        } else {
                            pstmt.setNull(20, java.sql.Types.DOUBLE);
                            pstmt.setNull(21, java.sql.Types.DOUBLE);
                            pstmt.setNull(22, java.sql.Types.VARCHAR);
                            pstmt.setNull(23, java.sql.Types.DOUBLE);
                        }
                        int next = bindCounterState(
                                pstmt, 24, chargingCounter.snapshotState());
                        pstmt.setInt(next++, pendingCloseResumeBlocked ? 1 : 0);
                        pstmt.setInt(next++, pd.tariffId.isEmpty() ? 1 : 0);
                        pstmt.setInt(next++, stageSohCalibration ? 0 : 1);
                        if (stageSohCalibration) {
                            pstmt.setDouble(next++, stagedSohEnergyKwh);
                        } else {
                            pstmt.setNull(next++, java.sql.Types.DOUBLE);
                        }
                        if (!Double.isNaN(persistedHighCellV)) {
                            pstmt.setDouble(next++, persistedHighCellV);
                        } else {
                            pstmt.setNull(next++, java.sql.Types.DOUBLE);
                        }
                        if (stageSohCalibration) {
                            pstmt.setString(next++, stagedSohFrame.nominalIdentity);
                            pstmt.setLong(next++, stagedSohFrame.estimatorGeneration);
                            pstmt.setLong(next++, stagedSohFrame.resetModelEpoch);
                            pstmt.setLong(next++, stagedSohFrame.priorCalibrationAtMs);
                        } else {
                            pstmt.setNull(next++, java.sql.Types.VARCHAR);
                            pstmt.setNull(next++, java.sql.Types.BIGINT);
                            pstmt.setNull(next++, java.sql.Types.BIGINT);
                            pstmt.setNull(next++, java.sql.Types.BIGINT);
                        }
                        pstmt.setLong(next, chargingStartTime);
                        if (pstmt.executeUpdate() != 1) {
                            throw new java.sql.SQLException(
                                    "session close found no matching open session");
                        }
                    }

                    // A closed row and its permanent daily contribution are one accounting unit.
                    foldSessionIntoDaily(persistedEndTime, persistedEnergyAdded, cost, isDc, persistedPeakPower,
                            rangeGained, energyRes.incomplete);
                });
                replayPendingChargingPostCommitMetadata();

                logger.info("Charging session ended at " + soc + "% (+" +
                    String.format("%.1f", socDelta) + "%, ~" +
                    String.format("%.1f", energyAdded) + " kWh, peak " +
                    String.format("%.1f", chargingPeakPower) + " kW, " +
                    (isDc == 1 ? "DC" : isDc == 0 ? "AC" : "?") + ", " +
                    String.format("%.0f", packTemp) + "°C)");

                // Publish the committed close to memory only after both the row and daily rollup are
                // durable. Retain the final counter solely as the next session's stale-value fence.
                resetLiveChargingState(true);
            }

            wasCharging = isCharging;
            if (!wasCharging) chargingLifecycleHold = false;
            if (lifecycleTransition && isCharging) {
                chargingLifecycleJournalDirty = true;
            }
            return !chargingLifecycleJournalDirty
                    || persistChargingLifecycleJournal();

        } catch (Exception e) {
            final boolean closeAttempt = !isCharging && wasCharging && chargingStartTime > 0;
            final long attemptedStart = chargingStartTime;
            if (startAttempt != null && reconcileDurableSessionStart(startAttempt)) {
                logger.warn("Charging start for session " + startAttempt.sessionStart
                        + " threw after persistence committed; reconciled the exact open row"
                        + " and its continuation claim");
                noteWriteOk();
                return persistChargingLifecycleJournal();
            }
            if (closeAttempt && isSessionDurablyClosed(attemptedStart)) {
                logger.warn("Charging close for session " + attemptedStart
                        + " threw after persistence committed; reconciled exact closed row");
                replayPendingChargingPostCommitMetadata();
                resetLiveChargingState(true);
                noteWriteOk();
                return persistChargingLifecycleJournal();
            }
            logger.error("Failed to track charging session", e);
            if (isSqlFailure(e)) {
                noteWriteFailed();
                try { reconnect(); } catch (Exception ignored) {}
            }
            // A failed connection can prevent the first reconciliation query even when COMMIT was
            // durable. Retry once after the normal reconnect path before exposing failure to the
            // manager; otherwise every retry updates zero rows and the in-memory session stays open.
            if (closeAttempt && isSessionDurablyClosed(attemptedStart)) {
                logger.warn("Charging close for session " + attemptedStart
                        + " reconciled as durable after reconnect");
                replayPendingChargingPostCommitMetadata();
                resetLiveChargingState(true);
                noteWriteOk();
                return persistChargingLifecycleJournal();
            }
            if (startAttempt != null && reconcileDurableSessionStart(startAttempt)) {
                logger.warn("Charging start for session " + startAttempt.sessionStart
                        + " reconciled as durable after reconnect, including its continuation claim");
                noteWriteOk();
                return persistChargingLifecycleJournal();
            }
            if (startAttempt != null || (isCharging && !wasCharging)) {
                // No start row committed. Keep the pre-session counter buffer for retry, but clear
                // transient offer ownership; the database claim rolled back with the INSERT.
                continuationSource = null;
                pendingSweepMarkerRow = -1L;
                clearClaimedContinuationOffer();
            }
            return false;
        }
    }

    /** Exact-row reconciliation for the uncertain-result side of JDBC commit. */
    private boolean isSessionDurablyClosed(long sessionStart) {
        Connection c = connection;
        if (c == null || sessionStart <= 0) return false;
        try (PreparedStatement p = c.prepareStatement(
                "SELECT end_time FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
            p.setLong(1, sessionStart);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return false;
                long end = rs.getLong(1);
                return !rs.wasNull() && end > 0;
            }
        } catch (Exception reconcileFailure) {
            logger.debug("Could not reconcile charging close for session " + sessionStart
                    + ": " + reconcileFailure.getMessage());
            return false;
        }
    }

    /**
     * Reconcile the uncertain-result side of a session-start commit. The new row and the prior row's
     * continuation claim are one transaction, so both must be durable before memory adopts the start.
     */
    private boolean reconcileDurableSessionStart(SessionStartAttempt attempt) {
        Connection c = connection;
        if (c == null || attempt == null || attempt.sessionStart <= 0) return false;
        try {
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT end_time FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
                p.setLong(1, attempt.sessionStart);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) return false;
                    rs.getLong(1);
                    if (!rs.wasNull()) return false;
                }
            }
            ContinuationOffer offer = attempt.continuationOffer;
            if (offer != null) {
                try (PreparedStatement p = c.prepareStatement(
                        "SELECT continuation_claimed FROM " + TABLE_CHARGING
                                + " WHERE start_time = ?;")) {
                    p.setLong(1, offer.rowStart);
                    try (ResultSet rs = p.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) != 1) return false;
                    }
                }
            }

            chargingStartTime = attempt.sessionStart;
            activateClaimedContinuationOffer(offer, attempt.sessionStart,
                    attempt.continuationAlreadyResolved);
            preSessionCounterLowKwh = Double.NaN;
            preSessionCounterAtMs = 0L;
            preSessionCounterSource = null;
            clearPreSessionProvisionalExternal();
            counterProgressDirty = false;
            wasCharging = true;
            if (attempt.deferredPhysicalStart) {
                consumeDeferredPhysicalSessionAfterStart(attempt.sessionStart);
            }
            resolvePlaceLabelAsync(chargingStartTime, chargingStartLat, chargingStartLng);
            return true;
        } catch (Exception reconcileFailure) {
            logger.debug("Could not reconcile charging start for session " + attempt.sessionStart
                    + ": " + reconcileFailure.getMessage());
            return false;
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
                // Upper bound EXCLUSIVE. The caller passes the next session's start_time, and a
                // heartbeat is written AT that instant — an inclusive bound handed the newer charge's
                // first heartbeat to the older row as its final activity.
                " WHERE is_charging = 1 AND timestamp >= ? AND timestamp < ?;")) {
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
     * SoC% from the LAST charging heartbeat in the window, or NaN.
     *
     * <p>Companion to {@link #maxChargingHeartbeat}, which already uses these rows as the activity
     * signal on models that produce no power samples. Their SoC was being ignored, so a stale session
     * with no power samples closed with {@code end_soc = start_soc} — recording zero SoC gain for a
     * charge that demonstrably delivered, and denying the energy resolver its SOC-derived estimate.
     */
    private double lastChargingHeartbeatSoc(long startInclusive, long upperInclusive) {
        if (!isInitialized || connection == null) return Double.NaN;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT soc_percent FROM " + TABLE_SOC +
                // Exclusive upper bound, same reason as maxChargingHeartbeat.
                " WHERE is_charging = 1 AND timestamp >= ? AND timestamp < ?" +
                " ORDER BY timestamp DESC LIMIT 1;")) {
            ps.setLong(1, startInclusive);
            ps.setLong(2, upperInclusive);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double v = rs.getDouble(1);
                    if (!rs.wasNull() && v >= 0) return v;
                }
            }
        } catch (Exception e) {
            logger.debug("lastChargingHeartbeatSoc failed: " + e.getMessage());
        }
        return Double.NaN;
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
    private static final class ResumeState {
        double peak;
        double powerSum;
        int powerCount;
        int gun = -1;
        int typeVerdict = ChargingTypeClassifier.UNKNOWN;
        int startRange = -1;
        int startOdometer = -1;
        double lat;
        double lng;
    }

    private static final class ResumeCommitAttempt {
        final long canonicalStart;
        final long boundaryAt;
        final java.util.List<Long> mergedStarts;
        final double canonicalStartSoc;
        final CounterRestoreState counterState;
        final ResumeState state;
        final int mergedMembers;
        final double currentSoc;

        ResumeCommitAttempt(long canonicalStart, long boundaryAt,
                            java.util.List<Long> mergedStarts, double canonicalStartSoc,
                            CounterRestoreState counterState, ResumeState state,
                            int mergedMembers, double currentSoc) {
            this.canonicalStart = canonicalStart;
            this.boundaryAt = boundaryAt;
            this.mergedStarts = mergedStarts;
            this.canonicalStartSoc = canonicalStartSoc;
            this.counterState = counterState;
            this.state = state;
            this.mergedMembers = mergedMembers;
            this.currentSoc = currentSoc;
        }
    }

    private static final class CounterRestoreState {
        double baseline = Double.NaN;
        double last = Double.NaN;
        double energy = Double.NaN;
        double fullScale = Double.NaN;
        boolean incomplete;
        String source;
        com.overdrive.app.charging.ChargeCounterAccumulator.State exactState;
    }

    private static final class ContinuationOffer {
        final long rowStart;
        final double endpointKwh;
        final String source;
        final double startSoc;
        final double fullScaleKwh;

        ContinuationOffer(long rowStart, double endpointKwh, String source,
                          double startSoc, double fullScaleKwh) {
            this.rowStart = rowStart;
            this.endpointKwh = endpointKwh;
            this.source = source;
            this.startSoc = startSoc;
            this.fullScaleKwh = fullScaleKwh;
        }
    }

    private static final class SessionStartAttempt {
        final long sessionStart;
        final ContinuationOffer continuationOffer;
        final boolean continuationAlreadyResolved;
        final boolean deferredPhysicalStart;

        SessionStartAttempt(long sessionStart, ContinuationOffer continuationOffer,
                            boolean continuationAlreadyResolved, boolean deferredPhysicalStart) {
            this.sessionStart = sessionStart;
            this.continuationOffer = continuationOffer;
            this.continuationAlreadyResolved = continuationAlreadyResolved;
            this.deferredPhysicalStart = deferredPhysicalStart;
        }
    }

    private boolean tryResumeChargingSession(long now, double soc) {
        if (!isInitialized || connection == null) return false;
        ResumeCommitAttempt resumeAttempt = null;
        try {
            java.util.List<long[]> rows = new java.util.ArrayList<>(); // [start, end, activity]
            java.util.List<Double> startSocs = new java.util.ArrayList<>();
            java.util.List<Double> endSocs = new java.util.ArrayList<>();
            java.util.List<Boolean> resumeBlocked = new java.util.ArrayList<>();
            java.util.List<Boolean> integrationTruncated = new java.util.ArrayList<>();
            java.util.List<long[]> raw = new java.util.ArrayList<>();  // [start, end, last sample]
            long sinceTs = now - 24L * 60 * 60 * 1000L;
            try (PreparedStatement sel = connection.prepareStatement(
                    "SELECT c.start_time, c.end_time, c.start_soc, c.end_soc, "
                    + "c.resume_blocked, c.integration_truncated, "
                    + "(SELECT MAX(t) FROM " + TABLE_CPS
                    + " s WHERE s.session_start_time = c.start_time) AS last_t "
                    + "FROM " + TABLE_CHARGING
                    + " c WHERE c.start_time >= ? ORDER BY c.start_time DESC;")) {
                sel.setLong(1, sinceTs);
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) {
                        long st = rs.getLong("start_time");
                        long en = rs.getLong("end_time");
                        long lt = rs.getLong("last_t");
                        boolean ltNull = rs.wasNull();
                        double endSoc = rs.getDouble("end_soc");
                        if (rs.wasNull()) endSoc = Double.NaN;
                        raw.add(new long[]{st, en, (!ltNull && lt > 0) ? lt : 0L});
                        startSocs.add(rs.getDouble("start_soc"));
                        endSocs.add(endSoc);
                        resumeBlocked.add(rs.getInt("resume_blocked") == 1);
                        integrationTruncated.add(rs.getInt("integration_truncated") == 1);
                    }
                }
            }
            if (raw.isEmpty()) return false;
            // The newest row is an explicit lifecycle barrier. Looking past it would merely resume
            // an older row into the same opted-out interval the durable marker is meant to exclude.
            if (resumeBlocked.get(0)) return false;

            for (int i = 0; i < raw.size(); i++) {
                long st = raw.get(i)[0];
                long en = raw.get(i)[1];
                long upper = (i == 0) ? now - 1 : raw.get(i - 1)[0] - 1;
                long heartbeat = maxChargingHeartbeat(st, upper);
                long activity = Math.max(Math.max(raw.get(i)[2], heartbeat), Math.max(en, st));
                rows.add(new long[]{st, en, activity});
            }
            if (now - rows.get(0)[2] > CHARGING_MERGE_GAP_MS
                    || now - rows.get(0)[2] < 0) return false;
            double newestEndSoc = endSocs.get(0);
            if (!Double.isNaN(newestEndSoc) && soc + 1.0 < newestEndSoc) return false;

            int chainEnd = 0;
            for (int i = 1; i < rows.size(); i++) {
                if (resumeBlocked.get(i)) break;
                long gap = rows.get(i - 1)[0] - rows.get(i)[2];
                if (gap < 0 || gap > CHARGING_MERGE_GAP_MS) break;
                chainEnd = i;
            }
            boolean chainHasTruncatedIntegration = false;
            for (int i = 0; i <= chainEnd; i++) {
                chainHasTruncatedIntegration |= integrationTruncated.get(i);
            }
            final int mergedMembers = chainEnd;
            // Any restart gap is observationally incomplete even when it is shorter than the old
            // ten-minute integration cap: charging may have stopped and restarted while the daemon
            // was absent. A boundary prevents pricing that idle interval as delivered energy.
            final boolean resumedIntegrationTruncated = true;
            final long canonStart = rows.get(chainEnd)[0];
            final double canonStartSoc = startSocs.get(chainEnd);
            if (soc + 1.0 < canonStartSoc) return false;

            // Read counter state before any mutation. A failed restoration query must decline resume
            // while every row and daily bucket is still untouched.
            final CounterRestoreState counterState = readCounterRestoreState(canonStart);
            final ResumeState state = new ResumeState();
            final java.util.Set<Long> affectedDays = new java.util.LinkedHashSet<>();
            final long resumeBoundaryAt = Math.max(canonStart + 1L, now - 1L);
            final java.util.List<Long> mergedStarts = new java.util.ArrayList<>();
            for (int i = 0; i < mergedMembers; i++) {
                long memberStart = rows.get(i)[0];
                if (memberStart != canonStart) mergedStarts.add(memberStart);
            }
            resumeAttempt = new ResumeCommitAttempt(
                    canonStart, resumeBoundaryAt, mergedStarts, canonStartSoc,
                    counterState, state, mergedMembers, soc);

            runInTransaction(() -> {
                for (int i = 0; i < mergedMembers; i++) {
                    long memberStart = rows.get(i)[0];
                    long memberEnd = rows.get(i)[1];
                    if (memberStart == canonStart) continue;
                    if (memberEnd > 0) affectedDays.add(dayEpoch(memberEnd));

                    try (PreparedStatement rk = connection.prepareStatement(
                            "UPDATE " + TABLE_CPS
                            + " SET session_start_time = ? WHERE session_start_time = ?;")) {
                        rk.setLong(1, canonStart);
                        rk.setLong(2, memberStart);
                        rk.executeUpdate();
                    }
                    try (PreparedStatement ins = connection.prepareStatement(
                            "INSERT INTO " + TABLE_CPS
                            + " (session_start_time, t, power_kw, soc, temp, temp_high, temp_low)"
                            + " VALUES (?, ?, -1, 0, -999, -999, -999);")) {
                        ins.setLong(1, canonStart);
                        ins.setLong(2, memberStart - 1);
                        ins.executeUpdate();
                    }
                    try (PreparedStatement del = connection.prepareStatement(
                            "DELETE FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
                        del.setLong(1, memberStart);
                        if (del.executeUpdate() != 1) {
                            throw new java.sql.SQLException(
                                    "resume member disappeared before consolidation: " + memberStart);
                        }
                    }
                }
                try (PreparedStatement boundary = connection.prepareStatement(
                        "INSERT INTO " + TABLE_CPS
                                + " (session_start_time, t, power_kw, soc, temp, temp_high, temp_low)"
                                + " VALUES (?, ?, -1, 0, -999, -999, -999);")) {
                    boundary.setLong(1, canonStart);
                    boundary.setLong(2, resumeBoundaryAt);
                    boundary.executeUpdate();
                }

                try (PreparedStatement r = connection.prepareStatement(
                        "SELECT end_time, peak_power_kw, gun_state, is_dc, start_range_km,"
                        + " start_odometer_km, start_lat, start_lng FROM " + TABLE_CHARGING
                        + " WHERE start_time = ?;")) {
                    r.setLong(1, canonStart);
                    try (ResultSet rs = r.executeQuery()) {
                        if (!rs.next()) {
                            throw new java.sql.SQLException("resume canonical row missing");
                        }
                        long canonEnd = rs.getLong("end_time");
                        if (!rs.wasNull() && canonEnd > 0) affectedDays.add(dayEpoch(canonEnd));
                        double peak = rs.getDouble("peak_power_kw");
                        state.peak = !rs.wasNull()
                                && isValidMeasuredChargingPower(peak) ? peak : 0;
                        state.gun = rs.getInt("gun_state");
                        state.typeVerdict = rs.getInt("is_dc");
                        if (state.typeVerdict != ChargingTypeClassifier.AC
                                && state.typeVerdict != ChargingTypeClassifier.DC) {
                            state.typeVerdict = ChargingTypeClassifier.classify(
                                    state.gun, state.peak);
                        }
                        state.startRange = rs.getInt("start_range_km");
                        state.startOdometer = rs.getInt("start_odometer_km");
                        state.lat = rs.getDouble("start_lat");
                        state.lng = rs.getDouble("start_lng");
                    }
                }
                try (PreparedStatement upd = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING + " SET end_time = NULL, end_soc = NULL,"
                        + " energy_added_kwh = NULL, session_cost = -1, range_gained_km = -1,"
                        + " closed_by_sweep = 0, continuation_claimed = 0,"
                        + " integration_truncated = ?"
                        + " WHERE start_time = ?;")) {
                    upd.setInt(1, resumedIntegrationTruncated ? 1 : 0);
                    upd.setLong(2, canonStart);
                    if (upd.executeUpdate() != 1) {
                        throw new java.sql.SQLException("resume canonical reopen updated no row");
                    }
                }

                try (PreparedStatement sp = connection.prepareStatement(
                        "SELECT power_kw FROM " + TABLE_CPS
                        + " WHERE session_start_time = ?"
                        + " AND power_kw > 0 AND power_kw <= 500;")) {
                    sp.setLong(1, canonStart);
                    try (ResultSet rs = sp.executeQuery()) {
                        while (rs.next()) {
                            double p = rs.getDouble(1);
                            if (!isValidMeasuredChargingPower(p)) continue;
                            if (p > state.peak) state.peak = p;
                            state.powerSum += p;
                            state.powerCount++;
                        }
                    }
                }
                if (state.typeVerdict != ChargingTypeClassifier.AC
                        && state.typeVerdict != ChargingTypeClassifier.DC) {
                    state.typeVerdict = ChargingTypeClassifier.classify(
                            state.gun, state.peak);
                }
                for (long day : affectedDays) rebuildChargingDailyDay(day);
            });

            publishResumedSession(resumeAttempt);
            return true;
        } catch (Exception e) {
            if (resumeAttempt != null && reconcileDurableResume(resumeAttempt)) {
                logger.warn("Resume consolidation threw after its transaction committed; reconciled"
                        + " canonical row " + resumeAttempt.canonicalStart);
                publishResumedSession(resumeAttempt);
                noteWriteOk();
                return true;
            }
            if (isSqlFailure(e)) {
                noteWriteFailed();
                try { reconnect(); } catch (Exception ignored) {}
            }
            if (resumeAttempt != null && reconcileDurableResume(resumeAttempt)) {
                logger.warn("Resume consolidation reconciled after reconnect for canonical row "
                        + resumeAttempt.canonicalStart);
                publishResumedSession(resumeAttempt);
                noteWriteOk();
                return true;
            }
            logger.debug("tryResumeChargingSession failed without mutation: " + e.getMessage());
            return false;
        }
    }

    private boolean reconcileDurableResume(ResumeCommitAttempt attempt) {
        Connection c = connection;
        if (c == null || attempt == null) return false;
        try {
            try (PreparedStatement canonical = c.prepareStatement(
                    "SELECT end_time, integration_truncated FROM " + TABLE_CHARGING
                            + " WHERE start_time = ?;")) {
                canonical.setLong(1, attempt.canonicalStart);
                try (ResultSet rs = canonical.executeQuery()) {
                    if (!rs.next()) return false;
                    rs.getLong(1);
                    if (!rs.wasNull() || rs.getInt(2) != 1) return false;
                }
            }
            for (long mergedStart : attempt.mergedStarts) {
                try (PreparedStatement member = c.prepareStatement(
                        "SELECT 1 FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
                    member.setLong(1, mergedStart);
                    try (ResultSet rs = member.executeQuery()) {
                        if (rs.next()) return false;
                    }
                }
            }
            try (PreparedStatement boundary = c.prepareStatement(
                    "SELECT 1 FROM " + TABLE_CPS
                            + " WHERE session_start_time = ? AND t = ? AND power_kw <= 0;")) {
                boundary.setLong(1, attempt.canonicalStart);
                boundary.setLong(2, attempt.boundaryAt);
                try (ResultSet rs = boundary.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (Exception reconcileFailure) {
            logger.debug("Could not reconcile charging resume for "
                    + attempt.canonicalStart + ": " + reconcileFailure.getMessage());
            return false;
        }
    }

    private void publishResumedSession(ResumeCommitAttempt attempt) {
        ResumeState state = attempt.state;
        chargingStartTime = attempt.canonicalStart;
        chargingStartSoc = attempt.canonicalStartSoc;
        chargingGunState = state.gun;
        chargingTypeVerdict = state.typeVerdict;
        chargingStartRange = state.startRange;
        chargingStartOdometer = state.startOdometer;
        chargingStartLat = state.lat;
        chargingStartLng = state.lng;
        chargingTimeToFullMin = snapshotTimeToFullMin();
        chargingPeakPower = state.peak;
        chargingPowerSum = state.powerSum;
        chargingPowerCount = state.powerCount;
        clearClaimedContinuationOffer();
        applyCounterRestoreState(attempt.counterState);

        // Resume is already committed. Optional metadata backfill must not report "not resumed"
        // to the caller, which would make it insert a second open row.
        try {
            if (chargingStartLat == 0 && chargingStartLng == 0) {
                double[] loc = snapshotLocation();
                if (loc[0] != 0 || loc[1] != 0) {
                    chargingStartLat = loc[0];
                    chargingStartLng = loc[1];
                    try (PreparedStatement up = connection.prepareStatement(
                            "UPDATE " + TABLE_CHARGING
                                    + " SET start_lat = ?, start_lng = ? WHERE start_time = ?;")) {
                        up.setDouble(1, chargingStartLat);
                        up.setDouble(2, chargingStartLng);
                        up.setLong(3, chargingStartTime);
                        up.executeUpdate();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Resumed session location backfill failed: " + e.getMessage());
        }
        try {
            resolvePlaceLabelAsync(chargingStartTime, chargingStartLat, chargingStartLng);
        } catch (Exception e) {
            logger.debug("Resumed session place-label scheduling failed: " + e.getMessage());
        }
        logger.info("Resumed+consolidated charging session start=" + chargingStartTime
                + " (merged " + attempt.mergedMembers + " orphan row(s), soc="
                + attempt.currentSoc + "%, samples=" + state.powerCount + ")");
    }

    /**
     * Close any STALE open session left behind when a charge ended while the
     * daemon was down/restarting (the SESSION END tick never fired, so end_time
     * stayed NULL and the row shows blank energy/cost/range with only start
     * values). Called at init when NOT currently charging. Reconstructs the end
     * values from the recorded {@code charging_power_samples}: energy by ∫P·dt,
     * end_soc/temp/peak/avg from the last + max samples, then folds into the
     * daily rollup. Skips a row whose last activity is inside the resume window
     * ({@link #CHARGING_MERGE_GAP_MS}) — that is a charge the resume path is about
     * to re-adopt, and closing it here would split one physical charge in two.
     */
    /**
     * Open (or resume) the charging session row IMMEDIATELY on the fused charging edge, instead of
     * waiting for the next 2-minute SoC tick.
     *
     * <p>Session bookkeeping runs on the {@link #SAMPLE_INTERVAL_MS} SoC tick, but the fused detector
     * fires the moment charging is detected. Between the two, {@code getOpenChargingSessionStart()}
     * returns -1, and the fast sampler discards every tick it takes in that window — so the first up
     * to two minutes of the power curve was always lost, the peak could be missed entirely (DC power
     * is highest early), and any charge shorter than one tick recorded nothing at all.
     *
     * <p>This delegates to the same {@link #trackChargingSession} used by the tick, so the resume
     * path, the counter baseline and the opt-in gate all behave identically — it only changes WHEN
     * the row is created. Idempotent: a second call while a session is open is a no-op, because
     * {@code trackChargingSession} keys the SESSION START branch on {@code !wasCharging}.
     *
     * <p>{@code synchronized} on the same monitor as the SoC tick so an edge arriving mid-tick cannot
     * interleave with it.
     *
     * @param isCharging the fused verdict that just became true
     */
    public synchronized boolean capturePendingChargingClose() {
        return capturePendingChargingClose(false);
    }

    public synchronized boolean capturePendingChargingClose(boolean resumeBlocked) {
        if (!reconcilePendingActiveChargingReplacement()) return false;
        if (!wasCharging || chargingStartTime <= 0) {
            return !chargingLifecycleJournalDirty || persistChargingLifecycleJournal();
        }
        if (pendingCloseSessionStart == chargingStartTime) {
            if (resumeBlocked && !pendingCloseResumeBlocked) {
                pendingCloseResumeBlocked = true;
                return persistChargingLifecycleJournal();
            }
            return !chargingLifecycleJournalDirty || persistChargingLifecycleJournal();
        }
        pendingCloseSessionStart = chargingStartTime;
        pendingCloseAtMs = strictlyAfterChargingStart(
                chargingStartTime, System.currentTimeMillis());
        pendingCloseSoc = lastRecordedSoc >= 0 ? lastRecordedSoc : chargingStartSoc;
        try {
            BatterySocData sd = VehicleDataMonitor.getInstance().getBatterySoc();
            if (sd != null && sd.socPercent >= 0 && sd.socPercent <= 100) {
                pendingCloseSoc = sd.socPercent;
            }
        } catch (Throwable ignored) {}
        double counterAtBoundary = snapshotChargeCounterKwh();
        if (!Double.isNaN(counterAtBoundary)) {
            observeFinalCounterForClose(counterAtBoundary, pendingCloseSoc, pendingCloseAtMs);
        }
        // Later admitted callbacks may still advance the same accumulator during the bounded drain.
        // The close path itself must not take another live snapshot after a new physical charge starts.
        pendingCloseCounterCaptured = true;
        chargingPeakPower = resolvePeakKw(chargingStartTime, chargingPeakPower);
        pendingCloseIsDc = currentChargingTypeVerdict();
        try {
            pendingClosePricing = priceSessionForClose(
                    pendingCloseIsDc, chargingStartLat, chargingStartLng);
        } catch (Exception unavailable) {
            // Boundary/counter remain frozen in the lifecycle journal. The close path retries the
            // config read and must not turn this transient failure into a permanent fallback price.
            pendingClosePricing = null;
            logger.warn("Charging close pricing unavailable at boundary: "
                    + unavailable.getMessage());
        }
        pendingCloseResumeBlocked = resumeBlocked;
        try {
            BatteryThermalData thermal =
                    VehicleDataMonitor.getInstance().getBatteryThermal();
            if (thermal != null && thermal.hasData()) {
                if (!Double.isNaN(thermal.highestTempC)) {
                    pendingCloseTempHigh = thermal.highestTempC;
                }
                if (!Double.isNaN(thermal.lowestTempC)) {
                    pendingCloseTempLow = thermal.lowestTempC;
                }
                if (!Double.isNaN(thermal.averageTempC)) {
                    pendingCloseTempAvg = thermal.averageTempC;
                }
            }
        } catch (Throwable ignored) {}
        return persistChargingLifecycleJournal();
    }

    private long allocateMonotonicChargingStart(long proposed) {
        boolean databaseAvailable = connection != null;
        long allocated = nextMonotonicChargingStart(proposed, chargingStartIdentityFloor());
        boolean databaseDurable =
                databaseAvailable && reserveChargingStartIdentity(allocated);
        if (databaseAvailable && !databaseDurable) {
            throw new IllegalStateException(
                    "charging identity allocator did not reserve " + allocated);
        }
        lastAllocatedChargingStartMs = allocated;
        boolean journalDurable = persistChargingLifecycleJournal();
        if (!databaseDurable && !journalDurable) {
            throw new IllegalStateException(
                    "charging identity could not be made restart-safe");
        }
        return allocated;
    }

    /**
     * Normalize a wall-clock boundary without allowing an RTC rollback to produce an open-looking
     * closed row. Charging identities never allocate {@link Long#MAX_VALUE}, so saturation here is
     * a corrupt-state error rather than a representable close timestamp.
     */
    static long strictlyAfterChargingStart(long sessionStart, long proposed) {
        if (sessionStart <= 0L) return Math.max(1L, proposed);
        if (sessionStart == Long.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "charging start cannot be normalized past Long.MAX_VALUE");
        }
        return proposed > sessionStart ? proposed : sessionStart + 1L;
    }

    private long chargingStartIdentityFloor() {
        long floor = Math.max(chargingStartTime, lastAllocatedChargingStartMs);
        for (DeferredChargingGeneration generation : deferredPhysicalGenerations) {
            floor = Math.max(floor, generation.startMs);
        }
        if (connection != null) {
            try (PreparedStatement p = connection.prepareStatement(
                    "SELECT last_start FROM " + TABLE_CHARGING_IDENTITY
                            + " WHERE allocator_key = 1;");
                 ResultSet rs = p.executeQuery()) {
                if (!rs.next()) {
                    throw new java.sql.SQLException(
                            "charging identity allocator row missing");
                }
                floor = Math.max(floor, rs.getLong(1));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Could not read durable charging identity allocator", e);
            }
            try (PreparedStatement p = connection.prepareStatement(
                    "SELECT MAX(start_time) FROM " + TABLE_CHARGING + ";");
                 ResultSet rs = p.executeQuery()) {
                if (rs.next()) {
                    long durableFloor = rs.getLong(1);
                    if (!rs.wasNull()) floor = Math.max(floor, durableFloor);
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Could not read charging identity floor", e);
            }
        }
        return floor;
    }

    /**
     * Idempotently advance the database allocator. A zero-row UPDATE can mean a prior uncertain
     * attempt already committed, so the durable value is always read back before reporting failure.
     */
    private boolean reserveChargingStartIdentity(long allocated) {
        if (connection == null || allocated <= 0L) return false;
        try {
            try (PreparedStatement p = connection.prepareStatement(
                    "UPDATE " + TABLE_CHARGING_IDENTITY
                            + " SET last_start = ? WHERE allocator_key = 1"
                            + " AND last_start < ?;")) {
                p.setLong(1, allocated);
                p.setLong(2, allocated);
                if (p.executeUpdate() == 1) return true;
            }
        } catch (Exception updateFailure) {
            logger.debug("Charging identity reservation needs reconciliation: "
                    + updateFailure.getMessage());
        }
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT last_start FROM " + TABLE_CHARGING_IDENTITY
                        + " WHERE allocator_key = 1;");
             ResultSet rs = p.executeQuery()) {
            return rs.next() && rs.getLong(1) >= allocated;
        } catch (Exception reconcileFailure) {
            logger.warn("Could not reconcile charging identity reservation: "
                    + reconcileFailure.getMessage());
            return false;
        }
    }

    private boolean reserveDeferredChargingIdentities() {
        if (deferredPhysicalGenerations.isEmpty()) return true;
        if (connection == null) return false;
        for (DeferredChargingGeneration generation : deferredPhysicalGenerations) {
            if (!reserveChargingStartIdentity(generation.startMs)) return false;
        }
        return true;
    }

    static long nextMonotonicChargingStart(long proposed, long durableFloor) {
        if (durableFloor == Long.MAX_VALUE) {
            throw new IllegalStateException("charging start identity exhausted");
        }
        return Math.max(Math.max(1L, proposed), durableFloor + 1L);
    }

    private DeferredChargingGeneration currentDeferredPhysicalGeneration() {
        return deferredPhysicalGenerations.peekLast();
    }

    public synchronized boolean hasDeferredPhysicalGenerations() {
        return !deferredPhysicalGenerations.isEmpty();
    }

    /** True when startup must reconcile journal-restored lifecycle work even with no ON/OFF edge. */
    public synchronized boolean hasPendingChargingLifecycle() {
        return pendingActiveReplacement != null
                || pendingChargingMaintenanceIntent != null
                || wasCharging || optOutClosePending || pendingCloseSessionStart > 0L
                || !deferredPhysicalGenerations.isEmpty();
    }

    /** True when the restored active row already owns a frozen physical-stop boundary. */
    public synchronized boolean hasPendingChargingCloseBoundary() {
        return wasCharging && chargingStartTime > 0L
                && (pendingCloseSessionStart == chargingStartTime || optOutClosePending);
    }

    /**
     * Persist a normal session start as a deferred generation before its H2 INSERT. The same image is
     * usable on both sides of a crash: an absent row is materialized later, while an already-open row is
     * adopted and the redundant generation is consumed during journal/database reconciliation.
     */
    private DeferredChargingGeneration journalCurrentChargingStart(
            ContinuationOffer continuationOffer) throws Exception {
        DeferredChargingGeneration generation = new DeferredChargingGeneration();
        generation.startMs = chargingStartTime;
        generation.startSoc = chargingStartSoc;
        generation.startRange = chargingStartRange;
        generation.startOdometer = chargingStartOdometer;
        generation.gun = chargingGunState;
        generation.typeVerdict = currentChargingTypeVerdict();
        generation.timeToFull = chargingTimeToFullMin;
        generation.lat = chargingStartLat;
        generation.lng = chargingStartLng;
        generation.peakPower = chargingPeakPower;
        generation.powerSum = chargingPowerSum;
        generation.powerCount = chargingPowerCount;
        generation.counter.restoreState(chargingCounter.snapshotState());
        generation.counterOwner = counterOwner;
        generation.previousCounterKwh = lastSessionCounterKwh;
        generation.counterBaselinePending = counterBaselinePending;
        generation.counterBaselinePendingSinceMs = counterBaselinePendingSinceMs;
        generation.counterCandidateKwh = counterBaselineCandidateKwh;
        generation.counterCandidateAtMs = counterBaselineCandidateAtMs;
        generation.counterLatestKwh = counterBaselineLatestKwh;
        generation.counterLatestAtMs = counterBaselineLatestAtMs;
        generation.provisionalExternalKwh = provisionalExternalKwh;
        generation.provisionalExternalAtMs = provisionalExternalAtMs;
        generation.provisionalExternalUnitDivisor = provisionalExternalUnitDivisor;
        generation.continuationOffer = continuationOffer;

        double consumedPreSessionCounter = preSessionCounterLowKwh;
        long consumedPreSessionCounterAt = preSessionCounterAtMs;
        String consumedPreSessionCounterSource = preSessionCounterSource;
        double consumedPreSessionProvisionalRaw = preSessionProvisionalExternalRaw;
        long consumedPreSessionProvisionalAt = preSessionProvisionalExternalAtMs;
        double consumedPreSessionProvisionalDivisor =
                preSessionProvisionalExternalUnitDivisor;
        if (Double.isFinite(consumedPreSessionProvisionalRaw)
                && consumedPreSessionProvisionalAt > 0L
                && generation.startMs - consumedPreSessionProvisionalAt >= 0L
                && generation.startMs - consumedPreSessionProvisionalAt
                        <= PRE_SESSION_COUNTER_MAX_AGE_MS) {
            double divisor =
                    validCounterUnitDivisor(consumedPreSessionProvisionalDivisor);
            double candidate = consumedPreSessionProvisionalRaw / divisor;
            if (Double.isNaN(generation.provisionalExternalKwh)
                    || consumedPreSessionProvisionalRaw
                            < counterValueInRawFrame(
                                    generation.provisionalExternalKwh,
                                    generation.provisionalExternalUnitDivisor)) {
                generation.provisionalExternalKwh = candidate;
                generation.provisionalExternalAtMs =
                        consumedPreSessionProvisionalAt;
                generation.provisionalExternalUnitDivisor = divisor;
            }
        }
        // The write-ahead generation is now the sole owner. Publish the generation and removal of
        // standalone evidence in one journal image so a crash cannot replay either item twice.
        preSessionCounterLowKwh = Double.NaN;
        preSessionCounterAtMs = 0L;
        preSessionCounterSource = null;
        clearPreSessionProvisionalExternal();
        deferredPhysicalGenerations.addLast(generation);
        sessionInputsFenced = true;
        lastAllocatedChargingStartMs =
                Math.max(lastAllocatedChargingStartMs, generation.startMs);
        if (!persistChargingLifecycleJournal()) {
            deferredPhysicalGenerations.removeLastOccurrence(generation);
            sessionInputsFenced = !deferredPhysicalGenerations.isEmpty();
            preSessionCounterLowKwh = consumedPreSessionCounter;
            preSessionCounterAtMs = consumedPreSessionCounterAt;
            preSessionCounterSource = consumedPreSessionCounterSource;
            preSessionProvisionalExternalRaw =
                    consumedPreSessionProvisionalRaw;
            preSessionProvisionalExternalAtMs =
                    consumedPreSessionProvisionalAt;
            preSessionProvisionalExternalUnitDivisor =
                    consumedPreSessionProvisionalDivisor;
            throw new java.io.IOException(
                    "charging start write-ahead journal was not durable");
        }
        return generation;
    }

    private void adoptConfirmedPreSessionCounterAsActiveBaseline(
            double valueKwh, long observedAtMs, String source) {
        if (counterOwner == null) bindCounterOwner(source);
        if (!source.equals(counterOwner)) return;
        counterBaselinePending = false;
        counterBaselinePendingSinceMs = 0L;
        chargingCounter.observe(valueKwh, observedAtMs);
        lastSessionCounterKwh = valueKwh;
        clearCounterBaselineCandidates();
    }

    /** Fence the old row and queue a distinct physical charge while its close retries. */
    public synchronized boolean deferPhysicalChargingStart() {
        if (!reconcilePendingActiveChargingReplacement()) return false;
        if (!wasCharging || chargingStartTime <= 0) return false;
        capturePendingChargingClose();
        DeferredChargingGeneration current = currentDeferredPhysicalGeneration();
        if (current != null && !current.isEnded()) {
            return persistChargingLifecycleJournal()
                    && (connection == null || reserveChargingStartIdentity(current.startMs));
        }

        long observedAt = System.currentTimeMillis();
        DeferredChargingGeneration generation = new DeferredChargingGeneration();
        // Journal the complete generation before advancing the H2 allocator. Reserving first left a
        // crash window in which the identity survived but the physical B/C session did not.
        generation.startMs = nextMonotonicChargingStart(
                observedAt, chargingStartIdentityFloor());
        generation.startSoc = currentSocForContinuation();
        generation.startRange = snapshotRangeKm();
        generation.startOdometer = snapshotOdometerKm();
        generation.gun = snapshotGunState();
        generation.timeToFull = snapshotTimeToFullMin();
        double[] location = snapshotLocation();
        generation.lat = location[0];
        generation.lng = location[1];
        generation.counterBaselinePendingSinceMs = generation.startMs;
        if (current != null && current.counter.hasBaseline()) {
            generation.previousCounterKwh = current.counter.lastRawKwh();
        } else {
            generation.previousCounterKwh = chargingCounter.hasBaseline()
                    ? chargingCounter.lastRawKwh() : lastSessionCounterKwh;
        }
        try {
            ChargingStateData state = VehicleDataMonitor.getInstance().getChargingState();
            if (state != null && !state.isEstimated
                    && isFinite(state.chargingPowerKW)
                    && state.chargingPowerKW > 0
                    && state.chargingPowerKW <= 500) {
                generation.peakPower = state.chargingPowerKW;
                generation.powerSum = state.chargingPowerKW;
                generation.powerCount = 1;
            }
        } catch (Throwable ignored) {}
        generation.typeVerdict = deriveIsDc(
                generation.gun, generation.peakPower);
        deferredPhysicalGenerations.addLast(generation);
        sessionInputsFenced = true;
        lastAllocatedChargingStartMs =
                Math.max(lastAllocatedChargingStartMs, generation.startMs);
        if (!persistChargingLifecycleJournal()) return false;
        return connection == null || reserveChargingStartIdentity(generation.startMs);
    }

    /**
     * Journal a first physical ON edge while H2 is unavailable. This is the same deferred-generation
     * format used behind a failed close, so reconnect can materialize it without inventing another
     * identity or losing counter/sample ownership.
     */
    private boolean journalPhysicalChargingStartWithoutDatabase() {
        if (!chargingAnalyticsEnabled || !chargingLifecycleJournalLoaded
                || chargingLifecycleJournalReadFailed) {
            return false;
        }
        DeferredChargingGeneration current = currentDeferredPhysicalGeneration();
        if (current != null && !current.isEnded()) {
            return persistChargingLifecycleJournal();
        }
        long observedAt = System.currentTimeMillis();
        DeferredChargingGeneration generation = new DeferredChargingGeneration();
        generation.startMs = nextMonotonicChargingStart(
                observedAt, chargingStartIdentityFloor());
        generation.startSoc = currentSocForContinuation();
        generation.startRange = snapshotRangeKm();
        generation.startOdometer = snapshotOdometerKm();
        generation.gun = snapshotGunState();
        generation.timeToFull = snapshotTimeToFullMin();
        double[] location = snapshotLocation();
        generation.lat = location[0];
        generation.lng = location[1];
        generation.previousCounterKwh = lastSessionCounterKwh;
        generation.counterBaselinePendingSinceMs = generation.startMs;
        if (Double.isFinite(preSessionCounterLowKwh)
                && preSessionCounterLowKwh >= 0.0
                && preSessionCounterAtMs > 0L
                && preSessionCounterSource != null
                && observedAt - preSessionCounterAtMs >= 0L
                && observedAt - preSessionCounterAtMs <= PRE_SESSION_COUNTER_MAX_AGE_MS) {
            generation.counterOwner = preSessionCounterSource;
            generation.counter.setFullScaleKwh(
                    counterScaleForSource(preSessionCounterSource));
            generation.counter.observe(
                    preSessionCounterLowKwh, preSessionCounterAtMs);
            generation.counterBaselinePending = false;
            generation.counterBaselinePendingSinceMs = 0L;
            generation.counterLatestKwh = preSessionCounterLowKwh;
            generation.counterLatestAtMs = preSessionCounterAtMs;
        }
        double consumedPreSessionCounter = preSessionCounterLowKwh;
        long consumedPreSessionCounterAt = preSessionCounterAtMs;
        String consumedPreSessionCounterSource = preSessionCounterSource;
        double consumedPreSessionProvisionalRaw = preSessionProvisionalExternalRaw;
        long consumedPreSessionProvisionalAt = preSessionProvisionalExternalAtMs;
        double consumedPreSessionProvisionalDivisor =
                preSessionProvisionalExternalUnitDivisor;
        if (Double.isFinite(preSessionProvisionalExternalRaw)
                && preSessionProvisionalExternalAtMs > 0L
                && observedAt - preSessionProvisionalExternalAtMs >= 0L
                && observedAt - preSessionProvisionalExternalAtMs
                        <= PRE_SESSION_COUNTER_MAX_AGE_MS) {
            generation.provisionalExternalKwh =
                    preSessionProvisionalExternalRaw
                            / validCounterUnitDivisor(
                                    preSessionProvisionalExternalUnitDivisor);
            generation.provisionalExternalAtMs =
                    preSessionProvisionalExternalAtMs;
            generation.provisionalExternalUnitDivisor =
                    validCounterUnitDivisor(
                            preSessionProvisionalExternalUnitDivisor);
        }
        // The generation now owns all admissible evidence. Clear every standalone field before the
        // atomic publication; restore the exact prior image if publication fails.
        preSessionCounterLowKwh = Double.NaN;
        preSessionCounterAtMs = 0L;
        preSessionCounterSource = null;
        clearPreSessionProvisionalExternal();
        try {
            ChargingStateData state = VehicleDataMonitor.getInstance().getChargingState();
            if (state != null && !state.isEstimated
                    && isFinite(state.chargingPowerKW)
                    && state.chargingPowerKW > 0
                    && state.chargingPowerKW <= 500) {
                generation.peakPower = state.chargingPowerKW;
                generation.powerSum = state.chargingPowerKW;
                generation.powerCount = 1;
            }
        } catch (Throwable ignored) {}
        generation.typeVerdict = deriveIsDc(
                generation.gun, generation.peakPower);
        deferredPhysicalGenerations.addLast(generation);
        sessionInputsFenced = true;
        lastAllocatedChargingStartMs =
                Math.max(lastAllocatedChargingStartMs, generation.startMs);
        if (persistChargingLifecycleJournal()) {
            return true;
        }
        deferredPhysicalGenerations.removeLastOccurrence(generation);
        sessionInputsFenced = !deferredPhysicalGenerations.isEmpty();
        preSessionCounterLowKwh = consumedPreSessionCounter;
        preSessionCounterAtMs = consumedPreSessionCounterAt;
        preSessionCounterSource = consumedPreSessionCounterSource;
        preSessionProvisionalExternalRaw =
                consumedPreSessionProvisionalRaw;
        preSessionProvisionalExternalAtMs =
                consumedPreSessionProvisionalAt;
        preSessionProvisionalExternalUnitDivisor =
                consumedPreSessionProvisionalDivisor;
        return false;
    }

    /** Capture the endpoint of the newest deferred charge without changing an earlier generation. */
    public synchronized boolean deferPhysicalChargingStop() {
        return deferPhysicalChargingStop(false);
    }

    public synchronized boolean deferPhysicalChargingStop(boolean resumeBlocked) {
        if (!reconcilePendingActiveChargingReplacement()) return false;
        return endDeferredPhysicalGeneration(currentDeferredPhysicalGeneration(),
                System.currentTimeMillis(), currentSocForContinuation(), resumeBlocked);
    }

    private boolean endDeferredPhysicalGeneration(DeferredChargingGeneration generation,
                                                  long boundaryMs, double boundarySoc,
                                                  boolean resumeBlocked) {
        if (generation == null) return false;
        if (!generation.isEnded()) {
            generation.endMs = strictlyAfterChargingStart(
                    generation.startMs, boundaryMs);
            generation.endSoc = !Double.isNaN(boundarySoc)
                    ? boundarySoc : generation.startSoc;
            finalizeDeferredCounterBaseline(generation);
            try {
                BatteryThermalData thermal =
                        VehicleDataMonitor.getInstance().getBatteryThermal();
                if (thermal != null && thermal.hasData()) {
                    if (!Double.isNaN(thermal.highestTempC)) {
                        generation.endTempHigh = thermal.highestTempC;
                    }
                    if (!Double.isNaN(thermal.lowestTempC)) {
                        generation.endTempLow = thermal.lowestTempC;
                    }
                    if (!Double.isNaN(thermal.averageTempC)) {
                        generation.endTempAvg = thermal.averageTempC;
                    }
                }
            } catch (Throwable ignored) {}
            generation.closeIsDc =
                    deferredChargingTypeVerdict(generation);
            try {
                generation.closePricing = priceSessionForClose(
                        generation.closeIsDc, generation.lat, generation.lng);
            } catch (Exception unavailable) {
                generation.closePricing = null;
                logger.warn("Deferred charging pricing unavailable at boundary: "
                        + unavailable.getMessage());
            }
        }
        generation.resumeBlocked |= resumeBlocked;
        return persistChargingLifecycleJournal();
    }

    private void observeDeferredPhysicalCounter(DeferredChargingGeneration generation,
                                                String source, double counterKwh,
                                                double unitDivisor, long now) {
        if (generation == null || source == null) return;
        boolean external =
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL.equals(source);
        if (external && !com.overdrive.app.byd.ChargeSourceClassifier.isCounter(source)) {
            if (com.overdrive.app.byd.ChargeSourceClassifier.isRate(source)) {
                generation.provisionalExternalKwh = Double.NaN;
                generation.provisionalExternalAtMs = 0L;
                generation.provisionalExternalUnitDivisor = 1.0;
            } else if (Double.isNaN(generation.provisionalExternalKwh)
                    || counterValueInRawFrame(counterKwh, unitDivisor)
                    < counterValueInRawFrame(
                            generation.provisionalExternalKwh,
                            generation.provisionalExternalUnitDivisor)) {
                generation.provisionalExternalKwh = counterKwh;
                generation.provisionalExternalAtMs = now;
                generation.provisionalExternalUnitDivisor =
                        validCounterUnitDivisor(unitDivisor);
            }
            persistChargingLifecycleJournal();
            return;
        }
        if (generation.counterOwner == null) {
            generation.counterOwner = source;
            generation.counter.setFullScaleKwh(counterScaleForSource(source));
            generation.counter.markPersistenceMetadataChanged();
            if (external && !Double.isNaN(generation.provisionalExternalKwh)) {
                double held = convertCounterUnitFrame(
                        generation.provisionalExternalKwh,
                        generation.provisionalExternalUnitDivisor, unitDivisor);
                if (held <= counterKwh) {
                    generation.counterCandidateKwh = held;
                    generation.counterCandidateAtMs =
                            generation.provisionalExternalAtMs > 0L
                                    ? generation.provisionalExternalAtMs : now;
                }
                generation.provisionalExternalKwh = Double.NaN;
                generation.provisionalExternalAtMs = 0L;
                generation.provisionalExternalUnitDivisor = 1.0;
            }
        } else if (!generation.counterOwner.equals(source)) {
            return;
        }
        generation.counter.setIndependentEstimate(Double.NaN);
        generation.counterLatestKwh = counterKwh;
        generation.counterLatestAtMs = now;
        if (generation.counterBaselinePending) {
            boolean resetObserved = Double.isNaN(generation.previousCounterKwh)
                    || counterKwh < generation.previousCounterKwh;
            if (!resetObserved) {
                if (Double.isNaN(generation.counterCandidateKwh)
                        || counterKwh < generation.counterCandidateKwh) {
                    generation.counterCandidateKwh = counterKwh;
                    generation.counterCandidateAtMs = now;
                }
                long pendingSince = generation.counterBaselinePendingSinceMs > 0L
                        ? generation.counterBaselinePendingSinceMs : generation.startMs;
                if (pendingSince <= 0L || now - pendingSince <= COUNTER_BASELINE_WAIT_MS) {
                    persistChargingLifecycleJournal();
                    return;
                }
                finalizeDeferredCounterBaseline(generation);
                logger.info("Deferred physical session counter did not reset; accepted its earliest"
                        + " reading after the bounded baseline wait");
                persistChargingLifecycleJournal();
                return;
            }
            generation.counterBaselinePending = false;
            generation.counterBaselinePendingSinceMs = 0L;
            generation.counterCandidateKwh = Double.NaN;
            generation.counterCandidateAtMs = 0L;
        }
        generation.counter.observe(counterKwh, now);
        persistChargingLifecycleJournal();
    }

    private void finalizeDeferredCounterBaseline(DeferredChargingGeneration generation) {
        if (generation == null || !generation.counterBaselinePending) return;
        double baseline = generation.counterCandidateKwh;
        long baselineAt = generation.counterCandidateAtMs;
        double latest = generation.counterLatestKwh;
        long latestAt = generation.counterLatestAtMs;
        generation.counterBaselinePending = false;
        generation.counterBaselinePendingSinceMs = 0L;
        generation.counterCandidateKwh = Double.NaN;
        generation.counterCandidateAtMs = 0L;
        if (Double.isNaN(baseline)) return;
        generation.counter.observe(baseline,
                baselineAt > 0L ? baselineAt : generation.startMs);
        if (!Double.isNaN(latest) && latest != baseline) {
            generation.counter.observe(latest,
                    latestAt > 0L ? latestAt : generation.startMs);
        }
    }

    static double convertCounterUnitFrame(
            double value, double fromDivisor, double toDivisor) {
        if (!Double.isFinite(value)) return Double.NaN;
        return value * validCounterUnitDivisor(fromDivisor)
                / validCounterUnitDivisor(toDivisor);
    }

    private static double counterValueInRawFrame(double value, double divisor) {
        if (!Double.isFinite(value)) return Double.NaN;
        return value * validCounterUnitDivisor(divisor);
    }

    private static double validCounterUnitDivisor(double divisor) {
        return Double.isFinite(divisor) && divisor > 0.0 ? divisor : 1.0;
    }

    private void clearPreSessionProvisionalExternal() {
        preSessionProvisionalExternalRaw = Double.NaN;
        preSessionProvisionalExternalAtMs = 0L;
        preSessionProvisionalExternalUnitDivisor = 1.0;
    }

    public synchronized boolean recordDeferredChargingSample(
            long t, double powerKw, double soc, double temp, double tempHigh, double tempLow) {
        if (!chargingAnalyticsEnabled || optOutClosePending
                || !isValidChargingSamplePower(powerKw)) {
            return false;
        }
        if (!reconcilePendingActiveChargingReplacement()) return false;
        DeferredChargingGeneration generation = currentDeferredPhysicalGeneration();
        if (generation == null) return false;
        if (powerKw > 0 && generation.isEnded() && isAdmittedTaperTail()) {
            generation.endMs = Math.max(
                    generation.endMs,
                    strictlyAfterChargingStart(generation.startMs, t));
            if (!Double.isNaN(soc) && soc >= 0 && soc <= 100) {
                generation.endSoc = soc;
            }
            if (tempHigh > -999) generation.endTempHigh = tempHigh;
            if (tempLow > -999) generation.endTempLow = tempLow;
            if (temp > -999) generation.endTempAvg = temp;
        }
        long sampleAt = Math.max(generation.startMs, t);
        if (generation.isEnded()) sampleAt = Math.min(sampleAt, generation.endMs);
        generation.samples.add(new DeferredChargingSample(
                sampleAt, powerKw, soc, temp, tempHigh, tempLow));
        if (powerKw == MISSING_RATE_BOUNDARY_POWER_KW) {
            generation.integrationTruncated = true;
        }
        if (powerKw > 0) {
            generation.peakPower = Math.max(generation.peakPower, powerKw);
            generation.powerSum += powerKw;
            generation.powerCount++;
            deferredChargingTypeVerdict(generation);
        }
        if (tempHigh > -999) generation.endTempHigh = tempHigh;
        if (tempLow > -999) generation.endTempLow = tempLow;
        if (temp > -999) generation.endTempAvg = temp;
        int liveTtf = snapshotTimeToFullMin();
        if (liveTtf > 0) generation.timeToFull = liveTtf;
        if (generation.startRange < 0) generation.startRange = snapshotRangeKm();
        if (generation.startOdometer < 0) generation.startOdometer = snapshotOdometerKm();
        return persistChargingLifecycleJournal();
    }

    private void insertDeferredChargingSamples(
            DeferredChargingGeneration generation, long sessionStart) throws Exception {
        if (generation == null || generation.samples.isEmpty()) return;
        try (PreparedStatement p = connection.prepareStatement(
                "INSERT INTO " + TABLE_CPS
                        + " (session_start_time, t, power_kw, soc, temp, temp_high, temp_low)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?);")) {
            for (DeferredChargingSample sample : generation.samples) {
                p.setLong(1, sessionStart);
                p.setLong(2, sample.t);
                p.setDouble(3, sample.powerKw);
                p.setDouble(4, sample.soc);
                p.setDouble(5, sample.temp);
                p.setDouble(6, sample.tempHigh);
                p.setDouble(7, sample.tempLow);
                p.addBatch();
            }
            p.executeBatch();
        }
        if (generation.integrationTruncated) {
            try (PreparedStatement p = connection.prepareStatement(
                    "UPDATE " + TABLE_CHARGING
                            + " SET integration_truncated = 1"
                            + " WHERE start_time = ? AND end_time IS NULL;")) {
                p.setLong(1, sessionStart);
                if (p.executeUpdate() != 1) {
                    throw new java.sql.SQLException(
                            "deferred integration marker found no open session");
                }
            }
        }
    }

    private void consumeDeferredPhysicalSessionAfterStart(long sessionStart) {
        DeferredChargingGeneration generation = deferredPhysicalGenerations.pollFirst();
        if (generation == null) return;
        // Older journals may contain both a deferred owner and the standalone evidence from which it
        // was built. The active image must consume both in the same publication that removes the
        // deferred owner, otherwise a crash can offer the evidence to the next physical session.
        preSessionCounterLowKwh = Double.NaN;
        preSessionCounterAtMs = 0L;
        preSessionCounterSource = null;
        clearPreSessionProvisionalExternal();
        if (generation.isEnded()) {
            pendingCloseSessionStart = sessionStart;
            pendingCloseAtMs = strictlyAfterChargingStart(
                    sessionStart, generation.endMs);
            pendingCloseSoc = !Double.isNaN(generation.endSoc)
                    ? generation.endSoc : chargingStartSoc;
            pendingCloseCounterCaptured = true;
            pendingClosePricing = generation.closePricing;
            pendingCloseIsDc = generation.closeIsDc;
            pendingCloseResumeBlocked = generation.resumeBlocked;
            pendingCloseTempHigh = generation.endTempHigh;
            pendingCloseTempLow = generation.endTempLow;
            pendingCloseTempAvg = generation.endTempAvg;
        } else {
            pendingCloseSessionStart = 0L;
            pendingCloseAtMs = 0L;
            pendingCloseSoc = Double.NaN;
            pendingCloseCounterCaptured = false;
            pendingClosePricing = null;
            pendingCloseIsDc = -2;
            pendingCloseResumeBlocked = false;
            pendingCloseTempHigh = -999;
            pendingCloseTempLow = -999;
            pendingCloseTempAvg = -999;
        }
        sessionInputsFenced = !deferredPhysicalGenerations.isEmpty();
        persistChargingLifecycleJournal();
    }

    private void discardDeferredPhysicalSessions() {
        deferredPhysicalGenerations.clear();
        sessionInputsFenced = false;
        persistChargingLifecycleJournal();
    }

    private boolean hasJournaledChargingLifecycle() {
        return lastAllocatedChargingStartMs > 0L
                || optOutClosePending
                || pendingCloseSessionStart > 0L
                || recoveredActivePowerGapAtMs > 0L
                || pendingChargingMaintenanceIntent != null
                || hasAnyConfirmedPreSessionCounter()
                || !Double.isNaN(preSessionProvisionalExternalRaw)
                || !pendingTariffReprices.isEmpty()
                || !deferredPhysicalGenerations.isEmpty();
    }

    private boolean hasAnyConfirmedPreSessionCounter() {
        return Double.isFinite(preSessionCounterLowKwh)
                || preSessionCounterAtMs != 0L
                || preSessionCounterSource != null;
    }

    private boolean hasValidConfirmedPreSessionCounter() {
        return Double.isFinite(preSessionCounterLowKwh)
                && preSessionCounterLowKwh >= 0.0
                && preSessionCounterAtMs > 0L
                && isValidCounterSource(preSessionCounterSource, false);
    }

    private boolean persistChargingLifecycleJournal() {
        java.io.File file = chargingLifecycleJournalFile;
        if (chargingLifecycleJournalReadFailed) {
            chargingLifecycleJournalDirty = true;
            logger.error("Refusing to overwrite an unreadable charging lifecycle journal");
            return false;
        }
        if (file == null) {
            chargingLifecycleJournalDirty = true;
            return false;
        }
        try {
            if (!hasJournaledChargingLifecycle()) {
                boolean removed = java.nio.file.Files.deleteIfExists(file.toPath());
                if (removed) syncDirectoryMetadata(file.getParentFile());
                boolean deleted = !file.exists();
                chargingLifecycleJournalDirty = !deleted;
                return deleted;
            }
            JSONObject root = new JSONObject();
            root.put("version", CHARGING_LIFECYCLE_JOURNAL_VERSION);
            root.put("lastAllocatedStart", lastAllocatedChargingStartMs);
            JSONArray reprices = new JSONArray();
            for (String tariffKey : pendingTariffReprices) {
                reprices.put(tariffKey);
            }
            root.put("pendingTariffReprices", reprices);
            if (hasAnyConfirmedPreSessionCounter()) {
                if (!hasValidConfirmedPreSessionCounter()) {
                    throw new IllegalStateException(
                            "confirmed pre-session counter state is incomplete");
                }
                JSONObject confirmed = new JSONObject();
                confirmed.put("value", preSessionCounterLowKwh);
                confirmed.put("at", preSessionCounterAtMs);
                confirmed.put("source", preSessionCounterSource);
                root.put("preSessionCounter", confirmed);
            }
            if (!Double.isNaN(preSessionProvisionalExternalRaw)) {
                JSONObject preOpen = new JSONObject();
                putFinite(preOpen, "raw", preSessionProvisionalExternalRaw);
                preOpen.put("at", preSessionProvisionalExternalAtMs);
                putFinite(preOpen, "unitDivisor",
                        preSessionProvisionalExternalUnitDivisor);
                root.put("preOpenExternal", preOpen);
            }
            if (wasCharging && chargingStartTime > 0L) {
                root.put("active", activeChargingLifecycleToJson());
            }
            JSONArray deferred = new JSONArray();
            for (DeferredChargingGeneration generation : deferredPhysicalGenerations) {
                deferred.put(deferredGenerationToJson(generation));
            }
            root.put("deferred", deferred);
            if (pendingChargingMaintenanceIntent != null) {
                root.put("maintenanceIntent",
                        chargingMaintenanceIntentToJson(pendingChargingMaintenanceIntent));
            }

            java.io.File parent = file.getParentFile();
            if (parent == null
                    || (!parent.exists() && !parent.mkdirs() && !parent.isDirectory())) {
                throw new java.io.IOException("charging lifecycle journal directory unavailable");
            }
            java.io.File temporary = new java.io.File(parent, file.getName() + ".tmp");
            byte[] bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                try (java.io.FileOutputStream output =
                             new java.io.FileOutputStream(temporary, false)) {
                    output.write(bytes);
                    output.flush();
                    output.getFD().sync();
                }
                try {
                    java.nio.file.Files.move(
                            temporary.toPath(), file.toPath(),
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    java.nio.file.Files.move(
                            temporary.toPath(), file.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                syncDirectoryMetadata(parent);
            } finally {
                java.nio.file.Files.deleteIfExists(temporary.toPath());
            }
            chargingLifecycleJournalDirty = false;
            return true;
        } catch (Exception e) {
            chargingLifecycleJournalDirty = true;
            logger.error("Failed to persist charging lifecycle journal: " + e.getMessage());
            return false;
        }
    }

    static void syncDirectoryMetadata(java.io.File directory) throws Exception {
        if (directory == null || !directory.isDirectory()) {
            throw new java.io.IOException("directory metadata target unavailable");
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                directory.toPath(), java.nio.file.StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private JSONObject activeChargingLifecycleToJson() throws Exception {
        JSONObject active = new JSONObject();
        active.put("start", chargingStartTime);
        putFinite(active, "startSoc", chargingStartSoc);
        putFinite(active, "peak", chargingPeakPower);
        putFinite(active, "powerSum", chargingPowerSum);
        active.put("powerCount", chargingPowerCount);
        active.put("startRange", chargingStartRange);
        active.put("startOdometer", chargingStartOdometer);
        active.put("gun", chargingGunState);
        active.put("typeVerdict", currentChargingTypeVerdict());
        active.put("timeToFull", chargingTimeToFullMin);
        putFinite(active, "lat", chargingStartLat);
        putFinite(active, "lng", chargingStartLng);
        active.put("counterOwner", counterOwner != null ? counterOwner : "");
        active.put("counter", counterStateToJson(chargingCounter.snapshotState()));
        putFinite(active, "lastSessionCounter", lastSessionCounterKwh);
        active.put("baselinePending", counterBaselinePending);
        active.put("baselinePendingSince", counterBaselinePendingSinceMs);
        putFinite(active, "baselineCandidate", counterBaselineCandidateKwh);
        active.put("baselineCandidateAt", counterBaselineCandidateAtMs);
        putFinite(active, "baselineLatest", counterBaselineLatestKwh);
        active.put("baselineLatestAt", counterBaselineLatestAtMs);
        putFinite(active, "provisionalExternal", provisionalExternalKwh);
        active.put("provisionalExternalAt", provisionalExternalAtMs);
        putFinite(active, "provisionalExternalDivisor",
                provisionalExternalUnitDivisor);
        active.put("recoveredPowerGapAt", recoveredActivePowerGapAtMs);

        JSONObject close = new JSONObject();
        close.put("sessionStart", pendingCloseSessionStart);
        close.put("at", pendingCloseAtMs);
        putFinite(close, "soc", pendingCloseSoc);
        close.put("counterCaptured", pendingCloseCounterCaptured);
        close.put("isDc", pendingCloseIsDc);
        close.put("resumeBlocked", pendingCloseResumeBlocked);
        putFinite(close, "tempHigh", pendingCloseTempHigh);
        putFinite(close, "tempLow", pendingCloseTempLow);
        putFinite(close, "tempAvg", pendingCloseTempAvg);
        if (pendingClosePricing != null) {
            close.put("pricing", pricingToJson(pendingClosePricing));
        }
        active.put("pendingClose", close);

        JSONObject optOut = new JSONObject();
        optOut.put("pending", optOutClosePending);
        optOut.put("at", optOutBoundaryMs);
        putFinite(optOut, "soc", optOutBoundarySoc);
        optOut.put("counterCaptured", optOutCounterCaptured);
        optOut.put("isDc", optOutCloseIsDc);
        if (optOutClosePricing != null) {
            optOut.put("pricing", pricingToJson(optOutClosePricing));
        }
        active.put("optOut", optOut);
        return active;
    }

    private JSONObject deferredGenerationToJson(DeferredChargingGeneration generation)
            throws Exception {
        JSONObject out = new JSONObject();
        out.put("start", generation.startMs);
        putFinite(out, "startSoc", generation.startSoc);
        out.put("end", generation.endMs);
        putFinite(out, "endSoc", generation.endSoc);
        out.put("startRange", generation.startRange);
        out.put("startOdometer", generation.startOdometer);
        out.put("gun", generation.gun);
        out.put("typeVerdict", deferredChargingTypeVerdict(generation));
        out.put("timeToFull", generation.timeToFull);
        putFinite(out, "lat", generation.lat);
        putFinite(out, "lng", generation.lng);
        putFinite(out, "peak", generation.peakPower);
        putFinite(out, "powerSum", generation.powerSum);
        out.put("powerCount", generation.powerCount);
        putFinite(out, "tempHigh", generation.endTempHigh);
        putFinite(out, "tempLow", generation.endTempLow);
        putFinite(out, "tempAvg", generation.endTempAvg);
        out.put("counterOwner",
                generation.counterOwner != null ? generation.counterOwner : "");
        out.put("counter", counterStateToJson(generation.counter.snapshotState()));
        putFinite(out, "previousCounter", generation.previousCounterKwh);
        out.put("baselinePending", generation.counterBaselinePending);
        out.put("baselinePendingSince", generation.counterBaselinePendingSinceMs);
        putFinite(out, "counterCandidate", generation.counterCandidateKwh);
        out.put("counterCandidateAt", generation.counterCandidateAtMs);
        putFinite(out, "counterLatest", generation.counterLatestKwh);
        out.put("counterLatestAt", generation.counterLatestAtMs);
        putFinite(out, "provisionalExternal", generation.provisionalExternalKwh);
        out.put("provisionalExternalAt", generation.provisionalExternalAtMs);
        putFinite(out, "provisionalExternalDivisor",
                generation.provisionalExternalUnitDivisor);
        out.put("integrationTruncated", generation.integrationTruncated);
        out.put("closeIsDc", generation.closeIsDc);
        out.put("resumeBlocked", generation.resumeBlocked);
        if (generation.continuationOffer != null) {
            JSONObject continuation = new JSONObject();
            continuation.put("rowStart", generation.continuationOffer.rowStart);
            putFinite(continuation, "endpointKwh",
                    generation.continuationOffer.endpointKwh);
            continuation.put("source",
                    generation.continuationOffer.source != null
                            ? generation.continuationOffer.source : "");
            putFinite(continuation, "startSoc",
                    generation.continuationOffer.startSoc);
            putFinite(continuation, "fullScaleKwh",
                    generation.continuationOffer.fullScaleKwh);
            out.put("continuation", continuation);
        }
        if (generation.closePricing != null) {
            out.put("pricing", pricingToJson(generation.closePricing));
        }
        JSONArray samples = new JSONArray();
        for (DeferredChargingSample sample : generation.samples) {
            JSONObject encoded = new JSONObject();
            encoded.put("t", sample.t);
            putFinite(encoded, "power", sample.powerKw);
            putFinite(encoded, "soc", sample.soc);
            putFinite(encoded, "temp", sample.temp);
            putFinite(encoded, "tempHigh", sample.tempHigh);
            putFinite(encoded, "tempLow", sample.tempLow);
            samples.put(encoded);
        }
        out.put("samples", samples);
        return out;
    }

    private JSONObject chargingMaintenanceIntentToJson(ChargingMaintenanceIntent intent)
            throws Exception {
        JSONObject out = new JSONObject();
        out.put("operation", intent.operation);
        out.put("previousStart", intent.previousStartTime);
        if (intent.replacement != null) {
            out.put("replacement", activeChargingReplacementToJson(intent.replacement));
        }
        JSONArray deferred = new JSONArray();
        java.util.List<DeferredChargingGeneration> generations =
                intent.replacement != null
                        ? intent.replacement.deferredGenerations : intent.deferredGenerations;
        for (DeferredChargingGeneration generation : generations) {
            deferred.put(deferredGenerationToJson(generation));
        }
        out.put("deferred", deferred);
        return out;
    }

    private JSONObject activeChargingReplacementToJson(ActiveChargingReplacement state)
            throws Exception {
        JSONObject out = new JSONObject();
        out.put("previousStart", state.previousStartTime);
        out.put("start", state.startTime);
        putFinite(out, "startSoc", state.startSoc);
        out.put("startRange", state.startRange);
        out.put("startOdometer", state.startOdometer);
        out.put("gun", state.gun);
        out.put("typeVerdict", state.typeVerdict);
        out.put("timeToFull", state.timeToFull);
        putFinite(out, "lat", state.lat);
        putFinite(out, "lng", state.lng);
        out.put("counterSource", state.counterSource != null ? state.counterSource : "");
        out.put("counter", counterStateToJson(state.counterState != null
                ? state.counterState
                : new com.overdrive.app.charging.ChargeCounterAccumulator.State()));
        out.put("lifecycleHold", state.lifecycleHold);
        out.put("pendingClose", state.pendingClose);
        out.put("closeAt", state.closeAtMs);
        putFinite(out, "closeSoc", state.closeSoc);
        out.put("closeCounterCaptured", state.closeCounterCaptured);
        out.put("closeIsDc", state.closeIsDc);
        out.put("closeResumeBlocked", state.closeResumeBlocked);
        putFinite(out, "closeTempHigh", state.closeTempHigh);
        putFinite(out, "closeTempLow", state.closeTempLow);
        putFinite(out, "closeTempAvg", state.closeTempAvg);
        if (state.closePricing != null) {
            out.put("closePricing", pricingToJson(state.closePricing));
        }
        return out;
    }

    private ChargingMaintenanceIntent chargingMaintenanceIntentFromJson(JSONObject source) {
        if (source == null) return null;
        String operation = source.optString("operation", "");
        if (!"clearChargingHistory".equals(operation) && !"resetAll".equals(operation)) {
            return null;
        }
        ChargingMaintenanceIntent intent = new ChargingMaintenanceIntent();
        intent.operation = operation;
        intent.previousStartTime = source.optLong("previousStart", 0L);
        JSONObject replacement = source.optJSONObject("replacement");
        if (replacement != null) {
            intent.replacement = activeChargingReplacementFromJson(replacement);
            if (intent.replacement == null) return null;
        }
        JSONArray deferred = source.optJSONArray("deferred");
        if (deferred != null) {
            for (int i = 0; i < deferred.length(); i++) {
                JSONObject encoded = deferred.optJSONObject(i);
                if (encoded == null) continue;
                DeferredChargingGeneration generation =
                        deferredGenerationFromJson(encoded);
                if (generation == null) continue;
                if (intent.replacement != null) {
                    intent.replacement.deferredGenerations.add(generation);
                } else {
                    intent.deferredGenerations.add(generation);
                }
            }
        }
        return intent;
    }

    private ActiveChargingReplacement activeChargingReplacementFromJson(JSONObject source) {
        long start = source.optLong("start", 0L);
        if (start <= 0L) return null;
        ActiveChargingReplacement state = new ActiveChargingReplacement();
        state.previousStartTime = source.optLong("previousStart", 0L);
        state.startTime = start;
        state.startSoc = finiteOrZero(source, "startSoc");
        state.startRange = source.optInt("startRange", -1);
        state.startOdometer = source.optInt("startOdometer", -1);
        state.gun = source.optInt("gun", -1);
        state.typeVerdict = source.optInt(
                "typeVerdict",
                ChargingTypeClassifier.classify(state.gun, 0.0));
        state.timeToFull = source.optInt("timeToFull", -1);
        state.lat = finiteOrZero(source, "lat");
        state.lng = finiteOrZero(source, "lng");
        state.counterSource = emptyToNull(source.optString("counterSource", ""));
        JSONObject counter = source.optJSONObject("counter");
        state.counterState = counter != null
                ? counterStateFromJson(counter)
                : new com.overdrive.app.charging.ChargeCounterAccumulator.State();
        state.lifecycleHold = source.optBoolean("lifecycleHold", false);
        state.pendingClose = source.optBoolean("pendingClose", false);
        state.closeAtMs = source.optLong("closeAt", 0L);
        if (state.pendingClose) {
            state.closeAtMs = strictlyAfterChargingStart(state.startTime, state.closeAtMs);
        }
        state.closeSoc = finiteOrNaN(source, "closeSoc");
        state.closeCounterCaptured = source.optBoolean("closeCounterCaptured", false);
        state.closeIsDc = source.optInt("closeIsDc", -2);
        state.closeResumeBlocked = source.optBoolean("closeResumeBlocked", false);
        state.closeTempHigh = finiteOrNaN(source, "closeTempHigh");
        state.closeTempLow = finiteOrNaN(source, "closeTempLow");
        state.closeTempAvg = finiteOrNaN(source, "closeTempAvg");
        if (Double.isNaN(state.closeTempHigh)) state.closeTempHigh = -999;
        if (Double.isNaN(state.closeTempLow)) state.closeTempLow = -999;
        if (Double.isNaN(state.closeTempAvg)) state.closeTempAvg = -999;
        state.closePricing = pricingFromJson(source.optJSONObject("closePricing"));
        return state;
    }

    private static JSONObject counterStateToJson(
            com.overdrive.app.charging.ChargeCounterAccumulator.State state)
            throws Exception {
        JSONObject out = new JSONObject();
        putFinite(out, "baseline", state.baseline);
        putFinite(out, "last", state.last);
        out.put("lastAt", state.lastAtMs);
        out.put("observationGeneration", Math.max(0L, state.observationGeneration));
        putFinite(out, "energy", state.accumulated);
        out.put("wraps", state.wraps);
        out.put("resets", state.resets);
        out.put("ceilingStreak", state.ceilingStreak);
        out.put("saturated", state.saturated);
        putFinite(out, "abandoned", state.abandonedKwh);
        out.put("unattributedGaps", state.unattributedGaps);
        out.put("awaitingGap", state.awaitingGapReconcile);
        out.put("gapReconstructed", state.gapReconstructed);
        putFinite(out, "gapEstimate", state.gapEstimateKwh);
        putFinite(out, "recentRate", state.recentRateKwhPerH);
        putFinite(out, "fullScale", state.fullScaleKwh);
        return out;
    }

    private static com.overdrive.app.charging.ChargeCounterAccumulator.State
            counterStateFromJson(JSONObject source) {
        com.overdrive.app.charging.ChargeCounterAccumulator.State state =
                new com.overdrive.app.charging.ChargeCounterAccumulator.State();
        state.baseline = finiteOrNaN(source, "baseline");
        state.last = finiteOrNaN(source, "last");
        state.lastAtMs = source.optLong("lastAt", 0L);
        state.observationGeneration =
                Math.max(0L, source.optLong("observationGeneration", 0L));
        state.accumulated = finiteOrZero(source, "energy");
        state.wraps = Math.max(0, source.optInt("wraps", 0));
        state.resets = Math.max(0, source.optInt("resets", 0));
        state.ceilingStreak = Math.max(0, source.optInt("ceilingStreak", 0));
        state.saturated = source.optBoolean("saturated", false);
        state.abandonedKwh = finiteOrZero(source, "abandoned");
        state.unattributedGaps = Math.max(0, source.optInt("unattributedGaps", 0));
        state.awaitingGapReconcile = source.optBoolean("awaitingGap", false);
        state.gapReconstructed = source.optBoolean("gapReconstructed", false);
        state.gapEstimateKwh = finiteOrNaN(source, "gapEstimate");
        state.recentRateKwhPerH = finiteOrNaN(source, "recentRate");
        state.fullScaleKwh = finiteOrNaN(source, "fullScale");
        return state;
    }

    private static int bindCounterState(
            PreparedStatement statement, int first,
            com.overdrive.app.charging.ChargeCounterAccumulator.State state)
            throws Exception {
        statement.setLong(first++, Math.max(0L, state.lastAtMs));
        statement.setLong(first++, Math.max(0L, state.observationGeneration));
        statement.setInt(first++, Math.max(0, state.wraps));
        statement.setInt(first++, Math.max(0, state.resets));
        statement.setInt(first++, Math.max(0, state.ceilingStreak));
        statement.setInt(first++, state.saturated ? 1 : 0);
        statement.setDouble(first++, Math.max(0.0, state.abandonedKwh));
        statement.setInt(first++, Math.max(0, state.unattributedGaps));
        statement.setInt(first++, state.awaitingGapReconcile ? 1 : 0);
        statement.setInt(first++, state.gapReconstructed ? 1 : 0);
        if (Double.isFinite(state.gapEstimateKwh)) {
            statement.setDouble(first++, state.gapEstimateKwh);
        } else {
            statement.setNull(first++, java.sql.Types.DOUBLE);
        }
        if (Double.isFinite(state.recentRateKwhPerH)) {
            statement.setDouble(first++, state.recentRateKwhPerH);
        } else {
            statement.setNull(first++, java.sql.Types.DOUBLE);
        }
        return first;
    }

    private static JSONObject pricingToJson(PricingDecision pricing) throws Exception {
        JSONObject out = new JSONObject();
        putFinite(out, "rate", pricing.rate);
        out.put("currency", pricing.currency);
        out.put("tariffId", pricing.tariffId);
        out.put("tariffLabel", pricing.tariffLabel);
        return out;
    }

    private static PricingDecision pricingFromJson(JSONObject source) {
        if (source == null) return null;
        double rate = finiteOrNaN(source, "rate");
        if (Double.isNaN(rate)) return null;
        return new PricingDecision(
                rate,
                source.optString("currency", ""),
                source.optString("tariffId", ""),
                source.optString("tariffLabel", ""));
    }

    private static void putFinite(JSONObject target, String key, double value)
            throws Exception {
        target.put(key, Double.isFinite(value) ? Double.valueOf(value) : JSONObject.NULL);
    }

    private static double finiteOrNaN(JSONObject source, String key) {
        if (source == null || !source.has(key) || source.isNull(key)) return Double.NaN;
        double value = source.optDouble(key, Double.NaN);
        return Double.isFinite(value) ? value : Double.NaN;
    }

    private static double finiteOrZero(JSONObject source, String key) {
        double value = finiteOrNaN(source, key);
        return Double.isNaN(value) ? 0.0 : value;
    }

    private static void validateChargingLifecycleJournal(JSONObject root) {
        requireJournalLong(
                root, "version",
                CHARGING_LIFECYCLE_JOURNAL_VERSION,
                CHARGING_LIFECYCLE_JOURNAL_VERSION);
        long lastAllocated = requireJournalLong(
                root, "lastAllocatedStart", 0L, Long.MAX_VALUE - 1L);
        JSONArray reprices = requireJournalArray(root, "pendingTariffReprices");
        for (int i = 0; i < reprices.length(); i++) {
            Object value = reprices.opt(i);
            if (!(value instanceof String)
                    || normalizeTariffRepriceKey((String) value).isEmpty()) {
                throw invalidJournal(
                        "pendingTariffReprices contains an invalid key");
            }
        }
        JSONObject confirmed = optionalJournalObject(root, "preSessionCounter");
        if (confirmed != null) {
            requireJournalFinite(
                    confirmed, "value", 0.0, Double.MAX_VALUE);
            requireJournalLong(confirmed, "at", 1L, Long.MAX_VALUE);
            requireJournalCounterSource(confirmed, "source", false);
        }
        JSONObject preOpen = optionalJournalObject(root, "preOpenExternal");
        if (preOpen != null) {
            requireJournalFinite(preOpen, "raw", 0.0, Double.MAX_VALUE);
            requireJournalLong(preOpen, "at", 1L, Long.MAX_VALUE);
            requireJournalFinite(
                    preOpen, "unitDivisor", Double.MIN_VALUE, Double.MAX_VALUE);
        }
        JSONObject active = optionalJournalObject(root, "active");
        if (active != null) {
            validateActiveChargingLifecycle(active);
            long activeStart = requireJournalLong(
                    active, "start", 1L, Long.MAX_VALUE - 1L);
            if (lastAllocated < activeStart) {
                throw invalidJournal(
                        "lastAllocatedStart precedes the active session identity");
            }
        }

        JSONArray deferred = requireJournalArray(root, "deferred");
        long priorStart = active != null
                ? requireJournalLong(active, "start", 1L, Long.MAX_VALUE - 1L)
                : 0L;
        for (int i = 0; i < deferred.length(); i++) {
            JSONObject generation = requireJournalArrayObject(
                    deferred, i, "deferred");
            validateDeferredChargingGeneration(generation);
            long start = requireJournalLong(
                    generation, "start", 1L, Long.MAX_VALUE - 1L);
            if (start <= priorStart) {
                throw invalidJournal(
                        "deferred session identities are not strictly increasing");
            }
            if (lastAllocated < start) {
                throw invalidJournal(
                        "lastAllocatedStart precedes a deferred session identity");
            }
            priorStart = start;
        }

        JSONObject maintenance = optionalJournalObject(root, "maintenanceIntent");
        if (maintenance != null) {
            validateChargingMaintenanceIntent(maintenance, lastAllocated);
        }
    }

    private static void validateActiveChargingLifecycle(JSONObject active) {
        long start = requireJournalLong(
                active, "start", 1L, Long.MAX_VALUE - 1L);
        requireJournalSoc(active, "startSoc");
        requireOptionalJournalFinite(active, "peak", 0.0, Double.MAX_VALUE);
        requireOptionalJournalFinite(active, "powerSum", 0.0, Double.MAX_VALUE);
        requireJournalLong(active, "powerCount", 0L, Integer.MAX_VALUE);
        requireJournalLong(active, "startRange", -1L, Integer.MAX_VALUE);
        requireJournalLong(active, "startOdometer", -1L, Integer.MAX_VALUE);
        requireJournalLong(active, "gun", -1L, Integer.MAX_VALUE);
        if (active.has("typeVerdict")) {
            requireJournalLong(active, "typeVerdict", -1L, 1L);
        }
        requireJournalLong(active, "timeToFull", -1L, Integer.MAX_VALUE);
        requireOptionalJournalFinite(active, "lat", -90.0, 90.0);
        requireOptionalJournalFinite(active, "lng", -180.0, 180.0);
        requireJournalCounterSource(active, "counterOwner", true);
        validateJournalCounter(requireJournalObject(active, "counter"));
        requireOptionalJournalFinite(
                active, "lastSessionCounter", 0.0, Double.MAX_VALUE);
        boolean baselinePending = requireJournalBoolean(active, "baselinePending");
        long baselinePendingSince = requireJournalLong(
                active, "baselinePendingSince", 0L, Long.MAX_VALUE);
        if (baselinePending && baselinePendingSince <= 0L) {
            throw invalidJournal(
                    "active pending counter baseline has no timestamp");
        }
        validateOptionalValueTimestampPair(
                active, "baselineCandidate", "baselineCandidateAt",
                0.0, Double.MAX_VALUE);
        validateOptionalValueTimestampPair(
                active, "baselineLatest", "baselineLatestAt",
                0.0, Double.MAX_VALUE);
        validateOptionalValueTimestampPair(
                active, "provisionalExternal", "provisionalExternalAt",
                0.0, Double.MAX_VALUE);
        requireJournalFinite(
                active, "provisionalExternalDivisor",
                Double.MIN_VALUE, Double.MAX_VALUE);
        long recoveredGap = requireJournalLong(
                active, "recoveredPowerGapAt", 0L, Long.MAX_VALUE);
        if (recoveredGap > 0L && recoveredGap <= start) {
            throw invalidJournal(
                    "active recovered power-gap timestamp does not follow its session");
        }

        JSONObject close = requireJournalObject(active, "pendingClose");
        long closeSession = requireJournalLong(
                close, "sessionStart", 0L, Long.MAX_VALUE - 1L);
        long closeAt = requireJournalLong(close, "at", 0L, Long.MAX_VALUE);
        requireOptionalJournalSoc(close, "soc");
        if (closeSession != 0L) {
            if (closeSession != start || closeAt <= start) {
                throw invalidJournal(
                        "active pending-close identity or timestamp is invalid");
            }
        } else if (closeAt != 0L) {
            throw invalidJournal(
                    "active pending-close timestamp exists without an identity");
        }
        requireJournalBoolean(close, "counterCaptured");
        requireJournalBoolean(close, "resumeBlocked");
        requireJournalLong(close, "isDc", -2L, 1L);
        requireOptionalJournalFinite(
                close, "tempHigh", -999.0, Double.MAX_VALUE);
        requireOptionalJournalFinite(
                close, "tempLow", -999.0, Double.MAX_VALUE);
        requireOptionalJournalFinite(
                close, "tempAvg", -999.0, Double.MAX_VALUE);
        validateOptionalJournalPricing(close, "pricing");

        JSONObject optOut = requireJournalObject(active, "optOut");
        boolean pending = requireJournalBoolean(optOut, "pending");
        long boundaryAt = requireJournalLong(
                optOut, "at", 0L, Long.MAX_VALUE);
        requireOptionalJournalSoc(optOut, "soc");
        if (pending) {
            if (boundaryAt <= start) {
                throw invalidJournal(
                        "active opt-out boundary does not follow its session");
            }
        } else if (boundaryAt != 0L) {
            throw invalidJournal(
                    "active opt-out timestamp exists without a pending boundary");
        }
        requireJournalBoolean(optOut, "counterCaptured");
        requireJournalLong(optOut, "isDc", -2L, 1L);
        validateOptionalJournalPricing(optOut, "pricing");
    }

    private static void validateDeferredChargingGeneration(JSONObject generation) {
        long start = requireJournalLong(
                generation, "start", 1L, Long.MAX_VALUE - 1L);
        requireJournalSoc(generation, "startSoc");
        long end = requireJournalLong(
                generation, "end", 0L, Long.MAX_VALUE);
        if (end > 0L) {
            if (end <= start) {
                throw invalidJournal(
                        "deferred session end does not follow its start");
            }
            requireJournalSoc(generation, "endSoc");
        } else {
            requireOptionalJournalSoc(generation, "endSoc");
        }
        requireJournalLong(generation, "startRange", -1L, Integer.MAX_VALUE);
        requireJournalLong(
                generation, "startOdometer", -1L, Integer.MAX_VALUE);
        requireJournalLong(generation, "gun", -1L, Integer.MAX_VALUE);
        if (generation.has("typeVerdict")) {
            requireJournalLong(generation, "typeVerdict", -1L, 1L);
        }
        requireJournalLong(
                generation, "timeToFull", -1L, Integer.MAX_VALUE);
        requireOptionalJournalFinite(generation, "lat", -90.0, 90.0);
        requireOptionalJournalFinite(generation, "lng", -180.0, 180.0);
        requireOptionalJournalFinite(
                generation, "peak", 0.0, Double.MAX_VALUE);
        requireOptionalJournalFinite(
                generation, "powerSum", 0.0, Double.MAX_VALUE);
        requireJournalLong(generation, "powerCount", 0L, Integer.MAX_VALUE);
        requireOptionalJournalFinite(
                generation, "tempHigh", -999.0, Double.MAX_VALUE);
        requireOptionalJournalFinite(
                generation, "tempLow", -999.0, Double.MAX_VALUE);
        requireOptionalJournalFinite(
                generation, "tempAvg", -999.0, Double.MAX_VALUE);
        requireJournalCounterSource(generation, "counterOwner", true);
        validateJournalCounter(requireJournalObject(generation, "counter"));
        requireOptionalJournalFinite(
                generation, "previousCounter", 0.0, Double.MAX_VALUE);
        boolean baselinePending =
                requireJournalBoolean(generation, "baselinePending");
        long baselinePendingSince = requireJournalLong(
                generation, "baselinePendingSince", 0L, Long.MAX_VALUE);
        if (baselinePending && baselinePendingSince <= 0L) {
            throw invalidJournal(
                    "deferred pending counter baseline has no timestamp");
        }
        validateOptionalValueTimestampPair(
                generation, "counterCandidate", "counterCandidateAt",
                0.0, Double.MAX_VALUE);
        validateOptionalValueTimestampPair(
                generation, "counterLatest", "counterLatestAt",
                0.0, Double.MAX_VALUE);
        validateOptionalValueTimestampPair(
                generation, "provisionalExternal", "provisionalExternalAt",
                0.0, Double.MAX_VALUE);
        requireJournalFinite(
                generation, "provisionalExternalDivisor",
                Double.MIN_VALUE, Double.MAX_VALUE);
        requireJournalBoolean(generation, "integrationTruncated");
        requireJournalBoolean(generation, "resumeBlocked");
        requireJournalLong(generation, "closeIsDc", -2L, 1L);
        JSONObject continuation =
                optionalJournalObject(generation, "continuation");
        if (continuation != null) {
            validateJournalContinuation(continuation);
        }
        validateOptionalJournalPricing(generation, "pricing");

        JSONArray samples = requireJournalArray(generation, "samples");
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = requireJournalArrayObject(
                    samples, i, "deferred samples");
            long sampleAt = requireJournalLong(
                    sample, "t", start, Long.MAX_VALUE);
            if (end > 0L && sampleAt > end) {
                throw invalidJournal(
                        "deferred sample lies after its session boundary");
            }
            double samplePower =
                    requireJournalFinite(
                            sample, "power", -Double.MAX_VALUE, Double.MAX_VALUE);
            if (!isValidChargingSamplePower(samplePower)) {
                throw invalidJournal(
                        "deferred sample power is outside its domain");
            }
            requireOptionalJournalFinite(
                    sample, "soc", 0.0, 100.0);
            requireOptionalJournalFinite(
                    sample, "temp", -999.0, Double.MAX_VALUE);
            requireOptionalJournalFinite(
                    sample, "tempHigh", -999.0, Double.MAX_VALUE);
            requireOptionalJournalFinite(
                    sample, "tempLow", -999.0, Double.MAX_VALUE);
        }
    }

    private static void validateChargingMaintenanceIntent(
            JSONObject maintenance, long lastAllocated) {
        Object operationValue = maintenance.opt("operation");
        if (!(operationValue instanceof String)) {
            throw invalidJournal("maintenance operation is missing or not a string");
        }
        String operation = (String) operationValue;
        if (!"clearChargingHistory".equals(operation) && !"resetAll".equals(operation)) {
            throw invalidJournal("maintenance operation is unsupported");
        }
        long previousStart = requireJournalLong(
                maintenance, "previousStart", 0L, Long.MAX_VALUE - 1L);
        JSONObject replacement = optionalJournalObject(maintenance, "replacement");
        long priorStart = 0L;
        if (replacement != null) {
            validateActiveChargingReplacement(replacement);
            long replacementPrevious = requireJournalLong(
                    replacement, "previousStart", 0L, Long.MAX_VALUE - 1L);
            if (replacementPrevious != previousStart) {
                throw invalidJournal(
                        "maintenance replacement does not reference its previous session");
            }
            priorStart = requireJournalLong(
                    replacement, "start", 1L, Long.MAX_VALUE - 1L);
            if (lastAllocated < priorStart) {
                throw invalidJournal(
                        "lastAllocatedStart precedes the maintenance replacement");
            }
        }
        JSONArray deferred = requireJournalArray(maintenance, "deferred");
        for (int i = 0; i < deferred.length(); i++) {
            JSONObject generation = requireJournalArrayObject(
                    deferred, i, "maintenance deferred sessions");
            validateDeferredChargingGeneration(generation);
            long start = requireJournalLong(
                    generation, "start", 1L, Long.MAX_VALUE - 1L);
            if (start <= priorStart) {
                throw invalidJournal(
                        "maintenance session identities are not strictly increasing");
            }
            if (lastAllocated < start) {
                throw invalidJournal(
                        "lastAllocatedStart precedes a maintenance session identity");
            }
            priorStart = start;
        }
    }

    private static void validateActiveChargingReplacement(JSONObject replacement) {
        long start = requireJournalLong(
                replacement, "start", 1L, Long.MAX_VALUE - 1L);
        requireJournalLong(
                replacement, "previousStart", 0L, Long.MAX_VALUE - 1L);
        requireJournalSoc(replacement, "startSoc");
        requireJournalLong(replacement, "startRange", -1L, Integer.MAX_VALUE);
        requireJournalLong(
                replacement, "startOdometer", -1L, Integer.MAX_VALUE);
        requireJournalLong(replacement, "gun", -1L, Integer.MAX_VALUE);
        if (replacement.has("typeVerdict")) {
            requireJournalLong(replacement, "typeVerdict", -1L, 1L);
        }
        requireJournalLong(
                replacement, "timeToFull", -1L, Integer.MAX_VALUE);
        requireOptionalJournalFinite(replacement, "lat", -90.0, 90.0);
        requireOptionalJournalFinite(replacement, "lng", -180.0, 180.0);
        requireJournalCounterSource(replacement, "counterSource", true);
        validateJournalCounter(requireJournalObject(replacement, "counter"));
        requireJournalBoolean(replacement, "lifecycleHold");
        boolean pendingClose = requireJournalBoolean(replacement, "pendingClose");
        long closeAt = requireJournalLong(
                replacement, "closeAt", 0L, Long.MAX_VALUE);
        requireOptionalJournalSoc(replacement, "closeSoc");
        if (pendingClose) {
            if (closeAt <= start) {
                throw invalidJournal(
                        "maintenance replacement close does not follow its start");
            }
        } else if (closeAt != 0L) {
            throw invalidJournal(
                    "maintenance replacement close timestamp has no pending close");
        }
        requireJournalBoolean(replacement, "closeCounterCaptured");
        requireJournalBoolean(replacement, "closeResumeBlocked");
        requireJournalLong(replacement, "closeIsDc", -2L, 1L);
        requireOptionalJournalFinite(
                replacement, "closeTempHigh", -999.0, Double.MAX_VALUE);
        requireOptionalJournalFinite(
                replacement, "closeTempLow", -999.0, Double.MAX_VALUE);
        requireOptionalJournalFinite(
                replacement, "closeTempAvg", -999.0, Double.MAX_VALUE);
        validateOptionalJournalPricing(replacement, "closePricing");
    }

    private static void validateJournalContinuation(JSONObject continuation) {
        requireJournalLong(
                continuation, "rowStart", 1L, Long.MAX_VALUE - 1L);
        requireOptionalJournalFinite(
                continuation, "endpointKwh", 0.0, Double.MAX_VALUE);
        requireJournalCounterSource(continuation, "source", true);
        requireOptionalJournalSoc(continuation, "startSoc");
        Double fullScale = requireOptionalJournalFinite(
                continuation, "fullScaleKwh", 0.0, Double.MAX_VALUE);
        if (fullScale != null && fullScale <= 1.0) {
            throw invalidJournal(
                    "continuation counter full scale is outside its domain");
        }
    }

    private static void validateOptionalValueTimestampPair(
            JSONObject source, String valueKey, String timestampKey,
            double minimum, double maximum) {
        Double value =
                requireOptionalJournalFinite(source, valueKey, minimum, maximum);
        long timestamp =
                requireJournalLong(source, timestampKey, 0L, Long.MAX_VALUE);
        if ((value == null) != (timestamp == 0L)) {
            throw invalidJournal(
                    valueKey + " and " + timestampKey
                            + " must be present together");
        }
    }

    private static void validateJournalCounter(JSONObject counter) {
        Double baseline = requireOptionalJournalFinite(
                counter, "baseline", 0.0, Double.MAX_VALUE);
        Double last = requireOptionalJournalFinite(
                counter, "last", 0.0, Double.MAX_VALUE);
        if ((baseline == null) != (last == null)) {
            throw invalidJournal(
                    "counter baseline and last value must be present together");
        }
        requireJournalFinite(counter, "energy", 0.0, Double.MAX_VALUE);
        long lastAt = requireJournalLong(
                counter, "lastAt", 0L, Long.MAX_VALUE);
        if (baseline != null && lastAt <= 0L) {
            throw invalidJournal(
                    "counter series has no observation timestamp");
        }
        long observationGeneration = requireJournalLong(
                counter, "observationGeneration", 0L, Long.MAX_VALUE);
        requireJournalLong(counter, "wraps", 0L, Integer.MAX_VALUE);
        requireJournalLong(counter, "resets", 0L, Integer.MAX_VALUE);
        requireJournalLong(counter, "ceilingStreak", 0L, Integer.MAX_VALUE);
        requireJournalBoolean(counter, "saturated");
        requireJournalFinite(counter, "abandoned", 0.0, Double.MAX_VALUE);
        requireJournalLong(
                counter, "unattributedGaps", 0L, Integer.MAX_VALUE);
        boolean awaitingGap = requireJournalBoolean(counter, "awaitingGap");
        boolean gapReconstructed =
                requireJournalBoolean(counter, "gapReconstructed");
        if ((awaitingGap || gapReconstructed)
                && (baseline == null || last == null
                        || lastAt <= 0L || observationGeneration <= 0L)) {
            throw invalidJournal(
                    "counter gap state has no durable endpoint lineage");
        }
        requireOptionalJournalFinite(
                counter, "gapEstimate", 0.0, Double.MAX_VALUE);
        requireOptionalJournalFinite(
                counter, "recentRate", 0.0, Double.MAX_VALUE);
        Double fullScale = requireOptionalJournalFinite(
                counter, "fullScale", 0.0, Double.MAX_VALUE);
        if (fullScale != null && fullScale <= 1.0) {
            throw invalidJournal("counter full scale is outside its domain");
        }
    }

    private static void validateOptionalJournalPricing(
            JSONObject parent, String key) {
        JSONObject pricing = optionalJournalObject(parent, key);
        if (pricing == null) return;
        requireJournalFinite(pricing, "rate", 0.0, Double.MAX_VALUE);
        requireJournalString(pricing, "currency");
        requireJournalString(pricing, "tariffId");
        requireJournalString(pricing, "tariffLabel");
    }

    private static JSONObject requireJournalObject(JSONObject source, String key) {
        Object value = source.opt(key);
        if (!(value instanceof JSONObject)) {
            throw invalidJournal(key + " is missing or not an object");
        }
        return (JSONObject) value;
    }

    private static JSONObject optionalJournalObject(JSONObject source, String key) {
        if (!source.has(key)) return null;
        return requireJournalObject(source, key);
    }

    private static JSONArray requireJournalArray(JSONObject source, String key) {
        Object value = source.opt(key);
        if (!(value instanceof JSONArray)) {
            throw invalidJournal(key + " is missing or not an array");
        }
        return (JSONArray) value;
    }

    private static JSONObject requireJournalArrayObject(
            JSONArray source, int index, String label) {
        Object value = source.opt(index);
        if (!(value instanceof JSONObject)) {
            throw invalidJournal(label + " contains a non-object entry");
        }
        return (JSONObject) value;
    }

    private static long requireJournalLong(
            JSONObject source, String key, long minimum, long maximum) {
        Object value = source.opt(key);
        if (!(value instanceof Number)) {
            throw invalidJournal(key + " is missing or not an integer");
        }
        try {
            java.math.BigDecimal exact =
                    new java.math.BigDecimal(value.toString()).stripTrailingZeros();
            if (exact.scale() > 0
                    || exact.compareTo(java.math.BigDecimal.valueOf(minimum)) < 0
                    || exact.compareTo(java.math.BigDecimal.valueOf(maximum)) > 0) {
                throw invalidJournal(key + " is outside its integer domain");
            }
            return exact.longValueExact();
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw invalidJournal(key + " is outside its integer domain");
        }
    }

    private static boolean requireJournalBoolean(JSONObject source, String key) {
        Object value = source.opt(key);
        if (!(value instanceof Boolean)) {
            throw invalidJournal(key + " is missing or not a boolean");
        }
        return (Boolean) value;
    }

    private static String requireJournalString(JSONObject source, String key) {
        Object value = source.opt(key);
        if (!(value instanceof String)) {
            throw invalidJournal(key + " is missing or not a string");
        }
        return (String) value;
    }

    private static String requireJournalCounterSource(
            JSONObject source, String key, boolean allowEmpty) {
        String value = requireJournalString(source, key);
        if (!isValidCounterSource(value, allowEmpty)) {
            throw invalidJournal(key + " is not a supported counter source");
        }
        return value;
    }

    private static boolean isValidCounterSource(
            String value, boolean allowEmpty) {
        return (allowEmpty && (value == null || value.isEmpty()))
                || com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY.equals(value)
                || com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL.equals(value);
    }

    private static double requireJournalFinite(
            JSONObject source, String key, double minimum, double maximum) {
        Object value = source.opt(key);
        if (!(value instanceof Number)) {
            throw invalidJournal(key + " is missing or not numeric");
        }
        double result = ((Number) value).doubleValue();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw invalidJournal(key + " is outside its numeric domain");
        }
        return result;
    }

    private static Double requireOptionalJournalFinite(
            JSONObject source, String key, double minimum, double maximum) {
        if (!source.has(key)) {
            throw invalidJournal(key + " is missing");
        }
        Object value = source.opt(key);
        if (value == null || value == JSONObject.NULL) return null;
        if (!(value instanceof Number)) {
            throw invalidJournal(key + " is not numeric or null");
        }
        double result = ((Number) value).doubleValue();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw invalidJournal(key + " is outside its numeric domain");
        }
        return result;
    }

    private static double requireJournalSoc(JSONObject source, String key) {
        return requireJournalFinite(source, key, 0.0, 100.0);
    }

    private static void requireOptionalJournalSoc(JSONObject source, String key) {
        requireOptionalJournalFinite(source, key, 0.0, 100.0);
    }

    private static IllegalStateException invalidJournal(String reason) {
        return new IllegalStateException(
                "invalid charging lifecycle journal: " + reason);
    }

    private void loadChargingLifecycleJournal() {
        if (chargingLifecycleJournalLoaded) return;
        chargingLifecycleJournalLoaded = true;
        java.io.File file = chargingLifecycleJournalFile;
        if (file == null || !file.isFile()) return;
        try {
            String encoded = new String(
                    java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(encoded);
            if (root.optInt("version", -1) != CHARGING_LIFECYCLE_JOURNAL_VERSION) {
                throw new IllegalStateException("unsupported charging lifecycle journal version");
            }
            validateChargingLifecycleJournal(root);
            lastAllocatedChargingStartMs = Math.max(
                    lastAllocatedChargingStartMs,
                    root.optLong("lastAllocatedStart", 0L));
            JSONArray reprices = root.optJSONArray("pendingTariffReprices");
            if (reprices != null) {
                for (int i = 0; i < reprices.length(); i++) {
                    String tariffKey = normalizeTariffRepriceKey(
                            reprices.optString(i, ""));
                    if (!tariffKey.isEmpty()) pendingTariffReprices.add(tariffKey);
                }
            }
            JSONObject confirmed = root.optJSONObject("preSessionCounter");
            if (confirmed != null) {
                preSessionCounterLowKwh = confirmed.getDouble("value");
                preSessionCounterAtMs = confirmed.getLong("at");
                preSessionCounterSource = confirmed.getString("source");
            }
            JSONObject preOpen = root.optJSONObject("preOpenExternal");
            if (preOpen != null) {
                preSessionProvisionalExternalRaw = finiteOrNaN(preOpen, "raw");
                preSessionProvisionalExternalAtMs = preOpen.optLong("at", 0L);
                preSessionProvisionalExternalUnitDivisor =
                        validCounterUnitDivisor(
                                finiteOrNaN(preOpen, "unitDivisor"));
            }
            JSONObject active = root.optJSONObject("active");
            if (active != null) restoreActiveChargingLifecycle(active);
            JSONArray deferred = root.optJSONArray("deferred");
            if (deferred != null) {
                for (int i = 0; i < deferred.length(); i++) {
                    JSONObject item = deferred.optJSONObject(i);
                    if (item == null) continue;
                    DeferredChargingGeneration generation =
                            deferredGenerationFromJson(item);
                    if (generation != null) {
                        deferredPhysicalGenerations.addLast(generation);
                        lastAllocatedChargingStartMs = Math.max(
                                lastAllocatedChargingStartMs, generation.startMs);
                    }
                }
            }
            JSONObject maintenance = root.optJSONObject("maintenanceIntent");
            if (maintenance != null) {
                pendingChargingMaintenanceIntent =
                        chargingMaintenanceIntentFromJson(maintenance);
                if (pendingChargingMaintenanceIntent == null) {
                    throw new IllegalStateException(
                            "invalid charging maintenance intent");
                }
            }
            sessionInputsFenced = !deferredPhysicalGenerations.isEmpty();
            chargingLifecycleJournalReadFailed = false;
            chargingLifecycleJournalDirty = false;
            logger.warn("Restored charging lifecycle journal: active="
                    + (wasCharging ? chargingStartTime : "none")
                    + ", deferred=" + deferredPhysicalGenerations.size());
        } catch (Exception e) {
            chargingLifecycleJournalReadFailed = true;
            chargingLifecycleJournalDirty = true;
            logger.error("Charging lifecycle journal is unreadable; preserving it: "
                    + e.getMessage());
        }
    }

    private void restoreActiveChargingLifecycle(JSONObject active) {
        chargingStartTime = active.optLong("start", 0L);
        if (chargingStartTime <= 0L) return;
        wasCharging = true;
        chargingStartSoc = finiteOrZero(active, "startSoc");
        chargingPeakPower = finiteOrZero(active, "peak");
        chargingPowerSum = finiteOrZero(active, "powerSum");
        chargingPowerCount = Math.max(0, active.optInt("powerCount", 0));
        chargingStartRange = active.optInt("startRange", -1);
        chargingStartOdometer = active.optInt("startOdometer", -1);
        chargingGunState = active.optInt("gun", -1);
        chargingTypeVerdict = active.optInt(
                "typeVerdict",
                ChargingTypeClassifier.classify(
                        chargingGunState, chargingPeakPower));
        chargingTimeToFullMin = active.optInt("timeToFull", -1);
        chargingStartLat = finiteOrZero(active, "lat");
        chargingStartLng = finiteOrZero(active, "lng");
        counterOwner = emptyToNull(active.optString("counterOwner", ""));
        JSONObject counter = active.optJSONObject("counter");
        chargingCounter.restoreState(
                counter != null ? counterStateFromJson(counter) : null);
        lastSessionCounterKwh = finiteOrNaN(active, "lastSessionCounter");
        counterBaselinePending = active.optBoolean("baselinePending", false);
        counterBaselinePendingSinceMs = active.optLong("baselinePendingSince", 0L);
        counterBaselineCandidateKwh = finiteOrNaN(active, "baselineCandidate");
        counterBaselineCandidateAtMs = active.optLong("baselineCandidateAt", 0L);
        counterBaselineLatestKwh = finiteOrNaN(active, "baselineLatest");
        counterBaselineLatestAtMs = active.optLong("baselineLatestAt", 0L);
        provisionalExternalKwh = finiteOrNaN(active, "provisionalExternal");
        provisionalExternalAtMs = active.optLong("provisionalExternalAt", 0L);
        provisionalExternalUnitDivisor = validCounterUnitDivisor(
                finiteOrNaN(active, "provisionalExternalDivisor"));
        recoveredActivePowerGapAtMs =
                active.optLong("recoveredPowerGapAt", 0L);

        JSONObject close = active.optJSONObject("pendingClose");
        if (close != null) {
            pendingCloseSessionStart = close.optLong("sessionStart", 0L);
            pendingCloseAtMs = close.optLong("at", 0L);
            if (pendingCloseSessionStart == chargingStartTime) {
                pendingCloseAtMs = strictlyAfterChargingStart(
                        chargingStartTime, pendingCloseAtMs);
            }
            pendingCloseSoc = finiteOrNaN(close, "soc");
            pendingCloseCounterCaptured = close.optBoolean("counterCaptured", false);
            pendingCloseIsDc = close.optInt("isDc", -2);
            pendingCloseResumeBlocked = close.optBoolean("resumeBlocked", false);
            pendingCloseTempHigh = finiteOrNaN(close, "tempHigh");
            pendingCloseTempLow = finiteOrNaN(close, "tempLow");
            pendingCloseTempAvg = finiteOrNaN(close, "tempAvg");
            if (Double.isNaN(pendingCloseTempHigh)) pendingCloseTempHigh = -999;
            if (Double.isNaN(pendingCloseTempLow)) pendingCloseTempLow = -999;
            if (Double.isNaN(pendingCloseTempAvg)) pendingCloseTempAvg = -999;
            pendingClosePricing = pricingFromJson(close.optJSONObject("pricing"));
        }
        JSONObject optOut = active.optJSONObject("optOut");
        if (optOut != null) {
            optOutClosePending = optOut.optBoolean("pending", false);
            optOutBoundaryMs = optOut.optLong("at", 0L);
            if (optOutClosePending) {
                optOutBoundaryMs = strictlyAfterChargingStart(
                        chargingStartTime, optOutBoundaryMs);
            }
            optOutBoundarySoc = finiteOrNaN(optOut, "soc");
            optOutCounterCaptured = optOut.optBoolean("counterCaptured", false);
            optOutCloseIsDc = optOut.optInt("isDc", -2);
            optOutClosePricing = pricingFromJson(optOut.optJSONObject("pricing"));
        }
        lastAllocatedChargingStartMs = Math.max(
                lastAllocatedChargingStartMs, chargingStartTime);
    }

    private DeferredChargingGeneration deferredGenerationFromJson(JSONObject source) {
        long start = source.optLong("start", 0L);
        if (start <= 0L) return null;
        DeferredChargingGeneration generation = new DeferredChargingGeneration();
        generation.startMs = start;
        generation.startSoc = finiteOrZero(source, "startSoc");
        generation.endMs = source.optLong("end", 0L);
        if (generation.endMs > 0L) {
            generation.endMs = strictlyAfterChargingStart(
                    generation.startMs, generation.endMs);
        }
        generation.endSoc = finiteOrNaN(source, "endSoc");
        generation.startRange = source.optInt("startRange", -1);
        generation.startOdometer = source.optInt("startOdometer", -1);
        generation.gun = source.optInt("gun", -1);
        generation.timeToFull = source.optInt("timeToFull", -1);
        generation.lat = finiteOrZero(source, "lat");
        generation.lng = finiteOrZero(source, "lng");
        generation.peakPower = finiteOrZero(source, "peak");
        generation.powerSum = finiteOrZero(source, "powerSum");
        generation.powerCount = Math.max(0, source.optInt("powerCount", 0));
        generation.endTempHigh = finiteOrNaN(source, "tempHigh");
        generation.endTempLow = finiteOrNaN(source, "tempLow");
        generation.endTempAvg = finiteOrNaN(source, "tempAvg");
        if (Double.isNaN(generation.endTempHigh)) generation.endTempHigh = -999;
        if (Double.isNaN(generation.endTempLow)) generation.endTempLow = -999;
        if (Double.isNaN(generation.endTempAvg)) generation.endTempAvg = -999;
        generation.counterOwner = emptyToNull(source.optString("counterOwner", ""));
        JSONObject counter = source.optJSONObject("counter");
        generation.counter.restoreState(
                counter != null ? counterStateFromJson(counter) : null);
        generation.previousCounterKwh = finiteOrNaN(source, "previousCounter");
        generation.counterBaselinePending = source.optBoolean("baselinePending", true);
        generation.counterBaselinePendingSinceMs =
                source.optLong("baselinePendingSince", start);
        generation.counterCandidateKwh = finiteOrNaN(source, "counterCandidate");
        generation.counterCandidateAtMs = source.optLong("counterCandidateAt", 0L);
        generation.counterLatestKwh = finiteOrNaN(source, "counterLatest");
        generation.counterLatestAtMs = source.optLong("counterLatestAt", 0L);
        generation.provisionalExternalKwh = finiteOrNaN(source, "provisionalExternal");
        generation.provisionalExternalAtMs = source.optLong("provisionalExternalAt", 0L);
        generation.provisionalExternalUnitDivisor = validCounterUnitDivisor(
                finiteOrNaN(source, "provisionalExternalDivisor"));
        generation.integrationTruncated =
                source.optBoolean("integrationTruncated", false);
        generation.closeIsDc = source.optInt("closeIsDc", -1);
        generation.typeVerdict = source.optInt(
                "typeVerdict",
                generation.closeIsDc == ChargingTypeClassifier.AC
                                || generation.closeIsDc == ChargingTypeClassifier.DC
                        ? generation.closeIsDc
                        : ChargingTypeClassifier.classify(
                                generation.gun, generation.peakPower));
        generation.resumeBlocked = source.optBoolean("resumeBlocked", false);
        JSONObject continuation = source.optJSONObject("continuation");
        if (continuation != null) {
            long rowStart = continuation.optLong("rowStart", 0L);
            if (rowStart > 0L) {
                generation.continuationOffer = new ContinuationOffer(
                        rowStart,
                        finiteOrNaN(continuation, "endpointKwh"),
                        emptyToNull(continuation.optString("source", "")),
                        finiteOrNaN(continuation, "startSoc"),
                        finiteOrNaN(continuation, "fullScaleKwh"));
            }
        }
        generation.closePricing = pricingFromJson(source.optJSONObject("pricing"));
        JSONArray samples = source.optJSONArray("samples");
        if (samples != null) {
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.optJSONObject(i);
                if (sample == null) continue;
                long t = sample.optLong("t", start);
                double power = finiteOrNaN(sample, "power");
                if (Double.isNaN(power)) continue;
                generation.samples.add(new DeferredChargingSample(
                        t, power,
                        finiteOrNaN(sample, "soc"),
                        finiteOrNaN(sample, "temp"),
                        finiteOrNaN(sample, "tempHigh"),
                        finiteOrNaN(sample, "tempLow")));
            }
        }
        // Restore byte-for-byte here. Reconciliation marks only still-live generations as an outage
        // gap after durable H2 state has been merged and an independent estimate can be computed.
        return generation;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Remove journal entries whose transaction committed before the process died, and retain only
     * identities that still need materialization.
     */
    private void reconcileChargingLifecycleJournalWithDatabase() {
        if (!chargingLifecycleJournalLoaded || chargingLifecycleJournalReadFailed
                || connection == null) return;
        try {
            if (!reconcilePendingChargingMaintenanceIntent(null)) {
                throw new java.sql.SQLException(
                        "could not reconcile restored charging maintenance intent");
            }
            if (!reserveDeferredChargingIdentities()) {
                throw new java.sql.SQLException(
                        "could not reserve restored deferred charging identities");
            }
            if (wasCharging && chargingStartTime > 0L) {
                if (!isDurablyOpenSession(chargingStartTime)) {
                    clearRecoveredActiveLifecycle();
                } else {
                    mergeRecoveredActiveCounterWithDatabase();
                    reconcileRecoveredActivePowerStatsWithDatabase();
                    reconcileRecoveredActivePowerGap();
                }
            }
            java.util.Iterator<DeferredChargingGeneration> iterator =
                    deferredPhysicalGenerations.iterator();
            while (iterator.hasNext()) {
                DeferredChargingGeneration generation = iterator.next();
                int state = durableChargingRowState(generation.startMs);
                if (state == 2) {
                    iterator.remove();
                } else if (state == 1 && !wasCharging) {
                    adoptDeferredGenerationAsRecoveredActive(generation);
                    iterator.remove();
                    mergeRecoveredActiveCounterWithDatabase();
                    reconcileRecoveredActivePowerStatsWithDatabase();
                } else if (!generation.isEnded()
                        && generation.counter.hasSeriesState()) {
                    generation.counter.beginGapReconciliation(
                            outageGapEstimate(
                                    generation.startSoc,
                                    generation.counter.energyKwh()));
                }
            }
            sessionInputsFenced = !deferredPhysicalGenerations.isEmpty();
            persistChargingLifecycleJournal();
        } catch (Exception e) {
            logger.warn("Could not reconcile charging lifecycle journal: " + e.getMessage());
        }
    }

    private void mergeRecoveredActiveCounterWithDatabase() throws Exception {
        CounterRestoreState durable = readCounterRestoreState(chargingStartTime);
        com.overdrive.app.charging.ChargeCounterAccumulator.State journal =
                chargingCounter.snapshotState();
        boolean durableWins =
                com.overdrive.app.charging.ChargeCounterAccumulator.preferSecondCompleteState(
                        journal, durable.exactState);
        com.overdrive.app.charging.ChargeCounterAccumulator.State selected =
                com.overdrive.app.charging.ChargeCounterAccumulator.newestCompleteState(
                        journal, durable.exactState, durable.incomplete);
        chargingCounter.restoreState(selected);
        if (durableWins) counterOwner = durable.source;
        if (chargingCounter.hasBaseline()) {
            lastSessionCounterKwh = chargingCounter.lastRawKwh();
            counterBaselinePending = false;
            counterBaselinePendingSinceMs = 0L;
        }
        // A frozen close belongs to a physical generation that already ended. Only a still-live
        // generation gets an outage fence; otherwise startup would invent a second gap after its end.
        boolean ended = pendingCloseSessionStart == chargingStartTime || optOutClosePending;
        if (!ended && chargingCounter.hasSeriesState()) {
            chargingCounter.beginGapReconciliation(
                    outageGapEstimate(chargingStartSoc, chargingCounter.energyKwh()));
        }
    }

    /**
     * Reconcile the journal's coarse peak with fine samples that committed after its last snapshot.
     *
     * <p>This runs once when an open session is restored or adopted. Steady-state outbound telemetry
     * then reads the in-memory peak without issuing a database aggregate on every publication.
     */
    private void reconcileRecoveredActivePowerStatsWithDatabase() {
        chargingPeakPower = resolvePeakKw(chargingStartTime, chargingPeakPower);
    }

    private void reconcileRecoveredActivePowerGap() throws Exception {
        boolean ended = pendingCloseSessionStart == chargingStartTime || optOutClosePending;
        if (ended) {
            recoveredActivePowerGapAtMs = 0L;
            return;
        }
        if (recoveredActivePowerGapAtMs <= 0L) {
            recoveredActivePowerGapAtMs = strictlyAfterChargingStart(
                    chargingStartTime, System.currentTimeMillis());
            chargingLifecycleJournalDirty = true;
            if (!persistChargingLifecycleJournal()) {
                throw new java.io.IOException(
                        "recovered charging power-gap intent was not durable");
            }
        }
        final long sessionStart = chargingStartTime;
        final long boundaryAt = recoveredActivePowerGapAtMs;
        try {
            runInTransaction(() -> {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + TABLE_CPS
                                + " (session_start_time, t, power_kw, soc, temp, temp_high, temp_low)"
                                + " SELECT ?, ?, ?, NULL, NULL, NULL, NULL"
                                + " WHERE NOT EXISTS (SELECT 1 FROM " + TABLE_CPS
                                + " WHERE session_start_time = ? AND t = ? AND power_kw = ?);")) {
                    insert.setLong(1, sessionStart);
                    insert.setLong(2, boundaryAt);
                    insert.setDouble(3, STOP_BOUNDARY_POWER_KW);
                    insert.setLong(4, sessionStart);
                    insert.setLong(5, boundaryAt);
                    insert.setDouble(6, STOP_BOUNDARY_POWER_KW);
                    insert.executeUpdate();
                }
                try (PreparedStatement mark = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING
                                + " SET integration_truncated = 1"
                                + " WHERE start_time = ? AND end_time IS NULL;")) {
                    mark.setLong(1, sessionStart);
                    if (mark.executeUpdate() != 1) {
                        throw new java.sql.SQLException(
                                "recovered power-gap marker found no open session");
                    }
                }
            });
        } catch (Exception e) {
            if (!isRecoveredActivePowerGapDurable(sessionStart, boundaryAt)) throw e;
        }
        recoveredActivePowerGapAtMs = 0L;
        chargingLifecycleJournalDirty = true;
        persistChargingLifecycleJournal();
    }

    private boolean isRecoveredActivePowerGapDurable(long sessionStart, long boundaryAt) {
        Connection c = connection;
        if (c == null) return false;
        try (PreparedStatement row = c.prepareStatement(
                "SELECT integration_truncated FROM " + TABLE_CHARGING
                        + " WHERE start_time = ? AND end_time IS NULL;");
             PreparedStatement sample = c.prepareStatement(
                     "SELECT 1 FROM " + TABLE_CPS
                             + " WHERE session_start_time = ? AND t = ? AND power_kw = ?;")) {
            row.setLong(1, sessionStart);
            try (ResultSet rs = row.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != 1) return false;
            }
            sample.setLong(1, sessionStart);
            sample.setLong(2, boundaryAt);
            sample.setDouble(3, STOP_BOUNDARY_POWER_KW);
            try (ResultSet rs = sample.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private double outageGapEstimate(double startSoc, double alreadyAccountedKwh) {
        try {
            BatterySocData current = VehicleDataMonitor.getInstance().getBatterySoc();
            if (current == null || current.socPercent < 0 || current.socPercent > 100
                    || Double.isNaN(startSoc)) {
                return Double.NaN;
            }
            com.overdrive.app.abrp.SohEstimator soh = getSohEstimator();
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                    soh != null ? soh.getCapacitySohSnapshot() : null;
            double total = com.overdrive.app.charging.SessionEnergyResolver.socEstimateKwh(
                    current.socPercent - startSoc,
                    capacitySoh != null ? capacitySoh.getNominalCapacityKwh() : 0,
                    capacitySoh != null && capacitySoh.hasDisplaySoh()
                            ? capacitySoh.getDisplaySoh() : Double.NaN);
            return !Double.isNaN(total) && total > alreadyAccountedKwh
                    ? total - Math.max(0.0, alreadyAccountedKwh) : Double.NaN;
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    /** 0=absent, 1=open, 2=closed. */
    private int durableChargingRowState(long start) throws Exception {
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT end_time FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
            p.setLong(1, start);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return 0;
                rs.getLong(1);
                return rs.wasNull() ? 1 : 2;
            }
        }
    }

    private void clearRecoveredActiveLifecycle() {
        long clearedStart = chargingStartTime;
        wasCharging = false;
        chargingStartTime = 0L;
        chargingTypeVerdict = ChargingTypeClassifier.UNKNOWN;
        pendingCloseSessionStart = 0L;
        pendingCloseAtMs = 0L;
        pendingCloseSoc = Double.NaN;
        pendingCloseCounterCaptured = false;
        pendingClosePricing = null;
        optOutClosePending = false;
        optOutBoundaryMs = 0L;
        optOutBoundarySoc = Double.NaN;
        optOutCounterCaptured = false;
        optOutClosePricing = null;
        chargingCounter.reset();
        recoveredActivePowerGapAtMs = 0L;
        counterOwner = null;
        clearCounterBaselineCandidates();
        forgetChargingCloseTargetAliases(clearedStart);
    }

    private void adoptDeferredGenerationAsRecoveredActive(
            DeferredChargingGeneration generation) {
        wasCharging = true;
        chargingStartTime = generation.startMs;
        chargingStartSoc = generation.startSoc;
        chargingPeakPower = generation.peakPower;
        chargingPowerSum = generation.powerSum;
        chargingPowerCount = generation.powerCount;
        chargingStartRange = generation.startRange;
        chargingStartOdometer = generation.startOdometer;
        chargingGunState = generation.gun;
        chargingTypeVerdict = generation.typeVerdict;
        chargingTimeToFullMin = generation.timeToFull;
        chargingStartLat = generation.lat;
        chargingStartLng = generation.lng;
        chargingCounter.restoreState(generation.counter.snapshotState());
        recoveredActivePowerGapAtMs = 0L;
        counterOwner = generation.counterOwner;
        counterBaselinePending = generation.counterBaselinePending;
        counterBaselinePendingSinceMs = generation.counterBaselinePendingSinceMs;
        counterBaselineCandidateKwh = generation.counterCandidateKwh;
        counterBaselineCandidateAtMs = generation.counterCandidateAtMs;
        counterBaselineLatestKwh = generation.counterLatestKwh;
        counterBaselineLatestAtMs = generation.counterLatestAtMs;
        provisionalExternalKwh = generation.provisionalExternalKwh;
        provisionalExternalAtMs = generation.provisionalExternalAtMs;
        provisionalExternalUnitDivisor =
                generation.provisionalExternalUnitDivisor;
        if (chargingCounter.hasBaseline()) {
            lastSessionCounterKwh = chargingCounter.lastRawKwh();
        }
        if (generation.isEnded()) {
            pendingCloseSessionStart = generation.startMs;
            pendingCloseAtMs = strictlyAfterChargingStart(
                    generation.startMs, generation.endMs);
            pendingCloseSoc = generation.endSoc;
            pendingCloseCounterCaptured = true;
            pendingClosePricing = generation.closePricing;
            pendingCloseIsDc = generation.closeIsDc;
            pendingCloseResumeBlocked = generation.resumeBlocked;
            pendingCloseTempHigh = generation.endTempHigh;
            pendingCloseTempLow = generation.endTempLow;
            pendingCloseTempAvg = generation.endTempAvg;
        }
    }

    private boolean trackWithAnalyticsTemporarilyEnabled(
            boolean charging, double soc, double power, long now) {
        boolean enabled = chargingAnalyticsEnabled;
        try {
            chargingAnalyticsEnabled = true;
            return trackChargingSession(charging, soc, power, now);
        } finally {
            chargingAnalyticsEnabled = enabled;
        }
    }

    /**
     * Materialize queued generations in physical order. Completed generations ahead of the newest
     * generation are opened and closed before the current one is exposed as the durable open row.
     */
    private boolean materializeDeferredGenerationsForOpenEdge(
            double edgeSoc, double power, long now) {
        while (true) {
            if (wasCharging && pendingCloseSessionStart == chargingStartTime
                    && !deferredPhysicalGenerations.isEmpty()) {
                if (!trackChargingSession(false, pendingCloseSoc, 0, pendingCloseAtMs)) {
                    return false;
                }
                continue;
            }
            DeferredChargingGeneration generation = deferredPhysicalGenerations.peekFirst();
            if (generation == null) {
                return wasCharging || trackChargingSession(true, edgeSoc, power, now);
            }
            boolean ended = generation.isEnded();
            if (!wasCharging
                    && !trackChargingSession(true, generation.startSoc, 0, generation.startMs)) {
                return false;
            }
            // Capture ended before SESSION START consumes the queue head. A sole completed
            // generation still has to close before the actual current physical generation opens.
            if (ended) {
                if (!trackChargingSession(false, pendingCloseSoc, 0, pendingCloseAtMs)) {
                    return false;
                }
                continue;
            }
            return true;
        }
    }

    /** Drain every restored generation when the detector proves the vehicle is physically off. */
    private boolean materializeDeferredGenerationsForClosedEdge(double edgeSoc, long now) {
        while (wasCharging || !deferredPhysicalGenerations.isEmpty()) {
            if (wasCharging) {
                double closeSoc = !Double.isNaN(pendingCloseSoc) ? pendingCloseSoc : edgeSoc;
                long closeAt = pendingCloseAtMs > 0L ? pendingCloseAtMs : now;
                if (!trackChargingSession(false, closeSoc, 0, closeAt)) return false;
                continue;
            }

            DeferredChargingGeneration generation = deferredPhysicalGenerations.peekFirst();
            if (generation == null) break;
            if (!generation.isEnded()) {
                // The process disappeared while this queued generation was live. Detector OFF proves
                // it ended, but not when during the outage; preserve the current endpoint and mark the
                // power integral as a floor.
                generation.integrationTruncated = true;
                endDeferredPhysicalGeneration(generation, now, edgeSoc, false);
            }
            if (!trackChargingSession(true, generation.startSoc, 0, generation.startMs)) {
                return false;
            }
        }
        return !wasCharging && deferredPhysicalGenerations.isEmpty();
    }

    /** Persist every enabled deferred interval after the user disables analytics. */
    private boolean materializeDeferredGenerationsAtOptOut() {
        while ((wasCharging && pendingCloseResumeBlocked)
                || !deferredPhysicalGenerations.isEmpty()) {
            if (wasCharging) {
                if (!trackWithAnalyticsTemporarilyEnabled(
                        false, pendingCloseSoc, 0, pendingCloseAtMs)) {
                    return false;
                }
                continue;
            }
            DeferredChargingGeneration generation = deferredPhysicalGenerations.peekFirst();
            if (generation == null) break;
            if (!trackWithAnalyticsTemporarilyEnabled(
                    true, generation.startSoc, 0, generation.startMs)) {
                return false;
            }
        }
        return true;
    }

    public synchronized boolean onChargingEdge(boolean isCharging) {
        long expectedCloseStart = !isCharging && wasCharging ? chargingStartTime : 0L;
        return onChargingEdge(isCharging, expectedCloseStart, true);
    }

    /**
     * Apply one manager-owned edge, fencing a close to the exact row captured at the physical edge.
     *
     * <p>A delayed retry may run after that row has already closed and a replacement is live. In that
     * case the expected row's durable postcondition is checked and no mutable lifecycle state is
     * touched. {@code drainDeferredOnClose} is false while a newer physical generation is still live.
     */
    public synchronized boolean onChargingEdge(
            boolean isCharging, long expectedCloseStart, boolean drainDeferredOnClose) {
        if (!isInitialized || connection == null) {
            if (!isCharging) return false;
            if (wasCharging && chargingStartTime > 0L) {
                return !chargingLifecycleJournalDirty
                        || persistChargingLifecycleJournal();
            }
            return journalPhysicalChargingStartWithoutDatabase();
        }
        if (!reconcilePendingActiveChargingReplacement()) return false;
        if (!isCharging && expectedCloseStart > 0L) {
            expectedCloseStart = resolveChargingCloseTargetStart(expectedCloseStart);
        }
        // A stale close may arrive after a replacement became live. Persisting that replacement's
        // current image is safe and cannot mutate H2; acknowledging the old close while this image
        // remains volatile would lose the replacement on a power cut.
        if (chargingLifecycleJournalDirty && !persistChargingLifecycleJournal()) return false;
        if (!isCharging && expectedCloseStart > 0L
                && (!wasCharging || chargingStartTime != expectedCloseStart)) {
            return isChargingSessionCloseSatisfied(expectedCloseStart);
        }
        if (!reserveDeferredChargingIdentities()) return false;
        if (!isCharging) chargingLifecycleHold = false;
        if (isCharging == wasCharging
                && deferredPhysicalGenerations.isEmpty()
                && !(pendingCloseResumeBlocked && wasCharging)) {
            return !counterProgressDirty || persistCounterProgress();
        }
        try {
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            if (monitor == null) return false;
            BatterySocData socData = monitor.getBatterySoc();
            if (socData == null && isCharging) return false;
            // A stop must still close/fence the row if the live SOC accessor drops at the edge. Use
            // the most recent recorded value, then the session start, rather than leaving it OPEN
            // until an unrelated two-minute tick happens to recover.
            double edgeSoc = socData != null ? socData.socPercent
                    : lastRecordedSoc >= 0 ? lastRecordedSoc : chargingStartSoc;
            ChargingStateData cs = monitor.getChargingState();
            // Same rule as the tick: an estimated/placeholder kW must not seed peak/avg.
            double power = isCharging && cs != null && !cs.isEstimated
                    && isFinite(cs.chargingPowerKW)
                    && cs.chargingPowerKW > 0
                    && cs.chargingPowerKW <= 500
                    ? cs.chargingPowerKW : 0;
            long now = System.currentTimeMillis();
            if (!chargingAnalyticsEnabled && pendingCloseResumeBlocked && wasCharging) {
                if (!trackWithAnalyticsTemporarilyEnabled(
                        false, pendingCloseSoc, 0, pendingCloseAtMs)) {
                    return false;
                }
                if (!materializeDeferredGenerationsAtOptOut()) return false;
                return !isCharging;
            }
            if (isCharging && !deferredPhysicalGenerations.isEmpty()) {
                if (!materializeDeferredGenerationsForOpenEdge(edgeSoc, power, now)) return false;
            } else {
                if (!trackChargingSession(isCharging, edgeSoc, power, now)) return false;
                if (!isCharging && !chargingAnalyticsEnabled
                        && !deferredPhysicalGenerations.isEmpty()
                        && !materializeDeferredGenerationsAtOptOut()) {
                    return false;
                }
            }
            if (!isCharging) {
                if (drainDeferredOnClose
                        && chargingAnalyticsEnabled && !deferredPhysicalGenerations.isEmpty()
                        && !materializeDeferredGenerationsForClosedEdge(edgeSoc, now)) {
                    return false;
                }
                return expectedCloseStart > 0L
                        ? isChargingSessionCloseSatisfied(expectedCloseStart)
                        : !wasCharging && deferredPhysicalGenerations.isEmpty();
            }
            // Try to establish the counter baseline NOW as well. Opening the row early is only half
            // the fix: the baseline is left pending at SESSION START (deliberately — the counter may
            // still hold the previous charge's total), and it was then only resolved on the 2-minute
            // tick. Whatever the charger delivered before that first tick was therefore absorbed into
            // the baseline and never counted as energy — on a DC session that is several kWh. This
            // runs the same acceptance test immediately, so a counter that has already re-zeroed
            // anchors at the true start; one that has not stays pending exactly as before.
            if (counterBaselinePending && wasCharging) {
                double counterNow = snapshotChargeCounterKwh();
                if (!Double.isNaN(counterNow)) {
                    rememberCounterBaselineCandidate(counterNow, now);
                }
                if (!Double.isNaN(counterNow)
                        && (Double.isNaN(lastSessionCounterKwh) || counterNow < lastSessionCounterKwh)) {
                    // Ask about continuation BEFORE claiming this as a fresh baseline. The callback path
                    // does; this one did not, so a value that first became available on the edge was taken
                    // as a new session's start and an interrupted 10 -> 13 still lost 3 kWh.
                    if (tryLateContinuation(counterNow, now)) {
                        counterBaselinePending = false;
                        counterBaselinePendingSinceMs = 0;
                        clearCounterBaselineCandidates();
                        lastSessionCounterKwh = counterNow;
                        return persistCounterProgress();
                    }
                    counterBaselinePending = false;
                    counterBaselinePendingSinceMs = 0;
                    consumeSupersededSweepMarker("a baseline was established on the charging edge");
                    chargingCounter.observe(counterNow, now);
                    clearCounterBaselineCandidates();
                    counterProgressDirty = true;
                    lastSessionCounterKwh = counterNow;
                    return persistCounterProgress();
                }
            }
            return !counterProgressDirty || persistCounterProgress();
        } catch (Exception e) {
            logger.debug("onChargingEdge failed: " + e.getMessage());
            if (isSqlFailure(e)) noteWriteFailed();
            return false;
        }
    }

    /**
     * Offer a freshly-observed charged-energy counter reading directly to the open session.
     *
     * <p>Pushed by the collector the moment a value is admitted, so the accumulator does not have to
     * wait for the next {@link #SAMPLE_INTERVAL_MS} tick to see it. That wait is what still lost the
     * opening energy of a session: the vehicle resets its counter to 0 shortly after the charge starts,
     * and if that 0 arrived between the fused ON edge and the first tick, the accumulator never saw it —
     * the tick read whatever the counter had climbed to by then and made THAT the baseline, silently
     * discarding everything delivered in between. An in-flight poll overwriting the snapshot has the
     * same effect, and this path is immune to it because the value is handed over directly.
     *
     * <p>Only ever ESTABLISHES a pending baseline or advances an established one — it cannot open or
     * close a session, so a stray callback outside a session is a no-op.
     *
     * @param counterKwh the admitted raw counter reading, kWh (0 is valid and is the ideal baseline)
     */
    public synchronized void onChargeCounterObserved(String source, double counterKwh) {
        if (!Double.isFinite(counterKwh) || counterKwh < 0) return;
        if (source == null) return;
        final double rawCounterValue = counterKwh;
        // APPLY THE SAME UNIT CALIBRATION THE RATE PATH APPLIES. ChargeRateResolver corrects a
        // hectowatt-scaled counter's derived POWER; without the matching correction here the raw value
        // would flow straight into session ENERGY, leaving a trim with believable kW next to a total
        // 100x wrong. Energy is what gets priced, so it is the worse half to leave uncorrected. The
        // divisor is 1.0 unless the counter's own slope disagrees with the kWh-grounded reference by
        // very nearly exactly the unit factor.
        double unitDiv = 1.0;
        try {
            unitDiv = com.overdrive.app.monitor.ChargeRateResolver.counterUnitDivisor(source);
            if (unitDiv > 1.0) counterKwh = counterKwh / unitDiv;
        } catch (Throwable ignored) {}
        if (!Double.isFinite(counterKwh) || counterKwh < 0) return;
        if (!chargingAnalyticsEnabled || optOutClosePending) return;
        // Deferred generations are owned by the atomic sidecar journal, not H2. Route their observations
        // before any JDBC/replacement gate so a failed A close and unavailable reconnect cannot discard
        // B/C's only monotonic-counter evidence.
        if (sessionInputsFenced && !deferredPhysicalGenerations.isEmpty()) {
            observeDeferredPhysicalCounter(
                    currentDeferredPhysicalGeneration(), source, counterKwh, unitDiv,
                    System.currentTimeMillis());
            return;
        }
        boolean databaseAvailable = isInitialized && connection != null;
        if (databaseAvailable) {
            if (!reconcilePendingActiveChargingReplacement()) return;
        } else if (pendingChargingMaintenanceIntent != null
                || pendingActiveReplacement != null) {
            return;
        }
        // PROVISIONAL vs COMMITTED. An external source may be fed here BEFORE the classifier has ruled,
        // because its COUNTER verdict needs a 20-minute rising span and waiting for it discards the
        // opening portion of every external-counter-only session — the same loss the pre-session buffer
        // exists to prevent, just at a different stage.
        //
        // But an unclassified value must not be integrated: if it turns out to be a RATE, treating it as
        // kWh is nonsense. So hold the earliest reading only, and commit it as the baseline once the
        // verdict confirms COUNTER. The baseline is all that is needed — every later rise is measured
        // against it, so nothing between is lost by not accumulating yet.
        boolean isExternal = com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL.equals(source);
        if (isExternal && !com.overdrive.app.byd.ChargeSourceClassifier.isCounter(source)) {
            if (com.overdrive.app.byd.ChargeSourceClassifier.isRate(source)) {
                provisionalExternalKwh = Double.NaN;   // ruled a rate; the held value is meaningless
                provisionalExternalAtMs = 0L;
                provisionalExternalUnitDivisor = 1.0;
                clearPreSessionProvisionalExternal();
                persistChargingLifecycleJournal();
                return;
            }
            if (!wasCharging || chargingStartTime <= 0L) {
                if (Double.isNaN(preSessionProvisionalExternalRaw)
                        || rawCounterValue < preSessionProvisionalExternalRaw) {
                    preSessionProvisionalExternalRaw = rawCounterValue;
                    preSessionProvisionalExternalAtMs = System.currentTimeMillis();
                    preSessionProvisionalExternalUnitDivisor =
                            validCounterUnitDivisor(unitDiv);
                    persistChargingLifecycleJournal();
                }
                return;
            }
            // Still UNKNOWN. Keep the LOWEST reading seen — the closest observed point to the session's
            // true start, and immune to how long classification took.
            if (Double.isNaN(provisionalExternalKwh)
                    || counterValueInRawFrame(counterKwh, unitDiv)
                    < counterValueInRawFrame(
                            provisionalExternalKwh, provisionalExternalUnitDivisor)) {
                provisionalExternalKwh = counterKwh;
                provisionalExternalAtMs = System.currentTimeMillis();
                provisionalExternalUnitDivisor = validCounterUnitDivisor(unitDiv);
                persistChargingLifecycleJournal();
            }
            return;
        }
        if (isExternal && !wasCharging && chargingStartTime <= 0L
                && !Double.isNaN(preSessionProvisionalExternalRaw)) {
            double held = preSessionProvisionalExternalRaw
                    / validCounterUnitDivisor(unitDiv);
            long heldAt = preSessionProvisionalExternalAtMs;
            if (held <= counterKwh) {
                preSessionCounterLowKwh = held;
                preSessionCounterAtMs = heldAt > 0L
                        ? heldAt : System.currentTimeMillis();
                preSessionCounterSource = source;
            }
            clearPreSessionProvisionalExternal();
            persistChargingLifecycleJournal();
        }
        // The provisional external baseline is committed BELOW, only once ownership arbitration has
        // granted this source the accumulator. Committing it here — before arbitration — hijacked an
        // ALREADY-ACTIVE capacity series: it overwrote counterOwner and injected a value from a
        // different counter into a running monotonic series, which is exactly the corruption the
        // one-counter rule exists to prevent.
        // ONE COUNTER PER SESSION. There is a single accumulator, and it tracks one monotonic series —
        // so feeding it two independent counters interleaves them, and their alternating values read as
        // rises and resets of one counter. That silently corrupts session energy, which is worse than
        // ignoring a source. Claim the accumulator for the first counter that reports and ignore any
        // other for the rest of the session; the dedicated capacity counter is preferred when both
        // appear, because its unit and per-session semantics are documented rather than inferred.
        if (counterOwner == null) {
            bindCounterOwner(source);
            if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL.equals(source)) {
                // Now that this source owns the accumulator, adopt the reading held while it was still
                // unclassified: that is the closest observed point to the session's start, and using it
                // as the baseline credits the energy delivered before classification completed.
                double held = convertCounterUnitFrame(
                        provisionalExternalKwh,
                        provisionalExternalUnitDivisor, unitDiv);
                long heldAt = provisionalExternalAtMs;
                provisionalExternalKwh = Double.NaN;
                provisionalExternalAtMs = 0L;
                provisionalExternalUnitDivisor = 1.0;
                // Classification can latch a divisor on the confirming observation. Convert the
                // pre-verdict reading from its captured frame before comparing or baselining it.
                if (!Double.isNaN(held) && held <= counterKwh) {
                    logger.info(String.format(java.util.Locale.US,
                            "External counter confirmed and owns the accumulator; baselining at the"
                            + " earliest pre-verdict reading %.3f kWh (now %.3f) — crediting %.3f kWh"
                            + " delivered before classification completed",
                            held, counterKwh, counterKwh - held));
                    counterBaselinePending = false;
                    counterBaselinePendingSinceMs = 0;
                    consumeSupersededSweepMarker("the external counter's pre-verdict reading became this"
                            + " session's baseline");
                    chargingCounter.observe(held, heldAt > 0 ? heldAt : System.currentTimeMillis());
                    clearCounterBaselineCandidates();
                }
            }
        } else if (!counterOwner.equals(source)) {
            boolean incomingIsPreferred =
                    com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY.equals(source);
            if (!incomingIsPreferred) return;   // keep the owner; ignore the challenger
            // DO NOT REBIND ONCE ENERGY HAS BEEN GATHERED. Rebinding restarts the accumulation, and the
            // incumbent's total cannot be carried into a different series — so it is thrown away. That
            // is a straight loss whenever the incumbent has measured anything, and it is worst in the
            // case it was most likely to fire: a session RESTORED after a restart re-establishes
            // external ownership carrying all its pre-restart energy, and the first capacity reading
            // afterwards would discard exactly that. Preferring the documented counter is only worth
            // anything at the START of a session, before either source has measured energy.
            // Guard on ANY series state, not just a non-zero total. A session restored after a restart
            // holds a baseline, a last value and a pending gap reconciliation while its accumulated
            // energy is still exactly 0 — nothing has risen yet — so an energy-only test left precisely
            // that state unprotected and discarded the pre-restart endpoints with it.
            if (chargingCounter.hasSeriesState()) {
                logger.info("Declining to rebind the charged-energy accumulator from '" + counterOwner
                        + "' to '" + source + "': the incumbent series is live (measured "
                        + String.format(java.util.Locale.US, "%.3f", chargingCounter.energyKwh())
                        + " kWh so far) and rebinding would discard its baseline and any pending gap"
                        + " reconciliation. Preference only applies before a series exists.");
                return;
            }
            logger.info("Charged-energy accumulator rebinding from '" + counterOwner + "' to '"
                    + source + "' (documented counter takes precedence, nothing measured yet)");
            counterOwner = source;
            chargingCounter.reset();
            chargingCounter.markPersistenceMetadataChanged();
            provisionalExternalKwh = Double.NaN;   // belongs to the source being displaced
            provisionalExternalAtMs = 0L;
            provisionalExternalUnitDivisor = 1.0;
            lastSessionCounterKwh = Double.NaN;
            counterBaselinePending = true;
            counterBaselinePendingSinceMs = System.currentTimeMillis();
            clearCounterBaselineCandidates();
        }
        if (!wasCharging || chargingStartTime <= 0) {
            // NO ROW YET — but this reading still matters. Detection is not instantaneous: L3 needs
            // three consecutive observations (~15 s at the ACC-on cadence, longer parked) and the
            // movement channel does not itself force a recompute, so the vehicle's post-start reset to
            // 0 routinely arrives BEFORE the session row exists. Dropping it meant the row, once
            // opened, baselined at an already-incremented counter and silently lost the opening energy.
            //
            // Remember the LOWEST reading seen while no session was open. Lowest, not latest: the
            // counter only rises within a charge, so the minimum is the closest thing observed to the
            // true zero point, and it cannot be inflated by however long detection took. SESSION START
            // picks this up if it looks like a plausible baseline.
            // REFUSE a reading taken at a TERMINAL state. The collector deliberately admits a final
            // counter callback so the closing session keeps its tail — but if the close already won the
            // race, that same value arrives here with no session open and was buffered as the NEXT
            // charge's pre-session baseline. The next charge then anchors at the previous total and loses
            // its real opening interval once the vehicle resets the counter.
            if (isTerminalChargingStateNow()) return;
            if (Double.isNaN(preSessionCounterLowKwh) || counterKwh < preSessionCounterLowKwh
                    || !source.equals(preSessionCounterSource)) {
                // A reading from a DIFFERENT source replaces rather than competes: "lowest" is only
                // meaningful within one series, and the preferred source should win outright.
                boolean differentSource = !source.equals(preSessionCounterSource);
                boolean preferIncoming = differentSource
                        && com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY.equals(source);
                if (!differentSource || preferIncoming || preSessionCounterSource == null) {
                    preSessionCounterLowKwh = counterKwh;
                    preSessionCounterAtMs = System.currentTimeMillis();
                    preSessionCounterSource = source;
                    persistChargingLifecycleJournal();
                }
            }
            return;
        }
        // A session is open, so any pre-session candidate has served its purpose.
        preSessionCounterLowKwh = Double.NaN;
        preSessionCounterAtMs = 0L;
        preSessionCounterSource = null;
        try {
            long now = System.currentTimeMillis();
            if (counterBaselinePending) {
                rememberCounterBaselineCandidate(counterKwh, now);
                if (!databaseAvailable) {
                    persistChargingLifecycleJournal();
                    return;
                }
                // Same freshness test the tick applies: only accept a reading that cannot still be the
                // previous session's leftover. A genuinely re-zeroed counter reads lower than the last
                // value we saw (and 0 always does).
                if (!Double.isNaN(lastSessionCounterKwh) && counterKwh >= lastSessionCounterKwh) return;
                // FIRST CALLBACK OF A SESSION: lastSessionCounterKwh is NaN, so any value is accepted as
                // the baseline — including one that is actually a CONTINUATION of an interrupted charge.
                // The retry lived only on the periodic path, so a restart gap of 10 -> 13 lost 3 kWh here.
                // Ask before baselining; tryLateContinuation seeds the accumulator itself when it applies.
                if (tryLateContinuation(counterKwh, now)) {
                    counterBaselinePending = false;
                    counterBaselinePendingSinceMs = 0;
                    clearCounterBaselineCandidates();
                    lastSessionCounterKwh = counterKwh;
                    persistCounterProgress();
                    return;
                }
                counterBaselinePending = false;
                counterBaselinePendingSinceMs = 0;
                clearCounterBaselineCandidates();
                // A RESET WAS OBSERVED, so this is definitively a NEW charge — the vehicle re-zeroed its
                // counter. That supersedes any interrupted row's offer, and the marker must be consumed
                // rather than left claimable by a later unrelated session for the rest of the lookback.
                // This path baselines normally and never retries continuation, so nothing else clears it.
                consumeSupersededSweepMarker("a counter reset was observed after session start");
            }
            // A reading above the accumulator's assumed ceiling would be DROPPED by its domain gate. On
            // a session restored from a legacy row that recorded no source, the ceiling is a guess — so
            // treat the reading as the correction rather than discarding it. Only widens, and only
            // before any wrap has been credited (see widenFullScaleKwh).
            if (counterKwh > chargingCounter.fullScaleKwh()
                    && counterKwh <= EXTERNAL_COUNTER_FULL_SCALE_KWH) {
                if (chargingCounter.widenFullScaleKwh(
                            counterScaleForSource(
                                    com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL))
                        && counterOwner == null) {
                    // Only the external register is this wide, so the reading also identifies the owner.
                    counterOwner = com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL;
                }
            }
            chargingCounter.observe(counterKwh, now);
            counterProgressDirty = true;
            lastSessionCounterKwh = counterKwh;
            if (databaseAvailable) persistCounterProgress();
            else persistChargingLifecycleJournal();
        } catch (Exception e) {
            logger.debug("onChargeCounterObserved failed: " + e.getMessage());
        }
    }

    // synchronized: an EXTERNAL entry point (ChargingSessionManager.init) that
    // writes session rows + daily rollups. repriceSessionsForTariff runs an explicit
    // transaction on the shared JDBC Connection, and autoCommit is connection-level,
    // so an unserialized write here could land inside that transaction and be
    // committed or rolled back with it.
    public synchronized boolean finalizeStaleOpenSessions() {
        return finalizeStaleOpenSessions(false);
    }

    /**
     * Finalize persisted OPEN rows left by an earlier process.
     *
     * @param forceRecent when true, the detector is known OFF and even rows inside the normal resume
     *                    window must close; no live charge exists for them to resume into
     */
    public synchronized boolean finalizeStaleOpenSessions(boolean forceRecent) {
        if (!isInitialized || connection == null) return false;
        if (!reconcilePendingActiveChargingReplacement()) return false;
        boolean allCommitted = true;
        try {
            java.util.List<Long> staleStarts = new java.util.ArrayList<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement sel = connection.prepareStatement(
                    "SELECT start_time FROM " + TABLE_CHARGING + " WHERE end_time IS NULL ORDER BY start_time ASC;")) {
                try (ResultSet rs = sel.executeQuery()) {
                    while (rs.next()) staleStarts.add(rs.getLong(1));
                }
            }
            // Upper bound for each row's reconstruction window: the NEXT session's start, so an
            // abandoned row cannot claim a later charge's samples or heartbeats as its own ending.
            // Rows are ordered ascending, so the successor is the next element. The last row is bounded
            // at `now`. Without this a row abandoned days ago picked up today's charge as its final
            // activity and was priced with that SoC and end time.
            try (PreparedStatement nx = connection.prepareStatement(
                    "SELECT MIN(start_time) FROM " + TABLE_CHARGING + " WHERE start_time > ?;")) {
                for (int i = 0; i < staleStarts.size(); i++) {
                    long start = staleStarts.get(i);
                    boolean matchesInMemory = wasCharging && start == chargingStartTime;
                    // A normal sweep preserves a genuinely-live row. A forced sweep is an
                    // authoritative OFF reconciliation, so retaining this row would defeat its contract.
                    if (matchesInMemory && !forceRecent) continue;
                    // The successor is the next OPEN row we are about to close, or — more often — a
                    // closed row that opened after this one. Ask the table so both cases are covered.
                    nx.setLong(1, start);
                    // The heartbeat queries treat this bound as EXCLUSIVE, so use now+1 when there is
                    // no successor — otherwise a heartbeat written exactly at `now` would be dropped.
                    long bound = now + 1;
                    try (ResultSet rs = nx.executeQuery()) {
                        if (rs.next()) {
                            long v = rs.getLong(1);
                            if (!rs.wasNull() && v > start) bound = Math.min(now + 1, v);
                        }
                    }
                    boolean committed = finalizeOneStaleSession(start, now, bound, forceRecent);
                    allCommitted &= committed;
                    if (committed && matchesInMemory) {
                        resetLiveChargingState(true);
                        chargingLifecycleHold = false;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("finalizeStaleOpenSessions failed: " + e.getMessage());
            allCommitted = false;
        }
        return allCommitted;
    }

    /**
     * @param now   wall clock, used for the resume-window recency guard
     * @param bound upper bound on this session's reconstruction window — the next session's start, so
     *              an abandoned row cannot claim a later charge's samples or heartbeats as its ending
     */
    private boolean finalizeOneStaleSession(long start, long now, long bound,
                                            boolean forceRecent) {
        try {
            long accountingBound = bound;
            if (!chargingAnalyticsEnabled && analyticsDisabledSinceMs > 0) {
                accountingBound = Math.min(accountingBound, analyticsDisabledSinceMs);
            }
            if (accountingBound < start) accountingBound = start;
            // Aggregate the recorded samples for this session.
            long lastT = -1;
            double lastSoc = Double.NaN;
            double lastTemp = -999;
            double lastTempHigh = -999;
            double lastTempLow = -999;
            double peak = 0, sum = 0; int count = 0;
            double startSoc = 0;
            boolean rowFound = false;
            boolean invalidPowerSample = false;
            try (PreparedStatement r = connection.prepareStatement(
                    "SELECT start_soc, peak_power_kw FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
                r.setLong(1, start);
                try (ResultSet rs = r.executeQuery()) {
                    if (rs.next()) {
                        rowFound = true;
                        startSoc = rs.getDouble(1);
                        double pk = rs.getDouble(2);
                        peak = !rs.wasNull()
                                && isValidMeasuredChargingPower(pk) ? pk : 0;
                    }
                }
            }
            // Defensive: if the charging_sessions row is gone (corrupted state),
            // do NOT proceed — folding here would create a ghost session (0 energy
            // but +1 session_count) in the daily rollup.
            if (!rowFound) {
                logger.debug("finalizeOneStaleSession(" + start + ") skipped: session row missing");
                return true;
            }
            try (PreparedStatement sp = connection.prepareStatement(
                    "SELECT t, power_kw, soc, temp, temp_high, temp_low FROM " + TABLE_CPS
                    + " WHERE session_start_time = ?"
                    + " AND (power_kw >= 0 OR power_kw = ? OR power_kw = ?)"
                    + " AND t < ? ORDER BY t ASC;")) {
                sp.setLong(1, start);
                sp.setDouble(2, MISSING_RATE_BOUNDARY_POWER_KW);
                sp.setDouble(3, AUXILIARY_SAMPLE_POWER_KW);
                sp.setLong(4, accountingBound);
                try (ResultSet rs = sp.executeQuery()) {
                    while (rs.next()) {
                        long t = rs.getLong(1);
                        double p = rs.getDouble(2);
                        double s = rs.getDouble(3);
                        boolean socMissing = rs.wasNull();
                        double tp = rs.getDouble(4);
                        boolean tempMissing = rs.wasNull();
                        double tpHigh = rs.getDouble(5);
                        boolean tempHighMissing = rs.wasNull();
                        double tpLow = rs.getDouble(6);
                        boolean tempLowMissing = rs.wasNull();
                        if (isValidMeasuredChargingPower(p)) {
                            if (p > peak) peak = p;
                            sum += p;
                            count++;
                        } else if (!Double.isFinite(p) || p > 0) {
                            invalidPowerSample = true;
                        }
                        lastT = t;
                        if (!socMissing && isFinite(s) && s >= 0 && s <= 100) lastSoc = s;
                        if (!tempMissing && isFinite(tp) && tp > -999) lastTemp = tp;
                        if (!tempHighMissing && isFinite(tpHigh) && tpHigh > -999) {
                            lastTempHigh = tpHigh;
                        }
                        if (!tempLowMissing && isFinite(tpLow) && tpLow > -999) {
                            lastTempLow = tpLow;
                        }
                    }
                }
            }
            // Activity = last power sample, else last charging heartbeat in
            // soc_history (the only activity signal on models with no power
            // samples), else start.
            long heartbeat = maxChargingHeartbeat(start, accountingBound);
            long lastActivity = Math.max(lastT, heartbeat);
            // No samples/heartbeat → nothing to reconstruct; close with start
            // values so it stops showing as a dangling open row.
            long endTime = strictlyAfterChargingStart(
                    start, (lastActivity > 0) ? lastActivity : start);
            // Guard: if the last activity is recent enough that the RESUME path would adopt this row,
            // leave it alone. The threshold must be the resume window itself — it was 120 s while
            // tryResumeChargingSession accepts a gap up to CHARGING_MERGE_GAP_MS (15 min), so a restart
            // whose outage fell between the two closed a session that was about to be legitimately
            // resumed: the charge got split across two rows, the first flagged incomplete, and the
            // metered energy divided between them. Honour the heartbeat too, else a no-power-sample
            // live charge gets prematurely closed at init and churns through a fold/reverse-fold.
            if (!forceRecent && lastActivity > 0
                    && (now - lastActivity) < CHARGING_MERGE_GAP_MS) return true;

            // Prefer a CPS sample's SoC; fall back to the last charging HEARTBEAT before falling back
            // to startSoc. On a model that produces no power samples the heartbeat is the only SoC
            // record of the charge, and defaulting to startSoc recorded a zero SoC gain — which also
            // starved the energy resolver of the SOC-derived estimate it uses to cross-check the
            // counter. Only accept a heartbeat that actually moved upward; a lower reading is not this
            // charge's endpoint.
            double endSoc = !Double.isNaN(lastSoc) ? lastSoc : startSoc;
            if (Double.isNaN(lastSoc)) {
                double hbSoc = lastChargingHeartbeatSoc(start, accountingBound);
                if (!Double.isNaN(hbSoc) && hbSoc > startSoc) endSoc = hbSoc;
            }
            // ENERGY for a session we were NOT present to finish (daemon was down when the charge
            // ended, or it ended during a restart). Same resolution as the live path, with one
            // extra step: the counter endpoints persisted on the row are all that survive, and the
            // interval between the last one we saw and the true end was never observed.
            //
            // Prefer what the counter accumulated up to our last observation over re-differencing
            // the endpoints, because the accumulated figure already has wraps and resets applied
            // while a bare difference does not. Anything delivered after our last observation is
            // genuinely unrecoverable from the counter, so the row is marked incomplete rather than
            // padded with a guess.
            double counterEnergy = Double.NaN, counterStart = Double.NaN, counterLast = Double.NaN;
            double rowCounterFullScale = Double.NaN;
            boolean rowIncomplete = false;
            String rowCounterSource = null;
            try (PreparedStatement cp = connection.prepareStatement(
                    "SELECT counter_start_kwh, counter_last_kwh, counter_energy_kwh, energy_incomplete,"
                    + " counter_source, counter_full_scale_kwh FROM " + TABLE_CHARGING
                    + " WHERE start_time = ?;")) {
                cp.setLong(1, start);
                try (ResultSet crs = cp.executeQuery()) {
                    if (crs.next()) {
                        counterStart = crs.getDouble(1); if (crs.wasNull()) counterStart = Double.NaN;
                        counterLast = crs.getDouble(2);  if (crs.wasNull()) counterLast = Double.NaN;
                        counterEnergy = crs.getDouble(3); if (crs.wasNull()) counterEnergy = Double.NaN;
                        rowIncomplete = crs.getInt(4) == 1;
                        rowCounterSource = crs.getString(5);
                        rowCounterFullScale = crs.getDouble(6);
                        if (crs.wasNull()) rowCounterFullScale = Double.NaN;
                    }
                }
            } catch (Exception e) {
                logger.debug("counter columns unavailable for stale session " + start + ": " + e.getMessage());
            }
            rowIncomplete |= invalidPowerSample;
            // NOTE: the outage tail is NOT credited here. It is credited to the session that is
            // still LIVE, at SESSION START, by seeding that session's baseline from this row's
            // persisted counter_last_kwh when the counter shows it never reset (see the continuation
            // check in trackChargingSession). Crediting it in both places would double-count the same
            // energy, and attributing it to the live session is the more useful of the two: that row
            // is the one the user is watching, and it gets priced normally rather than as a
            // reconstructed floor.
            com.overdrive.app.abrp.SohEstimator staleSoh = getSohEstimator();
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot staleCapacitySoh =
                    staleSoh != null ? staleSoh.getCapacitySohSnapshot() : null;
            if (Double.isNaN(counterEnergy) && !Double.isNaN(counterStart) && !Double.isNaN(counterLast)) {
                // Endpoints without an accumulated total: a pre-restore row. Resolve the ambiguity
                // (did it wrap, or reset?) against the SOC estimate rather than assuming.
                // The wrap candidate depends on the counter's MODULUS, so it must be that counter's.
                // Using the dedicated-capacity default for an external-counter row makes the wrapped
                // candidate wrong by the difference between the two scales, potentially hundreds of kWh.
                double[] cands = com.overdrive.app.charging.ChargeCounterAccumulator
                        .gapCandidatesKwh(counterStart, counterLast,
                                !Double.isNaN(rowCounterFullScale) ? rowCounterFullScale
                                        : counterScaleForSource(
                                                rowCounterSource, counterStart, counterLast));
                double socForPick = com.overdrive.app.charging.SessionEnergyResolver.socEstimateKwh(
                        endSoc - startSoc,
                        staleCapacitySoh != null
                                ? staleCapacitySoh.getNominalCapacityKwh() : 0,
                        staleCapacitySoh != null && staleCapacitySoh.hasDisplaySoh()
                                ? staleCapacitySoh.getDisplaySoh() : Double.NaN);
                counterEnergy = com.overdrive.app.charging.ChargeCounterAccumulator.chooseCandidate(
                        cands, socForPick,
                        com.overdrive.app.charging.SessionEnergyResolver.RATIO_LOW,
                        com.overdrive.app.charging.SessionEnergyResolver.RATIO_HIGH);
            }
            double socEstStale = com.overdrive.app.charging.SessionEnergyResolver.socEstimateKwh(
                    endSoc - startSoc,
                    staleCapacitySoh != null
                            ? staleCapacitySoh.getNominalCapacityKwh() : 0,
                    staleCapacitySoh != null && staleCapacitySoh.hasDisplaySoh()
                            ? staleCapacitySoh.getDisplaySoh() : Double.NaN);
            // Both `counter_energy_kwh` and the wrap candidates above are in the counter's OWN frame
            // (see meteredEnergyKwh), so a proven register-width fault is corrected here — the same
            // boundary the live paths use, so a session finalized after a restart is not priced
            // differently from one we were present to close.
            counterEnergy = correctStoredCounterEnergy(counterEnergy, rowCounterSource);
            com.overdrive.app.charging.SessionEnergyResolver.Result staleRes =
                    com.overdrive.app.charging.SessionEnergyResolver.resolve(
                            counterEnergy, true, rowIncomplete,
                            integrateSessionEnergyKwh(start), socEstStale,
                            lastIntegrationTruncated,
                            counterScaleSuspect(rowCounterSource));
            double energyAdded = staleRes.isUsable() ? staleRes.energyKwh : 0;
            logger.info(String.format(java.util.Locale.US,
                    "Stale session %d energy resolved: %.3f kWh via %s (counter=%s soc=%s)",
                    start, energyAdded, staleRes.source,
                    Double.isNaN(counterEnergy) ? "n/a" : String.format("%.3f", counterEnergy),
                    Double.isNaN(socEstStale) ? "n/a" : String.format("%.3f", socEstStale)));
            // Same peak/average rules as the live close path, so a session reconstructed after a
            // restart is not reported differently from one we were present to finish.
            peak = resolvePeakKw(start, peak);
            double avgPower = resolveAveragePowerKw(
                    start, sum, count);
            int rangeGained = rangeGainedFromEnergy(energyAdded);
            // Determine AC/DC first — the DC tariff selection depends on it.
            // Read the charge LOCATION from the row too: this path runs after a
            // daemon restart, so the in-memory chargingStartLat/Lng are gone and
            // the stored coordinates are the only way to price this session at
            // its location's tariff.
            int gun = -1;
            double rowLat = 0, rowLng = 0;
            try (PreparedStatement r = connection.prepareStatement(
                    "SELECT gun_state, start_lat, start_lng FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
                r.setLong(1, start);
                try (ResultSet rs = r.executeQuery()) {
                    if (rs.next()) {
                        gun = rs.getInt(1);
                        rowLat = rs.getDouble(2);
                        rowLng = rs.getDouble(3);
                    }
                }
            }
            int isDc = deriveIsDc(gun, peak);  // peak-guarded against a misread DC gun
            // Price at the location's tariff, else the global DC/base rate.
            PricingDecision pd = priceSessionForClose(isDc, rowLat, rowLng);
            double rate = pd.rate;
            double cost = pd.costFor(energyAdded);
            String curr = pd.currency;
            double tAvg = lastTemp > -999 ? lastTemp : -999;
            double tHigh = lastTempHigh > -999 ? lastTempHigh : -999;
            double tLow = lastTempLow > -999 ? lastTempLow : -999;

            final double persistedEndSoc = endSoc;
            final double persistedAvgPower = avgPower;
            final double persistedPeak = peak;
            runInTransaction(() -> {
                try (PreparedStatement upd = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING + " SET end_time = ?, end_soc = ?, energy_added_kwh = ?, " +
                        "avg_power_kw = ?, peak_power_kw = ?, range_gained_km = ?, is_dc = ?, " +
                        "electricity_rate = ?, currency = ?, session_cost = ?, "
                        + "hv_temp_high = ?, hv_temp_low = ?, hv_temp_avg = ?, " +
                        "tariff_id = ?, tariff_label = ?, " +
                        "energy_source = ?, energy_soc_kwh = ?, energy_incomplete = ?," +
                        " closed_by_sweep = 1, post_commit_tariff_applied = ?," +
                        " post_commit_soh_applied = 1, resume_blocked = ? " +
                        "WHERE start_time = ? AND end_time IS NULL;")) {
                    upd.setLong(1, endTime);
                    upd.setDouble(2, persistedEndSoc);
                    upd.setDouble(3, energyAdded);
                    upd.setDouble(4, persistedAvgPower);
                    upd.setDouble(5, persistedPeak);
                    upd.setInt(6, rangeGained);
                    upd.setInt(7, isDc);
                    upd.setDouble(8, rate);
                    upd.setString(9, curr);
                    upd.setDouble(10, cost);
                    upd.setDouble(11, tHigh);
                    upd.setDouble(12, tLow);
                    upd.setDouble(13, tAvg);
                    upd.setString(14, pd.tariffId);
                    upd.setString(15, pd.tariffLabel);
                    upd.setString(16, staleRes.source);
                    if (!Double.isNaN(staleRes.socEstimateKwh)) {
                        upd.setDouble(17, staleRes.socEstimateKwh);
                    } else {
                        upd.setNull(17, java.sql.Types.DOUBLE);
                    }
                    // ALWAYS incomplete. This path exists precisely because nobody observed the end of
                    // the charge: the daemon was down when it finished, so everything delivered between
                    // our last sample and the unplug is unrecoverable. A 60 kWh pack that we watched for
                    // the first 10 min of a DC charge and then reconstructed would otherwise be stored
                    // as a confident ~5 kWh session when the car actually took ~54 kWh — and it is
                    // PRICED. Flagging it makes the UI show '~' rather than presenting a floor as exact.
                    // (The condition previously read `staleRes.incomplete || rowIncomplete`, which is a
                    // strictly weaker claim than this comment asserted.)
                    upd.setInt(18, 1);
                    upd.setInt(19, pd.tariffId.isEmpty() ? 1 : 0);
                    upd.setInt(20, forceRecent ? 1 : 0);
                    upd.setLong(21, start);
                    if (upd.executeUpdate() != 1) {
                        throw new java.sql.SQLException(
                                "stale close found no matching open session");
                    }
                }
                // A stale-finalized session is incomplete by construction: the daemon was absent for
                // part of it, so its energy is a floor.
                foldSessionIntoDaily(endTime, energyAdded, cost, isDc, persistedPeak, rangeGained, true);
            });
            replayPendingChargingPostCommitMetadata();
            logger.info("Finalized stale open charging session start=" + start +
                " (samples=" + count + ", energy=" + String.format("%.1f", energyAdded) +
                " kWh, end_soc=" + String.format("%.0f", endSoc) + "%)");
            return true;
        } catch (Exception e) {
            if (isSessionDurablyClosed(start)) {
                logger.warn("Stale-session finalization threw after commit; reconciled exact row "
                        + start);
                replayPendingChargingPostCommitMetadata();
                noteWriteOk();
                return true;
            }
            logger.debug("finalizeOneStaleSession(" + start + ") failed: " + e.getMessage());
            if (isSqlFailure(e)) {
                noteWriteFailed();
                try { reconnect(); } catch (Exception ignored) {}
            }
            if (isSessionDurablyClosed(start)) {
                logger.warn("Stale-session finalization reconciled after reconnect for row " + start);
                replayPendingChargingPostCommitMetadata();
                noteWriteOk();
                return true;
            }
            return false;
        }
    }

    private static long dayEpoch(long timestamp) {
        return (timestamp / 86_400_000L) * 86_400_000L;
    }

    /**
     * Rebuild one daily charging bucket from the authoritative closed session rows.
     *
     * <p>Used after resume consolidation and deletion. Recomputing is both simpler and safer than
     * subtracting an assumed prior fold: legacy rows may never have been folded, and a swallowed
     * decrement failure followed by a re-fold is exactly how duplicate daily energy was created.
     * The caller must include this in the same transaction as the row mutation.
     */
    private void rebuildChargingDailyDay(long day) throws Exception {
        double previousSoh = -999;
        try (PreparedStatement prior = connection.prepareStatement(
                "SELECT soh_at_day FROM " + TABLE_CHARGING_DAILY + " WHERE day_epoch = ?;")) {
            prior.setLong(1, day);
            try (ResultSet rs = prior.executeQuery()) {
                if (rs.next()) previousSoh = rs.getDouble(1);
            }
        }

        int count;
        double energy;
        double cost;
        int dc;
        int ac;
        double peak;
        int range;
        int incomplete;
        try (PreparedStatement aggregate = connection.prepareStatement(
                "SELECT COUNT(*),"
                + " COALESCE(SUM(CASE WHEN energy_added_kwh > 0 THEN energy_added_kwh ELSE 0 END), 0),"
                + " COALESCE(SUM(CASE WHEN session_cost > 0 THEN session_cost ELSE 0 END), 0),"
                + " COALESCE(SUM(CASE WHEN is_dc = 1 THEN 1 ELSE 0 END), 0),"
                + " COALESCE(SUM(CASE WHEN is_dc = 0 THEN 1 ELSE 0 END), 0),"
                + " COALESCE(MAX(CASE WHEN peak_power_kw > 0"
                + " AND peak_power_kw <= 500 THEN peak_power_kw ELSE 0 END), 0),"
                + " COALESCE(SUM(CASE WHEN range_gained_km > 0 THEN range_gained_km ELSE 0 END), 0),"
                + " COALESCE(SUM(CASE WHEN energy_incomplete = 1 THEN 1 ELSE 0 END), 0)"
                + " FROM " + TABLE_CHARGING
                + " WHERE end_time >= ? AND end_time < ?;")) {
            aggregate.setLong(1, day);
            aggregate.setLong(2, day + 86_400_000L);
            try (ResultSet rs = aggregate.executeQuery()) {
                if (!rs.next()) throw new java.sql.SQLException("daily aggregate returned no row");
                count = rs.getInt(1);
                energy = rs.getDouble(2);
                cost = rs.getDouble(3);
                dc = rs.getInt(4);
                ac = rs.getInt(5);
                peak = rs.getDouble(6);
                range = rs.getInt(7);
                incomplete = rs.getInt(8);
            }
        }

        if (count == 0) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM " + TABLE_CHARGING_DAILY + " WHERE day_epoch = ?;")) {
                delete.setLong(1, day);
                delete.executeUpdate();
            }
            return;
        }

        try (PreparedStatement merge = connection.prepareStatement(
                "MERGE INTO " + TABLE_CHARGING_DAILY
                + " (day_epoch, session_count, energy_kwh, cost, dc_count, ac_count,"
                + " peak_power_kw, soh_at_day, range_gained_km, incomplete_count)"
                + " KEY(day_epoch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);")) {
            merge.setLong(1, day);
            merge.setInt(2, count);
            merge.setDouble(3, energy);
            merge.setDouble(4, cost);
            merge.setInt(5, dc);
            merge.setInt(6, ac);
            merge.setDouble(7, peak);
            merge.setDouble(8, previousSoh);
            merge.setInt(9, range);
            merge.setInt(10, incomplete);
            if (merge.executeUpdate() <= 0) {
                throw new java.sql.SQLException("daily bucket rebuild updated no row");
            }
        }
    }

    /**
     * Re-price closed sessions after a tariff edit, and keep the daily rollups
     * consistent with the new costs.
     *
     * <p><b>Why this is needed.</b> A session's cost is snapshotted at close
     * time, so correcting a mistyped rate would otherwise leave every historical
     * charge priced wrong forever, with no way to fix it short of deleting the
     * sessions. This walks the affected rows, re-runs the SAME
     * {@link #priceSession} resolution used at session end, and rewrites
     * {@code electricity_rate} / {@code currency} / {@code session_cost} /
     * {@code tariff_id} — folding the cost delta into {@code charging_daily} so
     * period and lifetime totals stay in agreement with the session list.
     *
     * <p><b>Scope.</b> {@code tariffId} non-empty ⇒ only sessions that tariff
     * priced, plus any unpriced-but-in-range session it now covers (so widening
     * a radius or adding a rate retroactively adopts the charges it should own).
     * {@code tariffId} empty ⇒ every session is re-evaluated (used when a tariff
     * is deleted, so its orphaned sessions fall back to the global rate).
     *
     * <p>Sessions with no energy recorded are skipped — there is nothing to
     * price, and rewriting them would churn the rollups for no gain.
     *
     * @return the number of sessions whose cost actually changed
     * @throws IllegalStateException when the durable intent or repricing attempt cannot complete
     */
    public synchronized int repriceSessionsForTariff(String tariffId) {
        String queuedKey = queuePendingTariffReprice(tariffId);
        try {
            int changed = repriceSessionsForTariffNow(
                    tariffIdForRepriceKey(queuedKey));
            completePendingTariffReprice(queuedKey);
            replayPendingChargingPostCommitMetadata();
            return changed;
        } catch (Exception e) {
            logger.error("repriceSessionsForTariff failed; durable retry retained: "
                    + e.getMessage());
            throw new IllegalStateException(
                    "Tariff repricing is pending durable replay", e);
        }
    }

    private int repriceSessionsForTariffNow(String tariffId) throws Exception {
        if (!isAvailable()) {
            throw new java.sql.SQLException("charging database unavailable for tariff repricing");
        }
        int changed = 0;
        java.util.List<RepricePostcondition> expectedUpdates = new java.util.ArrayList<>();
        boolean commitAttempted = false;
        try {
            // Snapshot the rows first: we mutate inside the loop, and H2 doesn't
            // like an open ResultSet over a table being updated.
            java.util.List<long[]> keys = new java.util.ArrayList<>();      // startTime, endTime
            java.util.List<double[]> vals = new java.util.ArrayList<>();    // energy, oldCost, lat, lng, oldRate
            java.util.List<int[]> flags = new java.util.ArrayList<>();      // isDc, rangeGained
            java.util.List<String> owners = new java.util.ArrayList<>();    // existing tariff_id
            java.util.List<String[]> texts = new java.util.ArrayList<>();   // currency, tariff_label

            // Select EVERY column the UPDATE below writes, so the "nothing changed"
            // skip can be decided on all of them (a label-only or currency-only
            // edit must not be dropped just because the cost is unchanged).
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT start_time, end_time, energy_added_kwh, session_cost, start_lat, start_lng, " +
                    "is_dc, range_gained_km, tariff_id, currency, tariff_label, electricity_rate FROM " + TABLE_CHARGING +
                    " WHERE end_time IS NOT NULL;")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double energy = rs.getDouble(3);
                        if (!Double.isFinite(energy) || energy <= 0) continue;
                        keys.add(new long[]{ rs.getLong(1), rs.getLong(2) });
                        vals.add(new double[]{ energy, rs.getDouble(4), rs.getDouble(5), rs.getDouble(6), rs.getDouble(12) });
                        flags.add(new int[]{ rs.getInt(7), rs.getInt(8) });
                        String owner = rs.getString(9);
                        owners.add(owner != null ? owner : "");
                        String cur = rs.getString(10);
                        String lbl = rs.getString(11);
                        texts.add(new String[]{ cur != null ? cur : "", lbl != null ? lbl : "" });
                    }
                }
            }

            // One prepared statement reused for every row, and ONE transaction for
            // the whole sweep: 2N autocommits on a 2000-session history is minutes
            // of disk churn on a head unit, and a mid-sweep failure would otherwise
            // leave half the rows re-priced with charging_daily half-adjusted.
            Connection transactionConnection = connection;
            boolean priorAutoCommit = transactionConnection.getAutoCommit();
            if (!priorAutoCommit) {
                discardTransactionConnection(transactionConnection);
                throw new java.sql.SQLException(
                        "tariff repricing found auto-commit disabled before its transaction");
            }
            transactionConnection.setAutoCommit(false);
            boolean committed = false;
            Exception transactionFailure = null;
            try (PreparedStatement upd = transactionConnection.prepareStatement(
                    "UPDATE " + TABLE_CHARGING + " SET electricity_rate = ?, currency = ?, " +
                    "session_cost = ?, tariff_id = ?, tariff_label = ? WHERE start_time = ?;")) {

                for (int i = 0; i < keys.size(); i++) {
                    long startTime = keys.get(i)[0];
                    long endTime = keys.get(i)[1];
                    double energy = vals.get(i)[0];
                    double oldCost = vals.get(i)[1];
                    double lat = vals.get(i)[2];
                    double lng = vals.get(i)[3];
                    double oldRate = vals.get(i)[4];
                    int isDc = flags.get(i)[0];
                    int rangeGained = flags.get(i)[1];
                    String owner = owners.get(i);
                    String oldCurrency = texts.get(i)[0];
                    String oldLabel = texts.get(i)[1];

                    // Adoption of a previously-global session must be EARNED by an
                    // explicit geofence match, never inherited from the pinned
                    // default. Pre-v3 rows carry start_lat/start_lng = 0 from the
                    // ALTER default, so the lenient resolve() would hand every one
                    // of them to whichever profile happens to be default — which,
                    // since the first tariff a user creates is auto-promoted to
                    // default, would silently restate the ENTIRE cost history the
                    // moment they added one tariff. That is the I2 violation.
                    PricingDecision pd = (owner.isEmpty())
                            ? priceSessionInCircle(isDc, lat, lng)
                            : priceSession(isDc, lat, lng);

                    // NEVER re-price a session that was priced by the global rate
                    // and still is. Those costs are a historical record of what the
                    // driver actually paid; electricity_rate is a deliberate
                    // at-session-end snapshot (that is why the column exists).
                    if (owner.isEmpty() && pd.tariffId.isEmpty()) continue;

                    // A globally-priced session that a tariff now geofences may be
                    // adopted ONLY if it was never actually priced (no rate, no
                    // cost). Restating a real historical price is the I2 violation.
                    if (owner.isEmpty() && (oldRate >= 0 || oldCost >= 0)) continue;

                    // Targeted mode: touch only sessions this tariff owned before,
                    // or that it owns now. Other tariffs' sessions are untouched.
                    if (tariffId != null && !tariffId.isEmpty()
                            && !tariffId.equals(owner) && !tariffId.equals(pd.tariffId)) {
                        continue;
                    }

                    double newCost = pd.costFor(energy);
                    // Sub-cent moves aren't worth a rollup adjustment...
                    boolean costSame = (oldCost <= -1 && newCost <= -1)
                            || (oldCost > -1 && newCost > -1 && Math.abs(oldCost - newCost) < 0.005);
                    // ...but the row still has to be written when ANY other
                    // persisted column moved (a rename, a currency switch, an
                    // ownership transfer, or a rate change too small to move the
                    // cost). Deciding the skip on cost alone silently dropped
                    // those edits onto history.
                    boolean rowSame = costSame
                            && pd.tariffId.equals(owner)
                            && pd.currency.equals(oldCurrency)
                            && pd.tariffLabel.equals(oldLabel)
                            && realStorageEquivalent(oldRate, pd.rate);
                    if (rowSame) continue;

                    upd.setDouble(1, pd.rate);
                    upd.setString(2, pd.currency);
                    upd.setDouble(3, newCost);
                    upd.setString(4, pd.tariffId);
                    upd.setString(5, pd.tariffLabel);
                    upd.setLong(6, startTime);
                    // Only shift the rollup if the row actually changed. A 0-row
                    // update (row deleted between the snapshot SELECT and now) would
                    // otherwise move charging_daily for a session that no longer
                    // carries the new cost.
                    if (upd.executeUpdate() <= 0) continue;

                    // Keep charging_daily in step by shifting ONLY the cost column
                    // by the delta. Deliberately NOT reverse-fold + re-fold:
                    // re-folding rewrites soh_at_day with TODAY's SOH estimate,
                    // corrupting that day's point on the SOH-trend chart, and would
                    // churn session_count/dc/ac/peak that haven't changed.
                    //
                    // Called unconditionally, NOT under !costSame: an ownership
                    // transfer whose cost moved sub-cent still WROTE the new
                    // session_cost above, so skipping the shift here left the
                    // rollup permanently out of step with the session rows.
                    // adjustDailyCost already no-ops on a zero/NaN delta.
                    adjustDailyCost(endTime, (newCost > 0 ? newCost : 0) - (oldCost > 0 ? oldCost : 0));
                    expectedUpdates.add(new RepricePostcondition(
                            startTime, pd.rate, pd.currency, newCost,
                            pd.tariffId, pd.tariffLabel, !costSame));
                    if (!costSame) changed++;
                }
                commitAttempted = true;
                transactionConnection.commit();
                committed = true;
            } catch (Exception e) {
                transactionFailure = e;
                throw e;
            } finally {
                if (!committed) {
                    try {
                        transactionConnection.rollback();
                    } catch (Exception rollbackFailure) {
                        if (transactionFailure != null) {
                            transactionFailure.addSuppressed(rollbackFailure);
                        }
                        discardTransactionConnection(transactionConnection);
                    }
                    // Nothing persisted — never report a count for rolled-back work,
                    // or the UI would claim "N past charges re-priced" after a failure.
                    changed = 0;
                }
                try {
                    if (!transactionConnection.isClosed()) {
                        transactionConnection.setAutoCommit(priorAutoCommit);
                    }
                } catch (Exception restoreFailure) {
                    discardTransactionConnection(transactionConnection);
                    if (transactionFailure != null) {
                        transactionFailure.addSuppressed(restoreFailure);
                    } else {
                        throw restoreFailure;
                    }
                }
            }
            if (changed > 0) {
                logger.info("Re-priced " + changed + " charging session(s) for tariff "
                        + (tariffId == null || tariffId.isEmpty() ? "(all)" : tariffId));
            }
        } catch (Exception e) {
            if (commitAttempted && !expectedUpdates.isEmpty()
                    && reconcileRepriceCommit(expectedUpdates, e)) {
                changed = countCostChanged(expectedUpdates);
                noteWriteOk();
                logger.warn("Tariff repricing commit result was uncertain; reconciled "
                        + expectedUpdates.size() + " durable session update(s)");
                return changed;
            }
            // A completed scan with no updates has no transactional state to reconcile. A failure
            // before the commit boundary keeps commitAttempted false and remains retryable.
            if (commitAttempted && expectedUpdates.isEmpty()) return 0;
            throw e;
        }
        return changed;
    }

    static String normalizeTariffRepriceKey(String tariffId) {
        return tariffId == null || tariffId.isEmpty() ? "*" : tariffId;
    }

    private static String tariffIdForRepriceKey(String tariffKey) {
        return "*".equals(tariffKey) ? "" : tariffKey;
    }

    /**
     * Write the retry intent before H2 is touched. A wildcard subsumes every targeted request because
     * repricing always resolves against the latest durable tariff configuration.
     */
    private String queuePendingTariffReprice(String tariffId) {
        String requestedKey = normalizeTariffRepriceKey(tariffId);
        boolean changed = false;
        if ("*".equals(requestedKey)) {
            if (pendingTariffReprices.size() != 1
                    || !pendingTariffReprices.contains("*")) {
                pendingTariffReprices.clear();
                pendingTariffReprices.add("*");
                changed = true;
            }
        } else if (!pendingTariffReprices.contains("*")) {
            changed = pendingTariffReprices.add(requestedKey);
        }
        if (changed) chargingLifecycleJournalDirty = true;
        if ((changed || chargingLifecycleJournalDirty)
                && !persistChargingLifecycleJournal()) {
            throw new IllegalStateException(
                    "tariff repricing intent was not durable");
        }
        return pendingTariffReprices.contains("*") ? "*" : requestedKey;
    }

    /**
     * Remove an intent only after the H2 commit is known durable. Restore the in-memory queue when
     * journal cleanup is uncertain; the previously synced file still carries the same retry.
     */
    private void completePendingTariffReprice(String tariffKey) throws Exception {
        java.util.LinkedHashSet<String> before =
                new java.util.LinkedHashSet<>(pendingTariffReprices);
        if ("*".equals(tariffKey)) pendingTariffReprices.clear();
        else pendingTariffReprices.remove(tariffKey);
        chargingLifecycleJournalDirty = true;
        if (!persistChargingLifecycleJournal()) {
            pendingTariffReprices.clear();
            pendingTariffReprices.addAll(before);
            chargingLifecycleJournalDirty = true;
            throw new java.io.IOException(
                    "tariff repricing completion was not durable");
        }
    }

    /** Replay journal-restored tariff edits before reconciling tariff usage metadata. */
    private void replayPendingTariffReprices() {
        if (replayingTariffReprices || pendingTariffReprices.isEmpty()) return;
        replayingTariffReprices = true;
        try {
            while (!pendingTariffReprices.isEmpty()) {
                String tariffKey = pendingTariffReprices.iterator().next();
                try {
                    repriceSessionsForTariffNow(
                            tariffIdForRepriceKey(tariffKey));
                    completePendingTariffReprice(tariffKey);
                } catch (Exception e) {
                    logger.warn("Pending tariff repricing replay deferred for "
                            + tariffKey + ": " + e.getMessage());
                    break;
                }
            }
        } finally {
            replayingTariffReprices = false;
        }
    }

    /**
     * The per-kWh rate the most recent completed charge was billed at, with the
     * currency and tariff label that produced it — the price the energy currently
     * in the pack actually cost.
     *
     * <p>This is what Trips uses to cost a drive: energy consumed on a trip came
     * out of the last charge, so it should be valued at that charge's tariff, not
     * at whatever global rate happens to be configured now. A driver who charges
     * cheaply at home and expensively on a road trip gets per-trip costs that
     * reflect where the electrons were bought.
     *
     * <p>Only sessions with a real recorded rate qualify ({@code electricity_rate
     * > 0}); an unpriced session is skipped so we fall back to an older priced one
     * rather than reporting a zero rate. Returns {@code null} when no priced
     * session exists at all (fresh install, or analytics never enabled), which
     * callers treat as "use the configured global rate" — the pre-existing
     * behaviour, so nothing regresses.
     *
    * @param maxAgeDays ignore charges older than this; {@code <= 0} = no limit
     */
    public synchronized JSONObject getLastChargeRate(int maxAgeDays) {
        try {
            return getLastChargeRateStrict(maxAgeDays);
        } catch (java.sql.SQLException e) {
            logger.debug("getLastChargeRate failed: " + e.getMessage());
            try { reconnect(); } catch (Exception ignored) {}
            return null;
        }
    }

    /**
     * Strict trip-pricing lookup. {@code null} means the query succeeded and no tariff-owned charge
     * exists; an unavailable or failed database throws so a trip snapshot can remain retryable.
     */
    public synchronized JSONObject getLastChargeRateStrict(int maxAgeDays)
            throws java.sql.SQLException {
        final Connection conn = requireChargingHistoryReadConnection();
        try {
            // Take the MOST RECENT priced charge, then require that it be
            // tariff-owned. Filtering tariff-ownership inside the WHERE clause
            // instead would let a 59-day-old home tariff price today's trip while
            // ignoring yesterday's public charge — the opposite of "the energy in
            // the pack came from the last charge".
            //
            // Ownership is required because a globally-priced row must not set a
            // trip's rate: with no tariffs at all, a DC fast charge stores the
            // global dcRate, and inheriting it would cost every later trip at the
            // DC premium instead of collapsing to the base rate (invariant I1).
            StringBuilder sql = new StringBuilder(
                    "SELECT end_time, electricity_rate, currency, tariff_id, tariff_label, is_dc " +
                    "FROM " + TABLE_CHARGING +
                    " WHERE end_time IS NOT NULL AND electricity_rate > 0");
            if (maxAgeDays > 0) {
                // end_time is the real bound. start_time is added ONLY as an index
                // prune (idx_charging_start is the sole index), and is slacked back
                // 30 days: a session STARTS before it ENDS, so binding both to the
                // same cutoff would drop a charge that began just before the cutoff
                // and ended after it — including, at the boundary, the most recent
                // one. The slack is far wider than any real session span.
                sql.append(" AND start_time >= ? AND end_time >= ?");
            }
            sql.append(" ORDER BY end_time DESC LIMIT 1;");

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                if (maxAgeDays > 0) {
                    long cutoff = System.currentTimeMillis() - (maxAgeDays * 86_400_000L);
                    ps.setLong(1, cutoff - 30L * 86_400_000L);   // index prune, slacked
                    ps.setLong(2, cutoff);                        // the real age bound
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        noteReadOk();
                        return null;
                    }
                    double rate = rs.getDouble(2);
                    if (!Double.isFinite(rate) || rate <= 0) {
                        noteReadOk();
                        return null;
                    }
                    String tId = null, tLabel = null;
                    try {
                        tId = rs.getString(4);
                        tLabel = rs.getString(5);
                    } catch (Exception ignored) {
                        // Pre-v6 row without the tariff columns — treated as global.
                    }
                    // The latest priced charge was on the GLOBAL rate (or predates
                    // tariffs): return null so trips fall back to the configured
                    // base rate — the pre-feature behaviour (I1).
                    if (tId == null || tId.isEmpty()) {
                        noteReadOk();
                        return null;
                    }
                    JSONObject out = new JSONObject();
                    out.put("endTime", rs.getLong(1));
                    out.put("rate", rate);
                    String curr = rs.getString(3);
                    out.put("currency", curr != null ? curr : "");
                    out.put("tariffId", tId != null ? tId : "");
                    out.put("tariffLabel", tLabel != null ? tLabel : "");
                    int isDc = rs.getInt(6);
                    out.put("isDc", rs.wasNull() || (isDc != 0 && isDc != 1)
                            ? JSONObject.NULL : Boolean.valueOf(isDc == 1));
                    noteReadOk();
                    return out;
                }
            }
        } catch (Exception e) {
            if (isSqlFailure(e)) noteReadFailed();
            throw chargingHistoryReadException("get last charge rate", e);
        }
    }

    /**
     * Shift one day's rollup cost by {@code delta} (may be negative), clamped at
     * zero. Used by {@link #repriceSessionsForTariff} so period/lifetime cost
     * totals track a re-priced session without disturbing that day's session
     * count, AC/DC split, peak power, or {@code soh_at_day}.
     *
     * <p>Throws when the day has no rollup row so the surrounding repricing transaction rolls back;
     * committing only the session row would make period/lifetime totals inconsistent.
     */
    private void adjustDailyCost(long endTime, double delta) throws Exception {
        if (!isInitialized || connection == null) {
            throw new java.sql.SQLException("charging database unavailable during daily cost adjustment");
        }
        if (!Double.isFinite(delta)) {
            throw new java.sql.SQLException(
                    "non-finite charging cost delta");
        }
        if (delta == 0) return;
        long day = (endTime / 86_400_000L) * 86_400_000L;
        try (PreparedStatement upd = connection.prepareStatement(
                "UPDATE " + TABLE_CHARGING_DAILY +
                " SET cost = GREATEST(cost + ?, 0) WHERE day_epoch = ?;")) {
            upd.setDouble(1, delta);
            upd.setLong(2, day);
            if (upd.executeUpdate() != 1) {
                // The session row and daily rollup are one accounting unit. Throw through the caller's
                // transaction so repricing rolls back instead of committing an inconsistent row cost.
                throw new java.sql.SQLException(
                        "daily cost adjustment found no row for day " + day);
            }
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
        if (!Double.isFinite(energyKwh) || energyKwh <= 0) return -1;
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
                        updateSessionPlaceLabel(sessionStart, label);
                    }
                });
        } catch (Exception e) {
            logger.debug("resolvePlaceLabelAsync failed: " + e.getMessage());
        }
    }

    private synchronized void updateSessionPlaceLabel(long sessionStart, String label) {
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

    /** Charging gun state (2=AC 3=DC 4=AC_DC 5=V2L), or -1 if unavailable. */
    /**
     * Derive the is_dc column (1=DC, 0=AC, -1=unknown) from the gun state, with a
     * PHYSICAL sanity guard against a HAL gun-state misread. DC fast-charging is
     * fundamentally high-power; a session whose measured peak never approached a DC
     * rate is not DC, whatever the gun byte said. Observed: a PHEV AC charge at
     * ~1.7 kW (≈7 kW peak) reported gun=3 → was labelled "DC fast". So a gun==3
     * (DC) verdict is only honoured when the session peak is DC-plausible; otherwise
     * we downgrade to unknown (-1) and let the power-based classifier bucket it as
     * AC. gun==2 (AC) is trusted as-is. The shared 15 kW floor is above the observed
     * false-DC AC profiles while retaining
     * short or heavily tapered DC sessions that never reach the 25 kW power-only threshold.
     * With no trustworthy gun verdict, a measured peak at or above 25 kW is sufficient
     * evidence for DC because it is above the supported AC charging domain.
     *
     * <p>An UNMEASURED peak (0) deliberately lands on -1 too, i.e. base rate. It is
     * tempting to read 0 as "no evidence, so trust the gun" and return 1, but that is
     * the wrong default here: an AC-only PHEV whose charger getters are all dead
     * misreads gun==3 and would then be priced at {@code dcRate} FOREVER (I5 — the row
     * is an immutable snapshot). The peak is only 0 for the first sample interval of a
     * working trim, so the DC verdict is merely deferred, not lost; on a trim that never
     * measures anything, refusing to guess is I2. Overcharging permanently to fix a
     * seconds-long label transient is not a trade worth making.
     */
    static int deriveIsDc(int gunState, double peakKw) {
        int verdict = ChargingTypeClassifier.classify(gunState, peakKw);
        if (verdict != ChargingTypeClassifier.UNKNOWN) return verdict;
        // The gun-based classifier above has nothing to go on -- most
        // commonly a car_service-fed session (CarSvcTelemetry never
        // populates gun_state at all), where it lands on UNKNOWN unless
        // peak happens to clear the conservative 25kW power-only DC floor.
        // Without this fallback, a session like that stays classified as
        // UNKNOWN/null forever once it closes: every one of the close
        // paths below calls deriveIsDc() fresh and persists whatever it
        // returns, so a perfectly good peak_power_kw reading (e.g. a
        // 1.4kW AC session) would otherwise never turn into a real AC/DC
        // verdict. Reuse the SAME >=11kW peak-only threshold
        // updateOpenSessionPeakAvgPower() already applies live for this
        // exact no-gun-state situation, so a session that closes (normal
        // SESSION END, or the startup sweep for one abandoned mid-charge
        // by an app restart) before ever revisiting that live update still
        // ends up classified instead of stuck.
        if (peakKw > 0) {
            return peakKw >= LIVE_PEAK_ONLY_DC_THRESHOLD_KW
                    ? ChargingTypeClassifier.DC : ChargingTypeClassifier.AC;
        }
        return ChargingTypeClassifier.UNKNOWN;
    }

    /**
     * Peak-only AC/DC threshold used when no gun-state evidence exists at
     * all (see {@link #deriveIsDc} and {@link #updateOpenSessionPeakAvgPower}).
     * Deliberately far below {@link ChargingTypeClassifier#DC_POWER_ONLY_MIN_PEAK_KW}
     * -- that 25kW floor exists to avoid misreading a genuine high-power AC
     * session as DC when SOME gun evidence (even if unreliable) is
     * available; this threshold instead only ever applies when there is
     * NO gun evidence whatsoever, where a much lower bar is the best
     * available signal.
     */
    private static final double LIVE_PEAK_ONLY_DC_THRESHOLD_KW = 11.0;

    private int currentChargingTypeVerdict() {
        if (chargingTypeVerdict != ChargingTypeClassifier.AC
                && chargingTypeVerdict != ChargingTypeClassifier.DC) {
            chargingTypeVerdict = deriveIsDc(
                    chargingGunState, chargingPeakPower);
        }
        return chargingTypeVerdict;
    }

    private static int deferredChargingTypeVerdict(
            DeferredChargingGeneration generation) {
        if (generation.typeVerdict != ChargingTypeClassifier.AC
                && generation.typeVerdict != ChargingTypeClassifier.DC) {
            generation.typeVerdict = deriveIsDc(
                    generation.gun, generation.peakPower);
        }
        return generation.typeVerdict;
    }

    /**
     * Latch charger type from an inferred rate without admitting that rate to peak, average, energy,
     * or cost accounting. Only an explicit DC gun plus a high corroborating rate can change it.
     */
    public synchronized boolean observeEstimatedChargingTypeEvidence(
            double estimatedPowerKw) {
        if (!Double.isFinite(estimatedPowerKw)
                || estimatedPowerKw <= 0.0 || estimatedPowerKw > 500.0) {
            return true;
        }
        DeferredChargingGeneration generation =
                currentDeferredPhysicalGeneration();
        if (generation != null && !generation.isEnded()) {
            int verdict = ChargingTypeClassifier.classifyWithCorroboratingPower(
                    generation.gun, generation.peakPower, estimatedPowerKw);
            if (generation.typeVerdict == ChargingTypeClassifier.UNKNOWN
                    && verdict != ChargingTypeClassifier.UNKNOWN) {
                generation.typeVerdict = verdict;
                return persistChargingLifecycleJournal();
            }
            return !chargingLifecycleJournalDirty
                    || persistChargingLifecycleJournal();
        }
        if (!wasCharging || chargingStartTime <= 0L) return true;
        int verdict = ChargingTypeClassifier.classifyWithCorroboratingPower(
                chargingGunState, chargingPeakPower, estimatedPowerKw);
        if (chargingTypeVerdict == ChargingTypeClassifier.UNKNOWN
                && verdict != ChargingTypeClassifier.UNKNOWN) {
            chargingTypeVerdict = verdict;
            return persistChargingLifecycleJournal();
        }
        return !chargingLifecycleJournalDirty
                || persistChargingLifecycleJournal();
    }

    /**
     * Current reading of the vehicle's per-session charged-energy counter, kWh, or NaN.
     *
     * <p>Only returns a value the collector has ADMITTED (gun asserting a charging connection or a
     * live fused verdict, non-terminal BMS state) — the collector NaNs the field otherwise, so a
     * counter still holding its value with the gun out cannot be read here as live.
     */
    /**
     * The {@code SRC_*} key of the counter the snapshot path should read, or null when none qualifies.
     *
     * <p>ONCE A SESSION HAS AN OWNER, THIS RETURNS ONLY THAT OWNER. The snapshot path feeds the
     * accumulator directly from session start, tick and close, so if it silently switched sources
     * mid-session it would interleave two independent monotonic series into one — the exact corruption
     * the ownership rule exists to prevent, entered through a door that never consulted the rule.
     * Preference order only applies while no owner is bound yet.
     */
    private String resolveCounterSource(com.overdrive.app.byd.BydVehicleData vd) {
        if (vd == null) return null;
        boolean capAlive = Double.isFinite(vd.chargingCapacityKwh)
                && vd.chargingCapacityKwh >= 0
                && vd.chargingCapacityKwh
                        <= com.overdrive.app.charging.ChargeCounterAccumulator
                                .COUNTER_FULL_SCALE_KWH + 1.0;
        boolean extAlive = Double.isFinite(vd.externalChargingPowerKw)
                && vd.externalChargingPowerKw >= 0
                && vd.externalChargingPowerKw <= EXTERNAL_COUNTER_FULL_SCALE_KWH
                && com.overdrive.app.byd.ChargeSourceClassifier.isCounter(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL);
        String owner = counterOwner;
        if (owner != null) {
            // Bound already — honour it exclusively, and report nothing if it has gone quiet. Returning
            // the other source here is what would mix the series.
            if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY.equals(owner)) {
                return capAlive ? com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY : null;
            }
            if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL.equals(owner)) {
                return extAlive ? com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL : null;
            }
            return null;
        }
        // Unbound. If an INTERRUPTED row is offering a continuation, prefer the source IT used: binding
        // the other one makes the pairing cross-series and the outage energy is refused. Only then fall
        // back to the documented per-session counter.
        String wanted = pendingContinuationSourceHint();
        if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL.equals(wanted) && extAlive) {
            return com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL;
        }
        if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY.equals(wanted) && capAlive) {
            return com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY;
        }
        if (capAlive) return com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY;
        if (extAlive) return com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL;
        return null;
    }

    /**
     * Raw reading of the counter that owns this session, kWh, or NaN.
     *
     * <p>Every caller feeds this straight into the accumulator, so it MUST be the owner's value — see
     * {@link #resolveCounterSource}. Binds the owner on first use so that the source which establishes
     * the baseline is the one that keeps it.
     */
    private double snapshotChargeCounterKwh() {
        try {
            com.overdrive.app.byd.BydDataCollector col = com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) {
                com.overdrive.app.byd.BydVehicleData vd = col.getData();
                String src = resolveCounterSource(vd);
                if (src == null) return Double.NaN;
                if (counterOwner == null) bindCounterOwner(src);
                else if (!counterOwner.equals(src)) return Double.NaN;
                return snapshotCounterForSource(vd, src);
            }
        } catch (Exception ignored) {}
        return Double.NaN;
    }

    /** Read one explicit counter without changing the active row's owner. */
    private double snapshotCounterForSource(com.overdrive.app.byd.BydVehicleData vd, String source) {
        if (vd == null || source == null) return Double.NaN;
        if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL.equals(source)) {
            if (!Double.isFinite(vd.externalChargingPowerKw) || vd.externalChargingPowerKw < 0
                    || vd.externalChargingPowerKw > EXTERNAL_COUNTER_FULL_SCALE_KWH
                    || !com.overdrive.app.byd.ChargeSourceClassifier.isCounter(source)) {
                return Double.NaN;
            }
            // Same unit calibration the push path applies — both paths feed the same accumulator.
            double div = 1.0;
            try {
                div = com.overdrive.app.monitor.ChargeRateResolver.counterUnitDivisor(source);
            } catch (Throwable ignored) {}
            return div > 1.0 ? vd.externalChargingPowerKw / div : vd.externalChargingPowerKw;
        }
        if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY.equals(source)
                && Double.isFinite(vd.chargingCapacityKwh)
                && vd.chargingCapacityKwh >= 0
                && vd.chargingCapacityKwh
                        <= com.overdrive.app.charging.ChargeCounterAccumulator
                                .COUNTER_FULL_SCALE_KWH + 1.0) {
            // Zero is the ideal post-reset baseline and is therefore valid.
            return vd.chargingCapacityKwh;
        }
        return Double.NaN;
    }

    private void rememberCounterBaselineCandidate(double counterKwh, long observedAt) {
        if (!Double.isFinite(counterKwh) || counterKwh < 0) return;
        if (Double.isNaN(counterBaselineCandidateKwh)
                || counterKwh < counterBaselineCandidateKwh) {
            counterBaselineCandidateKwh = counterKwh;
            counterBaselineCandidateAtMs = observedAt;
        }
        counterBaselineLatestKwh = counterKwh;
        counterBaselineLatestAtMs = observedAt;
        if (wasCharging && chargingStartTime > 0L) {
            persistChargingLifecycleJournal();
        }
    }

    private void clearCounterBaselineCandidates() {
        counterBaselineCandidateKwh = Double.NaN;
        counterBaselineCandidateAtMs = 0L;
        counterBaselineLatestKwh = Double.NaN;
        counterBaselineLatestAtMs = 0L;
    }

    /** Admit one final counter snapshot without letting a stale previous-session value become energy. */
    private void observeFinalCounterForClose(double counterKwh, double endSoc, long observedAt) {
        if (!Double.isFinite(counterKwh) || counterKwh < 0) return;
        chargingCounter.setIndependentEstimate(socEstimateForOpenSession(endSoc));
        if (counterBaselinePending) {
            rememberCounterBaselineCandidate(counterKwh, observedAt);
            if (tryLateContinuation(counterKwh, observedAt)) {
                counterBaselinePending = false;
                counterBaselinePendingSinceMs = 0;
            } else {
                boolean fresh = Double.isNaN(lastSessionCounterKwh)
                        || counterKwh < lastSessionCounterKwh;
                if (!fresh) {
                    // A continuous (non-session-resetting) counter never becomes lower than the prior
                    // endpoint. Its earliest in-session reading is still a valid baseline; use it at
                    // close so a short session does not lose its entire metered delta while waiting for
                    // the six-minute escape hatch.
                    double baseline = counterBaselineCandidateKwh;
                    long baselineAt = counterBaselineCandidateAtMs;
                    if (Double.isNaN(baseline) || baseline > counterKwh) return;
                    counterBaselinePending = false;
                    counterBaselinePendingSinceMs = 0;
                    consumeSupersededSweepMarker(
                            "the continuous counter's earliest session reading became its baseline");
                    chargingCounter.observe(
                            baseline, baselineAt > 0L ? baselineAt : observedAt);
                    if (counterKwh != baseline) {
                        chargingCounter.observe(counterKwh, observedAt);
                    }
                    clearCounterBaselineCandidates();
                    lastSessionCounterKwh = counterKwh;
                    counterProgressDirty = true;
                    return;
                }
                counterBaselinePending = false;
                counterBaselinePendingSinceMs = 0;
                consumeSupersededSweepMarker("the final boundary counter established a fresh baseline");
            }
        }
        chargingCounter.observe(counterKwh, observedAt);
        clearCounterBaselineCandidates();
        lastSessionCounterKwh = counterKwh;
        counterProgressDirty = true;
    }

    /** True when the BMS currently reports a terminal (non-charging) state, or the gun is out. */
    private boolean isTerminalChargingStateNow() {
        try {
            com.overdrive.app.byd.BydDataCollector col =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col == null || !col.isInitialized()) return false;
            com.overdrive.app.byd.BydVehicleData vd = col.getData();
            if (vd == null) return false;
            if (vd.chargingGunState == 1) return true;
            return isTerminalChargingStateCode(vd.chargingState);
        } catch (Throwable ignored) {}
        return false;
    }

    static boolean isTerminalChargingStateCode(int state) {
        return state == 0 || state == 2 || state == 3 || state == 4
                || (state >= 5 && state <= 8)
                || state == 10 || state == 11 || state == 12;
    }

    /**
     * The counter source an interrupted row is offering for continuation, or null.
     *
     * <p>Consulted only while ownership is unbound, so a session that is about to continue an interrupted
     * charge binds the SAME series that charge was using. Cheap: one indexed lookup once per session.
     */
    private String pendingContinuationSourceHint() {
        if (!counterBaselinePending) return null;
        if (claimedContinuationSessionStart == chargingStartTime
                && claimedContinuationSource != null) {
            return claimedContinuationSource;
        }
        return continuationSource;
    }

    /**
     * Bind the accumulator to a counter source and declare its modulus.
     *
     * <p>Single place that establishes ownership, so the push path and the snapshot path cannot disagree
     * about which counter a session is using. The modulus must be set before the first observation
     * because the wrap arithmetic keys off it and it is immutable once a series has begun.
     */
    /**
     * Modulus to use for a persisted row's counter, kWh.
     *
     * <p>A row written before {@code counter_source} existed has no recorded owner. Defaulting such a
     * row to the dedicated-capacity scale is right for the common case but wrong for the external one,
     * and the accumulator cannot be re-scaled once a baseline exists, so the choice made here is final.
     * When the source is unknown, infer it from the endpoints themselves: a value above the dedicated
     * counter's ceiling can only belong to the wider accessor. Below that ceiling both are possible, so
     * the dedicated capacity counter remains the conservative default.
     */
    /**
     * Decide which counter a restored legacy series belongs to, by seeing which one currently reads
     * close to its stored endpoint.
     *
     * <p>The endpoint is the last value we observed from the owning counter, so the owner should still
     * be near it — a counter only rises within a session. The other source has no reason to agree.
     * Falls back to the documented capacity counter when neither is decisive, because that is what
     * legacy rows were overwhelmingly written by.
     */
    private String inferOwnerFromLiveReading(double storedLast) {
        String def = com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY;
        if (Double.isNaN(storedLast)) return def;
        try {
            com.overdrive.app.byd.BydDataCollector col =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col == null || !col.isInitialized()) return def;
            com.overdrive.app.byd.BydVehicleData vd = col.getData();
            if (vd == null) return def;
            // DIRECTION FIRST, THEN DISTANCE. "Closest to the stored endpoint" is the wrong test on its
            // own: after an outage the genuine owner has kept RISING and moved away from its endpoint,
            // while the other counter can sit coincidentally near it — so the naive comparison actively
            // favours the wrong source, and the resulting cross-series difference corrupts added energy.
            //
            // A counter only rises within a session, so the owner's live value must be AT OR ABOVE its
            // endpoint. That rules a candidate in or out on physics rather than on proximity. Only when
            // both are still plausible does distance decide, and then the SMALLER advance is preferred
            // because the outage bounds how much could really have been delivered.
            boolean capValid = Double.isFinite(vd.chargingCapacityKwh)
                    && vd.chargingCapacityKwh >= 0
                    && vd.chargingCapacityKwh
                            <= com.overdrive.app.charging.ChargeCounterAccumulator
                                    .COUNTER_FULL_SCALE_KWH + 1.0;
            boolean extValid = Double.isFinite(vd.externalChargingPowerKw)
                    && vd.externalChargingPowerKw >= 0
                    && com.overdrive.app.byd.ChargeSourceClassifier.isCounter(
                            com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL);
            double externalKwh = vd.externalChargingPowerKw;
            if (extValid) {
                try {
                    double divisor = com.overdrive.app.monitor.ChargeRateResolver.counterUnitDivisor(
                            com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL);
                    if (divisor > 1.0) externalKwh /= divisor;
                } catch (Throwable ignored) {}
            }
            // Advance since the endpoint; NaN when the candidate is absent or has moved BACKWARDS,
            // which a counter cannot do mid-session and therefore disqualifies it.
            double capAdvance = (capValid && vd.chargingCapacityKwh >= storedLast)
                    ? (vd.chargingCapacityKwh - storedLast) : Double.NaN;
            double extAdvance = (extValid && externalKwh >= storedLast)
                    ? (externalKwh - storedLast) : Double.NaN;
            // An advance larger than any outage could deliver is not this series either.
            if (!Double.isNaN(capAdvance) && capAdvance > LEGACY_CONTINUATION_MAX_GAP_KWH) {
                capAdvance = Double.NaN;
            }
            if (!Double.isNaN(extAdvance) && extAdvance > LEGACY_CONTINUATION_MAX_GAP_KWH) {
                extAdvance = Double.NaN;
            }
            if (Double.isNaN(capAdvance) && Double.isNaN(extAdvance)) return def;
            if (Double.isNaN(capAdvance)) return com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL;
            if (Double.isNaN(extAdvance)) return def;
            // BOTH still plausible — so this is a coincidence, and picking either risks differencing two
            // unrelated series. A legacy row predates external-counter support entirely, so it can only
            // have been written by the capacity counter; that is evidence, not a coin toss. Take the
            // default and log, rather than letting the smaller advance decide an ambiguous case.
            logger.info(String.format(java.util.Locale.US,
                    "Legacy counter row is ambiguous — both counters are plausible against endpoint"
                    + " %.3f (capacity +%.3f, external +%.3f). Binding capacity, which is what rows"
                    + " predating source tagging were written by.",
                    storedLast, capAdvance, extAdvance));
            return def;
        } catch (Throwable ignored) {}
        return def;
    }

    /**
     * The accumulator's total corrected for a PROVEN counter register-width fault, kWh, or NaN.
     *
     * <p>The one place a metered figure crosses from the raw counter frame into the priced frame. The
     * accumulator deliberately stays in the counter's own units — its wrap, saturation and reset
     * arithmetic all key off the register's real modulus, and {@code counter_energy_kwh} round-trips
     * through the database in that frame, so correcting inside it would double-apply on every restore.
     *
     * <p>An unproven suspicion does not change the numeric frame here. The resolver receives the raw
     * total together with the separate suspicion flag so it can compare that total with the measured
     * integral and SOC estimate. Discarding it here erased the ratio that proves a half-scale fault.
     */
    private double meteredEnergyKwh() {
        if (!chargingCounter.hasBaseline()) return Double.NaN;
        double raw = chargingCounter.energyKwh();
        if (Double.isNaN(raw)) return Double.NaN;
        String source = counterOwner != null
                ? counterOwner : com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY;
        try {
            double factor = com.overdrive.app.charging.CounterScaleCalibrator.factorFor(source);
            if (factor != 1.0) return raw * factor;
        } catch (Throwable ignored) { /* uncalibrated: behave exactly as before */ }
        return raw;
    }

    /**
     * Whether the metered counter's UNIT is under suspicion, so its total must not be priced or
     * offered for SOH calibration. See {@link com.overdrive.app.charging.CounterScaleCalibrator}.
     */
    private boolean counterScaleSuspect(String source) {
        String owner = source != null ? source
                : counterOwner != null ? counterOwner
                : com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY;
        try {
            return com.overdrive.app.charging.CounterScaleCalibrator.isScaleSuspect(owner);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * As {@link #meteredEnergyKwh}, for a total read back from a stored row rather than the live
     * accumulator. A null source predates source tagging; the capacity counter is the default owner.
     */
    private double correctStoredCounterEnergy(double storedKwh, String source) {
        if (Double.isNaN(storedKwh)) return storedKwh;
        String owner = source != null
                ? source : com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY;
        try {
            double factor = com.overdrive.app.charging.CounterScaleCalibrator.factorFor(owner);
            if (factor != 1.0) return storedKwh * factor;
        } catch (Throwable ignored) { /* uncalibrated: behave exactly as before */ }
        return storedKwh;
    }

    private double counterScaleForSource(String source) {
        if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL.equals(source)) {
            double divisor = 1.0;
            try {
                divisor = com.overdrive.app.monitor.ChargeRateResolver.counterUnitDivisor(source);
            } catch (Throwable ignored) {}
            return EXTERNAL_COUNTER_FULL_SCALE_KWH / Math.max(1.0, divisor);
        }
        return com.overdrive.app.charging.ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH;
    }

    /** As {@link #counterScaleForSource}, with a fallback inferred from observed endpoints. */
    private double counterScaleForSource(String source, double... endpoints) {
        if (source != null) return counterScaleForSource(source);
        double ceiling = com.overdrive.app.charging.ChargeCounterAccumulator.COUNTER_FULL_SCALE_KWH;
        for (double v : endpoints) {
            if (!Double.isNaN(v) && v > ceiling) return EXTERNAL_COUNTER_FULL_SCALE_KWH;
        }
        return ceiling;
    }

    private void bindCounterOwner(String source) {
        counterOwner = source;
        if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL.equals(source)) {
            chargingCounter.setFullScaleKwh(counterScaleForSource(source));
        }
        chargingCounter.markPersistenceMetadataChanged();
        if (wasCharging && chargingStartTime > 0L) {
            persistChargingLifecycleJournal();
        }
        logger.info("Charged-energy accumulator bound to source '" + source + "' for this session");
    }

    /**
     * Independent SOC-derived energy estimate for the open session, kWh, or NaN.
     *
     * <p>This is the cross-check that decides whether a metered figure is believable. It is coarse
     * (bounded by the 1% gauge quantum and by the capacity figure) but always monotonic, which is
     * exactly the property the counter lacks.
     */
    private double socEstimateForOpenSession(double endSoc) {
        try {
            if (Double.isNaN(endSoc) || chargingStartSoc < 0) return Double.NaN;
            com.overdrive.app.abrp.SohEstimator soh = getSohEstimator();
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                    soh != null ? soh.getCapacitySohSnapshot() : null;
            double nominal = capacitySoh != null
                    ? capacitySoh.getNominalCapacityKwh() : 0;
            double sohPct = capacitySoh != null && capacitySoh.hasDisplaySoh()
                    ? capacitySoh.getDisplaySoh() : Double.NaN;
            return com.overdrive.app.charging.SessionEnergyResolver.socEstimateKwh(
                    endSoc - chargingStartSoc, nominal, sohPct);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private CounterRestoreState readCounterRestoreState(long sessionStart) throws Exception {
        if (connection == null || sessionStart <= 0) {
            throw new java.sql.SQLException("counter restore requested without a session");
        }
        CounterRestoreState state = new CounterRestoreState();
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT counter_start_kwh, counter_last_kwh, counter_energy_kwh,"
                + " energy_incomplete, counter_source, counter_full_scale_kwh,"
                + " counter_last_at_ms, counter_observation_generation,"
                + " counter_wrap_count, counter_reset_count,"
                + " counter_ceiling_streak, counter_saturated, counter_abandoned_kwh,"
                + " counter_unattributed_gaps, counter_awaiting_gap,"
                + " counter_gap_reconstructed, counter_gap_estimate_kwh,"
                + " counter_recent_rate_kwh_per_h"
                + " FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
            p.setLong(1, sessionStart);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) throw new java.sql.SQLException("counter restore row missing");
                state.baseline = rs.getDouble(1);
                if (rs.wasNull()) state.baseline = Double.NaN;
                state.last = rs.getDouble(2);
                if (rs.wasNull()) state.last = Double.NaN;
                state.energy = rs.getDouble(3);
                if (rs.wasNull()) state.energy = Double.NaN;
                state.incomplete = rs.getInt(4) == 1;
                state.source = rs.getString(5);
                if (state.source != null && state.source.isEmpty()) state.source = null;
                state.fullScale = rs.getDouble(6);
                if (rs.wasNull()) state.fullScale = Double.NaN;
                com.overdrive.app.charging.ChargeCounterAccumulator.State exact =
                        new com.overdrive.app.charging.ChargeCounterAccumulator.State();
                exact.baseline = state.baseline;
                exact.last = state.last;
                exact.accumulated = !Double.isNaN(state.energy) ? state.energy : 0.0;
                exact.fullScaleKwh = state.fullScale;
                exact.lastAtMs = rs.getLong(7);
                exact.observationGeneration = Math.max(0L, rs.getLong(8));
                exact.wraps = Math.max(0, rs.getInt(9));
                exact.resets = Math.max(0, rs.getInt(10));
                exact.ceilingStreak = Math.max(0, rs.getInt(11));
                exact.saturated = rs.getInt(12) == 1;
                exact.abandonedKwh = Math.max(0.0, rs.getDouble(13));
                exact.unattributedGaps = Math.max(0, rs.getInt(14));
                exact.awaitingGapReconcile = rs.getInt(15) == 1;
                exact.gapReconstructed = rs.getInt(16) == 1;
                exact.gapEstimateKwh = rs.getDouble(17);
                if (rs.wasNull()) exact.gapEstimateKwh = Double.NaN;
                exact.recentRateKwhPerH = rs.getDouble(18);
                if (rs.wasNull()) exact.recentRateKwhPerH = Double.NaN;
                state.exactState = exact;
            }
        }
        return state;
    }

    private void applyCounterRestoreState(CounterRestoreState state) {
        if (Double.isNaN(state.baseline) && Double.isNaN(state.energy)) {
            chargingCounter.reset();
            counterOwner = null;
            counterBaselinePending = true;
            counterBaselinePendingSinceMs = System.currentTimeMillis();
            counterProgressDirty = false;
            return;
        }
        double gapEstimate = Double.NaN;
        try {
            BatterySocData sd = VehicleDataMonitor.getInstance().getBatterySoc();
            if (sd != null && sd.socPercent >= 0 && sd.socPercent <= 100) {
                double total = socEstimateForOpenSession(sd.socPercent);
                double already = (!Double.isNaN(state.energy) && state.energy > 0)
                        ? state.energy : 0;
                if (!Double.isNaN(total) && total > already) gapEstimate = total - already;
            }
        } catch (Exception ignored) {}

        double scale = !Double.isNaN(state.fullScale) ? state.fullScale
                : counterScaleForSource(state.source, state.baseline, state.last);
        com.overdrive.app.charging.ChargeCounterAccumulator.State durable =
                state.exactState;
        if (durable == null) {
            durable = new com.overdrive.app.charging.ChargeCounterAccumulator.State();
            durable.baseline = state.baseline;
            durable.last = state.last;
            durable.accumulated = !Double.isNaN(state.energy) ? state.energy : 0.0;
            durable.fullScaleKwh = scale;
        } else if (Double.isNaN(durable.fullScaleKwh)) {
            durable.fullScaleKwh = scale;
        }
        chargingCounter.restoreState(
                com.overdrive.app.charging.ChargeCounterAccumulator.newestCompleteState(
                        null, durable, state.incomplete));
        chargingCounter.beginGapReconciliation(gapEstimate);
        counterOwner = state.source;
        if (counterOwner == null) {
            counterOwner = scale > com.overdrive.app.charging.ChargeCounterAccumulator
                    .COUNTER_FULL_SCALE_KWH
                    ? com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL
                    : inferOwnerFromLiveReading(state.last);
        }
        counterBaselinePending = false;
        counterBaselinePendingSinceMs = 0;
        counterProgressDirty = false;
        logger.info(String.format(java.util.Locale.US,
                "Restored charged-energy accumulator for session %d:"
                + " baseline=%.3f last=%.3f accumulated=%.3f incomplete=%s gapEstimate=%s",
                chargingStartTime, state.baseline, state.last, state.energy, state.incomplete,
                Double.isNaN(gapEstimate) ? "n/a" : String.format("%.3f", gapEstimate)));
    }

    private ContinuationOffer findImmediateContinuationOffer(long newSessionStart) throws Exception {
        if (connection == null) throw new java.sql.SQLException("continuation lookup unavailable");
        long since = newSessionStart - CONTINUATION_LOOKBACK_MS;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT start_time, counter_last_kwh, counter_source, continuation_claimed,"
                + " end_time, closed_by_sweep, end_soc, counter_full_scale_kwh"
                + " FROM " + TABLE_CHARGING
                + " WHERE start_time < ?"
                + " ORDER BY start_time DESC LIMIT 1;")) {
            p.setLong(1, newSessionStart);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                long rowStart = rs.getLong(1);
                int claimed = rs.getInt(4);
                rs.getLong(5);
                boolean endIsNull = rs.wasNull();
                boolean swept = rs.getInt(6) == 1;
                if (rowStart < since || claimed != 0 || (!endIsNull && !swept)) return null;
                double endpoint = rs.getDouble(2);
                if (rs.wasNull() || endpoint < 0) endpoint = Double.NaN;
                double startSoc = rs.getDouble(7);
                if (rs.wasNull()) startSoc = Double.NaN;
                double fullScale = rs.getDouble(8);
                if (rs.wasNull()) fullScale = Double.NaN;
                return new ContinuationOffer(
                        rowStart, endpoint, rs.getString(3), startSoc, fullScale);
            }
        }
    }

    /** Claim the offer in the caller's session-INSERT transaction. */
    private void claimContinuationOffer(ContinuationOffer offer) throws Exception {
        if (offer == null) return;
        try (PreparedStatement claim = connection.prepareStatement(
                "UPDATE " + TABLE_CHARGING
                + " SET continuation_claimed = 1"
                + " WHERE start_time = ? AND continuation_claimed = 0;")) {
            claim.setLong(1, offer.rowStart);
            if (claim.executeUpdate() != 1) {
                throw new java.sql.SQLException(
                        "continuation offer was claimed concurrently: " + offer.rowStart);
            }
        }
    }

    private void activateClaimedContinuationOffer(ContinuationOffer offer, long sessionStart,
                                                   boolean alreadyResolved) {
        clearClaimedContinuationOffer();
        if (offer == null || alreadyResolved) return;
        claimedContinuationSessionStart = sessionStart;
        claimedContinuationRow = offer.rowStart;
        claimedContinuationEndpointKwh = offer.endpointKwh;
        claimedContinuationSource = offer.source;
        claimedContinuationStartSoc = offer.startSoc;
        claimedContinuationFullScaleKwh = offer.fullScaleKwh;
        continuationSource = offer.source;
        pendingSweepMarkerRow = offer.rowStart;
    }

    private void clearClaimedContinuationOffer() {
        claimedContinuationSessionStart = 0L;
        claimedContinuationRow = -1L;
        claimedContinuationEndpointKwh = Double.NaN;
        claimedContinuationSource = null;
        claimedContinuationStartSoc = Double.NaN;
        claimedContinuationFullScaleKwh = Double.NaN;
        continuationSource = null;
        pendingSweepMarkerRow = -1L;
    }

    private void consumeSupersededSweepMarker(String why) {
        if (claimedContinuationSessionStart == chargingStartTime
                && !Double.isNaN(claimedContinuationEndpointKwh)) {
            logger.info("Continuation offer was claimed but not applied — " + why);
        }
        clearClaimedContinuationOffer();
    }

    private double currentSocForContinuation() {
        try {
            BatterySocData sd = VehicleDataMonitor.getInstance().getBatterySoc();
            if (sd != null && sd.socPercent >= 0 && sd.socPercent <= 100) {
                return sd.socPercent;
            }
        } catch (Throwable ignored) {}
        return lastRecordedSoc >= 0 ? lastRecordedSoc : chargingStartSoc;
    }

    /**
     * Seed this row from an interrupted row's endpoint, selecting plain versus wrapped counter
     * progression against the SOC movement that covers the same outage interval.
     */
    private boolean applyContinuationOffer(ContinuationOffer offer, double counterNow,
                                           double currentSoc, long observedAt) {
        if (offer == null || Double.isNaN(offer.endpointKwh) || Double.isNaN(counterNow)) {
            return false;
        }
        double scale = !Double.isNaN(offer.fullScaleKwh)
                ? offer.fullScaleKwh
                : counterScaleForSource(offer.source, offer.endpointKwh, counterNow);
        double socEstimate = Double.NaN;
        if (!Double.isNaN(offer.startSoc) && currentSoc >= offer.startSoc) {
            try {
                com.overdrive.app.abrp.SohEstimator soh = getSohEstimator();
                com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                        soh != null ? soh.getCapacitySohSnapshot() : null;
                socEstimate = com.overdrive.app.charging.SessionEnergyResolver.socEstimateKwh(
                        currentSoc - offer.startSoc,
                        capacitySoh != null ? capacitySoh.getNominalCapacityKwh() : 0,
                        capacitySoh != null && capacitySoh.hasDisplaySoh()
                                ? capacitySoh.getDisplaySoh() : Double.NaN);
            } catch (Throwable ignored) {}
        }
        double[] candidates = com.overdrive.app.charging.ChargeCounterAccumulator.gapCandidatesKwh(
                offer.endpointKwh, counterNow, scale);
        double chosen = com.overdrive.app.charging.SessionEnergyResolver
                .continuationCounterEnergyKwh(offer.endpointKwh, counterNow, scale, socEstimate);
        if (Double.isNaN(chosen)) return false;

        double continuationStartSoc = chargingStartSoc;
        if (!Double.isNaN(offer.startSoc) && offer.startSoc >= 0 && offer.startSoc <= currentSoc) {
            continuationStartSoc = offer.startSoc;
            if (wasCharging && chargingStartTime > 0) {
                try (PreparedStatement p = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING
                                + " SET start_soc = ? WHERE start_time = ? AND end_time IS NULL;")) {
                    p.setDouble(1, continuationStartSoc);
                    p.setLong(2, chargingStartTime);
                    if (p.executeUpdate() != 1) return false;
                } catch (Exception e) {
                    return false;
                }
            }
        }
        boolean unverifiedAmbiguity = (Double.isNaN(socEstimate) || socEstimate <= 0)
                && candidates.length > 1;
        chargingCounter.restore(
                offer.endpointKwh, counterNow, chosen, unverifiedAmbiguity,
                Double.NaN, scale);
        // Clear restore's pending-next-observation state without adding another delta.
        chargingCounter.observe(counterNow, observedAt);
        chargingCounter.markReconstructedGap();
        counterOwner = offer.source != null ? offer.source : counterOwner;
        counterBaselinePending = false;
        counterBaselinePendingSinceMs = 0L;
        counterProgressDirty = true;
        lastSessionCounterKwh = counterNow;
        chargingStartSoc = continuationStartSoc;
        logger.info(String.format(java.util.Locale.US,
                "Continuation applied: counter %.3f -> %.3f, selected %.3f kWh"
                        + " (SOC estimate %s, full scale %.3f)",
                offer.endpointKwh, counterNow, chosen,
                Double.isNaN(socEstimate) ? "n/a" : String.format("%.3f", socEstimate), scale));
        return true;
    }

    private boolean tryLateContinuation(double counterNow, long now) {
        if (Double.isNaN(counterNow)
                || claimedContinuationSessionStart != chargingStartTime
                || Double.isNaN(claimedContinuationEndpointKwh)) {
            return false;
        }
        double prevLast = claimedContinuationEndpointKwh;
        String source = claimedContinuationSource;
        boolean sameSeries = source != null
                ? source.equals(counterOwner)
                : ((com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY.equals(counterOwner)
                        || counterOwner == null)
                   && prevLast <= com.overdrive.app.charging.ChargeCounterAccumulator
                        .COUNTER_FULL_SCALE_KWH);
        if (!sameSeries) return false;
        if (source == null && counterNow >= prevLast
                && counterNow - prevLast > LEGACY_CONTINUATION_MAX_GAP_KWH) {
            return false;
        }
        ContinuationOffer offer = new ContinuationOffer(
                claimedContinuationRow, prevLast, source,
                claimedContinuationStartSoc, claimedContinuationFullScaleKwh);
        if (!applyContinuationOffer(offer, counterNow, currentSocForContinuation(), now)) {
            return false;
        }
        clearClaimedContinuationOffer();
        return true;
    }

    /**
     * Persist counter endpoints for restart recovery.
     *
     * @return true only when the current open row confirmed the update
     */
    private boolean persistCounterProgress() {
        final Connection conn = connection;
        if (!chargingCounter.hasBaseline() || chargingStartTime <= 0) {
            boolean journalDurable = persistChargingLifecycleJournal();
            counterProgressDirty = !journalDurable;
            return journalDurable;
        }
        if (conn == null) {
            counterProgressDirty = true;
            return false;
        }
        counterProgressDirty = true;
        try (PreparedStatement p = conn.prepareStatement(
                "UPDATE " + TABLE_CHARGING + " SET counter_start_kwh = ?, counter_last_kwh = ?, " +
                "counter_energy_kwh = ?, energy_incomplete = ?, counter_source = ?, " +
                "counter_full_scale_kwh = ?, counter_last_at_ms = ?, " +
                "counter_observation_generation = ?, counter_wrap_count = ?, " +
                "counter_reset_count = ?, counter_ceiling_streak = ?, counter_saturated = ?, " +
                "counter_abandoned_kwh = ?, counter_unattributed_gaps = ?, " +
                "counter_awaiting_gap = ?, counter_gap_reconstructed = ?, " +
                "counter_gap_estimate_kwh = ?, counter_recent_rate_kwh_per_h = ? " +
                "WHERE start_time = ? AND end_time IS NULL;")) {
            p.setDouble(1, chargingCounter.baselineKwh());
            p.setDouble(2, chargingCounter.lastRawKwh());
            p.setDouble(3, chargingCounter.energyKwh());
            p.setInt(4, chargingCounter.isIncomplete() ? 1 : 0);
            if (counterOwner != null) p.setString(5, counterOwner); else p.setNull(5, java.sql.Types.VARCHAR);
            p.setDouble(6, chargingCounter.fullScaleKwh());
            int next = bindCounterState(p, 7, chargingCounter.snapshotState());
            p.setLong(next, chargingStartTime);
            if (p.executeUpdate() != 1) {
                throw new java.sql.SQLException("counter progress found no matching open session");
            }
            if (!persistChargingLifecycleJournal()) {
                throw new java.io.IOException(
                        "counter progress lifecycle journal was not durable");
            }
            counterProgressDirty = false;
            noteWriteOk();
            return true;
        } catch (Exception e) {
            logger.debug("persistCounterProgress failed: " + e.getMessage());
            if (isSqlFailure(e)) noteWriteFailed();
            try { reconnect(); } catch (Exception ignored) {}
            return false;
        }
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
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                    soh != null ? soh.getCapacitySohSnapshot() : null;
            double nominal = capacitySoh != null
                    ? capacitySoh.getNominalCapacityKwh() : 0;
            if (nominal > 0) {
                VehicleDataMonitor vm = VehicleDataMonitor.getInstance();
                BatterySocData sd = (vm != null) ? vm.getBatterySoc() : null;
                double soc = (sd != null) ? sd.socPercent : Double.NaN;
                ChargingStateData cs = (vm != null) ? vm.getChargingState() : null;
                // Reject ESTIMATED power, exactly as recordCurrentSoc does for peak/avg. A
                // nominal placeholder (3.3 kW PHEV / 7.0 kW BEV) or an inferred engine-power
                // figure would yield a confident-looking time-to-full derived from a number the
                // cascade itself flagged as not measured — and trackChargingSession LATCHES the
                // most recent positive TTF into the persisted session row, so the guess would
                // outlive the window that produced it. Estimated → no TTF; the caller falls back
                // to the HAL's own charging-rest-time.
                double powerKw = cs != null && !cs.isEstimated
                        && isFinite(cs.chargingPowerKW)
                        && cs.chargingPowerKW > 0
                        && cs.chargingPowerKW <= 500
                        ? cs.chargingPowerKW : Double.NaN;
                double sohFrac = capacitySoh != null && capacitySoh.hasDisplaySoh()
                        ? capacitySoh.getDisplaySoh() / 100.0 : 1.0;
                if (sohFrac <= 0) sohFrac = 1.0;
                if (isFinite(soc) && soc >= 0 && soc < 100
                        && isFinite(powerKw) && powerKw > 0.1) {
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
     * The tariff + rate + currency that price one session — the SINGLE
     * rate-selection point every cost-writing path shares, so AC/DC and
     * mapped/unmapped sessions can't diverge.
     *
     * <p>Resolution, in order:
     * <ol>
     *   <li><b>Location-matched tariff</b> — a {@link TariffProfile} whose circle
     *       contains {@code (lat,lng)} and which prices this gun type. This is
     *       what makes "charging at the same place reuses that place's rate"
     *       automatic. Carries its own currency when the user set one (a public
     *       DC network abroad can bill in a different currency than home).</li>
     *   <li><b>Global DC tariff</b> — when the session is confidently DC
     *       ({@code isDc == 1}) and a global {@code dcRate} is set.</li>
     *   <li><b>Global base rate</b> — the AC/home rate. A tri-state {@code isDc}
     *       of -1 (unknown / peak-downgraded gun misread) or 0 (AC) lands here,
     *       the safe default.</li>
     * </ol>
     *
     * <p>With no tariff profiles configured this collapses to exactly the
     * previous base/DC behaviour, so nothing regresses for existing installs.
     *
     * @param isDc tri-state gun verdict from {@link #deriveIsDc}: 1=DC, 0=AC, -1=unknown
     * @param lat  session start latitude, 0 when no fix was captured
     * @param lng  session start longitude, 0 when no fix was captured
     */
    private PricingDecision priceSession(int isDc, double lat, double lng) {
        try {
            com.overdrive.app.charging.TariffProfile p =
                    com.overdrive.app.charging.TariffManager.getInstance().resolve(lat, lng, isDc);
            if (p != null) {
                double r = p.rateFor(isDc);
                if (r > 0) {
                    String curr = (p.getCurrency() != null && !p.getCurrency().isEmpty())
                            ? p.getCurrency() : globalPricing(isDc).currency;
                    return new PricingDecision(r, curr, p.getId(), p.getLabel());
                }
            }
        } catch (Throwable t) {
            // Tariff layer must never break session accounting — fall through
            // to the global rate exactly as before.
            logger.debug("Tariff resolve skipped: " + t.getMessage());
        }
        return globalPricing(isDc);
    }

    /**
     * Strict monetary snapshot used by every close path.
     *
     * <p>A tariff/config read failure is intentionally propagated so the frozen physical boundary can
     * retry. Returning the global fallback here would make a transient I/O error permanent.
     */
    private PricingDecision priceSessionForClose(int isDc, double lat, double lng)
            throws Exception {
        com.overdrive.app.charging.TariffProfile p =
                com.overdrive.app.charging.TariffManager.getInstance()
                        .resolveStrict(lat, lng, isDc);
        if (p != null) {
            double rate = p.rateFor(isDc);
            if (rate > 0) {
                String currency = p.getCurrency();
                if (currency == null || currency.isEmpty()) {
                    currency = globalPricingStrict(isDc).currency;
                }
                return new PricingDecision(
                        rate, currency, p.getId(), p.getLabel());
            }
        }
        return globalPricingStrict(isDc);
    }

    /**
     * Global (non-tariff) pricing resolved from a SINGLE config read.
     *
     * <p>getElectricityRate/getDcRate/getCurrencySymbol each call loadConfig()
     * separately, so composing them did up to 3 reads per priced row - and both
     * repriceSessionsForTariff and the live session-list render call the pricing
     * path once PER ROW. Same values, one read.
     *
     * <p>Keeps the pre-existing return conventions exactly: an unset base rate is
     * -1 (never 0), an unset DC rate is ignored, and the currency is "" when unset.
     */
    private PricingDecision globalPricing(int isDc) {
        double rate = -1;
        double dc = 0;
        String curr = "";
        try {
            org.json.JSONObject cfg = com.overdrive.app.config.UnifiedConfigManager.loadConfig();
            org.json.JSONObject trips = cfg != null ? cfg.optJSONObject("tripAnalytics") : null;
            if (trips != null) {
                double r = trips.optDouble("electricityRate", -1);
                if (r > 0) rate = r;
                String c = trips.optString("currency", "");
                if (c != null && !c.isEmpty()) curr = c;
            }
            org.json.JSONObject charging = cfg != null ? cfg.optJSONObject("chargingAnalytics") : null;
            if (charging != null) {
                double d = charging.optDouble("dcRate", 0);
                if (d > 0) dc = d;
            }
        } catch (Exception ignored) {}
        if (isDc == 1 && dc > 0) return new PricingDecision(dc, curr, "", "");
        return new PricingDecision(rate, curr, "", "");
    }

    private PricingDecision globalPricingStrict(int isDc) throws Exception {
        org.json.JSONObject cfg =
                com.overdrive.app.charging.TariffManager.loadVerifiedConfig();
        double rate = -1;
        double dc = 0;
        String currency = "";
        org.json.JSONObject trips = cfg.optJSONObject("tripAnalytics");
        if (trips != null) {
            double configured = trips.optDouble("electricityRate", -1);
            if (configured > 0) rate = configured;
            currency = trips.optString("currency", "");
            if (currency == null) currency = "";
        }
        org.json.JSONObject charging = cfg.optJSONObject("chargingAnalytics");
        if (charging != null) {
            double configuredDc = charging.optDouble("dcRate", 0);
            if (configuredDc > 0) dc = configuredDc;
        }
        return new PricingDecision(
                isDc == 1 && dc > 0 ? dc : rate,
                currency, "", "");
    }

    /**
     * Like {@link #priceSession} but the tariff must EARN the match by geofence —
     * the pinned default is never consulted.
     *
     * <p>Used when deciding whether a historical session that the global rate
     * priced may be adopted by a tariff. Falling back to the default there would
     * let one new tariff claim every location-less legacy row at once.
     */
    private PricingDecision priceSessionInCircle(int isDc, double lat, double lng) {
        try {
            com.overdrive.app.charging.TariffProfile p =
                    com.overdrive.app.charging.TariffManager.getInstance()
                            .resolveInCircle(lat, lng, isDc);
            if (p != null) {
                double r = p.rateFor(isDc);
                if (r > 0) {
                    String curr = (p.getCurrency() != null && !p.getCurrency().isEmpty())
                            ? p.getCurrency() : globalPricing(isDc).currency;
                    return new PricingDecision(r, curr, p.getId(), p.getLabel());
                }
            }
        } catch (Throwable t) {
            logger.debug("Tariff geofence resolve skipped: " + t.getMessage());
        }
        // No geofence match: report "global rate" so the caller leaves the row alone.
        return globalPricing(isDc);
    }

    /**
     * Outcome of {@link #priceSession}: the per-kWh rate, the currency to record
     * it in, and the provenance of the tariff that supplied it ({@code tariffId}
     * empty ⇒ the global rate priced this session).
     */
    private static final class PricingDecision {
        final double rate;
        final String currency;
        final String tariffId;
        final String tariffLabel;

        PricingDecision(double rate, String currency, String tariffId, String tariffLabel) {
            this.rate = rate;
            this.currency = currency != null ? currency : "";
            this.tariffId = tariffId != null ? tariffId : "";
            this.tariffLabel = tariffLabel != null ? tariffLabel : "";
        }

        /** Cost for an energy amount, or -1 when this session can't be priced. */
        double costFor(double energyKwh) {
            return (rate > 0 && energyKwh > 0) ? energyKwh * rate : -1;
        }
    }

    private static final class SohCalibrationFrame {
        final String nominalIdentity;
        final long estimatorGeneration;
        final long resetModelEpoch;
        final long priorCalibrationAtMs;

        SohCalibrationFrame(String nominalIdentity, long estimatorGeneration,
                            long resetModelEpoch, long priorCalibrationAtMs) {
            this.nominalIdentity = nominalIdentity;
            this.estimatorGeneration = estimatorGeneration;
            this.resetModelEpoch = resetModelEpoch;
            this.priorCalibrationAtMs = priorCalibrationAtMs;
        }
    }

    private static final class PendingSohCalibration {
        long sessionStart;
        double socDelta;
        double energyKwh;
        double packTempC;
        boolean acCharge;
        double highCellV = Double.NaN;
        String nominalIdentity;
        long estimatorGeneration;
        long resetModelEpoch;
        long priorCalibrationAtMs;
        boolean hasResetModelEpoch;
        boolean hasFrame;
        boolean persistedPayloadValid;
        String persistedPayloadError;
    }

    private SohCalibrationFrame captureSohCalibrationFrame(
            com.overdrive.app.abrp.SohEstimator estimator) {
        if (estimator == null || !estimator.isInitializationReady()) return null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot before =
                        estimator.getCapacitySohSnapshot();
                com.overdrive.app.abrp.SohEstimator.NominalSnapshot nominal =
                        estimator.getNominalSnapshot();
                String modelId = selectedVehicleModelIdentity();
                long priorCalibrationAtMs = estimator.getCalibrationTimestampMs();
                com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot after =
                        estimator.getCapacitySohSnapshot();
                String modelIdAfter = selectedVehicleModelIdentity();
                if (before == null || after == null || nominal == null
                        || !estimator.isInitializationReady()
                        || before.getEstimatorGeneration()
                                != after.getEstimatorGeneration()
                        || before.getResetModelEpoch()
                                != after.getResetModelEpoch()
                        || before.getResetModelEpoch() <= 0
                        || Double.doubleToLongBits(before.getNominalCapacityKwh())
                                != Double.doubleToLongBits(
                                        after.getNominalCapacityKwh())
                        || Double.doubleToLongBits(before.getNominalCapacityKwh())
                                != Double.doubleToLongBits(
                                        nominal.getNominalCapacityKwh())
                        || before.getResetModelEpoch()
                                != nominal.getResetModelEpoch()
                        || nominal.getNominalCapacityKwh() <= 0
                        || !modelId.equals(modelIdAfter)) {
                    continue;
                }
                return new SohCalibrationFrame(
                        sohNominalIdentity(
                                nominal.getNominalCapacityKwh(),
                                nominal.getNominalSource(), modelId),
                        before.getEstimatorGeneration(),
                        before.getResetModelEpoch(),
                        Math.max(0L, priorCalibrationAtMs));
            } catch (Throwable unavailable) {
                return null;
            }
        }
        return null;
    }

    static String sohNominalIdentity(
            double nominalKwh, String nominalSource, String modelId) {
        String source = nominalSource == null || nominalSource.isEmpty()
                ? "unset" : nominalSource;
        String model = modelId == null ? "" : modelId.trim();
        return source + ":" + Long.toHexString(
                Double.doubleToLongBits(nominalKwh)) + ":" + model;
    }

    private static String selectedVehicleModelIdentity() {
        String modelId =
                com.overdrive.app.config.UnifiedConfigManager
                        .getSelectedVehicleModelIdStrict();
        return modelId != null ? modelId.trim() : "";
    }

    /** Exact row image used to reconcile a tariff transaction whose COMMIT result is uncertain. */
    private static final class RepricePostcondition {
        final long startTime;
        final double rate;
        final String currency;
        final double cost;
        final String tariffId;
        final String tariffLabel;
        final boolean costChanged;

        RepricePostcondition(long startTime, double rate, String currency, double cost,
                             String tariffId, String tariffLabel, boolean costChanged) {
            this.startTime = startTime;
            this.rate = rate;
            this.currency = currency != null ? currency : "";
            this.cost = cost;
            this.tariffId = tariffId != null ? tariffId : "";
            this.tariffLabel = tariffLabel != null ? tariffLabel : "";
            this.costChanged = costChanged;
        }
    }

    private static int countCostChanged(java.util.List<RepricePostcondition> expected) {
        int count = 0;
        for (RepricePostcondition row : expected) {
            if (row.costChanged) count++;
        }
        return count;
    }

    private Boolean areRepriceRowsDurable(java.util.List<RepricePostcondition> expected) {
        Connection c = connection;
        if (c == null) return null;
        try (PreparedStatement p = c.prepareStatement(
                "SELECT electricity_rate, currency, session_cost, tariff_id, tariff_label"
                        + " FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
            for (RepricePostcondition row : expected) {
                p.setLong(1, row.startTime);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()
                            || !realStorageEquivalent(
                                    rs.getDouble("electricity_rate"), row.rate)
                            || !realStorageEquivalent(
                                    rs.getDouble("session_cost"), row.cost)
                            || !row.currency.equals(nullToEmpty(rs.getString("currency")))
                            || !row.tariffId.equals(nullToEmpty(rs.getString("tariff_id")))
                            || !row.tariffLabel.equals(
                                    nullToEmpty(rs.getString("tariff_label")))) {
                        return Boolean.FALSE;
                    }
                }
            }
            return Boolean.TRUE;
        } catch (Exception e) {
            logger.debug("Could not reconcile tariff repricing: " + e.getMessage());
            return null;
        }
    }

    private boolean reconcileRepriceCommit(
            java.util.List<RepricePostcondition> expected, Exception failure) {
        Boolean durable = areRepriceRowsDurable(expected);
        if (durable != null) return durable;
        if (isSqlFailure(failure)) noteWriteFailed();
        try { reconnect(); } catch (Exception ignored) {}
        return Boolean.TRUE.equals(areRepriceRowsDurable(expected));
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    /**
     * H2 REAL is IEEE-754 single precision. Compare the representation that is actually stored,
     * including NaN, rather than applying a double-precision epsilon to a float column.
     */
    static boolean realStorageEquivalent(double actual, double expected) {
        return Float.floatToIntBits((float) actual)
                == Float.floatToIntBits((float) expected);
    }

    /**
     * Replay close side effects from authoritative rows.
     *
     * <p>The close transaction stores both the payload and an unapplied flag. This method can run
     * after an ordinary commit, an uncertain commit reconciliation, or a process restart. Tariff
     * usage is assigned from a database aggregate rather than incremented, so a crash between the
     * config write and the row flag update is idempotent. SOH calibration overwrites one deterministic
     * anchor image and is likewise safe to repeat before its flag becomes durable.
     */
    public synchronized void replayPendingChargingPostCommitMetadata() {
        if (!isInitialized || connection == null) return;

        com.overdrive.app.charging.TariffManager tariffManager =
                com.overdrive.app.charging.TariffManager.getInstance();
        // Repricing resolves every row through TariffManager. Load it before the
        // database transaction starts; a lazy load inside that transaction replays
        // config-side reprice intents and recursively enters a second H2 transaction.
        java.util.List<com.overdrive.app.charging.TariffProfile> configuredTariffs =
                tariffManager.getProfiles();
        replayPendingTariffReprices();
        try {
            java.util.LinkedHashSet<String> tariffIds =
                    new java.util.LinkedHashSet<>();
            // Maintenance can remove the final row for a tariff, leaving no row-level flag to replay.
            // Include every configured profile so delete/clear can assign an authoritative zero.
            for (com.overdrive.app.charging.TariffProfile profile
                    : configuredTariffs) {
                if (profile != null && profile.getId() != null
                        && !profile.getId().isEmpty()) {
                    tariffIds.add(profile.getId());
                }
            }
            try (PreparedStatement pending = connection.prepareStatement(
                    "SELECT DISTINCT tariff_id FROM " + TABLE_CHARGING
                            + " WHERE end_time IS NOT NULL"
                            + " AND post_commit_tariff_applied = 0"
                            + " AND tariff_id IS NOT NULL AND tariff_id <> ''"
                            + " ORDER BY tariff_id ASC;");
                 ResultSet rs = pending.executeQuery()) {
                while (rs.next()) tariffIds.add(rs.getString(1));
            }
            for (String tariffId : tariffIds) {
                int useCount = 0;
                long lastUsedAt = 0L;
                try (PreparedStatement aggregate = connection.prepareStatement(
                        "SELECT COUNT(*), MAX(end_time) FROM " + TABLE_CHARGING
                                + " WHERE end_time IS NOT NULL AND tariff_id = ?;")) {
                    aggregate.setString(1, tariffId);
                    try (ResultSet rs = aggregate.executeQuery()) {
                        if (rs.next()) {
                            useCount = rs.getInt(1);
                            lastUsedAt = rs.getLong(2);
                            if (rs.wasNull()) lastUsedAt = 0L;
                        }
                    }
                }
                if (!tariffManager.reconcileUsage(
                        tariffId, lastUsedAt, useCount)) {
                    continue;
                }
                try (PreparedStatement applied = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING
                                + " SET post_commit_tariff_applied = 1"
                                + " WHERE end_time IS NOT NULL AND tariff_id = ?"
                                + " AND post_commit_tariff_applied = 0;")) {
                    applied.setString(1, tariffId);
                    applied.executeUpdate();
                }
            }
        } catch (Exception e) {
            logger.warn("Charging tariff metadata replay deferred: " + e.getMessage());
            if (isSqlFailure(e)) noteWriteFailed();
        }

        com.overdrive.app.abrp.SohEstimator estimator = getSohEstimator();
        if (estimator == null) return;
        try {
            java.util.ArrayList<PendingSohCalibration> pendingRows =
                    new java.util.ArrayList<>();
            try (PreparedStatement pending = connection.prepareStatement(
                    "SELECT start_time, start_soc, end_soc, hv_temp_avg, is_dc,"
                            + " soh_calibration_energy_kwh, soh_calibration_cell_v,"
                            + " soh_calibration_nominal_identity,"
                            + " soh_calibration_estimator_generation,"
                            + " soh_calibration_reset_model_epoch,"
                            + " soh_calibration_prior_at_ms"
                            + " FROM " + TABLE_CHARGING
                            + " WHERE end_time IS NOT NULL"
                            + " AND post_commit_soh_applied = 0"
                            // Newest first: one accepted anchor supersedes every older pending row.
                            + " ORDER BY start_time DESC;");
                 ResultSet rs = pending.executeQuery()) {
                while (rs.next()) {
                    PendingSohCalibration row = new PendingSohCalibration();
                    row.sessionStart = rs.getLong("start_time");
                    double startSoc = rs.getDouble("start_soc");
                    boolean startSocMissing = rs.wasNull();
                    double endSoc = rs.getDouble("end_soc");
                    boolean endSocMissing = rs.wasNull();
                    row.socDelta = endSoc - startSoc;
                    row.packTempC = rs.getDouble("hv_temp_avg");
                    boolean packTempMissing = rs.wasNull();
                    int isDc = rs.getInt("is_dc");
                    boolean isDcMissing = rs.wasNull();
                    row.acCharge = isDc == 0;
                    row.energyKwh = rs.getDouble("soh_calibration_energy_kwh");
                    boolean energyMissing = rs.wasNull();
                    row.highCellV = rs.getDouble("soh_calibration_cell_v");
                    boolean highCellMissing = rs.wasNull();
                    if (highCellMissing) row.highCellV = Double.NaN;
                    row.nominalIdentity =
                            rs.getString("soh_calibration_nominal_identity");
                    row.estimatorGeneration =
                            rs.getLong("soh_calibration_estimator_generation");
                    boolean estimatorGenerationMissing = rs.wasNull();
                    row.resetModelEpoch =
                            rs.getLong("soh_calibration_reset_model_epoch");
                    row.hasResetModelEpoch = !rs.wasNull();
                    row.priorCalibrationAtMs =
                            rs.getLong("soh_calibration_prior_at_ms");
                    boolean priorMissing = rs.wasNull();
                    row.hasFrame = row.nominalIdentity != null
                            && !row.nominalIdentity.isEmpty()
                            && !estimatorGenerationMissing
                            && row.hasResetModelEpoch && !priorMissing;
                    if (startSocMissing || endSocMissing || packTempMissing
                            || isDcMissing || energyMissing) {
                        row.persistedPayloadError = "required numeric payload is NULL";
                    } else if (!isFinite(startSoc) || !isFinite(endSoc)
                            || !isFinite(row.packTempC) || !isFinite(row.energyKwh)
                            || !highCellMissing && !isFinite(row.highCellV)) {
                        row.persistedPayloadError = "numeric payload is non-finite";
                    } else if (startSoc < 0 || startSoc > 100
                            || endSoc < 0 || endSoc > 100
                            || row.socDelta <= 0 || row.energyKwh <= 0
                            || row.packTempC <= -999) {
                        row.persistedPayloadError = "numeric payload is outside its domain";
                    }
                    row.persistedPayloadValid = row.persistedPayloadError == null;
                    pendingRows.add(row);
                }
            }
            for (PendingSohCalibration row : pendingRows) {
                try {
                    if (!row.persistedPayloadValid) {
                        markSohCalibrationResolved(row.sessionStart, true);
                        logger.warn("Rejected malformed charging SOH calibration "
                                + row.sessionStart + ": " + row.persistedPayloadError);
                        continue;
                    }
                    SohCalibrationFrame current =
                            captureSohCalibrationFrame(estimator);
                    if (!row.hasResetModelEpoch || row.resetModelEpoch <= 0) {
                        markSohCalibrationResolved(row.sessionStart, true);
                        logger.warn("Rejected legacy charging SOH calibration "
                                + row.sessionStart
                                + ": durable reset/model epoch is absent or invalid");
                        continue;
                    }
                    if (!row.hasFrame) {
                        markSohCalibrationResolved(row.sessionStart, true);
                        logger.warn("Rejected malformed charging SOH calibration "
                                + row.sessionStart
                                + ": durable calibration frame is absent");
                        continue;
                    }
                    if (current == null) {
                        logger.warn("Charging SOH metadata replay remains pending for "
                                + row.sessionStart
                                + ": current calibration frame is temporarily unavailable");
                        break;
                    }
                    if (!row.nominalIdentity.equals(current.nominalIdentity)) {
                        markSohCalibrationResolved(row.sessionStart, true);
                        logger.warn("Rejected stale charging SOH calibration "
                                + row.sessionStart
                                + ": nominal/model identity is absent or changed");
                        continue;
                    }
                    if (row.resetModelEpoch != current.resetModelEpoch) {
                        markSohCalibrationResolved(row.sessionStart, true);
                        logger.warn("Rejected stale charging SOH calibration "
                                + row.sessionStart
                                + ": durable reset/model epoch changed");
                        continue;
                    }

                    long currentCalibrationAt = current.priorCalibrationAtMs;
                    if (currentCalibrationAt > row.sessionStart) {
                        // A newer deterministic anchor already supersedes this row.
                        markSohCalibrationResolved(row.sessionStart, false);
                        continue;
                    }
                    if (currentCalibrationAt != row.sessionStart
                            && currentCalibrationAt != row.priorCalibrationAtMs) {
                        markSohCalibrationResolved(row.sessionStart, true);
                        logger.warn("Rejected stale charging SOH calibration "
                                + row.sessionStart
                                + ": calibration lineage changed");
                        continue;
                    }

                    final com.overdrive.app.abrp.SohEstimator.CalibrationReplayOutcome[]
                            replayOutcome = {
                                com.overdrive.app.abrp.SohEstimator
                                    .CalibrationReplayOutcome.RETRY_LATER
                            };
                    boolean generationAccepted =
                            estimator.runWithEstimatorGenerationGuard(
                                    current.estimatorGeneration,
                                    () -> replayOutcome[0] =
                                            estimator.applyCalibrationReplayWithOutcome(
                                                    row.energyKwh, row.socDelta,
                                                    row.packTempC, row.acCharge,
                                                    row.highCellV,
                                                    row.sessionStart));
                    if (!generationAccepted
                            || replayOutcome[0]
                                == com.overdrive.app.abrp.SohEstimator
                                    .CalibrationReplayOutcome.RETRY_LATER) {
                        logger.warn("Charging SOH metadata replay remains pending for "
                                + row.sessionStart + ": calibration was not durably accepted");
                        break;
                    }
                    boolean rejected =
                            replayOutcome[0]
                                == com.overdrive.app.abrp.SohEstimator
                                    .CalibrationReplayOutcome.PERMANENTLY_REJECTED;
                    markSohCalibrationResolved(row.sessionStart, rejected);
                    if (rejected) {
                        logger.warn("Rejected invalid charging SOH calibration "
                                + row.sessionStart);
                    }
                } catch (Exception rowFailure) {
                    logger.warn("Charging SOH metadata replay deferred for "
                            + row.sessionStart + ": " + rowFailure.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            logger.warn("Charging SOH metadata scan deferred: " + e.getMessage());
            if (isSqlFailure(e)) noteWriteFailed();
        }
    }

    private void markSohCalibrationResolved(long sessionStart, boolean rejected)
            throws Exception {
        try (PreparedStatement applied = connection.prepareStatement(
                "UPDATE " + TABLE_CHARGING
                        + " SET post_commit_soh_applied = 1,"
                        + " soh_calibration_rejected = ?"
                        + " WHERE start_time = ?"
                        + " AND post_commit_soh_applied = 0;")) {
            applied.setInt(1, rejected ? 1 : 0);
            applied.setLong(2, sessionStart);
            if (applied.executeUpdate() != 1) {
                throw new java.sql.SQLException(
                        "SOH metadata row disappeared during replay");
            }
        }
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

    @FunctionalInterface
    private interface SqlTransactionWork {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface SqlTransactionCommit {
        void commit(Connection connection) throws Exception;
    }

    /**
     * Execute a short write unit atomically on the shared H2 connection. Callers are already under
     * this instance's monitor, the same ownership used by the existing tariff-reprice transaction.
     */
    private void runInTransaction(SqlTransactionWork work) throws Exception {
        runInTransaction(work, Connection::commit);
    }

    private void runInTransaction(
            SqlTransactionWork work, SqlTransactionCommit commit) throws Exception {
        Connection c = connection;
        if (c == null || c.isClosed()) throw new java.sql.SQLException("charging database unavailable");
        boolean priorAutoCommit = c.getAutoCommit();
        if (!priorAutoCommit) {
            discardTransactionConnection(c);
            throw new java.sql.SQLException(
                    "shared charging connection entered a write with auto-commit disabled");
        }
        c.setAutoCommit(false);
        Exception primaryFailure = null;
        try {
            work.run();
            commit.commit(c);
        } catch (Exception e) {
            primaryFailure = e;
            try {
                c.rollback();
            } catch (Exception rollbackFailure) {
                e.addSuppressed(rollbackFailure);
                discardTransactionConnection(c);
            }
            throw e;
        } finally {
            try {
                if (!c.isClosed()) c.setAutoCommit(true);
            } catch (Exception restoreFailure) {
                discardTransactionConnection(c);
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(restoreFailure);
                } else {
                    throw restoreFailure;
                }
            }
        }
    }

    /** A connection with unknown transaction state must never be reused for an apparent autocommit. */
    private void discardTransactionConnection(Connection c) {
        if (connection == c) connection = null;
        try { c.close(); } catch (Exception ignored) {}
    }

    private Boolean areTablesDurablyEmpty(String... tableNames) {
        Connection c = connection;
        if (c == null) return null;
        try (Statement statement = c.createStatement()) {
            for (String table : tableNames) {
                try (ResultSet rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + table + ";")) {
                    if (!rs.next() || rs.getLong(1) != 0L) return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        } catch (Exception e) {
            logger.debug("Could not reconcile cleared tables: " + e.getMessage());
            return null;
        }
    }

    private boolean reconcileClearedTables(Exception failure, String... tableNames) {
        Boolean durable = areTablesDurablyEmpty(tableNames);
        if (durable != null) return durable;
        if (isSqlFailure(failure)) noteWriteFailed();
        try { reconnect(); } catch (Exception ignored) {}
        return Boolean.TRUE.equals(areTablesDurablyEmpty(tableNames));
    }

    private Boolean isChargingSessionDurablyAbsent(long id) {
        Connection c = connection;
        if (c == null) return null;
        try (PreparedStatement p = c.prepareStatement(
                "SELECT 1 FROM " + TABLE_CHARGING + " WHERE id = ?;")) {
            p.setLong(1, id);
            try (ResultSet rs = p.executeQuery()) {
                return !rs.next();
            }
        } catch (Exception e) {
            logger.debug("Could not reconcile charging-session delete " + id
                    + ": " + e.getMessage());
            return null;
        }
    }

    private boolean reconcileDeletedChargingSession(long id, Exception failure) {
        Boolean absent = isChargingSessionDurablyAbsent(id);
        if (absent != null) return absent;
        if (isSqlFailure(failure)) noteWriteFailed();
        try { reconnect(); } catch (Exception ignored) {}
        return Boolean.TRUE.equals(isChargingSessionDurablyAbsent(id));
    }

    /**
     * Upsert one completed session into the permanent {@code charging_daily}
     * rollup so lifetime / monthly-cost trends survive the soc_history prune.
     * H2 MERGE accumulates per-day counters.
     */
    private void foldSessionIntoDaily(long endTime, double energyKwh, double cost,
                                       int isDc, double peakKw, int rangeGained) throws Exception {
        foldSessionIntoDaily(endTime, energyKwh, cost, isDc, peakKw, rangeGained, false);
    }

    /**
     * @param incomplete true when this session's energy figure is a floor rather than a measurement.
     *                   Counted separately so the day's total does not silently assert a precision its
     *                   constituent rows do not have.
     */
    private void foldSessionIntoDaily(long endTime, double energyKwh, double cost,
                                      int isDc, double peakKw, int rangeGained,
                                      boolean incomplete) throws Exception {
        if (!isInitialized || connection == null) {
            throw new java.sql.SQLException("charging database unavailable during daily fold");
        }
        long day = (endTime / 86_400_000L) * 86_400_000L;
        double soh = -999;
        try {
            com.overdrive.app.abrp.SohEstimator est = getSohEstimator();
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                    est != null ? est.getCapacitySohSnapshot() : null;
            if (capacitySoh != null && capacitySoh.hasDisplaySoh()) {
                soh = capacitySoh.getDisplaySoh();
            }
        } catch (Exception ignored) {}

        // Read current row (if any), accumulate, then MERGE the new totals.
        int sessionCount = 0, dcCount = 0, acCount = 0, rangeSum = 0, incompleteCount = 0;
        double energySum = 0, costSum = 0, peakMax = 0, sohDay = soh;
        try (PreparedStatement sel = connection.prepareStatement(
                "SELECT session_count, energy_kwh, cost, dc_count, ac_count, peak_power_kw, " +
                "soh_at_day, range_gained_km, incomplete_count FROM " + TABLE_CHARGING_DAILY
                + " WHERE day_epoch = ?;")) {
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
                    incompleteCount = rs.getInt(9);
                    if (soh <= 0 && prevSoh > 0) sohDay = prevSoh;
                }
            }
        }
        sessionCount += 1;
        if (energyKwh > 0) energySum += energyKwh;
        if (cost > 0) costSum += cost;
        if (isDc == 1) dcCount += 1; else if (isDc == 0) acCount += 1;
        if (peakKw > peakMax) peakMax = peakKw;
        if (rangeGained > 0) rangeSum += rangeGained;
        if (incomplete) incompleteCount += 1;

        try (PreparedStatement merge = connection.prepareStatement(
                "MERGE INTO " + TABLE_CHARGING_DAILY +
                " (day_epoch, session_count, energy_kwh, cost, dc_count, ac_count, peak_power_kw,"
                + " soh_at_day, range_gained_km, incomplete_count)"
                + " KEY(day_epoch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);")) {
            merge.setLong(1, day);
            merge.setInt(2, sessionCount);
            merge.setDouble(3, energySum);
            merge.setDouble(4, costSum);
            merge.setInt(5, dcCount);
            merge.setInt(6, acCount);
            merge.setDouble(7, peakMax);
            merge.setDouble(8, sohDay);
            merge.setInt(9, rangeSum);
            merge.setInt(10, incompleteCount);
            merge.executeUpdate();
        }
    }

    /**
     * Append a fine-grained in-session sample (driven by ChargingSessionManager's
     * fast sampler while ChargingDetector.isCharging()). Best-effort; never throws.
     */
    private boolean advancePendingCloseForAdmittedTaper(
            long sessionStartTime, long sampleAtMs, double soc,
            double temp, double tempHigh, double tempLow) {
        if (!isAdmittedTaperTail()
                || pendingCloseSessionStart != sessionStartTime
                || chargingStartTime != sessionStartTime
                || !wasCharging) {
            return true;
        }
        long advancedAt = Math.max(
                pendingCloseAtMs,
                strictlyAfterChargingStart(sessionStartTime, sampleAtMs));
        pendingCloseAtMs = advancedAt;
        if (!Double.isNaN(soc) && soc >= 0 && soc <= 100) pendingCloseSoc = soc;
        if (tempHigh > -999) pendingCloseTempHigh = tempHigh;
        if (tempLow > -999) pendingCloseTempLow = tempLow;
        if (temp > -999) pendingCloseTempAvg = temp;
        // The sample is not acknowledged until its advanced accounting boundary is restart-safe.
        return persistChargingLifecycleJournal();
    }

    private boolean isAdmittedTaperTail() {
        return physicalChargingStateKnown
                && !physicalChargingNow
                && chargingLiveEnrichmentAllowed;
    }

    public synchronized boolean recordChargingSample(long sessionStartTime, long t, double powerKw,
                                      double soc, double temp, double tempHigh, double tempLow) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return false;
        if (!isValidChargingSamplePower(powerKw)) return false;
        if (!reconcilePendingActiveChargingReplacement()) return false;
        if (!chargingAnalyticsEnabled || optOutClosePending) return false;
        // A clear/reset can atomically replace the active row while a sampler is waiting on this
        // monitor with the old start key. Reject that stale write instead of creating an orphan sample.
        if (wasCharging && sessionStartTime != chargingStartTime) return false;
        if (powerKw > 0 && !advancePendingCloseForAdmittedTaper(
                sessionStartTime, t, soc, temp, tempHigh, tempLow)) {
            return false;
        }
        try {
            runInTransaction(() -> {
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "INSERT INTO " + TABLE_CPS
                                + " (session_start_time, t, power_kw, soc, temp,"
                                + " temp_high, temp_low) VALUES (?, ?, ?, ?, ?, ?, ?);")) {
                    pstmt.setLong(1, sessionStartTime);
                    pstmt.setLong(2, t);
                    pstmt.setDouble(3, powerKw);
                    pstmt.setDouble(4, soc);
                    pstmt.setDouble(5, temp);
                    pstmt.setDouble(6, tempHigh);
                    pstmt.setDouble(7, tempLow);
                    if (pstmt.executeUpdate() != 1) {
                        throw new java.sql.SQLException(
                                "charging sample INSERT did not create one row");
                    }
                }
                if (powerKw == MISSING_RATE_BOUNDARY_POWER_KW) {
                    try (PreparedStatement mark = connection.prepareStatement(
                            "UPDATE " + TABLE_CHARGING
                                    + " SET integration_truncated = 1"
                                    + " WHERE start_time = ? AND end_time IS NULL;")) {
                        mark.setLong(1, sessionStartTime);
                        if (mark.executeUpdate() != 1) {
                            throw new java.sql.SQLException(
                                    "missing-rate marker found no matching open session");
                        }
                    }
                }
            });
            noteDurableChargingSample(sessionStartTime, powerKw);
            noteWriteOk();
            return true;
        } catch (Exception e) {
            logger.debug("recordChargingSample failed: " + e.getMessage());
            if (isChargingSampleDurable(
                    sessionStartTime, t, powerKw, soc, temp, tempHigh, tempLow)) {
                noteDurableChargingSample(sessionStartTime, powerKw);
                noteWriteOk();
                return true;
            }
            if (isSqlFailure(e)) noteWriteFailed();
            try { reconnect(); } catch (Exception ignored) {}
            if (isChargingSampleDurable(
                    sessionStartTime, t, powerKw, soc, temp, tempHigh, tempLow)) {
                noteDurableChargingSample(sessionStartTime, powerKw);
                noteWriteOk();
                return true;
            }
            return false;
        }
    }

    private static boolean isValidChargingSamplePower(double powerKw) {
        if (!Double.isFinite(powerKw) || powerKw > 500.0) return false;
        return powerKw >= 0.0
                || powerKw == STOP_BOUNDARY_POWER_KW
                || powerKw == MISSING_RATE_BOUNDARY_POWER_KW
                || powerKw == AUXILIARY_SAMPLE_POWER_KW;
    }

    private static boolean isValidMeasuredChargingPower(double powerKw) {
        return Double.isFinite(powerKw) && powerKw > 0.0 && powerKw <= 500.0;
    }

    /**
     * Keep the open-session peak synchronized with the durable fine sample series.
     *
     * <p>Resume consolidation rebuilds this field from stored samples. Updating it after each
     * confirmed write lets frequent outbound publications retain a proven AC/DC verdict without
     * running a database aggregate on every MQTT/telemetry cycle.
     */
    private void noteDurableChargingSample(long sessionStartTime, double powerKw) {
        if (powerKw > 0.0
                && wasCharging
                && sessionStartTime == chargingStartTime
                && powerKw > chargingPeakPower) {
            chargingPeakPower = powerKw;
            currentChargingTypeVerdict();
        }
    }

    /** Reconcile an INSERT that may have committed before JDBC threw while returning its result. */
    private boolean isChargingSampleDurable(
            long sessionStartTime, long t, double powerKw,
            double soc, double temp, double tempHigh, double tempLow) {
        Connection c = connection;
        if (c == null || sessionStartTime <= 0L || t <= 0L) return false;
        try (PreparedStatement p = c.prepareStatement(
                "SELECT power_kw, soc, temp, temp_high, temp_low FROM " + TABLE_CPS
                        + " WHERE session_start_time = ? AND t = ? ORDER BY id ASC;")) {
            p.setLong(1, sessionStartTime);
            p.setLong(2, t);
            try (ResultSet rs = p.executeQuery()) {
                boolean sampleDurable = false;
                while (rs.next()) {
                    if (realStorageEquivalent(rs.getDouble(1), powerKw)
                            && realStorageEquivalent(rs.getDouble(2), soc)
                            && realStorageEquivalent(rs.getDouble(3), temp)
                            && realStorageEquivalent(rs.getDouble(4), tempHigh)
                            && realStorageEquivalent(rs.getDouble(5), tempLow)) {
                        sampleDurable = true;
                        break;
                    }
                }
                if (!sampleDurable) return false;
            }
            if (powerKw != MISSING_RATE_BOUNDARY_POWER_KW) return true;
            try (PreparedStatement marker = c.prepareStatement(
                    "SELECT integration_truncated FROM " + TABLE_CHARGING
                            + " WHERE start_time = ? AND end_time IS NULL;")) {
                marker.setLong(1, sessionStartTime);
                try (ResultSet rs = marker.executeQuery()) {
                    return rs.next() && rs.getInt(1) == 1;
                }
            }
        } catch (Exception reconcileFailure) {
            logger.debug("Could not reconcile charging sample "
                    + sessionStartTime + "/" + t + ": " + reconcileFailure.getMessage());
            return false;
        }
    }

    /** Start time of the currently-open charging session, or -1 if none. */
    public synchronized long getOpenChargingSessionStart() {
        if (!reconcilePendingActiveChargingReplacement()) return -1L;
        return wasCharging ? chargingStartTime : -1;
    }

    /**
     * Recomputes {@code peak_power_kw}, {@code avg_power_kw}, and a rough
     * {@code is_dc} heuristic for the CURRENTLY OPEN session from its
     * recorded {@code charging_power_samples} (the same {@code AVG}/{@code
     * MAX} queries {@link #resolvePeakKw}/{@link #resolveAveragePowerKw}
     * already trust), and writes them back. Touches only the open row
     * ({@code start_time = sessionStartTime AND end_time IS NULL}) — never a
     * closed/historical session, per CHARGING-POWER-INVARIANTS.md I5 ("cost/
     * energy rows are immutable snapshots").
     *
     * <p>Added for {@link com.overdrive.app.byd.CarSvcTelemetry}'s car_service
     * (dumpsys) charging-power fallback on platforms (e.g. DiLink 5 /
     * Sealion 7) where this class's own coarse in-session tick — whose power
     * evidence comes from the normal C1-C4 HAL cascade — has nothing to
     * average. {@code is_dc} here is a rough peak-power-only heuristic
     * (&gt;= 11 kW), NOT the {@code gun_state}-derived verdict the rest of
     * this class uses elsewhere; property {@code 0x21403c00} may be worth
     * investigating as a real gun-state signal for car_service in future,
     * but was not verified this round.
     */
    public synchronized boolean updateOpenSessionPeakAvgPower(long sessionStartTime) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return false;
        if (!wasCharging || sessionStartTime != chargingStartTime) return false;
        double avg = averageSamplePowerKw(sessionStartTime);
        double peak = peakSampleKw(sessionStartTime);
        if (avg <= 0 && peak <= 0) return false;
        double finalAvg = avg;
        double finalPeak = peak;
        Integer isDc = peak > 0 ? (peak >= LIVE_PEAK_ONLY_DC_THRESHOLD_KW ? 1 : 0) : null;
        try {
            runInTransaction(() -> {
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING
                                + " SET peak_power_kw = ?, avg_power_kw = ?"
                                + (isDc != null ? ", is_dc = ?" : "")
                                + " WHERE start_time = ? AND end_time IS NULL;")) {
                    int idx = 1;
                    pstmt.setDouble(idx++, finalPeak > 0 ? finalPeak : 0);
                    pstmt.setDouble(idx++, finalAvg > 0 ? finalAvg : -1);
                    if (isDc != null) pstmt.setInt(idx++, isDc);
                    pstmt.setLong(idx, sessionStartTime);
                    if (pstmt.executeUpdate() != 1) {
                        throw new java.sql.SQLException(
                                "car_service peak/avg update found no matching open session");
                    }
                }
            });
            return true;
        } catch (Exception e) {
            logger.debug("updateOpenSessionPeakAvgPower failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Corrected session-energy recompute for the CURRENTLY OPEN session:
     * {@code energyKwh = avgPowerKw * (maxT - minT) / 3_600_000.0} over the
     * session's recorded positive, plausible ({@code 0 < power_kw <= 500})
     * samples. Writes directly to {@code energy_added_kwh}, tagging {@code
     * energy_source = SessionEnergyResolver.SRC_CARSVC} so the provenance is
     * visible in the row.
     *
     * <p>Deliberately bypasses the official {@link SessionEnergyResolver}/
     * {@code integrateSessionEnergyKwh} ("integrated_rate") pipeline for the
     * open session — field testing found that pipeline producing energy
     * figures roughly 8x too small on a car_service-only session (no C1-C4
     * HAL evidence for its other cross-checks), cross-validated independently
     * against the session's own observed SOC delta. This bypass only ever
     * touches the currently-open row; closed/historical sessions, and the
     * official calculation itself, are untouched — see
     * CHARGING-POWER-INVARIANTS.md I5.
     */
    public synchronized boolean recomputeOpenSessionEnergyKwh(long sessionStartTime) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return false;
        if (!wasCharging || sessionStartTime != chargingStartTime) return false;
        double avgPowerKw;
        long minT;
        long maxT;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT AVG(power_kw), MIN(t), MAX(t) FROM " + TABLE_CPS
                        + " WHERE session_start_time = ?"
                        + " AND power_kw > 0 AND power_kw <= 500;")) {
            p.setLong(1, sessionStartTime);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return false;
                avgPowerKw = rs.getDouble(1);
                if (rs.wasNull() || avgPowerKw <= 0) return false;
                minT = rs.getLong(2);
                maxT = rs.getLong(3);
            }
        } catch (Exception e) {
            logger.debug("recomputeOpenSessionEnergyKwh read failed: " + e.getMessage());
            return false;
        }
        if (maxT <= minT) return false;
        double energyKwh = avgPowerKw * (maxT - minT) / 3_600_000.0;
        if (!(energyKwh > 0) || energyKwh > 500.0) return false;
        try {
            runInTransaction(() -> {
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING
                                + " SET energy_added_kwh = ?, energy_source = ?, energy_incomplete = 0"
                                + " WHERE start_time = ? AND end_time IS NULL;")) {
                    pstmt.setDouble(1, energyKwh);
                    pstmt.setString(2, com.overdrive.app.charging.SessionEnergyResolver.SRC_CARSVC);
                    pstmt.setLong(3, sessionStartTime);
                    if (pstmt.executeUpdate() != 1) {
                        throw new java.sql.SQLException(
                                "car_service energy recompute found no matching open session");
                    }
                }
            });
            return true;
        } catch (Exception e) {
            logger.debug("recomputeOpenSessionEnergyKwh write failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Raw {@code energy_added_kwh} column for the CURRENTLY OPEN session —
     * bypasses {@link SessionEnergyResolver} arbitration entirely, for
     * callers (like {@link com.overdrive.app.byd.CarSvcTelemetry}) that
     * specifically want whatever was last written by
     * {@link #recomputeOpenSessionEnergyKwh}. Returns -1 if no session is
     * open, the row has no value yet, or the lookup fails.
     */
    public synchronized double getOpenSessionEnergyAddedKwhRaw(long sessionStartTime) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return -1;
        if (!wasCharging || sessionStartTime != chargingStartTime) return -1;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT energy_added_kwh FROM " + TABLE_CHARGING
                        + " WHERE start_time = ? AND end_time IS NULL;")) {
            p.setLong(1, sessionStartTime);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return -1;
                double value = rs.getDouble(1);
                return !rs.wasNull() && value > 0 ? value : -1;
            }
        } catch (Exception e) {
            logger.debug("getOpenSessionEnergyAddedKwhRaw failed: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Peak-guarded AC/DC verdict for the currently-open physical session.
     *
     * <p>{@code chargingPeakPower} is advanced by both coarse ticks and each durable 12-second
     * sample, and is rebuilt from those samples on resume. A proven DC session therefore remains DC
     * during its low-power taper without querying the database on every outbound telemetry cycle.
     * Returns {@link ChargingTypeClassifier#UNKNOWN} when no session is open or the available
     * evidence has not proved either charger type.
     */
    public synchronized int getOpenChargingSessionTypeVerdict() {
        if (!reconcilePendingActiveChargingReplacement()
                || !wasCharging || chargingStartTime <= 0L) {
            return ChargingTypeClassifier.UNKNOWN;
        }
        return currentChargingTypeVerdict();
    }

    /** Durable H2 truth used by manager edge postcondition checks. */
    public synchronized boolean hasOpenChargingSessionRow() throws java.sql.SQLException {
        if (!reconcilePendingActiveChargingReplacement()) {
            throw new java.sql.SQLException(
                    "active charging replacement identity is unresolved");
        }
        if (connection == null) {
            throw new java.sql.SQLException("charging history storage is unavailable");
        }
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT 1 FROM " + TABLE_CHARGING
                        + " WHERE end_time IS NULL LIMIT 1;");
             ResultSet rs = p.executeQuery()) {
            return rs.next();
        }
    }

    /** Exact row frozen for the next close retry, or the current open row when not yet frozen. */
    public synchronized long getChargingCloseTargetStart() {
        if (!reconcilePendingActiveChargingReplacement()) return -1L;
        if (pendingCloseSessionStart > 0L) return pendingCloseSessionStart;
        if (optOutClosePending && chargingStartTime > 0L) return chargingStartTime;
        return wasCharging ? chargingStartTime : -1L;
    }

    /** Resolve a captured close key through clear/reset replacements of the same physical boundary. */
    public synchronized long remapChargingCloseTargetStart(long capturedStart) {
        if (!reconcilePendingActiveChargingReplacement()) return -1L;
        return resolveChargingCloseTargetStart(capturedStart);
    }

    private long resolveChargingCloseTargetStart(long capturedStart) {
        long current = capturedStart;
        java.util.HashSet<Long> visited = new java.util.HashSet<>();
        while (current > 0L && visited.add(current)) {
            Long replacement = chargingCloseTargetRemaps.get(current);
            if (replacement == null || replacement <= 0L) break;
            current = replacement;
        }
        return current;
    }

    private void forgetChargingCloseTargetAliases(long closedStart) {
        if (closedStart <= 0L || chargingCloseTargetRemaps.isEmpty()) return;
        java.util.Iterator<java.util.Map.Entry<Long, Long>> iterator =
                chargingCloseTargetRemaps.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<Long, Long> entry = iterator.next();
            if (entry.getKey() == closedStart
                    || resolveChargingCloseTargetStart(entry.getValue()) == closedStart) {
                iterator.remove();
            }
        }
    }

    /**
     * True only when the requested identity is durably closed or intentionally absent.
     * A different open row does not affect the answer and is never mutated by this check.
     */
    public synchronized boolean isChargingSessionCloseSatisfied(long sessionStart) {
        if (connection == null || sessionStart <= 0L) return false;
        if (!reconcilePendingActiveChargingReplacement()) return false;
        sessionStart = resolveChargingCloseTargetStart(sessionStart);
        try {
            return durableChargingRowState(sessionStart) != 1;
        } catch (Exception e) {
            logger.debug("Could not verify exact charging close " + sessionStart
                    + ": " + e.getMessage());
            return false;
        }
    }

    /** Latched estimated time-to-full (minutes) for the open session, or -1 if none. */
    public int getOpenChargingSessionTimeToFullMin() {
        return wasCharging ? chargingTimeToFullMin : -1;
    }

    /** Snapshot used to replace an active row atomically when charging history is cleared. */
    private static final class ActiveChargingReplacement {
        long previousStartTime;
        long startTime;
        double startSoc;
        int startRange;
        int startOdometer;
        int gun;
        int typeVerdict = ChargingTypeClassifier.UNKNOWN;
        int timeToFull;
        double lat;
        double lng;
        String counterSource;
        com.overdrive.app.charging.ChargeCounterAccumulator.State counterState;
        boolean lifecycleHold;
        boolean pendingClose;
        long closeAtMs;
        double closeSoc = Double.NaN;
        boolean closeCounterCaptured;
        PricingDecision closePricing;
        int closeIsDc = -2;
        boolean closeResumeBlocked;
        double closeTempHigh = -999;
        double closeTempLow = -999;
        double closeTempAvg = -999;
        final java.util.ArrayList<DeferredChargingGeneration> deferredGenerations =
                new java.util.ArrayList<>();
    }

    /**
     * Write-ahead description of a clear/reset boundary. The old lifecycle remains the primary journal
     * image until H2 proves whether the transaction committed; this intent contains the exact image to
     * publish when the replacement/empty-table postcondition is durable.
     */
    private static final class ChargingMaintenanceIntent {
        String operation;
        long previousStartTime;
        ActiveChargingReplacement replacement;
        final java.util.ArrayList<DeferredChargingGeneration> deferredGenerations =
                new java.util.ArrayList<>();
    }

    private enum ChargingMaintenanceOutcome {
        COMMITTED,
        ROLLED_BACK,
        UNKNOWN
    }

    private ChargingMaintenanceIntent snapshotChargingMaintenanceIntent(String operation) {
        ChargingMaintenanceIntent intent = new ChargingMaintenanceIntent();
        intent.operation = operation;
        intent.previousStartTime = wasCharging ? chargingStartTime : 0L;
        intent.replacement = snapshotActiveChargingReplacement();
        if (intent.replacement == null && !deferredPhysicalGenerations.isEmpty()) {
            double boundarySoc = currentSocForContinuation();
            long proposedStart = System.currentTimeMillis();
            for (DeferredChargingGeneration generation : deferredPhysicalGenerations) {
                DeferredChargingGeneration rebased =
                        rebaseDeferredGenerationAtMaintenanceBoundary(
                                generation, proposedStart, boundarySoc);
                intent.deferredGenerations.add(rebased);
                proposedStart = rebased.startMs + 1L;
            }
        }
        return intent;
    }

    private ChargingMaintenanceIntent beginChargingMaintenance(String operation)
            throws Exception {
        ChargingMaintenanceIntent intent = snapshotChargingMaintenanceIntent(operation);
        pendingChargingMaintenanceIntent = intent;
        if (!persistChargingLifecycleJournal()) {
            pendingChargingMaintenanceIntent = null;
            throw new java.io.IOException(
                    "charging maintenance intent was not durable");
        }
        return intent;
    }

    private ActiveChargingReplacement snapshotActiveChargingReplacement() {
        if (!chargingAnalyticsEnabled || !wasCharging || chargingStartTime <= 0) return null;
        ActiveChargingReplacement state = new ActiveChargingReplacement();
        state.previousStartTime = chargingStartTime;
        state.startTime = allocateMonotonicChargingStart(System.currentTimeMillis());
        state.startSoc = lastRecordedSoc >= 0 ? lastRecordedSoc : chargingStartSoc;
        try {
            BatterySocData sd = VehicleDataMonitor.getInstance().getBatterySoc();
            if (sd != null && !Double.isNaN(sd.socPercent)) state.startSoc = sd.socPercent;
        } catch (Throwable ignored) {}
        state.startRange = snapshotRangeKm();
        state.startOdometer = snapshotOdometerKm();
        state.gun = snapshotGunState();
        state.typeVerdict = currentChargingTypeVerdict();
        state.timeToFull = snapshotTimeToFullMin();
        double[] location = snapshotLocation();
        state.lat = location[0];
        state.lng = location[1];
        // A current reading is a valid zero point for the post-clear segment. Do not fall back to a
        // stale cached endpoint: that could re-credit energy delivered before the clear boundary.
        double counterKwh = snapshotChargeCounterKwh();
        state.counterSource = counterOwner;
        com.overdrive.app.charging.ChargeCounterAccumulator replacementCounter =
                new com.overdrive.app.charging.ChargeCounterAccumulator();
        replacementCounter.setFullScaleKwh(chargingCounter.fullScaleKwh());
        if (!Double.isNaN(counterKwh)) {
            replacementCounter.observe(counterKwh, state.startTime);
        }
        state.counterState = replacementCounter.snapshotState();
        state.lifecycleHold = chargingLifecycleHold;
        if (pendingCloseSessionStart == state.previousStartTime) {
            state.pendingClose = true;
            state.closeAtMs = strictlyAfterChargingStart(
                    state.startTime, pendingCloseAtMs);
            state.closeSoc = pendingCloseSoc;
            state.closeCounterCaptured = pendingCloseCounterCaptured;
            state.closePricing = pendingClosePricing;
            state.closeIsDc = pendingCloseIsDc;
            state.closeResumeBlocked = pendingCloseResumeBlocked;
            state.closeTempHigh = pendingCloseTempHigh;
            state.closeTempLow = pendingCloseTempLow;
            state.closeTempAvg = pendingCloseTempAvg;
        }
        for (DeferredChargingGeneration generation : deferredPhysicalGenerations) {
            state.deferredGenerations.add(
                    rebaseDeferredGenerationAtMaintenanceBoundary(generation, state));
        }
        return state;
    }

    private DeferredChargingGeneration rebaseDeferredGenerationAtMaintenanceBoundary(
            DeferredChargingGeneration original, ActiveChargingReplacement replacement) {
        return rebaseDeferredGenerationAtMaintenanceBoundary(
                original, replacement.startTime + 1L, replacement.startSoc);
    }

    private DeferredChargingGeneration rebaseDeferredGenerationAtMaintenanceBoundary(
            DeferredChargingGeneration original, long proposedStart, double boundarySoc) {
        DeferredChargingGeneration rebased = new DeferredChargingGeneration();
        rebased.startMs = allocateMonotonicChargingStart(proposedStart);
        rebased.startSoc = boundarySoc;
        rebased.startRange = original.startRange;
        rebased.startOdometer = original.startOdometer;
        rebased.gun = original.gun;
        rebased.typeVerdict = original.typeVerdict;
        rebased.timeToFull = original.timeToFull;
        rebased.lat = original.lat;
        rebased.lng = original.lng;
        rebased.previousCounterKwh = Double.NaN;
        rebased.counterOwner = original.counterOwner;
        rebased.counterBaselinePendingSinceMs = rebased.startMs;
        if (original.counter.hasBaseline()) {
            double boundaryCounter = original.counter.lastRawKwh();
            rebased.counter.setFullScaleKwh(original.counter.fullScaleKwh());
            rebased.counter.observe(boundaryCounter, rebased.startMs);
            rebased.counterBaselinePending = false;
            rebased.counterLatestKwh = boundaryCounter;
            rebased.counterLatestAtMs = rebased.startMs;
        }
        if (original.isEnded()) {
            rebased.endMs = strictlyAfterChargingStart(
                    rebased.startMs, rebased.startMs);
            rebased.endSoc = boundarySoc;
            rebased.closeIsDc = original.closeIsDc;
            rebased.closePricing = original.closePricing;
            rebased.resumeBlocked = original.resumeBlocked;
        }
        return rebased;
    }

    private void insertActiveChargingReplacement(ActiveChargingReplacement state) throws Exception {
        if (state == null) return;
        com.overdrive.app.charging.ChargeCounterAccumulator.State counter =
                state.counterState != null
                        ? state.counterState
                        : new com.overdrive.app.charging.ChargeCounterAccumulator.State();
        try (PreparedStatement p = connection.prepareStatement(
                "INSERT INTO " + TABLE_CHARGING
                + " (start_time, start_soc, peak_power_kw, avg_power_kw, gun_state,"
                + " start_lat, start_lng, start_range_km, start_odometer_km, time_to_full_min,"
                + " counter_start_kwh, counter_last_kwh, counter_energy_kwh,"
                + " energy_incomplete, counter_source, counter_full_scale_kwh,"
                + " counter_last_at_ms, counter_observation_generation,"
                + " counter_wrap_count, counter_reset_count, counter_ceiling_streak,"
                + " counter_saturated, counter_abandoned_kwh, counter_unattributed_gaps,"
                + " counter_awaiting_gap, counter_gap_reconstructed,"
                + " counter_gap_estimate_kwh, counter_recent_rate_kwh_per_h)"
                + " VALUES (?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);")) {
            p.setLong(1, state.startTime);
            p.setDouble(2, state.startSoc);
            p.setInt(3, state.gun);
            p.setDouble(4, state.lat);
            p.setDouble(5, state.lng);
            p.setInt(6, state.startRange);
            p.setInt(7, state.startOdometer);
            p.setInt(8, state.timeToFull);
            if (!Double.isNaN(counter.baseline)) {
                p.setDouble(9, counter.baseline);
                p.setDouble(10, counter.last);
                p.setDouble(11, counter.accumulated);
            } else {
                p.setNull(9, java.sql.Types.DOUBLE);
                p.setNull(10, java.sql.Types.DOUBLE);
                p.setNull(11, java.sql.Types.DOUBLE);
            }
            p.setInt(12, counterStateIncomplete(counter) ? 1 : 0);
            if (state.counterSource != null) p.setString(13, state.counterSource);
            else p.setNull(13, java.sql.Types.VARCHAR);
            if (!Double.isNaN(counter.fullScaleKwh) && counter.fullScaleKwh > 1.0) {
                p.setDouble(14, counter.fullScaleKwh);
            } else {
                p.setNull(14, java.sql.Types.DOUBLE);
            }
            bindCounterState(p, 15, counter);
            if (p.executeUpdate() != 1) {
                throw new java.sql.SQLException("active charging replacement was not inserted");
            }
        }
    }

    private static boolean counterStateIncomplete(
            com.overdrive.app.charging.ChargeCounterAccumulator.State state) {
        return state != null && (state.saturated || state.resets > 0
                || state.unattributedGaps > 0 || state.awaitingGapReconcile);
    }

    /**
     * Reload the exact replacement row after a commit whose result was uncertain. Returning the
     * durable values, rather than the pre-transaction proposal, keeps the live key and DB row aligned.
     */
    private ActiveChargingReplacement readDurableActiveChargingReplacement(
            ActiveChargingReplacement expected) {
        Connection c = connection;
        if (c == null || expected == null || expected.startTime <= 0) return null;
        ActiveChargingReplacement durable = null;
        try (PreparedStatement p = c.prepareStatement(
                "SELECT start_time, start_soc, start_range_km, start_odometer_km, gun_state,"
                        + " time_to_full_min, start_lat, start_lng"
                        + " FROM " + TABLE_CHARGING
                        + " WHERE start_time = ? AND end_time IS NULL;")) {
            p.setLong(1, expected.startTime);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                durable = new ActiveChargingReplacement();
                durable.startTime = rs.getLong("start_time");
                durable.startSoc = rs.getDouble("start_soc");
                durable.startRange = rs.getInt("start_range_km");
                durable.startOdometer = rs.getInt("start_odometer_km");
                durable.gun = rs.getInt("gun_state");
                durable.timeToFull = rs.getInt("time_to_full_min");
                durable.lat = rs.getDouble("start_lat");
                durable.lng = rs.getDouble("start_lng");
            }
            CounterRestoreState counter = readCounterRestoreState(expected.startTime);
            durable.counterState = counter.exactState;
            durable.counterSource = counter.source;
            copyReplacementLifecycleState(expected, durable);
            return durable;
        } catch (Exception e) {
            logger.debug("Could not reconcile active charging replacement "
                    + expected.startTime + ": " + e.getMessage());
            return null;
        }
    }

    private static void copyReplacementLifecycleState(
            ActiveChargingReplacement source, ActiveChargingReplacement target) {
        target.previousStartTime = source.previousStartTime;
        target.lifecycleHold = source.lifecycleHold;
        target.typeVerdict = source.typeVerdict;
        target.pendingClose = source.pendingClose;
        target.closeAtMs = source.closeAtMs;
        target.closeSoc = source.closeSoc;
        target.closeCounterCaptured = source.closeCounterCaptured;
        target.closePricing = source.closePricing;
        target.closeIsDc = source.closeIsDc;
        target.closeResumeBlocked = source.closeResumeBlocked;
        target.closeTempHigh = source.closeTempHigh;
        target.closeTempLow = source.closeTempLow;
        target.closeTempAvg = source.closeTempAvg;
        target.deferredGenerations.addAll(source.deferredGenerations);
    }

    private Boolean isDurablyOpenSession(long sessionStart) {
        Connection c = connection;
        if (sessionStart <= 0) return Boolean.FALSE;
        if (c == null) return null;
        try (PreparedStatement p = c.prepareStatement(
                "SELECT end_time FROM " + TABLE_CHARGING + " WHERE start_time = ?;")) {
            p.setLong(1, sessionStart);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return Boolean.FALSE;
                rs.getLong(1);
                return rs.wasNull();
            }
        } catch (Exception e) {
            logger.debug("Could not read durable open-row state for "
                    + sessionStart + ": " + e.getMessage());
            return null;
        }
    }

    private boolean reconcilePendingChargingMaintenanceIntent(Exception failure) {
        return reconcileChargingMaintenanceOutcome(failure)
                != ChargingMaintenanceOutcome.UNKNOWN;
    }

    private ChargingMaintenanceOutcome reconcileChargingMaintenanceOutcome(Exception failure) {
        ChargingMaintenanceIntent intent = pendingChargingMaintenanceIntent;
        if (intent == null) return ChargingMaintenanceOutcome.ROLLED_BACK;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (intent.replacement != null) {
                ActiveChargingReplacement durable =
                        readDurableActiveChargingReplacement(intent.replacement);
                if (durable != null) {
                    publishCommittedChargingMaintenance(intent, durable);
                    return ChargingMaintenanceOutcome.COMMITTED;
                }
                Boolean oldStillOpen = isDurablyOpenSession(intent.previousStartTime);
                if (Boolean.TRUE.equals(oldStillOpen)) {
                    cancelRolledBackChargingMaintenance();
                    return ChargingMaintenanceOutcome.ROLLED_BACK;
                }
            } else {
                Boolean empty = areTablesDurablyEmpty(
                        chargingMaintenanceTables(intent.operation));
                if (Boolean.TRUE.equals(empty)) {
                    publishCommittedChargingMaintenance(intent, null);
                    return ChargingMaintenanceOutcome.COMMITTED;
                }
                if (Boolean.FALSE.equals(empty)) {
                    cancelRolledBackChargingMaintenance();
                    return ChargingMaintenanceOutcome.ROLLED_BACK;
                }
            }
            if (attempt == 0 && (connection == null
                    || failure != null && isSqlFailure(failure))) {
                if (failure != null && isSqlFailure(failure)) noteWriteFailed();
                try { reconnect(); } catch (Exception ignored) {}
            } else {
                break;
            }
        }
        return ChargingMaintenanceOutcome.UNKNOWN;
    }

    private String[] chargingMaintenanceTables(String operation) {
        if ("resetAll".equals(operation)) {
            return new String[] {
                    TABLE_SOC, TABLE_CPS, TABLE_CHARGING, TABLE_CHARGING_DAILY,
                    TABLE_SOC_DAILY, TABLE_ACC_EVENTS
            };
        }
        return new String[] { TABLE_CPS, TABLE_CHARGING, TABLE_CHARGING_DAILY };
    }

    private void publishCommittedChargingMaintenance(
            ChargingMaintenanceIntent intent, ActiveChargingReplacement durable) {
        pendingChargingMaintenanceIntent = null;
        if (durable != null) {
            publishActiveChargingReplacement(durable);
            return;
        }
        resetLiveChargingState(false, false);
        deferredPhysicalGenerations.clear();
        deferredPhysicalGenerations.addAll(intent.deferredGenerations);
        sessionInputsFenced = !deferredPhysicalGenerations.isEmpty();
        persistChargingLifecycleJournal();
    }

    private void cancelRolledBackChargingMaintenance() {
        pendingChargingMaintenanceIntent = null;
        persistChargingLifecycleJournal();
    }

    private ActiveChargingReplacement reconcileActiveChargingReplacement(
            ActiveChargingReplacement expected, long previousStart,
            Exception transactionFailure) {
        if (expected == null) return null;
        ActiveChargingReplacement durable = readDurableActiveChargingReplacement(expected);
        if (durable != null) {
            pendingActiveReplacement = null;
            pendingActiveReplacementPreviousStart = 0L;
            return durable;
        }
        Boolean oldStillOpen = isDurablyOpenSession(previousStart);
        if (Boolean.TRUE.equals(oldStillOpen)) {
            pendingActiveReplacement = null;
            pendingActiveReplacementPreviousStart = 0L;
            return null; // rollback is durable; the pre-transaction in-memory key is still correct
        }
        if (isSqlFailure(transactionFailure)) {
            noteWriteFailed();
            try { reconnect(); } catch (Exception ignored) {}
            durable = readDurableActiveChargingReplacement(expected);
            if (durable != null) {
                pendingActiveReplacement = null;
                pendingActiveReplacementPreviousStart = 0L;
                return durable;
            }
            oldStillOpen = isDurablyOpenSession(previousStart);
            if (Boolean.TRUE.equals(oldStillOpen)) return null;
        }
        // Neither postcondition could be proved. Retain the exact identities and fence every writer;
        // a later healthy call will publish the replacement or confirm rollback before using a key.
        pendingActiveReplacement = expected;
        pendingActiveReplacementPreviousStart = previousStart;
        return null;
    }

    private boolean reconcilePendingActiveChargingReplacement() {
        if (!reconcilePendingChargingMaintenanceIntent(null)) return false;
        ActiveChargingReplacement expected = pendingActiveReplacement;
        if (expected == null) return true;
        for (int attempt = 0; attempt < 2; attempt++) {
            ActiveChargingReplacement durable =
                    readDurableActiveChargingReplacement(expected);
            if (durable != null) {
                pendingActiveReplacement = null;
                pendingActiveReplacementPreviousStart = 0L;
                publishActiveChargingReplacement(durable);
                noteWriteOk();
                return true;
            }
            Boolean oldStillOpen =
                    isDurablyOpenSession(pendingActiveReplacementPreviousStart);
            if (Boolean.TRUE.equals(oldStillOpen)) {
                pendingActiveReplacement = null;
                pendingActiveReplacementPreviousStart = 0L;
                return true;
            }
            if (attempt == 0 && (oldStillOpen == null || connection == null)) {
                try { reconnect(); } catch (Exception ignored) {}
            } else {
                break;
            }
        }
        return false;
    }

    private void publishActiveChargingReplacement(ActiveChargingReplacement state) {
        pendingActiveReplacement = null;
        pendingActiveReplacementPreviousStart = 0L;
        if (state.pendingClose && state.previousStartTime > 0L
                && state.previousStartTime != state.startTime) {
            chargingCloseTargetRemaps.put(
                    state.previousStartTime, state.startTime);
        }
        wasCharging = true;
        chargingStartTime = state.startTime;
        chargingStartSoc = state.startSoc;
        chargingPeakPower = 0;
        chargingPowerSum = 0;
        chargingPowerCount = 0;
        chargingStartRange = state.startRange;
        chargingStartOdometer = state.startOdometer;
        chargingGunState = state.gun;
        chargingTypeVerdict = state.typeVerdict;
        if (chargingTypeVerdict != ChargingTypeClassifier.AC
                && chargingTypeVerdict != ChargingTypeClassifier.DC) {
            chargingTypeVerdict = ChargingTypeClassifier.classify(
                    chargingGunState, 0.0);
        }
        chargingTimeToFullMin = state.timeToFull;
        chargingStartLat = state.lat;
        chargingStartLng = state.lng;
        chargingCounter.restoreState(state.counterState);
        recoveredActivePowerGapAtMs = 0L;
        counterOwner = state.counterSource;
        if (chargingCounter.hasBaseline()) {
            lastSessionCounterKwh = chargingCounter.lastRawKwh();
            counterBaselinePending = false;
            counterBaselinePendingSinceMs = 0L;
        } else {
            // This is a replacement boundary inside the same physical charge, not a new session
            // waiting for the vehicle to reset. Let the next admitted reading baseline immediately.
            lastSessionCounterKwh = Double.NaN;
            counterBaselinePending = true;
            counterBaselinePendingSinceMs = state.startTime;
        }
        provisionalExternalKwh = Double.NaN;
        provisionalExternalAtMs = 0L;
        provisionalExternalUnitDivisor = 1.0;
        clearCounterBaselineCandidates();
        preSessionCounterLowKwh = Double.NaN;
        preSessionCounterAtMs = 0L;
        preSessionCounterSource = null;
        counterProgressDirty = false;
        clearClaimedContinuationOffer();
        optOutClosePending = false;
        optOutBoundaryMs = 0L;
        optOutBoundarySoc = Double.NaN;
        optOutCounterCaptured = false;
        optOutClosePricing = null;
        optOutCloseIsDc = -2;
        pendingCloseSessionStart = state.pendingClose ? state.startTime : 0L;
        pendingCloseAtMs = state.pendingClose
                ? strictlyAfterChargingStart(state.startTime, state.closeAtMs) : 0L;
        pendingCloseSoc = state.pendingClose ? state.closeSoc : Double.NaN;
        pendingCloseCounterCaptured =
                state.pendingClose && state.closeCounterCaptured;
        pendingClosePricing = state.pendingClose ? state.closePricing : null;
        pendingCloseIsDc = state.pendingClose ? state.closeIsDc : -2;
        pendingCloseResumeBlocked =
                state.pendingClose && state.closeResumeBlocked;
        pendingCloseTempHigh =
                state.pendingClose ? state.closeTempHigh : -999;
        pendingCloseTempLow =
                state.pendingClose ? state.closeTempLow : -999;
        pendingCloseTempAvg =
                state.pendingClose ? state.closeTempAvg : -999;
        chargingLifecycleHold = state.lifecycleHold;
        deferredPhysicalGenerations.clear();
        deferredPhysicalGenerations.addAll(state.deferredGenerations);
        sessionInputsFenced = !deferredPhysicalGenerations.isEmpty();
        persistChargingLifecycleJournal();
    }

    private void resetLiveChargingState(boolean retainLastCounter) {
        resetLiveChargingState(retainLastCounter, true);
    }

    private void resetLiveChargingState(boolean retainLastCounter, boolean persistJournal) {
        long closedStart = chargingStartTime;
        if (retainLastCounter && chargingCounter.hasBaseline()
                && !Double.isNaN(chargingCounter.lastRawKwh())) {
            lastSessionCounterKwh = chargingCounter.lastRawKwh();
        } else if (!retainLastCounter) {
            lastSessionCounterKwh = Double.NaN;
        }
        wasCharging = false;
        chargingStartTime = 0L;
        chargingStartSoc = 0;
        chargingPeakPower = 0;
        chargingPowerSum = 0;
        chargingPowerCount = 0;
        chargingStartRange = -1;
        chargingStartOdometer = -1;
        chargingGunState = -1;
        chargingTypeVerdict = ChargingTypeClassifier.UNKNOWN;
        chargingTimeToFullMin = -1;
        chargingStartLat = 0;
        chargingStartLng = 0;
        chargingCounter.reset();
        counterOwner = null;
        provisionalExternalKwh = Double.NaN;
        provisionalExternalAtMs = 0L;
        provisionalExternalUnitDivisor = 1.0;
        counterBaselinePending = false;
        counterBaselinePendingSinceMs = 0L;
        clearCounterBaselineCandidates();
        counterProgressDirty = false;
        clearClaimedContinuationOffer();
        chargingLifecycleHold = false;
        chargingLiveEnrichmentAllowed = false;
        optOutClosePending = false;
        optOutBoundaryMs = 0L;
        optOutBoundarySoc = Double.NaN;
        optOutCounterCaptured = false;
        optOutClosePricing = null;
        optOutCloseIsDc = -2;
        pendingCloseSessionStart = 0L;
        pendingCloseAtMs = 0L;
        pendingCloseSoc = Double.NaN;
        pendingCloseCounterCaptured = false;
        pendingClosePricing = null;
        pendingCloseIsDc = -2;
        pendingCloseResumeBlocked = false;
        pendingCloseTempHigh = -999;
        pendingCloseTempLow = -999;
        pendingCloseTempAvg = -999;
        recoveredActivePowerGapAtMs = 0L;
        forgetChargingCloseTargetAliases(closedStart);
        if (deferredPhysicalGenerations.isEmpty()) sessionInputsFenced = false;
        // ChargingSessionManager owns classifier/rate generations. Closing A here while buffered B is
        // already collecting evidence must not globally reset B's classifier.
        if (persistJournal) persistChargingLifecycleJournal();
    }

    /** SoC% at the start of the currently-open charging session, or -1 if none. */
    public double getOpenChargingSessionStartSoc() {
        return wasCharging ? chargingStartSoc : -1;
    }

    /** Quality-preserving live energy snapshot for the currently open charging session. */
    public static final class OpenChargingSessionEnergy {
        public final double energyKwh;
        public final String source;
        public final boolean incomplete;
        public final boolean estimated;

        private OpenChargingSessionEnergy(
                double energyKwh, String source,
                boolean incomplete, boolean estimated) {
            this.energyKwh = energyKwh;
            this.source = source;
            this.incomplete = incomplete;
            this.estimated = estimated;
        }

        public boolean isUsable() {
            return Double.isFinite(energyKwh)
                    && energyKwh
                    >= com.overdrive.app.charging.SessionEnergyResolver.MIN_SESSION_KWH;
        }

        private static OpenChargingSessionEnergy unavailable() {
            return new OpenChargingSessionEnergy(
                    -1.0,
                    com.overdrive.app.charging.SessionEnergyResolver.SRC_NONE,
                    false,
                    false);
        }
    }

    private static boolean isSessionEnergyEstimated(
            String source, boolean incomplete) {
        if (incomplete) return true;
        return source == null
                || source.isEmpty()
                || !com.overdrive.app.charging.SessionEnergyResolver.SRC_METERED.equals(source);
    }

    /**
     * Live energy for the open session with its provenance and quality, or an unavailable snapshot.
     *
     * <p>{@code synchronized} because {@link com.overdrive.app.charging.ChargeCounterAccumulator} is
     * documented as not thread-safe and {@code restore()} briefly zeroes its state before
     * re-establishing it. This method is called from HTTP/status and MQTT threads while the SoC
     * thread may be resuming a session, so an unsynchronised read could observe that window and
     * publish 0 for a session that has real energy.
     */
    public synchronized OpenChargingSessionEnergy getOpenChargingSessionEnergy() {
        if (!wasCharging || chargingStartTime <= 0) {
            return OpenChargingSessionEnergy.unavailable();
        }
        // SAME resolution as the session-close path, deliberately. This accessor feeds the live
        // in-progress card and /status, so using a different rule here made the card disagree with
        // the row it turns into the moment the charge ended.
        try {
            double liveSoc = Double.NaN;
            BatterySocData sd = VehicleDataMonitor.getInstance().getBatterySoc();
            // Validate SOC range [0,100] to match chargingRowToJson — an out-of-range BMS read
            // (e.g. 101) must NOT drive the live energy estimate, or /status would emit a value
            // while the detail endpoint emits NULL for the same session.
            if (sd != null && sd.socPercent >= 0 && sd.socPercent <= 100) liveSoc = sd.socPercent;

            double meteredKwh = meteredEnergyKwh();
            double socEstimate = Double.isNaN(liveSoc) ? Double.NaN
                    : socEstimateForOpenSession(liveSoc);
            com.overdrive.app.charging.SessionEnergyResolver.Result r =
                    com.overdrive.app.charging.SessionEnergyResolver.resolve(
                            meteredKwh, chargingCounter.containsReconstructedGap(), chargingCounter.isIncomplete(),
                            integrateSessionEnergyKwh(chargingStartTime), socEstimate,
                            lastIntegrationTruncated,
                            counterScaleSuspect(counterOwner));
            if (r.isUsable()) {
                return new OpenChargingSessionEnergy(
                        r.energyKwh,
                        r.source,
                        r.incomplete,
                        isSessionEnergyEstimated(r.source, r.incomplete));
            }
        } catch (Exception ignored) {}
        return OpenChargingSessionEnergy.unavailable();
    }

    /** Compatibility accessor for callers that only need the numeric value. */
    public synchronized double getOpenChargingSessionEnergyKwh() {
        return getOpenChargingSessionEnergy().energyKwh;
    }


    // ==================== DATA RETRIEVAL ====================

    /**
     * Get SOC history for charting.
     * Uses time-based bucketing for efficient downsampling - larger windows = larger buckets.
     * Returns data in ASC order (oldest first) for time-series chart rendering.
     */
    public synchronized JSONArray getSocHistory(int hoursBack, int maxPoints) {
        if (!isInitialized || connection == null) {
            return new JSONArray();
        }
        try {
            return getSocHistoryStrict(hoursBack, maxPoints);
        } catch (java.sql.SQLException e) {
            logger.error("Failed to get SOC history", e);
            try { reconnect(); } catch (Exception ignored) {}
            return new JSONArray();
        }
    }

    /**
     * Strict API-facing SoC history read. Storage failure throws; a valid
     * query with no rows returns an empty array.
     */
    public synchronized JSONArray getSocHistoryStrict(
            int hoursBack, int maxPoints) throws java.sql.SQLException {
        JSONArray results = new JSONArray();
        final Connection conn = requireChargingHistoryReadConnection();
        
        try {
            long now = System.currentTimeMillis();
            int hours = clampSocHistoryHours(hoursBack);
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
                "  AVG(CASE WHEN charging_power_kw > 0"
                    + " AND charging_power_kw <= 500 THEN charging_power_kw END) as power, " +
                "  AVG(range_km) as range, " +
                "  AVG(CASE WHEN remaining_kwh > 0 THEN remaining_kwh END) as kwh, " +
                "  AVG(CASE WHEN voltage_v > 0 THEN voltage_v END) as volt, " +
                "  AVG(CASE WHEN hv_temp_avg > -999 THEN hv_temp_avg END) as temp, " +
                "  AVG(CASE WHEN soh_percent > 0 THEN soh_percent END) as soh " +
                "FROM " + TABLE_SOC + " " +
                "WHERE timestamp >= ? AND timestamp <= ? " +
                "GROUP BY (timestamp / ?) " +
                "ORDER BY t ASC " +
                "LIMIT ?;";
            
            try (PreparedStatement pstmt = conn.prepareStatement(querySql)) {
                pstmt.setLong(1, startTime);
                // UPPER BOUND = now. Without it, rows written before a BACKWARD wall-clock
                // correction (these head units set the clock from GPS/NTP after boot, and a dead
                // RTC can start them years ahead) carry timestamps LARGER than the current time yet
                // still satisfy `>= startTime`. They come back interleaved with the live point that
                // getFullReport appends using the corrected clock, so the series stops being
                // ascending — and the chart maps x as (t - history[0].t) / (last.t - history[0].t),
                // which then collapses to ~0 or throws points off-canvas, rendering a blank or
                // garbage line that looks exactly like a frozen graph until the stale rows age out.
                // Reuse the SAME `now` that startTime was derived from. Calling the clock twice
                // left a window where a backward NTP step between the two reads could make the
                // upper bound EARLIER than startTime, returning zero rows for that refresh. Sharing
                // one timestamp makes upper >= startTime true by construction.
                pstmt.setLong(2, now);
                pstmt.setLong(3, bucketMs);
                pstmt.setInt(4, rowLimit);

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
            // The query completed, so the connection is demonstrably usable — clear any
            // broken-but-open escalation. Without this, an escalation triggered by a FAILING QUERY
            // could only be cleared by a successful WRITE, and the writer dedups (0.5% SOC / 10-min
            // heartbeat) — so a recovered store could stay flagged dead for up to 10 minutes while
            // reads were already succeeding.
            noteReadOk();
            return results;
        } catch (Exception e) {
            if (isSqlFailure(e)) noteReadFailed();
            throw chargingHistoryReadException(
                    "get SOC history", e);
        }
    }

    static int clampSocHistoryHours(int hoursBack) {
        return Math.max(1, Math.min(hoursBack, 24 * 30));
    }
    
    /**
     * Get charging sessions.
     */
    public synchronized JSONArray getChargingSessions(int daysBack) {
        JSONArray results = new JSONArray();
        
        // Snapshot once — see the connection field's doc.
        final Connection conn = connection;
        if (!isInitialized || conn == null) {
            return results;
        }
        
        try {
            long requestedDays = Math.max(1L, (long) daysBack);
            long startTime = System.currentTimeMillis()
                    - (requestedDays * 24L * 60L * 60L * 1000L);
            
            String sql = "SELECT start_time as startTime, end_time as endTime, start_soc as startSoc, " +
                "end_soc as endSoc, energy_added_kwh as energyAdded, peak_power_kw as peakPower, " +
                "avg_power_kw as avgPower, gun_state as gunState, is_dc as isDc, " +
                "energy_source as energySource, energy_incomplete as energyIncomplete " +
                "FROM " + TABLE_CHARGING + " WHERE start_time >= ? ORDER BY start_time DESC;";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, startTime);
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        row.put("startTime", rs.getLong("startTime"));
                        row.put("endTime", rs.getLong("endTime"));
                        // NaN-safe: put(String,double) throws on NaN, which would abort the whole
                        // response. A session opened before SoC ever resolved can hold NaN here.
                        double startSoc = rs.getDouble("startSoc");
                        row.put("startSoc", rs.wasNull()
                                ? JSONObject.NULL : jsonSoc(startSoc));
                        double endSoc = rs.getDouble("endSoc");
                        row.put("endSoc", rs.wasNull()
                                ? JSONObject.NULL : jsonSoc(endSoc));
                        double energy = rs.getDouble("energyAdded");
                        boolean hasEnergy = !rs.wasNull()
                                && Double.isFinite(energy) && energy > 0;
                        row.put("energyAdded", hasEnergy
                                ? Double.valueOf(energy) : JSONObject.NULL);
                        double peakPower = rs.getDouble("peakPower");
                        row.put("peakPower", rs.wasNull()
                                || !isValidMeasuredChargingPower(peakPower)
                                ? JSONObject.NULL : peakPower);
                        double avgPower = rs.getDouble("avgPower");
                        row.put("avgPower", rs.wasNull()
                                || !isValidMeasuredChargingPower(avgPower)
                                ? JSONObject.NULL : avgPower);
                        int gunState = rs.getInt("gunState");
                        row.put("gunState", rs.wasNull() ? -1 : gunState);
                        int isDc = rs.getInt("isDc");
                        row.put("isDc", rs.wasNull() || (isDc != 0 && isDc != 1)
                                ? JSONObject.NULL : Boolean.valueOf(isDc == 1));
                        String energySource = rs.getString("energySource");
                        boolean energyIncomplete =
                                rs.getInt("energyIncomplete") == 1;
                        row.put("energySource",
                                energySource == null || energySource.isEmpty()
                                        ? JSONObject.NULL : energySource);
                        row.put("energyIncomplete",
                                hasEnergy && energyIncomplete);
                        row.put("energyEstimated",
                                hasEnergy && isSessionEnergyEstimated(
                                        energySource, energyIncomplete));
                        results.put(row);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to get charging sessions", e);
            // Count a genuine JDBC failure so a broken-but-OPEN connection escalates to
            // dead and the reconnect below actually reopens it. Non-SQL throws are not
            // counted — they say nothing about the connection (see isSqlFailure).
            if (isSqlFailure(e)) noteReadFailed();
            reconnect();
        }

        return results;
    }

    // ==================== CHARGING ANALYTICS (v2) ====================

    /** Column list shared by the v2 session readers. */
    private static final String CHARGING_V2_COLS =
        "id, start_time, end_time, start_soc, end_soc, energy_added_kwh, peak_power_kw, avg_power_kw, " +
        "range_gained_km, gun_state, is_dc, electricity_rate, currency, session_cost, time_to_full_min, " +
        "hv_temp_high, hv_temp_low, hv_temp_avg, start_lat, start_lng, place_label, start_odometer_km, " +
        "tariff_id, tariff_label, energy_source, energy_soc_kwh, energy_incomplete, " +
        "counter_energy_kwh";

    /** Non-finite -> JSON null; JSON numeric values must be finite. */
    private static Object jsonNum(double v) {
        return Double.isFinite(v) ? Double.valueOf(v) : JSONObject.NULL;
    }

    private static Object jsonSoc(double v) {
        return Double.isFinite(v) && v >= 0.0 && v <= 100.0
                ? Double.valueOf(v) : JSONObject.NULL;
    }

    private JSONObject chargingRowToJson(ResultSet rs) throws Exception {
        JSONObject o = new JSONObject();
        long start = rs.getLong("start_time");
        long end = rs.getLong("end_time");
        boolean inProgress = rs.wasNull();
        o.put("id", rs.getLong("id"));
        o.put("startTime", start);
        o.put("endTime", end);
        o.put("inProgress", inProgress);
        o.put("chargingNow", inProgress
                && wasCharging && start == chargingStartTime
                && physicalChargingStateKnown && physicalChargingNow);
        double startSoc = rs.getDouble("start_soc");
        o.put("startSoc", rs.wasNull()
                ? JSONObject.NULL : jsonSoc(startSoc));
        double endSoc = rs.getDouble("end_soc");
        o.put("endSoc", rs.wasNull() ? JSONObject.NULL : jsonSoc(endSoc));
        double energy = rs.getDouble("energy_added_kwh");
        boolean hasStoredEnergy =
                !rs.wasNull() && Double.isFinite(energy) && energy > 0;
        o.put("energyAdded", hasStoredEnergy
                ? Double.valueOf(energy) : JSONObject.NULL);
        double peak = rs.getDouble("peak_power_kw");
        o.put("peakPower", rs.wasNull()
                || !isValidMeasuredChargingPower(peak)
                ? JSONObject.NULL : Double.valueOf(peak));
        double avg = rs.getDouble("avg_power_kw");
        o.put("avgPower", rs.wasNull()
                || !isValidMeasuredChargingPower(avg)
                ? JSONObject.NULL : Double.valueOf(avg));
        int range = rs.getInt("range_gained_km");
        o.put("rangeGained", !rs.wasNull() && range >= 0
                ? Integer.valueOf(range) : JSONObject.NULL);
        int gunState = rs.getInt("gun_state");
        o.put("gunState", rs.wasNull() ? -1 : gunState);
        int isDc = rs.getInt("is_dc");
        o.put("isDc", rs.wasNull() || (isDc != 0 && isDc != 1)
                ? JSONObject.NULL : Boolean.valueOf(isDc == 1));
        double rate = rs.getDouble("electricity_rate");
        o.put("electricityRate", !rs.wasNull()
                && Double.isFinite(rate) && rate > 0
                ? Double.valueOf(rate) : JSONObject.NULL);
        double cost = rs.getDouble("session_cost");
        o.put("cost", !rs.wasNull()
                && Double.isFinite(cost) && cost >= 0
                ? Double.valueOf(cost) : JSONObject.NULL);
        String curr = rs.getString("currency");
        o.put("currency", curr != null ? curr : "");
        int ttf = rs.getInt("time_to_full_min");
        o.put("timeToFullMin", !rs.wasNull() && ttf >= 0
                ? Integer.valueOf(ttf) : JSONObject.NULL);
        double tHi = rs.getDouble("hv_temp_high");
        boolean tHiMissing = rs.wasNull();
        double tLo = rs.getDouble("hv_temp_low");
        boolean tLoMissing = rs.wasNull();
        double tAvg = rs.getDouble("hv_temp_avg");
        boolean tAvgMissing = rs.wasNull();
        o.put("tempHigh", !tHiMissing && Double.isFinite(tHi) && tHi > -999
                ? Double.valueOf(tHi) : JSONObject.NULL);
        o.put("tempLow", !tLoMissing && Double.isFinite(tLo) && tLo > -999
                ? Double.valueOf(tLo) : JSONObject.NULL);
        o.put("tempAvg", !tAvgMissing && Double.isFinite(tAvg) && tAvg > -999
                ? Double.valueOf(tAvg) : JSONObject.NULL);
        o.put("durationMinutes", (end > 0 && end > start) ? Math.round((end - start) / 60000.0) : JSONObject.NULL);
        // Location of the charge. lat/lng are 0/0 when no GPS fix; placeLabel is
        // filled async by the geocoder (may be empty on early reads).
        double lat = rs.getDouble("start_lat");
        boolean latMissing = rs.wasNull();
        double lng = rs.getDouble("start_lng");
        boolean lngMissing = rs.wasNull();
        boolean hasLoc = !latMissing && !lngMissing
                && Double.isFinite(lat) && Double.isFinite(lng)
                && !(lat == 0 && lng == 0);
        o.put("lat", hasLoc ? lat : JSONObject.NULL);
        o.put("lng", hasLoc ? lng : JSONObject.NULL);
        String place = rs.getString("place_label");
        o.put("placeLabel", (place != null && !place.isEmpty()) ? place : JSONObject.NULL);
        // Odometer at charge start (km). -1 sentinel → null so the UI shows "--".
        int odo = rs.getInt("start_odometer_km");
        o.put("startOdometerKm", !rs.wasNull() && odo >= 0
                ? Integer.valueOf(odo) : JSONObject.NULL);
        // Which tariff priced this session. Empty (every pre-v6 row, and any
        // charge at an unmapped location) → null, and the UI simply omits the
        // provenance chip. Read defensively: a session row written before the
        // v6 migration ran is still served, just without the chip.
        try {
            String tId = rs.getString("tariff_id");
            String tLabel = rs.getString("tariff_label");
            o.put("tariffId", (tId != null && !tId.isEmpty()) ? tId : JSONObject.NULL);
            o.put("tariffLabel", (tLabel != null && !tLabel.isEmpty()) ? tLabel : JSONObject.NULL);
        } catch (Exception ignored) {
            o.put("tariffId", JSONObject.NULL);
            o.put("tariffLabel", JSONObject.NULL);
        }
        // Energy provenance. Surfaced so the UI can mark a row whose total is known to be missing a
        // segment, and so support can tell a metered figure from a reconstructed one without
        // re-deriving anything. Read defensively: a row written before the migration still serves.
        try {
            String esrc = rs.getString("energy_source");
            o.put("energySource", (esrc != null && !esrc.isEmpty()) ? esrc : JSONObject.NULL);
            double esoc = rs.getDouble("energy_soc_kwh");
            o.put("energySocKwh", rs.wasNull()
                    || !Double.isFinite(esoc) || esoc < 0
                    ? JSONObject.NULL : Double.valueOf(esoc));
            double ectr = rs.getDouble("counter_energy_kwh");
            o.put("energyCounterKwh", rs.wasNull()
                    || !Double.isFinite(ectr) || ectr < 0
                    ? JSONObject.NULL : Double.valueOf(ectr));
            boolean energyIncomplete = rs.getInt("energy_incomplete") == 1;
            o.put("energyIncomplete", energyIncomplete);
            o.put("energyEstimated",
                    hasStoredEnergy
                            && ((esrc == null || esrc.isEmpty())
                            || isSessionEnergyEstimated(
                                    esrc, energyIncomplete)));
        } catch (Exception ignored) {
            o.put("energySource", JSONObject.NULL);
            o.put("energySocKwh", JSONObject.NULL);
            o.put("energyCounterKwh", JSONObject.NULL);
            o.put("energyIncomplete", false);
            o.put("energyEstimated", hasStoredEnergy);
        }

        // ---- Live enrichment for the OPEN (in-progress) session ----
        // The end_soc / energy / range / cost / ttf / temp columns are only
        // written at SESSION END. While a charge is still running they sit at
        // their sentinel defaults (end_soc NULL -> 0.0, the rest -1/-999), which
        // is why an in-progress session reads "20% -> 0%" with blank stats and
        // only avg/peak power filled (those ARE written by the mid-session tick).
        // Fill them from the live monitor + running aggregates so the card and
        // detail view reflect the charge so far. Only the row matching the
        // currently-open session is touched.
        boolean isOpen = inProgress;
        if (isOpen && wasCharging && start == chargingStartTime
                && chargingLiveEnrichmentAllowed) {
            long nowMs = System.currentTimeMillis();
            o.put("durationMinutes", Math.max(0, Math.round((nowMs - start) / 60000.0)));
            // AC/DC verdict for the OPEN session. The is_dc COLUMN is not written by
            // the session INSERT, so it sits at its -1 default until session end and
            // serialises as null — which left the UI's label classifier with no gun
            // evidence, guessing purely from power against its own (higher) DC_KW
            // threshold. A live DC session in the 15..25 kW band was therefore
            // labelled "AC fast" while being PRICED at dcRate (pricing uses this same
            // deriveIsDc below), and the DC/AC tile counted it in the AC bucket.
            // Resolve the same combined coarse + fine peak that the card receives
            // before deriving the verdict. Otherwise a 12-second DC ramp peak can
            // make the card show a DC-plausible number while classification and
            // pricing still use the lower two-minute peak.
            double livePeak = resolvePeakKw(start, chargingPeakPower);
            if (livePeak > chargingPeakPower) chargingPeakPower = livePeak;
            int liveIsDc = currentChargingTypeVerdict();
            o.put("isDc", liveIsDc == 1 ? Boolean.TRUE : liveIsDc == 0 ? Boolean.FALSE : JSONObject.NULL);
            if (livePeak > 0) o.put("peakPower", livePeak);
            double liveAverage = resolveAveragePowerKw(
                    start, chargingPowerSum, chargingPowerCount);
            if (liveAverage > 0) o.put("avgPower", liveAverage);
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
                // Same accessor the /status endpoint uses, which in turn uses the same resolver as
                // the session-close path. Previously this block ran its own integrate-then-
                // SOC x capacity rule, so the in-progress card could show a different figure from
                // the row it turned into the moment the charge ended.
                OpenChargingSessionEnergy liveEnergy =
                        getOpenChargingSessionEnergy();
                if (liveEnergy.isUsable()) {
                    double e = liveEnergy.energyKwh;
                    o.put("energyAdded", e);
                    o.put("energySource", liveEnergy.source);
                    o.put("energyIncomplete", liveEnergy.incomplete);
                    o.put("energyEstimated", liveEnergy.estimated);
                    // Same classifier + tariff resolution as the SESSION END path
                    // (location tariff first, then global DC/base), so this live
                    // card cost matches the value persisted when it closes — and
                    // the live card already names the tariff that will price it.
                    PricingDecision livePd = priceSession(
                            liveIsDc,
                            chargingStartLat, chargingStartLng);
                    if (livePd.rate > 0) {
                        o.put("cost", e * livePd.rate);
                        o.put("electricityRate", livePd.rate);
                        if (!livePd.currency.isEmpty()) o.put("currency", livePd.currency);
                        if (!livePd.tariffId.isEmpty()) {
                            o.put("tariffId", livePd.tariffId);
                            o.put("tariffLabel", livePd.tariffLabel);
                        }
                    }
                    // Range gained derived from energy × efficiency (the car's
                    // elecRangeKm delta is unavailable while parked/charging).
                    int rg = rangeGainedFromEnergy(e);
                    if (rg > 0) o.put("rangeGained", rg);
                }
                if (chargingTimeToFullMin > 0) o.put("timeToFullMin", chargingTimeToFullMin);
                BatteryThermalData th = vm != null ? vm.getBatteryThermal() : null;
                if (th != null && th.hasData()) {
                    if (isFinite(th.averageTempC)) o.put("tempAvg", th.averageTempC);
                    if (isFinite(th.highestTempC)) o.put("tempHigh", th.highestTempC);
                    if (isFinite(th.lowestTempC)) o.put("tempLow", th.lowestTempC);
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
                    if (!cs.isEstimated && isFinite(cs.chargingPowerKW)
                            && cs.chargingPowerKW > 0
                            && cs.chargingPowerKW <= 500) {
                        o.put("livePowerKw", cs.chargingPowerKW);
                    }
                }
            } catch (Exception ignored) {}
        }
        // Read-only evidence metadata. This is intentionally added after live
        // enrichment and never written back to charging_sessions, so historical
        // rows/costs/samples/rollups remain byte-for-byte unchanged.
        try {
            com.overdrive.app.byd.BydDataCollector collector =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
            boolean isPhev = collector != null && collector.isInitialized()
                    && collector.isPhevPublic();
            com.overdrive.app.byd.BydVehicleData snapshot = collector != null
                    ? collector.getData() : null;
            double highCellVoltage = snapshot != null
                    ? snapshot.highCellVoltage : Double.NaN;
            String declaredChemistry =
                    com.overdrive.app.server.ModelsApiHandler.batteryChemistryForSelectedModel();
            com.overdrive.app.battery.ChargingSessionQuality.enrich(
                    o, isPhev, highCellVoltage, declaredChemistry);
        } catch (Throwable ignored) {
            com.overdrive.app.battery.ChargingSessionQuality.enrich(
                    o, false, Double.NaN, "unknown");
        }
        return o;
    }

    /**
     * Session peak (kW), taken as the max of the COARSE running max and the FINE sample series.
     *
     * <p>The two series disagree because they are sampled 10x apart: the 2-minute tick can miss a
     * ramp peak the 12-second sampler caught. That mattered beyond display — the AC/DC verdict is
     * peak-guarded, so a real DC session whose coarse ticks all missed the threshold was recorded as
     * AC and priced at the AC rate. Taking the max of both makes every close path agree with the
     * curve the UI draws above it.
     */
    private double resolvePeakKw(long sessionStartTime, double coarsePeak) {
        double fine = peakSampleKw(sessionStartTime);
        return Math.max(
                isValidMeasuredChargingPower(coarsePeak) ? coarsePeak : 0,
                isValidMeasuredChargingPower(fine) ? fine : 0);
    }

    /**
     * Session average (kW) over the recorded positive charging-power points.
     *
     * <p>The vehicle recorder's charging summary defines average power as the arithmetic mean of its
     * stored power points; duration and charged energy are separate quantities. Using
     * {@code energy / row lifetime} dilutes the result when a row spans a pause, an offline gap, or a
     * resumed session. The fine sample series is preferred because it has a stable cadence. The
     * coarse in-memory mean survives database gaps. With no measured point, active charging time is
     * unknowable, so the average is left unavailable rather than dividing energy by row lifetime.
     */
    private double resolveAveragePowerKw(long sessionStartTime,
                                         double coarsePowerSum,
                                         int coarsePowerCount) {
        double sampled = averageSamplePowerKw(sessionStartTime);
        if (sampled > 0) return sampled;
        if (coarsePowerCount > 0) {
            double coarse = coarsePowerSum / coarsePowerCount;
            if (coarse > 0 && coarse <= 500) return coarse;
        }
        return -1;
    }

    /** Arithmetic mean of measured positive samples, or -1 when none are available. */
    private double averageSamplePowerKw(long sessionStartTime) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return -1;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT AVG(power_kw) FROM " + TABLE_CPS
                        + " WHERE session_start_time = ?"
                        + " AND power_kw > 0 AND power_kw <= 500;")) {
            p.setLong(1, sessionStartTime);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return -1;
                double value = rs.getDouble(1);
                return !rs.wasNull() && isValidMeasuredChargingPower(value)
                        ? value : -1;
            }
        } catch (Exception e) {
            logger.debug("averageSamplePowerKw failed: " + e.getMessage());
            return -1;
        }
    }

    /** Max measured power (kW) across a session's recorded samples, or 0. */
    private double peakSampleKw(long sessionStartTime) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return 0;
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT MAX(power_kw) FROM " + TABLE_CPS
                        + " WHERE session_start_time = ?"
                        + " AND power_kw > 0 AND power_kw <= 500;")) {
            p.setLong(1, sessionStartTime);
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) {
                    double value = rs.getDouble(1);
                    return !rs.wasNull()
                            && isValidMeasuredChargingPower(value) ? value : 0;
                }
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

    /**
     * Set by the most recent {@link #integrateSessionEnergyKwh} when it had to DROP an interval
     * (a gap longer than the 10-minute cap, i.e. a daemon restart mid-charge).
     *
     * <p>Read immediately after the integrate call on the same thread — every caller integrates and
     * resolves in one statement, so there is no interleaving to guard. It exists because the integral
     * alone cannot express "this is a floor, not a total": a truncated figure looks exactly like a
     * complete one, and without this it could be priced as a complete {@code integrated_rate}.
     */
    private volatile boolean lastIntegrationTruncated = false;

    private boolean hasPersistedIntegrationTruncation(long sessionStartTime) {
        try (PreparedStatement p = connection.prepareStatement(
                "SELECT integration_truncated FROM " + TABLE_CHARGING
                + " WHERE start_time = ?;")) {
            p.setLong(1, sessionStartTime);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            // Failure to prove continuity must not promote an integral to a complete measurement.
            logger.warn("Could not read integration continuity for session " + sessionStartTime
                    + ": " + e.getMessage());
            return true;
        }
    }

    private double integrateSessionEnergyKwh(long sessionStartTime) {
        if (!isInitialized || connection == null || sessionStartTime <= 0) return 0;
        lastIntegrationTruncated = hasPersistedIntegrationTruncation(sessionStartTime);
        // Pull ALL rows (NOT just power_kw > 0) ordered by time. A power_kw <= 0
        // row is a CHARGING-STOPPED boundary: either a fast-sampler tick that read
        // ≤0 power, or an explicit merge-boundary sentinel (power_kw = -1) written
        // by tryResumeChargingSession when it consolidates two physically distinct
        // sessions onto one canonical start_time. We must RESET the trapezoid
        // chain at each such boundary so the gap between two separate charges is
        // not bridged into a spurious trapezoid — that would over-count energy by
        // an unobserved idle gap. Resume now writes the same boundary even when it
        // reopens one row, because a short daemon outage can still contain stop/restart.
        try (PreparedStatement pstmt = connection.prepareStatement(
                "SELECT t, power_kw FROM " + TABLE_CPS +
                " WHERE session_start_time = ?"
                + " ORDER BY t ASC,"
                + " CASE WHEN power_kw <= 0 THEN 1 ELSE 0 END ASC, id ASC;")) {
            pstmt.setLong(1, sessionStartTime);
            try (ResultSet rs = pstmt.executeQuery()) {
                double kwh = 0; long prevT = -1; double prevP = 0; int n = 0;
                while (rs.next()) {
                    long t = rs.getLong(1);
                    double p = rs.getDouble(2);
                    if (!Double.isFinite(p) || p > 500.0) {
                        // A corrupt/legacy out-of-domain row is an observation gap, not power.
                        lastIntegrationTruncated = true;
                        prevT = -1;
                        prevP = 0;
                        continue;
                    }
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
                        } else if (dtHours > (10.0 / 60.0)) {
                            // DROPPED an interval. Both samples showed real power, so energy did flow
                            // across this gap — we simply cannot say how much. Record that, because a
                            // silently-truncated integral is indistinguishable from a complete one and
                            // would be priced as `integrated_rate` with no incompleteness flag when no
                            // counter or SOC fallback exists.
                            lastIntegrationTruncated = true;
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
    public synchronized JSONArray getChargingSessionsV2(int daysBack, int limit, int offset) {
        try {
            return getChargingSessionsV2Strict(daysBack, limit, offset);
        } catch (java.sql.SQLException e) {
            logger.error("Failed to get charging sessions v2", e);
            try { reconnect(); } catch (Exception ignored) {}
            return new JSONArray();
        }
    }

    /** Strict API-facing list: storage failure throws; a genuine empty result returns an empty array. */
    public synchronized JSONArray getChargingSessionsV2Strict(
            int daysBack, int limit, int offset) throws java.sql.SQLException {
        long from = System.currentTimeMillis() - (daysBack * 24L * 60 * 60 * 1000L);
        return getChargingSessionsV2RangeStrict(from, Long.MAX_VALUE, limit, offset);
    }

    /**
     * Range variant: sessions with {@code fromMs <= start_time <= toMs}. Used by
     * the date-range picker (charging history is permanent, so this can span
     * well beyond the 90-day quick filters). {@code toMs}=Long.MAX_VALUE = no
     * upper bound. start_time is epoch-ms.
     */
    public synchronized JSONArray getChargingSessionsV2Range(
            long fromMs, long toMs, int limit, int offset) {
        try {
            return getChargingSessionsV2RangeStrict(fromMs, toMs, limit, offset);
        } catch (java.sql.SQLException e) {
            logger.error("Failed to get charging sessions v2 (range)", e);
            try { reconnect(); } catch (Exception ignored) {}
            return new JSONArray();
        }
    }

    /** Strict API-facing range list: storage failure is distinct from a valid empty range. */
    public synchronized JSONArray getChargingSessionsV2RangeStrict(
            long fromMs, long toMs, int limit, int offset) throws java.sql.SQLException {
        JSONArray results = new JSONArray();
        final Connection conn = requireChargingHistoryReadConnection();
        try {
            String sql = "SELECT " + CHARGING_V2_COLS + " FROM " + TABLE_CHARGING +
                " WHERE start_time >= ? AND start_time <= ? ORDER BY start_time DESC LIMIT ? OFFSET ?;";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, fromMs);
                pstmt.setLong(2, toMs);
                pstmt.setInt(3, limit);
                pstmt.setInt(4, offset);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) results.put(chargingRowToJson(rs));
                }
            }
            noteReadOk();
            return results;
        } catch (Exception e) {
            if (isSqlFailure(e)) noteReadFailed();
            throw chargingHistoryReadException("list charging sessions", e);
        }
    }

    /** Single session by its IDENTITY id, or null. */
    public synchronized JSONObject getChargingSessionById(long id) {
        try {
            return getChargingSessionByIdStrict(id);
        } catch (java.sql.SQLException e) {
            logger.error("Failed to get charging session " + id, e);
            try { reconnect(); } catch (Exception ignored) {}
            return null;
        }
    }

    /** Strict API-facing lookup: null means absent; unavailable/failed storage throws. */
    public synchronized JSONObject getChargingSessionByIdStrict(long id)
            throws java.sql.SQLException {
        final Connection conn = requireChargingHistoryReadConnection();
        try {
            String sql = "SELECT " + CHARGING_V2_COLS + " FROM " + TABLE_CHARGING + " WHERE id = ?;";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, id);
                try (ResultSet rs = pstmt.executeQuery()) {
                    JSONObject result = rs.next() ? chargingRowToJson(rs) : null;
                    noteReadOk();
                    return result;
                }
            }
        } catch (Exception e) {
            if (isSqlFailure(e)) noteReadFailed();
            throw chargingHistoryReadException("get charging session " + id, e);
        }
    }

    /** Per-session fine-grained ramp samples (ASC by time) for the given session id. */
    public synchronized JSONArray getChargingSamples(long id) {
        try {
            return getChargingSamplesStrict(id);
        } catch (java.sql.SQLException e) {
            logger.error("Failed to get charging samples for " + id, e);
            try { reconnect(); } catch (Exception ignored) {}
            return new JSONArray();
        }
    }

    /** Strict API-facing sample read: failure throws; absent session or no samples is empty. */
    public synchronized JSONArray getChargingSamplesStrict(long id)
            throws java.sql.SQLException {
        JSONArray results = new JSONArray();
        final Connection conn = requireChargingHistoryReadConnection();
        try {
            // Resolve id -> start_time (the FK used by charging_power_samples).
            long start = -1;
            try (PreparedStatement sel = conn.prepareStatement(
                    "SELECT start_time FROM " + TABLE_CHARGING + " WHERE id = ?;")) {
                sel.setLong(1, id);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) start = rs.getLong(1);
                }
            }
            if (start <= 0) {
                noteReadOk();
                return results;
            }
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT t, power_kw, soc, temp, temp_high, temp_low FROM " + TABLE_CPS +
                    " WHERE session_start_time = ?"
                            + " AND (power_kw >= 0 OR power_kw = ? OR power_kw = ?)"
                            + " ORDER BY t ASC;")) {
                pstmt.setLong(1, start);
                pstmt.setDouble(2, MISSING_RATE_BOUNDARY_POWER_KW);
                pstmt.setDouble(3, AUXILIARY_SAMPLE_POWER_KW);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject o = new JSONObject();
                        o.put("t", rs.getLong("t"));
                        double samplePower = rs.getDouble("power_kw");
                        o.put("power", (!rs.wasNull() && isFinite(samplePower)
                                && samplePower > 0 && samplePower <= 500)
                                ? samplePower : JSONObject.NULL);
                        // NaN-safe. The fast sampler stores whatever SoC it had, and NaN is reachable
                        // (a sentinel SoC read while the power accessor still answers). JSONObject.put
                        // THROWS on a NaN double, which aborted this loop mid-session and served a
                        // silently TRUNCATED curve — the chart just stopped, with no error anywhere.
                        double sampleSoc = rs.getDouble("soc");
                        o.put("soc", (rs.wasNull() || !isFinite(sampleSoc)
                                || sampleSoc < 0 || sampleSoc > 100)
                                ? JSONObject.NULL : sampleSoc);
                        double sampleTemp = rs.getDouble("temp");
                        o.put("temp", (!rs.wasNull() && isFinite(sampleTemp)
                                && sampleTemp > -999) ? sampleTemp : JSONObject.NULL);
                        double sampleTempHigh = rs.getDouble("temp_high");
                        o.put("tempHigh", (!rs.wasNull() && isFinite(sampleTempHigh)
                                && sampleTempHigh > -999) ? sampleTempHigh : JSONObject.NULL);
                        double sampleTempLow = rs.getDouble("temp_low");
                        o.put("tempLow", (!rs.wasNull() && isFinite(sampleTempLow)
                                && sampleTempLow > -999) ? sampleTempLow : JSONObject.NULL);
                        results.put(o);
                    }
                }
            }
            noteReadOk();
            return results;
        } catch (Exception e) {
            if (isSqlFailure(e)) noteReadFailed();
            throw chargingHistoryReadException("get charging samples for " + id, e);
        }
    }

    private Connection requireChargingHistoryReadConnection() throws java.sql.SQLException {
        Connection conn = connection;
        if (!isInitialized || conn == null) {
            throw new java.sql.SQLException("charging history storage is unavailable");
        }
        try {
            if (conn.isClosed()) {
                throw new java.sql.SQLException("charging history storage is closed");
            }
        } catch (java.sql.SQLException e) {
            noteReadFailed();
            throw e;
        }
        return conn;
    }

    private static java.sql.SQLException chargingHistoryReadException(
            String operation, Exception failure) {
        if (failure instanceof java.sql.SQLException) {
            return (java.sql.SQLException) failure;
        }
        return new java.sql.SQLException("Could not " + operation, failure);
    }

    /**
     * Rollup summary for the charging dashboard/stats: period totals from the
     * permanent charging_daily, lifetime totals (survive pruning), SOH trend
     * from soc_daily, and the per-day series for the cost chart.
     */
    public synchronized JSONObject getChargingSummary(int daysBack) {
        if (!isInitialized || connection == null) {
            return new JSONObject();
        }
        try {
            return getChargingSummaryStrict(daysBack);
        } catch (java.sql.SQLException e) {
            logger.error("Failed to build charging summary", e);
            try { reconnect(); } catch (Exception ignored) {}
            return new JSONObject();
        }
    }

    public synchronized JSONObject getChargingSummaryStrict(
            int daysBack) throws java.sql.SQLException {
        long from = System.currentTimeMillis() - (daysBack * 24L * 60 * 60 * 1000L);
        return getChargingSummaryRangeStrict(from, Long.MAX_VALUE);
    }

    /**
     * Range variant of the rollup summary: period totals over day buckets in
     * [fromMs, toMs]. Lifetime totals + SOH trend remain all-time. toMs=
     * Long.MAX_VALUE = no upper bound.
     */
    public synchronized JSONObject getChargingSummaryRange(long fromMs, long toMs) {
        if (!isInitialized || connection == null) {
            return new JSONObject();
        }
        try {
            return getChargingSummaryRangeStrict(fromMs, toMs);
        } catch (java.sql.SQLException e) {
            logger.error("Failed to build charging summary", e);
            try { reconnect(); } catch (Exception ignored) {}
            return new JSONObject();
        }
    }

    /**
     * Strict API-facing rollup read. Query failure throws instead of returning
     * whichever fields happened to be populated before the failure.
     */
    public synchronized JSONObject getChargingSummaryRangeStrict(
            long fromMs, long toMs) throws java.sql.SQLException {
        JSONObject out = new JSONObject();
        final Connection conn = requireChargingHistoryReadConnection();
        try {
            long sinceDay = (fromMs / 86_400_000L) * 86_400_000L;
            long untilDay = (toMs == Long.MAX_VALUE) ? Long.MAX_VALUE : (toMs / 86_400_000L) * 86_400_000L;
            long untilExclusive = untilDay == Long.MAX_VALUE
                    || untilDay > Long.MAX_VALUE - 86_400_000L
                    ? Long.MAX_VALUE
                    : untilDay + 86_400_000L;
            java.util.Map<Long, Integer> estimatedByDay =
                    estimatedEnergySessionsByDay(
                            conn, sinceDay, untilExclusive);
            int periodEstimated = 0;
            for (Integer count : estimatedByDay.values()) {
                if (count != null) periodEstimated += count.intValue();
            }

            // Period aggregates from charging_daily.
            JSONArray daily = new JSONArray();
            double periodEnergy = 0, periodCost = 0;
            int periodSessions = 0, periodDc = 0, periodAc = 0, periodRange = 0, periodIncomplete = 0;
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT day_epoch, session_count, energy_kwh, cost, dc_count, ac_count, range_gained_km,"
                    + " incomplete_count FROM " + TABLE_CHARGING_DAILY
                    + " WHERE day_epoch >= ? AND day_epoch <= ? ORDER BY day_epoch ASC;")) {
                pstmt.setLong(1, sinceDay);
                pstmt.setLong(2, untilDay);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject d = new JSONObject();
                        d.put("day", rs.getLong("day_epoch"));
                        d.put("sessions", rs.getInt("session_count"));
                        d.put("energy", rs.getDouble("energy_kwh"));
                        d.put("cost", rs.getDouble("cost"));
                        // How many of the day's sessions carried a floor rather than a measured total.
                        // Exposed so a consumer can qualify the figure instead of reading it as exact.
                        d.put("incomplete", rs.getInt("incomplete_count"));
                        d.put("estimated",
                                estimatedByDay.getOrDefault(
                                        Long.valueOf(rs.getLong("day_epoch")),
                                        Integer.valueOf(0)).intValue());
                        daily.put(d);
                        periodSessions += rs.getInt("session_count");
                        periodEnergy += rs.getDouble("energy_kwh");
                        periodCost += rs.getDouble("cost");
                        periodDc += rs.getInt("dc_count");
                        periodAc += rs.getInt("ac_count");
                        periodRange += rs.getInt("range_gained_km");
                        periodIncomplete += rs.getInt("incomplete_count");
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
            out.put("periodIncompleteSessions", periodIncomplete);
            out.put("periodEstimatedSessions", periodEstimated);
            out.put("avgCostPerKwh", periodEnergy > 0 && periodCost > 0 ? periodCost / periodEnergy : JSONObject.NULL);

            // Lifetime totals (entire charging_daily — survives the prune).
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(SUM(session_count),0), COALESCE(SUM(energy_kwh),0), COALESCE(SUM(cost),0),"
                     + " COALESCE(SUM(incomplete_count),0) FROM " + TABLE_CHARGING_DAILY + ";")) {
                if (rs.next()) {
                    out.put("lifetimeSessions", rs.getInt(1));
                    out.put("lifetimeEnergyKwh", rs.getDouble(2));
                    out.put("lifetimeCost", rs.getDouble(3));
                    out.put("lifetimeIncompleteSessions", rs.getInt(4));
                }
            }
            out.put("lifetimeEstimatedSessions",
                    countEstimatedEnergySessions(
                            conn, 0L, Long.MAX_VALUE));

            // SOH-degradation trend from soc_daily.
            JSONArray sohTrend = new JSONArray();
            try (Statement st = conn.createStatement();
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
            noteReadOk();
            return out;
        } catch (Exception e) {
            if (isSqlFailure(e)) noteReadFailed();
            throw chargingHistoryReadException(
                    "build charging summary", e);
        }
    }

    /**
     * Count completed sessions whose displayed energy is not a complete direct meter reading.
     *
     * <p>The daily rollup predates energy provenance and intentionally remains compact. Detailed
     * charging rows are permanent, so deriving this quality count at read time avoids a schema
     * migration while keeping aggregate energy/cost/range labels honest for integrated, SOC-derived,
     * reconstructed, incomplete, and legacy unknown-source sessions.
     */
    private int countEstimatedEnergySessions(
            Connection conn, long fromInclusive,
            long toExclusive) throws java.sql.SQLException {
        String sql = "SELECT COUNT(*) FROM " + TABLE_CHARGING
                + " WHERE end_time IS NOT NULL"
                + " AND end_time >= ?"
                + (toExclusive == Long.MAX_VALUE
                        ? ""
                        : " AND end_time < ?")
                + " AND energy_added_kwh > 0"
                + " AND (energy_incomplete = 1"
                + " OR energy_source IS NULL"
                + " OR energy_source <> ?);";
        try (PreparedStatement query =
                     conn.prepareStatement(sql)) {
            int parameter = 1;
            query.setLong(parameter++, fromInclusive);
            if (toExclusive != Long.MAX_VALUE) {
                query.setLong(parameter++, toExclusive);
            }
            query.setString(
                    parameter,
                    com.overdrive.app.charging.SessionEnergyResolver
                            .SRC_METERED);
            try (ResultSet rs = query.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private java.util.Map<Long, Integer>
            estimatedEnergySessionsByDay(
                    Connection conn, long fromInclusive,
                    long toExclusive) throws java.sql.SQLException {
        java.util.Map<Long, Integer> counts =
                new java.util.HashMap<>();
        String sql = "SELECT end_time FROM " + TABLE_CHARGING
                + " WHERE end_time IS NOT NULL"
                + " AND end_time >= ?"
                + (toExclusive == Long.MAX_VALUE
                        ? ""
                        : " AND end_time < ?")
                + " AND energy_added_kwh > 0"
                + " AND (energy_incomplete = 1"
                + " OR energy_source IS NULL"
                + " OR energy_source <> ?);";
        try (PreparedStatement query =
                     conn.prepareStatement(sql)) {
            int parameter = 1;
            query.setLong(parameter++, fromInclusive);
            if (toExclusive != Long.MAX_VALUE) {
                query.setLong(parameter++, toExclusive);
            }
            query.setString(
                    parameter,
                    com.overdrive.app.charging.SessionEnergyResolver
                            .SRC_METERED);
            try (ResultSet rs = query.executeQuery()) {
                while (rs.next()) {
                    long endTime = rs.getLong(1);
                    long day = (endTime / 86_400_000L)
                            * 86_400_000L;
                    Long key = Long.valueOf(day);
                    counts.put(
                            key,
                            Integer.valueOf(
                                    counts.getOrDefault(
                                            key,
                                            Integer.valueOf(0))
                                            .intValue() + 1));
                }
            }
        }
        return counts;
    }

    /** Wipe only the charging-related tables (user "Clear charging history"). Returns rows deleted. */
    public synchronized long clearChargingHistory() {
        if (!isInitialized || connection == null) return -1;
        if (!reconcilePendingActiveChargingReplacement()) return -1;
        final long[] deleted = {0L};
        ChargingMaintenanceIntent stagedIntent = null;
        try {
            stagedIntent = beginChargingMaintenance("clearChargingHistory");
            final ChargingMaintenanceIntent intent = stagedIntent;
            final ActiveChargingReplacement replacement = intent.replacement;
            runInTransaction(() -> {
                try (Statement stmt = connection.createStatement()) {
                    deleted[0] += stmt.executeUpdate("DELETE FROM " + TABLE_CPS);
                    deleted[0] += stmt.executeUpdate("DELETE FROM " + TABLE_CHARGING);
                    deleted[0] += stmt.executeUpdate("DELETE FROM " + TABLE_CHARGING_DAILY);
                }
                insertActiveChargingReplacement(replacement);
            });
            publishCommittedChargingMaintenance(intent, replacement);
            replayPendingChargingPostCommitMetadata();
            logger.info("clearChargingHistory: removed " + deleted[0] + " rows");
            return deleted[0];
        } catch (Exception e) {
            ChargingMaintenanceOutcome outcome = stagedIntent != null
                    ? reconcileChargingMaintenanceOutcome(e)
                    : ChargingMaintenanceOutcome.ROLLED_BACK;
            if (outcome == ChargingMaintenanceOutcome.COMMITTED) {
                noteWriteOk();
                replayPendingChargingPostCommitMetadata();
                logger.warn("clearChargingHistory commit result was uncertain; reconciled its"
                        + " durable write-ahead maintenance intent");
                return deleted[0];
            }
            logger.error("clearChargingHistory failed", e);
            return -1;
        }
    }

    /**
     * Delete a single charging session (and its fine-grained samples), and
     * rebuild the affected {@code charging_daily} rollup so lifetime/period
     * totals stay consistent. Mirrors the Trips per-trip delete. Returns true
     * on success (also true if the row was already gone).
     */
    public synchronized boolean deleteChargingSession(long id) {
        if (!isInitialized || connection == null) return false;
        try {
            long startTime;
            long endTime;
            boolean closed;
            boolean found;
            try (PreparedStatement sel = connection.prepareStatement(
                    "SELECT start_time, end_time FROM " + TABLE_CHARGING + " WHERE id = ?;")) {
                sel.setLong(1, id);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        found = true;
                        startTime = rs.getLong("start_time");
                        endTime = rs.getLong("end_time");
                        closed = !rs.wasNull();
                    } else {
                        found = false;
                        startTime = 0L;
                        endTime = 0L;
                        closed = false;
                    }
                }
            }
            if (!found) {
                // A retry after an uncertain delete may arrive after the row disappeared but before
                // its display-only tariff usage metadata was repaired.
                replayPendingChargingPostCommitMetadata();
                return true;
            }

            if (!closed && wasCharging && chargingStartTime == startTime) {
                logger.warn("Refusing to delete active charging session " + id);
                return false;
            }

            final long sessionStart = startTime;
            final long sessionEnd = endTime;
            final boolean sessionClosed = closed;
            runInTransaction(() -> {
                try (PreparedStatement delS = connection.prepareStatement(
                        "DELETE FROM " + TABLE_CPS + " WHERE session_start_time = ?;")) {
                    delS.setLong(1, sessionStart);
                    delS.executeUpdate();
                }
                try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM " + TABLE_CHARGING + " WHERE id = ?;")) {
                    del.setLong(1, id);
                    if (del.executeUpdate() != 1) {
                        throw new java.sql.SQLException(
                                "charging session disappeared before delete: " + id);
                    }
                }
                if (sessionClosed && sessionEnd > 0) {
                    rebuildChargingDailyDay(dayEpoch(sessionEnd));
                }
            });
            replayPendingChargingPostCommitMetadata();
            logger.info("Deleted charging session " + id);
            return true;
        } catch (Exception e) {
            if (reconcileDeletedChargingSession(id, e)) {
                noteWriteOk();
                replayPendingChargingPostCommitMetadata();
                logger.warn("deleteChargingSession commit result was uncertain; reconciled"
                        + " durable absence of session " + id);
                return true;
            }
            logger.error("deleteChargingSession failed for " + id, e);
            return false;
        }
    }

    /**
     * Replace a completed session's total cost, derive its effective rate, and
     * rebuild that day's authoritative rollup in the same transaction.
     */
    public synchronized boolean updateChargingSessionCost(long id, double newCost) {
        if (!isInitialized || connection == null
                || !Double.isFinite(newCost)
                || !Float.isFinite((float) newCost)
                || (newCost < 0 && newCost != -1)) {
            return false;
        }

        final boolean[] updated = { false };
        final double[] storedRate = { -1 };
        try {
            runInTransaction(() -> {
                long startTime;
                long endTime;
                double energyAdded;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT start_time, end_time, energy_added_kwh"
                                + " FROM " + TABLE_CHARGING + " WHERE id = ?;")) {
                    select.setLong(1, id);
                    try (ResultSet rs = select.executeQuery()) {
                        if (!rs.next()) return;
                        startTime = rs.getLong("start_time");
                        endTime = rs.getLong("end_time");
                        if (rs.wasNull() || endTime <= startTime) {
                            logger.warn("Refusing to edit cost of in-progress charging session "
                                    + id);
                            return;
                        }
                        double storedEnergy = rs.getDouble("energy_added_kwh");
                        energyAdded = rs.wasNull() || !Double.isFinite(storedEnergy)
                                || storedEnergy < 0 ? 0 : storedEnergy;
                    }
                }

                double rate = newCost < 0
                        ? -1 : energyAdded > 0 ? newCost / energyAdded : 0;
                if (!Double.isFinite(rate)
                        || !Float.isFinite((float) rate)) {
                    throw new java.sql.SQLException(
                            "manual charging cost produced a non-finite rate");
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE " + TABLE_CHARGING
                                + " SET electricity_rate = ?, session_cost = ?,"
                                + " tariff_id = '', tariff_label = '',"
                                + " post_commit_tariff_applied = 1"
                                + " WHERE id = ?;")) {
                    update.setDouble(1, rate);
                    update.setDouble(2, newCost);
                    update.setLong(3, id);
                    if (update.executeUpdate() != 1) {
                        throw new java.sql.SQLException(
                                "charging session disappeared before cost update: " + id);
                    }
                }
                rebuildChargingDailyDay(dayEpoch(endTime));
                storedRate[0] = rate;
                updated[0] = true;
            });
            if (!updated[0]) return false;

            replayPendingChargingPostCommitMetadata();
            logger.info("Updated charging session " + id + " to cost " + newCost
                    + ", calculated rate " + storedRate[0]);
            return true;
        } catch (Exception e) {
            logger.error("updateChargingSessionCost failed for " + id, e);
            return false;
        }
    }

    /**
     * Get SOC statistics.
     */
    public synchronized JSONObject getSocStats(int hoursBack) {
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
            
            // Snapshot once — see the connection field's doc (guard-then-use can NPE if
            // reconnect() swaps the field between the two reads).
            final Connection conn = connection;
            if (!isInitialized || conn == null) {
                return stats;
            }
            
            long startTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L);
            
            // Get min/max/avg/count
            String statsSql = "SELECT MIN(soc_percent), MAX(soc_percent), AVG(soc_percent), COUNT(*) " +
                "FROM " + TABLE_SOC + " WHERE timestamp >= ?;";
            
            try (PreparedStatement pstmt = conn.prepareStatement(statsSql)) {
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
            
            try (PreparedStatement pstmt = conn.prepareStatement(chargingSql)) {
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
    public synchronized JSONObject getFullReport(int hoursBack, int maxPoints) {
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
                // NaN-safe: JSONObject.put THROWS on a NaN double, which would abort this whole
                // response rather than merely omitting one point. Both of these are live HAL-derived
                // values that can legitimately be NaN.
                livePoint.put("soc", Double.isNaN(currentSocData.socPercent)
                        ? JSONObject.NULL : currentSocData.socPercent);
                // Honour the CV taper, exactly as session accounting does. The state code stays
                // FINISHED during a taper by design, so a bare status test reported charging=false on the
                // live chart while the session row was still open and still accruing energy — the report
                // contradicted the accounting.
                livePoint.put("charging", chargingData != null
                    && (chargingData.status == ChargingStateData.ChargingStatus.CHARGING
                        || chargingData.isTaperCharging));
                boolean livePowerEstimated = chargingData != null && chargingData.isEstimated;
                double livePower = chargingData != null
                        ? chargingData.chargingPowerKW : Double.NaN;
                if (!livePowerEstimated && isFinite(livePower)
                        && livePower > 0 && livePower <= 500) {
                    livePoint.put("power", Math.round(livePower * 100) / 100.0);
                } else {
                    livePoint.put("power", JSONObject.NULL);
                }
                livePoint.put("powerEstimated", livePowerEstimated);
                livePoint.put("range", rangeData != null ? rangeData.elecRangeKm : 0);
                double liveKwh = monitor.getBatteryRemainPowerKwh();
                if (liveKwh > 0) livePoint.put("kwh", Math.round(liveKwh * 10) / 10.0);
                
                com.overdrive.app.abrp.SohEstimator sohEst = getSohEstimator();
                com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                        sohEst != null ? sohEst.getCapacitySohSnapshot() : null;
                if (capacitySoh != null && capacitySoh.hasDisplaySoh()) {
                    // Use the headline display chain (frame_anchor > capacity_ah
                    // > live > calibration on PHEV) so this last "live" point
                    // matches the chip / detail card the user sees, instead
                    // of the raw live formula that often diverges from the
                    // higher-priority anchors on PHEV trims.
                    livePoint.put("soh",
                            Math.round(capacitySoh.getDisplaySoh() * 10) / 10.0);
                }

                // MONOTONICITY GUARD, belt-and-braces with the query's upper bound. The chart
                // requires history to be ascending in `t`; appending a live point stamped with the
                // current clock behind a row that somehow carries a later timestamp would break
                // that and blank the line. If the last row is already newer, drop the live point
                // rather than corrupt the series — the row itself already carries current data.
                long liveT = livePoint.optLong("t", 0L);
                long lastT = Long.MIN_VALUE;
                if (history.length() > 0) {
                    JSONObject prev = history.optJSONObject(history.length() - 1);
                    if (prev != null) lastT = prev.optLong("t", Long.MIN_VALUE);
                }
                if (history.length() == 0 || liveT >= lastT) {
                    history.put(livePoint);
                } else {
                    logger.debug("Live SOC point dropped: its timestamp (" + liveT
                            + ") precedes the last stored row (" + lastT
                            + ") — clock moved backwards; keeping the series ascending");
                }
            }
            
            // Ensure stats has current SOC even if DB query returned nothing
            if (!stats.has("currentSoc") && currentSocData != null) {
                stats.put("currentSoc", currentSocData.socPercent);
                stats.put("isLow", currentSocData.isLow);
                stats.put("isCritical", currentSocData.isCritical);
            }
            
            report.put("history", history);
            report.put("stats", stats);
            int reportDays = Math.max(
                    1, (int) Math.ceil(hoursBack / 24.0));
            report.put("chargingSessions", getChargingSessions(reportDays));
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
    public synchronized void setSohEstimator(com.overdrive.app.abrp.SohEstimator estimator) {
        this.sohEstimator = estimator;
        replayPendingChargingPostCommitMetadata();
    }

    /**
     * Gate scheduler-owned lifecycle inference until ChargingSessionManager has loaded config and
     * reconciled detector state. Manager-owned edge calls remain available while the gate is closed.
     */
    public synchronized void setChargingLifecycleOwnerReady(boolean ready) {
        chargingLifecycleOwnerReady = ready;
        if (!ready) chargingLiveEnrichmentAllowed = false;
    }

    /** Update the physical charging flag independently of persistence-row close grace. */
    public synchronized void setPhysicalChargingNow(boolean chargingNow) {
        physicalChargingStateKnown = true;
        physicalChargingNow = chargingNow;
        chargingLiveEnrichmentAllowed = chargingNow;
    }

    /** Admit live open-row enrichment for a proven taper while physical fused state remains OFF. */
    public synchronized void setChargingLiveEnrichmentAllowed(boolean allowed) {
        chargingLiveEnrichmentAllowed = allowed && physicalChargingStateKnown;
    }

    /**
     * Opt-in gate for Charging Analytics session recording. Pushed by
     * {@link com.overdrive.app.charging.ChargingSessionManager} from
     * {@code ChargingConfig.enabled} at init and whenever the user toggles it.
     * When false, {@link #trackChargingSession} records nothing.
     */
    public synchronized boolean setChargingAnalyticsEnabled(boolean enabled) {
        if (!reconcilePendingActiveChargingReplacement()) return false;
        boolean wasEnabled = this.chargingAnalyticsEnabled;
        long priorDisabledSinceMs = analyticsDisabledSinceMs;
        long now = System.currentTimeMillis();
        // Re-enable is allowed to touch H2 below. First prove the exact disabled boundary already
        // captured in memory is durable; otherwise the close could commit while its retry image is
        // still only volatile.
        if (enabled && (optOutClosePending || chargingLifecycleJournalDirty)
                && !persistChargingLifecycleJournal()) {
            logger.warn("Charging analytics enable deferred until the opt-out boundary is durable");
            return false;
        }
        boolean bufferedReplacement = !enabled && !deferredPhysicalGenerations.isEmpty();
        if (!enabled && bufferedReplacement) {
            DeferredChargingGeneration current = currentDeferredPhysicalGeneration();
            if (current != null && !current.isEnded()) {
                endDeferredPhysicalGeneration(
                        current, now, currentSocForContinuation(), true);
            }
            for (DeferredChargingGeneration generation : deferredPhysicalGenerations) {
                generation.resumeBlocked = true;
            }
        }
        this.chargingAnalyticsEnabled = enabled;
        if (!enabled) {
            if (wasEnabled || analyticsDisabledSinceMs <= 0) analyticsDisabledSinceMs = now;
            chargingLifecycleHold = false;
            if (wasCharging && !optOutClosePending) {
                optOutClosePending = true;
                if (bufferedReplacement && pendingCloseSessionStart == chargingStartTime
                        && pendingCloseAtMs > 0L) {
                    // A already stopped and B is buffered behind A's failed close. Disabling analytics
                    // must close A at its frozen A boundary, not sample B's current SOC/counter into A.
                    optOutBoundaryMs = strictlyAfterChargingStart(
                            chargingStartTime, pendingCloseAtMs);
                    optOutBoundarySoc = !Double.isNaN(pendingCloseSoc)
                            ? pendingCloseSoc : chargingStartSoc;
                    optOutCounterCaptured = true;
                    optOutClosePricing = pendingClosePricing;
                    optOutCloseIsDc = pendingCloseIsDc;
                } else {
                    optOutBoundaryMs = strictlyAfterChargingStart(
                            chargingStartTime, now);
                    try {
                        BatterySocData sd = VehicleDataMonitor.getInstance().getBatterySoc();
                        optOutBoundarySoc = sd != null ? sd.socPercent
                                : lastRecordedSoc >= 0 ? lastRecordedSoc : chargingStartSoc;
                    } catch (Throwable ignored) {
                        optOutBoundarySoc = lastRecordedSoc >= 0
                                ? lastRecordedSoc : chargingStartSoc;
                    }
                    double counterAtBoundary = snapshotChargeCounterKwh();
                    if (!Double.isNaN(counterAtBoundary)) {
                        observeFinalCounterForClose(counterAtBoundary, optOutBoundarySoc, now);
                    }
                    optOutCounterCaptured = true;
                    chargingPeakPower = resolvePeakKw(chargingStartTime, chargingPeakPower);
                    optOutCloseIsDc = currentChargingTypeVerdict();
                    try {
                        optOutClosePricing = priceSessionForClose(
                                optOutCloseIsDc, chargingStartLat, chargingStartLng);
                    } catch (Exception unavailable) {
                        optOutClosePricing = null;
                        logger.warn("Opt-out close pricing unavailable at boundary: "
                                + unavailable.getMessage());
                    }
                }
            }
            // A pre-session reading captured before opt-out cannot become the baseline of a segment
            // recorded after re-enable; doing so would credit energy delivered while analytics was off.
            preSessionCounterLowKwh = Double.NaN;
            preSessionCounterAtMs = 0L;
            preSessionCounterSource = null;
        } else if (optOutClosePending && wasCharging) {
            // Retry immediately while the captured boundary is still the only admitted endpoint.
            // The central fence at trackChargingSession's entry temporarily restores disabled mode,
            // commits the resume-blocked close, and leaves the pending state intact if it still fails.
            if (!trackChargingSession(false,
                    !Double.isNaN(optOutBoundarySoc) ? optOutBoundarySoc : chargingStartSoc,
                    0, optOutBoundaryMs > 0 ? optOutBoundaryMs : now)) {
                logger.warn("Analytics re-enabled while the opt-out boundary is still pending;"
                        + " accounting remains fenced until its close commits");
            }
        } else {
            // No pending row owns the disabled boundary, so normal enabled accounting can resume.
            analyticsDisabledSinceMs = 0L;
        }
        boolean durable = persistChargingLifecycleJournal();
        if (!durable && enabled && !wasEnabled) {
            // Do not expose a newly-enabled recorder before its lifecycle boundary is durable.
            this.chargingAnalyticsEnabled = false;
            chargingLifecycleHold = false;
            analyticsDisabledSinceMs =
                    priorDisabledSinceMs > 0L ? priorDisabledSinceMs : now;
        }
        return durable;
    }

    /** Hold/release manager ownership of the currently-open session row. */
    public synchronized void setChargingLifecycleHold(boolean held) {
        this.chargingLifecycleHold = held && chargingAnalyticsEnabled;
    }

    /**
     * True only while the manager owns the bounded post-stop lifecycle window for a live row.
     *
     * <p>The collector uses this to admit the final counter callback after authoritative gun-out.
     * Deriving the result from both feature state and the in-memory open row prevents a stale hold
     * bit from admitting callbacks after opt-out, close, clear, or reset.
     */
    public synchronized boolean isChargingLifecycleHoldActive() {
        if (!reconcilePendingActiveChargingReplacement()) return false;
        return chargingLifecycleHold
                && chargingAnalyticsEnabled
                && wasCharging
                && chargingStartTime > 0;
    }

    /**
     * Clean up old remaining_kwh records using a one-shot repair limited to legacy PHEV rows.
     */
    public synchronized void fixStaleRemainingKwh(double nominalCapacityKwh) {
        com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh = null;
        try {
            if (sohEstimator != null) {
                capacitySoh = sohEstimator.getCapacitySohSnapshot();
            }
        } catch (Throwable ignored) {}
        if (capacitySoh == null
                || Double.doubleToLongBits(capacitySoh.getNominalCapacityKwh())
                    != Double.doubleToLongBits(nominalCapacityKwh)) {
            logger.warn("Legacy remaining_kwh migration skipped: nominal capacity snapshot changed");
            return;
        }
        fixStaleRemainingKwh(capacitySoh);
    }

    public synchronized boolean fixStaleRemainingKwh(
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh) {
        if (!isInitialized || connection == null || capacitySoh == null) return false;

        com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot attemptSnapshot = capacitySoh;
        for (int attempt = 1; attempt <= REMAINING_KWH_MIGRATION_ATTEMPTS; attempt++) {
            if (!isPhevRemainingKwhMigrationSnapshot(attemptSnapshot)) return false;

            final com.overdrive.app.abrp.SohEstimator expectedEstimator = sohEstimator;
            final com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot expectedSnapshot =
                    attemptSnapshot;
            final int[] correctedRows = {0};
            final boolean[] alreadyComplete = {false};
            try {
                runInTransaction(() -> {
                    if (isRemainingKwhMigrationComplete()) {
                        alreadyComplete[0] = true;
                        return;
                    }

                    requireCurrentMigrationSnapshot(expectedEstimator, expectedSnapshot);
                    correctedRows[0] = migrateLegacyRemainingKwhRows(expectedSnapshot);
                    requireCurrentMigrationSnapshot(expectedEstimator, expectedSnapshot);
                    markRemainingKwhMigrationComplete();
                    // Keep this as the final operation before runInTransaction commits. A reset or
                    // nominal change during the scan rolls back both row updates and the marker.
                    requireCurrentMigrationSnapshot(expectedEstimator, expectedSnapshot);
                }, transactionConnection -> commitRemainingKwhMigrationWithSnapshot(
                        expectedEstimator, expectedSnapshot, transactionConnection));

                noteWriteOk();
                if (alreadyComplete[0]) {
                    logger.debug("Legacy remaining_kwh migration already complete");
                } else {
                    logger.info("Migrated " + correctedRows[0]
                            + " legacy remaining_kwh records (nominal="
                            + String.format("%.1f", expectedSnapshot.getNominalCapacityKwh())
                            + " kWh)");
                }
                return true;
            } catch (EstimatorSnapshotChangedException changed) {
                attemptSnapshot = captureCurrentCapacitySohSnapshot();
                logger.warn("Legacy remaining_kwh migration snapshot changed; retrying ("
                        + attempt + "/" + REMAINING_KWH_MIGRATION_ATTEMPTS + ")");
            } catch (Exception e) {
                if (reconcileRemainingKwhMigrationCommit(e)) {
                    noteWriteOk();
                    logger.warn("Legacy remaining_kwh migration commit result was uncertain;"
                            + " durable version marker confirmed");
                    return true;
                }
                logger.error("Failed to migrate legacy remaining_kwh: " + e.getMessage());
                return false;
            }
        }

        logger.warn("Legacy remaining_kwh migration deferred after repeated estimator changes");
        return false;
    }

    private static final class LegacyRemainingKwhUpdate {
        final long id;
        final double targetKwh;

        LegacyRemainingKwhUpdate(long id, double targetKwh) {
            this.id = id;
            this.targetKwh = targetKwh;
        }
    }

    private static final class EstimatorSnapshotChangedException extends Exception {
        EstimatorSnapshotChangedException() {
            super("SOH estimator capacity snapshot changed");
        }
    }

    private int migrateLegacyRemainingKwhRows(
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh)
            throws Exception {
        java.util.ArrayList<LegacyRemainingKwhUpdate> updates = new java.util.ArrayList<>();
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT id, soc_percent, remaining_kwh, soh_percent FROM " + TABLE_SOC
                        + " WHERE (remaining_kwh_format_version IS NULL"
                        + " OR remaining_kwh_format_version < ?)"
                        + " AND soc_percent > 0 AND remaining_kwh > 0;")) {
            read.setInt(1, REMAINING_KWH_FORMAT_VERSION);
            try (ResultSet rs = read.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    double socPercent = rs.getDouble(2);
                    double remainingKwh = rs.getDouble(3);
                    double rowSohPercent = rs.getDouble(4);
                    if (rs.wasNull()) rowSohPercent = Double.NaN;
                    double targetKwh = legacyRemainingKwhTarget(
                            socPercent,
                            capacitySoh.getNominalCapacityKwh(),
                            rowSohPercent,
                            // Never rewrite historical rows from today's mutable display SOH. A row
                            // without its own historical SOH uses the deterministic 100% fallback.
                            Double.NaN);
                    if (!Double.isNaN(targetKwh)
                            && Math.abs(remainingKwh - targetKwh) / targetKwh > 0.12) {
                        updates.add(new LegacyRemainingKwhUpdate(id, targetKwh));
                    }
                }
            }
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + TABLE_SOC
                        + " SET remaining_kwh = ?, remaining_kwh_format_version = ?"
                        + " WHERE id = ? AND (remaining_kwh_format_version IS NULL"
                        + " OR remaining_kwh_format_version < ?);")) {
            for (LegacyRemainingKwhUpdate row : updates) {
                update.setDouble(1, row.targetKwh);
                update.setInt(2, REMAINING_KWH_FORMAT_VERSION);
                update.setLong(3, row.id);
                update.setInt(4, REMAINING_KWH_FORMAT_VERSION);
                update.addBatch();
            }
            if (!updates.isEmpty()) update.executeBatch();
        }
        try (PreparedStatement markRows = connection.prepareStatement(
                "UPDATE " + TABLE_SOC + " SET remaining_kwh_format_version = ?"
                        + " WHERE remaining_kwh_format_version IS NULL"
                        + " OR remaining_kwh_format_version < ?;")) {
            markRows.setInt(1, REMAINING_KWH_FORMAT_VERSION);
            markRows.setInt(2, REMAINING_KWH_FORMAT_VERSION);
            markRows.executeUpdate();
        }
        return updates.size();
    }

    static double legacyRemainingKwhTarget(double socPercent, double nominalCapacityKwh,
                                           double rowSohPercent, double fallbackDisplaySoh) {
        if (Double.isNaN(socPercent) || socPercent <= 0 || socPercent > 100
                || Double.isNaN(nominalCapacityKwh) || nominalCapacityKwh <= 0) {
            return Double.NaN;
        }
        double selectedSoh = rowSohPercent > 0 && rowSohPercent <= 100
                ? rowSohPercent
                : fallbackDisplaySoh > 0 && fallbackDisplaySoh <= 100
                        ? fallbackDisplaySoh : 100.0;
        return (socPercent / 100.0) * nominalCapacityKwh * (selectedSoh / 100.0);
    }

    private boolean isRemainingKwhMigrationComplete() throws Exception {
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT version FROM " + TABLE_DATA_MIGRATIONS
                        + " WHERE migration_name = ?;")) {
            read.setString(1, REMAINING_KWH_MIGRATION);
            try (ResultSet rs = read.executeQuery()) {
                return rs.next() && rs.getInt(1) >= REMAINING_KWH_FORMAT_VERSION;
            }
        }
    }

    private void markRemainingKwhMigrationComplete() throws Exception {
        try (PreparedStatement marker = connection.prepareStatement(
                "MERGE INTO " + TABLE_DATA_MIGRATIONS
                        + " (migration_name, version, completed_at)"
                        + " KEY(migration_name) VALUES (?, ?, ?);")) {
            marker.setString(1, REMAINING_KWH_MIGRATION);
            marker.setInt(2, REMAINING_KWH_FORMAT_VERSION);
            marker.setLong(3, System.currentTimeMillis());
            marker.executeUpdate();
        }
    }

    private boolean reconcileRemainingKwhMigrationCommit(Exception failure) {
        try {
            if (isRemainingKwhMigrationComplete()) return true;
        } catch (Exception ignored) {
            // Retry below after normal broken-connection escalation.
        }
        if (isSqlFailure(failure)) noteWriteFailed();
        try { reconnect(); } catch (Exception ignored) {}
        try {
            return connection != null && isRemainingKwhMigrationComplete();
        } catch (Exception ignored) {
            return false;
        }
    }

    private com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot
            captureCurrentCapacitySohSnapshot() {
        try {
            com.overdrive.app.abrp.SohEstimator estimator = sohEstimator;
            return estimator != null ? estimator.getCapacitySohSnapshot() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void requireCurrentMigrationSnapshot(
            com.overdrive.app.abrp.SohEstimator expectedEstimator,
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot expectedSnapshot)
            throws EstimatorSnapshotChangedException {
        if (expectedEstimator == null || sohEstimator != expectedEstimator) {
            throw new EstimatorSnapshotChangedException();
        }
        com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot current;
        try {
            current = expectedEstimator.getCapacitySohSnapshot();
        } catch (Throwable unavailable) {
            throw new EstimatorSnapshotChangedException();
        }
        if (!sameCapacitySohSnapshot(expectedSnapshot, current)) {
            throw new EstimatorSnapshotChangedException();
        }
    }

    /**
     * Keep the final estimator-generation check and JDBC commit in one estimator lock hold. Without
     * this guard, reset/nominal mutation could land after the final check in the transaction body but
     * before {@link Connection#commit()}, publishing a migration marker for a stale capacity frame.
     */
    private void commitRemainingKwhMigrationWithSnapshot(
            com.overdrive.app.abrp.SohEstimator expectedEstimator,
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot expectedSnapshot,
            Connection transactionConnection) throws Exception {
        if (expectedEstimator == null || expectedSnapshot == null
                || sohEstimator != expectedEstimator) {
            throw new EstimatorSnapshotChangedException();
        }
        boolean committed = expectedEstimator.runWithEstimatorGenerationGuard(
                expectedSnapshot.getEstimatorGeneration(),
                transactionConnection::commit);
        if (!committed) throw new EstimatorSnapshotChangedException();
    }

    private static boolean sameCapacitySohSnapshot(
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot expected,
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot current) {
        return expected != null
                && current != null
                && expected.getEstimatorGeneration()
                    == current.getEstimatorGeneration()
                && expected.getResetModelEpoch()
                    == current.getResetModelEpoch()
                && Double.doubleToLongBits(expected.getNominalCapacityKwh())
                    == Double.doubleToLongBits(current.getNominalCapacityKwh());
    }

    private static boolean isPhevRemainingKwhMigrationSnapshot(
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot snapshot) {
        return snapshot != null
                && snapshot.getNominalCapacityKwh() > 0
                && snapshot.getNominalCapacityKwh() < 30.0;
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
    public synchronized JSONArray getBatteryVoltageHistory(int hoursBack, int maxPoints) {
        JSONArray results = new JSONArray();
        // Snapshot once — see the connection field's doc (guard-then-use can NPE).
        final Connection conn = connection;
        if (!isInitialized || conn == null) return results;
        
        try {
            long now = System.currentTimeMillis();
            int hours = Math.min(hoursBack, 168);
            long startTime = now - (hours * 60 * 60 * 1000L);
            long timeRangeMs = hours * 60 * 60 * 1000L;
            long bucketMs = Math.max(120_000L, timeRangeMs / maxPoints);
            
            String sql = 
                "SELECT MIN(timestamp) as t, AVG(voltage_v) as voltage, " +
                "  MAX(is_charging) as charging " +
                // Upper-bounded by now for the same reason as getSocHistory: after a backward
                // clock correction, pre-correction rows carry future timestamps, still match
                // `>= startTime`, and break this chart's ascending-time assumption.
                "FROM " + TABLE_SOC + " WHERE timestamp >= ? AND timestamp <= ? AND voltage_v > 0 " +
                "GROUP BY (timestamp / ?) ORDER BY t ASC LIMIT ?;";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, startTime);
                pstmt.setLong(2, now);   // same clock read as startTime — see getSocHistory
                pstmt.setLong(3, bucketMs);
                pstmt.setInt(4, maxPoints);
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
            // Count a genuine JDBC failure so a broken-but-OPEN connection escalates to
            // dead and the reconnect below actually reopens it. Non-SQL throws are not
            // counted — they say nothing about the connection (see isSqlFailure).
            if (isSqlFailure(e)) noteReadFailed();
            reconnect();
        }
        return results;
    }
    
    /**
     * Get HV battery thermal history for charting.
     */
    public synchronized JSONArray getThermalHistory(int hoursBack, int maxPoints) {
        JSONArray results = new JSONArray();
        // Snapshot once — see the connection field's doc (guard-then-use can NPE).
        final Connection conn = connection;
        if (!isInitialized || conn == null) return results;
        
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
                // Upper-bounded by now — see getSocHistory / getBatteryVoltageHistory.
                "FROM " + TABLE_SOC + " WHERE timestamp >= ? AND timestamp <= ? " +
                "AND (hv_temp_high > -999 OR hv_temp_low > -999 OR hv_temp_avg > -999) " +
                "GROUP BY (timestamp / ?) ORDER BY t ASC LIMIT ?;";
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, startTime);
                pstmt.setLong(2, now);   // same clock read as startTime — see getSocHistory
                pstmt.setLong(3, bucketMs);
                pstmt.setInt(4, maxPoints);
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
            // Count a genuine JDBC failure so a broken-but-OPEN connection escalates to
            // dead and the reconnect below actually reopens it. Non-SQL throws are not
            // counted — they say nothing about the connection (see isSqlFailure).
            if (isSqlFailure(e)) noteReadFailed();
            reconnect();
        }
        return results;
    }
    
    /**
     * Get battery health report — current state + historical stats.
     */
    public synchronized JSONObject getBatteryHealthReport(int hoursBack, int maxPoints) {
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
            if (socData != null && !Double.isNaN(socData.socPercent)) {
                // Omitted rather than emitted as NaN — JSONObject.put throws on NaN, which would
                // fail the whole response instead of one field.
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
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                    sohEst != null ? sohEst.getCapacitySohSnapshot() : null;
            if (capacitySoh != null && capacitySoh.hasDisplaySoh()) {
                // Headline display chain (frame_anchor > capacity_ah > live >
                // calibration on PHEV) — keeps the battery-health card and
                // the SoH detail card in lockstep instead of showing two
                // different numbers on PHEV trims where capacity_ah outranks
                // the live formula.
                double nominalCapacityKwh = capacitySoh.getNominalCapacityKwh();
                double estimatedCapacityKwh = nominalCapacityKwh > 0
                        ? capacitySoh.getDisplaySoh() / 100.0 * nominalCapacityKwh
                        : -1;
                current.put("soh",
                        Math.round(capacitySoh.getDisplaySoh() * 10) / 10.0);
                current.put("estimatedCapacityKwh",
                        Math.round(estimatedCapacityKwh * 10) / 10.0);
                current.put("nominalCapacityKwh", nominalCapacityKwh);
            } else {
                logger.info("Canonical SOH unavailable for health report");
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
                // One clock read shared by startTime and the queries' upper bound, so a backward
                // NTP step between two reads cannot make upper < startTime (see getSocHistory).
                long now = System.currentTimeMillis();
                long startTime = now - (hoursBack * 60 * 60 * 1000L);
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
                    // Upper-bounded by now — third time-series chart with the same backward-clock
                    // hazard as getSocHistory / voltage / thermal.
                    "FROM " + TABLE_SOC + " WHERE timestamp >= ? AND timestamp <= ? "
                    + "AND soh_percent > 0 " +
                    "GROUP BY (timestamp / ?) ORDER BY t ASC LIMIT ?;";
                long sohBucketMs = Math.max(120_000L, (long)(hoursBack) * 60 * 60 * 1000L / maxPoints);
                JSONArray sohHistory = new JSONArray();
                try (PreparedStatement pstmt = connection.prepareStatement(sohSql)) {
                    pstmt.setLong(1, startTime);
                    pstmt.setLong(2, now);
                    pstmt.setLong(3, sohBucketMs);
                    pstmt.setInt(4, maxPoints);
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
    public synchronized long resetAll() {
        if (!isInitialized || connection == null) return -1;
        if (!reconcilePendingActiveChargingReplacement()) return -1;
        final int[] deleted = new int[4];
        ChargingMaintenanceIntent stagedIntent = null;
        try {
            stagedIntent = beginChargingMaintenance("resetAll");
            final ChargingMaintenanceIntent intent = stagedIntent;
            final ActiveChargingReplacement replacement = intent.replacement;
            runInTransaction(() -> {
                try (Statement stmt = connection.createStatement()) {
                    deleted[0] = stmt.executeUpdate("DELETE FROM " + TABLE_SOC);
                    deleted[1] = stmt.executeUpdate("DELETE FROM " + TABLE_CPS);
                    deleted[2] = stmt.executeUpdate("DELETE FROM " + TABLE_CHARGING);
                    deleted[3] = stmt.executeUpdate("DELETE FROM " + TABLE_CHARGING_DAILY)
                            + stmt.executeUpdate("DELETE FROM " + TABLE_SOC_DAILY)
                            + stmt.executeUpdate("DELETE FROM " + TABLE_ACC_EVENTS);
                }
                insertActiveChargingReplacement(replacement);
            });
            publishCommittedChargingMaintenance(intent, replacement);
            replayPendingChargingPostCommitMetadata();
            int total = deleted[0] + deleted[1] + deleted[2] + deleted[3];
            logger.info("resetAll: cleared " + deleted[0] + " from " + TABLE_SOC
                + ", " + deleted[2] + " from " + TABLE_CHARGING
                + ", and " + (deleted[1] + deleted[3])
                + " from charging/accounting support tables");
            return total;
        } catch (Exception e) {
            ChargingMaintenanceOutcome outcome = stagedIntent != null
                    ? reconcileChargingMaintenanceOutcome(e)
                    : ChargingMaintenanceOutcome.ROLLED_BACK;
            if (outcome == ChargingMaintenanceOutcome.COMMITTED) {
                noteWriteOk();
                replayPendingChargingPostCommitMetadata();
                int total = deleted[0] + deleted[1] + deleted[2] + deleted[3];
                logger.warn("resetAll commit result was uncertain; reconciled its durable"
                        + " write-ahead maintenance intent");
                return total;
            }
            logger.error("resetAll failed", e);
            return -1;
        }
    }

    private synchronized void cleanupOldData() {
        if (!isInitialized || connection == null) return;

        try {
            long now = System.currentTimeMillis();
            // Floored to a whole UTC day: soc_daily is MERGE-keyed by day and MERGE OVERWRITES, so a
            // mid-day cutoff rolls the boundary day up across two passes and the second pass replaces
            // the first half's min/max/avg/count instead of accumulating it.
            long socCutoff = dayEpoch(now - (SOC_RETENTION_DAYS * 24 * 60 * 60 * 1000L));
            long cpsCutoff = now - (CPS_RETENTION_DAYS * 24 * 60 * 60 * 1000L);
            long accCutoff = now - (ACC_RETENTION_DAYS * 24 * 60 * 60 * 1000L);

            // ROLLUP-ON-PRUNE: fold soc_history rows about to be deleted into the
            // permanent soc_daily table so the SOH/temp degradation trend outlives
            // the 30-day raw-sample window. MERGE upserts one row per UTC day.
            boolean rolledUp = false;
            try {
                String rollup =
                    "MERGE INTO " + TABLE_SOC_DAILY +
                    " (day_epoch, min_soc, max_soc, avg_soc, soh_percent, hv_temp_avg, sample_count) KEY(day_epoch) " +
                    "SELECT (timestamp/86400000)*86400000 AS d, MIN(soc_percent), MAX(soc_percent), AVG(soc_percent), " +
                    "MAX(CASE WHEN soh_percent > 0 THEN soh_percent ELSE NULL END), " +
                    "AVG(CASE WHEN hv_temp_avg > -999 THEN hv_temp_avg ELSE NULL END), COUNT(*) " +
                    // The GROUP BY must match the select expression TEXTUALLY. H2 resolves grouped
                    // expressions by generated-SQL equality, so a bare "(timestamp/86400000)" left the
                    // rescaled select item ungrouped and threw MUST_GROUP_BY_COLUMN on every run.
                    "FROM " + TABLE_SOC + " WHERE timestamp < ? GROUP BY (timestamp/86400000)*86400000;";
                try (PreparedStatement pstmt = connection.prepareStatement(rollup)) {
                    pstmt.setLong(1, socCutoff);
                    pstmt.executeUpdate();
                }
                rolledUp = true;
            } catch (Exception e) {
                logger.error("soc_daily rollup failed — deferring the prune so the raw rows survive"
                        + " to the next pass", e);
            }

            // Prune soc_history ONLY once its rows are aggregated — autoCommit is on here, so a
            // failed rollup and a successful delete would otherwise commit independently and destroy
            // the history un-rolled. Re-running is safe: whole-day cutoff + MERGE upsert is idempotent.
            if (rolledUp) {
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "DELETE FROM " + TABLE_SOC + " WHERE timestamp < ?;")) {
                    pstmt.setLong(1, socCutoff);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        logger.info("Pruned " + deleted + " soc_history rows (rolled into soc_daily)");
                    }
                }
            } else {
                // A rollup that keeps failing must not grow soc_history without bound. Past twice the
                // retention window the raw rows go anyway, loudly, so the loss is never silent.
                long hardCutoff = dayEpoch(now - (2 * SOC_RETENTION_DAYS * 24 * 60 * 60 * 1000L));
                try (PreparedStatement pstmt = connection.prepareStatement(
                        "DELETE FROM " + TABLE_SOC + " WHERE timestamp < ?;")) {
                    pstmt.setLong(1, hardCutoff);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        logger.error("Pruned " + deleted + " soc_history rows WITHOUT rollup (past 2x"
                                + " retention) — soc_daily is permanently missing that history");
                    }
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
    public synchronized int getRecordCount() {
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
    
    public synchronized boolean isAvailable() {
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
    // synchronized for the same reason as finalizeStaleOpenSessions: an external
    // caller (CameraDaemon's ACC edge) that INSERTs on the shared Connection.
    public synchronized void recordAccEvent(String eventType, com.overdrive.app.byd.BydVehicleData data) {
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
    public synchronized JSONObject getLastParkingDelta(int maxAgeHours) {
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
     *  { startTime, endTime, durationMinutes, energyAddedKwh, startSoc, endSoc,
     *    energySource, energyIncomplete, energyEstimated }
     */
    public synchronized JSONObject getMostRecentCompletedChargingSession(int hoursBack) {
        if (!isAvailable()) return null;
        if (hoursBack <= 0) return null;
        try {
            long cutoff = System.currentTimeMillis() - (hoursBack * 60L * 60L * 1000L);
            String sql = "SELECT start_time, end_time, start_soc, end_soc, energy_added_kwh,"
                + " energy_source, energy_incomplete " +
                "FROM " + TABLE_CHARGING +
                " WHERE end_time IS NOT NULL AND end_time >= ? " +
                "ORDER BY end_time DESC LIMIT 1";
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setLong(1, cutoff);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) return null;
                    long start = rs.getLong(1);
                    long end = rs.getLong(2);
                    double startSoc = rs.getDouble(3);
                    boolean startSocMissing = rs.wasNull();
                    double endSoc = rs.getDouble(4);
                    boolean endSocMissing = rs.wasNull();
                    double energy = rs.getDouble(5);
                    String energySource = rs.getString(6);
                    boolean energyIncomplete = rs.getInt(7) == 1;
                    if (end <= start) return null;
                    if (!Double.isFinite(energy) || energy <= 0 || energy > 500) return null;
                    long durationMin = (end - start) / 60_000L;
                    if (durationMin <= 0 || durationMin > 7 * 24 * 60) return null;
                    JSONObject out = new JSONObject();
                    out.put("startTime", start);
                    out.put("endTime", end);
                    out.put("durationMinutes", durationMin);
                    out.put("energyAddedKwh", Math.round(energy * 10) / 10.0);
                    out.put("energySource",
                            energySource != null
                                    && !energySource.isEmpty()
                                    ? energySource
                                    : JSONObject.NULL);
                    out.put("energyIncomplete",
                            energyIncomplete);
                    out.put("energyEstimated",
                            energySource == null
                                    || energySource.isEmpty()
                                    || isSessionEnergyEstimated(
                                            energySource,
                                            energyIncomplete));
                    out.put("startSoc",
                            startSocMissing
                                    ? JSONObject.NULL
                                    : jsonSoc(Math.round(startSoc * 10) / 10.0));
                    out.put("endSoc",
                            endSocMissing
                                    ? JSONObject.NULL
                                    : jsonSoc(Math.round(endSoc * 10) / 10.0));
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
    public synchronized double getSocChangeRatePerHour() {
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
