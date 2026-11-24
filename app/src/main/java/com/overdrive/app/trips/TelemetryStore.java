package com.overdrive.app.trips;

import com.overdrive.app.storage.removable.RemovableStorageFs;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility class for reading/writing gzipped JSON-lines telemetry files.
 *
 * Used by TripTelemetryRecorder (chunked writes) and Trip API (reads).
 *
 * The recorder appends multiple gzip streams to the same file (one per flush).
 * Standard GZIPInputStream only reads the first stream, so readFromFile()
 * handles concatenated gzip streams by reading in a loop until EOF.
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
     * all by looping over the raw bytes and creating a new GZIPInputStream for each stream.
     *
     * @param file the .jsonl.gz file to read
     * @return list of TelemetrySample objects parsed from all gzip streams
     */
    public static List<TelemetrySample> readFromFile(File file) {
        if (file == null || !RemovableStorageFs.exists(file) || RemovableStorageFs.length(file) == 0) {
            return new ArrayList<>();
        }

        try {
            // Read entire file into memory (telemetry files are bounded by storage limits)
            byte[] fileBytes = readAllBytes(file);
            String allJsonLines = decompressConcatenatedGzip(fileBytes);
            return parseJsonLines(allJsonLines);
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
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            try {
                JSONObject json = new JSONObject(trimmed);
                samples.add(TelemetrySample.fromJson(json));
            } catch (Exception e) {
                logger.warn("Skipping malformed telemetry line: " + e.getMessage());
            }
        }
        return samples;
    }

    // ==================== INTERNAL HELPERS ====================

    /**
     * Decompress concatenated gzip streams from raw bytes.
     *
     * The gzip format allows multiple streams to be concatenated. Each stream has
     * its own header (magic bytes 0x1f 0x8b). We scan for these headers and
     * decompress each stream individually, concatenating the results.
     */
    private static String decompressConcatenatedGzip(byte[] data) throws IOException {
        if (data == null || data.length == 0) return "";

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        byte[] buffer = new byte[4096];

        while (bais.available() > 0) {
            // Mark position to detect if GZIPInputStream consumes any bytes
            bais.mark(data.length);

            try {
                GZIPInputStream gzis = new GZIPInputStream(bais);
                int bytesRead;
                while ((bytesRead = gzis.read(buffer)) != -1) {
                    result.write(buffer, 0, bytesRead);
                }
                gzis.close();
                // After close, bais position is advanced past the consumed gzip stream.
                // Loop continues to read the next concatenated stream if any.
            } catch (IOException e) {
                // If we can't read a gzip stream, we've likely hit trailing garbage or EOF
                break;
            }
        }

        return result.toString("UTF-8");
    }

    /**
     * Read all bytes from a file into a byte array.
     */
    private static byte[] readAllBytes(File file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) RemovableStorageFs.length(file));
        try (BufferedInputStream bis = new BufferedInputStream(RemovableStorageFs.openInput(file))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
        }
        return baos.toByteArray();
    }
}
