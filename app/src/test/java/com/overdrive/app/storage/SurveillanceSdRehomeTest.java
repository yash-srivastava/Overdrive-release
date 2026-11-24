package com.overdrive.app.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

/** File-level contracts for the finalized ACC-OFF event SD rehome. */
public class SurveillanceSdRehomeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    // hasPublishedSurveillanceMarker consults the process-wide StorageManager; an instance left
    // by an earlier test points at another internal dir and hides these temp-folder markers.
    private Object savedInstance;

    @org.junit.Before
    public void isolateStorageManager() throws Exception {
        java.lang.reflect.Field field = StorageManager.class.getDeclaredField("instance");
        field.setAccessible(true);
        savedInstance = field.get(null);
        field.set(null, null);
    }

    @org.junit.After
    public void restoreStorageManager() throws Exception {
        java.lang.reflect.Field field = StorageManager.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, savedInstance);
    }

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
    public void stagingUsesSweptPrefixAndKeepsRecentMtimeUntilPublish() throws Exception {
        File internal = temporary.newFolder("staging-internal");
        File sd = temporary.newFolder("staging-sd");
        File source = new File(internal, "event_staging.mp4");
        write(source, "video");
        assertTrue(source.setLastModified(1_640_995_200_000L));
        File staged = StorageManager.stageSurveillanceMigrationFile(source, sd);
        try {
            assertTrue(staged.getName().startsWith(".surv-rehome-"));
            assertFalse(new File(sd, source.getName()).exists());
            assertArrayEquals(Files.readAllBytes(source.toPath()),
                    Files.readAllBytes(staged.toPath()));
            assertTrue("Staging must not inherit an old mtime that the orphan sweep could delete",
                    staged.lastModified() > source.lastModified() + 10L * 60_000L);
        } finally {
            assertTrue(staged.delete());
        }
    }

    @Test
    public void restartFingerprintRequiresBothMatchingSizeAndMtime() throws Exception {
        File internal = temporary.newFolder("internal");
        File sd = temporary.newFolder("sd");
        File source = new File(internal, "event_20261119_081000.mp4");
        File destination = new File(sd, source.getName());
        write(source, "original");
        write(destination, "short");
        assertFalse(StorageManager.matchesPublishedSurveillanceCopy(source, destination));
        write(destination, "altered!");
        assertTrue(source.setLastModified(1_640_995_200_000L));
        assertTrue(destination.setLastModified(1_640_995_202_000L));
        assertTrue("FAT32 rounds down by up to two seconds",
                StorageManager.matchesPublishedSurveillanceCopy(source, destination));
        assertTrue(destination.setLastModified(1_640_995_202_001L));
        assertFalse(StorageManager.matchesPublishedSurveillanceCopy(source, destination));
        assertTrue(destination.setLastModified(source.lastModified() + 10L));
        assertTrue("exFAT can round to 10 milliseconds",
                StorageManager.matchesPublishedSurveillanceCopy(source, destination));
    }

    @Test
    public void unmarkedSameNamedTwinIsNotPublished() throws Exception {
        File internal = temporary.newFolder("unmarked-internal");
        File sd = temporary.newFolder("unmarked-sd");
        File source = new File(internal, "event_20261119_081000.mp4");
        File twin = new File(sd, source.getName());
        write(source, "original");
        write(twin, "original");
        assertTrue(twin.setLastModified(source.lastModified()));
        assertTrue(StorageManager.matchesPublishedSurveillanceCopy(source, twin));
        assertFalse("No marker means the internal clip remains visible",
                StorageManager.hasPublishedSurveillanceMarker(source));
    }

    @Test
    public void markerMustBePublishedAndMatchTheOriginalFingerprint() throws Exception {
        File dir = temporary.newFolder("marked-internal");
        File source = new File(dir, "event_20261119_081000.mp4");
        File marker = new File(dir, ".surv-retained-" + source.getName() + ".marker");
        write(source, "video");
        String fingerprint = source.length() + " " + source.lastModified() + "\n";
        write(marker, "pending " + fingerprint);
        assertFalse(StorageManager.hasPublishedSurveillanceMarker(source));
        write(marker, "published " + fingerprint);
        assertTrue(StorageManager.hasPublishedSurveillanceMarker(source));
        write(source, "a newly recorded clip");
        assertFalse("A reused pathname must never be hidden by a stale marker",
                StorageManager.hasPublishedSurveillanceMarker(source));
        assertTrue(marker.delete());
    }

    private static void write(File path, String value) throws Exception {
        Files.write(path.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }
}
