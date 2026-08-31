package com.overdrive.app.roadsense.store

import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.roadsense.detect.ALTITUDE_UNKNOWN
import com.overdrive.app.roadsense.detect.HazardType
import com.overdrive.app.roadsense.detect.RoadSenseHazard
import com.overdrive.app.roadsense.detect.Severity
import com.overdrive.app.roadsense.detect.StoredHazard
import com.overdrive.app.roadsense.detect.altitudeKnown
import com.overdrive.app.roadsense.detect.altitudeMatches
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.overdrive.app.util.ScratchPaths

/**
 * Local-first hazard store for RoadSense [D-017].
 *
 * H2 embedded (pure-Java JDBC) — NOT Room/native SQLite. This mirrors the
 * project's existing [com.overdrive.app.monitor.SocHistoryDatabase] and the
 * recordings index, and it is the deliberate house choice (D-017): H2 has zero
 * native deps (no .so / UnsatisfiedLinkError), no Android `Context` dependency,
 * and runs cleanly in the **CameraDaemon, UID 2000** process where RoadSense's
 * whole detection/store/sync pipeline lives [D-019, D-020, D-023]. Room would
 * break that cross-UID story.
 *
 * Lifecycle, JDBC-URL flags, the held-open synchronized connection, the
 * stale-lock recovery, and the [ScheduledExecutorService] retention prune are
 * all copied conventions from `SocHistoryDatabase` so this class looks like it
 * belongs next to it. Where the algorithm types are Kotlin (RoadSenseTypes),
 * we keep the Kotlin style of `GravityFrame` — but the H2 mechanics are a
 * line-for-line mirror of the Java daemon class.
 *
 * Threading: every method that touches [connection] does so inside
 * `synchronized(lock)`, exactly as the Java class does. The store is a
 * singleton; the daemon owns its lifecycle.
 *
 * ── Contract seam (RoadSenseHazard vs the table) ─────────────────────────────
 * `RoadSenseHazard` (the detector's output) carries only the *measured* fields:
 * lat, lng, type, severity, headingDeg, confidence, speedKmh, aVertPeak, tMs.
 * The store OWNS the bookkeeping columns the contract does not carry:
 *   - `id`            — synthesized UUID on insert.
 *   - `tile`          — derived from lat/lng via [SpatialIndex.tileKey].
 *   - `observations`  — starts at 1, incremented on same-spot merge.
 *   - `status`        — starts 0 (candidate); → 1 (locally confirmed) per D-012.
 *   - `human_verified`— 0 until Calibration Mode confirms (D-012/R-DET-7).
 *   - `source`        — 0 (local) for everything this store writes; cloud rows
 *                       (source=1) arrive via the sync layer, not here.
 *   - `device_id`     — left NULL locally; the sync layer stamps the rotating
 *                       anon UUID only on upload (R-EXT-5). See the report.
 *   - `created_ms`/`updated_ms` — both seeded from `nowMs` on insert.
 * `RoadSenseHazard.tMs` (detection time) is recorded as the *detection* instant
 * but the store uses the caller-supplied `nowMs` for created/updated so the
 * write clock is consistent with the retention prune; see `upsertDetection`.
 */
class RoadSenseStore private constructor() {

    // ──────────────────────────── Tunables ──────────────────────────────────

