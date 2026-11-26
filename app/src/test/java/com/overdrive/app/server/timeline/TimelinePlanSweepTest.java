package com.overdrive.app.server.timeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import org.junit.Test;

public class TimelinePlanSweepTest {
    private static TimelineData.ClipRef clip(String id, String type, String layout, long start, Long end) {
        return new TimelineData.ClipRef(id, type, null, layout, start, end, 0, 0, true);
    }

    @Test
    public void seamUsesOriginalEndAndKeepsExactPriorityTies() {
        TimelineData.ClipRef earlier = clip("earlier", "sentry", null, 1000, 3500L);
        TimelineData.ClipRef best = clip("best", "normal", null, 2000, 6000L);
        TimelineData.ClipRef tie = clip("best", "normal", null, 2000, 7000L);
        List<TimelineData.ClipRef> clips = Arrays.asList(tie, earlier, best);
        List<TimelineData.PlanItem> plan = TimelinePlan.build(clips, 2500, 5000, null);
        assertEquals(2, plan.size());
        assertSame(earlier, plan.get(0).clip);
        assertEquals(2500L, plan.get(0).playFromWall);
        assertEquals(3500L, plan.get(0).playToWall);
        assertSame(tie, plan.get(1).clip);
        assertEquals(5000L, plan.get(1).playToWall);

        earlier.end = 3501L; // Outside the seam, even if clipped inside it.
        plan = TimelinePlan.build(clips, 2500, 3000, null);
        assertEquals(1, plan.size());
        assertSame(tie, plan.get(0).clip);
    }

    @Test
    public void denseOverlapPreservesEveryWinnerTransition() {
        List<TimelineData.ClipRef> clips = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            clips.add(clip(null, "normal", null, 10_000L + i, 100_000L + i));
        }
        List<TimelineData.PlanItem> plan = TimelinePlan.build(clips, 0, 200_000, null);
        assertEquals(10_000, plan.size());
        for (int i = 0; i < clips.size(); i++) {
            assertSame(clips.get(i), plan.get(i).clip);
            assertEquals(i == 0 ? 10_000L : 99_999L + i, plan.get(i).playFromWall);
            assertEquals(100_000L + i, plan.get(i).playToWall);
        }
    }

    @Test
    public void matchesFullScanAcrossSeededWindows() {
        Random random = new Random(92401);
        String[] types = {"normal", "sentry", "proximity", "replay", "oemDashcam", null, "other"};
        String[] ids = {null, "a", "b", "a", "c"};
        for (int run = 0; run < 1500; run++) {
            List<TimelineData.ClipRef> clips = new ArrayList<>();
            long offset = run % 5 == 0 ? -10_000 : 0;
            for (int i = 0; i < 40; i++) {
                long start = offset + random.nextInt(30) * 500L;
                Long end = random.nextInt(10) == 0 ? null : start + (random.nextInt(20) - 1) * 500L;
                TimelineData.ClipRef c = clip(ids[random.nextInt(ids.length)],
                        types[random.nextInt(types.length)], random.nextInt(5) == 0 ? "single" : null,
                        start, end);
                c.available = random.nextInt(10) != 0;
                clips.add(c);
                if (random.nextInt(10) == 0) clips.add(null);
                if (random.nextInt(10) == 0) clips.add(c);
            }
            long from = offset + random.nextInt(30) * 500L;
            long to = from + random.nextInt(40) * 500L;
            String prefer = types[random.nextInt(types.length)];
            assertPlan(reference(clips, from, to, prefer), TimelinePlan.build(clips, from, to, prefer));
        }
    }

    @Test
    public void extremeNegativeStartPreservesSignedSeamArithmetic() {
        List<TimelineData.ClipRef> clips = Arrays.asList(
                clip("a", "sentry", null, Long.MIN_VALUE, Long.MAX_VALUE),
                clip("b", "normal", null, Long.MIN_VALUE + 10, Long.MAX_VALUE - 1),
                clip("c", "normal", null, Long.MAX_VALUE - 10, Long.MAX_VALUE));
        assertPlan(reference(clips, Long.MIN_VALUE + 20, Long.MAX_VALUE, null),
                TimelinePlan.build(clips, Long.MIN_VALUE + 20, Long.MAX_VALUE, null));
    }

    private static void assertPlan(List<TimelineData.PlanItem> expected, List<TimelineData.PlanItem> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i).clip, actual.get(i).clip);
            assertEquals(expected.get(i).playFromWall, actual.get(i).playFromWall);
            assertEquals(expected.get(i).playToWall, actual.get(i).playToWall);
        }
    }

    // Deliberately simple reference: rescan all clips at every boundary. The stable
    // input order resolves equal priorities; the original sliver pass is unchanged.
    private static List<TimelineData.PlanItem> reference(List<TimelineData.ClipRef> clips,
                                                         long from, long to, String prefer) {
        List<TimelineData.PlanItem> result = new ArrayList<>();
        TreeSet<Long> points = new TreeSet<>();
        List<TimelineData.ClipRef> valid = new ArrayList<>();
        for (TimelineData.ClipRef c : clips) {
            if (c == null || !c.available || c.end == null) continue;
            long a = Math.max(c.start, from), b = Math.min(c.end, to);
            if (b <= a) continue;
            valid.add(c);
            points.add(a);
            points.add(b);
        }
        Long a = null;
        TimelineData.PlanItem current = null;
        for (long b : points) {
            if (a != null) {
                TimelineData.ClipRef best = null, seam = null;
                for (TimelineData.ClipRef c : valid) {
                    if (c.start <= a && c.end > a && better(c, best, prefer)) best = c;
                }
                if (best != null) {
                    for (TimelineData.ClipRef c : valid) {
                        if (c.start < best.start && c.end > a && c.end > best.start
                                && c.end - best.start <= TimelinePlan.SEAM_MS
                                && TimelinePlan.oem(c) == TimelinePlan.oem(best)
                                && better(c, seam, prefer)) seam = c;
                    }
                }
                TimelineData.ClipRef winner = seam != null ? seam : best;
                if (winner == null) current = null;
                else if (current != null && current.clip == winner && current.playToWall == a) {
                    current.playToWall = b;
                } else {
                    current = new TimelineData.PlanItem(winner, a, b);
                    result.add(current);
                }
            }
            a = b;
        }
        return TimelinePlan.dropSlivers(result);
    }

    private static boolean better(TimelineData.ClipRef a, TimelineData.ClipRef b, String prefer) {
        if (b == null) return true;
        int ra = TimelinePlan.rank(a, prefer), rb = TimelinePlan.rank(b, prefer);
        if (ra != rb) return ra < rb;
        if (a.start != b.start) return a.start < b.start;
        return a.id != null && (b.id == null || a.id.compareTo(b.id) < 0);
    }
}
