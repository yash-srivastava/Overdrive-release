#include "../../../app/src/main/cpp/surveillance/motion_pixel_gates.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>

namespace {
void checkRatio(motion_pixels::LumaRatioGate& gate, float threshold) {
    gate.update(threshold);
    // Exhaustive for all pairs of possible 8-bit luma values, including
    // denominators 1 and 256 and the exact threshold boundary.
    for (int a = 0; a <= 255; ++a) {
        for (int b = 0; b <= 255; ++b) {
            const int numerator = std::max(a, b);
            const int denominator = std::min(a, b) + 1;
            const bool expected = (float)numerator / denominator < threshold;
            if (gate.rejects(numerator, denominator) != expected) {
                std::cerr << "Ratio mismatch: " << a << ", " << b
                          << " threshold=" << threshold << '\n';
                std::exit(1);
            }
        }
    }
}

void checkDirection() {
    // Representative signed channel deltas, sampled densely around the
    // +/-5 boundaries and across the full byte range.
    const int deltas[] = {-255, -128, -7, -6, -5, -4, -1, 0, 1, 4, 5, 6, 7, 128, 255};
    for (int dr : deltas) {
        for (int dg : deltas) {
            for (int db : deltas) {
                const bool allDarker = dr <= 5 && dg <= 5 && db <= 5;
                const bool meaningfulDarkening = dr < -5 || dg < -5 || db < -5;
                if (motion_pixels::shadowDirection(
                        std::max(dr, 0), std::max(dg, 0), std::max(db, 0),
                        std::max(-dr, 0), std::max(-dg, 0), std::max(-db, 0))
                        != (allDarker && meaningfulDarkening)) {
                    std::cerr << "Shadow direction mismatch\n";
                    std::exit(1);
                }
            }
        }
    }
}
} // namespace

int main() {
    motion_pixels::LumaRatioGate gate{};
    for (float threshold : {0.0f, 1.0f, 1.05f, 1.15f, 1.3f, 1.3f * 0.85f,
                            1.3f * 0.88f, 2.0f, 255.0f, 256.0f}) {
        checkRatio(gate, threshold);
        checkRatio(gate, threshold); // repeated (cache hit)
    }
    // Exercise rounded thresholds immediately below, on and above exact
    // attainable ratios, including denominators that do not divide evenly.
    for (int numerator : {7, 17, 64, 127, 200, 255}) {
        for (int denominator : {1, 3, 7, 11, 31, 63, 127, 256}) {
            const float value = (float)numerator / denominator;
            checkRatio(gate, std::nextafter(value, 0.0f));
            checkRatio(gate, value);
            checkRatio(gate, std::nextafter(value, std::numeric_limits<float>::max()));
        }
    }
    gate = {}; // session reset
    checkRatio(gate, 1.05f);
    checkDirection();
    std::cout << "Motion pixel gate parity passed\n";
}
