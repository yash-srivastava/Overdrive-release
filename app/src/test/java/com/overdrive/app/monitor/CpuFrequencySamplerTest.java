package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test public void latePolicyRefreshesCurrentAndSettledHardwarePeak() throws Exception {
        File cpufreq = temp.newFolder("cpufreq");
        File silver = new File(cpufreq, "policy0");
        silver.mkdir();
        write(silver, "scaling_cur_freq", "1420800\n");
        write(silver, "cpuinfo_max_freq", "1804800\n");
        AtomicLong now = new AtomicLong();
        CpuFrequencySampler sampler = new CpuFrequencySampler(temp.getRoot(), now::get);
        assertEquals(1420, sampler.currentPeakMhz());
        assertEquals(1804, sampler.hardwarePeakMhz());

        File gold = new File(cpufreq, "policy4");
        gold.mkdir();
        write(gold, "scaling_cur_freq", "2016000\n");
        write(gold, "cpuinfo_max_freq", "2016000\n");
        now.set(TimeUnit.SECONDS.toNanos(29));
        assertEquals(1420, sampler.currentPeakMhz());
        assertEquals(1804, sampler.hardwarePeakMhz());

        now.set(TimeUnit.SECONDS.toNanos(30));
        // Hardware-only callers must refresh even after the ceiling was settled.
        assertEquals(2016, sampler.hardwarePeakMhz());
        assertEquals(2016, sampler.currentPeakMhz());

        // A disappearing domain must not lower the highest known hardware max.
        Files.delete(new File(gold, "scaling_cur_freq").toPath());
        Files.delete(new File(gold, "cpuinfo_max_freq").toPath());
        Files.delete(gold.toPath());
        now.set(TimeUnit.SECONDS.toNanos(60));
        assertEquals(1420, sampler.currentPeakMhz());
        assertEquals(2016, sampler.hardwarePeakMhz());
    }

    @Test public void discoversPoliciesAfterInitiallyEmptySysfs() throws Exception {
        AtomicLong now = new AtomicLong();
        CpuFrequencySampler sampler = new CpuFrequencySampler(temp.getRoot(), now::get);
        assertEquals(0, sampler.currentPeakMhz());
        assertEquals(0, sampler.hardwarePeakMhz());

        File gold = new File(temp.newFolder("cpufreq"), "policy4");
        gold.mkdir();
        write(gold, "scaling_cur_freq", "1804800\n");
        write(gold, "cpuinfo_max_freq", "2016000\n");
        now.set(TimeUnit.SECONDS.toNanos(30));
        assertEquals(1804, sampler.currentPeakMhz());
        assertEquals(2016, sampler.hardwarePeakMhz());
    }

    @Test public void shortAsciiReadsAndLegacyTextFallbackAgree() throws Exception {
        File policy = new File(temp.newFolder("cpufreq"), "policy0");
        policy.mkdir();
        File current = new File(policy, "scaling_cur_freq");
        write(policy, "cpuinfo_cur_freq", "1234000\n");
        CpuFrequencySampler sampler = new CpuFrequencySampler(temp.getRoot());
        String[] cases = {
                "2016000\n", "0001804800\r\n", "1804800\rgarbage",
                "465000", " +1056000 \n", "0\n", "-1000\n", "\n", "",
                "1234x\n", "1234\n9999", "9223372036854775807\n",
                "9223372036854775808\n", "99999999999999999999999999999999\n",
                "000000000000000000000000000000000000000000001056000\n",
                "                                1056000\n", "１２３４０００\n",
                "1234000\u0000\n", "1234\t5678\n"
        };
        for (String value : cases) {
            Files.write(current.toPath(), value.getBytes(StandardCharsets.UTF_8));
            assertEquals(value, referencePeak(current), sampler.currentPeakMhz());
        }
        Random random = new Random(4801L);
        for (int i = 0; i < 512; i++) {
            String value = Long.toString(random.nextLong());
            if ((i & 3) == 0) value = "  " + value + " ";
            if ((i & 7) != 0) value += "\n";
            write(policy, "scaling_cur_freq", value);
            assertEquals(value, referencePeak(current), sampler.currentPeakMhz());
        }
    }

    private static int referencePeak(File current) {
        try (BufferedReader reader = new BufferedReader(new FileReader(current))) {
            String line = reader.readLine();
            if (line != null) {
                long khz = Long.parseLong(line.trim());
                if (khz > 0) return (int) Math.min(Integer.MAX_VALUE, khz / 1000L);
            }
        } catch (Exception ignored) {
        }
        return 1234; // cpuinfo_cur_freq fallback
    }

}
