package com.overdrive.app.geo;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class GeoBackfillSweepTest {
    private static final long HOUR = 60 * 60 * 1000L;
    private static final long NOW = 10 * HOUR;

    private static GeoBackfillSweep.RecentSidecar entry(String name, long mtime) {
        return new GeoBackfillSweep.RecentSidecar(new File(name), mtime, "recording");
    }

    private static JSONObject geo() {
        return new JSONObject().put("lat", 47.0).put("lng", 8.0);
    }

    private static JSONObject root(JSONObject geo) {
        return new JSONObject().put("geo", geo);
    }

    @Test
    public void backlogReadsOnlyEightNewestEvenWhenAllLookupsMiss() {
        List<GeoBackfillSweep.RecentSidecar> entries = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) entries.add(entry(i + ".json", NOW - 10_000 + i));
        List<String> reads = new ArrayList<>();
        List<String> attempts = new ArrayList<>();
        int merged = GeoBackfillSweep.scanRecent(entries, NOW, f -> {
            reads.add(f.getName());
            return root(geo());
        }, (e, ll) -> {
            attempts.add(e.sidecar.getName());
            return false;
        });
        assertEquals(0, merged);
        assertEquals(Arrays.asList("9999.json", "9998.json", "9997.json", "9996.json",
                "9995.json", "9994.json", "9993.json", "9992.json"), attempts);
        assertEquals(attempts, reads);
    }

    @Test
    public void skipsIneligibleFilesWithoutSpendingBudgetAndKeepsStableTieOrder() {
        List<GeoBackfillSweep.RecentSidecar> entries = new ArrayList<>();
        Map<File, JSONObject> roots = new HashMap<>();
        String[] names = {"first", "second", "tagged", "no-fix", "cooldown", "expired", "invalid", "nested", "missing"};
        JSONObject[] data = {root(geo()), root(geo()), root(geo().put("place", new JSONObject())),
                new JSONObject(), root(geo().put("placeUnresolvedAtMs", NOW - 1)),
                root(geo().put("placeUnresolvedAtMs", NOW - 7 * HOUR)),
                root(new JSONObject().put("lat", 0).put("lng", 0)),
                root(new JSONObject().put("peak", geo()).put("placeUnresolvedAtMs", NOW - 2 * HOUR)), null};
        for (int i = 0; i < names.length; i++) {
            GeoBackfillSweep.RecentSidecar e = entry(names[i], NOW - (i < 2 ? 2 : 1));
            entries.add(e);
            roots.put(e.sidecar, data[i]);
        }
        List<String> attempts = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        int merged = GeoBackfillSweep.scanRecent(entries, NOW, f -> {
            reads.incrementAndGet();
            return roots.get(f);
        }, (e, ll) -> {
            attempts.add(e.sidecar.getName() + ":" + ll[0] + "," + ll[1]);
            return true;
        });
        assertEquals(9, reads.get());
        assertEquals(3, merged);
        assertEquals(Arrays.asList("nested:47.0,8.0", "first:47.0,8.0", "second:47.0,8.0"), attempts);
    }

    @Test
    public void interruptedSweepDoesNotOpenAnotherSidecar() {
        List<GeoBackfillSweep.RecentSidecar> entries = new ArrayList<>(Arrays.asList(
                entry("one", NOW), entry("two", NOW - 1)));
        AtomicInteger reads = new AtomicInteger();
        try {
            int merged = GeoBackfillSweep.scanRecent(entries, NOW, f -> {
                reads.incrementAndGet();
                return root(geo());
            }, (e, ll) -> {
                Thread.currentThread().interrupt();
                return false;
            });
            assertEquals(0, merged);
            assertEquals(1, reads.get());
        } finally {
            Thread.interrupted();
        }
    }
}
