#ifndef TRACKER_RGB_H
#define TRACKER_RGB_H

#include <stdint.h>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

// Exact legacy luma approximation: (R + 2*G + B) >> 2. Input rows may
// belong to a larger RGB mosaic; the output is always tightly packed.
static inline void trackerRgbToGray(const uint8_t* rgb, uint8_t* gray,
                                    int width, int height, int rgbStride) {
    for (int y = 0; y < height; ++y) {
        const uint8_t* src = rgb + y * rgbStride;
        uint8_t* dst = gray + y * width;
        int x = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        for (; x + 16 <= width; x += 16) {
            const uint8x16x3_t p = vld3q_u8(src + x * 3);
            uint16x8_t lo = vaddl_u8(vget_low_u8(p.val[0]), vget_low_u8(p.val[2]));
            uint16x8_t hi = vaddl_u8(vget_high_u8(p.val[0]), vget_high_u8(p.val[2]));
            lo = vaddq_u16(lo, vshll_n_u8(vget_low_u8(p.val[1]), 1));
            hi = vaddq_u16(hi, vshll_n_u8(vget_high_u8(p.val[1]), 1));
            vst1q_u8(dst + x, vcombine_u8(vshrn_n_u16(lo, 2), vshrn_n_u16(hi, 2)));
        }
#endif
        for (; x < width; ++x) {
            const uint8_t* p = src + x * 3;
            dst[x] = static_cast<uint8_t>((p[0] + 2 * p[1] + p[2]) >> 2);
        }
    }
}

#endif // TRACKER_RGB_H
