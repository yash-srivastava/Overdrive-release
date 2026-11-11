#include <jni.h>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <arm_neon.h>
#include <cstdint>
#include <cmath>
#include <vector>
#include <memory>
#include <algorithm>
#include <cstring>

#define TAG "NativeMotion"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// ============================================================================
// SOTA: Temporal Consistency & Spatial Analysis
// ============================================================================

// Maximum grid size (640/8 = 80 cols, 480/8 = 60 rows = 4800 blocks max)
#define MAX_GRID_COLS 80
#define MAX_GRID_ROWS 60
#define MAX_BLOCKS (MAX_GRID_COLS * MAX_GRID_ROWS)
#define TEMPORAL_HISTORY_FRAMES 5

// Per-block temporal history (ring buffer of last N frames)
static uint8_t g_blockHistory[MAX_BLOCKS][TEMPORAL_HISTORY_FRAMES];
static int g_historyIndex = 0;
static bool g_historyInitialized = false;
static int g_lastGridCols = 0;
static int g_lastGridRows = 0;

// Initialize/reset temporal history
static void initTemporalHistory(int cols, int rows) {
    if (!g_historyInitialized || cols != g_lastGridCols || rows != g_lastGridRows) {
        memset(g_blockHistory, 0, sizeof(g_blockHistory));
        g_historyIndex = 0;
        g_historyInitialized = true;
        g_lastGridCols = cols;
        g_lastGridRows = rows;
        LOGD("Temporal history initialized: %dx%d grid", cols, rows);
    }
}

// Check if block has consistent motion across N frames
static bool hasTemporalConsistency(int blockIdx, int requiredFrames) {
    if (blockIdx >= MAX_BLOCKS) return false;
    
    int consecutive = 0;
    for (int i = 0; i < TEMPORAL_HISTORY_FRAMES; i++) {
        // Check backwards from current frame
        int idx = (g_historyIndex - i + TEMPORAL_HISTORY_FRAMES) % TEMPORAL_HISTORY_FRAMES;
        if (g_blockHistory[blockIdx][idx]) {
            consecutive++;
            if (consecutive >= requiredFrames) return true;
        } else {
            consecutive = 0;
        }
    }
    return false;
}

// Update block history for current frame
static void updateBlockHistory(int blockIdx, bool active) {
    if (blockIdx < MAX_BLOCKS) {
        g_blockHistory[blockIdx][g_historyIndex] = active ? 1 : 0;
    }
}

// Advance to next frame in history ring buffer
static void advanceHistoryFrame() {
    g_historyIndex = (g_historyIndex + 1) % TEMPORAL_HISTORY_FRAMES;
}

// ============================================================================
// SOTA: RGB to HSV conversion for chrominance filtering
// ============================================================================

// Fast RGB to Hue conversion (returns 0-180 range like OpenCV)
static inline uint8_t rgbToHue(uint8_t r, uint8_t g, uint8_t b) {
    uint8_t maxVal = std::max({r, g, b});
    uint8_t minVal = std::min({r, g, b});
    uint8_t delta = maxVal - minVal;
    
    if (delta == 0) return 0;  // Grayscale, no hue
    
    int hue;
    if (maxVal == r) {
        hue = 30 * (int)(g - b) / delta;  // 0-60 range scaled to 0-30
    } else if (maxVal == g) {
        hue = 60 + 30 * (int)(b - r) / delta;  // 60-120 scaled to 30-60
    } else {
        hue = 120 + 30 * (int)(r - g) / delta;  // 120-180 scaled to 60-90
    }
    
    if (hue < 0) hue += 180;
    return (uint8_t)hue;
}

// YOLO detection is now handled in Java (YoloDetector.kt)
// This C++ module only handles motion detection

// Forward declarations
extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeEdgeMotion(
    JNIEnv* env, jclass clazz,
    jobject currentBuffer, jobject referenceBuffer,
    jint width, jint height, jint blockSize, jfloat sensitivity, jint flashImmunity);

extern "C" JNIEXPORT jlong JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeEdgeMotionSOTA(
    JNIEnv* env, jclass clazz,
    jobject currentBuffer, jobject referenceBuffer,
    jint width, jint height, jint blockSize, jfloat sensitivity, 
    jint flashImmunity, jint temporalFrames, jboolean useChroma);

/**
 * Computes Sum of Absolute Differences using ARM NEON SIMD.
 * 
 * Processes 16 bytes at a time using NEON instructions for maximum performance.
 * Target: <0.5ms for 320x240 RGB (230,400 bytes).
 */
extern "C" JNIEXPORT jfloat JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeSAD(
    JNIEnv* env, jclass clazz,
    jobject currentBuffer, jobject referenceBuffer,
    jint width, jint height) {
    
    // Get direct buffer addresses
    uint8_t* current = (uint8_t*)env->GetDirectBufferAddress(currentBuffer);
    uint8_t* reference = (uint8_t*)env->GetDirectBufferAddress(referenceBuffer);
    
    if (current == nullptr || reference == nullptr) {
        LOGE("Invalid buffer addresses");
        return -1.0f;
    }
    
    int size = width * height * 3;  // RGB
    uint64_t totalDiff = 0;
    
#ifdef __aarch64__
    // ARM64 NEON path - process 16 bytes at a time
    int i = 0;
    for (; i + 16 <= size; i += 16) {
        // Load 16 bytes from each buffer
        uint8x16_t curr = vld1q_u8(current + i);
        uint8x16_t ref = vld1q_u8(reference + i);
        
        // Compute absolute differences
        uint8x16_t diff = vabdq_u8(curr, ref);
        
        // Sum the differences (widen to avoid overflow)
        uint16x8_t sum16 = vpaddlq_u8(diff);
        uint32x4_t sum32 = vpaddlq_u16(sum16);
        uint64x2_t sum64 = vpaddlq_u32(sum32);
        
        // Extract and accumulate
        totalDiff += vgetq_lane_u64(sum64, 0) + vgetq_lane_u64(sum64, 1);
    }
    
    // Handle remaining bytes (scalar)
    for (; i < size; i++) {
        totalDiff += abs(current[i] - reference[i]);
    }
#else
    // Scalar fallback for non-ARM64 devices
    for (int i = 0; i < size; i++) {
        totalDiff += abs(current[i] - reference[i]);
    }
#endif
    
    // Normalize to 0.0-1.0 range
    // Maximum possible difference: size * 255
    float maxDiff = size * 255.0f;
    float normalizedScore = (float)totalDiff / maxDiff;
    
    return normalizedScore;
}

