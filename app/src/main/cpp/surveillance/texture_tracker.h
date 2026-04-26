/**
 * TextureTracker — Lightweight Visual Object Tracker for Edge Surveillance
 *
 * Implements the "Decoupled Classification and Tracking" pattern:
 *   1. YOLO provides the initial bounding box + class label (runs once)
 *   2. This tracker memorizes the pixel texture inside that box
 *   3. Frame-by-frame, it finds the best match using normalized cross-correlation
 *   4. YOLO wakes up every N seconds as a "heartbeat" to verify the track
 *
 * Why NCC template matching instead of KCF:
 *   - opencv-mobile doesn't include the contrib tracking module (no KCF/CSRT)
 *   - cv::matchTemplate with TM_CCOEFF_NORMED is in imgproc (available)
 *   - On ARM64 with NEON, NCC runs in <2ms for a 64x64 template in a 160x160 search window
 *   - Handles stationary objects perfectly (the template doesn't change)
 *
 * Architecture:
 *   - Up to 4 concurrent tracks (one per quadrant)
 *   - Each track stores a grayscale template (the object's appearance)
 *   - Search window is centered on last known position, expanded by a margin
 *   - Confidence = peak NCC value (0.0 to 1.0)
 *   - Track is dropped when confidence falls below threshold for N frames
 *
 * Memory: ~40KB per track (64x64 template + 160x160 search region)
 * CPU: ~1-2ms per track per frame on Snapdragon 665
 */

#ifndef TEXTURE_TRACKER_H
#define TEXTURE_TRACKER_H

#include <cstdint>

#define TRACKER_MAX_TRACKS      4
#define TRACKER_TEMPLATE_SIZE   64    // Template is resized to 64x64 grayscale
#define TRACKER_SEARCH_MARGIN   48    // Search window extends 48px beyond last bbox
#define TRACKER_MIN_CONFIDENCE  0.20f // Below this, track is "lost" (lowered for 320×240 mosaic)
#define TRACKER_LOST_FRAMES_MAX 20    // Drop track after 20 consecutive lost frames (~2 sec at 10 FPS)
#define TRACKER_MAX_DURATION_MS 180000 // 3 minutes hard kill (fire hydrant failsafe)

struct TrackedObject {
    bool active;
    int classId;
    int quadrant;
    
    // Bounding box in quadrant pixel coords (320x240 space)
    int x, y, w, h;
    
    // Template (grayscale, resized to TRACKER_TEMPLATE_SIZE x TRACKER_TEMPLATE_SIZE)
    uint8_t templateData[TRACKER_TEMPLATE_SIZE * TRACKER_TEMPLATE_SIZE];
    
    // Tracking state
    float lastConfidence;
    int lostFrameCount;       // Consecutive frames where confidence < threshold
    long long startTimeMs;    // When this track was created (for max duration failsafe)
    long long lastUpdateMs;   // Last successful update
    
    // YOLO heartbeat
    bool needsYoloVerification;  // Set true every N seconds
    long long lastYoloTimeMs;    // When YOLO last verified this track
};

struct TrackerState {
    TrackedObject tracks[TRACKER_MAX_TRACKS];
    bool initialized;
};

/**
 * Initialize the tracker state.
 */
void tracker_init(TrackerState* state);

/**
 * Start tracking an object detected by YOLO.
 *
 * @param state     Tracker state
 * @param frame     Grayscale quadrant image (320x240)
 * @param width     Frame width (320)
 * @param height    Frame height (240)
 * @param quadrant  Quadrant index (0-3)
 * @param classId   COCO class ID from YOLO
 * @param x,y,w,h   Bounding box in quadrant pixel coords
 * @param nowMs     Current time in milliseconds
 * @return Track index (0-3) or -1 if no slot available
 */
int tracker_startTrack(
    TrackerState* state,
    const uint8_t* frame, int width, int height,
    int quadrant, int classId,
    int x, int y, int w, int h,
    long long nowMs
);

/**
 * Update all active tracks on a new frame.
 *
 * @param state     Tracker state
 * @param frame     Grayscale quadrant image (320x240)
 * @param width     Frame width (320)
 * @param height    Frame height (240)
 * @param quadrant  Which quadrant this frame is from (0-3)
 * @param nowMs     Current time in milliseconds
 */
void tracker_update(
    TrackerState* state,
    const uint8_t* frame, int width, int height,
    int quadrant,
    long long nowMs
);

/**
 * Check if any track in the given quadrant is still active.
 * Used to keep recording alive even when MotionPipelineV2 drops to zero.
 */
bool tracker_hasActiveTrack(const TrackerState* state, int quadrant);

/**
 * Get the bounding box of the active track in a quadrant.
 * Returns false if no active track.
 */
bool tracker_getTrackBox(const TrackerState* state, int quadrant,
                         int* outX, int* outY, int* outW, int* outH,
                         float* outConfidence, int* outClassId);

/**
 * Drop a specific track (e.g., when YOLO heartbeat says the box is empty).
 */
void tracker_dropTrack(TrackerState* state, int quadrant);

/**
 * Check if a track needs YOLO verification (heartbeat).
 */
bool tracker_needsYoloHeartbeat(const TrackerState* state, int quadrant);

/**
 * Confirm a track via YOLO heartbeat (resets the verification timer).
 */
void tracker_confirmHeartbeat(TrackerState* state, int quadrant, long long nowMs);

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
    long long nowMs
);

#endif // TEXTURE_TRACKER_H
