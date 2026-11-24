package com.overdrive.app.roadsense.detect

import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes/decodes batched IMU frames sent app-sidecar → daemon (D-023).
 *
 * ## Why batching is mandatory
 * The IMU runs at ~100 Hz on TWO sensors (accel + gyro) = ~200 samples/s. The
 * existing GPS sidecar writes one framed JSON line per fix at ~2 Hz; doing that
 * per IMU sample would be ~100× the syscalls and would dominate CPU on the shared
 * Adreno/CPU budget (must not regress camera frames — gate G-5). So the sidecar
 * accumulates ~[TARGET_BATCH_MS] of samples and emits ONE frame.
 *
 * ## Wire format (mirrors the project's newline-delimited JSON IPC to port 19877)
 * One JSON object per line, `command = "IMU_BATCH"`. Samples are packed as flat
 * arrays (not array-of-objects) to keep the payload compact: a 100 ms batch is
 * ~10 accel + ~10 gyro samples.
 *
 * ```
 * {"command":"IMU_BATCH",
 *  "a":[[tMs,ax,ay,az], ...],   // accel samples, m/s²
 *  "g":[[tMs,gx,gy,gz], ...]}   // gyro samples, rad/s
 * ```
 *
 * Each sample carries its own `tMs`, so batching adds at most ~[TARGET_BATCH_MS]
 * of delivery latency but ZERO timing error — downstream timing (GravityFrame,
 * EventDetector, GPS back-projection) all key off the per-sample tMs, not arrival
 * time. ~100 ms latency ≈ 1.7 m at 60 km/h, negligible vs the ≥30 m warning lead.
 *
 * Pure + Android-free (only org.json, which is in the platform) → unit-testable
 * on both sides with no sockets.
 */
object ImuFrameCodec {

    const val COMMAND = "IMU_BATCH"

    /** Sidecar diagnostic line (`{"command":"IMU_STATUS","m":"..."}`), logged by the daemon. */
    const val STATUS_COMMAND = "IMU_STATUS"

    /** Target wall-clock span per batch. ~100 ms ⇒ ~10 samples/sensor at 100 Hz. */
    const val TARGET_BATCH_MS = 100L

    /** Hard cap on samples per sensor per frame (defensive against a stall-then-flush
     *  dumping a huge batch). At 100 Hz, 64 ≈ 640 ms — far above TARGET_BATCH_MS. */
    const val MAX_SAMPLES_PER_FRAME = 64

    private const val KEY_COMMAND = "command"
    private const val KEY_ACCEL = "a"
    private const val KEY_GYRO = "g"
    // Optional accel sample period (ms); only the DiLink 5 sidecar sends it.
    private const val KEY_PERIOD = "p"
    private const val MIN_PERIOD_MS = 2.0
    private const val MAX_PERIOD_MS = 500.0

    /**
     * Encode a batch into a single newline-terminated wire line (the daemon's
     * IPC reader is line-oriented, like the GPS sidecar). Either list may be
     * empty but not both (caller shouldn't send empty frames).
     */
    fun encode(
        accel: List<ImuAccelSample>,
        gyro: List<ImuGyroSample>,
        periodMs: Float? = null,
    ): String {
        val obj = JSONObject()
        obj.put(KEY_COMMAND, COMMAND)
        val a = JSONArray()
        for (s in accel) {
            a.put(JSONArray().put(s.tMs).put(s.ax.toDouble()).put(s.ay.toDouble()).put(s.az.toDouble()))
        }
        val g = JSONArray()
        for (s in gyro) {
            g.put(JSONArray().put(s.tMs).put(s.gx.toDouble()).put(s.gy.toDouble()).put(s.gz.toDouble()))
        }
        obj.put(KEY_ACCEL, a)
        obj.put(KEY_GYRO, g)
        if (periodMs != null) obj.put(KEY_PERIOD, Math.round(periodMs * 10f) / 10.0)
        return obj.toString() + "\n"
    }

    /** Decoded frame: the two sample lists pulled off the wire. */
    data class Decoded(
        val accel: List<ImuAccelSample>,
        val gyro: List<ImuGyroSample>,
        /** Tagged accel sample period, or null for an untagged (100 Hz) stream. */
        val periodMs: Float? = null,
    )

    /**
     * Decode one wire line back into samples. Returns null if [line] is not an
     * IMU_BATCH (the daemon's IPC server multiplexes many command types on the
     * same port, so a non-match just means "not for us"). Malformed entries are
     * skipped defensively rather than throwing — a corrupt sample must not stall
     * the whole stream.
     */
    fun decode(line: String): Decoded? {
        val obj = try { JSONObject(line) } catch (_: Throwable) { return null }
        return decode(obj)
    }

    /**
     * Decode from an ALREADY-PARSED JSONObject. The IPC server parses the wire
     * line once to read the command; feeding that same object here avoids a
     * redundant toString()+re-parse of the (nested-array) batch ~10×/sec on the
     * IPC reader thread that shares the SDM665 with the camera pipeline.
     */
    fun decode(obj: JSONObject): Decoded? {
        if (obj.optString(KEY_COMMAND) != COMMAND) return null

        val accel = ArrayList<ImuAccelSample>()
        obj.optJSONArray(KEY_ACCEL)?.let { arr ->
            // Enforce the per-sensor cap HERE (audit detection #11): the daemon
            // ingest consumes untrusted local-IPC bytes, and the sidecar-side cap in
            // RoadSenseImuSidecarService is no defence against a malformed/oversized
            // or hostile IMU_BATCH line. Clamp the loop (and pre-size the list) so a
            // huge array can't allocate unbounded or run the 100 Hz pipeline
            // thousands of times synchronously on the IPC reader thread.
            val n = minOf(arr.length(), MAX_SAMPLES_PER_FRAME)
            accel.ensureCapacity(n)
            for (i in 0 until n) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 4) continue
                accel.add(
                    ImuAccelSample(
                        tMs = row.optLong(0),
                        ax = row.optDouble(1).toFloat(),
                        ay = row.optDouble(2).toFloat(),
                        az = row.optDouble(3).toFloat(),
                    )
                )
            }
        }
        val gyro = ArrayList<ImuGyroSample>()
        obj.optJSONArray(KEY_GYRO)?.let { arr ->
            val n = minOf(arr.length(), MAX_SAMPLES_PER_FRAME)
            gyro.ensureCapacity(n)
            for (i in 0 until n) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 4) continue
                gyro.add(
                    ImuGyroSample(
                        tMs = row.optLong(0),
                        gx = row.optDouble(1).toFloat(),
                        gy = row.optDouble(2).toFloat(),
                        gz = row.optDouble(3).toFloat(),
                    )
                )
            }
        }
        val period = obj.optDouble(KEY_PERIOD, Double.NaN)
        val periodMs = if (period >= MIN_PERIOD_MS && period <= MAX_PERIOD_MS) period.toFloat() else null
        return Decoded(accel, gyro, periodMs)
    }
}
