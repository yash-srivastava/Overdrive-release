// Host parity fixture: compile with JDK JNI headers and -pthread, like OverlayTableTest.
#include "../../../app/src/main/cpp/camera/CpuMosaicCompositor.cpp"

#include <cassert>
#include <cstdio>
#include <random>

namespace odcpu {
// Original row-by-row coordinate generation, including the windshield path.
static void referenceLinearRow(int n, float u0, float du, float vTex, int uSize, int vSize,
                      int32_t* lu, int32_t* lv) {
    const float uMax = (float) (uSize - 1);
    float vt = fminf(fmaxf(vTex, 0.f), (float) (vSize - 1));
    const int32_t vf = (int32_t) (vt * 256.f + 0.5f);
    for (int i = 0; i < n; i++) {
        const float u = fminf(fmaxf(u0 + (float) i * du, 0.f), uMax);
        lu[i] = (int32_t) (u * 256.f + 0.5f);
        lv[i] = vf;
    }
}

static void referenceDashcamRow(const RemapSpec& p, const RemapSource* src, const Planes& d,
                       const CellBounds& cb, int y, bool chroma) {
    int32_t lu[kMaxRow], lv[kMaxRow];
    const int div = chroma ? 2 : 1;
    const int n = p.outW / div;
    const float vy = chroma ? (float) (2 * y + 1) / (float) p.outH
                            : ((float) y + 0.5f) / (float) p.outH;
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
            const int ww = ws.w / div, wh = ws.h / div;
            const float t = p.cropY + ly * (1.f - p.cropY * 2.f);
            referenceLinearRow(n, vx0 * (float) ww - 0.5f, dvx * (float) ww, t * (float) wh - 0.5f,
                      ww, wh, lu, lv);
            if (chroma) gatherUV(ws, n, lu, lv, 0, ou, ov, d.pixUV);
            else gatherY(ws, n, lu, lv, 0, oy);
            return;
        }
        rectifyRow(p, n, vx0, dvx, ly, 0.25f * (float) sw, (float) sh, lu, lv);
        const int off = offsetFx(p.off[0], sw);
        if (chroma) gatherUV(strip, n, lu, lv, off, ou, ov, d.pixUV);
        else gatherY(strip, n, lu, lv, off, oy);
        return;
    }
    const float by = (vy - p.split) / (1.f - p.split);
    const float cellOff[3] = {p.off[3], p.off[2], p.off[1]};  // left, rear, right
    for (int c = 0; c < 3; c++) {
        const int* bounds = chroma ? cb.chroma : cb.luma;
        const int a = bounds[c], b = bounds[c + 1];
        if (b <= a) continue;
        const float tx0 = (vx0 + (float) a * dvx) * 3.f - (float) c;
        rectifyRow(p, b - a, tx0, dvx * 3.f, by, 0.25f * (float) sw, (float) sh, lu, lv);
        const int off = offsetFx(cellOff[c], sw);
        if (chroma) gatherUV(strip, b - a, lu, lv, off, ou + (size_t) a * d.pixUV, ov + (size_t) a * d.pixUV, d.pixUV);
        else gatherY(strip, b - a, lu, lv, off, oy + a);
    }
}

// Padded planar, NV12 and NV21 buffers, including guard bytes in comparisons.
struct Frame {
    std::vector<uint8_t> y, u, v;
    Planes planes;

    Frame(int w, int h, int mode, int pad, std::mt19937* rng) {
        int pix = mode == 0 ? 1 : 2;
        int rowY = w + pad, rowUV = (w / 2) * pix + pad;
        y.resize((size_t) rowY * h + 16, 0xa5);
        u.resize((size_t) rowUV * (h / 2) + 16, 0xa5);
        if (mode == 0) v.resize(u.size(), 0xa5);
        if (rng) {
            for (auto* buf : {&y, &u, &v}) {
                for (uint8_t& b : *buf) b = (uint8_t) (*rng)();
            }
        }
        uint8_t* up = u.data() + (mode == 2 ? 1 : 0);
        uint8_t* vp = mode == 0 ? v.data() : u.data() + (mode == 1 ? 1 : 0);
        planes = {y.data(), up, vp, y.size(), u.size() - (mode == 2 ? 1 : 0),
                  mode == 0 ? v.size() : u.size() - (mode == 1 ? 1 : 0), rowY, rowUV, pix};
    }
};

