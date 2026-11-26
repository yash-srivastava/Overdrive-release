/**
 * Motion Detection Pipeline V2 — Per-Quadrant 6-Stage Filter Pipeline
 * 
 * Processes a 640×480 RGB mosaic (2×2 camera grid) by splitting into
 * 4 independent 320×240 quadrants and running a full filter pipeline
 * on each. Any quadrant detecting motion triggers an event.
 * 
 * Stages:
 *   1. Global brightness check (suppress quadrant on light change)
 *   2. Per-block luma+edge analysis (dual-gate activation)
 *   3. Temporal decay confidence (accumulate/decay per block)
 *   4. Spatial coherence (connected component analysis)
 *   5. Behavioral classification (loitering vs passing vs approaching)
 *   6. Result packing (per-quadrant motion flags + metadata)
 */

#ifndef MOTION_PIPELINE_V2_H
#define MOTION_PIPELINE_V2_H

#include <cstdint>
#include <cstddef>
#include <cstring>
#include <cmath>
#include <algorithm>

// ============================================================================
// Constants
// ============================================================================

#define V2_NUM_QUADRANTS     4
#define V2_QUADRANT_WIDTH    320
#define V2_QUADRANT_HEIGHT   240
#define V2_BLOCK_SIZE        32
#define V2_GRID_COLS         (V2_QUADRANT_WIDTH / V2_BLOCK_SIZE)   // 10
#define V2_GRID_ROWS         ((V2_QUADRANT_HEIGHT + V2_BLOCK_SIZE - 1) / V2_BLOCK_SIZE) // 8, last row 16 px
#define V2_TOTAL_BLOCKS      (V2_GRID_COLS * V2_GRID_ROWS)        // 80
#define V2_POLICY_ROWS       (V2_QUADRANT_HEIGHT / V2_BLOCK_SIZE) // Original 7-row calibration
#define V2_POLICY_BLOCKS     (V2_GRID_COLS * V2_POLICY_ROWS)      // Original flash/mass basis: 70
#define V2_GEOMETRY_ABI      2
#define V2_FRAME_HISTORY     4    // Ring buffer depth (N vs N-3 = 300ms at 10 FPS)
#define V2_COMPARE_OFFSET    3    // Compare current frame with N-3
#define V2_CENTROID_HISTORY  100  // 10 seconds at 10 FPS — MUST cover the full
                                  // loiteringFrames range (10-100, see PipelineConfigV2).
                                  // Previously 30 (3s): with loiter>3s, loiteringFrames
                                  // exceeded this ceiling so stage5's
                                  // `centroidCount < loiteringFrames` gate was permanently
                                  // true, making THREAT_HIGH(loiter) UNREACHABLE and
                                  // pinning every detection at MEDIUM(approach).
#define V2_BYTES_PER_PIXEL   3    // RGB
#define V2_QUADRANT_SIZE     (V2_QUADRANT_WIDTH * V2_QUADRANT_HEIGHT * V2_BYTES_PER_PIXEL)
#define V2_SAMPLED_WIDTH     (V2_QUADRANT_WIDTH / 4)
#define V2_SAMPLED_SIZE      (V2_SAMPLED_WIDTH * (V2_QUADRANT_HEIGHT / 4))
#define V2_OSCILLATION_WINDOW 10  // Track last 10 frames for oscillation detection
#define V2_FLOW_NET_WINDOW    10  // Stage 4b: net-displacement window (~1s at 10 FPS)
#define V2_FLOW_SEARCH_RADIUS 6   // Stage 4b: block-match search half-window (px)

// Threat levels
#define THREAT_NONE          0
#define THREAT_LOW           1    // Passing motion
#define THREAT_MEDIUM        2    // Approaching motion
#define THREAT_HIGH          3    // Loitering motion

// ============================================================================
// Pipeline Configuration (passed from Java via direct ByteBuffer)
// ============================================================================

struct PipelineConfigV2 {
    // Stage 1: Brightness
    float brightnessShiftThreshold;    // 0.08 - 0.25
    int   brightnessSuppressionFrames; // 5-10 frames (500ms-1s at 10 FPS)
    
