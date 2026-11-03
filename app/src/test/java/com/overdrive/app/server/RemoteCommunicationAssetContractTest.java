package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level ownership and lifecycle guards for the Communicate feature. */
public class RemoteCommunicationAssetContractTest {

    @Test
    public void microphoneAndRealtimeResourcesArePressScoped() throws IOException {
        String page = readRepositoryFile(
                "app/src/main/assets/web/local/communicate.html");
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");

        assertTrue(page.contains("id=\"pttButton\""));
        assertTrue(page.contains("maxlength=\"200\""));
        assertTrue(page.contains("role=\"tablist\""));
        assertTrue(page.contains("id=\"talkStateGuidance\""));
        assertTrue(page.contains("id=\"messageReadiness\""));

        String initialization = between(script, "init: function () {", "bindTalk:");
        assertFalse(initialization.contains("getUserMedia"));
        assertFalse(initialization.contains("new WebSocket"));
        assertFalse(initialization.contains("new AudioContext"));
        assertFalse(initialization.contains("setInterval"));

        String press = between(script, "onPressStart: function", "onPressEnd:");
        assertTrue(press.contains("media.getUserMedia"));
        assertTrue(script.contains("button.addEventListener('pointerdown'"));
        assertTrue(script.contains("window.addEventListener('pointerup'"));
        assertTrue(script.contains("window.addEventListener('pointercancel'"));
        assertTrue(script.contains("setPointerCapture(event.pointerId)"));
        assertTrue(script.contains("'lostpointercapture'"));
        assertTrue(script.contains(
                "event.pointerId !== this.activePointerId"));
    }

    @Test
    public void everyInactivePathTearsDownCommunicationResources() throws IOException {
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");

        assertTrue(script.contains("document.addEventListener('visibilitychange'"));
        assertTrue(script.contains("window.addEventListener('pagehide'"));
        assertTrue(script.contains("window.addEventListener('pageshow'"));
        assertTrue(script.contains("window.addEventListener('beforeunload'"));
        assertTrue(script.contains("self.stopTalk('Navigation', true)"));
        assertTrue(script.contains("self.stopTalk(self.tt('communicate.limit_30s',"
                + " '30 second limit reached'))"));
        assertTrue(script.contains(
                "self.stopTalk(self.tt('communicate.mic_inactive',"
                        + " 'Microphone stream became inactive'))"));
        assertTrue(script.contains("MAX_SESSION_MS: 30000"));
        assertTrue(script.contains("AUDIO_INACTIVITY_MS: 3000"));
        assertTrue(script.contains("now - this.lastMeterAt >= 100"));

        String stop = between(script, "stopTalk: function", "failTalk:");
        assertTrue(stop.contains("this.stopTalkTicker()"));
        assertTrue(stop.contains("socket.close"));
        assertTrue(stop.contains("this.disconnectCaptureGraph()"));
        assertTrue(stop.contains("this.audioContext.close()"));
        assertTrue(stop.contains("this.stopTracks(this.stream)"));
        assertTrue(stop.contains("this.setMeter(0)"));
        assertFalse(script.contains("requestAnimationFrame"));
    }

    @Test
    public void inactiveStatesAvoidHighFrequencyStatusAndPermissionWork()
            throws IOException {
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationApiHandler.java");
        String websocket = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationWebSocket.java");

        assertTrue(script.contains("INACTIVE_STATUS_POLL_MS: 30000"));
        assertTrue(script.contains("statusPollDelay: function"));
        assertTrue(script.contains("statusGeneration"));
        assertTrue(script.contains("isStatusPollingCurrent(generation)"));
        assertTrue(script.contains("!this.pressing"));
        assertTrue(script.contains(
                "BYDAuth.fetch('/api/communicate/status', options)"));
        assertTrue(script.contains("status.carState === 'off'"));
        assertTrue(script.contains(
                "audioInactive && listenerInactive && messagesInactive"));
        assertTrue(api.contains("shouldCheckAnyOverlay("));
        assertTrue(api.contains("shouldCheckMessageOverlay("));
        assertTrue(websocket.contains("shouldCheckVoiceOverlay("));
    }

