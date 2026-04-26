/**
 * Motion Detection Pipeline V2 — Per-Quadrant 6-Stage Filter Pipeline
 * 
 * See motion_pipeline_v2.h for architecture overview.
 */

#include "motion_pipeline_v2.h"
#include <jni.h>
#include <android/log.h>

#define TAG_V2 "MotionV2"

// Debug logging: only active in debug builds (NDEBUG is defined in release by CMake)
#ifndef NDEBUG
#define LOGD_V2(...) __android_log_print(ANDROID_LOG_DEBUG, TAG_V2, __VA_ARGS__)
#else
#define LOGD_V2(...) ((void)0)
#endif

#define LOGI_V2(...) __android_log_print(ANDROID_LOG_INFO,  TAG_V2, __VA_ARGS__)
#define LOGW_V2(...) __android_log_print(ANDROID_LOG_WARN,  TAG_V2, __VA_ARGS__)
#define LOGE_V2(...) __android_log_print(ANDROID_LOG_ERROR, TAG_V2, __VA_ARGS__)

// Persistent pipeline state (single instance, lives for the sentry session)
static PipelineStateV2 g_pipeline;

// ============================================================================
// Initialization
// ============================================================================

void v2_initPipeline(PipelineStateV2* state) {
    memset(state, 0, sizeof(PipelineStateV2));
    
    for (int q = 0; q < V2_NUM_QUADRANTS; q++) {
        QuadrantState& qs = state->quadrants[q];
        qs.enabled = true;
        qs.hasCustomRoi = false;
        qs.brightnessInitialized = false;
        qs.historyIndex = 0;
        qs.historyCount = 0;
        qs.suppressionCountdown = 0;
        qs.centroidIndex = 0;
        qs.centroidCount = 0;
        qs.oscillationHistoryIndex = 0;
        qs.oscillationHistoryCount = 0;
        
        // Default ROI: all blocks enabled
        for (int i = 0; i < V2_TOTAL_BLOCKS; i++) {
            qs.blockRoiMask[i] = true;
            qs.blockConfidence[i] = 0.0f;
        }
        
        // Clear oscillation history
        memset(qs.blockActiveHistory, 0, sizeof(qs.blockActiveHistory));
    }
    
    state->initialized = true;
    state->frameCount = 0;
    LOGI_V2("Pipeline V2 initialized (%d quadrants, %dx%d grid per quadrant, shadow filter ready)",
            V2_NUM_QUADRANTS, V2_GRID_COLS, V2_GRID_ROWS);
}

// ============================================================================
// Helper: Extract quadrant from mosaic
// ============================================================================

static void extractQuadrant(
    const uint8_t* mosaic, int mosaicW, int mosaicH,
    int quadrant, uint8_t* out)
{
    int qW = mosaicW / 2;
    int qH = mosaicH / 2;
    int startX = (quadrant % 2) * qW;
    int startY = (quadrant / 2) * qH;
    
    // Clamp to actual quadrant dimensions we process
    int copyW = std::min(qW, V2_QUADRANT_WIDTH);
    int copyH = std::min(qH, V2_QUADRANT_HEIGHT);
    
    for (int y = 0; y < copyH; y++) {
        int srcOffset = ((startY + y) * mosaicW + startX) * V2_BYTES_PER_PIXEL;
        int dstOffset = y * copyW * V2_BYTES_PER_PIXEL;
        memcpy(out + dstOffset, mosaic + srcOffset, copyW * V2_BYTES_PER_PIXEL);
    }
}

// ============================================================================
// Stage 1: Global Brightness Check
// ============================================================================

static bool stage1_brightnessCheck(
    QuadrantState* qs,
    const uint8_t* quadrantRgb,
    const PipelineConfigV2* config,
    float* outMeanLuma)
{
    // Compute mean luma with stride-8 sampling
    long lumaSum = 0;
    int sampleCount = 0;
    
    for (int y = 0; y < V2_QUADRANT_HEIGHT; y += 8) {
        int rowOffset = y * V2_QUADRANT_WIDTH * V2_BYTES_PER_PIXEL;
        for (int x = 0; x < V2_QUADRANT_WIDTH; x += 8) {
            int idx = rowOffset + x * V2_BYTES_PER_PIXEL;
            int r = quadrantRgb[idx] & 0xFF;
            int g = quadrantRgb[idx + 1] & 0xFF;
            int b = quadrantRgb[idx + 2] & 0xFF;
            lumaSum += (r + (g << 1) + b) >> 2;
            sampleCount++;
        }
    }
    
    float meanLuma = (sampleCount > 0) ? (float)lumaSum / sampleCount : 0.0f;
    *outMeanLuma = meanLuma;
    
    if (!qs->brightnessInitialized) {
        qs->meanLuma = meanLuma;
        qs->prevMeanLuma = meanLuma;
        qs->baselineLuma = meanLuma;
        qs->brightnessInitialized = true;
        return false; // Don't suppress on first frame
    }
    
    qs->prevMeanLuma = qs->meanLuma;
    qs->meanLuma = meanLuma;
    
    // --- Frame-to-frame shift detection (catches sudden changes) ---
    float denom = std::max(qs->prevMeanLuma, 1.0f);
    float shift = std::abs(meanLuma - qs->prevMeanLuma) / denom;
    
    if (shift > config->brightnessShiftThreshold) {
        qs->suppressionCountdown = config->brightnessSuppressionFrames;
        // Reset baseline after a sudden shift so we adapt to new lighting
        qs->baselineLuma = meanLuma;
        LOGD_V2("Brightness shift %.1f%% > %.1f%% — suppressing",
                shift * 100, config->brightnessShiftThreshold * 100);
        return true;
    }
    
    // --- Baseline drift detection (catches gradual changes like sunset, fluorescent warm-up) ---
    // Compare current luma against a slow-moving baseline. If the baseline has drifted
    // significantly, suppress and reset. This catches changes too slow for frame-to-frame
    // detection but large enough to cause false positives in the N vs N-3 comparison.
    float baselineDenom = std::max(qs->baselineLuma, 1.0f);
    float baselineDrift = std::abs(meanLuma - qs->baselineLuma) / baselineDenom;
    
    // Drift threshold is half the sudden-shift threshold (gradual changes are sneakier)
    float driftThreshold = config->brightnessShiftThreshold * 0.5f;
    if (baselineDrift > driftThreshold) {
        qs->suppressionCountdown = config->brightnessSuppressionFrames;
        qs->baselineLuma = meanLuma;  // Adapt to new level
        LOGD_V2("Baseline drift %.1f%% > %.1f%% — suppressing (gradual light change)",
                baselineDrift * 100, driftThreshold * 100);
        return true;
    }
    
    // Slowly adapt baseline toward current luma (EMA with alpha=0.02 at 10 FPS)
    // This allows the baseline to track very slow environmental changes (day/night)
    // without triggering suppression, while still catching medium-speed drifts.
    qs->baselineLuma = qs->baselineLuma * 0.98f + meanLuma * 0.02f;
    
    // Decrement suppression countdown
    if (qs->suppressionCountdown > 0) {
        qs->suppressionCountdown--;
        // FIX: Adapt baseline DURING suppression, not just after.
        // Without this, the baseline stays at the flash brightness level.
        // When suppression expires, the drift check sees a huge gap between
        // the stable post-flash luma and the stale baseline, re-triggering
        // suppression in an infinite loop. Fast-adapt during suppression
        // so the baseline converges to the new stable level by the time
        // the countdown expires.
        qs->baselineLuma = qs->baselineLuma * 0.8f + meanLuma * 0.2f;  // 10x faster during suppression
        return true;
    }
    
    return false;
}

