package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards the dependency/cycle regressions raised in the #790 review. */
public class DaemonStartupDependencyContractTest {
    private static String source() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (cwd != null) {
            Path candidate = cwd.resolve(
                    "app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt");
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            cwd = cwd.getParent();
        }
        throw new AssertionError("Cannot find startup manager in repository");
    }

    @Test public void managerWindowsResetAndOlderCallbacksCannotReleaseNewCycle() throws Exception {
        String s = source();
        assertTrue(s.contains("startupStartedAtMs = android.os.SystemClock.elapsedRealtime()"));
        assertTrue(s.contains("startupReleased.set(false)"));
        assertTrue(s.contains("startupProbeInFlight.set(false)"));
        assertTrue(s.contains("generation != startupGeneration.get()"));
        assertTrue(s.contains("deadlineMs - (android.os.SystemClock.elapsedRealtime() - startupStartedAtMs)"));
        assertFalse(s.contains("deadlineMs - android.os.SystemClock.elapsedRealtime()"));
    }

    @Test public void coreStartsAreStaggeredOnBothPaths() throws Exception {
        String s = source();
        String vm = s.substring(s.indexOf("private fun startCoreDaemons()"),
                s.indexOf("private fun startCoreDaemonsViaAdb()"));
        String adb = s.substring(s.indexOf("private fun startCoreDaemonsViaAdb()"),
                s.indexOf("private fun ifNotUserStopped("));
        for (String body : new String[]{vm, adb}) {
            assertTrue(body.contains("ifNotUserStopped(DaemonType.CAMERA_DAEMON)"));
            assertTrue(body.contains("ifNotUserStopped(DaemonType.SENTRY_DAEMON)"));
            assertTrue(body.contains("ifNotUserStopped(DaemonType.ACC_SENTRY_DAEMON)"));
            assertTrue(body.contains("}, 5_000L)"));
            assertTrue(body.contains("}, 10_000L)"));
        }
    }

    @Test public void proxyIsNeverDestructivelyRelaunchedWhileLive() throws Exception {
        String s = source();
        assertTrue(s.contains("singboxStartInFlight.compareAndSet(0L, token)"));
        assertTrue(s.contains("adbLauncher.isSingboxRunning { pidAlive ->"));
        assertTrue(s.contains("if (!pidAlive && singboxStartInFlight.get() == 0L)"));
        assertTrue(s.contains("if (running && singboxPortReady()"));
        assertTrue(s.contains("if (hasEnabledTunnel()) startAllTunnelsViaAdb()"));
        assertTrue(s.contains("if (hasEnabledTunnel()) startAllTunnelsFromPreferences(vm)"));
    }
}
