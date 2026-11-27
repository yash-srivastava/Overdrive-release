package com.overdrive.app.surveillance;

import java.nio.ByteBuffer;

/**
 * JNI bridge to the CPU recording engine (cpp/camera/CpuMosaicCompositor.cpp,
 * linked into libsurveillance). Every buffer argument must be direct.
 */
final class CpuMosaicCompositor {

    private static volatile boolean available;

    private CpuMosaicCompositor() {}

    /** True once libsurveillance in this process carries the compositor. */
    static boolean isAvailable() {
        if (available) return true;
        try {
            available = nativeVersion() >= 1;
        } catch (Throwable t) {
            // Library not loaded yet, or an older build: retried on the next call.
            return false;
        }
        return available;
    }

    static native int nativeVersion();

    /** Copies the four strip tiles into the 2x2 grid; elapsed ns, or negative on reject. */
    static native long nativeCompose(
            ByteBuffer srcY, ByteBuffer srcU, ByteBuffer srcV,
            int srcRowY, int srcRowUV, int srcPixUV, int srcW, int srcH,
            ByteBuffer dstY, ByteBuffer dstU, ByteBuffer dstV,
            int dstRowY, int dstRowUV, int dstPixUV, int dstW, int dstH,
            int xTopLeft, int xTopRight, int xBottomLeft, int xBottomRight,
            int camW, int camH);

    /**
     * GpuMosaicRecorder's shader on the CPU: 2x2 grid or dashcam layout (0/1),
     * lens-correction dewarp, windshield top band; GL_LINEAR + CLAMP_TO_EDGE
     * sampling. Windshield buffers are read only when {@code windshield} is set.
     * Elapsed ns, or negative on reject.
     */
    static native long nativeRemap(
            ByteBuffer srcY, ByteBuffer srcU, ByteBuffer srcV,
            int srcRowY, int srcRowUV, int srcPixUV, int srcW, int srcH,
            ByteBuffer wsY, ByteBuffer wsU, ByteBuffer wsV,
            int wsRowY, int wsRowUV, int wsPixUV, int wsW, int wsH,
            ByteBuffer dstY, ByteBuffer dstU, ByteBuffer dstV,
            int dstRowY, int dstRowUV, int dstPixUV, int dstW, int dstH,
            float offFront, float offRight, float offRear, float offLeft,
            float k1, float k2, float aspect, int layout, boolean windshield,
            float cropY, float split);

    /** Size of the overlay blend table for a w x h band; 0 when unsupported. */
    static native int nativeOverlayBytes(int w, int h);

    /** Builds the blend table from a premultiplied RGBA bitmap copy. */
    static native boolean nativePrepareOverlay(
            ByteBuffer rgba, int bw, int bh, int rowBytes, ByteBuffer table, int w, int h);

    /**
     * Brings {@code table}, built from {@code previous}, to what nativePrepareOverlay writes
     * for {@code rgba}, recomputing only what changed; {@code opaque} first sets alpha to 255
     * in {@code rgba}. Returns the cells recomputed (0: same bitmap), or a negative value
     * when the table must be rebuilt with nativePrepareOverlay.
     */
    static native int nativeUpdateOverlay(
            ByteBuffer previous, ByteBuffer rgba, int bw, int bh, int rowBytes,
            boolean opaque, ByteBuffer table, int w, int h);

    /** 0 = blended, 1 = table not ready, negative = rejected. */
    static native int nativeBlendOverlay(
            ByteBuffer table, ByteBuffer dstY, ByteBuffer dstU, ByteBuffer dstV,
            int dstRowY, int dstRowUV, int dstPixUV, int dstW, int dstH);
}
