package com.overdrive.app.surveillance;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.overdrive.app.camera.GlUtil;
import com.overdrive.app.logging.DaemonLogger;

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
 * At 320×240 per quadrant, a person at 5m is ~12×35 pixels (YOLO can't classify).
 * At 640×640 from the raw strip, the same person is ~80×200 pixels (easy classify).
 *
 * Architecture:
 * - Runs on the GL thread (same thread that owns the camera texture)
 * - Uses a dedicated 640×640 FBO (Frame Buffer Object)
 * - Fragment shader samples directly from the OES camera texture
 * - glReadPixels extracts the crop as RGB for YOLO
 * - Zero idle cost: FBO only renders when motion triggers a crop request
 *
 * Strip layout (5120×960):
 *   cam1(Rear)  = x: 0..1280
 *   cam2(Left)  = x: 1280..2560
 *   cam3(Right) = x: 2560..3840
 *   cam4(Front) = x: 3840..5120
 *
 * Quadrant-to-strip mapping:
 *   Q0 (front) → strip offset 0.75 (3840px)
 *   Q1 (right) → strip offset 0.50 (2560px)
 *   Q2 (left)  → strip offset 0.25 (1280px) [note: BL in mosaic = rear, BR = left]
 *   Q3 (rear)  → strip offset 0.00 (0px)
 *
 * Wait — the mosaic layout is: TL=Front, TR=Right, BL=Rear, BR=Left
 * But QUADRANT_NAMES = ["front", "right", "left", "rear"]
 * Quadrant indices: 0=TL(front), 1=TR(right), 2=BL(rear?), 3=BR(left?)
 *
 * Actually from the fragment shader:
 *   gridPos = step(0.5, vTexCoord)  → (0,0)=TL, (1,0)=TR, (0,1)=BL, (1,1)=BR
 *   TL=Front(0.75), TR=Right(0.50), BL=Rear(0.00), BR=Left(0.25)
 *
 * And from SurveillanceEngineGpu.runAiOnQuadrant:
 *   startX = (quadrant % 2) * qW   → Q0: x=0, Q1: x=320, Q2: x=0, Q3: x=320
 *   startY = (quadrant / 2) * qH   → Q0: y=0, Q1: y=0,   Q2: y=240, Q3: y=240
 *
 * So: Q0=TL=Front, Q1=TR=Right, Q2=BL=Rear, Q3=BR=Left
 * But QUADRANT_NAMES = ["front", "right", "left", "rear"]
 * This means Q2 maps to "left" in the name array but BL in the grid (which is Rear in the strip).
 *
 * Let's just use the same strip offset math as the fragment shader:
 */
public class FoveatedCropper {
    private static final DaemonLogger logger = DaemonLogger.getInstance("FoveatedCrop");

    // Output crop size — matches YOLO input
    public static final int CROP_SIZE = 640;

    // Source strip dimensions
    private static final int STRIP_WIDTH = 5120;
    private static final int STRIP_HEIGHT = 960;

    // Each camera occupies 1/4 of the strip = 1280×960
    private static final int CAM_WIDTH = 1280;

    // GL resources
    private int fbo = -1;
    private int fboTexture = -1;
    private int program = -1;
    private int aPosition = -1;
    private int aTexCoord = -1;
    private int uCameraTex = -1;
    private ByteBuffer readBuffer = null;
    private byte[] rgbBuffer = null;
    private boolean initialized = false;

    // Fullscreen quad vertices
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // Vertex shader — pass-through
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";

    // Fragment shader — samples a sub-region of the OES camera texture.
    // uCropRect = vec4(left, top, width, height) in normalized [0,1] coords
    // relative to the full 5120×960 strip.
    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES uCameraTex;\n" +
        "uniform vec4 uCropRect;\n" +  // left, top, width, height in normalized coords
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    vec2 samplePos = vec2(\n" +
        "        uCropRect.x + vTexCoord.x * uCropRect.z,\n" +
        "        uCropRect.y + vTexCoord.y * uCropRect.w\n" +
        "    );\n" +
        "    gl_FragColor = texture2D(uCameraTex, samplePos);\n" +
        "}\n";

    private int uCropRect = -1;

    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };

    // Tex coords flipped vertically (GL origin bottom-left, image origin top-left)
    private static final float[] TEX_COORDS = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    };

    /**
     * Strip X offset for each quadrant index.
     *
     * From the mosaic fragment shader (GpuDownscaler.java):
     *   Grid: TL=Front, TR=Right, BL=Rear, BR=Left
     *   Strip: cam1(Rear)=0.00, cam2(Left)=0.25, cam3(Right)=0.50, cam4(Front)=0.75
     *
     * Quadrant indices (from runAiOnQuadrant grid position):
     *   Q0: TL → Front → strip 0.75
     *   Q1: TR → Right → strip 0.50
     *   Q2: BL → Rear  → strip 0.00
     *   Q3: BR → Left  → strip 0.25
     *
     * QUADRANT_NAMES = ["front", "right", "rear", "left"]
     */
    private static final float[] QUADRANT_STRIP_OFFSET_X = {
        0.75f,  // Q0 (TL → Front)
        0.50f,  // Q1 (TR → Right)
        0.00f,  // Q2 (BL → Rear)
        0.25f   // Q3 (BR → Left)
    };

    /**
     * Initialize the FBO and shader. Must be called on the GL thread.
     */
    public void init() {
        try {
            program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (program == 0) {
                logger.error("Foveated crop shader compilation failed");
                return;
            }
            aPosition = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
            uCameraTex = GLES20.glGetUniformLocation(program, "uCameraTex");
            uCropRect = GLES20.glGetUniformLocation(program, "uCropRect");

            // Create FBO texture (RGBA, 640×640)
            int[] texIds = new int[1];
            GLES20.glGenTextures(1, texIds, 0);
            fboTexture = texIds[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTexture);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    CROP_SIZE, CROP_SIZE, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

            // Create FBO
            int[] fboIds = new int[1];
            GLES20.glGenFramebuffers(1, fboIds, 0);
            fbo = fboIds[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, fboTexture, 0);

            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                logger.error("Foveated FBO incomplete: " + status);
                return;
            }

            // Pre-allocate readback buffers
            readBuffer = ByteBuffer.allocateDirect(CROP_SIZE * CROP_SIZE * 4);
            readBuffer.order(ByteOrder.nativeOrder());
            rgbBuffer = new byte[CROP_SIZE * CROP_SIZE * 3];

            // Vertex buffers
            vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
            texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);

            initialized = true;
            logger.info("FoveatedCropper initialized (640×640 FBO)");
        } catch (Exception e) {
            logger.error("FoveatedCropper init failed: " + e.getMessage());
        }
    }

    /**
     * Crop a 640×640 window from the raw camera strip, centered on the motion centroid.
     *
     * Must be called on the GL thread (same thread that owns cameraTextureId).
     *
     * @param cameraTextureId OES texture ID of the raw 5120×960 camera strip
     * @param quadrant        Quadrant index (0-3) where motion was detected
     * @param centroidX       Motion centroid X in block coordinates (0..GRID_COLS-1)
     * @param centroidY       Motion centroid Y in block coordinates (0..GRID_ROWS-1)
     * @return 640×640 RGB byte array, or null on failure
     */
    public byte[] crop(int cameraTextureId, int quadrant, float centroidX, float centroidY) {
        if (!initialized || quadrant < 0 || quadrant >= 4) return null;

        // Step 1: Map centroid from block coords to pixel coords within the quadrant.
        // Each quadrant in the mosaic is 320×240, with a 10×7 block grid (32px blocks).
        // centroidX is in [0, 9], centroidY is in [0, 6].
        float quadPixelX = (centroidX + 0.5f) * 32.0f;  // Center of block, in 320px space
        float quadPixelY = (centroidY + 0.5f) * 32.0f;  // Center of block, in 240px space

        // Step 2: Map to normalized coordinates within the individual camera's 1280×960 frame.
        // The mosaic quadrant (320×240) is a downscaled version of the camera's 1280×960.
        // Scale factor: 1280/320 = 4x horizontal, 960/240 = 4x vertical.
        float camNormX = quadPixelX / 320.0f;  // [0, 1] within camera frame
        float camNormY = quadPixelY / 240.0f;  // [0, 1] within camera frame

        // Step 3: Compute the crop window in the full 5120×960 strip.
        // Each camera occupies 0.25 of the strip width.
        float stripOffsetX = QUADRANT_STRIP_OFFSET_X[quadrant];

        // The crop window is 640×640 pixels from the 5120×960 strip.
        // In normalized strip coords: width = 640/5120 = 0.125, height = 640/960 = 0.667
        float cropWidthNorm = (float) CROP_SIZE / STRIP_WIDTH;   // 0.125
        float cropHeightNorm = (float) CROP_SIZE / STRIP_HEIGHT; // 0.6667

        // Center the crop on the centroid within this camera's strip region.
        // Camera region in strip: [stripOffsetX, stripOffsetX + 0.25] × [0, 1]
        float centerX = stripOffsetX + camNormX * 0.25f;
        float centerY = camNormY;

        // Compute crop rect, clamped to the camera's strip region
        float cropLeft = centerX - cropWidthNorm / 2.0f;
        float cropTop = centerY - cropHeightNorm / 2.0f;

        // Clamp to camera boundaries (don't bleed into adjacent cameras)
        float camLeft = stripOffsetX;
        float camRight = stripOffsetX + 0.25f;
        cropLeft = Math.max(camLeft, Math.min(cropLeft, camRight - cropWidthNorm));
        cropTop = Math.max(0.0f, Math.min(cropTop, 1.0f - cropHeightNorm));

        // Step 4: Render the crop via FBO
        int[] savedViewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, CROP_SIZE, CROP_SIZE);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(uCameraTex, 0);
        GLES20.glUniform4f(uCropRect, cropLeft, cropTop, cropWidthNorm, cropHeightNorm);

        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);

        // Ensure GPU finishes before readback
        GLES20.glFinish();

        // Step 5: Read pixels (RGBA → RGB with Y-flip)
        readBuffer.clear();
        GLES20.glReadPixels(0, 0, CROP_SIZE, CROP_SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readBuffer);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);

        // RGBA → RGB with Y-flip (GL origin is bottom-left)
        readBuffer.rewind();
        int rgbIdx = 0;
        for (int y = CROP_SIZE - 1; y >= 0; y--) {
            int rowStart = y * CROP_SIZE * 4;
            for (int x = 0; x < CROP_SIZE; x++) {
                int srcIdx = rowStart + x * 4;
                rgbBuffer[rgbIdx++] = readBuffer.get(srcIdx);
                rgbBuffer[rgbIdx++] = readBuffer.get(srcIdx + 1);
                rgbBuffer[rgbIdx++] = readBuffer.get(srcIdx + 2);
            }
        }

        return rgbBuffer;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Release GL resources. Must be called on the GL thread.
     */
    public void release() {
        if (fbo >= 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
            fbo = -1;
        }
        if (fboTexture >= 0) {
            GLES20.glDeleteTextures(1, new int[]{fboTexture}, 0);
            fboTexture = -1;
        }
        if (program > 0) {
            GLES20.glDeleteProgram(program);
            program = -1;
        }
        initialized = false;
        logger.info("FoveatedCropper released");
    }
}
