package com.overdrive.app.roadsense

import android.content.Context
import android.util.Log
import org.json.JSONObject
import com.overdrive.app.roadsense.detect.Assessment
import com.overdrive.app.roadsense.detect.DaemonImuStream
import com.overdrive.app.roadsense.detect.DetectionCandidate
import com.overdrive.app.roadsense.detect.EventDetector
import com.overdrive.app.roadsense.detect.GravityFrame
import com.overdrive.app.roadsense.detect.HazardClassifier
import com.overdrive.app.roadsense.detect.PitchSignCalibrator
import com.overdrive.app.roadsense.detect.GpsRingBuffer
import com.overdrive.app.roadsense.detect.GyroStats
import com.overdrive.app.roadsense.detect.HazardType
import com.overdrive.app.roadsense.detect.ImuAccelSample
import com.overdrive.app.roadsense.detect.ImuGyroSample
import com.overdrive.app.roadsense.detect.ImuStream
import com.overdrive.app.roadsense.detect.RawImuRecorder
import com.overdrive.app.roadsense.detect.RejectionFilter
import com.overdrive.app.roadsense.detect.RoadSenseHazard
import com.overdrive.app.roadsense.detect.SeverityClassifier
import com.overdrive.app.roadsense.detect.VehicleCalibrator
import com.overdrive.app.roadsense.detect.VehicleDynamics
import com.overdrive.app.roadsense.detect.VerticalSample
import com.overdrive.app.roadsense.detect.ImuFrameCodec
import com.overdrive.app.roadsense.config.RoadSenseConfig
import com.overdrive.app.roadsense.label.GroundTruthStore
import com.overdrive.app.roadsense.sidecar.RoadSenseImuSidecarService
import com.overdrive.app.roadsense.source.LocationSource
import com.overdrive.app.roadsense.source.VehicleDataSource
import com.overdrive.app.roadsense.source.VehicleStateGate
import com.overdrive.app.roadsense.store.RoadSenseStore
import com.overdrive.app.roadsense.store.SpatialIndex
import com.overdrive.app.roadsense.sync.CloudflareEdgeSyncProvider
import com.overdrive.app.roadsense.sync.DeviceId
import com.overdrive.app.roadsense.sync.RoadSenseSyncProvider
import com.overdrive.app.roadsense.sync.TileCursor
import com.overdrive.app.monitor.AccMonitor
import com.overdrive.app.roadsense.warn.OverlayState
import kotlin.math.abs

/**
 * Daemon-side brain of RoadSense (D-019/D-023). Wires the whole pipeline and owns
 * the vehicle-state lifecycle. Self-contained: nothing in CameraDaemon /
 * SurveillanceIpcServer references this yet — those two integration points are
 * DEFERRED tiny patches (see dev/roadsense/06-DAEMON-PATCHES.md) because another
 * session is editing those files. To go live, the daemon calls:
 *   - [start] once at boot (after BydDataCollector/GpsMonitor are up),
 *   - [onImuBatch] from the IPC server's `IMU_BATCH` case,
 *   - [onVehicleStatePoll] periodically (or it self-polls — see [start]),
 *   - [stop] at shutdown.
 *
 * ## Data flow (all on the IPC reader thread, single-threaded per the engine
 *    contract — see DaemonImuStream):
 *
 *   IMU_BATCH ─► DaemonImuStream.feed ─► [onAccel]/[onGyro]
 *     onAccel: GravityFrame.update → a_vert → VehicleCalibrator.onSample
 *                                          └► EventDetector.onSample → candidate?
 *       candidate → RejectionFilter.evaluate(dynamics, gyroStats, eventRate)
 *         not rejected → SeverityClassifier.classify(vehicleScale, maturity, gpsAcc)
 *           → back-project pose (GpsRingBuffer) → RoadSenseStore.upsertDetection
 *     onGyro: fold into rolling GyroStats (peak yaw/roll over the event window)
 *
 * ## Vehicle-state gating (D-021)
 * [onVehicleStatePoll] reads gear (BydDataCollector via VehicleDataSource) + ACC
 * (AccMonitor) through [VehicleStateGate] and drives the app IMU sidecar:
 *   DRIVING → start sidecar FAST + run detector
 *   RELAXED → sidecar SLOW + pause detector (keep state warm)
 *   OFF     → stop sidecar + release detector state (silent, ~zero cost)
 *
 * Not a singleton on purpose — the daemon owns exactly one instance and its
 * lifecycle. Clock injected for testability.
 */
