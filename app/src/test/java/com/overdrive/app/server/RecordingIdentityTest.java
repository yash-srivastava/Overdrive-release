package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

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
}