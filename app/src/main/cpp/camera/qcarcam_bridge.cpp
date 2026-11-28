// DiLink 5 fast_cam_capture bridge.
//
// Preferred path: import each linear UYVY DMA-BUF once as an EGLImage, render
// it into the caller-owned GL_TEXTURE_2D, then return the producer buffer with
// a native GPU fence. If the vendor EGL stack rejects DMA-BUF import, the same
// protocol falls back to NEON conversion plus glTexSubImage2D.

#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <arm_neon.h>
#include <errno.h>
#include <linux/dma-buf.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <strings.h>
#include <sys/ioctl.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <vector>

#include "fast_cam_bridge.h"
#include "fast_cam_ipc.h"

#define TAG "QCarCamBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kSensorWidth = 1920;
constexpr int kSensorHeight = 1300;
constexpr int kOutputWidth = 1920;
constexpr int kOutputHeight = 1080;
constexpr int kCropTop = (kSensorHeight - kOutputHeight) / 2;
constexpr int kMosaicHalfWidth = kOutputWidth / 2;
constexpr int kMosaicHalfHeight = kOutputHeight / 2;
constexpr int kDefaultCaptureFps = 30;
constexpr int kStreamJoinTimeoutMs = 3000;
// CPU-copy staging geometry. The mosaic consumer only ever reads the
// center-cropped rows at 2:1 vertical decimation (mosaicSourceYForOutputRow)
// and keeps every other UYVY pair (mosaicSourcePairForOutputPair), so the
// staging copy stores exactly that subset: 540 rows x 960 px per camera
// instead of the full 1300 x 1920 sensor frame. Single-camera modes store
// the center-cropped 1080 rows at full width.
constexpr int kStagingMosaicRows = kMosaicHalfHeight;          // 540
constexpr int kStagingMosaicRowBytes = kMosaicHalfWidth * 2;   // 960 px UYVY
constexpr int kStagingSingleRows = kOutputHeight;              // 1080
constexpr int kStagingSingleRowBytes = kOutputWidth * 2;       // 1920 px UYVY
// UYVY staging texture for the GPU unpack path: one RGBA8 texel packs one
// UYVY pair (two output pixels), so the 1920x1080 output maps to 960x1080.
constexpr int kUnpackTexelsPerRow = kOutputWidth / 2;          // 960
constexpr int kUnpackTextureRows = kOutputHeight;              // 1080
// Kill switch for the GPU UYVY unpack stage of the CPU-copy transport.
// "0"/"false" reverts to the NEON convertUyvyToRgba + RGBA upload path at
// the next camera session without an OTA. Producer DMA-BUFs are never
// imported in either stage; only our own staging bytes touch GL.
constexpr char kGpuUnpackProperty[] =
        "persist.overdrive.dilink5_gpu_unpack";
// Throttle for the CPU-path stage timing log (copy/convert/upload).
constexpr uint64_t kCpuStatsLogIntervalNs = 120ull * 1000000000ull;
constexpr EGLint kDrmFormatUyvy = 0x59565955;  // DRM_FORMAT_UYVY
constexpr GLuint kPositionAttribute = 6;
constexpr GLuint kTexCoordAttribute = 7;
constexpr char kAisClientPath[] = "/vendor/lib64/libais_client.so";

struct CameraMapping {
    std::atomic<int> front{0};
    std::atomic<int> right{1};
    std::atomic<int> rear{2};
    std::atomic<int> left{3};
    std::atomic<int> dashcam{-1};
};

enum class IngestMode : int {
    kUnknown,
    kDirectDma,
    kCpuFallback,
};

#ifndef OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA
#define OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA 0
#endif

static_assert(
        OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA == 0
                || OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA == 1,
        "OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA must be 0 or 1");

constexpr bool kDirectDmaEnabled =
        OVERDRIVE_DILINK5_ENABLE_DIRECT_DMA == 1;

constexpr IngestMode configuredInitialIngestMode() {
    return kDirectDmaEnabled
            ? IngestMode::kUnknown
            : IngestMode::kCpuFallback;
}

constexpr bool shouldUseCpuCopy(IngestMode mode) {
    return !kDirectDmaEnabled || mode == IngestMode::kCpuFallback;
}

constexpr const char* configuredIngestPolicyName() {
    return kDirectDmaEnabled
            ? "direct-dma-experimental-with-cpu-fallback"
            : "cpu-copy-safe";
}

static_assert(
        kDirectDmaEnabled
                || configuredInitialIngestMode()
                        == IngestMode::kCpuFallback,
        "Production DiLink 5 builds must start in CPU-copy mode");

struct CameraFrame {
    const uint8_t* pixels = nullptr;
    uint32_t stride = 0;
};

struct CpuCameraFrame {
    std::vector<uint8_t> bytes;
    uint32_t stride = 0;      // staging row bytes (tightly packed)
    uint32_t rows = 0;        // staging row count
    bool decimated = false;   // true = mosaic quadrant subset layout
    bool valid = false;
};

struct PendingFrame {
    FastCamFrame frame = {};
    FastCamClient* client = nullptr;
    uint32_t session = 0;
    int selected = 4;
    bool publish = false;
    bool valid = false;
};

struct ImportedBuffer {
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    uint32_t session = 0;
    int dma_fd = -1;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t stride = 0;
};

struct GraphicsExtensions {
    PFNEGLCREATEIMAGEKHRPROC create_image = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroy_image = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC target_texture = nullptr;
    PFNEGLCREATESYNCKHRPROC create_sync = nullptr;
    PFNEGLDESTROYSYNCKHRPROC destroy_sync = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC dup_native_fence = nullptr;
    bool resolved = false;
};

struct SavedGlState {
    GLint framebuffer = 0;
    GLint viewport[4] = {};
    GLint program = 0;
    GLint active_texture = GL_TEXTURE0;
    GLint texture_2d = 0;
    GLint texture_external = 0;
    GLint array_buffer = 0;
    GLint unpack_alignment = 4;
    GLboolean blend = GL_FALSE;
    GLboolean scissor = GL_FALSE;
    GLboolean depth = GL_FALSE;
    GLboolean cull = GL_FALSE;
    GLboolean color_mask[4] = {};
    GLfloat clear_color[4] = {};
};

struct SavedCpuUploadState {
    GLint active_texture = GL_TEXTURE0;
    GLint texture_2d = 0;
    GLint unpack_alignment = 4;
};

struct SavedVertexAttrib {
    GLint enabled = GL_FALSE;
    GLint size = 4;
    GLint type = GL_FLOAT;
    GLint normalized = GL_FALSE;
    GLint stride = 0;
    GLint buffer = 0;
    void* pointer = nullptr;
};

CameraMapping g_camera_mapping;
std::atomic<bool> g_streaming{false};
std::atomic<int> g_active_camera{4};
std::atomic<int> g_output_fps{15};
std::atomic<int> g_capture_fps{kDefaultCaptureFps};
std::atomic<IngestMode> g_ingest_mode{configuredInitialIngestMode()};
std::atomic<uint32_t> g_session_epoch{1};
std::atomic<uint32_t> g_active_session{0};
std::atomic<int> g_expected_server_pid{-1};
pthread_t g_stream_thread = 0;
bool g_stream_thread_running = false;
std::mutex g_stream_state_mutex;
std::condition_variable g_stream_stopped;
std::string g_socket_path;

std::mutex g_frame_mutex;
std::condition_variable g_frame_consumed;
PendingFrame g_pending_frame;
CpuCameraFrame g_cpu_cameras[FAST_CAM_MAX_CAMS];
std::vector<uint32_t> g_cpu_rgba[2];
int g_cpu_front_buffer = -1;
bool g_cpu_has_new_frame = false;
int g_cpu_selected_mode = -1;
std::atomic<uint64_t> g_latest_timestamp_ns{0};
// GPU unpack stage of the CPU-copy transport. When active, the client
// thread skips the NEON convert entirely and the GL thread uploads the
// UYVY staging bytes + runs one unpack draw into the output texture.
// Any GL failure latches the flag off for the session and the NEON path
// takes over from the next frame (one dropped frame at the transition).
std::atomic<bool> g_gpu_unpack_enabled{false};
bool g_cpu_staging_publish_pending = false;   // guarded by g_frame_mutex
GLuint g_unpack_texture = 0;
bool g_unpack_texture_allocated = false;
GLuint g_unpack_program = 0;
GLuint g_unpack_vertex_buffer = 0;
GLint g_unpack_source_uniform = -1;
// Stage timing (throttled diagnostics; nanosecond sums + counts).
std::atomic<uint64_t> g_stat_copy_ns{0};
std::atomic<uint64_t> g_stat_copy_count{0};
std::atomic<uint64_t> g_stat_copy_skipped{0};
std::atomic<uint64_t> g_stat_convert_ns{0};
std::atomic<uint64_t> g_stat_convert_count{0};
std::atomic<uint64_t> g_stat_upload_ns{0};
std::atomic<uint64_t> g_stat_upload_count{0};
std::atomic<uint64_t> g_stat_last_log_ns{0};

GraphicsExtensions g_graphics;
ImportedBuffer g_imported_buffers[FAST_CAM_MAX_TOTAL_BUFS];
EGLDisplay g_gl_display = EGL_NO_DISPLAY;
EGLContext g_gl_context = EGL_NO_CONTEXT;
uint32_t g_gl_session = 0;
GLuint g_program = 0;
GLuint g_vertex_buffer = 0;
GLuint g_framebuffer = 0;
GLuint g_output_texture = 0;
GLint g_source_texture_uniform = -1;
bool g_output_texture_allocated = false;
bool g_native_fence_supported = false;
int g_direct_selected_mode = -1;
uint8_t g_direct_mosaic_mask = 0;

JavaVM* g_java_vm = nullptr;
jclass g_backend_class = nullptr;
jmethodID g_on_frame_available = nullptr;

constexpr bool advancePacingPhase(
        int capture_fps,
        int output_fps,
        int& phase) {
    phase += output_fps;
    if (phase < capture_fps) return false;
    phase -= capture_fps;
    return true;
}

// Non-mutating forecast of the NEXT advancePacingPhase decision. Mosaic
// staging copies made between two anchor frames are only ever consumed by
// the next anchor's publish tick, so a copy whose upcoming anchor will not
// publish can be skipped entirely (no DMA sync, no read). The phase state
// only advances on anchor frames, which makes this peek exact between
// anchors; an fps knob change mid-window costs at most one 33 ms-stale
// quadrant on the following publish.
constexpr bool peekNextPacingPublish(
        int capture_fps,
        int output_fps,
        int phase) {
    return advancePacingPhase(capture_fps, output_fps, phase);
}

