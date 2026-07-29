package com.overdrive.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the Vehicle screen's cloud availability and partial-door-state UI. */
public class VehicleControlAvailabilityAssetTest {

    @Test
    public void dynamicStatusLabelsSurviveI18nHydration() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertTrue(html.contains("<span id=\"lockStatus\">Unknown</span>"));
        assertTrue(html.contains("<span id=\"cloudStatusText\">Checking...</span>"));
        assertFalse(html.contains("id=\"lockStatus\" data-i18n="));
        assertFalse(html.contains("id=\"cloudStatusText\" data-i18n="));
        assertTrue(script.contains("self.updateCloudIndicator();"));
        assertTrue(script.contains("self.updateCloudControlAvailability();"));
        assertTrue(script.contains("cloudState = 'unavailable'"));
        assertTrue(script.contains("startCloudStatusSync"));
    }

    @Test
    public void partialDoorReadingIsNotPresentedAsWholeVehicleState() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        String english = readRepositoryFile("app/src/main/assets/web/i18n/en.json");

        assertTrue(api.contains("doors.put(\"scope\", \"driver_door\")"));
        assertTrue(api.contains("doors.put(\"scope\", \"vehicle\")"));
        assertTrue(script.contains("d.source === 'ota' ? 'driver_door' : 'vehicle'"));
        assertTrue(script.contains("scope === 'driver_door'"));
        assertTrue(script.contains("vehicle.driver_door_unlocked"));
        assertTrue(english.contains("\"driver_door_unlocked\": \"Driver door unlocked\""));
    }

    @Test
    public void cloudOnlyControlsAreMarkedWithoutDisablingLocalControls() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/vehicle-control.html");
        String css = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.css");
        String script = readRepositoryFile("app/src/main/assets/web/shared/vehicle-control.js");

        assertEquals(8, count(html, "data-requires-cloud=\"true\""));
        assertTrue(html.contains(
                "id=\"btnLock\" title=\"Lock\" data-requires-cloud=\"true\""));
        assertTrue(html.contains(
                "id=\"btnTrunkOpen\" title=\"Open Trunk\" data-requires-cloud=\"true\""));
        assertFalse(html.contains(
                "id=\"btnTrunkClose\" title=\"Close Trunk\" data-requires-cloud=\"true\""));
        assertFalse(html.contains(
                "id=\"btnDRL\" title=\"Daytime running lights\" data-requires-cloud=\"true\""));
        assertTrue(css.contains(".vc-tile[data-requires-cloud=\"true\"]::after"));
        assertTrue(css.contains(".vc-tile[data-cloud-state=\"not_configured\"]"));
        assertTrue(script.contains("querySelectorAll('[data-requires-cloud=\"true\"]')"));
        assertTrue(script.contains("this.showCloudModal();"));
    }

    private static int count(String text, String needle) {
        int result = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            result++;
            from += needle.length();
        }
        return result;
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
