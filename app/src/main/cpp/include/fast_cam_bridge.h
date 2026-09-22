#pragma once
#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define FAST_CAM_API __attribute__((visibility("default")))

typedef struct {
    uint32_t cam_id;        // 0: Front, 1: Right, 2: Rear, 3: Left
    uint32_t width;         // 1920
    uint32_t height;        // 1300
    uint32_t stride;        // 3840 (bytes per row)
    uint64_t timestamp_ns;  // Hardware capture timestamp in nanoseconds
    const uint8_t* pixels;  // Direct Zero-Copy mapped pointer in RAM (no memcpy)
} FastCamFrame;

// Client handle opaque structure
typedef struct FastCamClientCtx FastCamClientCtx;

FAST_CAM_API FastCamClientCtx* fast_cam_client_create(void);
FAST_CAM_API void fast_cam_client_destroy(FastCamClientCtx* ctx);

FAST_CAM_API bool fast_cam_client_connect(FastCamClientCtx* ctx, const char* sock_path);
FAST_CAM_API void fast_cam_client_disconnect(FastCamClientCtx* ctx);
FAST_CAM_API bool fast_cam_client_is_connected(const FastCamClientCtx* ctx);

// Waits for the next hardware frame from any active camera (timeout in milliseconds)
FAST_CAM_API bool fast_cam_client_wait_frame(FastCamClientCtx* ctx, FastCamFrame* out_frame, int timeout_ms);

// Ultra-fast 2x2 Compositor in UYVY (4 cameras decimated into 1920x1300 standard canvas)
FAST_CAM_API void fast_cam_compose_2x2(
    const uint8_t* cam0, const uint8_t* cam1,
    const uint8_t* cam3, const uint8_t* cam2,
    uint8_t* out_grid_1080p
);

// 4K Ultra-HD Native Compositor in UYVY (3840x2600, 100% native pixels preserved, zero downsampling)
FAST_CAM_API void fast_cam_compose_4k(
    const uint8_t* cam0, const uint8_t* cam1,
    const uint8_t* cam3, const uint8_t* cam2,
    uint8_t* out_4k_grid
);

#ifdef __cplusplus
}

// Convenient C++ RAII Wrapper for Android / NDK integration
class FastCamClient {
public:
    FastCamClient() : m_ctx(fast_cam_client_create()) {}
    ~FastCamClient() { fast_cam_client_destroy(m_ctx); }

    bool connect(const char* sock_path = "@fast_cam.sock") {
        return fast_cam_client_connect(m_ctx, sock_path);
    }

    void disconnect() {
        fast_cam_client_disconnect(m_ctx);
    }

    bool isConnected() const {
        return fast_cam_client_is_connected(m_ctx);
    }

    bool waitForFrame(FastCamFrame* out_frame, int timeout_ms = 100) {
        return fast_cam_client_wait_frame(m_ctx, out_frame, timeout_ms);
    }

    // 2x2 Standard Decimated Compositor (1920x1300 canvas)
    static void compose2x2(const uint8_t* cam0, const uint8_t* cam1,
                           const uint8_t* cam3, const uint8_t* cam2,
                           uint8_t* out_grid_1080p) {
        fast_cam_compose_2x2(cam0, cam1, cam3, cam2, out_grid_1080p);
    }

    // 4K Ultra-HD Full-Resolution Compositor (3840x2600 canvas)
    static void compose4K(const uint8_t* cam0, const uint8_t* cam1,
                          const uint8_t* cam3, const uint8_t* cam2,
                          uint8_t* out_4k_grid) {
        fast_cam_compose_4k(cam0, cam1, cam3, cam2, out_4k_grid);
    }

private:
    FastCamClientCtx* m_ctx;
};
#endif
