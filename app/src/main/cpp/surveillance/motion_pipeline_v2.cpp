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

        // Stage 4b: flow coherence. Start at 1.0 (coherent) so a genuine
        // translation isn't demoted before the net-flow window fills.
        qs.lastFlowCoherence = 1.0f;
        qs.netRingIndex = 0;
        qs.netRingCount = 0;
        for (int n = 0; n < V2_FLOW_NET_WINDOW; n++) {
            qs.netRingX[n] = 0.0f;
            qs.netRingY[n] = 0.0f;
        }

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
            int blockHeight = std::min(V2_BLOCK_SIZE, V2_QUADRANT_HEIGHT - startY);
            int lumaChangedCount = 0;
            int edgeChangedCount = 0;
            int shadowPixelCount = 0;   // Pixels that changed but look like shadow
            int totalChangedPixels = 0; // All pixels that passed luma gate
            
            // Full cells retain 64 stride-4 samples. The 16 px last row has 32.
            for (int y = 0; y < blockHeight; y += 4) {
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
            
            // The configured density is calibrated for 64 samples. Preserve it
            // exactly on existing rows and use the same changed fraction on the
            // partial cell, rounding up so a fractional sample never admits it.
            const int fullSamples = (V2_BLOCK_SIZE / 4) * (V2_BLOCK_SIZE / 4);
            int density = config->densityThreshold;
            if (blockHeight < V2_BLOCK_SIZE) {
                int samples = ((blockHeight + 3) / 4) * (V2_BLOCK_SIZE / 4);
                int64_t scaled = static_cast<int64_t>(density) * samples;
                if (scaled > 0) scaled += fullSamples - 1;
                density = static_cast<int>(scaled / fullSamples);
            }
            bool hasEnoughLuma = (lumaChangedCount >= density);
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
// Stage 4: Connected Component Analysis (including the partial bottom row)
// ============================================================================

static int stage4_connectedComponents(
    const bool confirmedBlocks[V2_TOTAL_BLOCKS],
    int componentLabels[V2_TOTAL_BLOCKS],
    float* largestCentroidX,
    float* largestCentroidY,
    int* outLargestLabel,
    int* outComponentCount)
{
    // Initialize labels to -1 (unvisited)
    for (int i = 0; i < V2_TOTAL_BLOCKS; i++) {
        componentLabels[i] = -1;
    }
    
    int largestSize = 0;
    int largestLabel = -1;
    int currentLabel = 0;
    
    // Simple flood-fill BFS using a stack (grid is tiny, 80 cells max)
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
            // Keep the old rows' coordinates exactly. A partial last cell is
            // centered at 232 px, represented as 6.75 so the existing Java crop
            // transform (centroid + .5) * 32 remains correct.
            int rowHeight = std::min(V2_BLOCK_SIZE, V2_QUADRANT_HEIGHT - by * V2_BLOCK_SIZE);
            sumY += by + (rowHeight / (2.0f * V2_BLOCK_SIZE)) - 0.5f;
            
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

    if (outLargestLabel) *outLargestLabel = largestLabel;
    // currentLabel advanced once per discovered component, so it IS the count.
    if (outComponentCount) *outComponentCount = currentLabel;
    return largestSize;
}

// ============================================================================
// Stage 4b: Motion-direction flow coherence (flag / shadow / foliage rejection)
//
// For each block in the largest connected component, estimate a per-block flow
// vector by an integer SAD block-match of the current 32×32 block against the
// N-3 reference over a ±V2_FLOW_SEARCH_RADIUS window (stride-4 grayscale, ~64
// samples/block). A real intruder translates → vectors agree → |net|/|gross|
// (coherence) → 1 and net displacement accumulates over the window. A flag /
// foliage / sweeping shadow oscillates in place → vectors cancel → coherence
// → 0 and windowed net drift stays ≈ 0.
//
// Returns the per-frame net vector via outNetX/outNetY (block units) and the
// instantaneous coherence ratio via outCoherence. Touches only the largest
// component's blocks (typically 2–15 of 70), reusing the existing N-3 ring, so
// the per-frame cost is bounded. A uniform low-variance block (flag interior,
// blank wall) is skipped — block-matching it is the aperture problem and would
// fabricate a spurious vector; we only trust textured blocks.
// ============================================================================

static inline int lumaAt(const uint8_t* img, int x, int y) {
    int idx = (y * V2_QUADRANT_WIDTH + x) * V2_BYTES_PER_PIXEL;
    int r = img[idx] & 0xFF;
    int g = img[idx + 1] & 0xFF;
    int b = img[idx + 2] & 0xFF;
    return (r + (g << 1) + b) >> 2;
}

static float stage4b_flowCoherence(
    const uint8_t* current,
    const uint8_t* reference,
    const int componentLabels[V2_TOTAL_BLOCKS],
    int largestLabel,
    float* outNetX, float* outNetY)
{
    *outNetX = 0.0f;
    *outNetY = 0.0f;
    if (largestLabel < 0) return -1.0f;

    float netX = 0.0f, netY = 0.0f;
    float gross = 0.0f;
    int matchedBlocks = 0;

    // Bound worst-case cost: coherence is a statistical average over the
    // component's blocks, so matching ~12 of them yields the same ratio as
    // matching all of them. Capping here keeps the per-frame block-match count
    // bounded even if a large blob forms (near the 25% global-flash ceiling),
    // so Stage 4b stays well under ~1ms/quadrant on the shared Adreno-610 bus.
    const int MAX_FLOW_BLOCKS = 12;

    for (int blockIdx = 0; blockIdx < V2_TOTAL_BLOCKS && matchedBlocks < MAX_FLOW_BLOCKS; blockIdx++) {
        if (componentLabels[blockIdx] != largestLabel) continue;

        int bx = blockIdx % V2_GRID_COLS;
        int by = blockIdx / V2_GRID_COLS;
        int startX = bx * V2_BLOCK_SIZE;
        int startY = by * V2_BLOCK_SIZE;
        int blockHeight = std::min(V2_BLOCK_SIZE, V2_QUADRANT_HEIGHT - startY);

        // Keep the ±R search window inside the quadrant.
        int x0 = startX, y0 = startY;
        if (x0 < V2_FLOW_SEARCH_RADIUS) x0 = V2_FLOW_SEARCH_RADIUS;
        if (y0 < V2_FLOW_SEARCH_RADIUS) y0 = V2_FLOW_SEARCH_RADIUS;
        int xMax = V2_QUADRANT_WIDTH  - V2_BLOCK_SIZE - V2_FLOW_SEARCH_RADIUS;
        int yMax = V2_QUADRANT_HEIGHT - V2_BLOCK_SIZE - V2_FLOW_SEARCH_RADIUS;
        if (x0 > xMax) x0 = xMax;
        int patchHeight = V2_BLOCK_SIZE;
        if (blockHeight == V2_BLOCK_SIZE) {
            if (y0 > yMax) y0 = yMax;
        } else {
            // Never move a bottom-row flow probe into a different cell. Reserve
            // R px below the sampled patch for a symmetric reference search.
            // Untextured/too-short patches return unavailable, preserving the
            // existing fail-open handling of absent coherence.
            y0 = startY;
            patchHeight = std::min(blockHeight, V2_QUADRANT_HEIGHT - y0 - V2_FLOW_SEARCH_RADIUS);
            if (patchHeight <= 0) continue;
        }
        if (x0 < 0 || y0 < 0) continue;  // block too close to edge to search

        // Cache the current block's stride-4 grayscale samples ONCE: they are
        // invariant across the ~169 search positions below, so recomputing them
        // each time would 169× the luma loads. 8×8 = 64 samples max.
        const int SPB = V2_BLOCK_SIZE / 4;  // samples per block axis (8)
        int curSamp[SPB * SPB];
        int n = 0, sampleSum = 0;
        for (int y = 0; y < patchHeight; y += 4) {
            for (int x = 0; x < V2_BLOCK_SIZE; x += 4) {
                int v = lumaAt(current, x0 + x, y0 + y);
                curSamp[n++] = v;
                sampleSum += v;
            }
        }
        int sampleCount = n;
        if (sampleCount == 0) continue;

        // Variance gate (aperture problem): a flat, low-texture patch
        // block-matches ambiguously and would fabricate a vector. Skip it.
        int meanL = sampleSum / sampleCount;
        int texture = 0;
        for (int i = 0; i < sampleCount; i++) texture += std::abs(curSamp[i] - meanL);
        // ~64 samples; require average per-sample deviation ≥ 6 luma.
        if (texture < sampleCount * 6) continue;

        // Integer SAD block-match over the ±R window (stride-4 grayscale),
        // matching the cached current samples against the N-3 reference.
        long bestSad = -1;
        int bestDx = 0, bestDy = 0;
        // SAD is a sum of nonnegative differences. Once a candidate reaches
        // the current best, the remaining samples cannot improve it. Preserve
        // the strict-less-than tie rule and original scan order so the flow
        // vector and downstream threat decisions remain unchanged.
        for (int dy = -V2_FLOW_SEARCH_RADIUS;
                dy <= V2_FLOW_SEARCH_RADIUS && bestSad != 0; dy++) {
            for (int dx = -V2_FLOW_SEARCH_RADIUS;
                    dx <= V2_FLOW_SEARCH_RADIUS && bestSad != 0; dx++) {
                long sad = 0;
                int si = 0;
                bool canImprove = true;
                for (int y = 0; y < patchHeight && canImprove; y += 4) {
                    for (int x = 0; x < V2_BLOCK_SIZE; x += 4) {
                        int ref = lumaAt(reference, x0 + x + dx, y0 + y + dy);
                        sad += std::abs(curSamp[si++] - ref);
                        if (bestSad >= 0 && sad >= bestSad) {
                            canImprove = false;
                            break;
                        }
                    }
                }
                if (canImprove && (bestSad < 0 || sad < bestSad)) {
                    bestSad = sad;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }

        netX += bestDx;
        netY += bestDy;
        gross += std::sqrt((float)(bestDx * bestDx + bestDy * bestDy));
        matchedBlocks++;
    }

    *outNetX = netX / V2_BLOCK_SIZE;  // convert px → block units
    *outNetY = netY / V2_BLOCK_SIZE;

    if (matchedBlocks == 0 || gross < 1e-3f) {
        // No textured moving blocks (e.g. uniform flag, or no real motion).
        // Treat as "unknown" — return -1 so the caller fails open.
        return -1.0f;
    }

    float netMag = std::sqrt(netX * netX + netY * netY);
    return netMag / gross;  // coherence ∈ [0,1]
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
    
    // Loiter window, clamped to what the centroid ring buffer can actually hold.
    // Without this clamp, a loiteringFrames larger than V2_CENTROID_HISTORY makes
    // the gate below permanently true (centroidCount caps at V2_CENTROID_HISTORY),
    // so THREAT_HIGH would be unreachable — the exact bug that pinned detections at
    // MEDIUM whenever loiter was configured beyond the buffer size.
    int loiterWindow = std::min(config->loiteringFrames, V2_CENTROID_HISTORY);

    // Need enough history for loitering analysis
    if (qs->centroidCount < loiterWindow) {
        // Not enough history yet — default to medium threat if motion exists
        return THREAT_MEDIUM;
    }

    // Check loitering: has centroid stayed within radius for N frames?
    int framesToCheck = std::min(loiterWindow, qs->centroidCount);
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
    // Preserve the behavioral calibration for the original seven rows.
    float centerY = V2_POLICY_ROWS / 2.0f;
    
    float distFirst = std::sqrt((firstX - centerX) * (firstX - centerX) + 
                                (firstY - centerY) * (firstY - centerY));
    float distLast  = std::sqrt((lastX - centerX) * (lastX - centerX) + 
                                (lastY - centerY) * (lastY - centerY));
    
    if (distLast < distFirst - 0.5f) {
        // Moving toward center = approaching
        return THREAT_MEDIUM;
    }
    
    // FIX: Proximity mass override for side cameras (left/right).
    // The "approaching = moving toward center" heuristic works for front/rear
    // cameras where a person walks toward the car (centroid moves from top/far
    // to bottom/close = toward center). But side cameras see lateral motion:
    // a person walking along the car moves across the FOV, not toward center.
    // This was classified as THREAT_LOW (passing), causing left/right cameras
    // to never trigger recording even with a person right next to the car.
    //
    // The BYD's side mirror cameras use ultra-wide fisheye lenses where the
    // centroid Y-coordinate does NOT reliably indicate physical distance.
    // Instead, use the connected component size (active motion area) as a
    // proxy for proximity: a person right next to the car door fills 15-25%
    // of the block grid, while a distant pedestrian fills only 3-5%.
    // This is invariant to camera orientation and fisheye distortion.
    float activeAreaFraction = std::min(1.0f, (float)componentSize / V2_POLICY_BLOCKS);
    if (activeAreaFraction > 0.15f) {
        return THREAT_MEDIUM;
    }
    
    // Passing or receding at distance
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
    // Flow-coherence sentinel: memset zeroed these, but 0.0 means "perfectly
    // incoherent" to Java. -1 = "not computed this frame" → Java fails open to
    // the person-tracker test. Stage 4b overwrites these only when it runs.
    result->flowCoherence = -1.0f;
    result->netDriftBlocks = -1.0f;

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
    //
    // ...EXCEPT that block mass also grows with object SIZE and CLOSENESS: a
    // person at ~1.5m is ~18/70 blocks and a van fills far more, so the closest,
    // largest subjects are the ones this filter is most likely to erase. With
    // salienceEnabled we PROBE such a frame (stages 3/4/4b below) and suppress it
    // only if the mass is diffuse or non-translating; a failed probe restores the
    // pre-probe state and takes this exact path, so it stays byte-identical.
    // Coverage grows without relaxing the existing 18-block flash boundary.
    // Clamp the externally logged fraction when all 80 cells are active.
    float activeRatio = std::min(1.0f, (float)activeCount / V2_POLICY_BLOCKS);
    bool highMass = (activeRatio > 0.25f);
    bool salienceProbe = highMass && config->salienceEnabled != 0;
    if (highMass && !salienceProbe) {
        LOGD_V2("Q%d: Global flash (%.0f%% blocks) — suppressed", quadrantIdx, activeRatio * 100);
        // Decay all blocks
        bool noBlocks[V2_TOTAL_BLOCKS] = {};
        bool dummy[V2_TOTAL_BLOCKS];
        stage3_temporalDecay(qs, noBlocks, config, dummy);
        memcpy(result->blockConfidence, qs->blockConfidence, sizeof(float) * V2_TOTAL_BLOCKS);
        return;
    }

    // Snapshot every field stages 3 and 4b mutate, so a rejected probe can leave
    // the quadrant exactly as the suppression path above would have.
    float savedConfidence[V2_TOTAL_BLOCKS];
    float savedFlowCoherence = qs->lastFlowCoherence;
    float savedNetRingX[V2_FLOW_NET_WINDOW];
    float savedNetRingY[V2_FLOW_NET_WINDOW];
    int   savedNetRingIndex = qs->netRingIndex;
    int   savedNetRingCount = qs->netRingCount;
    if (salienceProbe) {
        memcpy(savedConfidence, qs->blockConfidence, sizeof(savedConfidence));
        memcpy(savedNetRingX, qs->netRingX, sizeof(savedNetRingX));
        memcpy(savedNetRingY, qs->netRingY, sizeof(savedNetRingY));
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
    int largestLabel = -1;
    int componentCount = 0;
    int largestComponent = stage4_connectedComponents(confirmedBlocks, componentLabels, &centroidX, &centroidY, &largestLabel, &componentCount);
    result->componentSize = largestComponent;
    result->componentCount = componentCount;
    result->centroidX = centroidX;
    result->centroidY = centroidY;

    // --- Stage 4b: Flow coherence (flag/shadow/foliage rejection) ---
    // Estimate the largest component's motion direction. Update the per-frame
    // net-displacement ring and EMA-smooth the coherence ratio. Computed here
    // (before Stage 5) so Stage 5's loiter branch can demote an INCOHERENT
    // stationary loiter — a waving flag / sweeping shadow — to THREAT_MEDIUM
    // while a coherently translating approach keeps THREAT_HIGH.
    {
        float netX = 0.0f, netY = 0.0f;
        float coh = stage4b_flowCoherence(quadrantRgb, referenceFrame,
                                          componentLabels, largestLabel, &netX, &netY);
        // Push this frame's net vector into the ring (even when coh<0, push 0 so
        // a stalled/uniform frame decays the windowed drift rather than freezing it).
        qs->netRingX[qs->netRingIndex] = (coh >= 0.0f) ? netX : 0.0f;
        qs->netRingY[qs->netRingIndex] = (coh >= 0.0f) ? netY : 0.0f;
        qs->netRingIndex = (qs->netRingIndex + 1) % V2_FLOW_NET_WINDOW;
        if (qs->netRingCount < V2_FLOW_NET_WINDOW) qs->netRingCount++;

        if (coh >= 0.0f) {
            // EMA-smooth (alpha 0.4) so a single aperture-ambiguous frame can't
            // flip the verdict; init was 1.0 (coherent) to protect early frames.
            qs->lastFlowCoherence = qs->lastFlowCoherence * 0.6f + coh * 0.4f;
        }

        // Windowed cumulative net drift magnitude (blocks).
        float sumX = 0.0f, sumY = 0.0f;
        for (int n = 0; n < qs->netRingCount; n++) { sumX += qs->netRingX[n]; sumY += qs->netRingY[n]; }
        float netDrift = std::sqrt(sumX * sumX + sumY * sumY);

        // Only publish a trustworthy signal once enough flow history has accrued
        // AND this frame actually produced a usable coherence reading. Otherwise
        // leave the -1 sentinel so Java fails open to the tracker test.
        if (qs->netRingCount >= config->coherenceMinFrames && coh >= 0.0f) {
            result->flowCoherence = qs->lastFlowCoherence;
            result->netDriftBlocks = netDrift;
        }
    }

    // --- Salience probe verdict (only on a high-mass frame, salience enabled) ---
    // Decide whether this high-mass frame is ONE OBJECT or an illumination event.
    // Requires ALL of: a component big enough to be an object, that component
    // dominating the confirmed mass (a flash scatters across the frame), and
    // rigid translation (flowCoherence / netDrift — a flash has no consistent
    // flow direction). Coherence UNAVAILABLE (-1) fails the probe: unlike the
    // Java trust gate, this path can only ADD signal, so absent evidence must
    // fall back to the original suppression rather than fail open (invariant I3 —
    // suppression is evidence-scoped, and here the "evidence" is the object's).
    if (salienceProbe) {
        bool bigEnough  = (largestComponent >= config->salienceMinBlocks);
        bool dominant   = (confirmedCount > 0)
                && ((float)largestComponent / (float)confirmedCount) >= config->salienceDominanceFrac;
        bool translating = (result->flowCoherence >= 0.0f)
                && (result->flowCoherence >= config->coherenceRatioMin
                    || result->netDriftBlocks >= config->coherenceNetMin);
        if (bigEnough && dominant && translating) {
            result->salienceState = 2;  // RESCUED
            // Throttled like every other sustained LOGI in this file: the rescue
            // condition holds for the WHOLE approach, so an unthrottled line would
            // emit ~35/s across 4 quadrants and evict the surrounding motion/YOLO
            // lines from logd during the exact event you need to diagnose.
            if (frameCount % 50 == 0) {
                LOGI_V2("Q%d: Salience RESCUE of high-mass frame (%.0f%% blocks): component=%d/%d "
                        "dominance=%.2f coherence=%.2f netDrift=%.1f — large close object, not a flash",
                        quadrantIdx, activeRatio * 100, largestComponent, confirmedCount,
                        confirmedCount > 0 ? (float)largestComponent / confirmedCount : 0.0f,
                        result->flowCoherence, result->netDriftBlocks);
            }
        } else {
            // Probe FAILED → this really does look like a lighting event. Restore
            // every field stages 3/4b touched, then take the original suppression
            // path verbatim so the outcome is identical to salienceEnabled==0.
            memcpy(qs->blockConfidence, savedConfidence, sizeof(savedConfidence));
            memcpy(qs->netRingX, savedNetRingX, sizeof(savedNetRingX));
            memcpy(qs->netRingY, savedNetRingY, sizeof(savedNetRingY));
            qs->netRingIndex = savedNetRingIndex;
            qs->netRingCount = savedNetRingCount;
            qs->lastFlowCoherence = savedFlowCoherence;
            // LOGI, not LOGD: LOGD_V2 compiles to ((void)0) under NDEBUG, which is
            // forced for release builds — and this is a SIGNAL-KILL site, so it must
            // stay attributable on a shipped build (invariant I7). Rate-limited to
            // once per 50 frames so a sustained flash can't flood the log.
            if (frameCount % 50 == 0) {
                LOGI_V2("Q%d: Global flash (%.0f%% blocks) — suppressed (salience probe failed: "
                        "component=%d/%d big=%d dominant=%d translating=%d)",
                        quadrantIdx, activeRatio * 100, largestComponent, confirmedCount,
                        bigEnough, dominant, translating);
            }
            bool noBlocks[V2_TOTAL_BLOCKS] = {};
            bool dummy[V2_TOTAL_BLOCKS];
            stage3_temporalDecay(qs, noBlocks, config, dummy);
            // Clear ONLY what the probe published. Do NOT memset the struct or
            // set brightnessSuppressed: the original flash path leaves meanLuma /
            // activeBlocks / shadowFiltered intact and brightnessSuppressed FALSE
            // (stage 1 already returned false to get here). Setting it true would
            // latch suppressionWasActive[q] in Java, which disables the close-zone
            // fast paths and the 2s AI-confirm timeout for BASELINE_STABILIZATION_
            // FRAMES — turning an FP guard into a scene-wide deafness (invariant I2).
            // confirmedBlocks MUST be zeroed. Stage 3 set it from the PRE-restore
            // (incremented) confidences, while the mask published below holds the
            // POST-restore (decayed) ones — so leaking it publishes a positive
            // count next to a mask that cannot satisfy it. Java's S4 detection
            // filter arms on `snapshotConfirmedBlocks > 0` and then requires a
            // block >= BLOCK_MOTION_MIN_CONF(0.5) near the bbox; in the reachable
            // band (a block on its 2nd active frame: 0.4 counted, 0.3 published)
            // it arms and then matches nothing, dropping EVERY detection — no
            // Actor, no hero, eventEverSawPerson never latches. The original path
            // returned before Stage 3, leaving the memset 0 which takes the
            // fail-open "no motion data → keep all detections" branch instead.
            result->confirmedBlocks = 0;
            result->componentSize = 0;
            result->componentCount = 0;
            result->centroidX = 0.0f;
            result->centroidY = 0.0f;
            result->threatLevel = THREAT_NONE;
            result->motionDetected = false;
            result->flowCoherence = -1.0f;
            result->netDriftBlocks = -1.0f;
            result->salienceState = 1;  // probed and suppressed
            memcpy(result->blockConfidence, qs->blockConfidence, sizeof(float) * V2_TOTAL_BLOCKS);
            return;
        }
    }

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

    // --- Stage 5b: Coherence demotion of incoherent stationary loiter ---
    // Stage 5 calls a near-stationary centroid THREAT_HIGH ("loiter") on
    // geometry alone — exactly what a waving flag / sweeping shadow produces.
    // If Stage 4b has a trustworthy reading (flowCoherence >= 0, i.e. enough
    // history AND a textured moving component) and the motion is INCOHERENT
    // (low ratio AND low windowed net drift), demote HIGH → MEDIUM so it must
    // clear the Java YOLO confirmation gate. DOWNGRADE not drop: a real but
    // YOLO-invisible loiterer still records via the Java 2s-timeout fallback.
    // Fail-open: when flowCoherence < 0 (warmup / uniform / coherence stage
    // absent) we leave the THREAT_HIGH verdict untouched — current behavior.
    if (threatLevel >= THREAT_HIGH && result->flowCoherence >= 0.0f) {
        bool incoherent = (result->flowCoherence < config->coherenceRatioMin)
                       && (result->netDriftBlocks < config->coherenceNetMin);
        if (incoherent) {
            threatLevel = THREAT_MEDIUM;
            if (frameCount % 50 == 0) {
                LOGI_V2("Q%d: HIGH→MEDIUM demote (incoherent loiter: coherence=%.2f<%.2f, netDrift=%.1f<%.1f) — likely flag/shadow",
                        quadrantIdx, result->flowCoherence, config->coherenceRatioMin,
                        result->netDriftBlocks, config->coherenceNetMin);
            }
        }
    }

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
        
        // The JNI caller guarantees a 640x480 RGB mosaic. Stage 1 only
        // samples every eighth pixel, so read those samples from the mosaic
        // directly instead of copying the entire 320x240 RGB quadrant into
        // the scratch buffer. PASS 2 still extracts the same quadrant bytes
        // for the motion filters; brightness/suppression results are identical.
        float meanLuma = 0;
        int sampleCount = 0;
        const int startX = (q % 2) * (width / 2);
        const int startY = (q / 2) * (height / 2);
        const int rowStride = width * V2_BYTES_PER_PIXEL;
        for (int y = 0; y < V2_QUADRANT_HEIGHT; y += 8) {
            const int rowOffset = (startY + y) * rowStride
                    + startX * V2_BYTES_PER_PIXEL;
            for (int x = 0; x < V2_QUADRANT_WIDTH; x += 8) {
                int idx = rowOffset + x * V2_BYTES_PER_PIXEL;
                int r = frame[idx] & 0xFF;
                int g = frame[idx + 1] & 0xFF;
                int b = frame[idx + 2] & 0xFF;
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

// PIPELINE MUTEX (audit R13-1b / ExtE-7). g_pipeline is mutated by THREE
// entry points on different threads: processFrameV2 (engine/motion lane,
// every frame), setQuadrantRoi (control thread, rewrites a quadrant's
// blockRoiMask that processQuadrant scans mid-frame), and initPipelineV2
// (control thread at arm, memsets the whole state an in-flight frame may be
// reading/writing). Same UB class as the tracker globals — retired the same
// way. Hold time is one frame's processing (~10-30ms) on the hot path;
// ROI/init calls are rare user/arm actions, so contention is negligible.
// No callee takes another lock and none calls back into Java — no ordering
// cycle. (The CONFIG buffer race — Java rewriting the direct ByteBuffer
// while this file memcpys it — is fixed Java-side by an immutable
// buffer-swap in MotionPipelineV2.applyConfig; the memcpy here always reads
// a buffer that is never rewritten after publication.)
#include <mutex>
static std::mutex g_pipeline_mutex;

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_initPipelineV2(
    JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::mutex> lock(g_pipeline_mutex);  // audit R13-1b
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
    const jlong expectedFrameBytes =
            (jlong)V2_QUADRANT_WIDTH * 2 * V2_QUADRANT_HEIGHT * 2 * V2_BYTES_PER_PIXEL;
    if (!frameBuffer || !configBuffer || !resultBuffer
            || width != V2_QUADRANT_WIDTH * 2 || height != V2_QUADRANT_HEIGHT * 2
            || env->GetDirectBufferCapacity(frameBuffer) < expectedFrameBytes
            || env->GetDirectBufferCapacity(configBuffer) < (jlong)sizeof(PipelineConfigV2)
            || env->GetDirectBufferCapacity(resultBuffer)
                    < (jlong)(sizeof(QuadrantResultV2) * V2_NUM_QUADRANTS)) {
        jclass invalidArgument = env->FindClass("java/lang/IllegalArgumentException");
        if (invalidArgument) env->ThrowNew(invalidArgument, "Invalid motion frame or native buffer size");
        return;
    }
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
    {
        std::lock_guard<std::mutex> lock(g_pipeline_mutex);  // audit R13-1b
        v2_processFrame(&g_pipeline, frame, width, height, &config, results);
    }
    
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

extern "C" JNIEXPORT jintArray JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_getPipelineGeometryV2(
    JNIEnv* env, jclass clazz)
{
    const jint geometry[] = {
        V2_GEOMETRY_ABI, V2_QUADRANT_WIDTH, V2_QUADRANT_HEIGHT, V2_BLOCK_SIZE,
        V2_GRID_COLS, V2_GRID_ROWS, V2_POLICY_ROWS,
        (jint)offsetof(QuadrantResultV2, blockConfidence)
    };
    jintArray result = env->NewIntArray(8);
    if (result) env->SetIntArrayRegion(result, 0, 8, geometry);
    return result;
}

// Set per-quadrant ROI block mask from Java.
// Accept current masks and legacy 70-cell masks (last row inherits row 6).
// Passing null clears the ROI (all blocks enabled).
extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_setQuadrantRoi(
    JNIEnv* env, jclass clazz,
    jint quadrant, jbyteArray blockMask)
{
    if (quadrant < 0 || quadrant >= V2_NUM_QUADRANTS) return;
    
    if (blockMask == nullptr) {
        // Clear ROI — all blocks enabled
        std::lock_guard<std::mutex> lock(g_pipeline_mutex);  // audit R13-1b
        QuadrantState& qs = g_pipeline.quadrants[quadrant];
        qs.hasCustomRoi = false;
        for (int i = 0; i < V2_TOTAL_BLOCKS; i++) {
            qs.blockRoiMask[i] = true;
        }
        return;
    }
    
    jsize len = env->GetArrayLength(blockMask);
    if (len != V2_TOTAL_BLOCKS && len != V2_POLICY_BLOCKS) {
        LOGE_V2("Invalid ROI mask size: %d (expected %d)", len, V2_TOTAL_BLOCKS);
        return;
    }
    
    jbyte* mask = env->GetByteArrayElements(blockMask, nullptr);
    if (!mask) return;
    
    {
        std::lock_guard<std::mutex> lock(g_pipeline_mutex);  // audit R13-1b
        QuadrantState& qs = g_pipeline.quadrants[quadrant];
        qs.hasCustomRoi = true;
        for (int i = 0; i < V2_TOTAL_BLOCKS; i++) {
            int source = i < len ? i : i - V2_GRID_COLS;
            qs.blockRoiMask[i] = (mask[source] != 0);
        }
    }
    
    env->ReleaseByteArrayElements(blockMask, mask, JNI_ABORT);
}

// ============================================================================
// Texture Tracker JNI Bridge
// ============================================================================

#include "texture_tracker.h"
#include <mutex>

static TrackerState g_trackerState;
static bool g_trackerInitialized = false;

// TRACKER MUTEX (audit R13-1a / ExtE-2). g_trackerState is touched by FOUR
// lanes: the motion/frame lane (trackerUpdate every recording/immunity
// frame), the aiExecutor (startTrack/refreshTemplate/confirmHeartbeat after
// inference), the engine tick (hasActiveTrack/getTrackBox/needsHeartbeat
// queries, dropAllTrackerLocks at stop), and the control thread
// (drop-on-toggle, initTracker at arm). Since round 8 this was carried as an
// "accepted torn-POD" residual — TrackedObject is a fixed inline struct with
// no pointers, so races could not UAF, only tear plain fields (one garbage
// NCC frame, a lost fresh track). Every external audit round re-reported it
// regardless, because unsynchronized concurrent access to non-atomic state
// is C++ UB whatever the observed blast radius. Retired structurally: every
// JNI entry point below holds this mutex for its full body. Hold times are
// small (tracker_update ≈ a few ms of single-threaded NCC on a 64×64
// template; start/refresh ≈ one 4KB memcpy + bookkeeping; queries are
// field reads), contention is rare (lanes coincide only around heartbeats
// and toggles), and none of the tracker_* functions call back out (no JNI
// upcalls, no other locks — cv::setNumThreads(1) keeps matchTemplate on the
// calling thread), so no ordering cycle is possible.
static std::mutex g_tracker_mutex;

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_initTracker(
    JNIEnv* env, jclass clazz)
{
    std::lock_guard<std::mutex> lock(g_tracker_mutex);
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
    jbyte* frameData = env->GetByteArrayElements(frameRgb, nullptr);
    if (!frameData) return -1;
    
    int result;
    {
        std::lock_guard<std::mutex> lock(g_tracker_mutex);  // audit R13-1a
        if (!g_trackerInitialized) {
            env->ReleaseByteArrayElements(frameRgb, frameData, JNI_ABORT);
            return -1;
        }
        result = tracker_startTrack(
            &g_trackerState,
            (const uint8_t*)frameData, width, height,
            quadrant, classId, x, y, w, h, (long long)nowMs);
    }
    
    env->ReleaseByteArrayElements(frameRgb, frameData, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerUpdate(
    JNIEnv* env, jclass clazz,
    jbyteArray frameRgb, jint width, jint height,
    jint quadrant, jlong nowMs)
{
    jbyte* frameData = env->GetByteArrayElements(frameRgb, nullptr);
    if (!frameData) return;
    
    {
        std::lock_guard<std::mutex> lock(g_tracker_mutex);  // audit R13-1a
        if (g_trackerInitialized) {
            tracker_update(
                &g_trackerState,
                (const uint8_t*)frameData, width, height,
                quadrant, (long long)nowMs);
        }
    }
    
    env->ReleaseByteArrayElements(frameRgb, frameData, JNI_ABORT);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerHasActiveTrack(
    JNIEnv* env, jclass clazz, jint quadrant)
{
    std::lock_guard<std::mutex> lock(g_tracker_mutex);  // audit R13-1a
    if (!g_trackerInitialized) return JNI_FALSE;
    return tracker_hasActiveTrack(&g_trackerState, quadrant) ? JNI_TRUE : JNI_FALSE;
}

// Returns float[7]: {x, y, w, h, confidence, classId, active} or null
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerGetTrackBox(
    JNIEnv* env, jclass clazz, jint quadrant)
{
    int x, y, w, h, classId;
    float confidence;
    {
        std::lock_guard<std::mutex> lock(g_tracker_mutex);  // audit R13-1a
        if (!g_trackerInitialized) return nullptr;
        if (!tracker_getTrackBox(&g_trackerState, quadrant, &x, &y, &w, &h, &confidence, &classId)) {
            return nullptr;
        }
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
    std::lock_guard<std::mutex> lock(g_tracker_mutex);  // audit R13-1a
    if (!g_trackerInitialized) return;
    tracker_dropTrack(&g_trackerState, quadrant);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerNeedsYoloHeartbeat(
    JNIEnv* env, jclass clazz, jint quadrant)
{
    std::lock_guard<std::mutex> lock(g_tracker_mutex);  // audit R13-1a
    if (!g_trackerInitialized) return JNI_FALSE;
    return tracker_needsYoloHeartbeat(&g_trackerState, quadrant) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_trackerConfirmHeartbeat(
    JNIEnv* env, jclass clazz, jint quadrant, jlong nowMs)
{
    std::lock_guard<std::mutex> lock(g_tracker_mutex);  // audit R13-1a
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
    jbyte* frameData = env->GetByteArrayElements(frameRgb, nullptr);
    if (!frameData) return;
    
    {
        std::lock_guard<std::mutex> lock(g_tracker_mutex);  // audit R13-1a
        if (g_trackerInitialized) {
            tracker_refreshTemplate(
                &g_trackerState,
                (const uint8_t*)frameData, width, height,
                quadrant, newX, newY, newW, newH, (long long)nowMs);
        }
    }
    
    env->ReleaseByteArrayElements(frameRgb, frameData, JNI_ABORT);
}
