package com.overdrive.app.updater;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level regressions for unattended downloads; vehicle evidence is still required. */
public class AutoUpdateSafetySourceContractTest {
    private static String source(String path) throws IOException {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("app/src/main"))) {
            root = root.getParent();
        }
        if (root == null) throw new IOException("Cannot locate project root");
        return new String(Files.readAllBytes(root.resolve(path)), StandardCharsets.UTF_8);
    }

    @Test
    public void skipProbeUntilDurableCheckIsDue() throws IOException {
        String scheduler = source("app/src/main/java/com/overdrive/app/updater/AutoUpdateScheduler.java");
        assertTrue(scheduler.contains("if (!AutoUpdateAttemptState.mayCheck() || !mayQueryRelease()"));
        assertFalse(scheduler.contains("AutoUpdateAttemptState.reject(digest,"));
    }

    @Test
    public void onlyDeterministicPreflightIsRemembered() throws IOException {
        String updater = source("app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        assertTrue(updater.contains("AutoApkCompatibilityResult.retry(\n                    \"APK preflight unavailable:"));
        assertTrue(updater.contains("if (incompatible.deterministic) {\n                            AutoUpdateAttemptState.reject("));
        assertTrue(updater.contains("AutoApkCompatibilityResult.reject(\"APK signing certificates differ\")"));
    }

    @Test
    public void downloadingDeferralsUsePersistentBackoff() throws IOException {
        String scheduler = source("app/src/main/java/com/overdrive/app/updater/AutoUpdateScheduler.java");
        String state = source("app/src/main/java/com/overdrive/app/updater/AutoUpdateAttemptState.java");
        assertTrue(scheduler.contains("if (deferred) AutoUpdateAttemptState.downloadDeferred();"));
        assertTrue(state.contains("static void downloadDeferred() {\n        checkFailed();"));
        assertTrue(state.contains("Math.min(24L, 3L * (1L << (n - 1)))"));
    }

    @Test
    public void ownerMeteringIsNotJustAccessPointHint() throws IOException {
        String scheduler = source("app/src/main/java/com/overdrive/app/updater/AutoUpdateScheduler.java");
        String monitor = source("app/src/main/java/com/overdrive/app/monitor/NetworkMonitor.java");
        assertTrue(scheduler.contains("NetworkMonitor.isAutoUpdateWifiOwnerUnmetered(appContext)"));
        assertTrue(monitor.contains("NetworkCapabilities.NET_CAPABILITY_NOT_METERED"));
        assertTrue(monitor.contains("config.networkId != connected.getNetworkId()"));
        assertTrue(monitor.contains("case 1: return false;"));
        assertTrue(monitor.contains("case 0: // Automatic: require the AP hint to be readable."));
    }
}
