package com.overdrive.app.camera;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Pins the WebCodecs live-view player's recovery contract.
 *
 * <p>Per the WebCodecs spec a decode error closes the VideoDecoder BEFORE the
 * error callback runs, so reset()/configure() on that instance throw and no
 * decoder was ever rebuilt: the tunnel viewer sat on a frozen frame and then
 * fell back to software decode. The player must drop the faulted instance
 * and build a fresh one, must not carry a decoder across reconnects, and
 * must notice a mid-stream stall (encoder retired, dead tunnel link) instead
 * of showing "Live" for the server's 60 s idle exit.
 *
 * <p>The file is also loaded (never instantiated) by live-view.html on the
 * in-car Chrome 58 WebView, so it has to keep parsing as ES2015.
 */
public class SotaPlayerRecoveryAssetTest {

    private static final String PLAYER = "app/src/main/assets/web/shared/SotaPlayer.js";
    private static final String LIVE_VIEW = "app/src/main/assets/web/local/live-view.html";

    @Test
    public void handleErrorDropsTheClosedDecoderAndRebuildsIt() throws Exception {
        String js = stripComments(readRepositoryFile(PLAYER));
        String handleError = method(js, "handleError(e) {");

        int nullOut = handleError.indexOf("this.decoder = null;");
        int close = handleError.indexOf(".close();");
        int rebuild = handleError.indexOf("this.initDecoder(");
        assertTrue("handleError must release the faulted decoder", nullOut >= 0);
        assertTrue("handleError must close() the faulted decoder", close >= 0);
        assertTrue("handleError must build a fresh decoder", rebuild > nullOut);
        assertTrue("rebuild reuses the SPS-derived codec string",
                handleError.contains("this.extractCodecFromSPS(this.sps)"));
        assertTrue(handleError.contains("this.hasReceivedKeyframe = false;"));
        assertFalse("reset() on a closed decoder throws; it must not be the recovery path",
                handleError.contains("this.decoder.reset()"));
        // The 6th error still escalates to onError so a working decoder can take over.
        assertTrue(handleError.contains("if (this.decodeErrors > 5) {"));
        assertTrue(handleError.contains("this._fail("));
    }

    @Test
    public void reconnectNeverReusesTheOldDecoder() throws Exception {
        String js = stripComments(readRepositoryFile(PLAYER));
        String onopen = js.substring(js.indexOf("this.ws.onopen = () => {"), js.indexOf("this.ws.onmessage"));
        int close = onopen.indexOf("this.decoder.close();");
        int nullOut = onopen.indexOf("this.decoder = null;");
        int init = onopen.indexOf("this.initDecoder();");
        assertTrue(close >= 0 && nullOut > close && init > nullOut);
        assertTrue(onopen.contains("this._armStallWatchdog();"));
    }

    @Test
    public void runningStreamStallWatchdogIsEightSecondsAndClearedOnEveryExit() throws Exception {
        String js = stripComments(readRepositoryFile(PLAYER));
        assertTrue(js.contains("SotaPlayer.STALL_TIMEOUT_MS = 8000;"));

        String watchdog = method(js, "_armStallWatchdog() {");
        assertTrue("fires only after frames have flowed", watchdog.contains("if (this.frameCount === 0) return;"));
        assertTrue("only while the socket is open", watchdog.contains("this.ws.readyState !== WebSocket.OPEN"));
        assertTrue(watchdog.contains("idleMs < SotaPlayer.STALL_TIMEOUT_MS) return;"));
        assertTrue("closes the socket so onclose runs the backoff reconnect", watchdog.contains("this.ws.close();"));

        assertTrue(method(js, "handleFrame(frame) {").contains("this._lastFrameAt = SotaPlayer._now();"));
        assertTrue(method(js, "stop() {").contains("this._clearStallWatchdog();"));
        assertTrue(method(js, "_suspendForHidden() {").contains("this._clearStallWatchdog();"));
        String onclose = js.substring(js.indexOf("this.ws.onclose = () => {"), js.indexOf("this.ws.onerror"));
        assertTrue(onclose.contains("this._clearStallWatchdog();"));
    }

    @Test
    public void playerStaysParseableByTheChrome58WebView() throws Exception {
        String js = stripComments(readRepositoryFile(PLAYER));
        assertFalse("optional chaining is ES2020", js.contains("?."));
        assertFalse("nullish coalescing is ES2020", js.contains("??"));
        assertFalse("optional catch binding is ES2019",
                Pattern.compile("catch\\s*\\{").matcher(js).find());
        assertFalse("String.prototype.replaceAll is ES2021", js.contains("replaceAll("));
        assertFalse("class fields are ES2022",
                Pattern.compile("(?m)^\\s*static\\s+[A-Za-z_$][\\w$]*\\s*=").matcher(js).find());
        assertFalse("private class members are ES2022",
                Pattern.compile("(?m)^\\s*#[A-Za-z_$]").matcher(js).find());
        assertFalse("async/await is ES2017",
                Pattern.compile("\\basync\\b|\\bawait\\b").matcher(js).find());
        assertFalse("object spread is ES2018",
                Pattern.compile("\\{\\s*\\.\\.\\.").matcher(js).find());
        assertFalse("BigInt literals are ES2020",
                Pattern.compile("\\b\\d+n\\b").matcher(js).find());
    }

    @Test
    public void liveViewLoadsTheRecoveringPlayerBuild() throws Exception {
        String liveView = readRepositoryFile(LIVE_VIEW);
        assertTrue(liveView.contains("<script src=\"../shared/SotaPlayer.js?v=16\"></script>"));
    }

    /** Body of a class method from its header to the next method at the same indent. */
    private static String method(String js, String header) {
        int start = js.indexOf(header);
        assertTrue("missing " + header, start >= 0);
        int end = js.indexOf("\n        }\n", start);
        assertTrue(end > start);
        return js.substring(start, end);
    }

    private static String stripComments(String js) {
        String noBlocks = js.replaceAll("(?s)/\\*.*?\\*/", "");
        // Full-line and " // " trailing comments; "://" inside strings survives.
        return noBlocks.replaceAll("(?m)^\\s*//.*$", "").replaceAll("(?m)\\s//\\s.*$", "");
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
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
