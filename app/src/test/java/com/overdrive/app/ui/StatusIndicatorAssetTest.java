package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards compact dot-and-label status pills against old-WebView flex-gap regressions. */
public class StatusIndicatorAssetTest {

    @Test
    public void sharedPillUsesExplicitDotMarginForLegacyWebView() throws IOException {
        String styles = readRepositoryFile("app/src/main/assets/web/shared/styles.css");

        assertTrue(styles.contains(".compact-status-pill > .compact-status-pill__dot"));
        // Scoped: "margin-right: 6px" also matches unrelated rules in this sheet.
        assertTrue(ruleFor(styles, ".compact-status-pill > .compact-status-pill__dot")
                .contains("margin-right: 6px"));
        assertTrue(styles.contains("[dir=\"rtl\"] .compact-status-pill"));
    }

    @Test
    public void compactStatusPillsReuseTheSharedPrimitive() throws IOException {
        String vehicle = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String performance = readRepositoryFile("app/src/main/assets/web/local/performance.html");

        assertTrue(vehicle.contains("vc-pill compact-status-pill"));
        // Lock pill starts grey/"Unknown" until the cloud probe answers.
        assertTrue(vehicle.contains("dot grey compact-status-pill__dot"));
        assertTrue(vehicle.contains("dot amber compact-status-pill__dot"));
        assertTrue(performance.contains("monitoring-status compact-status-pill"));
        assertTrue(performance.contains("monitoring-dot compact-status-pill__dot"));
    }

    @Test
    public void pagesDoNotLayerFlexGapOnTopOfSharedDotSpacing() throws IOException {
        String live = readRepositoryFile("app/src/main/assets/web/local/live-view.html");
        String vehicleStyles =
                readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");
        String performance = readRepositoryFile("app/src/main/assets/web/local/performance.html");

        assertFalse(ruleFor(live, ".connection-status").contains("gap:"));
        assertFalse(ruleFor(vehicleStyles, ".vc-pill .dot").contains("margin-right:"));
        assertFalse(ruleFor(performance, ".monitoring-status").contains("gap:"));
    }

    @Test
    public void vehicleStateUpdatesPreserveTheSharedDotClass() throws IOException {
        String vehicleScript =
                readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(vehicleScript.contains(
                "'dot compact-status-pill__dot ' +"));
        assertTrue(vehicleScript.contains(
                "dot.className = 'dot compact-status-pill__dot green'"));
        assertTrue(vehicleScript.contains(
                "dot.className = 'dot compact-status-pill__dot red'"));
        assertFalse(vehicleScript.contains("dot.className = 'dot green'"));
        assertFalse(vehicleScript.contains("dot.className = 'dot red'"));
    }

    private static String ruleFor(String css, String selector) {
        int start = css.indexOf(selector);
        if (start < 0) return "";
        int end = css.indexOf('}', start);
        return end < 0 ? css.substring(start) : css.substring(start, end + 1);
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
