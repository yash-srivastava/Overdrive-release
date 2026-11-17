package com.overdrive.app.byd;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Cross-process and routing wiring; behavioral sequence/ticket tests cover the race policy. */
public class RemoteKeyActuationWiringTest {
    @Test public void registrationIsOptInSoAnOldDaemonSeesOneFinalReply() throws Exception {
        String service = source("services/VehicleActuatorService.java");
        String submit = method(service, "private boolean submitDiLink5Bridge(");
        assertTrue(submit.contains("createDiLink5Cancellation("));
        assertTrue(submit.contains("VehicleActuatorBridge.DILINK5_FRAME_CANCELLATION_OPT_IN"));
        int negotiated = submit.indexOf("createDiLink5Cancellation(");
        int registered = submit.indexOf("if (cancellation != null)");
        int reply = submit.indexOf("DILINK5_CANCELLATION_REGISTERED, registration");
        assertTrue(negotiated >= 0 && registered > negotiated && reply > registered);
        assertTrue(service.contains("LocalActuationCancellation.registrationAllowed("));
        String bridge = source("byd/VehicleActuatorBridge.java");
        assertTrue(bridge.contains(
                "if (genericFrameCommand) intent.putExtra(DILINK5_FRAME_CANCELLATION_OPT_IN, true);"));
        assertTrue(bridge.contains("resultCode == DILINK5_CANCELLATION_REGISTERED"));
        assertTrue(bridge.contains("registerDiLink5CancellationOwner("));
    }

    @Test public void bothNewRoutingEntryPointsResetAttemptStateButCloudFollowupDoesNot()
            throws Exception {
        String router = source("byd/routing/VehicleCommandRouter.java");
        assertTrue(method(router, "public CommandResult execute(VehicleCommand cmd)")
                .contains("cmd.beginLocalAttempt();"));
        assertTrue(method(router, "public CommandResult executeSdkOnly(VehicleCommand cmd)")
                .contains("cmd.beginLocalAttempt();"));
        String cloud = method(router, "public CommandResult executeCloudOnly(VehicleCommand cmd)");
        assertFalse(cloud.contains("cmd.beginLocalAttempt();"));
        assertTrue(cloud.contains("stoppedLocalAttempt(cmd, 0L)"));
        assertTrue(method(router, "private SdkLeg invokeSdk(VehicleCommand cmd)")
                .contains("attempt.abort(stopped)"));
        assertTrue(method(router, "private CloudCallResult runCloudCall(final VehicleCommand cmd)")
                .contains("localAttempt.cloudAbort()"));
    }

    @Test public void bridgeCarriesTerminalRefusalSeparatelyFromTheAcceptedAndTypedOutcome()
            throws Exception {
        String bridge = source("byd/VehicleActuatorBridge.java");
        String service = source("services/VehicleActuatorService.java");
        assertTrue(bridge.contains("LocalActuationAbort.fromWire("));
        assertTrue(bridge.contains("DILINK5_RESULT_ACTUATION_ABORT"));
        assertTrue(service.contains("result.putString(VehicleActuatorBridge.DILINK5_RESULT_ACTUATION_ABORT,"));
        assertTrue(service.contains("VehicleActuatorBridge.withRemoteKeyRequestCancellation("));
        assertTrue(bridge.contains("noteDiLink5LockOutcome(result == null ? null"));
        assertTrue(service.contains("result.putString(VehicleActuatorBridge.DILINK5_RESULT_LOCK_OUTCOME"));
    }

    @Test public void uncertainGenericLaunchStopsBeforeAnyShellFallback() throws Exception {
        String bridge = source("byd/VehicleActuatorBridge.java");
        int uncertain = bridge.indexOf(
                "if (genericFrameCommand && serviceMayOwnRequest.get()) {",
                bridge.indexOf("if (started == null) {"));
        int shell = bridge.indexOf("startDiLink5ServiceAsShell(startContext, intent)", uncertain);
        assertTrue(uncertain >= 0 && shell > uncertain);
        String stop = bridge.substring(uncertain, shell);
        assertTrue(stop.contains("cancellation.cancel();"));
        assertTrue(stop.contains("LocalActuationAbort.Kind.REQUEST_EXPIRED"));
        assertTrue(stop.contains("return null;"));
        assertTrue(bridge.contains(
                "if (!isDefiniteServiceStartRejection(directStart)) serviceMayOwnRequest.set(true);"));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String source(String relative) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path path = current.resolve("app/src/main/java/com/overdrive/app").resolve(relative);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IllegalStateException("source unavailable: " + relative);
    }
}
