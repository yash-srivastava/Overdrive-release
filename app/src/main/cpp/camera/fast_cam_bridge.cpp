#include "fast_cam_bridge.h"
#include "fast_cam_ipc.h"
#include "fd_passing.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/mman.h>

struct FastCamClientCtx {
    int sock_fd;
    int received_fds[FAST_CAM_MAX_TOTAL_BUFS];
    void* mapped_ptrs[FAST_CAM_MAX_TOTAL_BUFS];
    fast_cam_handshake_multi_resp_t handshake;
};

FastCamClientCtx* fast_cam_client_create(void) {
    FastCamClientCtx* ctx = (FastCamClientCtx*)calloc(1, sizeof(FastCamClientCtx));
    if (ctx) ctx->sock_fd = -1;
    return ctx;
}

void fast_cam_client_disconnect(FastCamClientCtx* ctx) {
    if (!ctx) return;
    for (uint32_t i = 0; i < ctx->handshake.total_fds; i++) {
        if (ctx->mapped_ptrs[i]) {
            munmap(ctx->mapped_ptrs[i], 4992000);
            ctx->mapped_ptrs[i] = NULL;
        }
        if (ctx->received_fds[i] >= 0) {
            close(ctx->received_fds[i]);
            ctx->received_fds[i] = -1;
        }
    }
    if (ctx->sock_fd >= 0) {
        close(ctx->sock_fd);
        ctx->sock_fd = -1;
    }
}

void fast_cam_client_destroy(FastCamClientCtx* ctx) {
    if (!ctx) return;
    fast_cam_client_disconnect(ctx);
    free(ctx);
}

bool fast_cam_client_connect(FastCamClientCtx* ctx, const char* sock_path) {
    if (!ctx) return false;
    fast_cam_client_disconnect(ctx);

    ctx->sock_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (ctx->sock_fd < 0) return false;

    struct sockaddr_un saddr;
    memset(&saddr, 0, sizeof(saddr));
    saddr.sun_family = AF_UNIX;
    socklen_t slen = 0;
    bool connected = false;

    if (sock_path && sock_path[0] == '@') {
        saddr.sun_path[0] = '\0';
        strncpy(saddr.sun_path + 1, sock_path + 1, sizeof(saddr.sun_path) - 2);
        slen = sizeof(sa_family_t) + strlen(sock_path);
        if (connect(ctx->sock_fd, (struct sockaddr*)&saddr, slen) == 0) connected = true;
    } else if (sock_path) {
        strncpy(saddr.sun_path, sock_path, sizeof(saddr.sun_path) - 1);
        slen = sizeof(sa_family_t) + strlen(saddr.sun_path) + 1;
        if (connect(ctx->sock_fd, (struct sockaddr*)&saddr, slen) == 0) connected = true;
    }

    if (!connected) {
        // Fallback to abstract socket @fast_cam.sock
        memset(&saddr, 0, sizeof(saddr));
        saddr.sun_family = AF_UNIX;
        const char* abs_name = "fast_cam.sock";
        saddr.sun_path[0] = '\0';
        memcpy(saddr.sun_path + 1, abs_name, strlen(abs_name));
        slen = sizeof(sa_family_t) + 1 + strlen(abs_name);
        if (connect(ctx->sock_fd, (struct sockaddr*)&saddr, slen) == 0) {
            connected = true;
        }
    }

    if (!connected) {
        close(ctx->sock_fd);
        ctx->sock_fd = -1;
        return false;
    }

    // Receive handshake and all passed ION FDs via SCM_RIGHTS
    int num_received = 0;
    int rc = recv_fds(ctx->sock_fd, ctx->received_fds, FAST_CAM_MAX_TOTAL_BUFS,
                      &num_received, &ctx->handshake, sizeof(ctx->handshake));
    if (rc <= 0 || ctx->handshake.magic != FAST_CAM_MAGIC) {
        fast_cam_client_disconnect(ctx);
        return false;
    }

    // Map each received ION FD into process virtual address space (Zero-Copy)
    for (uint32_t i = 0; i < ctx->handshake.total_fds; i++) {
        ctx->mapped_ptrs[i] = mmap(NULL, 4992000, PROT_READ, MAP_SHARED, ctx->received_fds[i], 0);
        if (ctx->mapped_ptrs[i] == MAP_FAILED) {
            ctx->mapped_ptrs[i] = NULL;
        }
    }

    return true;
}

bool fast_cam_client_is_connected(const FastCamClientCtx* ctx) {
    return ctx != NULL && ctx->sock_fd >= 0;
}

