package com.overdrive.app.server.timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Playback plan: at every wall instant the best-ranked clip plays; an earlier clip of the same
 * camera family ending within 1.5 s after a better one starts plays to its end (seam trim).
 */
final class TimelinePlan {

    static final long SEAM_MS = 1500L;

    private TimelinePlan() {}

    private static final class Cand {
        final TimelineData.ClipRef clip;
        final int rank;
        final long from;
        final int order;

        Cand(TimelineData.ClipRef clip, int rank, long from, int order) {
            this.clip = clip;
            this.rank = rank;
            this.from = from;
            this.order = order;
        }
    }

    /** OEM single-camera footage is one family whatever list type the index gives it. */
    static String family(TimelineData.ClipRef clip) {
        if ("oemDashcam".equals(clip.type) || "single".equals(clip.layout)) return "oemDashcam";
        return clip.type;
    }

    static boolean oem(TimelineData.ClipRef clip) {
        return "oemDashcam".equals(family(clip));
    }

    static int rank(TimelineData.ClipRef clip, String prefer) {
        String family = family(clip);
        if (prefer != null && prefer.equals(family)) return -1;
        if ("normal".equals(family)) return 0;
        if ("sentry".equals(family)) return 1;
        if ("proximity".equals(family)) return 2;
        if ("oemDashcam".equals(family)) return 3;
        if ("replay".equals(family)) return 4;
        return 5;
    }

    // The original active list breaks exact priority ties by insertion order.
    private static int comparePriority(Cand a, Cand b) {
        int cmp = Integer.compare(a.rank, b.rank);
        if (cmp == 0) cmp = Long.compare(a.clip.start, b.clip.start);
        if (cmp == 0 && a.clip.id != b.clip.id) {
            cmp = a.clip.id == null ? 1 : b.clip.id == null ? -1 : a.clip.id.compareTo(b.clip.id);
        }
        return cmp != 0 ? cmp : Integer.compare(a.order, b.order);
    }

    static List<TimelineData.PlanItem> build(List<TimelineData.ClipRef> clips, long from, long to,
                                             String prefer) {
        List<TimelineData.PlanItem> out = new ArrayList<>();
        if (clips == null || to <= from) return out;
        List<Cand> cands = new ArrayList<>();
        for (TimelineData.ClipRef clip : clips) {
            if (clip == null || !clip.available || clip.end == null || clip.end <= clip.start) continue;
            long a = Math.max(clip.start, from);
            long b = Math.min(clip.end, to);
            if (b <= a) continue;
            cands.add(new Cand(clip, rank(clip, prefer), a, cands.size()));
        }
        if (cands.isEmpty()) return out;
        Collections.sort(cands, Comparator.comparingLong(c -> c.from));

        // Sweep the two sorted boundary lists rather than boxing every boundary
        // into a separate set. Original ends also serve as the seam search index.
        List<Cand> ends = new ArrayList<>(cands);
        Collections.sort(ends, Comparator.comparingLong(c -> c.clip.end));
        TreeSet<Cand> active = new TreeSet<>(TimelinePlan::comparePriority);
        int next = 0;
        int expired = 0;
        Cand current = null;
        long currentFrom = 0L;
        long currentTo = 0L;
        long a = cands.get(0).from;
        while (a < to) {
            while (next < cands.size() && cands.get(next).from <= a) {
                active.add(cands.get(next++));
            }
            while (expired < ends.size() && ends.get(expired).clip.end <= a) {
                active.remove(ends.get(expired++));
            }
            if (expired == ends.size()) break;
            long b = Math.min(to, ends.get(expired).clip.end);
            if (next < cands.size()) b = Math.min(b, cands.get(next).from);
            Cand winner = winner(active, ends, expired);
            if (winner == null) {
                if (current != null) out.add(item(current, currentFrom, currentTo));
                current = null;
            } else if (winner == current && currentTo == a) {
                currentTo = b;
            } else {
                if (current != null) out.add(item(current, currentFrom, currentTo));
                current = winner;
                currentFrom = a;
                currentTo = b;
            }
            a = b;
        }
        if (current != null) out.add(item(current, currentFrom, currentTo));
        return dropSlivers(out);
    }

    /** A piece under SEAM_MS from the other camera family, with neighbours within SEAM_MS on both sides, is a flicker: drop it. */
    static List<TimelineData.PlanItem> dropSlivers(List<TimelineData.PlanItem> in) {
        if (in.size() < 3) return in;
        List<TimelineData.PlanItem> out = new ArrayList<>(in.size());
        for (int i = 0; i < in.size(); i++) {
            TimelineData.PlanItem p = in.get(i);
            if (i > 0 && i < in.size() - 1 && p.playToWall - p.playFromWall < SEAM_MS) {
                TimelineData.PlanItem before = out.isEmpty() ? null : out.get(out.size() - 1);
                TimelineData.PlanItem after = in.get(i + 1);
                if (before != null && p.playFromWall - before.playToWall <= SEAM_MS
                        && after.playFromWall - p.playToWall <= SEAM_MS
                        && oem(p.clip) != oem(before.clip) && oem(p.clip) != oem(after.clip)) {
                    continue;
                }
            }
            out.add(p);
        }
        return out;
    }

    private static Cand winner(TreeSet<Cand> active, List<Cand> ends, int expired) {
        if (active.isEmpty()) return null;
        Cand best = active.first();
        if (active.size() == 1) return best;
        long start = best.clip.start;
        Cand seam = null;
        for (int i = expired; i < ends.size(); i++) {
            Cand c = ends.get(i);
            // Remaining ends are after the current boundary, hence after best.start.
            // For nonnegative starts, later ends cannot qualify once this limit is exceeded.
            // Negative starts retain the original signed-subtraction behavior.
            if (start >= 0 && c.clip.end - start > SEAM_MS) break;
            // A clip starting before best is already active if its end has not expired.
            if (c.clip.start < start && c.clip.end > start
                    && c.clip.end - start <= SEAM_MS
                    && oem(c.clip) == oem(best.clip)
                    && (seam == null || comparePriority(c, seam) < 0)) {
                seam = c;
            }
        }
        return seam != null ? seam : best;
    }

    private static TimelineData.PlanItem item(Cand c, long from, long to) {
        return new TimelineData.PlanItem(c.clip, from, to);
    }
}
