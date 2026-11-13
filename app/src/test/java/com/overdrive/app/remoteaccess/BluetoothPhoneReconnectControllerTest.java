package com.overdrive.app.remoteaccess;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Drives recovery sequences against fake native profiles; no Android service or car is accessed. */
public class BluetoothPhoneReconnectControllerTest {
    private static final String PHONE = "AA:BB:CC:DD:EE:01";
    private static final String OTHER = "AA:BB:CC:DD:EE:02";

    private static final class TestClock implements BluetoothPhoneReconnectController.Clock {
        long now;
        @Override public long elapsedRealtime() { return now; }
        @Override public long wallTimeMillis() { return 1_700_000_000_000L + now; }
    }

    private static final class TestGate implements BluetoothPhoneReconnectController.Gate {
        volatile String reason;
        @Override public String blockReason() { return reason; }
    }

    private static final class FakeProfile implements BluetoothPhoneReconnectController.Profile {
        Collection<?> busy = Collections.emptyList();
        int state;
        int priority = 100;
        boolean accepted = true;
        boolean queryFailure;
        RuntimeException busyFailure;
        RuntimeException stateFailure;
        RuntimeException priorityFailure;
        RuntimeException connectFailure;
        int connects;
        int stateReads;
        String lastStateAddress;
        String lastAddress;
        List<String> requestedAddresses = new ArrayList<>();
        Runnable duringPriority;

        @Override public Collection<?> busyDevices() throws Exception {
            if (busyFailure != null) throw busyFailure;
            if (queryFailure) throw new Exception("Native service unavailable");
            return busy;
        }
        @Override public int connectionState(String address) {
            stateReads++;
            lastStateAddress = address;
            if (stateFailure != null) throw stateFailure;
            return state;
        }
        @Override public int priority(String address) {
            if (duringPriority != null) duringPriority.run();
            if (priorityFailure != null) throw priorityFailure;
            return priority;
        }
        @Override public boolean connect(String address) {
            connects++;
            lastAddress = address;
            requestedAddresses.add(address);
            if (connectFailure != null) throw connectFailure;
            return accepted;
        }
    }

    private static final class FakePlatform implements BluetoothPhoneReconnectController.Platform {
        boolean supported = true;
        boolean on = true;
        boolean openAccepted = true;
        String activity;
        boolean activityFailure;
        RuntimeException adapterFailure;
        RuntimeException connectedFailure;
        RuntimeException activityException;
        RuntimeException openFailure;
        int activityReads;
        Runnable duringActivity;
        Set<String> bonded = new HashSet<>(Arrays.asList(PHONE, OTHER));
        Set<String> connected = new HashSet<>();
        int opens;
        int cancels;
        int pendingBindings;
        int lifecycleCloses;
        List<BluetoothPhoneReconnectController.ProfileListener> listeners = new ArrayList<>();
        List<BluetoothPhoneReconnectController.Profile> closed = new ArrayList<>();

        @Override public boolean supported() { return supported; }
        @Override public boolean adapterOn() {
            if (adapterFailure != null) throw adapterFailure;
            return on;
        }
        @Override public boolean bonded(String address) { return bonded.contains(address); }
        @Override public boolean connected(String address) {
            if (connectedFailure != null) throw connectedFailure;
            return connected.contains(address);
        }
        @Override public String activityBlockReason(String address) throws Exception {
            activityReads++;
            if (duringActivity != null) duringActivity.run();
            if (activityException != null) throw activityException;
            if (activityFailure) throw new Exception("Native adapter state unavailable");
            return activity;
        }
        @Override public boolean openProfile(BluetoothPhoneReconnectController.ProfileListener listener) {
            opens++;
            listeners.add(listener);
            if (openFailure != null) throw openFailure;
            if (openAccepted) pendingBindings++;
            return openAccepted;
        }
        @Override public void cancelPendingProfile() { cancels++; pendingBindings = 0; }
        @Override public void closeProfile(BluetoothPhoneReconnectController.Profile profile) {
            closed.add(profile);
        }
        @Override public void close() { lifecycleCloses++; }
        void ready(FakeProfile profile) {
            pendingBindings = 0;
            listeners.get(listeners.size() - 1).connected(16, profile);
        }
    }

    private static final class Fixture {
        final TestClock clock = new TestClock();
        final TestGate gate = new TestGate();
        final FakePlatform platform = new FakePlatform();
        final FakeProfile profile = new FakeProfile();
        final BluetoothPhoneReconnectController controller;
        Fixture() { this(Arrays.asList(PHONE, OTHER)); }
        Fixture(Collection<String> addresses) {
            platform.bonded.addAll(addresses);
            controller = new BluetoothPhoneReconnectController(addresses, platform, gate, clock);
        }
        void ready() {
            controller.tick(PHONE, false);
            platform.ready(profile);
        }
    }

    @Test public void ineligibleFeaturesNeverOpenNativeProfiles() {
        for (String blocked : Arrays.asList("master_off", "shared_bluetooth_off", "vehicle_on",
                "power_unknown", "vehicle_on_only", "settings_changed", "settings_unavailable")) {
            Fixture f = new Fixture();
            f.gate.reason = blocked;
            f.controller.tick(PHONE, false);
            assertEquals(blocked, f.controller.snapshot(PHONE).reason);
            assertEquals(0, f.platform.opens);
            assertEquals(0, f.profile.connects);
        }
        Fixture f = new Fixture();
        f.platform.supported = false;
        f.controller.tick(PHONE, false);
        assertEquals("unsupported_platform", f.controller.snapshot(PHONE).reason);
        assertEquals(0, f.platform.opens);
        f.platform.supported = true;
        f.platform.on = false;
        f.controller.tick(PHONE, false);
        assertEquals("radio_off", f.controller.snapshot(PHONE).reason);
        f.platform.on = true;
        f.platform.bonded.remove(PHONE);
        f.controller.tick(PHONE, false);
        assertEquals("phone_not_paired", f.controller.snapshot(PHONE).reason);
        f.controller.tick("AA:BB:CC:DD:EE:03", false);
        assertEquals(0, f.platform.opens);
    }

    @Test public void consentIsRecheckedBeforeOpeningTheNativeProfile() {
        Fixture f = new Fixture();
        BluetoothPhoneReconnectController.Gate changing = new BluetoothPhoneReconnectController.Gate() {
            int reads;
            @Override public String blockReason() { return ++reads == 1 ? null : "master_off"; }
        };
        BluetoothPhoneReconnectController controller = new BluetoothPhoneReconnectController(
                Collections.singletonList(PHONE), f.platform, changing, f.clock);
        controller.tick(PHONE, false);
        assertEquals(0, f.platform.opens);
        assertEquals("master_off", controller.snapshot(PHONE).reason);
    }

