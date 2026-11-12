package com.overdrive.app.safekeep.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Regression coverage for intermittent thermal-zone discovery and sensor read failures. */
public class ThermalTest {

    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void retriesMissingZonesOnlyAfterCooldown() throws Exception {
        File sys = temp.newFolder("thermal");
        long[] now = {100L};
        Thermal.Sampler sampler = new Thermal.Sampler(sys, () -> now[0]);

        assertTrue(Float.isNaN(sampler.readCelsius()));
        createZone(sys, "thermal_zone0", "soc-thermal", "73000");

        now[0] += Thermal.Sampler.RESCAN_INTERVAL_MS - 1L;
        assertTrue(Float.isNaN(sampler.readCelsius()));
        now[0]++;
        assertEquals(73f, sampler.readCelsius(), 0f);
    }

    @Test
    public void replacesUnreadableChosenZoneAfterCooldown() throws Exception {
        File sys = temp.newFolder("thermal");
        File selected = createZone(sys, "thermal_zone0", "cpu-thermal", "80000");
        createZone(sys, "thermal_zone1", "soc-thermal", "55000");
        long[] now = {1000L};
        Thermal.Sampler sampler = new Thermal.Sampler(sys, () -> now[0]);

        assertEquals(80f, sampler.readCelsius(), 0f);
        assertTrue(new File(selected, "temp").delete());
        now[0] += Thermal.Sampler.RESCAN_INTERVAL_MS - 1L;
        assertTrue(Float.isNaN(sampler.readCelsius()));
        now[0]++;
        assertEquals(55f, sampler.readCelsius(), 0f);
    }

    @Test
    public void healthySelectedZoneDoesNotTriggerNewScans() throws Exception {
        File sys = temp.newFolder("thermal");
        createZone(sys, "thermal_zone0", "cpu-thermal", "62000");
        long[] now = {1L};
        Thermal.Sampler sampler = new Thermal.Sampler(sys, () -> now[0]);

        assertEquals(62f, sampler.readCelsius(), 0f);
        createZone(sys, "thermal_zone1", "soc-thermal", "92000");
        now[0] += Thermal.Sampler.RESCAN_INTERVAL_MS * 3L;
        assertEquals(62f, sampler.readCelsius(), 0f);
    }

    @Test
    public void retriesAfterMonotonicClockResetInInjectedClock() throws Exception {
        File sys = temp.newFolder("thermal");
        long[] now = {100_000L};
        Thermal.Sampler sampler = new Thermal.Sampler(sys, () -> now[0]);
        assertTrue(Float.isNaN(sampler.readCelsius()));
        createZone(sys, "thermal_zone0", "soc-thermal", "44000");
        now[0] = 50L;
        assertEquals(44f, sampler.readCelsius(), 0f);
    }

    private static File createZone(File root, String name, String type, String reading) throws Exception {
        File zone = new File(root, name);
        assertTrue(zone.mkdir());
        write(new File(zone, "type"), type);
        write(new File(zone, "temp"), reading);
        return zone;
    }

    private static void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
