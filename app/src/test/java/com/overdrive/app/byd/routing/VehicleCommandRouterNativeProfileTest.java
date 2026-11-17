package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.dilink5.NativeVehicleAction;
import com.overdrive.app.byd.dilink5.NativeVehicleWriteBoundary;
import com.overdrive.app.byd.dilink5.NativeVehicleWriteService;

import java.lang.reflect.Constructor;

import org.junit.Test;

public class VehicleCommandRouterNativeProfileTest {
    @Test
    public void genericIngressesRejectNativeCommand() throws Exception {
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        VehicleCommandRouter.NativeVehicleCommand command = command(
                NativeVehicleAction.SUNROOF_STOP);

        assertEquals(VehicleCommandRouter.Outcome.NOT_SUPPORTED,
                router.execute(command).outcome);
        assertEquals(VehicleCommandRouter.Outcome.NOT_SUPPORTED,
                router.executeSdkOnly(command).outcome);
        assertEquals(VehicleCommandRouter.Outcome.NOT_SUPPORTED,
                router.executeCloudOnly(command).outcome);
        assertFalse(command.executeViaSdk(null));
    }

    @Test
    public void nativeCommandCannotBeConstructedByNormalIngressCode() throws Exception {
        Constructor<?> constructor = VehicleCommandRouter.NativeVehicleCommand.class
                .getDeclaredConstructor(NativeVehicleAction.class);
        assertFalse(constructor.isAccessible());
    }

    @Test
    public void nativeTailgateRetainsMovementGuardButSunshadeDoesNot() throws Exception {
        VehicleCommandRouter.NativeVehicleCommand open = command(
                NativeVehicleAction.TAILGATE_OPEN);
        VehicleCommandRouter.NativeVehicleCommand close = command(
                NativeVehicleAction.TAILGATE_CLOSE);
        VehicleCommandRouter.NativeVehicleCommand shade = command(
                NativeVehicleAction.SUNSHADE_OPEN);

        assertEquals(VehicleCommandRouter.VehicleCommand.MotionSafety.BLOCK_WHILE_MOVING,
                open.motionSafety());
        assertEquals(DrivingSafetyGuard.GUARD_TRUNK, open.motionSafetyGuardKey());
        assertEquals(VehicleCommandRouter.VehicleCommand.MotionSafety.BLOCK_WHILE_MOVING,
                close.motionSafety());
        assertEquals(VehicleCommandRouter.VehicleCommand.MotionSafety.UNRESTRICTED,
                shade.motionSafety());
    }

    @Test
    public void nativeTailgateUsesRawRailOnlyAfterFreshExplicitUnlock() {
        assertTrue(VehicleCommandRouter.shouldUseNativeTailgateOpen(
                BydDataCollector.DOOR_STATE_UNLOCK));
        assertFalse(VehicleCommandRouter.shouldUseNativeTailgateOpen(
                BydDataCollector.DOOR_STATE_LOCK));
        assertFalse(VehicleCommandRouter.shouldUseNativeTailgateOpen(
                BydDataCollector.DOOR_STATE_INVALID));
    }

    @Test
    public void lockedOrUnknownNativeTailgateTakesCapabilityGatedCloudPath() {
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        for (int lockState : new int[] {
                BydDataCollector.DOOR_STATE_LOCK, BydDataCollector.DOOR_STATE_INVALID}) {
            java.util.concurrent.atomic.AtomicInteger cloudCalls =
                    new java.util.concurrent.atomic.AtomicInteger();
            VehicleCommandRouter.CommandResult result = router.completeTrunkOpenAttempt(
                    new VehicleCommandRouter.TrunkOpenCommand(),
                    System.currentTimeMillis(),
                    lockState,
                    new VehicleCommandRouter.SdkLeg(false, null),
                    () -> false,
                    () -> true,
                    () -> {
                        cloudCalls.incrementAndGet();
                        return VehicleCommandRouter.CommandResult.success(
                                VehicleCommandRouter.Path.CLOUD, "cloud", 0L);
                    });
            assertEquals(VehicleCommandRouter.Outcome.SUCCESS, result.outcome);
            assertEquals(VehicleCommandRouter.Path.CLOUD, result.path);
            assertEquals(1, cloudCalls.get());
        }
    }

