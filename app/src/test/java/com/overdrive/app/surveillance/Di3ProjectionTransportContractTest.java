package com.overdrive.app.surveillance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Regression contracts for DI3 projection transport and recovery ordering. */
public class Di3ProjectionTransportContractTest {

    @Test
    public void legacyProjectionUsesBinderFirstAndLetsLateCallsResolve()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "ClusterProjectionController.java");
        String dispatch = section(
                source,
                "private static LegacyCommandResult sendLegacyInfo(",
                "public enum DiLink5CommandResult");

        assertOrdered(
                dispatch,
                "legacyCommandStillBlocked()",
                "runLegacyBinderAttempt(",
                "attempt.completion.await(",
                "watchLateLegacyBinderAttempt(attempt, op)",
                "if (attempt.result == LegacyTransportResult.UNAVAILABLE)",
                "sendLegacyInfoShell(service, op)");
        assertTrue(dispatch.contains(
                "sendInfoBinderTransportResult(String service, int op)"));
        assertTrue(dispatch.contains(
                "scheduleRecoveryAfterLateLegacyCommand()"));
        assertTrue(dispatch.contains(
                "retryRecoveryAfterLegacyCommandResolution()"));
        assertTrue(dispatch.contains("return LegacyTransportResult.FAILED;"));
        assertFalse(dispatch.contains("destroyForcibly"));
        // A command never waits on config I/O.
        assertFalse(dispatch.contains("updateValues"));

        String shellFallback = section(
                source,
                "private static LegacyCommandResult sendLegacyInfoShell(",
                "static boolean isAcceptedLegacyServiceCallResult(");
        assertOrdered(
                shellFallback,
                "process.waitFor(",
                "watchLateLegacyShellCommand(process, service, op)",
                "LegacyTransportResult.INDETERMINATE");
        assertFalse(shellFallback.contains("destroyForcibly"));
        assertFalse(shellFallback.contains("updateValues"));
    }

    @Test
    public void blockedLegacyCommandStopsRecoveryRetriesAndDuplicateDispatch()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/"
                        + "ClusterProjectionController.java");
        String bootRecovery = section(
                source,
                "private static void runBootGaugeRestore(",
                "private static boolean sleepForLegacyRestore(");

        assertOrdered(
                bootRecovery,
                "LegacyCommandResult closeResult =",
                "closeResult == LegacyCommandResult.INDETERMINATE",
                "LegacyBootRecoveryResult\n"
                        + "                                    "
                        + ".SAME_BOOT_INDETERMINATE",
                "break;");
        assertTrue(bootRecovery.contains(
                "refreshResult\n"
                        + "                            "
                        + "== LegacyCommandResult.INDETERMINATE"));
        assertTrue(bootRecovery.contains(
                "profileResult\n"
                        + "                                "
                        + "== LegacyCommandResult.INDETERMINATE"));

        String bootAdmission = section(
                source,
                "private static LegacyBootRecoveryResult\n"
                        + "            clearStaleGateAtBootResultSynchronously(",
                "private static void runBootGaugeRestore(");
        assertOrdered(
                bootAdmission,
                "legacyCommandStillBlocked()",
                "isLegacyProjectionBlockedByDiLink5Recovery())",
                "new BootRestoreTicket()");

        String gateClear = section(
                source,
                "private static boolean clearGateFlagsStatic()",
                "static LegacyGateSnapshot parseLegacyGateSnapshot(");
        assertOrdered(
                gateClear,
                "GATE_IO.submit(",
                "legacyCommandStillBlocked()",
                "UnifiedConfigManager.updateValues(");
    }

    @Test
    public void crossModeRecoveryPreservesSameBootFailureCause()
            throws IOException {
        String cast = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/"
                        + "DiLink5ClusterCast.kt");
        assertTrue(cast.contains(
                "clearStaleGateAfterDiLink5RecoveryResultSynchronously()"));
        assertTrue(cast.contains(
                "fun retryRecoveryAfterLegacyCommandResolution()"));
        assertTrue(cast.contains(
                "scheduleLegacyRecoveryResumePoll()"));
        assertTrue(cast.contains(
                "sameBootIndeterminate =\n"
                        + "                            "
                        + "legacyRecovery.sameBootIndeterminate"));
        assertTrue(cast.contains(
                "retryBlockedBySameBootIndeterminate ||\n"
                        + "                                "
                        + "legacyRecovery.sameBootIndeterminate"));

        String legacyCast = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/"
                        + "ClusterCast.java");
        assertTrue(legacyCast.contains(
                "ClusterProjectionController\n"
                        + "                .isLegacyProjectionAdmissionBlocked()"));
        assertFalse(legacyCast.contains(
                "unresolved DI5 recovery ownership"));
    }

    @Test
    public void automaticMapRestorationCannotPreemptANewerCustomLayout() throws IOException {
        String root = "app/src/main/java/com/overdrive/app/";
        String cast = readRepositoryFile(root + "launcher/ClusterCast.java");
        String projector = readRepositoryFile(root + "navmap/ClusterMapProjector.java");
        String controller = readRepositoryFile(root + "surveillance/ClusterProjectionController.java");
        assertTrue(cast.contains("ClusterMapProjector.restoreAfterLegacyCastStop()"));
        assertOrdered(projector, "public static void start()", "start(true);",
                "public static void restoreAfterLegacyCastStop()", "start(false);");
        assertOrdered(projector, "private static synchronized void start(boolean explicitSelection)",
                "if (!explicitSelection)", "CustomLayoutsController.claimsAutomaticProjection()",
                "return;", "startAdmitted(explicitSelection)", "publishActiveFlag(true)",
                "? controller.acquireSustained()", ": controller.acquireAutomaticMapHold()");
        assertTrue(section(projector, "private static void armLateDisplayRetry", "private static boolean sleepWhileActive")
                .contains("startWhenSettled(false);"));
        // A selection arriving between the map's admission check and its completed hold also wins.
        assertOrdered(controller, "public boolean acquireSustained(String token)",
                "return acquireSustained(token, true)", "public boolean acquireAutomaticMapHold()",
                "return acquireSustained(\"map\", false)", "if (!requestOpen())",
                "if (notifySelection) notifyCustomLayoutsOfOwner(holderToken)");
    }

    @Test
    public void automaticProjectionKeepsItsIntentThroughQueuedAndTransportAdmission() throws IOException {
        String root = "app/src/main/java/com/overdrive/app/";
        String acc = readRepositoryFile(root + "monitor/AccMonitor.java");
        String cast = readRepositoryFile(root + "launcher/ClusterCast.java");
        String map = readRepositoryFile(root + "navmap/ClusterMapProjector.java");
        String projection = readRepositoryFile(root + "surveillance/ClusterProjectionController.java");
        String di5 = readRepositoryFile(root + "launcher/DiLink5ClusterCast.kt");
        assertOrdered(acc, "private static void maybeAutoStartClusterProjection", "&& !clusterRecoveryOwner)",
                "return;", "ClusterMapProjector.startAutomaticallyWhenSettled()", "ClusterCast.startAutomaticallyWhenSettled(projPkg)");
        assertOrdered(cast, "public static boolean startWhenSettled(String pkg)", "return startWhenSettled(pkg, true)",
                "public static boolean startAutomaticallyWhenSettled(String pkg)", "return startWhenSettled(pkg, false)",
                "Thread queued", "start(pkg, explicitSelection)");
        assertOrdered(cast, "private static void armLateDisplayRetry", "startAutomaticallyWhenSettled(pkg)");
        assertOrdered(cast, "private static synchronized boolean start(String pkg, boolean explicitSelection)",
                "claimsAutomaticProjection()", "startAdmitted(pkg, explicitSelection)",
                "DiLink5ClusterCast.start(pkg, explicitSelection)", "controller.acquireAutomaticCastHold()");
        assertTrue(projection.contains("return acquireSustained(\"castapp\", false)"));
        assertOrdered(map, "public static void startAutomaticallyWhenSettled()", "startWhenSettled(false)",
                "Thread queued", "start(explicitSelection)", "DiLink5ClusterCast.startMap(false)");
        assertOrdered(di5, "if (target.automatic && activeKind == SessionKind.CUSTOM) return null",
                "activePackage to generation", "stopInternal(previousGeneration", "if (target.automatic && !selectionStillAllows(target)) return null",
                "ticket = StartTicket(nextGeneration)");
    }

    private static String section(
            String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start);
        if (start < 0 || end <= start) {
            throw new AssertionError(
                    "Could not find source section: "
                            + startNeedle + " -> " + endNeedle);
        }
        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... needles) {
        int cursor = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, cursor + 1);
            assertTrue("Missing or out-of-order source fragment: " + needle,
                    next > cursor);
            cursor = next;
        }
    }

    private static String readRepositoryFile(String relativePath)
            throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }
}
