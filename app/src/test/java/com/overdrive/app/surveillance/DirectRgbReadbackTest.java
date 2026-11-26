package com.overdrive.app.surveillance;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.Assert.*;

public class DirectRgbReadbackTest {
    private static ByteBuffer rgba(int value) {
        return ByteBuffer.wrap(new byte[]{(byte) value, 2, 3, 4});
    }

    private static boolean nativeCopy(ByteBuffer rgba, byte[] rgb) {
        for (int i = 0; i < 3; i++) rgb[i] = rgba.get(i);
        return true;
    }

    @Test public void invalidUnmapPreservesLastPublishedBytesInEveryMode() {
        for (DirectRgbReadback.Mode mode : DirectRgbReadback.Mode.values()) {
            DirectRgbReadback readback = new DirectRgbReadback(1, 1, mode,
                    DirectRgbReadbackTest::nativeCopy);
            readback.prepare(rgba(10));
            byte[] previous = readback.finish(true);
            assertArrayEquals(new byte[]{10, 2, 3}, previous);
            readback.prepare(rgba(20));
            assertArrayEquals(new byte[]{10, 2, 3}, previous);
            assertNull(readback.finish(false));
            assertArrayEquals(new byte[]{10, 2, 3}, previous);
            assertNull(readback.finish(true));
            readback.prepare(rgba(30));
            assertArrayEquals(new byte[]{30, 2, 3}, readback.finish(true));
        }
    }

    @Test public void defaultAndExplicitJavaNeverInvokeNative() {
        for (DirectRgbReadback.Mode mode : new DirectRgbReadback.Mode[]{
                null, DirectRgbReadback.Mode.JAVA}) {
            DirectRgbReadback readback = new DirectRgbReadback(1, 1, mode,
                    (source, target) -> { throw new AssertionError("Unexpected native call"); });
            readback.prepare(rgba(10));
            assertArrayEquals(new byte[]{10, 2, 3}, readback.finish(true));
            assertFalse(readback.lastNative());
            assertFalse(readback.lastCompared());
        }
    }

    @Test public void abortedNativePackPreservesFrameAndAllowsNextFrame() {
        for (DirectRgbReadback.Mode mode : new DirectRgbReadback.Mode[]{
                DirectRgbReadback.Mode.NATIVE, DirectRgbReadback.Mode.VERIFY}) {
            DirectRgbReadback readback = new DirectRgbReadback(1, 1, mode,
                    (source, target) -> {
                        nativeCopy(source, target);
                        if (source.get(0) == 20) throw new OutOfMemoryError("Injected JNI failure");
                        return true;
                    });
            readback.prepare(rgba(10));
            byte[] previous = readback.finish(true);
            try {
                readback.prepare(rgba(20));
                fail("Expected injected allocation failure");
            } catch (OutOfMemoryError expected) {
                // Same abort as GpuDownscaler after retiring the mapped slot.
                assertNull(readback.finish(false));
            }
            assertArrayEquals(new byte[]{10, 2, 3}, previous);
            assertNull(readback.finish(true));
            readback.prepare(rgba(30));
            assertArrayEquals(new byte[]{30, 2, 3}, readback.finish(true));
        }
    }

    @Test public void successfulVerificationCountsActualNativeComparison() {
        DirectRgbReadback readback = new DirectRgbReadback(1, 1, DirectRgbReadback.Mode.VERIFY,
                DirectRgbReadbackTest::nativeCopy);
        readback.prepare(rgba(10));
        assertArrayEquals(new byte[]{10, 2, 3}, readback.finish(true));
        assertTrue(readback.lastNative());
        assertTrue(readback.lastCompared());
        assertFalse(readback.lastMismatch());
    }

    @Test public void mismatchUsesReferenceAndDisablesNativeForLane() {
        int[] calls = {0};
        DirectRgbReadback readback = new DirectRgbReadback(1, 1, DirectRgbReadback.Mode.VERIFY,
                (source, target) -> { calls[0]++; Arrays.fill(target, (byte) 99); return true; });
        readback.prepare(rgba(10));
        assertArrayEquals(new byte[]{10, 2, 3}, readback.finish(true));
        assertTrue(readback.lastNative());
        assertTrue(readback.lastCompared());
        assertTrue(readback.lastMismatch());
        readback.prepare(rgba(20));
        assertArrayEquals(new byte[]{20, 2, 3}, readback.finish(true));
        assertFalse(readback.lastNative());
        assertFalse(readback.lastCompared());
        assertFalse(readback.lastMismatch());
        assertEquals(1, calls[0]);
    }

    @Test public void unavailableNativeFallsBackWithoutClaimingComparison() {
        DirectRgbReadback readback = new DirectRgbReadback(1, 1, DirectRgbReadback.Mode.VERIFY,
                (source, target) -> false);
        readback.prepare(rgba(10));
        assertArrayEquals(new byte[]{10, 2, 3}, readback.finish(true));
        assertFalse(readback.lastNative());
        assertFalse(readback.lastCompared());
        readback.prepare(ByteBuffer.allocate(3));
        assertNull(readback.finish(true));
    }

    @Test public void foveatedSizeNativeWarmupAndFallbackPreserveRgbRows() {
        int size = 640;
        ByteBuffer source = ByteBuffer.allocateDirect(size * size * 4);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                source.put((byte) y).put((byte) x).put((byte) (x ^ y)).put((byte) 255);
            }
        }
        source.rewind();
        RgbaReadback reference = new RgbaReadback(size, size);
        byte[] expected = new byte[size * size * 3];
        assertTrue(reference.pack(source, expected));
        DirectRgbReadback readback = new DirectRgbReadback(size, size,
                DirectRgbReadback.Mode.NATIVE, (rgba, target) -> {
                    int dst = 0;
                    for (int y = size - 1; y >= 0; y--) {
                        for (int x = 0; x < size; x++) {
                            int src = (y * size + x) * 4;
                            target[dst++] = rgba.get(src);
                            target[dst++] = rgba.get(src + 1);
                            target[dst++] = rgba.get(src + 2);
                        }
                    }
                    return true;
                });
        for (int i = 0; i <= DirectRgbReadback.NATIVE_SELF_CHECK_FRAMES; i++) {
            readback.prepare(source);
            assertArrayEquals(expected, readback.finish(true));
            assertTrue(readback.lastNative());
            assertEquals(i < DirectRgbReadback.NATIVE_SELF_CHECK_FRAMES,
                    readback.lastCompared());
        }
        readback.prepare(source);
        assertNull(readback.finish(false));
        assertNull(readback.finish(true));

        DirectRgbReadback fallback = new DirectRgbReadback(size, size,
                DirectRgbReadback.Mode.NATIVE, (rgba, target) -> false);
        fallback.prepare(source);
        assertArrayEquals(expected, fallback.finish(true));
        assertFalse(fallback.lastNative());
    }
}
