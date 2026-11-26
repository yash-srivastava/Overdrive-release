package com.overdrive.app.server.timeline;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TimelinePlanSweepTest {
    private static TimelineData.ClipRef clip(String id, String type, String layout, long start, Long end) {
        return new TimelineData.ClipRef(id, type, null, layout, start, end, 0, 0, true);
    }

    @Test
    public void denseOverlapsAndClippedSeams() {
        List<TimelineData.ClipRef> clips = new ArrayList<>();
        for (int i = 0; i < 100; i++) clips.add(clip("background" + i, "replay", null, i, 20_000L + i));
        TimelineData.ClipRef first = clip("tie", "normal", null, 1000, 4000L);
        clips.add(first);
        clips.add(clip("tie", "normal", null, 1000, 5000L));
        clips.add(clip(null, "normal", null, 1000, 6000L));
        clips.add(clip("preferred", "sentry", null, 3000, 15_000L));
        clips.add(clip("oem", "normal", "single", 3100, 12_000L));
        for (String prefer : Arrays.asList(null, "sentry", "oemDashcam", "normal")) {
            compare(clips, 500, 3500, prefer);
            compare(clips, 500, 25_000, prefer);
            compare(clips, 3500, 7000, prefer);
        }
        assertSame(first, TimelinePlan.build(clips, 1000, 1500, null).get(0).clip);
    }

    @Test
    public void extremeTimestampsAndOverflow() {
        List<TimelineData.ClipRef> clips = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            clips.add(clip("long" + i, "replay", null, Long.MIN_VALUE + i, Long.MAX_VALUE - i));
        }
        clips.add(clip("negative", "normal", null, -9000, 1000L));
        clips.add(clip("late", "normal", null, Long.MAX_VALUE - 500, Long.MAX_VALUE));
        compare(clips, -10_000, 10_000, null);
        compare(clips, Long.MAX_VALUE - 1000, Long.MAX_VALUE, null);
    }

    @Test
    public void randomizedSparseAndDensePlans() {
        Random random = new Random(3917);
        String[] types = {"normal", "sentry", "proximity", "oemDashcam", "replay", null};
        String[] preferences = {null, "normal", "sentry", "oemDashcam"};
        for (int trial = 0; trial < 1200; trial++) {
            List<TimelineData.ClipRef> clips = new ArrayList<>();
            int count = random.nextInt(180);
            boolean dense = trial % 2 == 0;
            for (int i = 0; i < count; i++) {
                long start = random.nextInt(dense ? 3000 : 100_000) - (trial % 7 == 0 ? 2000 : 0);
                Long end = random.nextInt(30) == 0 ? null : start + random.nextInt(dense ? 10_000 : 4000) - 50;
                TimelineData.ClipRef c = clip(random.nextInt(10) == 0 ? null : "id" + random.nextInt(30),
                        types[random.nextInt(types.length)], random.nextInt(5) == 0 ? "single" : null, start, end);
                c.available = random.nextInt(12) != 0;
                clips.add(c);
                if (i % 37 == 0) clips.add(null);
            }
            Collections.shuffle(clips, random);
            long from = random.nextInt(3000) - 1000;
            compare(clips, from, from + 1000 + random.nextInt(dense ? 12_000 : 100_000),
                    preferences[random.nextInt(preferences.length)]);
        }
    }

    private static void compare(List<TimelineData.ClipRef> clips, long from, long to, String prefer) {
        List<TimelineData.PlanItem> expected = reference(clips, from, to, prefer);
        List<TimelineData.PlanItem> actual = TimelinePlan.build(clips, from, to, prefer);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i).clip, actual.get(i).clip);
            assertEquals(expected.get(i).playFromWall, actual.get(i).playFromWall);
            assertEquals(expected.get(i).playToWall, actual.get(i).playToWall);
        }
    }

    // Deliberately rescan all candidates at each boundary, independently of the active indexes.
    private static List<TimelineData.PlanItem> reference(List<TimelineData.ClipRef> clips,
                                                        long from, long to, String prefer) {
        List<TimelineData.ClipRef> candidates = new ArrayList<>();
        TreeSet<Long> boundaries = new TreeSet<>();
        for (TimelineData.ClipRef c : clips) {
            if (c == null || !c.available || c.end == null || c.end <= c.start) continue;
            long a = Math.max(from, c.start), b = Math.min(to, c.end);
            if (a >= b) continue;
            candidates.add(c);
            boundaries.add(a);
            boundaries.add(b);
        }
        candidates.sort(Comparator.comparingLong(c -> Math.max(from, c.start)));
        List<TimelineData.PlanItem> out = new ArrayList<>();
        Long previous = null;
        for (long b : boundaries) {
            if (previous == null) { previous = b; continue; }
            long a = previous;
            previous = b;
            TimelineData.ClipRef best = null, seam = null;
            for (TimelineData.ClipRef c : candidates) {
                if (c.start <= a && c.end > a && (best == null || better(c, best, prefer))) best = c;
            }
            if (best == null) continue;
            for (TimelineData.ClipRef c : candidates) {
                if (c.start <= a && c.end > a && c.start < best.start && c.end > best.start
                        && c.end - best.start <= TimelinePlan.SEAM_MS
                        && TimelinePlan.oem(c) == TimelinePlan.oem(best)
                        && (seam == null || better(c, seam, prefer))) seam = c;
            }
            TimelineData.ClipRef winner = seam == null ? best : seam;
            TimelineData.PlanItem last = out.isEmpty() ? null : out.get(out.size() - 1);
            // Random fixtures use distinct ClipRef instances even when IDs are equal.
            if (last != null && last.clip == winner && last.playToWall == a) last.playToWall = b;
            else out.add(new TimelineData.PlanItem(winner, a, b));
        }
        return TimelinePlan.dropSlivers(out);
    }

    private static boolean better(TimelineData.ClipRef a, TimelineData.ClipRef b, String prefer) {
        int ra = TimelinePlan.rank(a, prefer), rb = TimelinePlan.rank(b, prefer);
        if (ra != rb) return ra < rb;
        if (a.start != b.start) return a.start < b.start;
        return a.id != null && (b.id == null || a.id.compareTo(b.id) < 0);
    }
}
