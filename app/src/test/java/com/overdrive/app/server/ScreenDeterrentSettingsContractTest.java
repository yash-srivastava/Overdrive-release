package com.overdrive.app.server;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class ScreenDeterrentSettingsContractTest {

    @Test
    public void immediateSettingsReportPersistenceFailures() throws Exception {
        String server = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String web = readRepositoryFile(
                "app/src/main/assets/web/shared/surveillance.js");
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/surveillance.html");

        assertTrue(server.contains("screenDeterrentUpdates"));
        assertTrue(server.contains("if (!persisted)"));
        assertTrue(server.contains("Could not save screen deterrent settings"));
        assertTrue(web.contains(
                "var request = this._writeJson('/api/surveillance/config', body);"));
        assertTrue(web.contains("data.success !== true"));
        assertTrue(web.contains("_screenDeterrentFieldSequences"));
        assertTrue(web.contains("self.config[configKey] = self.savedConfig"));
        assertTrue(web.contains("_deterrentPreviewRequestId"));
        assertTrue(web.contains("previewVideo.muted = true"));
        assertTrue(html.contains("surveillance.js?v=survtoggle10"));
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
        throw new AssertionError("Could not locate " + relativePath);
    }
}
