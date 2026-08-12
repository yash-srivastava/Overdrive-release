package com.overdrive.app.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StoragePostSaveOwnershipTest {

    @Test
    public void mp4FinalizerOwnsPostSaveDispatch() throws IOException {
        String encoder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/HardwareEventRecorderGpu.java");
        assertTrue(encoder.contains("onFileSaved(finalFile)"));

        String mosaic = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuMosaicRecorder.java");
        String closeCallback = lambdaBody(mosaic, "encoder.setFileClosedCallback(() ->");
        assertFalse(closeCallback.contains("onRecordingFileSaved()"));
        assertFalse(closeCallback.contains("onSurveillanceFileSaved()"));

        String proximity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/proximity/ProximityRecordingHandler.java");
        assertFalse(methodBody(proximity, "public void stopRecording()")
                .contains("onProximityFileSaved()"));
    }

    @Test
    public void nonMp4TripFinalizerStillRequestsCleanup() throws IOException {
        String trips = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/trips/TripTelemetryRecorder.java");
        assertTrue(methodBody(trips, "public String stopRecording()")
                .contains("onTripFileSaved()"));
    }

    private static String lambdaBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Could not locate " + signature);
        return balancedBlock(source, source.indexOf('{', start), signature);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) throw new AssertionError("Could not locate " + signature);
        return balancedBlock(source, source.indexOf('{', start), signature);
    }

    private static String balancedBlock(String source, int openingBrace, String label) {
        int depth = 0;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) {
                return source.substring(openingBrace, i + 1);
            }
        }
        throw new AssertionError("Unbalanced block for " + label);
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