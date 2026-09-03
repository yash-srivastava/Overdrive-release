// ais_capture_main.cpp — exec via linker64 from /data/app/.../lib/arm64/libais_capture.so

#include "ais_direct.h"

#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <atomic>

#define SOCKET_NAME "dilink5_cam"
#define MAGIC_HEADER 0x44494C35
#define FRAME_WIDTH 1920
#define FRAME_HEIGHT 1300
#define MAX_CLIENTS 8
#define MOSAIC_CMD 31
#define MOSAIC_COLS 3
#define MOSAIC_ROWS 2
#define MOSAIC_TILES 6
#define MOSAIC_TILE_W (FRAME_WIDTH / MOSAIC_COLS)
#define MOSAIC_TILE_H (FRAME_HEIGHT / MOSAIC_ROWS)

struct FrameHeader {
    uint32_t magic, width, height, format, data_size;
    uint64_t timestamp;
};

static std::atomic<bool> g_running{true};
static int g_clients[MAX_CLIENTS];
static int g_client_cam[MAX_CLIENTS];
static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static int g_server_fd = -1;
static int g_default_cam = 0;
static int g_mosaic_ids[MOSAIC_TILES] = {8, 9, 5, 4, 0, -1};

static void on_signal(int) { g_running.store(false); }

static void fill_uyvy_black(uint8_t* dst, size_t pixels) {
    for (size_t i = 0; i < pixels; i += 2) {
        dst[i * 2 + 0] = 0x80;
        dst[i * 2 + 1] = 0x10;
        dst[i * 2 + 2] = 0x80;
        dst[i * 2 + 3] = 0x10;
    }
}

static void blit_uyvy_nn(uint8_t* dst, int dst_w, int dst_x, int dst_y,
                         int tile_w, int tile_h,
                         const uint8_t* src, int src_w, int src_h) {
    if (!dst || !src || src_w <= 0 || src_h <= 0) return;
    for (int y = 0; y < tile_h; y++) {
        int sy = y * src_h / tile_h;
        uint8_t* drow = dst + ((dst_y + y) * dst_w + dst_x) * 2;
        const uint8_t* srow = src + sy * src_w * 2;
        for (int x = 0; x < tile_w; x += 2) {
            int sx = (x * src_w / tile_w) & ~1;
            memcpy(drow + x * 2, srow + sx * 2, 4);
        }
    }
}

/** Scale source into dst, preserving aspect ratio with black letterbox bars. */
static void blit_uyvy_letterbox(uint8_t* dst, int dst_w, int dst_h,
                                const uint8_t* src, int src_w, int src_h) {
    fill_uyvy_black(dst, (size_t)dst_w * (size_t)dst_h);
    if (!src || src_w <= 0 || src_h <= 0) return;
    int tile_w = dst_w;
    int tile_h = (int)((int64_t)src_h * dst_w / src_w);
    if (tile_h > dst_h) {
        tile_h = dst_h;
        tile_w = (int)((int64_t)src_w * dst_h / src_h);
    }
    int dst_x = (dst_w - tile_w) / 2;
    int dst_y = (dst_h - tile_h) / 2;
    blit_uyvy_nn(dst, dst_w, dst_x, dst_y, tile_w, tile_h, src, src_w, src_h);
}

static void parse_mosaic_arg(const char* spec) {
    if (!spec) return;
    if (strncmp(spec, "mosaic=", 7) == 0) spec += 7;
    int slot = 0;
    while (*spec && slot < MOSAIC_TILES) {
        char* end = nullptr;
        long v = strtol(spec, &end, 10);
        if (end == spec) break;
        g_mosaic_ids[slot++] = (int)v;
        spec = (*end == ',') ? end + 1 : end;
    }
    while (slot < MOSAIC_TILES) g_mosaic_ids[slot++] = -1;
}

static bool write_all(int fd, const void* buf, size_t count) {
    size_t total = 0;
    const uint8_t* ptr = (const uint8_t*)buf;
    while (total < count) {
        ssize_t w = send(fd, ptr + total, count - total, MSG_NOSIGNAL);
        if (w <= 0) return false;
        total += (size_t)w;
    }
    return true;
}

static int listen_socket() {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCKET_NAME, strlen(SOCKET_NAME));
    socklen_t len = sizeof(sa_family_t) + strlen(SOCKET_NAME) + 1;
    if (bind(fd, (sockaddr*)&addr, len) < 0) {
        close(fd);
        return -1;
    }
    if (listen(fd, 8) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static void* accept_loop(void*) {
    while (g_running.load()) {
        int client = accept(g_server_fd, nullptr, nullptr);
        if (client < 0) {
            if (!g_running.load()) break;
            usleep(50000);
            continue;
        }
        pthread_mutex_lock(&g_mu);
        bool added = false;
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (g_clients[i] < 0) {
                g_clients[i] = client;
                g_client_cam[i] = g_default_cam;
                added = true;
                break;
            }
        }
        pthread_mutex_unlock(&g_mu);
        if (!added) close(client);
    }
    return nullptr;
}

