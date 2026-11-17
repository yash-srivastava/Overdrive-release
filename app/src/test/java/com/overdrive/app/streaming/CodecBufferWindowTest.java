package com.overdrive.app.streaming;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

public class CodecBufferWindowTest {
    @Test
    public void acceptsExactlyTheVisibleCodecWindowWithoutMovingPosition() {
        ByteBuffer output = ByteBuffer.allocateDirect(20);
        output.position(5);
        output.limit(16);
        assertTrue(CodecBufferWindow.isValid(output, 4, 12));
        assertFalse(CodecBufferWindow.isValid(output, 4, 13));
        assertTrue(output.position() == 5);
        assertTrue(output.limit() == 16);
    }

    @Test
    public void rejectsEmptyNegativeOverflowAndNullWindows() {
        ByteBuffer output = ByteBuffer.allocate(8);
        assertFalse(CodecBufferWindow.isValid(output, 0, 0));
        assertFalse(CodecBufferWindow.isValid(output, -1, 1));
        assertFalse(CodecBufferWindow.isValid(output, 1, -1));
        assertFalse(CodecBufferWindow.isValid(output, Integer.MAX_VALUE, 2));
        assertFalse(CodecBufferWindow.isValid(output, 2, Integer.MAX_VALUE));
        assertFalse(CodecBufferWindow.isValid(null, 0, 1));
    }
}
