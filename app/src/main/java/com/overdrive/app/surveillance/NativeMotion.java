package com.overdrive.app.surveillance;

import java.nio.ByteBuffer;

/** JNI entry points for surveillance motion detection and texture tracking. */
public class NativeMotion {
    private static boolean libraryLoaded = false;
    private static String loadError = null;
    
    static {
        try {
            System.loadLibrary("surveillance");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            loadError = e.getMessage();
            // Library not found - will be loaded lazily when nativeLibDir is known
            System.err.println("NativeMotion: libsurveillance.so not found in default path, will try explicit path later");
        }
    }
    
    /**
     * Try to load the native library from an explicit path.
     * Call this from CameraDaemon after getting the nativeLibDir.
     * 
     * @param nativeLibDir The app's native library directory
     * @return true if library is now loaded
     */
    public static boolean tryLoadLibrary(String nativeLibDir) {
        if (libraryLoaded) return true;
        
        try {
            // Try explicit path
            String libPath = nativeLibDir + "/libsurveillance.so";
            System.load(libPath);
            libraryLoaded = true;
            System.out.println("NativeMotion: Loaded from " + libPath);
            return true;
        } catch (UnsatisfiedLinkError e) {
            loadError = e.getMessage();
            System.err.println("NativeMotion: Failed to load from explicit path: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if the native library is loaded.
     * @return true if library is loaded and ready
     */
    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }
    
    /**
     * Get the error message if library failed to load.
     * @return error message or null if loaded successfully
     */
    public static String getLoadError() {
        return loadError;
    }
    
    /**
     * Checks if MOG2 is available (requires OpenCV).
     * 
     * @return true if MOG2 is available, false if only SAD is available
     */
    public static native boolean isMog2Available();

    /**
     * Resets the process-global MOG2 background model (fresh sentry session).
     *
     * <p>Called from SurveillanceEngineGpu.enable() so a session never inherits
     * the previous parking spot's background (invariant I2). Cheap; safe to
     * call when OpenCV is absent (returns false).
     *
     * @return true if the model was reset, false if MOG2 is unavailable
     */
    public static native boolean resetMOG2();

    /**
     * Release the MOG2 background model (disarm teardown — audit R3b
     * Ext-14). Frees ~18.7MB of native model storage that otherwise stays
     * resident across disarm; enable() rebuilds via {@link #resetMOG2()}.
     * Safe against a straggler apply() (mutex-guarded natively). No-op when
     * OpenCV is absent or the model was never created.
     */
    public static native void releaseMOG2();

    /**
     * MOG2 background subtraction over the FULL 2x2 mosaic, with per-quadrant
     * foreground fractions.
     *
     * <p>One background-model update per call; the model is process-global and
     * single-stream, so callers must feed the SAME full-mosaic view every time
     * (per-quadrant feeding would corrupt learning). Quadrant indexing matches
     * {@code cropFromMosaic}: Q0=TL front, Q1=TR right, Q2=BL rear, Q3=BR left.
     *
     * <p>Single-threaded by contract: only the engine's frame-processing
     * thread may call this (same discipline as the V2 pipeline itself).
     *
     * @param rgbFrame     Direct ByteBuffer, full 640×480 RGB mosaic
     * @param width        Mosaic width (640)
     * @param height       Mosaic height (480)
     * @param learningRate OpenCV semantics: -1 = automatic (1/history),
     *                     1.0 = reseed background from this frame entirely
     * @param outQuadrantFractions float[4] out param — per-quadrant foreground
     *                     pixel fraction in [0,1]
     * @return whole-frame foreground fraction in [0,1], or negative on error /
     *         OpenCV absent (callers MUST treat negative as "no evidence" —
     *         the consuming channel is adding-only and fails closed, I9)
     */
    public static native float computeMOG2Quadrants(
        ByteBuffer rgbFrame,
        int width,
        int height,
        float learningRate,
        float[] outQuadrantFractions
    );
    
    /**
     * GRAYSCALE GRID MOTION DETECTION - Deterministic Computer Vision
     * 
     * Replaces the complex edge-based SOTA approach with a simple, robust algorithm:
     * 1. Convert RGB to Grayscale (eliminates color noise from LEDs, etc.)
     * 2. Grid-based block comparison (32x32 blocks)
     * 3. Shadow Filter: Per-pixel luma threshold (ignores faint changes < shadowThreshold)
     * 4. Light Filter: Global change rejection (ignores if >80% blocks change = light switch)
     * 
     * Why this works:
     * - Grayscale eliminates color noise (LED bulb color shifts)
     * - Per-pixel threshold eliminates shadows (shadows are faint, ~10-20 luma diff)
     * - Real objects have HIGH contrast (>40 luma diff)
     * - Global filter catches auto-exposure and light switches
     * 
     * Returns a 64-bit packed result:
     * - Bit 63:    isFlash (1 = global light change, 0 = real motion)
     * - Bit 48-62: temporalActiveBlocks (blocks with temporal consistency)
     * - Bit 32-47: maxY (bottom of motion bounding box - for distance estimation)
     * - Bit 16-31: minY (top of motion bounding box - for object size)
     * - Bit 0-15:  rawActiveBlocks (before temporal filtering)
     * 
     * Parameter mapping:
     * - sensitivity: Maps to alarmBlockThreshold (0.02=HIGH/2blocks, 0.04=MED/3blocks, 0.06=LOW/5blocks)
     * - flashImmunity: Maps to shadowThreshold (0=30, 1=40, 2=50, 3=60)
     * - temporalFrames: Required consecutive frames (0=disabled, 3=recommended)
     * - useChroma: IGNORED in grayscale mode (kept for API compatibility)
     * 
     * @param currentFrame Direct ByteBuffer containing current frame RGB data
     * @param referenceFrame Direct ByteBuffer containing reference frame RGB data (N-3 from ring buffer)
     * @param width Frame width
     * @param height Frame height
     * @param blockSize Size of each grid block (8/16/32/64)
     * @param sensitivity Per-block sensitivity threshold (0.0-1.0, typically 0.04)
     * @param flashImmunity Shadow rejection level (0=OFF, 1=NORMAL, 2=STRICT, 3=MAX)
     * @param temporalFrames Required consecutive frames (0=disabled, 3=recommended)
     * @param useChroma IGNORED - kept for API compatibility
     * @return Packed long with motion data (see bit layout above)
     */
    public static native long computeEdgeMotionSOTA(
        ByteBuffer currentFrame,
        ByteBuffer referenceFrame,
        int width,
        int height,
        int blockSize,
        float sensitivity,
        int flashImmunity,
        int temporalFrames,
        boolean useChroma
    );
    
    /**
     * Resets the temporal history buffer.
     * 
     * Call this when:
     * - Surveillance is enabled/disabled
     * - Camera view changes
     * - Block size changes
     */
    public static native void resetTemporalHistory();
    
    // ========================================================================
    // UNIFIED SENSITIVITY API - Single Slider Control
    // ========================================================================
    
    /**
     * UNIFIED GRAYSCALE GRID MOTION DETECTION
     * 
     * This is the production API that accepts explicit thresholds.
     * The Java layer maps a single "Sensitivity" slider (0-100%) to these params.
     * 
     * @param currentFrame Direct ByteBuffer containing current frame RGB data
     * @param referenceFrame Direct ByteBuffer containing reference frame RGB data
     * @param width Frame width
     * @param height Frame height
     * @param shadowThreshold Per-pixel luma diff to count as changed (25-90)
     *                        Lower = more sensitive to faint motion
     *                        Higher = ignores shadows/lights
     * @param densityThreshold Pixels per block that must change (8-64)
     *                         Lower = easier to trigger a block
     *                         Higher = needs more pixels changed
     * @param alarmBlockThreshold Blocks needed to trigger alarm (1-5)
     *                            Lower = more sensitive (1 block = any motion)
     *                            Higher = needs larger object
     * @return Packed long with motion data (same format as computeEdgeMotionSOTA)
     */
    public static native long computeGrayscaleGrid(
        ByteBuffer currentFrame,
        ByteBuffer referenceFrame,
        int width,
        int height,
        int shadowThreshold,
        int densityThreshold,
        int alarmBlockThreshold
    );
    
    // ========================================================================
    // SOTA Result Unpacking Helpers
    // ========================================================================
    
    /**
     * Unpacks the isFlash flag from SOTA result.
     * @param result Result from computeEdgeMotionSOTA
     * @return true if flash was detected
     */
    public static boolean unpackIsFlash(long result) {
        return (result >>> 63) != 0;
    }
    
    /**
     * Unpacks the temporal active blocks count from SOTA result.
     * @param result Result from computeEdgeMotionSOTA
     * @return Number of blocks with temporal consistency
     */
    public static int unpackTemporalBlocks(long result) {
        return (int)((result >>> 48) & 0x7FFF);
    }
    
    /**
     * Unpacks the maxY (bottom of motion) from SOTA result.
     * @param result Result from computeEdgeMotionSOTA
     * @return Y coordinate of bottom of motion bounding box
     */
    public static int unpackMaxY(long result) {
        return (int)((result >>> 32) & 0xFFFF);
    }
    
    /**
     * Unpacks the minY (top of motion) from SOTA result.
     * @param result Result from computeEdgeMotionSOTA
     * @return Y coordinate of top of motion bounding box
     */
    public static int unpackMinY(long result) {
        return (int)((result >>> 16) & 0xFFFF);
    }
    
    /**
     * Unpacks the raw active blocks count from SOTA result.
     * @param result Result from computeEdgeMotionSOTA
     * @return Number of blocks active in current frame (before temporal filtering)
     */
    public static int unpackRawBlocks(long result) {
        return (int)(result & 0xFFFF);
    }
    
    /**
     * Calculates motion height in pixels from SOTA result.
     * @param result Result from computeEdgeMotionSOTA
     * @return Height of motion bounding box in pixels
     */
    public static int unpackMotionHeight(long result) {
        int maxY = unpackMaxY(result);
        int minY = unpackMinY(result);
        return maxY - minY;
    }
    
    // ========================================================================
    // Pipeline V2: Per-Quadrant 6-Stage Motion Detection
    // ========================================================================
    
    /**
     * Initialize the V2 pipeline state. Call once before first processFrameV2.
     */
    public static native void initPipelineV2();
    
    /**
     * Process a full mosaic frame through the V2 per-quadrant pipeline.
     * 
     * @param frameBuffer   640×480 RGB direct ByteBuffer (from GpuDownscaler)
     * @param width         Frame width (640)
     * @param height        Frame height (480)
     * @param configBuffer  PipelineConfigV2 serialized as direct ByteBuffer
     * @param resultBuffer  Output: 4 × QuadrantResultV2 as direct ByteBuffer
     */
    public static native void processFrameV2(
        java.nio.ByteBuffer frameBuffer,
        int width, int height,
        java.nio.ByteBuffer configBuffer,
        java.nio.ByteBuffer resultBuffer
    );
    
    /**
     * Get the size of PipelineConfigV2 struct (for ByteBuffer allocation).
     */
    public static native int getPipelineConfigSize();
    
    /**
     * Get the size of one QuadrantResultV2 struct (for ByteBuffer allocation).
     * Total result buffer size = getQuadrantResultSize() * 4.
     */
    public static native int getQuadrantResultSize();

    /** Geometry/version descriptor; absent on legacy seven-row native libraries. */
    public static native int[] getPipelineGeometryV2();
    
    /**
     * Set per-quadrant ROI block mask.
     * 
     * @param quadrant Quadrant index (0-3: Front, Right, Rear, Left)
     * @param blockMask current grid mask, or a legacy 70-cell mask, where 1=enabled, 0=disabled.
     *                  Pass null to clear ROI (all blocks enabled).
     */
    public static native void setQuadrantRoi(int quadrant, byte[] blockMask);
    
    // ========================================================================
    // Texture Tracker (YOLO + NCC Hybrid VOT)
    // ========================================================================
    
    /** Initialize the native texture tracker. */
    public static native void initTracker();
    
    /**
     * Start tracking an object detected by YOLO.
     * @return Track slot index (0-3) or -1 if no slot available
     */
    public static native int trackerStartTrack(
            byte[] frameRgb, int width, int height,
            int quadrant, int classId,
            int x, int y, int w, int h,
            long nowMs);
    
    /** Update all active tracks in a quadrant with a new frame. */
    public static native void trackerUpdate(
            byte[] frameRgb, int width, int height,
            int quadrant, long nowMs);
    
    /** Update from the borrowed 640x480 direct RGB mosaic (no retained reference). */
    public static native void trackerUpdateMosaic(
            ByteBuffer mosaicRgb, int quadrant, long nowMs);

    /** Check if any track is active in the given quadrant. */
    public static native boolean trackerHasActiveTrack(int quadrant);
    
    /**
     * Get the tracked bounding box for a quadrant.
     * @return float[7] = {x, y, w, h, confidence, classId, active} or null
     */
    public static native float[] trackerGetTrackBox(int quadrant);
    
    /** Drop (kill) the track in a quadrant. */
    public static native void trackerDropTrack(int quadrant);
    
    /** Check if a track needs YOLO heartbeat verification. */
    public static native boolean trackerNeedsYoloHeartbeat(int quadrant);
    
    /** Confirm YOLO heartbeat (resets verification timer). */
    public static native void trackerConfirmHeartbeat(int quadrant, long nowMs);
    
    /**
     * Refresh a track's template with a new YOLO bounding box (handles scale change).
     */
    public static native void trackerRefreshTemplate(
            byte[] frameRgb, int width, int height,
            int quadrant,
            int newX, int newY, int newW, int newH,
            long nowMs);
}
