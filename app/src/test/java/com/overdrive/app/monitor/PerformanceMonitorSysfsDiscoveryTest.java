package com.overdrive.app.monitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void cpuFrequencyUsesFastestOnlineCoreAcrossHeterogeneousClusters() throws Exception {
        File cpu0 = createCpu(temp.getRoot(), 0, "1804800", "1804800", null);
        File cpu4 = createCpu(temp.getRoot(), 4, "1056000", "2016000", "1");
        createCpu(temp.getRoot(), 7, "1804800", "2016000", "1");
        File cpu8 = createCpu(temp.getRoot(), 8, "2400000", "2400000", "0");

        File[] cpus = {cpu0, cpu4, new File(temp.getRoot(), "cpu7"), cpu8};
        assertEquals(1804, PerformanceMonitor.readHighestCpuFrequencyMhz(
                cpus, "scaling_cur_freq", "cpuinfo_cur_freq"));
        // Current clocks exclude offline cpu8, while hardware ceilings do not.
        assertEquals(2400, PerformanceMonitor.observeCpuMaxFrequencyMhz(
                0, cpus, temp.newFolder("empty-policies")));
    }

    @Test
    public void maxFrequencyUpdatesAfterOfflineBigClusterBecomesReadable() throws Exception {
        File cpu0 = createCpu(temp.getRoot(), 0, "1804800", "1804800", null);
        File cpu4 = new File(temp.getRoot(), "cpu4");
        assertTrue(cpu4.mkdir());
        write(new File(cpu4, "online"), "0");
        File[] cpus = {cpu0, cpu4};
        File policies = temp.newFolder("policies-hotplug");

        int first = PerformanceMonitor.observeCpuMaxFrequencyMhz(0, cpus, policies);
        assertEquals(1804, first);

        // The online check must NOT prevent reading its ceiling once available.
        File bigCpufreq = new File(cpu4, "cpufreq");
        assertTrue(bigCpufreq.mkdir());
        write(new File(bigCpufreq, "cpuinfo_max_freq"), "2016000");
        int second = PerformanceMonitor.observeCpuMaxFrequencyMhz(first, cpus, policies);
        assertEquals(2016, second);

        // A transiently unreadable sysfs node must not lower a known ceiling.
        assertTrue(new File(bigCpufreq, "cpuinfo_max_freq").delete());
        assertEquals(2016, PerformanceMonitor.observeCpuMaxFrequencyMhz(second, cpus, policies));
    }

    @Test
    public void policiesReportOfflineClusterCeilingWithoutPerCoreCpufreq() throws Exception {
        File cpu0 = createCpu(temp.getRoot(), 0, "1804800", "1804800", null);
        File offline = new File(temp.getRoot(), "cpu4");
        assertTrue(offline.mkdir());
        write(new File(offline, "online"), "0");
        File policies = temp.newFolder("cpufreq-policies");
        File bigPolicy = new File(policies, "policy4");
        assertTrue(bigPolicy.mkdir());
        write(new File(bigPolicy, "cpuinfo_max_freq"), "2016000");
        File invalidPolicy = new File(policies, "policy-not-an-id");
        assertTrue(invalidPolicy.mkdir());
        write(new File(invalidPolicy, "cpuinfo_max_freq"), "9999000");

        assertEquals(2016, PerformanceMonitor.observeCpuMaxFrequencyMhz(0,
                new File[] {cpu0, offline}, policies));
    }

    @Test
    public void cpuFrequencyFallsBackWhenPreferredNodeIsUnavailable() throws Exception {
        File cpu = new File(temp.getRoot(), "cpu2");
        File cpufreq = new File(cpu, "cpufreq");
        assertTrue(cpufreq.mkdirs());
        write(new File(cpufreq, "cpuinfo_cur_freq"), "1420800");

        assertEquals(1420, PerformanceMonitor.readHighestCpuFrequencyMhz(
                new File[] {cpu}, "scaling_cur_freq", "cpuinfo_cur_freq"));
    }

    private static File createCpu(File root, int index, String currentKHz,
            String maxKHz, String online) throws Exception {
        File cpu = new File(root, "cpu" + index);
        File cpufreq = new File(cpu, "cpufreq");
        assertTrue(cpufreq.mkdirs());
        write(new File(cpufreq, "scaling_cur_freq"), currentKHz);
        write(new File(cpufreq, "cpuinfo_max_freq"), maxKHz);
        if (online != null) write(new File(cpu, "online"), online);
        return cpu;
    }

    private static void write(File file, String text) throws Exception {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
