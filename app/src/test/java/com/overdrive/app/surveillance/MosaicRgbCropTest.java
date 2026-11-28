package com.overdrive.app.surveillance;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;

import java.util.Arrays;
import org.junit.Test;

public class MosaicRgbCropTest {
    @Test
    public void extractsAllFourQuadrantsInPackedRgbOrder() {
        int width = 320;
        int height = 240;
        byte[] mosaic = new byte[width * height * 4 * 3];
        new java.util.Random(42).nextBytes(mosaic);

        for (int quadrant = 0; quadrant < 4; quadrant++) {
            byte[] expected = new byte[width * height * 3];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int source = ((y + (quadrant / 2) * height) * width * 2
                            + x + (quadrant % 2) * width) * 3;
                    for (int c = 0; c < 3; c++) {
                        expected[(y * width + x) * 3 + c] = mosaic[source + c];
                    }
                }
            }
            byte[] scratch = new byte[expected.length];
            MosaicRgbCrop.copyInto(mosaic, quadrant, width, height, scratch);
            assertArrayEquals(expected, scratch);
            assertArrayEquals(expected, MosaicRgbCrop.snapshot(mosaic, quadrant, width, height));
        }
    }

    @Test
    public void ownedSnapshotSurvivesSourceAndScratchReuse() {
        byte[] mosaic = new byte[4 * 3 * 4 * 3];
        new java.util.Random(43).nextBytes(mosaic);
        byte[] owned = MosaicRgbCrop.snapshot(mosaic, 3, 4, 3);
        byte[] expected = owned.clone();
        byte[] scratch = MosaicRgbCrop.snapshot(mosaic, 3, 4, 3);
        assertNotSame(owned, scratch);

        Arrays.fill(mosaic, (byte) 0);
        MosaicRgbCrop.copyInto(mosaic, 0, 4, 3, scratch);
        assertArrayEquals(expected, owned);
    }
}
