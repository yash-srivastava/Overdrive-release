package com.overdrive.app.server.timeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Guards the short spool lock scope without requiring a running camera daemon. */
public class TimelineJournalSpoolContractTest {

    @Test
    public void parsingIsOutsideTheSpoolAndFileLockScope() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/timeline/TimelineJournal.java");
        String drain = between(source,
                "static List<Entry> drainSpool() {", "private static byte[] readAndClearSpool() {");
        String snapshot = between(source,
                "private static byte[] readAndClearSpool() {", "private static FileLock lock(");

        assertTrue(drain.contains("byte[] bytes = readAndClearSpool();"));
        assertTrue(drain.contains("return decode(bytes);"));
        assertFalse(drain.contains("synchronized (SPOOL_LOCK)"));
        assertTrue(snapshot.contains("synchronized (SPOOL_LOCK)"));
        assertTrue(snapshot.contains("channel.truncate(0);"));
        assertTrue(snapshot.contains("release(lock);"));
        assertFalse(snapshot.contains("decode("));
    }

    @Test
    public void preservedCodecHandlesUnicodeAndEmptyFields() {
        List<TimelineJournal.Entry> rows = Arrays.asList(
                new TimelineJournal.Entry(123L, "power", "off", "acc_off", "Café · parked"),
                new TimelineJournal.Entry(124L, "daemon", "on", null, null));
        List<TimelineJournal.Entry> restored = TimelineJournal.decode(TimelineJournal.encode(rows));

        assertEquals(2, restored.size());
        assertEquals("Café · parked", restored.get(0).extra);
        assertEquals("acc_off", restored.get(0).reason);
        assertEquals(124L, restored.get(1).ts);
        assertNull(restored.get(1).reason);
        assertNull(restored.get(1).extra);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("Missing marker: " + start, from >= 0);
        assertTrue("Missing marker: " + end, to > from);
        return source.substring(from, to);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
