#include "texture_tracker.h"
#include <cstring>
#include <cmath>
#include <algorithm>

// OpenCV for template matching (available in opencv-mobile imgproc)
// The build system defines HAVE_OPENCV=1 via CMake, and opencv_modules.hpp
// defines HAVE_OPENCV_IMGPROC (without a value). Use #ifdef, not #if.
#ifdef HAVE_OPENCV
#include <opencv2/imgproc.hpp>
#include <opencv2/core.hpp>
#define USE_OPENCV_IMGPROC
#endif

// YOLO heartbeat interval: verify track every 3 seconds
static const long long YOLO_HEARTBEAT_INTERVAL_MS = 3000;

// NCC confidence floor: below this, wake YOLO for re-identification.
// This handles scale variance — as a person walks toward the car, the template
// becomes stale and NCC drops. At 0.40, YOLO re-extracts a fresh template.
// Lowered from 0.60 because at 320×240 mosaic resolution, NCC scores of 0.4-0.5
// are normal for a valid match on a small person (~25×60 pixels).
static const float NCC_YOLO_WAKEUP_THRESHOLD = 0.40f;

// Inner core shrink factor: shrink YOLO bbox by 15% on each side before
// extracting the template. This removes background pixels (asphalt, grass)
// that would drag the NCC score down and cause false drift.
static const float INNER_CORE_SHRINK = 0.15f;

// Maximum movement per frame at 10 FPS: a person running at 3 m/s covers
// ~0.3m per frame. At 5m distance on a 320px-wide quadrant, that's ~20px.
// We use 40px to be safe (accounts for closer distances).
static const int MAX_MOVEMENT_PER_FRAME = 40;

// ============================================================================
// Utility: RGB to grayscale conversion (fast integer approximation)
// ============================================================================

static void rgbToGray(const uint8_t* rgb, uint8_t* gray, int width, int height) {
    int total = width * height;
    for (int i = 0; i < total; i++) {
        int r = rgb[i * 3];
        int g = rgb[i * 3 + 1];
        int b = rgb[i * 3 + 2];
        gray[i] = (uint8_t)((r + (g << 1) + b) >> 2);
    }
}

// ============================================================================
// Inner Core Template Extraction
//
// Shrinks the YOLO bbox by INNER_CORE_SHRINK on all sides, then resizes the
// inner region to TRACKER_TEMPLATE_SIZE x TRACKER_TEMPLATE_SIZE.
// This ensures the template contains purely the object's texture (clothing,
// body) without background contamination from the bbox edges.
// ============================================================================

static void extractInnerCoreTemplate(
    const uint8_t* gray, int width, int height,
    int x, int y, int w, int h,
    uint8_t* templateOut)
{
    // Shrink bbox by 15% on each side
    int shrinkX = (int)(w * INNER_CORE_SHRINK);
    int shrinkY = (int)(h * INNER_CORE_SHRINK);
    int innerX = x + shrinkX;
    int innerY = y + shrinkY;
    int innerW = w - 2 * shrinkX;
    int innerH = h - 2 * shrinkY;

    // Clamp to frame bounds
    int x1 = std::max(0, innerX);
    int y1 = std::max(0, innerY);
    int x2 = std::min(width, innerX + innerW);
    int y2 = std::min(height, innerY + innerH);
    int bw = x2 - x1;
    int bh = y2 - y1;

    if (bw < 4 || bh < 4) {
        // Inner core too small — fall back to full bbox
        x1 = std::max(0, x);
        y1 = std::max(0, y);
        x2 = std::min(width, x + w);
        y2 = std::min(height, y + h);
        bw = x2 - x1;
        bh = y2 - y1;
    }

    if (bw <= 0 || bh <= 0) {
        memset(templateOut, 128, TRACKER_TEMPLATE_SIZE * TRACKER_TEMPLATE_SIZE);
        return;
    }

#ifdef USE_OPENCV_IMGPROC
    cv::Mat src(height, width, CV_8UC1, const_cast<uint8_t*>(gray));
    cv::Mat roi = src(cv::Rect(x1, y1, bw, bh));
    cv::Mat dst(TRACKER_TEMPLATE_SIZE, TRACKER_TEMPLATE_SIZE, CV_8UC1, templateOut);
    cv::resize(roi, dst, dst.size(), 0, 0, cv::INTER_LINEAR);
#else
    for (int ty = 0; ty < TRACKER_TEMPLATE_SIZE; ty++) {
        int sy = y1 + (ty * bh) / TRACKER_TEMPLATE_SIZE;
        for (int tx = 0; tx < TRACKER_TEMPLATE_SIZE; tx++) {
            int sx = x1 + (tx * bw) / TRACKER_TEMPLATE_SIZE;
            templateOut[ty * TRACKER_TEMPLATE_SIZE + tx] = gray[sy * width + sx];
        }
    }
#endif
}

