package com.overdrive.app.telemetry;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * DI5: the burn-in overlay's gear comes from GearMonitor's fresh DI5 sample, not the
 * gearbox-device getter, and an unknown gear is drawn as unknown rather than as the
 * collector's initial P.
 */
public class DiLink5OverlayGearContractTest {

    @Test
    public void collectorTakesTheDiLink5GearFromGearMonitor() throws Exception {
        String collector = read(
                "app/src/main/java/com/overdrive/app/telemetry/TelemetryDataCollector.java");
        assertTrue(collector.contains(
                "        if (diLink5) {\n"
                        + "            applyDiLink5Gear();\n"
                        + "            gearMode = lastGearMode;\n"
                        + "        } else if (gearboxDevice != null"));
        assertTrue(collector.contains(
                "sample = com.overdrive.app.monitor.GearMonitor.getInstance().getFreshSample();"));
        assertTrue(collector.contains(
                "lastGearReadElapsedRealtimeMs = sample.observedAtElapsedMs;"));
        // No fresh sample must clear validity, not keep the last gear.
        assertTrue(collector.contains(
                "        } else {\n"
                        + "            lastGearValid = false;\n"
                        + "        }"));
    }

    @Test
    public void rendererDrawsAnUnknownDiLink5GearAsUnknown() throws Exception {
        String overlay = read(
                "app/src/main/java/com/overdrive/app/telemetry/OverlayBitmapRenderer.java");
        assertTrue(overlay.contains(
                "if (isUnknownOnDiLink5(snap.gearValid, snap.gearReadElapsedRealtimeMs, snap)) {"));
        assertTrue(overlay.contains("c.drawText(\"-\", x, 54, gearPaint);"));
    }

    private static String read(String relative) throws Exception {
        Path path = Paths.get(System.getProperty("user.dir"), relative);
        if (!Files.exists(path)) {
            path = Paths.get(System.getProperty("user.dir")).getParent()
                    .resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
