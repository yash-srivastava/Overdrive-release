// HardwareBufferTextureBinder — JNI bridge for AHardwareBuffer → EGLImage →
// GL_TEXTURE_EXTERNAL_OES binding. Required for the ImageReader-based zero-copy
// camera path that bypasses SurfaceFlinger throttling on the SurfaceTexture
// consumer (see CAMERA_FPS_INVESTIGATION.md).
//
// Standard Android Java SDK does not expose eglCreateImageKHR /
// glEGLImageTargetTexture2DOES, so this small JNI bridge resolves them via
// eglGetProcAddress and exposes two methods to Java:
//
//   - probeExtensionsNative(): returns a string describing whether the EGL
//     extensions we need are available on the current display.
//   - bindHardwareBufferToTextureNative(HardwareBuffer, int textureId): wraps
//     the gralloc buffer as an EGLImage and binds it to the supplied OES
//     texture. Caller must hold a current EGL context on the calling thread.
//
// Lifetime: a fresh EGLImage is created and bound per call. After
// glEGLImageTargetTexture2DOES, the texture retains an internal ref to the
// gralloc buffer, so we can immediately destroy the EGLImage. The Java side
// is responsible for closing the Image / HardwareBuffer once the GL bind has
// completed.

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
// NOTE: <android/hardware_buffer_jni.h> declares
// AHardwareBuffer_fromHardwareBuffer with __INTRODUCED_IN(26). minSdk on this
// project is 28, so the symbol is always present at runtime — but the NDK
// build targets the minSdk floor and the availability checker rejects direct
// calls. We resolve via dlsym to keep the build clean across NDK versions.
#include <dlfcn.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

#define TAG "HwBufferBinder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Function pointer types for runtime extension resolution.
typedef EGLClientBuffer (EGLAPIENTRYP PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)(
    const struct AHardwareBuffer* buffer);
typedef EGLImageKHR (EGLAPIENTRYP PFNEGLCREATEIMAGEKHRPROC)(
    EGLDisplay dpy, EGLContext ctx, EGLenum target,
    EGLClientBuffer buffer, const EGLint* attrib_list);
typedef EGLBoolean (EGLAPIENTRYP PFNEGLDESTROYIMAGEKHRPROC)(
    EGLDisplay dpy, EGLImageKHR image);
typedef void (GL_APIENTRYP PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)(
    GLenum target, GLeglImageOES image);

// Signature of AHardwareBuffer_fromHardwareBuffer; resolved via dlsym so the
// build doesn't need NDK API 26+.
typedef AHardwareBuffer* (*PFN_AHB_FROM_HARDWARE_BUFFER)(JNIEnv*, jobject);

struct ExtFns {
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = nullptr;
    PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = nullptr;
    PFN_AHB_FROM_HARDWARE_BUFFER ahbFromHardwareBuffer = nullptr;
    bool resolved = false;
    bool ok = false;
};

ExtFns g_fns;
std::once_flag g_resolveOnce;

void resolveExtensions() {
    std::call_once(g_resolveOnce, []() {
        g_fns.eglGetNativeClientBufferANDROID =
            (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC) eglGetProcAddress(
                "eglGetNativeClientBufferANDROID");
        g_fns.eglCreateImageKHR =
            (PFNEGLCREATEIMAGEKHRPROC) eglGetProcAddress("eglCreateImageKHR");
        g_fns.eglDestroyImageKHR =
            (PFNEGLDESTROYIMAGEKHRPROC) eglGetProcAddress("eglDestroyImageKHR");
        g_fns.glEGLImageTargetTexture2DOES =
            (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC) eglGetProcAddress(
                "glEGLImageTargetTexture2DOES");

        // libnativewindow.so hosts AHardwareBuffer_fromHardwareBuffer on
        // Android 8+. RTLD_DEFAULT works too on most builds since the symbol
        // is exposed by the bionic dynamic linker — fall back to that if the
        // explicit dlopen fails.
        void* lib = dlopen("libnativewindow.so", RTLD_NOW);
        if (lib != nullptr) {
            g_fns.ahbFromHardwareBuffer =
                (PFN_AHB_FROM_HARDWARE_BUFFER) dlsym(
                    lib, "AHardwareBuffer_fromHardwareBuffer");
        }
        if (g_fns.ahbFromHardwareBuffer == nullptr) {
            g_fns.ahbFromHardwareBuffer =
                (PFN_AHB_FROM_HARDWARE_BUFFER) dlsym(
                    RTLD_DEFAULT, "AHardwareBuffer_fromHardwareBuffer");
        }

        g_fns.ok = g_fns.eglGetNativeClientBufferANDROID
                && g_fns.eglCreateImageKHR
                && g_fns.eglDestroyImageKHR
                && g_fns.glEGLImageTargetTexture2DOES
                && g_fns.ahbFromHardwareBuffer;
        if (!g_fns.ok) {
            LOGW("symbol resolution failed: nativeClientBuf=%p createImage=%p "
                 "destroyImage=%p targetTexture=%p ahbFromHwb=%p",
                 g_fns.eglGetNativeClientBufferANDROID,
                 g_fns.eglCreateImageKHR,
                 g_fns.eglDestroyImageKHR,
                 g_fns.glEGLImageTargetTexture2DOES,
                 g_fns.ahbFromHardwareBuffer);
        }
        g_fns.resolved = true;
    });
}

