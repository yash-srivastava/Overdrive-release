package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.Test;

/** Static contracts for the surveillance safe-location editor. */
public class SafeLocationsUiAssetTest {

    @Test
    public void safeLocationsUseThemedControlsAndSupportEditing() throws IOException {
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/safe-locations.js");
        String styles = readRepositoryFile(
                "app/src/main/assets/web/shared/styles.css");
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/surveillance.html");
        String english = readRepositoryFile(
                "app/src/main/assets/web/i18n/en.json");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SafeLocationApiHandler.java");

        assertFalse(script.contains("prompt("));
        assertFalse(script.contains("window.confirm("));
        assertFalse(script.contains("if (!confirm("));
        assertFalse(script.contains("🟢"));
        assertFalse(script.contains("⚪"));
        assertFalse(script.contains("🗑"));

        assertTrue(script.contains("showZoneDialog(zone)"));
        assertTrue(script.contains("async editZone(id)"));
        assertTrue(script.contains("method: 'PUT'"));
        assertTrue(script.contains("BYD.utils.confirmDialog"));
        assertTrue(script.contains("popupName.textContent = zone.name"));
        assertTrue(api.contains("mgr.updateZone(id, req)"));

        assertTrue(styles.contains(".safe-zone-row"));
        assertTrue(styles.contains(".safe-zone-icon-btn"));
        assertTrue(styles.contains(".safe-zone-dialog"));
        assertTrue(html.contains("safe-locations.js?v=4"));
        assertTrue(html.contains("styles.css?v=38"));
        assertTrue(html.contains("data-i18n=\"safe_loc.saved_zones\""));

        assertTrue(english.contains("\"edit_title\": \"Edit safe zone\""));
        assertTrue(english.contains("\"zone_updated\": \"\\\"{name}\\\" updated\""));
        assertTrue(english.contains("\"delete_title\": \"Delete safe zone?\""));
    }

    @Test
    public void safeZoneStatusTranslationsDoNotEmbedEmojiIndicators() throws IOException {
        Path i18n = locate("app/src/main/assets/web/i18n");
        try (Stream<Path> files = Files.list(i18n)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .forEach(path -> {
                        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
                            lines.filter(line -> line.contains("\"state_safe_zone\"")
                                            || line.contains("\"state_safe_zone_named\"")
                                            || line.contains("\"in_safe_zone\"")
                                            || line.contains("\"outside_zone\"")
                                            || line.contains("\"you_are_here\""))
                                    .forEach(line -> {
                                        assertFalse(path + " must not embed a house emoji",
                                                line.contains("🏠"));
                                        assertFalse(path + " must not embed a green-dot emoji",
                                                line.contains("🟢"));
                                        assertFalse(path + " must not embed a red-dot emoji",
                                                line.contains("🔴"));
                                        assertFalse(path + " must not embed a pin emoji",
                                                line.contains("📍"));
                                    });
                        } catch (IOException error) {
                            throw new AssertionError(error);
                        }
                    });
        }
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path file = locate(relativePath);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static Path locate(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.exists(direct)) return direct;

            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.exists(fromModule)) return fromModule;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository path: " + relativePath);
    }
}
