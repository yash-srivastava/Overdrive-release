package com.overdrive.app.trips;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Request-local processing for one telemetry flush, independent of storage and monitors. */
final class TripTelemetryChunk {
    private TripTelemetryChunk() {}

    /** Select the closest row to each whole second, preserving ties and landed distance. */
    static List<TelemetrySample> downsampleTo1Hz(List<TelemetrySample> samples) {
        if (samples == null || samples.isEmpty()) return new ArrayList<>();

        List<TelemetrySample> result = new ArrayList<>();
        TelemetrySample closest = null;
        long boundary = 0;
        long closestDistance = Long.MAX_VALUE;
        float landedMeters = 0f;
        for (TelemetrySample sample : samples) {
            long second = (sample.timestampMs / 1000) * 1000;
            if (closest != null && second < boundary) {
                // A backwards clock step can split a second into multiple runs.
                // Reprocess the original rows, not the partially aggregated output.
                return downsampleUnordered(samples);
            }
            if (closest == null || second != boundary) {
                if (closest != null) appendSecond(result, closest, landedMeters);
                boundary = second;
                closest = null;
                closestDistance = Long.MAX_VALUE;
                landedMeters = 0f;
            }
            long distance = Math.abs(sample.timestampMs - second);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = sample;
            }
            landedMeters += sample.distanceLandedM;
        }
        appendSecond(result, closest, landedMeters);
        return result;
    }

    private static void appendSecond(List<TelemetrySample> result,
                                     TelemetrySample closest, float landedMeters) {
        // Landed distance is an event: retain the sum on the selected row.
        result.add(landedMeters != closest.distanceLandedM
                ? closest.withDistanceLandedM(landedMeters) : closest);
    }

    /** Accumulates a second without allocating a list for every 5Hz sample. */
    private static final class SecondBucket {
        TelemetrySample closest;
        long closestDistance = Long.MAX_VALUE;
        float landedMeters;
    }

    /** Wall-clock corrections can revisit earlier seconds. Preserve encounter order in each bucket. */
    private static List<TelemetrySample> downsampleUnordered(List<TelemetrySample> samples) {
        // Keep the original encounter order for ties and float accumulation.
        Map<Long, SecondBucket> bySecond = new HashMap<>();
        for (TelemetrySample s : samples) {
            long boundary = (s.timestampMs / 1000) * 1000;
            SecondBucket bucket = bySecond.get(boundary);
            if (bucket == null) {
                bucket = new SecondBucket();
                bySecond.put(boundary, bucket);
            }
            long distance = Math.abs(s.timestampMs - boundary);
            if (distance < bucket.closestDistance) {
                bucket.closestDistance = distance;
                bucket.closest = s;
            }
            bucket.landedMeters += s.distanceLandedM;
        }

        List<Long> sortedSeconds = new ArrayList<>(bySecond.keySet());
        java.util.Collections.sort(sortedSeconds);

        List<TelemetrySample> result = new ArrayList<>(sortedSeconds.size());
        for (long boundary : sortedSeconds) {
            SecondBucket bucket = bySecond.get(boundary);
            TelemetrySample closest = bucket.closest;
            if (closest != null) appendSecond(result, closest, bucket.landedMeters);
        }
        return result;
    }

    /** Build a complete compressed member before opening the append target. */
    static ByteArrayOutputStream gzip(List<TelemetrySample> samples) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        // Buffer only a small block of UTF-8 rows rather than the entire uncompressed chunk.
        try (BufferedOutputStream rows = new BufferedOutputStream(
                new GZIPOutputStream(compressed), 4096)) {
            for (TelemetrySample sample : samples) {
                rows.write(sample.toJson().toString().getBytes(StandardCharsets.UTF_8));
                rows.write('\n');
            }
        }
        return compressed;
    }
}