// ============================================================================
// Shadow Discrimination Helper
// ============================================================================

/**
 * Check if a pixel change is shadow-like by analyzing chrominance preservation.
 * 
 * Key insight: shadows darken a surface but preserve its color ratios.
 * If pixel goes from RGB(200,100,50) to RGB(140,70,35), the ratios R:G:B
 * stay ~2:1:0.5 — it's a shadow. A real object changes the color composition.
 * 
 * We compute the normalized chrominance (r/(r+g+b), g/(r+g+b)) for both
 * current and reference pixels. If the chrominance shift is below tolerance,
 * the change is classified as shadow/lighting, not real motion.
 * 
 * Additional check: shadows always darken (luma decreases) or lighten uniformly.
 * We verify that all channels shift in the same direction (all darker or all brighter).
 */
static bool isShadowPixel(
    int r1, int g1, int b1,  // current pixel
    int r2, int g2, int b2,  // reference pixel
    float chromaTolerance)
{
    // Need minimum brightness to compute meaningful ratios
    int sum1 = r1 + g1 + b1;
    int sum2 = r2 + g2 + b2;
    if (sum1 < 30 || sum2 < 30) return false;  // Too dark to analyze
    
    // Compute normalized chrominance for both pixels
    float invSum1 = 1.0f / sum1;
    float invSum2 = 1.0f / sum2;
    
    float cr1 = r1 * invSum1;  // Red chrominance
    float cg1 = g1 * invSum1;  // Green chrominance
    float cr2 = r2 * invSum2;
    float cg2 = g2 * invSum2;
    
    // Chrominance shift (if color ratios are preserved, this is near zero)
    float chromaDist = std::abs(cr1 - cr2) + std::abs(cg1 - cg2);
    
    if (chromaDist > chromaTolerance) {
        return false;  // Color changed — real object, not shadow
    }
    
    // Verify uniform direction: all channels should shift the same way.
    // FIX: "Headlight False-Positive" — Shadows ONLY darken surfaces. If the
    // pixel got brighter, it's illumination (headlights, streetlights), not a
    // shadow. Previously, "allBrighter" was accepted as shadow-like, which caused
    // headlight sweeps (which also preserve chroma ratios for white/warm light)
    // to be classified as shadows. The pipeline then rejected them, treating the
    // remaining non-shadow pixels as a physical object → false THREAT_HIGH alarms
    // every time a car turned a corner at night.
    //
    // Only accept "allDarker" as shadow. "allBrighter" is illumination change
    // and should be handled by the brightness suppression stage, not shadow filter.
    int dr = r1 - r2;
    int dg = g1 - g2;
    int db = b1 - b2;
    
    // Check if all channels got darker (current < reference, so delta is negative)
    // Allow small counter-movements (±5) for noise tolerance
    bool allDarker = (dr <= 5) && (dg <= 5) && (db <= 5);
    
    // Must have gotten meaningfully darker (not just noise)
    bool meaningfulDarkening = (dr < -5) || (dg < -5) || (db < -5);
    
    if (!allDarker || !meaningfulDarkening) {
        return false;  // Not a shadow — either got brighter or mixed direction
    }
    
    return true;  // Shadow-like: chrominance preserved, uniform direction
}

// ============================================================================
// Stage 2: Per-Block Luma+Edge Analysis (with Shadow Discrimination)
// ============================================================================