int main(int argc, char** argv) {
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGPIPE, SIG_IGN);
    const char* log_path = nullptr;
    int default_cam = 0;
    for (int i = 1; i < argc; i++) {
        if (!argv[i]) continue;
        if (strstr(argv[i], ".log")) {
            log_path = argv[i];
            continue;
        }
        if (strncmp(argv[i], "mosaic=", 7) == 0) {
            parse_mosaic_arg(argv[i]);
            continue;
        }
        if (argv[i][0] >= '0' && argv[i][0] <= '9') default_cam = atoi(argv[i]);
    }
    g_default_cam = default_cam;

    char status[4096];
    if (!ais_direct_probe(log_path, status, sizeof(status))) return 2;
    if (!ais_direct_open_stream(default_cam, FRAME_WIDTH, FRAME_HEIGHT)) return 3;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        g_clients[i] = -1;
        g_client_cam[i] = default_cam;
    }
    g_server_fd = listen_socket();
    if (g_server_fd < 0) return 4;
    pthread_t acc;
    pthread_create(&acc, nullptr, accept_loop, nullptr);
    pthread_detach(acc);

    uint8_t* mosaic = (uint8_t*)malloc(FRAME_WIDTH * FRAME_HEIGHT * 2u);
    fill_uyvy_black(mosaic, FRAME_WIDTH * FRAME_HEIGHT);
    int opened = default_cam, mosaic_slot = 0;

    while (g_running.load()) {
        int want = default_cam;
        pthread_mutex_lock(&g_mu);
        for (int i = 0; i < MAX_CLIENTS; i++) {
            if (g_clients[i] < 0) continue;
            uint8_t cmd = 0xFF;
            if (recv(g_clients[i], &cmd, 1, MSG_DONTWAIT) == 1 && cmd <= MOSAIC_CMD) {
                g_client_cam[i] = cmd;
            }
            want = g_client_cam[i];
        }
        pthread_mutex_unlock(&g_mu);

        const uint8_t* send_ptr = nullptr;
        uint32_t send_w = FRAME_WIDTH, send_h = FRAME_HEIGHT;
        int idx = -1;

        if (want == MOSAIC_CMD) {
            if (opened != MOSAIC_CMD) {
                ais_direct_close_stream();
                fill_uyvy_black(mosaic, FRAME_WIDTH * FRAME_HEIGHT);
                opened = MOSAIC_CMD;
                mosaic_slot = 0;
            }
            int cam = g_mosaic_ids[mosaic_slot];
            if (cam >= 0 && ais_direct_open_stream(cam, FRAME_WIDTH, FRAME_HEIGHT)) {
                int w = 0, h = 0, fmt = 1;
                const uint8_t* p = ais_direct_acquire(&w, &h, &fmt, &idx, 400);
                if (p) {
                    int col = mosaic_slot % 3, row = mosaic_slot / 3;
                    blit_uyvy_nn(mosaic, FRAME_WIDTH, col * MOSAIC_TILE_W, row * MOSAIC_TILE_H,
                                 MOSAIC_TILE_W, MOSAIC_TILE_H, p, w, h);
                }
                if (idx >= 0) ais_direct_release(idx);
                ais_direct_close_stream();
            }
            mosaic_slot = (mosaic_slot + 1) % MOSAIC_TILES;
            send_w = FRAME_WIDTH;
            send_h = FRAME_HEIGHT;
            send_ptr = mosaic;
            idx = -1;
        } else {
            if (want != opened) {
                ais_direct_close_stream();
                if (ais_direct_open_stream(want, FRAME_WIDTH, FRAME_HEIGHT)) opened = want;
                else {
                    usleep(200000);
                    continue;
                }
            }
            int w = 0, h = 0, fmt = 1;
            const uint8_t* p = ais_direct_acquire(&w, &h, &fmt, &idx, 200);
            if (!p) continue;
            // Cabin/DMS and other non-mosaic cameras often arrive at a native
            // resolution that differs from the 1920x1300 panoramic tile size.
            // Normalize every single-camera frame to the standard buffer so the
            // Java bridge and stream scaler always see a full SurfaceTexture.
            if (w == FRAME_WIDTH && h == FRAME_HEIGHT) {
                send_ptr = p;
                send_w = (uint32_t)w;
                send_h = (uint32_t)h;
            } else {
                blit_uyvy_letterbox(mosaic, FRAME_WIDTH, FRAME_HEIGHT, p, w, h);
                send_ptr = mosaic;
                send_w = FRAME_WIDTH;
                send_h = FRAME_HEIGHT;
                if (idx >= 0) {
                    ais_direct_release(idx);
                    idx = -1;
                }
            }
        }

        FrameHeader header{};
        header.magic = MAGIC_HEADER;
        header.width = send_w;
        header.height = send_h;
        header.format = 1;
        header.data_size = send_w * send_h * 2u;
        pthread_mutex_lock(&g_mu);
        for (int i = 0; i < MAX_CLIENTS; i++) {
            int fd = g_clients[i];
            if (fd < 0) continue;
            if (!write_all(fd, &header, sizeof(header)) ||
                !write_all(fd, send_ptr, header.data_size)) {
                close(fd);
                g_clients[i] = -1;
            }
        }
        pthread_mutex_unlock(&g_mu);
        if (idx >= 0) ais_direct_release(idx);
    }
    free(mosaic);
    return 0;
}
