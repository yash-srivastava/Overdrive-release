package com.overdrive.app.services;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression test for run-2 finding
 * {@code exported-mediaplaybackservice-ungated-tts-playback}: the exported
 * service must be DUMP-gated and check the calling UID; the STOP_MEDIA
 * receiver must not be exported on API 33+.
 */
public class MediaPlaybackServiceSecurityContractTest {

    @Test
    public void serviceRequiresDumpPermission() throws Exception {
        String m = new String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")),
                StandardCharsets.UTF_8);
        int i = m.indexOf("com.overdrive.app.services.MediaPlaybackService");
        assertTrue(i > 0);
        String block = m.substring(i, m.indexOf("/>", i));
        assertTrue(block.contains("android:permission=\"android.permission.DUMP\""));
    }

    @Test
    public void onStartCommandChecksCallingUidAndStopReceiverNotExported() throws Exception {
        String s = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/services/MediaPlaybackService.java")),
                StandardCharsets.UTF_8);
        assertTrue(s.contains("Binder.getCallingUid()"));
        assertTrue(s.contains("rejected playback command from uid"));
        assertTrue(s.contains("RECEIVER_NOT_EXPORTED"));
    }
}
