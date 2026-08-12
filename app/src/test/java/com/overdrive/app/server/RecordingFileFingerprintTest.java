package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

public class RecordingFileFingerprintTest {

    @Test
    public void exactStoredStateMatches() throws Exception {
        Path dir = Files.createTempDirectory("recording-fingerprint");
        File mp4 = write(dir.resolve("event_20260810_120000.mp4"), "video");
        File sidecar = write(dir.resolve("event_20260810_120000.json"), "{}");
        Files.setLastModifiedTime(mp4.toPath(), FileTime.fromMillis(10_000L));
        Files.setLastModifiedTime(sidecar.toPath(), FileTime.fromMillis(20_000L));

        RecordingFileFingerprint fingerprint = RecordingFileFingerprint.from(mp4);

        assertTrue(fingerprint.matches(mp4.getAbsolutePath(), mp4.length(),
                mp4.lastModified(), sidecar.lastModified()));
        assertEquals(RecordingFileFingerprint.Decision.UNCHANGED,
                fingerprint.decisionAgainst(mp4.getAbsolutePath(), mp4.length(),
                        mp4.lastModified(), sidecar.lastModified()));
    }

    @Test
    public void pathSizeAndMtimeChangesRequireRefresh() throws Exception {
        Path dir = Files.createTempDirectory("recording-fingerprint");
        File mp4 = write(dir.resolve("cam_20260810_120000.mp4"), "video");
        RecordingFileFingerprint fingerprint = RecordingFileFingerprint.from(mp4);

        assertEquals(RecordingFileFingerprint.Decision.ADD,
                fingerprint.decisionAgainst(null, 0L, 0L, 0L));
        assertFalse(fingerprint.matches("/old/volume/" + mp4.getName(),
                fingerprint.sizeBytes, fingerprint.mp4Mtime, fingerprint.sidecarMtime));
        assertFalse(fingerprint.matches(fingerprint.absolutePath,
                fingerprint.sizeBytes + 1L, fingerprint.mp4Mtime, fingerprint.sidecarMtime));
        assertFalse(fingerprint.matches(fingerprint.absolutePath,
                fingerprint.sizeBytes, fingerprint.mp4Mtime + 1L, fingerprint.sidecarMtime));
        assertEquals(RecordingFileFingerprint.Decision.REFRESH,
                fingerprint.decisionAgainst("/old/volume/" + mp4.getName(),
                        fingerprint.sizeBytes, fingerprint.mp4Mtime,
                        fingerprint.sidecarMtime));
    }

    @Test
    public void sidecarCreationChangeAndDeletionRequireRefresh() throws Exception {
        Path dir = Files.createTempDirectory("recording-fingerprint");
        File mp4 = write(dir.resolve("proximity_20260810_120000.mp4"), "video");
        RecordingFileFingerprint withoutSidecar = RecordingFileFingerprint.from(mp4);

        File sidecar = write(dir.resolve("proximity_20260810_120000.json"), "{}");
        Files.setLastModifiedTime(sidecar.toPath(), FileTime.fromMillis(30_000L));
        RecordingFileFingerprint withSidecar = RecordingFileFingerprint.from(mp4);

        assertFalse(withSidecar.matches(withoutSidecar.absolutePath,
                withoutSidecar.sizeBytes, withoutSidecar.mp4Mtime,
                withoutSidecar.sidecarMtime));
        assertTrue(sidecar.delete());
        RecordingFileFingerprint afterDelete = RecordingFileFingerprint.from(mp4);
        assertFalse(afterDelete.matches(withSidecar.absolutePath,
                withSidecar.sizeBytes, withSidecar.mp4Mtime, withSidecar.sidecarMtime));
    }

    @Test
    public void sidecarMtimeUpdateRefreshesOnceThenConverges() throws Exception {
        Path dir = Files.createTempDirectory("recording-fingerprint");
        File mp4 = write(dir.resolve("event_20260810_130000.mp4"), "video");
        File sidecar = write(dir.resolve("event_20260810_130000.json"), "{}");
        Files.setLastModifiedTime(sidecar.toPath(), FileTime.fromMillis(30_000L));
        RecordingFileFingerprint before = RecordingFileFingerprint.from(mp4);

        Files.setLastModifiedTime(sidecar.toPath(), FileTime.fromMillis(40_000L));
        RecordingFileFingerprint after = RecordingFileFingerprint.from(mp4);

        assertEquals(RecordingFileFingerprint.Decision.REFRESH,
                after.decisionAgainst(before.absolutePath, before.sizeBytes,
                        before.mp4Mtime, before.sidecarMtime));
        assertEquals(RecordingFileFingerprint.Decision.UNCHANGED,
                after.decisionAgainst(after.absolutePath, after.sizeBytes,
                        after.mp4Mtime, after.sidecarMtime));
    }

    @Test
    public void unreadableSidecarUsesSameZeroMtimeAsParser() throws Exception {
        Path dir = Files.createTempDirectory("recording-fingerprint");
        File mp4 = write(dir.resolve("event_20260810_140000.mp4"), "video");
        File sidecar = write(dir.resolve("event_20260810_140000.json"), "{}");
        sidecar.setReadable(false, false);
        Assume.assumeFalse("filesystem did not make sidecar unreadable", sidecar.canRead());

        RecordingFileFingerprint fingerprint = RecordingFileFingerprint.from(mp4);

        assertEquals(0L, fingerprint.sidecarMtime);
        assertEquals(RecordingFileFingerprint.Decision.UNCHANGED,
                fingerprint.decisionAgainst(fingerprint.absolutePath,
                        fingerprint.sizeBytes, fingerprint.mp4Mtime, 0L));
    }

    @Test
    public void sidecarNameOnlyReplacesTrailingMp4Suffix() {
        File mp4 = new File("/recordings/event_a.mp4_2.mp4");

        assertTrue(RecordingFileFingerprint.sidecarFor(mp4).getPath()
                .endsWith("/recordings/event_a.mp4_2.json"));
    }

    private static File write(Path path, String content) throws Exception {
        Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return path.toFile();
    }
}