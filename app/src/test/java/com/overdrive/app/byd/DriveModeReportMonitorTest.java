package com.overdrive.app.byd;

import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DriveModeReportMonitorTest {
    @Test public void keepsAnUnchangedModeAndDiscardsLateFramesFromPreviousSessions() {
        FakeConnection source = new FakeConnection();
        AtomicLong clock = new AtomicLong();
        DriveModeReportMonitor monitor = new DriveModeReportMonitor(source, clock::get);

        monitor.setAccOn(false);
        assertEquals(0, source.registrations);
        monitor.setAccOn(true);
        DriveModeReportMonitor.Observation first = source.receiver;
        first.report(frame(0x30));
        clock.set(600_000);
        monitor.setAccOn(true);
        assertEquals(1, monitor.currentMode());
        assertEquals(1, source.registrations);

        monitor.setAccOn(false);
        first.report(frame(0x10));
        assertEquals(-1, monitor.currentMode());
        monitor.setAccOn(true);
        assertEquals(-1, monitor.currentMode());
        source.receiver.report(frame(0x20));
        first.report(frame(0x10));
        assertEquals(3, monitor.currentMode());
        monitor.close();
        source.receiver.report(frame(0x30));
        monitor.setAccOn(true);
        assertEquals(-1, monitor.currentMode());
        assertEquals(2, source.registrations);
    }

    @Test public void acceptsAnInitialSynchronousReportOnlyAfterRegistrationSucceeds() {
        FakeConnection source = new FakeConnection();
        source.reportDuringRegistration = true;
        source.rejectNext = true;
        AtomicLong clock = new AtomicLong();
        DriveModeReportMonitor monitor = new DriveModeReportMonitor(source, clock::get);
        monitor.setAccOn(true);
        assertEquals(-1, monitor.currentMode());
        assertFalse(source.needsCleanup());
        clock.set(5);
        monitor.setAccOn(true);
        assertEquals(1, source.registrations);
        clock.set(15_000);
        monitor.setAccOn(true);
        assertEquals(2, monitor.currentMode());
        assertEquals(2, source.registrations);
    }

    @Test public void receiverFailureClearsModeAndReopensOnNextConfirmedPowerReading() {
        FakeConnection source = new FakeConnection();
        DriveModeReportMonitor monitor = new DriveModeReportMonitor(source, () -> 0);
        monitor.setAccOn(true);
        DriveModeReportMonitor.Observation broken = source.receiver;
        broken.report(frame(0x20));
        broken.fail();
        assertEquals(-1, monitor.currentMode());
        monitor.setAccOn(true);
        broken.report(frame(0x20));
        assertEquals(-1, monitor.currentMode());
        source.receiver.report(frame(0x40));
        assertEquals(1, monitor.currentMode());
    }

    @Test public void reclaimsOwnedTableWhileParkedWithoutOpeningNewSubscription() {
        FakeConnection source = new FakeConnection();
        source.pending = true;
        DriveModeReportMonitor monitor = new DriveModeReportMonitor(source, () -> 0);
        monitor.setAccOn(false);
        assertFalse(source.needsCleanup());
        assertEquals(0, source.registrations);
    }

    @Test public void keepsExistingNarrowNativeFilterAndCanShareASubscribeAllReceiver() throws Exception {
        FakeDevice device = new FakeDevice();
        Runnable observer = () -> {};
        device.mIBYDAutoListenerMap.narrowOnly = true;
        assertFalse(OtaReportListeners.register(device, Runnable.class, observer));
        assertEquals(0, device.registrations);
        device.mIBYDAutoListenerMap.all = true;
        assertTrue(OtaReportListeners.register(device, Runnable.class, observer));
        assertTrue(OtaReportListeners.contains(device, observer));
        assertEquals(1, device.registrations);
    }

    private static byte[] frame(int raw) {
        byte[] frame = new byte[14];
        frame[2] = 3;
        frame[3] = 0x42;
        frame[8] = (byte) raw;
        return frame;
    }

    private static final class FakeConnection implements DriveModeReportMonitor.Connection {
        DriveModeReportMonitor.Observation receiver;
        boolean pending, rejectNext, reportDuringRegistration;
        int registrations;

        @Override public boolean needsCleanup() { return pending; }
        @Override public void subscribe(DriveModeReportMonitor.Observation observation) throws IOException {
            registrations++;
            receiver = observation;
            pending = true;
            if (reportDuringRegistration) receiver.report(frame(0x10));
            if (rejectNext) {
                rejectNext = false;
                throw new IOException("table rejected");
            }
        }
        @Override public void unsubscribe() { pending = false; }
    }

    public static final class FakeDevice {
        private final FakeListenerMap mIBYDAutoListenerMap = new FakeListenerMap();
        int registrations;
        public void registerListener(Runnable listener, int[] ids) {
            assertEquals(1, ids.length);
            assertEquals(0x9900001e, ids[0]);
            registrations++;
            mIBYDAutoListenerMap.listeners.add(listener);
        }
    }

    private static final class FakeListenerMap {
        boolean narrowOnly, all;
        final Set<Runnable> listeners = new HashSet<>();
        public boolean isListenerEmpty() { return !all && !narrowOnly && listeners.isEmpty(); }
        public boolean isListenerAllIdsEmpty() { return !all; }
        public Collection<Runnable> getIdListeners(int id) { return listeners; }
    }
}