static int stage2_blockAnalysis(
    const uint8_t* current,
    const uint8_t* reference,
    const QuadrantState* qs,
    const PipelineConfigV2* config,
    bool activeBlocks[V2_TOTAL_BLOCKS],
    int* shadowSuppressedBlocks)
{
    int totalActive = 0;
    int totalShadowSuppressed = 0;
    bool shadowFilterEnabled = (config->shadowFilterMode > 0);
    
    // Scale chrominance tolerance based on filter mode
    // LIGHT=1: generous tolerance (only obvious shadows filtered)
    // NORMAL=2: balanced
    // AGGRESSIVE=3: strict (more things classified as shadow)
    float chromaTol = config->chromaRatioTolerance;
    float shadowFrac = config->shadowPixelFraction;
    if (shadowFilterEnabled) {
        switch (config->shadowFilterMode) {
            case 1: // LIGHT
                chromaTol *= 1.5f;   // More lenient
                shadowFrac = 0.7f;   // Need 70% shadow pixels to suppress
                break;
            case 2: // NORMAL
                // Use configured values as-is
                break;
            case 3: // AGGRESSIVE
                chromaTol *= 0.7f;   // Stricter
                shadowFrac = 0.3f;   // Only 30% shadow pixels needed to suppress
                break;
        }
    }
    
    for (int by = 0; by < V2_GRID_ROWS; by++) {
        for (int bx = 0; bx < V2_GRID_COLS; bx++) {
            int blockIdx = by * V2_GRID_COLS + bx;
            activeBlocks[blockIdx] = false;
            
            // Skip blocks outside ROI
            if (qs->hasCustomRoi && !qs->blockRoiMask[blockIdx]) {
                continue;
            }
            
            int startX = bx * V2_BLOCK_SIZE;
            int startY = by * V2_BLOCK_SIZE;
            int lumaChangedCount = 0;
            int edgeChangedCount = 0;
            int shadowPixelCount = 0;   // Pixels that changed but look like shadow
            int totalChangedPixels = 0; // All pixels that passed luma gate
            
            // Stride-4 sampling: 8×8 = 64 samples per block
            for (int y = 0; y < V2_BLOCK_SIZE; y += 4) {
                int gy = startY + y;
                if (gy >= V2_QUADRANT_HEIGHT) break;
                
                int rowIdx = gy * V2_QUADRANT_WIDTH * V2_BYTES_PER_PIXEL;
                
                for (int x = 0; x < V2_BLOCK_SIZE; x += 4) {
                    int gx = startX + x;
                    if (gx >= V2_QUADRANT_WIDTH) break;
                    
                    int idx = rowIdx + gx * V2_BYTES_PER_PIXEL;
                    
                    // Current pixel
                    int r1 = current[idx] & 0xFF;
                    int g1 = current[idx + 1] & 0xFF;
                    int b1 = current[idx + 2] & 0xFF;
                    int luma1 = (r1 + (g1 << 1) + b1) >> 2;
                    
                    // Reference pixel
                    int r2 = reference[idx] & 0xFF;
                    int g2 = reference[idx + 1] & 0xFF;
                    int b2 = reference[idx + 2] & 0xFF;
                    int luma2 = (r2 + (g2 << 1) + b2) >> 2;
                    
                    // Gate 1: Luma difference + ratio
                    int lumaDiff = std::abs(luma1 - luma2);
                    if (lumaDiff <= config->shadowThreshold) continue;
                    
                    int maxLuma = std::max(luma1, luma2);
                    int minLuma = std::min(luma1, luma2) + 1;
                    float ratio = (float)maxLuma / minLuma;
                    if (ratio < config->lumaRatioThreshold) continue;
                    
                    // Luma gate passed — this pixel changed significantly
                    totalChangedPixels++;
                    
                    // Shadow discrimination: check if this pixel change is shadow-like
                    if (shadowFilterEnabled && isShadowPixel(r1, g1, b1, r2, g2, b2, chromaTol)) {
                        shadowPixelCount++;
                        continue;  // Skip shadow pixels — don't count toward motion
                    }
                    
                    lumaChangedCount++;
                    
                    // Gate 2: Edge movement
                    int maxEdgeDiff = 0;
                    
                    // Horizontal gradient
                    int gxr = gx + 4;
                    if (gxr < V2_QUADRANT_WIDTH) {
                        int idxR = rowIdx + gxr * V2_BYTES_PER_PIXEL;
                        
                        int r1r = current[idxR] & 0xFF;
                        int g1r = current[idxR + 1] & 0xFF;
                        int b1r = current[idxR + 2] & 0xFF;
                        int luma1r = (r1r + (g1r << 1) + b1r) >> 2;
                        int edgeH1 = std::abs(luma1 - luma1r);
                        
                        int r2r = reference[idxR] & 0xFF;
                        int g2r = reference[idxR + 1] & 0xFF;
                        int b2r = reference[idxR + 2] & 0xFF;
                        int luma2r = (r2r + (g2r << 1) + b2r) >> 2;
                        int edgeH2 = std::abs(luma2 - luma2r);
                        
                        maxEdgeDiff = std::abs(edgeH1 - edgeH2);
                    }
                    
                    // Vertical gradient
                    int gyb = gy + 4;
                    if (gyb < V2_QUADRANT_HEIGHT) {
                        int idxB = gyb * V2_QUADRANT_WIDTH * V2_BYTES_PER_PIXEL + gx * V2_BYTES_PER_PIXEL;
                        
                        int r1b = current[idxB] & 0xFF;
                        int g1b = current[idxB + 1] & 0xFF;
                        int b1b = current[idxB + 2] & 0xFF;
                        int luma1b = (r1b + (g1b << 1) + b1b) >> 2;
                        int edgeV1 = std::abs(luma1 - luma1b);
                        
                        int r2b = reference[idxB] & 0xFF;
                        int g2b = reference[idxB + 1] & 0xFF;
                        int b2b = reference[idxB + 2] & 0xFF;
                        int luma2b = (r2b + (g2b << 1) + b2b) >> 2;
                        int edgeV2 = std::abs(luma2 - luma2b);
                        
                        int vertDiff = std::abs(edgeV1 - edgeV2);
                        if (vertDiff > maxEdgeDiff) maxEdgeDiff = vertDiff;
                    }
                    
                    if (maxEdgeDiff >= config->edgeDiffThreshold) {
                        edgeChangedCount++;
                    }
                }
            }
            
            // Shadow block suppression: if most changed pixels are shadow-like,
            // suppress the entire block even if a few non-shadow pixels remain.
            // This handles the case where shadow edges create some edge changes
            // but the bulk of the motion is shadow.
            //
            // FIX: "Self-Erasing Person" — A person at 5m occupies a fraction of a
            // 32×32 block. Their own shadow falls in the same block. If the shadow
            // pixels satisfy shadowFrac, the entire block (including the person) gets
            // suppressed before YOLO ever sees it. To prevent this, we check if the
            // block has strong edge evidence. Real objects create sharp edges; shadows
            // create soft gradients. If edgeChangedCount >= 2, the block has a real
            // object and must NOT be suppressed regardless of shadow ratio.
            if (shadowFilterEnabled && totalChangedPixels > 0 && edgeChangedCount < 2) {
                float shadowRatio = (float)shadowPixelCount / totalChangedPixels;
                if (shadowRatio >= shadowFrac) {
                    // Block is predominantly shadow with no edge evidence — suppress it
                    totalShadowSuppressed++;
                    continue;  // Skip to next block
                }
            }
            
            bool hasEnoughLuma = (lumaChangedCount >= config->densityThreshold);
            bool hasEdgeEvidence = (edgeChangedCount >= 2);
            
            if (hasEnoughLuma && hasEdgeEvidence) {
                activeBlocks[blockIdx] = true;
                totalActive++;
            }
        }
    }
    
    if (shadowSuppressedBlocks) {
        *shadowSuppressedBlocks = totalShadowSuppressed;
    }
    
    return totalActive;
}

// ============================================================================
// Stage 3: Temporal Decay Confidence
// ============================================================================

