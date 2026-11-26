package com.overdrive.app.parking.signage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Random;

public class OcrTileMergerTest {
    private static void reference(float[] tile, float[] map, int fw, int tx, int ty,
                                  int tw, int th, int dw, int dh, int ow, int oh) {
        for (int y = 0; y < th; y++) {
            int orow = Math.min(oh - 1, y * oh / dh) * ow;
            int row = (ty + y) * fw + tx;
            for (int x = 0; x < tw; x++) {
                float v = tile[orow + Math.min(ow - 1, x * ow / dw)];
                if (v > map[row + x]) map[row + x] = v;
            }
        }
    }

    private static float value(Random r) {
        switch (r.nextInt(12)) {
            case 0: return Float.NaN;
            case 1: return -0f;
            case 2: return 0f;
            case 3: return Float.POSITIVE_INFINITY;
            case 4: return Float.NEGATIVE_INFINITY;
            default: return r.nextFloat() - 0.25f;
        }
    }

    @Test public void overlappingAndPartialTilesMatchOriginalBitForBit() {
        Random r = new Random(81453);
        for (int trial = 0; trial < 300; trial++) {
            int dw = 1 + r.nextInt(37), dh = 1 + r.nextInt(29);
            // Exercise identical, mixed and resized output geometry.
            int ow = trial % 3 == 0 ? dw : 1 + r.nextInt(41);
            int oh = trial % 4 == 0 ? dh : 1 + r.nextInt(31);
            int fw = dw + 7, fh = dh + 5;
            float[] expected = new float[fw * fh];
            for (int i = 0; i < expected.length; i++) expected[i] = value(r);
            float[] actual = expected.clone();
            OcrTileMerger merger = new OcrTileMerger(dw, dh, ow, oh);
            for (int t = 0; t < 8; t++) {
                int tx = t == 0 ? 0 : r.nextInt(fw);
                int ty = t == 0 ? 0 : r.nextInt(fh);
                int tw = Math.min(dw, fw - tx), th = Math.min(dh, fh - ty);
                float[] tile = new float[ow * oh];
                for (int i = 0; i < tile.length; i++) tile[i] = value(r);
                reference(tile, expected, fw, tx, ty, tw, th, dw, dh, ow, oh);
                merger.merge(tile, actual, fw, tx, ty, tw, th);
                for (int i = 0; i < actual.length; i++) {
                    assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]));
                }
            }
        }
    }
}
