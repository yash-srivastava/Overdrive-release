package com.overdrive.app.server.timeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import org.junit.Test;

public class TimelinePlanIndexedSweepTest {
    private static TimelineData.ClipRef clip(String id, String type, String layout,
                                             long start, Long end, boolean available) {
        return new TimelineData.ClipRef(id, type, "", layout, start, end, 0, 0, available);
    }

    private static TimelineData.ClipRef clip(String id, String type, long start, long end) {
        return clip(id, type, null, start, end, true);
    }

    // Deliberately scan all clips per boundary, in the old stable active order.
    // Do not use the indexed sweep's comparator, end index or boundary array.
    private static List<TimelineData.PlanItem> reference(List<TimelineData.ClipRef> clips,
                                                         long from, long to, String prefer) {
        List<TimelineData.PlanItem> out = new ArrayList<>();
        if (clips == null || to <= from) return out;
        List<TimelineData.ClipRef> valid = new ArrayList<>();
        TreeSet<Long> points = new TreeSet<>();
        for (TimelineData.ClipRef c : clips) {
            if (c == null || !c.available || c.end == null || c.end <= c.start) continue;
            long a = Math.max(from, c.start), b = Math.min(to, c.end);
            if (b <= a) continue;
            valid.add(c);
            points.add(a);
            points.add(b);
        }
        valid.sort(Comparator.comparingLong(c -> Math.max(from, c.start)));
        Long a = null;
        int previousWinner = -1;
        for (long b : points) {
            if (a == null) {
                a = b;
                continue;
            }
            int best = -1;
            for (int i = 0; i < valid.size(); i++) {
                TimelineData.ClipRef c = valid.get(i);
                if (c.start <= a && c.end > a
                        && (best < 0 || better(c, valid.get(best), prefer))) best = i;
            }
            int seam = -1;
            if (best >= 0) {
                TimelineData.ClipRef winner = valid.get(best);
                for (int i = 0; i < valid.size(); i++) {
                    TimelineData.ClipRef c = valid.get(i);
                    if (c.start <= a && c.end > a && c.start < winner.start
                            && c.end > winner.start && c.end - winner.start <= TimelinePlan.SEAM_MS
                            && TimelinePlan.oem(c) == TimelinePlan.oem(winner)
                            && (seam < 0 || better(c, valid.get(seam), prefer))) seam = i;
                }
            }
            int w = seam >= 0 ? seam : best;
            if (w >= 0) {
                TimelineData.PlanItem last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (w == previousWinner && last != null && last.playToWall == a) {
                    last.playToWall = b;
                } else {
                    out.add(new TimelineData.PlanItem(valid.get(w), a, b));
                }
            }
            previousWinner = w;
            a = b;
        }
        return TimelinePlan.dropSlivers(out);
    }

    private static boolean better(TimelineData.ClipRef a, TimelineData.ClipRef b, String prefer) {
        int ar = TimelinePlan.rank(a, prefer), br = TimelinePlan.rank(b, prefer);
        if (ar != br) return ar < br;
        if (a.start != b.start) return a.start < b.start;
        return a.id != null && (b.id == null || a.id.compareTo(b.id) < 0);
    }

