package com.overdrive.app.navmap.cluster;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;

import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.launcher.ClusterCast;
import com.overdrive.app.launcher.DiLink5ClusterCast;
import com.overdrive.app.navmap.ClusterMapProjector;
import com.overdrive.app.surveillance.ClusterAdasLifecycle;
import com.overdrive.app.surveillance.ClusterProjectionController;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Runs exclusively on the dedicated guidance worker in the camera daemon.
 * Protocol facts follow DashCast CanNavigationBatches and ClusterNavPusher (MIT);
 * no settings-HAL result is treated as proof that a physical HUD rendered.
 */
final class ClusterGuidanceVendorWriter {
    private static final int STATUS = 0x43E0003A, ICON = 0x43F01010, DISTANCE = 0x43F01018;
    private static final int STREET = 0x43FA1008, REMAIN = 0x43F02028;
    private static final int HOUR = 0x43F02010, MINUTE = 0x43F02018, SECOND = 0x43F0201E;
    private static final int NAV_SCREEN = 0x4C10E015;
    private Object instrument, setting;
    private Class<?> eventClass;
    private boolean navModeEnabled;
    private IBinder navContainer;
    private boolean canLaneActivated;
    private long lastCanLaneAttemptMs;

    static final class Result {
        final Map<String, String> registers = new LinkedHashMap<>();
        String cluster = "not_attempted";
        boolean stripClearDeferred;
    }

    Result write(ClusterGuidanceFrame frame, boolean windshieldHud, BooleanSupplier current) {
        Result result = new Result();
        if (windshieldHud) {
            put(result, "status", STATUS, 2, false, current);
            // Only activate the nav lane, preserving the user's HUD switch/mode.
            if (!projectionOwnsCluster() && !canLaneActivated && (lastCanLaneAttemptMs == 0
                    || android.os.SystemClock.elapsedRealtime() - lastCanLaneAttemptMs >= 30_000)) {
                lastCanLaneAttemptMs = android.os.SystemClock.elapsedRealtime();
                put(result, "nav_screen", NAV_SCREEN, 3, true, current);
                canLaneActivated = "sdk_rc_0_unverified".equals(result.registers.get("nav_screen"));
            }
            put(result, "icon", ICON, frame.canIcon(), false, current);
            put(result, "distance", DISTANCE, frame.distanceMeters, false, current);
            put(result, "street", STREET, ClusterGuidanceEncoding.streetBytes(frame.roadName), false, current);
            put(result, "remaining_distance", REMAIN, frame.remainingDistanceMeters, false, current);
            int seconds = Math.max(0, frame.remainingTimeSeconds);
            put(result, "remaining_hour", HOUR, seconds / 3600, false, current);
            put(result, "remaining_minute", MINUTE, (seconds / 60) % 60, false, current);
            // OEM writes zero after the remaining-duration hour/minute pair.
            put(result, "remaining_second", SECOND, 0, false, current);
        }
        if (!stripAllowed(current, result)) return result;
        try {
            IBinder binder = container();
            if (binder != navContainer) {
                navContainer = binder;
                navModeEnabled = false;
            }
            if (!navModeEnabled) {
                if (!stripAllowed(current, result)) return result;
                String activation = transact(binder, 2, null);
                if (!activation.startsWith("accepted")) {
                    result.cluster = activation;
                    return result;
                }
                navModeEnabled = true;
            }
            if (!stripAllowed(current, result)) return result;
            result.cluster = transact(binder, 3, ClusterGuidanceEncoding.active(frame));
            if (!result.cluster.startsWith("accepted")) navModeEnabled = false;
        } catch (Throwable error) {
            navModeEnabled = false;
            navContainer = null;
            result.cluster = failure(error);
        }
        return result;
    }

    Result clear(boolean windshieldHud) {
        Result result = new Result();
        if (windshieldHud) {
            put(result, "status", STATUS, 4, false, () -> true);
            put(result, "icon", ICON, 0, false, () -> true);
            put(result, "distance", DISTANCE, -1, false, () -> true);
            put(result, "street", STREET, new byte[0], false, () -> true);
            put(result, "remaining_distance", REMAIN, -1, false, () -> true);
            put(result, "remaining_hour", HOUR, 0, false, () -> true);
            put(result, "remaining_minute", MINUTE, 0, false, () -> true);
            put(result, "remaining_second", SECOND, 0, false, () -> true);
        }
        try {
            result.stripClearDeferred = projectionOwnsCluster();
            if (!result.stripClearDeferred) {
                IBinder binder = container();
                // Service lookup can race projection preparation; postpone the clear as well.
                result.stripClearDeferred = projectionOwnsCluster();
                if (!result.stripClearDeferred)
                    result.cluster = transact(binder, 3, ClusterGuidanceEncoding.clear());
            }
            if (result.stripClearDeferred) result.cluster = "suspended_for_active_projection";
        } catch (Throwable error) {
            result.cluster = failure(error);
        } finally {
            navModeEnabled = false;
            canLaneActivated = false;
            lastCanLaneAttemptMs = 0;
        }
        return result;
    }

