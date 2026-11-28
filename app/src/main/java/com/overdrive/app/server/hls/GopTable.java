package com.overdrive.app.server.hls;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** GOP timing of one clip (16 bytes per GOP): everything a playlist needs, without sample tables. */
final class GopTable {
    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_GOPS = 262_144;
    private static final LinkedHashMap<String, GopTable> CACHE = new LinkedHashMap<>(256, 0.75f, true);
    private static long cachedGops;

    /** Presentation start of GOP g in microseconds; us[count] = video end. */
    final long[] us;
    /** Output decode time of GOP g's first sample in track ticks; ticks[count] = track end. */
    final long[] ticks;
    final long timescale;
    final boolean audio;

    GopTable(long[] us, long[] ticks, long timescale, boolean audio) {
        this.us = us;
        this.ticks = ticks;
        this.timescale = timescale;
        this.audio = audio;
    }

    int count() {
        return us.length - 1;
    }

    long startMs(int n) {
        return msRound(us[n]);
    }

    /** EXTINF in ms from rounded boundaries, so the sum never drifts from the media timeline. */
    long segmentMs(int n, int k) {
        if (k == 0) return msRound(us[n + 1]) - msRound(us[n]);
        return msRound(Mp4Index.ticksToUs(skimTicks(n + 1, k), timescale))
                - msRound(Mp4Index.ticksToUs(skimTicks(n, k), timescale));
    }

    /** Skim timeline: GOP g starts at floor(ticks[g] / k), so durations sum without drift. */
    long skimTicks(int g, int k) {
        return Math.floorDiv(ticks[g], (long) k);
    }

    static long msRound(long us) {
        return Math.floorDiv(us + 500, 1000L);
    }

    static GopTable cached(String key) {
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    /** Bounded by entry count and total GOPs; the newest entry is never evicted. */
    static void put(String key, GopTable t) {
        synchronized (CACHE) {
            GopTable old = CACHE.put(key, t);
            if (old != null) cachedGops -= old.count();
            cachedGops += t.count();
            Iterator<Map.Entry<String, GopTable>> it = CACHE.entrySet().iterator();
            while ((CACHE.size() > MAX_ENTRIES || cachedGops > MAX_GOPS) && it.hasNext()) {
                Map.Entry<String, GopTable> e = it.next();
                if (e.getValue() == t) break;
                cachedGops -= e.getValue().count();
                it.remove();
            }
        }
    }
}
