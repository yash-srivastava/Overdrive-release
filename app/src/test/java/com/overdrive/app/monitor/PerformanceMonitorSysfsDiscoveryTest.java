package com.overdrive.app.monitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Regression coverage for Qualcomm/BYD kernels with more than 30 thermal zones. */
public class PerformanceMonitorSysfsDiscoveryTest {

    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void discoversAllPresentZonesIncludingIdsAboveLegacyScanLimit() throws Exception {
        File zones = temp.newFolder("thermal");
        createZone(zones, 0);
        createZone(zones, 12);
        createZone(zones, 21);
        createZone(zones, 41);
        createZone(zones, 47);
        new File(zones, "thermal_zone-not-a-number").mkdir();
        new File(zones, "thermal_zone-1").mkdir();
        new File(zones, "thermal_zone+2").mkdir();
        new File(zones, "thermal_zone2147483648").mkdir();
        new File(zones, "thermal_zone3invalid").mkdir();
        new File(zones, "not-a-thermal-zone").mkdir();

        assertArrayEquals(new int[] {0, 12, 21, 41, 47},
                PerformanceMonitor.discoverThermalZoneIndices(zones));
    }

    @Test
    public void malformedZonesDoNotInventZoneZero() throws Exception {
        File zones = temp.newFolder("thermal-without-zero");
        createZone(zones, 19);
        createZone(zones, 47);
        assertTrue(new File(zones, "thermal_zone-bogus").mkdir());
        assertTrue(new File(zones, "thermal_zone2147483648").mkdir());
        assertArrayEquals(new int[] {19, 47},
                PerformanceMonitor.discoverThermalZoneIndices(zones));
    }

    @Test
    public void returnsEmptyArrayWhenThermalSysfsIsUnavailable() throws Exception {
        File zones = temp.newFolder("missing-thermal");
        assertArrayEquals(new int[0],
                PerformanceMonitor.discoverThermalZoneIndices(zones));
    }

    @Test
    public void keepsIdentifiedZonesWithUnavailableTemperatures() throws Exception {
        File zones = temp.newFolder("thermal-readings");
        writeZone(zones, 4, "conn-therm-adc", null);
        writeZone(zones, 5, "skin-therm-adc", "");
        writeZone(zones, 12, "cpuss-0-usr", "56700");
        writeZone(zones, 21, "gpu-usr", "53500");
        writeZone(zones, 40, "display-lowf", "invalid");
        writeZone(zones, 41, "gpu-lowf", "NaN");
        writeZone(zones, 42, "cdsp-step", "Infinity");
        writeZone(zones, 43, "wlan-step", "0");
        writeZone(zones, 47, "quiet-therm-step", "47.341");
        createZone(zones, 48); // No type: not an identified sensor.

        JSONArray result = PerformanceMonitor.discoverThermalZones(
                zones, PerformanceMonitor.discoverThermalZoneIndices(zones));

        assertEquals(9, result.length());
        assertUnavailableZone(result.getJSONObject(0), 4, "conn-therm-adc");
        assertUnavailableZone(result.getJSONObject(1), 5, "skin-therm-adc");
        assertEquals(56.7, result.getJSONObject(2).getDouble("temp"), 0.001);
        assertEquals(53.5, result.getJSONObject(3).getDouble("temp"), 0.001);
        assertUnavailableZone(result.getJSONObject(4), 40, "display-lowf");
        assertUnavailableZone(result.getJSONObject(5), 41, "gpu-lowf");
        assertUnavailableZone(result.getJSONObject(6), 42, "cdsp-step");
        assertEquals(0.0, result.getJSONObject(7).getDouble("temp"), 0.001);
        assertEquals(47, result.getJSONObject(8).getInt("zone"));
        assertEquals(47.341, result.getJSONObject(8).getDouble("temp"), 0.001);
    }

    private static void assertUnavailableZone(JSONObject zone, int index, String type)
            throws Exception {
        assertEquals(index, zone.getInt("zone"));
        assertEquals(type, zone.getString("type"));
        assertTrue(zone.has("temp"));
        assertTrue(zone.isNull("temp"));
    }

    private static void writeZone(File root, int index, String type, String temperature)
            throws Exception {
        createZone(root, index);
        File zone = new File(root, "thermal_zone" + index);
        Files.write(new File(zone, "type").toPath(), type.getBytes(StandardCharsets.UTF_8));
        if (temperature != null) {
            Files.write(new File(zone, "temp").toPath(), temperature.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void createZone(File root, int index) throws Exception {
        File zone = new File(root, "thermal_zone" + index);
        if (!zone.mkdir()) throw new AssertionError("Could not create " + zone);
    }
}
