package com.overdrive.app.server.timeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Playback plan: at every wall instant the best-ranked clip plays; an earlier clip of the same
 * camera family ending within 1.5 s after a better one starts plays to its end (seam trim).
 */
final class TimelinePlan {

    static final long SEAM_MS = 1500L;

    // Small overlap sets are cheaper to scan than to maintain two indexes.
    private static final int INDEX_THRESHOLD = 32;

    private TimelinePlan() {}

    private static final class Cand {
        final TimelineData.ClipRef clip;
        final int rank;
        final long from;
        final long to;
        final int order;

        Cand(TimelineData.ClipRef clip, int rank, long from, long to, int order) {
            this.clip = clip;
            this.rank = rank;
            this.from = from;
            this.to = to;
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

    private static boolean better(Cand a, Cand b) {
        if (a.rank != b.rank) return a.rank < b.rank;
        if (a.clip.start != b.clip.start) return a.clip.start < b.clip.start;
        return a.clip.id != null && (b.clip.id == null || a.clip.id.compareTo(b.clip.id) < 0);
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
            cands.add(new Cand(clip, rank(clip, prefer), a, b, cands.size()));
        }
        if (cands.isEmpty()) return out;
        Collections.sort(cands, Comparator.comparingLong(c -> c.from));
        long[] points = new long[cands.size() * 2];
        for (int i = 0; i < cands.size(); i++) {
            points[2 * i] = cands.get(i).from;
            points[2 * i + 1] = cands.get(i).to;
        }
        Arrays.sort(points);

        ActiveClips active = new ActiveClips();
        int next = 0;
        Cand current = null;
        long currentFrom = 0L;
        long currentTo = 0L;
        long a = points[0];
        for (int i = 1; i < points.length; i++) {
            long b = points[i];
            if (b == a) continue;
            active.expire(a);
            while (next < cands.size() && cands.get(next).from <= a) active.add(cands.get(next++));
            Cand winner = active.winner();
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

    /** Stable priority order, including the original input order for otherwise equal clips. */
    private static int comparePriority(Cand a, Cand b) {
        if (better(a, b)) return -1;
        if (better(b, a)) return 1;
        return Integer.compare(a.order, b.order);
    }

    private static boolean isSeam(Cand c, Cand best) {
        return c != best && c.clip.start < best.clip.start && c.clip.end > best.clip.start
                && c.clip.end - best.clip.start <= SEAM_MS
                && oem(c.clip) == oem(best.clip);
    }

    /** Use the original scan for small overlaps; index once when overlap becomes dense. */
    private static final class ActiveClips {
        private final List<Cand> small = new ArrayList<>();
        private TreeSet<Cand> priority;
        private TreeMap<Long, List<Cand>> byEnd;

        void add(Cand c) {
            if (priority == null) {
                small.add(c);
                if (small.size() < INDEX_THRESHOLD) return;
                priority = new TreeSet<>(TimelinePlan::comparePriority);
                byEnd = new TreeMap<>();
                for (Cand item : small) addIndexed(item);
                small.clear();
            } else {
                addIndexed(c);
            }
        }

        private void addIndexed(Cand c) {
            priority.add(c);
            // Use the actual clip end, not the clipped window end: the seam rule
            // compares the original media timestamps. Expiry is equivalent because
            // the sweep never selects a clip at or after the requested window end.
            byEnd.computeIfAbsent(c.clip.end, ignored -> new ArrayList<>()).add(c);
        }

        void expire(long at) {
            if (priority == null) {
                int kept = 0;
                for (int i = 0; i < small.size(); i++) {
                    Cand c = small.get(i);
                    if (c.to > at) small.set(kept++, c);
                }
                for (int i = small.size() - 1; i >= kept; i--) small.remove(i);
                return;
            }
            while (!byEnd.isEmpty() && byEnd.firstKey() <= at) {
                for (Cand c : byEnd.pollFirstEntry().getValue()) priority.remove(c);
            }
        }

        Cand winner() {
            if (priority == null) return scanWinner(small);
            if (priority.isEmpty()) return null;
            Cand best = priority.first();
            // For negative starts, preserve the original signed-long subtraction
            // behavior even on extreme timestamps where the difference overflows.
            if (best.clip.start < 0) {
                for (Cand c : priority) if (isSeam(c, best)) return c;
                return best;
            }
            long lastEnd = best.clip.start > Long.MAX_VALUE - SEAM_MS
                    ? Long.MAX_VALUE : best.clip.start + SEAM_MS;
            Cand seam = null;
            for (Map.Entry<Long, List<Cand>> entry :
                    byEnd.subMap(best.clip.start, false, lastEnd, true).entrySet()) {
                for (Cand c : entry.getValue()) {
                    if (isSeam(c, best) && (seam == null || comparePriority(c, seam) < 0)) seam = c;
                }
            }
            return seam != null ? seam : best;
        }
    }

    private static Cand scanWinner(List<Cand> active) {
        Cand best = null;
        for (int i = 0; i < active.size(); i++) {
            Cand c = active.get(i);
            if (best == null || better(c, best)) best = c;
        }
        if (best == null) return null;
        Cand seam = null;
        for (int i = 0; i < active.size(); i++) {
            Cand c = active.get(i);
            if (isSeam(c, best) && (seam == null || better(c, seam))) seam = c;
        }
        return seam != null ? seam : best;
    }

    private static TimelineData.PlanItem item(Cand c, long from, long to) {
        return new TimelineData.PlanItem(c.clip, from, to);
    }
}
