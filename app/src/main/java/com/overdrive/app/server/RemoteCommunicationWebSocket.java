package com.overdrive.app.server;

import com.overdrive.app.communication.RemoteCommunicationAvailability;
import com.overdrive.app.communication.RemoteCommunicationPolicy;
import com.overdrive.app.communication.RemoteCommunicationSettings;
import com.overdrive.app.communication.RemoteVoiceBridge;
import com.overdrive.app.communication.RemoteVoiceController;
import com.overdrive.app.communication.RemoteVoicePcmConverter;
import com.overdrive.app.communication.VehicleCommunicationSafety;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.WebSocketFrames.Frame;
import com.overdrive.app.server.WebSocketFrames.IdleReadTimeout;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Authenticated push-to-talk WebSocket endpoint at {@code /ws/communicate}. */
public final class RemoteCommunicationWebSocket {

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("RemoteCommunicate");
    private static final AtomicReference<Session> ACTIVE = new AtomicReference<>();
    private static final long NETWORK_GAP_WARN_MS = 150L;

    private RemoteCommunicationWebSocket() {}

    public static boolean isBusy() {
        return ACTIVE.get() != null;
    }

    public static void stopActive(String reason) {
        Session session = ACTIVE.get();
        if (session != null) session.requestStop(reason);
    }

    public static void handle(Socket client, String websocketKey) {
        handle(client, websocketKey, null);
    }

    static void handle(Socket client, String websocketKey, InputStream upgradedInput) {
        Session session = new Session(client);
        try {
            session.handshake(websocketKey, upgradedInput);
            if (!ACTIVE.compareAndSet(null, session)) {
                session.failAndClose("Another talk session is already active", 1013);
                return;
            }
            session.run();
        } catch (Throwable error) {
            logger.warn("PTT WebSocket failed: " + error.getMessage());
            if (!session.stopRequested) session.requestStop("Connection lost");
        } finally {
            try {
                session.logPcmStats();
                session.close();
            } finally {
                ACTIVE.compareAndSet(session, null);
            }
        }
    }

    private static final class Session implements RemoteVoiceBridge.Listener {
        private final Socket socket;
        private final Object outputLock = new Object();
        private volatile boolean stopRequested;
        private volatile boolean drainAudio;
        private volatile String stopReason = "Connection closed";
        private InputStream input;
        private OutputStream output;
        private RemoteVoiceBridge bridge;
        private long pcmFrames;
        private long pcmBytes;
        private long previousPcmAt;
        private long maxPcmGapMs;
        private long delayedPcmFrames;
        private int maxPcmPeak;
        private boolean cabinListenerPaused;

        Session(Socket socket) {
            this.socket = socket;
        }

        void handshake(String key, InputStream upgradedInput) throws Exception {
            input = WebSocketFrames.sessionInput(socket, upgradedInput);
            output = WebSocketFrames.sessionOutput(socket);
            WebSocketFrames.handshake(socket, output, key);
        }

        void run() throws Exception {
            RemoteCommunicationSettings.Snapshot settings =
                    RemoteCommunicationSettings.load();
            boolean carOff = VehicleCommunicationSafety.isCarKnownOff();
            Boolean overlay = RemoteCommunicationAvailability.shouldCheckVoiceOverlay(
                    carOff, settings, false)
                    ? RemoteVoiceController.hasOverlayPermission() : null;
            RemoteCommunicationAvailability.Result availability =
                    RemoteCommunicationAvailability.voice(
                            carOff,
                            settings,
                            false,
                            overlay);
            if (!availability.ready) {
                failAndClose(availability.reason, 1008);
                return;
            }

            sendJson(new JSONObject()
                    .put("type", "connected")
                    .put("maxSeconds",
                            RemoteCommunicationPolicy.MAX_SESSION_MS / 1000L));

            if (!awaitStartCommand()) return;
            CabinAudioWebSocket.setTalkActive(true);
            cabinListenerPaused = true;

            try {
                bridge = RemoteVoiceBridge.connect(
                        RemoteCommunicationPolicy.effectiveOutputLevel(
                                settings.outputLevelOverrideEnabled,
                                settings.outputLevel),
                        this);
            } catch (RemoteVoiceBridge.RejectedException rejected) {
                failAndClose(rejected.getMessage(), 1011);
                return;
            }
            if (stopRequested) return;

            long startedAt = System.currentTimeMillis();
            long lastAudioAt = startedAt;
            sendJson(new JSONObject()
                    .put("type", "live")
                    .put("sampleRate", RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ)
                    .put("maxSeconds",
                            RemoteCommunicationPolicy.MAX_SESSION_MS / 1000L));

            while (!stopRequested && !socket.isClosed()) {
                long now = System.currentTimeMillis();
                if (VehicleCommunicationSafety.isCarKnownOff()) {
                    stopReason = RemoteCommunicationAvailability.voice(
                            true, settings, false, null).reason;
                    break;
                }
                if (RemoteCommunicationPolicy.shouldStopForLimit(now - startedAt)) {
                    stopReason = "30 second limit reached";
                    drainAudio = true;
                    break;
                }
                if (RemoteCommunicationPolicy.shouldStopForInactivity(now - lastAudioAt)) {
                    stopReason = "Audio stream became inactive";
                    break;
                }

                Frame frame;
                try {
                    frame = WebSocketFrames.readFrame(
                            input, RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES);
                } catch (IdleReadTimeout timeout) {
                    continue;
                } catch (IOException disconnected) {
                    if (stopRequested) break;
                    throw disconnected;
                }
                if (frame == null || frame.opcode == 0x8) {
                    if (!stopRequested) stopReason = "Connection closed";
                    break;
                }
                if (frame.opcode == 0x9) {
                    sendFrame(0xA, frame.payload);
                    continue;
                }
                if (frame.opcode == 0x1) {
                    String text = new String(frame.payload, StandardCharsets.UTF_8);
                    JSONObject command = new JSONObject(text);
                    if ("stop".equals(command.optString("type"))) {
                        stopReason = command.optString("reason", "Released");
                        drainAudio = isGracefulStopReason(stopReason);
                        break;
                    }
                    continue;
                }
                if (frame.opcode != 0x2) continue;
                if (frame.payload.length == 0
                        || frame.payload.length
                        > RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES
                        || (frame.payload.length & 1) != 0) {
                    throw new IOException("Invalid PCM WebSocket frame");
                }
                bridge.sendPcm(frame.payload);
                maxPcmPeak = Math.max(
                        maxPcmPeak,
                        RemoteVoicePcmConverter.peakAbsoluteSample(
                                frame.payload, frame.payload.length));
                long receivedAt = System.currentTimeMillis();
                if (previousPcmAt > 0L) {
                    long gapMs = receivedAt - previousPcmAt;
                    maxPcmGapMs = Math.max(maxPcmGapMs, gapMs);
                    if (gapMs >= NETWORK_GAP_WARN_MS) delayedPcmFrames++;
                }
                previousPcmAt = receivedAt;
                pcmFrames++;
                pcmBytes += frame.payload.length;
                lastAudioAt = receivedAt;
            }

            try {
                sendJson(new JSONObject()
                        .put("type", "stopped")
                        .put("reason", stopReason));
                sendClose(1000, stopReason);
            } catch (IOException ignored) {
                // The browser may close immediately after its stop command.
            }
        }

