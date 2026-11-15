package com.overdrive.app.audio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

public class AppAudioCaptureBackpressureContractTest {

    @Test
    public void captureRetriesTemporaryEncoderBackpressureInsteadOfDroppingPcm() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/audio/AppAudioCaptureController.java")),
                StandardCharsets.UTF_8);
        int captureStart = source.indexOf("    private void captureLoop() {");
        int captureEnd = source.indexOf("    /**\n     * A read that failed", captureStart);
        String captureLoop = source.substring(captureStart, captureEnd);

        assertTrue(captureLoop.contains(
                "while (inputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && running.get())"));
        assertTrue(captureLoop.contains("aacEncoder.queueInputBuffer(inputIndex, 0, consumerLength, 0L, 0)"));
        assertFalse(captureLoop.contains("drop this PCM chunk"));
        assertFalse(captureLoop.contains("if (inputIndex >= 0)"));
    }

    @Test
    public void audioRecordBufferCanAbsorbTwoSecondsOfEncoderBackpressure() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/audio/AppAudioCaptureController.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains(
                "int backpressureBufferBytes = captureSampleRate * BYTES_PER_SAMPLE * 2;"));
        assertTrue(source.contains(
                "int bufSize = Math.max(minBuf * 4, backpressureBufferBytes);"));
    }

    @Test
    public void drainTimelineDoesNotReanchorToSchedulingWallClock() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/overdrive/app/audio/AppAudioCaptureController.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("new AacPresentationTimeline("));
        assertTrue(source.contains("long ptsUs = audioTimeline.nextPtsUs();"));
        assertFalse(source.contains("AUDIO_PTS_RESYNC_THRESHOLD_US"));
        assertFalse(source.contains("realElapsedUs - cadenceElapsedUs"));
    }
}
