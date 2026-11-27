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

// Preserve full-resolution luma for arbitrary-offset flow and RGB only at
// the stride-4 positions used by shadow discrimination and diagnostics.
// All three outputs are populated together before a history slot is published.
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
inline uint8x16_t luma16(const uint8x16x3_t& p) {
    uint16x8_t lo = vaddl_u8(vget_low_u8(p.val[0]), vget_low_u8(p.val[2]));
    uint16x8_t hi = vaddl_u8(vget_high_u8(p.val[0]), vget_high_u8(p.val[2]));
    lo = vaddw_u8(lo, vget_low_u8(p.val[1]));
    lo = vaddw_u8(lo, vget_low_u8(p.val[1]));
    hi = vaddw_u8(hi, vget_high_u8(p.val[1]));
    hi = vaddw_u8(hi, vget_high_u8(p.val[1]));
    return vcombine_u8(vshrn_n_u16(lo, 2), vshrn_n_u16(hi, 2));
}
#endif

template<int Width, int Height>
inline void packHistory(const uint8_t* rgb, int rgbStride, uint8_t* gray,
                        uint8_t* sampledLuma, uint8_t* sampledRgb) {
    static_assert(Width > 0 && Width % 32 == 0 && Height > 0 && Height % 4 == 0,
                  "Motion history requires complete stride-4 rows");
    for (int y = 0; y < Height; ++y) {
        const uint8_t* row = rgb + y * rgbStride;
        uint8_t* dst = gray + y * Width;
        const bool sampledRow = (y % 4 == 0);
        uint8_t* packedLuma = sampledLuma + (y / 4) * (Width / 4);
        uint8_t* packedRgb = sampledRgb + (y / 4) * (Width / 4) * 3;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        if (sampledRow) {
            for (int x = 0; x < Width; x += 32) {
                const uint8x16x3_t lo = vld3q_u8(row + x * 3);
                const uint8x16x3_t hi = vld3q_u8(row + x * 3 + 48);
                const uint8x16_t grayLo = luma16(lo);
                const uint8x16_t grayHi = luma16(hi);
                vst1q_u8(dst + x, grayLo);
                vst1q_u8(dst + x + 16, grayHi);
                vst1_u8(packedLuma + x / 4, stride4(grayLo, grayHi));
                uint8x8x3_t colors;
                colors.val[0] = stride4(lo.val[0], hi.val[0]);
                colors.val[1] = stride4(lo.val[1], hi.val[1]);
                colors.val[2] = stride4(lo.val[2], hi.val[2]);
                vst3_u8(packedRgb + (x / 4) * 3, colors);
            }
        } else {
            for (int x = 0; x < Width; x += 16) {
                vst1q_u8(dst + x, luma16(vld3q_u8(row + x * 3)));
            }
        }
#else
        for (int x = 0; x < Width; ++x) {
            const uint8_t* p = row + x * 3;
            dst[x] = (p[0] + 2 * p[1] + p[2]) >> 2;
            if (sampledRow && x % 4 == 0) {
                packedLuma[x / 4] = dst[x];
                for (int c = 0; c < 3; ++c) packedRgb[(x / 4) * 3 + c] = p[c];
            }
        }
#endif
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
