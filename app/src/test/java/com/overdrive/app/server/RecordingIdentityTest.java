package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;

public class RecordingIdentityTest {

    @Test
    public void samePathProducesStableOpaqueIdentity() {
        RecordingIdentity first = RecordingIdentity.fromPath(
                "/storage/emulated/0/Overdrive/recordings/cam_20260810_120000.mp4");
        RecordingIdentity second = RecordingIdentity.fromPath(
                "/storage/emulated/0/Overdrive/recordings/cam_20260810_120000.mp4");

        assertEquals(first.recordingId, second.recordingId);
        assertEquals("internal", first.volumeId);
        assertEquals("Overdrive/recordings/cam_20260810_120000.mp4", first.relativePath);
        assertTrue(first.recordingId.matches("[0-9a-f]{32}"));
    }

    @Test
    public void duplicateFilenameOnDifferentVolumesGetsDifferentIdentity() {
        RecordingIdentity internal = RecordingIdentity.fromPath(
                "/storage/emulated/0/Overdrive/recordings/cam_20260810_120000.mp4");
        RecordingIdentity sd = RecordingIdentity.fromPath(
                "/storage/ABCD-1234/Overdrive/recordings/cam_20260810_120000.mp4");
        RecordingIdentity usb = RecordingIdentity.fromPath(
                "/storage/9876-DCBA/Overdrive/recordings/cam_20260810_120000.mp4");

        assertNotEquals(internal.recordingId, sd.recordingId);
        assertNotEquals(sd.recordingId, usb.recordingId);
        assertEquals("portable:abcd-1234", sd.volumeId);
    }

    @Test
    public void filesInSameDirectoryShareRootIdentity() {
        RecordingIdentity first = RecordingIdentity.fromPath(
                "/storage/ABCD-1234/Overdrive/surveillance/event_20260810_120000.mp4");
        RecordingIdentity second = RecordingIdentity.fromPath(
                "/storage/ABCD-1234/Overdrive/surveillance/event_20260810_120100.mp4");

        assertEquals(first.rootId, second.rootId);
        assertEquals(first.rootId,
                RecordingIdentity.rootIdFor(new File(
                        "/storage/ABCD-1234/Overdrive/surveillance")));
    }

    @Test
    public void mediaRwAndPublicStorageUseSamePortableIdentity() {
        RecordingIdentity publicPath = RecordingIdentity.fromPath(
                "/storage/ABCD-1234/Overdrive/recordings/cam.mp4");
        RecordingIdentity mediaRwPath = RecordingIdentity.fromPath(
                "/mnt/media_rw/ABCD-1234/Overdrive/recordings/cam.mp4");

        assertEquals(publicPath.volumeId, mediaRwPath.volumeId);
        assertEquals(publicPath.recordingId, mediaRwPath.recordingId);
    }

    @Test
    public void digestPrefixesMatchLegacyEncodingAcrossStoragePaths() throws Exception {
        Random random = new Random(8421L);
        for (int i = 0; i < 256; i++) {
            String name = "cam_é_" + Long.toHexString(random.nextLong()) + ".mp4";
            String relative = "Overdrive/recordings/" + name;
            assertLegacyIdentity("/storage/emulated/0/" + relative, "internal", relative);
            assertLegacyIdentity("/storage/ABCD-1234/" + relative, "portable:abcd-1234", relative);
            assertLegacyIdentity("/mnt/media_rw/ABCD-1234/" + relative, "portable:abcd-1234", relative);
            assertLegacyIdentity("/mnt/USB/" + relative, "mount:usb", relative);
            assertLegacyIdentity("/data/" + relative, "data", "data/" + relative);
            assertLegacyIdentity("/other/" + name, "path:" + legacyDigest("/other", 16), name);
        }
        assertLegacyIdentity("/storage//ABCD-1234\\recordings\\cam.mp4",
                "portable:abcd-1234", "recordings/cam.mp4");
        assertLegacyIdentity(null, "path:" + legacyDigest("/", 16), "unknown");
        assertLegacyIdentity("", "path:" + legacyDigest("/", 16), "unknown");
    }

    private static void assertLegacyIdentity(String path, String volume, String relative)
            throws Exception {
        RecordingIdentity identity = RecordingIdentity.fromPath(path);
        int slash = relative.lastIndexOf('/');
        String root = slash < 0 ? "" : relative.substring(0, slash);
        assertEquals(volume, identity.volumeId);
        assertEquals(relative, identity.relativePath);
        assertEquals(legacyDigest(volume + "|" + root, 24), identity.rootId);
        assertEquals(legacyDigest(volume + "|" + relative, 32), identity.recordingId);
    }

    private static String legacyDigest(String value, int chars) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format(Locale.US, "%02x", b & 0xff));
        return hex.substring(0, chars);
    }
}