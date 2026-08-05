package com.overdrive.app.server;

import android.util.Base64;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authenticated latest-frame WebSocket transport for Remote Dev View.
 *
 * Capture runs continuously on a daemon producer thread. A capacity-one queue
 * means a slow tunnel can never accumulate stale UI frames: the producer
 * replaces the queued frame with the newest completed capture. HTTP input uses
 * a different bridge connection and is therefore no longer serialized behind
 * this stream.
 */
final class RemoteDevViewWebSocketStream {
    static final String PATH = "/ws/dev-view";
    static final String PROTOCOL = "overdrive-dev-view";

    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int STREAM_WIDTH = 960;
    private static final int STREAM_QUALITY = 55;
    private static final int MAX_WS_FRAGMENT_BYTES = 32 * 1024;
    private static final long ERROR_RETRY_MS = 120L;
    private static final long STREAM_POLL_INTERVAL_MS = 80L;

    private RemoteDevViewWebSocketStream() {}

    /**
     * Browser WebSockets cannot set an Authorization-style capability header.
     * Carry the memory-only session as a second requested subprotocol instead
     * of putting it in the URL, browser history, or proxy request logs.
     */
    static String sessionFromProtocols(String header) {
        if (header == null || header.isEmpty()) return null;
        boolean protocolPresent = false;
        String candidate = null;
        for (String part : header.split(",")) {
            String value = part.trim();
            if (PROTOCOL.equals(value)) {
                protocolPresent = true;
            } else if (value.matches("[0-9a-f]{64}")) {
                candidate = value;
            }
        }
        return protocolPresent ? candidate : null;
    }

