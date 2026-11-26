#include <jni.h>
#include <cstdint>
#include <climits>

#if defined(__aarch64__)
#include <arm_neon.h>

namespace {

// A rounded float reciprocal alone is not bit-equivalent to division by 255.
// Correct the approximation with a fused residual and fused multiply-add.
// Java validates all 256 channel values before allowing this path to publish.
inline float32x4_t normalize4(uint32x4_t bytes) {
    const float32x4_t x = vcvtq_f32_u32(bytes);
    const float32x4_t approx = vmulq_n_f32(x, 1.0f / 255.0f);
    const float32x4_t residual = vfmsq_n_f32(x, approx, 255.0f);
    return vfmaq_n_f32(approx, residual, 1.0f / 255.0f);
}

void normalize(const uint8_t* source, float* destination, int count) {
    int i = 0;
    for (; i <= count - 16; i += 16) {
        const uint8x16_t rgb = vld1q_u8(source + i);
        const uint16x8_t lo = vmovl_u8(vget_low_u8(rgb));
        const uint16x8_t hi = vmovl_u8(vget_high_u8(rgb));
        vst1q_f32(destination + i, normalize4(vmovl_u16(vget_low_u16(lo))));
        vst1q_f32(destination + i + 4, normalize4(vmovl_u16(vget_high_u16(lo))));
        vst1q_f32(destination + i + 8, normalize4(vmovl_u16(vget_low_u16(hi))));
        vst1q_f32(destination + i + 12, normalize4(vmovl_u16(vget_high_u16(hi))));
    }
    for (; i < count; ++i) destination[i] = source[i] / 255.0f;
}

float* outputAddress(JNIEnv* env, jobject destination, jint count) {
    if (!destination || count <= 0 || count > INT_MAX / 4
            || env->GetDirectBufferCapacity(destination) < static_cast<int64_t>(count) * 4) {
        return nullptr;
    }
    return static_cast<float*>(env->GetDirectBufferAddress(destination));
}

} // namespace
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_ai_NormalizedRgbInput_nativeNormalizeArray(
        JNIEnv* env, jclass, jbyteArray source, jint offset, jobject destination, jint count) {
#if defined(__aarch64__)
    float* output = outputAddress(env, destination, count);
    if (!source || !output || offset < 0 || offset > env->GetArrayLength(source)
            || count > env->GetArrayLength(source) - offset) return JNI_FALSE;
    // ART may pin or copy the input. Never copy unchanged source bytes back.
    jbyte* bytes = env->GetByteArrayElements(source, nullptr);
    if (!bytes) return JNI_FALSE;
    normalize(reinterpret_cast<const uint8_t*>(bytes) + offset, output, count);
    env->ReleaseByteArrayElements(source, bytes, JNI_ABORT);
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_ai_NormalizedRgbInput_nativeNormalizeDirect(
        JNIEnv* env, jclass, jobject source, jobject destination, jint count) {
#if defined(__aarch64__)
    float* output = outputAddress(env, destination, count);
    if (!source || !output || env->GetDirectBufferCapacity(source) < count) return JNI_FALSE;
    const auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    if (!bytes) return JNI_FALSE;
    normalize(bytes, output, count);
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
