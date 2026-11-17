package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Regression contract for daemon starts after a stale parked/ADB session. */
public class DaemonActiveSessionRecoveryContractTest {

    @Test
    public void explicitStartClearsBothDurableBlockersBeforeLaunch()
            throws IOException {
        String viewModel = read(
                "app/src/main/java/com/overdrive/app/ui/viewmodel/DaemonsViewModel.kt");

        int clear = viewModel.indexOf("private fun clearStartBlockers(");
        int next = viewModel.indexOf(
                "fun stopDaemon(type: DaemonType)", clear);
        assertTrue(clear >= 0 && next > clear);
        String helper = viewModel.substring(clear, next);
        assertTrue(helper.contains("ParkedShutdown.MARKER_PATH"));
        assertTrue(helper.contains(
                "rm -f ${type.sentinelPath} $parkedMarker"));
        assertTrue(helper.contains("onLaunched()"));
        assertTrue(helper.contains("onCleared()"));

        int start = viewModel.indexOf("fun startDaemon(");
        int launcher = viewModel.indexOf("private fun launchController(", start);
        assertTrue(start >= 0 && launcher > start);
        String startBody = viewModel.substring(start, launcher);
        assertTrue(startBody.contains("clearStartBlockers(type)"));
        assertTrue(startBody.contains("launchController(type, controller, onComplete)"));
        assertTrue(startBody.contains(
                "type in DaemonStartupManager.userStoppedDaemons"));
        assertTrue(startBody.indexOf(
                "type in DaemonStartupManager.userStoppedDaemons")
                < startBody.indexOf("launchController(type, controller)"));
    }

    /**
     * The "Vehicle ON only" parked-shutdown marker is authoritative for every
     * AUTOMATIC start. Neither the MainActivity-scoped timers nor the health check
     * may erase it: an Activity start, a +45 s timer or a 30 s revival tick is not
     * evidence that the vehicle is on. Only the ACC judge (acc_sentry_daemon), a
     * recovery trigger with a VERIFIED erase, or the explicit user Start above may
     * end a park.
     */
    @Test
    public void automaticStartsHonorParkedMarkerWithoutErasingIt()
            throws IOException {
        String startup = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt");

        int automatic = startup.indexOf("private fun ifNotUserStopped(");
        int optional = startup.indexOf(
                "private fun startOptionalDaemonsFromPreferences()", automatic);
        assertTrue(automatic >= 0 && optional > automatic);
        String automaticBody = startup.substring(automatic, optional);
        assertTrue(automaticBody.contains("PARKED_BLOCKED"));
        assertTrue(automaticBody.contains("ParkedShutdown.MARKER_PATH"));
        assertTrue(automaticBody.contains("noteParkObserved()"));
        // The gate is unconditional (both managers) and read-only.
        assertFalse(automaticBody.contains("daemonsViewModel != null"));
        assertFalse(automaticBody.contains("rm -f \"$P\""));

        int relaunch = startup.indexOf("private fun relaunchDaemon(");
        int doRelaunch = startup.indexOf("private fun doRelaunchDaemon(", relaunch);
        assertTrue(relaunch >= 0 && doRelaunch > relaunch);
        String relaunchBody = startup.substring(relaunch, doRelaunch);
        assertTrue(relaunchBody.contains(
                "-o -f $parkedMarker && echo STOPPED || echo OK"));
        assertFalse(relaunchBody.contains("rm -f $parkedMarker"));

        // No age-based sweep of the marker anywhere in the startup manager.
        assertFalse(startup.contains("cleared stale"));

        // startOnBoot carries an absolute file gate and rebuilds after an observed park.
        int boot = startup.indexOf("fun startOnBoot(context: Context)");
        int stop = startup.indexOf("fun stopHealthChecks()", boot);
        assertTrue(boot >= 0 && stop > boot);
        String bootBody = startup.substring(boot, stop);
        assertTrue(bootBody.contains("ParkedShutdown.MARKER_PATH).exists()"));
        assertTrue(bootBody.indexOf("ParkedShutdown.MARKER_PATH).exists()")
                < bootBody.indexOf("if (bootStarted)"));
        // A duplicate call is a no-op; only an observed park or a fresh park-END
        // breadcrumb from the ACC judge may rebuild past the process-lifetime guard,
        // and never beside a live MainActivity-scoped manager.
        assertTrue(bootBody.contains("if (!parkObserved && !breadcrumbIsNew) return"));
        assertTrue(bootBody.contains("ParkedShutdown.ENDED_PATH")
                || startup.contains("private fun readParkEndedStamp()"));
        assertTrue(bootBody.contains("if (activityManager != null)"));
        // While parked, the only thing startOnBoot may do is keep the judge alive.
        assertTrue(bootBody.indexOf("ensureAccSentryJudgeRunning(context)")
                < bootBody.indexOf("if (bootStarted)"));

        // The judge is exempt from the marker half of the automatic-start gates.
        assertTrue(automaticBody.contains(
                "if (type == DaemonType.ACC_SENTRY_DAEMON) {"));
        assertTrue(relaunchBody.contains(
                "if (type == DaemonType.ACC_SENTRY_DAEMON) {"));

        // Recovery relaunches ONLY after the erase is verified, and ACC_MODE_CHANGED
        // (a both-edges broadcast) is no longer a recovery trigger.
        String receiver = read(
                "app/src/main/java/com/overdrive/app/receiver/BootReceiver.kt");
        int recovery = receiver.indexOf("private fun isRecoveryTrigger(");
        int startDaemons = receiver.indexOf("private fun startDaemons(", recovery);
        assertTrue(recovery >= 0 && startDaemons > recovery);
        assertFalse(receiver.substring(recovery, startDaemons)
                .contains("ACC_MODE_CHANGED"));
        assertTrue(receiver.contains(
                "DaemonStartupManager.recoverFromPark(appCtx) { launchStack(appCtx, trigger) }"));
        assertTrue(startup.contains(
                "if [ -f $marker ]; then echo STILL_PRESENT; exit 1; fi; echo CLEARED"));
    }

    @Test
    public void transientAuthProbeFailureDoesNotKillPollingLoop()
            throws IOException {
        String executor = read(
                "app/src/main/java/com/overdrive/app/launcher/AdbShellExecutor.kt");

        int polling = executor.indexOf("private fun startAuthPollingInternal(");
        int close = executor.indexOf("fun closeConnection()", polling);
        assertTrue(polling >= 0 && close > polling);
        String body = executor.substring(polling, close);
        assertTrue(body.contains("val testDadb = try {"));
        assertTrue(body.contains("tryConnectWithTimeout(keyPair, 2000)"));
        assertTrue(body.contains("catch (e: Exception)"));
        assertTrue(body.contains("Auth poll attempt $attempts failed"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