static_assert(!peekNextPacingPublish(30, 15, 0));
static_assert(peekNextPacingPublish(30, 15, 15));
static_assert(peekNextPacingPublish(30, 30, 0));

constexpr int pacedFrameCount(
        int capture_fps,
        int output_fps,
        int input_frames) {
    int phase = capture_fps - output_fps;
    int output_frames = 0;
    for (int i = 0; i < input_frames; i++) {
        if (advancePacingPhase(capture_fps, output_fps, phase)) {
            output_frames++;
        }
    }
    return output_frames;
}

static_assert(pacedFrameCount(30, 1, 30) == 1);
static_assert(pacedFrameCount(30, 20, 30) == 20);
static_assert(pacedFrameCount(30, 30, 30) == 30);
static_assert(pacedFrameCount(15, 15, 15) == 15);

constexpr int mosaicSourceYForOutputRow(int output_y) {
    return kCropTop + (output_y % kMosaicHalfHeight) * 2;
}

constexpr int mosaicSourcePairForOutputPair(int output_pair) {
    return output_pair * 2;
}

static_assert(mosaicSourceYForOutputRow(0) == kCropTop);
static_assert(mosaicSourceYForOutputRow(kMosaicHalfHeight - 1) == 1188);
static_assert(mosaicSourceYForOutputRow(kMosaicHalfHeight) == kCropTop);
static_assert(mosaicSourceYForOutputRow(kOutputHeight - 1) == 1188);
static_assert(mosaicSourcePairForOutputPair(0) == 0);
static_assert(mosaicSourcePairForOutputPair(kMosaicHalfWidth / 2 - 1)
        == 958);
static_assert((kMosaicHalfWidth / 2) % 4 == 0);

int semanticSlotFor(uint32_t hardware_camera_id) {
    if (hardware_camera_id
            == static_cast<uint32_t>(g_camera_mapping.front.load())) return 0;
    if (hardware_camera_id
            == static_cast<uint32_t>(g_camera_mapping.right.load())) return 1;
    if (hardware_camera_id
            == static_cast<uint32_t>(g_camera_mapping.rear.load())) return 2;
    if (hardware_camera_id
            == static_cast<uint32_t>(g_camera_mapping.left.load())) return 3;
    int dashcam = g_camera_mapping.dashcam.load();
    return dashcam >= 0
            && hardware_camera_id == static_cast<uint32_t>(dashcam)
            ? 6
            : -1;
}

uint64_t monotonicNowNs() {
    return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch())
                    .count());
}

bool hasExtension(const char* extensions, const char* wanted) {
    if (!extensions || !wanted || !wanted[0] || strchr(wanted, ' ')) {
        return false;
    }
    size_t length = strlen(wanted);
    const char* current = extensions;
    while ((current = strstr(current, wanted)) != nullptr) {
        bool left = current == extensions || current[-1] == ' ';
        bool right = current[length] == '\0' || current[length] == ' ';
        if (left && right) return true;
        current += length;
    }
    return false;
}

void clearGlErrors() {
    for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; i++) {}
}

void saveVertexAttrib(GLuint index, SavedVertexAttrib* state) {
    glGetVertexAttribiv(index, GL_VERTEX_ATTRIB_ARRAY_ENABLED, &state->enabled);
    glGetVertexAttribiv(index, GL_VERTEX_ATTRIB_ARRAY_SIZE, &state->size);
    glGetVertexAttribiv(index, GL_VERTEX_ATTRIB_ARRAY_TYPE, &state->type);
    glGetVertexAttribiv(
            index, GL_VERTEX_ATTRIB_ARRAY_NORMALIZED, &state->normalized);
    glGetVertexAttribiv(index, GL_VERTEX_ATTRIB_ARRAY_STRIDE, &state->stride);
    glGetVertexAttribiv(
            index, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING, &state->buffer);
    glGetVertexAttribPointerv(
            index, GL_VERTEX_ATTRIB_ARRAY_POINTER, &state->pointer);
}

void restoreVertexAttrib(GLuint index, const SavedVertexAttrib& state) {
    glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(state.buffer));
    glVertexAttribPointer(
            index,
            state.size,
            static_cast<GLenum>(state.type),
            static_cast<GLboolean>(state.normalized),
            state.stride,
            state.pointer);
    state.enabled
            ? glEnableVertexAttribArray(index)
            : glDisableVertexAttribArray(index);
}

void saveGlState(SavedGlState* state) {
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &state->framebuffer);
    glGetIntegerv(GL_VIEWPORT, state->viewport);
    glGetIntegerv(GL_CURRENT_PROGRAM, &state->program);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &state->active_texture);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &state->array_buffer);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &state->unpack_alignment);
    state->blend = glIsEnabled(GL_BLEND);
    state->scissor = glIsEnabled(GL_SCISSOR_TEST);
    state->depth = glIsEnabled(GL_DEPTH_TEST);
    state->cull = glIsEnabled(GL_CULL_FACE);
    glGetBooleanv(GL_COLOR_WRITEMASK, state->color_mask);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, state->clear_color);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &state->texture_2d);
    glGetIntegerv(
            GL_TEXTURE_BINDING_EXTERNAL_OES, &state->texture_external);
}

void restoreGlState(const SavedGlState& state) {
    glBindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(state.array_buffer));
    glBindFramebuffer(
            GL_FRAMEBUFFER, static_cast<GLuint>(state.framebuffer));
    glViewport(
            state.viewport[0],
            state.viewport[1],
            state.viewport[2],
            state.viewport[3]);
    glUseProgram(static_cast<GLuint>(state.program));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(state.texture_2d));
    glBindTexture(
            GL_TEXTURE_EXTERNAL_OES,
            static_cast<GLuint>(state.texture_external));
    glActiveTexture(static_cast<GLenum>(state.active_texture));
    glPixelStorei(GL_UNPACK_ALIGNMENT, state.unpack_alignment);
    state.blend ? glEnable(GL_BLEND) : glDisable(GL_BLEND);
    state.scissor ? glEnable(GL_SCISSOR_TEST) : glDisable(GL_SCISSOR_TEST);
    state.depth ? glEnable(GL_DEPTH_TEST) : glDisable(GL_DEPTH_TEST);
    state.cull ? glEnable(GL_CULL_FACE) : glDisable(GL_CULL_FACE);
    glColorMask(
            state.color_mask[0],
            state.color_mask[1],
            state.color_mask[2],
            state.color_mask[3]);
    glClearColor(
            state.clear_color[0],
            state.clear_color[1],
            state.clear_color[2],
            state.clear_color[3]);
}

bool saveCpuUploadState(SavedCpuUploadState* state) {
    if (!state) return false;
    clearGlErrors();
    glGetIntegerv(GL_ACTIVE_TEXTURE, &state->active_texture);
    glActiveTexture(GL_TEXTURE0);
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &state->texture_2d);
    glGetIntegerv(GL_UNPACK_ALIGNMENT, &state->unpack_alignment);
    GLenum error = glGetError();
    if (error == GL_NO_ERROR) return true;
    LOGE("FastCam CPU upload state capture failed: gl=0x%x", error);
    glActiveTexture(static_cast<GLenum>(state->active_texture));
    clearGlErrors();
    return false;
}

bool restoreCpuUploadState(const SavedCpuUploadState& state) {
    glPixelStorei(GL_UNPACK_ALIGNMENT, state.unpack_alignment);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(state.texture_2d));
    glActiveTexture(static_cast<GLenum>(state.active_texture));
    GLenum error = glGetError();
    if (error == GL_NO_ERROR) return true;
    LOGE("FastCam CPU upload state restore failed: gl=0x%x", error);
    clearGlErrors();
    return false;
}

void resolveGraphicsExtensions() {
    if (g_graphics.resolved) return;
    g_graphics.create_image =
            reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
                    eglGetProcAddress("eglCreateImageKHR"));
    g_graphics.destroy_image =
            reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
                    eglGetProcAddress("eglDestroyImageKHR"));
    g_graphics.target_texture =
            reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
                    eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    g_graphics.create_sync =
            reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
                    eglGetProcAddress("eglCreateSyncKHR"));
    g_graphics.destroy_sync =
            reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
                    eglGetProcAddress("eglDestroySyncKHR"));
    g_graphics.dup_native_fence =
            reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
                    eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    g_graphics.resolved = true;
}

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[512] = {};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("DMA compositor shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool ensureRendererLocked() {
    if (g_program
            && g_vertex_buffer
            && g_framebuffer
            && g_source_texture_uniform >= 0) {
        return true;
    }
    static const char* vertex_source =
            "attribute vec2 aPosition;\n"
            "attribute vec2 aTexCoord;\n"
            "varying vec2 vTexCoord;\n"
            "void main() {\n"
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            "  vTexCoord = aTexCoord;\n"
            "}\n";
    static const char* fragment_source =
            "#extension GL_OES_EGL_image_external : require\n"
            "precision mediump float;\n"
            "uniform samplerExternalOES uSource;\n"
            "varying vec2 vTexCoord;\n"
            "void main() {\n"
            "  gl_FragColor = texture2D(uSource, vTexCoord);\n"
            "}\n";

    GLuint vertex = compileShader(GL_VERTEX_SHADER, vertex_source);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, fragment_source);
    if (!vertex || !fragment) {
        if (vertex) glDeleteShader(vertex);
        if (fragment) glDeleteShader(fragment);
        return false;
    }
    g_program = glCreateProgram();
    glAttachShader(g_program, vertex);
    glAttachShader(g_program, fragment);
    glBindAttribLocation(g_program, kPositionAttribute, "aPosition");
    glBindAttribLocation(g_program, kTexCoordAttribute, "aTexCoord");
    glLinkProgram(g_program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(g_program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512] = {};
        glGetProgramInfoLog(g_program, sizeof(log), nullptr, log);
        LOGE("DMA compositor program link failed: %s", log);
        glDeleteProgram(g_program);
        g_program = 0;
        return false;
    }
    g_source_texture_uniform = glGetUniformLocation(g_program, "uSource");

    const GLfloat top =
            static_cast<GLfloat>(kCropTop + 0.5f) / kSensorHeight;
    const GLfloat bottom =
            static_cast<GLfloat>(
                    kCropTop + kOutputHeight - 0.5f) / kSensorHeight;
    const GLfloat vertices[] = {
        -1.0f, -1.0f, 0.0f, bottom,
         1.0f, -1.0f, 1.0f, bottom,
        -1.0f,  1.0f, 0.0f, top,
         1.0f,  1.0f, 1.0f, top,
    };
    glGenBuffers(1, &g_vertex_buffer);
    glBindBuffer(GL_ARRAY_BUFFER, g_vertex_buffer);
    glBufferData(
            GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    glGenFramebuffers(1, &g_framebuffer);
    bool ready = g_source_texture_uniform >= 0
            && g_vertex_buffer != 0
            && g_framebuffer != 0
            && glGetError() == GL_NO_ERROR;
    if (!ready) {
        if (g_vertex_buffer) glDeleteBuffers(1, &g_vertex_buffer);
        if (g_framebuffer) glDeleteFramebuffers(1, &g_framebuffer);
        if (g_program) glDeleteProgram(g_program);
        g_program = 0;
        g_vertex_buffer = 0;
        g_framebuffer = 0;
        g_source_texture_uniform = -1;
    }
    return ready;
}

