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
}
