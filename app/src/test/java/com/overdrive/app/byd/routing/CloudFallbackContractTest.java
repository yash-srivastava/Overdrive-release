package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.cloud.CloudCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the additive SDK-first/cloud-fallback routing contract. */
public class CloudFallbackContractTest {

    @Test
    public void localCapableBodyAndClimateCommandsPreferSdk() {
        assertEquals(VehicleCommandRouter.RoutePreference.SDK_FIRST,
                new VehicleCommandRouter.ClimateOnCommand(22).defaultPreference());
        assertEquals(VehicleCommandRouter.RoutePreference.SDK_FIRST,
                new VehicleCommandRouter.ClimateOffCommand().defaultPreference());
        assertEquals(VehicleCommandRouter.RoutePreference.SDK_ONLY,
                new VehicleCommandRouter.OpenAllWindowsCommand().defaultPreference());
        assertEquals(VehicleCommandRouter.RoutePreference.SDK_FIRST,
                new VehicleCommandRouter.CloseAllWindowsCommand().defaultPreference());
        assertEquals(VehicleCommandRouter.RoutePreference.SDK_FIRST,
                new VehicleCommandRouter.TrunkCloseCommand().defaultPreference());
    }

    @Test
    public void climateFallbackNeverClampsAValidLocalSetpoint() {
        assertTrue(new VehicleCommandRouter.ClimateOnCommand(31).hasCloudPath());
        assertTrue(new VehicleCommandRouter.ClimateOnCommand(15, 25).hasCloudPath());
        assertTrue(new VehicleCommandRouter.ClimateOnCommand(15, 25).hasSdkPath());
        assertFalse(new VehicleCommandRouter.ClimateOnCommand(22, 12).hasCloudPath());
        assertTrue(!new VehicleCommandRouter.ClimateOnCommand(31.5).hasCloudPath());
        assertTrue(!new VehicleCommandRouter.ClimateOnCommand(32).hasCloudPath());
        assertTrue(!new VehicleCommandRouter.ClimateOnCommand(33).hasCloudPath());
    }

    @Test
    public void remoteWindowVentIsDistinctFromFullLocalOpen() {
        VehicleCommandRouter.OpenAllWindowsCommand fullOpen =
                new VehicleCommandRouter.OpenAllWindowsCommand();
        VehicleCommandRouter.VentAllWindowsCommand vent =
                new VehicleCommandRouter.VentAllWindowsCommand();

        assertFalse(fullOpen.hasCloudPath());
        assertTrue(fullOpen.hasSdkPath());
        assertTrue(vent.hasCloudPath());
        assertTrue(vent.hasSdkPath());
        assertEquals(VehicleCommandRouter.Capability.AVAILABLE, vent.cloudCapability());
        assertEquals(VehicleCommandRouter.Capability.AVAILABLE, vent.sdkCapability());
        assertEquals(VehicleCommandRouter.RoutePreference.CLOUD_FIRST,
                vent.defaultPreference());
        assertTrue(vent.preferLocalWhenOccupiedOrAwake());
        assertEquals(15, VehicleCommandRouter.VentAllWindowsCommand.LOCAL_VENT_PERCENT);
        assertEquals(CloudCapabilities.Feature.WINDOWS_OPEN_VENT, vent.cloudFeature());
        assertTrue(vent.requiresKnownCloudFeature());
    }

    @Test
    public void newCloudOnlyImmediateChargeRequiresKnownCapability() {
        VehicleCommandRouter.StartChargingNowCommand command =
                new VehicleCommandRouter.StartChargingNowCommand();

        assertTrue(command.requiresKnownCloudFeature());
        assertEquals(CloudCapabilities.Feature.SMART_CHARGING, command.cloudFeature());
        assertEquals(VehicleCommandRouter.RoutePreference.CLOUD_ONLY,
                command.defaultPreference());
    }

    @Test
    public void scheduledClimateUsesTheSameCapabilityGatedCloudPathAsRemoteClimate() {
        VehicleCommandRouter.ClimateScheduleCommand command =
                new VehicleCommandRouter.ClimateScheduleCommand(
                        VehicleCommandRouter.ClimateScheduleCommand.CREATE, null,
                        Long.valueOf(1_800_000_000L), Double.valueOf(22D), Integer.valueOf(20));

        assertEquals(VehicleCommandRouter.RoutePreference.CLOUD_ONLY, command.defaultPreference());
        assertEquals(CloudCapabilities.Feature.CLIMATE, command.cloudFeature());
        assertTrue(command.requiresKnownCloudFeature());
        assertTrue(command.hasCloudPath());
        assertFalse(command.hasSdkPath());
        assertTrue(command.allowCloudFallbackFromMqtt());
    }

