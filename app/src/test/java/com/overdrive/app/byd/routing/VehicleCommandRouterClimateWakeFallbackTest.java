package com.overdrive.app.byd.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Field logs 2026-11-25 (two Seal U DM-i, plugged in): the climate wake's power-on frames were
 * delivered (rc=0), the car stayed off, and NOT_ON never reached OPENAIR, so remote pre-heat
 * could not work at all. NOT_ON now falls back to the cloud, but only for a car that still reads
 * exactly as off as it did before the wake. Style of {@link VehicleCommandRouterCloudOnlyEntrypointTest}.
 */
public class VehicleCommandRouterClimateWakeFallbackTest {
    private static final String WAKE =
            "    private CommandResult runWithVehicleWake(VehicleCommand cmd) {";
    private static final String REFUSAL = "    private String ignoredPowerOnCloudRefusal("
            + "VehicleCommand cmd, BydDataCollector collector) {";

    @Test
    public void ignoredPowerOnReachesTheCloudOnlyAfterTheStillOffCheck() throws Exception {
        String wake = method(readRouterSource(), WAKE);
        int notSent = wake.indexOf("if (wake == Wake.NOT_SENT) {");
        int stopped = wake.indexOf(
                "CommandResult stopped = stoppedLocalAttempt(cmd, elapsed);", notSent);
        int stoppedReturn = wake.indexOf("if (stopped != null) return stopped;", stopped);
        int refusal = wake.indexOf(
                "String refusal = ignoredPowerOnCloudRefusal(cmd, collector);", stoppedReturn);
        int refused = wake.indexOf(
                "return CommandResult.failed(Path.SDK, msg(\"local_failed\"), elapsed, null);",
                refusal);
        int cloud = wake.indexOf("CommandResult cloud = runCloudOnly(cmd);", refused);
        assertTrue(notSent > 0 && stopped > notSent && stoppedReturn > stopped
                && refusal > stoppedReturn && refused > refusal && cloud > refused);
        // Two cloud sites only: no power-on sent, and a power-on the car ignored.
        assertEquals(2, count(wake, "runCloudOnly(cmd)"));
        assertEquals(1, count(wake, "ignoredPowerOnCloudRefusal("));
        // Success is local-then-cloud, which also opens the router's remote-climate session.
        assertTrue(wake.contains("? CommandResult.success(Path.SDK_THEN_CLOUD, "
                + "msg(\"local_unavailable_used_cloud\"),"));
        // A car that did come on, or whose ACC moved, still never gets the cloud leg here.
        assertTrue(wake.contains("if (wake == Wake.AWAKE) return runSdkAfterWake(cmd, collector, start);"));
        assertTrue(wake.contains("if (wake == Wake.STATE_CHANGED) return null;"));
    }

    @Test
    public void stillOffCheckReReadsTheWakeGateWithTheLivePowerLevelLast() throws Exception {
        String refusal = method(readRouterSource(), REFUSAL);
        String[] ordered = {
                "if (!cmd.hasCloudPath()) return \"no cloud leg\";",
                "if (resolveEffectivePreference(cmd) == RoutePreference.SDK_ONLY) return \"sdk_only policy\";",
                "if (!cloudHandshakeSatisfied(cmd)) return \"cloud not configured\";",
                "if (isRemoteClimateActive()) return \"remote climate active\";",
                "if (isRecentPowerOff()) return \"switched off during the wait\";",
                "if (!DrivingSafetyGuard.isAccConfirmedOff()) return \"ACC not confirmed off\";",
                "if (cmd.localOnlyWhenAwake() && isVehicleOccupiedOrAwake()) return \"awake or occupied\";",
                "int level = collector.readPowerLevelNow();",
                "return level == POWER_LEVEL_OFF ? null : \"power level \" + level;"
        };
        int at = -1;
        for (String check : ordered) {
            int next = refusal.indexOf(check);
            assertTrue(check, next > at);
            at = next;
        }
        // Only an exact 0 passes: ACC (1), on (2/3) and an unreadable level all refuse.
        assertEquals(0, count(refusal, "return null;"));
        assertEquals(1, count(refusal, "? null :"));
    }

    @Test
    public void startsTheCloudCannotRepresentRefuseBeforeAnyVehicleRead() throws Exception {
        Method refusal = VehicleCommandRouter.class.getDeclaredMethod("ignoredPowerOnCloudRefusal",
                VehicleCommandRouter.VehicleCommand.class, com.overdrive.app.byd.BydDataCollector.class);
        refusal.setAccessible(true);
        VehicleCommandRouter router = VehicleCommandRouter.getInstance();
        // A null collector proves these refuse before reading any vehicle state.
        // 32 C is a valid local setpoint that OPENAIR would clamp; 12 min is no BYD timeSpan.
        assertEquals("no cloud leg",
                refusal.invoke(router, new VehicleCommandRouter.ClimateOnCommand(32), null));
        assertEquals("no cloud leg",
                refusal.invoke(router, new VehicleCommandRouter.ClimateOnCommand(22, 12), null));
        // The absolute setpoint wakes the car too, but has no cloud leg at all.
        assertEquals("no cloud leg", refusal.invoke(router,
                new VehicleCommandRouter.ClimateSetTempCommand(0, 22), null));
    }

    @Test
    public void mqttSdkOnlyEntryPointStillNeverWakesOrReachesTheCloud() throws Exception {
        String source = readRouterSource();
        int executeSdkOnly = source.indexOf("public CommandResult executeSdkOnly(VehicleCommand cmd)");
        int finishCommand = source.indexOf("private CommandResult finishCommand(", executeSdkOnly);
        assertTrue(executeSdkOnly > 0 && finishCommand > executeSdkOnly);
        String sdkOnly = source.substring(executeSdkOnly, finishCommand);
        assertFalse(sdkOnly.contains("runWithVehicleWake("));
        assertFalse(sdkOnly.contains("ignoredPowerOnCloudRefusal("));
        assertFalse(sdkOnly.contains("runCloudOnly("));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(signature, start > 0);
        return source.substring(start, source.indexOf("\n    }\n", start));
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
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
