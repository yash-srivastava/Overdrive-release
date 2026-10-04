package com.overdrive.app.surveillance;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the UI hydration and last-request-wins contracts of the master toggle. */
public class SurveillanceToggleReliabilityContractTest {

    @Test
    public void toggleWaitsForHydrationAndSerializesRequests() throws IOException {
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/surveillance.html");
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/surveillance.js");
        String server = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");

        assertTrue(html.contains(
                "id=\"survEnabled\" disabled onchange=\"SurvSettings.toggleSurveillance()\""));
        assertTrue(html.contains(
                "<main class=\"main-content\" aria-busy=\"true\" inert style=\"pointer-events:none\">"));
        assertTrue(html.contains("settings-hydration.js?v=1"));
        assertTrue(html.contains("surveillance.js?v=survtoggle10"));
        assertTrue(html.contains("id=\"cdrCleanupEnabled\" disabled"));
        assertTrue(html.contains("id=\"cdrReservedSlider\""));
        assertTrue(html.contains("id=\"cdrProtectedSlider\""));
        assertTrue(html.contains(
                "id=\"cdrMinKeepSlider\" min=\"0\" max=\"100\""));
        assertTrue(html.contains(
                "id=\"cdrCleanupNow\" disabled"));
        assertTrue(script.contains(
                "if (!this._hydrated || !this.savedConfig || this._surveillanceTogglePending)"));
        assertTrue(script.contains("this._surveillanceTogglePending = true;"));
        assertTrue(script.contains("this._surveillanceTogglePending = false;"));
        assertTrue(script.contains(
                "id === 'survEnabled' && self._surveillanceTogglePending"));
        assertTrue(script.contains(
                "const body = await this._enqueueWrite(async () =>"));
        assertTrue(script.contains("data.success !== true"));
        assertTrue(script.contains("_writeQueue: Promise.resolve()"));
        assertTrue(script.contains("_writeJson(url, body)"));
        assertTrue(script.contains("_nextImmediateWrite(key)"));
        assertTrue(script.contains("_cdrWritesPending"));
        assertTrue(script.contains("_cdrCleanupPending"));
        assertTrue(script.contains("Object.keys(this._cdrDirty).length > 0"));
        assertTrue(script.contains("if (!saved) this._cdrReady = false;"));
        assertTrue(script.contains("if (data.cleanupEnabled != null)"));
        assertTrue(script.contains(
                "!this._cdrReady || !this.cdrConfig.enabled"));
        assertTrue(script.contains("_geocodingWriteVersion"));
        assertTrue(script.contains("_layoutWriteVersion"));
        assertTrue(script.contains("_telemetryWriteVersion"));
        assertTrue(server.contains(
                "config.put(\"enabled\", com.overdrive.app.config.UnifiedConfigManager.isSurveillanceEnabled())"));
        assertTrue(server.contains(
                "if (!com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(true))"));
        assertTrue(server.contains(
                "if (!com.overdrive.app.config.UnifiedConfigManager.setSurveillanceEnabled(false))"));
    }

    @Test
    public void coreHydrationDoesNotWaitForCdrStatus() throws IOException {
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/surveillance.html");
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/surveillance.js");

        int initStart = script.indexOf("async init() {");
        int reloadStart = script.indexOf("async reloadConfig()", initStart);
        assertTrue(initStart >= 0);
        assertTrue(reloadStart > initStart);
        String init = script.substring(initStart, reloadStart);

        assertTrue(init.contains("const configLoaded = await this.loadConfig();"));
        assertTrue(init.contains("if (!configLoaded)"));
        assertTrue(!init.contains("await this.updateCdrCleanupVisibility();"));

        int hydrated = init.indexOf("this._hydrated = true;");
        int cdrLoad = init.indexOf("this.updateCdrCleanupVisibility();");
        assertTrue(hydrated >= 0);
        assertTrue(cdrLoad > hydrated);

        assertTrue(html.contains("}).then(unlockSettings, failSettings);"));
        assertTrue(html.contains("BYD.i18n.t('errors.load_failed')"));
    }

    @Test
    public void disabledPreferenceWinsAgainstAnInFlightEnable() throws IOException {
        String daemon = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String ipc = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        String tcp = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/TcpCommandServer.java");

        int enable = daemon.indexOf("private static boolean enableSurveillanceForAccGeneration(");
        int pipelineEnable = daemon.indexOf("pipeline.enableSurveillance();", enable);
        int preferenceRecheck = daemon.indexOf(
                ".isSurveillanceEnabled()) {", pipelineEnable);
        int rollback = daemon.indexOf("disableSurveillance();", preferenceRecheck);
        assertTrue(enable >= 0);
        assertTrue(pipelineEnable > enable);
        assertTrue(preferenceRecheck > pipelineEnable);
        assertTrue(rollback > preferenceRecheck);

        assertPersistedBeforeRuntimeDisable(
                api, "private static void handleDisable(", "private static void handlePrepareRestart(");
        assertPersistedBeforeRuntimeDisable(ipc, "case \"STOP\":", "case \"STATUS\":");
        assertPersistedBeforeRuntimeDisable(
                tcp, "case \"disableSurveillance\":", "case \"surveillanceStatus\":");
    }

    private static void assertPersistedBeforeRuntimeDisable(
            String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        int end = source.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(start >= 0);
        assertTrue(end > start);
        String body = source.substring(start, end);
        int persist = body.indexOf("setSurveillanceEnabled(false)");
        int disable = body.indexOf("CameraDaemon.disableSurveillance()");
        assertTrue(persist >= 0);
        assertTrue(disable > persist);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) {
                return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
