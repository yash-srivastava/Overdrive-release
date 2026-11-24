package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Queue;

import org.junit.Test;

public class ClusterWarningMonitorTest {
    private int result = 0x400;
    private long now = 100;
    private Runnable duringRead = () -> {};
    private final Queue<Runnable> work = new ArrayDeque<>();
    private final ClusterWarningMonitor monitor = new ClusterWarningMonitor(flags -> {
        duringRead.run();
        return result;
    }, () -> now, work::add);

    @Test public void forwardWarningRequiresEitherExactPairAndBothSharedQualifications() {
        for (int pre = 0; pre < 3; pre++) {
            for (int pcw = 0; pcw < 3; pcw++) {
                for (int acute = 0; acute < 3; acute++) {
                    for (int aeb = 0; aeb < 3; aeb++) {
                        boolean expected = (pre == 1 && pcw == 1) || (acute == 1 && aeb == 1);
                        assertEquals(expected, forwardWarning(pre, pcw, acute, aeb, 1, 1));
                        assertFalse(forwardWarning(pre, pcw, acute, aeb, -10011, 1));
                        assertFalse(forwardWarning(pre, pcw, acute, aeb, 1, 0));
                    }
                }
            }
        }
        assertFalse(forwardWarning(65535, 1, -1, 1, 1, 1));
        assertTrue(forwardWarning(-10011, 1, 1, 1, 1, 1));
    }

    private static boolean forwardWarning(int pre, int pcw, int acute, int aeb, int online, int equipped) {
        return forwardSample(pre, pcw, acute, aeb, online, equipped, true) == BydDataCollector.FORWARD_COLLISION_BIT;
    }

    private static int forwardSample(int pre, int pcw, int acute, int aeb, int online, int equipped, boolean published) {
        return ClusterAdasFirmware.readForwardWarning(id -> {
            switch (id) {
                case 0x32d00018: return pre;
                case 0x32d0001a: return pcw;
                case 0x32d0002b: return acute;
                case 0x32d0002c: return aeb;
                case 0x32d00000: return online;
                case 0x43200009: return equipped;
                default: throw new AssertionError(id);
            }
        }, published);
    }

    @Test public void publicPrewarningRequiresAnObservedEdgeAndContinuousFreshSamples() {
        int active = forwardSample(1, 1, 0, 0, -1, 1, false);
        int idle = forwardSample(0, 1, 0, 0, -1, 1, false);
        assertEquals(0, sample(active));
        assertEquals(0, sample(idle));
        assertEquals(0x400, sample(active));
        for (int i = 0; i < 21; i++) assertEquals(0x400, sample(active));
        assertEquals(0x400, monitor.current(ClusterWarningMonitor.FORWARD | ClusterWarningMonitor.STOPPED));
        work.remove().run();
        assertEquals(0x400, monitor.current(ClusterWarningMonitor.FORWARD));
        work.remove().run();
        assertEquals(0, sample(idle));
        assertEquals(0x400, sample(active));
        assertEquals(0, sample(idle));
        now += 1_000;
        assertEquals(0, sample(active));
        monitor.reset();
        assertEquals(0, sample(active));
        assertEquals(0, forwardSample(1, 0, 1, 1, -1, 1, false));
        assertEquals(0, forwardSample(1, 1, 1, 1, -1, 0, false));
        assertEquals(0, forwardSample(1, 1, 1, 1, 0, 1, true));
        assertEquals(0, forwardSample(65535, 1, 1, 1, -1, 1, false));
        assertEquals(active, forwardSample(1, 1, 0, 0, -1, -10011, false));
        assertEquals(idle, forwardSample(0, 1, 1, 1, -1, 1, false));
    }

    private int sample(int raw) {
        now += 200;
        result = raw;
        monitor.current(ClusterWarningMonitor.FORWARD);
        work.remove().run();
        return monitor.current(ClusterWarningMonitor.FORWARD);
    }

    @Test public void selectedIntervalRequiresItsOwnSourceQualifications() {
        for (int gap : new int[]{-10011, 0, 1, 2, 3, 4, 5, 255}) {
            int expected = gap >= 1 && gap <= 4 ? gap : 0;
            assertEquals(expected, drivingGap(1, 1, 3, gap));
            assertEquals(expected, drivingGap(1, 1, 7, gap));
            assertEquals(0, drivingGap(1, 0, 3, gap));
            assertEquals(0, drivingGap(0, 1, 3, gap));
            assertEquals(0, drivingGap(1, 1, 2, gap));
            assertEquals(0, drivingGap(1, 1, 8, gap));
            assertEquals(expected, instrumentGap(1, 3, 1, 1, gap));
            assertEquals(0, instrumentGap(0, 3, 1, 1, gap));
            assertEquals(0, instrumentGap(1, 1, 1, 1, gap));
            assertEquals(0, instrumentGap(1, 3, -10011, 1, gap));
            assertEquals(0, instrumentGap(1, 3, 1, 2, gap));
        }
    }

    private static int drivingGap(int online, int equipped, int mode, int gap) {
        return ClusterAdasFirmware.readDrivingGap(id -> {
            switch (id) {
                case 0x32d00000: return online;
                case 0x43200008: return equipped;
                case 0x32d00034: return mode;
                case 0x32d00012: return gap;
                default: throw new AssertionError(id);
            }
        });
    }

