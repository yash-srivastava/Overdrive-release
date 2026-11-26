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
    // Keep the direct output path for small batches; larger batches
    // amortize in-place compaction over several avoided stream writes.
    private static final int MIN_PACKED_RUNS = 4;

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
            int runs = 1;
            long gapBytes = 0;
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
                // Fill the entire bounded window before emitting only selected
                // contiguous runs. Gap bytes never leave the server.
                readFully(start, (int) (end - start));
                byte[] data = buffer.array();
                boolean pack = runs >= MIN_PACKED_RUNS;
                int packed = 0;
                for (int j = first; j < i; ) {
                    long runStart = offsets[j];
                    j = contiguousEnd(offsets, sizes, j, i);
                    long runEnd = offsets[j - 1] + sizes[j - 1];
                    if (runEnd > runStart) {
                        int offset = (int) (runStart - start);
                        int length = (int) (runEnd - runStart);
                        if (pack) {
                            // The destination never lies after the source, and
                            // cannot overlap a later run. arraycopy handles overlap
                            // within a run; the first run stays where it is.
                            if (packed != offset) System.arraycopy(data, offset, data, packed, length);
                            packed += length;
                        } else {
                            out.write(data, offset, length);
                        }
                    }
                }
                if (pack && packed > 0) out.write(data, 0, packed);
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
