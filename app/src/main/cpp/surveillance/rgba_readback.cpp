#include <jni.h>
#include <cstdint>
#include <climits>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_com_overdrive_app_surveillance_RgbaReadback_nativePack(
        JNIEnv* env, jclass, jobject rgba, jbyteArray rgb, jint width, jint height) {
    const int64_t pixels = static_cast<int64_t>(width) * height;
    if (!rgba || !rgb || width <= 0 || height <= 0 || pixels > INT_MAX / 4) {
        return JNI_FALSE;
    }
    const auto* source = static_cast<const uint8_t*>(env->GetDirectBufferAddress(rgba));
    if (!source || env->GetDirectBufferCapacity(rgba) < pixels * 4
            || env->GetArrayLength(rgb) != pixels * 3) {
        return JNI_FALSE;
    }
    // Do not hold a JNI critical array region while reading driver-mapped memory.
    // ART may pin or copy the destination; release it before the caller unmaps.
    jbyte* output = env->GetByteArrayElements(rgb, nullptr);
    if (!output) return JNI_FALSE;
    auto* dst = reinterpret_cast<uint8_t*>(output);
    for (int y = height - 1; y >= 0; --y) {
        const uint8_t* src = source + y * width * 4;
        int x = 0;
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        for (; x <= width - 16; x += 16, src += 64, dst += 48) {
            const uint8x16x4_t rgba16 = vld4q_u8(src);
            uint8x16x3_t rgb16;
            rgb16.val[0] = rgba16.val[0];
            rgb16.val[1] = rgba16.val[1];
            rgb16.val[2] = rgba16.val[2];
            vst3q_u8(dst, rgb16);
        }
#endif
        for (; x < width; ++x, src += 4, dst += 3) {
            dst[0] = src[0];
            dst[1] = src[1];
            dst[2] = src[2];
        }
    }
    env->ReleaseByteArrayElements(rgb, output, 0);
    return JNI_TRUE;
}
