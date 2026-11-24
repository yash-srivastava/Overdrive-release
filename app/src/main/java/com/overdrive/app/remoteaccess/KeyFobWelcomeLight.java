package com.overdrive.app.remoteaccess;

import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.logging.DaemonLogger;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** Checks Smart Welcome Light once per eligible parking session, outside the fob receiver thread. */
final class KeyFobWelcomeLight {
    private static final DaemonLogger LOG = DaemonLogger.getInstance("KeyFobWelcomeLight");
    private final Executor executor;
    private final Supplier<Object> settingDevice;
    private final BooleanSupplier stillAllowed;
    private volatile Object parkingSession;

    KeyFobWelcomeLight(Executor executor, Supplier<Object> settingDevice, BooleanSupplier stillAllowed) {
        this.executor = executor;
        this.settingDevice = settingDevice;
        this.stillAllowed = stillAllowed;
    }

    static boolean enabled(JSONObject settings) {
        if (settings == null || !settings.optBoolean("enabled") || !settings.optBoolean("keyFobEnabled")
                || settings.optBoolean("dryRun", true)) return false;
        return settings.optBoolean("unlockEnabled")
                || (settings.optBoolean("lockEnabled") && settings.optBoolean("keyFobLockEnabled"));
    }

    /** Called by the fob worker. A missing power reading does not start a new parking session. */
    void observe(int power, boolean enabled) {
        if (!enabled || power > 0) {
            parkingSession = null;
        } else if (power == 0 && parkingSession == null) {
            Object session = new Object();
            parkingSession = session;
            try {
                executor.execute(() -> prepare(session));
            } catch (RejectedExecutionException unavailable) {
                LOG.debug("Welcome light check could not be scheduled");
            }
        }
    }

    private boolean current(Object session) {
        // A power or settings read can overlap a stop or a later parking session.
        return parkingSession == session && stillAllowed.getAsBoolean() && parkingSession == session;
    }

    private void prepare(Object session) {
        try {
            if (!current(session)) return;
            Object device = settingDevice.get();
            if (device == null) return;
            Object feature = device.getClass().getMethod("hasFeature", String.class)
                    .invoke(device, "SmartWelcomeLightState");
            if (!hasValue(feature, 1)) return;
            Object state = BydDeviceHelper.callGetter(device, "getSmartWelcomeLightState");
            if (!hasValue(state, 2) || !current(session)) return;
            Object result = BydDeviceHelper.callMethod(device, "setSmartWelcomeLightState", 1);
            if (hasValue(result, 0)) LOG.info("Enabled Smart Welcome Light for the physical fob");
            else LOG.debug("Vehicle did not confirm the welcome light setting");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            LOG.debug("Welcome light check unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    private static boolean hasValue(Object result, int value) {
        return result instanceof Number && ((Number) result).doubleValue() == value;
    }
}
