package com.overdrive.app.camera;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;

import com.overdrive.app.logging.DaemonLogger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Full-resolution preview sampler for the camera-mapping dialog.
 *
 * <p><b>Architecture: dedicated thread + shared EGL context.</b> The encoder
 * pipeline's GL thread (camera glHandler) is on the recording hot path —
 * any work posted there blocks {@code eglSwapBuffers} and produces gaps in
 * the recorded MP4. Instead this class runs its own {@link HandlerThread}
 * with an EGL context that <em>shares</em> the camera's OES texture (via
 * {@code eglCreateContext(... shareContext)}). The camera HAL keeps writing
 * to {@code cameraTextureId} on its own schedule; the sampler reads from
 * it concurrently. Same pattern {@code GpuDownscaler} already uses for the
 * AI lane — recording-safe.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #sampleFullMosaicJpeg} — full recorder frame at encoder resolution.
 *       Seal: 2560×1920. Tang: 2560×1440.</li>
 *   <li>{@link #samplePerQuadrantJpeg} — one camera tile from the raw strip
 *       at full per-camera resolution. Seal: 1280×960. Tang: 1280×720.</li>
 * </ul>
 *
 * <p>Both calls block the calling thread until the GL job completes.
 * Caller is the HTTP worker (dialog request), not the GL thread.
 */
public final class HighResPreviewSampler {
    private static final DaemonLogger logger =
            DaemonLogger.getInstance("HighResPreviewSampler");

    // oem-parity: uTexMatrix from SurfaceTexture.getTransformMatrix()
    // crops to the HAL's live region. Identity by default.
    //
    // TEX_COORDS below are pre-flipped V (legacy convention). On DiLink 4
    // uTexMatrix already includes Android's producer Y-flip — combined,
    // we'd double-flip and the dialog preview would be upside-down.
    // Counter-flip via uApplyInverseFlip for SurfaceTexture layouts 1 and 3.
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform float uApplyInverseYFlip;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vec2 src = aTexCoord;\n" +
            "    if (uApplyInverseYFlip > 0.5) src.y = 1.0 - src.y;\n" +
            "    vTexCoord = (uTexMatrix * vec4(src, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static String buildMosaicShader(float[] offsets, boolean isTexture2D) {
        // Layout 1 = full-frame passthrough. Layout 3 = four-corner DiLink 4
        // output with the configured inset. Layout 0 = legacy 4-strip.
        String ext = isTexture2D ? "#extension GL_OES_EGL_image_external : enable\n" : "#extension GL_OES_EGL_image_external : require\n";
        String camSampler = isTexture2D ? "uniform sampler2D uCameraTex;\n" : "uniform samplerExternalOES uCameraTex;\n";
        return String.format(Locale.US,
                ext +
                "precision mediump float;\n" +
                camSampler +
                "uniform float uApaMode;\n" +
                "uniform float uRedMaskStrength;\n" +
                "uniform float uApaCenterInset;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    vec2 samplePos;\n" +
                "    if (uApaMode > 2.5) {\n" +
                "        samplePos = vTexCoord;\n" +
                com.overdrive.app.camera.GlUtil.APA_CENTER_INSET_GLSL +
                "    } else if (uApaMode > 0.5) {\n" +
                "        samplePos = vTexCoord;\n" +
                "    } else {\n" +
                "        vec2 gridPos = step(0.5, vTexCoord);\n" +
                "        float frontOffset = %.5f;\n" +
                "        float rightOffset = %.5f;\n" +
                "        float rearOffset  = %.5f;\n" +
                "        float leftOffset  = %.5f;\n" +
                "        float stripOffsetX;\n" +
                "        if (gridPos.x < 0.5) {\n" +
                "            stripOffsetX = gridPos.y < 0.5 ? frontOffset : rearOffset;\n" +
                "        } else {\n" +
                "            stripOffsetX = gridPos.y < 0.5 ? rightOffset : leftOffset;\n" +
                "        }\n" +
                "        float localX = mod(vTexCoord.x, 0.5) * 0.5;\n" +
                "        float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +
                "        samplePos = vec2(localX + stripOffsetX, localY);\n" +
                "    }\n" +
                "    vec4 src = texture2D(uCameraTex, samplePos);\n" +
                com.overdrive.app.camera.GlUtil.RED_MASK_GLSL +
                "    gl_FragColor = src;\n" +
                "}\n",
                offsets[0], offsets[1], offsets[2], offsets[3]);
    }

    // Layout-aware single-quadrant sampler.
    //   uApaMode <= 2.5 → legacy 4-strip: 0.25-wide vertical strip at uStripOffsetX.
    //   uApaMode >  2.5 → DiLink 4 2x2: 0.5×0.5 corner at uCorner.
    // uTexMatrix in the vertex shader is applied to the sampled UV
    // regardless, so the HAL's transform matrix still crops out chrome.
    private static String buildQuadrantShader(boolean isTexture2D) {
        String ext = isTexture2D ? "#extension GL_OES_EGL_image_external : enable\n" : "#extension GL_OES_EGL_image_external : require\n";
        String camSampler = isTexture2D ? "uniform sampler2D uCameraTex;\n" : "uniform samplerExternalOES uCameraTex;\n";
        return ext +
                "precision mediump float;\n" +
                camSampler +
                "uniform float uStripOffsetX;\n" +
                "uniform vec2 uCorner;\n" +
                "uniform vec2 uFlip;\n" +
                "uniform float uApaMode;\n" +
                "uniform float uRedMaskStrength;\n" +
                "uniform float uApaCenterInset;\n" +
                "varying vec2 vTexCoord;\n" +
                "void main() {\n" +
                "    vec2 samplePos;\n" +
                "    if (uApaMode > 2.5) {\n" +
                "        // DiLink 4 2x2: sample inside the role's 0.5×0.5\n" +
                "        // producer corner. uFlip mirrors the local axes when\n" +
                "        // the HAL emits that role's tile flipped.\n" +
                "        vec2 sampledLocal = vec2(vTexCoord.x * 0.5, vTexCoord.y * 0.5);\n" +
                "        if (uFlip.x > 0.5) sampledLocal.x = 0.5 - sampledLocal.x;\n" +
                "        if (uFlip.y > 0.5) sampledLocal.y = 0.5 - sampledLocal.y;\n" +
                "        samplePos = uCorner + sampledLocal;\n" +
                com.overdrive.app.camera.GlUtil.APA_CENTER_INSET_GLSL +
                "    } else {\n" +
                "        samplePos = vec2(uStripOffsetX + vTexCoord.x * 0.25, vTexCoord.y);\n" +
                "    }\n" +
                "    vec4 src = texture2D(uCameraTex, samplePos);\n" +
                com.overdrive.app.camera.GlUtil.RED_MASK_GLSL +
                "    gl_FragColor = src;\n" +
                "}\n";
    }

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

    private final EGLContext sharedContext;
    private final HandlerThread thread;
    private final Handler handler;

    // GL state — owned by the sampler thread.
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private boolean glReady = false;

    private int mosaicProgram = 0;
    private int mosaicAPos = -1, mosaicATex = -1, mosaicUTex = -1;
    private int mosaicUTexMatrix = -1, mosaicUApaMode = -1;
    private int mosaicURedMaskStrength = -1;
    private int mosaicUApaCenterInset = -1;
    private int mosaicUApplyInverseYFlip = -1;
    private float[] cachedOffsets = null;

    private int quadProgram = 0;
    private int quadAPos = -1, quadATex = -1, quadUTex = -1, quadUOffset = -1;
    private int quadUTexMatrix = -1;
    private int quadUCorner = -1;
    private int quadUFlip = -1;
    private int quadURedMaskStrength = -1;
    private int quadUApaCenterInset = -1;
    private volatile float apaCenterInset = 0.0f;
    private volatile boolean redMaskEnabled = false;
    private int quadUApaMode = -1;
    private int quadUApplyInverseYFlip = -1;

    // SurfaceTexture transform matrix + layout selector. Cross-thread
    // (HTTP worker writes via setTextureMatrix → sampler thread reads
    // inside renderAndEncode), so the writer holds the lock and the
    // reader snapshots into a local before uploading.
    private final float[] currentTexMatrix = {
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    };
    private final Object texMatrixLock = new Object();
    private volatile int cameraLayout = 0;  // 0=4-strip, 1=full-frame, 3=corner remap

    private int fbo = -1;
    private int fboTexture = -1;
    private int fboWidth = 0, fboHeight = 0;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    private ByteBuffer readBuffer;
    private byte[] scratchRgba;
    private int[] argbPixels;
    private final boolean isTexture2D;

    /**
     * @param sharedContext EGL context owned by the camera GL thread. Pass
     *     {@code camera.getEglCore().getContext()} so we can sample the
     *     camera's OES texture from this thread.
     */
    public HighResPreviewSampler(EGLContext sharedContext) {
        this(sharedContext, false);
    }

    public HighResPreviewSampler(EGLContext sharedContext, boolean isTexture2D) {
        this.sharedContext = sharedContext;
        this.isTexture2D = isTexture2D;
        this.thread = new HandlerThread("HighResPreviewSampler");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
        // Initialize EGL on the sampler thread. Posted async — sample calls
        // will block on glReady internally.
        handler.post(this::initGl);
    }

    private void initGl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                logger.warn("eglGetDisplay returned NO_DISPLAY");
                return;
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                logger.warn("eglInitialize failed");
                return;
            }
            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0,
                    configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                logger.warn("eglChooseConfig failed");
                return;
            }
            int[] contextAttribs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            // Share with the camera's context so we can sample the OES texture.
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
                    sharedContext, contextAttribs, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                logger.warn("eglCreateContext (shared) failed");
                return;
            }
            // 1×1 pbuffer — we render to FBOs, not the surface.
            int[] pbufferAttribs = {
                    EGL14.EGL_WIDTH, 1,
                    EGL14.EGL_HEIGHT, 1,
                    EGL14.EGL_NONE
            };
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0],
                    pbufferAttribs, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                logger.warn("eglCreatePbufferSurface failed");
                return;
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                logger.warn("eglMakeCurrent failed");
                return;
            }
            vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
            texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);
            glReady = true;
            logger.info("HighResPreviewSampler GL initialized on dedicated thread");
        } catch (Throwable t) {
            logger.warn("initGl failed: " + t.getMessage());
        }
    }

    private void ensureFbo(int width, int height) {
        if (fbo >= 0 && fboWidth == width && fboHeight == height) return;
        releaseFbo();
        try {
            int[] texIds = new int[1];
            GLES20.glGenTextures(1, texIds, 0);
            fboTexture = texIds[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

            int[] fboIds = new int[1];
            GLES20.glGenFramebuffers(1, fboIds, 0);
            fbo = fboIds[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, fboTexture, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                logger.warn("FBO incomplete at " + width + "x" + height + " status=" + status);
                releaseFbo();
                return;
            }
            int rgbaBytes = width * height * 4;
            readBuffer = ByteBuffer.allocateDirect(rgbaBytes);
            readBuffer.order(ByteOrder.nativeOrder());
            scratchRgba = new byte[rgbaBytes];
            argbPixels = new int[width * height];
            fboWidth = width;
            fboHeight = height;
        } catch (Throwable t) {
            logger.warn("ensureFbo failed: " + t.getMessage());
            releaseFbo();
        }
    }

    private boolean ensureMosaicProgram(float[] offsets) {
        if (mosaicProgram > 0 && cachedOffsets != null
                && cachedOffsets[0] == offsets[0]
                && cachedOffsets[1] == offsets[1]
                && cachedOffsets[2] == offsets[2]
                && cachedOffsets[3] == offsets[3]) return true;
        if (mosaicProgram > 0) {
            try { GLES20.glDeleteProgram(mosaicProgram); } catch (Throwable ignored) {}
            mosaicProgram = 0;
        }
        mosaicProgram = GlUtil.createProgram(VERTEX_SHADER, buildMosaicShader(offsets, isTexture2D));
        if (mosaicProgram <= 0) return false;
        mosaicAPos = GLES20.glGetAttribLocation(mosaicProgram, "aPosition");
        mosaicATex = GLES20.glGetAttribLocation(mosaicProgram, "aTexCoord");
        mosaicUTex = GLES20.glGetUniformLocation(mosaicProgram, "uCameraTex");
        mosaicUTexMatrix = GLES20.glGetUniformLocation(mosaicProgram, "uTexMatrix");
        mosaicUApaMode = GLES20.glGetUniformLocation(mosaicProgram, "uApaMode");
        mosaicUApplyInverseYFlip = GLES20.glGetUniformLocation(mosaicProgram, "uApplyInverseYFlip");
        mosaicURedMaskStrength = GLES20.glGetUniformLocation(mosaicProgram, "uRedMaskStrength");
        mosaicUApaCenterInset = GLES20.glGetUniformLocation(mosaicProgram, "uApaCenterInset");
        cachedOffsets = offsets.clone();
        return true;
    }

    private boolean ensureQuadrantProgram() {
        if (quadProgram > 0) return true;
        quadProgram = GlUtil.createProgram(VERTEX_SHADER, buildQuadrantShader(isTexture2D));
        if (quadProgram <= 0) return false;
        quadAPos = GLES20.glGetAttribLocation(quadProgram, "aPosition");
        quadATex = GLES20.glGetAttribLocation(quadProgram, "aTexCoord");
        quadUTex = GLES20.glGetUniformLocation(quadProgram, "uCameraTex");
        quadUOffset = GLES20.glGetUniformLocation(quadProgram, "uStripOffsetX");
        quadUTexMatrix = GLES20.glGetUniformLocation(quadProgram, "uTexMatrix");
        quadUCorner = GLES20.glGetUniformLocation(quadProgram, "uCorner");
        quadUFlip = GLES20.glGetUniformLocation(quadProgram, "uFlip");
        quadUApaMode = GLES20.glGetUniformLocation(quadProgram, "uApaMode");
        quadUApplyInverseYFlip = GLES20.glGetUniformLocation(quadProgram, "uApplyInverseYFlip");
        quadURedMaskStrength = GLES20.glGetUniformLocation(quadProgram, "uRedMaskStrength");
        quadUApaCenterInset = GLES20.glGetUniformLocation(quadProgram, "uApaCenterInset");
        return true;
    }

    /**
     * Block the caller, post the render to the sampler thread, return JPEG.
     * Safe to call from any non-GL thread (HTTP workers).
     */
    public byte[] sampleFullMosaicJpeg(int textureId, int stripWidth, int stripHeight,
                                       float[] offsets) {
        if (textureId == 0 || offsets == null || offsets.length != 4) return null;
        int outW = cameraLayout == 1
                ? PassiveApaGeometry.WIDTH : Math.max(1, stripWidth / 2);
        int outH = cameraLayout == 1
                ? PassiveApaGeometry.HEIGHT : Math.max(1, stripHeight * 2);
        return runOnSamplerThread(() -> {
            ensureFbo(outW, outH);
            if (fbo < 0 || !ensureMosaicProgram(offsets)) return null;
            return renderAndEncode(textureId, mosaicProgram, mosaicAPos, mosaicATex, mosaicUTex,
                    -1, 0f, mosaicUTexMatrix, mosaicUApaMode, outW, outH);
        });
    }

    public byte[] samplePerQuadrantJpeg(int textureId, int stripWidth, int stripHeight,
                                        float stripOffsetX) {
        // Legacy entry-point: 4-strip layout (cameraLayout=0 path).
        return samplePerQuadrantJpeg(textureId, stripWidth, stripHeight,
                                     stripOffsetX, Float.NaN, Float.NaN);
    }

    /**
     * Layout-aware per-quadrant sampler. Pass Float.NaN for cornerX/cornerY
     * to force legacy 4-strip mode (uApaMode=0); pass real values to enable
     * 2x2 corner sampling (uApaMode=3, DiLink 4 path).
     *
     * Output dims are layout-derived: 4-strip → stripWidth/4 × stripHeight,
     * 2x2 → producerW/2 × producerH/2 where producerW/H = stripWidth/2 ×
     * stripHeight*2. Both produce ~per-camera resolution (Seal: 1280×960).
     */
    public byte[] samplePerQuadrantJpeg(int textureId, int stripWidth, int stripHeight,
                                        float stripOffsetX,
                                        float cornerX, float cornerY) {
        return samplePerQuadrantJpeg(textureId, stripWidth, stripHeight,
                stripOffsetX, cornerX, cornerY, 0f, 0f);
    }

    /**
     * Layout-aware sampler with explicit per-role flip flags. xFlip / yFlip
     * mirror the local 0.5×0.5 sample window when DiLink 4's HAL emits a
     * flipped tile for that role.
     */
    public byte[] samplePerQuadrantJpeg(int textureId, int stripWidth, int stripHeight,
                                        float stripOffsetX,
                                        float cornerX, float cornerY,
                                        float xFlip, float yFlip) {
        if (textureId == 0) return null;
        boolean useCorner = !Float.isNaN(cornerX) && !Float.isNaN(cornerY);
        int outW;
        int outH;
        if (useCorner) {
            // 2x2-native HAL: producer surface is stripWidth/2 × stripHeight*2,
            // each quadrant is half on each axis → producerW/2 × producerH/2
            // = stripWidth/4 × stripHeight, identical per-camera resolution
            // to the legacy path.
            outW = Math.max(1, stripWidth / 4);
            outH = Math.max(1, stripHeight);
        } else {
            outW = Math.max(1, stripWidth / 4);
            outH = Math.max(1, stripHeight);
        }
        final int finalW = outW;
        final int finalH = outH;
        final int apaMode = useCorner ? 3 : 0;
        final float cx = useCorner ? cornerX : 0f;
        final float cy = useCorner ? cornerY : 0f;
        final float fx = useCorner ? xFlip : 0f;
        final float fy = useCorner ? yFlip : 0f;
        return runOnSamplerThread(() -> {
            ensureFbo(finalW, finalH);
            if (fbo < 0 || !ensureQuadrantProgram()) return null;
            // Upload corner + flip uniforms if the program has them (silent
            // no-op otherwise). uApaMode picks which branch the shader takes.
            if (quadUCorner >= 0) {
                GLES20.glUseProgram(quadProgram);
                GLES20.glUniform2f(quadUCorner, cx, cy);
            }
            if (quadUFlip >= 0) {
                GLES20.glUseProgram(quadProgram);
                GLES20.glUniform2f(quadUFlip, fx, fy);
            }
            // renderAndEncode handles uApaMode upload via the location arg.
            return renderAndEncode(textureId, quadProgram, quadAPos, quadATex, quadUTex,
                    quadUOffset, stripOffsetX, quadUTexMatrix,
                    quadUApaMode, finalW, finalH, apaMode);
        });
    }

    private interface GlJob {
        byte[] run();
    }

    private byte[] runOnSamplerThread(GlJob job) {
        if (!glReady) {
            // GL might still be initializing — give it a brief moment.
            for (int i = 0; i < 20 && !glReady; i++) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            if (!glReady) {
                logger.warn("GL not ready after 1s wait");
                return null;
            }
        }
        final Object lock = new Object();
        final AtomicReference<byte[]> result = new AtomicReference<>();
        handler.post(() -> {
            try {
                result.set(job.run());
            } catch (Throwable t) {
                logger.warn("GL job failed: " + t.getMessage());
            } finally {
                synchronized (lock) { lock.notifyAll(); }
            }
        });
        synchronized (lock) {
            try { lock.wait(1500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return result.get();
    }

    /** Caller has bound the FBO + program; we render, read, encode. */
    private byte[] renderAndEncode(int textureId, int program,
                                   int aPosLoc, int aTexLoc, int uTexLoc,
                                   int uOffsetLoc, float offsetValue,
                                   int uTexMatrixLoc, int uApaModeLoc,
                                   int width, int height) {
        // Default apaMode = persisted cameraLayout (used by mosaic sampler).
        return renderAndEncode(textureId, program, aPosLoc, aTexLoc, uTexLoc,
            uOffsetLoc, offsetValue, uTexMatrixLoc, uApaModeLoc,
            width, height, cameraLayout);
    }

    /** Variant that accepts an explicit apaMode override (per-call, e.g.
     *  per-quadrant sampler decides 0 vs 3 based on whether corner offsets
     *  were supplied, regardless of the persisted layout). */
    private byte[] renderAndEncode(int textureId, int program,
                                   int aPosLoc, int aTexLoc, int uTexLoc,
                                   int uOffsetLoc, float offsetValue,
                                   int uTexMatrixLoc, int uApaModeLoc,
                                   int width, int height, int apaModeOverride) {
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            if (isTexture2D) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            } else {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
            }
            GLES20.glUniform1i(uTexLoc, 0);
            if (uOffsetLoc >= 0) {
                GLES20.glUniform1f(uOffsetLoc, offsetValue);
            }
            if (uTexMatrixLoc >= 0) {
                float[] m = new float[16];
                synchronized (texMatrixLock) {
                    System.arraycopy(currentTexMatrix, 0, m, 0, 16);
                }
                GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, m, 0);
            }
            if (uApaModeLoc >= 0) {
                GLES20.glUniform1f(uApaModeLoc, (float) apaModeOverride);
            }
            // On dilink4 the SurfaceTexture matrix already Y-flips; combined
            // with the legacy pre-flipped TEX_COORDS we'd double-flip.
            // Counter-flip in the vertex shader so the dialog preview comes
            // out the right way up.
            int inverseFlipLoc = (program == mosaicProgram)
                ? mosaicUApplyInverseYFlip
                : (program == quadProgram ? quadUApplyInverseYFlip : -1);
            if (inverseFlipLoc >= 0) {
                GLES20.glUniform1f(inverseFlipLoc,
                    (apaModeOverride == 1 || apaModeOverride > 2.5f) ? 1.0f : 0.0f);
            }
            int redMaskLoc = (program == mosaicProgram)
                ? mosaicURedMaskStrength
                : (program == quadProgram ? quadURedMaskStrength : -1);
            if (redMaskLoc >= 0) {
                GLES20.glUniform1f(redMaskLoc, redMaskEnabled ? 1.0f : 0.0f);
            }
            // APA center inset is applied to BOTH the quadrant per-role
            // sampler AND the mosaic passthrough — the mosaic JPEG would
            // otherwise show the producer center seam where the BYD AVC
            // chrome lives. Mirrors the snapshot endpoint output to the
            // recorder/stream/downscaler chain on dilink4.
            int insetLoc = (program == mosaicProgram)
                ? mosaicUApaCenterInset
                : (program == quadProgram ? quadUApaCenterInset : -1);
            if (insetLoc >= 0) {
                GLES20.glUniform1f(insetLoc, apaCenterInset);
            }

            GLES20.glEnableVertexAttribArray(aPosLoc);
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(aTexLoc);
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPosLoc);
            GLES20.glDisableVertexAttribArray(aTexLoc);

            // glReadPixels syncs on this FBO's pending writes.
            readBuffer.clear();
            GLES20.glReadPixels(0, 0, width, height,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuffer);

            int rgbaBytes = width * height * 4;
            readBuffer.rewind();
            readBuffer.get(scratchRgba, 0, rgbaBytes);

            byte[] src = scratchRgba;
            int rowRgbaBytes = width * 4;
            int dstIdx = 0;
            for (int y = height - 1; y >= 0; y--) {
                int srcRow = y * rowRgbaBytes;
                for (int x = 0; x < width; x++) {
                    int s = srcRow + (x << 2);
                    int r = src[s] & 0xFF;
                    int g = src[s + 1] & 0xFF;
                    int b = src[s + 2] & 0xFF;
                    argbPixels[dstIdx++] = 0xFF000000 | (r << 16) | (g << 8) | b;
                }
            }

            Bitmap bmp = Bitmap.createBitmap(argbPixels, width, height,
                    Bitmap.Config.ARGB_8888);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
                return out.toByteArray();
            } finally {
                bmp.recycle();
            }
        } catch (Throwable t) {
            logger.warn("renderAndEncode failed " + width + "x" + height
                    + ": " + t.getMessage());
            return null;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }
    }

    private void releaseFbo() {
        if (fbo >= 0) {
            try { GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0); } catch (Throwable ignored) {}
            fbo = -1;
        }
        if (fboTexture >= 0) {
            try { GLES20.glDeleteTextures(1, new int[]{fboTexture}, 0); } catch (Throwable ignored) {}
            fboTexture = -1;
        }
        // Drop the readback scratch (~3.6 MB at 1280×720). Lazy ensureFbo
        // re-creates these on the next sample; without this they survive
        // every camera re-init for the daemon's lifetime.
        readBuffer = null;
        scratchRgba = null;
        argbPixels = null;
        fboWidth = 0;
        fboHeight = 0;
    }

    /**
     * Synchronous release. Posts the GL teardown to the sampler thread and
     * blocks the caller until it completes (or 500 ms timeout). Caller is
     * typically the camera GL thread inside {@code releaseGl()} — we want
     * the sampler's EGL context fully destroyed BEFORE the camera's parent
     * EGL context is torn down, otherwise we'd race the shared-resource
     * destruction and possibly hit EGL_BAD_DISPLAY in the sampler's
     * eglDestroyContext.
     */
    /**
     * Publishes the SurfaceTexture transform matrix subsequent samples
     * will apply. Caller is the camera GL thread (publishes per frame);
     * actual sample renders happen on the sampler thread, so we lock
     * around the float[16] copy to avoid tearing.
     */
    public void setTextureMatrix(float[] matrix4x4) {
        if (matrix4x4 == null || matrix4x4.length < 16) return;
        synchronized (texMatrixLock) {
            System.arraycopy(matrix4x4, 0, currentTexMatrix, 0, 16);
        }
    }

    /** 0 = legacy 4-strip; 1 = full-frame APA; 3 = four-corner DiLink 4. */
    public void setCameraLayout(int layout) { this.cameraLayout = layout; }

    /** Enables the GL red-overlay suppression on dialog preview JPEGs. */
    /** APA center inset (oem APACropFilter parity). See {@link
     *  com.overdrive.app.surveillance.GpuMosaicRecorder#setApaCenterInset}. */
    public void setApaCenterInset(float inset) {
        this.apaCenterInset = Math.max(0.0f, Math.min(0.20f, inset));
    }

    public void setRedMaskEnabled(boolean enabled) {
        if (com.overdrive.app.camera.dilink5.DiLink5QCarCamBackend.isSupported()) {
            enabled = false;
        }
        this.redMaskEnabled = enabled;
    }

    public void release() {
        if (thread == null || !thread.isAlive()) return;
        final Object lock = new Object();
        final boolean[] done = {false};
        handler.post(() -> {
            try {
                releaseFbo();
                if (mosaicProgram > 0) {
                    try { GLES20.glDeleteProgram(mosaicProgram); } catch (Throwable ignored) {}
                    mosaicProgram = 0;
                }
                if (quadProgram > 0) {
                    try { GLES20.glDeleteProgram(quadProgram); } catch (Throwable ignored) {}
                    quadProgram = 0;
                }
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface);
                        eglSurface = EGL14.EGL_NO_SURFACE;
                    }
                    if (eglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext);
                        eglContext = EGL14.EGL_NO_CONTEXT;
                    }
                    EGL14.eglTerminate(eglDisplay);
                    eglDisplay = EGL14.EGL_NO_DISPLAY;
                }
                glReady = false;
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 500;
            while (!done[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try { lock.wait(remaining); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        thread.quitSafely();
    }
}