bool finishGpuLocked(const char* operation) {
    clearGlErrors();
    glFinish();
    GLenum error = glGetError();
    if (error == GL_NO_ERROR) return true;
    LOGE("%s failed: gl=0x%x", operation, error);
    return false;
}

bool destroyImportedBufferLocked(ImportedBuffer* imported) {
    if (!imported) return false;
    if (imported->texture) {
        clearGlErrors();
        glDeleteTextures(1, &imported->texture);
        GLenum error = glGetError();
        if (error != GL_NO_ERROR) {
            LOGE("Imported texture destruction failed: gl=0x%x", error);
            return false;
        }
        imported->texture = 0;
    }
    if (imported->image != EGL_NO_IMAGE_KHR) {
        if (g_gl_display == EGL_NO_DISPLAY
                || !g_graphics.destroy_image
                || g_graphics.destroy_image(
                        g_gl_display, imported->image) != EGL_TRUE) {
            LOGE("Imported EGLImage destruction failed: egl=0x%x",
                    eglGetError());
            return false;
        }
        imported->image = EGL_NO_IMAGE_KHR;
    }
    *imported = ImportedBuffer();
    return true;
}

bool destroyImportedBuffersLocked() {
    for (ImportedBuffer& imported : g_imported_buffers) {
        if (!destroyImportedBufferLocked(&imported)) return false;
    }
    return true;
}

bool destroyGlResourcesLocked() {
    if (!finishGpuLocked("FastCam GPU retirement")
            || !destroyImportedBuffersLocked()) {
        return false;
    }
    if (g_vertex_buffer) {
        clearGlErrors();
        glDeleteBuffers(1, &g_vertex_buffer);
        if (glGetError() != GL_NO_ERROR) return false;
        g_vertex_buffer = 0;
    }
    if (g_framebuffer) {
        clearGlErrors();
        glDeleteFramebuffers(1, &g_framebuffer);
        if (glGetError() != GL_NO_ERROR) return false;
        g_framebuffer = 0;
    }
    if (g_program) {
        clearGlErrors();
        glDeleteProgram(g_program);
        if (glGetError() != GL_NO_ERROR) return false;
        g_program = 0;
    }
    if (g_unpack_vertex_buffer) {
        clearGlErrors();
        glDeleteBuffers(1, &g_unpack_vertex_buffer);
        if (glGetError() != GL_NO_ERROR) return false;
        g_unpack_vertex_buffer = 0;
    }
    if (g_unpack_program) {
        clearGlErrors();
        glDeleteProgram(g_unpack_program);
        if (glGetError() != GL_NO_ERROR) return false;
        g_unpack_program = 0;
    }
    if (g_unpack_texture) {
        clearGlErrors();
        glDeleteTextures(1, &g_unpack_texture);
        if (glGetError() != GL_NO_ERROR) return false;
        g_unpack_texture = 0;
    }
    g_unpack_texture_allocated = false;
    g_unpack_source_uniform = -1;
    g_output_texture = 0;
    g_source_texture_uniform = -1;
    g_output_texture_allocated = false;
    g_native_fence_supported = false;
    g_direct_selected_mode = -1;
    g_direct_mosaic_mask = 0;
    g_gl_display = EGL_NO_DISPLAY;
    g_gl_context = EGL_NO_CONTEXT;
    g_gl_session = 0;
    return true;
}

bool prepareGlSessionLocked(uint32_t session) {
    EGLDisplay display = eglGetCurrentDisplay();
    EGLContext context = eglGetCurrentContext();
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return false;
    resolveGraphicsExtensions();

    if ((g_gl_context != EGL_NO_CONTEXT && g_gl_context != context)
            || (g_gl_display != EGL_NO_DISPLAY
                    && g_gl_display != display)) {
        // The old context owns every imported EGLImage. Losing those IDs would
        // let Java stop the producer while old imports are still alive.
        LOGE("Refusing FastCam rendering after EGL context ownership changed");
        return false;
    }
    g_gl_display = display;
    g_gl_context = context;
    if (g_gl_session != 0 && g_gl_session != session) {
        if (!finishGpuLocked("FastCam session retirement")
                || !destroyImportedBuffersLocked()) {
            return false;
        }
        g_output_texture = 0;
        g_output_texture_allocated = false;
        g_direct_selected_mode = -1;
        g_direct_mosaic_mask = 0;
    }
    g_gl_session = session;

    const char* egl_extensions = eglQueryString(display, EGL_EXTENSIONS);
    const char* gl_extensions =
            reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    bool dma_ready =
            g_graphics.create_image
            && g_graphics.destroy_image
            && g_graphics.target_texture
            && hasExtension(
                    egl_extensions, "EGL_EXT_image_dma_buf_import")
            && hasExtension(egl_extensions, "EGL_KHR_image_base")
            && hasExtension(
                    gl_extensions, "GL_OES_EGL_image_external");
    if (!dma_ready) return false;

    g_native_fence_supported =
            g_graphics.create_sync
            && g_graphics.destroy_sync
            && g_graphics.dup_native_fence
            && hasExtension(egl_extensions, "EGL_KHR_fence_sync")
            && hasExtension(
                    egl_extensions, "EGL_ANDROID_native_fence_sync");
    clearGlErrors();
    return ensureRendererLocked();
}

bool ensureOutputTextureLocked(GLuint texture_id) {
    if (!texture_id) return false;
    glBindTexture(GL_TEXTURE_2D, texture_id);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    bool newly_allocated =
            g_output_texture != texture_id || !g_output_texture_allocated;
    if (newly_allocated) {
        glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_RGBA,
                kOutputWidth,
                kOutputHeight,
                0,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                nullptr);
        g_output_texture = texture_id;
        g_output_texture_allocated = glGetError() == GL_NO_ERROR;
        if (!g_output_texture_allocated) return false;
        if (g_framebuffer) {
            glBindFramebuffer(GL_FRAMEBUFFER, g_framebuffer);
            glFramebufferTexture2D(
                    GL_FRAMEBUFFER,
                    GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D,
                    texture_id,
                    0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER)
                    != GL_FRAMEBUFFER_COMPLETE) {
                return false;
            }
            glViewport(0, 0, kOutputWidth, kOutputHeight);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// GPU unpack stage of the CPU-copy transport (persist.overdrive.
// dilink5_gpu_unpack). The client thread stages UYVY bytes exactly as in the
// NEON path; this stage uploads them as half-width RGBA8 texels and converts
// UYVY->RGBA in one fragment pass into the caller-owned output texture. No
// producer DMA-BUF is ever imported here — only app-owned staging memory
// touches GL, which keeps the buffer-lifetime story identical to the NEON
// path while removing every per-pixel CPU instruction and halving the
// texture upload bytes.
// ---------------------------------------------------------------------------

bool ensureUnpackRendererLocked() {
    if (g_unpack_program
            && g_unpack_vertex_buffer
            && g_framebuffer
            && g_unpack_source_uniform >= 0) {
        return true;
    }
    static const char* vertex_source =
            "attribute vec2 aPosition;\n"
            "attribute vec2 aTexCoord;\n"
            "varying vec2 vTexCoord;\n"
            "void main() {\n"
            "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            "  vTexCoord = aTexCoord;\n"
            "}\n";
    // One staging texel carries one UYVY pair (two output pixels); the
    // fragment's x parity picks Y0 or Y1. Coefficients are the NEON path's
    // fixed-point constants over 1024 (BT.601 full range), so both stages
    // produce matching colors.
    static const char* fragment_source =
            "precision highp float;\n"
            "uniform sampler2D uStaging;\n"
            "varying vec2 vTexCoord;\n"
            "void main() {\n"
            "  vec4 uyvy = texture2D(uStaging, vTexCoord);\n"
            "  float luma = mod(floor(gl_FragCoord.x), 2.0) < 0.5\n"
            "      ? uyvy.g : uyvy.a;\n"
            "  float u = uyvy.r - 0.5;\n"
            "  float v = uyvy.b - 0.5;\n"
            "  gl_FragColor = vec4(\n"
            "      luma + 1.40234375 * v,\n"
            "      luma - 0.34375 * u - 0.71386719 * v,\n"
            "      luma + 1.77246094 * u,\n"
            "      1.0);\n"
            "}\n";

    GLuint vertex = compileShader(GL_VERTEX_SHADER, vertex_source);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, fragment_source);
    if (!vertex || !fragment) {
        if (vertex) glDeleteShader(vertex);
        if (fragment) glDeleteShader(fragment);
        return false;
    }
    g_unpack_program = glCreateProgram();
    glAttachShader(g_unpack_program, vertex);
    glAttachShader(g_unpack_program, fragment);
    glBindAttribLocation(g_unpack_program, kPositionAttribute, "aPosition");
    glBindAttribLocation(g_unpack_program, kTexCoordAttribute, "aTexCoord");
    glLinkProgram(g_unpack_program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(g_unpack_program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512] = {};
        glGetProgramInfoLog(g_unpack_program, sizeof(log), nullptr, log);
        LOGE("UYVY unpack program link failed: %s", log);
        glDeleteProgram(g_unpack_program);
        g_unpack_program = 0;
        return false;
    }
    g_unpack_source_uniform =
            glGetUniformLocation(g_unpack_program, "uStaging");

    // Fullscreen quad; V flipped so output texture row 0 carries the image
    // bottom — byte-identical orientation to the NEON path's flip_vertical
    // RGBA writes that every consumer already samples.
    if (!g_unpack_vertex_buffer) {
        const GLfloat vertices[] = {
            -1.0f, -1.0f, 0.0f, 1.0f,
             1.0f, -1.0f, 1.0f, 1.0f,
            -1.0f,  1.0f, 0.0f, 0.0f,
             1.0f,  1.0f, 1.0f, 0.0f,
        };
        glGenBuffers(1, &g_unpack_vertex_buffer);
        glBindBuffer(GL_ARRAY_BUFFER, g_unpack_vertex_buffer);
        glBufferData(
                GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    }
    if (!g_framebuffer) {
        glGenFramebuffers(1, &g_framebuffer);
    }
    bool ready = g_unpack_source_uniform >= 0
            && g_unpack_vertex_buffer != 0
            && g_framebuffer != 0
            && glGetError() == GL_NO_ERROR;
    if (!ready) {
        if (g_unpack_vertex_buffer) {
            glDeleteBuffers(1, &g_unpack_vertex_buffer);
        }
        if (g_unpack_program) glDeleteProgram(g_unpack_program);
        g_unpack_program = 0;
        g_unpack_vertex_buffer = 0;
        g_unpack_source_uniform = -1;
    }
    return ready;
}

// Allocates/binds the UYVY staging texture. NEAREST filtering is load-bearing:
// linear filtering would blend chroma across neighboring pairs.
bool ensureUnpackTextureLocked() {
    if (!g_unpack_texture) {
        glGenTextures(1, &g_unpack_texture);
        g_unpack_texture_allocated = false;
    }
    if (!g_unpack_texture) return false;
    glBindTexture(GL_TEXTURE_2D, g_unpack_texture);
    if (g_unpack_texture_allocated) return true;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA,
            kUnpackTexelsPerRow,
            kUnpackTextureRows,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            nullptr);
    g_unpack_texture_allocated = glGetError() == GL_NO_ERROR;
    return g_unpack_texture_allocated;
}

