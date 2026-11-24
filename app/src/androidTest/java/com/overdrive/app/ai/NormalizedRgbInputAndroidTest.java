package com.overdrive.app.ai;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Random;

/** Check actual Android Bitmap resizing and TFLite Support 0.4.4 normalization. */
public class NormalizedRgbInputAndroidTest {
    private static final int SIZE = 640;
    private static final int ELEMENTS = SIZE * SIZE * 3;

    private static TensorImage image(byte[] rgb, int width, int height) {
        TensorBuffer buffer = TensorBuffer.createFixedSize(new int[]{height, width, 3}, DataType.UINT8);
        buffer.loadBuffer(ByteBuffer.wrap(rgb));
        TensorImage image = new TensorImage(DataType.UINT8);
        image.load(buffer);
        return image;
    }

    private static void assertSameFloats(ByteBuffer expected, ByteBuffer actual) {
        expected.rewind();
        actual.rewind();
        FloatBuffer e = expected.asFloatBuffer();
        FloatBuffer a = actual.asFloatBuffer();
        assertEquals(ELEMENTS, e.remaining());
        assertEquals(ELEMENTS, a.remaining());
        for (int i = 0; i < ELEMENTS; i++) {
            int expectedBits = Float.floatToIntBits(e.get());
            int actualBits = Float.floatToIntBits(a.get());
            if (expectedBits != actualBits) assertEquals("Element " + i, expectedBits, actualBits);
        }
    }

    @Test
    public void nativeSizeAndResizedSquareMatchOriginal() {
        Random random = new Random(127);
        NormalizedRgbInput input = new NormalizedRgbInput(ELEMENTS);
        ImageProcessor original = new ImageProcessor.Builder()
                .add(new ResizeOp(SIZE, SIZE, ResizeOp.ResizeMethod.BILINEAR))
                .add(new NormalizeOp(0f, 255f)).build();
        ImageProcessor resize = new ImageProcessor.Builder()
                .add(new ResizeOp(SIZE, SIZE, ResizeOp.ResizeMethod.BILINEAR)).build();
        for (int width : new int[]{SIZE, 320, 800, SIZE}) {
            byte[] rgb = new byte[width * width * 3];
            random.nextBytes(rgb);
            ByteBuffer expected = original.process(image(rgb, width, width)).getBuffer();
            ByteBuffer actual = width == SIZE ? input.prepare(rgb)
                    : input.prepare(resize.process(image(rgb, width, width)).getBuffer());
            assertSameFloats(expected, actual);
        }
    }

    @Test
    public void letterboxCanvasMatchesOriginal() {
        Random random = new Random(128);
        NormalizedRgbInput input = new NormalizedRgbInput(ELEMENTS);
        ImageProcessor normalize = new ImageProcessor.Builder().add(new NormalizeOp(0f, 255f)).build();
        for (int[] dims : new int[][]{{320, 240}, {240, 320}, {319, 240}}) {
            int w = dims[0], h = dims[1];
            byte[] rgb = new byte[w * h * 3];
            random.nextBytes(rgb);
            float scale = Math.min((float) SIZE / w, (float) SIZE / h);
            int contentW = Math.round(w * scale), contentH = Math.round(h * scale);
            ByteBuffer resized = new ResizeOp(contentH, contentW, ResizeOp.ResizeMethod.BILINEAR)
                    .apply(image(rgb, w, h)).getBuffer();
            resized.rewind();
            byte[] canvas = new byte[ELEMENTS];
            Arrays.fill(canvas, (byte) 114);
            int padX = (SIZE - contentW) / 2, padY = (SIZE - contentH) / 2;
            for (int row = 0; row < contentH; row++) {
                resized.get(canvas, ((padY + row) * SIZE + padX) * 3, contentW * 3);
            }
            assertSameFloats(normalize.process(image(canvas, SIZE, SIZE)).getBuffer(), input.prepare(canvas));
        }
    }
}
