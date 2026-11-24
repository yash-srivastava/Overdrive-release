package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source contracts for demand-driven cabin listening and recording isolation. */
public class CabinAudioAssetContractTest {

    @Test
    public void oneCaptureOwnerServesRecordingAndListenerDemand()
            throws IOException {
        String capture = read(
                "app/src/main/java/com/overdrive/app/audio/"
                        + "AppAudioCaptureController.java");
        String overlay = read(
                "app/src/main/java/com/overdrive/app/overlay/"
                        + "StatusOverlayService.java");
        String service = read(
                "app/src/main/java/com/overdrive/app/services/"
                        + "CabinAudioCaptureService.java");

        assertTrue(capture.contains("private static AppAudioCaptureController owner"));
        assertTrue(capture.contains("setRecordingDemand(boolean wanted)"));
        assertTrue(capture.contains("setListenerDemand(boolean wanted)"));
        assertTrue(capture.contains(
                "current.recordForMuxing == recordingDemand"));
        assertTrue(capture.contains("LISTENER_CAPTURE_RATE = 8000"));
        assertTrue(capture.contains(
                "listenerDemand && listenerNavRoutingCompatibility"));
        assertTrue(capture.contains(
                "current.captureSampleRate == desiredCaptureRate"));
        assertTrue(capture.contains("normalizePcmRate(pcmBuf, n)"));
        assertTrue(capture.contains("MediaRecorder.AudioSource.MIC"));
        assertTrue(overlay.contains(".setRecordingDemand(shouldCapture)"));
        assertTrue(service.contains(
                "RemoteCommunicationSettings.AUDIO_CHANNEL_NAVIGATION"));
        assertTrue(service.contains(
                "AppAudioCaptureController.setListenerDemand(\n"
                        + "                            true, diPlusCompatibility)"));
        assertFalse(overlay.contains("new com.overdrive.app.audio.AppAudioCaptureController"));
    }

    @Test
    public void listenerOnlyModeAvoidsAacAndIdleModeStopsEverything()
            throws IOException {
        String capture = read(
                "app/src/main/java/com/overdrive/app/audio/"
                        + "AppAudioCaptureController.java");
        String listener = read(
                "app/src/main/java/com/overdrive/app/server/"
                        + "CabinAudioWebSocket.java");
        String browser = read(
                "app/src/main/assets/web/shared/cabin-audio.js");

        assertTrue(capture.contains("if (recordForMuxing) {\n"
                + "                currentStep = \"encoder\""));
        assertTrue(capture.contains("recordForMuxing ? 2 : 1"));
        assertTrue(capture.contains("if (!recordingDemand && !listenerDemand)"));
        assertTrue(capture.contains("stopOwnerLocked();"));
        assertTrue(listener.contains("ArrayBlockingQueue<PcmFrame>"));
        assertTrue(listener.contains("pool.offer(new PcmFrame"));
        assertTrue(listener.contains("droppedFrames++"));
        assertTrue(browser.contains("function cleanup()"));
        assertTrue(browser.contains("context.close()"));
        assertTrue(browser.contains("oldSocket.close"));
        assertFalse(browser.substring(0, browser.indexOf("function start()"))
                .contains("new WebSocket"));
    }

    @Test
    public void bothWebSurfacesUseTheSharedListenerAndPttIsHalfDuplex()
            throws IOException {
        String live = read(
                "app/src/main/assets/web/local/live-view.html");
        String communicate = read(
                "app/src/main/assets/web/local/communicate.html");
        String communicateJs = read(
                "app/src/main/assets/web/shared/communicate.js");
        String ptt = read(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationWebSocket.java");

        assertTrue(live.contains("id=\"cabinListenButton\""));
        assertTrue(live.contains("shared/cabin-audio.js"));
        assertTrue(live.contains("html.is-app-webview .cabin-listen-btn"));
        assertTrue(live.contains(
                "document.documentElement.classList.add('is-app-webview')"));
        assertTrue(live.contains("width: 124px"));
        assertTrue(communicate.contains("id=\"cabinListenerToggle\""));
        assertTrue(communicate.contains("shared/cabin-audio.js"));
        assertTrue(communicateJs.contains("CabinAudio.setLocalPaused(true)"));
        assertTrue(communicateJs.contains("CabinAudio.setLocalPaused(false)"));
        assertTrue(ptt.contains("CabinAudioWebSocket.setTalkActive(true)"));
        assertTrue(ptt.contains("CabinAudioWebSocket.setTalkActive(false)"));
    }

    @Test
    public void listenerEndpointIsAuthenticatedAndTokenLogsAreRedacted()
            throws IOException {
        String server = read(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String manifest = read("app/src/main/AndroidManifest.xml");

        assertTrue(server.contains("wsPathOnly.equals(\"/ws/cabin-audio\")"));
        assertTrue(server.contains(
                "AuthMiddleware.checkAuth(wsPathOnly"));
        assertTrue(server.contains("redactQueryToken(requestLine)"));
        assertTrue(manifest.contains(
                "android:name=\"com.overdrive.app.services.CabinAudioCaptureService\""));
        assertTrue(manifest.contains(
                "android:permission=\"android.permission.DUMP\""));
    }

    @Test
    public void pttPauseIsNonBlockingAndOrphanCaptureStopsRetrying()
            throws IOException {
        String listener = read(
                "app/src/main/java/com/overdrive/app/server/"
                        + "CabinAudioWebSocket.java");
        String service = read(
                "app/src/main/java/com/overdrive/app/services/"
                        + "CabinAudioCaptureService.java");
        int pauseStart = listener.indexOf("void setTalkPaused(boolean paused)");
        int pauseEnd = listener.indexOf("void requestStop", pauseStart);
        String pauseMethod = listener.substring(pauseStart, pauseEnd);

        assertTrue(pauseMethod.contains("controlPending = true"));
        assertTrue(pauseMethod.contains("worker.interrupt()"));
        assertTrue(pauseMethod.contains("lastSendProgressAt = now"));
        assertFalse(pauseMethod.contains("sendJson("));
        assertTrue(listener.contains("CLIENT_STALL_TIMEOUT_MS"));
        assertTrue(listener.contains("lastSendProgressAt"));
        assertTrue(service.contains("MAX_UNAVAILABLE_MS"));
        assertTrue(service.contains("stopSelf();"));
    }

    private static String read(String relative) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule =
                    current.resolve(relative.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relative);
    }
}
