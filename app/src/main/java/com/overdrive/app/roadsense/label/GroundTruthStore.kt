package com.overdrive.app.roadsense.label

import com.overdrive.app.logging.DaemonLogger
import com.overdrive.app.roadsense.detect.DetectionCandidate
import com.overdrive.app.roadsense.detect.HazardType
import com.overdrive.app.roadsense.detect.Severity
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import com.overdrive.app.util.ScratchPaths

/**
 * Append-only store of Calibration-Mode ground-truth labels (R-DET-7, D-025).
 *
 * When the user is in Calibration Mode and a detection fires, the overlay shows a
 * confirm card pre-filled with the algorithm's assessment; the user confirms /
 * rejects / corrects severity / corrects type. Each verdict is recorded HERE,
 * paired with the RAW detection features the algorithm saw. That pairing is the
 * labeled dataset that:
 *   - validates the detector against the G-1..G-3 accuracy gates,
 *   - tunes the provisional thresholds (severity buckets, normalization exponent),
 *   - feeds consensus with high weight (a human said so).
 *
 * Daemon-side (UID 2000), H2/JDBC — same engine + lock/recovery conventions as
 * [com.overdrive.app.roadsense.store.RoadSenseStore] and SocHistoryDatabase
 * (D-017). Append-only: we never mutate a label, we just accumulate evidence.
 *
 * Kept deliberately small — it's a research/label sink, not a hot path. Writes
 * happen at most a few times per drive (one per confirmed detection), so a plain
 * synchronized connection is fine; no scheduler/pruning (labels are precious, we
 * keep them all).
 */
class GroundTruthStore private constructor() {

    private val lock = Any()
    @Volatile private var connection: Connection? = null
    @Volatile private var initialized = false

    fun init() {
        synchronized(lock) {
            if (initialized) return
            try {
                Class.forName("org.h2.Driver")
                connection = DriverManager.getConnection(JDBC_URL)
                createTable()
                initialized = true
                logger.info("GroundTruthStore initialized at $DB_PATH")
            } catch (e: Exception) {
                logger.error("GroundTruthStore init failed: " + e.message, e)
            }
        }
    }

    private fun createTable() {
        connection?.createStatement()?.use { st ->
            st.execute(
                "CREATE TABLE IF NOT EXISTS roadsense_labels (" +
                    "id VARCHAR(40) PRIMARY KEY," +
                    "hazard_id VARCHAR(40)," +       // the RoadSenseStore row this labels (may be null)
                    // user verdict
                    "confirmed INT," +               // 1 = real hazard, 0 = rejected (not a hazard)
                    "user_severity INT," +           // user-corrected severity (1..3), null if unchanged
                    "user_type INT," +               // user-corrected type ordinal, null if unchanged
                    // algorithm assessment at detection time (what we predicted)
                    "algo_type INT," +
                    "algo_severity INT," +
                    "algo_confidence DOUBLE," +
                    // RAW detection features (the labeled input the detector saw)
                    "peak_up DOUBLE, peak_down DOUBLE, rise_ms INT, duration_ms INT," +
                    "dip_leading INT, speed_mps DOUBLE, axle_gap_ms INT, lateral_asym DOUBLE," +
                    // gyro-derived pitch/roll features (breaker-vs-pothole; pitch_valid gates them)
                    "peak_pitch_rate DOUBLE, peak_roll_rate DOUBLE, pitch_valid INT," +
                    // auto_accepted=1 ⇒ this "confirmed" label is the G-7 timeout AUTO-accept
                    // (the user did NOTHING — the algo's own guess was recorded), NOT an
                    // explicit human verdict. CRITICAL for honest analysis: an auto row is
                    // CIRCULAR (the algorithm grading itself) and MUST be excluded from any
                    // accuracy metric or weight fit. Only confirmed/rejected/corrected rows
                    // with auto_accepted=0 are trustworthy ground truth.
                    "auto_accepted INT DEFAULT 0," +
                    "lat DOUBLE, lng DOUBLE, created_ms BIGINT);"
            )
        }
        // Migration: CREATE TABLE IF NOT EXISTS won't add columns to a pre-existing
        // table, so add newer features idempotently for older databases.
        // H2 supports ADD COLUMN IF NOT EXISTS; a failure here must not crash init.
        try {
            connection?.createStatement()?.use { st ->
                st.execute("ALTER TABLE roadsense_labels ADD COLUMN IF NOT EXISTS peak_pitch_rate DOUBLE;")
                st.execute("ALTER TABLE roadsense_labels ADD COLUMN IF NOT EXISTS peak_roll_rate DOUBLE;")
                st.execute("ALTER TABLE roadsense_labels ADD COLUMN IF NOT EXISTS pitch_valid INT;")
                st.execute("ALTER TABLE roadsense_labels ADD COLUMN IF NOT EXISTS auto_accepted INT DEFAULT 0;")
            }
        } catch (e: Exception) {
            logger.error("GroundTruthStore column migration failed: " + e.message, e)
        }
    }