    static void handle(Socket client, String websocketKey, String session) {
        AtomicBoolean running = new AtomicBoolean(true);
        Thread producer = null;
        try {
            client.setSoTimeout(0);
            client.setTcpNoDelay(true);
            client.setSendBufferSize(128 * 1024);
            OutputStream out = client.getOutputStream();
            writeHandshake(out, websocketKey);

            BlockingQueue<StreamFrame> latest = new ArrayBlockingQueue<>(1);
            AtomicLong sequence = new AtomicLong();
            producer = new Thread(
                () -> produceFrames(client, session, running, latest, sequence),
                "remote-dev-frame-producer");
            producer.setDaemon(true);
            producer.start();

            sendText(out, new JSONObject()
                .put("type", "ready")
                .put("transport", "websocket-latest-frame")
                .put("width", STREAM_WIDTH)
                .put("quality", STREAM_QUALITY)
                .toString());

            while (running.get() && !client.isClosed()) {
                if (!RemoteDevViewApiHandler.validateStreamSession(session)) {
                    sendClose(out, 1008, "Developer-view session expired");
                    break;
                }

                StreamFrame frame = latest.poll(5, TimeUnit.SECONDS);
                if (frame == null) {
                    sendPing(out);
                    continue;
                }

                sendText(out, frame.metadata.toString());
                if (frame.jpeg.length > 0 && frame.metadata.optBoolean("success", false)) {
                    sendBinary(out, frame.jpeg);
                }
            }
        } catch (Exception ignored) {
            // Disconnects are expected when a tab hides, reloads, or ends its
            // session. The producer is stopped in finally and no frame persists.
        } finally {
            running.set(false);
            if (producer != null) producer.interrupt();
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private static void produceFrames(
            Socket client,
            String session,
            AtomicBoolean running,
            BlockingQueue<StreamFrame> latest,
            AtomicLong sequence) {
        boolean hasSuccessfulFrame = false;
        long lastSourceSequence = -1L;
        while (running.get() && !client.isClosed()
                && RemoteDevViewApiHandler.validateStreamSession(session)) {
            long started = System.nanoTime();
            RemoteDevViewBridgeClient.Response response =
                RemoteDevViewApiHandler.captureStreamFrame(STREAM_WIDTH, STREAM_QUALITY);
            long captureMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            JSONObject metadata = streamMetadata(
                response, sequence.incrementAndGet(), captureMs);
            byte[] jpeg = response == null ? new byte[0] : response.payload;
            boolean successful = metadata.optBoolean("success", false) && jpeg.length > 0;
            long sourceSequence = metadata.optLong("frameSequence", 0L);
            boolean isNewFrame = successful
                && (sourceSequence <= 0L || sourceSequence != lastSourceSequence);

            if (isNewFrame || (!successful && !hasSuccessfulFrame)) {
                // Capacity one: replace an unsent old frame with this newer
                // one. Once a good frame exists, transient PixelCopy races do
                // not replace it or its successful metadata with an error.
                latest.poll();
                latest.offer(new StreamFrame(metadata, jpeg));
            }
            if (isNewFrame) {
                hasSuccessfulFrame = true;
                lastSourceSequence = sourceSequence;
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long delayMs = successful
                ? Math.max(1L, STREAM_POLL_INTERVAL_MS - elapsedMs)
                : ERROR_RETRY_MS;
            if (delayMs > 0L) {
                try { Thread.sleep(delayMs); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        running.set(false);
    }

    private static JSONObject streamMetadata(
            RemoteDevViewBridgeClient.Response response, long sequence, long captureMs) {
        JSONObject metadata = new JSONObject();
        try {
            if (response == null) {
                metadata.put("success", false)
                    .put("error", "App-process bridge is unavailable");
            } else {
                metadata = new JSONObject(response.metadata.toString());
            }
            metadata.put("type", "frame")
                .put("sequence", sequence)
                .put("captureMs", captureMs);
        } catch (Exception ignored) {
            // The fields above are fixed primitive values; JSONException is not
            // expected, but an empty metadata object safely suppresses payload
            // delivery if a platform implementation rejects one.
        }
        return metadata;
    }

    private static void writeHandshake(OutputStream out, String key) throws Exception {
        String accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                (key + WS_MAGIC).getBytes(StandardCharsets.UTF_8)),
            Base64.NO_WRAP);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: " + accept + "\r\n"
            + "Sec-WebSocket-Protocol: " + PROTOCOL + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void sendText(OutputStream out, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        sendFrame(out, data, 0, data.length, 0x01, true);
        out.flush();
    }

    private static void sendBinary(OutputStream out, byte[] data) throws Exception {
        int offset = 0;
        boolean first = true;
        while (offset < data.length) {
            int count = Math.min(MAX_WS_FRAGMENT_BYTES, data.length - offset);
            boolean last = offset + count >= data.length;
            sendFrame(out, data, offset, count, first ? 0x02 : 0x00, last);
            first = false;
            offset += count;
        }
        out.flush();
    }

    private static void sendPing(OutputStream out) throws Exception {
        out.write(new byte[]{(byte) 0x89, 0x00});
        out.flush();
    }

    private static void sendClose(OutputStream out, int code, String reason) {
        try {
            byte[] reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[2 + reasonBytes.length];
            payload[0] = (byte) ((code >>> 8) & 0xff);
            payload[1] = (byte) (code & 0xff);
            System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
            sendFrame(out, payload, 0, payload.length, 0x08, true);
            out.flush();
        } catch (Exception ignored) {}
    }

    private static void sendFrame(
            OutputStream out, byte[] data, int offset, int length, int opcode, boolean fin)
            throws Exception {
        out.write((fin ? 0x80 : 0x00) | opcode);
        if (length <= 125) {
            out.write(length);
        } else if (length <= 65_535) {
            out.write(126);
            out.write((length >>> 8) & 0xff);
            out.write(length & 0xff);
        } else {
            out.write(127);
            long value = length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((value >>> shift) & 0xff));
            }
        }
        out.write(data, offset, length);
    }

    private static final class StreamFrame {
        final JSONObject metadata;
        final byte[] jpeg;

        StreamFrame(JSONObject metadata, byte[] jpeg) {
            this.metadata = metadata;
            this.jpeg = jpeg;
        }
    }
}
