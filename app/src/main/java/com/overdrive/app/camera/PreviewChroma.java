package com.overdrive.app.camera;

import java.nio.ByteBuffer;

/** Packs camera chroma planes into the VU portion of an NV21 preview. */
final class PreviewChroma {
    private PreviewChroma() {}

    static void copyToNv21(ByteBuffer u, int uRowStride, int uPixelStride,
            ByteBuffer v, int vRowStride, int vPixelStride,
            int width, int height, byte[] out, int dst) {
        if (width == 0 || height == 0) return;
        // Direct camera buffers have expensive individual byte accesses. Copy
        // only through the last sampled byte of each row (no trailing padding
        // is guaranteed on the last row). Never assume U and V alias each other.
        if ((u.isDirect() || v.isDirect()) && width >= 64
                && (uPixelStride == 1 || uPixelStride == 2)
                && (vPixelStride == 1 || vPixelStride == 2)) {
            int uSpan = (width - 1) * uPixelStride + 1;
            int vSpan = (width - 1) * vPixelStride + 1;
            byte[] uRow = new byte[uSpan];
            byte[] vRow = vPixelStride == 1 ? new byte[vSpan] : null;
            ByteBuffer uRead = u.duplicate();
            ByteBuffer vRead = v.duplicate();
            for (int row = 0; row < height; row++) {
                uRead.position(row * uRowStride);
                uRead.get(uRow);
                vRead.position(row * vRowStride);
                if (vPixelStride == 2) {
                    // V samples already occupy the even NV21 positions. The
                    // intervening bytes are overwritten with U below, even
                    // when the two planes are backed by separate storage.
                    vRead.get(out, dst, vSpan);
                } else {
                    vRead.get(vRow);
                    for (int col = 0; col < width; col++) {
                        out[dst + col * 2] = vRow[col];
                    }
                }
                if (uPixelStride == 2) {
                    for (int col = 0; col < width; col++) {
                        out[dst + col * 2 + 1] = uRow[col * 2];
                    }
                } else {
                    for (int col = 0; col < width; col++) {
                        out[dst + col * 2 + 1] = uRow[col];
                    }
                }
                dst += width * 2;
            }
            return;
        }
        // Keep the original scalar path for unusual layouts and tiny/heap planes.
        for (int row = 0; row < height; row++) {
            int uOffset = row * uRowStride;
            int vOffset = row * vRowStride;
            for (int col = 0; col < width; col++) {
                out[dst++] = v.get(vOffset + col * vPixelStride);
                out[dst++] = u.get(uOffset + col * uPixelStride);
            }
        }
    }
}
