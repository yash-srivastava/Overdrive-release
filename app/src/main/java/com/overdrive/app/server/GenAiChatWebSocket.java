package com.overdrive.app.server;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.genai.GenAiAction;
import com.overdrive.app.genai.GenAiConfig;
import com.overdrive.app.genai.GenAiContext;
import com.overdrive.app.genai.GenAiRuntime;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.WebSocketFrames.Frame;
import com.overdrive.app.server.WebSocketFrames.IdleReadTimeout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellable text-stream bridge at {@code /ws/genai/chat}. */
public final class GenAiChatWebSocket {

    public static final String PATH = "/ws/genai/chat";
    private static final DaemonLogger logger =
            DaemonLogger.getInstance("GenAiChat");

    private GenAiChatWebSocket() {
    }

    public static void handle(Socket client, String websocketKey) {
        handle(client, websocketKey, null);
    }

    static void handle(Socket client, String websocketKey, InputStream upgradedInput) {
        Session session = new Session(client);
        try {
            session.run(websocketKey, upgradedInput);
        } catch (Throwable error) {
            logger.warn("Streaming chat failed: " + error.getMessage());
            session.sendFailure("stream_failed",
                    "The streaming connection failed.");
        } finally {
            session.close();
        }
    }

    private static final class Session {
        private final Socket socket;
        private final Object outputLock = new Object();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final GenAiRuntime.Cancellation cancellation =
                new GenAiRuntime.Cancellation();
        private InputStream input;
        private OutputStream output;

        Session(Socket socket) {
            this.socket = socket;
        }

        void run(String key, InputStream upgradedInput) throws Exception {
            input = WebSocketFrames.sessionInput(socket, upgradedInput);
            output = WebSocketFrames.sessionOutput(socket);
            WebSocketFrames.handshake(socket, output, key);

            JSONObject request = readInitialRequest();
            if (request == null) return;
            JSONArray messages;
            try {
                messages = GenAiApiHandler.validateMessages(request);
            } catch (GenAiApiHandler.RequestValidationException e) {
                sendFailure(e.code, e.getMessage());
                return;
            }

            GenAiConfig config = GenAiConfig.fromUnifiedConfig();
            GenAiRuntime runtime = CameraDaemon.getGenAiRuntime();
            if (!config.enabled) {
                sendFailure("genai_disabled", "GenAI is disabled.");
                return;
            }
            if (!config.isConfigured() || runtime == null) {
                sendFailure("genai_not_configured",
                        "GenAI provider settings are incomplete.");
                return;
            }

            GenAiContext.Snapshot snapshot = GenAiContext.build(
                    request.optString("mode", GenAiContext.GENERAL),
                    GenAiApiHandler.latestUserMessage(messages));
            String language = request.optString("language", "");
            if (GenAiContext.AUTOMATION_DRAFT.equals(snapshot.mode)) {
                sendFailure("stream_not_supported",
                        "Automation creation uses the validated draft flow.");
                return;
            }
            if (GenAiContext.VEHICLE_ACTION.equals(snapshot.mode)) {
                sendJson(new JSONObject()
                        .put("type", "start")
                        .put("mode", snapshot.mode)
                        .put("contextAttached", true));
                startControlReader();
                try {
                    JSONObject response =
                            GenAiApiHandler.createActionProposal(
                                    runtime, messages, snapshot,
                                    language,
                                    cancellation);
                    response.put("type", "done");
                    sendJson(response);
                    sendClose(1000, "Complete");
                } catch (GenAiAction.ValidationException e) {
                    sendFailure("invalid_action_proposal",
                            e.getMessage());
                } catch (GenAiRuntime.GenAiException e) {
                    if (!cancellation.isCancelled()
                            || !"request_cancelled".equals(e.code)) {
                        sendFailure(e.code, e.getMessage());
                    }
                } finally {
                    finished.set(true);
                }
                return;
            }

            sendJson(new JSONObject()
                    .put("type", "start")
                    .put("mode", snapshot.mode)
                    .put("contextAttached", snapshot.hasContext()));
            startControlReader();

            try {
                JSONObject response = runtime.stream(
                        messages, snapshot.context,
                        GenAiContext.withResponseLanguage(
                                snapshot.instructions, language),
                        cancellation,
                        delta -> sendJson(new JSONObject()
                                .put("type", "delta")
                                .put("text", delta)),
                        true);
                response.put("type", "done");
                response.put("mode", snapshot.mode);
                response.put("contextAttached", snapshot.hasContext());
                Iterator<String> fields = snapshot.clientData.keys();
                while (fields.hasNext()) {
                    String field = fields.next();
                    response.put(field, snapshot.clientData.opt(field));
                }
                sendJson(response);
                sendClose(1000, "Complete");
            } catch (GenAiRuntime.GenAiException e) {
                if (!cancellation.isCancelled()
                        || !"request_cancelled".equals(e.code)) {
                    sendFailure(e.code, e.getMessage());
                }
            } finally {
                finished.set(true);
            }
        }