// Uploads the staged UYVY bytes into the bound staging texture: four quadrant
// sub-rects in mosaic mode, one full-frame rect in single-camera mode. Half
// the bytes of the old RGBA upload, and no CPU conversion beforehand.
bool uploadUnpackStagingLocked() {
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (g_cpu_selected_mode == 4) {
        for (int slot = 0; slot < 4; slot++) {
            const CpuCameraFrame& staging = g_cpu_cameras[slot];
            if (!staging.valid || !staging.decimated) return false;
            int texel_x = (slot == 1 || slot == 3)
                    ? kUnpackTexelsPerRow / 2
                    : 0;
            int texel_y = (slot == 0 || slot == 1)
                    ? 0
                    : kUnpackTextureRows / 2;
            glTexSubImage2D(
                    GL_TEXTURE_2D,
                    0,
                    texel_x,
                    texel_y,
                    kUnpackTexelsPerRow / 2,
                    kUnpackTextureRows / 2,
                    GL_RGBA,
                    GL_UNSIGNED_BYTE,
                    staging.bytes.data());
        }
        return glGetError() == GL_NO_ERROR;
    }
    int selected = g_cpu_selected_mode;
    if (selected < 0 || selected >= FAST_CAM_MAX_CAMS) return false;
    const CpuCameraFrame& staging = g_cpu_cameras[selected];
    if (!staging.valid || staging.decimated) return false;
    glTexSubImage2D(
            GL_TEXTURE_2D,
            0,
            0,
            0,
            kUnpackTexelsPerRow,
            kUnpackTextureRows,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            staging.bytes.data());
    return glGetError() == GL_NO_ERROR;
}

// One publish through the GPU unpack stage. Full state save/restore and an
// error drain on every exit, so a failure can never leave a latched GL error
// or dirty state for the app's own rendering.
bool runGpuUnpackLocked(GLuint texture_id) {
    SavedGlState saved;
    SavedVertexAttrib saved_position;
    SavedVertexAttrib saved_tex_coord;
    saveGlState(&saved);
    saveVertexAttrib(kPositionAttribute, &saved_position);
    saveVertexAttrib(kTexCoordAttribute, &saved_tex_coord);
    clearGlErrors();
    bool ready = ensureUnpackRendererLocked()
            && ensureUnpackTextureLocked()
            && uploadUnpackStagingLocked()
            && ensureOutputTextureLocked(texture_id);
    if (ready) {
        glBindFramebuffer(GL_FRAMEBUFFER, g_framebuffer);
        glFramebufferTexture2D(
                GL_FRAMEBUFFER,
                GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D,
                texture_id,
                0);
        ready = glCheckFramebufferStatus(GL_FRAMEBUFFER)
                == GL_FRAMEBUFFER_COMPLETE;
    }
    if (ready) {
        glViewport(0, 0, kOutputWidth, kOutputHeight);
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glUseProgram(g_unpack_program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, g_unpack_texture);
        glUniform1i(g_unpack_source_uniform, 0);
        glBindBuffer(GL_ARRAY_BUFFER, g_unpack_vertex_buffer);
        glEnableVertexAttribArray(kPositionAttribute);
        glEnableVertexAttribArray(kTexCoordAttribute);
        glVertexAttribPointer(
                kPositionAttribute,
                2,
                GL_FLOAT,
                GL_FALSE,
                4 * sizeof(GLfloat),
                nullptr);
        glVertexAttribPointer(
                kTexCoordAttribute,
                2,
                GL_FLOAT,
                GL_FALSE,
                4 * sizeof(GLfloat),
                reinterpret_cast<const void*>(2 * sizeof(GLfloat)));
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(kPositionAttribute);
        glDisableVertexAttribArray(kTexCoordAttribute);
        ready = glGetError() == GL_NO_ERROR;
        if (ready) {
            glFlush();
            ready = glGetError() == GL_NO_ERROR;
        }
    }
    restoreVertexAttrib(kPositionAttribute, saved_position);
    restoreVertexAttrib(kTexCoordAttribute, saved_tex_coord);
    restoreGlState(saved);
    clearGlErrors();
    return ready;
}

bool importDmaBufferLocked(
        const FastCamFrame& frame,
        GLuint* output_texture) {
    if (frame.buffer_slot >= FAST_CAM_MAX_TOTAL_BUFS
            || frame.dma_buf_fd < 0) {
        return false;
    }
    ImportedBuffer& imported = g_imported_buffers[frame.buffer_slot];
    if (imported.image != EGL_NO_IMAGE_KHR
            && imported.session == g_gl_session
            && imported.dma_fd == frame.dma_buf_fd
            && imported.width == frame.width
            && imported.height == frame.height
            && imported.stride == frame.stride) {
        *output_texture = imported.texture;
        return true;
    }
    if (imported.image != EGL_NO_IMAGE_KHR || imported.texture) {
        if (!finishGpuLocked("FastCam slot retirement")
                || !destroyImportedBufferLocked(&imported)) {
            return false;
        }
    }

    EGLint attributes_with_hints[] = {
        EGL_WIDTH, static_cast<EGLint>(frame.width),
        EGL_HEIGHT, static_cast<EGLint>(frame.height),
        EGL_LINUX_DRM_FOURCC_EXT, kDrmFormatUyvy,
        EGL_DMA_BUF_PLANE0_FD_EXT, frame.dma_buf_fd,
        EGL_DMA_BUF_PLANE0_OFFSET_EXT, 0,
        EGL_DMA_BUF_PLANE0_PITCH_EXT, static_cast<EGLint>(frame.stride),
        EGL_YUV_COLOR_SPACE_HINT_EXT, EGL_ITU_REC601_EXT,
        EGL_SAMPLE_RANGE_HINT_EXT, EGL_YUV_FULL_RANGE_EXT,
        EGL_NONE,
    };
    EGLint minimal_attributes[] = {
        EGL_WIDTH, static_cast<EGLint>(frame.width),
        EGL_HEIGHT, static_cast<EGLint>(frame.height),
        EGL_LINUX_DRM_FOURCC_EXT, kDrmFormatUyvy,
        EGL_DMA_BUF_PLANE0_FD_EXT, frame.dma_buf_fd,
        EGL_DMA_BUF_PLANE0_OFFSET_EXT, 0,
        EGL_DMA_BUF_PLANE0_PITCH_EXT, static_cast<EGLint>(frame.stride),
        EGL_NONE,
    };
    imported.image = g_graphics.create_image(
            g_gl_display,
            EGL_NO_CONTEXT,
            EGL_LINUX_DMA_BUF_EXT,
            nullptr,
            attributes_with_hints);
    if (imported.image == EGL_NO_IMAGE_KHR) {
        imported.image = g_graphics.create_image(
                g_gl_display,
                EGL_NO_CONTEXT,
                EGL_LINUX_DMA_BUF_EXT,
                nullptr,
                minimal_attributes);
    }
    if (imported.image == EGL_NO_IMAGE_KHR) {
        LOGW("EGL DMA-BUF import failed: egl=0x%x", eglGetError());
        return false;
    }

    clearGlErrors();
    glGenTextures(1, &imported.texture);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, imported.texture);
    glTexParameteri(
            GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(
            GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(
            GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(
            GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g_graphics.target_texture(
            GL_TEXTURE_EXTERNAL_OES,
            reinterpret_cast<GLeglImageOES>(imported.image));
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        LOGW("DMA-BUF external texture bind failed: gl=0x%x", error);
        if (!destroyImportedBufferLocked(&imported)) {
            LOGE("Failed to retire rejected DMA-BUF import");
        }
        return false;
    }
    imported.session = g_gl_session;
    imported.dma_fd = frame.dma_buf_fd;
    imported.width = frame.width;
    imported.height = frame.height;
    imported.stride = frame.stride;
    *output_texture = imported.texture;
    return true;
}

bool drawDirectFrameLocked(
        const FastCamFrame& frame,
        int semantic_slot,
        int selected,
        GLuint output_texture) {
    if (eglGetCurrentDisplay() == EGL_NO_DISPLAY
            || eglGetCurrentContext() == EGL_NO_CONTEXT) {
        return false;
    }
    SavedGlState saved;
    SavedVertexAttrib saved_position;
    SavedVertexAttrib saved_tex_coord;
    saveGlState(&saved);
    saveVertexAttrib(kPositionAttribute, &saved_position);
    saveVertexAttrib(kTexCoordAttribute, &saved_tex_coord);
    if (!prepareGlSessionLocked(g_pending_frame.session)) {
        restoreVertexAttrib(kPositionAttribute, saved_position);
        restoreVertexAttrib(kTexCoordAttribute, saved_tex_coord);
        restoreGlState(saved);
        return false;
    }
    clearGlErrors();
    GLuint source_texture = 0;
    bool ready =
            importDmaBufferLocked(frame, &source_texture)
            && ensureOutputTextureLocked(output_texture);
    if (!ready) {
        restoreVertexAttrib(kPositionAttribute, saved_position);
        restoreVertexAttrib(kTexCoordAttribute, saved_tex_coord);
        restoreGlState(saved);
        return false;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, g_framebuffer);
    glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            output_texture,
            0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER)
            != GL_FRAMEBUFFER_COMPLETE) {
        restoreVertexAttrib(kPositionAttribute, saved_position);
        restoreVertexAttrib(kTexCoordAttribute, saved_tex_coord);
        restoreGlState(saved);
        return false;
    }
    if (g_direct_selected_mode != selected) {
        glViewport(0, 0, kOutputWidth, kOutputHeight);
        glDisable(GL_SCISSOR_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        g_direct_selected_mode = selected;
        g_direct_mosaic_mask = 0;
    }

    int viewport_x = 0;
    int viewport_y = 0;
    int viewport_width = kOutputWidth;
    int viewport_height = kOutputHeight;
    if (selected == 4) {
        viewport_width = kMosaicHalfWidth;
        viewport_height = kMosaicHalfHeight;
        viewport_x = (semantic_slot == 1 || semantic_slot == 3)
                ? kMosaicHalfWidth
                : 0;
        viewport_y = (semantic_slot == 0 || semantic_slot == 1)
                ? kMosaicHalfHeight
                : 0;
    }
    glViewport(
            viewport_x, viewport_y, viewport_width, viewport_height);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glUseProgram(g_program);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, source_texture);
    glUniform1i(g_source_texture_uniform, 0);
    glBindBuffer(GL_ARRAY_BUFFER, g_vertex_buffer);
    glEnableVertexAttribArray(kPositionAttribute);
    glEnableVertexAttribArray(kTexCoordAttribute);
    glVertexAttribPointer(
            kPositionAttribute,
            2,
            GL_FLOAT,
            GL_FALSE,
            4 * sizeof(GLfloat),
            nullptr);
    glVertexAttribPointer(
            kTexCoordAttribute,
            2,
            GL_FLOAT,
            GL_FALSE,
            4 * sizeof(GLfloat),
            reinterpret_cast<const void*>(2 * sizeof(GLfloat)));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (selected == 4 && semantic_slot >= 0 && semantic_slot < 4) {
        g_direct_mosaic_mask |=
                static_cast<uint8_t>(1u << semantic_slot);
    }
    glDisableVertexAttribArray(kPositionAttribute);
    glDisableVertexAttribArray(kTexCoordAttribute);
    GLenum error = glGetError();
    restoreVertexAttrib(kPositionAttribute, saved_position);
    restoreVertexAttrib(kTexCoordAttribute, saved_tex_coord);
    restoreGlState(saved);
    return error == GL_NO_ERROR;
}

