#ifndef MOTION_FLOW_SAD_H
#define MOTION_FLOW_SAD_H

#include <stdint.h>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace motion_flow {

// Convert a contiguous RGB row using the exact integer luma formula used by
// the motion filters. The final samples are scalar; no read past the row.
inline void fillLumaRow(const uint8_t* rgb, uint8_t* luma, int width) {
    int x = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    for (; x + 8 <= width; x += 8) {
        const uint8x8x3_t pixels = vld3_u8(rgb + x * 3);
        uint16x8_t sum = vaddl_u8(pixels.val[0], pixels.val[2]);
        sum = vaddw_u8(sum, pixels.val[1]);
        sum = vaddw_u8(sum, pixels.val[1]);
        vst1_u8(luma + x, vshrn_n_u16(sum, 2));
    }
#endif
    for (; x < width; ++x) {
        const uint8_t* p = rgb + x * 3;
        luma[x] = (p[0] + 2 * p[1] + p[2]) >> 2;
    }
}

// Exactly eight stride-4 samples from a 32-pixel reference row. The caller
// provides the full row including the three unused pixels after sample 7.
inline int sadRow(const uint8_t* current, const uint8_t* reference) {
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    const uint8x8_t ref = vld4_u8(reference).val[0];
    const uint8x8_t diff = vabd_u8(vld1_u8(current), ref);
    const uint16x4_t pairs = vpaddl_u8(diff);
    const uint32x2_t halves = vpaddl_u16(pairs);
    return vget_lane_u32(halves, 0) + vget_lane_u32(halves, 1);
#else
    int sum = 0;
    for (int x = 0; x < 8; ++x) {
        const int d = current[x] - reference[x * 4];
        sum += d < 0 ? -d : d;
    }
    return sum;
#endif
}

// Reference starts at the upper-left corner of the padded search window.
// Rows are converted on first use, not per candidate/sample. Early exits are
// checked after a row instead of after each pixel: SAD terms are nonnegative,
// so this preserves the strict first-minimum tie rule and zero-SAD exit.
template<int Radius>
inline void bestDisplacement(const uint8_t* current, const uint8_t* reference,
                             int rgbStride, int patchHeight, int& bestDx, int& bestDy) {
    constexpr int Width = 32 + 2 * Radius;
    uint8_t luma[Width][Width];
    bool ready[Width] = {};
    int bestSad = -1;
    bestDx = bestDy = 0;
    for (int dy = -Radius; dy <= Radius && bestSad != 0; ++dy) {
        for (int dx = -Radius; dx <= Radius && bestSad != 0; ++dx) {
            int sad = 0;
            for (int y = 0; y < patchHeight; y += 4) {
                const int row = y + dy + Radius;
                if (!ready[row]) {
                    fillLumaRow(reference + row * rgbStride, luma[row], Width);
                    ready[row] = true;
                }
                sad += sadRow(current + (y / 4) * 8, luma[row] + (dx + Radius));
                if (bestSad >= 0 && sad >= bestSad) break;
            }
            if (bestSad < 0 || sad < bestSad) {
                bestSad = sad;
                bestDx = dx;
                bestDy = dy;
            }
        }
    }
}

} // namespace motion_flow

#endif