static int stage3_temporalDecay(
    QuadrantState* qs,
    const bool activeBlocks[V2_TOTAL_BLOCKS],
    const PipelineConfigV2* config,
    bool confirmedBlocks[V2_TOTAL_BLOCKS])
{
    int totalConfirmed = 0;
    
    for (int i = 0; i < V2_TOTAL_BLOCKS; i++) {
        if (activeBlocks[i]) {
            qs->blockConfidence[i] = std::min(qs->blockConfidence[i] + config->confidenceIncrement, 1.0f);
        } else {
            qs->blockConfidence[i] = std::max(qs->blockConfidence[i] - config->confidenceDecay, 0.0f);
        }
        
        confirmedBlocks[i] = (qs->blockConfidence[i] >= config->confidenceThreshold);
        if (confirmedBlocks[i]) totalConfirmed++;
    }
    
    return totalConfirmed;
}

// ============================================================================
// Stage 4: Connected Component Analysis (flood-fill on 10×7 grid)
// ============================================================================

static int stage4_connectedComponents(
    const bool confirmedBlocks[V2_TOTAL_BLOCKS],
    int componentLabels[V2_TOTAL_BLOCKS],
    float* largestCentroidX,
    float* largestCentroidY)
{
    // Initialize labels to -1 (unvisited)
    for (int i = 0; i < V2_TOTAL_BLOCKS; i++) {
        componentLabels[i] = -1;
    }
    
    int largestSize = 0;
    int largestLabel = -1;
    int currentLabel = 0;
    
    // Simple flood-fill BFS using a stack (grid is tiny, 70 cells max)
    int stack[V2_TOTAL_BLOCKS];
    
    for (int i = 0; i < V2_TOTAL_BLOCKS; i++) {
        if (!confirmedBlocks[i] || componentLabels[i] >= 0) continue;
        
        // BFS from this block
        int stackTop = 0;
        int componentSize = 0;
        float sumX = 0, sumY = 0;
        
        stack[stackTop++] = i;
        componentLabels[i] = currentLabel;
        
        while (stackTop > 0) {
            int idx = stack[--stackTop];
            componentSize++;
            
            int bx = idx % V2_GRID_COLS;
            int by = idx / V2_GRID_COLS;
            sumX += bx;
            sumY += by;
            
            // Check 4 neighbors
            int neighbors[4] = {
                (by > 0) ? (by - 1) * V2_GRID_COLS + bx : -1,                // up
                (by < V2_GRID_ROWS - 1) ? (by + 1) * V2_GRID_COLS + bx : -1, // down
                (bx > 0) ? by * V2_GRID_COLS + (bx - 1) : -1,                // left
                (bx < V2_GRID_COLS - 1) ? by * V2_GRID_COLS + (bx + 1) : -1  // right
            };
            
            for (int n = 0; n < 4; n++) {
                int ni = neighbors[n];
                if (ni >= 0 && confirmedBlocks[ni] && componentLabels[ni] < 0) {
                    componentLabels[ni] = currentLabel;
                    stack[stackTop++] = ni;
                }
            }
        }
        
        if (componentSize > largestSize) {
            largestSize = componentSize;
            largestLabel = currentLabel;
            *largestCentroidX = sumX / componentSize;
            *largestCentroidY = sumY / componentSize;
        }
        
        currentLabel++;
    }
    
    return largestSize;
}

// ============================================================================
// Stage 5: Behavioral Classification
// ============================================================================

static int stage5_behaviorClassification(
    QuadrantState* qs,
    float centroidX, float centroidY,
    int componentSize,
    const PipelineConfigV2* config)
{
    if (componentSize == 0) return THREAT_NONE;
    
    // Store centroid in history
    qs->centroidHistoryX[qs->centroidIndex] = centroidX;
    qs->centroidHistoryY[qs->centroidIndex] = centroidY;
    qs->centroidIndex = (qs->centroidIndex + 1) % V2_CENTROID_HISTORY;
    if (qs->centroidCount < V2_CENTROID_HISTORY) qs->centroidCount++;
    
    // Need enough history for loitering analysis
    if (qs->centroidCount < config->loiteringFrames) {
        // Not enough history yet — default to medium threat if motion exists
        return THREAT_MEDIUM;
    }
    
    // Check loitering: has centroid stayed within radius for N frames?
    int framesToCheck = std::min(config->loiteringFrames, qs->centroidCount);
    float maxDrift = 0.0f;
    float firstX = 0, firstY = 0;
    float lastX = 0, lastY = 0;
    
    for (int i = 0; i < framesToCheck; i++) {
        int idx = (qs->centroidIndex - 1 - i + V2_CENTROID_HISTORY) % V2_CENTROID_HISTORY;
        float cx = qs->centroidHistoryX[idx];
        float cy = qs->centroidHistoryY[idx];
        
        if (i == 0) { lastX = cx; lastY = cy; }
        if (i == framesToCheck - 1) { firstX = cx; firstY = cy; }
        
        // Check drift against most recent centroid
        float dx = cx - qs->centroidHistoryX[(qs->centroidIndex - 1 + V2_CENTROID_HISTORY) % V2_CENTROID_HISTORY];
        float dy = cy - qs->centroidHistoryY[(qs->centroidIndex - 1 + V2_CENTROID_HISTORY) % V2_CENTROID_HISTORY];
        float dist = std::sqrt(dx * dx + dy * dy);
        if (dist > maxDrift) maxDrift = dist;
    }
    
    // Loitering: centroid stayed within radius
    if (maxDrift <= config->loiteringRadiusBlocks) {
        return THREAT_HIGH;
    }
    
    // Check direction: approaching (toward center) vs passing (lateral)
    float centerX = V2_GRID_COLS / 2.0f;
    float centerY = V2_GRID_ROWS / 2.0f;
    
    float distFirst = std::sqrt((firstX - centerX) * (firstX - centerX) + 
                                (firstY - centerY) * (firstY - centerY));
    float distLast  = std::sqrt((lastX - centerX) * (lastX - centerX) + 
                                (lastY - centerY) * (lastY - centerY));
    
    if (distLast < distFirst - 0.5f) {
        // Moving toward center = approaching
        return THREAT_MEDIUM;
    }
    
    // Passing or receding
    return THREAT_LOW;
}

// ============================================================================
// Process single quadrant through all stages
// ============================================================================

