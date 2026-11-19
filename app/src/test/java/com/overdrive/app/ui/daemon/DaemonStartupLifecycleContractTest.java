package com.overdrive.app.ui.daemon;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source contracts for the ADB/startup lifecycle (no Android Looper or head unit needed). */
public class DaemonStartupLifecycleContractTest {
    private static final String STARTUP =
            "app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt";

    @Test
    public void reconnectCannotRestartTheStagedDeadlinesOrHealthCheck() throws IOException {
        String source = read(STARTUP);
        String initialize = section(source, "fun initializeOnAppLaunch()",
                "private fun beginStartupCycle(");
        assertTrue(initialize.contains("if (startupCycleStartedAtMs != null)"));
        assertTrue(initialize.contains("val inheritedStart = previousBootManager?.startupCycleStartedAtMs"));
        assertTrue(initialize.contains("val healthWasRunning = previousBootManager?.healthCheckRunning"));
        assertTrue(initialize.contains("bootHadDeferredStarts"));
        assertTrue(initialize.contains("runHealthCheck(RECONNECT_PROBE_RETRIES)"));
        assertTrue(initialize.contains("beginStartupCycle(boot = false, inheritedStart = inheritedStart"));
        assertFalse(initialize.contains("handler.postDelayed({ startCoreDaemons()"));

        String cycle = section(source, "private fun beginStartupCycle(",
                "private fun startLeadingTailscale()");
        assertTrue(cycle.contains("startupCycleStartedAtMs = origin"));
        assertTrue(cycle.contains("firedStartupPhases.addAll(alreadyFired)"));
        assertTrue(cycle.contains("firedStartupPhases.add(offset)"));
        assertTrue(cycle.contains("CORE_DELAY_MS + DaemonBootSchedule.SENTRY_STAGGER_MS"));
        assertTrue(cycle.contains("CORE_DELAY_MS + DaemonBootSchedule.ACC_SENTRY_STAGGER_MS"));
        assertTrue(cycle.contains("SystemClock.elapsedRealtime()"));
        assertTrue(cycle.contains("origin + offset - SystemClock.elapsedRealtime()"));
        assertTrue(cycle.contains("if (healthWasRunning)"));
        assertTrue(cycle.contains("startDaemonHealthCheck()"));
        assertTrue(cycle.contains("AdbShellExecutor.addConnectionReestablishedListener"));

        String reconnect = section(source, "private fun onAdbConnectionReestablished(",
                "private fun deferUntilAdbConnects(");
        assertTrue(reconnect.contains("replayDeferredStarts(generation)"));
        assertTrue(reconnect.contains("runHealthCheck(RECONNECT_PROBE_RETRIES)"));
        assertFalse(reconnect.contains("initializeOnAppLaunch()"));
        assertFalse(reconnect.contains("beginStartupCycle("));
    }

    @Test
    public void singboxUserStopOrTimeoutAllowsDirectTunnels() throws IOException {
        String source = read(STARTUP);
        String start = section(source, "private fun startTunnelsAfterSingbox(",
                "private fun syncTelegramEnabledToUnifiedConfig(");
        assertTrue(start.contains("onBlocked = {"));
        assertTrue(start.contains("finishSingboxWait(wait"));
        assertTrue(start.contains("SINGBOX_READY_POLLS"));
        assertTrue(start.contains("Singbox port 8119 not ready after bounded wait"));
        assertTrue(start.contains("ifNotUserStopped(DaemonType.SINGBOX_PROXY"));
        assertTrue(start.contains("wait.launchInFlight"));
        assertTrue(start.contains("SINGBOX_MAX_LAUNCH_ATTEMPTS"));

        String relaunch = section(source, "private fun doRelaunchDaemon(",
                "private fun launchHealthCheckDaemon(");
        assertTrue(relaunch.contains("ifNotUserStopped(DaemonType.SINGBOX_PROXY"));
        assertTrue(relaunch.contains("relaunchDaemonDirect(type)"));
        assertTrue(relaunch.contains("poll >= SINGBOX_READY_POLLS"));
        assertTrue(relaunch.contains("ParkedShutdown.MARKER_PATH"));
        assertFalse(relaunch.contains("startSingbox(createLogCallback"));
        assertFalse(relaunch.contains("startTailscaleOnBoot()"));
    }

    @Test
    public void userStopCacheIsOnlyResetAtTheRealBootEntry() throws IOException {
        String source = read(STARTUP);
        String app = section(source, "fun initializeOnAppLaunch()", "private fun beginStartupCycle(");
        String boot = section(source, "private fun initializeOnBoot(", "fun checkAllDaemonStatuses()");
        assertFalse(app.contains("userStoppedDaemons.clear()"));
        assertFalse(boot.contains("userStoppedDaemons.clear()"));
        assertTrue(section(source, "fun startOnBoot(context: Context)",
                "fun stopHealthChecks()").contains("userStoppedDaemons.clear()"));
    }

    private static String section(String s, String from, String to) {
        int start = s.indexOf(from);
        int end = s.indexOf(to, start);
        assertTrue("Missing " + from, start >= 0);
        assertTrue("Missing " + to, end > start);
        return s.substring(start, end);
    }

    private static String read(String path) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(path);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new IOException("Cannot find " + path);
    }
}
