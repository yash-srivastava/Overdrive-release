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
    public static final int GRID_COLS = MotionGridGeometry.GRID_COLS;
    public static final int GRID_ROWS = MotionGridGeometry.GRID_ROWS;
    public static final int TOTAL_BLOCKS = MotionGridGeometry.TOTAL_BLOCKS;
    public static final int POLICY_ROWS = MotionGridGeometry.POLICY_ROWS;
    public static final int POLICY_BLOCKS = MotionGridGeometry.POLICY_BLOCKS;
    /**
     * Block edge length in quadrant pixels. MUST match {@code V2_BLOCK_SIZE} in
     * motion_pipeline_v2.h — the native side owns the grid geometry; this is the
     * Java mirror. Consumers that map a pixel-space bbox onto the block grid
     * (the engine's motion-overlap detection filter, ThumbnailBuffer's hero
     * staticness test) previously hard-coded 32 in three places, so the claim
     * that they "agree by construction" was not actually enforced by construction.
     *
     * <p>The eighth row is clipped to 16 px. Legacy native libraries still
     * cover only 224 px; result decoding records their actual confidence count.
     * Consumers retain the conservative bottom-band detection policy.
     */
    public static final int BLOCK_SIZE = MotionGridGeometry.BLOCK_SIZE;
    /**
     * Per-block confidence at or above which a block counts as motion for
     * bbox-overlap tests. Shared so the detection-side filter and the hero-side
     * staticness test cannot drift apart.
     *
     * <p>Deliberately BELOW the {@code confidenceThreshold} (0.6–0.8) that gates
     * {@code confirmedBlocks}: a block in the 0.5–0.7 band counts as motion for
     * overlap purposes but not as "confirmed". That asymmetry is intentional and
     * safe — it makes the overlap test more motion-biased, i.e. more likely to
     * judge an actor LIVE.
     */
    public static final float BLOCK_MOTION_MIN_CONF = 0.5f;
    
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
    private MotionResultLayout resultLayout;
    
    // Expected sizes with shadow filter fields (for backward compatibility)
    // Old config: up to maxDistanceRow = 56 bytes (varies by alignment)
    // New config: + shadowFilterMode(4) + chromaRatioTolerance(4) + shadowPixelFraction(4) + oscillationThreshold(4) = +16 bytes
    // Old result: brightnessSuppressed(1) + 3 padding = ends at same offset
    // New result: brightnessSuppressed(1) + shadowFiltered(1) + 2 padding
    private boolean nativeHasShadowFilter = false;

    // True when the loaded native library includes the flow-coherence stage
    // (Phase 2). Detected from the config struct size growing by the 3 new
    // coherence params (12 bytes) past the shadow-filter layout. When false,
    // processFrame leaves QuadrantResult.flowCoherence/netDriftBlocks at -1
    // and the Java trust gate falls back to tracker-only — i.e. a partial or
    // older .so degrades to the pre-coherence behaviour, never crashes on a
    // size/offset mismatch.
    private boolean nativeHasCoherence = false;

    // True when the loaded native library has the salience probe (the flash-filter
    // rescue for large close objects). Detected from the config struct growing by
    // the 3 salience params (12 bytes) past the coherence layout. When false,
    // processFrame leaves componentCount/salienceState at 0 and the Java salience
    // trigger channel is inert — an older .so degrades to the pre-salience
    // behaviour rather than mis-parsing the result struct.
    private boolean nativeHasSalience = false;

    // CONFIG BUFFER — IMMUTABLE-SWAP (audit R13-1b / ExtE-7). applyConfig
    // used to rewrite this direct buffer IN PLACE on the control thread
    // while the engine thread's native processFrameV2 memcpys it — a torn
    // config (half old, half new floats/ints) for one frame. Now applyConfig
    // builds a FRESH direct buffer and publishes it with a single volatile
    // write; processFrame reads the reference once per frame. A published
    // buffer is never written again, so the native memcpy always sees a
    // complete, coherent config. Allocation is ~76 bytes per config change
    // (rare user action) — no hot-path GC impact. resultBuffer stays
    // pre-allocated: it is only ever touched on the engine thread.
    private volatile ByteBuffer configBuffer;
    private int configStructSizeBytes;
    private ByteBuffer resultBuffer;
    
    // Parsed results (reused across frames)
    private final QuadrantResult[] results = new QuadrantResult[NUM_QUADRANTS];
    
    private boolean initialized = false;
    
    /**
     * Per-quadrant detection result.
     */
    public static class QuadrantResult extends MotionResultLayout.Result {}
    
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

        // ---- Motion directionality / flow-coherence (native Phase 2) ----
        // A near-stationary connected-component centroid is classified as
        // loitering (THREAT_HIGH) on geometry alone, which a waving flag /
        // sweeping shadow trivially satisfies. These gate the native demotion
        // of an INCOHERENT stationary loiter (flag/foliage/shadow) down to
        // THREAT_MEDIUM so it must clear the YOLO confirmation gate, while a
        // COHERENTLY translating approach keeps the fast THREAT_HIGH path.
        // Only consumed by a coherence-capable native build
        // ({@link #isNativeCoherenceSupported()}); ignored by older .so.
        //
        // coherenceRatioMin: below this |net|/|gross| ratio the component's
        //   flow is "incoherent" (oscillating in place). 0.35 default.
        // coherenceNetMin: windowed cumulative net drift (in blocks) above
        //   which the motion counts as coherent translation regardless of the
        //   instantaneous ratio (slow but steady real approach). 1.5 default.
        // coherenceMinFrames: flow history required before the signal is
        //   trusted; until then flowCoherence is reported as -1 (fail-open).
        //   MUST be <= V2_FLOW_NET_WINDOW (10, motion_pipeline_v2.h): the native
        //   publish gate is `netRingCount >= coherenceMinFrames` and netRingCount
        //   is capped at V2_FLOW_NET_WINDOW, so a value of 12 was UNSATISFIABLE —
        //   flowCoherence stayed pinned at the -1 sentinel forever, which made the
        //   Stage-5b incoherent-loiter demotion, cachedIncoherentLoiter, and
        //   highThreatIsTrusted path (b) all permanently inert. 8 gives ~0.8s of
        //   flow history (at 10 FPS) before the signal is trusted while staying
        //   within the window, so coherence actually publishes on-device.
        public float coherenceRatioMin = 0.35f;
        public float coherenceNetMin = 1.5f;
        public int coherenceMinFrames = 8;

        // ---- Motion salience: rescue a LARGE close object from the flash filter ----
        // The native >25%-of-blocks "global flash" filter uses motion MASS as
        // evidence of a lighting event, but mass also grows with object size and
        // closeness — a person at ~1.5m is ~18/70 blocks. When enabled, such a
        // frame is probed (is it ONE compact, rigidly-translating blob?) instead of
        // discarded outright. A failed probe restores state and suppresses exactly
        // as before, so with salienceEnabled=false this is byte-identical.
        // Only consumed by a salience-capable native build
        // ({@link #isNativeSalienceSupported()}); ignored by older .so.
        public boolean salienceEnabled = false;
        public int salienceMinBlocks = 10;          // ~14% of the 70-block grid
        public float salienceDominanceFrac = 0.60f;

        /**
         * Java-side gate values for a sensitivity level. Used by per-quadrant
         * post-filtering: the native pipeline runs once with the most-permissive
         * config, and stricter quadrants demote results that don't clear these
         * values. Keep in sync with {@link #applySensitivity(int)}.
         */
        public static class GateThresholds {
            public final int alarmBlockThreshold;
            public final int minComponentSize;
            public final float confidenceThreshold;
            GateThresholds(int alarm, int comp, float conf) {
                this.alarmBlockThreshold = alarm;
                this.minComponentSize = comp;
                this.confidenceThreshold = conf;
            }
        }

        public static GateThresholds gatesForSensitivity(int level) {
            switch (level) {
                case 1: return new GateThresholds(3, 2, 0.8f);
                case 2: return new GateThresholds(2, 2, 0.75f);
                case 3: return new GateThresholds(2, 1, 0.7f);
                case 4: return new GateThresholds(1, 1, 0.65f);
                case 5: return new GateThresholds(1, 1, 0.6f);
                default: return new GateThresholds(2, 1, 0.7f);
            }
        }

        /**
         * Detection-zone row cutoff. Mirrors {@link #applyDetectionZone(String)}
         * for use in per-quadrant post-filtering. Returns the maxDistanceRow
         * (centroids strictly above this row are rejected).
         */
        public static int maxDistanceRowForZone(String zone) {
            if (zone == null) return 2;
            switch (zone) {
                case "close":    return 4;
                case "normal":   return 2;
                case "extended": return 0;
                default:         return 2;
            }
        }

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
            
            // Geometry is explicit in current libraries. Missing geometry is a
            // known legacy 70-cell layout; unknown sizes never reach deserialization.
            resultLayout = MotionResultLayout.resolve(configStructSize, resultStructSize,
                    readNativeGeometry());
            nativeHasShadowFilter = resultLayout.hasShadow;
            nativeHasCoherence = resultLayout.hasCoherence;
            nativeHasSalience = resultLayout.hasSalience;
            for (QuadrantResult result : results) {
                result.nativeBlockCount = resultLayout.blockCount;
                result.coveredHeight = resultLayout.coveredHeight;
            }
            logger.info(String.format("V2 struct sizes: config=%d, result=%d, cells=%d, coverage=%d px",
                    configStructSize, resultStructSize, resultLayout.blockCount,
                    resultLayout.coveredHeight));

            // Allocate direct ByteBuffers. The config buffer here is only
            // the pre-applyConfig default; applyConfig publishes fresh
            // immutable buffers (audit R13-1b).
            configStructSizeBytes = configStructSize;
            ByteBuffer initialConfig = ByteBuffer.allocateDirect(configStructSize);
            initialConfig.order(ByteOrder.nativeOrder());
            configBuffer = initialConfig;
            
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

        // IMMUTABLE-SWAP (audit R13-1b / ExtE-7): serialize into a FRESH
        // direct buffer and publish it with one volatile write at the end.
        // Never mutate the currently-published buffer — the engine thread's
        // native memcpy may be reading it right now. allocateDirect zero-fills
        // (JLS-guaranteed for direct buffers), so any bytes we don't
        // explicitly write are 0 rather than stale — preserving the old
        // zero-fill loop's forward-compat guarantee for free.
        ByteBuffer buf = ByteBuffer.allocateDirect(configStructSizeBytes);
        buf.order(ByteOrder.nativeOrder());

        // Stage 1
        buf.putFloat(config.brightnessShiftThreshold);
        buf.putInt(config.brightnessSuppressionFrames);
        
        // Stage 2
        buf.putInt(config.shadowThreshold);
        buf.putFloat(config.lumaRatioThreshold);
        buf.putInt(config.edgeDiffThreshold);
        buf.putInt(config.densityThreshold);
        
        // Stage 3
        buf.putFloat(config.confidenceIncrement);
        buf.putFloat(config.confidenceDecay);
        buf.putFloat(config.confidenceThreshold);
        
        // Stage 4
        buf.putInt(config.minComponentSize);
        
        // Stage 5
        buf.putFloat(config.loiteringRadiusBlocks);
        buf.putInt(config.loiteringFrames);
        
        // Per-quadrant enable (4 bools → 4 bytes with padding)
        for (int i = 0; i < NUM_QUADRANTS; i++) {
            buf.put((byte)(config.quadrantEnabled[i] ? 1 : 0));
        }
        
        // Alarm threshold
        buf.putInt(config.alarmBlockThreshold);
        
        // Detection zone: max centroid row for distance filtering
        buf.putInt(config.maxDistanceRow);
        
        // Shadow discrimination parameters (only if native supports them)
        if (nativeHasShadowFilter) {
            buf.putInt(config.shadowFilterMode);
            buf.putFloat(config.chromaRatioTolerance);
            buf.putFloat(config.shadowPixelFraction);
            buf.putInt(config.oscillationThreshold);
        }

        // Flow-coherence parameters (only if native supports them — Phase 2).
        // Must come AFTER the shadow fields to match the C struct layout.
        if (nativeHasCoherence) {
            buf.putFloat(config.coherenceRatioMin);
            buf.putFloat(config.coherenceNetMin);
            buf.putInt(config.coherenceMinFrames);
        }

        // Salience-probe parameters. Must come AFTER the coherence fields to match
        // the C struct layout. salienceEnabled is an int in C (not bool) so the
        // 4-byte write is exact with no padding ambiguity.
        if (nativeHasSalience) {
            buf.putInt(config.salienceEnabled ? 1 : 0);
            buf.putInt(config.salienceMinBlocks);
            buf.putFloat(config.salienceDominanceFrac);
        }

        buf.flip();
        configBuffer = buf; // single volatile publish (audit R13-1b)
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
        
        // One volatile read pins this frame's config buffer; a concurrent
        // applyConfig publishes a NEW buffer and never touches this one
        // (audit R13-1b / ExtE-7).
        final ByteBuffer cfg = configBuffer;
        NativeMotion.processFrameV2(frameBuffer, width, height, cfg, resultBuffer);
        
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
        resultLayout.readQuadrant(buf, result);
    }

    private static int[] readNativeGeometry() {
        try {
            return NativeMotion.getPipelineGeometryV2();
        } catch (UnsatisfiedLinkError legacyLibrary) {
            return null;
        }
    }

    /** Converts persisted grid masks for the actual validated native library. */
    public static byte[] toNativeRoiMask(byte[] mask) {
        if (mask == null) return null;
        MotionResultLayout layout = MotionResultLayout.resolve(
                NativeMotion.getPipelineConfigSize(), NativeMotion.getQuadrantResultSize(),
                readNativeGeometry());
        return MotionGridGeometry.nativeRoiMask(mask, layout.blockCount);
    }

    /** Normalizes current or legacy persisted block arrays without changing ROI enable state. */
    public static byte[] roiMaskFromJson(org.json.JSONArray array) {
        if (array == null || (array.length() != TOTAL_BLOCKS && array.length() != POLICY_BLOCKS)) {
            return null;
        }
        byte[] mask = new byte[array.length()];
        for (int i = 0; i < mask.length; i++) {
            mask[i] = (byte) (array.optInt(i, 1) != 0 ? 1 : 0);
        }
        return MotionGridGeometry.migrateRoiMask(mask);
    }

    public static org.json.JSONArray roiMaskToJson(byte[] mask) {
        org.json.JSONArray array = new org.json.JSONArray();
        for (byte enabled : mask) array.put(enabled != 0 ? 1 : 0);
        return array;
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
     * Check if the loaded native library computes flow-coherence (Phase 2).
     * When false, {@link QuadrantResult#flowCoherence} stays at -1 and callers
     * must fall back to other trust signals (e.g. the person tracker).
     */
    public boolean isNativeCoherenceSupported() {
        return nativeHasCoherence;
    }

    /**
     * Check if the loaded native library has the flash-filter salience probe.
     * When false, {@link QuadrantResult#componentCount} and
     * {@link QuadrantResult#salienceState} stay 0 and the Java salience trigger
     * channel must treat the signal as unavailable (no trigger, no suppression).
     */
    public boolean isNativeSalienceSupported() {
        return nativeHasSalience;
    }
    
    public QuadrantResult[] getResults() {
        return results;
    }
}
