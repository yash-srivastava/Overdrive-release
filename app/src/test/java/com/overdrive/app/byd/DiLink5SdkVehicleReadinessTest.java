package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class DiLink5SdkVehicleReadinessTest {

    private static final Object ADAPTER = new Object();

    private static final class TestClock implements DiLink5VehicleReadiness.Clock {
        long now;

        @Override
        public long elapsedRealtime() {
            return now;
        }

        void sleep(long millis) {
            now += millis;
        }
    }

    @Test
    public void delayedBindingDispatchesExactlyOnceAfterReadiness() {
        TestClock clock = new TestClock();
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger setters = new AtomicInteger();

        DiLink5VehicleReadiness.Outcome outcome = execute(
                clock,
                100L,
                () -> probes.incrementAndGet() == 3 ? ADAPTER : null,
                ignored -> true,
                () -> true,
                () -> true,
                ignored -> {
                    setters.incrementAndGet();
                    return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                },
                clock::sleep);

        assertEquals(DiLink5VehicleReadiness.Outcome.ACCEPTED, outcome);
        assertEquals(3, probes.get());
        assertEquals(1, setters.get());
        assertEquals(20L, clock.now);
    }

    @Test
    public void neverBoundAdapterDoesNotDispatch() {
        TestClock clock = new TestClock();
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger setters = new AtomicInteger();

        DiLink5VehicleReadiness.Outcome outcome = execute(
                clock,
                100L,
                () -> {
                    probes.incrementAndGet();
                    return null;
                },
                ignored -> true,
                () -> true,
                () -> true,
                ignored -> {
                    setters.incrementAndGet();
                    return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                },
                clock::sleep);

        assertEquals(DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED, outcome);
        assertEquals(5, probes.get());
        assertEquals(0, setters.get());
    }

    @Test
    public void expiryDuringWaitDoesNotDispatch() {
        TestClock clock = new TestClock();
        AtomicInteger setters = new AtomicInteger();

        DiLink5VehicleReadiness.Outcome outcome = execute(
                clock,
                10L,
                () -> null,
                ignored -> true,
                () -> true,
                () -> true,
                ignored -> {
                    setters.incrementAndGet();
                    return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                },
                clock::sleep);

        assertEquals(DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED, outcome);
        assertEquals(0, setters.get());
        assertEquals(10L, clock.now);
    }

    @Test
    public void cancellationDuringWaitDoesNotDispatch() {
        TestClock clock = new TestClock();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger setters = new AtomicInteger();

        DiLink5VehicleReadiness.Outcome outcome = DiLink5VehicleReadiness.awaitAndDispatch(
                5,
                10L,
                100L,
                clock,
                cancelled::get,
                () -> true,
                () -> null,
                ignored -> true,
                () -> true,
                () -> true,
                ignored -> {
                    setters.incrementAndGet();
                    return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                },
                millis -> {
                    clock.sleep(millis);
                    cancelled.set(true);
                });

        assertEquals(DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED, outcome);
        assertEquals(0, setters.get());
    }

    @Test
    public void adapterDeathAfterReadinessDoesNotDispatch() {
        TestClock clock = new TestClock();
        AtomicInteger readinessChecks = new AtomicInteger();
        AtomicInteger setters = new AtomicInteger();

        DiLink5VehicleReadiness.Outcome outcome = execute(
                clock,
                100L,
                () -> ADAPTER,
                ignored -> readinessChecks.incrementAndGet() == 1,
                () -> true,
                () -> true,
                ignored -> {
                    setters.incrementAndGet();
                    return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                },
                clock::sleep);

        assertEquals(DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED, outcome);
        assertEquals(0, setters.get());
        assertEquals(2, readinessChecks.get());
    }

    @Test
    public void newlyUnsafeMovementAfterPermitDoesNotDispatch() {
        TestClock clock = new TestClock();
        AtomicInteger safetyChecks = new AtomicInteger();
        AtomicInteger setters = new AtomicInteger();

        DiLink5VehicleReadiness.Outcome outcome = execute(
                clock,
                100L,
                () -> ADAPTER,
                ignored -> true,
                () -> safetyChecks.incrementAndGet() == 1,
                () -> true,
                ignored -> {
                    setters.incrementAndGet();
                    return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                },
                clock::sleep);

        assertEquals(DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED, outcome);
        assertEquals(0, setters.get());
        assertEquals(2, safetyChecks.get());
    }

    @Test
    public void revokedProximityPermitDoesNotDispatch() {
        TestClock clock = new TestClock();
        AtomicInteger setters = new AtomicInteger();

        DiLink5VehicleReadiness.Outcome outcome = execute(
                clock,
                100L,
                () -> ADAPTER,
                ignored -> true,
                () -> true,
                () -> false,
                ignored -> {
                    setters.incrementAndGet();
                    return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                },
                clock::sleep);

        assertEquals(DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED, outcome);
        assertEquals(0, setters.get());
    }

    @Test
    public void lockedOrUnknownTailgateSafetyDoesNotDispatch() {
        for (int lockState : new int[] {
                BydDataCollector.DOOR_STATE_LOCK,
                BydDataCollector.DOOR_STATE_INVALID}) {
            TestClock clock = new TestClock();
            AtomicInteger setters = new AtomicInteger();
            DiLink5VehicleReadiness.Outcome outcome = execute(
                    clock,
                    100L,
                    () -> ADAPTER,
                    ignored -> true,
                    () -> lockState == BydDataCollector.DOOR_STATE_UNLOCK,
                    () -> true,
                    ignored -> {
                        setters.incrementAndGet();
                        return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                    },
                    clock::sleep);
            assertEquals(DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED, outcome);
            assertEquals(0, setters.get());
        }
    }

    @Test
    public void definitiveRejectionAndUncertainFailureAreNeverRetried() {
        TestClock clock = new TestClock();
        AtomicInteger rejectedCalls = new AtomicInteger();
        DiLink5VehicleReadiness.Outcome rejected = execute(
                clock,
                100L,
                () -> ADAPTER,
                ignored -> true,
                () -> true,
                () -> true,
                ignored -> {
                    rejectedCalls.incrementAndGet();
                    return DiLink5VehicleReadiness.Outcome.REJECTED;
                },
                clock::sleep);
        assertEquals(DiLink5VehicleReadiness.Outcome.REJECTED, rejected);
        assertEquals(1, rejectedCalls.get());

        AtomicInteger uncertainCalls = new AtomicInteger();
        DiLink5VehicleReadiness.Outcome uncertain = execute(
                clock,
                100L,
                () -> ADAPTER,
                ignored -> true,
                () -> true,
                () -> true,
                ignored -> {
                    uncertainCalls.incrementAndGet();
                    throw new IllegalStateException("reply lost");
                },
                clock::sleep);
        assertEquals(DiLink5VehicleReadiness.Outcome.UNCERTAIN, uncertain);
        assertEquals(1, uncertainCalls.get());
    }

    @Test
    public void sdkResultClassificationKeepsTimeoutAndUnknownSeparateFromRejection() {
        assertEquals(DiLink5VehicleReadiness.Outcome.ACCEPTED,
                BydDataCollector.classifyDiLink5SdkResult(Integer.valueOf(0)));
        assertEquals(DiLink5VehicleReadiness.Outcome.UNCERTAIN,
                BydDataCollector.classifyDiLink5SdkResult(Integer.valueOf(2)));
        assertEquals(DiLink5VehicleReadiness.Outcome.UNCERTAIN,
                BydDataCollector.classifyDiLink5SdkResult(
                        Integer.valueOf(com.byd.datasource.feature.Status.STATUS_TIMEOUT)));
        assertEquals(DiLink5VehicleReadiness.Outcome.REJECTED,
                BydDataCollector.classifyDiLink5SdkResult(Integer.valueOf(3)));
        assertEquals(DiLink5VehicleReadiness.Outcome.UNCERTAIN,
                BydDataCollector.classifyDiLink5SdkResult(null));
        assertTrue(BydDataCollector.diLink5SdkAbortForOutcome(
                DiLink5VehicleReadiness.Outcome.UNCERTAIN) != null);
        assertTrue(BydDataCollector.diLink5SdkAbortForOutcome(
                DiLink5VehicleReadiness.Outcome.REJECTED) == null);
    }

    @Test
    public void assignedBridgeWritesOwnCancellationWithoutChangingDoorAdmission()
            throws Exception {
        assertTrue(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.ClimateOnCommand(22D)));
        assertTrue(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.ClimateOffCommand()));
        assertTrue(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.SunroofCommand(1)));
        assertTrue(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.SunshadeCommand(2)));
        assertTrue(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.HazardCommand(true)));
        assertTrue(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkOpenCommand()));
        assertTrue(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkCloseCommand()));
        assertTrue(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.TrunkStopCommand()));
        assertFalse(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.LockCommand()));
        assertFalse(VehicleActuatorBridge.usesDiLink5Cancellation(
                new com.overdrive.app.byd.routing.VehicleCommandRouter.UnlockCommand()));

        LocalActuationCancellation cancellation = new LocalActuationCancellation();
        AtomicInteger setters = new AtomicInteger();
        cancellation.cancel();
        DiLink5VehicleReadiness.Outcome outcome =
                VehicleActuatorBridge.withRemoteKeyRequestCancellation(
                        cancellation,
                        () -> VehicleActuatorBridge.dispatchWhileRemoteKeyRequestActive(
                                () -> {
                                    setters.incrementAndGet();
                                    return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                                },
                                DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED));
        assertEquals(DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED, outcome);
        assertEquals(0, setters.get());
    }

    @Test
    public void cancellationAfterAdapterAcquisitionDispatchesNoSetter() {
        TestClock clock = new TestClock();
        LocalActuationCancellation cancellation = new LocalActuationCancellation();
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger safetyChecks = new AtomicInteger();
        AtomicInteger setters = new AtomicInteger();

        DiLink5VehicleReadiness.Outcome outcome = DiLink5VehicleReadiness.awaitAndDispatch(
                5,
                10L,
                100L,
                clock,
                cancellation::isCancelled,
                () -> true,
                () -> {
                    probes.incrementAndGet();
                    return ADAPTER;
                },
                ignored -> true,
                () -> {
                    safetyChecks.incrementAndGet();
                    cancellation.cancel();
                    return true;
                },
                () -> true,
                ignored -> VehicleActuatorBridge.dispatchWhileRemoteKeyRequestActive(
                        () -> {
                            setters.incrementAndGet();
                            return DiLink5VehicleReadiness.Outcome.ACCEPTED;
                        },
                        DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED),
                clock::sleep);

        assertEquals(DiLink5VehicleReadiness.Outcome.NOT_DISPATCHED, outcome);
        assertEquals(1, probes.get());
        assertEquals(1, safetyChecks.get());
        assertEquals(0, setters.get());
    }

    @Test
    public void assignedSdkWritesUseBoundedAdaptersAndFinalTailgateGuards()
            throws Exception {
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertTrue(collector.contains("dispatchDiLink5SdkWrite("));
        assertTrue(collector.contains("\"getHvacAdapterManager\",\n                    \"hvac\""));
        assertTrue(collector.contains("\"getCarBodyManager\",\n                        \"body\""));
        assertTrue(collector.contains("\"getSettingAdapterManager\",\n                \"setting\""));
        assertTrue(collector.contains(
                "cmd != 1 || readDoorLockState() == DOOR_STATE_UNLOCK"));
        assertTrue(collector.contains(
                "VehicleActuatorBridge::confirmDiLink5WriteSafety"));
        assertTrue(collector.contains("rawResult[0] = BydDeviceHelper.callGetter(adapter, methodName, 0)"));
        assertTrue(collector.contains("adapter, adapterCommand, voiceCommand"));
        assertTrue(collector.contains("\"setVoiceCtlMoonRoofS\""));
        assertTrue(collector.contains("\"setVoiceCtlSunshadePanelS\""));
        assertTrue(collector.contains("bodyworkDevice, facadeCommand, voiceCommand"));
        assertTrue(collector.contains("adapter, \"voiceCtlBackDoor\", cmd"));
        assertTrue(collector.contains("adapter, \"setTurnLightsFlashState\""));

        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/VehicleActuatorBridge.java");
        assertTrue(bridge.contains("String motionSafetyGuard"));
        assertTrue(bridge.contains("confirmProximityBeforeDiLink5Write()"));
        assertTrue(bridge.contains("getActionBlockReason(guard)"));
        assertTrue(bridge.contains("dispatchWhileRemoteKeyRequestActive("));
        assertTrue(bridge.contains("usesDiLink5Cancellation("));
        assertTrue(collector.contains("LocalActuationAbort.Kind.UNCERTAIN"));
    }

    @Test
    public void olderGenerationBranchesRetainTheirFallbacksAndDomains()
            throws Exception {
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertTrue(collector.contains("if (code == 3)"));
        assertTrue(collector.contains(
                "sendSetCommand(acDevice, BydFeatureIds.AC_AUTO_MODE_SET, on ? 1 : 0)"));
        assertEquals(4, BydDataCollector.sunWindowVoiceCommand(3));
        assertEquals(3, BydDataCollector.sunWindowVoiceCommand(4));
        assertEquals(1, BydDataCollector.diLink5HazardCommand(true));
        assertEquals(2, BydDataCollector.diLink5HazardCommand(false));
    }

    private static DiLink5VehicleReadiness.Outcome execute(
            TestClock clock,
            long deadline,
            DiLink5VehicleReadiness.AdapterProbe<Object> probe,
            DiLink5VehicleReadiness.AdapterCheck<Object> ready,
            DiLink5VehicleReadiness.SafetyCheck safety,
            DiLink5VehicleReadiness.FinalPermit permit,
            DiLink5VehicleReadiness.Setter<Object> setter,
            DiLink5VehicleReadiness.Sleeper sleeper) {
        return DiLink5VehicleReadiness.awaitAndDispatch(
                5,
                10L,
                deadline,
                clock,
                () -> false,
                () -> true,
                probe,
                ready,
                safety,
                permit,
                setter,
                sleeper);
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path fromModule = Paths.get(relative.substring("app/".length()));
        Path path = Files.exists(fromModule) ? fromModule : Paths.get(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
