package com.overdrive.app.server.hls;

import java.util.Arrays;

/**
 * Back-to-back AAC playback timeline. All entries have one duration, so their
 * timestamps are implicit. Store only contiguous real-sample and silence runs.
 */
final class ClipAudioTimeline {
    final long f;
    final int n;
    private final long first;
    private final int[] ends;
    private final int[] samples;

    private ClipAudioTimeline(long f, long first, RunBuilder runs) {
        this.f = f;
        this.first = first;
        n = runs.entries;
        ends = Arrays.copyOf(runs.ends, runs.count);
        samples = Arrays.copyOf(runs.samples, runs.count);
    }

    long startAt(int entry) {
        return first + (long) entry * f;
    }

    int runAt(int entry) {
        int lo = 0;
        int hi = ends.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ends[mid] <= entry) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    int runEnd(int run) {
        return ends[run];
    }

    int sampleAt(int entry, int run) {
        int sample = samples[run];
        return sample < 0 ? -1 : sample + (entry - (run == 0 ? 0 : ends[run - 1]));
    }

    int firstAtOrAfter(long t) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (startAt(mid) < t) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /**
     * Keep the original silence rules: a stretched first sample, late start,
     * persistent timestamp holes, and early end. Null means no repair needed.
     * time includes the end boundary after the final sample.
     */
    static ClipAudioTimeline build(long[] time, long em, long f, long clipStart, long clipEnd) {
        int count = time.length - 1;
        long half = f / 2;
        boolean filled = false;
        long s0 = time[0] - em;
        if (count > 1 && time[1] - time[0] > f + half) {
            s0 = time[1] - em - f;
            filled = true;
        }
        RunBuilder runs = new RunBuilder();
        long first = s0;
        if (s0 - clipStart >= f && s0 <= clipEnd) {
            long gap = (s0 - clipStart) / f;
            first = s0 - gap * f;
            runs.add(-1, gap);
            filled = true;
        }
        long cursor = s0;
        int realStart = 0;
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                long lag = time[i] - em - cursor;
                long nextLag = i + 1 < count ? time[i + 1] - em - (cursor + f) : lag;
                if (lag >= half && nextLag >= half) {
                    long room = Math.max(0L, (clipEnd - cursor) / f);
                    long gap = Math.min((Math.min(lag, nextLag) + half) / f, room);
                    if (gap > 0) {
                        runs.add(realStart, i - realStart);
                        runs.add(-1, gap);
                        realStart = i;
                        cursor += gap * f;
                    }
                    // Even a hole clipped to zero frames used the repair path before.
                    filled = true;
                }
            }
            cursor += f;
        }
        if (clipEnd - cursor >= f) {
            runs.add(realStart, count - realStart);
            runs.add(-1, (clipEnd - cursor) / f);
            realStart = count;
            filled = true;
        }
        if (!filled) return null;
        runs.add(realStart, count - realStart);
        return new ClipAudioTimeline(f, first, runs);
    }

    private static final class RunBuilder {
        int[] ends = new int[8];
        int[] samples = new int[8];
        int count;
        int entries;

        void add(int sample, long length) {
            if (length == 0) return;
            entries = Math.toIntExact(entries + length);
            if (count == ends.length) {
                ends = Arrays.copyOf(ends, count * 2);
                samples = Arrays.copyOf(samples, count * 2);
            }
            ends[count] = entries;
            samples[count++] = sample;
        }
    }
}
