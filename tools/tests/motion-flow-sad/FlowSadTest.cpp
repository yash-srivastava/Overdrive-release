#include "../../../app/src/main/cpp/surveillance/motion_flow_sad.h"

#include <array>
#include <cstdlib>
#include <chrono>
#include <cstring>
#include <vector>
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

template<int GrayStride>
void checkStride(const Samples& samples, const Reference& ref, int height,
                 const std::pair<int, int>& expected) {
    std::array<uint8_t, GrayStride * Width> gray{};
    for (int y = 0; y < Width; ++y) {
        for (int x = 0; x < Width; ++x) {
            gray[y * GrayStride + x] = luma(ref.data() + y * Stride + x * 3);
        }
    }
    int dx, dy;
    motion_flow::bestDisplacement<Radius>(samples.data(), gray.data(), GrayStride, height, dx, dy);
    if (std::make_pair(dx, dy) != expected) {
        std::cerr << "SAD displacement mismatch at height " << height << '\n';
        std::exit(1);
    }
}

void check(const Samples& samples, const Reference& ref, int height) {
    const auto expected = exhaustive(samples, ref, height);
    checkStride<Width + 5>(samples, ref, height, expected);
    checkStride<Width>(samples, ref, height, expected);
}

// Same binary, same input corpus, original search vs candidate-lane search.
// Use on ARM64; on other architectures both calls use the portable path.
struct BenchCase {
    std::array<uint8_t, Width * Width> gray;
    Samples samples;
};

template<bool Serial>
void benchmark(const std::vector<BenchCase>& cases) {
    int checksum = 0;
    const auto start = std::chrono::steady_clock::now();
    for (int repeat = 0; repeat < 50; ++repeat) {
        for (const auto& c : cases) {
            int dx, dy;
            if (Serial) {
                motion_flow::bestDisplacementSerial<Radius>(c.samples.data(), c.gray.data(),
                        Width, 32, dx, dy);
            } else {
                motion_flow::bestDisplacement<Radius>(c.samples.data(), c.gray.data(),
                        Width, 32, dx, dy);
            }
            checksum += (dx + Radius) + (dy + Radius) * (2 * Radius + 1);
        }
    }
    const auto end = std::chrono::steady_clock::now();
    const double ns = std::chrono::duration<double, std::nano>(end - start).count();
    std::cout << (Serial ? "serial" : "dispatch") << ": "
              << ns / (50 * cases.size()) << " ns/block, checksum " << checksum << '\n';
}

void runBenchmark() {
    std::mt19937 rng(732);
    for (int mode = 0; mode < 3; ++mode) {
        std::vector<BenchCase> cases(256);
        for (auto& c : cases) {
            for (auto& b : c.gray) b = static_cast<uint8_t>(rng());
            const int dx = mode == 1 ? 0 : static_cast<int>(rng() % 13) - Radius;
            const int dy = mode == 1 ? 0 : static_cast<int>(rng() % 13) - Radius;
            for (int y = 0; y < 8; ++y) {
                for (int x = 0; x < 8; ++x) {
                    c.samples[y * 8 + x] = mode == 0 ? static_cast<uint8_t>(rng())
                            : c.gray[(y * 4 + dy + Radius) * Width + x * 4 + dx + Radius];
                }
            }
        }
        std::cout << (mode == 0 ? "random" : mode == 1 ? "center match" : "shifted match") << '\n';
        benchmark<true>(cases);
        benchmark<false>(cases);
        benchmark<false>(cases);
        benchmark<true>(cases);
    }
}
} // namespace

int main(int argc, char** argv) {
    if (argc == 2 && std::strcmp(argv[1], "--benchmark") == 0) {
        runBenchmark();
        return 0;
    }
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
        // A perfect match in an unused SIMD lane (dx = +7) must never win.
        // The 44-pixel reference window still contains all of its samples.
        for (auto& b : ref) b = static_cast<uint8_t>(rng());
        int i = 0;
        for (int y = 0; y < height; y += 4) {
            for (int x = 0; x < 32; x += 4) {
                samples[i++] = luma(ref.data() + (y + Radius) * Stride + (x + 13) * 3);
            }
        }
        check(samples, ref, height);
        ++cases;
    }
    std::cout << cases << " motion-flow SAD cases passed\n";
}
