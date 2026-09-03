# Shark 6 / DiLink 5 camera — self-contained restart notes

Everything needed to re-apply the working Shark path on a clean OverDrive branch is **in this file**. Do not depend on the messy tree this was written from.

Field unit: BYD Shark 6, DiLink 5, SA8155P (same OS family as Sealion 7). ADB `192.168.50.68:5555`. Product strings are generic (`ro.product.model=BYD AUTO`). Fingerprint that actually identifies this car: `ro.vehicle.type=Di5.0_DXF_W`.

Leave camera mode **Default** (not DiLink 4). Park, keep OEM 360 / `com.ts.avm` closed — it contends for AIS `open()`.

Package id used below: `com.overdrive.app`. Change the path strings if the package id changes.

---

## Do not re-try

Proven dead on this Shark.

1. **`/data/local/tmp` as daemon scratch.** SELinux `u:object_r:data_local:s0` Enforcing. Even ADB shell uid 2000 gets `EACCES`. `adb root` is off.
2. **`LD_PRELOAD` of a hook `.so` from `/data/app/.../lib/arm64/` into `/vendor/bin/qcarcam_test`.** Vendor linker namespace only permits `/odm:/vendor:/system/vendor`.
3. **In-process AIS from the `app_process` camera daemon.** `dlopen(/vendor/lib64/libais_client.so)` fails: `libdl_android.so` not accessible for `classloader-namespace`. `android_get_exported_namespace` / `android_create_namespace` were NULL in that process.
4. **`dlopen` / `System.load` of vendor AIS from `/storage/emulated/0/...`.** App classloader permitted paths are `/data`, `/mnt/expand`, `/data/user/0/<pkg>` only.
5. **Normal APK (`untrusted_app`) reading `/vendor/lib64/libais_client.so`.** SELinux `vendor_file`.
6. **`qcarcam_test -dumpFrame=`.** Writes `/data/vendor/camera/` (inaccessible).
7. **V4L2 loopback `/dev/video51`–`58`.** Exists (1920×1024 UYVY, `system:camera`) but app/shell are not in group `camera`.
8. **A gradient / fake “sidecar” that does not call AIS.** Not a camera path.

`access("/vendor/lib64/libais_client.so", F_OK)` only proves the file exists. It does not mean this process can load it.

---

## 1. Daemon scratch on emulated app files

Stock OverDrive writes locks, logs, config, and ADB scripts to `/data/local/tmp`. On this Shark that dir is unwritable. Both the app UID and shell **can** write the app external files dir.

```
/storage/emulated/0/Android/data/com.overdrive.app/files/daemon
```

Android `sh` also writes heredoc scratch to `$TMPDIR`. If TMPDIR is still `/data/local/tmp`, even a script written to the emulated dir fails.

### Java helper (complete)

```java
package com.overdrive.app.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class DaemonTmp {
    public static final String LEGACY_DIR = "/data/local/tmp";
    public static final String DIR =
            "/storage/emulated/0/Android/data/com.overdrive.app/files/daemon";

    private DaemonTmp() {}

    public static String path(String name) {
        if (name == null || name.isEmpty()) return DIR;
        if (name.charAt(0) == '/') {
            if (name.equals(LEGACY_DIR)) return DIR;
            if (name.startsWith(LEGACY_DIR + "/")) {
                return DIR + name.substring(LEGACY_DIR.length());
            }
            return name;
        }
        return DIR + "/" + name;
    }

    public static File file(String name) {
        return new File(path(name));
    }

    public static File ensureDir() {
        File dir = new File(DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("DaemonTmp", "mkdirs failed: " + DIR);
        }
        return dir;
    }

    public static void migrateIfNeeded(String fileName) {
        File dest = file(fileName);
        if (dest.exists()) return;
        File src = new File(LEGACY_DIR, fileName);
        if (!src.isFile()) return;
        ensureDir();
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            android.util.Log.w("DaemonTmp", "migrate " + fileName + ": " + e.getMessage());
        }
    }
}
```

### ADB script drop (must set TMPDIR)

```
export TMPDIR=/storage/emulated/0/Android/data/com.overdrive.app/files/daemon
mkdir -p "$TMPDIR"
cat > "$TMPDIR/.adb_script_<nonce>.sh" <<'EOF'
...script...
EOF
sh "$TMPDIR/.adb_script_<nonce>.sh"
```

