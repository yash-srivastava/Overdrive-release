// CPU recording engine: builds the recording grid straight into the encoder's
// YUV input (no GPU) and blends the telemetry overlay. composeGrid is the 1:1
// copy fast path; remapGrid reproduces every legacy shader layout.
#include <jni.h>
#include <math.h>
#include <new>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <memory>
#include <thread>
#include <vector>
#if defined(__ANDROID__) || defined(__linux__)
#include <sched.h>
#include <stdio.h>
#endif
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define OD_CPU_MOSAIC_NEON 1
#endif
#if defined(__aarch64__) && defined(OD_CPU_MOSAIC_NEON) && !defined(OD_REMAP_SCALAR)
#define OD_REMAP_NEON 1
#endif

namespace odcpu {

struct Planes {
    uint8_t* y;
    uint8_t* u;
    uint8_t* v;
    size_t capY;
    size_t capU;
    size_t capV;
    int rowY;
    int rowUV;
    int pixUV;
};

enum Chroma { kNv12 = 0, kNv21 = 1, kStrided = 2 };

static const int32_t kOverlayMagic = 0x4F564C31;  // "OVL1"

static int64_t nowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static Chroma chromaOf(const uint8_t* u, const uint8_t* v, int pix) {
    if (pix == 2 && v == u + 1) return kNv12;
    if (pix == 2 && u == v + 1) return kNv21;
    return kStrided;
}

// True when `rows` rows of `samples` samples (pixel stride `pix`) fit in `cap` bytes.
// Interleaved chroma reads both bytes of a pair through min(u, v); the second
// byte is the other plane's sample, which that plane's own fits() check covers.
static bool fits(size_t cap, int rows, int row, int samples, int pix) {
    if (rows <= 0 || samples <= 0) return true;
    if (row <= 0 || pix <= 0) return false;
    uint64_t need = (uint64_t) (rows - 1) * (uint64_t) row
            + (uint64_t) (samples - 1) * (uint64_t) pix + 1u;
    return need <= (uint64_t) cap;
}

static void swapPairs(uint8_t* d, const uint8_t* s, int n) {
    int i = 0;
#ifdef OD_CPU_MOSAIC_NEON
    for (; i + 16 <= n; i += 16) vst1q_u8(d + i, vrev16q_u8(vld1q_u8(s + i)));
#endif
    for (; i + 1 < n; i += 2) {
        uint8_t a = s[i];
        d[i] = s[i + 1];
        d[i + 1] = a;
    }
}

// A pair of planar rows can be copied in bulk. For mixed planar/interleaved
// layouts, use structured NEON loads/stores to convert 16 chroma samples at
// once, preserving the U/V order and leaving row padding untouched.
static void copyChromaRow(const uint8_t* su, const uint8_t* sv, int sPix, Chroma sc,
                          uint8_t* du, uint8_t* dv, int dPix, Chroma dc, int n) {
    if (sPix == 1 && dPix == 1) {
        memcpy(du, su, (size_t) n);
        memcpy(dv, sv, (size_t) n);
        return;
    }
    int i = 0;
#ifdef OD_CPU_MOSAIC_NEON
    if (sPix == 1 && dc != kStrided) {
        uint8_t* dst = dc == kNv12 ? du : dv;
        for (; i + 16 <= n; i += 16) {
            uint8x16x2_t pair;
            pair.val[0] = vld1q_u8((dc == kNv12 ? su : sv) + i);
            pair.val[1] = vld1q_u8((dc == kNv12 ? sv : su) + i);
            vst2q_u8(dst + (size_t) i * 2, pair);
        }
    } else if (sc != kStrided && dPix == 1) {
        const uint8_t* src = sc == kNv12 ? su : sv;
        for (; i + 16 <= n; i += 16) {
            const uint8x16x2_t pair = vld2q_u8(src + (size_t) i * 2);
            vst1q_u8(du + i, pair.val[sc == kNv12 ? 0 : 1]);
            vst1q_u8(dv + i, pair.val[sc == kNv12 ? 1 : 0]);
        }
    }
#endif
    for (; i < n; i++) {
        du[(size_t) i * dPix] = su[(size_t) i * sPix];
        dv[(size_t) i * dPix] = sv[(size_t) i * sPix];
    }
}

// Quadrants: 0=TL, 1=TR, 2=BL, 3=BR; srcX[q] is the tile's left column in the strip.
int composeGrid(const Planes& s, int srcW, int srcH, const Planes& d, int dstW, int dstH,
                const int srcX[4], int camW, int camH) {
    if (camW <= 0 || camH <= 0 || (camW & 1) || (camH & 1)) return -10;
    if (dstW < 2 * camW || dstH < 2 * camH || srcH < camH) return -11;
    for (int q = 0; q < 4; q++) {
        if (srcX[q] < 0 || (srcX[q] & 1) || srcX[q] + camW > srcW) return -12;
    }
    if (!s.y || !s.u || !s.v || !d.y || !d.u || !d.v) return -13;
    if (s.rowY < srcW || d.rowY < 2 * camW) return -14;
    if (s.rowUV < (srcW / 2) * s.pixUV || d.rowUV < camW * d.pixUV) return -15;
    if (!fits(s.capY, camH, s.rowY, srcW, 1)) return -20;
    if (!fits(s.capU, camH / 2, s.rowUV, srcW / 2, s.pixUV)
            || !fits(s.capV, camH / 2, s.rowUV, srcW / 2, s.pixUV)) return -21;
    if (!fits(d.capY, 2 * camH, d.rowY, 2 * camW, 1)) return -22;
    if (!fits(d.capU, camH, d.rowUV, camW, d.pixUV)
            || !fits(d.capV, camH, d.rowUV, camW, d.pixUV)) return -23;

    const Chroma sc = chromaOf(s.u, s.v, s.pixUV);
    const Chroma dc = chromaOf(d.u, d.v, d.pixUV);
    const bool interleaved = sc != kStrided && dc != kStrided;
    const uint8_t* sBase = sc == kNv21 ? s.v : s.u;
    uint8_t* dBase = dc == kNv21 ? d.v : d.u;
    const int halfW = camW / 2;
    const int halfH = camH / 2;

    for (int q = 0; q < 4; q++) {
        const int ox = (q & 1) * camW;
        const int oy = (q >> 1) * camH;
        const int sx = srcX[q];
        for (int r = 0; r < camH; r++) {
            memcpy(d.y + (size_t) (oy + r) * d.rowY + ox, s.y + (size_t) r * s.rowY + sx,
                   (size_t) camW);
        }
        for (int r = 0; r < halfH; r++) {
            const size_t sRow = (size_t) r * s.rowUV;
            const size_t dRow = (size_t) (oy / 2 + r) * d.rowUV;
            if (interleaved) {
                const uint8_t* src = sBase + sRow + sx;
                uint8_t* dst = dBase + dRow + ox;
                if (sc == dc) memcpy(dst, src, (size_t) camW);
                else swapPairs(dst, src, camW);
            } else {
                const uint8_t* su = s.u + sRow + (size_t) (sx / 2) * s.pixUV;
                const uint8_t* sv = s.v + sRow + (size_t) (sx / 2) * s.pixUV;
                uint8_t* du = d.u + dRow + (size_t) (ox / 2) * d.pixUV;
                uint8_t* dv = d.v + dRow + (size_t) (ox / 2) * d.pixUV;
                copyChromaRow(su, sv, s.pixUV, sc, du, dv, d.pixUV, dc, halfW);
            }
        }
    }
    return 0;
}

// Table layout: int32 header[4] {magic, w, h, ready}, int32 spansY[h][2],
// int32 spansC[h/2][2], uint16 tabY[h][w][2] {a*Y*256, 256-a*256},
// uint16 tabC[h/2][w/2][3] {a*U*256, a*V*256, 256-a*256}.
size_t overlayBytes(int w, int h) {
    if (w <= 0 || h <= 0 || (w & 1) || (h & 1) || w > 8192 || h > 2048) return 0;
    const size_t wc = (size_t) w / 2, hc = (size_t) h / 2;
    return 16 + (size_t) h * 8 + hc * 8 + (size_t) w * h * 4 + wc * hc * 6;
}

static inline uint16_t clampU16(double v) {
    long r = lround(v);
    if (r < 0) return 0;
    if (r > 65535) return 65535;
    return (uint16_t) r;
}

// rgba: premultiplied RGBA8 (Android ARGB_8888 memory order), drawn over the
// top w x h of the frame with bilinear scaling, like the GL overlay pass.
// Colour math is BT.601 limited range, the encoder's RGB->YUV conversion.
bool prepareOverlay(const uint8_t* rgba, size_t rgbaCap, int bw, int bh, int rowBytes,
                    uint8_t* out, size_t outCap, int w, int h) {
    const size_t need = overlayBytes(w, h);
    if (!rgba || !out || need == 0 || need > outCap) return false;
    if (bw <= 0 || bh <= 0 || rowBytes < bw * 4) return false;
    if ((uint64_t) (bh - 1) * (uint64_t) rowBytes + (uint64_t) bw * 4u > (uint64_t) rgbaCap) return false;
    if (((uintptr_t) out & 3u) != 0) return false;

    int32_t* hdr = (int32_t*) out;
    hdr[0] = kOverlayMagic;
    hdr[1] = w;
    hdr[2] = h;
    hdr[3] = 0;
    int32_t* spansY = hdr + 4;
    int32_t* spansC = spansY + (size_t) h * 2;
    uint16_t* tabY = (uint16_t*) (spansC + (size_t) (h / 2) * 2);
    uint16_t* tabC = tabY + (size_t) w * h * 2;
    const int wc = w / 2;

    // Per pixel of the current row pair: alpha and alpha-weighted U/V.
    float* scratch = new (std::nothrow) float[(size_t) w * 6];
    if (!scratch) return false;
    float* rowA[2] = {scratch, scratch + w};
    float* rowU[2] = {scratch + 2 * (size_t) w, scratch + 3 * (size_t) w};
    float* rowV[2] = {scratch + 4 * (size_t) w, scratch + 5 * (size_t) w};

    // The horizontal bilinear mapping is identical for every output row.
    // Preserve the original double expression and clamping (no accumulated step).
    struct Column {
        int x0, x1;
        double wx;
    };
    Column* columns = new (std::nothrow) Column[w];
    if (!columns) {
        delete[] scratch;
        return false;
    }
    for (int x = 0; x < w; x++) {
        double fx = ((double) x + 0.5) * bw / w - 0.5;
        if (fx < 0) fx = 0;
        if (fx > bw - 1) fx = bw - 1;
        const int x0 = (int) fx;
        columns[x] = {x0, x0 + 1 < bw ? x0 + 1 : x0, fx - x0};
    }

    for (int yc = 0; yc < h / 2; yc++) {
        for (int k = 0; k < 2; k++) {
            const int y = yc * 2 + k;
            double fy = ((double) y + 0.5) * bh / h - 0.5;
            if (fy < 0) fy = 0;
            if (fy > bh - 1) fy = bh - 1;
            const int y0 = (int) fy;
            const int y1 = y0 + 1 < bh ? y0 + 1 : y0;
            const double wy = fy - y0;
            const uint8_t* r0 = rgba + (size_t) y0 * rowBytes;
            const uint8_t* r1 = rgba + (size_t) y1 * rowBytes;
            int first = -1, last = -1;
            uint16_t* ty = tabY + (size_t) y * w * 2;
            for (int x = 0; x < w; x++) {
                const Column& column = columns[x];
                const int x0 = column.x0;
                const int x1 = column.x1;
                const double wx = column.wx;
                const uint8_t* p00 = r0 + (size_t) x0 * 4;
                const uint8_t* p01 = r0 + (size_t) x1 * 4;
                const uint8_t* p10 = r1 + (size_t) x0 * 4;
                const uint8_t* p11 = r1 + (size_t) x1 * 4;
                if ((p00[3] | p01[3] | p10[3] | p11[3]) == 0) {
                    // Premultiplied: zero alpha means zero colour too.
                    ty[x * 2] = 0;
                    ty[x * 2 + 1] = 256;
                    rowA[k][x] = rowU[k][x] = rowV[k][x] = 0.0f;
                    continue;
                }
                double c[4];
                for (int ch = 0; ch < 4; ch++) {
                    const double top = p00[ch] + (p01[ch] - p00[ch]) * wx;
                    const double bot = p10[ch] + (p11[ch] - p10[ch]) * wx;
                    c[ch] = top + (bot - top) * wy;
                }
                const double a = c[3] / 255.0;
                const double yv = 16.0 + (65.481 * c[0] + 128.553 * c[1] + 24.966 * c[2]) / 255.0;
                const double uv = 128.0 + (-37.797 * c[0] - 74.203 * c[1] + 112.0 * c[2]) / 255.0;
                const double vv = 128.0 + (112.0 * c[0] - 93.786 * c[1] - 18.214 * c[2]) / 255.0;
                const long a256 = lround(a * 256.0);
                ty[x * 2] = a256 > 0 ? clampU16(a * yv * 256.0) : 0;
                ty[x * 2 + 1] = (uint16_t) (256 - (a256 < 0 ? 0 : a256 > 256 ? 256 : a256));
                if (a256 > 0) {
                    if (first < 0) first = x;
                    last = x;
                }
                rowA[k][x] = (float) a;
                rowU[k][x] = (float) (a * uv);
                rowV[k][x] = (float) (a * vv);
            }
            spansY[y * 2] = first < 0 ? 0 : first;
            spansY[y * 2 + 1] = first < 0 ? 0 : last + 1;
        }
        int first = -1, last = -1;
        uint16_t* tc = tabC + (size_t) yc * wc * 3;
        for (int xc = 0; xc < wc; xc++) {
            const int x = xc * 2;
            const double a = ((double) rowA[0][x] + rowA[0][x + 1] + rowA[1][x] + rowA[1][x + 1]) * 0.25;
            // Zero (or rounded-to-zero) alpha is an identity blend. Do not
            // load/average the two chroma channels when their weights are unused.
            const long a256 = a == 0.0 ? 0 : lround(a * 256.0);
            if (a256 <= 0) {
                tc[xc * 3] = tc[xc * 3 + 1] = 0;
                tc[xc * 3 + 2] = 256;
                continue;
            }
            const double pu = ((double) rowU[0][x] + rowU[0][x + 1] + rowU[1][x] + rowU[1][x + 1]) * 0.25;
            const double pv = ((double) rowV[0][x] + rowV[0][x + 1] + rowV[1][x] + rowV[1][x + 1]) * 0.25;
            tc[xc * 3] = clampU16(pu * 256.0);
            tc[xc * 3 + 1] = clampU16(pv * 256.0);
            tc[xc * 3 + 2] = (uint16_t) (256 - (a256 > 256 ? 256 : a256));
            if (first < 0) first = xc;
            last = xc;
        }
        spansC[yc * 2] = first < 0 ? 0 : first;
        spansC[yc * 2 + 1] = first < 0 ? 0 : last + 1;
    }
    delete[] columns;
    delete[] scratch;
    hdr[3] = 1;
    return true;
}

static inline uint8_t mix(uint32_t premul, uint32_t inv, uint32_t dst) {
    uint32_t v = (premul + inv * dst + 128u) >> 8;
    return (uint8_t) (v > 255u ? 255u : v);
}

#ifdef OD_CPU_MOSAIC_NEON
// Match mix() exactly, including rounding and saturation. Widen before
// adding: arbitrary 16-bit table weights can produce a sum above 65535.
static inline uint8x8_t mix8(uint16x8_t premul, uint16x8_t inv, uint8x8_t dst) {
    const uint16x8_t pixels = vmovl_u8(dst);
    const uint32x4_t lo = vmlal_u16(vmovl_u16(vget_low_u16(premul)),
                                    vget_low_u16(inv), vget_low_u16(pixels));
    const uint32x4_t hi = vmlal_u16(vmovl_u16(vget_high_u16(premul)),
                                    vget_high_u16(inv), vget_high_u16(pixels));
    return vqmovn_u16(vcombine_u16(vqrshrn_n_u32(lo, 8), vqrshrn_n_u32(hi, 8)));
}
#endif

// 0 = blended, 1 = no overlay table yet, negative = rejected.
int blendOverlay(const uint8_t* table, size_t tableCap, const Planes& d, int dstW, int dstH) {
    if (!table || tableCap < 16 || ((uintptr_t) table & 3u) != 0) return -30;
    const int32_t* hdr = (const int32_t*) table;
    if (hdr[0] != kOverlayMagic) return -31;
    if (hdr[3] != 1) return 1;
    const int w = hdr[1], h = hdr[2];
    const size_t need = overlayBytes(w, h);
    if (need == 0 || need > tableCap) return -32;
    if (w > dstW || h > dstH) return -33;
    if (!d.y || !d.u || !d.v) return -34;
    if (!fits(d.capY, h, d.rowY, w, 1)) return -35;
    if (!fits(d.capU, h / 2, d.rowUV, w / 2, d.pixUV)
            || !fits(d.capV, h / 2, d.rowUV, w / 2, d.pixUV)) return -36;
    const int32_t* spansY = hdr + 4;
    const int32_t* spansC = spansY + (size_t) h * 2;
    const uint16_t* tabY = (const uint16_t*) (spansC + (size_t) (h / 2) * 2);
    const uint16_t* tabC = tabY + (size_t) w * h * 2;
    const int wc = w / 2;
    for (int y = 0; y < h; y++) {
        int x0 = spansY[y * 2], x1 = spansY[y * 2 + 1];
        x0 = std::max(0, std::min(x0, w));
        x1 = std::max(0, std::min(x1, w));
        uint8_t* row = d.y + (size_t) y * d.rowY;
        const uint16_t* t = tabY + (size_t) y * w * 2;
        int x = x0;
#ifdef OD_CPU_MOSAIC_NEON
        for (; x + 8 <= x1; x += 8) {
            const uint16x8x2_t weights = vld2q_u16(t + x * 2);
            vst1_u8(row + x, mix8(weights.val[0], weights.val[1], vld1_u8(row + x)));
        }
#endif
        for (; x < x1; x++) row[x] = mix(t[x * 2], t[x * 2 + 1], row[x]);
    }
#ifdef OD_CPU_MOSAIC_NEON
    const Chroma dc = chromaOf(d.u, d.v, d.pixUV);
#endif
    for (int yc = 0; yc < h / 2; yc++) {
        int x0 = spansC[yc * 2], x1 = spansC[yc * 2 + 1];
        x0 = std::max(0, std::min(x0, wc));
        x1 = std::max(0, std::min(x1, wc));
        uint8_t* ur = d.u + (size_t) yc * d.rowUV;
        uint8_t* vr = d.v + (size_t) yc * d.rowUV;
        const uint16_t* t = tabC + (size_t) yc * wc * 3;
        int xc = x0;
#ifdef OD_CPU_MOSAIC_NEON
        if (d.pixUV == 1) {
            for (; xc + 8 <= x1; xc += 8) {
                const uint16x8x3_t weights = vld3q_u16(t + xc * 3);
                vst1_u8(ur + xc, mix8(weights.val[0], weights.val[2], vld1_u8(ur + xc)));
                vst1_u8(vr + xc, mix8(weights.val[1], weights.val[2], vld1_u8(vr + xc)));
            }
        } else if (dc != kStrided) {
            uint8_t* base = dc == kNv12 ? ur : vr;
            const int uIndex = dc == kNv12 ? 0 : 1;
            const int vIndex = 1 - uIndex;
            for (; xc + 8 <= x1; xc += 8) {
                const uint16x8x3_t weights = vld3q_u16(t + xc * 3);
                uint8_t* dst = base + (size_t) xc * 2;
                uint8x8x2_t pair = vld2_u8(dst);
                pair.val[uIndex] = mix8(weights.val[0], weights.val[2], pair.val[uIndex]);
                pair.val[vIndex] = mix8(weights.val[1], weights.val[2], pair.val[vIndex]);
                vst2_u8(dst, pair);
            }
        }
#endif
        for (; xc < x1; xc++) {
            const size_t o = (size_t) xc * d.pixUV;
            ur[o] = mix(t[xc * 3], t[xc * 3 + 2], ur[o]);
            vr[o] = mix(t[xc * 3 + 1], t[xc * 3 + 2], vr[o]);
        }
    }
    return 0;
}


// ---------------------------------------------------------------------------
// General remap: a transliteration of GpuMosaicRecorder's fragment shader for
// the 4-camera strip (2x2 grid and dashcam layout, lens-correction dewarp,
// windshield top band), sampled like GL_LINEAR + CLAMP_TO_EDGE.
// ---------------------------------------------------------------------------

struct RemapSpec {
    int outW;
    int outH;
    int layout;          // 0 = 2x2 grid, 1 = dashcam
    float off[4];        // strip x of {front, right, rear, left}, normalized
    float k1;
    float k2;
    float aspect;        // tile height / width
    int windshield;      // dashcam top band from the windshield frame
    float cropY;         // windshield vertical crop
    float split;         // dashcam top band height (0.70)
};

static const int kMaxRow = 4096;

// With lens correction disabled, X depends only on the column and Y only
// on the row. Keep the original float operation order and 8.8 rounding.
static bool isIdentityRemap(const RemapSpec& p) {
    // Outside plausible camera aspect ratios retain the original math.
    return p.k1 == 0.f && p.k2 == 0.f && p.aspect >= 0.0625f && p.aspect <= 16.f;
}

static void identityX(int n, float tx0, float dtx, float uScale, int32_t* lu) {
    for (int i = 0; i < n; i++) {
        const float ax = (tx0 + (float) i * dtx) * 2.f - 1.f;
        const float ux = fminf(fmaxf(ax * 0.5f + 0.5f, 0.f), 1.f);
        lu[i] = (int32_t) floorf((ux * uScale - 0.5f) * 256.f + 0.5f);
    }
}

static void identityY(int n, float ty, float aspect, float vSize, int32_t* lv) {
    const float ay = (ty * 2.f - 1.f) * aspect;
    const float yScale = 1.f / aspect;
    const float uy = fminf(fmaxf(ay * yScale * 0.5f + 0.5f, 0.f), 1.f);
    const float vt = fminf(fmaxf(uy * vSize - 0.5f, 0.f), vSize - 1.f);
    std::fill_n(lv, n, (int32_t) (vt * 256.f + 0.5f));
}

// Tile coords tx = tx0 + i * dtx at fixed ty -> rectifyTile() of the shader ->
// 8.8 fixed texel coords: lu is tile-local (add the strip offset), lv is
// clamped to the source height. k1 = k2 = 0 is the identity.
static void rectifyRow(const RemapSpec& p, int n, float tx0, float dtx, float ty,
                       float uScale, float vSize, int32_t* __restrict lu,
                       int32_t* __restrict lv) {
    if (isIdentityRemap(p)) {
        identityX(n, tx0, dtx, uScale, lu);
        identityY(n, ty, p.aspect, vSize, lv);
        return;
    }
    const float ay = (ty * 2.f - 1.f) * p.aspect;
    const float ay2 = ay * ay;
    const float a2 = p.aspect * p.aspect;
    const float zoom = 1.f + p.k1 * a2 + p.k2 * a2 * a2;
    const float yScale = zoom / p.aspect;
    const float vMax = vSize - 1.f;
    const float k1 = p.k1, k2 = p.k2;
    for (int i = 0; i < n; i++) {
        const float ax = (tx0 + (float) i * dtx) * 2.f - 1.f;
        const float r2 = ax * ax + ay2;
        const float inv = 1.f / (1.f + k1 * r2 + k2 * r2 * r2);
        const float ux = fminf(fmaxf(ax * inv * zoom * 0.5f + 0.5f, 0.f), 1.f);
        const float uy = fminf(fmaxf(ay * inv * yScale * 0.5f + 0.5f, 0.f), 1.f);
        lu[i] = (int32_t) floorf((ux * uScale - 0.5f) * 256.f + 0.5f);
        const float vt = fminf(fmaxf(uy * vSize - 0.5f, 0.f), vMax);
        lv[i] = (int32_t) (vt * 256.f + 0.5f);
    }
}

static inline int clampFx(int v, int maxFx) { return v < 0 ? 0 : (v > maxFx ? maxFx : v); }

// GL_LINEAR on one plane at 8.8 fixed texel coords (already clamped).
static inline uint8_t sample1(const uint8_t* base, size_t row, int pix, int uf, int vf,
                              int w, int h) {
    const int x0 = uf >> 8, y0 = vf >> 8, fx = uf & 255, fy = vf & 255;
    const int x1 = x0 + 1 < w ? x0 + 1 : x0;
    const int y1 = y0 + 1 < h ? y0 + 1 : y0;
    const uint8_t* r0 = base + (size_t) y0 * row;
    const uint8_t* r1 = base + (size_t) y1 * row;
    const int a = r0[(size_t) x0 * pix], b = r0[(size_t) x1 * pix];
    const int c = r1[(size_t) x0 * pix], d = r1[(size_t) x1 * pix];
    const int top = a * 256 + (b - a) * fx;
    const int bot = c * 256 + (d - c) * fx;
    return (uint8_t) ((top * 256 + (bot - top) * fy + 32768) >> 16);
}

struct RemapSource {
    Planes pl;
    int w;
    int h;
};

static inline uint16_t load16(const uint8_t* p) {
    uint16_t v;
    memcpy(&v, p, 2);
    return v;
}

static inline uint32_t load32(const uint8_t* p) {
    uint32_t v;
    memcpy(&v, p, 4);
    return v;
}

static inline int lerp2(int a, int b, int c, int d, int fx, int fy) {
    const int top = a * 256 + (b - a) * fx;
    const int bot = c * 256 + (d - c) * fx;
    return (top * 256 + (bot - top) * fy + 32768) >> 16;
}

// Separable maps have one vertical coordinate for the whole row. Keep the
// same single-rounding bilinear math, but hoist the source row addresses.
struct FixedSampleRow {
    const uint8_t* a;
    const uint8_t* b;
    int width;
    int pix;
    int fy;