    companion object {
        private const val TAG = "RoadSenseStore"
        private val logger = DaemonLogger.getInstance(TAG)

        /**
         * Sibling of SocHistoryDatabase's `/data/local/tmp/overdrive_soc_h2`.
         * `/data/local/tmp` is the daemon-writable (UID 2000) location the
         * project already uses for its H2 stores.
         */
        private val DB_PATH = ScratchPaths.path("overdrive_roadsense_h2")

        /**
         * JDBC URL — identical flag set to SocHistoryDatabase:
         *  • FILE_LOCK=SOCKET     — socket lock, the real cross-process safety net.
         *  • TRACE_LEVEL_FILE=0   — no .trace.db spew.
         *  • DB_CLOSE_ON_EXIT=FALSE — we drive shutdown from [stop]; H2's JVM
         *    shutdown hook must not race our explicit close (the "Database is
         *    already closed" + orphaned lock.db pair). AUTO_SERVER is omitted on
         *    purpose — H2 rejects AUTO_SERVER together with DB_CLOSE_ON_EXIT=FALSE,
         *    and we are single-process (only the daemon writes; the daemon's HTTP
         *    handlers read in the same JVM).
         */
        // AUTO_COMPACT_FILL_RATE=50: idle-CPU tuning shared by all seven H2
        // stores (see SocHistoryDatabase.JDBC_URL for the rationale).
        private val JDBC_URL =
            "jdbc:h2:file:$DB_PATH;FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE" +
                ";AUTO_COMPACT_FILL_RATE=50"

        private const val TABLE = "roadsense_hazards"

        /**
         * Two-tier same-spot merge (D-032). The old single 8 m radius conflated
         * two different jobs: (a) absorbing GPS jitter on ONE pass over ONE bump,
         * and (b) deduping a LATER pass over that same bump. At 8 m it also
         * swallowed genuinely DISTINCT adjacent hazards (two breakers a car-length
         * apart) into one row — exactly the under-representation D-032 fixes. So we
         * split the radius by TIME:
         *
         *  • [TIGHT_MERGE_RADIUS_M] (4 m): always merge. This is tight enough that
         *    two distinct hazards stay distinct, but covers same-pass GPS jitter
         *    (typical 3–5 m accuracy + back-projection slack).
         *  • [REPEAT_MERGE_RADIUS_M] (8 m): merge ONLY if the existing row was last
         *    updated ≥ [REPEAT_PASS_MIN_GAP_MS] ago — i.e. a genuine separate pass,
         *    not a second distinct hazard detected seconds apart in the SAME drive.
         *    A later pass over the same spot can land up to ~8 m off (different lane,
         *    different GPS fix) yet is still the same hazard; two distinct hazards
         *    detected within the same minute will NOT both be inside this gate.
         *
         * The consensus radius (R=10 m, D-012) stays a touch looser still; this
         * local merge stays tighter so the cloud does the cross-device clustering.
         */
        private const val TIGHT_MERGE_RADIUS_M = 4.0
        private const val REPEAT_MERGE_RADIUS_M = 8.0

        /**
         * Minimum age of an existing row before a detection in the 4–8 m "loose"
         * band is treated as a repeat pass (and merged) rather than a distinct
         * adjacent hazard. 60 s comfortably exceeds the time to drive THROUGH a
         * cluster of close hazards (a few seconds) while being far below the gap to
         * any realistic later pass over the same spot. Tunable (R-DET-7).
         */
        private const val REPEAT_PASS_MIN_GAP_MS = 60_000L

        /**
         * Observation count at/above which a candidate is promoted to
         * locally-confirmed (status 1) per D-012's K=2 self-confirm rule.
         */
        private const val LOCAL_CONFIRM_OBSERVATIONS = 2

        /** status values (D-012). */
        private const val STATUS_CANDIDATE = 0
        private const val STATUS_LOCALLY_CONFIRMED = 1
        // status 2 (shared/cloud-confirmed) is set by the consensus/sync layer.

        /** source values. Locally-detected rows are SOURCE_LOCAL (uploadable);
         *  rows pulled from the crowdsource backend are SOURCE_CLOUD and must NEVER
         *  be re-uploaded (audit network #7 — otherwise a hazard one real device
         *  confirmed ping-pongs through downloaders and fabricates distinct-device
         *  consensus). queryForUpload filters on SOURCE_LOCAL. */
        private const val SOURCE_LOCAL = 0
        private const val SOURCE_CLOUD = 1

        /**
         * Retention: candidate-tier rows (status 0) that have NOT been
         * re-observed within this many days are pruned. Confirmed rows
         * (status ≥ 1) and human-verified rows are kept indefinitely — they are
         * the calibration ground truth and the user's confirmed map. 30 days
         * gives a candidate plenty of separate drives to earn confirmation
         * before it is dropped as noise.
         */
        private const val CANDIDATE_RETENTION_DAYS = 30L

        /** Retention prune cadence — mirrors SocHistoryDatabase's daily sweep. */
        private const val PRUNE_INTERVAL_HOURS = 24L

        @Volatile
        private var instance: RoadSenseStore? = null
        private val singletonLock = Any()

        @JvmStatic
        fun getInstance(): RoadSenseStore {
            instance?.let { return it }
            synchronized(singletonLock) {
                instance?.let { return it }
                return RoadSenseStore().also { instance = it }
            }
        }
    }

    // ──────────────────────────── State ─────────────────────────────────────

    /** Guards every access to [connection], exactly like the Java class. */
    private val lock = Any()

    /** Held-open H2 connection (perf — re-opening per query thrashes the lock file). */
    private var connection: Connection? = null

    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var running = false

    init {
        // Load the pure-Java H2 driver. Same as SocHistoryDatabase's ctor.
        try {
            Class.forName("org.h2.Driver")
            logger.info("H2 JDBC Driver loaded successfully")
        } catch (e: ClassNotFoundException) {
            logger.error("H2 Driver not found! Check gradle dependencies.", e)
        } catch (e: Exception) {
            logger.error("Failed to load H2 Driver: " + e.message, e)
        }
    }

    // ──────────────────────────── Lifecycle ─────────────────────────────────

    /**
     * Open the connection, create the table + indices if absent, recover from a
     * stale lock left by a crashed daemon. Idempotent. Mirrors
     * SocHistoryDatabase.init() including the 3-attempt lock-recovery retry.
     */
    fun init() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return

            logger.info("Initializing RoadSense H2 store at: $DB_PATH")

