package com.overdrive.app.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

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
    public void arm64NativeMatchesJavaForArrayAndSlicedBuffers() {
        assumeTrue("aarch64".equals(System.getProperty("os.arch")));
        Random random = new Random(129);
        for (int count : new int[]{1, 15, 16, 17, 271, 4097, ELEMENTS}) {
            NormalizedRgbInput reference = new NormalizedRgbInput(count, false);
            NormalizedRgbInput fast = new NormalizedRgbInput(count, true);
            byte[] rgb = new byte[count];
            random.nextBytes(rgb);
            ByteBuffer expected = reference.prepare(rgb);
            ByteBuffer output = fast.prepare(rgb);
            assertTrue("ARM64 native normalization must be active", fast.isNativeEnabled());
            assertFalse(reference.isNativeEnabled());
            assertEqualBits(expected, output, count);

            ByteBuffer heap = ByteBuffer.allocate(count + 9);
            ByteBuffer direct = ByteBuffer.allocateDirect(count + 9);
            for (ByteBuffer backing : new ByteBuffer[]{heap, direct}) {
                backing.position(5);
                backing.put(rgb);
                backing.position(5);
                backing.limit(5 + count);
                ByteBuffer slice = backing.slice();
                slice.position(count / 2);
                output.position(output.limit());
                assertSame(output, fast.prepare(slice));
                assertEquals(count / 2, slice.position());
                assertEquals(0, output.position());
                assertEqualBits(expected, output, count);
                assertTrue(fast.isNativeEnabled());
                // Read-only heap buffers intentionally use the Java fallback;
                // read-only direct buffers can still use the native input address.
                assertSame(output, fast.prepare(slice.asReadOnlyBuffer()));
                assertEqualBits(expected, output, count);
            }
            random.nextBytes(rgb);
            assertSame(output, fast.prepare(rgb));
            assertEqualBits(reference.prepare(rgb), output, count);
            assertTrue(fast.isNativeEnabled());
        }
    }

    private static void assertEqualBits(ByteBuffer expected, ByteBuffer actual, int count) {
        assertEquals(count * Float.BYTES, actual.remaining());
        assertEquals(expected.order(), actual.order());
        for (int i = 0; i < count; i++) {
            assertEquals(Float.floatToIntBits(expected.getFloat(i * Float.BYTES)),
                    Float.floatToIntBits(actual.getFloat(i * Float.BYTES)));
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
