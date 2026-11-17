package com.overdrive.app.streaming;

import java.nio.ByteBuffer;

/** Validates a codec output window without integer overflow or buffer mutation. */
final class CodecBufferWindow {
    private CodecBufferWindow() { }

    static boolean isValid(ByteBuffer buffer, int offset, int size) {
        // Subtraction avoids overflow for offset + size. A zero-size codec
        // control buffer is not a video packet and must not be broadcast.
        return buffer != null && offset >= 0 && size > 0
                && offset <= buffer.limit() && size <= buffer.limit() - offset;
    }
}
