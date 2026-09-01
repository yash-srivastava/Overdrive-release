#include "ais_direct.h"

#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>
#include <cstdint>

#define TAG "AisDirect"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define AIS_LIB "/vendor/lib64/libais_client.so"
#define MAX_BUFS 5
#define FRAME_TIMEOUT_NS 500000000ULL
#define INPUT_INFO_SIZE 0x140u
#define FMT_UYVY_VENDOR 0x07080102u

extern "C" android_namespace_t* __loader_android_get_exported_namespace(const char*)
        __attribute__((weak));

namespace {

enum {
    QCARCAM_RET_OK = 0,
    QCARCAM_RET_TIMEOUT = 7,
};

struct QCarCamPlane {
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t size;
    void* buffer;
};

struct QCarCamBuffer {
    QCarCamPlane planes[3];
    uint32_t nPlanes;
    uint32_t flags;
};

struct QCarCamBuffers {
    uint32_t colorFormat;
    uint32_t flags;
    QCarCamBuffer* buffers;
    uint32_t count;
    uint32_t reserved;
};

struct QCarCamFrameInfo {
    uint32_t bufferIndex;
    uint8_t vendorData[44];
};

struct IonAllocationData {
    uint64_t length;
    uint32_t heapMask;
    uint32_t flags;
    int32_t fd;
    uint32_t unused;
};

static_assert(sizeof(QCarCamPlane) == 0x18, "unexpected QCarCam plane ABI");
static_assert(sizeof(QCarCamBuffer) == 0x50, "unexpected QCarCam buffer ABI");
static_assert(sizeof(QCarCamBuffers) == 0x18, "unexpected QCarCam buffers ABI");
static_assert(sizeof(QCarCamFrameInfo) == 0x30, "unexpected QCarCam frame ABI");
static_assert(sizeof(IonAllocationData) == 0x18, "unexpected ION ABI");

constexpr unsigned long ION_IOC_ALLOC = _IOWR('I', 0, IonAllocationData);

using fn_initialize = int (*)(void*);
using fn_uninitialize = int (*)();
using fn_query_inputs = int (*)(void*, unsigned int, unsigned int*);
using fn_open = void* (*)(int);
using fn_close = int (*)(void*);
using fn_s_buffers = int (*)(void*, void*);
using fn_start = int (*)(void*);
using fn_stop = int (*)(void*);
using fn_get_frame = int (*)(void*, void*, uint64_t, uint32_t);
using fn_release_frame = int (*)(void*, uint32_t);

struct InputInfo {
    uint32_t id;
    uint32_t width;
    uint32_t height;
    uint32_t colorFormat;
};

struct Client {
    void* lib = nullptr;
    fn_initialize initialize = nullptr;
    fn_uninitialize uninitialize = nullptr;
    fn_query_inputs query_inputs = nullptr;
    fn_open open = nullptr;
    fn_close close = nullptr;
    fn_s_buffers s_buffers = nullptr;
    fn_start start = nullptr;
    fn_stop stop = nullptr;
    fn_get_frame get_frame = nullptr;
    fn_release_frame release_frame = nullptr;
    bool initialized = false;
    bool ready = false;
    void* hndl = nullptr;
    int cam_id = -1;
    int width = 0;
    int height = 0;
    uint32_t color_fmt = FMT_UYVY_VENDOR;
    uint32_t stride = 0;
    uint32_t frame_size = 0;
    QCarCamBuffers qbufs{};
    QCarCamBuffer buffers[MAX_BUFS]{};
    int buffer_fds[MAX_BUFS]{-1, -1, -1, -1, -1};
    void* mappings[MAX_BUFS]{MAP_FAILED, MAP_FAILED, MAP_FAILED, MAP_FAILED, MAP_FAILED};
    size_t allocation_size = 0;
    int n_bufs = 0;
    InputInfo inputs[16]{};
    unsigned input_n = 0;
};

Client g;
pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
char g_status[4096];

void stlog(const char* fmt, ...) {
    char line[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    LOGI("%s", line);
    fprintf(stderr, "[AisDirect] %s\n", line);
    size_t used = strlen(g_status);
    if (used + 1 < sizeof(g_status)) {
        snprintf(g_status + used, sizeof(g_status) - used, "%s\n", line);
    }
}

void write_log_file(const char* path) {
    if (!path || !path[0]) return;
    FILE* f = fopen(path, "w");
    if (!f) return;
    fputs(g_status, f);
    fclose(f);
}

void* try_dlopen(const char* path, android_namespace_t* ns) {
    if (path && strncmp(path, "/storage/", 9) == 0) {
        stlog("dlopen %s skipped (/storage not in linker namespace)", path);
        return nullptr;
    }
    dlerror();
    if (ns) {
        android_dlextinfo info{};
        info.flags = ANDROID_DLEXT_USE_NAMESPACE;
        info.library_namespace = ns;
        void* h = android_dlopen_ext(path, RTLD_NOW | RTLD_LOCAL, &info);
        stlog("dlopen_ext ns=%p path=%s -> %p (%s)", ns, path, h, h ? "ok" : dlerror());
        return h;
    }
    void* h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    stlog("dlopen %s -> %p (%s)", path, h, h ? "ok" : dlerror());
    return h;
}

bool load_library() {
    if (g.lib) return true;

    const char* local[] = {
            "/data/user/0/com.overdrive.app/files/ais/libais_client.so",
            "/data/data/com.overdrive.app/files/ais/libais_client.so",
            "libais_client.so",
    };
    for (const char* path : local) {
        g.lib = try_dlopen(path, nullptr);
        if (g.lib) break;
    }

    using GetNsFn = android_namespace_t* (*)(const char*);
    GetNsFn get_ns = reinterpret_cast<GetNsFn>(
            dlsym(RTLD_DEFAULT, "android_get_exported_namespace"));
    if (!get_ns) {
        get_ns = reinterpret_cast<GetNsFn>(
                dlsym(RTLD_DEFAULT, "__loader_android_get_exported_namespace"));
    }
    if (!get_ns && __loader_android_get_exported_namespace) {
        get_ns = __loader_android_get_exported_namespace;
    }

    if (!g.lib && get_ns) {
        const char* names[] = {"sphal", "vendor"};
        const char* vendor[] = {AIS_LIB, "/vendor/lib64/libais_hidl_client.so"};
        for (const char* name : names) {
            android_namespace_t* ns = get_ns(name);
            if (!ns) continue;
            for (const char* path : vendor) {
                g.lib = try_dlopen(path, ns);
                if (g.lib) break;
            }
            if (g.lib) break;
        }
    }

    if (!g.lib) {
        stlog("FAIL: cannot dlopen libais_client.so");
        return false;
    }

    auto resolve = [&](const char* name) -> void* {
        void* p = dlsym(g.lib, name);
        stlog("  dlsym %s = %p", name, p);
        return p;
    };

    g.initialize = reinterpret_cast<fn_initialize>(resolve("qcarcam_initialize"));
    g.uninitialize = reinterpret_cast<fn_uninitialize>(resolve("qcarcam_uninitialize"));
    g.query_inputs = reinterpret_cast<fn_query_inputs>(resolve("qcarcam_query_inputs"));
    g.open = reinterpret_cast<fn_open>(resolve("qcarcam_open"));
    g.close = reinterpret_cast<fn_close>(resolve("qcarcam_close"));
    g.s_buffers = reinterpret_cast<fn_s_buffers>(resolve("qcarcam_s_buffers"));
    g.start = reinterpret_cast<fn_start>(resolve("qcarcam_start"));
    g.stop = reinterpret_cast<fn_stop>(resolve("qcarcam_stop"));
    g.get_frame = reinterpret_cast<fn_get_frame>(resolve("qcarcam_get_frame"));
    g.release_frame = reinterpret_cast<fn_release_frame>(resolve("qcarcam_release_frame"));

    if (!g.initialize || !g.open || !g.s_buffers || !g.start || !g.get_frame ||
        !g.release_frame || !g.stop || !g.close) {
        stlog("FAIL: missing required qcarcam_* symbols");
        return false;
    }
    return true;
}

bool do_initialize() {
    if (g.initialized) return true;
    int rc = g.initialize(nullptr);
    stlog("qcarcam_initialize(nullptr) = %d", rc);
    if (rc == QCARCAM_RET_OK) {
        g.initialized = true;
        return true;
    }
    return false;
}

void do_query() {
    g.input_n = 0;
    if (!g.query_inputs) return;
    unsigned int n = 0;
    int rc = g.query_inputs(nullptr, 0, &n);
    stlog("qcarcam_query_inputs(NULL,0) rc=%d count=%u", rc, n);
    if (rc != 0 || n == 0 || n > 16) return;

    char blob[16 * INPUT_INFO_SIZE];
    memset(blob, 0, sizeof(blob));
    unsigned int filled = n;
    rc = g.query_inputs(blob, n, &filled);
    if (rc != 0) return;

    for (unsigned int i = 0; i < filled && i < n && g.input_n < 16; i++) {
        const uint8_t* e = reinterpret_cast<const uint8_t*>(blob) + i * INPUT_INFO_SIZE;
        InputInfo info{};
        memcpy(&info.id, e, sizeof(info.id));
        memcpy(&info.width, e + 0xa4, sizeof(info.width));
        memcpy(&info.height, e + 0xa8, sizeof(info.height));
        memcpy(&info.colorFormat, e + 0x120, sizeof(info.colorFormat));
        float fps = 0;
        memcpy(&fps, e + 0xac, sizeof(fps));
        stlog("input id=%u %ux%u @ %.1f format=0x%x",
              info.id, info.width, info.height, fps, info.colorFormat);
        g.inputs[g.input_n++] = info;
    }
}

bool find_input(int cam_id, InputInfo* out) {
    for (unsigned i = 0; i < g.input_n; i++) {
        if (static_cast<int>(g.inputs[i].id) == cam_id) {
            *out = g.inputs[i];
            return out->width > 0 && out->height > 0;
        }
    }
    return false;
}

void free_all_bufs() {
    for (int i = 0; i < MAX_BUFS; i++) {
        if (g.mappings[i] != MAP_FAILED) {
            munmap(g.mappings[i], g.allocation_size);
            g.mappings[i] = MAP_FAILED;
        }
        if (g.buffer_fds[i] >= 0) {
            close(g.buffer_fds[i]);
            g.buffer_fds[i] = -1;
        }
    }
    g.qbufs = {};
    memset(g.buffers, 0, sizeof(g.buffers));
    g.allocation_size = 0;
    g.n_bufs = 0;
}

bool attach_buffers() {
    uint32_t stride = (static_cast<uint32_t>(g.width) * 2u + 63u) & ~63u;
    uint32_t frame_size = stride * static_cast<uint32_t>(g.height);
    size_t allocation = (static_cast<size_t>(frame_size) + 4095u) & ~4095u;

    int ion = open("/dev/ion", O_RDONLY | O_CLOEXEC);
    if (ion < 0) return false;

    g.allocation_size = allocation;
    g.stride = stride;
    g.frame_size = frame_size;
    g.n_bufs = MAX_BUFS;
    for (int i = 0; i < MAX_BUFS; i++) {
        IonAllocationData alloc{allocation, 0x02000000u, 0, -1, 0};
        if (ioctl(ion, ION_IOC_ALLOC, &alloc) != 0 || alloc.fd < 0) {
            close(ion);
            free_all_bufs();
            return false;
        }
        g.buffer_fds[i] = alloc.fd;
        g.mappings[i] = mmap(nullptr, allocation, PROT_READ | PROT_WRITE, MAP_SHARED, alloc.fd, 0);
        if (g.mappings[i] == MAP_FAILED) {
            close(ion);
            free_all_bufs();
            return false;
        }
        g.buffers[i].planes[0] = {
                static_cast<uint32_t>(g.width),
                static_cast<uint32_t>(g.height),
                stride,
                frame_size,
                reinterpret_cast<void*>(static_cast<intptr_t>(alloc.fd))};
        g.buffers[i].nPlanes = 1;
    }
    close(ion);

    g.qbufs.colorFormat = g.color_fmt;
    g.qbufs.flags = 0;
    g.qbufs.buffers = g.buffers;
    g.qbufs.count = MAX_BUFS;
    g.qbufs.reserved = 0;
    int rc = g.s_buffers(g.hndl, &g.qbufs);
    if (rc != QCARCAM_RET_OK) {
        free_all_bufs();
        return false;
    }
    return true;
}

}  // namespace

bool ais_direct_probe(const char* log_path, char* status, size_t status_len) {
    pthread_mutex_lock(&g_mu);
    g_status[0] = '\0';
    bool ok = load_library() && do_initialize();
    if (ok) {
        do_query();
        g.ready = true;
        stlog("PROBE_OK inputs=%u", g.input_n);
    } else {
        g.ready = false;
        stlog("PROBE_FAIL");
    }
    write_log_file(log_path);
    if (status && status_len) {
        snprintf(status, status_len, "%s", ok ? "ok" : "fail");
        size_t n = strlen(status);
        if (n + 2 < status_len) snprintf(status + n, status_len - n, ": %s", g_status);
    }
    pthread_mutex_unlock(&g_mu);
    return ok;
}

bool ais_direct_is_ready(void) {
    return g.ready && g.initialized;
}

bool ais_direct_open_stream(int cam_id, int width, int height) {
    pthread_mutex_lock(&g_mu);
    if (!g.ready && !(load_library() && do_initialize())) {
        pthread_mutex_unlock(&g_mu);
        return false;
    }
    g.ready = true;
    if (g.input_n == 0) do_query();
    if (g.hndl && g.cam_id == cam_id) {
        pthread_mutex_unlock(&g_mu);
        return true;
    }
    if (g.hndl) {
        pthread_mutex_unlock(&g_mu);
        ais_direct_close_stream();
        pthread_mutex_lock(&g_mu);
    }

    InputInfo info{};
    if (find_input(cam_id, &info)) {
        g.width = static_cast<int>(info.width);
        g.height = static_cast<int>(info.height);
        g.color_fmt = info.colorFormat ? info.colorFormat : FMT_UYVY_VENDOR;
    } else {
        g.width = width > 0 ? width : 1920;
        g.height = height > 0 ? height : 1300;
        g.color_fmt = FMT_UYVY_VENDOR;
    }
    g.cam_id = cam_id;
    g.hndl = g.open(cam_id);
    if (!g.hndl) {
        pthread_mutex_unlock(&g_mu);
        return false;
    }
    if (!attach_buffers()) {
        if (g.close) g.close(g.hndl);
        g.hndl = nullptr;
        pthread_mutex_unlock(&g_mu);
        return false;
    }
    int rc = g.start(g.hndl);
    if (rc != QCARCAM_RET_OK) {
        if (g.close) g.close(g.hndl);
        g.hndl = nullptr;
        free_all_bufs();
        pthread_mutex_unlock(&g_mu);
        return false;
    }
    pthread_mutex_unlock(&g_mu);
    return true;
}

const uint8_t* ais_direct_acquire(int* width, int* height, int* fmt, int* idx,
                                  unsigned timeout_ms) {
    pthread_mutex_lock(&g_mu);
    if (!g.hndl || !g.get_frame) {
        pthread_mutex_unlock(&g_mu);
        return nullptr;
    }
    void* h = g.hndl;
    fn_get_frame get_frame = g.get_frame;
    pthread_mutex_unlock(&g_mu);

    QCarCamFrameInfo info{};
    uint64_t to = timeout_ms ? static_cast<uint64_t>(timeout_ms) * 1000000ULL : FRAME_TIMEOUT_NS;
    int rc = get_frame(h, &info, to, 0);
    if (rc != QCARCAM_RET_OK) return nullptr;

    pthread_mutex_lock(&g_mu);
    unsigned int i = info.bufferIndex;
    const uint8_t* ptr = nullptr;
    if (i < static_cast<unsigned int>(g.n_bufs) && g.mappings[i] != MAP_FAILED) {
        ptr = static_cast<const uint8_t*>(g.mappings[i]);
    }
    if (width) *width = g.width;
    if (height) *height = g.height;
    if (fmt) *fmt = 1;
    if (idx) *idx = static_cast<int>(i);
    pthread_mutex_unlock(&g_mu);
    return ptr;
}

void ais_direct_release(int idx) {
    pthread_mutex_lock(&g_mu);
    if (g.hndl && g.release_frame && idx >= 0) {
        g.release_frame(g.hndl, static_cast<uint32_t>(idx));
    }
    pthread_mutex_unlock(&g_mu);
}

void ais_direct_close_stream(void) {
    pthread_mutex_lock(&g_mu);
    if (g.hndl) {
        if (g.stop) g.stop(g.hndl);
        if (g.close) g.close(g.hndl);
        g.hndl = nullptr;
        g.cam_id = -1;
    }
    free_all_bufs();
    pthread_mutex_unlock(&g_mu);
}
