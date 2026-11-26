#ifndef MOTION_SAMPLED_LUMA_H
#define MOTION_SAMPLED_LUMA_H

#include <stdint.h>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace motion_luma {

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
// Extract pixels 0, 4, ..., 28 from two adjacent 16-pixel vectors.
inline uint8x8_t stride4(uint8x16_t lo, uint8x16_t hi) {
    const uint8x16_t even = vuzpq_u8(lo, hi).val[0];
    return vuzp_u8(vget_low_u8(even), vget_high_u8(even)).val[0];
}
#endif

// The input contains exactly 32 RGB pixels; the output is eight luma samples.
inline void sampleRow32(const uint8_t* rgb, uint8_t* out) {
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    const uint8x16x3_t lo = vld3q_u8(rgb);
    const uint8x16x3_t hi = vld3q_u8(rgb + 48);
    const uint8x8_t r = stride4(lo.val[0], hi.val[0]);
    const uint8x8_t g = stride4(lo.val[1], hi.val[1]);
    const uint8x8_t b = stride4(lo.val[2], hi.val[2]);
    uint16x8_t sum = vaddl_u8(r, b);
    sum = vaddw_u8(sum, g);
    sum = vaddw_u8(sum, g);
    vst1_u8(out, vshrn_n_u16(sum, 2));
#else
    for (int i = 0; i < 8; ++i) {
        const uint8_t* p = rgb + i * 12;
        out[i] = (p[0] + 2 * p[1] + p[2]) >> 2;
    }
#endif
}

template<int Width, int Height>
inline void sampleFrame(const uint8_t* rgb, uint8_t* out) {
    static_assert(Width > 0 && Width % 32 == 0 && Height > 0 && Height % 4 == 0,
                  "Sampled luma requires complete stride-4 rows");
    for (int y = 0; y < Height; y += 4) {
        const uint8_t* row = rgb + y * Width * 3;
        uint8_t* dst = out + (y / 4) * (Width / 4);
        for (int x = 0; x < Width; x += 32) {
            sampleRow32(row + x * 3, dst + x / 4);
        }
    }
}

// Exact equivalent of any(abs(current[i] - reference[i]) > threshold).
// Only eight packed samples are read from either plane.
inline bool rowHasChange(const uint8_t* current, const uint8_t* reference, int threshold) {
    if (threshold < 0) return true;
    if (threshold >= 255) return false;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    const uint8x8_t diff = vabd_u8(vld1_u8(current), vld1_u8(reference));
    const uint8x8_t changed = vcgt_u8(diff, vdup_n_u8(static_cast<uint8_t>(threshold)));
    return vget_lane_u64(vreinterpret_u64_u8(changed), 0) != 0;
#else
    for (int i = 0; i < 8; ++i) {
        const int diff = current[i] - reference[i];
        if ((diff < 0 ? -diff : diff) > threshold) return true;
    }
    return false;
#endif
}

} // namespace motion_luma

#endif
