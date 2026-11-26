package com.overdrive.app.streaming;

import java.nio.ByteBuffer;
import java.util.Collection;

/** Admission budget for the legacy direct WebSocket video sender. */
final class DirectStreamBacklogPolicy {
    static final int MAX_PENDING_BUFFERS = 16;
    // Allow one maximum-size 4 MiB H.264 packet plus its server-side WS header.
    static final long MAX_PENDING_BYTES = 4L * 1024 * 1024 + 10;
    private static final int MAX_HEADER_BYTES = 10;

    private DirectStreamBacklogPolicy() {}

    /**
     * Count allocated capacity, not remaining bytes: a partly written frame
     * still retains its full backing buffer. The library's writer can drain
     * concurrently and its control frames are outside our application lock;
     * this is a high-water admission check, not an exact socket-memory limit.
     */
    static boolean wouldExceed(Collection<ByteBuffer> pending, int payloadBytes) {
        long bytes = (long) payloadBytes + MAX_HEADER_BYTES;
        int count = 1;
        if (bytes > MAX_PENDING_BYTES) return true;
        if (pending.isEmpty()) return false;
        for (ByteBuffer buffer : pending) {
            bytes += buffer.capacity();
            if (++count > MAX_PENDING_BUFFERS || bytes > MAX_PENDING_BYTES) return true;
        }
        return false;
    }
}
