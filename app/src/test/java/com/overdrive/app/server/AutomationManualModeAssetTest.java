package com.overdrive.app.server;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins the web/API wiring for the automatic/manual/disabled selector. */
public class AutomationManualModeAssetTest {

    @Test
    public void automationUiExposesAndPersistsManualMode() throws IOException {
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/automations.js");
        String css = readRepositoryFile(
                "app/src/main/assets/web/shared/automations.css");
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/automations.html");
        String english = readRepositoryFile(
                "app/src/main/assets/web/i18n/en.json");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/AutomationApiHandler.java");

        assertTrue(script.contains(
                "for (const mode of ['automatic', 'manual', 'disabled'])"));
        assertTrue(script.contains("automation && automation.manualOnly"));
        assertTrue(script.contains(
                "fetch('/api/automations/mode/' + encodeURIComponent(key)"));
        assertTrue(script.contains("grid.append(this.createModeField());"));
        assertTrue(css.contains("#automationList .status-dot.manual"));
        assertTrue(css.contains(".automation-mode-select"));
        assertTrue(english.contains("\"mode_manual\": \"Manual only\""));
        assertTrue(api.contains("/api/automations/mode/"));
        assertTrue(html.contains("automations.css?v=av56"));
        assertTrue(html.contains("automations.js?v=av78"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
