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

    @Test
    public void dilink5BroadwayUsesBoundedRecycledDecodePath() throws Exception {
        String stream = readAsset("shared/stream.js");
        String liveView = readAsset("local/live-view.html");

        assertTrue(stream.contains("?decoder=broadway"));
        assertTrue(stream.contains("reuseMemory: this.isDiLink5 === true"));
        assertTrue(stream.contains("this.isDiLink5 = data.dilink5 === true"));
        assertTrue(liveView.contains("stream.js?v=index9"));
        // All three Broadway assets must bypass the one-hour shared-JS cache.
        assertTrue(liveView.contains("YUVCanvas.js?v=2"));
        assertTrue(liveView.contains("Player.js?v=2"));
        assertTrue(stream.contains("forceBroadway: new URLSearchParams"));
        assertTrue(stream.contains("if (this.forceBroadway) return await this.initBroadway()"));
        assertTrue(liveView.contains("if (!this.forceBroadway && 'VideoDecoder' in window"));
        assertTrue(stream.contains("reusePlaneTextures: this.reusePlaneTextures === true"));
        assertTrue(liveView.contains("BYD.stream.cycleBroadwayAb = function()"));
        assertTrue(liveView.contains("id=\"broadwayAbButton\""));
    }

    @Test
    public void optionalTextureReuseLeavesLegacyGlCallsInPlace() throws Exception {
        String canvas = readAsset("shared/YUVCanvas.js");
        String player = readAsset("shared/Player.js");

        assertTrue(canvas.contains("this.reusePlaneTextures = parOptions.reusePlaneTextures === true"));
        assertTrue(canvas.contains("if (!this.reusePlaneTextures)"));
        assertTrue(canvas.contains("size.reuseFrames < 29"));
        assertTrue(canvas.contains("gl.texSubImage2D("));
        assertTrue(canvas.contains("gl.texImage2D("));
        assertTrue(player.contains("reusePlaneTextures: this._config.reusePlaneTextures === true"));
        assertTrue(player.contains("benchmarkPlaneTextures"));
    }

    @Test
    public void jmuxerUsesTheSelectedPresetFps() throws Exception {
        String stream = readAsset("shared/stream.js");
        String liveView = readAsset("local/live-view.html");

        assertTrue(stream.contains("fps: this.activeFps || 10"));
        assertTrue(liveView.contains("if (qData.fps) this.activeFps = qData.fps"));
        assertTrue(liveView.contains("BYD.stream.activeFps = preset.fps"));
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
