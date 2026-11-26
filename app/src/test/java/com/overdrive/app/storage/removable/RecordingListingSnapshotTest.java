package com.overdrive.app.storage.removable;

import static org.junit.Assert.*;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class RecordingListingSnapshotTest {
    @Test public void baselineAndTransientEmptyListingEmitNothing() {
        RecordingListingSnapshot first = new RecordingListingSnapshot();
        first.add("a.mp4", 100, 200, null);
        assertTrue(first.dispatchChanges(null, name -> fail(name), name -> fail(name)));
        RecordingListingSnapshot empty = new RecordingListingSnapshot();
        empty.add("a.json", 5, 10, first);
        assertFalse(empty.dispatchChanges(first, name -> fail(name), name -> fail(name)));
    }

    @Test public void detectsRawMetadataChangesAndOnlyMissingMp4s() {
        RecordingListingSnapshot first = new RecordingListingSnapshot();
        first.add("a.mp4", 100, 200, null);
        first.add("b.mp4", 100, 200, null);
        first.add("c.mp4", 100, 200, null);
        first.add("a.json", 3, 20, null);
        first.add("b.json", 3, 20, null);
        RecordingListingSnapshot next = new RecordingListingSnapshot();
        next.add("a.mp4", 101, 200, first);
        next.add("b.mp4", 100, 201, first);
        next.add("a.json", 3, 21, first);
        next.add("new.mp4", 0, 0, first);
        next.add("ignored.MP4", 5, 5, first);
        next.add("temp.tmp", 5, 5, first);
        Set<String> refresh = new HashSet<>(), missing = new HashSet<>();
        assertTrue(next.dispatchChanges(first, refresh::add, missing::add));
        assertEquals(new HashSet<>(java.util.Arrays.asList("a.mp4", "b.mp4", "a.json", "new.mp4")), refresh);
        assertEquals(java.util.Collections.singleton("c.mp4"), missing);
    }

    @Test public void stableLargeDirectoryReusesEveryStamp() throws Exception {
        RecordingListingSnapshot first = new RecordingListingSnapshot();
        RecordingListingSnapshot next = new RecordingListingSnapshot();
        for (int i = 0; i < 10_000; i++) first.add(i + ".mp4", i, 1000L * i, null);
        for (int i = 0; i < 10_000; i++) next.add(i + ".mp4", i, 1000L * i, first);
        Field field = RecordingListingSnapshot.class.getDeclaredField("entries");
        field.setAccessible(true);
        Map<?, ?> old = (Map<?, ?>) field.get(first), now = (Map<?, ?>) field.get(next);
        assertEquals(10_000, now.size());
        for (Map.Entry<?, ?> e : now.entrySet()) assertSame(old.get(e.getKey()), e.getValue());
        assertTrue(next.dispatchChanges(first, name -> fail(name), name -> fail(name)));
        RecordingListingSnapshot changed = new RecordingListingSnapshot();
        changed.add("0.mp4", 1, 0, next);
        assertNotSame(now.get("0.mp4"), ((Map<?, ?>) field.get(changed)).get("0.mp4"));
    }

    @Test public void matchesOriginalStringSignatureDiffAcrossRandomListings() {
        Random random = new Random(8371);
        RecordingListingSnapshot previous = null;
        Map<String, String> old = null;
        for (int round = 0; round < 1000; round++) {
            RecordingListingSnapshot next = new RecordingListingSnapshot();
            Map<String, String> current = new HashMap<>();
            for (int i = 0; i < 60; i++) {
                if (random.nextBoolean()) continue;
                String name = (i % 12) + (i % 3 == 0 ? ".mp4" : i % 3 == 1 ? ".json" : ".tmp");
                if (round % 19 == 0 && name.endsWith(".mp4")) continue;
                long length = random.nextInt(5), time = random.nextInt(5) * 1000L;
                next.add(name, length, time, previous);
                if (name.endsWith(".mp4") || name.endsWith(".json")) current.put(name, length + ":" + time);
            }
            boolean accept = old == null || old.keySet().stream().noneMatch(n -> n.endsWith(".mp4"))
                    || current.keySet().stream().anyMatch(n -> n.endsWith(".mp4"));
            Set<String> expectedRefresh = new HashSet<>(), expectedMissing = new HashSet<>();
            if (accept && old != null) {
                for (String name : current.keySet()) {
                    if (!current.get(name).equals(old.get(name))) expectedRefresh.add(name);
                }
                for (String name : old.keySet()) {
                    if (name.endsWith(".mp4") && !current.containsKey(name)) expectedMissing.add(name);
                }
            }
            Set<String> refresh = new HashSet<>(), missing = new HashSet<>();
            assertEquals(accept, next.dispatchChanges(previous, refresh::add, missing::add));
            assertEquals(expectedRefresh, refresh);
            assertEquals(expectedMissing, missing);
            if (accept) {
                previous = next;
                old = current;
            }
        }
    }
}