Put that prefix on **every** in-app dadb heredoc. PowerShell on the PC eats `$(pm ...)` inside double-quoted `adb shell` — use a single-quoted remote command or write a script under `DIR`.

### Paths the camera path must use

| Purpose                                         | New path                               |
| ----------------------------------------------- | -------------------------------------- |
| Camera daemon log                               | `…/files/daemon/cam_daemon.log`        |
| AIS sidecar log                                 | `…/files/daemon/ais_capture.log`       |
| Unified config                                  | `…/files/daemon/overdrive_config.json` |
| Device id file                                  | `…/files/daemon/.overdrive_device_id`  |
| HTTP web root (if daemon serves files from tmp) | under `…/files/daemon/`                |

Grep a clean branch for `/data/local/tmp` and convert at least: camera daemon, ADB script executor, config load, log paths. Other writers (zrok, acc-sentry, mqtt, audio) will still fail on this Shark until they use the same dir.

---

## 2. Architecture that works

```
OverDrive Java (live view / encoder)
    → JNI posts UYVY/NV12 to a Surface
    → UNIX abstract socket client  @dilink5_cam
         ← FrameHeader + UYVY payload

Standalone process (NOT app_process):
    /system/bin/linker64 /data/app/~~…/com.overdrive.app-…/lib/arm64/libais_capture.so
        → linker [system] section (dir.system = /data)
        → dlopen vendor libais_client via sphal
        → qcarcam_initialize / open / get_frame
```

Why `/data/app/.../lib/arm64`: AGP extracts native libs there. Executing a PIE from that path makes the linker treat it as a **system** binary, not an app classloader-namespace binary.

Why `linker64 <pie>`: the packaged file is named `libais_capture.so` so the APK installer extracts it. It is a PIE executable, not a shared library.

After every Studio install the `~~…` directory changes. Resolve with:

```
pm path com.overdrive.app
# package:/data/app/~~XXXX/com.overdrive.app-YYYY==/base.apk
# lib dir = that path with /base.apk replaced by /lib/arm64
```

**Kill leftovers after every install.** Studio does not kill `app_process` / old sidecars.

```
pkill -9 -f byd_cam_daemon
pkill -9 -f libais_capture
pkill -9 -f qcarcam_test
```

Do **not** treat `pgrep -f libais_capture` “already running” as success — that keeps a stale binary from the previous APK.

Default open camera on Shark must be **8 (front)**, not 0 (DMS).

---

## 3. CMake: package the PIE as `libais_capture.so`

```cmake
# PIE executable packaged as libais_capture.so so AGP extracts it under
# /data/app/.../lib/arm64. Exec from that path uses the [system] linker
# namespace, which can reach vendor libais_client via sphal.
add_executable(ais_capture
    camera/ais_capture_main.cpp
    camera/ais_direct.cpp
)
target_link_libraries(ais_capture
    dl
    log
    android
)
if(DEFINED CMAKE_LIBRARY_OUTPUT_DIRECTORY)
    add_custom_command(TARGET ais_capture POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy
            $<TARGET_FILE:ais_capture>
            ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libais_capture.so
        COMMENT "Package ais_capture as libais_capture.so for the APK"
    )
    add_dependencies(surveillance ais_capture)
endif()
```

Link `android` for `android_dlopen_ext`.

---

## 4. Start the sidecar (Java)

```java
String probeLog = DaemonTmp.path("ais_capture.log");
String linker = new File("/system/bin/linker64").exists()
        ? "/system/bin/linker64" : "/system/bin/linker";
String bin = /* …/lib/arm64/libais_capture.so from pm path */;
int defaultCam = 8; // Shark front
String mosaicArg = "mosaic=8,9,5,4,0,-1";
ProcessBuilder pb = new ProcessBuilder(
        linker, bin, probeLog, String.valueOf(defaultCam), mosaicArg);
pb.redirectErrorStream(true);
Process p = pb.start();
```

Resolve `bin`:

```java
Process p = Runtime.getRuntime().exec(new String[]{"pm", "path", "com.overdrive.app"});
// read lines starting with "package:" containing "/base.apk"
// apk.substring(0, apk.length() - "/base.apk".length()) + "/lib/arm64/libais_capture.so"
```

Also check `java.library.path` and the daemon’s extracted JNI dir. `chmod +x` the `.so` (`setExecutable(true, false)`).

