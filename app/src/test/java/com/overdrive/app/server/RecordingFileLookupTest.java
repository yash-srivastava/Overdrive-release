package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public class RecordingFileLookupTest {
    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void firstReadableNonemptyMediaWins() throws Exception {
        File first = temporaryFolder.newFolder("first");
        File second = temporaryFolder.newFolder("second");
        File earlier = new File(first, "clip.mp4");
        File later = new File(second, "clip.mp4");
        Files.write(earlier.toPath(), new byte[]{1});
        Files.write(later.toPath(), new byte[]{2});

        assertEquals(earlier, RecordingsApiHandler.findInDirectories(
                Arrays.asList(first, second), "clip.mp4", true));
    }

    @Test
    public void emptyMediaAndMissingEntriesAreSkipped() throws Exception {
        File missing = temporaryFolder.newFolder("missing");
        File empty = temporaryFolder.newFolder("empty");
        File ready = temporaryFolder.newFolder("ready");
        Files.createFile(new File(empty, "clip.mp4.tmp").toPath());
        File complete = new File(ready, "clip.mp4.tmp");
        Files.write(complete.toPath(), new byte[]{1});

        assertEquals(complete, RecordingsApiHandler.findInDirectories(
                Arrays.asList(missing, empty, ready), "clip.mp4.tmp", true));
        assertNull(RecordingsApiHandler.findInDirectories(
                Arrays.asList(missing, empty), "clip.mp4.tmp", true));
    }

    @Test
    public void legacySidecarLookupRetainsExistenceOnlyPolicy() throws Exception {
        File first = temporaryFolder.newFolder("first");
        File second = temporaryFolder.newFolder("second");
        File empty = new File(first, "clip.json");
        Files.createFile(empty.toPath());
        Files.write(new File(second, "clip.json").toPath(), new byte[]{1});

        assertEquals(empty, RecordingsApiHandler.findInDirectories(
                Arrays.asList(first, second), "clip.json", false));
        assertNull(RecordingsApiHandler.findInDirectories(
                Arrays.asList(first, second), "missing.json", false));
    }
}
