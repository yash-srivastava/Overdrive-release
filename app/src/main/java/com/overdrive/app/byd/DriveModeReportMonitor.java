package com.overdrive.app.byd;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.util.ScratchRoot;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Owns the on-change driving-mode subscription for one vehicle-data collector. */
final class DriveModeReportMonitor {
    private static final Logger LOG = Logger.getLogger(DriveModeReportMonitor.class.getName());
    private static final long RETRY_MS = 10_000L;

    interface Connection {
        boolean needsCleanup();
        void subscribe(Observation observation) throws Exception;
        void unsubscribe() throws Exception;
    }

    // SDK callbacks run under the device's listener-map lock. They only touch their own
    // observation, never the monitor lock which is held during SDK registration/teardown.
    static final class Observation {
        private int mode = DriveModeReport.UNKNOWN;
        private boolean failed;

        synchronized void report(byte[] frame) {
            int reading = DriveModeReport.decode(frame);
            if (reading != DriveModeReport.UNRELATED) mode = reading;
        }

        synchronized void fail() { failed = true; }
        synchronized boolean failed() { return failed; }
        synchronized int mode() { return failed ? DriveModeReport.UNKNOWN : mode; }
    }

    private final Connection connection;
    private final LongSupplier clock;
    private volatile Observation current;
    private boolean closed;
    private long retryAt;

    DriveModeReportMonitor(Context context) {
        this(new SdkConnection(context), SystemClock::elapsedRealtime);
    }

    DriveModeReportMonitor(Connection connection, LongSupplier clock) {
        this.connection = connection;
        this.clock = clock;
    }

    int currentMode() {
        Observation active = current;
        return active == null ? DriveModeReport.UNKNOWN : active.mode();
    }

    synchronized void setAccOn(boolean on) {
        if (closed) return;
        Observation previous = current;
        if (on && previous != null && !previous.failed()) return;
        current = null;
        long now = clock.getAsLong();
        if (previous == null && now < retryAt) return;
        try {
            if (connection.needsCleanup()) connection.unsubscribe();
            if (on) {
                Observation next = new Observation();
                connection.subscribe(next);
                if (next.failed()) throw new IOException("receiver failed during registration");
                current = next;
            }
            retryAt = 0;
        } catch (Exception | LinkageError unavailable) {
            retryAt = now + RETRY_MS;
            LOG.log(Level.FINE, "Vehicle report unavailable", unavailable);
            release();
        }
    }

    synchronized void close() {
        closed = true;
        current = null;
        release();
    }

    private void release() {
        try {
            if (connection.needsCleanup()) connection.unsubscribe();
        } catch (Exception | LinkageError unavailable) {
            LOG.log(Level.FINE, "Vehicle report cleanup pending", unavailable);
        }
    }

    private static final class SdkConnection implements Connection {
        private final Context context;
        // The table may survive its shell-owned collector process. Reconcile only the
        // entry we added, including after losing a table acknowledgement or restarting.
        private final File tableMarker = ScratchRoot.file("overdrive_drive_mode_table.pending");
        private Object tableDevice;
        private Method sendTable;
        private Object otaDevice;
        private Class<?> listenerType;
        private Object listener;
        private boolean tablePending;

        SdkConnection(Context context) {
            this.context = context;
            tablePending = tableMarker.exists();
        }

        @Override public boolean needsCleanup() { return tablePending || listener != null; }

        private void openTable() throws Exception {
            if (tableDevice != null && sendTable != null) return;
            Object device = BydDeviceHelper.getDevice(
                    "android.hardware.bydauto.vehicledata.BYDAutoVehicleDataDevice", context);
            if (device == null) throw new IOException("vehicle-data device unavailable");
            sendTable = device.getClass().getMethod("sendRegisterTable", int.class, byte[].class);
            tableDevice = device;
        }

        private void updateTable(byte action) throws Exception {
            openTable();
            // Big-endian report ID, two selectors, change notification, add/remove.
            byte[] entry = {0, 0, 3, 0x42, 0, 0, 1, action};
            Object result = sendTable.invoke(tableDevice, 1, entry);
            if (!Integer.valueOf(0).equals(result)) throw new IOException("table rejected");
        }

        @Override public void subscribe(Observation sample) throws Exception {
            openTable();
            otaDevice = BydDeviceHelper.getDevice(
                    "android.hardware.bydauto.ota.BYDAutoOtaDevice", context);
            if (otaDevice == null) throw new IOException("OTA device unavailable");
            listenerType = Class.forName("android.hardware.IBYDAutoListener", false,
                    otaDevice.getClass().getClassLoader());
            Object proxy = Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[]{listenerType}, (self, method, args) -> {
                        String name = method.getName();
                        if ("equals".equals(name)) return self == args[0];
                        if ("hashCode".equals(name)) return System.identityHashCode(self);
                        if ("toString".equals(name)) return "DriveModeReportListener";
                        if ("onError".equals(name)) sample.fail();
                        if ("onDataEventChanged".equals(name) && args != null && args.length == 2
                                && Integer.valueOf(OtaReportListeners.DYNAMIC_REPORT).equals(args[0])
                                && args[1] != null) {
                            try {
                                Object frame = args[1].getClass().getField("bufferDataValue").get(args[1]);
                                if (frame instanceof byte[]) sample.report((byte[]) frame);
                            } catch (ReflectiveOperationException | RuntimeException invalid) {
                                sample.fail();
                            }
                        }
                        return null;
                    });
            listener = proxy;
            if (!OtaReportListeners.register(otaDevice, listenerType, proxy)) {
                throw new IOException("dynamic report listener unavailable");
            }
            // A previous collector in this process may have finished cleanup since
            // construction, or left its owned record after a failed shutdown.
            if (!tableMarker.createNewFile()) {
                tablePending = true;
                throw new IOException("previous table entry pending cleanup");
            }
            tablePending = true;
            updateTable((byte) 1);
        }

        @Override public void unsubscribe() throws Exception {
            try {
                if (tablePending) {
                    updateTable((byte) 2);
                    if (!tableMarker.delete() && tableMarker.exists()) {
                        throw new IOException("table marker retained");
                    }
                    tablePending = false;
                }
            } finally {
                tableDevice = null;
                sendTable = null;
                if (listener != null) {
                    if (OtaReportListeners.contains(otaDevice, listener)) {
                        otaDevice.getClass().getMethod("unregisterListener", listenerType)
                                .invoke(otaDevice, listener);
                        if (OtaReportListeners.contains(otaDevice, listener)) {
                            throw new IOException("report listener retained");
                        }
                    }
                    listener = null;
                    otaDevice = null;
                }
            }
        }
    }
}