---

## 5. Socket protocol (`@dilink5_cam`)

Abstract UNIX socket (leading NUL, name `dilink5_cam`).

Client → server: one byte, AIS input id `0…24`, or `31` = 2×3 mosaic.

Server → client, repeating:

```c
#define MAGIC_HEADER 0x44494C35  /* 'DIL5' */

struct FrameHeader {
    uint32_t magic;      /* 0x44494C35 */
    uint32_t width;      /* typically 1920 */
    uint32_t height;     /* typically 1300 */
    uint32_t format;     /* 1 = UYVY */
    uint32_t data_size;  /* width * height * 2 for UYVY */
    uint64_t timestamp;
};
```

Then `data_size` bytes of UYVY.

Client connect (same bind as server, but `connect`):

```c
int fd = socket(AF_UNIX, SOCK_STREAM, 0);
sockaddr_un addr{};
addr.sun_family = AF_UNIX;
addr.sun_path[0] = '\0';
memcpy(addr.sun_path + 1, "dilink5_cam", 11);
socklen_t len = sizeof(sa_family_t) + 11 + 1;
connect(fd, (sockaddr*)&addr, len);
uint8_t cmd = 8; /* or 31 for mosaic */
send(fd, &cmd, 1, MSG_NOSIGNAL);
```

Post frames to an `ANativeWindow` as RGBA (existing OverDrive scaler already does UYVY→RGBA). Window geometry used: **1920×1300** RGBA8888. Gpu stream scaler on DiLink 5 uses **full-frame passthrough** (`cameraLayout = 1`): do not crop a 4-strip mosaic out of these frames. Single cameras are already one AIS stream. ALL is a pre-composited 2×3 UYVY frame of the same size.

---

## 6. Verified QCarCam / AIS ABI

Do not guess another header. This is what opened cameras on this Shark.

| Item                      | Value                                                                               |
| ------------------------- | ----------------------------------------------------------------------------------- |
| `qcarcam_initialize`      | `int (*)(void*)` — call `initialize(nullptr)`                                       |
| `qcarcam_open`            | `void* (*)(int)` — `open(id)` only, no out-param                                    |
| `qcarcam_query_inputs`    | `int (*)(void* buf, unsigned count, unsigned* outCount)` — first call `(NULL,0,&n)` |
| Query record              | **0x140** bytes                                                                     |
| id                        | `+0` (`uint32`)                                                                     |
| width                     | `+0xa4`                                                                             |
| height                    | `+0xa8`                                                                             |
| fps                       | `+0xac` (`float`)                                                                   |
| format                    | `+0x120`, default `0x07080102` (UYVY)                                               |
| Plane                     | `width, height, stride, size, buffer` — **0x18**                                    |
| `s_buffers` blob          | `colorFormat, flags, buffers*, count, reserved` — **0x18**                          |
| Frame info                | `uint32 bufferIndex` + 44 bytes — **0x30**                                          |
| ION heap mask             | bit **25** = `0x02000000`                                                           |
| Buffers                   | 5                                                                                   |
| Stride                    | `(width*2 + 63) & ~63`                                                              |
| `plane.buffer`            | `(void*)(intptr_t)fd` — **the ION fd, not the mmap pointer**                        |
| `qcarcam_get_frame`       | `int (*)(void* h, void* info, uint64_t timeout_ns, uint32_t flags)`                 |
| Timeout OK                | return `0`; timeout return `7`                                                      |
| `query_inputs` count here | **8**                                                                               |

Sealion 7 input 0: 1920×1300 @ 30, stride 3840, 4,992,000 bytes. Shark V4L2 XML height is 1024 — **ignore it**. AIS query is truth.

Wrong ABI (`open` out-param, different query size, `plane.buffer = mapped ptr`) is why `open(0)` failed until this layout was used.

Symbols to `dlsym` from `libais_client.so`:

```
qcarcam_initialize
qcarcam_uninitialize
qcarcam_query_inputs
qcarcam_open
qcarcam_close
qcarcam_s_buffers
qcarcam_start
qcarcam_stop
qcarcam_get_frame
qcarcam_release_frame
```

### `ais_direct.h` (complete)

