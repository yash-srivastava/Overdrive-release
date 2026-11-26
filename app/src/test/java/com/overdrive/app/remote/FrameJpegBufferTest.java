package com.overdrive.app.remote;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FrameJpegBufferTest {
    @Test
    public void publishedFramesAreDetachedAndHaveExactLength() {
        FrameJpegBuffer buffer = new FrameJpegBuffer();
        byte[] first = new byte[100_000];
        Arrays.fill(first, (byte) 0x75);
        buffer.write(first, 0, first.length);
        byte[] published = buffer.toByteArray();
        buffer.reset();
        byte[] second = {1, 2, 3};
        buffer.write(second, 0, second.length);
        assertArrayEquals(second, buffer.toByteArray());
        assertArrayEquals(first, published);
        assertEquals(FrameJpegBuffer.INITIAL_BYTES, buffer.capacity());
    }

    @Test
    public void normalGrowthIsRetainedButOversizedScratchIsTrimmed() {
        FrameJpegBuffer buffer = new FrameJpegBuffer();
        byte[] normal = new byte[FrameJpegBuffer.INITIAL_BYTES + 1];
        buffer.write(normal, 0, normal.length);
        int grown = buffer.capacity();
        assertTrue(grown >= normal.length && grown <= FrameJpegBuffer.MAX_RETAINED_BYTES);
        buffer.reset();
        assertEquals(grown, buffer.capacity());

        byte[] large = new byte[FrameJpegBuffer.MAX_RETAINED_BYTES + 1];
        Arrays.fill(large, (byte) 42);
        buffer.write(large, 0, large.length);
        assertTrue(buffer.capacity() > FrameJpegBuffer.MAX_RETAINED_BYTES);
        byte[] published = buffer.toByteArray();
        buffer.reset();
        assertEquals(0, buffer.size());
        assertEquals(FrameJpegBuffer.INITIAL_BYTES, buffer.capacity());
        assertArrayEquals(large, published);
    }

    @Test
    public void resetDiscardsPartialFailedFrame() {
        FrameJpegBuffer buffer = new FrameJpegBuffer();
        buffer.write(0xff);
        buffer.write(0xd8);
        buffer.reset();
        assertEquals(0, buffer.size());
        buffer.write(7);
        assertArrayEquals(new byte[] {7}, buffer.toByteArray());
    }
}
