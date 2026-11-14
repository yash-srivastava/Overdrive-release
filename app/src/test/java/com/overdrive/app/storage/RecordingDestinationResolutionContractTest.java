package com.overdrive.app.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guards for writers that must not use the active-session cached directory. */
public class RecordingDestinationResolutionContractTest {

    @Test
    public void liveRecordingsDirectoryResolvesConfiguredVolumeUnderCategoryLock() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/storage/StorageManager.java");
        String body = methodBody(source, "public File getLiveRecordingsDir()");

        assertTrue(body.contains("synchronized (recordingsCleanupLock)"));
        assertTrue(body.contains("resolveActive(recordingsStorageType"));
    }

    @Test
    public void independentAndFreshWritersUseLiveMountedDestination() throws IOException {
        String replay = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/recording/ManualClipService.java");
        String recorder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");

        assertTrue(methodBody(replay, "private static File prepareOutputFile()")
                .contains("getLiveRecordingsDirIfMounted()"));
        assertTrue(methodBody(recorder, "public void startRecording(java.io.File outputDir")
                .contains("getLiveRecordingsDirIfMounted()"));
        assertTrue(pipeline.contains("getLiveRecordingsDirIfMounted()"));
        assertFalse(methodBody(replay, "private static File prepareOutputFile()")
                .contains("getRecordingsDir()"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Could not locate " + signature);
        int openingBrace = source.indexOf('{', start);
        int depth = 0;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openingBrace, i + 1);
            }
        }
        throw new AssertionError("Unbalanced method body for " + signature);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
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