    FixedSampleRow(const uint8_t* base, size_t stride, int w, int h, int pixel, int vf)
        : a(base + (size_t) (vf >> 8) * stride),
          b((vf >> 8) < h - 1 ? a + stride : a), width(w), pix(pixel), fy(vf & 255) {}

    uint8_t sample(int uf) const {
        const int x = uf >> 8, fx = uf & 255;
        const size_t i = (size_t) x * pix;
        const size_t j = x < width - 1 ? i + pix : i;
        return (uint8_t) lerp2(a[i], a[j], b[i], b[j], fx, fy);
    }
};

// FixedY reads only lv[0]; the general sampler reads one coordinate per pixel.
// __restrict: byte stores could otherwise alias the coordinate rows and force reloads.
template<bool FixedY = false>
static void gatherY(const RemapSource& sr, int n, const int32_t* __restrict lu,
                    const int32_t* __restrict lv, int offFx, uint8_t* __restrict out) {
    const int w = sr.w, h = sr.h;
    const int uMax = (w - 1) * 256;
    const uint8_t* base = sr.pl.y;
    const size_t row = (size_t) sr.pl.rowY;
    int i = 0;
#ifdef OD_REMAP_NEON
    // 8 pixels on one source row within a 16-byte window: two loads + table
    // lookups; the blend is the same integer math as lerp2(), so bit-identical.
    const int32x4_t vOff = vdupq_n_s32(offFx), vZero = vdupq_n_s32(0), vMax = vdupq_n_s32(uMax);
    const int32x4_t v255 = vdupq_n_s32(255);
    const uint16x8_t v256 = vdupq_n_u16(256);
    const uint32x4_t vRound = vdupq_n_u32(32768);
    for (; i + 8 <= n; i += 8) {
        const int32x4_t u0 = vminq_s32(vmaxq_s32(vaddq_s32(vld1q_s32(lu + i), vOff), vZero), vMax);
        const int32x4_t u1 = vminq_s32(vmaxq_s32(vaddq_s32(vld1q_s32(lu + i + 4), vOff), vZero), vMax);
        const int32x4_t v0 = FixedY ? vdupq_n_s32(lv[0]) : vld1q_s32(lv + i);
        const int32x4_t v1 = FixedY ? v0 : vld1q_s32(lv + i + 4);
        const int32x4_t ya = vshrq_n_s32(v0, 8), yb = vshrq_n_s32(v1, 8);
        const int yMin = FixedY ? lv[0] >> 8 : vminvq_s32(vminq_s32(ya, yb));
        const int yMax = FixedY ? yMin : vmaxvq_s32(vmaxq_s32(ya, yb));
        const int32x4_t xa = vshrq_n_s32(u0, 8), xb = vshrq_n_s32(u1, 8);
        const int xMin = vminvq_s32(vminq_s32(xa, xb));
        const int xMax = vmaxvq_s32(vmaxq_s32(xa, xb));
        // Up to two source rows: lanes on yMin + 1 take the next row pair.
        if (yMax - yMin > 1 || yMax >= h - 1 || xMax - xMin > 14 || xMin + 16 > w) {
            for (int k = i; k < i + 8; k++) {
                out[k] = sample1(base, row, 1, clampFx(lu[k] + offFx, uMax), lv[FixedY ? 0 : k], w, h);
            }
            continue;
        }
        const uint8_t* r0 = base + (size_t) yMin * row + xMin;
        const uint8_t* r2 = !FixedY && yMin + 2 <= h - 1 ? r0 + 2 * row : r0 + row;  // unused if one row
        const uint8x16_t row0 = vld1q_u8(r0), row1 = vld1q_u8(r0 + row), row2 = vld1q_u8(r2);
        const int32x4_t xm = vdupq_n_s32(xMin);
        const uint16x8_t idx16 = vcombine_u16(vmovn_u32(vreinterpretq_u32_s32(vsubq_s32(xa, xm))),
                                              vmovn_u32(vreinterpretq_u32_s32(vsubq_s32(xb, xm))));
        const uint8x8_t ia = vmovn_u16(idx16), ib = vadd_u8(ia, vdup_n_u8(1));
        const int32x4_t yN = vdupq_n_s32(yMin + 1);
        const uint8x8_t next = FixedY ? vdup_n_u8(0) :
                vmovn_u16(vcombine_u16(vmovn_u32(vceqq_s32(ya, yN)), vmovn_u32(vceqq_s32(yb, yN))));
        const uint8x8_t a0 = vqtbl1_u8(row0, ia), b0 = vqtbl1_u8(row0, ib);
        const uint8x8_t a1 = vqtbl1_u8(row1, ia), b1 = vqtbl1_u8(row1, ib);
        const uint8x8_t a2 = vqtbl1_u8(row2, ia), b2 = vqtbl1_u8(row2, ib);
        const uint16x8_t a = vmovl_u8(vbsl_u8(next, a1, a0)), b = vmovl_u8(vbsl_u8(next, b1, b0));
        const uint16x8_t c = vmovl_u8(vbsl_u8(next, a2, a1)), d = vmovl_u8(vbsl_u8(next, b2, b1));
        const uint16x8_t fx = vcombine_u16(vmovn_u32(vreinterpretq_u32_s32(vandq_s32(u0, v255))),
                                           vmovn_u32(vreinterpretq_u32_s32(vandq_s32(u1, v255))));
        const uint16x8_t fy = vcombine_u16(vmovn_u32(vreinterpretq_u32_s32(vandq_s32(v0, v255))),
                                           vmovn_u32(vreinterpretq_u32_s32(vandq_s32(v1, v255))));
        const uint16x8_t ifx = vsubq_u16(v256, fx), ify = vsubq_u16(v256, fy);
        const uint16x8_t top = vmlaq_u16(vmulq_u16(a, ifx), b, fx);
        const uint16x8_t bot = vmlaq_u16(vmulq_u16(c, ifx), d, fx);
        uint32x4_t lo = vmlal_u16(vmull_u16(vget_low_u16(top), vget_low_u16(ify)), vget_low_u16(bot), vget_low_u16(fy));
        uint32x4_t hi = vmlal_high_u16(vmull_high_u16(top, ify), bot, fy);
        lo = vaddq_u32(lo, vRound);
        hi = vaddq_u32(hi, vRound);
        vst1_u8(out + i, vmovn_u16(vcombine_u16(vshrn_n_u32(lo, 16), vshrn_n_u32(hi, 16))));
    }
#endif
    if (FixedY) {
        const FixedSampleRow fixed(base, row, w, h, 1, lv[0]);
        for (; i < n; i++) out[i] = fixed.sample(clampFx(lu[i] + offFx, uMax));
        return;
    }
    for (; i < n; i++) {
        const int uf = clampFx(lu[i] + offFx, uMax);
        const int vf = lv[i];
        const int x0 = uf >> 8, y0 = vf >> 8;
        if (x0 < w - 1 && y0 < h - 1) {
            // Both neighbours of each row in one little-endian 16-bit load.
            const uint8_t* r0 = base + (size_t) y0 * row + x0;
            const uint32_t t = load16(r0), b = load16(r0 + row);
            out[i] = (uint8_t) lerp2((int) (t & 255), (int) (t >> 8), (int) (b & 255), (int) (b >> 8),
                                     uf & 255, vf & 255);
        } else {
            out[i] = sample1(base, row, 1, uf, vf, w, h);
        }
    }
}

template<bool FixedY = false>
static void gatherUV(const RemapSource& sr, int n, const int32_t* __restrict lu,
                     const int32_t* __restrict lv, int offFx, uint8_t* __restrict ou,
                     uint8_t* __restrict ov, int dPix) {
    const int cw = sr.w / 2, ch = sr.h / 2;
    const int uMax = (cw - 1) * 256;
    const size_t row = (size_t) sr.pl.rowUV;
    const int pix = sr.pl.pixUV;
    const Chroma order = chromaOf(sr.pl.u, sr.pl.v, pix);
    if (order == kStrided) {
        if (FixedY) {
            const FixedSampleRow u(sr.pl.u, row, cw, ch, pix, lv[0]);
            const FixedSampleRow v(sr.pl.v, row, cw, ch, pix, lv[0]);
            for (int i = 0; i < n; i++) {
                const int uf = clampFx(lu[i] + offFx, uMax);
                ou[(size_t) i * dPix] = u.sample(uf);
                ov[(size_t) i * dPix] = v.sample(uf);
            }
            return;
        }
        for (int i = 0; i < n; i++) {
            const int uf = clampFx(lu[i] + offFx, uMax);
            ou[(size_t) i * dPix] = sample1(sr.pl.u, row, pix, uf, lv[i], cw, ch);
            ov[(size_t) i * dPix] = sample1(sr.pl.v, row, pix, uf, lv[i], cw, ch);
        }
        return;
    }
    // Interleaved chroma: one 32-bit load per row covers both samples' U and V.
    const uint8_t* base = order == kNv12 ? sr.pl.u : sr.pl.v;
    const int uShift = order == kNv12 ? 0 : 8;  // byte of U inside each pair
    const int vShift = 8 - uShift;
    int i = 0;
#ifdef OD_REMAP_NEON
    const int32x4_t vOff = vdupq_n_s32(offFx), vZero = vdupq_n_s32(0), vMax = vdupq_n_s32(uMax);
    const int32x4_t v255 = vdupq_n_s32(255);
    const uint16x8_t v256 = vdupq_n_u16(256);
    const uint32x4_t vRound = vdupq_n_u32(32768);
    const uint8_t uByte = (uint8_t) (uShift / 8), vByte = (uint8_t) (vShift / 8);
    const bool dstNv12 = dPix == 2 && ov == ou + 1;
    const bool dstNv21 = dPix == 2 && ou == ov + 1;
    const bool dstPlanar = dPix == 1;
    for (; i + 8 <= n; i += 8) {
        const int32x4_t u0 = vminq_s32(vmaxq_s32(vaddq_s32(vld1q_s32(lu + i), vOff), vZero), vMax);
        const int32x4_t u1 = vminq_s32(vmaxq_s32(vaddq_s32(vld1q_s32(lu + i + 4), vOff), vZero), vMax);
        const int32x4_t v0 = FixedY ? vdupq_n_s32(lv[0]) : vld1q_s32(lv + i);
        const int32x4_t v1 = FixedY ? v0 : vld1q_s32(lv + i + 4);
        const int32x4_t ya = vshrq_n_s32(v0, 8), yb = vshrq_n_s32(v1, 8);
        const int yMin = FixedY ? lv[0] >> 8 : vminvq_s32(vminq_s32(ya, yb));
        const int yMax = FixedY ? yMin : vmaxvq_s32(vmaxq_s32(ya, yb));
        const int32x4_t xa = vshrq_n_s32(u0, 8), xb = vshrq_n_s32(u1, 8);
        const int xMin = vminvq_s32(vminq_s32(xa, xb));
        const int xMax = vmaxvq_s32(vmaxq_s32(xa, xb));
        if (yMax - yMin > 1 || yMax >= ch - 1 || xMax - xMin > 14
                || xMin + 16 > cw || !(dstNv12 || dstNv21 || dstPlanar)) {
            for (int k = i; k < i + 8; k++) {
                const int uf = clampFx(lu[k] + offFx, uMax);
                ou[(size_t) k * dPix] = sample1(sr.pl.u, row, pix, uf, lv[FixedY ? 0 : k], cw, ch);
                ov[(size_t) k * dPix] = sample1(sr.pl.v, row, pix, uf, lv[FixedY ? 0 : k], cw, ch);
            }
            continue;
        }
        // 16 interleaved samples = 32 bytes per row.
        const uint8_t* r0 = base + (size_t) yMin * row + (size_t) xMin * 2;
        const uint8_t* r2 = !FixedY && yMin + 2 <= ch - 1 ? r0 + 2 * row : r0 + row;
        uint8x16x2_t t0, t1, t2;
        t0.val[0] = vld1q_u8(r0);
        t0.val[1] = vld1q_u8(r0 + 16);
        t1.val[0] = vld1q_u8(r0 + row);
        t1.val[1] = vld1q_u8(r0 + row + 16);
        t2.val[0] = vld1q_u8(r2);
        t2.val[1] = vld1q_u8(r2 + 16);
        const int32x4_t yN = vdupq_n_s32(yMin + 1);
        const uint8x8_t next = FixedY ? vdup_n_u8(0) :
                vmovn_u16(vcombine_u16(vmovn_u32(vceqq_s32(ya, yN)), vmovn_u32(vceqq_s32(yb, yN))));
        const int32x4_t xm = vdupq_n_s32(xMin);
        const uint16x8_t idx16 = vcombine_u16(vmovn_u32(vreinterpretq_u32_s32(vsubq_s32(xa, xm))),
                                              vmovn_u32(vreinterpretq_u32_s32(vsubq_s32(xb, xm))));
        const uint8x8_t pairA = vshl_n_u8(vmovn_u16(idx16), 1);   // byte of sample x0
        const uint8x8_t pairB = vadd_u8(pairA, vdup_n_u8(2));     // byte of sample x0 + 1
        const uint16x8_t fx = vcombine_u16(vmovn_u32(vreinterpretq_u32_s32(vandq_s32(u0, v255))),
                                           vmovn_u32(vreinterpretq_u32_s32(vandq_s32(u1, v255))));
        const uint16x8_t fy = vcombine_u16(vmovn_u32(vreinterpretq_u32_s32(vandq_s32(v0, v255))),
                                           vmovn_u32(vreinterpretq_u32_s32(vandq_s32(v1, v255))));
        const uint16x8_t ifx = vsubq_u16(v256, fx), ify = vsubq_u16(v256, fy);
        uint8x8_t res[2];
        for (int plane = 0; plane < 2; plane++) {
            const uint8x8_t off8 = vdup_n_u8(plane == 0 ? uByte : vByte);
            const uint8x8_t ia = vadd_u8(pairA, off8), ib = vadd_u8(pairB, off8);
            const uint8x8_t a0 = vqtbl2_u8(t0, ia), b0 = vqtbl2_u8(t0, ib);
            const uint8x8_t a1 = vqtbl2_u8(t1, ia), b1 = vqtbl2_u8(t1, ib);
            const uint8x8_t a2 = vqtbl2_u8(t2, ia), b2 = vqtbl2_u8(t2, ib);
            const uint16x8_t a = vmovl_u8(vbsl_u8(next, a1, a0)), b = vmovl_u8(vbsl_u8(next, b1, b0));
            const uint16x8_t c = vmovl_u8(vbsl_u8(next, a2, a1)), d = vmovl_u8(vbsl_u8(next, b2, b1));
            const uint16x8_t top = vmlaq_u16(vmulq_u16(a, ifx), b, fx);
            const uint16x8_t bot = vmlaq_u16(vmulq_u16(c, ifx), d, fx);
            uint32x4_t lo = vmlal_u16(vmull_u16(vget_low_u16(top), vget_low_u16(ify)), vget_low_u16(bot), vget_low_u16(fy));
            uint32x4_t hi = vmlal_high_u16(vmull_high_u16(top, ify), bot, fy);
            lo = vaddq_u32(lo, vRound);
            hi = vaddq_u32(hi, vRound);
            res[plane] = vmovn_u16(vcombine_u16(vshrn_n_u32(lo, 16), vshrn_n_u32(hi, 16)));
        }
        if (dstPlanar) {
            vst1_u8(ou + i, res[0]);
            vst1_u8(ov + i, res[1]);
        } else {
            uint8x8x2_t pair;
            pair.val[0] = dstNv12 ? res[0] : res[1];
            pair.val[1] = dstNv12 ? res[1] : res[0];
            vst2_u8((dstNv12 ? ou : ov) + (size_t) i * 2, pair);
        }
    }
#endif
    if (FixedY) {
        const FixedSampleRow u(sr.pl.u, row, cw, ch, pix, lv[0]);
        const FixedSampleRow v(sr.pl.v, row, cw, ch, pix, lv[0]);
        for (; i < n; i++) {
            const int uf = clampFx(lu[i] + offFx, uMax);
            ou[(size_t) i * dPix] = u.sample(uf);
            ov[(size_t) i * dPix] = v.sample(uf);
        }
        return;
    }
    for (; i < n; i++) {
        const int uf = clampFx(lu[i] + offFx, uMax);
        const int vf = lv[i];
        const int x0 = uf >> 8, y0 = vf >> 8;
        if (x0 < cw - 1 && y0 < ch - 1) {
            const uint8_t* r0 = base + (size_t) y0 * row + (size_t) x0 * 2;
            const uint32_t t = load32(r0), b = load32(r0 + row);
            const int fx = uf & 255, fy = vf & 255;
            ou[(size_t) i * dPix] = (uint8_t) lerp2((int) ((t >> uShift) & 255), (int) ((t >> (16 + uShift)) & 255),
                                                    (int) ((b >> uShift) & 255), (int) ((b >> (16 + uShift)) & 255), fx, fy);
            ov[(size_t) i * dPix] = (uint8_t) lerp2((int) ((t >> vShift) & 255), (int) ((t >> (16 + vShift)) & 255),
                                                    (int) ((b >> vShift) & 255), (int) ((b >> (16 + vShift)) & 255), fx, fy);
        } else {
            ou[(size_t) i * dPix] = sample1(sr.pl.u, row, pix, uf, vf, cw, ch);
            ov[(size_t) i * dPix] = sample1(sr.pl.v, row, pix, uf, vf, cw, ch);
        }
    }
}

// Only bypass the general sampler when every horizontal coordinate is an
// in-bounds integer texel in a contiguous run. Check once per column plan,
// not once per output row; rounding and nonstandard offsets retain the fallback.
static int contiguousStart(const int32_t* u, int n, int off, int width) {
    if (n <= 0 || n > width) return -1;
    const int64_t first = (int64_t) u[0] + off;
    if (first < 0 || (first & 255) || first / 256 > width - n) return -1;
    for (int i = 1; i < n; i++) {
        if ((int64_t) u[i] + off != first + (int64_t) i * 256) return -1;
    }
    return (int) (first / 256);
}

static inline uint8_t verticalSample(uint8_t a, uint8_t b, int fy) {
    return (uint8_t) ((a * (256 - fy) + b * fy + 128) >> 8);
}

// With fx=0, lerp2 reduces exactly to one rounded vertical blend. No gather,
// coordinate loads or per-pixel address calculations are needed for packed rows.
static void verticalBytes(const uint8_t* a, const uint8_t* b, uint8_t* out,
                          int n, int fy, bool swap = false) {
    if (fy == 0 || a == b) {
        if (swap) swapPairs(out, a, n);
        else memcpy(out, a, (size_t) n);
        return;
    }
    int i = 0;
#ifdef OD_CPU_MOSAIC_NEON
    for (; i + 8 <= n; i += 8) {
        const uint16x8_t av = vmovl_u8(vld1_u8(a + i));
        const uint16x8_t bv = vmovl_u8(vld1_u8(b + i));
        const uint16x8_t sum = vmlaq_n_u16(vmulq_n_u16(av, (uint16_t) (256 - fy)),
                                           bv, (uint16_t) fy);
        const uint8x8_t blended = vrshrn_n_u16(sum, 8);
        vst1_u8(out + i, swap ? vrev16_u8(blended) : blended);
    }
#endif
    for (; i < n; i++) {
        const int j = swap ? (i ^ 1) : i;  // swap is only used for complete UV pairs
        out[i] = verticalSample(a[j], b[j], fy);
    }
}

static void verticalY(const RemapSource& sr, int x, int n, int vf, uint8_t* out) {
    const int y = vf >> 8;
    const uint8_t* a = sr.pl.y + (size_t) y * sr.pl.rowY + x;
    const uint8_t* b = y < sr.h - 1 ? a + sr.pl.rowY : a;
    verticalBytes(a, b, out, n, vf & 255);
}

static void verticalUV(const RemapSource& sr, int x, int n, int vf,
                       uint8_t* ou, uint8_t* ov, int dPix) {
    const Planes& s = sr.pl;
    const int y = vf >> 8, fy = vf & 255;
    const size_t start = (size_t) y * s.rowUV + (size_t) x * s.pixUV;
    const size_t next = y < sr.h / 2 - 1 ? (size_t) s.rowUV : 0;
    const uint8_t* au = s.u + start;
    const uint8_t* av = s.v + start;
    const Chroma sc = chromaOf(au, av, s.pixUV);
    const Chroma dc = chromaOf(ou, ov, dPix);
    if (sc != kStrided && dc != kStrided) {
        const uint8_t* a = sc == kNv12 ? au : av;
        verticalBytes(a, a + next, dc == kNv12 ? ou : ov, n * 2, fy, sc != dc);
    } else if (s.pixUV == 1 && dPix == 1) {
        verticalBytes(au, au + next, ou, n, fy);
        verticalBytes(av, av + next, ov, n, fy);
    } else if (fy == 0 || next == 0) {
        copyChromaRow(au, av, s.pixUV, sc, ou, ov, dPix, dc, n);
    } else {
        // Mixed or unusual strides: blend only actual U/V samples, not padding.
        for (int i = 0; i < n; i++) {
            const size_t si = (size_t) i * s.pixUV, di = (size_t) i * dPix;
            ou[di] = verticalSample(au[si], au[next + si], fy);
            ov[di] = verticalSample(av[si], av[next + si], fy);
        }
    }
}

// Linear (windshield) coords: u = (x0 + i) * du + u0 texels, fixed v.
static void linearX(int n, float u0, float du, int uSize, int32_t* lu) {
    const float uMax = (float) (uSize - 1);
    for (int i = 0; i < n; i++) {
        const float u = fminf(fmaxf(u0 + (float) i * du, 0.f), uMax);
        lu[i] = (int32_t) (u * 256.f + 0.5f);
    }
}

static void linearY(int n, float vTex, int vSize, int32_t* lv) {
    float vt = fminf(fmaxf(vTex, 0.f), (float) (vSize - 1));
    std::fill_n(lv, n, (int32_t) (vt * 256.f + 0.5f));
}

static inline int offsetFx(float off, int size) {
    return (int) floorf(off * (float) size * 256.f + 0.5f);
}

// 2x2 grid: one coordinate row per tile row serves all four quadrants.
static void gridRows(const RemapSpec& p, const RemapSource& sr, const Planes& d,
                     int ty0, int ty1) {
    const int tw = p.outW / 2, th = p.outH / 2;
    int32_t lu[kMaxRow], lv[kMaxRow], chromaLu[kMaxRow / 4];
    const bool identity = isIdentityRemap(p);
    if (identity) {
        identityX(tw, 0.5f / (float) tw, 1.f / (float) tw, 0.25f * (float) sr.w, lu);
        identityX(tw / 2, 1.f / (float) tw, 2.f / (float) tw,
                  0.25f * (float) (sr.w / 2), chromaLu);
    }
    const int offY[4] = {offsetFx(p.off[0], sr.w), offsetFx(p.off[1], sr.w),
                         offsetFx(p.off[2], sr.w), offsetFx(p.off[3], sr.w)};
    const int offC[4] = {offsetFx(p.off[0], sr.w / 2), offsetFx(p.off[1], sr.w / 2),
                         offsetFx(p.off[2], sr.w / 2), offsetFx(p.off[3], sr.w / 2)};
    int startY[4] = {-1, -1, -1, -1}, startC[4] = {-1, -1, -1, -1};
    if (identity) {
        for (int q = 0; q < 4; q++) {
            startY[q] = contiguousStart(lu, tw, offY[q], sr.w);
            startC[q] = contiguousStart(chromaLu, tw / 2, offC[q], sr.w / 2);
        }
    }
    for (int y = ty0; y < ty1; y++) {
        const float ty = ((float) y + 0.5f) / (float) th;
        if (identity) identityY(1, ty, p.aspect, (float) sr.h, lv);
        else rectifyRow(p, tw, 0.5f / (float) tw, 1.f / (float) tw, ty,
                        0.25f * (float) sr.w, (float) sr.h, lu, lv);
        for (int q = 0; q < 4; q++) {
            uint8_t* out = d.y + (size_t) (y + (q >> 1) * th) * d.rowY + (size_t) (q & 1) * tw;
            if (startY[q] >= 0) verticalY(sr, startY[q], tw, lv[0], out);
            else if (identity) gatherY<true>(sr, tw, lu, lv, offY[q], out);
            else gatherY(sr, tw, lu, lv, offY[q], out);
        }
        if (y & 1) continue;
        const int yc = y / 2;
        const int tcw = tw / 2, tch = th / 2;
        const float cty = (float) (2 * yc + 1) / (float) th;
        if (identity) identityY(1, cty, p.aspect, (float) (sr.h / 2), lv);
        else rectifyRow(p, tcw, 1.f / (float) tw, 2.f / (float) tw, cty,
                        0.25f * (float) (sr.w / 2), (float) (sr.h / 2), lu, lv);
        for (int q = 0; q < 4; q++) {
            const size_t rowOff = (size_t) (yc + (q >> 1) * tch) * d.rowUV;
            const size_t colOff = (size_t) ((q & 1) * tcw) * d.pixUV;
            uint8_t* ou = d.u + rowOff + colOff;
            uint8_t* ov = d.v + rowOff + colOff;
            if (startC[q] >= 0) verticalUV(sr, startC[q], tcw, lv[0], ou, ov, d.pixUV);
            else if (identity) gatherUV<true>(sr, tcw, chromaLu, lv, offC[q], ou, ov, d.pixUV);
            else gatherUV(sr, tcw, lu, lv, offC[q], ou, ov, d.pixUV);
        }
    }
}

// Bottom-band cell bounds: cell c covers samples [b[c], b[c + 1]), the cell
// being floor(3 * vx) with vx at each sample's centre (same test as the shader).
struct CellBounds {
    int luma[4];
    int chroma[4];
};

static void cellBounds(int outW, CellBounds* cb) {
    for (int plane = 0; plane < 2; plane++) {
        const int n = plane ? outW / 2 : outW;
        int* b = plane ? cb->chroma : cb->luma;
        int c = 0;
        b[0] = 0;
        for (int i = 0; i < n; i++) {
            const float vx = plane ? (float) (2 * i + 1) / (float) outW
                                   : ((float) i + 0.5f) / (float) outW;
            int cell = (int) floorf(vx * 3.f);
            if (cell > 2) cell = 2;
            while (c < cell) b[++c] = i;
        }
        while (c < 3) b[++c] = n;
    }
}

// Horizontal sampling is independent of row for an identity strip remap
// and for the linear windshield band. Build once per frame, shared read-only
// by all bands. Bottom columns are concatenated in their original cell order.
struct DashcamColumns {
    int32_t topY[kMaxRow], topC[kMaxRow / 2];
    int32_t bottomY[kMaxRow], bottomC[kMaxRow / 2];
    bool topReady, bottomReady;
    int topStart[2], bottomStart[2][3];  // luma/chroma, then cell
};

static void dashcamColumns(const RemapSpec& p, const RemapSource* src,
                           const CellBounds& cb, DashcamColumns& cols) {
    cols.bottomReady = isIdentityRemap(p);
    cols.topReady = cols.bottomReady || p.windshield;
    for (int div : {1, 2}) {
        const int n = p.outW / div;
        const float vx0 = (0.5f * (float) div) / (float) p.outW;
        const float dvx = (float) div / (float) p.outW;
        const int sw = src[0].w / div;
        int32_t* top = div == 1 ? cols.topY : cols.topC;
        int32_t* bottom = div == 1 ? cols.bottomY : cols.bottomC;
        if (p.windshield) {
            const int ww = src[1].w / div;
            linearX(n, vx0 * (float) ww - 0.5f, dvx * (float) ww, ww, top);
        } else if (cols.topReady) {
            identityX(n, vx0, dvx, 0.25f * (float) sw, top);
        }
        const int plane = div - 1;
        const int topWidth = p.windshield ? src[1].w / div : sw;
        const int topOff = p.windshield ? 0 : offsetFx(p.off[0], sw);
        cols.topStart[plane] = cols.topReady
                ? contiguousStart(top, n, topOff, topWidth) : -1;
        std::fill_n(cols.bottomStart[plane], 3, -1);
        if (!cols.bottomReady) continue;
        const int* bounds = div == 1 ? cb.luma : cb.chroma;
        for (int c = 0; c < 3; c++) {
            const int a = bounds[c], b = bounds[c + 1];
            if (b <= a) continue;
            const float tx0 = (vx0 + (float) a * dvx) * 3.f - (float) c;
            identityX(b - a, tx0, dvx * 3.f, 0.25f * (float) sw, bottom + a);
            cols.bottomStart[plane][c] = contiguousStart(bottom + a, b - a,
                    offsetFx(p.off[3 - c], sw), sw);
        }
    }
}

// A CPU recording lane keeps its lens geometry for the whole session. Store
// only fixed-point coordinates, never frame pointers or pixels. Limit the
// cache to 16 MiB; prefer the entire corrected image, then the lower band.
// 16 MiB holds the lower band of the legacy 2560x1920 mosaic (~14.7 MB).
// The calling thread owns it, so a retired recording lane frees its table.
struct DashcamRemapCache {
    struct Plane {
        int first = 0, last = 0, width = 0;
        size_t offset = 0;

