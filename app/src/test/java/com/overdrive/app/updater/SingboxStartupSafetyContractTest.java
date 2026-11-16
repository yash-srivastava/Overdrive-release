package com.overdrive.app.updater;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source-level regression contracts for boot-time Sing-box startup. */
public class SingboxStartupSafetyContractTest {

    @Test
    public void readinessUsesTheListeningSocketRatherThanSelfMatchingProcessSearch()
            throws Exception {
        String launcher = read(
                "app/src/main/java/com/overdrive/app/launcher/SingboxLauncher.kt");
        String controller = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/SingboxController.kt");

        assertTrue(launcher.contains("ProxyHelper.probePort(PROXY_PORT)"));
        assertFalse(launcher.contains("pgrep -f sing-box"));
        assertTrue(controller.contains("adbLauncher.isSingboxRunning(callback)"));
        assertFalse(controller.contains("ps -A | grep sing-box"));
    }

    @Test
    public void healthyProxyIsReusedInsteadOfRestarted() throws Exception {
        String launcher = read(
                "app/src/main/java/com/overdrive/app/launcher/SingboxLauncher.kt");
        String controller = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/SingboxController.kt");

        int readyProbe = launcher.indexOf("probeProxyPort { alreadyRunning ->");
        int readyReturn = launcher.indexOf("callback.onStarted()", readyProbe);
        int install = launcher.indexOf("// Check if binary is installed", readyProbe);
        assertTrue(readyProbe >= 0);
        assertTrue(readyReturn > readyProbe);
        assertTrue(install > readyReturn);
        assertTrue(controller.contains("if (running)"));
        assertTrue(controller.contains("return@isSingboxRunning"));
    }

    @Test
    public void startupKeepsSystemStabilizationAndStaggeredStages() throws Exception {
        String startup = read(
                "app/src/main/java/com/overdrive/app/ui/daemon/DaemonStartupManager.kt");

        assertTrue(startup.contains("handler.postDelayed({ awaitAdbReadiness { releaseStartupGraph() } }, 45_000L)"));
        assertTrue(startup.contains("}, 15_000L)"));
        assertTrue(startup.contains("}, 45_000L)"));
        assertTrue(startup.contains("SystemUI and the power service"));
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
