package com.overdrive.app.surveillance;

/** Row copies from a packed RGB888 2x2 mosaic, with explicit buffer ownership. */
final class MosaicRgbCrop {
    private static final int CHANNELS = 3;

    private MosaicRgbCrop() {}

    /** Independent snapshot safe to hand off to the asynchronous inference lane. */
    static byte[] snapshot(byte[] mosaic, int quadrant, int width, int height) {
        byte[] result = new byte[width * height * CHANNELS];
        copyInto(mosaic, quadrant, width, height, result);
        return result;
    }

    /** Borrowed destination for synchronous callers that already own scratch. */
    static void copyInto(byte[] mosaic, int quadrant, int width, int height, byte[] result) {
        int rowBytes = width * CHANNELS;
        int stride = rowBytes * 2;
        int source = (quadrant / 2) * height * stride + (quadrant % 2) * rowBytes;
        for (int destination = 0; destination < rowBytes * height; destination += rowBytes) {
            System.arraycopy(mosaic, source, result, destination, rowBytes);
            source += stride;
        }
    }
}