        uint64_t size() const { return (uint64_t) (last - first) * width * 2; }
    };
    std::unique_ptr<int32_t[]> data;
    Plane planes[2];
    int outW = 0, outH = 0, sw = 0, sh = 0, windshield = 0;
    float k1 = 0, k2 = 0, aspect = 0, split = 0;
    bool needsFill = false;

    bool matches(const RemapSpec& p, const RemapSource& sr) const {
        return outW == p.outW && outH == p.outH && sw == sr.w && sh == sr.h
                && windshield == p.windshield && k1 == p.k1 && k2 == p.k2
                && aspect == p.aspect && split == p.split;
    }

    static float rowV(const RemapSpec& p, int y, int div) {
        return div == 2 ? (float) (2 * y + 1) / (float) p.outH
                        : ((float) y + 0.5f) / (float) p.outH;
    }

    void prepare(const RemapSpec& p, const RemapSource& sr) {
        if (p.k1 == 0.f && p.k2 == 0.f) {
            data.reset();
            outW = 0;
            return;
        }
        if (matches(p, sr)) return;
        data.reset();  // release the old geometry before allocating a replacement
        needsFill = false;
        outW = p.outW; outH = p.outH; sw = sr.w; sh = sr.h;
        windshield = p.windshield; k1 = p.k1; k2 = p.k2;
        aspect = p.aspect; split = p.split;

        int bottom[2];
        for (int div : {1, 2}) {
            Plane& plane = planes[div - 1];
            plane.last = p.outH / div;
            plane.width = p.outW / div;
            int lo = 0, hi = plane.last;
            while (lo < hi) {
                const int mid = lo + (hi - lo) / 2;
                if (rowV(p, mid, div) < p.split) lo = mid + 1;
                else hi = mid;
            }
            bottom[div - 1] = lo;
            plane.first = p.windshield ? lo : 0;
        }
        constexpr size_t maxEntries = 16 * 1024 * 1024 / sizeof(int32_t);
        if (planes[0].size() + planes[1].size() > maxEntries) {
            planes[0].first = bottom[0];
            planes[1].first = bottom[1];
        }
        const uint64_t count = planes[0].size() + planes[1].size();
        if (count == 0 || count > maxEntries) return;
        planes[0].offset = 0;
        planes[1].offset = (size_t) planes[0].size();
        data.reset(new (std::nothrow) int32_t[(size_t) count]);
        // Fill rows on the existing band workers during the first frame.
        // Each band owns distinct luma/chroma rows; later frames only read.
        needsFill = data != nullptr;
    }

