package com.overdrive.app.surveillance;

import java.nio.ByteBuffer;

/** Packs bottom-up GL RGBA readback into the existing top-down RGB array. */
final class RgbaReadback {
    private static volatile boolean missingNativeSymbol;
    private final int width;
    private final int height;
    private final int rgbaBytes;
    private final int rgbBytes;
    // Owned by one readback lane; direct readback reserves it before mapping.
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

    private boolean validSource(ByteBuffer rgba) {
        return rgba != null && rgba.position() == 0 && rgba.remaining() >= rgbaBytes;
    }

    // Synchronous snapshots stay on Java until native readback is validated.
    boolean pack(ByteBuffer rgba, byte[] rgb) {
        if (rgb == null || rgb.length != rgbBytes || !validSource(rgba)) return false;
        if (!copyRgba(rgba)) return false;
        packCopied(rgb);
        return true;
    }

    boolean tryNativePack(ByteBuffer rgba, byte[] rgb) {
        if (!validSource(rgba) || rgb == null || rgb.length != rgbBytes) return false;
        if (rgba.isDirect() && !missingNativeSymbol && NativeMotion.isLibraryLoaded()) {
            try {
                return nativePack(rgba, rgb, width, height);
            } catch (UnsatisfiedLinkError e) {
                // An older native library can still use the original Java path.
                missingNativeSymbol = true;
            }
        }
        return false;
    }

    void reserveScratch() {
        if (scratch == null) scratch = new byte[rgbaBytes];
    }

    // Copy while the PBO is mapped; the Java path can pack after unmapping.
    boolean copyRgba(ByteBuffer rgba) {
        if (!validSource(rgba)) return false;
        reserveScratch();
        rgba.get(scratch, 0, rgbaBytes);
        rgba.position(0);
        return true;
    }

    void packCopied(byte[] rgb) {
        int dst = 0;
        for (int y = height - 1; y >= 0; y--) {
            int src = y * width * 4;
            for (int x = 0; x < width; x++, src += 4) {
                rgb[dst++] = scratch[src];
                rgb[dst++] = scratch[src + 1];
                rgb[dst++] = scratch[src + 2];
            }
        }
    }

    /** Rare post-verification fallback: pack into unpublished RGB using one preallocated row. */
    boolean packRows(ByteBuffer rgba, byte[] rgb, byte[] row) {
        int rowBytes = width * 4;
        if (!validSource(rgba) || rgb == null || rgb.length != rgbBytes
                || row == null || row.length != rowBytes) return false;
        int rowRgbBytes = width * 3;
        try {
            // Read mapped memory sequentially and reverse only the destination rows.
            for (int y = height - 1; y >= 0; y--) {
                rgba.get(row, 0, rowBytes);
                int dst = y * rowRgbBytes;
                for (int src = 0; src < rowBytes; src += 4) {
                    rgb[dst++] = row[src];
                    rgb[dst++] = row[src + 1];
                    rgb[dst++] = row[src + 2];
                }
            }
            return true;
        } finally {
            rgba.position(0);
        }
    }

    void clear() {
        scratch = null;
    }

    private static native boolean nativePack(ByteBuffer rgba, byte[] rgb, int width, int height);
}
