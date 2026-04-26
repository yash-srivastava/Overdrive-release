package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Java wrapper for the native V2 per-quadrant motion detection pipeline.
 * 
 * Handles config serialization, result deserialization, and provides
 * a clean API for SurveillanceEngineGpu to call.
 */
public class MotionPipelineV2 {
    
    private static final DaemonLogger logger = DaemonLogger.getInstance("MotionV2");
    
    public static final int NUM_QUADRANTS = 4;
    public static final int GRID_COLS = 10;
    public static final int GRID_ROWS = 7;
    public static final int TOTAL_BLOCKS = GRID_COLS * GRID_ROWS;  // 70
    
    // Quadrant names for logging.
    // Grid layout (from GpuDownscaler shader): TL=Front, TR=Right, BL=Rear, BR=Left
    // Quadrant indices: Q0=TL, Q1=TR, Q2=BL, Q3=BR
    // FIX: Q2 and Q3 were swapped — Q2 is BL (Rear), Q3 is BR (Left).
    public static final String[] QUADRANT_NAMES = {"front", "right", "rear", "left"};
    
    // Threat levels (must match native THREAT_* constants)
    public static final int THREAT_NONE = 0;
    public static final int THREAT_LOW = 1;      // Passing
    public static final int THREAT_MEDIUM = 2;   // Approaching
    public static final int THREAT_HIGH = 3;     // Loitering
    
    // Native struct sizes (queried at init)
    private int configStructSize;
    private int resultStructSize;
    
    // Expected sizes with shadow filter fields (for backward compatibility)
    // Old config: up to maxDistanceRow = 56 bytes (varies by alignment)
    // New config: + shadowFilterMode(4) + chromaRatioTolerance(4) + shadowPixelFraction(4) + oscillationThreshold(4) = +16 bytes
    // Old result: brightnessSuppressed(1) + 3 padding = ends at same offset
    // New result: brightnessSuppressed(1) + shadowFiltered(1) + 2 padding
    private boolean nativeHasShadowFilter = false;
    
    // Pre-allocated direct ByteBuffers for JNI (zero GC)
    private ByteBuffer configBuffer;
    private ByteBuffer resultBuffer;
    
    // Parsed results (reused across frames)
    private final QuadrantResult[] results = new QuadrantResult[NUM_QUADRANTS];
    
    private boolean initialized = false;
    
    /**
     * Per-quadrant detection result.
     */
    public static class QuadrantResult {
        public boolean motionDetected;
        public int threatLevel;
        public int activeBlocks;
        public int confirmedBlocks;
        public int componentSize;
        public float centroidX;
        public float centroidY;
        public float meanLuma;
        public boolean brightnessSuppressed;
        public boolean shadowFiltered;
        public float[] blockConfidence = new float[TOTAL_BLOCKS];
    }
    
    /**
     * Pipeline configuration. Set fields and call {@link #applyConfig(Config)}.
     */
    public static class Config {
        // Stage 1: Brightness
        public float brightnessShiftThreshold = 0.15f;
        public int brightnessSuppressionFrames = 5;
        
        // Stage 2: Block thresholds
        public int shadowThreshold = 30;
        public float lumaRatioThreshold = 1.3f;
        public int edgeDiffThreshold = 15;
        public int densityThreshold = 12;
        
        // Stage 3: Temporal
        // With increment=0.3 and threshold=0.7, a block needs 3 consecutive active frames
        // (0.0 → 0.3 → 0.6 → 0.9 ≥ 0.7) to be confirmed. This filters out brief noise
        // events that only last 1-2 frames (100-200ms).
        public float confidenceIncrement = 0.3f;
        public float confidenceDecay = 0.1f;
        public float confidenceThreshold = 0.7f;
        
        // Stage 4: Spatial
        public int minComponentSize = 1;
        
        // Stage 5: Behavioral
        public float loiteringRadiusBlocks = 2.5f;
        public int loiteringFrames = 30;  // 3 seconds at 10 FPS
        
        // Per-quadrant enable
        public boolean[] quadrantEnabled = {true, true, true, true};
        
        // Alarm threshold
        public int alarmBlockThreshold = 2;
        
        // Detection zone: max centroid row for distance filtering
        // 0 = no limit (extended), 2 = normal (~3m), 4 = close (~1.5m)
        public int maxDistanceRow = 2;  // Default: normal zone
        