static void processQuadrant(
    QuadrantState* qs,
    const uint8_t* quadrantRgb,
    const PipelineConfigV2* config,
    QuadrantResultV2* result,
    int quadrantIdx,
    int frameCount)
{
    // Clear result
    memset(result, 0, sizeof(QuadrantResultV2));
    
    if (!qs->enabled) return;
    
    // --- Stage 1: Brightness check ---
    float meanLuma = 0;
    bool suppressed = stage1_brightnessCheck(qs, quadrantRgb, config, &meanLuma);
    result->meanLuma = meanLuma;
    result->brightnessSuppressed = suppressed;
    
    // Store current frame in ring buffer
    int histIdx = qs->historyIndex;
    memcpy(qs->frameHistory[histIdx], quadrantRgb, V2_QUADRANT_SIZE);
    
    // First few frames: fill history, skip detection
    if (qs->historyCount < V2_FRAME_HISTORY) {
        qs->historyCount++;
        qs->historyIndex = (qs->historyIndex + 1) % V2_FRAME_HISTORY;
        // Copy confidence to result for heatmap
        memcpy(result->blockConfidence, qs->blockConfidence, sizeof(float) * V2_TOTAL_BLOCKS);
        return;
    }
    
    // Advance ring buffer index for next frame
    qs->historyIndex = (qs->historyIndex + 1) % V2_FRAME_HISTORY;
    
    if (suppressed) {
        // Still update confidence (decay all blocks during suppression)
        bool noBlocks[V2_TOTAL_BLOCKS] = {};
        bool dummy[V2_TOTAL_BLOCKS];
        stage3_temporalDecay(qs, noBlocks, config, dummy);
        memcpy(result->blockConfidence, qs->blockConfidence, sizeof(float) * V2_TOTAL_BLOCKS);
        return;
    }
    
    // Get reference frame (N-3 from ring buffer)
    int refIdx = (histIdx - V2_COMPARE_OFFSET + V2_FRAME_HISTORY) % V2_FRAME_HISTORY;
    const uint8_t* referenceFrame = qs->frameHistory[refIdx];
    
    // DIAGNOSTIC: Every 100 frames, compute raw pixel diff between current and reference
    // to verify frames are actually different. If maxDiff=0, the downscaler is returning
    // identical frames (stale data).
    if (frameCount % 100 == 0) {
        int maxDiff = 0;
        int totalDiff = 0;
        int samePixels = 0;
        int samples = 0;
        for (int y = 0; y < V2_QUADRANT_HEIGHT; y += 16) {
            for (int x = 0; x < V2_QUADRANT_WIDTH; x += 16) {
                int idx = (y * V2_QUADRANT_WIDTH + x) * V2_BYTES_PER_PIXEL;
                int r1 = quadrantRgb[idx] & 0xFF;
                int g1 = quadrantRgb[idx+1] & 0xFF;
                int b1 = quadrantRgb[idx+2] & 0xFF;
                int r2 = referenceFrame[idx] & 0xFF;
                int g2 = referenceFrame[idx+1] & 0xFF;
                int b2 = referenceFrame[idx+2] & 0xFF;
                int diff = std::abs(r1-r2) + std::abs(g1-g2) + std::abs(b1-b2);
                if (diff > maxDiff) maxDiff = diff;
                totalDiff += diff;
                if (diff == 0) samePixels++;
                samples++;
            }
        }
        float avgDiff = samples > 0 ? (float)totalDiff / samples : 0;
        LOGI_V2("Q%d DIAG: maxDiff=%d avgDiff=%.1f samePixels=%d/%d (%.0f%%) histCount=%d refIdx=%d curIdx=%d",
                quadrantIdx, maxDiff, avgDiff, samePixels, samples,
                samples > 0 ? (samePixels * 100.0f / samples) : 0,
                qs->historyCount, refIdx, histIdx);
        // Also log a sample pixel from center of quadrant
        int cx = V2_QUADRANT_WIDTH / 2;
        int cy = V2_QUADRANT_HEIGHT / 2;
        int ci = (cy * V2_QUADRANT_WIDTH + cx) * V2_BYTES_PER_PIXEL;
        LOGI_V2("Q%d DIAG: center pixel cur=(%d,%d,%d) ref=(%d,%d,%d)",
                quadrantIdx,
                quadrantRgb[ci] & 0xFF, quadrantRgb[ci+1] & 0xFF, quadrantRgb[ci+2] & 0xFF,
                referenceFrame[ci] & 0xFF, referenceFrame[ci+1] & 0xFF, referenceFrame[ci+2] & 0xFF);
    }
    
    // --- Stage 2: Block analysis ---
    // SOTA: Per-quadrant adaptive exposure — simple Day/Night hysteresis.
    // The BYD's ISP clamps mean luma to ~122 regardless of sun conditions,
    // so a 3-state glare mode is ineffective. Instead, use a 2-state system
    // with relative multipliers that scale the user's config thresholds.
    // Daytime: 50% scaling (ISP contrast crushing makes shadows faint)
    // Night: 45% scaling (ISO noise requires even lower thresholds)
    // YOLO + spatial filter + connected components handle false positive rejection.
    
    #define EXPOSURE_NORMAL 0
    #define EXPOSURE_NIGHT  1
    
    // ISP Warmup Blindfold: ignore luma for the first 15 frames (~3 seconds at 5 FPS).
    // The camera's AEC (Auto Exposure Control) hunts wildly during startup, producing
    // artificially low luma values that would falsely trigger night mode. Force NORMAL
    // until the hardware stabilizes.
    if (frameCount > 15) {
        if (qs->exposureMode == EXPOSURE_NORMAL) {
            if (meanLuma > 0 && meanLuma <= 85.0f) qs->exposureMode = EXPOSURE_NIGHT;
        } else if (qs->exposureMode == EXPOSURE_NIGHT) {
            if (meanLuma > 95.0f) qs->exposureMode = EXPOSURE_NORMAL;
        }
    }
    
    // Log mode transitions (fires once per transition)
    static int prevModes[V2_NUM_QUADRANTS] = {0, 0, 0, 0};
    if (qs->exposureMode != prevModes[quadrantIdx]) {
        const char* modeNames[] = {"NORMAL", "NIGHT"};
        LOGI_V2("Q%d exposure: %s -> %s (luma=%.0f)",
                quadrantIdx, modeNames[prevModes[quadrantIdx]], modeNames[qs->exposureMode], meanLuma);
        prevModes[quadrantIdx] = qs->exposureMode;
    }
    
    // Clone user's baseline config and apply relative hardware scaling
    PipelineConfigV2 localConfig = *config;
    if (qs->exposureMode == EXPOSURE_NORMAL) {
        // Daytime: scale down 50% to survive ISP contrast crushing.
        // At ISO 100, digital noise is near-zero so lower thresholds are safe.
        // YOLO vetoes any false positives from faint shadows or clouds.
        localConfig.shadowThreshold = std::max(8, (int)(localConfig.shadowThreshold * 0.50f));
        localConfig.edgeDiffThreshold = std::max(5, (int)(localConfig.edgeDiffThreshold * 0.50f));
        localConfig.lumaRatioThreshold = std::max(1.05f, localConfig.lumaRatioThreshold * 0.85f);
    } else {
        // Night: scale down 45% for ISO grain compensation
        localConfig.shadowThreshold = std::max(8, (int)(localConfig.shadowThreshold * 0.45f));
        localConfig.edgeDiffThreshold = std::max(5, (int)(localConfig.edgeDiffThreshold * 0.50f));
        localConfig.lumaRatioThreshold = std::max(1.05f, localConfig.lumaRatioThreshold * 0.88f);
    }
    
    bool activeBlocks[V2_TOTAL_BLOCKS];
    int shadowSuppressed = 0;
    int activeCount = stage2_blockAnalysis(quadrantRgb, referenceFrame, qs, &localConfig, activeBlocks, &shadowSuppressed);
    result->activeBlocks = activeCount;
    
    // Diagnostic: log block analysis results every 50 frames
    if (frameCount % 50 == 0) {
        const char* expModes[] = {"N", "NIGHT"};
        LOGI_V2("Q%d stage2: active=%d shadow_suppressed=%d (shadow=%d, ratio=%.1f, edge=%d, density=%d, shadowFilter=%d, exp=%s)",
                quadrantIdx, activeCount, shadowSuppressed, localConfig.shadowThreshold, 
                localConfig.lumaRatioThreshold, localConfig.edgeDiffThreshold, localConfig.densityThreshold,
                localConfig.shadowFilterMode, expModes[qs->exposureMode]);
    }
    
    // --- Stage 2.5: Oscillation filter (tree shadow wind pattern detection) ---
    // Tree shadows in wind create a distinctive flickering pattern: blocks rapidly
    // toggle active/inactive as leaves sway. Real motion is more sustained.
    // Track per-block activation history and suppress blocks that oscillate.
    if (config->shadowFilterMode >= 2 && config->oscillationThreshold > 0) {
        // Store current activation state in history
        int histSlot = qs->oscillationHistoryIndex;
        memcpy(qs->blockActiveHistory[histSlot], activeBlocks, sizeof(bool) * V2_TOTAL_BLOCKS);
        qs->oscillationHistoryIndex = (qs->oscillationHistoryIndex + 1) % V2_OSCILLATION_WINDOW;
        if (qs->oscillationHistoryCount < V2_OSCILLATION_WINDOW) {
            qs->oscillationHistoryCount++;
        }
        
        // Only apply oscillation filter when we have enough history
        if (qs->oscillationHistoryCount >= 6) {
            int oscillationSuppressed = 0;
            for (int i = 0; i < V2_TOTAL_BLOCKS; i++) {
                if (!activeBlocks[i]) continue;
                
                // Count on→off transitions in the history window
                int transitions = 0;
                bool prevActive = false;
                int framesToCheck = std::min(qs->oscillationHistoryCount, (int)V2_OSCILLATION_WINDOW);
                
                for (int f = 0; f < framesToCheck; f++) {
                    int idx = (qs->oscillationHistoryIndex - 1 - f + V2_OSCILLATION_WINDOW) % V2_OSCILLATION_WINDOW;
                    bool wasActive = qs->blockActiveHistory[idx][i];
                    if (f > 0 && wasActive != prevActive) {
                        transitions++;
                    }
                    prevActive = wasActive;
                }
                
                // High transition count = oscillating (shadow flickering)
                if (transitions >= config->oscillationThreshold) {
                    activeBlocks[i] = false;
                    activeCount--;
                    oscillationSuppressed++;
                }
            }
            
            if (oscillationSuppressed > 0 && frameCount % 50 == 0) {
                LOGD_V2("Q%d: Oscillation filter suppressed %d blocks (threshold=%d transitions)",
                        quadrantIdx, oscillationSuppressed, config->oscillationThreshold);
            }
        }
    } else {
        // Still track history even if oscillation filter is off (for when it's enabled later)
        int histSlot = qs->oscillationHistoryIndex;
        memcpy(qs->blockActiveHistory[histSlot], activeBlocks, sizeof(bool) * V2_TOTAL_BLOCKS);
        qs->oscillationHistoryIndex = (qs->oscillationHistoryIndex + 1) % V2_OSCILLATION_WINDOW;
        if (qs->oscillationHistoryCount < V2_OSCILLATION_WINDOW) {
            qs->oscillationHistoryCount++;
        }
    }
    
    // Track if shadow filtering had an effect
    result->shadowFiltered = (shadowSuppressed > 0 || activeCount < result->activeBlocks);
    result->activeBlocks = activeCount;  // Update with post-oscillation count
    
    // --- Global flash filter: if >25% of blocks active, it's likely a light change ---
    // Real motion from a person is typically 2-8 blocks out of 70 (3-11%).
    // Anything activating >25% of blocks is almost certainly a lighting event.
    float activeRatio = (float)activeCount / V2_TOTAL_BLOCKS;
    if (activeRatio > 0.25f) {
        LOGD_V2("Q%d: Global flash (%.0f%% blocks) — suppressed", quadrantIdx, activeRatio * 100);
        // Decay all blocks
        bool noBlocks[V2_TOTAL_BLOCKS] = {};
        bool dummy[V2_TOTAL_BLOCKS];
        stage3_temporalDecay(qs, noBlocks, config, dummy);
        memcpy(result->blockConfidence, qs->blockConfidence, sizeof(float) * V2_TOTAL_BLOCKS);
        return;
    }
    
    // --- Stage 3: Temporal decay ---
    bool confirmedBlocks[V2_TOTAL_BLOCKS];
    int confirmedCount = stage3_temporalDecay(qs, activeBlocks, config, confirmedBlocks);
    result->confirmedBlocks = confirmedCount;
    
    // Copy confidence for heatmap
    memcpy(result->blockConfidence, qs->blockConfidence, sizeof(float) * V2_TOTAL_BLOCKS);
    
    // --- Stage 4: Connected components ---
    int componentLabels[V2_TOTAL_BLOCKS];
    float centroidX = 0, centroidY = 0;
    int largestComponent = stage4_connectedComponents(confirmedBlocks, componentLabels, &centroidX, &centroidY);
    result->componentSize = largestComponent;
    result->centroidX = centroidX;
    result->centroidY = centroidY;
    
    // Check minimum component size
    if (largestComponent < config->minComponentSize) {
        if (largestComponent > 0 && frameCount % 50 == 0) {
            LOGD_V2("Q%d: Component too small (%d < %d)", quadrantIdx, largestComponent, config->minComponentSize);
        }
        return;
    }
    
    // --- Detection zone enforcement: reject motion that's too far from the car ---
    // In each quadrant, row 0 = top (horizon/far), row 6 = bottom (close to car).
    // maxDistanceRow defines the cutoff: centroids above this row are rejected.
    // Close zone: maxDistanceRow=4 → only rows 4-6 (bottom ~43% of quadrant)
    // Normal zone: maxDistanceRow=2 → rows 2-6 (bottom ~71%)
    // Extended zone: maxDistanceRow=0 → all rows (no filtering)
    if (config->maxDistanceRow > 0 && centroidY < (float)config->maxDistanceRow) {
        LOGI_V2("Q%d: REJECTED distance — centroidY=%.1f < maxRow=%d (motion too far from car)",
                quadrantIdx, centroidY, config->maxDistanceRow);
        return;
    }
    
    // Check alarm threshold
    if (confirmedCount < config->alarmBlockThreshold) {
        if (confirmedCount > 0 && frameCount % 50 == 0) {
            LOGD_V2("Q%d: REJECTED alarm — confirmed=%d < threshold=%d",
                    quadrantIdx, confirmedCount, config->alarmBlockThreshold);
        }
        return;
    }
    
    // --- Stage 5: Behavioral classification ---
    int threatLevel = stage5_behaviorClassification(qs, centroidX, centroidY, largestComponent, config);
    result->threatLevel = threatLevel;
    result->motionDetected = (threatLevel > THREAT_NONE);
    
    if (result->motionDetected) {
        const char* threatNames[] = {"NONE", "LOW(pass)", "MEDIUM(approach)", "HIGH(loiter)"};
        LOGI_V2("Q%d: >>> MOTION DETECTED <<<\n"
                "  Threat: %s | Blocks: active=%d confirmed=%d component=%d\n"
                "  Centroid: (%.1f, %.1f) | Luma: %.0f\n"
                "  Config: density=%d edge=%d shadow=%d alarm=%d minComp=%d maxRow=%d loiterR=%.1f loiterF=%d",
                quadrantIdx, threatNames[threatLevel],
                result->activeBlocks, confirmedCount, largestComponent,
                centroidX, centroidY, result->meanLuma,
                config->densityThreshold, config->edgeDiffThreshold, config->shadowThreshold,
                config->alarmBlockThreshold, config->minComponentSize, config->maxDistanceRow,
                config->loiteringRadiusBlocks, config->loiteringFrames);
    }
}

