#pragma once
#include <stdint.h>

#define FAST_CAM_IPC_SOCKET_PATH "/data/local/tmp/fast_cam.sock"
#define FAST_CAM_MAX_CAMS        4
#define FAST_CAM_BUFS_PER_CAM    5
#define FAST_CAM_MAX_TOTAL_BUFS  (FAST_CAM_MAX_CAMS * FAST_CAM_BUFS_PER_CAM)
#define FAST_CAM_MAGIC           0x4643414D // 'FCAM'

typedef enum {
    FAST_CAM_MSG_HANDSHAKE_REQ = 1,
    FAST_CAM_MSG_HANDSHAKE_RESP,
    FAST_CAM_MSG_FRAME_READY,
    FAST_CAM_MSG_FRAME_RELEASE,
} fast_cam_msg_type_t;

// Information about a single camera stream
typedef struct {
    uint32_t cam_id;        // 0: Front, 1: Right, 2: Rear, 3: Left
    uint32_t width;         // 1920
    uint32_t height;        // 1300
    uint32_t stride;        // 3840
    uint32_t buffer_bytes;  // 4,992,000
    uint32_t num_buffers;   // 5
    uint32_t fd_start_idx;  // Index in the SCM_RIGHTS FD array
} __attribute__((packed)) fast_cam_stream_info_t;

// Handshake response describing all active independent camera streams
typedef struct {
    uint32_t magic;         // 'FCAM'
    uint32_t msg_type;      // FAST_CAM_MSG_HANDSHAKE_RESP
    uint32_t num_streams;   // 1..4 active cameras
    uint32_t total_fds;     // Total FDs passed via SCM_RIGHTS (e.g. 20 for 4 cams)
    fast_cam_stream_info_t streams[FAST_CAM_MAX_CAMS];
} __attribute__((packed)) fast_cam_handshake_multi_resp_t;

// Lightweight frame notification (32 bytes total)
typedef struct {
    uint32_t magic;         // 'FCAM'
    uint32_t msg_type;      // FAST_CAM_MSG_FRAME_READY
    uint32_t cam_id;        // Which camera produced this frame (0..3)
    uint32_t buf_index;     // Ring buffer slot index (0..4)
    uint32_t sequence_no;   // Hardware capture sequence counter
    uint32_t width;         // Frame width (1920)
    uint32_t height;        // Frame height (1300)
    uint64_t timestamp_ns;  // Hardware capture timestamp in nanoseconds
} __attribute__((packed)) fast_cam_frame_msg_t;
