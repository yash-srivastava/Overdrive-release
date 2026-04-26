package com.overdrive.app.surveillance;

import java.nio.ByteBuffer;

/**
 * NativeMotion - JNI interface for advanced motion detection.
 * 
 * Provides two detection methods:
 * 1. SAD (Sum of Absolute Differences) - Fast, simple frame differencing
 * 2. MOG2 (Mixture of Gaussians) - Advanced background subtraction
 * 
 * MOG2 is superior because it:
 * - Learns the background (trees, clouds, lighting changes)
 * - Ignores shadows and gradual changes
 * - Only detects NEW objects entering the scene
 * - Reduces false positives by 90%
 * 
 * Performance: <1ms per frame on ARM64 with NEON
 */
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
     * Computes Sum of Absolute Differences between current and reference frames.
     * 
     * This is a lightweight motion detection algorithm that compares pixel
     * values between two frames. The result is normalized to 0.0-1.0 range.
     * 
     * Uses ARM NEON SIMD instructions for vectorized computation, achieving
     * <0.5ms processing time on 320x240 RGB frames.
     * 
     * @param currentFrame Direct ByteBuffer containing current frame RGB data
     * @param referenceFrame Direct ByteBuffer containing reference frame RGB data
     * @param width Frame width (typically 320)
     * @param height Frame height (typically 240)
     * @return Normalized motion score (0.0 = no motion, 1.0 = maximum motion)
     */
    public static native float computeSAD(
        ByteBuffer currentFrame,
        ByteBuffer referenceFrame,
        int width,
        int height
    );
    
    /**
     * Computes motion using MOG2 background subtraction with optional ROI mask.
     * 
     * MOG2 learns the background over time and only detects foreground objects.
     * This is the SOTA approach used by professional surveillance systems.
     * 
     * @param rgbFrame Direct ByteBuffer containing RGB frame data
     * @param width Frame width (typically 320)
     * @param height Frame height (typically 240)
     * @param roiMask Optional ROI mask (null = full frame, byte array with 0/1 values)
     * @param learningRate Learning rate (0.0-1.0, typically 0.001 for slow adaptation)
     * @return Normalized motion score (0.0 = no motion, 1.0 = maximum motion)
     */
    public static native float computeMOG2(
        ByteBuffer rgbFrame,
        int width,
        int height,
        byte[] roiMask,
        float learningRate
    );
    
    /**
     * Detects objects in the frame using YOLO or similar detector.
     * 
     * Returns bounding boxes with class labels and confidence scores.
     * Filters objects by minimum size to reduce false positives.
     * 
     * @param rgbFrame Direct ByteBuffer containing RGB frame data
     * @param width Frame width
     * @param height Frame height
     * @param minObjectSize Minimum object size (0.0-1.0, fraction of frame)
     * @param confidenceThreshold Minimum confidence (0.0-1.0)
     * @param classFilter Array of class IDs to detect (null = all classes)
     * @return Array of detected objects [x, y, w, h, classId, confidence, ...]
     */
    public static native float[] detectObjects(
        ByteBuffer rgbFrame,
        int width,
        int height,
        float minObjectSize,
        float confidenceThreshold,
        int[] classFilter
    );
    
    /**
     * Updates the reference frame with exponential moving average.
     * 
     * This slowly adapts the reference frame to changing lighting conditions
     * and static scene changes, preventing false positives.
     * 
     * Formula: reference = (1-alpha) * reference + alpha * current
     * 
     * @param currentFrame Direct ByteBuffer containing current frame RGB data
     * @param referenceFrame Direct ByteBuffer containing reference frame RGB data (modified in-place)
     * @param width Frame width (typically 320)
     * @param height Frame height (typically 240)
     * @param alpha Adaptation rate (0.0-1.0, typically 0.02 for slow adaptation)
     */
    public static native void updateReference(
        ByteBuffer currentFrame,
        ByteBuffer referenceFrame,
        int width,
        int height,
        float alpha
    );
    
    /**
     * Checks if MOG2 is available (requires OpenCV).
     * 
     * @return true if MOG2 is available, false if only SAD is available
     */
    public static native boolean isMog2Available();
    
    /**
     * Checks if object detection is available (requires YOLO model).
     * 
     * @return true if object detection is available
     */
    public static native boolean isObjectDetectionAvailable();
    
    /**
     * Loads YOLO model from assets.
     * 
     * @param assetManager Android AssetManager
     * @param paramPath Path to .param file in assets (e.g., "models/yolov8s.param")
     * @param binPath Path to .bin file in assets (e.g., "models/yolov8s.bin")
     * @return true if loaded successfully
     */
    public static native boolean loadYoloFromAssets(
        android.content.res.AssetManager assetManager,
        String paramPath,
        String binPath
    );
    
    /**
     * SOTA: Grid-Based Motion Detection.
     * 
     * Divides frame into blocks (e.g., 32x32) and checks if any block
     * exceeds the sensitivity threshold. This detects small moving objects
     * (like a walking person) that global SAD would miss.
     * 
     * Why this works:
     * - A person walking occupies ~500 pixels = 0.65% of 320x240 frame
     * - Global SAD with 5% threshold would NEVER detect them
     * - But that person fills 60% of ONE 32x32 block -> TRIGGER
     * 
     * Performance: <1ms with stride-2 subsampling
     * 
     * @param currentFrame Direct ByteBuffer containing current frame RGB data
     * @param referenceFrame Direct ByteBuffer containing reference frame RGB data
     * @param width Frame width
     * @param height Frame height
     * @param blockSize Size of each grid block (e.g., 32 for 32x32 blocks)
     * @param sensitivity Per-block sensitivity threshold (0.0-1.0, typically 0.15)
     * @return Number of blocks that exceeded the sensitivity threshold
     */
    public static native int computeGridMotion(
        ByteBuffer currentFrame,
        ByteBuffer referenceFrame,
        int width,
        int height,
        int blockSize,
        float sensitivity
    );
    
    /**
     * SOTA: Grid-Based Motion Detection with Shadow/Light Filtering.
     * 
     * Uses CHROMINANCE (color) instead of raw RGB to ignore brightness changes.
     * Light changes affect luminance (Y) but not chrominance (U/V).
     * 
     * How it works:
     * 1. Convert RGB to YUV color space
     * 2. Compare only U and V channels (ignore Y/brightness)
     * 3. Shadows and light changes are filtered out
     * 4. Real objects (people, cars) change color/texture -> detected
     * 
     * Use this instead of computeGridMotion() when:
     * - Camera is outdoors with changing sunlight
     * - Indoor with flickering lights
     * - Shadows from trees/clouds cause false positives
     * 
     * Performance: ~1.5ms (slightly slower than RGB due to color conversion)
     * 
     * @param currentFrame Direct ByteBuffer containing current frame RGB data
     * @param referenceFrame Direct ByteBuffer containing reference frame RGB data
     * @param width Frame width
     * @param height Frame height
     * @param blockSize Size of each grid block (e.g., 32 for 32x32 blocks)
     * @param sensitivity Per-block sensitivity threshold (0.0-1.0, typically 0.08)
     * @return Packed int: activeBlocks in lower 16 bits, isFlash flag in bit 16
     */
    public static native int computeGridMotionChroma(
        ByteBuffer currentFrame,
        ByteBuffer referenceFrame,
        int width,
        int height,
        int blockSize,
        float sensitivity
    );
    
    /**
     * SOTA: Edge-Based Motion Detection with Configurable Flash Immunity.
     * 
     * Uses GRADIENT/EDGE differencing instead of raw pixel differencing.
     * This is immune to light flashes because edges don't move during flashes.
     * 
     * How it works:
     * 1. Convert RGB to grayscale
     * 2. Apply Sobel gradient filter to extract edges
     * 3. Compare edge maps between frames (not raw pixels)
     * 4. If edges moved → real motion, if edges just got stronger → flash
     * 
     * Flash Immunity Levels:
     * - 0 = OFF (legacy pixel differencing, sensitive to flashes)
     * - 1 = LOW (edge-based, some flash filtering)
     * - 2 = MEDIUM (edge-based + brightness normalization) [DEFAULT]
     * - 3 = HIGH (edge-based + aggressive flash rejection)
     * 
     * Performance: ~1.5ms with stride-2 subsampling on ARM64
     * 
     * @param currentFrame Direct ByteBuffer containing current frame RGB data
     * @param referenceFrame Direct ByteBuffer containing reference frame RGB data
     * @param width Frame width
     * @param height Frame height
     * @param blockSize Size of each grid block (e.g., 32 for 32x32 blocks)
     * @param sensitivity Per-block sensitivity threshold (0.0-1.0, typically 0.04)
     * @param flashImmunity Flash immunity level (0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH)
     * @return Packed int: activeBlocks in lower 16 bits, isFlash flag in bit 16
     */
    public static native int computeEdgeMotion(
        ByteBuffer currentFrame,
        ByteBuffer referenceBuffer,
        int width,
        int height,
        int blockSize,
        float sensitivity,
        int flashImmunity
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
