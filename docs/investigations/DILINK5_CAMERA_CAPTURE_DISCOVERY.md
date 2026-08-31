# BYD DiLink 5.0 (Qualcomm Snapdragon SA8155P) Camera Architecture & Hardware Stream Extraction

## Executive Summary
On **BYD DiLink 5.0** vehicles (e.g., **BYD Sealion 7**, Snapdragon SA8155P / `msmnile` running Android 11 Automotive inside a QNX Hypervisor VM), the legacy **`android.hardware.AVMCamera`** Java class present on DiLink 3.0 and 4.0 has been completely removed from the Android framework.

Additionally:
1. Standard **Android Camera2 API** (`CameraManager`) rejects non-system applications with `Permission failure: android.permission.SYSTEM_CAMERA from uid=100xx` and `connectHelper:1734: Illegal argument to HAL module (-22)`.
2. The 360° Surround View app (`com.byd.avm`) renders onto a hardware overlay plane below Android's `SurfaceFlinger`, causing `screencap` to return black/transparent images.

**The Solution / Breakthrough**:  
We discovered that the low-level camera pipeline is managed by Qualcomm's **Automotive Imaging Subsystem (AIS / QCarCam)**. The vendor library **`/vendor/lib64/libais_client.so`** is accessible directly in **userspace without root permissions**, allowing OverDrive to open, stream, and capture raw uncompressed video frames (**1920x1300 @ 30.0 FPS**, YUV 4:2:2 UYVY) for Dashcam recording, Sentry Mode, and AI object detection.

---

## Technical Architecture Comparison

| Component | DiLink 3.0 / 4.0 (e.g. Seal, Atto 3, Dolphin) | DiLink 5.0 (e.g. Sealion 7, Snapdragon 8155) |
| :--- | :--- | :--- |
| **SoC Platform** | Qualcomm Snapdragon 665 / 690 / 6125 | Qualcomm Snapdragon SA8155P (`msmnile`) |
| **OS Architecture** | Android 10 Automotive (Bare Metal / Semi-Virtual) | Android 11 Automotive (Guest VM over QNX 7.1) |
| **Java Framework Class** | `android.hardware.AVMCamera` (Reflection) | **Removed** (`ClassNotFoundException`) |
| **OEM Surround View App** | `com.byd.apa` / `com.byd.avm` | `com.byd.avm` + `com.ts.avm` (AIDL Service) |
| **Native Video Pipeline** | BMM Camera Server (`/system/framework/bmmcamera.jar`) | **Qualcomm AIS Client** (`/vendor/lib64/libais_client.so`) |
| **Native Resolution** | 5120x960 (Mosaic Strip) / 1280x720 | **1920x1300 per camera** (Front, Rear, Left, Right) |
| **Frame Rate** | 15 - 30 FPS | **30.0 FPS rock solid** |

---

## Hardware Validation & Proof of Concept

### 1. Live Hardware Stream Verification
Executing the vendor test diagnostic `/vendor/bin/qcarcam_test -config=/vendor/bin/1cam.xml` established a direct stream with Camera ID 0:
```text
Success - First Frame [0:0]
fps: 30.0 | rel: 30.0 | diff: 33.3 ms
```

### 2. Live Frame Extraction
We extracted consecutive raw frames (`frame_0_10.raw`) directly from the video buffer:
* **Raw Frame Size**: Exactly `4,992,000 bytes`
* **Dimensions**: `1920 x 1300` pixels
* **Color Format**: `YUV 4:2:2 UYVY` (2 bytes per pixel: `1920 * 1300 * 2 = 4,992,000`)
* **Visual Confirmation**: Successfully decoded into RGB/PNG, showing full ultra-wide perspective from the front bumper.

---

## Native C++ Implementation (Qualcomm AIS / QCarCam JNI Bridge)

The native bridge interacts with `/vendor/lib64/libais_client.so` using standard POSIX `dlopen` and `dlsym`:

```cpp
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>

typedef int   (*qcarcam_init_fn)(void*);
typedef void* (*qcarcam_open_fn)(int);
typedef int   (*qcarcam_start_fn)(void*);
typedef int   (*qcarcam_stop_fn)(void*);
typedef int   (*qcarcam_close_fn)(void*);
typedef int   (*qcarcam_uninit_fn)();

struct QCarCamClient {
    void* handle_lib = nullptr;
    qcarcam_init_fn   init = nullptr;
    qcarcam_open_fn   open = nullptr;
    qcarcam_start_fn  start = nullptr;
    qcarcam_stop_fn   stop = nullptr;
    qcarcam_close_fn  close = nullptr;
    qcarcam_uninit_fn uninit = nullptr;
};

static QCarCamClient g_client;

bool initQCarCam() {
    g_client.handle_lib = dlopen("/vendor/lib64/libais_client.so", RTLD_NOW);
    if (!g_client.handle_lib) return false;

    g_client.init   = (qcarcam_init_fn)dlsym(g_client.handle_lib, "qcarcam_initialize");
    g_client.open   = (qcarcam_open_fn)dlsym(g_client.handle_lib, "qcarcam_open");
    g_client.start  = (qcarcam_start_fn)dlsym(g_client.handle_lib, "qcarcam_start");
    g_client.stop   = (qcarcam_stop_fn)dlsym(g_client.handle_lib, "qcarcam_stop");
    g_client.close  = (qcarcam_close_fn)dlsym(g_client.handle_lib, "qcarcam_close");
    g_client.uninit = (qcarcam_uninit_fn)dlsym(g_client.handle_lib, "qcarcam_uninitialize");

    if (!g_client.init || !g_client.open || !g_client.start) return false;

    g_client.init(nullptr);
    return true;
}
```

---

## Java / Kotlin Architecture in OverDrive

### 1. Dynamic Platform Detection (`DiLink5QCarCamBackend.java`)
```java
public class DiLink5QCarCamBackend {
    public static boolean isSupported() {
        try {
            return nativeIsSupported(); // checks /vendor/lib64/libais_client.so
        } catch (Throwable t) {
            return false;
        }
    }
}
```

### 2. Safe Fallback in `PanoramicCameraGpu.java`
```java
// DiLink 5.0 (Snapdragon SA8155P): uses native QCarCam / AIS backend directly
if (DiLink5QCarCamBackend.isSupported()) {
    DiLink5QCarCamBackend dilink5Backend = new DiLink5QCarCamBackend(cameraId);
    if (dilink5Backend.start()) {
        logger.info("DiLink 5 native QCarCam stream active.");
        return;
    }
}

// Fallback to legacy reflection on DiLink 3/4
Class<?> avmClass;
try {
    avmClass = Class.forName("android.hardware.AVMCamera");
} catch (ClassNotFoundException e) {
    logger.warn("AVMCamera absent — non-fatal on DiLink 5+");
    return;
}
```

### 3. OEM 360° Service Coordination via AIDL (`TsAvmCoordinator.java`)
To interact with the OEM surround view without driver arbitration conflicts, OverDrive connects to the DiLink 5 system service:
* **Package**: `com.ts.avm`
* **Service**: `com.ts.avm.AvmAndroidService`
* **AIDL Interface**: `com.ts.avm.IAvmServiceInterface` (`getAvmStatus()`, `startAvm()`, `stopAvm()`).

---

## Conclusion & Benefits
1. **Full Dashcam & Sentry Support on DiLink 5**: Enables 1920x1300 @ 30 FPS video recording on BYD Sealion 7 and future DiLink 5 / Snapdragon 8155 models.
2. **Zero-Copy Performance**: Raw frame buffers feed directly into Android's `MediaCodec` H.264/H.265 hardware encoder and OpenGL ES pipeline.
3. **100% Backward Compatibility**: Existing DiLink 3.0 and 4.0 vehicles continue using the `AVMCamera` reflection path without any regressions.