        // Shadow discrimination (tree shadow / cloud shadow filtering)
        // Mode: 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
        public int shadowFilterMode = 2;  // Default: NORMAL
        // Chrominance ratio tolerance for shadow detection
        public float chromaRatioTolerance = 0.10f;
        // Fraction of changed pixels that must be shadow to suppress a block.
        // Raised from 0.5 to 0.6 to reduce risk of the "self-erasing person" bug
        // where a person's own shadow causes their block to be suppressed.
        public float shadowPixelFraction = 0.6f;
        // Oscillation transitions threshold (in 10-frame window)
        public int oscillationThreshold = 3;
        
        /**
         * Apply a sensitivity preset (1-5).
         */
        public void applySensitivity(int level) {
            switch (level) {
                case 1: // Low
                    densityThreshold = 32;
                    alarmBlockThreshold = 3;
                    minComponentSize = 2;
                    confidenceIncrement = 0.25f;
                    confidenceThreshold = 0.8f;
                    break;
                case 2:
                    densityThreshold = 20;
                    alarmBlockThreshold = 2;
                    minComponentSize = 2;
                    confidenceIncrement = 0.28f;
                    confidenceThreshold = 0.75f;
                    break;
                case 3: // Default
                    densityThreshold = 12;
                    alarmBlockThreshold = 2;
                    minComponentSize = 1;
                    confidenceIncrement = 0.3f;
                    confidenceThreshold = 0.7f;
                    break;
                case 4:
                    densityThreshold = 8;
                    alarmBlockThreshold = 1;
                    minComponentSize = 1;
                    confidenceIncrement = 0.35f;
                    confidenceThreshold = 0.65f;
                    break;
                case 5: // Max
                    densityThreshold = 4;
                    alarmBlockThreshold = 1;
                    minComponentSize = 1;
                    confidenceIncrement = 0.4f;
                    confidenceThreshold = 0.6f;
                    break;
            }
        }
        
        /**
         * Apply a detection zone preset.
         */
        /**
         * Apply a detection zone preset.
         * Controls how far from the car motion is considered relevant:
         * - close: Only triggers within ~1.5m. Requires larger connected components.
         *          Rejects motion in the top 4 rows (far from car).
         * - normal: Standard ~3m detection range. Rejects top 2 rows.
         * - extended: Full quadrant, any motion triggers. No distance filtering.
         *
         * The grid is 7 rows tall (0=top/far, 6=bottom/close).
         * maxDistanceRow is the cutoff: centroids above this row are rejected.
         */
        public void applyDetectionZone(String zone) {
            switch (zone) {
                case "close":
                    loiteringRadiusBlocks = 1.5f;
                    minComponentSize = 2;  // Require larger clusters for close-range
                    maxDistanceRow = 4;    // Only bottom 3 rows (~43% of quadrant)
                    break;
                case "normal":
                    loiteringRadiusBlocks = 3.0f;
                    minComponentSize = 1;
                    maxDistanceRow = 2;    // Bottom 5 rows (~71% of quadrant)
                    break;
                case "extended":
                    loiteringRadiusBlocks = 5.0f;
                    minComponentSize = 1;
                    maxDistanceRow = 0;    // All rows (no distance filtering)
                    break;
            }
        }
        
