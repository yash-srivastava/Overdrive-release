package com.overdrive.app.trips;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for reading gzipped JSON-lines telemetry journals.
 *
 * TripTelemetryRecorder owns journal writes; the Trip API and analytics read here.
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
