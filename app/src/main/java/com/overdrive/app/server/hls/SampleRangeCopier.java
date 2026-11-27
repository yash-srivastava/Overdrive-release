package com.overdrive.app.server.hls;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/** Streams selected MP4 samples, batching small forward gaps within a bounded read window. */
final class SampleRangeCopier {
    static final int COPY_BUFFER = 64 * 1024;
    // Read at most 4 KiB of unselected bytes per batch (e.g. interleaved AAC).
    static final int MAX_GAP_BYTES = 4 * 1024;
    // Large runs are cheaper to stream directly than to move again in memory.
    private static final int MAX_AVERAGE_COMPACT_RUN = 4 * 1024;

    interface Reader {
        int read(ByteBuffer buffer, long position) throws IOException;
    }

    private final Reader reader;
    private final OutputStream out;
    private final ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER);

    SampleRangeCopier(Reader reader, OutputStream out) {
        this.reader = reader;
        this.out = out;
    }

    void copySamples(long[] offsets, int[] sizes, int from, int to) throws IOException {
        int i = from;
        while (i < to) {
            int first = i;
            long start = offsets[i];
            i = contiguousEnd(offsets, sizes, i, to);
            long end = offsets[i - 1] + sizes[i - 1];
            int firstRunEnd = i;
            long gapBytes = 0;
            int runs = 1;
            // Keep large contiguous runs on the original streaming path. Never
            // bridge backwards/overlapping offsets or extend beyond the last selected run.
            while (i < to && end > start && end - start < COPY_BUFFER) {
                long next = offsets[i];
                if (next < end || next - end > MAX_GAP_BYTES - gapBytes
                        || next - start > COPY_BUFFER - sizes[i]) break;
                int nextRunEnd = contiguousEnd(offsets, sizes, i, to);
                long nextEnd = offsets[nextRunEnd - 1] + sizes[nextRunEnd - 1];
                if (nextEnd <= next || nextEnd - start > COPY_BUFFER) break;
                long totalGap = gapBytes + next - end;
                // Avoid disproportionate read amplification on sparse tracks.
                if (totalGap > (nextEnd - start - totalGap) / 4) break;
                gapBytes = totalGap;
                end = nextEnd;
                i = nextRunEnd;
                runs++;
            }
            if (i == firstRunEnd) {
                copyRange(start, end - start);
            } else {
                // Coalesce small writes in the existing scratch buffer. Keep
                // larger runs on the original path to avoid extra bulk copies.
                boolean compact = end - start - gapBytes <= (long) runs * MAX_AVERAGE_COMPACT_RUN;
                readFully(start, (int) (end - start));
                byte[] bytes = buffer.array();
                int written = 0;
                for (int j = first; j < i; ) {
                    long runStart = offsets[j];
                    j = contiguousEnd(offsets, sizes, j, i);
                    int length = (int) (offsets[j - 1] + sizes[j - 1] - runStart);
                    if (length <= 0) continue;
                    int source = (int) (runStart - start);
                    if (compact) {
                        // The destination ends at or before this run's end;
                        // arraycopy handles overlap and cannot touch later runs.
                        if (source != written) {
                            System.arraycopy(bytes, source, bytes, written, length);
                        }
                        written += length;
                    } else {
                        out.write(bytes, source, length);
                    }
                }
                if (written > 0) out.write(bytes, 0, written);
            }
        }
    }

    private static int contiguousEnd(long[] offsets, int[] sizes, int from, int to) {
        int i = from + 1;
        long end = offsets[from] + sizes[from];
        while (i < to && offsets[i] == end) end += sizes[i++];
        return i;
    }

    private void readFully(long position, int length) throws IOException {
        buffer.clear();
        buffer.limit(length);
        while (buffer.hasRemaining()) {
            int n = reader.read(buffer, position);
            if (n <= 0) throw new EOFException("source truncated at " + position);
            position += n;
        }
    }

    private void copyRange(long position, long length) throws IOException {
        // Preserve streaming of long contiguous ranges and short-read handling.
        while (length > 0) {
            buffer.clear();
            if (length < buffer.capacity()) buffer.limit((int) length);
            int n = reader.read(buffer, position);
            if (n <= 0) throw new EOFException("source truncated at " + position);
            out.write(buffer.array(), 0, n);
            position += n;
            length -= n;
        }
    }
}
