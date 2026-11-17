package com.overdrive.app.byd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import org.junit.Test;

public class BydDataCollectorDiLink5TelemetryTest {
    public static final class EventValueDevice {
        public Object get(int[] featureIds, Class<?> type) {
            assertEquals("android.hardware.bydauto.BYDAutoEventValue", type.getName());
            android.hardware.bydauto.BYDAutoEventValue value =
                    new android.hardware.bydauto.BYDAutoEventValue();
            value.intValue = featureIds[0] == 4208 ? 3 : 0;
            return value;
        }
    }

    public static final class OtaVoltageDevice {
        private final double primary;
        private final double indexed;
        int indexedCalls;

        OtaVoltageDevice(double primary, double indexed) {
            this.primary = primary;
            this.indexed = indexed;
        }

        public double getBatteryPowerVoltage() {
            return primary;
        }

        public double getBatteryVoltage(int battery) {
            assertEquals(0, battery);
            indexedCalls++;
            return indexed;
        }
    }

    @Test
    public void dilink5ProducerKeepsFullTelemetryFreshWhileParked() {
        assertEquals(5_000L, BydDataCollector.pollIntervalMs(false, true));
        assertEquals(5_000L, BydDataCollector.pollIntervalMs(true, true));
        assertEquals(5_000L, BydDataCollector.pollIntervalMs(true, false));
        assertEquals(90_000L, BydDataCollector.pollIntervalMs(false, false));
    }

    @Test
    public void onlyRunningDiLink5ProducerSkipsDuplicateSlowRefreshes() {
        assertTrue(BydDataCollector.useDiLink5FastRefresh(true, true));
        assertFalse(BydDataCollector.useDiLink5FastRefresh(true, false));
        assertFalse(BydDataCollector.useDiLink5FastRefresh(false, true));
        assertFalse(BydDataCollector.useDiLink5FastRefresh(false, false));
    }

    @Test
    public void dilink5PerSignalFreshnessExpiresIndependentlyOfBridgeHeartbeat() {
        assertTrue(BydDataCollector.isFreshDiLink5Observation(
                100_000L, 90_000L));
        assertFalse(BydDataCollector.isFreshDiLink5Observation(
                100_001L, 85_000L));
        assertFalse(BydDataCollector.isFreshDiLink5Observation(
                100_000L, 0L));
        assertFalse(BydDataCollector.isFreshDiLink5Observation(
                100_000L, 100_001L));

        assertTrue(BydDataCollector.isDiLink5DynamicObservationFresh(
                10_000L, 7_000L));
        assertFalse(BydDataCollector.isDiLink5DynamicObservationFresh(
                10_001L, 7_000L));
        assertFalse(BydDataCollector.isDiLink5DynamicObservationFresh(
                10_000L, 10_001L));
        assertEquals(7_000L,
                BydDataCollector.observedAtFromAge(10_000L, 3_000L));
    }

