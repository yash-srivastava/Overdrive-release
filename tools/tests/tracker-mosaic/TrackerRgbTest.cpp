#include "tracker_rgb.h"

#include <algorithm>
#include <cassert>
#include <cstdio>
#include <random>
#include <vector>

static uint8_t reference(const uint8_t* rgb) {
    return (rgb[0] + (rgb[1] << 1) + rgb[2]) >> 2;
}

static void check(const uint8_t* src, int w, int h, int stride) {
    std::vector<uint8_t> gray(w * h + 32, 0xa5);
    trackerRgbToGray(src, gray.data() + 16, w, h, stride);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            assert(gray[16 + y * w + x] == reference(src + y * stride + x * 3));
        }
    }
    for (int i = 0; i < 16; ++i) {
        assert(gray[i] == 0xa5);
        assert(gray[16 + w * h + i] == 0xa5);
    }
}

int main() {
    // Exercise every possible RGB triple, including maximum widened sums.
    std::vector<uint8_t> colors(256 * 256 * 3);
    for (int r = 0; r < 256; ++r) {
        for (int g = 0; g < 256; ++g) {
            for (int b = 0; b < 256; ++b) {
                int i = (g * 256 + b) * 3;
                colors[i] = r;
                colors[i + 1] = g;
                colors[i + 2] = b;
            }
        }
        check(colors.data(), 256, 256, 256 * 3);
    }

    std::mt19937 rng(197);
    int cases = 0;
    for (int w : {1, 7, 15, 16, 17, 31, 32, 33, 319, 320, 321}) {
        for (int h : {1, 3, 240}) {
            for (int pad : {0, 1, 7, 960}) {
                int stride = w * 3 + pad;
                // Last row has no trailing padding, so ASan catches overreads.
                std::vector<uint8_t> rgb(1 + (h - 1) * stride + w * 3);
                for (auto& value : rgb) value = rng() & 255;
                check(rgb.data() + 1, w, h, stride);
                ++cases;
            }
        }
    }

    std::vector<uint8_t> mosaic(640 * 480 * 3);
    for (auto& value : mosaic) value = rng() & 255;
    std::vector<uint8_t> packed(320 * 240 * 3);
    std::vector<uint8_t> directGray(320 * 240), packedGray(320 * 240);
    for (int q = 0; q < 4; ++q) {
        const uint8_t* tile = mosaic.data() + ((q / 2) * 240 * 640 + (q % 2) * 320) * 3;
        for (int y = 0; y < 240; ++y) {
            std::copy_n(tile + y * 640 * 3, 320 * 3, packed.data() + y * 320 * 3);
        }
        trackerRgbToGray(tile, directGray.data(), 320, 240, 640 * 3);
        trackerRgbToGray(packed.data(), packedGray.data(), 320, 240, 320 * 3);
        assert(directGray == packedGray);
        check(tile, 320, 240, 640 * 3);
    }
    std::printf("All 16,777,216 RGB triples, %d stride/tail cases and four mosaic quadrants passed.\n", cases);
}
