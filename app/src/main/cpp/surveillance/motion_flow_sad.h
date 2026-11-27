#ifndef MOTION_FLOW_SAD_H
#define MOTION_FLOW_SAD_H

#include <stdint.h>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace motion_flow {

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

#if defined(__aarch64__) && (defined(__ARM_NEON) || defined(__ARM_NEON__))
// One lane per horizontal displacement, rather than one lane per pixel.
// At radius 6 the window is 44 pixels wide: the final stride-4 sample starts
// at x=28, so even the full 16-byte load stays inside the reference row.
inline void bestDisplacement13(const uint8_t* current, const uint8_t* reference,
                               int stride, int height, int& bestDx, int& bestDy) {
    constexpr int radius = 6;
    // Seed a pruning bound with the zero-displacement SAD. Do not publish it
    // as a winner: an earlier offset with the same score must still win.
    int bestSad = 1;
    for (int y = 0; y < height; y += 4) {
        bestSad += sadRow(current + (y / 4) * 8,
                           reference + (y + radius) * stride + radius);
    }
    bestDx = bestDy = 0;

    for (int dy = -radius; dy <= radius && bestSad != 0; ++dy) {
        uint16x8_t lo = vdupq_n_u16(0);
        // Lanes 13-15 are not candidates. The bias is larger than the
        // maximum 64*255 SAD, and even biased sums fit in uint16_t.
        uint16x8_t hi = {0, 0, 0, 0, 0, 32768, 32768, 32768};
        int rowMin = bestSad;
        for (int y = 0; y < height; y += 4) {
            const uint8_t* ref = reference + (y + dy + radius) * stride;
            const uint8_t* cur = current + (y / 4) * 8;
            for (int x = 0; x < 8; ++x) {
                const uint8x16_t samples = vld1q_u8(ref + x * 4);
                const uint8x16_t value = vdupq_n_u8(cur[x]);
                lo = vabal_u8(lo, vget_low_u8(samples), vget_low_u8(value));
                hi = vabal_high_u8(hi, samples, value);
            }
            rowMin = vminvq_u16(vminq_u16(lo, hi));
            // All valid candidates can only increase after this row.
            if (rowMin >= bestSad) break;
        }
        if (rowMin < bestSad) {
            // A completed row of candidates improved the global minimum.
            // Select the first lane, preserving the original dy/dx tie rule.
            uint16_t scores[16];
            vst1q_u16(scores, lo);
            vst1q_u16(scores + 8, hi);
            int dx = 0;
            while (scores[dx] != rowMin) ++dx;
            bestSad = rowMin;
            bestDx = dx - radius;
            bestDy = dy;
        }
    }
}
#endif

// Reference starts at the upper-left corner of the padded luma search window.
// SAD terms are nonnegative, so row pruning preserves the strict first-minimum
// tie rule and zero-SAD exit.
template<int Radius>
inline void bestDisplacementSerial(const uint8_t* current, const uint8_t* reference,
                                   int lumaStride, int patchHeight, int& bestDx, int& bestDy) {
    int bestSad = -1;
    bestDx = bestDy = 0;
    for (int dy = -Radius; dy <= Radius && bestSad != 0; ++dy) {
        for (int dx = -Radius; dx <= Radius && bestSad != 0; ++dx) {
            int sad = 0;
            for (int y = 0; y < patchHeight; y += 4) {
                const int row = y + dy + Radius;
                sad += sadRow(current + (y / 4) * 8,
                              reference + (row * lumaStride + dx + Radius));
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

template<int Radius>
inline void bestDisplacement(const uint8_t* current, const uint8_t* reference,
                             int lumaStride, int patchHeight, int& bestDx, int& bestDy) {
#if defined(__aarch64__) && (defined(__ARM_NEON) || defined(__ARM_NEON__))
    if (Radius == 6 && patchHeight > 0 && patchHeight <= 32) {
        bestDisplacement13(current, reference, lumaStride, patchHeight, bestDx, bestDy);
        return;
    }
#endif
    bestDisplacementSerial<Radius>(current, reference, lumaStride, patchHeight, bestDx, bestDy);
}

} // namespace motion_flow

#endif
