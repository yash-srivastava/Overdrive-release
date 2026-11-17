package com.overdrive.app.byd.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.LocalActuationCancellation;
import com.overdrive.app.byd.cloud.crypto.EnvelopeCodec;
import com.overdrive.app.byd.dilink5.NativeVehicleWriteBoundary;
import com.overdrive.app.byd.routing.DrivingSafetyGuard;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.monitor.AccMonitor;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;

/** Runs the real client/session/envelope/Call registration path with no network or vehicle. */
public class CloudTailgateStopAuthorityTest {
    @Test
    public void stopDuringClientSessionMonitorWaitPreventsPhysicalPost() throws Exception {
        NativeVehicleWriteBoundary boundary = boundary();
        long captured = boundary.captureTailgateStopGeneration();
        FakeCalls calls = new FakeCalls();
        IdentityCodec codec = new IdentityCodec();
        BydCloudConfig config = config();
        BydCloudClient client = new BydCloudClient(
                config, codec, new BydCloudTransport(config, codec, calls));
        setField(client, "commandsVerified", true);
        setField(client, "session", new BydCloudSession("fixture", "sign", "encry"));
        CountDownLatch authorityEntered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                client.executeRemoteCommandWithCode("fixture-vin", "OPENTRUNK", null, true,
                        () -> {
                            authorityEntered.countDown();
                            return boundary.captureTailgateStopGeneration() == captured;
                        });
            } catch (Throwable result) {
                failure.set(result);
            }
        });

        synchronized (client) {
            worker.start();
            assertTrue(authorityEntered.await(2, TimeUnit.SECONDS));
            awaitBlocked(worker); // Actual synchronized ensureSession(), after the first gate.
            boundary.cancelTailgateOpen();
        }
        worker.join(2_000L);

        assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof IOException);
        assertEquals(0, calls.created.get());
        assertEquals(0, calls.executed.get());
    }

    @Test
    public void stopDuringEnvelopeEncodingCancelsRegisteredCallBeforeExecute() throws Exception {
        NativeVehicleWriteBoundary boundary = boundary();
        long captured = boundary.captureTailgateStopGeneration();
        FakeCalls calls = new FakeCalls();
        IdentityCodec codec = new IdentityCodec();
        codec.whileEncoding = boundary::cancelTailgateOpen;
        BydCloudTransport transport = new BydCloudTransport(config(), codec, calls);

        try {
            transport.postSecure("/control/remoteControl", new JSONObject(), 0L,
                    () -> boundary.captureTailgateStopGeneration() == captured);
            throw new AssertionError("A revoked request must not dispatch");
        } catch (IOException expected) {
            assertEquals(1, calls.created.get());
            assertEquals(0, calls.executed.get());
            assertTrue(calls.last.get().isCanceled());
        }
    }

    @Test
    public void stopRacingCallRegistrationIsRecheckedAfterPublication() throws Exception {
        NativeVehicleWriteBoundary boundary = boundary();
        long captured = boundary.captureTailgateStopGeneration();
        FakeCalls calls = new FakeCalls();
        calls.whileCreating = boundary::cancelTailgateOpen;
        BydCloudTransport transport = new BydCloudTransport(config(), new IdentityCodec(), calls);

        try {
            transport.postSecure("/control/remoteControl", new JSONObject(), 0L,
                    () -> boundary.captureTailgateStopGeneration() == captured);
            throw new AssertionError("A registration race must not dispatch");
        } catch (IOException expected) {
            assertEquals(0, calls.executed.get());
            assertTrue(calls.last.get().isCanceled());
        }
    }

    @Test
    public void ownedStopCancelsTheActualInFlightTransportCallAndCleansUp() throws Exception {
        NativeVehicleWriteBoundary boundary = boundary();
        long captured = boundary.captureTailgateStopGeneration();
        FakeCalls calls = new FakeCalls();
        calls.blockExecute = true;
        BydCloudTransport transport = new BydCloudTransport(config(), new IdentityCodec(), calls);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicInteger released = new AtomicInteger();
        Thread worker = new Thread(() -> {
            Thread ownedThread = Thread.currentThread();
            NativeVehicleWriteBoundary.TailgateStopOwner owner =
                    boundary.createTailgateStopOwner(new Object(), () -> {
                        transport.cancelCallForThread(ownedThread);
                        ownedThread.interrupt();
                    }, released::incrementAndGet);
            try {
                if (!owner.register(captured)) throw new IOException("Owner was revoked");
                transport.postSecure("/control/remoteControl", new JSONObject(), 0L,
                        () -> boundary.captureTailgateStopGeneration() == captured);
            } catch (Throwable result) {
                failure.set(result);
            } finally {
                owner.close(); // Same lifecycle used by the production worker's finally block.
            }
        });
        worker.start();
        assertTrue(calls.entered.await(2, TimeUnit.SECONDS));
        boundary.cancelTailgateOpen();
        worker.join(2_000L);

        assertFalse(worker.isAlive());
        assertNotNull(failure.get());
        assertTrue(failure.get() instanceof IOException);
        assertTrue(calls.last.get().isCanceled());
        assertEquals(1, calls.executed.get());
        assertEquals(1, released.get());
        boundary.cancelTailgateOpen();
        assertEquals(1, released.get());
    }

    @Test
    public void legacyOverloadDoesNotAcquireNativeAuthorityOrChangeItsResult() throws Exception {
        FakeCalls calls = new FakeCalls();
        IdentityCodec codec = new IdentityCodec();
        BydCloudConfig config = config();
        BydCloudClient client = new BydCloudClient(
                config, codec, new BydCloudTransport(config, codec, calls));
        setField(client, "commandsVerified", true);
        setField(client, "session", new BydCloudSession("fixture", "sign", "encry"));

        BydCloudClient.CloudCommandResult result =
                client.executeRemoteCommandWithCode("fixture-vin", "BATTERYHEAT", null, false);

        assertTrue(result.success);
        assertEquals("0", result.code);
        assertEquals(1, calls.executed.get());
        assertFalse(calls.last.get().isCanceled());
    }

    @Test
    public void appStopDuringActualSessionWaitKeepsTheOriginalManualOwner() throws Exception {
        NativeVehicleWriteBoundary daemon = boundary();
        long capturedDaemon = daemon.captureTailgateStopGeneration();
        AtomicLong localGeneration = new AtomicLong(7L);
        LocalActuationCancellation cancellation = new LocalActuationCancellation();
        CountDownLatch authorityEntered = new CountDownLatch(1);
        VehicleCommandRouter.TrunkOpenCommand command = manualCloudCommand(
                7L, capturedDaemon, cancellation, () -> localGeneration.get() == 7L,
                () -> {
                    authorityEntered.countDown();
                    return daemon.captureTailgateStopGeneration() == capturedDaemon;
                });
        FakeCalls calls = new FakeCalls();
        IdentityCodec codec = new IdentityCodec();
        BydCloudConfig config = config();
        BydCloudClient client = new BydCloudClient(
                config, codec, new BydCloudTransport(config, codec, calls));
        setField(client, "commandsVerified", true);
        setField(client, "session", new BydCloudSession("fixture", "sign", "encry"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                command.executeViaCloud(client, "fixture-vin");
            } catch (Throwable result) {
                failure.set(result);
            }
        });

        synchronized (client) {
            worker.start();
            assertTrue(authorityEntered.await(2, TimeUnit.SECONDS));
            awaitBlocked(worker); // After the router's command has entered the real client.
            localGeneration.incrementAndGet();
            cancellation.cancel();
        }
        worker.join(2_000L);

        assertFalse(worker.isAlive());
        assertEquals(capturedDaemon, daemon.captureTailgateStopGeneration());
        assertTrue(failure.get() instanceof IOException);
        assertEquals(0, calls.created.get());
        assertEquals(0, calls.executed.get());
    }

    @Test
    public void appStopDuringActualEnvelopeEncodingNeverAdoptsANewLocalEpoch() throws Exception {
        NativeVehicleWriteBoundary daemon = boundary();
        long capturedDaemon = daemon.captureTailgateStopGeneration();
        AtomicLong localGeneration = new AtomicLong(7L);
        LocalActuationCancellation cancellation = new LocalActuationCancellation();
        VehicleCommandRouter.TrunkOpenCommand command = manualCloudCommand(
                7L, capturedDaemon, cancellation, () -> localGeneration.get() == 7L,
                () -> daemon.captureTailgateStopGeneration() == capturedDaemon);
        FakeCalls calls = new FakeCalls();
        IdentityCodec codec = new IdentityCodec();
        codec.whileEncoding = () -> {
            localGeneration.incrementAndGet();
            cancellation.cancel();
        };
        BydCloudConfig config = config();
        BydCloudClient client = new BydCloudClient(
                config, codec, new BydCloudTransport(config, codec, calls));
        setField(client, "commandsVerified", true);
        setField(client, "session", new BydCloudSession("fixture", "sign", "encry"));

        try (ParkedFixture ignored = new ParkedFixture()) {
            assertFalse(DrivingSafetyGuard.isActionBlocked(DrivingSafetyGuard.GUARD_TRUNK));
            try {
                command.executeViaCloud(client, "fixture-vin");
                throw new AssertionError("A local STOP must revoke the original physical POST");
            } catch (IOException expected) {
                assertEquals(capturedDaemon, daemon.captureTailgateStopGeneration());
                assertEquals(1, calls.created.get());
                assertEquals(0, calls.executed.get());
                assertTrue(calls.last.get().isCanceled());
            }
        }
    }

    /** Uses the same owner factory as the production manual fallback, without Android ingress. */
    private static VehicleCommandRouter.TrunkOpenCommand manualCloudCommand(
            long localGeneration,
            long daemonGeneration,
            LocalActuationCancellation cancellation,
            BooleanSupplier localCurrent,
            BooleanSupplier daemonCurrent) throws Exception {
        Method factory = VehicleCommandRouter.class.getDeclaredMethod(
                "manualTailgateCloudCommand", long.class, long.class,
                LocalActuationCancellation.class, BooleanSupplier.class, BooleanSupplier.class);
        factory.setAccessible(true);
        return (VehicleCommandRouter.TrunkOpenCommand) factory.invoke(
                null, localGeneration, daemonGeneration, cancellation, localCurrent, daemonCurrent);
    }

    /** In-memory parked evidence only; never fires an ACC edge or changes persistent settings. */
    private static final class ParkedFixture implements AutoCloseable {
        private final Field[] fields;
        private final Object[] previous;

        ParkedFixture() throws Exception {
            fields = new Field[] {
                    DiLink5Platform.class.getDeclaredField("processMode"),
                    AccMonitor.class.getDeclaredField("accOn"),
                    AccMonitor.class.getDeclaredField("accOnAuthoritative")
            };
            previous = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) {
                fields[i].setAccessible(true);
                previous[i] = fields[i].get(null);
            }
            fields[0].set(null, "default");
            fields[1].set(null, false);
            fields[2].set(null, true);
        }

        @Override public void close() throws Exception {
            for (int i = fields.length - 1; i >= 0; i--) fields[i].set(null, previous[i]);
        }
    }

    private static NativeVehicleWriteBoundary boundary() {
        return new NativeVehicleWriteBoundary(() -> 10123,
                (action, authority, cancellation) ->
                        NativeVehicleWriteBoundary.TransportReply.handled(0));
    }

    private static void awaitBlocked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive() && thread.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.BLOCKED, thread.getState());
    }

    private static BydCloudConfig config() throws Exception {
        Constructor<BydCloudConfig> constructor = BydCloudConfig.class.getDeclaredConstructor(
                boolean.class, String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, String.class,
                boolean.class, String.class);
        constructor.setAccessible(true);
        return constructor.newInstance(true, "fixture", "", "", "", "",
                "fixture-vin", "NL", "en", "eu", false, "");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class IdentityCodec implements EnvelopeCodec {
        Runnable whileEncoding;

        @Override public void loadTables(InputStream source) {}
        @Override public boolean isReady() { return true; }
        @Override public String encodeEnvelope(String plaintext) {
            if (whileEncoding != null) whileEncoding.run();
            return plaintext;
        }
        @Override public String decodeEnvelope(String envelope) { return envelope; }
    }

    private static final class FakeCalls implements Call.Factory {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger executed = new AtomicInteger();
        final AtomicReference<FakeCall> last = new AtomicReference<>();
        final CountDownLatch entered = new CountDownLatch(1);
        Runnable whileCreating;
        boolean blockExecute;

        @Override public Call newCall(Request request) {
            FakeCall call = new FakeCall(request, this);
            last.set(call);
            created.incrementAndGet();
            if (whileCreating != null) whileCreating.run();
            return call;
        }
    }

    private static final class FakeCall implements Call {
        final Request request;
        final FakeCalls calls;
        final CountDownLatch cancelled = new CountDownLatch(1);
        volatile boolean started;
        volatile boolean isCancelled;

        FakeCall(Request request, FakeCalls calls) {
            this.request = request;
            this.calls = calls;
        }

        @Override public Request request() { return request; }

        @Override public Response execute() throws IOException {
            started = true;
            calls.executed.incrementAndGet();
            calls.entered.countDown();
            if (calls.blockExecute) {
                try {
                    if (!cancelled.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("Fixture request did not get cancelled");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Fixture request interrupted", interrupted);
                }
            }
            if (isCancelled) throw new IOException("Fixture request cancelled");
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(
                            "{\"response\":\"{\\\"code\\\":\\\"0\\\",\\\"respondData\\\":\\\"\\\"}\"}",
                            MediaType.parse("application/json")))
                    .build();
        }

        @Override public void enqueue(Callback callback) {
            throw new UnsupportedOperationException("Synchronous fixture only");
        }
        @Override public void cancel() {
            isCancelled = true;
            cancelled.countDown();
        }
        @Override public boolean isExecuted() { return started; }
        @Override public boolean isCanceled() { return isCancelled; }
        @Override public Timeout timeout() { return new Timeout(); }
        @Override public Call clone() { return new FakeCall(request, calls); }
    }
}
