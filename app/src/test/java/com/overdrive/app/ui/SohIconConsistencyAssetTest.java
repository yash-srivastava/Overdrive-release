package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
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

/** Keeps State-of-Health presentation on the same heart symbol across native and web UI. */
public class SohIconConsistencyAssetTest {

    private static final String WEB_HEART_PATH =
            "M12 21.35 10.55 20.03C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35Z";

    @Test
    public void semanticNativeHealthIconReusesExistingFavoriteHeart() throws Exception {
        String health = readRepositoryFile("app/src/main/res/drawable/ic_battery_health.xml");
        String favorite = readRepositoryFile("app/src/main/res/drawable/ic_favorite.xml");

        assertEquals(pathData(favorite), pathData(health));
        assertTrue(health.contains("Material Symbols Rounded: favorite"));
        assertFalse(health.contains("battery_charging_full"));
    }

    @Test
    public void everyNativeBatteryHealthSurfaceUsesSemanticHeartResource() throws Exception {
        String dashboard = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_dashboard.xml");
        String diagnosticsLandscape = readRepositoryFile(
                "app/src/main/res/layout-land/fragment_diagnostics.xml");
        String diagnosticsPortrait = readRepositoryFile(
                "app/src/main/res/layout/fragment_diagnostics.xml");
        String dialog = readRepositoryFile(
                "app/src/main/res/layout/dialog_battery_health.xml");

        assertTrue(dashboard.contains("@drawable/ic_battery_health"));
        assertTrue(diagnosticsLandscape.contains("@drawable/ic_battery_health"));
        assertTrue(diagnosticsPortrait.contains("@drawable/ic_battery_health"));
        assertTrue(dialog.contains("@drawable/ic_battery_health"));
    }

    @Test
    public void webBatteryHealthAndSohCardsUseMatchingFilledHearts() throws Exception {
        String performance = readRepositoryFile(
                "app/src/main/assets/web/local/performance.html");

        assertEquals(2, countOccurrences(performance, "class=\"soh-heart-icon\""));
        assertEquals(2, countOccurrences(performance, WEB_HEART_PATH));
    }

    private static String pathData(String vector) {
        Matcher matcher = Pattern.compile("android:pathData=\"([^\"]+)\"").matcher(vector);
        if (!matcher.find()) throw new AssertionError("Vector has no pathData");
        return matcher.group(1);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
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
