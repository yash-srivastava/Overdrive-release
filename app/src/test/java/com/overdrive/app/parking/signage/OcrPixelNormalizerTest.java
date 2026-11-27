package com.overdrive.app.parking.signage;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class OcrPixelNormalizerTest {
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    @Test public void everyChannelValueMatchesOriginalFormula() {
        int[] pixels = new int[256];
        for (int i = 0; i < 256; i++) {
            pixels[i] = 0xff000000 | i << 16 | (255 - i) << 8 | ((i + 73) & 255);
        }
        for (boolean nchw : new boolean[] {false, true}) {
            check(pixels, 256, 256, 1, 256, 1, nchw, false);
            check(pixels, 256, 256, 1, 256, 1, nchw, true);
        }
    }

    @Test public void partialTilesAndRecognizerPaddingMatchOriginal() {
        Random rng = new Random(19);
        for (int i = 0; i < 500; i++) {
            int w = 1 + rng.nextInt(35), h = 1 + rng.nextInt(25);
            int tw = 1 + rng.nextInt(w), th = 1 + rng.nextInt(h);
            int[] tile = new int[w * h];
            int[] crop = new int[tw * h];
            for (int j = 0; j < tile.length; j++) tile[j] = rng.nextInt();
            for (int j = 0; j < crop.length; j++) crop[j] = rng.nextInt();
            for (boolean nchw : new boolean[] {false, true}) {
                check(tile, w, tw, th, w, h, nchw, false);
                check(crop, tw, tw, h, w, h, nchw, true);
            }
        }
    }

    @Test public void boundedSpansAndReusedScratchMatchOriginal() {
        OcrPixelNormalizer normalizer = new OcrPixelNormalizer();
        Random rng = new Random(29);
        for (int width : new int[] {1023, 1024, 1025, 3071, 3072, 3073, 4097}) {
            int height = 4;
            int[] pixels = new int[width * height];
            for (int i = 0; i < pixels.length; i++) pixels[i] = rng.nextInt();
            for (boolean nchw : new boolean[] {false, true}) {
                for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
                    ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 3 * 4).order(order);
                    check(normalizer, buffer, pixels, width, width, height, width, height, nchw, false);
                    check(normalizer, buffer, pixels, width, width - 1, 2, width, height, nchw, false);
                    check(normalizer, buffer, pixels, width, 1, 1, width, height, nchw, false);
                    check(normalizer, buffer, pixels, width, width, height, width, height, nchw, true);
                }
            }
        }
    }

    private static void check(int[] pixels, int stride, int cw, int ch,
                              int w, int h, boolean nchw, boolean signed) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(w * h * 3 * 4).order(ByteOrder.nativeOrder());
        check(new OcrPixelNormalizer(), buffer, pixels, stride, cw, ch, w, h, nchw, signed);
    }

    private static void check(OcrPixelNormalizer normalizer, ByteBuffer buffer,
                              int[] pixels, int stride, int cw, int ch,
                              int w, int h, boolean nchw, boolean signed) {
        buffer.rewind();
        // A reused buffer must overwrite both content and padding.
        for (int i = 0; i < w * h * 3; i++) buffer.putFloat(Float.NaN);
        if (signed) normalizer.recognizer(pixels, cw, h, buffer, nchw, w);
        else normalizer.detector(pixels, stride, cw, ch, buffer, nchw, w, h);
        assertEquals(buffer.capacity(), buffer.position());
        assertEquals(buffer.capacity(), buffer.limit());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    float expected = 0f;
                    if (x < cw && y < ch) {
                        int value = (pixels[y * stride + x] >> (16 - c * 8)) & 255;
                        expected = signed ? (value / 255f - 0.5f) / 0.5f
                                : (value / 255f - MEAN[c]) / STD[c];
                    }
                    int index = nchw ? (c * h + y) * w + x : (y * w + x) * 3 + c;
                    assertEquals(Float.floatToRawIntBits(expected), buffer.getInt(index * 4));
                }
            }
        }
    }
}
