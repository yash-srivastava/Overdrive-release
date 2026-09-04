package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Guards the BYD WebView camera-switch regression where an already-open
 * WebSocket continued delivering video but the top-left indicator stayed on
 * "Connecting..." because no second onopen event was emitted.
 */
public class LiveViewConnectionStateAssetTest {

    @Test
    public void frameDeliveryPromotesBothDecoderPathsToLive() throws Exception {
        String stream = readAsset("shared/stream.js");
        String liveView = readAsset("local/live-view.html");

        assertTrue(stream.contains("noteFrameReceived(count)"));
        assertTrue(stream.contains("this.noteFrameReceived();"));
        assertTrue(stream.contains("this.noteFrameReceived(count);"));
        assertTrue(liveView.contains("if (sotaLive || legacyLive)"));
        assertTrue(liveView.contains(
                "this.sotaPlayer.onFrame = (count) => this.noteFrameReceived(count);"));
        assertTrue(liveView.contains("this.markStreamConnecting();"));
    }

    /**
     * The indicator carries no text, so its state reaches assistive tech
     * through aria-label. Clearing the stage on 'live' must not depend on the
     * indicator being in the DOM.
     */
    @Test
    public void streamIndicatorIsALabelledDotAndStageClearingIsIndependent() throws Exception {
        String liveView = readAsset("local/live-view.html");

        assertTrue(liveView.contains("data-i18n-attr=\"aria-label:pip.select_camera\""));
        assertFalse(liveView.contains("class=\"status-text\""));
        assertTrue(liveView.contains("el.setAttribute('aria-label', BYD.i18n.t(labelKey));"));
        assertTrue(liveView.contains("if (status === 'live') this.setStagePhase('live');"));
    }

    private static String readAsset(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path fromModule = current.resolve("src/main/assets/web").resolve(relativePath);
        if (Files.exists(fromModule)) {
            return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
        }
        Path fromRepository = current.resolve("app/src/main/assets/web").resolve(relativePath);
        if (Files.exists(fromRepository)) {
            return new String(Files.readAllBytes(fromRepository), StandardCharsets.UTF_8);
        }
        throw new AssertionError("Could not locate web asset: " + relativePath);
    }
}
