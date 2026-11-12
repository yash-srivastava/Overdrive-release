package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pins the connected head unit's public setter values and raw getter encoding. */
public class BydDriveModeEncodingTest {

    @Test
    public void configModesUseTheWorkingPublicSetterValues() {
        assertEquals(3, BydDataCollector.energyOperationModeForDriveConfig(1));
        assertEquals(1, BydDataCollector.energyOperationModeForDriveConfig(2));
        assertEquals(2, BydDataCollector.energyOperationModeForDriveConfig(3));
        assertEquals(4, BydDataCollector.energyOperationModeForDriveConfig(4));
    }

    @Test
    public void rawEnergyGetterMapsBackToConfigModes() {
        assertEquals(2, BydDataCollector.driveModeFromEnergyAxis(1, 1, 1));
        assertEquals(1, BydDataCollector.driveModeFromEnergyAxis(1, 1, 3));
        assertEquals(2, BydDataCollector.driveModeFromEnergyAxis(1, -1, -1));
        assertEquals(4, BydDataCollector.driveModeFromEnergyAxis(1, 2, 1));
        assertEquals(3, BydDataCollector.driveModeFromEnergyAxis(2, 1, 2));
        assertEquals(4, BydDataCollector.driveModeFromEnergyAxis(2, 2, 2));
        assertEquals(1, BydDataCollector.driveModeFromEnergyAxis(3, 1, 3));
        assertEquals(4, BydDataCollector.driveModeFromEnergyAxis(4, 1, 4));
        assertEquals(-1, BydDataCollector.driveModeFromEnergyAxis(0, 1, -1));
    }

    @Test
    public void typedEnergyCallbackDisambiguatesNormalWithoutChangingOtherModes() {
        EnergyListenerDevice device = new EnergyListenerDevice();
        int[] callbackModes = {-1, -1, -1};
        assertEquals(true, BydDeviceHelper.registerEnergyListener(device, (method, args) -> {
            if ("onOperationModeChanged".equals(method)) {
                callbackModes[0] = ((Number) args[0]).intValue();
            } else if ("onEnergyModeChanged".equals(method)) {
                callbackModes[1] = ((Number) args[0]).intValue();
            } else if ("onRoadSurfaceChanged".equals(method)) {
                callbackModes[2] = ((Number) args[0]).intValue();
            }
        }));

        android.hardware.bydauto.energy.AbsBYDAutoEnergyListener listener =
                (android.hardware.bydauto.energy.AbsBYDAutoEnergyListener) device.listener;
        listener.onEnergyModeChanged(3);
        listener.onOperationModeChanged(3);
        listener.onRoadSurfaceChanged(2);
        assertEquals(3, callbackModes[0]);
        assertEquals(3, callbackModes[1]);
        assertEquals(2, callbackModes[2]);
        assertEquals(1, BydDataCollector.driveModeFromEnergyAxis(1, 65535, -1, callbackModes[0]));
        assertEquals(2, BydDataCollector.driveModeFromEnergyAxis(1, 65535, -1, 1));
        assertEquals(3, BydDataCollector.driveModeFromEnergyAxis(2, 65535, -1, 3));
        assertEquals(4, BydDataCollector.driveModeFromEnergyAxis(1, 2, -1, 3));
    }

    @Test
    public void publicSetterValuesMatchTheLiveGetterAxis() {
        assertEquals(true, BydDataCollector.operationModeMatchesSetter(1, 1, 1, 1));
        assertEquals(true, BydDataCollector.operationModeMatchesSetter(2, 2, 1, 2));
        assertEquals(true, BydDataCollector.operationModeMatchesSetter(3, 1, 1, 3));
        assertEquals(true, BydDataCollector.operationModeMatchesSetter(3, 1, 1, -1));
        assertEquals(true, BydDataCollector.operationModeMatchesSetter(4, 4, 1, 4));
        assertEquals(true, BydDataCollector.operationModeMatchesSetter(4, 2, 2, 2));
        assertEquals(false, BydDataCollector.operationModeMatchesSetter(2, 2, 2, 2));
        assertEquals(false, BydDataCollector.operationModeMatchesSetter(1, 1, 1, 3));
    }

    @Test
    public void standaloneReadbackUsesTheSameNormalizedEnergyAxis() {
        assertEquals(true, BydModeCommand.driveModeMatches(
                new OperationModeDevice(1, 1), new TargetModeDevice(3), 1));
        assertEquals(false, BydModeCommand.driveModeMatches(
                new OperationModeDevice(1, 1), new TargetModeDevice(1), 1));
        assertEquals(true, BydModeCommand.driveModeMatches(
                new OperationModeDevice(1, 1), new TargetModeDevice(1), 2));
        assertEquals(true, BydModeCommand.driveModeMatches(
                new OperationModeDevice(2, 1), new TargetModeDevice(2), 3));
        assertEquals(false, BydModeCommand.driveModeMatches(
                new OperationModeDevice(2, 2), new TargetModeDevice(2), 3));
        assertEquals(true, BydModeCommand.driveModeMatches(
                new OperationModeDevice(2, 2), new TargetModeDevice(2), 4));
        assertEquals(false, BydModeCommand.driveModeMatches(
                new OperationModeDevice(1, 1), new TargetModeDevice(-1), 1));
        assertEquals(true, BydModeCommand.driveModeMatchesAfterWrite(
                new OperationModeDevice(1, 1), new TargetModeDevice(-1), 1));
    }

    @Test
    public void confirmedNormalCommandDisambiguatesOnlyTheSharedBaseAxis() {
        assertEquals(1, BydDataCollector.driveModeFromEnergyAxis(1, 1, -1, -1, 1));
        assertEquals(2, BydDataCollector.driveModeFromEnergyAxis(1, 1, -1, -1, 2));
        assertEquals(3, BydDataCollector.driveModeFromEnergyAxis(2, 1, -1, -1, 1));
        assertEquals(4, BydDataCollector.driveModeFromEnergyAxis(1, 2, -1, -1, 1));
    }