    @Test
    public void cloudOnlyRemotePhysicalControlsRequireKnownCapabilities() {
        assertTrue(new VehicleCommandRouter.LockCommand().requiresKnownCloudFeature());
        assertTrue(new VehicleCommandRouter.UnlockCommand().requiresKnownCloudFeature());
        assertTrue(new VehicleCommandRouter.FindCarCommand().requiresKnownCloudFeature());
        assertTrue(new VehicleCommandRouter.FlashLightsCommand().requiresKnownCloudFeature());
        assertTrue(new VehicleCommandRouter.BatteryHeatCommand(true).requiresKnownCloudFeature());
    }

    @Test
    public void remoteFindAndFlashRequireTerminalCloudConfirmation() {
        assertEquals(CloudCapabilities.Feature.FIND_CAR,
                new VehicleCommandRouter.FindCarCommand().cloudFeature());
        assertEquals(CloudCapabilities.Feature.FLASH_LIGHTS,
                new VehicleCommandRouter.FlashLightsCommand().cloudFeature());
        assertTrue(new VehicleCommandRouter.FindCarCommand().requiresKnownCloudFeature());
        assertTrue(new VehicleCommandRouter.FlashLightsCommand().requiresKnownCloudFeature());
    }

    @Test
    public void cloudCommandsUseTheFullConfirmationTimeoutBudget() {
        assertEquals(30_000L, new VehicleCommandRouter.FindCarCommand().cloudTimeoutMs());
        assertEquals(30_000L, new VehicleCommandRouter.FlashLightsCommand().cloudTimeoutMs());
        assertEquals(30_000L, new VehicleCommandRouter.ChargeScheduleCommand(
                "22:00", "06:00", "e", true).cloudTimeoutMs());
    }

    @Test
    public void seatCloudFallbackRetainsIntentWhileRouterValidatesTheCompositeSnapshot()
            throws Exception {
        VehicleCommandRouter.SeatHeatCommand localOnly =
                new VehicleCommandRouter.SeatHeatCommand(1, 1, 1, 0, 0, 0);
        VehicleCommandRouter.SeatHeatCommand safeFallback =
                new VehicleCommandRouter.SeatHeatCommand(1, 1, 1, 0, 0, 0, true);
        VehicleCommandRouter.SeatHeatCommand cloudSeedOnly =
                new VehicleCommandRouter.SeatHeatCommand(1, 1, 0, 0, 0, 0, true, 0L);

        assertTrue(!localOnly.hasCloudPath());
        assertTrue(safeFallback.hasCloudPath());
        assertTrue(cloudSeedOnly.hasCloudPath());

        String source = readRouterSource();
        assertTrue(source.contains("VehicleCloudSnapshot cloud = freshCloudSeatSnapshot();"));
        assertTrue(source.contains("if (!isCompleteSeatState(state)) return null;"));
    }

    @Test
    public void seatCloudFallbackUsesThePyBydGateForItsTargetSeat() {
        VehicleCommandRouter.SeatHeatCommand driver =
                new VehicleCommandRouter.SeatHeatCommand(1, 1, 1, 0, 0, 0, true);
        VehicleCommandRouter.SeatVentCommand passenger =
                new VehicleCommandRouter.SeatVentCommand(2, 1, 0, 0, 1, 0, true);

        assertEquals(CloudCapabilities.Feature.SEAT_DRIVER, driver.cloudFeature());
        assertEquals(CloudCapabilities.Feature.SEAT_PASSENGER, passenger.cloudFeature());
        assertTrue(driver.requiresKnownCloudFeature());
        assertTrue(passenger.requiresKnownCloudFeature());
    }

    @Test
    public void steeringWheelHeatIsSdkFirstAndUsesTheDedicatedCloudGate() throws Exception {
        VehicleCommandRouter.SteeringWheelHeatCommand command =
                new VehicleCommandRouter.SteeringWheelHeatCommand(true);

        assertEquals(VehicleCommandRouter.RoutePreference.SDK_FIRST, command.defaultPreference());
        assertTrue(command.hasSdkPath());
        assertTrue(command.hasCloudPath());
        assertEquals(CloudCapabilities.Feature.SEAT_STEERING_WHEEL, command.cloudFeature());
        assertTrue(command.requiresKnownCloudFeature());
        assertTrue(command.allowCloudFallbackFromMqtt());
        assertTrue(command.executeViaCloud(null, null).unsupported);

        String source = readRouterSource();
        assertTrue(source.contains("prepareSeatCloudSteeringWheelWireState"));
        assertTrue(source.contains("getSteeringWheelHeatingState()"));
        assertTrue(source.contains("seatSteeringWheelWireState < 0"));
        assertTrue(source.contains("cloudChairType() { return \"5\"; }"));
    }

