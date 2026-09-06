package com.overdrive.app.camera;

import android.opengl.GLES11Ext;
import com.overdrive.app.logging.DaemonLogger;
import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GlUtil - OpenGL utility functions for texture and shader management.
 * 
 * Provides helper methods for common OpenGL operations in the GPU Zero-Copy Pipeline:
 * - Creating external textures for camera input
 * - Compiling and linking shaders
 * - Creating vertex buffers
 * - Error checking
 */
public class GlUtil {
    private static final String TAG = "GlUtil";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /**
     * GLSL fragment-shader fragment that suppresses the BYD AVM HAL's
     * translucent red overlay. Reads existing local `vec4 src`, mutates
     * `src` in place. Caller must declare `uniform float uRedMaskStrength;`
     * and bind it per frame; gating on `uRedMaskStrength > 0.5` is included
     * so legacy cars where the uniform is never written (driver default 0.0)
     * pass through cleanly.
     *
     * <p>The HAL composites {@code displayed = (1-α)·orig + α·(1,0,0)}.
     * The red excess {@code α ≈ src.r - max(src.g, src.b)} estimates the
     * blend strength per pixel, and we recover
     * {@code orig = (src - α·(1,0,0)) / (1-α)}. We only apply this when
     * the pixel is meaningfully red-shifted ({@code r > g+0.05 AND r > b+0.05}),
     * sparing warm tones (sunset, taillights, red cars) where g/b are close
     * to r. The opaque calibration banner where α saturates produces an
     * implausible (clamped-negative) {@code orig.r}; we leave those pixels
     * as-is — the goal is the overall translucent wash, not the banner.
     */
    /**
     * GLSL fragment-shader fragment that mirrors oem's
     * {@code APACropFilter(240, 0)} (oem {@code ml/C7609b.java}): trims
     * {@code uApaCenterInset} of producer-UV off the LEFT and RIGHT outer
     * edges of the full producer, leaves the vertical axis untouched.
     *
     * <p>Caller declares {@code uniform float uApaCenterInset;} and runs
     * this snippet on a {@code vec2 samplePos} that is already in producer
     * UV {@code [0, 1]^2}. The snippet linearly remaps
     * {@code samplePos.x: [0, 1] -> [inset, 1 - inset]} so the consumer
     * sees the byd_apa producer's middle band stretched horizontally to
     * its full output rect, exactly like oem. Default uniform 0 → no-op
     * (legacy path bit-exact).
     *
     * <p>Oem's filter cropped the FULL producer surface (the unstitched
     * 4-up byd_apa frame) before its per-role lens-select. Our pipeline
     * skips oem's intermediate FBOs and samples directly from the OES
     * texture, so we apply the same horizontal remap right before the
     * {@code texture2D} sample — algebraically identical effect.
     */
    public static final String APA_CENTER_INSET_GLSL =
        "    if (uApaCenterInset > 0.0001) {\n" +
        "        // Linear remap samplePos.x: [0, 1] -> [inset, 1 - inset].\n" +
        "        samplePos.x = uApaCenterInset\n" +
        "                    + samplePos.x * (1.0 - 2.0 * uApaCenterInset);\n" +
        "    }\n";

    public static final String RED_MASK_GLSL =
        "    if (uRedMaskStrength > 0.5) {\n" +
        "        float gb = max(src.g, src.b);\n" +
        "        if (src.r > gb + 0.05) {\n" +
        "            // Inverse alpha-blend the translucent red wash.\n" +
        "            //   src   = (1-a)*orig + a*(1,0,0)\n" +
        "            //   orig  = (src - a*(1,0,0)) / (1-a)\n" +
        "            // a is the per-pixel red excess; clamp to keep the\n" +
        "            // 1/(1-a) factor numerically sane on near-saturated reds.\n" +
        "            float a = clamp(src.r - gb, 0.0, 0.85);\n" +
        "            float inv = 1.0 / max(1.0 - a, 0.05);\n" +
        "            vec3 orig = vec3((src.r - a) * inv, src.g * inv, src.b * inv);\n" +
        "            src = vec4(clamp(orig, 0.0, 1.0), src.a);\n" +
        "        }\n" +
        "    }\n";
    
    /**
     * Creates an external texture for camera input.
     * 
     * External textures (GL_TEXTURE_EXTERNAL_OES) are required for receiving
     * camera frames via SurfaceTexture. They handle YUV-to-RGB conversion
     * automatically in hardware.
     * 
     * @return Texture ID for the created external texture
     */
    public static int createExternalTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        checkGlError("glGenTextures");
        
        int textureId = textures[0];
        
