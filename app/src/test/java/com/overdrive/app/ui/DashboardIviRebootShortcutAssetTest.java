package com.overdrive.app.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Pins the dashboard shortcut to Vehicle Control's safe IVI reboot contract. */
public class DashboardIviRebootShortcutAssetTest {

    @Test
    public void dashboardRebootUsesConfirmationAndParkedOnlyApi() throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/index.html");

        assertTrue(page.contains("id=\"dashIviReboot\""));
        assertTrue(page.contains("data-i18n=\"vehicle.ivi_reboot\""));
        assertTrue(page.contains("rebootIvi: function(btn)"));
        assertTrue(page.contains("BYD.utils.confirmDialog"));
        assertTrue(page.contains("danger: true"));
        assertTrue(page.contains("if (!confirmed) return;"));
        assertTrue(page.contains("fetch('/api/system/ivi-reboot', {"));
        assertTrue(page.contains("JSON.stringify({ confirm: 'REBOOT' })"));
        assertTrue(page.contains("btn.disabled = true"));
        assertTrue(page.contains("btn.disabled = false"));
    }

    @Test
    public void dashboardRebootRespectsAvailabilityAndStaleState() throws IOException {
        String page = readRepositoryFile("app/src/main/assets/web/local/index.html");

        assertTrue(page.contains("updateIviRebootAvailability: function(available)"));
        assertTrue(page.contains("data.iviRebootAvailable === true"));
        assertTrue(page.contains("data.dilink5 === true"));
        assertTrue(page.contains("this.updateIviRebootAvailability(false)"));
        assertTrue(page.contains("this.layoutPills()"));
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
