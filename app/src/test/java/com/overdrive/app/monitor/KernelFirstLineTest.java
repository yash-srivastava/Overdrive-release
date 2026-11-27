package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import org.junit.Test;

public class KernelFirstLineTest {
    private static void compare(byte[] data, int maxRead) throws IOException {
        String expected;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            expected = reader.readLine();
        }
        InputStream stream = new ByteArrayInputStream(data) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(maxRead, len));
            }
        };
        assertEquals(expected, KernelFirstLine.read(stream));
    }

    @Test public void handlesShortLinesAndTerminators() throws IOException {
        for (String text : new String[]{"", "\n", "\r", "\r\n", "  45@1000\n99", "56700",
                "cpu 100 2 30 300 4 5 6 0\nother", "123 (a name) with ) spaces) S 0 1\n",
                "  café 車 \r\nsecond", "\nignored"}) {
            for (int maxRead : new int[]{1, 3, 511, 512, 8192}) {
                compare(text.getBytes(StandardCharsets.UTF_8), maxRead);
            }
        }
    }

    @Test public void handlesLongLinesAndSplitUtf8() throws IOException {
        for (int length : new int[]{510, 511, 512, 513, 1024, 8192, 17000}) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < length; i++) line.append('x');
            line.append("車é\r\nignored");
            for (int maxRead : new int[]{1, 7, 511, 512, 8192}) {
                compare(line.toString().getBytes(StandardCharsets.UTF_8), maxRead);
            }
            compare(line.substring(0, length).getBytes(StandardCharsets.UTF_8), 512);
        }
    }

    @Test public void matchesReaderForRandomBytes() throws IOException {
        Random random = new Random(180512);
        for (int i = 0; i < 1000; i++) {
            byte[] data = new byte[random.nextInt(4096)];
            random.nextBytes(data);
            compare(data, 1 + random.nextInt(1024));
        }
    }

    @Test public void fileReadsFreshValuesAndPropagatesMissingFile() throws IOException {
        File file = File.createTempFile("kernel-line", ".txt");
        try {
            Files.write(file.toPath(), "45@1000\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("45@1000", KernelFirstLine.read(file));
            Files.write(file.toPath(), "0\n".getBytes(StandardCharsets.UTF_8));
            assertEquals("0", KernelFirstLine.read(file));
        } finally {
            Files.deleteIfExists(file.toPath());
        }
        try {
            KernelFirstLine.read(file);
            fail("Missing file must throw");
        } catch (IOException expected) {
            // Callers retain their existing error policy.
        }
    }
}
