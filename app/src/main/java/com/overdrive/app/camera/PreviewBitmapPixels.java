package com.overdrive.app.camera;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Sampler-thread-only conversion of bottom-first opaque RGBA readback. */
final class PreviewBitmapPixels {
    private final byte[] rowScratch;

    PreviewBitmapPixels(int width) {
        rowScratch = new byte[width * 4];
    }

    /** Consumes the readback; the fast path reverses rows in place. */
    void copyOpaqueRgba(ByteBuffer rgba, Bitmap bitmap) {
        rgba.clear();
        // Android's raw ARGB_8888 word is 0xAABBGGRR. On little-endian
        // devices it is exactly RGBA in memory. Require tightly packed rows
        // before using the raw copy; setPixels handles any other row stride.
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                && bitmap.getConfig() == Bitmap.Config.ARGB_8888
                && bitmap.getRowBytes() == bitmap.getWidth() * 4) {
            flipRows(rgba, bitmap.getHeight());
            bitmap.copyPixelsFromBuffer(rgba);
        } else {
            copyRows(rgba, bitmap);
        }
    }

    /** Swap non-overlapping row ranges using bulk buffer copies, not pixel loops. */
    void flipRows(ByteBuffer rgba, int height) {
        int stride = rowScratch.length;
        ByteBuffer other = rgba.duplicate();
        for (int y = 0; y < height / 2; y++) {
            int top = y * stride;
            int bottom = (height - 1 - y) * stride;
            rgba.clear();
            rgba.position(top);
            rgba.get(rowScratch);
            other.clear();
            other.position(bottom);
            other.limit(bottom + stride);
            rgba.position(top);
            rgba.put(other);
            rgba.position(bottom);
            rgba.put(rowScratch);
        }
        rgba.clear();
    }

    /** Portable fallback; only one row of Java scratch is needed. */
    static void copyRows(ByteBuffer rgba, Bitmap bitmap) {
        rgba.clear();
        int width = bitmap.getWidth();
        int[] row = new int[width];
        for (int y = bitmap.getHeight() - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                int r = rgba.get() & 0xff;
                int g = rgba.get() & 0xff;
                int b = rgba.get() & 0xff;
                rgba.get(); // alpha is intentionally ignored, as in the original path
                row[x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
    }
}