        /**
         * Apply an environment preset (sets multiple parameters).
         */
        public void applyEnvironmentPreset(String preset) {
            switch (preset) {
                case "outdoor":
                    applySensitivity(3);
                    applyDetectionZone("normal");
                    loiteringFrames = 30;  // 3 seconds
                    brightnessShiftThreshold = 0.15f;
                    edgeDiffThreshold = 20;
                    // FIX: Fast-motion ghosting — at 10 FPS a person crosses a 32px block
                    // in ~150-200ms (1-2 frames). With increment=0.3 and threshold=0.7,
                    // confirmation requires 3 frames (300ms) — they outrun the filter.
                    // Bump increment to 0.4 so confirmation triggers in 2 frames (200ms).
                    confidenceIncrement = 0.4f;
                    confidenceThreshold = 0.7f;
                    // Outdoor: tree shadows are the main problem — enable normal filtering.
                    // shadowPixelFraction raised to 0.6 to prevent the "self-erasing person"
                    // bug where a person's own shadow causes the block to be suppressed.
                    shadowFilterMode = 2;  // NORMAL
                    chromaRatioTolerance = 0.10f;
                    shadowPixelFraction = 0.6f;
                    oscillationThreshold = 3;
                    break;
                case "garage":
                    applySensitivity(4);
                    applyDetectionZone("close");
                    loiteringFrames = 20;  // 2 seconds
                    brightnessShiftThreshold = 0.08f;
                    edgeDiffThreshold = 15;
                    // Garage: no tree shadows, but fluorescent flicker possible — light filtering
                    shadowFilterMode = 1;  // LIGHT
                    chromaRatioTolerance = 0.15f;
                    shadowPixelFraction = 0.7f;
                    oscillationThreshold = 4;
                    break;
                case "street":
                    applySensitivity(3);
                    applyDetectionZone("normal");
                    loiteringFrames = 50;  // 5 seconds
                    brightnessShiftThreshold = 0.15f;
                    edgeDiffThreshold = 20;
                    // FIX: Fast-motion ghosting — street has even faster-moving objects
                    // (joggers, cyclists). Same 2-frame confirmation as outdoor.
                    confidenceIncrement = 0.4f;
                    confidenceThreshold = 0.7f;
                    // Street: tree shadows + passing car shadows.
                    // CHANGED from AGGRESSIVE(3) to NORMAL(2). Aggressive mode with
                    // shadowPixelFraction=0.3 was the worst case for the "self-erasing
                    // person" bug — a person's shadow only needs to be 30% of changed
                    // pixels to erase the entire block. The C++ fix now protects blocks
                    // with edge evidence, but NORMAL mode is safer for street scenes
                    // where people and their shadows coexist in the same blocks.
                    shadowFilterMode = 2;  // NORMAL (was AGGRESSIVE)
                    chromaRatioTolerance = 0.10f;
                    shadowPixelFraction = 0.6f;
                    oscillationThreshold = 3;
                    break;
            }
        }
        
        /**
         * Apply night mode preset.
         * Tuned for the BYD's camera ISP which boosts ISO heavily at night,
         * pushing mean luma to ~75-85 even in darkness. The heavy ISO boost
         * creates significant sensor noise that looks like motion to the
         * standard thresholds.
         */
        public void applyNightMode() {
            // Lower absolute thresholds for crushed, grainy blacks
            shadowThreshold = 14;
            lumaRatioThreshold = 1.15f;
            
            // ISO noise creates fake edges. Lower the edge threshold so real
            // objects pass, but keep densityThreshold normal to filter the static.
            edgeDiffThreshold = 8;
            densityThreshold = 12;  // Do NOT lower — ISO grain triggers false motion
            
            // Temporal: require 2 frames confirmation (same as outdoor fast-motion fix)
            confidenceIncrement = 0.4f;
            confidenceThreshold = 0.7f;
            
            // Headlight suppression: relax brightness shift threshold so passing
            // headlights trigger the brightness suppression stage instead of
            // being misclassified as motion.
            brightnessShiftThreshold = 0.35f;
            brightnessSuppressionFrames = 8;  // Longer suppression for headlight sweeps
            
            // Relax shadow filtering — color noise is too high at night for
            // chrominance-based shadow detection to work reliably.
            shadowFilterMode = 1;  // LIGHT
            chromaRatioTolerance = 0.25f;
            shadowPixelFraction = 0.7f;
            oscillationThreshold = 4;
            
            // Keep spatial and behavioral settings from current preset
            // (don't override minComponentSize, loiteringFrames, etc.)
        }
        
        /**
         * Apply glare mode preset.
         * When direct sunlight hits the lens, the BYD's ISP crushes exposure to
         * prevent blowout, compressing ground contrast from ~40 luma points to ~12.
         * The standard shadowThreshold of 30 makes people invisible.
         *
         * Uses relative multipliers on the user's current settings so their
         * sensitivity preference is respected (high sensitivity → aggressive glare
         * mode, low sensitivity → conservative glare mode).
         */
        public void applyGlareMode() {
            // Scale thresholds down to catch faint, contrast-crushed motion
            shadowThreshold = Math.max(10, (int)(shadowThreshold * 0.5f));
            lumaRatioThreshold = Math.max(1.05f, lumaRatioThreshold * 0.85f);
            edgeDiffThreshold = Math.max(6, (int)(edgeDiffThreshold * 0.6f));
            
            // Keep density threshold high — lens flares create massive sharp edges
            // that would trigger false alarms if density is too low
            densityThreshold = Math.max(14, densityThreshold);
            
            // Brightness suppression: relax slightly — the ISP is already fighting
            // the sun, so frame-to-frame brightness shifts are larger than normal
            brightnessShiftThreshold = Math.max(brightnessShiftThreshold, 0.20f);
            
            // Shadow filter: keep NORMAL — shadows are still real in direct sun,
            // they're just lower contrast. The lowered shadowThreshold handles this.
        }
    }
    
