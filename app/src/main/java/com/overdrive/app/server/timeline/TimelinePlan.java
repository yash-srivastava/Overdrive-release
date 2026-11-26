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

    private TimelinePlan() {}

    private static final class Cand {
        final TimelineData.ClipRef clip;
        final int rank;
        final long from;
        final long to;
        final int order;
        final boolean oem;

        Cand(TimelineData.ClipRef clip, int rank, long from, long to, int order) {
            this.clip = clip;
            this.rank = rank;
            this.from = from;
            this.to = to;
            this.order = order;
            this.oem = oem(clip);
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

    // Equal-priority candidates used to win in stable input order. Keep them
    // distinct in the set even when their IDs (including null IDs) match.
    private static final Comparator<Cand> PRIORITY = (a, b) -> {
        int cmp = Integer.compare(a.rank, b.rank);
        if (cmp == 0) cmp = Long.compare(a.clip.start, b.clip.start);
        if (cmp == 0) {
            String ai = a.clip.id, bi = b.clip.id;
            cmp = ai == null ? (bi == null ? 0 : 1) : (bi == null ? -1 : ai.compareTo(bi));
        }
        return cmp != 0 ? cmp : Integer.compare(a.order, b.order);
    };

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

        // Ordinary non-overlapping recordings need neither boundary sorting
        // nor active indexes. Adjacent clips and gaps retain their own slices.
        boolean disjoint = true;
        long lastEnd = cands.get(0).from;
        for (Cand c : cands) {
            if (c.from < lastEnd) {
                disjoint = false;
                break;
            }
            lastEnd = c.to;
        }
        if (disjoint) {
            for (Cand c : cands) out.add(item(c, c.from, c.to));
            return dropSlivers(out);
        }

        // Primitive boundaries avoid one boxed Long and tree node per endpoint.
        long[] points = new long[cands.size() * 2];
        for (int i = 0; i < cands.size(); i++) {
            points[2 * i] = cands.get(i).from;
            points[2 * i + 1] = cands.get(i).to;
        }
        Arrays.sort(points);

        TreeSet<Cand> active = new TreeSet<>(PRIORITY);
        // Use original (not window-clipped) ends for the seam rule. Expiry is
        // equivalent at every interior boundary, since all a < window end.
        TreeMap<Long, List<Cand>> byEnd = new TreeMap<>();
        int next = 0;
        Cand current = null;
        long currentFrom = 0L;
        long currentTo = 0L;
        long a = points[0];
        for (int i = 1; i < points.length; i++) {
            long b = points[i];
            if (a == b) continue;
            while (!byEnd.isEmpty() && byEnd.firstKey() <= a) {
                for (Cand expired : byEnd.pollFirstEntry().getValue()) active.remove(expired);
            }
            while (next < cands.size() && cands.get(next).from <= a) {
                Cand c = cands.get(next++);
                if (c.to <= a) continue;
                active.add(c);
                byEnd.computeIfAbsent(c.clip.end, ignored -> new ArrayList<>(1)).add(c);
            }
            Cand winner = winner(active, byEnd);
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

    private static Cand winner(TreeSet<Cand> active, TreeMap<Long, List<Cand>> byEnd) {
        if (active.isEmpty()) return null;
        Cand best = active.first();
        if (active.size() == 1) return best;
        long start = best.clip.start;
        long limit = start > Long.MAX_VALUE - SEAM_MS ? Long.MAX_VALUE : start + SEAM_MS;
        // Preserve the original subtraction even for pathological signed-long
        // timestamps that overflow. Real wall timestamps take the bounded path.
        boolean mayOverflow = start < 0 && byEnd.lastKey() > Long.MAX_VALUE + start;
        if (!mayOverflow && byEnd.firstKey() > limit) return best;
        Cand seam = null;
        // Only ends in (start, start + SEAM_MS] can be eligible. All active
        // clips outside this narrow interval need no per-clip checks.
        for (Map.Entry<Long, List<Cand>> entry : byEnd.tailMap(start, false).entrySet()) {
            if (!mayOverflow && entry.getKey() > limit) break;
            if (entry.getKey() - start > SEAM_MS) continue;
            for (Cand c : entry.getValue()) {
                if (c.clip.start < start && c.oem == best.oem
                        && (seam == null || PRIORITY.compare(c, seam) < 0)) {
                    seam = c;
                }
            }
        }
        return seam != null ? seam : best;
    }

    private static TimelineData.PlanItem item(Cand c, long from, long to) {
        return new TimelineData.PlanItem(c.clip, from, to);
    }
}