    @Test
    public void localWheelReadbackPreservesSupportedWheelWhenCloudOmitsIt()
            throws Exception {
        JSONObject frontSeatsOnly = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "10300003")));
        CloudCapabilities noWheel = CloudCapabilities.fromResponses(
                "VIN", frontSeatsOnly, null, 1L);
        assertEquals(3, VehicleCommandRouter.resolveSeatCloudSteeringWheelWireState(
                -1, -1, 0, noWheel));

        JSONObject wheelConfig = new JSONObject().put("cfFixedList", new JSONArray()
                .put(new JSONObject().put("functionNo", "10300004")));
        CloudCapabilities wheelSupported = CloudCapabilities.fromResponses(
                "VIN", wheelConfig, null, 1L);
        assertEquals(-1, VehicleCommandRouter.resolveSeatCloudSteeringWheelWireState(
                -1, -1, 0, wheelSupported));
        assertEquals(1, VehicleCommandRouter.resolveSeatCloudSteeringWheelWireState(
                -1, -1, 2, wheelSupported));
        assertEquals(3, VehicleCommandRouter.resolveSeatCloudSteeringWheelWireState(
                -1, -1, 1, wheelSupported));
        assertEquals(1, VehicleCommandRouter.resolveSeatCloudSteeringWheelWireState(
                1, -1, 1, wheelSupported));
    }

    @Test
    public void seatCloudFallbackSerializesFreshStateAndCommitsOnlyAfterConfirmation()
            throws Exception {
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        java.lang.reflect.Field cache = VehicleCommandRouter.class
                .getDeclaredField("seatCompositeState");
        java.lang.reflect.Field cacheAt = VehicleCommandRouter.class
                .getDeclaredField("seatCompositeStateAtMs");
        cache.setAccessible(true);
        cacheAt.setAccessible(true);
        int[] previousCache = (int[]) cache.get(router);
        long previousCacheAt = cacheAt.getLong(router);

        Class<?> seatCommand = Class.forName(
                "com.overdrive.app.byd.routing.VehicleCommandRouter$SeatClimateCommand");
        java.lang.reflect.Method prepare = VehicleCommandRouter.class.getDeclaredMethod(
                "prepareSeatCloudState", seatCommand);
        java.lang.reflect.Method commit = VehicleCommandRouter.class.getDeclaredMethod(
                "commitSeatCompositeState", int[].class);
        java.lang.reflect.Method invalidateAfterLocal = VehicleCommandRouter.class.getDeclaredMethod(
                "updateSeatCloudCacheAfterLocalSuccess",
                VehicleCommandRouter.VehicleCommand.class, VehicleCommandRouter.CommandResult.class);
        prepare.setAccessible(true);
        commit.setAccessible(true);
        invalidateAfterLocal.setAccessible(true);

        try {
            cache.set(router, null);
            cacheAt.setLong(router, 0L);
            long now = System.currentTimeMillis();
            VehicleCommandRouter.SeatHeatCommand driverHigh =
                    new VehicleCommandRouter.SeatHeatCommand(1, 2, 0, 0, 0, 0, true, now);
            VehicleCommandRouter.SeatVentCommand passengerHigh =
                    new VehicleCommandRouter.SeatVentCommand(2, 2, 0, 0, 0, 0, true, now);

            int[] driverPayload = (int[]) prepare.invoke(router, driverHigh);
            assertArrayEquals(new int[] { 2, 0, 0, 0 }, driverPayload);

            // A failed/timed-out first request must not change the next command's base state.
            int[] beforeConfirmation = (int[]) prepare.invoke(router, passengerHigh);
            assertArrayEquals(new int[] { 0, 0, 0, 2 }, beforeConfirmation);

            // Once terminal cloud confirmation arrives, the later command retains that update.
            commit.invoke(router, new Object[] { driverPayload });
            int[] afterConfirmation = (int[]) prepare.invoke(router, passengerHigh);
            assertArrayEquals(new int[] { 2, 0, 0, 2 }, afterConfirmation);

            // A later collector snapshot supersedes older cloud cache evidence.
            cacheAt.setLong(router, now - 1L);
            VehicleCommandRouter.SeatVentCommand fromNewerSnapshot =
                    new VehicleCommandRouter.SeatVentCommand(
                            2, 2, 1, 1, 1, 1, true, now);
            int[] fromNewerSeed = (int[]) prepare.invoke(router, fromNewerSnapshot);
            assertArrayEquals(new int[] { 1, 1, 1, 2 }, fromNewerSeed);

            // A local write that was built from an older snapshot must retain sibling
            // values confirmed by the cloud while that local write was in flight.
            cache.set(router, new int[] { 2, 0, 0, 0 });
            cacheAt.setLong(router, now);
            VehicleCommandRouter.SeatVentCommand stalePassengerSeed =
                    new VehicleCommandRouter.SeatVentCommand(
                            2, 2, 0, 0, 0, 0, true, now - 1L);
            invalidateAfterLocal.invoke(router, stalePassengerSeed,
                    VehicleCommandRouter.CommandResult.success(
                            VehicleCommandRouter.Path.SDK, "local", 0L));
            assertArrayEquals(new int[] { 2, 0, 0, 2 }, (int[]) cache.get(router));

            // An SDK command with an older seed must likewise preserve the newer
            // confirmed sibling state while applying its own target.
            invalidateAfterLocal.invoke(router, driverHigh,
                    VehicleCommandRouter.CommandResult.success(
                            VehicleCommandRouter.Path.SDK, "local", 0L));
            assertArrayEquals(new int[] { 2, 0, 0, 2 }, (int[]) cache.get(router));
            assertTrue(cacheAt.getLong(router) >= now);
        } finally {
            cache.set(router, previousCache);
            cacheAt.setLong(router, previousCacheAt);
        }
    }

    @Test
    public void legacyRawSeatLevelThreeNormalizesToHighBeforeCloudFallback() {
        VehicleCommandRouter.SeatHeatCommand command =
                new VehicleCommandRouter.SeatHeatCommand(1, 3, 3, 0, 0, 0, true);

        assertEquals(2, command.level);
        assertEquals(2, command.driverHeat);
        assertTrue(command.hasCloudPath());
    }

    @Test
    public void routerTracksOnlyConfirmedCloudClimateSessionsAndExpiresThem() throws Exception {
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        java.lang.reflect.Field until = VehicleCommandRouter.class
                .getDeclaredField("remoteClimateActiveUntilMs");
        until.setAccessible(true);
        java.lang.reflect.Method update = VehicleCommandRouter.class.getDeclaredMethod(
                "updateRemoteClimateSession", VehicleCommandRouter.VehicleCommand.class,
                VehicleCommandRouter.CommandResult.class);
        update.setAccessible(true);
        try {
            update.invoke(router, new VehicleCommandRouter.ClimateOnCommand(22),
                    VehicleCommandRouter.CommandResult.success(
                            VehicleCommandRouter.Path.SDK, "local", 0L));
            assertFalse(router.isRemoteClimateActive());

            update.invoke(router, new VehicleCommandRouter.ClimateOnCommand(22),
                    VehicleCommandRouter.CommandResult.success(
                            VehicleCommandRouter.Path.SDK_THEN_CLOUD, "cloud", 0L));
            assertTrue(router.isRemoteClimateActive());
            long twentyMinuteUntil = until.getLong(router);

            update.invoke(router, new VehicleCommandRouter.ClimateOnCommand(22, 10),
                    VehicleCommandRouter.CommandResult.success(
                            VehicleCommandRouter.Path.CLOUD, "cloud", 0L));
            long tenMinuteUntil = until.getLong(router);
            assertTrue("a 10-minute session must not be reported as the default 20 minutes",
                    tenMinuteUntil < twentyMinuteUntil - (9L * 60L * 1_000L));

            update.invoke(router, new VehicleCommandRouter.ClimateOffCommand(),
                    VehicleCommandRouter.CommandResult.failed(
                            VehicleCommandRouter.Path.SDK, "failed", 0L, null));
            assertTrue(router.isRemoteClimateActive());

            update.invoke(router, new VehicleCommandRouter.ClimateOffCommand(),
                    VehicleCommandRouter.CommandResult.success(
                            VehicleCommandRouter.Path.SDK, "local", 0L));
            assertFalse(router.isRemoteClimateActive());

            update.invoke(router, new VehicleCommandRouter.ClimateOnCommand(22),
                    VehicleCommandRouter.CommandResult.success(
                            VehicleCommandRouter.Path.SDK_THEN_CLOUD, "cloud", 0L));
            assertTrue(router.isRemoteClimateActive());
            router.clearRemoteClimateSession();
            assertFalse(router.isRemoteClimateActive());

            until.setLong(router, System.currentTimeMillis() - 1L);
            assertFalse(router.isRemoteClimateActive());
            assertEquals(0L, until.getLong(router));
        } finally {
            until.setLong(router, 0L);
        }

        String source = readRouterSource();
        assertTrue(source.contains("return finishCommand(cmd, result);"));
    }

    @Test
    public void trunkTransactionUsesDirectCloudOpenWithoutUnlockingTheCar() throws Exception {
        String source = readRouterSource();
        int transaction = source.indexOf(
                "private CommandResult executeTrunkOpen(long stopGeneration)");
        int serialized = source.indexOf(
                "private CommandResult executeTrunkOpenSerialized(long stopGeneration)", transaction);
        int transactionEnd = source.indexOf(
                "private boolean isTailgateOpenCancelled(long stopGeneration)", serialized);
        String trunkOpen = source.substring(serialized, transactionEnd);

        assertTrue(transaction >= 0);
        assertTrue(serialized > transaction);
        assertTrue(source.substring(transaction, serialized).contains("synchronized (cloudLock)"));
        assertTrue(transactionEnd > serialized);
        assertTrue(trunkOpen.contains(
                "if (lockState == BydDataCollector.DOOR_STATE_UNLOCK)"));
        assertTrue(trunkOpen.contains("CloudCallResult cloud = runCloudCall(command);"));
        assertFalse(trunkOpen.contains("UnlockCommand"));
        assertFalse(trunkOpen.contains("waitForTrunkUnlockSettle"));
        assertTrue(trunkOpen.contains("Never issue OPENDOOR here"));
        assertTrue(source.contains("private final AtomicLong tailgateStopGeneration"));
        assertTrue(source.contains("if (cmd instanceof TrunkStopCommand)"));
        assertTrue(source.contains("isTailgateOpenCancelled(stopGeneration)"));
        assertTrue(source.contains("tailgate open cancelled by stop"));
    }

    @Test
    public void cloudMutationUncertaintySuppressesSdkFallback() {
        assertFalse(VehicleCommandRouter.cloudFailureAllowsSdkFallback(true));
        assertTrue(VehicleCommandRouter.cloudFailureAllowsSdkFallback(false));
    }

    @Test
    public void tailgateFallbackExecutesTerminalAndCapabilityDecisions() {
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicInteger admissions =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger cloudCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        for (int timeoutCode : new int[] {
                -10, 2, com.byd.datasource.feature.Status.STATUS_TIMEOUT}) {
            com.overdrive.app.byd.DiLink5VehicleReadiness.Outcome timeoutOutcome =
                    com.overdrive.app.byd.BydDataCollector.classifyDiLink5SdkResult(
                            Integer.valueOf(timeoutCode));
            com.overdrive.app.byd.LocalActuationAbort timeoutAbort =
                    com.overdrive.app.byd.BydDataCollector.diLink5SdkAbortForOutcome(
                            timeoutOutcome);
            assertEquals(com.overdrive.app.byd.DiLink5VehicleReadiness.Outcome.UNCERTAIN,
                    timeoutOutcome);
            assertTrue(timeoutAbort != null);

            VehicleCommandRouter.CommandResult uncertain = router.completeTrunkOpenAttempt(
                    new VehicleCommandRouter.TrunkOpenCommand(), now,
                    com.overdrive.app.byd.BydDataCollector.DOOR_STATE_UNLOCK,
                    new VehicleCommandRouter.SdkLeg(false, timeoutAbort),
                    () -> false,
                    () -> { admissions.incrementAndGet(); return true; },
                    () -> {
                        cloudCalls.incrementAndGet();
                        return VehicleCommandRouter.CommandResult.success(
                                VehicleCommandRouter.Path.SDK_THEN_CLOUD, "cloud", 0L);
                    });

            assertEquals(VehicleCommandRouter.Outcome.FAILED, uncertain.outcome);
            assertEquals(VehicleCommandRouter.Path.SDK, uncertain.path);
            assertTrue(uncertain.error instanceof com.overdrive.app.byd.LocalActuationAbort);
            assertEquals(0, admissions.get());
            assertEquals(0, cloudCalls.get());
        }

        VehicleCommandRouter.CommandResult rejectedWithoutCapability =
                router.completeTrunkOpenAttempt(
                        new VehicleCommandRouter.TrunkOpenCommand(), now,
                        com.overdrive.app.byd.BydDataCollector.DOOR_STATE_UNLOCK,
                        new VehicleCommandRouter.SdkLeg(false, null),
                        () -> false,
                        () -> { admissions.incrementAndGet(); return false; },
                        () -> {
                            cloudCalls.incrementAndGet();
                            return VehicleCommandRouter.CommandResult.success(
                                    VehicleCommandRouter.Path.SDK_THEN_CLOUD, "cloud", 0L);
                        });
        assertEquals(VehicleCommandRouter.Outcome.FAILED,
                rejectedWithoutCapability.outcome);
        assertEquals(1, admissions.get());
        assertEquals(0, cloudCalls.get());

        VehicleCommandRouter.CommandResult rejectedWithCapability =
                router.completeTrunkOpenAttempt(
                        new VehicleCommandRouter.TrunkOpenCommand(), now,
                        com.overdrive.app.byd.BydDataCollector.DOOR_STATE_UNLOCK,
                        new VehicleCommandRouter.SdkLeg(false, null),
                        () -> false,
                        () -> { admissions.incrementAndGet(); return true; },
                        () -> {
                            cloudCalls.incrementAndGet();
                            return VehicleCommandRouter.CommandResult.success(
                                    VehicleCommandRouter.Path.SDK_THEN_CLOUD, "cloud", 0L);
                        });
        assertEquals(VehicleCommandRouter.Outcome.SUCCESS, rejectedWithCapability.outcome);
        assertEquals(VehicleCommandRouter.Path.SDK_THEN_CLOUD, rejectedWithCapability.path);
        assertEquals(2, admissions.get());
        assertEquals(1, cloudCalls.get());
    }

    @Test
    public void stopDuringAdapterAcquisitionRevokesTheOwnedSetter() throws Exception {
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        long stopGeneration = router.captureTailgateOpenStopGeneration();
        java.util.concurrent.CountDownLatch sleeping = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger setters =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<VehicleCommandRouter.SdkLeg> pending = executor.submit(
                    () -> router.invokeDiLink5TailgateOpen(
                            unrestrictedTailgateTestCommand(), stopGeneration, () -> {
                                com.overdrive.app.byd.DiLink5VehicleReadiness.Outcome outcome =
                                        awaitUnavailableTailgateAdapter(
                                                com.overdrive.app.byd.VehicleActuatorBridge
                                                        ::isRemoteKeyRequestCancelled,
                                                () -> true,
                                                setters,
                                                ignored -> {
                                                    sleeping.countDown();
                                                    try {
                                                        release.await(2,
                                                                java.util.concurrent.TimeUnit.SECONDS);
                                                    } catch (InterruptedException interrupted) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                });
                                return outcome == com.overdrive.app.byd.DiLink5VehicleReadiness
                                        .Outcome.ACCEPTED;
                            }));

            assertTrue(sleeping.await(2, java.util.concurrent.TimeUnit.SECONDS));
            router.abortPendingTailgateOpen();
            release.countDown();
            assertFalse(pending.get(2, java.util.concurrent.TimeUnit.SECONDS).success);
            assertEquals(0, setters.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void generationRevocationDuringAdapterAcquisitionDispatchesNoSetter() {
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        java.util.concurrent.atomic.AtomicBoolean generationCurrent =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger setters =
                new java.util.concurrent.atomic.AtomicInteger();

        VehicleCommandRouter.SdkLeg result = router.invokeDiLink5TailgateOpen(
                unrestrictedTailgateTestCommand(),
                router.captureTailgateOpenStopGeneration(),
                () -> awaitUnavailableTailgateAdapter(
                        com.overdrive.app.byd.VehicleActuatorBridge
                                ::isRemoteKeyRequestCancelled,
                        generationCurrent::get,
                        setters,
                        ignored -> generationCurrent.set(false))
                        == com.overdrive.app.byd.DiLink5VehicleReadiness.Outcome.ACCEPTED);

        assertFalse(result.success);
        assertEquals(0, setters.get());
    }

    private static com.overdrive.app.byd.DiLink5VehicleReadiness.Outcome
            awaitUnavailableTailgateAdapter(
                    com.overdrive.app.byd.DiLink5VehicleReadiness.Cancellation cancellation,
                    com.overdrive.app.byd.DiLink5VehicleReadiness.CurrentCheck current,
                    java.util.concurrent.atomic.AtomicInteger setters,
                    com.overdrive.app.byd.DiLink5VehicleReadiness.Sleeper sleeper) {
        return com.overdrive.app.byd.DiLink5VehicleReadiness.awaitAndDispatch(
                5, 1L, Long.MAX_VALUE, System::currentTimeMillis,
                cancellation, current, () -> null, ignored -> true,
                () -> true, () -> true,
                ignored -> {
                    setters.incrementAndGet();
                    return com.overdrive.app.byd.DiLink5VehicleReadiness.Outcome.ACCEPTED;
                },
                sleeper);
    }

    private static VehicleCommandRouter.VehicleCommand unrestrictedTailgateTestCommand() {
        return new VehicleCommandRouter.VehicleCommand() {
            @Override public String name() { return "tailgate-open-behavior"; }
        };
    }

    @Test
    public void seatCommandsSerializeBothLocalAndCloudLegs() throws Exception {
        String source = readRouterSource();

        assertTrue(source.contains("private final Object seatCommandLock = new Object()"));
        assertTrue(source.contains(
                "return executeUnserialized(cmd, tailgateOpenStopGeneration);"));
        assertTrue(source.contains(
                "return executeSdkOnlyUnserialized(cmd, tailgateOpenStopGeneration);"));
        assertTrue(source.contains("synchronized (seatCommandLock)"));
    }

    @Test
    public void cloudWorkerRechecksMotionImmediatelyBeforeDispatch() throws Exception {
        String source = readRouterSource();

        assertTrue(source.contains("if (isCloudDispatchBlocked(cmd))"));
        assertTrue(source.contains("return CloudOutcome.blockedDriving();"));
        assertTrue(source.contains("case BLOCKED_DRIVING: return CommandResult.blocked"));
        assertTrue(VehicleCommandRouter.CloudOutcome.blockedDriving().blockedDriving);
    }

    @Test
    public void trunkSdkOnlyPolicyBypassesTheCloudComposite() throws Exception {
        String source = readRouterSource();

        assertTrue(source.contains(
                "RoutePreference trunkPreference = resolveEffectivePreference(cmd);"));
        assertTrue(source.contains("if (trunkPreference == RoutePreference.SDK_ONLY)"));
        assertTrue(source.contains(
                "return finishCommand(cmd, runSdkOnlyTrunkOpen(cmd, tailgateOpenStopGeneration));"));
    }

    @Test
    public void sdkOnlyTrunkOpenIsAnAtomicNoCloudLocalTransaction() throws Exception {
        String source = readRouterSource();
        int helper = source.indexOf("private CommandResult runSdkOnlyTrunkOpen(");
        int nextMethod = source.indexOf(
                "private static boolean isSdkOnlyTrunkOpen(", helper);
        String transaction = source.substring(helper, nextMethod);

        assertTrue(helper >= 0);
        assertTrue(nextMethod > helper);
        assertTrue(transaction.contains("synchronized (cloudLock)"));
        assertTrue(transaction.contains("collector.readDoorLockState()"));
        assertTrue(transaction.contains("CommandResult safety = checkDrivingSafety(command);"));
        assertTrue(transaction.contains("collector.openTailgate()"));
        assertTrue(transaction.indexOf("collector.readDoorLockState()")
                < transaction.indexOf("CommandResult safety = checkDrivingSafety(command);"));
        assertTrue(transaction.indexOf("CommandResult safety = checkDrivingSafety(command);")
                < transaction.indexOf("collector.openTailgate()"));
        assertFalse(transaction.contains("runCloudCall("));
        assertFalse(transaction.contains("UnlockCommand"));
        assertFalse(transaction.contains("cloudHandshakeSatisfied("));
    }

    @Test
    public void sdkOnlyEntrypointsUseTheAtomicTrunkTransaction() throws Exception {
        String source = readRouterSource();
        int executeSdkOnly = source.indexOf("private CommandResult executeSdkOnlyUnserialized(");
        int finishCommand = source.indexOf("private CommandResult finishCommand(", executeSdkOnly);
        String sdkOnlyDispatch = source.substring(executeSdkOnly, finishCommand);

        assertTrue(sdkOnlyDispatch.contains("isSdkOnlyTrunkOpen(cmd)"));
        assertTrue(sdkOnlyDispatch.contains(
                "runSdkOnlyTrunkOpen(cmd, tailgateOpenStopGeneration)"));
        assertTrue(source.contains("if (cmd instanceof TrunkOpenSdkCommand)"));
    }

    @Test
    public void tailgateStopCancelsQueuedAndInFlightCloudOpens() throws Exception {
        String source = readRouterSource();
        int execute = source.indexOf("public CommandResult execute(VehicleCommand cmd)");
        int unserialized = source.indexOf("private CommandResult executeUnserialized(", execute);
        int executeSdkOnly = source.indexOf("public CommandResult executeSdkOnly(VehicleCommand cmd)");
        int sdkOnlyUnserialized = source.indexOf("private CommandResult executeSdkOnlyUnserialized(",
                executeSdkOnly);
        String normalIngress = source.substring(execute, unserialized);
        String sdkOnlyIngress = source.substring(executeSdkOnly, sdkOnlyUnserialized);

        assertTrue(normalIngress.contains(
                "long tailgateOpenStopGeneration = tailgateOpenStopGeneration(cmd)"));
        assertTrue(normalIngress.indexOf("tailgateOpenStopGeneration(cmd)")
                < normalIngress.indexOf("executeUnserialized(cmd, tailgateOpenStopGeneration)"));
        assertTrue(sdkOnlyIngress.contains(
                "long tailgateOpenStopGeneration = tailgateOpenStopGeneration(cmd)"));
        assertTrue(sdkOnlyIngress.indexOf("tailgateOpenStopGeneration(cmd)")
                < sdkOnlyIngress.indexOf(
                        "executeSdkOnlyUnserialized(cmd, tailgateOpenStopGeneration)"));
        assertTrue(source.contains(
                "private long tailgateOpenStopGeneration(VehicleCommand cmd)"));
        assertTrue(source.contains(
                "return ((TrunkOpenCommand) cmd).stopGeneration;"));
        assertTrue(source.contains("bindTailgateStopCancellation"));
        assertTrue(source.contains("private void cancelPendingTailgateOpen()"));
        assertTrue(source.contains("activeTailgateOpenFuture.getAndSet(null)"));
        assertTrue(source.contains("activeTailgateOpenWorker.getAndSet(null)"));
        assertTrue(source.contains("activeTailgateOpenClient.getAndSet(null)"));
        assertTrue(source.contains("future.cancel(true)"));
        assertTrue(source.contains("client.cancelRequestForThread(worker)"));
    }

    @Test
    public void tailgateOpenRegistersCancellableCloudWorkAndLinearizesLocalMotorCall()
            throws Exception {
        String source = readRouterSource();
        int localHelper = source.indexOf(
                "private CommandResult runSdkOnlyTrunkOpen(VehicleCommand command, long stopGeneration)");
        int localHelperEnd = source.indexOf(
                "private static boolean isSdkOnlyTrunkOpen(", localHelper);
        String localTransaction = source.substring(localHelper, localHelperEnd);

        assertTrue(localTransaction.contains("synchronized (tailgateAbortLock)"));
        assertTrue(localTransaction.indexOf("isTailgateOpenCancelled(stopGeneration)")
                < localTransaction.indexOf("collector.openTailgate()"));
        assertTrue(source.contains("private SdkLeg invokeTailgateOpen("));
        assertTrue(source.contains("activeTailgateOpenClient.set(cloudClient)"));
        assertTrue(source.contains("activeTailgateOpenFuture.set(submitted)"));
        assertTrue(source.contains("activeTailgateOpenWorker.set(worker)"));
        assertTrue(source.contains("clearActiveTailgateOpen(future, workerThread.get(), client)"));
    }

    @Test
    public void cloudFallbacksRefreshCapabilitiesAndFailClosedWhenUnknown() throws Exception {
        String source = readRouterSource();
        assertTrue(source.contains("if (capabilities == null) {"));
        assertTrue(source.contains("capabilities = cloudClient.fetchCloudCapabilities(vin);"));
        assertTrue(source.contains("skipping cloud dispatch"));
        assertTrue(source.contains("if (!capabilities.supports(feature))"));
        assertTrue(!source.contains("capabilities == null && cmd.requiresKnownCloudFeature()"));
        assertTrue(source.contains("catch (InterruptedException ie)"));
        assertTrue(source.contains("cancelCloudRequest(future, client, workerThread.get(), trackTailgateOpen)"));
    }

    @Test
    public void localSdkRejectionIsNotReportedAsUnsupported() throws Exception {
        String source = readRouterSource();
        String failed = "return CommandResult.failed(Path.SDK, msg(\"local_failed\"), "
                + "elapsed, leg.error);";

        int awakeStart = source.indexOf(
                "private CommandResult runLocalOnlyWhileAwake(VehicleCommand cmd)");
        int awakeEnd = source.indexOf("\n    /**", awakeStart + 1);
        assertTrue(source.substring(awakeStart, awakeEnd).contains(failed));

        int sdkStart = source.indexOf("private CommandResult runSdkOnly(VehicleCommand cmd)");
        int sdkEnd = source.indexOf("\n    /**", sdkStart + 1);
        assertTrue(source.substring(sdkStart, sdkEnd).contains(failed));
    }

    private static String readRouterSource() throws Exception {
        Path fromModule = Paths.get("src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java");
        if (Files.exists(fromModule)) {
            return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
        }
        return new String(Files.readAllBytes(Paths.get(
                "app/src/main/java/com/overdrive/app/byd/routing/VehicleCommandRouter.java")),
                StandardCharsets.UTF_8);
    }
}
