package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.ChargingStateData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Verifies that DiLink 5 telemetry and automation polls skip VHAL properties that are absent from
 * the DiLink 5 VHAL config ({@code 0x21404710}, {@code 0x21403436}, {@code 0x21404909..0x21404910})
 * and legacy {@code tsimpl.*ManagerImpl.get(int[], Class)} feature IDs that log
 * {@code "Feature id doesn't exist"} while returning a zero-filled {@code BYDAutoEventValue}.
 */
public class BydDataCollectorDiLink5VhalSuppressionTest {

    private DaemonLogger.Config previousLogConfig;
    private String previousProcessMode;

    @Before
    public void setUp() throws Exception {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        previousProcessMode = DiLink5Platform.currentActiveMode();
        setProcessMode("dilink5");
        CarSvcTelemetry.clearForTest();
        suppressCarSvcAndDirectTurnProbes();
    }

    @After
    public void tearDown() throws Exception {
        CarSvcTelemetry.clearForTest();
        setProcessMode(previousProcessMode);
        DaemonLogger.configure(previousLogConfig);
    }

    @Test
    public void readTurnNowSkipsGetTurnLightFlashStateOnDiLink5Only() throws Exception {
        List<String> calls = new ArrayList<>();
        FakeLightDevice light = new FakeLightDevice(calls, 0, 2);
        BydDataCollector collector = newCollector();
        setField(collector, "lightDevice", light);

        assertEquals(-1, collector.readTurnNow());
        assertEquals(Collections.singletonList("getDoubleFlashLightState"), calls);

        calls.clear();
        setProcessMode("default");
        assertEquals(0x1, collector.readTurnNow());
        assertEquals(Collections.singletonList("getTurnLightFlashState"), calls);
    }

    @Test
    public void collectCabinTemperatureSkipsUnsupportedFeatureIdAndAllowsCloudFallbackOnDiLink5()
            throws Exception {
        ZeroFilledGenericDevice ac = new ZeroFilledGenericDevice();
        BydDataCollector collector = newCollector();
        setField(collector, "acDevice", ac);

        BydVehicleData.Builder builder = new BydVehicleData.Builder();
        Method collectCabin = BydDataCollector.class.getDeclaredMethod(
                "collectCabinTemperature", BydVehicleData.Builder.class);
        collectCabin.setAccessible(true);
        boolean halSucceeded = (Boolean) collectCabin.invoke(collector, builder);

        assertFalse(halSucceeded);
        assertEquals(0, ac.genericCalls);
        assertTrue(Double.isNaN(builder.build().insideTempC));

        setProcessMode("default");
        boolean defaultSucceeded = (Boolean) collectCabin.invoke(collector, builder);
        assertTrue(defaultSucceeded);
        assertEquals(1, ac.genericCalls);
        assertEquals(0.0, builder.build().insideTempC, 0.001);
    }

    @Test
    public void collectRadarSkipsGetAllRadarDistanceOnDiLink5() throws Exception {
        FakeRadarDevice radar = new FakeRadarDevice();
        BydDataCollector collector = newCollector();
        setField(collector, "radarDevice", radar);

        BydVehicleData.Builder builder = new BydVehicleData.Builder();
        invokeBuilderCollector(collector, "collectRadar", builder);
        assertEquals(0, radar.calls);

        setProcessMode("default");
        invokeBuilderCollector(collector, "collectRadar", builder);
        assertEquals(1, radar.calls);
    }

    @Test
    public void collectChargingSkipsChargerWorkStateAndZeroFilledGenericReadsOnDiLink5()
            throws Exception {
        FakeDiLink5ChargingDevice charging = new FakeDiLink5ChargingDevice();
        BydDataCollector collector = newCollector();
        setField(collector, "chargingDevice", charging);

        BydVehicleData.Builder builder = new BydVehicleData.Builder();
        invokeBuilderCollector(collector, "collectCharging", builder);
        BydVehicleData snapshot = builder.build();

        assertEquals(0, charging.chargerWorkStateCalls);
        assertEquals(0, charging.genericCalls);
        assertEquals(1, charging.bmsGetterCalls);
        assertEquals(ChargingStateData.CHARGING_BATTERY_STATE_CHARGING, snapshot.chargingState);
        assertTrue(Double.isNaN(snapshot.chargingCapacityKwh));
    }

