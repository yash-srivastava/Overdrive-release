package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public class TelemetryStoreProjectionTest {
    @Test
    public void projectsSameRowsAsListReaderAcrossMembers() throws Exception {
        Path file = Files.createTempFile("telemetry-projection", ".jsonl.gz");
        try {
            append(file, "\n{\"t\":1,\"s\":40,\"la\":0,\"lo\":151.2,\"so\":77.123}\n"
                    + "{\"t\":2,\"la\":-33.8,\"lo\":0,\"ss\":1,\"nl\":true,\"gt\":1234}\n"
                    + "malformed row\n{\"t\":3,\"la\":10}");
            append(file, "\n{\"t\":4,\"lo\":10}\n{\"la\":0,\"lo\":0}\n"
                    + "{\"la\":null,\"lo\":12}\n{\"la\":\"invalid\",\"lo\":12}\n"
                    + "{\"la\":\"NaN\",\"lo\":12}\n{\"la\":91,\"lo\":12}\n"
                    + "{\"la\":12,\"lo\":181}\n{\"la\":\"-90\",\"lo\":\"180\",\"ha\":3,\"dl\":2}\n"
                    + "{\"t\":5,\"la\":0,\"lo\":151.2,\"va\":3,\"am\":true,\"pu\":true,\"sp\":5,\"sz\":true}");
            assertMatchesList(file.toFile());
            assertEquals(12, TelemetryStore.readJsonTelemetry(file.toFile()).length());
            JSONArray gps = TelemetryStore.readGpsTrace(file.toFile());
            assertEquals(4, gps.length());
            assertEquals(0.0, gps.getJSONArray(0).getDouble(0), 0.0);
            assertEquals(0.0, gps.getJSONArray(1).getDouble(1), 0.0);
            assertEquals(-90.0, gps.getJSONArray(2).getDouble(0), 0.0);
            assertEquals(151.2, gps.getJSONArray(3).getDouble(1), 0.0);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void emptyMissingAndDamagedTailMatchListReader() throws Exception {
        Path file = Files.createTempFile("telemetry-projection-damaged", ".jsonl.gz");
        try {
            assertMatchesList(null);
            assertMatchesList(file.toFile());
            append(file, "{\"la\":1,\"lo\":2}\n");
            Files.write(file, new byte[]{0x1f, (byte) 0x8b, 0x08}, StandardOpenOption.APPEND);
            assertMatchesList(file.toFile());
            assertEquals(1, TelemetryStore.readGpsTrace(file.toFile()).length());
        } finally {
            Files.deleteIfExists(file);
        }
        assertMatchesList(file.toFile());
    }

    @Test
    public void longJournalKeepsEveryPointInOrder() throws Exception {
        Path file = Files.createTempFile("telemetry-projection-long", ".jsonl.gz");
        try {
            for (int member = 0; member < 60; member++) {
                StringBuilder rows = new StringBuilder();
                for (int i = 0; i < 60; i++) {
                    int t = member * 60 + i;
                    rows.append("{\"t\":").append(t).append(",\"la\":46,\"lo\":")
                            .append(7 + t / 100000.0).append("}\n");
                }
                append(file, rows.toString());
            }
            assertMatchesList(file.toFile());
            assertEquals(3600, TelemetryStore.readGpsTrace(file.toFile()).length());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void assertMatchesList(File file) throws Exception {
        List<TelemetrySample> samples = TelemetryStore.readFromFile(file);
        JSONArray telemetry = new JSONArray();
        JSONArray gps = new JSONArray();
        for (TelemetrySample sample : samples) {
            telemetry.put(sample.toJson());
            if (TelemetrySample.hasGpsFix(sample.lat, sample.lon)) {
                gps.put(new JSONArray().put(sample.lat).put(sample.lon));
            }
        }
        assertEquals(telemetry.toString(), TelemetryStore.readJsonTelemetry(file).toString());
        assertEquals(gps.toString(), TelemetryStore.readGpsTrace(file).toString());
    }

    private static void append(Path file, String content) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file, StandardOpenOption.APPEND))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
