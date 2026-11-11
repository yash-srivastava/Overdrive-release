package com.overdrive.app.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Live preview for icon opacity/size sliders (icon transparency range preserved). */
public class StatusOverlayIconAppearanceContractTest {

    @Test
    public void iconOpacityStoredTwentyToOneHundred() {
        assertEquals(20, StatusOverlayIconAppearance.clampStoredOpacity(0));
        assertEquals(100, StatusOverlayIconAppearance.clampStoredOpacity(150));
        assertEquals(55, StatusOverlayIconAppearance.storedOpacityFromSliderTransparency(45));
    }

    @Test
    public void iconSizeStoredTwelveToThirtyTwo() {
        assertEquals(12, StatusOverlayIconAppearance.clampStoredSizeDp(8));
        assertEquals(32, StatusOverlayIconAppearance.clampStoredSizeDp(40));
    }

    @Test
    public void serviceSupportsIconAppearancePreview() throws Exception {
        String service = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        assertTrue(service.contains("previewIconOpacity"));
        assertTrue(service.contains("previewIconSizeDp"));
        assertTrue(service.contains("clearIconAppearancePreview"));
        assertTrue(service.contains("iconOpacityPreview"));
    }

    @Test
    public void settingsPreviewIconSlidersWhileDragging() throws Exception {
        String fragment = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/settings/SettingsOverlayFragment.kt");
        assertTrue(fragment.contains("previewIconOpacity"));
        assertTrue(fragment.contains("previewIconSizeDp"));
        assertFalse(fragment.contains("persistInt(\"iconOpacity\""));
        assertTrue(fragment.contains("persistIconOpacity"));
        assertTrue(fragment.contains("clearIconAppearancePreview"));
    }

    @Test
    public void layoutKeepsIconTransparencySlider() throws Exception {
        String layout = readRepositoryFile(
                "app/src/main/res/layout/fragment_settings_overlay.xml");
        assertTrue(layout.contains("sliderOverlayIconOpacity"));
        assertTrue(layout.contains("sliderOverlayIconSize"));
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
