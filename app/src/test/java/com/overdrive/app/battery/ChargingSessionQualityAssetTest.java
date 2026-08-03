package com.overdrive.app.battery;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Static contracts for charging-session evidence and presentation. */
public class ChargingSessionQualityAssetTest {
    @Test
    public void calibrationAndQualityEvidenceRemainVisibleAndServerOwned() throws Exception {
        String html = read("app/src/main/assets/web/local/charging.html");
        String js = read("app/src/main/assets/web/shared/charging.js");

        assertTrue(html.contains("calibration-badge"));
        assertTrue(html.contains("detailQualityWarning"));
        assertTrue(html.contains("margin: 10px 0 14px"));
        assertTrue(html.contains("charging.js?v=33"));
        assertTrue(js.contains("s.powerDataQuality === 'poisoned'"));
        assertTrue(js.contains("BYD.utils.alertDialog"));
        assertTrue(js.contains("quality-info-button"));
        assertFalse(js.contains("s.isDc === false && peak >= this.DC_KW"));
    }

    @Test
    public void lfpBadgeRequiresDeclaredPhysicalModelChemistry() throws Exception {
        String quality = read("app/src/main/java/com/overdrive/app/battery/ChargingSessionQuality.java");
        String modelApi = read("app/src/main/java/com/overdrive/app/server/ModelsApiHandler.java");
        String manifest = read("app/src/main/assets/web/shared/models/manifest.json");

        assertTrue(quality.contains("declaredChemistry"));
        assertTrue(quality.contains("if (!\"lfp\".equals(declared)) return \"unknown\""));
        assertTrue(modelApi.contains("getSelectedVehicleModelId()"));
        assertTrue(modelApi.contains("batteryChemistryForSelectedModel"));
        assertTrue(manifest.contains("\"batteryChemistryEvidence\""));
        assertTrue(modelApi.contains("BatteryChemistryMetadata.resolve(manifest, model)"));
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relative.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relative);
    }
}
