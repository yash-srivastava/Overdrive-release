package com.overdrive.app.updater;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Source-level guardrails for unattended update safety; no hardware required. */
public class AutoUpdateSafetyContractTest {
    private static String source(String relative) throws Exception {
        Path base = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8 && base != null; i++, base = base.getParent()) {
            Path path = base.resolve(relative);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new java.io.IOException("Cannot locate source: " + relative);
    }

    @Test public void automaticInstallIsOptInAndChannelChangesPreserveIt() throws Exception {
        String config = source("app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt");
        assertTrue(config.contains("optBoolean(\"autoInstall\", false)"));
        assertTrue(config.contains("updateSection(\"updates\", JSONObject().put(\"channel\", channel))"));
        assertTrue(config.contains("updateValues(\"updates\", mapOf(\"autoInstall\" to enabled))"));
    }

    @Test public void autoUpdatesRequireBraveheartAndConfirmedIdleState() throws Exception {
        String scheduler = source("app/src/main/java/com/overdrive/app/updater/AutoUpdateScheduler.java");
        String daemon = source("app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        assertTrue(scheduler.contains("isAutoUpdateEnabled()"));
        assertTrue(scheduler.contains("CHANNEL_BRAVEHEART.equals"));
        assertTrue(scheduler.contains("isSafeForAutomaticUpdate()"));
        assertTrue(scheduler.contains("AppUpdater.tryBeginInstall()"));
        assertTrue(scheduler.contains("NET_CAPABILITY_NOT_METERED"));
        assertTrue(scheduler.contains("AutoUpdateAttemptState.mayCheck()"));
        assertTrue(daemon.contains("!isAccOffAdmitted()"));
        assertTrue(daemon.contains("UnifiedConfigManager.isSurveillanceEnabled()"));
        assertTrue(daemon.contains("pano.isRecording()"));
        assertTrue(daemon.contains("30L * 60L * 1000L"));
        assertTrue(daemon.contains("probeDiLink5AccOnForTransition"));
        assertTrue(daemon.contains("oem.isRecording()"));
    }

    @Test public void manualInstallIsUnchangedAndAutoGuardRunsBeforeHandoff() throws Exception {
        String updater = source("app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        assertTrue(updater.contains("downloadAndInstall(callback, null);"));
        int afterDownload = updater.indexOf("Automatic update deferred: vehicle became active");
        int afterCameraPrep = updater.indexOf("Automatic update deferred: vehicle became active", afterDownload + 1);
        int preHandoff = updater.indexOf("Automatic update deferred: vehicle became active", afterCameraPrep + 1);
        int handoff = updater.indexOf("boolean detachedStarted = runDetachedInstall(", preHandoff);
        assertTrue(afterDownload > 0 && afterCameraPrep > afterDownload);
        assertTrue(preHandoff > afterCameraPrep && handoff > preHandoff);
        assertTrue(updater.contains("rollbackPreparedUpdateMetadata(\n                            channel, priorUpdateTimestamp, priorDisplayVersion)"));
        assertTrue(updater.contains("verifyAutomaticApkSha256()"));
        assertTrue(updater.contains("AutoUpdateAttemptState.reserve(normalizedDigest())"));
        assertTrue(updater.contains("automatic ? \"PM_OUT=$(pm install -r \""));
    }
}