    // Stage 2: Per-block thresholds
    int   shadowThreshold;             // 20-50 luma units
    float lumaRatioThreshold;          // 1.3 - 2.0
    int   edgeDiffThreshold;           // 10-40
    int   densityThreshold;            // 8-48 changed pixels per block
    
    // Stage 3: Temporal
    float confidenceIncrement;         // 0.3 - 0.5
    float confidenceDecay;             // 0.05 - 0.15
    float confidenceThreshold;         // 0.6 - 0.8
    
    // Stage 4: Spatial
    int   minComponentSize;            // 1-4 blocks
    
    // Stage 5: Behavioral
    float loiteringRadiusBlocks;       // 1.5 - 3.0
    int   loiteringFrames;             // 10-100 (1-10 seconds at 10 FPS)
    
    // Per-quadrant enable
    bool  quadrantEnabled[V2_NUM_QUADRANTS];
    
    // Alarm threshold
    int   alarmBlockThreshold;         // 1-4
    
    // Detection zone: maximum centroid row (0=top/far, GRID_ROWS-1=bottom/close)
    // Blocks with centroid above this row are considered "too far" and rejected.
    // Close=4 (bottom 3 rows), Normal=2 (bottom 5 rows), Extended=0 (all rows)
    int   maxDistanceRow;              // 0 = no limit (extended), 2 = normal, 4 = close
    
    // Shadow discrimination (tree shadow / cloud shadow filtering)
    // Mode: 0=OFF, 1=LIGHT, 2=NORMAL, 3=AGGRESSIVE
    int   shadowFilterMode;            // 0-3
    // Chrominance ratio tolerance: how much color ratio can change and still be "shadow"
    // Shadows preserve R:G:B ratios. Real objects change them.
    // Lower = stricter (more things classified as shadow). Range: 0.05 - 0.25
    float chromaRatioTolerance;        // 0.10 default
    // Minimum fraction of changed pixels that must be non-shadow to activate a block.
    // If >shadowPixelFraction of changed pixels are shadow-like, the block is suppressed.
    // Range: 0.3 - 0.8 (0.5 = at least half must be real motion)
    float shadowPixelFraction;         // 0.5 default
    // Temporal oscillation filter: suppress blocks that flicker on/off rapidly.
    // Tree shadows in wind create a distinctive oscillation pattern.
    // Tracks per-block activation history and suppresses oscillating blocks.
    // Threshold: number of on→off transitions in last 10 frames to classify as oscillation.
    int   oscillationThreshold;        // 3 default (3+ transitions in 10 frames = shadow)

    // ---- Stage 4b: Motion-direction flow coherence (flag/shadow rejection) ----
    // A near-stationary connected-component centroid is classified as loitering
    // (THREAT_HIGH) on geometry alone — which a waving flag / sweeping shadow
    // trivially satisfies. Flow coherence is the discriminator: a real intruder
    // TRANSLATES (per-block flow vectors agree → coherence ratio C → 1), while a
    // flag/foliage/shadow OSCILLATES in place (vectors cancel → C → 0) and never
    // accumulates net displacement. An incoherent loiter is demoted to
    // THREAT_MEDIUM so it must clear the YOLO gate; a coherent one keeps HIGH.
    //
    // coherenceRatioMin: below this |net|/|gross| ratio the component flow is
    //   "incoherent" (oscillating in place). 0.35 default.
    // coherenceNetMin: windowed cumulative net drift (in blocks) above which the
    //   motion is coherent translation regardless of the instantaneous ratio
    //   (a slow but steady real approach). 1.5 default.
    // coherenceMinFrames: flow-history frames required before the signal is
    //   trusted; until then flowCoherence is reported as -1 (fail-open).
    float coherenceRatioMin;           // 0.35 default
    float coherenceNetMin;             // 1.5 default (blocks)
    // Java is the source of truth for every value in this struct (it serializes
    // the config buffer); PipelineConfigV2 in MotionPipelineV2.java sends 8, i.e.
    // ~0.8s of fail-open warmup at 10 FPS before coherence is trusted. This
    // comment previously said 12, which would mislead anyone reasoning about how
    // long the -1 sentinel window lasts.
    int   coherenceMinFrames;          // 8 (see MotionPipelineV2.java)