// ============================================================================
// Micro-ROI NCC Template Matching
//
// Instead of searching the entire frame, we define a tight search window
// around the last known position. At 10 FPS, a person moves at most ~40px
// per frame. The search ROI is [lastBbox expanded by MAX_MOVEMENT_PER_FRAME].
//
// The search region is resized so the object appears at template scale,
// then cv::matchTemplate finds the peak NCC score.
// ============================================================================

#ifdef USE_OPENCV_IMGPROC
static float matchInMicroRoi(
    const uint8_t* gray, int width, int height,
    const uint8_t* tmpl,
    int lastX, int lastY, int lastW, int lastH,
    int* outX, int* outY)
{
    // Micro-ROI: last bbox expanded by MAX_MOVEMENT_PER_FRAME on all sides
    int roiX1 = std::max(0, lastX - MAX_MOVEMENT_PER_FRAME);
    int roiY1 = std::max(0, lastY - MAX_MOVEMENT_PER_FRAME);
    int roiX2 = std::min(width, lastX + lastW + MAX_MOVEMENT_PER_FRAME);
    int roiY2 = std::min(height, lastY + lastH + MAX_MOVEMENT_PER_FRAME);
    int roiW = roiX2 - roiX1;
    int roiH = roiY2 - roiY1;

    // ROI must be larger than template after scaling
    if (roiW < lastW || roiH < lastH || lastW < 4 || lastH < 4) {
        *outX = lastX;
        *outY = lastY;
        return 0.0f;
    }

    cv::Mat frameMat(height, width, CV_8UC1, const_cast<uint8_t*>(gray));
    cv::Mat roi = frameMat(cv::Rect(roiX1, roiY1, roiW, roiH));

    // Scale the ROI so the object appears at TRACKER_TEMPLATE_SIZE.
    // This normalizes for the object's current size in the frame.
    float scaleX = (float)TRACKER_TEMPLATE_SIZE / lastW;
    float scaleY = (float)TRACKER_TEMPLATE_SIZE / lastH;
    float scale = std::min(scaleX, scaleY);

    // Clamp scale to prevent absurd resize (object too small or too large)
    scale = std::max(0.1f, std::min(scale, 4.0f));

    int scaledW = (int)(roiW * scale);
    int scaledH = (int)(roiH * scale);

    if (scaledW <= TRACKER_TEMPLATE_SIZE + 2 || scaledH <= TRACKER_TEMPLATE_SIZE + 2) {
        *outX = lastX;
        *outY = lastY;
        return 0.0f;
    }

    cv::Mat scaledRoi;
    cv::resize(roi, scaledRoi, cv::Size(scaledW, scaledH), 0, 0, cv::INTER_LINEAR);

    cv::Mat tmplMat(TRACKER_TEMPLATE_SIZE, TRACKER_TEMPLATE_SIZE, CV_8UC1,
                    const_cast<uint8_t*>(tmpl));

    // NCC template matching
    cv::Mat result;
    cv::matchTemplate(scaledRoi, tmplMat, result, cv::TM_CCOEFF_NORMED);

    // Find peak
    double minVal, maxVal;
    cv::Point minLoc, maxLoc;
    cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);

    // Map peak location back to original frame coordinates
    *outX = roiX1 + (int)(maxLoc.x / scale);
    *outY = roiY1 + (int)(maxLoc.y / scale);

    return (float)maxVal;
}
#endif