bool createReleaseFenceLocked(int* output_fence_fd) {
    if (!output_fence_fd) return false;
    *output_fence_fd = -1;
    if (g_native_fence_supported) {
        const EGLint attributes[] = {EGL_NONE};
        EGLSyncKHR sync = g_graphics.create_sync(
                g_gl_display,
                EGL_SYNC_NATIVE_FENCE_ANDROID,
                attributes);
        if (sync != EGL_NO_SYNC_KHR) {
            clearGlErrors();
            glFlush();
            GLenum flush_error = glGetError();
            int fence_fd =
                    flush_error == GL_NO_ERROR
                            ? g_graphics.dup_native_fence(g_gl_display, sync)
                            : -1;
            bool destroyed =
                    g_graphics.destroy_sync(g_gl_display, sync) == EGL_TRUE;
            if (flush_error != GL_NO_ERROR) {
                LOGE("Native GPU fence flush failed: gl=0x%x", flush_error);
            }
            if (!destroyed) {
                if (fence_fd >= 0) close(fence_fd);
                LOGE("Native GPU fence destruction failed: egl=0x%x",
                        eglGetError());
                return false;
            }
            if (fence_fd >= 0) {
                *output_fence_fd = fence_fd;
                return true;
            }
        }
        LOGW("Native GPU fence creation failed; using glFinish fallback");
    }
    return finishGpuLocked("FastCam release fence fallback");
}

void convertUyvyToRgba(
        const uint8_t* __restrict__ uyvy,
        int width,
        int height,
        int source_stride,
        uint32_t* __restrict__ rgba,
        int output_stride,
        bool flip_vertical) {
    const int stride = output_stride > 0 ? output_stride : width;
    const int input_stride =
            source_stride >= width * 2 ? source_stride : width * 2;
    const int16x8_t chroma_center = vdupq_n_s16(128);
    const uint8x8_t opaque = vdup_n_u8(255);

    for (int y = 0; y < height; y++) {
        const uint8_t* source = uyvy + y * input_stride;
        const int output_y = flip_vertical ? height - 1 - y : y;
        uint32_t* destination = rgba + output_y * stride;
        int x = 0;
        for (; x <= width - 16; x += 16) {
            uint8x8x4_t pixels = vld4_u8(source);
            source += 32;
            int16x8_t u = vsubq_s16(
                    vreinterpretq_s16_u16(vmovl_u8(pixels.val[0])),
                    chroma_center);
            int16x8_t v = vsubq_s16(
                    vreinterpretq_s16_u16(vmovl_u8(pixels.val[2])),
                    chroma_center);
            // Doubling the centered chroma keeps the Q10 coefficients
            // representable in Q15. SQRDMULH gives exactly (c*k+512)>>10
            // for red and blue, without widening to 32-bit lanes.
            int16x8_t red_delta = vqrdmulhq_n_s16(
                    vshlq_n_s16(v, 1), 1436 * 16);
            int16x8_t blue_delta = vqrdmulhq_n_s16(
                    vshlq_n_s16(u, 1), 1815 * 16);
            // Green must round after summing both products.
            int32x4_t green_low = vmlal_n_s16(
                    vmull_n_s16(vget_low_s16(u), 352),
                    vget_low_s16(v), 731);
            int32x4_t green_high = vmlal_n_s16(
                    vmull_n_s16(vget_high_s16(u), 352),
                    vget_high_s16(v), 731);
            int16x8_t green_delta = vcombine_s16(
                    vrshrn_n_s32(green_low, 10),
                    vrshrn_n_s32(green_high, 10));
            int16x8_t y0 =
                    vreinterpretq_s16_u16(vmovl_u8(pixels.val[1]));
            int16x8_t y1 =
                    vreinterpretq_s16_u16(vmovl_u8(pixels.val[3]));
            uint8x8x2_t red = vzip_u8(
                    vqmovun_s16(vaddq_s16(y0, red_delta)),
                    vqmovun_s16(vaddq_s16(y1, red_delta)));
            uint8x8x2_t green = vzip_u8(
                    vqmovun_s16(vsubq_s16(y0, green_delta)),
                    vqmovun_s16(vsubq_s16(y1, green_delta)));
            uint8x8x2_t blue = vzip_u8(
                    vqmovun_s16(vaddq_s16(y0, blue_delta)),
                    vqmovun_s16(vaddq_s16(y1, blue_delta)));
            uint8x8x4_t lower = {
                red.val[0], green.val[0], blue.val[0], opaque
            };
            uint8x8x4_t upper = {
                red.val[1], green.val[1], blue.val[1], opaque
            };
            vst4_u8(
                    reinterpret_cast<uint8_t*>(destination + x), lower);
            vst4_u8(
                    reinterpret_cast<uint8_t*>(destination + x + 8), upper);
        }
        for (; x < width; x += 2) {
            int u = static_cast<int>(source[0]) - 128;
            int y0 = source[1];
            int v = static_cast<int>(source[2]) - 128;
            int y1 = source[3];
            source += 4;
            int red_delta = (1436 * v + 512) >> 10;
            int green_delta = (352 * u + 731 * v + 512) >> 10;
            int blue_delta = (1815 * u + 512) >> 10;
            int red0 = y0 + red_delta;
            int green0 = y0 - green_delta;
            int blue0 = y0 + blue_delta;
            int red1 = y1 + red_delta;
            int green1 = y1 - green_delta;
            int blue1 = y1 + blue_delta;
            red0 = red0 < 0 ? 0 : (red0 > 255 ? 255 : red0);
            green0 = green0 < 0 ? 0 : (green0 > 255 ? 255 : green0);
            blue0 = blue0 < 0 ? 0 : (blue0 > 255 ? 255 : blue0);
            red1 = red1 < 0 ? 0 : (red1 > 255 ? 255 : red1);
            green1 = green1 < 0 ? 0 : (green1 > 255 ? 255 : green1);
            blue1 = blue1 < 0 ? 0 : (blue1 > 255 ? 255 : blue1);
            destination[x] = static_cast<uint32_t>(
                    0xFF000000
                    | (static_cast<uint32_t>(blue0) << 16)
                    | (static_cast<uint32_t>(green0) << 8)
                    | static_cast<uint32_t>(red0));
            destination[x + 1] = static_cast<uint32_t>(
                    0xFF000000
                    | (static_cast<uint32_t>(blue1) << 16)
                    | (static_cast<uint32_t>(green1) << 8)
                    | static_cast<uint32_t>(red1));
        }
    }
}

void renderMosaicUyvyToRgba(
        const CameraFrame& front,
        const CameraFrame& right,
        const CameraFrame& rear,
        const CameraFrame& left,
        uint32_t* __restrict__ rgba) {
    // Each staging plane is already decimated and tightly cropped. Convert
    // directly to its quadrant, using the full output stride and the same
    // vertical flip as the single-camera path. No intermediate UYVY rows.
    uint32_t* top = rgba + static_cast<size_t>(kMosaicHalfHeight) * kOutputWidth;
    convertUyvyToRgba(
            front.pixels, kMosaicHalfWidth, kMosaicHalfHeight,
            static_cast<int>(front.stride), top, kOutputWidth, true);
    convertUyvyToRgba(
            right.pixels, kMosaicHalfWidth, kMosaicHalfHeight,
            static_cast<int>(right.stride), top + kMosaicHalfWidth, kOutputWidth, true);
    convertUyvyToRgba(
            rear.pixels, kMosaicHalfWidth, kMosaicHalfHeight,
            static_cast<int>(rear.stride), rgba, kOutputWidth, true);
    convertUyvyToRgba(
            left.pixels, kMosaicHalfWidth, kMosaicHalfHeight,
            static_cast<int>(left.stride), rgba + kMosaicHalfWidth, kOutputWidth, true);
}

bool syncDmaBufCpuRead(int fd, uint64_t flags) {
    struct dma_buf_sync sync = {};
    sync.flags = flags;
    int result;
    do {
        result = ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
    } while (result < 0 && errno == EINTR);
    return result == 0;
}

