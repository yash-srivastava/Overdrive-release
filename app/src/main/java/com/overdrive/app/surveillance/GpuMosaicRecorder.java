package com.overdrive.app.surveillance;

import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import com.overdrive.app.logging.DaemonLogger;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;

import com.overdrive.app.camera.EGLCore;
import com.overdrive.app.camera.GlUtil;
import com.overdrive.app.telemetry.OverlayBitmapRenderer;
import com.overdrive.app.telemetry.TelemetryDataCollector;
import com.overdrive.app.telemetry.TelemetrySnapshot;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * GpuMosaicRecorder - GPU-based 2x2 grid compositor for zero-copy recording.
 * 
 * This class renders a 5120x960 camera strip into a 2560x1920 2x2 grid layout
 * directly to the MediaCodec encoder's input surface. All composition happens
 * on the GPU, achieving 0% CPU usage and 0 GB/s memory bandwidth.
 * 
 * Key features:
 * - Zero-copy GPU path (camera texture → encoder surface)
 * - Branchless fragment shader for optimal GPU performance
 * - Direct rendering to encoder (no intermediate buffers)
 * - 0% CPU usage for video composition
 */
public class GpuMosaicRecorder {
    private static final String TAG = "GpuMosaicRecorder";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // EGL and OpenGL state
    private EGLCore eglCore;
    private EGLSurface encoderSurface;
    private Surface encoderInputSurface;
    
    // PTS comes from the HAL-provided Image.getTimestamp() value, passed in
    // through drawFrame(textureId, timestampNs). The previous EMA-based
    // Time-Base Corrector clamped per-frame deltas to [30 ms, 500 ms] and
    // smoothed at α = 0.1, which was incompatible with rates ≥ ~22 fps —
    // the lower clamp pushed averageDeltaNs toward 33 fps regardless of the
    // real arrival rate, and any 200 ms spike took ~22 frames to recover,
    // producing the rubber-banding/snapback users saw at 15+ fps + high
    // bitrate. Letting the hardware timestamp speak for itself means PTS
    // values exactly mirror real camera cadence.
    
    // OpenGL program and locations
    private int programId;
    private int uCameraTexLocation;
    private int uApaModeLocation;
    private int uTexMatrixLocation;
    private int uApplyManualYFlipLocation;
    private int uProducerForFrontLocation;
    private int uProducerForRightLocation;
    private int uProducerForRearLocation;
    private int uProducerForLeftLocation;
    private int uFlipForFrontLocation;
    private int uFlipForRightLocation;
    private int uFlipForRearLocation;
    private int uFlipForLeftLocation;
    private int uRedMaskStrengthLocation;
    private int uApaCenterInsetLocation;
    private volatile float apaCenterInset = 0.0f;
    // Two-parameter division-model dewarp coefficients. 0 = passthrough
    // (identity). Positive values pull peripheral output pixels toward the
    // centre of the source so straight world lines come out straighter.
    // Sampling formula:
    //     r_source = r_output / (1 + k1·r² + k2·r⁴)
    // Single slider drives both in a fixed 3:1 ratio so the UX stays one
    // control; k2 disproportionately corrects the corners (r⁴ grows 4×
    // faster than r² between mid-radius and corner) while leaving the
    // centre unchanged. UI exposes 0..100 mapped to k1∈[0,0.30], k2∈[0,0.10];
    // see setRectifyStrength. Gated to the legacy 4-strip layout
    // (uApaMode==0): dilink4's HAL-emitted 2x2 lands clean per-tile, and
    // 3-cam / APA paths have non-square producer tiles where a global
    // radial formula would distort rather than rectify.
    //
    // Volatile — UI thread writes via the public setter, GL thread reads
    // inside the dirty-flag CAS block in drawFrame.
    private volatile float rectifyK1 = 0.0f;
    private volatile float rectifyK2 = 0.0f;
    // Per-cam tile aspect ratio (height/width). Default 0.75 = Seal
    // (1280×960). Pipeline pushes the profile-resolved value via
    // setRectifyAspect on init; volatile because the writer is the camera
    // GL thread (init) and the reader is the encoder GL thread (drawFrame),
    // and there's no enclosing lock between them.
    private volatile float rectifyAspect = 0.75f;
    private int uRectifyK1Location;
    private int uRectifyK2Location;
    private int uRectifyAspectLocation;
    private int uRecordLayoutLocation;
    // Optional dedicated windshield camera (dashcam top band). uWindshieldTex
    // is bound to GL_TEXTURE2 in drawFrame; uWindshieldReady gates its use.
    private int uWindshieldTexLocation;
    private int uWindshieldReadyLocation;
    private int aPositionLocation;
    private int aTexCoordLocation;

    // Red-overlay mask toggle. Off by default; UI flips on for cars where
    // the AVM HAL paints a red 'calibration failed' chrome. Volatile —
    // UI thread writes via setRedMaskEnabled, GL thread reads in drawFrame.
    private volatile boolean redMaskEnabled = false;

    // Per-output-corner producer-corner mapping for dilink4 layout. Each
    // 2-element pair is (cornerX, cornerY) of the producer 0.5×0.5
    // sub-rect this output corner samples. Default = identity mapping
    // (Front samples producer-TL, Right samples producer-TR, etc.).
    // We use a single 8-float field + lock since the camera and GL
    // threads can both touch this. Order matches uProducerFor{Front,
    // Right, Rear, Left}.
    private final float[] producerCornerMap = {
        0.0f, 0.0f,  // Front  ← producer TL
        0.5f, 0.0f,  // Right  ← producer TR
        0.0f, 0.5f,  // Rear   ← producer BL
        0.5f, 0.5f,  // Left   ← producer BR
    };
    // Per-role X/Y flip flags (xFlip, yFlip) for dilink4 layout. 1.0 =
    // flip that axis within the role's 0.5×0.5 producer corner. Order
    // matches producerCornerMap above. Default = no flips.
    private final float[] flipFlags = {
        0.0f, 0.0f,  // Front
        0.0f, 0.0f,  // Right
        0.0f, 0.0f,  // Rear
        0.0f, 0.0f,  // Left
    };
    private final Object producerCornerMapLock = new Object();

