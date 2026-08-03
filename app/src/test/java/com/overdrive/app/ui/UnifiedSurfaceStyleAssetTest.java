package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards shared card styling and encoding-safe in-car text. */
public class UnifiedSurfaceStyleAssetTest {

    @Test
    public void chargingCardsUseTheSharedSurfaceShell() throws IOException {
        String styles = readRepositoryFile("app/src/main/assets/web/shared/styles.css");
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/charging.js");

        assertTrue(styles.contains(".app-surface-card {"));
        assertTrue(styles.contains("background: var(--bg-surface);"));
        assertTrue(html.contains("ch-hero-card ch-hero-card--soc app-surface-card"));
        assertTrue(html.contains("ch-card stats-soc-card app-surface-card"));
        assertTrue(html.contains("ch-stat ch-stat--sessions app-surface-card"));
        assertTrue(html.contains("ch-detail-panel ch-detail-overview app-surface-card"));
        assertTrue(script.contains(
                "session-card app-surface-card app-surface-card--interactive"));

        assertFalse(html.contains(
                "linear-gradient(135deg, var(--bg-surface), var(--bg-elevated))"));
        assertFalse(html.contains(
                "linear-gradient(145deg, var(--bg-surface), rgba(22, 33, 44, .76))"));
        assertFalse(html.contains(
                "linear-gradient(105deg, var(--bg-surface), rgba(20, 33, 44, .78))"));
    }

    @Test
    public void recordingMetadataUsesAnEncodingSafeSeparator() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/util/RecordingUiText.kt");

        assertTrue(source.contains("joinToString(\" \\u00B7 \")"));
        assertFalse(source.contains("\u00C2\u00B7"));
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