        // Bind as external texture
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        checkGlError("glBindTexture");
        
        // Set texture parameters (required for external textures)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameteri");
        
        logger.debug( "Created external texture: " + textureId);
        
        return textureId;
    }

    /**
     * Creates a standard 2D texture (GL_TEXTURE_2D) for direct CPU/GPU pixel upload.
     * Unlike GL_TEXTURE_EXTERNAL_OES, this texture is completely isolated from
     * SurfaceFlinger/Gralloc and does not require EGLImage or AHardwareBuffer.
     *
     * @return Texture ID for the created 2D texture
     */
    public static int create2DTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        checkGlError("glGenTextures");

        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        checkGlError("glBindTexture");

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGlError("glTexParameteri");

        logger.debug("Created 2D texture: " + textureId);
        return textureId;
    }
    
    /**
     * Creates an OpenGL program from vertex and fragment shader source code.
     * 
     * @param vertexShaderSource Vertex shader GLSL source code
     * @param fragmentShaderSource Fragment shader GLSL source code
     * @return Program ID for the compiled and linked program
     */
    public static int createProgram(String vertexShaderSource, String fragmentShaderSource) {
        // Compile vertex shader
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource);
        if (vertexShader == 0) {
            return 0;
        }
        
        // Compile fragment shader
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource);
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader);
            return 0;
        }
        
        // Create program and attach shaders
        int program = GLES20.glCreateProgram();
        checkGlError("glCreateProgram");
        
        if (program == 0) {
            logger.error( "Failed to create program");
            return 0;
        }
        
        GLES20.glAttachShader(program, vertexShader);
        checkGlError("glAttachShader (vertex)");
        
        GLES20.glAttachShader(program, fragmentShader);
        checkGlError("glAttachShader (fragment)");
        
        // Link program
        GLES20.glLinkProgram(program);
        
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        
        if (linkStatus[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            logger.error( "Failed to link program: " + log);
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            return 0;
        }
        
        // Shaders can be deleted after linking
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);
        
        logger.debug( "Created program: " + program);
        
        return program;
    }
    
    /**
     * Compiles a shader from source code.
     * 
     * @param shaderType GL_VERTEX_SHADER or GL_FRAGMENT_SHADER
     * @param source GLSL source code
     * @return Shader ID, or 0 if compilation failed
     */
    private static int compileShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        checkGlError("glCreateShader");
        
        if (shader == 0) {
            logger.error( "Failed to create shader");
            return 0;
        }
        
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        
        if (compileStatus[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            String shaderTypeName = (shaderType == GLES20.GL_VERTEX_SHADER) ? "vertex" : "fragment";
            logger.error( "Failed to compile " + shaderTypeName + " shader: " + log);
            logger.error( "Shader source:\n" + source);
            GLES20.glDeleteShader(shader);
            return 0;
        }
        
        return shader;
    }
    
    /**
     * Creates a FloatBuffer from a float array.
     * 
     * FloatBuffers are required for passing vertex data to OpenGL.
     * 
     * @param data Float array containing vertex data
     * @return FloatBuffer with native byte order
     */
    public static FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4); // 4 bytes per float
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }
    
    /**
     * Checks for OpenGL errors and logs them.
     * 
     * @param operation Description of the operation being checked
     */
    public static void checkGlError(String operation) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            String errorMsg = String.format("%s: GL error 0x%x (%d)", operation, error, error);
            logger.error( errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }
    
    /**
     * Deletes an OpenGL texture.
     * 
     * @param textureId Texture ID to delete
     */
    public static void deleteTexture(int textureId) {
        if (textureId != 0) {
            int[] textures = new int[] { textureId };
            GLES20.glDeleteTextures(1, textures, 0);
            checkGlError("glDeleteTextures");
        }
    }
    
    /**
     * Deletes an OpenGL program.
     * 
     * @param programId Program ID to delete
     */
    public static void deleteProgram(int programId) {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId);
            checkGlError("glDeleteProgram");
        }
    }
    
    /**
     * Gets the maximum texture size supported by the GPU.
     * 
     * @return Maximum texture dimension in pixels
     */
    public static int getMaxTextureSize() {
        int[] maxSize = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0);
        return maxSize[0];
    }
    
    /**
     * Logs OpenGL version and renderer information.
     */
    public static void logGlInfo() {
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String vendor = GLES20.glGetString(GLES20.GL_VENDOR);
        
        logger.info( "OpenGL ES Version: " + version);
        logger.info( "Renderer: " + renderer);
        logger.info( "Vendor: " + vendor);
        logger.info( "Max Texture Size: " + getMaxTextureSize());
    }
}
