package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * One no-data convention across the restyled pages: a placeholder while the value is unanswered,
 * then an em dash for a value that came back empty. A page that settles on {@code --}, a bare
 * {@code -} or a stand-in zero reads as a different product from the one beside it.
 */
public class NoDataConventionAssetTest {

    /** Pages whose value slots must settle on an em dash. */
    private static final String[] WEB_PAGES = {
            "app/src/main/assets/web/local/vehicle-control.html",
            "app/src/main/assets/web/local/seat-positions.html",
            "app/src/main/assets/web/local/live-view.html",
            "app/src/main/assets/web/shared/vehicle-control.js",
            "app/src/main/assets/web/shared/seat-positions.js",
            "app/src/main/assets/web/shared/map.js",
    };

    /** Double-hyphen forms, excluding HTML comment fences. */
    private static final String[] STALE_GLYPHS = {
            ">--<", ">--%", ">-- ", "'--'", "\"--\"", "'--%'", "'-- kPa'", "'-- PSI'",
    };

    @Test
    public void webValueSlotsUseTheEmDash() throws IOException {
        for (String path : WEB_PAGES) {
            String source = readRepositoryFile(path);
            for (String glyph : STALE_GLYPHS) {
                assertFalse(path + " still renders " + glyph, source.contains(glyph));
            }
        }
    }

    @Test
    public void nativeValueSlotsUseTheEmDash() throws IOException {
        String strings = readRepositoryFile("app/src/main/res/values/strings.xml");
        for (String name : new String[]{
                "dashboard_metric_value_pending",
                "recordings_summary_pending",
                "recording_preview_not_available"}) {
            assertTrue(name, strings.contains(
                    "<string name=\"" + name + "\">\u2014</string>"));
        }

        // The storage line is a value slot, so its unanswered state is the dash, not prose.
        for (String path : new String[]{
                "app/src/main/res/layout/fragment_dashboard.xml",
                "app/src/main/res/layout-land/fragment_dashboard.xml"}) {
            String layout = readRepositoryFile(path);
            assertTrue(path, layout.contains("android:id=\"@+id/metricStorageValue\""));
            assertFalse(path, layout.contains("@string/dashboard_modern_updating\"\n"
                    + "                        android:textAppearance"));
        }

        // A clip with no readable duration or storage type reads as unknown, not as 0:00.
        String recordings = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/RecordingsFragment.kt");
        assertFalse(recordings.contains("R.string.player_time_zero"));
        assertFalse(recordings.contains("R.string.recording_preview_storage_unknown"));
    }

    /**
     * M3 draws a stop dot at the end of a determinate track. The theme kills it once so no layout
     * has to remember, and no layout may opt back in.
     */
    @Test
    public void progressBarsCarryNoStopIndicator() throws IOException {
        String theme = readRepositoryFile("app/src/main/res/values/themes_overdrive.xml");
        assertTrue(theme.contains(
                "<item name=\"linearProgressIndicatorStyle\">"
                        + "@style/Widget.Overdrive.M3.LinearProgress</item>"));
        assertTrue(theme.contains("<item name=\"trackStopIndicatorSize\">0dp</item>"));

        Pattern size = Pattern.compile("app:trackStopIndicatorSize=\"([^\"]+)\"");
        for (Path layout : layoutFiles()) {
            String source = new String(Files.readAllBytes(layout), StandardCharsets.UTF_8);
            if (!source.contains("LinearProgressIndicator")) continue;
            Matcher matcher = size.matcher(source);
            while (matcher.find()) {
                assertTrue(layout + " re-enables the stop dot",
                        "0dp".equals(matcher.group(1)));
            }
        }
    }

    private static Iterable<Path> layoutFiles() throws IOException {
        Path res = repositoryRoot().resolve("app/src/main/res");
        try (java.util.stream.Stream<Path> paths = Files.walk(res)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .filter(p -> p.getParent().getFileName().toString().startsWith("layout"))
                    .collect(java.util.stream.Collectors.toList());
        }
    }

    private static Path repositoryRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("app/src/main/res"))) return current;
            current = current.getParent();
        }
        throw new AssertionError("Could not locate the repository root");
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path candidate = repositoryRoot().resolve(relativePath);
        if (!Files.isRegularFile(candidate)) {
            throw new AssertionError("Could not locate repository file: " + relativePath);
        }
        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
    }
}
