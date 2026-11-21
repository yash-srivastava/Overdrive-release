package com.overdrive.app.monitor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Aggregate {@code cpu } line parsing used by {@link PerformanceMonitor}'s system CPU sampler. */
public class PerformanceMonitorCpuStatParseTest {

    @Test
    public void parsesTypicalAggregateLine() {
        long[] out = new long[2];
        // user nice system idle iowait irq softirq steal guest guest_nice
        assertTrue(PerformanceMonitor.parseAggregateCpuTimes(
                "cpu  100 20 30 400 50 6 7 8 0 0", out));
        // total = 100+20+30+400+50+6+7 (steal excluded), idle = 400+50
        assertArrayEquals(new long[] {613L, 450L}, out);
    }

    @Test
    public void acceptsExactlySevenCounters() {
        long[] out = new long[2];
        assertTrue(PerformanceMonitor.parseAggregateCpuTimes("cpu 1 2 3 4 5 6 7", out));
        assertArrayEquals(new long[] {28L, 9L}, out);
    }

    @Test
    public void toleratesSingleAndRepeatedSpaces() {
        long[] out = new long[2];
        assertTrue(PerformanceMonitor.parseAggregateCpuTimes("cpu 1  2   3 4 5 6 7 8", out));
        assertArrayEquals(new long[] {28L, 9L}, out);
    }

    @Test
    public void rejectsPerCoreAndNonCpuLines() {
        long[] out = {-1L, -1L};
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("cpu0 1 2 3 4 5 6 7", out));
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("intr 1 2 3 4 5 6 7", out));
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("", out));
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes(null, out));
        assertArrayEquals(new long[] {-1L, -1L}, out);
    }

    @Test
    public void rejectsTruncatedLineWithoutTouchingOutput() {
        long[] out = {-1L, -1L};
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("cpu 1 2 3 4 5 6", out));
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("cpu ", out));
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("cpu", out));
        assertArrayEquals(new long[] {-1L, -1L}, out);
    }

    @Test
    public void rejectsNonNumericOrNegativeCounters() {
        long[] out = {-1L, -1L};
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("cpu 1 2 x 4 5 6 7", out));
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("cpu 1 2 3 -4 5 6 7", out));
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("cpu 1 2 3 4 5 6 7x", out));
        assertArrayEquals(new long[] {-1L, -1L}, out);
    }

    @Test
    public void rejectsOversizedCounterInsteadOfOverflowing() {
        long[] out = {-1L, -1L};
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes(
                "cpu 1 2 3 4 5 6 99999999999999999999", out));
        assertArrayEquals(new long[] {-1L, -1L}, out);
    }

    @Test
    public void rejectsUndersizedOutputArray() {
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("cpu 1 2 3 4 5 6 7", new long[1]));
        assertFalse(PerformanceMonitor.parseAggregateCpuTimes("cpu 1 2 3 4 5 6 7", null));
    }
}
