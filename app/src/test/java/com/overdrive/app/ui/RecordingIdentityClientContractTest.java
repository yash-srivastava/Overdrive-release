package com.overdrive.app.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RecordingIdentityClientContractTest {

    @Test
    public void nativeDeletePrefersRecordingIdentity() throws IOException {
        String model = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/model/RecordingFile.kt");
        String client = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/util/RecordingsApiClient.kt");
        String scanner = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/util/RecordingScanner.kt");

        assertTrue(model.contains("val recordingId: String? = null"));
        assertTrue(client.contains("/api/recordings/id/${enc(id)}"));
        assertTrue(client.contains("recordingId = rec.optString(\"id\")"));
        assertTrue(scanner.contains("RecordingsApiClient.deleteRecording(recording)"));
    }

    @Test
    public void webMirrorsUseStableKeysAndServerActionUrls() throws IOException {
        byte[] shared = readRepositoryBytes("app/src/main/assets/web/shared/events.js");
        byte[] local = readRepositoryBytes("app/src/main/assets/web/local/shared/events.js");
        assertArrayEquals(shared, local);

        String script = new String(shared, StandardCharsets.UTF_8);
        assertTrue(script.contains("return rec && rec.id ? rec.id"));
        assertTrue(script.contains("data-recording-key="));
        assertTrue(script.contains("const deleteUrl = rec.deleteUrl"));
        assertTrue(script.contains("const eventUrl = recording.eventUrl"));
        assertTrue(script.contains("const payload = { ids: ids, filenames: filenames }"));
        assertFalse(script.contains("this.selectedFiles.add(rec.filename)"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        return new String(readRepositoryBytes(relativePath), StandardCharsets.UTF_8);
    }

    private static byte[] readRepositoryBytes(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return Files.readAllBytes(candidate);
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) return Files.readAllBytes(fromModule);
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}