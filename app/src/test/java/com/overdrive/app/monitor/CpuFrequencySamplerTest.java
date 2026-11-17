package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class CpuFrequencySamplerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static void write(File dir, String name, String value) throws Exception {
        Files.write(new File(dir, name).toPath(), value.getBytes(StandardCharsets.US_ASCII));
    }

    @Test public void distinctPoliciesReportGoldClusterWithoutDuplicateCoreReads() throws Exception {
        File cpufreq = temp.newFolder("cpufreq");
        File silver = new File(cpufreq, "policy0");
        File gold = new File(cpufreq, "policy4");
        silver.mkdir();
        gold.mkdir();
        write(silver, "scaling_cur_freq", "1420800\n");
        write(silver, "cpuinfo_max_freq", "1804800\n");
        write(gold, "scaling_cur_freq", "2016000\n");
        write(gold, "cpuinfo_max_freq", "2016000\n");
        CpuFrequencySampler sampler = new CpuFrequencySampler(temp.getRoot());
        assertEquals(2016, sampler.currentPeakMhz());
        assertEquals(2016, sampler.hardwarePeakMhz());
    }

    @Test public void oldPerCoreLayoutAndFallbackRemainSupported() throws Exception {
        File silver = new File(temp.getRoot(), "cpu0/cpufreq");
        File gold = new File(temp.getRoot(), "cpu4/cpufreq");
        silver.mkdirs();
        gold.mkdirs();
        write(silver, "cpuinfo_cur_freq", "1056000\n");
        write(silver, "scaling_max_freq", "1804800\n");
        write(gold, "scaling_cur_freq", "1804800\n");
        write(gold, "cpuinfo_max_freq", "2016000\n");
        CpuFrequencySampler sampler = new CpuFrequencySampler(temp.getRoot());
        assertEquals(1804, sampler.currentPeakMhz());
        assertEquals(2016, sampler.hardwarePeakMhz());
    }

    @Test public void transientMissingValuesRetryWithoutInvalidClocks() throws Exception {
        File policy = new File(temp.newFolder("cpufreq"), "policy0");
        policy.mkdir();
        write(policy, "scaling_cur_freq", "bad\n");
        CpuFrequencySampler sampler = new CpuFrequencySampler(temp.getRoot());
        assertEquals(0, sampler.currentPeakMhz());
        assertEquals(0, sampler.hardwarePeakMhz());
        write(policy, "scaling_cur_freq", "465000\n");
        write(policy, "cpuinfo_max_freq", "1804800\n");
        assertEquals(465, sampler.currentPeakMhz());
        assertEquals(1804, sampler.hardwarePeakMhz());
    }
}
