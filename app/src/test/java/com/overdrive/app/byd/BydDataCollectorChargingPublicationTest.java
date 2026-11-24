package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.charging.ChargeCounterAccumulator;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.ChargeRateResolver;
import com.overdrive.app.monitor.ChargingDetector;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.SocHistoryDatabase;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class BydDataCollectorChargingPublicationTest {

    @Test
    public void preheatCompletionSurvivesAnOlderPollAndRetiredChargingListener() throws Exception {
        Object device = new CallbackOnlyChargingDevice();
        BydVehicleData initial = new BydVehicleData.Builder().build();
        BydDataCollector collector = newActiveCollector(device, initial);
        BydVehicleData.Builder poll = initial.toBuilder();
        Object observed = invokeCollectChargingOrdered(collector, poll);
        invokeChargingCallback(collector, device, "onChargePreheatStateChanged", 2);
        invokePublishCollectedSnapshot(collector, poll.build(), observed, 1L);
        invokeChargingCallback(collector, new Object(), "onChargePreheatStateChanged", 1);
        assertEquals(2, collector.getData().batteryPreheatState);
        assertTrue(collector.getData().batteryPreheatObservedAtMs > 0L);
        invokeChargingCallback(collector, device, "onChargePreheatStateChanged", 65535);
        assertEquals(BydVehicleData.UNAVAILABLE, collector.getData().batteryPreheatState);
        assertEquals(0L, collector.getData().batteryPreheatObservedAtMs);
    }

    @Test
    public void bmsFallbackRejectsUnavailableAndOutOfRangeValues() {
        assertTrue(BydDataCollector.isValidChargingBmsState(0));
        assertTrue(BydDataCollector.isValidChargingBmsState(15));
        assertFalse(BydDataCollector.isValidChargingBmsState(16));
        assertFalse(BydDataCollector.isValidChargingBmsState(65535));
        assertFalse(BydDataCollector.isValidChargingBmsState(BydFeatureIds.BMS_UNAVAILABLE));
    }

    @Test
    public void powerIsChargingRejectsNumericSentinels() {
        assertEquals(Boolean.FALSE, BydDataCollector.decodePowerIsCharging(0));
        assertEquals(Boolean.TRUE, BydDataCollector.decodePowerIsCharging(1));
        assertEquals(Boolean.TRUE, BydDataCollector.decodePowerIsCharging(true));
        assertEquals(null, BydDataCollector.decodePowerIsCharging(-1));
        assertEquals(null, BydDataCollector.decodePowerIsCharging(2));
        assertEquals(null, BydDataCollector.decodePowerIsCharging(255));
        assertEquals(null, BydDataCollector.decodePowerIsCharging(65_535));
    }

    @Test
    public void chargingGunStateAcceptsOnlyDocumentedDomain() {
        assertFalse(BydDataCollector.isValidChargingGunState(0));
        for (int state = 1; state <= 5; state++) {
            assertTrue(BydDataCollector.isValidChargingGunState(state));
        }
        assertFalse(BydDataCollector.isValidChargingGunState(6));
        assertFalse(BydDataCollector.isValidChargingGunState(255));
    }

    @Test
    public void negativeDevicePowerCallbackIsAValidClearSignal() {
        assertTrue(BydDataCollector.isChargingPowerCallbackPayload(-1.0));
        assertTrue(BydDataCollector.isChargingPowerCallbackPayload(0.0));
        assertTrue(BydDataCollector.isChargingPowerCallbackPayload(359.4));
        assertTrue(BydDataCollector.isChargingPowerCallbackPayload(500.0));
        assertFalse(BydDataCollector.isChargingPowerCallbackPayload(500.1));
        assertFalse(BydDataCollector.isChargingPowerCallbackPayload(-10011.0));
    }

    @Test
    public void acChargingCurrentLimitLabelsMatchOemFiveStateControl() {
        assertEquals("6 A", BydDataCollector.acChargingCurrentLimitLabel(1));
        assertEquals("8 A", BydDataCollector.acChargingCurrentLimitLabel(2));
        assertEquals("10 A", BydDataCollector.acChargingCurrentLimitLabel(3));
        assertEquals("16 A", BydDataCollector.acChargingCurrentLimitLabel(4));
        assertEquals("Max", BydDataCollector.acChargingCurrentLimitLabel(5));
        assertEquals(null, BydDataCollector.acChargingCurrentLimitLabel(0));
    }

    @Test
    public void chargingDevicePowerPrefersDedicatedFrameworkMethod() {
        DualPowerAliasChargingDevice device =
                new DualPowerAliasChargingDevice(6.4, 7.2);

        BydDataCollector.ChargingPowerReading reading =
                BydDataCollector.readChargingDevicePower(device);

        assertTrue(reading.answered());
        assertEquals("getChargingPower", reading.getter);
        assertEquals(7.2, reading.raw, 0.0);
        assertEquals(1L, device.chargingPowerCalls.get());
        assertEquals(0L, device.chargePowerCalls.get());
    }

    @Test
    public void chargingDevicePowerFallsBackToCompatibilityAlias() {
        ChargePowerOnlyChargingDevice device =
                new ChargePowerOnlyChargingDevice(6.4);

        BydDataCollector.ChargingPowerReading reading =
                BydDataCollector.readChargingDevicePower(device);

        assertTrue(reading.answered());
        assertEquals("getChargePower", reading.getter);
        assertEquals(6.4, reading.raw, 0.0);
    }

    @Test
    public void chargingDevicePowerTreatsPrimaryZeroAsAnswered() {
        DualPowerAliasChargingDevice device =
                new DualPowerAliasChargingDevice(6.4, 0.0);

        BydDataCollector.ChargingPowerReading reading =
                BydDataCollector.readChargingDevicePower(device);

        assertEquals("getChargingPower", reading.getter);
        assertEquals(0.0, reading.raw, 0.0);
        assertEquals(1L, device.chargingPowerCalls.get());
        assertEquals(0L, device.chargePowerCalls.get());
    }

    @Test
    public void unavailableSettingValuesDoNotMeanUnsupported() {
        assertTrue(BydDataCollector.isSettingFeatureUnavailable(
                BydVehicleData.UNAVAILABLE));
        assertTrue(BydDataCollector.isSettingFeatureUnavailable(
                BydFeatureIds.BMS_UNAVAILABLE));
        assertTrue(BydDataCollector.isSettingFeatureUnavailable(
                BydFeatureIds.INVALID_VALUE));
        assertTrue(BydDataCollector.isSettingFeatureUnavailable(
                BydFeatureIds.INVALID_VALUE_2));
        assertTrue(BydDataCollector.isSettingFeatureUnavailable(65_535));
        assertTrue(BydDataCollector.isSettingFeatureUnavailable(-1));
        assertFalse(BydDataCollector.isSettingFeatureUnavailable(0));
        assertFalse(BydDataCollector.isSettingFeatureUnavailable(1));
        assertFalse(BydDataCollector.isSettingFeatureUnavailable(2));
    }

    @Test
    public void validCurrentLimitReadbackProvesSupportDespiteConfigMismatch() {
        assertEquals(Boolean.TRUE,
                BydDataCollector.resolveAcChargingCurrentLimitSupport(
                        0, BydDataCollector.AC_CHARGE_CURRENT_10A, null));
        assertEquals(Boolean.TRUE,
                BydDataCollector.resolveAcChargingCurrentLimitSupport(
                        BydVehicleData.UNAVAILABLE,
                        BydDataCollector.AC_CHARGE_CURRENT_MAX, null));
        assertEquals(Boolean.TRUE,
                BydDataCollector.resolveAcChargingCurrentLimitSupport(
                        0, BydVehicleData.UNAVAILABLE, Boolean.TRUE));
        assertEquals(Boolean.TRUE,
                BydDataCollector.resolveAcChargingCurrentLimitSupport(
                        BydVehicleData.UNAVAILABLE,
                        BydVehicleData.UNAVAILABLE, Boolean.TRUE));
        assertEquals(0x18500843,
                BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_CONFIG_STATUS);
        assertEquals(0x18500845,
                BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS);
        assertEquals(0x4EF06044,
                BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS_SET);
    }

    @Test
    public void chargePowerAliasPublishesMeasuredChargingRate() throws Exception {
        try (SourceKindOverride ignored = new SourceKindOverride(
                ChargeSourceClassifier.SRC_DEVICE,
                ChargeSourceClassifier.Kind.RATE)) {
            ChargePowerOnlyChargingDevice device =
                    new ChargePowerOnlyChargingDevice(6.4);
            BydVehicleData initial = new BydVehicleData.Builder()
                    .chargingState(1)
                    .chargingGunState(2)
                    .chargingType(0)
                    .build();
            BydDataCollector collector = newActiveCollector(device, initial);
            BydVehicleData.Builder builder = initial.toBuilder();

            invokeCollectChargingOrdered(collector, builder);

            assertEquals(6.4, builder.build().chargingPowerKw, 0.0);
        }
    }

    @Test
    public void freshCallbackPowerSurvivesZeroGetterWithoutRefreshingItsAge()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 1);
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .build());

        invokeChargingCallback(
                collector, device, "onChargingPowerChanged", 6.1);
        long callbackAtMs = collector.getData().chargingPowerAtMs;
        BydVehicleData.Builder builder = collector.getData().toBuilder();

        invokeCollectChargingOrdered(collector, builder);

        BydVehicleData polled = builder.build();
        assertEquals(6.1, polled.chargingPowerKw, 0.0);
        assertEquals(callbackAtMs, polled.chargingPowerAtMs);
    }

    @Test
    public void freshCallbackPowerSurvivesUnavailableGetterWithoutRefreshingItsAge()
            throws Exception {
        CallbackOnlyChargingDevice device = new CallbackOnlyChargingDevice();
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .build());

        invokeChargingCallback(
                collector, device, "onChargingPowerChanged", 7.2);
        long callbackAtMs = collector.getData().chargingPowerAtMs;
        BydVehicleData.Builder builder = collector.getData().toBuilder();

        invokeCollectChargingOrdered(collector, builder);

        BydVehicleData polled = builder.build();
        assertEquals(7.2, polled.chargingPowerKw, 0.0);
        assertEquals(callbackAtMs, polled.chargingPowerAtMs);
    }

    @Test
    public void staleCallbackPowerDoesNotSurviveZeroGetter() throws Exception {
        long staleAtMs = System.currentTimeMillis()
                - BydDataCollector.DEVICE_POWER_CALLBACK_MAX_AGE_MS - 1L;
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 1);
        BydVehicleData initial = new BydVehicleData.Builder()
                .chargingState(1)
                .chargingGunState(2)
                .chargingType(0)
                .chargingPowerKw(6.1)
                .chargingPowerAtMs(staleAtMs)
                .build();
        BydDataCollector collector = newActiveCollector(device, initial);
        setField(collector, "latestDevicePowerCameFromCallback", true);
        setField(collector, "lastPositiveDevicePowerCallbackAtMs", staleAtMs);
        BydVehicleData.Builder builder = initial.toBuilder();

        invokeCollectChargingOrdered(collector, builder);

        assertTrue(Double.isNaN(builder.build().chargingPowerKw));
    }

    @Test
    public void explicitZeroCallbackStillClearsCallbackOwnedPower() throws Exception {
        CallbackOnlyChargingDevice device = new CallbackOnlyChargingDevice();
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .build());

        invokeChargingCallback(
                collector, device, "onChargingPowerChanged", 7.2);
        invokeChargingCallback(
                collector, device, "onChargingPowerChanged", 0.0);

        assertTrue(Double.isNaN(collector.getData().chargingPowerKw));
        assertEquals(0L, collector.getData().chargingPowerAtMs);
        assertFalse((Boolean) field(
                collector, "latestDevicePowerCameFromCallback"));
    }

    @Test
    public void finiteNonPositiveExternalRateCallbackClearsStaleValue() {
        assertTrue(BydDataCollector.isExplicitExternalRateStop(
                -1.0, ChargeSourceClassifier.Kind.RATE));
        assertTrue(BydDataCollector.isExplicitExternalRateStop(
                0.0, ChargeSourceClassifier.Kind.RATE));
        assertFalse(BydDataCollector.isExplicitExternalRateStop(
                -1.0, ChargeSourceClassifier.Kind.COUNTER));
        assertFalse(BydDataCollector.isExplicitExternalRateStop(
                Double.NaN, ChargeSourceClassifier.Kind.RATE));
        assertFalse(BydDataCollector.isExplicitExternalRateStop(
                -10011.0, ChargeSourceClassifier.Kind.RATE));
    }

    @Test
    public void finishedConnectedPreservesDirectCandidateBeforeClassification() {
        assertTrue(BydDataCollector.canPreserveFinishedConnectedRate(
                BydDataCollector.SRC_PACK_SIDE_DIRECT));
        assertFalse(BydDataCollector.canPreserveFinishedConnectedRate(
                "unknown-finished-source-" + System.nanoTime()));
    }

    @Test
    public void terminalBarrierSuppressesCallbackOnlyBmsTransitions() {
        assertFalse(BydDataCollector.shouldPublishBmsCallbackTransition(2, 1, true));
        assertFalse(BydDataCollector.shouldPublishBmsCallbackTransition(15, 2, true));
        assertTrue(BydDataCollector.shouldPublishBmsCallbackTransition(2, 2, true));
        assertTrue(BydDataCollector.shouldPublishBmsCallbackTransition(2, 1, false));
    }

    @Test
    public void terminalCounterReconciliationPreservesLowerWrapAndResetObservations() {
        double nearCapacityCeiling =
                BydDataCollector.CHARGING_CAPACITY_MAX_KWH - 0.1;
        assertEquals(0.25, BydDataCollector.reconcileTerminalCounterObservation(
                ChargeSourceClassifier.SRC_CAPACITY, nearCapacityCeiling, 0.25), 0.0);
        assertEquals(0.0, BydDataCollector.reconcileTerminalCounterObservation(
                ChargeSourceClassifier.SRC_CAPACITY, 4.25, 0.0), 0.0);
        assertEquals(3.0, BydDataCollector.reconcileTerminalCounterObservation(
                ChargeSourceClassifier.SRC_EXTERNAL, 499.5, 3.0), 0.0);
        assertEquals(4.25, BydDataCollector.reconcileTerminalCounterObservation(
                ChargeSourceClassifier.SRC_CAPACITY, 4.25,
                BydDataCollector.CHARGING_CAPACITY_MAX_KWH + 0.001), 0.0);
    }

    private DaemonLogger.Config previousLogConfig;

    @Before
    public void disableAndroidAndFileLogging() throws Exception {
        previousLogConfig = DaemonLogger.getConfig();
        DaemonLogger.configure(DaemonLogger.Config.defaults()
                .withConsoleLog(false)
                .withFileLog(false));
        resetSharedDetectorForCollectorTest();
    }

    @After
    public void restoreLogging() {
        DaemonLogger.configure(previousLogConfig);
    }

    @Test
    public void newerTerminalCallbackWinsOverInFlightPollSnapshot() {
        BydVehicleData stalePoll = new BydVehicleData.Builder()
                .chargingState(1)
                .chargingGunState(2)
                .build();
        BydVehicleData terminalCallback = stalePoll.toBuilder()
                .chargingState(2)
                .chargingStateAtMs(12345L)
                .chargingGunState(1)
                .vtolCharging(false)
                .build();

        BydVehicleData merged = BydDataCollector.preserveNewerChargingEdge(
                stalePoll, terminalCallback, 10L, 11L);

        assertEquals(2, merged.chargingState);
        assertEquals(12345L, merged.chargingStateAtMs);
        assertEquals(1, merged.chargingGunState);
    }

    @Test
    public void unchangedVersionKeepsCollectedValues() {
        BydVehicleData poll = new BydVehicleData.Builder()
                .chargingState(1)
                .chargingGunState(2)
                .build();
        assertTrue(poll == BydDataCollector.preserveNewerChargingEdge(
                poll, poll, 10L, 10L));
    }

    @Test
    public void v2lAlwaysBlocksPositiveChargingSources() {
        assertTrue(BydDataCollector.isPowerExportContext(5, false));
        assertTrue(BydDataCollector.isPowerExportContext(2, true));
        assertFalse(BydDataCollector.isPowerExportContext(2, false));
    }

    @Test
    public void rawDetectorEvidenceRequiresConnectedNonExportGun() {
        assertFalse(BydDataCollector.allowsRawChargingEvidence(1, false));
        assertFalse(BydDataCollector.allowsRawChargingEvidence(5, true));
        assertFalse(BydDataCollector.allowsRawChargingEvidence(2, true));
        assertTrue(BydDataCollector.allowsRawChargingEvidence(2, false));
        assertTrue(BydDataCollector.allowsRawChargingEvidence(BydVehicleData.UNAVAILABLE, false));
    }

    @Test
    public void gunOutFinalCounterRequiresBoundedLifecycleHold() {
        String capacity = ChargeSourceClassifier.SRC_CAPACITY;

        assertTrue(BydDataCollector.allowsFinalCounterDuringLifecycleHold(
                capacity, BydDataCollector.CHARGING_CAPACITY_MAX_KWH,
                1, false, true));
        assertFalse(BydDataCollector.allowsFinalCounterDuringLifecycleHold(
                capacity, BydDataCollector.CHARGING_CAPACITY_MAX_KWH + 0.001,
                1, false, true));
        assertFalse(BydDataCollector.allowsFinalCounterDuringLifecycleHold(
                capacity, 12.3, 1, false, false));
        assertFalse(BydDataCollector.allowsFinalCounterDuringLifecycleHold(
                capacity, 12.3, 2, false, true));
        assertFalse(BydDataCollector.allowsFinalCounterDuringLifecycleHold(
                capacity, 12.3, 1, true, true));
        assertFalse(BydDataCollector.allowsFinalCounterDuringLifecycleHold(
                ChargeSourceClassifier.SRC_DEVICE, 12.3, 1, false, true));

        assertTrue(BydDataCollector.allowsFinalCounterDuringLifecycleHold(
                ChargeSourceClassifier.SRC_EXTERNAL, ChargeSourceClassifier.Kind.COUNTER,
                500.0, 1, false, true));
        assertFalse(BydDataCollector.allowsFinalCounterDuringLifecycleHold(
                ChargeSourceClassifier.SRC_EXTERNAL, ChargeSourceClassifier.Kind.COUNTER,
                500.001, 1, false, true));
        assertFalse(BydDataCollector.allowsFinalCounterDuringLifecycleHold(
                ChargeSourceClassifier.SRC_EXTERNAL, ChargeSourceClassifier.Kind.RATE,
                119.0, 1, false, true));
    }

    @Test
    public void directRawEnvelopeAdmitsHectowattAcValuesWithoutScalingGuess() {
        assertTrue(BydDataCollector.isRawChargingSourceValueAdmissible(650.0));
        assertTrue(BydDataCollector.isRawChargingSourceValueAdmissible(700.0));
        assertTrue(BydDataCollector.isRawChargingSourceValueAdmissible(50_000.0));
        assertFalse(BydDataCollector.isRawChargingSourceValueAdmissible(50_000.1));
        assertFalse(BydDataCollector.isRawChargingSourceValueAdmissible(65_535.0));
        assertFalse(BydDataCollector.isRawChargingSourceValueAdmissible(-1.0));
        assertEquals("__packSideDirect", BydDataCollector.SRC_PACK_SIDE_DIRECT);
    }

    @Test
    public void phevFailureSignaturesAreRejectedBeforeSourceTraining() {
        assertTrue(BydDataCollector.isRawChargingSourceValueAdmissible(359.4));
        assertTrue(BydDataCollector.isRawChargingSourceValueAdmissible(1320.10));
        assertTrue(BydDataCollector.isRawChargingSourceValueAdmissible(359.4, false));
        assertTrue(BydDataCollector.isRawChargingSourceValueAdmissible(1320.10, false));

        assertFalse(BydDataCollector.isRawChargingSourceValueAdmissible(359.4, true));
        assertFalse(BydDataCollector.isRawChargingSourceValueAdmissible(1320.10, true));
        assertTrue(BydDataCollector.isRawChargingSourceValueAdmissible(300.0, true));
    }

    @Test
    public void externalUnknownSamplesReachDatabaseBeforeCounterConfirmation() throws Exception {
        String source = ChargeSourceClassifier.SRC_EXTERNAL;
        assertEquals(BydDataCollector.CounterObservationRoute.DATABASE_ONLY,
                BydDataCollector.counterObservationRoute(
                        source, ChargeSourceClassifier.Kind.UNKNOWN));
        assertEquals(BydDataCollector.CounterObservationRoute.DATABASE_ONLY,
                BydDataCollector.counterObservationRoute(
                        source, ChargeSourceClassifier.Kind.RATE));
        assertEquals(BydDataCollector.CounterObservationRoute.SCALE_AND_DATABASE,
                BydDataCollector.counterObservationRoute(
                        source, ChargeSourceClassifier.Kind.COUNTER));

        Field loadedField = ChargeSourceClassifier.class.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        boolean previousLoaded = loadedField.getBoolean(null);
        loadedField.setBoolean(null, true);

        Field observationsField = ChargeSourceClassifier.class.getDeclaredField("observations");
        observationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> observations = (Map<String, Object>) observationsField.get(null);
        Object previousObservation = observations.get(source);
        Class<?> observationClass =
                Class.forName("com.overdrive.app.byd.ChargeSourceClassifier$Observation");
        Constructor<?> observationConstructor = observationClass.getDeclaredConstructor();
        observationConstructor.setAccessible(true);
        Object controlledObservation = observationConstructor.newInstance();
        Field kindField = observationClass.getDeclaredField("kind");
        kindField.setAccessible(true);
        observations.put(source, controlledObservation);

        Field divisorsField = ChargeRateResolver.class.getDeclaredField("latchedDivisors");
        divisorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Double> divisors = (Map<String, Double>) divisorsField.get(null);
        Double previousDivisor = divisors.remove(source);

        SocHistoryDatabase counterDb = null;
        SocHistoryDatabase rateDb = null;
        try {
            counterDb = newOpenCounterDatabase("collector_counter_route", 101L);
            kindField.set(controlledObservation, ChargeSourceClassifier.Kind.UNKNOWN);
            counterDb.onChargeCounterObserved(source, 12.1);
            counterDb.onChargeCounterObserved(source, 12.4);
            assertEquals(12.1, doubleField(counterDb, "provisionalExternalKwh"), 0.0);

            kindField.set(controlledObservation, ChargeSourceClassifier.Kind.COUNTER);
            counterDb.onChargeCounterObserved(source, 12.8);
            ChargeCounterAccumulator accumulator =
                    (ChargeCounterAccumulator) field(counterDb, "chargingCounter");
            assertEquals(12.1, accumulator.baselineKwh(), 0.0);
            assertEquals(12.8, accumulator.lastRawKwh(), 0.0);
            assertEquals(0.7, accumulator.energyKwh(), 1e-9);

            rateDb = newOpenCounterDatabase("collector_rate_route", 202L);
            kindField.set(controlledObservation, ChargeSourceClassifier.Kind.UNKNOWN);
            rateDb.onChargeCounterObserved(source, 6.6);
            assertEquals(6.6, doubleField(rateDb, "provisionalExternalKwh"), 0.0);
            kindField.set(controlledObservation, ChargeSourceClassifier.Kind.RATE);
            rateDb.onChargeCounterObserved(source, 6.6);
            assertTrue(Double.isNaN(doubleField(rateDb, "provisionalExternalKwh")));
        } finally {
            closeDatabaseConnection(counterDb);
            closeDatabaseConnection(rateDb);
            if (previousObservation != null) {
                observations.put(source, previousObservation);
            } else {
                observations.remove(source);
            }
            loadedField.setBoolean(null, previousLoaded);
            divisors.remove(source);
            if (previousDivisor != null) divisors.put(source, previousDivisor);
        }
    }

    @Test
    public void terminalBmsStatesNeverTrainChargeSourceClassification() {
        int[] terminalStates = {0, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12};
        for (int state : terminalStates) {
            assertFalse("state=" + state,
                    BydDataCollector.shouldClassifyChargingSource(state));
        }
        assertTrue(BydDataCollector.shouldClassifyChargingSource(1));
        assertTrue(BydDataCollector.shouldClassifyChargingSource(15));
        assertTrue(BydDataCollector.shouldClassifyChargingSource(BydVehicleData.UNAVAILABLE));
        assertFalse(BydDataCollector.shouldClassifyChargingSource(15, true));
        assertTrue(BydDataCollector.shouldClassifyChargingSource(15, false));
    }

    @Test
    public void faultAndTimeoutStatesCannotForwardTerminalCounterTails() {
        assertTrue(BydDataCollector.allowsTerminalCounterTail(0));
        assertTrue(BydDataCollector.allowsTerminalCounterTail(2));
        assertTrue(BydDataCollector.allowsTerminalCounterTail(4));
        int[] rejectedStates = {3, 5, 6, 7, 8, 10, 11, 12};
        for (int state : rejectedStates) {
            assertFalse("state=" + state,
                    BydDataCollector.allowsTerminalCounterTail(state));
        }
    }

    @Test
    public void onlyFinishedConnectedRateCanSupplyTerminalRawMovement() {
        assertTrue(BydDataCollector.shouldObserveRawChargingSignal(15, false));
        assertTrue(BydDataCollector.shouldObserveRawChargingSignal(2, true));
        int[] rejectedStates = {0, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12};
        for (int state : rejectedStates) {
            assertFalse("state=" + state,
                    BydDataCollector.shouldObserveRawChargingSignal(state, false));
        }
    }

    @Test
    public void clusterMovementRejectsEveryTerminalStateExceptKnownFinishedTaperRate() {
        int[] terminalStates = {0, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12};
        for (int state : terminalStates) {
            boolean expected = state == 2;
            assertEquals("state=" + state, expected,
                    BydDataCollector.shouldObserveClusterRawChargingSignal(
                            state, 2, ChargeSourceClassifier.Kind.RATE));
        }
        assertFalse(BydDataCollector.shouldObserveClusterRawChargingSignal(
                2, 1, ChargeSourceClassifier.Kind.RATE));
        assertFalse(BydDataCollector.shouldObserveClusterRawChargingSignal(
                2, 2, ChargeSourceClassifier.Kind.UNKNOWN));
        assertTrue(BydDataCollector.shouldObserveClusterRawChargingSignal(
                15, 2, ChargeSourceClassifier.Kind.UNKNOWN));
    }

    @Test
    public void bmsObservationOrderRejectsQueuedOldCallbackAndAcceptsPostPollCallback() {
        BydDataCollector.ChargingObservationOrder order =
                new BydDataCollector.ChargingObservationOrder();

        long oldTerminalCallback = order.begin();
        long reconnectPoll = order.begin();
        order.recordBmsPoll(reconnectPoll);
        assertFalse(order.claimBmsCallback(oldTerminalCallback));
        assertEquals(reconnectPoll, order.latestBms());

        long newerTerminalCallback = order.begin();
        assertTrue(order.claimBmsCallback(newerTerminalCallback));
        assertEquals(newerTerminalCallback, order.latestBms());
    }

    @Test
    public void typedAndGenericGunOrderingRejectsOldEdgeAndAcceptsPostPollEdge() {
        BydDataCollector.ChargingObservationOrder order =
                new BydDataCollector.ChargingObservationOrder();

        long oldGunOutCallback = order.begin();
        long reconnectPoll = order.begin();
        order.recordGunPoll(reconnectPoll);
        assertFalse(order.claimGunCallback(oldGunOutCallback));
        assertEquals(reconnectPoll, order.latestGun());

        // Both typed and generic gun callbacks enter the same publishChargingGunEdge claim path.
        long newerGunOutCallback = order.begin();
        assertTrue(order.claimGunCallback(newerGunOutCallback));
        assertEquals(newerGunOutCallback, order.latestGun());
    }

    @Test
    public void chargingStateTimestampChangesOnlyOnAnActualStateTransition() {
        BydVehicleData charging = new BydVehicleData.Builder().chargingState(1).build();
        BydVehicleData same = charging.toBuilder().chargingState(1).build();
        BydVehicleData finished = same.toBuilder().chargingState(2).build();

        assertEquals(charging.chargingStateAtMs, same.chargingStateAtMs);
        assertTrue(finished.chargingStateAtMs >= same.chargingStateAtMs);
    }

    @Test
    public void newerGunCallbackCannotRollBackCurrentChargingType() {
        BydVehicleData currentPoll = new BydVehicleData.Builder()
                .chargingGunState(2)
                .chargingType(0)
                .vtolCharging(false)
                .build();
        BydVehicleData olderTypeWithNewerGun = new BydVehicleData.Builder()
                .chargingGunState(2)
                .chargingType(3)
                .vtolCharging(true)
                .build();

        BydVehicleData merged = BydDataCollector.preserveNewerChargingEdges(
                currentPoll, olderTypeWithNewerGun,
                1L, 1L,
                1L, 2L,
                7L, 7L);

        assertEquals(2, merged.chargingGunState);
        assertEquals(0, merged.chargingType);
        assertFalse(merged.vtolCharging);
    }

    @Test
    public void splitPollRefreshesOnlyLifecycleComponentsChangedByCallbacks() {
        BydVehicleData.Builder poll = new BydVehicleData.Builder()
                .chargingState(15)
                .chargingStateAtMs(200L)
                .chargingGunState(2)
                .chargingType(0);
        BydVehicleData unplugCallback = new BydVehicleData.Builder()
                .chargingState(2)
                .chargingStateAtMs(100L)
                .chargingGunState(1)
                .chargingType(0)
                .build();

        BydDataCollector.refreshChargingLifecycleContext(
                poll, unplugCallback, false, true, false);
        BydVehicleData gunOnly = poll.build();
        assertEquals(15, gunOnly.chargingState);
        assertEquals(200L, gunOnly.chargingStateAtMs);
        assertEquals(1, gunOnly.chargingGunState);

        BydDataCollector.refreshChargingLifecycleContext(
                poll, unplugCallback, true, false, false);
        BydVehicleData withTerminal = poll.build();
        assertEquals(2, withTerminal.chargingState);
        assertEquals(100L, withTerminal.chargingStateAtMs);
    }

    @Test
    public void authoritativeConnectionEdgeClearsRatesButKeepsEnergyCounter() {
        BydVehicleData live = new BydVehicleData.Builder()
                .chargingPowerKw(6.5)
                .externalChargingPowerKw(6.6)
                .chargePowerKw(6.4)
                .clusterChargePowerKw(650.0)
                .enginePowerKw(-6.3)
                .chargingCapacityKwh(8.2)
                .build();

        BydVehicleData cleared =
                BydDataCollector.clearChargingRateFields(live, false);

        assertTrue(Double.isNaN(cleared.chargingPowerKw));
        assertTrue(Double.isNaN(cleared.externalChargingPowerKw));
        assertTrue(Double.isNaN(cleared.chargePowerKw));
        assertTrue(Double.isNaN(cleared.clusterChargePowerKw));
        assertTrue(Double.isNaN(cleared.enginePowerKw));
        assertEquals(0L, cleared.chargingPowerAtMs);
        assertEquals(0L, cleared.externalChargingPowerAtMs);
        assertEquals(0L, cleared.chargePowerAtMs);
        assertEquals(0L, cleared.clusterChargePowerAtMs);
        assertEquals(0L, cleared.enginePowerAtMs);
        assertEquals(8.2, cleared.chargingCapacityKwh, 0.0);
    }

    @Test
    public void callbackRateClearWinsOverOlderInFlightPoll() {
        BydVehicleData stalePoll = new BydVehicleData.Builder()
                .chargingPowerKw(6.5)
                .externalChargingPowerKw(6.6)
                .chargePowerKw(6.4)
                .clusterChargePowerKw(650.0)
                .enginePowerKw(-6.3)
                .build();
        BydVehicleData callbackClear =
                BydDataCollector.clearChargingRateFields(stalePoll, false);

        BydVehicleData merged = BydDataCollector.preserveNewerChargingRateClear(
                stalePoll, callbackClear, 4L, 5L);

        assertTrue(Double.isNaN(merged.chargingPowerKw));
        assertTrue(Double.isNaN(merged.externalChargingPowerKw));
        assertTrue(Double.isNaN(merged.chargePowerKw));
        assertTrue(Double.isNaN(merged.clusterChargePowerKw));
        assertTrue(Double.isNaN(merged.enginePowerKw));
    }

    @Test
    public void lateNegativeEngineCallbackCannotUndoGunOutOrV2lClear() {
        BydVehicleData gunOut = new BydVehicleData.Builder()
                .chargingGunState(1)
                .vtolCharging(false)
                .build();
        BydVehicleData v2l = gunOut.toBuilder()
                .chargingGunState(5)
                .vtolCharging(true)
                .build();

        assertFalse(BydDataCollector.allowsEnginePowerCallback(
                gunOut, -3.2, false, 4L, 4L, 8L, 8L));
        assertFalse(BydDataCollector.allowsEnginePowerCallback(
                gunOut, -0.1, false, 4L, 4L, 8L, 8L));
        assertFalse(BydDataCollector.allowsEnginePowerCallback(
                v2l, -3.2, false, 4L, 4L, 8L, 8L));
        assertTrue(BydDataCollector.allowsEnginePowerCallback(
                gunOut, -3.2, true, 4L, 4L, 8L, 8L));
    }

    @Test
    public void chargingEdgeCrossingEngineDispatchFencesStaleNegativePower() {
        BydVehicleData reconnected = new BydVehicleData.Builder()
                .chargingGunState(2)
                .vtolCharging(false)
                .build();

        assertFalse(BydDataCollector.allowsEnginePowerCallback(
                reconnected, -3.2, true,
                2L, 3L, 4L, 4L, 6L, 6L, 8L, 8L));
        assertFalse(BydDataCollector.allowsEnginePowerCallback(
                reconnected, -3.2, true,
                3L, 3L, 4L, 5L, 6L, 6L, 8L, 8L));
        assertFalse(BydDataCollector.allowsEnginePowerCallback(
                reconnected, -3.2, true,
                3L, 3L, 5L, 5L, 6L, 7L, 8L, 8L));
        assertFalse(BydDataCollector.allowsEnginePowerCallback(
                reconnected, -3.2, true,
                3L, 3L, 5L, 5L, 7L, 7L, 8L, 9L));
        assertTrue(BydDataCollector.allowsEnginePowerCallback(
                reconnected, -3.2, true,
                3L, 3L, 5L, 5L, 7L, 7L, 9L, 9L));
        assertTrue(BydDataCollector.allowsEnginePowerCallback(
                reconnected, 12.0, true, 4L, 5L, 8L, 9L));
    }

    @Test
    public void callbackLifecycleFenceCoversBmsGunAndChargingTypeEdges() {
        assertTrue(BydDataCollector.isChargingCallbackLifecycleCurrent(
                1L, 1L, 2L, 2L, 3L, 3L));
        assertFalse(BydDataCollector.isChargingCallbackLifecycleCurrent(
                1L, 4L, 2L, 2L, 3L, 3L));
        assertFalse(BydDataCollector.isChargingCallbackLifecycleCurrent(
                1L, 1L, 2L, 4L, 3L, 3L));
        assertFalse(BydDataCollector.isChargingCallbackLifecycleCurrent(
                1L, 1L, 2L, 2L, 3L, 4L));
    }

    @Test
    public void queuedCallbackCannotCrossTerminalLifecycleEdge() throws Exception {
        AtomicLong bmsVersion = new AtomicLong(7L);
        CountDownLatch dispatchCaptured = new CountDownLatch(1);
        CountDownLatch edgePublished = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);

        Thread callback = new Thread(() -> {
            long bmsAtDispatch = bmsVersion.get();
            dispatchCaptured.countDown();
            try {
                if (!edgePublished.await(2, TimeUnit.SECONDS)) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            accepted.set(BydDataCollector.isChargingCallbackLifecycleCurrent(
                    bmsAtDispatch, bmsVersion.get(), 4L, 4L, 9L, 9L));
        }, "queued-charging-callback-test");

        callback.start();
        assertTrue(dispatchCaptured.await(2, TimeUnit.SECONDS));
        bmsVersion.incrementAndGet();
        edgePublished.countDown();
        callback.join(2_000L);

        assertFalse(callback.isAlive());
        assertFalse(accepted.get());
    }

    @Test
    public void terminalPollFencesPowerCallbackBeforeFinalPublication() throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(1, 0, 2);
        device.blockPowerGetter = true;
        BydVehicleData initial = new BydVehicleData.Builder()
                .chargingState(1)
                .chargingGunState(2)
                .chargingType(0)
                .chargingPowerKw(6.4)
                .build();
        BydDataCollector collector = newActiveCollector(device, initial);
        BydVehicleData.Builder pollBuilder = initial.toBuilder();
        AtomicReference<Object> observed = new AtomicReference<>();
        AtomicReference<Throwable> pollFailure = new AtomicReference<>();

        Thread poll = new Thread(() -> {
            try {
                observed.set(invokeCollectChargingOrdered(collector, pollBuilder));
            } catch (Throwable t) {
                pollFailure.set(t);
            }
        }, "terminal-poll");
        poll.start();
        assertTrue(device.powerGetterEntered.await(2, TimeUnit.SECONDS));

        Thread queuedPower = new Thread(() ->
                invokeChargingCallbackUnchecked(
                        collector, device, "onChargingPowerChanged", 6.8),
                "queued-old-power");
        queuedPower.start();
        awaitBlocked(queuedPower);

        device.releasePowerGetter.countDown();
        poll.join(2_000L);
        queuedPower.join(2_000L);
        rethrow(pollFailure.get());
        assertFalse(poll.isAlive());
        assertFalse(queuedPower.isAlive());

        BydVehicleData fenced = collector.getData();
        assertEquals(1, fenced.chargingGunState);
        assertEquals(2, fenced.chargingState);
        assertTrue(Double.isNaN(fenced.chargingPowerKw));

        // A callback dispatched after the terminal read but before final poll publication sees the
        // fenced snapshot and is rejected too.
        invokeChargingCallback(collector, device, "onChargingPowerChanged", 7.1);
        assertTrue(Double.isNaN(collector.getData().chargingPowerKw));

        invokePublishCollectedSnapshot(
                collector, pollBuilder.build(), observed.get(), 1L);
        BydVehicleData published = collector.getData();
        assertEquals(1, published.chargingGunState);
        assertEquals(2, published.chargingState);
        assertTrue(Double.isNaN(published.chargingPowerKw));
    }

    @Test
    public void phevRateProofMutationStaysInsideSnapshotPublicationFence()
            throws Exception {
        String source = readCollectorSource();
        int method = source.indexOf(
                "private BydVehicleData publishCollectedSnapshot");
        int mutation = source.indexOf(
                "ChargingDetector.beginPublicationMutation()", method);
        int mutationBody = source.indexOf('{', mutation);
        int mutationEnd = matchingBrace(source, mutationBody);
        int push = source.indexOf("pushChargingEvidence(", mutationBody);
        int proof = source.indexOf(
                "VehicleDataMonitor.observePhevSessionRateProofs(", mutationBody);

        assertTrue(method >= 0);
        assertTrue(mutation > method);
        assertTrue(push > mutationBody && push < mutationEnd);
        assertTrue(proof > push && proof < mutationEnd);
    }

    @Test
    public void terminalBmsLinearizesBeforeFinalGetterAndRetainsNewCounterCallback()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 2);
        device.chargingCapacity = 4.75;
        device.blockCapacityGetter = true;
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .chargingPowerKw(6.4)
                        .enginePowerKw(-3.0)
                        .chargingCapacityKwh(4.25)
                        .build());

        AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
        Thread terminal = new Thread(() -> {
            try {
                invokeChargingCallback(
                        collector, device, "onBatteryManagementDeviceStateChanged", 2);
            } catch (Throwable t) {
                terminalFailure.set(t);
            }
        }, "terminal-bms-final-getter");
        terminal.start();
        assertTrue(device.capacityGetterEntered.await(2, TimeUnit.SECONDS));

        // The edge and clear are already linearized even though final accounting I/O is blocked.
        assertEquals(1L, atomicLongField(collector, "bmsEdgeVersion").get());
        assertEquals(1L, atomicLongField(collector, "chargingRateClearVersion").get());
        assertEquals(2, collector.getData().chargingState);
        assertTrue(Double.isNaN(collector.getData().chargingPowerKw));
        assertTrue(Double.isNaN(collector.getData().enginePowerKw));

        AtomicReference<Throwable> counterFailure = new AtomicReference<>();
        Thread newerCounter = new Thread(() -> {
            try {
                invokeChargingCallback(
                        collector, device, "onChargingCapacityChanged", 5.0);
            } catch (Throwable t) {
                counterFailure.set(t);
            }
        }, "post-terminal-counter-callback");
        newerCounter.start();
        awaitBlocked(newerCounter);

        device.releaseCapacityGetter.countDown();
        terminal.join(2_000L);
        newerCounter.join(2_000L);
        rethrow(terminalFailure.get());
        rethrow(counterFailure.get());

        assertFalse(terminal.isAlive());
        assertFalse(newerCounter.isAlive());
        assertEquals(1L, device.capacityGetterCalls.get());
        assertEquals(5.0, collector.getData().chargingCapacityKwh, 0.0);
    }

    @Test
    public void terminalFinalGetterKeepsLegitimateLowerPostWrapObservation()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 2);
        device.chargingCapacity = 0.25;
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .chargingCapacityKwh(65.4)
                        .build());

        invokeChargingCallback(
                collector, device, "onBatteryManagementDeviceStateChanged", 2);

        assertEquals(2, collector.getData().chargingState);
        assertEquals(0.25, collector.getData().chargingCapacityKwh, 0.0);
    }

    @Test
    public void terminalPollKeepsLegitimateLowerPostResetObservation()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 2);
        device.chargingCapacity = 0.0;
        BydVehicleData initial = new BydVehicleData.Builder()
                .chargingState(1)
                .chargingGunState(2)
                .chargingType(0)
                .chargingCapacityKwh(4.25)
                .build();
        BydDataCollector collector = newActiveCollector(device, initial);

        invokeCollectChargingOrdered(collector, initial.toBuilder());

        assertEquals(2, collector.getData().chargingState);
        assertEquals(0.0, collector.getData().chargingCapacityKwh, 0.0);
    }

    @Test
    public void latestReservedCounterObservationWinsEvenWhenItIsLowerAfterWrap()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 2);
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .chargingCapacityKwh(65.4)
                        .build());
        AtomicReference<Throwable> highFailure = new AtomicReference<>();
        AtomicReference<Throwable> wrappedFailure = new AtomicReference<>();
        Thread high = new Thread(() -> {
            try {
                invokeChargingCallback(
                        collector, device, "onChargingCapacityChanged", 65.5);
            } catch (Throwable t) {
                highFailure.set(t);
            }
        }, "pre-wrap-counter");
        Thread wrapped = new Thread(() -> {
            try {
                invokeChargingCallback(
                        collector, device, "onChargingCapacityChanged", 0.25);
            } catch (Throwable t) {
                wrappedFailure.set(t);
            }
        }, "post-wrap-counter");

        Object transitionLock = field(collector, "chargingStateTransitionLock");
        Object edgeLock = field(collector, "chargingEdgePublishLock");
        synchronized (transitionLock) {
            synchronized (edgeLock) {
                high.start();
                awaitBlocked(high);
                wrapped.start();
                awaitBlocked(wrapped);
                invokeChargingCallback(
                        collector, device,
                        "onBatteryManagementDeviceStateChanged", 2);
                assertEquals(0.25, collector.getData().chargingCapacityKwh, 0.0);
            }
        }
        high.join(2_000L);
        wrapped.join(2_000L);
        rethrow(highFailure.get());
        rethrow(wrappedFailure.get());

        assertFalse(high.isAlive());
        assertFalse(wrapped.isAlive());
        assertEquals(0.25, collector.getData().chargingCapacityKwh, 0.0);
    }

    @Test
    public void preTerminalCapacityCallbackSurvivesBmsOvertakeWhenGetterUnavailable()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 2);
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .chargingPowerKw(6.4)
                        .chargingCapacityKwh(4.25)
                        .build());
        SocHistoryDatabase database =
                newOpenCounterDatabase("pre_terminal_capacity_bms", 301L);
        AtomicReference<Throwable> counterFailure = new AtomicReference<>();
        Thread counter = new Thread(() -> {
            try {
                invokeChargingCallback(
                        collector, device, "onChargingCapacityChanged", 4.75);
            } catch (Throwable t) {
                counterFailure.set(t);
            }
        }, "pre-terminal-capacity-bms");

        try (SharedDatabaseOverride ignored = new SharedDatabaseOverride(database)) {
            database.onChargeCounterObserved(
                    ChargeSourceClassifier.SRC_CAPACITY, 4.25);
            Object transitionLock = field(collector, "chargingStateTransitionLock");
            Object edgeLock = field(collector, "chargingEdgePublishLock");
            synchronized (transitionLock) {
                synchronized (edgeLock) {
                    counter.start();
                    awaitBlocked(counter);
                    // Reentrant acquisition lets the later terminal callback overtake deterministically.
                    invokeChargingCallback(
                            collector, device,
                            "onBatteryManagementDeviceStateChanged", 2);
                    assertEquals(4.75, collector.getData().chargingCapacityKwh, 0.0);
                }
            }
            counter.join(2_000L);
            rethrow(counterFailure.get());

            assertFalse(counter.isAlive());
            assertEquals(2, collector.getData().chargingState);
            assertTrue(Double.isNaN(collector.getData().chargingPowerKw));
            assertEquals(1L, atomicLongField(
                    collector, "capacityEdgeVersion").get());
            ChargeCounterAccumulator accumulator =
                    (ChargeCounterAccumulator) field(database, "chargingCounter");
            assertEquals(4.75, accumulator.lastRawKwh(), 0.0);
        } finally {
            closeDatabaseConnection(database);
        }
    }

    @Test
    public void preTerminalExternalCounterSurvivesBmsOvertakeWhenGettersUnavailable()
            throws Exception {
        try (SourceKindOverride ignoredKind = new SourceKindOverride(
                ChargeSourceClassifier.SRC_EXTERNAL,
                ChargeSourceClassifier.Kind.COUNTER)) {
            InterleavingChargingDevice device =
                    new InterleavingChargingDevice(2, 0, 2);
            UnavailableExternalCounterDevice instrument =
                    new UnavailableExternalCounterDevice();
            BydDataCollector collector = newActiveCollector(device,
                    new BydVehicleData.Builder()
                            .chargingState(1)
                            .chargingGunState(2)
                            .chargingType(0)
                            .chargingPowerKw(6.4)
                            .externalChargingPowerKw(12.1)
                            .externalChargingPowerAtMs(System.currentTimeMillis())
                            .build());
            setField(collector, "instrumentDevice", instrument);
            setField(collector, "activeInstrumentListenerDevice", instrument);
            setField(collector, "activeInstrumentListenerGeneration", 52L);
            SocHistoryDatabase database =
                    newOpenCounterDatabase("pre_terminal_external_bms", 302L);
            AtomicReference<Throwable> counterFailure = new AtomicReference<>();
            Thread counter = new Thread(() -> {
                try {
                    invokeInstrumentCallback(
                            collector, instrument,
                            "onExternalChargingPowerChanged", 12.8);
                } catch (Throwable t) {
                    counterFailure.set(t);
                }
            }, "pre-terminal-external-bms");

            try (SharedDatabaseOverride ignoredDb =
                         new SharedDatabaseOverride(database)) {
                database.onChargeCounterObserved(
                        ChargeSourceClassifier.SRC_EXTERNAL, 12.1);
                Object transitionLock = field(
                        collector, "chargingStateTransitionLock");
                Object edgeLock = field(collector, "chargingEdgePublishLock");
                synchronized (transitionLock) {
                    synchronized (edgeLock) {
                        counter.start();
                        awaitBlocked(counter);
                        invokeChargingCallback(
                                collector, device,
                                "onBatteryManagementDeviceStateChanged", 2);
                        assertEquals(12.8,
                                collector.getData().externalChargingPowerKw, 0.0);
                    }
                }
                counter.join(2_000L);
                rethrow(counterFailure.get());

                assertFalse(counter.isAlive());
                assertEquals(2, collector.getData().chargingState);
                assertTrue(Double.isNaN(collector.getData().chargingPowerKw));
                assertEquals(1L, atomicLongField(
                        collector, "externalPowerEdgeVersion").get());
                ChargeCounterAccumulator accumulator =
                        (ChargeCounterAccumulator) field(database, "chargingCounter");
                assertEquals(12.8, accumulator.lastRawKwh(), 0.0);
            } finally {
                closeDatabaseConnection(database);
            }
        }
    }

    @Test
    public void typeDerivedV2lPollFencesInterveningPositivePowerCallback()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 3, 1);
        device.blockPowerGetter = true;
        BydVehicleData initial = new BydVehicleData.Builder()
                .chargingState(1)
                .chargingGunState(2)
                .chargingType(0)
                .chargingPowerKw(6.4)
                .enginePowerKw(-3.0)
                .build();
        BydDataCollector collector = newActiveCollector(device, initial);
        BydVehicleData.Builder pollBuilder = initial.toBuilder();
        AtomicReference<Object> observed = new AtomicReference<>();
        AtomicReference<Throwable> pollFailure = new AtomicReference<>();

        Thread poll = new Thread(() -> {
            try {
                observed.set(invokeCollectChargingOrdered(collector, pollBuilder));
            } catch (Throwable t) {
                pollFailure.set(t);
            }
        }, "type-v2l-poll");
        poll.start();
        assertTrue(device.powerGetterEntered.await(2, TimeUnit.SECONDS));
        assertEquals(1L, atomicLongField(collector, "chargingTypeVersion").get());

        Thread interveningPower = new Thread(() ->
                invokeChargingCallbackUnchecked(
                        collector, device, "onChargingPowerChanged", 7.0),
                "post-type-positive-power");
        interveningPower.start();
        awaitBlocked(interveningPower);

        device.releasePowerGetter.countDown();
        poll.join(2_000L);
        interveningPower.join(2_000L);
        rethrow(pollFailure.get());

        assertFalse(poll.isAlive());
        assertFalse(interveningPower.isAlive());
        BydVehicleData fenced = collector.getData();
        assertEquals(3, fenced.chargingType);
        assertTrue(fenced.vtolCharging);
        assertTrue(Double.isNaN(fenced.chargingPowerKw));
        assertTrue(Double.isNaN(fenced.enginePowerKw));

        invokePublishCollectedSnapshot(
                collector, pollBuilder.build(), observed.get(), 1L);
        BydVehicleData published = collector.getData();
        assertEquals(3, published.chargingType);
        assertTrue(published.vtolCharging);
        assertTrue(Double.isNaN(published.chargingPowerKw));
        assertTrue(Double.isNaN(published.enginePowerKw));
    }

    @Test
    public void unchangedChargingTypePollKeepsCallbackOnlyPowerUpdate() throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 1);
        device.blockTypeGetter = true;
        BydVehicleData initial = new BydVehicleData.Builder()
                .chargingState(1)
                .chargingGunState(2)
                .chargingType(0)
                .build();
        BydDataCollector collector = newActiveCollector(device, initial);
        BydVehicleData.Builder pollBuilder = initial.toBuilder();
        AtomicReference<Object> observed = new AtomicReference<>();
        AtomicReference<Throwable> pollFailure = new AtomicReference<>();

        Thread poll = new Thread(() -> {
            try {
                observed.set(invokeCollectChargingOrdered(collector, pollBuilder));
            } catch (Throwable t) {
                pollFailure.set(t);
            }
        }, "unchanged-type-poll");
        poll.start();
        assertTrue(device.typeGetterEntered.await(2, TimeUnit.SECONDS));

        Thread callbackOnlyPower = new Thread(() ->
                invokeChargingCallbackUnchecked(
                        collector, device, "onChargingPowerChanged", 3.2),
                "callback-only-power");
        callbackOnlyPower.start();
        awaitBlocked(callbackOnlyPower);

        device.releaseTypeGetter.countDown();
        poll.join(2_000L);
        callbackOnlyPower.join(2_000L);
        rethrow(pollFailure.get());
        assertFalse(poll.isAlive());
        assertFalse(callbackOnlyPower.isAlive());
        assertEquals(0L, atomicLongField(collector, "chargingTypeVersion").get());
        assertEquals(3.2, collector.getData().chargingPowerKw, 0.0);

        invokePublishCollectedSnapshot(
                collector, pollBuilder.build(), observed.get(), 1L);
        assertEquals(3.2, collector.getData().chargingPowerKw, 0.0);
    }

    @Test
    public void stopWaitsForPublicationAndPreventsAccPollRestart() throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 1);
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(15)
                        .chargingGunState(2)
                        .chargingType(0)
                        .build());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        setField(collector, "pollScheduler", scheduler);

        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        collector.addChargingStateListener((previous, next) -> {
            if (next == 1) {
                listenerEntered.countDown();
                awaitUnchecked(releaseListener);
            }
        });

        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        Thread callback = new Thread(() -> {
            try {
                invokeChargingCallback(
                        collector, device, "onBatteryManagementDeviceStateChanged", 1);
            } catch (Throwable t) {
                callbackFailure.set(t);
            }
        }, "publication-in-flight");
        callback.start();
        assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));

        AtomicBoolean stopReturned = new AtomicBoolean();
        Thread stop = new Thread(() -> {
            collector.stop();
            stopReturned.set(true);
        }, "collector-stop");
        stop.start();
        awaitBlocked(stop);

        AtomicBoolean accReturned = new AtomicBoolean();
        Thread acc = new Thread(() -> {
            collector.setAccState(false);
            accReturned.set(true);
        }, "acc-after-stop");
        acc.start();
        awaitBlocked(acc);
        assertFalse(stopReturned.get());
        assertFalse(accReturned.get());

        releaseListener.countDown();
        callback.join(2_000L);
        stop.join(2_000L);
        acc.join(2_000L);
        rethrow(callbackFailure.get());

        assertTrue(stopReturned.get());
        assertTrue(accReturned.get());
        assertFalse(collector.isInitialized());
        assertTrue(field(collector, "pollScheduler") == null);
        assertTrue(scheduler.isShutdown());

        invokeChargingCallback(
                collector, device, "onBatteryManagementDeviceStateChanged", 2);
        assertEquals(1, collector.getData().chargingState);
    }

    @Test
    public void lifecycleHandoffDropsOldCallbackAndReconcilesMissedFinished()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 2);
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .chargingPowerKw(6.4)
                        .build());
        Object edgeLock = field(collector, "chargingEdgePublishLock");
        AtomicReference<Throwable> oldCallbackFailure = new AtomicReference<>();
        Thread oldCallback;

        synchronized (edgeLock) {
            oldCallback = new Thread(() -> {
                try {
                    invokeChargingCallback(
                            collector, device, "onChargingPowerChanged", 8.0);
                } catch (Throwable t) {
                    oldCallbackFailure.set(t);
                }
            }, "old-lifecycle-power");
            oldCallback.start();
            awaitBlocked(oldCallback);

            invokeNoArg(collector, "deactivateCallbackPublication");
            assertFalse(collector.isInitialized());

            // This FINISHED callback lands in the deactivated registration handoff and is dropped.
            invokeChargingCallback(
                    collector, device, "onBatteryManagementDeviceStateChanged", 2);
            assertEquals(1, collector.getData().chargingState);

            invokeNoArg(collector, "activateCallbackPublication");
            invokeNoArg(collector, "reconcileChargingAfterCallbackActivation");
            assertEquals(2, collector.getData().chargingState);
        }

        oldCallback.join(2_000L);
        rethrow(oldCallbackFailure.get());
        assertFalse(oldCallback.isAlive());
        assertEquals(2, collector.getData().chargingState);
        assertTrue(Double.isNaN(collector.getData().chargingPowerKw));
        assertEquals(1L, atomicLongField(
                collector, "callbackLifecycleGeneration").get());
    }

    @Test
    public void scheduledPollBlockedAtMonitorCannotRunAfterStopReturns()
            throws Exception {
        BydVehicleData initial = new BydVehicleData.Builder()
                .chargingState(15)
                .chargingGunState(1)
                .build();
        BydDataCollector collector = newActiveCollector(null, initial);
        long schedulerGeneration =
                atomicLongField(collector, "pollSchedulerGeneration").get();
        long chargingPollsBefore =
                atomicLongField(collector, "chargingPollGeneration").get();
        AtomicReference<Boolean> ran = new AtomicReference<>();
        AtomicReference<Throwable> taskFailure = new AtomicReference<>();
        Thread scheduled;

        synchronized (collector) {
            scheduled = new Thread(() -> {
                try {
                    ran.set(invokeCollectAllFromScheduler(
                            collector, schedulerGeneration));
                } catch (Throwable t) {
                    taskFailure.set(t);
                }
            }, "scheduled-poll-waiting-for-monitor");
            scheduled.start();
            awaitBlocked(scheduled);

            // Reentrant here, but identical to stop winning the collector monitor before the
            // already-scheduled task. stop() returns while that task is still waiting.
            collector.stop();
            assertFalse(collector.isInitialized());
            assertTrue(scheduled.isAlive());
        }

        scheduled.join(2_000L);
        rethrow(taskFailure.get());
        assertFalse(scheduled.isAlive());
        assertEquals(Boolean.FALSE, ran.get());
        assertEquals(chargingPollsBefore,
                atomicLongField(collector, "chargingPollGeneration").get());
        assertTrue(initial == collector.getData());
    }

    @Test
    public void rawChargingListenersObservePublicationOrder() throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 1);
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(15)
                        .chargingGunState(2)
                        .chargingType(0)
                        .build());
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        collector.addChargingStateListener((previous, next) -> {
            if (next == 1) {
                firstEntered.countDown();
                awaitUnchecked(releaseFirst);
            }
            delivered.add(previous + "->" + next);
        });

        Thread charging = new Thread(() -> invokeChargingCallbackUnchecked(
                collector, device, "onBatteryManagementDeviceStateChanged", 1),
                "raw-bms-charging");
        charging.start();
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS));

        Thread finished = new Thread(() -> invokeChargingCallbackUnchecked(
                collector, device, "onBatteryManagementDeviceStateChanged", 2),
                "raw-bms-finished");
        finished.start();
        awaitBlocked(finished);
        assertTrue(delivered.isEmpty());

        releaseFirst.countDown();
        charging.join(2_000L);
        finished.join(2_000L);

        assertFalse(charging.isAlive());
        assertFalse(finished.isAlive());
        assertEquals(Arrays.asList("15->1", "1->2"), delivered);
        assertEquals(2, collector.getData().chargingState);
    }

    @Test
    public void terminalConnectedPollCannotRestampFrozenNegativeEnginePower() throws Exception {
        InterleavingChargingDevice chargingDevice =
                new InterleavingChargingDevice(2, 0, 2);
        long originalEngineAtMs = System.currentTimeMillis();
        BydVehicleData initial = new BydVehicleData.Builder()
                .chargingState(1)
                .chargingGunState(2)
                .chargingType(0)
                .enginePowerKw(-3.0)
                .enginePowerAtMs(originalEngineAtMs)
                .build();
        BydDataCollector collector = newActiveCollector(chargingDevice, initial);
        setField(collector, "engineDevice", new FrozenEngineDevice(-3.0));
        setField(collector, "accIsOn", false);

        BydVehicleData.Builder pollBuilder = initial.toBuilder();
        Object observed = invokeCollectChargingOrdered(collector, pollBuilder);
        invokeCollectEngineOrdered(collector, pollBuilder, observed);

        BydVehicleData collected = pollBuilder.build();
        assertEquals(2, collected.chargingState);
        assertEquals(2, collected.chargingGunState);
        assertTrue(Double.isNaN(collected.enginePowerKw));
        assertEquals(0L, collected.enginePowerAtMs);

        invokePublishCollectedSnapshot(collector, collected, observed, 1L);
        assertTrue(Double.isNaN(collector.getData().enginePowerKw));
        assertEquals(0L, collector.getData().enginePowerAtMs);

        assertFalse(BydDataCollector.allowsEnginePowerObservation(
                2, 2, false, -3.0, false, false));
        assertFalse(BydDataCollector.allowsEnginePowerObservation(
                2, 2, false, -3.0, true, false));
        assertTrue(BydDataCollector.allowsEnginePowerObservation(
                0, 1, false, -3.0, true, false));
    }

    @Test
    public void unanchoredAmbiguousPhevFallbackIsNotBlindlyDoubled() {
        assertEquals(16.5, BydDataCollector.resolvePhevGrossRemainKwh(
                16.5, 77.0, 0.0, false), 0.0);
        assertEquals(16.5, BydDataCollector.resolvePhevGrossRemainKwh(
                8.25, 77.0, 0.0, true), 0.0);
        assertEquals(16.5, BydDataCollector.resolvePhevGrossRemainKwh(
                16.5, 77.0, 21.5, true), 0.0);
    }

    @Test
    public void bevRemainEnergyRejectsCapturedOscillatingFrames() {
        assertFalse(BydDataCollector.isPlausibleBevRemainKwh(
                4.6, 24.0, 82.5));
        assertFalse(BydDataCollector.isPlausibleBevRemainKwh(
                1.2, 24.0, 82.5));
        assertTrue(BydDataCollector.isPlausibleBevRemainKwh(
                20.0, 24.0, 82.5));
        assertTrue(BydDataCollector.isPlausibleBevRemainKwh(
                22.8, 28.0, 82.5));
    }

    @Test
    public void startupDrivetrainCacheRejectsJunkBeforePersistedRateCalibration()
            throws Exception {
        String source = ChargeSourceClassifier.SRC_CLUSTER;
        Field loadedField = ChargeSourceClassifier.class.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        boolean previousLoaded = loadedField.getBoolean(null);
        loadedField.setBoolean(null, true);

        Field observationsField = ChargeSourceClassifier.class.getDeclaredField("observations");
        observationsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> observations = (Map<String, Object>) observationsField.get(null);
        Object previousObservation = observations.get(source);
        Class<?> observationClass =
                Class.forName("com.overdrive.app.byd.ChargeSourceClassifier$Observation");
        Constructor<?> observationConstructor = observationClass.getDeclaredConstructor();
        observationConstructor.setAccessible(true);
        Object rateObservation = observationConstructor.newInstance();
        Field kindField = observationClass.getDeclaredField("kind");
        kindField.setAccessible(true);
        kindField.set(rateObservation, ChargeSourceClassifier.Kind.RATE);
        observations.put(source, rateObservation);

        Field divisorsField = ChargeRateResolver.class.getDeclaredField("latchedRateDivisors");
        divisorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Double> divisors = (Map<String, Double>) divisorsField.get(null);
        Double previousDivisor = divisors.put(source, 100.0);

        try {
            // This is exactly the dangerous persisted state: raw 359.4 resolves to a believable
            // ~3.6 kW before the current process has observed enough drivetrain evidence.
            assertEquals(3.594, ChargeRateResolver.rateKw(source, 359.4), 1e-9);

            BydDataCollector collector = newActiveCollector(null,
                    new BydVehicleData.Builder()
                            .chargingState(15)
                            .chargingGunState(2)
                            .build());
            setField(collector, "cachedDrivetrain", BydDataCollector.DRIVETRAIN_BEV);
            setField(collector, "lastDrivetrainProbeMs", System.currentTimeMillis());
            setField(collector, "establishedDrivetrain",
                    BydDataCollector.DRIVETRAIN_UNKNOWN);

            assertFalse(collector.isRawChargingSourceValueAdmissibleForCurrentDrivetrain(359.4));
            assertFalse(collector.isRawChargingSourceValueAdmissibleForCurrentDrivetrain(1320.10));
            assertTrue(collector.isRawChargingSourceValueAdmissibleForCurrentDrivetrain(300.0));

            // A real BEV earns trust only after confirmation; only then may the BEV-valid bands pass.
            setField(collector, "establishedDrivetrain", BydDataCollector.DRIVETRAIN_BEV);
            assertTrue(collector.isRawChargingSourceValueAdmissibleForCurrentDrivetrain(359.4));
        } finally {
            if (previousObservation != null) {
                observations.put(source, previousObservation);
            } else {
                observations.remove(source);
            }
            loadedField.setBoolean(null, previousLoaded);
            divisors.remove(source);
            if (previousDivisor != null) divisors.put(source, previousDivisor);
        }
    }

    @Test
    public void concurrentStartupProbesCannotDoubleConfirmProvisionalBev()
            throws Exception {
        BydDataCollector collector = newActiveCollector(null,
                new BydVehicleData.Builder()
                        .chargingState(15)
                        .chargingGunState(2)
                        .build());
        BlockingSentinelStatisticDevice statistic =
                new BlockingSentinelStatisticDevice();
        setField(collector, "statisticDevice", statistic);

        // Keep this test independent of any model/capacity state another focused test installed.
        SocHistoryDatabase database = SocHistoryDatabase.getInstance();
        Object previousEstimator = field(database, "sohEstimator");
        setField(database, "sohEstimator", null);
        AtomicReference<Boolean> firstAccepted = new AtomicReference<>();
        AtomicReference<Boolean> secondAccepted = new AtomicReference<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try {
                firstAccepted.set(
                        collector.isRawChargingSourceValueAdmissibleForCurrentDrivetrain(359.4));
            } catch (Throwable t) {
                firstFailure.set(t);
            }
        }, "first-drivetrain-probe");
        Thread second = new Thread(() -> {
            try {
                secondAccepted.set(
                        collector.isRawChargingSourceValueAdmissibleForCurrentDrivetrain(1320.10));
            } catch (Throwable t) {
                secondFailure.set(t);
            }
        }, "second-drivetrain-probe");

        try {
            first.start();
            assertTrue(statistic.fuelPctGetterEntered.await(2, TimeUnit.SECONDS));
            second.start();
            awaitBlocked(second);
            assertEquals(1L, statistic.fuelPctCalls.get());
            assertEquals(0L, statistic.fuelRangeCalls.get());

            statistic.releaseFuelPctGetter.countDown();
            first.join(2_000L);
            second.join(2_000L);
            rethrow(firstFailure.get());
            rethrow(secondFailure.get());

            assertFalse(first.isAlive());
            assertFalse(second.isAlive());
            assertEquals(Boolean.FALSE, firstAccepted.get());
            assertEquals(Boolean.FALSE, secondAccepted.get());
            assertEquals(1L, statistic.fuelPctCalls.get());
            assertEquals(1L, statistic.fuelRangeCalls.get());
            assertEquals(BydDataCollector.DRIVETRAIN_UNKNOWN,
                    ((Number) field(collector, "establishedDrivetrain")).intValue());
        } finally {
            statistic.releaseFuelPctGetter.countDown();
            setField(database, "sohEstimator", previousEstimator);
        }
    }

    @Test
    public void gunOutReadsGetterOnlyFinalCounterBeforeAuthoritativeStop() throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 1);
        device.chargingCapacity = 4.75;
        device.blockCapacityGetter = true;
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .chargingCapacityKwh(4.25)
                        .build());
        com.overdrive.app.monitor.ChargingDetector detector =
                com.overdrive.app.monitor.ChargingDetector.getInstance();
        CountDownLatch authoritativeStop = new CountDownLatch(1);
        AtomicReference<Double> counterAtStop = new AtomicReference<>();
        com.overdrive.app.monitor.ChargingDetector.AuthoritativeStopListener listener = source -> {
            counterAtStop.set(collector.getData().chargingCapacityKwh);
            authoritativeStop.countDown();
        };
        detector.addAuthoritativeStopListener(listener);

        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        Thread gunOut = new Thread(() -> {
            try {
                invokeChargingCallback(
                        collector, device, "onChargingGunStateChanged", 1);
            } catch (Throwable t) {
                callbackFailure.set(t);
            }
        }, "gun-out-final-counter");
        try {
            gunOut.start();
            assertTrue(device.capacityGetterEntered.await(2, TimeUnit.SECONDS));

            assertEquals(1L, atomicLongField(collector, "gunEdgeVersion").get());
            assertEquals(1L, atomicLongField(
                    collector, "chargingRateClearVersion").get());
            assertEquals(1, collector.getData().chargingGunState);
            assertEquals(4.25, collector.getData().chargingCapacityKwh, 0.0);
            assertEquals(1L, authoritativeStop.getCount());
            assertTrue(detector.isCharging());

            device.releaseCapacityGetter.countDown();
            gunOut.join(2_000L);
            rethrow(callbackFailure.get());

            assertFalse(gunOut.isAlive());
            assertTrue(authoritativeStop.await(2, TimeUnit.SECONDS));
            assertEquals(1L, device.capacityGetterCalls.get());
            assertEquals(4.75, collector.getData().chargingCapacityKwh, 0.0);
            assertEquals(4.75, counterAtStop.get(), 0.0);
        } finally {
            device.releaseCapacityGetter.countDown();
            detector.removeAuthoritativeStopListener(listener);
        }
    }

    @Test
    public void preTerminalCapacityCallbackSurvivesGunOutWhenGetterUnavailable()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 1);
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .chargingPowerKw(6.4)
                        .chargingCapacityKwh(4.25)
                        .build());
        SocHistoryDatabase database =
                newOpenCounterDatabase("pre_terminal_capacity_gun", 303L);
        AtomicReference<Throwable> counterFailure = new AtomicReference<>();
        Thread counter = new Thread(() -> {
            try {
                invokeChargingCallback(
                        collector, device, "onChargingCapacityChanged", 4.9);
            } catch (Throwable t) {
                counterFailure.set(t);
            }
        }, "pre-terminal-capacity-gun");

        try (SharedDatabaseOverride ignored = new SharedDatabaseOverride(database)) {
            database.onChargeCounterObserved(
                    ChargeSourceClassifier.SRC_CAPACITY, 4.25);
            Object edgeLock = field(collector, "chargingEdgePublishLock");
            synchronized (edgeLock) {
                counter.start();
                awaitBlocked(counter);
                invokeChargingCallback(
                        collector, device, "onChargingGunStateChanged", 1);
                assertEquals(4.9, collector.getData().chargingCapacityKwh, 0.0);
            }
            counter.join(2_000L);
            rethrow(counterFailure.get());

            assertFalse(counter.isAlive());
            assertEquals(1, collector.getData().chargingGunState);
            assertTrue(Double.isNaN(collector.getData().chargingPowerKw));
            assertEquals(1L, atomicLongField(
                    collector, "capacityEdgeVersion").get());
            ChargeCounterAccumulator accumulator =
                    (ChargeCounterAccumulator) field(database, "chargingCounter");
            assertEquals(4.9, accumulator.lastRawKwh(), 0.0);
            assertFalse(ChargingDetector.getInstance().isCharging());
        } finally {
            closeDatabaseConnection(database);
        }
    }

    @Test
    public void finishedCallbackClearsStalePowerDuringDetectorOffDebounce()
            throws Exception {
        try (SourceKindOverride ignored = new SourceKindOverride(
                ChargeSourceClassifier.SRC_EXTERNAL,
                ChargeSourceClassifier.Kind.RATE)) {
            InterleavingChargingDevice device =
                    new InterleavingChargingDevice(2, 0, 2);
            BydDataCollector collector = newActiveCollector(device,
                    new BydVehicleData.Builder()
                            .chargingState(1)
                            .chargingGunState(2)
                            .chargingType(0)
                            .chargingPowerKw(6.4)
                            .externalChargingPowerKw(6.5)
                            .chargePowerKw(6.3)
                            .clusterChargePowerKw(640.0)
                            .enginePowerKw(-3.0)
                            .build());
            armDetectorDebouncedSession();

            invokeChargingCallback(
                    collector, device, "onBatteryManagementDeviceStateChanged", 2);

            ChargingDetector detector = ChargingDetector.getInstance();
            assertTrue("OFF debounce must still be active", detector.isCharging());
            BydVehicleData terminal = collector.getData();
            assertEquals(2, terminal.chargingState);
            assertTrue(Double.isNaN(terminal.chargingPowerKw));
            assertTrue(Double.isNaN(terminal.externalChargingPowerKw));
            assertTrue(Double.isNaN(terminal.chargePowerKw));
            assertTrue(Double.isNaN(terminal.clusterChargePowerKw));
            assertTrue(Double.isNaN(terminal.enginePowerKw));
            assertEquals(0L, terminal.enginePowerAtMs);

            double monitorPackFlow = invokeFreshNegativeEnginePackFlow(
                    terminal, System.currentTimeMillis());
            assertTrue(Double.isNaN(monitorPackFlow));
            assertFalse(invokeAbrpCanPublishEnginePower(
                    terminal, System.currentTimeMillis(), false, true));
        }
    }

    @Test
    public void genuinePostFinishedRateMovementAndEngineObservationRestoresTaper()
            throws Exception {
        try (SourceKindOverride ignored = new SourceKindOverride(
                ChargeSourceClassifier.SRC_DEVICE,
                ChargeSourceClassifier.Kind.RATE)) {
            InterleavingChargingDevice chargingDevice =
                    new InterleavingChargingDevice(2, 0, 2);
            Object engineDevice = new Object();
            BydDataCollector collector = newActiveCollector(chargingDevice,
                    new BydVehicleData.Builder()
                            .chargingState(1)
                            .chargingGunState(2)
                            .chargingType(0)
                            .chargingPowerKw(3.2)
                            .enginePowerKw(-3.2)
                            .build());
            setField(collector, "engineDevice", engineDevice);
            setField(collector, "activeEngineListenerDevice", engineDevice);
            setField(collector, "activeEngineListenerGeneration", 73L);
            setField(collector, "accIsOn", false);
            setField(collector, "statisticDevice", new FuelStatisticDevice(55, 620));

            armDetectorDebouncedSession();
            ChargingDetector detector = ChargingDetector.getInstance();
            detector.observeRawChargingSignal(ChargeSourceClassifier.SRC_DEVICE, 3.2);

            invokeChargingCallback(
                    collector, chargingDevice,
                    "onBatteryManagementDeviceStateChanged", 2);
            BydVehicleData cleared = collector.getData();
            long finishedAtMs = cleared.chargingStateAtMs;
            assertTrue(Double.isNaN(cleared.chargingPowerKw));
            assertTrue(Double.isNaN(cleared.enginePowerKw));

            // A current poll confirms the pending callback stop. The connected taper path must then
            // rebuild evidence exclusively from observations newer than this FINISHED boundary.
            detector.confirmBmsState(2);
            assertFalse(detector.isCharging());
            waitForWallClockAfter(finishedAtMs);

            invokeChargingCallback(
                    collector, chargingDevice, "onChargingPowerChanged", 3.0);
            invokeEngineCallback(
                    collector, engineDevice, "onDataEventChanged",
                    new Object[] {BydFeatureIds.ENGINE_POWER, -3});

            BydVehicleData taperSnapshot = collector.getData();
            assertEquals(2, taperSnapshot.chargingState);
            assertEquals(3.0, taperSnapshot.chargingPowerKw, 0.0);
            assertTrue(taperSnapshot.chargingPowerAtMs > finishedAtMs);
            assertTrue(taperSnapshot.chargingPowerChangedAtMs > finishedAtMs);
            assertEquals(-3.0, taperSnapshot.enginePowerKw, 0.0);
            assertTrue(taperSnapshot.enginePowerAtMs > finishedAtMs);
            // The rate change is recorded as weak evidence until the detector ingests the
            // independent engine observation on its next poll. Taper proof below uses the
            // source and engine timestamps directly, so strong L3 promotion is not required here.
            assertTrue(detector.hasRecentRawChargingSignal());
            assertTrue(detector.isTerminalSessionBarrierActive());
            assertFalse(detector.isCharging());

            Object previousCollector = staticField(BydDataCollector.class, "instance");
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            long previousClosedTaper =
                    ((Number) field(monitor, "closedTaperFinishedAtMs")).longValue();
            try {
                setStaticField(BydDataCollector.class, "instance", collector);
                setField(monitor, "closedTaperFinishedAtMs", 0L);
                VehicleDataMonitor.ChargingSnapshot coherent =
                        monitor.getChargingSnapshot();
                assertTrue(coherent != null);
                assertSame(taperSnapshot, coherent.getVehicleData());
                ChargingStateData state = coherent.getChargingState();
                assertTrue(state != null);
                assertEquals(ChargingStateData.ChargingStatus.FINISHED, state.status);
                assertTrue(state.isTaperCharging);
                assertEquals(3.0, state.chargingPowerKW, 0.01);

                boolean telemetryCharging =
                        invokeAbrpIsChargingForTelemetry(state, taperSnapshot);
                assertTrue(telemetryCharging);
                assertEquals(-3.0, invokeAbrpSelectTelemetryPower(
                        taperSnapshot, state, System.currentTimeMillis(),
                        false, telemetryCharging), 0.0);
            } finally {
                setField(monitor, "closedTaperFinishedAtMs", previousClosedTaper);
                setStaticField(BydDataCollector.class, "instance", previousCollector);
            }
        }
    }

    @Test
    public void unchangedPostFinishedRateAndEngineCallbacksCannotRestoreTaper()
            throws Exception {
        try (SourceKindOverride ignored = new SourceKindOverride(
                ChargeSourceClassifier.SRC_DEVICE,
                ChargeSourceClassifier.Kind.RATE)) {
            InterleavingChargingDevice chargingDevice =
                    new InterleavingChargingDevice(2, 0, 2);
            Object engineDevice = new Object();
            BydDataCollector collector = newActiveCollector(chargingDevice,
                    new BydVehicleData.Builder()
                            .chargingState(1)
                            .chargingGunState(2)
                            .chargingType(0)
                            .chargingPowerKw(3.0)
                            .enginePowerKw(-3.0)
                            .build());
            setField(collector, "engineDevice", engineDevice);
            setField(collector, "activeEngineListenerDevice", engineDevice);
            setField(collector, "activeEngineListenerGeneration", 73L);
            setField(collector, "accIsOn", false);
            setField(collector, "statisticDevice", new FuelStatisticDevice(55, 620));

            armDetectorDebouncedSession();
            ChargingDetector detector = ChargingDetector.getInstance();
            detector.observeRawChargingSignal(ChargeSourceClassifier.SRC_DEVICE, 3.0);
            invokeChargingCallback(
                    collector, chargingDevice,
                    "onBatteryManagementDeviceStateChanged", 2);
            long finishedAtMs = collector.getData().chargingStateAtMs;
            detector.confirmBmsState(2);
            assertFalse(detector.isCharging());
            waitForWallClockAfter(finishedAtMs);

            invokeChargingCallback(
                    collector, chargingDevice, "onChargingPowerChanged", 3.0);
            invokeEngineCallback(
                    collector, engineDevice, "onDataEventChanged",
                    new Object[] {BydFeatureIds.ENGINE_POWER, -3});

            BydVehicleData repeated = collector.getData();
            assertTrue(repeated.chargingPowerAtMs > finishedAtMs);
            assertTrue(repeated.enginePowerAtMs > finishedAtMs);
            assertTrue(repeated.chargingPowerChangedAtMs <= finishedAtMs);

            Object previousCollector = staticField(BydDataCollector.class, "instance");
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            long previousClosedTaper =
                    ((Number) field(monitor, "closedTaperFinishedAtMs")).longValue();
            try {
                setStaticField(BydDataCollector.class, "instance", collector);
                setField(monitor, "closedTaperFinishedAtMs", 0L);
                VehicleDataMonitor.ChargingSnapshot coherent =
                        monitor.getChargingSnapshot();
                assertTrue(coherent != null);
                assertSame(repeated, coherent.getVehicleData());
                ChargingStateData state = coherent.getChargingState();
                assertTrue(state != null);
                assertEquals(ChargingStateData.ChargingStatus.FINISHED, state.status);
                assertFalse(state.isTaperCharging);
                assertEquals(0.0, state.chargingPowerKW, 0.0);
            } finally {
                setField(monitor, "closedTaperFinishedAtMs", previousClosedTaper);
                setStaticField(BydDataCollector.class, "instance", previousCollector);
            }
        }
    }

    @Test
    public void delayedBmsCallbacksCannotMintANewFinishedEpochAfterTerminalBarrier()
            throws Exception {
        InterleavingChargingDevice chargingDevice =
                new InterleavingChargingDevice(2, 0, 2);
        long finishedAtMs = 12_345L;
        BydDataCollector collector = newActiveCollector(chargingDevice,
                new BydVehicleData.Builder()
                        .chargingState(2)
                        .chargingStateAtMs(finishedAtMs)
                        .chargingGunState(2)
                        .chargingType(0)
                        .build());
        ChargingDetector detector = ChargingDetector.getInstance();
        detector.confirmBmsState(2);
        assertTrue(detector.isTerminalSessionBarrierActive());

        invokeChargingCallback(
                collector, chargingDevice,
                "onBatteryManagementDeviceStateChanged", 1);
        invokeChargingCallback(
                collector, chargingDevice,
                "onBatteryManagementDeviceStateChanged", 2);

        BydVehicleData afterDelayedCallbacks = collector.getData();
        assertEquals(2, afterDelayedCallbacks.chargingState);
        assertEquals(finishedAtMs, afterDelayedCallbacks.chargingStateAtMs);
        assertTrue(detector.isTerminalSessionBarrierActive());
    }

    @Test
    public void enginePowerScalingUsesSignedOemThreshold() {
        assertEquals(15.0, BydDataCollector.scaleEnginePowerKw(150.0), 0.0);
        assertEquals(100.0, BydDataCollector.scaleEnginePowerKw(100.0), 0.0);
        assertEquals(-150.0, BydDataCollector.scaleEnginePowerKw(-150.0), 0.0);
    }

    @Test
    public void callbackAndAccInvalidationAdvanceExternalPublicationGeneration()
            throws Exception {
        InterleavingChargingDevice device =
                new InterleavingChargingDevice(2, 0, 1);
        BydDataCollector collector = newActiveCollector(device,
                new BydVehicleData.Builder()
                        .chargingState(1)
                        .chargingGunState(2)
                        .chargingType(0)
                        .enginePowerKw(-3.0)
                        .build());
        ChargingDetector detector = ChargingDetector.getInstance();

        ChargingDetector.StateSnapshot beforeCallback =
                detector.getStateSnapshot();
        invokeChargingCallback(
                collector, device, "onChargingPowerChanged", 3.2);
        ChargingDetector.StateSnapshot afterCallback =
                detector.getStateSnapshot();

        assertEquals(3.2, collector.getData().chargingPowerKw, 0.0);
        assertTrue(afterCallback.externalGeneration
                > beforeCallback.externalGeneration);
        assertTrue(ChargingDetector.isPublicationWindowStable(
                afterCallback, afterCallback));

        ChargingDetector.StateSnapshot beforeAcc =
                detector.getStateSnapshot();
        collector.setAccState(false);
        ChargingDetector.StateSnapshot afterAcc =
                detector.getStateSnapshot();

        assertTrue(Double.isNaN(collector.getData().enginePowerKw));
        assertTrue(afterAcc.externalGeneration > beforeAcc.externalGeneration);
        assertTrue(ChargingDetector.isPublicationWindowStable(
                afterAcc, afterAcc));
    }

    private static BydDataCollector newActiveCollector(
            Object chargingDevice, BydVehicleData initial) throws Exception {
        Constructor<BydDataCollector> constructor =
                BydDataCollector.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        BydDataCollector collector = constructor.newInstance();
        @SuppressWarnings("unchecked")
        AtomicReference<BydVehicleData> snapshot =
                (AtomicReference<BydVehicleData>) field(collector, "snapshot");
        snapshot.set(initial);
        setField(collector, "chargingDevice", chargingDevice);
        setField(collector, "activeChargingListenerDevice", chargingDevice);
        setField(collector, "activeChargingListenerGeneration", 41L);
        setField(collector, "initialized", true);
        return collector;
    }

    private static Object invokeCollectChargingOrdered(
            BydDataCollector collector, BydVehicleData.Builder builder) throws Exception {
        Method method = BydDataCollector.class.getDeclaredMethod(
                "collectChargingOrdered", BydVehicleData.Builder.class);
        method.setAccessible(true);
        try {
            return method.invoke(collector, builder);
        } catch (InvocationTargetException e) {
            throwCause(e);
            return null;
        }
    }

    private static void invokeCollectEngineOrdered(
            BydDataCollector collector, BydVehicleData.Builder builder, Object observed)
            throws Exception {
        Method target = null;
        for (Method method : BydDataCollector.class.getDeclaredMethods()) {
            if ("collectEngineOrdered".equals(method.getName())) {
                target = method;
                break;
            }
        }
        if (target == null) throw new AssertionError("collectEngineOrdered not found");
        target.setAccessible(true);
        try {
            target.invoke(collector, builder, observed);
        } catch (InvocationTargetException e) {
            throwCause(e);
        }
    }

    private static void invokePublishCollectedSnapshot(
            BydDataCollector collector, BydVehicleData collected,
            Object observed, long generation) throws Exception {
        Method target = null;
        for (Method method : BydDataCollector.class.getDeclaredMethods()) {
            if ("publishCollectedSnapshot".equals(method.getName())) {
                target = method;
                break;
            }
        }
        if (target == null) throw new AssertionError("publishCollectedSnapshot not found");
        target.setAccessible(true);
        try {
            target.invoke(collector, collected, observed, generation);
        } catch (InvocationTargetException e) {
            throwCause(e);
        }
    }

    private static void invokeChargingCallback(
            BydDataCollector collector, Object device, String methodName, Object value)
            throws Exception {
        Method method = BydDataCollector.class.getDeclaredMethod(
                "onChargingCallback", Object.class, long.class, String.class, Object[].class);
        method.setAccessible(true);
        try {
            method.invoke(collector, device, 41L, methodName, new Object[] {value});
        } catch (InvocationTargetException e) {
            throwCause(e);
        }
    }

    private static void invokeChargingCallbackUnchecked(
            BydDataCollector collector, Object device, String methodName, Object value) {
        try {
            invokeChargingCallback(collector, device, methodName, value);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private static void invokeInstrumentCallback(
            BydDataCollector collector, Object device, String methodName, Object value)
            throws Exception {
        Method method = BydDataCollector.class.getDeclaredMethod(
                "onInstrumentCallback", Object.class, long.class, String.class, Object[].class);
        method.setAccessible(true);
        try {
            method.invoke(collector, device, 52L, methodName, new Object[] {value});
        } catch (InvocationTargetException e) {
            throwCause(e);
        }
    }

    private static void invokeEngineCallback(
            BydDataCollector collector, Object device, String methodName, Object[] args)
            throws Exception {
        Method method = BydDataCollector.class.getDeclaredMethod(
                "onEngineCallback", Object.class, long.class, String.class, Object[].class);
        method.setAccessible(true);
        try {
            method.invoke(collector, device, 73L, methodName, args);
        } catch (InvocationTargetException e) {
            throwCause(e);
        }
    }

    private static void invokeNoArg(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        try {
            method.invoke(target);
        } catch (InvocationTargetException e) {
            throwCause(e);
        }
    }

    private static boolean invokeCollectAllFromScheduler(
            BydDataCollector collector, long generation) throws Exception {
        Method method = BydDataCollector.class.getDeclaredMethod(
                "collectAllFromScheduler", long.class);
        method.setAccessible(true);
        try {
            return (Boolean) method.invoke(collector, generation);
        } catch (InvocationTargetException e) {
            throwCause(e);
            return false;
        }
    }

    private static double invokeFreshNegativeEnginePackFlow(
            BydVehicleData data, long nowMs) throws Exception {
        Method method = VehicleDataMonitor.class.getDeclaredMethod(
                "freshNegativeEnginePackFlow",
                double.class, long.class, long.class, long.class);
        method.setAccessible(true);
        return (Double) method.invoke(
                null, data.enginePowerKw, data.enginePowerAtMs,
                data.chargingStateAtMs, nowMs);
    }

    private static boolean invokeAbrpCanPublishEnginePower(
            BydVehicleData data, long nowMs, boolean accOn, boolean charging)
            throws Exception {
        Class<?> service = Class.forName(
                "com.overdrive.app.abrp.AbrpTelemetryService");
        Method method = service.getDeclaredMethod(
                "canPublishEnginePower",
                BydVehicleData.class, long.class, boolean.class, boolean.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, data, nowMs, accOn, charging);
    }

    private static boolean invokeAbrpIsChargingForTelemetry(
            ChargingStateData state, BydVehicleData data) throws Exception {
        Class<?> service = Class.forName(
                "com.overdrive.app.abrp.AbrpTelemetryService");
        Method method = service.getDeclaredMethod(
                "isChargingForTelemetry",
                ChargingStateData.class, BydVehicleData.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, state, data);
    }

    private static double invokeAbrpSelectTelemetryPower(
            BydVehicleData data, ChargingStateData state,
            long nowMs, boolean accOn, boolean charging) throws Exception {
        Class<?> service = Class.forName(
                "com.overdrive.app.abrp.AbrpTelemetryService");
        Method method = service.getDeclaredMethod(
                "selectTelemetryPower",
                BydVehicleData.class, ChargingStateData.class,
                long.class, boolean.class, boolean.class);
        method.setAccessible(true);
        return (Double) method.invoke(
                null, data, state, nowMs, accOn, charging);
    }

    private static AtomicLong atomicLongField(Object target, String name) throws Exception {
        return (AtomicLong) field(target, name);
    }

    private static void awaitBlocked(Thread thread) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (thread.isAlive()
                && thread.getState() != Thread.State.BLOCKED
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(2L);
        }
        assertEquals("thread did not block: " + thread.getName(),
                Thread.State.BLOCKED, thread.getState());
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test release");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure == null) return;
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError(failure);
    }

    private static void throwCause(InvocationTargetException failure) throws Exception {
        rethrow(failure.getCause());
    }

    private static void resetSharedDetectorForCollectorTest() throws Exception {
        ChargingDetector detector = ChargingDetector.getInstance();
        Object detectorLock = field(detector, "lock");
        synchronized (detectorLock) {
            setField(detector, "terminalSessionBarrier", false);
            setField(detector, "terminalBarrierSinceMs", 0L);
            setField(detector, "terminalBarrierSinceElapsedMs", 0L);
            setField(detector, "scheduledRestartLevelArmed", false);
            setField(detector, "disconnectedLatched", false);
            setField(detector, "v2lActive", false);
            setField(detector, "pendingTerminalBmsState", BydVehicleData.UNAVAILABLE);
            setField(detector, "pendingTerminalEpoch", 0L);
            setField(detector, "chargingGunState", 2);
            setField(detector, "bmsState", 1);
            setField(detector, "bmsStateAtMs", System.currentTimeMillis());
            setField(detector, "bmsStateAtElapsedMs",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            long epoch = declaredField(detector, "sessionEpoch").getLong(detector);
            setField(detector, "activeSessionEpoch", epoch);
            setField(detector, "fusedCharging", true);
        }
    }

    private static void armDetectorDebouncedSession() throws Exception {
        ChargingDetector detector = ChargingDetector.getInstance();
        Object detectorLock = field(detector, "lock");
        synchronized (detectorLock) {
            long epoch = Math.max(
                    2L, declaredField(detector, "sessionEpoch").getLong(detector));
            setField(detector, "sessionEpoch", epoch);
            setField(detector, "activeSessionEpoch", epoch);
            setField(detector, "bmsState", 1);
            setField(detector, "bmsStateAtMs", System.currentTimeMillis());
            setField(detector, "bmsStateAtElapsedMs",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            setField(detector, "pendingTerminalBmsState", BydVehicleData.UNAVAILABLE);
            setField(detector, "pendingTerminalEpoch", 0L);
            setField(detector, "terminalSessionBarrier", false);
            setField(detector, "disconnectedLatched", false);
            setField(detector, "v2lActive", false);
            setField(detector, "chargingGunState", 2);
            setField(detector, "fusedCharging", true);
        }
    }

    private static void waitForWallClockAfter(long boundaryMs) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() <= boundaryMs
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue("wall clock did not advance after FINISHED",
                System.currentTimeMillis() > boundaryMs);
    }

    public static final class InterleavingChargingDevice {
        final int gunState;
        final int chargingType;
        final int bmsState;
        volatile boolean blockTypeGetter;
        volatile boolean blockPowerGetter;
        volatile boolean blockCapacityGetter;
        volatile double chargingCapacity = Double.NaN;
        final CountDownLatch typeGetterEntered = new CountDownLatch(1);
        final CountDownLatch releaseTypeGetter = new CountDownLatch(1);
        final CountDownLatch powerGetterEntered = new CountDownLatch(1);
        final CountDownLatch releasePowerGetter = new CountDownLatch(1);
        final CountDownLatch capacityGetterEntered = new CountDownLatch(1);
        final CountDownLatch releaseCapacityGetter = new CountDownLatch(1);
        final AtomicLong capacityGetterCalls = new AtomicLong();

        InterleavingChargingDevice(int gunState, int chargingType, int bmsState) {
            this.gunState = gunState;
            this.chargingType = chargingType;
            this.bmsState = bmsState;
        }

        public int getChargingGunState() {
            return gunState;
        }

        public int getChargerWorkState() {
            return 0;
        }

        public int getChargingType() {
            if (blockTypeGetter) {
                typeGetterEntered.countDown();
                awaitUnchecked(releaseTypeGetter);
            }
            return chargingType;
        }

        public int getBatteryManagementDeviceState() {
            return bmsState;
        }

        public double getChargingPower() {
            if (blockPowerGetter) {
                powerGetterEntered.countDown();
                awaitUnchecked(releasePowerGetter);
            }
            return 0.0;
        }

        public int getChargingMode() {
            return 0;
        }

        public int getChargingState() {
            return bmsState;
        }

        public double getChargingCapacity() {
            capacityGetterCalls.incrementAndGet();
            if (blockCapacityGetter) {
                capacityGetterEntered.countDown();
                awaitUnchecked(releaseCapacityGetter);
            }
            return chargingCapacity;
        }

        public int getChargingPercent() {
            return 50;
        }
    }

    public static final class DualPowerAliasChargingDevice {
        private final double chargePower;
        private final double chargingPower;
        final AtomicLong chargePowerCalls = new AtomicLong();
        final AtomicLong chargingPowerCalls = new AtomicLong();

        DualPowerAliasChargingDevice(double chargePower, double chargingPower) {
            this.chargePower = chargePower;
            this.chargingPower = chargingPower;
        }

        public double getChargePower() {
            chargePowerCalls.incrementAndGet();
            return chargePower;
        }

        public double getChargingPower() {
            chargingPowerCalls.incrementAndGet();
            return chargingPower;
        }
    }

    public static final class LegacyPowerAliasChargingDevice {
        private final double chargingPower;

        LegacyPowerAliasChargingDevice(double chargingPower) {
            this.chargingPower = chargingPower;
        }

        public double getChargingPower() {
            return chargingPower;
        }
    }

    public static final class ChargePowerOnlyChargingDevice {
        private final double chargePower;

        ChargePowerOnlyChargingDevice(double chargePower) {
            this.chargePower = chargePower;
        }

        public int getChargingGunState() {
            return 2;
        }

        public int getChargingType() {
            return 0;
        }

        public int getBatteryManagementDeviceState() {
            return 1;
        }

        public double getChargePower() {
            return chargePower;
        }
    }

    public static final class CallbackOnlyChargingDevice {
        public int getChargingGunState() {
            return 2;
        }

        public int getChargingType() {
            return 0;
        }

        public int getBatteryManagementDeviceState() {
            return 1;
        }
    }

    public static final class FrozenEngineDevice {
        private final double enginePower;

        FrozenEngineDevice(double enginePower) {
            this.enginePower = enginePower;
        }

        public int getEngineSpeed() {
            return 0;
        }

        public double getEnginePower() {
            return enginePower;
        }
    }

    public static final class UnavailableExternalCounterDevice {
        public double getExternalChargingPower() {
            return Double.NaN;
        }
    }

    public static final class BlockingSentinelStatisticDevice {
        final CountDownLatch fuelPctGetterEntered = new CountDownLatch(1);
        final CountDownLatch releaseFuelPctGetter = new CountDownLatch(1);
        final AtomicLong fuelPctCalls = new AtomicLong();
        final AtomicLong fuelRangeCalls = new AtomicLong();

        public int getFuelPercentageValue() {
            fuelPctCalls.incrementAndGet();
            fuelPctGetterEntered.countDown();
            awaitUnchecked(releaseFuelPctGetter);
            return 255;
        }

        public int getFuelDrivingRangeValue() {
            fuelRangeCalls.incrementAndGet();
            return 2046;
        }
    }

    public static final class FuelStatisticDevice {
        private final int fuelPercent;
        private final int fuelRange;

        FuelStatisticDevice(int fuelPercent, int fuelRange) {
            this.fuelPercent = fuelPercent;
            this.fuelRange = fuelRange;
        }

        public int getFuelPercentageValue() {
            return fuelPercent;
        }

        public int getFuelDrivingRangeValue() {
            return fuelRange;
        }
    }

    private static final class SourceKindOverride implements AutoCloseable {
        private final String source;
        private final Field loadedField;
        private final boolean previousLoaded;
        private final Map<String, Object> observations;
        private final Object previousObservation;
        private final Map<String, Double> latchedDivisors;
        private final Double previousLatchedDivisor;
        private final Map<String, Double> latchedRateDivisors;
        private final Double previousLatchedRateDivisor;
        private final Map<String, Object> sessionRateProofs;
        private final Object previousSessionRateProof;

        @SuppressWarnings("unchecked")
        SourceKindOverride(String source, ChargeSourceClassifier.Kind kind)
                throws Exception {
            this.source = source;
            loadedField = ChargeSourceClassifier.class.getDeclaredField("loaded");
            loadedField.setAccessible(true);
            previousLoaded = loadedField.getBoolean(null);
            loadedField.setBoolean(null, true);

            Field observationsField =
                    ChargeSourceClassifier.class.getDeclaredField("observations");
            observationsField.setAccessible(true);
            observations = (Map<String, Object>) observationsField.get(null);
            previousObservation = observations.get(source);
            Class<?> observationClass =
                    Class.forName("com.overdrive.app.byd.ChargeSourceClassifier$Observation");
            Constructor<?> constructor = observationClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object controlled = constructor.newInstance();
            Field kindField = observationClass.getDeclaredField("kind");
            kindField.setAccessible(true);
            kindField.set(controlled, kind);
            observations.put(source, controlled);

            latchedDivisors = (Map<String, Double>) staticField(
                    ChargeRateResolver.class, "latchedDivisors");
            previousLatchedDivisor = latchedDivisors.remove(source);
            latchedRateDivisors = (Map<String, Double>) staticField(
                    ChargeRateResolver.class, "latchedRateDivisors");
            previousLatchedRateDivisor = latchedRateDivisors.remove(source);
            sessionRateProofs = (Map<String, Object>) staticField(
                    ChargeRateResolver.class, "sessionRateProofs");
            previousSessionRateProof = sessionRateProofs.remove(source);
        }

        @Override
        public void close() throws Exception {
            if (previousObservation != null) {
                observations.put(source, previousObservation);
            } else {
                observations.remove(source);
            }
            loadedField.setBoolean(null, previousLoaded);
            restoreMapValue(latchedDivisors, source, previousLatchedDivisor);
            restoreMapValue(latchedRateDivisors, source, previousLatchedRateDivisor);
            restoreMapValue(sessionRateProofs, source, previousSessionRateProof);
        }
    }

    private static final class SharedDatabaseOverride implements AutoCloseable {
        private final SocHistoryDatabase previous;

        SharedDatabaseOverride(SocHistoryDatabase replacement) throws Exception {
            previous = (SocHistoryDatabase) staticField(
                    SocHistoryDatabase.class, "instance");
            setStaticField(SocHistoryDatabase.class, "instance", replacement);
        }

        @Override
        public void close() throws Exception {
            setStaticField(SocHistoryDatabase.class, "instance", previous);
        }
    }

    private static <T> void restoreMapValue(Map<String, T> map, String key, T value) {
        map.remove(key);
        if (value != null) map.put(key, value);
    }

    private static SocHistoryDatabase newOpenCounterDatabase(String name, long start)
            throws Exception {
        Constructor<SocHistoryDatabase> constructor =
                SocHistoryDatabase.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        SocHistoryDatabase database = constructor.newInstance();
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE charging_sessions ("
                    + "start_time BIGINT PRIMARY KEY, end_time BIGINT,"
                    + "counter_start_kwh DOUBLE, counter_last_kwh DOUBLE,"
                    + "counter_energy_kwh DOUBLE, energy_incomplete INTEGER,"
                    + "counter_source VARCHAR(32), counter_full_scale_kwh DOUBLE)");
            statement.execute("INSERT INTO charging_sessions (start_time) VALUES (" + start + ")");
        }
        setField(database, "connection", connection);
        setField(database, "isInitialized", true);
        setField(database, "chargingAnalyticsEnabled", true);
        setField(database, "wasCharging", true);
        setField(database, "chargingStartTime", start);
        return database;
    }

    private static void closeDatabaseConnection(SocHistoryDatabase database) throws Exception {
        if (database == null) return;
        Connection connection = (Connection) field(database, "connection");
        if (connection != null) connection.close();
    }

    private static double doubleField(Object target, String name) throws Exception {
        return declaredField(target, name).getDouble(target);
    }

    private static Object field(Object target, String name) throws Exception {
        return declaredField(target, name).get(target);
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static String readCollectorSource() throws Exception {
        Path fromModule = Paths.get(
                "src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        Path path = Files.exists(fromModule)
                ? fromModule
                : Paths.get(
                        "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int matchingBrace(String source, int openBrace) {
        if (openBrace < 0) return -1;
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        declaredField(target, name).set(target, value);
    }

    private static void setStaticField(Class<?> type, String name, Object value)
            throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static Field declaredField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
