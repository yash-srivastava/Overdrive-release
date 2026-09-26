package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class ZrokLauncherReliabilityContractTest {

    @Test
    public void watchdogUsesIpv4AppProcessAndLocalOriginGate() throws Exception {
        String source = read("app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt");
        String probe = read(
                "app/src/main/java/com/overdrive/app/launcher/ZrokRuntimeProbe.java");

        assertTrue(source.contains(
                "private const val ZROK_BACKEND_URL = \"http://127.0.0.1:8080\""));
        assertFalse(source.contains("http://localhost:8080"));
        assertFalse(source.contains("curl -s"));
        assertTrue(source.contains("ZROK_RUNTIME_PROBE_CLASS"));
        assertTrue(source.contains("app_process /system/bin"));
        assertTrue(probe.contains("portOpen(BACKEND_PORT, PORT_TIMEOUT_MS)"));
        assertTrue(source.contains("val probeNameFile = if (reserved)"));
        assertTrue(source.contains("else \"/dev/null\""));
        assertTrue(source.contains(
                "val actualName = ZrokRuntimeProbe.extractLastShareName(output)"));
    }

    @Test
    public void reservedSharesRefreshBinaryAndVersionGateOverride() throws Exception {
        String source = read("app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt");

        assertTrue(source.contains("PACKAGED_ZROK"));
        assertTrue(source.contains("mv -f \\\"\\$STAGED_ZROK\\\""));
        assertTrue(source.contains("supports-override"));
        assertFalse(source.contains(
                "share reserved --help 2>&1 | grep -q override-endpoint"));
        assertTrue(source.contains("--override-endpoint $ZROK_BACKEND_URL"));
    }

    @Test
    public void bootAndTelegramWaitForSingboxReadiness() throws Exception {
        String telegram = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");
        String startup = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt");

        assertTrue(telegram.contains("ProxyHelper.probePort(8119)"));
        assertTrue(telegram.contains("ZrokRuntimeProbe.waitForProxy(5_000L)"));
        assertFalse(telegram.contains("pgrep -f sing-box"));
        assertTrue(startup.contains("val tunnelDelay = if (PreferencesManager.isDaemonEnabled"));
    }

    @Test
    public void releaseKeepsProbeEntrypointAndItsStdoutProtocol() throws Exception {
        String probe = read("app/src/main/java/com/overdrive/app/launcher/ZrokRuntimeProbe.java");
        String rules = read("app/proguard-rules.pro");

        assertTrue(rules.contains("-keep class com.overdrive.app.launcher.ZrokRuntimeProbe"));
        assertTrue(rules.contains("public static void main(java.lang.String[]);"));
        assertTrue(probe.contains("FileDescriptor.out"));
        assertFalse(probe.contains("System.out"));
    }

    @Test
    public void postUpdateRestartPreservesUserStopButIgnoresMachineStop() throws Exception {
        String launcher = read(
                "app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt");
        String updater = read(
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        String startup = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt");

        assertTrue(launcher.contains(
                "ScratchPaths.path(\"zrok.disabled\")"));
        assertTrue(updater.contains(
                "ScratchPaths.path(\"zrok.disabled\")"));
        assertTrue(startup.contains(
                "type == DaemonType.ZROK_TUNNEL"));
        assertTrue(startup.contains(
                "'disabled by ui'*|'disabled by telegram'*"));
        assertTrue(startup.contains("echo MACHINE"));
        assertTrue(read(
                "app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt")
                .contains("writeSentinel: Boolean = true"));
    }

    @Test
    public void shellWatchdogExclusivelyOwnsEdgeRecoveryAndRateLimitBackoff()
            throws Exception {
        String zrok = read(
                "app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt");
        String probe = read(
                "app/src/main/java/com/overdrive/app/launcher/ZrokRuntimeProbe.java");
        String startup = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt");

        assertTrue(zrok.contains("private const val ZROK_RATE_LIMIT_COOLDOWN_SEC = 300"));
        assertTrue(zrok.contains("SERVER_TOO_MANY_REQUESTS"));
        assertTrue(zrok.contains("DELAY=\\$RATE_LIMIT_COOLDOWN_SEC"));
        assertTrue(zrok.contains("fun isTunnelManaged"));
        assertFalse(zrok.contains("fun checkTunnelHealth"));
        assertTrue(startup.contains("zrokLauncherForHealthCheck.isTunnelManaged"));
        assertTrue(startup.contains("a live start_zrok.sh means \"recovering\""));
        assertFalse(startup.contains("EDGE_STALE"));
        assertTrue(probe.contains("Edge stale confirmed; terminating zrok pid"));
    }

    @Test
    public void zrokCredentialsAreQuotedAndNeverUsedAsLogLabels() throws Exception {
        String zrok = read(
                "app/src/main/java/com/overdrive/app/launcher/ZrokLauncher.kt");
        String adb = read(
                "app/src/main/java/com/overdrive/app/launcher/AdbShellExecutor.kt");
        String telegram = read(
                "app/src/main/java/com/overdrive/app/daemon/telegram/DaemonCommandHandler.java");

        assertTrue(zrok.contains("ZrokRuntimeProbe.shellQuote(zrokToken)"));
        assertTrue(zrok.contains("adbShellExecutor.executeSensitive("));
        assertTrue(zrok.contains("ZrokRuntimeProbe.summarizeFailure(rawError)"));
        assertFalse(zrok.contains("Executing enable: $cmd"));
        assertFalse(zrok.contains("Reserved! Token: $token"));
        assertFalse(zrok.contains("Reserved token: $token"));

        assertTrue(adb.contains("fun execute(command: String, callback: ShellCallback)"));
        assertTrue(adb.contains("fun executeSensitive("));
        // shellGuarded runs the remapped command on the generation-tagged
        // connection; logs still see only the redacted $commandForLog.
        assertTrue(adb.contains(
                "conn.dadb.shell(ScratchPaths.prepareShellCommand(command))"));
        assertTrue(adb.contains("$commandForLog"));

        assertTrue(telegram.contains("ZrokRuntimeProbe.shellQuote(enableToken)"));
        assertTrue(telegram.contains("ZrokRuntimeProbe.extractErrorMessage(enableResult)"));
        assertTrue(telegram.contains("ScratchPaths.path(\".zrok/environment.json\")"));
        assertFalse(telegram.contains("Using reserved token:"));
        assertFalse(telegram.contains("Enable result:"));
    }

    private static String read(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