    @Test
    public void collectEngineStatisticBodyworkAndInstrumentSkipZeroFilledGenericReadsOnDiLink5()
            throws Exception {
        FakeDiLink5EngineDevice engine = new FakeDiLink5EngineDevice();
        FakeDiLink5StatisticDevice statistic = new FakeDiLink5StatisticDevice();
        ZeroFilledGenericDevice bodywork = new ZeroFilledGenericDevice();
        FakeDiLink5InstrumentDevice instrument = new FakeDiLink5InstrumentDevice();

        BydDataCollector collector = newCollector();
        setField(collector, "accIsOn", true);
        setField(collector, "engineDevice", engine);
        setField(collector, "statisticDevice", statistic);
        setField(collector, "bodyworkDevice", bodywork);
        setField(collector, "instrumentDevice", instrument);

        BydVehicleData.Builder builder = new BydVehicleData.Builder()
                .currentTripMileageKm(42.5)
                .currentTripTimeHours(1.25)
                .currentTripConsumptionKwh(7.8);

        invokeBuilderCollector(collector, "collectEngine", builder);
        invokeBuilderCollector(collector, "collectStatistic", builder);
        invokeBuilderCollector(collector, "collectStatisticExtended", builder);
        invokeBuilderCollector(collector, "collectBodywork", builder);
        invokeBuilderCollector(collector, "collectBodyworkExtended", builder);
        invokeBuilderCollector(collector, "collectInstrument", builder);
        invokeBuilderCollector(collector, "collectInstrumentExtended", builder);

        BydVehicleData data = builder.build();
        assertEquals(0, engine.genericCalls);
        assertEquals(1450, data.engineSpeedRpm);
        assertEquals(18.5, data.enginePowerKw, 0.001);
        assertEquals(BydVehicleData.UNAVAILABLE, data.frontMotorSpeed);
        assertEquals(BydVehicleData.UNAVAILABLE, data.rearMotorSpeed);
        assertTrue(Double.isNaN(data.frontMotorTorque));

        assertEquals(0, statistic.genericCalls);
        assertEquals(12345, data.totalMileageKm);
        assertEquals(78.0, data.socPercent, 0.001);
        assertEquals(410, data.elecRangeKm);

        assertEquals(0, bodywork.genericCalls);
        assertEquals(BydVehicleData.UNAVAILABLE, data.emergencyAlarmState);
        assertEquals(BydVehicleData.UNAVAILABLE, data.sunshadePercent);

        assertEquals(0, instrument.genericCalls);
        assertEquals(BydVehicleData.UNAVAILABLE, data.chargingPercent);
        assertEquals(BydVehicleData.UNAVAILABLE, data.chargingRestTimeHours);
        assertEquals(BydVehicleData.UNAVAILABLE, data.chargingRestTimeMinutes);
        assertEquals(42.5, data.currentTripMileageKm, 0.001);
        assertEquals(1.25, data.currentTripTimeHours, 0.001);
        assertEquals(7.8, data.currentTripConsumptionKwh, 0.001);
        assertEquals(16.4, data.last50KmConsumption, 0.001);
    }

    @Test
    public void wiperAndDriveModePollsSkipUnsupportedFeatureIdProbesOnDiLink5() throws Exception {
        ZeroFilledGenericDevice wiper = new ZeroFilledGenericDevice();
        ZeroFilledGenericDevice setting = new ZeroFilledGenericDevice();
        FakeDiLink5EnergyDevice energy = new FakeDiLink5EnergyDevice();

        BydDataCollector collector = newCollector();
        setField(collector, "wiperDevice", wiper);
        setField(collector, "settingDevice", setting);
        setField(collector, "energyDevice", energy);

        Method readEnergyMode = BydDataCollector.class.getDeclaredMethod("readEnergyAxisDriveMode");
        readEnergyMode.setAccessible(true);

        assertEquals(BydVehicleData.UNAVAILABLE, collector.readWiperActiveNow());
        assertEquals(2, ((Integer) readEnergyMode.invoke(collector)).intValue());
        assertEquals(0, wiper.genericCalls);
        assertEquals(0, setting.genericCalls);
        assertEquals(0, energy.genericCalls);
    }