    int32_t* row(int y, int div) const {
        const Plane& plane = planes[div - 1];
        if (!data || y < plane.first || y >= plane.last) return nullptr;
        return data.get() + plane.offset + (size_t) (y - plane.first) * plane.width * 2;
    }
};

// Dashcam: 360 front (or windshield) on top, left / rear / right below.
static void dashcamRow(const RemapSpec& p, const RemapSource* src, const Planes& d,
                       const CellBounds& cb, const DashcamColumns& cols,
                       const DashcamRemapCache& cache, int y, bool chroma) {
    int32_t lu[kMaxRow], lv[kMaxRow];
    const int div = chroma ? 2 : 1;
    const int n = p.outW / div;
    int32_t* cached = cache.row(y, div);
    const float vy = DashcamRemapCache::rowV(p, y, div);
    // Output pixel i sits at vx = (i * div + 0.5 * div) / outW.
    const float vx0 = (0.5f * (float) div) / (float) p.outW;
    const float dvx = (float) div / (float) p.outW;
    uint8_t* oy = chroma ? nullptr : d.y + (size_t) y * d.rowY;
    uint8_t* ou = chroma ? d.u + (size_t) y * d.rowUV : nullptr;
    uint8_t* ov = chroma ? d.v + (size_t) y * d.rowUV : nullptr;
    const RemapSource& strip = src[0];
    const int sw = strip.w / div, sh = strip.h / div;
    if (vy < p.split) {
        const float ly = vy / p.split;
        if (p.windshield) {
            const RemapSource& ws = src[1];
            const int wh = ws.h / div;
            const float t = p.cropY + ly * (1.f - p.cropY * 2.f);
            const int start = cols.topStart[div - 1];
            linearY(1, t * (float) wh - 0.5f, wh, lv);
            const int32_t* u = chroma ? cols.topC : cols.topY;
            if (start >= 0) {
                if (chroma) verticalUV(ws, start, n, lv[0], ou, ov, d.pixUV);
                else verticalY(ws, start, n, lv[0], oy);
            } else if (chroma) gatherUV<true>(ws, n, u, lv, 0, ou, ov, d.pixUV);
            else gatherY<true>(ws, n, u, lv, 0, oy);
            return;
        }
        const int32_t* u = lu;
        const int32_t* v = lv;
        const int start = cols.topStart[div - 1];
        if (cols.topReady) {
            u = chroma ? cols.topC : cols.topY;
            identityY(1, ly, p.aspect, (float) sh, lv);
        } else if (cached) {
            if (cache.needsFill) rectifyRow(p, n, vx0, dvx, ly, 0.25f * (float) sw,
                                            (float) sh, cached, cached + n);
            u = cached;
            v = cached + n;
        } else {
            rectifyRow(p, n, vx0, dvx, ly, 0.25f * (float) sw, (float) sh, lu, lv);
        }
        const int off = offsetFx(p.off[0], sw);
        if (start >= 0) {
            if (chroma) verticalUV(strip, start, n, lv[0], ou, ov, d.pixUV);
            else verticalY(strip, start, n, lv[0], oy);
        } else if (cols.topReady) {
            if (chroma) gatherUV<true>(strip, n, u, lv, off, ou, ov, d.pixUV);
            else gatherY<true>(strip, n, u, lv, off, oy);
        } else if (chroma) gatherUV(strip, n, u, v, off, ou, ov, d.pixUV);
        else gatherY(strip, n, u, v, off, oy);
        return;
    }
    const float by = (vy - p.split) / (1.f - p.split);
    const float cellOff[3] = {p.off[3], p.off[2], p.off[1]};  // left, rear, right
    for (int c = 0; c < 3; c++) {
        const int* bounds = chroma ? cb.chroma : cb.luma;
        const int a = bounds[c], b = bounds[c + 1];
        if (b <= a) continue;
        const float tx0 = (vx0 + (float) a * dvx) * 3.f - (float) c;
        const int32_t* u = lu;
        const int32_t* v = lv;
        const int start = cols.bottomStart[div - 1][c];
        if (cols.bottomReady) {
            u = (chroma ? cols.bottomC : cols.bottomY) + a;
            identityY(1, by, p.aspect, (float) sh, lv);
        } else if (cached) {
            if (cache.needsFill) rectifyRow(p, b - a, tx0, dvx * 3.f, by,
                                            0.25f * (float) sw, (float) sh,
                                            cached + a, cached + n + a);
            u = cached + a;
            v = cached + n + a;
        } else {
            rectifyRow(p, b - a, tx0, dvx * 3.f, by, 0.25f * (float) sw, (float) sh, lu, lv);
        }
        const int off = offsetFx(cellOff[c], sw);
        if (start >= 0) {
            if (chroma) verticalUV(strip, start, b - a, lv[0],
                    ou + (size_t) a * d.pixUV, ov + (size_t) a * d.pixUV, d.pixUV);
            else verticalY(strip, start, b - a, lv[0], oy + a);
        } else if (cols.bottomReady) {
            if (chroma) gatherUV<true>(strip, b - a, u, lv, off,
                    ou + (size_t) a * d.pixUV, ov + (size_t) a * d.pixUV, d.pixUV);
            else gatherY<true>(strip, b - a, u, lv, off, oy + a);
        } else if (chroma) gatherUV(strip, b - a, u, v, off, ou + (size_t) a * d.pixUV, ov + (size_t) a * d.pixUV, d.pixUV);
        else gatherY(strip, b - a, u, v, off, oy + a);
    }
}

// Small persistent pool; the caller also works. Helpers prefer the slowest
// CPU cluster so the UI's big cores stay free.
class Pool {
public:
    static Pool& get() {
        static Pool* pool = new Pool();
        return *pool;
    }

