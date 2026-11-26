package com.overdrive.app.trips;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

public class TripFlushTest {
    @Test
    public void orderedBucketsPreserveTiesGapsAndLandedDistance() {
        TelemetrySample first = sample(1200, 10, 0f);
        TelemetrySample tie = sample(1200, 20, 0f);
        TelemetrySample closest = sample(3001, 30, 1.25f);
        List<TelemetrySample> rows = Arrays.asList(first, tie, sample(1900, 40, 0f),
                sample(3900, 50, 0.75f), closest, sample(3999, 60, 2f));
        List<TelemetrySample> result = TripTelemetryRecorder.downsampleTo1Hz(rows);
        assertEquals(2, result.size());
        assertSame(first, result.get(0));
        assertEquals(closest.withDistanceLandedM(4f).toJson().toString(),
                result.get(1).toJson().toString());
        assertMatchesReference(rows);
    }

    @Test
    public void backwardsClockAndExtremeTimestampsMatchReference() {
        List<TelemetrySample> rows = Arrays.asList(sample(3200, 10, 1f),
                sample(4100, 20, 2f), sample(3000, 30, 4f), sample(-999, 40, 8f),
                sample(Long.MIN_VALUE, 50, 16f), sample(Long.MAX_VALUE, 60, 32f),
                sample(-1001, 70, 64f), sample(0, 80, 128f));
        assertMatchesReference(rows);
        List<TelemetrySample> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> Long.compare(a.timestampMs, b.timestampMs));
        assertMatchesReference(sorted);
        assertTrue(TripTelemetryRecorder.downsampleTo1Hz(null).isEmpty());
        assertTrue(TripTelemetryRecorder.downsampleTo1Hz(Collections.emptyList()).isEmpty());
    }

    @Test
    public void randomizedOrderedAndShuffledRowsMatchReference() {
        Random random = new Random(0x0d2026L);
        for (int trial = 0; trial < 100; trial++) {
            List<TelemetrySample> rows = new ArrayList<>();
            long base = 1_700_000_000_000L;
            for (int i = 0; i < 300; i++) {
                rows.add(sample(base + random.nextInt(90_000), i % 100,
                        random.nextFloat() * 50f));
            }
            rows.sort((a, b) -> Long.compare(a.timestampMs / 1000, b.timestampMs / 1000));
            assertMatchesReference(rows);
            Collections.shuffle(rows, random);
            assertMatchesReference(rows);
        }
    }

    @Test
    public void gzipMembersPreserveExactJournalLinesAndAppendOrder() throws IOException {
        List<TelemetrySample> rows = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            rows.add(sample(1_700_000_000_000L + i * 1000L, i % 100, i / 7f));
        }
        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        StringBuilder expected = new StringBuilder();
        for (List<TelemetrySample> part : Arrays.asList(rows.subList(0, 17),
                rows.subList(17, 800), Collections.<TelemetrySample>emptyList(),
                rows.subList(800, rows.size()))) {
            ByteArrayOutputStream member = GzipJsonLines.encodeMember(part);
            member.writeTo(combined);
            StringBuilder memberText = new StringBuilder();
            for (TelemetrySample row : part) memberText.append(row.toJson().toString()).append('\n');
            expected.append(memberText);
            ByteArrayOutputStream expanded = new ByteArrayOutputStream();
            try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(
                    new ByteArrayInputStream(member.toByteArray()))) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = gzip.read(chunk)) != -1) expanded.write(chunk, 0, count);
            }
            assertArrayEquals(memberText.toString().getBytes(StandardCharsets.UTF_8),
                    expanded.toByteArray());
        }
        byte[] compressed = combined.toByteArray();
        List<String> decoded = new ArrayList<>();
        GzipJsonLines.read(new ByteArrayInputStream(compressed), compressed.length, decoded::add);
        assertEquals(Arrays.asList(expected.toString().split("\n")), decoded);
    }

    private static TelemetrySample sample(long time, int speed, float landed) {
        return new TelemetrySample(time, speed, 12, 5, false, 4, 47.3, 8.5,
                400.25, 3.5, true, TelemetrySample.SPEED_GPS, true, false, false,
                speed, 2.5f, landed, 1_700_000_000_000L, 75.25);
    }

    private static void assertMatchesReference(List<TelemetrySample> rows) {
        Map<Long, List<TelemetrySample>> groups = new TreeMap<>();
        for (TelemetrySample row : rows) {
            long boundary = (row.timestampMs / 1000) * 1000;
            groups.computeIfAbsent(boundary, ignored -> new ArrayList<>()).add(row);
        }
        List<TelemetrySample> actual = TripTelemetryRecorder.downsampleTo1Hz(rows);
        assertEquals(groups.size(), actual.size());
        int i = 0;
        for (Map.Entry<Long, List<TelemetrySample>> group : groups.entrySet()) {
            TelemetrySample closest = null;
            long minDistance = Long.MAX_VALUE;
            float landed = 0f;
            for (TelemetrySample row : group.getValue()) {
                long distance = Math.abs(row.timestampMs - group.getKey());
                if (distance < minDistance) {
                    minDistance = distance;
                    closest = row;
                }
                landed += row.distanceLandedM;
            }
            TelemetrySample found = actual.get(i++);
            if (landed == closest.distanceLandedM) assertSame(closest, found);
            else assertEquals(closest.withDistanceLandedM(landed).toJson().toString(),
                    found.toJson().toString());
        }
    }
}
