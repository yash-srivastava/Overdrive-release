package com.overdrive.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins chip visibility slider semantics after the post-#493 follow-up. */
public class StatusOverlayChipOpacityContractTest {

    @Test
    public void chipOpacitySnapMatchesMaterialStepGrid() {
        assertEquals(10f, StatusOverlayChipOpacity.snapSliderValue(10), 0f);
        assertEquals(100f, StatusOverlayChipOpacity.snapSliderValue(100), 0f);
        assertEquals(35f, StatusOverlayChipOpacity.snapSliderValue(37), 0f);
    }

    @Test
    public void chipOpacityRangeIsTenToOneHundred() {
        assertEquals(10, StatusOverlayChipOpacity.MIN);
        assertEquals(100, StatusOverlayChipOpacity.MAX);
        assertEquals(100, StatusOverlayChipOpacity.clampStored(150));
        assertEquals(10, StatusOverlayChipOpacity.clampStored(0));
        assertEquals(35, StatusOverlayChipOpacity.clampStored(35));
        // Legacy floor from the first release (20) remains valid.
        assertEquals(20, StatusOverlayChipOpacity.clampStored(20));
    }

    @Test
    public void settingsFragmentGuardsStaleChipSaveFromClearingPreview() throws Exception {
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/settings/SettingsOverlayFragment.kt");
        assertTrue(fragment.contains("chipOpacitySaveGeneration"));
        assertTrue(fragment.contains("chipOpacityDragInProgress"));
    }

    @Test
    public void settingsOverlayUsesDirectOpacitySliderNotTransparencyCap()
            throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout/fragment_settings_overlay.xml");
        assertTrue(layout.contains("android:id=\"@+id/sliderOverlayChipOpacity\""));
        assertTrue(layout.contains("android:valueFrom=\"10\""));
        assertTrue(layout.contains("android:valueTo=\"100\""));
        assertFalse(layout.contains("sliderOverlayIconOpacity"));
        assertFalse(layout.contains("tvOverlayIconOpacity"));
    }

    @Test
    public void serviceIgnoresLegacyIconOpacityAndSupportsLivePreview()
            throws Exception {
        String service = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        assertTrue(service.contains("previewChipOpacity"));
        assertTrue(service.contains("clearChipOpacityPreview"));
        assertTrue(service.contains("runOnOverlayMain"));
        assertTrue(service.contains("CHIP_OPACITY_MIN = 10"));
        assertTrue(service.contains(
                "applyIconAppearance(ICON_OPACITY_DEFAULT, iconSizeDp)"));
        assertFalse(service.contains("optInt(\"iconOpacity\""));
    }

    @Test
    public void englishAndHebrewLabelsDescribeVisibilityNotTransparency()
            throws Exception {
        String en = readRepositoryFile("app/src/main/res/values/strings.xml");
        String he = readRepositoryFile("app/src/main/res/values-he/strings.xml");
        assertTrue(en.contains("Chip visibility"));
        assertFalse(en.contains("settings_overlay_icon_opacity\">Icon transparency"));
        assertFalse(en.contains("10%%"));
        assertTrue(he.contains("נראות הצ"));
        assertFalse(he.contains("10%%"));
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
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
