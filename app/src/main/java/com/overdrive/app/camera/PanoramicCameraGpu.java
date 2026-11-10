package com.overdrive.app.camera;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.GpuDownscaler;
import com.overdrive.app.surveillance.FoveatedCropper;
import com.overdrive.app.surveillance.GpuMosaicRecorder;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import com.overdrive.app.surveillance.SurveillanceEngineGpu;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PanoramicCameraGpu - GPU Edition with Zero-Copy Pipeline.
 * 
 * This is the GPU-native version of PanoramicCamera that replaces ImageReader
 * with SurfaceTexture. Camera frames flow directly to GPU texture, enabling:
 * - Zero-copy recording (camera → GPU → encoder)
 * - Minimal AI readback (GPU downscales to 320x240)
 * - <10% total CPU usage
 * 
 * Architecture:
 * - Camera writes to GL_TEXTURE_EXTERNAL_OES via SurfaceTexture
 * - Render loop on dedicated GL thread distributes frames to:
 *   - Recording Lane: GpuMosaicRecorder (zero-copy to encoder)
 *   - AI Lane: GpuDownscaler (2 FPS readback for motion detection)
 */
public class PanoramicCameraGpu {
    private static final String TAG = "PanoramicCameraGpu";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final int PHYSICAL_CAMERA_ID = 1;
    private static final int MAX_CAMERA_ID = 5;     // Probe camera IDs 0-5

    /** Sentry-restart straddle token. The CameraDaemon dilink4 ACC-OFF
     *  handler acquires this BEFORE calling gpuPipeline.stop(), so the
     *  BydApaViewpointHelper observer set never goes empty during the
     *  close+gate+reopen window. Releases automatically on ACC ON or after
     *  the new pipeline registers its own per-instance token (whichever
     *  comes first). Mirrors oem's behaviour where the FlameoutService
     *  keeps no observer of its own but the new C5319i registers its
     *  fresh C5920a into C6498a.observerSet during the reopen, BEFORE the
     *  caller's own MainService-side C5320a was released — so the set
     *  stays non-empty across the whole flow.
     *
     *  We approximate that by acquiring the bridge token explicitly at
     *  ACC-OFF (before stop) and releasing it explicitly after start
     *  completes. Use a static singleton — there's only ever one ACC-OFF
     *  in flight per process. */
    private static final Object SENTRY_BRIDGE_TOKEN = new Object() {
        @Override public String toString() { return "sentry-bridge"; }
    };
    private static volatile boolean sentryBridgeHeld = false;

    /** Called by CameraDaemon at ACC-OFF, BEFORE gpuPipeline.stop().
     *  Idempotent. */
    public static void acquireSentryBridgeViewpoint() {
        if (sentryBridgeHeld) {
            logger.info("sentry-bridge token already held");
            return;
        }
        BydApaViewpointHelper.acquire(SENTRY_BRIDGE_TOKEN);
        sentryBridgeHeld = true;
        logger.info("sentry-bridge viewpoint token acquired (straddling stop+reopen)");
    }

    /** Called by CameraDaemon AFTER gpuPipeline.start() returns successfully
     *  (the new pipeline instance has acquired its own per-instance token).
     *  Also called on ACC ON as a safety net. Idempotent. */
    public static void releaseSentryBridgeViewpoint() {
        if (!sentryBridgeHeld) return;
        BydApaViewpointHelper.release(SENTRY_BRIDGE_TOKEN);
        sentryBridgeHeld = false;
        logger.info("sentry-bridge viewpoint token released");
    }

    // AVMCamera surface mode — 0 works on Seal, Atto 1 may need different value
    // Set via setCameraSurfaceMode() before start() for per-model override.
    // On the oem SurfaceTexture path this same value is the previewIndex
    // passed to addTexture/setTexture/rmTexture — 0=firmware-default panorama
    // output, 1-4=individual viewpoints.
    private int cameraSurfaceMode = 0;

    // Frame-ingestion path selector. Three modes, persisted in unified
    // config under camera.cameraMode:
    //   "default" → legacy ImageReader + 4-strip → 2x2 rearrangement.
    //   "dilink4" → oem SurfaceTexture (addTexture + setTexture +
    //               previewIndex). Normal mode uses layout 3 for the known
    //               four-corner remap; passive APA uses layout 1 to record
    //               preview port 0 unchanged.
    //   "dilink5" → fast_cam_capture DMA-BUFs composited into a persistent
    //               GL_TEXTURE_2D, with a CPU upload fallback.
    //
    // Resolved at construction. 0=legacy strip, 1=full-frame passthrough,
    // 3=DiLink 4 remapped 2x2 mosaic.
    private final boolean USE_DILINK5_QCARCAM_PATH =
        com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
    // "camera2": the legacy ImageReader strip layout, fed by an NDK Camera2
    // session on the AVM super-frame camera instead of AVMCamera.
    private final boolean USE_CAMERA2_AVM_PATH =
        com.overdrive.app.camera.camera2.Camera2Platform.isSelected();
    private final int CAMERA_LAYOUT_MODE = resolveCameraLayoutModeFromConfig();
    private final boolean USE_OEM_SURFACE_TEXTURE_PATH = CAMERA_LAYOUT_MODE != 0;
    private final boolean USE_DILINK4_AVM_PATH =
        USE_OEM_SURFACE_TEXTURE_PATH && !USE_DILINK5_QCARCAM_PATH;
    // DiLink 4-only compatibility path: leave the OEM panorama output untouched
    // and consume preview port 0 exactly as supplied by the HAL.
    private final boolean USE_PASSIVE_APA_MODE =
        CAMERA_LAYOUT_MODE == 1 && !USE_DILINK5_QCARCAM_PATH;

    // Decoupled encoder lane (BYD native-AVM starvation fix). When enabled on
    // the LEGACY ImageReader path, every consumer pass samples an app-owned
    // COPY of the camera frame (CopiedFrameRing) instead of the camera-owned
    // gralloc buffer; the bound Image/HardwareBuffer is released as soon as
    // the copy's GPU fence signals (instead of at the NEXT bind); and the
    // recorder's encoder-blocking GL (makeCurrent + mosaic draw +
    // eglSwapBuffers — the calls that stall 100-300ms under MediaCodec
    // backpressure) runs on a dedicated EncoderLane thread with a shared EGL
    // context. The acquisition thread therefore never waits on the encoder,
    // so ImageReader gralloc slots return to the BYD HAL producer at frame
    // cadence even during encoder stalls — the starvation mechanism behind
    // the native AVM losing its reverse-gear feed while we keep recording.
    //
    // Opt-in per vehicle via camera.decoupledEncoderLane (default false =
    // shipped zero-copy behaviour, bit-identical). Applied at pipeline
    // construction like every other USE_* path selector — never hot-swapped.
    // Legacy path only: DiLink 5 already publishes app-owned 2D textures
    // (native DMA compositor) and DiLink 4's SurfaceTexture path has a
    // different buffer-lifetime model (updateTexImage auto-recycles).
    private final boolean USE_DECOUPLED_ENCODER_LANE =
        !USE_OEM_SURFACE_TEXTURE_PATH
        && !USE_DILINK5_QCARCAM_PATH
        && !USE_CAMERA2_AVM_PATH
        && resolveDecoupledEncoderLaneFromConfig();

    private static boolean resolveDecoupledEncoderLaneFromConfig() {
        try {
            org.json.JSONObject cam = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            return cam != null && cam.optBoolean("decoupledEncoderLane", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the decoupled encoder lane is active (flag on + legacy path).
     *  Consumers use this to pick the 2D windshield sampler variant; the
     *  camera sampler variant rides the existing {@link #isTexture2D()}. */
    public boolean isDecoupledEncoderLane() {
        return USE_DECOUPLED_ENCODER_LANE;
    }

    private static int resolveCameraLayoutModeFromConfig() {
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            return 1;
        }
        if (!com.overdrive.app.camera.dilink5.DiLink5Platform
                .isDiLink4Selected()) {
            return 0;
        }
        try {
            org.json.JSONObject cam = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            if (cam == null) return 0;
            return Di4AvcViewpointPolicy.cameraLayoutMode(
                "dilink4",
                cam.optBoolean("dilink4PassiveApaMode", false));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Effective camera-layout mode for downstream consumers.
     *  0 = 4-strip → 2x2 rearrangement (legacy);
     *  1 = full-frame passthrough (DiLink 4 passive APA / DiLink 5);
     *  3 = DiLink 4 four-corner remap. */
    public int getCameraLayoutMode() {
        return CAMERA_LAYOUT_MODE;
    }
    
    // Camera ID override — set via setCameraId() before start()
    private int cameraIdOverride = -1;  // -1 = use default PHYSICAL_CAMERA_ID
    
    // SOTA: Full-matrix auto-probe — sweeps camera IDs 0-5 × surface modes 0-5
    // to find the first combination that produces panoramic image data.
    private boolean autoProbeCameras = false;
    // When true, skip frame-15/50 validation entirely (user manually set camera ID)
    private boolean skipFrameValidation = false;
    private int probeStartId = -1;  // Tracks where probe started for wrap-around detection
    private int probeNextCameraId = 0;    // Next camera ID to try
    private int probeNextSurfaceMode = 0; // Next surface mode to try
    
    // SOTA: Probe gate — blocks recording/streaming/AI until probe finds a working camera.
    // Without this, the encoder records BLACK frames and the stream shows garbage during probe.
    // Defaults to true (no gate) — only set to false when setAutoProbeCameras(true) is called.
    private volatile boolean probeComplete = true;
    
    // Track the last camera ID that delivered non-black data during probe.
    // If the probe exhausts all IDs without finding a verified strip, fall back
    // to this camera — it's better to record from a real camera than nothing.
    private int lastDataCameraId = -1;
    
    // Callback when auto-probe discovers a working camera config
    public interface CameraProbeCallback {
        void onCameraFound(int cameraId, int surfaceMode);
    }
    private CameraProbeCallback probeCallback;
    
    // Camera dimensions
    private final int width;
    private final int height;
    
    // EGL and OpenGL
    private EGLCore eglCore;
    private android.opengl.EGLSurface dummySurface;  // Pbuffer for headless context
    private int cameraTextureId;
    // CRASH FIX (cross-context use-after-free): the AI-lane GL thread (AiLaneGl,
    // 2nd shared EGL context) samples cameraTextureId for readback/foveated while
    // this render thread can rebind a new EGLImage onto the same id or free the
    // prior gralloc buffer (consumeLatestImageAndBind / consumeSurfaceTextureFrame
    // / releaseCameraConsumer). Sampling a freed/swapped backing buffer mid-readback
    // faults the Adreno driver (SIGSEGV in libGLESv2_adreno). Both sides hold this
    // monitor around their texture access; the AI lane (AiLaneGl.processOnce) also
    // re-checks isCameraTextureValid() inside the lock + issues glFinish() before
    // releasing, so the GPU sample completes before the encoder can recycle the
    // buffer. See AiLaneGl.CameraState.cameraTextureLock()/isCameraTextureValid().
    private final Object cameraTextureLock = new Object();

    // ==================== Decoupled encoder lane state ====================
    // (camera.decoupledEncoderLane — see USE_DECOUPLED_ENCODER_LANE comment.)
    // GL-side objects are GL-thread-confined; EncoderLane is thread-safe.
    //
    // cameraOesTextureId is the PRIVATE EXTERNAL_OES bind target for the HAL
    // gralloc buffer (copy source). cameraTextureId — what every consumer
    // samples — is republished each frame as the freshly-copied ring slot
    // (2D), exactly the way the DiLink 5 path republishes its compositor
    // output texture.
    private int cameraOesTextureId;                 // GL thread
    private CopiedFrameRing decoupledCamRing;       // GL thread (writer)
    private CopiedFrameRing decoupledWsRing;        // GL thread (writer), lazy
    private volatile EncoderLane encoderLane;
    private int decoupledLastWsSlot = -1;           // GL thread
    // =======================================================================

    // Camera consumer: ImageReader → AHardwareBuffer → EGLImage →
    // cameraTextureId. Bypasses SurfaceFlinger throttling that clamps the
    // SurfaceTexture path to ~8.5 fps on DiLink50 5.0UI builds (verified by
    // AvmImageReaderFpsProbe → 26 fps panoramic). cameraSurface is what we
    // hand to AVMCamera.addPreviewSurface — sourced from ImageReader.getSurface().
    // minSdk=28 enforces Image.getHardwareBuffer availability.
    private ImageReader cameraImageReader;
    private Surface cameraSurface;
    // SurfaceTexture-backed consumer for the oem-style path (addTexture /
    // setTexture / rmTexture). Lifetime mirrors cameraImageReader: created
    // by createCameraSurfaceTexture(), freed by releaseCameraConsumer().
    // Bound directly to cameraTextureId — no separate gralloc handoff.
    private volatile SurfaceTexture cameraSurfaceTexture;

    // DiLink 4 SurfaceTexture callbacks MUST NOT run on glHandler. The render
    // runnable blocks that looper in frameSync.wait(), so a callback posted to
    // the same looper cannot wake the wait and the path self-paces at the wait
    // timeout instead of at producer cadence. Keep one lightweight callback
    // thread for the lifetime of this pipeline; same-handle SurfaceTexture
    // rebinds reuse it, and releaseGl retires it after the consumer is fenced.
    //
    // The state lock makes consumer replacement atomic with callback
    // acceptance. setOnFrameAvailableListener(null) does not retract a message
    // already queued on a Handler, so identity alone is not enough without a
    // generation fence: an old SurfaceTexture callback could otherwise arrive
    // after rebind, advance the new consumer's sequence and falsely cancel
    // producer recovery.
    private HandlerThread diLink4FrameCallbackThread;
    private Handler diLink4FrameCallbackHandler;
    private final Object diLink4SurfaceTextureStateLock = new Object();
    private int diLink4SurfaceTextureEpoch = 0;

    // Optional direct windshield camera used by the dashcam recording layout.
    // Field verification on Tang: pano camera 2 and windshield camera 0 stream
    // concurrently. This path is opened only when the user selects dashcam +
    // windshield source; if open/bind fails the recorder falls back to the
    // 360-front slice without dropping frames.
    private ImageReader windshieldImageReader;
    private Surface windshieldSurface;
    private Object windshieldCameraObj;
    private int windshieldTextureId;
    private volatile boolean windshieldEnabled = false;
    private volatile int windshieldCameraId = -1;
    // Legacy AVMCamera open/start/close calls are uninterruptible vendor
    // Binder transactions. Keep their ownership visible to stop() and latch
    // teardown closed after a hard timeout; process retirement is then the
    // only safe cleanup authority.
    private final AtomicBoolean legacyWindshieldCameraLifecycleInFlight =
        new AtomicBoolean(false);
    private final AtomicBoolean legacyWindshieldCameraTerminalRestart =
        new AtomicBoolean(false);
    private volatile boolean windshieldPending = false;
    private boolean windshieldStarted = false;
    private boolean windshieldOpenFailed = false;
    private boolean windshieldFrameReady = false;
    private Image windshieldBoundImage;
    private HardwareBuffer windshieldBoundHwBuffer;
    private long windshieldFrameCount = 0;
    // GL-thread-confined: fps last pushed to the legacy windshield HAL; 0 = untracked path.
    private int windshieldAppliedFps = 0;
    // GL-thread-confined: wall-clock ms of the most recent successful
    // windshield frame bind. Used by the render loop to detect a STALLED
    // windshield feed — once frames stop arriving (AVMCamera handle
    // contention from pano + OEM-dashcam + windshield all competing, a HAL
    // pause, or a silent open that never delivers), windshieldFrameReady
    // stays latched true and drawFrame would composite the last bound
    // HardwareBuffer forever → the "stuck on a static frame" symptom in the
    // dashcam recording layout. On stall we drop windshieldFrameReady (so the
    // recorder falls back to the live 360 front in the top band — never a
    // frozen image) and schedule one throttled reopen attempt.
    private long windshieldLastFrameMs = 0;
    private long windshieldLastReopenMs = 0;
    // No new frame for this long ⇒ treat the windshield as stalled. Matches
    // the main-camera FRAME_STALL_THRESHOLD_MS (4s) so a transient HAL/IO
    // hiccup doesn't flap the top band between windshield and 360 front.
    private static final long WINDSHIELD_STALL_THRESHOLD_MS = 4000;
    // Don't hammer close+reopen: a single-client HAL that refuses the second
    // client would otherwise spin. One attempt per this interval.
    private static final long WINDSHIELD_REOPEN_MIN_INTERVAL_MS = 10_000;
    // Dedicated handler for ImageReader.OnImageAvailableListener. MUST be
    // separate from glHandler — renderLoop blocks the GL thread on
    // frameSync.wait(), which would starve the listener if it ran on the
    // same looper. The callback hops to glHandler.post for the actual GL
    // bind work via onHalImageAvailable.
    private HandlerThread imageReaderThread;
    private Handler imageReaderHandler;
    
    // Camera object (via reflection).
    // volatile because reopenCamera() runs on the daemon thread and writes
    // cameraObj while the GL render thread reads it in renderLoop(). Without
    // volatile, the GL thread could observe a stale non-null cameraObj after
    // we've torn down the BYD HAL and block in updateTexImage() against a
    // dead BufferQueue (which is what was tripping the GL watchdog on
    // ACC OFF→ON transitions).
    private volatile Object cameraObj;

    /** Per-instance BydApaViewpointHelper observer-set token. Mirrors oem
     *  C5920a's "self" registration into C6498a.observerSet (C5920a.java:323
     *  /:387 — `C6498a.f26622a.m28933k(this)` / `m28930h(this)`). Scoping
     *  the token to this instance lets a separate sentry caller acquire its
     *  own token at ACC-OFF and straddle our close+reopen — keeping the set
     *  non-empty so the helper never writes viewpoint=0 mid-session. */
    private final Object viewpointToken = new Object() {
        @Override public String toString() { return "PanoramicCameraGpu@" + System.identityHashCode(this); }
    };

    // Render loop
    // Reuse the same callback for every frame. Recreating the bound method
    // reference for every Handler.post can allocate on the camera GL path.
    private final Runnable renderLoopCallback = this::renderLoop;
    private HandlerThread glThread;
    private Handler glHandler;
    private volatile boolean running = false;
    private volatile long activeStartEpoch = Long.MIN_VALUE;
    private final Object frameSync = new Object();
    // State-backed signal between the HAL callback (onHalImageAvailable) and
    // the GL render loop. Plain notify()/wait() races: if the HAL fires while
    // the GL thread is mid-processing (not yet in wait()), the notification
    // is dropped and the GL thread blocks for up to 100 ms before the NEXT
    // HAL fire wakes it — capping effective FPS well below the HAL emission
    // rate. The pending flag closes the race: HAL sets it, GL skips wait()
    // when it's already set, and clears it before processing.
    private volatile boolean imagePending = false;
    
    // Consumers
    // volatile: the stall watchdog thread reads both to decide whether a consumer is
    // starved, and encoder is wired ~1.5s AFTER that thread starts.
    private volatile GpuMosaicRecorder recorder;
    private volatile HardwareEventRecorderGpu encoder;  // Direct encoder reference for draining
    // Volatile so the GL render loop's snapshot read at drawFrame's
    // top-of-loop sees stream-disable's null-write atomically. Without
    // volatile, the GL thread can cache a stale ref past the disable
    // and call drawFrame on a released scaler (EGL surface destroyed,
    // program deleted) — undefined behaviour on Adreno.
    private volatile com.overdrive.app.streaming.GpuStreamScaler streamScaler;  // Stream scaler (optional)
    private volatile HardwareEventRecorderGpu streamEncoder;  // Stream encoder (optional)
    // Dedicated blind-spot lane (views 7/8). A SECOND independent scaler+encoder
    // fed from the SAME camera texture each render-loop iteration (read-only
    // fan-out, exactly like the stream lane). Kept fully separate from the stream
    // lane so the blind-spot overlay never contends with / hijacks the live-view
    // stream's view mode, quality, or WS. Null until setBsStreamingComponents().
    private volatile com.overdrive.app.streaming.GpuStreamScaler bsStreamScaler;
    private volatile HardwareEventRecorderGpu bsStreamEncoder;
    private volatile boolean bsLayerVisible = false;
    // BS render diagnostics (throttled): counts PASS-1C drawFrame calls + records why
    // it was skipped, so a "card composites but stays BLACK" report can be triaged from
    // the log (is drawFrame even running? is the texture valid?) instead of guessing.
    private long bsDiagFrames = 0L;
    private long bsDiagLastLogMs = 0L;
    private long bsDiagSkipScaler = 0L;   // skipped: scaler null
    private long bsDiagSkipHidden = 0L;   // skipped: bsLayerVisible false
    private GpuDownscaler downscaler;
    /** Lazy-allocated full-resolution sampler for the camera-mapping dialog.
     *  Lives on the GL handler; allocates GL resources on first use. */
    private HighResPreviewSampler highResSampler;
    private SurveillanceEngineGpu sentry;
    private FoveatedCropper foveatedCropper;  // High-res AI crop from raw strip
    
    // Frame timing
    private int frameCounter = 0;
    // AI lane is fully decoupled from the GL thread (AiLaneWorker). GL thread
    // produces downscaled frames at camera rate; worker consumes at its own
    // pace and drops frames when busy. V2 motion's internal 100ms throttle
    // (MOTION_PROCESS_INTERVAL_MS) keeps actual processing at ~10 fps so
    // there's no need for a separate frame-skip counter on the GL side.
    private com.overdrive.app.camera.AiLaneWorker aiLaneWorker;
    // Tier-1 SOTA fix: dedicated AI-lane GL thread on a shared EGL context.
    // The encoder GL thread now does ONLY consume→draw→swap; the readback
    // and foveated crops live here, on a separate hardware-queue submission
    // path that no longer stalls eglSwapBuffers when YOLO OpenCL is busy.
    // volatile: written on the GL thread (ensure/release), read on the GL
    // thread in renderLoop. volatile is belt-and-suspenders for the rare
    // cross-thread reader (releaseGl runs the shutdown net) — see note there.
    private volatile AiLaneGl aiLaneGl;
    // CameraState captured at start() for the LAZY AI-lane bring-up. The lane
    // is created on the first surveillance-active frame, not at pipeline start.
    private AiLaneGl.CameraState aiCameraStateRef;
    // Single-flight guard so ensure/release toggle cleanly and two near-
    // simultaneous arm frames can't double-start the lane. GL-thread-confined.
    private boolean aiLaneStarting = false;
    // Monotonic per-bound-frame counter. AiLaneGl polls this via the
    // CameraState callback to detect "is there a new frame to read?"; we
    // bump it after every successful HAL bind in consumeLatestImageAndBind.
    private final java.util.concurrent.atomic.AtomicLong cameraFrameSeq =
            new java.util.concurrent.atomic.AtomicLong(0);
    // Last measured camera FPS, computed in the 2-min Stats log. Surfaced
    // via getMeasuredFps() so the UI can show actualFps when it falls below
    // requested (HAL clamp; e.g. user requests 30, HAL emits ~26).
    private volatile float measuredFps = 0f;
    private long lastFrameTime = 0;
    private volatile long lastCameraStartTime = 0;
    // DiLink 4: track last error-restart so we can throttle tight reopen
    // loops when the HAL keeps emitting event=8. oem-parity: 60 s, not 30 s.
    // The AVM HAL daemon (vendor.byd.avm) needs ~60 s after a
    // DAEMON_DIED/SERVER_DIED event to (1) respawn, (2) re-handshake the MCU/
    // ISP rail, (3) re-allocate gralloc pool. Reopens inside that window catch
    // the daemon mid-respawn — accept the open, hand back a buffer, but the
    // calibration isn't done yet, so frames are black/garbage. Oem hardcodes
    // 60_000L in p290le/C7340b.java:595 (m32197w aka tryRestart). Only
    // consulted on USE_OEM_SURFACE_TEXTURE_PATH; legacy cars unchanged.
    private volatile long lastErrorRestartTime = 0;
    private static final long DILINK4_ERROR_RESTART_MIN_INTERVAL_MS = 60_000L;
    private long startTime = 0;
    
    // Watchdog for GL thread hang detection
    private volatile long lastGlThreadHeartbeat = 0;
    private Thread watchdogThread;
    private static final long GL_THREAD_TIMEOUT_MS = 3000;
    // Extended timeout for initial camera warmup — the BYD panoramic camera HAL
    // can take several seconds to deliver the first frame. During this period the
    // GL thread is legitimately blocked on frameSync.wait(), not deadlocked.
    private static final long GL_THREAD_WARMUP_TIMEOUT_MS = 10000;
    // A stop racing an ordinary legacy open gets a brief chance to observe the
    // uninterruptible vendor Binder call finish. Beyond this, touching the same
    // partial handle or EGL state from teardown is unsafe; process retirement
    // owns cleanup.
    private static final long LEGACY_OPEN_STOP_GRACE_MS = 2_000L;
    private volatile boolean firstFrameReceived = false;
    
    // BYD camera coordinator for legacy polling and AVMCamera error events.
    // DI4 deliberately skips IBYDCameraUser ownership arbitration: its single
    // panoramic producer remains open continuously while the native UI co-consumes it.
    // DiLink 5 uses the dedicated source-handoff fields below.
    private BydCameraCoordinator cameraCoordinator;
    private volatile boolean cameraYielded = false;
    private volatile boolean diLink5ReverseRequested = false;
    private volatile boolean diLink5ReverseHandoffComplete = true;
    private volatile boolean diLink5AvmPreservedForReverse = false;
    // Reverse gear needs the physical FastCam inputs released to the system
    // AVM, but it must not run the generic yield lifecycle: that lifecycle
    // finalizes the current MP4, detaches live-view, and restarts recorder
    // state. This flag marks the narrower DI5 source-only handoff so reacquire
    // reopens the producer without touching recorder/streaming sessions.
    private volatile boolean diLink5ReverseSourcePaused = false;
    // Source-only recovery is also used for an active FastCam frame stall.
    // Keep this separate from the reverse-state flag so health recovery can
    // preserve recorder/live-view sessions without pretending system AVM owns
    // the cameras.
    private volatile boolean diLink5SourceOnlyReacquire = false;
    // Coalesce camera-error/stall recovery requests until either the first
    // replacement frame arrives or the attempt moves to a deferred retry.
    // The epoch invalidates a queued recovery when reverse or shutdown
    // supersedes it, preventing a stale task from closing a newly resumed
    // source after an R -> D transition.
    private final AtomicBoolean diLink5SourceRecoveryActive =
            new AtomicBoolean(false);
    private final AtomicInteger diLink5SourceRecoveryEpoch =
            new AtomicInteger(0);
    private volatile boolean diLink5ReverseSourceTransitionInProgress = false;
    // Dedicated render/AI/preview gate while system reverse owns the physical
    // inputs. Unlike cameraYielded, this never finalizes recording or detaches
    // streaming. It remains closed across the R -> D settle delay and opens
    // only immediately before a verified source reopen/resume.
    private volatile boolean diLink5SystemAvmFrameGate = false;
    // A reverse/source-health resume has left the source-handoff state but has
    // not yet
    // delivered a frame from the replacement FastCam session. This is distinct
    // from the legacy cameraYielded lifecycle: DI5 preflight can spend several
    // seconds with cameraObj == null. The frame watchdog must not reinterpret
    // the previous session's timestamp as an active-stream stall during that
    // ownership gap.
    private volatile boolean diLink5ReacquireInProgress = false;
    private volatile long diLink5ReacquireStartedAtMs = 0L;
    // Parked idle: RMM closes the FastCam source while nothing needs frames (fast_cam_capture
    // otherwise streams every camera at its launch fps); any consumer demand reopens it.
    private volatile boolean diLink5IdleSourceReleased = false;
    private final AtomicInteger diLink5IdleDemandEpoch = new AtomicInteger(0);
    private volatile long diLink5IdleLastDemandElapsedMs = 0L;
    private final AtomicBoolean diLink5IdleResumeQueued = new AtomicBoolean(false);
    // From queuing a resume until its first frame: previews wait, releases hold off.
    private volatile boolean diLink5IdleResumePending = false;
    // Set only once an idle resume has actually started its reacquire; 0 otherwise. Uptime clock, so
    // a suspend mid-reopen does not count against the budget.
    private volatile long diLink5IdleResumeStartedAtUptimeMs = 0L;
    // A failed idle reopen: pending stays set until a real frame or stop(), whoever else asks.
    private volatile boolean diLink5IdleResumeFailed = false;
    private final Object diLink5IdleResumeFailureLock = new Object();
    // Elapsed time until which idle release stays off after a failed close or reopen (-1 unknown).
    private volatile long diLink5IdleReleaseBlockedUntilElapsedMs = -1L;
    private static final String DILINK5_IDLE_RELEASE_BLOCK_PATH =
            com.overdrive.app.util.ScratchRoot.path("overdrive_dilink5_idle_release_blocked");
    private static final long DILINK5_IDLE_RELEASE_BLOCK_MS = 12L * 60L * 60_000L;
    // A reopen without a first frame by then (backoff, the 45 s handle wait, one deferred retry) failed.
    private static final long DILINK5_IDLE_RESUME_MAX_MS = 120_000L;
    private final AtomicInteger diLink5ReverseEpoch = new AtomicInteger(0);
    private final java.util.concurrent.locks.ReentrantLock
            diLink5OwnershipTransition =
                    new java.util.concurrent.locks.ReentrantLock();

    // Yield-state re-acquire poller for the legacy contention path.
    private volatile Thread yieldPollerThread;
    private static final long YIELD_POLL_INTERVAL_MS = 5000;

    // audit avc-yield (round 2): when onReacquireCamera's GL-handler runnable
    // throws (AVMCamera.open transient false, NoSuchMethodError on a reflection
    // target, attachSurfaceTextureToCamera failing because the BYD HAL is
    // still finalising the prior native release, etc.) the previous recovery
    // depended solely on startYieldPoller observing a *future* native-app
    // transition — but coordinator.yielded was already cleared on the first
    // transition, so checkNativeAppActive's edge-only handleNativeAppClosed
    // never re-fires, the poller exits, and pano recording stays dead until
    // ACC cycle. Schedule explicit backoff retries of startCamera at 2s/5s/
    // 10s (then give up and self-restart the daemon process the way the GL
    // watchdog does at line 2400/2767).
    private static final long[] REACQUIRE_RETRY_DELAYS_MS = new long[] { 2000L, 5000L, 10000L };
    // audit avc-yield (round 5, finding cross-thread-race): reacquireRetryCount
    // is read+incremented from the GL-handler retry catch block AND reset from
    // onReacquireCamera (HAL listener thread). A naive int can lose an
    // increment if the listener fires reset between the read and the +1 write.
    // AtomicInteger.compareAndSet(currentAttempt, currentAttempt+1) makes the
    // increment fail loud (we treat it as concurrent-reset and skip the retry
    // bump rather than overwriting a fresh 0).
    private final AtomicInteger reacquireRetryCount = new AtomicInteger(0);
    // audit avc-yield (round 7, finding pending-retry-not-cancelled): when
    // a yield-cycle attempt fails we postDelayed a retry runnable on glHandler.
    // If the yield poller's edge-fire path (onReacquireCamera) succeeds before
    // the postDelayed fires, the retry will still run and unconditionally
    // tear down the just-resumed camera (attemptReacquireOnGlThread always
    // closes cameraObj on entry). Track the pending runnable so we can
    // removeCallbacks on success or on a fresh poller-driven re-entry.
    // Also bump pendingReacquireEpoch on success/fresh-cycle so that even if
    // the cancellation race loses, the epoch-gate at the top of
    // attemptReacquireOnGlThread short-circuits the stale runnable.
    private volatile Runnable pendingReacquireRetry = null;
    private final AtomicInteger pendingReacquireEpoch = new AtomicInteger(0);
    // Recreate the camera consumer before the first open after a contention
    // yield. Legacy ImageReader needs this once per yield cycle because the BYD
    // HAL freezes a Surface released by the prior camera instance. DI4 also
    // needs a new SurfaceTexture generation after every stale camera handle is
    // closed during retry: otherwise a callback queued by the retired handle
    // can be accepted after the replacement handle starts using the same
    // SurfaceTexture. Set by yieldCameraInternal (and by DI4 stale-handle
    // cleanup), cleared after a successful recreation.
    private volatile boolean consumerNeedsRecreation = false;


    // Camera health monitor — detects stalled frames and triggers recovery
    private static final long FRAME_STALL_THRESHOLD_MS = 4000;  // 4 seconds without frames (HAL issue)
    // Post-(re)open grace window. The BYD panoramic AVM HAL is documented (see
    // the GL watchdog comment) to take ~5-8s to deliver the first frame after a
    // camera open. Measured from lastCameraStartTime, the stall watchdog must not
    // declare a frame stall inside this window — otherwise the 4s threshold trips
    // before frame 1 can arrive and the camera is torn down and reopened in a loop
    // that never escapes warmup (root cause of the sentry->drive recording blackout
    // when ACC turns on while surveillance is still armed). 9s covers the worst-
    // case 8s first-frame latency with margin while staying under the 10s GL-hang
    // warmup timeout that bounds genuine deadlocks.
    private static final long FRAME_STALL_WARMUP_GRACE_MS = 9000;
    private static final long DILINK5_REACQUIRE_MAX_MS = 45_000L;
    private static final long DILINK5_DEFERRED_REACQUIRE_DELAY_MS = 30_000L;
    // A preview sample waits this long for an idle-released source's first frame (callers allow 12 s).
    private static final long DILINK5_IDLE_SAMPLE_WAIT_MS = 10_000L;
    // Closing the DMA producer and retiring its EGLImages is slower than an
    // active render tick but must still be bounded. This is deliberately
    // shorter than the full reacquire budget: no HAL warmup or first-frame
    // wait belongs to the source-release half of the transition.
    // The backend allows up to 22 s for one complete token-scoped retirement
    // retry. Keep the GL watchdog beyond that bound plus scheduling/GL cleanup
    // margin; otherwise it arms the non-cancellable 5 s process halt while a
    // legitimate second native/process retirement attempt is still running.
    private static final long DILINK5_OWNERSHIP_TRANSITION_TIMEOUT_MS = 30_000L;
    // When native app is active, use a longer threshold to avoid false yields
    // from transient CPU/IO load. The HAL needs time to settle into sharing mode.
    private static final long FRAME_STALL_CONTENTION_THRESHOLD_MS = 3000;
    // Require consecutive stalls before yielding — a single stall could be transient
    private static final int CONTENTION_STALL_COUNT_TO_YIELD = 2;
    private volatile int consecutiveContentionStalls = 0;

    static boolean shouldSuppressDiLink5FrameStall(
            boolean diLink5Path,
            boolean reacquireInProgress,
            boolean hasCameraHandle,
            long lastCameraStartMs,
            long reacquireStartedAtMs,
            long nowMs,
            long warmupGraceMs,
            long maxReacquireMs) {
        if (!diLink5Path || !reacquireInProgress) return false;
        if (isDiLink5ReacquireDeadlineExpired(
                reacquireStartedAtMs, nowMs, maxReacquireMs)) {
            return false;
        }
        if (!hasCameraHandle) return true;
        if (lastCameraStartMs <= 0) return true;
        long ageMs = Math.max(0L, nowMs - lastCameraStartMs);
        return ageMs < Math.max(0L, warmupGraceMs);
    }

    static boolean isDiLink5ReacquireDeadlineExpired(
            long reacquireStartedAtMs,
            long nowMs,
            long maxReacquireMs) {
        if (reacquireStartedAtMs <= 0 || maxReacquireMs <= 0) return true;
        return Math.max(0L, nowMs - reacquireStartedAtMs)
                >= maxReacquireMs;
    }

    static boolean shouldRunPostReacquireLifecycle(
            boolean diLink5Path, boolean reverseSourcePaused) {
        return !diLink5Path || !reverseSourcePaused;
    }

    /** True only while OEM reverse owns, or is taking, the DI5 camera inputs. */
    private boolean isDiLink5SystemAvmHandoffActive() {
        return USE_DILINK5_QCARCAM_PATH
                && (diLink5SystemAvmFrameGate
                        || diLink5ReverseRequested
                        || diLink5ReverseSourceTransitionInProgress);
    }

    /**
     * Frame consumers use a source-pause gate, not the legacy yield flag, on
     * DiLink 5. Once reverse ends, cameraObj==null keeps consumers out until
     * reopen; after publication they must be allowed to consume the first frame.
     */
    private boolean isCameraFrameConsumptionPaused() {
        return USE_DILINK5_QCARCAM_PATH
                ? isDiLink5SystemAvmHandoffActive()
                : cameraYielded
                    || legacyWindshieldCameraLifecycleInFlight.get()
                    || legacyWindshieldCameraTerminalRestart.get();
    }

    /** A generic coordinator yield must never gate DI5 source acquisition. */
    private boolean isCameraReacquireBlockedByOwnershipHandoff() {
        if (USE_DILINK5_QCARCAM_PATH) {
            return diLink5ReverseRequested || isFreshDiLink5Reverse();
        }
        return cameraYielded
                || (cameraCoordinator != null
                        && cameraCoordinator.isCameraYielded());
    }

    private void beginDiLink5Reacquire(String reason) {
        if (!USE_DILINK5_QCARCAM_PATH) return;
        boolean firstTransition = !diLink5ReacquireInProgress;
        diLink5ReacquireInProgress = true;
        if (firstTransition) {
            diLink5ReacquireStartedAtMs = System.currentTimeMillis();
        }
        // Retire both clocks from the prior FastCam ownership session. The
        // watchdog falls back to lastCameraStartTime after the new handle opens.
        lastRealFrameTimeSt = 0L;
        lastFrameTime = 0L;
        lastCameraStartTime = 0L;
        warmupGraceLoggedForStartMs = -1L;
        if (firstTransition) {
            logger.info("DiLink 5 FastCam reacquire started: " + reason);
        }
    }

    private void finishDiLink5Reacquire(String reason) {
        if (!USE_DILINK5_QCARCAM_PATH) return;
        diLink5SourceRecoveryActive.set(false);
        if (!diLink5ReacquireInProgress) return;
        diLink5ReacquireInProgress = false;
        diLink5ReacquireStartedAtMs = 0L;
        logger.info("DiLink 5 FastCam reacquire finished: " + reason);
    }

    /**
     * Leave the recorder and daemon alive when a DI5 source reopen cannot
     * complete immediately. A fresh bounded retry is queued after the HAL and
     * system AVM have had time to settle. This is intentionally process-local:
     * an ordinary reopen failure is not proof that camera ownership is unsafe
     * for the rest of the vehicle boot.
     */
    private boolean scheduleDiLink5DeferredReacquire(String reason) {
        if (!USE_DILINK5_QCARCAM_PATH) return false;
        finishDiLink5Reacquire("waiting for deferred source retry");
        if (!running || diLink5SafetyDisabled
                || diLink5ReverseRequested || isFreshDiLink5Reverse()) {
            logger.warn("DiLink 5 deferred source retry not scheduled: "
                    + reason + " (running=" + running
                    + ", reverse=" + diLink5ReverseRequested
                    + ", safeOff=" + diLink5SafetyDisabled + ")");
            return false;
        }

        cancelPendingReacquireRetry(
                "DiLink 5: replaced pending source retry");
        Handler handler = glHandler;
        if (handler == null) {
            logger.error("DiLink 5 source unavailable; GL handler is absent: "
                    + reason);
            return false;
        }

        final int scheduledEpoch = pendingReacquireEpoch.get();
        Runnable retry = new Runnable() {
            @Override
            public void run() {
                if (pendingReacquireRetry == this) {
                    pendingReacquireRetry = null;
                }
                if (pendingReacquireEpoch.get() != scheduledEpoch
                        || !running
                        || diLink5SafetyDisabled
                        || diLink5IdleSourceReleased
                        || diLink5ReverseRequested
                        || isFreshDiLink5Reverse()) {
                    logger.info("DiLink 5 deferred source retry was superseded");
                    return;
                }
                if (cameraObj != null) {
                    logger.info("DiLink 5 deferred source retry skipped; "
                            + "a camera source is already open");
                    return;
                }
                beginDiLink5Reacquire("deferred source retry");
                attemptReacquireOnGlThread();
            }
        };
        pendingReacquireRetry = retry;
        if (!handler.postDelayed(
                retry, DILINK5_DEFERRED_REACQUIRE_DELAY_MS)) {
            pendingReacquireRetry = null;
            logger.error("DiLink 5 source unavailable; deferred retry was "
                    + "rejected by the GL handler: " + reason);
            return false;
        }
        logger.warn("DiLink 5 source unavailable without daemon restart; "
                + "retrying in " + DILINK5_DEFERRED_REACQUIRE_DELAY_MS
                + "ms: " + reason);
        return true;
    }

    /**
     * Recover only the DI5 FastCam producer. Recorder, muxer, encoder drainers
     * and live-view objects remain attached throughout this operation.
     */
    private void requestDiLink5SourceOnlyRecovery(String reason) {
        if (!USE_DILINK5_QCARCAM_PATH || !running
                || diLink5SafetyDisabled) {
            return;
        }
        if (diLink5IdleSourceReleased) {
            logger.info("DiLink 5 source-only recovery skipped; the source "
                    + "is released while idle: " + reason);
            return;
        }
        if (!diLink5SourceRecoveryActive.compareAndSet(false, true)) {
            logger.info("DiLink 5 source-only recovery already active; "
                    + "coalescing request: " + reason);
            return;
        }
        final int recoveryEpoch =
                diLink5SourceRecoveryEpoch.incrementAndGet();
        Handler handler = glHandler;
        if (handler == null) {
            diLink5SourceRecoveryActive.set(false);
            logger.error("DiLink 5 source-only recovery unavailable; "
                    + "GL handler is absent: " + reason);
            return;
        }
        boolean posted = handler.post(() -> {
            diLink5OwnershipTransition.lock();
            try {
                if (recoveryEpoch != diLink5SourceRecoveryEpoch.get()
                        || !running || diLink5SafetyDisabled
                        || diLink5IdleSourceReleased
                        || diLink5ReverseRequested
                        || isFreshDiLink5Reverse()) {
                    return;
                }
                diLink5SourceOnlyReacquire = true;
                diLink5ReverseSourcePaused = false;
                cancelPendingReacquireRetry(
                        "DiLink 5 source recovery replaced a pending retry");
                beginDiLink5Reacquire(reason);

                Object source = cameraObj;
                if (source != null) {
                    if (!closeCameraForPath(source, false)) {
                        // closeCameraForPath already owns the hard escape for
                        // a proven native/EGL ownership-retirement failure.
                        return;
                    }
                    cameraObj = null;
                    if (cameraCoordinator != null) {
                        cameraCoordinator.resetEventCallbackState();
                        cameraCoordinator.notifyPosCloseCamera();
                    }
                }
                attemptReacquireOnGlThread();
            } finally {
                if (recoveryEpoch == diLink5SourceRecoveryEpoch.get()
                        && !diLink5ReacquireInProgress) {
                    diLink5SourceRecoveryActive.set(false);
                }
                lastGlThreadHeartbeat = System.currentTimeMillis();
                diLink5OwnershipTransition.unlock();
            }
        });
        if (!posted) {
            if (recoveryEpoch == diLink5SourceRecoveryEpoch.get()) {
                diLink5SourceRecoveryActive.set(false);
            }
            logger.error("DiLink 5 source-only recovery was rejected by "
                    + "the GL handler; daemon left running: " + reason);
        }
    }

    /**
     * DiLink 5: close the FastCam source while nothing needs its frames. Skipped when a consumer
     * asked for frames within {@code minQuietMs} or a reacquire/recovery owns the source.
     */
    public void releaseDiLink5IdleSource(String reason, long minQuietMs) {
        if (!USE_DILINK5_QCARCAM_PATH) return;
        // Before the quiet-window read, so a demand landing between the two cancels this release.
        final int demandEpoch = diLink5IdleDemandEpoch.get();
        if (!running || diLink5SafetyDisabled
                || diLink5IdleSourceReleased || diLink5IdleResumePending
                || android.os.SystemClock.elapsedRealtime()
                        - diLink5IdleLastDemandElapsedMs < minQuietMs
                || isDiLink5IdleReleaseBlocked()) {
            return;
        }
        Handler handler = glHandler;
        if (handler == null) return;
        handler.post(() -> {
            diLink5OwnershipTransition.lock();
            try {
                Object source = cameraObj;
                if (demandEpoch != diLink5IdleDemandEpoch.get()
                        || !running || diLink5SafetyDisabled
                        || diLink5IdleSourceReleased
                        || diLink5IdleResumePending
                        || diLink5ReacquireInProgress
                        || diLink5SourceRecoveryActive.get()
                        || !(source instanceof
                                com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend)) {
                    return;
                }
                // Published before the epoch re-check and the close: a racing demand either sees
                // it and queues a resume or is seen here, and the producer exit is not a fault.
                diLink5IdleSourceReleased = true;
                if (demandEpoch != diLink5IdleDemandEpoch.get()) {
                    diLink5IdleSourceReleased = false;
                    return;
                }
                cancelPendingReacquireRetry(
                        "DiLink 5 idle release replaced a pending source retry");
                if (!closeCameraForPath(source, false)) {
                    // closeCameraForPath already requested the urgent restart.
                    blockDiLink5IdleRelease("the idle close did not complete");
                    return;
                }
                cameraObj = null;
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                    cameraCoordinator.notifyPosCloseCamera();
                }
                lastRealFrameTimeSt = 0L;
                lastFrameTime = 0L;
                lastCameraStartTime = 0L;
                logger.info("DiLink 5 FastCam source released while idle: " + reason);
            } finally {
                lastGlThreadHeartbeat = System.currentTimeMillis();
                diLink5OwnershipTransition.unlock();
            }
        });
    }

    /** DiLink 5: a consumer needs frames, so a source released while idle reopens. */
    public void resumeDiLink5IdleSource(String reason) {
        if (!USE_DILINK5_QCARCAM_PATH) return;
        diLink5IdleLastDemandElapsedMs = android.os.SystemClock.elapsedRealtime();
        diLink5IdleDemandEpoch.incrementAndGet();
        if (!diLink5IdleSourceReleased || !running) return;
        Handler handler = glHandler;
        if (handler == null) return;
        // Before the CAS: a demand that loses it must still see a reopen in progress.
        diLink5IdleResumePending = true;
        if (!diLink5IdleResumeQueued.compareAndSet(false, true)) return;
        final String why = reason == null ? "consumer" : reason;
        boolean posted = handler.post(() -> {
            diLink5IdleResumeQueued.set(false);
            diLink5OwnershipTransition.lock();
            try {
                if (!diLink5IdleSourceReleased || !running || diLink5SafetyDisabled
                        || cameraObj != null) {
                    // Pending stays only for an idle reacquire that is running or has failed.
                    if (diLink5IdleResumeStartedAtUptimeMs == 0L && !diLink5IdleResumeFailed) {
                        diLink5IdleResumePending = false;
                    }
                    return;
                }
                diLink5IdleSourceReleased = false;
                diLink5SourceOnlyReacquire = true;
                diLink5IdleResumeStartedAtUptimeMs = android.os.SystemClock.uptimeMillis();
                beginDiLink5Reacquire("idle source resume: " + why);
                attemptReacquireOnGlThread();
            } finally {
                lastGlThreadHeartbeat = System.currentTimeMillis();
                diLink5OwnershipTransition.unlock();
            }
        });
        if (!posted) {
            diLink5IdleResumeQueued.set(false);
            if (diLink5IdleResumeStartedAtUptimeMs == 0L && !diLink5IdleResumeFailed) {
                diLink5IdleResumePending = false;
            }
        }
    }

    /** DiLink 5: the source is released or reopening, so no cached image is current. */
    public boolean isDiLink5IdleSourceUnavailable() {
        return USE_DILINK5_QCARCAM_PATH && (diLink5IdleSourceReleased || diLink5IdleResumePending);
    }

    /**
     * A preview sample reopens an idle-released DI5 source and waits briefly for its first frame.
     * False when no fresh frame arrived: the texture would still hold the pre-release image.
     */
    private boolean awaitDiLink5IdleSourceForSample(String sample) {
        if (!USE_DILINK5_QCARCAM_PATH) return true;
        boolean reopening = diLink5IdleSourceReleased || diLink5IdleResumePending;
        resumeDiLink5IdleSource(sample);
        reopening |= diLink5IdleSourceReleased || diLink5IdleResumePending;
        if (!reopening) return true;
        Handler handler = glHandler;
        if (handler != null && handler.getLooper().isCurrentThread()) return false;
        long deadline = android.os.SystemClock.elapsedRealtime() + DILINK5_IDLE_SAMPLE_WAIT_MS;
        while (running && android.os.SystemClock.elapsedRealtime() < deadline) {
            if (cameraObj != null && lastRealFrameTimeSt > 0L && !diLink5IdleResumePending) {
                return true;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * A reopen after an idle release failed (refused for the process, or no frame in time): stop
     * releasing for a while and restart the daemon once, since only a new process clears a refusal.
     */
    private void onDiLink5IdleResumeFailed(String why, long minElapsedMs) {
        synchronized (diLink5IdleResumeFailureLock) {
            long startedAt = diLink5IdleResumeStartedAtUptimeMs;
            if (!diLink5IdleResumePending || startedAt == 0L
                    || android.os.SystemClock.uptimeMillis() - startedAt < minElapsedMs) {
                return;
            }
            // Pending stays set: previews keep refusing the pre-release texture until a real frame.
            diLink5IdleResumeFailed = true;
            diLink5IdleResumeStartedAtUptimeMs = 0L;
        }
        // The marker is written first: it is what keeps the next daemon from repeating this.
        blockDiLink5IdleRelease(why);
        CameraDaemon.requestProcessRestartPreservingTrip(
                "DiLink 5 camera reopen after an idle release failed: " + why);
    }

    private static String diLink5BootId() {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String id = reader.readLine();
            return id == null || id.trim().isEmpty() ? null : id.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Off for 12 h of this vehicle boot once a release's close or reopen failed. */
    private boolean isDiLink5IdleReleaseBlocked() {
        long now = android.os.SystemClock.elapsedRealtime();
        long until = diLink5IdleReleaseBlockedUntilElapsedMs;
        if (until >= 0L) return now < until;
        String boot = diLink5BootId();
        until = boot == null ? Long.MAX_VALUE : 0L;
        if (boot != null) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader(DILINK5_IDLE_RELEASE_BLOCK_PATH))) {
                String[] marker = String.valueOf(reader.readLine()).trim().split(" ");
                if (marker.length == 2 && boot.equals(marker[0])) {
                    until = Long.parseLong(marker[1]) + DILINK5_IDLE_RELEASE_BLOCK_MS;
                }
            } catch (Throwable absentOrUnreadable) {
                until = 0L;
            }
        }
        diLink5IdleReleaseBlockedUntilElapsedMs = until;
        return now < until;
    }

    /** Written synchronously: the restart that follows must not outrun the marker. */
    private void blockDiLink5IdleRelease(String reason) {
        long now = android.os.SystemClock.elapsedRealtime();
        diLink5IdleReleaseBlockedUntilElapsedMs = now + DILINK5_IDLE_RELEASE_BLOCK_MS;
        logger.warn("DiLink 5 idle source release disabled for 12 h: " + reason);
        String boot = diLink5BootId();
        if (boot == null) return;
        try (java.io.FileWriter out = new java.io.FileWriter(DILINK5_IDLE_RELEASE_BLOCK_PATH)) {
            out.write(boot + " " + now);
        } catch (Throwable t) {
            logger.warn("DiLink 5 idle release marker not written: " + t.getMessage());
        }
    }

    // Escalation: count consecutive bare-reopen restarts that delivered ZERO
    // frames. A close/reopen is not enough when the AVM producer route or this
    // process's camera/GL state remains wedged (the sentry->drive blackout:
    // 14 reopens, 0 frames, 2 min lost). After this many back-to-back zero-frame
    // reopens, escalate to a full camera/GL rebuild or trip-safe process
    // replacement instead of looping bare reopens forever. Incremented in
    // restartCameraAfterError when the prior open never produced a frame;
    // reset to 0 the moment a real frame arrives.
    private static final int FRAME_STALL_RESTART_ESCALATE_THRESHOLD = 3;
    private volatile int consecutiveZeroFrameRestarts = 0;
    // Snapshot of frameCounter at the start of the current open. If frameCounter
    // hasn't advanced past this by the next restart, that open delivered nothing.
    private volatile long frameCounterAtOpen = 0;
    // Set true while an escalation is in flight so the watchdog stops posting
    // bare restartCameraAfterError() until the full rebuild completes (and
    // resets it via notePipelineRestarted()).
    private volatile boolean halRecoveryEscalated = false;

    // DiLink 4 parked-producer recovery: the byd_apa producer can die at ACC OFF
    // and never resume. Bounded reopen, spaced by
    // DILINK4_ERROR_RESTART_MIN_INTERVAL_MS. 5 attempts matches oem's cap.
    private static final int DILINK4_STALL_RESTART_MAX_ATTEMPTS = 5;
    private volatile int dilink4StallRestartAttempts = 0;
    private volatile long dilink4LastStallRestartMs = 0L;
    /** Latched when the budget is spent so the give-up logs once, not per tick. */
    private volatile boolean dilink4StallRecoveryExhausted = false;
    // Proof-of-recovery before the budget is refilled: a half-alive HAL can emit a
    // frame or two after a reopen and freeze again, and refilling on frame 1 turns
    // the bounded ladder into an unbounded reopen loop. 30s of unbroken flow (~120
    // frames at this HAL's 4-5 fps) keeps a pause/flow/pause cycle from earning a
    // fresh budget every minute; any stall in between voids the tally.
    private static final int DILINK4_RECOVERY_PROOF_FRAMES = 60;
    private static final long DILINK4_RECOVERY_PROOF_MS = 30_000L;
    private volatile int dilink4RecoveryProofFrames = 0;
    private volatile long dilink4RecoveryProofSinceMs = 0L;

    // DI4 demand-driven producer recovery. A parked byd_apa producer may stop
    // emitting while the AVMCamera handle remains open and isPreview() still
    // reports true. Carrying that handle across ACC ON is intentional, but a
    // newly-active consumer must be able to re-arm it without immediately
    // destroying the mosaic/viewpoint state.
    //
    // Recovery ladder:
    //   1. one-shot byte-callback producer probe/kick;
    //   2. recreate + rebind only the SurfaceTexture on the SAME AVMCamera;
    //   3. bounded full camera close/reopen;
    //   4. existing zero-frame escalation to a full process/pipeline rebuild.
    private static final long DILINK4_DEMAND_STALE_MS = 2_000L;
    private static final long DILINK4_SOFT_RECOVERY_VERIFY_MS = 1_750L;
    // Same-handle recovery on a stopped preview must follow the old-DI4
    // callback-first contract too. Allow the producer its normal first-byte
    // warmup before declaring that stage dead; once attached, the ordinary
    // 1.75 s texture-frame verification starts from the actual attach edge.
    private static final long DILINK4_REBIND_CALLBACK_TIMEOUT_MS = 6_000L;
    // The callback watcher polls every 200 ms. Let its finally block retire
    // before stage 2 installs another callback on the same AVMCamera.
    private static final long DILINK4_CALLBACK_DISARM_SETTLE_MS = 250L;
    private static final long DILINK4_SOFT_RECOVERY_MIN_INTERVAL_MS = 5_000L;
    private final AtomicBoolean dilink4SoftRecoveryInFlight =
        new AtomicBoolean(false);
    private final AtomicInteger dilink4SoftRecoveryEpoch =
        new AtomicInteger(0);
    // A 60-second OEM reopen floor can defer stage 3. Keep exactly one timer,
    // bound to the AVMCamera instance that failed; a frame, teardown or camera
    // replacement invalidates it before it can disturb a newer session.
    private final AtomicBoolean dilink4DeferredReopenPending =
        new AtomicBoolean(false);
    private final AtomicInteger dilink4DeferredReopenEpoch =
        new AtomicInteger(0);
    private volatile boolean dilink4FrameDemanded = false;
    private volatile String dilink4FrameDemandReason = "none";
    private volatile long dilink4LastSoftRecoveryAttemptMs = 0L;
    private volatile int dilink4SoftRecoveryCycles = 0;
    // Unlike lastRealFrameTimeSt, this is NEVER advanced by the watchdog to
    // throttle repeated stall handling. It changes only on a genuine
    // SurfaceTexture onFrameAvailable callback and therefore remains a
    // trustworthy demand-edge freshness signal during a multi-hour stall.
    private volatile long dilink4LastGenuineFrameArrivalMs = 0L;

    // HAL camera-death release (event 8/1002). Reopen backoff: 1/3/5/10 s with ACC
    // on; parked keeps the 60 s floor, then retries every 10 min once the reopen
    // budget is spent. Parked never escalates to a process rebuild.
    private static final long DILINK4_DEATH_VERIFY_MS = 500L;
    private static final long[] DILINK4_DEATH_REOPEN_BACKOFF_MS =
        {1_000L, 3_000L, 5_000L, 10_000L};
    private static final long DILINK4_PARKED_RETRY_MS = 600_000L;
    private final AtomicInteger dilink4DeathEpoch = new AtomicInteger(0);
    private volatile boolean dilink4ReleasedAfterDeath = false;
    private volatile int dilink4DeathReopenStreak = 0;
    private volatile boolean dilink4ParkedRebuildSkipLogged = false;
    /** Open worker that outlived restartCameraAfterError's 2 s DI4 wait. No DI4
     *  restart may start while it lives; past the deadline only process death
     *  can cancel the blocked HAL call. */
    private volatile Thread dilink4LateOpenThread = null;
    private volatile AtomicBoolean dilink4LateOpenSucceeded = null;
    private volatile long dilink4LateOpenSinceMs = 0L;
    // A HAL death reported while that worker was still opening; applied once it ends.
    private volatile long dilink4PendingDeathAtMs = 0L;
    private volatile String dilink4PendingDeathWhy = null;
    private static final long DILINK4_LATE_OPEN_DEADLINE_MS = 20_000L;

    private enum DiLink4SurfaceRebindResult {
        FAILED,
        ATTACHED,
        WAITING_FOR_CALLBACK
    }

    static boolean isDiLink4ProducerStale(
            boolean diLink4Path,
            long lastGenuineFrameArrivalMs,
            long cameraStartMs,
            long nowMs,
            long staleThresholdMs,
            long firstFrameGraceMs) {
        if (!diLink4Path || nowMs <= 0L) return false;
        if (lastGenuineFrameArrivalMs > 0L) {
            return Math.max(0L, nowMs - lastGenuineFrameArrivalMs)
                >= Math.max(0L, staleThresholdMs);
        }
        if (cameraStartMs <= 0L) return false;
        return Math.max(0L, nowMs - cameraStartMs)
            >= Math.max(0L, firstFrameGraceMs);
    }

    static boolean isDiLink4ConsumerStarved(
            boolean explicitFrameDemand,
            boolean blindSpotVisible,
            boolean recorderWriting,
            boolean eventWriterActive,
            boolean streamLanePresent) {
        return explicitFrameDemand
            || blindSpotVisible
            || recorderWriting
            || eventWriterActive
            || streamLanePresent;
    }

    // DEAD-SLOT ESCAPE (issue #170). Every self-healing path above needs proof
    // that the camera produced at least one frame: the frame-15/50 revalidation
    // is driven by frameCounter, and the frame-stall monitor is gated on
    // `lastFrameTime > 0`. A slot that opens but can NEVER stream therefore arms
    // none of them and the pipeline waits forever — Diagnostics stays on
    // "Probing…", frameCount stays 0, nothing is ever recorded.
    //
    // That is exactly what BYD's 2602-generation firmware does on legacy pano_h
    // boards: AVMCamera.open(1) succeeds, but the HAL reports a 0x0 preview so
    // addPreviewSurface fails (err 423) and startPreview fails (err 279) — both
    // inside the HAL, neither raising a Java exception we could catch at open.
    //
    // So: if NO frame has arrived within this window of the camera opening, the
    // slot is presumed dead and we walk PanoCameraFallbackOrder. The window must
    // clear the documented 5-8s BYD first-frame latency and the 9s stall grace
    // with room to spare — a false trigger costs a HAL close/open cycle, so we
    // are deliberately patient. This only ever fires for a camera that has not
    // produced a single frame; once one arrives, the existing machinery owns
    // recovery and this path is permanently disarmed for the session.
    //
    // Legacy (addPreviewSurface) path ONLY. On dilink4 the oem-parity posture
    // deliberately performs no stall-driven close/reopen at all (see
    // dilink4SkipStallRestart below): that HAL routinely pauses frame emission
    // on parked cars, so "open but no frames for 25s" is a NORMAL state there,
    // not a dead slot — walking would churn the HAL and could persist a wrong
    // id. Issue #170's hardware is exclusively the legacy path.
    private static final long FIRST_FRAME_DEAD_SLOT_MS = 25000;
    // Camera IDs opened OR attempted this session, so the walk never revisits
    // a candidate. startCamera records every successful open; the walk records
    // each candidate at switch time — an attempt that fails to open counts as
    // tried too, otherwise a candidate whose open throws would be retried on
    // every watchdog tick, forever.
    // CopyOnWriteArraySet, not a synchronized wrapper: startCamera adds from the
    // camera-open worker thread that restartCameraAfterError spawns, while the
    // GL thread reads it (and concatenates it into log lines). COW gives both
    // contains() and toString() snapshot semantics with no lock and no risk of
    // ConcurrentModificationException. Writes are rare and the set holds at most
    // a couple of entries, so the copy cost is irrelevant.
    private final java.util.Set<Integer> deadSlotTriedCameraIds =
        new java.util.concurrent.CopyOnWriteArraySet<>();
    // True once the walk has switched ids at least once. Two consumers: it
    // relaxes the watchdog's cameraObj gate (a candidate whose open FAILED
    // leaves cameraObj null mid-walk — the walk must still advance past it),
    // and it makes the switch re-enable frame validation so the frame-15/50
    // path can validate-and-persist the recovered id (see advance...()).
    private volatile boolean deadSlotWalkActive = false;
    // Latched once the candidate list is exhausted so we log once, not per tick.
    private volatile boolean deadSlotWalkExhausted = false;
    // Slot the walk left; an exhausted walk returns to it and re-walks on a backoff
    // until a frame arrives (cameras can come back long after a parked reboot).
    private volatile int deadSlotWalkOriginId = -1;
    private volatile boolean deadSlotWalkOriginSkipValidation = false;
    private volatile long deadSlotRetryAtMs = 0L;
    private volatile int deadSlotRetryCycle = 0;
    private static final long[] DEAD_SLOT_RETRY_BACKOFF_MS =
        {30_000L, 60_000L, 120_000L, 300_000L, 600_000L};
    // Re-walk even if a retry's reopen failed and left no slot for the walk to judge.
    private static final long DEAD_SLOT_RETRY_SAFETY_NET_MS = 120_000L;
    // ACC-ON reopen lost the restart race: the next retry runs a fresh walk instead.
    private volatile boolean deadSlotFreshWalkPending = false;
    // Retry restarts skip onPostReacquire; it runs once on the first frame instead.
    private volatile boolean deadSlotResumePending = false;
    // Watchdog-thread only (elapsedRealtime): the native-app flag is a cache, refreshed on the
    // GL thread while never-streamed.
    private long lastNativeAppRecheckMs = Long.MIN_VALUE / 2;
    private static final long NATIVE_APP_RECHECK_MS = 10_000L;
    // The posted resume holds the restart gate; the ACC-ON reopen waits it out briefly.
    private volatile boolean deadSlotResumeInFlight = false;
    private static final long DEAD_SLOT_RESUME_WAIT_MS = 6_000L;
    // BmmCameraInfo panoramic tag mapping, resolved lazily on first use so the
    // reflection + logging cost is paid only on boards that actually wedge.
    // MIN_VALUE = not yet resolved; -1 = resolved, API absent (DiLink 3.0).
    private volatile int halPanoCameraIdHint = Integer.MIN_VALUE;

    // Flag to indicate camera restart is in progress — watchdog uses extended timeout.
    // P1 #11: AtomicBoolean so concurrent restartCameraAfterError + reopenCamera
    // calls can't both enter the restart path. Loser observes
    // compareAndSet(false,true)==false and returns; only the winner runs the
    // close/open sequence and is responsible for clearing the flag.
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);
    // Exact ownership fences for legacy startup/open work. AVMCamera.open() is
    // an uninterruptible vendor Binder call and publishes cameraObj before all
    // attach/start steps finish. stop() must not close that partial object or
    // tear down its GL state from another thread.
    private final AtomicBoolean legacyCameraOpenInFlight =
            new AtomicBoolean(false);
    private final AtomicBoolean legacyCameraInitializationInFlight =
            new AtomicBoolean(false);
    
    // SOTA: Pre-yield listener — pipeline registers this to finalize recordings before yield
    public interface CameraYieldListener {
        /** Called BEFORE camera is yielded. Finalize any active recording to prevent corruption. */
        void onPreYield();
        /** Called AFTER camera is re-acquired. Resume recording if needed. */
        void onPostReacquire();
        /**
         * Called when bare close/reopen restarts have repeatedly failed to
         * revive frame delivery (FRAME_STALL_RESTART_ESCALATE_THRESHOLD
         * consecutive reopens with zero frames). The listener should rebuild
         * the complete camera/GL pipeline; when there is no active recording
         * mode to reactivate, it should request the trip-safe process rebuild.
         * Default no-op so existing listeners stay source-compatible.
         */
        default void onHalRecoveryNeeded() {}
    }
    private CameraYieldListener yieldListener;
    
    // CPU usage monitoring
    private long lastCpuCheckTime = 0;
    private static final long CPU_CHECK_INTERVAL_MS = 10000;  // Every 10 seconds
    
    // Stats logging (time-based, not frame-based)
    private long lastStatsTime = 0;
    private int lastStatsFrameCount = 0;
    private static final long STATS_INTERVAL_MS = 120000;  // Every 2 minutes

    // Per-stage timing diagnostic. Tracks the WORST frame in a 30 s window
    // and logs a single line per window so the contribution of each stage
    // (acquire / mosaicDraw / aiReadback / aiSubmit / swap) is visible
    // without log spam. Used to verify that readback-skip + drainer keep
    // each stage under budget.
    private static final long STAGE_TIMING_LOG_INTERVAL_MS = 30000;
    private long stageTimingWindowStartMs = 0;
    private long stageWorstTotalNs = 0;
    private long stageWorstAcquireNs = 0;
    private long stageWorstMosaicNs = 0;
    private int stageWindowFrames = 0;

    // AI readback throttle — frame-counter modulo, NOT wall-clock.
    // Wall-clock throttling is fragile when readback duration approaches the
    // interval: the GL thread spends ~117ms per frame (mosaic+swap+readback),
    // which guarantees `now - lastReadback >= 95ms` on every loop, so 100% of
    // frames trigger readback and the pipeline collapses to ~8 fps.
    // Frame-modulo couples AI rate directly to HAL emission rate. With HAL
    // emitting at ~26 fps (ImageReader path), every 3rd frame is ~8.6 AI fps,
    // matching V2 motion's 10 fps internal cadence. If HAL rate changes, AI
    // rate scales proportionally and the GL thread budget stays balanced.
    private static final int AI_READBACK_FRAME_MODULO = 3;

    private int targetFps = 15;  // Desired frame rate for camera

    // Cached AI-lane readback pacing (parked-idle throttle). The AiLaneGl is
    // created/torn down lazily on the GL thread and is null while disarmed, so a
    // control-thread setter caches the value here and re-applies it at bring-up
    // (ensureAiLaneStarted) — mirroring how sentry.setCameraTargetFps is
    // re-asserted. Default 0 = disabled ⇒ AiLaneGl uses its compiled modulo=3,
    // byte-identical to today.
    private volatile long aiReadbackMinIntervalMs = 0L;

    // Recorder draw stride. The render loop draws into the RECORDING encoder's
    // input surface (PASS 1A) only on every Nth camera frame; stream (PASS 1B)
    // and blind-spot (PASS 1C) are unaffected (separate encoders, drawn every
    // frame). MediaCodec encodes exactly the frames rendered into its Surface,
    // so a stride of N yields an effective ~cameraFps/N recording rate without
    // touching KEY_FRAME_RATE (which Android can't change at runtime). Used by
    // Proximity Guard to keep a low-rate, low-bitrate pre-record ring while
    // MONITORING and snap to full rate the instant a trigger fires.
    //
    // 1 = draw every frame (default; ZERO behaviour change for every other
    // mode). Always >= 1. Volatile: written by the proximity controller's
    // state thread (via pipeline), read by the GL render thread — same
    // single-writer/single-reader visibility pattern as bsLayerVisible.
    private volatile int recorderFrameStride = 1;
    // Master on/off for PASS 1A (the H.265 recorder mosaic). true = normal
    // (default; ZERO behaviour change for every recording mode). false = skip
    // the recorder drawFrame + drainEncoder ENTIRELY this frame — used when the
    // camera is kept warm ONLY for blind-spot (PASS 1C, no encoder): there is no
    // recording mode and no pre-record ring to feed, so running the encoder is
    // pure wasted Venus/GPU. Distinct from recorderFrameStride (which sub-samples
    // the lane); this gates it off completely. Stream (1B) + BS (1C) unaffected.
    // Volatile: written by RecordingModeManager's lifecycle thread (via pipeline),
    // read by the GL render thread — same single-writer/single-reader pattern as
    // bsLayerVisible / recorderFrameStride.
    private volatile boolean recorderLaneEnabled = true;
    // Counter that advances every consumed camera frame and selects which
    // frames clear the stride gate (drawn when counter % stride == 0). The GL
    // render thread increments it; setRecorderFrameStride resets it to 0 from
    // the control thread so a stride change starts on a drawn frame. Volatile
    // so that cross-thread reset is atomic (no 32-bit long tearing) and visible
    // — the GL increment racing a reset can at worst drop one increment, which
    // is benign for a phase counter read as `% stride`.
    private volatile long recorderStrideCounter = 0;

    // Stream-lane stride: same pattern as recorderFrameStride but derived from
    // streamEncoder.getFps() vs camera targetFps. When the camera runs at 15 fps
    // and the stream preset requests 10 fps, stride = floor(15/10) = 1 (draw every
    // frame); at 30 vs 10, stride = 3 (draw every 3rd). Stride 1 = every frame.
    // Volatile: written on the GL thread during enable/quality-change (via pipeline),
    // read on the same GL render thread.
    private volatile int streamFrameStride = 1;
    private volatile long streamStrideCounter = 0;
    // Stream client-presence probe (set by the pipeline to
    // WebSocketStreamServer::hasActiveClients). When it returns false, PASS 1B
    // skips the GPU raster + encode entirely: with no viewer the encoded bytes
    // are dropped anyway (WebSocketStreamServer.onH264Packet early-exits), so
    // rastering + encoding them is pure wasted GPU/Venus for the 30s idle window.
    // Default supplier returns true so any wiring gap fails OPEN (stream keeps
    // working) rather than silently going black. Same pattern as the recorder's
    // halContentionProbe. When a client (re)connects, the pipeline requests a
    // fresh IDR (encoder.requestSyncFrame) so the resumed stream is immediately
    // decodable — see the keyframeRequestHook path.
    private volatile java.util.function.BooleanSupplier streamClientProbe = () -> true;
    // Rising-edge tracker for the stream client-presence gate. GL-thread-confined
    // read/write in the render loop; reset from the control thread on teardown
    // (benign racy hint — worst case one extra IDR on the next enable).
    private volatile boolean streamWasActive = false;

    private final float[] quadrantStripOffsetX;
    private final float[] quadrantCornerOffsetsXY;

    // One-shot mismatch warning for HAL-emitted dims vs. configured strip.
    // The HAL silently delivers whatever it wants; we want to know if Tang
    // returns 720 against a Seal-configured 960 ImageReader (mosaic geometry
    // would be wrong). volatile because the GL thread sets it on first frame.
    private volatile boolean emittedDimsLogged = false;

    /** Dimensions the HAL was OBSERVED to emit, or -1 before the first frame.
     *  Legacy ImageReader path: from {@code Image.getWidth/getHeight}. dilink4:
     *  populated instead by the byte-callback kick ({@code halBytePathWidth}),
     *  because on the SurfaceTexture path neither the transform matrix nor
     *  BmmCameraInfo can reveal the producer size. Only these observed values
     *  may ever be persisted as {@code probedWidth}/{@code probedHeight}. */
    private volatile int halEmittedWidth = -1;
    private volatile int halEmittedHeight = -1;

    /** Best available OBSERVED producer width, or -1. Prefers the legacy
     *  ImageReader observation, then the dilink4 byte-callback observation. */
    public int getObservedProducerWidth() {
        int w = halEmittedWidth;
        if (w > 0) return w;
        return halBytePathWidth;
    }

    /** Best available OBSERVED producer height, or -1. See
     *  {@link #getObservedProducerWidth}. */
    public int getObservedProducerHeight() {
        int h = halEmittedHeight;
        if (h > 0) return h;
        return halBytePathHeight;
    }

    // Sticky flag for the SurfaceTexture path: true once SurfaceTexture has
    // signalled at least one onFrameAvailable. Drives the renderLoop bind
    // instead of imagePending (which is for the ImageReader path).
    private volatile boolean stFramePending = false;
    // Native fast-camera callback signal. DiLink 5 composites or uploads the
    // matching frame into cameraTextureId on the GL thread.
    private volatile boolean diLink5FramePending = false;
    private volatile boolean diLink5CloseInProgress = false;
    private volatile com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend
            diLink5GlOwnerBackend;
    private volatile boolean diLink5SafetyDisabled = false;

    // GENUINE new-buffer counter for the SurfaceTexture (dilink4) path.
    // Incremented ONLY from onFrameAvailable — i.e. only when the HAL actually
    // queued a buffer. This exists because updateTexImage() is a documented
    // no-op that returns normally when nothing is queued, so
    // consumeSurfaceTextureFrame() cannot distinguish "new frame" from "same
    // frame again". Before this counter existed, frameCounter/lastFrameTime
    // advanced on every render-loop tick, which:
    //   - inflated the Stats: line (loop ticks reported as camera frames),
    //   - blinded the frame-stall watchdog (lastFrameTime always fresh),
    //   - pinned priorOpenDeliveredNoFrame false so zero-frame escalation
    //     could never fire.
    // Legacy ImageReader path never touches this (it has a real freshness
    // signal: acquireLatestImage() == null), so non-dilink4 cars are unaffected.
    private final java.util.concurrent.atomic.AtomicLong stFrameArrivalSeq =
        new java.util.concurrent.atomic.AtomicLong(0);

    // Value of stFrameArrivalSeq consumed by the last updateTexImage() that
    // actually picked up new content. GL thread only.
    private long stLastConsumedArrivalSeq = 0;

    // Wall-clock of the last GENUINE new buffer on the SurfaceTexture path
    // (0 = none yet). This is what the frame-stall watchdog reads on dilink4;
    // lastFrameTime keeps its legacy meaning (any processed loop iteration).
    private volatile long lastRealFrameTimeSt = 0;

    // True once a stall has been announced for the CURRENT stall episode, so the
    // (deliberately action-free) dilink4 stall path logs once per episode instead
    // of on every watchdog tick — a parked byd_apa HAL pauses frame emission for
    // minutes at a time and would otherwise emit ~1400 log lines/hour saying the
    // same thing. Cleared as soon as a real frame arrives.
    private volatile boolean stallEpisodeLogged = false;

    /** Wall-clock of the last REAL frame before the current stall episode began,
     *  and the next re-log deadline. Needed because the stall branch resets the
     *  detector's own clock every time it fires (or it would re-fire on every
     *  tick), which makes {@code timeSinceFrame} useless as a duration. 0 = no
     *  episode in progress. */
    private volatile long stallEpisodeStartMs = 0;
    private volatile long stallEpisodeNextLogMs = 0;

    /** First re-log of a dilink4 stall episode. Escalates from here (see the
     *  watchdog): 1min → 5min → 30min, so a long freeze stays visible in the log
     *  without the ~1400 lines/hour a fixed cadence produced. */
    private static final long STALL_RELOG_STEP_MS = 60_000L;

    /** {@code lastCameraStartTime} value for which the warmup-grace suppression
     *  line has already been logged, so it prints once per camera open instead of
     *  once per watchdog tick. */
    private volatile long warmupGraceLoggedForStartMs = -1;

    // One-shot first-frame transform-matrix dump on the SurfaceTexture path.
    // Cleared by attachSurfaceTextureToCamera so we re-emit on every
    // (re)attach, not just the cold-start session. The transform matrix
    // tells us the HAL's actual framing — diagonal sx/sy give us "what
    // fraction of the surface holds real pixels", which exposes 5120x960
    // strip-vs-2x2-mosaic and similar. Cheap (single 16-float read).
    private boolean firstFrameDimsLogged = false;

    // Per-frame SurfaceTexture transform matrix, captured from
    // SurfaceTexture.getTransformMatrix() inside consumeSurfaceTextureFrame
    // and forwarded to GpuMosaicRecorder + GpuStreamScaler via
    // setTextureMatrix. oem-parity: same matrix oem's pipeline applies as
    // uTexMatrix in the vertex shader. Only used when the oem SurfaceTexture
    // path is active; legacy ImageReader path leaves it at identity.
    private final float[] currentTexMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };

    /**
     * Creates a GPU-based panoramic camera.
     *
     * @param width Camera width (typically 5120)
     * @param height Camera height (typically 960 on Seal, 720 on Tang)
     */
    public PanoramicCameraGpu(int width, int height) {
        this(width, height, null);
    }

    /**
     * @param quadrantStripOffsetX Per-quadrant strip-X offsets. Null = legacy
     *     Seal default. Forwarded to the foveated cropper so its
     *     {@code centerX} math picks the slice the user mapped to each role.
     */
    public PanoramicCameraGpu(int width, int height, float[] quadrantStripOffsetX) {
        this(width, height, quadrantStripOffsetX, null);
    }

    /**
     * @param quadrantStripOffsetX Per-role X offsets for legacy 4-strip HAL.
     *     {Front, Right, Rear, Left}. Null → legacy default.
     * @param quadrantCornerOffsetsXY Per-role (cornerX, cornerY) for the
     *     0.5×0.5 corner in a 2x2-native HAL frame. {fX,fY, rX,rY, bX,bY,
     *     lX,lY}. Null → mirrors recorder's default (Front=TL, Right=TR,
     *     Rear=BL, Left=BR). Used when DiLink 4 mode is active.
     */
    public PanoramicCameraGpu(int width, int height,
                              float[] quadrantStripOffsetX,
                              float[] quadrantCornerOffsetsXY) {
        this.width = width;
        this.height = height;
        this.quadrantStripOffsetX = (quadrantStripOffsetX != null && quadrantStripOffsetX.length == 4)
            ? quadrantStripOffsetX.clone()
            : null;
        this.quadrantCornerOffsetsXY =
            (quadrantCornerOffsetsXY != null && quadrantCornerOffsetsXY.length == 8)
                ? quadrantCornerOffsetsXY.clone()
                : null;
    }
    
    /**
     * Sets the consumers for the camera frames.
     * 
     * @param recorder GPU mosaic recorder for zero-copy recording
     * @param downscaler GPU downscaler for AI lane
     * @param sentry Surveillance engine for motion detection
     */
    public void setConsumers(GpuMosaicRecorder recorder, GpuDownscaler downscaler,
                            SurveillanceEngineGpu sentry) {
        this.recorder = recorder;
        this.downscaler = downscaler;
        this.sentry = sentry;

        // Build the AI lane worker once consumers are wired. Recycler points
        // back to the downscaler's buffer pool so dropped frames are returned
        // immediately (no leak under sustained submit-while-busy).
        if (this.aiLaneWorker == null) {
            this.aiLaneWorker = new com.overdrive.app.camera.AiLaneWorker(frame -> {
                GpuDownscaler ds = this.downscaler;
                if (ds != null && frame != null) {
                    try {
                        ds.recycleBuffer(frame);
                    } catch (Throwable ignored) {}
                }
            });
        }
        this.aiLaneWorker.setSentry(sentry);
        // Sentry's foveated crops now run on the AiLaneGl thread, so we
        // don't hand it the encoder GL handler any more — that path posted
        // crops back to the encoder thread and competed with the render
        // loop for handler slots. With AiLaneGl, the crop runs inline on
        // its own GL context.
        if (sentry != null) {
            sentry.setCameraTargetFps(targetFps);
            // Frozen-feed detector (parked): the HAL can keep queuing buffers
            // whose content never changes (camera/ISP rail down while the AVM
            // SoC re-emits its last frame — DI5 field log 2026-09-21). The
            // engine hashes its downscaled CPU frames; this listener owns the
            // response: always log the episode, and reopen the camera only
            // when camera.frozenFeedReopen is enabled (default OFF — evidence
            // first; a reopen cannot fix a rail-down freeze, only the MCU
            // power hold can).
            sentry.setFrozenFeedListener(new FrozenFeedDetector.Listener() {
                @Override
                public void onFrozenFeed(int identicalSamples, long frozenForMs) {
                    logger.warn("FROZEN FEED: " + identicalSamples
                            + " consecutive bit-identical sampled frames over "
                            + frozenForMs + " ms — HAL is repeating one buffer"
                            + " (camera/ISP likely powered down; check MCU hold)");
                    maybeReopenForFrozenFeed();
                }

                @Override
                public void onFeedRecovered(long frozenForMs) {
                    logger.info("FROZEN FEED recovered after " + frozenForMs
                            + " ms — frame content is changing again");
                }
            });
        }
    }

    // One reopen per frozen episode at most, and never more often than this.
    private static final long FROZEN_FEED_RESTART_MIN_INTERVAL_MS = 60_000L;
    private volatile long lastFrozenFeedRestartMs = 0L;

    /** Config-gated (camera.frozenFeedReopen, default false) throttled reopen. */
    private void maybeReopenForFrozenFeed() {
        boolean reopenEnabled = false;
        try {
            org.json.JSONObject cam = com.overdrive.app.config.UnifiedConfigManager
                    .loadConfig().optJSONObject("camera");
            reopenEnabled = cam != null && cam.optBoolean("frozenFeedReopen", false);
        } catch (Throwable ignored) {
        }
        if (!reopenEnabled) {
            logger.info("Frozen feed: reopen disabled (camera.frozenFeedReopen=false) — log only");
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastFrozenFeedRestartMs < FROZEN_FEED_RESTART_MIN_INTERVAL_MS) {
            logger.info("Frozen feed: reopen throttled ("
                    + (now - lastFrozenFeedRestartMs) + " ms since last)");
            return;
        }
        lastFrozenFeedRestartMs = now;
        logger.warn("Frozen feed: requesting camera reopen (same throttled path as frame stall)");
        android.os.Handler h = glHandler;
        if (h != null) {
            h.post(this::restartCameraAfterError);
        }
    }

    /**
     * Starts the GPU camera pipeline.
     * 
     * @throws Exception if initialization fails
     */
    private static boolean isCurrentStartEpoch(long startEpoch) {
        return com.overdrive.app.daemon.CameraDaemon
                .isCameraStartEpochCurrent(startEpoch);
    }

    private static void requireCurrentStartEpoch(
            long startEpoch, String phase) {
        if (!isCurrentStartEpoch(startEpoch)) {
            throw new IllegalStateException(
                    "Camera start cancelled during " + phase);
        }
    }

    public void start() throws Exception {
        start(com.overdrive.app.daemon.CameraDaemon.captureCameraStartEpoch());
    }

    public void start(long startEpoch) throws Exception {
        requireCurrentStartEpoch(startEpoch, "camera admission");
        activeStartEpoch = startEpoch;
        logger.info( "Starting GPU camera pipeline...");
        // Surface the resolved ingestion mode in every camera open log so
        // field debugging can correlate "what does the recording look like"
        // with "which path the daemon took".
        logger.info("Camera ingestion mode: "
            + (USE_DILINK5_QCARCAM_PATH ? "DiLink 5 (fast_cam_capture DMA bridge)"
                : USE_DILINK4_AVM_PATH ? "DiLink 4 (SurfaceTexture passthrough)"
                : "Default (ImageReader + 2x2 rearrangement)"));
        if (USE_CAMERA2_AVM_PATH) {
            logger.info("Camera source: Camera2 360 super-frame (NDK camera2, no AVMCamera)");
            camera2RestartQueued.set(false);
        }
        startTime = System.currentTimeMillis();
        
        // SOTA: Initialize BYD camera coordinator for cooperative sharing
        if (cameraCoordinator == null) {
            cameraCoordinator = new BydCameraCoordinator();
            cameraCoordinator.setLogEventArgs(USE_DILINK4_AVM_PATH);
            cameraCoordinator.setYieldCallback(new BydCameraCoordinator.CameraYieldCallback() {
                @Override
                public boolean onYieldCamera() {
                    if (USE_DILINK5_QCARCAM_PATH) {
                        // FastCam does not participate in the legacy
                        // IBYDCameraUser yield lifecycle. That lifecycle
                        // finalizes the muxer and detaches live streaming,
                        // which turns a transient/stale coordinator callback
                        // into a recording split. DI5 arbitration is owned by
                        // the gear handoff and source-only health recovery.
                        logger.warn("Ignoring legacy camera-yield callback on "
                                + "DiLink 5; recorder and live view remain active");
                        return true;
                    }
                    if (USE_DILINK4_AVM_PATH) {
                        // Panoramic recording is a continuous co-consumer.
                        // It never releases AVMCamera when the native camera
                        // UI opens.
                        logger.warn("Ignoring camera-yield callback on DiLink 4 "
                                + "panoramic mode; keeping the single AVMCamera "
                                + "producer and recording session active");
                        return true;
                    }
                    // Contention detected — yield on GL thread
                    logger.info("YIELD: Contention detected — releasing camera for native app");
                    finishDiLink5Reacquire("new native-app yield");
                    cameraYielded = true;
                    // audit avc-yield (round 8, finding yield-mid-backoff-cascades-to-exit):
                    // a Yield #2 arriving mid-backoff for a failed Yield #1 reacquire
                    // must reset the retry budget and cancel the pending postDelayed
                    // retry. Otherwise, the stale retry runnable fires after the new
                    // yield, hits the isCameraYielded gate, throws "treating as
                    // reacquire failure", bumps the counter further, and at
                    // attemptIdx=3 cascades to System.exit(0) — converting a
                    // legitimate native-app re-engagement into a daemon kill.
                    // Symmetric with onReacquireCamera at line 543/552.
                    cancelPendingReacquireRetry(
                        "Yield: cancelled pending postDelayed reacquire retry");
                    if (glHandler != null
                            && glHandler.post(() -> yieldCameraInternal())) {
                        return true;
                    }
                    return cameraObj == null;
                }

                @Override
                public void onReacquireCamera() {
                    if (USE_DILINK5_QCARCAM_PATH) {
                        logger.info("Ignoring legacy camera-reacquire callback "
                                + "on DiLink 5; source ownership has its own "
                                + "gear/recovery state machine");
                        return;
                    }
                    // Native app released camera after contention yield — re-acquire
                    logger.info("REACQUIRE: Native app released camera — reopening");
                    beginDiLink5Reacquire("native app released camera");
                    cameraYielded = false;
                    stopYieldPoller();
                    // audit avc-yield (round 2): reset retry counter at the
                    // start of every fresh re-acquire — a successful prior
                    // cycle should not poison the next yield.
                    cancelPendingReacquireRetry(
                        "Reacquire: cancelled pending postDelayed retry");
                    if (glHandler != null) {
                        glHandler.post(() -> attemptReacquireOnGlThread());
                    }
                }

                @Override
                public void onCameraError(int eventType) {
                    // Camera HAL error — but only restart if frames have actually stopped.
                    // On DiLink5.0, event 8 fires immediately after camera open (after event 1004)
                    // as a benign HAL lifecycle notification. Restarting on it causes an infinite loop.
                    // Guard: ignore error events within 3 seconds of camera start — the HAL is still
                    // settling. If it's a real error, the frame stall watchdog will catch it.
                    long timeSinceStart = System.currentTimeMillis() - lastCameraStartTime;
                    if (timeSinceStart < 3000) {
                        logger.warn("CAMERA ERROR: event=" + eventType + " — IGNORED (camera started " +
                            timeSinceStart + "ms ago, waiting for frame stall watchdog)");
                        return;
                    }
                    // DiLink 4: the service has already closed the device and reset its
                    // client counts. A handle kept past the native app's 2 s reopen
                    // later stops the device under it, so release it now and reopen
                    // on a timer. The stall watchdog still owns silent failures.
                    if (USE_DILINK4_AVM_PATH) {
                        long now = System.currentTimeMillis();
                        long timeSinceLastError = now - lastErrorRestartTime;
                        lastErrorRestartTime = now;
                        logger.warn("CAMERA ERROR: event=" + eventType
                            + " on dilink4 ("
                            + (timeSinceLastError == now ? "first" : timeSinceLastError + "ms since last")
                            + ") — releasing the camera unless frames continue");
                        scheduleDiLink4DeathRelease(eventType, now);
                        return;
                    }
                    if (USE_DILINK5_QCARCAM_PATH) {
                        logger.error("CAMERA ERROR: event=" + eventType
                                + " — reopening only the DiLink 5 FastCam "
                                + "source");
                        requestDiLink5SourceOnlyRecovery(
                                "FastCam camera error event " + eventType);
                        return;
                    }
                    logger.error("CAMERA ERROR: event=" + eventType + " — restarting camera");
                    if (glHandler != null) {
                        glHandler.post(() -> restartCameraAfterError());
                    }
                }
            });
            if (USE_CAMERA2_AVM_PATH) {
                logger.info("camera2: skipping legacy IBYDCameraService registration");
            } else
            if (!USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH) {
                cameraCoordinator.register();
            } else if (USE_DILINK4_AVM_PATH) {
                logger.info("dilink4: skipping IBYDCameraService registration "
                        + "(continuous panoramic co-consumer)");
            } else {
                logger.info("dilink5: skipping legacy IBYDCameraService registration");
            }
        }
        
        // Start GL thread.
        //
        // PRIORITY (perf): run at THREAD_PRIORITY_DISPLAY (nice -4) instead of
        // the default (nice 0). This thread does consume->draw->swap feeding the
        // hardware encoder; at default priority it competes on equal footing
        // with every ordinary background thread in this ~40-thread daemon, and
        // the daemon itself is spawned via app_process (no cgroup/nice applied),
        // so nothing else keeps it ahead of unrelated work.
        //
        // Two concrete wins:
        //   1. Frame pacing gets more consistent, which reduces PTS jitter and
        //      encoder-input backpressure (the rt/swap stalls documented in
        //      GpuMosaicRecorder's per-stage timing notes).
        //   2. It makes the GL stall watchdog LESS likely to fire. That watchdog
        //      calls System.exit(0) when the heartbeat stalls past
        //      GL_THREAD_TIMEOUT_MS (3s), so a starved GL thread means a daemon
        //      restart, not just a dropped frame.
        //
        // Deliberately DISPLAY (-4) and not URGENT_DISPLAY (-8) or AUDIO:
        // SurfaceFlinger runs at -9, so at -4 we stay strictly below the
        // compositor and cannot starve system UI. This is the highest tier that
        // is still safely under SF.
        glThread = new HandlerThread("GL-RenderLoop",
                android.os.Process.THREAD_PRIORITY_DISPLAY);
        glThread.start();
        glHandler = new Handler(glThread.getLooper());

        // Tier 1 wiring: the AI-lane GL thread needs the camera frame seq
        // and texture id, both of which live on this instance. Implement
        // CameraState here.
        final AiLaneGl.CameraState aiCameraState = new AiLaneGl.CameraState() {
            @Override public int getCameraTextureId() { return cameraTextureId; }
            @Override public long getFrameSeq()      { return cameraFrameSeq.get(); }
            // Crash-fix: the AI lane must NOT sample the camera texture while the
            // camera source is handed off/closed/restarting (its backing
            // EGLImage is being freed/swapped). All gates are volatile/atomic
            // and safe to read cross-thread.
            @Override public boolean isCameraTextureValid() {
                return !(isCameraFrameConsumptionPaused()
                        || diLink5SafetyDisabled
                        || diLink5CloseInProgress
                        || cameraObj == null
                        || restartInProgress.get());
            }
            @Override public Object cameraTextureLock() { return cameraTextureLock; }
        };

        if (sentry != null) {
            sentry.setCameraTargetFps(targetFps);
        }

        // Initialize on GL thread.
        //
        // FIX (EGL-leak audit): this used to be a fire-and-forget post whose
        // catch block RETHREW inside the Handler callback. An init failure
        // (e.g. eglCreateContext refusing under context exhaustion) therefore
        // killed the GL-RenderLoop looper thread — the daemon's global
        // uncaught-exception handler swallowed it, start() returned "success",
        // and queued cleanup work landed on a dead looper. Now init runs on
        // the GL thread behind a latch: on failure it cleans up ON THAT SAME
        // THREAD (releaseGl, while its EGL state is still coherent), the
        // thread is quit+joined, and the actual exception propagates
        // synchronously out of start() so the pipeline's rollback path can do
        // its job.
        final boolean legacyInitialization =
                !USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH;
        final java.util.concurrent.CountDownLatch initDone =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Exception> initError =
            new java.util.concurrent.atomic.AtomicReference<>();
        // Timeout-cancellation token. If start() gives up waiting and throws,
        // a still-in-flight init runnable must NOT publish success afterwards
        // (set running=true, schedule renderLoop, start the watchdog) — and a
        // camera handle opened AFTER the rollback's stop() checked cameraObj
        // would otherwise leak with nothing left to close it. The runnable
        // re-checks this token before opening the HAL and again before
        // publishing; if cancelled, it tears down whatever it built ON THIS
        // THREAD (late cameraObj included) and exits without publishing.
        final java.util.concurrent.atomic.AtomicBoolean startCancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        // Makes cancel-set and gate-check+publication mutually exclusive.
        // Without it there is a check-then-act race: the runnable could pass
        // the gate, the timeout could fire and the rollback's stop() run to
        // completion, and THEN the runnable publishes running=true + starts
        // the watchdog against a torn-down looper — the watchdog would see a
        // permanently stalled heartbeat and System.exit the daemon. With the
        // lock, either the cancel lands first (runnable self-cleans, never
        // publishes) or publication completes first (the rollback's stop()
        // then tears down a FULLY published camera, which it handles).
        final Object publishLock = new Object();
        if (legacyInitialization) {
            legacyCameraInitializationInFlight.set(true);
        }
        boolean initPosted = glHandler.post(() -> {
            try {
                requireCurrentStartEpoch(startEpoch, "GL initialization");
                initializeGl();

                // Cancelled while initializeGl ran? Don't open the HAL at all.
                if (startCancelled.get()
                        || !isCurrentStartEpoch(startEpoch)) {
                    logger.warn("start: cancelled during GL init — releasing GL state, "
                        + "skipping camera open");
                    try { releaseGl(); } catch (Throwable t) {
                        logger.warn("start: cancel cleanup errored: " + t.getMessage());
                    }
                    return;
                }

                requireCurrentStartEpoch(startEpoch, "camera open");
                startCamera();

                // SOTA: Setup event callback for HAL error detection (-10086, 8)
                if (cameraCoordinator != null && cameraObj != null) {
                    cameraCoordinator.setupEventCallback(cameraObj);
                }

                // Publish gate — ATOMIC with the cancel-set via publishLock.
                // Inside the lock we either publish fully (running=true,
                // renderLoop scheduled, watchdog started) or observe the
                // cancel and publish nothing; the timeout path takes the same
                // lock to set the cancel, so a cancel can never land between
                // the check and the publication. Cleanup for the cancelled
                // case runs AFTER the lock is dropped — it's heavyweight
                // (HAL close + releaseGl) and needs no atomicity, only the
                // decision does.
                //
                // Tier 1: the AI-lane GL thread + its second EGL context +
                // FoveatedCropper FBO/PBO ring (~6.5MB GPU + ~2.8MB CPU) are
                // brought up LAZILY on the first surveillance-active frame
                // (see ensureAiLaneStarted in renderLoop), NOT eagerly here.
                // Both AI consumers are surveillance-only, so in every ACC-ON
                // recording mode where sentry never activates (CONTINUOUS /
                // DRIVE_MODE / PROXIMITY_GUARD) the lane + its memory + its
                // idle thread/context never exist. It is created when sentry
                // arms and torn back down when sentry disarms. The CameraState
                // (aiCameraState) is captured into a field for the lazy path.
                boolean published = false;
                synchronized (publishLock) {
                    if (!startCancelled.get()
                            && isCurrentStartEpoch(startEpoch)) {
                        this.aiCameraStateRef = aiCameraState;
                        running = true;
                        // Start render loop
                        glHandler.post(renderLoopCallback);
                        // Start watchdog
                        startWatchdog();
                        published = true;
                    }
                }

                if (!published) {
                    logger.warn("start: cancelled after camera open — closing late "
                        + "camera handle and GL state on GL thread");
                    try {
                        if (cameraObj != null) {
                            Object toClose = cameraObj;
                            cameraObj = null;
                            closeCameraForPath(toClose);
                            if (cameraCoordinator != null) {
                                cameraCoordinator.notifyPosCloseCamera();
                            }
                        }
                    } catch (Throwable t) {
                        logger.warn("start: late camera close errored: " + t.getMessage());
                    }
                    try { releaseGl(); } catch (Throwable t) {
                        logger.warn("start: cancel cleanup errored: " + t.getMessage());
                    }
                    return;
                }

                logger.info("GPU camera pipeline started (AI lane on dedicated GL thread)");
            } catch (Exception e) {
                logger.error("Failed to start GPU pipeline", e);
                initError.set(e);
                // Clean up partial GL state on THIS thread while its EGL
                // bindings are still coherent. releaseGl is fortified and
                // null-safe against whatever initializeGl did or didn't
                // allocate before throwing.
                try {
                    releaseGl();
                } catch (Throwable cleanup) {
                    logger.warn("start: GL-thread failure cleanup errored: "
                        + cleanup.getMessage());
                }
            } finally {
                initDone.countDown();
                if (legacyInitialization) {
                    legacyCameraInitializationInFlight.set(false);
                }
            }
        });
        if (!initPosted) {
            if (legacyInitialization) {
                legacyCameraInitializationInFlight.set(false);
            }
            throw new Exception(
                    "GL pipeline initialization could not be posted");
        }

        // Wait for GL-thread init to complete. DiLink 5 performs a boot-scoped
        // safety preflight and may wait for a prior daemon generation's
        // release fence. DiLink 4 retains its shipped 20-second deadline.
        //
        // Legacy startup also runs the bounded AVC cold-open recovery inside
        // this GL runnable. Its force-restart branch can legitimately consume
        // the full warmup allowance before AVMCamera/EGL gets its former
        // 10-second startup allowance. Cancelling the runnable at the old
        // 20-second aggregate deadline could therefore abort a healthy third-
        // failure AVC recovery and feed a boot/restart loop.
        long initTimeoutMs;
        if (USE_DILINK5_QCARCAM_PATH) {
            initTimeoutMs = 35_000L;
        } else if (USE_DILINK4_AVM_PATH) {
            initTimeoutMs = 20_000L;
        } else {
            initTimeoutMs = Math.max(
                    20_000L,
                    AvcHalWarmup.coldOpenWarmupTimeoutMs()
                            + GL_THREAD_WARMUP_TIMEOUT_MS);
        }
        boolean completed;
        try {
            completed = initDone.await(
                    initTimeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            // Same race as the timeout path: the runnable may still be in
            // flight — take the publish lock so the cancel is atomic with
            // the runnable's gate-check+publication.
            synchronized (publishLock) {
                startCancelled.set(true);
            }
            throw new Exception("Interrupted waiting for GL pipeline init", ie);
        }
        if (!completed) {
            // Cancel under the publish lock: after this block, EITHER the
            // runnable has already fully published (running=true + renderLoop
            // + watchdog — the rollback's stop() tears that down orderly) OR
            // it has not and its gate will now see the cancel and self-clean
            // (including a camera handle the HAL hands back after this
            // point) without publishing anything. No third interleaving is
            // possible. Do NOT tear the GL thread down here — the handler
            // serializes the runnable's own cleanup and any later releaseGl
            // post behind it. The pipeline's rollback calls stop(), which
            // owns the orderly teardown of whatever else exists.
            synchronized (publishLock) {
                startCancelled.set(true);
            }
            throw new Exception("GL pipeline init did not complete within "
                + initTimeoutMs + "ms "
                + "(camera HAL or EGL wedged) — in-flight init cancelled");
        }
        Exception failure = initError.get();
        if (failure != null) {
            // GL-side cleanup already ran on the GL thread; renderLoop was
            // never scheduled. Quit + join the thread so no dead looper is
            // left holding queued work, then surface the real exception to
            // the caller (pipeline rollback releases camera/downscaler/etc).
            HandlerThread deadThread = glThread;
            glHandler = null;
            glThread = null;
            if (deadThread != null) {
                deadThread.quitSafely();
                try {
                    deadThread.join(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            throw failure;
        }
    }

    private void cancelPendingReacquireRetry(String reason) {
        reacquireRetryCount.set(0);
        pendingReacquireEpoch.incrementAndGet();
        Runnable stale = pendingReacquireRetry;
        pendingReacquireRetry = null;
        Handler handler = glHandler;
        if (stale == null || handler == null) return;
        try {
            handler.removeCallbacks(stale);
            logger.info(reason);
        } catch (Throwable failure) {
            logger.warn("Failed to cancel pending camera reacquire: "
                + failure.getMessage());
        }
    }
    
    /**
     * Bring up the AI lane (AiLaneGl thread + shared EGL context + FoveatedCropper
     * FBO/PBO ring) lazily on the first surveillance-active frame. MUST run on the
     * GL thread with eglCore current (it is — called from renderLoop) so the
     * share-group context create + cropper GL alloc happen against a live parent
     * context. Single-flight via aiLaneStarting + the aiLaneGl null check so it's
     * a cheap no-op once up. Mirrors the old eager block at start() verbatim.
     */
    private void ensureAiLaneStarted() {
        if (aiLaneGl != null || aiLaneStarting) return;
        if (eglCore == null || aiCameraStateRef == null) return;  // pipeline not fully up yet
        aiLaneStarting = true;
        try {
            // This block can stall the GL render thread for up to ~start()'s 3s
            // latch + 1.5s cropper.init() in the degraded-driver tail. The GL
            // watchdog (GL_THREAD_TIMEOUT_MS=3000) would otherwise System.exit
            // the recording process mid-bring-up. Refresh the heartbeat right
            // before AND after so a legitimate one-time lane warmup can never be
            // mistaken for a wedged GL thread. (A genuinely hung warmup is still
            // bounded by start()'s/runOnGlThreadBlocking's own internal timeouts.)
            lastGlThreadHeartbeat = System.currentTimeMillis();
            AiLaneGl lane = new AiLaneGl(eglCore, aiCameraStateRef);
            lane.start();  // blocks until the shared context is current on its thread
            lastGlThreadHeartbeat = System.currentTimeMillis();
            lane.setConsumers(downscaler, foveatedCropper, sentry, aiLaneWorker);
            boolean cropperReady = lane.runOnGlThreadBlocking(() -> {
                if (foveatedCropper != null) foveatedCropper.init();
            }, 1500);
            lastGlThreadHeartbeat = System.currentTimeMillis();
            if (!cropperReady) {
                logger.warn("Lazy AI-lane: FoveatedCropper init did not complete in 1.5s");
            }
            // Publish the lane FIRST, then apply the cached idle-throttle readback
            // pacing by RE-READING the volatile cache. Ordering matters: a
            // control-thread setReadbackMinIntervalMs that races bring-up reads
            // aiLaneGl and forwards to the live lane only if non-null, so once we
            // publish here any subsequent setter reaches the lane directly; and by
            // re-reading the cache AFTER publishing we also pick up a write that
            // landed during the (blocking) bring-up above. If we instead applied
            // the cache before publishing, a setter interleaving between the apply
            // and the publish would see aiLaneGl==null, update only the cache, and
            // leave the just-published lane on the stale value. 0 = disabled (lane
            // keeps its modulo default).
            aiLaneGl = lane;
            lane.setReadbackMinIntervalMs(aiReadbackMinIntervalMs);
            logger.info("AI lane started lazily (surveillance armed)");
        } catch (Throwable t) {
            logger.warn("Lazy AI-lane start failed: " + t.getMessage());
            // Leave aiLaneGl null so the next active frame retries.
        } finally {
            aiLaneStarting = false;
        }
    }

    /**
     * Tear the AI lane back down when surveillance disarms, freeing the thread,
     * the shared EGL context, and the cropper's ~6.5MB GPU + ~2.8MB CPU buffers.
     * MUST run on the GL thread (called from renderLoop). aiLaneGl.shutdown()
     * releases the cropper + downscaler direct resources on the AI-lane context,
     * so afterwards we null sentry's cropper ref so AiLaneGl.processOnce re-attaches
     * a freshly-re-init'd cropper on the NEXT arm (the cropper OBJECT is reused;
     * only its GL state was released, and ensureAiLaneStarted re-init()s it).
     */
    private void releaseAiLaneOnGlThread() {
        AiLaneGl lane = aiLaneGl;
        if (lane == null) return;
        aiLaneGl = null;
        try { lane.shutdown(); } catch (Throwable ignored) {}
        // Clear the stale cropper ref the sentry captured so the lazy re-attach
        // (AiLaneGl.processOnce: getFoveatedCropper()==null -> setFoveatedCropper)
        // re-fires on the next arm instead of holding a released-GL-state cropper.
        try {
            SurveillanceEngineGpu s = sentry;
            if (s != null) s.setFoveatedCropper(null, cameraTextureId);
        } catch (Throwable ignored) {}
        logger.info("AI lane released (surveillance disarmed) — freed thread + EGL context + cropper buffers");
    }

    public boolean isTexture2D() {
        // DiLink 5 owns a native DMA compositor output texture; the decoupled
        // encoder lane publishes app-owned ring copies. Both are plain 2D.
        return USE_DILINK5_QCARCAM_PATH || USE_DECOUPLED_ENCODER_LANE;
    }

    /**
     * Initializes OpenGL context and textures.
     */
    private void initializeGl() {
        // Create EGL context
        eglCore = new EGLCore();
        
        // Create a dummy pbuffer surface and make it current
        // This is required before any OpenGL calls can be made
        dummySurface = eglCore.createPbufferSurface(1, 1);
        eglCore.makeCurrent(dummySurface);
        
        // Log GL info (now that context is current)
        GlUtil.logGlInfo();
        
        // DiLink 5 writes a standard 2D output; legacy camera paths remain OES.
        cameraTextureId = isTexture2D()
            ? GlUtil.create2DTexture()
            : GlUtil.createExternalTexture();
        windshieldTextureId = GlUtil.createExternalTexture();

        // Decoupled encoder lane: the HAL frame binds to a PRIVATE OES texture
        // and is immediately blitted into an app-owned ring; the 2D
        // cameraTextureId created above is only a pre-first-frame placeholder
        // that runDecoupledLanePass() republishes as ring slot textures.
        // Ring allocation failure is a hard start failure by design — with
        // isTexture2D() already true every consumer compiled 2D shaders, so
        // there is no coherent fallback short of a restart with the flag off.
        if (USE_DECOUPLED_ENCODER_LANE) {
            cameraOesTextureId = GlUtil.createExternalTexture();
            decoupledCamRing = new CopiedFrameRing("CamRing", 3, width, height);
            if (!decoupledCamRing.init()) {
                decoupledCamRing = null;
                throw new RuntimeException(
                    "decoupledEncoderLane: camera copy ring allocation failed ("
                    + width + "x" + height + " ×3 RGBA8) — disable "
                    + "camera.decoupledEncoderLane or free GPU memory");
            }
        }

        // Build the camera consumer. Default = oem-style SurfaceTexture
        // path (addTexture/setTexture/rmTexture + previewIndex). Falls back
        // to the ImageReader path only when USE_OEM_SURFACE_TEXTURE_PATH is
        // disabled — kept around for FPS-ceiling investigations on Seal
        // (verified ~26 fps by AvmImageReaderFpsProbe vs SurfaceFlinger's
        // ~8.5 fps clamp on legacy SurfaceTexture wiring).
        if (USE_DILINK5_QCARCAM_PATH) {
            cameraSurfaceTexture = null;
            cameraSurface = null;
        } else if (USE_OEM_SURFACE_TEXTURE_PATH) {
            createCameraSurfaceTexture();
        } else {
            createCameraImageReader();
        }
        
        // Initialize GPU components now that EGL context exists
        if (recorder != null) {
            // Recorder needs to be initialized with EGLCore and encoder
            // This should be done by the caller after encoder is created
            logger.debug( "Recorder initialization deferred to caller");
        }
        
        if (downscaler != null) {
            // The downscaler's init() spawns ITS OWN HandlerThread+EGL for the
            // legacy ImageReader-backed probe path (readPixels). That path is
            // independent of the AI-lane GL context — it's used by the
            // camera-profile probe (PanoramicCameraGpu#1125,1193) and the
            // diagnostics camera-mapping snapshot endpoint, neither of which
            // is event-correlated. So leave it set up on the encoder thread.
            //
            // The hot path (readPixelsDirect) lazy-allocates its FBO + PBO
            // ring on the *current* GL thread the first time it's called.
            // With Tier 1 wiring, that first call lands on the AiLaneGl
            // thread, so the FBO/PBOs end up in the AI-lane share-group
            // context — not here. AiLaneGl.shutdown() calls
            // GpuDownscaler.releaseDirectResources() to free them on the
            // matching context (see T1-H1 fix).
            boolean downscalerUp = downscaler.init();
            if (!downscalerUp) {
                // Explicit degraded mode: the PRIVATE probe/thumbnail thread
                // is dead (downscaler self-released — thread joined, reader
                // closed, no EGL state leaked). Core recording and the
                // AI-lane direct path are unaffected. Two consequences:
                //
                // 1. readPixels() now returns null — and the frame-15/50
                //    validation treats a null readback as a BLACK frame,
                //    which would misdiagnose a WORKING camera as a wrong
                //    camera-id and trigger a destructive re-probe (or, in
                //    auto-probe mode, sweep every id×mode combo seeing
                //    "black" everywhere). Pixel-based validation is
                //    meaningless without readback: disable it and the
                //    auto-probe for this run.
                //
                // 2. Layout config below still applies — see the comment
                //    there; the AI direct path consumes those fields.
                logger.error("GpuDownscaler init FAILED — degraded mode: camera-profile "
                    + "probe + mapping-snapshot thumbnails disabled, frame-15/50 pixel "
                    + "validation + auto-probe disabled (no readback to judge frames by; "
                    + "recording and AI direct path unaffected)");
                if (autoProbeCameras) {
                    logger.warn("Auto-probe was requested but cannot run without readback "
                        + "— keeping current camera id "
                        + (cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID));
                    autoProbeCameras = false;
                    // setAutoProbeCameras(true) gated ALL consumer passes on
                    // probeComplete, and the only code that can set it true
                    // again is the frame-15/50 readback validation we just
                    // disabled — leaving it false would gate the render loop
                    // (recording/streaming/AI) forever. Ungate now: recording
                    // whatever the current camera id delivers beats recording
                    // nothing, same trade the probe-timeout fallback makes.
                    probeComplete = true;
                }
                skipFrameValidation = true;
            }

            // Layout configuration applies UNCONDITIONALLY: these setters are
            // plain field writes (volatiles + a lock-guarded array copy) that
            // are consumed by BOTH the private-thread probe path AND the
            // AI-lane direct path (readPixelsDirect renders on the AiLaneGl
            // context and reads cameraLayout / producer corners / flips /
            // red-mask), which remains fully functional when the private
            // thread failed. Gating them on downscalerUp would hand V2 motion
            // a wrongly-arranged mosaic in degraded mode.
            // Layout 1 = full-frame passthrough. Layout 3 = DiLink 4 /
            // 2x2-native HAL. The fragment shader
            // rearranges the producer's 2x2 into canonical Front=TL,
            // Right=TR, Rear=BL, Left=BR upright via the per-role corner+
            // flip uniforms set just below. Layout 0 = legacy 4-strip on
            // every other car (Seal, Atto, Dolphin, Tang).
            downscaler.setCameraLayout(getCameraLayoutMode());

            // DiLink 4: same Variant A producer-corner remap + per-role
            // flip the recorder/stream use, so the AI-lane downscaled
            // mosaic is canonically arranged (Front=TL, Right=TR, Rear=BL,
            // Left=BR upright). V2 motion's hardcoded quadrant-index→role
            // mapping (Q0=Front..Q3=Left) only holds when the downscaler
            // emits canonical layout — without this the cropper's centroid
            // and the engine's quadrant grid disagree.
            if (CAMERA_LAYOUT_MODE == 3
                    && !com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled()) {
                // Read from Dilink4Constants rather than re-typing the arrays.
                // These were duplicated literals and had drifted: this site
                // carried the Y bit on Front+Right (the pair that rendered
                // upside down) while FoveatedCropper below carried Y bits on
                // Rear+Left instead — two different wrong answers. Routing every
                // feed site through the single source of truth is what makes
                // that class of drift impossible.
                downscaler.setProducerCornerMap(
                    Dilink4Constants.CORNER_FRONT,
                    Dilink4Constants.CORNER_RIGHT,
                    Dilink4Constants.CORNER_REAR,
                    Dilink4Constants.CORNER_LEFT);
                downscaler.setFlipFlags(
                    Dilink4Constants.FLIP_FRONT,
                    Dilink4Constants.FLIP_RIGHT,
                    Dilink4Constants.FLIP_REAR,
                    Dilink4Constants.FLIP_LEFT);
            }
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled()) {
                downscaler.setRedMaskEnabled(false);
                downscaler.setApaCenterInset(0.0f);
            } else if (USE_OEM_SURFACE_TEXTURE_PATH) {
                // Red-mask remains available on both DiLink 4 layouts. The
                // center inset is specific to the four-corner layout.
                try {
                    org.json.JSONObject camCfgDs = com.overdrive.app.config
                        .UnifiedConfigManager.loadConfig().optJSONObject("camera");
                    if (camCfgDs != null) {
                        downscaler.setRedMaskEnabled(
                            camCfgDs.optBoolean("dilink4RedMask", false));
                        downscaler.setApaCenterInset(CAMERA_LAYOUT_MODE == 3
                            ? (float) camCfgDs.optDouble(
                                "dilink4ApaCenterInset", 0.09375)
                            : 0.0f);
                    }
                } catch (Throwable t) {
                    logger.warn("Downscaler red-mask flag read failed: " + t.getMessage());
                }
            }
            if (downscalerUp) {
                logger.debug("Downscaler initialized (probe path on its own thread)");
            }
        }

        if (USE_PASSIVE_APA_MODE) {
            // Port 0 is an opaque full-frame layout on these variants. Do not
            // invent four producer quadrants for high-resolution AI crops;
            // the normal full-frame readback remains active for motion/AI.
            foveatedCropper = null;
            logger.info("Passive APA: foveated quadrant crops disabled; "
                + "using full-frame surveillance readback");
        } else {
            // Construct the foveated cropper, but defer its init to the AI-lane
            // GL thread. Allocating its FBOs + shader on the encoder thread
            // would serialize readback against encoder eglSwapBuffers.
            foveatedCropper = new FoveatedCropper(width, height,
                quadrantStripOffsetX, quadrantCornerOffsetsXY, isTexture2D());
            foveatedCropper.setCameraLayout(getCameraLayoutMode());

            // DiLink 4: override the canonical corner map with the known
            // four-corner layout so AI crops match recorder/stream geometry.
            if (CAMERA_LAYOUT_MODE == 3
                    && !com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled()) {
                foveatedCropper.setProducerCornerMap(
                    Dilink4Constants.CORNER_FRONT,
                    Dilink4Constants.CORNER_RIGHT,
                    Dilink4Constants.CORNER_REAR,
                    Dilink4Constants.CORNER_LEFT);
                foveatedCropper.setFlipFlags(
                    Dilink4Constants.FLIP_FRONT,
                    Dilink4Constants.FLIP_RIGHT,
                    Dilink4Constants.FLIP_REAR,
                    Dilink4Constants.FLIP_LEFT);
            }
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled()) {
                foveatedCropper.setRedMaskEnabled(false);
                foveatedCropper.setApaCenterInset(0.0f);
            } else if (USE_OEM_SURFACE_TEXTURE_PATH) {
                try {
                    org.json.JSONObject camCfgFc = com.overdrive.app.config
                        .UnifiedConfigManager.loadConfig().optJSONObject("camera");
                    if (camCfgFc != null) {
                        foveatedCropper.setRedMaskEnabled(
                            camCfgFc.optBoolean("dilink4RedMask", false));
                        foveatedCropper.setApaCenterInset(CAMERA_LAYOUT_MODE == 3
                            ? (float) camCfgFc.optDouble(
                                "dilink4ApaCenterInset", 0.09375)
                            : 0.0f);
                    }
                } catch (Throwable t) {
                    logger.warn("Cropper red-mask flag read failed: " + t.getMessage());
                }
            }
        }

        logger.info("OpenGL initialized (texture=" + cameraTextureId + ")");
    }
    
    /**
     * Initializes the recorder on the GL thread.
     * 
     * This must be called after the GL context is created and made current.
     * 
     * @param recorder GPU mosaic recorder to initialize
     * @param encoder Hardware encoder providing the input surface
     */
    public void initRecorderOnGlThread(GpuMosaicRecorder recorder, HardwareEventRecorderGpu encoder) {
        if (glHandler == null) {
            logger.error( "GL thread not started");
            return;
        }
        
        // Store encoder reference for draining in render loop
        this.encoder = encoder;

        // Decoupled lane: the recorder's GL (shaders, encoder EGL surface,
        // every subsequent drawFrame) lives on the EncoderLane thread with its
        // shared child context — init it THERE, not on the render thread.
        // Probe wiring and the ready-callback semantics are identical.
        if (USE_DECOUPLED_ENCODER_LANE) {
            EncoderLane lane = ensureEncoderLane();
            if (lane == null) {
                logger.error("Decoupled lane unavailable — recorder init skipped");
                return;
            }
            lane.initRecorder(recorder, encoder, () -> {
                if (recorderInitCallback != null) {
                    recorderInitCallback.run();
                }
            });
            return;
        }

        glHandler.post(() -> {
            try {
                recorder.init(eglCore, encoder);
                // Wire the HAL-contention probe so the recorder's safety valve
                // only fires when the BYD native AVM app is actively sharing
                // the camera. In normal solo operation the valve stays inert
                // and eglSwapBuffers handles encoder backpressure natively —
                // no more 1-in-N drops at 25–30 fps.
                recorder.setHalContentionProbe(() -> {
                    BydCameraCoordinator c = cameraCoordinator;
                    return c != null && c.isNativeAppActive();
                });
                logger.info( "Recorder initialized on GL thread");

                // Notify pipeline that recorder is ready
                if (recorderInitCallback != null) {
                    recorderInitCallback.run();
                }
            } catch (Exception e) {
                logger.error( "Failed to initialize recorder on GL thread", e);
            }
        });
    }
    
    // Callback for when recorder is initialized
    private Runnable recorderInitCallback;
    
    /**
     * Sets a callback to be invoked when the recorder is initialized.
     * 
     * @param callback Callback to run on GL thread after recorder init
     */
    public void setRecorderInitCallback(Runnable callback) {
        this.recorderInitCallback = callback;
    }
    
    /**
     * Initializes the stream scaler on the GL thread.
     * 
     * @param streamScaler GPU stream scaler to initialize
     * @param streamEncoder Hardware encoder for streaming
     */
    public void initStreamScalerOnGlThread(com.overdrive.app.streaming.GpuStreamScaler streamScaler,
                                          HardwareEventRecorderGpu streamEncoder) {
        if (glHandler == null) {
            logger.error("GL thread not started");
            return;
        }
        
        glHandler.post(() -> {
            try {
                streamScaler.init(eglCore, streamEncoder);
                logger.info("Stream scaler initialized on GL thread");
            } catch (Exception e) {
                logger.error("Failed to initialize stream scaler on GL thread", e);
            }
        });
    }
    
    /**
     * Gets the EGL core for initializing GPU components.
     * 
     * @return EGLCore instance (only valid after start() is called)
     */
    public EGLCore getEglCore() {
        return eglCore;
    }
    
    /**
     * Recreates the SurfaceTexture and Surface for camera switching.
     * 
     * The BYD AVMCamera HAL doesn't properly deliver frames to a Surface
     * that was previously connected to a different camera ID. After the first
     * frame, subsequent frames are never delivered, causing a frozen image.
     * Recreating the SurfaceTexture forces a clean connection to the new camera.
     */
    private void recreateCameraSurface() {
        logger.info("Recreating "
            + (USE_OEM_SURFACE_TEXTURE_PATH ? "SurfaceTexture" : "ImageReader")
            + " consumer for camera switch...");
        releaseCameraConsumer();
        if (USE_OEM_SURFACE_TEXTURE_PATH) {
            createCameraSurfaceTexture();
        } else {
            createCameraImageReader();
        }
        logger.info("Camera consumer recreated for camera switch");
    }

    /** Build a SurfaceTexture-backed consumer (oem path).
     *  Frame handling:
     *    HAL → SurfaceTexture producer (BufferQueue)
     *      → setOnFrameAvailableListener fires on diLink4FrameCallbackHandler
     *        → callback advances the genuine-arrival sequence + wakes frameSync
     *          → renderLoop sees stFramePending, calls updateTexImage()
     *  Mirrors oem's gl.C5920a path: addTexture/setTexture/rmTexture.
     *  cameraTextureId is created in initializeGl() and is the EXTERNAL_OES
     *  texture the SurfaceTexture writes into. The callback thread does no GL
     *  work; updateTexImage remains on glHandler where the EGL context is
     *  current. Keeping notification and consumption on separate loopers is
     *  load-bearing because renderLoop blocks glHandler in frameSync.wait().
     *
     *  We do NOT call attachToGLContext / detachFromGLContext on this
     *  SurfaceTexture: the SurfaceTexture(int) ctor already attaches it to
     *  the current EGL context's cameraTextureId, and updateTexImage runs
     *  on the GL thread where that context is current.  */
    private void createCameraSurfaceTexture() {
        if (USE_DILINK5_QCARCAM_PATH) {
            cameraSurfaceTexture = null;
            cameraSurface = null;
            return;
        }
        if (cameraTextureId == 0) {
            logger.warn("createCameraSurfaceTexture called before GL texture exists");
            return;
        }
        Handler callbackHandler = ensureDiLink4FrameCallbackHandler();
        SurfaceTexture created = new SurfaceTexture(cameraTextureId);
        final int consumerEpoch;
        synchronized (diLink4SurfaceTextureStateLock) {
            consumerEpoch = ++diLink4SurfaceTextureEpoch;
            cameraSurfaceTexture = created;
            stFramePending = false;
            stFrameArrivalSeq.set(0L);
            stLastConsumedArrivalSeq = 0L;
        }
        try {
            created.setOnFrameAvailableListener(
                st -> onDiLink4SurfaceTextureFrameAvailable(st, consumerEpoch),
                callbackHandler);
        } catch (RuntimeException | Error registrationFailure) {
            synchronized (diLink4SurfaceTextureStateLock) {
                if (cameraSurfaceTexture == created
                        && diLink4SurfaceTextureEpoch == consumerEpoch) {
                    diLink4SurfaceTextureEpoch++;
                    cameraSurfaceTexture = null;
                }
            }
            try { created.release(); } catch (Throwable ignored) {}
            throw registrationFailure;
        }
        cameraSurface = null;
    }

    /** Create the DI4 callback looper lazily and retain it across soft rebinds. */
    private Handler ensureDiLink4FrameCallbackHandler() {
        if (!USE_DILINK4_AVM_PATH) {
            throw new IllegalStateException(
                "SurfaceTexture callback thread requested outside DiLink 4");
        }
        HandlerThread currentThread = diLink4FrameCallbackThread;
        Handler currentHandler = diLink4FrameCallbackHandler;
        if (currentThread != null && currentThread.isAlive()
                && currentHandler != null) {
            return currentHandler;
        }
        if (currentThread != null) {
            try { currentThread.quit(); } catch (Throwable ignored) {}
        }
        HandlerThread replacement = new HandlerThread("Di4-FrameCallback");
        replacement.start();
        Handler replacementHandler = new Handler(replacement.getLooper());
        if (glHandler != null
                && replacementHandler.getLooper() == glHandler.getLooper()) {
            replacement.quit();
            throw new IllegalStateException(
                "DiLink 4 frame callback looper aliases GL render looper");
        }
        diLink4FrameCallbackThread = replacement;
        diLink4FrameCallbackHandler = replacementHandler;
        return replacementHandler;
    }

    /** Full-pipeline teardown only; soft SurfaceTexture rebinds reuse the looper. */
    private void shutdownDiLink4FrameCallbackThread() {
        if (USE_DILINK4_AVM_PATH) {
            // Fence callbacks even if the preceding consumer teardown was
            // partial or threw before it could retire the active identity.
            // cameraTornDown is normally already true, but the epoch is the
            // durable guard for startup-failure and abnormal-release paths.
            synchronized (diLink4SurfaceTextureStateLock) {
                diLink4SurfaceTextureEpoch++;
            }
        }
        HandlerThread thread = diLink4FrameCallbackThread;
        diLink4FrameCallbackThread = null;
        diLink4FrameCallbackHandler = null;
        if (thread == null) return;
        try { thread.quitSafely(); } catch (Throwable ignored) {}
        if (Thread.currentThread() == thread) return;

        final boolean[] interrupted = { Thread.interrupted() };
        boolean exited = false;
        try {
            exited = com.overdrive.app.util.ThreadJoins
                .joinFullDeadline(thread, 1000L, interrupted);
        } finally {
            if (interrupted[0]) {
                Thread.currentThread().interrupt();
            }
        }
        if (!exited) {
            // Every callback is generation/identity fenced, so a late worker
            // cannot mutate a replacement pipeline. Keep the failure visible
            // without turning a harmless callback-thread retirement delay into
            // a process restart.
            logger.warn("DiLink 4 frame callback thread did not exit within 1s");
        }
    }

    static boolean shouldAcceptDiLink4SurfaceTextureCallback(
            int callbackEpoch,
            int activeEpoch,
            boolean sameSurfaceTexture,
            boolean cameraTornDown) {
        return callbackEpoch == activeEpoch
            && sameSurfaceTexture
            && !cameraTornDown;
    }

    /**
     * Producer notification only — deliberately no GL calls on this thread.
     * The identity + epoch check rejects messages queued by an old
     * SurfaceTexture before its listener was cleared during rebind/teardown.
     */
    private void onDiLink4SurfaceTextureFrameAvailable(
            SurfaceTexture callbackSurface, int callbackEpoch) {
        boolean softRecoverySucceeded;
        boolean deferredReopenCancelled;
        int recoveryCycles;
        String demandReason;
        synchronized (diLink4SurfaceTextureStateLock) {
            if (!shouldAcceptDiLink4SurfaceTextureCallback(
                    callbackEpoch,
                    diLink4SurfaceTextureEpoch,
                    cameraSurfaceTexture == callbackSurface,
                    cameraTornDown)) {
                return;
            }
            dilink4LastGenuineFrameArrivalMs = System.currentTimeMillis();
            softRecoverySucceeded =
                dilink4SoftRecoveryInFlight.getAndSet(false);
            if (softRecoverySucceeded) {
                dilink4SoftRecoveryEpoch.incrementAndGet();
            }
            deferredReopenCancelled =
                dilink4DeferredReopenPending.getAndSet(false);
            if (deferredReopenCancelled) {
                dilink4DeferredReopenEpoch.incrementAndGet();
            }
            recoveryCycles = dilink4SoftRecoveryCycles;
            demandReason = dilink4FrameDemandReason;
            dilink4SoftRecoveryCycles = 0;

            // stFrameArrivalSeq is the ONLY genuine "the HAL queued a new
            // buffer" signal on this path. Publish it before notifying the GL
            // waiter so updateTexImage cannot run ahead of freshness tracking.
            stFrameArrivalSeq.incrementAndGet();
            stFramePending = true;
            synchronized (frameSync) {
                frameSync.notify();
            }
        }
        if (softRecoverySucceeded) {
            logger.info("DiLink 4 soft producer recovery succeeded after "
                + recoveryCycles + " cycle(s); first SurfaceTexture"
                + " frame arrived for demand=" + demandReason);
        }
        if (deferredReopenCancelled) {
            logger.info("DiLink 4 deferred full reopen cancelled — producer"
                + " delivered a genuine SurfaceTexture frame");
        }
    }

    /**
     * Atomically retire the current SurfaceTexture before clearing/releasing it.
     * A callback already queued for the retired object will observe either the
     * new epoch or the new identity and return without touching frame health.
     */
    private SurfaceTexture retireCameraSurfaceTextureConsumer() {
        synchronized (diLink4SurfaceTextureStateLock) {
            SurfaceTexture retired = cameraSurfaceTexture;
            diLink4SurfaceTextureEpoch++;
            cameraSurfaceTexture = null;
            stFramePending = false;
            stFrameArrivalSeq.set(0L);
            stLastConsumedArrivalSeq = 0L;
            return retired;
        }
    }

    /** Bind the active SurfaceTexture to the AVMCamera via reflection,
     *  mirroring oem's startPreview block:
     *      addTexture(st, previewIndex)
     *      setTexture(st, previewIndex)
     *      startPreview()
     *  previewIndex comes from cameraSurfaceMode (0=firmware-default panorama
     *  output, 1-4=individual viewpoints).
     *  Caller must have just opened the camera (cameraObj != null). */
    private void attachSurfaceTextureToCamera(int cameraId) throws Exception {
        attachSurfaceTextureToCamera(cameraId, false, null);
    }

    private void attachSurfaceTextureToCamera(
            int cameraId, boolean di4CallbackReady, Object expectedCamera)
            throws Exception {
        Object attachedCamera = cameraObj;
        if (attachedCamera == null) {
            throw new IllegalStateException("attachSurfaceTextureToCamera with null cameraObj");
        }
        if (expectedCamera != null && attachedCamera != expectedCamera) {
            logger.info("Skipping stale DiLink 4 callback texture attach");
            return;
        }
        if (attachedCamera instanceof com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend) {
            logger.info("Attaching GL texture to DiLink 5 QCarCam backend");
            if (!((com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend)
                    attachedCamera).isStreaming()) {
                throw new IllegalStateException(
                    "DiLink 5 QCarCam stream is not active");
            }
            return;
        }
        if (cameraSurfaceTexture == null) {
            throw new IllegalStateException("attachSurfaceTextureToCamera before createCameraSurfaceTexture");
        }

        Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
        int previewIndex = cameraSurfaceMode;

        // A camera is being attached — clear the teardown sentinel BEFORE any
        // helper thread is spawned below, so they see a live camera. (Set again
        // by releaseCameraConsumer on stop/reattach.)
        cameraTornDown = false;

        // BmmCameraInfo dim probe — what the HAL claims it'll emit for this
        // camera id BEFORE we attach. Mirrors oem's C6500c.m28945a path.
        // Output is purely diagnostic; the BYD HAL ignores anything we do
        // with these numbers, but logging them lets us correlate the
        // "configured strip" (pipeline expects 5120x960) against the slot
        // we're actually opening (id=1 might be 1280x720, etc.). On variants
        // where BmmCameraInfo is empty (vehicle.config.cam_sort unset) both
        // calls return 0 — note that explicitly.
        logHalDeclaredDims(cameraId);

        // AVM factory-calibration probe. oem reads these at app init:
        //   - persist.vendor.camera.autostudy.avm  (calibration coefficients)
        //   - vehicle.config.camInfo.avm           (physical module info)
        //   - vehicle.config.cam_sort / pano_cam / pano_l_cam (id mapping)
        // Purely INFORMATIONAL. Do NOT infer "the HAL will paint a red
        // calibration banner" from these being empty — that claim used to live
        // here and it is FALSE. Verified 2026-07-29 against the OEM reference
        // app (decompiled OEM dashcam): it contains ZERO SystemProperties.set
        // calls and ZERO calibration strings, reads these five props once at
        // init purely to upload them as telemetry (`vj/a.java:325-347` →
        // `ph/q.java:198`), and their ONE functional consumer is a quadrant-
        // order permutation (`il/e.java:150`) that byd_apa boards bypass
        // entirely — lens indices are hardcoded under `if (d.c()) { … return; }`
        // in `AVMCameraLensFacing.java:78-86`. Empty `autostudy.avm` is a fully
        // supported path in the app that works on these cars.
        logAvmCalibrationProps();

        // Every pre-API-32 DiLink 4 starts the byte producer first and attaches
        // the texture from the first valid callback. The proven sequence never
        // resolves the BmmCameraInfo width/height methods on those releases, so a
        // positive vendor backport must not silently switch us to texture-first
        // startup. The callback stays armed until that frame
        // arrives or this camera is torn down; startup itself does not block.
        boolean di4PreviewBootstrapped = di4CallbackReady;
        if (shouldUseDi4PreviewBootstrap()
                && !di4CallbackReady) {
            if (bootstrapDi4PreviewBeforeTexture(
                    avmClass, cameraId, previewIndex, attachedCamera)) {
                firstFrameDimsLogged = false;
                return;
            }
        }

        // ── isPreview() PROBE ───────────────────────────────────────────────
        // Checked on every SurfaceTexture availability edge. True
        // means THIS AVMCamera instance is already streaming, so only
        // addTexture+setTexture are needed; never call startPreview twice.
        boolean alreadyPreviewing = false;
        try {
            Method mIsPreview = avmClass.getDeclaredMethod("isPreview");
            mIsPreview.setAccessible(true);
            Object pv = mIsPreview.invoke(attachedCamera);
            if (pv instanceof Boolean) alreadyPreviewing = (Boolean) pv;
            logger.info("isPreview() = " + alreadyPreviewing
                + (di4PreviewBootstrapped
                    ? " after DiLink 4 callback bootstrap"
                    : " (diagnostic only — texture-first path unchanged)"));
        } catch (NoSuchMethodException e) {
            logger.info("isPreview() not present on this HAL");
        } catch (Throwable t) {
            logger.warn("isPreview() probe failed: " + t.getMessage());
        }

        Method mAddTexture = avmClass.getDeclaredMethod(
            "addTexture", SurfaceTexture.class, int.class);
        mAddTexture.setAccessible(true);
        mAddTexture.invoke(attachedCamera, cameraSurfaceTexture, previewIndex);

        Method mSetTexture = avmClass.getDeclaredMethod(
            "setTexture", SurfaceTexture.class, int.class);
        mSetTexture.setAccessible(true);
        mSetTexture.invoke(attachedCamera, cameraSurfaceTexture, previewIndex);

        Method mStart = avmClass.getDeclaredMethod("startPreview");
        mStart.setAccessible(true);
        if (USE_DILINK4_AVM_PATH && alreadyPreviewing) {
            logger.info("DiLink 4 texture attached: addTexture+setTexture(idx="
                + previewIndex + "), preview already running — no second startPreview"
                + " (cameraId=" + cameraId
                + ", callbackBootstrap=" + di4PreviewBootstrapped + ")");
        } else {
            Object startResult = mStart.invoke(attachedCamera);
            logger.info("Oem-path attached: addTexture+setTexture(idx=" + previewIndex
                + ") + startPreview → " + startResult
                + " (cameraId=" + cameraId + ", isPreview was " + alreadyPreviewing + ")");
        }

        // ── BYTE-CALLBACK KICK when the HAL declares no preview size ───────
        // API-32+ resolves BmmCameraInfo and uses this fallback when the
        // declared size is absent/zero. Pre-32 DI4 already took the unconditional
        // callback-first branch above.
        //
        // We keep our SurfaceTexture as the real frame source and use the
        // callback purely as a producer kick.
        //
        // ARMED IMMEDIATELY, matching the OEM (gl/a.java:409-413). There is no
        // delay: a car whose API-32+ BmmCameraInfo answers correctly never reaches
        // this branch, while a missing entry needs the producer kick immediately.
        if (!halDeclaredDimsKnown && !di4PreviewBootstrapped) {
            armPreviewCallbackKick(avmClass, previewIndex, false, null);
        }

        // NO POST-ATTACH SETTLE HERE — deliberately.
        //
        // An earlier revision suppressed consumer draws for 2000 ms after attach,
        // citing the OEM's `sendMessageDelayed(..., 2000L)` at gl/a.java:420/:194
        // as parity. That was a MISREADING, on three counts:
        //   1. Those messages post into gl/a.java's handler, whose only real work
        //      is `cameraListener.b(width, height)` (gl/a.java:169-172) — and that
        //      callback is where the OEM *starts* its GL renderer
        //      (ll/k.java:331-333: mGLManager.s() / .p(1) / .m(w,h)). Until it
        //      runs, `isGLReleased` is true and `onDrawFrame` early-returns
        //      (ll/k.java:148-151). So the OEM has no renderer to suppress — it is
        //      a renderer START delay, not a draw-suppression window. Our GL loop
        //      is already live and wired when we attach, so there is no equivalent
        //      seam and suppressing draws is not the same operation.
        //   2. The :420 delay sits in the else-branch of gl/a.java:407 — the
        //      size-KNOWN path. A unit that declares no preview size (exactly the
        //      broken population here) takes the byte-callback branch and gets NO
        //      delay at all. Applying one unconditionally hurt precisely the cars
        //      the OEM exempts.
        //   3. The OEM's is CAS-latched once per camera session
        //      (isFirstFrame, gl/a.java:149); ours re-armed on every attach,
        //      punching a 2 s hole in the recording on every reacquire/restart.
        // Net effect was a guaranteed 2 s of missing video added to a bug whose
        // headline symptom is missing video. Do not reintroduce it as "parity".

        // Re-arm the first-frame transform-matrix log so we print it on the
        // next frame after every (re)attach, not just the very first
        // session. SurfaceTexture stays the same instance across recreate
        // so we'd otherwise miss the dim probe on probe-driven re-opens.
        firstFrameDimsLogged = false;
    }

    /** The callback-first bootstrap is restricted to the pre-API-32 DiLink 4 path.
     *  The explicit runtime camera mode keeps default, legacy and DiLink 5/SL7
     *  models completely outside this behavior. */
    private boolean shouldUseDi4PreviewBootstrap() {
        return USE_DILINK4_AVM_PATH
            && android.os.Build.VERSION.SDK_INT < 32;
    }

    /**
     * Start the old-Di4 producer in byte-callback mode and return immediately.
     * The first valid callback schedules SurfaceTexture attachment on the GL
     * thread; the callback remains armed until then or until teardown.
     *
     * <p>Returns false when the callback API cannot be armed, allowing the caller
     * to execute the exact texture-first path that shipped before this branch.
     */
    private boolean bootstrapDi4PreviewBeforeTexture(
            Class<?> avmClass,
            int cameraId,
            int previewIndex,
            Object expectedCamera) {
        boolean armed = armPreviewCallbackKick(avmClass, previewIndex, true, () -> {
            Handler handler = glHandler;
            if (handler == null || cameraTornDown || cameraObj != expectedCamera) {
                logger.info("Ignoring stale DiLink 4 preview callback");
                disableDi4PreviewCallbackAfterFirstFrame(
                    previewIndex, expectedCamera);
                return;
            }
            boolean posted = handler.post(() -> {
                if (cameraTornDown || cameraObj != expectedCamera) return;
                try {
                    logger.info("DiLink 4 callback-first bootstrap received a valid frame"
                        + " — attaching SurfaceTexture");
                    attachSurfaceTextureToCamera(
                        cameraId, true, expectedCamera);
                } catch (Throwable t) {
                    logger.error("DiLink 4 callback texture attach failed: "
                        + t.getMessage());
                }
            });
            if (!posted) {
                logger.warn("DiLink 4 callback texture attach was rejected"
                    + " by the GL thread");
            }
            disableDi4PreviewCallbackAfterFirstFrame(
                previewIndex, expectedCamera);
        });
        if (!armed) {
            logger.warn("DiLink 4 callback-first bootstrap unavailable"
                + " — falling back to shipped texture-first attach");
        }
        return armed;
    }

    /** True when BmmCameraInfo answered with a usable (non-zero) preview size
     *  for the slot we are attaching. False when the class/method is absent or
     *  the HAL reports 0x0 — the OEM treats that as "take the byte-callback
     *  startup path instead", and so do we. Set by {@link #logHalDeclaredDims}
     *  on every attach, before it is read. */
    private boolean halDeclaredDimsKnown = false;

    /** Probe BmmCameraInfo for the HAL's declared preview size for this
     *  cameraId. Also latches {@link #halDeclaredDimsKnown}, which selects the
     *  attach strategy (texture-only vs texture + byte-callback kick). */
    private void logHalDeclaredDims(int cameraId) {
        halDeclaredDimsKnown = false;
        if (shouldUseDi4PreviewBootstrap()) {
            logger.info("BmmCameraInfo dim probe skipped on pre-API-32 DiLink 4"
                + " (pre-API-32 always uses callback-first startup)");
            return;
        }
        try {
            Class<?> bmm = Class.forName("android.hardware.BmmCameraInfo");
            Method gw = bmm.getDeclaredMethod("getDefaultPreviewWidth", int.class);
            Method gh = bmm.getDeclaredMethod("getDefaultPreviewHeight", int.class);
            gw.setAccessible(true);
            gh.setAccessible(true);
            Object w = gw.invoke(null, cameraId);
            Object h = gh.invoke(null, cameraId);
            int wInt = (w instanceof Integer) ? (Integer) w : 0;
            int hInt = (h instanceof Integer) ? (Integer) h : 0;
            logger.info("BmmCameraInfo declared dims for cam=" + cameraId
                + ": " + wInt + "x" + hInt
                + (wInt == 0 || hInt == 0
                    ? "  (HAL has no entry — vehicle.config.cam_sort empty)"
                    : "")
                + "; pipeline configured " + width + "x" + height);
            // BmmCameraInfo's "single preview" reports per-quadrant; the
            // mosaic-doubled dims that AVMCamera emits for previewIndex=0
            // are 2*W x 2*H per oem's C6500c.m28945a:88. Spell that out.
            if (wInt > 0 && hInt > 0) {
                halDeclaredDimsKnown = true;
                int mosaicW = wInt * 2;
                int mosaicH = hInt * 2;
                logger.info("  → mosaic-doubled would be " + mosaicW + "x" + mosaicH
                    + " (oem AVMCamera 2x scale rule)");
            } else {
                logger.info("  → no declared size: old DiLink 4 uses callback-first"
                    + " bootstrap; other paths arm the producer kick after attach");
            }
        } catch (ClassNotFoundException e) {
            logger.info("BmmCameraInfo class not present — skipping dim probe");
        } catch (NoSuchMethodException e) {
            logger.info("BmmCameraInfo.getDefaultPreviewWidth/Height not found — skipping dim probe");
        } catch (Throwable t) {
            logger.warn("BmmCameraInfo dim probe failed: " + t.getMessage());
        }
    }

    /** Live byte-callback proxy, non-null only while a producer kick is armed.
     *  GL/daemon threads both touch it during attach/teardown, hence volatile. */
    private volatile Object previewCallbackProxy = null;

    /** Max time the post-attach fallback kick stays armed.
     *
     *  <p>The callback-first old-Di4 path does not use this deadline: it stays
     *  armed until a valid byte frame arrives or that camera is torn down. */
    private static final long POST_ATTACH_PREVIEW_KICK_MAX_MS = 6_000L;

    /** Liveness signal for the attach-time helper threads.
     *
     *  <p>These threads must NOT test {@code running}: {@code start()} calls
     *  {@code startCamera()} — which attaches and therefore spawns them — BEFORE
     *  it sets {@code running = true}. On a cold start they would observe
     *  {@code running == false} on their first iteration and return immediately,
     *  silently disabling both the byte-callback producer kick and the
     *  startPreview safety net on precisely the hardware they exist for.
     *
     *  <p>Cleared when a camera is attached, set on any consumer teardown. So
     *  "torn down" means an actual stop/reattach happened, not merely "start()
     *  has not finished wiring itself up yet". */
    private volatile boolean cameraTornDown = false;

    /** SINGLE-FLIGHT guard for the byte-callback watcher thread. Attach can recur
     *  (auto-probe walk, reacquire, error restart); without this, each attach
     *  would spawn another sleeping thread and the only thing bounding the count
     *  would be luck plus per-caller throttling spread across five call sites.
     *  CAS-claimed on spawn, cleared in the worker's finally. */
    private final AtomicBoolean previewKickWatcherRunning = new AtomicBoolean(false);

    /** Atomic arm-claim for the byte-callback kick. Guards the install sequence
     *  itself (a plain null-check on previewCallbackProxy is check-then-act and
     *  could let two racing attaches each install a proxy, leaking the first).
     *  Released by disarmPreviewCallbackKick, or on any install failure. */
    private final AtomicBoolean previewKickArming = new AtomicBoolean(false);


    /**
     * Arm the AVMCamera byte preview callback purely as a producer kick, for
     * HALs that declare no preview size.
     *
     * <p>Rationale (OEM parity): when {@code BmmCameraInfo} has no entry for the
     * slot, the reference app does not use the texture path alone — it runs
     * {@code setPreviewCallback} + {@code startPreview} +
     * {@code enablePreviewCallback(idx)} and only tears the callback down once a
     * frame has landed ({@code gl/a.java:407-413} and {@code :162-163}). On
     * those boards the callback is what makes the producer start emitting.
     *
     * <p>We do NOT decode the callback bytes — the SurfaceTexture remains the
     * one frame source, so there is no second decode path to keep in sync.
     * Callback-driven Di4 startup waits for a valid byte frame; the fallback
     * post-attach kick keeps its existing bounded deadline.
     *
     * <p>Fails soft in every direction: any missing method, any throw, and we
     * simply continue with the plain texture attach that shipped before.
     */
    private boolean armPreviewCallbackKick(
            Class<?> avmClass,
            int previewIndex,
            boolean startPreviewBeforeEnable,
            Runnable firstValidFrameAction) {
        // ATOMIC claim, not a check-then-act on the volatile. Two attaches racing
        // (probe walk + reacquire) could both read previewCallbackProxy == null and
        // both install a proxy; the second would overwrite the field and the first
        // proxy would be leaked — permanently armed, with the HAL copying
        // full-resolution frames to a callback nobody will ever disarm.
        if (!previewKickArming.compareAndSet(false, true)) {
            logger.info("Preview-callback kick already armed/arming — skipping re-arm");
            return false;
        }
        final Object camAtArm = cameraObj;
        if (camAtArm == null) {
            previewKickArming.set(false);
            return false;
        }
        // Fresh arm ⇒ fresh disarm signals. A leftover true from a previous
        // session would make the watcher disarm on its very first tick.
        previewKickByteSeen = false;
        previewKickFirstByteLogged = false;
        try {
            Class<?> cbInterface = Class.forName(
                "android.hardware.AVMCamera$IPreviewCallback");
            final long baselineArrival = stFrameArrivalSeq.get();
            final AtomicBoolean firstValidFrameHandled = new AtomicBoolean(false);
            Object proxy = Proxy.newProxyInstance(
                cbInterface.getClassLoader(),
                new Class<?>[]{ cbInterface },
                (p, method, args) -> {
                    // AVMCamera callback args are
                    // (camera, data, width, height, format, size, index, time).
                    // We do not consume the pixels, only verify a real payload.
                    // NOTE: this runs on a HAL binder thread. Keep it trivial and
                    // never touch GL state here.
                    if ("onPreview".equals(method.getName())) {
                        int w = -1, h = -1, size = -1;
                        if (args != null && args.length >= 6) {
                            if (args[2] instanceof Integer) w = (Integer) args[2];
                            if (args[3] instanceof Integer) h = (Integer) args[3];
                            if (args[5] instanceof Integer) size = (Integer) args[5];
                        }
                        // Preserve the shipped post-attach fallback behavior:
                        // any callback proves that producer path is responsive.
                        previewKickByteSeen = true;
                        boolean validFrame = w > 0 && h > 0 && size > 0;
                        // DI4 texture attachment still requires a usable frame.
                        if (validFrame) {
                            if (firstValidFrameAction != null
                                    && firstValidFrameHandled.compareAndSet(
                                        false, true)) {
                                try {
                                    firstValidFrameAction.run();
                                } catch (Throwable t) {
                                    logger.warn("Preview callback action failed: "
                                        + t.getMessage());
                                }
                            }
                        }
                        if (!previewKickFirstByteLogged) {
                            previewKickFirstByteLogged = true;
                            logger.info("Preview-callback kick: HAL emitted first byte"
                                + " frame " + w + "x" + h + " size=" + size
                                + " (producer is alive; texture path should follow)");
                            // Record the dims the HAL reports on this callback.
                            //
                            // DO NOT over-read these. They are the byte-callback's
                            // own view of previewIndex 0 and are NOT proof of the
                            // stitched-mosaic geometry: a unit that renders the 2x2
                            // split perfectly still reports 1280x720 here. Treating
                            // this as "the producer is a single camera, so the
                            // quadrant remap must be wrong" is a mistake that was
                            // made once already — the 4-cam split was correct and
                            // the real defect was elsewhere. Diagnostic only.
                            if (w > 0 && h > 0) {
                                halBytePathWidth = w;
                                halBytePathHeight = h;
                            }
                        }
                        return null;
                    }
                    // Object methods must still behave sanely on a proxy.
                    String n = method.getName();
                    if ("hashCode".equals(n)) return System.identityHashCode(p);
                    if ("equals".equals(n)) return args != null && args.length == 1 && p == args[0];
                    if ("toString".equals(n)) return "AvmPreviewKickProxy";
                    return null;
                });

            Method mSetCb = avmClass.getDeclaredMethod(
                "setPreviewCallback", cbInterface);
            mSetCb.setAccessible(true);
            // PUBLISH THE FIELD BEFORE the HAL knows about the proxy. If
            // enablePreviewCallback's lookup or invoke throws, the catch below
            // must be able to SEE that a callback is installed in order to tear
            // it down — with the assignment after the invoke, the field was still
            // null on that path and the HAL kept our proxy with no disarm ever
            // issued (a leak that only the next camera close cleaned up).
            previewCallbackProxy = proxy;
            previewKickArmedCamera = camAtArm;
            mSetCb.invoke(camAtArm, proxy);

            if (startPreviewBeforeEnable) {
                Method mStart = avmClass.getDeclaredMethod("startPreview");
                mStart.setAccessible(true);
                Object started = mStart.invoke(camAtArm);
                logger.info("DiLink 4 callback-first bootstrap: startPreview → "
                    + started + " before enablePreviewCallback(idx="
                    + previewIndex + ")");
            }

            Method mEnableCb = avmClass.getDeclaredMethod(
                "enablePreviewCallback", int.class);
            mEnableCb.setAccessible(true);
            Object enabled = mEnableCb.invoke(camAtArm, previewIndex);
            logger.info("Preview-callback kick ARMED (idx=" + previewIndex
                + ", enablePreviewCallback → " + enabled + ")");

            // Callback-driven Di4 startup has no timeout. Its first valid frame
            // action queues both callback removal and texture attachment; close
            // also clears it if the camera is torn down before any frame arrives.
            if (firstValidFrameAction != null) {
                return true;
            }

            // Disarm watcher. Runs off the GL thread so a wedged HAL call can
            // never stall rendering. Single-flight + monotonic clock, same
            // reasoning as the two schedulers.
            final int idx = previewIndex;
            if (!previewKickWatcherRunning.compareAndSet(false, true)) {
                // A previous watcher is still winding down (it releases the arm
                // claim in disarm's finally BEFORE clearing this flag, so a
                // racing attach can land here). We have already installed a
                // proxy and there is nobody to disarm it — tear it down now
                // rather than leave the HAL copying frames for the whole session
                // with previewKickArming wedged true.
                logger.info("Preview-kick watcher already running — disarming this"
                    + " arm rather than leaving it unattended");
                disarmPreviewCallbackKick(idx);
                return false;
            }
            Thread watcher = new Thread(() -> {
                long deadline = android.os.SystemClock.elapsedRealtime()
                    + POST_ATTACH_PREVIEW_KICK_MAX_MS;
                try {
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        // cameraTornDown, NOT !running — start() spawns us from
                        // startCamera() before it sets running = true, so testing
                        // `running` would disarm the kick instantly on cold start.
                        if (cameraTornDown
                                || cameraObj != camAtArm
                                || previewKickArmedCamera != camAtArm) {
                            disarmPreviewCallbackKick(idx, camAtArm);
                            return;
                        }
                        if (previewKickByteSeen) {
                            logger.info("Preview-callback kick: producer emitted"
                                + " a byte callback — disarming");
                            disarmPreviewCallbackKick(idx, camAtArm);
                            return;
                        }
                        if (stFrameArrivalSeq.get() != baselineArrival) {
                            logger.info("Preview-callback kick: texture path delivered"
                                + " — disarming callback");
                            disarmPreviewCallbackKick(idx, camAtArm);
                            return;
                        }
                        Thread.sleep(200);
                    }
                    logger.warn("Preview-callback kick: no valid frame within "
                        + POST_ATTACH_PREVIEW_KICK_MAX_MS
                        + "ms — disarming fallback callback");
                    disarmPreviewCallbackKick(idx, camAtArm);
                } catch (InterruptedException ignored) {
                    disarmPreviewCallbackKick(idx, camAtArm);
                } catch (Throwable t) {
                    logger.warn("Preview-kick watcher error: " + t.getMessage());
                    disarmPreviewCallbackKick(idx, camAtArm);
                } finally {
                    previewKickWatcherRunning.set(false);
                }
            }, "AvmPreviewKick");
            watcher.setDaemon(true);
            try {
                watcher.start();
            } catch (Throwable startFail) {
                // OOM / EAGAIN: no worker exists, so nothing will ever clear
                // previewKickWatcherRunning or disarm the proxy. Undo both here or
                // every future arm lands on the CAS-lost branch above forever.
                logger.warn("Preview-kick watcher failed to start: " + startFail.getMessage());
                previewKickWatcherRunning.set(false);
                disarmPreviewCallbackKick(idx);
                return false;
            }
            return true;
        } catch (ClassNotFoundException e) {
            previewKickArming.set(false);
            logger.info("AVMCamera$IPreviewCallback absent — no byte-callback kick available");
            return false;
        } catch (NoSuchMethodException e) {
            if (previewCallbackProxy != null) {
                disarmPreviewCallbackKick(previewIndex);
            } else {
                previewKickArming.set(false);
            }
            logger.info("setPreviewCallback/enablePreviewCallback absent — skipping kick: "
                + e.getMessage());
            return false;
        } catch (Throwable t) {
            // Release the claim so a later attach can retry; if a proxy did get
            // installed before the throw, tear it down rather than leak it.
            if (previewCallbackProxy != null) {
                disarmPreviewCallbackKick(previewIndex);
            } else {
                previewKickArming.set(false);
            }
            logger.warn("Preview-callback kick failed to arm: " + t.getMessage());
            return false;
        }
    }

    /** Tear the producer kick down. Idempotent; safe from any thread.
     *  Always releases the arm-claim, even when there was nothing to tear down,
     *  so a failed/partial arm can never wedge the kick permanently.
     *
     *  <p>Acts on {@link #previewKickArmedCamera} — the camera the kick was armed
     *  ON — never on a re-read of {@code cameraObj}. A stale watcher outliving a
     *  camera swap would otherwise disarm the NEW camera's freshly-armed kick
     *  (guaranteed to trigger, since releaseCameraConsumer zeroes the arrival
     *  counter the watcher compares against) and would issue
     *  {@code disablePreviewCallback} on an instance we never enabled — the exact
     *  co-consumer hazard we avoid elsewhere in this method. */
    private void disarmPreviewCallbackKick(int previewIndex) {
        disarmPreviewCallbackKick(previewIndex, null);
    }

    /** First-valid-frame cleanup for callback-driven Di4 startup.
     *  Keep the registered proxy in place but disabled until camera close. */
    private void disableDi4PreviewCallbackAfterFirstFrame(
            int previewIndex, Object expectedCamera) {
        Object cam = previewKickArmedCamera;
        if (cam == null || cam != expectedCamera) return;
        try {
            Method mDisable = cam.getClass().getDeclaredMethod(
                "disablePreviewCallback", int.class);
            mDisable.setAccessible(true);
            mDisable.invoke(cam, previewIndex);
            if (previewKickArmedCamera == expectedCamera) {
                previewCallbackProxy = null;
                previewKickArmedCamera = null;
                previewKickArming.set(false);
            }
            logger.info("DiLink 4 preview callback disabled after first valid frame");
        } catch (Throwable t) {
            logger.warn("DiLink 4 preview callback disable failed: "
                + t.getMessage());
            disarmPreviewCallbackKick(previewIndex, expectedCamera);
        }
    }

    private void disarmPreviewCallbackKick(
            int previewIndex, Object expectedCamera) {
        Object cam = previewKickArmedCamera;
        if (expectedCamera != null && cam != expectedCamera) return;
        Object proxy = previewCallbackProxy;
        if (proxy == null) { previewKickArming.set(false); return; }
        if (cam == null || cam != cameraObj) {
            // The camera we armed is gone or has been replaced. closeCameraForPath
            // already nulled the HAL-side callback for it, so there is nothing
            // left to tear down — just drop our references and release the claim
            // WITHOUT touching the current camera.
            previewCallbackProxy = null;
            previewKickArmedCamera = null;
            previewKickArming.set(false);
            logger.info("Preview-kick disarm skipped — armed camera already replaced/closed");
            return;
        }
        previewCallbackProxy = null;
        previewKickArmedCamera = null;
        try {
            Class<?> avmClass = cam.getClass();
            try {
                Method mDisable = avmClass.getDeclaredMethod(
                    "disablePreviewCallback", int.class);
                mDisable.setAccessible(true);
                mDisable.invoke(cam, previewIndex);
            } catch (Throwable t) {
                logger.info("disablePreviewCallback during disarm: " + t.getMessage());
            }
            try {
                Class<?> cbInterface = Class.forName(
                    "android.hardware.AVMCamera$IPreviewCallback");
                Method mSetCb = avmClass.getDeclaredMethod(
                    "setPreviewCallback", cbInterface);
                mSetCb.setAccessible(true);
                mSetCb.invoke(cam, (Object) null);
            } catch (Throwable t) {
                logger.info("setPreviewCallback(null) during disarm: " + t.getMessage());
            }
            logger.info("Preview-callback kick disarmed");
        } catch (Throwable t) {
            logger.warn("Preview-callback disarm failed: " + t.getMessage());
        } finally {
            previewKickArming.set(false);
        }
    }

    /**
     * Publish whether a real camera consumer currently needs fresh DI4 frames.
     * Repeated calls are cheap: recovery is single-flight, stale-gated and
     * interval-limited. A rising edge is handled immediately; a sustained demand
     * is also visible to the watchdog so a producer that later stalls is repaired.
     */
    public void setDiLink4FrameDemand(boolean demanded, String reason) {
        if (!USE_DILINK4_AVM_PATH) return;
        String safeReason = (reason == null || reason.trim().isEmpty())
            ? "unspecified" : reason;
        boolean rising = demanded && !dilink4FrameDemanded;
        dilink4FrameDemanded = demanded;
        dilink4FrameDemandReason = safeReason;
        if (demanded) {
            requestDiLink4ProducerRecovery(
                (rising ? "demand-rising:" : "demand-refresh:") + safeReason,
                false);
        }
    }

    /**
     * One-shot recovery request for lifecycle edges such as ACC ON. This does
     * not make the caller a persistent demand owner; it only repairs an already
     * stale persistent handle before the next UI/recording consumer needs it.
     */
    public void requestDiLink4ProducerRecovery(String reason) {
        requestDiLink4ProducerRecovery(reason, true);
    }

    private boolean requestDiLink4ProducerRecovery(
            String reason, boolean forceDemandEdge) {
        if (!USE_DILINK4_AVM_PATH || !running || cameraTornDown
                || cameraObj == null || restartInProgress.get()
                || halRecoveryEscalated || isDiLink4OpenInFlight()
                || dilink4DeferredReopenPending.get()
                || com.overdrive.app.daemon.CameraDaemon
                    .isProcessRestartPending()) {
            return false;
        }
        if (!forceDemandEdge && !dilink4FrameDemanded && !bsLayerVisible) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (!isDiLink4ProducerStale(
                true,
                dilink4LastGenuineFrameArrivalMs,
                lastCameraStartTime,
                now,
                DILINK4_DEMAND_STALE_MS,
                FRAME_STALL_WARMUP_GRACE_MS)) {
            return false;
        }
        long sinceLastAttempt = dilink4LastSoftRecoveryAttemptMs > 0L
            ? Math.max(0L, now - dilink4LastSoftRecoveryAttemptMs)
            : Long.MAX_VALUE;
        if (sinceLastAttempt < DILINK4_SOFT_RECOVERY_MIN_INTERVAL_MS) {
            return false;
        }
        if (!dilink4SoftRecoveryInFlight.compareAndSet(false, true)) {
            return false;
        }

        final Object expectedCamera = cameraObj;
        final int recoveryEpoch = dilink4SoftRecoveryEpoch.incrementAndGet();
        final String safeReason = (reason == null || reason.trim().isEmpty())
            ? "unspecified" : reason;
        Handler handler = glHandler;
        if (handler == null || !handler.post(() ->
                beginDiLink4SoftRecovery(
                    expectedCamera, recoveryEpoch, safeReason, forceDemandEdge))) {
            if (dilink4SoftRecoveryEpoch.get() == recoveryEpoch) {
                dilink4SoftRecoveryInFlight.set(false);
            }
            logger.warn("DiLink 4 soft producer recovery could not be posted"
                + " (reason=" + safeReason + ")");
            return false;
        }
        return true;
    }

    private void beginDiLink4SoftRecovery(
            Object expectedCamera,
            int recoveryEpoch,
            String reason,
            boolean forceDemandEdge) {
        if (!isCurrentDiLink4SoftRecovery(expectedCamera, recoveryEpoch)) return;
        if (com.overdrive.app.daemon.CameraDaemon.isProcessRestartPending()) {
            finishDiLink4SoftRecovery(
                recoveryEpoch, "trip-safe process rebuild already pending");
            return;
        }
        if (!forceDemandEdge && !dilink4FrameDemanded && !bsLayerVisible) {
            finishDiLink4SoftRecovery(recoveryEpoch,
                "demand disappeared before recovery began");
            return;
        }
        long now = System.currentTimeMillis();
        if (!isDiLink4ProducerStale(
                true,
                dilink4LastGenuineFrameArrivalMs,
                lastCameraStartTime,
                now,
                DILINK4_DEMAND_STALE_MS,
                FRAME_STALL_WARMUP_GRACE_MS)) {
            finishDiLink4SoftRecovery(recoveryEpoch,
                "producer resumed before recovery began");
            return;
        }

        dilink4LastSoftRecoveryAttemptMs = now;
        dilink4SoftRecoveryCycles++;
        dilink4FrameDemandReason = reason;
        try {
            BydApaViewpointHelper.reassertIfHeld();
        } catch (Throwable t) {
            logger.warn("DiLink 4 soft recovery viewpoint re-assert failed: "
                + t.getMessage());
        }

        try {
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
            Boolean previewing = queryDiLink4PreviewState(
                avmClass, expectedCamera, "soft-recovery");
            boolean startPreview = Boolean.FALSE.equals(previewing);
            boolean callbackArmed = armPreviewCallbackKick(
                avmClass, cameraSurfaceMode, startPreview, null);
            logger.warn("DiLink 4 producer stale on active demand — callback probe"
                + " armed=" + callbackArmed
                + ", isPreview=" + previewing
                + ", staleMs=" + diLink4GenuineFrameAgeMs(now)
                + ", reason=" + reason);
        } catch (Throwable t) {
            logger.warn("DiLink 4 callback-probe recovery failed to arm: "
                + t.getMessage());
        }

        if (!postDiLink4RecoveryVerification(
                expectedCamera, recoveryEpoch, reason, forceDemandEdge, false)) {
            escalateDiLink4SoftRecovery(
                expectedCamera, recoveryEpoch, reason, forceDemandEdge,
                "callback verification could not be scheduled");
        }
    }

    private boolean postDiLink4RecoveryVerification(
            Object expectedCamera,
            int recoveryEpoch,
            String reason,
            boolean forceDemandEdge,
            boolean surfaceRebound) {
        Handler handler = glHandler;
        return handler != null && handler.postDelayed(() ->
                verifyDiLink4SoftRecovery(
                    expectedCamera,
                    recoveryEpoch,
                    reason,
                    forceDemandEdge,
                    surfaceRebound),
            DILINK4_SOFT_RECOVERY_VERIFY_MS);
    }

    private void verifyDiLink4SoftRecovery(
            Object expectedCamera,
            int recoveryEpoch,
            String reason,
            boolean forceDemandEdge,
            boolean surfaceRebound) {
        if (!isCurrentDiLink4SoftRecovery(expectedCamera, recoveryEpoch)) return;
        if (com.overdrive.app.daemon.CameraDaemon.isProcessRestartPending()) {
            finishDiLink4SoftRecovery(
                recoveryEpoch, "trip-safe process rebuild already pending");
            return;
        }
        long now = System.currentTimeMillis();
        if (!isDiLink4ProducerStale(
                true,
                dilink4LastGenuineFrameArrivalMs,
                lastCameraStartTime,
                now,
                DILINK4_DEMAND_STALE_MS,
                FRAME_STALL_WARMUP_GRACE_MS)) {
            finishDiLink4SoftRecovery(recoveryEpoch,
                "fresh SurfaceTexture frame observed");
            return;
        }
        if (!forceDemandEdge && !dilink4FrameDemanded && !bsLayerVisible) {
            finishDiLink4SoftRecovery(recoveryEpoch,
                "demand disappeared during recovery");
            return;
        }

        boolean byteFrameSeen = previewKickByteSeen;
        disarmPreviewCallbackKick(cameraSurfaceMode, expectedCamera);
        if (!surfaceRebound) {
            logger.warn("DiLink 4 callback probe did not restore texture frames"
                + " (byteFrameSeen=" + byteFrameSeen + ", staleMs="
                + diLink4GenuineFrameAgeMs(now) + ") — rebuilding only the"
                + " SurfaceTexture on the existing AVMCamera");
            if (postDiLink4SurfaceRebind(
                    expectedCamera, recoveryEpoch, reason, forceDemandEdge)) {
                return;
            }
            escalateDiLink4SoftRecovery(
                expectedCamera, recoveryEpoch, reason, forceDemandEdge,
                "same-handle SurfaceTexture rebind could not be scheduled");
            return;
        }

        escalateDiLink4SoftRecovery(
            expectedCamera, recoveryEpoch, reason, forceDemandEdge,
            "callback probe and same-handle SurfaceTexture rebind produced no frame");
    }

    /**
     * Hand stage 1's callback watcher enough time to observe the disarm and
     * exit before stage 2 may install a fresh callback. Without this boundary,
     * the old watcher can win its single-flight cleanup race and disarm the new
     * callback immediately.
     */
    private boolean postDiLink4SurfaceRebind(
            Object expectedCamera,
            int recoveryEpoch,
            String reason,
            boolean forceDemandEdge) {
        Handler handler = glHandler;
        return handler != null && handler.postDelayed(() -> {
            if (!isCurrentDiLink4SoftRecovery(
                    expectedCamera, recoveryEpoch)) {
                return;
            }
            if (com.overdrive.app.daemon.CameraDaemon
                    .isProcessRestartPending()) {
                finishDiLink4SoftRecovery(
                    recoveryEpoch, "trip-safe process rebuild already pending");
                return;
            }
            long now = System.currentTimeMillis();
            if (!isDiLink4ProducerStale(
                    true,
                    dilink4LastGenuineFrameArrivalMs,
                    lastCameraStartTime,
                    now,
                    DILINK4_DEMAND_STALE_MS,
                    FRAME_STALL_WARMUP_GRACE_MS)) {
                finishDiLink4SoftRecovery(
                    recoveryEpoch, "producer resumed before SurfaceTexture rebind");
                return;
            }
            if (!forceDemandEdge && !dilink4FrameDemanded && !bsLayerVisible) {
                finishDiLink4SoftRecovery(
                    recoveryEpoch, "demand disappeared before SurfaceTexture rebind");
                return;
            }
            DiLink4SurfaceRebindResult rebindResult =
                rebindDiLink4SurfaceTexture(
                    expectedCamera,
                    recoveryEpoch,
                    reason,
                    forceDemandEdge);
            if (rebindResult == DiLink4SurfaceRebindResult.WAITING_FOR_CALLBACK) {
                return;
            }
            if (rebindResult == DiLink4SurfaceRebindResult.ATTACHED
                    && postDiLink4RecoveryVerification(
                        expectedCamera,
                        recoveryEpoch,
                        reason,
                        forceDemandEdge,
                        true)) {
                return;
            }
            escalateDiLink4SoftRecovery(
                expectedCamera, recoveryEpoch, reason, forceDemandEdge,
                "same-handle SurfaceTexture rebind failed");
        }, DILINK4_CALLBACK_DISARM_SETTLE_MS);
    }

    /**
     * Rebuild only the BufferQueue consumer while preserving the AVMCamera
     * handle and panorama/viewpoint ownership. This is the non-destructive
     * recovery for "producer alive, texture route dead".
     */
    private DiLink4SurfaceRebindResult rebindDiLink4SurfaceTexture(
            Object expectedCamera,
            int recoveryEpoch,
            String reason,
            boolean forceDemandEdge) {
        if (!USE_DILINK4_AVM_PATH || expectedCamera == null
                || cameraObj != expectedCamera || cameraTextureId == 0) {
            return DiLink4SurfaceRebindResult.FAILED;
        }
        SurfaceTexture retiredSurface = null;
        try {
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
            disarmPreviewCallbackKick(cameraSurfaceMode, expectedCamera);

            // Serialize the final "still stale?" decision with callback
            // acceptance. If a genuine frame won this lock first it already
            // completed the recovery, so leave the now-healthy consumer alone.
            // If we win, retire the old identity/epoch before any HAL detach so
            // a callback queued concurrently can no longer certify this stage.
            synchronized (diLink4SurfaceTextureStateLock) {
                if (!isCurrentDiLink4SoftRecovery(
                        expectedCamera, recoveryEpoch)) {
                    return DiLink4SurfaceRebindResult.FAILED;
                }
                retiredSurface = retireCameraSurfaceTextureConsumer();
            }

            detachSurfaceTextureFromCamera(expectedCamera, retiredSurface);
            synchronized (cameraTextureLock) {
                if (retiredSurface != null) {
                    try { retiredSurface.setOnFrameAvailableListener(null); }
                    catch (Throwable ignored) {}
                    try { retiredSurface.release(); } catch (Throwable ignored) {}
                    retiredSurface = null;
                }
                createCameraSurfaceTexture();
            }
            final SurfaceTexture reboundSurface = cameraSurfaceTexture;
            if (reboundSurface == null || cameraObj != expectedCamera) {
                throw new IllegalStateException(
                    "SurfaceTexture recreation did not produce a live consumer");
            }

            Boolean previewing = queryDiLink4PreviewState(
                avmClass, expectedCamera, "surface-rebind");
            // Pre-32 ordering when preview is stopped: callback first,
            // then startPreview, then enablePreviewCallback, with texture attach
            // following ONLY after the first valid byte frame. The former
            // recovery path attached immediately after enablePreviewCallback,
            // which reintroduced the exact ordering this sequence avoids.
            if (Boolean.FALSE.equals(previewing)) {
                final AtomicBoolean callbackAttachStarted =
                    new AtomicBoolean(false);
                boolean callbackArmed = armPreviewCallbackKick(
                    avmClass, cameraSurfaceMode, true, () -> {
                        Handler handler = glHandler;
                        boolean posted = handler != null && handler.post(() -> {
                            if (!isCurrentDiLink4SoftRecovery(
                                    expectedCamera, recoveryEpoch)
                                    || cameraSurfaceTexture != reboundSurface) {
                                return;
                            }
                            callbackAttachStarted.set(true);
                            if (!forceDemandEdge
                                    && !dilink4FrameDemanded
                                    && !bsLayerVisible) {
                                finishDiLink4SoftRecovery(
                                    recoveryEpoch,
                                    "demand disappeared before callback-first"
                                        + " SurfaceTexture attach");
                                return;
                            }
                            try {
                                bindDiLink4ReboundSurfaceTexture(
                                    avmClass,
                                    expectedCamera,
                                    reboundSurface,
                                    previewing,
                                    reason);
                                if (!postDiLink4RecoveryVerification(
                                        expectedCamera,
                                        recoveryEpoch,
                                        reason,
                                        forceDemandEdge,
                                        true)) {
                                    escalateDiLink4SoftRecovery(
                                        expectedCamera,
                                        recoveryEpoch,
                                        reason,
                                        forceDemandEdge,
                                        "callback-first SurfaceTexture"
                                            + " verification could not be scheduled");
                                }
                            } catch (Throwable t) {
                                logger.warn("DiLink 4 callback-first SurfaceTexture"
                                    + " attach failed: " + t.getMessage());
                                escalateDiLink4SoftRecovery(
                                    expectedCamera,
                                    recoveryEpoch,
                                    reason,
                                    forceDemandEdge,
                                    "callback-first SurfaceTexture attach failed");
                            }
                        });
                        if (!posted) {
                            logger.warn("DiLink 4 callback-first SurfaceTexture"
                                + " attach was rejected by the GL handler");
                        }
                        disableDi4PreviewCallbackAfterFirstFrame(
                            cameraSurfaceMode, expectedCamera);
                    });
                if (!callbackArmed) {
                    return DiLink4SurfaceRebindResult.FAILED;
                }

                Handler handler = glHandler;
                boolean timeoutPosted = handler != null && handler.postDelayed(() -> {
                    if (callbackAttachStarted.get()
                            || !isCurrentDiLink4SoftRecovery(
                                expectedCamera, recoveryEpoch)) {
                        return;
                    }
                    disarmPreviewCallbackKick(
                        cameraSurfaceMode, expectedCamera);
                    escalateDiLink4SoftRecovery(
                        expectedCamera,
                        recoveryEpoch,
                        reason,
                        forceDemandEdge,
                        "same-handle rebind produced no valid byte frame");
                }, DILINK4_REBIND_CALLBACK_TIMEOUT_MS);
                if (!timeoutPosted) {
                    disarmPreviewCallbackKick(
                        cameraSurfaceMode, expectedCamera);
                    return DiLink4SurfaceRebindResult.FAILED;
                }
                logger.info("DiLink 4 SurfaceTexture rebind waiting for first"
                    + " valid byte frame before addTexture/setTexture"
                    + " (reason=" + reason + ")");
                return DiLink4SurfaceRebindResult.WAITING_FOR_CALLBACK;
            }

            bindDiLink4ReboundSurfaceTexture(
                avmClass,
                expectedCamera,
                reboundSurface,
                previewing,
                reason);
            // isPreview=true (or unavailable): never double-start. A bounded
            // callback probe is safe and tells us whether only the texture
            // route was broken.
            armPreviewCallbackKick(
                avmClass, cameraSurfaceMode, false, null);
            return DiLink4SurfaceRebindResult.ATTACHED;
        } catch (Throwable t) {
            if (retiredSurface != null) {
                try { retiredSurface.setOnFrameAvailableListener(null); }
                catch (Throwable ignored) {}
                try { retiredSurface.release(); } catch (Throwable ignored) {}
            }
            logger.warn("DiLink 4 same-handle SurfaceTexture rebind failed: "
                + t.getMessage());
            return DiLink4SurfaceRebindResult.FAILED;
        }
    }

    /** Attach a recreated SurfaceTexture without issuing startPreview. */
    private void bindDiLink4ReboundSurfaceTexture(
            Class<?> avmClass,
            Object expectedCamera,
            SurfaceTexture expectedSurface,
            Boolean previewing,
            String reason) throws Exception {
        if (cameraObj != expectedCamera
                || cameraSurfaceTexture != expectedSurface) {
            throw new IllegalStateException(
                "stale camera/SurfaceTexture during same-handle rebind");
        }
        Method mAddTexture = avmClass.getDeclaredMethod(
            "addTexture", SurfaceTexture.class, int.class);
        mAddTexture.setAccessible(true);
        mAddTexture.invoke(
            expectedCamera, expectedSurface, cameraSurfaceMode);

        Method mSetTexture = avmClass.getDeclaredMethod(
            "setTexture", SurfaceTexture.class, int.class);
        mSetTexture.setAccessible(true);
        mSetTexture.invoke(
            expectedCamera, expectedSurface, cameraSurfaceMode);

        cameraTornDown = false;
        firstFrameDimsLogged = false;
        logger.info("DiLink 4 SurfaceTexture rebound on existing AVMCamera"
            + " (isPreview=" + previewing + ", reason=" + reason + ")");
    }

    private Boolean queryDiLink4PreviewState(
            Class<?> avmClass, Object camera, String context) {
        try {
            Method mIsPreview = avmClass.getDeclaredMethod("isPreview");
            mIsPreview.setAccessible(true);
            Object value = mIsPreview.invoke(camera);
            Boolean result = value instanceof Boolean ? (Boolean) value : null;
            logger.info("DiLink 4 isPreview(" + context + ")=" + result);
            return result;
        } catch (NoSuchMethodException e) {
            logger.info("DiLink 4 isPreview unavailable during " + context);
            return null;
        } catch (Throwable t) {
            logger.warn("DiLink 4 isPreview failed during " + context + ": "
                + t.getMessage());
            return null;
        }
    }

    private boolean isCurrentDiLink4SoftRecovery(
            Object expectedCamera, int recoveryEpoch) {
        return USE_DILINK4_AVM_PATH
            && dilink4SoftRecoveryInFlight.get()
            && dilink4SoftRecoveryEpoch.get() == recoveryEpoch
            && running
            && !cameraTornDown
            && cameraObj == expectedCamera
            && !restartInProgress.get()
            && !halRecoveryEscalated;
    }

    private long diLink4GenuineFrameAgeMs(long now) {
        long anchor = dilink4LastGenuineFrameArrivalMs > 0L
            ? dilink4LastGenuineFrameArrivalMs : lastCameraStartTime;
        return anchor > 0L ? Math.max(0L, now - anchor) : Long.MAX_VALUE;
    }

    private void finishDiLink4SoftRecovery(
            int recoveryEpoch, String outcome) {
        if (dilink4SoftRecoveryEpoch.get() != recoveryEpoch) return;
        dilink4SoftRecoveryInFlight.set(false);
        dilink4SoftRecoveryEpoch.incrementAndGet();
        logger.info("DiLink 4 soft producer recovery finished: " + outcome);
    }

    private void escalateDiLink4SoftRecovery(
            Object expectedCamera,
            int recoveryEpoch,
            String reason,
            boolean forceDemandEdge,
            String failure) {
        if (!isCurrentDiLink4SoftRecovery(expectedCamera, recoveryEpoch)) return;
        dilink4SoftRecoveryInFlight.set(false);
        dilink4SoftRecoveryEpoch.incrementAndGet();
        if (!forceDemandEdge && !dilink4FrameDemanded && !bsLayerVisible) {
            logger.info("DiLink 4 soft recovery exhausted after demand ended: "
                + failure);
            return;
        }
        requestDiLink4CameraReopen(
            expectedCamera, reason, failure, forceDemandEdge);
    }

    private boolean requestDiLink4CameraReopen(
            Object expectedCamera,
            String reason,
            String failure,
            boolean forceDemandEdge) {
        if (!USE_DILINK4_AVM_PATH
                || expectedCamera == null
                || cameraObj != expectedCamera
                || cameraTornDown
                || !running
                || restartInProgress.get()
                || halRecoveryEscalated
                || isDiLink4OpenInFlight()
                || com.overdrive.app.daemon.CameraDaemon
                    .isProcessRestartPending()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long lastAttemptAnchor = Math.max(
            lastCameraStartTime, dilink4LastStallRestartMs);
        long sinceLastAttempt = lastAttemptAnchor > 0L
            ? Math.max(0L, now - lastAttemptAnchor) : Long.MAX_VALUE;
        if (sinceLastAttempt < DILINK4_ERROR_RESTART_MIN_INTERVAL_MS) {
            long delay = DILINK4_ERROR_RESTART_MIN_INTERVAL_MS - sinceLastAttempt;
            Handler handler = glHandler;
            if (handler == null
                    || !dilink4DeferredReopenPending.compareAndSet(
                        false, true)) {
                return false;
            }
            final int deferredEpoch =
                dilink4DeferredReopenEpoch.incrementAndGet();
            boolean posted = handler.postDelayed(() -> {
                    if (dilink4DeferredReopenEpoch.get() != deferredEpoch) {
                        return;
                    }
                    dilink4DeferredReopenPending.set(false);
                    if (cameraObj != expectedCamera
                            || cameraTornDown
                            || !running
                            || restartInProgress.get()
                            || halRecoveryEscalated
                            || com.overdrive.app.daemon.CameraDaemon
                                .isProcessRestartPending()) {
                        return;
                    }
                    if ((!forceDemandEdge && !dilink4FrameDemanded
                            && !bsLayerVisible)
                            || !isDiLink4ProducerStale(
                                true,
                                dilink4LastGenuineFrameArrivalMs,
                                lastCameraStartTime,
                                System.currentTimeMillis(),
                                DILINK4_DEMAND_STALE_MS,
                                FRAME_STALL_WARMUP_GRACE_MS)) {
                        return;
                    }
                    requestDiLink4CameraReopen(
                        expectedCamera,
                        reason,
                        failure + " (deferred by reopen floor)",
                        forceDemandEdge);
                }, delay);
            if (!posted) {
                if (dilink4DeferredReopenEpoch.get() == deferredEpoch) {
                    dilink4DeferredReopenPending.set(false);
                    dilink4DeferredReopenEpoch.incrementAndGet();
                }
                logger.warn("DiLink 4 full reopen could not be scheduled on"
                    + " the GL handler");
                return false;
            }
            logger.info("DiLink 4 full reopen deferred " + delay
                + "ms by the reopen floor after soft-recovery failure");
            return false;
        }
        if (dilink4StallRecoveryExhausted
                || dilink4StallRestartAttempts
                    >= DILINK4_STALL_RESTART_MAX_ATTEMPTS) {
            dilink4StallRecoveryExhausted = true;
            logger.error("DiLink 4 producer recovery exhausted "
                + dilink4StallRestartAttempts + " camera reopens — requesting"
                + " full pipeline/process recovery (reason=" + reason + ")");
            requestDiLink4HalRecoveryEscalation();
            return false;
        }

        dilink4DeferredReopenPending.set(false);
        dilink4DeferredReopenEpoch.incrementAndGet();
        dilink4StallRestartAttempts++;
        dilink4LastStallRestartMs = now;
        logger.warn("DiLink 4 soft recovery failed (" + failure
            + ") — full camera reopen attempt "
            + dilink4StallRestartAttempts + "/"
            + DILINK4_STALL_RESTART_MAX_ATTEMPTS
            + " (reason=" + reason + ")");
        restartCameraAfterError();
        return true;
    }

    private void requestDiLink4HalRecoveryEscalation() {
        if (halRecoveryEscalated || isDiLink4OpenInFlight()
                || com.overdrive.app.daemon.CameraDaemon
                    .isProcessRestartPending()) {
            return;
        }
        if (skipDiLink4ParkedRebuild("producer recovery exhausted")) {
            // A held, silent handle would otherwise sit dead until ACC on.
            if (cameraObj != null) {
                scheduleDiLink4Release("parked stall with the reopen budget spent",
                    System.currentTimeMillis(), 0L, true);
            }
            return;
        }
        if (yieldListener == null) {
            logger.error("DiLink 4 full-rebuild listener unavailable —"
                + " requesting trip-safe process recovery");
            com.overdrive.app.daemon.CameraDaemon
                .requestProcessRestartPreservingTrip(
                    "DI4 producer recovery exhausted without pipeline listener");
            return;
        }
        halRecoveryEscalated = true;
        try {
            yieldListener.onHalRecoveryNeeded();
        } catch (Throwable t) {
            halRecoveryEscalated = false;
            logger.warn("DiLink 4 full recovery dispatch failed: "
                + t.getMessage() + " — requesting trip-safe process recovery");
            com.overdrive.app.daemon.CameraDaemon
                .requestProcessRestartPreservingTrip(
                    "DI4 producer full-rebuild dispatch failed");
        }
    }

    /** A parked byd_apa producer is powered down: a process rebuild cannot revive
     *  it and only churns the HAL. Logged once until ACC on or proven recovery. */
    private boolean skipDiLink4ParkedRebuild(String why) {
        if (!USE_DILINK4_AVM_PATH
                || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            return false;
        }
        if (!dilink4ParkedRebuildSkipLogged) {
            dilink4ParkedRebuildSkipLogged = true;
            logger.warn("DiLink 4 parked: skipping full rebuild (" + why
                + ") — reopening on the parked cadence until ACC on");
        }
        return true;
    }

    /** HAL event thread → GL thread. Captures the handle the event belongs to. */
    private void scheduleDiLink4DeathRelease(int eventType, long eventAtMs) {
        scheduleDiLink4Release("HAL event=" + eventType, eventAtMs,
            DILINK4_DEATH_VERIFY_MS, false);
    }

    /** {@code afterWarmup}: only release a handle older than the first-frame
     *  grace, so a stall verdict reached mid-reopen cannot close the new one. */
    private void scheduleDiLink4Release(
            String why, long sinceMs, long delayMs, boolean afterWarmup) {
        final Object camera = cameraObj;
        Handler handler = glHandler;
        if (camera == null || handler == null) return;
        if (!handler.postDelayed(
                () -> releaseDiLink4Camera(camera, why, sinceMs, afterWarmup),
                delayMs)) {
            logger.warn("DiLink 4 camera release could not be scheduled (" + why + ")");
        }
    }

    private boolean stillOwnsDiLink4Release(Object camera) {
        return running && !cameraTornDown && cameraObj == camera
            && !halRecoveryEscalated
            && !isDiLink4OpenInFlight()
            && !CameraDaemon.isProcessRestartPending();
    }

    /** GL thread. Keeps the camera if a genuine frame arrived after {@code sinceMs};
     *  re-checks ownership after each blocking step because stop() takes no lock. */
    private void releaseDiLink4Camera(
            Object camera, String why, long sinceMs, boolean afterWarmup) {
        if (!afterWarmup && cameraObj == camera && isDiLink4OpenInFlight()) {
            dilink4PendingDeathAtMs = sinceMs;
            dilink4PendingDeathWhy = why;
            return;
        }
        if (!stillOwnsDiLink4Release(camera)) return;
        if (afterWarmup && System.currentTimeMillis() - lastCameraStartTime
                < FRAME_STALL_WARMUP_GRACE_MS) {
            return;
        }
        if (dilink4LastGenuineFrameArrivalMs > sinceMs) {
            logger.info("DiLink 4 " + why + ": frames still arriving — keeping the camera");
            return;
        }
        if (!restartInProgress.compareAndSet(false, true)) {
            logger.info("DiLink 4 " + why + ": a restart already owns the close/reopen");
            return;
        }
        boolean released = false;
        try {
            finalizeRecordingBeforeDiLink4Release();
            if (!stillOwnsDiLink4Release(camera)) {
                logger.info("DiLink 4 release (" + why + ") abandoned: teardown took over");
                return;
            }
            if (dilink4LastGenuineFrameArrivalMs > sinceMs) {
                logger.info("DiLink 4 " + why + ": frames resumed during the finalize"
                    + " — keeping the camera and resuming");
                resumeAfterAbandonedDiLink4Release();
                return;
            }
            final HardwareEventRecorderGpu releaseStreamEnc = streamEncoder;
            if (streamScaler != null || streamEncoder != null) {
                clearStreamingComponents();
            }
            if (!stopEncoderDrainersBeforeCameraClose(
                    "dilink4-release", encoder, releaseStreamEnc)) {
                return;
            }
            if (!stillOwnsDiLink4Release(camera)) {
                logger.info("DiLink 4 release (" + why + ") abandoned: teardown took over");
                return;
            }
            closeCameraForPath(camera);
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
                cameraCoordinator.notifyPosCloseCamera();
            }
            dilink4SoftRecoveryEpoch.incrementAndGet();
            dilink4SoftRecoveryInFlight.set(false);
            dilink4DeferredReopenEpoch.incrementAndGet();
            dilink4DeferredReopenPending.set(false);
            // A death breaks the run: proof of recovery must be re-earned unbroken.
            dilink4RecoveryProofFrames = 0;
            dilink4RecoveryProofSinceMs = 0L;
            if (encoder != null && running && !cameraTornDown) {
                encoder.restartDrainerAfterCameraClose();
            }
            dilink4ReleasedAfterDeath = true;
            released = true;
            logger.warn("DiLink 4 camera released (" + why + ", "
                + (System.currentTimeMillis() - sinceMs) + "ms after detection)");
        } finally {
            restartInProgress.set(false);
        }
        if (released) {
            scheduleDiLink4ReopenAfterDeath(why);
        }
    }

    /** The clip was finalized but the camera is staying: resume as after a restart. */
    private void resumeAfterAbandonedDiLink4Release() {
        CameraYieldListener listener = yieldListener;
        if (listener == null) return;
        lastGlThreadHeartbeat = System.currentTimeMillis();
        try {
            listener.onPostReacquire();
        } catch (Exception e) {
            logger.warn("DiLink 4 release: post-reacquire error: " + e.getMessage());
        }
    }

    /** Same bounded finalize as the restart path, polled finer so the release
     *  lands before other AVM clients reopen. */
    private void finalizeRecordingBeforeDiLink4Release() {
        final CameraYieldListener listener = yieldListener;
        if (listener == null) return;
        final AtomicBoolean done = new AtomicBoolean(false);
        Thread finalizeThread = new Thread(() -> {
            try {
                listener.onPreYield();
            } catch (Exception e) {
                logger.warn("DiLink 4 release: pre-yield error: " + e.getMessage());
            } finally {
                done.set(true);
            }
        }, "PreYield-Di4Release");
        finalizeThread.setDaemon(true);
        finalizeThread.start();
        long start = System.currentTimeMillis();
        while (!done.get() && System.currentTimeMillis() - start < 8_000L) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            lastGlThreadHeartbeat = System.currentTimeMillis();
        }
        if (!done.get()) {
            logger.error("DiLink 4 release: recording finalize timed out after"
                + " 8000ms — releasing the camera anyway");
        }
    }

    private void scheduleDiLink4ReopenAfterDeath(String reason) {
        Handler handler = glHandler;
        if (handler == null) return;
        boolean accOn = com.overdrive.app.monitor.AccMonitor.isAccOn();
        // Quick retries only for a handle that streamed: a zero-frame one keeps
        // the 60 s spacing, or the zero-frame rebuild would recur every ~40 s.
        boolean streamedSinceOpen = frameCounter != frameCounterAtOpen;
        long delay;
        if (accOn && streamedSinceOpen) {
            // Then the 60 s floor, so a HAL that keeps dying cannot split the
            // recording every few seconds.
            delay = dilink4DeathReopenStreak < DILINK4_DEATH_REOPEN_BACKOFF_MS.length
                ? DILINK4_DEATH_REOPEN_BACKOFF_MS[dilink4DeathReopenStreak]
                : DILINK4_ERROR_RESTART_MIN_INTERVAL_MS;
        } else if (!accOn
                && dilink4StallRestartAttempts >= DILINK4_STALL_RESTART_MAX_ATTEMPTS) {
            delay = DILINK4_PARKED_RETRY_MS;
        } else {
            long anchor = Math.max(lastCameraStartTime, dilink4LastStallRestartMs);
            long since = anchor > 0L
                ? Math.max(0L, System.currentTimeMillis() - anchor) : Long.MAX_VALUE;
            delay = since >= DILINK4_ERROR_RESTART_MIN_INTERVAL_MS
                ? DILINK4_DEATH_REOPEN_BACKOFF_MS[0]
                : Math.max(DILINK4_DEATH_REOPEN_BACKOFF_MS[0],
                    DILINK4_ERROR_RESTART_MIN_INTERVAL_MS - since);
        }
        final int epoch = dilink4DeathEpoch.incrementAndGet();
        if (!handler.postDelayed(() -> reopenDiLink4AfterDeath(epoch), delay)) {
            logger.warn("DiLink 4 reopen could not be scheduled (" + reason + ")");
            return;
        }
        logger.info("DiLink 4 reopen scheduled in " + delay + "ms ("
            + (accOn ? "ACC on" : "parked") + ", " + reason + ")");
    }

    private void reopenDiLink4AfterDeath(int epoch) {
        if (dilink4DeathEpoch.get() != epoch) return;
        // A timed-out open is owned by its watch. cameraTornDown is not checked:
        // every restart sets it before the open and only an attach clears it.
        if (!running || !dilink4ReleasedAfterDeath
                || cameraObj != null || restartInProgress.get()
                || halRecoveryEscalated || isDiLink4OpenInFlight()
                || CameraDaemon.isProcessRestartPending()) {
            return;
        }
        if (com.overdrive.app.monitor.AccMonitor.isAccOn()) {
            dilink4DeathReopenStreak++;
        }
        reopenReleasedDiLink4Camera("after release");
    }

    /** GL thread. A failure is re-armed by restartCameraAfterError's DI4 tail. */
    private void reopenReleasedDiLink4Camera(String why) {
        dilink4ReleasedAfterDeath = false;
        dilink4StallRestartAttempts++;
        dilink4LastStallRestartMs = System.currentTimeMillis();
        logger.warn("DiLink 4 reopening camera " + why + " (attempt "
            + dilink4StallRestartAttempts + ")");
        restartCameraAfterError();
    }

    private boolean isDiLink4OpenInFlight() {
        Thread lateOpen = dilink4LateOpenThread;
        return lateOpen != null && lateOpen.isAlive();
    }

    /** DI4 tail of every restart. A timed-out open gets one watch; a restart that
     *  left no handle stays released and retries instead of waiting for the next
     *  lifecycle edge. */
    private void afterDiLink4Restart() {
        if (!USE_DILINK4_AVM_PATH) return;
        // Still recorded means the open outlived the wait, even if the worker
        // finished a moment ago; the watch completes or fails it either way.
        Thread lateOpen = dilink4LateOpenThread;
        if (lateOpen != null) {
            watchDiLink4LateOpen(lateOpen);
            return;
        }
        if (!running
                || cameraObj != null || restartInProgress.get()
                || halRecoveryEscalated
                || CameraDaemon.isProcessRestartPending()) {
            return;
        }
        dilink4ReleasedAfterDeath = true;
        scheduleDiLink4ReopenAfterDeath("reopen left no camera");
    }

    /** A constructor-fallback open that returned false, or an attach that threw,
     *  leaves cameraObj set. Close it (drainers first) so the tail retries.
     *  @return false when a wedged drainer made a process restart the owner */
    private boolean closeHalfOpenDiLink4Camera() {
        if (!USE_DILINK4_AVM_PATH || isDiLink4OpenInFlight()) return false;
        Object halfOpen = cameraObj;
        if (halfOpen == null) return true;
        final HardwareEventRecorderGpu halfOpenStreamEnc = streamEncoder;
        if (streamScaler != null || streamEncoder != null) {
            clearStreamingComponents();
        }
        if (!stopEncoderDrainersBeforeCameraClose(
                "dilink4-half-open", encoder, halfOpenStreamEnc)) {
            return false;
        }
        cameraObj = null;
        try {
            closeCameraForPath(halfOpen);
        } catch (Throwable t) {
            logger.warn("DiLink 4 half-open camera close failed: " + t.getMessage());
        }
        if (cameraCoordinator != null) {
            cameraCoordinator.resetEventCallbackState();
        }
        logger.warn("DiLink 4 restart failed with a half-open handle — closed it");
        return true;
    }

    private void watchDiLink4LateOpen(Thread watched) {
        Handler handler = glHandler;
        if (watched == null) return;
        if (handler == null
                || !handler.postDelayed(() -> checkDiLink4LateOpen(watched), 1_000L)) {
            logger.warn("DiLink 4 late-open watch could not be scheduled");
        }
    }

    /** GL thread. A late success keeps its handle; a late failure goes back to the
     *  rate-limited reopen schedule. Past the deadline only process death helps. */
    private void checkDiLink4LateOpen(Thread watched) {
        if (!running) return;
        Thread lateOpen = dilink4LateOpenThread;
        if (lateOpen == null || lateOpen != watched) return;
        if (lateOpen.isAlive()) {
            long blockedMs = System.currentTimeMillis() - dilink4LateOpenSinceMs;
            if (blockedMs >= DILINK4_LATE_OPEN_DEADLINE_MS) {
                logger.error("DiLink 4 camera open still blocked after " + blockedMs
                    + "ms — requesting the bounded camera-release restart");
                CameraDaemon.requestUrgentCameraReleaseRestart(
                    "DiLink 4 AVMCamera open blocked for " + blockedMs + "ms");
                return;
            }
            watchDiLink4LateOpen(lateOpen);
            return;
        }
        dilink4LateOpenThread = null;
        AtomicBoolean succeeded = dilink4LateOpenSucceeded;
        dilink4LateOpenSucceeded = null;
        if (restartInProgress.get() || halRecoveryEscalated
                || CameraDaemon.isProcessRestartPending()) {
            return;
        }
        long pendingDeathAt = dilink4PendingDeathAtMs;
        String pendingDeathWhy = dilink4PendingDeathWhy;
        dilink4PendingDeathAtMs = 0L;
        if (succeeded != null && succeeded.get() && cameraObj != null) {
            if (pendingDeathAt > 0L) {
                // Declared dead mid-open: release unless frames came after the event.
                releaseDiLink4Camera(cameraObj,
                    pendingDeathWhy != null ? pendingDeathWhy : "HAL death during open",
                    pendingDeathAt, false);
                if (cameraObj == null) return;
            }
            // The release may have yielded to a teardown after its finalize.
            if (!stillOwnsDiLink4Release(cameraObj)) return;
            completeDiLink4LateOpen();
            return;
        }
        logger.warn("DiLink 4 timed-out camera open finished without a usable handle");
        if (!closeHalfOpenDiLink4Camera()) return;
        dilink4ReleasedAfterDeath = true;
        scheduleDiLink4ReopenAfterDeath("timed-out open failed");
    }

    /** GL thread. Runs the post-open steps restartCameraAfterError skipped when its
     *  2 s wait gave up on a worker that went on to open successfully. */
    private void completeDiLink4LateOpen() {
        lastFrameTime = firstFrameReceived ? System.currentTimeMillis() : 0;
        if (encoder != null) {
            encoder.restartDrainerAfterCameraClose();
        }
        if (cameraCoordinator != null && cameraObj != null) {
            cameraCoordinator.setupEventCallback(cameraObj);
        }
        lastGlThreadHeartbeat = System.currentTimeMillis();
        if (yieldListener != null) {
            try {
                yieldListener.onPostReacquire();
            } catch (Exception e) {
                logger.warn("DiLink 4 late open: post-reacquire error: " + e.getMessage());
            }
        }
        logger.info("DiLink 4 timed-out camera open finished — kept the handle and"
            + " applied the post-open steps");
    }

    /**
     * DiLink 4 ACC-on edge. The power state changed, so a released handle, or one
     * whose soft recovery already failed, reopens now instead of waiting out the
     * parked floor. Anything else takes the ordinary producer check.
     */
    public void onDiLink4AccOn() {
        if (!USE_DILINK4_AVM_PATH) return;
        Handler handler = glHandler;
        if (handler == null) return;
        handler.post(() -> {
            if (!running || restartInProgress.get()
                    || halRecoveryEscalated
                    || CameraDaemon.isProcessRestartPending()) {
                return;
            }
            // New power state, fresh budget: parked exhaustion and the parked
            // zero-frame count no longer reach a rebuild, so nothing else would
            // reset them before the drive.
            dilink4DeathReopenStreak = 0;
            dilink4ParkedRebuildSkipLogged = false;
            dilink4StallRestartAttempts = 0;
            dilink4StallRecoveryExhausted = false;
            consecutiveZeroFrameRestarts = 0;
            if (isDiLink4OpenInFlight()) return;
            boolean released = dilink4ReleasedAfterDeath && cameraObj == null;
            boolean softRecoveryFailed = cameraObj != null
                && dilink4DeferredReopenPending.get();
            if (!released && !softRecoveryFailed) {
                requestDiLink4ProducerRecovery("acc-on persistent handle");
                return;
            }
            dilink4DeathEpoch.incrementAndGet();
            dilink4DeferredReopenEpoch.incrementAndGet();
            dilink4DeferredReopenPending.set(false);
            if (released) {
                reopenReleasedDiLink4Camera("on ACC on (was released)");
            } else {
                dilink4StallRestartAttempts++;
                dilink4LastStallRestartMs = System.currentTimeMillis();
                logger.warn("DiLink 4 ACC on: soft recovery already failed —"
                    + " reopening now instead of waiting for the reopen floor");
                restartCameraAfterError();
            }
        });
    }

    private volatile boolean previewKickFirstByteLogged = false;

    /** The AVMCamera instance the byte-callback kick was armed ON. Disarm must
     *  target this object, not a fresh read of {@code cameraObj}, so a watcher
     *  that outlives a camera swap cannot tear down the new camera's kick or call
     *  {@code disablePreviewCallback} on an instance we never enabled. */
    private volatile Object previewKickArmedCamera = null;

    /** Set by any byte callback for the bounded post-attach fallback watcher.
     *  Callback-driven Di4 attachment separately requires valid dimensions and
     *  payload size before it schedules the texture. */
    private volatile boolean previewKickByteSeen = false;

    /** True producer dims as reported by the HAL's own byte callback, or -1.
     *  This is the ONLY place on the dilink4 path where the real emitted size
     *  becomes knowable when BmmCameraInfo is empty — the transform matrix
     *  cannot reveal it (see logFirstFrameDims). Diagnostic today; exposed so
     *  the geometry mismatch can be surfaced rather than silently assumed. */
    private volatile int halBytePathWidth = -1;
    private volatile int halBytePathHeight = -1;
    public int getHalBytePathWidth() { return halBytePathWidth; }
    public int getHalBytePathHeight() { return halBytePathHeight; }

    /** Dump the four AVM-related SystemProperties. INFORMATIONAL ONLY — see the
     *  call site in {@link #attachSurfaceTextureToCamera} for why the old
     *  "empty props ⇒ HAL paints a red banner" reading is false. */
    private void logAvmCalibrationProps() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            String autostudy = safeGetProp(get, "persist.vendor.camera.autostudy.avm");
            String camInfo   = safeGetProp(get, "vehicle.config.camInfo.avm");
            String camSort   = safeGetProp(get, "vehicle.config.cam_sort");
            String panoCam   = safeGetProp(get, "vehicle.config.pano_cam");
            String panoLCam  = safeGetProp(get, "vehicle.config.pano_l_cam");
            logger.info("AVM calibration props:"
                + " autostudy.avm=" + describeProp(autostudy)
                + " camInfo.avm=" + describeProp(camInfo)
                + " cam_sort=" + describeProp(camSort)
                + " pano_cam=" + describeProp(panoCam)
                + " pano_l_cam=" + describeProp(panoLCam));

            if (isBlank(autostudy) && isBlank(camInfo)) {
                // INFORMATIONAL ONLY — do not read a red banner into this.
                //
                // The old text here asserted "the HAL will paint a red banner
                // into the producer surface", and that assertion was then quoted
                // back as if it were field evidence. It is NOT true: the OEM app
                // (decompiled OEM dashcam) reads these same props purely for
                // telemetry upload, never writes them, and on byd_apa boards
                // bypasses their only functional consumer entirely
                // (AVMCameraLensFacing.java:78-86 hardcodes lens indices under
                // `if (d.c()) return;`). Empty autostudy.avm is a fully supported
                // configuration in an app that works on these cars.
                //
                // What IS true: nothing in user-space can write
                // persist.vendor.camera.autostudy.avm (SELinux denies app/shell
                // writes, verified rc=1 in the field), so this is a read-only
                // observation about dealer provisioning — useful for correlating
                // per-unit behaviour, and nothing more.
                logger.info("AVM autostudy/camInfo properties are EMPTY on this "
                    + "vehicle (dealer AVM calibration not provisioned). "
                    + "Informational: the OEM app runs fine in this state, so do "
                    + "NOT treat this as the cause of a red or frozen tile.");
            }
        } catch (Throwable t) {
            logger.warn("AVM calibration prop probe failed: " + t.getMessage());
        }
    }

    private static String safeGetProp(java.lang.reflect.Method get, String name) {
        try {
            Object v = get.invoke(null, name);
            return v instanceof String ? (String) v : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String describeProp(String v) {
        if (v == null || v.isEmpty()) return "(empty)";
        // Truncate ridiculously long values (autostudy can be hundreds of bytes
        // of binary-as-hex). Just show length + first 40 chars so logs stay
        // legible while still preserving "is it actually populated?".
        if (v.length() <= 60) return "'" + v + "'";
        return "len=" + v.length() + " head='" + v.substring(0, 40) + "…'";
    }

    private static boolean isBlank(String v) {
        return v == null || v.isEmpty();
    }

    // Cached reflection handles for AVMCamera.rmTexture, resolved once.
    // Camera detach is not a hot path, but caching avoids a repeated
    // Class.forName + getDeclaredMethod on every close/reopen cycle.
    // sRmTextureMethod stays null on older HAL builds that lack rmTexture
    // (the NoSuchMethodException path) — close() handles teardown there.
    private static Class<?> sAvmCameraClass;
    private static Method sRmTextureMethod;
    private static boolean sReflectionInitialized = false;

    private static synchronized void ensureReflectionCache() {
        if (sReflectionInitialized) return;
        try {
            sAvmCameraClass = Class.forName("android.hardware.AVMCamera");
            try {
                sRmTextureMethod = sAvmCameraClass.getDeclaredMethod(
                    "rmTexture", SurfaceTexture.class, int.class);
                sRmTextureMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
                // older HAL builds without rmTexture — close() handles it
            }
        } catch (Throwable t) {
            logger.warn("Failed to initialize AVMCamera reflection cache: " + t.getMessage());
        }
        sReflectionInitialized = true;
    }

    /** Detach the SurfaceTexture from the camera before close.
     *  Mirrors oem gl.C5920a.m26746l: rmTexture(st, previewIndex).
     *  Quiet on errors — close() right after is the canonical teardown. */
    private void detachSurfaceTextureFromCamera(Object cam) {
        detachSurfaceTextureFromCamera(cam, cameraSurfaceTexture);
    }

    /** Explicit-consumer variant used after callback identity was retired. */
    private void detachSurfaceTextureFromCamera(
            Object cam, SurfaceTexture st) {
        if (cam == null || st == null) return;
        try {
            ensureReflectionCache();
            if (sRmTextureMethod != null) {
                sRmTextureMethod.invoke(cam, st, cameraSurfaceMode);
            }
            // else: older HAL builds without rmTexture — close() handles it
        } catch (Throwable t) {
            logger.warn("rmTexture failed: " + t.getMessage());
        }
    }

    /** Close the camera in the order oem uses (gl.C5920a.m26747m:318-356):
     *    1. BYDApaHelper unregister + vp reset       (oem line 325)
     *    2. rmTexture(SurfaceTexture, previewIndex)  (oem line 328)
     *    3. setPreviewCallback(null)                 (oem line 333)
     *    4. setEventCallback(null)                   (oem line 339)
     *    5. stopPreview                              (oem line 342)
     *    6. close                                    (oem line 345)
     *  BydCameraCoordinator.closeCamera bundles steps 5+6 (and a redundant
     *  disablePreviewCallback before stopPreview, which is benign).
     *
     *  Legacy path: only steps 5+6 — BydCameraCoordinator.closeCamera. */
    private boolean closeCameraForPath(Object cam) {
        return closeCameraForPath(cam, false);
    }

    private boolean closeCameraForPath(
            Object cam, boolean preserveDiLink5Avm) {
        if (cam == null) return true;
        if (cam instanceof com.overdrive.app.camera.camera2.Camera2AvmBackend) {
            return ((com.overdrive.app.camera.camera2.Camera2AvmBackend) cam).close();
        }
        if (cam instanceof com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend) {
            com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend backend =
                    (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend) cam;
            boolean currentOwner = diLink5GlOwnerBackend == backend;
            if (currentOwner) {
                diLink5CloseInProgress = true;
                diLink5FramePending = false;
            }
            // Arm the exact child before GL teardown. A pending producer frame
            // can make the release guard exit with status 125 during this
            // phase, before closeWithRetirementRetry reaches nativeRelease().
            backend.markExpectedProcessRetirement();
            backend.clearFrameListenerIfOwner();
            // An unclaimed/stale worker never imported EGLImages. Only the
            // backend currently published in cameraObj may retire the GL
            // source; the backend token makes the native side compare-and-clear
            // as a second line of defense.
            boolean glReleased = !currentOwner
                    || releaseDiLink5GlResourcesBeforeClose(backend);
            if (currentOwner
                    && glReleased
                    && diLink5GlOwnerBackend == backend) {
                diLink5GlOwnerBackend = null;
            }
            // Native stream stop and release each have their own bounded wait.
            // Complete a token-scoped retry while this exact backend still
            // owns the sidecar before arming any process halt; otherwise the
            // old five-second urgent guard could kill JNI/DMA ownership in the
            // middle of its legitimate second retirement attempt.
            boolean clean = glReleased
                    && backend.closeWithRetirementRetry(
                            !preserveDiLink5Avm);
            if (!clean) {
                stopVerdictWedged = true;
                logger.error(glReleased
                        ? "DiLink 5 native camera handle did not close cleanly"
                        : "DiLink 5 EGLImage cleanup did not complete; "
                                + "producer shutdown was refused");
                try {
                    com.overdrive.app.daemon.CameraDaemon
                            .requestUrgentCameraReleaseRestart(
                                    "DiLink 5 camera ownership release incomplete");
                } catch (Throwable t) {
                    logger.error("Native camera restart request failed: "
                            + t.getMessage());
                }
            }
            return clean;
        }
        if (USE_DILINK4_AVM_PATH) {
            // Step 1 — release our viewpoint token. Mirrors oem C5920a.m26747m
            // (gl/C5920a.java:323 — C6498a.f26622a.m28933k(this)). Observer-set
            // semantics: this only writes viewpoint=0 + disableDevice if WE were
            // the last holder. If a sentry-restart caller acquired its own token
            // before stop() was invoked (ACC-OFF straddle), the set stays
            // non-empty and the HAL is never told to drop mosaic — that's what
            // keeps frames flowing across the close+reopen on this car.
            BydApaViewpointHelper.release(viewpointToken);
            // Step 2 — remove our texture binding from the HAL.
            detachSurfaceTextureFromCamera(cam);
            // Steps 3+4 — null callback proxies on the AVMCamera. This also
            // covers a still-armed producer kick (clearAvmCameraCallbacks nulls
            // setPreviewCallback); drop our own reference so the next attach is
            // free to re-arm rather than seeing a stale non-null proxy.
            previewCallbackProxy = null;
            previewKickArmedCamera = null;
            previewKickFirstByteLogged = false;
            previewKickByteSeen = false;
            // Release the arm-claim too, or the next attach would refuse to arm
            // (compareAndSet fails) and the kick would be permanently unavailable
            // after the first camera close.
            previewKickArming.set(false);
            clearAvmCameraCallbacks(cam);
        }
        // Steps 5+6 (and disablePreviewCallback in legacy compat).
        return BydCameraCoordinator.closeCamera(cam, cameraSurfaceMode);
    }

    private boolean releaseDiLink5GlResourcesBeforeClose(
            com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend backend) {
        if (!USE_DILINK5_QCARCAM_PATH) return true;
        Handler handler = glHandler;
        if (handler == null
                || android.os.Looper.myLooper() == handler.getLooper()) {
            return releaseDiLink5GlResourcesOnGlThread(backend);
        }
        java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean released = new AtomicBoolean(false);
        if (!handler.post(() -> {
            try {
                released.set(releaseDiLink5GlResourcesOnGlThread(backend));
            } finally {
                done.countDown();
            }
        })) {
            return false;
        }
        try {
            return done.await(
                    GL_THREAD_TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS)
                    && released.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean releaseDiLink5GlResourcesOnGlThread(
            com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend backend) {
        synchronized (cameraTextureLock) {
            try {
                if (eglCore != null && dummySurface != null) {
                    eglCore.makeCurrent(dummySurface);
                }
                return backend.releaseOwnedGlResources();
            } catch (Throwable t) {
                logger.warn("DiLink 5 EGLImage cleanup failed: " + t.getMessage());
                return false;
            }
        }
    }

    /** Null out the AVMCamera-side preview + event callback proxies before
     *  the HAL stopPreview/close. oem does this in m26747m at C5920a:333,339;
     *  some HAL builds keep stale refs alive otherwise. Quiet on errors. */
    private void clearAvmCameraCallbacks(Object cam) {
        if (cam == null) return;
        try {
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
            Class<?> previewCb = null;
            Class<?> eventCb = null;
            try {
                previewCb = Class.forName("android.hardware.AVMCamera$IPreviewCallback");
            } catch (ClassNotFoundException ignored) {}
            try {
                eventCb = Class.forName("android.hardware.AVMCamera$IEventCallback");
            } catch (ClassNotFoundException ignored) {}
            if (previewCb != null) {
                try {
                    Method m = avmClass.getDeclaredMethod("setPreviewCallback", previewCb);
                    m.setAccessible(true);
                    m.invoke(cam, new Object[]{null});
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable t) {
                    logger.warn("setPreviewCallback(null) failed: " + t.getMessage());
                }
            }
            if (eventCb != null) {
                try {
                    Method m = avmClass.getDeclaredMethod("setEventCallback", eventCb);
                    m.setAccessible(true);
                    m.invoke(cam, new Object[]{null});
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable t) {
                    logger.warn("setEventCallback(null) failed: " + t.getMessage());
                }
            }
        } catch (ClassNotFoundException e) {
            // AVMCamera class not present — fatal everywhere else, ignore here.
        }
    }

    /** Build an ImageReader-backed consumer (zero-copy path).
     *  Frame handling:
     *    HAL → ImageReader producer (gralloc)
     *      → OnImageAvailableListener fires on imageReaderThread
     *        → acquireLatestImage / getHardwareBuffer
     *          → glHandler.post(bindHardwareBufferToTexture + notify frameSync)
     *  The listener MUST run on a thread separate from glHandler because
     *  renderLoop parks the GL thread on frameSync.wait(); a same-thread
     *  listener would starve and the HAL queue would back up, dropping
     *  frames the way we observed at boot (Stats: 0 frames). */
    private void createCameraImageReader() {
        if (imageReaderThread == null) {
            imageReaderThread = new HandlerThread("CamImageReaderCb");
            imageReaderThread.start();
            imageReaderHandler = new Handler(imageReaderThread.getLooper());
        }
        // Pool size 6 (vs the typical 3) absorbs GL-thread stalls during
        // surveillance heavy work (YOLO inference, foveated readback) without
        // throttling the HAL producer rate. At 5120×960 NV12 = 7.4 MB/buf,
        // pool=6 holds ~44 MB gralloc — well within Adreno 610 budget.
        // Pool=3 was throttling HAL emission to ~5.7 fps in surveillance mode
        // because GL frames occasionally hit 261ms (logged backpressure).
        // 6 buffers × 67ms (15 fps cycle) = 400ms slack vs 200ms.
        // PRIVATE = opaque gralloc, optimal for zero-copy GPU sampling.
        // USAGE_GPU_SAMPLED_IMAGE tells the gralloc allocator we want a
        // GPU-friendly memory layout.
        final int poolSize = 6;
        try {
            long usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
            cameraImageReader = ImageReader.newInstance(
                width, height,
                ImageFormat.PRIVATE,
                poolSize,
                usage);
        } catch (Throwable t) {
            // Some BYD HAL builds may reject PRIVATE — fall back to YUV_420_888.
            logger.warn("ImageReader PRIVATE init failed: " + t.getMessage()
                + " — falling back to YUV_420_888");
            cameraImageReader = ImageReader.newInstance(
                width, height,
                ImageFormat.YUV_420_888,
                poolSize);
        }
        cameraImageReader.setOnImageAvailableListener(
            this::onHalImageAvailable, imageReaderHandler);
        cameraSurface = cameraImageReader.getSurface();
    }

    /** Idempotent teardown of whichever consumer is active. */
    private void releaseCameraConsumer() {
        // Crash-fix (gap-closer): this runs on the GL render thread via
        // recreateCameraSurface during live reacquire/auto-probe/restart while the
        // AI lane is STILL alive — freeing the bound gralloc / releasing the
        // SurfaceTexture here can race an in-flight AI-lane sample exactly like the
        // rebind path. Hold cameraTextureLock across BOTH buffer-free sites (the
        // top releasePreviousBoundImage AND the SurfaceTexture.release). Harmless
        // when reached from releaseGl (AI lane already shut down there).
        synchronized (cameraTextureLock) {
        // Decoupled lane note: any buffer copied this session was already
        // closed inline behind the per-frame publish barrier; the only
        // possible holdover is a bind that never reached its copy, and
        // releasePreviousBoundImage below owns exactly that case.
        // Release the held Image + HardwareBuffer FIRST so the gralloc slots
        // go back to the ImageReader pool before we close the reader.
        releasePreviousBoundImage();
        if (cameraSurface != null) {
            try { cameraSurface.release(); } catch (Throwable ignored) {}
            cameraSurface = null;
        }
        if (cameraImageReader != null) {
            try { cameraImageReader.close(); } catch (Throwable ignored) {}
            cameraImageReader = null;
        }
        if (cameraSurfaceTexture != null) {
            SurfaceTexture retired = retireCameraSurfaceTextureConsumer();
            if (retired != null) {
                try { retired.setOnFrameAvailableListener(null); } catch (Throwable ignored) {}
                try { retired.release(); } catch (Throwable ignored) {}
            }
        }
        }
        stFramePending = false;
        diLink5FramePending = false;
        // Reset the arrival bookkeeping together with the SurfaceTexture it
        // describes. The old listener is detached and, more importantly, its
        // identity/epoch was retired above, so even a callback already queued on
        // the Handler cannot increment the replacement consumer's state. A NEW
        // SurfaceTexture starts its own arrival sequence. Both must go back to 0
        // in lockstep:
        //   - leaving stFrameArrivalSeq high while stLastConsumedArrivalSeq is
        //     reset would fabricate a "fresh" frame before the HAL produced one;
        //   - leaving stLastConsumedArrivalSeq high while the counter restarts
        //     at 0 would make (arrivalSeq != last) true on the first arrival by
        //     accident and then FALSE for the genuine second frame.
        // Resetting both to 0 keeps the invariant "equal ⇒ nothing new".
        stFrameArrivalSeq.set(0);
        stLastConsumedArrivalSeq = 0;
        dilink4LastGenuineFrameArrivalMs = 0L;
        dilink4SoftRecoveryEpoch.incrementAndGet();
        dilink4SoftRecoveryInFlight.set(false);
        dilink4DeferredReopenEpoch.incrementAndGet();
        dilink4DeferredReopenPending.set(false);
        dilink4SoftRecoveryCycles = 0;
        // Drop the real-arrival clock too: a stale value would make the stall
        // watchdog measure against the previous camera session and could fire
        // (or suppress) spuriously right after a reattach. 0 = "no frame yet",
        // which the watchdog's `stallClock > 0` guard treats as warmup.
        lastRealFrameTimeSt = 0;
        stallEpisodeLogged = false;
        stallEpisodeStartMs = 0;
        stallEpisodeNextLogMs = 0;
        // Tell any in-flight attach helper thread (byte-callback watcher,
        // startPreview safety net) that the camera it was watching is gone, so it
        // stops rather than poking a released HAL object. Cleared again by the
        // next attachSurfaceTextureToCamera.
        cameraTornDown = true;
        // Invalidate the OBSERVED producer dims along with the camera that
        // produced them: they describe a slot we no longer hold, and a legacy
        // auto-probe walking id 0 → id 1 must not report id 0's geometry for id 1.
        //
        // emittedDimsLogged is deliberately NOT reset. It is the one-shot latch for
        // the "HAL emitted WxH but pipeline configured WxH" line, and this method
        // runs on every consumer recreate (probe advance, post-yield reacquire,
        // restartCameraAfterError) — re-arming it would turn a once-per-process
        // warning into once-per-reacquire on the legacy fleet, which is a
        // behavioural (log-volume) change for cars this work is not meant to
        // touch. The dims fields below are only consumed as diagnostics now
        // (persistPanoramicProbe no longer writes geometry), so leaving them at
        // -1 after a re-attach is correct-by-omission rather than stale.
        halEmittedWidth = -1;
        halEmittedHeight = -1;
        halBytePathWidth = -1;
        halBytePathHeight = -1;
        // Reset to identity so a stale matrix from the previous camera
        // can't leak into the first draw against a freshly-attached
        // SurfaceTexture if its first consumeSurfaceTextureFrame returns
        // false (e.g., spurious wakeup before HAL emits its first frame).
        // Identity is safe — it samples the whole producer surface, same
        // as legacy ImageReader behaviour.
        resetCurrentTexMatrixToIdentity();
    }

    private void resetCurrentTexMatrixToIdentity() {
        currentTexMatrix[0]  = 1f; currentTexMatrix[1]  = 0f;
        currentTexMatrix[2]  = 0f; currentTexMatrix[3]  = 0f;
        currentTexMatrix[4]  = 0f; currentTexMatrix[5]  = 1f;
        currentTexMatrix[6]  = 0f; currentTexMatrix[7]  = 0f;
        currentTexMatrix[8]  = 0f; currentTexMatrix[9]  = 0f;
        currentTexMatrix[10] = 1f; currentTexMatrix[11] = 0f;
        currentTexMatrix[12] = 0f; currentTexMatrix[13] = 0f;
        currentTexMatrix[14] = 0f; currentTexMatrix[15] = 1f;
    }

    private void createWindshieldImageReader() {
        if (imageReaderThread == null) {
            imageReaderThread = new HandlerThread("CamImageReaderCb");
            imageReaderThread.start();
            imageReaderHandler = new Handler(imageReaderThread.getLooper());
        }
        try {
            windshieldImageReader = ImageReader.newInstance(
                1920, 1080,
                ImageFormat.PRIVATE,
                4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        } catch (Throwable t) {
            logger.warn("Windshield ImageReader PRIVATE init failed: " + t.getMessage()
                + " — falling back to YUV_420_888");
            windshieldImageReader = ImageReader.newInstance(
                1920, 1080,
                ImageFormat.YUV_420_888,
                4);
        }
        windshieldImageReader.setOnImageAvailableListener(this::onWindshieldImageAvailable,
            imageReaderHandler);
        windshieldSurface = windshieldImageReader.getSurface();
    }

    private void onWindshieldImageAvailable(ImageReader r) {
        windshieldPending = true;
        synchronized (frameSync) {
            frameSync.notify();
        }
    }

    private void updateWindshieldCameraOnGlThread() {
        if (windshieldEnabled && windshieldCameraId >= 0) {
            int wantFps = targetFps;
            if (!windshieldStarted && !windshieldOpenFailed) {
                startWindshieldCameraOnGlThread();
            } else if (windshieldStarted && windshieldAppliedFps > 0
                    && windshieldAppliedFps != wantFps) {
                applyLegacyWindshieldFpsWithHardTimeout(wantFps);
            }
        } else if (windshieldStarted || windshieldOpenFailed) {
            stopWindshieldCameraOnGlThread();
            windshieldOpenFailed = false;
        }
    }

    private void startWindshieldCameraOnGlThread() {
        if (USE_CAMERA2_AVM_PATH) {
            logger.warn("camera2: no windshield camera in this mode; dashcam layout uses the 360 front");
            windshieldOpenFailed = true;
            return;
        }
        if (USE_DILINK4_AVM_PATH) {
            int concurrentAvmSupported = -1;
            try {
                org.json.JSONObject cameraConfig =
                    com.overdrive.app.config.UnifiedConfigManager
                        .loadConfig().optJSONObject("camera");
                if (cameraConfig != null) {
                    concurrentAvmSupported =
                        cameraConfig.optInt("concurrentAvmSupported", -1);
                }
            } catch (Throwable ignored) {
                concurrentAvmSupported = -1;
            }
            int primaryCameraId = getCameraId();
            if (!Di4CameraSafetyPolicy.canOpenSecondaryAvmCamera(
                    true,
                    primaryCameraId,
                    windshieldCameraId,
                    concurrentAvmSupported)) {
                logger.warn("DiLink 4 windshield open blocked: primaryId="
                    + primaryCameraId + ", windshieldId=" + windshieldCameraId
                    + ", concurrentAvmSupported=" + concurrentAvmSupported);
                windshieldOpenFailed = true;
                return;
            }
        }
        if (!USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH) {
            startLegacyWindshieldCameraWithHardTimeout();
            return;
        }
        try {
            createWindshieldImageReader();
            // This is a secondary handle opened only from the render loop
            // after the primary camera is already live and has passed the
            // cold-open warmup gate. Re-warming here would block the GL thread
            // and drop primary recording frames.
            Class<?> avmClass = Class.forName("android.hardware.AVMCamera");
            Constructor<?> constructor = avmClass.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            windshieldCameraObj = constructor.newInstance(windshieldCameraId);

            Method mOpen = avmClass.getDeclaredMethod("open");
            mOpen.setAccessible(true);
            if (!(boolean) mOpen.invoke(windshieldCameraObj)) {
                throw new RuntimeException("AVMCamera.open() returned false (id="
                    + windshieldCameraId + ")");
            }

            AvmCameraHelper.setCameraFps(windshieldCameraObj, targetFps);

            Method mAddSurface = avmClass.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
            mAddSurface.setAccessible(true);
            mAddSurface.invoke(windshieldCameraObj, windshieldSurface, 0);

            Method mStart = avmClass.getDeclaredMethod("startPreview");
            mStart.setAccessible(true);
            mStart.invoke(windshieldCameraObj);

            windshieldStarted = true;
            windshieldFrameReady = false;
            windshieldFrameCount = 0;
            // Seed the stall clock at start so the first-frame grace window is
            // measured from now, not from a stale prior-session timestamp.
            windshieldLastFrameMs = System.currentTimeMillis();
            logger.info("Windshield camera started (id=" + windshieldCameraId + ")");
        } catch (Throwable t) {
            logger.warn("Windshield camera unavailable; dashcam layout will fall back to 360 front: "
                + t.getMessage());
            windshieldOpenFailed = true;
            stopWindshieldCameraOnGlThread();
        }
    }

    /**
     * Bounds the optional legacy windshield camera's complete vendor
     * acquisition (open + attach + startPreview). The old path ran all three
     * calls directly on the primary GL thread, so one wedged secondary camera
     * froze the primary recorder and fed the frame-stall restart loop.
     */
    private void startLegacyWindshieldCameraWithHardTimeout() {
        if (legacyWindshieldCameraTerminalRestart.get()
                || CameraDaemon.isProcessRestartPending()) {
            windshieldOpenFailed = true;
            return;
        }

        final int requestedCameraId = windshieldCameraId;
        final long requestedStartEpoch = activeStartEpoch;
        final int requestedFps = targetFps;
        final java.util.concurrent.atomic.AtomicReference<Object> openedCamera =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> openFailure =
            new java.util.concurrent.atomic.AtomicReference<>();

        try {
            createWindshieldImageReader();
            final Surface requestedSurface = windshieldSurface;
            if (!legacyWindshieldCameraLifecycleInFlight
                    .compareAndSet(false, true)) {
                logger.info("Legacy windshield camera lifecycle already in flight");
                return;
            }

            Thread worker = new Thread(() -> {
                Object candidate = null;
                try {
                    Class<?> avmClass =
                        Class.forName("android.hardware.AVMCamera");
                    Constructor<?> constructor =
                        avmClass.getDeclaredConstructor(int.class);
                    constructor.setAccessible(true);
                    candidate = constructor.newInstance(requestedCameraId);

                    Method mOpen = avmClass.getDeclaredMethod("open");
                    mOpen.setAccessible(true);
                    if (!(boolean) mOpen.invoke(candidate)) {
                        throw new RuntimeException(
                            "AVMCamera.open() returned false (id="
                                + requestedCameraId + ")");
                    }

                    AvmCameraHelper.setCameraFps(candidate, requestedFps);

                    Method mAddSurface = avmClass.getDeclaredMethod(
                        "addPreviewSurface", Surface.class, int.class);
                    mAddSurface.setAccessible(true);
                    mAddSurface.invoke(candidate, requestedSurface, 0);

                    Method mStart =
                        avmClass.getDeclaredMethod("startPreview");
                    mStart.setAccessible(true);
                    mStart.invoke(candidate);

                    if (!isLegacyWindshieldRequestCurrent(
                            requestedCameraId, requestedStartEpoch)) {
                        logger.info("Legacy windshield camera request retired "
                            + "during vendor acquisition");
                        return;
                    }
                    openedCamera.set(candidate);
                    candidate = null;
                } catch (Throwable t) {
                    openFailure.set(t);
                } finally {
                    try {
                        if (candidate != null) {
                            // This cleanup is part of the same bounded worker.
                            // If it wedges, the caller's hard deadline retires
                            // the process instead of touching the ambiguous
                            // handle.
                            BydCameraCoordinator.closeCamera(candidate, 0);
                        }
                    } finally {
                        legacyWindshieldCameraLifecycleInFlight.set(false);
                    }
                }
            }, "LegacyWindshieldCameraOpen");
            worker.setDaemon(true);
            try {
                worker.start();
            } catch (Throwable spawnFailure) {
                legacyWindshieldCameraLifecycleInFlight.set(false);
                throw spawnFailure;
            }

            if (!awaitLegacyWindshieldWorker(
                    worker, "Legacy windshield camera open/start")) {
                windshieldOpenFailed = true;
                return;
            }

            Throwable failure = openFailure.get();
            if (failure != null) {
                throw new RuntimeException(
                    "Legacy windshield camera acquisition failed", failure);
            }

            Object completed = openedCamera.getAndSet(null);
            if (completed == null) {
                // A request retired while the worker was in vendor code. Its
                // worker-side finally already closed the temporary handle.
                return;
            }
            if (!isLegacyWindshieldRequestCurrent(
                    requestedCameraId, requestedStartEpoch)) {
                if (CameraDaemon.isProcessRestartPending()) {
                    armLegacyWindshieldTerminalRestart(
                        "Legacy windshield camera completed after process "
                            + "retirement began");
                    windshieldOpenFailed = true;
                    return;
                }
                if (!closeLegacyWindshieldCameraWithHardTimeout(
                        completed, "retired after acquisition")) {
                    windshieldOpenFailed = true;
                }
                return;
            }

            windshieldCameraObj = completed;
            windshieldStarted = true;
            windshieldFrameReady = false;
            windshieldFrameCount = 0;
            windshieldAppliedFps = requestedFps;
            windshieldLastFrameMs = System.currentTimeMillis();
            // The primary camera continued producing while its GL consumer
            // waited for the secondary acquisition. Give it a fresh stall
            // window instead of treating that intentional wait as HAL loss.
            lastFrameTime = System.currentTimeMillis();
            lastGlThreadHeartbeat = lastFrameTime;
            logger.info("Windshield camera started (id="
                + requestedCameraId + ")");
        } catch (Throwable t) {
            logger.warn("Windshield camera unavailable; dashcam layout will "
                + "fall back to 360 front: " + t.getMessage());
            windshieldOpenFailed = true;
            stopWindshieldCameraOnGlThread();
        }
    }

    private boolean isLegacyWindshieldRequestCurrent(
            int requestedCameraId, long requestedStartEpoch) {
        return running
            && windshieldEnabled
            && windshieldCameraId == requestedCameraId
            && CameraDaemon.isCameraStartEpochCurrent(requestedStartEpoch)
            && !restartInProgress.get()
            && !CameraDaemon.isProcessRestartPending()
            && !legacyWindshieldCameraTerminalRestart.get();
    }

    private boolean awaitLegacyWindshieldWorker(
            Thread worker, String phase) {
        boolean interrupted = false;
        long deadline = android.os.SystemClock.elapsedRealtime()
            + GL_THREAD_WARMUP_TIMEOUT_MS;
        while (worker.isAlive()) {
            long remaining =
                deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0L) break;
            lastGlThreadHeartbeat = System.currentTimeMillis();
            try {
                worker.join(Math.min(remaining, 200L));
            } catch (InterruptedException waitInterrupted) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            armLegacyWindshieldTerminalRestart(
                phase + " blocked for " + GL_THREAD_WARMUP_TIMEOUT_MS + "ms");
            return false;
        }
        return true;
    }

    private boolean closeLegacyWindshieldCameraWithHardTimeout(
            Object camera, String phase) {
        if (camera == null) return true;
        if (legacyWindshieldCameraTerminalRestart.get()) return false;
        if (!legacyWindshieldCameraLifecycleInFlight.compareAndSet(
                false, true)) {
            armLegacyWindshieldTerminalRestart(
                "Overlapping legacy windshield camera lifecycle during "
                    + phase);
            return false;
        }

        Thread worker = new Thread(() -> {
            try {
                BydCameraCoordinator.closeCamera(camera, 0);
            } finally {
                legacyWindshieldCameraLifecycleInFlight.set(false);
            }
        }, "LegacyWindshieldCameraClose");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Throwable spawnFailure) {
            legacyWindshieldCameraLifecycleInFlight.set(false);
            armLegacyWindshieldTerminalRestart(
                "Could not start bounded legacy windshield close worker: "
                    + spawnFailure.getMessage());
            return false;
        }
        return awaitLegacyWindshieldWorker(
            worker, "Legacy windshield camera close (" + phase + ")");
    }

    /** Keeps the legacy windshield HAL at the pano target fps (it is only
     *  consumed at pano cadence). Same bounded-worker discipline as open/close. */
    private void applyLegacyWindshieldFpsWithHardTimeout(int fps) {
        final Object camera = windshieldCameraObj;
        if (camera == null
                || !running
                || legacyWindshieldCameraTerminalRestart.get()
                || CameraDaemon.isProcessRestartPending()
                || !legacyWindshieldCameraLifecycleInFlight.compareAndSet(
                    false, true)) {
            return;
        }
        final AtomicBoolean accepted = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try {
                accepted.set(AvmCameraHelper.setCameraFps(camera, fps));
            } finally {
                legacyWindshieldCameraLifecycleInFlight.set(false);
            }
        }, "LegacyWindshieldCameraFps");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Throwable spawnFailure) {
            legacyWindshieldCameraLifecycleInFlight.set(false);
            windshieldAppliedFps = fps;
            logger.warn("Windshield fps worker did not start: "
                + spawnFailure.getMessage());
            return;
        }
        if (awaitLegacyWindshieldWorker(worker, "Legacy windshield camera fps")) {
            // Recorded even when the vendor returns false (the legacy HAL may still apply it);
            // retrying every frame would only repeat the same answer.
            windshieldAppliedFps = fps;
            // Same as the open path: the pano was not consumed while this thread waited.
            lastFrameTime = System.currentTimeMillis();
            lastGlThreadHeartbeat = lastFrameTime;
            logger.info("Windshield camera fps requested " + fps
                + (accepted.get() ? "" : " (vendor returned false)"));
        }
    }

    private void armLegacyWindshieldTerminalRestart(String reason) {
        legacyWindshieldCameraTerminalRestart.set(true);
        CameraDaemon.requestUrgentCameraReleaseRestart(reason);
        logger.error(reason + " — terminal process retirement armed; "
            + "windshield camera/EGL cleanup fenced");
    }

    private boolean stopWindshieldCameraOnGlThread() {
        if (legacyWindshieldCameraTerminalRestart.get()) {
            logger.warn("Skipping windshield teardown: terminal legacy camera "
                    + "restart owns the ambiguous vendor lifecycle");
            return false;
        }
        boolean cameraClosed = true;
        if (windshieldCameraObj != null) {
            Object cameraToClose = windshieldCameraObj;
            if (!USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH) {
                if (!closeLegacyWindshieldCameraWithHardTimeout(
                        cameraToClose, "normal teardown")) {
                    return false;
                }
            } else {
                cameraClosed =
                        BydCameraCoordinator.closeCamera(cameraToClose, 0);
            }
            windshieldCameraObj = null;
        }
        releasePreviousBoundWindshieldImage();
        if (windshieldSurface != null) {
            try { windshieldSurface.release(); } catch (Throwable ignored) {}
            windshieldSurface = null;
        }
        if (windshieldImageReader != null) {
            try { windshieldImageReader.close(); } catch (Throwable ignored) {}
            windshieldImageReader = null;
        }
        if (windshieldStarted || windshieldFrameReady) {
            logger.info("Windshield camera stopped (frames=" + windshieldFrameCount + ")");
        }
        windshieldStarted = false;
        windshieldPending = false;
        windshieldFrameReady = false;
        windshieldFrameCount = 0;
        windshieldAppliedFps = 0;
        return cameraClosed;
    }

    /**
     * DiLink 5 never yields the FastCam source to OEM reverse. The AIS server
     * multiplexes the QCarCam inputs to every client, so the OEM reverse/360
     * view renders alongside our stream. The former gear-driven pause/resume
     * (kill producer on R, respawn 3 s after leaving R, hand the vendor AVM
     * back and forth) raced the frame watchdog, the release guard and the
     * boot-scoped safety markers; it was removed. Both predicates are kept
     * as permanent {@code false} roots so every remaining reverse-flag
     * writer in this class is unreachable, and the {@code diLink5Reverse*}
     * flags they gated stay at their idle defaults for the process lifetime.
     */
    private boolean shouldHoldForDiLink5Reverse() {
        return false;
    }

    private boolean isFreshDiLink5Reverse() {
        return false;
    }

    private boolean awaitDiLink5OwnershipTransitionBeforeStop() {
        if (!USE_DILINK5_QCARCAM_PATH) return true;
        boolean locked = false;
        try {
            locked = diLink5OwnershipTransition.tryLock(
                    GL_THREAD_WARMUP_TIMEOUT_MS + GL_THREAD_TIMEOUT_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (locked) return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked) diLink5OwnershipTransition.unlock();
        }
        CameraDaemon.requestUrgentCameraReleaseRestart(
                "DiLink 5 GL ownership transition did not stop");
        return false;
    }

    /**
     * Starts the BYD camera via AVMCamera reflection with multi-strategy fallback.
     * Tries constructor path first, then static factory for firmware compatibility.
     */
    private void startCamera() throws Exception {
        requireCurrentStartEpoch(activeStartEpoch, "camera acquisition");
        if (USE_DILINK5_QCARCAM_PATH && diLink5SafetyDisabled) {
            logger.error("DiLink 5 camera remains in boot-scoped safe-off mode");
            return;
        }
        if (shouldHoldForDiLink5Reverse()) {
            logger.info("DiLink 5 reverse/AVM owns the cameras — skipping FastCam open");
            return;
        }
        // GATE: Don't open camera if yielded to native app via IBYDCameraUser callback
        if (!USE_DILINK5_QCARCAM_PATH
                && cameraCoordinator != null
                && cameraCoordinator.isCameraYielded()) {
            logger.info("Camera yielded to native app — skipping open");
            cameraYielded = true;
            // Defensive: ensure the yield poller is running. yieldCameraInternal
            // also starts it, but this path can be hit if startCamera is invoked
            // while the coordinator already reports yielded (e.g. ACC ON race).
            startYieldPoller();
            return;
        }

        if (cameraObj != null) {
            logger.info("Camera handle already active — skipping duplicate cold open");
            return;
        }

        // Cold-open invariant: every path that creates the primary AVMCamera
        // handle (initial start, ACC reopen, HAL-error restart, yield
        // reacquire, and auto-probe) routes through this one method.
        if (!AvcHalWarmup.warmupBeforeColdOpen(
                () -> (running || legacyCameraInitializationInFlight.get())
                    && cameraObj == null
                    && CameraDaemon.isCameraStartEpochCurrent(activeStartEpoch)
                    && !CameraDaemon.isProcessRestartPending())) {
            throw new InterruptedException(
                "AVC HAL warmup interrupted before AVMCamera open");
        }
        lastGlThreadHeartbeat = System.currentTimeMillis();

        // Warmup is deliberately blocking. Revalidate everything that may
        // have changed while it ran before touching the HAL.
        requireCurrentStartEpoch(activeStartEpoch, "post-warmup camera acquisition");
        if (shouldHoldForDiLink5Reverse()) {
            logger.info("DiLink 5 reverse/AVM claimed the cameras during warmup");
            return;
        }
        if (cameraObj != null) {
            logger.info("Camera opened by a concurrent path during warmup — "
                + "skipping duplicate cold open");
            return;
        }
        if (!USE_DILINK5_QCARCAM_PATH
                && cameraCoordinator != null
                && cameraCoordinator.isCameraYielded()) {
            logger.info("Camera yielded during warmup — skipping open");
            cameraYielded = true;
            startYieldPoller();
            return;
        }

        int cameraId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
        final boolean legacyOpen =
                !USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH;
        if (legacyOpen) {
            legacyCameraOpenInFlight.set(true);
        }
        try {
            startCameraViaAvmReflection(cameraId);
            // A refused Camera2 open is retried by the camera2 health tick.
            if (USE_CAMERA2_AVM_PATH && cameraObj == null) {
                return;
            }
            if (USE_DILINK5_QCARCAM_PATH
                    && diLink5SafetyDisabled
                    && cameraObj == null) {
                logger.error("DiLink 5 camera startup degraded to camera-off mode; "
                        + "the daemon and non-camera subsystems remain active");
                return;
            }
            if (USE_DILINK5_QCARCAM_PATH && cameraObj == null) {
                if (diLink5ReverseRequested || isFreshDiLink5Reverse()) {
                    diLink5ReverseRequested = true;
                    diLink5SystemAvmFrameGate = true;
                    logger.info("DiLink 5 FastCam open was superseded before "
                            + "GL-owner publication; system reverse retains the "
                            + "physical inputs");
                    return;
                }
                throw new IllegalStateException(
                        "DiLink 5 FastCam source was not published to its GL owner");
            }

            // Remember every slot we've opened so the dead-slot walk (issue #170)
            // can't loop back onto one that already failed to deliver a frame.
            // Recomputed rather than reusing the local: the static-factory branch
            // inside startCameraViaAvmReflection can probe ids 0-5 and open a
            // DIFFERENT slot (it updates cameraIdOverride when it does) — record
            // the id that actually opened, not the one we asked for.
            deadSlotTriedCameraIds.add(
                    cameraIdOverride >= 0
                            ? cameraIdOverride : PHYSICAL_CAMERA_ID);

            boolean reverseClaimedDuringOpen =
                    USE_DILINK5_QCARCAM_PATH
                            && (diLink5ReverseRequested || isFreshDiLink5Reverse());
            if (reverseClaimedDuringOpen) {
                // Reverse can arrive while nativeInit/start is blocked inside the
                // HAL. The queued reverse task owns the subsequent close, but the
                // newly opened producer must never become render-visible in the
                // meantime.
                diLink5ReverseRequested = true;
                diLink5SystemAvmFrameGate = true;
                diLink5ReverseHandoffComplete = false;
                logger.info("DiLink 5 reverse claimed the camera during FastCam "
                        + "open; keeping frame consumption paused until the "
                        + "source-only handoff closes it");
            } else {
                if (!USE_DILINK5_QCARCAM_PATH) {
                    cameraYielded = false;
                } else {
                    diLink5SystemAvmFrameGate = false;
                }
                diLink5ReverseHandoffComplete = true;
                diLink5AvmPreservedForReverse = false;
            }
            lastCameraStartTime = System.currentTimeMillis();
            // Snapshot the frame counter at this open so the next restart can tell
            // whether THIS open ever delivered a frame (zero-frame-reopen escalation).
            frameCounterAtOpen = frameCounter;
            logger.info("Camera started (" + width + "x" + height +
                ", id=" + cameraId + ", surfaceMode=" + cameraSurfaceMode + ")");

            // Update coordinator with actual camera ID
            if (cameraCoordinator != null) {
                cameraCoordinator.setActiveCameraId(cameraId);
            }
        } finally {
            if (legacyOpen) {
                legacyCameraOpenInFlight.set(false);
            }
        }
    }

    /**
     * Opens camera via AVMCamera reflection.
     *
     * Strategy:
     *   - Old DiLink 4: try the static AVMCamera.open(int) factory first,
     *     then fall back to new AVMCamera(int) + .open(). This keeps the
     *     factory-managed path without adding a reverse-gear recording gap.
     *   - Every other AVMCamera path retains the shipped constructor-first
     *     behavior and its static-factory fallback/probe.
     *
     * See CAMERA_FPS_INVESTIGATION.md for the full rationale.
     *
     * After either path succeeds, addPreviewSurface + startPreview are called.
     *
     * Calls the coordinator's pre-open hook before acquisition.
     */
    private void startCameraViaAvmReflection(int cameraId) throws Exception {
        requireCurrentStartEpoch(activeStartEpoch, "AVM acquisition");
        // The cold-open warmup gate is centralized in startCamera(), before
        // any caller can reach this reflection boundary.

        // Notify camera service we're about to open
        if (cameraCoordinator != null) {
            cameraCoordinator.notifyPreOpenCamera();
        }
        requireCurrentStartEpoch(activeStartEpoch, "AVM pre-open");

        // oem-parity: tell the BYDAutoManager Panorama device (1031) to switch
        // its viewpoint to mosaic-output BEFORE opening AVMCamera. On byd_apa /
        // apa firmware variants the HAL boots in single-camera (dashcam) mode
        // and stays there until this setIntArray write flips it. Mirrors oem
        // gl.C5920a.mo26750v:386-388.
        //
        // Gated on the DiLink 4 path: on legacy pano_h/pano_l boards the
        // disable counterpart in closeCameraForPath is also gated, so we
        // keep the pair symmetric. The helper would warn-log on legacy
        // anyway (no panorama device exposed), but skipping the call also
        // skips a binder round-trip per camera open.
        if (USE_DILINK4_AVM_PATH) {
            // Acquire our viewpoint token. Mirrors oem C5920a.mo26750v
            // (gl/C5920a.java:387 — C6498a.f26622a.m28930h(this)). If
            // we're the only holder this writes viewpoint=2012 and registers
            // the listener; if a sentry-restart caller is already holding a
            // token, this just re-issues the viewpoint write idempotently
            // (matches oem's "size>1 already, no enableDevice/listener" branch).
            BydApaViewpointHelper.acquire(viewpointToken);

            // Release the static sentry-bridge token NOW (set transitions
            // bridge+pano → pano on the same lock acquire as our add — no
            // empty-set window). Idempotent + harmless if no bridge was held.
            releaseSentryBridgeViewpoint();
        }

        // DiLink 5.0 (Snapdragon SA8155P): uses fast_cam_capture DMA-FD IPC.
        // Never silently fall back to the legacy AVMCamera path when explicitly selected.
        if (USE_DILINK5_QCARCAM_PATH) {
            if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled()) {
                throw new IllegalStateException(
                        "DiLink 5 selected but the QCarCam/AIS runtime is unavailable");
            }
            logger.info("DiLink 5 platform detected — initializing native QCarCam backend (cameraId=" + cameraId + ")");
            com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend dilink5Backend =
                    new com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend(
                            cameraId, activeStartEpoch);
            boolean started = dilink5Backend.start(ignoredTimestampNs -> {
                synchronized (frameSync) {
                    diLink5FramePending = true;
                    frameSync.notify();
                }
            });
            if (!started) {
                dilink5Backend.clearFrameListenerIfOwner();
                closeCameraForPath(dilink5Backend);
                if (com.overdrive.app.camera.dilink5
                        .DiLink5QCarCamBackend.isCaptureSuppressed()) {
                    diLink5SafetyDisabled = true;
                    logger.error("DiLink 5 QCarCam acquisition was refused by "
                            + "the lifecycle safety guard; camera features are "
                            + "disabled until the next safe daemon/vehicle cycle");
                    onDiLink5IdleResumeFailed("the camera reopen was refused", 0L);
                    return;
                }
                throw new IllegalStateException(
                        "DiLink 5 native QCarCam stream failed to start");
            }
            boolean sourceClaimed;
            synchronized (com.overdrive.app.camera.dilink5
                    .DiLink5QCarCamBackend.class) {
                sourceClaimed =
                        dilink5Backend.claimPublishedSourceForGlOwner();
                if (sourceClaimed) {
                    // The backend claim and this owner-visible assignment must
                    // be one atomic step against reverse cancellation. Once
                    // cancellation observes "claimed", its queued GL task is
                    // guaranteed to find this exact source in cameraObj.
                    cameraObj = dilink5Backend;
                    diLink5GlOwnerBackend = dilink5Backend;
                    diLink5CloseInProgress = false;
                    diLink5IdleSourceReleased = false;
                    diLink5IdleLastDemandElapsedMs =
                            android.os.SystemClock.elapsedRealtime();
                }
            }
            if (!sourceClaimed) {
                dilink5Backend.clearFrameListenerIfOwner();
                boolean closed =
                        dilink5Backend.closeWithRetirementRetry(false);
                if (!closed) {
                    CameraDaemon.requestUrgentCameraReleaseRestart(
                            "FastCam source lost GL-owner claim and could not close");
                }
                logger.info("DiLink 5 native QCarCam source was superseded "
                        + "before GL-owner publication.");
                return;
            }
            logger.info("DiLink 5 native QCarCam stream initialized (cameraObj assigned).");
            return;
        }

        if (USE_CAMERA2_AVM_PATH) {
            openCamera2Source(cameraId);
            return;
        }

        Class<?> avmClass = Class.forName("android.hardware.AVMCamera");

        if (shouldUseDi4PreviewBootstrap()) {
            // Old-Di4 pano uses the framework factory before constructor fallback.
            // Unlike a reverse-gear yield, this leaves the recorder running.
            cameraObj = null;
            try {
                Method mStaticOpen = avmClass.getDeclaredMethod("open", int.class);
                mStaticOpen.setAccessible(true);
                cameraObj = mStaticOpen.invoke(null, cameraId);
                if (cameraObj != null) {
                    logger.info("DiLink 4 camera opened via static factory (id="
                        + cameraId + ")");
                }
            } catch (Throwable staticOpenFailure) {
                logger.warn("DiLink 4 static AVMCamera.open(" + cameraId
                    + ") failed — trying constructor: "
                    + staticOpenFailure.getMessage());
                cameraObj = null;
            }

            if (cameraObj == null) {
                logger.info("DiLink 4 static AVMCamera.open(" + cameraId
                    + ") returned null — trying constructor fallback");
                Constructor<?> constructor = avmClass.getDeclaredConstructor(int.class);
                constructor.setAccessible(true);
                cameraObj = constructor.newInstance(cameraId);

                Method mOpen = avmClass.getDeclaredMethod("open");
                mOpen.setAccessible(true);
                if (!(boolean) mOpen.invoke(cameraObj)) {
                    throw new RuntimeException(
                        "AVMCamera.open() returned false (id=" + cameraId + ")");
                }
                logger.info("DiLink 4 camera opened via constructor fallback (id="
                    + cameraId + ")");
            }
        } else {
            // === ATTEMPT 1: Constructor new AVMCamera(int) + .open() ===
            try {
                Constructor<?> constructor = avmClass.getDeclaredConstructor(int.class);
                constructor.setAccessible(true);
                cameraObj = constructor.newInstance(cameraId);

                Method mOpen = avmClass.getDeclaredMethod("open");
                mOpen.setAccessible(true);
                if (!(boolean) mOpen.invoke(cameraObj)) {
                    throw new RuntimeException("AVMCamera.open() returned false (id=" + cameraId + ")");
                }
                logger.info("Camera opened via constructor path (id=" + cameraId + ")");
            } catch (NoSuchMethodException e) {
                // Constructor with int param doesn't exist — fall back to static factory
                logger.info("AVMCamera(int) constructor not found — trying static factory");
                cameraObj = null;

                // === ATTEMPT 2: Static factory AVMCamera.open(cameraId) ===
                try {
                    Method mStaticOpen = avmClass.getDeclaredMethod("open", int.class);
                    mStaticOpen.setAccessible(true);
                    cameraObj = mStaticOpen.invoke(null, cameraId);
                    if (cameraObj != null) {
                        logger.info("Camera opened via static factory (id=" + cameraId + ")");
                    } else {
                        logger.info("AVMCamera.open(" + cameraId + ") returned null — trying IDs 0-5");
                        for (int tryId = 0; tryId <= 5; tryId++) {
                            if (tryId == cameraId) continue;
                            cameraObj = mStaticOpen.invoke(null, tryId);
                            if (cameraObj != null) {
                                logger.info("Camera opened via static factory probe (id=" + tryId + ")");
                                cameraIdOverride = tryId;
                                break;
                            }
                        }
                    }
                    if (cameraObj == null) {
                        throw new RuntimeException("AVMCamera.open() returned null for all IDs 0-5");
                    }
                } catch (NoSuchMethodException e2) {
                    throw new RuntimeException(
                        "AVMCamera API not compatible: no constructor(int) and no static open(int). " +
                        "Available constructors: " + Arrays.toString(avmClass.getDeclaredConstructors()) +
                        ", methods: " + Arrays.toString(avmClass.getDeclaredMethods()), e2);
                }
            }
        }
        
        // Set FPS BEFORE attaching any consumer. On DiLink 3.x firmware the
        // HAL rejects setCameraFps once a consumer is bound — even before
        // startPreview. Order matches both oem's AVMCameraRecorder and the
        // legacy ImageReader path: open → setCameraFps → attach → start.
        AvmCameraHelper.setCameraFps(cameraObj, targetFps);

        // oem-parity: register the AVMCamera IEventCallback BEFORE the
        // consumer attach so the 1003 first-frame event and any pre-frame
        // 8/1000/1002 fatal events emitted during HAL warmup are observable.
        // Mirrors oem gl.C5920a.mo26750v:418 (after setCameraFps, before
        // addTexture). The previous wiring registered the callback after
        // start() returned; on byd_apa boards that fire the death event
        // inside the warmup window, the coordinator's onCameraError never
        // fired.
        if (cameraCoordinator != null) {
            cameraCoordinator.setupEventCallback(cameraObj);
        }

        if (USE_OEM_SURFACE_TEXTURE_PATH) {
            // oem path: addTexture(st, idx) + setTexture(st, idx) + startPreview.
            // attachSurfaceTextureToCamera does all three; cameraSurfaceMode is
            // the previewIndex (0=mosaic on byd_apa/apa HAL).
            attachSurfaceTextureToCamera(cameraId);
        } else {
            // Legacy path: addPreviewSurface(Surface, surfaceMode) + startPreview.
            // mode 0 works on Seal; other models may need different mode.
            Method mAddSurface = avmClass.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
            mAddSurface.setAccessible(true);
            mAddSurface.invoke(cameraObj, cameraSurface, cameraSurfaceMode);

            // Start preview — required for real frame data on BYD Seal HAL.
            // The HAL supports multiple consumers calling startPreview simultaneously.
            // The AVC warmup (com.byd.avc launch + 4s delay) ensures the native DVR
            // has already initialized before we reach here, preventing race conditions.
            Method mStart = avmClass.getDeclaredMethod("startPreview");
            mStart.setAccessible(true);
            mStart.invoke(cameraObj);
            logger.info("Camera started (id=" + cameraId + ", targetFps=" + targetFps + ")");
        }
    }
    
    // Diagnostic counters for the ImageReader frame flow. Kept in place as
    // permanent instrumentation since the path crosses two threads + a
    // gralloc lifetime boundary; surfacing health via 2-min Stats line is
    // cheap and useful in field debugging.
    private volatile long irFireCount = 0;       // onHalImageAvailable invocations
    private volatile long irAcquireOkCount = 0;
    private volatile long irAcquireNullCount = 0;
    private volatile long irBindFailCount = 0;
    private volatile long lastIrDiagLogMs = 0;

    /**
     * Called when a new gralloc buffer is available from the HAL
     * (ImageReader path, API 28+). Runs on imageReaderThread (NOT glThread)
     * — we cannot do the EGLImage bind here because the EGL context lives
     * on the GL thread.
     *
     * Strategy: notify frameSync so renderLoop wakes up. renderLoop will
     * do acquireLatestImage + getHardwareBuffer + bind on the GL thread
     * where the EGL context is current. This mirrors the SurfaceTexture
     * path where the producer notifies and the consumer thread does
     * updateTexImage.
     */
    private void onHalImageAvailable(ImageReader r) {
        irFireCount++;
        synchronized (frameSync) {
            imagePending = true;
            frameSync.notify();
        }
    }

    /**
     * Acquires the latest gralloc buffer from cameraImageReader and binds it
     * to cameraTextureId. MUST be called from the GL thread (current EGL
     * context required for glEGLImageTargetTexture2DOES).
     *
     * Returns true if a frame was bound; false if no frame was ready or
     * the bind failed. acquireLatestImage drops older buffered frames if
     * the GL loop falls behind, matching SurfaceTexture's "always sample
     * latest" semantics.
     */
    // The Image and HardwareBuffer currently bound to cameraTextureId.
    // Held alive across GL render cycles — closing them returns the gralloc
    // slot to the ImageReader pool, which invalidates the EGLImage we bound
    // and causes the producer side to stall. Released only when the NEXT
    // bind succeeds (releasePreviousImage call inside consumeLatestImageAndBind),
    // so the texture always references a live gralloc buffer.
    //
    // THREAD-CONFINED to the GL thread (renderLoop). All reads and writes
    // happen inside consumeLatestImageAndBind / releasePreviousBoundImage,
    // which are only invoked from renderLoop. Do NOT access from the
    // ImageReader callback thread, watchdog, or any daemon thread — touching
    // these from another thread will leak the gralloc slot and stall the HAL.
    private Image currentBoundImage;             // @GuardedBy(GL thread)
    private HardwareBuffer currentBoundHwBuffer; // @GuardedBy(GL thread)
    // HAL-provided sensor timestamp (ns) of the currently bound image,
    // captured from Image.getTimestamp() inside consumeLatestImageAndBind.
    // Fed to eglPresentationTimeANDROID so MediaCodec produces honest PTS.
    //
    // BYD DiLink 5.0 HAL specifics observed in field probes:
    //   - On the very first frame, hwTs ≈ 52ms (an uptime offset, not a
    //     real sensor time).
    //   - Subsequent frames return the same value, OR micro-advance by
    //     a few hundred ns per frame, never tracking real cadence.
    //
    // Strategy (single clock domain): the BYD PRIVATE-ImageReader HAL returns a
    // stuck, different-epoch (~uptime) value for Image.getTimestamp() on this
    // fleet, so we DO NOT trust it. nextFrameTimestampNs() stamps
    // System.nanoTime() from frame 0 unconditionally — exactly what the sibling
    // oem SurfaceTexture path does — which means there is never a mid-stream
    // HW→nanoTime clock-domain transition to corrupt the muxer's rebase math.
    // (The former hwTs-trust-then-latch machine is the historical root of the
    // "55 min – 1 hr clip duration" bug; removed.) lastAcceptedPtsNs enforces
    // MediaCodec's strictly-increasing PTS contract within that one domain.
    private long currentFrameTimestampNs = 0;     // @GuardedBy(GL thread)
    private long lastAcceptedPtsNs = 0;           // @GuardedBy(GL thread)
    private int  ptsDomain = PTS_DOMAIN_NONE;     // @GuardedBy(GL thread)
    private boolean ptsSourceLogged = false;      // @GuardedBy(GL thread)
    private static final int PTS_DOMAIN_NONE = 0;
    private static final int PTS_DOMAIN_NANO = 2;

    private boolean consumeLatestImageAndBind() {
        ImageReader reader = cameraImageReader;
        if (reader == null) return false;
        Image image = null;
        HardwareBuffer hwBuffer = null;
        boolean transferredOwnership = false;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                irAcquireNullCount++;
                return false;
            }
            irAcquireOkCount++;
            // SOTA cross-vehicle sanity: log once per session if HAL-emitted
            // dims differ from the configured strip. Mosaic offsets, foveated
            // crop math, and encoder geometry all assume the configured size.
            // A silent mismatch (e.g., Tang HAL ignoring our 960 request and
            // emitting 720 anyway) would record corrupted mosaics — surface
            // it loudly so the operator can pick a different camera profile.
            if (!emittedDimsLogged) {
                int emittedW = image.getWidth();
                int emittedH = image.getHeight();
                // Record the OBSERVED size. This is the authoritative producer
                // size on the legacy path and the only one we should ever
                // persist as "probed" — previously it was logged and discarded
                // while persistPanoramicProbe wrote back the CONFIGURED size.
                halEmittedWidth = emittedW;
                halEmittedHeight = emittedH;
                if (emittedW != width || emittedH != height) {
                    logger.warn("HAL emitted " + emittedW + "x" + emittedH
                        + " but pipeline configured " + width + "x" + height
                        + " — mosaic/foveated geometry assumes the configured size."
                        + " Pick a different camera profile if this looks wrong.");
                } else {
                    logger.info("HAL emitted " + emittedW + "x" + emittedH
                        + " (matches configured " + width + "x" + height + ")");
                }
                emittedDimsLogged = true;
            }
            hwBuffer = image.getHardwareBuffer();
            if (hwBuffer == null) {
                logger.warn("Image.getHardwareBuffer() returned null — dropping frame");
                irBindFailCount++;
                return false;
            }
            // Crash-fix: hold cameraTextureLock across the rebind + prev-buffer
            // free so the AI lane cannot be mid-sampling the OLD backing buffer
            // when we swap the EGLImage / free the gralloc it points at.
            //
            // Decoupled lane: the HAL buffer binds to the PRIVATE OES texture
            // (copy source) — consumers sample the published ring slot, never
            // this texture, so the AI-lane rebind race can't reach it; the
            // lock is still taken for the release-previous path parity.
            final int bindTargetTexture = USE_DECOUPLED_ENCODER_LANE
                ? cameraOesTextureId
                : cameraTextureId;
            synchronized (cameraTextureLock) {
                boolean bound = HardwareBufferTextureBinder
                    .bindHardwareBufferToTextureNative(hwBuffer, bindTargetTexture);
                if (!bound) {
                    logger.warn("bindHardwareBufferToTexture failed — dropping frame");
                    irBindFailCount++;
                    return false;
                }
                // Bind succeeded. NOW it's safe to release the previous image —
                // the texture is no longer pointing at it.
                releasePreviousBoundImage();
                // Transfer ownership of this image+hwBuffer into the held slots.
                currentBoundImage = image;
                currentBoundHwBuffer = hwBuffer;
            }
            // Resolve the per-frame PTS. The BYD DiLink HAL on the PRIVATE
            // ImageReader path returns a stuck, different-epoch value for
            // Image.getTimestamp(), so nextFrameTimestampNs() ignores it and
            // stamps System.nanoTime() from frame 0 (single clock domain) —
            // see that method for the full rationale.
            currentFrameTimestampNs = nextFrameTimestampNs(image);
            transferredOwnership = true;
            // Bump the per-bind seq counter so the AI-lane GL thread can
            // detect a fresh frame is ready. Bumped AFTER the bind succeeds
            // so we never advertise a half-bound texture.
            cameraFrameSeq.incrementAndGet();
            return true;
        } catch (Throwable t) {
            logger.warn("consumeLatestImageAndBind error: " + t.getMessage());
            irBindFailCount++;
            return false;
        } finally {
            // Only close locally if we did NOT transfer ownership to the
            // held slots. On the success path the held slots own the refs;
            // on failure paths we close immediately to release the slot.
            if (!transferredOwnership) {
                if (hwBuffer != null) {
                    try { hwBuffer.close(); } catch (Throwable ignored) {}
                }
                if (image != null) {
                    try { image.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    /** Composite or upload the latest DiLink 5 frame into cameraTextureId. */
    private boolean consumeDiLink5Frame() {
        if (!(cameraObj
                instanceof com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend)) {
            return false;
        }
        com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend backend =
                (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend)
                        cameraObj;
        int activeTexture;
        synchronized (cameraTextureLock) {
            activeTexture =
                    backend.bindLatestFrameForOwner(cameraTextureId);
            if (activeTexture > 0) cameraTextureId = activeTexture;
        }
        if (activeTexture <= 0) return false;

        // FastCam's timestamp is only an arrival signal. Keep presentation
        // timestamps in the same monotonic clock domain as every other path.
        long candidate = System.nanoTime();
        if (candidate <= lastAcceptedPtsNs) {
            candidate = lastAcceptedPtsNs + 1_000L;
        }
        lastAcceptedPtsNs = candidate;
        currentFrameTimestampNs = candidate;
        cameraFrameSeq.incrementAndGet();
        lastRealFrameTimeSt = System.currentTimeMillis();
        if (diLink5ReverseSourcePaused) {
            diLink5ReverseSourcePaused = false;
            logger.info("DiLink 5 reverse source delivered its first "
                    + "replacement frame");
        } else if (diLink5SourceOnlyReacquire) {
            logger.info("DiLink 5 FastCam source recovery delivered its "
                    + "first replacement frame");
        }
        diLink5SourceOnlyReacquire = false;
        if (diLink5IdleResumePending || diLink5IdleResumeFailed
                || diLink5IdleResumeStartedAtUptimeMs != 0L) {
            // Under the verdict lock: a frame landing at the deadline wins over a failure verdict.
            synchronized (diLink5IdleResumeFailureLock) {
                diLink5IdleResumePending = false;
                diLink5IdleResumeFailed = false;
                diLink5IdleResumeStartedAtUptimeMs = 0L;
            }
        }
        finishDiLink5Reacquire("first replacement frame received");
        if (stallEpisodeLogged) {
            stallEpisodeLogged = false;
            stallEpisodeStartMs = 0L;
            stallEpisodeNextLogMs = 0L;
        }
        return true;
    }

    /**
     * SurfaceTexture-path equivalent of consumeLatestImageAndBind.
     * Pulls the freshest BufferQueue slot into cameraTextureId via
     * updateTexImage() and captures the SurfaceTexture timestamp for PTS.
     *
     * Mirrors oem's gl.C5920a / GL pipeline: the BYD HAL writes into the
     * SurfaceTexture-backed Surface, and updateTexImage rebinds the latest
     * frame to the EXTERNAL_OES texture. No gralloc handoff, so there's no
     * separate Image/HardwareBuffer ref to hold across GL cycles.
     *
     * Must be called on the GL thread.
     */
    private boolean consumeSurfaceTextureFrame() {
        SurfaceTexture st = cameraSurfaceTexture;
        if (st == null) return false;
        // FRESHNESS GATE. updateTexImage() silently no-ops (no throw) when the
        // BufferQueue has nothing new, so without this check a frozen HAL still
        // yields return=true → frameCounter++ → lastFrameTime refreshed →
        // stale texture re-encoded with a brand-new PTS, forever.
        //
        // SCOPE — be precise about what this does and does not fix. It is an
        // INSTRUMENTATION correctness fix, not a cure for a frozen picture:
        // renderLoop returns before every consumer pass either way, so the old
        // code redrew identical pixels where the new code draws nothing. Neither
        // creates nor cures a freeze. What it DOES fix is that four independent
        // recovery/reporting mechanisms were reading a counter that advanced on
        // render-loop ticks rather than on camera buffers: the Stats line, the
        // 4s frame-stall watchdog (structurally unable to fire — it was measuring
        // its own heartbeat), the zero-frame reopen escalation
        // (priorOpenDeliveredNoFrame permanently false), and the AI lane
        // re-scoring duplicate content. De-blinding the watchdog is the
        // load-bearing part.
        //
        // stFrameArrivalSeq only moves in onFrameAvailable, so it is the one
        // trustworthy "HAL queued a buffer" signal on this path. We still call
        // updateTexImage() unconditionally below — that is oem's continuous
        // pump (`ll/k.java:159` runs it every GL tick under renderMode=1) and
        // some byd_apa boards need the dequeue to release slots back to the
        // producer — but when no buffer arrived we report no-frame so the
        // pipeline treats the tick as a miss rather than as a camera frame.
        // Consume-without-drawing is itself OEM-sanctioned: on an fps-limited
        // tick the OEM also consumes then returns without drawing
        // (`ll/k.java:160-164`).
        long arrivalSeq = stFrameArrivalSeq.get();
        boolean freshBuffer = (arrivalSeq != stLastConsumedArrivalSeq);
        try {
            // Crash-fix: updateTexImage swaps the backing EGLImage of
            // cameraTextureId; hold cameraTextureLock so the AI lane isn't
            // mid-sampling the prior buffer when it's recycled.
            synchronized (cameraTextureLock) {
                st.updateTexImage();
            }
        } catch (IllegalStateException e) {
            // The BYD HAL can abandon the BufferQueue asynchronously (gear
            // transitions, AVM open/close). updateTexImage then throws ISE —
            // drop the frame gracefully rather than let it bubble up the GL loop.
            logger.warn("updateTexImage: BufferQueue abandoned by HAL, dropping frame: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            // BufferQueue can be in disconnected state during reopen — log and skip.
            logger.warn("updateTexImage failed: " + t.getMessage());
            return false;
        }
        // oem-parity: capture the producer's transform matrix. Forwarded
        // to the recorder + stream scaler + AI-lane downscaler so each
        // shader's uTexMatrix crops to the HAL's "live" sub-region. Without
        // this we sample any letterbox / chrome the HAL drew into the
        // producer surface.
        try {
            st.getTransformMatrix(currentTexMatrix);
        } catch (Throwable t) {
            // Fall back to identity — already initialised in the field.
            logger.warn("getTransformMatrix failed: " + t.getMessage());
        }
        // The HAL on this firmware publishes an identity matrix
        // (sx=1, sy=1, tx=0, ty=0). Our vertex layout maps NDC-bottom to
        // aTexCoord.y=0 and NDC-top to aTexCoord.y=1, while every
        // rearrangement shader treats `vTexCoord.y < 0.5` as "top half of
        // output". With identity texMatrix that conflict produces a
        // top-down flipped image on every consumer. When the matrix is
        // already a Y-flip (Android producer canonical, m[5]<0, m[13]=1),
        // the conventions line up. So: when m[5] >= 0, post-multiply a
        // Y-flip into the matrix so every shader sees the same Y-down
        // convention regardless of HAL build. (Oem hits the canonical
        // m[5]=-1 case so it doesn't need this; we have to.)
        if (currentTexMatrix[5] >= 0.0f) {
            currentTexMatrix[1]  = -currentTexMatrix[1];
            currentTexMatrix[5]  = -currentTexMatrix[5];
            currentTexMatrix[9]  = -currentTexMatrix[9];
            currentTexMatrix[13] =  1.0f - currentTexMatrix[13];
        }
        // Publish to the downscaler too. The probe shader runs on a
        // separate thread (AI-lane GL or probe GL), but the downscaler
        // instance is shared and copies the matrix internally.
        if (USE_OEM_SURFACE_TEXTURE_PATH) {
            GpuDownscaler ds = downscaler;
            if (ds != null) ds.setTextureMatrix(currentTexMatrix);
            // FoveatedCropper does not consume the matrix — its samples are
            // already in producer-space UV via the role's corner+flip remap.
            // HighResPreviewSampler is lazy-allocated; only push when present.
            // The dialog endpoint is rare so freshness within a few frames
            // is fine, but per-frame upload is cheap (memcpy under lock).
            HighResPreviewSampler hr = highResSampler;
            if (hr != null) hr.setTextureMatrix(currentTexMatrix);
        }
        // Record what we consumed and short-circuit when no genuine buffer
        // arrived. Everything below (PTS mint, cameraFrameSeq bump, probe)
        // must run ONLY for real frames — a duplicate would fabricate a new
        // timestamp for unchanged pixels and re-drive the AI lane on content
        // it has already scored.
        if (!freshBuffer) {
            return false;
        }
        stLastConsumedArrivalSeq = arrivalSeq;
        lastRealFrameTimeSt = System.currentTimeMillis();
        // Frames are flowing again — re-arm the once-per-episode stall log so a
        // LATER stall is still announced.
        if (stallEpisodeLogged) {
            stallEpisodeLogged = false;
            stallEpisodeStartMs = 0;
            stallEpisodeNextLogMs = 0;
        }
        // Refill the reopen budget only on SUSTAINED flow, not the first buffer.
        // A half-alive HAL can hand back one or two frames after a reopen and then
        // freeze again; refilling on frame 1 would let it reopen indefinitely.
        // dilink4LastStallRestartMs is deliberately NOT cleared — it is half of the
        // reopen floor's anchor and must keep its spacing.
        if (USE_DILINK4_AVM_PATH) {
            if (dilink4StallRestartAttempts != 0 || dilink4StallRecoveryExhausted) {
                if (dilink4RecoveryProofFrames == 0) {
                    dilink4RecoveryProofSinceMs = lastRealFrameTimeSt;
                }
                dilink4RecoveryProofFrames++;
                if (dilink4RecoveryProofFrames >= DILINK4_RECOVERY_PROOF_FRAMES
                        && (lastRealFrameTimeSt - dilink4RecoveryProofSinceMs)
                            >= DILINK4_RECOVERY_PROOF_MS) {
                    logger.info("dilink4 producer sustained " + dilink4RecoveryProofFrames
                        + " frames over "
                        + (lastRealFrameTimeSt - dilink4RecoveryProofSinceMs)
                        + "ms — reopen budget refilled");
                    dilink4StallRestartAttempts = 0;
                    dilink4StallRecoveryExhausted = false;
                    dilink4RecoveryProofFrames = 0;
                    dilink4RecoveryProofSinceMs = 0L;
                    dilink4DeathReopenStreak = 0;
                    dilink4ParkedRebuildSkipLogged = false;
                }
            } else if (dilink4RecoveryProofFrames != 0) {
                dilink4RecoveryProofFrames = 0;
                dilink4RecoveryProofSinceMs = 0L;
            }
        }
        if (!firstFrameDimsLogged) {
            firstFrameDimsLogged = true;
            logFirstFrameDims(st);
        }
        // oem-parity: PTS comes from System.nanoTime() unconditionally.
        // oem's GL pipeline (C7411k) never trusts SurfaceTexture.getTimestamp;
        // it stamps frames at the moment of capture on the consumer thread.
        // The hwTs/latch state machine on the legacy ImageReader path exists
        // because gralloc's Image.getTimestamp returns a stuck value on this
        // HAL — same trap exists on SurfaceTexture, but oem proves nanoTime
        // is the right answer either way. Apply the same monotonic +1us
        // guard to satisfy MediaCodec's strictly-increasing PTS contract.
        long candidate = System.nanoTime();
        if (candidate <= lastAcceptedPtsNs) {
            candidate = lastAcceptedPtsNs + 1_000L;
        }
        lastAcceptedPtsNs = candidate;
        currentFrameTimestampNs = candidate;
        cameraFrameSeq.incrementAndGet();

        // dilink4 black-frame probe: every 30 frames, render a 4x4 region
        // of the OES texture into a 1x1 RGBA8 FBO and read back the pixel.
        // Tells us whether the buffer the HAL handed us has actual content
        // or is uniform/zero. Zero overhead for legacy fleet (gated on
        // USE_OEM_SURFACE_TEXTURE_PATH).
        if (USE_OEM_SURFACE_TEXTURE_PATH) {
            probeOesPixel();
        }
        return true;
    }

    // ==================== DILINK 4 PIXEL PROBE ====================
    //
    // Read one pixel from the OES texture every 30 frames to disambiguate
    // black-frame causes. Logs RGB. The probe samples (0.5, 0.5) of the
    // producer surface — middle of the configured strip. If the HAL is
    // delivering buffers with real content, R/G/B will vary frame to
    // frame. If the HAL is delivering all-zero buffers, R=G=B=0 forever.
    // If the HAL froze on a stale frame, R/G/B will be constant non-zero.

    private int probeFbo = 0;
    private int probeColorTex = 0;
    private int probeProgram = 0;
    private int probeAPosLoc = -1;
    private int probeATexLoc = -1;
    private int probeUTexSamplerLoc = -1;
    private int probeUTexMatrixLoc = -1;
    private final java.nio.FloatBuffer probeQuadVerts =
        java.nio.ByteBuffer.allocateDirect(16 * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer();
    private final java.nio.ByteBuffer probeReadBuffer =
        java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder());
    private long probeFrameCount = 0L;
    private long probeNonZeroFrames = 0L;
    private long probeLastLogMs = 0L;
    private static final long PROBE_LOG_INTERVAL_MS = 5_000L;
    private static final int PROBE_EVERY_N_FRAMES = 30;

    private static final String PROBE_VS =
        "attribute vec2 aPos;\n" +
        "attribute vec2 aTex;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "varying vec2 vTex;\n" +
        "void main() {\n" +
        "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
        "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
        "}";

    private static final String PROBE_FS =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES uTex;\n" +
        "varying vec2 vTex;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(uTex, vTex);\n" +
        "}";

    private boolean ensureProbeResources() {
        if (probeFbo != 0) return true;
        try {
            // 1x1 RGBA8 color attachment.
            int[] tex = new int[1];
            android.opengl.GLES20.glGenTextures(1, tex, 0);
            probeColorTex = tex[0];
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, probeColorTex);
            android.opengl.GLES20.glTexImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0,
                android.opengl.GLES20.GL_RGBA, 1, 1, 0,
                android.opengl.GLES20.GL_RGBA, android.opengl.GLES20.GL_UNSIGNED_BYTE, null);
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR);
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR);
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, 0);

            int[] fbo = new int[1];
            android.opengl.GLES20.glGenFramebuffers(1, fbo, 0);
            probeFbo = fbo[0];
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, probeFbo);
            android.opengl.GLES20.glFramebufferTexture2D(android.opengl.GLES20.GL_FRAMEBUFFER,
                android.opengl.GLES20.GL_COLOR_ATTACHMENT0,
                android.opengl.GLES20.GL_TEXTURE_2D, probeColorTex, 0);
            int status = android.opengl.GLES20.glCheckFramebufferStatus(android.opengl.GLES20.GL_FRAMEBUFFER);
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0);
            if (status != android.opengl.GLES20.GL_FRAMEBUFFER_COMPLETE) {
                logger.warn("probe FBO not complete: " + status);
                return false;
            }

            probeProgram = GlUtil.createProgram(PROBE_VS, PROBE_FS);
            probeAPosLoc = android.opengl.GLES20.glGetAttribLocation(probeProgram, "aPos");
            probeATexLoc = android.opengl.GLES20.glGetAttribLocation(probeProgram, "aTex");
            probeUTexSamplerLoc = android.opengl.GLES20.glGetUniformLocation(probeProgram, "uTex");
            probeUTexMatrixLoc = android.opengl.GLES20.glGetUniformLocation(probeProgram, "uTexMatrix");

            // Full-screen quad. NDC pos + UV (0..1).
            probeQuadVerts.put(new float[]{
                -1f, -1f,  0f, 0f,
                 1f, -1f,  1f, 0f,
                -1f,  1f,  0f, 1f,
                 1f,  1f,  1f, 1f,
            }).position(0);

            logger.info("dilink4 OES pixel probe initialized");
            return true;
        } catch (Throwable t) {
            logger.warn("ensureProbeResources failed: " + t.getMessage());
            probeFbo = 0;
            return false;
        }
    }

    private void probeOesPixel() {
        probeFrameCount++;
        if (probeFrameCount % PROBE_EVERY_N_FRAMES != 0) return;
        if (!ensureProbeResources()) return;
        if (cameraTextureId == 0) return;

        try {
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, probeFbo);
            android.opengl.GLES20.glViewport(0, 0, 1, 1);
            android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f);
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT);

            android.opengl.GLES20.glUseProgram(probeProgram);

            android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0);
            android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
            android.opengl.GLES20.glUniform1i(probeUTexSamplerLoc, 0);
            android.opengl.GLES20.glUniformMatrix4fv(probeUTexMatrixLoc, 1, false, currentTexMatrix, 0);

            probeQuadVerts.position(0);
            android.opengl.GLES20.glVertexAttribPointer(probeAPosLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, probeQuadVerts);
            probeQuadVerts.position(2);
            android.opengl.GLES20.glVertexAttribPointer(probeATexLoc, 2, android.opengl.GLES20.GL_FLOAT, false, 16, probeQuadVerts);
            android.opengl.GLES20.glEnableVertexAttribArray(probeAPosLoc);
            android.opengl.GLES20.glEnableVertexAttribArray(probeATexLoc);

            android.opengl.GLES20.glDrawArrays(android.opengl.GLES20.GL_TRIANGLE_STRIP, 0, 4);

            android.opengl.GLES20.glDisableVertexAttribArray(probeAPosLoc);
            android.opengl.GLES20.glDisableVertexAttribArray(probeATexLoc);

            probeReadBuffer.position(0);
            android.opengl.GLES20.glReadPixels(0, 0, 1, 1,
                android.opengl.GLES20.GL_RGBA,
                android.opengl.GLES20.GL_UNSIGNED_BYTE, probeReadBuffer);

            int r = probeReadBuffer.get(0) & 0xFF;
            int g = probeReadBuffer.get(1) & 0xFF;
            int b = probeReadBuffer.get(2) & 0xFF;
            int a = probeReadBuffer.get(3) & 0xFF;

            if (r != 0 || g != 0 || b != 0) probeNonZeroFrames++;

            long now = System.currentTimeMillis();
            if (now - probeLastLogMs >= PROBE_LOG_INTERVAL_MS) {
                probeLastLogMs = now;
                long sampled = probeFrameCount / PROBE_EVERY_N_FRAMES;
                logger.info("OES-PROBE: frame=" + cameraFrameSeq.get()
                    + " sampled=" + sampled
                    + " nonZero=" + probeNonZeroFrames
                    + " last RGBA=(" + r + "," + g + "," + b + "," + a + ")");
            }
        } catch (Throwable t) {
            // Non-fatal — don't take down the render loop on a probe error.
            logger.warn("probeOesPixel error: " + t.getMessage());
        } finally {
            android.opengl.GLES20.glBindFramebuffer(android.opengl.GLES20.GL_FRAMEBUFFER, 0);
        }
    }

    /** First-frame diagnostic on the SurfaceTexture path. SurfaceTexture
     *  itself doesn't expose the producer-side W×H, but the transform
     *  matrix encodes the U/V scale the GL shader has to apply to sample
     *  the live region. With BYD HAL emitting the full configured strip,
     *  the diagonal entries (sx, sy) are very close to 1.0; if the HAL is
     *  cropping (e.g., delivering a 2x2 mosaic into a 5120x960 surface),
     *  the scale will be < 1.0 on one or both axes and that surfaces here
     *  before we waste cycles wondering why the recorded mosaic looks
     *  squashed. Cheap — one float[16] read per cold attach. */
    /** Effective HAL-emit dims derived from the SurfaceTexture transform
     *  matrix on the first frame after each (re)attach. {@code -1} until
     *  the first frame arrives. Volatile because the GL thread writes and
     *  HTTP / pipeline threads can read for diagnostics. */
    private volatile int halEffectiveWidth = -1;
    private volatile int halEffectiveHeight = -1;
    public int getHalEffectiveWidth() { return halEffectiveWidth; }
    public int getHalEffectiveHeight() { return halEffectiveHeight; }

    private void logFirstFrameDims(SurfaceTexture st) {
        try {
            float[] m = new float[16];
            st.getTransformMatrix(m);
            // Standard SurfaceTexture matrix: row-major OpenGL form, where
            // the diagonal m[0]/m[5] are X/Y scale and m[12]/m[13] are
            // X/Y translation. Sign of sy is usually negative (Y flip).
            float sx = m[0];
            float sy = m[5];
            float tx = m[12];
            float ty = m[13];
            // Effective sampled region in producer coords: |sx|×|sy| of
            // the surface, offset by (tx, ty). Scale 1.0 = full surface.
            float effW = Math.abs(sx) * (float) width;
            float effH = Math.abs(sy) * (float) height;
            halEffectiveWidth = Math.round(effW);
            halEffectiveHeight = Math.round(effH);
            logger.info(String.format(java.util.Locale.US,
                "First frame transform: sx=%.4f sy=%.4f tx=%.4f ty=%.4f → "
                + "crop covers %.0f%%x%.0f%% of the producer buffer"
                + " (configured %dx%d — NOT a size check, see below)",
                sx, sy, tx, ty,
                Math.abs(sx) * 100f, Math.abs(sy) * 100f, width, height));
            // Cross-correlate effective dims with the configured pipeline
            // viewport. oem's encoder adapts to whatever the HAL emits;
            // we instead pin a fixed encoder viewport (Seal: 2560×1920),
            // so a delta > 5% on either axis means the recorder is going
            // to stretch/squish content to fill the encoder. Surface the
            // warning loudly so the operator picks the right cameraMode
            // / camera profile rather than wondering why the recording
            // looks squashed.
            // CAVEAT — this comparison CANNOT detect a producer-size mismatch,
            // and must not be read as if it could. effW = |sx| * width, so
            // effW/width reduces to |sx| exactly and the configured dims cancel
            // out. Because createCameraSurfaceTexture deliberately skips
            // setDefaultBufferSize, the producer owns the buffer and the crop
            // rect is normalised against THAT buffer, so |sx| = |sy| = 1.0
            // whatever the true size is. This test therefore only ever catches
            // a HAL that sets a sub-rect crop on its own buffer — a
            // wrong-sized buffer with a full-surface crop reads as a perfect
            // match. The only reliable producer-size signal on this path is the
            // HAL's own byte callback (see halBytePathWidth/Height).
            float wRatio = effW / (float) Math.max(1, width);
            float hRatio = effH / (float) Math.max(1, height);
            float wDeviation = Math.abs(wRatio - 1.0f);
            float hDeviation = Math.abs(hRatio - 1.0f);
            if (wDeviation > 0.05f || hDeviation > 0.05f) {
                logger.warn(String.format(java.util.Locale.US,
                    "ENCODER DIM MISMATCH: HAL effective %.0fx%.0f vs configured "
                    + "%dx%d (deviation: %.1f%% width, %.1f%% height). The "
                    + "recorder/streamer/AI lane will rescale content into the "
                    + "fixed viewport, which may stretch or squish the output. "
                    + "If this car ships a different mosaic shape, switch "
                    + "cameraMode (Default vs DiLink 4) or update the camera "
                    + "profile's panoWidth/panoHeight to match.",
                    effW, effH, width, height,
                    wDeviation * 100f, hDeviation * 100f));
            }
            if (Math.abs(Math.abs(sx) - 1.0f) > 0.01f
                    || Math.abs(Math.abs(sy) - 1.0f) > 0.01f) {
                logger.warn("HAL is delivering a CROPPED region of the surface — "
                    + "if you expected a 4-quadrant 5120x960 strip and effective "
                    + "is closer to half on either axis, the HAL is in 2x2 "
                    + "mosaic mode (or some non-strip layout). Consider trying "
                    + "a different previewIndex (cameraSurfaceMode) or cameraId.");
            }
        } catch (Throwable t) {
            logger.warn("First-frame transform-matrix probe failed: " + t.getMessage());
        }
    }

    /**
     * Resolve the per-frame PTS. Returns System.nanoTime() from frame 0 in a
     * single clock domain (the BYD HAL timestamp is not trusted on this fleet),
     * so no mid-stream clock-domain transition can ever corrupt the muxer's
     * rebase math. Output is strictly monotonic (MediaCodec requires it).
     *
     * The {@code image} argument is used only for a one-shot diagnostic log of
     * the (unused) HAL timestamp on the first frame.
     *
     * MUST be called from the GL thread (renderLoop).
     */
    private long nextFrameTimestampNs(Image image) {
        // SINGLE CLOCK DOMAIN — System.nanoTime() from frame 0, unconditionally.
        //
        // History: this path used to trust Image.getTimestamp() (HW sensor
        // clock) until it detected the value was stuck, then LATCHED to
        // System.nanoTime() after STUCK_HW_TS_FRAMES. That mid-stream HW→NANO
        // transition is the root of the "55 min – 1 hr clip duration" bug: the
        // two clocks have different epochs (HW ≈ uptime µs ~52ms; nanoTime ≈
        // CLOCK_MONOTONIC ns, billions), so when the encoder muxer rebases a
        // post-transition frame against an origin captured pre-transition it
        // records a multi-billion-µs gap as literal playback time. The
        // transition was especially likely right after a camera/encoder restart
        // (SD unmount, GL watchdog, ACC bounce) where the latch re-evaluates
        // while a muxer origin is already seeded.
        //
        // The latch machine ALWAYS ended at System.nanoTime() anyway — the BYD
        // PRIVATE-ImageReader HAL never honors the timestamp contract on this
        // fleet. The sibling oem SurfaceTexture path (renderLoop, search
        // "oem-parity") already stamps System.nanoTime() from frame 0 and its
        // comment documents that nanoTime "is the right answer either way."
        // Using it from the first frame here too eliminates the domain
        // transition entirely — there is exactly one clock domain for the whole
        // session, so no rebase can ever see a cross-epoch jump. The muxer-level
        // re-anchor guard in HardwareEventRecorderGpu.writeRebased remains as a
        // belt-and-suspenders net for any other producer.
        //
        // One-shot diagnostic: log the HAL timestamp we're deliberately NOT
        // using, so field logs still show what the sensor clock was doing.
        if (!ptsSourceLogged) {
            long hwTs = 0;
            try { hwTs = image.getTimestamp(); } catch (Throwable ignored) {}
            logger.info("PTS source: System.nanoTime() unconditional (single-domain). "
                + "HAL Image.getTimestamp() first value=" + hwTs + "ns (NOT used — "
                + "BYD PRIVATE-ImageReader HAL doesn't honor the timestamp contract)");
            ptsSourceLogged = true;
            ptsDomain = PTS_DOMAIN_NANO;
        }
        long candidate = System.nanoTime();
        // Monotonic guard. MediaCodec rejects non-increasing PTS; a duplicate
        // or rewind within the (now single) nanoTime domain gets bumped +1us.
        if (candidate <= lastAcceptedPtsNs) {
            candidate = lastAcceptedPtsNs + 1_000L;
        }
        lastAcceptedPtsNs = candidate;
        return candidate;
    }

    private void releasePreviousBoundImage() {
        if (currentBoundHwBuffer != null) {
            try { currentBoundHwBuffer.close(); } catch (Throwable ignored) {}
            currentBoundHwBuffer = null;
        }
        if (currentBoundImage != null) {
            try { currentBoundImage.close(); } catch (Throwable ignored) {}
            currentBoundImage = null;
        }
    }

    private boolean consumeLatestWindshieldImageAndBind() {
        ImageReader reader = windshieldImageReader;
        if (reader == null || windshieldTextureId == 0) return false;
        Image image = null;
        HardwareBuffer hwBuffer = null;
        boolean transferredOwnership = false;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return false;
            hwBuffer = image.getHardwareBuffer();
            if (hwBuffer == null) return false;
            boolean bound = HardwareBufferTextureBinder
                .bindHardwareBufferToTextureNative(hwBuffer, windshieldTextureId);
            if (!bound) return false;
            releasePreviousBoundWindshieldImage();
            windshieldBoundImage = image;
            windshieldBoundHwBuffer = hwBuffer;
            windshieldFrameReady = true;
            windshieldFrameCount++;
            windshieldLastFrameMs = System.currentTimeMillis();
            transferredOwnership = true;
            return true;
        } catch (Throwable t) {
            logger.warn("consumeLatestWindshieldImageAndBind error: " + t.getMessage());
            return false;
        } finally {
            if (!transferredOwnership) {
                if (hwBuffer != null) {
                    try { hwBuffer.close(); } catch (Throwable ignored) {}
                }
                if (image != null) {
                    try { image.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    private void releasePreviousBoundWindshieldImage() {
        if (windshieldBoundHwBuffer != null) {
            try { windshieldBoundHwBuffer.close(); } catch (Throwable ignored) {}
            windshieldBoundHwBuffer = null;
        }
        if (windshieldBoundImage != null) {
            try { windshieldBoundImage.close(); } catch (Throwable ignored) {}
            windshieldBoundImage = null;
        }
    }

    // ==================== Decoupled encoder lane (GL thread) ====================

    /**
     * Flag-on replacement for PASS 1A. Runs on the GL render thread with the
     * freshly-bound HAL frame on {@code cameraOesTextureId}.
     *
     * Sequence per frame:
     *   1. Windshield camera work (open/drain/stall-guard) — camera-side, so
     *      it STAYS on this thread; only the encoder draw moved to the lane.
     *   2. Blit camera OES → ring slot; blit windshield OES → its ring on a
     *      FRESH windshield frame only (the previous slot stays valid for
     *      re-composition — re-blitting a released OES source would be the
     *      exact use-after-free this design removes).
     *   3. COMPLETION BARRIER: CopiedFrameRing.copyFrom glFinish-es INSIDE
     *      its write reservation before returning, covering three needs at
     *      once. (a) Cross-context completeness: the lane and AI contexts
     *      may sample the slot immediately after publish, and glFlush only
     *      SUBMITS the blit — sampling a half-written slot is a visible-
     *      corruption race. (b) Camera-buffer lifetime: the held Image/
     *      HardwareBuffer must not close before the GPU retired the blit
     *      that sampled it. (c) Reservation integrity: writingSlot must stay
     *      held until the write COMPLETES, or a stale lane packet can pin
     *      the slot mid-write (review round 2, finding 2). The barrier is
     *      bounded (~1-2 ms; only this frame's blits are queued) and is the
     *      deliberately-simple v1 choice; per-slot fences waited on in EACH
     *      consumer context are the later optimization — a producer-side
     *      fence alone would cover only (b).
     *   4. Close the camera-owned buffers inline — the barrier just proved
     *      the GPU is done with them, so their gralloc slots go straight
     *      back to the BYD HAL producer pool.
     *   5. Republish cameraTextureId = ring slot under cameraTextureLock
     *      (same discipline as the DiLink 5 compositor path) for PASS
     *      1B/1C/AI and the pixel probes.
     *   6. Submit the packet to the EncoderLane (recorder gate applies; the
     *      stride gate is applied lane-side for parity).
     */
    private void runDecoupledLanePass(GpuMosaicRecorder localRecorder) {
        CopiedFrameRing camRing = decoupledCamRing;
        if (camRing == null || !camRing.isInitialized() || cameraOesTextureId == 0) {
            return;
        }

        boolean recorderGateOpen = localRecorder != null
            && (recorderLaneEnabled || localRecorder.isRecording());

        boolean wsFresh = false;
        if (recorderGateOpen) {
            updateWindshieldCameraOnGlThread();
            // Unconditional drain — same lost-update-race rationale as the
            // legacy PASS 1A block (gralloc slots fill → HAL producer stalls).
            if (windshieldStarted) {
                wsFresh = consumeLatestWindshieldImageAndBind();
                windshieldPending = false;
            }
            // Windshield stall guard — verbatim behaviour from PASS 1A.
            if (windshieldStarted && windshieldFrameReady
                    && windshieldLastFrameMs > 0
                    && (System.currentTimeMillis() - windshieldLastFrameMs)
                        > WINDSHIELD_STALL_THRESHOLD_MS) {
                long stalledMs = System.currentTimeMillis() - windshieldLastFrameMs;
                logger.warn("Windshield feed stalled " + stalledMs
                    + "ms (frames=" + windshieldFrameCount
                    + ") — falling back to 360 front + scheduling reopen");
                windshieldFrameReady = false;
                long nowReopen = System.currentTimeMillis();
                if (nowReopen - windshieldLastReopenMs > WINDSHIELD_REOPEN_MIN_INTERVAL_MS) {
                    windshieldLastReopenMs = nowReopen;
                    try {
                        stopWindshieldCameraOnGlThread();
                        windshieldOpenFailed = false;
                    } catch (Throwable t) {
                        logger.warn("Windshield reopen (stop phase) failed: " + t.getMessage());
                    }
                }
            }
        } else if (windshieldStarted) {
            // Same teardown as legacy PASS 1A's else-branch: with the recorder
            // lane off, nothing would drain the windshield reader — its slots
            // fill and the HAL producer stalls. runDecoupledLanePass keeps the
            // pano copy flowing for PASS 1B/1C/AI regardless.
            stopWindshieldCameraOnGlThread();
        }

        // Camera blit. On failure keep legacy lifetime semantics: the bound
        // Image stays in currentBoundImage and is released at the next bind.
        int camSlot = camRing.copyFrom(cameraOesTextureId);
        if (camSlot < 0) {
            return;
        }

        // Windshield blit on fresh frames only.
        boolean wsCopiedThisFrame = false;
        if (recorderGateOpen && wsFresh && windshieldFrameReady) {
            if (decoupledWsRing == null) {
                CopiedFrameRing ring = new CopiedFrameRing("WsRing", 3, 1920, 1080);
                if (ring.init()) {
                    decoupledWsRing = ring;
                } else {
                    logger.warn("Windshield copy ring init failed — dashcam top band"
                        + " falls back to the 360 front slice");
                }
            }
            if (decoupledWsRing != null && decoupledWsRing.isInitialized()) {
                int slot = decoupledWsRing.copyFrom(windshieldTextureId);
                if (slot >= 0) {
                    decoupledLastWsSlot = slot;
                    wsCopiedThisFrame = true;
                }
            }
        }

        // Inline release behind the ring's completion barrier (see method
        // doc, steps 3-4): every successful copyFrom above glFinish-ed before
        // returning, so the GPU has provably retired the blits that sampled
        // these buffers. The windshield Image is only closed when its pixels
        // were captured into the ring THIS frame — otherwise it stays held
        // (legacy release-at-next-bind), because the OES texture must keep a
        // live backing buffer until a fresh frame replaces it.
        closeHeldCameraBuffersAfterBarrier(wsCopiedThisFrame);

        // Publish. AI lane samples under this same lock (DiLink5 parity).
        int slotTex = camRing.textureOf(camSlot);
        if (slotTex != 0) {
            synchronized (cameraTextureLock) {
                cameraTextureId = slotTex;
            }
        }

        if (recorderGateOpen) {
            EncoderLane lane = encoderLane;
            if (lane != null) {
                boolean wsUsable = windshieldStarted && windshieldFrameReady
                    && decoupledLastWsSlot >= 0
                    && decoupledWsRing != null;
                lane.submit(new EncoderLane.Frame(
                    camSlot,
                    slotTex,
                    camRing.generationOf(camSlot),
                    wsUsable ? decoupledLastWsSlot : -1,
                    wsUsable ? decoupledWsRing.textureOf(decoupledLastWsSlot) : 0,
                    wsUsable ? decoupledWsRing.generationOf(decoupledLastWsSlot) : -1,
                    wsUsable,
                    currentFrameTimestampNs,
                    cameraFrameSeq.get()));
            }
        }
    }

    /**
     * Closes the held camera (and, when copied this frame, windshield)
     * buffers. MUST be called only after the publish barrier (glFinish) has
     * proven the GPU retired the blits that sampled them — closing earlier
     * returns a gralloc slot the GPU may still be reading, the exact
     * use-after-free class the cameraTextureLock comments document.
     * GL thread only.
     */
    private void closeHeldCameraBuffersAfterBarrier(boolean wsCopiedThisFrame) {
        if (currentBoundHwBuffer != null) {
            try { currentBoundHwBuffer.close(); } catch (Throwable ignored) { }
            currentBoundHwBuffer = null;
        }
        if (currentBoundImage != null) {
            try { currentBoundImage.close(); } catch (Throwable ignored) { }
            currentBoundImage = null;
        }
        if (wsCopiedThisFrame) {
            if (windshieldBoundHwBuffer != null) {
                try { windshieldBoundHwBuffer.close(); } catch (Throwable ignored) { }
                windshieldBoundHwBuffer = null;
            }
            if (windshieldBoundImage != null) {
                try { windshieldBoundImage.close(); } catch (Throwable ignored) { }
                windshieldBoundImage = null;
            }
        }
    }

    /** Lazily creates the EncoderLane (thread starts on first initRecorder). */
    private synchronized EncoderLane ensureEncoderLane() {
        if (encoderLane != null) {
            return encoderLane;
        }
        EGLCore core = eglCore;
        if (core == null) {
            logger.error("EncoderLane requested before GL init");
            return null;
        }
        encoderLane = new EncoderLane(
            core,
            () -> decoupledCamRing,
            () -> decoupledWsRing,
            () -> recorderLaneEnabled,
            () -> recorderFrameStride,
            () -> {
                BydCameraCoordinator c = cameraCoordinator;
                return c != null && c.isNativeAppActive();
            },
            restartInProgress);
        return encoderLane;
    }

    /**
     * Pipeline live-reconfiguration support (decoupled lane only): releases
     * the recorder's encoder EGL surface ON THE LANE THREAD, serialized with
     * draws by the lane handler's FIFO ordering. Posting this to the render
     * thread — the legacy pipeline behaviour — would destroy a surface the
     * lane may be mid-eglSwapBuffers on (release-blocker review, finding 1).
     *
     * @return true when the release ran within the deadline. A lane that was
     *         never started holds no recorder GL, so there is nothing to
     *         race — reported as success.
     */
    public boolean releaseRecorderEncoderSurfaceOnLane(GpuMosaicRecorder rec,
            long timeoutMs) {
        if (!USE_DECOUPLED_ENCODER_LANE || rec == null) {
            return false;
        }
        EncoderLane lane = encoderLane;
        if (lane == null) {
            return true;
        }
        return lane.runOnLane(() -> {
            try {
                rec.releaseEncoderSurface();
                logger.info("Recorder encoder surface released on EncoderLane thread");
            } catch (Exception e) {
                logger.warn("Error releasing recorder surface (lane): " + e.getMessage());
            }
        }, timeoutMs);
    }

    /**
     * Pipeline live-reconfiguration support (decoupled lane only):
     * synchronous recorder (re)initialization against the lane context —
     * init, contention-probe wiring, and adoption of the new recorder AND
     * encoder refs by the lane, all as one serialized lane operation. Without
     * the adoption step the lane keeps drawing against the released codec
     * (release-blocker review, finding 1).
     *
     * @return null on success, otherwise the failure to rethrow.
     */
    public Exception reinitRecorderOnEncoderLane(GpuMosaicRecorder rec,
            HardwareEventRecorderGpu enc, long timeoutMs) {
        if (!USE_DECOUPLED_ENCODER_LANE) {
            return new IllegalStateException("decoupled encoder lane not active");
        }
        EncoderLane lane = ensureEncoderLane();
        if (lane == null) {
            return new IllegalStateException("EncoderLane unavailable");
        }
        // Keep this pipeline's encoder field in step for the lane path only —
        // the legacy path deliberately keeps its historical behaviour.
        this.encoder = enc;
        return lane.initRecorderAndWait(rec, enc, timeoutMs);
    }

    // ============================================================================

    /** Periodic diagnostic for the ImageReader path. Throttled to align with
     *  the 2-minute Stats log so it rides along instead of spamming. */
    private void maybeLogImageReaderDiag() {
        long now = System.currentTimeMillis();
        if (now - lastIrDiagLogMs < STATS_INTERVAL_MS) return;
        lastIrDiagLogMs = now;
        logger.info(String.format(
            "IR-diag: fire=%d acqOk=%d acqNull=%d bindFail=%d",
            irFireCount, irAcquireOkCount, irAcquireNullCount, irBindFailCount));
    }
    
    /**
     * Main render loop - distributes frames to recording and AI lanes.
     */
    private void renderLoop() {
        if (!running) {
            return;
        }

        try {
            // Wait for new frame (hardware sync). Skip the wait if either
            // path already signaled while we were processing the previous
            // frame — otherwise the unconditional wait() would miss that
            // notify and park us until the NEXT HAL fire, capping FPS.
            // imagePending is set by ImageReader, stFramePending by
            // SurfaceTexture, and diLink5FramePending by the native bridge.
            synchronized (frameSync) {
                if (!imagePending
                        && !stFramePending
                        && !diLink5FramePending) {
                    try {
                        // FIX H4: 250 ms timeout (was 100 ms). The watchdog
                        // owns frame-stall detection at its own 5 s cadence;
                        // the timeout here only paces how often we re-check
                        // running.get(). 100 ms produced ~10 idle wakeups/s
                        // when the camera HAL was paused (e.g. during ACC-off
                        // teardown latency); 250 ms cuts that to ~4/s with no
                        // user-visible behaviour change.
                        frameSync.wait(250);
                    } catch (InterruptedException e) {
                        // Continue
                    }
                }
                imagePending = false;
                stFramePending = false;
                diLink5FramePending = false;
            }

            if (!running) {
                return;
            }

            // Update watchdog heartbeat
            lastGlThreadHeartbeat = System.currentTimeMillis();
            maybeLogImageReaderDiag();

            // SOTA: Skip frame processing if camera is yielded to native app,
            // not yet open, or being torn down/reopened by the daemon thread
            // (reopenCamera/restartCameraAfterError). The restartInProgress
            // gate is essential — without it the GL thread can race the
            // daemon thread's close and block in updateTexImage() against a
            // dead BufferQueue, freezing the GL thread until the watchdog
            // kills the process.
            if (isCameraFrameConsumptionPaused()
                    || diLink5SafetyDisabled
                    || cameraObj == null
                    || restartInProgress.get()
                    || diLink5CloseInProgress) {
                // GL thread stays alive but doesn't touch camera — waiting for re-acquire
                return;
            }

            // Bind the latest camera frame to cameraTextureId. Three paths:
            //   - DiLink 5: DMA-BUF compositor (or CPU upload fallback) into 2D.
            //   - oem SurfaceTexture: updateTexImage() pulls the most recent
            //     BufferQueue slot into the EXTERNAL_OES texture. PTS comes
            //     from SurfaceTexture.getTimestamp().
            //   - legacy ImageReader: acquireLatestImage + getHardwareBuffer
            //     + glEGLImageTargetTexture2DOES on the gralloc buffer.
            // Both run on the GL thread (current EGL context). If no new
            // frame is ready (spurious wakeup or notify race), return — the
            // finally re-posts the loop and we wait again.
            long stageT0 = System.nanoTime();
            if (USE_DILINK5_QCARCAM_PATH) {
                if (!consumeDiLink5Frame()) {
                    return;
                }
            } else if (USE_OEM_SURFACE_TEXTURE_PATH) {
                if (cameraSurfaceTexture == null) {
                    return;
                }
                if (!consumeSurfaceTextureFrame()) {
                    return;
                }
            } else {
                if (cameraImageReader == null) {
                    return;
                }
                if (USE_CAMERA2_AVM_PATH && !camera2FrameDue()) {
                    return;
                }
                if (!consumeLatestImageAndBind()) {
                    return;
                }
            }
            long stageAfterAcquireNs = System.nanoTime();
            frameCounter++;
            lastFrameTime = System.currentTimeMillis();
            firstFrameReceived = true;
            consecutiveContentionStalls = 0;  // Frames flowing — clear stall counter
            consecutiveZeroFrameRestarts = 0; // Real frame arrived — reopen succeeded; clear escalation counter
            if (deadSlotResumePending) {
                deadSlotResumePending = false;
                postDeadSlotResume();
            }

            // Dead-slot fallback (issue #170): deliberately NO persist here.
            // A first frame proves delivery, not content — every other persist
            // site demands pixel evidence first. The switch re-enabled frame
            // validation (advanceToNextCandidateCameraId sets
            // skipFrameValidation=false), so the frame-15/50 blocks below own
            // validating AND persisting the recovered id — including their
            // non-black readback and the frame-50 manual-override guard.
            if (deadSlotWalkActive && frameCounter == 1) {
                logger.info("Dead-slot fallback: camera id "
                    + (cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID)
                    + " delivered its first frame — frame-15/50 validation will"
                    + " confirm content before anything is persisted");
            }

            // DiLink 5 is fed by fast_cam_capture, not the legacy camera HAL.
            // Never sweep camera IDs or run blocking pixel probes against it.
            if (USE_DILINK5_QCARCAM_PATH) {
                probeComplete = true;
                autoProbeCameras = false;
                skipFrameValidation = true;
            }
            // Camera2 opens only the AVM super-frame camera: no id sweep or pixel probe.
            if (USE_CAMERA2_AVM_PATH) {
                probeComplete = true;
                autoProbeCameras = false;
                skipFrameValidation = true;
                noteCamera2FrameDelivered();
            }

            // SOTA: Full-matrix auto-probe at frame 15 (~2 sec).
            // Sweeps camera IDs 0-5 × surface modes 0-5 to find the first
            // combination that produces panoramic image data. Each combo gets
            // 15 frames to warm up before pixel readback.
            // downscaler.isInitialized(): structural guard against a dead
            // probe path — readPixels() returns null when the downscaler's
            // private thread failed init or was released, and this block
            // reads null as BLACK, which would misdiagnose a working camera.
            // Belt (skipFrameValidation=true set at init-failure) AND
            // suspenders (this check), because the dead-slot walk resets
            // skipFrameValidation=false in advanceToNextCandidateCameraId.
            if (frameCounter == 15 && downscaler != null && downscaler.isInitialized()
                    && !skipFrameValidation && !USE_DILINK5_QCARCAM_PATH) {
                try {
                    byte[] probe = downscaler.readPixels(cameraTextureId, 8, 8);
                    boolean hasData = false;
                    if (probe != null) {
                        for (int i = 0; i < Math.min(probe.length, 192); i++) {
                            if ((probe[i] & 0xFF) > 10) { hasData = true; break; }
                        }
                    }
                    int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                    boolean isDilink5 = com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled();
                    boolean isPanoramic = isDilink5 || (width >= 5000);
                    logger.info("Camera ID " + currentId + " probe: " + 
                        (hasData ? "HAS DATA" : "BLACK") +
                        " | resolution=" + width + "x" + height +
                        " | type=" + (isPanoramic ? "PANORAMIC" : "SINGLE") +
                        " | surfaceMode=" + cameraSurfaceMode);
                    
                    if (hasData && isPanoramic) {
                        // Track this camera as having real data (for fallback if strip check fails)
                        lastDataCameraId = currentId;
                        
                        // During auto-probe: accept the first camera with non-black panoramic data.
                        // The 5120x960 resolution IS the panoramic strip identifier on BYD — no other
                        // camera output uses this resolution with real image data. The luma-based
                        // strip check was producing false negatives in low-light/uniform scenes.
                        if (autoProbeCameras) {
                            logger.info("Auto-probe: SELECTED camera ID " + currentId + 
                                " (panoramic data confirmed, surfaceMode=" + cameraSurfaceMode + ")");
                            autoProbeCameras = false;
                            probeStartId = -1;
                            probeComplete = true;
                            lastDataCameraId = -1;
                            logger.info("Probe complete — recording/streaming/AI lanes now active");
                            if (probeCallback != null) {
                                probeCallback.onCameraFound(currentId, cameraSurfaceMode);
                            }
                        } else {
                            // Not in auto-probe mode — this is the frame-15 check for a saved config.
                            // Camera has data at panoramic resolution — it's working correctly.
                            // No further validation needed (skipFrameValidation handles saved configs,
                            // but this path covers the default camera ID 1 on first boot).
                            probeComplete = true;
                        }
                    } else if (autoProbeCameras) {
                        // Advance to next combination in the matrix
                        advanceProbeToNext(currentId);
                    } else if (!hasData) {
                        // Saved config gave black frames at frame 15. This could be:
                        // 1. HAL warmup (normal — wait longer)
                        // 2. OEM dashcam contention (transient)
                        // 3. Genuinely wrong camera ID (BmmCameraInfo returned wrong value)
                        //
                        // Don't re-probe immediately (causes OEM dashcam "no signal").
                        // Instead, schedule a second check at frame 50 (~5s). If still black
                        // at that point, the saved config is genuinely wrong and we re-probe.
                        logger.warn("Frame 15 readback BLACK for cam=" + currentId +
                            ", surfaceMode=" + cameraSurfaceMode +
                            " — will recheck at frame 50 before deciding");
                    }
                } catch (Exception e) {
                    logger.warn("Camera probe failed: " + e.getMessage());
                }
            }
            
            // Frame 50 recheck (~5s): if frame 15 was black, verify again.
            // By frame 50 the HAL has definitely warmed up. If still black, the saved
            // config is genuinely wrong (BmmCameraInfo returned incorrect ID).
            // Only then trigger a re-probe — this is rare and justified.
            // Same dead-probe-path guard as frame 15: null readback is NOT a
            // black frame; without isInitialized() this recheck would re-probe
            // a working camera whenever the downscaler thread failed init.
            if (frameCounter == 50 && !autoProbeCameras && !skipFrameValidation
                    && !USE_DILINK5_QCARCAM_PATH
                    && downscaler != null && downscaler.isInitialized()) {
                try {
                    byte[] probe = downscaler.readPixels(cameraTextureId, 8, 8);
                    boolean hasData = false;
                    if (probe != null) {
                        for (int i = 0; i < Math.min(probe.length, 192); i++) {
                            if ((probe[i] & 0xFF) > 10) { hasData = true; break; }
                        }
                    }
                    if (!hasData) {
                        int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                        logger.warn("Frame 50 STILL BLACK for cam=" + currentId +
                            " — saved config is wrong, starting re-probe");
                        autoProbeCameras = true;
                        probeComplete = false;
                        probeNextCameraId = 0;
                        probeNextSurfaceMode = 0;
                        lastDataCameraId = -1;
                        advanceProbeToNext(currentId);
                    } else {
                        // Camera has non-black data at frame 50 — it's working.
                        // Persist as validated so next restart skips all frame checks.
                        // BUT: don't overwrite if user has a manual override set — they may have
                        // changed the camera ID in the UI and it hasn't taken effect yet.
                        int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
                        logger.info("Frame 50 recheck: camera ID " + currentId + " confirmed working");
                        probeComplete = true;
                        try {
                            org.json.JSONObject existingCam = com.overdrive.app.config.UnifiedConfigManager
                                .loadConfig().optJSONObject("camera");
                            boolean hasManualOverride = existingCam != null && existingCam.optBoolean("manualOverride", false);
                            int savedId = existingCam != null ? existingCam.optInt("probedCameraId", -1) : -1;
                            
                            // Only write back if there's no manual override, or if the manual override
                            // matches what we're currently running (user's choice is already applied)
                            if (!hasManualOverride || savedId == currentId) {
                                // Pass OBSERVED dims, not the configured ones —
                                // writing `width, height` here is what made
                                // probedWidth/Height self-confirming. -1 when
                                // unobserved leaves the stored values alone.
                                com.overdrive.app.camera.CameraConfigResolver.persistPanoramicProbe(
                                    currentId,
                                    cameraSurfaceMode,
                                    getObservedProducerWidth(),
                                    getObservedProducerHeight(),
                                    true,
                                    false);
                            } else {
                                logger.info("Skipping config write — manual override exists (saved=" + savedId + ", running=" + currentId + ")");
                            }
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    logger.warn("Frame 50 recheck failed: " + e.getMessage());
                }
            }

            // SOTA: Gate all consumer passes until probe finds a working camera.
            // Without this, the encoder records BLACK frames, the stream shows garbage,
            // and the AI lane processes empty images during the probe sweep.
            if (!probeComplete) {
                // Still probing — don't feed consumers. Heartbeat already
                // updated above. Re-post handled by the finally block.
                return;
            }

            // PASS 1: Recording (Zero-Copy GPU Path)
            // SOTA: Always render to encoder (for pre-record circular buffer)
            GpuMosaicRecorder localRecorder = recorder;
            HardwareEventRecorderGpu localEncoder = encoder;
            long stageBeforeMosaicNs = System.nanoTime();
            // DECOUPLED LANE (camera.decoupledEncoderLane): PASS 1A is replaced
            // by copy-into-ring + EncoderLane submit — the encoder-blocking
            // makeCurrent/draw/eglSwap now happen on the lane thread, so this
            // thread never waits on MediaCodec backpressure and the camera's
            // gralloc buffers go back to the HAL at frame cadence. PASS
            // 1B/1C/AI below are unchanged; they sample cameraTextureId, which
            // runDecoupledLanePass republished as the fresh ring copy.
            if (USE_DECOUPLED_ENCODER_LANE) {
                runDecoupledLanePass(localRecorder);
            } else
            // Recorder lane master gate around ALL of PASS 1A (windshield consume
            // + recorder draw + drain). When the camera is kept warm ONLY for
            // blind-spot (PASS 1C, no encoder, no recording mode), the H.265
            // recorder lane is switched OFF: skip the windshield 2nd-camera
            // consume/drain AND the mosaic draw/encode so we burn zero Venus/GPU
            // on footage nothing will flush. A windshield camera that was already
            // STARTED is torn down in the `else if (windshieldStarted)` branch
            // below (it would otherwise gralloc-stall undrained) and re-opened by
            // updateWindshieldCameraOnGlThread() when PASS 1A resumes.
            //
            // SAFETY OVERRIDE — `|| localRecorder.isRecording()`: this single
            // GpuMosaicRecorder instance is SHARED by every recording consumer
            // (RecordingModeManager modes AND the ACC-off SurveillanceEngineGpu
            // sentry path, which holds the same recorder via setRecorder() and
            // triggers clips through recorder.triggerEventRecording() WITHOUT
            // routing through GpuSurveillancePipeline.startRecording()'s lane
            // re-assert — likewise OEM dashcam). Gating PASS 1A purely on
            // recorderLaneEnabled would let a sentry/OEM clip that started while
            // the camera was BS-only-warmed (lane OFF) record BLACK. Drawing
            // whenever the recorder is ACTUALLY recording closes every such
            // bypass at this one gate, by construction — recorderLaneEnabled then
            // only governs the idle pre-record-ring feed (no live clip open).
            if (localRecorder != null && (recorderLaneEnabled || localRecorder.isRecording())) {
                // Publish per-frame transform matrix to recorder before draw.
                // Cheap (16-float arraycopy); matches oem's per-frame
                // getTransformMatrix → uTexMatrix flow.
                if (USE_OEM_SURFACE_TEXTURE_PATH) {
                    localRecorder.setTextureMatrix(currentTexMatrix);
                }
                // Pass the HAL-provided sensor timestamp straight through to
                // eglPresentationTimeANDROID. Replaces the old TBC EMA path:
                // the encoder now produces PTS values that exactly mirror real
                // camera cadence, eliminating the rubber-banding/snapback the
                // EMA introduced at 15+ fps.
                updateWindshieldCameraOnGlThread();
                // Drain the windshield ImageReader EVERY recorder frame, not
                // only when windshieldPending is set. The pending flag is a
                // lost-update race: the GL thread reads pending==true, drains,
                // then clears it — but if the OnImageAvailable listener (its
                // own thread) fires a NEW frame between the drain and the
                // clear, our windshieldPending=false clobbers that set. The
                // frame is never drained; with maxImages=4 the gralloc slots
                // fill, the BYD AVM HAL producer stalls, no further callbacks
                // fire, and the top band freezes — classically on the first
                // frame. acquireLatestImage() returns null when nothing new
                // arrived, so the unconditional drain is a cheap no-op that
                // just keeps the last frame bound on idle iterations.
                // (Ported from Overdrive-release PR #97.) The stall guard
                // below remains as a safety net for genuine frame-stoppage
                // (handle contention / HAL pause) that draining can't prevent.
                if (windshieldStarted) {
                    consumeLatestWindshieldImageAndBind();
                    windshieldPending = false;
                }
                // Windshield stall guard. If the feed has gone quiet past the
                // threshold while still "started", the bound HardwareBuffer is
                // stale — keep drawing it and the dashcam top band freezes on
                // one frame. Drop windshieldFrameReady so drawFrame composites
                // the LIVE 360 front instead (never a frozen image), and make
                // one throttled close+reopen attempt to recover the feed.
                // All windshield fields are GL-thread-confined, so no lock.
                if (windshieldStarted && windshieldFrameReady
                        && windshieldLastFrameMs > 0
                        && (System.currentTimeMillis() - windshieldLastFrameMs)
                            > WINDSHIELD_STALL_THRESHOLD_MS) {
                    long stalledMs = System.currentTimeMillis() - windshieldLastFrameMs;
                    logger.warn("Windshield feed stalled " + stalledMs
                        + "ms (frames=" + windshieldFrameCount
                        + ") — falling back to 360 front + scheduling reopen");
                    // Stop trusting the stale frame immediately.
                    windshieldFrameReady = false;
                    // Throttled reopen: close + restart on this (GL) thread.
                    long nowReopen = System.currentTimeMillis();
                    if (nowReopen - windshieldLastReopenMs > WINDSHIELD_REOPEN_MIN_INTERVAL_MS) {
                        windshieldLastReopenMs = nowReopen;
                        try {
                            stopWindshieldCameraOnGlThread();
                            // windshieldEnabled is still true; updateWindshield
                            // on the next iteration will re-run start. Clearing
                            // the open-failed latch lets that retry proceed.
                            windshieldOpenFailed = false;
                        } catch (Throwable t) {
                            logger.warn("Windshield reopen (stop phase) failed: " + t.getMessage());
                        }
                    }
                }
                // Recorder draw stride gate (Proximity Guard low-rate pre-record).
                // We draw into the encoder surface only on selected frames; on
                // skipped frames MediaCodec simply receives no input, lowering
                // the effective recording rate. The windshield drain above
                // intentionally runs EVERY frame (gralloc-slot starvation guard)
                // and is outside this gate. Stride 1 = every frame (default).
                // The counter advances per consumed camera frame so the cadence
                // is uniform; frame 0 always draws so a freshly-applied stride
                // starts with a frame rather than a gap.
                // Recorder draw stride gate (Proximity Guard low-rate pre-record):
                // draw into the encoder surface only every stride-th frame; on
                // skipped frames MediaCodec gets no input, lowering the effective
                // recording rate. Stride 1 = every frame (default).
                int stride = recorderFrameStride;
                boolean drawThisFrame = stride <= 1 || (recorderStrideCounter % stride) == 0;
                recorderStrideCounter++;
                if (drawThisFrame) {
                    localRecorder.drawFrame(cameraTextureId, windshieldTextureId,
                        windshieldStarted && windshieldFrameReady, currentFrameTimestampNs);
                }
                // NOTE: encoder draining now runs on HardwareEventRecorderGpu's
                // dedicated drainer thread; the old inline drainEncoder() calls
                // here were no-ops (see HardwareEventRecorderGpu.drainEncoder) and
                // have been removed.

                // RECOVERY: If encoder surface died (EGL_BAD_SURFACE after prolonged use),
                // reinitialize the encoder and reconnect the recorder.
                // P1 #9: keep using localRecorder/localEncoder captured above.
                // pipeline.stop() runs on the daemon thread and can null
                // this.recorder/this.encoder concurrently; re-reading the fields
                // here would NPE.
                if (localRecorder.needsReinit() && localEncoder != null) {
                    logger.warn("Encoder surface lost - reinitializing encoder...");
                    // Extend the GL watchdog window: encoder.release() joins
                    // the drainer (up to 2s) plus MediaCodec stop/release —
                    // the bare 3s GL timeout is not enough headroom.
                    // P1 #11: CAS so a concurrent reopenCamera (daemon thread)
                    // can't race; if another restart is already in flight,
                    // skip — it'll re-fire on the next frame.
                    if (!restartInProgress.compareAndSet(false, true)) {
                        return;
                    }
                    try {
                        // Full teardown of recorder GL resources. Without this,
                        // shader programs (programId, overlayProgramId) and the
                        // overlay texture (overlayTextureId) leak on every
                        // reinit, since recorder.init() only frees the encoder
                        // surface, not the programs/textures it then re-creates.
                        localRecorder.release();
                        // Consume the release verdict (audit follow-up): a wedge
                        // discovered here means a worker is still alive on the old
                        // codec. release() already requested the trip-safe restart;
                        // building a replacement codec now would hide the wedged
                        // original from every close guard (fresh healthy workers on
                        // the new instance). init() also self-rejects on a terminal
                        // instance, but abort explicitly for a clear log trail.
                        if (!localEncoder.release()) {
                            logger.error("Encoder reinit ABORTED — worker wedged during "
                                + "release (trip-safe restart pending); refusing to "
                                + "build a replacement codec over the wedged one");
                            return;
                        }
                        localEncoder.init();
                        localRecorder.init(eglCore, localEncoder);
                        // Re-wire the contention probe — release() restored
                        // the inert default, and we don't want a contention
                        // event right after recovery to silently ignore the
                        // BYD AVM's signal-loss risk.
                        localRecorder.setHalContentionProbe(() -> {
                            BydCameraCoordinator c = cameraCoordinator;
                            return c != null && c.isNativeAppActive();
                        });
                        localRecorder.clearReinitFlag();
                        logger.info("Encoder reinitialized successfully after surface loss");
                    } catch (Exception reinitEx) {
                        logger.error("Encoder reinit failed: " + reinitEx.getMessage());
                        // If reinit fails, force process restart — EGL context is likely corrupt
                        logger.error("CRITICAL: Encoder reinit failed, forcing process restart");
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        CameraDaemon.requestProcessRestartPreservingTrip(
                                "encoder surface reinitialization failed");
                    } finally {
                        restartInProgress.set(false);
                    }
                }
            } else if (windshieldStarted) {
                // PASS 1A is skipped (recorder lane OFF and nothing recording —
                // e.g. camera kept warm ONLY for blind-spot). The recorder is the
                // ONLY consumer of the 2nd (windshield) AVMCamera, so with PASS 1A
                // gated off nothing drains its 4-slot ImageReader — the gralloc
                // slots fill, the HAL producer stalls, and a 2nd physical camera
                // stays powered on the shared SDM665 bus for the whole idle
                // window. Tear it down here; updateWindshieldCameraOnGlThread()
                // re-opens it the moment PASS 1A resumes (windshieldEnabled is
                // unchanged). Only reached when the OEM windshield dual-cam feature
                // is on (default off), so normally a no-op.
                stopWindshieldCameraOnGlThread();
            }

            // PASS 1B: Streaming (Parallel Zero-Copy GPU Path)
            // Only runs if streaming is enabled - uses separate encoder at lower resolution.
            // Stream stride gate: when the camera HAL runs faster than the stream
            // preset (e.g. 30 fps camera vs 10 fps stream), we skip frames here
            // to avoid wasted GPU raster + encode. Same pattern as PASS 1A's
            // recorderFrameStride.
            // Capture local refs to avoid NPE from concurrent pipeline shutdown
            com.overdrive.app.streaming.GpuStreamScaler localStreamScaler = streamScaler;
            HardwareEventRecorderGpu localStreamEncoder = streamEncoder;
            if (localStreamScaler != null && localStreamEncoder != null) {
                // Client-presence gate: with no viewer connected the encoded bytes
                // are dropped downstream, so skip the raster + encode entirely.
                boolean streamActive = streamClientProbe.getAsBoolean();
                if (streamActive) {
                    // Rising edge (idle → a client just (re)connected): force a fresh
                    // IDR so the resumed stream is immediately decodable rather than
                    // waiting for the next natural keyframe interval. Rate-limited:
                    // the /ws attach that caused this edge has usually just asked
                    // for the same IDR, and one forced keyframe serves every viewer.
                    if (!streamWasActive) {
                        localStreamEncoder.requestSyncFrameRateLimited();
                        streamStrideCounter = 0;  // resume on a drawn frame
                    }
                    streamWasActive = true;

                    int sStride = streamFrameStride;
                    boolean drawStreamFrame = sStride <= 1 || (streamStrideCounter % sStride) == 0;
                    streamStrideCounter++;
                    if (drawStreamFrame) {
                        if (USE_OEM_SURFACE_TEXTURE_PATH) {
                            localStreamScaler.setTextureMatrix(currentTexMatrix);
                            // Stamp the presentation time on dilink4 ONLY.
                            //
                            // This HAL emits at its own fixed rate (~4.5 fps
                            // observed) and refuses setCameraFps outright — it
                            // returns false for every value, and both the OEM app
                            // (gl/a.java:402) and other OEM-derived players discard that return, so
                            // false is simply normal here. An encoder configured
                            // for a higher KEY_FRAME_RATE and fed UNSTAMPED buffers
                            // has to invent timing: most ticks see an identical
                            // image, yielding near-empty P-frames and a picture
                            // that looks frozen after the first keyframe.
                            //
                            // currentFrameTimestampNs is the same single-domain
                            // System.nanoTime() PTS the recorder lane already
                            // stamps (GpuMosaicRecorder:1022) and the OEM stamps
                            // per frame arrival. The stream lane was the only one
                            // pushing buffers with no timestamp at all.
                            //
                            // Legacy (ImageReader) path is untouched: it keeps the
                            // unstamped swapBuffers it has always used, so nothing
                            // about non-dilink4 timing changes.
                            localStreamScaler.drawFrame(cameraTextureId,
                                currentFrameTimestampNs);
                        } else {
                            localStreamScaler.drawFrame(cameraTextureId);
                        }
                    }
                } else {
                    streamWasActive = false;
                }
            }

            // PASS 1C: Blind-spot lane (views 7/8). Independent scaler fed from the
            // SAME cameraTextureId, read-only — like PASS 1B but owned by the
            // dedicated blind-spot pipeline. NATIVE path: the scaler's render target
            // is a SurfaceControl layer (GPU → screen), so there is NO encoder —
            // localBsEncoder is null and drawFrame's swapBuffers IS the on-screen
            // present; we only drain when an encoder is present (legacy/none now).
            // Local snapshot so a concurrent disableBlindSpot() nulling the field
            // can't NPE mid-frame.
            //
            // VISIBILITY GATE: only render when the SurfaceControl layer is actually
            // shown (turn signal active / debug-preview). While the layer is hidden,
            // rendering is pure GPU waste — eglSwapBuffers still rasterizes 1280×960
            // into the SC surface, SurfaceFlinger just discards the buffer. On the
            // Adreno 610 single shader core this doubles GPU load (recording mosaic +
            // blind-spot mosaic) and pins the clock at 820 MHz. Gating on visibility
            // reduces the "enabled but idle" cost to zero; the very next frame after
            // the turn trigger sets bsLayerVisible=true picks up rendering (~66ms
            // worst-case latency, imperceptible).
            com.overdrive.app.streaming.GpuStreamScaler localBsScaler = bsStreamScaler;
            if (localBsScaler != null && bsLayerVisible) {
                if (USE_OEM_SURFACE_TEXTURE_PATH && localBsScaler == bsStreamScaler) {
                    localBsScaler.setTextureMatrix(currentTexMatrix);
                }
                localBsScaler.drawFrame(cameraTextureId);
                bsDiagFrames++;
                // Native path: drawFrame's swapBuffers presented straight to the
                // SurfaceControl layer — no encoder to drain. (The old
                // localBsEncoder.drainEncoder() here was a no-op; removed.)
            } else if (localBsScaler == null) {
                bsDiagSkipScaler++;
            } else {
                bsDiagSkipHidden++;
            }
            // Throttled BS render diagnostic (~5s). Logs whether PASS 1C is actually
            // drawing the BS lane and, when not, WHY — so "card shows but black" is
            // triageable from the log. cameraTextureId==0 here means the external
            // camera texture isn't allocated → drawFrame would sample nothing = black.
            // od.isReady()==false means the view-7/8 sampler coefficients are zero-filled
            // (license/authorize gate) = black even with frames + a valid texture.
            {
                long nowDiagMs = android.os.SystemClock.elapsedRealtime();
                if (nowDiagMs - bsDiagLastLogMs >= 5000L
                        && (bsDiagFrames > 0 || bsDiagSkipScaler > 0 || bsDiagSkipHidden > 0)) {
                    boolean odReady = false;
                    try { odReady = com.overdrive.app.od.Od.INSTANCE.isReady(); } catch (Throwable ignored) {}
                    logger.info("BS render diag: drawn=" + bsDiagFrames
                            + " skipNoScaler=" + bsDiagSkipScaler
                            + " skipHidden=" + bsDiagSkipHidden
                            + " camTex=" + cameraTextureId
                            + " bsVisible=" + bsLayerVisible
                            + " odReady=" + odReady);
                    bsDiagLastLogMs = nowDiagMs;
                    bsDiagFrames = 0; bsDiagSkipScaler = 0; bsDiagSkipHidden = 0;
                }
            }

            // PASS 2 + 3: AI lane.
            //
            // SOTA: All AI-lane GL work (mosaic readback + foveated crop)
            // moved off this thread to AiLaneGl, which owns a separate EGL
            // context in the same share group. We just publish "a new
            // camera frame is ready" via the seq counter and let that
            // thread pick it up. Stays decoupled from eglSwap cadence even
            // when the Adreno's hardware queue is busy with YOLO OpenCL —
            // any glReadPixels stall now lands on the AI thread, not here.
            //
            // glFlush ensures texture writes from this context are visible
            // to the share-group sibling. Without it, the AI thread may
            // sample stale bytes despite holding a "fresh" texture id —
            // share-group visibility for textures is per-EGL-flush.
            //
            // <b>Gate on sentry.isActive().</b> The AI lane has exactly two
            // consumers — V2 motion mosaic readback and the foveated crop
            // mailbox — and both are surveillance-only. When sentry is off
            // (every ACC-ON recording mode: CONTINUOUS / DRIVE_MODE /
            // PROXIMITY_GUARD) nobody reads the AI lane's output, so
            // publishing frames to it just burns CPU: glFlush is a kernel
            // ioctl into msm_kgsl, the AtomicInteger lazySet plus
            // postQueued CAS bounce a cache line cross-core, the
            // Handler.post wakes the AI-lane thread for a no-op
            // processOnce that immediately exits via the sentry.isActive()
            // checks inside it. Pre-v19 the entire AI block here was
            // gated on sentry.isActive(); the v19 refactor that moved AI
            // work to the dedicated GL thread inadvertently dropped this
            // gate, costing ~30-40% extra encoder-thread CPU during
            // ACC-ON CONTINUOUS recording. Restoring the gate here puts
            // ACC-ON load back at v17/v18 levels.
            // AI lane notify (publish-only; AI work runs on AiLaneGl's
            // own thread).
            SurveillanceEngineGpu localSentry = sentry;
            // AI lane is needed only when surveillance is active AND actually
            // consuming AI output. CONTINUOUS (always-record) ACC-OFF mode sets
            // active=true but uses no motion/YOLO/mosaic-readback — recording is
            // fed by the GL→encoder chain directly — so excluding it keeps the
            // lane (thread + EGL context + ~6.5MB cropper) from being created and
            // per-frame-fed for nothing in that sub-mode.
            boolean aiLaneNeeded = localSentry != null && localSentry.isActive()
                    && !localSentry.isContinuousMode();
            // Lazy lifecycle: bring the AI lane UP on the first surveillance-
            // active frame, tear it DOWN (freeing thread + EGL context + ~6.5MB
            // GPU + ~2.8MB CPU) when surveillance disarms. Both run here on the
            // GL thread with eglCore current — the only safe place to create the
            // share-group context + alloc the cropper FBO/PBO.
            if (aiLaneNeeded) {
                ensureAiLaneStarted();
            } else if (aiLaneGl != null) {
                releaseAiLaneOnGlThread();
            }
            AiLaneGl localAiLane = aiLaneGl;
            if (localAiLane != null && localAiLane.isRunning() && aiLaneNeeded) {
                android.opengl.GLES20.glFlush();
                localAiLane.notifyFrame(cameraFrameSeq.get());
            }

            // Per-stage timing roll-up. Track only the worst frame per 30 s
            // window so the log line stays bounded; the worst frame is what
            // crosses the encoder backpressure threshold. AI readback/submit
            // timers were removed: post-Tier-1 the AI work runs on a separate
            // GL thread, so the deltas here would always be zero.
            long stageEndNs = System.nanoTime();
            long stageTotalNs   = stageEndNs - stageT0;
            long stageAcquireNs = stageAfterAcquireNs - stageT0;
            long stageMosaicNs  = stageEndNs - stageBeforeMosaicNs;
            stageWindowFrames++;
            if (stageTotalNs > stageWorstTotalNs) {
                stageWorstTotalNs   = stageTotalNs;
                stageWorstAcquireNs = stageAcquireNs;
                stageWorstMosaicNs  = stageMosaicNs;
            }
            long nowMs = System.currentTimeMillis();
            if (stageTimingWindowStartMs == 0) {
                stageTimingWindowStartMs = nowMs;
            } else if (nowMs - stageTimingWindowStartMs >= STAGE_TIMING_LOG_INTERVAL_MS) {
                logger.info(String.format(
                        "Stage(worst/30s, encoder-thread): total=%dms acq=%dms mosaic+swap=%dms (frames=%d)",
                        stageWorstTotalNs / 1_000_000,
                        stageWorstAcquireNs / 1_000_000,
                        stageWorstMosaicNs / 1_000_000,
                        stageWindowFrames));
                stageWorstTotalNs = 0;
                stageWorstAcquireNs = 0;
                stageWorstMosaicNs = 0;
                stageWindowFrames = 0;
                stageTimingWindowStartMs = nowMs;
            }

            // Log stats periodically (every 2 minutes, time-based).
            // Reports the *windowed* FPS (frames since the last stats log) instead
            // of the lifetime average — otherwise a stall during one window drags
            // the running mean down forever and masks recovery in later windows.
            long now = System.currentTimeMillis();
            if (now - lastStatsTime >= STATS_INTERVAL_MS) {
                long windowMs = (lastStatsTime == 0) ? (now - startTime) : (now - lastStatsTime);
                int windowFrames = frameCounter - lastStatsFrameCount;
                float fps = windowMs > 0 ? (windowFrames * 1000.0f) / windowMs : 0f;
                measuredFps = fps;

                long aiProc = aiLaneWorker != null ? aiLaneWorker.getProcessedFrames() : 0;
                long aiDrop = aiLaneWorker != null ? aiLaneWorker.getDroppedFrames() : 0;
                long uptimeS = (now - startTime) / 1000;
                logger.info(String.format(
                        "Stats: %d frames (window), %.1f FPS (target=%d), uptime=%ds, aiProcessed=%d, aiDropped=%d",
                        windowFrames, fps, targetFps, uptimeS, aiProc, aiDrop));
                if (aiLaneWorker != null) {
                    aiLaneWorker.resetCounters();
                }

                lastStatsTime = now;
                lastStatsFrameCount = frameCounter;
            }

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) {
                msg = e.getClass().getSimpleName();
            }
            logger.error("Render loop error: " + msg, e);
        } finally {
            // Schedule next frame in finally so any `return` inside the try
            // (e.g., consumeLatestImageAndBind() returning false on a frame
            // where no new image is ready) still re-posts the loop. Without
            // this, the GL thread stops iterating and the watchdog kills us.
            if (running) {
                glHandler.post(renderLoopCallback);
            }
        }
    }
    
    /**
     * Verifies that the camera is producing a real panoramic strip (4 distinct views)
     * rather than a single camera stretched or AVM bird's-eye view.
     *
     * A real panoramic strip has 4 cameras stitched side by side. Each quadrant shows
     * a different scene. We verify by reading pixel samples from each quadrant and
     * checking that they have significantly different luma values.
     *
     * Uses the downscaler's 8x8 readback. Columns 0-1=Q0, 2-3=Q1, 4-5=Q2, 6-7=Q3.
     */
    private boolean verifyPanoramicStrip(byte[] probe8x8) {
        if (probe8x8 == null || probe8x8.length < 192) return false;
        int[] qLuma = new int[4];
        int[] qCnt = new int[4];
        int[] qMin = {255, 255, 255, 255};
        int[] qMax = {0, 0, 0, 0};
        int totalNonBlack = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int idx = (y * 8 + x) * 3;
                int r = probe8x8[idx] & 0xFF, g = probe8x8[idx+1] & 0xFF, b = probe8x8[idx+2] & 0xFF;
                int luma = (r + g*2 + b) / 4;
                int q = x / 2;
                qLuma[q] += luma; qCnt[q]++;
                if (luma < qMin[q]) qMin[q] = luma;
                if (luma > qMax[q]) qMax[q] = luma;
                if (luma > 10) totalNonBlack++;
            }
        }
        for (int q = 0; q < 4; q++) { if (qCnt[q] > 0) qLuma[q] /= qCnt[q]; }
        
        // Primary check: luma difference between quadrant pairs.
        // A real panoramic strip has 4 cameras showing different scenes.
        int diffPairs = 0;
        for (int i = 0; i < 4; i++) for (int j = i+1; j < 4; j++) if (Math.abs(qLuma[i]-qLuma[j]) > 15) diffPairs++;
        boolean isStrip = diffPairs >= 2;
        
        // Secondary check: if all quadrants have real (non-black) data with internal
        // variance, this is a real camera feed even if the scenes look similar.
        // This handles the common case of a parked car in a garage/at night where
        // all 4 cameras see similar dark scenes (low inter-quadrant difference)
        // but each quadrant still has texture/detail (intra-quadrant variance).
        if (!isStrip && totalNonBlack >= 48) {  // At least 75% of pixels are non-black
            int quadrantsWithVariance = 0;
            for (int q = 0; q < 4; q++) {
                // Each quadrant has internal texture (not a flat solid color)
                if (qMax[q] - qMin[q] >= 3) quadrantsWithVariance++;
            }
            // Accept if all quadrants have real data (non-black) and at least 3 have
            // internal variance. This distinguishes a real 4-camera feed from a
            // synthetic AVM bird's-eye view (which would have large inter-quadrant
            // differences) or a single stretched camera (which would have identical
            // min/max patterns across all quadrants).
            if (quadrantsWithVariance >= 3) {
                isStrip = true;
                logger.info("Strip accepted via secondary check: " + quadrantsWithVariance + 
                    " quadrants with variance, " + totalNonBlack + "/64 non-black pixels");
            }
        }
        
        logger.info("Strip check: Q0=" + qLuma[0] + " Q1=" + qLuma[1] + " Q2=" + qLuma[2] + " Q3=" + qLuma[3] +
                " diffPairs=" + diffPairs + " → " + (isStrip ? "STRIP" : "NOT_STRIP"));
        return isStrip;
    }

    /**
     * SOTA: Advance to the next camera ID during probe.
     * Surface mode 0 is confirmed working on all tested models — only probe camera IDs 0-5.
     * 
     * @param skipId Camera ID to skip (the one we just tested). -1 to start fresh.
     */
    private void advanceProbeToNext(int skipId) {
        if (USE_DILINK5_QCARCAM_PATH) {
            probeComplete = true;
            autoProbeCameras = false;
            logger.info("DiLink 5: legacy camera auto-probe suppressed");
            return;
        }
        // Close current camera cleanly
        if (cameraObj != null) {
            try {
                closeCameraForPath(cameraObj);
            } catch (Exception closeEx) {
                logger.warn("Error closing camera for probe: " + closeEx.getMessage());
            }
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
            }
        }
        
        // CRITICAL: Let the BYD camera HAL settle between close and next open.
        // Without this delay, rapid camera cycling overwhelms the HAL service
        // and triggers a system watchdog reboot.
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        
        // Probe camera IDs 0-5 with surface mode 0 (confirmed working on all models)
        boolean found = false;
        while (probeNextCameraId <= MAX_CAMERA_ID) {
            int tryId = probeNextCameraId;
            probeNextCameraId++;
            
            // Skip the ID we just tested
            if (tryId == skipId) {
                continue;
            }
            
            logger.info("Auto-probe: trying camera ID " + tryId + 
                " [" + (tryId + 1) + "/" + (MAX_CAMERA_ID + 1) + "]");
            
            cameraIdOverride = tryId;
            cameraSurfaceMode = 0;  // Surface mode 0 confirmed working
            frameCounter = 0;
            lastStatsFrameCount = 0;
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // Recreate SurfaceTexture — HAL won't deliver continuous frames
            // to a Surface previously connected to a different camera/mode
            recreateCameraSurface();
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            try {
                // Brief pause before opening next camera — HAL needs time to release resources
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                
                startCamera();
                // Setup event callback (only for AVMCamera path — binder service handles its own events)
                if (cameraCoordinator != null && cameraObj != null) {
                    cameraCoordinator.setupEventCallback(cameraObj);
                }
                found = true;
                break;
            } catch (Exception e) {
                // Camera ID doesn't exist or can't open — skip to next
                logger.info("Auto-probe: camera ID " + tryId + " failed to open: " + e.getMessage());
                cameraObj = null;
                // Delay before trying next combo to avoid HAL overload
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                continue;
            }
        }
        
        if (!found) {
            // If we found at least one camera with data during probe, switch back to it.
            // This prevents the "probe failed" state from leaving us on a black camera.
            if (lastDataCameraId >= 0 && lastDataCameraId != cameraIdOverride) {
                logger.info("Auto-probe: no verified strip found, falling back to camera ID " + 
                    lastDataCameraId + " (last known data source)");
                cameraIdOverride = lastDataCameraId;
                cameraSurfaceMode = 0;
                frameCounter = 0;
                lastStatsFrameCount = 0;
                lastGlThreadHeartbeat = System.currentTimeMillis();
                recreateCameraSurface();
                lastGlThreadHeartbeat = System.currentTimeMillis();
                try {
                    Thread.sleep(500);
                    startCamera();
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                } catch (Exception e) {
                    logger.error("Fallback camera open failed: " + e.getMessage());
                }
                // Persist this as a fallback so next restart doesn't re-probe
                try {
                    // OBSERVED dims only (see persistPanoramicProbe javadoc).
                    com.overdrive.app.camera.CameraConfigResolver.persistPanoramicProbe(
                        lastDataCameraId,
                        0,
                        getObservedProducerWidth(),
                        getObservedProducerHeight(),
                        true,
                        true);
                    logger.info("Persisted fallback camera ID " + lastDataCameraId + " for next launch");
                } catch (Exception ex) {
                    logger.warn("Failed to persist fallback camera config: " + ex.getMessage());
                }
            } else {
                logger.error("Auto-probe: exhausted all " + 
                    (MAX_CAMERA_ID + 1) + 
                    " camera IDs — no working panoramic camera found");
            }
            autoProbeCameras = false;
            probeStartId = -1;
            lastDataCameraId = -1;
            // Ungate consumers even on failure — better to record whatever we have
            // than to stay permanently blocked
            probeComplete = true;
            logger.warn("Probe complete (fallback mode) — unblocking consumers");
        }
    }

    /**
     * Starts the watchdog thread that monitors GL thread health.
     * 
     * If the GL thread hangs (e.g., eglSwapBuffers blocks), the watchdog
     * will call System.exit(0) to force a process restart, since EGL
     * contexts cannot be recovered from a blocked thread.
     */
    private void startWatchdog() {
        lastGlThreadHeartbeat = System.currentTimeMillis();
        firstFrameReceived = false;
        
        watchdogThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(1000);  // Check every second
                    
                    long now = System.currentTimeMillis();
                    long timeSinceHeartbeat = now - lastGlThreadHeartbeat;
                    
                    // Use extended timeout until the first camera frame arrives.
                    // The BYD panoramic camera HAL can take 5-8 seconds to deliver
                    // the first frame after open. During this period the GL thread
                    // is blocked on frameSync.wait(100) which still updates the
                    // heartbeat, but if the HAL is slow to even accept the surface
                    // (e.g., I/O contention from MediaScanner broadcasts), the
                    // heartbeat can stall. Killing the process here just causes a
                    // restart loop that makes things worse.
                    // Also use extended timeout during camera restart — the GL thread
                    // is busy with close/reopen operations and heartbeat updates are
                    // interleaved but may not be frequent enough for the normal timeout.
                    long effectiveTimeout;
                    if (AvcHalWarmup.isColdOpenWarmupInProgress()) {
                        effectiveTimeout = AvcHalWarmup.coldOpenWarmupTimeoutMs();
                    } else if (USE_DILINK5_QCARCAM_PATH
                            && diLink5ReacquireInProgress) {
                        // Reverse recovery runs synchronously on the GL owner
                        // and includes DI5 service probes/cooldown before a new
                        // handle exists. Give that bounded acquisition the same
                        // timeout as first-frame warmup; the normal 3 s active-
                        // stream timeout resumes on the first replacement frame.
                        effectiveTimeout = DILINK5_REACQUIRE_MAX_MS;
                    } else if (USE_DILINK5_QCARCAM_PATH
                            && (diLink5ReverseSourceTransitionInProgress
                                    || diLink5CloseInProgress)) {
                        // Releasing EGLImages, the native DMA client, and the
                        // owned sidecar is a bounded GL-thread operation. It
                        // can legitimately exceed the normal 3 s active-frame
                        // heartbeat without implying a deadlock.
                        effectiveTimeout =
                                DILINK5_OWNERSHIP_TRANSITION_TIMEOUT_MS;
                    } else {
                        effectiveTimeout = (firstFrameReceived && !restartInProgress.get())
                                ? GL_THREAD_TIMEOUT_MS
                                : GL_THREAD_WARMUP_TIMEOUT_MS;
                    }
                    
                    if (timeSinceHeartbeat > effectiveTimeout) {
                        // Request the restart BEFORE logging (audit follow-up):
                        // the logger can block on the same wedged storage that
                        // froze the GL thread, and this watchdog is the LAST
                        // escape for the camera-held wedge — a log call ahead
                        // of the request could strand the camera forever. The
                        // urgent variant arms its halt deadline before it
                        // touches the logger for the same reason.
                        if (cameraObj != null) {
                            // URGENT (audit follow-up): the GL thread is the
                            // only thread that can close or yield the camera,
                            // and it is heartbeat-dead while we HOLD the
                            // AVMCamera handle — the native AVM app has no
                            // video until this process dies. This is also the
                            // only escape from the timed-out pre-yield worker
                            // wedged inside closeEventRecording() while
                            // holding startStopLock: the GL thread then blocks
                            // on that monitor inside stopDrainerForCameraClose
                            // BEFORE its join or its urgent failure branch, so
                            // the drainer-close helper can never fire. The
                            // conservative coordinator is no escape either —
                            // its checkpoint write can block on the same
                            // wedged mount. Bounded halt; process death
                            // releases the handle, wrapper respawns us.
                            CameraDaemon.requestUrgentCameraReleaseRestart(
                                    "GL watchdog heartbeat timeout (camera held)");
                        } else {
                            // No camera held — nothing external is waiting on
                            // a release. The coordinator exits (and the
                            // DaemonLauncher wrapper respawns us) only after
                            // the active trip journal is durable.
                            CameraDaemon.requestProcessRestartPreservingTrip(
                                    "GL watchdog heartbeat timeout");
                        }

                        logger.error( "CRITICAL: GL thread blocked for " + timeSinceHeartbeat + 
                                "ms - forcing process restart" +
                                (firstFrameReceived ? "" : " (during camera warmup)"));
                        
                        // Give the log a moment to flush before the requested
                        // exit/halt lands.
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {}
                    }

                    // DEAD-SLOT ESCAPE (issue #170) — must be checked BEFORE the
                    // frame-health monitor below, whose `lastFrameTime > 0` gate a
                    // never-streamed camera cannot satisfy (restarts triggered
                    // before the first frame deliberately leave it 0 — see
                    // restartCameraAfterError).
                    //
                    // Conditions, all required:
                    //   - legacy path only: on dilink4 the HAL routinely pauses
                    //     frame emission on parked cars, so 25s of silence is a
                    //     normal state there, not a dead slot — mirrors the
                    //     dilink4SkipStallRestart oem-parity carve-out below.
                    //   - firstFrameReceived == false: this camera has produced
                    //     nothing since the pipeline started. Once any frame has
                    //     arrived the normal stall/restart machinery is armed and
                    //     owns recovery, so we stay out of its way for good.
                    //   - source not handed off AND no native app active: an OEM app
                    //     (reverse cam, AVM parking view) holding or contending
                    //     the HAL legitimately starves frames without a yield
                    //     event — the stall monitor below makes the same
                    //     allowance via its contention threshold.
                    //   - cameraObj != null, EXCEPT mid-walk: a candidate whose
                    //     open failed leaves cameraObj null, and the walk must
                    //     still advance past it rather than freeze forever.
                    //   - no restart or HAL-recovery already in flight, so we
                    //     never race a close/open we don't own.
                    boolean nativeAppHoldsHal = cameraCoordinator != null
                            && cameraCoordinator.isNativeAppActive();
                    long nowElapsed = android.os.SystemClock.elapsedRealtime();
                    if (nativeAppHoldsHal
                            && !USE_OEM_SURFACE_TEXTURE_PATH
                            && !USE_CAMERA2_AVM_PATH
                            && !firstFrameReceived
                            && !isCameraFrameConsumptionPaused()
                            && nowElapsed - lastNativeAppRecheckMs >= NATIVE_APP_RECHECK_MS
                            && glHandler != null) {
                        lastNativeAppRecheckMs = nowElapsed;
                        final BydCameraCoordinator coordinator = cameraCoordinator;
                        glHandler.post(coordinator::checkNativeAppActive);
                    }
                    if (!USE_OEM_SURFACE_TEXTURE_PATH
                            && !USE_CAMERA2_AVM_PATH
                            && !firstFrameReceived
                            && !isCameraFrameConsumptionPaused()
                            && !nativeAppHoldsHal
                            && (cameraObj != null || deadSlotWalkActive)
                            && !restartInProgress.get()
                            && !halRecoveryEscalated
                            && !deadSlotWalkExhausted
                            && lastCameraStartTime > 0
                            && (now - lastCameraStartTime) > FIRST_FRAME_DEAD_SLOT_MS
                            && glHandler != null) {
                        long deadFor = now - lastCameraStartTime;
                        logger.warn("DEAD SLOT: camera id "
                            + (cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID)
                            + (cameraObj != null
                                ? " opened but delivered ZERO frames in " + deadFor
                                    + "ms — the HAL accepted the open but cannot stream"
                                    + " this slot (BYD 2602/pano_h reports a 0x0 preview"
                                    + " here)."
                                : " failed to reopen during the fallback walk.")
                            + " Trying next panoramic candidate.");
                        glHandler.post(this::advanceToNextCandidateCameraId);
                    }
                    if (!USE_OEM_SURFACE_TEXTURE_PATH
                            && !USE_CAMERA2_AVM_PATH
                            && !firstFrameReceived
                            && deadSlotRetryAtMs > 0L
                            && android.os.SystemClock.elapsedRealtime() >= deadSlotRetryAtMs
                            && (deadSlotWalkExhausted || cameraObj == null)
                            && (cameraObj == null
                                || now - lastCameraStartTime > FIRST_FRAME_DEAD_SLOT_MS)
                            && !isCameraFrameConsumptionPaused()
                            && !nativeAppHoldsHal
                            && !restartInProgress.get()
                            && !halRecoveryEscalated
                            && glHandler != null) {
                        glHandler.post(this::retryDeadSlotWalk);
                    }

                    if (USE_CAMERA2_AVM_PATH) {
                        camera2HealthTick();
                    }

                    // SOTA: Frame health monitor — detect stalled camera feed
                    // If GL thread is alive but no new frames for FRAME_STALL_THRESHOLD_MS,
                    // the camera HAL may be starved or dead.
                    // Decision is contention-aware: if native app is active, use longer
                    // threshold and require consecutive stalls before yielding.
                    // On DiLink 4/5 read the GENUINE-arrival clock, not
                    // lastFrameTime: the latter used to be refreshed by every
                    // render-loop tick (updateTexImage no-ops without throwing),
                    // which made this detector structurally unable to see a
                    // frozen HAL. lastRealFrameTimeSt only moves on a real
                    // onFrameAvailable. Legacy keeps lastFrameTime exactly as
                    // before — its acquireLatestImage() null-check already made
                    // lastFrameTime a true arrival signal there.
                    boolean usesRealArrivalStallClock =
                            USE_DILINK4_AVM_PATH || USE_DILINK5_QCARCAM_PATH;
                    long stallClock = usesRealArrivalStallClock
                        ? lastRealFrameTimeSt : lastFrameTime;
                    // dilink4: releaseCameraConsumer() zeroes lastRealFrameTimeSt on
                    // every reopen, so `stallClock > 0` alone would permanently
                    // suppress this detector after the FIRST recovery attempt for a
                    // producer that never comes back. Fall back to the open time so a
                    // reopen that delivers nothing is still measurable. Legacy is
                    // untouched — its clock is not zeroed this way.
                    if (usesRealArrivalStallClock && stallClock <= 0) {
                        stallClock = lastCameraStartTime;
                    }
                    boolean diLink5ReacquireStallSuppressed =
                            shouldSuppressDiLink5FrameStall(
                                    USE_DILINK5_QCARCAM_PATH,
                                    diLink5ReacquireInProgress,
                                    cameraObj != null,
                                    lastCameraStartTime,
                                    diLink5ReacquireStartedAtMs,
                                    now,
                                    FRAME_STALL_WARMUP_GRACE_MS,
                                    DILINK5_REACQUIRE_MAX_MS);
                    if (USE_DILINK5_QCARCAM_PATH
                            && diLink5ReacquireInProgress
                            && cameraObj == null
                            && !diLink5ReacquireStallSuppressed
                            && !diLink5SafetyDisabled) {
                        logger.error("DiLink 5 FastCam reacquire exceeded "
                                + DILINK5_REACQUIRE_MAX_MS
                                + "ms without an active handle");
                        scheduleDiLink5DeferredReacquire(
                                "FastCam reacquire deadline exceeded");
                    }
                    if (USE_DILINK5_QCARCAM_PATH && diLink5IdleResumePending
                            && diLink5IdleResumeStartedAtUptimeMs > 0L
                            && android.os.SystemClock.uptimeMillis()
                                    - diLink5IdleResumeStartedAtUptimeMs
                                    >= DILINK5_IDLE_RESUME_MAX_MS) {
                        onDiLink5IdleResumeFailed("no frame "
                                + (DILINK5_IDLE_RESUME_MAX_MS / 1000L) + " s after the reopen",
                                DILINK5_IDLE_RESUME_MAX_MS);
                    }
                    if (!isCameraFrameConsumptionPaused()
                        && !USE_CAMERA2_AVM_PATH
                        && !diLink5SafetyDisabled
                        && !diLink5ReacquireStallSuppressed
                        && stallClock > 0 &&
                        timeSinceHeartbeat < GL_THREAD_TIMEOUT_MS) {
                        long timeSinceFrame = now - stallClock;

                        // Use longer threshold when native app is active — transient
                        // CPU/IO stalls shouldn't trigger a yield that interrupts recording
                        boolean nativeActive = cameraCoordinator != null && 
                            cameraCoordinator.isNativeAppActive();
                        long stallThreshold = nativeActive 
                            ? FRAME_STALL_CONTENTION_THRESHOLD_MS 
                            : FRAME_STALL_THRESHOLD_MS;
                        
                        // Post-(re)open warmup grace. The BYD AVM HAL takes ~5-8s
                        // to deliver the first frame after any camera open (see GL
                        // watchdog comment). The 4s stall threshold otherwise trips
                        // before frame 1 can arrive — tearing the camera down and
                        // reopening it in a loop that never escapes warmup (the
                        // sentry->drive recording-blackout root cause when ACC turns
                        // on while surveillance is still armed). Suppress the stall
                        // until the grace window (measured from the most recent open)
                        // elapses. lastFrameTime is deliberately NOT reset here: if
                        // the HAL is genuinely dead, timeSinceFrame keeps growing and
                        // the real stall path fires on the first tick past the grace
                        // window instead of being perpetually re-deferred.
                        long timeSinceCameraStart = lastCameraStartTime > 0
                                ? now - lastCameraStartTime
                                : Long.MAX_VALUE;
                        if (timeSinceFrame > stallThreshold && halRecoveryEscalated) {
                            // A full-rebuild recovery (onHalRecoveryNeeded) is in
                            // flight. Don't post bare restarts on top of it — the
                            // pipeline clears this latch via notePipelineRestarted()
                            // once its full camera/GL rebuild completes.
                            //
                            // Once per episode. These two suppression branches were
                            // unreachable on dilink4 before the stall clock was
                            // fixed; at 1 Hz they are pure spam (the message is
                            // invariant and no action follows).
                            // Throttle on dilink4 ONLY. On legacy this line kept
                            // its original once-per-tick cadence: the requirement
                            // is zero behavioural change for non-dilink4 cars, and
                            // "behaviour" includes log cadence a field engineer may
                            // be reading. Note the dilink4 branch also latches
                            // stallEpisodeLogged, which is why it must not run on
                            // legacy — it would suppress the later FRAME STALL
                            // anchor for an episode that legacy does act on.
                            if (!usesRealArrivalStallClock || !stallEpisodeLogged) {
                                logger.info("Frame stall while HAL-recovery escalation in flight — "
                                    + "deferring to full-rebuild recovery.");
                                if (usesRealArrivalStallClock) stallEpisodeLogged = true;
                            }
                        } else if (timeSinceFrame > stallThreshold
                                && timeSinceCameraStart < FRAME_STALL_WARMUP_GRACE_MS) {
                            // Once per warmup window on dilink4, unchanged
                            // once-per-tick on legacy (see the note above — no
                            // legacy log-cadence changes). Re-arms on the next
                            // camera open, since lastCameraStartTime changes.
                            if (!usesRealArrivalStallClock
                                    || warmupGraceLoggedForStartMs != lastCameraStartTime) {
                                if (usesRealArrivalStallClock) {
                                    warmupGraceLoggedForStartMs = lastCameraStartTime;
                                }
                                logger.info("Frame stall suppressed — within post-open warmup grace ("
                                    + timeSinceCameraStart + "ms < " + FRAME_STALL_WARMUP_GRACE_MS
                                    + "ms; BYD HAL first-frame latency is 5-8s). Not restarting yet.");
                            }
                        } else if (timeSinceFrame > stallThreshold) {
                            // EPISODE-THROTTLED on dilink4. A parked byd_apa HAL
                            // legitimately pauses frame emission for minutes, and
                            // the dilink4 branch below deliberately takes no
                            // action — so re-announcing every ~5s would be pure
                            // spam (~1400 lines/hour) with no new information.
                            // Log the first stall of an episode, then stay quiet
                            // until frames actually resume (which clears the
                            // latch in consumeSurfaceTextureFrame).
                            boolean firstOfEpisode = !stallEpisodeLogged;
                            if (firstOfEpisode) {
                                // Anchor the episode so we can report TRUE elapsed
                                // time later. The stall clock itself is reset below
                                // (otherwise the detector re-fires every tick), so
                                // timeSinceFrame alone always reads ~one threshold
                                // and a 5-second hiccup would be indistinguishable
                                // from a 5-hour freeze.
                                stallEpisodeStartMs = now - timeSinceFrame;
                                stallEpisodeNextLogMs = now + STALL_RELOG_STEP_MS;
                            }
                            long episodeMs = stallEpisodeStartMs > 0
                                ? now - stallEpisodeStartMs : timeSinceFrame;
                            // Legacy logs every stall (its detector acts on them).
                            // dilink4 takes NO action, so log the first, then
                            // re-log on an escalating cadence carrying the real
                            // elapsed time — bounded volume, duration still
                            // diagnosable.
                            boolean relogDue = usesRealArrivalStallClock
                                && stallEpisodeNextLogMs > 0 && now >= stallEpisodeNextLogMs;
                            if (firstOfEpisode || !usesRealArrivalStallClock || relogDue) {
                                logger.warn("FRAME STALL: No frames for " + timeSinceFrame + "ms" +
                                    (nativeActive ? " (native app active)" : "")
                                    + (USE_DILINK4_AVM_PATH
                                        ? " (dilink4: from last REAL onFrameAvailable; episode "
                                          + (episodeMs / 1000) + "s)"
                                        : USE_DILINK5_QCARCAM_PATH
                                            ? " (dilink5: from last REAL FastCam frame; episode "
                                              + (episodeMs / 1000) + "s)"
                                            : ""));
                                if (relogDue) {
                                    // Escalate 1min → 5min → 30min → 30min…
                                    long step = (episodeMs < 300_000L) ? 300_000L
                                              : (episodeMs < 1_800_000L) ? 1_800_000L
                                              : 1_800_000L;
                                    stallEpisodeNextLogMs = now + step;
                                }
                            }
                            // dilink4-only latch. On legacy nothing reads it (the
                            // log condition short-circuits on
                            // !USE_OEM_SURFACE_TEXTURE_PATH), and leaving it
                            // unwritten keeps legacy state byte-identical.
                            if (usesRealArrivalStallClock) stallEpisodeLogged = true;
                            // Reset the clock this detector actually read, or the
                            // next tick re-fires immediately. On dilink4 that is
                            // lastRealFrameTimeSt; touching only lastFrameTime
                            // there would leave the stall latched forever.
                            lastFrameTime = now;
                            if (usesRealArrivalStallClock) {
                                lastRealFrameTimeSt = now;
                            }

                            // dilink4: bounded stall-driven restart (oem caps at 5
                            // reopens too, but its watchdog can't fire for a
                            // producer that dies parked, so we don't copy it).
                            if (USE_DILINK4_AVM_PATH) {
                                maybeRestartStalledDilink4Producer(now, episodeMs,
                                    firstOfEpisode);
                            } else if (USE_DILINK5_QCARCAM_PATH) {
                                requestDiLink5SourceOnlyRecovery(
                                    "FastCam bridge frame stall");
                            } else if (cameraCoordinator != null) {
                                if (nativeActive) {
                                    // Contention path: require consecutive stalls before yielding
                                    consecutiveContentionStalls++;
                                    if (consecutiveContentionStalls >= CONTENTION_STALL_COUNT_TO_YIELD) {
                                        logger.warn("Consecutive contention stalls: " +
                                            consecutiveContentionStalls + " — yielding camera");
                                        consecutiveContentionStalls = 0;
                                        cameraCoordinator.onFrameStallDetected();
                                    } else {
                                        logger.info("Contention stall " + consecutiveContentionStalls +
                                            "/" + CONTENTION_STALL_COUNT_TO_YIELD +
                                            " — waiting for more evidence before yielding");
                                    }
                                } else {
                                    // No native app — this is a HAL issue, restart camera
                                    consecutiveContentionStalls = 0;
                                    logger.info("Frame stall is HAL issue — restarting camera");
                                    if (glHandler != null) {
                                        glHandler.post(() -> restartCameraAfterError());
                                    }
                                }
                            } else {
                                // No coordinator — just restart
                                if (glHandler != null) {
                                    glHandler.post(() -> restartCameraAfterError());
                                }
                            }
                        } else if (nativeActive && timeSinceFrame < 500) {
                            // Frames are flowing despite native app — reset stall counter
                            consecutiveContentionStalls = 0;
                        }
                    }
                    
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "GL-Watchdog");
        
        watchdogThread.setDaemon(true);
        watchdogThread.start();
        
        logger.info( "GL thread watchdog started (timeout=" + GL_THREAD_TIMEOUT_MS + "ms, " +
            "warmupTimeout=" + GL_THREAD_WARMUP_TIMEOUT_MS + "ms, " +
            "frameStall=" + FRAME_STALL_THRESHOLD_MS + "ms, " +
            "cameraId=" + (cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID) + ", " +
            "probe=" + (autoProbeCameras ? "ACTIVE" : "OFF") + ")");
    }

    /**
     * DiLink 4 only: recover a stalled byd_apa producer when a real consumer is
     * starved. The first action is deliberately non-destructive: callback probe,
     * then same-handle SurfaceTexture rebind. A full close/reopen is reached only
     * if both soft stages fail.
     *
     * @return true if a soft recovery was posted
     */
    private boolean maybeRestartStalledDilink4Producer(long now, long episodeMs,
                                                       boolean firstOfEpisode) {
        // Any stall voids accumulated proof-of-recovery: it must be earned inside ONE
        // continuous run. Otherwise a HAL dribbling 3 frames per reopen would sum
        // 3+3+3+... to the threshold, refill the budget, and reopen forever.
        dilink4RecoveryProofFrames = 0;
        dilink4RecoveryProofSinceMs = 0L;
        // Released after a HAL death, or an open still in flight: the
        // scheduled reopen / late-open watch owns recovery.
        if ((dilink4ReleasedAfterDeath && cameraObj == null)
                || isDiLink4OpenInFlight()) {
            return false;
        }
        // The explicit demand is published by RecordingModeManager's single
        // camera-intent authority and includes armed surveillance, visible
        // camera-view/blind-spot and live stream demand. Keep the direct state
        // checks as defense-in-depth for callers that precede the next reconcile.
        GpuMosaicRecorder recForStall = recorder;
        HardwareEventRecorderGpu encForStall = encoder;
        boolean consumerStarved = isDiLink4ConsumerStarved(
            dilink4FrameDemanded,
            bsLayerVisible,
            recForStall != null && recForStall.isRecording(),
            encForStall != null && encForStall.isWritingToFile(),
            streamEncoder != null);
        if (!consumerStarved) {
            if (firstOfEpisode) {
                logger.info("Frame stall on dilink4 — no starved consumer,"
                    + " leaving producer paused");
            }
            return false;
        }
        if (dilink4StallRecoveryExhausted
                || dilink4StallRestartAttempts
                    >= DILINK4_STALL_RESTART_MAX_ATTEMPTS) {
            dilink4StallRecoveryExhausted = true;
            requestDiLink4HalRecoveryEscalation();
            return true;
        }
        return requestDiLink4ProducerRecovery(
            "watchdog episode=" + (episodeMs / 1000) + "s"
                + ", demand=" + dilink4FrameDemandReason,
            false);
    }

    /**
     * Dead-slot escape (issue #170): move to the next panoramic camera candidate
     * after the current one opened but never produced a frame.
     *
     * <p>MUST run on the GL thread — it mutates {@code cameraIdOverride} and
     * hands off to {@link #restartCameraAfterError()}, which owns the
     * close/reopen sequence (including the encoder drainer, event-callback
     * re-registration and {@code onPostReacquire}). Reusing that path rather
     * than open-coding a second close/open keeps the two in lockstep.
     */
    private void advanceToNextCandidateCameraId() {
        // Re-validate on the GL thread: the watchdog observed this up to a tick
        // ago, and a first frame (or someone else's restart) may have landed in
        // between. Cheap insurance against tearing down a camera that just
        // started working.
        if (firstFrameReceived || isCameraFrameConsumptionPaused()
                || restartInProgress.get() || halRecoveryEscalated
                || deadSlotWalkExhausted) {
            return;
        }
        if (cameraCoordinator != null && cameraCoordinator.isNativeAppActive()) {
            return;
        }
        // Mid-walk a failed candidate open leaves cameraObj null and the walk
        // must still move past it; outside the walk, null means we never held
        // a camera at all — no slot evidence, nothing to escape from.
        if (cameraObj == null && !deadSlotWalkActive) {
            return;
        }
        // Re-check the trigger itself, not just the flags. The watchdog posts
        // this once per 1s tick while the condition holds, so a GL-thread
        // stall of >1s at the boundary queues DUPLICATE posts; the first one
        // switches ids and reopens (refreshing lastCameraStartTime), and
        // without this check the stale second post would advance again with
        // zero warmup — burning the new candidate's 25s window, or falsely
        // latching exhaustion while it is still warming up. Every reopen
        // refreshes lastCameraStartTime, so this single predicate makes
        // stale and duplicate posts self-neutralizing. (Exception: after a
        // FAILED candidate open, startCamera never reached the refresh, the
        // window is stale by construction, and advancing immediately is
        // exactly right — no point waiting 25s for a camera that is not open.)
        if (lastCameraStartTime <= 0
                || (System.currentTimeMillis() - lastCameraStartTime) <= FIRST_FRAME_DEAD_SLOT_MS) {
            return;
        }
        if (isNativeAppHoldingCameraNow()) {
            return;
        }

        int currentId = cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
        int nextId = PanoCameraFallbackOrder.next(
            resolveHalPanoCameraIdHint(), deadSlotTriedCameraIds);

        if (nextId == PanoCameraFallbackOrder.NO_CANDIDATE) {
            // Latch so this logs once rather than every watchdog tick. A user
            // can always pin a slot via POST /api/surveillance/config
            // {"manualCameraId":N}.
            deadSlotWalkExhausted = true;
            int originId = deadSlotWalkOriginId >= 0 ? deadSlotWalkOriginId : currentId;
            long retryInMs = DEAD_SLOT_RETRY_BACKOFF_MS[
                Math.min(deadSlotRetryCycle, DEAD_SLOT_RETRY_BACKOFF_MS.length - 1)];
            deadSlotRetryCycle++;
            deadSlotRetryAtMs = android.os.SystemClock.elapsedRealtime() + retryInMs;
            logger.error("Dead-slot walk exhausted — tried camera ids "
                + deadSlotTriedCameraIds + " and none delivered a frame. Back on"
                + " camera id " + originId + "; reopening it in "
                + (retryInMs / 1000) + "s and on a backoff until a frame arrives."
                + " If this vehicle streams on another slot,"
                + " pin it with POST /api/surveillance/config {\"manualCameraId\": N}.");
            if (originId != currentId && !isNativeAppHoldingCameraNow()) {
                cameraIdOverride = originId;
                skipFrameValidation = deadSlotWalkOriginSkipValidation;
                deadSlotWalkActive = false;
                consecutiveZeroFrameRestarts = 0;
                restartCameraAfterError();
            }
            return;
        }

        logger.warn("Dead-slot fallback: switching panoramic camera id "
            + currentId + " -> " + nextId + " (tried so far: "
            + deadSlotTriedCameraIds + ")");
        if (!deadSlotWalkActive) {
            deadSlotWalkOriginId = currentId;
            deadSlotWalkOriginSkipValidation = skipFrameValidation;
        }

        // An attempted candidate counts as tried even if its open fails —
        // otherwise a candidate that throws at open would be retried on every
        // watchdog pass, forever, with the escalation path suppressed.
        deadSlotTriedCameraIds.add(nextId);
        cameraIdOverride = nextId;
        // The zero-frame reopen evidence was gathered against a DIFFERENT
        // physical slot, so it says nothing about this one. Without this reset
        // the escalation counter would reach its threshold mid-walk and hand
        // off to a full rebuild, which reopens the same dead slot — exactly the
        // loop this method exists to break.
        consecutiveZeroFrameRestarts = 0;
        deadSlotWalkActive = true;
        // The saved config's validated/manual privilege belongs to the id it
        // was saved FOR — a different slot must earn persistence through the
        // frame-15/50 pixel checks (which also carry the manual-override
        // guard). Re-enabling validation here is what lets the recovered id be
        // confirmed and written back on configs that skip validation.
        skipFrameValidation = false;

        restartCameraAfterError();
    }

    /**
     * GL thread. After an exhausted walk, reopens the origin slot on a backoff until a
     * frame arrives; a pending ACC-ON reset runs a fresh walk instead.
     */
    private void retryDeadSlotWalk() {
        long nowEr = android.os.SystemClock.elapsedRealtime();
        if (!running || firstFrameReceived || deadSlotRetryAtMs <= 0L
                || nowEr < deadSlotRetryAtMs
                || !(deadSlotWalkExhausted || cameraObj == null)
                || (cameraObj != null && System.currentTimeMillis() - lastCameraStartTime
                    <= FIRST_FRAME_DEAD_SLOT_MS)
                || isCameraFrameConsumptionPaused() || restartInProgress.get()
                || halRecoveryEscalated) {
            return;
        }
        if (isNativeAppHoldingCameraNow()) {
            return;
        }
        int originId = deadSlotWalkOriginId >= 0 ? deadSlotWalkOriginId
            : (cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID);
        cameraIdOverride = originId;
        if (deadSlotWalkOriginId >= 0) {
            skipFrameValidation = deadSlotWalkOriginSkipValidation;
        }
        consecutiveZeroFrameRestarts = 0;
        if (deadSlotFreshWalkPending) {
            deadSlotFreshWalkPending = false;
            deadSlotWalkExhausted = false;
            deadSlotWalkActive = false;
            deadSlotTriedCameraIds.clear();
            deadSlotRetryCycle = 0;
            deadSlotRetryAtMs = nowEr + DEAD_SLOT_RETRY_SAFETY_NET_MS;
            logger.warn("Dead-slot: camera never streamed — reopening camera id "
                + originId + " with a fresh dead-slot walk");
        } else {
            long retryInMs = DEAD_SLOT_RETRY_BACKOFF_MS[
                Math.min(deadSlotRetryCycle, DEAD_SLOT_RETRY_BACKOFF_MS.length - 1)];
            deadSlotRetryCycle++;
            deadSlotRetryAtMs = nowEr + retryInMs;
            logger.warn("Dead-slot retry " + (deadSlotRetryCycle - 1)
                + ": still no frame since start — reopening camera id " + originId
                + " (next in " + (retryInMs / 1000) + "s)");
        }
        restartCameraAfterError();
    }

    private boolean isDeadSlotRecoveryEngaged() {
        return !USE_OEM_SURFACE_TEXTURE_PATH && !USE_CAMERA2_AVM_PATH && !firstFrameReceived
            && (deadSlotWalkActive || deadSlotWalkExhausted || deadSlotRetryCycle > 0);
    }

    /** Called before the legacy ACC-ON reopen: back to the origin slot with a fresh walk. */
    private void resetDeadSlotWalkForReopen() {
        if (!isDeadSlotRecoveryEngaged()) {
            return;
        }
        if (deadSlotWalkOriginId >= 0) {
            cameraIdOverride = deadSlotWalkOriginId;
            skipFrameValidation = deadSlotWalkOriginSkipValidation;
        }
        deadSlotFreshWalkPending = false;
        deadSlotWalkExhausted = false;
        deadSlotWalkActive = false;
        deadSlotTriedCameraIds.clear();
        deadSlotRetryCycle = 0;
        deadSlotRetryAtMs = android.os.SystemClock.elapsedRealtime() + DEAD_SLOT_RETRY_SAFETY_NET_MS;
        consecutiveZeroFrameRestarts = 0;
        logger.info("Camera never streamed — reopening on camera id "
            + (cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID)
            + " with a fresh dead-slot walk");
    }

    /** Resumes recording/surveillance once, off the frame path, after deferred retries. */
    private void postDeadSlotResume() {
        final CameraYieldListener listener = yieldListener;
        final Handler handler = glHandler;
        if (listener == null || handler == null) {
            deadSlotResumePending = true;
            return;
        }
        handler.post(() -> {
            if (!running) {
                return;
            }
            // Same gate as the restart it replaces: 10 s watchdog budget, no overlapping reopen.
            deadSlotResumeInFlight = true;
            if (!restartInProgress.compareAndSet(false, true)) {
                deadSlotResumeInFlight = false;
                deadSlotResumePending = true;   // the next frame re-posts it
                return;
            }
            try {
                lastGlThreadHeartbeat = System.currentTimeMillis();
                listener.onPostReacquire();
                logger.info("Dead-slot recovery: recording/surveillance resumed on the first frame");
            } catch (Exception e) {
                logger.warn("Dead-slot resume callback error: " + e.getMessage());
            } finally {
                restartInProgress.set(false);
                deadSlotResumeInFlight = false;
            }
        });
    }

    /** reopenCamera thread: true once the gate is claimed after a dead-slot resume finished. */
    private boolean awaitDeadSlotResumeThenClaimGate() {
        long deadline = android.os.SystemClock.elapsedRealtime() + DEAD_SLOT_RESUME_WAIT_MS;
        while (deadSlotResumeInFlight
                && android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !deadSlotResumeInFlight && restartInProgress.compareAndSet(false, true);
    }

    /** Live query (the coordinator's flag is a cache that can be stale while never-streamed). */
    private boolean isNativeAppHoldingCameraNow() {
        return cameraCoordinator != null && cameraCoordinator.checkNativeAppActive();
    }

    /** The legacy ACC-ON reopen lost the restart race: retry with a fresh walk next. */
    private void noteDeadSlotReopenSkipped() {
        if (!isDeadSlotRecoveryEngaged()) {
            return;
        }
        deadSlotFreshWalkPending = true;
        deadSlotRetryCycle = 0;
        deadSlotRetryAtMs = android.os.SystemClock.elapsedRealtime();
        logger.info("Dead-slot: ACC-ON reopen skipped by an in-flight restart — fresh walk queued");
    }

    /**
     * Panoramic camera ID from the HAL's own tag map, resolved once and cached.
     *
     * <p>Returns -1 when {@code BmmCameraInfo} is unavailable — the norm on
     * DiLink 3.0, where only the native {@code BMMCamProp} holds the mapping and
     * no Java API exposes it. {@link PanoCameraFallbackOrder} handles that by
     * falling through to the raw-strip slot.
     */
    private int resolveHalPanoCameraIdHint() {
        if (halPanoCameraIdHint == Integer.MIN_VALUE) {
            int discovered;
            try {
                discovered = AvmCameraHelper.discoverPanoCameraId();
            } catch (Throwable t) {
                logger.warn("BmmCameraInfo pano-id lookup failed: " + t.getMessage());
                discovered = -1;
            }
            halPanoCameraIdHint = discovered;
            logger.info("Dead-slot fallback: BmmCameraInfo panoramic hint = "
                + (discovered >= 0 ? String.valueOf(discovered) : "unavailable"));
        }
        return halPanoCameraIdHint;
    }

    /**
     * SOTA: Yields the camera to the native BYD AVM app.
     * 
     * Called on GL thread when contention is detected (frame stall while native
     * app is active). Finalizes any active recording FIRST to prevent MP4 corruption,
     * then does a clean camera close.
     * 
     * The GL render loop continues running but skips frame processing while yielded.
     * Camera is re-acquired when onCloseCamera fires from IBYDCameraService.
     */
    private void yieldCameraInternal() {
        yieldCameraInternal(false);
    }

    private boolean yieldCameraInternal(boolean preserveDiLink5Avm) {
        if (USE_DILINK5_QCARCAM_PATH) {
            // This method owns the legacy full recorder/stream lifecycle.
            // No DI5 path may enter it: FastCam recovery retires only the
            // producer and keeps the muxer, encoder drainers and live-view
            // session attached.
            logger.error("Rejected generic camera yield on DiLink 5");
            if (!diLink5ReverseRequested && !isFreshDiLink5Reverse()) {
                requestDiLink5SourceOnlyRecovery(
                        "legacy generic yield request");
            }
            return false;
        }
        logger.info("Yielding camera to native AVM app...");
        finishDiLink5Reacquire("camera yielded to native AVM");
        // A generic yield owns the recorder/stream lifecycle below. Never let
        // a stale reverse-only marker suppress its matching post-reacquire.
        diLink5ReverseSourcePaused = false;
        diLink5SourceOnlyReacquire = false;

        // CRITICAL: Finalize active recording BEFORE closing camera.
        // FIX: Same bounded-yield pattern as restartCameraAfterError — onPreYield()
        // does muxer.stop() + FUSE rename which can wedge indefinitely on a flaky
        // USB/SD mount. Run on a worker thread with heartbeat ticking.
        if (yieldListener != null) {
            final java.util.concurrent.atomic.AtomicBoolean yieldDone =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<Exception> yieldError =
                new java.util.concurrent.atomic.AtomicReference<>(null);
            Thread yieldThread = new Thread(() -> {
                try {
                    yieldListener.onPreYield();
                } catch (Exception e) {
                    yieldError.set(e);
                } finally {
                    yieldDone.set(true);
                }
            }, "PreYield-Yield");
            yieldThread.setDaemon(true);
            yieldThread.start();

            long yieldStart = System.currentTimeMillis();
            long yieldTimeout = 8000;
            while (!yieldDone.get() &&
                   (System.currentTimeMillis() - yieldStart) < yieldTimeout) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                lastGlThreadHeartbeat = System.currentTimeMillis();
            }

            if (yieldDone.get()) {
                Exception err = yieldError.get();
                if (err != null) {
                    logger.warn("Pre-yield callback error: " + err.getMessage());
                } else {
                    logger.info("Pre-yield: recording finalized");
                }
            } else {
                logger.error("Pre-yield: onPreYield TIMED OUT after " + yieldTimeout
                    + "ms — FUSE mount wedged, proceeding with yield "
                    + "(recording may be truncated)");
            }
        }
        
        // Snapshot the stream encoder BEFORE detaching: clearStreamingComponents()
        // only nulls the reference — its drainer keeps running and must still be
        // stopped and VERIFIED below, or the camera close races it (audit finding:
        // the guard used to read the already-nulled field and skip it).
        final HardwareEventRecorderGpu yieldStreamEnc = streamEncoder;
        // Detach streaming components from the render loop
        if (streamScaler != null || streamEncoder != null) {
            clearStreamingComponents();
        }
        
        // FORTIFY FIX: Stop encoder drainer threads BEFORE closing camera —
        // and abort the yield if one is wedged (trip-safe restart already
        // requested; process exit releases the camera handle, and the daemon
        // re-registers with the coordinator on its way back up).
        if (!stopEncoderDrainersBeforeCameraClose("yield", encoder, yieldStreamEnc)) {
            return false;
        }
        
        boolean cameraClosed = true;
        if (cameraObj != null) {
            cameraClosed = closeCameraForPath(
                    cameraObj, preserveDiLink5Avm);
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.resetEventCallbackState();
                cameraCoordinator.notifyPosCloseCamera();
            }
            logger.info("Camera yielded — GL pipeline idle, waiting for onCloseCamera");
        }
        if (!cameraClosed) return false;
        
        // Restart drainer threads after camera is closed (for pre-record buffer)
        if (encoder != null) {
            encoder.restartDrainerAfterCameraClose();
        }

        // The BYD HAL just released this consumer from the old camera handle.
        // Legacy ImageReader must be recreated to avoid the one-frame-then-
        // freeze failure. DI4 must also get a fresh SurfaceTexture identity so
        // a callback queued by the yielded handle cannot certify the replacement
        // handle. DI5 is rejected at the top of this method and owns a separate
        // source-only lifecycle.
        if (!USE_DILINK5_QCARCAM_PATH) {
            consumerNeedsRecreation = true;
            logger.info("Yield: marked "
                + (USE_DILINK4_AVM_PATH ? "SurfaceTexture" : "ImageReader")
                + " consumer for recreation before reacquire");
        }

        // Start the yield-state re-acquire poller. Without this, nothing
        // observes the native app closing — the IBYDCameraUser callback path
        // is disabled and the GL frame-stall watchdog can't fire (lastFrameTime
        // is frozen because the render loop early-returns while yielded).
        if (!preserveDiLink5Avm && !diLink5SafetyDisabled) {
            startYieldPoller();
        }
        return true;
    }

    /**
     * GL-thread runnable for the camera re-acquire after a contention yield.
     *
     * <p>Extracted from the original onReacquireCamera GL-handler runnable so
     * that on a transient failure (AVMCamera.open returning false, reflection
     * NoSuchMethodError, attachSurfaceTextureToCamera failing because the BYD
     * HAL is still finalising the prior native release) we can re-post the
     * exact same flow with a backoff schedule rather than relying on the
     * yield poller to observe a second native-app transition — that
     * transition was already consumed by the first checkNativeAppActive →
     * handleNativeAppClosed call, so the poller alone could never recover
     * (audit avc-yield round 2 RESIDUAL).
     *
     * <p>Retry schedule: {@link #REACQUIRE_RETRY_DELAYS_MS} (2 s, 5 s, 10 s).
     * After all retries fail, self-restart the daemon process via
     * {@code System.exit(0)} the way the GL watchdog does at line 2400 /
     * 2767 — wrapper script respawns and full cold-boot warmup runs.
     */
    private void attemptReacquireOnGlThread() {
        if (USE_DILINK5_QCARCAM_PATH && diLink5SafetyDisabled) {
            finishDiLink5Reacquire("camera already in safe-off mode");
            logger.error("Reacquire skipped: DiLink 5 camera is in safe-off mode");
            return;
        }
        // audit avc-yield (round 7, finding pending-retry-not-cancelled):
        // capture the epoch on entry. The scheduling site (failure catch
        // below) snapshots epoch into a local closure when it postDelayed-
        // schedules a retry; that wrapper checks the snapshot vs the
        // current epoch before invoking us. This top-of-method guard is a
        // belt-and-braces fence in case a caller reaches us via a non-
        // wrapped post path. Currently no caller does, but we log to
        // catch regressions.
        //
        // audit avc-yield (round 8, finding yield-mid-backoff-cascades-to-exit):
        // if a fresh yield is in progress (coordinator reports yielded, or
        // local cameraYielded flag is true), abort silently. Do NOT close
        // cameraObj, do NOT call startCamera, do NOT bump
        // reacquireRetryCount, do NOT schedule retries. The yield poller
        // (started by yieldCameraInternal) is the authoritative recovery
        // path during yield. This prevents a Yield #1 backoff retry from
        // colliding with Yield #2 and counter-bumping into System.exit(0).
        if (isCameraReacquireBlockedByOwnershipHandoff()) {
            logger.info("Reacquire: yield-in-progress detected on entry "
                + "(diLink5=" + USE_DILINK5_QCARCAM_PATH
                + ", reverse=" + diLink5ReverseRequested
                + ", coordYielded="
                + (cameraCoordinator != null
                        && cameraCoordinator.isCameraYielded())
                + ", localYielded=" + cameraYielded
                + ") — aborting attempt; ownership handoff owns recovery");
            return;
        }
        beginDiLink5Reacquire("GL ownership reacquire attempt");
        final boolean resumeSourceOnly =
                USE_DILINK5_QCARCAM_PATH && diLink5SourceOnlyReacquire;
        try {
            // audit avc-yield (round 5, finding belt-and-braces-leak): drop the
            // retryCount>0 gate. If cameraObj is non-null on entry to a fresh
            // reacquire (e.g. a belt-and-braces poller restart raced with the
            // first attempt and we re-entered with an already-open handle), we
            // must always close+null it to avoid leaking a second cameraObj.
            if (cameraObj != null) {
                Object stale = cameraObj;
                cameraObj = null;
                logger.warn("Reacquire (attempt=" + reacquireRetryCount.get()
                    + "): clearing stale cameraObj before startCamera (always-close)");
                try {
                    closeCameraForPath(stale);
                } catch (Throwable th) {
                    logger.warn("Reacquire: closeCameraForPath on stale obj errored: "
                        + th.getMessage());
                }
                if (USE_DILINK4_AVM_PATH) {
                    // A retry is crossing another AVMCamera-handle generation.
                    // Do not reuse the SurfaceTexture whose callback queue may
                    // still contain notifications from the handle just closed.
                    consumerNeedsRecreation = true;
                }
            }

            // Recreate only when the current consumer belongs to a retired
            // camera-handle generation. For legacy that is normally the first
            // attempt after yield. For DI4 it is also every retry that had to
            // close a partially-open stale handle, preserving callback identity
            // fencing across the full reacquire ladder. DI5 never enters this
            // consumer path.
            if (!USE_DILINK5_QCARCAM_PATH && consumerNeedsRecreation) {
                try {
                    logger.info("Reacquire: recreating "
                        + (USE_DILINK4_AVM_PATH
                            ? "SurfaceTexture consumer before reopen "
                                + "(DI4 callback-generation fence)"
                            : "ImageReader consumer before reopen "
                                + "(legacy HAL frozen-frame guard)"));
                    recreateCameraSurface();
                    consumerNeedsRecreation = false;
                    lastGlThreadHeartbeat = System.currentTimeMillis();
                } catch (Throwable th) {
                    logger.warn("Reacquire: recreateCameraSurface failed — "
                        + "proceeding with stale consumer: " + th.getMessage());
                }
            } else if (!USE_DILINK5_QCARCAM_PATH) {
                logger.info("Reacquire: skipping consumer recreate "
                    + "(consumerNeedsRecreation=false, retry attempt)");
            }

            startCamera();
            // audit avc-yield (round 3, finding 10): startCamera early-returns
            // (no throw) when cameraCoordinator.isCameraYielded() is true,
            // leaving cameraObj==null. Treat that as failure here so the
            // backoff/retry path runs instead of silently returning success
            // with a null camera handle.
            if (cameraObj == null) {
                throw new IllegalStateException(
                    "startCamera returned without opening (cameraObj==null) — "
                    + "likely yielded gate hit; treating as reacquire failure");
            }
            if (USE_DILINK5_QCARCAM_PATH
                    && (diLink5ReverseRequested || isFreshDiLink5Reverse())) {
                // A reverse transition landed while the blocking native open
                // was in flight. The reverse runnable is queued behind this
                // ownership section and will close only the FastCam source.
                // Do not run generic post-reacquire callbacks or schedule a
                // retry for an intentionally superseded open.
                diLink5ReverseRequested = true;
                logger.info("DiLink 5 FastCam reacquire was superseded by "
                        + "reverse; queued source-only handoff owns cleanup");
                return;
            }
            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.resetEventCallbackState();
                cameraCoordinator.setupEventCallback(cameraObj);
            }

            boolean skipPostReacquireLifecycle =
                    USE_DILINK5_QCARCAM_PATH && resumeSourceOnly;
            if (skipPostReacquireLifecycle) {
                // Source-only arbitration never stopped either encoder
                // drainer, finalized the muxer, or detached streaming.
                logger.info("DiLink 5 source reopened; recorder and streaming sessions "
                        + "remained attached");
            } else {
                // Generic contention/fault yield did stop the recorder lane.
                // Restore its drainer and pipeline state exactly as before.
                if (encoder != null) {
                    encoder.restartDrainerAfterCameraClose();
                }
                if (yieldListener != null) {
                    try {
                        yieldListener.onPostReacquire();
                        logger.info("Post-reacquire: recording resumed");
                    } catch (Exception e) {
                        logger.warn("Post-reacquire callback error: " + e.getMessage());
                    }
                }
            }
            // Success — reset retry budget for the next yield cycle.
            int finalCount = reacquireRetryCount.get();
            if (finalCount > 0) {
                logger.info("Camera re-acquired after " + finalCount
                    + " retry attempt(s)");
            } else {
                logger.info("Camera re-acquired after contention yield");
            }
            reacquireRetryCount.set(0);
            // audit avc-yield (round 7, finding pending-retry-not-cancelled):
            // a prior failed attempt in this cycle may have scheduled a
            // postDelayed retry. Now that we've succeeded, cancel any pending
            // retry runnable AND bump the epoch so any runnable that survives
            // the removeCallbacks race short-circuits at the epoch gate
            // (avoids spurious double-teardown of the just-resumed camera).
            pendingReacquireEpoch.incrementAndGet();
            Runnable staleRetry = pendingReacquireRetry;
            pendingReacquireRetry = null;
            if (staleRetry != null && glHandler != null) {
                try {
                    glHandler.removeCallbacks(staleRetry);
                    logger.info("Reacquire success: cancelled pending "
                        + "postDelayed retry runnable");
                } catch (Throwable th) {
                    logger.warn("Reacquire success: removeCallbacks errored: "
                        + th.getMessage());
                }
            }
        } catch (Exception e) {
            int attemptIdx = reacquireRetryCount.get();
            logger.error("Failed to re-acquire camera (attempt " + attemptIdx
                + "): " + e.getMessage());

            // audit avc-yield (round 8, finding yield-mid-backoff-cascades-to-exit):
            // belt-and-braces — if a fresh yield landed during the body
            // above (e.g. native app re-engaged just before we threw the
            // "yielded gate hit" sentinel), do NOT bump counter and do NOT
            // schedule a retry. Let the yield poller drive recovery. Without
            // this, the failure path here would still cascade to System.exit
            // even if the top-of-method gate raced ahead.
            if (isCameraReacquireBlockedByOwnershipHandoff()) {
                logger.warn("Reacquire: failure caught while yielded "
                    + "(diLink5=" + USE_DILINK5_QCARCAM_PATH
                    + ", reverse=" + diLink5ReverseRequested
                    + ", coordYielded="
                    + (cameraCoordinator != null
                            && cameraCoordinator.isCameraYielded())
                    + ", localYielded=" + cameraYielded
                    + ") — suppressing retry schedule; ownership handoff owns recovery");
                return;
            }

            if (attemptIdx < REACQUIRE_RETRY_DELAYS_MS.length) {
                long delay = REACQUIRE_RETRY_DELAYS_MS[attemptIdx];
                // audit avc-yield (round 5, finding cross-thread-race):
                // CAS-bump the counter. If a peer thread (onReacquireCamera
                // listener) already reset to 0, our CAS fails — that's OK,
                // the reset path will own scheduling.
                boolean bumped = reacquireRetryCount.compareAndSet(
                    attemptIdx, attemptIdx + 1);
                if (!bumped) {
                    logger.warn("Reacquire: CAS bump failed (attempt=" + attemptIdx
                        + ", current=" + reacquireRetryCount.get()
                        + ") — concurrent reset detected, skipping retry schedule");
                    return;
                }
                logger.warn("Reacquire scheduling backoff retry "
                    + reacquireRetryCount.get() + "/" + REACQUIRE_RETRY_DELAYS_MS.length
                    + " in " + delay + "ms");
                if (glHandler != null && running) {
                    // audit avc-yield (round 7, finding pending-retry-not-cancelled):
                    // capture epoch snapshot + store runnable handle so a
                    // poller-driven success path (onReacquireCamera) or a
                    // sibling success path can cancel us before we re-enter
                    // and unconditionally tear down the just-resumed camera.
                    final int scheduledEpoch = pendingReacquireEpoch.get();
                    Runnable retryRunnable = new Runnable() {
                        @Override
                        public void run() {
                            int currentEpoch = pendingReacquireEpoch.get();
                            if (currentEpoch != scheduledEpoch) {
                                logger.info("Reacquire backoff retry: epoch "
                                    + "mismatch (scheduled=" + scheduledEpoch
                                    + ", current=" + currentEpoch
                                    + ") — superseded by poller/success path,"
                                    + " skipping stale retry");
                                return;
                            }
                            // Clear our own handle before running so a peer
                            // cancellation observes null instead of stale.
                            if (pendingReacquireRetry == this) {
                                pendingReacquireRetry = null;
                            }
                            attemptReacquireOnGlThread();
                        }
                    };
                    pendingReacquireRetry = retryRunnable;
                    boolean retryPosted =
                            glHandler.postDelayed(retryRunnable, delay);
                    if (!retryPosted) {
                        pendingReacquireRetry = null;
                        if (USE_DILINK5_QCARCAM_PATH) {
                            CameraDaemon.requestProcessRestartPreservingTrip(
                                    "DiLink 5 source retry rejected by dead GL handler");
                            finishDiLink5Reacquire(
                                    "GL handler rejected source retry");
                            logger.error("DiLink 5 camera source remains "
                                    + "unavailable; a trip-safe daemon "
                                    + "restart was requested");
                        } else {
                            logger.error("Reacquire retry rejected by GL handler");
                            CameraDaemon.requestProcessRestartPreservingTrip(
                                    "camera reacquire retry scheduling rejected");
                        }
                        return;
                    }
                } else {
                    logger.warn("Reacquire: glHandler null or pipeline stopped — "
                        + "cannot schedule retry");
                    if (USE_DILINK5_QCARCAM_PATH && running) {
                        CameraDaemon.requestProcessRestartPreservingTrip(
                                "DiLink 5 source retry handler unavailable");
                        finishDiLink5Reacquire(
                                "source retry handler unavailable");
                        logger.error("DiLink 5 camera source remains "
                                + "unavailable; a trip-safe daemon "
                                + "restart was requested");
                    }
                    return;
                }
                // Also restart poller as belt-and-braces last-ditch path.
                // Audit notes this likely won't fire (edge already consumed),
                // but it costs nothing and protects against the corner case
                // where the native app re-opens and re-closes the camera
                // during our backoff window.
                //
                // audit avc-yield (round 9, finding
                // round-8-yield-gate-deadlocks-retry): do NOT set
                // cameraYielded=true here. The round-8 entry gate at the
                // top of attemptReacquireOnGlThread aborts when
                // cameraYielded is true; setting it here would deadlock our
                // own postDelayed retry runnable (scheduled just above) the
                // moment it fires. The poller's `while (cameraYielded ...)`
                // guard means it exits immediately if cameraYielded is
                // false, which is correct — the postDelayed runnable is the
                // primary recovery path, the poller is fallback only.
                logger.info("Reacquire scheduled retry — NOT setting cameraYielded; postDelayed runnable is the primary recovery path");
                try {
                    startYieldPoller();
                } catch (Throwable th) {
                    logger.warn("Reacquire: poller fallback start errored: "
                        + th.getMessage());
                }
            } else {
                if (USE_DILINK5_QCARCAM_PATH) {
                    logger.error("DiLink 5 reacquire retries exhausted — "
                            + "leaving daemon alive and scheduling a fresh "
                            + "source-only retry");
                    reacquireRetryCount.set(0);
                    scheduleDiLink5DeferredReacquire(
                            "FastCam reacquire retries exhausted");
                    return;
                }
                // All retries exhausted — give up and let the watchdog
                // wrapper respawn the daemon. Mirrors line 2400/2767 GL
                // watchdog escape hatch.
                logger.error("Reacquire: all "
                    + REACQUIRE_RETRY_DELAYS_MS.length
                    + " retries exhausted — exiting daemon for wrapper respawn");
                reacquireRetryCount.set(0);
                CameraDaemon.requestProcessRestartPreservingTrip(
                        "camera reacquire retries exhausted");
            }
        }
    }

    /**
     * Spawns a daemon thread that polls BydCameraCoordinator.checkNativeAppActive()
     * every 5 s while {@code cameraYielded == true}. checkNativeAppActive's
     * polling branch fires handleNativeAppClosed → onReacquireCamera when the
     * native app releases the camera, which clears cameraYielded and re-opens
     * via the GL handler. The poller exits as soon as cameraYielded flips
     * false (i.e. the re-acquire path took over) or running flips false (stop).
     */
    private void startYieldPoller() {
        if (USE_DILINK5_QCARCAM_PATH && diLink5SafetyDisabled) return;
        Thread existing = yieldPollerThread;
        if (existing != null && existing.isAlive()) return;
        Thread t = new Thread(() -> {
            logger.info("Yield poller started — will check native app every "
                + YIELD_POLL_INTERVAL_MS + "ms");
            while (cameraYielded && running && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(YIELD_POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    return;
                }
                if (!cameraYielded) return;
                try {
                    if (cameraCoordinator != null) {
                        // checkNativeAppActive is the live polling path. When
                        // the native app releases the camera it internally
                        // calls handleNativeAppClosed → onReacquireCamera,
                        // which posts startCamera() to the GL handler and
                        // clears cameraYielded. Loop will then exit.
                        boolean active = cameraCoordinator.checkNativeAppActive();
                        if (!active) {
                            logger.info("Yield poller: native app no longer active — re-acquire path triggered");
                            // checkNativeAppActive already fired the re-acquire
                            // when transitioning active→inactive. Exit; the
                            // re-acquire callback will null this thread.
                            return;
                        }
                    }
                } catch (Throwable th) {
                    logger.warn("Yield poller error: " + th.getMessage());
                }
            }
            logger.info("Yield poller exiting (yielded=" + cameraYielded + ", running=" + running + ")");
        }, "PanoYieldPoller");
        t.setDaemon(true);
        yieldPollerThread = t;
        t.start();
    }

    /**
     * Interrupts and clears the yield poller. Called when cameraYielded flips
     * false (re-acquire) or when the pipeline is stopped.
     */
    private void stopYieldPoller() {
        Thread t = yieldPollerThread;
        if (t != null) {
            t.interrupt();
            yieldPollerThread = null;
        }
    }

    // ==================== Camera2 360 source ====================

    private static final long CAMERA2_FIRST_FRAME_TIMEOUT_MS = 10_000L;
    private static final long CAMERA2_REOPEN_BACKOFF_MIN_MS = 2_000L;
    private static final long CAMERA2_REOPEN_BACKOFF_MAX_MS = 60_000L;
    // Unbroken streaming needed before the reopen backoff is refilled.
    private static final long CAMERA2_STREAM_PROOF_MS = 30_000L;
    private static final long CAMERA2_ACC_ON_FRESH_FRAME_MS = 2_000L;
    // BYD camera services report private start-up codes as device errors and keep streaming.
    private static final long CAMERA2_STARTUP_ERROR_WINDOW_MS = 3_000L;
    private static final long CAMERA2_STARTUP_ERROR_GRACE_MS = 1_000L;
    // Process-wide so a pipeline rebuild cannot reset the reopen backoff; GL thread writes.
    private static volatile long sCamera2ReopenBackoffMs = CAMERA2_REOPEN_BACKOFF_MIN_MS;
    private static volatile long sCamera2NextOpenAtMs = 0L;
    private static volatile int sCamera2OpenAttempts = 0;
    private static volatile String sCamera2DisabledReason;
    private final AtomicBoolean camera2RestartQueued = new AtomicBoolean(false);
    // Per session, monotonic (a wall-clock jump must not stall recovery); GL thread writes.
    private volatile long camera2OpenedAtMs = 0L;
    private volatile long camera2SessionFirstFrameMs = 0L;
    private volatile long camera2LastFrameAtMs = 0L;
    private volatile boolean camera2StartupErrorsHeld = false;
    private volatile boolean camera2StartupErrorsProven = false;
    // GL thread only.
    private long camera2NextFrameDueNs = 0L;
    private boolean camera2StreamProven = false;

    private static long camera2NowMs() {
        return android.os.SystemClock.elapsedRealtime();
    }

    /** GL thread: opens the 360 session into cameraSurface; a refusal leaves cameraObj null. */
    private void openCamera2Source(int hintCameraId) {
        String disabled = sCamera2DisabledReason;
        if (disabled != null) {
            logger.error("camera2: 360 camera disabled until the daemon restarts: " + disabled);
            return;
        }
        long backoffMs = noteCamera2OpenAttempt();
        com.overdrive.app.camera.camera2.Camera2AvmBackend backend;
        try {
            backend = com.overdrive.app.camera.camera2.Camera2AvmBackend.open(
                    cameraSurface, hintCameraId, targetFps);
        } catch (com.overdrive.app.camera.camera2.Camera2AvmBackend.Camera2AvmException e) {
            if (e.permanent) {
                sCamera2DisabledReason = e.getMessage();
                logger.error("camera2: 360 camera unavailable until the daemon restarts: "
                        + e.getMessage());
            } else {
                logger.warn("camera2: 360 camera open failed, next attempt in " + backoffMs
                        + "ms: " + e.getMessage());
                com.overdrive.app.camera.camera2.Camera2Diagnostics.captureFailure(
                        "open", "camera2 open refused: " + e.getMessage());
            }
            return;
        } catch (RuntimeException e) {
            logger.warn("camera2: 360 camera open error, next attempt in " + backoffMs
                    + "ms: " + e);
            return;
        }
        if (!running && !legacyCameraInitializationInFlight.get()) {
            backend.close();
            logger.info("camera2: pipeline stopped during open; session released");
            return;
        }
        camera2NextFrameDueNs = 0L;
        camera2SessionFirstFrameMs = 0L;
        camera2LastFrameAtMs = 0L;
        camera2StreamProven = false;
        camera2StartupErrorsHeld = false;
        camera2StartupErrorsProven = false;
        camera2OpenedAtMs = camera2NowMs();
        cameraObj = backend;
    }

    /** GL thread: steps the reopen backoff for one attempt and returns the wait it imposes. */
    private static long noteCamera2OpenAttempt() {
        long backoffMs = sCamera2ReopenBackoffMs;
        sCamera2NextOpenAtMs = camera2NowMs() + backoffMs;
        sCamera2ReopenBackoffMs = Math.min(backoffMs * 2, CAMERA2_REOPEN_BACKOFF_MAX_MS);
        sCamera2OpenAttempts++;
        return backoffMs;
    }

    /** GL thread: paces frames to targetFps; an early frame goes straight back to the HAL. */
    private boolean camera2FrameDue() {
        long now = System.nanoTime();
        long intervalNs = 1_000_000_000L / Math.max(1, targetFps);
        long due = camera2NextFrameDueNs;
        if (due != 0L && now < due - intervalNs / 4) {
            Image early = null;
            try {
                ImageReader reader = cameraImageReader;
                if (reader != null) early = reader.acquireLatestImage();
            } catch (Throwable ignored) {
            } finally {
                if (early != null) {
                    try { early.close(); } catch (Throwable ignored) {}
                }
            }
            return false;
        }
        camera2NextFrameDueNs = (due == 0L || now - due > intervalNs)
                ? now + intervalNs
                : due + intervalNs;
        return true;
    }

    /** GL thread, per bound frame: freshness clock, plus the backoff refill once streaming is proven. */
    private void noteCamera2FrameDelivered() {
        long now = camera2NowMs();
        camera2LastFrameAtMs = now;
        if (camera2StreamProven) return;
        if (camera2SessionFirstFrameMs == 0L) {
            camera2SessionFirstFrameMs = now;
            logger.info("camera2: first 360 frame " + (now - camera2OpenedAtMs) + "ms after open");
        } else if (now - camera2SessionFirstFrameMs >= CAMERA2_STREAM_PROOF_MS) {
            camera2StreamProven = true;
            sCamera2ReopenBackoffMs = CAMERA2_REOPEN_BACKOFF_MIN_MS;
        }
    }

    /** Watchdog thread: never lets a camera2 fault end the GL hang watchdog itself. */
    private void camera2HealthTick() {
        try {
            camera2HealthTickUnchecked();
        } catch (Throwable t) {
            if (!camera2TickFaultLogged) {
                camera2TickFaultLogged = true;
                logger.error("camera2: health check failed: " + t);
            }
        }
    }

    private volatile boolean camera2TickFaultLogged = false;

    private void camera2HealthTickUnchecked() {
        if (!running || restartInProgress.get() || sCamera2DisabledReason != null) return;
        long now = camera2NowMs();
        Object source = cameraObj;
        if (source instanceof com.overdrive.app.camera.camera2.Camera2AvmBackend) {
            String failure = ((com.overdrive.app.camera.camera2.Camera2AvmBackend) source)
                    .pollDeviceFailure();
            if (failure != null) {
                failure = camera2FailureAfterStartupErrors(
                        (com.overdrive.app.camera.camera2.Camera2AvmBackend) source, failure, now);
            }
            if (failure != null) {
                com.overdrive.app.camera.camera2.Camera2Diagnostics.captureFailure(
                        "device", "camera2 session failed: " + failure);
                requestCamera2Restart(failure, source, () -> true);
            } else if (camera2SessionFirstFrameMs == 0L && camera2OpenedAtMs > 0L
                    && now - camera2OpenedAtMs > CAMERA2_FIRST_FRAME_TIMEOUT_MS) {
                com.overdrive.app.camera.camera2.Camera2Diagnostics.captureFailure(
                        "no-frame", "camera2 session delivered no frame");
                // HAL buffers vs bound frames tells "never streamed" from "frames unusable".
                requestCamera2Restart("no frame within " + CAMERA2_FIRST_FRAME_TIMEOUT_MS
                        + "ms of open (total reader callbacks=" + irFireCount
                        + ", acquired=" + irAcquireOkCount + ", bind failures=" + irBindFailCount
                        + ")", source, () -> camera2SessionFirstFrameMs == 0L);
            } else if (camera2SessionFirstFrameMs > 0L
                    && now - camera2LastFrameAtMs > FRAME_STALL_THRESHOLD_MS
                    && System.currentTimeMillis() - lastGlThreadHeartbeat < GL_THREAD_TIMEOUT_MS) {
                // Owns camera2 stall recovery on the monotonic clock; a busy GL thread is not a stall.
                requestCamera2Restart("no frame for " + (now - camera2LastFrameAtMs)
                        + "ms after streaming", source,
                        () -> camera2NowMs() - camera2LastFrameAtMs > FRAME_STALL_THRESHOLD_MS);
            }
        } else if (source == null && now >= sCamera2NextOpenAtMs) {
            requestCamera2Restart("360 camera is not open", null, () -> true);
        }
    }

    /** Null while start-up device errors are held because frames still arrive, else the restart reason. */
    private String camera2FailureAfterStartupErrors(
            com.overdrive.app.camera.camera2.Camera2AvmBackend backend, String failure, long now) {
        if (backend.hasOnlyStartupErrors(CAMERA2_STARTUP_ERROR_WINDOW_MS)) {
            long errorAt = backend.lastDeviceErrorAtMs();
            boolean awaitingFirstFrame = camera2SessionFirstFrameMs == 0L
                    && now - camera2OpenedAtMs <= CAMERA2_STARTUP_ERROR_WINDOW_MS;
            if (camera2LastFrameAtMs > errorAt || now - errorAt <= CAMERA2_STARTUP_ERROR_GRACE_MS
                    || awaitingFirstFrame) {
                camera2StartupErrorsHeld = true;
                if (!camera2StartupErrorsProven
                        && camera2LastFrameAtMs - errorAt >= CAMERA2_STARTUP_ERROR_GRACE_MS) {
                    camera2StartupErrorsProven = true;
                    backend.noteStartupErrorsTolerated();
                    logger.info("camera2: " + failure
                            + " at start-up, frames still arriving; keeping the 360 session");
                }
                return null;
            }
        }
        if (camera2StartupErrorsHeld && backend.wasDisconnected()) {
            return "camera " + backend.getCameraId() + " disconnected after its start-up errors";
        }
        return failure;
    }

    /**
     * Any thread; one queued at a time. Runs on the GL thread only if the session it
     * judged is still current and {@code stillValid} still holds there.
     */
    private void requestCamera2Restart(String reason, Object expectedSource,
            java.util.function.BooleanSupplier stillValid) {
        Handler handler = glHandler;
        if (handler == null || !camera2RestartQueued.compareAndSet(false, true)) return;
        boolean posted = handler.post(() -> {
            camera2RestartQueued.set(false);
            if (cameraObj != expectedSource || !stillValid.getAsBoolean()) return;
            restartCamera2Source(reason);
        });
        if (!posted) camera2RestartQueued.set(false);
    }

    /**
     * GL thread: closes the current Camera2 session and reopens it once the
     * backoff allows. Recorder and live-view stay attached; frames just pause.
     */
    private void restartCamera2Source(String reason) {
        if (!running || sCamera2DisabledReason != null) return;
        if (!restartInProgress.compareAndSet(false, true)) {
            logger.info("camera2: restart already in progress (" + reason + ")");
            return;
        }
        try {
            Object stale = cameraObj;
            if (stale != null) {
                logger.warn("camera2: restarting the 360 session: " + reason);
                cameraObj = null;
                closeCameraForPath(stale);
                lastGlThreadHeartbeat = System.currentTimeMillis();
            }
            // The stall monitor re-arms on the first frame of the new session.
            lastFrameTime = 0;
            if (!running || camera2NowMs() < sCamera2NextOpenAtMs) {
                return;
            }
            int attemptsBefore = sCamera2OpenAttempts;
            try {
                startCamera();
            } finally {
                // startCamera can bail before the open (stale epoch); that still costs a step.
                if (sCamera2OpenAttempts == attemptsBefore && cameraObj == null) {
                    noteCamera2OpenAttempt();
                }
            }
            lastGlThreadHeartbeat = System.currentTimeMillis();
        } catch (Exception e) {
            logger.warn("camera2: 360 reopen failed: " + e.getMessage());
        } finally {
            restartInProgress.set(false);
        }
    }

    /**
     * ACC ON: judged on the GL thread, where no restart can be mid-flight. A streaming or
     * starting session is kept; otherwise the new drive restarts the backoff ladder and reopens.
     */
    private void reopenCamera2IfNotStreaming() {
        Handler handler = glHandler;
        if (handler == null) return;
        handler.post(() -> {
            if (!running || sCamera2DisabledReason != null) return;
            if (isCamera2SessionStreamingOrStarting(cameraObj, camera2NowMs())) {
                logger.info("camera2: ACC ON — 360 session is streaming or starting, no reopen");
                return;
            }
            sCamera2ReopenBackoffMs = CAMERA2_REOPEN_BACKOFF_MIN_MS;
            sCamera2NextOpenAtMs = 0L;
            restartCamera2Source("ACC ON without a fresh 360 frame");
        });
    }

    private boolean isCamera2SessionStreamingOrStarting(Object source, long now) {
        if (source == null) return false;
        if (camera2LastFrameAtMs > 0L && now - camera2LastFrameAtMs < CAMERA2_ACC_ON_FRESH_FRAME_MS) {
            return true;
        }
        return camera2SessionFirstFrameMs == 0L
                && now - camera2OpenedAtMs < CAMERA2_FIRST_FRAME_TIMEOUT_MS;
    }

    /**
     * SOTA: Restarts the camera after a HAL error event or frame stall.
     *
     * Called on GL thread. Does a full close→reopen cycle with proper cleanup.
     * This is faster than the watchdog kill+restart because it doesn't require
     * a full process restart — just a camera reopen.
     */
    private void restartCameraAfterError() {
        if (USE_DILINK5_QCARCAM_PATH && diLink5SafetyDisabled) {
            logger.error("Camera restart skipped: DiLink 5 is in safe-off mode");
            return;
        }
        if (USE_DILINK5_QCARCAM_PATH) {
            // The generic restart below intentionally finalizes recording,
            // detaches stream components and rebuilds the whole consumer.
            // FastCam failures are producer-local and must never cross that
            // lifecycle boundary.
            requestDiLink5SourceOnlyRecovery(
                    "legacy full camera restart request");
            return;
        }
        if (USE_CAMERA2_AVM_PATH) {
            // No stall can be real inside a session's warmup grace, so such a request predates it.
            if (cameraObj != null && camera2NowMs() - camera2OpenedAtMs
                    < FRAME_STALL_WARMUP_GRACE_MS) {
                logger.info("camera2: ignoring a restart request older than the current 360 session");
                return;
            }
            restartCamera2Source("frame stall or reopen request");
            return;
        }
        if (USE_DILINK4_AVM_PATH && isDiLink4OpenInFlight()) {
            logger.info("Restart skipped — a timed-out DiLink 4 camera open"
                + " is still in flight");
            return;
        }
        // P1 #11: CAS — only one restart can be in flight. If reopenCamera
        // (daemon thread) is already restarting, return without touching the
        // flag so its finally{set(false)} doesn't get clobbered.
        if (!restartInProgress.compareAndSet(false, true)) {
            logger.info("Restart already in progress — skipping restartCameraAfterError");
            return;
        }

        // RE-ENTRY GUARD: once we've escalated to the full-rebuild restart, that
        // path released restartInProgress (below) so its own close/open isn't
        // blocked — but it now OWNS the camera teardown on its worker thread
        // (forceWarmupRestart → pipeline.stop() → camera.stop() → closeCamera).
        // onCameraError is NOT gated on halRecoveryEscalated, so a fresh error
        // could re-enter here, win the CAS, and fall through to a BARE
        // close/reopen concurrent with that teardown — redundant HAL churn that
        // can re-grab the wedged slot and perturb the warmup recovery. Bail out
        // until notePipelineRestarted() clears the flag when the rebuild completes.
        if (halRecoveryEscalated) {
            logger.info("Restart skipped — HAL full-rebuild recovery in flight"
                + " (owns camera teardown)");
            restartInProgress.set(false);
            return;
        }

        // ESCALATION (#3): a bare close/reopen cannot recover a producer route
        // or process-local camera/GL stack that remains wedged. Empirically the
        // sentry->drive blackout looped 14 bare reopens over 2 min with ZERO
        // frames. Detect that loop here: if the PREVIOUS open produced no frames
        // (frameCounter didn't advance past the snapshot taken at its open),
        // count it; once we've stacked FRAME_STALL_RESTART_ESCALATE_THRESHOLD
        // consecutive zero-frame reopens, stop looping and hand off to the
        // listener's full camera/GL rebuild.
        boolean priorOpenDeliveredNoFrame = (frameCounter == frameCounterAtOpen);
        if (priorOpenDeliveredNoFrame) {
            consecutiveZeroFrameRestarts++;
        } else {
            consecutiveZeroFrameRestarts = 0;
        }
        boolean skipParkedDi4Rebuild =
            consecutiveZeroFrameRestarts >= FRAME_STALL_RESTART_ESCALATE_THRESHOLD
                && skipDiLink4ParkedRebuild("zero-frame reopens");
        if (!skipParkedDi4Rebuild
                && consecutiveZeroFrameRestarts >= FRAME_STALL_RESTART_ESCALATE_THRESHOLD
                && yieldListener != null && !halRecoveryEscalated) {
            halRecoveryEscalated = true;
            logger.error("Frame-stall restart loop: " + consecutiveZeroFrameRestarts
                + " consecutive reopens delivered ZERO frames — bare reopen cannot "
                + "recover the AVM producer. Escalating to a full camera/GL rebuild.");
            // Release the CAS so the full-rebuild restart (which does its own
            // close/open) isn't blocked by our in-flight flag, then hand off.
            restartInProgress.set(false);
            try {
                yieldListener.onHalRecoveryNeeded();
            } catch (Throwable t) {
                // The handler normally spawns a thread whose finally calls
                // notePipelineRestarted() to clear this latch. If the dispatch
                // itself throws (e.g. Thread.start() OOM/EAGAIN), that finally
                // never runs and the latch would stick true forever — the stall
                // watchdog's defer branch would then permanently suppress all
                // bare restarts (drive-long blackout). Since a throw here means
                // NO recovery is in flight, clear the latch so the watchdog can
                // retry on the next stall tick. Defense-in-depth; the normal
                // path still clears it via notePipelineRestarted().
                halRecoveryEscalated = false;
                logger.warn("onHalRecoveryNeeded() threw: " + t.getMessage()
                    + " — cleared halRecoveryEscalated so stall watchdog can retry");
            }
            afterDiLink4Restart();
            return;
        }

        logger.info("Restarting camera after error/stall...");

        // audit avc-yield (round 3, finding 9): a stickily-true coordinator
        // yielded flag (e.g. left over from a prior cycle whose
        // active→inactive edge was masked by a binder-error short-circuit at
        // BydCameraCoordinator.checkNativeAppActive's catch path) would make
        // startCamera() early-return at line ~1285 without throwing. The open
        // thread below would then set openSuccess[0]=true with cameraObj==null,
        // and we'd "successfully" finish a restart with no live camera. Clear
        // the sticky yield up front: this is the error-recovery path; if a
        // native app is genuinely active, the very next reacquire poll will
        // re-set yielded. If it isn't (binder-error stickiness), we recover
        // immediately instead of waiting for ACC OFF→ON.
        if (cameraCoordinator != null && cameraCoordinator.isCameraYielded()) {
            logger.warn("Restart: clearing sticky coordinator yielded flag "
                + "before reopen (avoid silent startCamera no-op)");
            try {
                cameraCoordinator.clearYieldedForRestart();
            } catch (Throwable th) {
                logger.warn("Restart: clearYieldedForRestart errored: "
                    + th.getMessage());
            }
        }

        boolean releaseRestartGate = true;
        try {
            // CRITICAL: Finalize active recording BEFORE closing camera.
            // FIX: onPreYield() calls muxer.stop() + FUSE rename which can block
            // 10+ seconds on a wedged USB mount. Running it inline on the GL thread
            // starves the heartbeat and the watchdog kills the daemon — the root
            // cause of mid-drive trip loss. Run it on a bounded worker thread and
            // keep the heartbeat alive while we wait. The recording MUST finalize
            // before camera close (otherwise moov atom is never written), so we
            // wait up to 8 seconds — well within the 10s warmup watchdog timeout.
            if (yieldListener != null) {
                final java.util.concurrent.atomic.AtomicBoolean yieldDone =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
                final java.util.concurrent.atomic.AtomicReference<Exception> yieldError =
                    new java.util.concurrent.atomic.AtomicReference<>(null);
                Thread yieldThread = new Thread(() -> {
                    try {
                        yieldListener.onPreYield();
                    } catch (Exception e) {
                        yieldError.set(e);
                    } finally {
                        yieldDone.set(true);
                    }
                }, "PreYield-Finalize");
                yieldThread.setDaemon(true);
                yieldThread.start();

                // Keep heartbeat alive while waiting for FUSE I/O to complete
                long yieldStart = System.currentTimeMillis();
                long yieldTimeout = 8000; // 8s — within the 10s warmup watchdog budget
                while (!yieldDone.get() &&
                       (System.currentTimeMillis() - yieldStart) < yieldTimeout) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    lastGlThreadHeartbeat = System.currentTimeMillis();
                }

                if (yieldDone.get()) {
                    Exception err = yieldError.get();
                    if (err != null) {
                        logger.warn("Pre-restart callback error: " + err.getMessage());
                    } else {
                        logger.info("Pre-restart: recording finalized");
                    }
                } else {
                    // Timed out — the FUSE mount is wedged. The recording file will be
                    // left as .tmp (recovered on next startup by orphan tmp sweep).
                    // Proceeding with camera close is safe: the muxer is already writing
                    // to a file descriptor (not the camera), so closing the camera won't
                    // corrupt whatever did get flushed. The next onFileSaved will still
                    // fire once the mount unblocks (or the file stays .tmp and is reaped).
                    logger.error("Pre-restart: onPreYield TIMED OUT after " + yieldTimeout
                        + "ms — FUSE mount wedged, proceeding with camera restart "
                        + "(recording may be truncated)");
                }
            }
            
            // Snapshot the stream encoder BEFORE detaching — clearStreamingComponents()
            // only nulls the reference; its drainer must still be stopped and
            // verified below (see stopEncoderDrainersBeforeCameraClose).
            final HardwareEventRecorderGpu restartStreamEnc = streamEncoder;
            // Detach streaming components from the render loop
            if (streamScaler != null || streamEncoder != null) {
                clearStreamingComponents();
                logger.info("Pre-restart: streaming components detached");
            }

            // FORTIFY FIX: Stop encoder drainer threads BEFORE closing camera —
            // and abort the restart if one is wedged (trip-safe process restart
            // already requested by the helper; the finally below clears the
            // in-flight flag either way).
            if (!stopEncoderDrainersBeforeCameraClose("restart", encoder, restartStreamEnc)) {
                return;
            }

            // Close with proper cleanup + notify service
            if (cameraObj != null) {
                closeCameraForPath(cameraObj);
                cameraObj = null;
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                    cameraCoordinator.notifyPosCloseCamera();
                }
            }

            // Brief pause to let HAL settle
            Thread.sleep(500);

            // Update heartbeat so watchdog doesn't kill us during restart
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // CRITICAL: Recreate SurfaceTexture before reopening camera.
            // The BYD HAL won't deliver continuous frames to a Surface that was
            // previously connected to a different camera instance — only the first
            // frame arrives, then the stream freezes. This matches the fix already
            // present in the auto-probe path in renderLoop().
            recreateCameraSurface();
            
            // Update heartbeat again after surface recreation
            lastGlThreadHeartbeat = System.currentTimeMillis();

            // Keep the existing 2-second AVMCamera.open timeout meaningful:
            // complete the bounded AVC warmup first, then start the timed HAL
            // open worker. startCamera() sees this fresh warmup and coalesces.
            if (!AvcHalWarmup.warmupBeforeColdOpen(
                    () -> running
                        && restartInProgress.get()
                        && !CameraDaemon.isProcessRestartPending())) {
                throw new InterruptedException(
                    "AVC HAL warmup interrupted before camera restart");
            }
            lastGlThreadHeartbeat = System.currentTimeMillis();
            
            // CRITICAL FIX: Open camera on a separate thread with a timeout.
            // startCamera() calls into the BYD HAL which can block indefinitely
            // if the HAL is in a bad state. Running it on the GL thread causes
            // the watchdog to kill the process (GL heartbeat stops updating).
            // By opening on a worker thread, the GL thread stays alive and the
            // watchdog heartbeat keeps ticking. If the open times out, we let
            // the watchdog handle it on the next stall cycle instead of crash-looping.
            final java.util.concurrent.atomic.AtomicBoolean openSuccess =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<Exception>
                    openError = new java.util.concurrent.atomic.AtomicReference<>();
            Thread cameraOpenThread = new Thread(() -> {
                try {
                    startCamera();
                    // audit avc-yield (round 3, finding 10): startCamera can
                    // early-return without throwing when the coordinator
                    // reports yielded; cameraObj==null then "succeeds" by
                    // accident. Treat success as "did not throw AND cameraObj
                    // is live" so the outer recovery path actually runs.
                    openSuccess.set(cameraObj != null);
                } catch (Exception e) {
                    openError.set(e);
                }
            }, "CameraReopen");
            cameraOpenThread.start();
            if (USE_DILINK4_AVM_PATH) {
                dilink4LateOpenSinceMs = System.currentTimeMillis();
                dilink4PendingDeathAtMs = 0L;
                dilink4LateOpenSucceeded = openSuccess;
                dilink4LateOpenThread = cameraOpenThread;
            }

            long openSoftTimeout = 2_000L;
            boolean legacyOpenAttempt =
                    !USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH;
            long openStart = legacyOpenAttempt
                    ? android.os.SystemClock.elapsedRealtime()
                    : System.currentTimeMillis();
            long openHardTimeout = legacyOpenAttempt
                    ? Math.max(openSoftTimeout, GL_THREAD_WARMUP_TIMEOUT_MS)
                    : openSoftTimeout;
            while (cameraOpenThread.isAlive() &&
                   ((legacyOpenAttempt
                           ? android.os.SystemClock.elapsedRealtime()
                           : System.currentTimeMillis()) - openStart)
                           < openHardTimeout) {
                Thread.sleep(200);
                lastGlThreadHeartbeat = System.currentTimeMillis();
            }
            if (USE_DILINK4_AVM_PATH && !cameraOpenThread.isAlive()
                    && dilink4LateOpenThread == cameraOpenThread) {
                dilink4LateOpenThread = null;
            }

            if (!openSuccess.get()) {
                if (cameraOpenThread.isAlive()) {
                    if (legacyOpenAttempt) {
                        // A timed-out Binder open cannot be safely abandoned:
                        // the worker still owns the same start epoch and may
                        // publish cameraObj after this method returns. Clearing
                        // restartInProgress would then allow another close/open
                        // to overlap it, while the late worker never runs the
                        // callback/drainer/post-reacquire completion below.
                        // Keep the restart gate latched and use the bounded
                        // camera-release restart; process death is the only
                        // reliable cancellation for an in-flight HAL open.
                        releaseRestartGate = false;
                        CameraDaemon.requestUrgentCameraReleaseRestart(
                                "legacy AVMCamera open blocked for "
                                        + openHardTimeout + "ms");
                        logger.error("Legacy camera open remained blocked for "
                                + openHardTimeout
                                + "ms — terminal restart armed; overlapping "
                                + "reopen attempts are fenced");
                    } else {
                        // Preserve the existing DiLink 4 behavior exactly;
                        // this audit is intentionally scoped to legacy modes.
                        logger.warn("Camera open timed out after "
                                + openSoftTimeout
                                + "ms — will retry on next stall cycle");
                    }
                    return;
                }
                // The worker has finished: a constructor-fallback open that
                // returned false, or a throwing attach, leaves cameraObj set.
                // Re-read: it may have succeeded after the first check.
                if (!openSuccess.get()) {
                    closeHalfOpenDiLink4Camera();
                }
                if (openError.get() != null) {
                    throw openError.get();
                }
                // audit avc-yield (round 3, finding 10): no throw but
                // cameraObj is still null — startCamera short-circuited.
                // Surface the failure so the outer catch logs and the GL
                // watchdog gets a chance to escalate, instead of pretending
                // the restart succeeded with a dead camera handle.
                if (cameraObj == null) {
                    throw new IllegalStateException(
                        "startCamera returned without opening (cameraObj==null) — "
                        + "treating restart as failed");
                }
            }
            if (USE_DILINK4_AVM_PATH && dilink4LateOpenThread == cameraOpenThread) {
                dilink4LateOpenThread = null;
                dilink4LateOpenSucceeded = null;
            }
            
            // Update heartbeat after successful open
            lastGlThreadHeartbeat = System.currentTimeMillis();

            // Reset the frame-stall clock to the reopen instant. Otherwise the
            // stall watchdog measures timeSinceFrame from the LAST pre-restart
            // frame, so it re-declares a stall within ~1 tick of this reopen
            // (the 4s threshold was already exceeded before we got here) and
            // tears the fresh camera straight back down — the unbreakable
            // reopen loop. Pairing this with the post-open warmup grace
            // (FRAME_STALL_WARMUP_GRACE_MS) gives the BYD HAL its full 5-8s
            // first-frame latency before any stall can fire again. startCamera
            // already set lastCameraStartTime; align lastFrameTime to it.
            //
            // EXCEPT before the very first frame (issue #170): arming the stall
            // monitor for a camera that has never streamed would let it seize
            // ownership of a dead slot at ~9s and churn same-id bare restarts —
            // each one refreshing lastCameraStartTime and starving the
            // dead-slot walk's 25s window, so later fallback candidates would
            // never be tried. Leaving it 0 keeps the never-streamed state's
            // recovery with the walk (and its retry backoff once it
            // exhausts); it also keeps the dead-slot field comment's invariant
            // — "the stall monitor's lastFrameTime > 0 gate a never-streamed
            // camera cannot satisfy" — actually true across restarts.
            lastFrameTime = firstFrameReceived ? System.currentTimeMillis() : 0;

            // Restart encoder drainer now that camera is open again
            if (encoder != null) {
                encoder.restartDrainerAfterCameraClose();
            }
            
            // Re-register event callback
            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.setupEventCallback(cameraObj);
            }
            
            // Resume recording/surveillance after camera restart.
            // Update heartbeat first: onPostReacquire → startRecording →
            // ensureStorageReady can block up to 4s on a flaky mount.
            lastGlThreadHeartbeat = System.currentTimeMillis();
            if (yieldListener != null) {
                if (deadSlotRetryCycle > 0 && !firstFrameReceived
                        && !USE_OEM_SURFACE_TEXTURE_PATH && !USE_CAMERA2_AVM_PATH) {
                    deadSlotResumePending = true;
                } else {
                    deadSlotResumePending = false;
                    try {
                        yieldListener.onPostReacquire();
                        logger.info("Post-restart: recording/surveillance resumed");
                    } catch (Exception e) {
                        logger.warn("Post-restart callback error: " + e.getMessage());
                    }
                }
            }

            logger.info("Camera restarted successfully after error");

        } catch (Exception e) {
            logger.error("Camera restart failed: " + e.getMessage());
            // If restart fails, the watchdog will eventually kill the process
            // but at least we won't crash-loop immediately
        } finally {
            if (releaseRestartGate) {
                restartInProgress.set(false);
            }
            afterDiLink4Restart();
        }
    }

    /**
     * FORTIFY FIX (audit follow-up): stop both encoder drainer threads and VERIFY
     * they exited before the caller closes the camera. The drainer calls
     * MediaCodec.dequeueOutputBuffer(), which internally touches the camera's
     * SurfaceTexture buffer queue — destroying the camera (and its native mutex)
     * while a drainer is mid-dequeue aborts the whole process with
     * "FORTIFY: pthread_mutex_lock called on a destroyed mutex". That abort is
     * WORSE than any recovery we can choose: it kills the process before the
     * trip-safe restart coordinator (which is asynchronous) can checkpoint the
     * trip. So on a wedged drainer this requests a process restart and tells
     * the caller to SKIP the camera close — process exit releases the camera
     * handle without ever racing the stuck dequeue.
     *
     * <p>Which restart depends on whether we HOLD the camera (audit follow-up,
     * "no video signal" regression vs v36.6's unconditional close): with
     * {@code cameraObj != null} the native AVM app is stuck with NO VIDEO until
     * this process dies, and the conservative coordinator can wait indefinitely
     * (its checkpoint write can BLOCK on the same wedged mount that wedged the
     * drainer), so the URGENT bounded variant is used — a short halt deadline
     * guarantees the handle is released within seconds and the wrapper
     * respawns us. Without a held handle (e.g. stop() while yielded) nothing
     * external is waiting on a release and the conservative trip-safe
     * coordinator remains the right recovery.
     *
     * <p>Takes explicit encoder SNAPSHOTS rather than reading the fields: the
     * yield/restart paths call {@link #clearStreamingComponents()} (which only
     * nulls the references — it does not stop the drainer) before closing the
     * camera, so a field read here would see null and silently skip verifying
     * the stream encoder's drainer. Callers must snapshot BEFORE detaching.
     *
     * @return true when the camera is safe to close; false when a drainer is
     *         wedged and a process restart (urgent when the camera is held,
     *         trip-safe otherwise) has been requested — the caller must abort
     *         its close path.
     */
    private boolean stopEncoderDrainersBeforeCameraClose(String where,
            HardwareEventRecorderGpu mainEnc, HardwareEventRecorderGpu streamEnc) {
        boolean safe = true;
        // Heartbeat before/between the joins: each can legitimately block 2s
        // (stopDrainerForCameraClose's full-deadline join).
        lastGlThreadHeartbeat = System.currentTimeMillis();
        if (mainEnc != null) {
            safe &= mainEnc.stopDrainerForCameraClose();
        }
        lastGlThreadHeartbeat = System.currentTimeMillis();
        if (streamEnc != null) {
            safe &= streamEnc.stopDrainerForCameraClose();
        }
        if (!safe) {
            final boolean cameraHeld = cameraObj != null;
            // Request BEFORE logging: the logger can block on the same wedged
            // FUSE/SD storage that wedged the drainer, and a blocked log call
            // ahead of the request would strand the held camera with no
            // deadline armed. The urgent variant arms its halt deadline before
            // it touches the logger for the same reason.
            try {
                if (cameraHeld) {
                    CameraDaemon.requestUrgentCameraReleaseRestart(
                        "encoder drainer wedged before camera close (" + where + ")");
                } else {
                    CameraDaemon.requestProcessRestartPreservingTrip(
                        "encoder drainer wedged before camera close (" + where + ")");
                }
            } catch (Throwable t) {
                try {
                    logger.error(where + ": process-restart request failed: "
                        + t.getMessage());
                } catch (Throwable ignored) {}
            }
            logger.error(where + ": encoder drainer wedged — closing the camera now "
                + "would abort the process (FORTIFY destroyed mutex) before the trip "
                + "checkpoint lands; skipping camera close and requested "
                + (cameraHeld ? "URGENT bounded" : "trip-safe")
                + " daemon process restart");
        }
        return safe;
    }

    /**
     * Wait briefly for legacy startup/open ownership to leave vendor code before
     * teardown touches cameraObj or EGL. A Binder open that remains in flight
     * cannot be cancelled safely in-process.
     */
    private boolean awaitLegacyCameraLifecycleIdleForStop() {
        if (!legacyWindshieldCameraTerminalRestart.get()
                && !legacyCameraInitializationInFlight.get()
                && !legacyCameraOpenInFlight.get()
                && !legacyWindshieldCameraLifecycleInFlight.get()) {
            return true;
        }

        boolean interrupted = Thread.interrupted();
        long waitStart = android.os.SystemClock.elapsedRealtime();
        try {
            while ((legacyCameraInitializationInFlight.get()
                    || legacyCameraOpenInFlight.get()
                    || legacyWindshieldCameraLifecycleInFlight.get())
                    && android.os.SystemClock.elapsedRealtime() - waitStart
                            < LEGACY_OPEN_STOP_GRACE_MS) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException waitInterrupted) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        if (!legacyWindshieldCameraTerminalRestart.get()
                && !legacyCameraInitializationInFlight.get()
                && !legacyCameraOpenInFlight.get()
                && !legacyWindshieldCameraLifecycleInFlight.get()) {
            return true;
        }

        boolean cameraOwnershipIndeterminate =
                legacyWindshieldCameraTerminalRestart.get()
                    || legacyCameraOpenInFlight.get()
                    || legacyWindshieldCameraLifecycleInFlight.get()
                    || cameraObj != null
                    || windshieldCameraObj != null;
        if (cameraOwnershipIndeterminate) {
            CameraDaemon.requestUrgentCameraReleaseRestart(
                    "Legacy camera lifecycle remained indeterminate during stop");
        } else {
            CameraDaemon.requestProcessRestartPreservingTrip(
                    "Legacy camera initialization remained in flight during stop");
        }
        logger.error("stop: legacy camera lifecycle did not quiesce within "
                + LEGACY_OPEN_STOP_GRACE_MS
                + "ms — refusing concurrent camera/EGL teardown");
        return false;
    }

    /**
     * True while this pipeline holds an open AVMCamera handle. Volatile read —
     * safe from any thread. Used to decide between the URGENT bounded restart
     * (handle held: the native AVM app has no video until this process dies)
     * and the conservative trip-safe restart (no handle: nothing external is
     * waiting on a release).
     */
    public boolean isCameraHandleHeld() {
        return cameraObj != null
            || windshieldCameraObj != null
            || legacyWindshieldCameraLifecycleInFlight.get()
            || legacyWindshieldCameraTerminalRestart.get();
    }

    /** True only while DI5 has intentionally handed its physical inputs to
     * the OEM reverse/360 view. Recorder and stream sessions remain alive, so
     * health monitors must treat the resulting no-frame interval as planned. */
    public boolean isDiLink5SourcePausedForSystemAvm() {
        return USE_DILINK5_QCARCAM_PATH &&
                (diLink5SystemAvmFrameGate ||
                        diLink5ReverseRequested ||
                        diLink5ReverseSourceTransitionInProgress ||
                        diLink5ReverseSourcePaused);
    }

    /**
     * Stops the GPU camera pipeline.
     *
     * @return true when the teardown completed cleanly; false when it was aborted
     *         or degraded by a wedged worker/GL thread (a trip-safe process restart
     *         has been requested in that case). Callers MUST NOT release
     *         recorder/encoder GL or codec state on false — the wedged native
     *         state is exactly what must not be touched, and the process exit
     *         reclaims it.
     */
    public boolean stop() {
        logger.info( "Stopping GPU camera pipeline...");
        Object sourceAtStop = cameraObj;
        if (sourceAtStop instanceof
                com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend) {
            // Stop can spend seconds quiescing encoder/GL workers after it
            // stops consuming producer frames. Arm retirement before that
            // interval so the release guard's bounded status-125 exit is
            // classified as part of this intentional shutdown.
            ((com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend)
                    sourceAtStop).markExpectedProcessRetirement();
        }
        synchronized (this) {
            running = false;
            if (USE_DILINK5_QCARCAM_PATH) {
                diLink5SourceRecoveryEpoch.incrementAndGet();
                diLink5SourceRecoveryActive.set(false);
                finishDiLink5Reacquire("pipeline stopping");
                diLink5ReverseEpoch.incrementAndGet();
                // DI5 no longer yields to reverse; keep the (now permanently
                // idle) reverse flags at their defaults.
                diLink5ReverseRequested = false;
                diLink5ReverseHandoffComplete = true;
                diLink5SystemAvmFrameGate = false;
                cameraYielded = false;
                diLink5IdleSourceReleased = false;
                diLink5IdleResumePending = false;
                diLink5IdleResumeFailed = false;
                diLink5IdleResumeStartedAtUptimeMs = 0L;
            }
        }
        // Also stop the attach-time helper threads. They deliberately do NOT test
        // `running` (start() spawns them before it sets running = true, so that
        // test would kill them on every cold start) — this sentinel is their only
        // shutdown signal on a stop that doesn't route through
        // releaseCameraConsumer.
        cameraTornDown = true;

        // Stop watchdog
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }

        // Stop yield poller (if a yield is in-flight when stop() races in)
        stopYieldPoller();

        if (!awaitLegacyCameraLifecycleIdleForStop()) {
            stopVerdictWedged = true;
            return false;
        }

        if (!awaitDiLink5OwnershipTransitionBeforeStop()) {
            stopVerdictWedged = true;
            return false;
        }
        
        // FORTIFY FIX: Stop encoder drainer threads BEFORE closing camera —
        // and abort the close entirely if one is wedged (trip-safe restart
        // already requested; process exit releases the camera handle). stop()
        // has not detached the streaming components, so the field reads here
        // are the live snapshots. Returns false so the pipeline knows this
        // stop was ABORTED and skips its own recorder/encoder release.
        // Decoupled lane: quiesce the EncoderLane FIRST, while the encoder
        // drainers are still alive — a lane blocked mid-eglSwapBuffers can
        // only complete if the encoder's output keeps draining, so stopping
        // the drainers first could wedge the very draw this shutdown waits
        // out. The shutdown also precedes camera close, releaseGl's parent-
        // EGL teardown (child share group), and the pipeline's recorder/
        // encoder release after stop() returns true. On a wedged lane the
        // stop ABORTS and requests the trip-safe restart — the same posture
        // as the drainer guard below, because proceeding would tear down the
        // codec and EGL underneath a still-executing draw.
        EncoderLane laneToStop = encoderLane;
        if (laneToStop != null) {
            if (!laneToStop.shutdown(2000)) {
                // Request BEFORE logging (the logger can block on the same
                // wedged storage that wedged the lane), and escalate urgently
                // ONLY while the camera handle is held — conservative
                // otherwise. Same posture and ordering as the drainer-close
                // helper below.
                if (cameraObj != null) {
                    CameraDaemon.requestUrgentCameraReleaseRestart(
                        "EncoderLane wedged during stop (camera held)");
                } else {
                    CameraDaemon.requestProcessRestartPreservingTrip(
                        "EncoderLane wedged during stop");
                }
                logger.error("stop: EncoderLane did not quiesce — aborting stop; "
                    + "trip-safe restart requested");
                stopVerdictWedged = true;
                return false;
            }
            encoderLane = null;
        }

        if (!stopEncoderDrainersBeforeCameraClose("stop", encoder, streamEncoder)) {
            stopVerdictWedged = true;
            return false;
        }
        
        boolean stopClean = true;

        // Close camera with proper cleanup + notify service
        if (cameraObj != null) {
            stopClean = closeCameraForPath(cameraObj);
            cameraObj = null;
            if (cameraCoordinator != null) {
                cameraCoordinator.notifyPosCloseCamera();
            }
        }
        
        // Unregister from IBYDCameraService AFTER notifying posCloseCamera.
        // Must keep the service proxy alive until the close notification is sent,
        // otherwise the native camera app never receives the "camera released" signal
        // and hangs waiting for it.
        if (cameraCoordinator != null) {
            cameraCoordinator.unregister();
        }
        
        // Cleanup on GL thread. quitSafely() below still drains messages that
        // were already posted, so a successful join implies releaseGl actually
        // ran — no separate completion latch is needed. But the post() result
        // MUST be checked: a looper that is already quitting rejects the post
        // silently, and treating "cleanup never scheduled" as a clean teardown
        // leaves the EGL context/display unreleased.
        boolean glCleanupPosted = false;
        if (glHandler != null) {
            glCleanupPosted = glHandler.post(this::releaseGl);
            if (!glCleanupPosted) {
                logger.warn("stop: releaseGl post rejected (GL looper already quitting)");
            }
        }

        // Stop GL thread. FIX (EGL-leak audit follow-up): the old join(1000)
        // (a) returned instantly if the caller arrived interrupted (swallowed
        // InterruptedException), (b) never checked isAlive() afterwards, and
        // (c) nulled glThread unconditionally — so a wedged GL thread was
        // silently abandoned with its EGL context still CURRENT. EGL defers
        // destruction of anything current on a live thread, so the context
        // (and the display releaseGl would have terminated) stays pinned in
        // the driver's context table — the same per-cycle leak the
        // GpuDownscaler teardown fix closes. Now: full-deadline join across
        // interrupts, verify the thread exited, and on failure escalate to
        // the EXISTING trip-safe process restart (same recovery path the GL
        // stall watchdog and GpuDownscaler.release use) — a process exit is
        // the only thing that frees a context pinned on a wedged thread.
        if (glThread != null) {
            final HandlerThread deadGlThread = glThread;
            deadGlThread.quitSafely();
            boolean glThreadExited = true;
            if (Thread.currentThread() != deadGlThread) {
                final boolean[] interrupted = { Thread.interrupted() };
                try {
                    glThreadExited = com.overdrive.app.util.ThreadJoins
                        .joinFullDeadline(deadGlThread, 1000, interrupted);
                } finally {
                    if (interrupted[0]) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (!glThreadExited || !glCleanupPosted) {
                stopClean = false;
                String reason = !glThreadExited
                    ? "GL thread did not exit within its full 1s deadline after quitSafely"
                    : "releaseGl was never scheduled (post rejected before looper quit)";
                logger.error("stop: " + reason + " — the camera's EGL context/display "
                    + "stays pinned; requesting trip-safe daemon process restart");
                try {
                    com.overdrive.app.daemon.CameraDaemon.requestProcessRestartPreservingTrip(
                        "PanoramicCameraGpu stop wedged: " + reason);
                } catch (Throwable t) {
                    logger.error("stop: process-restart request failed: " + t.getMessage());
                }
            }
            // Drop the reference ONLY on a verified exit (audit follow-up):
            // nulling it up front advertised "GL thread gone" to any concurrent
            // start() while a wedged thread still pinned the old context — and
            // the trip-safe restart above is ASYNCHRONOUS (its coordinator can
            // retry the trip checkpoint for a while before System.exit), so
            // that window is real. On the failure path the field keeps pointing
            // at the wedged thread: nothing may treat it as reusable, and the
            // dangling reference is the honest diagnostic state until the
            // process exit lands.
            if (glThreadExited) {
                glThread = null;
            }
        }
        
        // STICKY verdict (audit follow-up): a wedge detected by ANY stop() call
        // latches, so a second stop of the same instance can never report clean
        // over it. Example: call one fails because releaseGl's post was rejected
        // and nulls the (exited) GL thread; call two would skip the whole GL
        // block and return true — erasing the wedge. Nothing un-wedges
        // in-process, so the latch never clears.
        if (!stopClean) {
            stopVerdictWedged = true;
        }
        boolean verdict = stopClean && !stopVerdictWedged;
        logger.info( "GPU camera pipeline stopped"
            + (verdict ? "" : " (DEGRADED — wedged teardown, restart pending)"));
        return verdict;
    }

    // Sticky stop verdict — see stop(). Never cleared; only a process restart
    // (which replaces the instance) lifts it.
    private volatile boolean stopVerdictWedged = false;
    /**
     * Releases and reopens the AVMCamera without tearing down the GL pipeline.
     *
     * This is needed during ACC OFF→ON transitions. The daemon holds the camera
     * open continuously (surveillance → recording mode), which prevents the BYD
     * native camera app from getting video frames. By briefly releasing the camera,
     * the native app can grab it, and when we reopen we get added as a secondary
     * consumer via addPreviewSurface.
     */
    /**
     * Releases and reopens the AVMCamera without tearing down the GL pipeline.
     * 
     * During ACC OFF→ON, the daemon holds the camera from surveillance mode.
     * The BYD native camera app starts on ACC ON but can't get frames.
     * Releasing briefly lets the native app grab the primary slot, then we
     * get added as secondary consumer via addPreviewSurface.
     */
    public void reopenCamera() {
        reopenCamera(15000);
    }

    public void reopenCamera(long maxWaitMs) {
        if (!running) {
            logger.warn("Cannot reopen camera - not running");
            return;
        }
        // The OEM 360 view does not use Camera2, so there is nothing to hand over.
        if (USE_CAMERA2_AVM_PATH) {
            reopenCamera2IfNotStreaming();
            return;
        }

        // P1 #11: CAS — only one restart can be in flight. If
        // restartCameraAfterError (GL thread) already owns the flag, return
        // without clobbering its finally{set(false)}.
        if (!restartInProgress.compareAndSet(false, true)
                && !awaitDeadSlotResumeThenClaimGate()) {
            logger.warn("Restart already in progress — skipping reopenCamera");
            noteDeadSlotReopenSkipped();
            return;
        }

        logger.info("Reopening AVMCamera...");
        final boolean legacyOpenAttempt =
                !USE_DILINK4_AVM_PATH && !USE_DILINK5_QCARCAM_PATH;
        boolean releaseRestartGate = true;

        // CRITICAL: Mark restart-in-progress BEFORE touching the camera so the
        // GL watchdog uses GL_THREAD_WARMUP_TIMEOUT_MS (10s) instead of the
        // normal 3s. Without this, the daemon thread's polling sleep + the
        // GL thread briefly blocking on updateTexImage() against a dying HAL
        // is enough to trip the watchdog and force a full process restart on
        // every ACC OFF→ON transition. See log: "GL thread blocked for 3492ms".

        try {
            // Proper cleanup order via BydCameraCoordinator.
            // Null cameraObj BEFORE closeCamera() so the GL renderLoop's
            // `cameraObj == null` short-circuit (line ~616) kicks in immediately
            // and stops calling updateTexImage() on a HAL that's being torn down.
            if (cameraObj != null) {
                Object toClose = cameraObj;
                cameraObj = null;
                closeCameraForPath(toClose);
                if (cameraCoordinator != null) {
                    cameraCoordinator.resetEventCallbackState();
                }
                logger.info("Camera closed (proper cleanup)");
            }

            // Kick the GL heartbeat so the watchdog timer resets at the start of
            // the wait — close+log above can already have spent >1s.
            lastGlThreadHeartbeat = System.currentTimeMillis();

            // registerCameraUser is DISABLED — the event-driven branch below is
            // dead. Kept for reference; do NOT re-enable without re-validating
            // the IBYDCameraUser yield/reacquire path end-to-end.
            // if (cameraCoordinator != null && cameraCoordinator.isRegisteredAsUser()) {
            //     logger.info("Registered as camera user — waiting for onCloseCamera callback");
            //     Thread.sleep(3000);
            //     if (!cameraCoordinator.isCameraYielded()) {
            //         startCamera();
            //         if (cameraCoordinator != null && cameraObj != null) {
            //             cameraCoordinator.setupEventCallback(cameraObj);
            //         }
            //     }
            //     return;
            // }

            // Polling path — the only live path. Wait long enough for the BYD
            // native AVM app to claim the primary camera slot, then reopen as
            // secondary consumer. Sleeps in 500ms chunks so we can refresh the
            // GL watchdog heartbeat — otherwise a long single sleep on this
            // (daemon) thread can race the GL thread mid-updateTexImage and
            // make timeSinceHeartbeat exceed the threshold.
            logger.info("Polling fallback (maxWait=" + maxWaitMs + "ms)");
            final long minWaitMs = 3000;
            sleepWithHeartbeat(minWaitMs);

            if (cameraCoordinator != null && cameraCoordinator.isRegistered()) {
                long deadline = (legacyOpenAttempt
                        ? android.os.SystemClock.elapsedRealtime()
                        : System.currentTimeMillis())
                        + (maxWaitMs - minWaitMs);
                boolean nativeAppDetected = false;

                while ((legacyOpenAttempt
                        ? android.os.SystemClock.elapsedRealtime()
                        : System.currentTimeMillis()) < deadline) {
                    if (cameraCoordinator.checkNativeAppActive()) {
                        nativeAppDetected = true;
                        logger.info("Native app claimed camera (polling) — waiting for release");
                        sleepWithHeartbeat(500);
                        break;
                    }
                    sleepWithHeartbeat(500);
                }

                if (!nativeAppDetected) {
                    logger.info("Native app not detected after polling — reopening");
                }
            } else {
                long remainingWait = maxWaitMs - minWaitMs;
                logger.info("No service available — fixed delay (" + remainingWait + "ms)");
                sleepWithHeartbeat(remainingWait);
            }

            resetDeadSlotWalkForReopen();
            if (legacyOpenAttempt) {
                if (!openLegacyCameraWithHardTimeout("ACC ON camera reopen")) {
                    // The worker may still be inside the vendor Binder call.
                    // Process death is the only safe cancellation; keep the
                    // single-flight gate latched until the urgent restart lands.
                    releaseRestartGate = false;
                    return;
                }
            } else {
                startCamera();
            }

            if (cameraCoordinator != null && cameraObj != null) {
                cameraCoordinator.setupEventCallback(cameraObj);
            }

            // Reset heartbeat after a successful reopen so the next watchdog
            // tick measures from a known-good baseline.
            lastGlThreadHeartbeat = System.currentTimeMillis();
            // Reset the frame-stall clock to the reopen instant too — same
            // reason as restartCameraAfterError: this is the ACC-ON
            // surveillance->drive reopen, after which lastFrameTime still
            // points at the last sentry-era frame. Without this, the stall
            // watchdog fires within ~1 tick and the just-reopened camera is
            // torn down before its 5-8s first-frame warmup can complete
            // (the recording-blackout reopen loop). startCamera set
            // lastCameraStartTime; align lastFrameTime to it.
            //
            // Pre-first-frame, leave it 0 for the same reason as
            // restartCameraAfterError: the dead-slot walk owns the
            // never-streamed state, and arming the stall monitor here would
            // let its same-id churn starve the walk's 25s window.
            lastFrameTime = firstFrameReceived ? System.currentTimeMillis() : 0;
            logger.info("Camera reopened successfully");

        } catch (Exception e) {
            logger.error("Failed to reopen camera: " + e.getMessage(), e);
            try {
                if (cameraObj == null) {
                    logger.warn("Retry camera open...");
                    if (legacyOpenAttempt) {
                        if (!openLegacyCameraWithHardTimeout(
                                "ACC ON camera reopen retry")) {
                            releaseRestartGate = false;
                            return;
                        }
                    } else {
                        startCamera();
                    }
                    if (cameraCoordinator != null && cameraObj != null) {
                        cameraCoordinator.setupEventCallback(cameraObj);
                    }
                    lastGlThreadHeartbeat = System.currentTimeMillis();
                }
            } catch (Exception e2) {
                logger.error("Camera retry failed: " + e2.getMessage());
            }
        } finally {
            if (releaseRestartGate) {
                restartInProgress.set(false);
            }
        }
    }

    /**
     * Legacy-only bounded AVMCamera open used by the ACC ON reopen path.
     *
     * <p>The vendor Binder call can remain blocked after the caller's deadline;
     * interrupting the Java worker cannot cancel that native transaction. A
     * timeout therefore arms the bounded camera-release process restart and
     * returns {@code false}, requiring the caller to leave
     * {@link #restartInProgress} latched so no second open can overlap the late
     * worker. DiLink 4/5 never call this helper.
     */
    private boolean openLegacyCameraWithHardTimeout(String phase)
            throws Exception {
        // Keep the timeout scoped to AVMCamera/HAL acquisition. startCamera()
        // sees this just-completed warmup and coalesces its own cold-open gate.
        if (!AvcHalWarmup.warmupBeforeColdOpen(
                () -> running
                    && restartInProgress.get()
                    && !CameraDaemon.isProcessRestartPending())) {
            throw new InterruptedException(
                    "AVC HAL warmup interrupted before " + phase);
        }
        lastGlThreadHeartbeat = System.currentTimeMillis();

        final java.util.concurrent.atomic.AtomicReference<Exception> openError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread cameraOpenThread = new Thread(() -> {
            try {
                startCamera();
            } catch (Exception e) {
                // startCameraViaAvmReflection publishes cameraObj before every
                // vendor step has completed. If a later attach/start operation
                // throws, retire that partial handle on this same worker before
                // exposing the error or attempting the one bounded retry.
                Object partial = cameraObj;
                if (partial != null) {
                    cameraObj = null;
                    try {
                        closeCameraForPath(partial);
                    } catch (Throwable cleanup) {
                        logger.warn(phase + ": partial camera cleanup failed: "
                                + cleanup.getMessage());
                    }
                    if (cameraCoordinator != null) {
                        cameraCoordinator.resetEventCallbackState();
                    }
                }
                openError.set(e);
            }
        }, "LegacyCameraReopen");
        cameraOpenThread.setDaemon(true);
        cameraOpenThread.start();

        final long hardTimeoutMs = GL_THREAD_WARMUP_TIMEOUT_MS;
        final long openStartElapsed =
                android.os.SystemClock.elapsedRealtime();
        while (cameraOpenThread.isAlive()
                && android.os.SystemClock.elapsedRealtime()
                        - openStartElapsed < hardTimeoutMs) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                CameraDaemon.requestUrgentCameraReleaseRestart(
                        phase + " interrupted with AVMCamera open in flight");
                logger.error(phase + " interrupted while vendor open remained "
                        + "in flight — terminal restart armed");
                return false;
            }
            lastGlThreadHeartbeat = System.currentTimeMillis();
        }

        if (cameraOpenThread.isAlive()) {
            CameraDaemon.requestUrgentCameraReleaseRestart(
                    phase + " blocked for " + hardTimeoutMs + "ms");
            logger.error(phase + " remained blocked for " + hardTimeoutMs
                    + "ms — terminal restart armed; overlapping opens fenced");
            return false;
        }

        Exception error = openError.get();
        if (error != null) {
            throw error;
        }
        return true;
    }

    /**
     * Sleeps for {@code totalMs} milliseconds in 250ms chunks, refreshing
     * the GL watchdog heartbeat each chunk. Used while the daemon thread is
     * waiting for the BYD HAL to settle so the watchdog doesn't kill the
     * process during the wait.
     */
    private void sleepWithHeartbeat(long totalMs) throws InterruptedException {
        final long step = 250;
        long remaining = totalMs;
        while (remaining > 0 && running) {
            long chunk = Math.min(step, remaining);
            Thread.sleep(chunk);
            lastGlThreadHeartbeat = System.currentTimeMillis();
            remaining -= chunk;
        }
    }
    
    /**
     * Releases OpenGL resources.
     */
    private void releaseGl() {
        // Shut down the AI worker FIRST so any in-flight processFrame
        // completes before we tear down the consumers it might still
        // reference. The worker's drain timeout caps this at ~2s.
        if (aiLaneWorker != null) {
            try { aiLaneWorker.shutdown(); } catch (Throwable ignored) {}
            aiLaneWorker = null;
        }

        // Decoupled lane: defensive shutdown for teardown paths that reach
        // releaseGl without going through stop() (init-failure rollback).
        // Same share-group ordering rationale as the AI lane below. No-op on
        // the normal stop() path, which already shut it down.
        EncoderLane laneAtRelease = encoderLane;
        if (laneAtRelease != null) {
            try { laneAtRelease.shutdown(1500); } catch (Throwable ignored) {}
            encoderLane = null;
        }

        // Tier 1: shut down the AI-lane GL thread before destroying the
        // encoder EGL context. The lane's shared context lives in the same
        // share group as eglCore — if eglCore went down first, the lane's
        // textures/programs would become orphans and its GL teardown would
        // log spurious errors. Order: shut lane (releases its GL state on
        // its own thread, including the foveated cropper FBOs), then we
        // can safely tear down eglCore here.
        if (aiLaneGl != null) {
            try { aiLaneGl.shutdown(); } catch (Throwable ignored) {}
            aiLaneGl = null;
        }
        // foveatedCropper.release() is called by AiLaneGl.shutdown() above
        // (the cropper's GL resources live in the AI-lane context). Just
        // null the reference here.
        foveatedCropper = null;

        // Tear down the dialog-preview sampler. It owns its own EGL context
        // (shared with our eglCore) on its own HandlerThread; if we don't
        // release it the context outlives this pipeline instance and leaks
        // EGL handles every time the daemon restarts.
        synchronized (this) {
            if (highResSampler != null) {
                try { highResSampler.release(); } catch (Throwable ignored) {}
                highResSampler = null;
            }
        }

        // Fortified teardown: isolate each step so one failing release
        // (e.g. a HAL binder already dead) doesn't strand the remaining
        // GL resources and leak them across the daemon restart.
        try { stopWindshieldCameraOnGlThread(); } catch (Throwable t) { logger.warn("releaseGl: windshield teardown: " + t.getMessage()); }

        // A Camera2 session published by a restart that raced stop() must stop
        // writing into the ImageReader before the reader is released below.
        if (USE_CAMERA2_AVM_PATH) {
            Object source = cameraObj;
            if (source instanceof com.overdrive.app.camera.camera2.Camera2AvmBackend) {
                cameraObj = null;
                try { closeCameraForPath(source); } catch (Throwable t) { logger.warn("releaseGl: camera2 close: " + t.getMessage()); }
            }
        }

        // Releases whichever consumer (SurfaceTexture or ImageReader) is active.
        try { releaseCameraConsumer(); } catch (Throwable t) { logger.warn("releaseGl: consumer teardown: " + t.getMessage()); }

        // DI4-only producer notifications have their own looper so they can
        // wake frameSync while glHandler is waiting. The consumer was retired
        // above (epoch advanced + identity cleared), so any already-queued
        // callback is now harmless while the looper drains and exits.
        shutdownDiLink4FrameCallbackThread();

        // Tear down the ImageReader callback thread (full shutdown only —
        // recreateCameraSurface keeps it alive across camera re-attach).
        if (imageReaderThread != null) {
            try { imageReaderThread.quitSafely(); } catch (Throwable ignored) {}
            imageReaderThread = null;
            imageReaderHandler = null;
        }

        if (USE_DILINK5_QCARCAM_PATH) {
            try {
                if (eglCore != null && dummySurface != null) {
                    eglCore.makeCurrent(dummySurface);
                }
                com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend
                        backend = diLink5GlOwnerBackend;
                if (backend != null
                        && !backend.releaseOwnedGlResources()) {
                    logger.warn("releaseGl: deferred DiLink 5 EGLImage cleanup failed");
                } else if (backend != null) {
                    diLink5GlOwnerBackend = null;
                }
            } catch (Throwable t) {
                logger.warn("releaseGl: DiLink 5 native teardown: " + t.getMessage());
            }
        }

        if (cameraTextureId != 0) {
            try { GlUtil.deleteTexture(cameraTextureId); } catch (Throwable t) { logger.warn("releaseGl: cameraTextureId: " + t.getMessage()); }
            cameraTextureId = 0;
        }
        if (windshieldTextureId != 0) {
            try { GlUtil.deleteTexture(windshieldTextureId); } catch (Throwable t) { logger.warn("releaseGl: windshieldTextureId: " + t.getMessage()); }
            windshieldTextureId = 0;
        }

        // Decoupled lane resources. Held camera buffers were closed inline
        // behind the per-frame publish barrier; releaseCameraConsumer above
        // handled any bind that never reached its copy.
        if (USE_DECOUPLED_ENCODER_LANE) {
            if (decoupledCamRing != null) {
                try { decoupledCamRing.release(); } catch (Throwable t) { logger.warn("releaseGl: camRing: " + t.getMessage()); }
                decoupledCamRing = null;
            }
            if (decoupledWsRing != null) {
                try { decoupledWsRing.release(); } catch (Throwable t) { logger.warn("releaseGl: wsRing: " + t.getMessage()); }
                decoupledWsRing = null;
            }
            decoupledLastWsSlot = -1;
            if (cameraOesTextureId != 0) {
                try { GlUtil.deleteTexture(cameraOesTextureId); } catch (Throwable t) { logger.warn("releaseGl: cameraOesTextureId: " + t.getMessage()); }
                cameraOesTextureId = 0;
            }
        }

        // Free the OES-probe FBO/texture/program. These are lazily created
        // by ensureProbeResources() and were previously never released here,
        // leaking one FBO + texture + program per daemon restart.
        if (probeFbo != 0) {
            try { android.opengl.GLES20.glDeleteFramebuffers(1, new int[]{probeFbo}, 0); } catch (Throwable ignored) {}
            probeFbo = 0;
        }
        if (probeColorTex != 0) {
            try { android.opengl.GLES20.glDeleteTextures(1, new int[]{probeColorTex}, 0); } catch (Throwable ignored) {}
            probeColorTex = 0;
        }
        if (probeProgram != 0) {
            try { android.opengl.GLES20.glDeleteProgram(probeProgram); } catch (Throwable ignored) {}
            probeProgram = 0;
        }

        if (dummySurface != null) {
            try { if (eglCore != null) eglCore.destroySurface(dummySurface); } catch (Throwable t) { logger.warn("releaseGl: dummySurface: " + t.getMessage()); }
            dummySurface = null;
        }

        if (eglCore != null) {
            try { eglCore.release(); } catch (Throwable t) { logger.warn("releaseGl: eglCore release: " + t.getMessage()); }
            eglCore = null;
        }

        logger.info("OpenGL resources released");
    }
    
    /**
     * Sets streaming components for parallel GPU path.
     * 
     * @param streamScaler GPU stream scaler
     * @param streamEncoder Stream encoder
     */
    public void setStreamingComponents(com.overdrive.app.streaming.GpuStreamScaler streamScaler,
                                      HardwareEventRecorderGpu streamEncoder) {
        this.streamScaler = streamScaler;
        this.streamEncoder = streamEncoder;
    }

    /**
     * Sets the stream client-presence probe read by PASS 1B. When it returns
     * false (no live-view client on either the port-8887 or /ws path), the
     * render loop skips the stream raster + encode. Pass
     * {@code WebSocketStreamServer::hasActiveClients}. A null probe restores the
     * fail-open default (always render), so a wiring gap never blacks the stream.
     */
    public void setStreamClientProbe(java.util.function.BooleanSupplier probe) {
        this.streamClientProbe = (probe != null) ? probe : (() -> true);
        // Reset the edge tracker so the next active frame forces a fresh IDR.
        this.streamWasActive = false;
    }

    /**
     * Publishes the dedicated blind-spot lane's scaler+encoder to the render
     * loop (PASS 1C). The fields are volatile so the render loop's per-frame
     * snapshot read sees the write, and so cross-thread readers (calibration /
     * param tuning via getBsStreamScaler) observe the reference atomically.
     *
     * <p>Volatile alone only publishes the <em>reference</em>, not a happens-
     * before edge to the scaler's GL-resource construction. The scaler is
     * init()'d on the GL thread (handler post in GpuSurveillancePipeline), while
     * this publish is invoked from the lifecycle (enable) thread. If the bare
     * volatile write became visible to the GL render loop before that init
     * Runnable had fully constructed the scaler's GL state, the render loop
     * would call drawFrame() on a half-built scaler — undefined behaviour on
     * Adreno (program/FBO not yet created). To make the publish safe, hop it
     * onto the GL thread: the Handler is FIFO, so this write lands strictly
     * after any already-queued init Runnable, and because the render loop runs
     * on the same thread it can never observe the reference ahead of the
     * scaler's fully-constructed GL state. If we're already on the GL thread
     * (or the handler is gone during teardown), write directly.
     */
    public void setBsStreamingComponents(com.overdrive.app.streaming.GpuStreamScaler scaler,
                                         HardwareEventRecorderGpu encoder) {
        final Handler h = glHandler;
        if (h == null || h.getLooper().getThread() == Thread.currentThread()) {
            this.bsStreamScaler = scaler;
            this.bsStreamEncoder = encoder;
            return;
        }
        h.post(() -> {
            this.bsStreamScaler = scaler;
            this.bsStreamEncoder = encoder;
        });
    }

    /** Detach the blind-spot lane from the render loop (render loop sees null
     *  next frame and stops blitting it). Does NOT release the GL objects — the
     *  caller releases them on the GL thread after this returns. */
    public void clearBsStreamingComponents() {
        this.bsStreamScaler = null;
        this.bsStreamEncoder = null;
    }

    /** @return the live blind-spot scaler, or null if the BS lane isn't active. */
    public com.overdrive.app.streaming.GpuStreamScaler getBsStreamScaler() { return bsStreamScaler; }

    /** Called by GpuSurveillancePipeline when the blind-spot SurfaceControl layer
     *  transitions between shown (turn active / debug-preview) and hidden. PASS 1C
     *  uses this to skip rendering while the layer is invisible, saving a full
     *  1280×960 GPU raster pass per frame. */
    public void setBsLayerVisible(boolean visible) {
        boolean rising = visible && !this.bsLayerVisible;
        this.bsLayerVisible = visible;
        if (rising) {
            requestDiLink4ProducerRecovery("blind-spot/camera-view visible");
        }
    }

    /** Whether PASS 1C is currently drawing the BS lane (the render gate). Used by
     *  the pipeline to detect a gate/show desync and re-arm. */
    public boolean isBsLayerVisible() { return bsLayerVisible; }

    /**
     * Enables/disables the optional direct windshield camera used by the
     * dashcam recording layout. The actual AVMCamera open/close happens on
     * the GL thread; if it fails, the recorder keeps using the 360-front
     * fallback without interrupting recording.
     */
    public void setDashcamWindshieldCamera(boolean enabled, int cameraId) {
        this.windshieldEnabled = enabled && cameraId >= 0;
        this.windshieldCameraId = cameraId;
        this.windshieldOpenFailed = false;
        Handler handler = glHandler;
        if (handler != null) {
            handler.post(this::updateWindshieldCameraOnGlThread);
        }
        logger.info("Dashcam windshield source "
            + (this.windshieldEnabled ? ("enabled (id=" + cameraId + ")") : "disabled"));
    }

    /**
     * Clears streaming components (called when streaming is disabled).
     * This prevents the render loop from trying to use released surfaces.
     */
    public void clearStreamingComponents() {
        this.streamScaler = null;
        this.streamEncoder = null;
        this.streamFrameStride = 1;
        this.streamStrideCounter = 0;
        this.streamClientProbe = () -> true;
        this.streamWasActive = false;
    }
    
    /**
     * Gets the GL thread handler for posting operations.
     * 
     * @return Handler for GL thread
     */
    public Handler getGlHandler() {
        return glHandler;
    }
    
    /**
     * Checks if the camera is running.
     * 
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * True once the camera HAL has delivered at least one frame and the GL
     * thread has bound it to {@code cameraTextureId}. Used by the
     * camera-mapping dialog to decide whether a sync readback would actually
     * see real content vs. an uninitialized black texture.
     */
    public boolean isFirstFrameReceived() {
        return firstFrameReceived;
    }

    /**
     * Number of frames the GL thread has consumed from the HAL since
     * {@code start()}. Combined with {@link #isFirstFrameReceived()} this
     * lets callers wait for the BYD HAL warmup probe (frames 1–15 are
     * cold/dim on most builds) before sampling.
     */
    public int getFrameCounter() {
        return frameCounter;
    }

    /**
     * Sets the AVMCamera surface mode for addPreviewSurface().
     * Must be called before start(). Default is 0 (works on Seal).
     * Atto 1 may need mode 1 for processed panoramic output.
     *
     * On the oem SurfaceTexture path this same value is the previewIndex
     * argument to addTexture/setTexture/rmTexture: 0=firmware-default panorama
     * output, 1-4=individual viewpoints.
     */
    public void setCameraSurfaceMode(int mode) {
        this.cameraSurfaceMode = USE_PASSIVE_APA_MODE ? 0 : mode;
        logger.info("Camera surface mode set to: " + cameraSurfaceMode
            + (USE_PASSIVE_APA_MODE ? " (DiLink 4 passive APA)" : ""));
    }

    public boolean isUsingOemSurfaceTexturePath() {
        return USE_OEM_SURFACE_TEXTURE_PATH;
    }
    
    /**
     * Gets the current camera surface mode.
     */
    public int getCameraSurfaceMode() {
        return cameraSurfaceMode;
    }
    
    /**
     * Gets the active camera ID (the one currently open or selected by probe).
     */
    public int getCameraId() {
        return cameraIdOverride >= 0 ? cameraIdOverride : PHYSICAL_CAMERA_ID;
    }
    
    /**
     * Sets the AVMCamera ID to use.
     * Must be called before start(). Default is 1 (works on Seal).
     * Dolphin/Atto 1 may need ID 0.
     */
    public void setCameraId(int id) {
        this.cameraIdOverride = id;
        logger.info("Camera ID override set to: " + id);
    }
    
    /**
     * Sets the target frame rate for the active camera backend.
     * DiLink 5 applies it to the native DMA-to-Surface output pacer; legacy
     * backends push it to the HAL via AvmCameraHelper.setCameraFps. Neither
     * path requires a camera reopen.
     *
     * @param fps Desired frames per second (range enforced by callers; this
     *            method just stores and applies)
     */
    public void setTargetFps(int fps) {
        // Idempotent: skip the work (and the reflective HAL call) when the rate
        // is unchanged. RecordingModeManager.reconcileCameraProfile may re-assert
        // the same fps on every BS show/hide edge and lifecycle transition; with
        // a sustained turn signal that's many calls, and a reflective
        // setCameraFps each time would be needless churn on the HAL thread.
        if (fps == this.targetFps) {
            return;
        }
        this.targetFps = fps;
        logger.info("Target FPS set to: " + fps);
        // Keep the AI-lane GL-hop budget in sync with the new rate.
        if (sentry != null) {
            sentry.setCameraTargetFps(fps);
        }
        // Camera2: re-apply the AE range; renderLoop paces frames to targetFps.
        if (USE_CAMERA2_AVM_PATH) {
            Object source = cameraObj;
            if (source instanceof com.overdrive.app.camera.camera2.Camera2AvmBackend) {
                ((com.overdrive.app.camera.camera2.Camera2AvmBackend) source).setTargetFps(fps);
            }
            updateStreamFrameStride();
            return;
        }
        // If the camera is currently open, push the new rate to the HAL.
        // Returns false on devices where setCameraFps is rejected (e.g., the
        // BYD HAL when isValidCamera gate fails) — we log and continue; the
        // encoder reconfig will still produce the right KEY_FRAME_RATE.
        Object cam = cameraObj;
        if (USE_DILINK5_QCARCAM_PATH) {
            com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend
                .setOutputFps(fps);
        } else if (cam != null) {
            try {
                AvmCameraHelper.setCameraFps(cam, fps);
            } catch (Throwable t) {
                logger.warn("Live setCameraFps failed: " + t.getMessage());
            }
        }
        // Keep the stream-lane stride in sync: a recording-mode change can move
        // the camera HAL rate (e.g. 15→30) while a live stream is active, and the
        // stride is camFps/streamFps. No-op when no stream encoder is attached.
        updateStreamFrameStride();
    }

    /**
     * Gets the target FPS setting.
     */
    public int getTargetFps() {
        return targetFps;
    }

    /**
     * Pin the AI-lane motion-readback cadence to a fixed wall-clock interval
     * (ms), independent of the HAL delivery rate; 0 restores the default
     * frame-count modulo. Caches the value and forwards to the live AiLaneGl if
     * present (re-applied at the next lazy bring-up otherwise). Used by the
     * parked-idle throttle so dropping/ramping the HAL fps does NOT change how
     * often motion detection runs.
     */
    public void setAiReadbackMinIntervalMs(long ms) {
        long clamped = Math.max(0L, ms);
        this.aiReadbackMinIntervalMs = clamped;
        AiLaneGl lane = aiLaneGl;
        if (lane != null) {
            lane.setReadbackMinIntervalMs(clamped);
        }
    }

    /**
     * Set the recorder draw stride. The render loop draws into the recording
     * encoder's input surface only every {@code stride}-th camera frame, giving
     * an effective recording rate of ~cameraFps/stride WITHOUT a codec
     * reconfigure. Streaming and blind-spot lanes are unaffected. {@code 1}
     * restores full-rate recording (the default). Values &lt; 1 are clamped to 1.
     *
     * <p>Thread-safe: writes a volatile read by the GL render thread.
     */
    public void setRecorderFrameStride(int stride) {
        int clamped = Math.max(1, stride);
        if (clamped != recorderFrameStride) {
            recorderFrameStride = clamped;
            // Reset the phase so the FIRST frame after a stride change always
            // draws (counter % stride == 0), rather than waiting up to the old
            // phase offset. The counter is GL-thread-confined; this write from
            // the control thread is a benign racy hint — worst case the first
            // post-change draw lands one frame early/late, cosmetic. It is read
            // as `% stride` so no torn-value hazard.
            recorderStrideCounter = 0;
            logger.info("Recorder frame stride set to " + clamped
                + " (effective recording rate ≈ cameraFps/" + clamped + ")");
        }
    }

    /**
     * Gets the current recorder draw stride (1 = every frame).
     */
    public int getRecorderFrameStride() {
        return recorderFrameStride;
    }

    /**
     * Recompute the stream-lane draw stride from the current camera target fps
     * and the stream encoder's configured fps. Call after enabling streaming or
     * changing the stream quality preset. Stride 1 = every camera frame drawn to
     * the stream encoder (no savings). Values > 1 skip frames to match the
     * encoder's lower fps — same approach as {@link #setRecorderFrameStride}.
     */
    public void updateStreamFrameStride() {
        HardwareEventRecorderGpu enc = streamEncoder;
        if (enc == null) {
            streamFrameStride = 1;
            return;
        }
        int camFps = targetFps;
        int sFps = enc.getFps();
        int stride = (sFps > 0 && camFps > sFps) ? camFps / sFps : 1;
        // NEVER decimate on dilink4. This HAL emits at its own fixed low rate
        // (~4.5 fps observed) and refuses setCameraFps, so `targetFps` is a request
        // it ignored — comparing it against the encoder's rate is meaningless here,
        // and any stride > 1 would throw away real frames from a source that is
        // already starving the encoder. (Concretely: the SMOOTH preset asks 25
        // while the encoder is clamped to 10, giving stride 2 and halving an
        // already-slow feed.) Legacy keeps the full stride behaviour, where
        // targetFps is genuinely honoured by the HAL and skipping saves real work.
        if (USE_DILINK4_AVM_PATH && stride > 1) {
            logger.info("dilink4: forcing stream stride 1 (was " + stride
                + ") — HAL rate is fixed and below the request, so decimating"
                + " would drop real frames");
            stride = 1;
        }
        if (stride != streamFrameStride) {
            streamFrameStride = Math.max(1, stride);
            streamStrideCounter = 0;
            logger.info("Stream frame stride set to " + streamFrameStride
                + " (camera " + camFps + " fps → stream " + sFps + " fps)");
        }
    }

    /**
     * Master enable/disable for the recorder lane (PASS 1A H.265 mosaic). When
     * {@code false}, the render loop skips the recorder drawFrame + drainEncoder
     * entirely; the stream (PASS 1B) and blind-spot (PASS 1C) lanes are
     * unaffected. {@code true} is the default — ZERO behaviour change for every
     * recording mode. Used when the camera is kept warm ONLY for blind-spot (no
     * encoder, no pre-record ring to feed) so the H.265 encoder doesn't burn
     * Venus for footage nothing will flush.
     *
     * <p>Thread-safe: writes a volatile read by the GL render thread.
     */
    public void setRecorderLaneEnabled(boolean enabled) {
        if (enabled != recorderLaneEnabled) {
            recorderLaneEnabled = enabled;
            // Reset the stride phase so re-enabling starts on a drawn frame
            // (mirrors setRecorderFrameStride's reset rationale).
            if (enabled) recorderStrideCounter = 0;
            logger.info("Recorder lane " + (enabled ? "ENABLED" : "DISABLED (PASS 1A skipped)"));
        }
    }

    /** @return whether the recorder lane (PASS 1A) is currently drawing. */
    public boolean isRecorderLaneEnabled() {
        return recorderLaneEnabled;
    }

    /**
     * Gets the most recently measured camera FPS (over the last 2-minute
     * stats window). Returns 0 if no stats window has elapsed yet. Use this
     * to surface to the UI when HAL clamps below the requested target —
     * e.g., user picks 30, HAL emits ~26 on this device.
     */
    public float getMeasuredFps() {
        return measuredFps;
    }
    /**
     * Enables auto-probe mode: tries camera IDs 0-5 at startup to find
     * the one that produces actual image data. Logs resolution and pixel
     * content for each ID. Auto-selects the first panoramic (5120-wide) camera
     * with non-black frames.
     */
    public void setAutoProbeCameras(boolean enabled) {
        if (USE_DILINK5_QCARCAM_PATH) {
            autoProbeCameras = false;
            probeComplete = true;
            logger.info("Camera auto-probe: DISABLED (DiLink 5 fast camera path)");
            return;
        }
        this.autoProbeCameras = enabled;
        if (enabled) {
            probeComplete = false;
            probeNextCameraId = 0;
            probeNextSurfaceMode = 0;
        }
        logger.info("Camera auto-probe: " + (enabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * When true, skip frame-15/50 validation. Used when user manually set camera ID.
     */
    public void setSkipFrameValidation(boolean skip) {
        this.skipFrameValidation = skip;
        if (skip) logger.info("Frame validation SKIPPED (manual camera override)");
    }
    
    /**
     * Sets a callback to be notified when auto-probe discovers a working camera.
     * The pipeline can use this to persist the result for faster restarts.
     */
    public void setCameraProbeCallback(CameraProbeCallback callback) {
        this.probeCallback = callback;
    }
    
    /**
     * Gets the timestamp of the last frame.
     * 
     * @return Timestamp in milliseconds
     */
    public long getLastFrameTime() {
        return lastFrameTime;
    }
    
    /**
     * SOTA: Gets the BYD camera coordinator for status queries.
     */
    public BydCameraCoordinator getCameraCoordinator() {
        return cameraCoordinator;
    }
    
    /**
     * SOTA: Sets the yield listener for recording finalization during camera yield.
     * The pipeline registers this to ensure recordings are properly closed before
     * the camera is released, and resumed after re-acquisition.
     */
    public void setCameraYieldListener(CameraYieldListener listener) {
        this.yieldListener = listener;
    }

    /**
     * Clears the zero-frame-reopen escalation latch. The pipeline MUST call
     * this after it has handled onHalRecoveryNeeded() (i.e. performed its
     * full camera/GL rebuild), so a later independent HAL wedge in the same
     * drive can escalate again instead of being permanently suppressed.
     * Also resets the zero-frame counter — the rebuild is a fresh start.
     */
    public void notePipelineRestarted() {
        consecutiveZeroFrameRestarts = 0;
        halRecoveryEscalated = false;
        dilink4StallRestartAttempts = 0;
        dilink4StallRecoveryExhausted = false;
        dilink4RecoveryProofFrames = 0;
        dilink4RecoveryProofSinceMs = 0L;
        dilink4SoftRecoveryEpoch.incrementAndGet();
        dilink4SoftRecoveryInFlight.set(false);
        dilink4DeferredReopenEpoch.incrementAndGet();
        dilink4DeferredReopenPending.set(false);
        dilink4SoftRecoveryCycles = 0;
        dilink4DeathEpoch.incrementAndGet();
        dilink4ReleasedAfterDeath = false;
        dilink4DeathReopenStreak = 0;
        dilink4PendingDeathAtMs = 0L;
        // The rebuild dropped the old GL looper's watch; keep the deadline armed.
        if (USE_DILINK4_AVM_PATH && isDiLink4OpenInFlight()) {
            watchDiLink4LateOpen(dilink4LateOpenThread);
        }
    }

    // The DiLink 5 gear-driven reverse arbitration was removed: DI5 keeps the
    // FastCam source open in every gear. See shouldHoldForDiLink5Reverse().

    /**
     * SOTA: Returns true if camera is currently yielded to native BYD app.
     */
    public boolean isCameraYielded() {
        return cameraYielded;
    }
    
    /**
     * Gets the total frame count.
     * 
     * @return Frame count
     */
    public int getFrameCount() {
        return frameCounter;
    }
    
    /**
     * Returns true when camera probe is complete and frames are valid for consumption.
     * During probe, recording/streaming/AI are gated to prevent encoding BLACK frames.
     */
    public boolean isProbeComplete() {
        return probeComplete;
    }
    
    /**
     * Gets the camera width.
     * 
     * @return Width in pixels
     */
    public int getWidth() {
        return width;
    }
    
    /**
     * Gets the camera height.
     * 
     * @return Height in pixels
     */
    public int getHeight() {
        return height;
    }
    
    /**
     * Gets the latest JPEG frame for a specific camera view.
     *
     * <p>Delegates to the surveillance engine's published mosaic JPEG —
     * no GL-thread work, no AVMCamera open, no concurrent HAL claim. The
     * engine encodes the mosaic on its own worker thread once per surveillance
     * cycle (see {@link SurveillanceEngineGpu#getLatestMosaicJpeg()}); this
     * call decodes that JPEG and crops the requested quadrant.
     *
     * <p>Returns null if the engine hasn't published a JPEG yet (surveillance
     * not running, or first frames still warming up). Callers should treat
     * null as "no frame available" and retry, NOT trigger any side-effect
     * that would touch the camera HAL.
     *
     * @param cameraId 0=full frame, 1=Front, 2=Right, 3=Rear, 4=Left.
     *     Passive APA returns the full native frame for every ID because its
     *     internal panel-to-direction mapping is not known.
     * @return JPEG bytes, or null when no published mosaic is available
     */
    public byte[] getLatestJpegFrame(int cameraId) {
        SurveillanceEngineGpu engine = sentry;
        if (engine == null) return null;
        byte[] mosaicJpeg = engine.getLatestMosaicJpeg();
        if (mosaicJpeg == null || mosaicJpeg.length == 0) return null;
        if (cameraId == 0 || USE_PASSIVE_APA_MODE) return mosaicJpeg;
        return cropMosaicJpegQuadrant(mosaicJpeg, cameraId);
    }

    /**
     * Sample the live camera texture at full encoder resolution. Passive APA
     * returns its native 1280×720 frame; other layouts return their mosaic.
     *
     * <p><b>Recording-safe.</b> Runs on a dedicated GL thread with a shared
     * EGL context — does NOT block the camera GL thread, the encoder draw,
     * or {@code eglSwapBuffers}. The shared context lets us sample the
     * camera's OES texture concurrently while it's being updated by the HAL.
     * Same pattern {@link com.overdrive.app.surveillance.GpuDownscaler} uses
     * for the AI lane.
     *
     * <p>Independent of {@link SurveillanceEngineGpu}. Works whenever the
     * camera pipeline is running, including proximity-guard mode.
     *
     * @return JPEG bytes, or null when the camera isn't running yet / EGL
     *     context isn't available.
     */
    public byte[] sampleFullResMosaicJpeg() {
        if (!awaitDiLink5IdleSourceForSample("full-resolution preview")) {
            logger.warn("sampleFullResMosaicJpeg: no fresh frame after the idle reopen");
            return null;
        }
        int textureId = cameraTextureId;
        if (!isPreviewTextureAvailableForSampling(textureId)) {
            logger.warn("sampleFullResMosaicJpeg unavailable during camera "
                    + "ownership transition");
            return null;
        }
        HighResPreviewSampler sampler = ensureHighResSampler();
        if (sampler == null) {
            logger.warn("sampleFullResMosaicJpeg early-exit sampler="
                    + (sampler != null) + " textureId=" + textureId);
            return null;
        }
        // Decoupled-lane exposure note (review round 2, finding 4 — accepted
        // for v1): cameraTextureId is a ring-slot texture here and this
        // sampler takes NO slot pin, so the writer can wrap onto the slot
        // (~3 frame intervals) while the sampler's context still reads it.
        // The ring's in-reservation glFinish guarantees the content it STARTS
        // from is complete, memory is app-owned (never freed under the read),
        // and the consequence is a torn on-demand PREVIEW JPEG — cosmetic,
        // rare-path, and the same simultaneous read/rewrite exposure this
        // sampler already has against the DiLink 5 compositor output. A
        // second reader pin on CopiedFrameRing is the clean follow-up if
        // field previews ever show tearing.
        float[] offsets = quadrantStripOffsetX != null
                ? quadrantStripOffsetX.clone()
                : new float[]{0.75f, 0.50f, 0.00f, 0.25f};
        return sampler.sampleFullMosaicJpeg(textureId, width, height, offsets);
    }

    /**
     * Sample one camera tile at FULL per-camera resolution.
     * Seal: 1280×960. Tang: 1280×720. Recording-safe — same threading model
     * as {@link #sampleFullResMosaicJpeg}.
     *
     * <p>Layout-aware:
     * <ul>
     *   <li>Default mode (legacy 4-strip HAL): pass {@code sliceOffsetX} only;
     *       cornerX/cornerY default to NaN → sampler uses 4-strip math.</li>
     *   <li>DiLink 4 mode (2x2-native HAL): pass corner XY for the slice's
     *       0.5×0.5 corner → sampler uses 2x2 math.</li>
     * </ul>
     *
     * @param sliceOffsetX strip-X offset for the slice (legacy path)
     */
    public byte[] samplePerQuadrantJpeg(float sliceOffsetX) {
        return samplePerQuadrantJpeg(sliceOffsetX, Float.NaN, Float.NaN);
    }

    /**
     * Layout-aware variant. cornerX/cornerY are the slice's top-left in a
     * 2x2-native HAL frame (only used when the camera is in DiLink 4 mode).
     */
    public byte[] samplePerQuadrantJpeg(float sliceOffsetX,
                                        float cornerX, float cornerY) {
        return samplePerQuadrantJpeg(sliceOffsetX, cornerX, cornerY, 0f, 0f);
    }

    /**
     * Layout-aware variant with per-role flip flags. xFlip/yFlip apply to
     * the local 0.5×0.5 sample window when DiLink 4's HAL emits a flipped
     * tile for that role. {@code 0f, 0f} = no flip (legacy/canonical).
     */
    public byte[] samplePerQuadrantJpeg(float sliceOffsetX,
                                        float cornerX, float cornerY,
                                        float xFlip, float yFlip) {
        if (!awaitDiLink5IdleSourceForSample("camera preview")) {
            logger.warn("samplePerQuadrantJpeg: no fresh frame after the idle reopen");
            return null;
        }
        int textureId = cameraTextureId;
        if (!isPreviewTextureAvailableForSampling(textureId)) {
            logger.warn("samplePerQuadrantJpeg unavailable during camera "
                    + "ownership transition");
            return null;
        }
        HighResPreviewSampler sampler = ensureHighResSampler();
        if (sampler == null) {
            logger.warn("samplePerQuadrantJpeg early-exit sampler="
                    + (sampler != null) + " textureId=" + textureId);
            return null;
        }
        if (USE_PASSIVE_APA_MODE) {
            float[] offsets = quadrantStripOffsetX != null
                    ? quadrantStripOffsetX.clone()
                    : new float[]{0.75f, 0.50f, 0.00f, 0.25f};
            return sampler.sampleFullMosaicJpeg(
                    textureId, width, height, offsets);
        }
        // Force 2x2 math when DiLink 4 is active AND caller supplied corner
        // values; otherwise legacy 4-strip math.
        boolean useCorner = USE_OEM_SURFACE_TEXTURE_PATH
            && !Float.isNaN(cornerX) && !Float.isNaN(cornerY);
        if (useCorner) {
            return sampler.samplePerQuadrantJpeg(
                textureId, width, height, sliceOffsetX,
                cornerX, cornerY, xFlip, yFlip);
        }
        return sampler.samplePerQuadrantJpeg(
            textureId, width, height, sliceOffsetX);
    }

    /**
     * Called both before a preview request is queued and again on the sampler
     * GL thread while {@link #cameraTextureLock} is held. The second check is
     * what prevents a timed-out/queued HTTP sample from touching a texture
     * after reverse teardown has retired its backing EGLImage.
     */
    private boolean isPreviewTextureAvailableForSampling(int expectedTextureId) {
        if (expectedTextureId <= 0 || cameraTextureId != expectedTextureId) {
            return false;
        }
        return !USE_DILINK5_QCARCAM_PATH
                || (running
                        && !isDiLink5SystemAvmHandoffActive()
                        && !diLink5CloseInProgress
                        && !diLink5SafetyDisabled
                        && cameraObj != null);
    }

    /**
     * Lazily allocate the high-res sampler with a shared EGL context.
     * Returns null when the camera isn't running yet (no EGL core).
     * Allocation is one-shot — sampler thread + EGL context outlive the
     * dialog session and absorb subsequent requests cheaply.
     */
    private synchronized HighResPreviewSampler ensureHighResSampler() {
        if (highResSampler != null) return highResSampler;
        if (eglCore == null) return null;
        android.opengl.EGLContext sharedContext = eglCore.getContext();
        if (sharedContext == null
                || sharedContext == android.opengl.EGL14.EGL_NO_CONTEXT) {
            return null;
        }
        try {
            highResSampler = new HighResPreviewSampler(
                sharedContext, isTexture2D());
            highResSampler.setSourceGuard(
                    cameraTextureLock,
                    this::isPreviewTextureAvailableForSampling);
            // Layout mirrors the active camera layout mode; matrix is
            // refreshed on every consume tick so even legacy mode (which
            // uses identity) stays current.
            highResSampler.setCameraLayout(getCameraLayoutMode());
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isEnabled()) {
                highResSampler.setTextureMatrix(currentTexMatrix);
                highResSampler.setRedMaskEnabled(false);
                highResSampler.setApaCenterInset(0.0f);
            } else if (USE_OEM_SURFACE_TEXTURE_PATH) {
                highResSampler.setTextureMatrix(currentTexMatrix);
                try {
                    org.json.JSONObject camCfgHr = com.overdrive.app.config
                        .UnifiedConfigManager.loadConfig().optJSONObject("camera");
                    if (camCfgHr != null) {
                        highResSampler.setRedMaskEnabled(
                            camCfgHr.optBoolean("dilink4RedMask", false));
                        highResSampler.setApaCenterInset(CAMERA_LAYOUT_MODE == 3
                            ? (float) camCfgHr.optDouble(
                                "dilink4ApaCenterInset", 0.09375)
                            : 0.0f);
                    }
                } catch (Throwable t) {
                    logger.warn("Sampler red-mask flag read failed: " + t.getMessage());
                }
            }
            return highResSampler;
        } catch (Throwable t) {
            logger.warn("ensureHighResSampler failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Crops a 2×2-mosaic JPEG into the requested quadrant. Called on the
     * HTTP worker thread; no GL involvement. Quadrant indices match the
     * existing snapshot endpoint contract: 1=TL, 2=TR, 3=BL, 4=BR.
     */
    private static byte[] cropMosaicJpegQuadrant(byte[] mosaicJpeg, int cameraId) {
        if (cameraId < 1 || cameraId > 4) return null;
        android.graphics.Bitmap mosaic = null;
        android.graphics.Bitmap quadrant = null;
        try {
            mosaic = android.graphics.BitmapFactory.decodeByteArray(
                    mosaicJpeg, 0, mosaicJpeg.length);
            if (mosaic == null) return null;
            int qW = Math.max(1, mosaic.getWidth() / 2);
            int qH = Math.max(1, mosaic.getHeight() / 2);
            int x = (cameraId == 2 || cameraId == 4) ? qW : 0;
            int y = (cameraId == 3 || cameraId == 4) ? qH : 0;
            quadrant = android.graphics.Bitmap.createBitmap(mosaic, x, y, qW, qH);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            quadrant.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (quadrant != null) {
                try { quadrant.recycle(); } catch (Exception ignored) {}
            }
            if (mosaic != null) {
                try { mosaic.recycle(); } catch (Exception ignored) {}
            }
        }
    }
    
    /**
     * Checks CPU usage and logs warning if exceeds threshold.
     * 
     * Provides breakdown by component to identify bottlenecks.
     */
    private void checkCpuUsage() {
        long now = System.currentTimeMillis();
        if (now - lastCpuCheckTime < CPU_CHECK_INTERVAL_MS) {
            return;
        }
        
        lastCpuCheckTime = now;
        
        try {
            // Read /proc/stat for total CPU time
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();
            
            // Parse CPU times
            String[] tokens = line.split("\\s+");
            long totalCpu = 0;
            for (int i = 1; i < tokens.length; i++) {
                totalCpu += Long.parseLong(tokens[i]);
            }
            
            // Read /proc/self/stat for process CPU time
            reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/stat"));
            line = reader.readLine();
            reader.close();
            
            tokens = line.split("\\s+");
            long processCpu = Long.parseLong(tokens[13]) + Long.parseLong(tokens[14]);
            
            // Calculate CPU percentage (simplified)
            // Note: This is a rough estimate. For accurate measurement, use
            // Android Profiler or systrace.
            // Logging disabled to reduce log spam - uncomment for debugging
            // logger.debug( String.format("CPU check: process=%d, total=%d", processCpu, totalCpu));
            
        } catch (Exception e) {
            // Silent fail - CPU monitoring is optional
        }
    }
}