/**
 * Updates reference frame with exponential moving average.
 * 
 * Formula: reference = (1-alpha) * reference + alpha * current
 * 
 * Uses NEON for vectorized computation when available.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_updateReference(
    JNIEnv* env, jclass clazz,
    jobject currentBuffer, jobject referenceBuffer,
    jint width, jint height, jfloat alpha) {
    
    // Get direct buffer addresses
    uint8_t* current = (uint8_t*)env->GetDirectBufferAddress(currentBuffer);
    uint8_t* reference = (uint8_t*)env->GetDirectBufferAddress(referenceBuffer);
    
    if (current == nullptr || reference == nullptr) {
        LOGE("Invalid buffer addresses");
        return;
    }
    
    int size = width * height * 3;  // RGB
    
    // Clamp alpha to valid range
    if (alpha < 0.0f) alpha = 0.0f;
    if (alpha > 1.0f) alpha = 1.0f;
    
    float oneMinusAlpha = 1.0f - alpha;
    
#ifdef __aarch64__
    // ARM64 NEON path - process 8 bytes at a time
    // (Using 8 instead of 16 to avoid precision loss in float conversion)
    int i = 0;
    
    // Create alpha vectors
    float32x4_t vAlpha = vdupq_n_f32(alpha);
    float32x4_t vOneMinusAlpha = vdupq_n_f32(oneMinusAlpha);
    
    for (; i + 8 <= size; i += 8) {
        // Load 8 bytes from each buffer
        uint8x8_t curr8 = vld1_u8(current + i);
        uint8x8_t ref8 = vld1_u8(reference + i);
        
        // Convert to 16-bit for intermediate calculations
        uint16x8_t curr16 = vmovl_u8(curr8);
        uint16x8_t ref16 = vmovl_u8(ref8);
        
        // Split into two 32-bit vectors for float conversion
        uint32x4_t curr32_lo = vmovl_u16(vget_low_u16(curr16));
        uint32x4_t curr32_hi = vmovl_u16(vget_high_u16(curr16));
        uint32x4_t ref32_lo = vmovl_u16(vget_low_u16(ref16));
        uint32x4_t ref32_hi = vmovl_u16(vget_high_u16(ref16));
        
        // Convert to float
        float32x4_t currF_lo = vcvtq_f32_u32(curr32_lo);
        float32x4_t currF_hi = vcvtq_f32_u32(curr32_hi);
        float32x4_t refF_lo = vcvtq_f32_u32(ref32_lo);
        float32x4_t refF_hi = vcvtq_f32_u32(ref32_hi);
        
        // Compute: reference = (1-alpha) * reference + alpha * current
        float32x4_t newRef_lo = vmlaq_f32(vmulq_f32(refF_lo, vOneMinusAlpha), currF_lo, vAlpha);
        float32x4_t newRef_hi = vmlaq_f32(vmulq_f32(refF_hi, vOneMinusAlpha), currF_hi, vAlpha);
        
        // Convert back to uint8
        uint32x4_t newRefU32_lo = vcvtq_u32_f32(newRef_lo);
        uint32x4_t newRefU32_hi = vcvtq_u32_f32(newRef_hi);
        uint16x8_t newRefU16 = vcombine_u16(vmovn_u32(newRefU32_lo), vmovn_u32(newRefU32_hi));
        uint8x8_t newRefU8 = vmovn_u16(newRefU16);
        
        // Store back to reference buffer
        vst1_u8(reference + i, newRefU8);
    }
    
    // Handle remaining bytes (scalar)
    for (; i < size; i++) {
        reference[i] = (uint8_t)(oneMinusAlpha * reference[i] + alpha * current[i]);
    }
#else
    // Scalar fallback
    for (int i = 0; i < size; i++) {
        reference[i] = (uint8_t)(oneMinusAlpha * reference[i] + alpha * current[i]);
    }
#endif
}

/**
 * SOTA: Edge-Based Motion Detection with Flash Immunity
 * 
 * Uses GRADIENT/EDGE differencing instead of raw pixel differencing.
 * This is immune to light flashes because:
 * - Light Flash: Brightness changes but edges stay in same location
 * - Object Motion: Edges physically move to new coordinates
 * 
 * Algorithm:
 * 1. Convert RGB to grayscale
 * 2. Apply Sobel gradient filter to extract edges
 * 3. Compare edge maps between frames (not raw pixels)
 * 4. If edges moved → real motion, if edges just got stronger → flash
 * 
 * Flash Immunity Levels (configurable):
 * - 0 = OFF (legacy pixel differencing, sensitive to flashes)
 * - 1 = LOW (edge-based, some flash filtering)
 * - 2 = MEDIUM (edge-based + brightness normalization)
 * - 3 = HIGH (edge-based + aggressive flash rejection)
 * 
 * Performance: ~1.5ms with stride-2 subsampling on ARM64
 * 
 * Returns: Packed int32 with activeBlocks in lower 16 bits, isFlash flag in bit 16
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeGridMotionChroma(
    JNIEnv* env, jclass clazz,
    jobject currentBuffer, jobject referenceBuffer,
    jint width, jint height, jint blockSize, jfloat sensitivity) {
    
    // Call the new edge-based function with default flash immunity (MEDIUM)
    return Java_com_overdrive_app_surveillance_NativeMotion_computeEdgeMotion(
        env, clazz, currentBuffer, referenceBuffer, width, height, blockSize, sensitivity, 2);
}

/**
 * SOTA: Edge-Based Motion Detection with Configurable Flash Immunity
 * 
 * @param flashImmunity 0=OFF, 1=LOW, 2=MEDIUM, 3=HIGH
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeEdgeMotion(
    JNIEnv* env, jclass clazz,
    jobject currentBuffer, jobject referenceBuffer,
    jint width, jint height, jint blockSize, jfloat sensitivity, jint flashImmunity) {
    
    uint8_t* current = (uint8_t*)env->GetDirectBufferAddress(currentBuffer);
    uint8_t* reference = (uint8_t*)env->GetDirectBufferAddress(referenceBuffer);
    
    if (!current || !reference) {
        LOGE("computeEdgeMotion: Invalid buffer addresses");
        return 0;
    }
    
    int cols = width / blockSize;
    int rows = height / blockSize;
    int activeBlocks = 0;
    int totalBlocks = cols * rows;
    
    // Threshold for edge difference (adjusted by sensitivity)
    // Higher sensitivity = lower threshold = more sensitive
    uint32_t edgeThreshold = (uint32_t)((1.0f - sensitivity) * 50.0f + 10.0f);
    
    // Flash detection variables
    int64_t currentBrightnessSum = 0;
    int64_t referenceBrightnessSum = 0;
    int totalPixels = 0;
    
    // Process each block
    for (int by = 0; by < rows; by++) {
        for (int bx = 0; bx < cols; bx++) {
            int startX = bx * blockSize;
            int startY = by * blockSize;
            uint32_t edgeDiff = 0;
            int edgePixels = 0;
            int64_t localCurrentBrightness = 0;
            int64_t localRefBrightness = 0;
            int localPixels = 0;
            
            // Process block with stride-2 for speed
            // Skip first/last row/col for Sobel (needs neighbors)
            for (int y = 2; y < blockSize - 2; y += 2) {
                for (int x = 2; x < blockSize - 2; x += 2) {
                    int gx = startX + x;
                    int gy = startY + y;
                    
                    if (gx >= width - 1 || gy >= height - 1) continue;
                    
                    // Convert to grayscale: Y = 0.299*R + 0.587*G + 0.114*B
                    // Fast approximation: Y = (R + 2*G + B) >> 2
                    auto toGray = [](uint8_t* rgb, int idx) -> int {
                        return (rgb[idx] + (rgb[idx + 1] << 1) + rgb[idx + 2]) >> 2;
                    };
                    
                    // Get grayscale values for Sobel kernel (3x3)
                    int idx_c = (gy * width + gx) * 3;  // center
                    int idx_t = ((gy - 1) * width + gx) * 3;  // top
                    int idx_b = ((gy + 1) * width + gx) * 3;  // bottom
                    int idx_l = (gy * width + (gx - 1)) * 3;  // left
                    int idx_r = (gy * width + (gx + 1)) * 3;  // right
                    int idx_tl = ((gy - 1) * width + (gx - 1)) * 3;
                    int idx_tr = ((gy - 1) * width + (gx + 1)) * 3;
                    int idx_bl = ((gy + 1) * width + (gx - 1)) * 3;
                    int idx_br = ((gy + 1) * width + (gx + 1)) * 3;
                    
                    // Current frame grayscale
                    int c_c = toGray(current, idx_c);
                    int c_t = toGray(current, idx_t);
                    int c_b = toGray(current, idx_b);
                    int c_l = toGray(current, idx_l);
                    int c_r = toGray(current, idx_r);
                    int c_tl = toGray(current, idx_tl);
                    int c_tr = toGray(current, idx_tr);
                    int c_bl = toGray(current, idx_bl);
                    int c_br = toGray(current, idx_br);
                    
                    // Reference frame grayscale
                    int r_c = toGray(reference, idx_c);
                    int r_t = toGray(reference, idx_t);
                    int r_b = toGray(reference, idx_b);
                    int r_l = toGray(reference, idx_l);
                    int r_r = toGray(reference, idx_r);
                    int r_tl = toGray(reference, idx_tl);
                    int r_tr = toGray(reference, idx_tr);
                    int r_bl = toGray(reference, idx_bl);
                    int r_br = toGray(reference, idx_br);
                    
                    // Sobel X gradient: [-1 0 +1; -2 0 +2; -1 0 +1]
                    // Sobel Y gradient: [-1 -2 -1; 0 0 0; +1 +2 +1]
                    int c_gx = -c_tl + c_tr - 2*c_l + 2*c_r - c_bl + c_br;
                    int c_gy = -c_tl - 2*c_t - c_tr + c_bl + 2*c_b + c_br;
                    int r_gx = -r_tl + r_tr - 2*r_l + 2*r_r - r_bl + r_br;
                    int r_gy = -r_tl - 2*r_t - r_tr + r_bl + 2*r_b + r_br;
                    
                    // Edge magnitude (approximation: |Gx| + |Gy|)
                    int c_edge = abs(c_gx) + abs(c_gy);
                    int r_edge = abs(r_gx) + abs(r_gy);
                    
                    // SOTA: Compare edge POSITIONS, not just magnitudes
                    // If edges moved, the difference in gradient direction matters
                    int edgeMagnitudeDiff = abs(c_edge - r_edge);
                    int edgeDirectionDiff = abs(c_gx - r_gx) + abs(c_gy - r_gy);
                    
                    // Combined edge difference (weighted by flash immunity)
                    int combinedDiff;
                    if (flashImmunity == 0) {
                        // Legacy: just use magnitude difference
                        combinedDiff = edgeMagnitudeDiff;
                    } else if (flashImmunity == 1) {
                        // LOW: Prefer direction change over magnitude
                        combinedDiff = (edgeDirectionDiff * 2 + edgeMagnitudeDiff) / 3;
                    } else if (flashImmunity == 2) {
                        // MEDIUM: Strong preference for direction change
                        combinedDiff = (edgeDirectionDiff * 3 + edgeMagnitudeDiff) / 4;
                    } else {
                        // HIGH: Almost entirely direction-based
                        combinedDiff = (edgeDirectionDiff * 4 + edgeMagnitudeDiff) / 5;
                    }
                    
                    edgeDiff += combinedDiff;
                    edgePixels++;
                    
                    // Track brightness for flash detection
                    localCurrentBrightness += c_c;
                    localRefBrightness += r_c;
                    localPixels++;
                }
            }
            
            // Accumulate global brightness
            currentBrightnessSum += localCurrentBrightness;
            referenceBrightnessSum += localRefBrightness;
            totalPixels += localPixels;
            
            // Check if block has significant edge movement
            if (edgePixels > 0) {
                uint32_t avgEdgeDiff = edgeDiff / edgePixels;
                if (avgEdgeDiff > edgeThreshold) {
                    activeBlocks++;
                }
            }
        }
    }
    
    // Flash detection (for HIGH immunity mode)
    bool isFlash = false;
    if (flashImmunity >= 2 && totalPixels > 0) {
        float avgCurrentBrightness = (float)currentBrightnessSum / totalPixels;
        float avgRefBrightness = (float)referenceBrightnessSum / totalPixels;
        float brightnessChange = std::abs(avgCurrentBrightness - avgRefBrightness);
        float brightnessChangePercent = brightnessChange / 255.0f;
        
        // Flash criteria for HIGH immunity:
        // - Global brightness changed significantly (>15%)
        // - Many blocks active (>50% = widespread change)
        float activeRatio = (float)activeBlocks / totalBlocks;
        
        if (flashImmunity == 3) {
            // HIGH: Aggressive flash rejection
            if (brightnessChangePercent > 0.12f && activeRatio > 0.40f) {
                isFlash = true;
            }
        } else if (flashImmunity == 2) {
            // MEDIUM: Moderate flash rejection
            if (brightnessChangePercent > 0.18f && activeRatio > 0.50f) {
                isFlash = true;
            }
        }
        
        // SOTA: If flash detected but motion is LOCALIZED, it's real motion
        // Real motion during flash: <25% of blocks active
        if (isFlash && activeRatio < 0.25f) {
            isFlash = false;  // Localized motion, not a flash
        }
    }
    
    // Pack result: activeBlocks in lower 16 bits, isFlash in bit 16
    int result = activeBlocks & 0xFFFF;
    if (isFlash) {
        result |= (1 << 16);
    }
    
    return result;
}

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
 * Returns packed 64-bit result (same format for compatibility):
 * - Bit 63:    isFlash (1 = global light change, 0 = real motion)
 * - Bit 48-62: temporalActiveBlocks (blocks with temporal consistency)
 * - Bit 32-47: maxY (bottom of motion bounding box)
 * - Bit 16-31: minY (top of motion bounding box)
 * - Bit 0-15:  rawActiveBlocks (before temporal filtering)
 * 
 * @param sensitivity Maps to alarmBlockThreshold: LOW=5, MED=3, HIGH=2
 * @param flashImmunity Maps to shadowThreshold: 0=30, 1=40, 2=50, 3=60
 * @param temporalFrames Required consecutive frames (0=disabled, 3=recommended)
 * @param useChroma IGNORED in grayscale mode (kept for API compatibility)
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeEdgeMotionSOTA(
    JNIEnv* env, jclass clazz,
    jobject currentBuffer, jobject referenceBuffer,
    jint width, jint height, jint blockSize, jfloat sensitivity, 
    jint flashImmunity, jint temporalFrames, jboolean useChroma) {
    
    uint8_t* current = (uint8_t*)env->GetDirectBufferAddress(currentBuffer);
    uint8_t* reference = (uint8_t*)env->GetDirectBufferAddress(referenceBuffer);
    
    if (!current || !reference) {
        LOGE("computeGrayscaleGrid: Invalid buffer addresses");
        return 0;
    }
    
    // =========================================================================
    // CONFIGURATION - Mapped from UI parameters
    // =========================================================================
    
    // Shadow Threshold: Min luma difference to count as motion (0-255)
    // Balance: Too low = catches lights, Too high = misses distant people
    // Testing shows: Lights cause ~30-50 diff, People at 5m cause ~50-80 diff
    // flashImmunity: 0=SENSITIVE(40), 1=NORMAL(55), 2=STRICT(70), 3=MAX(90)
    int shadowThreshold;
    switch (flashImmunity) {
        case 0:  shadowThreshold = 40;  break;  // Sensitive - may catch some lights
        case 1:  shadowThreshold = 55;  break;  // Normal (default) - balanced
        case 2:  shadowThreshold = 70;  break;  // Strict - filters most lights
        case 3:  shadowThreshold = 90;  break;  // Maximum - only high contrast
        default: shadowThreshold = 55;  break;
    }
    
    // Alarm Block Threshold: How many blocks must change to trigger
    // A walking person at 5m creates ~3-6 blocks of motion
    // sensitivity: 0.02=HIGH(2), 0.04=MED(3), 0.06=LOW(5)
    int alarmBlockThreshold;
    if (sensitivity <= 0.025f) {
        alarmBlockThreshold = 2;  // HIGH sensitivity - detect distant objects
    } else if (sensitivity <= 0.045f) {
        alarmBlockThreshold = 3;  // MEDIUM sensitivity (default)
    } else {
        alarmBlockThreshold = 5;  // LOW sensitivity - only large/close objects
    }
    
    // Pixel Density Threshold: Pixels per block that must change
    // 32x32 = 1024 pixels. With stride-4, we check ~64 pixels.
    // 8 pixels = ~12% of sampled pixels must change
    const int pixelDensityThreshold = 8;
    
    // Global Light Filter: If >50% of blocks change, it's a light switch
    // Lowered to catch more global lighting changes
    const float globalLightFilterPercent = 0.50f;
    
    // =========================================================================
    // GRID SETUP
    // =========================================================================
    
    int cols = width / blockSize;
    int rows = height / blockSize;
    
    // Clamp to max grid size
    if (cols > MAX_GRID_COLS) cols = MAX_GRID_COLS;
    if (rows > MAX_GRID_ROWS) rows = MAX_GRID_ROWS;
    
    // Initialize temporal history if needed
    initTemporalHistory(cols, rows);
    
    int rawActiveBlocks = 0;
    int temporalActiveBlocks = 0;
    int totalBlocks = cols * rows;
    
    // Spatial bounding box of motion
    int motionMinY = height;
    int motionMaxY = 0;
    
    // =========================================================================
    // GRAYSCALE GRID DETECTION - Process each block
    // =========================================================================
    
    for (int by = 0; by < rows; by++) {
        for (int bx = 0; bx < cols; bx++) {
            int blockIdx = by * cols + bx;
            int startX = bx * blockSize;
            int startY = by * blockSize;
            int changedPixels = 0;
            
            // Process block with stride-4 for speed (check every 4th pixel)
            // This gives us ~64 samples per 32x32 block - enough for accuracy
            for (int y = 0; y < blockSize; y += 4) {
                int gy = startY + y;
                if (gy >= height) break;
                
                // Calculate row start index once per row
                int rowIdx = gy * width * 3;
                
                for (int x = 0; x < blockSize; x += 4) {
                    int gx = startX + x;
                    if (gx >= width) break;
                    
                    int idx = rowIdx + gx * 3;
                    
                    // =========================================================
                    // GRAYSCALE CONVERSION
                    // Luma = (R + 2*G + B) >> 2  (fast integer approximation)
                    // This eliminates color noise from LEDs, etc.
                    // =========================================================
                    int r1 = current[idx] & 0xFF;
                    int g1 = current[idx + 1] & 0xFF;
                    int b1 = current[idx + 2] & 0xFF;
                    int luma1 = (r1 + (g1 << 1) + b1) >> 2;
                    
                    int r2 = reference[idx] & 0xFF;
                    int g2 = reference[idx + 1] & 0xFF;
                    int b2 = reference[idx + 2] & 0xFF;
                    int luma2 = (r2 + (g2 << 1) + b2) >> 2;
                    
                    // =========================================================
                    // SHADOW FILTER
                    // If pixel changed by less than shadowThreshold, ignore it.
                    // Shadows are faint (~10-20). Real objects are bright (>40).
                    // =========================================================
                    int diff = std::abs(luma1 - luma2);
                    
                    if (diff > shadowThreshold) {
                        changedPixels++;
                    }
                }
            }
            
            // =========================================================
            // BLOCK ACTIVATION CHECK
            // Did enough pixels change in this block?
            // =========================================================
            bool isBlockActive = (changedPixels >= pixelDensityThreshold);
            
            // Update temporal history
            updateBlockHistory(blockIdx, isBlockActive);
            
            if (isBlockActive) {
                rawActiveBlocks++;
                
                // Update spatial bounding box
                int blockTop = by * blockSize;
                int blockBottom = blockTop + blockSize;
                
                if (blockTop < motionMinY) motionMinY = blockTop;
                if (blockBottom > motionMaxY) motionMaxY = blockBottom;
                
                // Temporal consistency check
                bool temporallyConsistent = (temporalFrames <= 0) || 
                    hasTemporalConsistency(blockIdx, temporalFrames);
                
                if (temporallyConsistent) {
                    temporalActiveBlocks++;
                }
            }
        }
    }
    
    // =========================================================================
    // SPATIAL CLUSTERING CHECK (before advancing history!)
    // Lights cause scattered, random blocks. Real objects cause CONNECTED blocks.
    // Count how many active blocks have at least one active neighbor.
    // =========================================================================
    int clusteredBlocks = 0;
    int currentHistoryIdx = g_historyIndex;  // Current frame's history index
    for (int by = 0; by < rows; by++) {
        for (int bx = 0; bx < cols; bx++) {
            int blockIdx = by * cols + bx;
            if (!g_blockHistory[blockIdx][currentHistoryIdx]) continue;  // Not active
            
            // Check 4-connected neighbors (up, down, left, right)
            bool hasNeighbor = false;
            if (by > 0 && g_blockHistory[(by-1) * cols + bx][currentHistoryIdx]) hasNeighbor = true;
            if (by < rows-1 && g_blockHistory[(by+1) * cols + bx][currentHistoryIdx]) hasNeighbor = true;
            if (bx > 0 && g_blockHistory[by * cols + (bx-1)][currentHistoryIdx]) hasNeighbor = true;
            if (bx < cols-1 && g_blockHistory[by * cols + (bx+1)][currentHistoryIdx]) hasNeighbor = true;
            
            if (hasNeighbor) clusteredBlocks++;
        }
    }
    
    // Advance temporal history to next frame (AFTER clustering check)
    advanceHistoryFrame();
    
    // =========================================================================
    // GLOBAL LIGHT FILTER
    // If >60% of blocks changed, it's a light switch or auto-exposure.
    // Real motion (person/car) rarely covers 60% of the screen instantly.
    // =========================================================================
    bool isFlash = false;
    float activeRatio = (float)rawActiveBlocks / totalBlocks;
    
    if (activeRatio > globalLightFilterPercent) {
        isFlash = true;
        LOGD("Global light change detected: %.1f%% blocks active - IGNORED", activeRatio * 100);
    }
    
    // =========================================================================
    // SCATTERED LIGHT FILTER (RELAXED)
    // Only reject if motion is VERY scattered (no clustering at all) AND small.
    // A walking person creates 3-8 blocks that may not always be adjacent
    // because they're moving between frames.
    // 
    // Only reject if: <25% clustered AND <5 blocks (truly random noise)
    // =========================================================================
    if (!isFlash && rawActiveBlocks >= alarmBlockThreshold && rawActiveBlocks < 5) {
        float clusterRatio = (rawActiveBlocks > 0) ? (float)clusteredBlocks / rawActiveBlocks : 0;
        
        // Only reject truly scattered small motion (likely LED reflections)
        if (clusterRatio < 0.25f) {
            LOGD("Scattered motion rejected: %d blocks, %.0f%% clustered", 
                 rawActiveBlocks, clusterRatio * 100);
            isFlash = true;
        }
    }
    
    // =========================================================================
    // PACK RESULT (same format as before for compatibility)
    // =========================================================================
    jlong result = 0;
    
    if (isFlash) result |= (1LL << 63);
    result |= ((jlong)(temporalActiveBlocks & 0x7FFF) << 48);
    result |= ((jlong)(motionMaxY & 0xFFFF) << 32);
    result |= ((jlong)(motionMinY & 0xFFFF) << 16);
    result |= (jlong)(rawActiveBlocks & 0xFFFF);
    
    // Debug logging (only when motion detected)
    if (rawActiveBlocks >= alarmBlockThreshold && !isFlash) {
        LOGD("Motion: raw=%d, clustered=%d, threshold=%d, shadow=%d", 
             rawActiveBlocks, clusteredBlocks, alarmBlockThreshold, shadowThreshold);
    }
    
    return result;
}

// ============================================================================
// LEGACY CODE REMOVED - The following edge-based code has been replaced
// by the Grayscale Grid approach above. Keeping this comment for reference.
// ============================================================================

/*
 * OLD EDGE-BASED APPROACH (REMOVED):
 * - Used Sobel gradients to detect edge movement
 * - Complex chrominance filtering
 * - Prone to false positives from LED color shifts
 * 
 * NEW GRAYSCALE GRID APPROACH:
 * - Simple luma differencing
 * - Per-pixel shadow threshold
 * - Global light filter
 * - Deterministic and predictable
 */

