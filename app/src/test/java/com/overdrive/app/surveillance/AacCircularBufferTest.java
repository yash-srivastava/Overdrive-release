package com.overdrive.app.surveillance;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AacCircularBufferTest {
    @Test
    public void matchesFifoReferenceAcrossWrapGrowthAndClockResets() {
        AacCircularBuffer ring = new AacCircularBuffer(1, 8000);
        ArrayDeque<AacCircularBuffer.Packet> reference = new ArrayDeque<>();
        Random random = new Random(12082);
        int bytes = 0;
        for (int i = 0; i < 12_000; i++) {
            // Alternate dense small packets with large ones; exercise header growth,
            // byte wrap, eviction of multiple packets and duplicate/backward PTS.
            int length = i % 1000 < 500 ? 1 + random.nextInt(50) : 1 + random.nextInt(500);
            if (i % 997 == 0) length = ring.getByteBudget();
            byte[] source = new byte[length + 6];
            random.nextBytes(source);
            long pts = i % 127 == 0 ? Long.MIN_VALUE : i % 131 == 0 ? Long.MAX_VALUE : random.nextInt(300);
            ring.add(source, 3, length, pts);
            reference.addLast(new AacCircularBuffer.Packet(Arrays.copyOfRange(source, 3, 3 + length), pts));
            Arrays.fill(source, (byte) 0); // Ingest source can be reused immediately.
            bytes += length;
            while (bytes > ring.getByteBudget()) bytes -= reference.removeFirst().data.length;
            assertEquals(bytes, ring.getByteCount());
            assertEquals(reference.size(), ring.getPacketCount());
            if (i % 43 == 0) {
                long from = random.nextInt(300), to = random.nextInt(300);
                List<AacCircularBuffer.Packet> expected = new ArrayList<>();
                for (AacCircularBuffer.Packet p : reference) {
                    if (p.ptsUs >= from && p.ptsUs <= to) expected.add(p);
                }
                assertPackets(expected, ring.snapshotRange(from, to));
                assertEquals(bytes, ring.getByteCount());
            }
            if (i % 1301 == 1300) {
                assertPackets(new ArrayList<>(reference), ring.drainAll());
                reference.clear();
                bytes = 0;
                assertEquals(0, ring.getByteCount());
                assertEquals(0, ring.getPacketCount());
            }
        }
        assertPackets(new ArrayList<>(reference), ring.drainAll());
    }

    @Test
    public void growsWrappedHeadersWithoutAnExtraPacketLimit() {
        AacCircularBuffer ring = new AacCircularBuffer(1, 8000);
        byte[] large = new byte[256];
        for (int i = 0; i < 65; i++) ring.add(large, 0, large.length, i);
        // One header has wrapped. Smaller packets permit more than 64 live headers.
        byte[] tiny = {42};
        for (int i = 65; i < 1065; i++) ring.add(tiny, 0, 1, i);
        List<AacCircularBuffer.Packet> all = ring.drainAll();
        assertEquals(1060, all.size());
        assertEquals(5L, all.get(0).ptsUs);
        for (int i = 0; i < all.size(); i++) {
            assertEquals(i + 5L, all.get(i).ptsUs);
            assertArrayEquals(i + 5 < 65 ? large : tiny, all.get(i).data);
        }
    }

    @Test
    public void exportsOwnBytesAndInvalidInputCannotEraseHistory() {
        AacCircularBuffer ring = new AacCircularBuffer(1, 8000);
        byte[] data = {1, 2, 3, 4};
        ring.add(data, 1, 2, 7);
        ring.add(null, 0, 1, 0);
        ring.add(data, Integer.MAX_VALUE, 2, 0);
        ring.add(data, 0, 0, 0);
        byte[] oversized = new byte[ring.getByteBudget() + 1];
        ring.add(oversized, 0, oversized.length, 0);
        assertEquals(2, ring.getByteCount());
        List<AacCircularBuffer.Packet> first = ring.snapshotRange(7, 7);
        assertEquals(1, first.size());
        first.get(0).data[0] = 99;
        List<AacCircularBuffer.Packet> second = ring.drainAll();
        assertArrayEquals(new byte[]{2, 3}, second.get(0).data);
        byte[] full = new byte[ring.getByteBudget()];
        Arrays.fill(full, (byte) 8);
        ring.add(full, 0, full.length, Long.MAX_VALUE);
        assertEquals(full.length, ring.getByteCount());
        assertEquals(1, ring.snapshotRange(Long.MAX_VALUE, Long.MAX_VALUE).size());
        ring.clear();
        assertEquals(0, ring.getByteCount());
        assertEquals(0, ring.getPacketCount());
        ring.add(data, 0, data.length, 1);
        assertArrayEquals(new byte[]{2, 3}, second.get(0).data);
        assertArrayEquals(data, ring.drainAll().get(0).data);
    }

    @Test
    public void warmIngestReusesAllThreeBackingArrays() throws Exception {
        AacCircularBuffer ring = new AacCircularBuffer(62, 64000);
        byte[] data = new byte[256];
        for (int i = 0; i < 5000; i++) ring.add(data, 0, data.length, i);
        Field payload = AacCircularBuffer.class.getDeclaredField("payload");
        Field lengths = AacCircularBuffer.class.getDeclaredField("lengths");
        Field timestamps = AacCircularBuffer.class.getDeclaredField("timestamps");
        payload.setAccessible(true);
        lengths.setAccessible(true);
        timestamps.setAccessible(true);
        Object arena = payload.get(ring), sizes = lengths.get(ring), pts = timestamps.get(ring);
        for (int i = 0; i < 100_000; i++) ring.add(data, 0, data.length, i);
        assertSame(arena, payload.get(ring));
        assertSame(sizes, lengths.get(ring));
        assertSame(pts, timestamps.get(ring));
        assertEquals(2906, ring.getPacketCount());
        assertEquals(2906 * 256, ring.getByteCount());
        List<AacCircularBuffer.Packet> tail = ring.drainAll();
        assertEquals(100_000L - 2906, tail.get(0).ptsUs);
        assertEquals(99_999L, tail.get(tail.size() - 1).ptsUs);
        assertSame(arena, payload.get(ring));
        ring.clear();
        assertNull(payload.get(ring));
        assertNull(lengths.get(ring));
        assertNull(timestamps.get(ring));
    }

    @Test
    public void concurrentDrainsDoNotLosePacketsOrDriftAccounting() throws Exception {
        AacCircularBuffer ring = new AacCircularBuffer(62, 64000);
        int total = 20_000; // All bytes fit even if the consumer is delayed.
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try {
                start.await();
                byte[] frame = new byte[8];
                for (int i = 0; i < total; i++) {
                    frame[0] = (byte) i;
                    ring.add(frame, 0, frame.length, i);
                }
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.set(true);
            }
        });
        producer.start();
        start.countDown();
        List<AacCircularBuffer.Packet> all = new ArrayList<>();
        try {
            while (!done.get()) {
                all.addAll(ring.drainAll());
                Thread.yield();
            }
        } finally {
            producer.join();
        }
        all.addAll(ring.drainAll());
        assertNull(failure.get());
        assertEquals(total, all.size());
        for (int i = 0; i < total; i++) {
            assertEquals((long) i, all.get(i).ptsUs);
            assertEquals((byte) i, all.get(i).data[0]);
        }
        assertEquals(0, ring.getByteCount());
        assertEquals(0, ring.getPacketCount());
    }

    private static void assertPackets(List<AacCircularBuffer.Packet> expected,
                                      List<AacCircularBuffer.Packet> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).ptsUs, actual.get(i).ptsUs);
            assertArrayEquals(expected.get(i).data, actual.get(i).data);
        }
    }
}
