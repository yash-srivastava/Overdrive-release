package com.overdrive.app.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards the RoadSense volume control's browser-side contract. */
public class RoadSenseChimeVolumeAssetTest {

    @Test
    public void sliderIsNamedBoundedAndCacheBusted() throws IOException {
        String html = readRepositoryFile("app/src/main/assets/web/local/road-sense.html");

        assertTrue(html.contains("id=\"rsWarnVolumeLabel\""));
        assertTrue(html.contains("min=\"10\" max=\"100\" step=\"5\""));
        assertTrue(html.contains(
                "aria-labelledby=\"rsWarnVolumeLabel rsWarnVolumeValue\""));
        assertTrue(html.contains(
                "onchange=\"RoadSenseSettings.commitWarnVolume(this.value)\""));
        assertTrue(html.contains("class=\"slider-track-control\""));
        assertTrue(html.contains("styles.css?v=36"));
        assertTrue(html.contains("road-sense.js?v=chimevolume7"));
    }

    @Test
    public void testUsesSevereCeilingAndFlushesConfirmedVolume() throws IOException {
        String script = readRepositoryFile("app/src/main/assets/web/shared/road-sense.js");

        int testStart = script.indexOf("async testChime()");
        int testEnd = script.indexOf("async setWarnMode", testStart);
        String testChime = script.substring(testStart, testEnd);
        assertTrue(testChime.contains("await this._flushWarnVolumeSave();"));
        assertTrue(testChime.contains("severity: 'severe'"));
        assertTrue(testChime.contains("volumePercent: volume"));

        assertTrue(script.contains("this._warnVolumeConfirmed = value;"));
        assertTrue(script.contains("this.config.warnAudioVolume = confirmed;"));
        assertTrue(script.contains("navigator.sendBeacon('/api/settings/unified', payload)"));
        assertTrue(script.contains("self._flushWarnVolumeOnExit();"));

        int exitFlushStart = script.indexOf("    _flushWarnVolumeOnExit() {");
        int exitFlushEnd = script.indexOf("// Detection-sensitivity multiplier", exitFlushStart);
        String exitFlush = script.substring(exitFlushStart, exitFlushEnd);
        assertTrue(exitFlush.contains(
                "if (value == null || sequence == null || this._warnVolumeExitFlushed) return;"));
        assertTrue(exitFlush.contains("this._warnVolumeExitFlushed = true;"));
        assertTrue(exitFlush.contains("this._flushWarnVolumeSave();"));
        assertFalse(exitFlush.contains("this._warnVolumePending = null;"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
