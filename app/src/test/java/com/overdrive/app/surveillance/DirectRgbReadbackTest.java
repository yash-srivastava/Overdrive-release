package com.overdrive.app.surveillance;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

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

    @Test public void releasesWarmupScratchOnlyAfterValidMatchingNativeFrames() throws Exception {
        boolean[] available = {true};
        DirectRgbReadback readback = new DirectRgbReadback(1, 1, DirectRgbReadback.Mode.NATIVE,
                (source, target) -> available[0] && nativeCopy(source, target));
        for (int i = 0; i < DirectRgbReadback.NATIVE_SELF_CHECK_FRAMES - 1; i++) {
            readback.prepare(rgba(i));
            assertNotNull(readback.finish(true));
        }
        assertNotNull(fullScratch(readback));
        readback.prepare(rgba(41));
        assertNull(readback.finish(false));
        assertNotNull(fullScratch(readback));
        available[0] = false;
        readback.prepare(rgba(42));
        assertArrayEquals(new byte[]{42, 2, 3}, readback.finish(true));
        assertNotNull(fullScratch(readback));
        available[0] = true;
        readback.prepare(rgba(43));
        assertArrayEquals(new byte[]{43, 2, 3}, readback.finish(true));
        assertTrue(readback.lastCompared());
        assertNull(fullScratch(readback));
    }

    @Test public void postWarmupFallbackStagesRowsWithoutFullScratchOrNativeClaim() throws Exception {
        Random random = new Random(1717);
        for (int[] size : new int[][]{{1, 1}, {17, 3}, {640, 480}, {640, 640}}) {
            int width = size[0], height = size[1];
            boolean[] available = {true};
            RgbaReadback reference = new RgbaReadback(width, height);
            DirectRgbReadback readback = new DirectRgbReadback(width, height,
                    DirectRgbReadback.Mode.NATIVE, (source, target) -> {
                        if (available[0]) return reference.pack(source, target);
                        // A failed pack may already have dirtied staging.
                        Arrays.fill(target, (byte) 99);
                        return false;
                    });
            byte[] data = new byte[width * height * 4];
            byte[] expected = new byte[width * height * 3];
            ByteBuffer source = ByteBuffer.allocateDirect(data.length + 8);
            byte[] previous = null;
            for (int i = 0; i < DirectRgbReadback.NATIVE_SELF_CHECK_FRAMES; i++) {
                random.nextBytes(data);
                source.clear(); source.put(data).flip();
                readback.prepare(source);
                previous = readback.finish(true);
                assertNotNull(previous);
            }
            assertNull(fullScratch(readback));
            byte[] old = previous.clone();
            available[0] = false;
            random.nextBytes(data);
            source.clear(); source.put(data).flip();
            assertTrue(reference.pack(source, expected));
            readback.prepare(source);
            assertArrayEquals(old, previous);
            assertNull(readback.finish(false));
            assertArrayEquals(old, previous);
            assertNull(readback.finish(true));
            // Retry a valid frame, then recover native mode. No full scratch is recreated.
            readback.prepare(source);
            assertArrayEquals(expected, readback.finish(true));
            assertEquals(0, source.position());
            assertEquals(data.length, source.limit());
            assertFalse(readback.lastNative());
            assertFalse(readback.lastCompared());
            assertNull(fullScratch(readback));
            available[0] = true;
            readback.prepare(source);
            assertArrayEquals(expected, readback.finish(true));
            assertTrue(readback.lastNative());
            assertFalse(readback.lastCompared());
            assertNull(fullScratch(readback));
        }
    }

    @Test public void verificationAndJavaRetainScratchAndMismatchDiscardsStaging() throws Exception {
        for (DirectRgbReadback.Mode mode : new DirectRgbReadback.Mode[]{
                DirectRgbReadback.Mode.JAVA, DirectRgbReadback.Mode.VERIFY}) {
            DirectRgbReadback readback = new DirectRgbReadback(1, 1, mode,
                    DirectRgbReadbackTest::nativeCopy);
            for (int i = 0; i < 35; i++) {
                readback.prepare(rgba(i));
                assertNotNull(readback.finish(true));
            }
            assertNotNull(fullScratch(readback));
        }
        DirectRgbReadback mismatch = new DirectRgbReadback(1, 1, DirectRgbReadback.Mode.NATIVE,
                (source, target) -> { Arrays.fill(target, (byte) 99); return true; });
        mismatch.prepare(rgba(1));
        assertArrayEquals(new byte[]{1, 2, 3}, mismatch.finish(true));
        assertNotNull(fullScratch(mismatch));
        assertNull(field(mismatch, "staging"));
        mismatch.prepare(rgba(2));
        assertArrayEquals(new byte[]{2, 2, 3}, mismatch.finish(true));
        assertFalse(mismatch.lastNative());
    }

    private static Object field(Object instance, String name) throws Exception {
        java.lang.reflect.Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static Object fullScratch(DirectRgbReadback readback) throws Exception {
        return field(field(readback, "packer"), "scratch");
    }
}