        private JSONObject readInitialRequest() throws Exception {
            long deadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < deadline) {
                Frame frame;
                try {
                    frame = WebSocketFrames.readFrame(
                            input, GenAiRequestSecurity.MAX_CLIENT_FRAME_BYTES);
                } catch (IdleReadTimeout timeout) {
                    continue;
                }
                if (frame == null || frame.opcode == 0x8) return null;
                if (frame.opcode == 0x9) {
                    WebSocketFrames.writeFrame(
                            output, outputLock, 0xA, frame.payload);
                    continue;
                }
                if (frame.opcode != 0x1) continue;
                return new JSONObject(new String(
                        frame.payload, StandardCharsets.UTF_8));
            }
            sendFailure("request_timeout",
                    "No chat request was received.");
            return null;
        }

        private void startControlReader() {
            Thread control = new Thread(() -> {
                while (!finished.get() && !socket.isClosed()) {
                    try {
                        Frame frame = WebSocketFrames.readFrame(
                                input, GenAiRequestSecurity.MAX_CLIENT_FRAME_BYTES);
                        if (frame == null || frame.opcode == 0x8) {
                            cancellation.cancel();
                            return;
                        }
                        if (frame.opcode == 0x9) {
                            WebSocketFrames.writeFrame(
                                    output, outputLock, 0xA,
                                    frame.payload);
                        } else if (frame.opcode == 0x1) {
                            JSONObject controlMessage = new JSONObject(
                                    new String(frame.payload,
                                            StandardCharsets.UTF_8));
                            if ("cancel".equals(controlMessage.optString(
                                    "type", ""))) {
                                cancellation.cancel();
                                return;
                            }
                        }
                    } catch (IdleReadTimeout ignored) {
                    } catch (Throwable ignored) {
                        cancellation.cancel();
                        return;
                    }
                }
            }, "GenAiChatControl");
            control.setDaemon(true);
            control.start();
        }

        void sendFailure(String code, String reason) {
            try {
                sendJson(new JSONObject()
                        .put("type", "error")
                        .put("success", false)
                        .put("code", code)
                        .put("error", safeReason(reason)));
                sendClose(1011, safeReason(reason));
            } catch (Throwable ignored) {
            }
        }

        private void sendJson(JSONObject object) throws java.io.IOException {
            WebSocketFrames.writeFrame(
                    output, outputLock, 0x1,
                    object.toString().getBytes(StandardCharsets.UTF_8));
        }

        private void sendClose(int code, String reason)
                throws java.io.IOException {
            WebSocketFrames.writeFrame(output, outputLock, 0x8,
                    WebSocketFrames.closePayload(code, safeReason(reason)));
        }

        void close() {
            finished.set(true);
            cancellation.cancel();
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String safeReason(String reason) {
        String value = reason == null ? "" : reason
                .replace('\r', ' ').replace('\n', ' ').trim();
        if (value.isEmpty()) return "GenAI request failed.";
        return value.length() > 240
                ? value.substring(0, 240) : value;
    }
}
