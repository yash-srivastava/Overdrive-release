package com.overdrive.app.parking.signage;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;

/** Exact RGB normalization with reusable bulk-write scratch. One instance per OCR backend. */
final class OcrPixelNormalizer {
    private static final float[] RED = table(0.485f, 0.229f);
    private static final float[] GREEN = table(0.456f, 0.224f);
    private static final float[] BLUE = table(0.406f, 0.225f);
    private static final float[] SIGNED = table(0.5f, 0.5f);

    // 1024 interleaved RGB pixels, or 3072 planar pixels.
    private final float[] scratch = new float[3072];

    private static float[] table(float mean, float std) {
        float[] result = new float[256];
        for (int i = 0; i < result.length; i++) {
            // Keep the original float operation order, including rounding.
            result[i] = (i / 255f - mean) / std;
        }
        return result;
    }

    /** ImageNet normalization, optionally padding a partial detector tile. */
    void detector(int[] pixels, int stride, int contentW, int contentH,
                         ByteBuffer buf, boolean nchw, int width, int height) {
        fill(pixels, stride, contentW, contentH, buf, nchw, width, height, RED, GREEN, BLUE);
    }

    /** Signed RGB normalization with right padding for the recognizer. */
    void recognizer(int[] pixels, int contentW, int height,
                           ByteBuffer buf, boolean nchw, int width) {
        fill(pixels, contentW, contentW, height, buf, nchw, width, height, SIGNED, SIGNED, SIGNED);
    }

    private void fill(int[] pixels, int stride, int contentW, int contentH,
                      ByteBuffer buf, boolean nchw, int width, int height,
                      float[] red, float[] green, float[] blue) {
        buf.rewind();
        FloatBuffer output = buf.asFloatBuffer();
        // Normalize into ordinary heap memory, then transfer each bounded span
        // in bulk instead of calling direct-buffer putFloat for every channel.
        if (!nchw) {
            for (int y = 0; y < height; y++) {
                int row = y * stride;
                for (int x = 0; x < width; ) {
                    int count = Math.min(width - x, scratch.length / 3);
                    int valid = y < contentH ? Math.max(0, Math.min(count, contentW - x)) : 0;
                    int pos = 0;
                    for (int i = 0; i < valid; i++) {
                        int p = pixels[row + x + i];
                        scratch[pos++] = red[(p >> 16) & 0xff];
                        scratch[pos++] = green[(p >> 8) & 0xff];
                        scratch[pos++] = blue[p & 0xff];
                    }
                    Arrays.fill(scratch, pos, count * 3, 0f);
                    output.put(scratch, 0, count * 3);
                    x += count;
                }
            }
        } else {
            for (int c = 0; c < 3; c++) {
                float[] values = c == 0 ? red : (c == 1 ? green : blue);
                int shift = 16 - 8 * c;
                for (int y = 0; y < height; y++) {
                    int row = y * stride;
                    for (int x = 0; x < width; ) {
                        int count = Math.min(width - x, scratch.length);
                        int valid = y < contentH ? Math.max(0, Math.min(count, contentW - x)) : 0;
                        for (int i = 0; i < valid; i++) {
                            scratch[i] = values[(pixels[row + x + i] >> shift) & 0xff];
                        }
                        Arrays.fill(scratch, valid, count, 0f);
                        output.put(scratch, 0, count);
                        x += count;
                    }
                }
            }
        }
        // The view has its own position; preserve the ByteBuffer caller contract.
        buf.position(output.position() * Float.BYTES);
    }
}