    // Last SurfaceTexture transform matrix published by the camera thread.
    // Initialised to identity so non-oem paths keep working as before.
    // Volatile because the camera thread writes via setTextureMatrix and
    // the encoder GL thread reads in drawFrame.
    private final float[] currentTexMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };
    
    // Vertex data
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    // Encoder reference
    private HardwareEventRecorderGpu encoder;
    
    // State
    // volatile + accessed under recordingLock for read-modify-write safety.
    // The double-check around encoder.triggerEventRecording closes the racing
    // start-paths (RecordingModeManager, deferred-listener thread, direct
    // CameraDaemon.startPipeline calls) that previously could land two muxers
    // on disk with timestamps milliseconds apart. The encoder side has its
    // own startStopLock as defense in depth.
    private volatile boolean recording = false;
    private final Object recordingLock = new Object();
    // FIX (audit R1, RESIDUAL): segment-rotation listener. Pipeline registers
    // a Runnable that stamps its lastSegmentRotateMs so RecordingModeManager
    // can suppress wedge-detection during the natural isRecording()=false
    // flicker between segments. Optional — null means no listener wired.
    private volatile Runnable segmentRotatedListener = null;
    // Pending override for the next {@link #startRecording(File, String)}
    // call. Written by the storage watchdog when a hot remount lands the
    // volume on a different filesystem path; consumed by the next
    // start-recording entry so future segments are written to the new
    // mount point rather than the stale path captured at the original
    // startRecording. Null = no override (default behaviour).
    private volatile java.io.File pendingOutputDirOverride = null;
    private volatile boolean apaMode = false;  // APA mode: passthrough instead of mosaic split
    private volatile int cameraLayout = 0;  // 0=4-cam, 1=full-frame, 2=3-cam, 3=four-corner remap
    // Set when setCameraLayout flips on a non-GL thread; consumed inside
    // drawFrame on the encoder GL thread to push the new uniform exactly
    // once. Per GLES2 spec, uniform values are part of the program object,
    // so a write outside drawFrame would be lost (we don't own the GL
    // context elsewhere). The previous code wrote glUniform1f every frame
    // — ~30 driver-side state-dirty calls per second on Adreno 610 for a
    // value that changes maybe twice a session.
    //
    // AtomicBoolean (not volatile boolean) because the consumer uses
    // getAndSet(false): a setCameraLayout that races between the consumer's
    // glUniform1f and its dirty-clear would otherwise be lost (volatile
    // pair has no read-modify-write atomicity), pinning the shader to a
    // stale layout forever. Also reset to true in init() so an EGL
    // reinit's fresh programId picks up the layout on first drawFrame.
    private final java.util.concurrent.atomic.AtomicBoolean apaModeUniformDirty =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    // Broader dirty bit covering the OTHER quasi-static uniforms that
    // change only on configuration: uApplyManualYFlip (depends on
    // cameraLayout), uProducerFor* / uFlipFor* (set once at init),
    // uRedMaskStrength + uApaCenterInset (rare config flips). Pre-fix
    // every drawFrame uploaded all 11 uniforms unconditionally and
    // alloc'd two scratch float[8] arrays. This bit is CAS-claimed so
    // a setter racing the consume re-flips dirty=true and the next
    // frame replays.
    private final java.util.concurrent.atomic.AtomicBoolean uniformsDirty =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    // Scratch buffers for the per-frame producer-corner snapshot under
    // the lock. Reused — no per-frame alloc.
    private final float[] producerCornerScratch = new float[8];
    private final float[] flipFlagsScratch = new float[8];
    // Recording composition layout. 0 = standard 2x2 360 mosaic (default)
    private volatile int recordLayout = 0;
    private final java.util.concurrent.atomic.AtomicBoolean recordLayoutUniformDirty =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    private long lastFrameTime = 0;
    private long frameCount = 0;
    
    // Overlay GL resources
    private int overlayTextureId;
    private int overlayProgramId;
    private int overlayAPositionLoc;
    private int overlayATexCoordLoc;
    private int overlayUTextureLoc;
    private FloatBuffer overlayVertexBuffer;
    private FloatBuffer overlayTexCoordBuffer;

    // Overlay state
    private volatile boolean overlayEnabled = false;
    private OverlayBitmapRenderer overlayRenderer;
    private TelemetryDataCollector telemetryCollector;
    // Which telemetry fields the overlay draws for THIS recorder's active flow.
    // Defaults to the legacy eight-field set so an unconfigured recorder is
    // visually identical to the pre-feature overlay. Pushed to the renderer and
    // used to report overlay-only field demand to the collector.
    private volatile com.overdrive.app.telemetry.TelemetryFields overlayFields =
        com.overdrive.app.telemetry.TelemetryFields.legacyDefault();
    // Stable key identifying this recorder as an overlay-field-demand consumer
    // on the shared TelemetryDataCollector ("pano" — the OEM pipeline uses "oem").
    private String overlayDemandKey = "pano";
    private int overlayFrameCounter = 0;
    // Frame stride between telemetry-overlay bitmap re-rasters, derived from
    // encoder fps in init() to land near 2 Hz (matches TelemetryDataCollector
    // overlay poll rate). Default assumes 15 fps → every 8th frame ≈ 1.9 Hz.
    private int overlayRasterStride = 8;
    private volatile boolean overlayRecordingModeAllowed = false;
    private boolean overlayTextureReady = false;
    private boolean overlayTextureInitialized = false;
    
    // HAL-contention safety valve.
    //
    // The original code skipped a frame any time the *previous* draw exceeded
    // 30 ms three times in a row. That tripped constantly at 25–30 fps + high
    // bitrate (per-frame budget = 33–40 ms; eglSwap fence + AI readback
    // routinely cross 30 ms by themselves), removing 1-in-N frames from the
    // recorded MP4 even when nothing was actually wrong.
    //
    // Under normal operation we now do nothing — eglSwapBuffers is supposed to
    // block when the encoder input queue is full, and acquireLatestImage will
    // drop incoming HAL frames at zero GPU cost when we fall behind.
    //
    // The valve only opens when the BYD native parking-camera app is sharing
    // the HAL with us (set via setHalContentionProbe). In that mode a stalled
    // GL thread fills the gralloc pool and the native AVM loses its preview
    // signal, so we sacrifice one frame to keep the BufferQueue draining.
    // Threshold is derived from the encoder's frame budget × 2, refreshed on
    // every init() so an FPS change scales it correctly.
    private long lastDrawDurationNs = 0;

    // Last PTS handed to the encoder surface. Belt-and-suspenders against
    // the encoder-input-queue lag at HW->NANO domain transition: PTS values
    // queued before the latch fires can have larger timestamps than NANO
    // values that follow, breaking muxer monotonicity. The PTS-source helper
    // in PanoramicCameraGpu already enforces monotonicity within its own
    // sequence, but doesn't see what's still pending in MediaCodec's input
    // queue. Clamping here against the previously-stamped value covers both
    // the cross-domain transition and any future code path that bypasses
    // nextFrameTimestampNs (audit P1).
    private long lastSwappedPtsNs = 0;
    private long maxDrawDurationNs = 100_000_000L;          // refreshed in init()
    private int consecutiveSlowFrames = 0;
    private static final int SLOW_FRAME_SKIP_THRESHOLD = 20; // ~0.7 s at 30 fps
    private int skippedFrames = 0;
    // Default probe — never trips. PanoramicCameraGpu installs the real one
    // pointing at BydCameraCoordinator.isNativeAppActive(); if no one wires
    // it, the safety valve stays inert and eglSwap handles backpressure
    // natively.
    private volatile java.util.function.BooleanSupplier halContentionProbe = () -> false;
    
    // EGL_BAD_SURFACE recovery: track consecutive surface errors to trigger reinit
    private int consecutiveSurfaceErrors = 0;
    private static final int SURFACE_ERROR_REINIT_THRESHOLD = 3;
    private volatile boolean needsReinit = false;

    // Per-stage timing diagnostic for drawFrame.
    //
    // The encoder GL thread's drawFrame() bundles five distinct GPU/CPU
    // operations under one timer in PanoramicCameraGpu.renderLoop's stage
    // log: makeCurrent + clear + mosaic-shader-draw + overlay-upload-and-
    // draw + eglSwapBuffersWithTimestamp. When the bundled "mosaic+swap"
    // worst-frame goes high, we can't tell which sub-stage was responsible.
    //
    // This per-stage timer logs the worst frame in a 30s window (matching
    // the parent stage timer's cadence) and breaks out:
    //   - mc:    makeCurrent
    //   - vp:    glViewport (near-free client-side state)
    //   - rt:    glClear = the FIRST GL command after makeCurrent, so the
    //            driver's lazy producer-side dequeueBuffer on the encoder's
    //            BufferQueue lands here. glClear is ~free on tiled Adreno; a
    //            non-trivial rt is encoder-input backpressure (the render
    //            target could not be acquired), NOT clearing cost. (This was
    //            previously mislabeled "cls" — see issue #176.)
    //   - shd:   mosaic shader bind + draw
    //   - ovl:   overlay (texSubImage2D upload + composite draw)
    //   - swap:  eglSwapBuffersWithTimestamp itself (also blocks on encoder
    //            input-pool backpressure)
    //
    // Encoder backpressure therefore surfaces in EITHER rt (dequeue) or swap
    // (queue), depending on when MediaCodec returns an input buffer — treat
    // rt+swap together as the encoder-queue wait.
    //
    // Once a stutter event is captured, the dominant sub-stage tells us
    // where to look — overlay upload vs shader draw vs encoder backpressure
    // produce identical "mosaic+swap=207ms" symptoms in the parent log.
    private static final long DRAW_STAGE_LOG_INTERVAL_MS = 30_000L;
    private long drawStageWindowStartMs = 0;
    private long drawStageWorstTotalNs = 0;
    private long drawStageWorstMakeCurrentNs = 0;
    private long drawStageWorstViewportNs = 0;
    private long drawStageWorstRenderTargetNs = 0;
    private long drawStageWorstShaderNs = 0;
    private long drawStageWorstOverlayNs = 0;
    private long drawStageWorstSwapNs = 0;
    private int  drawStageWindowFrames = 0;

    // Per-quadrant strip-X offsets resolved from the camera profile + user role
    // mapping. Default mirrors the legacy Seal layout (TL=Front, TR=Right,
    // BL=Rear, BR=Left). The fragment shader is rebuilt at construction time
    // with these constants baked in — no per-frame uniform write needed.
    private final float[] quadrantStripOffsetX;
    private final String fragmentShader;
    private final int viewportWidth;
    private final int viewportHeight;
    private static final float[] DEFAULT_QUADRANT_STRIP_OFFSET_X = {
        0.75f, 0.50f, 0.00f, 0.25f
    };
    private static final int DEFAULT_VIEWPORT_WIDTH = 2560;
    private static final int DEFAULT_VIEWPORT_HEIGHT = 1920;
    // Dashcam composition: fraction of the output height the forward view
    // occupies (top band). MUST match the shader's `float split` and the
    // players' DASHCAM_SPLIT.
    private static final float DASHCAM_SPLIT = 0.70f;
    // Dedicated windshield camera native aspect (1920x1080 = 16:9). Used to
    // crop it vertically into the dashcam top band without stretch.
    private static final float WINDSHIELD_SOURCE_ASPECT = 1.7777778f;

    // Fullscreen quad vertices (NDC coordinates)
    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,  // Bottom-left
         1.0f, -1.0f,  // Bottom-right
        -1.0f,  1.0f,  // Top-left
         1.0f,  1.0f   // Top-right
    };
    
    // Texture coordinates — UN-flipped V. The vertex shader applies the
    // appropriate Y-flip per layout:
    //   - Legacy (uApaMode <= 0.5): vertex shader inverts V so the recorder
    //     samples top-of-producer at top-of-screen, identical to pre-Phase-2
    //     output.
    //   - SurfaceTexture layouts 1 and 3: uTexMatrix already contains the
    //     Android producer Y-flip, so we DON'T pre-flip — that would
    //     double-flip and emit upside-down content.
    // Both branches yield the producer's top-of-image at top-of-screen.
    private static final float[] TEX_COORDS = {
        0.0f, 0.0f,  // Bottom-left vertex → bottom of texture
        1.0f, 0.0f,  // Bottom-right vertex → bottom of texture
        0.0f, 1.0f,  // Top-left vertex → top of texture
        1.0f, 1.0f   // Top-right vertex → top of texture
    };
    
    // Vertex shader. oem-parity: applies the SurfaceTexture transform
    // matrix (uTexMatrix) so the consumer samples whatever sub-region the
    // BYD HAL marked as "live frame" — without it we sample the whole
    // producer surface including any HAL chrome (calibration text, letter-
    // box, etc). The matrix is published per-frame via setTextureMatrix
    // and defaults to identity, which is safe for HALs that don't set one.
    //
    // Y-flip handling. Input TEX_COORDS are un-flipped (V=0 at bottom of
    // texture). Two cases for the shader:
    //   - DiLink 4 (uTexMatrix non-identity): the SurfaceTexture's matrix
    //     already includes the producer's Y-flip, so we apply it directly.
    //   - Legacy (uTexMatrix == identity): we must Y-flip explicitly so the
    //     producer's top-of-image lands at top-of-screen, matching the
    //     pre-Phase-2 ImageReader output bit-for-bit.
    // The shader uses uApplyManualYFlip (0 or 1) to switch between them.
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform float uApplyManualYFlip;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vec2 src = aTexCoord;\n" +
        "    if (uApplyManualYFlip > 0.5) src.y = 1.0 - src.y;\n" +
        "    vTexCoord = (uTexMatrix * vec4(src, 0.0, 1.0)).xy;\n" +
        "}\n";
    
    // Fragment shader - supports 4-camera mosaic, 3-camera mosaic, and APA passthrough.
    // uApaMode: 0.0 = 4-camera mosaic (Seal: pano_h/pano_l with surfaceMode=0)
    //           1.0 = APA passthrough (single pre-composited image)
    //           2.0 = 3-camera mosaic (Atto 3 default: Rear=0-25%, Side=25-75%, Front=75-100%)
    // 4-cam strip: cam1(Rear)=0.00, cam2(Left)=0.25, cam3(Right)=0.50, cam4(Front)=0.75
    // 3-cam strip: Rear=0.00-0.25, Left+Right=0.25-0.75, Front=0.75-1.00
    /** 4-camera mosaic offsets are baked at construction via
     *  {@link #buildFragmentShader(float[])} so the per-quadrant slice→corner
     *  mapping can be resolved per camera profile (Seal vs Tang) and per
     *  user role mapping. 3-cam and APA branches are layout-independent so
     *  they stay as-is. */
    
    // Overlay vertex shader - simple 2D passthrough
    private static final String OVERLAY_VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";
    
    // Overlay fragment shader - standard sampler2D with alpha (NOT OES)
    private static final String OVERLAY_FRAGMENT_SHADER =
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform sampler2D uTexture;\n" +
        "void main() {\n" +
        "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
        "}\n";

    public GpuMosaicRecorder() {
        this(null, DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT);
    }

    public GpuMosaicRecorder(float[] quadrantStripOffsetX) {
        this(quadrantStripOffsetX, DEFAULT_VIEWPORT_WIDTH, DEFAULT_VIEWPORT_HEIGHT);
    }

    /**
     * @param quadrantStripOffsetX Per-quadrant strip-X offsets in
     *     {Front, Right, Rear, Left} order. Resolved from
     *     {@code ResolvedCameraConfig.getQuadrantStripOffsetX()}. Pass null
     *     for the legacy Seal default.
     * @param viewportWidth Encoder/mosaic width. Should match the encoder's
     *     configured width — typically {@code panoWidth/2} (2560 on Seal,
     *     2560 on Tang).
     * @param viewportHeight Encoder/mosaic height. Should match the encoder's
     *     configured height — typically {@code panoHeight*2} (1920 on Seal,
     *     1440 on Tang).
     */
    public GpuMosaicRecorder(float[] quadrantStripOffsetX, int viewportWidth, int viewportHeight) {
        this.quadrantStripOffsetX = normalizeOffsets(quadrantStripOffsetX);
        this.viewportWidth = viewportWidth > 0 ? viewportWidth : DEFAULT_VIEWPORT_WIDTH;
        this.viewportHeight = viewportHeight > 0 ? viewportHeight : DEFAULT_VIEWPORT_HEIGHT;
        // Windshield top-band crop: the dedicated windshield camera is 16:9;
        // the dashcam top band is full-width × DASHCAM_SPLIT of the encoder
        // output. Crop it vertically so it fills the band without stretch.
        float topBandAspect =
            ((float) this.viewportWidth / (float) this.viewportHeight) / DASHCAM_SPLIT;
        float cropY = windshieldCropY(WINDSHIELD_SOURCE_ASPECT, topBandAspect);
        this.fragmentShader = buildFragmentShader(this.quadrantStripOffsetX, cropY);
    }

    /**
     * Initializes the GPU mosaic recorder.
     *
     * @param eglCore EGL context manager
     * @param encoder Hardware encoder that provides the input surface
     */
    public void init(EGLCore eglCore, HardwareEventRecorderGpu encoder) {
        // Release old resources if reinitializing
        if (this.encoderSurface != null && this.eglCore != null) {
            this.eglCore.destroySurface(this.encoderSurface);
            this.encoderSurface = null;
            logger.info("Released old encoder surface for reinitialization");
        }

        this.eglCore = eglCore;
        this.encoder = encoder;

        // Scale the HAL-contention safety valve to the configured fps. Budget
        // is 2 × the per-frame target so a normal eglSwap fence + AI readback
        // never trips it; only sustained backpressure during BYD native AVM
        // contention should cross it.
        int encFps = encoder != null ? encoder.getFps() : 15;
        maxDrawDurationNs = 2L * 1_000_000_000L / Math.max(1, encFps);

        // Re-raster the telemetry overlay bitmap at ~2 Hz, matching the
        // TelemetryDataCollector overlay poll rate. The CPU raster
        // (overlayRenderer.renderFrame) is the expensive half of the overlay
        // pass; the composite draw reuses the last texture every frame and is
        // cheap. Rastering faster than telemetry updates just redraws identical
        // pixels — pure waste. Derive the frame stride from fps so a 30 fps
        // profile rasters every 15th frame, 15 fps every 8th, etc., all landing
        // near 2 Hz. Min 1 so a pathologically low fps still rasters.
        overlayRasterStride = Math.max(1, Math.round(encFps / 2.0f));

        // Reset the swap-time monotonic clamp on every init so a recorder
        // reinit (encoder swap, codec change) doesn't carry over a stale
        // ceiling that would clamp every new frame to a far-future PTS.
        lastSwappedPtsNs = 0;

        // FIX (audit R2): register writer-abort callback so SD-card death /
        // disk-write failure inside the encoder thread propagates back into
        // wrapper.recording AND StorageManager.recordingActive. Without this
        // bridge, RMM's wedge detector reads pipeline.isRecording()==true
        // forever (the wrapper boolean lies), the watchdog's
        // pendingOutputDirOverride never gets consumed, and recording stays
        // dead until ACC OFF/ON or daemon restart.
        encoder.setWriterAbortListener(reason -> {
            try {
                if (recording) {
                    recording = false;
                    logger.warn("Writer aborted — wrapper recording flag cleared (reason="
                        + reason + ")");
                }
                com.overdrive.app.storage.StorageManager.getInstance().setRecordingActive(false);
                // FIX (false-GREEN): notify RMM immediately so its wedge flag
                // latches NOW instead of waiting up to a full 30s resync tick
                // (and risking the tick landing inside the 5s rotation grace,
                // which would mask it entirely). Without this, the status pill
                // stays GREEN via the deferredActive branch for up to 30s after
                // the writer died. resyncFromHardware does heavy work
                // (GearMonitor.start, synchronized(this)) so it MUST NOT run on
                // this disk-writer thread (it's being torn down) — fire it on a
                // short-lived daemon thread.
                final String abortReason = reason;
                Thread t = new Thread(() -> {
                    try {
                        com.overdrive.app.recording.RecordingModeManager rmm =
                            com.overdrive.app.daemon.CameraDaemon.getRecordingModeManager();
                        if (rmm != null) {
                            rmm.resyncFromHardware("writer-abort:" + abortReason);
                        }
                    } catch (Throwable inner) {
                        logger.warn("writer-abort RMM resync failed: " + inner.getMessage());
                    }
                }, "WriterAbortResync");
                t.setDaemon(true);
                t.start();
            } catch (Throwable t) {
                logger.warn("onWriterAborted bridge failed: " + t.getMessage());
            }
        });

        // Register callback to sync recording flag when encoder closes file
        encoder.setFileClosedCallback(() -> {
            if (recording) {
                recording = false;
                logger.info("Recording flag reset (encoder closed file)");
            }
            // FIX (audit R1, RESIDUAL): notify segment-rotation listener so
            // RecordingModeManager's wedge ticker can grace-window the
            // between-segments isRecording()=false flicker.
            Runnable rotL = segmentRotatedListener;
            if (rotL != null) {
                try { rotL.run(); }
                catch (Throwable t) {
                    logger.warn("Segment-rotated listener threw: " + t.getMessage());
                }
            }
        });
        
        // Get encoder's input surface
        encoderInputSurface = encoder.getInputSurface();
        if (encoderInputSurface == null) {
            throw new RuntimeException("Encoder input surface is null");
        }
        
        // Create EGL surface from encoder surface (with RECORDABLE flag)
        encoderSurface = eglCore.createWindowSurface(encoderInputSurface);
        
        // Compile shaders and create program (fragment shader is profile-baked)
        programId = GlUtil.createProgram(VERTEX_SHADER, fragmentShader);
        if (programId == 0) {
            throw new RuntimeException("Failed to create shader program");
        }
        
        // Get attribute and uniform locations
        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(programId, "aTexCoord");
        uCameraTexLocation = GLES20.glGetUniformLocation(programId, "uCameraTex");
        uApaModeLocation = GLES20.glGetUniformLocation(programId, "uApaMode");
        uTexMatrixLocation = GLES20.glGetUniformLocation(programId, "uTexMatrix");
        uApplyManualYFlipLocation = GLES20.glGetUniformLocation(programId, "uApplyManualYFlip");
        uProducerForFrontLocation = GLES20.glGetUniformLocation(programId, "uProducerForFront");
        uProducerForRightLocation = GLES20.glGetUniformLocation(programId, "uProducerForRight");
        uProducerForRearLocation  = GLES20.glGetUniformLocation(programId, "uProducerForRear");
        uProducerForLeftLocation  = GLES20.glGetUniformLocation(programId, "uProducerForLeft");
        uFlipForFrontLocation = GLES20.glGetUniformLocation(programId, "uFlipForFront");
        uFlipForRightLocation = GLES20.glGetUniformLocation(programId, "uFlipForRight");
        uFlipForRearLocation  = GLES20.glGetUniformLocation(programId, "uFlipForRear");
        uFlipForLeftLocation  = GLES20.glGetUniformLocation(programId, "uFlipForLeft");
        uRedMaskStrengthLocation = GLES20.glGetUniformLocation(programId, "uRedMaskStrength");
        uApaCenterInsetLocation = GLES20.glGetUniformLocation(programId, "uApaCenterInset");
        uRectifyK1Location = GLES20.glGetUniformLocation(programId, "uRectifyK1");
        uRectifyK2Location = GLES20.glGetUniformLocation(programId, "uRectifyK2");
        uRectifyAspectLocation = GLES20.glGetUniformLocation(programId, "uRectifyAspect");
        uRecordLayoutLocation = GLES20.glGetUniformLocation(programId, "uRecordLayout");
        uWindshieldTexLocation = GLES20.glGetUniformLocation(programId, "uWindshieldTex");
        uWindshieldReadyLocation = GLES20.glGetUniformLocation(programId, "uWindshieldReady");

        GlUtil.checkGlError("glGetLocation");
        
        // Create vertex buffers
        vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
        texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);
        
        // --- Overlay GL resource initialization ---
        overlayProgramId = GlUtil.createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER);
        if (overlayProgramId == 0) {
            logger.error("Failed to create overlay shader program - overlay disabled");
            overlayEnabled = false;
        } else {
            overlayAPositionLoc = GLES20.glGetAttribLocation(overlayProgramId, "aPosition");
            overlayATexCoordLoc = GLES20.glGetAttribLocation(overlayProgramId, "aTexCoord");
            overlayUTextureLoc = GLES20.glGetUniformLocation(overlayProgramId, "uTexture");
            
            // Generate overlay texture
            int[] texIds = new int[1];
            GLES20.glGenTextures(1, texIds, 0);
            overlayTextureId = texIds[0];
            
            // Overlay quad: top 160px of 1920px frame
            // NDC Y: +1.0 (top) down to +1.0 - 0.1667 = +0.8333
            float[] overlayVertexCoords = {
                -1.0f,  0.8333f,
                 1.0f,  0.8333f,
                -1.0f,  1.0f,
                 1.0f,  1.0f
            };
            // Tex coords flipped Y for correct orientation
            float[] overlayTexCoords = {
                0.0f, 1.0f,
                1.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f
            };
            overlayVertexBuffer = GlUtil.createFloatBuffer(overlayVertexCoords);
            overlayTexCoordBuffer = GlUtil.createFloatBuffer(overlayTexCoords);

            // On an encoder REINIT (init() re-run on an existing recorder, e.g.
            // a mid-recording quality/codec change), tear down the prior renderer
            // first — stop+join its raster worker and recycle its bitmaps — so we
            // don't orphan a 2Hz daemon thread + ~0.8MB of bitmaps per reinit.
            if (overlayRenderer != null) {
                overlayRenderer.stopWorker();
                overlayRenderer.release();
            }
            // Create bitmap renderer
            overlayRenderer = new OverlayBitmapRenderer();
            overlayTextureInitialized = false;
        }

        // Start the off-GL-thread raster worker if the overlay is already active
        // at init time. The 3 overlay setters run at recorder-CONFIG time (before
        // init() runs on the GL thread), when overlayRenderer is still null and
        // reconcileOverlayWorker early-returns — so without this, the worker would
        // only ever start if a setter fired AGAIN post-init. On the encoder-reinit
        // path no such setter fires, and the overlay would silently freeze. Mirror
        // OemDashcamPipeline's post-create reconcile.
        reconcileOverlayWorker();
        
        // Set GL clear color once. GL context state, persists across
        // drawFrame calls; the encoder EGL context is exclusive to this
        // recorder. Saves ~one glClearColor call per frame (~30/sec at
        // 30 fps) on the encoder GL thread.
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

        // The shader program is fresh; its uApaMode uniform defaults to 0.0f.
        // Force the next drawFrame to re-publish the current cameraLayout so
        // an EGL recovery (release → init) doesn't pin the new programId at
        // layout 0 when the field-level cameraLayout != 0.
        apaModeUniformDirty.set(true);
        // Same EGL-recovery discipline for the recording composition layout:
        // re-push uRecordLayout on the next frame of the fresh program object.
        recordLayoutUniformDirty.set(true);
        // ...and for the quasi-static uniform block. This was MISSING, and on
        // DiLink 4 it is a correctness bug, not just a cosmetic one: that block
        // carries uProducerFor*/uFlipFor* (the per-role 2x2 corner remap),
        // uApplyManualYFlip, uRedMaskStrength and uApaCenterInset. A fresh
        // program object starts with all of them at 0.0, so after any
        // release→init cycle (EGL recovery, or reinitializeEncoder swapping in a
        // new GpuMosaicRecorder on a codec/quality/fps change) the recorder
        // would composite with corner map {0,0} for every role — i.e. all four
        // output quadrants sampling the producer's top-left — and with the
        // manual Y-flip wrongly disabled on legacy. uniformsDirty is initialised
        // true at the field, so cold start was fine and only the re-init path
        // was affected, which is exactly why this went unnoticed.
        uniformsDirty.set(true);

        logger.info("GpuMosaicRecorder initialized (encoder codec=" +
            (encoder.isHevcCodec() ? "H.265" : "H.264") + ")");
    }
    
    /**
     * Draws a frame from the camera texture to the encoder surface.
     * 
     * This performs the GPU-based 2x2 grid composition and submits the
     * result directly to the encoder. All processing happens in VRAM.
     * 
     * NOTE: In SOTA mode, this ALWAYS renders (encoder is always running).
     * The recording flag only controls whether frames are saved to file.
     * 
     * @param cameraTextureId OpenGL texture ID containing camera frame
     */
    /**
     * Draws a frame from the camera texture to the encoder surface.
     * 
     * This performs the GPU-based 2x2 grid composition and submits the
     * result directly to the encoder. All processing happens in VRAM.
     * 
     * NOTE: In SOTA mode, this ALWAYS renders (encoder is always running).
     * The recording flag only controls whether frames are saved to file.
     * 
     * IMPORTANT: If the encoder is backed up (eglSwapBuffers blocking due to
     * full encoder input buffer), we skip rendering to prevent blocking the GL thread.
     * This keeps the camera HAL's BufferQueue flowing, which prevents the BYD native
     * parking camera app from losing video signal during prolonged recording.
     * 
     * @param cameraTextureId OpenGL texture ID containing camera frame
     * @param frameTimestampNs HAL-provided sensor timestamp in nanoseconds
     *                         (from Image.getTimestamp()). Stamped onto the
     *                         encoder surface via eglPresentationTimeANDROID
     *                         so MediaCodec emits PTS values that match real
     *                         camera cadence — no smoothing, no EMA, no clamps.
     */
    public void drawFrame(int cameraTextureId, long frameTimestampNs) {
        drawFrame(cameraTextureId, 0, false, frameTimestampNs);
    }

    /**
     * Render one mosaic frame, optionally compositing a dedicated windshield
     * camera into the dashcam top band. [windshieldTextureId] is an external
     * OES texture owned by the caller (PanoramicCameraGpu); it is sampled only
     * when [windshieldReady] AND the dashcam layout is active — in every other
     * case the 360 source is used and the windshield texture is ignored.
     */
    public void drawFrame(int cameraTextureId, int windshieldTextureId,
                          boolean windshieldReady, long frameTimestampNs) {
        // Check if initialized.
        //
        // programId/vertexBuffer are checked alongside eglCore/encoderSurface
        // because init() assigns the EGL fields BEFORE it compiles the shader
        // (encoderSurface at createWindowSurface, programId after). A shader
        // compile failure throws out of init() with eglCore + encoderSurface
        // already set but programId == 0 and vertexBuffer == null, so an
        // eglCore-only guard lets us fall through to glVertexAttribPointer
        // with a null Buffer — an NPE per frame (~4/s) for the life of the
        // process, drowning the log and hiding the real compile error.
        if (eglCore == null || encoderSurface == null
                || programId == 0 || vertexBuffer == null || texCoordBuffer == null) {
            // Not initialized (or init failed) - skip silently
            return;
        }

        // HAL-CONTENTION SAFETY VALVE: only trips when the BYD native AVM app
        // is actively sharing the camera. In normal operation we let
        // eglSwapBuffers block (its native backpressure mechanism) and let
        // acquireLatestImage() drop upstream frames at zero GPU cost.
        if (consecutiveSlowFrames >= SLOW_FRAME_SKIP_THRESHOLD
                && halContentionProbe.getAsBoolean()) {
            skippedFrames++;
            consecutiveSlowFrames = 0;
            if (skippedFrames % 10 == 1) {
                logger.warn("HAL contention: skipped " + skippedFrames +
                    " frames to keep BYD native AVM signal alive (last draw=" +
                    (lastDrawDurationNs / 1_000_000) + "ms, threshold=" +
                    (maxDrawDurationNs / 1_000_000) + "ms × " +
                    SLOW_FRAME_SKIP_THRESHOLD + ")");
            }
            return;
        }

        // SOTA: Always render to encoder (for pre-record buffer)
        // The encoder decides whether to write to file or just buffer

        long startTime = System.nanoTime();

        // Per-stage diagnostic timing. Cheap (~50ns nanoTime + Long math
        // per call); only the worst frame in a 30s window is logged.
        long t0 = startTime;

        // Make encoder surface current
        eglCore.makeCurrent(encoderSurface);
        long tAfterMakeCurrentNs = System.nanoTime();

        // Set viewport to encoder resolution (profile-driven: Seal=2560x1920, Tang=2560x1440)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
        long tAfterViewportNs = System.nanoTime();

        // Clear. glClearColor was hoisted to init() — it's GL context state
        // that persists; the encoder's EGL context is exclusive to this
        // recorder so no other code path can change it under us.
        //
        // NB: this is the FIRST GL command after makeCurrent, so the driver
        // lazily dequeues the encoder-input buffer HERE. On encoder
        // backpressure the block is charged to this segment ("rt"), not to a
        // clearing cost (glClear is ~free on tiled Adreno). See issue #176.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        long tAfterClearNs = System.nanoTime();

        // Use our shader program
        GLES20.glUseProgram(programId);

        // Bind camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(uCameraTexLocation, 0);
        // Bind the optional windshield texture to unit 2 (dashcam top band).
        // Fall back to the camera texture when no windshield frame is ready so
        // the external sampler always has a valid binding; the shader gates
        // actual use on uWindshieldReady.
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            (windshieldReady && windshieldTextureId != 0) ? windshieldTextureId : cameraTextureId);
        if (uWindshieldTexLocation >= 0) {
            GLES20.glUniform1i(uWindshieldTexLocation, 2);
        }
        if (uWindshieldReadyLocation >= 0) {
            GLES20.glUniform1f(uWindshieldReadyLocation,
                (windshieldReady && windshieldTextureId != 0) ? 1.0f : 0.0f);
        }
        // uApaMode uniform: rewrite only on layout change. Per GLES2 spec
        // uniform values are part of the program object and persist across
        // glUseProgram of the same program. setCameraLayout sets the dirty
        // flag from another thread; we consume it here on the GL thread.
        // Atomic claim of the dirty bit. If a setCameraLayout races between
        // the load and the uniform write, getAndSet(false) ensures the
        // racing setter's dirty=true survives (the racer sets it AFTER our
        // getAndSet returned true, so the next drawFrame will replay).
        // Read cameraLayout AFTER the claim so we see the latest value the
        // racer published before flipping the dirty bit.
        if (apaModeUniformDirty.compareAndSet(true, false)) {
            GLES20.glUniform1f(uApaModeLocation, (float) cameraLayout);
        }
        // Recording composition layout (0 = standard 2x2 mosaic, 1 = dashcam).
        // Deferred from setRecordingLayout() on another thread and consumed
        // here on the encoder GL thread — same dirty-bit discipline as uApaMode.
        if (recordLayoutUniformDirty.compareAndSet(true, false)) {
            if (uRecordLayoutLocation >= 0) {
                GLES20.glUniform1f(uRecordLayoutLocation, (float) recordLayout);
            }
        }

        // SurfaceTexture transform matrix. Published per-frame from
        // PanoramicCameraGpu after updateTexImage(). Identity if the path
        // never set one — that matches legacy ImageReader behaviour.
        if (uTexMatrixLocation >= 0) {
            GLES20.glUniformMatrix4fv(uTexMatrixLocation, 1, false, currentTexMatrix, 0);
        }
        // Quasi-static uniforms (manual-Y-flip, per-role producer corner +
        // flip, red-mask, APA center inset) change only on config / layout
        // changes. Wrap behind a CAS-claimed dirty bit so steady-state
        // frames pay zero uniform uploads + zero lock acquisitions.
        if (uniformsDirty.compareAndSet(true, false)) {
            if (uApplyManualYFlipLocation >= 0) {
                // Layouts 1 and 3 consume SurfaceTexture output. Layouts 0, 2 and DiLink 5 need the manual flip.
                GLES20.glUniform1f(uApplyManualYFlipLocation,
                    (cameraLayout == 1 || cameraLayout == 3) ? 0.0f : 1.0f);
            }
            if (uProducerForFrontLocation >= 0) {
                synchronized (producerCornerMapLock) {
                    System.arraycopy(producerCornerMap, 0, producerCornerScratch, 0, 8);
                    System.arraycopy(flipFlags, 0, flipFlagsScratch, 0, 8);
                }
                float[] m = producerCornerScratch;
                float[] f = flipFlagsScratch;
                GLES20.glUniform2f(uProducerForFrontLocation, m[0], m[1]);
                GLES20.glUniform2f(uProducerForRightLocation, m[2], m[3]);
                GLES20.glUniform2f(uProducerForRearLocation,  m[4], m[5]);
                GLES20.glUniform2f(uProducerForLeftLocation,  m[6], m[7]);
                if (uFlipForFrontLocation >= 0) {
                    GLES20.glUniform2f(uFlipForFrontLocation, f[0], f[1]);
                    GLES20.glUniform2f(uFlipForRightLocation, f[2], f[3]);
                    GLES20.glUniform2f(uFlipForRearLocation,  f[4], f[5]);
                    GLES20.glUniform2f(uFlipForLeftLocation,  f[6], f[7]);
                }
            }
            if (uRedMaskStrengthLocation >= 0) {
                GLES20.glUniform1f(uRedMaskStrengthLocation,
                    redMaskEnabled ? 1.0f : 0.0f);
            }
            if (uApaCenterInsetLocation >= 0) {
                GLES20.glUniform1f(uApaCenterInsetLocation, apaCenterInset);
            }
            if (uRectifyK1Location >= 0) {
                GLES20.glUniform1f(uRectifyK1Location, rectifyK1);
            }
            if (uRectifyK2Location >= 0) {
                GLES20.glUniform1f(uRectifyK2Location, rectifyK2);
            }
            if (uRectifyAspectLocation >= 0) {
                GLES20.glUniform1f(uRectifyAspectLocation, rectifyAspect);
            }
        }

        // Set up vertex attributes
        GLES20.glEnableVertexAttribArray(aPositionLocation);
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(aTexCoordLocation);
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        // Draw fullscreen quad (shader does the 2x2 grid mapping)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(aPositionLocation);
        GLES20.glDisableVertexAttribArray(aTexCoordLocation);
        long tAfterShaderNs = System.nanoTime();

        // OVERLAY PASS: Composite telemetry overlay if enabled
        if (overlayEnabled && overlayRecordingModeAllowed && overlayRenderer != null) {
            overlayFrameCounter++;
            try {
                // The software Canvas raster (renderFrame) now runs on
                // OverlayBitmapRenderer's dedicated 2 Hz background worker
                // (started in setOverlayEnabled), NOT here — the GL thread used
                // to eat that per-icon Gaussian-blur raster every ~8th frame
                // right before eglSwap. The GL thread now does ONLY the cheap
                // half: swapAndGetFront() + texSubImage2D upload + composite.
                //
                // Upload new bitmap to texture ONLY when the double buffer actually swapped.
                // swapAndGetFront() returns null when no new content is available,
                // avoiding the expensive texImage2D/texSubImage2D call on unchanged frames.
                android.graphics.Bitmap overlayBitmap = overlayRenderer.swapAndGetFront();
                if (overlayBitmap != null && !overlayBitmap.isRecycled()) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
                    if (!overlayTextureInitialized) {
                        // First upload: allocate GPU texture storage with texImage2D
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                        overlayTextureInitialized = true;
                    } else {
                        // Subsequent uploads: reuse existing texture storage with texSubImage2D
                        // This avoids GPU texture reallocation on every update
                        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, overlayBitmap);
                    }
                    overlayTextureReady = true;
                    
                    if (overlayFrameCounter <= 3) {
                        logger.info("Overlay: uploaded frame " + overlayFrameCounter + 
                            " bitmap=" + overlayBitmap.getWidth() + "x" + overlayBitmap.getHeight());
                    }
                }
                
                // Draw overlay quad EVERY frame (reuses last uploaded texture)
                if (overlayTextureReady) {
                    GLES20.glEnable(GLES20.GL_BLEND);
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                    
                    GLES20.glUseProgram(overlayProgramId);
                    
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId);
                    GLES20.glUniform1i(overlayUTextureLoc, 1);
                    
                    GLES20.glEnableVertexAttribArray(overlayAPositionLoc);
                    GLES20.glVertexAttribPointer(overlayAPositionLoc, 2, GLES20.GL_FLOAT, false, 0, overlayVertexBuffer);
                    GLES20.glEnableVertexAttribArray(overlayATexCoordLoc);
                    GLES20.glVertexAttribPointer(overlayATexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, overlayTexCoordBuffer);
                    
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    
                    GLES20.glDisableVertexAttribArray(overlayAPositionLoc);
                    GLES20.glDisableVertexAttribArray(overlayATexCoordLoc);
                    GLES20.glDisable(GLES20.GL_BLEND);
                }
            } catch (Exception e) {
                // Skip overlay on error, never drop frame
                if (overlayFrameCounter <= 5) {
                    logger.error("Overlay draw error: " + e.getMessage(), e);
                }
            }
        }
        long tAfterOverlayNs = System.nanoTime();

        // Push the HAL sensor timestamp straight through. MediaCodec uses the
        // value stamped on the encoder surface via eglPresentationTimeANDROID
        // to compute PTS, so the recorded MP4 inherits true camera cadence
        // without any software smoothing/EMA layer in between.
        //
        // Swap-time monotonic clamp: if the candidate is not strictly greater
        // than the previously-stamped value, bump by 1 ms. This guards the
        // muxer against any path that produces a non-monotonic PTS — most
        // notably the HW→NANO clock-domain transition where queued frames
        // already inside MediaCodec's input pool carry pre-transition
        // timestamps that can sequence-wise leapfrog post-transition ones.
        long stampedPtsNs = frameTimestampNs;
        if (stampedPtsNs <= lastSwappedPtsNs) {
            stampedPtsNs = lastSwappedPtsNs + 1_000_000L;  // +1 ms
        }
        lastSwappedPtsNs = stampedPtsNs;

        try {
            eglCore.swapBuffersWithTimestamp(encoderSurface, stampedPtsNs);
            consecutiveSurfaceErrors = 0;  // Reset on success
        } catch (RuntimeException e) {
            consecutiveSurfaceErrors++;
            if (consecutiveSurfaceErrors >= SURFACE_ERROR_REINIT_THRESHOLD) {
                logger.error("Encoder surface dead after " + consecutiveSurfaceErrors +
                    " consecutive errors, requesting reinit");
                needsReinit = true;
                encoderSurface = null;  // Prevent further attempts
                return;
            }
            if (consecutiveSurfaceErrors <= 3) {
                logger.warn("swapBuffers failed (" + consecutiveSurfaceErrors + "): " + e.getMessage());
            }
            return;
        }
        long tAfterSwapNs = System.nanoTime();

        // Track draw duration to detect encoder backpressure
        long elapsedNs = tAfterSwapNs - startTime;
        lastDrawDurationNs = elapsedNs;

        if (elapsedNs > maxDrawDurationNs) {
            consecutiveSlowFrames++;
        } else {
            consecutiveSlowFrames = 0;
        }

        // Per-stage diagnostic: track the worst frame in a 30s window so we
        // can localize the dominant cost on bad frames. The PARENT stage
        // timer in PanoramicCameraGpu sees this whole method as
        // "mosaic+swap"; if its worst frame exceeds the per-frame budget,
        // this log will tell us which sub-stage was responsible (overlay
        // upload? encoder backpressure in rt/swap? shader draw?).
        //
        // rt = glClear span = first GL cmd after makeCurrent, where the
        // encoder-input buffer is dequeued; treat rt+swap together as the
        // encoder-queue wait. See issue #176 (was previously labeled "cls").
        long stageTotalNs    = tAfterSwapNs       - t0;
        long stageMakeNs     = tAfterMakeCurrentNs - t0;
        long stageViewportNs = tAfterViewportNs   - tAfterMakeCurrentNs;
        long stageRtNs       = tAfterClearNs      - tAfterViewportNs;
        long stageShaderNs   = tAfterShaderNs     - tAfterClearNs;
        long stageOverlayNs  = tAfterOverlayNs    - tAfterShaderNs;
        long stageSwapNs     = tAfterSwapNs       - tAfterOverlayNs;
        drawStageWindowFrames++;
        if (stageTotalNs > drawStageWorstTotalNs) {
            drawStageWorstTotalNs        = stageTotalNs;
            drawStageWorstMakeCurrentNs  = stageMakeNs;
            drawStageWorstViewportNs     = stageViewportNs;
            drawStageWorstRenderTargetNs = stageRtNs;
            drawStageWorstShaderNs       = stageShaderNs;
            drawStageWorstOverlayNs      = stageOverlayNs;
            drawStageWorstSwapNs         = stageSwapNs;
        }
        long nowMsForStage = System.currentTimeMillis();
        if (drawStageWindowStartMs == 0) {
            drawStageWindowStartMs = nowMsForStage;
        } else if (nowMsForStage - drawStageWindowStartMs >= DRAW_STAGE_LOG_INTERVAL_MS) {
            logger.info(String.format(
                    "DrawStage(worst/30s): total=%dms mc=%dms vp=%dms rt=%dms shd=%dms ovl=%dms swap=%dms (frames=%d)",
                    drawStageWorstTotalNs        / 1_000_000,
                    drawStageWorstMakeCurrentNs  / 1_000_000,
                    drawStageWorstViewportNs     / 1_000_000,
                    drawStageWorstRenderTargetNs / 1_000_000,
                    drawStageWorstShaderNs       / 1_000_000,
                    drawStageWorstOverlayNs      / 1_000_000,
                    drawStageWorstSwapNs         / 1_000_000,
                    drawStageWindowFrames));
            drawStageWorstTotalNs = 0;
            drawStageWorstMakeCurrentNs = 0;
            drawStageWorstViewportNs = 0;
            drawStageWorstRenderTargetNs = 0;
            drawStageWorstShaderNs = 0;
            drawStageWorstOverlayNs = 0;
            drawStageWorstSwapNs = 0;
            drawStageWindowFrames = 0;
            drawStageWindowStartMs = nowMsForStage;
        }

        // Update stats (only count if actually recording to file)
        if (recording) {
            lastFrameTime = System.currentTimeMillis();
            frameCount++;
        }


    }
    
    /**
     * Starts recording to a file.
     *
     * @param outputPath Path for the output MP4 file
     * @return true iff the encoder accepted the trigger and a muxer is now
     *         live (or recording was already in flight). False when the
     *         encoder is null OR the savedFormat barrier inside
     *         HardwareEventRecorderGpu refused.
     */
    public boolean startRecording(String outputPath) {
        synchronized (recordingLock) {
            if (recording) {
                logger.warn("Already recording");
                return true;
            }

            // OWNERSHIP PRE-CHECK (audit R11-1 / ExtD-2): same guard as
            // triggerEventRecordingExclusive — the encoder's already-writing
            // no-op leg must not be reported as a successful start of OUR
            // outputPath (the file would never get a muxer).
            if (encoder != null && encoder.isWritingToFile()) {
                logger.warn("Encoder busy with a non-GMR session — refusing start for "
                        + outputPath);
                return false;
            }

            // Start encoder recording (with pre-record buffer flush)
            if (encoder != null && encoder.triggerEventRecording(outputPath, 5000)) {  // Default 5 sec post-record
                recording = true;
                frameCount = 0;

                // SOTA: Notify StorageManager that recording is active (for periodic cleanup)
                try {
                    com.overdrive.app.storage.StorageManager.getInstance().setRecordingActive(true);
                } catch (Exception e) {
                    logger.warn("Could not set recording active state: " + e.getMessage());
                }

                logger.info("Recording started: " + outputPath + " (codec=" +
                    (encoder.isHevcCodec() ? "H.265" : "H.264") + ")");
                return true;
            }
            logger.error("Failed to start encoder recording");
            return false;
        }
    }

    /**
     * Triggers event recording with pre-record buffer flush.
     * Alias for startRecording for API compatibility.
     *
     * @return true iff the encoder accepted the trigger and a muxer is now
     *         live. False when the encoder is null OR the savedFormat
     *         barrier inside HardwareEventRecorderGpu refused (encoder
     *         hasn't published its format yet — typically a cold-boot
     *         race). "Already recording" is reported as true since the
     *         existing recording satisfies the caller's intent.
     */
    public boolean triggerEventRecording(String outputPath, long postRecordDurationMs) {
        return triggerEventRecordingExclusive(outputPath, postRecordDurationMs)
                != TriggerResult.REFUSED;
    }

    /** Ownership-explicit result of {@link #triggerEventRecordingExclusive}. */
    public enum TriggerResult { STARTED, ALREADY_RECORDING, REFUSED }

    /**
     * Ownership-explicit variant of {@link #triggerEventRecording} (audit R6
     * lifecycle #1). The boolean form reports "already recording" as TRUE —
     * fine for fire-and-forget callers, but a caller that must UNWIND on a
     * failed commit (SurveillanceEngineGpu's epoch-checked start commits)
     * then believes it OWNS a session another caller opened, and its unwind
     * stop kills the other session's live recording (worst case: a stale
     * pre-bounce start stopping the successor arm session's recorder while
     * the engine reports recording — whole-session silent video loss).
     *
     * @return STARTED iff THIS call opened the recorder session (caller owns
     *         it and may stop it); ALREADY_RECORDING when another session
     *         owns it (caller must neither commit nor unwind); REFUSED on
     *         encoder refusal (savedFormat barrier / null encoder).
     */
    public TriggerResult triggerEventRecordingExclusive(String outputPath, long postRecordDurationMs) {
        synchronized (recordingLock) {
            if (recording) {
                logger.warn("Already recording");
                return TriggerResult.ALREADY_RECORDING;
            }

            // OWNERSHIP PRE-CHECK (audit R11-1 / ExtD-2, defense-in-depth
            // under the stop-path locking above). The encoder's trigger has
            // an "already writing → return true" no-op leg that this wrapper
            // cannot distinguish from a genuine open — the false STARTED it
            // produced meant a session that never got a muxer. With GMR's
            // stops now serialized on recordingLock, wrapper recording==false
            // with the encoder still writing can only mean a NON-GMR session
            // owns the encoder (e.g. a driving-mode file still mid-close on
            // a mode transition). REFUSE rather than adopt: the caller's
            // retry paths (continuous retry chain; next motion tick in smart
            // mode) re-attempt against a settled encoder.
            if (encoder != null && encoder.isWritingToFile()) {
                logger.warn("Encoder busy with a non-GMR session — refusing trigger for "
                        + outputPath);
                return TriggerResult.REFUSED;
            }

            // Start encoder recording (with pre-record buffer flush)
            if (encoder != null && encoder.triggerEventRecording(outputPath, postRecordDurationMs)) {
                recording = true;
                frameCount = 0;
                logger.info("Recording started: " + outputPath);
                return TriggerResult.STARTED;
            }
            logger.error("Failed to start encoder recording");
            return TriggerResult.REFUSED;
        }
    }
    
    /**
     * Starts recording (generates automatic filename).
     */
    public void startRecording() {
        startRecording(null, "cam");
    }
    
    /**
     * Starts recording with custom output directory and filename prefix.
     * 
     * @param outputDir Custom output directory (null for default recordings dir)
     * @param prefix Filename prefix (e.g., "cam", "proximity", "event")
     */
    public void startRecording(java.io.File outputDir, String prefix) {
        // Two-phase guard for the directory+prefix overload:
        //
        // Phase 1 (this check, intentionally unlocked): cheap volatile read.
        //   If recording is already true, skip mkdirs/ensureSpace/timestamp
        //   generation — work that's irrelevant once another caller has
        //   started. This is a performance optimization, NOT the correctness
        //   guarantee.
        //
        // Phase 2 (inside the inner startRecording(outputPath) call): the
        //   recordingLock-protected re-check is the authoritative one. If two
        //   callers both pass Phase 1, both will compute their own timestamps,
        //   but only the first to acquire recordingLock will start the encoder
        //   and create a file. The second caller is rejected at the inner
        //   guard and discards its timestamp harmlessly — the wasted work is
        //   bounded to a few mkdirs and a SimpleDateFormat call, which is
        //   acceptable to keep this method lock-free during normal operation.
        //
        // The duplicate-files-on-disk symptom this whole structure prevents
        // came from an earlier version where neither phase used a lock; the
        // encoder's startStopLock alone was insufficient because the wrapper's
        // recording flag had its own race window.
        if (recording) {
            logger.warn("Already recording — ignoring redundant start (dir=" +
                (outputDir != null ? outputDir.getName() : "default") + ", prefix=" + prefix + ")");
            return;
        }
        // SOTA: Use StorageManager for recordings directory and auto-cleanup.
        // Each step is wrapped in lightweight timing because this path used
        // to silently stall for minutes when the boot reap held the storage
        // cleanup lock and ensureRecordingsSpace blocked behind it. With no
        // logs between "Starting DRIVE_MODE recording" and the muxer start,
        // the symptom looked like "recording isn't triggering" rather than
        // "storage cleanup is starving the recorder." Treat any pre-flight
        // step that takes &gt;1s as suspicious and log it.
        long startNs = System.nanoTime();
        try {
            com.overdrive.app.storage.StorageManager storageManager =
                com.overdrive.app.storage.StorageManager.getInstance();

            // Resolution order:
            //  1) Caller-supplied outputDir (explicit, wins).
            //  2) pendingOutputDirOverride (set by storage watchdog after a
            //     hot remount; one-shot — cleared after consumption so the
            //     next call falls back to live StorageManager.getRecordingsDir()
            //     unless the watchdog re-pokes it again).
            //  3) Live StorageManager.getRecordingsDir() (default).
            java.io.File overrideDir = this.pendingOutputDirOverride;
            java.io.File targetDir;
            if (outputDir != null) {
                targetDir = outputDir;
            } else if (overrideDir != null) {
                // Reconcile the latched override against current storage state
                // before trusting it. The watchdog-FAILURE branch pushes an
                // INTERNAL fallback while an external volume is gone; if that
                // volume came back via the recording-start path's own mount
                // (not a watchdog SUCCESS tick, which is the only place that
                // re-pokes setOutputDir), the latch is stale and would land
                // this recording on internal despite the configured SD/USB
                // being available again. reconcileRecordingOverride redirects
                // ONLY that confirmed-stale case and is otherwise a no-op.
                java.io.File reconciled = storageManager.reconcileRecordingOverride(overrideDir);
                targetDir = (reconciled != null) ? reconciled : overrideDir;
                this.pendingOutputDirOverride = null;
                if (targetDir == overrideDir || targetDir.getAbsolutePath().equals(overrideDir.getAbsolutePath())) {
                    logger.info("Recorder using watchdog-supplied output dir override: "
                        + overrideDir.getAbsolutePath());
                } else {
                    logger.info("Recorder override reconciled to live storage dir: "
                        + targetDir.getAbsolutePath() + " (was " + overrideDir.getAbsolutePath() + ")");
                }
            } else {
                targetDir = storageManager.getRecordingsDir();
            }

            // ENOSPC fallback (false-GREEN fix): if the resolved target lives on
            // a configured EXTERNAL volume that is mounted-but-FULL, redirect
            // THIS segment to internal so the recording is saved rather than
            // ENOSPC-aborting into a quarantined .broken clip. One-directional
            // (external→internal only) and start-of-segment only, so it can't
            // ping-pong a live session. No-op when target is already internal,
            // has room, or is genuinely unmounted (left to the unmount path).
            java.io.File enospcSafe = storageManager.resolveTargetWithEnospcFallback(
                targetDir, 100 * 1024 * 1024, true /* trackState — primary path drives the UI banner */);
            if (enospcSafe != null && enospcSafe != targetDir) {
                targetDir = enospcSafe;
            }

            // Ensure directory exists
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            // Reserve ~100MB for new recording. Time it explicitly — a slow
            // call here means the storage cleanup lock is contended (boot
            // reap walking a large external volume, periodic cleanup running
            // a deletion burst, etc.) and the user's recording is being held
            // up by housekeeping.
            //
            // Pass `targetDir` explicitly so the pre-flight reserve operates
            // on the SAME directory we're about to write into. Without this,
            // a concurrent storage-type switch (HTTP setRecordingsStorageType)
            // could swap recordingsDir between our targetDir read above and
            // ensureRecordingsSpace's internal re-read, leaving the reserve
            // pointed at the new volume while the file lands on the old.
            // Use the recorder-critical-path variant: it starts immediately when
            // the volume has enough PHYSICAL free space (a ~200µs StatFs probe,
            // no lock, no shell fork) and hands category-limit retention to the
            // async cleanup executor. The full lock-held reap-to-limit only runs
            // when the disk is genuinely short on space. This is the fix for the
            // "recording doesn't start until ~9-10 min after ACC ON" stall, where
            // this pre-flight blocked behind the boot reap on recordingsCleanupLock.
            long reserveStartNs = System.nanoTime();
            storageManager.ensureRecordingsSpaceForRecorder(100 * 1024 * 1024, targetDir);
            long reserveMs = (System.nanoTime() - reserveStartNs) / 1_000_000L;
            if (reserveMs > 1_000) {
                logger.warn("Recorder pre-flight ensureRecordingsSpace took " + reserveMs
                    + "ms — storage cleanup is starving the recording start path");
            } else if (reserveMs > 100) {
                logger.info("Recorder pre-flight ensureRecordingsSpace: " + reserveMs + "ms");
            }

            // Generate filename with timestamp
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String filename = prefix + "_" + timestamp + ".mp4";

            // Use target directory
            String outputPath = new java.io.File(targetDir, filename).getAbsolutePath();
            // DIAGNOSTIC (root-cause confirm for "REC green, no file"): log the
            // FINAL resolved target + its free space + configured-vs-active
            // storage type at each segment open. With the user-reported trigger
            // being "varies", this single line ties a missing clip to its exact
            // cause on-car — ENOSPC (free near 0), internal fallback fired
            // (path under /storage/emulated/0 while type=SD_CARD/USB), or a
            // healthy external write — without needing a repro.
            try {
                long freeBytes = -1L;
                try {
                    java.io.File probe = targetDir.exists() ? targetDir : targetDir.getParentFile();
                    if (probe != null && probe.exists()) {
                        freeBytes = new android.os.StatFs(probe.getAbsolutePath()).getAvailableBytes();
                    }
                } catch (Throwable ignore) { /* unmounted — leave -1 */ }
                logger.info("Recording start: target=" + targetDir.getAbsolutePath()
                    + " free=" + (freeBytes < 0 ? "unknown(unmounted?)" : (freeBytes / (1024 * 1024)) + "MB")
                    + " configuredType=" + storageManager.getRecordingsStorageType()
                    + " activeType=" + storageManager.getActiveRecordingsStorageType()
                    + " prefix=" + prefix);
            } catch (Throwable ignore) { /* diagnostics must never block a start */ }
            startRecording(outputPath);
        } catch (Exception e) {
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            logger.error("Failed to start recording after " + ms + "ms: " + e.getMessage());
            // Fallback to legacy path
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String filename = (prefix != null ? prefix : "cam") + "_" + timestamp + ".mp4";
            startRecording("/storage/emulated/0/Android/data/com.overdrive.app/files/" + filename);
        }
    }
    
    /**
     * Stops recording.
     *
     * <p>LOCK (audit R11-1 / ExtD-2): the whole body runs under
     * {@code recordingLock} — the SAME lock the start paths hold. Both stop
     * paths used to flip {@code recording=false} and close the encoder
     * lock-free; a concurrent {@code triggerEventRecordingExclusive} could
     * then pass its {@code recording==false} check, win the encoder's
     * {@code startStopLock} BEFORE the stop's close, hit the encoder's
     * "already writing → return true" no-op leg, and report a false STARTED
     * for a session that never got a muxer (the stop then closed the
     * encoder): one event silently unrecorded behind a success signal.
     * Serializing stops with starts makes the flag flip + encoder close
     * atomic against a start — a start now sees either recording==true
     * (ALREADY_RECORDING) or a fully-closed encoder (genuine open). Lock
     * order recordingLock → startStopLock matches the start paths; the
     * encoder's callbacks (fileClosedCallback / writer-abort) never take
     * recordingLock, so no inversion. Holding the lock across the encoder
     * close (drainer join, ≤2s finalizer wait) intentionally blocks a
     * racing start for the duration — that start would have been a false
     * STARTED before; now it waits and opens against clean state.
     */
    public void stopRecording() {
        if (!recording) {
            return;
        }
        synchronized (recordingLock) {
            if (!recording) {
                return; // lost the race to another stop — nothing to do
            }
            recording = false;

            // SOTA: Notify StorageManager that recording is inactive
            try {
                com.overdrive.app.storage.StorageManager.getInstance().setRecordingActive(false);
            } catch (Exception e) {
                logger.warn("Could not set recording inactive state: " + e.getMessage());
            }

            // Stop encoder recording
            if (encoder != null) {
                encoder.stopRecording();
            }
        }
    }
    
    /**
     * Stops recording with post-record support.
     * 
     * @param immediate If true, stops immediately. If false, uses post-record.
     */
    public void stopEventRecording(boolean immediate, long postRecordDurationMs) {
        if (!recording) {
            return;
        }
        // LOCK (audit R11-1 / ExtD-2): serialized with the start paths —
        // see stopRecording()'s doc for the false-STARTED interleave this
        // closes and the lock-order/deadlock analysis.
        synchronized (recordingLock) {
            if (!recording) {
                return; // lost the race to another stop — nothing to do
            }
            recording = false;

            // SOTA: Notify StorageManager that recording is inactive
            try {
                com.overdrive.app.storage.StorageManager.getInstance().setRecordingActive(false);
            } catch (Exception e) {
                logger.warn("Could not set recording inactive state: " + e.getMessage());
            }

            // Stop encoder recording
            if (encoder != null) {
                encoder.stopEventRecording(immediate, postRecordDurationMs);
            }

            logger.info(String.format("Recording stopped. Total frames: %d", frameCount));
        }
    }
    
    /**
     * Checks if currently recording.
     *
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recording;
    }

    /**
     * Sets the output directory to use for the NEXT segment / recording
     * session. Storage watchdog calls this after a hot SD/USB remount so
     * that future {@link #startRecording(java.io.File, String)} calls land
     * on the freshly-mounted volume rather than the stale path captured
     * at the original start.
     *
     * <p>Does NOT affect the current in-flight segment — the underlying
     * encoder's {@code segmentBasePath} is fixed at the segment's open;
     * mid-segment volume change cannot be hot-patched without a stop
     * and re-start. The current segment's writes will continue to fail
     * silently against the vanished mount until the encoder rotates or
     * the recording is restarted.
     *
     * @param dir new recordings directory, or {@code null} to clear the
     *            override and fall back to {@code StorageManager.getRecordingsDir()}
     *            on the next start-recording call.
     */
    public void setOutputDir(java.io.File dir) {
        this.pendingOutputDirOverride = dir;
        logger.info("Recorder output dir override set to "
            + (dir != null ? dir.getAbsolutePath() : "(null/default)")
            + " — applies on next startRecording()");
    }
    
    /**
     * Gets the timestamp of the last rendered frame.
     * 
     * @return Timestamp in milliseconds
     */
    public long getLastFrameTime() {
        return lastFrameTime;
    }
    
    /**
     * Gets the total number of frames rendered.
     * 
     * @return Frame count
     */
    public long getFrameCount() {
        return frameCount;
    }
    
    /**
     * Gets the encoder instance.
     * 
     * @return Hardware encoder
     */
    public HardwareEventRecorderGpu getEncoder() {
        return encoder;
    }
    
    public void setOverlayEnabled(boolean enabled) {
        this.overlayEnabled = enabled;
        reconcileOverlayWorker();
    }

    /**
     * Start/stop the overlay raster worker to match the active overlay state.
     * The worker rasters the overlay bitmap off the GL thread; it should run
     * exactly when the overlay would actually be composited (enabled + in a
     * recording mode that shows it + a telemetry source to read). Idempotent —
     * OverlayBitmapRenderer.start/stopWorker dedupe. Called from every input
     * that changes those conditions (setOverlayEnabled / setOverlayRecordingModeAllowed
     * / setTelemetryCollector), all of which may run off the GL thread.
     */
    private void reconcileOverlayWorker() {
        OverlayBitmapRenderer r = overlayRenderer;
        if (r == null) return;
        // Keep the renderer's field selection current whenever the worker exists.
        r.setActiveFields(overlayFields);
        boolean active = overlayEnabled && overlayRecordingModeAllowed && telemetryCollector != null;
        if (active) {
            // Report which overlay-only signals this flow actually draws, so the
            // collector can skip the reflective turn-signal / seatbelt reads when
            // unused. Reported only while the overlay is genuinely compositing.
            telemetryCollector.setOverlayFieldDemand(
                overlayDemandKey,
                overlayFields.has(com.overdrive.app.telemetry.TelemetryFields.Field.TURN_SIGNALS),
                overlayFields.hasAny(
                    com.overdrive.app.telemetry.TelemetryFields.Field.SEATBELT_DRIVER,
                    com.overdrive.app.telemetry.TelemetryFields.Field.SEATBELT_PASSENGER));
            // Beams come from BydDataCollector's 5 s poll (NOT the telemetry
            // collector), and that poll is otherwise automation-gated — without
            // this the beam glyphs stay frozen at their boot state.
            com.overdrive.app.byd.BydDataCollector.setOverlayBeamDemand(
                overlayDemandKey,
                overlayFields.hasAny(
                    com.overdrive.app.telemetry.TelemetryFields.Field.LOW_BEAM,
                    com.overdrive.app.telemetry.TelemetryFields.Field.HIGH_BEAM));
            r.startWorker(telemetryCollector);
        } else {
            if (telemetryCollector != null) {
                telemetryCollector.clearOverlayFieldDemand(overlayDemandKey);
            }
            com.overdrive.app.byd.BydDataCollector.setOverlayBeamDemand(overlayDemandKey, false);
            r.stopWorker();
        }
    }

    /**
     * Set the field selection this recorder's overlay draws (per recording
     * flow). {@code null} resets to the legacy default. Applied to the renderer
     * immediately and re-reported as field demand on the next reconcile. Safe
     * off-GL-thread (renderer publishes via volatile; reconcile is idempotent).
     */
    public void setOverlayFields(com.overdrive.app.telemetry.TelemetryFields fields) {
        this.overlayFields = (fields != null)
            ? fields
            : com.overdrive.app.telemetry.TelemetryFields.legacyDefault();
        reconcileOverlayWorker();
    }

    /** Identify this recorder's overlay-demand key on the shared collector. */
    public void setOverlayDemandKey(String key) {
        if (key != null) this.overlayDemandKey = key;
    }

    /**
     * Select the recording composition layout for the 4-camera 360 source.
     * 0 = standard 2x2 mosaic (default). 1 = dashcam: the 360 front-camera
     * slice fills the top band, with the 360 left/rear/right slices tiled
     * across the bottom band. Only the 4-camera 360 path honours this — the
     * APA / 3-camera / DiLink4 shader branches take precedence. The actual
     * glUniform1f is deferred to the next drawFrame on the encoder GL thread
     * (we don't own that context here), matching the uApaMode pattern.
     */
    public void setRecordingLayout(int layout) {
        int v = (layout == 1) ? 1 : 0;
        if (v == this.recordLayout) return;
        this.recordLayout = v;
        this.recordLayoutUniformDirty.set(true);
    }

    /**
     * Wires the HAL-contention probe used by the safety valve. The valve only
     * skips frames when this returns true (i.e., the BYD native AVM app is
     * actively sharing the camera and could lose its preview signal). When
     * unset (or set to a probe that always returns false), the valve is inert
     * and eglSwapBuffers handles backpressure natively.
     */
    public void setHalContentionProbe(java.util.function.BooleanSupplier probe) {
        this.halContentionProbe = probe != null ? probe : () -> false;
    }
    
    /**
     * Sets the camera layout mode for the mosaic shader.
     * 0 = 4-camera mosaic (Seal: pano_h/pano_l, surfaceMode=0)
     * 1 = full-frame APA passthrough (firmware-default preview port 0)
     * 2 = 3-camera mosaic (Atto 3 default: Rear, Side, Front)
     * 3 = DiLink 4 four-corner producer remap.
     */
    public void setCameraLayout(int layout) {
        if (layout == this.cameraLayout) return;
        this.apaMode = (layout == 1);
        this.cameraLayout = layout;
        // Defer the actual glUniform1f to the next drawFrame on the
        // encoder GL thread; we don't own that context here.
        this.apaModeUniformDirty.set(true);
        // Also flag the broader quasi-static set: uApplyManualYFlip
        // depends on cameraLayout==3.
        this.uniformsDirty.set(true);
        String[] names = {
            "4-camera mosaic", "APA passthrough", "3-camera mosaic",
            "oem-parity passthrough"
        };
        logger.info("Camera layout: " + (layout < names.length ? names[layout] : "unknown(" + layout + ")"));
    }

    /**
     * Publishes the SurfaceTexture transform matrix the shader will use on
     * the next drawFrame. Called from the GL thread inside the camera's
     * consume function — same thread as drawFrame, so a plain copy is safe
     * (no synchronization needed). Identity is retained until the first
     * call.
     */
    public void setTextureMatrix(float[] matrix4x4) {
        if (matrix4x4 == null || matrix4x4.length < 16) return;
        System.arraycopy(matrix4x4, 0, currentTexMatrix, 0, 16);
    }

    /**
     * Per-role X/Y flip flags on the DiLink 4 passthrough path. Each
     * float[] is {xFlip, yFlip}; values >= 0.5 flip that axis within the
     * role's 0.5×0.5 producer corner. Pass null/short arrays to leave
     * a role's flags unchanged.
     */
    public void setFlipFlags(float[] front, float[] right,
                             float[] rear, float[] left) {
        synchronized (producerCornerMapLock) {
            if (front != null && front.length >= 2) {
                flipFlags[0] = front[0]; flipFlags[1] = front[1];
            }
            if (right != null && right.length >= 2) {
                flipFlags[2] = right[0]; flipFlags[3] = right[1];
            }
            if (rear != null && rear.length >= 2) {
                flipFlags[4] = rear[0]; flipFlags[5] = rear[1];
            }
            if (left != null && left.length >= 2) {
                flipFlags[6] = left[0]; flipFlags[7] = left[1];
            }
            // Set dirty INSIDE the lock so the field-write happens-before
            // the unlock; otherwise drawFrame can observe dirty=false
            // between the unlock and the set, skipping the upload for
            // one frame (one-frame staleness on DiLink 4 mosaic flips).
            uniformsDirty.set(true);
        }
    }

    /**
     * Atomic combined setter for producer corners + flip flags.
     * Prefer this over the split pair to avoid a drawFrame landing
     * between the two writes and rendering one frame with mismatched
     * pair on DiLink 4. Single lock, single dirty flip.
     */
    public void setProducerLayout(float[] frontC, float[] rightC,
                                  float[] rearC, float[] leftC,
                                  float[] frontF, float[] rightF,
                                  float[] rearF, float[] leftF) {
        synchronized (producerCornerMapLock) {
            if (frontC != null && frontC.length >= 2) {
                producerCornerMap[0] = frontC[0]; producerCornerMap[1] = frontC[1];
            }
            if (rightC != null && rightC.length >= 2) {
                producerCornerMap[2] = rightC[0]; producerCornerMap[3] = rightC[1];
            }
            if (rearC != null && rearC.length >= 2) {
                producerCornerMap[4] = rearC[0]; producerCornerMap[5] = rearC[1];
            }
            if (leftC != null && leftC.length >= 2) {
                producerCornerMap[6] = leftC[0]; producerCornerMap[7] = leftC[1];
            }
            if (frontF != null && frontF.length >= 2) {
                flipFlags[0] = frontF[0]; flipFlags[1] = frontF[1];
            }
            if (rightF != null && rightF.length >= 2) {
                flipFlags[2] = rightF[0]; flipFlags[3] = rightF[1];
            }
            if (rearF != null && rearF.length >= 2) {
                flipFlags[4] = rearF[0]; flipFlags[5] = rearF[1];
            }
            if (leftF != null && leftF.length >= 2) {
                flipFlags[6] = leftF[0]; flipFlags[7] = leftF[1];
            }
            uniformsDirty.set(true);
        }
    }
    public float[] getFlipFlags() {
        synchronized (producerCornerMapLock) {
            return flipFlags.clone();
        }
    }

    /** Toggle the red-overlay mask filter. When enabled, the recorder's
     *  fragment shader replaces saturated-red pixels (HAL 'calibration
     *  failed' chrome) with a small-offset neighbour sample so they
     *  don't appear in recordings. Cosmetic only — doesn't fix the
     *  underlying calibration. Off by default. */
    /**
     * Sets the APA center inset (oem APACropFilter parity).
     *
     * <p><b>What it actually does — read this before tuning it.</b> The shader
     * applies a GLOBAL remap of the final sample x, AFTER the per-role corner
     * offset has been added ({@code samplePos = producerCorner + sampledLocal},
     * then {@code APA_CENTER_INSET_GLSL} maps {@code [0,1] -> [inset, 1-inset]}).
     * That is an OUTER-EDGE trim of the whole producer, NOT a per-role trim:
     * x=0 maps to {@code inset}, x=1 maps to {@code 1-inset}, and x=0.5 — the
     * inner 2x2 seam where the four roles meet — is an exact FIXED POINT and is
     * left completely untouched.
     *
     * <p>An earlier version of this javadoc claimed the opposite ("applied to
     * each role's local [0,0.5] window … trimming each role inward eliminates
     * the red bars"). That was wrong, and it matters: if chrome genuinely lives
     * on the centre seams, this control cannot remove it at any value. It only
     * trims the outermost left/right columns of the mosaic.
     *
     * <p>OEM comparison ({@code ml/b.java:170-176}, {@code el/a.java:313}): the
     * reference app's {@code APACropFilter.h(150, 0)} is also a whole-surface
     * outer-edge crop, computed as genuine float division — verified in smali
     * ({@code int-to-float} on both operands, then {@code div-float}), so it is
     * NOT the integer-division no-op it can look like in decompiled Java. At its
     * surface width of 1920 that is {@code 150/1920 = 0.078}. Note it lives in
     * the RTMP publisher chain; both OEM recorder lanes ({@code el/b.java:293},
     * {@code el/i.java:282}) discard the byd_apa discriminator and add overlay
     * only, i.e. they apply no crop at all.
     *
     * <p>Our default is {@code 240/2560 = 0.09375}, which has no OEM basis — the
     * OEM constant is 150, not 240. Left as-is rather than retuned because the
     * two lanes are not comparable (different surface dims, and the OEM's crop
     * sits after a 90° pre-rotation so its "horizontal" is our vertical), and
     * because no device frame has yet been captured to show what the trim is
     * actually doing on a real car. Tune it from a device frame, not from this
     * arithmetic.
     *
     * <p>Default 0 = no crop (legacy and unconfigured paths bit-exact).
     * Clamped to [0, 0.20] so the visible window never collapses to zero.
     */
    public void setApaCenterInset(float inset) {
        float clamped = Math.max(0.0f, Math.min(0.20f, inset));
        if (Float.compare(clamped, this.apaCenterInset) == 0) return;
        this.apaCenterInset = clamped;
        this.uniformsDirty.set(true);
    }

    public void setRedMaskEnabled(boolean enabled) {
        if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            enabled = false;
        }
        if (enabled == this.redMaskEnabled) return;
        this.redMaskEnabled = enabled;
        this.uniformsDirty.set(true);
    }
    public boolean isRedMaskEnabled() { return redMaskEnabled; }

    /**
     * Sets the recording-side dewarp strength.
     *
     * <p>Maps a UI slider value in [0, 100] to a pair of division-model
     * coefficients (k1, k2) that drive the shader formula
     * {@code r_source = r_output / (1 + k1·r² + k2·r⁴)}. The single slider
     * scales both linearly in a fixed 3:1 ratio:
     * <pre>
     *   k1 = 0.30 * slider/100   // primary curvature (uniform across radius)
     *   k2 = 0.10 * slider/100   // 4th-order term (boosts corner correction)
     * </pre>
     *
     * <p>Why two parameters: the single-parameter Fitzgibbon model runs out
     * of corner-correction power at high strengths — the centre keeps
     * stretching but corners stop straightening proportionally. Adding the
     * r⁴ term grows 16× faster at the corner relative to the centre
     * (compared to r²'s 4×), so it disproportionately corrects the corner
     * region without affecting central pixels. Same family OpenCV's
     * {@code cv::fisheye} uses, just with 2 coeffs instead of 4.
     *
     * <p>Sign convention: positive k1, k2 means "for each rectified-output
     * pixel, sample a SMALLER radius in the source fisheye image" — i.e.
     * the output's corners pull in from the source's centre, which is the
     * geometric definition of barrel-removal. Combined with a zoom-to-fill
     * factor in the shader, this produces the crop-and-stretch look:
     * outer ring of the source is cropped, inner content stretches to
     * fill the tile, lines that were curving at the corners come out
     * straighter.
     *
     * <p>0 is identity (bit-exact to the legacy passthrough). 100 is
     * maximum dewarp at ~32% peripheral crop. Values clamped to [0, 100].
     *
     * <p>Only effective on the legacy 4-strip layout (uApaMode==0). DiLink 4
     * (uApaMode==3), 3-cam (uApaMode==2), and APA (uApaMode==1) paths are
     * unaffected — the shader's other branches read whatever sample position
     * they computed without consulting the rectify uniforms. Callers may
     * freely write non-zero values regardless of layout; the GPU cost is
     * identical.
     *
     * <p>Default: 0 (off). Persisted in
     * {@code UnifiedConfigManager.recording.rectifyStrength}; the same key is
     * read by both ACC-on (recording) and ACC-off (surveillance) pipelines so
     * one slider drives both flows.
     *
     * @param strength 0..100; clamped if outside.
     */
    public void setRectifyStrength(float strength) {
        float clamped = Math.max(0f, Math.min(100f, strength));
        float t = clamped / 100f;
        // 3:1 split favours the primary term so mid-slider values stay
        // visually similar to the prior single-parameter behaviour; the
        // r⁴ term only becomes dominant near the corners at high slider
        // values, which is exactly where the previous model under-
        // corrected. Combined denominator at the corner (r²=2): up to
        // 1 + 0.30·2 + 0.10·4 = 2.0 at slider==100 — a 50% radial pull-in
        // before zoom-to-fill, vs 40% under the old single-parameter
        // ceiling. Same maximum periphery crop (~32%) but with smoother
        // corner-region geometry.
        float k1 = 0.30f * t;
        float k2 = 0.10f * t;
        if (Float.compare(k1, this.rectifyK1) == 0
                && Float.compare(k2, this.rectifyK2) == 0) return;
        this.rectifyK1 = k1;
        this.rectifyK2 = k2;
        this.uniformsDirty.set(true);
    }

    /** Current primary dewarp coefficient (0.0 = off). */
    public float getRectifyK1() { return rectifyK1; }
    /** Current 4th-order dewarp coefficient (0.0 = off). */
    public float getRectifyK2() { return rectifyK2; }

    /**
     * Sets the per-cam tile aspect ratio (tile_height / tile_width) used
     * by the dewarp shader to compute true pixel-space radial distance.
     *
     * <p>Pipeline pushes this from the active camera profile at init:
     * Seal/Atto = 1280×960 → 0.75; Tang = 1280×720 → 0.5625. Default 0.75.
     * Identity-equivalent at any aspect when k1 = k2 = 0, so a wrong
     * aspect at slider 0 produces zero visual difference. Clamped to a
     * sane range [0.25, 1.0] to keep the inverse-aspect math stable.
     */
    public void setRectifyAspect(float aspect) {
        float clamped = Math.max(0.25f, Math.min(1.0f, aspect));
        if (Float.compare(clamped, this.rectifyAspect) == 0) return;
        this.rectifyAspect = clamped;
        this.uniformsDirty.set(true);
    }
    public float getRectifyAspect() { return rectifyAspect; }

    /**
     * Per-output-corner producer-corner remap for the DiLink 4 passthrough
     * path. Each role (Front, Right, Rear, Left) reads from one of four
     * 0.5×0.5 producer corners: TL=(0,0), TR=(0.5,0), BL=(0,0.5),
     * BR=(0.5,0.5). Pass identity values to disable the remap; pass a
     * permutation to fix HAL layouts that paint roles in different
     * corners than our default.
     *
     * Each parameter is a {x, y} pair; null/short arrays are ignored
     * for that role only (other roles still update).
     */
    public void setProducerCornerMap(float[] front, float[] right,
                                     float[] rear, float[] left) {
        synchronized (producerCornerMapLock) {
            if (front != null && front.length >= 2) {
                producerCornerMap[0] = front[0]; producerCornerMap[1] = front[1];
            }
            if (right != null && right.length >= 2) {
                producerCornerMap[2] = right[0]; producerCornerMap[3] = right[1];
            }
            if (rear != null && rear.length >= 2) {
                producerCornerMap[4] = rear[0]; producerCornerMap[5] = rear[1];
            }
            if (left != null && left.length >= 2) {
                producerCornerMap[6] = left[0]; producerCornerMap[7] = left[1];
            }
            uniformsDirty.set(true);
        }
    }

    /** Returns a copy of the current producer-corner map (8 floats:
     *  fX,fY, rX,rY, bX,bY, lX,lY). */
    public float[] getProducerCornerMap() {
        synchronized (producerCornerMapLock) {
            return producerCornerMap.clone();
        }
    }
    
    public void setApaMode(boolean apa) {
        setCameraLayout(apa ? 1 : 0);
    }
    
    public boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public void setOverlayRecordingModeAllowed(boolean allowed) {
        this.overlayRecordingModeAllowed = allowed;
        reconcileOverlayWorker();
    }

    public void setTelemetryCollector(TelemetryDataCollector collector) {
        this.telemetryCollector = collector;
        reconcileOverlayWorker();
    }

    /**
     * Registers a listener invoked whenever a recording segment file finishes
     * closing (segment rotation or final stop). Used by GpuSurveillancePipeline
     * to stamp lastSegmentRotateMs so RecordingModeManager's wedge ticker can
     * grace-window the natural isRecording()=false flicker between segments.
     */
    public void setSegmentRotatedListener(Runnable listener) {
        this.segmentRotatedListener = listener;
    }

    /**
     * Returns true if the encoder surface has died and needs reinitialization.
     * Called by PanoramicCameraGpu to trigger encoder recovery.
     */
    public boolean needsReinit() {
        return needsReinit;
    }

    /**
     * Clears the reinit flag after recovery is complete.
     */
    public void clearReinitFlag() {
        needsReinit = false;
        consecutiveSurfaceErrors = 0;
    }
    
    /**
     * SOTA: Releases only the encoder surface without releasing other resources.
     * Called before encoder reinitialization to prevent EGL_BAD_SURFACE errors.
     * The surface will be recreated when init() is called with the new encoder.
     */
    public void releaseEncoderSurface() {
        if (encoderSurface != null && eglCore != null) {
            eglCore.destroySurface(encoderSurface);
            encoderSurface = null;
            encoderInputSurface = null;
            logger.info("Released encoder surface for reinitialization");
        }
    }
    
    /**
     * Releases all resources.
     */
    public void release() {
        recording = false;
        
        if (programId != 0) {
            GlUtil.deleteProgram(programId);
            programId = 0;
        }
        
        // Release overlay resources
        if (overlayProgramId != 0) {
            GLES20.glDeleteProgram(overlayProgramId);
            overlayProgramId = 0;
        }
        if (overlayTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{overlayTextureId}, 0);
            overlayTextureId = 0;
        }
        if (overlayRenderer != null) {
            // CRITICAL ordering: stop+join the raster worker BEFORE recycling
            // the bitmaps, so the worker can never draw into a recycled bitmap
            // (use-after-free). stopWorker() joins; release() then recycles.
            overlayRenderer.stopWorker();
            overlayRenderer.release();
            overlayRenderer = null;
        }
        overlayTextureReady = false;
        overlayTextureInitialized = false;
        
        if (encoderSurface != null) {
            eglCore.destroySurface(encoderSurface);
            encoderSurface = null;
        }
        
        logger.info( "GpuMosaicRecorder released");
    }

    private static float[] normalizeOffsets(float[] quadrantStripOffsetX) {
        if (quadrantStripOffsetX == null || quadrantStripOffsetX.length != 4) {
            return DEFAULT_QUADRANT_STRIP_OFFSET_X.clone();
        }
        return quadrantStripOffsetX.clone();
    }

    /**
     * Build the mosaic fragment shader with the four per-quadrant strip-X
     * offsets baked in as GLSL constants. Order: {Front, Right, Rear, Left}
     * → {TL, TR, BL, BR}. 3-cam (uApaMode > 1.5) and APA (uApaMode > 0.5)
     * branches are layout-independent and stay as-is.
     */
    private static String buildFragmentShader(float[] offsets, float windshieldCropY) {
        // uApaMode branches:
        //   0.0  4-camera mosaic: sample 4 quadrants of a 5120x960 horizontal
        //        strip and rearrange into 2x2 corners (legacy Seal layout).
        //   1.0  APA passthrough: sample camera surface as-is.
        //   2.0  3-camera mosaic (Atto 3): rear=left half, front=top-right,
        //        left+right=bottom-right.
        //   3.0  DiLink 4 four-corner producer remap.
        return String.format(Locale.US,
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uCameraTex;\n" +
            "uniform samplerExternalOES uWindshieldTex;\n" +
            "uniform float uWindshieldReady;\n" +
            "uniform float uApaMode;\n" +
            "uniform vec2 uProducerForFront;\n" +
            "uniform vec2 uProducerForRight;\n" +
            "uniform vec2 uProducerForRear;\n" +
            "uniform vec2 uProducerForLeft;\n" +
            // Per-role flip flags (xFlip, yFlip). 1.0 = flip that axis
            // within the role's local 0.5×0.5 producer corner. Used to
            // un-mirror cameras whose HAL output is flipped relative to
            // the canonical Y-down image convention.
            "uniform vec2 uFlipForFront;\n" +
            "uniform vec2 uFlipForRight;\n" +
            "uniform vec2 uFlipForRear;\n" +
            "uniform vec2 uFlipForLeft;\n" +
            // Red-overlay mask strength. 0.0 = pass through, 1.0 = active.
            // Used to suppress the AVM HAL's 'calibration failed' chrome
            // on uncalibrated cars where the dealer hasn't calibrated yet.
            "uniform float uRedMaskStrength;\n" +
            "uniform float uApaCenterInset;\n" +
            // Two-parameter division-model dewarp coefficients. Identity at
            // (0, 0); positive values straighten residual barrel curvature
            // in the legacy 4-strip BYD HAL output. k1 is the primary r²
            // term; k2 is the r⁴ corner-boost term. Only sampled in the
            // uApaMode==0 branch — see comment near the dewarp block.
            "uniform float uRectifyK1;\n" +
            "uniform float uRectifyK2;\n" +
            // Per-cam tile aspect ratio (height / width). 0.75 on Seal
            // (1280×960), 0.5625 on Tang (1280×720). Used inside the
            // dewarp block so the radial distance is computed in true
            // pixel space — without this multiply the iso-distortion
            // contour is elliptical (vertical content corrected less
            // than horizontal content of the same physical length).
            // Identity-equivalent at any aspect when k1 = k2 = 0.
            "uniform float uRectifyAspect;\n" +
            "uniform float uRecordLayout;\n" +
            "varying vec2 vTexCoord;\n" +
            // NOTE: the previous revision of this file had a 4-tap
            // Catmull-Rom bicubic sampler here, gated on uRectifyK1 > 0.
            // It saturated the Adreno 610 command queue at 2560×1920 and
            // pushed the encoder eglSwap into 65-80ms territory; the
            // shared-EGL-group AiLaneGl pipeline's glClientWaitSync then
            // crashed on KGSL-reaped fence handles. Reverted to the
            // single-tap bilinear (the OES sampler's hardware filter)
            // which has been stable for the entire pre-fisheye history.
            // The 2-parameter division-model dewarp + aspect correction
            // are kept; bicubic was a marginal sharpness gain that's not
            // worth the GPU pressure.
            // Shared lens dewarp — the "un-fisheye" setting
            // (recording.rectifyStrength → uRectifyK1/K2). `t` is a per-camera
            // tile coord in 0..1; returns the dewarped sample coord in 0..1
            // (clamped). Identity when k1 = k2 = 0 (setting off). The dashcam
            // 360 slices call this so they honour the setting exactly like the
            // standard 2x2 branch. NOTE: this mirrors the inline dewarp in the
            // standard branch below — kept as a separate copy so this (new,
            // on-device-unverified) helper can't regress the proven standard
            // path; unify once verified on-device.
            "vec2 rectifyTile(vec2 t) {\n" +
            "    vec2 nxy = t * 2.0 - 1.0;\n" +
            "    vec2 nxyAspect = vec2(nxy.x, nxy.y * uRectifyAspect);\n" +
            "    float r2 = dot(nxyAspect, nxyAspect);\n" +
            "    float r4 = r2 * r2;\n" +
            "    float invDenom = 1.0 / (1.0 + uRectifyK1 * r2 + uRectifyK2 * r4);\n" +
            "    float aspect2 = uRectifyAspect * uRectifyAspect;\n" +
            "    float aspect4 = aspect2 * aspect2;\n" +
            "    float zoom = 1.0 + uRectifyK1 * aspect2 + uRectifyK2 * aspect4;\n" +
            "    vec2 srcAspect = (nxyAspect * invDenom) * zoom;\n" +
            "    vec2 srcTile = vec2(srcAspect.x, srcAspect.y / uRectifyAspect);\n" +
            "    vec2 camUV = srcTile * 0.5 + 0.5;\n" +
            "    return clamp(camUV, vec2(0.0), vec2(1.0));\n" +
            "}\n" +
            "void main() {\n" +
            "    vec2 samplePos;\n" +
            "    float frontOffset = %.5f;\n" +
            "    float rightOffset = %.5f;\n" +
            "    float rearOffset  = %.5f;\n" +
            "    float leftOffset  = %.5f;\n" +
            "    if (uApaMode > 2.5) {\n" +
            "        // DiLink 4 per-output-corner producer remap + per-role\n" +
            "        // X/Y flip. Each of the four output\n" +
            "        // corners (Front=TL, Right=TR, Rear=BL, Left=BR) reads\n" +
            "        // a configurable producer corner; X/Y flips are applied\n" +
            "        // within the role's local 0.5×0.5 region to un-mirror\n" +
            "        // HAL outputs that have inverted axes per camera.\n" +
            "        bool inRight = (vTexCoord.x >= 0.5 && vTexCoord.y <  0.5);\n" +
            "        bool inRear  = (vTexCoord.x <  0.5 && vTexCoord.y >= 0.5);\n" +
            "        bool inLeft  = (vTexCoord.x >= 0.5 && vTexCoord.y >= 0.5);\n" +
            "        // Local coord inside this output's 0.5×0.5 corner.\n" +
            "        vec2 localOffset = vec2(0.0);\n" +
            "        if (inRight) localOffset = vec2(0.5, 0.0);\n" +
            "        else if (inRear) localOffset = vec2(0.0, 0.5);\n" +
            "        else if (inLeft) localOffset = vec2(0.5, 0.5);\n" +
            "        vec2 local = vTexCoord - localOffset;\n" +
            "        // Pick the producer corner + flip flags for this role.\n" +
            "        vec2 producerCorner = uProducerForFront;\n" +
            "        vec2 flip = uFlipForFront;\n" +
            "        if (inRight) { producerCorner = uProducerForRight; flip = uFlipForRight; }\n" +
            "        else if (inRear)  { producerCorner = uProducerForRear;  flip = uFlipForRear;  }\n" +
            "        else if (inLeft)  { producerCorner = uProducerForLeft;  flip = uFlipForLeft;  }\n" +
            "        // Apply X/Y flip within the local 0.5-wide window. flip.x>0.5\n" +
            "        // mirrors left-right; flip.y>0.5 mirrors top-bottom.\n" +
            "        vec2 sampledLocal = local;\n" +
            "        if (flip.x > 0.5) sampledLocal.x = 0.5 - sampledLocal.x;\n" +
            "        if (flip.y > 0.5) sampledLocal.y = 0.5 - sampledLocal.y;\n" +
            "        samplePos = producerCorner + sampledLocal;\n" +
            com.overdrive.app.camera.GlUtil.APA_CENTER_INSET_GLSL +
            "    } else if (uApaMode > 1.5) {\n" +
            "        if (vTexCoord.x < 0.5) {\n" +
            "            float lx = vTexCoord.x * 0.5;\n" +
            "            float ly = mod(vTexCoord.y, 0.5) * 2.0;\n" +
            "            if (vTexCoord.y < 0.5) { samplePos = vec2(lx + 0.75, ly); }\n" +
            "            else { samplePos = vec2(lx, ly); }\n" +
            "        } else {\n" +
            "            float lx = (vTexCoord.x - 0.5);\n" +
            "            samplePos = vec2(0.25 + lx * 0.5, vTexCoord.y);\n" +
            "        }\n" +
            "    } else if (uApaMode > 0.5) {\n" +
            "        // DiLink 5 1:1 direct camera stream: center-crop 1920x1300 source to 1920x1080 canvas (16:9)\n" +
            "        float cropY = 0.0846;\n" +
            "        samplePos = vec2(vTexCoord.x, cropY + vTexCoord.y * (1.0 - 2.0 * cropY));\n" +
            "    } else if (uRecordLayout > 0.5) {\n" +
            // Dashcam composition (4-camera 360 source only). The 360 front
            // slice fills the top `split` band at full width; the 360
            // left / rear / right slices tile the bottom band in thirds.
            // Reuses the same per-camera 0.25-wide producer columns as the
            // standard 2x2 branch (frontOffset / leftOffset / rearOffset /
            // rightOffset), just rearranged. The 360 slices run through
            // rectifyTile() so they honour the same lens-dewarp / "un-fisheye"
            // setting as the standard mosaic. The optional dedicated
            // windshield camera (top band) is rectilinear, so it is sampled
            // raw (not dewarped) when present; otherwise the 360 front slice
            // is the documented graceful fallback.
            "        float split = 0.70;\n" +
            "        if (vTexCoord.y < split) {\n" +
            "            float ly = vTexCoord.y / split;\n" +
            // Optional dedicated windshield camera for the top band. When a
            // windshield frame is bound (uWindshieldReady), sample it directly
            // (vertically cropped to the band aspect via cropY) and skip the
            // 360 front slice. Otherwise fall through to the 360 front slice.
            "            if (uWindshieldReady > 0.5) {\n" +
            "                float cropY = %.5f;\n" +
            "                vec2 wuv = vec2(vTexCoord.x, cropY + ly * (1.0 - cropY * 2.0));\n" +
            "                gl_FragColor = texture2D(uWindshieldTex, wuv);\n" +
            "                return;\n" +
            "            }\n" +
            "            vec2 camUV = rectifyTile(vec2(vTexCoord.x, ly));\n" +
            "            samplePos = vec2(frontOffset + camUV.x * 0.25, camUV.y);\n" +
            "        } else {\n" +
            "            float by = (vTexCoord.y - split) / (1.0 - split);\n" +
            "            float bx = vTexCoord.x * 3.0;\n" +
            "            float cell = floor(bx);\n" +
            "            float lx = bx - cell;\n" +
            "            float off = leftOffset;\n" +
            "            if (cell > 1.5) off = rightOffset;\n" +
            "            else if (cell > 0.5) off = rearOffset;\n" +
            "            vec2 camUV = rectifyTile(vec2(lx, by));\n" +
            "            samplePos = vec2(off + camUV.x * 0.25, camUV.y);\n" +
            "        }\n" +
            "    } else {\n" +
            "        vec2 gridPos = step(0.5, vTexCoord);\n" +
            "        float stripOffsetX;\n" +
            "        if (gridPos.x < 0.5) {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? frontOffset : rearOffset;\n" +
            "        } else {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? rightOffset : leftOffset;\n" +
            "        }\n" +
            "        float localX = mod(vTexCoord.x, 0.5) * 0.5;\n" +  // 0..0.25 in producer strip
            "        float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +  // 0..1
            // Two-parameter division-model dewarp:
            //     samplePos = outputPos / (1 + k1·r² + k2·r⁴)
            // Applied in per-CAMERA image space (treat the per-cam tile as
            // a square 0..1 region) so the radial geometry isn't
            // anisotropically squashed by the strip's 0.25-wide × 1.0-tall
            // aspect.
            //
            // Why two terms: r² alone runs out of corner-correction power
            // — at high strengths the centre keeps stretching but corners
            // stop straightening proportionally. r⁴ grows 4× faster than
            // r² between mid-radius and corner, so it disproportionately
            // pulls corner samples in without affecting central pixels.
            //
            // Zoom-to-fill: post-divide we multiply by (1 + k1 + k2),
            // which is exactly the corner denominator (r²=2 → 1+2k1+4k2,
            // wait — that's not quite right; we want the corners of the
            // rectified output to map to the corners of the source). The
            // zoom factor is set so the rectified image fills the tile
            // edge-to-edge instead of leaving a black ring. Trade-off:
            // some peripheral pixels are cropped out at high slider
            // values. Identity when k1 = k2 = 0 (zoom = 1, denom = 1, the
            // formula collapses to samplePos = outputPos).
            //
            // Final clamp keeps samplePos strictly inside the camera's
            // 0.25-wide column of the producer strip — a single-precision
            // jitter at r² near 0 cannot push us into the neighbouring
            // quadrant.
            "        vec2 nxy = vec2(localX / 0.25, localY) * 2.0 - 1.0;\n" + // -1..+1 in tile units
            // Aspect-correct radial: compute r² in true tile-pixel space
            // by squashing the y-axis by the tile aspect (height / width
            // < 1 for landscape). This keeps iso-distortion lines
            // circular in pixel space; without it vertical content is
            // under-corrected. Reuse `aspectY` for the inverse on the way
            // out so the final UV mapping back to the strip is consistent.
            "        vec2 nxyAspect = vec2(nxy.x, nxy.y * uRectifyAspect);\n" +
            "        float r2 = dot(nxyAspect, nxyAspect);\n" +
            "        float r4 = r2 * r2;\n" +
            "        float invDenom = 1.0 / (1.0 + uRectifyK1 * r2 + uRectifyK2 * r4);\n" +
            // Zoom factor: pick so the cardinal-axis edge midpoint of
            // the SHORTER axis (Y on a 4:3 landscape tile) maps exactly
            // to the source's Y edge. Using the longer-axis edge would
            // leave a black band on top/bottom; using the corner would
            // crop too aggressively. Shorter-axis fill is the standard
            // "fill" projection — outer X content is cropped, content
            // stretches to fill the full tile in both axes, no black
            // borders. The Y-edge midpoint is at nxyAspect = (0, aspect)
            // → r² = aspect², so the matching denominator is
            // 1 + k1·aspect² + k2·aspect⁴.
            "        float aspect2 = uRectifyAspect * uRectifyAspect;\n" +
            "        float aspect4 = aspect2 * aspect2;\n" +
            "        float zoom = 1.0 + uRectifyK1 * aspect2 + uRectifyK2 * aspect4;\n" +
            // Apply the dewarp in aspect-squashed space, then invert the
            // squash so the final UV is back in tile coords.
            "        vec2 srcAspect = (nxyAspect * invDenom) * zoom;\n" +
            "        vec2 srcTile = vec2(srcAspect.x, srcAspect.y / uRectifyAspect);\n" +
            "        vec2 camUV = srcTile * 0.5 + 0.5;\n" +
            "        camUV = clamp(camUV, vec2(0.0), vec2(1.0));\n" +
            "        samplePos = vec2(stripOffsetX + camUV.x * 0.25, camUV.y);\n" +
            "    }\n" +
            "    vec4 src = texture2D(uCameraTex, samplePos);\n" +
            // Shared red-overlay suppression. See GlUtil.RED_MASK_GLSL.
            com.overdrive.app.camera.GlUtil.RED_MASK_GLSL +
            "    gl_FragColor = src;\n" +
            "}\n",
            offsets[0], offsets[1], offsets[2], offsets[3], windshieldCropY);
    }

    /**
     * Vertical crop (0..0.45) to fit a {@code sourceAspect} (w/h) image into a
     * band of {@code topBandAspect} without horizontal stretch. When the band
     * is wider than the source we crop top + bottom symmetrically; when it
     * isn't, no crop (0). Used to fit the 16:9 windshield camera into the
     * dashcam top band.
     */
    private static float windshieldCropY(float sourceAspect, float topBandAspect) {
        if (sourceAspect <= 0.0f || topBandAspect <= 0.0f || topBandAspect <= sourceAspect) {
            return 0.0f;
        }
        float visibleHeight = sourceAspect / topBandAspect;
        return Math.max(0.0f, Math.min(0.45f, (1.0f - visibleHeight) * 0.5f));
    }
}