bool copyCpuCameraLocked(int slot, const FastCamFrame& frame, bool decimate) {
    if (slot < 0
            || slot >= FAST_CAM_MAX_CAMS
            || frame.dma_buf_fd < 0
            || frame.buffer_bytes
                    < static_cast<uint64_t>(frame.stride) * frame.height) {
        return false;
    }
    if (!frame.pixels) {
        LOGE("CPU fallback unavailable: DMA-BUF slot %u is not CPU-mappable",
                frame.buffer_slot);
        g_streaming.store(false);
        return false;
    }
    CpuCameraFrame& destination = g_cpu_cameras[slot];
    const int rows = decimate ? kStagingMosaicRows : kStagingSingleRows;
    const int row_bytes =
            decimate ? kStagingMosaicRowBytes : kStagingSingleRowBytes;
    destination.bytes.resize(static_cast<size_t>(rows) * row_bytes);
    if (!syncDmaBufCpuRead(
                frame.dma_buf_fd,
                DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ)) {
        LOGE("DMA_BUF_SYNC_START failed: fd=%d errno=%d",
                frame.dma_buf_fd, errno);
        g_streaming.store(false);
        return false;
    }
    if (decimate) {
        // Mosaic staging: copy exactly the subset the mosaic consumer reads
        // (mosaicSourceYForOutputRow rows, every other UYVY pair) instead of
        // the whole 1920x1300 sensor frame. ~1 MB written per frame instead
        // of ~5 MB, and the producer buffer is held for the shorter copy.
        constexpr int pairs_per_row = kStagingMosaicRowBytes / 4;
        for (int row = 0; row < rows; row++) {
            const uint32_t* source_pairs =
                    reinterpret_cast<const uint32_t*>(
                            frame.pixels
                                    + static_cast<size_t>(
                                            mosaicSourceYForOutputRow(row))
                                            * frame.stride);
            uint32_t* destination_pairs =
                    reinterpret_cast<uint32_t*>(
                            destination.bytes.data()
                                    + static_cast<size_t>(row) * row_bytes);
            for (int pair = 0; pair < pairs_per_row; pair += 4) {
                uint32x4x2_t pairs = vld2q_u32(
                        source_pairs
                                + mosaicSourcePairForOutputPair(pair));
                vst1q_u32(destination_pairs + pair, pairs.val[0]);
            }
        }
    } else {
        // Single-camera staging: center-cropped rows at full width, tightly
        // packed so the converter and the unpack upload read it directly.
        for (int row = 0; row < rows; row++) {
            memcpy(destination.bytes.data()
                            + static_cast<size_t>(row) * row_bytes,
                    frame.pixels
                            + static_cast<size_t>(kCropTop + row)
                                    * frame.stride,
                    row_bytes);
        }
    }
    if (!syncDmaBufCpuRead(
                frame.dma_buf_fd,
                DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ)) {
        LOGE("DMA_BUF_SYNC_END failed: fd=%d errno=%d",
                frame.dma_buf_fd, errno);
        g_streaming.store(false);
        destination.valid = false;
        return false;
    }
    destination.stride = static_cast<uint32_t>(row_bytes);
    destination.rows = static_cast<uint32_t>(rows);
    destination.decimated = decimate;
    destination.valid = true;
    return true;
}

// Copy phase of the CPU transport. Runs while the producer buffer is still
// held, so it does the minimum: stage the consumed pixel subset and decide
// whether a publish should follow. The convert phase runs after the buffer
// has been released back to the producer.
//
// Mosaic copy admission: a quadrant is staged only when something will read
// it — its staging is invalid (warmup / mode change), it is the anchor of a
// publishing tick, or the upcoming anchor tick is forecast to publish. At
// capture 30 / output 15 this skips roughly half of all copies.
bool prepareCpuCopyLocked(
        int slot,
        int selected,
        const FastCamFrame& frame,
        bool publish,
        bool next_anchor_publish) {
    if (selected != 4) {
        // Single-camera mode publishes straight from this frame's copy, so
        // frames outside a publish tick are never staged at all.
        if (!publish) return false;
        uint64_t copy_start = monotonicNowNs();
        if (!copyCpuCameraLocked(slot, frame, false)) return false;
        g_stat_copy_ns.fetch_add(monotonicNowNs() - copy_start);
        g_stat_copy_count.fetch_add(1);
        return true;
    }
    CpuCameraFrame& staging = g_cpu_cameras[slot];
    bool consumed = !staging.valid
            || !staging.decimated
            || (slot == 0 ? publish : next_anchor_publish);
    if (!consumed) {
        g_stat_copy_skipped.fetch_add(1);
        return false;
    }
    uint64_t copy_start = monotonicNowNs();
    if (!copyCpuCameraLocked(slot, frame, true)) return false;
    g_stat_copy_ns.fetch_add(monotonicNowNs() - copy_start);
    g_stat_copy_count.fetch_add(1);
    return publish;
}

// Convert/publish phase of the CPU transport. The producer buffer is already
// back with the producer; everything here reads app-owned staging only. In
// GPU unpack sessions the NEON convert is skipped entirely — the GL thread
// uploads the UYVY staging bytes and unpacks them in one shader pass.
bool finishCpuOutputLocked(int selected, uint64_t timestamp_ns) {
    if (selected == 4) {
        for (int camera = 0; camera < 4; camera++) {
            if (!g_cpu_cameras[camera].valid
                    || !g_cpu_cameras[camera].decimated) {
                return false;
            }
        }
    } else {
        if (selected < 0
                || selected >= FAST_CAM_MAX_CAMS
                || !g_cpu_cameras[selected].valid
                || g_cpu_cameras[selected].decimated) {
            return false;
        }
    }
    if (g_gpu_unpack_enabled.load()) {
        g_cpu_staging_publish_pending = true;
        g_latest_timestamp_ns.store(timestamp_ns);
        return true;
    }
    uint64_t convert_start = monotonicNowNs();
    int write_buffer = g_cpu_front_buffer == 0 ? 1 : 0;
    g_cpu_rgba[write_buffer].resize(
            static_cast<size_t>(kOutputWidth) * kOutputHeight);
    uint32_t* output = g_cpu_rgba[write_buffer].data();
    if (selected == 4) {
        CameraFrame cameras[4];
        for (int camera = 0; camera < 4; camera++) {
            cameras[camera] = CameraFrame{
                    g_cpu_cameras[camera].bytes.data(),
                    g_cpu_cameras[camera].stride};
        }
        renderMosaicUyvyToRgba(
                cameras[0], cameras[1], cameras[2], cameras[3], output);
    } else {
        // Staging is already center-cropped; convert it in place.
        const CpuCameraFrame& source = g_cpu_cameras[selected];
        convertUyvyToRgba(
                source.bytes.data(),
                kOutputWidth,
                kOutputHeight,
                static_cast<int>(source.stride),
                output,
                kOutputWidth,
                true);
    }
    g_cpu_front_buffer = write_buffer;
    g_cpu_has_new_frame = true;
    g_latest_timestamp_ns.store(timestamp_ns);
    g_stat_convert_ns.fetch_add(monotonicNowNs() - convert_start);
    g_stat_convert_count.fetch_add(1);
    return true;
}

// Throttled CPU-transport stage timing (client thread). Field logs use this
// to quantify copy/convert/upload cost before and after tuning.
void maybeLogCpuStageStats() {
    uint64_t now = monotonicNowNs();
    uint64_t last = g_stat_last_log_ns.load();
    if (last == 0
            || now - last < kCpuStatsLogIntervalNs
            || !g_stat_last_log_ns.compare_exchange_strong(last, now)) {
        return;
    }
    uint64_t copies = g_stat_copy_count.exchange(0);
    uint64_t copy_ns = g_stat_copy_ns.exchange(0);
    uint64_t skipped = g_stat_copy_skipped.exchange(0);
    uint64_t converts = g_stat_convert_count.exchange(0);
    uint64_t convert_ns = g_stat_convert_ns.exchange(0);
    uint64_t uploads = g_stat_upload_count.exchange(0);
    uint64_t upload_ns = g_stat_upload_ns.exchange(0);
    LOGI("FastCam CPU stage stats: copies=%llu avgUs=%llu skipped=%llu "
            "converts=%llu avgUs=%llu uploads=%llu avgUs=%llu gpuUnpack=%d",
            static_cast<unsigned long long>(copies),
            static_cast<unsigned long long>(
                    copies ? copy_ns / copies / 1000 : 0),
            static_cast<unsigned long long>(skipped),
            static_cast<unsigned long long>(converts),
            static_cast<unsigned long long>(
                    converts ? convert_ns / converts / 1000 : 0),
            static_cast<unsigned long long>(uploads),
            static_cast<unsigned long long>(
                    uploads ? upload_ns / uploads / 1000 : 0),
            g_gpu_unpack_enabled.load() ? 1 : 0);
}

bool uploadCpuFrameLocked(GLuint texture_id) {
    const bool gpu_unpack = g_gpu_unpack_enabled.load();
    if (gpu_unpack) {
        if (!g_cpu_staging_publish_pending) return false;
    } else if (!g_cpu_has_new_frame
            || g_cpu_front_buffer < 0
            || g_cpu_rgba[g_cpu_front_buffer].empty()) {
        return false;
    }
    EGLDisplay display = eglGetCurrentDisplay();
    EGLContext context = eglGetCurrentContext();
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return false;
    if ((g_gl_context != EGL_NO_CONTEXT && g_gl_context != context)
            || (g_gl_display != EGL_NO_DISPLAY
                    && g_gl_display != display)) {
        LOGE("Refusing FastCam upload after EGL context ownership changed");
        g_streaming.store(false);
        return false;
    }
    g_gl_display = display;
    g_gl_context = context;
    uint32_t session = g_session_epoch.load();
    if (g_gl_session != 0 && g_gl_session != session) {
        if (!finishGpuLocked("FastCam CPU session retirement")
                || !destroyImportedBuffersLocked()) {
            g_streaming.store(false);
            return false;
        }
        g_output_texture = 0;
        g_output_texture_allocated = false;
        g_direct_selected_mode = -1;
        g_direct_mosaic_mask = 0;
    }
    g_gl_session = session;

    if (gpu_unpack) {
        uint64_t upload_start = monotonicNowNs();
        if (runGpuUnpackLocked(texture_id)) {
            g_cpu_staging_publish_pending = false;
            g_stat_upload_ns.fetch_add(monotonicNowNs() - upload_start);
            g_stat_upload_count.fetch_add(1);
            return true;
        }
        // Fail once, fall back for the rest of the session: the NEON convert
        // path resumes from the next client-thread frame. This publish is
        // dropped — the same semantics as a failed legacy upload.
        g_gpu_unpack_enabled.store(false);
        g_cpu_staging_publish_pending = false;
        LOGE("FastCam GPU unpack failed; reverting to NEON convert path");
        return false;
    }

    SavedCpuUploadState saved;
    if (!saveCpuUploadState(&saved)) return false;
    bool ready = ensureOutputTextureLocked(texture_id);
    if (ready) {
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexSubImage2D(
                GL_TEXTURE_2D,
                0,
                0,
                0,
                kOutputWidth,
                kOutputHeight,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                g_cpu_rgba[g_cpu_front_buffer].data());
        ready = glGetError() == GL_NO_ERROR;
        if (ready) {
            glFlush();
            ready = glGetError() == GL_NO_ERROR;
        }
    }
    ready = restoreCpuUploadState(saved) && ready;
    if (ready) g_cpu_has_new_frame = false;
    return ready;
}

