package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Recording filenames come from world-writable scan roots and reach events.js innerHTML. Foreign
 * names stay indexed (RecordingsIndex surfaces unknown grammar on purpose), so the page escapes them.
 */
public class EventsFilenameEscapeContractTest {

    @Test
    public void eventsJsEscapesFilenameAtEverySink() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/assets/web/shared/events.js")), StandardCharsets.UTF_8);
        assertFalse(s.contains("data-filename=\"' + rec.filename"));
        assertFalse(s.contains("data-filename=\"' + filename"));
        assertFalse(s.contains("data-sk-file=\"' + rec.filename"));
        assertFalse(s.contains("title=\"' + rec.filename"));
        assertFalse(s.contains("'\">' + rec.filename + '</span>"));
        assertTrue(s.contains("this._esc(rec.filename)"));
        assertTrue(s.contains("this._jsStr(recordingKey)"));
        // Attribute sinks need quotes escaped; the textContent/innerHTML trick does not do that.
        assertTrue(s.contains(".replace(/\"/g, '&quot;')"));
        assertTrue(s.contains(".replace(/'/g, '&#39;')"));
    }
}
