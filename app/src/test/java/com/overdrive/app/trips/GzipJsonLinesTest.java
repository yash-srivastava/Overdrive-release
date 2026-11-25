package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GzipJsonLinesTest {
    @Test
    public void handlesMultipleMembersAndMissingFinalNewline() throws IOException {
        byte[] data = members(utf8("\n {\"t\":1}\r\n\n"), utf8(""), utf8("{\"t\":2"),
                utf8("}\nmalformed row\n\t\n{\"t\":3}"));
        assertEquals(Arrays.asList("{\"t\":1}", "{\"t\":2}", "malformed row", "{\"t\":3}"),
                decoded(data));
        compare(data);
    }

    @Test
    public void preservesUtf8AcrossReadAndMemberBoundaries() throws IOException {
        byte[] row = utf8("{\"t\":1,\"label\":\"" + repeat('x', 4080) + "é🚗漢\"}\n");
        for (int split = 4070; split < row.length; split++) {
            compare(members(Arrays.copyOfRange(row, 0, split),
                    Arrays.copyOfRange(row, split, row.length), utf8("{\"t\":2}")));
        }
        compare(members(utf8(repeat('a', 100_000) + "\nshort\n")));
    }

    @Test
    public void keepsInflatedPrefixOfDamagedFiles() throws IOException {
        byte[] first = members(utf8("{\"t\":1}\n"));
        byte[] second = members(utf8(repeat('a', 12_000) + "\n{\"t\":2}\n"));
        for (int n = 0; n <= second.length; n++) {
            compare(concat(first, Arrays.copyOf(second, n)));
        }
        byte[] badCrc = second.clone();
        badCrc[badCrc.length - 8] ^= 1;
        compare(concat(first, badCrc));
        compare(concat(first, utf8("not gzip")));
        compare(utf8("not gzip"));
        compare(new byte[0]);
        compare(null);
    }

    @Test
    public void randomizedByteAndMemberBoundariesMatchReference() throws IOException {
        Random random = new Random(0x0d2026);
        for (int trial = 0; trial < 500; trial++) {
            byte[] raw = new byte[random.nextInt(24_000)];
            random.nextBytes(raw); // Includes malformed UTF-8 and arbitrary LF/CR positions.
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            int pos = 0;
            while (pos < raw.length) {
                int end = Math.min(raw.length, pos + 1 + random.nextInt(4500));
                compressed.write(members(Arrays.copyOfRange(raw, pos, end)));
                pos = end;
            }
            compare(compressed.toByteArray());
        }
    }

    // Compare what the JSON parser sees (trimmed, nonempty LF records) against
    // the original whole-file decompression and split implementation.
    private static void compare(byte[] data) throws IOException {
        assertEquals(reference(data), decoded(data));
    }

    private static List<String> decoded(byte[] data) {
        List<String> rows = new ArrayList<>();
        GzipJsonLines.read(data, line -> add(rows, line));
        return rows;
    }

    private static List<String> reference(byte[] data) throws IOException {
        List<String> rows = new ArrayList<>();
        if (data == null || data.length == 0) return rows;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        ByteArrayInputStream source = new ByteArrayInputStream(data);
        byte[] chunk = new byte[4096];
        while (source.available() > 0) {
            try (GZIPInputStream gzip = new GZIPInputStream(source)) {
                int n;
                while ((n = gzip.read(chunk)) != -1) result.write(chunk, 0, n);
            } catch (IOException e) {
                break;
            }
        }
        for (String line : result.toString("UTF-8").split("\n")) add(rows, line);
        return rows;
    }

    private static void add(List<String> rows, String line) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty()) rows.add(trimmed);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static byte[] members(byte[]... parts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(part);
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[] first, byte[] second) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(first);
        bytes.write(second);
        return bytes.toByteArray();
    }
}