// Placeholder to maintain code structure - the actual Sobel code was here
static void __removed_edge_detection_placeholder() {
    // This function exists only to mark where the old code was
    // The new grayscale grid approach is implemented above
}

/**
 * Reset temporal history (call when surveillance is enabled/disabled)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_resetTemporalHistory(
    JNIEnv* env, jclass clazz) {
    g_historyInitialized = false;
    LOGD("Temporal history reset");
}

// ============================================================================
// UNIFIED SENSITIVITY API - Single Slider Control
// ============================================================================

/**
 * UNIFIED GRAYSCALE GRID MOTION DETECTION
 * 
 * This is the production API that accepts explicit thresholds from Java.
 * The Java layer maps a single "Sensitivity" slider (0-100%) to these params.
 * 
 * @param shadowThreshold   Per-pixel luma diff to count as changed (25-90)
 *                          Lower = more sensitive to faint motion
 *                          Higher = ignores shadows/lights
 * 
 * @param densityThreshold  Pixels per block that must change (8-64)
 *                          Lower = easier to trigger a block
 *                          Higher = needs more pixels changed
 * 
 * @param alarmBlockThreshold  Blocks needed to trigger alarm (1-5)
 *                             Lower = more sensitive (1 block = any motion)
 *                             Higher = needs larger object
 * 
 * Returns packed 64-bit result (same format as computeEdgeMotionSOTA):
 * - Bit 63:    isFlash (1 = global light change, 0 = real motion)
 * - Bit 48-62: temporalActiveBlocks
 * - Bit 32-47: maxY (bottom of motion bounding box)
 * - Bit 16-31: minY (top of motion bounding box)
 * - Bit 0-15:  rawActiveBlocks
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeGrayscaleGrid(
    JNIEnv* env, jclass clazz,
    jobject currentBuffer, jobject referenceBuffer,
    jint width, jint height,
    jint shadowThreshold,
    jint densityThreshold,
    jint alarmBlockThreshold) {
    
    uint8_t* current = (uint8_t*)env->GetDirectBufferAddress(currentBuffer);
    uint8_t* reference = (uint8_t*)env->GetDirectBufferAddress(referenceBuffer);
    
    if (!current || !reference) {
        LOGE("computeGrayscaleGrid: Invalid buffer addresses");
        return 0;
    }
    
    // Fixed block size of 32 (optimal for human detection)
    const int blockSize = 32;
    
    int cols = width / blockSize;
    int rows = height / blockSize;
    
    // Clamp to max grid size
    if (cols > MAX_GRID_COLS) cols = MAX_GRID_COLS;
    if (rows > MAX_GRID_ROWS) rows = MAX_GRID_ROWS;
    
    // Initialize temporal history if needed
    initTemporalHistory(cols, rows);
    
    int rawActiveBlocks = 0;
    int temporalActiveBlocks = 0;
    int totalBlocks = cols * rows;
    
    // Spatial bounding box of motion
    int motionMinY = height;
    int motionMaxY = 0;
    
    // Global light filter threshold (50% of blocks = light switch)
    const float globalLightFilterPercent = 0.50f;
    
    // Process each block
    for (int by = 0; by < rows; by++) {
        for (int bx = 0; bx < cols; bx++) {
            int blockIdx = by * cols + bx;
            int startX = bx * blockSize;
            int startY = by * blockSize;
            int changedPixels = 0;
            
            // Process block with stride-4 for speed
            for (int y = 0; y < blockSize; y += 4) {
                int gy = startY + y;
                if (gy >= height) break;
                
                int rowIdx = gy * width * 3;
                
                for (int x = 0; x < blockSize; x += 4) {
                    int gx = startX + x;
                    if (gx >= width) break;
                    
                    int idx = rowIdx + gx * 3;
                    
                    // Grayscale conversion: Y = (R + 2*G + B) >> 2
                    int r1 = current[idx] & 0xFF;
                    int g1 = current[idx + 1] & 0xFF;
                    int b1 = current[idx + 2] & 0xFF;
                    int luma1 = (r1 + (g1 << 1) + b1) >> 2;
                    
                    int r2 = reference[idx] & 0xFF;
                    int g2 = reference[idx + 1] & 0xFF;
                    int b2 = reference[idx + 2] & 0xFF;
                    int luma2 = (r2 + (g2 << 1) + b2) >> 2;
                    
                    // Shadow filter: only count if diff > shadowThreshold
                    int diff = std::abs(luma1 - luma2);
                    if (diff > shadowThreshold) {
                        changedPixels++;
                    }
                }
            }
            
            // Block activation: did enough pixels change?
            bool isBlockActive = (changedPixels >= densityThreshold);
            
            // Update temporal history
            updateBlockHistory(blockIdx, isBlockActive);
            
            if (isBlockActive) {
                rawActiveBlocks++;
                
                // Update spatial bounding box
                int blockTop = by * blockSize;
                int blockBottom = blockTop + blockSize;
                
                if (blockTop < motionMinY) motionMinY = blockTop;
                if (blockBottom > motionMaxY) motionMaxY = blockBottom;
                
                // Temporal consistency check (3 frames)
                if (hasTemporalConsistency(blockIdx, 3)) {
                    temporalActiveBlocks++;
                }
            }
        }
    }
    
    // Spatial clustering check
    int clusteredBlocks = 0;
    int currentHistoryIdx = g_historyIndex;
    for (int by = 0; by < rows; by++) {
        for (int bx = 0; bx < cols; bx++) {
            int blockIdx = by * cols + bx;
            if (!g_blockHistory[blockIdx][currentHistoryIdx]) continue;
            
            bool hasNeighbor = false;
            if (by > 0 && g_blockHistory[(by-1) * cols + bx][currentHistoryIdx]) hasNeighbor = true;
            if (by < rows-1 && g_blockHistory[(by+1) * cols + bx][currentHistoryIdx]) hasNeighbor = true;
            if (bx > 0 && g_blockHistory[by * cols + (bx-1)][currentHistoryIdx]) hasNeighbor = true;
            if (bx < cols-1 && g_blockHistory[by * cols + (bx+1)][currentHistoryIdx]) hasNeighbor = true;
            
            if (hasNeighbor) clusteredBlocks++;
        }
    }
    
    // Advance temporal history
    advanceHistoryFrame();
    
    // Global light filter
    bool isFlash = false;
    float activeRatio = (float)rawActiveBlocks / totalBlocks;
    
    if (activeRatio > globalLightFilterPercent) {
        isFlash = true;
        LOGD("Global light change: %.1f%% blocks - IGNORED", activeRatio * 100);
    }
    
    // Scattered light filter (reject random noise)
    if (!isFlash && rawActiveBlocks >= alarmBlockThreshold && rawActiveBlocks < 5) {
        float clusterRatio = (rawActiveBlocks > 0) ? (float)clusteredBlocks / rawActiveBlocks : 0;
        if (clusterRatio < 0.25f) {
            LOGD("Scattered motion rejected: %d blocks, %.0f%% clustered", 
                 rawActiveBlocks, clusterRatio * 100);
            isFlash = true;
        }
    }
    
    // Pack result
    jlong result = 0;
    if (isFlash) result |= (1LL << 63);
    result |= ((jlong)(temporalActiveBlocks & 0x7FFF) << 48);
    result |= ((jlong)(motionMaxY & 0xFFFF) << 32);
    result |= ((jlong)(motionMinY & 0xFFFF) << 16);
    result |= (jlong)(rawActiveBlocks & 0xFFFF);
    
    // Debug logging - log every 50 frames to see what's happening
    static int frameCounter = 0;
    frameCounter++;
    if (frameCounter % 50 == 0 || rawActiveBlocks > 0) {
        LOGD("Frame %d: raw=%d, temporal=%d, alarm=%d, shadow=%d, density=%d, flash=%d", 
             frameCounter, rawActiveBlocks, temporalActiveBlocks, alarmBlockThreshold, 
             shadowThreshold, densityThreshold, isFlash ? 1 : 0);
    }
    
    return result;
}

/**
 * SOTA: Grid-Based Motion Detection
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
 * Performance: <1ms with stride-2 subsampling on ARM64
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeGridMotion(
    JNIEnv* env, jclass clazz,
    jobject currentBuffer, jobject referenceBuffer,
    jint width, jint height, jint blockSize, jfloat sensitivity) {
    
    uint8_t* current = (uint8_t*)env->GetDirectBufferAddress(currentBuffer);
    uint8_t* reference = (uint8_t*)env->GetDirectBufferAddress(referenceBuffer);
    
    if (!current || !reference) {
        LOGE("computeGridMotion: Invalid buffer addresses");
        return 0;
    }
    
    int cols = width / blockSize;
    int rows = height / blockSize;
    int activeBlocks = 0;
    
    // Threshold in raw pixel difference sum for one block
    // 255 (max diff) * 3 (channels) * pixels_in_block * sensitivity
    // With stride-2 subsampling, we only check half the rows
    float pixelThreshold = 255.0f * 3.0f * (blockSize * blockSize / 2) * sensitivity;
    uint32_t blockThresh = (uint32_t)pixelThreshold;
    
    for (int by = 0; by < rows; by++) {
        for (int bx = 0; bx < cols; bx++) {
            int startX = bx * blockSize;
            int startY = by * blockSize;
            uint32_t blockDiff = 0;
            
            // Inner loop with stride-2 Y subsampling (saves 50% CPU)
            // A walking person won't hide between scanlines
            for (int y = 0; y < blockSize; y += 2) {
                int rowStart = ((startY + y) * width + startX) * 3;
                
                // Process one row of the block
                for (int x = 0; x < blockSize; x++) {
                    int idx = rowStart + x * 3;
                    int r = abs(current[idx] - reference[idx]);
                    int g = abs(current[idx + 1] - reference[idx + 1]);
                    int b = abs(current[idx + 2] - reference[idx + 2]);
                    blockDiff += (r + g + b);
                }
                
                // Early exit: if this block is already "hot", stop counting
                if (blockDiff > blockThresh) break;
            }
            
            if (blockDiff > blockThresh) {
                activeBlocks++;
                // Could return 1 here for "any motion" mode, but counting
                // helps estimate motion size for logging
            }
        }
    }
    
    return activeBlocks;
}

#if HAVE_OPENCV
#include <opencv2/opencv.hpp>
#include <opencv2/video/background_segm.hpp>

// Global MOG2 instance (one per process)
static cv::Ptr<cv::BackgroundSubtractorMOG2> g_mog2;
static bool g_mog2_initialized = false;
// Explicit-release latch (audit R11-6 / ExtD-6). releaseMOG2 (disarm
// teardown) sets it; resetMOG2 (session arm) clears it. Java's disable()
// does not join an in-flight frame tick, so a straggler
// computeMOG2Quadrants landing AFTER releaseMOG2 used to find
// !g_mog2_initialized and lazily initMog2() — silently re-creating the
// ~18.7MB model for the entire disarm period (the driving majority of
// runtime), defeating the R3b Ext-14 release. With the latch, a
// post-release sample returns -1 (channel fails CLOSED per I9, exactly as
// if OpenCV were absent) until the next session's resetMOG2 re-arms it.
// Guarded by g_mog2_mutex like the rest of the MOG2 globals.
static bool g_mog2_released = false;

// Global motion mask for overlap checking
static cv::Mat g_lastMotionMask;
static int g_motionMaskWidth = 0;
static int g_motionMaskHeight = 0;

// Serializes ALL touches of the process-global g_mog2 AND g_lastMotionMask
// (reassignment/release in resetMOG2 vs apply() in computeMOG2Quadrants —
// plus the legacy computeMOG2 / checkMotionOverlap paths, dead today but
// locked so this claim stays true if they ever gain a caller; audit R2 #7).
// The Java side orders its enable() reset before the volatile `active`
// publication, but disable() does not join an in-flight frame tick — a fast
// disable→enable can still reach resetMOG2 while a straggler apply()
// (~20-30ms) is executing on the engine thread. Reassigning a cv::Ptr
// concurrently with a method call on the pointee is a use-after-free.
// Uncontended in steady state (one lock per 500ms sample); only ever
// contended for one apply() duration during a re-arm.
#include <mutex>
static std::mutex g_mog2_mutex;

// Confine OpenCV's internal parallel_for_ to the calling thread.
// opencv-mobile is linked with OpenMP and by default fans apply() /
// morphologyEx out onto default-priority workers across all cores —
// including the cluster the encoder threads live on — contradicting the
// engine's nice-gradient isolation strategy (audit R2-perf#2). Single-
// threaded also makes the documented ~20-30ms apply() estimate the actual
// behavior. Only ever called under g_mog2_mutex (both init paths), so the
// guard flag needs no atomics.
static void confineOpenCvToCallingThread() {
    static bool s_cvThreadsConfined = false;
    if (!s_cvThreadsConfined) {
        cv::setNumThreads(1);
        s_cvThreadsConfined = true;
    }
}

/**
 * Initialize MOG2 background subtractor.
 */
