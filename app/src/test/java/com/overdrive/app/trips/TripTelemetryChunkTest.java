package com.overdrive.app.trips;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
import java.util.zip.GZIPInputStream;

import org.junit.Test;

public class TripTelemetryChunkTest {
    private static TelemetrySample row(long time, int id, float landed) {
        return new TelemetrySample(time, id, 10, 0, false, 4, -33.8, 151.2, 20,
                2, true, TelemetrySample.SPEED_GPS, false, false, false, id,
                3f, landed, 1000L + id, 70.25);
    }

    @Test
    public void orderedSecondsKeepFirstTieAndAllLandedDistance() {
        TelemetrySample first = row(1200, 1, 1f);
        TelemetrySample tie = row(1200, 2, 2f);
        TelemetrySample last = row(5001, 3, 0f);
        List<TelemetrySample> actual = TripTelemetryChunk.downsampleTo1Hz(
                Arrays.asList(first, tie, row(1900, 4, 4f), last));
        assertEquals(2, actual.size());
        assertEquals(first.speedKmh, actual.get(0).speedKmh);
        assertEquals(7f, actual.get(0).distanceLandedM, 0f);
        assertEquals(first.socPercent, actual.get(0).socPercent, 0.0);
        assertSame(last, actual.get(1));
        assertTrue(TripTelemetryChunk.downsampleTo1Hz(null).isEmpty());
        assertTrue(TripTelemetryChunk.downsampleTo1Hz(Collections.emptyList()).isEmpty());
    }

    @Test
    public void clockCorrectionsAndSignedTimestampsMatchReference() {
        compare(Arrays.asList(row(2000, 1, 2f), row(-1999, 2, 1f),
                row(2100, 3, 3f), row(-1001, 4, 5f), row(-1, 5, 7f),
                row(1, 6, 1f), row(Long.MIN_VALUE, 7, 0f), row(Long.MAX_VALUE, 8, 0f)));
        Random random = new Random(20261126L);
        for (int trial = 0; trial < 1000; trial++) {
            List<TelemetrySample> rows = new ArrayList<>();
            long time = random.nextInt(4000) - 2000;
            for (int i = 0, n = random.nextInt(300); i < n; i++) {
                time += random.nextInt(500);
                float landed = random.nextBoolean() ? 0f : random.nextFloat() * 100;
                rows.add(row(time, i, landed));
            }
            compare(rows);
            Collections.shuffle(rows, random);
            compare(rows);
        }
    }

    @Test
    public void longOrderedChunkPreservesRowsAndGzipPayload() throws IOException {
        List<TelemetrySample> rows = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) rows.add(row(1_000_000L + i * 200L, i, 0f));
        List<TelemetrySample> result = TripTelemetryChunk.downsampleTo1Hz(rows);
        assertEquals(20_000, result.size());
        for (int i = 0; i < result.size(); i++) assertSame(rows.get(i * 5), result.get(i));
        checkGzip(result);
        checkGzip(Collections.emptyList());
        checkGzip(Collections.singletonList(rows.get(0)));
        // Recorder flushes append standalone gzip members. A reader must see both members.
        ByteArrayOutputStream combined = TripTelemetryChunk.gzip(result.subList(0, 10));
        TripTelemetryChunk.gzip(result.subList(10, 20)).writeTo(combined);
        assertArrayEquals(legacyPayload(result.subList(0, 20)), inflate(combined));
    }

    private static void compare(List<TelemetrySample> rows) {
        List<TelemetrySample> expected = reference(rows);
        List<TelemetrySample> actual = TripTelemetryChunk.downsampleTo1Hz(rows);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) {
            TelemetrySample e = expected.get(i), a = actual.get(i);
            assertEquals(e.timestampMs, a.timestampMs);
            assertEquals(e.speedKmh, a.speedKmh);
            assertEquals(Float.floatToIntBits(e.distanceLandedM), Float.floatToIntBits(a.distanceLandedM));
            assertEquals(e.toJson().toString(), a.toJson().toString());
        }
    }

    // Independent grouping oracle, including Java's original truncation toward zero.
    private static List<TelemetrySample> reference(List<TelemetrySample> rows) {
        Map<Long, List<TelemetrySample>> groups = new TreeMap<>();
        for (TelemetrySample row : rows) {
            groups.computeIfAbsent(row.timestampMs / 1000 * 1000, k -> new ArrayList<>()).add(row);
        }
        List<TelemetrySample> result = new ArrayList<>();
        for (Map.Entry<Long, List<TelemetrySample>> entry : groups.entrySet()) {
            TelemetrySample best = null;
            long distance = Long.MAX_VALUE;
            float sum = 0f;
            for (TelemetrySample sample : entry.getValue()) {
                long delta = Math.abs(sample.timestampMs - entry.getKey());
                if (delta < distance) {
                    best = sample;
                    distance = delta;
                }
                sum += sample.distanceLandedM;
            }
            result.add(sum == best.distanceLandedM ? best : best.withDistanceLandedM(sum));
        }
        return result;
    }

    private static byte[] legacyPayload(List<TelemetrySample> rows) {
        StringBuilder json = new StringBuilder();
        for (TelemetrySample sample : rows) json.append(sample.toJson().toString()).append('\n');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void checkGzip(List<TelemetrySample> rows) throws IOException {
        assertArrayEquals(legacyPayload(rows), inflate(TripTelemetryChunk.gzip(rows)));
    }

    private static byte[] inflate(ByteArrayOutputStream compressed) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()));
             ByteArrayOutputStream raw = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = gzip.read(buffer)) != -1) raw.write(buffer, 0, n);
            return raw.toByteArray();
        }
    }
}
