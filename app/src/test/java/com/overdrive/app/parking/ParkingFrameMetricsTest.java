package com.overdrive.app.parking;

import org.junit.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class ParkingFrameMetricsTest {
    private static int gray(int luma) {
        return 0xff000000 | luma * 0x010101;
    }

    @Test
    public void blankThresholdsAndBrightPixelPositions() {
        assertEquals(false, ParkingFrameMetrics.isBlank(new int[0], 6, 24));
        int[] pixels = new int[257];
        for (int level = 0; level <= 255; level++) {
            Arrays.fill(pixels, gray(level));
            assertEquals(referenceBlank(pixels), ParkingFrameMetrics.isBlank(pixels, 6, 24));
        }
        for (int pos = 0; pos < pixels.length; pos++) {
            Arrays.fill(pixels, gray(0));
            pixels[pos] = gray(24);
            assertEquals(false, ParkingFrameMetrics.isBlank(pixels, 6, 24));
            pixels[pos] = gray(23);
            assertEquals(true, ParkingFrameMetrics.isBlank(pixels, 6, 24));
        }
        assertEquals(true, ParkingFrameMetrics.isBlank(new int[]{gray(5), gray(6)}, 6, 24));
        assertEquals(false, ParkingFrameMetrics.isBlank(new int[]{gray(5), gray(7)}, 6, 24));
    }

    @Test
    public void matchesOriginalScoreAndBlankOnVariedFrames() {
        Random random = new Random(20261127L);
        int[] sizes = {1, 2, 3, 4, 7, 16, 31, 64, 127};
        for (int w : sizes) {
            for (int h : sizes) {
                for (int mode = 0; mode < 12; mode++) {
                    int[] pixels = new int[w * h];
                    for (int i = 0; i < pixels.length; i++) {
                        switch (mode) {
                            case 0: pixels[i] = gray(0); break;
                            case 1: pixels[i] = gray(255); break;
                            case 2: pixels[i] = gray(16); break;
                            case 3: pixels[i] = gray(239); break;
                            case 4: pixels[i] = gray((i % 2) * 255); break;
                            case 5: pixels[i] = gray(random.nextInt(24)); break;
                            case 6: pixels[i] = gray(random.nextInt(12)); break;
                            case 7: pixels[i] = gray(random.nextInt(256)); break;
                            default: pixels[i] = random.nextInt(); break;
                        }
                    }
                    assertParity(pixels, w, h);
                }
            }
        }
        // Full sampled quadrant, including the RGB565 quantization used by AndroidImageOps.
        int[] pixels = new int[320 * 240];
        for (int i = 0; i < pixels.length; i++) {
            int r = random.nextInt(32), g = random.nextInt(64), b = random.nextInt(32);
            pixels[i] = 0xff000000 | ((r << 3 | r >> 2) << 16)
                    | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
        }
        assertParity(pixels, 320, 240);
    }

    private static void assertParity(int[] pixels, int w, int h) {
        assertEquals(referenceBlank(pixels), ParkingFrameMetrics.isBlank(pixels, 6, 24));
        double expected = referenceScore(pixels, w, h);
        double actual = ParkingFrameMetrics.scoreInPlace(pixels.clone(), w, h);
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    // Original production loops, independent of the new helper.
    static boolean referenceBlank(int[] pixels) {
        long sum = 0;
        int max = 0;
        for (int p : pixels) {
            int l = (((p >> 16) & 0xff) * 77 + ((p >> 8) & 0xff) * 150 + (p & 0xff) * 29) >> 8;
            sum += l;
            if (l > max) max = l;
        }
        return pixels.length > 0 && sum / pixels.length < 6 && max < 24;
    }

    static double referenceScore(int[] pixels, int w, int h) {
        int[] lum = new int[pixels.length];
        int clipped = 0;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int l = (((p >> 16) & 0xff) * 77 + ((p >> 8) & 0xff) * 150 + (p & 0xff) * 29) >> 8;
            lum[i] = l;
            if (l < 16 || l > 239) clipped++;
        }
        double energy = 0.0;
        long n = 0;
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int idx = row + x;
                energy += Math.abs(lum[idx + 1] - lum[idx - 1]) + Math.abs(lum[idx + w] - lum[idx - w]);
                n++;
            }
        }
        if (n == 0) return 0.0;
        double sharp = energy / n / 255.0;
        double exposure = 1.0 - (double) clipped / pixels.length;
        return sharp * exposure * 100.0;
    }
}