    @Test
    public void normalizesDiLink5PressureUnitsAndDriveModes() {
        assertEquals(270, BydDataCollector.decodeDiLink5TyrePressureKpa(27f, 1));
        assertEquals(276, BydDataCollector.decodeDiLink5TyrePressureKpa(401f, 2));
        assertEquals(277, BydDataCollector.decodeDiLink5TyrePressureKpa(277f, 3));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.decodeDiLink5TyrePressureKpa(0f, 3));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.decodeDiLink5TyrePressureKpa(277f, 0));
        assertEquals(270, BydDataCollector.retainFreshDiLink5TyreValue(
                270, BydVehicleData.UNAVAILABLE, 100_000L, 90_000L));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.retainFreshDiLink5TyreValue(
                        270, BydVehicleData.UNAVAILABLE,
                        100_000L, 84_999L));
        assertEquals(275, BydDataCollector.retainFreshDiLink5TyreValue(
                270, 275, 100_000L, 0L));
        assertEquals(0, BydDataCollector.retainFreshDiLink5TyreValue(
                2, 0, 100_000L, 90_000L));
        assertEquals(1, BydDataCollector.retainFreshDiLink5TyreValue(
                1, BydVehicleData.UNAVAILABLE, 100_000L, 90_000L));
        assertEquals(2, BydDataCollector.retainFreshDiLink5TyreValue(
                1, 2, 100_000L, 0L));
        assertEquals(31, BydDataCollector.retainFreshDiLink5TyreValue(
                31, BydVehicleData.UNAVAILABLE,
                220_000L, 100_000L, 120_000L));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.retainFreshDiLink5TyreValue(
                        31, BydVehicleData.UNAVAILABLE,
                        220_001L, 100_000L, 120_000L));

        assertTrue(BydDataCollector.isValidTyreTemperatureC(-40));
        assertTrue(BydDataCollector.isValidTyreTemperatureC(0));
        assertTrue(BydDataCollector.isValidTyreTemperatureC(125));
        assertFalse(BydDataCollector.isValidTyreTemperatureC(-41));
        assertFalse(BydDataCollector.isValidTyreTemperatureC(126));

        assertEquals(2, BydDataCollector.normalizeDiLink5DriveMode(1));
        assertEquals(3, BydDataCollector.normalizeDiLink5DriveMode(2));
        assertEquals(1, BydDataCollector.normalizeDiLink5DriveMode(3));
        assertEquals(4, BydDataCollector.normalizeDiLink5DriveMode(4));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeDiLink5DriveMode(0));
        assertEquals(0, BydDataCollector.normalizeDiLink5TyreLeakState(0));
        assertEquals(2, BydDataCollector.normalizeDiLink5TyreLeakState(1));
        assertEquals(1, BydDataCollector.normalizeDiLink5TyreLeakState(2));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeDiLink5TyreLeakState(3));
        assertEquals(0, BydDataCollector.normalizeDiLink5TyreStatus(0));
        assertEquals(3, BydDataCollector.normalizeDiLink5TyreStatus(3));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeDiLink5TyreStatus(-10011));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeDiLink5TyreStatus(65534));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeDiLink5TyreStatus(65535));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.selectDiLink5ChargingGear(
                        0, BydVehicleData.UNAVAILABLE));

        assertEquals(Boolean.FALSE, BydDataCollector.decodeDiLink5ItacMode(0));
        assertEquals(Boolean.TRUE, BydDataCollector.decodeDiLink5ItacMode(1));
        assertEquals(Boolean.FALSE, BydDataCollector.decodeDiLink5ItacMode(4));
        assertNull(BydDataCollector.decodeDiLink5ItacMode(5));
        assertEquals(4, BydDataCollector.diLink5ItacCommand(true));
        assertEquals(1, BydDataCollector.diLink5ItacCommand(false));
        assertEquals(6, BydDataCollector.diLink5ChildLockArea(true));
        assertEquals(7, BydDataCollector.diLink5ChildLockArea(false));
        assertEquals(1, BydDataCollector.diLink5ChildLockState(true));
        assertEquals(2, BydDataCollector.diLink5ChildLockState(false));
    }

    @Test
    public void missingDiLink5TyreDeviceClearsEveryPreviousValue() {
        BydVehicleData.Builder builder = new BydVehicleData.Builder()
                .tyrePressure(new int[] { 250, 251, 252, 253 })
                .tyrePressureState(new int[] { 0, 0, 0, 0 })
                .tyreAirLeakState(new int[] { 0, 0, 0, 0 })
                .tyreSignalState(new int[] { 0, 0, 0, 0 })
                .tyreTemperature(new int[] { 30, 31, 32, 33 })
                .tyreSystemState(0)
                .tyreTemperatureState(0);

        BydDataCollector.clearDiLink5TyreSnapshot(builder);
        BydVehicleData cleared = builder.build();
        int[] unavailable = {
                BydVehicleData.UNAVAILABLE,
                BydVehicleData.UNAVAILABLE,
                BydVehicleData.UNAVAILABLE,
                BydVehicleData.UNAVAILABLE
        };
        assertArrayEquals(unavailable, cleared.tyrePressure);
        assertArrayEquals(unavailable, cleared.tyrePressureState);
        assertArrayEquals(unavailable, cleared.tyreAirLeakState);
        assertArrayEquals(unavailable, cleared.tyreSignalState);
        assertArrayEquals(unavailable, cleared.tyreTemperature);
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.tyreSystemState);
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.tyreTemperatureState);
    }

    @Test
    public void resolvesMissingAcUnitWithoutChangingLegacyFallback() {
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAcTemperatureUnit(-10011, true));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAcTemperatureUnit(65534, true));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.normalizeAcTemperatureUnit(65535, true));
        assertEquals(-10011,
                BydDataCollector.normalizeAcTemperatureUnit(-10011, false));
        assertEquals(65535,
                BydDataCollector.normalizeAcTemperatureUnit(65535, false));
        assertEquals(BydDataCollector.TEMP_UNIT_FAHRENHEIT,
                BydDataCollector.resolveAcTemperatureUnit(
                        BydVehicleData.UNAVAILABLE, 72, true));
        assertEquals(BydDataCollector.TEMP_UNIT_FAHRENHEIT,
                BydDataCollector.resolveAcTemperatureUnit(-10011, 72, true));
        assertEquals(1, BydDataCollector.resolveAcTemperatureUnit(
                BydVehicleData.UNAVAILABLE, 22, true));
        assertEquals(BydVehicleData.UNAVAILABLE,
                BydDataCollector.resolveAcTemperatureUnit(
                        BydVehicleData.UNAVAILABLE,
                        BydVehicleData.UNAVAILABLE, true));
        assertEquals(1, BydDataCollector.resolveAcTemperatureUnit(
                BydVehicleData.UNAVAILABLE,
                BydVehicleData.UNAVAILABLE, false));

        assertTrue(BydDataCollector.isAcSetpointReadbackConfirmed(
                0, 22, 22, 22));
        assertFalse(BydDataCollector.isAcSetpointReadbackConfirmed(
                0, 22, 22, 21));
        assertTrue(BydDataCollector.isAcSetpointReadbackConfirmed(
                1, 22, 22, BydVehicleData.UNAVAILABLE));
        assertTrue(BydDataCollector.isAcSetpointReadbackConfirmed(
                2, 22, BydVehicleData.UNAVAILABLE, 22));
    }

    @Test
    public void confirmsOnlyTheExpectedDriverSeatMemoryResult() {
        assertTrue(BydDataCollector.isDiLink5SeatMemoryCallback(
                1, 1, 0, 1, 1));
        assertTrue(BydDataCollector.isDiLink5SeatMemoryCallback(
                2, 2, 0, 1, 2));
        assertFalse(BydDataCollector.isDiLink5SeatMemoryCallback(
                1, 2, 1, 1, 2));
        assertFalse(BydDataCollector.isDiLink5SeatMemoryCallback(
                1, 2, 0, 2, 2));
        assertFalse(BydDataCollector.isDiLink5SeatMemoryCallback(
                1, 2, 0, 1, 1));
        assertFalse(BydDataCollector.isDiLink5SeatMemoryCallback(
                3, 2, 0, 1, 2));
        assertTrue(BydDataCollector.isDiLink5SeatMemorySuccess(0));
        assertFalse(BydDataCollector.isDiLink5SeatMemorySuccess(1));
        assertEquals(2_500L,
                BydDataCollector.diLink5CommandConfirmationTimeoutMs(
                        20_000L, 10_000L));
        assertEquals(500L,
                BydDataCollector.diLink5CommandConfirmationTimeoutMs(
                        10_500L, 10_000L));
        assertEquals(0L,
                BydDataCollector.diLink5CommandConfirmationTimeoutMs(
                        9_999L, 10_000L));
    }

    @Test
    public void childPresenceDetectionKeepsUnknownOutOfTheDomain() {
        assertTrue(BydDataCollector.isChildPresenceDetectionValue(1));
        assertTrue(BydDataCollector.isChildPresenceDetectionValue(2));
        assertTrue(BydDataCollector.isChildPresenceDetectionValue(3));
        assertFalse(BydDataCollector.isChildPresenceDetectionValue(0));
        assertFalse(BydDataCollector.isChildPresenceDetectionValue(4));
        assertFalse(BydDataCollector.isChildPresenceDetectionValue(
                BydVehicleData.UNAVAILABLE));
    }

    @Test
    public void readsDiLink5SeatVentilationCapabilityFromHvacConfig() {
        assertNull(BydDataCollector.diLink5SeatVentilationSupportFromConfig(
                0, false));
        assertEquals(Boolean.FALSE,
                BydDataCollector.diLink5SeatVentilationSupportFromConfig(
                        0, true));
        assertEquals(Boolean.TRUE,
                BydDataCollector.diLink5SeatVentilationSupportFromConfig(
                        1, true));
        assertEquals(Boolean.TRUE,
                BydDataCollector.diLink5SeatVentilationSupportFromConfig(
                        7, true));
        assertNull(BydDataCollector.diLink5SeatVentilationSupportFromConfig(
                -1, true));
        assertNull(BydDataCollector.diLink5SeatVentilationSupportFromConfig(
                8, true));
    }

    @Test
    public void rejectsDiLink5ConnectingZeroFromPolledSocOnly() {
        assertFalse(BydDataCollector.isUsablePolledSoc(0.0, true));
        assertTrue(BydDataCollector.isUsablePolledSoc(0.1, false));
        assertTrue(BydDataCollector.isUsablePolledSoc(1.0, true));
        assertTrue(BydDataCollector.isUsablePolledSoc(100.0, true));
        assertFalse(BydDataCollector.isUsablePolledSoc(100.1, true));
    }

    @Test
    public void statisticPollUsesDiLink5KilometresAndRejectsSdkRails() {
        assertEquals(1.0,
                BydDataCollector.statisticDistanceFactor(true, 1.60934), 0.0);
        assertEquals(1.60934,
                BydDataCollector.statisticDistanceFactor(false, 1.60934), 0.0);
        assertTrue(BydDataCollector.isUsableMileage(0, true));
        assertFalse(BydDataCollector.isUsableMileage(0, false));
        assertTrue(BydDataCollector.isUsableMileage(1, false));
        assertFalse(BydDataCollector.isUsableMileage(2_000_001, true));

        assertTrue(BydDataCollector.isPlausibleTotalMileage(123456.7));
        assertFalse(BydDataCollector.isPlausibleTotalMileage(0));
        assertFalse(BydDataCollector.isPlausibleTotalMileage(10_000_000));

        assertTrue(BydDataCollector.isPlausibleElectricRange(0));
        assertTrue(BydDataCollector.isPlausibleElectricRange(2_000));
        assertFalse(BydDataCollector.isPlausibleElectricRange(4_095));

        assertFalse(BydDataCollector.isPlausibleWaterTemperature(0, true));
        assertTrue(BydDataCollector.isPlausibleWaterTemperature(0, false));
        assertTrue(BydDataCollector.isPlausibleWaterTemperature(200, true));
        assertFalse(BydDataCollector.isPlausibleWaterTemperature(255, true));

        assertTrue(BydDataCollector.isPlausibleTotalElectricConsumption(
                1_676_721.4));
        assertFalse(BydDataCollector.isPlausibleTotalElectricConsumption(
                1_676_721.5));
        assertTrue(BydDataCollector.isPlausibleTotalFuelConsumption(
                9_999.9, true));
        assertFalse(BydDataCollector.isPlausibleTotalFuelConsumption(
                10_000, true));
        assertTrue(BydDataCollector.isPlausibleTotalFuelConsumption(
                104_857.4, false));
        assertFalse(BydDataCollector.isPlausibleTotalFuelConsumption(
                104_857.5, false));
        assertFalse(BydDataCollector.isPlausibleTotalFuelConsumption(
                Double.NaN, true));
    }

    @Test
    public void prefersDiLink5AcOutsideTemperatureAndKeepsZeroValid() {
        assertEquals(0.0,
                BydDataCollector.selectDiLink5OutsideTemperatureC(0, 24), 0.0);
        assertEquals(-12.0,
                BydDataCollector.selectDiLink5OutsideTemperatureC(-12, 24), 0.0);
        assertEquals(24.0,
                BydDataCollector.selectDiLink5OutsideTemperatureC(255, 24), 0.0);
        assertTrue(Double.isNaN(
                BydDataCollector.selectDiLink5OutsideTemperatureC(255, 0)));
    }

    @Test
    public void readsPressureUnitUsingTheDiLink5EventValueToken() {
        Object value = BydDeviceHelper.callGetEventValue(
                new EventValueDevice(), 4208);
        assertEquals(3, BydDeviceHelper.getIntValue(value));
    }

    @Test
    public void usesIndexed12vFallbackOnlyForDiLink5AndInvalidPrimaryReadings() {
        OtaVoltageDevice diLink5 = new OtaVoltageDevice(-1, 13.2);
        assertEquals(13.2, BydDataCollector.readOtaVoltage12v(diLink5, true), 0.001);
        assertEquals(1, diLink5.indexedCalls);

        OtaVoltageDevice legacy = new OtaVoltageDevice(-1, 13.2);
        assertTrue(Double.isNaN(BydDataCollector.readOtaVoltage12v(legacy, false)));
        assertEquals(0, legacy.indexedCalls);

        OtaVoltageDevice primaryWorks = new OtaVoltageDevice(12.7, 13.2);
        assertEquals(12.7,
                BydDataCollector.readOtaVoltage12v(primaryWorks, true), 0.001);
        assertEquals(0, primaryWorks.indexedCalls);
    }

    @Test
    public void rejectsStaleOrReplayedBridgeMessages() {
        assertTrue(BydDataCollector.isNewerDiLink5Message(10, 4, 10, 5));
        assertTrue(BydDataCollector.isNewerDiLink5Message(10, 99, 11, 1));
        assertFalse(BydDataCollector.isNewerDiLink5Message(10, 4, 10, 4));
        assertFalse(BydDataCollector.isNewerDiLink5Message(10, 4, 9, 100));
        assertFalse(BydDataCollector.isNewerDiLink5Message(10, 4, 0, 5));

        assertTrue(BydDataCollector.isDiLink5IngressFresh(100_000L, 85_001L));
        assertTrue(BydDataCollector.isDiLink5IngressFresh(100_000L, 105_000L));
        assertFalse(BydDataCollector.isDiLink5IngressFresh(100_000L, 85_000L));
        assertFalse(BydDataCollector.isDiLink5IngressFresh(100_000L, 105_001L));
        assertFalse(BydDataCollector.isDiLink5IngressFresh(100_000L, 0L));
    }

    @Test
    public void automationLeaseEndsWithTheBridgeSampleNotTheFastPoll() {
        assertEquals(15_000L,
                BydDataCollector.diLink5AutomationLeaseRemainingMs(
                        100_000L, 100_000L));
        assertEquals(5_000L,
                BydDataCollector.diLink5AutomationLeaseRemainingMs(
                        110_000L, 100_000L));
        assertEquals(0L,
                BydDataCollector.diLink5AutomationLeaseRemainingMs(
                        115_000L, 100_000L));
        assertEquals(-1L,
                BydDataCollector.diLink5AutomationLeaseRemainingMs(
                        115_001L, 100_000L));
        assertEquals(-1L,
                BydDataCollector.diLink5AutomationLeaseRemainingMs(
                        99_999L, 100_000L));
    }

    @Test
    public void normalizesDoorStateHeartbeatWithoutInventingValues() {
        assertNull(BydDataCollector.normalizeDiLink5DoorStates(null));
        assertArrayEquals(
                new int[]{
                    0, 1,
                    BydVehicleData.UNAVAILABLE,
                    BydVehicleData.UNAVAILABLE,
                    BydVehicleData.UNAVAILABLE,
                    BydVehicleData.UNAVAILABLE,
                    BydVehicleData.UNAVAILABLE
                },
                BydDataCollector.normalizeDiLink5DoorStates(
                        new int[]{0, 1, 2, 255}));
    }

    @Test
    public void staleBridgeSnapshotClearsEveryVolatileFieldButKeepsMetadata()
            throws Exception {
        BydVehicleData.Builder populated = new BydVehicleData.Builder();
        for (Field field : BydVehicleData.Builder.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == double.class) field.setDouble(populated, 7.5);
            else if (type == int.class) field.setInt(populated, 7);
            else if (type == long.class) field.setLong(populated, 7L);
            else if (type == boolean.class) field.setBoolean(populated, true);
            else if (type == String.class) field.set(populated, "dynamic");
            else if (type == int[].class) field.set(populated, new int[]{7});
            else if (type == String[].class) {
                field.set(populated, new String[]{"dynamic"});
            }
        }
        populated.vin("VIN-STATIC")
                .engineCode("ENGINE-STATIC")
                .availableDevices(new String[]{"speed", "tyre"})
                .unavailableDevices(new String[]{"radar"});
        populated.timestamp = 1234L;

        BydVehicleData source = populated.buildPreservingTimestamp();
        BydVehicleData cleared =
                BydDataCollector.clearStaleDiLink5VehicleFields(source);
        BydVehicleData defaults =
                new BydVehicleData.Builder().buildPreservingTimestamp();

        for (Field field : BydVehicleData.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            String name = field.getName();
            Object actual = field.get(cleared);
            if ("vin".equals(name)
                    || "engineCode".equals(name)
                    || "availableDevices".equals(name)
                    || "unavailableDevices".equals(name)
                    || "timestamp".equals(name)) {
                assertFieldEquals(name, field.get(source), actual);
            } else {
                assertFieldEquals(name, field.get(defaults), actual);
            }
        }
        assertNotSame(source.availableDevices, cleared.availableDevices);
        assertNotSame(source.unavailableDevices, cleared.unavailableDevices);
    }

    @Test
    public void dilink5ChargingPrefersObservedSnapshotGear() {
        assertEquals(
                com.overdrive.app.monitor.GearMonitor.GEAR_D,
                BydDataCollector.selectDiLink5ChargingGear(
                        com.overdrive.app.monitor.GearMonitor.GEAR_D,
                        com.overdrive.app.monitor.GearMonitor.GEAR_P));
        assertEquals(
                com.overdrive.app.monitor.GearMonitor.GEAR_R,
                BydDataCollector.selectDiLink5ChargingGear(
                        BydVehicleData.UNAVAILABLE,
                        com.overdrive.app.monitor.GearMonitor.GEAR_R));
        assertEquals(
                BydVehicleData.UNAVAILABLE,
                BydDataCollector.selectDiLink5ChargingGear(
                        BydVehicleData.UNAVAILABLE,
                        BydVehicleData.UNAVAILABLE));
    }

    private static void assertFieldEquals(
            String name, Object expected, Object actual) {
        if (expected instanceof int[] || actual instanceof int[]) {
            assertArrayEquals(name, (int[]) expected, (int[]) actual);
        } else if (expected instanceof String[] || actual instanceof String[]) {
            assertArrayEquals(name, (String[]) expected, (String[]) actual);
        } else {
            assertEquals(name, expected, actual);
        }
    }
}