static void initMog2() {
    if (!g_mog2_initialized) {
        confineOpenCvToCallingThread();
        // Parameters tuned for surveillance:
        // - history=100: Learn background over 100 frames (~50 seconds at 2 FPS)
        // - varThreshold=8: Lower = more sensitive (default is 16)
        // - detectShadows=false: Shadows cause false positives
        g_mog2 = cv::createBackgroundSubtractorMOG2(100, 8, false);
        g_mog2_initialized = true;
        LOGD("MOG2 initialized (history=100, varThreshold=8, shadows=off)");
    }
}

/**
 * Check if detected object overlaps with motion pixels.
 * Returns overlap ratio (0.0-1.0).
 */
static float checkMotionOverlap(int x, int y, int w, int h, int frameW, int frameH) {
    // g_lastMotionMask is released by resetMOG2 under g_mog2_mutex; hold the
    // same lock for the whole read (dead path today — audit R2 #7 coverage).
    std::lock_guard<std::mutex> lock(g_mog2_mutex);
    if (g_lastMotionMask.empty() || g_motionMaskWidth == 0) {
        return 1.0f;  // No motion mask, assume overlap
    }
    
    // Scale coordinates from detection frame to motion mask
    float scaleX = (float)g_motionMaskWidth / frameW;
    float scaleY = (float)g_motionMaskHeight / frameH;
    
    int mx = (int)(x * scaleX);
    int my = (int)(y * scaleY);
    int mw = (int)(w * scaleX);
    int mh = (int)(h * scaleY);
    
    // Clamp to mask bounds
    mx = std::max(0, std::min(mx, g_motionMaskWidth - 1));
    my = std::max(0, std::min(my, g_motionMaskHeight - 1));
    int mx2 = std::min(mx + mw, g_motionMaskWidth);
    int my2 = std::min(my + mh, g_motionMaskHeight);
    
    // Count motion pixels in bounding box
    int motionPixels = 0;
    int totalPixels = 0;
    
    for (int py = my; py < my2; py++) {
        const uint8_t* row = g_lastMotionMask.ptr<uint8_t>(py);
        for (int px = mx; px < mx2; px++) {
            totalPixels++;
            if (row[px] > 127) {
                motionPixels++;
            }
        }
    }
    
    if (totalPixels == 0) return 0.0f;
    return (float)motionPixels / totalPixels;
}
#endif

