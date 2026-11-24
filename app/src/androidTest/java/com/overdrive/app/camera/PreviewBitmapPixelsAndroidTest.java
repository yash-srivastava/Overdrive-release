package com.overdrive.app.camera;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;

@RunWith(AndroidJUnit4.class)
public class PreviewBitmapPixelsAndroidTest {
    @Test
    public void rawCopyAndRowFallbackMatchOriginalArgbConversion() {
        Random random = new Random(723);
        for (int[] size : new int[][] {{1, 1}, {3, 5}, {16, 9}, {321, 241}, {1280, 960}}) {
            int width = size[0], height = size[1];
            int count = width * height;
            ByteBuffer rgba = ByteBuffer.allocateDirect(count * 4);
            int[] expected = new int[count];
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            PreviewBitmapPixels converter = new PreviewBitmapPixels(width);
            try {
                // Different colors in each row and channel detect swaps, flips,
                // incorrect row stride and stale pixels after repeated copying.
                for (int pass = 0; pass < 2; pass++) {
                    rgba.clear();
                    for (int i = 0; i < count; i++) {
                        int r = random.nextInt(256);
                        int g = random.nextInt(256);
                        int b = random.nextInt(256);
                        rgba.put((byte) r).put((byte) g).put((byte) b).put((byte) 255);
                        expected[(height - 1 - i / width) * width + i % width] = 0xff000000 | (r << 16) | (g << 8) | b;
                    }
                    // The raw path consumes the bottom-first frame in place.
                    ByteBuffer fallback = ByteBuffer.allocateDirect(count * 4);
                    rgba.flip();
                    fallback.put(rgba);
                    converter.copyOpaqueRgba(rgba, bitmap);
                    int[] actual = new int[count];
                    bitmap.getPixels(actual, 0, width, 0, 0, width, height);
                    assertArrayEquals(expected, actual);
                    bitmap.eraseColor(0);
                    PreviewBitmapPixels.copyRows(fallback, bitmap);
                    bitmap.getPixels(actual, 0, width, 0, 0, width, height);
                    assertArrayEquals(expected, actual);
                }
            } finally {
                bitmap.recycle();
            }
        }
    }
}
