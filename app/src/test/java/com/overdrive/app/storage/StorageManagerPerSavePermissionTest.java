package com.overdrive.app.storage;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StorageManagerPerSavePermissionTest {

    @Test
    public void fileFinalizationUsesSingleFilePermissionUpdate() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/storage/StorageManager.java");

        assertTrue(methodBody(source, "public void onFileSaved")
                .contains("makeFileReadable(file)"));
        assertFalse(methodBody(source, "public void onRecordingFileSaved")
                .contains("makeFilesReadable("));
        assertFalse(methodBody(source, "public void onSurveillanceFileSaved")
                .contains("makeFilesReadable("));
        assertFalse(methodBody(source, "public void onProximityFileSaved")
                .contains("makeFilesReadable("));
        assertFalse(methodBody(source, "public void onTripFileSaved")
                .contains("makeFilesReadable("));
    }

    @Test
    public void startupStillRepairsLegacyFilePermissions() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/storage/StorageManager.java");

        assertTrue(methodBody(source, "public void fixAllPermissions")
                .contains("makeFilesReadable(recordingsDir)"));
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