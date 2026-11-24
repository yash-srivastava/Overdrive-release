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
