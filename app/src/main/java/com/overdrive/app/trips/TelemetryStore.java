package com.overdrive.app.trips;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * Utility class for reading/writing gzipped JSON-lines telemetry files.
 *
 * Used by TripTelemetryRecorder (chunked writes) and Trip API (reads).
 *
 * The recorder appends multiple gzip streams to the same file (one per flush).
 * Reads concatenated gzip streams incrementally, without staging all expanded JSON.
 */
public class TelemetryStore {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TelemetryStore");

    private TelemetryStore() {
        // Static utility class
    }

    /**
     * Read a gzipped JSON-lines telemetry file, handling concatenated gzip streams.
     *
     * The recorder appends a new gzip stream on each flush (every 60s or on stop),
     * so a single file may contain multiple concatenated gzip streams. We read them
     * up to the length of the opened file, decoding and parsing each line as it is inflated.
     *
     * @param file the .jsonl.gz file to read
     * @return list of TelemetrySample objects parsed from all gzip streams
     */
    public static List<TelemetrySample> readFromFile(File file) {
        if (file == null || !RemovableStorageFs.exists(file) || RemovableStorageFs.length(file) == 0) {
            return new ArrayList<>();
        }

        try (FileInputStream input = RemovableStorageFs.openInput(file)) {
            // Capture the descriptor length once, so a live append cannot extend the read.
            long length = input.getChannel().size();
            List<TelemetrySample> samples = new ArrayList<>();
            GzipJsonLines.read(input, length, line -> appendSample(samples, line));
            return samples;
        } catch (Exception e) {
            logger.error("Failed to read telemetry file: " + file.getName(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Write a list of samples as a single gzipped JSON-lines file.
     * Used for testing/utility — the recorder uses chunked writes instead.
     *
     * @param file    the output .jsonl.gz file
     * @param samples the telemetry samples to write
     */
    public static void writeToFile(File file, List<TelemetrySample> samples) throws IOException {
        if (file == null) throw new IOException("Output file is null");
        if (samples == null || samples.isEmpty()) {
            throw new IOException("No samples to write");
        }

        String jsonLines = serializeToJsonLines(samples);
        byte[] jsonBytes = jsonLines.getBytes("UTF-8");

        try (OutputStream fos = new BufferedOutputStream(RemovableStorageFs.openOutput(file, false));
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
            gzos.write(jsonBytes);
        }

        logger.info("Wrote " + samples.size() + " samples to " + file.getName());
    }

    /**
     * Convert a list of TelemetrySample objects to a JSON-lines string.
     * Each sample is serialized as a single JSON object on its own line using compact keys.
     *
     * @param samples the telemetry samples
     * @return JSON-lines string (one JSON object per line, no trailing newline on last line)
     */
    public static String serializeToJsonLines(List<TelemetrySample> samples) {
        if (samples == null || samples.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < samples.size(); i++) {
            sb.append(samples.get(i).toJson().toString());
            if (i < samples.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Parse a JSON-lines string back into a list of TelemetrySample objects.
     * Each non-empty line is expected to be a valid JSON object with compact keys.
     *
     * @param jsonLines the JSON-lines string
     * @return list of parsed TelemetrySample objects
     */
    public static List<TelemetrySample> parseJsonLines(String jsonLines) {
        List<TelemetrySample> samples = new ArrayList<>();
        if (jsonLines == null || jsonLines.trim().isEmpty()) return samples;

        String[] lines = jsonLines.split("\n");
        for (String line : lines) {
            appendSample(samples, line);
        }
        return samples;
    }

    private static void appendSample(List<TelemetrySample> samples, String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return;
        try {
            JSONObject json = new JSONObject(trimmed);
            samples.add(TelemetrySample.fromJson(json));
        } catch (Exception e) {
            logger.warn("Skipping malformed telemetry line: " + e.getMessage());
        }
    }
}
