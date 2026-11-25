package com.overdrive.app.trips;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TripJournalChunkTest {
    private static byte[] legacy(List<String> rows) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String row : rows) text.append(row).append('\n');
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(text.toString().getBytes(StandardCharsets.UTF_8));
        }
        return compressed.toByteArray();
    }

    private static byte[] decode(byte[] compressed) throws IOException {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) decoded.write(buffer, 0, count);
        }
        return decoded.toByteArray();
    }

    private static void compare(List<String> rows) throws IOException {
        assertArrayEquals(decode(legacy(rows)),
                decode(TripJournalChunk.encode(rows, Function.identity()).toByteArray()));
    }

    @Test public void preservesUtf8AndLineBoundaries() throws IOException {
        compare(Collections.emptyList());
        compare(Collections.singletonList("{}"));
        char[] longRow = new char[4095];
        Arrays.fill(longRow, 'x');
        compare(Arrays.asList(new String(longRow) + "\uD83D\uDE97", "\u00e9\u4e2d",
                "\uD800", "\uDC00", "", "{\"t\":123}"));
    }

    @Test public void randomizedChunksMatchOriginal() throws IOException {
        Random random = new Random(29);
        for (int iteration = 0; iteration < 200; iteration++) {
            List<String> rows = new ArrayList<>();
            int count = random.nextInt(200);
            for (int i = 0; i < count; i++) {
                rows.add("{\"t\":" + random.nextLong() + ",\"s\":" + random.nextInt(201)
                        + ",\"la\":" + random.nextDouble() + ",\"bp\":" + random.nextBoolean() + "}");
            }
            compare(rows);
        }
    }

    @Test public void completeMembersCanBeAppended() throws IOException {
        List<String> first = Arrays.asList("{\"t\":1}", "{\"t\":2}");
        List<String> second = Collections.singletonList("{\"t\":3}");
        ByteArrayOutputStream journal = new ByteArrayOutputStream();
        journal.write(legacy(first));
        TripJournalChunk.encode(second, Function.identity()).writeTo(journal);
        assertEquals("{\"t\":1}\n{\"t\":2}\n{\"t\":3}\n",
                new String(decode(journal.toByteArray()), StandardCharsets.UTF_8));
    }

    @Test public void serializationFailureDoesNotReturnPartialMember() throws IOException {
        try {
            TripJournalChunk.encode(Arrays.asList(1, 2, 3), row -> {
                if (row == 2) throw new IllegalStateException("serialization failed");
                return "{}";
            });
            fail("Expected serializer failure");
        } catch (IllegalStateException expected) {
            assertEquals("serialization failed", expected.getMessage());
        }
    }
}
