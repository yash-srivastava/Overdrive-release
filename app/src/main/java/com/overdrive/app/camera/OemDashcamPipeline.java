package com.overdrive.app.camera;

import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import com.overdrive.app.telemetry.OverlayBitmapRenderer;
import com.overdrive.app.telemetry.TelemetryDataCollector;
import com.overdrive.app.telemetry.TelemetrySnapshot;
import android.opengl.GLUtils;

import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OemDashcamPipeline — single-sensor forward dashcam.
 *
 * Distinct from {@link PanoramicCameraGpu}, which owns the AVM panoramic strip
 * and its surveillance / mosaic / AI consumers. This class drives the OEM
 * forward-facing sensor (camera id resolved by
 * {@link UnifiedConfigManager#resolveOemDashcamId}) into a single MediaCodec
 * H.264/H.265 encoder + MediaMuxer for mp4 output, plus an optional stream
 * callback so {@code StreamingApiHandler} view mode 5 can multiplex the same
 * encoder bitstream over WebSocket.
 *
 * <h3>What this class does NOT do</h3>
 * <ul>
 *   <li>No GPU mosaic, no per-quadrant rearrange, no foveated cropper.</li>
 *   <li>No AI lane / YOLO — single forward sensor doesn't need it.</li>
 *   <li>No surveillance event detection — that lives on the pano pipeline.</li>
 *   <li>No HighResPreviewSampler / dialog preview path.</li>
 * </ul>
 *
 * <h3>Concurrency with pano</h3>
 * Whether this pipeline can run simultaneously with {@link PanoramicCameraGpu}
 * depends on the BYD HAL's tolerance of two concurrent {@code AVMCamera}
 * instances. {@code camera.concurrentAvmSupported} (sticky in UCM) gates the
 * "run both at once" UI; until the probe at {@link ConcurrentAvmProbe} writes
 * a value, callers should run pipelines exclusively. This class is otherwise
 * agnostic — it opens its own {@code AVMCamera(oemDashcamId)} regardless.
 *
 * <h3>Filename convention</h3>
 * Output clips are named {@code dvr_yyyyMMdd_HHmmss.mp4}, parsed by
 * {@code RecordingFile.kt}'s DVR pattern. Distinct from {@code cam_*}
 * (pano dashcam), {@code event_*} (surveillance), and {@code proximity_*}.
 *
 * <h3>Threading</h3>
 * Three threads:
 * <ol>
 *   <li>{@code glHandler} — GL render loop (camera SurfaceTexture →
 *       passthrough draw → encoder Surface).</li>
 *   <li>{@code irHandler} — ImageReader / SurfaceTexture availability
 *       callback dispatch. Separate looper so a stalled GL thread can't
 *       starve the camera consumer.</li>
 *   <li>Encoder drainer — owned by {@link HardwareEventRecorderGpu}, not
 *       this class.</li>
 * </ol>
 * No AI worker, no foveated cropper, no V2 motion thread.
 */
public class OemDashcamPipeline {
    private static final String TAG = "OemDashcamPipeline";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Default capture geometry. The OEM front sensor on Seal/Han ships 1920×1080;
    // Tang variants ship 1280×720. We probe BmmCameraInfo at start() and adopt
    // the HAL-declared dims if they're sane (16:9 in [1280×720, 1920×1080]).
    // Anything else we treat as "this id isn't an OEM dashcam at all" and refuse
    // to start — protects against accidentally opening the panoramic id and
    // recording a 5120×960 strip into a clip the user expected to be 1080p.
    private static final int DEFAULT_WIDTH = 1920;
    private static final int DEFAULT_HEIGHT = 1080;
    private static final int DEFAULT_FPS = 30;
    private static final int DEFAULT_BITRATE = 4_000_000;
    private static final int MAX_ASPECT_RATIO_TIMES_100 = 200;   // 2.0:1 — anything wider rejected
    // 1.55:1 floor — rejects 4:3 (=1.33) so a manually-overridden id pointing
    // at a pano-quadrant (1280×960 per BmmCameraInfo on Seal/Han) doesn't slip
    // through. 16:10 (=1.6) and 16:9 (=1.78) sensors still pass cleanly.
    private static final int MIN_ASPECT_RATIO_TIMES_100 = 155;
    private static final int MIN_WIDTH = 640;
    private static final int MAX_WIDTH = 3840;

    // GL watchdog — mirrors PanoramicCameraGpu.lastGlThreadHeartbeat. The
    // render loop stamps this at the top of every iteration; an external
    // watchdog Thread checks it and forces stop() if the loop hasn't progressed
    // in WATCHDOG_TIMEOUT_MS. Without this, a wedged eglCore.makeCurrent or a
    // stalled HAL leaves running=true and leaks the AVMCamera handle until
    // daemon kill.
    private static final long WATCHDOG_TIMEOUT_MS = 30_000;
    private static final long WATCHDOG_POLL_INTERVAL_MS = 5_000;
    // After this many consecutive renderLoop catch-block firings, stop the
    // pipeline — bounded retry instead of unbounded 20Hz spin on a persistently
    // failing eglCore.makeCurrent or AVMCamera HAL.
    private static final int MAX_CONSECUTIVE_RENDER_ERRORS = 30;

    // Pre-record window — small (1 s) by default. The pano pipeline's 5 s
    // pre-record is justified by surveillance event triggers; OEM dashcam is
    // purely continuous-record + manual-trigger, so a short pre-roll is
    // sufficient to capture the moment the user taps "save now".
    private static final int PRE_RECORD_SECONDS_DEFAULT = 1;

    private final String outputDir;

    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    private int fps = DEFAULT_FPS;
    private int bitrate = DEFAULT_BITRATE;
    private String codecMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
    private int oemDashcamCameraId = -1;

    // EGL + GL
    private EGLCore eglCore;
    private EGLSurface dummySurface;
    private EGLSurface encoderEglSurface;
    private int cameraTextureId = 0;
    // volatile so the GL render thread observes the caller-thread null
    // write at stopInternal teardown time without needing a happens-before
    // edge through running.set(false). Without volatile, an in-flight
    // updateTexImage could read a torn / stale reference. R8-A #22.
    private volatile SurfaceTexture cameraSurfaceTexture;
    private volatile Surface cameraSurface;

    // Passthrough shader: full-screen quad sampling EXTERNAL_OES camera
    // texture into encoder Surface. No mosaic, no quadrant selection — OEM
    // dashcam is a single forward sensor.
    private int passthroughProgram = 0;
    private int aPositionLoc = -1;
    private int aTexCoordLoc = -1;
    private int uTexMatrixLoc = -1;
    private int uTextureLoc = -1;
    private java.nio.FloatBuffer vertexBuffer;
    private java.nio.FloatBuffer texCoordBuffer;
    private final float[] texMatrix = new float[16];
    private static final float[] FULL_SCREEN_VERTS = {
        -1f, -1f,  1f, -1f,  -1f,  1f,  1f,  1f
    };
    private static final float[] FULL_SCREEN_TEX = {
        0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f
    };
    private static final String VS_PASSTHROUGH =
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTexCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
        "}\n";
    private static final String FS_PASSTHROUGH =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES uTexture;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
        "}\n";

    // Telemetry overlay shaders. Standard sampler2D (NOT OES) — the overlay
    // bitmap is uploaded via texSubImage2D from the OverlayBitmapRenderer's
    // double-buffered Bitmap. Mirrors GpuMosaicRecorder's overlay program so
    // the OEM dashcam clips render telemetry the same way pano clips do.
    private static final String VS_OVERLAY =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";
    private static final String FS_OVERLAY =
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform sampler2D uTexture;\n" +
        "void main() {\n" +
        "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
        "}\n";

    // Overlay GL resources — allocated alongside the passthrough program in
    // initEglAndEncoder() once the EGL context is current. Released next to
    // cameraTextureId / passthroughProgram on stopInternalLocked teardown.
    private int overlayProgramId = 0;
    private int overlayTextureId = 0;
    private int overlayAPositionLoc = -1;
    private int overlayATexCoordLoc = -1;
    private int overlayUTextureLoc = -1;
    private java.nio.FloatBuffer overlayVertexBuffer;
    private java.nio.FloatBuffer overlayTexCoordBuffer;
    // Renderer that produces the 1280×80 ARGB bitmap each frame. Allocated
    // lazily on the GL thread once the context is current — Bitmap creation
    // is thread-agnostic, but holding it on the GL thread keeps lifetimes
    // simple (created in init, released in stop).
    private OverlayBitmapRenderer overlayRenderer;
    private int overlayFrameCounter = 0;
    private boolean overlayTextureInitialized = false;
    private boolean overlayTextureReady = false;

    // Camera HAL
    private volatile Object cameraObj;

    // Recording encoder — H.265 @ recording quality, writes dvr_*.mp4.
    // volatile: assigned on the GL thread (initEglAndEncoder /
    // reinitializeEncoder) but read from the API thread (updateSegmentDuration,
    // isEncoderFormatAvailable, getEncoder, triggerEventRecording). Without it
    // the JMM gives no happens-before edge, so the API thread could observe a
    // stale/null reference. Mirrors GpuSurveillancePipeline.encoder.
    private volatile HardwareEventRecorderGpu encoder;
    private Surface encoderSurface;

    // Threads. Earlier audit suggested consolidating the SurfaceTexture
    // frame-available callback onto glHandler — that was wrong. The
    // renderLoop body parks in `frameSync.wait()` to wait FOR the next
    // frame; if the listener also runs on glHandler, the camera HAL's
    // notification can never dispatch (single-threaded looper, parked).
    // Result: imagePending stays false forever, no frames flow into the
    // encoder, savedFormat stays null, the rotator spin-loops on
    // "Cannot rotate: savedFormat is null". Restored as a dedicated
    // HandlerThread for the listener — the ~256 KB cost is the price of
    // a working pipeline.
    private HandlerThread glThread;
    private Handler glHandler;
    private HandlerThread irThread;
    private Handler irHandler;

    // Lifecycle / state
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean recording = new AtomicBoolean(false);
    // True when the in-flight clip was opened by the surveillance EVENT path
    // (tryStartIfIdle), as opposed to the continuous / resolver path
    // (startRecording). The trigger-lifecycle resolver must NOT finalize a
    // surveillance-owned clip on its "recordingDesired flipped false" stop
    // path — surveillance owns that clip's lifecycle via stopRecordingIfOwned.
    // Without this, the periodic self-heal recalc (every 30s) would truncate
    // every surv=smart motion clip the moment a tick observed
    // recordingDesired=false while surveillance had a clip open.
    //
    // The (recording, recordingEventOwned) PAIR is mutated and read under
    // recordingStateLock so they're always coherent: tryStartIfIdle (off the
    // surveillance thread, without LIFECYCLE_LOCK) and the resolver's
    // ownership-gated stop (under LIFECYCLE_LOCK) can run concurrently, and
    // without the pair-lock there'd be a tiny window between "recording flips
    // true" and "owner flips true" where the resolver could see
    // (recording=true, owned=false) and finalize a just-starting event clip.
    private final AtomicBoolean recordingEventOwned = new AtomicBoolean(false);
    // Guards atomic read/write of the (recording, recordingEventOwned) pair.
    private final Object recordingStateLock = new Object();
    // Reentrant lock guarding start() ↔ stopInternal(): three call paths
    // reach stopInternal (user disable via stop(), watchdog timeout,
    // render-loop max-errors) and any of them can race a freshly-launched
    // start() that just won the running.getAndSet(true) gate. Without
    // serialization, stopInternal can null the same fields a concurrent
    // start() is allocating, NPE-ing or leaking the new instance.
    // ReentrantLock (vs synchronized) so start()'s catch path can call
    // stopInternal without re-acquiring (start already holds it).
    private final java.util.concurrent.locks.ReentrantLock lifecycleLock =
        new java.util.concurrent.locks.ReentrantLock();
    private final Object frameSync = new Object();
    private volatile boolean imagePending = false;
    // Heartbeat updated at the top of every renderLoop iteration; read by the
    // watchdog Thread to detect a wedged GL thread.
    private volatile long lastRenderHeartbeat = 0L;
    // Monotonic PTS state. The BYD AVMCamera HAL doesn't honor the
    // SurfaceTexture timestamp contract — `getTimestamp()` either returns
    // the same nanosecond value frame-after-frame or advances by sub-µs
    // increments. Pano (PanoramicCameraGpu) detects this and falls back to
    // System.nanoTime(); OEM does the same here. Without it, the encoder's
    // input PTSs are non-monotonic, MediaMuxer rejects every frame after
    // the first keyframe, and we end up with a 168 KB single-frame .mp4.
    // Detection: 10 frames of <1 ms advance → latch to System.nanoTime().
    private long firstHalPtsNs = 0L;
    private int halPtsStuckCount = 0;
    private boolean useSystemNanoForPts = false;
    private long lastPtsNs = 0L;
    private static final int HAL_PTS_STUCK_THRESHOLD = 10;
    private static final long HAL_PTS_MIN_ADVANCE_NS = 1_000_000L;  // 1ms
    // Active clock domain for the current PTS run. Used to detect
    // HW→NANO transitions and re-anchor without triggering the
    // monotonic +1ns clamp (which would otherwise pin PTS forever
    // across the clock-epoch jump). Mirrors PanoramicCameraGpu's
    // ptsDomain field (PanoramicCameraGpu:~1417).
    private static final int PTS_DOMAIN_NONE = 0;
    private static final int PTS_DOMAIN_HW = 1;
    private static final int PTS_DOMAIN_NANO = 2;
    private int ptsDomain = PTS_DOMAIN_NONE;
    private Thread watchdogThread;
    // Tracks the most recent OemDvr-EncoderStop thread when its bounded
    // join times out. Static so that a subsequent restart-cfg storm
    // doesn't accumulate orphan stop-threads holding the same encRef:
    // on every new stop, if the previous wedged thread is still alive,
    // we interrupt it and skip spawning another. One leak is the cost
    // of a wedged stop; N leaks would be an OOM on rapid restart.
    private static final java.util.concurrent.atomic.AtomicReference<Thread> wedgedEncoderStopThread =
        new java.util.concurrent.atomic.AtomicReference<>(null);
    // True once initEglAndEncoder has plumbed a real shader into drawPassthrough.
    // Phase B-1 lands the EXTERNAL_OES passthrough; startRecording is now
    // allowed. The flag remains so a future no-encoder mode can re-gate.
    private static final boolean DRAW_PASSTHROUGH_IS_REAL = true;

    // Optional parent EGLCore. When non-null at start() time, the OEM
    // pipeline creates its EGL context as {@link EGLCore#createShared(EGLCore)}
    // off this parent so the EXTERNAL_OES camera texture is visible inside
    // pano's scaler context. The streaming view-5 path sets this to pano's
    // camera EGLCore; callers that don't need cross-context sharing leave
    // it null and OEM allocates a fresh independent context (current
    // recording-only behaviour).
    private volatile EGLCore parentEglCore;

    // TRUE only when this pipeline's GL context was actually created in pano's
    // EGL SHARE GROUP (EGLCore.createShared succeeded). Recording never needs
    // this — OEM blits its own texture inside its own context, so an independent
    // context records perfectly. But VIEW-6 STREAMING does: pano's stream scaler
    // binds our cameraTextureId on PANO's GL thread, and a texture name from an
    // unshared context does not exist there — it samples as undefined/black while
    // every gate (isRunning / texture id != 0 / matrix published) still reports
    // ready. That silent mismatch is why view 6 showed the AVM mosaic or black
    // while views 1-4 (which use pano's own texture) were correct, and why the
    // manual OEM off/on workaround "fixed" it (the restart won the pano race).
    // isRouteReady() now gates on this so the route is refused (and a restart
    // scheduled) instead of falsely succeeding. Set inside start()'s EGL block.
    private volatile boolean eglSharedWithPano = false;

    // Pano stream scaler this pipeline publishes its tex matrix into when
    // view-6 streaming is active. Set/cleared by GpuSurveillancePipeline
    // around bindOemSource/unbindOemSource. Per-frame the render loop
    // reads the SurfaceTexture's current transform matrix and pushes it
    // to the scaler so the scaler can sample with correct orientation.
    //
    // The previous ping-pong design (A/B buffer flip) didn't actually
    // prevent torn reads: 16 floats span two cache lines on Adreno 610,
    // so a producer reusing buffer A while the consumer was mid-arraycopy
    // out of A could observe a half-old / half-new matrix. We now allocate
    // a fresh float[16] per publish — 64 bytes × 30 fps = 1.92 KB/s, fully
    // absorbed by the young-gen TLAB with negligible GC impact. The
    // consumer reads the volatile reference once and arraycopies from
    // an immutable snapshot.
    private volatile com.overdrive.app.streaming.GpuStreamScaler streamScalerForOemPublish;

    // Stream-encoder lazy reference. We don't allocate it until a WS client
    // connects; saves ~30-40 MB while idle. Pano handles streaming via the
    // mosaic pipeline; OEM dashcam streams its own encoder output directly.

    // Telemetry — shared singleton injected from caller, refcounted via
    // setOverlayRecordingActive on the recorder side.
    private volatile TelemetryDataCollector telemetryCollector;
    private volatile boolean overlayEnabled = false;
    // Which telemetry fields the OEM dashcam overlay draws (its OWN independent
    // selection — never shared with pano/surveillance). Defaults to the legacy
    // eight-field set so behavior is unchanged until the user edits it.
    private volatile com.overdrive.app.telemetry.TelemetryFields overlayFields =
        com.overdrive.app.telemetry.TelemetryFields.legacyDefault();

    public OemDashcamPipeline(String outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * Inject the parent EGLCore to share textures/programs with. MUST be
     * called BEFORE {@link #start()} — the EGL context is created during
     * start and is immutable after. Pass null for an independent context
     * (recording-only mode).
     *
     * <p>The parent's actual GLES version drives the child; if pano's
     * camera EGL fell back to GLES2, OEM will too — see {@link
     * EGLCore#createShared}.
     */
    public void setParentEglCore(EGLCore parent) {
        this.parentEglCore = parent;
    }

    /**
     * Set/clear the pano stream scaler this pipeline publishes its OEM
     * SurfaceTexture transform matrix into. Set by
     * {@code GpuSurveillancePipeline.attachExternalStreamCallback} when
     * the user picks DVR view; cleared when the view switches back. Per
     * frame on the OEM render loop, after updateTexImage, the latest
     * texture transform is pushed to the scaler so cross-context sampling
     * stays correctly oriented.
     */
    public void setStreamScalerForOemPublish(com.overdrive.app.streaming.GpuStreamScaler scaler) {
        this.streamScalerForOemPublish = scaler;
    }

    /**
     * Start the pipeline. Reads UCM for the resolved OEM dashcam id and
     * recording quality. Throws if the id is unset (OEM dashcam unconfigured)
     * or if the HAL-declared dims look like a panoramic strip.
     */
    public void start() throws Exception {
        lifecycleLock.lock();
        try {
            if (running.getAndSet(true)) {
                logger.warn("start() called while already running");
                return;
            }

            oemDashcamCameraId = UnifiedConfigManager.resolveOemDashcamId();
            if (oemDashcamCameraId < 0) {
                running.set(false);
                throw new IllegalStateException("OEM Dashcam id unconfigured (resolveOemDashcamId returned -1)");
            }

            // Pull recording config — same UCM keys as pano so the user's quality
            // tier carries across, but capped against the bitrate budget so
            // pano + OEM combined stays under the encoder ceiling. The cap is
            // resolved by QualitySettingsApiHandler at write time; we just read.
            applyRecordingConfigFromUcm();

            // Refuse to open if HAL-declared dims look panoramic. This protects
            // against a bad oemDashcamCameraId pointing at the AVM strip.
            if (!validateHalDimsOrReject()) {
                running.set(false);
                throw new IllegalStateException(
                    "OEM dashcam id " + oemDashcamCameraId + " reports panoramic-strip dims; refusing");
            }

            startThreads();
            try {
                initEglAndEncoder();
                openCameraAndAttach();
                installFrameCallback();
                startRenderLoop();
                logger.info("OemDashcamPipeline started: " + width + "x" + height + " @ "
                    + fps + " fps, " + (bitrate / 1_000_000) + " Mbps, id=" + oemDashcamCameraId
                    + ", codec=" + (isHevc() ? "H.265" : "H.264"));
            } catch (Throwable t) {
                running.set(false);
                // Reentrant lock: stopInternal acquires the same lock,
                // safe to call from inside start's catch.
                stopInternal(true);
                throw t;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /** Stop the pipeline. Safe to call from any thread. */
    public void stop() {
        if (!running.getAndSet(false)) return;
        stopInternal(false);
    }

    /** True if start() was called and the GL/HAL handles are still live. */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Live-apply a new clip segment length (minutes) to the running OEM /
     * surveillance encoder without a reinit. Takes effect on the next
     * rotation. Called by the quality API when the user changes the shared
     * Clip Duration control. No-op if the encoder isn't up.
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

    /** True iff the pipeline has progressed far enough that downstream
     *  consumers can safely sample its EXTERNAL_OES texture and bind to
     *  its EGLCore. {@link #isRunning} flips true at the top of
     *  {@link #start()} (mutex gate) BEFORE {@code initEglAndEncoder}
     *  populates {@code cameraTextureId}, so a streaming-view-6 click
     *  arriving in that ~50–500ms window passes {@code isRunning} but
     *  finds a zero texture id. Use this method anywhere a consumer needs
     *  to know "the camera + EGL handles are actually plumbed" rather
     *  than "start() is mid-flight".
     *
     *  <p>ALSO requires {@link #isEglSharedWithPano()}: view-6 routing makes PANO's
     *  GL thread bind OUR {@code cameraTextureId}, which only names a real texture
     *  when both contexts are in one share group. Without this clause the route
     *  "succeeded" against an unshared texture and the viewer got the AVM mosaic /
     *  black while every other gate reported healthy. Recording is unaffected by
     *  this gate (it never crosses contexts) — see {@link #isRecording}. */
    public boolean isRouteReady() {
        return running.get() && cameraTextureId != 0 && cameraSurfaceTexture != null
            && eglSharedWithPano;
    }

    /** True iff this pipeline's GL context shares pano's EGL group, i.e. pano may
     *  sample our camera texture (the view-6 streaming requirement). False when OEM
     *  came up before pano and got an independent context — recording still works,
     *  but the pipeline must be restarted for view-6 streaming to render. */
    public boolean isEglSharedWithPano() {
        return eglSharedWithPano;
    }

    /** True iff a dvr_*.mp4 segment is currently being written. Independent
     *  of {@link #isRunning} — the pipeline can be warm (streaming-only)
     *  with recording=false. */
    public boolean isRecording() {
        return recording.get();
    }

    /** True iff the underlying encoder has produced its first
     *  OUTPUT_FORMAT_CHANGED, so a subsequent triggerEventRecording can
     *  build a muxer with a valid track. Polled by the lifecycle worker
     *  so it can defer recording-start until the encoder is hot.
     *  Replaces a reflective field+method walk that ran in a 50ms hot
     *  loop. */
    public boolean isEncoderFormatAvailable() {
        HardwareEventRecorderGpu enc = encoder;
        return enc != null && enc.isFormatAvailable();
    }

    /**
     * Expose the encoder for stream-routing wiring. Returns null when the
     * pipeline isn't running. Callers attach a {@link HardwareEventRecorderGpu.StreamCallback}
     * to multiplex the OEM encoder bitstream into the WebSocket sink.
     */
    public HardwareEventRecorderGpu getEncoder() {
        return encoder;
    }

    /** Trigger a continuous-recording segment. Filename is {@code dvr_*}.
     *
     *  <p>Refuses to start while {@link #DRAW_PASSTHROUGH_IS_REAL} is false —
     *  the GL passthrough shader is a Phase-9 stub today and an empty
     *  drawPassthrough would record 30 fps of black at full bitrate. Better
     *  to fail fast and surface the missing-feature error than silently
     *  burn the SD card. */
    /**
     * Open an OEM clip with optional post-event tail. The pre-record ring
     * (see {@link #resolveOemPreRecordSeconds()} + {@code init()} wiring)
     * is flushed by {@link HardwareEventRecorderGpu#triggerEventRecording},
     * so the resulting {@code dvr_*.mp4} spans
     * {@code [trigger - preRecord, trigger + postRecordMs]} the same way
     * pano's surveillance / proximity clips do.
     *
     * @param postRecordMs tail length after the engine signals event end.
     *        Pass 0 for "stop right at end-of-event" (continuous mode); pass
     *        the engine's configured post-window for parity with pano.
     */
    public boolean startRecording(long postRecordMs) {
        return startRecordingInternal(postRecordMs, false);
    }

    /**
     * Shared start path. {@code eventOwned} marks the clip as surveillance-
     * owned so the trigger-lifecycle resolver's stop path leaves it alone
     * (only {@link #stopRecordingIfOwned} may finalize it). Continuous /
     * resolver-driven recording passes false.
     */
    private boolean startRecordingInternal(long postRecordMs, boolean eventOwned) {
        if (!running.get()) return false;
        if (!DRAW_PASSTHROUGH_IS_REAL) {
            logger.warn("startRecording refused: passthrough shader not yet plumbed (Phase-9)");
            return false;
        }
        // Strict CAS: returns true only when THIS call took the recording
        // flag from false→true, so the caller can interpret the boolean
        // as "I now own the in-flight clip." Pre-fix this used
        // getAndSet(true) and returned true even when recording was
        // already running, allowing surveillance's tryStartIfIdle to
        // claim ownership of a user-initiated continuous clip and
        // finalize it prematurely on event-end (R7-A #1).
        // Flip (recording, owner) together under the pair-lock so a concurrent
        // ownership-gated resolver stop never sees a half-published state.
        synchronized (recordingStateLock) {
            if (!recording.compareAndSet(false, true)) return false;
            recordingEventOwned.set(eventOwned);
            // Parked-idle throttle: snap the encode draw-stride back to 1 the
            // instant we win the CAS — INSIDE the lock so a concurrent
            // reapplyIdleStrideAfterStop (which re-checks recording under the same
            // lock) can never clobber it after we've opened a full-rate clip. The
            // clip body then records at full frame rate from frame 1 (parity with
            // today). No-op when stride was already 1. triggerEventRecording (below)
            // issues its own splice IDR, so strided pre-roll frames stay valid.
            recorderDrawStride = 1;
        }
        String path = generateOutputPath();
        long clampedPost = Math.max(0L, postRecordMs);
        HardwareEventRecorderGpu.VideoUploadPolicy uploadPolicy = uploadPolicyFor(eventOwned);
        boolean ok = encoder != null
                && encoder.triggerEventRecording(path, clampedPost, uploadPolicy);
        if (!ok) {
            synchronized (recordingStateLock) {
                recording.set(false);
                recordingEventOwned.set(false);
            }
        }
        // Telemetry polling is gated on (overlayEnabled && running && recording)
        // — reconcile after the recording-flag transitions to grab the
        // refcount on start and release on stop.
        reconcileTelemetryHold();
        return ok;
    }

    /** Package-visible ownership-to-upload policy seam for regression tests. */
    static HardwareEventRecorderGpu.VideoUploadPolicy uploadPolicyFor(boolean eventOwned) {
        return eventOwned
                ? HardwareEventRecorderGpu.VideoUploadPolicy.SURVEILLANCE_GATED
                : HardwareEventRecorderGpu.VideoUploadPolicy.AUTOMATIC;
    }

    /** True iff the in-flight clip (if any) is surveillance-event-owned.
     *  The trigger-lifecycle resolver consults this before its stop path so
     *  it never finalizes a clip surveillance opened. */
    public boolean isRecordingEventOwned() {
        synchronized (recordingStateLock) {
            return recording.get() && recordingEventOwned.get();
        }
    }

    /** Back-compat overload — defaults to no post-roll. New callers should
     *  pass an explicit post-window (see {@link #startRecording(long)}). */
    public boolean startRecording() {
        return startRecording(0L);
    }

    /**
     * Stop the in-flight clip. {@code postRecordMs} is the tail to append
     * after stop is signalled — pano's recorder honours this internally
     * by deferring muxer finalization until the tail elapses.
     */
    public void stopRecording(long postRecordMs) {
        stopRecordingAndGetFinalizedClip(postRecordMs);
    }

    /**
     * Finalized OEM clip metadata returned to the surveillance owner. The
     * recorder itself suppresses automatic Telegram delivery for event-owned
     * clips; SurveillanceEngineGpu uses this result only after its tier gate
     * passes.
     */
    public static final class FinalizedClip {
        private final String path;
        private final int durationSeconds;

        FinalizedClip(String path, int durationSeconds) {
            this.path = path;
            this.durationSeconds = durationSeconds;
        }

        public String getPath() { return path; }
        public int getDurationSeconds() { return durationSeconds; }
    }

    private FinalizedClip stopRecordingAndGetFinalizedClip(long postRecordMs) {
        HardwareEventRecorderGpu enc;
        String path;
        synchronized (recordingStateLock) {
            if (!recording.getAndSet(false)) return null;
            recordingEventOwned.set(false);
            enc = encoder;
            path = enc != null ? enc.getCurrentOutputPath() : null;
        }
        if (enc != null) enc.stopEventRecording(true, Math.max(0L, postRecordMs));
        reapplyIdleStrideAfterStop();
        reconcileTelemetryHold();
        if (path == null || !new File(path).isFile()) return null;
        return new FinalizedClip(path, enc != null ? enc.getLastFinalizedDurationSec() : 0);
    }

    /** Back-compat overload — no post-roll. */
    public void stopRecording() {
        stopRecording(0L);
    }

    /**
     * Resolver-only stop: finalize the in-flight clip ONLY if it is NOT
     * surveillance-event-owned. The ownership check and the recording-flag
     * clear happen inside a SINGLE {@link #recordingStateLock} critical
     * section so a concurrent {@link #tryStartIfIdle} cannot flip the
     * (recording, recordingEventOwned) pair into the event-owned state in
     * the gap between a separate check and stop. Returns {@code true} iff
     * this call actually stopped a (resolver-owned) clip.
     *
     * <p>Replaces the resolver's previous
     * {@code isRecording() && !isRecordingEventOwned() then stopRecording()}
     * sequence, which was a check-then-act race: an independent
     * {@code handleWriterAbort} clearing {@code recording} followed by a
     * surveillance {@code tryStartIfIdle} could open an event-owned clip
     * between the gate and the ownership-blind stop, truncating it.
     */
    public boolean stopRecordingIfNotEventOwned(long postRecordMs) {
        synchronized (recordingStateLock) {
            if (!recording.get() || recordingEventOwned.get()) return false;
            recording.set(false);
            recordingEventOwned.set(false);
        }
        if (encoder != null) encoder.stopEventRecording(true, Math.max(0L, postRecordMs));
        reapplyIdleStrideAfterStop();
        reconcileTelemetryHold();
        return true;
    }

    /** Back-compat overload — no post-roll. */
    public boolean stopRecordingIfNotEventOwned() {
        return stopRecordingIfNotEventOwned(0L);
    }

    /**
     * Surveillance-only entry: start a fresh recording ONLY if the pipeline
     * is currently idle. Returns {@code true} when this call actually opened
     * a new clip — the caller is responsible for matching with
     * {@link #stopRecordingIfOwned(boolean, long)}. Returns {@code false} when
     * a user-initiated continuous recording is already in flight; the caller
     * must NOT stop it on event end.
     *
     * <p>Audit motivation: when OEM is in continuous-record mode (user
     * enabled) AND surveillance fires, the previous code's
     * {@link #startRecording()} returned {@code true} for the already-running
     * case, and the matching {@code stopRecording()} on event-end finalized
     * the user's clip prematurely.
     */
    public boolean tryStartIfIdle(long postRecordMs) {
        return startRecordingInternal(postRecordMs, true);
    }

    /** Back-compat overload — no post-roll. */
    public boolean tryStartIfIdle() {
        return tryStartIfIdle(0L);
    }

    /** Stop only if the caller (e.g. surveillance) owns the recording.
     *  Pass the engine's post-window so the OEM tail matches pano's. */
    public void stopRecordingIfOwned(boolean owned, long postRecordMs) {
        if (owned) stopRecordingAndGetFinalizedClip(postRecordMs);
    }

    /** Back-compat overload — no post-roll. */
    public void stopRecordingIfOwned(boolean owned) {
        stopRecordingIfOwned(owned, 0L);
    }

    /**
     * Surveillance stop variant that returns the finalized OEM mirror for
     * delivery after the parent event passes its Telegram severity gate.
     */
    public FinalizedClip stopRecordingIfOwnedAndGetFinalizedClip(
            boolean owned, long postRecordMs) {
        return owned ? stopRecordingAndGetFinalizedClip(postRecordMs) : null;
    }

    /**
     * Segment rotation phase offset (ms) for continuous mode. Read from UCM
     * at start time and applied by the continuous-segment scheduler so the
     * OEM pipeline's MediaMuxer.stop() bursts (~50–200 ms) don't collide
     * with the pano pipeline's segment-stop bursts. Default 30 s into the
     * pano cycle.
     */
    public int getSegmentRotateOffsetMs() {
        try {
            return UnifiedConfigManager.getOemDashcam()
                .optInt("segmentRotateOffsetMs", 30_000);
        } catch (Throwable t) {
            return 30_000;
        }
    }

    /**
     * Re-arm the parked-idle encode stride after a clip finalizes. Event/
     * continuous starts snap the stride to 1 for a full-rate clip; once the clip
     * ends we must return to the throttled idle stride if we are still on the
     * parked surveillance axis with the throttle on — otherwise the encoder would
     * keep running at full rate (no power saving) until the next config reapply.
     * Never throttles while a clip is in flight. No-op when the throttle is off
     * (resolves to stride 1 = today's behaviour).
     */
    private void reapplyIdleStrideAfterStop() {
        try {
            // Compute the target stride OUTSIDE the lock (AccMonitor + UCM reads can
            // take ms), then re-check recording and write the stride TOGETHER under
            // recordingStateLock. This closes the check-then-act race with
            // startRecordingInternal: a clip that won the CAS (and set stride=1
            // inside the same lock) is observed here as recording==true, so we skip
            // the write and never clobber a freshly-opened full-rate clip's stride.
            boolean surveillanceAxis = !com.overdrive.app.monitor.AccMonitor.isAccOn();
            int target;
            if (surveillanceAxis && UnifiedConfigManager.isOemIdleThrottleWhenParked()) {
                int idleFps = UnifiedConfigManager.getOemIdleFps();
                target = (idleFps > 0) ? Math.max(1, Math.round((float) fps / idleFps)) : 1;
            } else {
                target = 1;
            }
            synchronized (recordingStateLock) {
                if (recording.get()) return;  // a new clip already opened — leave its stride
                recorderDrawStride = target;
            }
        } catch (Throwable t) {
            recorderDrawStride = 1;  // fail safe: full rate
        }
    }

    /**
     * Pre-roll window (seconds) for OEM event clips. Mirrors pano's
     * surveillance.preRecordSeconds so a smart-mode trigger lands the same
     * pre-event coverage on both pipelines. Falls back to {@code 5} (the
     * UCM default) if the surveillance section is unreadable.
     */
    private int resolveOemPreRecordSeconds() {
        try {
            int pano = UnifiedConfigManager.getSurveillance().optInt("preRecordSeconds", 5);
            // OEM also honours per-flow override if the user (or a future
            // tuning path) lands on a different value for OEM specifically.
            int oem = UnifiedConfigManager.getOemDashcam().optInt("preRecordSeconds", pano);
            return Math.max(1, Math.min(30, oem));
        } catch (Throwable t) {
            return 5;
        }
    }

    /**
     * Expose the OEM camera EXTERNAL_OES texture id and SurfaceTexture so
     * pano's {@code GpuStreamScaler} can sample it directly when view 5
     * is selected. Both are valid only on the GL thread that created the
     * shared EGL context (see {@link #shareableEglContext()}).
     */
    public int getCameraTextureId() {
        return cameraTextureId;
    }

    public android.graphics.SurfaceTexture getCameraSurfaceTexture() {
        return cameraSurfaceTexture;
    }

    /**
     * The {@link EGLCore} this pipeline rendered with. Pano's stream
     * scaler creates a shared context off this so OEM's external-OES
     * texture is visible in the scaler's GL state.
     */
    public EGLCore getEglCore() {
        return eglCore;
    }

    // ==================== Telemetry ====================

    /**
     * Inject the shared {@link TelemetryDataCollector}. Caller is responsible
     * for refcounting startPolling()/stopPolling() across pipelines — this
     * class only flips the recorder's overlay-enabled bit. See {@link
     * #setOverlayEnabled}.
     */
    public void setTelemetryCollector(TelemetryDataCollector collector) {
        this.telemetryCollector = collector;
        // Reconcile the polling refcount in case overlay was already set.
        reconcileTelemetryHold();
    }

    /**
     * Tracks whether THIS pipeline currently holds a polling refcount on
     * the shared TelemetryDataCollector. Mirrors GpuSurveillancePipeline's
     * overlayPollingHeld discipline so two consumers (pano + OEM) never
     * underflow each other's start/stop pairs.
     */
    private boolean overlayPollingHeld = false;

    public void setOverlayEnabled(boolean enabled) {
        overlayEnabled = enabled;
        reconcileTelemetryHold();
    }

    /**
     * Disk-writer abort callback. Fires from the encoder's writer thread when
     * consecutive write failures cross the abort threshold (SD unmount, full
     * volume). Snaps {@code recording=false} so callers and HTTP status
     * readers stop seeing a misleading "Recording" badge, releases the
     * telemetry polling refcount, and writes a {@code lastWriteError} into
     * the UCM {@code oemDashcam} section so the surveillance.html OEM card
     * can surface the failure.
     *
     * <p>Doesn't tear the pipeline down — the GL thread + encoder are
     * still alive and could resume on a later trigger if the SD remounts.
     * We just close the in-flight clip's logical-recording state.
     */
    private void handleWriterAbort(String reason) {
        if (recording.compareAndSet(true, false)) {
            logger.warn("OEM writer abort observed (" + reason + ") — recording flag cleared");
            try {
                org.json.JSONObject delta = new org.json.JSONObject();
                delta.put("lastWriteError", reason == null ? "writer_aborted" : reason);
                delta.put("lastWriteErrorAt", java.lang.System.currentTimeMillis());
                UnifiedConfigManager.setOemDashcam(delta);
            } catch (Throwable t) {
                logger.warn("setOemDashcam(lastWriteError) failed: " + t.getMessage());
            }
            // Quarantine the in-flight segment immediately. Without this
            // call, the encoder's MediaMuxer native handle and the
            // {@code .mp4.tmp} stay around until the next
            // {@link #startRecording} reuses the muxer field, or until
            // {@link com.overdrive.app.storage.StorageManager#sweepOrphanTempFiles}
            // reaps the temp file 10 minutes later. stopEventRecording
            // closes the muxer, sees {@code writerAbortedCorrupt=true},
            // and renames the tempFile to {@code .broken} so the user
            // never sees a half-written file with a final {@code .mp4}
            // extension.
            HardwareEventRecorderGpu enc = encoder;
            if (enc != null) {
                try {
                    enc.stopEventRecording(true, 0);
                } catch (Throwable t) {
                    logger.warn("OEM stopEventRecording during abort failed: " + t.getMessage());
                }
            }
            // Drop telemetry polling now that recording is logically over;
            // the encoder GL thread will continue to push frames into the
            // dead surface but no overlay refresh / TelemetryDataCollector
            // hold is needed.
            reconcileTelemetryHold();
        }
    }

    private void reconcileTelemetryHold() {
        // Stop the overlay raster worker whenever the collector is gone (it has
        // nothing to read) — do this BEFORE the early return so a cleared
        // collector reliably tears the worker down.
        OverlayBitmapRenderer r = overlayRenderer;
        // Keep the renderer's field selection current whenever it exists.
        if (r != null) r.setActiveFields(overlayFields);
        if (telemetryCollector == null) {
            if (r != null) r.stopWorker();
            // Drop any beam demand this flow published — the overlay can't draw
            // without a collector, and a stale entry would keep the light poll
            // alive for a flow that has stopped.
            com.overdrive.app.byd.BydDataCollector.setOverlayBeamDemand("oem", false);
            return;
        }
        // Hold polling only when overlay is enabled AND we're actively
        // recording (no need to poll CAN/GPS just because the user opted
        // in — the overlay only paints into clips that are actually being
        // written to disk).
        boolean shouldHold = overlayEnabled && running.get() && recording.get();
        // Report which overlay-only signals this flow draws so the collector can
        // skip the reflective turn-signal / seatbelt reads when unused. Issued on
        // EVERY reconcile while holding (not just the start edge) so a LIVE field
        // change mid-recording — setOverlayFields() -> reconcileTelemetryHold()
        // with shouldHold already true — re-reports demand. Without this, newly
        // enabling turn/seatbelt mid-clip would draw a field whose HAL read is
        // still skipped (stale/dead). setOverlayFieldDemand is idempotent.
        if (shouldHold) {
            telemetryCollector.setOverlayFieldDemand(
                "oem",
                overlayFields.has(com.overdrive.app.telemetry.TelemetryFields.Field.TURN_SIGNALS),
                overlayFields.hasAny(
                    com.overdrive.app.telemetry.TelemetryFields.Field.SEATBELT_DRIVER,
                    com.overdrive.app.telemetry.TelemetryFields.Field.SEATBELT_PASSENGER));
        }
        // Beams live on BydDataCollector's 5 s poll (not the telemetry collector)
        // and that poll is otherwise automation-gated, so declare demand here or
        // the beam glyphs stay frozen at their boot state. Outside the shouldHold
        // branch so the demand is also CLEARED when this flow stops drawing.
        com.overdrive.app.byd.BydDataCollector.setOverlayBeamDemand(
            "oem",
            shouldHold && overlayFields.hasAny(
                com.overdrive.app.telemetry.TelemetryFields.Field.LOW_BEAM,
                com.overdrive.app.telemetry.TelemetryFields.Field.HIGH_BEAM));
        if (shouldHold && !overlayPollingHeld) {
            telemetryCollector.setOverlayRecordingActive(true);
            telemetryCollector.startPolling();
            overlayPollingHeld = true;
        } else if (!shouldHold && overlayPollingHeld) {
            telemetryCollector.clearOverlayFieldDemand("oem");
            telemetryCollector.setOverlayRecordingActive(false);
            telemetryCollector.stopPolling();
            overlayPollingHeld = false;
        }
        // Run the off-GL-thread overlay raster worker on the same condition the
        // GL composite uses (overlayEnabled && recording). startWorker/stopWorker
        // are idempotent. overlayRenderer is created lazily on the GL thread
        // (line ~1175); if not up yet, the next reconcile (or the GL-init path)
        // starts it.
        if (r != null) {
            if (shouldHold) r.startWorker(telemetryCollector);
            else r.stopWorker();
        }
    }

    /**
     * Set the OEM dashcam overlay's field selection (its own independent list).
     * {@code null} resets to the legacy default. Applied to the renderer + re-
     * reported as demand on the next reconcile.
     */
    public void setOverlayFields(com.overdrive.app.telemetry.TelemetryFields fields) {
        this.overlayFields = (fields != null)
            ? fields
            : com.overdrive.app.telemetry.TelemetryFields.legacyDefault();
        reconcileTelemetryHold();
    }

    public boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    // ==================== Internals ====================

    private boolean isHevc() {
        return MediaFormat.MIMETYPE_VIDEO_HEVC.equals(codecMimeType);
    }

    /**
     * Resolve OEM's own quality / codec / fps from the dedicated
     * {@code oemDashcam} UCM section. We deliberately do NOT read pano's
     * {@code recording.*} keys: that conflated the two pipelines, and the
     * bitrate-budget cap subtracted pano's tier from itself with a 2 Mbps
     * floor, regressing MAX picks to ECONOMY-equivalent.
     *
     * <p>Falls back to {@code recording.*} only as a one-shot migration for
     * pre-split installs where the user had configured the legacy single
     * key. Subsequent writes go to the OEM-specific slot.
     */
    private void applyRecordingConfigFromUcm() {
        try {
            // R8-A #15: read UCM once into locals, pass into helpers.
            // Pre-fix did 5 disk reads per start (oem + rec twice each +
            // a third loadConfig in the cap path). UnifiedConfigManager
            // mtime-caches but the cache is invalidated on writes, and
            // start() often follows a UCM write. Single read avoids the
            // 25-50ms latency spike on cold disk.
            JSONObject root = UnifiedConfigManager.loadConfig();
            JSONObject oem = root.optJSONObject("oemDashcam");
            JSONObject rec = root.optJSONObject("recording");
            JSONObject cam = root.optJSONObject("camera");
            if (oem == null) oem = new JSONObject();
            if (rec == null) rec = new JSONObject();

            // Axis selection — mirrors OemDashcamApiHandler's resolver: ACC OFF
            // means we're serving the PARKED SURVEILLANCE axis, ACC ON the
            // DRIVE-time recording axis. The surveillance axis honors the
            // surveillance page's independent quality/fps knobs
            // (recording.surveillanceQuality / camera.surveillanceTargetFps),
            // falling back to the recording-axis chain when those are unset so
            // a config predating the split is byte-identical. The recording
            // axis is UNCHANGED. Codec is SHARED across both axes (device-compat
            // choice, not a per-mode quality knob) so it does not branch here.
            boolean surveillanceAxis = !com.overdrive.app.monitor.AccMonitor.isAccOn();

            // Codec — OEM-specific, fallback to legacy recording.codec.
            String codec = oem.has("codec")
                ? oem.optString("codec", "H264")
                : rec.optString("codec", "H264");
            codecMimeType = "H265".equalsIgnoreCase(codec)
                ? MediaFormat.MIMETYPE_VIDEO_HEVC
                : MediaFormat.MIMETYPE_VIDEO_AVC;

            // Recording-axis fps chain (also the surveillance-axis fallback).
            int recAxisFps = oem.has("fps")
                ? oem.optInt("fps", DEFAULT_FPS)
                : rec.optInt("fps", DEFAULT_FPS);
            int requestedFps;
            if (surveillanceAxis) {
                requestedFps = (cam != null)
                    ? cam.optInt("surveillanceTargetFps", recAxisFps)
                    : recAxisFps;
            } else {
                requestedFps = recAxisFps;
            }
            fps = Math.max(15, Math.min(60, requestedFps));

            // Parked-idle encode throttle: on the SURVEILLANCE (parked) axis, when
            // the OEM throttle is enabled and we are NOT recording, sub-sample the
            // encoder draw to ~idleFps via the draw-stride. The camera HAL rate
            // (fps, >=15) is untouched — only the encode rate drops — so an event
            // can snap to a clean full-rate clip (startRecordingInternal resets
            // stride to 1). On the drive axis, or with the throttle off, force
            // stride 1 (today's behaviour). Never override an in-flight clip.
            if (surveillanceAxis
                    && UnifiedConfigManager.isOemIdleThrottleWhenParked()
                    && !recording.get()) {
                int idleFps = UnifiedConfigManager.getOemIdleFps();
                recorderDrawStride = (idleFps > 0) ? Math.max(1, Math.round((float) fps / idleFps)) : 1;
            } else if (!recording.get()) {
                recorderDrawStride = 1;
            }

            // Recording-axis tier chain (also the surveillance-axis fallback).
            String recAxisQuality = oem.has("recordingQuality")
                ? oem.optString("recordingQuality", "STANDARD")
                : rec.optString("recordingQuality", "STANDARD");
            String quality = (surveillanceAxis
                    ? rec.optString("surveillanceQuality", recAxisQuality)
                    : recAxisQuality)
                .toUpperCase(Locale.US);
            bitrate = bitrateForQuality(quality);

            bitrate = applyBitrateBudgetCap(bitrate, oem, rec, cam, surveillanceAxis);
        } catch (Throwable t) {
            logger.warn("applyRecordingConfigFromUcm failed: " + t.getMessage());
        }
    }

    /**
     * Live-re-apply the fps + bitrate for the CURRENT axis (ACC on↔off) to an
     * already-running pipeline WITHOUT a reinit — used at the ACC transition
     * when the pipeline is kept warm across the boundary (e.g. the user has
     * BOTH oemDashcam.recordingMode=continuous AND surveillanceMode=continuous,
     * so recordingDesired never drops and {@code start()} is not re-called).
     *
     * <p>Re-resolves via {@link #applyRecordingConfigFromUcm()} (which reads the
     * live ACC state) and pushes only the deltas: bitrate to the encoder
     * ({@code setBitrate}, a MediaCodec setParameters — no gap) and fps to the
     * camera HAL ({@code AvmCameraHelper.setCameraFps} — no reopen). Codec is
     * axis-independent and never changes across an ACC edge, so no encoder
     * reinit is ever needed here; a genuine codec change is a separate event
     * that goes through the quality-mirror restart path.
     *
     * <p>No-op if the pipeline isn't running.
     */
    public void reapplyAxisProfileFromUcm() {
        if (!running.get()) return;
        int oldFps = fps;
        int oldBitrate = bitrate;
        applyRecordingConfigFromUcm();  // re-resolves fps/bitrate for the live axis
        try {
            if (bitrate != oldBitrate) {
                HardwareEventRecorderGpu enc = encoder;
                if (enc != null) {
                    enc.setBitrate(bitrate);
                }
            }
            if (fps != oldFps) {
                Object cam = cameraObj;
                if (cam != null) {
                    AvmCameraHelper.setCameraFps(cam, fps);
                }
                // Also update the encoder's cached fps so its PTS re-anchor
                // fallback interval and duration-fallback math track the new
                // rate (MediaCodec KEY_FRAME_RATE can't change live, so this is
                // just the bookkeeping int — mirrors the pano pipeline pairing
                // camera.setTargetFps with encoder.setTargetFps).
                HardwareEventRecorderGpu enc = encoder;
                if (enc != null) {
                    enc.setTargetFps(fps);
                }
            }
            if (fps != oldFps || bitrate != oldBitrate) {
                logger.info("OEM axis profile re-applied live: fps " + oldFps + "→" + fps
                    + ", bitrate " + (oldBitrate / 1_000_000) + "→" + (bitrate / 1_000_000)
                    + " Mbps (accOn=" + com.overdrive.app.monitor.AccMonitor.isAccOn() + ")");
            }
        } catch (Throwable t) {
            logger.warn("reapplyAxisProfileFromUcm live-apply failed: " + t.getMessage());
        }
    }

    private static int bitrateForQuality(String q) {
        switch (q) {
            case "ECONOMY":  return 2_000_000;
            case "HIGH":     return 6_000_000;
            case "PREMIUM":  return 8_000_000;
            case "MAX":      return 10_000_000;
            case "STANDARD":
            default:         return 4_000_000;
        }
    }

    /**
     * Apply the combined-budget cap, but ONLY when we'd actually contend with
     * a live pano encoder. Pre-fix this method unconditionally subtracted
     * pano's tier from the budget — with both pipelines reading the same
     * {@code recording.recordingQuality} key (no per-pipeline slot today),
     * a user picking MAX clamped OEM to the 2 Mbps floor regardless of
     * whether pano was running.
     *
     * <p>Three conditions for the cap to bite:
     * <ol>
     *   <li>{@code camera.concurrentAvmSupported == 1} — HAL actually allows
     *       both pipelines to run at once. On single-client HALs only one
     *       pipeline runs at a time, so OEM gets the full budget.</li>
     *   <li>{@code gpuPipeline != null && gpuPipeline.isRunning()} — pano
     *       is currently active. Otherwise it consumes 0 Mbps.</li>
     *   <li>The pano tier is read from {@code GpuPipelineConfig.RecordingQuality}
     *       resolved against pano's actual codec (H.264 vs H.265 use different
     *       tables) — not a codec-agnostic estimate.</li>
     * </ol>
     */
    private int applyBitrateBudgetCap(int requested, JSONObject oem,
                                      JSONObject rec, JSONObject cam,
                                      boolean surveillanceAxis) {
        try {
            int budget = oem == null ? 10_000_000
                : oem.optInt("bitrateBudget", 10_000_000);
            int concurrent = cam == null ? -1 : cam.optInt("concurrentAvmSupported", -1);
            boolean panoActive;
            try {
                com.overdrive.app.surveillance.GpuSurveillancePipeline pano =
                    com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
                panoActive = pano != null && pano.isRunning();
            } catch (Throwable t) { panoActive = false; }

            if (concurrent != 1 || !panoActive) {
                // Either HAL is single-client (yield protocol → only one
                // pipeline at a time) or pano isn't currently encoding.
                // OEM is the sole consumer of the encoder bus; full budget.
                // R8-A #25: apply same 2 Mbps floor as the concurrent
                // branch so a pathologically-low UCM bitrateBudget can't
                // produce an unwatchable H.265 1080p30 stream.
                int allowedSole = Math.max(2_000_000, budget);
                if (requested > allowedSole) {
                    logger.info("OEM dashcam bitrate clamped from " + requested
                        + " to " + allowedSole + " (sole encoder, budget=" + budget + ")");
                    return allowedSole;
                }
                return requested;
            }

            // Concurrent operation: read pano's actual tier+codec from
            // the already-loaded recording section (passed in to avoid
            // a redundant disk read). Use the canonical codec-aware
            // table so OEM agrees with what pano actually uses.
            //
            // Axis-match the pano tier: when THIS pipeline serves the parked
            // surveillance axis, the pano pipeline is likewise in SENTRY at its
            // surveillanceQuality tier, so subtract THAT from the shared budget
            // (not the drive-time recordingQuality). Falls back to the
            // recording tier when the surveillance key is unset — byte-identical
            // to the pre-split cap math. Codec is shared across axes.
            JSONObject panoRec = rec != null ? rec : new JSONObject();
            String panoRecQuality = panoRec.optString("recordingQuality", "STANDARD");
            String panoQuality = (surveillanceAxis
                    ? panoRec.optString("surveillanceQuality", panoRecQuality)
                    : panoRecQuality)
                .toUpperCase(Locale.US);
            String panoCodec = panoRec.optString("codec", "H264").toUpperCase(Locale.US);
            int panoBps;
            try {
                com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality q =
                    com.overdrive.app.surveillance.GpuPipelineConfig.RecordingQuality
                        .fromString(panoQuality);
                com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec c =
                    "H265".equals(panoCodec)
                        ? com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H265
                        : com.overdrive.app.surveillance.GpuPipelineConfig.VideoCodec.H264;
                panoBps = q.getBitrateForCodec(c);
            } catch (Throwable t) {
                // Defensive: fall back to the local table if the canonical
                // enum lookup fails for any reason.
                panoBps = bitrateForQuality(panoQuality);
            }
            int allowed = Math.max(2_000_000, budget - panoBps);
            if (requested > allowed) {
                logger.info("OEM dashcam bitrate clamped from " + requested + " to " + allowed
                    + " (budget=" + budget + ", panoBps=" + panoBps
                    + ", panoQuality=" + panoQuality + "/" + panoCodec + ")");
                return allowed;
            }
        } catch (Throwable t) {
            logger.warn("applyBitrateBudgetCap failed: " + t.getMessage());
        }
        return requested;
    }

    private boolean validateHalDimsOrReject() {
        try {
            Class<?> bmm = Class.forName("android.hardware.BmmCameraInfo");
            Method gw = bmm.getDeclaredMethod("getDefaultPreviewWidth", int.class);
            Method gh = bmm.getDeclaredMethod("getDefaultPreviewHeight", int.class);
            gw.setAccessible(true);
            gh.setAccessible(true);
            Object w = gw.invoke(null, oemDashcamCameraId);
            Object h = gh.invoke(null, oemDashcamCameraId);
            int wInt = (w instanceof Integer) ? (Integer) w : 0;
            int hInt = (h instanceof Integer) ? (Integer) h : 0;
            if (wInt <= 0 || hInt <= 0) {
                // BmmCameraInfo empty (vehicle.config.cam_sort unset). We can't
                // validate; trust the configured id and proceed with defaults.
                logger.info("BmmCameraInfo empty for id=" + oemDashcamCameraId
                    + "; proceeding with default " + width + "x" + height);
                return true;
            }
            int aspect100 = (wInt * 100) / Math.max(1, hInt);
            if (aspect100 < MIN_ASPECT_RATIO_TIMES_100 || aspect100 > MAX_ASPECT_RATIO_TIMES_100) {
                logger.error("HAL declared dims " + wInt + "x" + hInt + " for id="
                    + oemDashcamCameraId + " (aspect " + (aspect100 / 100.0)
                    + ") look panoramic; refusing");
                return false;
            }
            if (wInt < MIN_WIDTH || wInt > MAX_WIDTH) {
                logger.error("HAL declared width " + wInt + " out of range; refusing");
                return false;
            }
            width = wInt;
            height = hInt;
            logger.info("Adopted HAL declared dims: " + width + "x" + height);
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // No BmmCameraInfo on this firmware; trust the id and proceed.
            return true;
        } catch (Throwable t) {
            logger.warn("HAL dim probe failed: " + t.getMessage() + "; proceeding with defaults");
            return true;
        }
    }

    private void startThreads() {
        // Default priority (was FOREGROUND): this is a compute-bound GL render
        // loop and, like the pano GL-RenderLoop, should not preempt the native
        // head-unit UI on the shared SDM665. The wait-bound encoder drainer /
        // disk writer keep FOREGROUND (they burn no CPU; priority only trims
        // their wakeup latency).
        glThread = new HandlerThread("OemDvr-GL", Process.THREAD_PRIORITY_DEFAULT);
        glThread.start();
        glHandler = new Handler(glThread.getLooper());
        // Dedicated thread for SurfaceTexture frame-available callbacks.
        // MUST be separate from glThread — see field comment above.
        irThread = new HandlerThread("OemDvr-IR", Process.THREAD_PRIORITY_DEFAULT);
        irThread.start();
        irHandler = new Handler(irThread.getLooper());
    }

    private void startWatchdog() {
        lastRenderHeartbeat = System.currentTimeMillis();
        watchdogThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(WATCHDOG_POLL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running.get()) return;
                long age = System.currentTimeMillis() - lastRenderHeartbeat;
                if (age > WATCHDOG_TIMEOUT_MS) {
                    logger.error("OEM render-loop watchdog: no heartbeat for "
                        + age + " ms — forcing stop()");
                    try { stop(); } catch (Throwable t) {
                        logger.warn("watchdog stop() threw: " + t.getMessage());
                    }
                    return;
                }
            }
        }, "OemDvr-Watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    private void initEglAndEncoder() throws Exception {
        // Bring up encoder on the GL thread so the input Surface and the
        // makeCurrent target end up in the same EGL context.
        runOnGlThreadAndWait(() -> {
            try {
                encoder = new HardwareEventRecorderGpu(width, height, fps, bitrate, codecMimeType);
                // Skip KEY_OPERATING_RATE so we don't over-subscribe the
                // single Venus H.264 block when pano is also encoding.
                // Pano keeps its pin (it's the primary recorder); OEM
                // accepts whatever frequency the firmware allocates.
                encoder.setPinOperatingRate(false);
                // Per-instance pre-record arena. The shared static ring is
                // single-producer by contract (H264ByteRingBuffer class doc);
                // pano writes to it from its own encoder drainer, so OEM
                // can't share. Instead OEM owns a private ring sized off its
                // own bitrate × pre-roll product. Cost is one direct
                // allocation (8–64 MB) per OEM start, freed on stop. Pano
                // is unaffected — it stays on the shared static path.
                encoder.setUseInstancePreRecordBuffer(true);
                // Mirror pano's pre-record window onto OEM at init time so
                // event clips opened via tryStartIfIdle have the configured
                // 5-second pre-roll available. Continuous-mode clips ignore
                // this (the ring still fills, but the trigger is "now" with
                // postRecord=segmentMs handling rotation).
                int oemPreRecord = resolveOemPreRecordSeconds();
                encoder.setPreRecordDuration(oemPreRecord);
                // Catch disk-writer aborts (SD unmount, full volume) so we
                // can flip recording=false and write UCM lastWriteError
                // immediately. Without this, the pipeline kept reporting
                // "Recording" indefinitely while the muxer was already
                // dead and the .tmp was unrecoverable.
                encoder.setWriterAbortListener(this::handleWriterAbort);
                // Seed the clip segment length from the shared recording config
                // so the OEM / ACC-off surveillance axis rotates at the same
                // user-chosen interval (2/5/10 min) as the dashcam axis — one
                // control, both axes (mirrors rectifyStrength sharing).
                try {
                    int segMin = UnifiedConfigManager.getSegmentDurationMinutes();
                    encoder.setSegmentDurationMs(segMin * 60_000L);
                } catch (Throwable t) {
                    logger.warn("Failed to apply segment duration: " + t.getMessage());
                }
                encoder.init();
                encoderSurface = encoder.getInputSurface();
                if (encoderSurface == null) {
                    throw new IllegalStateException("encoder.getInputSurface() returned null");
                }

                EGLCore parent = parentEglCore;
                if (parent != null) {
                    try {
                        // recordable=true so the EGLConfig the child picks
                        // is compatible with the MediaCodec encoder Surface
                        // we attach below. Without RECORDABLE, eglCreateWindowSurface
                        // fails on Adreno with EGL_BAD_MATCH on encoder
                        // input surfaces.
                        eglCore = EGLCore.createShared(parent, true);
                        // Only NOW is cross-context sampling (view-6 streaming) valid.
                        eglSharedWithPano = true;
                        logger.info("OEM EGL context created in shared group with pano (recordable)");
                    } catch (Throwable t) {
                        logger.warn("EGLCore.createShared failed (" + t.getMessage()
                            + "); falling back to independent context — RECORDING is fine, "
                            + "but view-6 streaming needs a restart once pano is up");
                        eglCore = new EGLCore();
                        eglSharedWithPano = false;
                    }
                } else {
                    // No parent (pano wasn't up yet, or recording-only bring-up):
                    // independent context. Recording works; view-6 streaming does not
                    // until this pipeline is restarted with pano's context as parent.
                    eglCore = new EGLCore();
                    eglSharedWithPano = false;
                }
                encoderEglSurface = eglCore.createWindowSurface(encoderSurface);
                dummySurface = eglCore.createPbufferSurface(1, 1);
                eglCore.makeCurrent(encoderEglSurface);

                // Camera consumer texture (EXTERNAL_OES sampled in passthrough shader)
                int[] tex = new int[1];
                GLES20.glGenTextures(1, tex, 0);
                cameraTextureId = tex[0];
                GLES20.glBindTexture(GLES11_OES_TEXTURE_EXTERNAL, cameraTextureId);
                GLES20.glTexParameteri(GLES11_OES_TEXTURE_EXTERNAL,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11_OES_TEXTURE_EXTERNAL,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11_OES_TEXTURE_EXTERNAL,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11_OES_TEXTURE_EXTERNAL,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

                cameraSurfaceTexture = new SurfaceTexture(cameraTextureId);
                cameraSurfaceTexture.setDefaultBufferSize(width, height);
                cameraSurface = new Surface(cameraSurfaceTexture);

                // Compile passthrough shader once per pipeline. The shader
                // samples the camera EXTERNAL_OES texture and blits it to
                // the encoder Surface; tex matrix preserves any HAL rotation
                // / flip baked into the SurfaceTexture transform.
                passthroughProgram = com.overdrive.app.camera.GlUtil
                    .createProgram(VS_PASSTHROUGH, FS_PASSTHROUGH);
                aPositionLoc = GLES20.glGetAttribLocation(passthroughProgram, "aPosition");
                aTexCoordLoc = GLES20.glGetAttribLocation(passthroughProgram, "aTexCoord");
                uTexMatrixLoc = GLES20.glGetUniformLocation(passthroughProgram, "uTexMatrix");
                uTextureLoc = GLES20.glGetUniformLocation(passthroughProgram, "uTexture");
                vertexBuffer = java.nio.ByteBuffer
                    .allocateDirect(FULL_SCREEN_VERTS.length * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer();
                vertexBuffer.put(FULL_SCREEN_VERTS).position(0);
                texCoordBuffer = java.nio.ByteBuffer
                    .allocateDirect(FULL_SCREEN_TEX.length * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asFloatBuffer();
                texCoordBuffer.put(FULL_SCREEN_TEX).position(0);

                // Telemetry overlay program + texture. Mirrors the pano
                // GpuMosaicRecorder overlay pass: a 2D sampler quad pinned
                // to the top 1/12 of the frame (matches pano's 160/1920
                // ratio so the overlay reads at the same relative size on
                // both pipelines). Allocate unconditionally — the runtime
                // gate is the (overlayEnabled && recording) check inside
                // drawPassthrough(), which avoids a recompile if the user
                // toggles the burn-in mid-clip.
                overlayProgramId = com.overdrive.app.camera.GlUtil
                    .createProgram(VS_OVERLAY, FS_OVERLAY);
                if (overlayProgramId == 0) {
                    logger.warn("Failed to create overlay shader; OEM telemetry burn-in disabled");
                } else {
                    overlayAPositionLoc = GLES20.glGetAttribLocation(overlayProgramId, "aPosition");
                    overlayATexCoordLoc = GLES20.glGetAttribLocation(overlayProgramId, "aTexCoord");
                    overlayUTextureLoc = GLES20.glGetUniformLocation(overlayProgramId, "uTexture");

                    int[] otex = new int[1];
                    GLES20.glGenTextures(1, otex, 0);
                    overlayTextureId = otex[0];

                    // Pin the overlay to the top 1/12 of the frame in NDC.
                    // Y=+1 is the top edge; bottom of overlay is +1 - 2*(1/12)
                    // = 0.8333. Same Y range as pano's overlay quad.
                    final float bottomY = 1.0f - 2.0f * (1.0f / 12.0f);
                    float[] overlayVerts = {
                        -1.0f, bottomY,
                         1.0f, bottomY,
                        -1.0f,  1.0f,
                         1.0f,  1.0f
                    };
                    // Tex coords are flipped on Y so the bitmap's row 0
                    // (top of the overlay PNG composite) ends up at the
                    // top of the on-screen quad. Same convention pano uses.
                    float[] overlayUv = {
                        0.0f, 1.0f,
                        1.0f, 1.0f,
                        0.0f, 0.0f,
                        1.0f, 0.0f
                    };
                    overlayVertexBuffer = com.overdrive.app.camera.GlUtil.createFloatBuffer(overlayVerts);
                    overlayTexCoordBuffer = com.overdrive.app.camera.GlUtil.createFloatBuffer(overlayUv);
                    overlayTextureInitialized = false;
                    overlayTextureReady = false;
                    overlayFrameCounter = 0;

                    try {
                        overlayRenderer = new OverlayBitmapRenderer();
                        // Renderer is created lazily here on the GL thread, possibly
                        // AFTER reconcileTelemetryHold already wanted the worker up —
                        // reconcile now that the renderer exists so the off-thread
                        // raster starts if overlay is active.
                        reconcileTelemetryHold();
                    } catch (Throwable t) {
                        logger.warn("OverlayBitmapRenderer init failed: " + t.getMessage());
                        overlayRenderer = null;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** GLES external-OES texture target. We don't link android-opengl-extensions. */
    private static final int GLES11_OES_TEXTURE_EXTERNAL = 0x8D65;

    private void openCameraAndAttach() throws Exception {
        Class<?> avmClass = Class.forName("android.hardware.AVMCamera");

        try {
            Constructor<?> c = avmClass.getDeclaredConstructor(int.class);
            c.setAccessible(true);
            cameraObj = c.newInstance(oemDashcamCameraId);
            Method open = avmClass.getDeclaredMethod("open");
            open.setAccessible(true);
            Object opened = open.invoke(cameraObj);
            if (opened instanceof Boolean && !((Boolean) opened)) {
                throw new RuntimeException("AVMCamera.open() returned false (id="
                    + oemDashcamCameraId + ")");
            }
        } catch (NoSuchMethodException e) {
            Method openStatic = avmClass.getDeclaredMethod("open", int.class);
            openStatic.setAccessible(true);
            cameraObj = openStatic.invoke(null, oemDashcamCameraId);
            if (cameraObj == null) {
                throw new RuntimeException("AVMCamera.open(id) returned null");
            }
        }

        AvmCameraHelper.setCameraFps(cameraObj, fps);

        // Attach surface. We try the legacy addPreviewSurface(Surface, mode)
        // first because OEM dashcam ids on Seal/Han respond to mode=0
        // (single-channel passthrough). DiLink 4 (USE_ESCO_SURFACE_TEXTURE_PATH)
        // pano uses addTexture(SurfaceTexture, idx) with idx=0 mosaic; for OEM
        // dashcam idx=0 is also "the only channel", so the same call works.
        boolean attached = false;
        try {
            Method addSurf = avmClass.getDeclaredMethod("addPreviewSurface", Surface.class, int.class);
            addSurf.setAccessible(true);
            addSurf.invoke(cameraObj, cameraSurface, 0);
            attached = true;
            logger.info("OEM dashcam attached via addPreviewSurface(mode=0)");
        } catch (NoSuchMethodException nsme) {
            // Try addTexture path
            Method addTex = avmClass.getDeclaredMethod("addTexture", SurfaceTexture.class, int.class);
            addTex.setAccessible(true);
            addTex.invoke(cameraObj, cameraSurfaceTexture, 0);
            try {
                Method setTex = avmClass.getDeclaredMethod("setTexture", SurfaceTexture.class, int.class);
                setTex.setAccessible(true);
                setTex.invoke(cameraObj, cameraSurfaceTexture, 0);
            } catch (NoSuchMethodException ignored) {
                // Older HAL: addTexture alone is sufficient.
            }
            attached = true;
            logger.info("OEM dashcam attached via addTexture(idx=0)");
        }

        if (!attached) {
            throw new RuntimeException("OEM dashcam attach failed: no compatible AVMCamera surface API");
        }

        Method start = avmClass.getDeclaredMethod("startPreview");
        start.setAccessible(true);
        start.invoke(cameraObj);
    }

    private void installFrameCallback() {
        // Dispatch on irHandler (NOT glHandler). The renderLoop on glThread
        // parks in `frameSync.wait()`; if the listener ran on the same
        // thread, the HAL's frame-available notification could never
        // dispatch and imagePending would stay false forever. Pano uses
        // the same separate-thread pattern (PanoramicCameraGpu's
        // imageReaderThread). Body must be cheap — the ImageReaderHandler
        // dispatch is the only reason this thread exists.
        cameraSurfaceTexture.setOnFrameAvailableListener(st -> {
            synchronized (frameSync) {
                imagePending = true;
                frameSync.notify();
            }
        }, irHandler);
    }

    private final AtomicInteger drawSequence = new AtomicInteger(0);

    // Parked-idle throttle: encode draw-stride. MediaCodec encodes exactly the
    // frames swapped into its input surface, so drawing every Nth iteration
    // yields ~halFps/N encode fps WITHOUT touching the (HAL-rejectable) camera
    // setCameraFps or the (immutable) KEY_FRAME_RATE. Default 1 = every frame
    // (byte-identical to today). Set >1 only while parked-idle keep-warm; snapped
    // back to 1 on an event so the clip body records full-rate. Written from the
    // control thread, read on the GL thread — volatile is sufficient.
    private volatile int recorderDrawStride = 1;
    // GL-thread-confined stride counter (never touched off the render loop).
    private int renderStridePhase = 0;
    // GL-thread-confined: last time we forced an IDR while parked-idle. At the
    // strided-down encode rate the frame-count GOP stretches past the pre-record
    // window, so we pulse a sync-frame here (inline in the render loop — glHandler
    // is occupied by the busy render while-loop, so a posted timer would never
    // run). Only adds IDRs; never runs unless idle-throttle is active.
    private long oemLastIdleKeyframeMs = 0;

    private void startRenderLoop() {
        startWatchdog();
        glHandler.post(this::renderLoop);
    }

    private void renderLoop() {
        int consecutiveErrors = 0;
        while (running.get()) {
            try {
                lastRenderHeartbeat = System.currentTimeMillis();
                synchronized (frameSync) {
                    long start = System.currentTimeMillis();
                    while (!imagePending && running.get()) {
                        try {
                            // FIX H4: 250 ms timeout (was 100 ms). The watchdog's
                            // 30 s ceiling is what actually decides "stuck" vs
                            // "live"; the wait timeout only controls how often
                            // we re-stamp the render heartbeat and re-check
                            // running.get(). 100 ms produced ~10 wakeups/s of
                            // pure overhead during pre-capture / warmup, and
                            // 250 ms still re-checks running well within any
                            // reasonable shutdown latency budget.
                            frameSync.wait(250);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        // Skip the GL work when no frame arrived in 5s. The
                        // outer while will re-stamp the heartbeat; the
                        // watchdog uses 30s so this doesn't false-fire on a
                        // legitimately-slow HAL warmup. Continuing into
                        // updateTexImage with imagePending=false would do
                        // wasted GL work and encode a duplicate frame.
                        if (System.currentTimeMillis() - start > 5_000) break;
                    }
                    if (!imagePending) {
                        // Wait timed out without a frame; iterate to refresh
                        // heartbeat and recheck running.
                        continue;
                    }
                    imagePending = false;
                }
                if (!running.get()) return;

                eglCore.makeCurrent(encoderEglSurface);
                // Capture cameraSurfaceTexture into a local so a concurrent
                // stop() that nulls the field between updateTexImage and
                // getTransformMatrix doesn't NPE here. R8-A #22 — the field
                // is now volatile so this snapshot is consistent within
                // this iteration; null-check guards against stop arriving
                // BEFORE the snapshot read.
                final SurfaceTexture stLocal = cameraSurfaceTexture;
                if (stLocal == null) return;
                stLocal.updateTexImage();
                long halTs = stLocal.getTimestamp();

                // Read the SurfaceTexture transform ONCE per frame into the
                // pipeline's `texMatrix` field. drawPassthrough samples this
                // for its uTexMatrix upload, and the scaler-publish branch
                // arraycopies it into a fresh per-publish snapshot. Pre-fix
                // had two getTransformMatrix calls per frame (one here for
                // publish, one inside drawPassthrough) — same JNI cost,
                // duplicated.
                stLocal.getTransformMatrix(texMatrix);

                // If pano's stream scaler is sampling our texture for view 6,
                // publish a fresh snapshot of the matrix. Allocating per
                // publish (vs ping-pong) eliminates the torn-read race:
                // the consumer's arraycopy operates on an immutable buffer
                // the producer never touches again, so a 16-float matrix
                // straddling two cache lines can't be read half-old /
                // half-new. R8-A #5: re-read the volatile reference into a
                // local IMMEDIATELY before the publish so a concurrent
                // disable that just cleared the publish ref isn't paid 64B
                // of throwaway allocation.
                com.overdrive.app.streaming.GpuStreamScaler scaler =
                    streamScalerForOemPublish;
                if (scaler != null) {
                    float[] snapshot = new float[16];
                    System.arraycopy(texMatrix, 0, snapshot, 0, 16);
                    // Second read pin: if the publish ref was nulled
                    // between the first read and now, skip the publish.
                    if (streamScalerForOemPublish != null) {
                        scaler.publishOemTexMatrix(snapshot);
                    }
                }

                // Resolve a strictly-monotonic PTS. If we already detected
                // the HAL is stuck, use System.nanoTime() unconditionally;
                // otherwise watch for advance < 1ms over 10 frames and
                // latch on detection. Final clamp guarantees > lastPtsNs
                // even if both sources misbehave (clock skew, sleep, etc.).
                // PTS resolution. Mirrors PanoramicCameraGpu.nextFrameTimestampNs
                // (PanoramicCameraGpu:1722). Pre-fix: the stuck-detection loop
                // kept feeding `halTs` to swapBuffersWithTimestamp for the
                // first 9 stuck frames then suddenly latched to System.nanoTime,
                // which produced a giant PTS jump across the clock-domain
                // boundary. The encoder muxer recorded that jump as real
                // playback time → 2-min clips reported as 30-40 min in
                // players. Two corrections:
                //   1. Substitute System.nanoTime IMMEDIATELY on every
                //      stuck frame (not at the latch boundary), so we
                //      never queue a sub-ms-spaced HAL value.
                //   2. Track the active clock domain and re-anchor cleanly
                //      on transition — the +1ns monotonic clamp would
                //      otherwise pin PTS at the old-epoch value forever
                //      because System.nanoTime values are larger than HAL
                //      μs values by ~1000×.
                long ptsNs;
                int candidateDomain;
                if (useSystemNanoForPts) {
                    ptsNs = System.nanoTime();
                    candidateDomain = PTS_DOMAIN_NANO;
                } else {
                    long advance = halTs - firstHalPtsNs;
                    boolean firstSeed = (firstHalPtsNs == 0L && halTs > 0L);
                    boolean realAdvance = advance >= HAL_PTS_MIN_ADVANCE_NS;
                    if (firstSeed || realAdvance) {
                        firstHalPtsNs = halTs;
                        halPtsStuckCount = 0;
                        ptsNs = halTs;
                        candidateDomain = PTS_DOMAIN_HW;
                    } else {
                        halPtsStuckCount++;
                        if (halPtsStuckCount >= HAL_PTS_STUCK_THRESHOLD) {
                            logger.warn("HAL timestamp stuck near " + firstHalPtsNs
                                + "ns (advance=" + advance + "ns < "
                                + HAL_PTS_MIN_ADVANCE_NS + "ns/frame) for "
                                + halPtsStuckCount
                                + " frames — latching to System.nanoTime() for the rest of the session");
                            useSystemNanoForPts = true;
                        }
                        ptsNs = System.nanoTime();
                        candidateDomain = PTS_DOMAIN_NANO;
                    }
                }
                if (candidateDomain != ptsDomain) {
                    // Cross-domain transition — re-anchor without applying
                    // the same-domain monotonic clamp. Pano's same fix:
                    // PanoramicCameraGpu:1765-1779.
                    if (ptsDomain != PTS_DOMAIN_NONE) {
                        logger.info("OEM PTS domain transition: "
                            + (ptsDomain == PTS_DOMAIN_HW ? "HW" : "NANO")
                            + " → "
                            + (candidateDomain == PTS_DOMAIN_HW ? "HW" : "NANO")
                            + " (re-anchoring at " + ptsNs + "ns)");
                    }
                    ptsDomain = candidateDomain;
                    lastPtsNs = ptsNs;
                } else {
                    if (ptsNs <= lastPtsNs) ptsNs = lastPtsNs + 1_000L;
                    lastPtsNs = ptsNs;
                }

                // Parked-idle throttle: only draw+swap (i.e. feed the encoder)
                // every Nth iteration. updateTexImage + PTS resolution above ran
                // this iteration regardless, so the HAL is fully drained and the
                // NEXT emitted frame still carries a correct monotonic wall-clock
                // PTS — playback speed is unchanged, only the encode rate drops.
                // stride==1 (default / event / not-idle) → byte-identical to today.
                final int stride = recorderDrawStride;
                if (stride <= 1 || (renderStridePhase++ % stride) == 0) {
                    drawPassthrough();

                    eglCore.swapBuffersWithTimestamp(encoderEglSurface, ptsNs);
                    drawSequence.incrementAndGet();

                    // Idle keyframe pulse: when strided down and NOT recording an
                    // event, force an IDR every ~preRecordSeconds/2 so the
                    // pre-record ring always holds a keyframe despite the stretched
                    // low-rate GOP. Guarded on stride>1 so it never runs at full
                    // rate; only adds IDRs.
                    if (stride > 1 && !recordingEventOwned.get()) {
                        long nowMs = android.os.SystemClock.uptimeMillis();
                        long periodMs = Math.max(1000L, resolveOemPreRecordSeconds() * 1000L / 2L);
                        if (oemLastIdleKeyframeMs == 0
                                || (nowMs - oemLastIdleKeyframeMs) >= periodMs) {
                            oemLastIdleKeyframeMs = nowMs;
                            HardwareEventRecorderGpu enc = encoder;
                            if (enc != null) {
                                try { enc.requestSyncFrame(); } catch (Throwable ignored) {}
                            }
                        }
                    } else {
                        oemLastIdleKeyframeMs = 0;
                    }
                }
                consecutiveErrors = 0;
            } catch (Throwable t) {
                consecutiveErrors++;
                logger.warn("renderLoop iter error (" + consecutiveErrors
                    + "/" + MAX_CONSECUTIVE_RENDER_ERRORS + "): " + t.getMessage());
                if (consecutiveErrors >= MAX_CONSECUTIVE_RENDER_ERRORS) {
                    logger.error("OEM render loop: too many consecutive errors; stopping");
                    // Schedule stop on a different thread — running stop()
                    // here would deadlock on glThread.quitSafely() since
                    // we're on glThread.
                    new Thread(() -> {
                        try { stop(); } catch (Throwable ignored) {}
                    }, "OemDvr-RenderStop").start();
                    return;
                }
                // FIX H4: 200 ms back-off after iteration error (was 50 ms).
                // A persistent error mode (e.g., GL context lost, encoder
                // input surface dead) used to spin at 50 ms × N retries
                // burning CPU before MAX_CONSECUTIVE_RENDER_ERRORS triggered
                // the stop. 200 ms is still well under any user-visible
                // recovery window and quartis the wakeup rate during the
                // error burst.
                try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /**
     * Passthrough draw: full-screen triangle-strip sampling the OES camera
     * texture into the encoder Surface. Caller MUST have populated
     * {@link #texMatrix} via cameraSurfaceTexture.getTransformMatrix on
     * the same iteration — the renderLoop does that once and shares the
     * result with the scaler-publish branch.
     */
    private void drawPassthrough() {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // R9 #2: drop the cameraSurfaceTexture re-read. The renderLoop's
        // stLocal capture already null-guarded for this iteration (an in-
        // flight stop that nulled the field would have made the renderLoop
        // bail before reaching here). drawPassthrough only consumes
        // cameraTextureId and the precomputed texMatrix; it doesn't touch
        // the SurfaceTexture at all. Re-reading the volatile field defeats
        // the snapshot pattern.
        if (passthroughProgram == 0) return;

        GLES20.glUseProgram(passthroughProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11_OES_TEXTURE_EXTERNAL, cameraTextureId);
        GLES20.glUniform1i(uTextureLoc, 0);
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0);

        GLES20.glEnableVertexAttribArray(aPositionLoc);
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoordLoc);
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLoc);
        GLES20.glDisableVertexAttribArray(aTexCoordLoc);
        GLES20.glBindTexture(GLES11_OES_TEXTURE_EXTERNAL, 0);

        // Overlay composite — same gate as the pano renderer: only run
        // while a clip is actively being written, the user opted in,
        // and we have a renderer + collector to source the bitmap from.
        // Cheap when off: a single boolean check on the volatile flag.
        if (overlayEnabled
                && recording.get()
                && overlayProgramId != 0
                && overlayRenderer != null
                && telemetryCollector != null) {
            overlayFrameCounter++;
            try {
                // The software Canvas raster now runs on OverlayBitmapRenderer's
                // dedicated 2 Hz background worker (started via reconcileTelemetryHold),
                // not here — it was the expensive per-icon Gaussian-blur half eating
                // GL-thread time before eglSwap. The GL thread keeps only the cheap
                // half: swapAndGetFront() + texSubImage2D upload + composite.
                //
                // Upload the new bitmap only when the double buffer
                // actually swapped — texSubImage2D on an unchanged
                // bitmap is wasted bandwidth (matches the pano impl).
                android.graphics.Bitmap overlayBitmap = overlayRenderer.swapAndGetFront();
                if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
                    if (!overlayTextureInitialized) {
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                        overlayTextureInitialized = true;
                    } else {
                        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, overlayBitmap);
                    }
                    overlayTextureReady = true;
                }

                if (overlayTextureReady) {
                    GLES20.glEnable(GLES20.GL_BLEND);
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

                    GLES20.glUseProgram(overlayProgramId);

                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
                    GLES20.glUniform1i(overlayUTextureLoc, 1);

                    GLES20.glEnableVertexAttribArray(overlayAPositionLoc);
                    GLES20.glVertexAttribPointer(overlayAPositionLoc, 2,
                        GLES20.GL_FLOAT, false, 0, overlayVertexBuffer);
                    GLES20.glEnableVertexAttribArray(overlayATexCoordLoc);
                    GLES20.glVertexAttribPointer(overlayATexCoordLoc, 2,
                        GLES20.GL_FLOAT, false, 0, overlayTexCoordBuffer);

                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

                    GLES20.glDisableVertexAttribArray(overlayAPositionLoc);
                    GLES20.glDisableVertexAttribArray(overlayATexCoordLoc);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                    GLES20.glDisable(GLES20.GL_BLEND);
                    // Restore TEXTURE0 so the next passthrough draw doesn't
                    // bind the OES texture into TEXTURE1 by accident.
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                }
            } catch (Throwable t) {
                // Never let a bad overlay frame drop a clip frame —
                // log the first few then go silent (mirrors pano).
                if (overlayFrameCounter <= 5) {
                    logger.warn("OEM overlay draw error: " + t.getMessage());
                }
            }
        }
    }

    private String generateOutputPath() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        // FIX (false-GREEN / dvr_ stranded on dead volume): re-resolve the LIVE
        // recordings dir at each segment open instead of using the dir latched
        // at construction. The pano cam_* path already tracks the resolved
        // (post-fallback) dir per-segment via StorageManager.getRecordingsDir();
        // dvr_ previously did not — so a dvr pipeline (re)started during a BYD
        // vold ACC-cycle unmount window wrote to internal for its ENTIRE
        // lifetime (or to a dead mount), never recovering when SD remounted.
        // getRecordingsPath() returns the resolveActive()-resolved volatile dir,
        // which already reflects the unmount→internal fallback AND the
        // remount→external recovery, so reading it here makes dvr_ honor both
        // automatically. Construction-time `outputDir` stays the safety net if
        // StorageManager is somehow unreachable.
        String dir = outputDir;
        try {
            com.overdrive.app.storage.StorageManager sm =
                com.overdrive.app.storage.StorageManager.getInstance();
            String live = sm.getRecordingsPath();
            if (live != null && !live.isEmpty()) dir = live;
            // ENOSPC fallback: if the live dir is on a full external volume,
            // redirect this dvr_ segment to internal (mirrors the cam_* path).
            // Reserve is smaller than cam_*'s 100MB because OEM dvr clips are a
            // single forward sensor at a lower bitrate; 40MB comfortably covers
            // a 2-min segment with headroom.
            File safe = sm.resolveTargetWithEnospcFallback(new File(dir), 40 * 1024 * 1024);
            if (safe != null) dir = safe.getAbsolutePath();
        } catch (Throwable t) {
            logger.warn("generateOutputPath: live recordings dir unavailable, "
                + "using construction-time outputDir: " + t.getMessage());
        }
        File f = new File(dir, "dvr_" + stamp + ".mp4");
        return f.getAbsolutePath();
    }

    private void runOnGlThreadAndWait(Runnable r) throws Exception {
        if (Thread.currentThread() == glThread) { r.run(); return; }
        Object[] err = new Object[1];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        glHandler.post(() -> {
            try { r.run(); } catch (Throwable t) { err[0] = t; }
            latch.countDown();
        });
        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("GL thread setup timed out");
        }
        if (err[0] != null) {
            if (err[0] instanceof Exception) throw (Exception) err[0];
            throw new RuntimeException((Throwable) err[0]);
        }
    }

    private void stopInternal(boolean fromStartFailure) {
        // R8-A #14: interrupt the watchdog BEFORE acquiring lifecycleLock.
        // Without this, watchdog's mid-sleep cycle (up to 5s) keeps the
        // thread alive past stop completion, which is harmless but
        // observable in /proc/<pid>/task. Interrupt is safe under any
        // ordering — the watchdog loop's `running.get()` check + sleep
        // both bail on interruption.
        Thread wdog = watchdogThread;
        if (wdog != null) wdog.interrupt();

        // R8-A #1: detachFromPano runs OUTSIDE lifecycleLock to avoid a
        // potential deadlock cycle (OEM-lifecycleLock ↔ pano-streamLifecycleLock
        // ↔ pano-GL-handler-barrier). It only reads pano state and posts
        // a no-op Runnable; nothing in it mutates OEM-instance state, so
        // it doesn't need the OEM lock. By moving it out of the locked
        // region we sidestep any future lock-ordering bug.
        detachFromPano();

        lifecycleLock.lock();
        try {
            stopInternalLocked(fromStartFailure);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Reattach pano's stream sink to its own AVM mosaic AND wait for one
     * frame to complete on pano's GL thread, so a subsequent
     * glDeleteTextures on our EXTERNAL_OES texture can't race an
     * in-flight pano drawFrame. Lock-free; called from stopInternal
     * before lifecycleLock acquisition (R8-A #1).
     */
    private void detachFromPano() {
        try {
            com.overdrive.app.surveillance.GpuSurveillancePipeline pano =
                com.overdrive.app.daemon.CameraDaemon.getGpuPipeline();
            if (pano == null) return;
            boolean wasSampling = false;
            try {
                wasSampling = pano.isStreamingEnabled()
                    && pano.getStreamViewMode() == 6;
            } catch (Throwable ignored) {}
            pano.reattachOwnStreamCallback();
            if (wasSampling) {
                com.overdrive.app.camera.PanoramicCameraGpu panoCam = pano.getCamera();
                android.os.Handler glH = panoCam != null ? panoCam.getGlHandler() : null;
                if (glH != null) {
                    final java.util.concurrent.CountDownLatch barrier =
                        new java.util.concurrent.CountDownLatch(1);
                    if (glH.post(barrier::countDown)) {
                        try { barrier.await(500, java.util.concurrent.TimeUnit.MILLISECONDS); }
                        catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.warn("stop: pano reattach failed: " + t.getMessage());
        }
    }

    private void stopInternalLocked(boolean fromStartFailure) {
        // Best-effort tear-down. Errors during stop are logged and swallowed
        // so we never block a daemon shutdown.
        //
        // detachFromPano() ran in stopInternal BEFORE we acquired
        // lifecycleLock — it's lock-free and just reattaches pano's
        // stream sink + waits one frame on pano's GL handler. By the
        // time we reach this method, pano is no longer sampling our
        // EXTERNAL_OES texture, so the glDeleteTextures inside the GL
        // Runnable below is safe.
        try {
            if (recording.getAndSet(false) && encoder != null) {
                encoder.stopEventRecording(true, 0);
            }
        } catch (Throwable t) { logger.warn("stop: stopEventRecording: " + t.getMessage()); }
        // Release the telemetry polling refcount if held — without this,
        // every full pipeline tear-down (user disable, watchdog stop,
        // quality-mirror restart) would leak a polling refcount on the
        // shared TelemetryDataCollector, eventually pinning it at 5Hz
        // forever from this caller. Both `recording` and `running` are
        // false at this point so reconcileTelemetryHold drops the hold.
        try { reconcileTelemetryHold(); } catch (Throwable ignored) {}

        try {
            if (cameraObj != null) {
                Class<?> avm = cameraObj.getClass();
                try {
                    Method m = avm.getDeclaredMethod("stopPreview");
                    m.setAccessible(true);
                    m.invoke(cameraObj);
                } catch (Throwable ignored) {}
                try {
                    Method m = avm.getDeclaredMethod("close");
                    m.setAccessible(true);
                    m.invoke(cameraObj);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { logger.warn("stop: AVMCamera close: " + t.getMessage()); }
        cameraObj = null;

        // ST.release MUST happen on the GL thread, ordered AFTER the
        // render loop's last updateTexImage. Pre-fix this released the
        // ST from the caller thread BEFORE the runOnGlThreadAndWait
        // Runnable below — leaving a ~10-30ms window where the render
        // loop could call updateTexImage on a released SurfaceTexture
        // (silent no-op or IllegalStateException; on some Adreno
        // builds, segfault inside the BufferQueue cleanup). running.set
        // (false) only stops new iterations; mid-iteration calls still
        // complete. By moving ST.release into the GL Runnable, handler
        // ordering guarantees the render loop has finished its current
        // iteration before ST.release runs.
        // Snapshot to locals so the lambda's captures stay valid even
        // if the surrounding catch path fires concurrently.
        final Surface cameraSurfaceLocal = cameraSurface;
        final SurfaceTexture cameraSurfaceTextureLocal = cameraSurfaceTexture;
        cameraSurface = null;
        cameraSurfaceTexture = null;

        // EGL release MUST happen on the GL thread (the context is bound
        // there). If runOnGlThreadAndWait times out — which is exactly the
        // failure mode the watchdog protects against (wedged eglCore.makeCurrent
        // / dead AVMCamera HAL stuck in updateTexImage) — we MUST NOT silently
        // null eglCore: that leaks both EGL surfaces (encoder window + dummy
        // pbuffer) and the camera external-OES texture. Track whether the
        // GL-thread release actually ran; on timeout, force-quit the thread
        // unsafely so the surfaces / texture release with the process at
        // worst on next start (or full daemon kill, if ever).
        boolean glReleased = false;
        if (glHandler != null) {
            try {
                runOnGlThreadAndWait(() -> {
                    // Release SurfaceTexture + Surface FIRST on the GL
                    // thread (after the in-flight updateTexImage finishes
                    // by handler ordering), then the EGL surfaces and
                    // texture. The unbind+listener-null avoids spurious
                    // onFrameAvailable callbacks landing during teardown.
                    try {
                        if (cameraSurfaceTextureLocal != null) {
                            cameraSurfaceTextureLocal.setOnFrameAvailableListener(null);
                            cameraSurfaceTextureLocal.release();
                        }
                    } catch (Throwable ignored) {}
                    try {
                        if (cameraSurfaceLocal != null) cameraSurfaceLocal.release();
                    } catch (Throwable ignored) {}
                    if (eglCore != null) {
                        try {
                            if (encoderEglSurface != null) {
                                try { eglCore.destroySurface(encoderEglSurface); } catch (Throwable ignored) {}
                            }
                            if (dummySurface != null) {
                                try { eglCore.destroySurface(dummySurface); } catch (Throwable ignored) {}
                            }
                            if (cameraTextureId != 0) {
                                try { GLES20.glDeleteTextures(1, new int[]{cameraTextureId}, 0); } catch (Throwable ignored) {}
                            }
                            if (passthroughProgram != 0) {
                                try { GLES20.glDeleteProgram(passthroughProgram); } catch (Throwable ignored) {}
                            }
                            if (overlayTextureId != 0) {
                                try { GLES20.glDeleteTextures(1, new int[]{overlayTextureId}, 0); } catch (Throwable ignored) {}
                            }
                            if (overlayProgramId != 0) {
                                try { GLES20.glDeleteProgram(overlayProgramId); } catch (Throwable ignored) {}
                            }
                            eglCore.release();
                        } catch (Throwable ignored) {}
                    }
                });
                glReleased = true;
            } catch (Throwable ignored) {
                logger.warn("stop: GL release timed out — GL thread is wedged. "
                    + "Falling back to display-scoped destroySurface from this "
                    + "thread; texture/program deletes are sacrificed (no current "
                    + "context) but the EGL surfaces and encoder will not leak.");
                // Display-scoped EGL surface destroy does NOT need a current
                // context — eglDestroySurface only needs the EGLDisplay. We
                // can salvage the two EGLSurfaces from this calling thread
                // even though the GL render thread is wedged.
                try {
                    if (eglCore != null && eglCore.getDisplay() != null) {
                        if (encoderEglSurface != null) {
                            try { android.opengl.EGL14.eglDestroySurface(
                                eglCore.getDisplay(), encoderEglSurface); }
                            catch (Throwable ignoredInner1) {}
                        }
                        if (dummySurface != null) {
                            try { android.opengl.EGL14.eglDestroySurface(
                                eglCore.getDisplay(), dummySurface); }
                            catch (Throwable ignoredInner2) {}
                        }
                    }
                } catch (Throwable ignoredInner3) {}
                // Texture/program deletes need a current context; log the
                // ID so an external diagnostic can correlate the leak.
                if (cameraTextureId != 0) {
                    logger.warn("stop: leaking EXTERNAL_OES texture id=" + cameraTextureId
                        + " on wedged GL path (no current context for glDeleteTextures)");
                }
                if (passthroughProgram != 0) {
                    logger.warn("stop: leaking GL program id=" + passthroughProgram
                        + " on wedged GL path (no current context for glDeleteProgram)");
                }
                if (overlayTextureId != 0) {
                    logger.warn("stop: leaking overlay texture id=" + overlayTextureId
                        + " on wedged GL path");
                }
                if (overlayProgramId != 0) {
                    logger.warn("stop: leaking overlay program id=" + overlayProgramId
                        + " on wedged GL path");
                }
                // Wedged GL path also missed the ST/Surface release the
                // queued GL Runnable would have done. Release here from
                // the caller thread — safe in the wedge case because the
                // GL thread isn't running, so there's no concurrent
                // updateTexImage to race.
                try {
                    if (cameraSurfaceTextureLocal != null) {
                        cameraSurfaceTextureLocal.setOnFrameAvailableListener(null);
                        cameraSurfaceTextureLocal.release();
                    }
                } catch (Throwable wedgeIgnoredA) {}
                try {
                    if (cameraSurfaceLocal != null) cameraSurfaceLocal.release();
                } catch (Throwable wedgeIgnoredB) {}
            }
        } else {
            // No GL handler at all (init aborted before glThread came up).
            // Release the ST/Surface here directly — no GL thread to race.
            try {
                if (cameraSurfaceTextureLocal != null) {
                    cameraSurfaceTextureLocal.setOnFrameAvailableListener(null);
                    cameraSurfaceTextureLocal.release();
                }
            } catch (Throwable noGlIgnoredA) {}
            try {
                if (cameraSurfaceLocal != null) cameraSurfaceLocal.release();
            } catch (Throwable noGlIgnoredB) {}
        }
        encoderEglSurface = null;
        dummySurface = null;
        cameraTextureId = 0;
        // The share-group property belongs to the context we just destroyed. Clear it
        // so a restarted pipeline can't inherit a stale "shared" claim and let
        // isRouteReady() green-light a cross-context bind against a dead texture.
        eglSharedWithPano = false;
        passthroughProgram = 0;
        overlayTextureId = 0;
        overlayProgramId = 0;
        overlayTextureInitialized = false;
        overlayTextureReady = false;
        overlayFrameCounter = 0;
        if (overlayRenderer != null) {
            // Stop+join the raster worker BEFORE recycling its bitmaps so it
            // can't draw into a recycled bitmap (use-after-free).
            try { overlayRenderer.stopWorker(); } catch (Throwable ignored) {}
            try { overlayRenderer.release(); } catch (Throwable ignored) {}
            overlayRenderer = null;
        }
        eglCore = null;

        // Encoder cleanup. release() — NOT stopRecording() — is the
        // primitive that actually frees the MediaCodec, joins the
        // drainer, releases the input Surface, and drains the muxer
        // packet pools. stopRecording() only stops the muxer and (worse)
        // RESTARTS the drainer in HardwareEventRecorderGpu's close path,
        // so calling stopRecording here would orphan a fresh drainer
        // thread + the entire native codec on every clean teardown.
        // We still bound the call on a disposable thread because
        // release() can wedge on a stuck encoder; the 2s join is the
        // wedge-fence, not the cleanup primitive. Leaking a stuck
        // wedged thread is strictly preferable to leaking the encoder.
        if (encoder != null) {
            final HardwareEventRecorderGpu encRef = encoder;
            // ALWAYS spawn a release thread for the current encRef.
            // R2 fix #12 had an else-skip branch when a prior wedged
            // thread was alive, but that orphaned the CURRENT MediaCodec
            // every restart cycle (the previous wedged thread holds a
            // DIFFERENT encoder instance — interrupting it doesn't
            // release the new one). Telemetry-log when wedged threads
            // accumulate; the daemon=true threads reclaim on process
            // exit, but a leaked codec block doesn't.
            Thread previousWedged = wedgedEncoderStopThread.get();
            if (previousWedged != null && previousWedged.isAlive()) {
                logger.warn("stop: previous OemDvr-EncoderStop still alive — "
                    + "spawning new release thread for current encoder anyway "
                    + "(prior thread holds a DIFFERENT MediaCodec instance)");
            }
            Thread stopThread = new Thread(() -> {
                try { encRef.release(); } catch (Throwable ignored) {}
            }, "OemDvr-EncoderStop");
            stopThread.setDaemon(true);
            stopThread.start();
            try { stopThread.join(2000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (stopThread.isAlive()) {
                logger.warn("stop: encoder.release did not return within 2s; "
                    + "abandoning the drainer thread (MediaCodec native handle "
                    + "will reclaim on process exit)");
                wedgedEncoderStopThread.set(stopThread);
            } else {
                // Only clear the wedge tracker if our PRIOR wedge has
                // also finished. Else clearing here loses observability
                // of the still-alive prior thread for the next iteration.
                Thread prior = wedgedEncoderStopThread.get();
                if (prior == null || !prior.isAlive()) {
                    wedgedEncoderStopThread.set(null);
                }
            }
        }
        encoder = null;
        encoderSurface = null;

        if (glThread != null) {
            if (glReleased) {
                glThread.quitSafely();
            } else {
                // Wedged GL thread won't drain its message queue. quit() is
                // the unsafe variant — abandons pending work but doesn't
                // wait. Combined with thread.interrupt() it's our best shot
                // at not leaking the kernel TID.
                try { glThread.interrupt(); } catch (Throwable ignored) {}
                try { glThread.quit(); } catch (Throwable ignored) {}
            }
            glThread = null;
            glHandler = null;
        }
        if (irThread != null) {
            irThread.quitSafely();
            irThread = null;
            irHandler = null;
        }
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }

        if (!fromStartFailure) {
            logger.info("OemDashcamPipeline stopped");
        }
    }
}