static void check(int w, int h, int sw, int sh, int ww, int wh,
                  int sourceMode, int dstMode, bool windshield, float k1, float k2,
                  float aspect, float split, float crop, int bands, std::mt19937& rng) {
    Frame strip(sw, sh, sourceMode, 7, &rng), ws(ww, wh, (sourceMode + 1) % 3, 5, &rng);
    Frame expected(w, h, dstMode, 9, nullptr), actual(w, h, dstMode, 9, nullptr);
    RemapSource src[2] = {{strip.planes, sw, sh}, {ws.planes, ww, wh}};
    RemapSpec p = {w, h, 1, {0.f, 0.25f, 0.5f, 0.75f}, k1, k2, aspect,
                   windshield ? 1 : 0, crop, split};
    // Nonstandard but valid offsets exercise clipping at strip edges as well.
    if (rng() % 2) p.off[0] = 0.013f, p.off[3] = 0.81f;
    CellBounds cb;
    cellBounds(w, &cb);
    for (int yc = 0; yc < h / 2; yc++) {
        referenceDashcamRow(p, src, expected.planes, cb, yc * 2, false);
        referenceDashcamRow(p, src, expected.planes, cb, yc * 2 + 1, false);
        referenceDashcamRow(p, src, expected.planes, cb, yc, true);
    }
    assert(remapGrid(p, src[0], windshield ? &src[1] : nullptr, actual.planes, bands) == 0);
    assert(actual.y == expected.y && actual.u == expected.u && actual.v == expected.v);
}
// A horizontally exact grid with vertical scaling uses the new path even
// though it does not qualify for the separate 1:1 composeGrid fast path.
static void checkGrid(std::mt19937& rng) {
    const int w = 128, h = 80, sw = 256, sh = 56;
    for (int sm = 0; sm < 3; sm++) {
        for (int dm = 0; dm < 3; dm++) {
            Frame strip(sw, sh, sm, 7, &rng);
            Frame expected(w, h, dm, 9, nullptr), actual(w, h, dm, 9, nullptr);
            RemapSource sr = {strip.planes, sw, sh};
            RemapSpec p = {w, h, 0, {0.f, 0.25f, 0.5f, 0.75f}, 0.f, 0.f,
                           0.75f, 0, 0.f, 0.7f};
            for (int div : {1, 2}) {
                const int tw = w / (2 * div), th = h / (2 * div);
                for (int y = 0; y < th; y++) {
                    int32_t u[kMaxRow], v[kMaxRow];
                    const float ty = div == 1 ? ((float) y + 0.5f) / (float) th
                                              : (float) (2 * y + 1) / (float) (2 * th);
                    rectifyRow(p, tw, (0.5f * div) / (float) (w / 2),
                               (float) div / (float) (w / 2), ty,
                               0.25f * (float) (sw / div), (float) (sh / div), u, v);
                    for (int q = 0; q < 4; q++) {
                        const int row = y + (q >> 1) * th, col = (q & 1) * tw;
                        const int off = offsetFx(p.off[q], sw / div);
                        if (div == 1) {
                            gatherY(sr, tw, u, v, off, expected.planes.y +
                                    (size_t) row * expected.planes.rowY + col);
                        } else {
                            const size_t pos = (size_t) row * expected.planes.rowUV +
                                               (size_t) col * expected.planes.pixUV;
                            gatherUV(sr, tw, u, v, off, expected.planes.u + pos,
                                     expected.planes.v + pos, expected.planes.pixUV);
                        }
                    }
                }
            }
            assert(remapGrid(p, sr, nullptr, actual.planes, 32) == 0);
            assert(actual.y == expected.y && actual.u == expected.u && actual.v == expected.v);
        }
    }
}

