package com.overdrive.app.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Regression source for the safety boundary of destructive SD cleanup. */
public class CdrCleanupSafetyTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void dedicatedRecorderPathIsAccepted() throws IOException {
        File sd = temp.newFolder("sd");
        File cdr = new File(sd, "Recorder/Normal");
        assertTrue(cdr.mkdirs());
        assertTrue(CdrCleanupSafety.isTrustedRecorderRoot(sd, cdr));
        assertTrue(CdrCleanupSafety.isTrustedRecorderRoot(sd,
                new File(sd, "Recorder")));
    }

    @Test
    public void personalMediaFoldersAndGuessedLocationsAreRejected() throws IOException {
        File sd = temp.newFolder("sd");
        for (String name : new String[]{"DCIM", "Movies", "Camera", "Video", "Holiday"}) {
            File dir = new File(sd, name);
            assertTrue(dir.mkdir());
            assertFalse(CdrCleanupSafety.isTrustedRecorderRoot(sd, dir));
        }
        assertFalse(CdrCleanupSafety.isTrustedRecorderRoot(sd, sd));
    }

    @Test
    public void configuredPathOutsideSdAndPrefixCollisionAreRejected() throws IOException {
        File sd = temp.newFolder("sd");
        File other = temp.newFolder("sd-bad");
        File cdr = new File(other, "CDR");
        assertTrue(cdr.mkdir());
        assertFalse(CdrCleanupSafety.isTrustedRecorderRoot(sd, cdr));
        assertFalse(CdrCleanupSafety.isSameOrWithinRoot(sd, cdr));
    }

    @Test
    public void symlinkNamedLikeRecorderCannotTargetPersonalMedia() throws IOException {
        File sd = temp.newFolder("sd");
        File dcim = new File(sd, "DCIM");
        assertTrue(dcim.mkdir());
        File alias = new File(sd, "CDR");
        createSymlinkOrSkip(alias, dcim);
        assertFalse(CdrCleanupSafety.isTrustedRecorderRoot(sd, alias));
    }

    @Test
    public void aFileOrDirectorySymlinkEscapingRecorderIsNotDeletable() throws IOException {
        File sd = temp.newFolder("sd");
        File cdr = new File(sd, "CDR");
        assertTrue(cdr.mkdir());
        File recording = new File(cdr, "old.mp4");
        assertTrue(recording.createNewFile());
        File privateClip = new File(temp.getRoot(), "private.mp4");
        assertTrue(privateClip.createNewFile());
        File shortcut = new File(cdr, "shortcut.mp4");
        createSymlinkOrSkip(shortcut, privateClip);
        assertTrue(CdrCleanupSafety.isRegularFileInRecorder(cdr, recording));
        assertFalse(CdrCleanupSafety.isRegularFileInRecorder(cdr, shortcut));
        File linkDir = new File(cdr, "other");
        createSymlinkOrSkip(linkDir, temp.getRoot());
        assertFalse(CdrCleanupSafety.isSameOrWithinRoot(cdr,
                new File(linkDir, "private.mp4")));
    }

    private static void createSymlinkOrSkip(File link, File target) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Windows without Developer Mode often cannot create symlinks.
            Assume.assumeNoException(e);
        }
    }
}
