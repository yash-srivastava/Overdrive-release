package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the responsive Charging sessions redesign and its shared chip contract. */
public class ChargingLayoutAssetTest {

    @Test
    public void sessionsPageUsesTheRichResponsiveStructure() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");

        assertTrue(html.contains("class=\"charging-page-heading\""));
        assertTrue(html.contains("class=\"filter-tabs charge-segmented-control"));
        assertTrue(html.contains("class=\"ch-stat ch-stat--sessions app-surface-card\""));
        assertTrue(html.contains("class=\"ch-stat ch-stat--energy app-surface-card\""));
        assertTrue(html.contains("class=\"ch-stat ch-stat--cost app-surface-card\""));
        assertTrue(html.contains("class=\"ch-stat ch-stat--type app-surface-card\""));
        assertTrue(html.contains("class=\"ch-stat ch-stat--range app-surface-card\""));
        assertTrue(html.contains("class=\"session-list-head\""));
        assertTrue(html.contains("id=\"sessionSort\""));
        assertTrue(html.contains("grid-template-areas: \"primary start duration range context actions\""));
        assertTrue(html.contains("grid-template-areas: \"primary primary\""));
        assertTrue(html.contains("charging.js?v="));
    }

    @Test
    public void sessionRowsReuseSharedChipsAndOnlyShowRealData() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/charging.js");

        assertTrue(script.contains("session-type-' + kind + ' app-chip"));
        assertTrue(script.contains("session-power-chip app-chip"));
        assertTrue(script.contains("session-live app-chip compact-status-pill"));
        assertTrue(script.contains("app-chip__dot compact-status-pill__dot"));
        assertTrue(script.contains("session-soc-track"));
        assertTrue(script.contains("session-metric--start"));
        assertTrue(script.contains("session-metric--duration"));
        assertTrue(script.contains("session-metric--range"));
        assertTrue(script.contains("session-metric--context"));
        assertTrue(script.contains("s.rangeGained != null && s.rangeGained > 0"));
        assertTrue(script.contains("sortOrder === 'oldest'"));
        assertTrue(script.contains("currentDays: 7"));
        assertFalse(script.toLowerCase().contains("estimated savings"));
    }

    @Test
    public void sharedChipSpacingSupportsTheLegacyCarWebView() throws IOException {
        String styles = readRepositoryFile("app/src/main/assets/web/shared/styles.css");
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");

        assertTrue(styles.contains(".app-chip > * + *"));
        assertTrue(styles.contains("margin-left: var(--app-chip-gap, 8px)"));
        assertTrue(styles.contains("[dir=\"rtl\"] .app-chip > * + *"));
        assertTrue(html.contains(".session-live > * + *"));
        assertFalse(ruleFor(html, ".session-live").contains("gap:"));
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