    @Test
    public void mobileMessageActionsUseRemainingViewportAndSafeArea()
            throws IOException {
        String page = readRepositoryFile(
                "app/src/main/assets/web/local/communicate.html");
        String styles = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.css");
        String mobile = between(
                styles, "@media (max-width: 700px)", "@media (max-width: 430px)");

        assertTrue(page.contains("communicate.css?v=6"));
        assertTrue(styles.contains("height: calc(var(--vh, 1vh) * 100)"));
        assertTrue(mobile.contains("height: auto"));
        assertTrue(mobile.contains("flex: 1 1 auto"));
        assertTrue(mobile.contains("env(safe-area-inset-bottom, 0px)"));
        assertTrue(mobile.contains("overflow-y: auto"));
    }

    @Test
    public void receiverAndSettingsStayInsideTheirDedicatedSurfaces()
            throws IOException {
        String server = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/RemoteVoiceService.java");
        String overlaySettings = readRepositoryFile(
                "app/src/main/res/layout/fragment_settings_overlay.xml");
        String portraitSettings = readRepositoryFile(
                "app/src/main/res/layout/fragment_settings.xml");
        String manifest = readRepositoryFile(
                "app/src/main/AndroidManifest.xml");

        assertTrue(server.contains("\"/ws/communicate\""));
        assertTrue(server.contains("\"local/communicate.html\""));
        assertTrue(receiver.contains("FLAG_NOT_FOCUSABLE"));
        assertTrue(receiver.contains("attachRoadSenseDuckTarget"));
        assertTrue(receiver.contains("updateOverlayVisibility"));
        assertTrue(readRepositoryFile(
                "app/src/main/java/com/overdrive/app/communication/"
                        + "RemoteVoiceBridge.java").contains(
                "startSafetyWatcher()"));
        assertTrue(overlaySettings.contains(
                "@layout/include_remote_communication_settings"));
        assertTrue(portraitSettings.contains(
                "@layout/include_remote_communication_settings"));
        assertTrue(manifest.contains(
                "android:name=\"com.overdrive.app.overlay.MessageOverlayService\""));
        assertTrue(manifest.contains(
                "android:permission=\"android.permission.DUMP\""));
    }