    private static void assertPlan(List<TimelineData.ClipRef> clips, long from, long to, String prefer) {
        List<TimelineData.PlanItem> expected = reference(clips, from, to, prefer);
        List<TimelineData.PlanItem> actual = TimelinePlan.build(clips, from, to, prefer);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i).clip, actual.get(i).clip);
            assertEquals(expected.get(i).playFromWall, actual.get(i).playFromWall);
            assertEquals(expected.get(i).playToWall, actual.get(i).playToWall);
        }
    }

    @Test public void disjointClipsKeepGapsAndSliverRule() {
        List<TimelineData.ClipRef> clips = Arrays.asList(
                clip("b", "normal", 5000, 8000), clip("a", "normal", 0, 3000),
                clip("short", "oemDashcam", 3500, 4500), null,
                clip("unknown", "normal", null, 0, null, true),
                clip("offline", "normal", null, 0, 9000L, false));
        assertPlan(clips, 1000, 7500, null);
        assertPlan(clips, 3000, 3500, null);
        assertPlan(clips, 5000, 5000, null);
        assertPlan(null, 0, 10000, null);
    }

    @Test public void seamUsesUnclippedEndAndInclusiveLimit() {
        TimelineData.ClipRef old = clip("old", "sentry", 0, 3500);
        TimelineData.ClipRef normal = clip("new", "normal", 2000, 10000);
        List<TimelineData.ClipRef> clips = Arrays.asList(old, normal);
        assertPlan(clips, 2100, 9000, null);
        assertSame(old, TimelinePlan.build(clips, 2100, 9000, null).get(0).clip);
        old.end = 3501L;
        assertPlan(clips, 2100, 3000, null);
        assertSame(normal, TimelinePlan.build(clips, 2100, 3000, null).get(0).clip);
        old.layout = "single";
        old.end = 3500L;
        assertPlan(clips, 0, 10000, null);
        assertPlan(clips, 0, 10000, "oemDashcam");
    }

    @Test public void equalIdsAndNullIdsRetainStableWinnerAndExpireIndividually() {
        List<TimelineData.ClipRef> clips = Arrays.asList(
                clip(null, "normal", 0, 9000), clip("id", "normal", 0, 3000),
                clip("id", "normal", 0, 6000), clip(null, "normal", 0, 12000));
        assertPlan(clips, 1000, 12000, null);
        List<TimelineData.PlanItem> plan = TimelinePlan.build(clips, 1000, 12000, null);
        assertEquals(4, plan.size());
        assertSame(clips.get(1), plan.get(0).clip);
        assertSame(clips.get(2), plan.get(1).clip);
        assertSame(clips.get(0), plan.get(2).clip);
        assertSame(clips.get(3), plan.get(3).clip);
    }

    @Test public void extremeSignedEndsRetainLegacyArithmetic() {
        assertPlan(Arrays.asList(clip("old", "sentry", Long.MIN_VALUE, 10),
                clip("new", "normal", Long.MIN_VALUE + 100, 20)),
                Long.MIN_VALUE + 100, 20, null);
        assertPlan(Arrays.asList(clip("old", "sentry", Long.MAX_VALUE - 4000, Long.MAX_VALUE),
                clip("new", "normal", Long.MAX_VALUE - 1000, Long.MAX_VALUE)),
                Long.MAX_VALUE - 3000, Long.MAX_VALUE, null);
    }

    @Test public void randomizedIndexedSweepMatchesFullScan() {
        Random random = new Random(81924);
        String[] types = {"normal", "sentry", "proximity", "oemDashcam", "replay", "other", null};
        String[] preferences = {null, "sentry", "normal", "oemDashcam", "replay"};
        for (int trial = 0; trial < 500; trial++) {
            List<TimelineData.ClipRef> clips = new ArrayList<>();
            int count = random.nextInt(80);
            for (int i = 0; i < count; i++) {
                long start = (random.nextInt(60) - 20) * 250L;
                Long end = random.nextInt(12) == 0 ? null : start + (random.nextInt(35) - 2) * 250L;
                String id = random.nextInt(5) == 0 ? null : "id-" + random.nextInt(12);
                clips.add(clip(id, types[random.nextInt(types.length)],
                        random.nextInt(5) == 0 ? "single" : null, start, end, random.nextInt(9) != 0));
            }
            Collections.shuffle(clips, random);
            long from = (random.nextInt(45) - 25) * 250L;
            long to = from + random.nextInt(80) * 250L;
            for (String prefer : preferences) assertPlan(clips, from, to, prefer);
        }
    }

    @Test public void tenThousandNestedClipsKeepSingleWinner() {
        List<TimelineData.ClipRef> clips = new ArrayList<>();
        for (int i = 0; i < 10000; i++) {
            clips.add(clip("nested-" + i, "normal", i * 2000L, 50_000_000L - i * 2000L));
        }
        List<TimelineData.PlanItem> plan = TimelinePlan.build(clips, 0, 50_000_000L, null);
        assertEquals(1, plan.size());
        assertSame(clips.get(0), plan.get(0).clip);
        assertEquals(0L, plan.get(0).playFromWall);
        assertEquals(50_000_000L, plan.get(0).playToWall);
    }
}
