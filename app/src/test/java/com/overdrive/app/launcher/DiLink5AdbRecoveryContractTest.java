package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Source-level contract for the DiLink 5 ADB recovery fallback: lock discipline,
 * non-goals (no transport/property/camera changes), and the preserved existing
 * limits in the two files it wires into.
 */
public class DiLink5AdbRecoveryContractTest {

    @Test
    public void escalatorStaysOffTheLockAndOutOfNonGoals() throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/launcher/DiLink5AdbRecoveryEscalator.kt");
        assertTrue(source.contains("Di5AdbRecoveryEscalation"));
        assertTrue(source.contains("AtomicBoolean"));
        assertTrue(source.contains("isAdbPortClosedNow"));
        // Budget is spent only after an OEM request was issued; early aborts take the other path.
        assertTrue(source.contains("onEscalationFinished"));
        assertTrue(source.contains("onEscalationAborted"));
        assertFalse(source.contains("sharedDadbLock"));
        assertFalse(source.contains("Dadb.create"));
        assertFalse(source.contains("adb_wifi_enabled"));
        assertFalse(source.contains("persist."));
    }

    @Test
    public void oemClientTouchesOnlyTheScopedProperty() throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/launcher/DiLink5OemAdbEnableClient.java");
        assertTrue(source.contains("ADB_TCP_PORT_PROPERTY = \"service.adb.tcp.port\""));
        assertTrue(source.contains("OTA_VAL_ADB_ENABLED = 77"));
        assertFalse(source.contains("adb_wifi_enabled"));
        assertFalse(source.contains("persist."));
        assertFalse(source.contains("CameraDaemonClient"));
    }

    @Test
    public void portWatchWiringIsPresentButNotUnderTheConnectLock() throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/launcher/AdbShellExecutor.kt");

        String acquireRegion = between(source,
                "private fun acquireConnection", "private fun installConnectionLocked");
        assertFalse(acquireRegion.contains("DiLink5AdbRecoveryEscalator"));

        String watchRegion = between(source,
                "private fun runDiLink5PortWatch", "private fun isAdbPortOpen");
        assertTrue(watchRegion.contains("onPortClosedObservation"));
        assertTrue(watchRegion.contains("onPortOpenObservation"));
        assertTrue(watchRegion.contains("onConnectionObserved"));

        // The watch's own successful connect (the primary success path after an escalation) must
        // report the installed connection too, not only the "someone else connected" branch at
        // the top of the loop: the hook has to follow acquireConnection() inside the watch.
        int ownConnect = watchRegion.indexOf("acquireConnection()");
        assertTrue("watch no longer connects itself", ownConnect >= 0);
        assertTrue("onConnectionObserved must follow the watch's own acquireConnection()",
                watchRegion.indexOf("onConnectionObserved", ownConnect) > ownConnect);
        assertTrue("the hook must run outside the connection lock",
                !between(watchRegion, "acquireConnection()", "onConnectionObserved")
                        .contains("synchronized"));

        assertTrue(source.contains("DI5_ADB_REENABLE_INTERVAL_MS = 60_000L"));
        assertTrue(source.contains("DI5_ADB_REENABLE_AFTER_CONNECT_MS = 10_000L"));
        assertTrue(source.contains("DI5_PORT_WATCH_POLL_MS = 2_000L"));
        assertTrue(source.contains("DI5_PORT_WATCH_FAST_MS = 15L * 60_000L"));
        assertTrue(source.contains("DI5_PORT_WATCH_SLOW_POLL_MS = 30_000L"));
        assertTrue(source.contains("val maxAttempts = 60"));
    }

    @Test
    public void daemonObservesClosedPortButLeavesMutationToCoordinator() throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        assertTrue(source.contains("DILINK5_ADB_RECOVERY_RETRY_MS = 15 * 60_000L"));
        assertTrue(source.contains("DILINK5_ADB_CHECK_EVERY_TICKS = 6"));

        String recoveryRegion = between(source,
                "private static void recoverDilink5AdbIfNeeded",
                "private static boolean isLoopbackPortOpen");
        assertTrue(recoveryRegion.contains("waiting for coordinated app-process recovery"));
        assertFalse(recoveryRegion.contains("settings put global adb_enabled"));
        assertFalse(recoveryRegion.contains("settings put global adb_wifi_enabled"));
        assertFalse(recoveryRegion.contains("settings put global adb_allowed_connection_time"));
    }

    @Test
    public void gateShipsWithAnEmptyAllowlist() throws Exception {
        String source = read(
                "app/src/main/java/com/overdrive/app/launcher/DiLink5AdbFallbackGate.java");
        assertTrue(source.contains(
                "VERIFIED_DILINK5_ADB_FALLBACK_SIGNATURES = Collections.emptySet()"));
    }

    private static String between(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue("missing anchor: " + from, start >= 0);
        int end = source.indexOf(to, start);
        assertTrue("missing anchor: " + to, end > start);
        return source.substring(start, end);
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