    public MotionPipelineV2() {
        for (int i = 0; i < NUM_QUADRANTS; i++) {
            results[i] = new QuadrantResult();
        }
    }
    
    /**
     * Initialize the pipeline. Must be called after native library is loaded.
     */
    public boolean init() {
        try {
            // Query native struct sizes
            configStructSize = NativeMotion.getPipelineConfigSize();
            resultStructSize = NativeMotion.getQuadrantResultSize();
            
            // Detect if native library includes shadow filter fields.
            // 
            // Exact struct layout of PipelineConfigV2 (all fields are 4-byte aligned):
            //   float brightnessShiftThreshold     4 bytes  offset 0
            //   int   brightnessSuppressionFrames   4 bytes  offset 4
            //   int   shadowThreshold               4 bytes  offset 8
            //   float lumaRatioThreshold            4 bytes  offset 12
            //   int   edgeDiffThreshold             4 bytes  offset 16
            //   int   densityThreshold              4 bytes  offset 20
            //   float confidenceIncrement           4 bytes  offset 24
            //   float confidenceDecay               4 bytes  offset 28
            //   float confidenceThreshold           4 bytes  offset 32
            //   int   minComponentSize              4 bytes  offset 36
            //   float loiteringRadiusBlocks         4 bytes  offset 40
            //   int   loiteringFrames               4 bytes  offset 44
            //   bool  quadrantEnabled[4]            4 bytes  offset 48
            //   int   alarmBlockThreshold           4 bytes  offset 52
            //   int   maxDistanceRow                4 bytes  offset 56
            //   --- base total: 60 bytes ---
            //   int   shadowFilterMode              4 bytes  offset 60
            //   float chromaRatioTolerance          4 bytes  offset 64
            //   float shadowPixelFraction           4 bytes  offset 68
            //   int   oscillationThreshold          4 bytes  offset 72
            //   --- full total: 76 bytes ---
            //
            // The base struct (without shadow fields) is exactly 60 bytes.
            // With shadow fields it's exactly 76 bytes (60 + 16).
            // Use exact size check: if configStructSize == 76, shadow is supported.
            // Also accept >= 76 in case future fields are added after shadow fields.
            final int BASE_CONFIG_SIZE = 60;
            final int SHADOW_FIELDS_SIZE = 16;  // 4 fields × 4 bytes each
            nativeHasShadowFilter = (configStructSize >= BASE_CONFIG_SIZE + SHADOW_FIELDS_SIZE);
            
            logger.info(String.format("V2 struct sizes: config=%d, result=%d (total result=%d), shadowFilter=%s",
                    configStructSize, resultStructSize, resultStructSize * NUM_QUADRANTS,
                    nativeHasShadowFilter ? "supported" : "NOT supported (old native)"));
            
            // Allocate direct ByteBuffers
            configBuffer = ByteBuffer.allocateDirect(configStructSize);
            configBuffer.order(ByteOrder.nativeOrder());
            
            resultBuffer = ByteBuffer.allocateDirect(resultStructSize * NUM_QUADRANTS);
            resultBuffer.order(ByteOrder.nativeOrder());
            
            // Initialize native pipeline
            NativeMotion.initPipelineV2();
            
            initialized = true;
            logger.info("Motion Pipeline V2 initialized");
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to initialize V2 pipeline: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Serialize config to the native ByteBuffer.
     */
    public void applyConfig(Config config) {
        if (!initialized) return;
        
        configBuffer.clear();
        
        // Stage 1
        configBuffer.putFloat(config.brightnessShiftThreshold);
        configBuffer.putInt(config.brightnessSuppressionFrames);
        
        // Stage 2
        configBuffer.putInt(config.shadowThreshold);
        configBuffer.putFloat(config.lumaRatioThreshold);
        configBuffer.putInt(config.edgeDiffThreshold);
        configBuffer.putInt(config.densityThreshold);
        
        // Stage 3
        configBuffer.putFloat(config.confidenceIncrement);
        configBuffer.putFloat(config.confidenceDecay);
        configBuffer.putFloat(config.confidenceThreshold);
        
        // Stage 4
        configBuffer.putInt(config.minComponentSize);
        
        // Stage 5
        configBuffer.putFloat(config.loiteringRadiusBlocks);
        configBuffer.putInt(config.loiteringFrames);
        
        // Per-quadrant enable (4 bools → 4 bytes with padding)
        for (int i = 0; i < NUM_QUADRANTS; i++) {
            configBuffer.put((byte)(config.quadrantEnabled[i] ? 1 : 0));
        }
        
        // Alarm threshold
        configBuffer.putInt(config.alarmBlockThreshold);
        
        // Detection zone: max centroid row for distance filtering
        configBuffer.putInt(config.maxDistanceRow);
        
        // Shadow discrimination parameters (only if native supports them)
        if (nativeHasShadowFilter) {
            configBuffer.putInt(config.shadowFilterMode);
            configBuffer.putFloat(config.chromaRatioTolerance);
            configBuffer.putFloat(config.shadowPixelFraction);
            configBuffer.putInt(config.oscillationThreshold);
        }
        
        configBuffer.flip();
    }
    
    /**
     * Process a frame through the V2 pipeline.
     * 
     * @param frameBuffer 640×480 RGB direct ByteBuffer
     * @param width Frame width
     * @param height Frame height
     * @return Array of 4 QuadrantResult (reused, do not hold references across calls)
     */
    public QuadrantResult[] processFrame(ByteBuffer frameBuffer, int width, int height) {
        if (!initialized) return results;
        
        resultBuffer.clear();
        
        NativeMotion.processFrameV2(frameBuffer, width, height, configBuffer, resultBuffer);
        
        // Deserialize results
        resultBuffer.rewind();
        for (int q = 0; q < NUM_QUADRANTS; q++) {
            deserializeResult(resultBuffer, results[q]);
        }
        
        return results;
    }
    
    /**
     * Deserialize one QuadrantResultV2 from the ByteBuffer.
     */
    private void deserializeResult(ByteBuffer buf, QuadrantResult result) {
        result.motionDetected = buf.get() != 0;
        
        // Alignment padding (3 bytes after bool to align int)
        buf.get(); buf.get(); buf.get();
        
        result.threatLevel = buf.getInt();
        result.activeBlocks = buf.getInt();
        result.confirmedBlocks = buf.getInt();
        result.componentSize = buf.getInt();
        result.centroidX = buf.getFloat();
        result.centroidY = buf.getFloat();
        result.meanLuma = buf.getFloat();
        result.brightnessSuppressed = buf.get() != 0;
        
        if (nativeHasShadowFilter) {
            // New layout: 2 bools + 2 padding bytes
            result.shadowFiltered = buf.get() != 0;
            buf.get(); buf.get();  // 2 bytes padding to align float array
        } else {
            // Old layout: 1 bool + 3 padding bytes
            result.shadowFiltered = false;
            buf.get(); buf.get(); buf.get();  // 3 bytes padding
        }
        
        // Block confidence array (70 floats)
        for (int i = 0; i < TOTAL_BLOCKS; i++) {
            result.blockConfidence[i] = buf.getFloat();
        }
    }
    
    /**
     * Check if any quadrant detected motion above the given threat threshold.
     */
    public boolean hasMotion(int minThreatLevel) {
        for (int q = 0; q < NUM_QUADRANTS; q++) {
            if (results[q].motionDetected && results[q].threatLevel >= minThreatLevel) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get the highest threat level across all quadrants.
     */
    public int getMaxThreatLevel() {
        int max = THREAT_NONE;
        for (int q = 0; q < NUM_QUADRANTS; q++) {
            if (results[q].threatLevel > max) {
                max = results[q].threatLevel;
            }
        }
        return max;
    }
    
    /**
     * Get bitmask of quadrants with motion (bit 0 = Q0, bit 1 = Q1, etc.)
     */
    public int getActiveQuadrantMask() {
        int mask = 0;
        for (int q = 0; q < NUM_QUADRANTS; q++) {
            if (results[q].motionDetected) {
                mask |= (1 << q);
            }
        }
        return mask;
    }
    
    /**
     * Get the quadrant with the highest threat level (for YOLO priority).
     * Returns -1 if no motion detected.
     */
    public int getHighestThreatQuadrant() {
        int bestQ = -1;
        int bestThreat = THREAT_NONE;
        for (int q = 0; q < NUM_QUADRANTS; q++) {
            if (results[q].threatLevel > bestThreat) {
                bestThreat = results[q].threatLevel;
                bestQ = q;
            }
        }
        return bestQ;
    }
    
    public boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Check if the loaded native library supports shadow filter fields.
     * Returns false if running against an older .so that doesn't have the shadow filter struct fields.
     */
    public boolean isNativeShadowFilterSupported() {
        return nativeHasShadowFilter;
    }
    
    public QuadrantResult[] getResults() {
        return results;
    }
}
