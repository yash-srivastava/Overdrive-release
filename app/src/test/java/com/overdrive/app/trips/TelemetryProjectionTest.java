package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public class TelemetryProjectionTest {
    @Test
    public void projectionsMatchLegacyResponsesAcrossMembers() throws Exception {
        String first = "\n{\"t\":1,\"la\":0,\"lo\":151.2,\"so\":42.123,\"gt\":100}\r\n"
                + "malformed row\n{\"t\":2,\"la\":-33.8,\"lo\":0,\"nl\":true,\"ss\":1}\n"
                + "{\"t\":3,\"la\":91,\"lo\":1}\n{\"t\":4,\"lo\":10}\n"
                + "{\"t\":5,\"la\":null,\"lo\":1}\n{\"t\":6,\"la\":0,\"lo\":0}\n";
        String second = "{\"t\":7,\"la\":\"-33.8\",\"lo\":\"151.2\",\"sp\":20,\"ha\":4.5,\"dl\":3,\"va\":2,\"am\":true}\n"
                + "{\"t\":8,\"la\":\"Infinity\",\"lo\":1}\n"
                + "{\"t\":9,\"la\":90,\"lo\":-180,\"pu\":true,\"sz\":true,\"s\":13,\"extra\":\"ignored\"}";
        byte[] data = members(first, second.substring(0, 23), second.substring(23));
        Path journal = Files.createTempFile("telemetry-projection", ".jsonl.gz");
        try {
            Files.write(journal, data);
            List<TelemetrySample> expected = TelemetryStore.parseJsonLines(first + second);
            assertEquals(9, expected.size());
            assertProjections(journal, expected);
            assertEquals(4, TelemetryStore.readGpsTrace(journal.toFile()).length());
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    @Test
    public void damagedTailRetainsExactlyTheLegacyInflatedPrefix() throws Exception {
        byte[] data = members("{\"t\":1,\"la\":10,\"lo\":20}\n",
                "{\"t\":2,\"la\":11,\"lo\":21}\n{\"t\":3,\"la\":12,\"lo\":22}\n");
        Path journal = Files.createTempFile("telemetry-projection-tail", ".jsonl.gz");
        try {
            for (int n = 0; n <= data.length; n++) {
                Files.write(journal, Arrays.copyOf(data, n));
                assertProjections(journal, TelemetryStore.readFromFile(journal.toFile()));
            }
            data[data.length - 8] ^= 1; // Damage the last member's CRC.
            Files.write(journal, data);
            assertProjections(journal, TelemetryStore.readFromFile(journal.toFile()));
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    @Test
    public void missingAndEmptyFilesReturnEmptyArrays() throws Exception {
        Path journal = Files.createTempFile("telemetry-projection-empty", ".jsonl.gz");
        try {
            assertEquals(0, TelemetryStore.readTelemetryJson(null).length());
            assertEquals(0, TelemetryStore.readGpsTrace(null).length());
            assertEquals(0, TelemetryStore.readTelemetryJson(journal.toFile()).length());
            assertEquals(0, TelemetryStore.readGpsTrace(journal.toFile()).length());
            Files.delete(journal);
            assertEquals(0, TelemetryStore.readTelemetryJson(journal.toFile()).length());
            assertEquals(0, TelemetryStore.readGpsTrace(journal.toFile()).length());
        } finally {
            Files.deleteIfExists(journal);
        }
    }

    private static void assertProjections(Path journal, List<TelemetrySample> samples) throws Exception {
        JSONArray telemetry = new JSONArray();
        JSONArray gps = new JSONArray();
        for (TelemetrySample sample : samples) {
            telemetry.put(sample.toJson());
            if (TelemetrySample.hasGpsFix(sample.lat, sample.lon)) {
                gps.put(new JSONArray().put(sample.lat).put(sample.lon));
            }
        }
        assertEquals(telemetry.toString(), TelemetryStore.readTelemetryJson(journal.toFile()).toString());
        assertEquals(gps.toString(), TelemetryStore.readGpsTrace(journal.toFile()).toString());
    }

    private static byte[] members(String... parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String part : parts) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(part.getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toByteArray();
    }
}
