package com.overdrive.app.roadsense.detect

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import com.overdrive.app.util.ScratchPaths

/**
 * Debug-only raw-IMU recorder (D-036) — the tool that makes RECALL measurable and every
 * PROVISIONAL detector constant fittable against real signal.
 *
 * ## Why this exists
 * RoadSense's detector constants (open threshold + speed slope, minSpeedKmh, rise/dip type
 * votes, severity floors, axle-pair caps, gyro-pitch weights) are first-principles GUESSES.
 * They can only be tuned against ground truth, and — critically — RECALL (genuine breakers
 * that produce NO detection) is *unmeasurable* from the hazard/label stores, because a missed
 * bump leaves no row. The only way to measure it is to capture the RAW 100 Hz inertial stream
 * during a drive, mark where real breakers were, then replay the detector offline and ask "for
 * each marked breaker, did a detection fire?". This recorder is that capture stage; the replay
 * + fit happens off-device (dev/roadsense/replay/). See dev/roadsense 02-STATE / 04-DECISIONS.
 *
 * ## What it records (one tagged CSV, replay-faithful)
 * Per accelerometer sample (row type `A`): raw ax/ay/az AND the derived a_vert + lateral/
 * longitudinal residuals + speed + the vehicle-dynamics + GPS context — i.e. BOTH the exact
 * [VerticalSample] the detector saw (so replay reproduces detection bit-for-bit) AND the raw
 * vectors (so a fit can re-derive with a different GravityFrame alpha / projection). Per gyro
 * sample (row type `G`): raw gx/gy/gz (so pitch/roll can be re-derived). Per user mark (row
 * type `M`): a timestamped ground-truth label at the current pose. Rows are written in arrival
 * order, so the replay re-merges by tMs exactly like [DaemonImuStream] does.
 *
 * ## Hot-path safety (R-PERF-1)
 * [recordAccel]/[recordGyro] run on the 100 Hz IPC/sensor thread. They build one CSV line and
 * `offer()` it to a bounded queue — lock-free, non-blocking. A dedicated background "roadsense-
 * rec" thread drains the queue to a [BufferedWriter] and flushes periodically. If the queue is
 * full (disk can't keep up), the line is DROPPED and counted — the recorder NEVER blocks the
 * detection path (a dropped sample only blemishes the recording, never the live drive). Same
 * posture as the sync executor + detached-daemon pattern.
 *
 * Not a production feature. DOUBLE-gated: (1) `roadSense.rawRecord` config flag (default OFF),
 * and (2) the controller only consults that flag inside a `BuildConfig.DEBUG` branch, so in a
 * RELEASE build R8 dead-strips the entire start/capture path — the shipped binary cannot record
 * even if the config key is flipped. Owned by the controller (one instance, lifecycle = the
 * flag). Clock injected for testability.
 */