    @Test
    public void sharedDaemonStopAuthorityBlocksSettingsCloudFallback() {
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        java.util.concurrent.atomic.AtomicLong observed =
                new java.util.concurrent.atomic.AtomicLong(12L);
        VehicleCommandRouter.TrunkOpenCommand carried =
                new VehicleCommandRouter.TrunkOpenCommand(
                        7L, 12L,
                        () -> VehicleCommandRouter.nativeTailgateAuthorityCurrent(
                                12L, observed.get()));
        assertTrue(VehicleCommandRouter.tailgateAuthorityCurrent(carried, 7L));
        java.util.concurrent.atomic.AtomicInteger cloudCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        observed.incrementAndGet();
        assertFalse(VehicleCommandRouter.tailgateAuthorityCurrent(carried, 7L));

        VehicleCommandRouter.CommandResult result = router.completeTrunkOpenAttempt(
                carried,
                System.currentTimeMillis(),
                BydDataCollector.DOOR_STATE_LOCK,
                new VehicleCommandRouter.SdkLeg(false, null),
                () -> !VehicleCommandRouter.nativeTailgateAuthorityCurrent(
                        12L, observed.get()),
                () -> true,
                () -> {
                    cloudCalls.incrementAndGet();
                    return VehicleCommandRouter.CommandResult.success(
                            VehicleCommandRouter.Path.CLOUD, "cloud", 0L);
                });

        assertEquals(VehicleCommandRouter.Outcome.FAILED, result.outcome);
        assertEquals(0, cloudCalls.get());
    }

    @Test
    public void sunroofOpeningRequiresFreshKnownSpeedAtOrBelowLimit() {
        assertFalse(NativeVehicleWriteService.isKnownSunroofOpeningSpeedSafe(Double.NaN));
        assertFalse(NativeVehicleWriteService.isKnownSunroofOpeningSpeedSafe(
                Double.POSITIVE_INFINITY));
        assertFalse(NativeVehicleWriteService.isKnownSunroofOpeningSpeedSafe(-0.1));
        assertTrue(NativeVehicleWriteService.isKnownSunroofOpeningSpeedSafe(0.0));
        assertTrue(NativeVehicleWriteService.isKnownSunroofOpeningSpeedSafe(80.0));
        assertFalse(NativeVehicleWriteService.isKnownSunroofOpeningSpeedSafe(80.1));
    }

    @Test
    public void terminalOutcomesDoNotCollapseAcceptedRejectedAndUnknown() {
        assertEquals(VehicleCommandRouter.Outcome.ACCEPTED_UNVERIFIED,
                VehicleCommandRouter.nativeTerminalOutcome(
                        NativeVehicleWriteBoundary.Kind.ACCEPTED));
        assertEquals(VehicleCommandRouter.Outcome.REJECTED,
                VehicleCommandRouter.nativeTerminalOutcome(
                        NativeVehicleWriteBoundary.Kind.REJECTED));
        assertEquals(VehicleCommandRouter.Outcome.UNKNOWN,
                VehicleCommandRouter.nativeTerminalOutcome(
                        NativeVehicleWriteBoundary.Kind.TRANSACT_FALSE));
        assertEquals(VehicleCommandRouter.Outcome.UNKNOWN,
                VehicleCommandRouter.nativeTerminalOutcome(
                        NativeVehicleWriteBoundary.Kind.DEAD_SERVICE));
    }