bool fast_cam_client_wait_frame(FastCamClientCtx* ctx, FastCamFrame* out_frame, int timeout_ms) {
    if (!ctx || ctx->sock_fd < 0 || !out_frame) return false;

    struct pollfd pfd;
    pfd.fd = ctx->sock_fd;
    pfd.events = POLLIN;

    int ret = poll(&pfd, 1, timeout_ms);
    if (ret <= 0) return false;

    if (pfd.revents & (POLLHUP | POLLERR | POLLNVAL)) {
        fast_cam_client_disconnect(ctx);
        return false;
    }

    fast_cam_frame_msg_t msg;
    ssize_t n = recv(ctx->sock_fd, &msg, sizeof(msg), MSG_WAITALL);
    if (n <= 0) {
        fast_cam_client_disconnect(ctx);
        return false;
    }
    if (n != sizeof(msg) || msg.magic != FAST_CAM_MAGIC) return false;

    // Find the stream matching msg.cam_id
    uint32_t fd_idx = 0;
    for (uint32_t s = 0; s < ctx->handshake.num_streams; s++) {
        if (ctx->handshake.streams[s].cam_id == msg.cam_id) {
            fd_idx = ctx->handshake.streams[s].fd_start_idx + msg.buf_index;
            break;
        }
    }

    if (fd_idx >= ctx->handshake.total_fds || !ctx->mapped_ptrs[fd_idx]) {
        return false;
    }

    out_frame->cam_id       = msg.cam_id;
    out_frame->width        = msg.width;
    out_frame->height       = msg.height;
    out_frame->stride       = msg.width * 2;
    out_frame->timestamp_ns = msg.timestamp_ns;
    out_frame->pixels       = (const uint8_t*)ctx->mapped_ptrs[fd_idx];

    return true;
}

void fast_cam_compose_2x2(
    const uint8_t* cam0, const uint8_t* cam1,
    const uint8_t* cam3, const uint8_t* cam2,
    uint8_t* out_grid_1080p
) {
    const int in_w = 1920;
    const int in_h = 1300;
    const int half_w = in_w / 2; // 960
    const int half_h = in_h / 2; // 650
    const int out_stride = in_w * 2; // 3840 bytes
    const int in_stride  = in_w * 2; // 3840 bytes

    for (int y = 0; y < half_h; y++) {
        int src_y = y * 2;
        const uint32_t* src0 = (const uint32_t*)(cam0 + src_y * in_stride);
        const uint32_t* src1 = (const uint32_t*)(cam1 + src_y * in_stride);
        uint32_t* dst_top = (uint32_t*)(out_grid_1080p + y * out_stride);

        for (int x = 0; x < half_w / 2; x++) dst_top[x] = src0[x * 2];
        for (int x = 0; x < half_w / 2; x++) dst_top[half_w / 2 + x] = src1[x * 2];

        const uint32_t* src3 = (const uint32_t*)(cam3 + src_y * in_stride);
        const uint32_t* src2 = (const uint32_t*)(cam2 + src_y * in_stride);
        uint32_t* dst_bot = (uint32_t*)(out_grid_1080p + (half_h + y) * out_stride);

        for (int x = 0; x < half_w / 2; x++) dst_bot[x] = src3[x * 2];
        for (int x = 0; x < half_w / 2; x++) dst_bot[half_w / 2 + x] = src2[x * 2];
    }
}

void fast_cam_compose_4k(
    const uint8_t* cam0, const uint8_t* cam1,
    const uint8_t* cam3, const uint8_t* cam2,
    uint8_t* out_4k_grid
) {
    const int W = 1920;
    const int H = 1300;
    const size_t STRIDE_IN = W * 2;         // 3840 bytes
    const size_t STRIDE_4K = W * 2 * 2;     // 7680 bytes

    // 1. Top half: Cam 0 (Front) on Left, Cam 1 (Right) on Right
    for (int y = 0; y < H; y++) {
        uint8_t* dst_row = out_4k_grid + y * STRIDE_4K;
        memcpy(dst_row, cam0 + y * STRIDE_IN, STRIDE_IN);
        memcpy(dst_row + STRIDE_IN, cam1 + y * STRIDE_IN, STRIDE_IN);
    }

    // 2. Bottom half: Cam 3 (Left) on Left, Cam 2 (Rear) on Right
    for (int y = 0; y < H; y++) {
        uint8_t* dst_row = out_4k_grid + (H + y) * STRIDE_4K;
        memcpy(dst_row, cam3 + y * STRIDE_IN, STRIDE_IN);
        memcpy(dst_row + STRIDE_IN, cam2 + y * STRIDE_IN, STRIDE_IN);
    }
}
