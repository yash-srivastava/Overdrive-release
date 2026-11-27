// Host CPU-only benchmark, built with the same JNI headers as DashcamColumnsTest.
// Define OD_COMPOSITOR_SOURCE to an alternate source file for a baseline run.
#ifndef OD_COMPOSITOR_SOURCE
#define OD_COMPOSITOR_SOURCE "../../../app/src/main/cpp/camera/CpuMosaicCompositor.cpp"
#endif
#include OD_COMPOSITOR_SOURCE
#include <cstdio>
#include <random>

namespace {
struct Frame {
    std::vector<uint8_t> y, uv;
    odcpu::Planes planes;

    Frame(int w, int h) : y(w * h), uv(w * h / 2) {
        std::mt19937 rng(47);
        for (auto& x : y) x = (uint8_t) rng();
        for (auto& x : uv) x = (uint8_t) rng();
        planes = {y.data(), uv.data(), uv.data() + 1, y.size(), uv.size(), uv.size() - 1,
                  w, w, 2};
    }
};
}

int main() {
    Frame strip(2560, 480), windshield(1280, 720), dst(1920, 1080);
    odcpu::RemapSource sr = {strip.planes, 2560, 480}, wr = {windshield.planes, 1280, 720};
    const char* names[] = {"grid", "dashcam", "windshield", "dewarped dashcam",
                           "dewarped windshield"};
    constexpr int frames = 80;
    for (int mode = 0; mode < 5; mode++) {
        odcpu::RemapSpec p = {1920, 1080, mode == 0 ? 0 : 1, {0.f, .25f, .5f, .75f},
                              mode >= 3 ? .3f : 0.f, mode >= 3 ? .07f : 0.f, .75f,
                              mode == 2 || mode == 4, .12f, .7f};
        for (int i = 0; i < 5; i++) {
            if (odcpu::remapGrid(p, sr, &wr, dst.planes, 16)) return 1;
        }
        timespec a, b;
        clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &a);
        const auto start = odcpu::nowNs();
        for (int i = 0; i < frames; i++) {
            if (odcpu::remapGrid(p, sr, &wr, dst.planes, 16)) return 1;
        }
        const auto stop = odcpu::nowNs();
        clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &b);
        uint64_t checksum = 0;
        for (auto x : dst.y) checksum = checksum * 31 + x;
        for (auto x : dst.uv) checksum = checksum * 31 + x;
        std::printf("%s: CPU %.3f ms/frame, wall %.3f ms/frame, checksum %llu\n",
                    names[mode], ((b.tv_sec - a.tv_sec) * 1e9 + b.tv_nsec - a.tv_nsec) / (frames * 1e6),
                    (stop - start) / (frames * 1e6), (unsigned long long) checksum);
    }
}
