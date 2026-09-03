package com.overdrive.app.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class OverlayPermissionAssetContractTest {

    @Test
    public void nativePermissionSurfacesUseSharedAuthoritativeChecker()
            throws Exception {
        assertUsesChecker(
                "src/main/java/com/overdrive/app/overlay/SetupGuideDialog.java");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/ui/fragment/settings/"
                        + "RemoteCommunicationSettingsBinder.kt");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/overlay/MessageOverlayService.java");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/services/RemoteVoiceService.java");
        assertUsesChecker(
                "src/main/java/com/overdrive/app/roadsense/overlay/"
                        + "RoadSenseOverlayService.kt");
    }

    @Test
    public void setupDialogRefreshesPermissionAfterReturningFromSettings()
            throws Exception {
        String source = read(
                "src/main/java/com/overdrive/app/overlay/SetupGuideDialog.java");
        assertTrue(source.contains("addOnWindowFocusChangeListener"));
        assertTrue(source.contains("renderOverlayPermission(context, overlayStep)"));
    }

    @Test
    public void statusPollChecksPermissionOnlyBeforeAttachingNewWindows()
            throws Exception {
        String source = read(
                "src/main/java/com/overdrive/app/overlay/StatusOverlayService.java");
        assertTrue(source.contains("if (visible && !camCloseAttached"));
        assertTrue(source.contains("if (visible && !bsCloseAttached"));
    }

    private static void assertUsesChecker(String relativePath) throws Exception {
        String source = read(relativePath);
        assertTrue(relativePath, source.contains("OverlayPermissionChecker.isGranted"));
        assertFalse(relativePath, source.contains("Settings.canDrawOverlays("));
    }

    private static String read(String relativePath) throws Exception {
        Path direct = Paths.get(relativePath);
        Path path = Files.exists(direct)
                ? direct
                : Paths.get("app").resolve(relativePath);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