    private boolean stripAllowed(BooleanSupplier current, Result result) {
        if (!current.getAsBoolean()) {
            result.cluster = "superseded";
            return false;
        }
        if (projectionOwnsCluster()) {
            navModeEnabled = false;
            result.cluster = "suspended_for_active_projection";
            return false;
        }
        return true;
    }

    static boolean projectionOwnsCluster() {
        // Layout admission begins before its physical open and survives pending recovery.
        // Activating the nav container in either gap could replace its owned projection.
        if (com.overdrive.app.launcher.layout.LayoutModeGate.layoutOwnsAdmission()) return true;
        // Consumer flags clear before asynchronous teardown/recovery finishes. The physical
        // snapshots also cover blind-spot projection, without starting or probing either owner.
        ClusterAdasLifecycle.Source legacy = ClusterProjectionController.clusterAdasState();
        ClusterAdasLifecycle.Source di5 = DiLink5ClusterCast.clusterAdasState();
        return legacy.active || legacy.busy || di5.active || di5.busy
                || ClusterCast.getCastPackage() != null || ClusterMapProjector.isActive();
    }

    private void put(Result result, String name, int register, Object value,
            boolean settings, BooleanSupplier current) {
        if (!current.getAsBoolean()) { result.registers.put(name, "superseded"); return; }
        // A mode change can race a queued clear/live frame. Check the family at each
        // register dispatch, rather than trusting the session's original platform snapshot.
        if (!ClusterGuidanceDaemon.di3()) {
            result.registers.put(name, "skipped_unsupported_platform");
            return;
        }
        try {
            Context context = CameraDaemon.getAppContext();
            if (context == null) throw new IllegalStateException("daemon context unavailable");
            Context wrapped = BydDeviceHelper.withBydPermissionBypass(context);
            if (eventClass == null) eventClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object device = settings ? setting : instrument;
            if (device == null) {
                device = BydDeviceHelper.getDevice(settings
                        ? "android.hardware.bydauto.setting.BYDAutoSettingDevice"
                        : "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice", wrapped);
                if (device == null) throw new IllegalStateException("instrument SDK unavailable");
                if (settings) setting = device; else instrument = device;
            }
            Object event = eventClass.getDeclaredConstructor().newInstance();
            eventClass.getField(value instanceof byte[] ? "bufferDataValue" : "intValue").set(event, value);
            Method set = device.getClass().getMethod("set", int[].class, eventClass);
            Object returned = set.invoke(device, new int[]{register}, event);
            result.registers.put(name, returned instanceof Number
                    ? (((Number) returned).intValue() == 0 ? "sdk_rc_0_unverified"
                    : "rejected_rc_" + returned) : "dispatched_unverified");
        } catch (Throwable error) { result.registers.put(name, failure(error)); }
    }

    private static IBinder container() throws Exception {
        Method get = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
        for (String name : new String[]{"AutoContainer", "auto_container"}) {
            Object value = get.invoke(null, name);
            if (value instanceof IBinder && ((IBinder) value).isBinderAlive()) return (IBinder) value;
        }
        throw new IllegalStateException("AutoContainer unavailable");
    }

    private static String transact(IBinder binder, int code, byte[] payload) throws Exception {
        String descriptor = binder.getInterfaceDescriptor();
        if (!"android.os.IAutoContainer".equals(descriptor))
            return "unsupported_interface";
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(descriptor);
            if (code == 2) {
                data.writeInt(5); data.writeInt(0); data.writeString("");
            } else {
                data.writeInt(4); data.writeByteArray(payload);
            }
            if (!binder.transact(code, data, reply, 0)) return "transaction_unhandled";
            reply.readException();
            if (reply.dataAvail() >= 4) {
                int rc = reply.readInt();
                if (rc != 0) return "rejected_rc_" + rc;
            }
            return "accepted_rendering_unverified";
        } finally { data.recycle(); reply.recycle(); }
    }

    private static String failure(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        return "failed_" + cause.getClass().getSimpleName();
    }
}
