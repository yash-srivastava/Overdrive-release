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
#define V2_GRID_ROWS         (V2_QUADRANT_HEIGHT / V2_BLOCK_SIZE)  // 7 (240/32=7.5, truncated)
#define V2_TOTAL_BLOCKS      (V2_GRID_COLS * V2_GRID_ROWS)        // 70
#define V2_FRAME_HISTORY     4    // Ring buffer depth (N vs N-3 = 300ms at 10 FPS)
#define V2_COMPARE_OFFSET    3    // Compare current frame with N-3
#define V2_CENTROID_HISTORY  30   // 3 seconds at 10 FPS
#define V2_BYTES_PER_PIXEL   3    // RGB
#define V2_QUADRANT_SIZE     (V2_QUADRANT_WIDTH * V2_QUADRANT_HEIGHT * V2_BYTES_PER_PIXEL)
#define V2_OSCILLATION_WINDOW 10  // Track last 10 frames for oscillation detection

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
    
    // Per-block confidence (for heatmap overlay)
    float blockConfidence[V2_TOTAL_BLOCKS];
};

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
    
    // Ring buffer: last N frames for this quadrant (grayscale, stride-4 sampled)
    // We store full RGB for edge computation
    uint8_t frameHistory[V2_FRAME_HISTORY][V2_QUADRANT_SIZE];
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
