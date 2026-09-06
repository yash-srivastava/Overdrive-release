package com.overdrive.app.surveillance;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.overdrive.app.camera.GlUtil;
import com.overdrive.app.logging.DaemonLogger;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * FoveatedCropper — High-resolution targeted AI crop from the raw camera strip.
 *
 * Implements the "foveated vision" pattern: the low-res 640×480 mosaic acts as
 * peripheral vision to detect WHERE motion is happening, then this cropper
 * extracts a 640×640 window directly from the raw 5120×960 camera strip at
 * full resolution, centered on the motion centroid.
 *
 * SOTA Tier 2 async readback (GLES 3.0 PBO + fence-sync ring):
 *   The previous double-FBO ping-pong reduced the GPU-side stall but
 *   still issued a synchronous {@code glReadPixels} into a Java direct
 *   ByteBuffer — on Adreno 610 that path implicitly joins the OpenCL
 *   command queue when YOLO inference is in flight, even when the
 *   target FBO has already drained.
 *
 *   This implementation pipelines the readback through a small ring of
 *   {@code GL_PIXEL_PACK_BUFFER} objects:
 *     1. Render the new (quadrant, centroid) into the FBO.
 *     2. Issue {@code glReadPixels} with a PBO bound — the driver queues
 *        a DMA into the PBO and returns immediately (no host wait, no
 *        OpenCL barrier).
 *     3. Drop a {@code glFenceSync} immediately after.
 *     4. On a future call, poll the OLDEST in-flight fence with
 *        {@code glClientWaitSync(timeout=0)}. If signaled, map the PBO,
 *        memcpy out, unmap. If not signaled, return null and try again
 *        next call — no blocking.
 *
 *   Result struct carries the quadrant the readback bytes correspond
 *   to (which is N-RING_SIZE+1 frames behind the request) so the caller
 *   publishes to the correct slot.
 */
public class FoveatedCropper {
    private static final DaemonLogger logger = DaemonLogger.getInstance("FoveatedCrop");

    public static final int CROP_SIZE = 640;

    // Source strip dimensions (runtime-resolved per camera profile).
    // Seal = 5120x960, Tang = 5120x720. Per-camera tile is stripWidth/4.
    private final int stripWidth;
    private final int stripHeight;
    private final float[] quadrantStripOffsetX;
    // 8 floats: {fX,fY, rX,rY, bX,bY, lX,lY} — top-left of each role's
    // 0.5×0.5 corner in a 2x2-native HAL frame (DiLink 4 path). Used when
    // cameraLayout == 3. Mutable so the pipeline can override with the
    // car's actual layout (Variant A) post-construction.
    private final float[] quadrantCornerOffsetsXY;
    // 8 floats: {fXFlip,fYFlip, rXFlip,rYFlip, bXFlip,bYFlip, lXFlip,lYFlip}.
    // 1.0 = mirror inside the role's local 0.5×0.5 region. Used to map the
    // motion-V2 centroid onto the right producer pixels when the HAL emits
    // a flipped tile per role.
    private final float[] flipFlags = {
        0f, 0f,  0f, 0f,  0f, 0f,  0f, 0f
    };
    private final Object cornerMapLock = new Object();
    // APA center inset — read under cornerMapLock alongside the producer
    // map and flip flags. 0 = no crop (default / legacy). On dilink4 the
    // pipeline pushes the producer-UV inset (e.g. 240/2560 = 0.09375).
    private volatile float apaCenterInset = 0.0f;
    // 0 = legacy 4-strip; 1 = full-frame; 3 = oem-parity 2x2 remap.
    // Volatile because the GL thread reads it inside crop() and the camera
    // thread (or pipeline init) writes it via setCameraLayout.
    private volatile int cameraLayout = 0;

    private int program = -1;
    private int aPosition = -1;
    private int aTexCoord = -1;
    private int uCameraTex = -1;
    private int uCropRect = -1;
    private int uRedMaskStrength = -1;
    private volatile boolean redMaskEnabled = false;

    // The cropper does not consume a SurfaceTexture transform matrix —
    // its samples are computed in producer-space UV directly via the
    // role's corner remap + per-role flip. See the FRAGMENT_SHADER comment
    // for why.

    // Single render FBO — readbacks pipeline through PBOs, not FBOs, so we
    // no longer need a ping-pong pair. The driver still does internal frame
    // queueing for the FBO target itself.
    private int fboId = -1;
    private int fboTexture = -1;

