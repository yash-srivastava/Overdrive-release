package com.overdrive.app.byd.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class NativeVehicleWriteBoundaryTest {
    private static final int TRUSTED_UID = 10123;

    @Test
    public void wrongCallerTokenAndTruncationDispatchNothing() {
        AtomicInteger calls = new AtomicInteger();
        NativeVehicleWriteBoundary boundary = boundary(calls,
                NativeVehicleWriteBoundary.TransportReply.handled(0));

        assertEquals(NativeVehicleWriteBoundary.Kind.CALLER_REJECTED,
                boundary.handle(request(999, true, 1001, 1125122056, 1)).kind);
        assertEquals(NativeVehicleWriteBoundary.Kind.TOKEN_REJECTED,
                boundary.handle(request(TRUSTED_UID, false, 1001, 1125122056, 1)).kind);
        assertEquals(NativeVehicleWriteBoundary.Kind.TRUNCATED,
                boundary.handle(request(TRUSTED_UID, true, 1001, 1125122056)).kind);
        FakeRequest missingAuthority = request(TRUSTED_UID, true, 1001, 1125122056, 1);
        missingAuthority.authority = null;
        assertEquals(NativeVehicleWriteBoundary.Kind.TRUNCATED,
                boundary.handle(missingAuthority).kind);
        assertEquals(0, calls.get());
    }

    @Test
    public void unknownTupleAndOtherDevice1004WritesDispatchNothing() {
        AtomicInteger calls = new AtomicInteger();
        NativeVehicleWriteBoundary boundary = boundary(calls,
                NativeVehicleWriteBoundary.TransportReply.handled(0));

        assertEquals(NativeVehicleWriteBoundary.Kind.UNKNOWN_ACTION,
                boundary.handle(request(TRUSTED_UID, true, 1001, 1125122056, 7)).kind);
        assertEquals(NativeVehicleWriteBoundary.Kind.UNKNOWN_ACTION,
                boundary.handle(request(TRUSTED_UID, true, 1004, 871366667, 1)).kind);
        assertEquals(0, calls.get());
    }

    @Test
    public void staleGenerationOrDeadlineDispatchesNothing() {
        AtomicInteger calls = new AtomicInteger();
        NativeVehicleWriteBoundary boundary = boundary(calls,
                NativeVehicleWriteBoundary.TransportReply.handled(0));
        FakeRequest request = valid();
        request.authority.current.set(false);
        NativeVehicleWriteBoundary.Result result = boundary.handle(request);
        assertEquals(NativeVehicleWriteBoundary.Kind.STALE_REQUEST, result.kind);
        assertEquals(0, calls.get());
    }

    @Test
    public void dispatcherReceivesRegistryActionRatherThanRawIntegers() {
        AtomicReference<NativeVehicleAction> dispatched = new AtomicReference<>();
        NativeVehicleWriteBoundary boundary = new NativeVehicleWriteBoundary(
                () -> TRUSTED_UID,
                (action, authority, cancellation) -> {
                    dispatched.set(action);
                    return NativeVehicleWriteBoundary.TransportReply.handled(0);
                });

        NativeVehicleWriteBoundary.Result result = boundary.handle(
                request(TRUSTED_UID, true, 1001, 1125122056, 4));
        assertEquals(NativeVehicleWriteBoundary.Kind.ACCEPTED, result.kind);
        assertEquals(NativeVehicleAction.SUNROOF_STOP, dispatched.get());
    }

    @Test
    public void rawZeroAndPositiveAreAcceptedButNeverObserved() {
        NativeVehicleWriteBoundary.Result zero = boundary(new AtomicInteger(),
                NativeVehicleWriteBoundary.TransportReply.handled(0)).handle(valid());
        NativeVehicleWriteBoundary.Result positive = boundary(new AtomicInteger(),
                NativeVehicleWriteBoundary.TransportReply.handled(1, 77)).handle(valid());

        assertEquals(NativeVehicleWriteBoundary.Kind.ACCEPTED, zero.kind);
        assertTrue(zero.hasStatus);
        assertEquals(0, zero.status);
        assertFalse(zero.hasOptionalWord);
        assertFalse(zero.observed());
        assertEquals(NativeVehicleWriteBoundary.Kind.ACCEPTED, positive.kind);
        assertEquals(1, positive.status);
        assertTrue(positive.hasOptionalWord);
        assertEquals(77, positive.optionalWord);
        assertFalse(positive.observed());
    }

    @Test
    public void negativeStatusIsDistinctRejection() {
        NativeVehicleWriteBoundary.Result result = boundary(new AtomicInteger(),
                NativeVehicleWriteBoundary.TransportReply.handled(-9)).handle(valid());
        assertEquals(NativeVehicleWriteBoundary.Kind.REJECTED, result.kind);
        assertTrue(result.hasStatus);
        assertEquals(-9, result.status);
        assertFalse(result.accepted());
    }

    @Test
    public void everyUncertainTransportShapeIsDistinctAndNeverRetried() {
        NativeVehicleWriteBoundary.Kind[] kinds = {
                NativeVehicleWriteBoundary.Kind.TRANSACT_FALSE,
                NativeVehicleWriteBoundary.Kind.MISSING_STATUS,
                NativeVehicleWriteBoundary.Kind.DEAD_SERVICE,
                NativeVehicleWriteBoundary.Kind.TRANSPORT_ERROR
        };
        for (NativeVehicleWriteBoundary.Kind kind : kinds) {
            AtomicInteger calls = new AtomicInteger();
            NativeVehicleWriteBoundary.Result result = boundary(calls,
                    NativeVehicleWriteBoundary.TransportReply.failed(kind)).handle(valid());
            assertEquals(kind, result.kind);
            assertEquals(1, calls.get());
            assertFalse(result.accepted());
        }
    }

    @Test
    public void unresolvedInstalledUidFailsClosed() {
        AtomicInteger calls = new AtomicInteger();
        NativeVehicleWriteBoundary boundary = new NativeVehicleWriteBoundary(
                () -> -1,
                (action, authority, cancellation) -> {
                    calls.incrementAndGet();
                    return NativeVehicleWriteBoundary.TransportReply.handled(0);
                });
        assertEquals(NativeVehicleWriteBoundary.Kind.CALLER_REJECTED,
                boundary.handle(valid()).kind);
        assertEquals(0, calls.get());
    }

    @Test
    public void earlyAndPostAdmissionCancellationDispatchNothing() {
        FakeRequest early = valid();
        AtomicInteger earlyDispatches = new AtomicInteger();
        NativeVehicleWriteBoundary earlyBoundary = boundary(earlyDispatches,
                NativeVehicleWriteBoundary.TransportReply.handled(0));
        assertTrue(earlyBoundary.cancel(cancel(early)));
        assertEquals(NativeVehicleWriteBoundary.Kind.CANCELLED,
                earlyBoundary.handle(early).kind);
        assertEquals(0, earlyDispatches.get());

        FakeRequest admitted = valid();
        AtomicInteger transactions = new AtomicInteger();
        AtomicReference<NativeVehicleWriteBoundary> holder = new AtomicReference<>();
        NativeVehicleWriteBoundary boundary = new NativeVehicleWriteBoundary(
                () -> TRUSTED_UID,
                (action, authority, cancellation) -> {
                    assertTrue(holder.get().cancel(cancel(admitted)));
                    return NativeVehicleWriteBoundary.dispatchAtFinalBoundary(
                            authority, cancellation, () -> true,
                            () -> {
                                transactions.incrementAndGet();
                                return NativeVehicleWriteBoundary.TransportReply.handled(0);
                            });
                });
        holder.set(boundary);
        assertEquals(NativeVehicleWriteBoundary.Kind.CANCELLED,
                boundary.handle(admitted).kind);
        assertEquals(0, transactions.get());
    }

    @Test
    public void cancellationRequiresTrustedCallerTokenAndMatchingOwner() {
        FakeRequest admitted = valid();
        AtomicReference<NativeVehicleWriteBoundary> holder = new AtomicReference<>();
        AtomicInteger transactions = new AtomicInteger();
        NativeVehicleWriteBoundary boundary = new NativeVehicleWriteBoundary(
                () -> TRUSTED_UID,
                (action, authority, cancellation) -> {
                    assertFalse(holder.get().cancel(new FakeCancellation(
                            999, true, admitted.authority.requestId,
                            admitted.authority.owner)));
                    assertFalse(holder.get().cancel(new FakeCancellation(
                            TRUSTED_UID, false, admitted.authority.requestId,
                            admitted.authority.owner)));
                    assertFalse(holder.get().cancel(new FakeCancellation(
                            TRUSTED_UID, true, admitted.authority.requestId, new Object())));
                    return NativeVehicleWriteBoundary.dispatchAtFinalBoundary(
                            authority, cancellation, () -> true,
                            () -> {
                                transactions.incrementAndGet();
                                return NativeVehicleWriteBoundary.TransportReply.handled(0);
                            });
                });
        holder.set(boundary);
        assertEquals(NativeVehicleWriteBoundary.Kind.ACCEPTED,
                boundary.handle(admitted).kind);
        assertEquals(1, transactions.get());
    }

    @Test
    public void daemonStopGenerationCancelsTailgateOpenBeforeAndDuringDispatch() {
        AtomicInteger transactions = new AtomicInteger();
        NativeVehicleWriteBoundary before = boundary(transactions,
                NativeVehicleWriteBoundary.TransportReply.handled(0));
        FakeRequest queued = request(TRUSTED_UID, true,
                NativeVehicleAction.TAILGATE_OPEN.device,
                NativeVehicleAction.TAILGATE_OPEN.featureId,
                NativeVehicleAction.TAILGATE_OPEN.value);
        queued.authority.tailgateStopGeneration = before.captureTailgateStopGeneration();
        before.cancelTailgateOpen();
        assertEquals(NativeVehicleWriteBoundary.Kind.CANCELLED, before.handle(queued).kind);
        assertEquals(0, transactions.get());

        AtomicReference<NativeVehicleWriteBoundary> holder = new AtomicReference<>();
        FakeRequest waiting = request(TRUSTED_UID, true,
                NativeVehicleAction.TAILGATE_OPEN.device,
                NativeVehicleAction.TAILGATE_OPEN.featureId,
                NativeVehicleAction.TAILGATE_OPEN.value);
        NativeVehicleWriteBoundary during = new NativeVehicleWriteBoundary(
                () -> TRUSTED_UID,
                (action, authority, cancellation) -> {
                    holder.get().cancelTailgateOpen();
                    return NativeVehicleWriteBoundary.dispatchAtFinalBoundary(
                            authority, cancellation, () -> true,
                            () -> {
                                transactions.incrementAndGet();
                                return NativeVehicleWriteBoundary.TransportReply.handled(0);
                            });
                });
        holder.set(during);
        waiting.authority.tailgateStopGeneration = during.captureTailgateStopGeneration();
        assertEquals(NativeVehicleWriteBoundary.Kind.CANCELLED, during.handle(waiting).kind);
        assertEquals(0, transactions.get());
    }

    @Test
    public void stopAfterFinalDispatchDoesNotRewriteAcceptedOutcome() {
        AtomicReference<NativeVehicleWriteBoundary> holder = new AtomicReference<>();
        AtomicInteger transactions = new AtomicInteger();
        NativeVehicleWriteBoundary boundary = new NativeVehicleWriteBoundary(
                () -> TRUSTED_UID,
                (action, authority, cancellation) ->
                        NativeVehicleWriteBoundary.dispatchAtFinalBoundary(
                                authority, cancellation, () -> true,
                                () -> {
                                    transactions.incrementAndGet();
                                    return NativeVehicleWriteBoundary.TransportReply.handled(0);
                                }));
        holder.set(boundary);
        FakeRequest request = request(TRUSTED_UID, true,
                NativeVehicleAction.TAILGATE_OPEN.device,
                NativeVehicleAction.TAILGATE_OPEN.featureId,
                NativeVehicleAction.TAILGATE_OPEN.value);
        request.authority.tailgateStopGeneration = boundary.captureTailgateStopGeneration();
        assertEquals(NativeVehicleWriteBoundary.Kind.ACCEPTED, boundary.handle(request).kind);
        boundary.cancelTailgateOpen();
        assertEquals(1, transactions.get());
    }

    @Test
    public void finalAuthorityAndSafetyRevocationDispatchNothing() {
        AtomicInteger transactions = new AtomicInteger();
        FakeRequest stale = valid();
        NativeVehicleWriteBoundary staleBoundary = new NativeVehicleWriteBoundary(
                () -> TRUSTED_UID,
                (action, authority, cancellation) -> {
                    stale.authority.current.set(false);
                    return NativeVehicleWriteBoundary.dispatchAtFinalBoundary(
                            authority, cancellation, () -> true,
                            () -> {
                                transactions.incrementAndGet();
                                return NativeVehicleWriteBoundary.TransportReply.handled(0);
                            });
                });
        assertEquals(NativeVehicleWriteBoundary.Kind.STALE_REQUEST,
                staleBoundary.handle(stale).kind);

        for (NativeVehicleAction action : new NativeVehicleAction[] {
                NativeVehicleAction.TAILGATE_OPEN,
                NativeVehicleAction.TAILGATE_CLOSE,
                NativeVehicleAction.SUNROOF_OPEN
        }) {
            FakeRequest unsafe = request(TRUSTED_UID, true,
                    action.device, action.featureId, action.value);
            NativeVehicleWriteBoundary unsafeBoundary = new NativeVehicleWriteBoundary(
                    () -> TRUSTED_UID,
                    (approved, authority, cancellation) ->
                            NativeVehicleWriteBoundary.dispatchAtFinalBoundary(
                                    authority, cancellation, () -> false,
                                    () -> {
                                        transactions.incrementAndGet();
                                        return NativeVehicleWriteBoundary.TransportReply.handled(0);
                                    }));
            assertEquals(NativeVehicleWriteBoundary.Kind.REJECTED,
                    unsafeBoundary.handle(unsafe).kind);
        }
        assertEquals(0, transactions.get());
    }

    @Test
    public void serviceAuthoritySuffixCarriesExactStopGenerationAndRejectsTrailingData() {
        android.os.IBinder owner = (android.os.IBinder) java.lang.reflect.Proxy.newProxyInstance(
                android.os.IBinder.class.getClassLoader(),
                new Class<?>[] {android.os.IBinder.class},
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return true;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    return null;
                });
        NativeVehicleWriteBoundary.RequestAuthority decoded =
                NativeVehicleWriteService.decodeAuthoritySuffix(
                        new NativeVehicleWriteService.AuthoritySuffixReader() {
                            private int longIndex;
                            @Override public String readString() { return "mode-generation"; }
                            @Override public long readLong() {
                                return new long[] {8_000L, 17L, 23L}[longIndex++];
                            }
                            @Override public Object readOwner() { return owner; }
                            @Override public int remaining() { return 0; }
                        });
        assertEquals(17L, decoded.requestId());
        assertEquals(owner, decoded.owner());
        assertEquals(23L, decoded.tailgateStopGeneration());

        assertEquals(null, NativeVehicleWriteService.decodeAuthoritySuffix(
                new NativeVehicleWriteService.AuthoritySuffixReader() {
                    private int longIndex;
                    @Override public String readString() { return "mode-generation"; }
                    @Override public long readLong() {
                        return new long[] {8_000L, 17L, 23L}[longIndex++];
                    }
                    @Override public Object readOwner() { return owner; }
                    @Override public int remaining() { return 4; }
                }));
    }

    @Test
    public void stopBeforeCloudOwnerRegistrationRefusesStaleEpoch() {
        NativeVehicleWriteBoundary boundary = boundary(new AtomicInteger(),
                NativeVehicleWriteBoundary.TransportReply.handled(0));
        long captured = boundary.captureTailgateStopGeneration();
        AtomicInteger notified = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        NativeVehicleWriteBoundary.TailgateStopOwner owner =
                boundary.createTailgateStopOwner(new Object(),
                        notified::incrementAndGet, released::incrementAndGet);
        boundary.cancelTailgateOpen();

        assertFalse(owner.register(captured));
        boundary.cancelTailgateOpen();
        assertEquals(0, notified.get());
        assertEquals(1, released.get());
    }

    @Test
    public void stopSubscriptionRequiresInstalledUidTokenAndCompleteParcel() {
        android.os.IBinder callback = (android.os.IBinder) java.lang.reflect.Proxy.newProxyInstance(
                android.os.IBinder.class.getClassLoader(),
                new Class<?>[] {android.os.IBinder.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == boolean.class) return true;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        int code = NativeVehicleWriteService.TX_REGISTER_TAILGATE_STOP;
        assertEquals(17L, NativeVehicleWriteService.decodeStopRegistration(code, TRUSTED_UID,
                registrationReader(TRUSTED_UID, true, true, 17L, callback, 0)).generation);
        assertEquals(callback, NativeVehicleWriteService.decodeStopRegistration(
                NativeVehicleWriteService.TX_UNREGISTER_TAILGATE_STOP, TRUSTED_UID,
                registrationReader(TRUSTED_UID, true, false, -1L, callback, 0)).callback);
        assertEquals(null, NativeVehicleWriteService.decodeStopRegistration(code, -1,
                registrationReader(TRUSTED_UID, true, true, 17L, callback, 0)));
        assertEquals(null, NativeVehicleWriteService.decodeStopRegistration(code, TRUSTED_UID,
                registrationReader(999, true, true, 17L, callback, 0)));
        assertEquals(null, NativeVehicleWriteService.decodeStopRegistration(code, TRUSTED_UID,
                registrationReader(TRUSTED_UID, false, true, 17L, callback, 0)));
        assertEquals(null, NativeVehicleWriteService.decodeStopRegistration(code, TRUSTED_UID,
                registrationReader(TRUSTED_UID, true, false, 17L, callback, 0)));
        assertEquals(null, NativeVehicleWriteService.decodeStopRegistration(code, TRUSTED_UID,
                registrationReader(TRUSTED_UID, true, true, -1L, callback, 0)));
        assertEquals(null, NativeVehicleWriteService.decodeStopRegistration(code, TRUSTED_UID,
                registrationReader(TRUSTED_UID, true, true, 17L, new Object(), 0)));
        assertEquals(null, NativeVehicleWriteService.decodeStopRegistration(code, TRUSTED_UID,
                registrationReader(TRUSTED_UID, true, true, 17L, callback, 4)));
    }

    @Test
    public void registeredCloudOwnerIsCancelledAndReleasedOnce() {
        NativeVehicleWriteBoundary boundary = boundary(new AtomicInteger(),
                NativeVehicleWriteBoundary.TransportReply.handled(0));
        AtomicInteger notified = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        NativeVehicleWriteBoundary.TailgateStopOwner owner =
                boundary.createTailgateStopOwner(new Object(),
                        notified::incrementAndGet, released::incrementAndGet);
        assertTrue(owner.register(boundary.captureTailgateStopGeneration()));
        boundary.cancelTailgateOpen();
        owner.close();
        boundary.cancelTailgateOpen();
        assertEquals(1, notified.get());
        assertEquals(1, released.get());
    }

    @Test
    public void workerFinallyOrCallbackDeathRemovesOnlyItsOwnedListener() {
        NativeVehicleWriteBoundary boundary = boundary(new AtomicInteger(),
                NativeVehicleWriteBoundary.TransportReply.handled(0));
        Object callback = new Object();
        AtomicInteger oldNotified = new AtomicInteger();
        AtomicInteger newNotified = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        NativeVehicleWriteBoundary.TailgateStopOwner old =
                boundary.createTailgateStopOwner(callback,
                        oldNotified::incrementAndGet, released::incrementAndGet);
        assertTrue(old.register(boundary.captureTailgateStopGeneration()));
        old.close(); // The production worker-finally and Binder-death paths both call close().
        NativeVehicleWriteBoundary.TailgateStopOwner replacement =
                boundary.createTailgateStopOwner(callback, newNotified::incrementAndGet, null);
        assertTrue(replacement.register(boundary.captureTailgateStopGeneration()));
        old.close();
        boundary.cancelTailgateOpen();
        assertEquals(0, oldNotified.get());
        assertEquals(1, newNotified.get());
        assertEquals(1, released.get());
    }

    @Test
    public void callbackDeathBeforeRegistrationCannotLeaveAListener() {
        NativeVehicleWriteBoundary boundary = boundary(new AtomicInteger(),
                NativeVehicleWriteBoundary.TransportReply.handled(0));
        AtomicInteger notified = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        NativeVehicleWriteBoundary.TailgateStopOwner owner =
                boundary.createTailgateStopOwner(new Object(),
                        notified::incrementAndGet, released::incrementAndGet);
        owner.close();
        assertFalse(owner.register(boundary.captureTailgateStopGeneration()));
        boundary.cancelTailgateOpen();
        assertEquals(0, notified.get());
        assertEquals(1, released.get());
    }

    @Test
    public void subscribeVersusStopRaceNeverLeavesAnUncancelledOwner() throws Exception {
        for (int attempt = 0; attempt < 16; attempt++) {
            NativeVehicleWriteBoundary boundary = boundary(new AtomicInteger(),
                    NativeVehicleWriteBoundary.TransportReply.handled(0));
            long captured = boundary.captureTailgateStopGeneration();
            AtomicInteger notified = new AtomicInteger();
            AtomicInteger released = new AtomicInteger();
            AtomicBoolean registered = new AtomicBoolean();
            NativeVehicleWriteBoundary.TailgateStopOwner owner =
                    boundary.createTailgateStopOwner(new Object(),
                            notified::incrementAndGet, released::incrementAndGet);
            CountDownLatch start = new CountDownLatch(1);
            Thread subscribe = new Thread(() -> {
                await(start);
                registered.set(owner.register(captured));
            });
            Thread stop = new Thread(() -> {
                await(start);
                boundary.cancelTailgateOpen();
            });
            subscribe.start();
            stop.start();
            start.countDown();
            subscribe.join(2_000L);
            stop.join(2_000L);
            assertFalse(subscribe.isAlive());
            assertFalse(stop.isAlive());
            assertTrue(notified.get() == 0 || notified.get() == 1);
            if (registered.get()) assertEquals(1, notified.get());
            assertEquals(1, released.get());
            boundary.cancelTailgateOpen();
            assertEquals(1, released.get());
        }
    }

    @Test
    public void stopNotificationRunsOutsideTheMutationLock() throws Exception {
        NativeVehicleWriteBoundary boundary = boundary(new AtomicInteger(),
                NativeVehicleWriteBoundary.TransportReply.handled(0));
        AtomicBoolean observedUnlockedBoundary = new AtomicBoolean();
        AtomicReference<Thread> probe = new AtomicReference<>();
        NativeVehicleWriteBoundary.TailgateStopOwner owner =
                boundary.createTailgateStopOwner(new Object(), () -> {
                    CountDownLatch finished = new CountDownLatch(1);
                    Thread other = new Thread(() -> {
                        boundary.captureTailgateStopGeneration();
                        finished.countDown();
                    });
                    probe.set(other);
                    other.start();
                    try {
                        observedUnlockedBoundary.set(finished.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }, null);
        assertTrue(owner.register(boundary.captureTailgateStopGeneration()));
        boundary.cancelTailgateOpen();
        probe.get().join(2_000L);
        assertTrue(observedUnlockedBoundary.get());
        assertFalse(probe.get().isAlive());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static NativeVehicleWriteService.StopRegistrationReader registrationReader(
            int uid, boolean token, boolean hasGeneration, long generation, Object callback,
            int remaining) {
        return new NativeVehicleWriteService.StopRegistrationReader() {
            @Override public int callingUid() { return uid; }
            @Override public boolean enforceInterface(String descriptor) {
                return token && NativeVehicleWriteBoundary.DESCRIPTOR.equals(descriptor);
            }
            @Override public boolean hasGeneration() { return hasGeneration; }
            @Override public long readGeneration() { return generation; }
            @Override public Object readCallback() { return callback; }
            @Override public int remaining() { return remaining; }
        };
    }

    private static NativeVehicleWriteBoundary boundary(AtomicInteger calls,
            NativeVehicleWriteBoundary.TransportReply reply) {
        return new NativeVehicleWriteBoundary(
                () -> TRUSTED_UID,
                (action, authority, cancellation) -> {
                    calls.incrementAndGet();
                    return reply;
                });
    }

    private static FakeRequest valid() {
        return request(TRUSTED_UID, true, 1001, 1125122056, 1);
    }

    private static FakeRequest request(int uid, boolean token, int... values) {
        return new FakeRequest(uid, token, values);
    }

    private static FakeCancellation cancel(FakeRequest request) {
        return new FakeCancellation(TRUSTED_UID, true,
                request.authority.requestId, request.authority.owner);
    }

    private static final class FakeAuthority
            implements NativeVehicleWriteBoundary.RequestAuthority {
        final long requestId;
        final Object owner;
        final AtomicBoolean current = new AtomicBoolean(true);
        long tailgateStopGeneration;

        FakeAuthority(long requestId, Object owner) {
            this.requestId = requestId;
            this.owner = owner;
        }

        @Override public long requestId() { return requestId; }
        @Override public Object owner() { return owner; }
        @Override public boolean current() { return current.get(); }
        @Override public long tailgateStopGeneration() { return tailgateStopGeneration; }
    }

    private static final class FakeRequest implements NativeVehicleWriteBoundary.Request {
        private final int uid;
        private final boolean token;
        private final int[] values;
        FakeAuthority authority = new FakeAuthority(17L, new Object());
        private int index;

        FakeRequest(int uid, boolean token, int[] values) {
            this.uid = uid;
            this.token = token;
            this.values = values;
        }

        @Override public int callingUid() { return uid; }
        @Override public boolean enforceInterface(String descriptor) {
            return token && NativeVehicleWriteBoundary.DESCRIPTOR.equals(descriptor);
        }
        @Override public boolean hasInt() { return index < values.length; }
        @Override public int readInt() { return values[index++]; }
        @Override public NativeVehicleWriteBoundary.RequestAuthority readAuthority() {
            return authority;
        }
    }

    private static final class FakeCancellation
            implements NativeVehicleWriteBoundary.CancellationRequest {
        private final int uid;
        private final boolean token;
        private final long requestId;
        private final Object owner;

        FakeCancellation(int uid, boolean token, long requestId, Object owner) {
            this.uid = uid;
            this.token = token;
            this.requestId = requestId;
            this.owner = owner;
        }

        @Override public int callingUid() { return uid; }
        @Override public boolean enforceInterface(String descriptor) {
            return token && NativeVehicleWriteBoundary.DESCRIPTOR.equals(descriptor);
        }
        @Override public boolean complete() { return true; }
        @Override public long requestId() { return requestId; }
        @Override public Object owner() { return owner; }
    }
}