            val maxRetries = 3
            val retryDelayMs = 1000L
            var attempt = 1
            while (attempt <= maxRetries) {
                try {
                    connection = DriverManager.getConnection(JDBC_URL, "sa", "")
                    logger.info("H2 connection established")

                    connection!!.createStatement().use { stmt ->
                        stmt.execute("SET CACHE_SIZE 8192") // 8MB cache
                    }

                    createSchema()

                    initialized = true
                    logger.info("RoadSense store initialized via H2 (Pure Java): $DB_PATH")
                    return
                } catch (e: Exception) {
                    val msg = e.message
                    val isLockError = msg != null && (
                        msg.contains("Locked by another process") ||
                            msg.contains("lock.db") ||
                            msg.contains("already in use")
                        )
                    if (isLockError && attempt < maxRetries) {
                        logger.warn("Store locked (attempt $attempt/$maxRetries), cleaning stale locks...")
                        cleanupStaleLocks()
                        try {
                            Thread.sleep(retryDelayMs * attempt) // backoff
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    } else {
                        logger.error("Failed to init RoadSense store: ${e.javaClass.name} - $msg", e)
                        break
                    }
                }
                attempt++
            }
        }
    }

    /**
     * Start the background retention prune. Safe to call after [init]; will init
     * if not already done. Mirrors SocHistoryDatabase.start()'s scheduler setup
     * (single-thread, MIN_PRIORITY, uncaught-exception guard so it can't die
     * silently).
     */
    fun start() {
        if (running) return
        if (!initialized) init()
        if (!initialized) {
            logger.error("Cannot start RoadSense prune — store init failed")
            return
        }
        running = true

        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "RoadSenseStore").apply {
                priority = Thread.MIN_PRIORITY
                setUncaughtExceptionHandler { _, ex ->
                    logger.error("Uncaught exception in RoadSenseStore thread: " + ex.message, ex)
                }
            }
        }

        // First prune after one cycle (don't fight init), then daily.
        scheduler!!.scheduleAtFixedRate({
            try {
                pruneStaleCandidates()
            } catch (t: Throwable) {
                logger.error("Critical error in RoadSense prune task: " + t.message, t)
            }
        }, PRUNE_INTERVAL_HOURS, PRUNE_INTERVAL_HOURS, TimeUnit.HOURS)

        logger.info("RoadSense retention prune started (interval ${PRUNE_INTERVAL_HOURS}h, candidate TTL ${CANDIDATE_RETENTION_DAYS}d)")
    }

    /**
     * Shut down the scheduler and close the connection. Mirrors
     * SocHistoryDatabase.stop(): wait briefly for an in-flight prune so we don't
     * close the connection out from under it, then close. We do NOT reconnect on
     * shutdown — re-opening would re-acquire the lock file just before the JVM
     * exits and orphan it for the next daemon start.
     */
    fun stop() {
        running = false
        scheduler?.let { s ->
            s.shutdown()
            try {
                if (!s.awaitTermination(2, TimeUnit.SECONDS)) s.shutdownNow()
            } catch (ie: InterruptedException) {
                s.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        scheduler = null

        synchronized(lock) {
            connection?.let {
                try {
                    it.close()
                } catch (ignored: Exception) {
                }
            }
            connection = null
            initialized = false
        }
        logger.info("RoadSense store stopped")
    }

    /**
     * Delete stale lock + trace files left by a crashed daemon, so the retry in
     * [init] can re-open. Same heuristic as SocHistoryDatabase: only remove a
     * lock file older than 5 minutes (a live holder touches it far more often).
     */
    private fun cleanupStaleLocks() {
        try {
            val lockFile = File("$DB_PATH.lock.db")
            if (lockFile.exists()) {
                val ageMs = System.currentTimeMillis() - lockFile.lastModified()
                if (ageMs > 5 * 60 * 1000) {
                    if (lockFile.delete()) logger.info("Deleted stale lock file (age ${ageMs / 1000}s)")
                }
            }
            val traceFile = File("$DB_PATH.trace.db")
            if (traceFile.exists()) traceFile.delete()
        } catch (e: Exception) {
            logger.debug("Lock cleanup failed: " + e.message)
        }
    }

    /**
     * Reopen the connection if it was lost mid-run (but never after [stop], to
     * avoid orphaning the lock file at JVM exit). Mirrors the Java reconnect().
     */
    private fun reconnect() {
        if (!running) return
        try {
            if (connection == null || connection!!.isClosed) {
                connection = DriverManager.getConnection(JDBC_URL, "sa", "")
                logger.debug("H2 connection re-established")
            }
        } catch (e: Exception) {
            logger.error("Failed to reconnect to H2", e)
        }
    }

    private fun createSchema() {
        connection!!.createStatement().use { stmt ->
            // Column names match dev/roadsense/03-ARCHITECTURE.md "Data model".
            // type/severity/status/human_verified/source are small ints; the
            // doubles use DOUBLE (REAL in the doc — H2 DOUBLE is the precise
            // fit for the Kotlin Double lat/lng we store).
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS $TABLE (" +
                    "id VARCHAR(40) PRIMARY KEY," +
                    "lat DOUBLE NOT NULL," +
                    "lng DOUBLE NOT NULL," +
                    "tile BIGINT NOT NULL," +
                    "type INT NOT NULL," +
                    "severity INT NOT NULL," +
                    "heading DOUBLE," +
                    "confidence DOUBLE," +
                    "speed_kmh DOUBLE," +
                    "a_vert_peak DOUBLE," +
                    "altitude DOUBLE," +   // NULL = level unknown (fail-open); see ALTITUDE_MATCH_M
                    "observations INT DEFAULT 1," +
                    "status INT DEFAULT 0," +
                    "human_verified INT DEFAULT 0," +
                    "source INT DEFAULT 0," +
                    "device_id VARCHAR(64)," +
                    "created_ms BIGINT NOT NULL," +
                    "updated_ms BIGINT NOT NULL" +
                    ");"
            )

            // Migration for stores created before altitude disambiguation: add the
            // column NULL-defaulted so every pre-existing hazard reads as "level
            // unknown" and stays fail-open (never suppressed). Idempotent.
            stmt.execute("ALTER TABLE $TABLE ADD COLUMN IF NOT EXISTS altitude DOUBLE;")

            // Single-column tile index for the approach query (WHERE tile IN ...).
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rs_tile ON $TABLE(tile);")

            // Compound (tile, updated_ms) index for delta-sync (R-CRD-5): the
            // approach/download cursor queries are exactly WHERE tile IN (...)
            // AND updated_ms > cursor, mirroring the D1 schema's index.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rs_tile_updated ON $TABLE(tile, updated_ms);")

            // Compound (source, updated_ms) index for the UPLOAD scan (queryForUpload):
            // WHERE source = ? AND updated_ms > ? ORDER BY updated_ms — this query is
            // tile-LESS, so neither tile index above can serve it and it would full-scan
            // the table every sync (audit storage LOW). Leading on source (the equality)
            // then updated_ms (the range + the ORDER BY) is the exact access path.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rs_source_updated ON $TABLE(source, updated_ms);")

            // Compound (lat, lng) index for the map's bbox query (queryByBbox):
            // WHERE lat >= ? AND lat <= ? AND lng >= ? AND lng <= ?. Without it,
            // every map poll full-scans + sorts the whole hazard table — and
            // because ALL store access funnels through one synchronized H2
            // connection, that scan serializes against the live IMU detection
            // upserts happening many times/min while driving. The native map
            // polls this endpoint ~1-2x/s (auto-follow), so it is a hot path
            // despite the queryByBbox comment. Leading on lat (the range H2 can
            // seek) bounds the scan to the visible latitude band; lng is then a
            // cheap residual filter. idx_rs_tile cannot serve a lat/lng range.
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_rs_lat_lng ON $TABLE(lat, lng);")

            logger.info("$TABLE table + indices ready")
        }
    }

    // ──────────────────────────── Writes ────────────────────────────────────

    /**
     * "Map from drive one" write [D-015]: store EVERY detection above the
     * detection floor; nothing gates storing. We either merge into an existing
     * same-spot, same-type hazard or insert a new candidate.
     *
     * Merge logic:
     *   1. Look in the current tile + 8 neighbours (the 3×3 block from
     *      [SpatialIndex.neighborTiles]) for rows of the SAME [HazardType].
     *   2. Among those, pick the closest within [MERGE_RADIUS_M] true metres
     *      (Haversine — the tile grid is only a coarse pre-filter, D-006).
     *   3. If found → MERGE: observations++, confidence = max(old, new) (a
     *      repeat sighting can only raise our belief, never lower it),
     *      a_vert_peak = max, severity = max (worst seen wins — a deeper hit on
     *      a later pass should not be downgraded), updated_ms = nowMs, and
     *      promote status to locally-confirmed once observations ≥ K=2 [D-012].
     *   4. Else → INSERT a new candidate (status 0, observations 1, confidence
     *      as detected — it starts low and climbs with repeats, D-015).
     *
     * @param hazard the detector's assessment (measured fields only).
     * @param nowMs  the write clock (caller-supplied so it is consistent with
     *               the retention prune; usually System.currentTimeMillis()).
     * @param source [SOURCE_LOCAL] for locally-detected rows (default; uploadable)
     *               or [SOURCE_CLOUD] for rows pulled from the backend (NOT
     *               re-uploaded — audit network #7). A merge into an existing CLOUD
     *               row never demotes it to LOCAL.
     * @return the id of the row written or updated, or empty string on failure.
     */
    @JvmOverloads
    fun upsertDetection(hazard: RoadSenseHazard, nowMs: Long, source: Int = SOURCE_LOCAL): String {
        synchronized(lock) {
            if (!ensureOpen()) return ""
            try {
                val tile = SpatialIndex.tileKey(hazard.lat, hazard.lng)
                val typeOrdinal = hazard.type.ordinal
                val neighbours = SpatialIndex.neighborTiles(hazard.lat, hazard.lng)

                // 1+2: find the closest same-type existing hazard within the
                // time-gated merge radius (D-032 two-tier: tight always, loose only
                // for a genuine later pass).
                // A merge keeps the EXISTING row's source (the UPDATE never touches
                // it), so a local re-detection over a downloaded CLOUD row folds in
                // without demoting it to LOCAL / minting an uploadable duplicate.
                val existing = findMergeTarget(neighbours, typeOrdinal, hazard.lat, hazard.lng, hazard.altitudeM, nowMs)
                if (existing != null) {
                    return mergeInto(existing, hazard, nowMs)
                }

                // 3: insert a fresh candidate.
                val id = UUID.randomUUID().toString()
                val sql =
                    "INSERT INTO $TABLE (id, lat, lng, tile, type, severity, heading, confidence, " +
                        "speed_kmh, a_vert_peak, altitude, observations, status, human_verified, source, " +
                        "device_id, created_ms, updated_ms) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);"
                connection!!.prepareStatement(sql).use { ps ->
                    ps.setString(1, id)
                    ps.setDouble(2, hazard.lat)
                    ps.setDouble(3, hazard.lng)
                    ps.setLong(4, tile)
                    ps.setInt(5, typeOrdinal)
                    ps.setInt(6, hazard.severity.level)
                    ps.setDouble(7, hazard.headingDeg.toDouble())
                    ps.setDouble(8, hazard.confidence.toDouble())
                    ps.setDouble(9, hazard.speedKmh.toDouble())
                    ps.setDouble(10, hazard.aVertPeak.toDouble())
                    if (altitudeKnown(hazard.altitudeM)) ps.setDouble(11, hazard.altitudeM)
                    else ps.setNull(11, java.sql.Types.DOUBLE)   // NULL = level unknown
                    ps.setInt(12, 1) // observations
                    ps.setInt(13, STATUS_CANDIDATE)
                    ps.setInt(14, 0) // human_verified
                    ps.setInt(15, source)
                    ps.setNull(16, java.sql.Types.VARCHAR) // device_id: stamped by sync only
                    ps.setLong(17, nowMs)
                    ps.setLong(18, nowMs)
                    ps.executeUpdate()
                }
                logger.debug("Inserted candidate $id type=${hazard.type} sev=${hazard.severity.level} conf=${hazard.confidence}")
                return id
            } catch (e: Exception) {
                logger.error("upsertDetection failed: " + e.message, e)
                reconnect()
                return ""
            }
        }
    }

    /**
     * Store a hazard pulled from the crowdsource backend (R-CRD-5 download path).
     * Identical to [upsertDetection] but tags the row [SOURCE_CLOUD] so it is never
     * re-uploaded (audit network #7). A merge onto an existing row keeps that row's
     * source; only a brand-new insert is stamped CLOUD.
     */
    fun upsertCloudHazard(hazard: RoadSenseHazard, nowMs: Long): String =
        upsertDetection(hazard, nowMs, SOURCE_CLOUD)

    /** A loaded existing hazard we may merge a new detection into. */
    private data class MergeTarget(
        val id: String,
        val observations: Int,
        val confidence: Double,
        val severity: Int,
        val aVertPeak: Double,
        val humanVerified: Int,
        val heading: Double,
    )

    /**
     * Find the nearest existing same-type hazard within [MERGE_RADIUS_M] across
     * the given neighbour tiles. SQL is strictly tile-scoped (WHERE tile IN ...)
     * so it never full-scans; the metric radius test happens in Kotlin on the
     * handful of rows the tile pre-filter returns.
     */
    private fun findMergeTarget(
        tiles: LongArray,
        typeOrdinal: Int,
        lat: Double,
        lng: Double,
        altitudeM: Double,
        nowMs: Long,
    ): MergeTarget? {
        val placeholders = tiles.joinToString(",") { "?" }
        val sql =
            "SELECT id, lat, lng, observations, confidence, severity, a_vert_peak, " +
                "human_verified, heading, altitude, updated_ms FROM $TABLE WHERE type = ? AND tile IN ($placeholders);"
        connection!!.prepareStatement(sql).use { ps ->
            ps.setInt(1, typeOrdinal)
            tiles.forEachIndexed { i, t -> ps.setLong(2 + i, t) }
            ps.executeQuery().use { rs ->
                var best: MergeTarget? = null
                // Start at the loosest radius we could ever accept; the per-row
                // gate below tightens it for recent rows (D-032).
                var bestDist = REPEAT_MERGE_RADIUS_M
                while (rs.next()) {
                    val rLat = rs.getDouble("lat")
                    val rLng = rs.getDouble("lng")
                    val d = GeoMath.haversineMeters(lat, lng, rLat, rLng)
                    if (d > bestDist) continue
                    // Altitude gate (flyover vs surface road below): don't merge two
                    // same-tile/same-type detections at clearly different levels. Fail-
                    // open — NULL/unknown on either side, or close altitude, still
                    // merges; only a confident > ALTITUDE_MATCH_M delta blocks it.
                    val rAltRaw = rs.getDouble("altitude")
                    val rAlt = if (rs.wasNull()) ALTITUDE_UNKNOWN else rAltRaw
                    if (!altitudeMatches(altitudeM, rAlt)) continue
                    // Two-tier gate: within the tight radius we always merge (same-
                    // pass GPS jitter). In the 4–8 m loose band we ONLY merge if this
                    // row is old enough to be a separate pass — otherwise it's a
                    // distinct adjacent hazard and must stay its own row (D-032).
                    val ageMs = nowMs - rs.getLong("updated_ms")
                    val mergeable = d <= TIGHT_MERGE_RADIUS_M || ageMs >= REPEAT_PASS_MIN_GAP_MS
                    if (!mergeable) continue
                    bestDist = d
                    best = MergeTarget(
                        id = rs.getString("id"),
                        observations = rs.getInt("observations"),
                        confidence = rs.getDouble("confidence"),
                        severity = rs.getInt("severity"),
                        aVertPeak = rs.getDouble("a_vert_peak"),
                        humanVerified = rs.getInt("human_verified"),
                        heading = rs.getDouble("heading"),
                    )
                }
                return best
            }
        }
    }

    /**
     * MERGE branch of [upsertDetection]: fold a repeat detection into an
     * existing hazard. Caller already holds [lock].
     */
    private fun mergeInto(target: MergeTarget, hazard: RoadSenseHazard, nowMs: Long): String {
        val newObservations = target.observations + 1
        // Confidence can only rise on a corroborating sighting (D-015: confidence
        // climbs with repeats); take the max of stored vs detected.
        val newConfidence = maxOf(target.confidence, hazard.confidence.toDouble())
        // Worst severity / peak seen wins — never downgrade a known-severe spot.
        val newSeverity = maxOf(target.severity, hazard.severity.level)
        val newPeak = maxOf(target.aVertPeak, hazard.aVertPeak.toDouble())
        // Heading UPGRADE (audit accuracy S1 follow-up): if the stored heading is the
        // "unknown" sentinel (<0, recorded when the first detection was too slow for a
        // reliable GPS bearing) and this re-detection HAS a valid heading, adopt it —
        // otherwise the row would stay road-match-exempt forever. If the stored heading
        // is already valid we keep it (don't thrash it with a noisier later bearing).
        val incomingHeading = hazard.headingDeg.toDouble()
        val newHeading =
            if (target.heading < 0.0 && incomingHeading >= 0.0) incomingHeading
            else target.heading
        // Promote to locally-confirmed at K=2 [D-012]; a human-verified row is
        // already confirmed and must not be demoted.
        val newStatus =
            if (newObservations >= LOCAL_CONFIRM_OBSERVATIONS || target.humanVerified == 1)
                STATUS_LOCALLY_CONFIRMED
            else STATUS_CANDIDATE

        val sql =
            "UPDATE $TABLE SET observations = ?, confidence = ?, severity = ?, " +
                "a_vert_peak = ?, status = ?, heading = ?, updated_ms = ? WHERE id = ?;"
        connection!!.prepareStatement(sql).use { ps ->
            ps.setInt(1, newObservations)
            ps.setDouble(2, newConfidence)
            ps.setInt(3, newSeverity)
            ps.setDouble(4, newPeak)
            ps.setInt(5, newStatus)
            ps.setDouble(6, newHeading)
            ps.setLong(7, nowMs)
            ps.setString(8, target.id)
            ps.executeUpdate()
        }
        logger.debug("Merged into ${target.id}: obs=$newObservations conf=$newConfidence status=$newStatus")
        return target.id
    }

    /**
     * Calibration-Mode label [D-012 / R-DET-7]: the user has confirmed,
     * rejected, or corrected the algorithm's assessment of a hazard.
     *
     *  - confirmed=true  → human_verified=1, status=locally-confirmed (instant
     *    confirm, K=1 human path); apply any severity/type corrections.
     *  - confirmed=false → reject: the row is DELETED (a human said "not a
     *    hazard"; keeping it would poison both warnings and the ground-truth
     *    set). corrections are ignored on a reject.
     *
     * @param correctedSeverity if non-null, overwrite severity (expects the
     *        stored 1..3 encoding; ignored on reject).
     * @param correctedType     if non-null, overwrite type (expects the stored
     *        ordinal; ignored on reject).
     */
    fun markHumanVerified(
        id: String,
        confirmed: Boolean,
        correctedSeverity: Int?,
        correctedType: Int?,
        nowMs: Long,
    ) {
        synchronized(lock) {
            if (!ensureOpen()) return
            try {
                if (!confirmed) {
                    connection!!.prepareStatement("DELETE FROM $TABLE WHERE id = ?;").use { ps ->
                        ps.setString(1, id)
                        val n = ps.executeUpdate()
                        logger.info("Rejected hazard $id via Calibration Mode (deleted $n)")
                    }
                    return
                }

                // Build the SET clause dynamically but with PreparedStatement
                // params only (never string-interpolated values).
                val sets = StringBuilder("human_verified = 1, status = ?, updated_ms = ?")
                if (correctedSeverity != null) sets.append(", severity = ?")
                if (correctedType != null) sets.append(", type = ?")
                val sql = "UPDATE $TABLE SET $sets WHERE id = ?;"

                connection!!.prepareStatement(sql).use { ps ->
                    var i = 1
                    ps.setInt(i++, STATUS_LOCALLY_CONFIRMED)
                    ps.setLong(i++, nowMs)
                    if (correctedSeverity != null) ps.setInt(i++, correctedSeverity)
                    if (correctedType != null) ps.setInt(i++, correctedType)
                    ps.setString(i, id)
                    val n = ps.executeUpdate()
                    logger.info("Human-verified hazard $id (sevΔ=$correctedSeverity typeΔ=$correctedType, updated $n)")
                }
            } catch (e: Exception) {
                logger.error("markHumanVerified failed: " + e.message, e)
                reconnect()
            }
        }
    }

    // ──────────────────────────── Reads ─────────────────────────────────────

    /**
     * Tile-scoped fetch of hazards near a position, for the ApproachEngine's
     * "what's ahead of me" query. We return everything in the current tile + 8
     * neighbours (the 3×3 block); the actual bearing-cone "is it ahead?" filter
     * is the ApproachEngine's job — we deliberately hand it the tile-scoped set
     * and let it apply [GeoMath.bearingDeltaDeg] against `bearingDeg`. SQL is
     * always `WHERE tile IN (...)`, never a full-table scan (D-006).
     *
     * `bearingDeg` is accepted for API symmetry / future server-side cone
     * pushdown but not used to filter here (kept on the engine).
     */
    fun queryAhead(
        lat: Double,
        lng: Double,
        @Suppress("UNUSED_PARAMETER") bearingDeg: Double,
        maxResults: Int,
    ): List<StoredHazard> {
        synchronized(lock) {
            if (!ensureOpen()) return emptyList()
            try {
                val tiles = SpatialIndex.neighborTiles(lat, lng)
                val placeholders = tiles.joinToString(",") { "?" }
                // Order by recency so a maxResults cap keeps the freshest rows.
                val sql =
                    "SELECT * FROM $TABLE WHERE tile IN ($placeholders) " +
                        "ORDER BY updated_ms DESC LIMIT ?;"
                connection!!.prepareStatement(sql).use { ps ->
                    tiles.forEachIndexed { i, t -> ps.setLong(1 + i, t) }
                    ps.setInt(1 + tiles.size, maxResults)
                    ps.executeQuery().use { rs -> return readHazards(rs) }
                }
            } catch (e: Exception) {
                logger.error("queryAhead failed: " + e.message, e)
                reconnect()
                return emptyList()
            }
        }
    }

    /**
     * Sync-layer read [D-016]: high-confidence rows updated since a cursor, for
     * the upload batch. This is a tile-LESS scan (all eligible rows regardless of
     * tile), so it is served by the dedicated idx_rs_source_updated(source,
     * updated_ms) index — NOT the tile indexes, which can't help a query with no
     * tile predicate. The HIGH upload bar (default ~0.7) is the caller's
     * `minConfidence`; this is independent of the personal warn threshold.
     *
     * Returns rows with source = LOCAL AND confidence ≥ minConfidence AND
     * updated_ms > since, ordered by updated_ms so the caller can advance its cursor.
     */
    fun queryForUpload(minConfidence: Double, sinceMs: Long): List<StoredHazard> {
        synchronized(lock) {
            if (!ensureOpen()) return emptyList()
            try {
                val sql =
                    "SELECT * FROM $TABLE WHERE confidence >= ? AND updated_ms > ? " +
                        "AND source = ? ORDER BY updated_ms ASC;"
                connection!!.prepareStatement(sql).use { ps ->
                    ps.setDouble(1, minConfidence)
                    ps.setLong(2, sinceMs)
                    ps.setInt(3, SOURCE_LOCAL) // only ever upload our own locally-mapped rows
                    ps.executeQuery().use { rs -> return readHazards(rs) }
                }
            } catch (e: Exception) {
                logger.error("queryForUpload failed: " + e.message, e)
                reconnect()
                return emptyList()
            }
        }
    }

    /**
     * Map-view read: every hazard whose lat/lng falls inside a viewport bounding box.
     * Backs the RoadSense map's `GET /api/roadsense/hazards?bbox=` endpoint. Unlike
     * [queryAhead] (3×3-tile scoped for the live "what's ahead" cone), the map needs the
     * whole visible rectangle, so this is a lat/lng range scan ordered by recency and
     * capped at [maxResults] (freshest kept). A bbox is a tiny fraction of the table and
     * the result is small; this is not a hot path (fired on map pan/zoom, debounced client-side).
     */
    fun queryByBbox(
        minLat: Double,
        minLng: Double,
        maxLat: Double,
        maxLng: Double,
        maxResults: Int,
    ): List<StoredHazard> {
        synchronized(lock) {
            if (!ensureOpen()) return emptyList()
            try {
                val sql =
                    "SELECT * FROM $TABLE WHERE lat >= ? AND lat <= ? AND lng >= ? AND lng <= ? " +
                        "ORDER BY updated_ms DESC LIMIT ?;"
                connection!!.prepareStatement(sql).use { ps ->
                    ps.setDouble(1, minLat)
                    ps.setDouble(2, maxLat)
                    ps.setDouble(3, minLng)
                    ps.setDouble(4, maxLng)
                    ps.setInt(5, maxResults)
                    ps.executeQuery().use { rs -> return readHazards(rs) }
                }
            } catch (e: Exception) {
                logger.error("queryByBbox failed: " + e.message, e)
                reconnect()
                return emptyList()
            }
        }
    }

    /**
     * Materialize a result set into [StoredHazard]s — the measured [RoadSenseHazard]
     * PLUS the row identity + bookkeeping (id/status/observations/humanVerified/
     * timestamps) the store owns. Read paths return identity so the overlay can
     * address a specific row for `markHumanVerified` and the sync layer can map
     * consensus responses back (the seam fix). The inner hazard's `tMs` is the
     * original detection time (`created_ms`); last-touch is exposed as `updatedMs`.
     */
    private fun readHazards(rs: ResultSet): List<StoredHazard> {
        val out = ArrayList<StoredHazard>()
        while (rs.next()) {
            val createdMs = rs.getLong("created_ms")
            out.add(
                StoredHazard(
                    id = rs.getString("id"),
                    hazard = RoadSenseHazard(
                        lat = rs.getDouble("lat"),
                        lng = rs.getDouble("lng"),
                        type = hazardTypeFromOrdinal(rs.getInt("type")),
                        severity = severityFromLevel(rs.getInt("severity")),
                        headingDeg = rs.getDouble("heading").toFloat(),
                        confidence = rs.getDouble("confidence").toFloat(),
                        speedKmh = rs.getDouble("speed_kmh").toFloat(),
                        aVertPeak = rs.getDouble("a_vert_peak").toFloat(),
                        tMs = createdMs,
                        // NULL altitude (old rows / no-fix) → unknown sentinel (fail-open).
                        // getDouble then wasNull immediately, before any other column read.
                        altitudeM = rs.getDouble("altitude").let { if (rs.wasNull()) ALTITUDE_UNKNOWN else it },
                    ),
                    status = rs.getInt("status"),
                    observations = rs.getInt("observations"),
                    humanVerified = rs.getInt("human_verified") != 0,
                    createdMs = createdMs,
                    updatedMs = rs.getLong("updated_ms"),
                )
            )
        }
        return out
    }

    // ──────────────────────────── Maintenance ───────────────────────────────

    /**
     * "Delete local calibrations" action [R-SET-5]: wipe every locally-stored
     * hazard. The table remains so mapping resumes on the next detection.
     * Returns rows deleted, or -1 on failure.
     */
    fun deleteAllLocal(): Long {
        synchronized(lock) {
            if (!ensureOpen()) return -1
            try {
                connection!!.createStatement().use { stmt ->
                    val n = stmt.executeUpdate("DELETE FROM $TABLE;")
                    logger.info("deleteAllLocal: cleared $n hazards")
                    return n.toLong()
                }
            } catch (e: Exception) {
                logger.error("deleteAllLocal failed: " + e.message, e)
                return -1
            }
        }
    }

    /**
     * Retention prune: drop candidate-tier rows (status 0, NOT human-verified)
     * that have not been re-observed within [CANDIDATE_RETENTION_DAYS]. Confirmed
     * rows (status ≥ 1) and any human-verified row are kept indefinitely — they
     * are the user's confirmed map + the calibration ground truth.
     */
    private fun pruneStaleCandidates() {
        synchronized(lock) {
            if (!ensureOpen()) return
            try {
                val cutoff = System.currentTimeMillis() - CANDIDATE_RETENTION_DAYS * 24 * 60 * 60 * 1000L
                val sql =
                    "DELETE FROM $TABLE WHERE status = ? AND human_verified = 0 AND updated_ms < ?;"
                connection!!.prepareStatement(sql).use { ps ->
                    ps.setInt(1, STATUS_CANDIDATE)
                    ps.setLong(2, cutoff)
                    val n = ps.executeUpdate()
                    if (n > 0) logger.info("Pruned $n stale candidate hazards (older than ${CANDIDATE_RETENTION_DAYS}d)")
                }
            } catch (e: Exception) {
                logger.error("pruneStaleCandidates failed: " + e.message, e)
                reconnect()
            }
        }
    }

    // ──────────────────────────── Helpers ───────────────────────────────────

    /**
     * Ensure the connection is open and usable, reconnecting once if it was
     * closed mid-run. Caller MUST hold [lock]. Returns false if unusable.
     */
    private fun ensureOpen(): Boolean {
        if (!initialized) {
            // Lazy open (on-demand): RoadSense is no longer start()'d at daemon boot when
            // the feature is disabled, so the store isn't pre-opened. But read/delete paths
            // that DON'T depend on roadSense.enabled — the map's queryByBbox and the
            // delete-local wipe — can still arrive while disabled. Open the connection on
            // demand so those keep working, WITHOUT the always-on retention-prune scheduler
            // that start() owns (there's nothing to prune while disabled anyway; enabling
            // the feature later runs start() → the prune scheduler as before). init() is
            // idempotent and reentrant on [lock] (the caller already holds it).
            init()
            if (!initialized) {
                logger.debug("Store not initialized (lazy open failed)")
                return false
            }
        }
        try {
            if (connection == null || connection!!.isClosed) {
                reconnect()
            }
        } catch (e: Exception) {
            reconnect()
        }
        return connection != null && !connection!!.isClosed
    }

    /** Map a stored type ordinal back to [HazardType], defensively. */
    private fun hazardTypeFromOrdinal(ordinal: Int): HazardType {
        val values = HazardType.values()
        return if (ordinal in values.indices) values[ordinal] else HazardType.UNKNOWN
    }

    /** Map a stored severity level (1..3) back to [Severity], defensively. */
    private fun severityFromLevel(level: Int): Severity =
        when (level) {
            Severity.MINOR.level -> Severity.MINOR
            Severity.MODERATE.level -> Severity.MODERATE
            Severity.SEVERE.level -> Severity.SEVERE
            else -> if (level >= Severity.SEVERE.level) Severity.SEVERE else Severity.MINOR
        }
}
