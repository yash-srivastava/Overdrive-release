package com.overdrive.app.camera;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import com.overdrive.app.logging.DaemonLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Small ring of app-owned 2D textures that the camera acquisition thread
 * copies EXTERNAL_OES camera frames into (decoupled encoder lane,
 * camera.decoupledEncoderLane).
 *
 * <p>Purpose: on the legacy ImageReader path every consumer (recorder mosaic,
 * stream scaler, blind-spot scaler, AI lane) samples the camera-owned gralloc
 * buffer directly through one EXTERNAL_OES texture, which forces the bound
 * Image/HardwareBuffer to stay alive until the NEXT bind — across encoder
 * makeCurrent/draw/eglSwapBuffers and any MediaCodec backpressure stall. This
 * ring breaks that lifetime dependency: the acquisition thread blits the OES
 * frame into a ring slot it owns, and the camera buffer can go back to the
 * BYD HAL producer pool as soon as the blit's GPU fence signals.
 *
 * <p>Threading contract (deliberately dumb — no internal locking beyond the
 * single pin atomic):
 * <ul>
 *   <li>{@link #init()}, {@link #copyFrom(int)} and {@link #release()} MUST
 *       run on the writer GL thread (GL-RenderLoop) with its EGL context
 *       current — the FBOs are container objects and are NOT shared across
 *       contexts.</li>
 *   <li>Slot <em>textures</em> ARE share-group objects; cross-context readers
 *       (EncoderLane, AiLaneGl) may sample them.</li>
 *   <li>Exactly one cross-context reader with an unbounded hold time is
 *       supported: the EncoderLane pins the slot it is drawing from via
 *       {@link #pinForRead(int)}, and the writer's slot selection and the
 *       pin check are serialized on the ring monitor so they can never
 *       interleave (a plain atomic was TOCTOU-racy at N-1-frame lane lag).
 *       A pin refusal means the slot is being rewritten RIGHT NOW — the
 *       caller must skip that draw, never sample unpinned. Bounded readers
 *       on the writer thread (stream / blind-spot passes) and the AI lane
 *       (≥2-frame slot distance plus the existing cameraTextureLock
 *       discipline, same exposure as the DiLink 5 compositor path) need no
 *       pin.</li>
 * </ul>
 *
 * <p>Memory: slotCount × width × height × 4 bytes (RGBA8). At the panoramic
 * strip size (5120×960) and 3 slots that is ~59 MB of GPU memory — the price
 * of never letting the encoder extend a camera buffer's lifetime. The copy
 * itself is an identity blit (ring(u,v) == src(u,v)), so every downstream
 * shader keeps its exact legacy sampling math; only the sampler type changes
 * (sampler2D via the existing isTexture2D plumbing the DiLink 5 path already
 * exercises in production).
 */
public final class CopiedFrameRing {

    private static final DaemonLogger logger =
        DaemonLogger.getInstance("CopiedFrameRing");

    /** No reader pin held. */
    private static final int NO_PIN = -1;

    private final String name;
    private final int slotCount;
    private final int width;
    private final int height;

    private int[] textures;
    private int[] fbos;
    private int blitProgram;
    private int aPositionLoc;
    private int aTexCoordLoc;
    private int uTexLoc;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    private boolean initialized;

    // Slot-state protocol. All three fields are guarded by the ring monitor:
    // a plain atomic pin was TOCTOU-racy — with N slots, a lane lagging N-1
    // frames pins exactly the slot the writer's stale pin-read already
    // selected, and the writer then blits into the slot mid-sample. The
    // monitor makes {select+mark writing} and {check writing+pin} atomic;
    // both operations run once per frame, so contention is negligible.
    private int lastWritten = -1;     // guarded by this
    private int writingSlot = NO_PIN; // guarded by this — submitted or mid-blit slot
    private int pendingSlot = NO_PIN; // guarded by this — blit queued, barrier not issued
    private int pinnedSlot = NO_PIN;  // guarded by this — lane-held slot
    // Each completed overwrite advances the slot generation. A queued encoder
    // packet must not sample a slot that now contains a different frame.
    private final long[] slotGenerations; // guarded by this

    // Fullscreen identity quad. Texcoords map 1:1 so the ring content is a
    // texel-for-texel proxy of the OES source — downstream geometry
    // (quadrant offsets, Y-flip uniforms, foveated crops) is untouched.
    private static final float[] QUAD_POS = {
        -1f, -1f,   1f, -1f,   -1f, 1f,   1f, 1f,
    };
    private static final float[] QUAD_TEX = {
         0f,  0f,   1f,  0f,    0f, 1f,   1f, 1f,
    };

    private static final String BLIT_VERTEX_SHADER =
        "attribute vec2 aPosition;\n"
        + "attribute vec2 aTexCoord;\n"
        + "varying vec2 vTexCoord;\n"
        + "void main() {\n"
        + "    gl_Position = vec4(aPosition, 0.0, 1.0);\n"
        + "    vTexCoord = aTexCoord;\n"
        + "}\n";

    private static final String BLIT_FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n"
        + "precision mediump float;\n"
        + "uniform samplerExternalOES uTex;\n"
        + "varying vec2 vTexCoord;\n"
        + "void main() {\n"
        + "    gl_FragColor = texture2D(uTex, vTexCoord);\n"
        + "}\n";

    public CopiedFrameRing(String name, int slotCount, int width, int height) {
        if (slotCount < 2) {
            throw new IllegalArgumentException("slotCount must be >= 2");
        }
        this.name = name;
        this.slotCount = slotCount;
        this.width = width;
        this.height = height;
        this.slotGenerations = new long[slotCount];
    }

    /**
     * Allocates slot textures (with storage — {@code GlUtil.create2DTexture}
     * deliberately allocates none), FBOs and the OES blit program. Writer GL
     * thread only, EGL context current.
     *
     * @return true when every slot is usable; false leaves the ring
     *         uninitialized (caller falls back / aborts the flag path).
     */
    public boolean init() {
        if (initialized) return true;
        try {
            blitProgram = GlUtil.createProgram(BLIT_VERTEX_SHADER, BLIT_FRAGMENT_SHADER);
            if (blitProgram == 0) {
                logger.error(name + ": blit program compile/link failed");
                return false;
            }
            aPositionLoc = GLES20.glGetAttribLocation(blitProgram, "aPosition");
            aTexCoordLoc = GLES20.glGetAttribLocation(blitProgram, "aTexCoord");
            uTexLoc = GLES20.glGetUniformLocation(blitProgram, "uTex");

            vertexBuffer = ByteBuffer.allocateDirect(QUAD_POS.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertexBuffer.put(QUAD_POS).position(0);
            texCoordBuffer = ByteBuffer.allocateDirect(QUAD_TEX.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
            texCoordBuffer.put(QUAD_TEX).position(0);

            textures = new int[slotCount];
            fbos = new int[slotCount];
            GLES20.glGenTextures(slotCount, textures, 0);
            GLES20.glGenFramebuffers(slotCount, fbos, 0);
            for (int i = 0; i < slotCount; i++) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i]);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                // Allocate immutable-size RGBA8 storage (null upload). Content
                // starts black, which is what the early-frame probes expect
                // from a not-yet-published slot.
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i]);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D,
                    textures[i], 0);
                int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    logger.error(name + ": FBO " + i + " incomplete: 0x"
                        + Integer.toHexString(status));
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    releasePartial();
                    return false;
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            initialized = true;
            logger.info(name + ": ring initialized (" + slotCount + " × "
                + width + "x" + height + " RGBA8, ~"
                + (slotCount * (long) width * height * 4 / (1024 * 1024)) + " MB)");
            return true;
        } catch (Throwable t) {
            logger.error(name + ": init failed: " + t.getMessage());
            releasePartial();
            return false;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Queues a blit of the given EXTERNAL_OES texture into the next free slot
     * and returns its reserved slot index (-1 on failure). The slot remains
     * unavailable to readers until {@link #completePendingWrites} publishes
     * it after the shared GPU completion barrier. Writer GL thread only.
     *
     * <p>Round-robin skips the lane-pinned slot, so with N≥3 slots the
     * writer can always make progress even while the encoder lane holds one
     * slot across a long backpressure stall. The framebuffer binding is
     * restored to 0 before returning — a leaked FBO binding would redirect
     * the next window-surface pass (recorder glClear) into the ring slot.
     */
    public int copyFrom(int oesTextureId) {
        if (!initialized || oesTextureId == 0) return -1;
        final int slot;
        synchronized (this) {
            // A caller must complete the previous write before submitting
            // another one to this ring. This keeps the slot reservation and
            // publication barrier unambiguous across shared EGL contexts.
            if (pendingSlot != NO_PIN || writingSlot != NO_PIN) return -1;
            int candidate = (lastWritten + 1) % slotCount;
            if (candidate == pinnedSlot) {
                candidate = (candidate + 1) % slotCount;
            }
            slot = candidate;
            writingSlot = slot;
        }
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[slot]);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glUseProgram(blitProgram);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniform1i(uTexLoc, 0);

            GLES20.glEnableVertexAttribArray(aPositionLoc);
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT,
                false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(aTexCoordLoc);
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT,
                false, 0, texCoordBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(aPositionLoc);
            GLES20.glDisableVertexAttribArray(aTexCoordLoc);

            // Keep the write reservation until completePendingWrites() issues
            // the shared barrier. The caller can queue camera and windshield
            // blits first, then pay for one GPU drain instead of one per ring.
            synchronized (this) {
                pendingSlot = slot;
            }
            return slot;
        } catch (Throwable t) {
            logger.warn(name + ": copyFrom failed: " + t.getMessage());
            synchronized (this) {
                writingSlot = NO_PIN;
                pendingSlot = NO_PIN;
            }
            return -1;
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }
    }

    /**
     * Completes all queued ring blits with one GPU barrier and publishes their
     * slots atomically with respect to each ring's pin protocol. Must run on
     * the writer GL thread with the shared EGL context current. A failed
     * barrier invalidates the pending writes; callers must not close source
     * buffers or publish the returned slot indices on false.
     */
    public static boolean completePendingWrites(CopiedFrameRing... rings) {
        if (rings == null || rings.length == 0) return true;
        try {
            GLES20.glFinish();
            for (CopiedFrameRing ring : rings) {
                if (ring == null) continue;
                synchronized (ring) {
                    int slot = ring.pendingSlot;
                    if (slot != NO_PIN) {
                        ring.slotGenerations[slot]++;
                        ring.lastWritten = slot;
                        ring.pendingSlot = NO_PIN;
                        ring.writingSlot = NO_PIN;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            for (CopiedFrameRing ring : rings) {
                if (ring == null) continue;
                synchronized (ring) {
                    ring.pendingSlot = NO_PIN;
                    ring.writingSlot = NO_PIN;
                }
            }
            logger.error("GPU copy completion barrier failed: " + t.getMessage());
            return false;
        }
    }

    /** Version of the pixels currently published in this slot. Writer reads
     * this immediately after copyFrom; the encoder verifies it when pinning. */
    public synchronized long generationOf(int slot) {
        if (!initialized || slot < 0 || slot >= slotCount) return -1;
        return slotGenerations[slot];
    }

    /** Texture id backing {@code slot}, or 0 when out of range/uninitialized. */
    public int textureOf(int slot) {
        if (!initialized || slot < 0 || slot >= slotCount) return 0;
        return textures[slot];
    }

    /**
     * Pins {@code slot} against rewrite while the encoder lane samples it.
     * Lane thread only; single reader. Refuses (returns false) when another
     * pin is already held, or when the writer is CURRENTLY blitting into this
     * slot — the caller must then SKIP the draw entirely (latest-wins delivers
     * a fresh packet within a frame), never sample unpinned.
     */
    public synchronized boolean pinForRead(int slot, long expectedGeneration) {
        if (!initialized || slot < 0 || slot >= slotCount
                || expectedGeneration < 0
                || slotGenerations[slot] != expectedGeneration) return false;
        if (pinnedSlot != NO_PIN) {
            logger.warn(name + ": pinForRead(" + slot + ") refused — slot "
                + pinnedSlot + " still pinned (unpin missed?)");
            return false;
        }
        if (slot == writingSlot) {
            // The packet went stale while the lane lagged and the writer is
            // mid-blit into this very slot. Skip; a newer packet is imminent.
            return false;
        }
        pinnedSlot = slot;
        return true;
    }

    /** Releases the reader pin. Lane thread only. Safe when nothing pinned. */
    public synchronized void unpinRead() {
        pinnedSlot = NO_PIN;
    }

    /** Frees GL resources. Writer GL thread only, context current. */
    public void release() {
        releasePartial();
        initialized = false;
    }

    private void releasePartial() {
        try {
            if (fbos != null) {
                GLES20.glDeleteFramebuffers(slotCount, fbos, 0);
                fbos = null;
            }
            if (textures != null) {
                GLES20.glDeleteTextures(slotCount, textures, 0);
                textures = null;
            }
            if (blitProgram != 0) {
                GLES20.glDeleteProgram(blitProgram);
                blitProgram = 0;
            }
        } catch (Throwable t) {
            logger.warn(name + ": release: " + t.getMessage());
        }
        synchronized (this) {
            pinnedSlot = NO_PIN;
            writingSlot = NO_PIN;
            pendingSlot = NO_PIN;
            lastWritten = -1;
            java.util.Arrays.fill(slotGenerations, 0L);
        }
    }
}