bool releaseClientFrame(
        FastCamClient* client,
        const FastCamFrame& frame,
        int fence_fd) {
    bool released = client && client->releaseFrame(&frame, fence_fd);
    if (fence_fd >= 0) close(fence_fd);
    if (!released) {
        LOGE("FastCam frame release failed: cam=%u buffer=%u seq=%u",
                frame.cam_id, frame.buffer_index, frame.sequence_no);
        g_streaming.store(false);
    }
    return released;
}

void clearPendingFrameLocked() {
    g_pending_frame = PendingFrame();
    g_frame_consumed.notify_all();
}

void releasePendingFrameLocked(int fence_fd) {
    if (!g_pending_frame.valid) {
        if (fence_fd >= 0) close(fence_fd);
        return;
    }
    releaseClientFrame(
            g_pending_frame.client,
            g_pending_frame.frame,
            fence_fd);
    clearPendingFrameLocked();
}

void notifyFrameAvailable(JNIEnv* environment, uint64_t timestamp_ns) {
    if (!environment || !g_backend_class || !g_on_frame_available) return;
    environment->CallStaticVoidMethod(
            g_backend_class,
            g_on_frame_available,
            static_cast<jlong>(timestamp_ns));
    if (environment->ExceptionCheck()) {
        environment->ExceptionClear();
        LOGW("DiLink 5 frame callback raised an exception");
    }
}

void markStreamStopped() {
    {
        std::lock_guard<std::mutex> lock(g_stream_state_mutex);
        g_stream_thread_running = false;
    }
    g_stream_stopped.notify_all();
}

void* streamClientLoop(void*) {
    LOGI("FastCam client thread started");
    FastCamClient client;
    int pacing_mode = -1;
    int pacing_fps = 0;
    int pacing_capture_fps = 0;
    int pacing_phase = 0;
    uint32_t published_frames = 0;
    JNIEnv* environment = nullptr;
    bool attached_to_vm = false;
    if (g_java_vm
            && g_java_vm->GetEnv(
                    reinterpret_cast<void**>(&environment),
                    JNI_VERSION_1_6) != JNI_OK) {
        if (g_java_vm->AttachCurrentThread(&environment, nullptr) == JNI_OK) {
            attached_to_vm = true;
        } else {
            environment = nullptr;
        }
    }

    const int expected_server_pid = g_expected_server_pid.load();
    while (g_streaming.load()
            && !client.connect(
                    g_socket_path.c_str(), expected_server_pid)) {
        usleep(300000);
    }
    if (!g_streaming.load()) {
        client.disconnect();
        if (attached_to_vm) g_java_vm->DetachCurrentThread();
        markStreamStopped();
        return nullptr;
    }
    if (!client.supportsReleaseFence()) {
        LOGE("Release/fence protocol unavailable; refusing unsafe buffer reads");
        client.disconnect();
        g_streaming.store(false);
        if (attached_to_vm) g_java_vm->DetachCurrentThread();
        markStreamStopped();
        return nullptr;
    }
    LOGI("Connected to owned fast_cam_capture DMA socket (releaseFence=%d)",
            client.supportsReleaseFence());

    while (g_streaming.load()) {
        FastCamFrame frame = {};
        if (!client.waitForFrame(&frame, 100)) {
            if (!client.isConnected()) break;
            continue;
        }
        bool valid =
                frame.width == kSensorWidth
                && frame.height == kSensorHeight
                && frame.stride >= kSensorWidth * 2
                && frame.stride <= kSensorWidth * 4
                && frame.buffer_bytes
                        >= static_cast<uint64_t>(frame.stride) * frame.height
                && frame.dma_buf_fd >= 0;
        int slot = valid ? semanticSlotFor(frame.cam_id) : -1;
        if (!valid || slot < 0 || slot >= FAST_CAM_MAX_CAMS) {
            LOGW("Rejected invalid fast camera frame cam=%u", frame.cam_id);
            if (!releaseClientFrame(&client, frame, -1)) break;
            continue;
        }

        int selected = g_active_camera.load();
        bool cycle_anchor = selected == 4 ? slot == 0 : selected == slot;
        bool publish = false;
        if (cycle_anchor) {
            int capture_fps = g_capture_fps.load();
            capture_fps = capture_fps < 1
                    ? 1
                    : (capture_fps > kDefaultCaptureFps
                            ? kDefaultCaptureFps
                            : capture_fps);
            int output_fps = g_output_fps.load();
            output_fps = output_fps < capture_fps
                    ? output_fps
                    : capture_fps;
            if (selected != pacing_mode
                    || output_fps != pacing_fps
                    || capture_fps != pacing_capture_fps) {
                pacing_mode = selected;
                pacing_fps = output_fps;
                pacing_capture_fps = capture_fps;
                pacing_phase = capture_fps - output_fps;
            }
            publish = advancePacingPhase(
                    capture_fps, output_fps, pacing_phase);
        }

        IngestMode mode = g_ingest_mode.load();
        if (shouldUseCpuCopy(mode)) {
            if ((selected == 4 && slot > 3)
                    || (selected != 4 && selected != slot)) {
                if (!releaseClientFrame(&client, frame, -1)) break;
                continue;
            }
            bool convert_pending = false;
            {
                std::lock_guard<std::mutex> lock(g_frame_mutex);
                if (g_cpu_selected_mode != selected) {
                    for (CpuCameraFrame& camera : g_cpu_cameras) {
                        camera.valid = false;
                    }
                    g_cpu_has_new_frame = false;
                    g_cpu_staging_publish_pending = false;
                    g_cpu_selected_mode = selected;
                }
                convert_pending = prepareCpuCopyLocked(
                        slot,
                        selected,
                        frame,
                        publish,
                        peekNextPacingPublish(
                                pacing_capture_fps,
                                pacing_fps,
                                pacing_phase));
            }
            // The producer buffer goes back BEFORE any conversion work: the
            // convert only reads app-owned staging, and holding the buffer
            // across a full mosaic render starved the producer pool on
            // publish ticks.
            if (!releaseClientFrame(&client, frame, -1)) break;
            bool output_ready = false;
            if (convert_pending) {
                std::lock_guard<std::mutex> lock(g_frame_mutex);
                output_ready = finishCpuOutputLocked(
                        selected,
                        frame.timestamp_ns > 0
                                ? frame.timestamp_ns
                                : monotonicNowNs());
            }
            maybeLogCpuStageStats();
            if (output_ready) {
                notifyFrameAvailable(
                        environment, g_latest_timestamp_ns.load());
                published_frames++;
            }
            continue;
        }

        bool needs_gl =
                selected == 4
                ? slot >= 0 && slot <= 3
                : selected == slot && publish;
        if (!needs_gl) {
            if (!releaseClientFrame(&client, frame, -1)) break;
            continue;
        }

        {
            std::unique_lock<std::mutex> lock(g_frame_mutex);
            if (g_pending_frame.valid) {
                LOGE("FastCam ownership invariant violated");
                releaseClientFrame(&client, frame, -1);
                break;
            }
            g_pending_frame.frame = frame;
            g_pending_frame.client = &client;
            g_pending_frame.session = g_session_epoch.load();
            g_pending_frame.selected = selected;
            g_pending_frame.publish = publish;
            g_pending_frame.valid = true;
        }
        notifyFrameAvailable(
                environment,
                frame.timestamp_ns > 0
                        ? frame.timestamp_ns
                        : monotonicNowNs());
        {
            std::unique_lock<std::mutex> lock(g_frame_mutex);
            g_frame_consumed.wait(lock, [] {
                return !g_pending_frame.valid || !g_streaming.load();
            });
            if (!g_streaming.load() && g_pending_frame.valid) {
                releasePendingFrameLocked(-1);
            }
        }
        if (publish) {
            published_frames++;
            if (published_frames == 1) {
                LOGI("First fast camera output frame ready: %dx%d mode=%d",
                        kOutputWidth, kOutputHeight, selected);
            } else if (published_frames % 300 == 0) {
                LOGI("Published %u fast camera frames", published_frames);
            }
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_frame_mutex);
        if (g_pending_frame.valid
                && g_pending_frame.client == &client) {
            releasePendingFrameLocked(-1);
        }
    }
    client.disconnect();
    g_streaming.store(false);
    if (attached_to_vm) g_java_vm->DetachCurrentThread();
    LOGI("FastCam client thread stopped");
    markStreamStopped();
    return nullptr;
}

bool ensureStreamThread() {
    std::lock_guard<std::mutex> lock(g_stream_state_mutex);
    if (g_stream_thread != 0) return g_stream_thread_running;
    pthread_t thread;
    g_stream_thread_running = true;
    int result = pthread_create(
            &thread, nullptr, streamClientLoop, nullptr);
    if (result != 0) {
        LOGE("Failed to create FastCam stream thread: %s",
                strerror(result));
        g_stream_thread_running = false;
        g_streaming.store(false);
        return false;
    }
    g_stream_thread = thread;
    return true;
}

bool isValidSocketPath(const std::string& path) {
    if (path.size() < 2 || path.size() >= 100 || path[0] != '@') {
        return false;
    }
    for (size_t i = 1; i < path.size(); i++) {
        char value = path[i];
        if (!((value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'z')
                || value == '_'
                || value == '-')) {
            return false;
        }
    }
    return true;
}

bool stopStream() {
    g_streaming.store(false);
    {
        std::lock_guard<std::mutex> lock(g_frame_mutex);
        if (g_pending_frame.valid) releasePendingFrameLocked(-1);
    }
    g_frame_consumed.notify_all();

    pthread_t thread = 0;
    {
        std::unique_lock<std::mutex> lock(g_stream_state_mutex);
        thread = g_stream_thread;
        if (thread != 0 && !g_stream_stopped.wait_for(
                lock,
                std::chrono::milliseconds(kStreamJoinTimeoutMs),
                [] { return !g_stream_thread_running; })) {
            LOGE("FastCam stream thread did not stop within %dms",
                    kStreamJoinTimeoutMs);
            return false;
        }
    }
    if (thread != 0) {
        int joined = pthread_join(thread, nullptr);
        if (joined != 0) {
            LOGE("FastCam stream thread join failed: %s",
                    strerror(joined));
            return false;
        }
        std::lock_guard<std::mutex> lock(g_stream_state_mutex);
        if (g_stream_thread == thread) g_stream_thread = 0;
    }
    return true;
}

