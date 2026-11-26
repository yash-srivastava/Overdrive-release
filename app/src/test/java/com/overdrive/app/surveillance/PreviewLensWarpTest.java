package com.overdrive.app.surveillance;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

public class PreviewLensWarpTest {
    @Test public void matchesOriginalForDifferentTilesAndStrips() {
        Random random = new Random(98123L);
        for (int[] size : new int[][]{{2, 2}, {9, 7}, {160, 90}, {160, 120}}) {
            int w = size[0], h = size[1];
            for (double aspect : new double[]{0.25, 0.75, 1.0}) {
                for (int strength : new int[]{1, 37, 100, 101}) {
                    PreviewLensWarp warp = PreviewLensWarp.forTile(w, h, aspect, strength);
                    for (int strip : new int[]{0, 1, h - 1, 0}) {
                        int[] input = randomPixels(w, h, random);
                        int[] actual = new int[input.length];
                        warp.apply(input, actual, strip);
                        assertArrayEquals(original(input, w, h, aspect, strength, strip), actual);
                    }
                }
            }
        }
    }

    @Test public void cacheIsKeyedByGeometryAndClampedStrength() {
        PreviewLensWarp first = PreviewLensWarp.forTile(160, 90, 0.75, 100);
        assertSame(first, PreviewLensWarp.forTile(160, 90, 0.75, 101));
        PreviewLensWarp width = PreviewLensWarp.forTile(161, 90, 0.75, 100);
        assertNotSame(first, width);
        PreviewLensWarp height = PreviewLensWarp.forTile(161, 91, 0.75, 100);
        assertNotSame(width, height);
        PreviewLensWarp aspect = PreviewLensWarp.forTile(161, 91, 0.76, 100);
        assertNotSame(height, aspect);
        assertNotSame(aspect, PreviewLensWarp.forTile(161, 91, 0.76, 99));
        // An in-flight request can still use its old immutable map after replacement.
        int[] input = randomPixels(160, 90, new Random(9L));
        int[] actual = new int[input.length];
        first.apply(input, actual, 6);
        assertArrayEquals(original(input, 160, 90, 0.75, 100, 6), actual);
    }

    @Test public void oversizedTilesDoNotReplaceTheBoundedCache() {
        PreviewLensWarp atLimit = PreviewLensWarp.forTile(256, 256, 0.75, 60);
        assertSame(atLimit, PreviewLensWarp.forTile(256, 256, 0.75, 60));
        int w = 257, h = 256;
        PreviewLensWarp large = PreviewLensWarp.forTile(w, h, 0.75, 60);
        assertNotSame(large, PreviewLensWarp.forTile(w, h, 0.75, 60));
        assertSame(atLimit, PreviewLensWarp.forTile(256, 256, 0.75, 60));
        int[] input = randomPixels(w, h, new Random(10L));
        int[] actual = new int[input.length];
        large.apply(input, actual, 7);
        assertArrayEquals(original(input, w, h, 0.75, 60, 7), actual);
    }

    private static int[] randomPixels(int w, int h, Random random) {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
        return pixels;
    }

    // Reference: original LensTag per-pixel calculation, without the cache.
    private static int[] original(int[] in, int w, int h, double aspect, int strength, int strip) {
        double t = Math.min(100, strength) / 100.0;
        double k1 = 0.30 * t, k2 = 0.10 * t;
        double a2 = aspect * aspect;
        double zoom = 1 + k1 * a2 + k2 * a2 * a2;
        int[] out = new int[in.length];
        for (int y = 0; y < h; y++) {
            if (y < strip) {
                System.arraycopy(in, y * w, out, y * w, w);
                continue;
            }
            double ny = ((y + 0.5) / h) * 2 - 1;
            for (int x = 0; x < w; x++) {
                double nx = ((x + 0.5) / w) * 2 - 1;
                double ay = ny * aspect;
                double r2 = nx * nx + ay * ay;
                double inv = zoom / (1 + k1 * r2 + k2 * r2 * r2);
                double u = Math.max(0, Math.min(1, nx * inv * 0.5 + 0.5)) * w - 0.5;
                double v = Math.max(0, Math.min(1, ny * inv * 0.5 + 0.5)) * h - 0.5;
                out[y * w + x] = bilinear(in, w, h, u, Math.max(v, strip));
            }
        }
        return out;
    }

    private static int bilinear(int[] p, int w, int h, double u, double v) {
        u = Math.max(0, Math.min(w - 1, u));
        v = Math.max(0, Math.min(h - 1, v));
        int x0 = (int) u, y0 = (int) v;
        int x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
        double fx = u - x0, fy = v - y0;
        int c00 = p[y0 * w + x0], c10 = p[y0 * w + x1], c01 = p[y1 * w + x0], c11 = p[y1 * w + x1];
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            double top = ((c00 >>> shift) & 0xff) * (1 - fx) + ((c10 >>> shift) & 0xff) * fx;
            double bot = ((c01 >>> shift) & 0xff) * (1 - fx) + ((c11 >>> shift) & 0xff) * fx;
            r |= ((int) Math.round(top * (1 - fy) + bot * fy) & 0xff) << shift;
        }
        return r;
    }
}