// ============================================================================
// Public API
// ============================================================================

void tracker_init(TrackerState* state) {
    memset(state, 0, sizeof(TrackerState));
    state->initialized = true;
}

int tracker_startTrack(
    TrackerState* state,
    const uint8_t* frame, int width, int height,
    int quadrant, int classId,
    int x, int y, int w, int h,
    long long nowMs)
{
    if (!state->initialized) return -1;

    // Find a free slot (prefer same quadrant to replace stale track)
    int slot = -1;
    for (int i = 0; i < TRACKER_MAX_TRACKS; i++) {
        if (!state->tracks[i].active) {
            slot = i;
            break;
        }
        if (state->tracks[i].quadrant == quadrant) {
            slot = i;
            break;
        }
    }

    if (slot < 0) {
        // Evict oldest track
        long long oldest = 0x7FFFFFFFFFFFFFFFLL;
        for (int i = 0; i < TRACKER_MAX_TRACKS; i++) {
            if (state->tracks[i].lastUpdateMs < oldest) {
                oldest = state->tracks[i].lastUpdateMs;
                slot = i;
            }
        }
    }

    if (slot < 0) return -1;

    TrackedObject& t = state->tracks[slot];
    t.active = true;
    t.classId = classId;
    t.quadrant = quadrant;
    t.x = x; t.y = y; t.w = w; t.h = h;
    t.lastConfidence = 1.0f;
    t.lostFrameCount = 0;
    t.startTimeMs = nowMs;
    t.lastUpdateMs = nowMs;
    t.needsYoloVerification = false;
    t.lastYoloTimeMs = nowMs;

    // Convert frame to grayscale and extract inner core template
    uint8_t grayBuf[320 * 240];
    if (width * height <= 320 * 240) {
        rgbToGray(frame, grayBuf, width, height);
        extractInnerCoreTemplate(grayBuf, width, height, x, y, w, h, t.templateData);
    }

    return slot;
}

void tracker_update(
    TrackerState* state,
    const uint8_t* frame, int width, int height,
    int quadrant,
    long long nowMs)
{
    if (!state->initialized) return;

#ifdef USE_OPENCV_IMGPROC
    uint8_t grayBuf[320 * 240];
    if (width * height > 320 * 240) return;
    rgbToGray(frame, grayBuf, width, height);

    for (int i = 0; i < TRACKER_MAX_TRACKS; i++) {
        TrackedObject& t = state->tracks[i];
        if (!t.active || t.quadrant != quadrant) continue;

        // Maximum duration failsafe (fire hydrant problem: 3 minutes)
        if ((nowMs - t.startTimeMs) > TRACKER_MAX_DURATION_MS) {
            t.active = false;
            continue;
        }

        // Time-based YOLO heartbeat: every 3 seconds
        if ((nowMs - t.lastYoloTimeMs) > YOLO_HEARTBEAT_INTERVAL_MS) {
            t.needsYoloVerification = true;
        }

        // Run micro-ROI NCC template matching
        int newX, newY;
        float confidence = matchInMicroRoi(
            grayBuf, width, height,
            t.templateData,
            t.x, t.y, t.w, t.h,
            &newX, &newY);

        t.lastConfidence = confidence;

        if (confidence >= TRACKER_MIN_CONFIDENCE) {
            // DRIFT ANCHOR: Reject NCC position updates that teleport the bbox
            // beyond what's physically possible. At 10 FPS, a person running at
            // 3 m/s moves ~40px per frame. If NCC reports a jump > MAX_MOVEMENT_PER_FRAME,
            // the tracker has locked onto background texture and is drifting.
            // Hold the bbox at its last known position and force a YOLO heartbeat.
            int dx = std::abs(newX - t.x);
            int dy = std::abs(newY - t.y);
            if (dx > MAX_MOVEMENT_PER_FRAME || dy > MAX_MOVEMENT_PER_FRAME) {
                // Impossible movement — tracker is drifting. Don't update position.
                // Force YOLO heartbeat to re-verify or kill the track.
                t.needsYoloVerification = true;
                t.lostFrameCount++;
                if (t.lostFrameCount >= TRACKER_LOST_FRAMES_MAX) {
                    t.active = false;
                }
            } else {
                t.x = newX;
                t.y = newY;
                t.lostFrameCount = 0;
                t.lastUpdateMs = nowMs;

                // NCC-triggered YOLO wakeup: if confidence drops below 0.60,
                // the template is going stale (scale change, partial occlusion).
                // Wake YOLO to re-identify and refresh the template.
                if (confidence < NCC_YOLO_WAKEUP_THRESHOLD) {
                    t.needsYoloVerification = true;
                }
            }
        } else {
            t.lostFrameCount++;
            if (t.lostFrameCount >= TRACKER_LOST_FRAMES_MAX) {
                t.active = false;
            }
        }
    }
#endif
}

