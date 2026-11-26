package com.overdrive.app.parking.signage;

import java.nio.ByteBuffer;

/** Exact 8-bit RGB normalization tables shared by the detector and recognizer. */
final class OcrPixelNormalizer {
    private static final float[] RED = table(0.485f, 0.229f);
    private static final float[] GREEN = table(0.456f, 0.224f);
    private static final float[] BLUE = table(0.406f, 0.225f);
    private static final float[] SIGNED = table(0.5f, 0.5f);

    private OcrPixelNormalizer() {}

    private static float[] table(float mean, float std) {
        float[] result = new float[256];
        for (int i = 0; i < result.length; i++) {
            // Keep the original float operation order, including rounding.
            result[i] = (i / 255f - mean) / std;
        }
        return result;
    }

    /** ImageNet normalization, optionally padding a partial detector tile. */
    static void detector(int[] pixels, int stride, int contentW, int contentH,
                         ByteBuffer buf, boolean nchw, int width, int height) {
        fill(pixels, stride, contentW, contentH, buf, nchw, width, height, RED, GREEN, BLUE);
    }

    /** Signed RGB normalization with right padding for the recognizer. */
    static void recognizer(int[] pixels, int contentW, int height,
                           ByteBuffer buf, boolean nchw, int width) {
        fill(pixels, contentW, contentW, height, buf, nchw, width, height, SIGNED, SIGNED, SIGNED);
    }

    private static void fill(int[] pixels, int stride, int contentW, int contentH,
                             ByteBuffer buf, boolean nchw, int width, int height,
                             float[] red, float[] green, float[] blue) {
        buf.rewind();
        if (!nchw) {
            for (int y = 0; y < contentH; y++) {
                int row = y * stride;
                for (int x = 0; x < contentW; x++) {
                    int p = pixels[row + x];
                    buf.putFloat(red[(p >> 16) & 0xff]);
                    buf.putFloat(green[(p >> 8) & 0xff]);
                    buf.putFloat(blue[p & 0xff]);
                }
                for (int x = contentW; x < width; x++) {
                    buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f);
                }
            }
            for (int y = contentH; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    buf.putFloat(0f); buf.putFloat(0f); buf.putFloat(0f);
                }
            }
        } else {
            for (int c = 0; c < 3; c++) {
                float[] values = c == 0 ? red : (c == 1 ? green : blue);
                int shift = 16 - 8 * c;
                for (int y = 0; y < contentH; y++) {
                    int row = y * stride;
                    for (int x = 0; x < contentW; x++) {
                        buf.putFloat(values[(pixels[row + x] >> shift) & 0xff]);
                    }
                    for (int x = contentW; x < width; x++) buf.putFloat(0f);
                }
                for (int y = contentH; y < height; y++) {
                    for (int x = 0; x < width; x++) buf.putFloat(0f);
                }
            }
        }
    }
}
