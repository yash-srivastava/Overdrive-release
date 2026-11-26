package com.overdrive.app.server.hls;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

public class SampleRangeCopierTest {
    private static final class Source implements SampleRangeCopier.Reader {
        final byte[] data;
        final int maxRead;
        int calls;
        long bytes;

        Source(int size, int maxRead) {
            data = new byte[size];
            new Random(37).nextBytes(data);
            this.maxRead = maxRead;
        }

        @Override public int read(ByteBuffer buffer, long position) {
            calls++;
            if (position >= data.length) return -1;
            int n = Math.min(maxRead, Math.min(buffer.remaining(), data.length - (int) position));
            buffer.put(data, (int) position, n);
            bytes += n;
            return n;
        }
    }

    private static void compare(Source src, long[] offsets, int[] sizes, int from, int to)
            throws IOException {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = from; i < to; i++) expected.write(src.data, (int) offsets[i], sizes[i]);
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        new SampleRangeCopier(src, actual).copySamples(offsets, sizes, from, to);
        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
    }

    @Test public void batchesNearbyInterleavedSamplesWithoutWritingGaps() throws IOException {
        Source src = new Source(100_000, Integer.MAX_VALUE);
        long[] offsets = new long[20];
        int[] sizes = new int[20];
        for (int i = 0; i < 20; i++) {
            offsets[i] = i * 2250L;
            sizes[i] = 2000;
        }
        compare(src, offsets, sizes, 0, sizes.length);
        // 4 KiB total gap cap allows 17 samples in the first batch, 3 in the next.
        assertEquals(2, src.calls);
        assertEquals(44_500, src.bytes);
    }

    @Test public void doesNotReadLargeGapsOrDisproportionateGaps() throws IOException {
        Source src = new Source(100_000, Integer.MAX_VALUE);
        compare(src, new long[] {0, 2100, 9000, 200}, new int[] {100, 100, 200, 100}, 0, 4);
        assertEquals(4, src.calls);
        assertEquals(500, src.bytes);
    }

    @Test public void largeContiguousRangesStillStreamInChunks() throws IOException {
        Source src = new Source(200_000, Integer.MAX_VALUE);
        compare(src, new long[] {0, 80_000, 160_100}, new int[] {80_000, 80_000, 1000}, 0, 3);
        assertEquals(4, src.calls);
        assertEquals(161_000, src.bytes);
    }

    @Test public void fillsBatchAcrossShortReadsAndThrowsOnTruncation() throws IOException {
        Source src = new Source(6000, 37);
        compare(src, new long[] {0, 2100}, new int[] {2000, 2000}, 0, 2);
        assertEquals(4100, src.bytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new SampleRangeCopier(src, out).copySamples(
                    new long[] {0, 4100}, new int[] {4000, 2000}, 0, 2);
            fail("truncation must fail");
        } catch (EOFException expected) {
            assertEquals(0, out.size());
        }
    }

    @Test public void randomizedRangesMatchSampleBySampleReference() throws IOException {
        Random random = new Random(123456);
        Source full = new Source(256 * 1024, Integer.MAX_VALUE);
        Source partial = new Source(256 * 1024, 997);
        for (int test = 0; test < 1000; test++) {
            int n = 1 + random.nextInt(60);
            long[] offsets = new long[n];
            int[] sizes = new int[n];
            int position = random.nextInt(1000);
            for (int i = 0; i < n; i++) {
                if (random.nextInt(10) == 0) position = random.nextInt(10_000);
                if (random.nextBoolean()) position += random.nextInt(5000);
                sizes[i] = random.nextInt(10) == 0 ? 0 : random.nextInt(4000);
                if (position + sizes[i] > full.data.length) position = 0;
                offsets[i] = position;
                position += sizes[i];
            }
            int from = random.nextInt(n + 1);
            int to = from + random.nextInt(n - from + 1);
            compare(test % 2 == 0 ? full : partial, offsets, sizes, from, to);
        }
    }
}