bool tracker_hasActiveTrack(const TrackerState* state, int quadrant) {
    if (!state->initialized) return false;
    for (int i = 0; i < TRACKER_MAX_TRACKS; i++) {
        if (state->tracks[i].active && state->tracks[i].quadrant == quadrant) {
            return true;
        }
    }
    return false;
}

bool tracker_getTrackBox(const TrackerState* state, int quadrant,
                         int* outX, int* outY, int* outW, int* outH,
                         float* outConfidence, int* outClassId)
{
    if (!state->initialized) return false;
    for (int i = 0; i < TRACKER_MAX_TRACKS; i++) {
        const TrackedObject& t = state->tracks[i];
        if (t.active && t.quadrant == quadrant) {
            *outX = t.x; *outY = t.y; *outW = t.w; *outH = t.h;
            *outConfidence = t.lastConfidence;
            *outClassId = t.classId;
            return true;
        }
    }
    return false;
}

void tracker_dropTrack(TrackerState* state, int quadrant) {
    if (!state->initialized) return;
    for (int i = 0; i < TRACKER_MAX_TRACKS; i++) {
        if (state->tracks[i].active && state->tracks[i].quadrant == quadrant) {
            state->tracks[i].active = false;
        }
    }
}

bool tracker_needsYoloHeartbeat(const TrackerState* state, int quadrant) {
    if (!state->initialized) return false;
    for (int i = 0; i < TRACKER_MAX_TRACKS; i++) {
        if (state->tracks[i].active && state->tracks[i].quadrant == quadrant) {
            return state->tracks[i].needsYoloVerification;
        }
    }
    return false;
}

void tracker_confirmHeartbeat(TrackerState* state, int quadrant, long long nowMs) {
    if (!state->initialized) return;
    for (int i = 0; i < TRACKER_MAX_TRACKS; i++) {
        TrackedObject& t = state->tracks[i];
        if (t.active && t.quadrant == quadrant) {
            t.needsYoloVerification = false;
            t.lastYoloTimeMs = nowMs;
        }
    }
}

/**
 * Refresh a track's template with a new YOLO bounding box.
 * Called when the YOLO heartbeat re-identifies the object at a new scale.
 * Extracts a fresh inner core template from the current frame.
 */
void tracker_refreshTemplate(
    TrackerState* state,
    const uint8_t* frame, int width, int height,
    int quadrant,
    int newX, int newY, int newW, int newH,
    long long nowMs)
{
    if (!state->initialized) return;

    for (int i = 0; i < TRACKER_MAX_TRACKS; i++) {
        TrackedObject& t = state->tracks[i];
        if (t.active && t.quadrant == quadrant) {
            // Update bbox to YOLO's new measurement
            t.x = newX; t.y = newY; t.w = newW; t.h = newH;
            t.lastConfidence = 1.0f;
            t.lostFrameCount = 0;
            t.lastUpdateMs = nowMs;
            t.needsYoloVerification = false;
            t.lastYoloTimeMs = nowMs;

            // Re-extract inner core template at the new scale
            uint8_t grayBuf[320 * 240];
            if (width * height <= 320 * 240) {
                rgbToGray(frame, grayBuf, width, height);
                extractInnerCoreTemplate(grayBuf, width, height,
                                         newX, newY, newW, newH, t.templateData);
            }
            break;
        }
    }
}
