package com.overdrive.app.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Guards first-load locking, ordered writes, and failed-toggle reconciliation. */
public class WebSettingsToggleReliabilityContractTest {

    @Test
    public void immediateSavePagesLockUntilHydratedAndSerializeWrites() throws IOException {
        assertPageLock("recording.html", "recording.js?v=budget16");
        assertPageLock("road-sense.html", "road-sense.js?v=chimevolume8");
        assertPageLock("trips.html", "trips.js?v=history13");
        assertPageLock("abrp.html", "abrp.js?v=toggle3");
        assertPageLock("surveillance.html", "surveillance.js?v=survtoggle10");
        assertPageLock("byd-cloud.html", "surveillance.js?v=survtoggle10");
        assertTrue(read("app/src/main/assets/web/local/live-view.html").contains(
                "surveillance.js?v=survtoggle10"));
        assertTrue(read("app/src/main/assets/web/local/notifications.html").contains(
                "surveillance.js?v=survtoggle10"));

        String hydration = read("app/src/main/assets/web/shared/settings-hydration.js");
        assertTrue(hydration.contains("event.stopImmediatePropagation();"));
        assertTrue(hydration.contains("root.removeAttribute('inert');"));

        String recording = read("app/src/main/assets/web/shared/recording.js");
        assertOrdered(recording);
        assertTrue(recording.contains("_cdrWritesPending"));
        assertTrue(recording.contains("_cdrDirty"));
        assertTrue(recording.contains("writeVersion !== this._cdrWriteVersion"));
        assertTrue(recording.contains("_enqueueWrite(() => this._saveSettingsNow())"));
        assertTrue(recording.contains("_postTelemetry(body)"));
        assertTrue(recording.contains("_layoutWritePending"));
        assertTrue(recording.contains("_layoutWriteVersion"));
        assertTrue(recording.contains("_audioWriteVersion"));
        assertTrue(recording.contains("_geocodingWriteVersion"));
        assertTrue(recording.contains("_cdrReady"));
        assertTrue(recording.contains("_cdrCleanupPending"));
        assertTrue(recording.contains("Object.keys(this._cdrDirty).length > 0"));
        assertTrue(recording.contains("if (!saved) this._cdrReady = false;"));
        assertTrue(recording.contains("if (data.cleanupEnabled != null)"));
        assertTrue(recording.contains("!this._hydrated || !this._cdrReady"));
        assertTrue(recording.contains(
                "!this._cdrReady || !this.cdrConfig.enabled"));
        assertTrue(recording.contains(
                "typeof data.protectedHours === 'number'"));
        assertTrue(recording.contains(
                "typeof data.minFilesKeep === 'number'"));
        assertTrue(!recording.contains("data.protectedHours || 24"));
        assertTrue(!recording.contains("data.minFilesKeep || 10"));
        assertTrue(recording.contains("data.success !== true"));
        String recordingHtml = read("app/src/main/assets/web/local/recording.html");
        assertTrue(recordingHtml.contains("id=\"cdrCleanupEnabled\" disabled"));
        assertTrue(recordingHtml.contains("id=\"cdrReservedSlider\" disabled"));
        assertTrue(recordingHtml.contains("id=\"cdrProtectedSlider\" disabled"));
        assertTrue(recordingHtml.contains("id=\"cdrMinKeepSlider\" disabled"));
        assertTrue(recordingHtml.contains(
                "id=\"cdrMinKeepSlider\" disabled min=\"0\" max=\"100\""));
        assertTrue(recordingHtml.contains("id=\"cdrCleanupNow\" disabled"));

        String roadSense = read("app/src/main/assets/web/shared/road-sense.js");
        assertOrdered(roadSense);
        assertTrue(roadSense.contains("_writesPending"));
        assertTrue(roadSense.contains("_writing = this._writesPending > 0;"));
        assertTrue(roadSense.contains("_saveSection(section, delta)"));
        assertTrue(roadSense.contains("this.updateUI(false);"));

        String abrp = read("app/src/main/assets/web/shared/abrp.js");
        assertOrdered(abrp);
        assertTrue(abrp.contains("_postConfig(data)"));
        assertTrue(abrp.contains("toggle.disabled = true;"));
        assertTrue(abrp.contains("result.success !== true"));

        String trips = read("app/src/main/assets/web/shared/trips.js");
        assertOrdered(trips);
        assertTrue(trips.contains("_postJson(url, body)"));
        assertTrue(trips.contains("_setCdrEnabled(enabled)"));
        assertTrue(trips.contains("saveCdrConfig()"));
        assertTrue(trips.contains("_cdrDirty"));
        assertTrue(trips.contains("_applyCdrConfig(data)"));
        assertTrue(trips.contains("_cdrReady"));
        assertTrue(trips.contains("_cdrCleanupPending"));
        assertTrue(trips.contains("Object.keys(this._cdrDirty).length > 0"));
        assertTrue(trips.contains("if (!saved) this._cdrReady = false;"));
        assertTrue(trips.contains("if (data.cleanupEnabled != null)"));
        assertTrue(trips.contains("!this._hydrated || !this._cdrReady"));
        assertTrue(trips.contains(
                "!this._cdrReady || !this.cdrCleanupEnabled"));
        assertTrue(trips.contains("data.filesDeleted"));
        assertTrue(!trips.contains("data.deletedCount"));
        assertTrue(trips.contains(
                "data.cdrFileCount == null ? '--' : data.cdrFileCount"));
        assertTrue(trips.contains(
                "data.totalFilesDeleted == null ? '--' : data.totalFilesDeleted"));
        assertTrue(trips.contains("data.success !== true"));
        String tripsHtml = read("app/src/main/assets/web/local/trips.html");
        assertTrue(tripsHtml.contains("id=\"tripCdrEnabled\" disabled"));
        assertTrue(tripsHtml.contains("id=\"tripCdrReservedSlider\" disabled"));
        assertTrue(tripsHtml.contains("id=\"tripCdrProtectedSlider\" disabled"));
        assertTrue(tripsHtml.contains("id=\"tripCdrMinKeepSlider\" disabled"));
        assertTrue(tripsHtml.contains(
                "id=\"tripCdrMinKeepSlider\" disabled min=\"0\" max=\"100\""));
        assertTrue(tripsHtml.contains("id=\"tripCdrCleanupNow\" disabled"));

        String surveillance = read("app/src/main/assets/web/shared/surveillance.js");
        assertOrdered(surveillance);
        assertTrue(surveillance.contains("_cdrWritesPending"));
        assertTrue(surveillance.contains("_cdrDirty"));
        assertTrue(surveillance.contains("_writeJson(url, body)"));
        assertTrue(surveillance.contains("_enqueueWrite(() => this._applySettingsNow())"));
        assertTrue(surveillance.contains("_nextImmediateWrite(key)"));
        assertTrue(surveillance.contains("_mergeWriteQueue: Promise.resolve()"));
        assertTrue(surveillance.contains("_mergeWriteVersion"));
        assertTrue(surveillance.contains("mergeWriteVersion === this._mergeWriteVersion"));

        String bydCloud = read("app/src/main/assets/web/local/byd-cloud.html");
        assertTrue(bydCloud.contains(
                "id=\"bydCloudMergeToggle\" disabled onchange=\"BydCloud.toggleCloudDataMerge(this.checked)\""));

        String notifications = read("app/src/main/assets/web/local/notifications.html");
        assertTrue(notifications.contains(
                "id=\"v2TelegramSendStartPing\" disabled onchange=\"SurvSettings.updateTelegramStartPing()\""));
        assertTrue(notifications.contains(
                "id=\"v2TelegramNotices\" disabled onchange=\"SurvSettings.updateTelegramTiers()\""));
        assertTrue(surveillance.contains("_geocodingWriteVersion"));
        assertTrue(surveillance.contains("_layoutWriteVersion"));
        assertTrue(surveillance.contains("_telemetryWriteVersion"));
    }

