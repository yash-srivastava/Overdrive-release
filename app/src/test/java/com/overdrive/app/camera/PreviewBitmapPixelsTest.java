package com.overdrive.app.camera;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PreviewBitmapPixelsTest {
    @Test
    public void fallbackReadsTopFirstRowsAndReusesScratch() {
        Random random = new Random(125);
        for (int width : new int[] {1, 2, 3, 16, 63, 320, 1280, 2560}) {
            int[] row = new int[width];
            for (int height : new int[] {1, 2, 3, 9, 48}) {
                byte[] original = new byte[width * height * 4];
                random.nextBytes(original);
                ByteBuffer rgba = ByteBuffer.allocateDirect(original.length);
                rgba.put(original).flip();
                int[] expected = new int[width];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int i = (y * width + x) * 4;
                        expected[x] = 0xff000000 | ((original[i] & 255) << 16)
                                | ((original[i + 1] & 255) << 8) | (original[i + 2] & 255);
                    }
                    PreviewBitmapPixels.readOpaqueArgbRow(rgba, row);
                    assertArrayEquals(expected, row);
                    assertEquals((y + 1) * width * 4, rgba.position());
                }
                assertEquals(0, rgba.remaining());
            }
        }
    }
}
