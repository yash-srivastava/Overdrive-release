package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.AutoServiceBridge;
import com.overdrive.app.byd.AutoServiceProtocol;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AutoServiceDebugApiHandlerTest {
    @Test public void diLink5RejectsLegacyAndReflectiveEndpointsBeforeDispatch() throws Exception {
        String[] paths = {
                "/api/debug/autoservice/get-int?area=1&cmd=2",
                "/api/debug/autoservice/get-buffer?area=1&cmd=2",
                "/api/debug/autoservice/sweep?from=1&to=2",
                "/api/debug/autoservice/known",
                "/api/debug/autoservice/remote-control?opcode=5&confirm=YES",
                "/api/debug/autoservice/probe-methods",
                "/api/debug/autoservice/sdk-remote-control?class=Setting&method=anything&arg=1&confirm=YES",
                "/api/debug/autoservice/sdk-getter?class=Setting&method=anything",
                "/api/debug/autoservice/probe-getters?prefix=get",
                "/api/debug/autoservice/list-setters",
                "/api/debug/autoservice/adas-slw"
        };
        FakeBackend backend = new FakeBackend("dilink5");
        for (String path : paths) {
            String response = invoke(path, backend);
            assertTrue(path, response.contains("\"supported\":false"));
        }
        assertEquals(0, backend.statusCalls);
        assertEquals(0, backend.typedCalls);
    }

    @Test public void typedIntegerUsesTransactionFiveAndPreservesZero() throws Exception {
        FakeBackend backend = new FakeBackend("dilink5");
        backend.reply = new AutoServiceBridge.DiLink5Read(0, 0);

        String response = invoke(
                "/api/debug/autoservice/typed-int?device=1013&fid=-1807745016&min=0&max=400",
                backend);

        assertEquals(5, backend.transaction);
        assertEquals(1013, backend.device);
        assertEquals(-1807745016, backend.featureId);
        assertTrue(response.contains("\"status\":0"));
        assertTrue(response.contains("\"value\":0"));
        assertTrue(response.contains("\"sentinel\":false"));
        assertTrue(response.contains("\"readable\":true"));
        assertTrue(response.contains("\"plausible\":true"));
    }

    @Test public void typedIntegerKeepsSignedRawValueSeparateFromPlausibility() throws Exception {
        FakeBackend backend = new FakeBackend("dilink5");
        backend.reply = new AutoServiceBridge.DiLink5Read(0, -42);

        String response = invoke(
                "/api/debug/autoservice/typed-int?device=9&fid=8&min=0&max=100",
                backend);

        assertTrue(response.contains("\"value\":-42"));
        assertTrue(response.contains("\"sentinel\":false"));
        assertTrue(response.contains("\"readable\":true"));
        assertTrue(response.contains("\"plausible\":false"));
    }

    @Test public void typedIntegerReportsSentinelWithoutConvertingRawBits() throws Exception {
        FakeBackend backend = new FakeBackend("dilink5");
        backend.reply = new AutoServiceBridge.DiLink5Read(0, -10011);

        String response = invoke("/api/debug/autoservice/typed-int?device=1&fid=2", backend);

        assertTrue(response.contains("\"valueBits\":-10011"));
        assertTrue(response.contains("\"sentinel\":true"));
        assertTrue(response.contains("\"value\":null"));
        assertTrue(response.contains("\"readable\":false"));
    }

    @Test public void typedFloatUsesTransactionSevenAndRejectsNonFiniteSentinel() throws Exception {
        FakeBackend backend = new FakeBackend("dilink5");
        backend.reply = new AutoServiceBridge.DiLink5Read(0, Float.floatToRawIntBits(Float.NaN));

        String response = invoke("/api/debug/autoservice/typed-float?device=5&fid=6", backend);

        assertEquals(7, backend.transaction);
        assertTrue(response.contains("\"sentinel\":true"));
        assertTrue(response.contains("\"value\":null"));
        assertTrue(response.contains("\"readable\":false"));
    }

    @Test public void typedCoordinatesRejectValuesOutsideTheThirtyTwoBitDomain() throws Exception {
        for (String value : new String[] {
                "2147483648", "-2147483649", "0x100000000"
        }) {
            FakeBackend backend = new FakeBackend("dilink5");
            String response = invoke(
                    "/api/debug/autoservice/typed-int?device=" + value + "&fid=1",
                    backend);
            assertTrue(value, response.contains("Need valid 'device' and 'fid' parameters"));
            assertEquals(value, 0, backend.typedCalls);
        }
        FakeBackend bits = new FakeBackend("dilink5");
        bits.reply = new AutoServiceBridge.DiLink5Read(0, 1);
        invoke("/api/debug/autoservice/typed-int?device=1&fid=0xffffffff", bits);
        assertEquals(-1, bits.featureId);
    }

    @Test public void malformedTransportReplyFailsClosed() throws Exception {
        FakeBackend backend = new FakeBackend("dilink5");
        backend.reply = null;

        String response = invoke("/api/debug/autoservice/typed-int?device=1&fid=2", backend);

        assertTrue(response.contains("\"transportReply\":false"));
        assertTrue(response.contains("\"readable\":false"));
    }

    @Test public void statusSeparatesConfiguredTransportConnectionAndReadability() throws Exception {
        FakeBackend backend = new FakeBackend("dilink5");
        backend.status = new AutoServiceBridge.DiagnosticStatus(
                "dilink5", "generation", "binder", false, false, null);

        String response = invoke("/api/debug/autoservice/status", backend);

        assertEquals(1, backend.statusCalls);
        assertTrue(response.contains("\"configuredTransport\":\"binder\""));
        assertTrue(response.contains("\"connected\":false"));
        assertTrue(response.contains("\"dispatchAvailable\":false"));
        assertTrue(response.contains("\"readable\":null"));
        assertFalse(response.contains("\"connected\":true"));
    }

    @Test public void missingCommittedModeRejectsBeforeBackendStatusOrRead() throws Exception {
        FakeBackend backend = new FakeBackend(null);
        String response = invoke("/api/debug/autoservice/status", backend);
        assertTrue(response.contains("active vehicle mode is unavailable"));
        assertEquals(0, backend.statusCalls);
        assertEquals(0, backend.typedCalls);
    }

    @Test public void modeTransitionRejectsBeforeStatusTypedOrLegacyDispatch() throws Exception {
        String[] paths = {
                "/api/debug/autoservice/status",
                "/api/debug/autoservice/typed-int?device=1&fid=2",
                "/api/debug/autoservice/get-int?area=1&cmd=2"
        };
        for (String path : paths) {
            FakeBackend backend = new FakeBackend(
                    path.contains("get-int") ? "default" : "dilink5");
            backend.modeAfterAdmission = new AutoServiceProtocol.ModeSnapshot(
                    path.contains("get-int") ? "dilink5" : "default", "changed");

            String response = invoke(path, backend);

            assertTrue(response.contains("active vehicle mode changed during request"));
            assertEquals(0, backend.statusCalls);
            assertEquals(0, backend.typedCalls);
            assertEquals(0, backend.getIntCalls);
        }
    }

    @Test public void modeTransitionRejectsBeforeReflectiveOrCollectorAccess() throws Exception {
        String[] paths = {
                "/api/debug/autoservice/probe-methods",
                "/api/debug/autoservice/sdk-getter?class=Setting&method=getAnything",
                "/api/debug/autoservice/probe-getters?prefix=get",
                "/api/debug/autoservice/adas-slw"
        };
        for (String path : paths) {
            FakeBackend backend = new FakeBackend("default");
            backend.modeAfterAdmission = new AutoServiceProtocol.ModeSnapshot(
                    "dilink5", "changed");

            String response = invoke(path, backend);

            assertTrue(response.contains("active vehicle mode changed during request"));
            assertEquals(0, backend.operationCalls());
        }
    }

    @Test public void postRoutingTransitionRejectsBeforeEveryDirectAccess() throws Exception {
        String[] paths = {
                "/api/debug/autoservice/probe-methods",
                "/api/debug/autoservice/sdk-remote-control?class=Setting&method=anything&arg=1&confirm=YES",
                "/api/debug/autoservice/sdk-getter?class=Setting&method=getAnything",
                "/api/debug/autoservice/probe-getters?class=Setting&prefix=get",
                "/api/debug/autoservice/list-setters",
                "/api/debug/autoservice/adas-slw"
        };
        for (String path : paths) {
            FakeBackend backend = transitioningBackend(2);
            FakeDirectAccess directAccess = new FakeDirectAccess();

            String response = invoke(path, backend, directAccess);

            assertTrue(path, response.contains("active vehicle mode changed during request"));
            assertEquals(path, 0, directAccess.totalAccesses());
        }
    }

    @Test public void transitionImmediatelyBeforeInvocationPreventsEveryDirectCall()
            throws Exception {
        String[] reflectivePaths = {
                "/api/debug/autoservice/sdk-remote-control?class=Setting&method=anything&arg=1&confirm=YES",
                "/api/debug/autoservice/sdk-getter?class=Setting&method=getAnything",
                "/api/debug/autoservice/probe-getters?class=Setting&prefix=get"
        };
        for (String path : reflectivePaths) {
            FakeBackend backend = transitioningBackend(4);
            FakeDirectAccess directAccess = new FakeDirectAccess();

            String response = invoke(path, backend, directAccess);

            assertTrue(path, response.contains("active vehicle mode changed during request"));
            assertEquals(path, 1, directAccess.collectorAccesses);
            assertEquals(path, 1, directAccess.fieldAccesses);
            assertEquals(path, 0, directAccess.invocations);
        }

        FakeBackend backend = transitioningBackend(3);
        FakeDirectAccess directAccess = new FakeDirectAccess();
        String response = invoke("/api/debug/autoservice/adas-slw", backend, directAccess);
        assertTrue(response.contains("active vehicle mode changed during request"));
        assertEquals(1, directAccess.collectorAccesses);
        assertEquals(0, directAccess.speedLimitInvocations);
    }

    private static FakeBackend transitioningBackend(int lastCurrentCall) {
        FakeBackend backend = new FakeBackend("default");
        backend.modeAfterAdmission = new AutoServiceProtocol.ModeSnapshot("dilink5", "changed");
        backend.modeChangeAfterCall = lastCurrentCall;
        return backend;
    }

    @Test public void legacyStatusPreservesInterfaceTokenForEveryOlderMode() throws Exception {
        for (String mode : new String[] {"default", "dilink4", "camera2"}) {
            FakeBackend backend = new FakeBackend(mode);
            backend.status = new AutoServiceBridge.DiagnosticStatus(
                    mode, "generation", "binder", true, true, null);

            String response = invoke("/api/debug/autoservice/status", backend);

            assertTrue(mode, response.contains(
                    "\"interfaceToken\":\"android.gui.BYDAutoServer\""));
            assertTrue(mode, response.contains("\"connected\":true"));
            assertTrue(mode, response.contains("\"useShellCall\":false"));
        }
    }

    @Test public void legacySweepStopsAtIntegerLimitsWithoutWrapping() throws Exception {
        for (String mode : new String[] {"default", "dilink4", "camera2"}) {
            FakeBackend maximum = new FakeBackend(mode);
            maximum.maximumGetIntCalls = 1;
            String single = invoke(
                    "/api/debug/autoservice/sweep?from=2147483647&to=2147483647&cmd=0",
                    maximum);
            assertEquals(mode, Arrays.asList(Integer.MAX_VALUE), maximum.getIntAreas);
            assertTrue(mode, single.contains("\"totalProbed\":1"));

            FakeBackend reversed = new FakeBackend(mode);
            reversed.maximumGetIntCalls = 2;
            String pair = invoke(
                    "/api/debug/autoservice/sweep?from=2147483647&to=2147483646&cmd=0",
                    reversed);
            assertEquals(mode, Arrays.asList(Integer.MAX_VALUE - 1, Integer.MAX_VALUE),
                    reversed.getIntAreas);
            assertTrue(mode, pair.contains("\"from\":2147483646"));
            assertTrue(mode, pair.contains("\"to\":2147483647"));
            assertTrue(mode, pair.contains("\"totalProbed\":2"));

            FakeBackend minimum = new FakeBackend(mode);
            minimum.maximumGetIntCalls = 2;
            String lowPair = invoke(
                    "/api/debug/autoservice/sweep?from=-2147483648&to=-2147483647&cmd=0",
                    minimum);
            assertEquals(mode, Arrays.asList(Integer.MIN_VALUE, Integer.MIN_VALUE + 1),
                    minimum.getIntAreas);
            assertTrue(mode, lowPair.contains("\"totalProbed\":2"));
        }
    }

    @Test public void legacySweepRejectsOverflowSizedRangesBeforeReading() throws Exception {
        for (String mode : new String[] {"default", "dilink4", "camera2"}) {
            for (String range : new String[] {
                    "from=-2147483648&to=2147483647",
                    "from=2147483647&to=-2147483648",
                    "from=-2147483648&to=0",
                    "from=-1&to=2147483647"
            }) {
                FakeBackend backend = new FakeBackend(mode);
                backend.maximumGetIntCalls = 0;
                String response = invoke("/api/debug/autoservice/sweep?" + range, backend);
                assertTrue(mode + " " + range, response.contains("Range too large (max 4096)"));
                assertEquals(mode + " " + range, 0, backend.getIntCalls);
            }
        }
    }

    @Test public void legacySweepPreservesTheInclusiveRangeLimit() throws Exception {
        FakeBackend accepted = new FakeBackend("default");
        String response = invoke("/api/debug/autoservice/sweep?from=0&to=4096", accepted);
        assertEquals(4097, accepted.getIntCalls);
        assertEquals(Integer.valueOf(0), accepted.getIntAreas.get(0));
        assertEquals(Integer.valueOf(4096), accepted.getIntAreas.get(4096));
        assertTrue(response.contains("\"totalProbed\":4097"));

        FakeBackend rejected = new FakeBackend("default");
        rejected.maximumGetIntCalls = 0;
        String tooLarge = invoke("/api/debug/autoservice/sweep?from=0&to=4097", rejected);
        assertTrue(tooLarge.contains("Range too large (max 4096)"));
        assertEquals(0, rejected.getIntCalls);
    }

    @Test public void actualAuthenticationRejectionPreventsHandlerDispatch() throws Exception {
        FakeBackend backend = new FakeBackend("dilink5");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String path = "/api/debug/autoservice/typed-int?device=1&fid=2";

        boolean authenticated = AuthMiddleware.checkAuth(
                path,
                null,
                null,
                out,
                new InetSocketAddress("192.0.2.1", 12345),
                false,
                null,
                8080,
                "127.0.0.1:8080");
        if (authenticated) {
            AutoServiceDebugApiHandler.handle("GET", path, null, out, backend);
        }

        assertFalse(authenticated);
        assertTrue(out.toString(StandardCharsets.UTF_8.name()).contains("401 Unauthorized"));
        assertEquals(0, backend.modeCalls);
        assertEquals(0, backend.operationCalls());
    }

    @Test public void reflectiveRegistryDefaultsToDenyForCallerChosenNamesAndPrefixes() {
        assertFalse(DiLink5SdkDiagnosticRegistry.isReflectiveCallAllowed(
                DiLink5SdkDiagnosticRegistry.Operation.SDK_GETTER,
                "example.Device", "getAnything"));
        assertFalse(DiLink5SdkDiagnosticRegistry.isReflectiveCallAllowed(
                DiLink5SdkDiagnosticRegistry.Operation.SDK_DISCOVERY,
                "example.Device", "get"));
    }

    @Test public void httpAuthenticationStillPrecedesAutoserviceDispatch() throws Exception {
        String server = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        int auth = server.indexOf("AuthMiddleware.checkAuth(path");
        int routing = server.indexOf("routeToHandlers(method", auth);
        int diagnostics = server.indexOf("AutoServiceDebugApiHandler.handle");
        assertTrue(auth >= 0);
        assertTrue(routing > auth);
        assertTrue(diagnostics > routing);
    }

    private static String invoke(String path, FakeBackend backend) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AutoServiceDebugApiHandler.handle("GET", path, null, out, backend);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String invoke(
            String path,
            FakeBackend backend,
            AutoServiceDebugApiHandler.DirectAccess directAccess) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AutoServiceDebugApiHandler.handle("GET", path, null, out, backend, directAccess);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String readRepositoryFile(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path direct = current.resolve(relative);
            if (Files.exists(direct)) return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
            Path fromModule = current.resolve(relative.startsWith("app/")
                    ? relative.substring("app/".length()) : relative);
            if (Files.exists(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relative);
    }

    private static final class FakeBackend implements AutoServiceDebugApiHandler.Backend {
        final AutoServiceProtocol.ModeSnapshot mode;
        AutoServiceProtocol.ModeSnapshot modeAfterAdmission;
        int modeChangeAfterCall = 1;
        AutoServiceBridge.DiagnosticStatus status;
        AutoServiceBridge.DiLink5Read reply = new AutoServiceBridge.DiLink5Read(0, 1);
        int modeCalls;
        int statusCalls;
        int typedCalls;
        int getIntCalls;
        int maximumGetIntCalls = 4097;
        final List<Integer> getIntAreas = new ArrayList<>();
        int getBufferCalls;
        int remoteControlCalls;
        int connectionCalls;
        int transaction;
        int device;
        int featureId;

        FakeBackend(String mode) {
            this.mode = mode == null ? null : new AutoServiceProtocol.ModeSnapshot(mode, "generation");
            status = new AutoServiceBridge.DiagnosticStatus(
                    mode, "generation", "binder", true, true, null);
        }

        @Override public AutoServiceProtocol.ModeSnapshot currentMode() {
            modeCalls++;
            return modeCalls > modeChangeAfterCall && modeAfterAdmission != null
                    ? modeAfterAdmission : mode;
        }

        @Override public AutoServiceBridge.DiagnosticStatus status(
                AutoServiceProtocol.ModeSnapshot expected) {
            statusCalls++;
            assertEquals(mode, expected);
            return status;
        }

        @Override public AutoServiceBridge.DiLink5Read typedRead(
                AutoServiceProtocol.ModeSnapshot expected,
                int transaction,
                int device,
                int featureId) {
            typedCalls++;
            assertEquals(mode, expected);
            this.transaction = transaction;
            this.device = device;
            this.featureId = featureId;
            return reply;
        }

        @Override public int getInt(
                AutoServiceProtocol.ModeSnapshot expected, int area, int command) {
            getIntCalls++;
            assertTrue("Unexpected or wrapped legacy sweep", getIntCalls <= maximumGetIntCalls);
            getIntAreas.add(area);
            assertEquals(mode, expected);
            return 0;
        }

        @Override public byte[] getBuffer(
                AutoServiceProtocol.ModeSnapshot expected, int area, int command) {
            getBufferCalls++;
            assertEquals(mode, expected);
            return new byte[] {1};
        }

        @Override public int remoteControl(
                AutoServiceProtocol.ModeSnapshot expected, int opcode) {
            remoteControlCalls++;
            assertEquals(mode, expected);
            return 0;
        }

        @Override public boolean isConnected(AutoServiceProtocol.ModeSnapshot expected) {
            connectionCalls++;
            assertEquals(mode, expected);
            return true;
        }

        @Override public boolean isUsingShellCall(AutoServiceProtocol.ModeSnapshot expected) {
            connectionCalls++;
            assertEquals(mode, expected);
            return false;
        }

        int operationCalls() {
            return statusCalls + typedCalls + getIntCalls + getBufferCalls
                    + remoteControlCalls + connectionCalls;
        }
    }

    private static final class FakeDirectAccess
            implements AutoServiceDebugApiHandler.DirectAccess {
        final DirectFixture fixture = new DirectFixture();
        int collectorAccesses;
        int classAccesses;
        int fieldAccesses;
        int multimediaAccesses;
        int invocations;
        int speedLimitInvocations;

        @Override public Object collector() {
            collectorAccesses++;
            return fixture;
        }

        @Override public Class<?> findClass(String name) {
            classAccesses++;
            return DirectFixture.class;
        }

        @Override public Object fieldValue(java.lang.reflect.Field field, Object target)
                throws Exception {
            fieldAccesses++;
            return field.get(target);
        }

        @Override public Object multimediaDevice(Object collector) {
            multimediaAccesses++;
            return null;
        }

        @Override public Object invoke(
                java.lang.reflect.Method method, Object target, Object... args) throws Exception {
            invocations++;
            return method.invoke(target, args);
        }

        @Override public Object speedLimitWarning(Object collector) {
            speedLimitInvocations++;
            return "{}";
        }

        int totalAccesses() {
            return collectorAccesses + classAccesses + fieldAccesses + multimediaAccesses
                    + invocations + speedLimitInvocations;
        }
    }

    private static final class DirectFixture {
        Object settingDevice = new DirectDevice();
    }

    private static final class DirectDevice {
        public int anything(int value) {
            return value;
        }

        public int getAnything() {
            return 1;
        }
    }
}
