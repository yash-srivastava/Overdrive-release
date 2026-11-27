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

// Reference starts at the upper-left corner of the padded luma search window.
// SAD terms are nonnegative, so row pruning preserves the strict first-minimum
// tie rule and zero-SAD exit.
template<int Radius>
inline void bestDisplacement(const uint8_t* current, const uint8_t* reference,
                             int lumaStride, int patchHeight, int& bestDx, int& bestDy) {
    int bestSad = -1;
    bestDx = bestDy = 0;
    for (int dy = -Radius; dy <= Radius && bestSad != 0; ++dy) {
        for (int dx = -Radius; dx <= Radius && bestSad != 0; ++dx) {
            int sad = 0;
            for (int y = 0; y < patchHeight; y += 4) {
                const int row = y + dy + Radius;
                sad += sadRow(current + (y / 4) * 8,
                              reference + row * lumaStride + dx + Radius);
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
