package com.overdrive.app.camera;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class PreviewBitmapPixelsTest {
    @Test
    public void bulkFlipMatchesReferenceAndCanReuseScratch() {
        Random random = new Random(125);
        for (int width : new int[] {1, 2, 3, 16, 63, 320, 1280, 2560}) {
            PreviewBitmapPixels converter = new PreviewBitmapPixels(width);
            for (int height : new int[] {1, 2, 3, 9, 48}) {
                int stride = width * 4;
                byte[] original = new byte[stride * height];
                random.nextBytes(original);
                byte[] expected = new byte[original.length];
                for (int y = 0; y < height; y++) {
                    System.arraycopy(original, y * stride, expected,
                            (height - 1 - y) * stride, stride);
                }
                ByteBuffer rgba = ByteBuffer.allocateDirect(original.length);
                rgba.put(original);
                converter.flipRows(rgba, height);
                assertEquals(0, rgba.position());
                assertEquals(original.length, rgba.remaining());
                byte[] actual = new byte[original.length];
                rgba.get(actual);
                assertArrayEquals(expected, actual);
                // Flipping twice restores every byte, including the middle row.
                converter.flipRows(rgba, height);
                rgba.get(actual);
                assertArrayEquals(original, actual);
            }
        }
    }
}