    @Test
    public void slowCdrStatusDoesNotHoldRecordingOrTripsHydration() throws IOException {
        assertCdrLoadsAfterHydration(
                read("app/src/main/assets/web/shared/recording.js"),
                "async reloadConfig()",
                "this.updateCdrCleanupVisibility();");
        assertCdrLoadsAfterHydration(
                read("app/src/main/assets/web/shared/trips.js"),
                "_startStorageRefresh()",
                "this.loadCdrInfo();");
    }

    @Test
    public void safeLocationsStayDisabledUntilLoadedAndBlockDuplicateWrites() throws IOException {
        String html = read("app/src/main/assets/web/local/surveillance.html");
        String script = read("app/src/main/assets/web/shared/safe-locations.js");

        assertTrue(html.contains(
                "id=\"safeLocEnabled\" disabled onchange=\"SafeLocations.toggleFeature()\""));
        assertTrue(html.contains("safe-locations.js?v=4"));
        assertOrdered(script);
        assertTrue(script.contains("this.ready = await this.loadData();"));
        assertTrue(script.contains("if (!this.ready) {"));
        assertTrue(script.contains("toggle.disabled = !this.ready || this.togglePending;"));
        assertTrue(script.contains("zoneTogglePending"));
    }

    private static void assertPageLock(String page, String scriptVersion) throws IOException {
        String html = read("app/src/main/assets/web/local/" + page);
        assertTrue(page, html.contains(
                "<main class=\"main-content\" aria-busy=\"true\" inert style=\"pointer-events:none\">"));
        assertTrue(page, html.contains("settings-hydration.js?v=1"));
        assertTrue(page, html.contains(scriptVersion));
    }

    private static void assertOrdered(String script) {
        assertTrue(script.contains("_writeQueue: Promise.resolve()"));
        assertTrue(script.contains("_enqueueWrite(task)"));
        assertTrue(script.contains("this._writeQueue.then(run, run)"));
    }

    private static void assertCdrLoadsAfterHydration(
            String script, String initEndNeedle, String cdrLoadNeedle) {
        int initStart = script.indexOf("async init() {");
        int initEnd = script.indexOf(initEndNeedle, initStart);
        assertTrue(initStart >= 0);
        assertTrue(initEnd > initStart);
        String init = script.substring(initStart, initEnd);

        assertTrue(!init.contains("await " + cdrLoadNeedle));
        int hydrated = init.indexOf("this._hydrated = true;");
        int unlock = init.indexOf("unlockSettingsHydration");
        int cdrLoad = init.lastIndexOf(cdrLoadNeedle);
        assertTrue(hydrated >= 0);
        assertTrue(unlock > hydrated);
        assertTrue(cdrLoad > unlock);
    }

    private static String read(String relativePath) throws IOException {
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
