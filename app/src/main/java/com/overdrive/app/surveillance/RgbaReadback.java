package com.overdrive.app.surveillance;

import java.nio.ByteBuffer;

/** Packs bottom-up GL RGBA readback into the existing top-down RGB array. */
final class RgbaReadback {
    private static volatile boolean missingNativeSymbol;
    private final int width;
    private final int height;
    private final int rgbaBytes;
    private final int rgbBytes;
    // Owned by one readback lane; allocated only on the Java fallback path.
    private byte[] scratch;

    RgbaReadback(int width, int height) {
        long pixels = (long) width * height;
        if (width <= 0 || height <= 0 || pixels > Integer.MAX_VALUE / 4) {
            throw new IllegalArgumentException("Invalid readback dimensions");
        }
        this.width = width;
        this.height = height;
        rgbaBytes = (int) pixels * 4;
        rgbBytes = (int) pixels * 3;
    }

    boolean pack(ByteBuffer rgba, byte[] rgb) {
        if (rgba == null || rgb == null || rgba.position() != 0
                || rgba.remaining() < rgbaBytes || rgb.length != rgbBytes) {
            return false;
        }
        if (rgba.isDirect() && !missingNativeSymbol && NativeMotion.isLibraryLoaded()) {
            try {
                if (nativePack(rgba, rgb, width, height)) return true;
            } catch (UnsatisfiedLinkError e) {
                // An older native library can still use the original Java path.
                missingNativeSymbol = true;
            }
        }
        if (scratch == null) scratch = new byte[rgbaBytes];
        rgba.get(scratch, 0, rgbaBytes);
        rgba.position(0);
        int dst = 0;
        for (int y = height - 1; y >= 0; y--) {
            int src = y * width * 4;
            for (int x = 0; x < width; x++, src += 4) {
                rgb[dst++] = scratch[src];
                rgb[dst++] = scratch[src + 1];
                rgb[dst++] = scratch[src + 2];
            }
        }
        return true;
    }

    void clear() {
        scratch = null;
    }

    private static native boolean nativePack(ByteBuffer rgba, byte[] rgb, int width, int height);
}