// Compare the new packed and strided vertical paths directly with the original
// bilinear sampler, including the last row, right edge, and untouched padding.
static void checkVerticalRows(std::mt19937& rng) {
    const int32_t exact[] = {-128, 128, 384};
    const int32_t gap[] = {0, 256, 513};
    assert(contiguousStart(exact, 3, 128, 3) == 0);
    assert(contiguousStart(exact, 3, 0, 3) == -1);
    assert(contiguousStart(exact, 3, 128, 2) == -1);
    assert(contiguousStart(gap, 3, 0, 4) == -1);
    for (int srcMode = 0; srcMode < 3; srcMode++) {
        Frame strip(64, 12, srcMode, 7, &rng);
        RemapSource sr = {strip.planes, 64, 12};
        for (int dstMode = 0; dstMode < 3; dstMode++) {
            for (int fy : {0, 1, 127, 128, 255}) {
                for (int y : {0, 4, 5}) {
                    if (y == 5 && fy != 0) continue; // coordinates clamp at the edge
                    Frame expected(64, 12, dstMode, 9, nullptr);
                    Frame actual(64, 12, dstMode, 9, nullptr);
                    const int vf = y * 256 + fy;
                    const int x = 3, n = 29; // rightmost texel, SIMD tail
                    verticalY(sr, x * 2, n * 2, vf, actual.planes.y);
                    verticalUV(sr, x, n, vf, actual.planes.u, actual.planes.v,
                               actual.planes.pixUV);
                    for (int i = 0; i < n * 2; i++) {
                        expected.planes.y[i] = sample1(sr.pl.y, sr.pl.rowY, 1,
                                (x * 2 + i) * 256, vf, sr.w, sr.h);
                    }
                    for (int i = 0; i < n; i++) {
                        const int pos = i * expected.planes.pixUV;
                        expected.planes.u[pos] = sample1(sr.pl.u, sr.pl.rowUV, sr.pl.pixUV,
                                (x + i) * 256, vf, sr.w / 2, sr.h / 2);
                        expected.planes.v[pos] = sample1(sr.pl.v, sr.pl.rowUV, sr.pl.pixUV,
                                (x + i) * 256, vf, sr.w / 2, sr.h / 2);
                    }
                    assert(actual.y == expected.y && actual.u == expected.u && actual.v == expected.v);
                }
            }
        }
    }
}

}  // namespace odcpu

int main() {
    std::mt19937 rng(715);
    odcpu::checkVerticalRows(rng);
    odcpu::checkGrid(rng);
    int cases = 0;
    for (int i = 0; i < 240; ++i) {
        // Widths not divisible by three, extreme splits (including empty bands),
        // identity/nonidentity and the existing unusual-aspect fallback.
        const int w = 4 * (1 + rng() % 80), h = 4 * (1 + rng() % 32);
        const float aspects[] = {0.75f, 0.5625f, 1.3f, 0.05f, 17.f};
        const float splits[] = {0.0001f, 0.333f, 0.7f, 0.9999f};
        odcpu::check(w, h, 4 * (4 + rng() % 150), 2 * (2 + rng() % 80),
                     2 * (2 + rng() % 100), 2 * (2 + rng() % 80),
                     i % 3, (i / 3) % 3, (i / 9) % 2, i % 4 == 0 ? 0.3f : 0.f,
                     i % 4 == 1 ? 0.07f : 0.f, aspects[i % 5], splits[i % 4],
                     (float) (i % 5) * 0.1f, 1 + i % 32, rng);
        cases++;
    }
    for (int mode = 0; mode < 3; mode++) {
        for (bool ws : {false, true}) {
            odcpu::check(1920, 1080, 2560, 480, 1280, 720, mode, (mode + 1) % 3,
                         ws, 0.f, 0.f, 0.75f, 0.7f, 0.12f, 32, rng);
            cases++;
        }
    }
    // Same-width windshield, including nonlinear correction of the lower band.
    for (int src = 0; src < 3; src++) {
        for (int dst = 0; dst < 3; dst++) {
            odcpu::check(1920, 1080, 2560, 480, 1920, 720, src, dst,
                         true, 0.3f, 0.07f, 0.75f, 0.7f, 0.12f, 32, rng);
            cases++;
        }
    }
    odcpu::check(4096, 12, 8192, 8, 4096, 8, 1, 2, true, 0.f, 0.f,
                 0.75f, 0.7f, 0.f, 32, rng);
    cases++;
    std::printf("Dashcam column parity passed (%d cases)\n", cases);
}
