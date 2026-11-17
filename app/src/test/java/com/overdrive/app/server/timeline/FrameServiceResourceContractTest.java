package com.overdrive.app.server.timeline;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source contracts for the Android 10 timeline's bounded decoder lifecycle. */
public class FrameServiceResourceContractTest {

    @Test
    public void idleDecoderCanRetireWithoutStrandingAQueuedRequest() throws IOException {
        String source = frameServiceSource();
        assertTrue(source.contains("WORKER_IDLE_NS = TimeUnit.SECONDS.toNanos(30)"));
        assertTrue(source.contains("if (PENDING.isEmpty())"));
        assertTrue(source.contains("if (worker == Thread.currentThread()) worker = null;"));
        assertTrue(source.contains("LOCK.wait(Math.max(1L"));
        assertTrue(source.contains("if (myGeneration != generation) return;"));
    }

    @Test
    public void scrubFramesDecodeAtRequestedSizeWithVendorFallback() throws IOException {
        String source = frameServiceSource();
        assertTrue(source.contains("getScaledFrameAtTime(job.atMs * 1000L"));
        assertTrue(source.contains("MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION"));
        assertTrue(source.contains("if (frame == null) {\n                frame = retriever.getFrameAtTime"));
        assertTrue(source.contains("recycle(corrected);"));
        assertTrue(source.contains("recycle(scaled);"));
        assertTrue(source.contains("recycle(frame);"));
    }

    private static String frameServiceSource() throws IOException {
        Path directory = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path file = directory.resolve(
                    "app/src/main/java/com/overdrive/app/server/timeline/FrameService.java");
            if (Files.isRegularFile(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
            file = directory.resolve(
                    "src/main/java/com/overdrive/app/server/timeline/FrameService.java");
            if (Files.isRegularFile(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new AssertionError("FrameService.java not found");
    }
}
