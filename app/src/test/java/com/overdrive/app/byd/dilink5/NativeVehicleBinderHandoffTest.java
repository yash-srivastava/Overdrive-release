package com.overdrive.app.byd.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class NativeVehicleBinderHandoffTest {
    @Test
    public void failedServiceRegistrationStillServesLateAppRequestAndDispatch() {
        AtomicReference<NativeVehicleBinderHandoff.RequestListener> listener =
                new AtomicReference<>();
        AtomicReference<Object> appBinder = new AtomicReference<>();
        AtomicInteger dispatches = new AtomicInteger();
        NativeVehicleWriteBoundary daemonBoundary = new NativeVehicleWriteBoundary(
                () -> 10123,
                (action, authority, cancellation) -> {
                    dispatches.incrementAndGet();
                    return NativeVehicleWriteBoundary.TransportReply.handled(0);
                });
        NativeVehicleBinderHandoff handoff = new NativeVehicleBinderHandoff(
                requestListener -> {
                    listener.set(requestListener);
                    return true;
                },
                () -> false,
                callback -> {
                    appBinder.set(daemonBoundary);
                    return callback != null;
                });

        assertTrue(handoff.start());
        assertTrue(handoff.requestChannelReady());
        assertFalse(handoff.serviceRegistered());
        assertNotNull(listener.get());
        assertTrue(listener.get().onRequest(new Object()));
        assertEquals(daemonBoundary, appBinder.get());

        NativeVehicleWriteBoundary.Result result = daemonBoundary.handle(validRequest());
        assertEquals(NativeVehicleWriteBoundary.Kind.ACCEPTED, result.kind);
        assertEquals(1, dispatches.get());
    }

    @Test
    public void daemonStopDuringAppBinderWaitCancelsCarriedTailgateOpenGeneration() {
        AtomicReference<NativeVehicleBinderHandoff.RequestListener> listener =
                new AtomicReference<>();
        AtomicReference<Object> offered = new AtomicReference<>();
        AtomicInteger dispatches = new AtomicInteger();
        NativeVehicleWriteBoundary daemonBoundary = new NativeVehicleWriteBoundary(
                () -> 10123,
                (action, authority, cancellation) -> {
                    dispatches.incrementAndGet();
                    return NativeVehicleWriteBoundary.TransportReply.handled(0);
                });
        NativeVehicleBinderHandoff handoff = new NativeVehicleBinderHandoff(
                requestListener -> {
                    listener.set(requestListener);
                    return true;
                },
                () -> false,
                callback -> {
                    offered.set(daemonBoundary);
                    return true;
                });

        long capturedBeforeWait = daemonBoundary.captureTailgateStopGeneration();
        assertTrue(handoff.start());
        daemonBoundary.cancelTailgateOpen();
        assertTrue(listener.get().onRequest(new Object()));
        assertEquals(daemonBoundary, offered.get());

        NativeVehicleWriteBoundary.Result result = daemonBoundary.handle(
                tailgateOpenRequest(capturedBeforeWait));
        assertEquals(NativeVehicleWriteBoundary.Kind.CANCELLED, result.kind);
        assertEquals(0, dispatches.get());
    }

    @Test
    public void productionPathUsesPermissionGatedRequestCallback() throws Exception {
        String service = readRepo("app/src/main/java/com/overdrive/app/byd/dilink5/NativeVehicleWriteService.java");
        String client = readRepo("app/src/main/java/com/overdrive/app/byd/dilink5/NativeVehicleWriteClient.kt");
        String manifest = readRepo("app/src/main/AndroidManifest.xml");

        assertTrue(service.contains("registerRequestChannel(service.context, listener)"));
        assertTrue(service.contains("HANDOFF_PERMISSION, null"));
        assertTrue(service.contains("offer.writeStrongBinder(this)"));
        assertTrue(client.contains("putBinder(NativeVehicleWriteService.EXTRA_CALLBACK"));
        assertTrue(client.contains("fun acquireBinder(context: Context?, timeoutMs: Long)"));
        assertTrue(manifest.contains("android:protectionLevel=\"signature\""));
        assertTrue(manifest.contains(NativeVehicleWriteService.HANDOFF_PERMISSION));
    }

    private static String readRepo(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository file not found: " + relative);
    }

    private static NativeVehicleWriteBoundary.Request tailgateOpenRequest(long stopGeneration) {
        return new NativeVehicleWriteBoundary.Request() {
            private final int[] tuple = {
                    NativeVehicleAction.TAILGATE_OPEN.device,
                    NativeVehicleAction.TAILGATE_OPEN.featureId,
                    NativeVehicleAction.TAILGATE_OPEN.value};
            private int index;

            @Override public int callingUid() { return 10123; }
            @Override public boolean enforceInterface(String descriptor) {
                return NativeVehicleWriteBoundary.DESCRIPTOR.equals(descriptor);
            }
            @Override public boolean hasInt() { return index < tuple.length; }
            @Override public int readInt() { return tuple[index++]; }
            @Override public NativeVehicleWriteBoundary.RequestAuthority readAuthority() {
                return new NativeVehicleWriteBoundary.RequestAuthority() {
                    @Override public long requestId() { return 2L; }
                    @Override public Object owner() { return this; }
                    @Override public boolean current() { return true; }
                    @Override public long tailgateStopGeneration() { return stopGeneration; }
                };
            }
        };
    }

    private static NativeVehicleWriteBoundary.Request validRequest() {
        return new NativeVehicleWriteBoundary.Request() {
            private final int[] tuple = {1001, 1125122056, 4};
            private int index;

            @Override public int callingUid() { return 10123; }
            @Override public boolean enforceInterface(String descriptor) {
                return NativeVehicleWriteBoundary.DESCRIPTOR.equals(descriptor);
            }
            @Override public boolean hasInt() { return index < tuple.length; }
            @Override public int readInt() { return tuple[index++]; }
            @Override public NativeVehicleWriteBoundary.RequestAuthority readAuthority() {
                return new NativeVehicleWriteBoundary.RequestAuthority() {
                    @Override public long requestId() { return 1L; }
                    @Override public Object owner() { return this; }
                    @Override public boolean current() { return true; }
                };
            }
        };
    }
}