    typedef void (*JobFn)(void* ctx, int job);

    // Runs fn(ctx, 0 .. jobs-1) across the helpers and the caller; returns when all are done.
    void run(int jobs, JobFn fn, void* ctx) {
        std::lock_guard<std::mutex> serial(runM_);  // one batch at a time
        {
            std::lock_guard<std::mutex> lock(m_);
            fn_ = fn;
            ctx_ = ctx;
            jobs_ = jobs;
            next_ = 0;
            remaining_ = jobs;
            gen_++;
        }
        cv_.notify_all();
        work();
        std::unique_lock<std::mutex> lock(m_);
        done_.wait(lock, [this] { return remaining_ == 0; });
        fn_ = nullptr;
        ctx_ = nullptr;
    }

private:
    Pool() {
        int helpers = (int) std::thread::hardware_concurrency() - 1;
        int little[64];
        int nLittle = slowCluster(little, 64);
        if (nLittle > 0) helpers = nLittle;
        if (helpers > 4) helpers = 4;
        if (helpers < 0) helpers = 0;
        for (int i = 0; i < helpers; i++) {
            threads_.emplace_back([this, little, nLittle] {
                pin(little, nLittle);
                loop();
            });
        }
    }

    static int slowCluster(int* cpus, int cap) {
#if defined(__ANDROID__) || defined(__linux__)
        long freq[64];
        int n = 0;
        for (int c = 0; c < 64 && c < cap; c++) {
            char path[96];
            snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", c);
            FILE* f = fopen(path, "r");
            if (!f) break;
            long v = 0;
            if (fscanf(f, "%ld", &v) != 1) v = 0;
            fclose(f);
            freq[n++] = v;
        }
        if (n < 2) return 0;
        long lo = freq[0], hi = freq[0];
        for (int i = 1; i < n; i++) {
            if (freq[i] < lo) lo = freq[i];
            if (freq[i] > hi) hi = freq[i];
        }
        if (lo <= 0 || lo == hi) return 0;
        int k = 0;
        for (int i = 0; i < n; i++) if (freq[i] == lo) cpus[k++] = i;
        return k;
#else
        (void) cpus;
        (void) cap;
        return 0;
#endif
    }