// ============================================================================
// Main entry point: process full mosaic frame
// ============================================================================

void v2_processFrame(
    PipelineStateV2* state,
    const uint8_t* frame,
    int width, int height,
    const PipelineConfigV2* config,
    QuadrantResultV2 results[V2_NUM_QUADRANTS])
{
    if (!state->initialized) {
        v2_initPipeline(state);
    }
    
    state->frameCount++;
    
    // Temporary buffer for extracted quadrant
    uint8_t quadrantBuf[V2_QUADRANT_SIZE];
    
    // ========================================================================
    // PASS 1: Global Illumination Sync (Spillover Hallucination Prevention)
    //
    // Check if ANY camera will trigger a NEW brightness suppression this frame.
    // If so, force ALL other cameras into suppression to prevent spillover.
    //
    // CRITICAL: Only detect NEW flashes (brightness shift on this frame), NOT
    // existing suppression countdowns. Checking existing countdowns creates a
    // self-reinforcing loop: one quadrant's countdown keeps re-triggering
    // global sync, which resets ALL countdowns, which never expire.
    // ========================================================================
    bool globalFlashDetected = false;
    for (int q = 0; q < V2_NUM_QUADRANTS; q++) {
        if (!config->quadrantEnabled[q]) continue;
        QuadrantState* qs = &state->quadrants[q];
        
        // Quick mean luma calculation (same as stage1 but without modifying state)
        extractQuadrant(frame, width, height, q, quadrantBuf);
        float meanLuma = 0;
        int sampleCount = 0;
        for (int y = 0; y < V2_QUADRANT_HEIGHT; y += 8) {
            for (int x = 0; x < V2_QUADRANT_WIDTH; x += 8) {
                int idx = (y * V2_QUADRANT_WIDTH + x) * V2_BYTES_PER_PIXEL;
                int r = quadrantBuf[idx] & 0xFF;
                int g = quadrantBuf[idx + 1] & 0xFF;
                int b = quadrantBuf[idx + 2] & 0xFF;
                meanLuma += (r + (g << 1) + b) >> 2;
                sampleCount++;
            }
        }
        if (sampleCount > 0) meanLuma /= sampleCount;
        
        // Check if this frame has a NEW brightness shift (not an existing countdown)
        if (qs->brightnessInitialized && qs->prevMeanLuma > 0) {
            float shift = std::abs(meanLuma - qs->prevMeanLuma) / (qs->prevMeanLuma + 1.0f);
            if (shift > config->brightnessShiftThreshold) {
                globalFlashDetected = true;
                break;
            }
        }
    }
    
    // If a global flash was detected, force suppression on ALL quadrants
    if (globalFlashDetected) {
        for (int q = 0; q < V2_NUM_QUADRANTS; q++) {
            if (!config->quadrantEnabled[q]) continue;
            QuadrantState* qs = &state->quadrants[q];
            if (qs->suppressionCountdown == 0) {
                // Force suppression — this bleeds the false motion energy out of
                // the block confidence matrix via stage3_temporalDecay
                qs->suppressionCountdown = config->brightnessSuppressionFrames;
            }
        }
    }
    
    // ========================================================================
    // PASS 2: Process each quadrant through the full 6-stage pipeline
    // ========================================================================
    for (int q = 0; q < V2_NUM_QUADRANTS; q++) {
        if (!config->quadrantEnabled[q]) {
            memset(&results[q], 0, sizeof(QuadrantResultV2));
            continue;
        }
        
        // Extract quadrant from mosaic
        extractQuadrant(frame, width, height, q, quadrantBuf);
        
        // Process through all stages
        processQuadrant(&state->quadrants[q], quadrantBuf, config, &results[q], q, state->frameCount);
    }
    
    // Periodic stats log
    if (state->frameCount % 50 == 0) {
        LOGD_V2("Frame %d: Q0=%s Q1=%s Q2=%s Q3=%s%s",
                state->frameCount,
                results[0].motionDetected ? "MOTION" : (results[0].brightnessSuppressed ? "SUPPRESSED" : "quiet"),
                results[1].motionDetected ? "MOTION" : (results[1].brightnessSuppressed ? "SUPPRESSED" : "quiet"),
                results[2].motionDetected ? "MOTION" : (results[2].brightnessSuppressed ? "SUPPRESSED" : "quiet"),
                results[3].motionDetected ? "MOTION" : (results[3].brightnessSuppressed ? "SUPPRESSED" : "quiet"),
                globalFlashDetected ? " [GLOBAL_FLASH_SYNC]" : "");
    }
}

