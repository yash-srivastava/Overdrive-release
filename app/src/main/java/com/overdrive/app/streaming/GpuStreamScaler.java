package com.overdrive.app.streaming;

import android.opengl.GLES20;
import com.overdrive.app.camera.EGLCore;
import com.overdrive.app.camera.GlUtil;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import android.opengl.GLES11Ext;
import android.opengl.EGLSurface;
import android.view.Surface;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * GpuStreamScaler - GPU-based downscaler for H.264 streaming.
 * 
 * Renders camera texture to a smaller resolution for efficient streaming.
 * Works in parallel with GpuMosaicRecorder - both sample the same source texture.
 * 
 * Typical usage:
 * - Recording: 2560×1920 @ 15fps (high quality)
 * - Streaming: 1280×960 @ 10fps (bandwidth-optimized)
 * 
 * GPU cost: <1% (texture sampling is nearly free)
 */
public class GpuStreamScaler {
    private static final String TAG = "GpuStreamScaler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    // EGL and OpenGL state
    private EGLCore eglCore;
    private EGLSurface encoderSurface;
    private Surface encoderInputSurface;
    
    // OpenGL program and locations
    private int programId;
    private int uCameraTexLocation;
    private int uViewModeLocation;
    private int uBsRadiusLocation;
    private int uBsAspectLocation;
    private int uBsFeatherLocation;
    // Blind-spot merge mode (views 7/8): 0 = both (rear+side stitch), 1 = side
    // camera only, 2 = rear camera only. Single-camera modes fill the whole card
    // from one tap's full FOV (see buildFragmentShader view 7/8 branch).
    private int uBsMergeModeLocation = -1;
    private volatile int bsMergeMode = 0;
    // Rounded-corner card mask for the blind-spot views (7/8 only). Radius as a
    // fraction of the shorter half-axis (0 = square, ~0.12 = SOTA card corners).
    // Native SurfaceControl path composites the transparent corners directly.
    private volatile float bsCornerRadius = 0.14f;
    // ── 3D "curved glass card" framing (views 7/8 only) ──────────────────────
    // The video stays FLAT + uniform-scale (never distorted); only the EDGE reads
    // as 3D — a beveled rim that curves like a raised glass bezel, lit from the
    // top and shadowed at the bottom, with a thin specular highlight on the top
    // arc. No drop shadow (it read as a black band below the card on a dark map).
    // All driven by one analytic rounded-box SDF in the fragment shader (no
    // derivatives). A tiny margin keeps the rounded corners + AA inside the
    // 1280×960 buffer; the video fills essentially the whole card.
    private int uBsMarginLocation      = -1;
    private int uBsBevelWLocation      = -1;
    private int uBsBevelLightLocation  = -1;
    private int uBsBevelDarkLocation   = -1;
    private int uBsLightDirLocation    = -1;
    private int uBsSpecWLocation       = -1;
    private int uBsSpecIntLocation     = -1;
    private volatile float bsMargin     = 0.014f;  // tiny inset: AA + corner room, video ~97%
    private volatile float bsBevelW     = 0.045f;  // bevel band width — wider = rounder curve
    private volatile float bsBevelLight = 0.42f;   // top-rim brighten gain (glassy highlight)
    private volatile float bsBevelDark  = 0.36f;   // bottom-rim darken gain (curved-edge depth)
    private volatile float bsLightDirX  = 0.0f;    // light from straight above
    private volatile float bsLightDirY  = -1.0f;   // uv.y=0 is buffer top → -1 is "up"
    private volatile float bsSpecW      = 0.009f;  // specular band half-width
    private volatile float bsSpecInt    = 0.30f;   // specular add intensity
    // ── Fisheye / lens dewarp for the SINGLE-CAMERA blind-spot views (7/8) ────
    // Same two-parameter division-model dewarp the recorder uses
    // (GpuMosaicRecorder: r_source = r_output / (1 + k1·r² + k2·r⁴)), applied to
    // the raw quadrant sample in the merge-mode 1 (side) / 2 (rear) passthrough.
    // ONLY the single-camera views are dewarped — the merged 'both' view is left
    // to libod's stitch (uOd*), which already handles projection. Identity at
    // k1=k2=0 (feature off). uBsRectifyAspect = tile height/width so the radial
    // distance is measured in true pixel space (0.75 for a 4:3 quadrant).
    private volatile float bsRectifyK1     = 0.0f;
    private volatile float bsRectifyK2     = 0.0f;
    private volatile float bsRectifyAspect = 0.75f;
    private int uBsRectifyK1Location     = -1;
    private int uBsRectifyK2Location     = -1;
    private int uBsRectifyAspectLocation = -1;
    private int uApaModeLocation;
    private int uTexMatrixLocation;
    private int uApplyManualYFlipLocation;
    // Per-role producer corners + flip flags. Set per-frame from the
    // pipeline so DiLink 4's non-canonical layout (e.g. Variant A with
    // Right at producer BR, Rear at producer TR, etc.) routes correctly.
    private int uProducerForFrontLocation;
    private int uProducerForRightLocation;
    private int uProducerForRearLocation;
    private int uProducerForLeftLocation;
    private int uFlipForFrontLocation;
    private int uFlipForRightLocation;
    private int uFlipForRearLocation;
    private int uFlipForLeftLocation;
    // Red-overlay suppression strength: 0.0 = passthrough, 1.0 = active.
    // Mirrors GpuMosaicRecorder's uRedMaskStrength so the live stream is
    // free of HAL "calibration failed" chrome on uncalibrated DiLink 4 cars.
    private int uRedMaskStrengthLocation = -1;
    private int uApaCenterInsetLocation = -1;
    private volatile float apaCenterInset = 0.0f;
    private volatile boolean redMaskEnabled = false;
    // View 7/8 sampler coefficients: an opaque set resolved by the native module
    // (com.overdrive.app.od) and uploaded as five vec4 uniforms (uOd0..4). The
    // shader does no geometry derivation itself.
    private int uOd0Location = -1;
    private int uOd1Location = -1;
    private int uOd2Location = -1;
    private int uOd3Location = -1;
    private int uOd4Location = -1;
    private final float[] odCoef = new float[20];
    private final float[] odIn = new float[11];
    // Opaque tuning scalars + per-side sign, forwarded to com.overdrive.app.od.
    // Indices 5/8/9 default to their identity values (no change until dialed).
    private final float[] odParam = { 1.66f, 1.98f, 1.23f, 0.25f, -0.275f, 1.0f, 1.0f, 0.38f, 0.0f, 0.0f };
    private volatile float odSign = -1.0f;   // resolved for the active side
    private int aPositionLocation;
    private int aTexCoordLocation;

    // Clip-space output rotation (blind-spot card). Column-major mat2 uploaded to
    // uRotation. Identity by default so every non-BS path (and 0° BS) renders exactly
    // as before. Written by setContentRotation (app/JS thread), read by drawFrame (GL
    // thread) — volatile snapshot + dirty bit, same pattern as producerCornerMap.
    private int uRotationLocation = -1;
    private final float[] rotationMat = { 1f, 0f, 0f, 1f };   // column-major identity
    private final float[] scratchRotation = new float[4];     // GL-thread snapshot, no per-frame alloc
    private volatile boolean rotationDirty = true;            // upload once at init (uniforms default to 0 → must seed identity)
    private volatile int contentRotationDeg = 0;
    // Clip-space translation applied AFTER uRotation (blind-spot card only). At a 90/270
    // quarter turn the 4:3 card becomes portrait and is scaled to FIT (pillarboxed)
    // inside the 4:3 buffer — centered by default, which makes a corner-anchored card's
    // video float in the middle of its dest rect instead of hugging the chosen left/
    // right edge. This offset pushes the pillarboxed content to that edge. Zero at
    // 0/180 (card fills, no pillarbox) and for a centered card, so those paths are
    // unchanged. uploaded with the same dirty bit as uRotation.
    private int uRotationOffsetLocation = -1;
    private final float[] rotationOffset = { 0f, 0f };
    private final float[] scratchRotationOffset = new float[2];
    private volatile int contentAlignX = 0;                   // -1 hug left, 0 center, +1 hug right

