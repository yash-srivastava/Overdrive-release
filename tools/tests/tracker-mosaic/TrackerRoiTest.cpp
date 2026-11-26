// Build with HAVE_OPENCV=1 and the same OpenCV imgproc/core libraries as
// texture_tracker.cpp. The two-track case deliberately exercises the original
// full-quadrant grayscale path as the reference for the single-track path.
#include "texture_tracker.h"

#include <cassert>
#include <cstdio>
#include <random>
#include <vector>

struct Box {
    int x, y, w, h;
};

static void checkTrack(const TrackedObject& a, const TrackedObject& b) {
    assert(a.active == b.active);
    assert(a.classId == b.classId && a.quadrant == b.quadrant);
    assert(a.x == b.x && a.y == b.y && a.w == b.w && a.h == b.h);
    assert(a.lastConfidence == b.lastConfidence);
    assert(a.lostFrameCount == b.lostFrameCount);
    assert(a.startTimeMs == b.startTimeMs && a.lastUpdateMs == b.lastUpdateMs);
    assert(a.needsYoloVerification == b.needsYoloVerification);
    assert(a.lastYoloTimeMs == b.lastYoloTimeMs);
    for (int i = 0; i < TRACKER_TEMPLATE_SIZE * TRACKER_TEMPLATE_SIZE; ++i) {
        assert(a.templateData[i] == b.templateData[i]);
    }
}

static void check(const std::vector<uint8_t>& packed, const uint8_t* frame,
                  int stride, const Box& box, bool expireOther) {
    constexpr int W = 320, H = 240;
    TrackerState single, shared;
    tracker_init(&single);
    const long long start = 1000;
    int slot = tracker_startTrack(&single, packed.data(), W, H, 0, 0,
                                  box.x, box.y, box.w, box.h, start);
    assert(slot == 0);
    shared = single;
    // A second matching track forces the shared full-gray path. Refresh it
    // before each update so dropping the second track cannot change the path.
    // The optional expired case instead checks that the count excludes tracks
    // that would not consume the converted image.
    for (int step = 0; step < 6; ++step) {
        const long long now = start + 100 + step * 700;
        if (single.tracks[0].active) {
            shared.tracks[1] = shared.tracks[0];
            if (expireOther) {
                shared.tracks[1].startTimeMs = now - TRACKER_MAX_DURATION_MS - 1;
            }
        }
        tracker_update(&single, frame, W, H, 0, now, stride);
        tracker_update(&shared, frame, W, H, 0, now, stride);
        checkTrack(single.tracks[0], shared.tracks[0]);
        if (expireOther) assert(!shared.tracks[1].active);
    }
    // All six updates must actually reach the active tracking loop; this
    // includes zero-confidence cases (the lost-frame limit exceeds six).
    assert(single.tracks[0].active);
    assert(single.tracks[0].needsYoloVerification);
}

int main() {
    constexpr int W = 320, H = 240;
    std::mt19937 rng(271);
    std::vector<uint8_t> packed(W * H * 3);
    for (auto& value : packed) value = rng() & 255;
    const Box boxes[] = {
        {90, 65, 25, 60},  // narrow object, odd ROI width (SIMD tail)
        {0, 0, 25, 60},    // clipped at top and left
        {295, 180, 25, 60},// clipped at bottom and right
        {135, 96, 4, 4},   // maximum resize factor
        {120, 100, 3, 3},  // geometry rejection before conversion
        {0, 0, 320, 240},  // scaled-size rejection
        {0, 30, 320, 100}, // full-width ROI
    };
    for (int padding : {0, 7, 960}) {
        int stride = W * 3 + padding;
        // Offset the start by one byte and omit padding after the last row.
        std::vector<uint8_t> source(1 + (H - 1) * stride + W * 3, 0xA5);
        uint8_t* frame = source.data() + 1;
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W * 3; ++x) {
                frame[y * stride + x] = packed[y * W * 3 + x];
            }
        }
        const auto before = source;
        for (const Box& box : boxes) {
            check(packed, frame, stride, box, false);
            check(packed, frame, stride, box, true);
        }
        assert(source == before);
    }
    std::puts("Tracker ROI/full-quadrant parity passed.");
}
