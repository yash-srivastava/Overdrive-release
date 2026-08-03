package com.overdrive.app.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the bridge that keeps the native toolbar aligned with charging tabs. */
public class ChargingNativeToolbarTitleAssetTest {

    @Test
    public void chargingTabTitleIsForwardedToTheNativeToolbar() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/charging.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/charging.js");
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/WebViewFragment.kt");
        String activity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/MainActivity.kt");

        assertTrue(html.contains("charging.js?v="));
        assertTrue(script.contains("tab.classList.contains('bottom-tab')"));
        assertTrue(script.contains("self._syncTabPresentation(tabId)"));
        assertTrue(script.contains("AndroidBridge.setPageTitle(title)"));
        assertTrue(bridge.contains("fun setPageTitle(title: String)"));
        assertTrue(bridge.contains("title.trim().take(80)"));
        assertTrue(bridge.contains("host.setWebPageTitle(normalized)"));
        assertTrue(activity.contains("fun setWebPageTitle(title: String)"));
        assertTrue(activity.contains("toolbar.title = title"));
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