    @Test
    public void roadSurfaceFallsBackToGenericEnergyFeature() {
        assertEquals(2, BydDataCollector.readRoadSurfaceMode(
                new GenericRoadSurfaceDevice()));
    }

    @Test
    public void normalEcoDiagnosticReadsOnlyCandidateFeatureIds() {
        assertEquals(
                "energy.operation[0x2120000e]=1,"
                        + "energy.operation2[0x3420001c]=3,"
                        + "energy.operationEv[0x3420001e]=4,"
                        + "setting.target[0x"
                        + Integer.toHexString(BydFeatureIds.SETTING_TARGET_DRIVING_MODE)
                        + "]=3,"
                        + "setting.targetLegacy[0xae2ac]=1,"
                        + "setting.targetAlt[0x"
                        + Integer.toHexString(BydFeatureIds.SETTING_TARGET_DRIVING_MODE_ALT)
                        + "]=2",
                BydDataCollector.driveModeDiagnosticProbeState(
                        new DriveModeProbeDevice(true),
                        new DriveModeProbeDevice(false)));
    }

    @Test
    public void liveEnergyReadbackBeatsCommandEchoAndSettingAxis() {
        assertEquals(1, BydDataCollector.chooseDriveModeReadback(1, 1, 3));
        assertEquals(1, BydDataCollector.chooseDriveModeReadback(1, -1, 3));
        assertEquals(2, BydDataCollector.chooseDriveModeReadback(2, 1, 3));
        assertEquals(3, BydDataCollector.chooseDriveModeReadback(3, 1, 2));
        assertEquals(4, BydDataCollector.chooseDriveModeReadback(4, 2, 1));
    }

    @Test
    public void commandEchoExpiresInsteadOfMaskingLaterManualChanges() {
        assertEquals(1, BydDataCollector.recentDriveModeCommand(1, 1000L, 4000L));
        assertEquals(-1, BydDataCollector.recentDriveModeCommand(1, 1000L, 4001L));
        assertEquals(-1, BydDataCollector.recentDriveModeCommand(-1, 1000L, 1001L));
    }

    @Test
    public void commandAndSettingRemainFallbacksWhenEnergyIsUnavailable() {
        assertEquals(1, BydDataCollector.chooseDriveModeReadback(-1, 1, -1));
        assertEquals(2, BydDataCollector.chooseDriveModeReadback(-1, 2, 3));
        assertEquals(3, BydDataCollector.chooseDriveModeReadback(-1, -1, 3));
        assertEquals(-1, BydDataCollector.chooseDriveModeReadback(-1, -1, 0));
    }

    public static final class GenericRoadSurfaceDevice {
        public Object get(int[] featureIds, Class<?> type) {
            return featureIds.length == 1
                    && featureIds[0] == BydFeatureIds.ENERGY_ROAD_SURFACE_MODE
                    && type == Integer.TYPE
                    ? Integer.valueOf(2)
                    : null;
        }
    }

    public static final class DriveModeProbeDevice {
        private final boolean energy;

        DriveModeProbeDevice(boolean energy) {
            this.energy = energy;
        }

        public Object get(int[] featureIds, Class<?> type) {
            if (featureIds.length != 1 || type != Integer.TYPE) return null;
            int id = featureIds[0];
            if (energy) {
                if (id == BydDataCollector.DIAG_ENERGY_OPERATION_MODE) return 1;
                if (id == BydDataCollector.DIAG_ENERGY_OPERATION_MODE_SECONDARY) return 3;
                if (id == BydDataCollector.DIAG_ENERGY_OPERATION_MODE_EV) return 4;
                return null;
            }
            if (id == BydFeatureIds.SETTING_TARGET_DRIVING_MODE) return 3;
            if (id == BydDataCollector.DIAG_SETTING_TARGET_DRIVING_MODE_LEGACY) return 1;
            if (id == BydFeatureIds.SETTING_TARGET_DRIVING_MODE_ALT) return 2;
            return null;
        }
    }

    public static final class EnergyListenerDevice {
        android.hardware.IBYDAutoListener listener;

        public void registerListener(android.hardware.IBYDAutoListener listener) {
            this.listener = listener;
        }
    }

    public static final class OperationModeDevice {
        private final int mode;
        private final int surface;

        OperationModeDevice(int mode, int surface) {
            this.mode = mode;
            this.surface = surface;
        }

        public int getOperationMode() {
            return mode;
        }

        public int getRoadSurfaceMode() {
            return surface;
        }
    }

    @Test
    public void dilink5DrvingModeSetterIsRecognized() throws Exception {
        DiLink5DrvingModeDevice device = new DiLink5DrvingModeDevice();
        java.lang.reflect.Method m = device.getClass().getMethod("setDrvingMode", int.class);
        assertEquals(0, m.invoke(device, 2));
        assertEquals(2, device.lastMode);
    }

    public static final class DiLink5DrvingModeDevice {
        public int lastMode = -1;
        public int setDrvingMode(int mode) {
            this.lastMode = mode;
            return 0;
        }
    }

    public static final class TargetModeDevice {
        private final int mode;

        TargetModeDevice(int mode) {
            this.mode = mode;
        }

        public Object get(int[] featureIds, Class<?> type) {
            return featureIds.length == 1
                    && featureIds[0] == BydFeatureIds.SETTING_TARGET_DRIVING_MODE
                    && type == Integer.TYPE
                    ? Integer.valueOf(mode)
                    : null;
        }
    }
}