    static void pin(const int* cpus, int n) {
#if defined(__ANDROID__) || defined(__linux__)
        if (n <= 0) return;
        cpu_set_t set;
        CPU_ZERO(&set);
        for (int i = 0; i < n; i++) CPU_SET(cpus[i], &set);
        sched_setaffinity(0, sizeof(set), &set);  // best effort
#else
        (void) cpus;
        (void) n;
#endif
    }

    void loop() {
        uint64_t seen = 0;
        for (;;) {
            {
                std::unique_lock<std::mutex> lock(m_);
                cv_.wait(lock, [this, seen] { return gen_ != seen; });
                seen = gen_;
            }
            work();
        }
    }

    void work() {
        for (;;) {
            int job;
            JobFn fn;
            void* ctx;
            {
                std::lock_guard<std::mutex> lock(m_);
                if (!fn_ || next_ >= jobs_) return;
                job = next_++;
                fn = fn_;
                ctx = ctx_;
            }
            fn(ctx, job);
            std::lock_guard<std::mutex> lock(m_);
            if (--remaining_ == 0) done_.notify_all();
        }
    }

    std::mutex runM_;
    std::mutex m_;
    std::condition_variable cv_;
    std::condition_variable done_;
    JobFn fn_ = nullptr;
    void* ctx_ = nullptr;
    int jobs_ = 0;
    int next_ = 0;
    int remaining_ = 0;
    uint64_t gen_ = 0;
    std::vector<std::thread> threads_;
};

static bool planesFit(const Planes& pl, int w, int h) {
    if (!pl.y || !pl.u || !pl.v || w < 2 || h < 2 || (w & 1) || (h & 1)) return false;
    if (pl.rowY < w || pl.pixUV < 1 || pl.rowUV < (w / 2) * pl.pixUV) return false;
    return fits(pl.capY, h, pl.rowY, w, 1)
            && fits(pl.capU, h / 2, pl.rowUV, w / 2, pl.pixUV)
            && fits(pl.capV, h / 2, pl.rowUV, w / 2, pl.pixUV);
}

struct BandJob {
    const RemapSpec* p;
    const RemapSource* src;
    const Planes* d;
    int rows;
    int per;
    CellBounds cb;
    const DashcamColumns* columns;
    const DashcamRemapCache* cache;
};

static void gridBand(void* ctx, int b) {
    const BandJob& j = *(const BandJob*) ctx;
    const int y0 = b * j.per;
    const int y1 = y0 + j.per < j.rows ? y0 + j.per : j.rows;
    if (y0 < y1) gridRows(*j.p, j.src[0], *j.d, y0, y1);
}

static void dashcamBand(void* ctx, int b) {
    const BandJob& j = *(const BandJob*) ctx;
    const int c0 = b * j.per;
    const int c1 = c0 + j.per < j.rows ? c0 + j.per : j.rows;
    for (int yc = c0; yc < c1; yc++) {
        dashcamRow(*j.p, j.src, *j.d, j.cb, *j.columns, *j.cache, yc * 2, false);
        dashcamRow(*j.p, j.src, *j.d, j.cb, *j.columns, *j.cache, yc * 2 + 1, false);
        dashcamRow(*j.p, j.src, *j.d, j.cb, *j.columns, *j.cache, yc, true);
    }
}

// 0 = done; negative = rejected (nothing written).
int remapGrid(const RemapSpec& p, const RemapSource& strip, const RemapSource* windshield,
              const Planes& d, int bands) {
    if (p.outW < 4 || p.outH < 4 || (p.outW & 3) || (p.outH & 3)) return -50;
    if (p.outW > kMaxRow || strip.w / 2 > kMaxRow) return -50;
    if (!(p.aspect > 0.f) || p.k1 < 0.f || p.k2 < 0.f) return -51;
    if (p.layout != 0 && p.layout != 1) return -52;
    if (!(p.split > 0.f && p.split < 1.f)) return -52;
    if (!planesFit(strip.pl, strip.w, strip.h)) return -53;
    if (p.layout == 1 && p.windshield
            && (!windshield || !planesFit(windshield->pl, windshield->w, windshield->h))) return -54;
    if (!planesFit(d, p.outW, p.outH)) return -55;
    RemapSource src[2] = {strip, strip};
    if (p.layout == 1 && p.windshield) src[1] = *windshield;
    if (bands < 1) bands = 1;
    thread_local DashcamRemapCache cache;
    BandJob job;
    job.p = &p;
    job.src = src;
    job.d = &d;
    if (p.layout == 0) {
        cache.data.reset();
        cache.outW = 0;
        // Bands of tile rows, even-aligned so each owns its chroma rows.
        const int th = p.outH / 2;
        job.rows = th;
        job.per = (((th + bands - 1) / bands) + 1) & ~1;
        Pool::get().run(bands, gridBand, &job);
    } else {
        const int ch = p.outH / 2;
        job.rows = ch;
        job.per = (ch + bands - 1) / bands;  // chroma rows per band
        cellBounds(p.outW, &job.cb);
        DashcamColumns columns;
        dashcamColumns(p, src, job.cb, columns);
        job.columns = &columns;
        cache.prepare(p, strip);
        job.cache = &cache;
        Pool::get().run(bands, dashcamBand, &job);
        cache.needsFill = false;
    }
    return 0;
}

}  // namespace odcpu

