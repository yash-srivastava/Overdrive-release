package com.overdrive.app.monitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

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

    private static void createZone(File root, int index) throws Exception {
        File zone = new File(root, "thermal_zone" + index);
        if (!zone.mkdir()) throw new AssertionError("Could not create " + zone);
    }
}
