package com.overdrive.app.monitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Reads CPU clocks across the available cpufreq policies, not just cpu0.
 *
 * Android 10 Qualcomm parts such as the BYD Dolphin's trinket expose separate
 * silver/gold policies (1804.8/2016 MHz ceilings) — not one CPU0 clock.
 * Reading only cpu0 silently understates the head unit's peak CPU frequency.
 * Cache the sysfs topology and refresh it periodically while the Performance
 * page is sampling, so initially missing domains can be discovered later.
 * No governor or affinity is changed.
 */
final class CpuFrequencySampler {
    private static final long TOPOLOGY_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final File cpuRoot;
    private final LongSupplier nanoTime;
    private File[] frequencyRoots;
    private long lastTopologyScanNanos;
    private int cachedHardwareMaxMhz;
    private boolean hardwareMaxSettled;

    CpuFrequencySampler() {
        this(new File("/sys/devices/system/cpu"));
    }

    // Package-private injection point for source-level regression fixtures.
    CpuFrequencySampler(File cpuRoot) {
        this(cpuRoot, System::nanoTime);
    }

    CpuFrequencySampler(File cpuRoot, LongSupplier nanoTime) {
        this.cpuRoot = cpuRoot;
        this.nanoTime = nanoTime;
    }

    int currentPeakMhz() {
        return maxFrequencyMhz("scaling_cur_freq", "cpuinfo_cur_freq");
    }

    int hardwarePeakMhz() {
        File[] roots = frequencyRoots();
        if (hardwareMaxSettled) return cachedHardwareMaxMhz;
        // An offline cluster has no readable ceiling yet: keep the highest seen and
        // re-read until every frequency domain reports cpuinfo_max_freq.
        boolean settled = true;
        long peakKhz = 0;
        for (File root : roots) {
            long khz = readPositiveKhz(new File(root, "cpuinfo_max_freq"));
            if (khz <= 0) {
                settled = false;
                khz = readPositiveKhz(new File(root, "scaling_max_freq"));
            }
            peakKhz = Math.max(peakKhz, khz);
        }
        cachedHardwareMaxMhz = Math.max(cachedHardwareMaxMhz, toMhz(peakKhz));
        hardwareMaxSettled = settled && cachedHardwareMaxMhz > 0;
        return cachedHardwareMaxMhz;
    }

    private int maxFrequencyMhz(String preferred, String fallback) {
        long peakKhz = 0;
        for (File root : frequencyRoots()) {
            long khz = readPositiveKhz(new File(root, preferred));
            if (khz <= 0) khz = readPositiveKhz(new File(root, fallback));
            peakKhz = Math.max(peakKhz, khz);
        }
        return toMhz(peakKhz);
    }

    private static int toMhz(long khz) {
        return khz > 0 ? (int) Math.min(Integer.MAX_VALUE, khz / 1000L) : 0;
    }

    private File[] frequencyRoots() {
        long now = nanoTime.getAsLong();
        if (frequencyRoots != null && now - lastTopologyScanNanos < TOPOLOGY_REFRESH_NANOS) {
            return frequencyRoots;
        }
        lastTopologyScanNanos = now;
        File[] discovered;
        // A policy corresponds to a frequency domain, so reading policy0 and
        // policy4 avoids eight duplicate per-core sysfs reads on the Dolphin.
        File[] policies = new File(cpuRoot, "cpufreq").listFiles(file ->
                file.isDirectory() && file.getName().matches("policy[0-9]+"));
        if (policies != null && policies.length > 0) {
            discovered = policies;
        } else {
            // Older vendor kernels expose only per-core cpufreq paths, and only
            // while the core is online, so keep every core.
            File[] cores = cpuRoot.listFiles(file ->
                    file.isDirectory() && file.getName().matches("cpu[0-9]+"));
            if (cores == null || cores.length == 0) {
                discovered = new File[]{new File(cpuRoot, "cpu0/cpufreq")};
            } else {
                discovered = new File[cores.length];
                for (int i = 0; i < cores.length; i++) {
                    discovered[i] = new File(cores[i], "cpufreq");
                }
            }
        }
        // Per-core directories all end in "cpufreq"; sort by full path so
        // filesystem enumeration order cannot invalidate the cached ceiling.
        Arrays.sort(discovered, Comparator.comparing(File::getPath));
        if (!Arrays.equals(frequencyRoots, discovered)) {
            frequencyRoots = discovered;
            // A newly visible domain may have a higher hardware ceiling.
            // Keep the highest previously observed ceiling across hotplug.
            hardwareMaxSettled = false;
        }
        return frequencyRoots;
    }

    private static long readPositiveKhz(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            if (value == null) return -1;
            long khz = Long.parseLong(value.trim());
            return khz > 0 ? khz : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }
}