    @Test
    public void manualNativeTailgateOpenOwnsCancellationBeforeBinderWait()
            throws Exception {
        com.overdrive.app.byd.LocalActuationCancellation cancellation =
                new com.overdrive.app.byd.LocalActuationCancellation();
        java.util.concurrent.atomic.AtomicBoolean current =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.CountDownLatch captureEntered =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseCapture =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Long> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread request = new Thread(() -> captured.set(
                VehicleCommandRouter.captureNativeTailgateAuthority(
                        cancellation,
                        current::get,
                        () -> {
                            captureEntered.countDown();
                            try {
                                releaseCapture.await(2, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return 2L;
                        })));

        request.start();
        assertTrue(captureEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));
        current.set(false);
        cancellation.cancel();
        releaseCapture.countDown();
        request.join(2_000L);

        assertFalse(request.isAlive());
        assertEquals(null, captured.get());
        assertTrue(VehicleCommandRouter.nativeTailgateAuthorityCurrent(2L, 2L));
        assertFalse(VehicleCommandRouter.nativeTailgateAuthorityCurrent(2L, 3L));
        assertFalse(VehicleCommandRouter.nativeTailgateAuthorityCurrent(2L, null));
    }

    @Test
    public void appStopAfterWarmCaptureCannotRebindManualCloudFallback() {
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        java.util.concurrent.atomic.AtomicLong localGeneration =
                new java.util.concurrent.atomic.AtomicLong(7L);
        long originalGeneration = localGeneration.get();
        com.overdrive.app.byd.LocalActuationCancellation cancellation =
                new com.overdrive.app.byd.LocalActuationCancellation();
        Long captured = VehicleCommandRouter.captureNativeTailgateAuthority(
                cancellation, () -> localGeneration.get() == originalGeneration, () -> 12L);
        assertEquals(Long.valueOf(12L), captured); // Warm daemon, before the lock read.

        localGeneration.incrementAndGet();
        cancellation.cancel(); // App-process STOP leaves the daemon epoch at 12.
        VehicleCommandRouter.TrunkOpenCommand carried =
                VehicleCommandRouter.manualTailgateCloudCommand(
                        originalGeneration, captured, cancellation,
                        () -> localGeneration.get() == originalGeneration,
                        () -> VehicleCommandRouter.nativeTailgateAuthorityCurrent(12L, 12L));
        assertFalse(VehicleCommandRouter.tailgateAuthorityCurrent(
                carried, localGeneration.get()));
        // The cancellation is independently retained even if a caller supplies the old S.
        assertFalse(VehicleCommandRouter.tailgateAuthorityCurrent(carried, originalGeneration));

        java.util.concurrent.atomic.AtomicInteger cloudCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        VehicleCommandRouter.CommandResult result = router.completeTrunkOpenAttempt(
                carried, System.currentTimeMillis(), BydDataCollector.DOOR_STATE_INVALID,
                new VehicleCommandRouter.SdkLeg(false, null),
                () -> !VehicleCommandRouter.tailgateAuthorityCurrent(
                        carried, localGeneration.get()),
                () -> { throw new AssertionError("A cancelled OPEN cannot enter cloud admission"); },
                () -> {
                    cloudCalls.incrementAndGet();
                    throw new AssertionError("A cancelled OPEN cannot reach physical dispatch");
                });
        assertEquals(VehicleCommandRouter.Outcome.FAILED, result.outcome);
        assertEquals(0, cloudCalls.get());
    }

    @Test
    public void appStopDuringDaemonRecheckKeepsOriginalManualCloudOwner() {
        java.util.concurrent.atomic.AtomicLong localGeneration =
                new java.util.concurrent.atomic.AtomicLong(7L);
        com.overdrive.app.byd.LocalActuationCancellation cancellation =
                new com.overdrive.app.byd.LocalActuationCancellation();
        VehicleCommandRouter.TrunkOpenCommand carried =
                VehicleCommandRouter.manualTailgateCloudCommand(
                        7L, 12L, cancellation, () -> localGeneration.get() == 7L,
                        () -> {
                            localGeneration.incrementAndGet();
                            cancellation.cancel();
                            return true; // Daemon is still exactly epoch 12.
                        });
        assertFalse(VehicleCommandRouter.tailgateAuthorityCurrent(carried, 7L));
        assertTrue(VehicleCommandRouter.nativeTailgateAuthorityCurrent(12L, 12L));
    }

    @Test
    public void sdkStopAndNamedNativeTiltRemainSeparateDomains() throws Exception {
        VehicleCommandRouter.SunroofCommand sdkStop = new VehicleCommandRouter.SunroofCommand(3);
        VehicleCommandRouter.NativeVehicleCommand nativeTilt = command(
                NativeVehicleAction.SUNROOF_TILT);
        VehicleCommandRouter.NativeVehicleCommand nativeStop = command(
                NativeVehicleAction.SUNROOF_STOP);

        assertEquals(3, sdkStop.command);
        assertEquals(3, nativeTilt.action.value);
        assertEquals(4, nativeStop.action.value);
        assertEquals(6, command(NativeVehicleAction.SUNROOF_COMFORT).action.value);
    }

    private static VehicleCommandRouter.NativeVehicleCommand command(
            NativeVehicleAction action) throws Exception {
        Constructor<VehicleCommandRouter.NativeVehicleCommand> constructor =
                VehicleCommandRouter.NativeVehicleCommand.class
                        .getDeclaredConstructor(NativeVehicleAction.class);
        constructor.setAccessible(true);
        return constructor.newInstance(action);
    }
}
