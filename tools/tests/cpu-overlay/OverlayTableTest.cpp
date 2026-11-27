// Host-side parity fixture. Compile the actual production translation unit
// via this include, using the JDK JNI headers and -pthread (see PR test steps).
#include "../../../app/src/main/cpp/camera/CpuMosaicCompositor.cpp"

#include <cassert>
#include <cstdio>
#include <random>

namespace odcpu {
// Original scalar table builder, kept here as the pre-optimization reference.
static bool referenceOverlay(const uint8_t* rgba, size_t rgbaCap, int bw, int bh, int rowBytes,
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
                double fx = ((double) x + 0.5) * bw / w - 0.5;
                if (fx < 0) fx = 0;
                if (fx > bw - 1) fx = bw - 1;
                const int x0 = (int) fx;
                const int x1 = x0 + 1 < bw ? x0 + 1 : x0;
                const double wx = fx - x0;
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
            const double pu = ((double) rowU[0][x] + rowU[0][x + 1] + rowU[1][x] + rowU[1][x + 1]) * 0.25;
            const double pv = ((double) rowV[0][x] + rowV[0][x + 1] + rowV[1][x] + rowV[1][x + 1]) * 0.25;
            const long a256 = lround(a * 256.0);
            tc[xc * 3] = a256 > 0 ? clampU16(pu * 256.0) : 0;
            tc[xc * 3 + 1] = a256 > 0 ? clampU16(pv * 256.0) : 0;
            tc[xc * 3 + 2] = (uint16_t) (256 - (a256 < 0 ? 0 : a256 > 256 ? 256 : a256));
            if (a256 > 0) {
                if (first < 0) first = xc;
                last = xc;
            }
        }
        spansC[yc * 2] = first < 0 ? 0 : first;
        spansC[yc * 2 + 1] = first < 0 ? 0 : last + 1;
    }
    delete[] scratch;
    hdr[3] = 1;
    return true;
}

}  // namespace odcpu

static void check(int bw, int bh, int w, int h, int pad, int mode) {
    const int stride = bw * 4 + pad;
    std::vector<uint8_t> rgba((bh - 1) * stride + bw * 4, 0xa5);
    std::mt19937 rng(197);
    for (int y = 0; y < bh; ++y) {
        for (int x = 0; x < bw; ++x) {
            uint8_t* p = rgba.data() + y * stride + x * 4;
            // All transparent, all opaque, sparse, random, and near-zero alpha.
            int a = mode == 0 ? 0 : mode == 1 ? 255 : mode == 2
                    ? (x >= bw / 3 && x <= bw / 2 && y >= bh / 3 ? 127 : 0)
                    : mode == 3 ? (int) (rng() % 256) : (int) (rng() % 3);
            for (int c = 0; c < 3; ++c) p[c] = (uint8_t) (rng() % (a + 1));
            p[3] = (uint8_t) a;
            if (mode == 5) {
                // Recording strip: opaque black, uniform panels (one nearly
                // transparent), sparse opaque text. Exercises uniform reuse.
                const uint8_t black[4] = {0, 0, 0, 255}, panel[4] = {40, 40, 40, 160},
                              faint[4] = {1, 0, 0, 1};
                const uint8_t* fill = x >= bw / 4 && x < bw / 2 ? panel
                        : x >= bw / 2 && x < 3 * bw / 4 && y < bh / 2 ? faint : black;
                memcpy(p, fill, 4);
                if (rng() % 10 == 0) {
                    p[3] = 255;
                    for (int c = 0; c < 3; ++c) p[c] = (uint8_t) rng();
                }
            }
        }
    }
    const size_t bytes = odcpu::overlayBytes(w, h);
    assert(bytes != 0);
    // Aligned table, plus guard words to detect writes beyond the declared size.
    std::vector<uint32_t> expected((bytes + 3) / 4 + 2, 0xa5a5a5a5u);
    std::vector<uint32_t> actual = expected;
    const auto original = rgba;
    assert(odcpu::referenceOverlay(rgba.data(), rgba.size(), bw, bh, stride,
            reinterpret_cast<uint8_t*>(expected.data() + 1), bytes, w, h));
    assert(odcpu::prepareOverlay(rgba.data(), rgba.size(), bw, bh, stride,
            reinterpret_cast<uint8_t*>(actual.data() + 1), bytes, w, h));
    assert(actual == expected);
    assert(actual.front() == 0xa5a5a5a5u && actual.back() == 0xa5a5a5a5u);
    const auto* raw = reinterpret_cast<const uint8_t*>(actual.data() + 1);
    for (size_t i = bytes; i < ((bytes + 3) / 4) * 4; ++i) assert(raw[i] == 0xa5);
    assert(rgba == original);
}

int main() {
    for (int mode = 0; mode < 6; ++mode) {
        for (int pad : {0, 7}) {
            check(1, 1, 2, 2, pad, mode);
            check(2, 2, 32, 16, pad, mode);
            check(7, 5, 14, 10, pad, mode);
            check(15, 11, 8, 6, pad, mode);
            check(23, 9, 18, 14, pad, mode);
            check(1280, 80, 2560, 160, pad, mode);
        }
    }
    std::puts("Overlay table parity passed (72 cases)");
}
