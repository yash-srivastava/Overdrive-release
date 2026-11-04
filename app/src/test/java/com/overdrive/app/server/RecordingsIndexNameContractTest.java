package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression tests for run-2 finding
 * {@code app/src/main/assets/web/shared/events.js:rec.filename-innerHTML}:
 * recording filenames are attacker-influenceable (world-writable scan roots)
 * and were rendered unescaped into innerHTML on the events page.
 */
public class RecordingsIndexNameContractTest {

    @Test
    public void onlyGrammarFilenamesAreIndexable() {
        assertTrue(RecordingsIndex.isIndexableName("cam_20260101_000000.mp4"));
        assertTrue(RecordingsIndex.isIndexableName("REPLAY-2026-01-01-00-00-00.mp4"));
        assertTrue(RecordingsIndex.isIndexableName("EVENT_20260101_000000_A.mp4"));
        assertFalse(RecordingsIndex.isIndexableName("x<img src=x onerror=alert(1)>.mp4"));
        assertFalse(RecordingsIndex.isIndexableName("clip\"><img src=x onerror=alert(1)>.mp4"));
        assertFalse(RecordingsIndex.isIndexableName("a b.mp4"));
        assertFalse(RecordingsIndex.isIndexableName("../evil.mp4"));
        assertFalse(RecordingsIndex.isIndexableName("x.mp4.mp4.exe"));
        assertFalse(RecordingsIndex.isIndexableName(""));
        assertFalse(RecordingsIndex.isIndexableName(null));
    }

    @Test
    public void eventsJsEscapesFilenameAtEverySink() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/assets/web/local/../shared/events.js")), StandardCharsets.UTF_8);
        // no raw rec.filename may remain inside an HTML attribute or element text
        assertFalse("raw data-filename interpolation must be gone",
                s.contains("data-filename=\"' + rec.filename"));
        assertFalse(s.contains("data-sk-file=\"' + rec.filename"));
        assertFalse(s.contains("title=\"' + rec.filename"));
        assertFalse(s.contains("'>\" + rec.filename + '</span>"));
        assertTrue(s.contains("this._esc(rec.filename)"));
        assertTrue(s.contains("this._jsStr(recordingKey)"));
    }
}