class RoadSenseController @JvmOverloads constructor(
    private val appContext: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val vehicleSource: VehicleDataSource = VehicleDataSource(),
    private val locationSource: LocationSource = LocationSource(),
    private val store: RoadSenseStore = RoadSenseStore.getInstance(),
) : ImuStream.Listener {

    // Persistent diagnostics: RoadSense runs in the daemon, but most of its logs go
    // via android.util.Log (logcat) which is VOLATILE — gone after a no-ADB drive.
    // For the few breadcrumbs we actually need to verify a drive post-hoc (regime
    // transitions, IMU-flow heartbeat, detections), ALSO write to DaemonLogger, which
    // persists to /data/local/tmp/cam_daemon.log and survives park→reconnect.
    private val plog = com.overdrive.app.logging.DaemonLogger.getInstance("RoadSense")

    // ── Pipeline stages (single-threaded; one feeder per DaemonImuStream) ──────
    private val gravity = GravityFrame()
    private val detector = EventDetector()
    // Last-applied corner-yaw config, to detect a change cheaply without rebuilding
    // every poll. Declared BEFORE [rejection] so buildRejectionFilter's write to it
    // in the initializer below isn't clobbered by a later default initializer.
    private var lastYawCfg: YawCfg? = null
    // Last detection-sensitivity multiplier pushed into the (stateful) detector, so the
    // ~2 Hz poll re-pushes ONLY when the user's slider actually changed — not every tick.
    // NaN = nothing applied yet (forces the first push to seed the detector from config).
    private var lastDetectionSensitivity: Float = Float.NaN
    // RejectionFilter is stateless but carries the speed-adaptive cornering-yaw
    // tunables as constructor params; rebuilt by [maybeRebuildRejection] when those
    // config knobs change (rare — a settings edit). @Volatile so the IMU thread's
    // handleCandidate read sees a rebuild done on the vehicle-poll thread.
    @Volatile private var rejection = buildRejectionFilter(RoadSenseConfig.snapshot())
    private val classifier = SeverityClassifier()
    private val calibrator = VehicleCalibrator()
    private val gpsBuffer = GpsRingBuffer()
    private val imuStream = DaemonImuStream()
    // SOTA temporal-fusion classifier + its pitch-sign self-calibrator (D-038/D-040). This is
    // the PRIMARY accept/type gate: finalizeClassification runs classify() on the completed
    // ±window and its `accept` decides whether the hazard is stored, its `type` is authoritative
    // (SeverityClassifier supplies only severity/confidence). The rolling ~20 Hz context ring
    // (speed/brake/accel/pitch around the event) feeds the crossing envelope. (Weights/threshold
    // are still PROVISIONAL physics-seeded priors — to be fit on the trustworthy label set.)
    private val hazardClassifier = HazardClassifier()
    private val pitchSign = PitchSignCalibrator()
    private val contextRing = ArrayDeque<HazardClassifier.ContextSample>()
    private var lastContextSampleMs = 0L
    private var lastCtxSpeedKmh = Float.NaN   // for the sign-calibrator's "speed dropping" flag
    // DEFERRED-decision queue (D-040): the HazardClassifier is now the PRIMARY accept/type
    // gate, but its crossing envelope needs the POST-event half (recover-after) which hasn't
    // elapsed at candidate completion. So we don't decide at completion — we ENQUEUE the
    // candidate + its rejection/severity context, and drain it ~CLASSIFY_DEFER_MS later (on the
    // same single IPC thread, from onImuBatch), when the context ring holds the full ±window.
    // Bounded; all access is on the IPC thread so no locking needed.
    private val pendingClassify = ArrayDeque<PendingClassify>()

    private data class PendingClassify(
        val candidate: DetectionCandidate,
        val dyn: VehicleDynamics?,
        val enqueuedTMs: Long,     // sensor tMs of the event peak (envelope split point)
        val enqueuedWall: Long,    // wall-clock at enqueue (for the store write clock)
    )
    // Latest SIGNED pitch rate + validity from the most recent gyro sample (onGyro), read by
    // the onAccel context sampler (gyro & accel interleave on the same IPC thread, so this is
    // a plain single-threaded hand-off — no volatile needed).
    private var latestPitchRate = 0f
    private var latestPitchValid = false
    // Debug raw-IMU recorder (D-036): captures raw 100 Hz accel+gyro + derived features to a
    // size-capped CSV when roadSense.rawRecord is on, so RECALL (missed breakers) can be
    // measured offline and the PROVISIONAL constants re-fit. Default-off; storage-bounded
    // (see RawImuRecorder caps). @Volatile rawRecord: written on the tick thread
    // (onWarningTick toggle), read on the IPC hot path (onAccel/onGyro).
    private val rawRecorder = RawImuRecorder(clock = clock)
    @Volatile private var rawRecord = false
    // Last consumed ground-truth-mark timestamp (roadSense.rawMark), so a single adb/web poke
    // records exactly one M row. Tick thread only.
    private var lastRawMarkTs = 0L
    // Route coverage: which tiles we've driven (distinct from vehicle calibration).
    // Updated from the live pose; supplies the overlay's "new road vs mapped" signal.
    private val coverage = com.overdrive.app.roadsense.store.RouteCoverage()
    // Last pose we published a coverage level for (overlay supplier reads this so the
    // level reflects WHERE WE ARE, not a stale tile). @Volatile: written on the warn
    // tick, read by the visualSink supplier lambda on the same tick — kept simple.
    @Volatile private var lastCoverageLevel = 0
    // Daemon-side visual sink publishes overlay state to UCM (D-024); calibration
    // level for the green/orange/red dot comes from the calibrator's maturity, and
    // route coverage (new/seen/mapped) from RouteCoverage.
    private val visualSink = com.overdrive.app.roadsense.warn.UcmVisualSink(
        calibrationLevelSupplier = { calibrator.maturity },
        coverageSupplier = { lastCoverageLevel },
        clock = clock,
    )
    // Audio channel the chime plays on (default navigation = the OEM guidance stream).
    // @Volatile: written on the tick/regime-poll threads from the config snapshot, read by
    // the cue's supplier when a chime fires — so a settings change applies live and the
    // chime never hits disk itself.
    @Volatile private var warnAudioChannel = "navigation"
    @Volatile private var warnAudioVolume =
        com.overdrive.app.roadsense.config.RoadSenseChimeLevels.DEFAULT_MASTER_PERCENT
    private val warnings = com.overdrive.app.roadsense.warn.WarningCoordinator(
        store, visualSink = visualSink, clock = clock,
        // Chimes go out through the app-process player so they can ride the OEM-extended
        // nav stream; a daemon-side SoundPool/ToneGenerator can only reach usage-routed or
        // public streams. See BridgedAudioCue.
        audio = com.overdrive.app.roadsense.warn.BridgedAudioCue(
            channelSupplier = { warnAudioChannel },
            volumeSupplier = { warnAudioVolume },
        ),
    )

    // Rolling gyro peaks over the current event window, reset when an event closes.
    // Each peak carries the sensor tMs of the sample that set it so it can be AGED
    // OUT once it falls outside ~one EventDetector window (audit detection #1): the
    // since-last-candidate max held a turn made seconds/minutes before a later
    // straight-line bump, making RejectionFilter wrongly reject it as cornering.
    // Only rotation concurrent with the jolt should vote "cornering".
    private var peakYaw = 0f
    private var peakYawTMs = 0L
    // AXIS-RESOLVED pitch/roll peaks for the classifier's breaker-vs-pothole vote
    // (the user's "up-down gyro movement" signal). peakPitch is the body's nose-up/
    // down rotation about the lateral axis (strong on a transverse breaker);
    // peakTrueRoll is the fore-aft-axis roll (strong on a one-sided pothole).
    // pitchEverValid records whether GravityFrame could isolate the axes for ≥1 gyro
    // sample in the event window (longitudinal axis established).
    private var peakPitch = 0f
    private var peakPitchTMs = 0L
    private var peakTrueRoll = 0f
    private var peakTrueRollTMs = 0L
    private var pitchEverValid = false
    // SIGNED pitch-couplet extrema, peak-held at 100 Hz over the event window (couplet-decimation
    // fix): the strongest +nose-up and −nose-down signed-pitch lobes and their sensor times. The
    // HazardClassifier couplet needs FULL gyro resolution — point-sampling the 16 Hz context ring
    // aliases a fast breaker's ~150 ms lobes. Peak-held here (like peakPitch), carried on the
    // candidate, so the couplet sees true extrema. Aged out per GYRO_PEAK_WINDOW_MS like the rest.
    private var peakPitchUp = 0f;   private var peakPitchUpTMs = 0L
    private var peakPitchDown = 0f; private var peakPitchDownTMs = 0L

    // Persistent IMU-flow heartbeat state (diagnostics; IPC thread only).
    private var imuBatchCount = 0L
    private var lastImuHeartbeatMs = 0L
    private var lastSeenSpeedKmh = 0f

    // Calibration-persistence throttle (daemon tick thread only).
    private var lastCalPersistMs = 0L
    private var lastPersistedQuietCount = 0L
    // Candidate-disposition tallies (diagnostics; IPC thread). Surfaced in the
    // imu-flow heartbeat so a no-ADB drive shows WHY candidates didn't store.
    private var dropNoDyn = 0
    private var dropNoPose = 0
    private var dropStored = 0
    private val dropRejects = HashMap<String, Int>()
    // Coverage-persistence throttle (daemon tick thread only).
    private var lastCoveragePersistMs = 0L
    // Sidecar stall-recovery relaunch throttle (daemon tick thread only) — don't spam
    // `am start` while a just-relaunched sidecar is still coming up.
    private var lastSidecarRelaunchMs = 0L

    // Rolling candidate rate for washboard rejection (events in the last window).
    private val recentCandidateMs = ArrayDeque<Long>()
    // D-032: throttle for mapping a sustained washboard stretch as ONE rough_section
    // row rather than spamming a row per cobble. Last rough-section map time (event
    // tMs) on the single IPC/sensor thread.
    private var lastRoughSectionMs = 0L

    @Volatile private var regime = VehicleStateGate.Regime.OFF
    @Volatile private var started = false
    // True once attach() has wired the config listener (boot). Distinct from [started]
    // (which tracks whether the heavy machinery is running): attached can be true while
    // started is false — that's the DISABLED steady state (listener armed, ~zero cost).
    @Volatile private var attached = false
    // Daemon-side config listener that starts/stops the controller live when the user
    // flips roadSense.enabled, so a DISABLED feature pays nothing (no ticker, no stores,
    // no sync executor) yet enabling it takes effect WITHOUT a daemon restart. Held as a
    // field so detach() can deregister it. Runs on the UnifiedConfigManager notify thread.
    private var enabledListener: com.overdrive.app.config.UnifiedConfigManager.ConfigChangeListener? = null
    // Last `enabled` value the listener acted on, so it reconciles ONLY when the flag
    // actually flips — NOT on the controller's own frequent roadSense self-writes
    // (calQuietCount every 60 s, lastSyncMs/lastUploadCursor per sync). Without this
    // gate, each self-write would spawn a toggle thread + forceReload() (a full-config
    // disk re-read that also invalidates the shared cache the recording pipeline reads)
    // for a guaranteed no-op. Written only inside the listener / reconcile (serialized).
    @Volatile private var lastKnownEnabled: Boolean? = null
    // Serializes attach/detach/reconcile (config-notify thread) against each other and the
    // daemon boot/shutdown threads, so a toggle storm can't race start() with stop().
    private val lifecycleLock = Any()
    private var ticker: java.util.concurrent.ScheduledExecutorService? = null
    // Set by onVehicleStatePoll (daemon housekeeping thread) when leaving DRIVING;
    // the actual pipeline-state reset is performed on the IPC/sensor thread at the
    // top of the next batch, so ALL detector/gravity/gps state is mutated by ONE
    // thread only. Avoids a data race without locking the 100 Hz hot path.
    @Volatile private var resetRequested = false
    // Set by onVehicleStatePoll (tick thread) when leaving DRIVING; consumed at the TOP of the
    // next onImuBatch (IPC thread, before the regime early-return) to FORCE-finalize any deferred
    // candidates before resetTransient clears them (RS-DEFER-1). Flag-only hand-off like
    // resetRequested — the tick thread never touches pipeline state directly.
    @Volatile private var flushPendingRequested = false
    private var lastGpsPollMs = 0L
    // Cached vehicle snapshot, refreshed on a throttle (the source only updates
    // every ~5 s; re-fetching at 100 Hz would allocate needlessly). Read on the
    // single IPC/sensor thread only.
    private var lastVehiclePollMs = 0L
    private var cachedDynamics: VehicleDynamics? = null
    // Master feature toggle (UCM roadSense.enabled). @Volatile: WRITTEN by both the
    // tick thread (onVehicleStatePoll) and the IPC thread (onAccel throttle), READ
    // on the IPC thread (handleCandidate). Without volatile the IPC thread could
    // cache a stale value and never observe a master-disable (review blocker #2).
    @Volatile private var featureEnabled = false
    // @Volatile: WRITTEN by the tick thread (onWarningTick), READ by the IPC thread
    // (handleCandidate). A stale read would silently never start surfacing confirm
    // cards after the user enables Calibration Mode (review blocker #2).
    @Volatile private var calibrationMode = false

    // Calibration-Mode ground-truth (D-025, R-DET-7) + the pending confirm awaiting
    // the user's verdict. @Volatile: WRITTEN by the IPC thread (handleCandidate sets
    // it) AND the tick thread (consumePendingConfirmResult clears it); single-ref
    // assignment, one clearer — volatile gives the needed visibility without a lock
    // on the 100 Hz path (review blocker #1).
    private val groundTruth = GroundTruthStore.getInstance()
    @Volatile private var pendingConfirm: PendingLabel? = null
    private var lastConfirmResultTs = 0L

    // Crowdsource sync (Phase 8, D-009). Worker URL comes from config; both
    // directions are opt-in (default OFF, R-CRD-6). Sync runs on a slow tick.
    private val syncProvider: RoadSenseSyncProvider = CloudflareEdgeSyncProvider(
        workerUrlSupplier = { RoadSenseConfig.snapshot().syncWorkerUrl }
    )
    private val tileCursor = TileCursor()
    // Last successful sync wall-clock, PERSISTED in UCM (roadSense.lastSyncMs) so a
    // daemon restart / new ACC session doesn't force an immediate re-sync — the
    // 2.5 h cadence survives reboots. -1 = not yet loaded.
    // @Volatile: the actual sync now runs OFF the tick thread on [syncExecutor] (so a
    // blocking download/upload can't stall the warn tick / overlay publish — see
    // maybeSync). The tick thread only READS lastSyncMs for the cheap "is it due?"
    // gate; it is WRITTEN solely on syncExecutor (single-threaded). Volatile gives the
    // tick thread visibility of the latest write without a lock.
    @Volatile private var lastSyncMs = -1L
    // Last uploaded row's updated_ms, PERSISTED in UCM (roadSense.lastUploadCursor)
    // so a daemon restart / ACC cycle doesn't re-scan and re-upload every eligible
    // local row from 0 (wasted bandwidth) and — worse — re-contribute this device's
    // own backlog under a NEW rotating deviceId after the 30-day id rotation,
    // self-inflating distinct-device consensus (audit network #5). -1 = not loaded.
    // Read AND written ONLY on syncExecutor (never the tick thread), so no volatile
    // needed — single-threaded access within the sync body, same as tileCursor.
    private var lastUploadCursor = -1L
    // Dedicated single-thread executor for the BLOCKING crowdsource sync (download/
    // upload are OkHttp calls with 10 s connect / 15 s read timeouts — see
    // CloudflareEdgeSyncProvider). It MUST NOT run on the warn-tick thread: that thread
    // is the same one that drives the regime poll AND publishes the overlay state every
    // tick, and scheduleWithFixedDelay won't fire the next tick until the current one
    // returns. A timing-out sync (~30 s) on that thread starves the overlay publish for
    // ~30 s, the app's 4 s staleness window trips, and the overlay falls back to "Idle"
    // — the exact production symptom. Created in start(), shutdownNow()+nulled in stop().
    private var syncExecutor: java.util.concurrent.ExecutorService? = null
    // True while a sync is submitted-but-not-yet-finished on syncExecutor. The tick
    // thread checks this in the due-gate and SKIPS submitting another sync if one is
    // already in flight, so a string of ticks during a slow/timing-out sync can't queue
    // up a backlog of concurrent syncs. @Volatile: set by the tick thread on submit,
    // cleared by syncExecutor in a finally when the task completes.
    @Volatile private var syncInFlight = false
    // Earliest wall-clock at which the NEXT sync attempt may run. 0 = no backoff in
    // effect (the normal 2.5 h SYNC_MS cadence on lastSyncMs governs). Set to
    // now + SYNC_RETRY_BACKOFF_MS by syncExecutor when an attempt completes with NO
    // success, so a persistently-unreachable worker is retried every ~5 min rather than
    // every tick (which, combined with the old "lastSyncMs only advances on success"
    // design, was a permanent block-retry storm). Cleared (set 0) on success — success
    // falls back to the existing 2.5 h lastSyncMs cadence. @Volatile: READ in the tick
    // due-gate, WRITTEN on syncExecutor.
    @Volatile private var nextSyncAttemptMs = 0L

    private data class PendingLabel(
        val hazardId: String,
        val candidate: DetectionCandidate,
        val assessment: Assessment,
        val lat: Double,
        val lng: Double,
        /** Wall-clock ms the confirm was surfaced — drives the G-7 auto-accept
         *  timeout so the card never demands interaction while driving. */
        val shownMs: Long,
    )

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Wire the controller into the daemon WITHOUT paying for a disabled feature.
     *
     * Called once at daemon boot (CameraDaemon) instead of [start]. It:
     *   1. start()s the heavy machinery (stores, 2 Hz ticker, sync executor) ONLY if
     *      roadSense.enabled is currently true, and
     *   2. registers a config-change listener so flipping the toggle later starts/stops
     *      the controller LIVE — no daemon restart.
     *
     * The controller object itself is always constructed (so the IPC IMU_BATCH case and
     * the map API's getRoadSense() are non-null), but a DISABLED feature now costs ~zero:
     * no ticker thread, no open H2 connections, no sync executor — only this one armed
     * listener + the resident (lazy, side-effect-free) field graph. Idempotent.
     */
    fun attach() {
        synchronized(lifecycleLock) {
            if (!attached) {
                attached = true
                // React to roadSense.enabled flips. The web settings page writes the
                // `roadSense` section through UnifiedConfigManager.updateSection (in this
                // same daemon process), which fires a section-specific notifyListeners
                // synchronously — so this is the authoritative live-toggle hook. We match
                // ONLY "roadSense" (NOT "all"): saveConfig fires notifyListeners("all") on
                // EVERY config write app-wide, and updateSection("roadSense", …) ALSO fires
                // the section-specific "roadSense" event — so matching the section alone
                // catches every enable/disable without reconciling on unrelated writes.
                // Same idiom as GpuSurveillancePipeline.rectifyConfigListener (matches only
                // "recording"). ConfigChangeListener is a plain Kotlin interface (not a fun
                // interface), so an object literal — not a SAM lambda — is required.
                val l = object : com.overdrive.app.config.UnifiedConfigManager.ConfigChangeListener {
                    override fun onConfigChanged(section: String, config: org.json.JSONObject) {
                        if (section != "roadSense") return
                        // Gate on the `enabled` key ACTUALLY flipping. `config` is the merged
                        // roadSense section (updateSection passes the post-merge object), so we
                        // can read the flag here cheaply — NO forceReload — and ignore our own
                        // calibration/sync self-writes that don't touch `enabled`. Skipping them
                        // avoids a per-60 s thread-spawn + full-config disk re-read for a no-op.
                        val now = config.optBoolean("enabled", false)
                        if (lastKnownEnabled == now) return
                        lastKnownEnabled = now
                        // CRITICAL: this callback runs INSIDE UnifiedConfigManager's
                        // synchronized(this) monitor (notifyListeners is called under it).
                        // reconcile()'s stop() can take ~2 s (awaitTermination + H2 close +
                        // sidecar exec); running it here would hold the global config monitor
                        // that long and stall EVERY other config read in the daemon (incl. the
                        // recording pipeline). So hop onto a short-lived daemon thread — a real
                        // enable/disable flip is rare (a user tap), so a thread per flip is
                        // negligible (project's detached-spawn idiom). reconcile() is serialized
                        // + idempotent.
                        Thread({
                            try { reconcile() } catch (t: Throwable) { Log.w(TAG, "reconcile error: ${t.message}") }
                        }, "roadsense-toggle").apply { isDaemon = true }.start()
                    }
                }
                enabledListener = l
                com.overdrive.app.config.UnifiedConfigManager.addListener(l)
                Log.i(TAG, "RoadSenseController attached (live-toggle listener armed)")
            }
        }
        // Bring the running state in line with the persisted flag right now (boot thread —
        // not under the config monitor, so a synchronous start() here is fine).
        reconcile()
    }

    /**
     * Deregister the live-toggle listener and stop the machinery. Called at daemon
     * shutdown (CameraDaemon.stop path) — the inverse of [attach]. Idempotent. Holds
     * [lifecycleLock] across stop() so an in-flight toggle reconcile can't race a start()
     * against this shutdown stop(); clearing [attached] first makes any queued reconcile a
     * no-op (it bails when not attached).
     */
    fun detach() {
        synchronized(lifecycleLock) {
            enabledListener?.let {
                try { com.overdrive.app.config.UnifiedConfigManager.removeListener(it) } catch (_: Throwable) {}
            }
            enabledListener = null
            attached = false
            stop()
            // Unconditional sidecar stop: stop() early-returns when !started, so
            // if a prior daemon instance was force-halted (Runtime.halt / kill)
            // its app-process IMU sidecar can survive as an orphan — this fresh
            // controller never saw started=true and would leave it streaming
            // batches at a dead socket forever. The stop is an idempotent
            // fire-and-forget `am stopservice`; a redundant call is a no-op.
            RoadSenseImuSidecarService.stop()
        }
    }

    /**
     * Start or stop the heavy machinery to match the persisted roadSense.enabled flag.
     * forceReload so a cross-UID web write is seen immediately (the listener fires in this
     * daemon process, but the flag may have been written via the app→daemon IPC path and
     * only just landed on disk). Serialized on [lifecycleLock] so overlapping toggle
     * notifications can't race start() against stop(). start()/stop() are themselves
     * idempotent (guarded by [started]), so a redundant reconcile is a cheap no-op.
     */
    private fun reconcile() {
        val enabled = try {
            RoadSenseConfig.snapshot(forceReload = true).enabled
        } catch (t: Throwable) {
            Log.w(TAG, "reconcile snapshot failed: ${t.message}"); return
        }
        synchronized(lifecycleLock) {
            // Record the authoritative flag we're acting on so the listener's flip-gate has a
            // correct baseline (e.g. the boot reconcile seeds it; a later real flip differs).
            lastKnownEnabled = enabled
            // Don't start after detach() (shutdown) has fired — the listener may have
            // queued one last reconcile before it was removed.
            if (!attached && enabled) return
            if (enabled && !started) {
                Log.i(TAG, "roadSense.enabled=true → starting controller")
                plog.info("enabled → start")
                start()
            } else if (!enabled && started) {
                Log.i(TAG, "roadSense.enabled=false → stopping controller (releasing stores/ticker/sync)")
                plog.info("disabled → stop (zero-cost)")
                stop()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        store.init()
        store.start()
        // groundTruth is deliberately NOT init()ed here: it opens lazily on the first
        // label write (see GroundTruthStore.record). Labels arrive at most a few times
        // per drive, but an eagerly opened H2 store keeps MVStore background writer/
        // compaction threads running for the whole session — measurable idle CPU.
        // stop() below stays unconditional (a no-op when the store was never opened).
        // Restore persisted per-vehicle calibration so maturity ACCUMULATES across
        // daemon restarts / reboots / app updates instead of resetting to 0 every
        // trip (a head unit power-cycles with the car, so without this the overlay
        // would sit at "Calibrating" forever and never reach the mature state).
        restoreCalibration()
        imuStream.setListener(this)
        // Self-driven housekeeping tick (kept out of CameraDaemon per D-023 notes):
        // ~2 Hz drives both the regime poll (gear/ACC → sidecar start/stop) and the
        // approach-warning eval. Both are cheap no-ops unless DRIVING. A single-
        // thread scheduler; the warning eval reads the store + GPS off the IMU path.
        ticker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "roadsense-tick").apply { isDaemon = true }
        }
        // Dedicated single-thread executor for the BLOCKING crowdsource sync, kept OFF
        // the tick thread (see syncExecutor field doc): a timing-out OkHttp sync must
        // never stall the regime poll / overlay publish that the tick drives. Single-
        // thread (so the relocated lastSyncMs/lastUploadCursor/tileCursor/store/UCM work
        // stays serialized exactly as it was when it ran inline on the one tick thread),
        // daemon thread so it never blocks daemon shutdown.
        syncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "roadsense-sync").apply { isDaemon = true }
        }
        ticker?.scheduleWithFixedDelay({
            try { onVehicleStatePoll(); onWarningTick() }
            catch (t: Throwable) { Log.w(TAG, "tick error: ${t.message}") }
        }, 0L, TICK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        Log.i(TAG, "RoadSenseController started")
    }

    fun stop() {
        if (!started) return
        started = false
        featureEnabled = false
        // Stop the IMU sidecar FIRST, before anything below that can block
        // (the synchronized classification drain can wait on an in-flight
        // IMU_BATCH; the calibration/coverage/H2 flushes do file I/O). The
        // stop is a fire-and-forget `am stopservice` exec (~non-blocking),
        // and issuing it early both halts the ~10 Hz inbound batch stream
        // during teardown and guarantees the app-process service is torn
        // down even if a later step wedges and the process is halted.
        RoadSenseImuSidecarService.stop()
        // The overlay is an app-process service, independent of this daemon-side
        // detector. The master-disable reconcile stops the ticker immediately, so no
        // later vehicle-state edge exists to tear the overlay down for us.
        com.overdrive.app.roadsense.overlay.RoadSenseOverlayService.stopFromDaemon()
        // A later re-enable must begin from a real OFF edge. Leaving this at DRIVING
        // made the first poll after restart return early and skip sidecar/overlay start.
        regime = VehicleStateGate.Regime.OFF
        ticker?.shutdownNow()
        ticker = null
        // Tear down the sync executor too. shutdownNow() interrupts any in-flight
        // blocking OkHttp call so a timing-out sync can't hold shutdown open; it's a
        // daemon thread anyway. Don't await — best-effort, the cursors are persisted
        // per-success and the next start() reloads them.
        syncExecutor?.shutdownNow()
        syncExecutor = null
        // Force-finalize any deferred candidates before teardown (RS-DEFER-1): on a clean
        // shutdown / DRIVING→OFF the sidecar stops, so no further onImuBatch will drain them —
        // without this, a hazard crossed in the final ~1.5 s is lost.
        // THREAD-SAFETY (audit RS-DEFER-1/d): stop() runs on the daemon shutdown thread, and the
        // IPC server may STILL be dispatching IMU_BATCH → onImuBatch → drainPendingClassifications
        // on its pool when roadSense.stop() is called (CameraDaemon stops the IPC server later in
        // its shutdown sequence). pendingClassify is a non-thread-safe ArrayDeque guarded only by
        // onImuBatch's @Synchronized(this) monitor — so we MUST take the same monitor here, or two
        // threads race the deque (CME / double-finalize). synchronized(this) makes an in-flight
        // batch either complete first or block until we're done.
        try {
            synchronized(this) {
                if (pendingClassify.isNotEmpty()) drainPendingClassifications(lastContextSampleMs, clock(), force = true)
            }
        } catch (t: Throwable) { Log.w(TAG, "stop() flush error: ${t.message}") }
        imuStream.stop()
        // Stop the scoped fast-dynamics poll if it was running (DRIVING at shutdown).
        try { vehicleSource.collectorOrNull()?.stopFastDynamicsPoll() } catch (_: Throwable) {}
        // (sidecar stop already issued at the top of stop(), before the drain)
        warnings.release()
        // Flush + close any open raw recording (debug, D-036).
        try { rawRecorder.stop() } catch (_: Throwable) {}
        rawRecord = false
        // Flush learned state on a clean shutdown so the last minute isn't lost (the
        // throttled persists may not have fired right before stop). Best-effort.
        try {
            val (qc, ms) = calibrator.snapshot()
            persistCalibration(qc, ms)
            coverage.persistIfDirty()
        } catch (_: Throwable) {}
        groundTruth.stop()
        store.stop()
        Log.i(TAG, "RoadSenseController stopped")
    }

    /** Wipe route coverage (called from delete-local, R-SET-5). */
    fun clearCoverage() = coverage.clear()

    /**
     * Entry point for the IPC server's `IMU_BATCH` case (DEFERRED patch). Decodes
     * the wire line and feeds the pipeline. Returns samples processed (for the
     * IPC ack), 0 if not an IMU_BATCH line or detector is paused.
     *
     * @Synchronized (audit threading): the IPC server dispatches on a fixed 8-thread
     * pool (SurveillanceIpcServer newFixedThreadPool(8)), one line per connection. The
     * sidecar normally batches ~10×/s over short-lived connections, but nothing
     * GUARANTEES batch N's handler finishes before batch N+1's thread starts — a
     * slow/stalled handler can overlap, and the whole detection pipeline (GravityFrame,
     * EventDetector ring buffers, gyro peaks, calibrator, gpsBuffer, store-merge maps)
     * is single-writer-by-contract and non-volatile on the hot path. Serializing here
     * upholds that contract at ~10 calls/s cost — same posture as applyConfig's
     * CONFIG_LOCK. onAccel/onGyro are only ever reached THROUGH this method (via
     * imuStream.feed), so locking the entry point covers the entire pipeline.
     */
    @Synchronized
    fun onImuBatch(frame: JSONObject): Int {
        // FLUSH-on-leaving-DRIVING (RS-DEFER-1): when the regime just left DRIVING, deferred
        // candidates in pendingClassify would otherwise be stranded — the drain below is past
        // the early-return, and resetTransient would CLEAR them unclassified. So FORCE-finalize
        // the queue here, BEFORE the early-return, on this same IPC thread (single-writer safe).
        // This catches the user's "cross a breaker then brake to a stop / park" case (the most
        // important slow crossings). One-shot per leave-DRIVING; localization already used the
        // event tMs, so finalizing now (slightly short post-envelope) beats dropping the hazard.
        if (flushPendingRequested) {
            flushPendingRequested = false
            if (pendingClassify.isNotEmpty()) {
                try { drainPendingClassifications(lastContextSampleMs, clock(), force = true) }
                catch (t: Throwable) { Log.w(TAG, "flush drain error: ${t.message}") }
            }
        }
        if (!started || regime != VehicleStateGate.Regime.DRIVING) return 0
        // Perform any pending reset HERE (on this single IPC/sensor thread) so all
        // pipeline-state mutation is serialized — onVehicleStatePoll only flags it.
        if (resetRequested) {
            resetRequested = false
            resetTransient()
        }
        val decoded = ImuFrameCodec.decode(frame) ?: return 0
        val n = imuStream.feed(decoded, clock())
        // Drain deferred classifications whose POST-event envelope has now elapsed (D-040).
        // feed() ran onAccel/onGyro synchronously above, so lastContextSampleMs is the newest
        // sensor clock. Same IPC thread → shares the contextRing/store single-writer contract.
        if (pendingClassify.isNotEmpty()) drainPendingClassifications(lastContextSampleMs, clock())
        // Persistent IMU-flow heartbeat (survives a no-ADB drive). Throttled to
        // ~30 s so it's cheap on the IPC path. A drive with ZERO detected hazards
        // still leaves proof here that IMU actually flowed + how calibration climbed,
        // distinguishing "pipeline ran, road was smooth" from "pipeline never ran".
        imuBatchCount += n
        val nowWall = clock()
        if (nowWall - lastImuHeartbeatMs > IMU_HEARTBEAT_MS) {
            lastImuHeartbeatMs = nowWall
            val rejects = if (dropRejects.isEmpty()) "" else
                " rejects=" + dropRejects.entries.joinToString(",") { "${it.key}:${it.value}" }
            plog.info("imu-flow: batches~${imuBatchCount} samples, cal=${"%.3f".format(calibrator.maturity)} " +
                "events=${detector.totalCandidates} stored=$dropStored noDyn=$dropNoDyn noPose=$dropNoPose" +
                rejects + " speed=${"%.0f".format(lastSeenSpeedKmh)}km/h")
        }
        return n
    }

    /**
     * Re-evaluate the operating regime from current gear + ACC and act on the
     * transition (D-021). Safe to call on any cadence; the daemon can self-poll
     * (e.g. every ~1 s) or call on vehicle-data ticks.
     */
    fun onVehicleStatePoll() {
        if (!started) return
        val now = clock()
        // Refresh the master-enabled flag here too (not only on the IMU throttle)
        // so toggling RoadSense off promptly tears the sidecar down even while the
        // car keeps driving — a disabled feature must cost ~zero (R-EXT-6 / D-021).
        // Snapshot once: we also consult overlayVisible below for the app-side overlay
        // launch, and a single read keeps the two decisions consistent within this tick.
        val cfgSnap = RoadSenseConfig.snapshot()
        featureEnabled = cfgSnap.enabled
        // Pick up any change to the speed-adaptive cornering-yaw knobs (rare; off the
        // 100 Hz IMU path — this is the ~2 Hz vehicle poll).
        maybeRebuildRejection(cfgSnap)
        // Pick up any change to the user's detection-sensitivity slider and push it into
        // the (stateful) detector LIVE — a plain @Volatile assignment, so no restart, no
        // lost in-flight event. Same off-the-100-Hz-path cadence as the yaw rebuild.
        maybeApplyDetectionSensitivity(cfgSnap)
        val dyn = vehicleSource.latest(now)
        val accOn = AccMonitor.isAccOn()
        val accAuth = AccMonitor.isAccStateAuthoritative()
        // Gear for the regime gate: prefer the AUTHORITATIVE GearMonitor (200 ms poll)
        // ONLY while it's actually running, else fall back to the collector snapshot.
        // CRITICAL (audit): GearMonitor.currentGear defaults to GEAR_P(1) and is never
        // 0/UNAVAILABLE, so a `g > 0` guard never falls back — and since GearMonitor
        // starts AFTER RoadSense at boot and STOPS on ACC-off, reading it unconditionally
        // would return a stale P during the boot/first-arming window and pin us in
        // RELAXED forever (the exact "Calibrating forever" bug). So we gate on
        // isRunning(): only trust GearMonitor when its poll thread is live; otherwise
        // use the collector's gearMode (which reads the same HAL getter and is correctly
        // UNAVAILABLE→non-forward before it has a real reading).
        val gm = com.overdrive.app.monitor.GearMonitor.getInstance()
        val gear = try {
            if (gm.isRunning) gm.currentGear else (dyn?.gearMode ?: 0)
        } catch (_: Throwable) { dyn?.gearMode ?: 0 }

        // Feature disabled ⇒ treat as OFF regime regardless of gear/ACC: no sidecar,
        // no IMU, no pipeline. This is the master switch's teeth.
        val newRegime = if (!featureEnabled) VehicleStateGate.Regime.OFF
        else VehicleStateGate.evaluate(accOn, accAuth, gear)
        if (newRegime == regime) return
        // RACE FIX: serialize the edge with stop()/detach(). This tick may have
        // passed the started check at the top just before shutdown or a toggle-
        // off flipped it; applying the edge would then re-launch the IMU sidecar
        // (startImu/slowImu) AFTER stop() issued its `am stopservice`, orphaning
        // the app-process service until the next daemon respawn. Recheck started
        // under the same lock stop()'s callers hold so no later start can win;
        // also recheck the edge in case another path already moved the regime.
        synchronized(lifecycleLock) {
            if (!started) return
            if (newRegime == regime) return
            applyRegimeTransitionLocked(newRegime, cfgSnap, now, accOn, accAuth, gear)
        }
    }

    /**
     * Apply a regime edge: sidecar start/stop, overlay lifecycle, transient-reset
     * flags, fast-dynamics poll and IMU stream rate. MUST be called under
     * [lifecycleLock] with started=true (see the race note in onVehicleStatePoll).
     */
    private fun applyRegimeTransitionLocked(
        newRegime: VehicleStateGate.Regime,
        cfgSnap: RoadSenseConfig.Snapshot,
        now: Long,
        accOn: Boolean,
        accAuth: Boolean,
        gear: Int,
    ) {
        val action = VehicleStateGate.transition(regime, newRegime)
        Log.i(TAG, "regime $regime → $newRegime")
        // Persist regime transitions (survives a no-ADB drive). gear + acc context so
        // a post-drive read explains WHY it did/didn't reach DRIVING.
        plog.info("regime $regime → $newRegime (acc=$accOn auth=$accAuth gear=$gear feature=$featureEnabled)")
        regime = newRegime

        when {
            action.startImu -> {
                RoadSenseImuSidecarService.start(RoadSenseImuSidecarService.ImuRate.FAST)
                // Give the freshly-launched sidecar its grace window before the warn-tick
                // stall check would consider relaunching it: lastFeedMs can be stale from
                // a prior DRIVING stint (live stays true across RELAXED/OFF), so without
                // this the first DRIVING tick after an OFF period would see a "stall" and
                // fire a redundant `am start` on the service we just launched.
                lastSidecarRelaunchMs = now
            }
            action.slowImu -> RoadSenseImuSidecarService.start(RoadSenseImuSidecarService.ImuRate.SLOW)
            action.stopImu -> RoadSenseImuSidecarService.stop()
        }

        // App-side overlay lifecycle (D-024): show the floating pill/card whenever the
        // feature is alive (ACC on + enabled ⇒ DRIVING or RELAXED), tear it down on OFF.
        // The daemon launches it via `am` (startFromDaemon) because the overlay must
        // appear on ACC-on WITHOUT the user opening MainActivity — its only other launch
        // path. We only reach here on a real regime EDGE (the newRegime==regime early-
        // return above already filtered no-ops), so this is one `am` per transition, not
        // per tick. startFromDaemon is idempotent on a DRIVING↔RELAXED flip (am reuses
        // the running service via onStartCommand). The overlay self-guards overlay
        // permission, so this no-ops cleanly when it isn't granted.
        //
        // overlayVisible gate (user opt-out, default ON): hiding the overlay is purely
        // an on-screen choice — detection/audio/crowdsource all keep running daemon-side
        // (audio is daemon-owned, independent of this app window). So when the user has
        // hidden it, DON'T launch the overlay on a regime edge (and the service's own poll
        // self-stops if it was already up). We still tear it down on OFF unconditionally.
        if (newRegime == VehicleStateGate.Regime.OFF || !cfgSnap.overlayVisible) {
            com.overdrive.app.roadsense.overlay.RoadSenseOverlayService.stopFromDaemon()
        } else {
            com.overdrive.app.roadsense.overlay.RoadSenseOverlayService.startFromDaemon()
        }

        if (action.persistState && newRegime != VehicleStateGate.Regime.DRIVING) {
            // Leaving DRIVING. FORCE-finalize any deferred candidates BEFORE the transient
            // reset clears them (RS-DEFER-1) — flag first, the reset consumes after. Both run
            // on the IPC thread at the next batch (thread safety); flush is ordered first in
            // onImuBatch so resetTransient can't discard an unclassified pending hazard.
            flushPendingRequested = true
            // Request a transient-state reset so a stale half-event can't fire on resume —
            // but DON'T mutate detector state here (housekeeping thread, not IPC/sensor thread).
            resetRequested = true
        }

        // Fast vehicle-dynamics poll (R-PERF-4): brake/accel/gear event-aligned to
        // ~200 ms jolts. Run it ONLY while DRIVING (when detection + rejection are
        // active) — and since !featureEnabled forces regime=OFF above, this is
        // implicitly gated on RoadSense being enabled too. Any non-DRIVING regime
        // (RELAXED/OFF) stops it, so it costs nothing when parked or feature-off.
        if (newRegime == VehicleStateGate.Regime.DRIVING) {
            try { vehicleSource.collectorOrNull()?.startFastDynamicsPoll() }
            catch (t: Throwable) { Log.w(TAG, "startFastDynamicsPoll: ${t.message}") }
        } else {
            try { vehicleSource.collectorOrNull()?.stopFastDynamicsPoll() }
            catch (t: Throwable) { Log.w(TAG, "stopFastDynamicsPoll: ${t.message}") }
        }

        if (newRegime == VehicleStateGate.Regime.DRIVING) imuStream.start(ImuStream.Rate.FULL)
    }

    /**
     * Restore persisted per-vehicle calibration from UCM. Cross-UID safe via
     * forceReload (daemon may have just (re)started). VEHICLE-IDENTITY GUARD (audit
     * accuracy S2): the persisted baseline is only restored if the stored VIN matches
     * the current car's VIN — a car/head-unit swap (different VIN) discards the old
     * baseline and re-learns, rather than silently mis-scaling severity with another
     * vehicle's suspension model. If either VIN is unknown/empty we can't prove a
     * mismatch, so we trust the persisted value (no regression vs no-guard). Any parse
     * failure simply starts calibration fresh.
     */
    private fun restoreCalibration() {
        try {
            val rs = com.overdrive.app.config.UnifiedConfigManager.forceReload()
                .optJSONObject("roadSense") ?: return
            val qc = rs.optLong("calQuietCount", 0L)
            val ms = rs.optDouble("calMeanSq", -1.0)
            if (qc <= 0L || ms < 0.0) return
            val savedKey = rs.optString("calVehicleKey", "")
            val curKey = vehicleKey()
            if (savedKey.isNotEmpty() && curKey.isNotEmpty() && savedKey != curKey) {
                plog.info("calibration NOT restored: vehicle changed (saved=$savedKey cur=$curKey) → re-learning")
                return
            }
            calibrator.restore(qc, ms.toFloat())
            plog.info("calibration restored: quietCount=$qc meanSq=${"%.5f".format(ms)} maturity=${"%.3f".format(calibrator.maturity)}")
        } catch (_: Throwable) { /* start fresh */ }
    }

    /**
     * Persist calibration state to UCM on a throttle ([CAL_PERSIST_MS]). Only writes
     * when quietCount has meaningfully advanced since the last persist, so a parked /
     * smooth-road tick doesn't rewrite the config file needlessly (updateSection is a
     * full-file rewrite). Daemon tick thread only.
     */
    private fun maybePersistCalibration(now: Long) {
        if (now - lastCalPersistMs < CAL_PERSIST_MS) return
        val (qc, ms) = calibrator.snapshot()   // atomic pair (audit concurrency B)
        if (qc <= lastPersistedQuietCount) return   // no new learning → skip the rewrite
        lastCalPersistMs = now
        lastPersistedQuietCount = qc
        persistCalibration(qc, ms)
    }

    /**
     * Write calibration to UCM. forceReload FIRST (audit concurrency D): these are
     * daemon-owned keys, but updateSection rewrites the whole roadSense section by
     * merging onto the daemon's cached config — and ext4's 1 s mtime granularity can
     * hide a just-written app key (warnMode / pendingConfirmResult), so without a
     * fresh reload the daemon's write could drop the user's toggle. The vehicle
     * fingerprint is stored alongside so a car/head-unit swap is detected on restore
     * (audit accuracy S2).
     */
    private fun persistCalibration(quietCount: Long, meanSq: Float) {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload()
            com.overdrive.app.config.UnifiedConfigManager.updateSection(
                "roadSense",
                org.json.JSONObject()
                    .put("calQuietCount", quietCount)
                    .put("calMeanSq", meanSq.toDouble())
                    .put("calVehicleKey", vehicleKey()),
            )
        } catch (_: Throwable) { /* best-effort; lost write just re-learns a little */ }
    }

    /**
     * Vehicle identity to tie calibration to the car that learned it (audit accuracy
     * S2): the BYD VIN from the bus snapshot. On a car/head-unit swap the VIN changes,
     * so restoreCalibration() discards a mismatched baseline rather than mis-scaling
     * severity with another vehicle's suspension model. Empty string when the VIN
     * isn't available yet — restore then skips the identity check (can't prove a
     * mismatch, so it trusts the persisted value, same as before this guard).
     */
    private fun vehicleKey(): String =
        try { com.overdrive.app.byd.BydDataCollector.getInstance().getData()?.vin ?: "" }
        catch (_: Throwable) { "" }

    /** Persist route coverage on a throttle ([COVERAGE_PERSIST_MS]). Called only when
     *  a NEW tile was entered, then further throttled so a fast road through many
     *  tiles doesn't rewrite the config file per tile. Daemon tick thread. */
    private fun maybePersistCoverage(now: Long) {
        if (now - lastCoveragePersistMs < COVERAGE_PERSIST_MS) return
        lastCoveragePersistMs = now
        coverage.persistIfDirty()
    }

    private fun resetTransient() {
        gravity.reset()
        detector.reset()
        gpsBuffer.reset()
        peakYaw = 0f; peakYawTMs = 0L
        peakPitch = 0f; peakPitchTMs = 0L; peakTrueRoll = 0f; peakTrueRollTMs = 0L
        pitchEverValid = false
        recentCandidateMs.clear()
        lastRoughSectionMs = 0L
        cachedDynamics = null
        lastVehiclePollMs = 0L
        lastGpsPollMs = 0L
        // Clear the hazard-classifier context ring across a pause/regime reset so a stale
        // pre-pause sample can't bridge into a post-resume envelope. Do NOT reset pitchSign:
        // the mount orientation is drive-stable and regime resets fire at every stop, so
        // re-anchoring each time would keep it perpetually cold (it only resets on a true
        // sensor restart / large gap, in reset()).
        contextRing.clear()
        lastContextSampleMs = 0L
        lastCtxSpeedKmh = Float.NaN
        latestPitchRate = 0f
        latestPitchValid = false
        // Drop deferred candidates across a pause — their envelope would finalize against a
        // post-resume context ring (stale). A half-built decision is not worth a wrong map row.
        pendingClassify.clear()
    }

    /**
     * Delete this device's uploaded rows from the shared backend (R-SET-5
     * delete-cloud), called by RoadSenseApiHandler. Uses the current rotating
     * device id; also clears local tile cursors so a re-sync repopulates cleanly.
     * Returns true on success.
     */
    fun deleteCloudUploads(): Boolean {
        val ok = syncProvider.deleteOwnUploads(DeviceId.current(clock()))
        if (ok) tileCursor.clear()
        return ok
    }

    /**
     * Approach-warning tick. Called by the daemon on a modest cadence (~2–4 Hz) —
     * NOT the 100 Hz IMU path. Reads the live pose + config and lets the
     * WarningCoordinator decide whether to chime/flash for an upcoming hazard.
     * Only meaningful while DRIVING; cheap no-op otherwise.
     */
    fun onWarningTick() {
        if (!started) return
        val now = clock()

        // Refresh the Calibration-Mode flag BEFORE the regime gate so a card opened
        // while driving still reacts to the user toggling Calibration Mode off after
        // they've stopped (regime → RELAXED/OFF). Cheap mtime-gated read.
        val cfgTick = RoadSenseConfig.snapshot(forceReload = false)
        calibrationMode = cfgTick.calibrationMode
        warnAudioChannel = cfgTick.warnAudioChannel
        warnAudioVolume = cfgTick.warnAudioVolume

        // Raw-IMU recorder lifecycle (D-036, debug): start/stop with the rawRecord flag, and
        // consume a one-shot ground-truth MARK poked into roadSense.rawMark (adb/web). Runs
        // every tick regardless of regime so you can arm recording + drop a "real breaker
        // HERE" mark whether driving or stopped. Cheap mtime-gated reads; off the hot path.
        //
        // BuildConfig.DEBUG gate: the recorder is a DEV/tuning tool, never a shipped feature.
        // In a release build BuildConfig.DEBUG is a compile-time `false` constant, so R8
        // dead-strips this whole block — the shipped binary has NO path to start the recorder
        // even if someone flips roadSense.rawRecord in the on-device config (it's a plain JSON
        // file). rawRecord then stays false forever, so every capture site (the `if (rawRecord)`
        // guards in onAccel/onGyro) is also dead. Same posture as Od.authorize's debug bypass.
        if (com.overdrive.app.BuildConfig.DEBUG) {
            maybeToggleRawRecorder(cfgTick.rawRecord)
            if (rawRecord) maybeConsumeRawMark(now)
        }

        // Consume any Calibration-Mode verdict / fire the G-7 auto-accept timeout
        // BEFORE the regime gate (and regardless of regime): a confirm card stays up
        // when you slow to a stop or park (regime → RELAXED/OFF), and it MUST still
        // auto-dismiss after CONFIRM_TIMEOUT_MS so it never lingers demanding a tap.
        // Previously this sat after the DRIVING early-return, so stopping with a card
        // open froze it forever. Only runs when actually awaiting a verdict.
        if (pendingConfirm != null) {
            // If the user turned Calibration Mode OFF while a card is open, dismiss it
            // immediately instead of waiting out the 8 s timeout (audit LOW). The
            // detection is already stored; we just don't surface/await a label.
            if (!calibrationMode) {
                pendingConfirm = null
                visualSink.setPendingConfirm(null)
            } else {
                consumePendingConfirmResult(now)
            }
        }

        // The rest (approach warnings, coverage, sync) is only meaningful while DRIVING.
        if (regime != VehicleStateGate.Regime.DRIVING) return

        // SIDECAR STALL RECOVERY (audit: isStalled was built but never wired). The IMU
        // sidecar is an app-process foreground service started START_NOT_STICKY; if the
        // OS kills it under memory pressure mid-DRIVING, batches stop arriving and the
        // whole pipeline silently goes dark — calibration freezes and NO hazard is ever
        // detected — until the next regime transition happens to re-launch it (which may
        // be the entire trip away). Detect the gap here on the ~2 Hz tick and re-launch
        // FAST, throttled so we don't spam `am` while a freshly-launched sidecar is still
        // coming up. Only meaningful once data has flowed at least once (isStalled guards
        // lastFeedMs>0), so a not-yet-started stream on a cold tick doesn't false-trip.
        if (imuStream.isStalled(now) && (now - lastSidecarRelaunchMs) > SIDECAR_RELAUNCH_MS) {
            // RACE FIX (same as onVehicleStatePoll's edge): recheck started under
            // lifecycleLock before relaunching — this tick may have passed the
            // started check at the top just before stop() flipped it, and a
            // relaunch here would resurrect the sidecar stop() just stopped.
            synchronized(lifecycleLock) {
                if (!started) return
                lastSidecarRelaunchMs = now
                Log.w(TAG, "IMU sidecar stalled → relaunch FAST")
                plog.info("imu sidecar stalled (no batch >${DaemonImuStream.DEFAULT_STALL_MS}ms) → relaunching FAST")
                try {
                    RoadSenseImuSidecarService.start(RoadSenseImuSidecarService.ImuRate.FAST)
                } catch (t: Throwable) { Log.w(TAG, "sidecar relaunch failed: ${t.message}") }
            }
        }

        // One coherent snapshot drives both warning gates and the channel/volume
        // suppliers above. A second read here could straddle a settings write and pair
        // the new warning policy with the previous audio destination for one tick.
        val cfg = cfgTick

        val rawPose = locationSource.latest(now)
        if (rawPose == null) {
            // No fix: still heartbeat idle so the app overlay knows we're alive.
            visualSink.publishIdle()
            return
        }
        // DISPLAY pose: forward-extrapolate the ~1–2 Hz fix to `now` so the hazard
        // distance counts down smoothly between fixes (the map puck already glides
        // via VehicleMotionEstimator; the raw fix is step-held flat, so an un-
        // extrapolated range freezes then jumps — read as "the distance lags").
        // Stateless + guarded (moving + good fix + capped horizon); outside the
        // guard band it returns the raw pose unchanged. Detection localization and
        // the GpsRingBuffer feed below deliberately keep the RAW fix.
        //
        // CRITICAL — display-only: the extrapolated pose is a constant-velocity dead-
        // reckon that knows nothing about hazards, so it must NOT drive the AHEAD/CLEAR
        // (selection / forward-cone / road-match) decision. While braking on approach
        // the true position lags the dead-reckon, and an over-advanced pose can flip a
        // not-yet-passed lead behind the cone — dropping the card a tick early and
        // skipping the next cluster member. So ALL geometry/gating below (coverage,
        // headingReliable, warnings.onTick selection, maybeSync) runs on the RAW fix;
        // only the DISPLAYED range is smoothed (WarningCoordinator subtracts the
        // forward-advance from the shown distance, clamped so it never crosses a hazard).
        val pose = locationSource.forwardExtrapolated(rawPose, now)
        // Heading is reliable only when actually moving (GPS bearing is noise at
        // crawl); ApproachEngine SUPPRESSES warnings entirely when this is false
        // (can't tell our road/direction without a reliable bearing — R-EXT-4).
        // Judged on the RAW fix's speed (the extrapolation never changes speed anyway).
        val headingReliable = rawPose.speedMps > HEADING_RELIABLE_MPS

        // Route coverage: record this tile (only while genuinely MOVING, so a parked
        // car with GPS jitter doesn't fabricate "passes") and publish its level so the
        // overlay's idle caption distinguishes a mapped road from a new one. The
        // supplier (lastCoverageLevel) is read by visualSink inside warnings.onTick.
        // Uses the RAW fix so coverage reflects where we ACTUALLY are, not a guess.
        if (rawPose.speedMps > HEADING_RELIABLE_MPS) {
            if (coverage.record(rawPose.lat, rawPose.lng, now)) maybePersistCoverage(now)
        }
        lastCoverageLevel = coverage.levelAt(rawPose.lat, rawPose.lng).ordinal

        // Selection/geometry on the RAW fix; the extrapolated `pose` is handed in only
        // to smooth the shown distance (counts down between fixes) — never to decide
        // whether a hazard is still ahead.
        warnings.onTick(rawPose, pose, cfg, headingReliable)
        // onTick always publishes overlay state on every DRIVING tick, so the app
        // overlay's 4 s staleness window never trips: a hazard ahead in a visual mode
        // writes the card via showApproach; nothing ahead (or AUDIO-only mode, which
        // shows no card) writes idle via clearApproach. Heartbeat covered in every mode.

        // Crowdsource sync runs on a much slower cadence than the warn tick. Uses the
        // RAW fix — the tile neighbourhood it syncs must match where we truly are.
        maybeSync(cfg, rawPose, now)

        // Persist the per-vehicle calibration on a slow throttle so it survives a
        // restart (off the 100 Hz path — this is the ~2 Hz warn tick).
        maybePersistCalibration(now)
    }

    /**
     * Opt-in crowdsource sync (Phase 8, D-009). Throttled to [SYNC_MS]. Download
     * pulls confirmed hazards in the tiles around the current pose (delta-by-tile,
     * R-CRD-5); upload pushes this device's high-confidence local hazards (D-016).
     * Both directions independently gated by config (default OFF, R-CRD-6).
     *
     * THREADING (production fix): the actual download()/upload() are BLOCKING OkHttp
     * calls (10 s connect / 15 s read timeouts — CloudflareEdgeSyncProvider). They MUST
     * NOT run on the warn-tick thread, which also drives the regime poll and publishes
     * the overlay state every tick: scheduleWithFixedDelay won't fire the next tick
     * until the current one returns, so a timing-out sync (~30 s) stalls the overlay
     * publish past the app's 4 s staleness window and the overlay reads "Idle". So this
     * method ONLY does the cheap "is a sync due?" gate on the tick thread, then SUBMITS
     * the blocking body to the dedicated [syncExecutor]. The submitted body keeps all
     * the existing audit-driven correctness (cursor tie guards, per-tile high-water,
     * upsertCloudHazard, forceReload-before-write) verbatim — it's RELOCATED, not
     * rewritten. Only ONE sync runs at a time ([syncInFlight] guard); overlapping ticks
     * during a slow sync skip submitting another.
     */
    private fun maybeSync(cfg: RoadSenseConfig.Snapshot, pose: com.overdrive.app.roadsense.detect.Pose, now: Long) {
        if (!cfg.crowdDownload && !cfg.crowdUpload) return
        // Already syncing on syncExecutor → don't queue another (one at a time).
        if (syncInFlight) return
        // Lazy-load the persisted last-sync time on first use so a reboot doesn't
        // re-sync. (cfg is fresh this tick; read the raw section for the long.) Cheap
        // file read, happens once; still on the tick thread but never blocks network.
        if (lastSyncMs < 0L) {
            lastSyncMs = try {
                com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                    .optJSONObject("roadSense")?.optLong("lastSyncMs", 0L) ?: 0L
            } catch (_: Throwable) { 0L }
        }
        if (now - lastSyncMs < SYNC_MS) return
        // Failure backoff: a prior attempt that made NO progress (worker unreachable,
        // network down) parks the next attempt SYNC_RETRY_BACKOFF_MS out instead of
        // letting it retry next tick. This preserves "retry transient failures sooner
        // than 2.5 h" (audit network #5b) WITHOUT the every-tick block-retry storm the
        // old design produced (lastSyncMs only advanced on success, so a persistently-
        // failing worker was hammered as fast as the block allowed, forever).
        if (now < nextSyncAttemptMs) return

        // Due. Mark in-flight and hand the BLOCKING body to syncExecutor. Capture the
        // pose/cfg/now by value (data-class snapshot + immutable args) so the task
        // doesn't read shared tick-thread state. lastSyncMs/lastUploadCursor/tileCursor/
        // store/UCM are all mutated INSIDE the task on the single sync thread — the tick
        // thread only READ lastSyncMs above for the due-gate (volatile) — so there's no
        // concurrent mutation of those fields.
        syncInFlight = true
        val exec = syncExecutor
        if (exec == null || exec.isShutdown) { syncInFlight = false; return }
        try {
            exec.execute {
                try { runSync(cfg, pose, now) }
                catch (t: Throwable) { Log.w(TAG, "sync error: ${t.message}") }
                finally { syncInFlight = false }
            }
        } catch (t: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down between the null-check and submit (stop() race) —
            // clear the guard so a later start() can sync again.
            syncInFlight = false
        }
    }

    /**
     * The BLOCKING crowdsource-sync body, RELOCATED off the warn-tick thread onto the
     * single-thread [syncExecutor] (see maybeSync). Runs at most one-at-a-time, so the
     * fields it reads/writes (lastSyncMs, lastUploadCursor, tileCursor) + the store /
     * UnifiedConfigManager calls are single-writer here exactly as they were when this
     * ran inline on the lone tick thread — the tick thread no longer mutates any of
     * them (it only READS the volatile lastSyncMs for the due-gate). Internals (cursor
     * tie guards, per-tile high-water advance, upsertCloudHazard vs upsertDetection,
     * forceReload-before-write) are unchanged.
     */
    private fun runSync(cfg: RoadSenseConfig.Snapshot, pose: com.overdrive.app.roadsense.detect.Pose, now: Long) {
        // Don't advance/persist the cadence cursor until at least one enabled
        // direction actually succeeded (audit network #5b): download()/upload() are
        // designed to return ok=false (network down, worker unreachable, proxy mid-
        // transition) rather than throw, so advancing here would burn the full 2.5 h
        // window on a transient blip and defer the next retry for hours. A failed
        // attempt instead re-arms via the SYNC_RETRY_BACKOFF_MS cooldown below (the
        // next attempt runs ~5 min out, not every tick — see the `else` branch).
        var anyOk = false
        // Set when the server capped the response and more rows match (cold/dense
        // first sync): schedule the NEXT sync SOON to drain the remaining pages
        // rather than waiting the full 2.5 h cadence (audit network LOW paging).
        var morePending = false

        if (cfg.crowdDownload) {
            val tiles = SpatialIndex.neighborTiles(pose.lat, pose.lng).toList()
            val sinceMap = tileCursor.sinceMap(tiles)
            val result = syncProvider.download(tiles, sinceMap)
            if (result.ok) {
                anyOk = true
                if (result.more) morePending = true
                // Persist downloaded confirmed hazards as CLOUD-sourced rows so they
                // show up in approach queries but are NEVER re-uploaded (audit network
                // #7): upsertDetection only ever writes source=LOCAL, so a plain
                // upsert here tagged downloaded rows LOCAL and made them eligible for
                // re-upload under this device's id — fabricating distinct-device
                // consensus. upsertCloudHazard tags them SOURCE_CLOUD; queryForUpload
                // filters those out.
                for (h in result.hazards) store.upsertCloudHazard(h, now)
                // Advance each tile to ITS OWN observed high-water, NEVER to a busy
                // neighbour's max (audit network #9): a quiet tile that returned
                // nothing is caught up only to its own query floor (the `since` we
                // sent for it), so a row whose updated_ms later lands between that
                // floor and an unrelated busy tile's max is still returned next sync
                // — not skipped forever. Tiles that DID return rows advance to their
                // own tileHighWater. This still avoids the min-cursor re-pull cost
                // (audit network #2) because each empty tile keeps its own floor
                // rather than being dragged back to the global minimum.
                tileCursor.advance(sinceMap, result.tileHighWater)
            }
        }
        if (cfg.crowdUpload) {
            val deviceId = DeviceId.current(now)
            // Lazy-load the persisted upload cursor on first use (same pattern as
            // lastSyncMs) so a reboot / id-rotation doesn't re-upload the backlog
            // (audit network #5).
            if (lastUploadCursor < 0L) {
                lastUploadCursor = try {
                    com.overdrive.app.config.UnifiedConfigManager.loadConfig()
                        .optJSONObject("roadSense")?.optLong("lastUploadCursor", 0L) ?: 0L
                } catch (_: Throwable) { 0L }
            }
            // Cap the batch HERE to the same size the provider sends (256), so the
            // cursor advance and the actually-uploaded slice agree. Advancing over a
            // larger query result than was sent would silently drop rows past 256
            // forever (audit network #1). Remaining rows go on the next tick.
            var batch = store.queryForUpload(cfg.uploadConfidenceThreshold.toDouble(), lastUploadCursor)
                .take(MAX_UPLOAD_BATCH)
            // Batch-boundary tie guard (audit network #6): queryForUpload filters
            // `updated_ms > since` STRICTLY, so once we advance the cursor to the
            // batch's max updated_ms, any eligible row sharing that exact ms but cut
            // off by the 256 cap is skipped FOREVER. Ties are realistic (inserts +
            // merges stamp updated_ms = nowMs; a backlog or washboard burst can write
            // several rows in the same ms). When the batch is full AND its trailing
            // rows all share the max updated_ms, drop that equal-ms tail and don't
            // advance past it, so the whole tied group is re-queried next tick instead
            // of partially uploaded-and-skipped.
            if (batch.size == MAX_UPLOAD_BATCH) {
                val maxMs = batch.last().updatedMs
                val trimmed = batch.filter { it.updatedMs < maxMs }
                // Only trim if doing so still leaves something to send; if the ENTIRE
                // capped batch shares one updated_ms we can't make progress by trimming
                // (we'd send nothing forever), so send it as-is and accept that any
                // same-ms rows beyond the cap are an extreme edge we can't page past.
                if (trimmed.isNotEmpty()) batch = trimmed
            }
            if (batch.isNotEmpty()) {
                val r = syncProvider.upload(batch, deviceId)
                if (r.ok) {
                    anyOk = true
                    lastUploadCursor = batch.maxOf { it.updatedMs }
                    try {
                        // forceReload BEFORE the merge-write (audit concurrency D, same
                        // as persistCalibration): updateSection rewrites the WHOLE
                        // roadSense section by merging our key onto our CACHED config. If
                        // the app just toggled warnMode and ext4's 1 s mtime granularity
                        // hides it from our mtime-gated cache, our rewrite would clobber
                        // it back. Rebuild the merge from fresh disk so app-owned keys
                        // survive (narrow window — only when crowd-upload is enabled).
                        com.overdrive.app.config.UnifiedConfigManager.forceReload()
                        com.overdrive.app.config.UnifiedConfigManager.updateSection(
                            "roadSense",
                            org.json.JSONObject().put("lastUploadCursor", lastUploadCursor),
                        )
                    } catch (_: Throwable) {}
                }
            } else {
                // Upload enabled but nothing eligible to send — that's a successful
                // no-op for cadence purposes (don't keep retrying every tick).
                anyOk = true
            }
        }

        // Only now that at least one direction succeeded (or had nothing to do) do we
        // advance + persist the 2.5 h cadence cursor (audit network #5b). If the
        // server signalled more pages pending, back-date the cursor so the NEXT
        // housekeeping tick re-syncs within ~SYNC_PAGE_DRAIN_MS to drain the rest,
        // instead of waiting the full cadence (cold/dense first sync only).
        if (anyOk) {
            lastSyncMs = if (morePending) now - SYNC_MS + SYNC_PAGE_DRAIN_MS else now
            // Clear any failure backoff — success returns us to the normal cadence,
            // governed by lastSyncMs (or the fast page-drain back-date above).
            nextSyncAttemptMs = 0L
            try {
                // forceReload first (audit concurrency D) — see lastUploadCursor write.
                com.overdrive.app.config.UnifiedConfigManager.forceReload()
                com.overdrive.app.config.UnifiedConfigManager.updateSection(
                    "roadSense", org.json.JSONObject().put("lastSyncMs", lastSyncMs))
            } catch (_: Throwable) {}
        } else {
            // No enabled direction made progress (worker unreachable / network down /
            // proxy mid-transition; download()/upload() return ok=false rather than
            // throw). We deliberately DON'T advance lastSyncMs (so a transient blip
            // doesn't burn the 2.5 h window — audit network #5b), but we also must not
            // retry every single tick: the old code did exactly that, and with a
            // months-old lastSyncMs success the SYNC_MS guard never short-circuited, so
            // a permanently-unreachable worker was retried as fast as the ~30 s block
            // allowed, forever (the production block-retry storm). Park the next attempt
            // ~SYNC_RETRY_BACKOFF_MS out instead — the due-gate in maybeSync respects
            // nextSyncAttemptMs. NOT persisted to UCM: this is an in-memory cooldown, so
            // it costs no config rewrite and naturally resets on daemon restart (where a
            // fresh attempt is reasonable anyway).
            nextSyncAttemptMs = now + SYNC_RETRY_BACKOFF_MS
        }
    }

    /**
     * Read the overlay's confirm verdict (roadSense.pendingConfirmResult), and if
     * it's new + matches our pending detection, record the ground-truth label
     * (R-DET-7) and mark the stored hazard human-verified (D-012/D-025).
     */
    private fun consumePendingConfirmResult(now: Long) {
        val pending = pendingConfirm ?: return
        // forceReload ONCE here (only reached when a confirm is pending): the verdict
        // is written by the APP UID, so the daemon needs a fresh cross-UID read to
        // see it. This is the only forceReload on the tick, and only while awaiting.
        val result = try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload()
                .optJSONObject("roadSense")?.optJSONObject("pendingConfirmResult")
        } catch (_: Throwable) { null }

        // Find a fresh verdict for THIS pending detection, if any.
        val verdict = result?.takeIf {
            it.optLong("ts", 0L) > lastConfirmResultTs && it.optString("id") == pending.hazardId
        }

        if (verdict == null) {
            // G-7 auto-accept: the confirm card must never demand interaction while
            // driving. If no verdict arrived within CONFIRM_TIMEOUT_MS, dismiss the card.
            //
            // Session-35 FIX (the user drives and almost never taps): the old path recorded
            // confirmed=true AND called markHumanVerified — i.e. "the user did NOTHING" was
            // logged as "the human agreed" AND promoted the row to human-verified/locally-
            // confirmed. That (a) fabricated circular ground truth (algo grading itself —
            // ~91 of 235 rows on the last drive) and (b) DEFEATED the MINOR-consensus chime
            // gate, which treats humanVerified/locally-confirmed as "corroborated → chime".
            // So in Calibration Mode every ignored first-pass minor FP would still chime.
            //
            // Now: record the label as AUTO (auto_accepted=1) so the analysis/fit can
            // EXCLUDE it, and DO NOT markHumanVerified — auto-accept is not a human verdict
            // and must not promote the row. The detection is already stored as a normal
            // candidate by handleCandidate; it still earns confirmation the honest way
            // (observations≥2 across passes / a real human tap / cloud consensus).
            if (now - pending.shownMs >= CONFIRM_TIMEOUT_MS) {
                groundTruth.record(
                    hazardId = pending.hazardId,
                    candidate = pending.candidate,
                    algoType = pending.assessment.type,
                    algoSeverity = pending.assessment.severity,
                    algoConfidence = pending.assessment.confidence,
                    confirmed = true,        // recorded as the algo's own (auto) label…
                    userSeverity = null,
                    userType = null,
                    nowMs = now,
                    lat = pending.lat,
                    lng = pending.lng,
                    autoAccepted = true,     // …but flagged AUTO so it's excluded from the fit
                )
                // NOTE: intentionally NO markHumanVerified here (was the poisoning bug).
                pendingConfirm = null
                visualSink.setPendingConfirm(null)
                Log.i(TAG, "calibration auto-dismiss (timeout) id=${pending.hazardId} → AUTO label (not human-verified)")
                plog.info("calibration auto-dismiss (timeout) id=${pending.hazardId} (auto label, not promoted)")
            }
            return
        }

        lastConfirmResultTs = verdict.optLong("ts", 0L)
        val id = verdict.optString("id")
        val confirmed = verdict.optBoolean("confirmed", true)
        // Optional user corrections (R-OVL-6): severity bucket 1..3 and/or type
        // ordinal. Absent keys (or <0 sentinels) mean "no correction — accept algo".
        val userSeverity = verdict.optInt("severity", -1).takeIf { it in 1..3 }
        val userType = verdict.optInt("type", -1).takeIf { it >= 0 }
        val userSeverityEnum = userSeverity?.let {
            com.overdrive.app.roadsense.detect.Severity.values().firstOrNull { s -> s.level == it }
        }
        val userTypeEnum = userType?.let {
            com.overdrive.app.roadsense.detect.HazardType.values().getOrNull(it)
        }

        // Persist the ground-truth label (raw features + algo assessment + verdict +
        // any user corrections).
        groundTruth.record(
            hazardId = pending.hazardId,
            candidate = pending.candidate,
            algoType = pending.assessment.type,
            algoSeverity = pending.assessment.severity,
            algoConfidence = pending.assessment.confidence,
            confirmed = confirmed,
            userSeverity = userSeverityEnum,
            userType = userTypeEnum,
            lat = pending.lat,
            lng = pending.lng,
            nowMs = now,
        )
        // Reflect the verdict in the hazard store (instant local-confirm or remove),
        // applying any severity/type correction on confirm.
        store.markHumanVerified(
            pending.hazardId, confirmed,
            if (confirmed) userSeverity else null,
            if (confirmed) userType else null,
            now,
        )
        pendingConfirm = null
        visualSink.setPendingConfirm(null)
        Log.i(TAG, "calibration verdict id=$id confirmed=$confirmed sevΔ=$userSeverity typeΔ=$userType → label recorded")
    }

    // ── Raw-IMU recorder (D-036, debug) ────────────────────────────────────────

    /**
     * Start/stop the raw recorder to match the rawRecord flag (tick thread). The IPC hot path
     * reads only the @Volatile [rawRecord] boolean; actual file open/close happens here, off
     * the 100 Hz path. We flip rawRecord true ONLY after a successful start() (a failed open —
     * e.g. low disk — leaves it off so the hot path stays a clean no-op).
     */
    private fun maybeToggleRawRecorder(wantOn: Boolean) {
        // The writer may have AUTO-STOPPED at the size cap while rawRecord was still true.
        // Reconcile: flip our flag off so the hot path stops offering, and log why. The user
        // re-arms (toggle rawRecord off→on) for a fresh capped file if they need more.
        if (rawRecord && rawRecorder.capped) {
            rawRecord = false
            plog.info("raw IMU recorder auto-stopped at size cap (written=${rawRecorder.rowsWritten}) → ${rawRecorder.path}")
            return
        }
        if (wantOn == rawRecord) return
        if (wantOn) {
            val p = try { rawRecorder.start() } catch (t: Throwable) { Log.w(TAG, "rawRec start: ${t.message}"); null }
            rawRecord = (p != null)
            if (rawRecord) { lastRawMarkTs = clock(); plog.info("raw IMU recorder ON → $p") }
        } else {
            try { rawRecorder.stop() } catch (_: Throwable) {}
            rawRecord = false
            plog.info("raw IMU recorder OFF (queued=${rawRecorder.rowsQueued} written=${rawRecorder.rowsWritten} dropped=${rawRecorder.rowsDropped})")
        }
    }

    /**
     * Consume a one-shot ground-truth MARK from roadSense.rawMark — "a REAL hazard was HERE",
     * the recall anchor (replay matches marks against detections). The web/adb writes a JSON
     * {ts, label}; we record one M row per NEW ts at the current pose. forceReload because the
     * APP UID writes it; only reached while recording, so the cross-UID read cost is rare.
     */
    private fun maybeConsumeRawMark(now: Long) {
        val mk = try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload()
                .optJSONObject("roadSense")?.optJSONObject("rawMark")
        } catch (_: Throwable) { null } ?: return
        val ts = mk.optLong("ts", 0L)
        if (ts <= lastRawMarkTs) return
        lastRawMarkTs = ts
        val pose = locationSource.latest(now)
        rawRecorder.mark(mk.optString("label", "hazard"), pose, lastSeenSpeedKmh)
    }

    // ── ImuStream.Listener (pipeline hot path, IPC reader thread) ──────────────

    override fun onAccel(sample: ImuAccelSample) {
        // HOT PATH — runs at ~100 Hz. Keep allocations + syscalls minimal (R-PERF-1).
        // One clock() read per sample; the cached snapshots below avoid re-fetching
        // (and re-allocating) vehicle/GPS data that only refreshes every few seconds.
        val nowWall = clock()

        // 1) gravity removal → vertical residual
        val aVert = gravity.update(sample.ax, sample.ay, sample.az)

        // 2) refresh the cached vehicle-dynamics snapshot on a throttle. The
        //    underlying BydDataCollector only polls ~5 s (F-011), so fetching it
        //    every 100 Hz sample would allocate a VehicleDynamics 100×/s for no new
        //    data. Cache it; refresh ~2 Hz. Same for GPS.
        if (nowWall - lastVehiclePollMs > VEHICLE_POLL_MS) {
            lastVehiclePollMs = nowWall
            cachedDynamics = vehicleSource.latest(nowWall)
            // Refresh the master-enabled flag on the same throttle (config is
            // file-backed; reading it per-100Hz-sample would be wasteful I/O).
            featureEnabled = RoadSenseConfig.snapshot().enabled
        }
        if (nowWall - lastGpsPollMs > GPS_POLL_MS) {
            lastGpsPollMs = nowWall
            locationSource.latest(nowWall)?.let { gpsBuffer.add(it) }
        }
        val dyn = cachedDynamics

        // 3) speed (from the reused snapshot; fall back to GPS speed if vehicle
        //    data isn't ready). Feeds detector threshold + calibration gating.
        val speedKmh = dyn?.speedKmh ?: (gpsBuffer.latest()?.speedMps?.times(3.6f) ?: 0f)
        val speedMps = speedKmh / 3.6f
        lastSeenSpeedKmh = speedKmh   // for the persistent IMU-flow heartbeat

        // 4) feed the silent vehicle calibrator (quiet samples only — it self-gates)
        calibrator.onSample(aVert, speedKmh, detector.inEvent, eventsPerSec())

        // 4.5) SOTA hazard-classifier context (D-038): maintain a decimated ~20 Hz ring of
        //      speed/brake/accel/signed-pitch around now, and feed the pitch-sign calibrator.
        //      Decimated (not 100 Hz) — the crossing envelope + couplet are seconds/100s-of-ms
        //      scale; the ring stays tiny. The sign calibrator learns "nose-down = which sign"
        //      from confident braking (physics), making the breaker/pothole couplet ORDER usable.
        if (sample.tMs - lastContextSampleMs >= HazardClassifier.DECIMATE_MS) {
            lastContextSampleMs = sample.tMs
            val speedDropping = !lastCtxSpeedKmh.isNaN() && speedKmh < lastCtxSpeedKmh - 0.1f
            val brakePct = dyn?.brakePercent ?: 0
            // anchor the pitch sign from braking dive (uses RAW signed pitch — the calibrator
            // only runs pre-latch, where latestPitchRate is raw since orientation is still 1).
            // Pass current |yaw| (peakYaw) so cornering-contaminated braking can't latch a wrong
            // sign (RS-3).
            pitchSign.onSample(
                pitchRate = if (latestPitchValid || gravity.pitchAxisReady) latestPitchRate else 0f,
                pitchValid = gravity.pitchAxisReady,
                brakePercent = brakePct,
                speedDropping = speedDropping,
                yawRateRps = peakYaw,
            )
            contextRing.addLast(
                HazardClassifier.ContextSample(
                    tMs = sample.tMs,
                    speedKmh = speedKmh,
                    brakePercent = brakePct,
                    accelPercent = dyn?.accelPercent ?: 0,
                    pitchRate = latestPitchRate,
                    pitchValid = latestPitchValid,
                )
            )
            // bound the ring to the context window (a little extra for the post-event half)
            val cutoff = sample.tMs - (HazardClassifier.CONTEXT_WINDOW_MS * 2)
            while (contextRing.isNotEmpty() && contextRing.first().tMs < cutoff) contextRing.removeFirst()
            lastCtxSpeedKmh = speedKmh
        }

        // 5) raw-IMU recorder (D-036, debug). Capture EVERY accel sample + its derived
        //    features BEFORE the detector's gate/early-return — recall measurement needs the
        //    full stream around a missed breaker, not just the samples that became events.
        //    Non-blocking + size-capped (RawImuRecorder); a no-op when rawRecord is off.
        if (rawRecord) {
            rawRecorder.recordAccel(
                sample.tMs, sample.ax, sample.ay, sample.az,
                aVert, gravity.lateralResidual, gravity.longitudinalResidual, speedKmh,
                dyn, gpsBuffer.latest(), detector.inEvent,
            )
        }

        // 6) detection (uses the sensor sample's own tMs, not wall-clock). Pass
        //    the gravity-free LATERAL + LONGITUDINAL residuals from the SAME
        //    projection so the detector can derive lateral asymmetry (one-sided vs
        //    full-width) while gating out fore-aft brake/launch energy (audit #6).
        val candidate = detector.onSample(
            VerticalSample(
                sample.tMs, aVert, speedMps,
                gravity.lateralResidual, gravity.longitudinalResidual,
            )
        ) ?: return
        handleCandidate(candidate, dyn, nowWall)
    }

    override fun onGyro(sample: ImuGyroSample) {
        // Track peak yaw/roll over the (current) event window for rejection. Yaw =
        // rotation ABOUT TRUE VERTICAL (a turn); that is the signal RejectionFilter
        // Rule 3 keys on. The old proxy used max(|gx|,|gy|,|gz|) as "yaw", but on the
        // 9.2°-tilted mount a sharp transverse BUMP fires its own pitch/roll-rate
        // transient onto a non-vertical axis, which became maxC and false-rejected the
        // genuine straight-line hazard as "cornering" — worst for exactly the severe,
        // sharp-edged hazards (audit: gyro-yaw false-reject). Fix: project the gyro
        // vector onto the MEASURED gravity unit vector to isolate the true yaw rate
        // about vertical (allocation-free, hot-path safe).
        val yaw = abs(gravity.alongGravity(sample.gx, sample.gy, sample.gz))
        // Age out a peak older than ~one event window so a turn made before the
        // current jolt no longer dominates (audit detection #1). Uses the sensor
        // sample's own tMs (same clock the detector/rate window use), not wall-clock.
        val tMs = sample.tMs
        if (tMs - peakYawTMs > GYRO_PEAK_WINDOW_MS) peakYaw = 0f
        if (yaw >= peakYaw) { peakYaw = yaw; peakYawTMs = tMs }

        // AXIS-RESOLVED pitch/roll peaks for the breaker-vs-pothole classifier vote
        // (separate from the combined-roll proxy above; see field docs). Same age-out
        // window so only rotation concurrent with the jolt votes. pitchAxisReady gates
        // both — when the longitudinal axis isn't established the split is meaningless.
        if (gravity.pitchAxisReady) {
            val signedPitch = gravity.pitchRate(sample.gx, sample.gy, sample.gz)
            val pitch = abs(signedPitch)
            val trueRoll = abs(gravity.rollRate(sample.gx, sample.gy, sample.gz))
            if (tMs - peakPitchTMs > GYRO_PEAK_WINDOW_MS) peakPitch = 0f
            if (tMs - peakTrueRollTMs > GYRO_PEAK_WINDOW_MS) peakTrueRoll = 0f
            if (pitch >= peakPitch) { peakPitch = pitch; peakPitchTMs = tMs }
            if (trueRoll >= peakTrueRoll) { peakTrueRoll = trueRoll; peakTrueRollTMs = tMs }
            pitchEverValid = true
            // Hand the SIGNED pitch (in +nose-up convention once the sign is anchored) to the
            // onAccel context sampler for the HazardClassifier (D-038).
            val orientedPitch = signedPitch * (if (pitchSign.anchored) pitchSign.orientation else 1)
            latestPitchRate = orientedPitch
            latestPitchValid = pitchSign.anchored
            // Peak-hold the signed couplet extrema at FULL 100 Hz (couplet-decimation fix): the
            // strongest +nose-up and −nose-down lobes + their times, so the classifier's couplet
            // sees true extrema instead of the aliased 16 Hz context ring. Oriented convention so
            // +=nose-up once anchored; aged out per GYRO_PEAK_WINDOW_MS like the peaks above.
            if (tMs - peakPitchUpTMs > GYRO_PEAK_WINDOW_MS) peakPitchUp = 0f
            if (tMs - peakPitchDownTMs > GYRO_PEAK_WINDOW_MS) peakPitchDown = 0f
            if (orientedPitch > peakPitchUp) { peakPitchUp = orientedPitch; peakPitchUpTMs = tMs }
            if (-orientedPitch > peakPitchDown) { peakPitchDown = -orientedPitch; peakPitchDownTMs = tMs }
        } else {
            // Axis not established → clear the value too (STALE-PITCH-1): keeping the last real
            // pitch and stamping it onto context samples with pitchValid=false is inconsistent
            // hygiene (and the pitchSign feed already guards it). 0 is the honest "unknown".
            latestPitchRate = 0f
            latestPitchValid = false
        }

        // Raw-IMU recorder (D-036, debug): capture the raw gyro vector so pitch/roll can be
        // re-derived offline with different params. Non-blocking; no-op when off.
        if (rawRecord) rawRecorder.recordGyro(sample.tMs, sample.gx, sample.gy, sample.gz)
    }

    override fun onStreamState(state: ImuStream.State) {
        Log.d(TAG, "IMU stream state=$state")
        // (Pitch-sign anchor is NOT reset here: DaemonImuStream fires ACTIVE on every
        //  start()/OFF→DRIVING re-entry, so resetting on ACTIVE would re-cold the anchor at
        //  every stop light. The mount is drive-stable; a bad anchor is instead prevented at
        //  the source by PitchSignCalibrator's cornering guard (RS-3), and fully re-anchors on
        //  a true process/sensor restart via reset().)
    }

    // ── Candidate → assessment → store ─────────────────────────────────────────

    private fun handleCandidate(rawCandidate: DetectionCandidate, dyn: VehicleDynamics?, now: Long) {
        // Fuse the axis-resolved gyro pitch/roll peaks (the user's "up-down gyro
        // movement" signal) onto the candidate so SeverityClassifier can vote
        // breaker-vs-pothole on body PITCH (full-width breaker) vs ROLL (one-sided
        // pothole). Captured HERE, before resetEventGyro() clears the peaks. The peaks
        // are tracked over GYRO_PEAK_WINDOW_MS around the jolt (onGyro), so they reflect
        // rotation concurrent with THIS event. pitchValid mirrors asymmetryValid:
        // false ⇒ classifier ignores the pitch vote (longitudinal axis wasn't ready).
        val candidate = rawCandidate.copy(
            peakPitchRate = if (pitchEverValid) peakPitch else 0f,
            peakRollRate = if (pitchEverValid) peakTrueRoll else 0f,
            pitchValid = pitchEverValid,
            // Full-resolution signed couplet extrema for the classifier (couplet-decimation fix).
            peakPitchUp = if (pitchEverValid) peakPitchUp else 0f,
            peakPitchUpTMs = if (pitchEverValid) peakPitchUpTMs else 0L,
            peakPitchDown = if (pitchEverValid) peakPitchDown else 0f,
            peakPitchDownTMs = if (pitchEverValid) peakPitchDownTMs else 0L,
        )
        trackCandidateRate(candidate.tMs)
        // Master toggle: if the feature is disabled we still let the detector +
        // calibrator run (cheap, keeps calibration warm) but we map nothing.
        if (!featureEnabled) { resetEventGyro(); return }

        // Rejection needs vehicle dynamics; if unavailable we can't safely confirm
        // it wasn't a brake/turn artefact, so be conservative and skip mapping.
        if (dyn == null) {
            dropNoDyn++
            resetEventGyro(); return
        }
        // CONTEXT vetoes only (softPedal=true): gear / cornering / washboard / curb are
        // unambiguous regardless of road feature, so reject them NOW. The brake/accel hard
        // vetoes are SKIPPED here and handed to the HazardClassifier's soft artifact term
        // (D-040) so braking INTO a real breaker — the user's exact scenario — isn't dropped.
        val gyroStats = GyroStats(peakYawRateRps = peakYaw)
        val verdict = rejection.evaluate(candidate, dyn, gyroStats, eventsPerSec(), softPedal = true)
        resetEventGyro()
        if (verdict.rejected) {
            if (verdict.reason == "washboard") {
                maybeMapRoughSection(candidate, now)
            } else {
                Log.d(TAG, "rejected: ${verdict.reason}")
            }
            dropRejects[verdict.reason ?: "?"] = (dropRejects[verdict.reason ?: "?"] ?: 0) + 1
            return
        }

        // DEFER (D-040): the HazardClassifier is the PRIMARY accept/type decision, but its
        // crossing envelope needs the POST-event half (recover-after) which hasn't happened
        // yet. So enqueue; drainPendingClassifications() (called from onImuBatch on this same
        // IPC thread) runs the classifier ~CLASSIFY_DEFER_MS later with the full context ring,
        // and does the accept→classify→store→confirm-card work in finalizeClassification().
        pendingClassify.addLast(PendingClassify(candidate, dyn, candidate.tMs, now))
        // Bound the queue defensively (a stall shouldn't grow it unbounded).
        while (pendingClassify.size > MAX_PENDING_CLASSIFY) pendingClassify.removeFirst()
    }

    /**
     * Drain deferred classifications whose POST-event envelope has now elapsed (D-040). Called
     * from onImuBatch on the single IPC/sensor thread, so it shares the contextRing/store
     * single-writer contract. [latestSensorTMs] is the newest sample's sensor clock; an item
     * is ready once that clock has advanced [CLASSIFY_DEFER_MS] past the event peak (so the
     * recover-after half is in the ring). On a sensor gap/stall, items also drain by wall-clock
     * age so they can't get stuck forever.
     */
    private fun drainPendingClassifications(latestSensorTMs: Long, nowWall: Long, force: Boolean = false) {
        // SCAN the whole queue (not break-on-front): the front-is-earliest assumption breaks if
        // a backward wall-clock step (NTP/manual set) makes a later candidate's enqueuedTMs
        // smaller (RS-DEFER-4). Scanning finalizes every ready item regardless of order; cheap
        // (queue ≤ MAX_PENDING_CLASSIFY). force=true (leaving DRIVING / shutdown) finalizes ALL
        // outstanding so none is stranded (RS-DEFER-1). An item is also ready if its event tMs is
        // IMPLAUSIBLY ahead of the latest sample (backward jump) — finalize rather than wait out
        // the wall fallback.
        val it = pendingClassify.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val sensorReady = latestSensorTMs - p.enqueuedTMs >= CLASSIFY_DEFER_MS
            val wallFallback = nowWall - p.enqueuedWall >= CLASSIFY_DEFER_MS * 3
            val backwardJump = p.enqueuedTMs - latestSensorTMs > CLASSIFY_DEFER_MS
            if (!force && !sensorReady && !wallFallback && !backwardJump) continue
            it.remove()
            try { finalizeClassification(p, nowWall) }
            catch (t: Throwable) { Log.w(TAG, "finalizeClassification error: ${t.message}") }
        }
    }

    /**
     * PRIMARY accept/type/store decision for one deferred candidate (D-040). The
     * HazardClassifier fuses the full ±window envelope (now complete) — its `accept` is the
     * gate (replacing the old confidence-free "store everything") and its `type` is
     * authoritative. SeverityClassifier still supplies severity + confidence + normalizedPeak.
     */
    private fun finalizeClassification(p: PendingClassify, now: Long) {
        val candidate = p.candidate
        // PRIMARY gate: temporal multi-sensor fusion over the now-complete envelope.
        val v = hazardClassifier.classify(candidate, contextRing.toList(), candidate.tMs)
        if (!v.accept) {
            dropRejects["classifier"] = (dropRejects["classifier"] ?: 0) + 1
            Log.d(TAG, "classifier reject realness=${"%.2f".format(v.realnessScore)} " +
                "pitch=${"%.2f".format(v.pitchScore)} cross=${"%.2f".format(v.crossingScore)} " +
                "artifact=${"%.2f".format(v.artifactScore)}")
            return
        }

        // Severity / confidence still from SeverityClassifier (amplitude→bucket, vehicle-cal).
        val pose = gpsBuffer.backProject(candidate.tMs, GpsRingBuffer.DEFAULT_FIX_LATENCY_MS)
        val gpsAcc = pose?.accuracyM ?: 999f
        val sev = classifier.classify(
            candidate = candidate,
            vehicleScale = calibrator.vehicleScale,
            calibrationMaturity = calibrator.maturity,
            gpsAccuracyM = gpsAcc,
        )
        if (pose == null) {
            Log.d(TAG, "no pose to localize hazard; skipping store")
            dropNoPose++
            return
        }
        // TYPE comes from the fusion classifier (pitch couplet leads); severity from sev.
        dropStored++
        val hazard = RoadSenseHazard(
            lat = pose.lat,
            lng = pose.lng,
            type = v.type,
            severity = sev.severity,
            headingDeg = validHeading(pose.bearingDeg, candidate.speedMps),
            confidence = sev.confidence,
            speedKmh = candidate.speedMps * 3.6f,
            aVertPeak = sev.normalizedPeak,
            tMs = candidate.tMs,
            altitudeM = pose.altitudeM,
        )
        val id = store.upsertDetection(hazard, now)
        val msg = "hazard ${v.type}/${sev.severity} realness=${"%.2f".format(v.realnessScore)} " +
            "${if (v.pitchCoupletUsed) (if (v.pitchCoupletSign < 0) "pitch:pot " else "pitch:brk ") else ""}" +
            "cross=${"%.2f".format(v.crossingScore)} conf=${"%.2f".format(sev.confidence)} " +
            "@${pose.lat},${pose.lng} id=$id"
        Log.i(TAG, msg)
        plog.info(msg)

        // Calibration Mode (R-OVL-6/D-025): surface the (now finalized) detection for optional
        // human confirm/correct. Pre-fills with the classifier's type + severity's bucket.
        // RS-DEFER-2: the deferred drain can finalize MULTIPLE candidates in one pass, but
        // pendingConfirm is a single slot. If a card is already outstanding, DON'T clobber it
        // (the first hazard would lose its card + label) — instead record THIS hazard's label
        // immediately as auto (so it still gets a ground-truth row), and leave the earlier
        // card's slot intact. Both hazards are already stored/mapped above regardless.
        if (calibrationMode) {
            val assess = sev.copy(type = v.type)
            if (pendingConfirm == null) {
                pendingConfirm = PendingLabel(id, candidate, assess, pose.lat, pose.lng, now)
                visualSink.setPendingConfirm(
                    OverlayState.PendingConfirm(
                        hazardId = id,
                        algoType = v.type.ordinal,
                        algoSeverity = sev.severity.level,
                        algoConfidence = sev.confidence,
                    )
                )
            } else {
                // A card is still up for an earlier hazard — record this one's label as AUTO
                // now rather than dropping it (excluded from the fit, but not lost).
                groundTruth.record(
                    hazardId = id, candidate = candidate,
                    algoType = v.type, algoSeverity = sev.severity, algoConfidence = sev.confidence,
                    confirmed = true, userSeverity = null, userType = null,
                    lat = pose.lat, lng = pose.lng, nowMs = now, autoAccepted = true,
                )
            }
        }
    }

    private fun resetEventGyro() {
        peakYaw = 0f; peakYawTMs = 0L
        peakPitch = 0f; peakPitchTMs = 0L; peakTrueRoll = 0f; peakTrueRollTMs = 0L
        peakPitchUp = 0f; peakPitchUpTMs = 0L; peakPitchDown = 0f; peakPitchDownTMs = 0L
        pitchEverValid = false
    }

    /**
     * Heading to STORE for a hazard, or a sentinel when it's untrustworthy (audit
     * accuracy S1). GPS bearing is only meaningful while moving; below the heading-
     * reliable floor Location.getBearing() defaults to 0° (due north), which would
     * be stored as a real heading and then mis-drive the ApproachEngine road-match
     * gate (suppressing real hazards on E-W roads, admitting wrong-road ones on N-S).
     * Store [HEADING_UNKNOWN] instead so the gate treats it as "road identity
     * unknown" and falls back to the cone-only test rather than a bogus 0°. */
    private fun validHeading(bearingDeg: Float, speedMps: Float): Float =
        if (speedMps > HEADING_RELIABLE_MPS) bearingDeg else HEADING_UNKNOWN

    /**
     * D-032: map a SUSTAINED washboard/cobble stretch as a single ROUGH_SECTION
     * hazard (one warning for the stretch), rather than discarding it or chiming on
     * every cobble. Throttled by [ROUGH_SECTION_MIN_GAP_MS] so a long rough run maps
     * ~one row at its start; the store's same-spot/zone logic groups repeats. Severity
     * is MINOR by default (washboard is annoyance, not a wheel-breaker) and bumped to
     * MODERATE only if the individual jolt was itself large. Confidence is modest —
     * it's a texture call, not a clean biphasic pulse. Runs on the IPC/sensor thread.
     */
    private fun maybeMapRoughSection(candidate: DetectionCandidate, now: Long) {
        // Use the event tMs for the throttle (same clock the rate window uses).
        if (candidate.tMs - lastRoughSectionMs < ROUGH_SECTION_MIN_GAP_MS) return
        if (!featureEnabled) return
        val pose = gpsBuffer.backProject(candidate.tMs, GpsRingBuffer.DEFAULT_FIX_LATENCY_MS) ?: return
        lastRoughSectionMs = candidate.tMs
        val rawJolt = maxOf(abs(candidate.peakUp), abs(candidate.peakDown))
        // Speed- + vehicle-normalize the jolt the SAME way SeverityClassifier does
        // (audit detection #8): a_vert_peak must be a road property comparable across
        // rows. Storing the RAW device-frame jolt here while every other row stores
        // the normalized peak made the column incomparable (mergeInto takes max
        // across rows) and made ROUGH_SECTION_MODERATE_JOLT speed-dependent — the
        // same washboard reading MINOR at low speed, MODERATE at high speed.
        val v = candidate.speedMps.coerceIn(
            SeverityClassifier.MIN_NORM_SPEED_MPS, SeverityClassifier.MAX_NORM_SPEED_MPS
        )
        val speedFactor = kotlin.math.sqrt(SeverityClassifier.REFERENCE_SPEED_MPS / v)
        val safeVehicleScale = calibrator.vehicleScale.let { if (it > 1e-3f) it else 1f }
        val jolt = rawJolt * speedFactor / safeVehicleScale
        val severity = if (jolt >= ROUGH_SECTION_MODERATE_JOLT)
            com.overdrive.app.roadsense.detect.Severity.MODERATE
        else com.overdrive.app.roadsense.detect.Severity.MINOR
        val hazard = RoadSenseHazard(
            lat = pose.lat,
            lng = pose.lng,
            type = HazardType.ROUGH_SECTION,
            severity = severity,
            headingDeg = validHeading(pose.bearingDeg, candidate.speedMps),
            confidence = ROUGH_SECTION_CONFIDENCE,
            speedKmh = candidate.speedMps * 3.6f,
            aVertPeak = jolt,
            tMs = candidate.tMs,
            altitudeM = pose.altitudeM,
        )
        val id = store.upsertDetection(hazard, now)
        Log.i(TAG, "rough_section sev=$severity @${pose.lat},${pose.lng} id=$id")
    }

    // Rolling candidate-rate window for washboard rejection (R-DET-5).
    private fun trackCandidateRate(tMs: Long) {
        recentCandidateMs.addLast(tMs)
        val cutoff = tMs - WASHBOARD_WINDOW_MS
        while (recentCandidateMs.isNotEmpty() && recentCandidateMs.first() < cutoff) {
            recentCandidateMs.removeFirst()
        }
    }

    private fun eventsPerSec(): Float =
        recentCandidateMs.size.toFloat() / (WASHBOARD_WINDOW_MS / 1000f)

    // ── Speed-adaptive cornering-yaw config plumbing ──────────────────────────
    /** The four corner-yaw knobs, captured so we can rebuild [rejection] only when
     *  they actually change (cheap equality vs rebuilding every poll). */
    private data class YawCfg(
        val adaptive: Boolean,
        val minRadiusM: Float,
        val floorRps: Float,
        val ceilRps: Float,
    )

    private fun yawCfgOf(cfg: RoadSenseConfig.Snapshot) = YawCfg(
        adaptive = cfg.cornerYawSpeedAdaptive,
        minRadiusM = cfg.cornerMinRadiusM,
        floorRps = cfg.cornerYawFloorRps,
        ceilRps = cfg.cornerYawCeilRps,
    )

    private fun buildRejectionFilter(cfg: RoadSenseConfig.Snapshot): RejectionFilter {
        lastYawCfg = yawCfgOf(cfg)
        return RejectionFilter(
            cornerMinRadiusM = cfg.cornerMinRadiusM,
            yawRejectFloorRps = cfg.cornerYawFloorRps,
            yawRejectCeilRps = cfg.cornerYawCeilRps,
            speedAdaptiveYaw = cfg.cornerYawSpeedAdaptive,
        )
    }

    /**
     * Push the user's detection-sensitivity slider into the detector when it changed.
     * Called from the ~2 Hz vehicle poll (off the 100 Hz IMU path). Unlike the yaw
     * knobs this does NOT rebuild anything — [EventDetector.setThresholdScale] only
     * assigns a @Volatile multiplier the hot path reads per sample, so the change is
     * live, restart-free, and cannot disturb an in-flight event or the detector's
     * ring-buffer/debounce state. Re-pushes only on an actual change (NaN seed forces
     * the first push) so a steady slider costs nothing.
     */
    private fun maybeApplyDetectionSensitivity(cfg: RoadSenseConfig.Snapshot) {
        val want = cfg.detectionSensitivity
        if (!lastDetectionSensitivity.isNaN() && lastDetectionSensitivity == want) return
        val applied = detector.setThresholdScale(want)
        lastDetectionSensitivity = applied
        plog.info("detection sensitivity → ${"%.2f".format(applied)} (×threshold)")
    }

    /** Rebuild the (stateless) RejectionFilter if the corner-yaw config changed.
     *  Called from the ~2 Hz vehicle/warning ticks — off the 100 Hz IMU path. */
    private fun maybeRebuildRejection(cfg: RoadSenseConfig.Snapshot) {
        val now = yawCfgOf(cfg)
        if (now != lastYawCfg) {
            rejection = buildRejectionFilter(cfg)
            plog.info("RejectionFilter rebuilt: cornerYaw adaptive=${now.adaptive} " +
                "Rmin=${now.minRadiusM} floor=${now.floorRps} ceil=${now.ceilRps}")
        }
    }

    companion object {
        private const val TAG = "RoadSense/Controller"
        private const val GPS_POLL_MS = 500L
        /** Vehicle-dynamics cache refresh (~2 Hz). Source only updates ~5 s (F-011),
         *  so this is generous; keeps brakeAgeMs fresh enough for rejection. */
        private const val VEHICLE_POLL_MS = 500L
        /** GPS bearing is noise below ~walking pace; above this we trust heading
         *  for the ApproachEngine forward-cone + road-match (below it, the engine
         *  suppresses warnings entirely rather than warn direction-blind). */
        private const val HEADING_RELIABLE_MPS = 2.5f
        /** Sentinel stored heading meaning "heading was unreliable at detection" — the
         *  ApproachEngine road-match gate skips the heading test for these (audit S1). */
        const val HEADING_UNKNOWN = -1f
        /** Self-driven housekeeping tick: ~2 Hz (regime poll + warning eval). */
        private const val TICK_MS = 500L
        /** Persistent IMU-flow heartbeat cadence (diagnostics). 30 s keeps the daemon
         *  log small over a long drive while still proving the pipeline ran. */
        private const val IMU_HEARTBEAT_MS = 30_000L
        /** How often to persist calibration to UCM (per-vehicle, slow-changing). 60 s
         *  is plenty — a lost minute of learning is trivial, and it keeps the full-file
         *  config rewrite rare. */
        private const val CAL_PERSIST_MS = 60_000L
        /** How often to persist route coverage (only when a new tile was entered). */
        private const val COVERAGE_PERSIST_MS = 30_000L
        /** Min gap between sidecar stall-recovery relaunches. A freshly-`am`-launched
         *  app FGS takes a beat to bind, request sensors, and stream the first batch;
         *  5 s lets it come up before we'd consider relaunching again, so a transient
         *  GC pause or one dropped batch run can't trigger a relaunch storm. */
        private const val SIDECAR_RELAUNCH_MS = 5_000L
        /** G-7 auto-accept timeout for the Calibration-Mode confirm card: if the
         *  driver hasn't tapped confirm/reject within this window, the card auto-
         *  dismisses and the algorithm's own assessment is recorded as the label, so
         *  it never demands interaction while driving. 8 s ≈ glanceable without
         *  nagging; checked on the ~2 Hz warn tick (so effective resolution ~0.5 s). */
        private const val CONFIRM_TIMEOUT_MS = 8_000L
        /** Crowdsource sync cadence — road hazards don't change minute-to-minute,
         *  so syncing every ~2.5 h (plus once shortly after the first DRIVING tick
         *  of an ACC session) is plenty and keeps the free-tier cost trivial (G-6).
         *  Persisted across daemon restarts so a reboot doesn't force a re-sync. */
        private const val SYNC_MS = 150 * 60 * 1000L  // 2.5 hours
        /** When the server signals more download pages pending, re-sync this soon
         *  instead of waiting the full cadence — drains a cold/dense first sync over
         *  a few quick ticks, then settles back to SYNC_MS. */
        private const val SYNC_PAGE_DRAIN_MS = 15_000L
        /** Failure backoff: when a sync attempt makes NO progress (worker unreachable,
         *  network down), park the NEXT attempt this far out rather than retrying every
         *  tick. Distinct from the 2.5 h SUCCESS cadence (SYNC_MS): we still want to
         *  retry transient failures sooner than 2.5 h (audit network #5b), but ~5 min is
         *  far enough apart to avoid the every-tick block-retry storm a persistently-
         *  unreachable worker used to cause. Each failed attempt is one blocking
         *  download/upload (~30 s) on the off-tick syncExecutor — bounded, not free, so
         *  we don't poll it tightly. In-memory only (resets on daemon restart). */
        private const val SYNC_RETRY_BACKOFF_MS = 5 * 60 * 1000L  // 5 minutes
        /** Upload batch cap — MUST match the provider/server cap (256) so the cursor
         *  advance and the sent slice agree (audit network #1). */
        private const val MAX_UPLOAD_BATCH = 256
        /** Window for the washboard candidate-rate metric (matches RejectionFilter). */
        private const val WASHBOARD_WINDOW_MS = 2_000L
        /** Age-out horizon for the rolling gyro peak (audit detection #1). Beyond
         *  this a peak no longer reflects rotation concurrent with a jolt, so it must
         *  not vote "cornering". ~EventDetector.DEFAULT_WINDOW_MS (400) so only yaw/
         *  roll near the event window contributes. */
        private const val GYRO_PEAK_WINDOW_MS = 500L
        /** D-032: min gap between mapping rough_section rows so a sustained stretch
         *  maps ~one row at a time (then the store/zone logic groups them) rather
         *  than one per cobble. ~5 s ≈ tens of metres at urban speed. */
        private const val ROUGH_SECTION_MIN_GAP_MS = 5_000L
        /** Jolt (m/s²) at/above which a rough-section sample is mapped MODERATE
         *  rather than MINOR — a washboard with real wheel-slamming bumps in it. */
        private const val ROUGH_SECTION_MODERATE_JOLT = 4.0f
        /** Confidence for a rough_section row — modest; it's a texture/rate call,
         *  not a clean isolated biphasic pulse. Above the default warn floor (0). */
        private const val ROUGH_SECTION_CONFIDENCE = 0.5f
        /** Defer window (ms) before the HazardClassifier finalizes a candidate (D-040): how
         *  long after the event peak we wait for the POST-event "recover-after" half of the
         *  crossing envelope to elapse into the context ring. Matches HazardClassifier
         *  ENVELOPE_MS (1500). The hazard is still mapped at the SAME event tMs/pose (we only
         *  delay the DECISION, not the localization), so a ~1.5 s map latency is invisible —
         *  approach warnings fire on a future pass / the rest of the drive anyway. */
        private const val CLASSIFY_DEFER_MS = 1_500L
        /** Cap on outstanding deferred candidates — a washboard burst or a stall can't grow
         *  the queue unboundedly; oldest dropped. Real isolated hazards never approach this. */
        private const val MAX_PENDING_CLASSIFY = 32
    }
}