    // ---- Motion salience: rescue a LARGE real object from the flash filter ----
    // The >25% active-ratio global-flash filter (v2_processQuadrant) treats block
    // MASS as evidence of a lighting event. That inverts on close subjects: a
    // person at ~1.5m covers ~18 of 70 blocks and a van covers far more, so the
    // biggest, closest — most threatening — objects are the ones most likely to
    // be discarded as "flash", before stages 3/4/4b ever run.
    //
    // When salienceEnabled, a high-mass frame is PROBED instead of dropped: run
    // 3/4/4b and keep the frame only if the mass is ONE COMPACT, RIGIDLY
    // TRANSLATING blob (an object) rather than a diffuse scene-wide response (an
    // illumination change). A failed probe restores the pre-probe confidences and
    // takes the original suppression path, so probe-fail is byte-identical to
    // salienceEnabled==0. Coherence thresholds are reused from the fields above.
    //
    // salienceMinBlocks: minimum largest-component size (blocks) to be an object.
    // salienceDominanceFrac: largest component as a fraction of confirmed blocks —
    //   one blob is ~1.0; a flash lighting every textured surface is far lower.
    int   salienceEnabled;             // 0 = off (default), 1 = probe high-mass frames
    int   salienceMinBlocks;           // 10 default (~14% of the 70-block grid)
    float salienceDominanceFrac;       // 0.60 default
};

// ============================================================================
// Per-Quadrant Result (returned to Java)
// ============================================================================

struct QuadrantResultV2 {
    bool  motionDetected;
    int   threatLevel;          // THREAT_NONE / LOW / MEDIUM / HIGH
    int   activeBlocks;         // Raw active block count (after Stage 2)
    int   confirmedBlocks;      // Confirmed blocks (after Stage 3)
    int   componentSize;        // Largest connected component size
    float centroidX;            // Centroid X in block coordinates
    float centroidY;            // Centroid Y in block coordinates
    float meanLuma;             // Current mean luma (for debug/UI)
    bool  brightnessSuppressed; // Was this quadrant suppressed by Stage 1
    bool  shadowFiltered;       // Were blocks suppressed by shadow discrimination

    // Stage 4b flow coherence. Declared BEFORE blockConfidence[] so the Java
    // deserializer (MotionPipelineV2.deserializeResult) reads these two floats
    // immediately before the trailing confidence array — keep this order in sync.
    // -1 = "not computed" (warmup, no component, or coherence stage absent):
    //   Java treats <0 as unavailable and falls back to the person-tracker test.
    float flowCoherence;        // |net flow| / |gross flow| over largest component, EMA-smoothed
    float netDriftBlocks;       // windowed cumulative net displacement magnitude (blocks)

    // Number of distinct connected components among the confirmed blocks. One
    // object is 1 (2-3 when a subject fragments around an occluder or its own
    // shadow); a diffuse response — rain, wipers, a scene-wide illumination
    // change — scatters into many. Java's salience test uses this as a cheap
    // diffuseness guard alongside the dominance fraction. Declared BEFORE
    // blockConfidence[]; keep in sync with MotionPipelineV2.deserializeResult.
    int   componentCount;
    // Flash-filter salience probe outcome, for attribution (invariant I7):
    // 0 = probe did not run, 1 = probed and SUPPRESSED (diffuse/incoherent →
    // treated as a lighting event, exactly as before), 2 = probed and RESCUED
    // (one compact rigidly-translating blob → kept as real motion).
    int   salienceState;

    // Per-block confidence (for heatmap overlay)
    float blockConfidence[V2_TOTAL_BLOCKS];
};

