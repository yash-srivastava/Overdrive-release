package com.overdrive.app.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

/** Contract for relocating a *finalized* ACC-OFF event after late SD remount. */
public class SurveillanceSdRehomeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void eventSidecarsIncludeOnlyTheSelectedSegmentsThumbnails() throws Exception {
        File internal = temporary.newFolder("internal");
        File event = new File(internal, "event_20261119_081000.mp4");
        write(event, "video");
        write(new File(internal, "event_20261119_081000.json"), "{}");
        write(new File(internal, "event_20261119_081000.jpg"), "jpg");
        write(new File(internal, "event_20261119_081000.srt"), "subtitles");
        write(new File(internal, "thumb_event_20261119_081000_a2_100.jpg"), "actor");
        write(new File(internal, "thumb_event_20261119_081000_2_a3_100.jpg"), "other segment");
        write(new File(internal, "event_20261119_081000_2.json"), "{}");
        List<String> names = StorageManager.surveillanceEventSidecars(event).stream()
                .map(File::getName).sorted().collect(Collectors.toList());
        assertEquals(4, names.size());
        assertTrue(names.contains("event_20261119_081000.json"));
        assertTrue(names.contains("event_20261119_081000.jpg"));
        assertTrue(names.contains("event_20261119_081000.srt"));
        assertTrue(names.contains("thumb_event_20261119_081000_a2_100.jpg"));
        assertFalse(names.contains("thumb_event_20261119_081000_2_a3_100.jpg"));
    }

    @Test
    public void stagedCopyPublishesExactBytesAndLeavesOriginalIntact() throws Exception {
        File internal = temporary.newFolder("internal");
        File sd = temporary.newFolder("sd");
        File source = new File(internal, "event_20261119_081000.mp4");
        byte[] video = "video-ä-安全".getBytes(StandardCharsets.UTF_8);
        Files.write(source.toPath(), video);
        File destination = new File(sd, source.getName());
        StorageManager.copySurveillanceMigrationFile(source, destination);
        assertArrayEquals(video, Files.readAllBytes(destination.toPath()));
        assertArrayEquals(video, Files.readAllBytes(source.toPath()));
        File[] temporaryFiles = sd.listFiles((dir, name) -> name.startsWith(".surv-rehome-"));
        assertTrue(temporaryFiles != null && temporaryFiles.length == 0);
    }

    @Test
    public void collisionNeverOverwritesDestinationOrDeletesSource() throws Exception {
        File internal = temporary.newFolder("internal");
        File sd = temporary.newFolder("sd");
        File source = new File(internal, "event_20261119_081000.mp4");
        File destination = new File(sd, source.getName());
        write(source, "original");
        write(destination, "different clip");
        try {
            StorageManager.copySurveillanceMigrationFile(source, destination);
            fail("existing destination must not be replaced");
        } catch (java.nio.file.FileAlreadyExistsException expected) {
            // Collision is a safe failure: no data is overwritten.
        }
        assertEquals("original", read(source));
        assertEquals("different clip", read(destination));
    }

    private static void write(File path, String value) throws Exception {
        Files.write(path.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File path) throws Exception {
        return new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8);
    }
}
