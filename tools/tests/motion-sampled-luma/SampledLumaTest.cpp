#include "../../../app/src/main/cpp/surveillance/motion_sampled_luma.h"

#include <algorithm>
#include <array>
#include <cstdlib>
#include <iostream>
#include <random>
#include <vector>

namespace {
constexpr int Width = 320;
constexpr int Height = 240;
constexpr int Samples = Width / 4 * (Height / 4);

void require(bool ok, const char* message) {
    if (!ok) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}

int rgbLuma(const uint8_t* rgb) {
    return (static_cast<int>(rgb[0]) + 2 * rgb[1] + rgb[2]) / 4;
}

void checkPlane(const std::vector<uint8_t>& rgb) {
    // A quadrant within a mosaic has a nonzero origin and a larger row stride.
    constexpr int Stride = Width * 2 * 3 + 7;
    constexpr int Origin = Width * 3;
    std::vector<uint8_t> mosaic(Stride * Height, 0x69);
    for (int y = 0; y < Height; ++y) {
        std::copy_n(rgb.data() + y * Width * 3, Width * 3,
                    mosaic.data() + y * Stride + Origin);
    }
    std::vector<uint8_t> gray(Width * Height + 2, 0x5a);
    std::array<uint8_t, Samples + 2> plane{};
    std::array<uint8_t, Samples * 3 + 2> colors{};
    plane.front() = colors.front() = 0x5a;
    plane.back() = colors.back() = 0xa5;
    motion_luma::packHistory<Width, Height>(mosaic.data() + Origin, Stride,
            gray.data() + 1, plane.data() + 1, colors.data() + 1);
    require(gray.front() == 0x5a && gray.back() == 0x5a, "Full luma guard overwritten");
    require(plane.front() == 0x5a && plane.back() == 0xa5, "Plane guard overwritten");
    require(colors.front() == 0x5a && colors.back() == 0xa5, "RGB guard overwritten");
    for (int y = 0; y < Height; ++y) {
        for (int x = 0; x < Width; ++x) {
            const int p = (y * Width + x) * 3;
            require(gray[1 + y * Width + x] == rgbLuma(rgb.data() + p), "Full luma mismatch");
            if (y % 4 == 0 && x % 4 == 0) {
                const int i = (y / 4) * (Width / 4) + x / 4;
                require(plane[1 + i] == rgbLuma(rgb.data() + p), "Sample mismatch");
                for (int c = 0; c < 3; ++c) {
                    require(colors[1 + i * 3 + c] == rgb[p + c], "Sample RGB mismatch");
                }
            }
        }
    }
}

unsigned scalarMask(const uint8_t* a, const uint8_t* b, int threshold) {
    unsigned mask = 0;
    for (int i = 0; i < 8; ++i) {
        if (std::abs(static_cast<int>(a[i]) - b[i]) > threshold) {
            mask |= 1u << i;
        }
    }
    return mask;
}

bool scalarAny(const uint8_t* a, const uint8_t* b, int threshold) {
    return scalarMask(a, b, threshold) != 0;
}

} // namespace

int main() {
    std::mt19937 rng(417);
    std::vector<uint8_t> rgb(Width * Height * 3);
    checkPlane(rgb);
    for (auto& value : rgb) value = 255;
    checkPlane(rgb);
    // Distinct values at each lane and channel expose ordering mistakes.
    for (int y = 0; y < Height; ++y) {
        for (int x = 0; x < Width; ++x) {
            const int p = (y * Width + x) * 3;
            rgb[p] = static_cast<uint8_t>(x + 3 * y);
            rgb[p + 1] = static_cast<uint8_t>(7 * x + y);
            rgb[p + 2] = static_cast<uint8_t>(x + 11 * y);
        }
    }
    checkPlane(rgb);
    for (int trial = 0; trial < 100; ++trial) {
        for (auto& value : rgb) value = static_cast<uint8_t>(rng());
        checkPlane(rgb);
    }

    std::array<uint8_t, 8> a{}, b{};
    for (int lane = 0; lane < 8; ++lane) {
        for (int diff = 0; diff <= 255; ++diff) {
            a.fill(0);
            b.fill(0);
            a[lane] = static_cast<uint8_t>(diff);
            for (int threshold : {-1, 0, diff - 1, diff, diff + 1, 255, 256}) {
                const bool expected = scalarAny(a.data(), b.data(), threshold);
                require(motion_luma::rowChangeMask(a.data(), b.data(), threshold)
                        == scalarMask(a.data(), b.data(), threshold), "Positive mask mismatch");
                require(motion_luma::rowChangeMask(b.data(), a.data(), threshold)
                        == scalarMask(b.data(), a.data(), threshold), "Negative mask mismatch");
                require(motion_luma::rowHasChange(a.data(), b.data(), threshold) == expected,
                        "Positive difference or threshold mismatch");
                require(motion_luma::rowHasChange(b.data(), a.data(), threshold) == expected,
                        "Negative difference or threshold mismatch");
            }
        }
    }
    for (int trial = 0; trial < 10000; ++trial) {
        for (auto& value : a) value = static_cast<uint8_t>(rng());
        for (auto& value : b) value = static_cast<uint8_t>(rng());
        const int threshold = static_cast<int>(rng() % 258) - 1;
        require(motion_luma::rowHasChange(a.data(), b.data(), threshold)
                        == scalarAny(a.data(), b.data(), threshold), "Mixed row mismatch");
        require(motion_luma::rowChangeMask(a.data(), b.data(), threshold)
                        == scalarMask(a.data(), b.data(), threshold), "Mixed mask mismatch");
    }
    // Exercise every position, both signs, all vector/tail boundaries and a
    // full quadrant. A change exactly at the threshold must not pass.
    for (int count : {0, 1, 7, 8, 15, 16, 17, 31, 32, 33, Samples}) {
        std::vector<uint8_t> current(count, 100), reference(count, 100);
        require(!motion_luma::planeHasChange(current.data(), reference.data(), count, 0),
                "Identical plane reported motion");
        require(motion_luma::planeHasChange(current.data(), reference.data(), count, -1)
                        == (count > 0), "Negative threshold mismatch");
        for (int i = 0; i < count; ++i) {
            current[i] = 115;
            require(!motion_luma::planeHasChange(current.data(), reference.data(), count, 15),
                    "Plane threshold equality mismatch");
            require(motion_luma::planeHasChange(current.data(), reference.data(), count, 14),
                    "Missed positive plane change");
            require(motion_luma::planeHasChange(reference.data(), current.data(), count, 14),
                    "Missed negative plane change");
            current[i] = 100;
        }
        require(!motion_luma::planeHasChange(current.data(), reference.data(), count, 255),
                "Threshold ceiling mismatch");
    }
    std::cout << "Packed motion history, row masks and quiet plane checks passed\n";
}
