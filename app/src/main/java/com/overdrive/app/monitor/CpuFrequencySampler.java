package com.overdrive.app.monitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Reads CPU clocks across the available cpufreq policies, not just cpu0.
 *
 * Android 10 Qualcomm parts such as the BYD Dolphin's trinket expose separate
 * silver/gold policies (1804.8/2016 MHz ceilings) — not one CPU0 clock.
 * Reading only cpu0 silently understates the head unit's peak CPU frequency.
 * Enumerate the stable sysfs topology once; only cheap value reads run on the
 * on-demand Performance page's sampler. No governor or affinity is changed.
 */
final class CpuFrequencySampler {
    private final File cpuRoot;
    private File[] frequencyRoots;
    private int cachedHardwareMaxMhz;
    private boolean hardwareMaxSettled;

    CpuFrequencySampler() {
        this(new File("/sys/devices/system/cpu"));
    }

    // Package-private injection point for source-level regression fixtures.
    CpuFrequencySampler(File cpuRoot) {
        this.cpuRoot = cpuRoot;
    }

    int currentPeakMhz() {
        return maxFrequencyMhz("scaling_cur_freq", "cpuinfo_cur_freq");
    }

    int hardwarePeakMhz() {
        if (hardwareMaxSettled) return cachedHardwareMaxMhz;
        // An offline cluster has no readable ceiling yet: keep the highest seen and
        // re-read until every frequency domain reports cpuinfo_max_freq.
        boolean settled = true;
        long peakKhz = 0;
        for (File root : frequencyRoots()) {
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
        if (frequencyRoots != null) return frequencyRoots;
        // A policy corresponds to a frequency domain, so reading policy0 and
        // policy4 avoids eight duplicate per-core sysfs reads on the Dolphin.
        File[] policies = new File(cpuRoot, "cpufreq").listFiles(file ->
                file.isDirectory() && file.getName().matches("policy[0-9]+"));
        if (policies != null && policies.length > 0) {
            frequencyRoots = policies;
        } else {
            // Older vendor kernels expose only per-core cpufreq paths, and only
            // while the core is online, so keep every core.
            File[] cores = cpuRoot.listFiles(file ->
                    file.isDirectory() && file.getName().matches("cpu[0-9]+"));
            if (cores == null || cores.length == 0) {
                frequencyRoots = new File[]{new File(cpuRoot, "cpu0/cpufreq")};
            } else {
                frequencyRoots = new File[cores.length];
                for (int i = 0; i < cores.length; i++) {
                    frequencyRoots[i] = new File(cores[i], "cpufreq");
                }
            }
        }
        Arrays.sort(frequencyRoots, Comparator.comparing(File::getName));
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
