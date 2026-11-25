package com.overdrive.app.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;

/**
 * available() is what the post-wake listen budget is anchored on: it must agree exactly with what
 * read() will hand out, including the cap after the producer lapped the consumer.
 */
public class PcmRingTest {

    @Test
    public void availableIsWrittenMinusRead() {
        PcmRing ring = new PcmRing(16000);
        ring.write(new short[3000], 0, 3000);
        ring.read(new short[1024], 0, 1024);
        assertEquals(1976, ring.available());
    }

    @Test
    public void availableIsZeroAfterSkipToLatest() {
        PcmRing ring = new PcmRing(16000);
        ring.write(new short[3000], 0, 3000);
        ring.skipToLatest();
        assertEquals(0, ring.available());
    }

    @Test
    public void availableIsBoundedByCapacityAfterALap() {
        PcmRing ring = new PcmRing(1024);
        ring.write(new short[5000], 0, 5000);
        assertEquals(1024, ring.available());
        assertEquals(1024, ring.read(new short[5000], 0, 5000));
    }

    @Test
    public void availableAgreesWithRead() {
        PcmRing ring = new PcmRing(4096);
        ring.write(new short[3000], 0, 3000);
        ring.read(new short[500], 0, 500);
        ring.write(new short[2000], 0, 2000);
        int expected = ring.available();
        int total = 0;
        short[] dst = new short[700];
        int n;
        while ((n = ring.read(dst, 0, dst.length)) > 0) total += n;
        assertEquals(expected, total);
        assertEquals(0, ring.available());
    }

    @Test
    public void wrappedReadAndWriteRespectOffsets() {
        PcmRing ring = new PcmRing(5);
        ring.write(new short[]{99, 1, 2, 3, 4, 99}, 1, 4);
        assertEquals(3, ring.read(new short[3], 0, 3));
        ring.write(new short[]{99, 5, 6, 7, 99}, 1, 3);
        short[] dst = {99, 99, 99, 99, 99, 99};
        assertEquals(4, ring.read(dst, 1, 4));
        assertArrayEquals(new short[]{99, 4, 5, 6, 7, 99}, dst);
        assertEquals(0, ring.droppedSamples());
    }

    @Test
    public void oversizedWriteRetainsNewestSamplesAndCountsLaps() {
        PcmRing ring = new PcmRing(3);
        ring.write(new short[]{1, 2}, 0, 2);
        ring.write(new short[]{99, 3, 4, 5, 6, 7, 8, 9, 99}, 1, 7);
        short[] dst = new short[3];
        assertEquals(3, ring.available());
        assertEquals(0, ring.droppedSamples());
        assertEquals(2, ring.read(dst, 0, 2));
        assertEquals(6, ring.droppedSamples());
        assertEquals(1, ring.read(dst, 2, 1));
        assertArrayEquals(new short[]{7, 8, 9}, dst);
        assertEquals(0, ring.available());
    }

    @Test
    public void randomizedOperationsMatchFifoIncludingDeferredDropAccounting() {
        Random random = new Random(0x50434d);
        for (int capacity : new int[]{1, 2, 3, 7, 64, 257, 1024}) {
            PcmRing ring = new PcmRing(capacity);
            ArrayDeque<Short> expected = new ArrayDeque<>();
            long pendingDrops = 0;
            long reportedDrops = 0;
            short[] src = new short[capacity * 3 + 4];
            short[] dst = new short[capacity + 4];
            short[] reference = new short[dst.length];
            for (int step = 0; step < 5000; step++) {
                int op = random.nextInt(10);
                if (op < 5) {
                    int len = random.nextInt(src.length - 3);
                    for (int i = 0; i < len; i++) src[i + 2] = (short) random.nextInt();
                    ring.write(src, 2, len);
                    for (int i = 0; i < len; i++) {
                        if (expected.size() == capacity) {
                            expected.removeFirst();
                            pendingDrops++;
                        }
                        expected.addLast(src[i + 2]);
                    }
                } else if (op < 9) {
                    int max = random.nextInt(capacity + 1);
                    Arrays.fill(dst, (short) 12345);
                    Arrays.fill(reference, (short) 12345);
                    int n = Math.min(max, expected.size());
                    for (int i = 0; i < n; i++) reference[i + 2] = expected.removeFirst();
                    reportedDrops += pendingDrops;
                    pendingDrops = 0;
                    assertEquals(n, ring.read(dst, 2, max));
                    assertArrayEquals(reference, dst);
                } else {
                    ring.skipToLatest();
                    expected.clear();
                    pendingDrops = 0;
                }
                assertEquals(expected.size(), ring.available());
                assertEquals(reportedDrops, ring.droppedSamples());
            }
        }
    }
}