void resetCpuStateLocked(bool free_memory) {
    for (CpuCameraFrame& camera : g_cpu_cameras) {
        camera.valid = false;
        camera.stride = 0;
        camera.rows = 0;
        camera.decimated = false;
        camera.bytes.clear();
        if (free_memory) std::vector<uint8_t>().swap(camera.bytes);
    }
    for (std::vector<uint32_t>& output : g_cpu_rgba) {
        output.clear();
        if (free_memory) std::vector<uint32_t>().swap(output);
    }
    g_cpu_front_buffer = -1;
    g_cpu_has_new_frame = false;
    g_cpu_staging_publish_pending = false;
    g_cpu_selected_mode = -1;
    g_latest_timestamp_ns.store(0);
}

// Session-scoped read of the GPU unpack kill switch. Unset defaults to ON;
// "0"/"false"/"off" pin the session to the NEON convert path — one setprop
// plus a camera reopen reverts a field unit without an OTA.
bool readGpuUnpackProperty() {
    char value[PROP_VALUE_MAX] = {};
    int length = __system_property_get(kGpuUnpackProperty, value);
    if (length <= 0) return true;
    return !(strcmp(value, "0") == 0
            || strcasecmp(value, "false") == 0
            || strcasecmp(value, "off") == 0);
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* java_vm, void*) {
    JNIEnv* environment = nullptr;
    if (!java_vm
            || java_vm->GetEnv(
                    reinterpret_cast<void**>(&environment),
                    JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass local_class = environment->FindClass(
            "com/overdrive/app/camera/dilink5/DiLink5QCarCamBackend");
    if (!local_class) return JNI_ERR;
    g_backend_class = static_cast<jclass>(
            environment->NewGlobalRef(local_class));
    environment->DeleteLocalRef(local_class);
    if (!g_backend_class) return JNI_ERR;
    g_on_frame_available = environment->GetStaticMethodID(
            g_backend_class, "onNativeFrameAvailable", "(J)V");
    if (!g_on_frame_available) {
        environment->DeleteGlobalRef(g_backend_class);
        g_backend_class = nullptr;
        return JNI_ERR;
    }
    g_java_vm = java_vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* java_vm, void*) {
    JNIEnv* environment = nullptr;
    if (java_vm
            && java_vm->GetEnv(
                    reinterpret_cast<void**>(&environment),
                    JNI_VERSION_1_6) == JNI_OK
            && g_backend_class) {
        environment->DeleteGlobalRef(g_backend_class);
    }
    g_backend_class = nullptr;
    g_on_frame_available = nullptr;
    g_java_vm = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeIsSupported(
        JNIEnv*, jclass) {
    return access(kAisClientPath, F_OK) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeHasActiveSession(
        JNIEnv*, jclass) {
    return g_active_session.load() != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeInit(
        JNIEnv* environment,
        jobject,
        jint input_id,
        jstring socket_path,
        jlong server_pid,
        jint capture_fps) {
    if (!socket_path
            || server_pid <= 0
            || server_pid > INT32_MAX
            || capture_fps < 1
            || capture_fps > kDefaultCaptureFps) {
        return 0;
    }
    const char* raw_path =
            environment->GetStringUTFChars(socket_path, nullptr);
    if (!raw_path) return 0;
    std::string resolved_path(raw_path);
    environment->ReleaseStringUTFChars(socket_path, raw_path);
    if (!isValidSocketPath(resolved_path)) {
        LOGE("Rejected invalid fast camera socket path");
        return 0;
    }
    uint32_t session = g_session_epoch.fetch_add(1) + 1;
    uint32_t expected_session = 0;
    if (!g_active_session.compare_exchange_strong(
                expected_session, session)) {
        LOGE("Refusing a second active FastCam native session");
        return 0;
    }
    {
        std::lock_guard<std::mutex> lock(g_frame_mutex);
        resetCpuStateLocked(false);
        g_pending_frame = PendingFrame();
    }
    g_socket_path = resolved_path;
    g_expected_server_pid.store(static_cast<int>(server_pid));
    g_capture_fps.store(capture_fps);
    IngestMode configured_mode = configuredInitialIngestMode();
    g_ingest_mode.store(configured_mode);
    LOGI("Fast camera ingest policy: %s (direct DMA code flag=%d)",
            configuredIngestPolicyName(),
            kDirectDmaEnabled ? 1 : 0);
    bool gpu_unpack = readGpuUnpackProperty();
    g_gpu_unpack_enabled.store(gpu_unpack);
    g_stat_last_log_ns.store(monotonicNowNs());
    LOGI("Fast camera CPU transport unpack stage: %s (%s)",
            gpu_unpack ? "gpu" : "neon",
            kGpuUnpackProperty);
    LOGI("Fast camera bridge initialized for input %d, server pid %lld, session %u",
            input_id,
            static_cast<long long>(server_pid),
            session);
    return session;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStart(
        JNIEnv*, jobject, jlong handle) {
    if (handle <= 0
            || static_cast<uint32_t>(handle) != g_active_session.load()) {
        return JNI_FALSE;
    }
    g_streaming.store(true);
    return ensureStreamThread() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeBindLatestFrame(
        JNIEnv*, jclass, jint texture_id) {
    std::lock_guard<std::mutex> lock(g_frame_mutex);
    IngestMode mode = g_ingest_mode.load();
    if (shouldUseCpuCopy(mode)) {
        return texture_id > 0
                && uploadCpuFrameLocked(static_cast<GLuint>(texture_id))
                ? texture_id
                : 0;
    }
    if (!g_pending_frame.valid) return 0;
    if (texture_id <= 0) {
        releasePendingFrameLocked(-1);
        return 0;
    }

    int slot = semanticSlotFor(g_pending_frame.frame.cam_id);
    bool drawn = slot >= 0
            && drawDirectFrameLocked(
                    g_pending_frame.frame,
                    slot,
                    g_pending_frame.selected,
                    static_cast<GLuint>(texture_id));
    if (!drawn) {
        EGLDisplay current_display = eglGetCurrentDisplay();
        EGLContext current_context = eglGetCurrentContext();
        bool context_owned =
                current_display != EGL_NO_DISPLAY
                && current_context != EGL_NO_CONTEXT
                && (g_gl_display == EGL_NO_DISPLAY
                        || g_gl_display == current_display)
                && (g_gl_context == EGL_NO_CONTEXT
                        || g_gl_context == current_context);
        if (!context_owned) {
            g_streaming.store(false);
            LOGE("FastCam GL ownership changed; stopping for safe process restart");
        } else {
            if (finishGpuLocked("FastCam direct-path retirement")
                    && destroyImportedBuffersLocked()) {
                g_ingest_mode.store(IngestMode::kCpuFallback);
                LOGW("Direct DMA compositor unavailable; switched to CPU upload fallback");
                releasePendingFrameLocked(-1);
                return 0;
            }
            g_streaming.store(false);
            LOGE("FastCam direct-path retirement failed; abandoning producer ownership");
        }
        clearPendingFrameLocked();
        return 0;
    }
    g_ingest_mode.store(IngestMode::kDirectDma);

    bool publish = g_pending_frame.publish
            && (g_pending_frame.selected != 4
                    || g_direct_mosaic_mask == 0x0f);
    int fence_fd = -1;
    if (!createReleaseFenceLocked(&fence_fd)) {
        g_streaming.store(false);
        LOGE("FastCam release fence failed; abandoning producer ownership");
        clearPendingFrameLocked();
        return 0;
    }
    releasePendingFrameLocked(fence_fd);
    if (!g_streaming.load()) return 0;
    if (!publish) return 0;
    return texture_id;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeReleaseGlResources(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_frame_mutex);
    EGLDisplay display = eglGetCurrentDisplay();
    EGLContext context = eglGetCurrentContext();
    if (g_gl_context == EGL_NO_CONTEXT) return JNI_TRUE;
    if (display != g_gl_display || context != g_gl_context) {
        LOGE("Refusing FastCam GL cleanup without its owning EGL context");
        return JNI_FALSE;
    }
    if (!destroyGlResourcesLocked()) {
        g_streaming.store(false);
        if (g_pending_frame.valid) clearPendingFrameLocked();
        LOGE("FastCam GL cleanup failed; abandoning producer ownership");
        return JNI_FALSE;
    }
    if (g_pending_frame.valid) releasePendingFrameLocked(-1);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeStop(
        JNIEnv*, jobject, jlong handle) {
    uint32_t active_session = g_active_session.load();
    if (active_session == 0
            || (handle != 0
                    && static_cast<uint32_t>(handle) != active_session)) {
        return JNI_FALSE;
    }
    return stopStream() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeSetActiveCamera(
        JNIEnv*, jclass, jint camera_index) {
    g_active_camera.store(camera_index);
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeSetOutputFps(
        JNIEnv*, jclass, jint fps) {
    int clamped = fps < 1
            ? 1
            : (fps > kDefaultCaptureFps ? kDefaultCaptureFps : fps);
    int previous = g_output_fps.exchange(clamped);
    if (previous != clamped) {
        LOGI("Fast camera requested output FPS: %d", clamped);
    }
}

JNIEXPORT void JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeSetCameraMapping(
        JNIEnv*,
        jclass,
        jint front,
        jint right,
        jint rear,
        jint left,
        jint dashcam) {
    bool duplicate = front == right || front == rear || front == left
            || right == rear || right == left || rear == left
            || (dashcam >= 0 && (dashcam == front || dashcam == right
                    || dashcam == rear || dashcam == left));
    if (front < 0 || right < 0 || rear < 0 || left < 0
            || dashcam < -1 || duplicate) {
        LOGE("Rejected invalid hardware camera mapping");
        return;
    }
    g_camera_mapping.front.store(front);
    g_camera_mapping.right.store(right);
    g_camera_mapping.rear.store(rear);
    g_camera_mapping.left.store(left);
    g_camera_mapping.dashcam.store(dashcam);
    LOGI("Hardware camera mapping: front=%d right=%d rear=%d left=%d dashcam=%d",
            front, right, rear, left, dashcam);
}

JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_camera_dilink5_DiLink5QCarCamBackend_nativeRelease(
        JNIEnv*, jobject, jlong handle) {
    uint32_t active_session = g_active_session.load();
    if (active_session == 0
            || (handle != 0
                    && static_cast<uint32_t>(handle) != active_session)) {
        return JNI_FALSE;
    }
    bool stopped = stopStream();
    if (!stopped) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_frame_mutex);
    resetCpuStateLocked(true);
    g_expected_server_pid.store(-1);
    g_socket_path.clear();
    g_active_session.compare_exchange_strong(active_session, 0);
    return JNI_TRUE;
}

}  // extern "C"
