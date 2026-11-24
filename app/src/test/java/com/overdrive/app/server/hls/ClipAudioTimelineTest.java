package com.overdrive.app.server.hls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;

public class ClipAudioTimelineTest {
    private static final long F = 1024;

    @Test
    public void ordinaryTrackNeedsNoRepair() {
        assertNull(ClipAudioTimeline.build(new long[]{0, F, 2 * F, 3 * F}, 0, F, 0, 3 * F));
    }

    @Test
    public void longSilenceIsRepresentedByOneRun() {
        int gap = 500_000;
        ClipAudioTimeline t = ClipAudioTimeline.build(new long[]{gap * F, (gap + 1) * F},
                0, F, 0, (gap + 1) * F);
        assertNotNull(t);
        assertEquals(gap + 1, t.n);
        assertEquals(gap, t.runEnd(0));
        assertEquals(-1, t.sampleAt(gap - 1, 0));
        assertEquals(0, t.sampleAt(gap, 1));
        assertEquals(1, t.runAt(gap));
        assertEquals(gap, t.firstAtOrAfter(gap * F - 1));
        assertEquals(gap + 1, t.firstAtOrAfter(gap * F + 1));
    }

    @Test
    public void boundaryAndRepairCasesMatchExpandedTimeline() {
        compare(new long[]{0, F, 2 * F, 3 * F}, 0, F, 0, 3 * F);
        compare(new long[]{0, 4 * F, 5 * F, 6 * F}, 0, F, 0, 9 * F);
        compare(new long[]{F, 2 * F, 7 * F, 8 * F, 9 * F}, 0, F, 0, 12 * F);
        compare(new long[]{0, F, 2 * F + F / 2, 3 * F, 4 * F}, 0, F, 0, 4 * F);
        compare(new long[]{0, F, 5 * F, 6 * F, 7 * F}, 0, F, 0, 2 * F);
        compare(new long[]{0, F, 2 * F, 3 * F}, 2 * F, F, 0, 5 * F);
        compare(new long[]{5 * F, 6 * F}, 0, F, 0, 2 * F);
        compare(new long[]{0, 2, 4, 6, 7, 10}, 1, 1, 0, 12);
    }

    @Test
    public void randomizedRepairAndGopBoundariesMatchExpandedTimeline() {
        Random random = new Random(90210);
        for (int trial = 0; trial < 3000; trial++) {
            long f = 1 + random.nextInt(2048);
            int count = 1 + random.nextInt(160);
            long[] time = new long[count + 1];
            time[0] = random.nextInt(6) * f;
            for (int i = 1; i <= count; i++) {
                long delta = f;
                if (random.nextInt(5) == 0) delta = random.nextInt(8) * f + random.nextInt((int) f);
                time[i] = time[i - 1] + delta;
            }
            long em = random.nextInt(8) * f;
            long clipStart = random.nextInt(5) * f;
            long clipEnd = Math.max(clipStart, time[count] + (random.nextInt(40) - 20) * f);
            compare(time, em, f, clipStart, clipEnd);
        }
    }

    private static void compare(long[] time, long em, long f, long clipStart, long clipEnd) {
        Expanded expected = expanded(time, em, f, clipStart, clipEnd);
        ClipAudioTimeline actual = ClipAudioTimeline.build(time, em, f, clipStart, clipEnd);
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.samples.size(), actual.n);
        int entry = 0;
        int run = actual.runAt(entry);
        while (entry < actual.n) {
            int end = actual.runEnd(run);
            for (; entry < end; entry++) {
                long start = expected.starts.get(entry);
                assertEquals(start, actual.startAt(entry));
                assertEquals(expected.samples.get(entry).intValue(), actual.sampleAt(entry, run));
                assertEquals(run, actual.runAt(entry));
                assertEquals(entry, actual.firstAtOrAfter(start));
                assertEquals(entry + 1, actual.firstAtOrAfter(start + 1));
            }
            run++;
        }
        assertEquals(0, actual.firstAtOrAfter(Long.MIN_VALUE));
        assertEquals(actual.n, actual.firstAtOrAfter(Long.MAX_VALUE));
    }

    private static final class Expanded {
        final List<Long> starts = new ArrayList<>();
        final List<Integer> samples = new ArrayList<>();

        void add(long t, int sample) {
            starts.add(t);
            samples.add(sample);
        }
    }

    /** Reference: the previous per-frame timeline construction. */
    private static Expanded expanded(long[] time, long em, long f, long clipStart, long clipEnd) {
        int count = time.length - 1;
        long half = f / 2;
        Expanded result = new Expanded();
        boolean filled = false;
        long s0 = time[0] - em;
        if (count > 1 && time[1] - time[0] > f + half) {
            s0 = time[1] - em - f;
            filled = true;
        }
        if (s0 - clipStart >= f && s0 <= clipEnd) {
            for (long k = (s0 - clipStart) / f; k >= 1; k--) result.add(s0 - k * f, -1);
            filled = true;
        }
        long cursor = s0;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                long lag = time[i] - em - cursor;
                long nextLag = i + 1 < count ? time[i + 1] - em - (cursor + f) : lag;
                if (lag >= half && nextLag >= half) {
                    long room = Math.max(0L, (clipEnd - cursor) / f);
                    for (long k = Math.min((Math.min(lag, nextLag) + half) / f, room); k >= 1; k--) {
                        result.add(cursor, -1);
                        cursor += f;
                    }
                    filled = true;
                }
            }
            result.add(cursor, i);
            cursor += f;
        }
        if (clipEnd - cursor >= f) {
            for (long k = (clipEnd - cursor) / f; k >= 1; k--) {
                result.add(cursor, -1);
                cursor += f;
            }
            filled = true;
        }
        return filled ? result : null;
    }
}