```c
#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

bool ais_direct_probe(const char* log_path, char* status, size_t status_len);
bool ais_direct_is_ready(void);
bool ais_direct_open_stream(int cam_id, int width, int height);
const uint8_t* ais_direct_acquire(int* width, int* height, int* fmt, int* idx,
                                  unsigned timeout_ms);
void ais_direct_release(int idx);
void ais_direct_close_stream(void);

#ifdef __cplusplus
}
#endif
```

### `ais_direct.cpp` (complete working client)

```cpp
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
```

In the sidecar process, `load_library()` typically succeeds via `sphal` + `/vendor/lib64/libais_client.so`. The `/data/user/0/.../files/ais/` fallback is only for a diagnostic APK that staged copies (section 9). Production sidecar does not need those copies.

---

## 7. Sidecar main loop (complete)

```cpp
// ais_capture_main.cpp — exec via linker64 from /data/app/.../lib/arm64

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
#define MOSAIC_TILE_W (FRAME_WIDTH / MOSAIC_COLS)   /* 640 */
#define MOSAIC_TILE_H (FRAME_HEIGHT / MOSAIC_ROWS)  /* 650 */

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
        dst[i * 2 + 0] = 0x80; dst[i * 2 + 1] = 0x10;
        dst[i * 2 + 2] = 0x80; dst[i * 2 + 3] = 0x10;
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
    if (bind(fd, (sockaddr*)&addr, len) < 0) { close(fd); return -1; }
    if (listen(fd, 8) < 0) { close(fd); return -1; }
    return fd;
}

static void* accept_loop(void*) {
    while (g_running.load()) {
        int client = accept(g_server_fd, nullptr, nullptr);
        if (client < 0) { if (!g_running.load()) break; usleep(50000); continue; }
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
        if (strstr(argv[i], ".log")) { log_path = argv[i]; continue; }
        if (strncmp(argv[i], "mosaic=", 7) == 0) { parse_mosaic_arg(argv[i]); continue; }
        if (argv[i][0] >= '0' && argv[i][0] <= '9') default_cam = atoi(argv[i]);
    }
    g_default_cam = default_cam;

    char status[4096];
    if (!ais_direct_probe(log_path, status, sizeof(status))) return 2;
    if (!ais_direct_open_stream(default_cam, FRAME_WIDTH, FRAME_HEIGHT)) return 3;
    for (int i = 0; i < MAX_CLIENTS; i++) { g_clients[i] = -1; g_client_cam[i] = default_cam; }
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
            send_w = FRAME_WIDTH; send_h = FRAME_HEIGHT; send_ptr = mosaic; idx = -1;
        } else {
            if (want != opened) {
                ais_direct_close_stream();
                if (ais_direct_open_stream(want, FRAME_WIDTH, FRAME_HEIGHT)) opened = want;
                else { usleep(200000); continue; }
            }
            int w = 0, h = 0, fmt = 1;
            const uint8_t* p = ais_direct_acquire(&w, &h, &fmt, &idx, 200);
            if (!p) continue;
            send_ptr = p; send_w = (uint32_t)w; send_h = (uint32_t)h;
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
```

AIS exclusive-opens: mosaic tiles update in round-robin, not 30 fps each. Single-camera views are live.

---

## 8. Shark camera IDs and live-view mapping

Stock Sealion 7 DiLink 5 map (do **not** use on Shark):

| Role               | AIS |
| ------------------ | --- |
| Front / windshield | 0   |
| Rear               | 1   |
| Left               | 2   |
| Right              | 3   |

**Shark 6** (field-verified):

| Role                   | AIS |
| ---------------------- | --- |
| Cabin / driver monitor | 0   |
| Left                   | 4   |
| Rear                   | 5   |
| Front / windshield     | 8   |
| Right                  | 9   |

Encoder / native frame: 1920×1300 AIS, encode 1920×1080.

### Infer

```
normalized vehicle model contains "shark"          → Shark profile
normalized contains "sealion"                      → Sealion 7 profile
ro.vehicle.type contains "DXF" (this unit DXF_W)   → Shark profile
libais_client.so exists, no other hint             → Sealion 7 (do not steal SL7)
```

Read `ro.vehicle.type` via hidden `android.os.SystemProperties.get(String, String)`. `ro.product.model` is `BYD AUTO` and is useless.

If the user locked camera profile to Sealion 7, or selected vehicle model Sealion 7, inference never runs — they will keep 0–3. Auto + no Sealion selection is the Shark path.

