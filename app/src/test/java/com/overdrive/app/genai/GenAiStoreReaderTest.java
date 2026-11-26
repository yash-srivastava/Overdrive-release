package com.overdrive.app.genai;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class GenAiStoreReaderTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void readsUtf8ObjectAtExactSizeLimit() throws Exception {
        File file = folder.newFile();
        byte[] data = "{\"text\":\"Grüße\"}".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), data);
        assertNotNull(GenAiStoreReader.read(file, data.length));
        assertEquals("Grüße", GenAiStoreReader.read(file, data.length).getString("text"));
        assertNull(GenAiStoreReader.read(file, data.length - 1));
    }

    @Test
    public void rejectsMissingEmptyAndNonObjectFiles() throws Exception {
        assertNull(GenAiStoreReader.read(new File(folder.getRoot(), "missing"), 1024));
        assertNull(GenAiStoreReader.read(folder.getRoot(), 1024));
        File file = folder.newFile();
        assertNull(GenAiStoreReader.read(file, 1024));
        Files.write(file.toPath(), "not json".getBytes(StandardCharsets.UTF_8));
        assertNull(GenAiStoreReader.read(file, 1024));
        Files.write(file.toPath(), "[]".getBytes(StandardCharsets.UTF_8));
        assertNull(GenAiStoreReader.read(file, 1024));
    }
}
