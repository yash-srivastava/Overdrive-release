package com.overdrive.app.surveillance;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class HardwareEventRecorderGpuLateResourceCleanupContractTest {

    private static final String RECORDER =
            "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java";

    @Test
    public void encoderCreationTimeoutReleasesPublishedAndLateCodecs() throws Exception {
        String source = readRepositoryFile(RECORDER);
        int start = source.indexOf(
                "final LateResourceHandoff<MediaCodec> createHandoff");
        int end = source.indexOf("// Confirm the negotiated codec name", start);
        assertTrue(start >= 0 && end > start);
        String block = source.substring(start, end);

        assertTrue(block.contains("!createHandoff.publish(created)"));
        assertTrue(block.contains("created.release()"));
        assertTrue(block.contains("createHandoff.abandon()"));
        assertTrue(block.contains("lateEncoder.release()"));
        assertTrue(block.contains("encoder = createHandoff.take()"));
    }

    @Test
    public void inputSurfaceTimeoutReleasesPublishedAndLateSurfaces() throws Exception {
        String source = readRepositoryFile(RECORDER);
        int start = source.indexOf(
                "final LateResourceHandoff<Surface> surfaceHandoff");
        int end = source.indexOf("logger.info(\"Input surface created\")", start);
        assertTrue(start >= 0 && end > start);
        String block = source.substring(start, end);

        assertTrue(block.contains("!surfaceHandoff.publish(created)"));
        assertTrue(block.contains("created.release()"));
        assertTrue(block.contains("surfaceHandoff.abandon()"));
        assertTrue(block.contains("lateSurface.release()"));
        assertTrue(block.contains("inputSurface = surfaceHandoff.take()"));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