        void logPcmStats() {
            if (pcmFrames == 0L) return;
            logger.info("PTT network stats: frames=" + pcmFrames
                    + ", bytes=" + pcmBytes
                    + ", maxGapMs=" + maxPcmGapMs
                    + ", delayedFrames=" + delayedPcmFrames
                    + ", maxPeak=" + maxPcmPeak);
        }

        private boolean awaitStartCommand() throws Exception {
            long deadline = System.currentTimeMillis() + 5_000L;
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                Frame frame;
                try {
                    frame = WebSocketFrames.readFrame(
                            input, RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES);
                } catch (IdleReadTimeout timeout) {
                    continue;
                }
                if (frame == null || frame.opcode == 0x8) return false;
                if (frame.opcode == 0x9) {
                    sendFrame(0xA, frame.payload);
                    continue;
                }
                if (frame.opcode != 0x1) continue;
                JSONObject command = new JSONObject(
                        new String(frame.payload, StandardCharsets.UTF_8));
                if (!"start".equals(command.optString("type"))) continue;
                int sampleRate = command.optInt("sampleRate", 0);
                String format = command.optString("format", "");
                if (sampleRate != RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ
                        || !"pcm_s16le".equals(format)) {
                    failAndClose("Unsupported browser audio format", 1003);
                    return false;
                }
                return true;
            }
            failAndClose("Talk session did not start in time", 1008);
            return false;
        }

        @Override public void onControl(String control) {
            if (control == null) return;
            try {
                if (control.startsWith("MUTE:")) {
                    sendJson(new JSONObject()
                            .put("type", "receiver-muted")
                            .put("muted", control.endsWith("1")));
                } else if (control.startsWith("DIAG:")) {
                    logger.info("Receiver " + control.substring(5));
                } else if ("STOP".equals(control)) {
                    requestStop("Stopped in the car");
                }
            } catch (Throwable ignored) {}
        }

        @Override public void onReceiverLost(String reason) {
            requestStop(reason);
        }

        void requestStop(String reason) {
            stopRequested = true;
            drainAudio = false;
            if (reason != null && !reason.trim().isEmpty()) stopReason = reason;
            try { socket.shutdownInput(); } catch (Throwable ignored) {}
        }

        void failAndClose(String reason, int closeCode) {
            try {
                sendJson(new JSONObject().put("type", "failed").put("reason", reason));
                sendClose(closeCode, reason);
            } catch (Throwable ignored) {}
            requestStop(reason);
        }

        void close() {
            if (bridge != null) bridge.close(shouldDrainAudio());
            if (cabinListenerPaused) {
                CabinAudioWebSocket.setTalkActive(false);
                cabinListenerPaused = false;
            }
            try { socket.close(); } catch (Throwable ignored) {}
        }

        private boolean shouldDrainAudio() {
            return drainAudio;
        }

        private static boolean isGracefulStopReason(String reason) {
            return "Released".equals(reason)
                    || "30 second limit reached".equals(reason);
        }

        private void sendJson(JSONObject object) throws IOException {
            sendFrame(0x1, object.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void sendClose(int code, String reason) throws IOException {
            sendFrame(0x8, WebSocketFrames.closePayload(code, reason));
        }

        private void sendFrame(int opcode, byte[] payload) throws IOException {
            WebSocketFrames.writeFrame(output, outputLock, opcode, payload);
        }
    }
}
