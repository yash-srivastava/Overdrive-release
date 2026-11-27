package com.overdrive.app.server.hls;

import org.junit.Test;
import java.util.Random;
import static org.junit.Assert.assertEquals;

public class Mp4AudioBoundarySearchTest {
    private static Mp4Track audio(long[] ticks, long scale, long edit) {
        int n = ticks.length - 1;
        return new Mp4Track(false, scale, new byte[0], "mp4a.40.2", ticks,
                new int[n], new long[n], null, 0, edit, 0, 0, 0, 0,
                new byte[36], 0, 0, 0, 0, 0);
    }

    private static int linear(Mp4Track a, int from, long us) {
        while (from < a.count && a.toUs(a.time[from] - a.editMediaTime) < us) from++;
        return from;
    }

    @Test public void duplicatesNegativeTimesAndSentinel() {
        Mp4Track a = audio(new long[]{0, 0, 1, 1, 2, 5, 9, 10}, 3_000_000, 2);
        for (int from = 0; from <= a.count; from++) {
            for (long us = -5; us <= 6; us++) {
                assertEquals(linear(a, from, us), Mp4Index.firstAudioAtOrAfter(a, from, us));
            }
        }
        Mp4Track empty = audio(new long[]{0}, 48_000, 0);
        assertEquals(0, Mp4Index.firstAudioAtOrAfter(empty, 0, 10));
    }

    @Test public void randomizedMonotonicTablesAndArbitraryBoundaries() {
        Random random = new Random(742903);
        long[] scales = {1, 1000, 44100, 48000, 90000, 1_000_000, 4_294_967_295L};
        for (int trial = 0; trial < 1000; trial++) {
            int n = 1 + random.nextInt(4096);
            long[] ticks = new long[n + 1];
            ticks[0] = random.nextInt(4000) - 2000;
            for (int j = 1; j <= n; j++) ticks[j] = ticks[j - 1] + random.nextInt(2049);
            Mp4Track a = audio(ticks, scales[trial % scales.length], random.nextInt(8192));
            for (int j = 0; j < 100; j++) {
                int from = random.nextInt(n + 1);
                long us = a.toUs(ticks[random.nextInt(n + 1)] - a.editMediaTime)
                        + random.nextInt(3) - 1;
                assertEquals(linear(a, from, us), Mp4Index.firstAudioAtOrAfter(a, from, us));
            }
        }
    }

    @Test public void sequentialGopsIncludingReversedAndRepeatedBoundaries() {
        int n = 30_000;
        long[] ticks = new long[n + 1];
        for (int i = 0; i <= n; i++) ticks[i] = i * 1024L;
        Mp4Track a = audio(ticks, 48000, 1024);
        for (int period : new int[]{1, 2, 16, 67, 100, 300, 2000, 31000}) {
            int expected = 0, actual = 0;
            for (int i = 0; i < n + period; i += period) {
                long us = a.toUs(i * 1024L);
                for (long boundary : new long[]{us, us, us - 50000}) {
                    expected = linear(a, expected, boundary);
                    actual = Mp4Index.firstAudioAtOrAfter(a, actual, boundary);
                    assertEquals(expected, actual);
                }
            }
            assertEquals(n, actual);
        }
    }
}
