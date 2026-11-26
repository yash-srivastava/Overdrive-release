package com.overdrive.app.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;

import org.junit.Test;

import com.overdrive.app.communication.RemoteCommunicationPolicy;

public class RemoteCommunicationWebSocketFrameTest {

    @Test
    public void idleTimeoutIsDistinguishedFromPartialFrameTimeout()
            throws Exception {
        Throwable idle = invokeFailure(new InputStream() {
            @Override public int read() throws SocketTimeoutException {
                throw new SocketTimeoutException("idle");
            }
        });
        assertEquals("IdleReadTimeout", idle.getClass().getSimpleName());

        Throwable partial = invokeFailure(new InputStream() {
            private boolean headerRead;

            @Override public int read() throws SocketTimeoutException {
                if (!headerRead) {
                    headerRead = true;
                    return 0x82;
                }
                throw new SocketTimeoutException("partial");
            }
        });
        assertEquals(SocketTimeoutException.class, partial.getClass());
    }

    @Test
    public void decodesMaskedBinaryFrames() throws Exception {
        byte[] mask = new byte[]{1, 2, 3, 4};
        byte[] payload = new byte[]{10, 20, 30, 40};
        byte[] frame = new byte[2 + mask.length + payload.length];
        frame[0] = (byte) 0x82;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, frame, 2, mask.length);
        for (int index = 0; index < payload.length; index++) {
            frame[2 + mask.length + index] =
                    (byte) (payload[index] ^ mask[index & 3]);
        }

        WebSocketFrames.Frame decoded = readFrame(new ByteArrayInputStream(frame));
        assertEquals(0x2, decoded.opcode);
        assertArrayEquals(payload, decoded.payload);
    }

    @Test
    public void rejectsOversizeFramesBeforeReadingPayload() throws Exception {
        int[] limits = {8 * 1024, RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES,
                GenAiRequestSecurity.MAX_CLIENT_FRAME_BYTES};
        for (int limit : limits) {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            header.write(0x82);
            header.write(0xFF);
            for (int i = 7; i >= 0; i--) {
                header.write((int) (((long) limit + 1) >>> (8 * i)) & 0xFF);
            }
            try {
                WebSocketFrames.readFrame(new ByteArrayInputStream(header.toByteArray()), limit);
                fail("Expected frame exceeding " + limit + " to be rejected");
            } catch (IOException expected) {
                assertEquals("WebSocket frame is too large", expected.getMessage());
            }
        }
    }

    @Test
    public void writesOnlyValidPrefixOfPooledAudioBuffer() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WebSocketFrames.writeFrame(output, new Object(), 0x2,
                new byte[]{10, 20, 30, 40}, 2);
        assertArrayEquals(new byte[]{(byte) 0x82, 2, 10, 20}, output.toByteArray());
    }

    @Test
    public void writesAllThreeLengthEncodings() throws Exception {
        int[] sizes = {125, 126, 65535, 65536};
        byte[][] headers = {
                {(byte) 0x82, 125},
                {(byte) 0x82, 126, 0, 126},
                {(byte) 0x82, 126, (byte) 0xFF, (byte) 0xFF},
                {(byte) 0x82, 127, 0, 0, 0, 0, 0, 1, 0, 0}
        };
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            WebSocketFrames.writeFrame(output, new Object(), 0x2, new byte[sizes[i]]);
            byte[] expected = new byte[headers[i].length + sizes[i]];
            System.arraycopy(headers[i], 0, expected, 0, headers[i].length);
            assertArrayEquals(expected, output.toByteArray());
        }
    }

    @Test
    public void closePayloadKeepsCodeAndBoundsReason() {
        assertArrayEquals(new byte[]{3, (byte) 0xE8},
                WebSocketFrames.closePayload(1000, null));
        char[] reason = new char[200];
        java.util.Arrays.fill(reason, 'x');
        byte[] payload = WebSocketFrames.closePayload(1000, new String(reason));
        assertEquals(125, payload.length);
        assertEquals(3, payload[0]);
        assertEquals((byte) 0xE8, payload[1]);
        for (int i = 2; i < payload.length; i++) assertEquals('x', payload[i]);
    }

    private static Throwable invokeFailure(InputStream input) throws Exception {
        try {
            readFrame(input);
            fail("Expected WebSocket frame parsing to fail");
            return null;
        } catch (SocketTimeoutException timeout) {
            return timeout;
        }
    }

    private static WebSocketFrames.Frame readFrame(InputStream input) throws IOException {
        return WebSocketFrames.readFrame(input, RemoteCommunicationPolicy.MAX_PCM_FRAME_BYTES);
    }
}
