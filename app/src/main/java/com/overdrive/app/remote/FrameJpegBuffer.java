package com.overdrive.app.remote;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Encoder-thread scratch; published frames must use the detached toByteArray() result. */
final class FrameJpegBuffer extends ByteArrayOutputStream {
    static final int INITIAL_BYTES = 128 * 1024;
    static final int MAX_RETAINED_BYTES = 256 * 1024;

    FrameJpegBuffer() {
        super(INITIAL_BYTES);
    }

    @Override
    public synchronized void reset() {
        // Captures may contain sensitive UI content. Do not keep the previous
        // frame in scratch after it has been published or compression failed.
        Arrays.fill(buf, 0, count, (byte) 0);
        super.reset();
        // Bound retained scratch after unusually complex frames. This is not
        // a limit on the encoded JPEG size or its quality.
        if (buf.length > MAX_RETAINED_BYTES) {
            buf = new byte[INITIAL_BYTES];
        }
    }

    // Package-private for regression coverage of the retention policy.
    synchronized int capacity() {
        return buf.length;
    }
}