    /**
     * Record one ground-truth label. [candidate] is the raw detection; the algo*
     * args are what we assessed; the user* args are the human verdict (corrections
     * null when the user accepted our value). [hazardId] links to the stored row.
     */
    fun record(
        hazardId: String?,
        candidate: DetectionCandidate,
        algoType: HazardType,
        algoSeverity: Severity,
        algoConfidence: Float,
        confirmed: Boolean,
        userSeverity: Severity?,
        userType: HazardType?,
        lat: Double,
        lng: Double,
        nowMs: Long,
        /** true ⇒ the G-7 timeout AUTO-accepted the algo's guess (user did nothing). Such
         *  rows are CIRCULAR (algo grading itself) and must be excluded from accuracy/fit.
         *  false ⇒ an explicit human verdict (confirm/reject/correct) = trustworthy. */
        autoAccepted: Boolean = false,
    ) {
        synchronized(lock) {
            // Lazy open on the FIRST label write: the controller no longer init()s this
            // store at RoadSense start (labels arrive at most a few times per drive, and
            // an open H2 store costs idle CPU — its MVStore background writer/compaction
            // threads run regardless of write volume). init() is idempotent on [lock].
            if (!initialized) init()
            val c = connection ?: return
            try {
                c.prepareStatement(
                    "INSERT INTO roadsense_labels (id, hazard_id, confirmed, user_severity, " +
                        "user_type, algo_type, algo_severity, algo_confidence, peak_up, peak_down, " +
                        "rise_ms, duration_ms, dip_leading, speed_mps, axle_gap_ms, lateral_asym, " +
                        "peak_pitch_rate, peak_roll_rate, pitch_valid, auto_accepted, " +
                        "lat, lng, created_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);"
                ).use { ps ->
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, hazardId)
                    ps.setInt(3, if (confirmed) 1 else 0)
                    if (userSeverity != null) ps.setInt(4, userSeverity.level) else ps.setNull(4, java.sql.Types.INTEGER)
                    if (userType != null) ps.setInt(5, userType.ordinal) else ps.setNull(5, java.sql.Types.INTEGER)
                    ps.setInt(6, algoType.ordinal)
                    ps.setInt(7, algoSeverity.level)
                    ps.setDouble(8, algoConfidence.toDouble())
                    ps.setDouble(9, candidate.peakUp.toDouble())
                    ps.setDouble(10, candidate.peakDown.toDouble())
                    ps.setInt(11, candidate.riseTimeMs)
                    ps.setInt(12, candidate.durationMs)
                    ps.setInt(13, if (candidate.dipLeading) 1 else 0)
                    ps.setDouble(14, candidate.speedMps.toDouble())
                    if (candidate.axlePairGapMs != null) ps.setInt(15, candidate.axlePairGapMs!!) else ps.setNull(15, java.sql.Types.INTEGER)
                    ps.setDouble(16, candidate.lateralAsymmetry.toDouble())
                    ps.setDouble(17, candidate.peakPitchRate.toDouble())
                    ps.setDouble(18, candidate.peakRollRate.toDouble())
                    ps.setInt(19, if (candidate.pitchValid) 1 else 0)
                    ps.setInt(20, if (autoAccepted) 1 else 0)
                    ps.setDouble(21, lat)
                    ps.setDouble(22, lng)
                    ps.setLong(23, nowMs)
                    ps.executeUpdate()
                }
                logger.info("ground-truth label: confirmed=$confirmed auto=$autoAccepted algo=$algoType/$algoSeverity hazard=$hazardId")
            } catch (e: Exception) {
                logger.error("GroundTruthStore.record failed: " + e.message, e)
            }
        }
    }

    /** "Delete local calibrations" also clears the ground-truth labels (R-SET-5). */
    fun deleteAll(): Int {
        synchronized(lock) {
            // Lazy open: the controller no longer init()s this store at boot when RoadSense
            // is disabled, but delete-local must still wipe labels left by a prior enabled
            // session. init() is idempotent and reentrant on [lock].
            val openedHere = !initialized
            if (openedHere) init()
            val c = connection ?: return -1
            return try {
                c.createStatement().use { st -> st.executeUpdate("DELETE FROM roadsense_labels;") }
            } catch (e: Exception) {
                -1
            } finally {
                // If THIS one-off call opened the store, close it again — otherwise a
                // delete-local while RoadSense is disabled would leave the H2 store (and
                // its MVStore background threads) running indefinitely for nothing.
                // When the store was already open (RoadSense session wrote a label), it
                // stays open for the session; the controller's stop() closes it.
                if (openedHere) stop()
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            try { connection?.close() } catch (_: Exception) {}
            connection = null
            initialized = false
        }
    }

    companion object {
        private val logger = DaemonLogger.getInstance("RoadSense/GroundTruth")
        private val DB_PATH = ScratchPaths.path("overdrive_roadsense_labels_h2")
        // AUTO_COMPACT_FILL_RATE=50: idle-CPU tuning shared by all seven H2
        // stores (see SocHistoryDatabase.JDBC_URL for the rationale).
        private val JDBC_URL =
            "jdbc:h2:file:$DB_PATH;FILE_LOCK=SOCKET;TRACE_LEVEL_FILE=0;DB_CLOSE_ON_EXIT=FALSE" +
                ";AUTO_COMPACT_FILL_RATE=50"

        @Volatile private var instance: GroundTruthStore? = null
        @JvmStatic
        fun getInstance(): GroundTruthStore =
            instance ?: synchronized(this) {
                instance ?: GroundTruthStore().also { instance = it }
            }
    }
}