namespace {

bool planesFrom(JNIEnv* env, jobject y, jobject u, jobject v, jint rowY, jint rowUV, jint pixUV,
                odcpu::Planes* out) {
    if (!y || !u || !v) return false;
    out->y = (uint8_t*) env->GetDirectBufferAddress(y);
    out->u = (uint8_t*) env->GetDirectBufferAddress(u);
    out->v = (uint8_t*) env->GetDirectBufferAddress(v);
    const jlong cy = env->GetDirectBufferCapacity(y);
    const jlong cu = env->GetDirectBufferCapacity(u);
    const jlong cv = env->GetDirectBufferCapacity(v);
    if (!out->y || !out->u || !out->v || cy <= 0 || cu <= 0 || cv <= 0) return false;
    out->capY = (size_t) cy;
    out->capU = (size_t) cu;
    out->capV = (size_t) cv;
    out->rowY = rowY;
    out->rowUV = rowUV;
    out->pixUV = pixUV;
    return true;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_CpuMosaicCompositor_nativeVersion(JNIEnv*, jclass) {
    return 1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_overdrive_app_surveillance_CpuMosaicCompositor_nativeCompose(
        JNIEnv* env, jclass,
        jobject sy, jobject su, jobject sv, jint sRowY, jint sRowUV, jint sPixUV,
        jint srcW, jint srcH,
        jobject dy, jobject du, jobject dv, jint dRowY, jint dRowUV, jint dPixUV,
        jint dstW, jint dstH,
        jint x0, jint x1, jint x2, jint x3, jint camW, jint camH) {
    odcpu::Planes s, d;
    if (!planesFrom(env, sy, su, sv, sRowY, sRowUV, sPixUV, &s)) return -1;
    if (!planesFrom(env, dy, du, dv, dRowY, dRowUV, dPixUV, &d)) return -2;
    const int xs[4] = {x0, x1, x2, x3};
    const int64_t t0 = odcpu::nowNs();
    const int rc = odcpu::composeGrid(s, srcW, srcH, d, dstW, dstH, xs, camW, camH);
    if (rc != 0) return rc;
    return (jlong) (odcpu::nowNs() - t0);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_CpuMosaicCompositor_nativeOverlayBytes(
        JNIEnv*, jclass, jint w, jint h) {
    return (jint) odcpu::overlayBytes(w, h);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_surveillance_CpuMosaicCompositor_nativePrepareOverlay(
        JNIEnv* env, jclass, jobject rgba, jint bw, jint bh, jint rowBytes,
        jobject table, jint w, jint h) {
    if (!rgba || !table) return JNI_FALSE;
    const uint8_t* src = (const uint8_t*) env->GetDirectBufferAddress(rgba);
    uint8_t* out = (uint8_t*) env->GetDirectBufferAddress(table);
    const jlong srcCap = env->GetDirectBufferCapacity(rgba);
    const jlong outCap = env->GetDirectBufferCapacity(table);
    if (!src || !out || srcCap <= 0 || outCap <= 0) return JNI_FALSE;
    return odcpu::prepareOverlay(src, (size_t) srcCap, bw, bh, rowBytes, out, (size_t) outCap, w, h)
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_overdrive_app_surveillance_CpuMosaicCompositor_nativeBlendOverlay(
        JNIEnv* env, jclass, jobject table,
        jobject dy, jobject du, jobject dv, jint dRowY, jint dRowUV, jint dPixUV,
        jint dstW, jint dstH) {
    if (!table) return -40;
    const uint8_t* t = (const uint8_t*) env->GetDirectBufferAddress(table);
    const jlong cap = env->GetDirectBufferCapacity(table);
    if (!t || cap <= 0) return -41;
    odcpu::Planes d;
    if (!planesFrom(env, dy, du, dv, dRowY, dRowUV, dPixUV, &d)) return -42;
    return odcpu::blendOverlay(t, (size_t) cap, d, dstW, dstH);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_overdrive_app_surveillance_CpuMosaicCompositor_nativeRemap(
        JNIEnv* env, jclass,
        jobject sy, jobject su, jobject sv, jint sRowY, jint sRowUV, jint sPixUV,
        jint srcW, jint srcH,
        jobject wy, jobject wu, jobject wv, jint wRowY, jint wRowUV, jint wPixUV,
        jint wsW, jint wsH,
        jobject dy, jobject du, jobject dv, jint dRowY, jint dRowUV, jint dPixUV,
        jint dstW, jint dstH,
        jfloat offFront, jfloat offRight, jfloat offRear, jfloat offLeft,
        jfloat k1, jfloat k2, jfloat aspect, jint layout, jboolean windshield,
        jfloat cropY, jfloat split) {
    odcpu::RemapSource strip, ws;
    odcpu::Planes d;
    if (!planesFrom(env, sy, su, sv, sRowY, sRowUV, sPixUV, &strip.pl)) return -1;
    if (!planesFrom(env, dy, du, dv, dRowY, dRowUV, dPixUV, &d)) return -2;
    strip.w = srcW;
    strip.h = srcH;
    const bool useWs = windshield == JNI_TRUE && layout == 1;
    if (useWs) {
        if (!planesFrom(env, wy, wu, wv, wRowY, wRowUV, wPixUV, &ws.pl)) return -3;
        ws.w = wsW;
        ws.h = wsH;
    }
    odcpu::RemapSpec p;
    p.outW = dstW;
    p.outH = dstH;
    p.layout = layout;
    p.off[0] = offFront;
    p.off[1] = offRight;
    p.off[2] = offRear;
    p.off[3] = offLeft;
    p.k1 = k1;
    p.k2 = k2;
    p.aspect = aspect;
    p.windshield = useWs ? 1 : 0;
    p.cropY = cropY;
    p.split = split;
    const int64_t t0 = odcpu::nowNs();
    const int rc = odcpu::remapGrid(p, strip, useWs ? &ws : nullptr, d, 32);
    if (rc != 0) return rc;
    return (jlong) (odcpu::nowNs() - t0);
}