    // OEM Dashcam (view-6) sampler — bound to texture unit 1 when an OEM
    // pipeline is active and the user picked DVR. uOemTexMatrix carries
    // the SurfaceTexture transform from the OEM camera (HAL Y-flip + crop)
    // so the sample stays correctly oriented.
    private int uOemTexLocation = -1;
    private int uOemTexMatrixLocation = -1;
    private int uOemActiveLocation = -1;
    private final float[] oemTexMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };

    // Producer-corner remap + per-role X/Y flip flags. Order matches
    // {Front, Right, Rear, Left}. Default = canonical 2x2; pipeline calls
    // setProducerCornerMap / setFlipFlags on DiLink 4 to override with the
    // car's actual layout. UI thread writes, GL thread reads.
    private final float[] producerCornerMap = {
        0.00f, 0.00f,
        0.50f, 0.00f,
        0.00f, 0.50f,
        0.50f, 0.50f
    };
    private final float[] flipFlags = {
        0f, 0f,  0f, 0f,  0f, 0f,  0f, 0f
    };
    private final Object producerCornerMapLock = new Object();
    // Reusable scratch buffers consumed inside drawFrame. The setters on
    // the JS / app thread write into producerCornerMap / flipFlags under
    // the lock; drawFrame copies into these scratch arrays under the same
    // lock and then uploads. NO per-frame allocation.
    private final float[] scratchCornerMap = new float[8];
    private final float[] scratchFlipFlags = new float[8];
    // Dirty bit — gates the eight glUniform2f calls. Setters flip it on,
    // drawFrame consumes after upload. With the layout written once at
    // start the typical case is a single upload.
    private volatile boolean producerCornerDirty = true;

    // SurfaceTexture transform matrix, written from the camera GL thread
    // immediately before drawFrame. Identity until the first publish.
    private final float[] currentTexMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };
    
    // View mode: 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left, 5=Raw
    private volatile int currentViewMode = 0;
    private volatile int cameraLayout = 0;  // 0=4-cam, 1=full-frame APA, 2=3-cam, 3=2x2 remap
    
    // Vertex data
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    // Configuration
    private final int outputWidth;
    private final int outputHeight;
    private final float[] quadrantStripOffsetX;
    private final String fragmentShader;
    private static final float[] DEFAULT_QUADRANT_STRIP_OFFSET_X = {
        0.75f, 0.50f, 0.00f, 0.25f
    };
    
    // Fullscreen quad vertices
    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    
    // Texture coordinates — UN-flipped V (V=0 at bottom of texture). The
    // vertex shader applies a manual Y-flip on legacy (uTexMatrix=identity)
    // and skips it on DiLink 4 where the SurfaceTexture matrix already
    // includes the producer's Y-flip.
    private static final float[] TEX_COORDS = {
        0.0f, 0.0f,
        1.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 1.0f
    };

    // Vertex shader with conditional manual Y-flip (matches recorder).
    // Also forwards the raw aTexCoord as vUnit so the fragment shader can
    // sample non-pano sources (OEM dashcam) without composing pano's
    // texture matrix on top.
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform float uApplyManualYFlip;\n" +
        // Clip-space rotation of the OUTPUT quad (blind-spot card only). We rotate
        // the RENDERED GEOMETRY here instead of asking SurfaceControl to rotate the
        // composited layer, because this firmware's SurfaceFlinger/HWC composites
        // identity + FLIP/180 transforms but DROPS a 90/270 (axis-transposing)
        // transform on a non-fullscreen overlay layer → the card went blank at
        // 90/270 (issue #164). Rotating in-shader keeps SurfaceControl at identity
        // (the path proven to composite) and never touches the fragment sampling /
        // od projection, so the image content is unchanged — only its on-screen
        // orientation. uRotation is a pure rotation (det +1) so it can never mirror;
        // the aspect term is folded in by the caller (see setContentRotation) so a
        // quarter turn of a 4:3 card letterboxes inside the 4:3 buffer with a uniform
        // scale (no stretch). Identity by default → 0° path is byte-for-byte unchanged.
        "uniform mat2 uRotation;\n" +
        // Post-rotation clip-space shift (blind-spot card only). At 90/270 the rotated
        // portrait card is pillarboxed inside the 4:3 output; this nudges it to the
        // chosen left/right edge so a corner-anchored card hugs that edge instead of
        // floating centered. (0,0) at 0/180 and for a centered card → no change there.
        "uniform vec2 uRotationOffset;\n" +
        "varying vec2 vTexCoord;\n" +
        "varying vec2 vUnit;\n" +
        "void main() {\n" +
        "    gl_Position = vec4(uRotation * aPosition.xy + uRotationOffset, aPosition.zw);\n" +
        "    vUnit = aTexCoord;\n" +
        "    vec2 src = aTexCoord;\n" +
        "    if (uApplyManualYFlip > 0.5) src.y = 1.0 - src.y;\n" +
        "    vTexCoord = (uTexMatrix * vec4(src, 0.0, 1.0)).xy;\n" +
        "}\n";
    
    /** Fragment shader is profile-baked at construction via
     *  {@link #buildFragmentShader(float[])}. Single-view modes use the four
     *  per-quadrant offsets so streaming a single direction picks the slice
     *  that the user mapped to that role. */

    private final boolean isTexture2D;

    public GpuStreamScaler(int outputWidth, int outputHeight) {
        this(outputWidth, outputHeight, null, false);
    }

    /**
     * @param quadrantStripOffsetX Per-role X offsets in 5120-wide 4-strip
     *     (legacy HAL). {Front, Right, Rear, Left}.
     */
    public GpuStreamScaler(int outputWidth, int outputHeight,
                           float[] quadrantStripOffsetX) {
        this(outputWidth, outputHeight, quadrantStripOffsetX, false);
    }

    public GpuStreamScaler(int outputWidth, int outputHeight,
                           float[] quadrantStripOffsetX, boolean isTexture2D) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.isTexture2D = isTexture2D;
        this.quadrantStripOffsetX = normalizeOffsets(quadrantStripOffsetX);
        this.fragmentShader = buildFragmentShader(this.quadrantStripOffsetX, isTexture2D);
    }
    
    public int getWidth() { return outputWidth; }
    public int getHeight() { return outputHeight; }
    
    /**
     * Initializes the stream scaler.
     * 
     * @param eglCore EGL context manager
     * @param encoder Hardware encoder for streaming
     */
    public void init(EGLCore eglCore, HardwareEventRecorderGpu encoder) {
        // Get encoder's input surface
        android.view.Surface encSurf = encoder.getInputSurface();
        if (encSurf == null) {
            throw new RuntimeException("Stream encoder input surface is null");
        }
        initWithSurface(eglCore, encSurf);
    }

    /**
     * Initialize the scaler to render into an ARBITRARY Android Surface instead of
     * a MediaCodec encoder input surface — used by the NATIVE blind-spot path,
     * where the target is a Surface wrapping a daemon-owned SurfaceControl layer
     * (GPU → screen directly, no encoder/WebSocket/decoder). The render path is
     * identical: drawFrame() makeCurrents `encoderSurface` (the EGLSurface) and
     * swapBuffers to it — whether that's backed by an encoder or a SurfaceControl
     * layer is opaque to the GL code.
     *
     * @param eglCore EGL context manager (the camera's shared context)
     * @param targetSurface Android Surface to render into (SurfaceControl-backed)
     */
    public void initWithSurface(EGLCore eglCore, android.view.Surface targetSurface) {
        this.eglCore = eglCore;

        if (targetSurface == null) {
            throw new RuntimeException("Target surface is null");
        }
        encoderInputSurface = targetSurface;

        // Create EGL surface from the target surface (encoder input OR SC layer)
        encoderSurface = eglCore.createWindowSurface(encoderInputSurface);

        // Compile shaders (profile-baked)
        programId = GlUtil.createProgram(VERTEX_SHADER, fragmentShader);
        if (programId == 0) {
            throw new RuntimeException("Failed to create shader program");
        }
        
        // Get locations
        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(programId, "aTexCoord");
        uCameraTexLocation = GLES20.glGetUniformLocation(programId, "uCameraTex");
        uViewModeLocation = GLES20.glGetUniformLocation(programId, "uViewMode");
        uBsRadiusLocation = GLES20.glGetUniformLocation(programId, "uBsRadius");
        uBsAspectLocation = GLES20.glGetUniformLocation(programId, "uBsAspect");
        uBsFeatherLocation = GLES20.glGetUniformLocation(programId, "uBsFeather");
        uBsMergeModeLocation = GLES20.glGetUniformLocation(programId, "uBsMergeMode");
        uBsMarginLocation     = GLES20.glGetUniformLocation(programId, "uBsMargin");
        uBsBevelWLocation     = GLES20.glGetUniformLocation(programId, "uBsBevelW");
        uBsBevelLightLocation = GLES20.glGetUniformLocation(programId, "uBsBevelLight");
        uBsBevelDarkLocation  = GLES20.glGetUniformLocation(programId, "uBsBevelDark");
        uBsLightDirLocation   = GLES20.glGetUniformLocation(programId, "uBsLightDir");
        uBsSpecWLocation      = GLES20.glGetUniformLocation(programId, "uBsSpecW");
        uBsSpecIntLocation    = GLES20.glGetUniformLocation(programId, "uBsSpecInt");
        uBsRectifyK1Location     = GLES20.glGetUniformLocation(programId, "uBsRectifyK1");
        uBsRectifyK2Location     = GLES20.glGetUniformLocation(programId, "uBsRectifyK2");
        uBsRectifyAspectLocation = GLES20.glGetUniformLocation(programId, "uBsRectifyAspect");
        uApaModeLocation = GLES20.glGetUniformLocation(programId, "uApaMode");
        uTexMatrixLocation = GLES20.glGetUniformLocation(programId, "uTexMatrix");
        uApplyManualYFlipLocation = GLES20.glGetUniformLocation(programId, "uApplyManualYFlip");
        uRotationLocation = GLES20.glGetUniformLocation(programId, "uRotation");
        uRotationOffsetLocation = GLES20.glGetUniformLocation(programId, "uRotationOffset");
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
        uOd0Location = GLES20.glGetUniformLocation(programId, "uOd0");
        uOd1Location = GLES20.glGetUniformLocation(programId, "uOd1");
        uOd2Location = GLES20.glGetUniformLocation(programId, "uOd2");
        uOd3Location = GLES20.glGetUniformLocation(programId, "uOd3");
        uOd4Location = GLES20.glGetUniformLocation(programId, "uOd4");
        uOemTexLocation = GLES20.glGetUniformLocation(programId, "uOemTex");
        uOemTexMatrixLocation = GLES20.glGetUniformLocation(programId, "uOemTexMatrix");
        uOemActiveLocation = GLES20.glGetUniformLocation(programId, "uOemActive");

        GlUtil.checkGlError("glGetLocation");

        // Create vertex buffers
        vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
        texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);

        // ONE-SHOT: bind texture-unit 1 to the GLES sampler `uOemTex` and
        // leave it bound. drawFrame previously rebound this every frame to
        // dodge an Adreno green-flash on first program use; binding once
        // here costs nothing and removes ~3 GL calls + an active-unit
        // ping-pong (TEXTURE1 → TEXTURE0) from the steady-state hot path.
        if (uOemTexLocation >= 0) {
            eglCore.makeCurrent(encoderSurface);
            GLES20.glUseProgram(programId);
            GLES20.glUniform1i(uOemTexLocation, 1);
            // Don't actually bind a real texture here — drawFrame's first
            // OEM-active path will. We only set the sampler unit assignment
            // (which is a program-state property, not a context-state
            // property). On Adreno the green-flash mitigation is the
            // `uOemActive` gate in the shader, not the bind.
        }

        // Resolve initial sampler coefficients for the default side so the very
        // first view-7/8 frame is correct even before any tuning call.
        resolveCoef();

        logger.info("GpuStreamScaler initialized: " + outputWidth + "×" + outputHeight);
    }

    /**
     * Renders a frame to the stream encoder, stamping an explicit presentation
     * timestamp onto the encoder surface.
     *
     * <p><b>Why this overload exists.</b> The plain {@link #drawFrame(int)} ends in
     * {@code eglCore.swapBuffers(...)}, which pushes the buffer with NO presentation
     * time — MediaCodec then has to invent one. That is fine when the source rate
     * matches the encoder's configured {@code KEY_FRAME_RATE}, but on the BYD
     * byd_apa HAL the camera emits at its own fixed ~4.5 fps and refuses
     * {@code setCameraFps} entirely (it returns false for every value; the OEM app
     * and other OEM-derived players both discard that return). A 15 fps-configured encoder fed an
     * unstamped 4.5 fps source produces near-empty P-frames with no usable
     * timing, which renders as a frozen picture after the first keyframe.
     *
     * <p>Both other lanes already do this — {@code GpuMosaicRecorder:1022} and
     * {@code OemDashcamPipeline:1748} call {@code swapBuffersWithTimestamp}. The
     * live-view stream lane was the only one that did not. The OEM's own encode
     * loop stamps every frame ({@code eglPresentationTimeANDROID} immediately
     * before {@code eglSwapBuffers}, once per real frame arrival).
     *
     * @param cameraTextureId Camera texture ID
     * @param ptsNs presentation timestamp in nanoseconds, from the same
     *              single-domain clock the recorder uses. {@code <= 0} falls back
     *              to the unstamped path, so a caller without a real timestamp
     *              behaves exactly as before.
     */
    public void drawFrame(int cameraTextureId, long ptsNs) {
        this.pendingPtsNs = ptsNs;
        try {
            drawFrame(cameraTextureId);
        } finally {
            this.pendingPtsNs = 0L;
        }
    }

    /** Presentation timestamp for the in-flight draw, or 0 for "unstamped".
     *  GL-thread only: set and cleared around a single synchronous drawFrame. */
    private long pendingPtsNs = 0L;

    /**
     * Renders a frame to the stream encoder.
     *
     * @param cameraTextureId Camera texture ID
     */
    public void drawFrame(int cameraTextureId) {
        // Skip if init() never completed. init() assigns eglCore/encoderSurface
        // BEFORE compiling the shader and creating the vertex buffers, so a
        // shader compile failure leaves this object half-built: callers still
        // hold a non-null reference and keep calling drawFrame every frame.
        // Without this guard that path reaches glVertexAttribPointer with a
        // null Buffer and throws an NPE per frame for the life of the process.
        if (eglCore == null || encoderSurface == null
                || programId == 0 || vertexBuffer == null || texCoordBuffer == null) {
            return;
        }

        // Make encoder surface current
        eglCore.makeCurrent(encoderSurface);
        
        // Set viewport
        GLES20.glViewport(0, 0, outputWidth, outputHeight);

        // Blind-spot views (7/8) draw a 3D "curved glass card" with TRANSPARENT
        // rounded corners (and transparent where the projection has no coverage)
        // onto the SurfaceControl layer. Clear to fully transparent (not opaque
        // black) so the cut-outs composite through, and enable premultiplied-alpha
        // blending. The gate fires for ANY BS view (the card always needs the
        // transparent corners); other views (live-view encoder) stay opaque —
        // clear black, no blend — exactly as before.
        boolean bsCard = (currentViewMode == 7 || currentViewMode == 8);
        if (bsCard) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);  // premultiplied
        } else {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        // Use shader
        GLES20.glUseProgram(programId);
        
        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        if (isTexture2D) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTextureId);
        } else {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        }
        GLES20.glUniform1i(uCameraTexLocation, 0);
        
        // Quasi-static uniforms — only re-upload when a setter flagged
        // a change. uTexMatrix is exempt (camera transform changes every
        // frame, written by setTextureMatrix below).
        // CAS-claim BEFORE the reads so a setter racing into a fresh
        // dirty=true on a subsequent frame replays correctly. Mirrors
        // GpuMosaicRecorder.apaModeUniformDirty.compareAndSet pattern.
        if (uniformsDirty.compareAndSet(true, false)) {
            GLES20.glUniform1i(uViewModeLocation, currentViewMode);
            GLES20.glUniform1f(uApaModeLocation, (float) cameraLayout);
            // Rounded-card mask params (only meaningful on views 7/8; the shader
            // returns full coverage when uBsRadius<=0). aspect = w/h so corners are
            // circular; feather ≈ 1.5px in output-height units for a clean AA edge.
            if (uBsRadiusLocation >= 0) {
                boolean bs = (currentViewMode == 7 || currentViewMode == 8);
                GLES20.glUniform1f(uBsRadiusLocation, bs ? bsCornerRadius : 0.0f);
            }
            if (uBsAspectLocation >= 0) {
                GLES20.glUniform1f(uBsAspectLocation,
                    outputHeight > 0 ? (float) outputWidth / (float) outputHeight : 1.0f);
            }
            if (uBsFeatherLocation >= 0) {
                GLES20.glUniform1f(uBsFeatherLocation,
                    outputHeight > 0 ? 1.5f / (float) outputHeight : 0.003f);
            }
            if (uBsMergeModeLocation >= 0) {
                GLES20.glUniform1i(uBsMergeModeLocation, bsMergeMode);
            }
            // 3D curved-glass-card framing params. Gate margin to 0 on non-BS views
            // so the framing branch (only reached on 7/8 anyway) is fully inert
            // elsewhere; the rest are constant. uniformsDirty gates the upload so
            // steady-state frames pay nothing for these uniforms.
            {
                boolean bsv = (currentViewMode == 7 || currentViewMode == 8);
                if (uBsMarginLocation     >= 0) GLES20.glUniform1f(uBsMarginLocation,     bsv ? bsMargin : 0.0f);
                if (uBsBevelWLocation     >= 0) GLES20.glUniform1f(uBsBevelWLocation,     bsBevelW);
                if (uBsBevelLightLocation >= 0) GLES20.glUniform1f(uBsBevelLightLocation, bsBevelLight);
                if (uBsBevelDarkLocation  >= 0) GLES20.glUniform1f(uBsBevelDarkLocation,  bsBevelDark);
                if (uBsLightDirLocation   >= 0) GLES20.glUniform2f(uBsLightDirLocation,   bsLightDirX, bsLightDirY);
                if (uBsSpecWLocation      >= 0) GLES20.glUniform1f(uBsSpecWLocation,      bsSpecW);
                if (uBsSpecIntLocation    >= 0) GLES20.glUniform1f(uBsSpecIntLocation,    bsSpecInt);
                // Fisheye dewarp coeffs for the single-camera passthrough (identity at 0).
                if (uBsRectifyK1Location     >= 0) GLES20.glUniform1f(uBsRectifyK1Location,     bsRectifyK1);
                if (uBsRectifyK2Location     >= 0) GLES20.glUniform1f(uBsRectifyK2Location,     bsRectifyK2);
                if (uBsRectifyAspectLocation >= 0) GLES20.glUniform1f(uBsRectifyAspectLocation, bsRectifyAspect);
            }
            if (uApplyManualYFlipLocation >= 0) {
                // SurfaceTexture layouts 1/3 use the matrix's Y-flip. Layout 0 / DiLink 5 needs manual Y-flip.
                GLES20.glUniform1f(uApplyManualYFlipLocation,
                    (cameraLayout == 1 || cameraLayout == 3) ? 0.0f : 1.0f);
            }
            if (uRedMaskStrengthLocation >= 0) {
                GLES20.glUniform1f(uRedMaskStrengthLocation, redMaskEnabled ? 1.0f : 0.0f);
            }
            if (uApaCenterInsetLocation >= 0) {
                GLES20.glUniform1f(uApaCenterInsetLocation, apaCenterInset);
            }
            if (uOd0Location >= 0) {
                GLES20.glUniform4f(uOd0Location, odCoef[0], odCoef[1], odCoef[2], odCoef[3]);
            }
            if (uOd1Location >= 0) {
                GLES20.glUniform4f(uOd1Location, odCoef[4], odCoef[5], odCoef[6], odCoef[7]);
            }
            if (uOd2Location >= 0) {
                GLES20.glUniform4f(uOd2Location, odCoef[8], odCoef[9], odCoef[10], odCoef[11]);
            }
            if (uOd3Location >= 0) {
                GLES20.glUniform4f(uOd3Location, odCoef[12], odCoef[13], odCoef[14], odCoef[15]);
            }
            if (uOd4Location >= 0) {
                GLES20.glUniform4f(uOd4Location, odCoef[16], odCoef[17], odCoef[18], odCoef[19]);
            }
        }
        if (uTexMatrixLocation >= 0) {
            GLES20.glUniformMatrix4fv(uTexMatrixLocation, 1, false, currentTexMatrix, 0);
        }
        if (uProducerForFrontLocation >= 0 && producerCornerDirty) {
            // Setters flip producerCornerDirty=true; we consume here under
            // the same lock so the scratch copy is consistent with the
            // setter's atomic writes. After the eight uniform uploads we
            // clear the dirty bit. Subsequent frames skip the lock + copy
            // + uniform uploads — at frame rate that's ~32B + 8 uniform
            // calls per draw saved.
            float[] m = scratchCornerMap;
            float[] f = scratchFlipFlags;
            synchronized (producerCornerMapLock) {
                System.arraycopy(producerCornerMap, 0, m, 0, 8);
                System.arraycopy(flipFlags, 0, f, 0, 8);
                producerCornerDirty = false;
            }
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
        // OEM dashcam binding for view 6.
        //   - View 0..4 with no OEM: skip everything in this block. The
        //     fragment shader's `uViewMode == 6 && uOemActive == 1` gate
        //     ignores texture unit 1 entirely, so an unbound sampler is fine
        //     in steady state. uOemActive is uploaded once on bind/unbind.
        //   - On bind/unbind transition (oemBindingDirty=true): re-bind the
        //     texture id, write uOemActive, and re-upload uOemTexMatrix.
        //   - Per-frame while bound: only re-upload the matrix (it's the
        //     only thing that changes per frame). No glActiveTexture ping-
        //     pong, no rebind, no uniform writes for unchanged uOemActive.
        // Treat OEM as "not ready" until OEM has actually published its
        // first transform matrix snapshot. Otherwise the first frame
        // after bindOemSource samples the OEM EXTERNAL_OES texture with
        // the identity matrix declared at field init — producing a
        // garbled or upside-down frame for ~33 ms before OEM's render
        // loop publishes its real matrix. Pin uOemActive=0 until snap
        // arrives, then re-flip oemBindingDirty so the next frame picks
        // up the real bind+matrix.
        float[] snap = oemTexMatrixSnapshot;
        boolean oemReady = oemSourceActive
            && oemSurfaceTexture != null
            && oemTextureId != 0
            && snap != null;
        if (uOemTexLocation >= 0 && oemBindingDirty.compareAndSet(true, false)) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            if (oemReady) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oemTextureId);
            } else {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            if (uOemActiveLocation >= 0) {
                GLES20.glUniform1i(uOemActiveLocation, oemReady ? 1 : 0);
            }
            // If OEM is still warming (snap not yet published), force the
            // dirty bit back true so the next frame re-evaluates without
            // requiring a setter call.
            if (!oemReady && oemSourceActive) {
                oemBindingDirty.set(true);
            }
        }
        if (oemReady && uOemTexMatrixLocation >= 0) {
            // OEM ownership of the SurfaceTexture lives in OEM's render
            // loop; we just sample. snap is non-null here by the
            // oemReady gate above.
            System.arraycopy(snap, 0, oemTexMatrix, 0, 16);
            GLES20.glUniformMatrix4fv(uOemTexMatrixLocation, 1, false, oemTexMatrix, 0);
        }

        // Set up vertex attributes
        GLES20.glEnableVertexAttribArray(aPositionLocation);
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        
        GLES20.glEnableVertexAttribArray(aTexCoordLocation);
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        // Output rotation (blind-spot card). Upload the column-major mat2 only when a
        // setter changed it; identity otherwise, so non-BS lanes and 0° pay nothing
        // after the first frame. Snapshot under the same lock the setter writes.
        if (uRotationLocation >= 0 && rotationDirty) {
            float[] rm = scratchRotation;
            float[] ro = scratchRotationOffset;
            synchronized (producerCornerMapLock) {
                rm[0] = rotationMat[0]; rm[1] = rotationMat[1];
                rm[2] = rotationMat[2]; rm[3] = rotationMat[3];
                ro[0] = rotationOffset[0]; ro[1] = rotationOffset[1];
                rotationDirty = false;
            }
            GLES20.glUniformMatrix2fv(uRotationLocation, 1, false, rm, 0);
            if (uRotationOffsetLocation >= 0) GLES20.glUniform2f(uRotationOffsetLocation, ro[0], ro[1]);
        }

        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(aPositionLocation);
        GLES20.glDisableVertexAttribArray(aTexCoordLocation);
        
        // Submit to encoder. Stamp the presentation time when the caller supplied
        // one (see drawFrame(int, long)); otherwise use the historical unstamped
        // swap so every existing call site — including the blind-spot lane, whose
        // target is a SurfaceControl layer with no encoder to time — is bit-identical
        // to before.
        long pts = pendingPtsNs;
        if (pts > 0L) {
            eglCore.swapBuffersWithTimestamp(encoderSurface, pts);
        } else {
            eglCore.swapBuffers(encoderSurface);
        }
    }

    /**
     * Push ONE fully-transparent frame to the layer's surface. Used on hide so the
     * SurfaceControl layer's last-latched buffer is BLANK, not the final live frame
     * — otherwise the next show flashes that stale frame until {@link #drawFrame}
     * swaps a fresh one (visible for seconds on the cluster, where re-open is slow).
     * MUST run on the GL thread (EGL surface is GL-thread-bound), same as drawFrame.
     */
    public void clearFrame() {
        try {
            eglCore.makeCurrent(encoderSurface);
            GLES20.glViewport(0, 0, outputWidth, outputHeight);
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);   // transparent
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            eglCore.swapBuffers(encoderSurface);
        } catch (Throwable t) {
            // best-effort; a failed clear just leaves the prior buffer (old behavior)
        }
    }

    /**
     * Sets the view mode for streaming.
     *
     * @param mode 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left,
     *             5=Raw (legacy debug passthrough — pano strip), 6=OEM Dashcam,
     *             7=BlindSpot L, 8=BlindSpot R
     */
    public void setViewMode(int mode) {
        if (mode < 0 || mode > 8) return;
        if (mode != 6) {
            synchronized (oemViewLock) {
                oemViewRequested = false;
            }
        }
        if (mode != 7 && mode != 8 && mode == this.currentViewMode) return;   // idempotent — no upload needed (BS modes 7/8 always re-apply side sign)
        this.currentViewMode = mode;
        // Resolve the sampler coefficients for the active side (7 = -1, 8 = +1).
        if (mode == 7) setBlindSpotSide(-1.0f);
        else if (mode == 8) setBlindSpotSide(1.0f);
        this.uniformsDirty.set(true);
        logger.info("Stream view mode set to " + mode);
    }

    /**
     * Rotate the on-screen blind-spot card by a quarter turn (0/90/180/270), applied
     * in the vertex shader to the OUTPUT geometry — NOT via the SurfaceControl layer
     * transform (this firmware's compositor drops a 90/270 layer transform → blank
     * card, issue #164). The rotation is a pure rotation (never a mirror) and folds in
     * the output aspect so a quarter turn of the 4:3 card letterboxes inside the 4:3
     * buffer at a uniform scale (no anamorphic stretch). Any non-multiple-of-90 is
     * snapped; out-of-range normalised to [0,270]. Thread-safe: stores a column-major
     * mat2 snapshot + sets a dirty bit that drawFrame consumes on the GL thread.
     *
     * <p>Derivation: clip space [-1,1]^2 maps to a WxH viewport, so 1 clip-x unit =
     * W/2 px and 1 clip-y unit = H/2 px — a unit is NOT visually square (a=W/H). A 4:3
     * card rotated a quarter turn becomes 3:4, which cannot FILL a 4:3 viewport without
     * distortion, so it is fit (pillarboxed) — rotate in pixel space then scale by 1/a
     * to fit the rotated height back inside the viewport. Verified per basis vector:
     *   90:  clip(1,0)->(0,1),  clip(0,1)->(-1/a^2,0)  =>  (x,y) -> (-y/a^2,  x)
     *   270: clip(1,0)->(0,-1), clip(0,1)->( 1/a^2,0)  =>  (x,y) -> ( y/a^2, -x)
     *   180: (x,y) -> (-x, -y)      0: identity
     * det(M)=1/a^2>0 for the quarter turns (a proper rotation composed with a UNIFORM
     * 1/a scale — no anamorphic stretch, and det>0 so it can NEVER mirror like the old
     * SurfaceControl-orientation path did). The rounded-card SDF is computed in
     * vTexCoord space (unrotated), so the card + its corners rotate as one rigid unit;
     * the pillarbox regions stay transparent (map shows through), same as at 0°. Where
     * that pillarboxed content sits horizontally is set by the alignment — see the
     * 2-arg overload; this 1-arg form keeps the current alignment.
     */
    public void setContentRotation(int degrees) {
        setContentRotation(degrees, this.contentAlignX);
    }

    /**
     * Set both the quarter-turn rotation AND the horizontal alignment of the rotated
     * card, in one atomic publish. {@code alignX}: -1 = hug the LEFT screen edge,
     * +1 = hug the RIGHT, 0 = stay centered. Alignment only matters at 90/270 (where
     * the portrait card is pillarboxed inside the 4:3 output); at 0/180 the card fills
     * its rect so the offset is forced to 0. This keeps a corner-anchored card touching
     * the chosen edge after a quarter turn instead of floating in the middle.
     */
    public void setContentRotation(int degrees, int alignX) {
        int d = ((degrees % 360) + 360) % 360;
        d = (Math.round(d / 90f) * 90) % 360;
        int ax = (alignX > 0) ? 1 : (alignX < 0 ? -1 : 0);
        if (d == this.contentRotationDeg && ax == this.contentAlignX) return;   // idempotent
        this.contentRotationDeg = d;
        this.contentAlignX = ax;
        float a = (outputHeight > 0) ? (float) outputWidth / (float) outputHeight : 1.0f;
        float inv2 = 1.0f / (a * a);   // fit-scale (1/a) folded with the aspect conj (1/a)
        // Column-major mat2: elements are {col0.x, col0.y, col1.x, col1.y}, i.e.
        // {M00, M10, M01, M11}. M maps aPosition.xy (column vector) as M * v.
        float m00, m10, m01, m11;
        // Post-rotation clip-x shift. Only the 90/270 turns pillarbox (content spans
        // clip-x ∈ [-inv2, +inv2]), leaving (1 - inv2) empty on EACH side; shift by that
        // full margin to hug an edge. 0/180 fill the width → no shift. Centered → no
        // shift. clip +x is screen-right, so alignX=+1 shifts +margin (right).
        float offX = 0f;
        boolean quarter = (d == 90 || d == 270);
        if (quarter && ax != 0) offX = ax * (1.0f - inv2);
        switch (d) {
            case 90:  m00 = 0f;    m10 = 1f;   m01 = -inv2;   m11 = 0f;   break;
            case 180: m00 = -1f;   m10 = 0f;   m01 = 0f;      m11 = -1f;  break;
            case 270: m00 = 0f;    m10 = -1f;  m01 = inv2;    m11 = 0f;   break;
            default:  m00 = 1f;    m10 = 0f;   m01 = 0f;      m11 = 1f;   break;   // 0°
        }
        synchronized (producerCornerMapLock) {
            rotationMat[0] = m00; rotationMat[1] = m10;
            rotationMat[2] = m01; rotationMat[3] = m11;
            rotationOffset[0] = offX; rotationOffset[1] = 0f;
            // Flag dirty INSIDE the lock so the new matrix and its dirty signal publish
            // together — drawFrame (which copies under this same lock) can never clear
            // the flag between the matrix write and the flag set and miss this update.
            rotationDirty = true;
        }
        logger.info("BS content rotation set to " + d + " deg, alignX=" + ax);
    }

    /** Current on-screen card rotation in degrees (0/90/180/270). */
    public int getContentRotation() { return contentRotationDeg; }

    /**
     * Bind the OEM Dashcam camera texture as the secondary source. View
     * mode 6 ({@code uViewMode==6}) then samples {@code uOemTex} instead
     * of the AVM mosaic. The texture id MUST be valid in the EGL share-
     * group of THIS scaler's context — wire it via {@link
     * com.overdrive.app.camera.EGLCore#createShared} at OEM pipeline start.
     *
     * <p>The SurfaceTexture stays attached to OEM's GL context — only OEM's
     * render loop calls {@code updateTexImage}. The scaler simply samples
     * the texture handle (cross-context shared via the EGL share group)
     * and reads the most recent transform matrix that OEM published into
     * {@link #publishOemTexMatrix}.
     */
    public void bindOemSource(int oemTextureId, android.graphics.SurfaceTexture oemSt) {
        synchronized (oemViewLock) {
            boolean sourceChanged = !this.oemSourceActive
                || this.oemTextureId != oemTextureId
                || this.oemSurfaceTexture != oemSt;
            // A transform only belongs to the SurfaceTexture that published it.
            // Do not activate view 6 from an earlier OEM instance's matrix while
            // the newly bound producer has not delivered a frame yet.
            if (sourceChanged) {
                this.oemTexMatrixSnapshot = null;
            }
            this.oemTextureId = oemTextureId;
            this.oemSurfaceTexture = oemSt;
            this.oemSourceActive = true;
            this.oemBindingDirty.set(true);
        }
        logger.info("OEM source bound to streamScaler (tex=" + oemTextureId + ")");
    }

    public void unbindOemSource() {
        synchronized (oemViewLock) {
            this.oemSourceActive = false;
            this.oemTextureId = 0;
            this.oemSurfaceTexture = null;
            // Drop the snapshot — without this, the OEM ping-pong matrix buffer
            // remains reachable from the scaler indefinitely, pinning OEM's
            // pipeline graph if the scaler outlives an OEM teardown.
            this.oemTexMatrixSnapshot = null;
            oemViewRequested = false;
            this.oemBindingDirty.set(true);
        }
        logger.info("OEM source unbound from streamScaler");
    }

    /** EGLCore behind this scaler; OEM pipeline shares its context with
     *  this so texture handles cross over. Returns null when scaler isn't
     *  initialised or has been released. */
    public EGLCore getEglCore() { return eglCore; }

    private volatile int oemTextureId = 0;
    private volatile android.graphics.SurfaceTexture oemSurfaceTexture;
    private volatile boolean oemSourceActive = false;
    // Dirty bit — flipped on bindOemSource / unbindOemSource. drawFrame
    // claims via compareAndSet(true,false) BEFORE consuming oemSourceActive,
    // so a racing setter (unbind during a draw) re-flips dirty=true and
    // the next frame replays. Same lost-update fix as uniformsDirty
    // (R3 #2) — this one was missed.
    private final java.util.concurrent.atomic.AtomicBoolean oemBindingDirty =
        new java.util.concurrent.atomic.AtomicBoolean(true);

    // Dirty bit for the ~5 quasi-static uniforms (uViewMode, uApaMode,
    // uApplyManualYFlip, uRedMaskStrength, uApaCenterInset). drawFrame
    // claims via compareAndSet(true,false) BEFORE the upload. A setter
    // racing during the upload re-sets dirty=true and the next frame
    // replays — without CAS, the naked-volatile clear-after pattern
    // would clobber a setter's flag and silently drop the new state.
    // Mirrors GpuMosaicRecorder.apaModeUniformDirty.
    private final java.util.concurrent.atomic.AtomicBoolean uniformsDirty =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    // Per-frame OEM tex matrix, published from OEM's GL thread after
    // every updateTexImage. Volatile reference flip is enough — the
    // scaler treats it as a snapshot and arraycopies into its own
    // working buffer. Producer allocates a fresh float[16] per publish
    // and never touches the buffer again, so a torn read across the
    // 16-float / 2-cache-line span can't happen.
    private volatile float[] oemTexMatrixSnapshot;
    // Serializes first-frame activation against a non-DVR selection. Without
    // it, publishOemTexMatrix could observe a stale pending request immediately
    // before a user selects another camera, then switch back to DVR afterward.
    private final Object oemViewLock = new Object();
    // A view-6 request may bind an allocated OEM texture before its first
    // updateTexImage publishes a transform. Keep the valid AVM view on-screen
    // until that snapshot arrives rather than falling through to the mosaic.
    private boolean oemViewRequested;

    /** Publish the OEM SurfaceTexture's transform matrix. Called from the
     *  OEM pipeline's GL thread immediately after updateTexImage. The
     *  reference is volatile so the scaler GL thread sees the latest
     *  publish. Caller MUST allocate a fresh buffer per publish — the
     *  scaler holds the reference until the next publish replaces it,
     *  so any post-publish mutation by the producer would be a torn
     *  read on the consumer side. */
    public void publishOemTexMatrix(float[] matrix) {
        if (matrix != null && matrix.length >= 16) {
            synchronized (oemViewLock) {
                if (!oemSourceActive) return;
                this.oemTexMatrixSnapshot = matrix;
                // The first real OEM frame is the only safe handoff point.
                // setViewMode marks uniforms dirty, so the next pano draw uploads
                // uViewMode=6 and uOemActive=1 together.
                if (oemViewRequested && oemTextureId != 0 && oemSurfaceTexture != null) {
                    oemViewRequested = false;
                    setViewMode(6);
                }
            }
        }
    }

    /**
     * Select the OEM view only after the attached source has published its
     * first SurfaceTexture transform. Returns false while activation is
     * deferred; publishOemTexMatrix completes the handoff automatically.
     */
    public boolean requestOemViewWhenReady() {
        synchronized (oemViewLock) {
            boolean ready = oemSourceActive && oemTextureId != 0
                && oemSurfaceTexture != null && oemTexMatrixSnapshot != null;
            if (!ready) {
                oemViewRequested = oemSourceActive;
                return false;
            }
            oemViewRequested = false;
            setViewMode(6);
            return true;
        }
    }
    
    /**
     * Gets the current view mode.
     */
    public int getViewMode() {
        return currentViewMode;
    }
    
    // setApaMode(boolean) intentionally NOT exposed.
    // It only mapped to cameraLayout∈{0,1} and would silently downgrade
    // a DiLink 4 stream's cameraLayout=3 (per-quadrant rearrange + flip
    // path in the shader) to 0 or 1 — emitting the raw producer mosaic
    // to the H.264 stream. Callers must use setCameraLayout(int) which
    // preserves layout=3 verbatim.

    public void setCameraLayout(int layout) {
        if (layout == this.cameraLayout) return;   // idempotent
        this.cameraLayout = layout;
        this.uniformsDirty.set(true);
    }

    /**
     * Sets the per-role producer corner XY map. Each pair is the top-left of
     * the role's 0.5×0.5 sub-rect inside the producer surface, in {Front,
     * Right, Rear, Left} order. Default = canonical 2x2; pipeline overrides
     * with Variant A constants on DiLink 4.
     */
    public void setProducerCornerMap(float[] front, float[] right,
                                     float[] rear, float[] left) {
        if (front == null || right == null || rear == null || left == null
                || front.length < 2 || right.length < 2
                || rear.length  < 2 || left.length  < 2) {
            return;
        }
        synchronized (producerCornerMapLock) {
            producerCornerMap[0] = front[0]; producerCornerMap[1] = front[1];
            producerCornerMap[2] = right[0]; producerCornerMap[3] = right[1];
            producerCornerMap[4] = rear[0];  producerCornerMap[5] = rear[1];
            producerCornerMap[6] = left[0];  producerCornerMap[7] = left[1];
            producerCornerDirty = true;
        }
    }

    /**
     * Atomic combined setter for both producer corners and flip flags.
     * Prefer this over the split setProducerCornerMap+setFlipFlags pair —
     * a drawFrame landing between the two writes can render a frame
     * with the new corners and the OLD flips (or vice versa), producing
     * one wrong-orientation mosaic frame per init. Single lock + single
     * dirty flip removes the window.
     */
    public void setProducerLayout(float[] frontC, float[] rightC,
                                  float[] rearC, float[] leftC,
                                  float[] frontF, float[] rightF,
                                  float[] rearF, float[] leftF) {
        if (frontC == null || rightC == null || rearC == null || leftC == null
                || frontF == null || rightF == null || rearF == null || leftF == null
                || frontC.length < 2 || rightC.length < 2 || rearC.length < 2 || leftC.length < 2
                || frontF.length < 2 || rightF.length < 2 || rearF.length < 2 || leftF.length < 2) {
            return;
        }
        synchronized (producerCornerMapLock) {
            producerCornerMap[0] = frontC[0]; producerCornerMap[1] = frontC[1];
            producerCornerMap[2] = rightC[0]; producerCornerMap[3] = rightC[1];
            producerCornerMap[4] = rearC[0];  producerCornerMap[5] = rearC[1];
            producerCornerMap[6] = leftC[0];  producerCornerMap[7] = leftC[1];
            flipFlags[0] = frontF[0]; flipFlags[1] = frontF[1];
            flipFlags[2] = rightF[0]; flipFlags[3] = rightF[1];
            flipFlags[4] = rearF[0];  flipFlags[5] = rearF[1];
            flipFlags[6] = leftF[0];  flipFlags[7] = leftF[1];
            producerCornerDirty = true;
        }
    }

    /**
     * Sets the per-role X/Y flip flags ({xFlip, yFlip} ∈ {0,1}). Applied
     * inside the role's local 0.5×0.5 region. {Front, Right, Rear, Left}.
     */
    public void setFlipFlags(float[] front, float[] right,
                             float[] rear, float[] left) {
        if (front == null || right == null || rear == null || left == null
                || front.length < 2 || right.length < 2
                || rear.length  < 2 || left.length  < 2) {
            return;
        }
        synchronized (producerCornerMapLock) {
            flipFlags[0] = front[0]; flipFlags[1] = front[1];
            flipFlags[2] = right[0]; flipFlags[3] = right[1];
            flipFlags[4] = rear[0];  flipFlags[5] = rear[1];
            flipFlags[6] = left[0];  flipFlags[7] = left[1];
            producerCornerDirty = true;
        }
    }

    /**
     * Enables or disables the GL red-overlay suppression on the live stream.
     * Mirrors GpuMosaicRecorder.setRedMaskEnabled. Off by default.
     */
    /** APA center inset (oem APACropFilter parity). See {@link
     *  com.overdrive.app.surveillance.GpuMosaicRecorder#setApaCenterInset}. */
    public void setApaCenterInset(float inset) {
        float clamped = Math.max(0.0f, Math.min(0.20f, inset));
        if (Float.compare(clamped, this.apaCenterInset) == 0) return;   // idempotent
        this.apaCenterInset = clamped;
        this.uniformsDirty.set(true);
    }

    /** Back-compat 8-arg overload (p8/p9 default to their identity value). */
    public void setBlindSpotParams(float hfov, float sideHFov, float yaw, float roll,
                                   float feather, float projExp, float vscale, float pitch) {
        setBlindSpotParams(hfov, sideHFov, yaw, roll, feather, projExp, vscale, pitch,
                           0.0f, 0.0f);
    }

    /**
     * View 7/8 live tuning. Opaque scalar inputs forwarded to the native module
     * (see com.overdrive.app.od.Od) which resolves them into the sampler
     * coefficient set; this class never computes the mapping. Accepted ranges:
     *   p0 [1.0,2.2]  p1 [1.0,2.2]  p2 [0.0,1.4]  p3 [-0.4,0.4]  p4 [-0.4,0.4]
     *   p5 [0.4,1.6]  p6 [0.7,1.3]  p7 [0.0,1.0]  p8 [-0.4,0.4]  p9 [-0.4,0.4]
     * Default values are the per-input identity (no change until dialed on-device).
     */
    public void setBlindSpotParams(float hfov, float sideHFov, float yaw, float roll,
                                   float feather, float projExp, float vscale, float pitch,
                                   float rearRoll, float rearPitch) {
        odParam[0] = Math.max(1.0f, Math.min(2.2f, hfov));
        odParam[1] = Math.max(1.0f, Math.min(2.2f, sideHFov));
        odParam[2] = Math.max(0.0f, Math.min(1.4f, yaw));
        odParam[3] = Math.max(-0.4f, Math.min(0.4f, roll));
        odParam[4] = Math.max(-0.4f, Math.min(0.4f, pitch));
        odParam[5] = Math.max(0.4f, Math.min(1.6f, projExp));
        odParam[6] = Math.max(0.7f, Math.min(1.3f, vscale));
        odParam[7] = Math.max(0.0f, Math.min(1.0f, feather));
        odParam[8] = Math.max(-0.4f, Math.min(0.4f, rearRoll));
        odParam[9] = Math.max(-0.4f, Math.min(0.4f, rearPitch));
        resolveCoef();
        this.uniformsDirty.set(true);
    }

    /** Blind-spot merge mode (views 7/8): 0 = both (rear+side stitch, default),
     *  1 = side camera only, 2 = rear camera only. Out-of-range values clamp to
     *  "both" so a corrupt config can't blank the view. Idempotent. */
    public void setBlindSpotMergeMode(int mode) {
        int m = (mode == 1 || mode == 2) ? mode : 0;
        if (m == this.bsMergeMode) return;
        this.bsMergeMode = m;
        this.uniformsDirty.set(true);
    }

    /**
     * Set the fisheye/lens-dewarp strength for the SINGLE-CAMERA blind-spot views
     * (merge mode side/rear). 0..100 maps to the same two-parameter division-model
     * coefficients the recorder uses (k1 = 0.30·t, k2 = 0.10·t, t = strength/100),
     * so the on-screen single-cam feed straightens residual barrel curvature exactly
     * like the recording pipeline. Identity at 0 (feature off). No effect on the
     * merged 'both' view (that path never samples the rectify uniforms). Idempotent.
     */
    public void setBlindSpotRectifyStrength(float strength) {
        float clamped = Math.max(0f, Math.min(100f, strength));
        float t = clamped / 100f;
        float k1 = 0.30f * t;
        float k2 = 0.10f * t;
        if (Float.compare(k1, this.bsRectifyK1) == 0
                && Float.compare(k2, this.bsRectifyK2) == 0) return;
        this.bsRectifyK1 = k1;
        this.bsRectifyK2 = k2;
        this.uniformsDirty.set(true);
    }

    /** Per-quadrant tile aspect (height/width) for the fisheye radial metric; 0.75
     *  for a 4:3 camera quadrant. Identity-equivalent when strength is 0. */
    public void setBlindSpotRectifyAspect(float aspect) {
        float clamped = Math.max(0.25f, Math.min(1.0f, aspect));
        if (Float.compare(clamped, this.bsRectifyAspect) == 0) return;
        this.bsRectifyAspect = clamped;
        this.uniformsDirty.set(true);
    }

    /** Set which side (view 7 = -1, view 8 = +1) the coefficients resolve for,
     *  then re-resolve. Called when the view mode changes to 7/8. */
    public void setBlindSpotSide(float sign) {
        if (Float.compare(sign, this.odSign) == 0) return;
        this.odSign = sign;
        resolveCoef();
        this.uniformsDirty.set(true);
    }

    /** Resolve the sampler coefficients via the native module. odIn is length 11:
     *  indices 0-9 are the raw params (odParam) and index 10 is the per-side sign.
     *  No-op-safe: Od zeroes the output if unauthorized. Reuses odIn (param-change
     *  events only, never per-frame; same single-thread assumption as odCoef). */
    private void resolveCoef() {
        System.arraycopy(odParam, 0, odIn, 0, 10);
        odIn[10] = odSign;
        com.overdrive.app.od.Od.resolve(odIn, odCoef);
    }

    public void setRedMaskEnabled(boolean enabled) {
        if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            enabled = false;
        }
        if (enabled == this.redMaskEnabled) return;   // idempotent
        this.redMaskEnabled = enabled;
        this.uniformsDirty.set(true);
    }

    /**
     * Publishes the SurfaceTexture transform matrix the next drawFrame will
     * upload to uTexMatrix. Called from the camera GL thread immediately
     * after consumeSurfaceTextureFrame; same thread as drawFrame so a plain
     * copy is safe (no synchronization needed).
     */
    public void setTextureMatrix(float[] matrix4x4) {
        if (matrix4x4 == null || matrix4x4.length < 16) return;
        System.arraycopy(matrix4x4, 0, currentTexMatrix, 0, 16);
    }
    
    /**
     * Releases all resources.
     */
    public void release() {
        // Drop OEM cross-context refs before anything else so any concurrent
        // OEM render-loop tick that races with our release sees the
        // unbound state and skips its publish/sample call entirely.
        try { unbindOemSource(); } catch (Throwable ignored) {}

        // glDeleteProgram requires a current EGL context. If we can't
        // make our encoder surface current (encoderSurface null because
        // of a partial init failure, or eglCore already released by a
        // sibling teardown), we MUST skip the delete and log the leak —
        // calling glDeleteProgram against a not-current context is a
        // silent no-op on Adreno (program leaks until process exit).
        if (programId != 0) {
            boolean contextReady = false;
            if (eglCore != null && encoderSurface != null) {
                try {
                    eglCore.makeCurrent(encoderSurface);
                    contextReady = true;
                } catch (Throwable t) {
                    logger.warn("release: makeCurrent failed: " + t.getMessage());
                }
            }
            if (contextReady) {
                try { GlUtil.deleteProgram(programId); } catch (Throwable ignored) {}
            } else {
                logger.warn("release: skipping glDeleteProgram (programId="
                    + programId + ", eglCore=" + (eglCore != null)
                    + ", encoderSurface=" + (encoderSurface != null)
                    + ") — leaking until process exit");
            }
            programId = 0;
        }

        if (encoderSurface != null && eglCore != null) {
            try { eglCore.destroySurface(encoderSurface); } catch (Throwable ignored) {}
            encoderSurface = null;
        }
        // Drop the EGLCore reference so getEglCore() (consumed by the
        // OEM pipeline's setParentEglCore wiring) doesn't return a
        // stale handle to a torn-down context. The scaler doesn't own
        // the EGLCore lifecycle (PanoramicCameraGpu does), so we don't
        // call release() on it — just null the field.
        eglCore = null;
        // Stale OEM source pointer won't reach the released sampler now
        // that drawFrame can never run again, but null defensively so
        // any stray getter / log shows the post-release state.
        encoderInputSurface = null;

        logger.info("GpuStreamScaler released");
    }

    private static float[] normalizeOffsets(float[] quadrantStripOffsetX) {
        if (quadrantStripOffsetX == null || quadrantStripOffsetX.length != 4) {
            return DEFAULT_QUADRANT_STRIP_OFFSET_X.clone();
        }
        return quadrantStripOffsetX.clone();
    }

    /**
     * Build the stream fragment shader with per-quadrant offsets baked in.
     * stripOffsets order: {Front, Right, Rear, Left} for legacy 4-strip HAL.
     * cornerOffsets order: {fX,fY, rX,rY, bX,bY, lX,lY} for 2x2-native HAL.
     * Single-view modes (uViewMode 1-4) pick the slice mapped to that role;
     * uViewMode 0 = mosaic; 5 = raw.
     */
    private static String buildFragmentShader(float[] offsets, boolean isTexture2D) {
        // uApaMode > 2.5 = DiLink 4 / 2x2-native HAL.
        //   uViewMode == 0 → rearrange the 2x2 into canonical Front=TL,
        //                    Right=TR, Rear=BL, Left=BR with per-role flips,
        //                    matching the recorder's mosaic output.
        //   uViewMode 1..4 → sample that role's producer corner with flip
        //                    applied within the local 0.5×0.5 region.
        //   uViewMode == 5 → raw passthrough (debug).
        // The legacy 4-strip math stays for uApaMode <= 2.5 paths.
        String ext = isTexture2D ? "#extension GL_OES_EGL_image_external : enable\n" : "#extension GL_OES_EGL_image_external : require\n";
        String camSampler = isTexture2D ? "uniform sampler2D uCameraTex;\n" : "uniform samplerExternalOES uCameraTex;\n";
        return String.format(Locale.US,
            ext +
            // highp: the view 7/8 sampler needs the extra precision (mediump, the
            // Adreno 610 fragment default, shimmers the seam). Other paths insensitive.
            "precision highp float;\n" +
            camSampler +
            "uniform samplerExternalOES uOemTex;\n" +
            "uniform mat4 uOemTexMatrix;\n" +
            "uniform int uOemActive;\n" +
            "uniform int uViewMode;\n" +
            "uniform float uApaMode;\n" +
            "uniform vec2 uProducerForFront;\n" +
            "uniform vec2 uProducerForRight;\n" +
            "uniform vec2 uProducerForRear;\n" +
            "uniform vec2 uProducerForLeft;\n" +
            "uniform vec2 uFlipForFront;\n" +
            "uniform vec2 uFlipForRight;\n" +
            "uniform vec2 uFlipForRear;\n" +
            "uniform vec2 uFlipForLeft;\n" +
            "uniform float uRedMaskStrength;\n" +
            "uniform float uApaCenterInset;\n" +
            // Resolved coefficient set (host-supplied; see com.overdrive.app.od.Od).
            "uniform vec4 uOd0;\n" +
            "uniform vec4 uOd1;\n" +
            "uniform vec4 uOd2;\n" +
            "uniform vec4 uOd3;\n" +
            // 5th opaque coefficient vec4 (rear-tap params; defaults identity).
            "uniform vec4 uOd4;\n" +
            "varying vec2 vTexCoord;\n" +
            "varying vec2 vUnit;\n" +
            // View 7/8 sampler. All geometry is host-resolved into uOd0..3
            // (com.overdrive.app.od); this only gathers from those coefficients.
            "const float OD_C = 1.55334303;\n" +
            "vec2 odMap(vec2 corner, vec2 rectSize, vec2 flip,\n" +
            "           float pixAspect, float ang, float tH,\n" +
            "           float y, float doRoll, float pitch, float cr, float sr) {\n" +
            "    if (tH < 0.0001) return corner;\n" +
            "    float a  = clamp(ang, -OD_C, OD_C);\n" +
            "    float ca = max(cos(a), 0.0175);\n" +
            // Resample exponent on uOd2.z; the > guard keeps the unauthorized/
            // zero-coefficient path at the identity mapping.
            "    float h1n = atan(tH);\n" +
            "    float pexp = uOd2.z;\n" +
            "    float an = clamp(abs(a) / h1n, 0.0, 1.0);\n" +
            "    float xm = (pexp > 0.0001) ? pow(an, pexp) : an;\n" +
            "    float xn = -sign(a) * xm;\n" +
            "    float ly = y * 2.0 - 1.0;\n" +
            "    float yn = (ly * pixAspect / ca) * uOd3.w;\n" +
            "    float den = 1.0;\n" +
            "    xn /= den;\n" +
            "    yn /= den;\n" +
            "    if (doRoll > 0.5) {\n" +
            "        float par = 1.0;\n" +
            "        if (flip.x > 0.5) par = -par;\n" +
            "        if (flip.y > 0.5) par = -par;\n" +
            // cr/sr are per-tap coefficients; par applies the quadrant mirror.
            "        float srp = sr * par;\n" +
            "        vec2  rxy = vec2(cr * xn - srp * yn, srp * xn + cr * yn);\n" +
            "        xn = rxy.x;\n" +
            "        yn = rxy.y + pitch;\n" +
            "    }\n" +
            "    xn = clamp(xn, -1.0, 1.0);\n" +
            "    yn = clamp(yn, -1.0, 1.0);\n" +
            "    float ql = xn * 0.5 + 0.5;\n" +
            "    float qm = yn * 0.5 + 0.5;\n" +
            "    if (flip.x > 0.5) ql = 1.0 - ql;\n" +
            "    if (flip.y > 0.5) qm = 1.0 - qm;\n" +
            "    vec2 uv = corner + vec2(ql, qm) * rectSize;\n" +
            "    vec2 lo = corner + rectSize * 0.0020;\n" +
            "    vec2 hi = corner + rectSize - rectSize * 0.0020;\n" +
            "    return clamp(uv, lo, hi);\n" +
            "}\n" +
            "vec4 odBlend(vec2 cA, vec2 fA, vec2 cB, vec2 fB,\n" +
            "             vec2 rectSize, float pixAspect,\n" +
            "             float sideSign, float xOut, float yOut) {\n" +
            "    float lo   = uOd0.x;\n" +
            "    float hi   = uOd0.y;\n" +
            "    float h0   = uOd0.w;\n" +
            "    float h1   = uOd1.x;\n" +
            "    float ctr  = uOd1.y;\n" +
            "    float bMid = uOd1.z;\n" +
            "    float bHf  = uOd1.w;\n" +
            // Each card renders only its own side's half of the output axis.
            "    float thA  = (sideSign > 0.0) ? hi : 0.0;\n" +
            "    float thB  = (sideSign > 0.0) ? 0.0 : lo;\n" +
            "    float th   = thA - clamp(xOut, 0.0, 1.0) * (thA - thB);\n" +
            "    float c0  = step(abs(th),       h0);\n" +
            "    float c1  = step(abs(th - ctr), h1);\n" +
            "    float s   = (th - bMid) * sideSign;\n" +
            "    float wOv = smoothstep(-bHf, bHf, s);\n" +
            "    float wB  = c1 * (c0 * wOv + (1.0 - c0));\n" +
            "    float cov = max(c0, c1);\n" +
            "    vec4 a = vec4(0.0);\n" +
            "    vec4 b = vec4(0.0);\n" +
            "    if (c0 > 0.5) {\n" +
            // tap A uses uOd4.xyz; defaults are the identity transform.
            "        a = texture2D(uCameraTex, odMap(cA, rectSize, fA, pixAspect, th, uOd2.x, yOut, 1.0, uOd4.z, uOd4.x, uOd4.y));\n" +
            "    }\n" +
            "    if (c1 > 0.5) {\n" +
            // tap B uses uOd3.xyz.
            "        b = texture2D(uCameraTex, odMap(cB, rectSize, fB, pixAspect, th - ctr, uOd2.y, yOut, 1.0, uOd3.z, uOd3.x, uOd3.y));\n" +
            "    }\n" +
            // STRAIGHT (non-premultiplied) blended color in .rgb, coverage in .a.
            // The BS card path does lighting on straight color then premultiplies
            // ONCE by the final alpha, so no-coverage regions are TRANSPARENT (map
            // shows through) — not opaque black, and no specular leaks at alpha 0.
            // cov is binary (max of two step()s).
            "    return vec4(mix(a, b, wB).rgb, cov);\n" +
            "}\n" +
            // Blind-spot card mask params (views 7/8 only). aspect = outputWidth/
            // outputHeight so the rounded corners are circular, not stretched.
            // uBsRadius = corner radius as a fraction of the SHORTER half-axis
            // (0=square, 1=stadium); uBsFeather ≈ 1.5px for a clean AA edge. The
            // coverage itself is computed inline in the 7/8 branches from the
            // inset card SDF (bsRoundBoxSDF) so the same field drives the curved
            // bevel — see the framing helpers below.
            "uniform float uBsRadius;\n" +
            "uniform float uBsAspect;\n" +
            "uniform float uBsFeather;\n" +
            // Blind-spot merge mode (views 7/8): 0 = both (rear+side stitch, default),
            // 1 = side camera only, 2 = rear camera only. Single-camera modes bypass
            // odBlend's two-tap blend and fill the whole card from one tap's full FOV.
            "uniform int uBsMergeMode;\n" +
            // ── 3D "curved glass card" framing (views 7/8) ───────────────────────
            // The video stays flat + uniform-scale; only the EDGE reads as 3D.
            // One analytic rounded-box SDF (IQ form) drives card coverage + a lit,
            // curved bevel rim with a thin specular edge — all in a single fragment
            // pass, NO derivatives. No drop shadow. ES 2.0 / Chrome-58 safe.
            "uniform float uBsMargin;\n" +      // card inset per side (buffer fraction)
            "uniform float uBsBevelW;\n" +      // bevel band width (aspect-space SDF units)
            "uniform float uBsBevelLight;\n" +  // top-rim brighten gain
            "uniform float uBsBevelDark;\n" +   // bottom-rim darken gain
            "uniform vec2  uBsLightDir;\n" +    // light dir in aspect space (top-lit)
            "uniform float uBsSpecW;\n" +       // specular band half-width
            "uniform float uBsSpecInt;\n" +     // specular add intensity
            // Fisheye/lens dewarp for the single-camera passthrough (merge 1/2). Same
            // two-parameter division model the recorder uses. Identity at k1=k2=0.
            "uniform float uBsRectifyK1;\n" +
            "uniform float uBsRectifyK2;\n" +
            "uniform float uBsRectifyAspect;\n" +   // tile height/width for the radial metric
            // Dewarp a 0..1 quadrant tile coord: pull each output pixel toward centre by
            // r_source = r_out / (1 + k1·r² + k2·r⁴), then zoom-to-fill so the corners
            // still reach the tile edge (no black border). Aspect makes the iso-distortion
            // contour circular in true pixel space. Identity when k1=k2=0. Mirrors
            // GpuMosaicRecorder.rectifyTile so BS single-cam matches the recording dewarp.
            "vec2 bsRectifyTile(vec2 t) {\n" +
            "    if (uBsRectifyK1 <= 0.0 && uBsRectifyK2 <= 0.0) return t;\n" +
            "    vec2 nxy = t * 2.0 - 1.0;\n" +
            "    vec2 nxyAspect = vec2(nxy.x, nxy.y * uBsRectifyAspect);\n" +
            "    float r2 = dot(nxyAspect, nxyAspect);\n" +
            "    float r4 = r2 * r2;\n" +
            "    float invDenom = 1.0 / (1.0 + uBsRectifyK1 * r2 + uBsRectifyK2 * r4);\n" +
            "    float aspect2 = uBsRectifyAspect * uBsRectifyAspect;\n" +
            "    float aspect4 = aspect2 * aspect2;\n" +
            "    float zoom = 1.0 + uBsRectifyK1 * aspect2 + uBsRectifyK2 * aspect4;\n" +
            "    vec2 srcAspect = (nxyAspect * invDenom) * zoom;\n" +
            "    vec2 srcTile = vec2(srcAspect.x, srcAspect.y / uBsRectifyAspect);\n" +
            "    return clamp(srcTile * 0.5 + 0.5, vec2(0.0), vec2(1.0));\n" +
            "}\n" +
            // Full IQ signed-distance to a rounded box (outside>0, inside<0). Reused
            // for card coverage and the curved bevel band.
            "float bsRoundBoxSDF(vec2 p, vec2 he, float r) {\n" +
            "    vec2 d = abs(p) - (he - vec2(r));\n" +
            "    return length(max(d, 0.0)) - r + min(max(d.x, d.y), 0.0);\n" +
            "}\n" +
            // Analytic outward normal (== gradient direction) of the rounded-box
            // field, WITHOUT dFdx/dFdy. Outer/edge region: radial s*normalize(max(q,0));
            // inner core: the dominant min-plane axis. One normalize, no taps.
            "vec2 bsRoundBoxNormal(vec2 p, vec2 he, float r) {\n" +
            "    vec2 q = abs(p) - (he - vec2(r));\n" +
            "    vec2 s = sign(p);\n" +
            "    vec2 outer = max(q, 0.0);\n" +
            "    if (outer.x + outer.y > 1e-6) {\n" +
            "        return s * normalize(outer + vec2(1e-6));\n" +
            "    }\n" +
            "    return (q.x > q.y) ? vec2(s.x, 0.0) : vec2(0.0, s.y);\n" +
            "}\n" +
            // Curved glass bezel: light the rim band of the inset card so the edge
            // reads as a raised, rounded piece of glass — top rim catches the light,
            // bottom rim falls into shadow (light from above), thin specular on the
            // top arc. The bevel weight ramps QUADRATICALLY from the edge inward so
            // the brightness rolls off like a curved surface, not a flat chamfer.
            // 'sd' is the card SDF (negative inside); p is the aspect-space position.
            "vec3 bsApplyRim(vec3 rgb, vec2 p, vec2 cardHe, float cardR, float sd) {\n" +
            "    vec2  nrm = bsRoundBoxNormal(p, cardHe, cardR);\n" +
            "    float t = clamp((-sd) / max(uBsBevelW, 1e-4), 0.0, 1.0);\n" +  // 0 at edge → 1 inward
            "    float curve = (1.0 - t) * (1.0 - t);\n" +   // quadratic roll-off = rounded profile
            "    float bevel = curve * step(-uBsBevelW - uBsFeather, sd);\n" +
            "    float rim  = dot(nrm, uBsLightDir);\n" +
            "    float lit  = bevel * max(rim, 0.0) * uBsBevelLight;\n" +   // top edge brightens
            "    float shad = bevel * max(-rim, 0.0) * uBsBevelDark;\n" +   // bottom edge darkens
            "    rgb = clamp(rgb * (1.0 + lit - shad), 0.0, 1.0);\n" +
            "    float specBand = 1.0 - smoothstep(0.0, uBsSpecW, abs(sd));\n" +
            "    float spec = specBand * max(-nrm.y, 0.0) * uBsSpecInt;\n" +  // thin top highlight
            "    return clamp(rgb + vec3(spec), 0.0, 1.0);\n" +
            "}\n" +
            "void main() {\n" +
            "    // View 6 with OEM bound: sample the OEM camera external\n" +
            "    // texture using its own transform matrix on the\n" +
            "    // un-transformed unit-quad coord (vUnit). The matrix\n" +
            "    // returned by SurfaceTexture.getTransformMatrix already\n" +
            "    // encodes the producer's Y-flip — applying a manual\n" +
            "    // 1.0 - vUnit.y on top double-flips and lands the feed\n" +
            "    // upside-down. Sample with the raw vUnit.\n" +
            "    if (uViewMode == 6 && uOemActive == 1) {\n" +
            "        vec2 oemTc = (uOemTexMatrix * vec4(vUnit, 0.0, 1.0)).xy;\n" +
            "        gl_FragColor = texture2D(uOemTex, oemTc);\n" +
            "        return;\n" +
            "    }\n" +
            // During an OEM restart the requested DVR mode can outlive its
            // source by one or more frames. Never fall through to the AVM
            // mosaic while view 6 has no valid external texture.
            "    if (uViewMode == 6) {\n" +
            "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
            "        return;\n" +
            "    }\n" +
            "    vec2 samplePos;\n" +
            "    float frontOffset = %.5f;\n" +
            "    float rightOffset = %.5f;\n" +
            "    float rearOffset  = %.5f;\n" +
            "    float leftOffset  = %.5f;\n" +
            "    if (uApaMode > 2.5 && uViewMode == 0) {\n" +
            "        // DiLink 4 mosaic: rearrange the HAL's 2x2 into the\n" +
            "        // canonical Front=TL / Right=TR / Rear=BL / Left=BR\n" +
            "        // arrangement with per-role X/Y flip applied inside the\n" +
            "        // role's 0.5×0.5 slot. Mirrors GpuMosaicRecorder so the\n" +
            "        // live stream matches the recording.\n" +
            "        bool inRight = (vTexCoord.x >= 0.5 && vTexCoord.y <  0.5);\n" +
            "        bool inRear  = (vTexCoord.x <  0.5 && vTexCoord.y >= 0.5);\n" +
            "        bool inLeft  = (vTexCoord.x >= 0.5 && vTexCoord.y >= 0.5);\n" +
            "        vec2 localOffset = vec2(0.0);\n" +
            "        if (inRight) localOffset = vec2(0.5, 0.0);\n" +
            "        else if (inRear) localOffset = vec2(0.0, 0.5);\n" +
            "        else if (inLeft) localOffset = vec2(0.5, 0.5);\n" +
            "        vec2 local = vTexCoord - localOffset;\n" +
            "        vec2 producerCorner = uProducerForFront;\n" +
            "        vec2 flip = uFlipForFront;\n" +
            "        if (inRight) { producerCorner = uProducerForRight; flip = uFlipForRight; }\n" +
            "        else if (inRear)  { producerCorner = uProducerForRear;  flip = uFlipForRear;  }\n" +
            "        else if (inLeft)  { producerCorner = uProducerForLeft;  flip = uFlipForLeft;  }\n" +
            "        vec2 sampledLocal = local;\n" +
            "        if (flip.x > 0.5) sampledLocal.x = 0.5 - sampledLocal.x;\n" +
            "        if (flip.y > 0.5) sampledLocal.y = 0.5 - sampledLocal.y;\n" +
            "        samplePos = producerCorner + sampledLocal;\n" +
            com.overdrive.app.camera.GlUtil.APA_CENTER_INSET_GLSL +
            "    } else if (uApaMode > 2.5 && uViewMode >= 1 && uViewMode <= 4) {\n" +
            "        // DiLink 4 single-direction view: pick the role's\n" +
            "        // producer corner + flip and stretch to the output.\n" +
            "        // Front=1, Right=2, Rear=3, Left=4.\n" +
            "        vec2 corner = uProducerForFront;\n" +
            "        vec2 flip = uFlipForFront;\n" +
            "        if (uViewMode == 2) { corner = uProducerForRight; flip = uFlipForRight; }\n" +
            "        else if (uViewMode == 3) { corner = uProducerForRear;  flip = uFlipForRear;  }\n" +
            "        else if (uViewMode == 4) { corner = uProducerForLeft;  flip = uFlipForLeft;  }\n" +
            "        vec2 sampledLocal = vec2(vTexCoord.x * 0.5, vTexCoord.y * 0.5);\n" +
            "        if (flip.x > 0.5) sampledLocal.x = 0.5 - sampledLocal.x;\n" +
            "        if (flip.y > 0.5) sampledLocal.y = 0.5 - sampledLocal.y;\n" +
            "        samplePos = corner + sampledLocal;\n" +
            com.overdrive.app.camera.GlUtil.APA_CENTER_INSET_GLSL +
            "    } else if (uApaMode > 2.5 && (uViewMode == 7 || uViewMode == 8)) {\n" +
            "        // View 7/8 sampler (DiLink 4).\n" +
            "        //   mode 7 (camera blend mode L) = sideSign -1\n" +
            "        //   mode 8 (camera blend mode R) = sideSign +1\n" +
            "        // Camera identity is ground-truthed by the single-view modes that\n" +
            "        // already render correctly: mode 4 (LEFT) reads uProducerForLeft,\n" +
            "        // mode 2 (RIGHT) reads uProducerForRight. So uProducerForLeft IS\n" +
            "        // the left cam; no swap.\n" +
            "        bool isLeftBtn = (uViewMode == 7);\n" +
            "        vec2  sideCorner = isLeftBtn ? uProducerForLeft : uProducerForRight;\n" +
            "        vec2  sideFlip   = isLeftBtn ? uFlipForLeft     : uFlipForRight;\n" +
            "        float sideSign   = isLeftBtn ? -1.0 : 1.0;\n" +
            // 3D curved glass card: inset the video into the card region (uniform
            // scale, no distortion) and light the curved bevel rim. One analytic
            // SDF drives coverage + bevel. Alpha is gated on od coverage so the
            // map shows through any region the projection doesn't fill.
            "        vec2 he     = vec2(uBsAspect, 1.0) * 0.5;\n" +
            "        vec2 p      = (vTexCoord - 0.5) * vec2(uBsAspect, 1.0);\n" +
            "        float mh    = min(he.x, he.y);\n" +
            "        float rr    = clamp(uBsRadius, 0.0, 1.0) * mh;\n" +
            "        vec2 mrg    = vec2(uBsMargin * uBsAspect, uBsMargin);\n" +
            "        vec2 cardHe = he - mrg;\n" +
            "        float cardR = min(rr, min(cardHe.x, cardHe.y));\n" +
            "        float sd    = bsRoundBoxSDF(p, cardHe, cardR);\n" +
            "        float bsCov = 1.0 - smoothstep(-uBsFeather, uBsFeather, sd);\n" +
            "        vec3 rgb = vec3(0.0);\n" +
            "        float vidCov = 0.0;\n" +    // od coverage; 0 where the projection has no pixels
            "        if (bsCov > 0.0029) {\n" +  // skip the sampler outside the card body
            "            vec2 cardUv = clamp((vTexCoord - uBsMargin) / max(1.0 - 2.0 * uBsMargin, 1e-4), 0.0, 1.0);\n" +
            "            vec4 bsCol = vec4(0.0);\n" +
            // Merge mode 1 = SIDE only, 2 = REAR only: CLEAN PASSTHROUGH. Show the
            // one camera's RAW quadrant with NO od dewarp — no FOV/tilt/height/spread
            // shaping, no libod coefficients. This samples exactly like the
            // ground-truth-correct single-view modes (mode 2 = RIGHT reads
            // uProducerForRight, mode 4 = LEFT reads uProducerForLeft, mode 3 = REAR
            // reads uProducerForRear): corner + cardUv*0.5, with the SAME per-camera
            // flip flags those modes apply, so the feed is never mirrored/upside-down.
            // The stitch tuning sliders only shape the merged 'both' view; single-cam
            // is a plain camera passthrough by design. Coverage is a constant 1.0 (the
            // camera always fills the card body); the rounded-corner transparency still
            // comes from bsCov below. Default (0) keeps the merged rear+side blend.
            "            if (uBsMergeMode == 1) {\n" +
            "                vec2 sl = bsRectifyTile(cardUv) * 0.5;\n" +   // fisheye dewarp then quadrant-scale
            "                if (sideFlip.x > 0.5) sl.x = 0.5 - sl.x;\n" +
            "                if (sideFlip.y > 0.5) sl.y = 0.5 - sl.y;\n" +
            "                bsCol = vec4(texture2D(uCameraTex, sideCorner + sl).rgb, 1.0);\n" +
            "            } else if (uBsMergeMode == 2) {\n" +
            "                vec2 sl = bsRectifyTile(cardUv) * 0.5;\n" +
            "                if (uFlipForRear.x > 0.5) sl.x = 0.5 - sl.x;\n" +
            "                if (uFlipForRear.y > 0.5) sl.y = 0.5 - sl.y;\n" +
            "                bsCol = vec4(texture2D(uCameraTex, uProducerForRear + sl).rgb, 1.0);\n" +
            "            } else {\n" +
            "                bsCol = odBlend(uProducerForRear, uFlipForRear,\n" +
            "                               sideCorner, sideFlip,\n" +
            "                               vec2(0.5), 0.5,\n" +
            "                               sideSign, cardUv.x, cardUv.y);\n" +
            "            }\n" +
            "            vidCov = bsCol.a;\n" +   // 1 where video covers, 0 where it doesn't
            "            rgb = bsApplyRim(bsCol.rgb, p, cardHe, cardR, sd);\n" +
            "        }\n" +
            // Alpha = card coverage × video coverage: rounded corners AND any region
            // the od projection doesn't fill are TRANSPARENT (map shows through), not
            // opaque black. No drop shadow. rgb is STRAIGHT (lit) color; premultiply
            // ONCE by the final alpha so corner AA + edge specular never leak color
            // where alpha is 0.
            "        float outA = bsCov * vidCov;\n" +
            "        gl_FragColor = vec4(rgb * outA, outA);\n" +
            "        return;\n" +
            "    } else if (uViewMode == 5) {\n" +
            "        samplePos = vTexCoord;\n" +
            "    } else if (uApaMode > 1.5) {\n" +
            "        if (uViewMode == 1) { samplePos = vec2(0.75 + vTexCoord.x * 0.25, vTexCoord.y); }\n" +
            "        else if (uViewMode == 3) { samplePos = vec2(vTexCoord.x * 0.25, vTexCoord.y); }\n" +
            "        else if (uViewMode == 2 || uViewMode == 4) { samplePos = vec2(0.25 + vTexCoord.x * 0.5, vTexCoord.y); }\n" +
            "        else {\n" +
            "            if (vTexCoord.x < 0.5) {\n" +
            "                float lx = vTexCoord.x * 0.5;\n" +
            "                float ly = mod(vTexCoord.y, 0.5) * 2.0;\n" +
            "                if (vTexCoord.y < 0.5) { samplePos = vec2(lx + 0.75, ly); }\n" +
            "                else { samplePos = vec2(lx, ly); }\n" +
            "            } else {\n" +
            "                samplePos = vec2(0.25 + (vTexCoord.x - 0.5) * 0.5, vTexCoord.y);\n" +
            "            }\n" +
            "        }\n" +
            "    } else if (uApaMode > 0.5) {\n" +
            "        samplePos = vTexCoord;\n" +
            "    } else if (uViewMode == 0) {\n" +
            "        vec2 gridPos = step(0.5, vTexCoord);\n" +
            "        float stripOffsetX;\n" +
            "        if (gridPos.x < 0.5) {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? frontOffset : rearOffset;\n" +
            "        } else {\n" +
            "            stripOffsetX = gridPos.y < 0.5 ? rightOffset : leftOffset;\n" +
            "        }\n" +
            "        float localX = mod(vTexCoord.x, 0.5) * 0.5;\n" +
            "        float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +
            "        samplePos = vec2(localX + stripOffsetX, localY);\n" +
            "    } else if (uViewMode == 7 || uViewMode == 8) {\n" +
            "        // View 7/8 sampler (legacy 4-strip).\n" +
            "        //   mode 7 (camera blend mode L) = sideSign -1\n" +
            "        //   mode 8 (camera blend mode R) = sideSign +1\n" +
            "        // Camera identity per single-view modes (2=Right->rightOffset,\n" +
            "        // 4=Left->leftOffset). So left btn reads leftOffset; no swap.\n" +
            "        bool isLeftBtn = (uViewMode == 7);\n" +
            "        float sideX    = isLeftBtn ? leftOffset : rightOffset;\n" +
            "        float sideSign = isLeftBtn ? -1.0 : 1.0;\n" +
            // 3D curved glass card (legacy 4-strip variant): same framing math as
            // the DiLink-4 branch, only the odBlend arg list differs. Separate GLSL
            // block scope, so the bs* locals are re-declared cleanly (no clash).
            "        vec2 he     = vec2(uBsAspect, 1.0) * 0.5;\n" +
            "        vec2 p      = (vTexCoord - 0.5) * vec2(uBsAspect, 1.0);\n" +
            "        float mh    = min(he.x, he.y);\n" +
            "        float rr    = clamp(uBsRadius, 0.0, 1.0) * mh;\n" +
            "        vec2 mrg    = vec2(uBsMargin * uBsAspect, uBsMargin);\n" +
            "        vec2 cardHe = he - mrg;\n" +
            "        float cardR = min(rr, min(cardHe.x, cardHe.y));\n" +
            "        float sd    = bsRoundBoxSDF(p, cardHe, cardR);\n" +
            "        float bsCov = 1.0 - smoothstep(-uBsFeather, uBsFeather, sd);\n" +
            "        vec3 rgb = vec3(0.0);\n" +
            "        float vidCov = 0.0;\n" +
            "        if (bsCov > 0.0029) {\n" +
            "            vec2 cardUv = clamp((vTexCoord - uBsMargin) / max(1.0 - 2.0 * uBsMargin, 1e-4), 0.0, 1.0);\n" +
            "            vec4 bsCol = vec4(0.0);\n" +
            // Same merge-mode switch as the DiLink-4 branch — single-cam modes are a
            // CLEAN PASSTHROUGH of one 4-strip slice with NO od dewarp, sampled exactly
            // like the ground-truth-correct legacy single-view modes (mode 2 = RIGHT
            // reads rightOffset, mode 4 = LEFT reads leftOffset, mode 3 = REAR reads
            // rearOffset): startX + cardUv.x*0.25 across the 0.25-wide strip, full
            // height, no flip (those modes apply none). The stitch tuning sliders only
            // shape the merged 'both' view. Mode 0 keeps the merged rear+side blend.
            "            if (uBsMergeMode == 1) {\n" +
            "                vec2 rc = bsRectifyTile(cardUv);\n" +   // fisheye dewarp (identity at 0)
            "                bsCol = vec4(texture2D(uCameraTex, vec2(sideX + rc.x * 0.25, rc.y)).rgb, 1.0);\n" +
            "            } else if (uBsMergeMode == 2) {\n" +
            "                vec2 rc = bsRectifyTile(cardUv);\n" +
            "                bsCol = vec4(texture2D(uCameraTex, vec2(rearOffset + rc.x * 0.25, rc.y)).rgb, 1.0);\n" +
            "            } else {\n" +
            "                bsCol = odBlend(vec2(rearOffset, 0.0), vec2(0.0),\n" +
            "                               vec2(sideX, 0.0),      vec2(0.0),\n" +
            "                               vec2(0.25, 1.0), 0.5,\n" +
            "                               sideSign, cardUv.x, cardUv.y);\n" +
            "            }\n" +
            "            vidCov = bsCol.a;\n" +
            "            rgb = bsApplyRim(bsCol.rgb, p, cardHe, cardR, sd);\n" +
            "        }\n" +
            "        float outA = bsCov * vidCov;\n" +   // transparent corners + no-coverage
            "        gl_FragColor = vec4(rgb * outA, outA);\n" +   // premultiplied once
            "        return;\n" +
            "    } else {\n" +
            "        float startX = frontOffset;\n" +
            "        if (uViewMode == 2) startX = rightOffset;\n" +
            "        else if (uViewMode == 3) startX = rearOffset;\n" +
            "        else if (uViewMode == 4) startX = leftOffset;\n" +
            "        samplePos = vec2(startX + (vTexCoord.x * 0.25), vTexCoord.y);\n" +
            "    }\n" +
            "    vec4 src = texture2D(uCameraTex, samplePos);\n" +
            com.overdrive.app.camera.GlUtil.RED_MASK_GLSL +
            "    gl_FragColor = src;\n" +
            "}\n",
            offsets[0], offsets[1], offsets[2], offsets[3]);
    }
}