    @Test public void stopCancelsPendingBindingAndClosesEveryLateCallback() {
        Fixture f = new Fixture();
        f.controller.tick(PHONE, false);
        assertEquals(1, f.platform.pendingBindings);
        f.controller.stop();
        assertEquals(0, f.platform.pendingBindings);
        f.platform.ready(f.profile);
        f.controller.tick(PHONE, false);
        assertTrue(f.platform.closed.contains(f.profile));
        assertEquals(0, f.profile.connects);
        assertEquals("stopped", f.controller.snapshot(PHONE).reason);
        f.controller.stop();
        assertEquals(1, f.platform.lifecycleCloses);
    }

    @Test public void masterOffDuringBindingClosesTheLateProxyAndCanResumeLater() {
        Fixture f = new Fixture();
        f.controller.tick(PHONE, false);
        BluetoothPhoneReconnectController.ProfileListener old = f.platform.listeners.get(0);
        f.gate.reason = "master_off";
        f.controller.tick(PHONE, false);
        assertEquals(0, f.platform.pendingBindings);
        old.connected(16, f.profile);
        assertTrue(f.platform.closed.contains(f.profile));
        assertEquals(0, f.profile.connects);
        f.gate.reason = null;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.platform.opens);
        old.connected(16, f.profile);
        assertEquals(0, f.profile.connects);
    }

    @Test public void masterOffBeforeTheRealConnectPreventsTheRequestAndClosesProxy() {
        Fixture f = new Fixture();
        f.ready();
        f.profile.duringPriority = () -> f.gate.reason = "master_off";
        f.controller.tick(PHONE, false);
        assertEquals(0, f.profile.connects);
        assertEquals("master_off", f.controller.snapshot(PHONE).reason);
        assertTrue(f.platform.closed.contains(f.profile));
    }

    @Test public void anotherPhoneBecomingBusyAtFinalCheckPreventsSwitching() {
        Fixture f = new Fixture();
        f.ready();
        f.profile.duringPriority = () -> f.profile.busy = Collections.singletonList(OTHER);
        f.controller.tick(PHONE, false);
        assertEquals(0, f.profile.connects);
        assertEquals("profile_busy", f.controller.snapshot(PHONE).reason);
    }

    @Test public void activeOrTransitionalDeviceAndDisabledPriorityNeverConnect() {
        Fixture f = new Fixture();
        f.ready();
        f.profile.busy = Collections.singletonList("Any other paired phone");
        f.controller.tick(PHONE, false);
        assertEquals("profile_busy", f.controller.snapshot(PHONE).reason);
        f.profile.busy = Collections.emptyList();
        for (int state : new int[]{1, 2, 3}) {
            f.profile.state = state;
            f.controller.tick(PHONE, false);
            assertEquals(0, f.profile.connects);
        }
        f.profile.state = 0;
        f.profile.priority = 0;
        f.controller.tick(PHONE, false);
        assertEquals("priority_disabled", f.controller.snapshot(PHONE).reason);
        assertEquals(0, f.profile.connects);
    }

    @Test public void missingOrFailedBusyReadFailsClosedInsteadOfTreatingProfileAsIdle() {
        for (boolean throwsError : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.ready();
            f.profile.busy = null;
            f.profile.queryFailure = throwsError;
            f.controller.tick(PHONE, false);
            assertEquals("profile_unavailable", f.controller.snapshot(PHONE).reason);
            assertFailure(f.controller.snapshot(PHONE), "profile_busy_devices",
                    throwsError ? "Exception" : "IllegalStateException");
            assertEquals(0, f.profile.connects);
            assertTrue(f.platform.closed.contains(f.profile));
        }
    }

    @Test public void incorrectProfileCallbackIsClosedAndNeverUsed() {
        Fixture f = new Fixture();
        f.controller.tick(PHONE, false);
        f.platform.listeners.get(0).connected(11, f.profile);
        f.controller.tick(PHONE, false);
        assertTrue(f.platform.closed.contains(f.profile));
        assertEquals(0, f.platform.pendingBindings);
        assertEquals(0, f.profile.connects);
        assertEquals(1, f.platform.opens);
    }

    @Test public void bindingTimeoutCancelsResourcesAndRejectsTheLateGeneration() {
        Fixture f = new Fixture();
        f.controller.tick(PHONE, false);
        f.clock.now = 5_000;
        f.controller.tick(PHONE, false);
        assertEquals("profile_timeout", f.controller.snapshot(PHONE).reason);
        assertEquals(0, f.platform.pendingBindings);
        f.platform.ready(f.profile);
        assertTrue(f.platform.closed.contains(f.profile));
        f.clock.now = 7_999;
        f.controller.tick(PHONE, false);
        assertEquals(1, f.platform.opens);
        f.clock.now = 8_000;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.platform.opens);
    }

    @Test public void acceptanceIsPendingUntilAnIndependentConnectionObservation() {
        Fixture f = new Fixture(Collections.singletonList(PHONE));
        f.ready();
        f.controller.tick(PHONE, false);
        assertEquals("connecting", f.controller.snapshot(PHONE).state);
        assertFalse(f.platform.connected(PHONE));
        assertEquals(1, f.profile.connects);
        f.clock.now = 14_000;
        f.controller.tick(PHONE, false);
        assertEquals(1, f.profile.connects);
        f.clock.now = 15_000;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.profile.connects);
        assertEquals("connecting", f.controller.snapshot(PHONE).state);
        f.platform.connected.add(PHONE);
        f.controller.tick(PHONE, true);
        assertEquals("connected", f.controller.snapshot(PHONE).state);
    }

    @Test public void permanentRejectionsRetryAtExactlyThreeSeconds() {
        Fixture f = new Fixture(Collections.singletonList(PHONE));
        f.profile.accepted = false;
        f.ready();
        f.controller.tick(PHONE, false);
        assertEquals(1, f.profile.connects);
        for (int requests = 1; requests <= 12; requests++) {
            long delay = 3_000;
            assertEquals(delay, f.controller.snapshot(PHONE).retryInMs);
            f.clock.now += delay - 1;
            f.controller.tick(PHONE, false);
            assertEquals(requests, f.profile.connects);
            assertEquals("retry_backoff", f.controller.snapshot(PHONE).reason);
            f.clock.now++;
            f.controller.tick(PHONE, false);
            assertEquals(requests + 1, f.profile.connects);
            assertEquals("attempt_rejected", f.controller.snapshot(PHONE).reason);
        }
        assertEquals(1, f.platform.opens);
    }

    @Test public void phonesShareOneProfileAndGlobalRequestSpacing() {
        Fixture f = new Fixture();
        f.profile.accepted = false;
        f.controller.tick(PHONE, false);
        f.controller.tick(OTHER, false);
        assertEquals(1, f.platform.opens);
        f.platform.ready(f.profile);
        f.controller.tick(PHONE, false);
        f.controller.tick(OTHER, false);
        assertEquals(1, f.profile.connects);
        f.clock.now = 2_999;
        f.controller.tick(OTHER, false);
        assertEquals(1, f.profile.connects);
        f.clock.now = 3_000;
        f.controller.tick(OTHER, false);
        assertEquals(2, f.profile.connects);
        assertEquals(OTHER, f.profile.lastAddress);
        assertEquals(1, f.platform.opens);
    }

    @Test public void fixedWorkerOrderRetriesAllEightPhonesBeforeRepeating() {
        List<String> addresses = new ArrayList<>(Arrays.asList(PHONE, OTHER));
        for (int i = 3; i <= 8; i++) addresses.add("AA:BB:CC:DD:EE:0" + i);
        Fixture f = new Fixture(addresses);
        f.profile.accepted = false;
        f.ready();
        for (int request = 0; request < 32; request++) {
            f.clock.now = request * 3_000L;
            for (String address : addresses) f.controller.tick(address, false);
            assertEquals(request + 1, f.profile.connects);
            assertEquals(addresses.get(request % addresses.size()), f.profile.lastAddress);
        }
        assertEquals(1, f.platform.opens);
        for (int i = 0; i < f.profile.requestedAddresses.size(); i++) {
            assertEquals(addresses.get(i % addresses.size()), f.profile.requestedAddresses.get(i));
        }
    }

    @Test public void connectedAndPriorityBlockedPhonesAreSkippedAndCanLaterRejoin() {
        String third = "AA:BB:CC:DD:EE:03";
        Fixture f = new Fixture(Arrays.asList(PHONE, OTHER, third));
        f.profile.accepted = false;
        f.platform.connected.add(PHONE);
        f.controller.tick(PHONE, true);
        f.controller.tick(OTHER, false);
        f.platform.ready(f.profile);
        f.profile.priority = 0;
        f.controller.tick(OTHER, false);
        assertEquals("priority_disabled", f.controller.snapshot(OTHER).reason);
        f.profile.priority = 100;

        for (long now : new long[]{0, 3_000, 6_000, 9_000}) {
            f.clock.now = now;
            f.controller.tick(third, false);
            assertEquals(third, f.profile.lastAddress);
        }
        assertEquals(4, f.profile.connects);
        f.clock.now = 12_000;
        f.controller.tick(OTHER, false);
        assertEquals(5, f.profile.connects);
        assertEquals(OTHER, f.profile.lastAddress);

        // A current fresh worker overrides its old connected cached state.
        f.platform.connected.remove(PHONE);
        f.clock.now = 15_000;
        f.controller.tick(PHONE, false);
        assertEquals(6, f.profile.connects);
        assertEquals(PHONE, f.profile.lastAddress);
    }

    @Test public void simultaneousPhoneTicksCannotIssueTwoNativeRequests() throws Exception {
        Fixture f = new Fixture();
        f.ready();
        CountDownLatch start = new CountDownLatch(1), finished = new CountDownLatch(2);
        for (String address : Arrays.asList(PHONE, OTHER)) {
            new Thread(() -> {
                try {
                    start.await();
                    f.controller.tick(address, false);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally { finished.countDown(); }
            }).start();
        }
        start.countDown();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(1, f.profile.connects);
    }

    @Test public void disabledGuardAlsoReleasesAnAlreadyBoundProfile() {
        Fixture f = new Fixture();
        f.ready();
        f.gate.reason = "shared_bluetooth_off";
        f.controller.tick(PHONE, false);
        assertTrue(f.platform.closed.contains(f.profile));
        assertEquals(0, f.profile.connects);
    }

    @Test public void otherProfileOrAccessoryActivityAndDiscoveryNeverOpenAProfile() {
        for (String activity : Arrays.asList("other_bluetooth_active", "discovery_active",
                "bluetooth_busy")) {
            Fixture f = new Fixture();
            f.platform.activity = activity;
            f.controller.tick(PHONE, false);
            assertEquals(0, f.platform.opens);
            assertEquals(0, f.profile.connects);
            assertEquals(activity, f.controller.snapshot(PHONE).reason);
        }
    }

    @Test public void otherBluetoothActivityClosesAnExistingNativeLease() {
        Fixture f = new Fixture();
        f.ready();
        f.platform.activity = "other_bluetooth_active";
        f.controller.tick(PHONE, false);
        assertTrue(f.platform.closed.contains(f.profile));
        assertEquals(0, f.profile.connects);
        f.platform.activity = null;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.platform.opens);
    }

    @Test public void newDiscoveryOrAccessoryConnectionAtFinalBoundaryPreventsConnect() {
        for (String activity : Arrays.asList("other_bluetooth_active", "discovery_active",
                "bluetooth_busy")) {
            Fixture f = new Fixture();
            f.ready();
            f.profile.duringPriority = () -> f.platform.activity = activity;
            f.controller.tick(PHONE, false);
            assertEquals(0, f.profile.connects);
            assertTrue(f.platform.closed.contains(f.profile));
            assertEquals(activity, f.controller.snapshot(PHONE).reason);
        }
    }

    @Test public void unavailableAggregateAdapterStateFailsClosedAndReleasesLease() {
        Fixture f = new Fixture();
        f.ready();
        f.platform.activityFailure = true;
        f.controller.tick(PHONE, false);
        assertEquals(0, f.profile.connects);
        assertEquals("bluetooth_state_unavailable", f.controller.snapshot(PHONE).reason);
        assertFailure(f.controller.snapshot(PHONE), "adapter_activity", "Exception");
        assertTrue(f.platform.closed.contains(f.profile));
    }

    @Test public void ownConfirmedConnectionDoesNotBecomeBlockedByAggregateBusyState() {
        Fixture f = new Fixture();
        f.platform.connected.add(PHONE);
        f.platform.activity = "bluetooth_busy";
        f.controller.tick(PHONE, true);
        assertEquals("connected", f.controller.snapshot(PHONE).state);
        assertEquals(0, f.platform.activityReads);
        assertEquals(0, f.platform.opens);
    }

    @Test public void activePairingAndItsGracePeriodPauseOnlyTheReconnectAttempt() {
        for (String activity : Arrays.asList("pairing_active", "pairing_grace")) {
            Fixture f = new Fixture();
            f.platform.activity = activity;
            f.controller.tick(PHONE, false);
            assertEquals(0, f.platform.opens);
            assertEquals(activity, f.controller.snapshot(PHONE).reason);
            f.platform.activity = null;
            f.ready();
            f.profile.duringPriority = () -> f.platform.activity = activity;
            f.controller.tick(PHONE, false);
            assertEquals(0, f.profile.connects);
            assertTrue(f.platform.closed.contains(f.profile));
        }
    }

    @Test public void adapterFailuresExposeTheOperationWithoutExceptionMessages() {
        for (String stage : Arrays.asList("adapter_state", "profile_binding", "adapter_activity")) {
            Fixture f = new Fixture();
            RuntimeException failure = new SecurityException("Private native failure for " + PHONE);
            if ("adapter_state".equals(stage)) f.platform.adapterFailure = failure;
            else if ("profile_binding".equals(stage)) f.platform.openFailure = failure;
            else f.platform.activityException = failure;
            f.controller.tick(PHONE, false);
            assertFailure(f.controller.snapshot(PHONE), stage, "SecurityException");
            assertEquals(0, f.profile.connects);
        }
    }

    @Test public void profileFailuresExposeEachNativeOperation() {
        for (String stage : Arrays.asList("profile_busy_devices", "profile_connection_state",
                "profile_priority", "profile_connect")) {
            Fixture f = new Fixture();
            f.ready();
            RuntimeException failure = new IllegalStateException("Private native failure for " + PHONE);
            if ("profile_busy_devices".equals(stage)) f.profile.busyFailure = failure;
            else if ("profile_connection_state".equals(stage)) f.profile.stateFailure = failure;
            else if ("profile_priority".equals(stage)) f.profile.priorityFailure = failure;
            else f.profile.connectFailure = failure;
            f.controller.tick(PHONE, false);
            assertEquals("profile_unavailable", f.controller.snapshot(PHONE).reason);
            assertFailure(f.controller.snapshot(PHONE), stage, "IllegalStateException");
            assertTrue(f.platform.closed.contains(f.profile));
            assertEquals("profile_connect".equals(stage) ? 1 : 0, f.profile.connects);
        }
    }

    @Test public void aFailedFinalConnectionReadIsReportedAsAdapterStateAndPreventsConnect() {
        Fixture f = new Fixture();
        f.ready();
        f.platform.connectedFailure = new SecurityException("Private native failure for " + PHONE);
        f.controller.tick(PHONE, false);
        assertFailure(f.controller.snapshot(PHONE), "adapter_state", "SecurityException");
        assertEquals(0, f.profile.connects);
        assertTrue(f.platform.closed.contains(f.profile));
    }

    @Test public void typedFailuresKeepTheirPreciseStageAndUnwrapReflectionCauses() {
        for (String stage : Arrays.asList("receiver_registration", "binder_unavailable")) {
            Fixture f = new Fixture();
            BluetoothPhoneReconnectController.Failure failure =
                    new BluetoothPhoneReconnectController.Failure(stage,
                    new InvocationTargetException(new InvocationTargetException(
                            new SecurityException("Private native failure for " + PHONE))));
            assertEquals(stage, failure.stage);
            assertEquals("SecurityException", failure.errorClass);
            f.platform.activityException = failure;
            f.controller.tick(PHONE, false);
            assertEquals("bluetooth_state_unavailable", f.controller.snapshot(PHONE).reason);
            assertFailure(f.controller.snapshot(PHONE), stage, "SecurityException");
            assertEquals(0, f.platform.opens);
        }
        Fixture f = new Fixture();
        f.ready();
        f.profile.connectFailure = new BluetoothPhoneReconnectController.Failure("profile_connect",
                new InvocationTargetException(new InvocationTargetException(
                        new SecurityException("Private native failure for " + PHONE))));
        f.controller.tick(PHONE, false);
        assertFailure(f.controller.snapshot(PHONE), "profile_connect", "SecurityException");
    }

    @Test public void failureDetailsSurviveBackoffAndLoadingUntilARequestIsAccepted() {
        Fixture f = new Fixture();
        f.ready();
        f.profile.priorityFailure = new SecurityException("Private native failure for " + PHONE);
        f.controller.tick(PHONE, false);
        assertFailure(f.controller.snapshot(PHONE), "profile_priority", "SecurityException");
        f.profile.priorityFailure = null;
        f.clock.now = 1_000;
        f.controller.tick(PHONE, false);
        assertEquals("retry_backoff", f.controller.snapshot(PHONE).reason);
        assertFailure(f.controller.snapshot(PHONE), "profile_priority", "SecurityException");
        f.clock.now = 8_000;
        f.controller.tick(PHONE, false);
        assertEquals("profile_loading", f.controller.snapshot(PHONE).reason);
        assertFailure(f.controller.snapshot(PHONE), "profile_priority", "SecurityException");
        f.platform.ready(f.profile);
        f.controller.tick(PHONE, false);
        assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
        assertNoFailure(f.controller.snapshot(PHONE));
        assertEquals(1, f.profile.connects);
    }

    @Test public void aRejectedRequestKeepsThePreviousFailureUntilALaterAcceptance() {
        Fixture f = new Fixture(Collections.singletonList(PHONE));
        f.ready();
        f.profile.priorityFailure = new SecurityException("Private native failure for " + PHONE);
        f.controller.tick(PHONE, false);
        f.profile.priorityFailure = null;
        f.profile.accepted = false;
        f.clock.now = 8_000;
        f.controller.tick(PHONE, false);
        f.platform.ready(f.profile);
        f.controller.tick(PHONE, false);
        assertEquals("attempt_rejected", f.controller.snapshot(PHONE).reason);
        assertFailure(f.controller.snapshot(PHONE), "profile_priority", "SecurityException");
        f.profile.accepted = true;
        f.clock.now = 16_000;
        f.controller.tick(PHONE, false);
        assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
        assertNoFailure(f.controller.snapshot(PHONE));
        assertEquals(2, f.profile.connects);
    }

    @Test public void anIndependentConnectionObservationClearsTheLastFailure() {
        Fixture f = new Fixture();
        f.ready();
        f.profile.busyFailure = new SecurityException("Private native failure for " + PHONE);
        f.controller.tick(PHONE, false);
        assertFailure(f.controller.snapshot(PHONE), "profile_busy_devices", "SecurityException");
        f.platform.connected.add(PHONE);
        f.controller.tick(PHONE, true);
        assertEquals("connected", f.controller.snapshot(PHONE).state);
        assertNoFailure(f.controller.snapshot(PHONE));
        assertEquals(0, f.profile.connects);
    }

    @Test public void staleCallbacksCannotReplaceFailureDetailsOrAnAcceptedNewRequest() {
        Fixture f = new Fixture();
        f.platform.openFailure = new BluetoothPhoneReconnectController.Failure("profile_binding",
                new NoSuchMethodException("Private native failure for " + PHONE));
        f.controller.tick(PHONE, false);
        BluetoothPhoneReconnectController.ProfileListener old = f.platform.listeners.get(0);
        assertFailure(f.controller.snapshot(PHONE), "profile_binding", "NoSuchMethodException");
        f.platform.openFailure = null;
        f.clock.now = 8_000;
        f.controller.tick(PHONE, false);
        FakeProfile abandoned = new FakeProfile();
        int activityReads = f.platform.activityReads;
        old.connected(16, abandoned);
        old.disconnected(16);
        assertTrue(f.platform.closed.contains(abandoned));
        assertEquals(activityReads, f.platform.activityReads);
        assertEquals("profile_loading", f.controller.snapshot(PHONE).reason);
        assertFailure(f.controller.snapshot(PHONE), "profile_binding", "NoSuchMethodException");
        f.platform.ready(f.profile);
        f.controller.tick(PHONE, false);
        old.disconnected(16);
        assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
        assertNoFailure(f.controller.snapshot(PHONE));
        assertEquals(1, f.profile.connects);
    }

    @Test public void stopClearsEveryPhoneFailureAndLateCallbacksCannotRestoreIt() {
        Fixture f = new Fixture();
        f.controller.tick(PHONE, false);
        BluetoothPhoneReconnectController.ProfileListener old = f.platform.listeners.get(0);
        f.platform.activityException = new BluetoothPhoneReconnectController.Failure(
                "receiver_registration", new SecurityException("Private native failure for " + PHONE));
        for (String address : Arrays.asList(PHONE, OTHER)) {
            f.controller.tick(address, false);
            assertFailure(f.controller.snapshot(address), "receiver_registration", "SecurityException");
        }
        f.controller.stop();
        int activityReads = f.platform.activityReads;
        old.connected(16, f.profile);
        old.disconnected(16);
        for (String address : Arrays.asList(PHONE, OTHER)) {
            assertEquals("stopped", f.controller.snapshot(address).reason);
            assertNoFailure(f.controller.snapshot(address));
        }
        assertEquals(activityReads, f.platform.activityReads);
        assertEquals(0, f.profile.connects);
    }

    @Test public void unexpectedMonitorFailureReclaimsPendingAndBoundProfilesAndKeepsDiagnostics() {
        for (boolean bound : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            if (bound) f.ready();
            else f.controller.tick(PHONE, false);
            BluetoothPhoneReconnectController.ProfileListener old = f.platform.listeners.get(0);
            int cancelsBeforeFailure = f.platform.cancels;
            Throwable failure = new InvocationTargetException(new InvocationTargetException(
                    new SecurityException("Private native failure for " + PHONE)));
            f.controller.stopAfterUnexpectedFailure(failure);
            assertEquals(0, f.platform.pendingBindings);
            assertEquals(cancelsBeforeFailure + 1, f.platform.cancels);
            assertEquals(1, f.platform.lifecycleCloses);
            assertEquals(bound ? 1 : 0, Collections.frequency(f.platform.closed, f.profile));
            for (String address : Arrays.asList(PHONE, OTHER)) {
                assertEquals("error", f.controller.snapshot(address).state);
                assertEquals("profile_unavailable", f.controller.snapshot(address).reason);
                assertFailure(f.controller.snapshot(address), "monitor_tick", "SecurityException");
            }

            int activityReads = f.platform.activityReads;
            f.clock.now = 200_000;
            f.platform.connected.add(OTHER);
            f.controller.tick(PHONE, false);
            f.controller.tick(OTHER, true);
            FakeProfile late = new FakeProfile();
            old.connected(16, late);
            old.disconnected(16);
            assertEquals(1, Collections.frequency(f.platform.closed, late));
            assertEquals(bound ? 1 : 0, Collections.frequency(f.platform.closed, f.profile));
            assertEquals(activityReads, f.platform.activityReads);
            assertEquals(1, f.platform.opens);
            assertEquals(0, f.profile.connects);
            for (String address : Arrays.asList(PHONE, OTHER)) {
                assertEquals("error", f.controller.snapshot(address).state);
                assertEquals("profile_unavailable", f.controller.snapshot(address).reason);
                assertFailure(f.controller.snapshot(address), "monitor_tick", "SecurityException");
            }

            f.controller.stopAfterUnexpectedFailure(failure);
            assertEquals(cancelsBeforeFailure + 1, f.platform.cancels);
            assertEquals(1, f.platform.lifecycleCloses);
            assertEquals(bound ? 1 : 0, Collections.frequency(f.platform.closed, f.profile));
            f.controller.stop();
            f.controller.stop();
            for (String address : Arrays.asList(PHONE, OTHER)) {
                assertEquals("disabled", f.controller.snapshot(address).state);
                assertEquals("stopped", f.controller.snapshot(address).reason);
                assertNoFailure(f.controller.snapshot(address));
            }
            old.disconnected(16);
            assertEquals(cancelsBeforeFailure + 1, f.platform.cancels);
            assertEquals(1, f.platform.lifecycleCloses);
            assertEquals(bound ? 1 : 0, Collections.frequency(f.platform.closed, f.profile));
            assertNoFailure(f.controller.snapshot(PHONE));
            assertEquals("disabled", f.controller.snapshot(PHONE).state);
        }
    }

    @Test public void aggregateBusyFromAnAcceptedRequestKeepsBothWorkersPendingWithoutRetrying() {
        Fixture f = new Fixture();
        f.ready();
        f.controller.tick(PHONE, false);
        f.platform.activity = "bluetooth_busy";
        for (long now : new long[]{1_000, 14_999}) {
            f.clock.now = now;
            f.controller.tick(PHONE, false);
            assertEquals("connecting", f.controller.snapshot(PHONE).state);
            assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
            f.controller.tick(OTHER, false);
            assertEquals("waiting", f.controller.snapshot(OTHER).state);
            assertEquals("profile_busy", f.controller.snapshot(OTHER).reason);
            assertEquals(1, f.profile.connects);
            assertEquals(1, f.platform.opens);
            assertFalse(f.platform.closed.contains(f.profile));
        }
    }

    @Test public void witnessedNativeFailureRetriesAtThreeSecondsAndResetsTheQueueGrace() {
        Fixture f = new Fixture(Collections.singletonList(PHONE));
        f.ready();
        f.controller.tick(PHONE, false);
        f.profile.state = 1;
        f.clock.now = 1_000;
        f.controller.tick(PHONE, false);
        assertEquals(PHONE, f.profile.lastStateAddress);
        assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);

        f.profile.state = 0;
        f.clock.now = 2_000;
        f.controller.tick(PHONE, false);
        assertEquals("connection_failed", f.controller.snapshot(PHONE).reason);
        assertEquals(1_000, f.controller.snapshot(PHONE).retryInMs);
        assertEquals(1, f.profile.connects);
        assertFalse(f.platform.closed.contains(f.profile));
        f.clock.now = 3_000;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.profile.connects);
        assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);

        // The second accepted request has not reported CONNECTING yet.
        // The previous request's witnessed transition must not shorten it.
        f.clock.now = 17_999;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.profile.connects);
        assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
        f.clock.now = 18_000;
        f.controller.tick(PHONE, false);
        assertEquals(3, f.profile.connects);
    }

    @Test public void initialDisconnectedStateAndMissedConnectingRetainTheFullGrace() {
        for (int interimState : new int[]{0, 2, 3}) {
            Fixture f = new Fixture(Collections.singletonList(PHONE));
            f.ready();
            f.controller.tick(PHONE, false);
            f.profile.state = interimState;
            f.clock.now = 2_000;
            f.controller.tick(PHONE, false);
            f.profile.state = 0;
            for (long now : new long[]{6_000, 8_000, 14_999}) {
                f.clock.now = now;
                f.controller.tick(PHONE, false);
                assertEquals(1, f.profile.connects);
                assertEquals("connecting", f.controller.snapshot(PHONE).state);
                assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
                assertNoFailure(f.controller.snapshot(PHONE));
            }
            f.clock.now = 15_000;
            f.controller.tick(PHONE, false);
            assertEquals(2, f.profile.connects);
        }
    }

    @Test public void pendingNativeStateFailureReleasesTheLeaseAndRetriesAfterBoundedDelay() {
        Fixture f = new Fixture();
        f.ready();
        f.controller.tick(PHONE, false);
        f.profile.stateFailure = new SecurityException("Private native failure for " + PHONE);
        f.clock.now = 4_000;
        f.controller.tick(OTHER, false);
        assertEquals("profile_unavailable", f.controller.snapshot(PHONE).reason);
        assertFailure(f.controller.snapshot(PHONE), "profile_connection_state", "SecurityException");
        assertEquals("retry_backoff", f.controller.snapshot(OTHER).reason);
        assertEquals(3_000, f.controller.snapshot(PHONE).retryInMs);
        assertTrue(f.platform.closed.contains(f.profile));
        assertEquals(1, f.profile.connects);

        f.profile.stateFailure = null;
        f.clock.now = 6_999;
        f.controller.tick(PHONE, false);
        assertEquals(1, f.platform.opens);
        assertEquals(1, f.profile.connects);
        f.clock.now = 7_000;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.platform.opens);
        f.platform.ready(f.profile);
        f.controller.tick(PHONE, false);
        assertEquals("phone_turn", f.controller.snapshot(PHONE).reason);
        f.controller.tick(OTHER, false);
        assertEquals(2, f.profile.connects);
        assertEquals(OTHER, f.profile.lastAddress);
        assertFailure(f.controller.snapshot(PHONE), "profile_connection_state", "SecurityException");
        f.platform.connected.add(OTHER);
        f.controller.tick(OTHER, true);
        f.clock.now = 10_000;
        f.controller.tick(PHONE, false);
        assertEquals(3, f.profile.connects);
        assertEquals(PHONE, f.profile.lastAddress);
        assertNoFailure(f.controller.snapshot(PHONE));
    }

    @Test public void invalidPendingNativeStateNeverBecomesAConnectionOrIdleProfile() {
        for (int state : new int[]{-1, 4}) {
            Fixture f = new Fixture();
            f.ready();
            f.controller.tick(PHONE, false);
            f.profile.state = state;
            f.clock.now = 8_000;
            f.controller.tick(PHONE, false);
            assertEquals(1, f.profile.connects);
            assertEquals("profile_unavailable", f.controller.snapshot(PHONE).reason);
            assertFailure(f.controller.snapshot(PHONE), "profile_connection_state", "IllegalStateException");
            assertTrue(f.platform.closed.contains(f.profile));
        }
    }

    @Test public void revokedConsentAndAdapterStatePreventEveryPendingNativeProbe() {
        for (String guard : Arrays.asList("master_off", "shared_bluetooth_off", "vehicle_on",
                "power_unknown", "vehicle_on_only", "settings_changed", "settings_unavailable",
                "radio_off", "phone_not_paired")) {
            Fixture f = new Fixture();
            f.ready();
            f.controller.tick(PHONE, false);
            int reads = f.profile.stateReads;
            if ("radio_off".equals(guard)) f.platform.on = false;
            else if ("phone_not_paired".equals(guard)) f.platform.bonded.remove(PHONE);
            else f.gate.reason = guard;
            f.clock.now = 2_000;
            f.controller.tick(PHONE, false);
            assertEquals(guard, f.controller.snapshot(PHONE).reason);
            assertEquals(reads, f.profile.stateReads);
            assertEquals(1, f.profile.connects);
            assertTrue(f.platform.closed.contains(f.profile));
        }
    }

    @Test public void stopAfterAcceptanceClosesTheLeaseAndMakesLaterTicksInert() {
        Fixture f = new Fixture();
        f.ready();
        f.controller.tick(PHONE, false);
        int reads = f.profile.stateReads;
        int activityReads = f.platform.activityReads;
        f.controller.stop();
        assertTrue(f.platform.closed.contains(f.profile));
        assertEquals(1, f.platform.lifecycleCloses);
        f.clock.now = 100_000;
        f.controller.tick(PHONE, false);
        f.controller.tick(OTHER, false);
        assertEquals(reads, f.profile.stateReads);
        assertEquals(activityReads, f.platform.activityReads);
        assertEquals(1, f.profile.connects);
        assertEquals("stopped", f.controller.snapshot(PHONE).reason);
    }

    @Test public void consentChangingDuringActivityReadPreventsThePendingNativeProbe() {
        Fixture f = new Fixture();
        f.ready();
        f.controller.tick(PHONE, false);
        int reads = f.profile.stateReads;
        f.platform.duringActivity = () -> f.gate.reason = "master_off";
        f.clock.now = 2_000;
        f.controller.tick(OTHER, false);
        assertEquals("master_off", f.controller.snapshot(OTHER).reason);
        assertEquals(reads, f.profile.stateReads);
        assertEquals(1, f.profile.connects);
        assertTrue(f.platform.closed.contains(f.profile));
    }

    @Test public void discoveryPairingAndAnotherDeviceStillAbortAnAcceptedPendingRequest() {
        for (String activity : Arrays.asList("discovery_active", "pairing_active",
                "pairing_grace", "other_bluetooth_active")) {
            for (String address : Arrays.asList(PHONE, OTHER)) {
                Fixture f = new Fixture();
                f.ready();
                f.controller.tick(PHONE, false);
                int reads = f.profile.stateReads;
                f.platform.activity = activity;
                f.clock.now = 1_000;
                f.controller.tick(address, false);
                assertEquals("blocked", f.controller.snapshot(address).state);
                assertEquals(activity, f.controller.snapshot(address).reason);
                assertTrue(f.platform.closed.contains(f.profile));
                assertEquals(1, f.profile.connects);
                assertEquals(reads, f.profile.stateReads);
            }
        }
    }

    // ── M3 (18 Oct): per-phone exponential backoff after repeated timed-out attempts ──────────────

    /** One accepted attempt at {@code at} for a phone whose profile is bound and whose turn it is. */
    private static void attempt(Fixture f, String address, long at, int expectedConnects) {
        f.clock.now = at;
        f.controller.tick(address, false);
        assertEquals(expectedConnects, f.profile.connects);
        assertEquals(address, f.profile.lastAddress);
        assertEquals("attempt_accepted", f.controller.snapshot(address).reason);
    }

    @Test public void absentPhoneBacksOff15ThenDoublingTo120sAndResetsOnConnect() {
        assertEquals(2, BluetoothPhoneReconnectController.BACKOFF_AFTER_FAILURES);
        assertEquals(15_000, BluetoothPhoneReconnectController.BACKOFF_BASE_MS);
        assertEquals(120_000, BluetoothPhoneReconnectController.BACKOFF_CAP_MS);
        Fixture f = new Fixture();
        // OTHER is present and connects at once; PHONE is absent and every accepted attempt times out.
        f.platform.connected.add(OTHER);
        f.controller.tick(OTHER, true);
        f.ready();
        attempt(f, PHONE, 0, 1);
        // Failure 1: as today, the timeout is detected and the retry made in the same tick.
        f.clock.now = 15_000;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.profile.connects);
        assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
        assertEquals(3_000, f.controller.snapshot(PHONE).retryInMs);
        // Failure 2: this phone alone backs off 15 s.
        f.clock.now = 30_000;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.profile.connects);
        assertEquals("retry_backoff", f.controller.snapshot(PHONE).reason);
        assertEquals(15_000, f.controller.snapshot(PHONE).retryInMs);
        f.clock.now = 44_999;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.profile.connects);
        assertEquals("retry_backoff", f.controller.snapshot(PHONE).reason);
        // Failures 3..6: 30 s, 60 s, then the 120 s cap.
        long at = 45_000;
        int connects = 2;
        for (long backoff : new long[] {30_000, 60_000, 120_000, 120_000}) {
            attempt(f, PHONE, at, ++connects);
            f.clock.now = at + BluetoothPhoneReconnectController.CONNECTION_TIMEOUT_MS;
            f.controller.tick(PHONE, false);
            assertEquals(connects, f.profile.connects);
            assertEquals("retry_backoff", f.controller.snapshot(PHONE).reason);
            assertEquals(backoff, f.controller.snapshot(PHONE).retryInMs);
            at = f.clock.now + backoff;
        }
        assertEquals(6, f.profile.connects);
        assertEquals("connected", f.controller.snapshot(OTHER).state);
        // The phone comes back inside its 120 s backoff: the connection observation clears it ...
        f.platform.connected.add(PHONE);
        f.clock.now = at - 60_000;
        f.controller.tick(PHONE, true);
        assertEquals("connected", f.controller.snapshot(PHONE).state);
        assertEquals(0, f.controller.snapshot(PHONE).retryInMs);
        // ... and the next failure streak starts from scratch (both phones were connected, so the
        // shared profile was released and is bound again first).
        f.platform.connected.remove(PHONE);
        f.clock.now = at;
        f.controller.tick(PHONE, false);
        assertEquals("profile_loading", f.controller.snapshot(PHONE).reason);
        f.platform.ready(f.profile);
        attempt(f, PHONE, at, 7);
        f.clock.now = at + BluetoothPhoneReconnectController.CONNECTION_TIMEOUT_MS;
        f.controller.tick(PHONE, false);
        assertEquals(8, f.profile.connects);
        assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
        assertEquals(3_000, f.controller.snapshot(PHONE).retryInMs);
    }

    @Test public void presentPhoneGetsItsTurnWhileTheAbsentPhoneIsInBackoff() {
        Fixture f = new Fixture();
        // OTHER is temporarily unpaired, so PHONE takes two consecutive turns and times out twice.
        f.platform.bonded.remove(OTHER);
        f.controller.tick(OTHER, false);
        assertEquals("phone_not_paired", f.controller.snapshot(OTHER).reason);
        f.ready();
        attempt(f, PHONE, 0, 1);
        f.clock.now = 15_000;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.profile.connects);
        f.clock.now = 30_000;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.profile.connects);
        assertEquals("retry_backoff", f.controller.snapshot(PHONE).reason);
        assertEquals(15_000, f.controller.snapshot(PHONE).retryInMs);
        // OTHER is paired again and gets the turn at once instead of waiting behind PHONE's next 15 s attempt.
        f.platform.bonded.add(OTHER);
        f.controller.tick(OTHER, false);
        assertEquals(3, f.profile.connects);
        assertEquals(OTHER, f.profile.lastAddress);
        assertEquals("attempt_accepted", f.controller.snapshot(OTHER).reason);
        // PHONE's own ticks make no request while OTHER's attempt is pending ...
        f.clock.now = 33_000;
        f.controller.tick(PHONE, false);
        assertEquals(3, f.profile.connects);
        assertEquals("profile_busy", f.controller.snapshot(PHONE).reason);
        f.platform.connected.add(OTHER);
        f.clock.now = 34_000;
        f.controller.tick(OTHER, true);
        assertEquals("connected", f.controller.snapshot(OTHER).state);
        // ... nor after it connected, until PHONE's own backoff has elapsed.
        f.clock.now = 44_999;
        f.controller.tick(PHONE, false);
        assertEquals(3, f.profile.connects);
        assertEquals("retry_backoff", f.controller.snapshot(PHONE).reason);
        attempt(f, PHONE, 45_000, 4);
        assertEquals(3_000, f.controller.snapshot(PHONE).retryInMs);
    }

    @Test public void radioRestoreClearsLongBackoffForShortReturnVisits() {
        Fixture f = new Fixture();
        f.platform.connected.add(OTHER);
        f.controller.tick(OTHER, true);
        f.ready();
        attempt(f, PHONE, 0, 1);
        f.clock.now = 15_000;
        f.controller.tick(PHONE, false); // First timed-out attempt; second accepted.
        f.clock.now = 30_000;
        f.controller.tick(PHONE, false); // Second timeout starts the 15-second backoff.
        assertEquals("retry_backoff", f.controller.snapshot(PHONE).reason);
        assertEquals(15_000, f.controller.snapshot(PHONE).retryInMs);

        // A safe-zone radio shutdown is withdrawn when a door opens. Do not make a
        // returning phone wait out an old absence backoff before it can reconnect.
        f.controller.radioRestored();
        assertEquals(0, f.controller.snapshot(PHONE).retryInMs);
        assertEquals("waiting", f.controller.snapshot(PHONE).state);
        assertEquals("radio_restored", f.controller.snapshot(PHONE).reason);
        f.controller.tick(PHONE, false);
        assertEquals("profile_loading", f.controller.snapshot(PHONE).reason);
        f.platform.ready(f.profile);
        f.controller.tick(PHONE, false);
        assertEquals(3, f.profile.connects);
        assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
    }

    @Test public void radioRestoreGraceKeepsRetryingWhileThePhoneReconnects() {
        Fixture f = new Fixture();
        f.platform.connected.add(OTHER);
        f.controller.tick(OTHER, true);
        f.controller.radioRestored();
        f.ready();
        assertEquals(BluetoothPhoneReconnectController.RADIO_RESTORE_GRACE_MS, 45_000L);
        attempt(f, PHONE, 0, 1);
        f.clock.now = 15_000;
        f.controller.tick(PHONE, false);
        assertEquals(1, f.profile.connects);
        assertEquals(3_000, f.controller.snapshot(PHONE).retryInMs);
        attempt(f, PHONE, 18_000, 2);
        f.clock.now = 33_000;
        f.controller.tick(PHONE, false);
        assertEquals(2, f.profile.connects);
        assertEquals(3_000, f.controller.snapshot(PHONE).retryInMs);
        attempt(f, PHONE, 36_000, 3);
    }

    @Test public void rebondingAfterAnUnpairedSpellClearsTheBackoff() {
        Fixture f = new Fixture();
        f.platform.connected.add(OTHER);
        f.controller.tick(OTHER, true);
        f.ready();
        attempt(f, PHONE, 0, 1);
        f.clock.now = 15_000;
        f.controller.tick(PHONE, false);                     // failure 1, immediate retry (attempt 2)
        f.clock.now = 30_000;
        f.controller.tick(PHONE, false);                     // failure 2, 15 s backoff
        attempt(f, PHONE, 45_000, 3);
        f.clock.now = 60_000;
        f.controller.tick(PHONE, false);                     // failure 3, 30 s backoff
        assertEquals(30_000, f.controller.snapshot(PHONE).retryInMs);
        f.platform.bonded.remove(PHONE);
        f.clock.now = 61_000;
        f.controller.tick(PHONE, false);
        assertEquals("phone_not_paired", f.controller.snapshot(PHONE).reason);
        // Bonded again: the 30 s backoff collapses to the normal 3 s retry spacing.
        f.platform.bonded.add(PHONE);
        f.clock.now = 62_000;
        f.controller.tick(PHONE, false);
        assertEquals("retry_backoff", f.controller.snapshot(PHONE).reason);
        assertEquals(3_000, f.controller.snapshot(PHONE).retryInMs);
        f.clock.now = 65_000;
        f.controller.tick(PHONE, false);                     // the unpaired spell released the profile
        assertEquals("profile_loading", f.controller.snapshot(PHONE).reason);
        f.platform.ready(f.profile);
        attempt(f, PHONE, 65_000, 4);
    }

    @Test public void witnessedNativeFailuresAlsoFeedTheBackoffOfThatPhoneOnly() {
        Fixture f = new Fixture();
        f.platform.connected.add(OTHER);
        f.controller.tick(OTHER, true);
        f.ready();
        for (int round = 1; round <= 2; round++) {
            attempt(f, PHONE, round * 10_000L, round);
            f.profile.state = 1;
            f.clock.now += 1_000;
            f.controller.tick(PHONE, false);
            f.profile.state = 0;
            f.clock.now += 1_000;
            f.controller.tick(PHONE, false);
            assertEquals("connection_failed", f.controller.snapshot(PHONE).reason);
        }
        assertEquals(15_000, f.controller.snapshot(PHONE).retryInMs);
        assertEquals("connected", f.controller.snapshot(OTHER).state);
        assertNoFailure(f.controller.snapshot(OTHER));
    }

    /** Byte-equivalent single-phone scheduling: timeouts retry at once with the plain 3 s spacing, forever. */
    @Test public void singlePhoneSchedulingIsUnchangedByTheBackoff() {
        Fixture f = new Fixture(Collections.singletonList(PHONE));
        f.ready();
        attempt(f, PHONE, 0, 1);
        for (int round = 1; round <= 8; round++) {
            long deadline = round * BluetoothPhoneReconnectController.CONNECTION_TIMEOUT_MS;
            f.clock.now = deadline - 1;
            f.controller.tick(PHONE, false);
            assertEquals(round, f.profile.connects);
            assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
            // At the deadline the timeout is detected and the next attempt made in the same tick, as today.
            f.clock.now = deadline;
            f.controller.tick(PHONE, false);
            assertEquals(round + 1, f.profile.connects);
            assertEquals("attempt_accepted", f.controller.snapshot(PHONE).reason);
            assertEquals(3_000, f.controller.snapshot(PHONE).retryInMs);
        }
        // Witnessed failures keep the exact 3 s retry too (compare witnessedNativeFailureRetriesAtThreeSeconds...).
        Fixture g = new Fixture(Collections.singletonList(PHONE));
        g.ready();
        for (int round = 1; round <= 4; round++) {
            attempt(g, PHONE, round * 10_000L, round);
            g.profile.state = 1;
            g.clock.now += 1_000;
            g.controller.tick(PHONE, false);
            g.profile.state = 0;
            g.clock.now += 1_000;
            g.controller.tick(PHONE, false);
            assertEquals("connection_failed", g.controller.snapshot(PHONE).reason);
            assertEquals(1_000, g.controller.snapshot(PHONE).retryInMs);
        }
    }


    private static void assertFailure(BluetoothPhoneReconnectController.Snapshot snapshot,
            String stage, String errorClass) {
        assertEquals(stage, snapshot.errorStage);
        assertEquals(errorClass, snapshot.errorClass);
        for (String diagnostic : Arrays.asList(snapshot.state, snapshot.reason,
                snapshot.errorStage, snapshot.errorClass)) {
            assertFalse(diagnostic.contains("Private native failure"));
            assertFalse(diagnostic.contains(PHONE));
            assertFalse(diagnostic.contains(OTHER));
        }
    }

    private static void assertNoFailure(BluetoothPhoneReconnectController.Snapshot snapshot) {
        assertEquals("", snapshot.errorStage);
        assertEquals("", snapshot.errorClass);
    }
}
