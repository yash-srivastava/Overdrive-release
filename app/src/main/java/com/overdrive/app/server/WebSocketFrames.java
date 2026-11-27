package com.overdrive.app.server;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Shared server-side WebSocket framing for the audio and GenAI endpoints. */
final class WebSocketFrames {
    private static final String WS_MAGIC =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketFrames() {}

    /** Keep HTTP read-ahead across the upgrade; standalone callers get the same buffering. */
    static InputStream sessionInput(Socket socket, InputStream upgradedInput) throws IOException {
        return upgradedInput != null ? upgradedInput
                : new BufferedInputStream(socket.getInputStream(), 8192);
    }

    /** Coalesce header and small payload writes; writeFrame still flushes every frame. */
    static OutputStream sessionOutput(Socket socket) throws IOException {
        return new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
    }

    static void handshake(Socket socket, OutputStream output, String key)
            throws Exception {
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + websocketAccept(key)
                + "\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(1_000);
    }

    static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    static final class IdleReadTimeout extends SocketTimeoutException {
        IdleReadTimeout() {
            super("No WebSocket frame started before the idle timeout");
        }
    }

    static Frame readFrame(InputStream input, int maxPayloadBytes)
            throws IOException {
        int first;
        try {
            first = input.read();
        } catch (SocketTimeoutException timeout) {
            throw new IdleReadTimeout();
        }
        if (first < 0) return null;
        int second = input.read();
        if (second < 0) throw new EOFException();
        if ((first & 0x80) == 0) {
            throw new IOException(
                    "Fragmented client frames are unsupported");
        }
        int opcode = first & 0x0F;
        if ((second & 0x80) == 0) {
            throw new IOException(
                    "Client WebSocket frame was not masked");
        }
        long length = second & 0x7F;
        if (length == 126) {
            length = ((long) readRequired(input) << 8)
                    | readRequired(input);
        } else if (length == 127) {
            length = 0L;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readRequired(input);
            }
        }
        if (length < 0 || length > maxPayloadBytes) {
            throw new IOException("WebSocket frame is too large");
        }
        byte[] mask = new byte[4];
        readFully(input, mask);
        byte[] payload = new byte[(int) length];
        readFully(input, payload);
        unmask(payload, mask);
        return new Frame(opcode, payload);
    }

    // XOR two complete mask periods at once, using a heap view of the same payload.
    private static void unmask(byte[] payload, byte[] mask) {
        int i = 0;
        // Avoid the view setup cost for short control frames.
        if (payload.length >= 256) {
            long key = ((mask[0] & 0xffL) << 24) | ((mask[1] & 0xffL) << 16)
                    | ((mask[2] & 0xffL) << 8) | (mask[3] & 0xffL);
            key |= key << 32;
            // ByteBuffer defaults to big-endian, matching the key construction above.
            ByteBuffer words = ByteBuffer.wrap(payload);
            int end = payload.length & ~7;
            for (; i < end; i += 8) {
                words.putLong(i, words.getLong(i) ^ key);
            }
        }
        for (; i < payload.length; i++) {
            payload[i] ^= mask[i & 3];
        }
    }

    private static int readRequired(InputStream input)
            throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException();
        return value;
    }

    private static void readFully(InputStream input, byte[] target)
            throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = input.read(
                    target, offset, target.length - offset);
            if (read < 0) throw new EOFException();
            offset += read;
        }
    }

    private static String websocketAccept(String key)
            throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest((key + WS_MAGIC).getBytes(
                        StandardCharsets.UTF_8));
        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }

    static byte[] closePayload(int code, String reason) {
        byte[] text = reason == null
                ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(123, text.length);
        byte[] payload = new byte[2 + length];
        payload[0] = (byte) ((code >>> 8) & 0xFF);
        payload[1] = (byte) (code & 0xFF);
        System.arraycopy(text, 0, payload, 2, length);
        return payload;
    }

    static void writeFrame(
            OutputStream output, Object lock, int opcode, byte[] payload)
            throws IOException {
        writeFrame(output, lock, opcode, payload, payload.length);
    }

    // The cabin listener reuses pooled PCM arrays; only write the valid prefix.
    static void writeFrame(OutputStream output, Object lock, int opcode,
            byte[] payload, int length) throws IOException {
        synchronized (lock) {
            output.write(0x80 | (opcode & 0x0F));
            if (length <= 125) {
                output.write(length);
            } else if (length <= 65_535) {
                output.write(126);
                output.write((length >>> 8) & 0xFF);
                output.write(length & 0xFF);
            } else {
                output.write(127);
                for (int i = 7; i >= 0; i--) {
                    output.write((int) (((long) length
                            >>> (8 * i)) & 0xFF));
                }
            }
            output.write(payload, 0, length);
            output.flush();
        }
    }
}
