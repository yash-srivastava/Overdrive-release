package com.overdrive.app.server.hls;

import static org.junit.Assert.assertEquals;

import java.util.Random;
import org.junit.Test;

public class Mp4AudioBoundarySearchTest {
    private static int linear(long[] time, long edit, long scale, int from, long targetUs) {
        while (from < time.length - 1
                && Mp4Index.ticksToUs(time[from] - edit, scale) < targetUs) from++;
        return from;
    }

    @Test public void preservesPrimingDuplicatesRoundingAndForwardCursor() {
        long[] time = {0, 1, 2, 2, 3, 4, 9};
        long scale = 2_000_000;
        long edit = 2;
        for (int from = 0; from < time.length; from++) {
            for (long target = -4; target <= 6; target++) {
                assertEquals(linear(time, edit, scale, from, target),
                        Mp4Index.firstAudioSampleAtOrAfter(time, edit, scale, from, target));
            }
        }
        assertEquals(2, Mp4Index.firstAudioSampleAtOrAfter(time, edit, scale, 0, 0));
        assertEquals(3, Mp4Index.firstAudioSampleAtOrAfter(time, edit, scale, 3, -1));
        assertEquals(6, Mp4Index.firstAudioSampleAtOrAfter(time, edit, scale, 0, 4));
        assertEquals(0, Mp4Index.firstAudioSampleAtOrAfter(new long[]{0}, 0, 1, 0, 1));
    }

    @Test public void randomizedBoundariesMatchOriginalForwardScan() {
        Random r = new Random(71423);
        long[] scales = {1, 44100, 48000, 90000, 2_000_000, 0xffffffffL};
        for (int trial = 0; trial < 1000; trial++) {
            long scale = scales[trial % scales.length];
            int n = 1 + r.nextInt(2000);
            long[] time = new long[n + 1];
            time[0] = r.nextInt(10000);
            for (int i = 1; i <= n; i++) {
                time[i] = time[i - 1] + (r.nextInt(5) == 0 ? 0 : r.nextInt(4096));
            }
            long edit = r.nextInt(20000);
            int cursor = 0;
            for (int g = 0; g < 100; g++) {
                int sample = r.nextInt(n + 1);
                long target = Mp4Index.ticksToUs(time[sample] - edit, scale) + r.nextInt(3) - 1;
                // Arbitrary starting points and even backward video boundaries must never rewind.
                int from = g % 2 == 0 ? cursor : r.nextInt(n + 1);
                int expected = linear(time, edit, scale, from, target);
                cursor = Mp4Index.firstAudioSampleAtOrAfter(time, edit, scale, from, target);
                assertEquals(expected, cursor);
            }
        }
    }

    @Test public void longAacTrackMatchesEveryTwoSecondBoundary() {
        int n = 1_000_000;
        long[] time = new long[n + 1];
        for (int i = 0; i <= n; i++) time[i] = 1024L * i;
        int expected = 0;
        int actual = 0;
        for (long target = 2_000_000; target <= 21_334_000_000L; target += 2_000_000) {
            expected = linear(time, 1024, 48000, expected, target);
            actual = Mp4Index.firstAudioSampleAtOrAfter(time, 1024, 48000, actual, target);
            assertEquals(expected, actual);
        }
        assertEquals(n, actual);
    }
}
