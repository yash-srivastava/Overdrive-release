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

    private static final class CountingOutput extends ByteArrayOutputStream {
        int calls;

        @Override public synchronized void write(byte[] b, int off, int len) {
            calls++;
            super.write(b, off, len);
        }
    }

    private static int compare(Source src, long[] offsets, int[] sizes, int from, int to)
            throws IOException {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int i = from; i < to; i++) expected.write(src.data, (int) offsets[i], sizes[i]);
        CountingOutput actual = new CountingOutput();
        new SampleRangeCopier(src, actual).copySamples(offsets, sizes, from, to);
        assertArrayEquals(expected.toByteArray(), actual.toByteArray());
        return actual.calls;
    }

    @Test public void batchesNearbyInterleavedSamplesWithoutWritingGaps() throws IOException {
        Source src = new Source(100_000, Integer.MAX_VALUE);
        long[] offsets = new long[20];
        int[] sizes = new int[20];
        for (int i = 0; i < 20; i++) {
            offsets[i] = i * 2250L;
            sizes[i] = 2000;
        }
        assertEquals(4, compare(src, offsets, sizes, 0, sizes.length));
        // First batch packs 17 runs; the remaining three use direct writes.
        // 4 KiB total gap cap allows 17 samples in the first batch, 3 in the next.
        assertEquals(2, src.calls);
        assertEquals(44_500, src.bytes);
    }

    @Test public void compactsDenseInterleavingInPlaceWithoutExtraReads() throws IOException {
        Source src = new Source(40_000, 97);
        long[] offsets = new long[1000];
        int[] sizes = new int[1000];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = i * 36L;
            sizes[i] = 32;
        }
        // All 1000 runs fit in one bounded window, including 3996 gap bytes.
        assertEquals(1, compare(src, offsets, sizes, 0, offsets.length));
        assertEquals(35_996, src.bytes);
        assertEquals((35_996 + 96) / 97, src.calls);
    }

    @Test public void packsAdjacentRunsAndZeroLengthEntriesWithoutLeakingGaps() throws IOException {
        Source src = new Source(20_000, Integer.MAX_VALUE);
        long[] offsets = {0, 100, 200, 210, 310, 320, 420, 430, 530, 540};
        int[] sizes = {100, 100, 0, 100, 0, 100, 0, 100, 0, 100};
        assertEquals(1, compare(src, offsets, sizes, 0, sizes.length));
        assertEquals(640, src.bytes);
        assertEquals(1, src.calls);
        // The same copier must not reuse packed bytes from a prior window.
        CountingOutput out = new CountingOutput();
        SampleRangeCopier copier = new SampleRangeCopier(src, out);
        copier.copySamples(offsets, sizes, 0, sizes.length);
        copier.copySamples(offsets, sizes, 0, sizes.length);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < sizes.length; i++) expected.write(src.data, (int) offsets[i], sizes[i]);
        }
        assertArrayEquals(expected.toByteArray(), out.toByteArray());
        assertEquals(2, out.calls);
    }

    @Test public void packedBatchNeverEmitsPartialBytesOnTruncation() throws IOException {
        Source src = new Source(500, 13);
        CountingOutput out = new CountingOutput();
        try {
            new SampleRangeCopier(src, out).copySamples(
                    new long[] {0, 110, 220, 330, 440}, new int[] {100,100,100,100,100}, 0, 5);
            fail("truncation must fail");
        } catch (EOFException expected) {
            assertEquals(0, out.calls);
            assertEquals(0, out.size());
        }
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