    @Test
    public void pttForwardsPcmFramesDirectlyToTheCarAudioTrack()
            throws IOException {
        String browser = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String worklet = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate-worklet.js");
        String websocket = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationWebSocket.java");
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/communication/"
                        + "RemoteVoiceBridge.java");
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/"
                        + "RemoteVoiceService.java");

        assertTrue(browser.contains("this.socket.send(pcmBuffer)"));
        assertTrue(browser.contains("new window.AudioWorkletNode"));
        assertTrue(browser.contains("startScriptProcessorPipeline"));
        assertTrue(browser.contains("onprocessorerror"));
        assertTrue(browser.contains("MAX_CAPTURE_AGE_MS: 250"));
        String activation = between(
                browser, "activateAudioPipeline: function", "handleCapturedPcm:");
        assertTrue(activation.contains(
                "self.audioContext.state !== 'running'"));
        assertTrue(activation.contains("self.audioClockOffsetMs ="));
        assertTrue(worklet.contains(
                "registerProcessor("));
        assertTrue(worklet.contains(
                "'remote-voice-capture'"));
        assertTrue(websocket.contains("bridge.sendPcm(frame.payload)"));
        assertTrue(bridge.contains("RemoteVoiceProtocol.writePcm(audioOutput, pcm)"));
        assertTrue(receiver.contains(
                "enqueueRemotePcm(session, packet.payload)"));
        assertTrue(receiver.contains("RemoteVoiceJitterBuffer"));
        assertTrue(receiver.contains("PLAYBACK_QUEUE_MS = 200"));
        assertTrue(receiver.contains("keepalivePcmBuffer"));
        assertTrue(receiver.contains("track.write("));
        assertTrue(websocket.contains("PTT network stats: frames="));
        assertTrue(websocket.contains("NETWORK_GAP_WARN_MS"));
        assertTrue(websocket.contains("catch (IdleReadTimeout timeout)"));
        assertTrue(websocket.contains(
                "class IdleReadTimeout extends SocketTimeoutException"));
        assertFalse(browser.contains("MediaRecorder"));
        assertFalse(browser.contains("Blob("));
    }

    @Test
    public void liveVoiceRequiresUnlockedCaptureAndStartedCarPlayback()
            throws IOException {
        String browser = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/"
                        + "RemoteVoiceService.java");
        String websocket = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationWebSocket.java");

        String press = between(browser, "onPressStart: function", "onPressEnd:");
        assertTrue(press.contains("this.prepareAudioContext(generation)"));
        assertTrue(browser.contains("self.audioContext.state !== 'running'"));
        assertTrue(browser.contains("self.markTalkLive()"));
        assertTrue(browser.contains("self.audioContext === context"));
        assertTrue(browser.contains("self.socket === socket"));
        assertTrue(browser.contains("generation === self.generation"));
        assertTrue(browser.contains(
                "generation !== self.statusGeneration"));

        String remoteSession = between(
                receiver,
                "runRemoteSession(",
                "private synchronized boolean attachBridgeSocket");
        int playbackPreparation =
                remoteSession.indexOf("prepareRemoteAudio(session)");
        int readyHandshake = remoteSession.indexOf("sendHandshake(");
        assertTrue(playbackPreparation >= 0);
        assertTrue(readyHandshake > playbackPreparation);
        assertTrue(receiver.contains("startAudioPlayback();"));
        assertTrue(receiver.contains(
                "track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING"));
        assertTrue(receiver.contains("primeAudioTrack(track)"));
        assertTrue(receiver.indexOf("primeAudioTrack(track)")
                < receiver.indexOf("track.play()"));
        assertTrue(receiver.contains("AudioTrack.WRITE_NON_BLOCKING"));
        assertTrue(receiver.contains("track.getUnderrunCount()"));
        assertTrue(receiver.contains("startMediaRouteKeeper()"));
        assertTrue(receiver.contains(
                "MediaPlaybackService.applyChannelRouting(keeper, outputChannel)"));
        assertTrue(receiver.contains("keeper.setVolume(0f, 0f)"));
        assertTrue(receiver.contains(
                "MediaPlaybackService.streamForChannel(outputChannel)"));
        assertTrue(receiver.contains("sendDiagnostic("));
        int audioSetup = receiver.indexOf("private void initStreamingAudio()");
        assertTrue(receiver.indexOf("startMediaRouteKeeper();", audioSetup)
                < receiver.indexOf("audioTrack = new AudioTrack(", audioSetup));
        assertTrue(receiver.contains(
                "enqueueRemotePcm(session, packet.payload)"));
        assertTrue(receiver.contains("startPlaybackWorker(session)"));
        assertTrue(receiver.contains("AUDIOFOCUS_REQUEST_GRANTED"));
        assertTrue(receiver.contains("continuing without focus"));
        String focusHandler = between(
                receiver,
                "handleAudioFocusChange(",
                "private void abandonAudioFocus");
        assertTrue(focusHandler.contains(
                "generation.get() != focusSession"));
        assertTrue(focusHandler.contains("!running.get()"));
        assertTrue(receiver.contains(
                "AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"));
        assertTrue(receiver.contains("calculateOutputDrainMs("));
        assertTrue(receiver.contains("track.getBufferSizeInFrames()"));
        assertTrue(receiver.contains("buffer.finish()"));
        assertTrue(receiver.contains("while (offset < byteCount)"));
        assertTrue(receiver.contains("AudioFormat.CHANNEL_OUT_STEREO"));
        assertTrue(receiver.contains("getNativeOutputSampleRate"));
        assertTrue(websocket.contains("maxPcmPeak"));
        assertTrue(websocket.contains("\"Receiver \" + control.substring(5)"));
    }

    @Test
    public void normalReleaseDrainsButSafetyStopsRemainImmediate()
            throws IOException {
        String websocket = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationWebSocket.java");
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/communication/"
                        + "RemoteVoiceBridge.java");
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/"
                        + "RemoteVoiceService.java");

        assertTrue(websocket.contains(
                "drainAudio = isGracefulStopReason(stopReason)"));
        assertTrue(websocket.contains("return drainAudio"));
        assertTrue(bridge.contains(
                "RemoteVoiceProtocol.writeEnd(audioOutput, drain)"));
        assertFalse(bridge.contains("RemoteVoiceController.stop()"));
        assertTrue(bridge.contains("\"RemoteVoiceSafety\""));
        assertTrue(bridge.contains("Thread.sleep(SAFETY_REFRESH_MS)"));
        assertTrue(bridge.indexOf(
                "VehicleCommunicationSafety.isRemoteVoiceOverlaySafe()")
                < bridge.indexOf(
                "synchronized (writeLock)",
                bridge.indexOf("sendOverlaySafety(boolean force)")));
        assertTrue(bridge.contains("HANDSHAKE_TIMEOUT_MS = 5_000"));
        assertTrue(receiver.contains("finishSession(session, drainAudio)"));
        assertTrue(receiver.contains(
                "generation.get() != completedGeneration || running.get()"));
        assertTrue(receiver.contains(
                "private synchronized void failPlaybackSession"));
        assertTrue(receiver.contains(
                "private synchronized void handleAudioFocusChange"));
        assertTrue(receiver.contains(
                "PLAYBACK_QUEUE_MS + KEEPALIVE_PCM_MS + 100"));
        assertTrue(receiver.contains(
                "stopSessionResources();\n        removeOverlay();"));
        assertTrue(receiver.contains(
                "if (!running.get() || generation.get() != session) break"));
        assertTrue(receiver.contains("playbackSession != session"));
        assertTrue(receiver.contains("synchronized (audioWriteLock)"));
        assertTrue(receiver.contains("buffer.finish()"));
        assertTrue(receiver.contains("buffer.close()"));
        assertTrue(receiver.contains("track.pause()"));
        assertTrue(receiver.contains("track.flush()"));
    }

    @Test
    public void statusDistinguishesCarOffFromUnreachableAndSetupRequired()
            throws IOException {
        String browser = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationApiHandler.java");
        String safety = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/communication/"
                        + "VehicleCommunicationSafety.java");

        assertTrue(browser.contains(
                "'Car off \\u2022 Communication unavailable'"));
        assertTrue(browser.contains("'Car unreachable'"));
        assertTrue(browser.contains("' \\u2022 Setup required'"));
        assertTrue(api.contains("response.put(\"carState\""));
        assertTrue(safety.contains("AccMonitor.isAccStateAuthoritative()"));
        assertTrue(safety.contains("!AccMonitor.isAccOn()"));
    }

    @Test
    public void voiceOutputUsesPerSessionVolumeAndCompatibilityRoute()
            throws IOException {
        String page = readRepositoryFile(
                "app/src/main/assets/web/local/communicate.html");
        String browser = readRepositoryFile(
                "app/src/main/assets/web/shared/communicate.js");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "RemoteCommunicationApiHandler.java");
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/"
                        + "RemoteVoiceService.java");
        String config = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/config/"
                        + "UnifiedConfigManager.kt");
        String settings = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/communication/"
                        + "RemoteCommunicationSettings.java");
        String binder = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/settings/"
                        + "RemoteCommunicationSettingsBinder.kt");
        String layout = readRepositoryFile(
                "app/src/main/res/layout/include_remote_communication_settings.xml");

        assertTrue(page.contains("id=\"remoteOutputLevel\""));
        assertTrue(page.contains("id=\"remoteOutputOverride\""));
        assertTrue(page.contains("step=\"5\""));
        assertTrue(browser.contains("'/api/communicate/settings'"));
        assertTrue(browser.contains("outputLevelOverrideEnabled"));
        assertTrue(browser.contains("this.outputSaving"));
        assertTrue(api.contains(".put(\"outputLevel\", settings.outputLevel)"));
        assertTrue(api.contains(".put(\"outputLevelOverrideEnabled\""));
        assertTrue(config.contains(
                "remoteCommunication.put(\"outputLevelOverrideEnabled\", false)"));
        assertTrue(config.contains(
                "remoteCommunication.put(\"audioChannel\", \"media\")"));
        assertTrue(settings.contains(
                "public static final String AUDIO_CHANNEL_MEDIA = \"media\""));
        assertTrue(settings.contains(
                "public static final String AUDIO_CHANNEL_NAVIGATION = \"navigation\""));
        assertTrue(settings.contains("updateAudioChannel(String audioChannel)"));
        assertTrue(binder.contains("RemoteCommunicationSettings.updateAudioChannel(channel)"));
        assertTrue(layout.contains("android:id=\"@+id/toggleRemoteAudioChannel\""));
        assertTrue(layout.contains("android:id=\"@+id/btnRemoteAudioMedia\""));
        assertTrue(layout.contains("android:id=\"@+id/btnRemoteAudioNavigation\""));
        assertTrue(receiver.contains("track.setVolume(volume)"));
        assertTrue(receiver.contains("selectOutputChannel(settings.audioChannel)"));
        assertTrue(receiver.contains(
                "MediaPlaybackService.streamForChannel(outputChannel)"));
        assertTrue(receiver.contains(
                "BYD_PRIORITY_ROUTE_FEATURE = 0xAA000282"));
        assertTrue(receiver.contains(
                "BYD_PRIORITY_ROUTE_RESTORE_FEATURE = 0xAA000283"));
        assertTrue(receiver.contains(
                "attributesBuilder.setFlags(FLAG_NAVI_UE)"));
        assertTrue(receiver.contains("new LoudnessEnhancer("));
        assertTrue(receiver.contains("if (!activateBydPriorityRoute())"));
        assertTrue(receiver.contains("releaseBydPriorityRoute();"));
        String setup = between(
                receiver,
                "private void initStreamingAudio()",
                "private int preferredOutputSampleRate()");
        assertTrue(setup.indexOf("activateBydPriorityRoute()")
                < setup.indexOf("new AudioTrack("));
        String routeCleanup = between(
                receiver,
                "private void releaseBydPriorityRoute()",
                "private void startMediaRouteKeeper()");
        assertTrue(routeCleanup.indexOf("BYD_PRIORITY_ROUTE_FEATURE")
                < routeCleanup.indexOf("BYD_PRIORITY_ROUTE_RESTORE_FEATURE"));
        String cleanup = between(
                receiver,
                "private synchronized void stopSessionResources()",
                "private void closeBridgeSocket()");
        assertTrue(cleanup.contains("track.release()"));
        assertTrue(cleanup.contains("enhancer.release()"));
        assertTrue(cleanup.contains("releaseBydPriorityRoute()"));
        assertTrue(cleanup.indexOf("track.release()")
                < cleanup.indexOf("releaseBydPriorityRoute()"));
        assertFalse(receiver.contains("private static final int OUTPUT_STREAM"));
        assertFalse(receiver.contains("AudioManager.STREAM_MUSIC"));
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing start marker: " + startMarker, start >= 0);
        assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule =
                    current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }
}
