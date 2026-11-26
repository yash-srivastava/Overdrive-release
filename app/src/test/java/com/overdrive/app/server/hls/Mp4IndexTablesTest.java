package com.overdrive.app.server.hls;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

/** Exercises the parser's actual sample-table readers without multi-gigabyte media fixtures. */
public class Mp4IndexTablesTest {
    private static final Class<?> INFO;
    private static final Constructor<?> CONSTRUCTOR;
    private static final Method OFFSETS, CTS;
    static {
        try {
            INFO = Class.forName(Mp4Index.class.getName() + "$TrakInfo");
            CONSTRUCTOR = INFO.getDeclaredConstructor();
            CONSTRUCTOR.setAccessible(true);
            OFFSETS = Mp4Index.class.getDeclaredMethod("sampleOffsets", byte[].class,
                    INFO, int[].class, long.class, String.class);
            CTS = Mp4Index.class.getDeclaredMethod("compositionOffsets", byte[].class,
                    INFO, int.class, String.class);
            OFFSETS.setAccessible(true);
            CTS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void field(Object info, String name, int value) throws Exception {
        java.lang.reflect.Field field = INFO.getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(info, value);
    }

    private static Object invoke(Method method, Object... args) throws Exception {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Mp4Exception) throw (Mp4Exception) e.getCause();
            throw e;
        }
    }

    private static long[] offsets(boolean wide, long[] chunks, int[] stsc, int[] sizes,
                                  long fileLen, int declaredChunks) throws Exception {
        int chunkBytes = 8 + chunks.length * (wide ? 8 : 4);
        ByteBuffer b = ByteBuffer.allocate(chunkBytes + 8 + stsc.length * 4);
        b.putInt(0).putInt(declaredChunks);
        for (long chunk : chunks) {
            if (wide) b.putLong(chunk); else b.putInt((int) chunk);
        }
        b.putInt(0).putInt(stsc.length / 3);
        for (int v : stsc) b.putInt(v);
        Object info = CONSTRUCTOR.newInstance();
        String box = wide ? "co64" : "stco";
        field(info, box, 0);
        field(info, box + "End", chunkBytes);
        field(info, "stsc", chunkBytes);
        field(info, "stscEnd", b.capacity());
        return (long[]) invoke(OFFSETS, b.array(), info, sizes, fileLen, "video ");
    }

    private static int[] cts(int version, int n, int... runs) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(8 + runs.length * 4);
        b.putInt(version << 24).putInt(runs.length / 2);
        for (int v : runs) b.putInt(v);
        Object info = CONSTRUCTOR.newInstance();
        field(info, "ctts", 0);
        field(info, "cttsEnd", b.capacity());
        return (int[]) invoke(CTS, b.array(), info, n, "video ");
    }

    @Test
    public void unsignedAndWideChunkOffsetsWithMultipleSamples() throws Exception {
        int[] sizes = {5, 7, 11, 13, 17};
        int[] stsc = {1, 2, 1, 3, 1, 1};
        long[] chunks = {0xfffffff0L, 100, 200};
        assertArrayEquals(new long[]{0xfffffff0L, 0xfffffff5L, 100, 111, 200},
                offsets(false, chunks, stsc, sizes, 0x100000000L, chunks.length));
        chunks[0] = 0x200000000L;
        assertArrayEquals(new long[]{0x200000000L, 0x200000005L, 100, 111, 200},
                offsets(true, chunks, stsc, sizes, 0x300000000L, chunks.length));
    }

    @Test
    public void zeroAndMixedCompositionRuns() throws Exception {
        for (int version = 0; version <= 1; version++) {
            assertNull(cts(version, 5, 2, 0, 0, 99, 3, 0));
            assertArrayEquals(new int[]{0, 0, -7, -7, 0, 9, 0},
                    cts(version, 7, 2, 0, 2, -7, 1, 0, 1, 9, 1, 0));
        }
    }

    @Test
    public void randomizedTablesMatchExpandedReference() throws Exception {
        Random random = new Random(3717);
        for (int trial = 0; trial < 2000; trial++) {
            boolean wide = random.nextBoolean();
            int count = 1 + random.nextInt(50);
            long[] chunks = new long[count];
            int[] stsc = new int[count * 3];
            int[] sizes = new int[count * 5];
            long[] expected = new long[sizes.length];
            int n = 0;
            for (int i = 0; i < count; i++) {
                long start = (wide ? 0x100000000L : 0) + random.nextInt(100000);
                chunks[i] = start;
                int spc = 1 + random.nextInt(5);
                stsc[i * 3] = i + 1;
                stsc[i * 3 + 1] = spc;
                stsc[i * 3 + 2] = 1;
                for (int j = 0; j < spc; j++) {
                    expected[n] = start;
                    sizes[n] = random.nextInt(100);
                    start += sizes[n++];
                }
            }
            assertArrayEquals(Arrays.copyOf(expected, n), offsets(wide, chunks, stsc,
                    Arrays.copyOf(sizes, n), 0x200000000L, count));
            int[] runs = new int[n * 2];
            int[] expanded = new int[n];
            boolean nonzero = false;
            for (int i = 0; i < n; i++) {
                runs[2 * i] = 1;
                runs[2 * i + 1] = expanded[i] = trial % 3 == 0 ? 0 : random.nextInt(7) - 3;
                nonzero |= expanded[i] != 0;
            }
            int[] actual = cts(trial & 1, n, runs);
            if (nonzero) assertArrayEquals(expanded, actual); else assertNull(actual);
        }
    }

    private interface Checked { void run() throws Exception; }
    private static void rejects(String message, Checked checked) throws Exception {
        try {
            checked.run();
            fail("Expected rejection: " + message);
        } catch (Mp4Exception e) {
            assertEquals(message, e.getMessage());
        }
    }

    @Test
    public void malformedTablesStillRejected() throws Exception {
        int[] sizes = {2, 3};
        int[] valid = {1, 1, 1};
        rejects("video chunk table truncated", () -> offsets(false, new long[]{1}, valid, sizes, 100, 2));
        rejects("video more chunks than samples", () -> offsets(false, new long[]{1, 2, 3}, valid, sizes, 100, 3));
        rejects("video chunk offset invalid", () -> offsets(true, new long[]{1, Long.MIN_VALUE}, new int[0], sizes, 100, 2));
        rejects("video stsc does not start at chunk 1", () -> offsets(false, new long[]{1, 2}, new int[]{2, 1, 1}, sizes, 100, 2));
        rejects("video sample beyond end of file", () -> offsets(false, new long[]{1, 99}, valid, sizes, 100, 2));
        rejects("video chunks hold 1 of 2 samples", () -> offsets(false, new long[]{1}, valid, sizes, 100, 1));
        rejects("video ctts overruns sample count", () -> cts(0, 2, 3, 0));
        rejects("video ctts covers 1 of 2 samples", () -> cts(1, 2, 1, 0));
        rejects("video ctts version 2", () -> cts(2, 2, 2, 0));
    }
}