    // PBO ring — three slots. RING_SIZE = 3 lets the GPU keep at most two
    // readbacks in flight while a third PBO is being mapped on the CPU,
    // without ever forcing a sync. At V2's 10 Hz motion cadence and our
    // 150 ms service throttle, two-in-flight is more than enough headroom.
    private static final int RING_SIZE = 3;
    private final int[] pboIds = new int[RING_SIZE];
    private final long[] fenceSyncs = new long[RING_SIZE];        // 0 = unused
    private final int[] pboQuadrant = new int[RING_SIZE];          // -1 = unused
    // Foveated→block-grid affine {mapAx,mapBx,mapAy,mapBy} captured at QUEUE
    // time (when crop() renders window N into slot ringHead). RING_SIZE=3 means
    // the readback that COMPLETES belongs to an EARLIER crop() call's window,
    // so the rect MUST be stored per-slot here and read back at drain time —
    // not recomputed from the current call's centroid. Mirrors fenceSyncs[] /
    // pboQuadrant[] exactly (same head/tail lifecycle).
    private final float[][] ringCropAffine = new float[RING_SIZE][4];
    // CAPTURE timestamp (System.nanoTime at QUEUE time) per slot (audit R4
    // ExtB-8): the ring lag (1-2 service intervals ≈ 150-300ms+) was
    // invisible to consumers because the slot's freshness stamp was taken
    // at PUBLISH time — total pixel age could reach ~0.8s while passing a
    // 500ms staleness check. Stamped/cleared with the same head/tail
    // lifecycle as ringCropAffine.
    private final long[] ringCaptureNanos = new long[RING_SIZE];
    private static final int PBO_BYTES = CROP_SIZE * CROP_SIZE * 4;
    // Head = next slot to render+queue into; tail = next slot to attempt
    // readback from. The ring is empty when head == tail and slots in
    // [tail..head) are in flight.
    private int ringHead = 0;
    private int ringTail = 0;