/**
 * Check if MOG2 is available.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_isMog2Available(
    JNIEnv* env, jclass clazz) {
    #if HAVE_OPENCV
        return JNI_TRUE;
    #else
        return JNI_FALSE;
    #endif
}

/**
 * Reset the MOG2 background model (fresh sentry session).
 *
 * g_mog2 is process-global and survives surveillance arm/disarm cycles; a
 * stale background from the PREVIOUS parking spot would classify the entire
 * new scene as foreground for the whole learning window. The Java engine
 * calls this from enable() so every session starts from a clean model
 * (invariant I2 — every latch/state that can influence a trigger resets in
 * enable()).
 */
// (g_mog2_mutex + its doc live with the other MOG2 globals above — moved in
// audit R2 #7 so the legacy checkMotionOverlap/computeMOG2 paths, which are
// declared before this point, can take the same lock.)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_resetMOG2(
    JNIEnv* env, jclass clazz) {
#if HAVE_OPENCV
    try {
        std::lock_guard<std::mutex> lock(g_mog2_mutex);
        confineOpenCvToCallingThread();
        g_mog2 = cv::createBackgroundSubtractorMOG2(100, 8, false);
        g_mog2_initialized = true;
        g_mog2_released = false;  // new session re-arms the channel (audit R11-6)
        g_lastMotionMask.release();
        return JNI_TRUE;
    } catch (...) {
        // An exception across the JNI boundary would abort the daemon;
        // a failed reset just means the channel re-arms on stale state,
        // which the Java-side warmup gate then re-learns through.
        return JNI_FALSE;
    }
#else
    return JNI_FALSE;
#endif
}

