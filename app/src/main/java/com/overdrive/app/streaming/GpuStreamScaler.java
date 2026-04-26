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
    private int uApaModeLocation;
    private int aPositionLocation;
    private int aTexCoordLocation;
    
    // View mode: 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left, 5=Raw
    private volatile int currentViewMode = 0;
    private volatile int cameraLayout = 0;  // 0=4-cam, 1=APA, 2=3-cam
    
    // Vertex data
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    // Configuration
    private final int outputWidth;
    private final int outputHeight;
    
    // Fullscreen quad vertices
    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    
    // Texture coordinates (flipped vertically)
    private static final float[] TEX_COORDS = {
        0.0f, 1.0f,
        1.0f, 1.0f,
        0.0f, 0.0f,
        1.0f, 0.0f
    };
    
    // Simple passthrough vertex shader
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";
    
    // Fragment shader - supports 4-cam mosaic, 3-cam mosaic, APA passthrough, single view, raw strip
    // uViewMode: 0=Mosaic, 1=Front, 2=Right, 3=Rear, 4=Left, 5=Raw strip
    // uApaMode: 0.0=4-camera, 1.0=APA passthrough, 2.0=3-camera mosaic
    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES uCameraTex;\n" +
        "uniform int uViewMode;\n" +
        "uniform float uApaMode;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    vec2 samplePos;\n" +
        "    if (uViewMode == 5) {\n" +
        "        samplePos = vTexCoord;\n" +
        "    } else if (uApaMode > 1.5) {\n" +
        "        // 3-camera: TL=Front, BL=Rear, Right=Side\n" +
        "        if (uViewMode == 1) { samplePos = vec2(0.75 + vTexCoord.x * 0.25, vTexCoord.y); }\n" +  // Front
        "        else if (uViewMode == 3) { samplePos = vec2(vTexCoord.x * 0.25, vTexCoord.y); }\n" +     // Rear
        "        else if (uViewMode == 2 || uViewMode == 4) { samplePos = vec2(0.25 + vTexCoord.x * 0.5, vTexCoord.y); }\n" + // Side
        "        else {\n" +
        "            // Mosaic for 3-cam\n" +
        "            if (vTexCoord.x < 0.5) {\n" +
        "                float lx = vTexCoord.x * 0.5;\n" +
        "                float ly = mod(vTexCoord.y, 0.5) * 2.0;\n" +
        "                if (vTexCoord.y < 0.5) { samplePos = vec2(lx + 0.75, ly); }\n" +
        "                else { samplePos = vec2(lx, ly); }\n" +
        "            } else {\n" +
        "                samplePos = vec2(0.25 + (vTexCoord.x - 0.5) * 1.0 * 0.5, vTexCoord.y);\n" +
        "            }\n" +
        "        }\n" +
        "    } else if (uApaMode > 0.5) {\n" +
        "        samplePos = vTexCoord;\n" +
        "    } else if (uViewMode == 0) {\n" +
        "        vec2 gridPos = step(0.5, vTexCoord);\n" +
        "        float stripOffsetX = 0.75 - (gridPos.x * 0.25) - (gridPos.y * 0.75) + (gridPos.x * gridPos.y * 0.50);\n" +
        "        float localX = mod(vTexCoord.x, 0.5) * 0.5;\n" +
        "        float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +
        "        samplePos = vec2(localX + stripOffsetX, localY);\n" +
        "    } else {\n" +
        "        float stripIndex;\n" +
        "        if (uViewMode == 1) stripIndex = 3.0;\n" +
        "        else if (uViewMode == 2) stripIndex = 2.0;\n" +
        "        else if (uViewMode == 3) stripIndex = 0.0;\n" +
        "        else stripIndex = 1.0;\n" +
        "        float startX = stripIndex * 0.25;\n" +
        "        samplePos = vec2(startX + (vTexCoord.x * 0.25), vTexCoord.y);\n" +
        "    }\n" +
        "    gl_FragColor = texture2D(uCameraTex, samplePos);\n" +
        "}\n";
    
    public GpuStreamScaler(int outputWidth, int outputHeight) {
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
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
        this.eglCore = eglCore;
        
        // Get encoder's input surface
        encoderInputSurface = encoder.getInputSurface();
        if (encoderInputSurface == null) {
            throw new RuntimeException("Stream encoder input surface is null");
        }
        
        // Create EGL surface from encoder surface
        encoderSurface = eglCore.createWindowSurface(encoderInputSurface);
        
        // Compile shaders
        programId = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (programId == 0) {
            throw new RuntimeException("Failed to create shader program");
        }
        
        // Get locations
        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(programId, "aTexCoord");
        uCameraTexLocation = GLES20.glGetUniformLocation(programId, "uCameraTex");
        uViewModeLocation = GLES20.glGetUniformLocation(programId, "uViewMode");
        uApaModeLocation = GLES20.glGetUniformLocation(programId, "uApaMode");
        
        GlUtil.checkGlError("glGetLocation");
        
        // Create vertex buffers
        vertexBuffer = GlUtil.createFloatBuffer(VERTEX_COORDS);
        texCoordBuffer = GlUtil.createFloatBuffer(TEX_COORDS);
        
        logger.info("GpuStreamScaler initialized: " + outputWidth + "×" + outputHeight);
    }
    
    /**
     * Renders a frame to the stream encoder.
     * 
     * @param cameraTextureId Camera texture ID
     */
    public void drawFrame(int cameraTextureId) {
        // Make encoder surface current
        eglCore.makeCurrent(encoderSurface);
        
        // Set viewport
        GLES20.glViewport(0, 0, outputWidth, outputHeight);
        
        // Clear
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        
        // Use shader
        GLES20.glUseProgram(programId);
        
        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(uCameraTexLocation, 0);
        
        // Set view mode (0=Mosaic, 1-4=Single camera, 5=Raw)
        GLES20.glUniform1i(uViewModeLocation, currentViewMode);
        GLES20.glUniform1f(uApaModeLocation, (float) cameraLayout);
        
        // Set up vertex attributes
        GLES20.glEnableVertexAttribArray(aPositionLocation);
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        
        GLES20.glEnableVertexAttribArray(aTexCoordLocation);
        GLES20.glVertexAttribPointer(aTexCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        
        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        
        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(aPositionLocation);
        GLES20.glDisableVertexAttribArray(aTexCoordLocation);
        
        // Submit to encoder
        eglCore.swapBuffers(encoderSurface);
    }
    
    /**
     * Sets the view mode for streaming.
     * 
     * @param mode 0=Mosaic (2x2 grid), 1=Front(cam4), 2=Right(cam3), 3=Rear(cam1), 4=Left(cam2), 5=Raw strip
     */
    public void setViewMode(int mode) {
        if (mode >= 0 && mode <= 5) {
            this.currentViewMode = mode;
            String[] modeNames = {"Mosaic", "Front", "Right", "Rear", "Left", "Raw"};
            logger.info("Stream view mode set to " + mode + " (" + modeNames[mode] + ")");
        }
    }
    
    /**
     * Gets the current view mode.
     */
    public int getViewMode() {
        return currentViewMode;
    }
    
    /**
     * Sets APA mode / camera layout.
     * 0=4-camera, 1=APA passthrough, 2=3-camera
     */
    public void setApaMode(boolean apa) {
        this.cameraLayout = apa ? 1 : 0;
    }
    
    public void setCameraLayout(int layout) {
        this.cameraLayout = layout;
    }
    
    /**
     * Releases all resources.
     */
    public void release() {
        if (programId != 0) {
            GlUtil.deleteProgram(programId);
            programId = 0;
        }
        
        if (encoderSurface != null) {
            eglCore.destroySurface(encoderSurface);
            encoderSurface = null;
        }
        
        logger.info("GpuStreamScaler released");
    }
}
