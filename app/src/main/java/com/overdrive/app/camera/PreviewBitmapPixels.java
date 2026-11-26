package com.overdrive.app.camera;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Sampler-thread-only conversion of top-first opaque RGBA readback. */
final class PreviewBitmapPixels {
    private PreviewBitmapPixels() {}

    /** Copies the readback in bitmap row order without rearranging the source. */
    static void copyOpaqueRgba(ByteBuffer rgba, Bitmap bitmap) {
        rgba.clear();
        // Android's raw ARGB_8888 word is 0xAABBGGRR. On little-endian
        // devices it is exactly RGBA in memory. Require tightly packed rows
        // before using the raw copy; setPixels handles any other row stride.
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                && bitmap.getConfig() == Bitmap.Config.ARGB_8888
                && bitmap.getRowBytes() == bitmap.getWidth() * 4) {
            bitmap.copyPixelsFromBuffer(rgba);
        } else {
            copyRows(rgba, bitmap);
        }
    }

    /** Portable fallback; only one row of Java scratch is needed. */
    static void copyRows(ByteBuffer rgba, Bitmap bitmap) {
        rgba.clear();
        int width = bitmap.getWidth();
        int[] row = new int[width];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            readOpaqueArgbRow(rgba, row);
            bitmap.setPixels(row, 0, width, 0, y, width, 1);
        }
    }

    /** Consumes exactly one RGBA row, preserving RGB and ignoring alpha. */
    static void readOpaqueArgbRow(ByteBuffer rgba, int[] row) {
        for (int x = 0; x < row.length; x++) {
            int r = rgba.get() & 0xff;
            int g = rgba.get() & 0xff;
            int b = rgba.get() & 0xff;
            rgba.get();
            row[x] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
    }
}