/**
 * Release the MOG2 background model + legacy overlap mask (disarm teardown,
 * audit R3b Ext-14). The model is ~18.7MB of native heap (bgmodel = 307,200
 * px × 5 mixtures × 3 floats + used-modes map) that used to stay resident
 * across disarm — i.e. for the driving majority of runtime — and bought
 * nothing: enable() reseeds via resetMOG2() anyway. g_mog2_mutex covers
 * teardown vs a straggler apply() (same discipline as resetMOG2).
 */
extern "C" JNIEXPORT void JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_releaseMOG2(
    JNIEnv* env, jclass clazz) {
#if HAVE_OPENCV
    try {
        std::lock_guard<std::mutex> lock(g_mog2_mutex);
        g_mog2.release();
        g_mog2_initialized = false;
        g_mog2_released = true;  // refuse lazy re-init until resetMOG2 (audit R11-6)
        g_lastMotionMask.release();
        g_motionMaskWidth = 0;
        g_motionMaskHeight = 0;
    } catch (...) {
        // Never throw across the JNI boundary; a failed release just leaves
        // the model resident until the next resetMOG2 replaces it.
    }
#endif
}

/**
 * MOG2 background subtraction over the FULL 2x2 mosaic with per-quadrant
 * foreground fractions.
 *
 * One g_mog2->apply() per call (g_mog2 is a single process-global model —
 * feeding quadrants separately would corrupt its learning; the mosaic layout
 * is stable so one whole-frame model is correct). Same grayscale + open/close
 * morphology as the legacy computeMOG2 above. Deliberately does NOT touch
 * g_lastMotionMask (no side effects on the legacy overlap path).
 *
 * Quadrant tiling matches SurveillanceEngineGpu.cropFromMosaic exactly:
 * quadrant q occupies (x = (q%2)*w/2, y = (q/2)*h/2), i.e. Q0=TL front,
 * Q1=TR right, Q2=BL rear, Q3=BR left.
 *
 * @param learningRate  OpenCV semantics: -1 = automatic (1/history);
 *                      1.0 = reseed the background entirely from this frame
 *                      (used for the first frame of a session).
 * @param outQuadrantFractions  float[4] out: per-quadrant foreground pixel
 *                      fraction in [0,1], indexed as above.
 * @return whole-frame foreground fraction in [0,1], or -1 on error /
 *         OpenCV absent (caller treats <0 as "channel unavailable" and the
 *         adding-only Java channel fails CLOSED — invariant I9).
 */