### Live-view UI modes (not AIS ids)

| UI mode | Meaning     | Shark AIS byte to sidecar    |
| ------- | ----------- | ---------------------------- |
| 0       | ALL         | **31** (mosaic), not 8       |
| 1       | Front       | 8                            |
| 2       | Right       | 9                            |
| 3       | Rear        | 5                            |
| 4       | Left        | 4                            |
| 6       | OEM DVR     | existing OEM path, unchanged |
| 9       | Cabin / DMS | 0                            |

```
aisForView(mode):
  0 → 31
  1 → 8
  2 → 9
  3 → 5
  4 → 4
  9 → 0
```

HTTP `/api/stream/view/{mode}` already uses those UI numbers. Persist mode **9** the same way as 0–6 (exclude 7/8, those are blind-spot). Scaler `setViewMode` must accept 9 and treat it as full-frame (same as 1–4 on `cameraLayout==1`).

### ALL mosaic layout (2×3, one black)

```
[8 front] [9 right] [5 rear]
[4 left ] [0 cabin] [black ]
```

Argv: `mosaic=8,9,5,4,0,-1`. Slot `-1` stays UYVY black (`U=0x80, Y=0x10`).

Live-view car graphic: keep ALL (mode 0). Add a **CABIN** hotspot at ~36% from the top of the silhouette, `data-cam="9"`, label “CABIN” / “Driver Monitor”. Do not hide ALL.

On DiLink 5, `cameraLayout = 1` (full frame). View 0 must **passthrough** the sidecar mosaic, not cut a 2×2 from a pano strip.

---

## 9. Diagnostic: staging vendor libs into `/data` (optional probe APK)

The production sidecar does **not** need this. Used only to prove AIS before the sidecar existed.

A normal APK cannot read `/vendor/lib64/*.so`. ADB shell can `cp` them.

```
# from a PC — destination is emulated app files (writable), NOT /data/local/tmp
adb shell cp /vendor/lib64/libais_client.so \
             /vendor/lib64/libmmosal.so \
             /vendor/lib64/libuhab.so \
             /sdcard/Android/data/<probe.pkg>/files/
```

Then the app **must copy** those three files into `/data/user/0/<probe.pkg>/files/ais/` and `System.load` **from `/data`**, never from `/storage`.

`run-as` cannot read `/sdcard/Android/data/...` (scoped storage). The app copies itself, or you pipe `cat`.

Vendor `DT_NEEDED` names `libc++.so`, `libutils.so`, `libcutils.so`. Empty `.so` shims with those sonames satisfy the linker; real symbols come from the public runtime. An empty C file compiled three times as `c++`, `utils`, `cutils` is enough.

Do **not** commit vendor `.so` files.

---

## 10. Other landmines

- Laptop ADB and in-app dadb use **different RSA keys**. Looks like “ADB is broken”; it is two authorizations.
- `minSdk=28` > `targetSdk=25` on this OverDrive tree is intentional.
- `vendor.qti.automotive.qcarcam@1.0-service` is always running; that is normal.
- External references that helped the ABI (not required to rebuild): VitalyArt `byd-360-cam-for-51-chipset`, OverDrive GitHub issue #3 (francescodoffizi / VitalyArt, Aug 2026). SL7 live camera + dashcam; v45 claimed SL7. A Shark owner (`sp-hy`) hit `/data/` denial and emulated-SD stats the same way.

---

## Re-apply order

1. Daemon scratch dir + TMPDIR on all ADB script writes the camera/daemon path uses.
2. Drop in `ais_direct` (section 6) and `ais_capture` (section 7). CMake copy to `libais_capture.so`.
3. Start sidecar with `linker64` + `pm path` (section 4). Socket client (section 5). **Always kill stale sidecar/daemon on start.**
4. Shark IDs + infer + UI mode → AIS byte (section 8). Cabin hotspot. Mosaic cmd 31. Default cam 8.
5. Confirm logs: `ais_capture.log` under the emulated daemon dir shows `PROBE_OK`, `stream open on camera 8`, then `frames=`.
6. Only then leftover `/data/local/tmp` in zrok/acc-sentry/audio.

Skip: vendor hook / LD_PRELOAD, in-process AIS from `app_process`, gradient fake sidecar, vendor libs in the APK, treating “libais_client exists” as streaming.