static_assert(sizeof(PipelineConfigV2) == 100, "V2 config ABI changed");
static_assert(offsetof(QuadrantResultV2, blockConfidence) == 52, "V2 result prefix ABI changed");
static_assert(sizeof(QuadrantResultV2) == 52 + V2_TOTAL_BLOCKS * sizeof(float),
              "V2 result size does not match its geometry descriptor");

// ============================================================================
// Per-Quadrant Persistent State
// ============================================================================

struct QuadrantState {
    // Stage 1: Brightness tracking
    float meanLuma;
    float prevMeanLuma;
    float baselineLuma;          // Slow-moving EMA baseline for gradual drift detection
    int   suppressionCountdown;  // Frames remaining to suppress
    bool  brightnessInitialized;
    
    // Stage 2: ROI mask (future: per-quadrant user-drawn polygon)
    bool  blockRoiMask[V2_TOTAL_BLOCKS];
    bool  hasCustomRoi;
    
    // Stage 3: Per-block temporal confidence
    float blockConfidence[V2_TOTAL_BLOCKS];
    
    // Full RGB history remains available for chroma and arbitrary-offset flow.
    uint8_t frameHistory[V2_FRAME_HISTORY][V2_QUADRANT_SIZE];
    // Lazily derived stride-4 luma, tied to the same immutable history slot.
    uint8_t sampledLuma[V2_FRAME_HISTORY][V2_SAMPLED_SIZE];
    bool    sampledLumaReady[V2_FRAME_HISTORY];
    int     historyIndex;
    int     historyCount;
    
    // Stage 5: Centroid tracking
    float centroidHistoryX[V2_CENTROID_HISTORY];
    float centroidHistoryY[V2_CENTROID_HISTORY];
    int   centroidIndex;
    int   centroidCount;
    
    // Shadow oscillation tracking: per-block activation history (circular buffer)
    // Each entry is a bitmask-style bool: was this block active in frame N?
    // Used to detect tree shadow flickering (rapid on/off/on pattern).
    bool  blockActiveHistory[V2_OSCILLATION_WINDOW][V2_TOTAL_BLOCKS];
    int   oscillationHistoryIndex;
    int   oscillationHistoryCount;
    
    // Per-quadrant enable
    bool  enabled;
    
    // Per-quadrant adaptive exposure mode (hysteresis state machine)
    // 0=NORMAL, 1=NIGHT, 2=GLARE — transitions use deadband to prevent jitter
    int   exposureMode;  // Initialized to 0 in v2_initPipeline

    // Stage 4b: motion-direction flow coherence state.
    // EMA-smoothed coherence ratio (init 1.0 so a real translation isn't
    // demoted during the first frames before history accrues — see
    // coherenceMinFrames). netRing holds the per-frame net displacement vector
    // (block units) of the largest component, summed over the window to detect
    // sustained translation (a flag random-walks to ~0; a walker accumulates).
    float lastFlowCoherence;     // init 1.0
    float netRingX[V2_FLOW_NET_WINDOW];
    float netRingY[V2_FLOW_NET_WINDOW];
    int   netRingIndex;
    int   netRingCount;
};

struct PipelineStateV2 {
    QuadrantState quadrants[V2_NUM_QUADRANTS];
    bool  initialized;
    int   frameCount;
};

// ============================================================================
// Pipeline API
// ============================================================================

/**
 * Initialize or reset the pipeline state.
 */
void v2_initPipeline(PipelineStateV2* state);

/**
 * Process one frame through the full 6-stage pipeline.
 * 
 * @param state     Persistent pipeline state (allocated by caller)
 * @param frame     640×480 RGB mosaic (direct buffer)
 * @param width     Frame width (640)
 * @param height    Frame height (480)
 * @param config    Pipeline configuration
 * @param results   Output: 4 QuadrantResultV2 structs (allocated by caller)
 */
void v2_processFrame(
    PipelineStateV2* state,
    const uint8_t* frame,
    int width, int height,
    const PipelineConfigV2* config,
    QuadrantResultV2 results[V2_NUM_QUADRANTS]
);

#endif // MOTION_PIPELINE_V2_H