extern "C" JNIEXPORT jfloat JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeMOG2Quadrants(
    JNIEnv* env, jclass clazz,
    jobject rgbBuffer, jint width, jint height,
    jfloat learningRate, jfloatArray outQuadrantFractions) {
#if HAVE_OPENCV
    // try/catch(...) is load-bearing: this is the first LIVE OpenCV path in
    // this file (the legacy computeMOG2 has no Java caller), and an escaped
    // cv::Exception / std::bad_alloc through the extern "C" JNI boundary is
    // std::terminate — the daemon aborts while parked. The Java caller's
    // catch(Throwable) cannot intercept a native abort; returning -1 keeps
    // the documented "negative ⇒ channel fails closed" contract (I9).
    try {
        uint8_t* rgbData = (uint8_t*)env->GetDirectBufferAddress(rgbBuffer);
        if (rgbData == nullptr || width <= 1 || height <= 1) {
            return -1.0f;
        }
        cv::Mat rgbMat(height, width, CV_8UC3, rgbData);
        cv::Mat grayMat;
        cv::cvtColor(rgbMat, grayMat, cv::COLOR_RGB2GRAY);

        cv::Mat fgMask;
        {
            // Guarded region: model creation + apply(). See g_mog2_mutex doc.
            std::lock_guard<std::mutex> lock(g_mog2_mutex);
            // Post-release straggler (audit R11-6 / ExtD-6): the model was
            // explicitly torn down by disarm; do NOT lazily re-create ~18.7MB
            // that would then sit resident for the whole drive. Fail closed
            // (-1) like the OpenCV-absent case; the next resetMOG2 re-arms.
            if (g_mog2_released) {
                return -1.0f;
            }
            if (!g_mog2_initialized) {
                initMog2();
            }
            g_mog2->apply(grayMat, fgMask, learningRate);
        }

        // Identical 3x3 structuring element on every frame. Share its
        // immutable storage instead of allocating the kernel each call.
        static const cv::Mat kernel = cv::getStructuringElement(
                cv::MORPH_ELLIPSE, cv::Size(3, 3));
        cv::morphologyEx(fgMask, fgMask, cv::MORPH_OPEN, kernel);   // remove small noise
        cv::morphologyEx(fgMask, fgMask, cv::MORPH_CLOSE, kernel);  // fill small holes

        int foregroundPixels = -1;
        if (outQuadrantFractions != nullptr
                && env->GetArrayLength(outQuadrantFractions) >= 4) {
            jfloat fr[4];
            int qw = width / 2, qh = height / 2;
            int quadrantSum = 0;
            for (int q = 0; q < 4; q++) {
                cv::Rect r((q % 2) * qw, (q / 2) * qh, qw, qh);
                int quadrantPixels = cv::countNonZero(fgMask(r));
                fr[q] = (float)quadrantPixels / (float)(qw * qh);
                quadrantSum += quadrantPixels;
            }
            env->SetFloatArrayRegion(outQuadrantFractions, 0, 4, fr);
            // Even dimensions tile the whole mask exactly. Avoid scanning
            // all pixels again after the four quadrant countNonZero calls.
            // Odd dimensions leave a border, so retain the full-mask scan.
            if ((width & 1) == 0 && (height & 1) == 0) {
                foregroundPixels = quadrantSum;
            }
        }
        if (foregroundPixels < 0) {
            foregroundPixels = cv::countNonZero(fgMask);
        }
        return (float)foregroundPixels / (float)(width * height);
    } catch (...) {
        return -1.0f;
    }
#else
    return -1.0f;
#endif
}

