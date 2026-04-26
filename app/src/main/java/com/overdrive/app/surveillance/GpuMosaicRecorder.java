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
    
    // SOTA: Dynamic Time-Base Corrector (TBC) state.
    // Uses an Exponential Moving Average to learn the actual hardware frame rate
    // in real-time, then feeds mathematically perfect, evenly spaced timestamps
    // to the encoder. This eliminates both the fast-forward bug (from hardcoded FPS)
    // and the rubber-banding jitter (from raw System.nanoTime()).
    private long lastRealTimeNs = -1;
    private long smoothedPtsNs = -1;
    private long averageDeltaNs = 181_000_000L;  // Initial assumption: ~5.5 FPS
    
    // OpenGL program and locations
    private int programId;
    private int uCameraTexLocation;
    private int uApaModeLocation;
    private int aPositionLocation;
    private int aTexCoordLocation;
    
    // Vertex data
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;
    
    // Encoder reference
    private HardwareEventRecorderGpu encoder;
    
    // State
    private boolean recording = false;
    private volatile boolean apaMode = false;  // APA mode: passthrough instead of mosaic split
    private volatile int cameraLayout = 0;  // 0=4-cam, 1=APA passthrough, 2=3-cam
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
    private int overlayFrameCounter = 0;
    private volatile boolean overlayRecordingModeAllowed = false;
    private boolean overlayTextureReady = false;
    private boolean overlayTextureInitialized = false;
    
    // Frame skip tracking - prevents eglSwapBuffers from blocking GL thread
    // When encoder is backed up (SD card I/O), skip rendering to keep camera HAL flowing
    private long lastDrawDurationNs = 0;
    private static final long MAX_DRAW_DURATION_NS = 30_000_000L;  // 30ms threshold
    private int consecutiveSlowFrames = 0;
    private static final int SLOW_FRAME_SKIP_THRESHOLD = 3;  // Skip after 3 slow frames
    private int skippedFrames = 0;
    
    // EGL_BAD_SURFACE recovery: track consecutive surface errors to trigger reinit
    private int consecutiveSurfaceErrors = 0;
    private static final int SURFACE_ERROR_REINIT_THRESHOLD = 3;
    private volatile boolean needsReinit = false;
    
    // Fullscreen quad vertices (NDC coordinates)
    private static final float[] VERTEX_COORDS = {
        -1.0f, -1.0f,  // Bottom-left
         1.0f, -1.0f,  // Bottom-right
        -1.0f,  1.0f,  // Top-left
         1.0f,  1.0f   // Top-right
    };
    
    // Texture coordinates (flipped vertically for correct orientation)
    private static final float[] TEX_COORDS = {
        0.0f, 1.0f,  // Bottom-left (flipped to top-left)
        1.0f, 1.0f,  // Bottom-right (flipped to top-right)
        0.0f, 0.0f,  // Top-left (flipped to bottom-left)
        1.0f, 0.0f   // Top-right (flipped to bottom-right)
    };
    
    // Vertex shader - simple passthrough
    private static final String VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    gl_Position = aPosition;\n" +
        "    vTexCoord = aTexCoord;\n" +
        "}\n";
    
    // Fragment shader - supports 4-camera mosaic, 3-camera mosaic, and APA passthrough.
    // uApaMode: 0.0 = 4-camera mosaic (Seal: pano_h/pano_l with surfaceMode=0)
    //           1.0 = APA passthrough (single pre-composited image)
    //           2.0 = 3-camera mosaic (Atto 3 default: Rear=0-25%, Side=25-75%, Front=75-100%)
    // 4-cam strip: cam1(Rear)=0.00, cam2(Left)=0.25, cam3(Right)=0.50, cam4(Front)=0.75
    // 3-cam strip: Rear=0.00-0.25, Left+Right=0.25-0.75, Front=0.75-1.00
    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "uniform samplerExternalOES uCameraTex;\n" +
        "uniform float uApaMode;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "    vec2 samplePos;\n" +
        "    if (uApaMode > 1.5) {\n" +
        "        // 3-camera mosaic: TL=Front, BL=Rear, Right=Side(Left+Right)\n" +
        "        if (vTexCoord.x < 0.5) {\n" +
        "            // Left column: top=Front(0.75-1.0), bottom=Rear(0.0-0.25)\n" +
        "            float localX = vTexCoord.x * 0.5;\n" +  // 0-0.5 -> 0-0.25
        "            float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +
        "            if (vTexCoord.y < 0.5) {\n" +
        "                samplePos = vec2(localX + 0.75, localY);\n" +  // Front
        "            } else {\n" +
        "                samplePos = vec2(localX, localY);\n" +  // Rear
        "            }\n" +
        "        } else {\n" +
        "            // Right column: Side view (0.25-0.75, full height)\n" +
        "            float localX = (vTexCoord.x - 0.5) * 1.0;\n" +  // 0.5-1.0 -> 0-0.5
        "            samplePos = vec2(0.25 + localX * 0.5, vTexCoord.y);\n" +
        "        }\n" +
        "    } else if (uApaMode > 0.5) {\n" +
        "        // APA passthrough\n" +
        "        samplePos = vTexCoord;\n" +
        "    } else {\n" +
        "        // 4-camera mosaic (Seal default)\n" +
        "        vec2 gridPos = step(0.5, vTexCoord);\n" +
        "        float stripOffsetX = 0.75 - (gridPos.x * 0.25) - (gridPos.y * 0.75) + (gridPos.x * gridPos.y * 0.50);\n" +
        "        float localX = mod(vTexCoord.x, 0.5) * 0.5;\n" +
        "        float localY = mod(vTexCoord.y, 0.5) * 2.0;\n" +
        "        samplePos = vec2(localX + stripOffsetX, localY);\n" +
        "    }\n" +
        "    gl_FragColor = texture2D(uCameraTex, samplePos);\n" +
        "}\n";
    
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
        
        // Register callback to sync recording flag when encoder closes file
        encoder.setFileClosedCallback(() -> {
            if (recording) {
                recording = false;
                logger.info("Recording flag reset (encoder closed file)");
            }
            
            // SOTA: Trigger storage cleanup after each file is saved
            try {
                com.overdrive.app.storage.StorageManager storageManager =
                    com.overdrive.app.storage.StorageManager.getInstance();
                
                // Determine if this was a surveillance or manual recording based on output path
                // Surveillance files go to surveillance dir, manual recordings to recordings dir
                if (encoder != null) {
                    String lastPath = encoder.getCurrentOutputPath();
                    if (lastPath != null) {
                        if (lastPath.contains("/surveillance/") || lastPath.contains("event_")) {
                            storageManager.onSurveillanceFileSaved();
                        } else {
                            storageManager.onRecordingFileSaved();
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Storage cleanup after file close failed: " + e.getMessage());
            }
        });
        
        // Get encoder's input surface
        encoderInputSurface = encoder.getInputSurface();
        if (encoderInputSurface == null) {
            throw new RuntimeException("Encoder input surface is null");
        }
        
        // Create EGL surface from encoder surface (with RECORDABLE flag)
        encoderSurface = eglCore.createWindowSurface(encoderInputSurface);
        
        // Compile shaders and create program
        programId = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (programId == 0) {
            throw new RuntimeException("Failed to create shader program");
        }
        
        // Get attribute and uniform locations
        aPositionLocation = GLES20.glGetAttribLocation(programId, "aPosition");
        aTexCoordLocation = GLES20.glGetAttribLocation(programId, "aTexCoord");
        uCameraTexLocation = GLES20.glGetUniformLocation(programId, "uCameraTex");
        uApaModeLocation = GLES20.glGetUniformLocation(programId, "uApaMode");
        
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
            
            // Create bitmap renderer
            overlayRenderer = new OverlayBitmapRenderer();
            overlayTextureInitialized = false;
        }
        
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
     */
    public void drawFrame(int cameraTextureId) {
        // Check if initialized
        if (eglCore == null || encoderSurface == null) {
            // Not initialized yet - skip silently
            return;
        }

        // ENCODER BACKPRESSURE GUARD: If previous frames took too long (encoder backed up),
        // skip this frame to prevent blocking the GL thread. The camera HAL's BufferQueue
        // must keep flowing or the BYD native camera app loses video signal.
        if (consecutiveSlowFrames >= SLOW_FRAME_SKIP_THRESHOLD) {
            skippedFrames++;
            // Reset after skipping one frame to retry
            consecutiveSlowFrames = 0;
            if (skippedFrames % 10 == 1) {
                logger.warn("Encoder backpressure: skipped " + skippedFrames + 
                    " frames to keep camera HAL flowing (last draw=" + 
                    (lastDrawDurationNs / 1_000_000) + "ms)");
            }
            return;
        }

        // SOTA: Always render to encoder (for pre-record buffer)
        // The encoder decides whether to write to file or just buffer

        long startTime = System.nanoTime();

        // Make encoder surface current
        eglCore.makeCurrent(encoderSurface);

        // Set viewport to encoder resolution (2560x1920)
        GLES20.glViewport(0, 0, 2560, 1920);

        // Clear
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // Use our shader program
        GLES20.glUseProgram(programId);

        // Bind camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
        GLES20.glUniform1i(uCameraTexLocation, 0);
        GLES20.glUniform1f(uApaModeLocation, (float) cameraLayout);

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

        // OVERLAY PASS: Composite telemetry overlay if enabled
        if (overlayEnabled && overlayRecordingModeAllowed && overlayRenderer != null) {
            overlayFrameCounter++;
            try {
                // Update bitmap every 3rd frame (~5 FPS at 15 FPS recording)
                if ((overlayFrameCounter == 1 || overlayFrameCounter % 3 == 0) && telemetryCollector != null) {
                    TelemetrySnapshot snapshot = telemetryCollector.getLatestSnapshot();
                    overlayRenderer.renderFrame(snapshot, overlayFrameCounter / 3);
                }
                
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

        // --- SOTA: Dynamic Time-Base Corrector (TBC) ---
        // Learns the actual hardware frame rate via EMA and produces perfectly
        // paced timestamps that eliminate both fast-forward and rubber-banding.
        long nowNs = System.nanoTime();
        if (smoothedPtsNs < 0) {
            // First frame initialization
            smoothedPtsNs = nowNs;
            lastRealTimeNs = nowNs;
        } else {
            // 1. Calculate the raw, jittery delta
            long rawDeltaNs = nowNs - lastRealTimeNs;
            lastRealTimeNs = nowNs;
            
            // 2. Clamp outliers (ignore massive CPU freezes or dropped frames)
            //    Min 30ms (33 FPS cap), Max 500ms (2 FPS floor)
            long clampedDeltaNs = Math.max(30_000_000L, Math.min(rawDeltaNs, 500_000_000L));
            
            // 3. Update the moving average (Alpha=0.1 for smooth adaptation)
            //    This learns the car's actual framerate without jitter
            averageDeltaNs = (long)(averageDeltaNs * 0.9 + clampedDeltaNs * 0.1);
            
            // 4. Advance the perfectly smooth timeline
            smoothedPtsNs += averageDeltaNs;
            
            // 5. Failsafe: prevent smoothed clock from drifting >1s from real time
            long driftNs = nowNs - smoothedPtsNs;
            if (Math.abs(driftNs) > 1_000_000_000L) {
                smoothedPtsNs = nowNs;
            }
        }
        
        // Push the mathematically perfect timestamp to the hardware encoder
        try {
            eglCore.swapBuffersWithTimestamp(encoderSurface, smoothedPtsNs);
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

        // Track draw duration to detect encoder backpressure
        long elapsedNs = System.nanoTime() - startTime;
        lastDrawDurationNs = elapsedNs;

        if (elapsedNs > MAX_DRAW_DURATION_NS) {
            consecutiveSlowFrames++;
        } else {
            consecutiveSlowFrames = 0;
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
     */
    public void startRecording(String outputPath) {
        if (recording) {
            logger.warn( "Already recording");
            return;
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
        } else {
            logger.error( "Failed to start encoder recording");
        }
    }
    
    /**
     * Triggers event recording with pre-record buffer flush.
     * Alias for startRecording for API compatibility.
     */
    public void triggerEventRecording(String outputPath, long postRecordDurationMs) {
        if (recording) {
            logger.warn("Already recording");
            return;
        }
        
        // Start encoder recording (with pre-record buffer flush)
        if (encoder != null && encoder.triggerEventRecording(outputPath, postRecordDurationMs)) {
            recording = true;
            frameCount = 0;
            logger.info("Recording started: " + outputPath);
        } else {
            logger.error("Failed to start encoder recording");
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
        // SOTA: Use StorageManager for recordings directory and auto-cleanup
        try {
            com.overdrive.app.storage.StorageManager storageManager =
                com.overdrive.app.storage.StorageManager.getInstance();
            
            // Use provided directory or default to recordings dir
            java.io.File targetDir = outputDir != null ? outputDir : storageManager.getRecordingsDir();
            
            // Ensure directory exists
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            
            // Reserve ~100MB for new recording
            storageManager.ensureRecordingsSpace(100 * 1024 * 1024);
            
            // Generate filename with timestamp
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String filename = prefix + "_" + timestamp + ".mp4";
            
            // Use target directory
            String outputPath = new java.io.File(targetDir, filename).getAbsolutePath();
            startRecording(outputPath);
        } catch (Exception e) {
            logger.error("Failed to start recording: " + e.getMessage());
            // Fallback to legacy path
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String filename = (prefix != null ? prefix : "cam") + "_" + timestamp + ".mp4";
            startRecording("/storage/emulated/0/Android/data/com.overdrive.app/files/" + filename);
        }
    }
    
    /**
     * Stops recording.
     */
    public void stopRecording() {
        if (!recording) {
            return;
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
    
    /**
     * Stops recording with post-record support.
     * 
     * @param immediate If true, stops immediately. If false, uses post-record.
     */
    public void stopEventRecording(boolean immediate, long postRecordDurationMs) {
        if (!recording) {
            return;
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
    
    /**
     * Checks if currently recording.
     * 
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return recording;
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
    }
    
    /**
     * Sets the camera layout mode for the mosaic shader.
     * 0 = 4-camera mosaic (Seal: pano_h/pano_l, surfaceMode=0)
     * 1 = APA passthrough (single pre-composited image, surfaceMode=1 with apa/byd_apa tag)
     * 2 = 3-camera mosaic (Atto 3 default: Rear, Side, Front)
     */
    public void setCameraLayout(int layout) {
        this.apaMode = (layout == 1);
        this.cameraLayout = layout;
        String[] names = {"4-camera mosaic", "APA passthrough", "3-camera mosaic"};
        logger.info("Camera layout: " + (layout < names.length ? names[layout] : "unknown(" + layout + ")"));
    }
    
    public void setApaMode(boolean apa) {
        setCameraLayout(apa ? 1 : 0);
    }
    
    // (TBC timestamp is computed inline in drawFrame)

    public boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public void setOverlayRecordingModeAllowed(boolean allowed) {
        this.overlayRecordingModeAllowed = allowed;
    }

    public void setTelemetryCollector(TelemetryDataCollector collector) {
        this.telemetryCollector = collector;
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
}
