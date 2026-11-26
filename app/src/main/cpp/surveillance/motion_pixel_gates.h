#ifndef MOTION_PIXEL_GATES_H
#define MOTION_PIXEL_GATES_H

#include <cstdint>

namespace motion_pixels {

// Per-quadrant cache of the first passing numerator for each denominator.
// Plain state: the pipeline's existing memset reset invalidates the cache.
struct LumaRatioGate {
    bool initialized;
    float threshold;
    uint16_t firstPassing[256];

    void update(float value) {
        if (initialized && threshold == value) return;
        // For a positive denominator, the original float comparison is
        // monotone in the numerator. As the denominator grows, the first
        // passing numerator never decreases for a positive threshold.
        // Each of the two indices advances at most 256 times.
        int numerator = 0;
        for (int denominator = 1; denominator <= 256; ++denominator) {
            while (numerator < 256 && (float)numerator / denominator < value) {
                ++numerator;
            }
            firstPassing[denominator - 1] = static_cast<uint16_t>(numerator);
        }
        threshold = value;
        initialized = true;
    }

    // Both inputs are derived from 8-bit luma; denominator is minLuma + 1.
    bool rejects(int numerator, int denominator) const {
        const int first = firstPassing[denominator - 1];
        // Keep the original comparison at the two boundary values. In
        // particular, do not replace a rounded division with multiplication:
        // the native build uses fast-math, and boundary rounding matters.
        if (numerator + 1 < first) return true;
        if (numerator > first) return false;
        return (float)numerator / denominator < threshold;
    }
};

// Shadows darken all channels (allowing small counter-movements), and at
// least one channel must darken meaningfully. Test this necessary condition
// before doing any floating-point chrominance work.
inline bool shadowDirection(int r1, int g1, int b1, int r2, int g2, int b2) {
    const int dr = r1 - r2;
    const int dg = g1 - g2;
    const int db = b1 - b2;
    return dr <= 5 && dg <= 5 && db <= 5 && (dr < -5 || dg < -5 || db < -5);
}

} // namespace motion_pixels

#endif
