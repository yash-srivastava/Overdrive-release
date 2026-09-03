package com.overdrive.app.surveillance;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.telemetry.TelemetryDataCollector;

import com.overdrive.app.camera.PanoramicCameraGpu;

import java.io.File;

/**
 * GpuSurveillancePipeline - Complete GPU Zero-Copy surveillance system.
 * 
 * Orchestrates all components of the GPU pipeline:
 * - PanoramicCameraGpu: Camera → GPU texture
 * - GpuMosaicRecorder: GPU composition → Encoder
 * - GpuDownscaler: GPU thumbnail → CPU
 * - SurveillanceEngineGpu: Motion detection & AI
 * - AdaptiveBitrateController: Quality optimization
 * 
 * Achieves <10% CPU usage through GPU zero-copy architecture.
 */
public class GpuSurveillancePipeline {
    private static final String TAG = "GpuPipeline";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // Components
    // volatile: read from worker threads (IdleShutdown, yield listener,
    // applyBatchedChange holds reconfigLock — different monitor than stop()'s
    // `this`) where the writer's monitor isn't held; volatile makes the
    // null-after-stop and swap-on-reinit visible without tearing.
    private volatile PanoramicCameraGpu camera;
    private volatile GpuMosaicRecorder recorder;  // Single recorder for both modes
    private GpuDownscaler downscaler;
    // Volatile: the sentry-teardown fixes read this from non-main threads — the
    // WS IdleShutdown thread (idle-timeout keep-alive check consults
    // sentry.isActive()) and stopRecording()'s callers (incl. the StorageManager
    // SD-card-watchdog thread). Matches the recorder/encoder/bitrateController
    // rationale above: without volatile there's no happens-before edge and a
    // reader could see a stale/partial reference (or miss the null-after-release).
    private volatile SurveillanceEngineGpu sentry;
    private volatile HardwareEventRecorderGpu encoder;  // Single encoder for recording/surveillance
    // Volatile: read from non-main threads (proximity binder/scheduler via
    // setRecordingBitrate) and nulled by stop()'s teardown body which runs
    // OUTSIDE the synchronized prologue. Without volatile there's no
    // happens-before edge and a reader could see a stale/partial reference.
    private volatile AdaptiveBitrateController bitrateController;
    
    // Streaming components (separate encoder - always available)
    private com.overdrive.app.streaming.GpuStreamScaler streamScaler;
    private HardwareEventRecorderGpu streamEncoder;
    private com.overdrive.app.streaming.WebSocketStreamServer wsStreamServer;
    // volatile so the camera GL render loop's read sees the latest
    // disable-write atomically; otherwise the loop can keep snapshot
    // streamScaler/streamEncoder past the disable cycle.
    private volatile boolean streamingEnabled = false;
    // Stream-lifecycle ReentrantLock replaces the per-instance monitor
    // (synchronized) on enableStreaming/disableStreaming/attachExternalStreamCallback.
    // ReentrantLock so we can explicitly release it around the 2-second
    // GL-thread init wait inside enableStreamingInternal — pre-fix,
    // synchronized(this) pinned every disable / attach / stop caller for
    // the entire wait. ReentrantLock + "drop around the wait" lets the
    // peers proceed while the stream init is pending; the partial-state
    // window is bounded by the existing camera.setStreamingComponents
    // invariant (called at the END of enableStreamingInternal, AFTER the
    // GL init completes) so a concurrent disable observes either both
    // components committed or neither.
    private final java.util.concurrent.locks.ReentrantLock streamLifecycleLock =
        new java.util.concurrent.locks.ReentrantLock();

    // ── Dedicated blind-spot lane (views 7/8) — NATIVE SurfaceControl path ────
    // A SECOND, independent GpuStreamScaler fed from the same camera texture via
    // PanoramicCameraGpu's PASS 1C render-loop fan-out, rendering the libod view
    // 7/8 stitch STRAIGHT onto a daemon-owned SurfaceControl layer on screen.
    // NO encoder, NO WebSocket, NO MediaCodec decoder — GPU → screen, all in the
    // daemon (UID 2000). Validated on this firmware by BsSurfaceControlSpike
    // (non-fullscreen + GL-fed + setGeometry-positioned SC layer composites).
    // Buffer is BS_WIDTH×BS_HEIGHT; on-screen rect comes from config (setGeometry),
    // since SurfaceControl layers have no InputChannel (not finger-draggable).
    private com.overdrive.app.streaming.GpuStreamScaler bsScaler;
    private com.overdrive.app.surveillance.BsNativeLayer bsLayer;
    private volatile boolean blindSpotEnabled = false;
    // True while an enableBlindSpot() is in flight (set under bsLifecycleLock
    // before entering enableBlindSpotInternal, cleared when it returns). The
    // internal init releases bsLifecycleLock around its GL-init wait; this flag
    // lets a second caller that reacquires the lock during that window detect
    // an in-flight enable that has not yet set blindSpotEnabled, and bail
    // instead of double-allocating the lane.
    private boolean bsEnabling = false;
    private volatile int bsViewMode = 7;   // 7=Rear+Left, 8=Right+Rear
    // On-screen geometry for the SC layer (panel pixels). Read from config on
    // enable; defaults to a top-right card. setBsGeometry updates it live.
    // BS-GEO-3: single atomic rect [x,y,w,h] (panel px) so a reader never sees a
    // torn quad (e.g. new x + old w) when the API thread updates it mid-read on
    // the turn/rotation thread. Writers build the 4-tuple locally + assign the
    // reference once; readers snapshot the reference once. -1s = unresolved.
    private volatile int[] bsGeomRect = new int[]{-1, -1, -1, -1};
    // Orientation-safe geometry preset: card size (% panel width) + corner. The px
    // rect is RECOMPUTED from these against the live panel on enable + rotation, so
    // position/size stay correct across portrait↔landscape. <=0 sizePct = unset.
    private volatile int bsSizePct = 40;
    private volatile String bsCorner = "tr";
    // On-screen card rotation (0/90/180/270 degrees), applied by the SurfaceControl
    // layer when it composites the fixed BS_WIDTH×BS_HEIGHT buffer. Only meaningful
    // for the single-view merge modes (side/rear); the merged "both" view is not
    // rotated (the stitched panorama is inherently landscape). Read from
    // blindspot.rotation on enable + config change. resolveBsGeometry swaps the
    // dest-rect aspect for the 90/270 cases so the scale stays uniform.
    private volatile int bsRotationDeg = 0;
    private volatile int bsLastPanelW = -1, bsLastPanelH = -1;  // for rotation detect
    // Blind-spot DISPLAY TARGET: "head_unit" (default — the 15.6" center screen,
    // layerStack 0, byte-for-byte the shipping behaviour) or "cluster" (the driver
    // gauge screen, layerStack 1, reached only while an OEM cluster projection is
    // open — driven by ClusterProjectionController). Read from UCM blindspot.target
    // on enable + retarget. Geometry is persisted PER TARGET (geometry vs
    // geometryCluster) since a card sized for the tall head-unit overflows the short
    // 1920×720 cluster.
    private volatile String bsTarget = "head_unit";
    // The cluster's layerStack is NOT fixed — SurfaceFlinger reassigns it each time
    // the projection display is (re)created (size-profile 30 → stack 1, 31 → stack 2).
    // Resolved LIVE from dumpsys in onClusterProjectionReady (BsNativeLayer
    // .clusterLayerStack); 1 is only the initial fallback before the first resolve.
    private volatile int bsClusterStack = 1;
    // Whether the layer is currently shown (turn-triggered / debug-preview gates it).
    private volatile boolean bsLayerVisible = false;
    // Daemon-side turn-trigger: reads the turn lamps (daemon owns the BYD light
    // HAL) + the blindspot.debugPreview flag on a ~250ms loop while the lane is
    // enabled, and shows/hides + side-switches the SurfaceControl layer. Replaces
    // the deleted app-process BlindSpotOverlayService tick (no app process needed).
    // Volatile: isBsGateAllowed()/getBsGateReason() read it from the HTTP thread to
    // report "no gate evaluation is running" without racing start/stopBsTurnLoop.
    private volatile java.util.concurrent.ScheduledExecutorService bsTurnExec;
    private long bsLastTurnOnMs = 0L;
    // Last tick the turn LAMP was observed lit — tracks the physical signal session
    // independently of whether the card was actually displayed. bsLastTurnOnMs freezes
    // while the conditional gate suppresses the card, so it cannot distinguish a blink
    // off-phase from a real stalk release; this can.
    private long bsLastLampOnMs = 0L;
    // Defense-in-depth latch for the map-leak fix: a turn-signal projection open is
    // a SESSION (the signal goes on, blinks, goes off). On the LEADING edge of such a
    // session — and only when no sustained map legitimately holds the projection — we
    // dismiss any ORPHANED parked cluster-map Activity (navMap.clusterMapActive=false)
    // so it can't paint under the partial BS card if its normal stop()-driven finish
    // was ever missed. Latched so we issue the (full-JSON) UCM write ONCE per session,
    // not every 250ms tick. Reset when the signal clears.
    private boolean bsDismissedOrphanMap = false;
    // User dismissed the card via the floating ✕ (StatusOverlayService). Scoped to the
    // CURRENT display session only: cleared the moment the signal session genuinely ends
    // (same !signalOn + debounce test bsDismissedOrphanMap re-arms on) so the next turn
    // signal always shows the card again. A blind-spot dismiss must never be sticky —
    // this is a safety view, and the feature stays armed (blindspot.enabled untouched).
    // Volatile: written from the HTTP thread (dismissBlindSpotCard), read on the 250ms
    // turn tick.
    private volatile boolean bsUserDismissed = false;
    private static final long BS_TURN_POLL_MS = 250L;
    private static final long BS_OFF_DEBOUNCE_MS = 800L;  // ride through blink off-phase
    // Last conditional-gate verdict (speed window / reverse), for /api/bs/status +
    // the edge-logged transition. Starts ALLOW so an un-gated config reports open.
    private volatile boolean bsGateAllowed = true;
    private volatile String bsGateReason = "";
    // Speed-bound slack applied only while the card is already shown, so a speed
    // sitting exactly on a bound can't strobe the card (and, on cluster, the gauges).
    private static final double BS_SPEED_HYST_KMH = 3.0;
    // Floor for the HYSTERESIS-widened lower bound. Keeps it strictly above 0 so a
    // stopped car (0 km/h) always falls below it — otherwise a min at or under the
    // slack would widen to <= 0, which no reading can be under, pinning the card up.
    private static final double BS_SPEED_MIN_EFFECTIVE_KMH = 0.5;
    private final java.util.concurrent.locks.ReentrantLock bsLifecycleLock =
        new java.util.concurrent.locks.ReentrantLock();
    private static final int BS_WIDTH = 1280;
    private static final int BS_HEIGHT = 960;
    private final int sharedLaneHeight;

    // ── Camera-view (on-demand) — SHARES the single BS lane (scaler+SC layer+EGL) ──
    // Option A coexistence: there is exactly ONE on-screen native lane. Blind-spot
    // and camera-view are two PROGRAMS that take turns on it, arbitrated every tick
    // in bsTurnTick with BLIND-SPOT PRIORITY. No second scaler/layer/EGLSurface is
    // ever allocated (memory-optimal), and a program switch is just a viewMode +
    // geometry(+layerStack) reconfig — never a lane rebuild (compute-optimal).
    // camViewActive = a camera view is requested; the lane may be shared with BS.
    private volatile boolean camViewActive = false;
    // ── Camera-view ownership (audit: automation ownership) ────────────────────
    // Single current-owner model, no restore of older automation views:
    //   * every accepted show bumps camViewSessionId (identifies THIS request, so
    //     a stale auto-hide dispatch can never hide a newer view) and records the
    //     requesting automation execution's token as camViewOwnerToken — or null
    //     for an ownerless show (manual API call, key mapping, overlay, web UI);
    //   * a TOKENED hide closes the view only when its token matches the owner
    //     (an automation can hide only the view it showed; A show → B show →
    //     A hide leaves B's view up);
    //   * an OWNERLESS hide (user ✕, key mapping, web UI) keeps the legacy
    //     global-close behaviour and clears any owner;
    //   * internal teardowns (pipeline stop, BS takeover) always close.
    private final java.util.concurrent.atomic.AtomicLong camViewSessionSeq =
        new java.util.concurrent.atomic.AtomicLong(0);
    private volatile long camViewSessionId = 0L;
    private volatile Long camViewOwnerToken = null;
    // The session the CURRENT auto-hide deadline belongs to, bound to
    // camViewHideAtMs when enableCamView arms it (under bsLifecycleLock). The
    // timeout claim returns THIS, not the live camViewSessionId — a claim
    // landing between a newer show's noteCamViewShowRequest and its
    // enableCamView re-arm would otherwise capture the NEW session and hide
    // the view that show is about to put up.
    private volatile long camViewHideSessionId = 0L;
    /** Verdicts for {@link #hideCamViewIfAllowed}. */
    public static final int CAMVIEW_HIDE_NOT_OWNER = 0;
    public static final int CAMVIEW_HIDE_CLOSED = 1;
    public static final int CAMVIEW_HIDE_ALREADY_HIDDEN = 2;
    // In-memory geometry override for the CURRENT camview session. Set ONLY when
    // the show request's atomic config write failed (fail-open: render the
    // REQUESTED geometry rather than stale persisted geometry); cleared on the
    // next successful persist and on disable. Same JSON shape as the persisted
    // per-target geometry object (sizePct/corner or x/y/w/h).
    private volatile org.json.JSONObject camViewGeomOverride = null;
    private volatile String camViewGeomOverrideTarget = null;
    // True while an enableCamView() is in flight (set under bsLifecycleLock before
    // buildSharedLaneLocked, cleared when it returns). Mirrors bsEnabling: the lane
    // build releases bsLifecycleLock around its GL-init wait, so this lets a
    // concurrent enable (BS or camview) detect an in-flight build and bail instead
    // of double-allocating the scaler/layer.
    private volatile boolean camViewEnabling = false;
    // 0=all-4 mosaic,1=front,2=right,3=rear,4=left,7=rear+side L,8=rear+side R
    // (7/8 are the blind-spot composite, sharing its calibration/merge/fisheye).
    private volatile int camViewMode = 0;
    private volatile String camViewTarget = "head_unit";
    // Camera-view geometry (panel px), independent of the BS card's geometry so the
    // two programs can occupy different rects. Same atomic-rect discipline as bsGeomRect.
    private volatile int[] camViewGeomRect = new int[]{-1, -1, -1, -1};
    private volatile int camViewSizePct = 60;
    private volatile String camViewCorner = "center";
    // Auto-hide: elapsedRealtime deadline after which the camera view hides itself
    // (0 = stay until explicitly hidden). Set on show.
    private volatile long camViewHideAtMs = 0L;
    // One-shot guard for the auto-hide dispatch. camViewHideAtMs is checked from TWO
    // sites (bsTurnTick's pre-yield check and camViewTick's own), each doing a
    // read-compare-zero that is not atomic on a volatile long — so both could observe the
    // same expired deadline and spawn a disableCamView thread. Cleared when a new
    // deadline is armed.
    private final java.util.concurrent.atomic.AtomicBoolean camViewAutoHideFired =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    // Which program the lane is CURRENTLY configured for (0=none,1=bs,2=camview), so
    // the arbiter reconfigures (viewMode/calibration/geometry/target) only on a real
    // transition, not every 250ms tick. -1 forces a reconfig on next arbitration.
    private volatile int laneProgram = 0;
    private static final int PROG_NONE = 0, PROG_BS = 1, PROG_CAMVIEW = 2;

    // Telemetry overlay
    private TelemetryDataCollector telemetryCollector;
    // ACC-on (pano trip) overlay master. Preserves the legacy single-flag
    // behavior for the driving flow; set from the "pano" resolver.
    private volatile boolean overlayEnabledConfig = false;
    // ACC-off surveillance overlay master. Independent opt-in, defaults off, so
    // sentry event clips are unchanged (no burn-in) until the user enables it.
    private volatile boolean surveillanceOverlayEnabledConfig = false;

    // Config-change listener for live propagation of recording.rectifyStrength
    // edits. UI writes to UnifiedConfigManager; this listener picks up the
    // change and pushes to the active recorder so the next frame uses the
    // new value — no daemon restart, no segment rotation. Held as a field so
    // release() can deregister it cleanly.
    private com.overdrive.app.config.UnifiedConfigManager.ConfigChangeListener
        rectifyConfigListener;

    // Recording composition layout (0 = standard 360 mosaic, 1 = dashcam:
    // forward view on top + 360 left/rear/right below). Persisted in
    // recording.recordingLayout; re-applied to each recorder on creation.
    private volatile int recordingLayoutConfig = 0;
    private volatile boolean dashcamUseWindshieldConfig = false;
    private volatile int windshieldCameraIdConfig = -1;

    // Sentry (surveillance) layout profile — the independent counterpart to
    // recordingLayoutConfig / dashcamUseWindshieldConfig above. Persisted in
    // surveillance.recordingLayout / surveillance.useWindshield. Selected over
    // the dashcam profile by applyActiveLayoutProfile() whenever the pipeline
    // is in SURVEILLANCE mode, so sentry and dashcam can use different layouts
    // on the one shared recorder (the two modes are mutually exclusive).
    private volatile int surveillanceLayoutConfig = 0;
    private volatile boolean surveillanceUseWindshieldConfig = false;

    // Mode tracking
    private enum Mode {
        IDLE,           // Nothing active
        NORMAL_RECORDING,   // User manually recording
        SURVEILLANCE    // Auto-recording on motion
    }
    // volatile: read from worker threads (IdleShutdown, IPC, GL yield) without
    // taking the monitor in stop()/start().
    private volatile Mode currentMode = Mode.IDLE;

    // External "keep pipeline alive" predicate, e.g. PROXIMITY_GUARD MONITORING
    // where currentMode is IDLE and recorder isn't recording yet, but the
    // ADAS listener is armed and will soon trigger startRecording(). Without
    // this hook the idle-shutdown timer would tear the pipeline down between
    // monitoring and the next radar trigger.
    private volatile java.util.concurrent.Callable<Boolean> keepAlivePredicate;

    // Fired (best-effort) whenever the BS layer's on-screen visibility changes
    // (turn signal on/off, debug-preview, cluster show/hide, disable). Lets
    // RecordingModeManager re-reconcile the GLOBAL camera fps when BS is the SOLE
    // consumer: ramp to BS active fps on show, drop to BS idle fps on hide.
    // No-op when a recording mode owns the camera (recording fps wins). Set by
    // RecordingModeManager; null otherwise. Invoked off the BS turn-tick thread.
    private volatile Runnable bsVisibilityListener;

    // Fired whenever live-view streaming is enabled or disabled (incl. the WS
    // idle-shutdown auto-close). Lets RecordingModeManager re-reconcile the global
    // camera fps floor: a live stream pins the camera at >= stream fps; when it
    // goes away the camera can drop back to the BS idle rate (or recording rate).
    // Set by RecordingModeManager; null otherwise.
    private volatile Runnable streamStateListener;

    /** Max frame rate we declare to the LIVE-VIEW stream encoder on dilink4.
     *
     *  <p>Matches the OEM's own AVM panoramic request (10 fps,
     *  {@code PanoCameraRecordService:530}); its publisher lane uses 8. That HAL
     *  refuses {@code setCameraFps} outright and emits at its own fixed low rate,
     *  so declaring more than it delivers just makes the encoder treat most ticks
     *  as duplicates. Applies to the stream encoder's declared rate only —
     *  resolution, bitrate and every legacy-vehicle path are unaffected. */
    private static final int DILINK4_STREAM_FPS_CAP = 10;

    // Configuration
    private final int cameraWidth;
    private final int cameraHeight;
    private int encoderWidth;
    private int encoderHeight;
    private final File eventOutputDir;
    private GpuPipelineConfig config;
    
    // State
    private boolean initialized = false;
    // volatile: idle-shutdown thread reads without taking the monitor.
    private volatile boolean running = false;
    // True while {@link #stop()} is mid-teardown (encoders releasing, EGL
    // tearing down). Concurrent start() must wait until stop completes —
    // otherwise we race the encoder release with init() allocating a new
    // one. Guarded by the same monitor as {@code running}.
    private volatile boolean stopping = false;
    // True while {@link #start(boolean)} is in progress but not yet
    // verified — set at entry, cleared once camera open is verified
    // (running=true) or on failure. Blocks concurrent start() without
    // publishing running=true prematurely; this is what keeps
    // isRunning() honest if the camera GL-thread runnable throws.
    private volatile boolean starting = false;
    // volatile because the cold-start storage-retry thread (RecStorageRetry)
    // reads this without holding the pipeline monitor; without volatile the
    // retry thread can observe a stale `false` after stopRecording() flipped
    // it, defeating the cancellation check.
    private volatile boolean recordingMode = false;  // true = recording, false = viewing only

    // Serializes runtime reconfig methods (applyFpsChange, applyBitrateChange,
    // applyCodecChange). Without this, two web-UI changes arriving back-to-back
    // can interleave reinitializeEncoder() calls — one observes encoder=null
    // mid-tear-down and silently no-ops, or worse, both threads tear down
    // recorder surfaces concurrently.
    private final Object reconfigLock = new Object();
    
    // Saved init params — needed for re-initialization after stop/start cycle (ACC OFF→ON)
    private android.content.res.AssetManager savedAssetManager;
    private android.content.Context savedContext;
    
    // Deferred recording: stored when startRecording() is called before encoder is ready
    private volatile java.io.File pendingRecordingDir = null;
    private volatile String pendingRecordingPrefix = null;

    // FIX (audit R3, Findings 3+6): the active normal-recording session's
    // outputDir + prefix. Captured at the top of pipeline.startRecording() and
    // cleared by stopRecording(). onPostReacquire() (camera-yield resume) uses
    // these so it can re-enter pipeline.startRecording(dir, prefix) — the only
    // path that gates on encoder.isFormatAvailable() and runs the storage
    // probe + scheduleStorageReadyRetry. Without this, a yield mid-recording
    // would call recorder.startRecording() bare, silent-no-op when the
    // encoder hadn't republished its format yet, and wedge for the rest of
    // the drive (no thread re-polls during ACC=ON).
    private volatile java.io.File activeRecordingDir = null;
    private volatile String activeRecordingPrefix = null;

    // FIX (audit R1, RESIDUAL): segment-rotation timestamp. Stamped by
    // GpuMosaicRecorder's file-closed callback when a normal segment
    // rotates. RecordingModeManager's wedge ticker reads this via
    // getLastSegmentRotateMs() and skips its wedge check for 5s after a
    // rotation, so the natural isRecording()=false flicker between
    // segments doesn't trigger a phantom wedgeDetected re-activation.
    private volatile long lastSegmentRotateMs = 0L;

    // FIX (audit R5): pipeline generation counter. Incremented on every
    // stop() and start() to invalidate background retry threads scheduled
    // against an earlier lifecycle. RecStorageRetry / RecStorageSlowRetry
    // capture this at schedule time and bail when a teardown-then-restart
    // cycle has rotated the value out from under them — the new pipeline
    // will reschedule its own retry if it still needs one.
    private final java.util.concurrent.atomic.AtomicLong pipelineGen =
        new java.util.concurrent.atomic.AtomicLong(0L);

    // FIX (audit R6): cache the resolved camera profile's per-quadrant strip-X
    // offsets so reinitializeEncoder()'s defensive `new GpuMosaicRecorder()`
    // (recorder=null branch) can rebuild with the correct viewport dims.
    // Without this, that branch falls back to the no-arg constructor which
    // uses DEFAULT_VIEWPORT_WIDTH/HEIGHT (2560x1920) and null offsets — a
    // mismatch on Tang (encoderHeight=1440) that would corrupt the encoder
    // feed. Captured during init() once the camera profile resolves.
    private volatile float[] lastQuadrantStripOffsetX = null;
    
    /**
     * Creates the GPU surveillance pipeline.
     * 
     * @param cameraWidth Camera width (typically 5120)
     * @param cameraHeight Camera height (typically 960)
     * @param eventOutputDir Directory for event recordings
     */
    public GpuSurveillancePipeline(int cameraWidth, int cameraHeight, File eventOutputDir) {
        this(cameraWidth, cameraHeight,
            Math.max(1, cameraWidth / 2),
            Math.max(1, cameraHeight * 2),
            eventOutputDir);
    }

    public GpuSurveillancePipeline(int cameraWidth, int cameraHeight,
                                   int encoderWidth, int encoderHeight,
                                   File eventOutputDir) {
        this.cameraWidth = cameraWidth;
        this.cameraHeight = cameraHeight;
        boolean isDilink5 = com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported();
        com.overdrive.app.camera.ResolvedCameraConfig resolved =
            com.overdrive.app.camera.CameraConfigResolver.resolve();
        if (isDilink5) {
            String q = "STANDARD";
            try {
                org.json.JSONObject rec = com.overdrive.app.config.UnifiedConfigManager.loadConfig().optJSONObject("recording");
                if (rec != null) {
                    q = rec.optString("recordingQuality", "STANDARD");
                }
            } catch (Throwable ignored) {}
            boolean want4K = "ULTRA_4K".equalsIgnoreCase(q);
            if (want4K) {
                this.encoderWidth = 3840;
                this.encoderHeight = 2160;
            } else {
                this.encoderWidth = 1920;
                this.encoderHeight = 1080;
            }
        } else if (resolved != null && resolved.getProfile() != null) {
            this.encoderWidth = resolved.getProfile().getEncoderWidth();
            this.encoderHeight = resolved.getProfile().getEncoderHeight();
        } else {
            // Encoder/mosaic dims are derived from the strip aspect: each tile is
            // (cameraWidth/4) wide x cameraHeight tall, mosaic is 2x2 of tiles, so
            // encoder = (cameraWidth/2) x (cameraHeight*2). Seal 5120x960 → 2560x1920
            // (4:3 quadrants). Tang 5120x720 → 2560x1440 (16:9 quadrants).
            this.encoderWidth = Math.max(1, encoderWidth);
            this.encoderHeight = Math.max(1, encoderHeight);
        }
        this.sharedLaneHeight =
            com.overdrive.app.camera.CameraConfigResolver.isPassiveApaModeEnabled()
                ? com.overdrive.app.camera.PassiveApaGeometry.HEIGHT
                : BS_HEIGHT;
        this.eventOutputDir = eventOutputDir;
        this.config = new GpuPipelineConfig();
    }
    
    /**
     * Gets the configuration.
     */
    public GpuPipelineConfig getConfig() {
        return config;
    }

    /**
     * Returns the underlying hardware encoder, or null if the pipeline has
     * not been initialized yet. Used by callers that need the active output
     * file path for things like push-notification deep-links.
     */
    public HardwareEventRecorderGpu getEncoder() {
        return recorder != null ? recorder.getEncoder() : null;
    }

    /**
     * Reads the shared recording.segmentDurationMinutes (clamped) and pushes
     * it to the live encoder as a millisecond rotation interval. Called after
     * encoder (re)init; safe no-op if the encoder isn't up yet.
     */
    private void applySegmentDurationFromConfig() {
        try {
            HardwareEventRecorderGpu enc = encoder;
            if (enc == null) return;
            int minutes = com.overdrive.app.config.UnifiedConfigManager
                .getSegmentDurationMinutes();
            enc.setSegmentDurationMs(minutes * 60_000L);
        } catch (Throwable t) {
            logger.warn("Failed to apply segment duration from config: " + t.getMessage());
        }
    }

    /**
     * Live-apply a new clip segment length (minutes) to the running dashcam
     * encoder without a reinit. Takes effect on the next rotation. Called by
     * the quality API when the user changes the shared Clip Duration control.
     */
    public void updateSegmentDuration(int minutes) {
        try {
            int clamped = Math.max(
                com.overdrive.app.util.Constants.MIN_SEGMENT_DURATION_MINUTES,
                Math.min(com.overdrive.app.util.Constants.MAX_SEGMENT_DURATION_MINUTES, minutes));
            HardwareEventRecorderGpu enc = encoder;
            if (enc != null) {
                enc.setSegmentDurationMs(clamped * 60_000L);
            }
        } catch (Throwable t) {
            logger.warn("updateSegmentDuration failed: " + t.getMessage());
        }
    }

    /**
     * Set the recorder draw stride (Proximity Guard low-rate pre-record). A
     * stride of N makes the render loop feed the recording encoder only every
     * Nth camera frame, lowering the effective recording rate without a codec
     * reconfigure. Streaming and blind-spot are unaffected. {@code 1} = full
     * rate (default). No-op if the camera isn't up yet. Idempotent.
     */
    public void setRecorderFrameStride(int stride) {
        PanoramicCameraGpu cam = camera;
        if (cam != null) {
            cam.setRecorderFrameStride(stride);
        }
    }

    /**
     * @return the camera's configured target FPS, or 0 if the camera isn't up.
     * Used by Proximity Guard to derive the recorder draw stride from a desired
     * monitor FPS (stride = round(cameraFps / monitorFps)).
     */
    public int getCameraTargetFps() {
        PanoramicCameraGpu cam = camera;
        return cam != null ? cam.getTargetFps() : 0;
    }

    /**
     * Set the GLOBAL camera HAL emission fps at runtime (live setCameraFps, no
     * camera reopen, no config persist). Used by RecordingModeManager to ramp the
     * whole pipeline's rate — e.g. drop to ~1fps when the camera is kept warm only
     * for a hidden blind-spot view, ramp to ~15fps on a turn-signal reveal. This
     * affects ALL render-loop passes (recorder, stream, blind-spot) since they
     * share the one camera. The recorder lane can be sub-sampled BELOW this with
     * setRecorderFrameStride, or skipped entirely with setRecorderLaneEnabled.
     * No-op if the camera isn't up.
     */
    public void setCameraTargetFps(int fps) {
        PanoramicCameraGpu cam = camera;
        if (cam != null) cam.setTargetFps(fps);
    }

    /**
     * Pin the AI motion-readback cadence to a fixed wall-clock interval (ms),
     * independent of the HAL rate; 0 restores the default frame-count modulo.
     * Parked-idle throttle uses this to keep detection identical while the HAL
     * fps is dropped for power. No-op if the camera isn't up (re-applied at the
     * next AI-lane bring-up). Co-located with setCameraTargetFps because RMM
     * sets the two together.
     */
    public void setAiReadbackMinIntervalMs(long ms) {
        PanoramicCameraGpu cam = camera;
        if (cam != null) cam.setAiReadbackMinIntervalMs(ms);
    }

    /**
     * Enable/disable the recorder lane (PASS 1A: H.265 mosaic encode) at runtime
     * without tearing down the pipeline. When disabled, the render loop skips the
     * recorder drawFrame + drainEncoder entirely — the stream (PASS 1B) and
     * blind-spot (PASS 1C) lanes are unaffected. Used when the camera is kept warm
     * ONLY for blind-spot: BS has no encoder, and no recording mode owns the
     * pre-record ring, so running the H.265 encoder would burn Venus for footage
     * nothing will ever flush. Re-enabled (true) the instant a recording mode
     * activates. No-op if the camera isn't up.
     */
    public void setRecorderLaneEnabled(boolean enabled) {
        PanoramicCameraGpu cam = camera;
        if (cam != null) cam.setRecorderLaneEnabled(enabled);
    }

    /**
     * @return the fps of the currently-enabled live-view stream lane, or 0 when
     * streaming is off. The stream (PASS 1B) shares the one camera; when the
     * camera is dropped to a low BS-only idle fps, callers use this as a FLOOR so
     * an active live view isn't starved/desynced. Returns 0 (no floor) when no
     * stream is up.
     */
    public int getActiveStreamFps() {
        if (!streamingEnabled) return 0;
        HardwareEventRecorderGpu enc = streamEncoder;
        if (enc == null) return 0;
        try {
            return enc.getFps();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Set the recording encoder bitrate at runtime (Proximity Guard adaptive
     * quality). Routed through the AdaptiveBitrateController when present so its
     * cached currentBitrate stays coherent; falls back to a direct encoder
     * setBitrate otherwise. No-op if the encoder isn't up. Safe and immediate —
     * MediaCodec PARAMETER_KEY_VIDEO_BITRATE, no reconfigure, no pre-record-ring
     * realloc.
     */
    public void setRecordingBitrate(int bitrate) {
        // Capture into locals before deref — stop()'s teardown body runs
        // outside the synchronized prologue and can null these between a
        // re-read, so a TOCTOU re-read would NPE on the proximity binder/
        // scheduler thread. Both fields are volatile so the null is visible.
        AdaptiveBitrateController bc = bitrateController;
        if (bc != null) {
            bc.setImmediateBitrate(bitrate);
            return;
        }
        HardwareEventRecorderGpu enc = getEncoder();
        if (enc != null) {
            enc.setBitrate(bitrate);
        }
    }

    /**
     * @return the pipeline's user-configured effective recording bitrate (bps).
     * Proximity Guard restores the encoder to THIS on teardown so a follow-on
     * recording mode inherits the user's real quality rather than proximity's
     * own event bitrate.
     */
    public int getConfiguredRecordingBitrate() {
        return config.getEffectiveBitrate();
    }

    /**
     * @return the user-CONFIGURED recording fps from unified config (NOT the
     * current live camera HAL rate, which may be temporarily lowered — e.g. by
     * Proximity Guard's monitor profile). Proximity Guard uses this as the
     * snap-UP target when a radar event fires, so the live event clip records at
     * the user's real fps regardless of the lowered monitoring rate. Falls back
     * to 15 if unreadable. (getCameraTargetFps() returns the live rate; this
     * returns the configured rate.)
     */
    public int getConfiguredRecordingFps() {
        return loadTargetFps();
    }

    /**
     * @return the pipeline's effective ACC-off SURVEILLANCE bitrate (bps),
     * resolving recording.surveillanceQuality against the shared codec. Honors
     * an active customBitrate (thermal/network throttle) exactly like
     * {@link #getConfiguredRecordingBitrate()}. When the surveillance tier is
     * unset (pre-split config), falls back to the ACC-on recordingQuality tier
     * — byte-identical to the pre-split single-knob behaviour. Used by
     * RecordingModeManager's reconcile and by {@link #setRecordingMode} when
     * entering SENTRY.
     */
    public int getEffectiveSurveillanceBitrate() {
        return config.getEffectiveBitrateForQuality(loadSurveillanceQuality());
    }

    /**
     * Live re-assert of the ACC-off surveillance profile (fps + bitrate) when
     * surveillance is CURRENTLY active. Called by the settings API after a
     * surveillance quality/fps edit so the change takes effect on the running
     * parked recording WITHOUT a pipeline restart or encoder reinit — fps via
     * the camera HAL live knob, bitrate via the adaptive controller / encoder
     * setParameters, exactly mirroring setRecordingMode(SENTRY). No-op (returns
     * false) when not in surveillance mode or the pipeline is torn down; the
     * persisted config is picked up on the next ACC-off transition in that case.
     *
     * @return true if the live re-assert was applied.
     */
    public boolean reapplySurveillanceProfileIfActive() {
        synchronized (reconfigLock) {
            if (!isSurveillanceMode()) {
                return false;
            }
            // Same teardown gate as applyFpsChangeLocked / applyBitrateChangeLocked:
            // reconfigLock does not serialize against stop()'s teardown body.
            if (!running || stopping) {
                logger.warn("reapplySurveillanceProfile: skipping live apply "
                    + "(running=" + running + ", stopping=" + stopping + ") — "
                    + "config persisted, applies on next ACC-off");
                return false;
            }
            return applySurveillanceProfileLocked("re-assert");
        }
    }

    /**
     * Applies the ACC-off surveillance fps + bitrate to the live camera + encoder.
     * CALLER MUST HOLD {@code reconfigLock}. This is the single canonical place
     * the surveillance tier is pushed to hardware, funnelling every arm/re-assert
     * path (enableSurveillance, setRecordingMode(SENTRY), the settings-API live
     * re-apply) through one reconfigLock-guarded body so they can never race each
     * other on the camera-fps / encoder-bitrate setters. No reinit: fps is a live
     * HAL knob and bitrate is a MediaCodec setParameters, both gap-free.
     *
     * <p>When the surveillance keys are unset this resolves to the ACC-on
     * recording tier (see loadSurveillanceTargetFps / getEffectiveSurveillanceBitrate),
     * so on a pre-split config it applies exactly what the old SENTRY path did.
     *
     * @param reason short tag for the log line (e.g. "arm", "re-assert").
     * @return true if applied without throwing.
     */
    private boolean applySurveillanceProfileLocked(String reason) {
        try {
            int survFps = loadSurveillanceTargetFps();
            // Floor the shared camera HAL rate at the active live-view stream fps
            // (0 when no stream) so arming surveillance — or editing the
            // surveillance fps via the settings API — while a stream is open does
            // NOT starve/desync that stream by dropping the HAL below its rate.
            // This is exactly what RecordingModeManager's authority computes
            // (reconcileCameraProfileLocked / applyFullRecordingProfile take the
            // same max), so this arm-path assert converges with RMM rather than
            // fighting it. NOTE: surveillance runs the recorder at stride 1
            // (continuous-style ownStrideBitrate), so the RECORDING rate equals
            // the HAL rate — when a stream forces camFps above survFps the clip is
            // (intentionally, by the shared-camera design) recorded at camFps for
            // the duration of the stream; with no stream it records at survFps.
            int camFps = Math.max(survFps, getActiveStreamFps());
            PanoramicCameraGpu cam = camera;
            if (cam != null) {
                cam.setTargetFps(camFps);
            }
            // setRecordingBitrate routes through the adaptive controller when
            // present (else the encoder directly) — same path RMM reconcile uses,
            // so the throttle/override semantics stay uniform.
            int survBitrate = getEffectiveSurveillanceBitrate();
            setRecordingBitrate(survBitrate);
            logger.info("Surveillance profile applied (" + reason + "): survFps="
                + survFps + ", camFps=" + camFps + ", bitrate="
                + (survBitrate / 1_000_000) + " Mbps");
            return true;
        } catch (Throwable t) {
            logger.warn("applySurveillanceProfileLocked(" + reason + ") failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Request an immediate keyframe (IDR) on the recording encoder. Proximity
     * Guard uses this to keep a keyframe inside the pre-record window while the
     * low-rate monitor profile stretches the natural GOP, and to open the live
     * event clip on a clean IDR. No-op if the encoder isn't up.
     */
    public void requestRecordingSyncFrame() {
        HardwareEventRecorderGpu enc = getEncoder();
        if (enc != null) {
            enc.requestSyncFrame();
        }
    }

    /**
     * Sets the recording mode (Normal/Sentry).
     */
    public void setRecordingMode(GpuPipelineConfig.RecordingMode mode) {
        config.setRecordingMode(mode);

        // SENTRY shares the SAME camera + recorder as the ACC-ON modes. When the
        // pipeline is REUSED across ACC-off (not freshly start()ed — the common
        // case when Proximity Guard kept it warm), the camera HAL may have been
        // left at a LOWERED rate by Proximity's monitor profile (~4 fps). A fresh
        // start() would read the configured fps, but a reuse does not — so sentry
        // would inherit ~4 fps and record motion/event clips at 4 fps. Re-assert
        // the configured recording fps + the full recorder lane here so surveillance
        // always captures at the user's real rate regardless of what the prior
        // ACC-ON mode left the shared camera at. setCameraTargetFps is the live
        // runtime knob (no reopen); idempotent if already at this rate. (Recorder
        // lane is also re-enabled in case a BS-only-warm state had it off — the
        // same by-construction guarantee startRecording() makes.)
        if (mode == GpuPipelineConfig.RecordingMode.SENTRY) {
            // SENTRY shares the SAME camera + recorder as the ACC-ON modes and is
            // REUSED across ACC-off (not freshly start()ed — the common case when
            // Proximity Guard kept it warm at ~4 fps). Re-assert the surveillance
            // fps + bitrate + full recorder lane so sentry always captures at the
            // user's real surveillance tier regardless of what the prior ACC-ON
            // mode left the shared camera/encoder at. All live knobs (no reopen /
            // no reinit); idempotent if already at this rate. Funnel the fps +
            // bitrate through applySurveillanceProfileLocked so this shares ONE
            // reconfigLock domain with enableSurveillance()'s arm assert and the
            // settings-API re-apply — they can never race on the setters.
            synchronized (reconfigLock) {
                PanoramicCameraGpu cam = camera;
                if (cam != null) {
                    try {
                        cam.setRecorderLaneEnabled(true);
                    } catch (Throwable t) {
                        logger.warn("setRecordingMode(SENTRY): recorder-lane re-enable failed: " + t.getMessage());
                    }
                }
                if (running && !stopping) {
                    applySurveillanceProfileLocked("SENTRY");
                }
            }
        } else if (encoder != null) {
            // Non-SENTRY (NORMAL etc.): re-assert the user's ACC-ON recording
            // bitrate (NOT the RecordingMode enum's legacy per-mode default),
            // honoring an active customBitrate throttle via getEffectiveBitrate.
            int userBitrate = config.getEffectiveBitrate();
            if (bitrateController != null) {
                bitrateController.setImmediateBitrate(userBitrate);
            }
            logger.info(String.format("Recording mode: %s (using bitrate=%d Mbps, mode default was %d Mbps)",
                    mode, userBitrate / 1_000_000, mode.bitrate / 1_000_000));
        }
    }
    
    /**
     * Sets the streaming quality (HQ/LQ).
     */
    public void setStreamingQuality(GpuPipelineConfig.StreamingQuality quality) {
        config.setStreamingQuality(quality);
        // Quality is saved — it will be applied on next stream start.
        // Don't restart the active stream to avoid disrupting the live view.
        logger.info(String.format("Streaming quality saved: %s (%dx%d @ %dfps)",
                quality, quality.width, quality.height, quality.fps));
        // GL budget warning: if the stream encoder rate exceeds the
        // recording encoder rate, both run inside the same GL render loop
        // iteration — at 30+30 fps the GL thread may not have headroom.
        // Not a hard error (encoder backpressure / reactive AI-skip will
        // handle it), but worth flagging so the operator knows why
        // performance might dip.
        int recordingFps = encoder != null ? encoder.getFps() : 0;
        if (recordingFps > 0 && quality.fps > recordingFps) {
            logger.warn("Stream fps " + quality.fps
                + " > recording fps " + recordingFps
                + " — GL thread budget may be tight on heavy frames");
        }
    }
    
    /**
     * Applies a bitrate change to the encoder.
     * 
     * Reinitializes encoder immediately to ensure new bitrate is used.
     * 
     * @param bitrate New bitrate in bps
     */
    public void applyBitrateChange(int bitrate) {
        synchronized (reconfigLock) {
            applyBitrateChangeLocked(bitrate);
        }
    }

    private void applyBitrateChangeLocked(int bitrate) {
        // Update config first
        config.setCustomBitrate(bitrate);

        // FIX (audit R7): gate against concurrent stop() teardown. reconfigLock
        // serializes apply* against each other but NOT against stop(); stop()'s
        // teardown body runs outside its synchronized block. Without this gate,
        // apply* sees encoder!=null, then stop() nulls it under our feet, and
        // reinitializeEncoder()'s defensive null-checks half-rebuild a fresh
        // encoder bound to no recorder. Persisting the config above is fine
        // (RMM's next activation re-reads it); skip the live reconfig.
        if (!running || stopping) {
            logger.warn("Bitrate change persisted to config but skipping live apply "
                + "(running=" + running + ", stopping=" + stopping + ")");
            return;
        }

        if (encoder == null) {
            logger.info("Bitrate setting saved (encoder not initialized yet): " + (bitrate / 1_000_000) + " Mbps");
            return;
        }

        // Check if bitrate actually changed
        if (encoder.getBitrate() == bitrate) {
            logger.info("Bitrate already set to: " + (bitrate / 1_000_000) + " Mbps");
            return;
        }

        // Bitrate-only change: inline reconfigure via MediaCodec.setParameters.
        // No encoder release, no recording restart, no pre-record loss. The
        // byte-ring pre-record buffer is bitrate-agnostic; the encoder's
        // PARAMETER_KEY_VIDEO_BITRATE is the only state that needs updating.
        // (Full reinit is reserved for codec changes — see applyCodecChange.)
        logger.info("Bitrate change: " + (bitrate / 1_000_000) + " Mbps (inline)");
        try {
            encoder.setBitrate(bitrate);
            if (bitrateController != null) {
                bitrateController.setImmediateBitrate(bitrate);
            }
            logger.info("Bitrate change applied: " + (bitrate / 1_000_000) + " Mbps");
            // FIX (audit R5): inline-success paths previously didn't kick off
            // any deferred recording. If a recording start arrived during a
            // cold encoder window and got deferred to pendingRecordingPrefix,
            // and then the user's first interaction was to change bitrate,
            // the deferred start would sit until the next external trigger.
            // checkPendingRecording is idempotent (no-ops if pending is null
            // or already recording), so call it opportunistically.
            if (pendingRecordingPrefix != null) {
                logger.info("Inline bitrate success — kicking deferred recording check");
                try { checkPendingRecording(); }
                catch (Throwable t) { logger.warn("Deferred-recording kick failed: " + t.getMessage()); }
            }
            return;
        } catch (Exception e) {
            logger.error("Inline bitrate change failed, falling back to encoder reinit: " + e.getMessage());
        }

        // Fallback path (rare — only if MediaCodec.setParameters threw).
        // Full reinit cycle: stop recording, reinit encoder, restart.
        boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
        boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
        boolean wasRecording = isRecording() || pendingRecordingPrefix != null || recordingMode;

        try {
            if (wasRecording && recorder != null && recorder.isRecording()) {
                logger.info("Stopping recording for bitrate change (fallback)");
                recorder.stopRecording();
                Thread.sleep(500);
            }

            reinitializeEncoder();

            if (bitrateController != null) {
                bitrateController.setImmediateBitrate(bitrate);
            }

            if (wasRecording) {
                if (wasSurveillance) {
                    logger.info("Restarting surveillance mode with new bitrate");
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    // FIX (audit R6): preserve session prefix/dir across the
                    // bitrate-fallback reinit. Mirror onPostReacquire's pendingPrefix
                    // -> activePrefix -> "cam" preference so session-identity is
                    // not silently regressed to default ("cam_*.mp4") on reinit.
                    java.io.File restartDir;
                    String restartPrefix;
                    if (pendingRecordingPrefix != null) {
                        restartDir = pendingRecordingDir;
                        restartPrefix = pendingRecordingPrefix;
                    } else if (activeRecordingPrefix != null) {
                        restartDir = activeRecordingDir;
                        restartPrefix = activeRecordingPrefix;
                    } else {
                        restartDir = null;
                        restartPrefix = null;
                    }
                    if (restartPrefix != null) {
                        logger.info("Restarting normal recording with new bitrate (prefix="
                            + restartPrefix + ")");
                        startRecording(restartDir, restartPrefix);
                    } else {
                        logger.info("Restarting normal recording with new bitrate (no captured prefix — default 'cam')");
                        startRecording();
                    }
                } else if (recordingMode || pendingRecordingPrefix != null) {
                    java.io.File restartDir;
                    String restartPrefix;
                    if (pendingRecordingPrefix != null) {
                        restartDir = pendingRecordingDir;
                        restartPrefix = pendingRecordingPrefix;
                    } else if (activeRecordingPrefix != null) {
                        restartDir = activeRecordingDir;
                        restartPrefix = activeRecordingPrefix;
                    } else {
                        restartDir = null;
                        restartPrefix = null;
                    }
                    if (restartPrefix != null) {
                        logger.info("Restarting deferred recording with new bitrate (prefix="
                            + restartPrefix + ")");
                        startRecording(restartDir, restartPrefix);
                    } else {
                        logger.info("Restarting deferred recording with new bitrate (no captured prefix — default 'cam')");
                        startRecording();
                    }
                }
            }

            logger.info("Bitrate change applied via fallback reinit: " + (bitrate / 1_000_000) + " Mbps");

        } catch (Exception e) {
            logger.error("Failed to apply bitrate change: " + e.getMessage(), e);
            // FIX (audit R4, Findings 1+2): reinitializeEncoder() may have nulled
            // the encoder field after releasing it but BEFORE the new encoder
            // bound to the recorder, leaving the recorder pointed at a dead
            // encoder. Calling startRecording() now would register a format-
            // available listener on a released encoder that never fires —
            // wedging recording for the rest of the ACC=ON window. Force a
            // full pipeline.stop() so RMM's next tick rebuilds from scratch.
            logger.warn("Forcing pipeline stop after bitrate-reinit failure — "
                + "RMM will rebuild on next activation");
            try {
                stop();
            } catch (Throwable t) {
                logger.warn("Failed to stop pipeline after bitrate change error: "
                    + t.getMessage());
            }
        }
    }

    /**
     * Applies a recording FPS change at runtime. Persists the new fps to
     * UnifiedConfigManager (camera.targetFps), propagates it to the camera
     * (so the HAL clamps emission to that rate), and reinitializes the
     * encoder so KEY_FRAME_RATE matches.
     *
     * Range: 10-30 fps. Values outside this range are clamped — the panoramic
     * HAL on this device tops out at ~26 fps and the V2 motion pipeline is
     * tuned for 10 fps minimum (aiFrameSkip handles the higher rates).
     *
     * If recording is active, it is stopped, the encoder reinitialized, and
     * recording resumes at the new rate. If the requested fps already matches
     * the current value, no-ops.
     */
    public void applyFpsChange(int fps) {
        synchronized (reconfigLock) {
            applyFpsChangeLocked(fps);
        }
    }

    private void applyFpsChangeLocked(int fps) {
        int clamped = Math.max(10, Math.min(30, fps));
        if (clamped != fps) {
            logger.warn("FPS " + fps + " out of range [10..30] — clamped to " + clamped);
        }

        // Persist to config first so reinitializeEncoder picks it up via loadTargetFps().
        try {
            org.json.JSONObject cameraCfg = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cameraCfg == null) cameraCfg = new org.json.JSONObject();
            cameraCfg.put("targetFps", clamped);
            com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", cameraCfg);
        } catch (Exception e) {
            logger.warn("Failed to persist targetFps: " + e.getMessage());
        }

        // FIX (audit R7): gate against concurrent stop() teardown. See
        // applyBitrateChangeLocked for the full rationale — same race window.
        if (!running || stopping) {
            logger.warn("FPS change persisted to config but skipping live apply "
                + "(running=" + running + ", stopping=" + stopping + ")");
            return;
        }

        // Propagate to camera so the HAL emission rate also tracks the new target.
        if (camera != null) {
            camera.setTargetFps(clamped);
        }

        if (encoder == null) {
            logger.info("FPS setting saved (encoder not initialized yet): " + clamped + " fps");
            return;
        }
        if (encoder.getFps() == clamped) {
            logger.info("FPS already set to: " + clamped + " fps");
            return;
        }

        logger.info("FPS change requested: " + clamped + " fps - reinitializing encoder");

        boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
        boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
        // See applyBitrateChangeLocked for why deferred-recording counts as recording.
        boolean wasRecording = isRecording() || pendingRecordingPrefix != null || recordingMode;

        try {
            if (wasRecording && recorder != null && recorder.isRecording()) {
                logger.info("Stopping recording for FPS change");
                recorder.stopRecording();
                Thread.sleep(500);
            }

            // reinitializeEncoder reads loadTargetFps() internally — picks up our persist.
            reinitializeEncoder();

            if (wasRecording) {
                if (wasSurveillance) {
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    // FIX (audit R6): preserve session prefix/dir across reinit.
                    java.io.File restartDir;
                    String restartPrefix;
                    if (pendingRecordingPrefix != null) {
                        restartDir = pendingRecordingDir;
                        restartPrefix = pendingRecordingPrefix;
                    } else if (activeRecordingPrefix != null) {
                        restartDir = activeRecordingDir;
                        restartPrefix = activeRecordingPrefix;
                    } else {
                        restartDir = null;
                        restartPrefix = null;
                    }
                    if (restartPrefix != null) {
                        logger.info("FPS reinit: resuming normal recording (prefix="
                            + restartPrefix + ")");
                        startRecording(restartDir, restartPrefix);
                    } else {
                        logger.info("FPS reinit: resuming normal recording (no captured prefix — default 'cam')");
                        startRecording();
                    }
                } else if (recordingMode || pendingRecordingPrefix != null) {
                    // Deferred-recording window — see applyBitrateChangeLocked
                    // for the full reasoning.
                    java.io.File restartDir;
                    String restartPrefix;
                    if (pendingRecordingPrefix != null) {
                        restartDir = pendingRecordingDir;
                        restartPrefix = pendingRecordingPrefix;
                    } else if (activeRecordingPrefix != null) {
                        restartDir = activeRecordingDir;
                        restartPrefix = activeRecordingPrefix;
                    } else {
                        restartDir = null;
                        restartPrefix = null;
                    }
                    if (restartPrefix != null) {
                        logger.info("FPS reinit: resuming deferred recording (prefix="
                            + restartPrefix + ")");
                        startRecording(restartDir, restartPrefix);
                    } else {
                        logger.info("FPS reinit: resuming deferred recording (no captured prefix — default 'cam')");
                        startRecording();
                    }
                }
            }
            logger.info("FPS change applied successfully: " + clamped + " fps");
        } catch (Exception e) {
            logger.error("Failed to apply FPS change: " + e.getMessage(), e);
            // FIX (audit R4, Findings 1+2): see applyBitrateChangeLocked catch
            // for full reasoning. Force pipeline.stop() instead of calling
            // startRecording() against a stale-encoder recorder.
            logger.warn("Forcing pipeline stop after FPS-reinit failure — "
                + "RMM will rebuild on next activation");
            try {
                stop();
            } catch (Throwable t) {
                logger.warn("Failed to stop pipeline after FPS change error: "
                    + t.getMessage());
            }
        }
    }

    /**
     * Applies a codec change. Requires encoder restart.
     *
     * @param codec New video codec
     */
    public void applyCodecChange(GpuPipelineConfig.VideoCodec codec) {
        synchronized (reconfigLock) {
            applyCodecChangeLocked(codec);
        }
    }

    private void applyCodecChangeLocked(GpuPipelineConfig.VideoCodec codec) {
        // Store the new codec setting
        config.setVideoCodec(codec);

        // FIX (audit R7): gate against concurrent stop() teardown. See
        // applyBitrateChangeLocked for the full rationale — same race window.
        if (!running || stopping) {
            logger.warn("Codec change persisted to config but skipping live apply "
                + "(running=" + running + ", stopping=" + stopping + ")");
            return;
        }

        // If encoder doesn't exist yet, just save the setting
        if (encoder == null) {
            logger.info("Codec changed to: " + codec.displayName + " - will apply when encoder initializes");
            return;
        }
        
        // Check if codec actually changed
        String currentCodec = encoder.getCodecMimeType();
        String newCodec = config.getCodecMimeType();
        if (currentCodec.equals(newCodec)) {
            logger.info("Codec already set to: " + codec.displayName);
            return;
        }
        
        logger.info("Codec change requested: " + codec.displayName + " - reinitializing encoder");

        boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
        boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
        // See applyBitrateChangeLocked for why deferred-recording counts as recording.
        boolean wasRecording = isRecording() || pendingRecordingPrefix != null || recordingMode;
        
        try {
            // Stop current recording first if active
            if (wasRecording && recorder != null && recorder.isRecording()) {
                logger.info("Stopping recording for codec change");
                recorder.stopRecording();
                // Wait for encoder to finish writing
                Thread.sleep(500);
            }
            
            // Reinitialize encoder with new codec
            reinitializeEncoder();
            
            // Restart recording if it was active
            if (wasRecording) {
                if (wasSurveillance) {
                    logger.info("Restarting surveillance mode with new codec");
                    enableSurveillance();
                } else if (wasNormalRecording) {
                    // FIX (audit R6): preserve session prefix/dir across reinit.
                    java.io.File restartDir;
                    String restartPrefix;
                    if (pendingRecordingPrefix != null) {
                        restartDir = pendingRecordingDir;
                        restartPrefix = pendingRecordingPrefix;
                    } else if (activeRecordingPrefix != null) {
                        restartDir = activeRecordingDir;
                        restartPrefix = activeRecordingPrefix;
                    } else {
                        restartDir = null;
                        restartPrefix = null;
                    }
                    if (restartPrefix != null) {
                        logger.info("Codec reinit: resuming normal recording (prefix="
                            + restartPrefix + ")");
                        startRecording(restartDir, restartPrefix);
                    } else {
                        logger.info("Codec reinit: resuming normal recording (no captured prefix — default 'cam')");
                        startRecording();
                    }
                } else if (recordingMode || pendingRecordingPrefix != null) {
                    // Deferred-recording window — see applyBitrateChangeLocked.
                    java.io.File restartDir;
                    String restartPrefix;
                    if (pendingRecordingPrefix != null) {
                        restartDir = pendingRecordingDir;
                        restartPrefix = pendingRecordingPrefix;
                    } else if (activeRecordingPrefix != null) {
                        restartDir = activeRecordingDir;
                        restartPrefix = activeRecordingPrefix;
                    } else {
                        restartDir = null;
                        restartPrefix = null;
                    }
                    if (restartPrefix != null) {
                        logger.info("Codec reinit: resuming deferred recording (prefix="
                            + restartPrefix + ")");
                        startRecording(restartDir, restartPrefix);
                    } else {
                        logger.info("Codec reinit: resuming deferred recording (no captured prefix — default 'cam')");
                        startRecording();
                    }
                }
            }

            logger.info("Codec change applied successfully: " + codec.displayName);

        } catch (Exception e) {
            logger.error("Failed to apply codec change: " + e.getMessage(), e);
            // FIX (audit R4, Findings 1+2): see applyBitrateChangeLocked catch
            // for full reasoning. Force pipeline.stop() instead of calling
            // startRecording() against a stale-encoder recorder; the prior
            // recovery path registered a format-available listener on the
            // released encoder which never fires, wedging recording for the
            // rest of the ACC=ON window.
            logger.warn("Forcing pipeline stop after codec-reinit failure — "
                + "RMM will rebuild on next activation");
            try {
                stop();
            } catch (Throwable t) {
                logger.warn("Failed to stop pipeline after codec change error: "
                    + t.getMessage());
            }
        }
    }

    /**
     * Applies multiple encoder reconfig knobs in a single stop / reinit /
     * restart cycle. The web UI's Quality tab Apply sends quality + codec +
     * fps together; calling apply*Change three times in sequence stops the
     * recorder once but each subsequent call observes wasRecording=false
     * (the deferred-start window) and skips its restart, leaving the pipeline
     * with no recording. Coalescing avoids that and also avoids three
     * back-to-back encoder reinits when one suffices.
     *
     * <p>Pass {@code null} for any knob you don't want to change.
     */
    public void applyBatchedChange(
            GpuPipelineConfig.RecordingQuality quality,
            GpuPipelineConfig.VideoCodec codec,
            Integer fps) {
        synchronized (reconfigLock) {
            // Three knobs, three different reconfig costs:
            //   - Bitrate: inline via MediaCodec.setParameters(VIDEO_BITRATE).
            //     Encoder stays alive, recording continues without a gap.
            //     setBitrate() also resizes the pre-record buffer to match.
            //   - FPS: camera HAL emission rate is inline via setCameraFps;
            //     encoder's KEY_FRAME_RATE is metadata for rate control and
            //     can ONLY be set at create time. We deliberately leave the
            //     encoder running with stale KEY_FRAME_RATE — bitrate accuracy
            //     drifts slightly until a natural reinit (codec/quality
            //     change or ACC cycle), but recording keeps producing frames
            //     with zero gap. Mirrors how SurveillanceEngineGpu treats
            //     setCameraTargetFps as a hint, not a teardown trigger.
            //   - Codec: requires full encoder reinit. There is no MediaCodec
            //     API for runtime codec change; KEY_MIME_TYPE is set at
            //     configure() and the encoder must be released and recreated.
            //
            // So we only stop / reinit / restart when codec actually changes.
            // Quality- and FPS-only updates are inline.
            boolean codecChanged = false;
            int newBitrate = -1;

            if (quality != null) {
                config.setRecordingQuality(quality);
                int eff = config.getEffectiveBitrate();
                if (encoder != null && encoder.getBitrate() != eff) {
                    newBitrate = eff;
                }
            }
            if (codec != null) {
                config.setVideoCodec(codec);
                String want = config.getCodecMimeType();
                if (encoder == null || !encoder.getCodecMimeType().equals(want)) {
                    codecChanged = true;
                }
            }
            int clampedFps = -1;
            if (fps != null) {
                clampedFps = Math.max(10, Math.min(30, fps));
                try {
                    org.json.JSONObject cameraCfg = com.overdrive.app.config.UnifiedConfigManager
                        .loadConfig().optJSONObject("camera");
                    if (cameraCfg == null) cameraCfg = new org.json.JSONObject();
                    cameraCfg.put("targetFps", clampedFps);
                    com.overdrive.app.config.UnifiedConfigManager.updateSection("camera", cameraCfg);
                } catch (Exception e) {
                    logger.warn("Batched apply: failed to persist targetFps: " + e.getMessage());
                }
                if (camera != null) camera.setTargetFps(clampedFps);
            }

            // FIX (audit R7): gate against concurrent stop() teardown. Config
            // persistence above is fine (RMM re-reads on next activation); skip
            // the live reconfig so we don't half-rebuild against a torn-down
            // encoder/recorder.
            if (!running || stopping) {
                logger.warn("Batched apply: settings persisted but skipping live apply "
                    + "(running=" + running + ", stopping=" + stopping + ")");
                return;
            }

            if (encoder == null) {
                logger.info("Batched apply: encoder not yet initialized — settings persisted, will apply on init");
                return;
            }

            // Inline-update path: bitrate change without codec change. Encoder
            // stays alive, recording continues seamlessly.
            if (!codecChanged) {
                if (newBitrate > 0) {
                    try {
                        encoder.setBitrate(newBitrate);
                        if (bitrateController != null) {
                            bitrateController.setImmediateBitrate(newBitrate);
                        }
                        logger.info("Batched apply: inline bitrate "
                            + (newBitrate / 1_000_000) + " Mbps");
                    } catch (Exception e) {
                        logger.warn("Batched apply: inline bitrate failed: " + e.getMessage());
                    }
                }
                if (clampedFps > 0) {
                    // Resize the encoder's pre-record buffer pool to match.
                    // Doesn't touch MediaCodec — KEY_FRAME_RATE is configure-only
                    // per the Android API. The encoder keeps producing at the
                    // surface's actual delivery rate; rate control recalibrates
                    // over a few seconds.
                    encoder.setTargetFps(clampedFps);
                    logger.info("Batched apply: inline FPS " + clampedFps
                        + " (camera HAL + encoder buffer pool resized; encoder"
                        + " KEY_FRAME_RATE remains configure-time)");
                }
                if (newBitrate <= 0 && clampedFps <= 0) {
                    logger.info("Batched apply: nothing changed");
                }
                // FIX (audit R5): inline-success — kick deferred recording.
                // Idempotent if no pending or already recording.
                if (pendingRecordingPrefix != null) {
                    logger.info("Batched inline success — kicking deferred recording check");
                    try { checkPendingRecording(); }
                    catch (Throwable t) { logger.warn("Deferred-recording kick failed: " + t.getMessage()); }
                }
                return;
            }

            // Codec-change path: full reinit cycle. Stops current recording,
            // releases old encoder, creates new one with the new MIME type
            // (and the latest bitrate/fps from config), restarts recording.
            logger.info("Batched apply: codec changed — reinitializing encoder (quality=" + quality
                + ", codec=" + codec + ", fps=" + (fps == null ? "n/a" : clampedFps) + ")");

            boolean wasSurveillance = currentMode == Mode.SURVEILLANCE;
            boolean wasNormalRecording = currentMode == Mode.NORMAL_RECORDING;
            boolean wasRecording = isRecording() || pendingRecordingPrefix != null || recordingMode;

            try {
                if (wasRecording && recorder != null && recorder.isRecording()) {
                    recorder.stopRecording();
                    Thread.sleep(500);
                }

                reinitializeEncoder();

                if (bitrateController != null && newBitrate > 0) {
                    bitrateController.setImmediateBitrate(newBitrate);
                }

                if (wasRecording) {
                    if (wasSurveillance) {
                        enableSurveillance();
                    } else if (wasNormalRecording) {
                        // FIX (audit R6): preserve session prefix/dir across reinit.
                        java.io.File restartDir;
                        String restartPrefix;
                        if (pendingRecordingPrefix != null) {
                            restartDir = pendingRecordingDir;
                            restartPrefix = pendingRecordingPrefix;
                        } else if (activeRecordingPrefix != null) {
                            restartDir = activeRecordingDir;
                            restartPrefix = activeRecordingPrefix;
                        } else {
                            restartDir = null;
                            restartPrefix = null;
                        }
                        if (restartPrefix != null) {
                            logger.info("Batched apply: resuming normal recording (prefix="
                                + restartPrefix + ")");
                            startRecording(restartDir, restartPrefix);
                        } else {
                            logger.info("Batched apply: resuming normal recording (no captured prefix — default 'cam')");
                            startRecording();
                        }
                    } else if (recordingMode || pendingRecordingPrefix != null) {
                        // Deferred-recording window — see applyBitrateChangeLocked.
                        java.io.File restartDir;
                        String restartPrefix;
                        if (pendingRecordingPrefix != null) {
                            restartDir = pendingRecordingDir;
                            restartPrefix = pendingRecordingPrefix;
                        } else if (activeRecordingPrefix != null) {
                            restartDir = activeRecordingDir;
                            restartPrefix = activeRecordingPrefix;
                        } else {
                            restartDir = null;
                            restartPrefix = null;
                        }
                        if (restartPrefix != null) {
                            logger.info("Batched apply: resuming deferred recording (prefix="
                                + restartPrefix + ")");
                            startRecording(restartDir, restartPrefix);
                        } else {
                            logger.info("Batched apply: resuming deferred recording (no captured prefix — default 'cam')");
                            startRecording();
                        }
                    }
                }
                logger.info("Batched apply: codec reinit complete");
            } catch (Exception e) {
                logger.error("Batched apply failed: " + e.getMessage(), e);
                // FIX (audit R4, Findings 1+2): see applyBitrateChangeLocked
                // catch for full reasoning. Force pipeline.stop() so the
                // recorder isn't left bound to a released encoder with a
                // dead format-available listener.
                logger.warn("Forcing pipeline stop after batched-reinit failure — "
                    + "RMM will rebuild on next activation");
                try {
                    stop();
                } catch (Throwable t) {
                    logger.warn("Batched apply: stop failed: " + t.getMessage());
                }
            }
        }
    }

    /**
     * Returns true if the encoder is alive and its configured FPS no longer
     * matches the user's selected FPS in unified config. Caller (typically
     * RecordingModeManager at the start of an ACC ON activation) is expected
     * to follow up with a {@link #stop()} so the next {@link #start()} re-runs
     * {@link #init()} and picks up the new FPS through {@link #loadTargetFps()}.
     *
     * Returning false is the no-action case: encoder hasn't been built yet
     * (next start() will pick up config naturally), pipeline isn't running,
     * or FPS is already current.
     */
    public boolean isFpsConfigStale() {
        if (!running || encoder == null) return false;
        return encoder.getFps() != loadTargetFps();
    }

    /**
     * Reads the user-selected camera FPS from unified config.
     * Falls back to 15 if missing or unreadable. Restricted to BYD-supported
     * values {8, 15, 25} via the UI; other values are clamped to 15 by the
     * settings API before being persisted.
     */
    private static int loadTargetFps() {
        try {
            org.json.JSONObject cameraConfig = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cameraConfig != null) {
                return cameraConfig.optInt("targetFps", 15);
            }
        } catch (Exception ignored) {}
        return 15;
    }

    /**
     * Reads the user-selected ACC-off surveillance camera FPS from unified
     * config (camera.surveillanceTargetFps). Falls back to the ACC-on
     * targetFps, then to 15 — so a config predating the split (key absent)
     * resolves to EXACTLY the pre-split rate (byte-identical). Same {8,15,25}
     * UI restriction as loadTargetFps(); the settings API clamps before
     * persisting.
     */
    private static int loadSurveillanceTargetFps() {
        try {
            org.json.JSONObject cameraConfig = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cameraConfig != null) {
                int accOnFallback = cameraConfig.optInt("targetFps", 15);
                return cameraConfig.optInt("surveillanceTargetFps", accOnFallback);
            }
        } catch (Exception ignored) {}
        return 15;
    }

    /**
     * Resolves the configured ACC-off surveillance quality tier from unified
     * config (recording.surveillanceQuality). Returns {@code null} when the key
     * is ABSENT (pre-split config) so the caller
     * ({@link GpuPipelineConfig#getEffectiveBitrateForQuality}) falls back to
     * the ACC-on recordingQuality tier — byte-identical to the pre-split world.
     * A present-but-unparseable value degrades to STANDARD via fromString, the
     * same default recordingQuality itself uses.
     */
    private static GpuPipelineConfig.RecordingQuality loadSurveillanceQuality() {
        try {
            org.json.JSONObject rec = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("recording");
            if (rec != null) {
                String q = rec.optString("surveillanceQuality", null);
                if (q != null) {
                    return GpuPipelineConfig.RecordingQuality.fromString(q);
                }
            }
        } catch (Exception ignored) {}
        return null;  // null => getEffectiveBitrateForQuality falls back to recordingQuality
    }

    /**
     * Push the camera layout, the DiLink 4 producer-corner/flip map, and the
     * dilink4-only red-mask + APA inset onto the CURRENT {@link #recorder}.
     *
     * <p>Single source of truth, called from BOTH the init path and
     * {@link #reinitializeEncoder()}. Reinit can allocate a brand-new
     * {@code GpuMosaicRecorder}, whose {@code cameraLayout} defaults to 0 and
     * whose producer-corner map is all zeros; before this was factored out, only
     * init pushed them, so a codec/quality/fps change on a working DiLink 4 car
     * silently reverted the recorder to legacy 4-strip geometry with every
     * output quadrant sampling the producer's top-left corner.
     *
     * <p>Layout 1 = full-frame passive APA. Layout 3 = DiLink 4 four-corner
     * remap. Layout 0 = legacy 4-strip.
     *
     * <p>Layout 3 is the known-good four-corner arrangement for that HAL:
     * Front=TL X-mirrored, Right=BR, Rear=TR, Left=BL, NO Y flip on any role.
     * A previous comment here claimed rear/left were Y-flipped; that was wrong
     * on device (front and right were the inverted tiles). Do not reintroduce Y
     * bits without a device frame showing rear/left inverted.
     */
    private void applyRecorderDilink4Layout() {
        GpuMosaicRecorder rec = recorder;
        if (rec == null) return;
        int layoutMode = camera != null ? camera.getCameraLayoutMode() : 0;
        rec.setCameraLayout(layoutMode);
        if (layoutMode == 3) {
            rec.setProducerLayout(
                com.overdrive.app.camera.Dilink4Constants.CORNER_FRONT,
                com.overdrive.app.camera.Dilink4Constants.CORNER_RIGHT,
                com.overdrive.app.camera.Dilink4Constants.CORNER_REAR,
                com.overdrive.app.camera.Dilink4Constants.CORNER_LEFT,
                com.overdrive.app.camera.Dilink4Constants.FLIP_FRONT,
                com.overdrive.app.camera.Dilink4Constants.FLIP_RIGHT,
                com.overdrive.app.camera.Dilink4Constants.FLIP_REAR,
                com.overdrive.app.camera.Dilink4Constants.FLIP_LEFT);
        }
        try {
            org.json.JSONObject camCfg = com.overdrive.app.config
                .UnifiedConfigManager.loadConfig().optJSONObject("camera");
            if (camCfg != null && layoutMode != 0) {
                // LAYOUT-GATED. The red-mask GLSL block sits OUTSIDE the
                // `uApaMode > 2.5` branch chain in every shader, so unlike the
                // corner/flip uniforms it is NOT structurally inert on legacy
                // cars: pushing 1.0 would desaturate every red-dominant pixel
                // (brake lights, red vehicles) in a LEGACY car's recordings.
                // The flag is a dilink4-only remedy — its API handler, its UI
                // label and its own comments all say so — so gate to match.
                rec.setRedMaskEnabled(camCfg.optBoolean("dilink4RedMask", false));
                rec.setApaCenterInset(layoutMode == 3
                    ? (float) camCfg.optDouble(
                        "dilink4ApaCenterInset", 0.09375)
                    : 0.0f);
            }
        } catch (Throwable t) {
            logger.warn("Failed to apply dilink4 red-mask/inset to recorder: " + t.getMessage());
        }
    }

    /**
     * Reinitializes the encoder with current config settings.
     * This is a synchronous operation that waits for completion.
     *
     * SOTA: Properly synchronizes with GL thread to prevent EGL_BAD_SURFACE errors.
     */
    private void reinitializeEncoder() throws Exception {
        logger.info("Reinitializing encoder...");
        
        // SOTA: First, release recorder's encoder surface on GL thread
        // This prevents EGL_BAD_SURFACE errors when the encoder is released
        if (camera != null && camera.getGlHandler() != null && recorder != null) {
            final Object releaseLock = new Object();
            final boolean[] releaseDone = {false};
            
            camera.getGlHandler().post(() -> {
                try {
                    // Release recorder's surface (it will be recreated after new encoder is ready)
                    recorder.releaseEncoderSurface();
                    logger.info("Recorder encoder surface released on GL thread");
                } catch (Exception e) {
                    logger.warn("Error releasing recorder surface: " + e.getMessage());
                } finally {
                    synchronized (releaseLock) {
                        releaseDone[0] = true;
                        releaseLock.notify();
                    }
                }
            });
            
            // Wait for GL thread to release surface (max 1 second)
            synchronized (releaseLock) {
                if (!releaseDone[0]) {
                    releaseLock.wait(1000);
                }
            }
        }
        
        // Now safe to release old encoder
        if (encoder != null) {
            // Wait for any pending writes to complete
            if (encoder.isWritingToFile()) {
                logger.info("Waiting for encoder to finish writing...");
                encoder.flushAndClose();
                Thread.sleep(200);
            }
            // Consume the release verdict (audit follow-up): a wedge discovered
            // here means a worker is still alive on the old codec — release()
            // already requested the trip-safe restart, and constructing the
            // replacement below would hide the wedged original from every close
            // guard. Abort the recreate; the caller's failure handling applies.
            if (!encoder.release()) {
                encoder = null;
                throw new IllegalStateException("encoder teardown wedged — refusing to "
                    + "create a replacement codec (trip-safe restart pending)");
            }
            encoder = null;
        }
        
        // Create new encoder with current config
        String codecMimeType = config.getCodecMimeType();
        int bitrate = config.getEffectiveBitrate();
        int fps = loadTargetFps();

        boolean isDilink5 = com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported();
        if (isDilink5) {
            if (config != null && config.is4K()) {
                this.encoderWidth = 3840;
                this.encoderHeight = 2160;
                codecMimeType = "video/hevc";
                com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.set4KUltraEnabled(true);
            } else {
                this.encoderWidth = 1920;
                this.encoderHeight = 1080;
                com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.set4KUltraEnabled(false);
            }
        }

        logger.info("Creating new encoder: " +
            (codecMimeType.contains("hevc") ? "H.265" : "H.264") +
            " " + encoderWidth + "x" + encoderHeight +
            " @ " + fps + "fps, " + (bitrate / 1_000_000) + " Mbps");

        // FIX (audit R4, Findings 1+2): on encoder allocation failure, ensure
        // both the encoder field AND the recorder's internal encoder reference
        // are cleared so the caller's catch (which now calls stop()) sees a
        // coherent torn-down state. Without this, a throw here leaves the
        // recorder bound to the released-and-nulled encoder, and stop()
        // would then try to flushAndClose against a NULL encoder field while
        // recorder.encoder still points at a freed instance.
        try {
            encoder = new HardwareEventRecorderGpu(encoderWidth, encoderHeight, fps, bitrate, codecMimeType);
            encoder.setManualClipRetentionDuration(
                com.overdrive.app.recording.ManualClipService.getConfiguredRetentionSeconds());
        } catch (Throwable t) {
            logger.warn("New encoder allocation failed — clearing recorder's stale "
                + "encoder ref so caller can stop() cleanly: " + t.getMessage());
            encoder = null;
            // Best-effort: drop the recorder's internal encoder ref by releasing
            // its surface again (the prior releaseEncoderSurface() covered the
            // GL surface; this path now has no live encoder to bind to).
            if (recorder != null) {
                try {
                    final GpuMosaicRecorder snapRec = recorder;
                    if (camera != null && camera.getGlHandler() != null) {
                        camera.getGlHandler().post(() -> {
                            try { snapRec.releaseEncoderSurface(); }
                            catch (Throwable ignored) {}
                        });
                    }
                } catch (Throwable ignored) {}
            }
            throw t instanceof Exception ? (Exception) t : new RuntimeException(t);
        }
        // On encoder reinit (codec change), restore the pre-record window
        // from the source-of-truth config for the ACTIVE recording mode.
        // Without mode-awareness, a codec change while in PROXIMITY_GUARD
        // would silently revert to the sentry/surveillance value, ignoring
        // the proximity tab's slider until the next setMode() cycle.
        //
        // The proximity controller's setPreRecordDuration call (after
        // reinit completes) is the long-term source of truth, but we seed
        // the encoder here with the right value up-front so the byte
        // ring's first allocations / window are correctly sized.
        try {
            int preRecordSec = -1;
            // Prefer proximity's value when the active mode is proximity guard.
            try {
                com.overdrive.app.recording.RecordingModeManager rmm =
                    com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                if (rmm != null
                        && rmm.getCurrentMode() == com.overdrive.app.recording.RecordingModeManager.Mode.PROXIMITY_GUARD) {
                    org.json.JSONObject pgCfg =
                        com.overdrive.app.config.UnifiedConfigManager.getProximityGuard();
                    int v = pgCfg.optInt("preRecordSeconds", -1);
                    if (v > 0) preRecordSec = v;
                }
            } catch (Throwable t) {
                logger.debug("Proximity-mode pre-record lookup failed: " + t.getMessage());
            }
            // Fallback: surveillance config (the historical source).
            if (preRecordSec <= 0) {
                SurveillanceConfigManager cfgMgr = new SurveillanceConfigManager();
                if (cfgMgr.configExists()) {
                    SurveillanceConfig survCfg = cfgMgr.loadConfig();
                    preRecordSec = survCfg.getPreRecordSeconds();
                }
            }
            if (preRecordSec > 0) {
                encoder.setPreRecordDuration(preRecordSec);
            }
        } catch (Exception e) {
            logger.warn("Failed to apply pre-record duration on reinit: " + e.getMessage());
        }
        // Wire the StorageManager cleanup gate against the new encoder so
        // post-save / periodic / sidecar cleanup paths defer their delete
        // bursts while we're mid-write. Field-deref lambda (audit P1) so a
        // future reinit that swaps `encoder` is reflected without rebinding —
        // the older `enc::isWritingToFile` form captured the *instance* and
        // would return false on a released encoder, leaving cleanup un-gated
        // during the reinit window.
        //
        // Wired BEFORE encoder.init() (audit: probeWired gate): a persistent
        // encoder.init() failure (codec configure timeout / OOM) must NOT leave
        // probeWired=false forever, which would silently disable the ENTIRE
        // periodic limit-enforcement ticker (including the encoder-independent
        // trips/proximity categories). The lambda already null-guards the field
        // and returns false until the encoder both exists and is actually
        // writing, so wiring it before init() preserves the anti-fail-open
        // intent while flipping probeWired=true on the first init attempt.
        try {
            com.overdrive.app.storage.StorageManager.getInstance()
                .setEncoderWritingProbe(() -> {
                    HardwareEventRecorderGpu e = this.encoder;
                    return e != null && e.isWritingToFile();
                });
        } catch (Exception e) {
            logger.warn("Failed to wire encoder writing probe: " + e.getMessage());
        }

        encoder.init();

        // Re-seed the clip segment length after a codec/quality reinit so the
        // fresh encoder keeps the user's chosen rotation interval.
        applySegmentDurationFromConfig();

        // Reinitialize recorder with new encoder on GL thread
        if (camera != null && camera.getEglCore() != null) {
            final Object initLock = new Object();
            final boolean[] initDone = {false};
            final Exception[] initError = {null};
            
            camera.getGlHandler().post(() -> {
                try {
                    // Recreate recorder if needed
                    if (recorder == null) {
                        // FIX (audit R6): use the cached profile-driven offsets
                        // + actual encoderWidth/encoderHeight instead of the
                        // no-arg constructor's DEFAULT_VIEWPORT_*. Without this,
                        // a Tang trim (encoderHeight=1440) would silently regress
                        // to 2560x1920 and corrupt encoder strip slicing. Falls
                        // through to no-arg only when init() has not yet captured
                        // a profile (cold-start race; should never happen on
                        // this code path because reinit only runs after init).
                        if (lastQuadrantStripOffsetX != null) {
                            logger.info("Reinit: rebuilding recorder with cached profile offsets ("
                                + encoderWidth + "x" + encoderHeight + ")");
                            recorder = new GpuMosaicRecorder(
                                lastQuadrantStripOffsetX, encoderWidth, encoderHeight);
                        } else {
                            logger.warn("Reinit: no cached profile offsets — falling back to no-arg "
                                + "GpuMosaicRecorder (Tang trims may be miss-sized)");
                            recorder = new GpuMosaicRecorder();
                        }
                        // FIX (audit R1, RESIDUAL): re-wire segment-rotated
                        // listener after a fresh recorder allocation so RMM
                        // wedge-ticker grace-windowing keeps working post-
                        // encoder-reinit.
                        recorder.setSegmentRotatedListener(this::noteSegmentRotated);
                        // FIX: a FRESH recorder starts with cameraLayout=0 and
                        // an all-zero producer-corner map, because the DiLink 4
                        // layout push lives only in the init path above. Without
                        // re-applying it here, a codec/quality/fps change on a
                        // WORKING DiLink 4 car silently reverts the recorder to
                        // legacy 4-strip geometry — all four output quadrants
                        // then sample the producer's top-left corner and the
                        // recording is garbled until the next daemon restart.
                        applyRecorderDilink4Layout();
                    }
                    recorder.init(camera.getEglCore(), encoder);
                    logger.info("Recorder reinitialized on GL thread");
                } catch (Exception e) {
                    initError[0] = e;
                    logger.error("Failed to reinitialize recorder on GL thread", e);
                } finally {
                    synchronized (initLock) {
                        initDone[0] = true;
                        initLock.notify();
                    }
                }
            });
            
            // Wait for GL thread initialization (max 3 seconds)
            synchronized (initLock) {
                if (!initDone[0]) {
                    initLock.wait(3000);
                }
            }
            
            if (initError[0] != null) {
                throw initError[0];
            }
            
            if (!initDone[0]) {
                throw new RuntimeException("Encoder reinitialization timed out");
            }
        }
        
        // Update bitrate controller. Release the OLD one before replacing it:
        // AdaptiveBitrateController holds a hard reference to the encoder it was
        // constructed against, and its ramp animator calls encoder.setBitrate()
        // from a callback. Dropping the reference without release() leaves a
        // running animator driving the encoder we just tore down above — a
        // use-after-release on the stale MediaCodec, not merely a leaked object.
        // release() only cancels the animator (no encoder interaction), so it is
        // safe to call unconditionally here. Mirrors the correct teardown at
        // stop() and the start()-rollback path.
        if (bitrateController != null) {
            try {
                bitrateController.release();
            } catch (Throwable t) {
                logger.warn("Stale bitrateController release failed: " + t.getMessage());
            }
            bitrateController = new AdaptiveBitrateController(encoder, bitrate);
        }

        // Preserve deferred-recording intent across the encoder swap. If the
        // user had recording active and a chain of apply* calls reinit the
        // encoder more than once (multi-setting POST: quality + codec + fps),
        // the format-available listener registered against the prior encoder
        // dies with it — and `wasRecording = isRecording()` reads false on
        // the second/third apply, so the normal restart path skips. Re-arm
        // the listener here so the new encoder's first frame still triggers
        // checkPendingRecording().
        if (recordingMode || pendingRecordingPrefix != null) {
            encoder.setFormatAvailableListener(() -> {
                new Thread(() -> {
                    try {
                        checkPendingRecording();
                    } catch (Exception e) {
                        logger.warn("Deferred recording start (post-reinit) failed: " + e.getMessage());
                    }
                }, "PendingRecKickoffReinit").start();
            });
        }

        logger.info("Encoder reinitialized successfully: " +
            (codecMimeType.contains("hevc") ? "H.265" : "H.264") +
            " @ " + (bitrate / 1_000_000) + " Mbps");
    }
    
    /**
     * Initializes the complete GPU pipeline.
     * 
     * @throws Exception if initialization fails
     */
    public void init() throws Exception {
        init(savedAssetManager, savedContext);
    }
    
    /**
     * Initializes the complete GPU pipeline with AssetManager for YOLO.
     * 
     * @param assetManager Android AssetManager for loading YOLO model (null = skip YOLO)
     * @throws Exception if initialization fails
     */
    public void init(android.content.res.AssetManager assetManager) throws Exception {
        init(assetManager, null);
    }
    
    /**
     * Initializes the complete GPU pipeline with Context for Java TFLite.
     * 
     * @param assetManager Android AssetManager (unused, kept for compatibility)
     * @param context Android Context for TFLite initialization
     * @throws Exception if initialization fails
     */
    public void init(android.content.res.AssetManager assetManager, android.content.Context context) throws Exception {
        if (initialized) {
            logger.warn("Already initialized");
            return;
        }
        
        // Save for re-initialization after stop/start cycle
        if (assetManager != null) this.savedAssetManager = assetManager;
        if (context != null) this.savedContext = context;
        
        logger.info("Initializing GPU surveillance pipeline...");
        
        // Ensure output directory exists
        if (!eventOutputDir.exists()) {
            eventOutputDir.mkdirs();
        }
        
        // SOTA: Release any stuck encoder resources before creating new one
        // This helps recover from previous crashes that left encoder in bad state.
        // The verdict is consumed (audit follow-up): release() returning false
        // means a worker on the OLD encoder is still alive — creating the
        // replacement below would hide it from every close guard, so abort and
        // latch this pipeline terminal. A THROW from release() is treated the
        // same way: release() contains its own failure handling internally, so
        // an escaping throw means teardown state is unknown — not safe to build
        // over.
        if (encoder != null) {
            logger.info("Releasing previous encoder before reinit...");
            boolean prevReleaseClean = false;
            try {
                prevReleaseClean = encoder.release();
            } catch (Exception e) {
                logger.warn("Error releasing previous encoder: " + e.getMessage());
            }
            encoder = null;
            if (!prevReleaseClean) {
                pipelineTeardownWedged = true;
                throw new IllegalStateException("previous encoder teardown wedged — "
                    + "refusing to build a replacement (trip-safe restart pending)");
            }
        }
        
        // 1. Create hardware encoder (shared by normal recording and surveillance)
        // Use config settings for bitrate, codec, and FPS. The encoder's KEY_FRAME_RATE
        // must match the camera's setCameraFps(), otherwise the encoder's PTS pacing
        // diverges from actual frame delivery and recorded video plays back at the
        // wrong speed (faster or slower than realtime).
        String codecMimeType = config.getCodecMimeType();
        int bitrate = config.getEffectiveBitrate();
        int fps = loadTargetFps();

        boolean isDilink5 = com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported();
        if (isDilink5) {
            if (config != null && config.is4K()) {
                this.encoderWidth = 3840;
                this.encoderHeight = 2160;
                codecMimeType = "video/hevc";
                com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.set4KUltraEnabled(true);
            } else {
                this.encoderWidth = 1920;
                this.encoderHeight = 1080;
                com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.set4KUltraEnabled(false);
            }
        }

        logger.info("Creating encoder with config: " +
            (codecMimeType.contains("hevc") ? "H.265" : "H.264") +
            " " + encoderWidth + "x" + encoderHeight +
            " @ " + fps + "fps, " + (bitrate / 1_000_000) + " Mbps");
        encoder = new HardwareEventRecorderGpu(encoderWidth, encoderHeight, fps, bitrate, codecMimeType);
        encoder.setManualClipRetentionDuration(
            com.overdrive.app.recording.ManualClipService.getConfiguredRetentionSeconds());

        // Pre-load saved pre-record duration BEFORE encoder.init() so the
        // byte ring is sized correctly on first allocation. Mode-aware:
        // when the persisted mode is PROXIMITY_GUARD, prefer the proximity
        // tab's value so cold boot is symmetric with the codec-reinit
        // path (see reinitializeEncoder at the same point in the file).
        // Without this, cold boot with mode=PROXIMITY_GUARD briefly sizes
        // the ring to surveillance's value before proximityController.start()
        // resizes it; functionally fine (no realloc) but inconsistent.
        SurveillanceConfig preLoadedConfig = null;
        int preRecordSec = -1;
        try {
            // Mode-aware preference: read mode FROM CONFIG (RecordingModeManager
            // isn't yet constructed at this point in init()). UnifiedConfigManager
            // exposes the persisted mode under recording.mode.
            try {
                org.json.JSONObject recCfg =
                    com.overdrive.app.config.UnifiedConfigManager.getRecording();
                String persistedMode = recCfg.optString("mode", "");
                if ("PROXIMITY_GUARD".equals(persistedMode)) {
                    org.json.JSONObject pgCfg =
                        com.overdrive.app.config.UnifiedConfigManager.getProximityGuard();
                    int v = pgCfg.optInt("preRecordSeconds", -1);
                    if (v > 0) preRecordSec = v;
                }
            } catch (Throwable t) {
                logger.debug("Cold-boot mode-aware pre-record lookup failed: " + t.getMessage());
            }
            if (preRecordSec <= 0) {
                SurveillanceConfigManager configManager = new SurveillanceConfigManager();
                if (configManager.configExists()) {
                    preLoadedConfig = configManager.loadConfig();
                    preRecordSec = preLoadedConfig.getPreRecordSeconds();
                }
            }
            if (preRecordSec > 0) {
                encoder.setPreRecordDuration(preRecordSec);
                logger.info("Pre-applied pre-record duration: " + preRecordSec + "s");
            }
        } catch (Exception e) {
            logger.warn("Failed to pre-load config (will retry after init): " + e.getMessage());
        }

        // Wire the StorageManager cleanup gate (RC9). Field-deref lambda so
        // reinit-driven encoder swaps don't leave a stale instance ref.
        //
        // Wired BEFORE encoder.init() (audit: probeWired gate): a persistent
        // encoder.init() failure (codec configure timeout / OOM) must NOT leave
        // probeWired=false forever, which would silently disable the ENTIRE
        // periodic limit-enforcement ticker (including the encoder-independent
        // trips/proximity categories). The lambda null-guards the field and
        // returns false until the encoder exists AND is writing, so this keeps
        // the anti-fail-open intent while flipping probeWired=true on the first
        // init attempt regardless of whether init() later throws.
        try {
            com.overdrive.app.storage.StorageManager.getInstance()
                .setEncoderWritingProbe(() -> {
                    HardwareEventRecorderGpu e = this.encoder;
                    return e != null && e.isWritingToFile();
                });
        } catch (Exception e) {
            logger.warn("Failed to wire encoder writing probe: " + e.getMessage());
        }

        encoder.init();

        // Seed the clip segment length from the shared recording config so the
        // ACC-on dashcam axis rotates at the user's chosen interval (2/5/10
        // min). Same key the ACC-off / OEM axis reads — one control, both axes.
        applySegmentDurationFromConfig();

        // Resolve the camera profile NOW so the recorder, downscaler, foveated
        // cropper, and PanoramicCameraGpu all share consistent per-quadrant
        // strip-X offsets. Profile inference uses the vehicle model + any
        // user-saved override in UnifiedConfigManager.camera.cameraProfile.
        com.overdrive.app.camera.ResolvedCameraConfig resolvedCamera =
            com.overdrive.app.camera.CameraConfigResolver.resolve(getVehicleModel());
        float[] quadrantStripOffsetX = resolvedCamera.getQuadrantStripOffsetX();
        float[] quadrantCornerOffsetsXY = resolvedCamera.getQuadrantCornerOffsetsXY();
        // FIX (audit R6): cache for reinitializeEncoder()'s recorder=null branch.
        this.lastQuadrantStripOffsetX = quadrantStripOffsetX;

        // 2. Create GPU mosaic recorder (shared) with profile-driven viewport
        // and per-quadrant offsets. Tang gets 2560x1440 instead of 2560x1920.
        recorder = new GpuMosaicRecorder(quadrantStripOffsetX, encoderWidth, encoderHeight);
        // Note: recorder.init() will be called after EGL context is created by camera

        // FIX (audit R1, RESIDUAL): stamp lastSegmentRotateMs on every
        // segment close so RecordingModeManager's wedge ticker can grace-
        // window the between-segments isRecording()=false flicker.
        recorder.setSegmentRotatedListener(this::noteSegmentRotated);

        // Wire up telemetry collector to new recorder if available
        if (telemetryCollector != null) {
            recorder.setTelemetryCollector(telemetryCollector);
        }
        // Apply persisted overlay enabled state to new recorder
        recorder.setOverlayEnabled(overlayEnabledConfig);
        // Apply the layout profile that matches the current mode (dashcam vs
        // sentry) to the freshly-created recorder. IDLE/normal-recording use
        // the dashcam profile; surveillance uses its own.
        applyActiveLayoutProfile();

        // 3. Create GPU downscaler with profile-driven offsets.
        // FIX (EGL-leak audit): defensively release any prior instance before
        // overwriting the reference. stop() now releases it too, but if a
        // prior teardown aborted partway (or a future code path reaches init()
        // with a live downscaler), overwriting the reference here would leak
        // its HandlerThread + EGL context + ImageReader with no way to ever
        // reclaim them. release() is synchronous and idempotent.
        if (downscaler != null) {
            logger.warn("init: previous downscaler was never released — releasing now "
                + "(EGL context/thread would otherwise leak)");
            try { downscaler.release(); } catch (Throwable t) {
                logger.warn("init: stale downscaler release failed: " + t.getMessage());
            }
            downscaler = null;
        }
        downscaler = new GpuDownscaler(quadrantStripOffsetX);
        // Note: downscaler.init() will be called after EGL context is created by camera

        // 4. Create surveillance engine (uses shared recorder).
        //
        // Release any PREVIOUS engine first. init() is re-entered from start()
        // whenever !initialized, and stop() clears initialized in its finally —
        // so every arm/disarm cycle lands here again. Normal teardown paths call
        // sentry.disable() but never sentry.release() (release() is reachable only
        // from the start()-failure rollback), so without this the outgoing engine's
        // executor threads — aiExecutor, aiScheduler, segmentMetadata, mosaicJpeg,
        // storageMaintenance — were abandoned still parked on their queues, one set
        // per cycle until process death. They are daemon threads, which is why the
        // leak stayed invisible. Mirrors the recorder guard above.
        if (sentry != null) {
            try {
                sentry.release();
            } catch (Throwable t) {
                logger.warn("Previous sentry engine release failed: " + t.getMessage());
            }
            sentry = null;
        }
        sentry = new SurveillanceEngineGpu();
        sentry.init(eventOutputDir, downscaler, assetManager, context);  // Pass Context for Java TFLite
        sentry.setRecorder(recorder);  // Share recorder with normal recording
        // Drive the (opt-in, default-off) surveillance telemetry overlay for the
        // exact span of each sentry event clip.
        sentry.setEventOverlayHook(this::applySurveillanceOverlayForEvent);
        // Per-vehicle camera-tile height for the foveated FOV scaling math
        // in DistanceEstimator. Seal=960, Tang=720. Without this the
        // foveated path uses a Seal-specific 0.66 ratio and reads ~30%
        // long on Tang.
        sentry.setCameraStripHeight(
            com.overdrive.app.camera.CameraConfigResolver.isPassiveApaModeEnabled()
                ? encoderHeight : cameraHeight);
        // Per-quadrant vertical FOV from the active camera profile.
        // Without this, the engine uses uniform 110° for all four
        // quadrants — which inflates side-camera distances by ~70%
        // because side mirrors carry tighter optics than the
        // front/rear ultra-wide fisheyes.
        com.overdrive.app.camera.CameraProfile profile = resolvedCamera.getProfile();
        if (profile != null) {
            sentry.setCameraVerticalFovDeg(new float[]{
                    profile.getVerticalFovDeg(0),
                    profile.getVerticalFovDeg(1),
                    profile.getVerticalFovDeg(2),
                    profile.getVerticalFovDeg(3),
            });
        }

        // 4b. Apply saved config (use the pre-loaded one if available so we don't
        // hit disk twice).
        try {
            if (preLoadedConfig == null) {
                SurveillanceConfigManager configManager = new SurveillanceConfigManager();
                if (configManager.configExists()) {
                    preLoadedConfig = configManager.loadConfig();
                }
            }
            if (preLoadedConfig != null) {
                sentry.setConfig(preLoadedConfig);
                logger.info("Loaded saved surveillance config");
            }
        } catch (Exception e) {
            logger.warn("Failed to load saved config, using defaults: " + e.getMessage());
        }
        
        // 5. Create camera (this creates EGL context). Pass the profile's
        // per-quadrant offsets so the foveated cropper + camera-side mosaic
        // math agree with the recorder/downscaler/scaler.
        if (cameraWidth != resolvedCamera.getPanoWidth()
                || cameraHeight != resolvedCamera.getPanoHeight()) {
            logger.warn("Pipeline geometry " + cameraWidth + "x" + cameraHeight
                + " differs from resolved camera profile "
                + resolvedCamera.getPanoWidth() + "x" + resolvedCamera.getPanoHeight()
                + " — restart the daemon to apply the new profile dimensions");
        }
        camera = new PanoramicCameraGpu(cameraWidth, cameraHeight,
            quadrantStripOffsetX, quadrantCornerOffsetsXY);
        camera.setConsumers(recorder, downscaler, sentry);
        // Apply the active layout profile's windshield-source preference to the
        // new camera (dashcam profile at startup/IDLE; the surveillance profile
        // is re-applied when enableSurveillance() runs).
        applyActiveLayoutProfile();

        // Camera FPS config — must match the encoder FPS used above (loadTargetFps())
        // so that camera frame delivery rate matches the encoder's KEY_FRAME_RATE.
        camera.setTargetFps(fps);
        logger.info("Camera targetFps=" + fps + " (from config)");
        logger.info("Resolved camera profile: " + resolvedCamera.getProfile().getDisplayName()
            + " (panoCam=" + resolvedCamera.getPanoCameraId()
            + ", size=" + resolvedCamera.getPanoWidth() + "x" + resolvedCamera.getPanoHeight()
            + ", surfaceMode=" + resolvedCamera.getPanoSurfaceMode() + ")");

        // Camera selection priority:
        //   1. Validated/manual override saved in UnifiedConfigManager → use as-is.
        //   2. BmmCameraInfo system hint → preferred over profile default if available.
        //   3. Profile default (Seal=1, Tang=2).
        if (resolvedCamera.isValidated() || resolvedCamera.isManualPanoOverride()) {
            logger.info("Using saved panoramic config: id=" + resolvedCamera.getPanoCameraId()
                + ", surfaceMode=" + resolvedCamera.getPanoSurfaceMode()
                + (resolvedCamera.isManualPanoOverride() ? " (manual)" : " (validated)"));
            camera.setCameraId(resolvedCamera.getPanoCameraId());
            camera.setCameraSurfaceMode(resolvedCamera.getPanoSurfaceMode());
            camera.setAutoProbeCameras(false);
            // Skip frame validation for saved configs. Luma heuristic produces
            // false negatives in low-light/uniform scenes.
            camera.setSkipFrameValidation(true);
        } else {
            int discoveredId = com.overdrive.app.camera.AvmCameraHelper.discoverPanoCameraId();
            if (discoveredId >= 0) {
                logger.info("Using BmmCameraInfo panoramic hint: camera ID " + discoveredId);
                camera.setCameraId(discoveredId);
            } else {
                logger.info("Using profile default panoramic camera ID "
                    + resolvedCamera.getPanoCameraId());
                camera.setCameraId(resolvedCamera.getPanoCameraId());
            }
            camera.setCameraSurfaceMode(resolvedCamera.getPanoSurfaceMode());
            camera.setAutoProbeCameras(false);
            // OEM-PARITY: dilink4 trusts the HAL camera-id resolution
            // unconditionally — oem's gl/C5920a static-init resolves the
            // camera ID via BmmCameraInfo and never re-probes. Frame-50
            // black-pixel re-probe (PanoramicCameraGpu.java:2233-2251)
            // would call closeCameraForPath + recreateCameraSurface on
            // first-boot — the same close+reopen race we've eliminated
            // everywhere else. Skip frame validation on dilink4.
            boolean dilink4Cam = false;
            try {
                dilink4Cam = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
            } catch (Throwable ignored) {}
            camera.setSkipFrameValidation(dilink4Cam);
            if (dilink4Cam) {
                logger.info("dilink4: skipping frame-50 auto-probe re-validation (oem-parity)");
            }
        }

        // Register probe callback — only used when manual probe is triggered via API
        camera.setCameraProbeCallback((cameraId, surfaceMode) -> {
            logger.info("Probe found working camera: id=" + cameraId + ", surfaceMode=" + surfaceMode);
            try {
                // OBSERVED dims, never the configured cameraWidth/Height —
                // passing the configured pair made probedWidth/Height a
                // self-confirming copy of the profile default, so a car whose
                // HAL emits a different size could never be detected.
                com.overdrive.app.camera.CameraConfigResolver.persistPanoramicProbe(
                    cameraId,
                    surfaceMode,
                    camera.getObservedProducerWidth(),
                    camera.getObservedProducerHeight(),
                    true,
                    false);
                logger.info("Saved camera config for next launch");
            } catch (Exception ex) {
                logger.warn("Failed to save camera config: " + ex.getMessage());
            }
            new Thread(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                checkPendingRecording();
            }, "PendingRecCheck").start();
        });
        
        if (recorder != null) {
            // Camera layout + DiLink 4 producer-corner map + dilink4 red-mask /
            // APA inset. Shared with reinitializeEncoder() so a fresh recorder
            // can never come up with legacy geometry on a DiLink 4 car.
            applyRecorderDilink4Layout();
            // Recording dewarp strength — shader gates on uApaMode==0, so
            // pushing a non-zero value on dilink4 is a no-op (safe).
            // We push regardless of layout so a layout flip later picks up
            // the user's setting without a daemon restart.
            try {
                int rectifyStrength = com.overdrive.app.config
                    .UnifiedConfigManager.getRectifyStrength();
                recorder.setRectifyStrength((float) rectifyStrength);
                // Push tile aspect (tile_height / tile_width) from the
                // active profile so the dewarp's radial math runs in true
                // pixel space. Profile.panoHeight is the per-cam tile
                // height; tile width is panoWidth/4 (4 cams across the
                // strip). Seal 5120×960 → 960 / (5120/4) = 0.75. Tang
                // 5120×720 → 0.5625. Identity-equivalent at slider 0.
                com.overdrive.app.camera.CameraProfile prof = profile;
                if (prof != null) {
                    float tileWidth = Math.max(1, prof.getPanoWidth() / 4f);
                    float tileHeight = Math.max(1, prof.getPanoHeight());
                    recorder.setRectifyAspect(tileHeight / tileWidth);
                }
            } catch (Throwable t) {
                logger.warn("Failed to read rectifyStrength from config: " + t.getMessage());
            }
        }

        // 6. Create adaptive bitrate controller. Defensive release of any
        // pre-existing instance for the same reason as the reinit path above:
        // a surviving animator would keep driving a prior encoder. Normally
        // null here (stop() clears it), but a start() that follows a partial
        // teardown can reach this with a stale controller still set.
        if (bitrateController != null) {
            try {
                bitrateController.release();
            } catch (Throwable t) {
                logger.warn("Stale bitrateController release failed (init): " + t.getMessage());
            }
        }
        bitrateController = new AdaptiveBitrateController(encoder, 6_000_000);

        // Register a single config-change listener that pushes rectifyStrength
        // edits to the live recorder. Listener fires on ANY recording-section
        // update (the listener API is section-granular, not field-granular);
        // we re-read the rectifyStrength field and push, dedupe is handled by
        // the recorder setter (no-op when the value didn't change).
        // Idempotent registration: deregister any prior registration first so
        // re-init paths (encoder reinit, profile change) don't stack listeners.
        try {
            if (rectifyConfigListener != null) {
                com.overdrive.app.config.UnifiedConfigManager
                    .removeListener(rectifyConfigListener);
            }
            rectifyConfigListener = (section, sectionConfig) -> {
                if (!"recording".equals(section)) return;
                GpuMosaicRecorder activeRecorder = recorder;
                if (activeRecorder == null) return;
                int strength = sectionConfig.optInt("rectifyStrength", 0);
                if (strength < 0) strength = 0;
                if (strength > 100) strength = 100;
                activeRecorder.setRectifyStrength((float) strength);
            };
            com.overdrive.app.config.UnifiedConfigManager
                .addListener(rectifyConfigListener);
        } catch (Throwable t) {
            logger.warn("Failed to register rectify config listener: " + t.getMessage());
        }

        initialized = true;
        logger.info( "GPU surveillance pipeline initialized");
    }
    
    /**
     * Starts the GPU pipeline.
     * 
     * @throws Exception if start fails
     */
    public void start() throws Exception {
        start(false);
    }

    // TERMINAL latch (audit follow-up): set when a camera stop — normal stop()
    // or a start()-rollback — reports a wedged teardown. Unlike the global
    // restart-pending flag (which self-clears if the coordinator fails), this
    // never clears: the wedged camera/GL stack cannot be reclaimed in-process,
    // so this pipeline instance must never start again.
    private volatile boolean pipelineTeardownWedged = false;

    // Retiring stream-encoder release verdict (audit follow-up). The stream
    // disable path nulls the encoder fields and queues release() on the
    // single-threaded release executor — so the camera-close guard can never
    // see the retiring encoder, whose drainer keeps dequeuing against the
    // camera until the release completes. stop() verifies this before the
    // camera closes; enableStreaming checks it before installing a replacement.
    //
    // PUBLICATION CONTRACT (audit follow-up 2 — the first revision published
    // the executor future from INSIDE the GL-posted cleanup runnable, so a
    // slow runnable left this null past the caller's 1s latch wait and the
    // guard was silently bypassed; and racing publications could let an OLDER
    // future overwrite a newer one): a placeholder CompletableFuture is
    // published SYNCHRONOUSLY at detach time (before any GL post), completed
    // by the eventual release with its verdict. Pending placeholder ⇒ verify
    // times out ⇒ treated as wedged — exactly right for a GL runnable that
    // never ran. Publications compose (existing AND fresh) so stacked retiring
    // encoders can't shadow an earlier dirty verdict, and verification clears
    // only the exact future it verified (CAS), never a newer one.
    private final java.util.concurrent.atomic.AtomicReference<
            java.util.concurrent.CompletableFuture<Boolean>>
        retiringStreamEncoderRelease = new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Publish a retirement placeholder synchronously at detach time. Returns the
     * FRESH future the caller must hand to {@link #submitEncoderRelease} for
     * completion; the published (possibly combined) future is what verification
     * observes. Callers hold streamLifecycleLock, so publications are ordered;
     * the CAS loop is belt-and-braces.
     */
    private java.util.concurrent.CompletableFuture<Boolean>
            publishRetiringStreamEncoderRelease() {
        java.util.concurrent.CompletableFuture<Boolean> fresh =
            new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CompletableFuture<Boolean> safeFresh =
            fresh.exceptionally(t -> Boolean.FALSE);
        while (true) {
            java.util.concurrent.CompletableFuture<Boolean> cur =
                retiringStreamEncoderRelease.get();
            java.util.concurrent.CompletableFuture<Boolean> pub = (cur == null)
                ? safeFresh
                : cur.thenCombine(safeFresh, (a, b) -> Boolean.TRUE.equals(a)
                    && Boolean.TRUE.equals(b));
            if (retiringStreamEncoderRelease.compareAndSet(cur, pub)) {
                return fresh;
            }
        }
    }

    /**
     * Verify the retiring stream-encoder release (if any) completed CLEANLY
     * within {@code budgetMs}. CAS-clears only the exact future verified clean,
     * so a newer retirement published concurrently is never wiped; a pending or
     * failed release stays, and every subsequent verification re-answers
     * honestly (sticky, same discipline as the worker stops).
     */
    private boolean verifyRetiringStreamEncoderRelease(long budgetMs, String where) {
        // LOOP on CAS failure (audit follow-up 3): a failed clear means a NEWER
        // retirement was published between our get() and the CAS — returning
        // true there verified only the OLD future and let the camera close over
        // the unverified new encoder's drainer. Re-read and verify the current
        // future with whatever remains of the deadline; only a run where the
        // verified future is still current (CAS succeeds) may answer clean.
        final long deadlineNanos = System.nanoTime() + budgetMs * 1_000_000L;
        while (true) {
            java.util.concurrent.CompletableFuture<Boolean> f =
                retiringStreamEncoderRelease.get();
            if (f == null) return true;
            long remainingMs =
                Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
            try {
                boolean clean = Boolean.TRUE.equals(
                    f.get(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS));
                if (!clean) {
                    logger.error(where + ": retiring stream-encoder release reported a "
                        + "wedged worker");
                    return false;
                }
                if (retiringStreamEncoderRelease.compareAndSet(f, null)) {
                    return true;
                }
                // Newer retirement published concurrently — loop and verify it.
                logger.info(where + ": newer stream-encoder retirement published "
                    + "during verification — re-verifying");
            } catch (java.util.concurrent.TimeoutException te) {
                logger.error(where + ": retiring stream-encoder release still pending "
                    + "after the " + budgetMs + "ms budget (release never ran, or is "
                    + "wedged)");
                return false;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.error(where + ": interrupted verifying retiring stream-encoder "
                    + "release");
                return false;
            } catch (Throwable t) {
                logger.error(where + ": retiring stream-encoder release failed: "
                    + t.getMessage());
                return false;
            }
        }
    }
    
    /**
     * Starts the GPU pipeline.
     * 
     * @param autoStartRecording If true, automatically starts recording when recorder is ready
     * @throws Exception if start fails
     */
    public void start(boolean autoStartRecording) throws Exception {
        // CRITICAL: claim the start-in-progress slot to prevent race
        // conditions. Multiple threads may call start() concurrently
        // (HTTP + WebSocket). We use `starting` to block concurrent starts
        // WITHOUT yet publishing running=true — that flag is only flipped
        // once the camera GL-thread runnable confirms successful open.
        // This way pipeline.isRunning() doesn't lie if camera open throws
        // asynchronously and RecordingModeManager's `if (!isRunning())`
        // gates correctly retry on the next trigger.
        synchronized (this) {
            if (running || starting) {
                logger.warn( "Already running");
                return;
            }
            if (stopping) {
                // stop() is mid-teardown (encoders releasing, EGL tearing
                // down). Refuse — caller (cold-start executor) can retry on
                // its next 2-second tick when the lane has settled.
                logger.warn("Refusing start() — pipeline is mid-stop");
                return;
            }
            // A trip-safe process restart is in flight (audit finding: a wedged
            // teardown escalates asynchronously, and its coordinator can retry
            // the trip checkpoint for a while before System.exit). Starting a
            // NEW pipeline in that window would open a second camera/EGL stack
            // alongside the wedged one the restart exists to escape — refuse.
            // The flag self-clears if the coordinator fails, so this cannot
            // permanently brick the daemon; callers retry on their next tick.
            if (com.overdrive.app.daemon.CameraDaemon.isProcessRestartPending()) {
                logger.warn("Refusing start() — trip-safe process restart pending");
                return;
            }
            // LOCAL sticky latch (audit follow-up): the global flag above
            // self-clears if the restart coordinator FAILS (System.exit threw),
            // but the wedged camera/GL stack this pipeline abandoned is still
            // out there — a start after that would build a second stack over
            // it. This instance-level latch never clears; only a real process
            // restart (which replaces the instance) lifts it.
            if (pipelineTeardownWedged) {
                logger.error("Refusing start() — a previous teardown of this pipeline "
                    + "wedged (terminal); process restart required");
                return;
            }
            starting = true;  // Block concurrent starts; running stays false
                              // until camera open is verified.
            // FIX (audit R5): bump generation on start so a retry scheduled
            // by a previous lifecycle that's still hanging around exits.
            long newGen = pipelineGen.incrementAndGet();
            logger.info("Pipeline generation bumped on start: " + newGen);
        }
        
        try {
            // Reinitialize if stopped (encoder/recorder were released)
            if (!initialized) {
                init();
            }
            
            logger.info( "Starting GPU pipeline (autoRecord=" + autoStartRecording + ")...");
            
            // Re-resolve camera config before starting — user may have changed
            // camera ID, profile, or role mappings via the app UI since init.
            // Resolver picks profile-default if no probed/manual config exists,
            // so this also covers "user cleared manual override → revert".
            try {
                com.overdrive.app.camera.ResolvedCameraConfig refreshedCamera =
                    com.overdrive.app.camera.CameraConfigResolver.resolve(getVehicleModel());
                int targetCameraId = refreshedCamera.getPanoCameraId();
                int targetSurfaceMode = refreshedCamera.getPanoSurfaceMode();
                int currentId = camera.getCameraId();
                if (currentId != targetCameraId) {
                    logger.info("Camera config changed since init: " + currentId + " → " + targetCameraId);
                    camera.setCameraId(targetCameraId);
                    camera.setCameraSurfaceMode(targetSurfaceMode);
                    camera.setAutoProbeCameras(false);
                    camera.setSkipFrameValidation(
                        refreshedCamera.isValidated() || refreshedCamera.isManualPanoOverride());
                }
            } catch (Exception e) {
                logger.debug("Camera config re-read failed: " + e.getMessage());
            }
            
            // Start camera (this creates EGL context and initializes downscaler)
            camera.start();
            
            // SOTA: Register yield listener for recording finalization during camera yield.
            // When contention is detected and the camera must yield to the native AVM app,
            // this ensures any active recording is properly finalized (moov atom written)
            // before the camera closes, and recording resumes after re-acquisition.
            camera.setCameraYieldListener(new PanoramicCameraGpu.CameraYieldListener() {
                @Override
                public void onPreYield() {
                    logger.info("Pre-yield: finalizing active recording...");
                    
                    // Stop any active recording to finalize the MP4 file
                    if (recorder != null && recorder.isRecording()) {
                        recorder.stopRecording();
                        logger.info("Pre-yield: recording stopped");
                    }
                    
                    // Flush encoder to ensure all buffered frames are written
                    if (encoder != null && encoder.isWritingToFile()) {
                        encoder.flushAndClose();
                        logger.info("Pre-yield: encoder flushed");
                    }
                }
                
                @Override
                public void onPostReacquire() {
                    logger.info("Post-reacquire: resuming recording and streaming...");
                    
                    // Restore streaming components if streaming was enabled.
                    // yieldCameraInternal and restartCameraAfterError call clearStreamingComponents()
                    // which nulls the camera's local refs. The pipeline still holds the actual objects.
                    if (streamingEnabled && streamScaler != null && streamEncoder != null && camera != null) {
                        camera.setStreamingComponents(streamScaler, streamEncoder);
                        camera.updateStreamFrameStride();
                        // Re-arm the client-presence gate — a yield may have run
                        // clearStreamingComponents() which reset it to fail-open.
                        com.overdrive.app.streaming.WebSocketStreamServer wsRe = wsStreamServer;
                        if (wsRe != null) {
                            camera.setStreamClientProbe(wsRe::hasActiveClients);
                        }
                        logger.info("Post-reacquire: streaming components restored");
                    }
                    
                    // Resume recording in whatever mode was active before yield
                    if (currentMode == Mode.SURVEILLANCE) {
                        // Sentry mode — re-enable surveillance (it will start recording on motion)
                        if (sentry != null && !sentry.isActive()) {
                            sentry.enable();
                        } else if (sentry != null) {
                            // RESYNC (audit R7 ExtC-1): onPreYield stopped the
                            // RECORDER only — the engine's active/recording
                            // flags were untouched, so this !isActive() guard
                            // skipped the restart while the engine still
                            // believed it was recording. Continuous mode has
                            // no tick loop and its start-retry chain died at
                            // the first success, so nothing ever noticed:
                            // zero video for the rest of the parked session
                            // (reachable via any AVM camera claim or HAL
                            // error restart, both of which use this listener
                            // pair). The hook repairs the flag desync and
                            // restarts the continuous recorder; a no-op for
                            // smart mode, whose tick loop self-heals.
                            sentry.resumeAfterCameraReacquire();
                        }
                        logger.info("Post-reacquire: surveillance mode restored");
                    } else if (currentMode == Mode.NORMAL_RECORDING || recordingMode) {
                        // Normal recording mode — restart recording.
                        // FIX (audit R3, Findings 3+6): re-enter the pipeline-level
                        // entrypoint (which gates on encoder.isFormatAvailable(),
                        // runs the storage probe, and schedules format-available /
                        // cold-start retry on miss) instead of calling
                        // recorder.startRecording() bare. A bare call silent-no-ops
                        // when the encoder hasn't republished its output format
                        // post-flushAndClose or when the volume returns transient
                        // EBUSY, leaving the pipeline wedged for the rest of the
                        // ACC=ON window. Prefer pendingRecordingPrefix (cold-start
                        // deferred case) over the captured active session.
                        if (recorder != null && !recorder.isRecording()) {
                            java.io.File resumeDir;
                            String resumePrefix;
                            if (pendingRecordingPrefix != null) {
                                resumeDir = pendingRecordingDir;
                                resumePrefix = pendingRecordingPrefix;
                                logger.info("Post-reacquire: resuming via pending request "
                                    + "(prefix=" + resumePrefix + ")");
                            } else if (activeRecordingPrefix != null) {
                                resumeDir = activeRecordingDir;
                                resumePrefix = activeRecordingPrefix;
                                logger.info("Post-reacquire: resuming active session "
                                    + "(prefix=" + resumePrefix + ", dir="
                                    + (resumeDir != null ? resumeDir.getName() : "default") + ")");
                            } else {
                                resumeDir = null;
                                resumePrefix = "cam";
                                logger.warn("Post-reacquire: no captured session — "
                                    + "falling back to default (prefix=cam)");
                            }
                            try {
                                startRecording(resumeDir, resumePrefix);
                                logger.info("Post-reacquire: normal recording resumed via pipeline.startRecording");
                            } catch (Throwable t) {
                                logger.warn("Post-reacquire: pipeline.startRecording threw — "
                                    + t.getMessage());
                            }
                        }
                    }
                }

                @Override
                public void onHalRecoveryNeeded() {
                    // ESCALATION (#3): bare close/reopen restarts have repeatedly
                    // failed to revive frame delivery — the AVM HAL co-consumer
                    // state is wedged and only a full teardown + com.byd.avc
                    // warmup recovers it (the sole thing that ever broke the
                    // sentry->drive blackout loop in field logs). Route through
                    // RecordingModeManager's warmup-capable restart. Run on a
                    // background thread: we're on the GL/watchdog path and the
                    // recovery does a blocking 4s warmup + pipeline rebuild.
                    logger.error("HAL recovery needed — bare reopen loop cannot recover. "
                        + "Routing through warmup-restart (full teardown + com.byd.avc warmup).");
                    final PanoramicCameraGpu cam = camera;
                    new Thread(() -> {
                        try {
                            com.overdrive.app.recording.RecordingModeManager rmm =
                                com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                            if (rmm != null) {
                                rmm.forceWarmupRestart("hal-zero-frame-escalation");
                            } else {
                                logger.warn("HAL recovery: RecordingModeManager unavailable — "
                                    + "cannot route warmup restart; leaving stall watchdog to retry");
                            }
                        } catch (Throwable t) {
                            logger.warn("HAL recovery routing failed: " + t.getMessage());
                        } finally {
                            // Always clear the escalation latch so the stall
                            // watchdog can act again (bare restart or a fresh
                            // escalation) if this recovery didn't take. Without
                            // this, a failed recovery would permanently silence
                            // the watchdog for the rest of the drive.
                            if (cam != null) {
                                cam.notePipelineRestarted();
                            }
                        }
                    }, "HalRecoveryRestart").start();
                }
            });
            
            // Wait for camera to fully initialize and GL context to be ready.
            // This isn't an oem-parity concern — the sleep gives MediaCodec
            // time to consume the first encoder input frame so the
            // INFO_OUTPUT_FORMAT_CHANGED callback fires and the encoder format
            // is saved for reuse. Field log (camera_daemon_20260604_120145.log)
            // showed every startRecording() returning formatAvailable=false
            // when this sleep was skipped on dilink4 — recording never started.
            Thread.sleep(1500);

            // Verify the camera GL-thread runnable actually completed without
            // throwing. PanoramicCameraGpu.start() posts initializeGl +
            // startCamera onto the GL handler and returns immediately; if that
            // runnable throws (camera open failure, EGL init failure), the
            // camera's `running` field stays false. Without this gate, the
            // pipeline would publish running=true and isRunning() would lie —
            // every subsequent RecordingModeManager retry would see "already
            // running" and skip, wedging recording for the rest of the drive.
            if (camera == null || !camera.isRunning()) {
                logger.warn("start(): camera.isRunning() false after warmup window — "
                    + "treating start as failed");
                // Deliberately NO camera.stop() here (audit follow-up): the catch
                // below performs the one and only rollback stop and CONSUMES its
                // verdict. An eager stop here discarded that verdict, and the
                // catch's second call could then report clean (e.g. a rejected
                // releaseGl post nulls an exited GL thread on call one; call two
                // skips the whole GL block and returns true) — erasing the wedge
                // the first call detected. Panoramic's verdict is also sticky now,
                // but one stop with one consumed verdict is the primary fix.
                // running stays false; starting cleared in catch below.
                throw new IllegalStateException(
                    "Camera failed to reach running state within warmup window");
            }

            // Camera open verified — publish running=true so isRunning() is honest.
            synchronized (this) {
                running = true;
            }

            // Set callback to start recording when recorder is ready
            if (autoStartRecording) {
                recordingMode = true;
                camera.setRecorderInitCallback(() -> {
                    logger.info( "Recorder ready - starting recording automatically");
                    recorder.startRecording();
                    currentMode = Mode.NORMAL_RECORDING;
                    
                    // Enable overlay for auto-started recording (pano flow).
                    // Push the pano field selection (additive) then the ORIGINAL
                    // polling mechanics — unchanged from before this feature.
                    recorder.setOverlayRecordingModeAllowed(true);
                    pushOverlayFieldsForFlow("pano");
                    if (telemetryCollector != null && recorder.isOverlayEnabled()) {
                        telemetryCollector.setOverlayRecordingActive(true);
                        telemetryCollector.startPolling();
                    }
                });
            } else {
                recordingMode = false;
            }
            
            // Initialize recorder on GL thread (CRITICAL: must be on GL thread!)
            if (camera.getEglCore() != null) {
                camera.initRecorderOnGlThread(recorder, encoder);
                logger.info( "Recorder initialization scheduled on GL thread");
                // Wait for recorder GL bind to complete on the GL thread —
                // initRecorderOnGlThread schedules the bind via glHandler.post,
                // and a downstream startRecording() before the bind completes
                // fails with formatAvailable=false. Removing this sleep on
                // dilink4 broke recording in the field log.
                Thread.sleep(500);
            }
            
            // DON'T auto-enable streaming - enable on-demand when client requests
            // Streaming will be enabled via enableStreaming() when HTTP client connects
            // enableStreaming() already auto-starts the pipeline if not running.

            // DON'T auto-enable surveillance - let caller decide
            // Surveillance should only be enabled when explicitly requested
            // sentry.enable();  // REMOVED - caller must explicitly enable

            // FIX (audit R4, Finding 6): if a startRecording() request landed
            // during the narrow window between a prior pipeline.stop() and
            // this start() (recorder==null branch in startRecording), the
            // intent is captured in pendingRecordingPrefix but no listener
            // was registered against any encoder (none existed). Now that
            // init()/start() has built a fresh encoder + recorder, rebind
            // the orphan request: register a one-shot format-available
            // listener so the new encoder's first frame triggers
            // checkPendingRecording(). Without this, the wedge persists
            // until either RMM's wedge ticker re-issues startRecording()
            // (slow self-heal, may be >30s) or the caller invokes again.
            if (pendingRecordingPrefix != null && encoder != null) {
                logger.info("start(): rebinding orphan deferred-recording request "
                    + "to fresh encoder (prefix=" + pendingRecordingPrefix + ")");
                encoder.setFormatAvailableListener(() -> {
                    new Thread(() -> {
                        try {
                            checkPendingRecording();
                        } catch (Exception e) {
                            logger.warn("Deferred recording start (post-start rebind) failed: "
                                + e.getMessage());
                        }
                    }, "PendingRecKickoffStart").start();
                });
            }

            logger.info( "GPU pipeline started (streaming on-demand, surveillance NOT auto-enabled)");

            // OEM Dashcam re-sync on pano-ready. In the DashCam+Pano layout
            // both AVMCamera clients race at ACC ON: if OEM's startPipeline()
            // lost that race (AVM handle contention on a single-client HAL, or
            // a transient open failure), nothing retried it — the OEM lifecycle
            // is edge-driven, so the forward sensor stayed un-recorded until
            // some later incidental edge (gear change, surveillance IPC) — the
            // "started ~2km in" symptom. Now that pano is fully up
            // (running=true), re-drive the OEM resolver: when OEM is NOT
            // running it starts it fresh (and, because pano is now up, gets
            // pano's shared EGL for the texture-share/streaming path); when OEM
            // is already running it short-circuits to a no-op. NOTE: this does
            // NOT restart an OEM that is already running on an independent EGL
            // context — that only affects view-6 streaming (recording is
            // unaffected) and the documented off/on workaround still applies
            // for that narrow case. This is the recording-side half of the
            // "defer/restart OEM when pano starts" fix flagged in
            // OEM_DASHCAM_PROGRESS.md. Scheduled (not inline) so the OEM
            // warmup+open doesn't block pano's start() return.
            try {
                if (com.overdrive.app.config.UnifiedConfigManager
                        .isAnyOemDashcamTriggerEnabled()) {
                    logger.info("Pano start complete — scheduling OEM Dashcam lifecycle recalc "
                        + "(DashCam+Pano re-sync)");
                    com.overdrive.app.server.OemDashcamApiHandler.scheduleLifecycleRecalc();
                }
            } catch (Throwable t) {
                logger.warn("OEM Dashcam pano-ready recalc dispatch failed: " + t.getMessage());
            }

            // Blind-spot self-arm on pano-ready. Same edge-only-lifecycle class
            // as OEM dashcam above: the app arms the BS lane only on the ACC_ON
            // broadcast edge, which is missed on a hard reboot (ACC already on
            // before the app receiver exists). Now that pano is running, re-drive
            // the idempotent daemon-side resolver so the lane arms here instead of
            // waiting for the 30s self-heal ticker. No-op when blindspot.enabled
            // is false or the lane is already armed.
            try {
                com.overdrive.app.server.StreamingApiHandler.resolveBlindSpotLifecycle();
            } catch (Throwable t) {
                logger.warn("Blind-spot pano-ready self-arm dispatch failed: " + t.getMessage());
            }

        } catch (Exception e) {
            // Reset flags on failure so retry is possible. Both `running`
            // and `starting` must be cleared — running may have been
            // published just above (post-verify) before a later step threw,
            // and starting was claimed at entry to block concurrent starts.
            synchronized (this) {
                running = false;
                starting = false;
            }
            // FIX (audit R1): release ALL fields allocated by init() so the
            // next start() retry runs init() against null refs and doesn't
            // overwrite half-built encoder/recorder/downscaler/sentry/camera
            // refs (memory leak + EGL leak). encoder has its own guard at
            // init():894, but recorder/downscaler/sentry/camera get
            // overwritten without releasing on the retry path.
            logger.warn("start() failed — releasing partial init state for clean retry: "
                + e.getMessage());
            // Rollback consumes camera.stop()'s verdict (audit follow-up): a
            // wedged teardown discovered HERE must gate the recorder/encoder
            // releases below (touching the wedged native state is the hazard
            // the verdict exists to prevent) and latch this pipeline terminal.
            // The earlier warmup-verify branch's own camera.stop() deliberately
            // discards its result — it rethrows into THIS catch, which re-calls
            // stop() (idempotent; sticky verdicts re-answer honestly) and
            // handles the verdict once, here.
            boolean rollbackCameraClean = true;
            try {
                if (camera != null) {
                    try { rollbackCameraClean = camera.stop(); } catch (Throwable t) {
                        logger.warn("start() rollback: camera.stop failed: " + t.getMessage());
                    }
                    camera = null;
                }
            } catch (Throwable ignored) {}
            if (!rollbackCameraClean) {
                pipelineTeardownWedged = true;
                logger.error("start() rollback: camera stop wedged — skipping "
                    + "recorder/encoder release (trip-safe restart pending); this "
                    + "pipeline instance is now terminal");
            }
            try {
                if (sentry != null) {
                    try { sentry.disable(); } catch (Throwable ignored) {}
                    try { sentry.release(); } catch (Throwable t) {
                        logger.warn("start() rollback: sentry.release failed: " + t.getMessage());
                    }
                    sentry = null;
                }
            } catch (Throwable ignored) {}
            try {
                if (downscaler != null) {
                    try { downscaler.release(); } catch (Throwable t) {
                        logger.warn("start() rollback: downscaler.release failed: " + t.getMessage());
                    }
                    downscaler = null;
                }
            } catch (Throwable ignored) {}
            if (rollbackCameraClean) {
                try {
                    if (recorder != null) {
                        try { recorder.release(); } catch (Throwable t) {
                            logger.warn("start() rollback: recorder.release failed: " + t.getMessage());
                        }
                        recorder = null;
                    }
                } catch (Throwable ignored) {}
                try {
                    if (encoder != null) {
                        // Verdict consumed (audit follow-up): a wedge first
                        // discovered here must latch the pipeline terminal.
                        try {
                            if (!encoder.release()) {
                                pipelineTeardownWedged = true;
                                logger.error("start() rollback: encoder release wedged "
                                    + "— pipeline terminal");
                            }
                        } catch (Throwable t) {
                            // Throw = wedge (see stop(): teardown state unknown).
                            pipelineTeardownWedged = true;
                            logger.error("start() rollback: encoder.release threw ("
                                + t.getMessage() + ") — pipeline terminal");
                        }
                        encoder = null;
                    }
                } catch (Throwable ignored) {}
            } else {
                // Wedged: drop the references without touching the native state —
                // the pending process restart reclaims them.
                recorder = null;
                encoder = null;
            }
            // FIX (audit R7): release the AdaptiveBitrateController that init()
            // allocated at line 1489. Without this, every start()-failure cycle
            // leaks the prior controller's handler thread, and the next init()
            // overwrites the field reference. Self-healing today via repeated
            // start failures, so logged as warn — explicit cleanup is cheap.
            try {
                if (bitrateController != null) {
                    logger.warn("start() rollback: releasing bitrateController");
                    try { bitrateController.release(); } catch (Throwable t) {
                        logger.warn("start() rollback: bitrateController.release failed: " + t.getMessage());
                    }
                    bitrateController = null;
                }
            } catch (Throwable ignored) {}
            initialized = false;
            // FIX (audit R4, Finding 4): clear pending/active recording state
            // so an orphan request doesn't survive into the next start() with
            // no listener attached. The recorder/encoder allocated by the
            // previous init() were just released above, so any format-
            // available listener registered against the prior encoder is dead;
            // a stale pendingRecordingPrefix would otherwise sit until either
            // the camera-probe callback fires (skipped on validated configs)
            // or RMM's wedge ticker eventually re-activates. Clearing here
            // forces RMM's next tick to re-issue startRecording() against the
            // freshly-built pipeline, which DOES register a fresh listener.
            if (pendingRecordingPrefix != null || activeRecordingPrefix != null
                    || recordingMode) {
                logger.warn("start() rollback: clearing pending/active recording state "
                    + "(pending=" + pendingRecordingPrefix
                    + ", active=" + activeRecordingPrefix
                    + ", recordingMode=" + recordingMode + ")");
            }
            pendingRecordingDir = null;
            pendingRecordingPrefix = null;
            activeRecordingDir = null;
            activeRecordingPrefix = null;
            recordingMode = false;
            currentMode = Mode.IDLE;
            throw e;
        } finally {
            // On the success path, clear `starting` once start() returns.
            // (On the failure path the catch above already cleared it; this
            // is idempotent.)
            synchronized (this) {
                starting = false;
            }
        }
    }
    
    /**
     * Stops the GPU pipeline.
     *
     * <p>Synchronized on the same monitor as {@link #start(boolean)} so a
     * concurrent cold-start request (from {@code SurveillanceApiHandler}'s
     * {@code requestColdStartAsync}) can't race in mid-teardown. Without this,
     * stop() can set {@code running=false} early, and a sibling start() can
     * re-enter init() while stop() is still draining encoders / releasing
     * EGL — corrupting both lanes.
     */
    public void stop() {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            stopping = true;
            // FIX (audit R5): bump generation so any in-flight storage retry
            // captured an older value and exits before touching torn-down state.
            long newGen = pipelineGen.incrementAndGet();
            logger.info("Pipeline generation bumped on stop: " + newGen);
        }

        try {
            logger.info( "Stopping GPU pipeline...");

            // Clear any pending deferred recording
            pendingRecordingDir = null;
            pendingRecordingPrefix = null;
            // FIX (audit R3, Findings 3+6): drop active-session memory on full
            // pipeline teardown; nothing to resume after this point.
            activeRecordingDir = null;
            activeRecordingPrefix = null;
            recordingMode = false;
            // Cancel the cold-start storage retry too. Without this, a
            // RecStorageRetry thread can outlive a full pipeline teardown,
            // call recorder.startRecording on a half-released encoder, and
            // either crash the daemon or resurrect a phantom recording on a
            // recorder that's about to be nulled.
            try { cancelStorageReadyRetry(); }
            catch (Throwable t) { logger.warn("stop: cancelStorageReadyRetry failed: " + t.getMessage()); }

            // Reset mode so status API reflects that we're not in any active mode
            currentMode = Mode.IDLE;

            // FIX (audit R3): re-arm the one-shot enable-time mount-wait for the
            // next pipeline lifecycle. A full teardown ends the lifecycle that
            // already consumed the wait; the next cold start's first arm should
            // again get the bounded window to let a fresh async SD mount land
            // before its inaugural event-dir snapshot.
            mountWaitConsumed = false;

            // Stop recording first to finalize file
            try {
                if (recorder != null && recorder.isRecording()) {
                    recorder.stopRecording();
                }
            } catch (Throwable t) {
                logger.warn("stop: recorder.stopRecording failed: " + t.getMessage());
            }

            // Disable streaming — stream encoder/scaler hold EGL surfaces that will be
            // destroyed when the camera stops. They must be released before camera.stop().
            // UNCONDITIONAL (audit follow-up 2): an in-progress enableStreaming holds
            // streamLifecycleLock across its bounded GL-init wait with a LIVE encoder
            // while streamingEnabled is still false — a streamingEnabled-gated call
            // here skipped the teardown in exactly that window and closed the camera
            // over the new drainer. Calling unconditionally serializes on the lock:
            // we block until the enable finishes (≤2s), then tear down whatever it
            // installed; disableStreamingLocked's own early-out makes the truly-idle
            // case a cheap no-op.
            try {
                disableStreaming();
            } catch (Throwable t) {
                logger.warn("stop: disableStreaming failed: " + t.getMessage());
            }

            // Disable the dedicated blind-spot lane too — its scaler+encoder
            // hold EGL surfaces on the same camera GL context.
            // Always call disableBlindSpot() rather than gating on blindSpotEnabled
            // here: this is an unsynchronized read of blindSpotEnabled, and a
            // concurrent disableBlindSpot() (e.g. from the API thread) can flip it
            // between the check and the call. disableBlindSpot() is idempotent —
            // it acquires bsLifecycleLock and returns early when already disabled —
            // so calling it unconditionally is both race-free and a no-op when off.
            try {
                disableBlindSpot();
            } catch (Throwable t) {
                logger.warn("stop: disableBlindSpot failed: " + t.getMessage());
            }
            // Disable the camera-view program too. disableBlindSpot() above only tears
            // the shared lane down when camview is NOT active; a camview-only session
            // (BS disabled) would otherwise survive a pipeline stop() with camViewActive
            // stuck true and its "camview" sustained token still held (cluster
            // projection pinned). disableCamView() is idempotent (returns early if not
            // active), releases the token unconditionally, and tears the lane down when
            // BS isn't using it — so the next pipeline lifecycle starts clean.
            try {
                disableCamView();
            } catch (Throwable t) {
                logger.warn("stop: disableCamView failed: " + t.getMessage());
            }

            // Disable surveillance
            try {
                if (sentry != null) {
                    sentry.disable();
                }
            } catch (Throwable t) {
                logger.warn("stop: sentry.disable failed: " + t.getMessage());
            }

            // OEM Dashcam pipeline shares pano's eglDisplay via EGLCore.createShared.
            // Calling pano camera.stop() (which terminates the display) before OEM
            // tears down would leave OEM's render loop sampling against a dead
            // EGLDisplay — every subsequent eglMakeCurrent / eglSwapBuffers fails
            // silently with EGL_BAD_DISPLAY and the OEM encoder produces black
            // frames. Tear OEM down here so its EGL release runs against a still-
            // valid parent display.
            try {
                com.overdrive.app.camera.OemDashcamPipeline oem =
                    com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
                if (oem != null && oem.isRunning()) {
                    logger.info("Stopping OEM Dashcam pipeline before pano camera tear-down "
                        + "(shared eglDisplay)");
                    try { oem.stopRecording(); } catch (Throwable ignored) {}
                    try { oem.stop(); } catch (Throwable ignored) {}
                    com.overdrive.app.daemon.CameraDaemon.setOemDashcamPipeline(null);
                }
            } catch (Throwable t) {
                logger.warn("OEM pre-pano-stop teardown failed: " + t.getMessage());
            }

            // FIX (EGL-leak audit): release the AI downscaler on every normal
            // stop, BEFORE camera.stop() eglTerminates the shared display.
            // The downscaler owns a private HandlerThread whose EGL context
            // (shared with the camera's) is made current forever; without
            // this release the context survives eglTerminate (EGL defers
            // deletion of anything current on a live thread), the next
            // init() overwrites the reference, and one context + surface +
            // ImageReader leaks per stop/start cycle until the Adreno driver
            // refuses new contexts (fleet failure: eglCreateContext loops
            // until reboot). release() is synchronous and idempotent; the
            // sentry was disabled above, so no consumer is still feeding it.
            try {
                if (downscaler != null) {
                    downscaler.release();
                }
            } catch (Throwable t) {
                logger.warn("stop: downscaler.release failed: " + t.getMessage());
            } finally {
                downscaler = null;
            }

            // Stop camera (this releases EGL context and surfaces). The verdict
            // matters (audit follow-up): camera.stop() ABORTS on a wedged
            // drainer/GL thread and requests a trip-safe restart — but the
            // restart coordinator is asynchronous and can retry its checkpoint
            // for a while, so this is NOT necessarily an imminent process exit.
            // Releasing the recorder's GL resources and the encoder's codec/input
            // surface over that still-live wedged state is exactly what must not
            // happen. A THROW keeps the legacy behaviour (release anyway) — a
            // throw is an ordinary teardown error with no restart pending, and
            // skipping the releases there would leak with no recovery scheduled.
            // Verify the RETIRING stream-encoder release BEFORE the camera closes
            // (audit follow-up): disableStreaming above nulled the encoder fields
            // and queued release() fire-and-forget, so the camera-close guard can
            // never see that encoder — but its drainer keeps dequeuing against
            // the camera until the release completes. 8s budget covers the worst
            // BOUNDED release (2s drainer + 2s writer joins + codec release +
            // executor queue); exceeding it means a wedge, and closing the camera
            // over it is the FORTIFY abort. Escalation is idempotent (release()
            // already requested it if the wedge was its own).
            boolean cameraStopClean = true;
            if (!verifyRetiringStreamEncoderRelease(8000, "stop")) {
                cameraStopClean = false;
                // URGENT when the camera is held (audit follow-up): skipping
                // the close leaves the native AVM app without video until this
                // process dies, and the conservative coordinator can wait
                // indefinitely (checkpoint write can block on the same wedged
                // mount). The urgent variant arms a short non-cancellable halt
                // deadline first — and it arms even though release() may have
                // already requested a conservative restart for the same wedge
                // (escalation, not a duplicate request).
                final boolean cameraHeld = camera != null && camera.isCameraHandleHeld();
                // Request BEFORE logging: the logger can block on the same
                // wedged storage that wedged the release, and a blocked log
                // call ahead of the request would strand the held camera with
                // no deadline armed.
                try {
                    if (cameraHeld) {
                        com.overdrive.app.daemon.CameraDaemon.requestUrgentCameraReleaseRestart(
                            "retiring stream encoder release incomplete before camera close");
                    } else {
                        com.overdrive.app.daemon.CameraDaemon.requestProcessRestartPreservingTrip(
                            "retiring stream encoder release incomplete before camera close");
                    }
                } catch (Throwable t) {
                    try {
                        logger.error("stop: process-restart request failed: " + t.getMessage());
                    } catch (Throwable ignored) {}
                }
                logger.error("stop: retiring stream-encoder release incomplete/wedged — "
                    + "skipping camera close (FORTIFY risk); requested "
                    + (cameraHeld ? "URGENT bounded" : "trip-safe") + " restart");
            } else {
                try {
                    if (camera != null) {
                        cameraStopClean = camera.stop();
                    }
                } catch (Throwable t) {
                    logger.warn("stop: camera.stop failed: " + t.getMessage());
                }
            }

            if (cameraStopClean) {
                // CRITICAL: Release recorder and encoder since EGL context is gone
                // They must be recreated on next start()
                try {
                    if (recorder != null) {
                        recorder.release();
                    }
                } catch (Throwable t) {
                    logger.warn("stop: recorder.release failed: " + t.getMessage());
                }

                // Consume the release verdict (audit follow-up): a disk-writer
                // wedge can be FIRST discovered here, and without latching it the
                // instance stays locally reusable if the global restart request
                // self-clears. release() escalates internally; the latch is ours.
                try {
                    if (encoder != null && !encoder.release()) {
                        pipelineTeardownWedged = true;
                        logger.error("stop: encoder release wedged — pipeline terminal");
                    }
                } catch (Throwable t) {
                    // A THROW is treated like a wedge (audit follow-up 3):
                    // release() handles its failures internally, so an escaping
                    // throw means teardown state is unknown — not safe to reuse.
                    pipelineTeardownWedged = true;
                    logger.error("stop: encoder.release threw (" + t.getMessage()
                        + ") — pipeline terminal");
                }
            } else {
                // LOCAL sticky latch too (audit follow-up): the finally below
                // still clears initialized/refs/stopping — necessary so the
                // shutdown path can proceed — but if the restart coordinator
                // FAILS and clears the global pending flag, only this latch
                // stops a later start() from building a second camera/GL stack
                // over the wedged one we just abandoned.
                pipelineTeardownWedged = true;
                logger.error("stop: camera stop aborted/degraded (wedged native state, "
                    + "trip-safe restart pending) — skipping recorder/encoder release; "
                    + "references are dropped and the process exit reclaims the "
                    + "resources. This pipeline instance is now terminal.");
            }

            logger.info( "GPU pipeline stopped");
        } finally {
            // Guarantee the pipeline lands in a clean, fully-deinitialized
            // state regardless of which teardown step threw — otherwise a
            // partial throw leaves initialized=true with stale refs and
            // every subsequent start() short-circuits past init() into a
            // half-released encoder / recorder.
            recorder = null;
            encoder = null;
            initialized = false;
            // Clear stopping flag so concurrent start() can proceed.
            synchronized (this) {
                stopping = false;
            }
        }
    }

    /**
     * Releases all resources.
     */
    public void release() {
        stop();

        // Deregister live-config listener BEFORE releasing the recorder so a
        // racing UCM update can't fire into a half-released recorder.
        if (rectifyConfigListener != null) {
            try {
                com.overdrive.app.config.UnifiedConfigManager
                    .removeListener(rectifyConfigListener);
            } catch (Throwable ignored) {}
            rectifyConfigListener = null;
        }

        if (bitrateController != null) {
            bitrateController.release();
            bitrateController = null;
        }

        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        
        if (downscaler != null) {
            downscaler.release();
            downscaler = null;
        }
        
        if (sentry != null) {
            sentry.release();
            sentry = null;
        }
        
        if (encoder != null) {
            // Verdict consumed (audit follow-up 3): this path is reachable with a
            // live encoder when stop() early-returned (pipeline never running), so
            // a wedge can be FIRST discovered here. release() escalates
            // internally; the terminal latch is ours to set — on a false verdict
            // AND on a throw (teardown state unknown either way).
            try {
                if (!encoder.release()) {
                    pipelineTeardownWedged = true;
                    logger.error("release(): encoder release wedged — pipeline terminal");
                }
            } catch (Throwable t) {
                pipelineTeardownWedged = true;
                logger.error("release(): encoder.release threw (" + t.getMessage()
                    + ") — pipeline terminal");
            }
            encoder = null;
        }
        
        initialized = false;
        logger.info( "GPU pipeline released");
    }
    
    /**
     * Starts recording.
     * Stops surveillance if active (mutually exclusive).
     */
    public void startRecording() {
        startRecording(null, "cam");
    }
    
    /**
     * Starts recording with custom output directory and filename prefix.
     * Stops surveillance if active (mutually exclusive).
     * 
     * @param outputDir Custom output directory (null for default recordings dir)
     * @param prefix Filename prefix (e.g., "cam", "proximity", "event")
     */
    public void startRecording(java.io.File outputDir, String prefix) {
        // LANE SAFETY (lifecycle redesign): the recorder lane (PASS 1A) can be
        // switched OFF when the camera is kept warm ONLY for blind-spot (BS has
        // no encoder). ANY recording — RMM mode, manual /api/start, TCP start,
        // ACC-off sentry, OEM dashcam, proximity event — funnels through this
        // method, so re-assert the lane HERE, by construction, rather than
        // relying on every external caller to remember. Without this, a record
        // started while the camera is BS-only-warm draws zero frames into the
        // encoder (renderLoop gates drawFrame on recorderLaneEnabled) → a
        // false-GREEN "recording" that writes an empty/zero-byte clip. Idempotent
        // volatile write; does NOT touch stride/bitrate/fps (proximity's own
        // MONITORING/event profile and the per-mode applyFullRecordingProfile
        // own those) — it only guarantees PASS 1A is not gated off. The global
        // camera fps is restored by the per-mode activate (applyFullRecordingProfile)
        // for RMM modes; for non-RMM record paths the camera was already at a
        // recording-grade fps unless BS-only-warmed, which onPipelineStartedExternally
        // / the caller handles — but the LANE is the silent-failure lever, so it
        // is the one we harden universally here.
        com.overdrive.app.camera.PanoramicCameraGpu camLane = camera;
        if (camLane != null && !camLane.isRecorderLaneEnabled()) {
            logger.info("startRecording: recorder lane was OFF (BS-only keep-warm) — re-enabling for recording");
            camLane.setRecorderLaneEnabled(true);
            // Lane was off ONLY in the BS-only keep-warm state, which also drops
            // the global camera fps to the BS idle/active rate (~1 fps). A record
            // started from that state (manual /api/start, ACC-off sentry on
            // dilink4) would otherwise capture at ~1 fps. Restore a recording-grade
            // fps. RMM mode activations independently call applyFullRecordingProfile
            // first; this is the safety net for non-RMM record paths. Idempotent
            // (setTargetFps no-ops if already at this rate).
            int recFps = loadTargetFps();
            if (camLane.getTargetFps() < recFps) {
                camLane.setTargetFps(recFps);
            }
        }

        // DIAG (Finding A): log the exact recorder/encoder/format state on
        // every start request so a silent no-op explains itself in the field
        // log. If this line is ABSENT from a drive's log right after "Starting
        // DRIVE_MODE recording", the running daemon is NOT this build.
        {
            boolean recReady = recorder != null;
            boolean encReady = recReady && recorder.getEncoder() != null;
            boolean fmtReady = encReady && recorder.getEncoder().isFormatAvailable();
            logger.info("startRecording(prefix=" + prefix + ", dir="
                + (outputDir != null ? outputDir.getName() : "default")
                + "): recorder=" + recReady + " encoder=" + encReady
                + " formatAvailable=" + fmtReady + " currentMode=" + currentMode);
        }

        // Stop surveillance if active (mutually exclusive)
        if (currentMode == Mode.SURVEILLANCE) {
            logger.info("Stopping surveillance to start normal recording (mutually exclusive)");
            if (sentry != null) {
                sentry.disable();
            }
        }

        // SOTA: Ensure storage is ready (mount SD card if needed) for recordings.
        // Done OUTSIDE the synchronized block below — ensureStorageReady can
        // take seconds (mount + dir-walk) and we don't want to hold the
        // pipeline monitor across that I/O.
        if (outputDir == null) {  // Only check for default recordings dir
            try {
                // FIX (coldstart: ensureStorageReady unbounded on the activation
                // critical path). This call runs under RecordingModeManager's
                // activationLock with warmupInFlight=true. ensureStorageReady can
                // block for MINUTES on a FUSE-bridged SD/USB at cold boot (mount
                // retry loop + per-file dir-walk under binder contention) — and
                // because it sits BEFORE the encoder-format branch below, a stall
                // here means (a) activationLock + warmupInFlight stay pinned, so
                // every 30s warmup/resync retry just coalesces and makes no
                // progress, and (b) the deferred format-available listener that
                // would actually start recording is never registered. Field log
                // camera_daemon_20260610_155444 showed ACC-ON→first-frame take
                // 4m28s for exactly this reason.
                //
                // Bound it like the isStorageWriteReady probe already does. On
                // timeout we treat storage as "not confirmed ready" and fall
                // through — which is the SAME path the method already takes on a
                // false return: the bounded isStorageWriteReady probe just below
                // verifies actual writability, and on a miss defers + schedules
                // the 2s storage-ready retry. So recording still starts within
                // seconds (once the encoder format lands / the volume settles)
                // instead of stalling the whole activation for minutes. On a
                // healthy mount (<500ms) this is byte-for-byte identical to before.
                if (!ensureStorageReadyBounded(false)) {
                    logger.warn("Storage not ready for recording, but continuing with fallback");
                }
            } catch (Exception e) {
                logger.warn("Error checking storage readiness: " + e.getMessage());
            }
        }

        // Snapshot the recorder under the pipeline monitor so a concurrent
        // pipeline.stop() (called from CameraDaemon, SurveillanceApiHandler,
        // SafeLocationManager, etc.) can't null the field between our checks.
        // The snapshotted reference stays valid for the duration of this
        // call; if stop() ran first, snapshotted is null and we early-return.
        // If stop() runs concurrently AFTER snapshot, the encoder may still
        // be released under us — but recorder.startRecording / triggerEvent
        // hold their own locks (recordingLock + startStopLock) and a stop
        // racing in returns its own clean failure path.
        final GpuMosaicRecorder snapRecorder;
        synchronized (this) {
            snapRecorder = recorder;
        }

        if (snapRecorder != null) {
            // Check if encoder is ready (has received at least one frame from camera).
            final HardwareEventRecorderGpu enc = snapRecorder.getEncoder();
            if (enc != null && enc.isFormatAvailable()) {
                // Pre-flight write probe (timeout-bounded). On a half-mounted USB
                // at cold start the deeper start path (ensureRecordingsSpace scan
                // / new MediaMuxer open) can BLOCK INDEFINITELY with no return —
                // which hangs this thread and defeats the isRecording()-based
                // retry below (it never runs). Probing first lets us fail fast
                // and defer + retry instead of hanging.
                java.io.File probeDir = resolveProbeDirWithEnospcFallback(
                        (outputDir != null) ? outputDir
                                : StorageManager.getInstance().getRecordingsDir());
                if (!isStorageWriteReady(probeDir)) {
                    logger.warn("Recordings volume not write-ready (probe failed/timed out) — "
                        + "deferring and scheduling retry");
                    pendingRecordingDir = outputDir;
                    pendingRecordingPrefix = prefix;
                    recordingMode = true;
                    scheduleStorageReadyRetry(outputDir, prefix);
                    return;
                }
                snapRecorder.startRecording(outputDir, prefix);
                if (snapRecorder.isRecording()) {
                    currentMode = Mode.NORMAL_RECORDING;
                    recordingMode = true;
                    // FIX (audit R3, Findings 3+6): remember the active session so
                    // onPostReacquire (camera-yield resume) can re-enter
                    // pipeline.startRecording with the same dir/prefix instead of
                    // calling recorder.startRecording() bare.
                    activeRecordingDir = outputDir;
                    activeRecordingPrefix = prefix;
                    snapRecorder.setOverlayRecordingModeAllowed(true);
                    pushOverlayFieldsForFlow("pano");
                    if (telemetryCollector != null) {
                        telemetryCollector.setOverlayRecordingActive(true);
                        telemetryCollector.startPolling();
                    }
                    cancelStorageReadyRetry();
                    logger.info("Normal recording started (dir=" + (outputDir != null ? outputDir.getName() : "default") + ", prefix=" + prefix + ")");
                } else {
                    // recorder.startRecording() returned WITHOUT starting — the
                    // encoder's triggerEventRecording() failed, in the field
                    // almost always because the USB volume was not write-ready
                    // at this instant. This is the cold-start race: the daemon
                    // boots straight into gear D and asks to record before the
                    // USB has finished mounting, so mkdirs() on the recordings
                    // dir fails ("Failed to create parent directory" /
                    // "No writable USB drive found") and the WHOLE drive then
                    // records nothing because the old code (a) logged a false
                    // "Normal recording started" and (b) never retried.
                    // Defer + retry until storage settles.
                    logger.warn("Recording did not start — storage not write-ready "
                        + "(USB still mounting?); deferring and scheduling retry");
                    pendingRecordingDir = outputDir;
                    pendingRecordingPrefix = prefix;
                    recordingMode = true;
                    scheduleStorageReadyRetry(outputDir, prefix);
                }
            } else {
                // Encoder not ready yet (camera still warming up). Store the
                // request and register a one-shot listener that fires the
                // moment the encoder publishes its output format. Without
                // this, cold-start CONTINUOUS recording never began until
                // the next ACC OFF/ON cycle, because checkPendingRecording()
                // was previously only called from the camera-probe callback —
                // which is skipped when a validated camera config exists.
                logger.info("Encoder not ready yet — recording will start when camera is ready");
                pendingRecordingDir = outputDir;
                pendingRecordingPrefix = prefix;
                recordingMode = true;
                if (enc != null) {
                    enc.setFormatAvailableListener(() -> {
                        // Posted off the encoder thread so we don't block dequeue.
                        new Thread(() -> {
                            try {
                                checkPendingRecording();
                            } catch (Exception e) {
                                logger.warn("Deferred recording start failed: " + e.getMessage());
                            }
                        }, "PendingRecKickoff").start();
                    });
                }
            }
        } else {
            // recorder == null: the GpuMosaicRecorder is created asynchronously
            // on the GL thread by start(); a DRIVE_MODE/CONTINUOUS activation
            // that reaches startRecording() before that completes would
            // otherwise fall through this whole method and silently no-op —
            // the daemon logs "Starting DRIVE_MODE recording" but no cam_*.mp4
            // is ever written for the drive (Finding A: "no recordings while
            // driving"). Defer instead: capture the intent so the
            // format-available listener / checkPendingRecording() starts
            // recording once the recorder + encoder are ready.
            logger.info("Recorder not created yet — deferring recording start "
                + "(will begin when pipeline is ready)");
            pendingRecordingDir = outputDir;
            pendingRecordingPrefix = prefix;
            recordingMode = true;
        }
    }
    
    /**
     * Called when the encoder format becomes available (probe complete, first frame encoded).
     * Starts any pending recording that was deferred because the encoder wasn't ready.
     */
    void checkPendingRecording() {
        if (pendingRecordingPrefix == null) return;
        if (recorder == null || recorder.getEncoder() == null) return;
        if (!recorder.getEncoder().isFormatAvailable()) return;

        // FIX (audit R7): atomically capture pendingDir/prefix while holding
        // the same monitor stopRecording uses. Without this, a concurrent
        // stopRecording() (RMM mode change) can clear the fields between our
        // null check above and the capture below — the listener thread reads
        // a non-null value AFTER stopRecording has already called
        // recorder.stopRecording(), then issues a fresh recorder.startRecording
        // against the just-stopped recorder. The synchronized re-check + capture
        // means stopRecording's clear is observed atomically.
        java.io.File dir;
        String prefix;
        // FIX (audit MEDIUM): also snapshot recorder + encoder under the same
        // monitor so a concurrent pipeline.stop() (idle-shutdown, ACC OFF, RMM
        // OFF) that nulls the recorder/encoder fields in its finally-block
        // can't NPE us between the capture below and the deferred
        // recorder.startRecording / isRecording calls further down. Mirrors
        // the snapshot pattern at :2173-2176 in startRecording's outer entry.
        final GpuMosaicRecorder localRecorder;
        final HardwareEventRecorderGpu localEncoder;
        synchronized (this) {
            if (pendingRecordingPrefix == null) {
                logger.warn("checkPendingRecording: pending cleared between null-check and capture (concurrent stopRecording) — skipping");
                return;
            }
            dir = pendingRecordingDir;
            prefix = pendingRecordingPrefix;
            localRecorder = recorder;
            localEncoder = (recorder != null) ? recorder.getEncoder() : null;
        }
        if (localRecorder == null || localEncoder == null) {
            logger.info("checkPendingRecording: recorder/encoder torn down before kickoff — skipping");
            return;
        }

        // FIX (audit R6): probe storage write-readiness BEFORE the inner
        // recorder.startRecording(), mirroring the synchronous startRecording()
        // path's pre-flight probe at line 1954. The deferred path historically
        // skipped this probe, inheriting the inner call's risk of blocking
        // indefinitely on a half-mounted USB volume (mkdirs / ensureRecordingsSpace
        // / new MediaMuxer can hang). On probe failure, re-arm pending state and
        // schedule the storage-ready retry instead of pinning the
        // PendingRecKickoff thread.
        java.io.File probeDir = resolveProbeDirWithEnospcFallback(
                (dir != null) ? dir : StorageManager.getInstance().getRecordingsDir());
        if (!isStorageWriteReady(probeDir)) {
            logger.warn("Deferred start: storage volume not write-ready (probe failed/timed out) — "
                + "rescheduling retry instead of issuing inner recorder.startRecording");
            // Keep pending state intact (do not null) so retry has the args.
            pendingRecordingDir = dir;
            pendingRecordingPrefix = prefix;
            recordingMode = true;
            scheduleStorageReadyRetry(dir, prefix);
            return;
        }

        pendingRecordingDir = null;
        pendingRecordingPrefix = null;

        logger.info("Encoder now ready — starting deferred recording");
        localRecorder.startRecording(dir, prefix);
        if (localRecorder.isRecording()) {
            currentMode = Mode.NORMAL_RECORDING;
            recordingMode = true;
            // FIX (audit R3, Findings 3+6): remember the active session so a
            // subsequent camera yield can resume via pipeline.startRecording.
            activeRecordingDir = dir;
            activeRecordingPrefix = prefix;
            localRecorder.setOverlayRecordingModeAllowed(true);
            pushOverlayFieldsForFlow("pano");
            if (telemetryCollector != null) {
                telemetryCollector.setOverlayRecordingActive(true);
                telemetryCollector.startPolling();
            }
            cancelStorageReadyRetry();
            logger.info("Deferred normal recording started (dir=" +
                (dir != null ? dir.getName() : "default") + ", prefix=" + prefix + ")");
        } else {
            // Encoder ready but storage still not write-ready (cold-start USB
            // mount race). Re-defer and retry until the volume settles, rather
            // than silently dropping the whole drive's recording.
            logger.warn("Deferred start: storage not write-ready yet — scheduling retry");
            pendingRecordingDir = dir;
            pendingRecordingPrefix = prefix;
            recordingMode = true;
            scheduleStorageReadyRetry(dir, prefix);
        }
    }

    // --- Cold-start storage-ready retry -----------------------------------
    // The daemon can boot straight into gear D and request a DRIVE_MODE /
    // CONTINUOUS recording before the USB volume has finished mounting. The
    // encoder's MediaMuxer/mkdirs then fails on the not-yet-writable volume
    // ("Failed to create parent directory" / "No writable USB drive found"),
    // and historically the entire drive recorded nothing because the start
    // path logged a false success and never retried. This bounded background
    // retry re-attempts the start once the volume becomes write-ready, then
    // exits. It is cancelled by a successful start or by stopRecording()
    // (e.g. gear D->P), so it can never resurrect a recording after the driver
    // has parked.
    private volatile Thread storageRetryThread;
    private volatile Thread storageSlowRetryThread;
    private volatile boolean slowRetryRunning = false;
    private static final long STORAGE_RETRY_INTERVAL_MS = 2000L;
    private static final long STORAGE_RETRY_TIMEOUT_MS = 60_000L;
    private static final long STORAGE_SLOW_RETRY_INTERVAL_MS = 30_000L;
    private static final long STORAGE_PROBE_TIMEOUT_MS = 1500L;
    // Boot/ACC-on mount-race: bounded window enableSurveillance() waits for the
    // configured external volume to finish mounting before snapshotting the
    // event dir. 4s is inside the observed 2-15s window's lower band and well
    // below any boot-wedge concern; the watchdog handles the longer tail for
    // subsequent events. See StorageManager.waitForConfiguredExternalMount.
    private static final long STORAGE_MOUNT_WAIT_MS = 4000L;
    // FIX (audit R3): one-shot gate for the enable-time mount-wait. The wait is
    // only load-bearing on the FIRST surveillance arm of a pipeline lifecycle —
    // that's the inaugural eventOutputDir snapshot (line ~3307) and the only one
    // that can pin the earliest event to the internal fallback before the async
    // SD mount lands. On any RE-arm (e.g. a lock-gate force-arm after a grace-
    // period disarm: surveillance was armed, then UNLOCK disarmed it to
    // currentMode=IDLE without stopping the pipeline, then a fresh LOCK re-arms),
    // the engine's per-trigger getLiveSurveillanceDir() refresh
    // (SurveillanceEngineGpu Site A/B) already routes a late-landing mount to SD
    // for every event, so the enable-time wait buys nothing there — and skipping
    // it avoids holding the CameraDaemon.class monitor for up to STORAGE_MOUNT_
    // WAIT_MS across the force-arm block (CameraDaemon.applyLockEvent is
    // static-synchronized on the same monitor), which would otherwise delay a
    // concurrent owner-return UNLOCK disarm by that long. NOTE: isRunning() can
    // NOT be used to distinguish first-arm vs re-arm — CameraDaemon.enable
    // Surveillance() calls gpuPipeline.start() (which sets running=true) BEFORE
    // gpuPipeline.enableSurveillance(), so running is already true on the cold
    // first arm. Reset in stop() so a fresh pipeline lifecycle re-arms the wait.
    private volatile boolean mountWaitConsumed = false;

    private synchronized void scheduleStorageReadyRetry(java.io.File outputDir, String prefix) {
        if (storageRetryThread != null && storageRetryThread.isAlive()) {
            return;  // a retry is already in flight
        }
        // FIX (audit R5): pin retry to current pipeline generation. Compound
        // state checks (recorder snapshot + isFormatAvailable + storage probe)
        // can straddle a stop()-then-start() and silently mutate the new
        // pipeline. Generation gate is the single check that catches that.
        final long capturedGen = pipelineGen.get();
        storageRetryThread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + STORAGE_RETRY_TIMEOUT_MS;
            int attempt = 0;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(STORAGE_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;  // cancelled (stopRecording / success)
                }
                // FIX (audit R5): generation gate — bail if pipeline cycled.
                if (pipelineGen.get() != capturedGen) {
                    logger.info("Storage-ready retry: pipeline generation rotated ("
                        + capturedGen + "→" + pipelineGen.get() + ") — exiting");
                    return;
                }
                // FIX (audit R1): re-check the pipeline lifecycle on every
                // wake. The cancel path (cancelStorageReadyRetry) issues an
                // interrupt, but the real stop signal arrives only after the
                // 1500ms isStorageWriteReady probe finishes. By the time the
                // loop body would dereference recorder/encoder, stop() may
                // already have nulled them. Refuse to proceed if the pipeline
                // is mid-stop or no longer running.
                if (stopping || !running) {
                    logger.info("Storage-ready retry: pipeline stopped — exiting");
                    return;
                }
                // Bail if the request was cancelled (gear change) or already
                // satisfied by another path.
                if (pendingRecordingPrefix == null && !recordingMode) return;
                if (recorder != null && recorder.isRecording()) return;
                attempt++;
                try {
                    // Re-resolve/mount storage, then re-attempt — but ONLY once a
                    // timeout-bounded write probe confirms the volume is actually
                    // writable, so a retry attempt can never itself hang inside
                    // the blocking start path on a still-half-mounted USB.
                    StorageManager.getInstance().ensureStorageReady(false);
                    // FIX (audit R4): route the retry-loop GATE probe through the same
                    // ENOSPC redirect as the activation/checkPendingRecording sites. On a
                    // mounted-but-FULL SD the raw probe ENOSPC-fails every iteration so the
                    // gate never opens and startRecording (which DOES re-redirect per-segment
                    // to internal) is never reached — re-wedging cold start in the unbounded
                    // retry path. snapRec.startRecording still gets the ORIGINAL outputDir;
                    // only the gate target is redirected (symmetric with the direct sites).
                    java.io.File probeDir = resolveProbeDirWithEnospcFallback(
                            (outputDir != null) ? outputDir
                                    : StorageManager.getInstance().getRecordingsDir());
                    // FIX (audit R1): snapshot recorder + re-verify pipeline is
                    // running BEFORE startRecording so a concurrent stop() that
                    // nulled recorder/encoder mid-probe can't drop us into
                    // half-built muxer.tmp + zombie recording=true state.
                    GpuMosaicRecorder snapRec = recorder;
                    if (stopping || !running || snapRec == null) {
                        logger.info("Storage-ready retry: pipeline torn down mid-probe — exiting");
                        return;
                    }
                    if (snapRec.getEncoder() != null
                            && snapRec.getEncoder().isFormatAvailable()
                            && isStorageWriteReady(probeDir)) {
                        snapRec.startRecording(outputDir, prefix);
                        if (snapRec.isRecording()) {
                            currentMode = Mode.NORMAL_RECORDING;
                            recordingMode = true;
                            // FIX (audit R4, Finding 5): mirror startRecording's
                            // success path — capture active session so a later
                            // camera-yield resume (onPostReacquire) can re-enter
                            // pipeline.startRecording with the correct dir/prefix
                            // instead of falling back to the default "cam"/null.
                            activeRecordingDir = outputDir;
                            activeRecordingPrefix = prefix;
                            snapRec.setOverlayRecordingModeAllowed(true);
                            pushOverlayFieldsForFlow("pano");
                            if (telemetryCollector != null) {
                                telemetryCollector.setOverlayRecordingActive(true);
                                telemetryCollector.startPolling();
                            }
                            pendingRecordingDir = null;
                            pendingRecordingPrefix = null;
                            logger.info("Normal recording started on storage retry #" + attempt
                                + " (dir=" + (outputDir != null ? outputDir.getName() : "default")
                                + ", prefix=" + prefix + ", active session captured)");
                            // FIX (audit R1): notify RMM so modeActive gets
                            // re-evaluated. Without this, RMM still has
                            // modeActive=false from the original cold-start
                            // failure, so the next ticker fires
                            // activateModeWithWarmup → pipeline.stopRecording
                            // → kills the recording we just started.
                            try {
                                com.overdrive.app.recording.RecordingModeManager rmm =
                                    com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                if (rmm != null) {
                                    rmm.resyncFromHardware("storage-retry-success");
                                    logger.info("RMM resynced after storage-retry success");
                                }
                            } catch (Throwable t) {
                                logger.warn("RMM resync after storage-retry failed: "
                                    + t.getMessage());
                            }
                            return;
                        }
                    }
                    logger.info("Storage-ready retry #" + attempt
                        + ": still not write-ready, will retry");
                } catch (Exception e) {
                    logger.warn("Storage-ready retry #" + attempt + " error: " + e.getMessage());
                }
            }
            logger.warn("Storage retry hit " + (STORAGE_RETRY_TIMEOUT_MS / 1000)
                + "s timeout — switching to slow-retry every "
                + (STORAGE_SLOW_RETRY_INTERVAL_MS / 1000) + "s");
            // Hand off to the slow-retry loop. Without this, the daemon would
            // sit in a "modeActive=true, pipeline running, NOT recording"
            // zombie state forever — pendingRecordingPrefix/recordingMode are
            // still set, but no thread is checking storage anymore. The slow
            // retry runs at a much lower cadence (30s) so it's effectively
            // free, and auto-cancels on stop()/release()/stopRecording() via
            // the slowRetryRunning flag.
            scheduleStorageSlowRetry(outputDir, prefix);
        }, "RecStorageRetry");
        storageRetryThread.setDaemon(true);
        storageRetryThread.start();
    }

    /**
     * Slow-retry tail of the cold-start storage retry. Activates when the
     * 60s fast-retry give-up fires, and re-checks storage every 30s. On
     * success (storage ready AND pendingRecordingPrefix still set), it
     * runs the same start-recording logic as the fast retry. Auto-cancels
     * via {@link #slowRetryRunning} when:
     *   - the user changes mode (pendingRecordingPrefix becomes null),
     *   - recording starts via any path,
     *   - the pipeline is stopped/released,
     *   - {@link #cancelStorageReadyRetry()} is called.
     *
     * Bounded by the slowRetryRunning sentinel — there is no hard time
     * cap because the wedge being recovered (USB never mounted, SD never
     * came back, fs corruption that eventually heals) can persist for an
     * arbitrary fraction of the drive. The cost of an idle 30s tick is
     * a single ensureStorageReady(false) call and a write probe, which is
     * a few ms on a healthy volume.
     */
    private synchronized void scheduleStorageSlowRetry(java.io.File outputDir, String prefix) {
        if (storageSlowRetryThread != null && storageSlowRetryThread.isAlive()) {
            return;  // a slow retry is already in flight
        }
        slowRetryRunning = true;
        // FIX (audit R5): pin slow retry to current pipeline generation too.
        final long capturedGen = pipelineGen.get();
        storageSlowRetryThread = new Thread(() -> {
            int attempt = 0;
            while (slowRetryRunning) {
                try {
                    Thread.sleep(STORAGE_SLOW_RETRY_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;  // cancelled (stop / release / stopRecording / success)
                }
                if (!slowRetryRunning) return;
                // FIX (audit R5): generation gate.
                if (pipelineGen.get() != capturedGen) {
                    logger.info("Slow-retry: pipeline generation rotated ("
                        + capturedGen + "→" + pipelineGen.get() + ") — exiting");
                    return;
                }
                // FIX (audit R1): re-check pipeline lifecycle on every wake.
                if (stopping || !running) {
                    logger.info("Slow-retry: pipeline stopped — exiting");
                    return;
                }
                // Bail if the request was cancelled (gear change / mode
                // change cleared the pending intent) or already satisfied.
                if (pendingRecordingPrefix == null && !recordingMode) {
                    logger.info("Slow-retry: pending intent cleared — exiting");
                    return;
                }
                if (recorder != null && recorder.isRecording()) {
                    logger.info("Slow-retry: recording already started — exiting");
                    return;
                }
                attempt++;
                try {
                    StorageManager.getInstance().ensureStorageReady(false);
                    // FIX (audit R4): same ENOSPC gate redirect as the fast loop /
                    // direct sites — a mounted-but-full SD must not wedge the slow
                    // retry forever. startRecording still gets the ORIGINAL outputDir.
                    java.io.File probeDir = resolveProbeDirWithEnospcFallback(
                            (outputDir != null) ? outputDir
                                    : StorageManager.getInstance().getRecordingsDir());
                    // FIX (audit R1): snapshot recorder + re-verify pipeline
                    // is running before startRecording (concurrent-stop NPE
                    // / zombie-recording guard).
                    GpuMosaicRecorder snapRec = recorder;
                    if (stopping || !running || snapRec == null) {
                        logger.info("Slow-retry: pipeline torn down mid-probe — exiting");
                        return;
                    }
                    if (snapRec.getEncoder() != null
                            && snapRec.getEncoder().isFormatAvailable()
                            && isStorageWriteReady(probeDir)) {
                        snapRec.startRecording(outputDir, prefix);
                        if (snapRec.isRecording()) {
                            currentMode = Mode.NORMAL_RECORDING;
                            recordingMode = true;
                            // FIX (audit R4, Finding 5): mirror startRecording's
                            // success path — capture active session so a later
                            // camera-yield resume can re-enter pipeline.start
                            // Recording with the correct dir/prefix instead of
                            // falling back to default.
                            activeRecordingDir = outputDir;
                            activeRecordingPrefix = prefix;
                            snapRec.setOverlayRecordingModeAllowed(true);
                            pushOverlayFieldsForFlow("pano");
                            if (telemetryCollector != null) {
                                telemetryCollector.setOverlayRecordingActive(true);
                                telemetryCollector.startPolling();
                            }
                            pendingRecordingDir = null;
                            pendingRecordingPrefix = null;
                            logger.info("Normal recording started on storage SLOW-retry #"
                                + attempt
                                + " (dir=" + (outputDir != null ? outputDir.getName() : "default")
                                + ", prefix=" + prefix + ", active session captured)");
                            // FIX (audit R1): notify RMM after slow-retry success
                            // so modeActive gets re-evaluated and the resync
                            // ticker doesn't tear down the just-started recording.
                            try {
                                com.overdrive.app.recording.RecordingModeManager rmm =
                                    com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                                if (rmm != null) {
                                    rmm.resyncFromHardware("storage-slow-retry-success");
                                    logger.info("RMM resynced after slow-retry success");
                                }
                            } catch (Throwable t) {
                                logger.warn("RMM resync after slow-retry failed: "
                                    + t.getMessage());
                            }
                            slowRetryRunning = false;
                            return;
                        }
                    }
                    logger.info("Storage slow-retry #" + attempt
                        + ": still not write-ready, will retry in "
                        + (STORAGE_SLOW_RETRY_INTERVAL_MS / 1000) + "s");
                } catch (Exception e) {
                    logger.warn("Storage slow-retry #" + attempt + " error: " + e.getMessage());
                }
            }
        }, "RecStorageSlowRetry");
        storageSlowRetryThread.setDaemon(true);
        storageSlowRetryThread.start();
    }

    private synchronized void cancelStorageReadyRetry() {
        // Cancel both the fast retry and the slow-retry tail. Either or
        // both may be alive depending on how far through the cold-start
        // recovery we are. Setting slowRetryRunning=false is the primary
        // exit signal for the slow loop; the interrupt below additionally
        // unblocks a sleeping slow-retry thread so the cancel returns
        // promptly rather than after the next 30s tick.
        slowRetryRunning = false;
        Thread t = storageRetryThread;
        if (t != null) {
            t.interrupt();
            storageRetryThread = null;
        }
        Thread st = storageSlowRetryThread;
        if (st != null) {
            st.interrupt();
            storageSlowRetryThread = null;
        }
    }

    /**
     * Timeout-bounded write probe for the target recordings volume. Creates the
     * dir if needed, then writes + deletes a tiny temp file on a worker thread
     * joined with a short timeout.
     *
     * <p>Returns false if the volume can't be written OR — the key case — the
     * probe doesn't finish in time. On a half-mounted USB at cold start,
     * filesystem ops (mkdirs / ensureRecordingsSpace's scan / {@code new
     * MediaMuxer()}'s open) can block indefinitely with no return, which would
     * otherwise hang the recording-start thread and defeat the retry above (the
     * isRecording() check never runs). Gating the real start on this cheap probe
     * turns that indefinite hang into a fast, recoverable "not ready → retry".
     * The probe thread is a daemon and holds no pipeline locks, so even if it
     * does hang on a wedged volume it is harmless and reaped when the process or
     * the volume recovers.
     */
    /**
     * Resolve the recordings write-probe target through the ENOSPC fallback so a
     * mounted-but-FULL external card doesn't wedge recording for the whole drive.
     *
     * <p>The write probe ({@link #isStorageWriteReady}) does a real {@code write()}
     * on the target. When the configured external volume (SD/USB) is mounted but
     * physically full, that {@code write()} ENOSPC-fails on EVERY tick while StatFs
     * keeps reporting the volume mounted — so the probe never passes, the deferred
     * retry re-probes the SAME dead path, and recording never starts even though
     * internal has tens of GB free (observed: full 14-min wedge after a cold-start
     * restart onto a full card). The running recorder already sidesteps this with
     * a per-segment {@link StorageManager#resolveTargetWithEnospcFallback}; this
     * applies the SAME redirect on the activation/deferred probe so the probe — and
     * the segment it gates — lands on internal when the card is full. No-op when the
     * target is internal, has room, or is genuinely unmounted (left to the mount
     * watchdog). {@code trackState=false}: the recorder's own per-segment call owns
     * the UI fallback banner; this probe must not flap the latch on a transient miss.
     */
    private java.io.File resolveProbeDirWithEnospcFallback(java.io.File probeDir) {
        if (probeDir == null) return null;
        try {
            java.io.File enospcSafe = StorageManager.getInstance()
                .resolveTargetWithEnospcFallback(probeDir, 100 * 1024 * 1024, false);
            if (enospcSafe != null && enospcSafe != probeDir) {
                logger.warn("Recordings volume full — pre-flight redirecting write probe to internal fallback: "
                    + enospcSafe.getAbsolutePath());
                return enospcSafe;
            }
        } catch (Throwable t) {
            logger.warn("ENOSPC pre-flight resolve threw, probing configured dir: " + t.getMessage());
        }
        return probeDir;
    }

    /**
     * Public (rather than private) for exactly one external caller:
     * StorageManager's post-mount recording-migration worker probes the
     * freshly-resolved external recordings dir with THIS bounded touch-probe
     * before it stops a healthy internal-fallback session — mount liveness
     * alone (StatFs on the volume root) cannot prove the recordings
     * directory itself accepts writes (perms reset, RO remount, dir-level
     * FUSE wedge), and discovering that only after the stop would trade a
     * working internal recording for none at all. Same probe, same timeout,
     * same semantics as the recording-start pre-flight — a target the start
     * path would accept is exactly what the migration must require.
     *
     * <p><b>Same-path single-flight.</b> A probe that misses its join budget
     * leaves its daemon thread wedged inside the FUSE write until the volume
     * recovers — and every RETRY loop above this method (the pipeline's own
     * 2s storage-ready retry, StorageManager's post-mount migration worker)
     * would otherwise stack a fresh wedged thread per attempt (~11 per
     * migration edge alone). One outstanding probe per absolute path is both
     * the leak fix and the honest answer: an unanswered probe already IS the
     * "not write-ready" verdict, so callers get {@code false} immediately
     * instead of paying another timeout against the same dead directory.
     * The wedged thread removes itself from the registry whenever the volume
     * finally lets it finish, after which probing resumes normally.
     */
    public boolean isStorageWriteReady(java.io.File dir) {
        if (dir == null) return false;
        final java.io.File target = dir;
        final String probeKey = dir.getAbsolutePath();
        final boolean[] ok = {false};
        final Thread[] spawned = {null};
        writeProbesInFlight.compute(probeKey, (key, existing) -> {
            if (existing != null) {
                // ANY existing entry is an in-flight probe — deliberately no
                // isAlive() check. The entry is published HERE, before the
                // spawning caller reaches start(), and a NEW (un-started)
                // thread reads isAlive()==false: an aliveness test would let
                // a concurrent same-path caller mistake the about-to-start
                // probe for a dead leftover, replace it, and run TWO probes
                // against the same path — the exact stacking this map
                // prevents. Presence IS the liveness signal: every started
                // probe removes itself in its finally, and the one way an
                // entry can go stale — start() itself throwing — is cleaned
                // up at the spawn site below.
                return existing;
            }
            // No probe outstanding — this call owns the slot.
            Thread t = new Thread(() -> {
                try {
                    if (!target.exists()) {
                        target.mkdirs();
                    }
                    if (!target.isDirectory()) return;
                    java.io.File probeFile = new java.io.File(target,
                        ".wrprobe_" + android.os.Process.myPid());
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(probeFile);
                    try {
                        fos.write(0);
                        fos.flush();
                    } finally {
                        try { fos.close(); } catch (Exception ignored) {}
                    }
                    probeFile.delete();
                    ok[0] = true;
                } catch (Throwable ignored) {
                    // ok stays false — volume not write-ready
                } finally {
                    // Two-arg remove: only clears the slot if THIS thread
                    // still owns it (a dead predecessor's late finally must
                    // not evict a live successor probe).
                    writeProbesInFlight.remove(key, Thread.currentThread());
                }
            }, "RecWriteProbe");
            t.setDaemon(true);
            spawned[0] = t;
            return t;
        });
        if (spawned[0] == null) {
            logger.info("isStorageWriteReady: probe for " + probeKey
                + " already in flight (concurrent caller or earlier timed-out attempt) — "
                + "reporting not-ready without stacking another probe thread");
            return false;
        }
        try {
            spawned[0].start();
        } catch (Throwable t) {
            // start() failing (thread limit, OOM) means the probe body's
            // self-removing finally will never run — without this cleanup the
            // never-started thread would occupy the path's slot forever and
            // every future probe of this path would report not-ready. Two-arg
            // remove so we only clear the slot if we still own it.
            writeProbesInFlight.remove(probeKey, spawned[0]);
            logger.warn("isStorageWriteReady: probe thread start failed for " + probeKey
                + " (" + t.getMessage() + ") — slot released, reporting not-ready");
            return false;
        }
        try {
            spawned[0].join(STORAGE_PROBE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok[0];  // false if the probe timed out (still running) or failed
    }

    /**
     * In-flight {@link #isStorageWriteReady} probe threads keyed by absolute
     * directory path — see the single-flight note on that method. An entry's
     * PRESENCE is the in-flight signal (no isAlive() checks — a published
     * thread may legitimately still be NEW for a beat before its owner calls
     * start()). Entries self-remove in the probe's finally, or at the spawn
     * site when start() itself throws; a wedged probe therefore occupies its
     * path's slot exactly as long as the volume holds its write hostage.
     */
    private final java.util.concurrent.ConcurrentHashMap<String, Thread> writeProbesInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Timeout-bounded wrapper around {@link StorageManager#ensureStorageReady}
     * for the recording-start critical path. {@code ensureStorageReady} can
     * block for minutes on a cold-boot FUSE-bridged SD/USB (mount retry loop +
     * dir-walk under binder contention); when it does, {@link #startRecording}
     * stalls BEFORE the encoder-format branch while holding
     * RecordingModeManager's activationLock + warmupInFlight, wedging recording
     * activation for the entire stall (field log
     * camera_daemon_20260610_155444: 4m28s ACC-ON→first-frame).
     *
     * <p>Same daemon-thread + bounded-join idiom as {@link #isStorageWriteReady}.
     * The worker holds no pipeline locks, so if it hangs on a wedged volume it is
     * harmless and reaped when the volume/process recovers. A {@code true} return
     * means {@code ensureStorageReady} completed and returned true within the
     * budget; ANY other outcome (timeout, false, or throw) returns {@code false},
     * which the caller already handles by continuing to the bounded
     * {@code isStorageWriteReady} probe + deferred-retry machinery.
     *
     * <p>Budget: {@link #ENSURE_STORAGE_READY_TIMEOUT_MS}. A healthy mount
     * resolves in well under this, so the common path is unchanged; the budget
     * only caps the pathological cold-boot stall.
     */
    private boolean ensureStorageReadyBounded(boolean forSurveillance) {
        final boolean[] ready = {false};
        Thread probe = new Thread(() -> {
            try {
                ready[0] = StorageManager.getInstance().ensureStorageReady(forSurveillance);
            } catch (Throwable t) {
                logger.warn("ensureStorageReadyBounded: ensureStorageReady threw: " + t.getMessage());
                // ready stays false — caller falls through to the write probe.
            }
        }, "EnsureStorageReady");
        probe.setDaemon(true);
        probe.start();
        try {
            probe.join(ENSURE_STORAGE_READY_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (probe.isAlive()) {
            logger.warn("ensureStorageReady did not return within "
                + ENSURE_STORAGE_READY_TIMEOUT_MS + "ms (storage mount likely wedged) — "
                + "proceeding to bounded write probe + deferred retry instead of "
                + "blocking activation");
            return false;
        }
        return ready[0];
    }

    // Budget for the recording-start ensureStorageReady call. Generous relative
    // to a healthy mount (sub-second) but well under the activation watchdog's
    // 30s stuck-warmup threshold, so a wedged mount surfaces as a fast deferred
    // retry rather than a multi-minute activation stall.
    private static final long ENSURE_STORAGE_READY_TIMEOUT_MS = 4_000L;

    /**
     * Stops recording.
     */
    public void stopRecording() {
        // CRITICAL: Clear any pending (deferred) recording request FIRST.
        // During cold start, startRecording() defers to checkPendingRecording() if the
        // encoder isn't ready yet. If a gear change (D→N/P) triggers stopRecording()
        // before the encoder is ready, the pending request survives and fires later —
        // starting recording in the wrong gear state. Clearing it here prevents that.
        // FIX (audit R7): clear under `synchronized (this)` so a concurrent
        // checkPendingRecording (encoder format-available listener thread) sees
        // the clear atomically — without this, the listener can capture a
        // non-null prefix AFTER recorder.stopRecording has run and start a
        // ghost recording against the freshly-stopped recorder.
        synchronized (this) {
            pendingRecordingDir = null;
            pendingRecordingPrefix = null;
            // FIX (audit R3, Findings 3+6): drop active-session memory; a yield
            // after this point should NOT auto-resume because the user/RMM has
            // explicitly stopped.
            activeRecordingDir = null;
            activeRecordingPrefix = null;
            recordingMode = false;
        }
        cancelStorageReadyRetry();

        if (recorder != null) {
            recorder.stopRecording();

            // Disable overlay compositing when recording stops
            recorder.setOverlayRecordingModeAllowed(false);
            if (telemetryCollector != null) {
                telemetryCollector.setOverlayRecordingActive(false);
                telemetryCollector.stopPolling();
            }

            // FIX (sentry-mode desync, root cause): this is a NORMAL/continuous
            // recording stop, but the recorder is SHARED with surveillance event
            // clips — so the SD-card watchdog (StorageManager remount branches) and
            // setRecordingsStorageType() call pipeline.stopRecording() while sentry
            // is armed and mid-event (recorder.isRecording()==true). Those callers
            // are gated on recordingsStorageType==SD_CARD, which is exactly why the
            // bug only reproduces with recordings on SD. Unconditionally forcing
            // currentMode=IDLE there desynced the pipeline: sentry.isActive() stayed
            // true but the mode read IDLE, which made isSurveillanceMode()/status API
            // lie AND let the live-view WS-idle auto-stop tear the whole pipeline
            // down out from under running surveillance. If the sentry engine is still
            // armed, the pipeline IS conceptually still in surveillance — keep the
            // mode SURVEILLANCE; only fall back to IDLE when nothing is armed. During
            // ACC-on normal recording sentry is never active, so this is a no-op there.
            SurveillanceEngineGpu sen = sentry;
            if (sen != null && sen.isActive()) {
                currentMode = Mode.SURVEILLANCE;
                logger.info("Normal recording stopped (sentry still armed — mode stays SURVEILLANCE)");
            } else {
                currentMode = Mode.IDLE;
                logger.info("Normal recording stopped");
            }
        }
    }
    
    /**
     * Enables surveillance mode (motion detection + event recording).
     * Stops normal recording if active (mutually exclusive).
     * SOTA: Ensures SD card is mounted if SD card storage is selected.
     */
    public void enableSurveillance() {
        // Stop normal recording if active (mutually exclusive)
        if (currentMode == Mode.NORMAL_RECORDING) {
            logger.info("Stopping normal recording to enable surveillance (mutually exclusive)");
            if (recorder != null) {
                recorder.stopRecording();
            }
        }
        
        // SOTA: Ensure storage is ready (mount SD card if needed).
        // BOUNDED (audit: arm/disarm notification storm): this was the raw
        // ensureStorageReady(true), which can block for minutes on a
        // FUSE-bridged SD mid-enumeration — blowing the 15s surveillance-
        // enable lease (CameraDaemon revoked it "after enable deadline" in
        // the field log), marking the ACC transition stale and re-running
        // the whole OFF lifecycle. Same bounded-probe idiom as the
        // recording-start path above; on timeout we continue on the internal
        // fallback and the engine's per-trigger dir refresh routes a
        // late-landing mount to SD.
        try {
            StorageManager storage = StorageManager.getInstance();
            if (!ensureStorageReadyBounded(true)) {
                logger.warn("Storage not ready for surveillance, but continuing with fallback");
            }

            // Boot/ACC-on mount-race: ensureStorageReady only ATTEMPTS the mount;
            // the real mount may still be in flight on the background
            // StorageMountInit thread. Give the configured external volume a short
            // bounded window to land BEFORE we snapshot the event dir below, so the
            // first 1-2 events don't get pinned to the internal fallback. No-op for
            // INTERNAL config / already-mounted volume / physically-absent SD.
            //
            // FIRST-ARM-OF-LIFECYCLE ONLY (mountWaitConsumed one-shot): the wait is
            // only load-bearing on the very first arm after pipeline start — the
            // inaugural eventOutputDir snapshot below. On any re-arm (notably the
            // lock-gate force-arm AFTER a grace-period UNLOCK disarm: surveillance
            // was armed, then disarmed to currentMode=IDLE without stopping the
            // pipeline, then a fresh LOCK re-arms) currentMode is IDLE / sentry is
            // inactive again, so the mode-based guard alone would re-enter the wait.
            // That re-entry is the audit-R3 coupling: CameraDaemon's force-arm holds
            // the static CameraDaemon.class monitor across enableSurveillance(), and
            // applyLockEvent() is static-synchronized on the same monitor, so a
            // concurrent owner-return UNLOCK disarm would block for up to
            // STORAGE_MOUNT_WAIT_MS. We skip the wait on re-arm because the engine's
            // per-trigger getLiveSurveillanceDir() refresh (SurveillanceEngineGpu
            // Site A/B) already routes a late-landing mount to SD on every event, so
            // the enable-time wait buys nothing there. NOTE: isRunning() can NOT
            // gate this — CameraDaemon.enableSurveillance() calls start()
            // (running=true) BEFORE gpuPipeline.enableSurveillance(), so running is
            // already true on the cold first arm. mountWaitConsumed is reset in
            // stop() so a fresh pipeline lifecycle re-arms the wait.
            if (!mountWaitConsumed
                    && (currentMode != Mode.SURVEILLANCE || sentry == null || !sentry.isActive())) {
                mountWaitConsumed = true;
                try {
                    storage.waitForConfiguredExternalMount(STORAGE_MOUNT_WAIT_MS);
                } catch (Throwable ignored) {
                    // Defensive: never let the boot-race guard break the enable path.
                }
            }

            // SOTA: Update sentry's event output directory to current surveillance path
            // This handles storage type changes (internal <-> SD card) at runtime
            if (sentry != null) {
                File currentSurveillanceDir = storage.getSurveillanceDir();
                sentry.setEventOutputDir(currentSurveillanceDir);
                logger.info("Surveillance output directory: " + currentSurveillanceDir.getAbsolutePath());
            }
        } catch (Exception e) {
            logger.warn("Error checking storage readiness: " + e.getMessage());
        }
        
        if (sentry != null) {
            sentry.enable();
            currentMode = Mode.SURVEILLANCE;
            logger.info("Surveillance mode enabled (sentry.active=" + sentry.isActive() + ")");
            // Assert the ACC-off surveillance fps/bitrate NOW that the mode is
            // SURVEILLANCE. setRecordingMode(SENTRY) only fires on the direct
            // ACC-off (door-lock) path; the schedule-window-open and
            // safe-zone-exit arm paths reach enableSurveillance() WITHOUT it, so
            // without this call those paths would arm at the ACC-ON recording
            // tier until RMM's 30s reconcile self-healed — mis-tiering any event
            // clip captured in that window. Funnels through the same
            // reconfigLock-guarded body as every other surveillance-profile push.
            // No-op-equivalent on a pre-split config (resolves to the recording
            // tier). Skip while a live encoder reconfig is mid-flight (running/
            // stopping gate) — that path re-asserts on its own completion.
            synchronized (reconfigLock) {
                if (running && !stopping) {
                    applySurveillanceProfileLocked("arm");
                }
            }
        } else {
            logger.error("Cannot enable surveillance: sentry is null!");
        }
        
        // Disable overlay compositing in surveillance mode
        if (recorder != null) {
            recorder.setOverlayRecordingModeAllowed(false);
        }
        if (telemetryCollector != null) {
            telemetryCollector.setOverlayRecordingActive(false);
            telemetryCollector.stopPolling();
        }

        // Now that the mode is SURVEILLANCE, switch the recorder + windshield
        // to sentry's own layout profile. applyActiveLayoutProfile() reads
        // currentMode, so if sentry was null (mode unchanged) this harmlessly
        // re-applies the dashcam profile instead.
        applyActiveLayoutProfile();
    }

    /**
     * Disables surveillance mode.
     */
    public void disableSurveillance() {
        if (sentry != null) {
            // PUBLISH IDLE **BEFORE** THE ENGINE DISABLE (audit R14-2 /
            // ExtF-2). sentry.disable() is a serialized state transition
            // that can take multi-second worst case (drains + recording
            // stop + encoder close). With IDLE published only AFTER it, a
            // camera reacquire landing in that window read
            // currentMode==SURVEILLANCE + !sentry.isActive(), queued
            // enable() behind the engine's state-transition lock, RE-ARMED
            // surveillance the moment our disable finished — and then this
            // method's trailing IDLE write published a mode that
            // contradicted the armed engine (engine armed, pipeline
            // reporting idle: no frames routed, zombie session). Publishing
            // the intent first means any reacquire during the teardown sees
            // IDLE and routes to normal-mode handling; the engine-side
            // monitor (R13-2) makes the disable itself atomic. If the
            // disable somehow fails, IDLE still reflects the caller's
            // intent — the engine's own guards keep a half-disabled session
            // from recording.
            currentMode = Mode.IDLE;
            sentry.disable();
            logger.info( "Surveillance mode disabled");
            // Back to IDLE → restore the dashcam layout profile so the next
            // normal/continuous recording uses the dashcam setting, not the
            // sentry one we may have switched to in enableSurveillance().
            applyActiveLayoutProfile();
        }
    }
    
    /**
     * Called when ACC turns ON - stops surveillance recording.
     * This ensures sentry recordings are properly finalized when car starts.
     * 
     * CRITICAL: Must synchronously close any active recording to prevent file corruption.
     */
    public void onAccOn() {
        logger.info("ACC ON detected - stopping surveillance and finalizing recordings");

        try {
            // First, stop any active recording immediately (synchronous)
            if (recorder != null && recorder.isRecording()) {
                logger.info("Stopping active recording before ACC transition");
                recorder.stopRecording();
            }

            // Also flush and close the encoder to ensure file is finalized
            if (encoder != null && encoder.isWritingToFile()) {
                logger.info("Flushing encoder before ACC transition");
                encoder.flushAndClose();
            }

            // Now disable surveillance mode
            if (currentMode == Mode.SURVEILLANCE) {
                disableSurveillance();
                // FIX (audit R5): a surveillance trigger that landed between
                // the initial recorder.stopRecording above and sentry.disable
                // here can re-arm the recorder. disableSurveillance only
                // tears down the sentry listener; an event that already
                // crossed the threshold and called recorder.startRecording
                // is still alive. Drain it now, before camera.reopenCamera —
                // otherwise reopenCamera nukes the producer surface mid-write
                // and the segment finalizes corrupted.
                if (recorder != null && recorder.isRecording()) {
                    logger.warn("onAccOn: surveillance re-armed recorder during stop window — "
                        + "draining before camera reopen");
                    try { recorder.stopRecording(); }
                    catch (Throwable t) { logger.warn("onAccOn: post-disable drain failed: " + t.getMessage()); }
                }
            }

            // Also stop normal recording if active
            if (currentMode == Mode.NORMAL_RECORDING) {
                stopRecording();
            }

            // FIX (audit R5): clear deferred-recording state unconditionally.
            // currentMode could be SURVEILLANCE or IDLE here (above branches
            // only handle NORMAL_RECORDING via stopRecording — which clears
            // these — and SURVEILLANCE via disableSurveillance — which does
            // NOT). A pending intent left over from cold-start could
            // otherwise resurrect a recording after camera reopen, against
            // RMM's intent. RMM re-issues post-ACC if the new mode demands.
            if (pendingRecordingDir != null || pendingRecordingPrefix != null
                    || recordingMode) {
                logger.info("onAccOn: clearing residual deferred-recording state "
                    + "(pending=" + pendingRecordingPrefix
                    + ", recordingMode=" + recordingMode + ")");
                pendingRecordingDir = null;
                pendingRecordingPrefix = null;
                recordingMode = false;
            }
            try { cancelStorageReadyRetry(); }
            catch (Throwable t) { logger.warn("onAccOn: cancelStorageReadyRetry failed: " + t.getMessage()); }

            // Reset config-side recording mode back to NORMAL so the next
            // startRecording() doesn't apply SENTRY-tier bitrate left over
            // from a prior surveillance session. Done before camera reopen
            // so the transition lands in a fully consistent state.
            try {
                config.setRecordingMode(GpuPipelineConfig.RecordingMode.NORMAL);
            } catch (Throwable t) {
                logger.warn("onAccOn: config.setRecordingMode(NORMAL) failed: " + t.getMessage());
            }

            // OEM-PARITY: dilink4 never closes the AVMCamera handle. oem's
            // PanoCameraRecord stays alive across ACC ON; the BYD native AVC app
            // attaches as a co-consumer of the AVM HAL daemon (gl/C5920a.java
            // observerSet) and shares the producer surface naturally.
            // reopenCamera() does a full close+reopen of our AVMCamera handle,
            // which on byd_apa firmware drops mosaic mode and leaves the next
            // open with all-zero frames — exactly what the user reports.
            //
            // Legacy fleet keeps the original "release and reopen as secondary
            // consumer" behaviour because that's how non-byd_apa HALs share.
            boolean dilink4 = false;
            try {
                dilink4 = com.overdrive.app.daemon.CameraDaemon.isDilink4ModeActiveStatic();
            } catch (Throwable ignored) {}
            if (camera != null && running && !dilink4) {
                camera.reopenCamera();
                logger.info("ACC ON transition complete - all recordings finalized, camera reopened");
            } else if (dilink4) {
                logger.info("ACC ON transition complete - dilink4 keeps camera alive (oem-parity, no reopen)");
            } else {
                logger.info("ACC ON transition complete - all recordings finalized");
            }
        } catch (Throwable t) {
            // Any failure mid-transition leaves the pipeline in a half-mutated
            // state (currentMode flipped, running=true, dead camera handle).
            // Force a full stop so the next caller sees a clean slate and can
            // do a fresh start() — better to drop a few frames than to wedge
            // recording for the rest of the drive.
            logger.error("onAccOn failed mid-transition — forcing full stop to recover: "
                + t.getMessage());
            try { stop(); } catch (Throwable t2) {
                logger.warn("Recovery stop also failed: " + t2.getMessage());
            }
            throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
        }
    }
    
    /**
     * Enables H.264 streaming with separate encoder.
     * 
     * @param streamWidth Stream width (e.g., 1280)
     * @param streamHeight Stream height (e.g., 960)
     * @param streamFps Stream FPS (e.g., 10)
     * @param streamBitrate Stream bitrate (e.g., 2 Mbps)
     */
    public void enableStreaming(int streamWidth, int streamHeight, int streamFps,
                               int streamBitrate) throws Exception {
        streamLifecycleLock.lock();
        try {
            if (streamingEnabled) {
                logger.warn("Streaming already enabled");
                return;
            }

            // Reject while a stop is in flight (audit follow-up 3): stop()'s
            // unconditional disableStreaming barrier has possibly ALREADY run,
            // so an enable admitted now would build a live encoder+drainer that
            // stop's subsequent camera close never re-checks. `stopping` is
            // volatile; the definitive re-check happens again after the
            // auto-start below.
            if (stopping) {
                throw new IllegalStateException(
                    "stream enable refused — pipeline stop in progress");
            }

            // Refuse a replacement while the RETIRING encoder's release is still
            // in flight or failed (audit follow-up): a rapid disable→enable used
            // to install a fresh encoder while the old one's drainer was still
            // dequeuing against the camera — the new lane's healthy workers then
            // masked the retiring one from every guard. Not-done → transient
            // refusal (caller retries in a moment); done-but-wedged → terminal
            // (release() already requested the trip-safe restart).
            java.util.concurrent.CompletableFuture<Boolean> retiring =
                retiringStreamEncoderRelease.get();
            if (retiring != null && !retiring.isDone()) {
                throw new IllegalStateException("stream re-enable refused — previous "
                    + "stream encoder release still in flight; retry shortly");
            }
            if (retiring != null
                    && !verifyRetiringStreamEncoderRelease(0, "enableStreaming")) {
                throw new IllegalStateException("stream re-enable refused — previous "
                    + "stream encoder release wedged (trip-safe restart pending)");
            }

            // Auto-start pipeline if not running (e.g., DRIVE_MODE in gear P, user opens stream)
            if (!running) {
                logger.info("Pipeline not running — auto-starting for streaming (view-only)");
                start(false);  // Start without auto-recording
            }

            // start(false) REFUSES BY RETURNING NORMALLY when a stop is mid-flight,
            // a trip-safe restart is pending, or the pipeline is terminal — so its
            // outcome must be re-checked, not assumed (audit follow-up 3).
            // Proceeding here used to build a live encoder+drainer against the
            // RETAINED camera object (whose stale GL handler is non-null even
            // after its looper quit), after stop()'s disable barrier had already
            // run — a drainer nothing would guard at the camera close.
            if (!running || stopping) {
                throw new IllegalStateException("Cannot enable streaming — pipeline "
                    + "not running (stop in progress, or start refused)");
            }

            // Verify camera GL thread is ready after start
            if (camera == null || camera.getGlHandler() == null) {
                logger.error("Cannot enable streaming - camera GL thread not ready");
                throw new IllegalStateException("Camera GL thread not initialized");
            }
            try {
                enableStreamingInternal(streamWidth, streamHeight, streamFps, streamBitrate);
            } catch (Throwable t) {
            // On any failure during init, mirror disableStreaming's
            // teardown order:
            //   1. clear camera-side refs FIRST so the GL render loop
            //      stops dereferencing the about-to-be-released scaler
            //      / encoder. enableStreamingInternal calls
            //      camera.setStreamingComponents BEFORE wsStreamServer
            //      starts, so by the time we reach this catch the
            //      camera may already hold them.
            //   2. snapshot + null pipeline fields.
            //   3. shutdown ws server.
            //   4. post scaler.release on the GL handler; encoder.release
            //      goes through STREAM_ENCODER_RELEASE_EXEC so the 3s
            //      waitForFinalizers doesn't pin the GL handler.
            // Without #1 the camera GL render loop calls drawFrame on a
            // released GL program after this catch returns.
            try {
                if (camera != null) camera.clearStreamingComponents();
            } catch (Throwable ignored) {}
            final HardwareEventRecorderGpu encLocal = streamEncoder;
            final com.overdrive.app.streaming.GpuStreamScaler scLocal = streamScaler;
            final com.overdrive.app.streaming.WebSocketStreamServer wsLocal = wsStreamServer;
            streamEncoder = null;
            streamScaler = null;
            wsStreamServer = null;
            // Publish the retirement placeholder SYNCHRONOUSLY at detach (audit
            // follow-up 2): publication from inside the GL runnable left the
            // guard bypassable whenever the runnable outran the caller's wait.
            // The runnable now only COMPLETES this placeholder; if it never
            // runs, the pending placeholder correctly reads as wedged.
            final java.util.concurrent.CompletableFuture<Boolean> encRetireVerdict =
                (encLocal != null) ? publishRetiringStreamEncoderRelease() : null;
            try { if (wsLocal != null) wsLocal.shutdown(); } catch (Throwable ignored) {}
            android.os.Handler glH = (camera != null) ? camera.getGlHandler() : null;
            boolean glPostAccepted = false;
            if (glH != null && scLocal != null) {
                glPostAccepted = glH.post(() -> {
                    try { scLocal.unbindOemSource(); scLocal.release(); }
                    catch (Throwable ignored) {}
                    // Encoder release dispatched AFTER scaler.release runs
                    // (still on GL thread), so the BufferQueue tear-down
                    // can't race the EGLWindowSurface destroy.
                    if (encLocal != null) submitEncoderRelease(encLocal, encRetireVerdict);
                });
            }
            if (!glPostAccepted) {
                // Either no GL handler available, or post() returned false
                // (Looper.quit() ran concurrently on a competing stop). In
                // either case the GL Runnable will never execute, so
                // scaler + encoder must be released here or both leak. The
                // Adreno EGLWindowSurface-destroy race the GL ordering
                // protects against can't trip if there's no GL thread to
                // race with — fall back to direct release for both.
                try { if (scLocal != null) { scLocal.unbindOemSource(); scLocal.release(); } }
                catch (Throwable ignored) {}
                if (encLocal != null) submitEncoderRelease(encLocal, encRetireVerdict);
            }
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        } finally {
            streamLifecycleLock.unlock();
        }
    }

    private void enableStreamingInternal(int streamWidth, int streamHeight, int streamFps,
                                         int streamBitrate) throws Exception {
        // R8-A #18: defensively reset externalStreamSourceActive on every
        // enable. The disable path resets it at line ~1999 — but a disable
        // that threw before reaching that line could leave the flag stuck
        // at true, causing the next reattachOwnStreamCallback to try
        // unbinding an OEM source that this fresh enable never bound.
        externalStreamSourceActive = false;

        int streamLayout = camera != null ? camera.getCameraLayoutMode() : 0;
        if (streamLayout == 1) {
            int nativeAspectHeight =
                com.overdrive.app.camera.PassiveApaGeometry.heightForWidth(streamWidth);
            if (streamHeight != nativeAspectHeight) {
                logger.info("Passive APA stream size " + streamWidth + "x" + streamHeight
                    + " -> " + streamWidth + "x" + nativeAspectHeight);
                streamHeight = nativeAspectHeight;
            }
        }

        // On dilink4, cap the ENCODER's declared frame rate to what this HAL can
        // actually deliver.
        //
        // The byd_apa AVM HAL emits at its own fixed low rate and cannot be
        // retimed: setCameraFps returns false for every value we pass (1, 15, 25),
        // and the OEM app (gl/a.java:402) and other OEM-derived players discard that return —
        // false is simply normal here. The OEM's answer is to ASK LOW: its
        // panoramic recorder requests 10 fps (PanoCameraRecordService:530) and its
        // publisher 8 (qi/g.java:316), never the 15-30 our quality presets offer.
        // With the request at or below delivery, the gap never manifests.
        //
        // We keep the user's preset for RESOLUTION and BITRATE — only the declared
        // frame rate is clamped, because that is the number MediaCodec uses to
        // budget bits and pace its rate control. Declaring 15 while receiving ~4.5
        // makes it treat two of every three ticks as a duplicate.
        //
        // Legacy (ImageReader) vehicles are untouched: their HAL honours the
        // requested rate, so the clamp is skipped entirely and streaming behaves
        // byte-identically to before.
        int effectiveStreamFps = streamFps;
        try {
            if (camera != null && camera.isUsingOemSurfaceTexturePath()
                    && streamFps > DILINK4_STREAM_FPS_CAP) {
                effectiveStreamFps = DILINK4_STREAM_FPS_CAP;
                logger.info("dilink4: clamping stream encoder fps " + streamFps + " → "
                    + effectiveStreamFps + " (this HAL refuses setCameraFps and emits"
                    + " at its own low fixed rate; OEM asks 10 on this lane)."
                    + " Resolution/bitrate keep the user's preset.");
            }
        } catch (Throwable t) {
            logger.warn("dilink4 stream-fps clamp check failed: " + t.getMessage());
        }

        logger.info(String.format("Enabling H.264 streaming: %dx%d @ %dfps, %d Mbps",
                streamWidth, streamHeight, effectiveStreamFps, streamBitrate / 1_000_000));

        // Create stream encoder
        logger.info("Creating stream encoder...");
        streamEncoder = new HardwareEventRecorderGpu(streamWidth, streamHeight, effectiveStreamFps, streamBitrate);
        streamEncoder.setUsePreRecordBuffer(false);  // Stream-only, no pre-record needed
        // Do NOT pin KEY_OPERATING_RATE on this SECONDARY encoder. The primary
        // recording encoder already pins it at fps to hold the Venus clock; if
        // the live-view stream encoder ALSO pins, both double-claim the single
        // SDM665 Venus block's firmware frequency budget — over-subscribing it
        // and producing the exact eglSwap stalls the pin was meant to prevent
        // (two encoders on one HW block). Only the primary encoder should claim
        // the frequency lock. Mirrors OemDashcamPipeline.java:1039, which sets
        // this on its own secondary encoder for the same reason.
        streamEncoder.setPinOperatingRate(false);
        streamEncoder.init();
        logger.info("Stream encoder initialized");
        
        // Create stream scaler
        logger.info("Creating stream scaler...");
        // Stream scaler picks the same per-role offsets used by the
        // recorder so user-mapped role-to-slice mappings affect
        // single-direction streaming too. We pass BOTH 4-strip offsets and
        // 2x2 corners; the shader picks based on uApaMode (cameraLayout).
        com.overdrive.app.camera.ResolvedCameraConfig streamCfg =
            com.overdrive.app.camera.CameraConfigResolver.resolve(getVehicleModel());
        float[] streamQuadrantStripOffsetX = streamCfg.getQuadrantStripOffsetX();
        streamScaler = new com.overdrive.app.streaming.GpuStreamScaler(
            streamWidth, streamHeight, streamQuadrantStripOffsetX);

            try {
                android.content.Context odCtx = savedContext;
                if (odCtx == null) odCtx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (odCtx != null) {
                    com.overdrive.app.od.Od.authorize(odCtx);
                } else {
                    logger.error("od authorize skipped: no context available");
                }
            } catch (Throwable t) {
                logger.warn("od init failed: " + t.getMessage());
            }

        // Match the recorder exactly: 0=legacy strip, 1=full-frame passive
        // APA (and DiLink 5), 3=four-corner DiLink 4 remap.
        streamScaler.setCameraLayout(streamLayout);

        // Hardcoded Variant A corner+flip constants on DiLink 4. Mirrors
        // GpuMosaicRecorder so live stream and recording stay aligned. On
        // legacy cars the uniforms are unused (uApaMode != 3 path).
        if (streamLayout == 3) {
            // Single combined call referencing the shared Dilink4Constants
            // so the stream scaler can never silently diverge from the
            // recorder's mosaic arrangement.
            streamScaler.setProducerLayout(
                com.overdrive.app.camera.Dilink4Constants.CORNER_FRONT,
                com.overdrive.app.camera.Dilink4Constants.CORNER_RIGHT,
                com.overdrive.app.camera.Dilink4Constants.CORNER_REAR,
                com.overdrive.app.camera.Dilink4Constants.CORNER_LEFT,
                com.overdrive.app.camera.Dilink4Constants.FLIP_FRONT,
                com.overdrive.app.camera.Dilink4Constants.FLIP_RIGHT,
                com.overdrive.app.camera.Dilink4Constants.FLIP_REAR,
                com.overdrive.app.camera.Dilink4Constants.FLIP_LEFT);
        }
        if (streamLayout != 0) {
            // Red-overlay suppression follows the recorder. Read the same
            // unified-config flag so the live preview matches the MP4.
            try {
                org.json.JSONObject camCfgStream = com.overdrive.app.config
                    .UnifiedConfigManager.loadConfig().optJSONObject("camera");
                if (camCfgStream != null) {
                    streamScaler.setRedMaskEnabled(
                        camCfgStream.optBoolean("dilink4RedMask", false));
                    streamScaler.setApaCenterInset(streamLayout == 3
                        ? (float) camCfgStream.optDouble(
                            "dilink4ApaCenterInset", 0.09375)
                        : 0.0f);
                }
            } catch (Throwable t) {
                logger.warn("Stream scaler red-mask flag read failed: " + t.getMessage());
            }
        } else if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            streamScaler.setRedMaskEnabled(false);
        }
        
        // Initialize on GL thread and WAIT for completion.
        // Captured locals (NOT the instance fields) — so if the wait
        // times out and the catch path nulls this.streamScaler /
        // this.streamEncoder, the queued init lambda still has a
        // coherent view of the objects to operate on. Without this,
        // a 2-second timeout with the GL thread mid-shader-compile
        // would null the fields, then the eventually-running lambda
        // would NPE on `streamScaler.init` and the partially-built
        // GL program would leak (caught lambda swallowed the NPE).
        final com.overdrive.app.streaming.GpuStreamScaler scalerLocal = streamScaler;
        final HardwareEventRecorderGpu encoderLocal = streamEncoder;
        final com.overdrive.app.camera.EGLCore eglCoreLocal = camera.getEglCore();
        final Object initLock = new Object();
        final boolean[] initDone = {false};
        final Exception[] initError = {null};

        camera.getGlHandler().post(() -> {
            try {
                scalerLocal.init(eglCoreLocal, encoderLocal);
                logger.info("Stream scaler initialized on GL thread");
            } catch (Exception e) {
                logger.error("Failed to initialize stream scaler on GL thread", e);
                initError[0] = e;
            } finally {
                synchronized (initLock) {
                    initDone[0] = true;
                    initLock.notify();
                }
            }
        });

        // Wait for GL thread initialization (max 2 seconds) — HOLDING
        // streamLifecycleLock (audit follow-up 2). An earlier fix released the
        // lock here so peers didn't pin for the full 2s, reasoning that a
        // concurrent disableStreaming "sees streamingEnabled == false and
        // bails — safe". That reasoning missed the camera CLOSE path: the
        // stream encoder and its drainer are ALREADY LIVE (streamEncoder.init()
        // above), while streamingEnabled is still false and the camera-side
        // reference is still null — so a concurrent stop() skipped streaming
        // teardown entirely and closed the camera with NOTHING guarding the
        // brand-new drainer (FORTIFY destroyed-mutex risk). stop() now calls
        // disableStreaming() unconditionally, and blocking it here for up to
        // 2s is exactly the serialization that makes its camera close safe.
        synchronized (initLock) {
            if (!initDone[0]) {
                initLock.wait(2000);
            }
        }

        if (!initDone[0]) {
            throw new RuntimeException("Stream scaler initialization timed out");
        }

        if (initError[0] != null) {
            throw new RuntimeException("Stream scaler initialization failed: " + initError[0].getMessage(), initError[0]);
        }

        // R8-A #9: a concurrent stop() running on the pipeline-level
        // monitor (different from streamLifecycleLock) could have torn
        // down `camera` during the unlocked GL-init wait. Check pipeline
        // viability BEFORE publishing components onto the camera —
        // otherwise camera.setStreamingComponents would write into a
        // released camera object whose eglCore + glHandler are dead, and
        // subsequent draws against streamScaler / streamEncoder would
        // NPE or render to a destroyed context. The catch path in the
        // caller will release the just-allocated scaler+encoder.
        if (!running || camera == null || camera.getGlHandler() == null) {
            throw new IllegalStateException(
                "Pipeline torn down during stream init wait — abandoning enable");
        }

        // Now set components on camera (scaler is guaranteed initialized)
        logger.info("Setting streaming components on camera...");
        camera.setStreamingComponents(streamScaler, streamEncoder);
        camera.updateStreamFrameStride();

        // Create WebSocket stream server (port 8887)
        // WebSocket has zero buffering delay vs HTTP Chunked (64KB+ buffer)
        logger.info("Starting WebSocket stream server...");
        wsStreamServer = new com.overdrive.app.streaming.WebSocketStreamServer();

        // Gate PASS 1B on live client presence: with no viewer on either the
        // port-8887 or the /ws relay path, skip the stream raster + encode
        // entirely (the encoded bytes would be dropped anyway). hasActiveClients
        // counts both consumer paths; the camera re-requests an IDR on the
        // rising edge so a reconnect within the 30s idle window is instantly
        // decodable. Cleared to fail-open in clearStreamingComponents().
        final com.overdrive.app.streaming.WebSocketStreamServer wsForProbe = wsStreamServer;
        camera.setStreamClientProbe(wsForProbe::hasActiveClients);


        // Set idle shutdown callback - auto-stop pipeline when no clients for
        // WebSocketStreamServer.IDLE_TIMEOUT_MS (30 seconds; was mis-documented as 15s)
        final GpuSurveillancePipeline self = this;
        wsStreamServer.setIdleShutdownCallback(new Runnable() {
            @Override
            public void run() {
                logger.info("WebSocket idle timeout - stopping streaming");
                // Run on separate thread to avoid blocking timer thread
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (!self.disableStreamingIfIdle(wsForProbe)) {
                                logger.info("WebSocket idle shutdown cancelled by a new client");
                                return;
                            }
                            // The WS pipe just went dark — view 6 is no longer
                            // a "keep warm" reason for OEM. Re-evaluate so OEM
                            // tears down if no recording mode is asking for it.
                            try {
                                com.overdrive.app.server.OemDashcamApiHandler
                                    .scheduleLifecycleRecalc();
                            } catch (Throwable ignored) {}
                            // Snapshot every cross-thread field once. Without this, a
                            // concurrent stop() can null `recorder` between the null
                            // check and the isRecording() call, NPEing into the
                            // outer catch and leaving streaming half-released.
                            GpuMosaicRecorder rec = recorder;
                            boolean recordingActive = rec != null && rec.isRecording();
                            Mode mode = currentMode;
                            // FIX (sentry-teardown, primary): armed surveillance is a
                            // keep-alive consumer in its OWN right, independent of
                            // currentMode. Between motion events sentry does no
                            // recording (recordingActive=false) and RMM's keepAlive
                            // predicate has no SURVEILLANCE case, so historically the
                            // ONLY thing protecting a live sentry from this WS-idle
                            // auto-stop was mode==SURVEILLANCE. But stopRecording()
                            // (and the SD-watchdog / storage-type-switch callers that
                            // invoke it) could desync currentMode to IDLE while the
                            // engine stayed active — after which a live-view stream's
                            // 30s idle timeout tore down the whole pipeline out from
                            // under running surveillance (field log: sentry frame
                            // arrives 7s before "No recording consumers active").
                            // Consult the engine's authoritative `active` flag so the
                            // teardown can never stop an armed sentry regardless of
                            // what currentMode says.
                            SurveillanceEngineGpu sen = sentry;
                            boolean sentryActive = sen != null && sen.isActive();
                            boolean keepAlive = false;
                            try {
                                java.util.concurrent.Callable<Boolean> hook = keepAlivePredicate;
                                if (hook != null) keepAlive = Boolean.TRUE.equals(hook.call());
                            } catch (Exception e) {
                                logger.warn("keepAlive predicate threw: " + e.getMessage());
                            }
                            // Keep pipeline running if ANY consumer still needs the camera/encoder:
                            //   - SURVEILLANCE: motion-triggered recording
                            //   - NORMAL_RECORDING: continuous / drive-mode recording
                            //   - active recorder: event recording in flight (proximity, manual)
                            //   - pending deferred recording: startRecording() before encoder ready
                            //   - keepAlive hook: PROXIMITY_GUARD MONITORING (radar armed, no
                            //     recording yet) — without this the pipeline would tear
                            //     down between trigger windows and the next event would
                            //     silently no-op against a null recorder.
                            // FIX (audit R1): use pendingRecordingPrefix, not
                            // pendingRecordingDir. startRecording(null, "cam") is
                            // the default-dir CONTINUOUS path — it sets prefix
                            // but leaves dir null, so the old guard always
                            // evaluated false and the WS-idle teardown would
                            // tear the pipeline down out from under a deferred
                            // recording that hadn't yet landed.
                            boolean pendingRec = pendingRecordingPrefix != null;
                            // OEM-PARITY: dilink4 keeps the pipeline alive
                            // unconditionally — oem's PanoCameraRecord is
                            // started at boot and never stopped on stream-
                            // client idle. The auto-stop here is a legacy
                            // resource-saving optimisation that breaks the
                            // "always-on camera for parked preview" model.
                            boolean dilink4Persistent = false;
                            try {
                                dilink4Persistent = com.overdrive.app.daemon.CameraDaemon
                                    .isDilink4ModeActiveStatic();
                            } catch (Throwable ignored) {}
                            if (dilink4Persistent) {
                                logger.info("Pipeline kept alive (dilink4 oem-parity — never auto-stop on WS idle)");
                            } else if (mode == Mode.IDLE && !recordingActive && !pendingRec && !keepAlive && !sentryActive && running) {
                                logger.info("No recording consumers active - stopping pipeline to save resources");
                                self.stop();
                            } else {
                                logger.info("Pipeline kept alive (mode=" + mode
                                    + ", recording=" + recordingActive
                                    + ", pending=" + pendingRec
                                    + ", keepAlive=" + keepAlive
                                    + ", sentryActive=" + sentryActive + ")");
                            }
                        } catch (Exception e) {
                            logger.error("Error during idle shutdown", e);
                        }
                    }
                }, "IdleShutdown").start();
            }
        });
        
        wsStreamServer.start();
        logger.info("WebSocket server started, setting stream callback...");
        streamEncoder.setStreamCallback(wsStreamServer);

        // Force an IDR keyframe at session start so the first packet sent to
        // any WebSocket client is decodable on its own. Without this, the
        // first NAL after a fresh stream encoder is often a P-frame
        // referencing an I-frame the client never received → decoders show
        // one frame and stall until the next GOP boundary (~2 s later).
        // Field-reported as "subsequent stream sessions show single frame"
        // after the prior session's stream encoder was torn down by the
        // WS-idle path.
        streamEncoder.requestSyncFrame();

        streamingEnabled = true;
        logger.info("H.264 streaming enabled (WebSocket port 8887)");
        // Live-view stream now needs the shared camera at >= stream fps. Notify
        // RMM to floor the global camera fps (covers ALL enable callers — HTTP,
        // OEM-dashcam, view-mode switches — not just the HTTP handler that also
        // calls reconcileForExternalConsumerChange).
        fireStreamStateChanged();
    }
    
    /**
     * Disables H.264 streaming and releases stream encoder.
     */
    public void disableStreaming() {
        streamLifecycleLock.lock();
        try {
            disableStreamingLocked();
        } finally {
            streamLifecycleLock.unlock();
        }
    }

    /**
     * Disable the current stream only when the exact server that fired the
     * idle callback is still current and still has no clients. A new /ws
     * registration is serialized by the same lock, so it either wins before
     * this check or is rejected after this teardown, never half-registering on
     * a retired encoder.
     */
    private boolean disableStreamingIfIdle(
            com.overdrive.app.streaming.WebSocketStreamServer idleServer) {
        streamLifecycleLock.lock();
        try {
            if (!streamingEnabled || wsStreamServer != idleServer
                    || idleServer == null || idleServer.hasActiveClients()) {
                return false;
            }
            disableStreamingLocked();
            return true;
        } finally {
            streamLifecycleLock.unlock();
        }
    }

    private void disableStreamingLocked() {
        // Held under streamLifecycleLock to prevent two concurrent
        // disable callers (idle-shutdown thread vs HTTP DELETE /stream
        // vs stop()) from both passing the gate and double-releasing
        // scaler/encoder. ReentrantLock semantics keep nesting safe.
        if (!streamingEnabled) {
            return;
        }

        logger.info("Disabling H.264 streaming...");
        streamingEnabled = false;
        // Reset the external-source flag here so a future re-enableStreaming
        // followed by view 0..4 doesn't trip the SPS/PPS-resend storm path
        // in reattachOwnStreamCallback (the flag was added precisely to
        // avoid that storm; leaving it stale survives the disable cycle).
        externalStreamSourceActive = false;
        
        // CRITICAL: Clear streaming components from camera FIRST
        // This prevents render loop from using released surfaces
        if (camera != null) {
            camera.clearStreamingComponents();
        }
        
        // Clear stream callback
        if (streamEncoder != null) {
            streamEncoder.clearStreamCallback();
        }
        
        // Stop WebSocket server
        if (wsStreamServer != null) {
            wsStreamServer.shutdown();
            wsStreamServer = null;
        }

        // R8-A #4 ORDERING: null streamScaler/streamEncoder fields FIRST
        // so a concurrent attachExternalStreamCallbackLocked observes
        // streamScaler == null (post-CAS) and refuses to install the OEM
        // publish ref. Pre-fix, we cleared the OEM publish ref BEFORE
        // nulling the field — that left a TOCTOU window where attach
        // could pass `streamScaler != liveScaler` (still equal!) and
        // re-install the publish ref into the about-to-be-released
        // scaler. Field-null first, publish-clear second, then the GL
        // Runnable does belt-and-braces re-clear inside the GL thread.
        final com.overdrive.app.streaming.GpuStreamScaler scalerRef = streamScaler;
        final HardwareEventRecorderGpu encoderRef = streamEncoder;
        streamScaler = null;        // field-null visible to render loop + attach NOW
        streamEncoder = null;
        // Publish the retirement placeholder SYNCHRONOUSLY at detach (audit
        // follow-up 2): the release itself is dispatched from inside the
        // GL-posted runnable below, which can outrun the 1000ms latch wait —
        // publication from in there left stop()'s pre-camera-close guard
        // reading null and bypassing the verification entirely. The runnable
        // now only COMPLETES this placeholder; a runnable that never runs
        // leaves it pending, which verification correctly reads as wedged.
        final java.util.concurrent.CompletableFuture<Boolean> streamRetireVerdict =
            (encoderRef != null) ? publishRetiringStreamEncoderRelease() : null;

        // OEM publish ref clear runs AFTER the field-null so any concurrent
        // attach that captured a non-null scaler reference observes the
        // streamScaler==null on its re-check and unbinds. Done on the
        // caller thread because OEM's render loop reads the volatile
        // reference; once it sees null, no more publishOemTexMatrix calls
        // touch our scaler.
        try {
            com.overdrive.app.camera.OemDashcamPipeline oem =
                com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
            if (oem != null) oem.setStreamScalerForOemPublish(null);
        } catch (Throwable ignored) {}

        // Stream encoder + scaler MUST be torn down ON the GL thread, in
        // order: scaler.unbindOemSource → scaler.release → encoder.release.
        // Reasoning: pano's drawFrame on the GL thread reads streamScaler
        // and pumps frames into streamEncoder.getInputSurface(). If we
        // released the encoder on the HTTP worker first (the pre-fix
        // sequence), an in-flight scaler.drawFrame would race against
        // inputSurface.release() — Adreno would either silently swallow
        // the swap or crash on EGL_BAD_NATIVE_WINDOW. Folding both into a
        // single posted Runnable serializes them between two render
        // iterations.
        android.os.Handler glHandler = (camera != null) ? camera.getGlHandler() : null;
        // GL-bound teardown — scaler.unbindOemSource + scaler.release ONLY.
        // encoder.release is offloaded to the dedicated stream-encoder
        // executor below so a 3s waitForFinalizers can't pin pano's GL
        // thread (and therefore frame production) post-disable.
        if (scalerRef != null && glHandler != null) {
            final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
            boolean posted = glHandler.post(() -> {
                try {
                    // Belt-and-braces: a racy attach that landed between
                    // pre-post null and this Runnable's run could have
                    // re-installed the OEM publish ref. Clear it here so
                    // the OEM render loop can't keep writing into the
                    // about-to-be-released scaler.
                    try {
                        com.overdrive.app.camera.OemDashcamPipeline oem2 =
                            com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
                        if (oem2 != null) oem2.setStreamScalerForOemPublish(null);
                    } catch (Throwable ignored) {}
                    try { scalerRef.unbindOemSource(); } catch (Throwable ignored) {}
                    try { scalerRef.release(); } catch (Throwable t) {
                        logger.warn("scaler release on GL thread: " + t.getMessage());
                    }
                    // CRITICAL ORDERING: encoder.release MUST happen AFTER
                    // scaler.release. The scaler's encoderSurface wraps
                    // encoder.getInputSurface(); destroying the BufferQueue
                    // (encoder.release → inputSurface.release) before
                    // eglDestroySurface on the EGLWindowSurface that
                    // wrapped it crashes Adreno with EGL_BAD_NATIVE_WINDOW.
                    // Dispatch the encoder release HERE (inside the GL
                    // Runnable's finally, after scaler.release returned)
                    // so order is preserved without pinning the GL thread
                    // for the 3s waitForFinalizers.
                    if (encoderRef != null) {
                        submitEncoderRelease(encoderRef, streamRetireVerdict);
                    }
                } finally {
                    latch.countDown();
                }
            });
            if (!posted) {
                logger.warn("scaler release: GL handler post() rejected; falling back");
                try { scalerRef.unbindOemSource(); } catch (Throwable ignored) {}
                try { scalerRef.release(); } catch (Throwable ignored) {}
                if (encoderRef != null) submitEncoderRelease(encoderRef, streamRetireVerdict);
            } else {
                try {
                    // 1000ms ceiling: scaler.release does eglCore.makeCurrent +
                    // glDeleteProgram + destroySurface + makeNothingCurrent and
                    // queues encoder.release on the offload exec — all of which
                    // need to complete on the GL thread before the next
                    // enableStreaming's init Runnable runs, otherwise the new
                    // init lands queued behind the old release and the user
                    // sees a black flash on rapid disable→enable. 200ms was
                    // shorter than worst-case GL frame stalls observed in V19
                    // stage timings (~207ms outlier).
                    if (!latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        logger.warn("scaler release on GL thread did not complete within 1000ms");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        } else if (scalerRef != null) {
            try { scalerRef.unbindOemSource(); } catch (Throwable ignored) {}
            try { scalerRef.release(); } catch (Throwable ignored) {}
            if (encoderRef != null) submitEncoderRelease(encoderRef, streamRetireVerdict);
        } else if (encoderRef != null) {
            // Scaler-less path (initialization aborted before scaler).
            submitEncoderRelease(encoderRef, streamRetireVerdict);
        }

        logger.info("H.264 streaming disabled");

        // The live-view stream just went away. If it was the only reason the
        // global camera fps was held up (e.g. above the BS idle rate), the camera
        // can now drop back. Notify RMM to re-reconcile the profile. This fires on
        // EVERY disable path — HTTP DELETE /stream AND the WS idle-shutdown
        // auto-close — so the fps doesn't get stranded at the stream rate when the
        // HTTP handler isn't the one that closed it. Best-effort, off-thread-safe
        // (reconcile self-serializes on its own lock).
        fireStreamStateChanged();
    }

    /** Notify the registered stream-state listener (RMM camera-profile reconcile)
     *  that streaming was enabled/disabled, so the global camera fps floor is
     *  recomputed. Fires from ALL stream enable/disable paths. Never throws into
     *  the caller. */
    private void fireStreamStateChanged() {
        Runnable l = streamStateListener;
        if (l != null) {
            try {
                l.run();
            } catch (Throwable t) {
                logger.warn("streamStateListener failed: " + t.getMessage());
            }
        }
    }

    // ── Dedicated blind-spot lane (views 7/8) ────────────────────────────────

    /**
     * Whether the blind-spot lane is GENUINELY armed — i.e. the {@code
     * blindSpotEnabled} flag is set AND the SurfaceControl layer it represents is
     * actually live ({@code bsLayer != null && isCreated()}).
     *
     * BLIND_SPOT_004 (false-success): the bare {@code blindSpotEnabled} flag can
     * transiently lag the layer state — e.g. a SurfaceControl handle lost on a
     * pano teardown/race can leave the flag {@code true} with a dead/null
     * {@code bsLayer}. This method is what {@code handleBsStatus} reports as
     * {@code enabled} and what {@code handleBsView}'s "lane armed?" gate consults.
     * If it returned the bare flag, the daemon would tell the overlay the lane is
     * up while no live SurfaceControl layer exists: the overlay commits the view,
     * STOPS re-driving the warm loop, and its WsH264Client reconnect-storms a dead
     * port forever (the observed NO-VIDEO flap). Gating on the LIVE layer — the
     * same liveness predicate enableBlindSpot()'s idempotent fast-path uses — makes
     * status truthful, so the overlay keeps re-POSTing /api/bs/enable until the
     * lane genuinely arms. Convergent: no false success, no dead-port loop.
     */
    public boolean isBlindSpotEnabled() {
        com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
        return blindSpotEnabled && layer != null && layer.isCreated();
    }
    public int getBlindSpotViewMode() { return bsViewMode; }
    /** Whether the BS SurfaceControl layer is currently SHOWN on screen (turn
     *  signal active / debug preview) and thus PASS 1C is drawing. Distinct from
     *  isBlindSpotEnabled() (lane armed but possibly hidden). Drives the BS
     *  idle↔active global-fps ramp in RecordingModeManager. */
    public boolean isBlindSpotLayerVisible() { return bsLayerVisible; }

    /**
     * True when the BLIND-SPOT card specifically is on screen — the signal the floating
     * ✕ ({@link com.overdrive.app.overlay.StatusOverlayService}) attaches to.
     *
     * <p>Requires {@code laneProgram == PROG_BS}, NOT just {@code bsLayerVisible}: the
     * layer is SHARED with the camera-view program, and camViewTick sets bsLayerVisible
     * too. Keying the BS ✕ on the bare visibility flag would attach it over a CAMERA VIEW
     * — on top of camview's own ✕ at the same corner, two buttons stacked, the upper one
     * POSTing the wrong hide. The program check makes the two mutually exclusive by
     * construction.
     *
     * <p>Deliberately NOT gated on the head-unit target: the caller decides that (the
     * app can't overlay the cluster display), and the poll reconcile needs the raw
     * "is it showing" fact paired with {@link #getBsTargetString()} to make that call —
     * exactly how camViewActive/camViewTarget are reported.
     */
    public boolean isBlindSpotCardShowing() {
        return blindSpotEnabled && bsLayerVisible && laneProgram == PROG_BS;
    }

    /**
     * True while the shared lane's program labels are INCOHERENT: the layer is
     * visible but {@code laneProgram} is {@link #PROG_NONE} — an inherited live
     * lane between {@code enableCamView} and the arbiter's transition tick
     * (≤250ms), where the on-screen content may be the previous program's (a
     * blind-spot card, or an earlier camera view) and this class cannot attribute
     * it ({@link #isBlindSpotCardShowing}'s javadoc explains why bare visibility
     * cannot distinguish the programs).
     *
     * <p>Surfaced through /status so the overlay's poll can SKIP adopting the
     * ✕-selection flags from a response built in this window. The overlay's
     * edge-settle gate alone cannot cover it: the show-edge broadcast rides a
     * DETACHED {@code am} exec, so a transitional poll can beat the edge's
     * timestamp stamp, clear the blind-spot flag, and the late edge (which only
     * carries camview state) never restores it — the wrong ✕ then stood until
     * the next poll (3s, 30s idle-throttled). An honest "labels unreliable"
     * signal closes that race independent of broadcast timing.
     *
     * <p>Single-label read. When PAIRING this with {@link #isBlindSpotCardShowing}
     * (the /status emission), use {@link #getCloseLabels()} — two separate calls
     * can straddle an arbiter program change and report false/false with the card
     * still visible, defeating the very gate the pair exists to feed.
     */
    public boolean isLaneProgramTransitioning() {
        return bsLayerVisible && laneProgram == PROG_NONE;
    }

    /** The ✕-selection labels as one CONSISTENT pair — see {@link #getCloseLabels()}. */
    public static final class CloseLabels {
        public final boolean bsCardShowing;
        public final boolean laneTransitioning;
        CloseLabels(boolean bsCardShowing, boolean laneTransitioning) {
            this.bsCardShowing = bsCardShowing;
            this.laneTransitioning = laneTransitioning;
        }
    }

    /**
     * Snapshot BOTH ✕-selection labels from ONE {@code laneProgram} read.
     *
     * <p>Volatile reads are individually visible but not jointly atomic: computing
     * {@code bsCardShowing} and {@code laneTransitioning} through two separate
     * getter calls lets the arbiter change {@code laneProgram} in between —
     * bsCardShowing evaluated during PROG_NONE (false), laneTransitioning after
     * the PROG_BS restore (false) — and the false/false pair told the overlay the
     * labels were COHERENT while the blind-spot card was still visible, so it
     * adopted the wrong camera ✕. Deriving both from the same program value makes
     * that pair impossible by construction: with the layer visible, PROG_NONE ⇒
     * transitioning=true (skip), PROG_BS ⇒ bsCardShowing=true (blind-spot ✕),
     * PROG_CAMVIEW ⇒ false/false is genuinely correct (camview content, camera ✕).
     *
     * <p>The WHOLE TUPLE is read twice (seqlock-style double read): a single
     * pre-read of the program alone could pair an OLD program with a NEW
     * visibility — during the idle-BS → camview yield the tick writes
     * laneProgram=PROG_CAMVIEW then bsLayerVisible=true, so a snapshot straddling
     * it combined stale PROG_BS with fresh visible=true and reported the
     * blind-spot ✕ over camview content (a false POSITIVE the transitioning flag
     * did not cover). And a program-only bracket is NOT preemption-safe: the HTTP
     * thread can be descheduled across multiple 250ms ticks, so BS → CAMVIEW → BS
     * passes an equal program check while the companion reads came from the
     * camview era. Requiring the FULL tuple to match across both passes catches
     * any single-field change mid-snapshot; a full-tuple ABA (returning to the
     * identical tuple) computes a label correct for the end-of-snapshot state.
     *
     * <p>ACCEPTED RESIDUAL (audit 2026-08, rated Low): an unversioned double read
     * is still not a true snapshot — IDENTICALLY torn passes can pass equality.
     * Concretely: repeated BS-idle → camview → BS-idle → camview flips could
     * yield (BS, true, true) on both passes, with each read landing in a
     * different era. Reaching it needs THREE program transitions (arbiter ticks
     * are 250ms apart, so ≥500ms of flapping) interleaved into one six-read
     * snapshot, with the reader descheduled at exactly the right field
     * boundaries each time, coinciding with a /status poll; the consequence is
     * one wrong ✕ for ≤1 poll cycle, self-correcting, and the mis-tap is
     * recoverable (a BS dismiss re-shows on the next signal; a camview hide is
     * re-showable). The airtight fix is a real seqlock — paired odd/even version
     * bumps around EVERY mutation of these three fields (~15 scattered writer
     * sites across the arbiter/enable/disable/takeover/teardown paths, several
     * of which run WITHOUT bsLifecycleLock, e.g. bsTurnTick's PROG_BS restore) —
     * where one missed or misordered bump silently voids the guarantee. A
     * single-bump version counter is NOT airtight (bump-after-write misses a
     * reader finishing before the bump; bump-before-write has the mirror hole),
     * so the only honest upgrades are the full seqlock or funneling all writes
     * through locked mutators. Both were judged regression-riskier than this
     * residual; escalate only if a wrong ✕ is actually observed in the field.
     */
    public CloseLabels getCloseLabels() {
        final int p1 = laneProgram;
        final boolean v1 = bsLayerVisible;
        final boolean e1 = blindSpotEnabled;
        final int p2 = laneProgram;
        final boolean v2 = bsLayerVisible;
        final boolean e2 = blindSpotEnabled;
        if (p1 != p2 || v1 != v2 || e1 != e2) {
            // The lane state moved mid-snapshot: the reads cannot be attributed
            // to a single instant. Honest incoherence.
            return new CloseLabels(false, true);
        }
        return new CloseLabels(
                e1 && v1 && p1 == PROG_BS,
                v1 && p1 == PROG_NONE);
    }

    /**
     * The lane's current on-screen dest rect {x,y,w,h} — whichever program owns it.
     *
     * <p>The floating ✕ needs this because the card renders on a SurfaceControl layer at
     * {@code Integer.MAX_VALUE - 1}, far ABOVE any app window, so a ✕ overlapping the card
     * is composited UNDER it and invisible. A fixed top-right ✕ sat fully inside the
     * default blind-spot rect (corner "tr", 24px inset) — which is why the blind-spot close
     * button could not be seen at all. The camera view hid the same bug only because its
     * default corner is "center". The caller offsets the button clear of this rect.
     *
     * <p>Returns a COPY (the field is swapped wholesale by the resolvers, and callers must
     * not alias it). Null when no lane geometry is resolved yet.
     */
    public int[] getLaneGeomRect() {
        // When a camera view holds an UNMASKED claim on the lane, report the CAMVIEW
        // rect: between enableCamView and the arbiter's transition tick (≤250ms)
        // bsGeomRect still holds the PREVIOUS program's geometry, and a /status
        // response built in that window fed the overlay a stale rect that passed
        // its fetched-after-the-edge freshness gate — the daemon itself was the
        // stale source, so the gate could not help. camViewGeomRect is resolved
        // BEFORE camViewActive publishes, and the transition tick copies this same
        // rect into bsGeomRect, so the two sources converge once configured.
        //
        // The claim during PROG_NONE requires blind-spot DISABLED. PROG_NONE alone
        // does NOT mean camview owns the visible lane: enableCamView sets PROG_NONE
        // while a blind-spot card may still be SHOWING (the PROG_BS restore in
        // bsTurnTick documents that state), and on that tick BS usually re-asserts
        // priority — reporting the camview rect there would move the VISIBLE
        // blind-spot ✕ to the camera position. With BS enabled we therefore keep
        // reporting bsGeomRect until the tick actually hands camview the program
        // (PROG_CAMVIEW). The camview-under-idle-BS pre-transition window (≤250ms)
        // is covered by the show-edge broadcast + the overlay's post-edge settle.
        boolean camViewClaim = camViewActive
                && (laneProgram == PROG_CAMVIEW
                    || (laneProgram == PROG_NONE && !blindSpotEnabled));
        // CLUSTER rects are in the cluster panel's space — meaningless to the
        // head-unit ✕. Gate on the CLAIM-appropriate target: camViewTarget for a
        // camview claim (bsTarget can lag it in the transition window — a
        // cluster→head-unit camview show would otherwise be suppressed by the
        // stale bsTarget, feeding the overlay a null that clears the fresh edge
        // rect), bsTarget otherwise. Callers need no cluster gate of their own.
        if (camViewClaim ? isCamViewClusterTarget() : "cluster".equals(bsTarget)) {
            return null;
        }
        int[] g = camViewClaim ? camViewGeomRect : bsGeomRect;
        if (g == null || g.length != 4) return null;
        // bsGeomRect starts life as the {-1,-1,-1,-1} SENTINEL (never-resolved), and a
        // degenerate w/h can also appear mid-resolve. Publishing that as a real rect made
        // the overlay place its ✕ against a card at x=-1,w=-1 — which lands it in the
        // wrong corner entirely. Report "unknown" instead so the caller keeps its fixed
        // inset until real geometry exists.
        if (g[2] <= 0 || g[3] <= 0) return null;
        return new int[]{g[0], g[1], g[2], g[3]};
    }

    /**
     * Dismiss the CURRENTLY-SHOWING blind-spot card (the floating ✕ tap).
     *
     * <p>Hides the card for THIS display session only and leaves the feature armed:
     * {@code blindspot.enabled} is untouched, the lane stays warm, and the next turn
     * signal shows the card again (the latch self-clears when the signal session ends —
     * see {@link #bsUserDismissed}). A blind-spot dismiss must never be sticky.
     *
     * <p>Two cases:
     * <ul>
     *   <li><b>Turn-triggered card</b> — set the latch; the turn tick takes its hide
     *       branch on the next pass (and skips re-showing while the stalk is still held).
     *   <li><b>Calibration preview</b> — {@code debugPreview} is a PERSISTED intent that
     *       the tick re-asserts every 250ms, so a latch alone would fight it forever.
     *       Clear the flag instead, which is what "Hide preview" in the settings UI does.
     * </ul>
     *
     * <p>Returns true if a card was actually showing and is now dismissed. Idempotent:
     * a tap with nothing on screen is a no-op false (so the caller can restore its ✕
     * rather than leave a view stranded).
     */
    public boolean dismissBlindSpotCard() {
        if (!isBlindSpotCardShowing()) return false;
        // Read the persisted preview intent BEFORE taking the lane lock: forceReload is a
        // full-file parse and getBlindSpot may block on the cross-process config lock, and
        // holding bsLifecycleLock across that would stall the 250ms tick / projThread.
        boolean preview = false;
        try {
            // forceReload: the flag is written by the web UI in the APP uid, so the
            // daemon's cached snapshot can be stale here.
            preview = com.overdrive.app.config.UnifiedConfigManager.forceReload()
                    .optJSONObject("blindspot") != null
                && com.overdrive.app.config.UnifiedConfigManager.getBlindSpot()
                    .optBoolean("debugPreview", false);
        } catch (Throwable t) {
            logger.debug("dismissBlindSpotCard: preview flag read failed: " + t.getMessage());
        }
        if (preview) {
            // Clear the persisted preview intent. The tick's debugPreview branch returns
            // before ever consulting bsUserDismissed, so the latch alone would fight it
            // every 250ms and leave the card up with the ✕ gone.
            // Single-key merge — never clobbers the calibration siblings (debugView, the
            // fov/yaw/roll tuning values) that live in the same section.
            try {
                com.overdrive.app.config.UnifiedConfigManager.setBlindSpotValues(
                        java.util.Collections.singletonMap("debugPreview", (Object) false));
            } catch (Throwable t) {
                logger.warn("dismissBlindSpotCard: clearing debugPreview failed: "
                        + t.getMessage());
            }
        }
        // Commit the latch under the LANE LOCK, re-checking liveness inside it. The config
        // I/O above can block for hundreds of ms, and a disableBlindSpot() (web toggle /
        // ACC-off teardown) landing in that window clears the latch + drops the lane via
        // stopBsTurnLoop. A bare `bsUserDismissed = true` after that would write a STALE
        // true into a dead lane — and startBsTurnLoop does NOT clear it — so the first turn
        // signal of the NEXT session would silently render no card, with no ✕ on screen to
        // explain or undo it (the "never sticky" invariant, violated across sessions).
        // Taking the same lock those teardowns hold makes the check-then-set atomic against
        // them; the re-check means we only latch a lane that is still live.
        boolean latched = false;
        bsLifecycleLock.lock();
        try {
            if (isBlindSpotCardShowing()) {
                // Latch regardless of `preview`: on a preview dismiss it also suppresses a
                // turn signal live at this instant, so one tap clears the screen either way.
                bsUserDismissed = true;
                latched = true;
                if (bsLayerVisible) setBlindSpotVisible(false);
            }
        } catch (Throwable t) {
            logger.warn("dismissBlindSpotCard: hide failed: " + t.getMessage());
        } finally {
            bsLifecycleLock.unlock();
        }
        if (!latched) {
            // The card went away under us (auto-hide, teardown, camview takeover). Nothing
            // to dismiss and nothing latched — report false so the ✕ owner reconciles.
            logger.info("BS: dismiss raced a teardown/auto-hide — nothing to dismiss");
            return false;
        }
        // Cluster gauge restore, OUTSIDE the lane lock (requestCloseLingered can call back
        // in via notifyPipelineClosed → onClusterProjectionClosed, which takes this lock).
        releaseClusterProjectionForDismiss();
        logger.info("BS: card dismissed by user (preview=" + preview
                + ") — re-shows on the next turn signal");
        return true;
    }

    /**
     * Release the TRANSIENT cluster projection a dismissed BS session had opened, so the
     * driver's gauges come back — mirroring the turn tick's hide branch. Without it, a
     * dismissed cluster card would leave the gauges blanked until the linger/max-cap
     * expired. MUST be called WITHOUT bsLifecycleLock: requestCloseLingered can call back
     * into the pipeline (notifyPipelineClosed → onClusterProjectionClosed, which takes
     * that same lock), so keeping it out avoids holding the lane lock across the
     * controller's own monitor. No-op for a head-unit session.
     */
    private void releaseClusterProjectionForDismiss() {
        if (!isClusterTarget()) return;
        try {
            com.overdrive.app.surveillance.ClusterProjectionController.getInstance()
                    .requestCloseLingered();
        } catch (Throwable ignored) {}
    }

    /** Current on-screen BS layer rect [x,y,w,h] (panel px); -1s if unresolved. */
    public int[] getBsGeometry() { int[] r = bsGeomRect; return new int[]{r[0], r[1], r[2], r[3]}; }

    /**
     * Thrown by {@link #enableBlindSpot(int)} when the BS lane cannot arm yet
     * because the pano pipeline isn't running. This is a TRANSIENT condition
     * (the API layer is cold-starting pano on a worker thread and the overlay
     * re-polls), NOT a hard failure — but it MUST surface as a failure to the
     * caller rather than a silent {@code void} return.
     *
     * BLIND_SPOT_004: handleBsEnable() reports {success:true,wsPort:8889}
     * immediately after enableBlindSpot() returns. Pre-fix, the "pano not
     * running yet" branch returned void, so the daemon told the overlay the
     * lane was up while blindSpotEnabled stayed false. The overlay then
     * committed the view (handleBsView also reported success), stopped
     * re-driving the warm, and its WsH264Client reconnect-stormed a port 8889
     * that was never opened. Making this a checked throw routes it into
     * handleBsEnable()'s {@code catch (Exception e)} → success:false, so the
     * overlay's confirm loop keeps re-posting /api/bs/enable (re-kicking the
     * async pano cold-start) until the lane is genuinely live — convergent,
     * no flap, no false success.
     */
    public static class BlindSpotNotReadyException extends Exception {
        public BlindSpotNotReadyException(String message) { super(message); }
    }

    /**
     * Switch the blind-spot lane between view 7 (Rear+Left) and 8 (Right+Rear).
     * Cheap: just flips the scaler's side sign + view mode; no encoder restart.
     * Re-applies the saved stitch calibration so the new side looks right.
     */
    public void setBlindSpotViewMode(int mode) {
        if (mode != 7 && mode != 8) return;
        bsViewMode = mode;
        // Serialize the bsScaler snapshot + use under bsLifecycleLock so a
        // concurrent disableBlindSpot() (which holds the same lock while it nulls
        // bsScaler and posts scalerRef.release()) can't release the scaler between
        // our snapshot and the setViewMode/calibration calls — that would be a
        // use-after-release. ReentrantLock makes the enableBlindSpot() caller (which
        // already holds the lock at the bsViewMode dispatch) re-entrant-safe. These
        // are CPU-side uniform setters (not GL-thread ops, same as setStreamViewMode),
        // so holding the lock briefly here can't deadlock against the GL thread.
        bsLifecycleLock.lock();
        try {
            // The SCALER is shared with the camera view, so reconfiguring it while a camera view
            // owns the lane would hijack the visible picture: an /api/bs/side POST (gated only on
            // isBlindSpotEnabled) flipped a plain camera view to the stitched blind-spot view with
            // the card's rotation, and camViewTick could never repair it because its reconfig is
            // transition-gated on `laneProgram != PROG_CAMVIEW`, which stays false for the rest of
            // the session (audit 2026-08). bsViewMode is already recorded above, so blind-spot's
            // takeback applies the side itself (it calls setViewMode + applyBlindSpotCalibration).
            if (camViewOwnsLane()) return;
            com.overdrive.app.streaming.GpuStreamScaler s = bsScaler;
            if (s != null) {
                s.setViewMode(mode);          // sets side sign internally (7→-1, 8→+1)
                applyBlindSpotCalibration(s);
            }
            // PER-SIDE POSITION: on the turn edge, jump the card to THIS camera's
            // chosen corner (cornerLeft vs cornerRight). Do NOT call resolveBsGeometry()
            // here — on the cluster target it runs clusterDisplaySize() → `dumpsys
            // display` (a subprocess) under this lock, which the turn path deliberately
            // avoids (see bsTurnTick's "no panel query, no dumpsys" note). The panel
            // size can't change on a mere side switch, so reuse the size cached by the
            // last resolveBsGeometry (bsLastPanelW/H) and only recompute the rect from
            // the new side's corner via presetRect. Falls back to a full resolve only if
            // the panel size was never cached (lane just armed → resolveBsGeometry ran).
            // Reposition ONLY when the card is in PRESET form (sizePct persisted) — the
            // exact same discriminator resolveBsGeometry uses. If the user pinned an
            // ABSOLUTE rect (/api/bs/geometry/{x}/{y}/{w}/{h}, no sizePct), leave it put:
            // recomputing a preset rect here would override their absolute placement on
            // the first turn-side flip (a regression). currentGeometryObj is a cheap
            // cached config read (no panel query).
            //
            // NOT while a CAMERA VIEW owns the shared lane. bsGeomRect then holds camview's
            // rect (placed at camViewCorner from the camview config), and recomputing it from
            // the blind-spot corner would silently move the user's chosen camera-view position
            // to the card's corner on the first turn-signal flip — the "I picked top right and
            // it went top left" half of the position bug. A real blind-spot card takes the lane
            // through its own arm path, which resolves geometry properly.
            org.json.JSONObject gNow = currentGeometryObj();
            boolean presetForm = (gNow != null && gNow.has("sizePct"));
            if (presetForm && !camViewOwnsLane()
                    && bsLastPanelW > 0 && bsLastPanelH > 0 && bsSizePct > 0) {
                bsCorner = resolveBsCorner(gNow);
                // presetRect is NOT in-bounds by construction: it derives h from w at 4:3,
                // so on the short 1920×720 cluster any sizePct above 50 overflows the panel
                // height (the UI's cluster default is 80 → 1536×1152). Clamp with the
                // pure-math helper against the cached panel — NOT clampBsRect, which calls
                // panelForTarget → clusterDisplaySize → dumpsys, the subprocess this path
                // exists to avoid. A size/panel change still comes through the full
                // resolveBsGeometry path (enable / orientation / settings).
                int[] pr = presetRect(new android.graphics.Point(bsLastPanelW, bsLastPanelH));
                if (pr != null) {
                    int[] cr = clampBsRectTo(pr[0], pr[1], pr[2], pr[3],
                                             bsLastPanelW, bsLastPanelH);
                    bsGeomRect = new int[]{cr[0], cr[1], cr[2], cr[3]};   // atomic publish
                }
            }
            com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
            if (layer != null && layer.isCreated() && bsLayerVisible) {
                int[] gr = bsGeomRect;
                if (gr[0] >= 0) layer.setGeometry(gr[0], gr[1], gr[2], gr[3]);
            }
        } finally {
            bsLifecycleLock.unlock();
        }
    }

    /** Apply the persisted 'blindspot' UCM calibration to a BS scaler, resolving
     *  per-side values against the blind-spot card's own view. */
    private void applyBlindSpotCalibration(com.overdrive.app.streaming.GpuStreamScaler s) {
        applyBlindSpotCalibration(s, bsViewMode, false);
    }

    /** As above, but resolving the PER-SIDE keys (rotation, corner) against an
     *  explicit view. A composite camera view (mode 7/8) shares this calibration but
     *  picks its side from the request, not from the card's current turn side — using
     *  bsViewMode there would give a right-side view the left camera's angle.
     *
     *  @param forCamView true when the rect being aligned to is the CAMERA VIEW's (so a rotated
     *         card pillarboxes against {@code camViewCorner} and its "center" default) rather than
     *         the blind-spot card's. The caller must state this: neither {@code viewMode !=
     *         bsViewMode} nor {@code laneProgram} can be read reliably at the call sites — see the
     *         alignX comment below. */
    private void applyBlindSpotCalibration(com.overdrive.app.streaming.GpuStreamScaler s,
                                           int viewMode, boolean forCamView) {
        try {
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            org.json.JSONObject bs = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
            if (bs != null && bs.length() > 0) {
                s.setBlindSpotParams(
                    (float) bs.optDouble("rearFov", 1.66),
                    (float) bs.optDouble("sideFov", 1.98),
                    (float) bs.optDouble("yaw",     1.23),
                    (float) bs.optDouble("roll",    0.25),
                    (float) bs.optDouble("feather", 0.38),
                    (float) bs.optDouble("projExp", 1.0), 1.0f,
                    (float) bs.optDouble("pitch",  -0.275),
                    (float) bs.optDouble("rearRoll",  0.0),
                    (float) bs.optDouble("rearPitch", 0.0));
                // Merge mode (both/side/rear) — re-applied here so it survives an
                // enable or a side switch, same lifecycle as the stitch calibration.
                s.setBlindSpotMergeMode(bsMergeModeCode(bs.optString("mergeMode", "both")));
                // Fisheye/lens dewarp for the single-camera views (side/rear). Separate
                // knob from recording.rectifyStrength; the shader applies it ONLY in the
                // merge 1/2 passthrough (identity at 0, and never touches 'both'). The
                // Match the active shared-lane buffer aspect.
                s.setBlindSpotRectifyStrength((float) bs.optInt("rectifyStrength", 0));
                s.setBlindSpotRectifyAspect((float) sharedLaneHeight / (float) BS_WIDTH);
                // On-screen card rotation is done in the GL vertex shader (output
                // geometry), NOT via the SurfaceControl layer transform — this
                // firmware's compositor drops a 90/270 layer transform → blank card
                // (issue #164). resolveBsRotation already gates rotation to the
                // single-view side/rear modes and reads AUTO/gear; re-apply it here so
                // it survives enable/side-switch, same lifecycle as the calibration.
                // PER-SIDE: pass the current view (7=left/8=right) so a side switch
                // re-resolves to that camera's own rotation. Keep bsRotationDeg in sync
                // with what we just pushed, so the 250ms turn-tick change-detector
                // (wantRot != bsRotationDeg) doesn't see a stale angle after a side
                // switch and either miss a needed re-apply or churn a redundant one.
                int rot = resolveBsRotation(bs, viewMode);
                // Only mirror into bsRotationDeg when this is the CARD's own render, not a
                // composite camera view's — a camview angle must not become the card's
                // change-detector baseline (the turn tick would then skip a needed re-apply).
                // Keyed on the caller's forCamView, not on `viewMode == bsViewMode`: the latter is
                // the inferred discriminator that broke the alignment below, and it compares equal
                // whenever a camview happens to show the card's current side. Harmless today
                // (resolveBsRotation returns the same angle for the same view), but the two must
                // not disagree about whose render this is.
                if (!forCamView) bsRotationDeg = rot;
                // Align the rotated card to the CURRENT side's corner edge. Resolve the
                // corner from this same config object (not the bsCorner field, which the
                // reposition step updates only AFTER this call) so a 90/270 card hugs the
                // right/left edge matching where it's anchored, per side.
                //
                // A COMPOSITE CAMERA VIEW is anchored by its OWN camview geometry, so a
                // rotated one must pillarbox against camViewCorner — using the card's
                // corner would flush the picture to the opposite edge of its rect.
                int alignX;
                // Which feature's corner does the rect belong to? The caller knows, so it TELLS us
                // via forCamView — do not infer it here:
                //  - `viewMode != bsViewMode` was wrong because bsViewMode defaults to 7, so a
                //    camera view showing the LEFT composite (view 7) compared equal and aligned
                //    against the blind-spot corner;
                //  - `laneProgram == PROG_CAMVIEW` is wrong too: the camview call site sits INSIDE
                //    its own `laneProgram != PROG_CAMVIEW` transition block, so the flag is not yet
                //    set when this runs, and the blind-spot takeback call site still sees
                //    PROG_CAMVIEW. Both readings are false at exactly the wrong moment.
                if (forCamView && (viewMode == 7 || viewMode == 8)) {
                    // Camera-view lane: pass ITS default ("center"), the same one
                    // camViewPresetRect uses, so rect and alignment agree on any token.
                    alignX = bsCornerAlignX(camViewCorner, "center");
                } else {
                    String geomKey = isClusterTarget() ? "geometryCluster" : "geometry";
                    org.json.JSONObject g = bs.optJSONObject(geomKey);
                    alignX = bsCornerAlignX(resolveBsCorner(g));
                }
                s.setContentRotation(rot, alignX);
            }
        } catch (Throwable t) {
            logger.warn("blindspot calib apply failed: " + t.getMessage());
        }
    }

    /**
     * Enable the dedicated blind-spot lane: a second scaler+encoder (1280×960 @
     * 15fps) locked to view {@code mode} (7/8), published to the camera render
     * loop's PASS 1C, streaming H.264 over its own WS server (port {@link #BS_WS_PORT}).
     * Independent of the live-view stream — does NOT touch streamingEnabled,
     * streamScaler, streamEncoder, or wsStreamServer. Auto-starts the pipeline
     * if needed. Idempotent.
     */
    public void enableBlindSpot(int mode) throws Exception {
        bsLifecycleLock.lock();
        try {
            if (mode == 7 || mode == 8) bsViewMode = mode;
            // Double-check locking: a concurrent enableBlindSpot() may have already
            // finished (blindSpotEnabled) or be mid-flight (bsEnabling) — its
            // internal init releases this lock around its GL-init wait, so reaching
            // here under the lock does NOT guarantee no enable is in progress.
            // Bail in either case so we never double-allocate the lane / re-bind 8889.
            //
            // Idempotent fast-path: lane already armed (layer created + scaler
            // published). Native path has no WS server to go stale, so gate on the
            // SurfaceControl layer being live.
            if (blindSpotEnabled && bsLayer != null && bsLayer.isCreated()) {
                setBlindSpotViewMode(bsViewMode);
                return;
            }
            // BLIND_SPOT_004 (orphan self-heal): blindSpotEnabled is set but the
            // SurfaceControl layer is dead/null (lost on a pano teardown/race, or a
            // partial arm that never reached a live layer). The fast-path above
            // didn't fire because the layer isn't live, but the stale flag would
            // (a) make isBlindSpotEnabled() lie → false-success masking, and
            // (b) short-circuit enableBlindSpotInternal()'s top-of-init bail
            //     (`if (blindSpotEnabled) return;`), so the lane could NEVER rebuild.
            // Tear the orphan down so this enable proceeds to a clean re-arm.
            // disableBlindSpot() is idempotent and re-acquires this re-entrant lock.
            if (blindSpotEnabled && (bsLayer == null || !bsLayer.isCreated())) {
                logger.warn("BS: stale blindSpotEnabled with dead layer — "
                    + "tearing down orphan before re-arm");
                disableBlindSpot();
            }
            if (bsEnabling) {
                logger.info("BS: enable already in flight — skipping duplicate enable");
                return;
            }
            // Symmetric to enableCamView's defer: if a CAMERA-VIEW build is in flight
            // (camViewEnabling, lock released around its GL-init wait), do NOT ride it.
            // If that camview build fails, BS adopting its half-built unpublished
            // scaler would leave blindSpotEnabled=true with a dead lane (black BS card).
            // Defer via the NotReady throw so handleBsEnable re-polls; by then the
            // camview build has resolved (published → adopt cleanly, or failed →
            // released) and this enable takes a deterministic path.
            if (camViewEnabling) {
                logger.info("BS: camera-view lane build in flight — deferring (caller re-polls)");
                throw new BlindSpotNotReadyException(
                    "blind-spot deferred — a camera-view lane build is in flight");
            }
            // Do NOT cold-start the pano pipeline from here. The BS lane is a
            // CONSUMER of an already-running pano (it fans a 2nd scaler+encoder off
            // pano's camera texture). Starting pano here — while the daemon is
            // booting and the overlay POSTs /api/bs/enable every 250ms — raced a
            // 2nd encoder creation against pano's own encoder init and crashed the
            // daemon at startup ("recursive attempt to load libmedia_jni.so").
            // handleBsEnable() owns cold-start via ensurePanoStartedNonBlocking();
            // we just defer until pano is genuinely up, and the overlay re-polls.
            //
            // BLIND_SPOT_004: this MUST throw, not return void. A void return is
            // indistinguishable from success to handleBsEnable() (it only catches
            // exceptions), so the daemon would report {success:true,wsPort:8889}
            // while blindSpotEnabled stays false — the overlay then commits the
            // view, stops re-warming, and its WsH264Client reconnect-storms a
            // port 8889 that was never opened. Throwing routes this into
            // handleBsEnable()'s catch → success:false, so the overlay's confirm
            // loop keeps re-posting /api/bs/enable (re-kicking pano cold-start)
            // until the lane is live. Convergent: no flap, no false success.
            if (!running || camera == null || camera.getGlHandler() == null) {
                logger.warn("BS: pano not running yet — enable deferred (caller must re-poll)");
                throw new BlindSpotNotReadyException(
                    "blind-spot lane cannot arm — pano pipeline not running yet");
            }
            bsEnabling = true;
            try {
                enableBlindSpotInternal();
            } finally {
                bsEnabling = false;
            }
            // Gate success on the lane ACTUALLY being armed. enableBlindSpotInternal()
            // sets blindSpotEnabled=true only on full success (encoder+scaler+WS 8889
            // up); its concurrent-already-enabled bail also leaves blindSpotEnabled
            // true. So if it's still false here, the lane did NOT come up — surface
            // that as a failure rather than letting handleBsEnable() report
            // success:true against a dead 8889 (BLIND_SPOT_004 again, from the
            // internal-init side). The overlay re-polls until it's genuinely live.
            if (!blindSpotEnabled) {
                logger.warn("BS: enableBlindSpotInternal returned but lane not armed "
                    + "(blindSpotEnabled=false) — reporting failure so caller re-polls");
                throw new BlindSpotNotReadyException(
                    "blind-spot lane not running after enable attempt");
            }
        } finally {
            bsLifecycleLock.unlock();
        }
    }

    private void enableBlindSpotInternal() throws Exception {
        logger.info(String.format("BS: enabling NATIVE blind-spot lane %dx%d, view=%d",
            BS_WIDTH, sharedLaneHeight, bsViewMode));

        // Build (or reuse) the single shared native lane. buildSharedLaneLocked returns
        // false when the lane already exists — which now has TWO causes:
        //   (a) a concurrent enableBlindSpot already armed BS (blindSpotEnabled==true), OR
        //   (b) the CAMERA-VIEW program built the lane first (blindSpotEnabled==false).
        // Only (a) is a genuine duplicate-init to bail on. In case (b) we MUST still
        // ADOPT the lane for blind-spot — configure the BS program + set blindSpotEnabled
        // — otherwise BS could never arm while a camera-view holds the lane, silently
        // defeating blind-spot PRIORITY. So bail ONLY when BS is already enabled; a
        // false return with blindSpotEnabled==false means "lane reused, proceed".
        buildSharedLaneLocked();
        if (blindSpotEnabled) {
            logger.info("BS: already enabled by concurrent call — skipping duplicate init");
            return;
        }
        if (bsScaler == null || bsLayer == null || !bsLayer.isCreated()) {
            // Defensive: build neither created nor found a live lane (should have thrown).
            throw new IllegalStateException("BS: shared lane not live after build");
        }

        // Lock the scaler to the blind-spot view + apply calibration.
        bsScaler.setViewMode(bsViewMode);
        applyBlindSpotCalibration(bsScaler);

        // Resolve on-screen geometry (config or default) and position the layer.
        // It stays HIDDEN until the turn-trigger / debug-preview shows it.
        // BS-ENABLE-004: position WITHOUT showing (single hidden-arm transaction)
        // to avoid a show-then-hide one-frame flash of an unrendered SC layer.
        resolveBsGeometry();
        if (bsLayer != null) {
            int[] g0 = bsGeomRect;
            // bsLayerVisible can be true here when BS ADOPTS a lane a camera-view was
            // already displaying (buildSharedLaneLocked reused it). setGeometry SHOWS
            // the layer, so honour the conditional gate before inheriting that visible
            // state — otherwise a car parked outside the speed window would paint the BS
            // card for one poll interval.
            // holding=FALSE: the visible thing on screen is a CAMVIEW, not a BS card the
            // driver was granted, so this is a decision to START a BS display and must
            // use the narrow turn-ON threshold. Passing true would lend the adopt the
            // hysteresis slack and paint the BS card just below the configured minimum.
            boolean showNow = bsLayerVisible && bsGateBlockReason(
                com.overdrive.app.config.UnifiedConfigManager.getBlindSpot(), false).isEmpty();
            if (showNow) {
                bsLayer.setGeometry(g0[0], g0[1], g0[2], g0[3]);
            } else {
                bsLayer.setGeometryHidden(g0[0], g0[1], g0[2], g0[3]);
                // Keep the intent flag consistent with the layer we just hid, so the
                // turn tick's hide branch isn't waiting on a debounce to correct a
                // desync (and the fps ramp drops to idle immediately).
                if (bsLayerVisible) setBlindSpotVisible(false);
            }
        }

        // Blind-spot PRIORITY: if a camera-view was using the lane, BS now takes full
        // ownership. Release camview's sustained "camview" cluster token here (BS
        // manages the projection via its own transient path) and clear camViewActive —
        // otherwise, because we set laneProgram=PROG_BS directly below, the arbiter's
        // laneProgram!=PROG_BS releaseSustained("camview") block never runs and the
        // "camview" token would orphan (projection pinned, gauges blanked, max-cap
        // disarmed) while camview sits masked behind BS priority. Idempotent no-ops
        // when camview was never active. camViewActive=false means a later
        // disableBlindSpot won't wrongly "retain the lane for camview"; the user
        // re-issues /api/camview/show if they still want it after BS turns off.
        if (camViewActive) {
            try { com.overdrive.app.surveillance.ClusterProjectionController.getInstance().releaseSustained("camview"); } catch (Throwable ignored) {}
            // Route through the SHARED session-ending contract (audit finding: this
            // path used to clear camViewActive directly, leaving the owner token,
            // session id, fail-open override and any pending deferred arm alive —
            // a stale enable or surviving arm-retry could re-arm the stolen view,
            // and the orphaned owner made the next automation hide a false
            // not-owner no-op). endCamViewSessionLocked invalidates all of it;
            // the lane itself is deliberately NOT torn down — BS owns it now.
            endCamViewSessionLocked();
            logger.info("BS: took lane ownership from camera-view (priority)");
            // The camera view is gone (BS stole the lane) but this path does NOT go
            // through disableCamView(), so tell the app-side ✕ overlay it's closed —
            // otherwise the floating close button would sit orphaned over the blind-spot
            // view. emitCamViewState only spawns a detached `am broadcast` (no lock, no
            // block), so it's safe to call inline here under bsLifecycleLock.
            emitCamViewState(false, null);
            // Clear the persisted request too. Keep the config write off the lane lock.
            Thread cvOff = new Thread(() -> {
                try {
                    java.util.Map<String, Object> off = new java.util.HashMap<>();
                    off.put("enabled", false);
                    com.overdrive.app.config.UnifiedConfigManager.setCamViewValues(off);
                } catch (Throwable ignored) {}
            }, "CamViewFlagClear");
            cvOff.setDaemon(true);
            cvOff.start();
        }

        blindSpotEnabled = true;
        // The lane is now configured for the blind-spot program (view 7/8 + calib).
        laneProgram = PROG_BS;
        startBsTurnLoop();   // daemon-side show/hide + side-switch (no app process)
        logger.info("BS: NATIVE blind-spot lane enabled (SurfaceControl layer)");
    }

    /**
     * Build the single shared native on-screen lane (SurfaceControl layer + scaler
     * + EGLSurface wrapping the layer's Surface + PASS-1C publish) IF it is not
     * already up. Idempotent and shared by BOTH the blind-spot and camera-view
     * programs (Option A: one lane, two programs). Extracted verbatim from the
     * former enableBlindSpotInternal body so the shipping blind-spot path is
     * byte-identical — the ONLY change is that program-specific config (view mode,
     * calibration, geometry) moved to the callers, and the "already built" bail is
     * generalized from `blindSpotEnabled` to "the lane's SurfaceControl layer is
     * live" so whichever program armed first is respected.
     *
     * <p>MUST be called holding {@link #bsLifecycleLock}. Releases the lock only
     * around the GL-init wait (restored before returning), exactly as before.
     *
     * @return true if the lane is now built and this caller should proceed to
     *         configure its program; false if a concurrent call already built it
     *         (the caller should bail its init — the lane is shared, not rebuilt).
     * @throws Exception on a genuine build failure (layer create / GL init).
     */
    private boolean buildSharedLaneLocked() throws Exception {
        // Fast path: the lane already exists (built by the other program or a prior
        // enable). Reuse it — never allocate a second scaler/layer/EGLSurface.
        if (bsLayer != null && bsLayer.isCreated() && bsScaler != null) {
            return false;
        }

        // Own SurfaceControl layer (GPU → screen, no encoder/WS/decoder).
        bsLayer = new com.overdrive.app.surveillance.BsNativeLayer(
            BS_WIDTH, sharedLaneHeight);
        if (!bsLayer.create()) {
            bsLayer = null;
            throw new RuntimeException("BS: SurfaceControl layer create failed");
        }

        // Own scaler — same per-role offsets as the live stream so the stitch
        // matches the recorder's camera arrangement.
        com.overdrive.app.camera.ResolvedCameraConfig cfg =
            com.overdrive.app.camera.CameraConfigResolver.resolve(getVehicleModel());
        bsScaler = new com.overdrive.app.streaming.GpuStreamScaler(
            BS_WIDTH, sharedLaneHeight, cfg.getQuadrantStripOffsetX());

        // BS-LIFECYCLE-1: from here on, bsScaler+bsLayer are assigned to the
        // instance fields and a GL EGLWindowSurface gets created wrapping the SC
        // layer Surface. Any failure/race below (GL-init timeout/throw, or a
        // concurrent stop() flipping running=false during the lock-released wait)
        // must NOT leak them — disableBlindSpot returns early on !blindSpotEnabled
        // and a subsequent enable overwrites the fields, orphaning the old layer +
        // dangling EGLSurface. Wrap the rest in try/catch → releasePartialBsLane.
        try {
        // libod host-authorization (same context fallback as the stream lane).
        try {
            android.content.Context odCtx = savedContext;
            if (odCtx == null) odCtx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (odCtx != null) com.overdrive.app.od.Od.authorize(odCtx);
        } catch (Throwable t) {
            logger.warn("BS: od init failed: " + t.getMessage());
        }

        int bsLayout = camera != null ? camera.getCameraLayoutMode() : 0;
        bsScaler.setCameraLayout(bsLayout);
        if (bsLayout == 3) {
            bsScaler.setProducerLayout(
                com.overdrive.app.camera.Dilink4Constants.CORNER_FRONT,
                com.overdrive.app.camera.Dilink4Constants.CORNER_RIGHT,
                com.overdrive.app.camera.Dilink4Constants.CORNER_REAR,
                com.overdrive.app.camera.Dilink4Constants.CORNER_LEFT,
                com.overdrive.app.camera.Dilink4Constants.FLIP_FRONT,
                com.overdrive.app.camera.Dilink4Constants.FLIP_RIGHT,
                com.overdrive.app.camera.Dilink4Constants.FLIP_REAR,
                com.overdrive.app.camera.Dilink4Constants.FLIP_LEFT);
        }
        if (bsLayout != 0) {
            // Parity with the recorder + stream lanes: the blind-spot scaler is
            // another GpuStreamScaler sampling the SAME producer, so it needs the
            // same two dilink4 corrections or its card diverges visually from
            // every other view — it was the only feed site never given either.
            // (Its 7/8 branches return before the red-mask splice, so the mask
            // is inert for those views today; pushing it keeps the contract
            // uniform if the branch layout ever changes.)
            try {
                org.json.JSONObject bsCamCfg = com.overdrive.app.config.UnifiedConfigManager
                    .loadConfig().optJSONObject("camera");
                if (bsCamCfg != null) {
                    bsScaler.setRedMaskEnabled(
                        bsCamCfg.optBoolean("dilink4RedMask", false));
                    bsScaler.setApaCenterInset(bsLayout == 3
                        ? (float) bsCamCfg.optDouble(
                            "dilink4ApaCenterInset", 0.09375)
                        : 0.0f);
                }
            } catch (Throwable t) {
                logger.warn("BS: failed to apply dilink4 red-mask/inset: " + t.getMessage());
            }
        }

        // GL-thread init + WAIT (captured locals, same rationale as the stream lane).
        // The scaler renders into the SurfaceControl layer's Surface (wrapped in an
        // EGLSurface on the GL thread) instead of an encoder input surface.
        final com.overdrive.app.streaming.GpuStreamScaler scalerLocal = bsScaler;
        final android.view.Surface layerSurfaceLocal = bsLayer.getSurface();
        final com.overdrive.app.camera.EGLCore eglCoreLocal = camera.getEglCore();
        final Object initLock = new Object();
        final boolean[] initDone = {false};
        final Exception[] initError = {null};
        camera.getGlHandler().post(() -> {
            try {
                scalerLocal.initWithSurface(eglCoreLocal, layerSurfaceLocal);
            } catch (Exception e) {
                initError[0] = e;
            } finally {
                synchronized (initLock) { initDone[0] = true; initLock.notify(); }
            }
        });
        boolean lockHeld = bsLifecycleLock.isHeldByCurrentThread();
        if (lockHeld) bsLifecycleLock.unlock();
        try {
            synchronized (initLock) { if (!initDone[0]) initLock.wait(2000); }
        } finally {
            if (lockHeld) bsLifecycleLock.lock();
        }
        if (!initDone[0]) throw new RuntimeException("BS: scaler init timed out");
        if (initError[0] != null) throw new RuntimeException("BS: scaler init failed", initError[0]);
        // Post-wait viability re-check: bsLifecycleLock was released around the
        // GL-init wait above. The bsEnabling/camViewEnabling guards set by the enable
        // callers under the lock bar any concurrent enable from entering here while we
        // wait, so simultaneous in-flight double-init can no longer happen. If a
        // concurrent call nonetheless already published a live lane, bail idempotently
        // on reacquire (do NOT release our scalerLocal — the fields may already point
        // at the published objects; teardown belongs to the single owning lifecycle).
        if (bsLayer != null && bsLayer.isCreated() && bsScaler != null && (blindSpotEnabled || camViewActive)) {
            // Another program finished arming during our wait — but only bail if OUR
            // freshly-built objects were superseded. Since we assigned bsLayer/bsScaler
            // at the top of THIS call, they are the live ones unless overwritten; the
            // enable guards prevent that, so proceeding is safe. Kept as belt-and-braces
            // parity with the former BS-only recheck.
        }
        if (!running || camera == null || camera.getGlHandler() == null) {
            throw new IllegalStateException("BS: pipeline torn down during init wait");
        }

        // Publish to the render loop's PASS 1C (no encoder on the native path —
        // PASS 1C skips drainEncoder when the encoder is null).
        camera.setBsStreamingComponents(bsScaler, null);
        return true;
        } catch (Throwable t) {
            // BS-LIFECYCLE-1: release the partially-built lane in the correct
            // order (scaler.release destroys the EGLSurface on the GL thread
            // BEFORE the SC layer's backing Surface is released) so a failed/raced
            // enable never orphans a SurfaceControl handle + dangling EGLSurface.
            // Only release if NEITHER program is live (a concurrent success must not
            // have its lane freed underneath it).
            if (!blindSpotEnabled && !camViewActive) releasePartialBsLane();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
    }

    /** Release a partially-built BS lane (scaler EGLSurface first on the GL
     *  thread, then the SC layer) — used by enableBlindSpotInternal's failure
     *  path so a throw/race never leaks GL/SurfaceControl resources. */
    private void releasePartialBsLane() {
        final com.overdrive.app.streaming.GpuStreamScaler scalerRef = bsScaler;
        final com.overdrive.app.surveillance.BsNativeLayer layerRef = bsLayer;
        bsScaler = null;
        bsLayer = null;
        try { if (camera != null) camera.clearBsStreamingComponents(); } catch (Throwable ignored) {}
        android.os.Handler glHandler = (camera != null) ? camera.getGlHandler() : null;
        if (scalerRef != null && glHandler != null) {
            final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
            boolean posted = glHandler.post(() -> {
                try { scalerRef.release(); } catch (Throwable ignored) {} finally { latch.countDown(); }
            });
            if (posted) {
                try { latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } else {
                try { scalerRef.release(); } catch (Throwable ignored) {}
            }
        } else if (scalerRef != null) {
            try { scalerRef.release(); } catch (Throwable ignored) {}
        }
        if (layerRef != null) { try { layerRef.release(); } catch (Throwable ignored) {} }
    }

    /** Resolve the on-screen rect for the BS layer against the LIVE panel.
     *  Prefers the orientation-safe preset (sizePct + corner) so the card is
     *  recomputed correctly for whatever orientation the panel is in right now;
     *  falls back to a legacy absolute {x,y,w,h} if that's what's stored, then to
     *  a default top-right card. Records the panel size for rotation detection. */
    private void resolveBsGeometry() {
        try {
            android.content.Context ctx = savedContext;
            if (ctx == null) ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();

            org.json.JSONObject bs = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
            // Resolve the display target FIRST so panel + geometry-key pick the
            // right display. Default head_unit = byte-for-byte the shipping path.
            bsTarget = (bs != null) ? bs.optString("target", "head_unit") : "head_unit";
            // Resolve the on-screen card rotation. Only the single-view modes
            // (side/rear) may rotate; the merged panorama stays landscape. This makes
            // the effective rotation self-correcting when the user switches back to
            // "both" without having to clear the stored angle.
            bsRotationDeg = resolveBsRotation(bs, bsViewMode);

            android.graphics.Point panel = (ctx != null)
                ? panelForTarget(ctx)
                : new android.graphics.Point(1920, isClusterTarget() ? 720 : 1080);
            bsLastPanelW = panel.x; bsLastPanelH = panel.y;

            // Per-target geometry: head_unit reads the existing "geometry" key
            // unchanged; cluster reads its own "geometryCluster" sibling key.
            String geomKey = isClusterTarget() ? "geometryCluster" : "geometry";
            org.json.JSONObject g = (bs != null) ? bs.optJSONObject(geomKey) : null;

            // BS-GEO-1/5: decide preset-vs-absolute by WHAT IS PERSISTED, not by
            // the bsSizePct field default (which is always >0, making presetRect
            // never-null and the absolute branch dead — silently snapping a user's
            // absolute /api/bs/geometry back to the 40%/tr preset on every re-enable).
            int[] r;
            if (g != null && g.has("sizePct")) {
                // Preset form (orientation-safe): recompute px from the live panel.
                bsSizePct = g.optInt("sizePct", bsSizePct);
                // PER-SIDE POSITION: the left camera (view 7, left turn) and right
                // camera (view 8, right turn) can each sit at their own corner —
                // mirroring per-side rotation, so a driver can put the left card on the
                // left of the screen and the right card on the right. Pick the current
                // view's corner key (cornerLeft/cornerRight) with a fallback to the
                // legacy single "corner" so an un-migrated config is unchanged.
                bsCorner = resolveBsCorner(g);
                r = presetRect(panel);
            } else if (g != null && g.has("x") && g.has("w")) {
                // Absolute form: honour the stored rect, clamped to the live panel.
                // Still resolve the corner — it does not place the rect here, but it IS what
                // bsRotationAlignX uses to decide which edge a ROTATED card's pillarboxed content
                // hugs. Leaving it at the stale field value flushed the picture to the wrong edge
                // of an absolutely-placed card (audit 2026-08).
                bsCorner = resolveBsCorner(g);
                r = clampBsRect(g.optInt("x"), g.optInt("y"), g.optInt("w"), g.optInt("h"));
            } else {
                // Nothing persisted → target-aware default card at the buffer aspect.
                // Cluster default = 0.80 (matches web bsSizePctCluster=80; the short
                // 1920×720 cluster is why the head-unit 0.40 is widened). clampBsRect
                // keeps the buffer aspect + fits panel height, so a large cluster card is
                // height-limited rather than overflowing.
                double defFrac = isClusterTarget() ? 0.80 : 0.40;
                int defW = Math.max(320, (int) (panel.x * defFrac));
                int defH = (int) (defW * (double) sharedLaneHeight / BS_WIDTH);
                r = clampBsRect(panel.x - defW - 24, 24, defW, defH);
            }
            if (r == null) {   // presetRect defensive null
                double defFrac = isClusterTarget() ? 0.80 : 0.40;
                int defW = Math.max(320, (int) (panel.x * defFrac));
                int defH = (int) (defW * (double) sharedLaneHeight / BS_WIDTH);
                r = clampBsRect(panel.x - defW - 24, 24, defW, defH);
            } else {
                r = clampBsRect(r[0], r[1], r[2], r[3]);
            }
            bsGeomRect = new int[]{r[0], r[1], r[2], r[3]};   // atomic publish
            // Rotation is applied in the GL render (bsScaler.setContentRotation via
            // applyBlindSpotCalibration), so the SurfaceControl layer stays at IDENTITY
            // orientation — a 90/270 LAYER transform is dropped by this firmware's
            // compositor and blanks the card (issue #164). Force the layer's buffer
            // rotation to 0 so no setGeometry call ever re-introduces a layer-level
            // transform, and keep the dest rect at the buffer's native 4:3 (done above).
            com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
            if (layer != null) layer.setBufferRotation(0);
            // Keep the GL scaler's card rotation in sync with the freshly-resolved angle
            // (covers the settings-write / enable path; the turn-tick AUTO path syncs it
            // too). Gated to side/rear inside resolveBsRotation. bsCorner is resolved for
            // the current side just above (preset branch), so align the rotated card to
            // that corner's edge — a 90/270 card hugs left/right per side, not centered.
            com.overdrive.app.streaming.GpuStreamScaler bss = bsScaler;
            if (bss != null) bss.setContentRotation(bsRotationDeg, bsRotationAlignX());
        } catch (Throwable t) {
            logger.warn("resolveBsGeometry failed: " + t.getMessage());
            if (bsGeomRect[2] <= 0) {
                bsGeomRect = new int[]{
                    24, 24, 640, (int) (640.0 * sharedLaneHeight / BS_WIDTH)
                };
            }
        }
    }

    /** Resolve the effective on-screen card rotation from the blindspot config.
     *  Rotation is honoured ONLY for the single-view merge modes (side/rear); the
     *  merged "both" panorama is inherently landscape and always renders upright.
     *
     *  <p>PER-SIDE: the left camera (view 7, left turn) and the right camera (view 8,
     *  right turn) are physically mirror-imaged, so each needs its OWN rotation — a
     *  single global angle that reads upright on the left camera reads upside-down /
     *  sideways on the right one. The stored config therefore carries per-side keys:
     *  fixed {@code rotationLeft}/{@code rotationRight} (int 0/90/180/270 or the
     *  string {@code "auto"}) and, for AUTO, per-side forward bases
     *  {@code rotationBaseLeft}/{@code rotationBaseRight}. When a per-side key is
     *  ABSENT we fall back to the legacy global {@code rotation}/{@code rotationBase}
     *  so an existing config (and any device that only ever set the old keys) keeps
     *  its current behaviour byte-for-byte.
     *
     *  <p>In AUTO mode the on-screen angle tracks the DIRECTION OF TRAVEL: it holds
     *  the configured base angle while moving forward and flips 180° in reverse gear,
     *  so the view reads naturally when backing up. Gear is read live from the
     *  daemon-local {@link com.overdrive.app.monitor.GearMonitor} (5 Hz), so no
     *  cross-process hop is involved. Any non-multiple-of-90 value is snapped to the
     *  nearest quarter turn.
     *
     *  @param viewMode the active blind-spot view (7 = left camera, 8 = right camera).
     *                  Any other value falls back to the left-side keys. */
    private int resolveBsRotation(org.json.JSONObject bs, int viewMode) {
        if (bs == null) return 0;
        String merge = bs.optString("mergeMode", "both");
        if (!"side".equals(merge) && !"rear".equals(merge)) return 0;
        // view 8 = RIGHT camera; everything else (incl. 7) = LEFT camera.
        boolean right = (viewMode == 8);
        // Fixed-rotation key: per-side (rotationRight/rotationLeft) with a fallback to
        // the legacy global "rotation" so an un-migrated config is unchanged.
        String sideKey = right ? "rotationRight" : "rotationLeft";
        Object rotVal = bs.has(sideKey) ? bs.opt(sideKey) : bs.opt("rotation");
        if (rotVal instanceof String && "auto".equalsIgnoreCase((String) rotVal)) {
            String baseKey = right ? "rotationBaseRight" : "rotationBaseLeft";
            int base = bs.has(baseKey)
                    ? snapDeg(bs.optInt(baseKey, 0))
                    : snapDeg(bs.optInt("rotationBase", 0));
            boolean reverse = false;
            try {
                reverse = com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGear()
                        == com.overdrive.app.monitor.GearMonitor.GEAR_R;
            } catch (Throwable ignored) {}
            return reverse ? (base + 180) % 360 : base;
        }
        // Fixed angle: read the per-side key when present, else the legacy global.
        int fixed = bs.has(sideKey) ? bs.optInt(sideKey, 0) : bs.optInt("rotation", 0);
        return snapDeg(fixed);
    }

    /** Normalise an angle into {0,90,180,270} (mod 360, nearest quarter turn). */
    private static int snapDeg(int deg) {
        deg = ((deg % 360) + 360) % 360;
        return (Math.round(deg / 90f) * 90) % 360;
    }

    /**
     * Conditional-display gate for the blind-spot card: may it be shown right now?
     * Evaluated on the 250ms turn tick, and its verdict is folded into the tick's
     * {@code side} value — so a gate that closes mid-signal HIDES an already-visible
     * card through the same debounced path as a turn-signal release (drive out of the
     * speed window / shift to R with the indicator still on).
     *
     * <p>Two independent user gates, both DISARMED by default so an existing config
     * behaves exactly as before:
     * <ul>
     *   <li><b>Speed window</b> {@code minSpeedKmh}/{@code maxSpeedKmh} — 0 means
     *       "no bound on that end", so 0/0 is off entirely. FAIL-OPEN on an
     *       unreadable speed: {@link com.overdrive.app.byd.BydDataCollector#readCurrentSpeedKmh()}
     *       returns NaN whenever the cluster's raw unit was never hardware-detected
     *       (and on any transient SDK miss), and silently withholding a SAFETY view on
     *       those trims would be a regression — so an unknown speed ALLOWS the card.
     *       An INVERTED pair (min &gt; max) describes an empty window that could never
     *       show anything, so it is treated as misconfiguration and ignored.</li>
     *   <li><b>Reverse suppression</b> {@code suppressInReverse} — hide while the
     *       gearbox reports R. Only trusted when {@link com.overdrive.app.monitor.GearMonitor}
     *       is actually polling ({@code isActive()}); its {@code currentGear} field
     *       cold-starts at GEAR_P and, after a {@code stop()} taken in R, would be a
     *       stale "reverse" that wrongly suppresses. Fail-open again: no live gear ⇒
     *       allow.</li>
     * </ul>
     *
     * <p>HYSTERESIS: the speed bounds are widened by {@link #BS_SPEED_HYST_KMH} while
     * the card is already up. Without it, cruising at exactly the boundary (min=30,
     * speed oscillating 29.9↔30.1) would flap the card — and each flap is a
     * SurfaceControl show/hide plus, on the cluster target, a projection open/close
     * that blanks and restores the gauges. The asymmetric thresholds make the on and
     * off edges distinct so a steady boundary speed settles instead of strobing.
     *
     * <p>Debug-preview is NOT gated (calibration must work parked at 0 km/h) — the
     * caller applies this only on the turn-signal path.
     *
     * <p>PURE: this only reads state — it does not publish {@link #bsGateAllowed}, so
     * it is safe to call from projThread (the cluster cold-open re-show path) as well
     * as the turn tick, without one thread's evaluation polluting the other's
     * edge-logged transition. {@link #bsEvalConditionalGate} is the tick's
     * publishing wrapper.
     *
     * @param bs the blindspot config snapshot the caller already read (one read per
     *           tick, and the gate sees the same values as the rotation resolution).
     * @param holding whether to apply the hysteresis slack — i.e. whether this is a
     *                DECISION TO KEEP/RESUME a display the driver has already been
     *                given, rather than a decision to start one. The turn tick passes
     *                the live {@code bsLayerVisible}; the cluster cold-open re-show
     *                passes TRUE, because by then the tick has already hidden the card
     *                (bsLayerVisible==false) even though the projection is lingering
     *                for exactly that session — reading the raw flag there would apply
     *                the narrow turn-ON threshold to a re-show and contradict the
     *                verdict the tick just published for the same speed.
     * @return the blocking reason, or "" when the card is allowed.
     */
    private String bsGateBlockReason(org.json.JSONObject bs, boolean holding) {
        try {
            // Calibration preview is never gated: it must work parked (0 km/h) and in
            // any gear, which is exactly what the speed/reverse gates would forbid.
            if (bs.optBoolean("debugPreview", false)) return "";
            int min = com.overdrive.app.config.UnifiedConfigManager
                .clampBsSpeedBound(bs.optInt("minSpeedKmh", 0));
            int max = com.overdrive.app.config.UnifiedConfigManager
                .clampBsSpeedBound(bs.optInt("maxSpeedKmh", 0));
            // An inverted window is unsatisfiable — ignore it instead of hiding forever.
            if (min > 0 && max > 0 && min > max) { min = 0; max = 0; }
            if (min > 0 || max > 0) {
                double kmh = com.overdrive.app.byd.BydDataCollector.getInstance().readCurrentSpeedKmh();
                // NaN = raw speed unit undetected / SDK miss → fail open (show).
                if (!Double.isNaN(kmh)) {
                    // Widen the window while holding a display (see HYSTERESIS above).
                    // The widened lower bound is clamped to be strictly REACHABLE: for a
                    // small min (<= the slack) an unclamped min-slack lands at or below
                    // 0, and since no speed is < 0 the card would pin up even at a full
                    // standstill. Flooring at 0 alone isn't enough (kmh < 0 is never
                    // true), so hold the widened bound just above 0 — a stopped car then
                    // still falls below it and the card hides.
                    double slack = holding ? BS_SPEED_HYST_KMH : 0.0;
                    double lowBound = Math.max(BS_SPEED_MIN_EFFECTIVE_KMH, min - slack);
                    if (min > 0 && kmh < lowBound) return "below min " + min;
                    if (max > 0 && kmh > (max + slack)) return "above max " + max;
                }
            }
            if (bs.optBoolean("suppressInReverse", false)) {
                com.overdrive.app.monitor.GearMonitor gm = com.overdrive.app.monitor.GearMonitor.getInstance();
                if (gm.isActive() && gm.getCurrentGear() == com.overdrive.app.monitor.GearMonitor.GEAR_R) {
                    return "reverse";
                }
            }
        } catch (Throwable t) {
            // Never let a config/SDK error suppress a safety view.
            return "";
        }
        return "";
    }

    /** Evaluate the conditional gate AND publish the verdict (edge-logged, exposed on
     *  /api/bs/status). Called once per turn tick on every tick that REACHES it —
     *  including no-indicator ticks, so the verdict can't go stale describing a
     *  condition that has since passed while BS owns the lane. Ticks that return
     *  earlier (camview-only, debugPreview) don't publish at all; the readers detect
     *  that via {@link #bsGateVerdictLive()} instead of serving a latched value. Costs
     *  nothing when no gate is armed (the reason resolver returns before any SDK read).
     *  The turn loop is the ONLY writer of these fields, so the log fires exactly once
     *  per real transition. */
    private boolean bsEvalConditionalGate(org.json.JSONObject bs) {
        String reason = bsGateBlockReason(bs, bsLayerVisible);
        boolean allow = reason.isEmpty();
        if (allow != bsGateAllowed) {
            logger.info("BS gate: " + (allow ? "ALLOW" : ("BLOCK (" + reason + ")")));
        }
        bsGateAllowed = allow;
        bsGateReason = reason;
        return allow;
    }

    /** Whether a published gate verdict is CURRENTLY being maintained, i.e. whether
     *  {@link #bsGateAllowed}/{@link #bsGateReason} describe live conditions.
     *
     *  <p>Requires BOTH that the turn loop is up AND that blind-spot owns it: the loop
     *  also runs for camera-view sessions, where {@code bsTurnTick} returns at its
     *  {@code !blindSpotEnabled} guard without evaluating the gate. And a BS disable
     *  that hands the lane to camview does NOT stop the loop, so "executor non-null"
     *  alone would keep serving a verdict from the last BS session forever. The
     *  debugPreview branch also returns early, but preview is ungated by definition, so
     *  it is reported as allowed rather than as a stale block.
     *
     *  <p>Resolved here rather than by resetting the fields on stop, so the turn loop
     *  stays the single writer — {@code shutdownNow()} interrupts but does not await, so
     *  a reset in {@code stopBsTurnLoop} could be re-latched by an in-flight tick. */
    private boolean bsGateVerdictLive() {
        if (bsTurnExec == null || !blindSpotEnabled) return false;
        try {
            return !com.overdrive.app.config.UnifiedConfigManager.getBlindSpot()
                .optBoolean("debugPreview", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the conditional gate (speed window / reverse) currently permits the
     *  card. True when no gate is armed, and true whenever no live verdict is being
     *  maintained (see {@link #bsGateVerdictLive()}) so a latched BLOCK is never
     *  reported for a feature that isn't currently gated. Exposed for /api/bs/status. */
    public boolean isBsGateAllowed() { return !bsGateVerdictLive() || bsGateAllowed; }

    /** Why the gate is blocking ("" when allowing, or when no live verdict is being
     *  maintained — see {@link #isBsGateAllowed()}). Exposed for /api/bs/status. */
    public String getBsGateReason() { return bsGateVerdictLive() ? bsGateReason : ""; }

    /** Resolve the on-screen card corner for the CURRENT view from a per-target
     *  geometry object. PER-SIDE: view 8 (right turn) reads {@code cornerRight},
     *  everything else (incl. view 7 / left turn) reads {@code cornerLeft}; either
     *  falls back to the legacy single {@code corner} (then the current field, then
     *  "tr") when the per-side key is absent, so an un-migrated config keeps its
     *  existing placement. Kept side-aware so a turn-signal side switch repositions
     *  the card to that camera's chosen corner. */
    private String resolveBsCorner(org.json.JSONObject g) {
        if (g == null) return canonicalCorner(bsCorner, "tr");
        String legacy = g.optString("corner", canonicalCorner(bsCorner, "tr"));
        String sideKey = (bsViewMode == 8) ? "cornerRight" : "cornerLeft";
        // Canonicalise HERE, at the single point every corner read passes through: the config
        // may hold a token from an older build, a hand edit, an import, or the unified-settings
        // POST (which does not validate geometry). Returning it raw let the rect decoder and
        // bsCornerAlignX disagree — see canonicalCorner.
        return canonicalCorner(g.optString(sideKey, legacy), "tr");
    }

    /** Horizontal alignment (+1 right / -1 left / 0 center) for a rotated (90/270)
     *  card anchored at {@code corner}: a RIGHT corner (tr/br) hugs the right screen
     *  edge, a LEFT corner (tl/bl) the left, a centered card stays centered. Passed to
     *  {@code setContentRotation} so the pillarboxed portrait content sits flush with
     *  the same edge the card is anchored to instead of floating in the middle of its
     *  dest rect. No effect at 0/180 (no pillarbox). */
    private static int bsCornerAlignX(String corner) {
        return bsCornerAlignX(corner, "tr");
    }

    /**
     * {@link #bsCornerAlignX} with an EXPLICIT fallback, so each lane uses the same default its
     * rect decoder does. The blind-spot card defaults to {@code tr}; the camera view defaults to
     * {@code center}. Passing the wrong one makes this disagree with {@code cornerRect} for an
     * unrecognised token: a camview corner resolved as {@code tr} (alignX +1) while its rect was
     * centred flushed the rotated picture to the right edge of a centred rect (audit 2026-08).
     *
     * @param fallback the lane's own default corner, matching what its rect decoder passes
     */
    private static int bsCornerAlignX(String corner, String fallback) {
        // Canonicalise first so this can never disagree with cornerRect for the same input.
        // It used to decode the raw token, which meant an uppercase/legacy "TR" anchored the
        // card top-RIGHT (cornerRect lowercases) while alignX read 0 — leaving a rotated
        // card's pillarboxed content floating centred in a right-anchored rect, the exact
        // defect this parameter exists to prevent.
        String c = canonicalCorner(corner, fallback);
        if ("center".equals(c)) return 0;
        if (c.endsWith("r")) return 1;
        if (c.endsWith("l")) return -1;
        return 0;
    }

    /** {@link #bsCornerAlignX} for the current {@link #bsCorner} field. */
    private int bsRotationAlignX() {
        return bsCornerAlignX(bsCorner);
    }

    /** The ACTIVE target's geometry JSON object ("geometry" for head-unit,
     *  "geometryCluster" for cluster), or null. Cheap config read (no panel query),
     *  so it's safe on the turn-signal side-switch path. Used by the per-side
     *  reposition to pick cornerLeft/cornerRight without a full resolveBsGeometry. */
    private org.json.JSONObject currentGeometryObj() {
        try {
            org.json.JSONObject bs = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
            if (bs == null) return null;
            return bs.optJSONObject(isClusterTarget() ? "geometryCluster" : "geometry");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Update the on-screen geometry live (from /api/bs/geometry / settings UI).
     *  Resize is a pure SurfaceControl scale transaction (the 1280×960 buffer is
     *  scaled into the dest rect) — no GL re-init, no reallocation, stable. Clamps
     *  into the panel + a sane min size so any caller is safe. Applied live only
     *  when shown; a hidden layer picks up the new rect on its next show. */
    public void setBsGeometry(int x, int y, int w, int h) {
        int[] r = clampBsRect(x, y, w, h);
        bsGeomRect = new int[]{r[0], r[1], r[2], r[3]};   // atomic publish
        com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
        if (layer != null && layer.isCreated() && bsLayerVisible) {
            layer.setGeometry(r[0], r[1], r[2], r[3]);
        }
    }

    /**
     * Apply a persisted size-only Blind Spot geometry edit. The caller has already
     * updated the target's geometry object; this computes the live rect from that
     * object while preserving its per-side corner choices.
     */
    public void setBsGeometrySize(int pct, String target) {
        boolean cluster = "cluster".equals(target);
        if (cluster != isClusterTarget()) return;
        try {
            bsSizePct = Math.max(15, Math.min(pct, 90));
            org.json.JSONObject geometry = currentGeometryObj();
            bsCorner = resolveBsCorner(geometry);
            android.content.Context ctx = savedContext;
            if (ctx == null) ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            android.graphics.Point panel = (ctx != null)
                ? panelForTarget(ctx)
                : new android.graphics.Point(1920, cluster ? 720 : 1080);
            int[] r = presetRect(panel);
            if (r == null) return;
            // Do NOT touch the shared rect at all while a camera view owns the lane. Mirrors
            // setCamViewGeometrySize's guard. Without this, moving the Blind Spot size slider
            // while a camera view was on screen shrank the camera view and jumped it to the card's
            // corner (audit 2026-08).
            //
            // Not even a publish-without-push: bsGeomRect is the field the SHOW helpers read
            // (setBlindSpotVisible, and clusterShowWhenReady on every tick it finds the layer not
            // yet shown), while camview re-asserts it only on its program-transition tick. So
            // storing BS's rect here corrupted the next camview show. The size is already
            // persisted by the caller, and blind-spot's own takeback recomputes the rect from
            // config (resolveBsGeometry / the cluster preset branch), so nothing is lost.
            if (camViewOwnsLane()) return;
            setBsGeometry(r[0], r[1], r[2], r[3]);
        } catch (Throwable t) {
            logger.warn("setBsGeometrySize failed: " + t.getMessage());
        }
    }

    /**
     * Apply a persisted size-only normal camera-view geometry edit. It preserves the
     * selected camera and corner, and only moves the shared native layer while the
     * camera-view program owns it. Blind Spot may temporarily own that lane; in that
     * case its geometry must remain untouched and the saved camera-view size is picked
     * up when the normal view regains the lane.
     */
    public void setCamViewGeometrySize(int pct, String target) {
        boolean notifyOverlay = false;
        String activeTarget = null;
        bsLifecycleLock.lock();
        try {
            String requestedTarget = "cluster".equals(target) ? "cluster" : "head_unit";
            if (!camViewActive || !requestedTarget.equals(camViewTarget)) return;

            // The API persisted the geometry first. Resolve it here so the panel-specific
            // 4:3 rect uses the current display dimensions and keeps the stored corner.
            resolveCamViewGeometry();
            if (laneProgram != PROG_CAMVIEW) return;

            // The camview program currently owns the shared geometry. Publishing the new
            // rect then issuing one SurfaceControl transaction resizes it without a GL
            // rebuild or a camera re-open.
            bsTarget = camViewTarget;
            bsGeomRect = camViewGeomRect;
            com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
            if (layer != null && layer.isCreated() && bsLayerVisible) {
                int[] r = camViewGeomRect;
                layer.setGeometry(r[0], r[1], r[2], r[3]);
            }
            notifyOverlay = true;
            activeTarget = camViewTarget;
        } catch (Throwable t) {
            logger.warn("setCamViewGeometrySize failed: " + t.getMessage());
        } finally {
            bsLifecycleLock.unlock();
        }

        // Refresh the head-unit close button with the new rect. This runs after the
        // shared rect is published, so the broadcast never gives it stale geometry.
        if (notifyOverlay) emitCamViewState(true, activeTarget);
    }

    /** Set on-screen geometry from a size%+corner preset for the CURRENT target. */
    public void setBsGeometryPreset(int pct, String corner) {
        setBsGeometryPreset(pct, corner, bsTarget);
    }

    /** Set on-screen geometry from a size%+corner preset (the daemon does the
     *  panel math — the web UI doesn't know the real panel size). Width = pct% of
     *  panel width, height keeps the active buffer aspect, inset 24px from the chosen
     *  corner (tl/tr/bl/br). Persists to the TARGET's geometry key (geometry vs
     *  geometryCluster) + applies live only when that target is active. */
    public void setBsGeometryPreset(int pct, String corner, String target) {
        try {
            boolean cluster = "cluster".equals(target);
            android.content.Context ctx = savedContext;
            if (ctx == null) ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            android.graphics.Point panel = (ctx != null)
                ? (cluster ? com.overdrive.app.surveillance.BsNativeLayer.clusterDisplaySize(ctx)
                           : com.overdrive.app.surveillance.BsNativeLayer.displaySize(ctx))
                : new android.graphics.Point(1920, cluster ? 720 : 1080);
            int p = Math.max(15, Math.min(pct, 90));
            int w = (int) (panel.x * (p / 100.0));
            int h = (int) (w * (double) sharedLaneHeight / BS_WIDTH);
            int inset = 24;
            // One canonical decode for every path (see canonicalCorner): this used to derive
            // right/bottom inline, which read an unknown token as top-left.
            String canonical = canonicalCorner(corner, "tr");
            int[] pr = cornerRect(canonical, panel, w, h, inset);
            int x = pr[0], y = pr[1];
            // Persist the PRESET (sizePct + corner) under the target's key — NOT
            // absolute px — so it stays correct across rotation. resolveBsGeometry()
            // recomputes the px rect from the LIVE target panel on enable + rotation.
            // updateSection is a shallow per-key merge at the TOP level only, so it
            // REPLACES the whole geometry object — we must therefore carry forward the
            // existing keys we don't set here, or a single-corner preset write would
            // silently drop the user's per-side cornerLeft/cornerRight. This
            // single-corner API means "put the card here" for BOTH sides, so mirror
            // `corner` into cornerLeft+cornerRight (keeping them coherent) while still
            // preserving any other keys (e.g. absolute x/y/w/h) already stored.
            String geomKey = cluster ? "geometryCluster" : "geometry";
            // Persist the CANONICAL token, never the caller's raw string: storing an
            // unrecognised corner would make every later read decode it as top-left.
            String cornerVal = canonical;
            org.json.JSONObject g;
            try {
                org.json.JSONObject bsNow = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
                org.json.JSONObject existing = (bsNow != null) ? bsNow.optJSONObject(geomKey) : null;
                g = (existing != null) ? new org.json.JSONObject(existing.toString()) : new org.json.JSONObject();
            } catch (Throwable t) {
                g = new org.json.JSONObject();
            }
            g.put("sizePct", p);
            g.put("corner", cornerVal);
            g.put("cornerLeft", cornerVal);
            g.put("cornerRight", cornerVal);
            com.overdrive.app.config.UnifiedConfigManager.updateSection("blindspot",
                new org.json.JSONObject().put(geomKey, g));
            // Apply live only if editing the active target; otherwise it's persisted
            // for when that target is next selected.
            if (cluster == isClusterTarget()) {
                bsSizePct = p;
                bsCorner = canonical;
                // Never move the shared layer while a CAMERA VIEW owns it: the size/corner fields
                // above are blind-spot's own and are re-read when it takes the lane back, but
                // setBsGeometry publishes bsGeomRect AND pushes it, so a Blind Spot settings write
                // used to resize the on-screen camera view and jump it to the card's corner
                // (audit 2026-08). Same guard as setBsGeometrySize / setCamViewGeometrySize.
                if (camViewOwnsLane()) return;
                setBsGeometry(x, y, w, h);
            }
        } catch (Throwable t) {
            logger.warn("setBsGeometryPreset failed: " + t.getMessage());
        }
    }

    /**
     * True when a CAMERA VIEW currently owns the shared native lane, so blind-spot code must not
     * move the layer or overwrite {@code bsGeomRect}/{@code bsTarget}.
     *
     * <p>{@code laneProgram != PROG_BS} rather than {@code == PROG_CAMVIEW}: {@code enableCamView}
     * publishes {@code camViewActive = true} together with {@code laneProgram = PROG_NONE} and
     * reconfigures on the NEXT tick, so a {@code == PROG_CAMVIEW} test reads false for up to one
     * 250ms tick and every guard misfires exactly during the handover. Testing "not blind-spot"
     * covers that window while still yielding when blind-spot has genuinely taken the lane —
     * {@code camViewActive && laneProgram == PROG_BS} is a documented reachable state (blind-spot
     * has priority and can hold the lane while a camera-view request stands).
     *
     * <p>Both fields are volatile and read non-atomically, so this is advisory: a concurrent
     * enable/disable can still land either side of the check. That is acceptable here — every
     * caller's fallback is "leave the shared rect alone and let the owner re-resolve it", which is
     * the safe direction. Do not use this where correctness needs a lock.
     */
    private boolean camViewOwnsLane() {
        return camViewActive && laneProgram != PROG_BS;
    }

    /** True when the blind-spot display target is the driver cluster. */
    private boolean isClusterTarget() { return "cluster".equals(bsTarget); }

    /** The panel size of the active target (head-unit vs cluster). The cluster
     *  metrics are only valid while an OEM projection is open; otherwise
     *  clusterDisplaySize falls back to the fixed 1920×720. */
    private android.graphics.Point panelForTarget(android.content.Context ctx) {
        return isClusterTarget()
            ? com.overdrive.app.surveillance.BsNativeLayer.clusterDisplaySize(ctx)
            : com.overdrive.app.surveillance.BsNativeLayer.displaySize(ctx);
    }

    /** Recompute the px rect from the current size%/corner preset + LIVE panel.
     *  Called on enable and on orientation change so the card stays correctly
     *  placed in both portrait and landscape. Returns [x,y,w,h] or null. */
    private int[] presetRect(android.graphics.Point panel) {
        if (bsSizePct <= 0) return null;
        int p = Math.max(15, Math.min(bsSizePct, 90));
        int w = (int) (panel.x * (p / 100.0));
        // The dest rect handed to setGeometry must keep the source buffer aspect
        // for EVERY rotation. Android's Transaction.setGeometry computes the scale from
        // the un-swapped source dimensions and rotates the
        // result AFTER scaling, so square (undistorted) pixels require dstW/dstH ==
        // source aspect. Swapping the destination aspect at a quarter turn makes
        // anamorphic stretch that was the "distorted when rotated" half of issue #164.
        // (Native then rotates the uniformly-scaled buffer; the card's visible footprint is
        // portrait — that is the post-scale rotation, not the scale reference.)
        int h = (int) (w * (double) sharedLaneHeight / BS_WIDTH);
        int inset = 24;
        return cornerRect(canonicalCorner(bsCorner, "tr"), panel, w, h, inset);
    }

    /**
     * Canonicalise a stored corner token to one of {@code center|tl|tr|bl|br}, falling back to
     * {@code fallback} (and LOGGING) when it is unrecognised.
     *
     * <p>The decode below reads left/right and top/bottom from the token's shape
     * ({@code endsWith("r")} / {@code startsWith("b")}), so an unknown string resolves to
     * neither-right-nor-bottom — top-left — no matter what the user actually picked. That silent
     * mis-decode is what made a "top right" selection render top left. Naming it in the log turns
     * a wrong position into a diagnosable one. The API layer rejects unknown corners at ingest
     * (StreamingApiHandler.VALID_CORNERS); this is the second line of defence for a config written
     * by an older build, a hand edit, or an import.
     */
    private static String canonicalCorner(String corner, String fallback) {
        if (corner != null) {
            String c = corner.trim().toLowerCase();
            if ("center".equals(c) || "tl".equals(c) || "tr".equals(c)
                    || "bl".equals(c) || "br".equals(c)) {
                return c;
            }
            logger.warn("blindspot/camview: unknown corner '" + corner
                + "' — falling back to " + fallback + " (expected center|tl|tr|bl|br)");
        }
        return fallback;
    }

    /** Anchor a w×h card at a CANONICAL corner within {@code panel}, inset from the edges.
     *  Shared by the blind-spot and camera-view preset paths so the two can never disagree
     *  about what a corner code means. */
    private static int[] cornerRect(String canonicalCorner, android.graphics.Point panel,
                                    int w, int h, int inset) {
        if ("center".equals(canonicalCorner)) {
            return new int[]{ (panel.x - w) / 2, (panel.y - h) / 2, w, h };
        }
        boolean right = canonicalCorner.endsWith("r");
        boolean bottom = canonicalCorner.startsWith("b");
        int x = right ? panel.x - w - inset : inset;
        int y = bottom ? panel.y - h - inset : inset;
        return new int[]{x, y, w, h};
    }

    /** Clamp a requested rect into the current TARGET panel with a min card size. */
    private int[] clampBsRect(int x, int y, int w, int h) {
        try {
            android.content.Context ctx = savedContext;
            if (ctx == null) ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            android.graphics.Point panel = (ctx != null)
                ? panelForTarget(ctx)
                : new android.graphics.Point(1920, isClusterTarget() ? 720 : 1080);
            w = Math.max(160, Math.min(w, panel.x));
            h = Math.max(120, Math.min(h, panel.y));
            // BS-GEO-4: keep the dest rect at the source buffer's aspect for all
            // rotations so the SurfaceControl scale stays UNIFORM — the rounded corners
            // are baked into the source buffer, so a mismatched destination
            // scales the circular corners into ellipses. Android's setGeometry computes
            // the scale from the UN-swapped source dims and rotates AFTER scaling, so a
            // uniform rotation needs dstW/dstH to match the source regardless of
            // the angle — a 3:4 dest at 90/270 gives xScale != yScale (~1.78x squish),
            // the distortion half of issue #164. So NO per-angle aspect swap here.
            double want = (double) BS_WIDTH / sharedLaneHeight;
            if ((double) w / h > want) w = (int) (h * want);
            else                       h = (int) (w / want);
            x = Math.max(0, Math.min(x, panel.x - w));
            y = Math.max(0, Math.min(y, panel.y - h));
        } catch (Throwable ignored) {
            w = Math.max(160, w); h = Math.max(120, h);
            x = Math.max(0, x); y = Math.max(0, y);
        }
        return new int[]{x, y, w, h};
    }

    /**
     * {@link #clampBsRect} against an EXPLICIT panel — pure arithmetic, no display query.
     * clampBsRect resolves the panel itself, and for a cluster target that reaches
     * clusterDisplaySize() → a {@code dumpsys display} shell-out, which may run only on
     * projThread (never on the 250ms turn loop). Callers on that loop pass the panel size
     * cached by the last full resolve, the same idiom presetRect uses.
     */
    private int[] clampBsRectTo(int x, int y, int w, int h, int panelW, int panelH) {
        w = Math.max(160, Math.min(w, panelW));
        h = Math.max(120, Math.min(h, panelH));
        double want = (double) BS_WIDTH / sharedLaneHeight;
        if ((double) w / h > want) w = (int) (h * want);
        else                       h = (int) (w / want);
        x = Math.max(0, Math.min(x, panelW - w));
        y = Math.max(0, Math.min(y, panelH - h));
        return new int[]{x, y, w, h};
    }

    /** Show/hide the BS layer (turn-trigger / debug-preview gate). */
    public void setBlindSpotVisible(boolean visible) {
        bsLayerVisible = visible;
        com.overdrive.app.camera.PanoramicCameraGpu cam = camera;
        if (cam != null) cam.setBsLayerVisible(visible);
        // Ramp global camera fps when BS is the sole consumer (edge-detected).
        fireBsVisibilityChanged();
        com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
        if (layer == null || !layer.isCreated()) return;
        if (visible) {
            // Retarget to the cluster's layerStack before showing (no-op if already
            // there). Head-unit keeps layerStack 0 (never calls setLayerStack with a
            // changed value → identical transaction). The show only happens via the
            // gated path in bsTurnTick when the cluster display is actually present.
            layer.setLayerStack(isClusterTarget() ? bsClusterStack : 0);
            int[] g = bsGeomRect; layer.setGeometry(g[0], g[1], g[2], g[3]);
        } else {
            layer.hide();
        }
    }

    /** Current BS display target string for API/status ("head_unit"|"cluster"). */
    public String getBsTargetString() { return bsTarget; }

    /** Invoked by ClusterProjectionController when the cluster projection CLOSES
     *  (linger / max-cap / disarm / any forceClose). Hide the BS layer + drop the
     *  render gate so PASS 1C stops drawing — otherwise the gate stays ON after the
     *  projection's display is gone and the GL pipeline keeps rendering at full rate
     *  into an orphaned layer (the "GPU stays high after the turn signal stops" bug).
     *  No-op for head-unit (this is only wired to the cluster lifecycle). */
    public void onClusterProjectionClosed() {
        // SHOW-AFTER-CLOSE GUARD (I6/I7): serialize the hide against the show
        // (clusterShowWhenReady / onClusterProjectionReady) on bsLifecycleLock so the
        // close-hide and a racing present-edge show are MUTUALLY EXCLUSIVE — they can
        // no longer interleave (hide landing between the show's isOpen() re-check and
        // its setGeometry, which would strand the layer shown after close). Whichever
        // wins the lock, the loser observes the authoritative state. Reentrant with
        // disableBlindSpot (holds this lock when it calls forceClose→notifyPipelineClosed).
        bsLifecycleLock.lock();
        try {
            // GPU fix ONLY: drop the render gate so PASS 1C stops drawing once the
            // projection's display is gone. Do the SAME as a turn-off: hide the layer
            // + clear the visible intent. (setBlindSpotVisible(false) is just
            // layer.hide() + gate off — no teardown, pipeline stays warm.)
            bsLayerVisible = false;
            com.overdrive.app.camera.PanoramicCameraGpu cam = camera;
            if (cam != null) cam.setBsLayerVisible(false);
            fireBsVisibilityChanged();   // drop global fps if BS is sole consumer
            // INCREMENTING-STACK FIX: the fission VirtualDisplay is destroyed on this
            // close; its layerStack is now dead and the NEXT open gets a new (higher)
            // one. Clear the cached stack so clusterLayerStack(bsClusterStack)'s
            // fallback path (fission block seen but stack unparsed) can never carry a
            // value from a destroyed lower stack into the next open — it returns
            // STACK_UNRESOLVED instead, so clusterShowWhenReady defers rather than
            // tagging the layer onto a dead stack. The next onClusterProjectionReady
            // re-resolves the live stack fresh. Guarded by bsLifecycleLock (same lock
            // serializing the show path); bsClusterStack is volatile.
            bsClusterStack = com.overdrive.app.surveillance.BsNativeLayer.STACK_UNRESOLVED;
            com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
            if (layer != null && layer.isCreated()) layer.hide();
        } catch (Throwable t) {
            logger.warn("onClusterProjectionClosed failed: " + t.getMessage());
        } finally {
            bsLifecycleLock.unlock();
        }
    }

    /** Invoked by ClusterProjectionController once the OEM cluster projection is
     *  open AND the cluster VirtualDisplay is present. Resolve the live layerStack
     *  (changes per size profile: 30→stack 1, 31→stack 2) + geometry against the
     *  real cluster panel, then show the card if a signal is currently active.
     *  SIMPLE direct show (the known-working path) — accepts a brief stale-frame on
     *  re-show as a minor cosmetic issue rather than the warm-reveal indirection
     *  that regressed to no-video. */
    /** Drive the cluster card to VISIBLE while a projection is open. Idempotent +
     *  desync-proof: it unconditionally asserts the camera render gate ON (cheap
     *  volatile write — fixes the "gate stuck off after a close, layer shown=true,
     *  nothing draws = no video" desync) and applies geometry only when not already
     *  shown (the one expensive transaction). Called every tick while intent=visible
     *  AND the projection is ready. Cluster-only. */
    private void clusterShowWhenReady() {
        // USE-AFTER-RELEASE FIX: serialize the whole bsLayer snapshot + use against
        // disableBlindSpot's teardown (which nulls + releases bsLayer under this same
        // lock). Without it, a present-edge re-notify on projThread can read bsLayer
        // non-null here, then disableBlindSpot can null + release it on another thread
        // before this method reaches setLayerStack/setGeometry — operating on (and
        // re-showing) a torn/released layer (violates I6/I7: no show-after-disable).
        // bsLifecycleLock is reentrant, so onClusterProjectionReady can hold it across
        // this call. The fireBsVisibilityChanged listener (RMM.reconcileCameraProfile)
        // takes only its own reconcileLock AFTER bsLifecycleLock — the same order
        // disableBlindSpot already uses — so no lock-inversion/deadlock.
        bsLifecycleLock.lock();
        try {
            bsLayerVisible = true;
            com.overdrive.app.camera.PanoramicCameraGpu cam = camera;
            if (cam != null) cam.setBsLayerVisible(true);   // unconditional — re-arm gate
            // Edge-detected: only the first show-tick of a signal session reaches the
            // listener (per-250ms re-asserts no-op via bsLastNotifiedVisible).
            fireBsVisibilityChanged();
            com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
            if (layer == null || !layer.isCreated()) return;
            if (!layer.isShown()) {
                // I9 GUARD: resolving the live layerStack below spawns a `dumpsys
                // display` (clusterLayerStack → resolveFissionDisplay). That MUST run
                // ONLY on projThread, NEVER on the 250ms BsTurnTrigger loop. When this
                // method is reached from bsTurnTick (BsTurnTrigger thread) with the layer
                // hidden, defer the dumpsys-driven show to projThread via a re-drive —
                // onClusterProjectionReady → clusterShowWhenReady then re-runs HERE on
                // projThread (isOnProjThread()==true), resolves the stack, and shows. The
                // cheap render-gate re-arm above (volatile writes + fireBsVisibilityChanged)
                // already ran on this tick, so the GL lane stays armed in the meantime.
                com.overdrive.app.surveillance.ClusterProjectionController ctrl =
                    com.overdrive.app.surveillance.ClusterProjectionController.getInstance();
                if (!ctrl.isOnProjThread()) {
                    ctrl.requestShowRedrive();   // I9-safe: dumpsys re-runs on projThread
                    return;
                }
                // Re-resolve the live cluster layerStack on each hidden→shown edge — the
                // fission display may have materialised AFTER the projection-ready commit
                // (READY_SETTLE_MS=900ms is shorter than the ~1-3s materialise on some
                // models), so a single resolve at onClusterProjectionReady can be stale.
                int live = com.overdrive.app.surveillance.BsNativeLayer.clusterLayerStack(bsClusterStack);
                // STACK_UNRESOLVED (-1) = no fission display found → DO NOT SHOW. Tagging
                // the layer with a wrong/sentinel stack composites it onto a dead stack =
                // BLACK (the model-dependent bug). Keep it hidden; bsTurnTick re-enters
                // every poll within the linger/cap window and retries once the display
                // appears. Never pass a negative stack to setLayerStack/setGeometry.
                if (live == com.overdrive.app.surveillance.BsNativeLayer.STACK_UNRESOLVED) {
                    logger.warn("clusterShowWhenReady: fission display unresolved — deferring show");
                    return;
                }
                // SHOW-AFTER-CLOSE GUARD (I6/I7): re-read the projection state as the
                // FINAL gate before the show transaction. A forceClose/shutdown on
                // another thread flips projState ST_OPEN→ST_CLOSING (under its monitor)
                // BEFORE it hides the layer (notifyPipelineClosed → onClusterProjectionClosed,
                // which serializes on this same bsLifecycleLock). So if a present-edge
                // re-notify on projThread passed pollPresentEdge's guards but a close
                // raced in, isOpen() is now false → DECLINE the show. Whichever of the
                // show/close-hide wins this lock, the loser sees the authoritative state:
                // close-first → show declines here; show-first → close-hide hides it.
                // No-op for the bsTurnTick callers (they only enter on c.isReady(), i.e.
                // projState==ST_OPEN). The dumpsys above already ran on projThread (I9).
                if (!com.overdrive.app.surveillance.ClusterProjectionController
                        .getInstance().isOpen()) {
                    logger.warn("clusterShowWhenReady: projection no longer open — declining show");
                    return;
                }
                bsClusterStack = live;
                layer.setLayerStack(bsClusterStack);
                int[] g = bsGeomRect; layer.setGeometry(g[0], g[1], g[2], g[3]);   // shows it
            }
        } finally {
            bsLifecycleLock.unlock();
        }
    }

    public void onClusterProjectionReady() {
        // USE-AFTER-RELEASE FIX: hold bsLifecycleLock across the bsLayer read + the
        // clusterShowWhenReady() call so a concurrent disableBlindSpot (which nulls +
        // releases bsLayer under the same lock) cannot release the layer between the
        // null-check here and the show. Reentrant with clusterShowWhenReady's own
        // acquire. Invoked only on projThread (the dumpsys-owning thread, per I9), so
        // running clusterLayerStack/resolveBsGeometry under the lock is legal here.
        bsLifecycleLock.lock();
        try {
            // Accept the ready callback for EITHER a blind-spot cluster session
            // (bsTarget==cluster) OR a camera-view cluster session (camViewTarget==cluster).
            // Without the camview arm, a camview-only cold open was skipped here — worse,
            // resolveBsGeometry() below resets bsTarget to head_unit, so every subsequent
            // re-notify then short-circuited on isClusterTarget() and the camview card never
            // showed on the cluster at all.
            // Ownership is `laneProgram == PROG_CAMVIEW`, NOT `!blindSpotEnabled`. Blind-spot can be
            // ENABLED (armed, waiting on a turn signal) while a camera view actually owns the lane —
            // the file documents that state as reachable. With the old test, camViewCluster went
            // false there and the branch below took resolveBsGeometry(), overwriting the camera
            // view's cluster rect with a head-unit-sized blind-spot rect AND resetting bsTarget,
            // which then broke every later re-notify (audit 2026-08).
            boolean camViewCluster = camViewOwnsLane()
                    && isCamViewClusterTarget();
            if (!isClusterTarget() && !camViewCluster) return;
            com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
            if (layer == null || !layer.isCreated()) return;
            int live = com.overdrive.app.surveillance.BsNativeLayer.clusterLayerStack(bsClusterStack);
            // Only adopt a positively-resolved stack; keep last-known-good on a miss
            // (don't poison bsClusterStack with the -1 sentinel — clusterShowWhenReady
            // re-resolves and defers the show until the display is actually present).
            if (live != com.overdrive.app.surveillance.BsNativeLayer.STACK_UNRESOLVED) {
                bsClusterStack = live;
                // INCREMENTING-STACK FIX ("no video after 3-4 attempts"): SurfaceFlinger
                // assigns a NEW, higher layerStack each time the fission VirtualDisplay
                // is destroyed (linger close) + recreated (next open) — observed 1→2→3→4→5
                // across cycles. The BS layer is created ONCE and kept warm, so it stays
                // tagged to whatever stack it was last given. clusterShowWhenReady only
                // re-tags on the hidden→shown EDGE, so an already-shown warm layer (or one
                // shown on a now-destroyed lower stack) composites onto a DEAD stack =
                // black. This hook runs ONCE per open on projThread (dumpsys-legal, unlike
                // the 250ms bsTurnTick path — I9), so re-assert the freshly-resolved live
                // stack on the layer NOW, regardless of shown-state. setLayerStack is a
                // cheap transaction that no-ops internally when the stack is unchanged
                // (BsNativeLayer.setLayerStack early-returns on ==), so this adds no churn
                // on a warm reopen onto the SAME stack and never passes a negative stack.
                if (layer.isShown()) layer.setLayerStack(live);
            }
            logger.info("onClusterProjectionReady: cluster layerStack=" + bsClusterStack
                    + " (resolved=" + live + ")");
            // Recompute geometry against the live cluster panel — but PROGRAM-AWARE. When
            // camera-view owns the lane, resolveBsGeometry() would read the BLIND-SPOT config
            // section, reset bsTarget to blindspot.target (head_unit by default) and overwrite
            // the camview cluster rect with a head-unit-sized BS rect — discarding the
            // automation's chosen size/position and (via the bsTarget reset) breaking the
            // isClusterTarget() guard for later re-notifies. Route to the camview resolver,
            // which keeps camViewTarget=cluster and sizes against clusterDisplaySize, exactly
            // as camViewTick's own reconfig block does. A BS-cluster session (blindSpotEnabled)
            // still takes resolveBsGeometry() unchanged.
            if (camViewCluster) {
                bsTarget = camViewTarget;          // stays "cluster" — keep the guard true
                resolveCamViewGeometry();
                bsGeomRect = camViewGeomRect;
            } else {
                resolveBsGeometry();
            }
            // COLD-OPEN no-show fix: do NOT gate the show on the instantaneous
            // bsLayerVisible. On a cold open the fission display materializes
            // 1-3.5s AFTER commitReady, and the turn signal commonly clears in
            // that gap (BS_OFF_DEBOUNCE_MS=800ms): bsTurnTick then runs
            // setBlindSpotVisible(false) → bsLayerVisible=false BEFORE the
            // present-edge re-notify lands here. Gating on bsLayerVisible would
            // skip the show forever even though the projection is still up and
            // LINGERING for exactly this card (gauges stay blanked the whole
            // linger). During a transient (BS-driven) open the projection is up
            // ONLY because a BS turn session occurred, so the card must show for
            // the linger. We still HONOR the sustained-map hold (I5/I7): when the
            // projection is held by the nav map and no BS signal is active
            // (bsLayerVisible==false), do NOT spuriously paint the BS card over
            // the map — that case only ever cold-opens for the map + speed badge.
            // clusterShowWhenReady() still re-checks isOpen()/stack/lock itself.
            boolean sustained;
            try {
                sustained = com.overdrive.app.surveillance.ClusterProjectionController
                        .getInstance().isSustainedHeld();
            } catch (Throwable t) {
                sustained = false;
            }
            // CONDITIONAL GATE on the cold-open re-show. The clause above deliberately
            // shows the card even when bsLayerVisible is already false (the signal
            // cleared during the 1-3.5s fission materialise), which would otherwise
            // walk straight past the speed/reverse gate the tick applies. Re-check it
            // here so a blocked gate can't be back-doored by the linger re-show. Skipped
            // for a camview session — this gate belongs to the blind-spot program only.
            // holding=true: this is a re-show for a session the driver was already
            // granted, so it must use the same widened threshold the tick used to allow
            // it — reading the raw bsLayerVisible (false by now, the tick hid the card)
            // would apply the narrow turn-ON bound and contradict that verdict.
            if (!camViewCluster && blindSpotEnabled) {
                String block = bsGateBlockReason(
                    com.overdrive.app.config.UnifiedConfigManager.getBlindSpot(), true);
                if (!block.isEmpty()) {
                    logger.info("onClusterProjectionReady: BS gate blocks show (" + block + ")");
                    // Don't leave a TRANSIENT (BS-only) projection open with nothing to
                    // paint — that's blanked gauges showing an empty screen for the whole
                    // linger. A sustained holder (nav map / camview) owns its own content,
                    // so only collapse the linger when nobody else is holding.
                    if (!sustained) {
                        try {
                            com.overdrive.app.surveillance.ClusterProjectionController
                                .getInstance().forceClose("bs-gate-blocked");
                        } catch (Throwable ignored) {}
                    }
                    return;
                }
            }
            if (bsLayerVisible || !sustained) clusterShowWhenReady();
        } catch (Throwable t) {
            logger.warn("onClusterProjectionReady failed: " + t.getMessage());
        } finally {
            bsLifecycleLock.unlock();
        }
    }

    /** Re-read the display target (after a UI/API target change) and re-apply.
     *  Flipping to head_unit force-closes any open cluster projection (restoring
     *  the gauges) and moves the layer back to layerStack 0. Flipping to cluster
     *  just re-resolves geometry; the projection opens lazily on the next signal. */
    public void retargetBlindSpot() {
        try {
            // Not while a CAMERA VIEW owns the shared lane: this rewrites bsTarget, can
            // force-close the cluster projection and re-stack the layer, and then pushes a
            // blind-spot rect. A blind-spot target switch used to yank a cluster camera view off
            // the fission stack, making it vanish (audit 2026-08). The new target is persisted, and
            // blind-spot re-resolves target + geometry when it next takes the lane.
            if (camViewOwnsLane()) return;
            com.overdrive.app.config.UnifiedConfigManager.forceReload();
            org.json.JSONObject bs = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
            String newTarget = (bs != null) ? bs.optString("target", "head_unit") : "head_unit";
            boolean wasCluster = isClusterTarget();
            bsTarget = newTarget;
            com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
            if (wasCluster && !isClusterTarget()) {
                // Leaving the cluster — restore gauges and move the card home.
                try { com.overdrive.app.surveillance.ClusterProjectionController.getInstance().forceClose("retarget-headunit"); } catch (Throwable ignored) {}
                if (layer != null) layer.setLayerStack(0);
            }
            resolveBsGeometry();
            if (layer != null && layer.isCreated() && bsLayerVisible && !isClusterTarget()) {
                int[] g = bsGeomRect; layer.setGeometry(g[0], g[1], g[2], g[3]);
            }
        } catch (Throwable t) {
            logger.warn("retargetBlindSpot failed: " + t.getMessage());
        }
    }

    /** Apply a changed cluster layout (size profile) LIVE. The daemon re-reads the
     *  profile from config on the next projection open, so to make a UI change take
     *  effect now we force-close any open projection + hide the card; the next turn
     *  signal reopens with the new profile (and onClusterProjectionReady re-resolves
     *  the stack/geometry). No-op when not on the cluster target. */
    public void relayoutCluster() {
        try {
            if (!isClusterTarget()) return;
            setBlindSpotVisible(false);
            com.overdrive.app.surveillance.ClusterProjectionController.getInstance().forceClose("relayout");
        } catch (Throwable t) {
            logger.warn("relayoutCluster failed: " + t.getMessage());
        }
    }

    /** Clear navMap.clusterMapActive so any ORPHANED parked cluster-map Activity
     *  self-finishes (it polls this flag). Called from the BS-open path when no
     *  sustained map holds the projection — a missed ClusterMapProjector.stop()
     *  finish would otherwise let the parked map paint under the partial BS card.
     *  Idempotent; safe no-op when no map Activity exists. Off the GL/turn loop's
     *  critical path is unnecessary (this is the 250ms turn thread, not GL). */
    private void dismissOrphanClusterMap() {
        try {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("clusterMapActive", false);
            com.overdrive.app.config.UnifiedConfigManager.updateValues("navMap", m);
            logger.info("BS open (no sustained map): dismissed any orphaned cluster-map Activity");
        } catch (Throwable t) {
            logger.debug("dismissOrphanClusterMap failed: " + t.getMessage());
        }
    }

    /** Start the daemon-side turn-trigger loop (idempotent). Reads turn lamps +
     *  debugPreview every BS_TURN_POLL_MS and drives the SurfaceControl layer:
     *  debugPreview → always show (calibration); else left/right indicator →
     *  view 7/8 + show, hidden after BS_OFF_DEBOUNCE_MS of no signal. */
    private void startBsTurnLoop() {
        if (bsTurnExec != null) return;
        // A FRESH session must never inherit a user-dismiss latch. stopBsTurnLoop already
        // clears it, but clearing here too makes it impossible for any ordering (a dismiss
        // that raced a teardown, a stop path that threw before the reset) to carry a stale
        // `true` into the new loop and silently suppress the first turn signal's card.
        // Cheap volatile write; the tick isn't scheduled yet, so nothing can observe a tear.
        bsUserDismissed = false;
        bsTurnExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BsTurnTrigger");
            t.setDaemon(true);
            return t;
        });
        bsTurnExec.scheduleWithFixedDelay(this::bsTurnTick, 0, BS_TURN_POLL_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS);
        logger.info("BS: turn-trigger loop started");
    }

    private void stopBsTurnLoop() {
        if (bsTurnExec != null) {
            bsTurnExec.shutdownNow();
            bsTurnExec = null;
        }
        bsLastTurnOnMs = 0L;
        bsLastLampOnMs = 0L;
        // Session state — clear it with the clocks. A dismiss is scoped to the display
        // session that was showing; carrying it across a disable→re-enable (or an
        // ACC cycle) would suppress the FIRST turn signal of the new session, with no ✕
        // on screen to explain why. The turn loop is the only consumer, and it is down
        // from here, so this cannot race a tick's show decision.
        bsUserDismissed = false;
        // NOTE: deliberately does NOT reset bsGateAllowed/bsGateReason. shutdownNow()
        // interrupts but does not AWAIT termination, so a tick already past its
        // blindSpotEnabled check would publish after the reset and re-latch a stale
        // BLOCK. The readers resolve "loop is down" from bsTurnExec==null instead, which
        // keeps the turn loop the single writer of those fields.
    }

    /**
     * Camera-view program driver, run from the shared arbiter tick when blind-spot
     * is NOT enabled. Applies the camview view mode (0-4, no blind-spot warp) +
     * geometry + target to the shared lane, shows it, and honours auto-hide. Mirrors
     * the BS show path (incl. the cluster-projection open + ACC-off safety gate) but
     * with camview's own program config. Called under NO lock (like bsTurnTick); the
     * scaler/layer snapshots are null-checked.
     */
    /**
     * Passive-APA camera selection: ask the OEM firmware to switch its composed
     * view to the requested camera. In {@code dilink4PassiveApaMode} the HAL feed
     * on preview port 0 is the firmware's own composed output and the GL shader
     * passes it through FULL-FRAME ({@code uApaMode > 0.5} — there are no
     * per-camera quadrants to slice), so front/rear/left/right selection can only
     * happen at the firmware, via the OEM {@code AUTO_VIDEO_BUTTON} view command.
     *
     * <p>Safe by the selector's own documented contract
     * ({@link com.overdrive.app.byd.BydDataCollector#setNativeCameraView}): the
     * broadcast "only sends the OEM view command; it never opens the panorama
     * application" — so no second camera pane appears; only the composed feed
     * OverDrive is already displaying changes camera.
     *
     * <p>Best-effort + fully detached (same discipline as
     * {@link #emitOverlayCloseState}): a short-lived {@code am broadcast} exec —
     * the daemon runs as shell/UID-2000 — drained on a daemon thread. Never throws.
     * No-op outside passive APA mode and for the all-4 mosaic (mode 0), which has
     * no OEM view code.
     */
    private void requestPassiveNativeView(int mode) {
        try {
            if (!com.overdrive.app.camera.CameraConfigResolver.isPassiveApaModeEnabled()) return;
            final int code;
            switch (mode) {
                case 1: code = com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_FRONT; break;
                case 2: case 8:
                        code = com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_RIGHT; break;
                case 3: code = com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_REAR;  break;
                case 4: case 7:
                        code = com.overdrive.app.byd.BydDataCollector.NATIVE_CAMERA_VIEW_LEFT;  break;
                default: return;   // 0 = all-4 mosaic — no OEM view code; leave the firmware view
            }
            Process p = new ProcessBuilder(
                    "am", "broadcast",
                    "-a", "android.intent.action.AUTO_VIDEO_BUTTON",
                    "--ei", "android.intent.extra.KEY_EVENT", Integer.toString(code))
                .redirectErrorStream(true).start();
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[256];
                try { while (is.read(buf) != -1) { /* discard */ } } catch (Throwable ignored) {}
            }, "passive-apa-view-broadcast");
            drain.setDaemon(true);
            drain.start();
            logger.info("CamView: passive-APA OEM view select mode=" + mode + " code=" + code);
        } catch (Throwable t) {
            logger.debug("requestPassiveNativeView(" + mode + ") failed: " + t.getMessage());
        }
    }

    private void camViewTick() {
        // ≥0 → the program transition below configured this camview mode; fire the
        // passive-APA OEM view select AFTER the lock is released (detached exec).
        int passiveSelectMode = -1;
        try {
            if (!camViewActive) return;
            // Auto-hide moved INSIDE the locked section below (the timeout claim
            // must be atomic with enableCamView's re-arm — see
            // tryClaimCamViewAutoHideLocked). Dispatch stays on a separate
            // short-lived thread: disableCamView() may call stopBsTurnLoop() →
            // bsTurnExec.shutdownNow(), which would interrupt THIS very thread
            // (camViewTick runs on bsTurnExec) and make teardownSharedLaneLocked's
            // GL-quiesce/scaler-release latch.await() calls throw immediately —
            // bypassing the EGL ordering barrier (EGL_BAD_NATIVE_WINDOW /
            // use-after-release risk).
            // LOCK-FOR-ACQUIRE (round-3 TOCTOU fix): the "camview" sustained-token
            // ACQUIRE below MUST be mutually exclusive with the locked RELEASE paths
            // (enableCamView retarget, disableCamView, arbiter BS-takeover). Without
            // the lock, a release could land between this tick's stale-snapshot read
            // and its acquire, and the tick would immediately re-acquire an orphaned
            // token (projection pinned, gauges blanked). Hold bsLifecycleLock across
            // the whole decide+acquire+show. tryLock (not lock) so the 250ms tick never
            // blocks behind a teardown — it just skips this tick and retries in 250ms.
            // Lock is reentrant + only wraps cheap CPU-side ops + clusterShowWhenReady
            // (which re-enters the same lock), so no GL-block / deadlock.
            if (!bsLifecycleLock.tryLock()) return;
            try {
                // Re-check under the lock: a concurrent disableCamView may have flipped
                // camViewActive false (and released the token) since the top-of-tick read.
                if (!camViewActive) return;
                // Atomic auto-hide claim (deadline check + one-shot CAS + session
                // capture in one locked step). tryLock miss above just retries in
                // 250ms — the deadline is wall-clock, not tick-aligned.
                long expiredSession = tryClaimCamViewAutoHideLocked(
                    android.os.SystemClock.elapsedRealtime());
                if (expiredSession >= 0) {
                    Thread t = new Thread(() -> disableCamViewForSession(expiredSession),
                        "CamViewAutoHide");
                    t.setDaemon(true);
                    t.start();
                    return;
                }
                boolean cluster = isCamViewClusterTarget();

                // Configure the shared scaler for the camview program on first entry /
                // after a program handover (transition-only, not every tick).
                if (laneProgram != PROG_CAMVIEW) {
                    // Point the shared geometry fields at the camview rect + target so the
                    // existing show helpers (setBlindSpotVisible / clusterShowWhenReady)
                    // place the layer where camview wants it. FIRST, because it refreshes
                    // camViewCorner, which the composite branch below aligns rotation to.
                    bsTarget = camViewTarget;
                    resolveCamViewGeometry();
                    bsGeomRect = camViewGeomRect;
                    com.overdrive.app.streaming.GpuStreamScaler s = bsScaler;
                    if (s != null) {
                        s.setViewMode(camViewMode);
                        if (camViewMode == 7 || camViewMode == 8) {
                            // Views 7/8 sample the blind-spot stitch path, so they need the
                            // dialed calibration + merge mode + fisheye — WITHOUT it the
                            // stitch runs on identity odParams and mis-projects. Shares the
                            // blindspot.* settings by design: tune the card, the composite
                            // camera view follows. Rotation comes from that same per-side
                            // config rather than 0 (a 90° card angle applies here too),
                            // resolved against THIS view's side — not the card's.
                            // forCamView=true: this scaler is rendering the CAMERA VIEW, so a
                            // rotated composite must pillarbox against camViewCorner.
                            applyBlindSpotCalibration(s, camViewMode, true);
                        } else {
                            // Plain camera view: modes 0-4 do NOT sample the stitch path, so
                            // no calibration/warp is applied (clean single-cam/mosaic). Reset
                            // the scaler-wide ROTATION uniform — blind-spot sets it for its
                            // side/rear card, and inheriting that renders a plain camera view
                            // sideways or upside-down.
                            s.setContentRotation(0, 0);
                        }
                    }
                    laneProgram = PROG_CAMVIEW;
                    // Passive APA: the shader shows the firmware's full-frame feed, so
                    // camera selection must be forwarded to the OEM firmware. Dispatch
                    // deferred past the unlock below. Transition-only (not per tick):
                    // every show forces a transition via laneProgram=PROG_NONE, so a
                    // camera change always lands here exactly once.
                    passiveSelectMode = camViewMode;
                }

                if (cluster) {
                    // Same ACC-off safety gate as BS: never (re)open the projection while
                    // ACC is authoritatively off (the ACC-off edge force-closed it to
                    // restore the gauges; re-opening here would blank them again).
                    if (com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
                            && !com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                        return;
                    }
                    com.overdrive.app.surveillance.ClusterProjectionController c =
                        com.overdrive.app.surveillance.ClusterProjectionController.getInstance();
                    // Camera-view is a SUSTAINED consumer (stays until hidden), unlike the
                    // transient turn-signal session. acquireSustained("camview") holds the
                    // projection open under its OWN token (independent of the nav map's
                    // "map" token) AND cancels the 90s max-cap. Idempotent: re-arms the hold
                    // each tick (cheap no-op once held). Released in disableCamView / arbiter.
                    c.acquireSustained("camview");
                    bsLayerVisible = true;   // intent
                    // The fps ramp is edge-detected inside setBlindSpotVisible, which this
                    // raw write bypasses — so a CLUSTER camview under an enabled-but-idle
                    // blind-spot never triggered a reconcile and rendered at the BS idle
                    // rate (~1fps, a frozen image). Notify explicitly; it is edge-guarded,
                    // so the per-tick re-assert costs nothing. Also keeps
                    // bsLastNotifiedVisible in sync so the later hide still fires.
                    fireBsVisibilityChanged();
                    if (c.isReady()) clusterShowWhenReady();
                } else {
                    if (!bsLayerVisible) setBlindSpotVisible(true);
                }
            } finally {
                bsLifecycleLock.unlock();
                // Deferred past the unlock (detached exec must never hold the lane
                // lock). No-op outside passive APA mode / for the all-4 mosaic.
                if (passiveSelectMode >= 0) requestPassiveNativeView(passiveSelectMode);
            }
        } catch (Throwable t) {
            logger.debug("camViewTick: " + t.getMessage());
        }
    }

    private void bsTurnTick() {
        try {
            // ── Arbiter (Option A, blind-spot priority) ──────────────────────────
            // The single shared lane serves whichever program is active. Blind-spot
            // wins whenever it is enabled: its turn-signal / debug-preview logic runs
            // below unchanged, and it OWNS the layer while enabled. The camera-view
            // program only drives the lane when blind-spot is NOT enabled. (When BS is
            // enabled but its card is hidden — no turn signal — the lane simply stays
            // hidden rather than showing camview, so a turn signal is never masked and
            // there is no 250ms tug-of-war over viewMode/geometry.)
            if (!blindSpotEnabled) {
                if (camViewActive) { camViewTick(); }
                return;
            }
            // Config + turn-signal read hoisted ABOVE the program re-assert below: the
            // camview yield needs both to decide whether blind-spot wants the lane at
            // all, and claiming the lane first is what caused the 4Hz hide/show blink.
            org.json.JSONObject bs = com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
            // Turn-gated: daemon owns the light HAL. readTurnNow packs bit0=L,bit1=R.
            int packed = com.overdrive.app.byd.BydDataCollector.getInstance().readTurnNow();
            boolean leftOn = packed > 0 && (packed & 0x1) != 0;
            boolean rightOn = packed > 0 && (packed & 0x2) != 0;
            int side = (leftOn && !rightOn) ? 7 : (rightOn && !leftOn) ? 8 : 0;  // both/none → hide
            long now = android.os.SystemClock.elapsedRealtime();

            // ── Yield to camera-view when blind-spot has NOTHING to show ────────────
            // Blind-spot priority (below) is about not MASKING a turn signal, not about
            // owning the lane while idle. An explicit camera-view request — from an
            // automation, a key mapping, or /api/camview/show — must still render
            // whenever BS isn't actually using the lane: no turn signal, or a signal the
            // conditional gate is suppressing. Without this, enabling blind-spot silently
            // kills "show camera view" (enableCamView never shows the layer itself; it
            // relies entirely on camViewTick), and turning ON "hide in reverse" made that
            // visible because the reverse-camera case is exactly when a user reaches for
            // the rear view.
            //
            // THIS MUST PRECEDE the `laneProgram != PROG_BS` restore. When the restore ran
            // first it set laneProgram=PROG_BS unconditionally, which re-armed the
            // hand-over-hidden guard below (setBlindSpotVisible(false) → layer.hide()) and
            // then camViewTick's own `laneProgram != PROG_CAMVIEW` reconfig showed it again
            // — a hide/show SurfaceControl pair EVERY 250ms tick, forever. That was the
            // "camera view blinks while blind-spot is enabled" defect, plus its two
            // side effects: an 8Hz fps-ramp/PASS-1C gate flip, and a `dumpsys display`
            // shell-out per tick on this thread (camview's cluster reconfig →
            // resolveCamViewGeometry → clusterDisplaySize), which the I9 rule forbids.
            // Deciding to yield BEFORE claiming the lane makes the handover one-shot
            // again: the guard below fires on the real BS→camview edge and nothing
            // re-arms it. The reverse preemption is unchanged — the moment a signal
            // arrives AND the gate allows it, bsWantsLane goes true, we stop yielding and
            // the restore re-asserts the BS program on that same tick.
            //
            // Skipping the geometry/rotation maintenance below is REQUIRED on this path,
            // not just cheaper: bsGeomRect and the scaler's rotation uniform are SHARED
            // fields that camview owns while it holds the lane, so the orientation repair
            // and the AUTO-rotation re-apply would clobber the live camera view.
            if (camViewActive) {
                // AUTO-HIDE IS EVALUATED HERE, not only inside camViewTick. The deadline
                // check used to live solely in that tick, which runs only when camview
                // actually gets the lane — so a camview requested while blind-spot held it
                // (debugPreview on, or a sustained turn signal) never aged out: it stayed
                // "active" indefinitely, pinning the camera at the camview fps and leaving
                // an orphaned ✕ on screen with nothing behind it. Checking on every tick
                // makes the timeout mean wall-clock time, independent of lane ownership.
                // Atomic auto-hide claim, same discipline as camViewTick's: the
                // deadline check, the one-shot CAS and the session capture happen in
                // ONE step under bsLifecycleLock, so the claim can never consume a
                // deadline a concurrent show just re-armed, nor capture the new
                // show's session. tryLock (reentrant-safe): a miss retries in 250ms.
                if (camViewHideAtMs > 0 && now >= camViewHideAtMs
                        && bsLifecycleLock.tryLock()) {
                    long cvExpiredSession;
                    try {
                        cvExpiredSession = tryClaimCamViewAutoHideLocked(now);
                    } finally {
                        bsLifecycleLock.unlock();
                    }
                    if (cvExpiredSession >= 0) {
                        Thread t = new Thread(
                            () -> disableCamViewForSession(cvExpiredSession),
                            "CamViewAutoHide");
                        t.setDaemon(true);
                        t.start();
                        return;   // next tick sees camViewActive=false and proceeds
                    }
                }
                // bsUserDismissed must be folded in HERE too, not only at the show
                // decision below: after the ✕ tap the card will not be shown for the rest
                // of this session, so blind-spot does not actually want the lane. Without
                // it a held indicator kept bsWantsLane true, camViewTick() was never
                // reached, and the ✕ the user pressed to get their camera view back
                // blanked the lane until the stalk was released.
                boolean bsWantsLane = bs.optBoolean("debugPreview", false)
                        || (side != 0 && !bsUserDismissed
                            && bsGateBlockReason(bs, bsLayerVisible).isEmpty());
                if (!bsWantsLane) {
                    // Publish the gate verdict on EVERY yielding tick, not just signalling
                    // ones: /api/bs/status reads these fields whenever the loop is up and BS
                    // is enabled, so skipping the no-signal case froze the last verdict
                    // ("below min 30") for as long as a camera view stayed up.
                    bsEvalConditionalGate(bs);
                    // SESSION BOOKKEEPING STILL RUNS. This branch returns early, skipping
                    // the turn/hide logic below — but that logic also maintains state keyed
                    // to the PHYSICAL STALK, which is independent of who owns the lane.
                    // Starving it while a camera view is up would mean: the orphan-map
                    // latch never re-arms (so the next BS cluster session skips the
                    // parked-map dismiss it exists to guarantee), and notifySignalCleared()
                    // never fires (so a 90s max-cap lockout could never lift). Both are
                    // cheap, idempotent, and correct to do here.
                    if (side != 0) bsLastLampOnMs = now;
                    if (side == 0) {
                        // BLIND-SPOT's OWN target, read from config — NOT isClusterTarget().
                        // That helper reads the SHARED bsTarget field, which camViewTick
                        // overwrites with camViewTarget on the tick it takes the lane. So on
                        // every yielding tick after the handover it answers "is CAMVIEW on the
                        // cluster", and both answers are wrong here: a cluster BS + head-unit
                        // camview would SKIP the clear, stranding a 90s max-cap lockout that
                        // only a real indicator-off can lift (next signal → no card at all),
                        // and the inverse would poke a controller this BS never uses. Plain
                        // string read off the config already in hand — no panel query.
                        if ("cluster".equals(bs.optString("target", "head_unit"))) {
                            try {
                                com.overdrive.app.surveillance.ClusterProjectionController
                                    .getInstance().notifySignalCleared();
                            } catch (Throwable ignored) {}
                        }
                        if ((now - bsLastLampOnMs) >= BS_OFF_DEBOUNCE_MS) {
                            bsDismissedOrphanMap = false;
                            // Clear the user-dismiss latch on the SAME genuine session end.
                            // CRITICAL: this branch returns before the hide-branch clear
                            // below ever runs, so while a camera view is active (the yield
                            // path) that clear is starved — and without this line one ✕ tap
                            // would suppress the blind-spot card across ALL later turn
                            // signals, making a safety-view dismiss permanently sticky.
                            // Same debounce + !signalOn condition as the orphan-map latch
                            // beside it (a blink off-phase must not clear it mid-session).
                            bsUserDismissed = false;
                        }
                    }
                    // Hand over a HIDDEN layer when blind-spot still owns it: camViewTick
                    // reconfigures only on a program change, so a layer left shown with BS
                    // geometry would flash the BS rect for one tick before camview's rect
                    // lands. Gated on bsLayerVisible so the common path (BS already hidden)
                    // costs nothing and doesn't churn the fps-ramp edge — camViewTick's own
                    // setBlindSpotVisible(true) supplies the off→on edge RMM needs. With the
                    // yield now decided BEFORE the restore, laneProgram is still
                    // PROG_CAMVIEW on every tick after the first, so this fires ONCE per
                    // real handover instead of every tick.
                    if (laneProgram == PROG_BS && bsLayerVisible) {
                        setBlindSpotVisible(false);
                        // Release the TRANSIENT cluster projection BS opened for this
                        // session. The linger close normally happens in the hide branch
                        // below, which this return skips — so without it a BS-opened
                        // projection stayed up with the gauges blanked, and if camview is
                        // head-unit-targeted nothing would paint on the cluster or ever
                        // close it. camViewTick re-acquires its own sustained hold on the
                        // next line if it wants the cluster, and a sustained holder makes
                        // maybeLingerClose a no-op — so this cannot close a projection
                        // camview still needs.
                        if (isClusterTarget()) {
                            try {
                                com.overdrive.app.surveillance.ClusterProjectionController
                                    .getInstance().requestCloseLingered();
                            } catch (Throwable ignored) {}
                        }
                    }
                    camViewTick();
                    return;
                }
            }
            // Blind-spot is enabled and WANTS the lane. If it was just handed the lane
            // back from camview (laneProgram != PROG_BS), re-assert the BS program.
            if (laneProgram != PROG_BS) {
                // FULL program restore. camViewTick clobbers the SHARED program fields —
                // bsTarget, bsGeomRect and the scaler's rotation uniform — so restoring
                // only viewMode+calibration left blind-spot running on camview's target
                // and rect. Concretely: a cluster-targeted camview left bsTarget="cluster"
                // behind, so the very next turn signal made a HEAD-UNIT-configured
                // blind-spot open the OEM cluster projection and blank the driver's
                // gauges, painted at camview's cluster rect. It was also STICKY — the
                // head-unit orientation-repair path that would have called
                // resolveBsGeometry() is itself gated on !cluster, so it never ran.
                // Restore bsTarget from config FIRST and CHEAPLY — a plain string read, no
                // panel query. Doing this via resolveBsGeometry() would be wrong here: for
                // a cluster target that helper reaches clusterDisplaySize() → a `dumpsys
                // display` shell-out, which per the I9 rule may run ONLY on projThread,
                // never on this 250ms turn loop.
                org.json.JSONObject bsCfg =
                    com.overdrive.app.config.UnifiedConfigManager.getBlindSpot();
                bsTarget = bsCfg.optString("target", "head_unit");
                // Now the rect + rotation. Geometry is recomputed only for the HEAD-UNIT
                // target, whose displaySize() is a cheap DisplayManager read; the cluster
                // is a fixed 1920×720 that never rotates, and its rect is re-resolved on
                // the projection-ready callback (projThread) where the dumpsys is legal.
                // Either way the ROTATION uniform is re-applied, since camViewTick zeroes
                // it and a stale 0 would render a side/rear BS card unrotated.
                if (!isClusterTarget()) {
                    resolveBsGeometry();
                } else {
                    // CLUSTER: cannot call resolveBsGeometry (dumpsys), and cannot rely on
                    // onClusterProjectionReady to re-resolve later either — when a cluster
                    // camview already holds the projection OPEN, BS's requestOpen() returns
                    // early (ST_OPEN) so that callback never fires, and the card would paint
                    // at camview's rect for the whole session. Recompute the rect here from
                    // the panel size cached by the last full resolve, the same dumpsys-free
                    // idiom setBlindSpotViewMode uses for a turn-side flip.
                    bsRotationDeg = resolveBsRotation(bsCfg, bsViewMode);
                    org.json.JSONObject gCl = currentGeometryObj();
                    boolean rectRestored = false;
                    // Panel dims for the pure-math clamps below. enableBlindSpot always
                    // runs resolveBsGeometry (which seeds these), so the cache is warm on
                    // every arm path; fall back to the fixed cluster panel anyway so a
                    // cold cache can never leave camview's rect in place — the same
                    // 1920×720 clusterDisplaySize itself defaults to, no display query.
                    int panW = bsLastPanelW > 0 ? bsLastPanelW : 1920;
                    int panH = bsLastPanelH > 0 ? bsLastPanelH : 720;
                    if (gCl != null && gCl.has("sizePct")) {
                        bsSizePct = gCl.optInt("sizePct", bsSizePct);
                        bsCorner = resolveBsCorner(gCl);
                        int[] pr = presetRect(new android.graphics.Point(panW, panH));
                        if (pr != null) {
                            // CLAMP, as resolveBsGeometry does after its own presetRect:
                            // presetRect derives h from w at 4:3, so on the short 1920×720
                            // cluster any sizePct above 50 overflows the panel height (the
                            // UI's cluster default is 80 → 1536×1152). Unclamped, the
                            // restored card paints oversized and bottom-clipped over the
                            // gauges, and differs from the same config's armed geometry.
                            int[] cr = clampBsRectTo(pr[0], pr[1], pr[2], pr[3], panW, panH);
                            bsGeomRect = new int[]{cr[0], cr[1], cr[2], cr[3]};
                            rectRestored = true;
                        }
                    } else if (gCl != null && gCl.has("x") && gCl.has("w")) {
                        // ABSOLUTE cluster geometry. Unlike the turn-side-flip path this
                        // must NOT be "left alone": bsGeomRect currently holds CAMVIEW's
                        // rect, so leaving it would paint the safety card at the camera
                        // view's position for the whole signal session (and the
                        // orientation repair that would fix it is head-unit-only).
                        int[] ar = clampBsRectTo(gCl.optInt("x"), gCl.optInt("y"),
                                                 gCl.optInt("w"), gCl.optInt("h"),
                                                 panW, panH);
                        bsGeomRect = new int[]{ar[0], ar[1], ar[2], ar[3]};
                        rectRestored = true;
                    }
                    if (!rectRestored) {
                        // NOTHING persisted for the cluster (the common case — the user
                        // never saved a cluster preset). Fall back to the same default
                        // card resolveBsGeometry would compute, so the rect is BS's own
                        // rather than the camera view's.
                        int defW = Math.max(320, (int) (panW * 0.80));
                        int defH = (int) (defW * (double) sharedLaneHeight / BS_WIDTH);
                        int[] dr = clampBsRectTo(panW - defW - 24, 24, defW, defH,
                                                 panW, panH);
                        bsGeomRect = new int[]{dr[0], dr[1], dr[2], dr[3]};
                    }
                    // No setContentRotation here: applyBlindSpotCalibration() below is the
                    // authoritative push (same angle, and it resolves alignX from the
                    // config rather than the bsCorner field), so a call here would only be
                    // overwritten three lines later.
                }
                com.overdrive.app.streaming.GpuStreamScaler s = bsScaler;
                if (s != null) { s.setViewMode(bsViewMode); applyBlindSpotCalibration(s); }
                // Blind-spot has priority and now OWNS the lane + projection lifecycle.
                // If camera-view was holding a sustained cluster projection, release its
                // token so it can't keep the projection pinned open (max-cap disarmed)
                // while masked — BS manages the projection via its own transient path
                // from here. Idempotent (no-op if camview never held). This closes the
                // "camview stuck sustained → BS transient gauge-restore disarmed" gap.
                try { com.overdrive.app.surveillance.ClusterProjectionController.getInstance().releaseSustained("camview"); } catch (Throwable ignored) {}
                // PUSH the restored rect to the LAYER, not just the field. camview can hand
                // back a layer that is still SHOWN (its own setBlindSpotVisible(true)), and
                // every setGeometry below is gated on the layer being hidden or the side
                // changing: the show branch only calls setBlindSpotVisible when
                // !bsLayerVisible, setBlindSpotViewMode only fires when side != bsViewMode,
                // and clusterShowWhenReady is wrapped in !layer.isShown(). So preempting a
                // camview with the SAME side already selected left the blind-spot card
                // painting at CAMVIEW's rect for the whole signal session.
                // ONLY for a real camview handover (PROG_CAMVIEW). laneProgram is also
                // PROG_NONE — enableCamView sets it while a BS card may still be SHOWING,
                // and on that tick BS often still wants the lane (so no yield happens) and
                // lands here. Repainting then is pointless (the rect is already BS's) and
                // the cluster branch would blank a live safety card for a projThread hop.
                // Every other PROG_NONE route already clears bsLayerVisible first.
                if (bsLayerVisible && laneProgram == PROG_CAMVIEW) {
                    if (!isClusterTarget()) {
                        // Re-asserts layerStack 0 + the restored rect in one transaction.
                        // Idempotent, and the fps-ramp edge is already latched true.
                        setBlindSpotVisible(true);
                    } else {
                        // CLUSTER: the inherited layer may be on camview's stack, and this
                        // thread may not resolve the live one (dumpsys → I9). Drop to hidden
                        // and let the gated show below re-place it on the correct stack.
                        setBlindSpotVisible(false);
                    }
                }
                laneProgram = PROG_BS;
            }
            boolean cluster = isClusterTarget();
            // Orientation change (head-unit only — the cluster is a fixed 1920×720
            // and never rotates). If the panel rotated (1920×1080 ↔ 1080×1920), the
            // px rect from the old orientation is wrong. Recompute from the preset
            // against the live panel and re-apply. Cheap check (one displaySize).
            // For the cluster target this is skipped (panel is constant), and the
            // cluster metrics aren't valid until the projection is open anyway.
            if (!cluster) {
                try {
                    android.content.Context ctx = savedContext;
                    if (ctx == null) ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                    if (ctx != null) {
                        android.graphics.Point panel =
                            com.overdrive.app.surveillance.BsNativeLayer.displaySize(ctx);
                        if (panel.x != bsLastPanelW || panel.y != bsLastPanelH) {
                            resolveBsGeometry();   // updates bsGeomRect + bsLastPanel*
                            if (bsLayerVisible && bsLayer != null) {
                                int[] g = bsGeomRect;
                                bsLayer.setGeometry(g[0], g[1], g[2], g[3]);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // AUTO rotation (direction-of-travel): when rotation="auto" the effective
            // angle depends on gear (forward=base, reverse=base+180), so it must be
            // re-evaluated live rather than only on a settings write. Re-resolve
            // cheaply each tick and re-apply only on change. Rotation is applied in the
            // GL vertex shader (bsScaler.setContentRotation), NOT the SurfaceControl
            // layer (a 90/270 LAYER transform blanks the card on this firmware — issue
            // #164), so the change is a single cheap uniform swap: no setGeometry, no
            // panel query, no dumpsys — keeping this cluster-safe on the 250ms loop.
            // The dest rect is always the buffer's 4:3, so it never needs recomputing
            // on a rotation change. No-op churn when want == bsRotationDeg (steady state).
            {
                // PER-SIDE: resolve for the CURRENT view (7=left/8=right). A side
                // switch above (setBlindSpotViewMode) already re-applied the new side's
                // angle and synced bsRotationDeg, so here we only catch the AUTO gear
                // flip (forward↔reverse) for the side we're on — no double-apply churn.
                int wantRot = resolveBsRotation(bs, bsViewMode);
                if (wantRot != bsRotationDeg) {
                    bsRotationDeg = wantRot;
                    com.overdrive.app.streaming.GpuStreamScaler bss = bsScaler;
                    // bsCorner reflects the current side (setBlindSpotViewMode's
                    // reposition runs before this on a side change), so align the
                    // rotated card to that side's edge.
                    if (bss != null) bss.setContentRotation(wantRot, bsRotationAlignX());
                }
            }
            boolean debugPreview = bs.optBoolean("debugPreview", false);
            if (debugPreview) {
                int want = bs.optInt("debugView", 7) == 8 ? 8 : 7;
                if (want != bsViewMode) setBlindSpotViewMode(want);
                if (cluster) {
                    // ACC-off gate (same as the turn-signal branch): never (re)open the
                    // cluster projection while ACC is authoritatively off, so the ACC-off
                    // force-close that restored the gauges isn't undone by a left-on
                    // calibration preview on the next tick.
                    if (com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
                            && !com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                        return;
                    }
                    // Calibration on the cluster: keep the projection open while
                    // previewing; show only once the cluster display is present
                    // (onClusterProjectionReady also shows it on the ready edge).
                    com.overdrive.app.surveillance.ClusterProjectionController c =
                        com.overdrive.app.surveillance.ClusterProjectionController.getInstance();
                    c.requestOpen(); c.noteSignal(); c.requestCloseLingered();
                    bsLayerVisible = true;   // intent
                    if (c.isReady()) clusterShowWhenReady();   // desync-proof show
                } else {
                    if (!bsLayerVisible) setBlindSpotVisible(true);
                }
                return;
            }
            // Conditional display (speed window / reverse). Evaluated on EVERY tick, so
            // the verdict published for /api/bs/status can never describe a condition
            // that has already passed. A blocked gate suppresses the display, but it is
            // deliberately kept DISTINCT from side==0: the no-indicator branch below
            // also means "the driver let go of the stalk", and two things there are
            // keyed to that meaning rather than to "card not shown" —
            //   * notifySignalCleared() lifts the 90s max-cap lockout, whose whole
            //     contract is "only a real indicator-off lifts it, so a STUCK signal
            //     stays capped". A gate block can return to allowed with no driver
            //     action, so forging a clear here would let a stuck indicator re-blank
            //     the gauges every time the speed crossed a bound.
            //   * bsDismissedOrphanMap re-arms per SIGNAL SESSION; re-arming it on a
            //     gate blip would split one session into many and re-issue the
            //     full-JSON navMap config write on this 250ms thread each time.
            // So gateOk only gates the SHOW, and the hide runs through the same
            // debounced path without claiming the signal ended.
            //
            // bsLastTurnOnMs is stamped only on ticks that actually DISPLAY (below), not
            // on every signal-on tick: it is the "last tick that wanted the card up"
            // clock the BS_OFF_DEBOUNCE_MS hide is measured from. Stamping it while the
            // gate blocks would keep re-arming the debounce for as long as the indicator
            // was held, so a blocked card could never hide at all.
            boolean gateOk = bsEvalConditionalGate(bs);
            boolean signalOn = (side != 0);
            // Last tick the LAMP was lit, independent of whether we displayed. Used to
            // tell a blink off-phase (and a transient readTurnNow miss) from a genuine
            // stalk release even while the gate is suppressing the card — the display
            // clock below can't, because a gate block freezes it.
            if (signalOn) bsLastLampOnMs = now;
            if (!gateOk) side = 0;
            // USER DISMISS (floating ✕): suppress the show for the REST OF THIS SESSION.
            // Folded in here with the gate rather than earlier so it only affects the
            // SHOW — every "the stalk was released" behaviour below (notifySignalCleared
            // lifting the 90s max-cap, the orphan-map latch re-arm) stays keyed to the
            // physical signal, exactly as the gate block is. Cleared in the else branch
            // once the session genuinely ends, so the next signal shows the card again.
            if (bsUserDismissed) side = 0;
            if (side != 0) {
                bsLastTurnOnMs = now;
                if (side != bsViewMode) setBlindSpotViewMode(side);
                if (cluster) {
                    // ACC-off gate: when ACC is AUTHORITATIVELY off, do NOT (re)open the
                    // cluster projection. The ACC-off edge (AccMonitor.notifyAccEdge)
                    // force-closes it to restore the gauges immediately; without this
                    // guard the still-running 250ms loop would re-open it on the very
                    // next tick if the indicator is mid-blink at ACC-off — FLASHING the
                    // gauges. Gated on isAccStateAuthoritative() so an unknown/default
                    // state (daemon just restarted) never wrongly suppresses projection.
                    if (com.overdrive.app.monitor.AccMonitor.isAccStateAuthoritative()
                            && !com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                        return;
                    }
                    // Lazy-open the OEM cluster projection on the first signal; keep it
                    // open across the blink phase. Show the layer only once the cluster
                    // display is present (never composite stack-1 onto nothing).
                    com.overdrive.app.surveillance.ClusterProjectionController c =
                        com.overdrive.app.surveillance.ClusterProjectionController.getInstance();
                    // Belt-and-braces for the map-leak fix: if NO sustained map holds
                    // the projection, this BS open must not re-surface an orphaned
                    // parked cluster-map Activity. Dismiss it once per signal session
                    // (idempotent UCM write; gated on !sustained so a legitimate
                    // map-on-cluster session — which holds the projection — is never
                    // dismissed). The Activity self-finishes on its ~500ms poll.
                    if (!bsDismissedOrphanMap && !c.isSustainedHeld()) {
                        bsDismissedOrphanMap = true;
                        dismissOrphanClusterMap();
                    }
                    c.requestOpen(); c.noteSignal(); c.requestCloseLingered();
                    bsLayerVisible = true;   // intent
                    if (c.isReady()) clusterShowWhenReady();   // desync-proof show
                } else {
                    if (!bsLayerVisible) setBlindSpotVisible(true);
                }
            } else {
                // Not showing. Lift any max-cap lockout on the first GENUINELY-clear
                // tick so a fresh indicator after the cap re-opens normally (a real
                // blink reaches here between flashes; a forgotten signal never does,
                // keeping the cap effective). Keyed to !signalOn, NOT to side==0: a
                // gate-blocked tick has the indicator physically ON, and treating that
                // as a clear would hand a stuck signal an unlimited supply of fresh
                // 90s gauge-blanking windows.
                if (cluster && !signalOn) {
                    com.overdrive.app.surveillance.ClusterProjectionController.getInstance()
                        .notifySignalCleared();
                }
                // Re-arm the orphan-dismiss latch once a signal SESSION has really ended.
                // Two conditions, and both are load-bearing:
                //   !signalOn        — a gate block leaves the indicator physically ON, and
                //                      re-arming then would re-issue the navMap write every
                //                      time the speed crossed a bound.
                //   past the debounce — the lamp reads 0 on every blink OFF-PHASE (~1.5Hz;
                //                      that is precisely why BS_OFF_DEBOUNCE_MS exists), and
                //                      readTurnNow() also returns -1 on a transient SDK
                //                      miss. Keying on the raw !signalOn alone turned ONE
                //                      full-JSON config write per session into one per
                //                      blink — on this 250ms thread, under the cross-process
                //                      config lock, invalidating every UID's config cache.
                // Deliberately NOT nested in the hide block below: that block additionally
                // requires bsLayerVisible, and a gate block hides the card mid-session, so
                // the later genuine release would find it already false and never re-arm —
                // leaving the latch consumed and the next session skipping the dismiss.
                // Idempotent: assigning false when already false is free.
                // Measured against bsLastLampOnMs (last tick the LAMP was lit), not
                // bsLastTurnOnMs (last tick that DISPLAYED). While the gate blocks, the
                // display clock is frozen, so a blink gap 800ms later would look like a
                // session end even with the stalk still held — re-arming mid-session and
                // costing an extra write per bound-crossing.
                boolean sessionEnded = !signalOn && (now - bsLastLampOnMs) >= BS_OFF_DEBOUNCE_MS;
                if (sessionEnded) bsDismissedOrphanMap = false;
                // Release the user's ✕ dismiss on the same genuine session end. This is
                // what keeps a dismiss NON-STICKY: the next turn signal shows the card
                // again, so one tap can never leave a safety view suppressed. Keyed to
                // sessionEnded (not bare !signalOn) for the same reason the latch above
                // is — the lamp reads 0 on every blink off-phase, which would otherwise
                // clear the dismiss mid-session and pop the card straight back up.
                if (sessionEnded) bsUserDismissed = false;
                if (bsLayerVisible && (now - bsLastTurnOnMs) >= BS_OFF_DEBOUNCE_MS) {
                    setBlindSpotVisible(false);
                    if (cluster) {
                        // Hide the card now; restore the gauges after the linger window
                        // (rides brief blink gaps without re-paying the open latency).
                        com.overdrive.app.surveillance.ClusterProjectionController.getInstance()
                            .requestCloseLingered();
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("bsTurnTick: " + t.getMessage());
        } finally {
            // Reconcile the floating ✕ from the authoritative post-tick state, on EVERY
            // exit path (show, hide, dismiss, camview yield, early return). Edge-detected
            // so it only broadcasts on a real transition. Lock-free here (the tick holds
            // no lock at this point), matching emitBsCardState's off-lock contract.
            fireBsCardStateChanged();
        }
    }

    public void disableBlindSpot() {
        bsLifecycleLock.lock();
        try {
            if (!blindSpotEnabled) return;
            logger.info("BS: disabling blind-spot lane...");
            // If camera-view will KEEP the lane (and possibly the cluster projection),
            // we must NOT force-close the projection or stop the shared driver — that
            // would blank an active camera-view on the cluster. Only do the full
            // gauge-restore + teardown when neither program needs the lane.
            boolean camviewKeepsLane = camViewActive;
            // SAFETY: if a cluster projection is open AND no other program keeps it,
            // restore the gauges FIRST, before any teardown. Gated on isClusterTarget()
            // so a head-unit-only user never even constructs the controller.
            if (isClusterTarget() && !camviewKeepsLane) {
                try { com.overdrive.app.surveillance.ClusterProjectionController.getInstance().forceClose("bs-disabled"); } catch (Throwable ignored) {}
            }
            blindSpotEnabled = false;

            if (!camviewKeepsLane) {
                stopBsTurnLoop();   // stop the daemon turn-trigger before teardown
                com.overdrive.app.camera.PanoramicCameraGpu cam = camera;
                if (cam != null) cam.setBsLayerVisible(false);
                bsLayerVisible = false;
                fireBsVisibilityChanged();   // BS gone — let RMM re-reconcile camera profile
                teardownSharedLaneLocked();
                laneProgram = PROG_NONE;
            } else {
                // Hand the lane to camview: keep the scaler/layer AND the running
                // driver (do NOT stop/restart it — it is the shared arbiter). Hide the
                // BS image now; force a reconfig so the next tick applies the camview
                // program. fireBsVisibilityChanged still runs so RMM re-reconciles.
                bsLayerVisible = false;
                fireBsVisibilityChanged();
                laneProgram = PROG_NONE;   // force camview reconfig on next tick
                logger.info("BS: disabled but lane retained for camera-view");
            }

            logger.info("BS: NATIVE blind-spot lane disabled");
        } finally {
            bsLifecycleLock.unlock();
            // Retract the ✕ if the card was showing when the feature was disabled. The
            // turn loop is stopped in the teardown branch, so its finally-block reconcile
            // won't run again — fire the hide edge here (outside the lock, like camview's
            // emitCamViewState). No-op edge if the card wasn't showing. blindSpotEnabled
            // is already false, so isBlindSpotCardShowing() is false → broadcasts closed.
            fireBsCardStateChanged();
        }
    }

    /**
     * Physically tear down the shared native lane (detach from PASS 1C, quiesce the
     * render loop, GL-release the scaler's EGLSurface, then release the SurfaceControl
     * layer — in that Adreno-mandated order). MUST be called holding
     * {@link #bsLifecycleLock} and ONLY when NEITHER program needs the lane. Extracted
     * verbatim from disableBlindSpot so both disable paths share the proven teardown.
     */
    private void teardownSharedLaneLocked() {
        // Detach from render loop FIRST so PASS 1C stops blitting the
        // about-to-be-released scaler.
        if (camera != null) camera.clearBsStreamingComponents();

        // FIX BS-RC-002: clearBsStreamingComponents() nulls the camera's volatile
        // bsStreamScaler, but a render-loop iteration that already snapshotted it
        // non-null at the top of PASS 1C will still call drawFrame() this frame.
        // Post a no-op barrier to the serial GL handler and wait: it can only run
        // AFTER any in-flight render iteration completed, so every subsequent
        // iteration is guaranteed to have re-read the now-null field and skipped
        // PASS 1C. Only THEN is it safe to release. Bounded so a wedged GL thread
        // can't hang the disable caller (the watchdog handles a truly dead thread).
        android.os.Handler renderQuiesceHandler =
            (camera != null) ? camera.getGlHandler() : null;
        if (renderQuiesceHandler != null) {
            final java.util.concurrent.CountDownLatch quiesceLatch =
                new java.util.concurrent.CountDownLatch(1);
            boolean quiescePosted = renderQuiesceHandler.post(quiesceLatch::countDown);
            if (quiescePosted) {
                try {
                    if (!quiesceLatch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        logger.warn("BS: render-loop quiesce barrier did not "
                            + "complete within 1000ms — proceeding with release");
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        final com.overdrive.app.streaming.GpuStreamScaler scalerRef = bsScaler;
        final com.overdrive.app.surveillance.BsNativeLayer layerRef = bsLayer;
        bsScaler = null;
        bsLayer = null;
        bsLayerVisible = false;
        com.overdrive.app.camera.PanoramicCameraGpu cam = camera;
        if (cam != null) cam.setBsLayerVisible(false);

        // GL-thread teardown: scaler.release (which destroys its EGLSurface wrapping
        // the SurfaceControl layer's Surface) MUST happen before the layer/Surface is
        // released — destroying the EGLWindowSurface after its backing Surface is gone
        // is EGL_BAD_NATIVE_WINDOW on Adreno. Release the SC layer only after the GL
        // release completes.
        android.os.Handler glHandler = (camera != null) ? camera.getGlHandler() : null;
        if (scalerRef != null && glHandler != null) {
            final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
            boolean posted = glHandler.post(() -> {
                try {
                    try { scalerRef.release(); } catch (Throwable t) {
                        logger.warn("BS: scaler release: " + t.getMessage());
                    }
                } finally { latch.countDown(); }
            });
            if (posted) {
                try {
                    if (!latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        logger.warn("BS: scaler release did not complete within 1000ms");
                    }
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } else {
                try { scalerRef.release(); } catch (Throwable ignored) {}
            }
        } else if (scalerRef != null) {
            try { scalerRef.release(); } catch (Throwable ignored) {}
        }
        // Now the EGLSurface is gone — safe to release the SurfaceControl layer.
        if (layerRef != null) {
            try { layerRef.release(); } catch (Throwable ignored) {}
        }
    }

    // ══════════════════════════ CAMERA-VIEW PROGRAM ══════════════════════════
    // On-demand camera view (front/rear/left/right/all-4) on the SAME shared native
    // lane, arbitrated with blind-spot priority. Reuses the proven lane build,
    // geometry/target machinery, and cluster projection flow; adds only the program
    // selection + a non-turn-signal lifecycle.

    public boolean isCamViewActive() { return camViewActive; }
    public int getCamViewMode() { return camViewMode; }
    public String getCamViewTargetString() { return camViewTarget; }

    /** True when the camera view is not just REQUESTED but actually driving the shared
     *  lane with the layer shown — i.e. frames are on screen. Distinct from
     *  {@link #isCamViewActive()}, which is only the request flag: blind-spot can own the
     *  lane while a camview request stands, in which case nothing is rendering. */
    public boolean isCamViewRendering() {
        return camViewActive && laneProgram == PROG_CAMVIEW && bsLayerVisible;
    }

    /** True once the auto-hide deadline for the CURRENT camview request has fired. The
     *  timeout path calls disableCamView() without clearing the persisted
     *  camview.enabled, so a deferred-arm retry that only consulted config could re-arm a
     *  view that just timed out. Cleared when a new session arms a fresh deadline. */
    public boolean camViewAutoHideConsumed() { return camViewAutoHideFired.get(); }

    /** True when a camera view is requested but blind-spot is ACTUALLY holding the shared
     *  lane, so the view is waiting rather than failed. Requires laneProgram == PROG_BS,
     *  not merely != PROG_CAMVIEW: a freshly built lane sits at PROG_NONE until the first
     *  tick configures it, and reporting "masked" in that window would mislabel a view
     *  that is simply about to appear. */
    public boolean isCamViewMaskedByBlindSpot() {
        return camViewActive && blindSpotEnabled && laneProgram == PROG_BS;
    }

    /**
     * Record an accepted show REQUEST (called by the API handler before arming, so
     * ownership is correct even while the arm is deferred behind a pano cold start).
     * Latest show wins: bumps the session id and replaces the owner. {@code ownerToken}
     * null = ownerless (manual) show. Runs under bsLifecycleLock so the owner/session
     * mutation is atomic with respect to the hide verdict and the auto-hide claim
     * (TOCTOU fix — HTTP handlers run on a 32-thread pool, automations on their own
     * threads).
     *
     * @return the new session id, which the deferred-arm retry uses to undo an arm
     *         that raced its own cancellation (see startCamViewArmRetry).
     */
    public long noteCamViewShowRequest(Long ownerToken) {
        bsLifecycleLock.lock();
        try {
            camViewSessionId = camViewSessionSeq.incrementAndGet();
            camViewOwnerToken = ownerToken;
            return camViewSessionId;
        } finally {
            bsLifecycleLock.unlock();
        }
    }

    /**
     * Atomically claim the camview auto-hide timeout. MUST be called with
     * bsLifecycleLock held: enableCamView re-arms the deadline, its session
     * binding and the fired flag under the same lock, so a locked claim can
     * never consume a deadline a concurrent show just re-armed (the old
     * unlocked claim could CAS the fresh fired=false and zero the NEW
     * deadline), and the session returned is atomically the one the expired
     * deadline was armed for.
     *
     * @return the session id the expired deadline belongs to, or -1 when
     *         there is nothing to claim.
     */
    private long tryClaimCamViewAutoHideLocked(long nowElapsedMs) {
        long hideAt = camViewHideAtMs;
        if (hideAt <= 0 || nowElapsedMs < hideAt) return -1L;
        if (!camViewAutoHideFired.compareAndSet(false, true)) return -1L;
        camViewHideAtMs = 0L;   // one-shot: don't re-dispatch every tick
        return camViewHideSessionId;
    }

    /** May a hide carrying {@code token} close the current camera view? An ownerless
     *  hide (null) always may — legacy global close. A tokened hide only when it
     *  matches the current owner, so an automation can never close a view a later
     *  automation (or the user) put up. */
    public boolean camViewHideAllowedFor(Long token) {
        if (token == null) return true;
        Long owner = camViewOwnerToken;
        return owner != null && owner.equals(token);
    }

    /** Fail-open geometry for the current session — see {@link #camViewGeomOverride}. */
    public void setCamViewGeometryOverride(String target, org.json.JSONObject geo) {
        camViewGeomOverrideTarget = "cluster".equals(target) ? "cluster" : "head_unit";
        camViewGeomOverride = geo;
    }

    public void clearCamViewGeometryOverride() {
        camViewGeomOverride = null;
        camViewGeomOverrideTarget = null;
    }

    // NOTE: there is deliberately NO self-minting enableCamView(mode, target,
    // autoHideSec) convenience wrapper. A wrapper that calls
    // noteCamViewShowRequest() itself mints a session NEWER than any hide that
    // just landed, so the session check below passes and the dismissed view
    // re-arms — the hide race the session mechanism exists to prevent. Callers
    // (the API show route) must mint ONE session per USER request and pass it
    // to every arm attempt, including every deferred-arm retry pass.

    /**
     * Show a camera view. Builds the shared lane if neither program has it up, then
     * marks camview active; the arbiter (bsTurnTick) applies the camview program
     * whenever blind-spot isn't actively showing. mode 0=all-4,1=front,2=right,
     * 3=rear,4=left. target "head_unit"/"cluster". autoHideSec 0 = until hidden.
     *
     * <p>{@code showSession} is the id {@link #noteCamViewShowRequest} returned for
     * THIS request. Validated under bsLifecycleLock before any state mutation: the
     * show handler's mutation transaction and this arm are deliberately not one
     * atomic unit (the arm can block seconds on a lane build), so a hide or a newer
     * show can land in between — and a stale arm proceeding anyway would resurrect a
     * dismissed view or overwrite the newer show's camera/target/timeout with the
     * OLD request's values (the session id would say B while the lane showed A).
     *
     * @return true when armed; false when this request was superseded (a newer show
     *         bumped the session, or an allowed hide invalidated it) — the caller
     *         must treat the request as cancelled, not retry it.
     */
    public boolean enableCamView(int mode, String target, int autoHideSec,
            long showSession) throws Exception {
        String preTarget = "cluster".equals(target) ? "cluster" : "head_unit";
        // Calculate the on-screen rect BEFORE taking the lane lock (audit: persist +
        // calculate outside bsLifecycleLock). Geometry resolution reads UCM (which can
        // block on the cross-process config file lock) and, for a cluster target, the
        // panel size via a `dumpsys display` shell-out — neither belongs under the
        // lock the 250ms arbiter tick contends on. Wasted work when the session turns
        // out stale below — acceptable, staleness is the rare path.
        int[] preRect = resolveCamViewGeometryRect(preTarget);
        boolean staleReject = false;
        bsLifecycleLock.lock();
        try {
            if (showSession != camViewSessionId) {
                // Suppress the finally's show-edge broadcast: nothing changed here,
                // and the current (newer) session already emitted its own edge.
                staleReject = true;
                logger.info("CamView: stale show request rejected (session " + showSession
                    + " superseded by " + camViewSessionId + ") — not arming");
                return false;
            }
            String newTarget = preTarget;
            // RETARGET LEAK GUARD: if a camview was ALREADY holding the cluster
            // projection ("camview" sustained token) and this re-show moves it to the
            // head-unit, release the cluster hold NOW. camViewTick only acquires while
            // cluster-targeted and never releases on a target flip, so without this the
            // token would orphan (projection pinned open, gauges blanked, max-cap
            // disarmed) until ACC-off. Idempotent: no-op if it wasn't holding.
            if (camViewActive && isCamViewClusterTarget() && !"cluster".equals(newTarget)) {
                try { com.overdrive.app.surveillance.ClusterProjectionController.getInstance().releaseSustained("camview"); } catch (Throwable ignored) {}
            }
            camViewMode = com.overdrive.app.server.StreamingApiHandler.isCamViewMode(mode) ? mode : 0;
            camViewTarget = newTarget;
            camViewHideAtMs = (autoHideSec > 0)
                ? android.os.SystemClock.elapsedRealtime() + autoHideSec * 1000L : 0L;
            // Bind the deadline to the session it belongs to (both under this
            // lock, so the timeout claim can never associate an old expired
            // deadline with a newer session — see camViewHideSessionId).
            camViewHideSessionId = camViewSessionId;
            // Re-arm the one-shot dispatch guard for this new session/deadline.
            camViewAutoHideFired.set(false);
            // Adopt the rect computed outside the lock (see above). The tick paths
            // still re-resolve via resolveCamViewGeometry() on reconfig.
            camViewGeomRect = preRect;

            if (!running || camera == null || camera.getGlHandler() == null) {
                logger.warn("CamView: pano not running yet — enable deferred (caller must re-poll)");
                throw new BlindSpotNotReadyException(
                    "camera-view lane cannot arm — pano pipeline not running yet");
            }

            // Guard against a concurrent in-flight build (BS or camview). Mirrors the
            // bsEnabling guard: buildSharedLaneLocked releases bsLifecycleLock around
            // its GL-init wait, so a second enable that reacquires the lock during that
            // window must NOT start a second build (double-alloc). If a build is in
            // flight OR a live lane already exists, just mark camview active + force a
            // reconfig and let the arbiter pick it up on the next tick — the lane is
            // shared, never rebuilt (compute/memory-optimal: one scaler/layer/EGL).
            boolean firstConsumer = !camViewActive && !blindSpotEnabled;
            // A build is IN-FLIGHT (another program is mid-buildSharedLaneLocked, which
            // released the lock around its GL-init wait). We must NOT optimistically
            // mark camview active against it: if that build FAILS, its
            // releasePartialBsLane guard (`!blindSpotEnabled && !camViewActive`) would
            // be defeated by our flag, leaking a half-built, never-published lane that
            // the reuse fast-path would then adopt forever (black video / pinned
            // cluster). Instead DEFER — throw NotReady so the caller re-polls; by the
            // next poll the in-flight build has resolved (published live → laneLive
            // true, or failed → fully released) and we take a deterministic branch.
            if (bsEnabling || camViewEnabling) {
                logger.info("CamView: lane build in flight — deferring (caller re-polls)");
                throw new BlindSpotNotReadyException(
                    "camera-view deferred — a lane build is in flight");
            }
            boolean laneLive = (bsLayer != null && bsLayer.isCreated() && bsScaler != null);
            if (laneLive) {
                // Lane already fully built + published — safe to mark active now (no
                // build can fail under us). Reconfigure to camview on the next tick.
                camViewActive = true;
                laneProgram = PROG_NONE;
                startBsTurnLoop();
                logger.info("CamView: reusing live shared lane (no rebuild)");
            } else {
                // Fresh build. Do NOT set camViewActive until the build SUCCEEDS —
                // otherwise a GL-init timeout/throw inside buildSharedLaneLocked would
                // (a) defeat releasePartialBsLane's `!blindSpotEnabled && !camViewActive`
                // guard, leaking the SurfaceControl layer + scaler, and (b) leave
                // camViewActive stuck true with a dead lane that the reuse branch would
                // then adopt on retry (permanent no-video). Mirrors the BS path, which
                // sets blindSpotEnabled only AFTER a successful build. On failure, reset
                // state + release the partial lane, then rethrow so the caller re-polls.
                camViewEnabling = true;
                try {
                    buildSharedLaneLocked();
                } catch (Throwable t) {
                    camViewEnabling = false;
                    camViewActive = false;      // never armed
                    camViewHideAtMs = 0L;
                    // Guard now passes (both flags false) → releases the partial lane.
                    if (!blindSpotEnabled && !camViewActive) releasePartialBsLane();
                    if (t instanceof Exception) throw (Exception) t;
                    throw new RuntimeException(t);
                }
                camViewEnabling = false;
                // RE-VALIDATE the session before publishing (audit finding: the
                // entry check is not enough). buildSharedLaneLocked RELEASES
                // bsLifecycleLock around its GL-init wait, and a hide (or newer
                // show) acquiring the lock in that window invalidates this
                // session — publishing camViewActive=true here anyway would
                // resurrect the dismissed view. The lane build itself is fine;
                // only THIS request's claim on it is stale. If nobody else wants
                // the lane, release it (same guard as the failure path).
                if (showSession != camViewSessionId) {
                    staleReject = true;
                    camViewHideAtMs = 0L;
                    if (!blindSpotEnabled && !camViewActive) releasePartialBsLane();
                    logger.info("CamView: session invalidated during lane build "
                        + "(hide or newer show) — not publishing");
                    return false;
                }
                camViewActive = true;       // armed only on successful build
                laneProgram = PROG_NONE;   // force camview program config next tick
                startBsTurnLoop();
                if (firstConsumer) logger.info("CamView: shared lane built for camera-view");
            }
            logger.info(String.format("CamView: enabled mode=%d target=%s autoHide=%ds",
                camViewMode, camViewTarget, autoHideSec));
            return true;
        } finally {
            boolean nowActive = camViewActive && !staleReject;
            String tgt = camViewTarget;
            // Snapshot THIS show's rect under the lock. bsGeomRect still holds the
            // previous program's geometry until camViewTick's transition tick copies
            // camViewGeomRect over — so the show edge must carry the camview rect
            // explicitly or the ✕ is placed against stale blind-spot geometry.
            int[] showRect = camViewGeomRect;
            bsLifecycleLock.unlock();
            // Tell the app-side close-button overlay a view is up (edge-driven, no poll).
            // Fired outside the lock so the short `am broadcast` exec never holds it.
            if (nowActive) {
                emitCamViewState(true, tgt, showRect);
            }
            // NOTE: the camera-profile ramp for the new camera-view rung is driven by the
            // existing camViewTick → setBlindSpotVisible/clusterShowWhenReady →
            // fireBsVisibilityChanged edge (RMM.desiredCameraState reads camViewKeepWarmActive
            // fresh), so no explicit reconcile trigger is needed here.
        }
    }

    /** Hide the camera view. Tears the lane down only if blind-spot isn't also using it.
     *  LENIENT variant for internal lifecycle callers (pipeline.stop()): does NOT
     *  invalidate a not-yet-armed show session — see {@link #hideCamView()}. */
    public void disableCamView() {
        disableCamViewInternal(null, null, false, false);
    }

    /** Session-guarded variant for the auto-hide dispatch: hides only while
     *  {@code sessionId} is still the current session. Checked UNDER
     *  bsLifecycleLock, so a show landing concurrently can't have its brand-new
     *  view torn down by a stale timeout. */
    private void disableCamViewForSession(long sessionId) {
        disableCamViewInternal(sessionId, null, false, false);
    }

    /**
     * End the current camview SESSION — flags, owner, session invalidation,
     * fail-open override, pending deferred arm — WITHOUT touching the lane.
     * The single implementation of the session-ending contract, shared by
     * {@link #disableCamViewInternal} (which additionally tears the lane down or
     * hands it to BS) and the blind-spot takeover (which keeps the lane for BS).
     * The session bump is load-bearing: it is what makes an in-flight stale
     * enableCamView reject itself instead of re-arming the ended session's view.
     * MUST be called holding {@link #bsLifecycleLock}.
     */
    private void endCamViewSessionLocked() {
        camViewActive = false;
        camViewHideAtMs = 0L;
        camViewOwnerToken = null;
        camViewSessionId = camViewSessionSeq.incrementAndGet();
        clearCamViewGeometryOverride();
        // The session bump above IS the deferred-arm-retry cancel: retry validity is
        // session-scoped (StreamingApiHandler.startCamViewArmRetry), so no separate
        // cancellation token exists to race with.
    }

    /** Current camview session id — read by the mutation-lock-serialized
     *  session-conditional config writes in the API handler. */
    public long getCamViewSessionId() {
        return camViewSessionId;
    }

    /**
     * Ownership-checked hide for the API path. The verdict and the hide are ONE
     * atomic step under bsLifecycleLock (TOCTOU fix): with an unlocked pre-check,
     * a show landing between the check and the disable let a stale hide close the
     * new view. When allowed, this also cancels a pending deferred arm and spends
     * ownership even if the view never armed (deferred behind a pano cold start) —
     * a hide of one's own not-yet-visible request is still a valid cancel.
     *
     * @return {@link #CAMVIEW_HIDE_NOT_OWNER}, {@link #CAMVIEW_HIDE_CLOSED} or
     *         {@link #CAMVIEW_HIDE_ALREADY_HIDDEN}.
     */
    public int hideCamViewIfAllowed(Long token) {
        return disableCamViewInternal(null, token, true, false);
    }

    /**
     * User-intent hide for the API path (no ownership gate — the global
     * {@code /api/camview/hide} contract closes whatever is up). Unlike
     * {@link #disableCamView()}, this INVALIDATES the current show session even
     * when the view never armed: a hide of a request still deferred behind a
     * pano cold start — or of a straight-through show whose enableCamView is
     * still in flight outside the mutation lock — is an explicit cancel, and
     * only the session bump stops that in-flight enable from arming AFTER this
     * hide and silently reopening the camera the caller was just told is closed.
     * {@link #disableCamView()} stays lenient on purpose: pipeline.stop() calls
     * it unconditionally on routine warmup restarts, where killing a pending
     * deferred arm would drop the key press the retry exists to rescue.
     */
    public int hideCamView() {
        return disableCamViewInternal(null, null, false, true);
    }

    private int disableCamViewInternal(Long requiredSessionId, Long token,
            boolean checkOwner, boolean invalidateSession) {
        boolean wasActive = false;
        bsLifecycleLock.lock();
        try {
            if (requiredSessionId != null && camViewSessionId != requiredSessionId) {
                logger.info("CamView: stale session-guarded hide ignored — a newer show "
                    + "owns the view (session moved on)");
                return CAMVIEW_HIDE_ALREADY_HIDDEN;
            }
            if (checkOwner) {
                if (!camViewHideAllowedFor(token)) {
                    return CAMVIEW_HIDE_NOT_OWNER;
                }
            }
            if (checkOwner || invalidateSession) {
                // Allowed / user-intent hide: spend ownership and INVALIDATE THE
                // SESSION here — even when the view never armed (deferred behind a
                // cold start). The session bump is what makes a straight-through
                // show's enableCamView, still in flight outside the mutation lock,
                // reject itself instead of arming AFTER this hide, AND what stops
                // any deferred-arm retry loop (retry validity is session-scoped).
                // The shared session-ending helper further down is skipped by the
                // !camViewActive early-out, so the bump must happen here.
                camViewOwnerToken = null;
                camViewSessionId = camViewSessionSeq.incrementAndGet();
            }
            wasActive = camViewActive;
            if (!camViewActive) return CAMVIEW_HIDE_ALREADY_HIDDEN;
            logger.info("CamView: disabling camera view...");
            // Shared session-ending contract (also used by the BS takeover): flags,
            // owner, session invalidation, fail-open override, pending deferred-arm
            // cancel — one implementation, endCamViewSessionLocked. NOTE its cancel
            // runs AFTER the no-op early-out above, deliberately: pipeline.stop()
            // calls this method unconditionally (a routine warmup restart does
            // stop()→setMode), so cancelling before that return would kill a
            // legitimate retry whose view has not armed yet (camViewActive==false
            // for the whole deferral) — silently dropping the key press the retry
            // exists to rescue. Here it only fires on a REAL teardown of a REAL
            // session. The checkOwner branch above already bumped/cancelled for the
            // not-yet-armed hide case; doing it twice is harmless.
            endCamViewSessionLocked();

            // SAFETY (gauge-blank prevention): release the "camview" sustained hold
            // UNCONDITIONALLY — removing a token that isn't in the set is a harmless
            // idempotent no-op, and releasing regardless of the CURRENT target closes
            // the "acquire keyed on cluster, release keyed on current target" leak: a
            // camview that opened the cluster projection then retargeted to head_unit
            // would otherwise orphan the token forever (projection pinned open, gauges
            // blanked, max-cap disarmed). releaseSustained keeps the projection up ONLY
            // if another sustained holder (nav map) remains OR a transient blind-spot
            // turn-signal currently wants it; otherwise it force-closes + restores the
            // gauges. Runs before any lane handoff/teardown, in BOTH branches below.
            try { com.overdrive.app.surveillance.ClusterProjectionController.getInstance().releaseSustained("camview"); } catch (Throwable ignored) {}

            // If BS still needs the lane, just hand it back: hide the camview image,
            // force a reconfig so the next tick re-applies the BS program.
            if (blindSpotEnabled) {
                // Hide the layer now; the BS arbiter re-shows on the next turn signal.
                setBlindSpotVisible(false);
                laneProgram = PROG_NONE;   // force BS reconfig on next tick
                logger.info("CamView: disabled, lane retained for blind-spot");
                return CAMVIEW_HIDE_CLOSED;
            }

            // Neither program needs the lane — full teardown. (The cluster projection,
            // if any, was already released above.)
            stopBsTurnLoop();
            teardownSharedLaneLocked();
            laneProgram = PROG_NONE;
            logger.info("CamView: camera view disabled");
            return CAMVIEW_HIDE_CLOSED;
        } finally {
            bsLifecycleLock.unlock();
            // Tell the app-side close-button overlay the view is gone (edge-driven).
            // Only when it was actually active, so the no-op early-return path (view
            // already hidden) doesn't fire a spurious "closed" broadcast.
            if (wasActive) emitCamViewState(false, null);
            // Let RMM re-reconcile so the camera drops back to a lower rung (BS/stream/idle)
            // now that cam-view no longer needs the higher fps.
            if (wasActive) fireBsVisibilityChanged();
        }
    }

    private boolean isCamViewClusterTarget() { return "cluster".equals(camViewTarget); }

    /**
     * Fire an {@code am broadcast} telling the app-process close-button overlay
     * (the camera-view ✕ hosted in {@link com.overdrive.app.overlay.StatusOverlayService})
     * that a camera view opened ({@code active=true}) or closed ({@code active=false}).
     * This is the zero-poll edge signal — the daemon runs as shell/UID-2000 so it can
     * broadcast to the app. Best-effort + fully detached: a short-lived exec whose output
     * we never read, so it can never block the lifecycle path. Never throws.
     */
    private void emitCamViewState(boolean active, String target) {
        emitCamViewState(active, target, null);
    }

    /**
     * Variant carrying an EXPLICIT on-screen rect for the show edge. enableCamView
     * must use this with the camview rect it just resolved: at that moment
     * {@code bsGeomRect} (what {@link #getLaneGeomRect} reads) still holds the
     * PREVIOUS program's geometry — camViewTick only copies
     * {@code camViewGeomRect} into it on its program-transition tick, up to 250ms
     * later. Broadcasting the lane rect on that edge positioned the ✕ against the
     * stale blind-spot card rect, then the next poll moved it — the "✕ appears and
     * jumps around" defect. {@code rect} null falls back to the live lane rect
     * (correct for the paths that publish the shared rect before emitting).
     */
    private void emitCamViewState(boolean active, String target, int[] rect) {
        emitOverlayCloseState(com.overdrive.app.overlay.StatusOverlayService.ACTION_CAMVIEW_STATE,
                active, target, "camview-state-broadcast", rect);
    }

    /**
     * Fire an {@code am broadcast} telling the app-process close-button overlay
     * (the blind-spot ✕ hosted in {@link com.overdrive.app.overlay.StatusOverlayService})
     * that the blind-spot card appeared ({@code active=true}) or went away
     * ({@code active=false}). Same zero-poll edge contract as {@link #emitCamViewState}
     * — the poll reconcile in the overlay is the catch-up for a dropped broadcast.
     */
    private void emitBsCardState(boolean active, String target) {
        emitOverlayCloseState(com.overdrive.app.overlay.StatusOverlayService.ACTION_BS_STATE,
                active, target, "bs-state-broadcast", null);
    }

    /**
     * Shared implementation for the camera-view / blind-spot close-button edge
     * broadcasts. Best-effort + fully detached: a short-lived {@code am broadcast} exec
     * whose output we drain-and-discard on a daemon thread, so it can never block the
     * lifecycle path or wedge on a full pipe. Never throws.
     */
    private void emitOverlayCloseState(String action, boolean active, String target,
                                       String threadName, int[] explicitRect) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.Arrays.asList(
                    "am", "broadcast",
                    "-a", action,
                    "-p", "com.overdrive.app",
                    "--ez", "active", active ? "true" : "false"));
            if (target != null) { cmd.add("--es"); cmd.add("target"); cmd.add(target); }
            // Carry the card's on-screen rect on the SHOW edge so the overlay can place its
            // ✕ clear of the card immediately. Without it the button attaches on this edge
            // but only learns the rect from the next /status poll (up to 3s, 30s when the
            // overlay is idle-throttled) — so a card resized while hidden would swallow the
            // ✕ for that whole window, since the card's SurfaceControl layer composites
            // above any app window. Omitted when unresolved (receiver keeps its fixed inset).
            // CLUSTER rects are in the 1920×720 cluster panel's space and the overlay can't
            // draw there at all — it suppresses the ✕ on a cluster target. Sending one would
            // leave cluster-space coordinates cached in the overlay, which a LATER head-unit
            // show could then position against (a 1080-tall panel vs a 720-tall rect → the
            // degenerate "card fills the panel" branch → ✕ back under the layer). Only the
            // head-unit rect is meaningful to the ✕.
            if (active && !"cluster".equals(target)) {
                // Prefer the caller's explicit rect (the geometry THIS edge is about);
                // fall back to the live lane rect. Same sentinel/degenerate screen as
                // getLaneGeomRect — never publish {-1,-1,-1,-1} as a real rect.
                int[] lr = (explicitRect != null && explicitRect.length == 4
                        && explicitRect[2] > 0 && explicitRect[3] > 0)
                    ? explicitRect : getLaneGeomRect();
                if (lr != null) {
                    cmd.add("--ei"); cmd.add("rectX"); cmd.add(Integer.toString(lr[0]));
                    cmd.add("--ei"); cmd.add("rectY"); cmd.add(Integer.toString(lr[1]));
                    cmd.add("--ei"); cmd.add("rectW"); cmd.add(Integer.toString(lr[2]));
                    cmd.add("--ei"); cmd.add("rectH"); cmd.add(Integer.toString(lr[3]));
                }
            }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // Detach: drain+discard on a daemon thread so the child can't wedge on a
            // full pipe, and never wait on the lifecycle thread.
            final java.io.InputStream is = p.getInputStream();
            Thread drain = new Thread(() -> {
                byte[] buf = new byte[256];
                try { while (is.read(buf) != -1) { /* discard */ } } catch (Throwable ignored) {}
            }, threadName);
            drain.setDaemon(true);
            drain.start();
        } catch (Throwable t) {
            logger.debug("emitOverlayCloseState(" + action + ") failed: " + t.getMessage());
        }
    }

    /** Resolve the camview on-screen rect from UCM geometry (preset or absolute),
     *  per target, mirroring resolveBsGeometry. Writes camViewGeomRect. */
    private void resolveCamViewGeometry() {
        camViewGeomRect = resolveCamViewGeometryRect(camViewTarget);
    }

    /** Rect-computing core of {@link #resolveCamViewGeometry}, parameterised by target
     *  so {@link #enableCamView} can run it BEFORE taking bsLifecycleLock (UCM read +
     *  possible `dumpsys display` shell-out don't belong under the lane lock). Still
     *  refreshes camViewSizePct/camViewCorner as before. Never throws; on failure
     *  returns the current rect when valid, else the fixed default. */
    private int[] resolveCamViewGeometryRect(String target) {
        final boolean clusterTarget = "cluster".equals(target);
        try {
            android.content.Context ctx = savedContext;
            if (ctx == null) ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            android.graphics.Point panel = (ctx != null)
                ? (clusterTarget
                    ? com.overdrive.app.surveillance.BsNativeLayer.clusterDisplaySize(ctx)
                    : com.overdrive.app.surveillance.BsNativeLayer.displaySize(ctx))
                : new android.graphics.Point(1920, clusterTarget ? 720 : 1080);
            String geomKey = clusterTarget ? "geometryCluster" : "geometry";
            // Fail-open override first: set only when the show's atomic config write
            // failed, so the session renders the REQUESTED geometry instead of stale
            // persisted geometry (same JSON shape as the persisted object).
            org.json.JSONObject g = null;
            org.json.JSONObject ov = camViewGeomOverride;
            if (ov != null && clusterTarget == "cluster".equals(camViewGeomOverrideTarget)) {
                g = ov;
            }
            if (g == null) {
                org.json.JSONObject cv =
                    com.overdrive.app.config.UnifiedConfigManager.getCamView();
                g = (cv != null) ? cv.optJSONObject(geomKey) : null;
            }
            int[] r;
            if (g != null && g.has("sizePct")) {
                camViewSizePct = g.optInt("sizePct", camViewSizePct);
                // ALWAYS reset the corner for this target, canonicalised — do not leave the
                // previous target's value in place when this one stores none. camViewCorner is a
                // single field shared by head-unit and cluster, so a conditional assignment made
                // the rendered position depend on which target was shown earlier: an identical
                // config rendered bottom-right this boot (inherited field) and centre after a
                // restart (fresh field). Absent → this lane's own "center" default.
                camViewCorner = canonicalCorner(g.optString("corner", null), "center");
                r = camViewPresetRect(panel);
            } else if (g != null && g.has("x") && g.has("w")) {
                r = clampRectToPanel(g.optInt("x"), g.optInt("y"), g.optInt("w"), g.optInt("h"), panel);
            } else {
                double defFrac = clusterTarget ? 0.80 : 0.60;
                int w = (int) (panel.x * defFrac);
                int h = (int) (w * (double) sharedLaneHeight / BS_WIDTH);
                r = clampRectToPanel((panel.x - w) / 2, (panel.y - h) / 2, w, h, panel);
            }
            return new int[]{r[0], r[1], r[2], r[3]};
        } catch (Throwable t) {
            logger.warn("resolveCamViewGeometry failed: " + t.getMessage());
            int[] cur = camViewGeomRect;
            if (cur != null && cur.length == 4 && cur[2] > 0) return cur;
            return new int[]{
                24, 24, 768, (int) (768.0 * sharedLaneHeight / BS_WIDTH)
            };
        }
    }

    private int[] camViewPresetRect(android.graphics.Point panel) {
        int p = Math.max(15, Math.min(camViewSizePct, 95));
        int w = (int) (panel.x * (p / 100.0));
        int h = (int) (w * (double) sharedLaneHeight / BS_WIDTH);
        int inset = 24;
        // Camera-view defaults to CENTER (not the card's tr) when nothing is stored.
        int[] r = cornerRect(canonicalCorner(camViewCorner, "center"), panel, w, h, inset);
        return clampRectToPanel(r[0], r[1], r[2], r[3], panel);
    }

    /** Clamp a rect into the given panel, keeping the buffer ratio (uniform scale). */
    private int[] clampRectToPanel(int x, int y, int w, int h, android.graphics.Point panel) {
        w = Math.max(160, Math.min(w, panel.x));
        h = Math.max(120, Math.min(h, panel.y));
        double want = (double) BS_WIDTH / sharedLaneHeight;
        if ((double) w / h > want) w = (int) (h * want);
        else                       h = (int) (w / want);
        x = Math.max(0, Math.min(x, panel.x - w));
        y = Math.max(0, Math.min(y, panel.y - h));
        return new int[]{x, y, w, h};
    }

    /**
     * Fire-and-forget submit of {@code encoder.release()} onto the dedicated
     * streaming-encoder release executor. NEVER blocks the caller — the GL
     * render thread inside the disable Runnable returns immediately while
     * the native release runs on the executor.
     *
     * <p>Single-threaded executor: Adreno's HAL refcount has known bugs
     * around concurrent {@code MediaCodec.release()} calls. If a release
     * wedges, subsequent ones queue behind it — that's an accepted cost
     * in exchange for a design we can reason about. The shutdown hook
     * drains the queue inline at process exit so encoders are released
     * before the JVM goes away.
     */
    static void submitEncoderRelease(HardwareEventRecorderGpu encoderRef,
            java.util.concurrent.CompletableFuture<Boolean> verdict) {
        if (encoderRef == null) {
            // Nothing to release — complete the placeholder clean so a stale
            // pending future can't wedge-flag an empty retirement.
            if (verdict != null) verdict.complete(Boolean.TRUE);
            return;
        }
        try {
            // Completes the caller's retirement PLACEHOLDER with the release
            // verdict (audit follow-up 2: the placeholder is published
            // synchronously at detach; completing it here — however late the GL
            // path submitted us — is what lets stop() verify the release before
            // the camera closes, and a placeholder we never complete correctly
            // reads as wedged).
            STREAM_ENCODER_RELEASE_EXEC.submit(() -> {
                boolean clean = false;
                try {
                    clean = encoderRef.release();
                } catch (Throwable t) {
                    DaemonLogger.getInstance(TAG).warn(
                        "streamEncoder release on offload thread: " + t.getMessage());
                } finally {
                    if (verdict != null) verdict.complete(clean);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException re) {
            // Executor shut down (typically JVM exit racing the disable
            // path). Best-effort: spawn a one-shot daemon thread so the
            // encoder still releases without pinning the caller.
            Thread t = new Thread(() -> {
                boolean clean = false;
                try { clean = encoderRef.release(); }
                catch (Throwable ignored) {}
                finally { if (verdict != null) verdict.complete(clean); }
            }, "StreamEncoderReleaseFallback");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * Static shutdown hook for the encoder-release executor. Called from
     * CameraDaemon's JVM shutdown hook so any in-flight releases drain
     * before the process exits.
     *
     * @return true iff the executor drained cleanly within {@code awaitMs};
     *         false if the timeout fired — in which case shutdownNow's
     *         dropped Runnables are run inline so encoders still release
     *         before process exit.
     */
    public static boolean shutdownStreamEncoderReleaseExec(long awaitMs) {
        boolean drained = false;
        try {
            STREAM_ENCODER_RELEASE_EXEC.shutdown();
            drained = STREAM_ENCODER_RELEASE_EXEC.awaitTermination(
                awaitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!drained) {
                DaemonLogger.getInstance(TAG).warn(
                    "streamEncoder release exec did not drain in " + awaitMs
                    + "ms; forcing shutdownNow + inline-draining queued releases");
                for (Runnable r : STREAM_ENCODER_RELEASE_EXEC.shutdownNow()) {
                    try { r.run(); } catch (Throwable ignored) {}
                }
            }
        } catch (InterruptedException ie) {
            DaemonLogger.getInstance(TAG).warn(
                "shutdownStreamEncoderReleaseExec interrupted; forcing shutdownNow");
            try {
                for (Runnable r : STREAM_ENCODER_RELEASE_EXEC.shutdownNow()) {
                    try { r.run(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            Thread.currentThread().interrupt();
        }
        return drained;
    }

    // Single-thread executor for streamEncoder.release(). Single-threaded
    // because two concurrent native-codec releases on Adreno occasionally
    // trip a HAL refcount bug. Daemon thread so it doesn't block JVM exit.
    private static final java.util.concurrent.ExecutorService STREAM_ENCODER_RELEASE_EXEC =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "StreamEncoderRelease");
            t.setDaemon(true);
            return t;
        });
    
    /**
     * Checks if streaming is enabled.
     */
    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    /**
     * Re-runs Od.authorize() to recover from a transient boot-time
     * authorization failure. enableStreamingInternal() authorizes once at
     * enable time, but if the context was null/unstable then (early boot,
     * system_server transient) authorization silently stayed false and
     * Od.resolve() zeros its output forever. Od.authorize() is idempotent
     * (returns early once ready), so calling it again later — e.g. on ACC ON
     * once a valid context exists — is a cheap, safe retry.
     *
     * @param ctx a valid app context; falls back to the saved/daemon context
     */
    public void retryOdAuthorization(android.content.Context ctx) {
        try {
            android.content.Context odCtx = ctx;
            if (odCtx == null) odCtx = savedContext;
            if (odCtx == null) odCtx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
            if (odCtx != null) {
                if (this.savedContext == null) this.savedContext = odCtx;
                com.overdrive.app.od.Od.authorize(odCtx);
            } else {
                logger.error("od authorize retry skipped: no context available");
            }
        } catch (Throwable t) {
            logger.warn("od retry failed: " + t.getMessage());
        }
    }

    /**
     * Gets the stream scaler component.
     */
    public com.overdrive.app.streaming.GpuStreamScaler getStreamScaler() {
        return streamScaler;
    }
    
    /**
     * Gets the stream encoder component.
     */
    public HardwareEventRecorderGpu getStreamEncoder() {
        return streamEncoder;
    }
    
    /**
     * Gets the WebSocket stream server.
     */
    public com.overdrive.app.streaming.WebSocketStreamServer getWebSocketServer() {
        return wsStreamServer;
    }

    /** A lifecycle-bound /ws sink. The captured pair remains safe to remove
     * even after a later disable/re-enable installs a different encoder. */
    public static final class ExternalStreamClientSubscription {
        private final HardwareEventRecorderGpu encoder;
        private final com.overdrive.app.streaming.WebSocketStreamServer server;
        private final HardwareEventRecorderGpu.StreamCallback callback;

        private ExternalStreamClientSubscription(HardwareEventRecorderGpu encoder,
                com.overdrive.app.streaming.WebSocketStreamServer server,
                HardwareEventRecorderGpu.StreamCallback callback) {
            this.encoder = encoder;
            this.server = server;
            this.callback = callback;
        }

        public HardwareEventRecorderGpu getEncoder() {
            return encoder;
        }
    }

    /**
     * Atomically attach one single-port /ws client to the live encoder. This
     * prevents a reconnect from adding its callback to an encoder that an idle
     * shutdown or quality restart has already retired.
     */
    public ExternalStreamClientSubscription registerExternalStreamClient(
            HardwareEventRecorderGpu.StreamCallback callback) {
        if (callback == null) return null;
        streamLifecycleLock.lock();
        try {
            HardwareEventRecorderGpu encoder = streamEncoder;
            com.overdrive.app.streaming.WebSocketStreamServer server = wsStreamServer;
            if (!streamingEnabled || encoder == null || server == null) {
                return null;
            }
            encoder.addStreamCallback(callback);
            server.registerExternalClient();
            return new ExternalStreamClientSubscription(encoder, server, callback);
        } catch (Throwable t) {
            logger.warn("registerExternalStreamClient failed: " + t.getMessage());
            return null;
        } finally {
            streamLifecycleLock.unlock();
        }
    }

    /** Remove the exact subscription that was previously registered. */
    public void unregisterExternalStreamClient(ExternalStreamClientSubscription subscription) {
        if (subscription == null) return;
        streamLifecycleLock.lock();
        try {
            subscription.encoder.removeStreamCallback(subscription.callback);
            // disableStreaming shuts down and clears the current server under
            // this same lock. Do not restart an idle timer on that retired
            // server while cleaning up a socket that lost the race.
            if (wsStreamServer == subscription.server) {
                subscription.server.unregisterExternalClient();
            }
        } catch (Throwable t) {
            logger.warn("unregisterExternalStreamClient failed: " + t.getMessage());
        } finally {
            streamLifecycleLock.unlock();
        }
    }
    
    /**
     * Sets the stream view mode (which camera to show).
     * 
     * @param mode 0=Mosaic (2x2 grid), 1=Front, 2=Right, 3=Rear, 4=Left
     */
    public void setStreamViewMode(int mode) {
        if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            int hookMode = 4; // 4 = 2x2 Mosaic
            if (mode == 0) hookMode = 4;      // Tutte le telecamere
            else if (mode == 1) hookMode = 0; // Anteriore (Front)
            else if (mode == 2) hookMode = 1; // Destra (Right)
            else if (mode == 3) hookMode = 2; // Posteriore (Rear)
            else if (mode == 4) hookMode = 3; // Sinistra (Left)
            com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.setActiveCamera(hookMode);
        }
        if (streamScaler != null) {
            streamScaler.setViewMode(mode);
            logger.info("Stream view mode changed to " + mode);
        } else {
            logger.warn("Cannot set stream view mode - streaming not enabled");
        }
    }

    /** Back-compat 8-arg pass-through (rear roll/pitch = 0 = rear identity). */
    public void setBlindSpotParams(float hfov, float sideHFov, float yaw, float roll,
                                   float feather, float projExp, float vscale, float pitch) {
        setBlindSpotParams(hfov, sideHFov, yaw, roll, feather, projExp, vscale, pitch,
                           0.0f, 0.0f);
    }

    /** POC blind-spot (view 7/8) panorama-stitch tuning pass-through. No-op if streaming off. */
    public void setBlindSpotParams(float hfov, float sideHFov, float yaw, float roll,
                                   float feather, float projExp, float vscale, float pitch,
                                   float rearRoll, float rearPitch) {
        // Tune BOTH the shared stream scaler (in case a browser is previewing
        // view 7/8 on the live stream) AND the dedicated blind-spot lane's scaler
        // (what the overlay actually renders) — so the debug-editor sliders
        // update whichever the user is watching.
        com.overdrive.app.streaming.GpuStreamScaler ss = streamScaler;
        if (ss != null) {
            ss.setBlindSpotParams(hfov, sideHFov, yaw, roll, feather, projExp, vscale, pitch,
                                  rearRoll, rearPitch);
        }
        com.overdrive.app.streaming.GpuStreamScaler bs = bsScaler;
        if (bs != null) {
            bs.setBlindSpotParams(hfov, sideHFov, yaw, roll, feather, projExp, vscale, pitch,
                                  rearRoll, rearPitch);
        }
        logger.info("Blind-spot params: hfov=" + hfov + " sideHFov=" + sideHFov
                + " yaw=" + yaw + " roll=" + roll + " feather=" + feather
                + " projExp=" + projExp + " vscale=" + vscale + " pitch=" + pitch
                + " rearRoll=" + rearRoll + " rearPitch=" + rearPitch);
    }

    /**
     * Blind-spot merge mode (views 7/8): 0 = both (rear+side stitch, default),
     * 1 = side camera only, 2 = rear camera only. Pushes to BOTH the shared
     * stream scaler (browser preview) and the dedicated BS lane's scaler (what
     * the overlay renders), same as {@link #setBlindSpotParams}. No-op-safe.
     */
    public void setBlindSpotMergeMode(int mode) {
        com.overdrive.app.streaming.GpuStreamScaler ss = streamScaler;
        if (ss != null) ss.setBlindSpotMergeMode(mode);
        com.overdrive.app.streaming.GpuStreamScaler bs = bsScaler;
        if (bs != null) bs.setBlindSpotMergeMode(mode);
        logger.info("Blind-spot merge mode set to " + mode);
    }

    /**
     * Fisheye/lens-dewarp strength (0..100) for the single-camera blind-spot views
     * (side/rear). Separate knob from recording.rectifyStrength. Pushes to BOTH the
     * shared stream scaler (browser preview) and the dedicated BS lane's scaler (what
     * the overlay renders), mirroring {@link #setBlindSpotMergeMode}. The dewarp is a
     * no-op in the merged 'both' view (shader only samples it in the merge 1/2
     * passthrough). No-op-safe when a lane isn't up.
     */
    public void setBlindSpotRectifyStrength(int strength) {
        com.overdrive.app.streaming.GpuStreamScaler ss = streamScaler;
        if (ss != null) ss.setBlindSpotRectifyStrength((float) strength);
        com.overdrive.app.streaming.GpuStreamScaler bs = bsScaler;
        if (bs != null) bs.setBlindSpotRectifyStrength((float) strength);
        logger.info("Blind-spot fisheye strength set to " + strength);
    }

    /** Map the persisted string merge mode to the scaler's int code. */
    private static int bsMergeModeCode(String mode) {
        if ("side".equals(mode)) return 1;
        if ("rear".equals(mode)) return 2;
        return 0;   // "both" / null / unknown
    }

    /**
     * Re-read the blind-spot card rotation (and merge-mode gate) from config and
     * re-apply it live. Called after a settings write changes blindspot.rotation OR
     * blindspot.mergeMode (a flip to/from "both" changes whether rotation applies).
     * Re-resolves geometry — which recomputes the rotation-aware dest rect + pushes
     * the angle onto the layer — and re-applies the rect when the card is shown, so
     * the change lands without an ACC cycle. No-op-safe when the lane isn't up.
     */
    public void refreshBlindSpotRotation() {
        bsLifecycleLock.lock();
        try {
            // Not while a CAMERA VIEW owns the shared lane. resolveBsGeometry() rewrites both
            // bsGeomRect and bsTarget from the BLIND-SPOT config, so a blindspot rotation /
            // mergeMode settings write used to shrink the on-screen camera view to the card's rect
            // — and on a cluster camview it also flipped bsTarget back to head_unit, so the
            // isClusterTarget() guard went false and later re-notifies stopped working
            // (audit 2026-08). Blind-spot re-resolves its own geometry when it takes the lane back.
            if (camViewOwnsLane()) return;
            resolveBsGeometry();
            com.overdrive.app.surveillance.BsNativeLayer layer = bsLayer;
            if (layer != null && layer.isCreated() && bsLayerVisible) {
                int[] g = bsGeomRect;
                layer.setGeometry(g[0], g[1], g[2], g[3]);
            }
        } catch (Throwable t) {
            logger.warn("refreshBlindSpotRotation failed: " + t.getMessage());
        } finally {
            bsLifecycleLock.unlock();
        }
    }

    
    /**
     * Gets the current stream view mode.
     * 
     * @return 0=Mosaic, 1-4=Single camera, -1 if streaming not enabled
     */
    public int getStreamViewMode() {
        return streamScaler != null ? streamScaler.getViewMode() : -1;
    }
    
    /**
     * Checks if currently recording.
     * 
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recorder != null && recorder.isRecording();
    }

    /**
     * Checks if in recording mode (vs viewing mode).
     */
    public boolean isRecordingMode() {
        return recordingMode;
    }

    /**
     * Current deferred-record prefix, or {@code null} if no record is pending.
     * Surfaced for {@link com.overdrive.app.recording.RecordingModeManager} so
     * its resync ticker can distinguish "modeActive=true but pipeline isn't
     * actually writing frames AND isn't waiting on a deferred-record path"
     * (which is genuinely wedged and warrants a re-activation) from "modeActive
     * but a deferred record is still in flight" (which is a normal transient
     * state and should not retrigger). Volatile field already; this is just a
     * read-through getter.
     */
    public String getPendingRecordingPrefix() {
        return pendingRecordingPrefix;
    }

    /**
     * Checks if initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
    
    private static String getVehicleModel() {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class)
                .invoke(null, "ro.product.model", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Checks if running.
     * 
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Null-safe "is the encoder currently writing packets to disk" accessor.
     * Used by the boot-time StorageManager cleanup-gate probe wired in
     * CameraDaemon.main(): the probe is bound before this pipeline is
     * constructed/init'd so the limit-enforcement ticker is never silently
     * disabled by a construction/pre-init throw. Returns false (encoder idle)
     * while the encoder is null or pre-init, preserving the anti-fail-open
     * intent (no destructive delete burst during an active write).
     *
     * @return true only if the encoder exists and is writing to a file
     */
    public boolean isEncoderWriting() {
        HardwareEventRecorderGpu e = this.encoder;
        return e != null && e.isWritingToFile();
    }

    /**
     * Register an external predicate that the WebSocket idle-shutdown
     * callback consults before tearing the pipeline down. Returning true
     * keeps the pipeline alive even when no recording is currently in
     * flight — used by PROXIMITY_GUARD MONITORING (radar armed, waiting
     * for trigger).
     *
     * <p>Pass {@code null} to clear. Predicate is called from the
     * IdleShutdown thread; implementation must be thread-safe and
     * non-blocking.
     */
    public void setKeepAlivePredicate(java.util.concurrent.Callable<Boolean> predicate) {
        this.keepAlivePredicate = predicate;
    }

    /**
     * Register a listener fired whenever the BS layer's on-screen visibility
     * changes. RecordingModeManager uses this to ramp the global camera fps when
     * BS is the sole consumer. Best-effort; exceptions are swallowed.
     */
    public void setBsVisibilityListener(Runnable listener) {
        this.bsVisibilityListener = listener;
    }

    /** Register a listener fired whenever live-view streaming is enabled/disabled
     *  (incl. WS idle auto-close). RecordingModeManager uses it to recompute the
     *  global camera fps floor. Best-effort; exceptions swallowed. */
    public void setStreamStateListener(Runnable listener) {
        this.streamStateListener = listener;
    }

    // Last bsLayerVisible value the listener was notified about. Edge-detect so
    // callers (incl. the per-250ms clusterShowWhenReady re-assert) can invoke
    // fireBsVisibilityChanged liberally without firing the listener every tick —
    // only true on→off / off→on transitions reach RecordingModeManager.
    private volatile boolean bsLastNotifiedVisible = false;

    // Last (showing, target) the BS close-button ✕ broadcast was fired for. Keyed on
    // the COMPOSITE card-showing predicate (not bare bsLayerVisible, which is shared
    // with camview), so the ✕ edge tracks the blind-spot card specifically. Evaluated
    // lock-free at the top of every turn tick + on the disable teardown.
    private volatile boolean bsLastNotifiedCardShowing = false;
    private volatile String bsLastNotifiedCardTarget = null;

    /**
     * Fire the blind-spot ✕ edge broadcast IFF the card's on-screen presence (or its
     * target) changed since the last notification. Idempotent + edge-detected, so it is
     * safe to call every 250ms tick; only real transitions reach the app overlay.
     *
     * <p>MUST be called WITHOUT holding {@link #bsLifecycleLock}: {@link #emitBsCardState}
     * spawns an {@code am broadcast} exec, and the camera-view path deliberately keeps
     * that off the lane lock. The turn tick (the primary caller) runs lock-free.
     */
    private void fireBsCardStateChanged() {
        boolean showing = isBlindSpotCardShowing();
        String target = showing ? getBsTargetString() : null;
        if (showing == bsLastNotifiedCardShowing
                && java.util.Objects.equals(target, bsLastNotifiedCardTarget)) {
            return;
        }
        bsLastNotifiedCardShowing = showing;
        bsLastNotifiedCardTarget = target;
        emitBsCardState(showing, target);
    }

    /** Fire the BS-visibility listener IFF bsLayerVisible actually changed since
     *  the last notification. Safe to call from every BS show/hide site (turn-
     *  tick, cluster show/close, disable). Never throws into the caller. */
    private void fireBsVisibilityChanged() {
        boolean now = bsLayerVisible;
        if (now == bsLastNotifiedVisible) {
            return;
        }
        bsLastNotifiedVisible = now;
        Runnable l = bsVisibilityListener;
        if (l != null) {
            try {
                l.run();
            } catch (Throwable t) {
                logger.warn("bsVisibilityListener failed: " + t.getMessage());
            }
        }
    }

    /**
     * Gets the camera component.
     * 
     * @return PanoramicCameraGpu instance
     */
    public PanoramicCameraGpu getCamera() {
        return camera;
    }
    
    /**
     * Gets the surveillance engine.
     *
     * @return SurveillanceEngineGpu instance
     */
    public SurveillanceEngineGpu getSentry() {
        return sentry;
    }

    /**
     * Gets the pano mosaic recorder. Used by the storage watchdog to
     * re-poke the recorder's output dir after a hot SD/USB remount, so
     * future segments land on the freshly-mounted volume rather than the
     * stale (vanished) mount point captured at startRecording time.
     *
     * @return the active {@link GpuMosaicRecorder}, or {@code null} when
     *         the pipeline has not yet created one (pre-init / post-release).
     */
    /**
     * Last time GpuMosaicRecorder closed a recording file (segment rotation
     * or final stop). Read by RecordingModeManager's wedge ticker so a
     * normal segment-boundary isRecording()=false flicker doesn't get
     * misread as a wedge that needs re-activation.
     *
     * @return wallclock millis of last file-closed callback, 0 if none yet.
     */
    public long getLastSegmentRotateMs() {
        return lastSegmentRotateMs;
    }

    /**
     * Stamps the segment-rotation timestamp. Called from GpuMosaicRecorder's
     * file-closed callback.
     */
    void noteSegmentRotated() {
        lastSegmentRotateMs = System.currentTimeMillis();
    }

    public GpuMosaicRecorder getRecorder() {
        return recorder;
    }

    /**
     * Gets the adaptive bitrate controller.
     * 
     * @return AdaptiveBitrateController instance
     */
    public AdaptiveBitrateController getBitrateController() {
        return bitrateController;
    }
    
    /**
     * Checks if surveillance mode is active.
     * 
     * @return true if in surveillance mode
     */
    public boolean isSurveillanceMode() {
        return currentMode == Mode.SURVEILLANCE;
    }

    /**
     * @return true when the sentry currently has active motion or is recording
     * an event — the signal RMM uses to ramp the camera from parked-idle fps
     * back to the full surveillance fps. False when the sentry is absent
     * (falls through to today's behaviour).
     */
    public boolean hasActiveSurveillanceMotion() {
        SurveillanceEngineGpu s = sentry;
        return s != null && s.hasActiveMotion();
    }

    /**
     * @return milliseconds of sustained no-motion since the sentry last saw
     * active motion (issue #174), or 0 while motion is active / the sentry is
     * absent. RMM uses this to step the parked-idle AI cadence down into the
     * quiet tier after a long no-motion period. Falls through to 0 (never
     * triggers the tier) when the sentry is not wired.
     */
    public long getSurveillanceQuietDurationMs() {
        SurveillanceEngineGpu s = sentry;
        return s != null ? s.getQuietDurationMs() : 0L;
    }

    /**
     * @return true when surveillance is in CONTINUOUS (always-record) mode,
     * which has NO AI lane and already records at full fps — the parked-idle
     * throttle must exclude it. False when the sentry is absent.
     */
    public boolean isContinuousSurveillance() {
        SurveillanceEngineGpu s = sentry;
        return s != null && s.isContinuousMode();
    }

    /**
     * Checks if normal recording mode is active.
     * 
     * @return true if in normal recording mode
     */
    public boolean isNormalRecordingMode() {
        return currentMode == Mode.NORMAL_RECORDING;
    }

    /**
     * FIX (audit R5): expose the encoder's last-encoded-frame timestamp so
     * RMM's wedge ticker can detect encoder hangs that don't surface in
     * isRunning()/isRecording(). Returns 0 when the encoder is null, has
     * not been initialized, or has not produced a coded frame yet — caller
     * must treat 0 as "no signal" (skip the wedge check). Returns the wall
     * clock time (System.currentTimeMillis) of the last
     * dequeueOutputBuffer that yielded a real coded frame.
     */
    public long getLastEncodedFrameMs() {
        HardwareEventRecorderGpu enc = encoder;
        if (enc == null) return 0L;
        return enc.getLastEncodedFrameMs();
    }

    /**
     * FIX (false-GREEN: "REC/MIC green but no video file"): expose the
     * encoder's last-disk-write timestamp so RMM's wedge ticker can detect a
     * "muxer open but nothing landing on disk" stall (SD unmount mid-segment,
     * ENOSPC, every write failing below the 5-strike abort). Distinct from
     * getLastEncodedFrameMs(): that advances on every coded frame even when no
     * file is being written, because the encoder always runs for the
     * pre-record ring. Returns 0 when the encoder is null or no muxer has
     * opened yet — caller must treat 0 as "no signal" (skip the check).
     */
    public long getLastDiskWrittenMs() {
        HardwareEventRecorderGpu enc = encoder;
        if (enc == null) return 0L;
        return enc.getLastDiskWrittenMs();
    }

    /**
     * Sets the telemetry collector instance for overlay data.
     */
    public void setTelemetryCollector(TelemetryDataCollector collector) {
        this.telemetryCollector = collector;
        if (recorder != null) {
            recorder.setTelemetryCollector(collector);
        }
    }
    
    /**
     * Switch the WebSocket stream sink from this pano pipeline's stream
     * encoder to the OEM Dashcam pipeline's encoder. Called via reflection
     * by {@code CameraDaemon.routeStreamToOemDashcam} when view mode 6 is
     * selected. Returns silently when streaming isn't active or the OEM
     * pipeline isn't running.
     *
     * <p>Bidirectional: when view mode 0..4 is selected later,
     * {@code reattachOwnStreamCallback} reverses this — the WS server is
     * the same instance throughout, only the source encoder changes.
     */
    /**
     * @return true iff the OEM encoder is now feeding the WS sink. Returns
     *         false (with a WARN log) when streaming wasn't enabled, the
     *         OEM pipeline wasn't running, or its encoder hadn't been
     *         constructed yet. Caller surfaces the failure to the client
     *         instead of misreporting success.
     */
    public boolean attachExternalStreamCallback(
            com.overdrive.app.camera.OemDashcamPipeline oemPipeline) {
        streamLifecycleLock.lock();
        try {
            return attachExternalStreamCallbackLocked(oemPipeline);
        } finally {
            streamLifecycleLock.unlock();
        }
    }

    private boolean attachExternalStreamCallbackLocked(
            com.overdrive.app.camera.OemDashcamPipeline oemPipeline) {
        // Held under streamLifecycleLock so we share the lock with
        // disableStreaming. Otherwise: HTTP worker A passes the
        // `streamingEnabled` gate, worker B's disableStreaming acquires
        // the lock, nulls streamScaler + clears OEM publish ref +
        // posts the GL release Runnable; worker A then re-installs the
        // publish ref onto the about-to-be-released scaler and the OEM
        // render loop pins it indefinitely. Symptom: live stream
        // switches feeds 1-2s after a view change because the scaler
        // the OEM loop is publishing into is a stale ref the WS server
        // is no longer reading from.
        if (!streamingEnabled || wsStreamServer == null) {
            logger.warn("attachExternalStreamCallback: streaming not enabled — ignoring");
            return false;
        }
        // A lifecycle pass that started for a prior DVR selection can finish
        // after the user selects an AVM camera. Check the persisted intent
        // while holding the stream lifecycle lock so that stale pass cannot
        // bind the OEM source after the newer selection has detached it.
        if (com.overdrive.app.server.StreamingApiHandler.getLastDesiredViewMode() != 6) {
            logger.info("attachExternalStreamCallback: DVR is no longer the desired view");
            return false;
        }
        if (oemPipeline == null || !oemPipeline.isRouteReady()) {
            logger.warn("attachExternalStreamCallback: OEM route not ready — ignoring");
            return false;
        }
        // Capture the live scaler under the monitor.
        final com.overdrive.app.streaming.GpuStreamScaler liveScaler = streamScaler;
        if (liveScaler == null) {
            logger.warn("attachExternalStreamCallback: streamScaler null — streaming not initialized");
            return false;
        }
        int oemTex = oemPipeline.getCameraTextureId();
        android.graphics.SurfaceTexture oemSt = oemPipeline.getCameraSurfaceTexture();
        if (oemTex == 0 || oemSt == null) {
            logger.warn("attachExternalStreamCallback: OEM texture not yet allocated — ignoring");
            return false;
        }
        try {
            liveScaler.bindOemSource(oemTex, oemSt);
            // Defensive: a concurrent disable that BARELY missed the
            // monitor entry could have just nulled streamScaler and
            // cleared the OEM publish ref. Re-check under our held
            // monitor that the captured scaler is still THE live scaler
            // before we re-install the publish ref. If not, undo and
            // refuse the route.
            if (streamScaler != liveScaler) {
                try { liveScaler.unbindOemSource(); } catch (Throwable ignored) {}
                logger.warn("attachExternalStreamCallback: scaler swapped under us; aborting attach");
                return false;
            }
            oemPipeline.setStreamScalerForOemPublish(liveScaler);
        } catch (Throwable t) {
            logger.warn("attachExternalStreamCallback: streamScaler.bindOemSource failed: "
                + t.getMessage());
            return false;
        }
        if (!externalStreamSourceActive) {
            // This generation remains pending until OEM teardown has
            // finished every pano draw that could sample this texture. A
            // normal view-0..5 switch may unbind it before the OEM lifecycle
            // worker stops the camera, so the later stop still needs a fence.
            oemSourceFenceGeneration++;
        }
        externalStreamSourceActive = true;
        logger.info("Stream sink switched: pano → OEM Dashcam");
        return true;
    }

    /**
     * Select view 6 only after the attached OEM producer has published its
     * first frame transform. A bound texture without a transform has
     * {@code uOemActive=0}; selecting view 6 in that interval falls through
     * to the AVM mosaic shader branch and visibly flashes the pano feed.
     *
     * @return true when view 6 is active now; false when the scaler will
     *         promote it automatically on the producer's first frame, or when
     *         streaming/source state was torn down.
     */
    public boolean activateOemStreamViewWhenReady() {
        streamLifecycleLock.lock();
        try {
            if (!streamingEnabled || !externalStreamSourceActive || streamScaler == null
                    || com.overdrive.app.server.StreamingApiHandler
                            .getLastDesiredViewMode() != 6) {
                return false;
            }
            return streamScaler.requestOemViewWhenReady();
        } finally {
            streamLifecycleLock.unlock();
        }
    }

    /**
     * True when the WS sink is currently bound to an external (OEM)
     * encoder rather than this pipeline's own streamEncoder. Used by
     * reattachOwnStreamCallback to skip the SPS/PPS-resend storm on
     * every view 0..4 click — only fires when there's actually something
     * to swap back.
     */
    private volatile boolean externalStreamSourceActive = false;

    /** True only while the live stream scaler is bound to the OEM source. */
    public boolean isOemStreamSourceActive() {
        return externalStreamSourceActive;
    }

    // Guarded by streamLifecycleLock. A generation advances only when an OEM
    // source becomes active; teardown acknowledges it after the pano GL fence
    // completes. Keeping this separate from externalStreamSourceActive covers
    // an already-detached source whose final GPU draw is still in flight.
    private long oemSourceFenceGeneration = 0L;
    private long oemSourceFencedGeneration = 0L;

    /**
     * Return the latest OEM source generation that still needs a pano GL
     * fence before its texture may be deleted, or 0 if pano never sampled an
     * OEM source since the last successful fence.
     */
    public long getPendingOemSourceFenceGeneration() {
        streamLifecycleLock.lock();
        try {
            return oemSourceFenceGeneration > oemSourceFencedGeneration
                ? oemSourceFenceGeneration : 0L;
        } finally {
            streamLifecycleLock.unlock();
        }
    }

    /** Record successful completion of a pano GL fence for an OEM source. */
    public void completeOemSourceFence(long generation) {
        if (generation <= 0L) return;
        streamLifecycleLock.lock();
        try {
            if (generation > oemSourceFencedGeneration) {
                oemSourceFencedGeneration = generation;
            }
        } finally {
            streamLifecycleLock.unlock();
        }
    }

    /**
     * Restore the AVM mosaic as the streamScaler's source. Called by the
     * existing /api/stream/view/{0..4} path after the scaler view-mode is
     * set. Under the SOTA texture-sharing architecture there's no encoder
     * swap or callback rebind — the same {@code streamEncoder} keeps
     * feeding the WS sink throughout. We only tell the scaler to stop
     * sampling the OEM OES texture and resume reading the AVM mosaic.
     */
    public boolean reattachOwnStreamCallback() {
        // Held under streamLifecycleLock so a concurrent disableStreaming
        // can't null streamScaler between our null-check and the
        // unbindOemSource call (R8 regression #2). Lock is cheap — no
        // GL post inside, just a setter on the scaler and an OEM publish
        // ref clear.
        streamLifecycleLock.lock();
        try {
            // Capture the scaler under the lock; release immediately
            // before invoking unbindOemSource so the call doesn't pin
            // peers, but keep the local reference so a concurrent disable
            // that nulls streamScaler post-capture can't NPE us.
            final com.overdrive.app.streaming.GpuStreamScaler scaler = streamScaler;
            if (!streamingEnabled || scaler == null) return false;
            if (!externalStreamSourceActive) return false;
            externalStreamSourceActive = false;
            try {
                scaler.unbindOemSource();
            } catch (Throwable t) {
                logger.warn("streamScaler.unbindOemSource failed: " + t.getMessage());
            }
            // Stop the OEM render loop's per-frame matrix publish — once
            // the scaler is no longer sampling OEM, the publish is wasted
            // work.
            try {
                com.overdrive.app.camera.OemDashcamPipeline oem =
                    com.overdrive.app.daemon.CameraDaemon.getOemDashcamPipeline();
                if (oem != null) oem.setStreamScalerForOemPublish(null);
            } catch (Throwable ignored) {}
            logger.info("Stream source: OEM → AVM mosaic");
            return true;
        } finally {
            streamLifecycleLock.unlock();
        }
    }

    /**
     * Tracks whether THIS pipeline instance currently holds an active polling
     * refcount on the shared TelemetryDataCollector. The collector is
     * refcount-floored at 0, but asymmetric start/stop calls (start gated on
     * NORMAL_RECORDING, stop unconditional) underflow against the floor and
     * silently consume a future consumer's release. Tracking explicit hold
     * state keeps every start/stop pair balanced regardless of mode.
     */
    private boolean overlayPollingHeld = false;
    // Dedicated poll-hold flag for the ACC-off surveillance overlay flow, kept
    // separate from the pano overlayPollingHeld so the two flows' start/stop
    // pairs on the shared TelemetryDataCollector can never underflow each other.
    private boolean surveillanceOverlayPollingHeld = false;

    /**
     * Select the DASHCAM recording composition layout (0 = standard 360
     * mosaic, 1 = dashcam: 360 front slice on top, 360 left/rear/right below).
     * Persisted in recording.recordingLayout. Stored here and pushed to the
     * recorder only while the pipeline is NOT in surveillance mode — sentry
     * owns its own layout profile (see {@link #setSurveillanceRecordingLayout}).
     * Called from the daemon at startup and from the settings API on change.
     */
    public void setRecordingLayout(int layout) {
        this.recordingLayoutConfig = (layout == 1) ? 1 : 0;
        applyActiveLayoutProfile();
    }

    /**
     * Record the user's preference for sourcing the DASHCAM top band from a
     * dedicated windshield camera (recording.dashcamUseWindshield). Pushed to
     * the producer only while NOT in surveillance mode. The windshield is
     * captured by PanoramicCameraGpu and composited into the recorder's dashcam
     * top band when available; the dashcam layout falls back to the 360
     * front-camera slice (the documented graceful fallback) when it isn't.
     */
    public void setDashcamUseWindshield(boolean useWindshield) {
        this.dashcamUseWindshieldConfig = useWindshield;
        applyActiveLayoutProfile();
    }

    /**
     * Select the SENTRY (surveillance) recording composition layout — the
     * independent counterpart to {@link #setRecordingLayout}. Persisted in
     * surveillance.recordingLayout. Stored here and pushed to the recorder
     * only while the pipeline IS in surveillance mode, so dashcam and sentry
     * recordings can use two different layouts on the one shared recorder.
     * Called from the daemon at startup and from the settings API on change.
     */
    public void setSurveillanceRecordingLayout(int layout) {
        this.surveillanceLayoutConfig = (layout == 1) ? 1 : 0;
        applyActiveLayoutProfile();
    }

    /**
     * SENTRY counterpart to {@link #setDashcamUseWindshield}: sentry's own
     * "use the dedicated windshield camera for the dashcam top band"
     * preference (surveillance.useWindshield). Pushed to the producer only
     * while in surveillance mode.
     */
    public void setSurveillanceUseWindshield(boolean useWindshield) {
        this.surveillanceUseWindshieldConfig = useWindshield;
        applyActiveLayoutProfile();
    }

    /**
     * Push the layout profile that matches the CURRENT pipeline mode to the
     * shared recorder + windshield camera. Surveillance mode uses the
     * surveillance.* profile; every other mode (normal recording / idle) uses
     * the dashcam recording.* profile. The two modes are mutually exclusive,
     * so a single recorder serves both by re-applying the right profile on each
     * mode transition, recorder (re)creation, and config change. A change to
     * the INACTIVE profile is stored but not shown until that mode is entered.
     */
    private void applyActiveLayoutProfile() {
        boolean surveillance = currentMode == Mode.SURVEILLANCE;
        int layout = surveillance ? surveillanceLayoutConfig : recordingLayoutConfig;
        boolean useWindshield = surveillance
            ? surveillanceUseWindshieldConfig : dashcamUseWindshieldConfig;
        if (recorder != null) {
            // GpuMosaicRecorder.setRecordingLayout early-returns when unchanged.
            recorder.setRecordingLayout(layout);
        }
        applyWindshieldToCamera(useWindshield);
    }

    /**
     * Push the given windshield-source preference to the producer: resolve the
     * windshield camera id for this vehicle and enable/disable the dedicated
     * windshield capture accordingly. No-op until the camera exists (re-applied
     * on the next mode transition / recorder creation). PanoramicCameraGpu
     * opens/closes the camera on its GL thread and falls back to the 360 front
     * slice if it can't open it. Callers (config setters + mode transitions on
     * ACC events) are infrequent, so re-resolving + pushing here is never on a
     * hot path.
     */
    private void applyWindshieldToCamera(boolean useWindshield) {
        PanoramicCameraGpu cam = this.camera;
        if (cam == null) return;
        int windshieldCameraId = -1;
        try {
            windshieldCameraId = com.overdrive.app.camera.CameraConfigResolver
                .resolve(getVehicleModel())
                .getDirectCameraIdForRole(com.overdrive.app.camera.CameraRole.WINDSHIELD);
        } catch (Throwable t) {
            windshieldCameraId = -1;
        }
        this.windshieldCameraIdConfig = windshieldCameraId;
        cam.setDashcamWindshieldCamera(
            useWindshield && windshieldCameraId >= 0, windshieldCameraId);
    }

    /**
     * Enables or disables the telemetry overlay.
     * Starts/stops the telemetry collector based on current recording mode.
     *
     * <p>Refcount discipline: start path is gated on {@code enabled &&
     * currentMode == NORMAL_RECORDING}; stop path mirrors that gate via the
     * {@code overlayPollingHeld} flag so we never issue more stops than
     * starts. Without this, a user toggling overlay-off outside NORMAL_RECORDING
     * issues an unmatched decrement that the collector's atomic floor
     * absorbs but that steals the next legitimate consumer's release.
     */
    // The overlay flow the recorder is CURRENTLY configured for. The shared
    // recorder serves both normal/manual clips (pano) and sentry event clips
    // (surveillance) but only one at a time; this tracks which selection is
    // loaded so a live field edit knows whether it applies. Written only from
    // the overlay-apply paths.
    private volatile String activeOverlayFlow = "pano";

    /**
     * Resolve a specific overlay flow ("pano" or "surveillance"), push its
     * master-enable + field selection to the shared recorder, and reconcile the
     * telemetry-collector polling hold. Centralizes what the scattered
     * record-start sites used to do inline.
     *
     * <p>The flow is chosen by the CALL SITE (normal-recording sites pass
     * "pano"; the sentry-event path passes "surveillance"), NOT by ACC state —
     * so manual recording while parked stays on the pano flow exactly as before.
     *
     * <p>No-regression: for "pano" with the legacy toggle on and no explicit
     * field list, this resolves to exactly {@code overlayEnabledConfig} + the
     * legacy eight fields — identical to before. Surveillance is a separate
     * opt-in that defaults off.
     */
    /**
     * Push a flow's field selection to the shared recorder WITHOUT touching the
     * polling refcount or the master-enable bit. Called from the record-start
     * sites (pano) and the sentry-event hook (surveillance) so the drawn field
     * set matches the clip being written. This is deliberately additive — the
     * original polling mechanics at each site are left exactly as they were, so
     * no refcount balance changes.
     */
    private void pushOverlayFieldsForFlow(String flow) {
        boolean surv = "surveillance".equals(flow);
        this.activeOverlayFlow = surv ? "surveillance" : "pano";
        if (recorder != null) {
            recorder.setOverlayDemandKey("pano"); // shared recorder → single demand key
            recorder.setOverlayFields(resolveFieldsForFlow(activeOverlayFlow));
        }
    }

    /** Load the persisted field selection for a flow, or the legacy default. */
    private com.overdrive.app.telemetry.TelemetryFields resolveFieldsForFlow(String flow) {
        try {
            org.json.JSONArray arr = com.overdrive.app.config.UnifiedConfigManager
                    .getTelemetryOverlayFields(flow);
            return com.overdrive.app.telemetry.TelemetryFields.fromJsonArray(arr);
        } catch (Throwable t) {
            return com.overdrive.app.telemetry.TelemetryFields.legacyDefault();
        }
    }

    /**
     * Enable/disable the telemetry overlay for a sentry EVENT clip, called by
     * the surveillance engine around {@link SurveillanceEngineGpu} event
     * recording. Opt-in and default-off, so event clips are unchanged until the
     * user turns the surveillance overlay on. Compositing/polling only runs for
     * the actual event window — never while merely armed.
     *
     * @param recordingActive true at event start, false at event stop
     */
    void applySurveillanceOverlayForEvent(boolean recordingActive) {
        if (recorder == null) return;
        if (recordingActive && surveillanceOverlayEnabledConfig) {
            // Push surveillance field selection + enable the master + open the
            // composite gate for this event clip.
            pushOverlayFieldsForFlow("surveillance");
            recorder.setOverlayEnabled(true);
            recorder.setOverlayRecordingModeAllowed(true);
            // Start telemetry polling for the event window. Uses a DEDICATED
            // surveillance hold flag (not the pano overlayPollingHeld) so the
            // two flows' start/stop pairs can never underflow each other on the
            // shared collector — the sentry event and an ACC-on clip never
            // overlap in practice, but the separate flag makes it safe if the
            // pipeline is torn down/rebuilt across an ACC edge mid-event.
            if (telemetryCollector != null && !surveillanceOverlayPollingHeld) {
                telemetryCollector.setOverlayRecordingActive(true);
                telemetryCollector.startPolling();
                surveillanceOverlayPollingHeld = true;
            }
        } else {
            recorder.setOverlayRecordingModeAllowed(false);
            // Restore the recorder's master-enable bit to the PANO config. The
            // recorder is SHARED and persists across the ACC-off→ACC-on edge
            // (onAccOn reuses it, never recreates it). Without this reset, a
            // sentry event that set overlayEnabled=true would leave it true, and
            // a later ACC-on pano/dashcam clip (which only sets
            // overlayRecordingModeAllowed, never overlayEnabled) would open the
            // composite gate and burn in the overlay even with the pano toggle
            // OFF. Resetting to overlayEnabledConfig keeps the two masters
            // independent as documented.
            recorder.setOverlayEnabled(overlayEnabledConfig);
            if (telemetryCollector != null && surveillanceOverlayPollingHeld) {
                telemetryCollector.setOverlayRecordingActive(false);
                telemetryCollector.stopPolling();
                surveillanceOverlayPollingHeld = false;
            }
        }
    }

    /**
     * Set the ACC-off surveillance overlay master. Independent of the ACC-on
     * (pano) master. Takes effect at the next sentry event's record-start via
     * {@link #applySurveillanceOverlayForEvent}; if a surveillance clip is
     * already recording, apply live.
     */
    public void setSurveillanceOverlayEnabled(boolean enabled) {
        this.surveillanceOverlayEnabledConfig = enabled;
        if ("surveillance".equals(activeOverlayFlow)
                && recorder != null && recorder.isRecording()) {
            applySurveillanceOverlayForEvent(enabled);
        }
    }

    /**
     * Push a live field-selection change for a flow to the recorder if that
     * flow is the one currently loaded. Lets the settings API update the drawn
     * fields mid-clip without a restart. No-op when the changed flow isn't live.
     */
    public void refreshOverlayFields(String flow) {
        if (recorder != null && activeOverlayFlow.equals(flow)) {
            recorder.setOverlayFields(resolveFieldsForFlow(flow));
        }
    }

    public void setOverlayEnabled(boolean enabled) {
        this.overlayEnabledConfig = enabled;
        // Push the pano field selection to the recorder (additive — does not
        // alter polling). Then reproduce the EXACT legacy polling mechanics.
        pushOverlayFieldsForFlow("pano");
        if (recorder != null) {
            recorder.setOverlayEnabled(enabled);
        }
        if (telemetryCollector == null) return;

        boolean shouldHold = enabled && currentMode == Mode.NORMAL_RECORDING;
        if (shouldHold && !overlayPollingHeld) {
            telemetryCollector.setOverlayRecordingActive(true);
            telemetryCollector.startPolling();
            overlayPollingHeld = true;
        } else if (!shouldHold && overlayPollingHeld) {
            telemetryCollector.setOverlayRecordingActive(false);
            telemetryCollector.stopPolling();
            overlayPollingHeld = false;
        }
    }
}