    private byte[] scratchRgba = null;   // bulk-copy RGBA scratch (single JNI hop)
    private byte[] rgbBuffer = null;
    private boolean initialized = false;
    // True iff the live GL context is GLES 3.x — checked once at init via
    // glGetString(GL_VERSION). The PBO + fence-sync path requires GLES 3
    // entry points; on a GLES 2 fallback we can't pipeline the readback
    // and have to fall back to a synchronous glReadPixels into a direct
    // ByteBuffer. Modern Adreno (Adreno 600 family on DiLink 5) is always
    // GLES 3.2; the fallback exists so the code is robust against a hostile
    // EGL config rejection on unknown hardware.
    private boolean gles3Available = false;
    // GLES2 fallback scratch — only allocated if gles3Available is false.
    private ByteBuffer fallbackReadBuffer = null;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // The cropper samples the OES texture directly using a producer-space
    // crop rect computed CPU-side from the role's corner + per-role flip.
    // Unlike the recorder/stream/sampler, the cropper does NOT need to
    // apply uTexMatrix in either shader: the HAL-marked live region is
    // already factored into the corner remap (Variant A constants), and
    // the cropper's output is bytes that go straight into YOLO — no
    // composite step where a different consumer would re-interpret the
    // sample positions. Keeping the matrix out avoids the double-flip
    // that bit us when PanoramicCameraGpu auto-injected a Y-flip into
    // currentTexMatrix for dilink4.
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES uCameraTex;\n" +
        "uniform vec4 uCropRect;\n" +
        "uniform float uRedMaskStrength;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    vec2 samplePos = vec2(\n" +
        "        uCropRect.x + vTexCoord.x * uCropRect.z,\n" +
        "        uCropRect.y + vTexCoord.y * uCropRect.w\n" +
        "    );\n" +
        "    vec4 src = texture2D(uCameraTex, samplePos);\n" +
        com.overdrive.app.camera.GlUtil.RED_MASK_GLSL +
        "    gl_FragColor = src;\n" +
        "}\n";

    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };

    private static final float[] TEX_COORDS = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    };

    private static final float[] DEFAULT_QUADRANT_STRIP_OFFSET_X = {
        0.75f,  // Q0 (TL → Front)
        0.50f,  // Q1 (TR → Right)
        0.00f,  // Q2 (BL → Rear)
        0.25f   // Q3 (BR → Left)
    };

    // 2x2 corner offsets for DiLink 4 HAL: {fX,fY, rX,rY, bX,bY, lX,lY}.
    // Mirrors GpuMosaicRecorder layout (Front=TL, Right=TR, Rear=BL, Left=BR).
    private static final float[] DEFAULT_QUADRANT_CORNER_OFFSETS_XY = {
        0.00f, 0.00f,  // Front (TL)
        0.50f, 0.00f,  // Right (TR)
        0.00f, 0.50f,  // Rear  (BL)
        0.50f, 0.50f   // Left  (BR)
    };

    public FoveatedCropper() {
        this(5120, 960, DEFAULT_QUADRANT_STRIP_OFFSET_X,
             DEFAULT_QUADRANT_CORNER_OFFSETS_XY);
    }

    public FoveatedCropper(int stripWidth, int stripHeight) {
        this(stripWidth, stripHeight, DEFAULT_QUADRANT_STRIP_OFFSET_X,
             DEFAULT_QUADRANT_CORNER_OFFSETS_XY);
    }

    public FoveatedCropper(int stripWidth, int stripHeight, float[] quadrantStripOffsetX) {
        this(stripWidth, stripHeight, quadrantStripOffsetX,
             DEFAULT_QUADRANT_CORNER_OFFSETS_XY);
    }

    private final boolean isTexture2D;

    /**
     * @param quadrantStripOffsetX Per-role X offsets for legacy 4-strip
     *     HAL. {Front, Right, Rear, Left}.
     * @param quadrantCornerOffsetsXY Per-role (cornerX, cornerY) for the
     *     0.5×0.5 corner in a 2x2-native HAL frame. {fX,fY, rX,rY, bX,bY,
     *     lX,lY}. Used when cameraLayout == 3.
     */
    public FoveatedCropper(int stripWidth, int stripHeight,
                           float[] quadrantStripOffsetX,
                           float[] quadrantCornerOffsetsXY) {
        this(stripWidth, stripHeight, quadrantStripOffsetX, quadrantCornerOffsetsXY, false);
    }

    public FoveatedCropper(int stripWidth, int stripHeight,
                           float[] quadrantStripOffsetX,
                           float[] quadrantCornerOffsetsXY,
                           boolean isTexture2D) {
        this.isTexture2D = isTexture2D;
        this.stripWidth = Math.max(1, stripWidth);
        this.stripHeight = Math.max(1, stripHeight);
        this.quadrantStripOffsetX = (quadrantStripOffsetX != null && quadrantStripOffsetX.length == 4)
            ? quadrantStripOffsetX.clone()
            : DEFAULT_QUADRANT_STRIP_OFFSET_X.clone();
        this.quadrantCornerOffsetsXY =
            (quadrantCornerOffsetsXY != null && quadrantCornerOffsetsXY.length == 8)
                ? quadrantCornerOffsetsXY.clone()
                : DEFAULT_QUADRANT_CORNER_OFFSETS_XY.clone();
    }

    /** Result of a crop call. {@link #rgb} may be null on the very first
     *  invocation (no previous frame to read back) — caller falls back to mosaic.
     *
     *  <p>Affine mapping foveated-pixel → 320×240 quadrant-block-grid:
     *  a detection bbox coordinate {@code fx} (in this crop's 640-pixel native
     *  space) maps to the motion block grid via
     *  {@code blockGridX = mapAx * fx + mapBx}, and likewise
     *  {@code blockGridY = mapAy * fy + mapBy}. These coefficients FOLD IN the
     *  crop window's scale, origin offset within the quadrant, the per-role
     *  flip, and the APA inset — everything {@link #crop} knows about the
     *  producer layout — so the consumer never re-derives layout. They travel
     *  WITH the pixels through the async ring, so the bbox is always mapped
     *  using the SAME window the pixels came from (see class doc: the readback
     *  that completes belongs to an EARLIER crop() call's window). */
    public static final class Result {
        public final byte[] rgb;
        public final int quadrant;
        public final int width;
        public final int height;
        // Affine coefficients: blockGrid = mapA*foveatedPx + mapB (320×240 space).
        public final float mapAx;
        public final float mapBx;
        public final float mapAy;
        public final float mapBy;
        // CAPTURE time (System.nanoTime at queue/render time — audit R4
        // ExtB-8). 0 = unknown (legacy ctor); consumers fall back to
        // publish-time aging.
        public final long captureNanos;
        public Result(byte[] rgb, int quadrant, int w, int h) {
            this(rgb, quadrant, w, h,
                 // Identity-ish fallback (only reached if a legacy caller uses
                 // the old ctor). 0.5 scale + no origin ≈ the OLD broken math,
                 // but the consumer FAILS SAFE (keep detection) when it detects
                 // an un-populated affine via the hasAffine() sentinel below.
                 0f, 0f, 0f, 0f, 0L);
        }
        public Result(byte[] rgb, int quadrant, int w, int h,
                      float mapAx, float mapBx, float mapAy, float mapBy,
                      long captureNanos) {
            this.rgb = rgb;
            this.quadrant = quadrant;
            this.width = w;
            this.height = h;
            this.mapAx = mapAx;
            this.mapBx = mapBx;
            this.mapAy = mapAy;
            this.mapBy = mapBy;
            this.captureNanos = captureNanos;
        }
        /** True iff a real foveated→block-grid affine was populated. A zero
         *  X-scale (mapAx==0) can never be a legitimate crop mapping (the
         *  window always covers non-zero width), so it doubles as the
         *  "no rect data" sentinel the consumer fail-safes on.
         *
         *  <p>NaN must be rejected explicitly: in IEEE-754 {@code NaN != 0f} is
         *  TRUE, so a NaN-poisoned affine would report VALID and send the
         *  consumer down the strict branch — where every comparison against a
         *  NaN-derived bbox corner is false, no block ever matches, and the
         *  detection is DROPPED. That is the exact failure this sentinel exists
         *  to prevent, reached via NaN instead of zero. NaN is reachable from a
         *  malformed apaCenterInset in JSON config, because
         *  {@code Math.max(0, Math.min(0.20, NaN))} returns NaN in Java and the
         *  poison then propagates through xScale → cropWidthNorm → mX → mapAx.
         *  All four coefficients are checked: the consumer uses every one, and a
         *  NaN in any of them corrupts the mapping identically. */
        public boolean hasAffine() {
            return mapAx != 0f
                    && !Float.isNaN(mapAx) && !Float.isNaN(mapBx)
                    && !Float.isNaN(mapAy) && !Float.isNaN(mapBy);
        }
    }

    public void init() {
        try {
            String fs = isTexture2D
                ? "#extension GL_OES_EGL_image_external : enable\n" +
                  "precision mediump float;\n" +
                  "uniform sampler2D uCameraTex;\n" +
                  "uniform vec4 uCropRect;\n" +
                  "uniform float uRedMaskStrength;\n" +
                  "varying vec2 vTexCoord;\n" +
                  "void main() {\n" +
                  "    vec2 samplePos = vec2(\n" +
                  "        uCropRect.x + vTexCoord.x * uCropRect.z,\n" +
                  "        uCropRect.y + vTexCoord.y * uCropRect.w\n" +
                  "    );\n" +
                  "    vec4 src = texture2D(uCameraTex, samplePos);\n" +
                  com.overdrive.app.camera.GlUtil.RED_MASK_GLSL +
                  "    gl_FragColor = src;\n" +
                  "}\n"
                : FRAGMENT_SHADER;
            program = GlUtil.createProgram(VERTEX_SHADER, fs);
            if (program == 0) {
                logger.error("Foveated crop shader compilation failed");
                return;
            }
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uCameraTex = GLES20.glGetUniformLocation(program, "uCameraTex");
            uCropRect = GLES20.glGetUniformLocation(program, "uCropRect");
            uRedMaskStrength = GLES20.glGetUniformLocation(program, "uRedMaskStrength");

            // One-shot GL version probe. PBO + fence-sync require GLES 3.
            String glVer = GLES20.glGetString(GLES20.GL_VERSION);
            gles3Available = (glVer != null && glVer.contains("OpenGL ES 3"));
            logger.info("FoveatedCropper GL version: '" + glVer + "' "
                    + "(gles3=" + gles3Available + ")");

            // One render FBO. The PBO ring is what gives us pipelining now.
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            fboTexture = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    CROP_SIZE, CROP_SIZE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

            int[] fbo = new int[1];
            GLES20.glGenFramebuffers(1, fbo, 0);
            fboId = fbo[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, fboTexture, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                logger.error("Foveated FBO incomplete: " + status);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                return;
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            if (gles3Available) {
                // PBO ring. STREAM_READ tells the driver these are CPU-readback
                // buffers (vs. STREAM_DRAW for vertex data).
                GLES30.glGenBuffers(RING_SIZE, pboIds, 0);
                for (int i = 0; i < RING_SIZE; i++) {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[i]);
                    GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, PBO_BYTES, null,
                            GLES30.GL_STREAM_READ);
                    fenceSyncs[i] = 0L;
                    pboQuadrant[i] = -1;
                }
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
            } else {
                // GLES2 fallback: synchronous glReadPixels into a direct buffer.
                // No pipelining, but at least the AI-lane GL thread is still
                // separate from the encoder thread — Tier 1's win is preserved.
                fallbackReadBuffer = ByteBuffer.allocateDirect(PBO_BYTES);
                fallbackReadBuffer.order(ByteOrder.nativeOrder());
            }

            scratchRgba = new byte[PBO_BYTES];
            rgbBuffer = new byte[CROP_SIZE * CROP_SIZE * 3];

            vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
            texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);

            ringHead = 0;
            ringTail = 0;

            initialized = true;
            if (gles3Available) {
                logger.info("FoveatedCropper initialized (GLES3 PBO ring x" + RING_SIZE
                        + ", " + CROP_SIZE + "×" + CROP_SIZE + " RGBA, async readback)");
            } else {
                logger.warn("FoveatedCropper initialized (GLES2 fallback — synchronous readback, no PBO pipelining)");
            }
        } catch (Exception e) {
            logger.error("FoveatedCropper init failed: " + e.getMessage());
        }
    }

    /**
     * Submit a crop request and read back the previous one in a single GL pass.
     *
     * Renders the new (quadrant, centroid) into the current FBO, then issues
     * glReadPixels against the OTHER FBO — the one the GPU already finished
     * during the previous call. The readback is non-blocking because the
     * pipeline has drained for that target.
     *
     * Must be called on the GL thread that owns {@code cameraTextureId}.
     *
     * @return Result whose bytes correspond to the PREVIOUS request's quadrant,
     *         or null on the very first call (nothing to read back yet).
     */
    /**
     * Layout-aware foveated AI crop. Branches between:
     *   cameraLayout == 0 → legacy 4-strip: each role is a 0.25-wide
     *       vertical strip indexed by stripOffsetX[quadrant].
     *   cameraLayout == 1 or 3 → SurfaceTexture frame: each motion-grid
     *       quadrant maps to a 0.5×0.5 producer corner.
     * Centroid (centroidX, centroidY) is in V2 motion's single-quadrant
     * local coordinates; we map it into the producer-frame UV based on
     * the active layout.
     */
    public Result crop(int cameraTextureId, int quadrant, float centroidX, float centroidY) {
        if (!initialized || quadrant < 0 || quadrant >= 4) return null;

        float quadPixelX = (centroidX + 0.5f) * 32.0f;
        float quadPixelY = (centroidY + 0.5f) * 32.0f;

        float camNormX = quadPixelX / 320.0f;
        float camNormY = quadPixelY / 240.0f;

        // Cropper's CROP_SIZE is in producer-frame pixels. The producer
        // frame has different geometry on legacy (4-strip 5120×960) and
        // SurfaceTexture layouts — derive crop dims from current layout
        // rather than always assuming `stripWidth × stripHeight`.
        boolean useCornerLayout = (cameraLayout == 1 || cameraLayout == 3);
        float cropWidthNorm;
        float cropHeightNorm;
        float quadLeft, quadTop;
        float quadRight, quadBottom;
        float centerX, centerY;
        // Per-role flip in producer space. Only the corner layout mirrors; the
        // legacy 4-strip path never flips (recorder paints upright strips).
        // Hoisted out of the corner branch so the affine build below sees it.
        float xFlipOut = 0f, yFlipOut = 0f;
        if (useCornerLayout) {
            // 2x2 mosaic: each role lives in a 0.5×0.5 corner of the
            // producer frame. The producer is roughly stripWidth/2 wide and
            // stripHeight*2 tall when the recorder configured 5120×960 →
            // 2560×1920 (or whatever the HAL emits). CROP_SIZE in
            // normalised UV is CROP_SIZE / producer_dim.
            int producerW = Math.max(1, stripWidth / 2);
            int producerH = Math.max(1, stripHeight * 2);
            cropWidthNorm = (float) CROP_SIZE / producerW;
            cropHeightNorm = (float) CROP_SIZE / producerH;
            // Look up role corner + flip. quadrant order matches
            // stripOffsetX: 0=Front, 1=Right, 2=Rear, 3=Left → 2 floats each.
            int base = quadrant * 2;
            float xFlip, yFlip, inset;
            synchronized (cornerMapLock) {
                quadLeft = quadrantCornerOffsetsXY[base];
                quadTop  = quadrantCornerOffsetsXY[base + 1];
                xFlip = flipFlags[base];
                yFlip = flipFlags[base + 1];
                inset = apaCenterInset;
            }
            quadRight = quadLeft + 0.5f;
            quadBottom = quadTop + 0.5f;
            // V2 motion's centroid is in single-quadrant local coords
            // (camNormX/Y in [0,1]) referenced to the canonical
            // (post-rearrange) tile. The producer tile may be flipped, so
            // mirror the centroid before mapping into the 0.5×0.5 corner.
            float localX = (xFlip > 0.5f) ? (1.0f - camNormX) : camNormX;
            float localY = (yFlip > 0.5f) ? (1.0f - camNormY) : camNormY;
            xFlipOut = xFlip;
            yFlipOut = yFlip;
            centerX = quadLeft + localX * 0.5f;
            centerY = quadTop  + localY * 0.5f;
            // APA center inset (oem APACropFilter parity, mirror of
            // GlUtil.APA_CENTER_INSET_GLSL): horizontal-only remap of the
            // FULL producer x: [0, 1] -> [inset, 1 - inset]. Apply to
            // both centerX and the role's x-bounds so the crop window and
            // the centroid stay in lockstep with the GPU samplers.
            if (inset > 0.0001f) {
                float xScale = 1.0f - 2.0f * inset;
                centerX  = inset + centerX  * xScale;
                quadLeft = inset + quadLeft * xScale;
                quadRight = inset + quadRight * xScale;
                cropWidthNorm = cropWidthNorm * xScale;
            }
        } else {
            // Legacy 4-strip: each role is a 0.25-wide vertical strip; full
            // height. Producer is stripWidth × stripHeight as configured.
            cropWidthNorm = (float) CROP_SIZE / stripWidth;
            cropHeightNorm = (float) CROP_SIZE / stripHeight;
            float stripOffsetX = quadrantStripOffsetX[quadrant];
            quadLeft = stripOffsetX;
            quadTop = 0.0f;
            quadRight = stripOffsetX + 0.25f;
            quadBottom = 1.0f;
            centerX = stripOffsetX + camNormX * 0.25f;
            centerY = camNormY;
        }

        float cropLeft = centerX - cropWidthNorm / 2.0f;
        float cropTop = centerY - cropHeightNorm / 2.0f;

        // Clamp the crop window to the role's quadrant bounds so we never
        // sample neighbouring cameras' pixels along the edges.
        cropLeft = Math.max(quadLeft,  Math.min(cropLeft, quadRight  - cropWidthNorm));
        cropTop  = Math.max(quadTop,   Math.min(cropTop,  quadBottom - cropHeightNorm));

        // ---- Build the foveated-pixel → 320×240 block-grid affine ----
        //
        // This INVERTS crop()'s forward transform. The fragment shader samples
        // producer UV as:
        //     producerNormX = cropLeft + (fx/CROP_SIZE) * cropWidthNorm
        //     producerNormY = cropTop  + (fy/CROP_SIZE) * cropHeightNorm
        // (vTexCoord = fx/CROP_SIZE after the readback's Y-flip lands the crop
        //  in top-left-origin pixels — the same convention YOLO/det coords use).
        //
        // Quadrant-local normalised coords come from the role bounds:
        //     quadLocalX = (producerNormX - quadLeft) / (quadRight - quadLeft)
        //     quadLocalY = (producerNormY - quadTop ) / (quadBottom - quadTop)
        // then undo the per-role mirror (corner layout only), then × (320,240).
        //
        // All of cropLeft/cropTop/cropWidthNorm/quadLeft.. are POST-inset and
        // POST-clamp here, so the folded coefficients are exact for the exact
        // window we are about to render. Compose to a single affine so the
        // consumer does only blockGridX = mapAx*fx + mapBx.
        final float qWidthNorm  = Math.max(1e-6f, quadRight  - quadLeft);
        final float qHeightNorm = Math.max(1e-6f, quadBottom - quadTop);
        // quadLocalX = mX*fx + cX ; quadLocalY = mY*fy + cY
        float mX = cropWidthNorm  / (CROP_SIZE * qWidthNorm);
        float cX = (cropLeft - quadLeft) / qWidthNorm;
        float mY = cropHeightNorm / (CROP_SIZE * qHeightNorm);
        float cY = (cropTop  - quadTop)  / qHeightNorm;
        float mapAx, mapBx, mapAy, mapBy;
        if (xFlipOut > 0.5f) {
            // quadLocalX' = 1 - (mX*fx + cX) → ×320
            mapAx = -320.0f * mX;
            mapBx =  320.0f * (1.0f - cX);
        } else {
            mapAx =  320.0f * mX;
            mapBx =  320.0f * cX;
        }
        if (yFlipOut > 0.5f) {
            mapAy = -240.0f * mY;
            mapBy =  240.0f * (1.0f - cY);
        } else {
            mapAy =  240.0f * mY;
            mapBy =  240.0f * cY;
        }
        // A degenerate zero X-scale means the window geometry is corrupt
        // (cropWidthNorm collapsed, or a NaN propagated out of stripWidth /
        // apaCenterInset). mX is cropWidthNorm/(640*qWidth), structurally > 0
        // for any real window, so reaching here means the coefficients cannot
        // be trusted.
        //
        // Leave mapAx AT ZERO so Result.hasAffine() reports false and the
        // consumer FAILS SAFE (keeps the detection). The previous code nudged
        // it to 1e-6f specifically to dodge that sentinel — which inverted the
        // intent: a 1e-6f scale collapses every bbox in the crop onto block
        // column 0, so the motion-overlap filter tested the wrong blocks,
        // found no overlap, and DROPPED the detection. Reporting "no affine"
        // is the whole point of the sentinel; a missed person is worse than an
        // over-inclusive recording.
        if (mapAx == 0f && logger != null) {
            logger.warn("Foveated affine degenerate (mapAx=0, q=" + quadrant
                    + ") — publishing without affine so the consumer fails safe");
        }

        int[] savedViewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);

        // ---- 1. Render the CURRENT request to the FBO ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
        GLES20.glViewport(0, 0, CROP_SIZE, CROP_SIZE);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        if (isTexture2D) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTextureId);
        } else {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        }
        GLES20.glUniform1i(uCameraTex, 0);
        GLES20.glUniform4f(uCropRect, cropLeft, cropTop, cropWidthNorm, cropHeightNorm);
        if (uRedMaskStrength >= 0) {
            GLES20.glUniform1f(uRedMaskStrength, redMaskEnabled ? 1.0f : 0.0f);
        }

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);

        // GLES2 fallback path: read synchronously, return immediately.
        // No pipelining, but Tier 1's separate AI-lane thread already
        // contains the cost away from the encoder thread.
        if (!gles3Available) {
            fallbackReadBuffer.clear();
            GLES20.glReadPixels(0, 0, CROP_SIZE, CROP_SIZE,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, fallbackReadBuffer);
            fallbackReadBuffer.rewind();
            fallbackReadBuffer.get(scratchRgba, 0, PBO_BYTES);
            byte[] src = scratchRgba;
            byte[] dst = rgbBuffer;
            final int rowRgbaBytes = CROP_SIZE * 4;
            int dstIdx = 0;
            for (int y = CROP_SIZE - 1; y >= 0; y--) {
                int srcRow = y * rowRgbaBytes;
                for (int x = 0; x < CROP_SIZE; x++) {
                    int s = srcRow + (x << 2);
                    dst[dstIdx++] = src[s];
                    dst[dstIdx++] = src[s + 1];
                    dst[dstIdx++] = src[s + 2];
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
            // Synchronous path: the bytes ARE this call's window, so the affine
            // computed above is the exact match (and the capture time is now).
            return new Result(dst, quadrant, CROP_SIZE, CROP_SIZE,
                    mapAx, mapBx, mapAy, mapBy, System.nanoTime());
        }

        // ---- 2. Queue an async readback into the next PBO slot ----
        // If the ring is full (head wraps to tail with non-null fence at tail),
        // we either (a) skip the queue this call and only attempt readback,
        // or (b) overwrite the oldest. Skipping is safer — RING_SIZE=3 leaves
        // generous headroom; saturating the ring means the AI lane is
        // calling us faster than we can drain, which only happens if the
        // 150 ms throttle is bypassed. Defensive skip + log.
        int nextHead = (ringHead + 1) % RING_SIZE;
        boolean ringFull = (nextHead == ringTail) && fenceSyncs[ringTail] != 0L;
        if (!ringFull) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[ringHead]);
            // glReadPixels with PBO bound: queues an async DMA of the FBO
            // into the PBO; returns immediately.
            GLES30.glReadPixels(0, 0, CROP_SIZE, CROP_SIZE,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);

            // Drop a fence right after. glClientWaitSync(timeout=0) will
            // tell us when the DMA has landed without blocking.
            fenceSyncs[ringHead] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            pboQuadrant[ringHead] = quadrant;
            // Stamp the affine for THIS window into the same slot the fence +
            // quadrant go into. When this slot later drains, we read it back so
            // the bbox is mapped with the window the pixels actually came from.
            ringCropAffine[ringHead][0] = mapAx;
            ringCropAffine[ringHead][1] = mapBx;
            ringCropAffine[ringHead][2] = mapAy;
            ringCropAffine[ringHead][3] = mapBy;
            ringCaptureNanos[ringHead] = System.nanoTime();  // R4 ExtB-8
            ringHead = nextHead;
        } else {
            // Saturated ring — only attempt drain below.
            if (logger != null) logger.debug("PBO ring full, skipping queue (drain only)");
        }

        // ---- 3. Drain: try to harvest the OLDEST in-flight slot ----
        Result result = null;
        if (ringTail != ringHead && fenceSyncs[ringTail] != 0L) {
            // glClientWaitSync with a 0 timeout is a poll; never blocks.
            // Returns one of:
            //   GL_ALREADY_SIGNALED       — fence already done; map immediately
            //   GL_CONDITION_SATISFIED    — done within the (zero) wait window
            //   GL_TIMEOUT_EXPIRED        — not yet done; try later
            //   GL_WAIT_FAILED            — driver error
            int sig = GLES30.glClientWaitSync(fenceSyncs[ringTail], 0, 0);
            boolean signaled = (sig == GLES30.GL_ALREADY_SIGNALED
                             || sig == GLES30.GL_CONDITION_SATISFIED);
            if (signaled) {
                int q = pboQuadrant[ringTail];
                // Read back the affine stamped when THIS slot was queued (an
                // earlier crop() window than the current call's centroid).
                float rAx = ringCropAffine[ringTail][0];
                float rBx = ringCropAffine[ringTail][1];
                float rAy = ringCropAffine[ringTail][2];
                float rBy = ringCropAffine[ringTail][3];
                // Map the PBO read-only and bulk-copy into our scratch
                // byte[]. glMapBufferRange returns a Buffer whose backing
                // memory is the GPU's PBO storage — direct DMA buffer.
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[ringTail]);
                Buffer mapped = GLES30.glMapBufferRange(
                        GLES30.GL_PIXEL_PACK_BUFFER, 0, PBO_BYTES,
                        GLES30.GL_MAP_READ_BIT);
                if (mapped instanceof ByteBuffer) {
                    ByteBuffer bb = (ByteBuffer) mapped;
                    bb.order(ByteOrder.nativeOrder());
                    bb.rewind();
                    bb.get(scratchRgba, 0, PBO_BYTES);
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);

                    // Y-flip + RGBA→RGB pack into the result buffer.
                    byte[] src = scratchRgba;
                    byte[] dst = rgbBuffer;
                    final int rowRgbaBytes = CROP_SIZE * 4;
                    int dstIdx = 0;
                    for (int y = CROP_SIZE - 1; y >= 0; y--) {
                        int srcRow = y * rowRgbaBytes;
                        for (int x = 0; x < CROP_SIZE; x++) {
                            int s = srcRow + (x << 2);
                            dst[dstIdx++] = src[s];
                            dst[dstIdx++] = src[s + 1];
                            dst[dstIdx++] = src[s + 2];
                        }
                    }
                    result = new Result(dst, q, CROP_SIZE, CROP_SIZE,
                            rAx, rBx, rAy, rBy, ringCaptureNanos[ringTail]);
                } else {
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
                    logger.warn("glMapBufferRange returned non-ByteBuffer mapping");
                }

                // Done with this slot — release fence + advance tail.
                GLES30.glDeleteSync(fenceSyncs[ringTail]);
                fenceSyncs[ringTail] = 0L;
                pboQuadrant[ringTail] = -1;
                ringCropAffine[ringTail][0] = 0f;
                ringCropAffine[ringTail][1] = 0f;
                ringCropAffine[ringTail][2] = 0f;
                ringCropAffine[ringTail][3] = 0f;
                ringCaptureNanos[ringTail] = 0L;
                ringTail = (ringTail + 1) % RING_SIZE;
            }
            // If sig == GL_TIMEOUT_EXPIRED we just leave the fence in
            // place; the next call will poll it again. Caller falls back
            // to mosaic for THIS tick — which is exactly what we want;
            // we have NEVER blocked the AI-lane GL thread.
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);

        return result;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Selects between layouts:
     *   0 = legacy 4-strip (default — Seal/Atto/Dolphin).
     *   1 = full-frame APA passthrough divided into the motion grid.
     *   3 = oem-parity 2x2 mosaic (DiLink 4 / byd_apa cars).
     * Other values fall through to layout 0 in the math.
     * Volatile read/write — set once at pipeline init, no per-frame churn.
     */
    public void setCameraLayout(int layout) { this.cameraLayout = layout; }

    /** Enables the GL red-overlay suppression on AI thumbnails. Off by default. */
    /** APA center inset (oem APACropFilter parity). See {@link
     *  com.overdrive.app.surveillance.GpuMosaicRecorder#setApaCenterInset}. */
    public void setApaCenterInset(float inset) {
        // Reject NaN explicitly: the clamp does NOT filter it
        // (Math.max(0, Math.min(0.20, NaN)) == NaN in Java), and a NaN inset
        // propagates through xScale → cropWidthNorm → mX → the whole
        // foveated→block-grid affine, where it silently drops every detection
        // in the crop. A malformed config double must leave the last good
        // value in place rather than poison the mapping.
        if (Float.isNaN(inset)) {
            if (logger != null) logger.warn("Ignoring NaN apaCenterInset");
            return;
        }
        this.apaCenterInset = Math.max(0.0f, Math.min(0.20f, inset));
    }

    public void setRedMaskEnabled(boolean enabled) {
        if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            enabled = false;
        }
        this.redMaskEnabled = enabled;
    }

    /**
     * Override the per-role producer corner XY map. Each pair is the
     * top-left of the role's 0.5×0.5 sub-rect inside the producer surface,
     * in {Front, Right, Rear, Left} order. Default = canonical 2x2; on
     * DiLink 4 the pipeline pushes Variant A constants here so V2 motion
     * crops sample the correct producer pixels.
     */
    public void setProducerCornerMap(float[] front, float[] right,
                                     float[] rear, float[] left) {
        if (front == null || right == null || rear == null || left == null
                || front.length < 2 || right.length < 2
                || rear.length  < 2 || left.length  < 2) {
            return;
        }
        synchronized (cornerMapLock) {
            quadrantCornerOffsetsXY[0] = front[0]; quadrantCornerOffsetsXY[1] = front[1];
            quadrantCornerOffsetsXY[2] = right[0]; quadrantCornerOffsetsXY[3] = right[1];
            quadrantCornerOffsetsXY[4] = rear[0];  quadrantCornerOffsetsXY[5] = rear[1];
            quadrantCornerOffsetsXY[6] = left[0];  quadrantCornerOffsetsXY[7] = left[1];
        }
    }

    /**
     * Per-role X/Y flip flags ({xFlip, yFlip} ∈ {0,1}). The motion
     * centroid is mirrored within the role's 0.5×0.5 region before the
     * crop window is computed, so AI thumbnails point at the same
     * physical-world pixels the recorder paints. {Front, Right, Rear, Left}.
     */
    public void setFlipFlags(float[] front, float[] right,
                             float[] rear, float[] left) {
        if (front == null || right == null || rear == null || left == null
                || front.length < 2 || right.length < 2
                || rear.length  < 2 || left.length  < 2) {
            return;
        }
        synchronized (cornerMapLock) {
            flipFlags[0] = front[0]; flipFlags[1] = front[1];
            flipFlags[2] = right[0]; flipFlags[3] = right[1];
            flipFlags[4] = rear[0];  flipFlags[5] = rear[1];
            flipFlags[6] = left[0];  flipFlags[7] = left[1];
        }
    }

    public void release() {
        // Drop any in-flight fences first; deleting their PBOs without
        // releasing the fences leaks driver-side sync objects.
        for (int i = 0; i < RING_SIZE; i++) {
            if (fenceSyncs[i] != 0L) {
                try { GLES30.glDeleteSync(fenceSyncs[i]); } catch (Throwable ignored) {}
                fenceSyncs[i] = 0L;
            }
            pboQuadrant[i] = -1;
            ringCropAffine[i][0] = 0f;
            ringCropAffine[i][1] = 0f;
            ringCropAffine[i][2] = 0f;
            ringCropAffine[i][3] = 0f;
            ringCaptureNanos[i] = 0L;
        }
        if (pboIds[0] != 0) {
            GLES30.glDeleteBuffers(RING_SIZE, pboIds, 0);
            for (int i = 0; i < RING_SIZE; i++) pboIds[i] = 0;
        }
        if (fboId >= 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fboId}, 0);
            fboId = -1;
        }
        if (fboTexture >= 0) {
            GLES20.glDeleteTextures(1, new int[]{fboTexture}, 0);
            fboTexture = -1;
        }
        if (program > 0) {
            GLES20.glDeleteProgram(program);
            program = -1;
        }
        // Drop CPU-side scratch + readback. ~4.4 MB held otherwise across
        // every surveillance pause; lazy paths re-init on next call.
        scratchRgba = null;
        rgbBuffer = null;
        fallbackReadBuffer = null;
        ringHead = 0;
        ringTail = 0;
        initialized = false;
        logger.info("FoveatedCropper released");
    }
}