/**
 * Check if object detection is available.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_isObjectDetectionAvailable(
    JNIEnv* env, jclass clazz) {
    #if HAVE_NCNN
        // Check if YOLO model files exist in assets
        return JNI_TRUE;
    #else
        return JNI_FALSE;
    #endif
}

/**
 * Compute motion using MOG2 background subtraction.
 * Returns motion mask via output parameter for overlap checking.
 */
extern "C" JNIEXPORT jfloat JNICALL
Java_com_overdrive_app_surveillance_NativeMotion_computeMOG2(
    JNIEnv* env, jclass clazz,
    jobject rgbBuffer, jint width, jint height,
    jbyteArray roiMask, jfloat learningRate) {
    
#if HAVE_OPENCV
    // Get RGB data
    uint8_t* rgbData = (uint8_t*)env->GetDirectBufferAddress(rgbBuffer);
    if (rgbData == nullptr) {
        LOGE("Invalid RGB buffer");
        return -1.0f;
    }
    
    // Convert RGB to grayscale (MOG2 works on grayscale)
    cv::Mat rgbMat(height, width, CV_8UC3, rgbData);
    cv::Mat grayMat;
    cv::cvtColor(rgbMat, grayMat, cv::COLOR_RGB2GRAY);
    
    // Apply MOG2
    cv::Mat fgMask;
    {
        // Guarded region: model init + apply() — same discipline as
        // computeMOG2Quadrants (audit R2 #7: this legacy path touched
        // g_mog2 unlocked, the exact UAF class g_mog2_mutex closes).
        std::lock_guard<std::mutex> lock(g_mog2_mutex);
        if (!g_mog2_initialized) {
            initMog2();
        }
        g_mog2->apply(grayMat, fgMask, learningRate);
    }
    
    // Apply ROI mask if provided
    if (roiMask != nullptr) {
        jbyte* maskData = env->GetByteArrayElements(roiMask, nullptr);
        if (maskData != nullptr) {
            cv::Mat roiMat(height, width, CV_8UC1, (uint8_t*)maskData);
            cv::bitwise_and(fgMask, roiMat, fgMask);
            env->ReleaseByteArrayElements(roiMask, maskData, JNI_ABORT);
        }
    }
    
    // Apply morphological operations to reduce noise
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3));
    cv::morphologyEx(fgMask, fgMask, cv::MORPH_OPEN, kernel);  // Remove small noise
    cv::morphologyEx(fgMask, fgMask, cv::MORPH_CLOSE, kernel); // Fill small holes
    
    // Store motion mask globally for overlap checking (locked: resetMOG2
    // releases this mask under g_mog2_mutex — audit R2 #7).
    {
        std::lock_guard<std::mutex> lock(g_mog2_mutex);
        g_lastMotionMask = fgMask.clone();
        g_motionMaskWidth = width;
        g_motionMaskHeight = height;
    }
    
    // Count foreground pixels
    int foregroundPixels = cv::countNonZero(fgMask);
    int totalPixels = width * height;
    
    // Normalize to 0.0-1.0
    float motionScore = (float)foregroundPixels / totalPixels;
    
    return motionScore;
    
#else
    LOGE("MOG2 not available - OpenCV not compiled");
    return -1.0f;
#endif
}

// This function is no longer used - detection happens in Java layer
