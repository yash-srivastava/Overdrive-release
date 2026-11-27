#include "../../../app/src/main/cpp/surveillance/motion_flow_sad.h"

#include <array>
#include <cstdlib>
#include <iostream>
#include <random>
#include <utility>

namespace {
constexpr int Radius = 6;
constexpr int Width = 32 + 2 * Radius;
constexpr int Stride = Width * 3 + 7; // Include padding; the row is not tightly packed.
using Reference = std::array<uint8_t, Stride * Width>;
using Samples = std::array<uint8_t, 64>;

int luma(const uint8_t* p) {
    return (p[0] + 2 * p[1] + p[2]) >> 2;
}

// Independent exhaustive implementation (no cache, pruning or SIMD).
std::pair<int, int> exhaustive(const Samples& samples, const Reference& ref, int height) {
    int best = -1;
    std::pair<int, int> displacement{0, 0};
    for (int dy = -Radius; dy <= Radius; ++dy) {
        for (int dx = -Radius; dx <= Radius; ++dx) {
            int sad = 0;
            int i = 0;
            for (int y = 0; y < height; y += 4) {
                for (int x = 0; x < 32; x += 4) {
                    const uint8_t* p = ref.data() + (y + dy + Radius) * Stride
                            + (x + dx + Radius) * 3;
                    sad += std::abs(samples[i++] - luma(p));
                }
            }
            if (best < 0 || sad < best) {
                best = sad;
                displacement = {dx, dy};
            }
        }
    }
    return displacement;
}

void check(const Samples& samples, const Reference& ref, int height) {
    constexpr int GrayStride = Width + 5;
    std::array<uint8_t, GrayStride * Width> gray{};
    for (int y = 0; y < Width; ++y) {
        for (int x = 0; x < Width; ++x) {
            gray[y * GrayStride + x] = luma(ref.data() + y * Stride + x * 3);
        }
    }
    int dx, dy;
    motion_flow::bestDisplacement<Radius>(samples.data(), gray.data(), GrayStride, height, dx, dy);
    if (std::make_pair(dx, dy) != exhaustive(samples, ref, height)) {
        std::cerr << "SAD displacement mismatch at height " << height << '\n';
        std::exit(1);
    }
}
} // namespace

int main() {
    std::mt19937 rng(731);
    Reference ref{};
    Samples samples{};
    int cases = 0;
    for (int height : {1, 4, 5, 10, 16, 31, 32}) {
        for (int trial = 0; trial < 100; ++trial) {
            for (auto& b : ref) b = static_cast<uint8_t>(rng());
            for (auto& b : samples) b = static_cast<uint8_t>(rng());
            check(samples, ref, height);
            ++cases;
        }
        // Exact matches at every search offset, including window boundaries.
        for (int dy = -Radius; dy <= Radius; ++dy) {
            for (int dx = -Radius; dx <= Radius; ++dx) {
                int i = 0;
                for (int y = 0; y < height; y += 4) {
                    for (int x = 0; x < 32; x += 4) {
                        samples[i++] = luma(ref.data() + (y + dy + Radius) * Stride
                                + (x + dx + Radius) * 3);
                    }
                }
                check(samples, ref, height);
                ++cases;
            }
        }
        // Equal nonzero SAD and equal zero SAD must both select the first offset.
        ref.fill(0);
        samples.fill(255);
        check(samples, ref, height);
        samples.fill(0);
        check(samples, ref, height);
        ref.fill(255);
        samples.fill(255);
        check(samples, ref, height);
        cases += 3;
    }
    std::cout << cases << " motion-flow SAD cases passed\n";
}