class RawImuRecorder(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var writerThread: Thread? = null
    @Volatile private var running = false
    private var queue: ArrayBlockingQueue<String>? = null
    @Volatile private var filePath: String? = null

    // Diagnostics (read on the controller's tick thread for the heartbeat; written on the
    // hot path / writer thread → volatile for cross-thread visibility).
    @Volatile var rowsQueued = 0L; private set
    @Volatile var rowsWritten = 0L; private set
    @Volatile var rowsDropped = 0L; private set
    // Bytes written this recording — drives the hard size cap (writer thread only + a
    // volatile read in the cap check). @Volatile var capped: latched true when the size cap
    // auto-stopped us, so the hot path stops offering and the controller can log why.
    @Volatile private var bytesWritten = 0L
    @Volatile var capped = false; private set

    val isRunning: Boolean get() = running
    val path: String? get() = filePath

    /**
     * Start a fresh recording. Idempotent if already running (returns the existing path).
     * The file is named with the start wall-clock so successive drives don't clobber each
     * other. Best-effort: a file-open failure logs and leaves the recorder stopped (the live
     * drive is unaffected).
     */
    @Synchronized
    fun start(dir: String = DEFAULT_DIR): String? {
        if (running) return filePath
        // STORAGE GUARDRAILS (don't bloat /data/local/tmp): (1) prune old recordings first,
        // (2) refuse to start if free space is below the floor — a debug capture must never
        // fill the partition the daemon / SD-mount / config all live on.
        pruneOldRecordings(dir)
        val dirFile = File(dir)
        val freeMb = try { dirFile.usableSpace / (1024 * 1024) } catch (_: Throwable) { -1L }
        if (freeMb in 0 until MIN_FREE_MB) {
            Log.w(TAG, "recorder NOT started: only ${freeMb}MB free (< ${MIN_FREE_MB}MB floor)")
            return null
        }
        val startMs = clock()
        val p = "$dir/roadsense_rec_$startMs.csv"
        val q = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
        val w: BufferedWriter = try {
            BufferedWriter(FileWriter(p, false), BUFFER_BYTES)
        } catch (t: Throwable) {
            Log.w(TAG, "recorder open failed [$p]: ${t.message}")
            return null
        }
        // Header documents the schema for the offline replay/fit harness.
        try { w.write(HEADER); w.write("\n") } catch (_: Throwable) {}
        queue = q
        filePath = p
        rowsQueued = 0L; rowsWritten = 0L; rowsDropped = 0L; bytesWritten = 0L; capped = false
        running = true
        val t = Thread({ drain(q, w) }, "roadsense-rec").apply { isDaemon = true }
        writerThread = t
        t.start()
        Log.i(TAG, "raw IMU recorder started → $p")
        return p
    }

    /** Stop recording: signal the drainer, flush + close the file, join briefly. Safe to call
     *  when not running. The drainer flushes whatever is queued before closing. */
    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        // Poison the queue so the drainer wakes immediately and exits after flushing.
        queue?.offer(POISON)
        writerThread?.let { try { it.join(2_000) } catch (_: InterruptedException) {} }
        writerThread = null
        queue = null
        Log.i(TAG, "raw IMU recorder stopped: queued=$rowsQueued written=$rowsWritten dropped=$rowsDropped → $filePath")
    }

    /**
     * Record one accelerometer sample + its full derived/context snapshot (row type `A`).
     * Hot path: builds a line and non-blocking offers it; drops + counts on a full queue.
     * `dyn` may be null (daemon still booting) — emitted as blank dynamics columns.
     */
    fun recordAccel(
        tMs: Long, ax: Float, ay: Float, az: Float,
        aVert: Float, latRes: Float, lonRes: Float, speedKmh: Float,
        dyn: VehicleDynamics?, pose: Pose?, inEvent: Boolean,
    ) {
        if (!running) return
        val sb = StringBuilder(160)
        sb.append('A').append(',').append(tMs).append(',')
            .append(ax).append(',').append(ay).append(',').append(az).append(',')
            .append(aVert).append(',').append(latRes).append(',').append(lonRes).append(',')
            .append(speedKmh).append(',')
        if (dyn != null) {
            sb.append(dyn.brakePercent).append(',').append(dyn.accelPercent).append(',')
                .append(dyn.steeringAngleDeg).append(',').append(dyn.gearMode).append(',')
                .append(dyn.brakeAgeMs).append(',')
        } else sb.append(",,,,,")
        if (pose != null) {
            sb.append(pose.lat).append(',').append(pose.lng).append(',')
                .append(pose.bearingDeg).append(',').append(pose.accuracyM).append(',')
        } else sb.append(",,,,")
        sb.append(if (inEvent) 1 else 0)
        sb.append(",,,,")           // gx,gy,gz,label (blank on A rows)
        offer(sb.toString())
    }

    /** Record one gyroscope sample (row type `G`): raw gx/gy/gz only (the rest blank). */
    fun recordGyro(tMs: Long, gx: Float, gy: Float, gz: Float) {
        if (!running) return
        // A,tMs,ax,ay,az,aVert,latRes,lonRes,speedKmh,brake,accel,steer,gear,brakeAge,lat,lng,brg,acc,inEvent,gx,gy,gz,label
        val sb = StringBuilder(96)
        sb.append('G').append(',').append(tMs)
            .append(",,,,,,,,,,,,,,,,,,")  // 18 empty cols up to gx
            .append(',').append(gx).append(',').append(gy).append(',').append(gz).append(',')
        offer(sb.toString())
    }

    /**
     * Record a user GROUND-TRUTH mark at the current pose (row type `M`) — "a real hazard was
     * HERE", whether or not the detector fired. This is the recall anchor: the replay matches
     * each mark against detections within a tolerance. [label] is a free tag (e.g. "breaker",
     * "pothole", "severe"). Emitted with the latest pose so it localizes on the same map.
     */
    fun mark(label: String, pose: Pose?, speedKmh: Float) {
        if (!running) return
        val safe = label.replace(',', ' ').replace('\n', ' ')
        val sb = StringBuilder(96)
        sb.append('M').append(',').append(clock())
            .append(",,,,,,,")                 // ax..lonRes blank
            .append(speedKmh).append(',')
            .append(",,,,,")                   // dynamics blank
        if (pose != null) sb.append(pose.lat).append(',').append(pose.lng).append(',').append(pose.bearingDeg).append(',').append(pose.accuracyM).append(',')
        else sb.append(",,,,")
        sb.append(",,,,").append(safe)         // inEvent,gx,gy,gz blank then label
        offer(sb.toString())
        Log.i(TAG, "ground-truth MARK '$safe' @${pose?.lat},${pose?.lng}")
    }

    private fun offer(line: String) {
        if (capped) return                  // hard size cap reached → stop accepting rows
        rowsQueued++
        val q = queue ?: return
        if (!q.offer(line)) rowsDropped++   // disk can't keep up → drop, never block the hot path
    }

    /** Background drainer: blocking-take lines, write, flush periodically; exit on POISON. */
    private fun drain(q: ArrayBlockingQueue<String>, w: BufferedWriter) {
        var sinceFlush = 0
        try {
            while (true) {
                val line = q.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                if (line === POISON) break
                if (line != null) {
                    w.write(line); w.write("\n")
                    rowsWritten++
                    bytesWritten += line.length + 1
                    if (++sinceFlush >= FLUSH_ROWS) { w.flush(); sinceFlush = 0 }
                    // Hard size cap: a debug capture must not grow without bound (~150 B/row ×
                    // 200 rows/s ≈ 108 MB/h). Latch capped so offer() stops feeding, flush,
                    // and exit — the controller sees `capped` and logs it; the user re-arms
                    // for a fresh file if they need more. Bounds disk per recording.
                    if (bytesWritten >= MAX_FILE_BYTES) {
                        capped = true
                        Log.w(TAG, "recording hit ${MAX_FILE_BYTES / (1024 * 1024)}MB cap → auto-stopping ($rowsWritten rows)")
                        break
                    }
                } else {
                    // Idle timeout → flush whatever's buffered so a paused drive isn't lost.
                    if (sinceFlush > 0) { w.flush(); sinceFlush = 0 }
                    if (!running) break
                }
            }
            // Drain any remainder after POISON / stop.
            while (true) {
                val line = q.poll() ?: break
                if (line === POISON) continue
                w.write(line); w.write("\n"); rowsWritten++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "recorder drain error: ${t.message}")
        } finally {
            try { w.flush() } catch (_: Throwable) {}
            try { w.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Keep only the newest [MAX_RECORDINGS] recordings in [dir]; delete older ones so a
     * habit of leaving rawRecord on across many drives can't slowly fill the partition.
     * Best-effort (a delete failure just leaves that file). Runs once per start(), off the
     * hot path.
     */
    private fun pruneOldRecordings(dir: String) {
        try {
            val files = File(dir).listFiles { f ->
                f.isFile && f.name.startsWith("roadsense_rec_") && f.name.endsWith(".csv")
            } ?: return
            if (files.size < MAX_RECORDINGS) return
            files.sortByDescending { it.lastModified() }
            for (i in (MAX_RECORDINGS - 1) until files.size) {
                if (files[i].delete()) Log.i(TAG, "pruned old recording ${files[i].name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "prune failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "RoadSense/RawRec"
        /** Daemon (uid 2000) writes here — same dir as cam_daemon.log + the H2 stores. */
        val DEFAULT_DIR = ScratchPaths.getDir()
        /** Hard per-recording size cap (~30 min of 200 Hz accel+gyro at ~150 B/row). Auto-
         *  stops at this size so one recording can't bloat /data/local/tmp. */
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024   // 64 MB
        /** Refuse to start if usable space is below this floor — never fill the partition the
         *  daemon, SD-mount, config + H2 stores share. */
        private const val MIN_FREE_MB = 256L
        /** Keep at most this many recordings; older ones are pruned on the next start(). */
        private const val MAX_RECORDINGS = 5
        /** Bounded backlog. ~8 k rows ≈ ~40 s of 200 Hz (accel+gyro) headroom if the writer
         *  stalls; beyond that we drop rather than grow unbounded or block the hot path. */
        private const val QUEUE_CAPACITY = 8192
        private const val BUFFER_BYTES = 64 * 1024
        /** Flush cadence: by row count (steady streaming) or idle timeout (paused drive). */
        private const val FLUSH_ROWS = 512
        private const val FLUSH_INTERVAL_MS = 1_000L
        private val POISON = "__POISON__"
        /** CSV schema — keep in lockstep with the offline replay harness
         *  (dev/roadsense/replay/replay.py). type: A=accel, G=gyro, M=mark. */
        const val HEADER =
            "type,tMs,ax,ay,az,aVert,latRes,lonRes,speedKmh,brake,accel,steer,gear,brakeAgeMs,lat,lng,bearing,accM,inEvent,gx,gy,gz,label"
    }
}