    private static int instrumentGap(int working, int power, int frame, int driving, int gap) {
        return ClusterAdasFirmware.readInstrumentGap(id -> {
            if (id == 0x23500000) return frame;
            if (id == 0x26f00000) return driving;
            throw new AssertionError(id);
        }, () -> power, () -> working, () -> gap);
    }

    @Test public void sideWarningLevelsAndPowerGateKeepEachSideIndependent() {
        int rLeft = BydDataCollector.RCTA_LEFT_BIT;
        int rRight = BydDataCollector.RCTA_RIGHT_BIT;
        int dLeft = BydDataCollector.DOW_LEFT_BIT;
        int dRight = BydDataCollector.DOW_RIGHT_BIT;
        assertEquals(rLeft | dRight, sides(true, true, 1, 2, 2, 1, 0, 0, 2, 1));
        assertEquals(rRight | dLeft, sides(true, true, 1, 2, 2, 0, 1, 2, 0, 1));
        assertEquals(rLeft | rRight, sides(true, true, 1, 2, 2, 1, 1, 2, 2, 3));
        assertEquals(dLeft | dRight, sides(false, true, 1, 2, 2, 1, 1, 2, 2, 1));
        assertEquals(rRight, sides(true, false, 1, 2, 2, 65535, 1, 2, 2, 1));
        assertEquals(0, sides(true, true, 1, 2, 2, 2, 2, 1, 1, 1));
        assertEquals(0, sides(true, true, 65535, 2, 2, 1, 1, 2, 2, 1));
        assertEquals(0, sides(true, true, 1, 1, -10011, 1, 1, 2, 2, 1));
        assertEquals(0, ClusterAdasFirmware.readSideWarnings(id -> {
            if (id >= 0x45300008 && id <= 0x4530000c) return -10011;
            return id == 0x4180001a ? 2 : 1;
        }, true, false, () -> 3));
    }

    private static int sides(boolean reverse, boolean stopped, int radar, int crossSwitch, int doorSwitch,
                             int crossLeft, int crossRight, int doorLeft, int doorRight, int power) {
        return ClusterAdasFirmware.readSideWarnings(id -> {
            switch (id) {
                case 0x41800000: return radar;
                case 0x4180001a: return crossSwitch;
                case 0x41800014: return crossLeft;
                case 0x41800015: return crossRight;
                case 0x4180001e: return doorSwitch;
                case 0x41800020: return doorLeft;
                case 0x41800022: return doorRight;
                case 0x45300008: case 0x45300009: case 0x4530000a: case 0x4530000b: return 0;
                case 0x4530000c: return 1;
                default: throw new AssertionError(id);
            }
        }, reverse, stopped, () -> power);
    }

    @Test public void readsAreDemandDrivenRateLimitedAndExplicitClearIsImmediate() {
        assertEquals(0, monitor.current(0));
        assertTrue(work.isEmpty());
        assertEquals(0, monitor.current(ClusterWarningMonitor.FORWARD));
        assertEquals(0, monitor.current(ClusterWarningMonitor.FORWARD));
        assertEquals(1, work.size());
        work.remove().run();
        now += 199;
        assertEquals(0x400, monitor.current(ClusterWarningMonitor.FORWARD));
        assertTrue(work.isEmpty());
        now++;
        monitor.current(ClusterWarningMonitor.FORWARD);
        result = 0;
        work.remove().run();
        assertEquals(0, monitor.current(ClusterWarningMonitor.FORWARD));
    }

    @Test public void lateReadCannotRenewAStaleWarning() {
        monitor.current(ClusterWarningMonitor.FORWARD);
        work.remove().run();
        now += 200;
        assertEquals(0x400, monitor.current(ClusterWarningMonitor.FORWARD));
        now += 650;
        assertEquals(0, monitor.current(ClusterWarningMonitor.FORWARD));
        work.remove().run();
        assertEquals(0, monitor.current(ClusterWarningMonitor.FORWARD));
    }

    @Test public void gearAndSessionTransitionsDiscardInFlightWarnings() {
        monitor.current(ClusterWarningMonitor.FORWARD);
        duringRead = () -> assertEquals(0, monitor.current(ClusterWarningMonitor.REVERSE));
        work.remove().run();
        assertEquals(0, monitor.current(ClusterWarningMonitor.REVERSE));
        duringRead = monitor::reset;
        work.remove().run();
        assertEquals(0, monitor.current(ClusterWarningMonitor.REVERSE));
        duringRead = () -> {};
        result = 4;
        work.remove().run();
        assertEquals(4, monitor.current(ClusterWarningMonitor.REVERSE));
        assertEquals(0, monitor.current(ClusterWarningMonitor.FORWARD));
    }

    @Test public void failedReadClearsWarningAndSubsequentReadRecovers() {
        monitor.current(ClusterWarningMonitor.FORWARD);
        work.remove().run();
        now += 200;
        monitor.current(ClusterWarningMonitor.FORWARD);
        duringRead = () -> { throw new IllegalStateException("disconnected"); };
        work.remove().run();
        assertEquals(0, monitor.current(ClusterWarningMonitor.FORWARD));
        now += 200;
        monitor.current(ClusterWarningMonitor.FORWARD);
        duringRead = () -> {};
        work.remove().run();
        assertEquals(0x400, monitor.current(ClusterWarningMonitor.FORWARD));
    }
}
