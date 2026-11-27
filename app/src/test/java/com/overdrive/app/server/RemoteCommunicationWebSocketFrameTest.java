package com.overdrive.app.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Random;

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
    public void decodesMaskedPayloadsAcrossWordAndLengthBoundaries() throws Exception {
        Random random = new Random(22022L);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        // Test all short sizes (including the fast-path threshold and every tail),
        // then real audio-sized frames and both extended-length boundaries.
        int[] large = {639, 640, 641, 1364, 4095, 4096, 4097, 32767, 32768,
                65534, 65535, 65536, 65537, 131071, 131072};
        byte[][] payloads = new byte[320 + large.length][];
        for (int i = 0; i < payloads.length; i++) {
            int size = i < 320 ? i : large[i - 320];
            byte[] payload = new byte[size];
            byte[] mask = new byte[4];
            random.nextBytes(payload);
            random.nextBytes(mask);
            payloads[i] = payload;
            wire.write(maskedFrame(payload, mask));
        }
        // Read multiple consecutive frames through deliberately short reads.
        InputStream input = new ByteArrayInputStream(wire.toByteArray()) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 13));
            }
        };
        for (byte[] payload : payloads) {
            WebSocketFrames.Frame frame = WebSocketFrames.readFrame(input,
                    GenAiRequestSecurity.MAX_CLIENT_FRAME_BYTES);
            assertEquals(2, frame.opcode);
            assertArrayEquals(payload, frame.payload);
        }
        assertEquals(null, WebSocketFrames.readFrame(input,
                GenAiRequestSecurity.MAX_CLIENT_FRAME_BYTES));
    }

    @Test
    public void truncatedMaskedPayloadIsNotPublished() throws Exception {
        byte[] wire = maskedFrame(new byte[513], new byte[]{-1, 2, -3, 4});
        try {
            WebSocketFrames.readFrame(new ByteArrayInputStream(wire, 0, wire.length - 1),
                    GenAiRequestSecurity.MAX_CLIENT_FRAME_BYTES);
            fail("Expected EOF in masked payload");
        } catch (EOFException expected) {
            // Incomplete frames must still fail before unmasking/publication.
        }
    }

    private static byte[] maskedFrame(byte[] payload, byte[] mask) {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int size = payload.length;
        wire.write(0x82);
        if (size <= 125) {
            wire.write(0x80 | size);
        } else if (size <= 65535) {
            wire.write(0xFE);
            wire.write(size >>> 8);
            wire.write(size);
        } else {
            wire.write(0xFF);
            for (int i = 7; i >= 0; i--) {
                wire.write((int) ((long) size >>> (8 * i)));
            }
        }
        wire.write(mask, 0, mask.length);
        for (int i = 0; i < size; i++) wire.write(payload[i] ^ mask[i & 3]);
        return wire.toByteArray();
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

    @Test
    public void reusesHttpReadAheadAcrossUpgrade() throws Exception {
        byte[] wire = {'H', '\r', '\n', (byte) 0x82, (byte) 0x81, 1, 2, 3, 4, 11,
                (byte) 0x89, (byte) 0x80, 1, 2, 3, 4};
        ByteArrayInputStream raw = new ByteArrayInputStream(wire);
        InputStream http = new BufferedInputStream(raw, 8192);
        assertEquals('H', http.read());
        assertEquals('\r', http.read());
        assertEquals('\n', http.read());
        assertEquals(0, raw.available()); // both frames already buffered by HTTP
        Socket socket = new Socket() {
            @Override public InputStream getInputStream() {
                throw new AssertionError("must not reopen socket input after upgrade");
            }
        };
        InputStream upgraded = WebSocketFrames.sessionInput(socket, http);
        assertSame(http, upgraded);
        WebSocketFrames.Frame first = readFrame(upgraded);
        assertEquals(0x2, first.opcode);
        assertArrayEquals(new byte[]{10}, first.payload);
        WebSocketFrames.Frame second = readFrame(upgraded);
        assertEquals(0x9, second.opcode);
        assertEquals(0, second.payload.length);
        assertEquals(null, readFrame(upgraded));
    }

    @Test
    public void standaloneInputReadsCompleteSmallFramesInOneBulkRead() throws Exception {
        byte[] wire = {(byte) 0x82, (byte) 0x81, 1, 2, 3, 4, 11,
                (byte) 0x89, (byte) 0x80, 1, 2, 3, 4};
        int[] reads = {0};
        ByteArrayInputStream raw = new ByteArrayInputStream(wire) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                reads[0]++;
                return super.read(b, off, len);
            }
            @Override public synchronized int read() {
                throw new AssertionError("unexpected single-byte socket read");
            }
        };
        Socket socket = new Socket() {
            @Override public InputStream getInputStream() { return raw; }
        };
        InputStream input = WebSocketFrames.sessionInput(socket, null);
        assertArrayEquals(new byte[]{10}, readFrame(input).payload);
        assertEquals(0x9, readFrame(input).opcode);
        assertEquals(1, reads[0]);
    }

    @Test
    public void bufferedOutputCoalescesSmallFramesWithoutDelayingThem() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        int[] writes = {0};
        int[] flushes = {0};
        OutputStream raw = new OutputStream() {
            @Override public void write(int b) { throw new AssertionError("single byte write"); }
            @Override public void write(byte[] b, int off, int len) {
                writes[0]++;
                wire.write(b, off, len);
            }
            @Override public void flush() { flushes[0]++; }
        };
        Socket socket = new Socket() {
            @Override public OutputStream getOutputStream() { return raw; }
        };
        OutputStream output = WebSocketFrames.sessionOutput(socket);
        Object lock = new Object();
        byte[] pcm = new byte[640];
        pcm[639] = 42;
        WebSocketFrames.writeFrame(output, lock, 0x2, pcm);
        assertEquals(1, writes[0]);
        assertEquals(1, flushes[0]);
        assertEquals(644, wire.size());
        byte[] expected = new byte[644];
        expected[0] = (byte) 0x82;
        expected[1] = 126;
        expected[2] = 2;
        expected[3] = (byte) 128;
        System.arraycopy(pcm, 0, expected, 4, pcm.length);
        assertArrayEquals(expected, wire.toByteArray());
        WebSocketFrames.writeFrame(output, lock, 0xA, new byte[0]);
        assertEquals(2, writes[0]);
        assertEquals(2, flushes[0]);
        assertEquals(646, wire.size());
    }

    @Test
    public void bufferedOutputPreservesLargeFrameAndPooledPrefix() throws Exception {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        Socket socket = new Socket() {
            @Override public OutputStream getOutputStream() { return wire; }
        };
        OutputStream output = WebSocketFrames.sessionOutput(socket);
        byte[] payload = new byte[70_000];
        payload[65_535] = 42;
        payload[65_536] = 99; // outside valid prefix
        WebSocketFrames.writeFrame(output, new Object(), 0x2, payload, 65_536);
        byte[] expected = new byte[65_546];
        expected[0] = (byte) 0x82;
        expected[1] = 127;
        expected[7] = 1;
        System.arraycopy(payload, 0, expected, 10, 65_536);
        assertArrayEquals(expected, wire.toByteArray());
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