// ============================================================================
// JNI Bridge
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_initPipelineV2(
    JNIEnv* env, jclass clazz)
{
    v2_initPipeline(&g_pipeline);
}

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_processFrameV2(
    JNIEnv* env, jclass clazz,
    jobject frameBuffer,
    jint width, jint height,
    jobject configBuffer,
    jobject resultBuffer)
{
    const uint8_t* frame = (const uint8_t*)env->GetDirectBufferAddress(frameBuffer);
    if (!frame) {
        LOGE_V2("Invalid frame buffer");
        return;
    }
    
    // Deserialize config from direct ByteBuffer
    const uint8_t* configBytes = (const uint8_t*)env->GetDirectBufferAddress(configBuffer);
    if (!configBytes) {
        LOGE_V2("Invalid config buffer");
        return;
    }
    
    PipelineConfigV2 config;
    memcpy(&config, configBytes, sizeof(PipelineConfigV2));
    
    // Process frame
    QuadrantResultV2 results[V2_NUM_QUADRANTS];
    v2_processFrame(&g_pipeline, frame, width, height, &config, results);
    
    // Serialize results to direct ByteBuffer
    uint8_t* resultBytes = (uint8_t*)env->GetDirectBufferAddress(resultBuffer);
    if (!resultBytes) {
        LOGE_V2("Invalid result buffer");
        return;
    }
    
    memcpy(resultBytes, results, sizeof(QuadrantResultV2) * V2_NUM_QUADRANTS);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_getPipelineConfigSize(
    JNIEnv* env, jclass clazz)
{
    return (jint)sizeof(PipelineConfigV2);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_getQuadrantResultSize(
    JNIEnv* env, jclass clazz)
{
    return (jint)sizeof(QuadrantResultV2);
}

// ============================================================================
// Texture Tracker JNI Bridge
// ============================================================================

#include "texture_tracker.h"

static TrackerState g_trackerState;
static bool g_trackerInitialized = false;

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_initTracker(
    JNIEnv* env, jclass clazz)
{
    tracker_init(&g_trackerState);
    g_trackerInitialized = true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerStartTrack(
    JNIEnv* env, jclass clazz,
    jbyteArray frameRgb, jint width, jint height,
    jint quadrant, jint classId,
    jint x, jint y, jint w, jint h,
    jlong nowMs)
{
    if (!g_trackerInitialized) return -1;
    
    jbyte* frameData = env->GetByteArrayElements(frameRgb, nullptr);
    if (!frameData) return -1;
    
    int result = tracker_startTrack(
        &g_trackerState,
        (const uint8_t*)frameData, width, height,
        quadrant, classId, x, y, w, h, (long long)nowMs);
    
    env->ReleaseByteArrayElements(frameRgb, frameData, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerUpdate(
    JNIEnv* env, jclass clazz,
    jbyteArray frameRgb, jint width, jint height,
    jint quadrant, jlong nowMs)
{
    if (!g_trackerInitialized) return;
    
    jbyte* frameData = env->GetByteArrayElements(frameRgb, nullptr);
    if (!frameData) return;
    
    tracker_update(
        &g_trackerState,
        (const uint8_t*)frameData, width, height,
        quadrant, (long long)nowMs);
    
    env->ReleaseByteArrayElements(frameRgb, frameData, JNI_ABORT);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerHasActiveTrack(
    JNIEnv* env, jclass clazz, jint quadrant)
{
    if (!g_trackerInitialized) return JNI_FALSE;
    return tracker_hasActiveTrack(&g_trackerState, quadrant) ? JNI_TRUE : JNI_FALSE;
}

// Returns float[7]: {x, y, w, h, confidence, classId, active} or null
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerGetTrackBox(
    JNIEnv* env, jclass clazz, jint quadrant)
{
    if (!g_trackerInitialized) return nullptr;
    
    int x, y, w, h, classId;
    float confidence;
    if (!tracker_getTrackBox(&g_trackerState, quadrant, &x, &y, &w, &h, &confidence, &classId)) {
        return nullptr;
    }
    
    jfloatArray result = env->NewFloatArray(7);
    float data[7] = {(float)x, (float)y, (float)w, (float)h, confidence, (float)classId, 1.0f};
    env->SetFloatArrayRegion(result, 0, 7, data);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerDropTrack(
    JNIEnv* env, jclass clazz, jint quadrant)
{
    if (!g_trackerInitialized) return;
    tracker_dropTrack(&g_trackerState, quadrant);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerNeedsYoloHeartbeat(
    JNIEnv* env, jclass clazz, jint quadrant)
{
    if (!g_trackerInitialized) return JNI_FALSE;
    return tracker_needsYoloHeartbeat(&g_trackerState, quadrant) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerConfirmHeartbeat(
    JNIEnv* env, jclass clazz, jint quadrant, jlong nowMs)
{
    if (!g_trackerInitialized) return;
    tracker_confirmHeartbeat(&g_trackerState, quadrant, (long long)nowMs);
}

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerRefreshTemplate(
    JNIEnv* env, jclass clazz,
    jbyteArray frameRgb, jint width, jint height,
    jint quadrant,
    jint newX, jint newY, jint newW, jint newH,
    jlong nowMs)
{
    if (!g_trackerInitialized) return;
    
    jbyte* frameData = env->GetByteArrayElements(frameRgb, nullptr);
    if (!frameData) return;
    
    tracker_refreshTemplate(
        &g_trackerState,
        (const uint8_t*)frameData, width, height,
        quadrant, newX, newY, newW, newH, (long long)nowMs);
    
    env->ReleaseByteArrayElements(frameRgb, frameData, JNI_ABORT);
}