bool stringContains(const char* hay, const char* needle) {
    return hay && needle && std::strstr(hay, needle) != nullptr;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_overdrive_app_camera_HardwareBufferTextureBinder_probeExtensionsNative(
    JNIEnv* env, jclass /*clazz*/) {
    resolveExtensions();

    EGLDisplay dpy = eglGetCurrentDisplay();
    const char* eglExt = (dpy != EGL_NO_DISPLAY)
        ? eglQueryString(dpy, EGL_EXTENSIONS) : nullptr;
    const char* glExt = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));

    bool hasNativeClientBuf = stringContains(eglExt,
        "EGL_ANDROID_get_native_client_buffer");
    bool hasImageNativeBuf = stringContains(eglExt,
        "EGL_ANDROID_image_native_buffer");
    bool hasImageBase = stringContains(eglExt, "EGL_KHR_image_base");
    bool hasOesEglImage = stringContains(glExt, "GL_OES_EGL_image_external");

    std::string report;
    report += "fnsResolved=";        report += g_fns.ok ? "true" : "false";
    report += " ahbFromHwb=";        report += g_fns.ahbFromHardwareBuffer ? "y" : "n";
    report += " EGL_KHR_image_base=";          report += hasImageBase ? "y" : "n";
    report += " EGL_ANDROID_get_native_client_buffer="; report += hasNativeClientBuf ? "y" : "n";
    report += " EGL_ANDROID_image_native_buffer=";      report += hasImageNativeBuf ? "y" : "n";
    report += " GL_OES_EGL_image_external=";   report += hasOesEglImage ? "y" : "n";
    report += " currentDisplay=";    report += (dpy != EGL_NO_DISPLAY) ? "yes" : "no";

    return env->NewStringUTF(report.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_HardwareBufferTextureBinder_bindHardwareBufferToTextureNative(
    JNIEnv* env, jclass /*clazz*/, jobject jHwBuffer, jint textureId) {
    if (jHwBuffer == nullptr) {
        LOGE("bindHardwareBufferToTexture: null HardwareBuffer");
        return JNI_FALSE;
    }
    resolveExtensions();
    if (!g_fns.ok) {
        LOGE("bindHardwareBufferToTexture: extension fns not available");
        return JNI_FALSE;
    }

    AHardwareBuffer* ahb = g_fns.ahbFromHardwareBuffer(env, jHwBuffer);
    if (ahb == nullptr) {
        LOGE("AHardwareBuffer_fromHardwareBuffer returned null");
        return JNI_FALSE;
    }

    EGLClientBuffer clientBuf = g_fns.eglGetNativeClientBufferANDROID(ahb);
    if (clientBuf == nullptr) {
        LOGE("eglGetNativeClientBufferANDROID returned null");
        return JNI_FALSE;
    }

    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY) {
        LOGE("no current EGL display on calling thread");
        return JNI_FALSE;
    }

    static const EGLint kAttrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };
    EGLImageKHR image = g_fns.eglCreateImageKHR(
        dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuf, kAttrs);
    if (image == EGL_NO_IMAGE_KHR) {
        LOGE("eglCreateImageKHR failed err=0x%x", eglGetError());
        return JNI_FALSE;
    }

    glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(textureId));
    g_fns.glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, image);
    GLenum glErr = glGetError();

    // Texture retains an internal ref to the gralloc buffer; the EGLImage
    // wrapper itself is no longer needed.
    g_fns.eglDestroyImageKHR(dpy, image);

    if (glErr != GL_NO_ERROR) {
        LOGE("glEGLImageTargetTexture2DOES failed glErr=0x%x", glErr);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// ---- Placeholder: parks the camera texture on a small black buffer of its own ----
namespace {

typedef int (*PFN_AHB_ALLOCATE)(const AHardwareBuffer_Desc*, AHardwareBuffer**);
typedef int (*PFN_AHB_LOCK)(AHardwareBuffer*, uint64_t, int32_t, const ARect*, void**);
typedef int (*PFN_AHB_LOCK_PLANES)(AHardwareBuffer*, uint64_t, int32_t, const ARect*, AHardwareBuffer_Planes*);
typedef int (*PFN_AHB_UNLOCK)(AHardwareBuffer*, int32_t*);
typedef void (*PFN_AHB_RELEASE)(AHardwareBuffer*);

struct PlaceholderFns {
    PFN_AHB_ALLOCATE allocate = nullptr;
    PFN_AHB_LOCK lock = nullptr;
    PFN_AHB_LOCK_PLANES lockPlanes = nullptr;  // API 29
    PFN_AHB_UNLOCK unlock = nullptr;
    PFN_AHB_RELEASE release = nullptr;
};

PlaceholderFns g_ph;
std::once_flag g_phOnce;

void* findAhbSymbol(void* lib, const char* name) {
    void* fn = lib != nullptr ? dlsym(lib, name) : nullptr;
    return fn != nullptr ? fn : dlsym(RTLD_DEFAULT, name);
}

void resolvePlaceholderFns() {
    std::call_once(g_phOnce, []() {
        void* lib = dlopen("libnativewindow.so", RTLD_NOW);
        g_ph.allocate = (PFN_AHB_ALLOCATE) findAhbSymbol(lib, "AHardwareBuffer_allocate");
        g_ph.lock = (PFN_AHB_LOCK) findAhbSymbol(lib, "AHardwareBuffer_lock");
        g_ph.lockPlanes = (PFN_AHB_LOCK_PLANES) findAhbSymbol(lib, "AHardwareBuffer_lockPlanes");
        g_ph.unlock = (PFN_AHB_UNLOCK) findAhbSymbol(lib, "AHardwareBuffer_unlock");
        g_ph.release = (PFN_AHB_RELEASE) findAhbSymbol(lib, "AHardwareBuffer_release");
        if (!g_ph.allocate || !g_ph.lock || !g_ph.unlock || !g_ph.release) {
            LOGW("placeholder: AHardwareBuffer allocate/lock/unlock/release not resolved");
        }
    });
}

AHardwareBuffer* allocateBlack(uint32_t format, uint32_t size) {
    AHardwareBuffer_Desc desc = {};
    desc.width = size;
    desc.height = size;
    desc.layers = 1;
    desc.format = format;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY;
    AHardwareBuffer* ahb = nullptr;
    if (g_ph.allocate(&desc, &ahb) != 0 || ahb == nullptr) return nullptr;
    bool filled = false;
    if (format == AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420) {
        // Limited-range black, the camera reader's own format.
        AHardwareBuffer_Planes planes = {};
        if (g_ph.lockPlanes != nullptr
                && g_ph.lockPlanes(ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, nullptr, &planes) == 0) {
            if (planes.planeCount >= 3) {
                for (uint32_t p = 0; p < 3; p++) {
                    const AHardwareBuffer_Plane& pl = planes.planes[p];
                    const uint32_t rows = p == 0 ? size : size / 2;
                    const uint32_t cols = p == 0 ? size : size / 2;
                    const uint8_t value = p == 0 ? 16 : 128;
                    auto* base = static_cast<uint8_t*>(pl.data);
                    for (uint32_t r = 0; base != nullptr && r < rows; r++) {
                        for (uint32_t c = 0; c < cols; c++) base[r * pl.rowStride + c * pl.pixelStride] = value;
                    }
                }
                filled = true;
            }
            g_ph.unlock(ahb, nullptr);
        }
    } else {
        void* px = nullptr;
        if (g_ph.lock(ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY, -1, nullptr, &px) == 0) {
            if (px != nullptr) {
                static const uint8_t kOpaqueBlack[4] = {0, 0, 0, 255};
                std::memcpy(px, kOpaqueBlack, sizeof(kOpaqueBlack));
                filled = true;
            }
            g_ph.unlock(ahb, nullptr);
        }
    }
    if (!filled) {
        g_ph.release(ahb);
        return nullptr;
    }
    return ahb;
}

}  // namespace

// Re-targets the external texture to a black buffer, so it stops importing the
// camera buffer it was last bound to. Caller's thread must hold a context.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_HardwareBufferTextureBinder_bindPlaceholderNative(
    JNIEnv* /*env*/, jclass /*clazz*/, jint textureId) {
    resolveExtensions();
    resolvePlaceholderFns();
    if (!g_fns.ok || !g_ph.allocate || !g_ph.lock || !g_ph.unlock || !g_ph.release) {
        return JNI_FALSE;
    }
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (dpy == EGL_NO_DISPLAY || eglGetCurrentContext() == EGL_NO_CONTEXT) return JNI_FALSE;

    AHardwareBuffer* ahb = allocateBlack(AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420, 16);
    if (ahb == nullptr) ahb = allocateBlack(AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM, 1);
    if (ahb == nullptr) {
        LOGE("placeholder: buffer allocation failed");
        return JNI_FALSE;
    }

    bool ok = false;
    EGLClientBuffer clientBuf = g_fns.eglGetNativeClientBufferANDROID(ahb);
    static const EGLint kAttrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLImageKHR image = clientBuf != nullptr
        ? g_fns.eglCreateImageKHR(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuf, kAttrs)
        : EGL_NO_IMAGE_KHR;
    if (image != EGL_NO_IMAGE_KHR) {
        for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; i++) {}
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, static_cast<GLuint>(textureId));
        g_fns.glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, image);
        const GLenum glErr = glGetError();
        ok = glErr == GL_NO_ERROR;
        if (!ok) LOGE("placeholder: glEGLImageTargetTexture2DOES failed glErr=0x%x", glErr);
        g_fns.eglDestroyImageKHR(dpy, image);
    } else {
        LOGE("placeholder: eglCreateImageKHR failed err=0x%x", eglGetError());
    }
    g_ph.release(ahb);  // a bound texture keeps its own reference
    return ok ? JNI_TRUE : JNI_FALSE;
}
