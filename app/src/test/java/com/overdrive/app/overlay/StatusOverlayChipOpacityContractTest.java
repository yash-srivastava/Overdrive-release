package com.overdrive.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Chip visibility + live preview follow-up (icon transparency preserved). */
public class StatusOverlayChipOpacityContractTest {

    @Test
    public void chipOpacityStoredRangeTenToOneHundred() {
        assertEquals(10, StatusOverlayChipOpacity.clampStored(0));
        assertEquals(100, StatusOverlayChipOpacity.clampStored(150));
        assertEquals(20, StatusOverlayChipOpacity.clampStored(20));
    }

    @Test
    public void layoutKeepsIconTransparencySliderAndDirectChipRange() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout/fragment_settings_overlay.xml");
        assertTrue(layout.contains("sliderOverlayChipOpacity"));
        assertTrue(layout.contains("android:valueFrom=\"10\""));
        assertTrue(layout.contains("android:valueTo=\"100\""));
        assertTrue(layout.contains("sliderOverlayIconOpacity"));
        assertTrue(layout.contains("tvOverlayIconOpacity"));
    }

    @Test
    public void serviceReadsIconOpacityAndSupportsChipPreview() throws Exception {
        String service = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        assertTrue(service.contains("optInt(\"iconOpacity\""));
        assertTrue(service.contains("previewChipOpacity"));
        assertTrue(service.contains("clearChipOpacityPreview"));
        assertTrue(service.contains("applyIconAppearance(iconOpacity"));
    }

    @Test
    public void settingsBindsIconTransparencyUnchanged() throws Exception {
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/settings/SettingsOverlayFragment.kt");
        assertTrue(fragment.contains("sliderOverlayIconOpacity"));
        assertTrue(fragment.contains("persistInt(\"iconOpacity\""));
        assertTrue(fragment.contains("chipOpacitySaveGeneration"));
        assertTrue(fragment.contains("previewChipOpacity"));
    }

    @Test
    public void englishKeepsIconTransparencyStrings() throws Exception {
        String en = readRepositoryFile("app/src/main/res/values/strings.xml");
        assertTrue(en.contains("settings_overlay_icon_opacity\">Icon transparency"));
        assertTrue(en.contains("Chip visibility"));
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