    private static BydDataCollector newCollector() throws Exception {
        Constructor<BydDataCollector> ctor = BydDataCollector.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static void invokeBuilderCollector(
            BydDataCollector collector, String methodName, BydVehicleData.Builder builder)
            throws Exception {
        Method method = BydDataCollector.class.getDeclaredMethod(
                methodName, BydVehicleData.Builder.class);
        method.setAccessible(true);
        method.invoke(collector, builder);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setProcessMode(String mode) throws Exception {
        Field field = DiLink5Platform.class.getDeclaredField("processMode");
        field.setAccessible(true);
        field.set(null, mode);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void suppressCarSvcAndDirectTurnProbes() throws Exception {
        Field dumpFailureField = CarSvcTelemetry.class.getDeclaredField("lastDumpFailureAtNanos");
        dumpFailureField.setAccessible(true);
        dumpFailureField.setLong(CarSvcTelemetry.INSTANCE, System.nanoTime());

        Field turnOwnerField = DiLink5DirectTelemetry.class.getDeclaredField("turnOwner");
        turnOwnerField.setAccessible(true);
        Class<? extends Enum> ownerEnum = (Class<? extends Enum>) turnOwnerField.getType();
        turnOwnerField.set(DiLink5DirectTelemetry.INSTANCE, Enum.valueOf(ownerEnum, "CAR_SERVICE"));
    }

    /** Simulates DiLink 5 {@code tsimpl.*ManagerImpl.get(int[], Class)} returning a zero-filled event. */
    public static class ZeroFilledGenericDevice {
        int genericCalls;

        public Object get(int[] featureIds, Class<?> type) {
            genericCalls++;
            return new ZeroEventValue();
        }
    }

    public static final class ZeroEventValue {
        public int intValue;
        public double doubleValue;
    }

    public static final class FakeLightDevice {
        private final List<String> calls;
        private final int doubleFlash;
        private final int turnFlash;

        FakeLightDevice(List<String> calls, int doubleFlash, int turnFlash) {
            this.calls = calls;
            this.doubleFlash = doubleFlash;
            this.turnFlash = turnFlash;
        }

        public int getDoubleFlashLightState() {
            calls.add("getDoubleFlashLightState");
            return doubleFlash;
        }

        public int getTurnLightFlashState() {
            calls.add("getTurnLightFlashState");
            return turnFlash;
        }
    }

    public static final class FakeRadarDevice {
        int calls;

        public int[] getAllRadarDistance() {
            calls++;
            return new int[]{100, 100, 100, 100};
        }
    }

    public static final class FakeDiLink5ChargingDevice extends ZeroFilledGenericDevice {
        int chargerWorkStateCalls;
        int bmsGetterCalls;

        public int getChargingGunState() {
            return 1;
        }

        public int getChargerWorkState() {
            chargerWorkStateCalls++;
            return 1;
        }

        public int getBatteryManagementDeviceState() {
            bmsGetterCalls++;
            return 1;
        }
    }

    public static final class FakeDiLink5EngineDevice extends ZeroFilledGenericDevice {
        public int getEngineSpeed() {
            return 1450;
        }

        public double getEnginePower() {
            return 18.5;
        }
    }

    public static final class FakeDiLink5StatisticDevice extends ZeroFilledGenericDevice {
        public double getTotalMileageValue() {
            return 12345.0;
        }

        public double getElecPercentageValue() {
            return 78.0;
        }

        public int getElecDrivingRangeValue() {
            return 410;
        }
    }

    public static final class FakeDiLink5InstrumentDevice extends ZeroFilledGenericDevice {
        public double getLast50KmPowerConsume() {
            return 16.4;
        }
    }

    public static final class FakeDiLink5EnergyDevice extends ZeroFilledGenericDevice {
        public int getOperationMode() {
            return 1;
        }

        public Integer getRoadSurfaceMode() {
            return null;
        }

        public int getEnergyMode() {
            return 1;
        }
    }
}
