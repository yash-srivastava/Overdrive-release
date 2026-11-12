package com.overdrive.app.byd;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** One-shot shell app_process entry point for physically verified BYD mode switching. */
public final class BydModeCommand {

    private static final String APP_PACKAGE = "com.overdrive.app";
    private static final String ENERGY_DEVICE =
            "android.hardware.bydauto.energy.BYDAutoEnergyDevice";
    private static final String SETTING_DEVICE =
            "android.hardware.bydauto.setting.BYDAutoSettingDevice";
    private static final long APPLY_TIMEOUT_MS = 2000L;

    private BydModeCommand() {}

    public static void main(String[] args) {
        boolean applied = apply(args);
        System.out.println("OVERDRIVE_MODE_RESULT=" + (applied ? "ok" : "failed"));
        System.exit(applied ? 0 : 2);
    }

    static boolean validArguments(String[] args) {
        if (args == null || args.length < 2) return false;
        final int mode;
        try {
            mode = Integer.parseInt(args[1]);
        } catch (NumberFormatException invalid) {
            return false;
        }
        if ("drive".equals(args[0])) {
            return args.length == 2 && mode >= 1 && mode <= 4;
        }
        if (!"energy".equals(args[0]) || args.length != 3
                || (mode != 1 && mode != 3)) {
            return false;
        }
        try {
            return Long.parseLong(args[2]) > 0L;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private static boolean apply(String[] args) {
        if (!validArguments(args)) return false;
        try {
            int mode = Integer.parseInt(args[1]);
            Context context =
                    BydDeviceHelper.withBydPermissionBypass(createAppContext());
            boolean energyCommand = "energy".equals(args[0]);
            Object energy = BydDeviceHelper.getDevice(ENERGY_DEVICE, context);
            if (energyCommand) prepareEnergyDevice(energy);
            return energyCommand
                    ? applyEnergyMode(context, energy, mode, Long.parseLong(args[2]))
                    : applyDriveMode(
                            energy,
                            BydDeviceHelper.getDevice(SETTING_DEVICE, context),
                            mode);
        } catch (Throwable failed) {
            Throwable cause = failed instanceof InvocationTargetException
                    && failed.getCause() != null ? failed.getCause() : failed;
            System.out.println("OVERDRIVE_MODE_FAILURE="
                    + cause.getClass().getSimpleName() + ":" + cause.getMessage());
            return false;
        }
    }

    /**
     * Prime the Energy singleton before its first write in this process. The OEM-compatible
     * sequence samples both public axes and registers the Energy listener before commands.
     */
    private static void prepareEnergyDevice(Object energy) {
        if (energy == null) return;
        int energyMode = readInt(energy, "getEnergyMode");
        int operationMode = readInt(energy, "getOperationMode");
        int mandatoryElectricState =
                com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                        ? -1
                        : VehicleActuatorBridge.readMandatoryElectricState(energy);
        boolean listenerRegistered =
                BydDeviceHelper.registerListener(energy, (method, callbackArgs) -> {});
        System.out.println("OVERDRIVE_ENERGY_INIT=energyMode:" + energyMode
                + ",operationMode:" + operationMode
                + ",mandatoryElectricState:" + mandatoryElectricState
                + ",listener:" + listenerRegistered);
    }

    private static boolean applyEnergyMode(
            Context context, Object energy, int mode, long generation) throws Exception {
        if (energy == null
                || !VehicleActuatorBridge.isPublishedEnergyRequestCurrent(
                        context, mode, generation)) {
            return false;
        }
        boolean diLink5 =
                com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
        boolean preferenceAxis =
                !diLink5
                        && VehicleActuatorBridge.readMandatoryElectricState(energy) > 0;
        int previousMode = readSelectedEnergyMode(energy, preferenceAxis);
        if (previousMode == mode) {
            return VehicleActuatorBridge.completeEnergyActuation(
                    context, generation, mode);
        }
        if (previousMode < 0 || previousMode > 5) return false;
        if (previousMode > 0
                && !VehicleActuatorBridge.beginEnergyActuation(
                context,
                generation,
                mode,
                previousMode,
                VehicleActuatorBridge.ENERGY_ACTUATOR_APP)) {
            return false;
        }
        if (!VehicleActuatorBridge.isPublishedEnergyRequestCurrent(
                context, mode, generation)) {
            return false;
        }
        if (diLink5) {
            int result = VehicleActuatorBridge.writeEnergyModeRaw(energy, mode);
            System.out.println("OVERDRIVE_setEnergyModeRaw_RETURN=" + result);
        } else {
            int setterValue = preferenceAxis
                    ? VehicleActuatorBridge.mandatoryElectricStateForEnergyMode(mode)
                    : mode;
            if (preferenceAxis) {
                int result = VehicleActuatorBridge.writeMandatoryElectricState(
                        energy, setterValue);
                System.out.println(
                        "OVERDRIVE_setMandatoryElectricPreference_RETURN=" + result);
            } else {
                invoke(energy, "setEnergyMode", setterValue);
            }
        }
        long deadline = SystemClock.elapsedRealtime() + APPLY_TIMEOUT_MS;
        do {
            if (!VehicleActuatorBridge.isPublishedEnergyRequestCurrent(
                    context, mode, generation)) {
                return false;
            }
            if (readSelectedEnergyMode(energy, preferenceAxis) == mode) {
                return VehicleActuatorBridge.completeEnergyActuation(
                        context, generation, mode);
            }
            sleepUntilNextPoll(deadline);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private static int readSelectedEnergyMode(
            Object energy, boolean preferenceAxis) {
        if (!preferenceAxis) return readInt(energy, "getEnergyMode");
        return VehicleActuatorBridge.energyModeForMandatoryElectricState(
                VehicleActuatorBridge.readMandatoryElectricState(energy));
    }

    private static boolean applyDriveMode(
            Object energy, Object setting, int configMode) throws Exception {
        if (configMode != 1 && driveModeMatches(energy, setting, configMode)) return true;

        int operationMode = BydDataCollector.energyOperationModeForDriveConfig(configMode);
        if (operationMode > 0 && energy != null) {
            if (configMode == 2) invokeIfPresent(energy, "setRoadSurfaceMode", 1);
            if (invokeIfPresent(energy, "setOperationMode", operationMode)
                    && awaitDriveMode(energy, setting, configMode)) {
                return true;
            }
        }

        if (configMode == 4 && energy != null) {
            invokeIfPresent(energy, "setRoadSurfaceMode", 2);
            if (awaitDriveMode(energy, setting, configMode)) return true;
        }

        if (setting != null
                && (invokeIfPresent(setting, "setDriveConfig", configMode)
                        || invokeIfPresent(setting, "setDrvingMode", configMode))
                && awaitDriveMode(energy, setting, configMode)) {
            return true;
        }
        return false;
    }

    private static boolean awaitDriveMode(
            Object energy, Object setting, int configMode) throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + APPLY_TIMEOUT_MS;
        do {
            if (driveModeMatchesAfterWrite(energy, setting, configMode)) return true;
            sleepUntilNextPoll(deadline);
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    static boolean driveModeMatchesAfterWrite(
            Object energy, Object setting, int configMode) {
        int operation = readInt(energy, "getOperationMode");
        int surface = BydDataCollector.readRoadSurfaceMode(energy);
        int target = BydDataCollector.readTargetDrivingMode(setting);
        if (BydDataCollector.driveModeFromEnergyAxis(
                operation, surface, target) == configMode) {
            return true;
        }
        return BydDataCollector.operationModeMatchesSetter(
                BydDataCollector.energyOperationModeForDriveConfig(configMode),
                operation,
                surface,
                target);
    }

    static boolean driveModeMatches(
            Object energy, Object setting, int configMode) {
        int operation = readInt(energy, "getOperationMode");
        return BydDataCollector.driveModeFromEnergyAxis(
                operation,
                BydDataCollector.readRoadSurfaceMode(energy),
                BydDataCollector.readTargetDrivingMode(setting)) == configMode;
    }

    private static int readInt(Object device, String methodName) {
        if (device == null) return -1;
        try {
            Object value = device.getClass().getMethod(methodName).invoke(device);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable unavailable) {
            return -1;
        }
    }

    private static void invoke(Object device, String methodName, int value) throws Exception {
        Method method = device.getClass().getMethod(methodName, int.class);
        Object result = method.invoke(device, value);
        System.out.println("OVERDRIVE_" + methodName + "_RETURN=" + result);
    }

    private static boolean invokeIfPresent(Object device, String methodName, int value) {
        try {
            invoke(device, methodName, value);
            return true;
        } catch (NoSuchMethodException absent) {
            return false;
        } catch (Throwable failed) {
            Throwable cause = failed instanceof InvocationTargetException
                    && failed.getCause() != null ? failed.getCause() : failed;
            System.out.println("OVERDRIVE_" + methodName + "_FAILURE="
                    + cause.getClass().getSimpleName() + ":" + cause.getMessage());
            return false;
        }
    }

    private static void sleepUntilNextPoll(long deadline) throws InterruptedException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining > 0L) Thread.sleep(Math.min(50L, remaining));
    }

    private static Context createAppContext() throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
        current.setAccessible(true);
        Object activityThread = current.invoke(null);
        if (activityThread == null) {
            Method systemMain = activityThreadClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            activityThread = systemMain.invoke(null);
        }
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Context systemContext = (Context) getSystemContext.invoke(activityThread);
        return systemContext.createPackageContext(
                APP_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
    }
}
