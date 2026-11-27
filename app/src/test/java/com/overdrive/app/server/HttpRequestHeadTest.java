package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/** Source-level regression coverage for the HTTP daemon's pre-auth framing. */
public class HttpRequestHeadTest {
    private interface Operation { void run() throws Exception; }

    private static void failsWith(int status, Operation action) throws Exception {
        try {
            action.run();
            fail("Expected a rejected HTTP request head");
        } catch (HttpRequestHead.HeadException expected) {
            assertEquals(status, expected.status);
        }
    }

    private static HttpRequestHead reader(String source) {
        return new HttpRequestHead(new ByteArrayInputStream(
                source.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void acceptsNormalAndProxyPrefixedRequestHeads() throws Exception {
        HttpRequestHead ordinary = reader("GET /status HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n");
        assertEquals("GET /status HTTP/1.1", ordinary.readRequestLine());
        assertEquals("Host: 127.0.0.1", ordinary.readHeaderLine());
        assertEquals("", ordinary.readHeaderLine());

        HttpRequestHead proxied = reader("PROXY TCP4 100.64.1.2 127.0.0.1 1234 8080\r\n"
                + "GET /ws HTTP/1.1\r\nUpgrade: websocket\r\n\r\n");
        assertTrue(proxied.readRequestLine().startsWith("PROXY TCP4"));
        assertEquals("GET /ws HTTP/1.1", proxied.readRequestLine());
        assertEquals("Upgrade: websocket", proxied.readHeaderLine());
        assertEquals("", proxied.readHeaderLine());
    }

    @Test public void acceptsLfOnlyForLegacyClients() throws Exception {
        HttpRequestHead head = reader("POST /api/settings HTTP/1.1\nContent-Length: 0\n\n");
        assertEquals("POST /api/settings HTTP/1.1", head.readRequestLine());
        HttpRequestHead.Framing framing = new HttpRequestHead.Framing();
        framing.accept(head.readHeaderLine());
        assertEquals(0, framing.contentLength());
        assertEquals("", head.readHeaderLine());
    }

    @Test public void rejectsOverlongRequestLineBeforeBufferingAllOfIt() throws Exception {
        HttpRequestHead head = reader("GET /" + repeat('x', 8200) + " HTTP/1.1\r\n\r\n");
        failsWith(414, head::readRequestLine);
    }

    @Test public void rejectsOverlongHeaderAndTooManyFields() throws Exception {
        HttpRequestHead longLine = reader("GET / HTTP/1.1\r\nX-Fill: "
                + repeat('x', 16400) + "\r\n\r\n");
        longLine.readRequestLine();
        failsWith(431, longLine::readHeaderLine);

        HttpRequestHead tooMany = reader("GET / HTTP/1.1\r\n"
                + repeat("X-A: b\r\n", 101) + "\r\n");
        tooMany.readRequestLine();
        for (int i = 0; i < 100; i++) tooMany.readHeaderLine();
        failsWith(431, tooMany::readHeaderLine);
    }

    @Test public void rejectsAggregateHeadBudget() throws Exception {
        HttpRequestHead head = reader("GET / HTTP/1.1\r\n"
                + repeat("X-Fill: " + repeat('a', 2000) + "\r\n", 20)
                + "\r\n");
        head.readRequestLine();
        failsWith(431, () -> {
            while (!head.readHeaderLine().isEmpty()) { /* consume bounded fields */ }
        });
    }

    @Test public void rejectsMissingHeaderTerminatorAndInvalidControls() throws Exception {
        HttpRequestHead eof = reader("GET / HTTP/1.1\r\nHost: localhost\r\n");
        eof.readRequestLine();
        eof.readHeaderLine();
        failsWith(400, eof::readHeaderLine);

        HttpRequestHead controls = reader("GET / HTTP/1.1\r\nX-Bad: 1\r2\r\n\r\n");
        controls.readRequestLine();
        failsWith(400, controls::readHeaderLine);
    }

    @Test public void rejectsAmbiguousOrUnsupportedBodyFraming() throws Exception {
        HttpRequestHead.Framing duplicate = new HttpRequestHead.Framing();
        duplicate.accept("Content-Length: 8");
        failsWith(400, () -> duplicate.accept("content-length: 8"));
        failsWith(400, () -> new HttpRequestHead.Framing()
                .accept("Content-Length: 4, 4"));
        HttpRequestHead.Framing lengthThenChunked = new HttpRequestHead.Framing();
        lengthThenChunked.accept("Content-Length: 8");
        failsWith(400, () -> lengthThenChunked.accept("Transfer-Encoding: chunked"));
        HttpRequestHead.Framing chunkedThenLength = new HttpRequestHead.Framing();
        chunkedThenLength.accept("Transfer-Encoding: chunked");
        failsWith(400, () -> chunkedThenLength.accept("Content-Length: 8"));
        failsWith(400, () -> new HttpRequestHead.Framing()
                .accept("Transfer-Encoding: gzip, chunked"));
        failsWith(400, () -> new HttpRequestHead.Framing()
                .accept("Transfer-Encoding: identity"));
        failsWith(400, () -> new HttpRequestHead.Framing()
                .accept("Content-Length : 8"));
        failsWith(400, () -> new HttpRequestHead.Framing()
                .accept("Content-Length: -1"));
        failsWith(413, () -> new HttpRequestHead.Framing()
                .accept("Content-Length: 2147483648"));
    }

    @Test public void decodesUtf8AcrossReadBoundariesWithoutConsumingFollowingBytes()
            throws Exception {
        String content = repeat('A', 4095) + "🚗" + "é";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String headers = "POST /api/settings HTTP/1.1\r\nContent-Length: "
                + bytes.length + "\r\n\r\n";
        byte[] prefix = headers.getBytes(StandardCharsets.US_ASCII);
        byte[] wire = new byte[prefix.length + bytes.length + 1];
        System.arraycopy(prefix, 0, wire, 0, prefix.length);
        System.arraycopy(bytes, 0, wire, prefix.length, bytes.length);
        wire[wire.length - 1] = '!';
        InputStream input = new BufferedInputStream(new ByteArrayInputStream(wire), 256);
        HttpRequestHead head = new HttpRequestHead(input);
        assertTrue(head.readRequestLine().startsWith("POST "));
        HttpRequestHead.Framing framing = new HttpRequestHead.Framing();
        framing.accept(head.readHeaderLine());
        assertEquals("", head.readHeaderLine());
        assertEquals(content, HttpRequestHead.readUtf8Body(input, framing.contentLength()));
        assertEquals('!', input.read());
    }

    @Test public void rejectsTruncatedBodyAndTreatsZeroLengthAsAbsent() throws Exception {
        assertNull(HttpRequestHead.readUtf8Body(
                new ByteArrayInputStream(new byte[0]), 0));
        failsWith(400, () -> HttpRequestHead.readUtf8Body(
                new ByteArrayInputStream("small".getBytes(StandardCharsets.UTF_8)), 9));
    }

    @Test public void smallBodiesMatchStreamingDecoderAndLeaveFollowingBytes() throws Exception {
        Random random = new Random(79211L);
        for (int length : new int[]{1, 2, 3, 7, 64, 255, 1024, 4095, 4096, 4097, 8192}) {
            for (int iteration = 0; iteration < 40; iteration++) {
                byte[] payload = new byte[length];
                if (iteration % 2 == 0) {
                    random.nextBytes(payload); // malformed and valid sequences, including tails
                } else {
                    for (int i = 0; i < length; i++) payload[i] = (byte) random.nextInt(128);
                }
                assertBodyMatchesReader(payload, 1 + random.nextInt(73));
            }
        }
        byte[] text = "{\"name\":\"Zürich 🚗 東京\"}".getBytes(StandardCharsets.UTF_8);
        for (int readSize = 1; readSize <= text.length; readSize++) {
            assertBodyMatchesReader(text, readSize);
        }
    }

    private static void assertBodyMatchesReader(byte[] payload, int readSize) throws Exception {
        StringBuilder expected = new StringBuilder();
        try (InputStreamReader decoder = new InputStreamReader(
                new ByteArrayInputStream(payload), StandardCharsets.UTF_8)) {
            char[] chars = new char[37];
            int count;
            while ((count = decoder.read(chars)) >= 0) expected.append(chars, 0, count);
        }
        byte[] wire = Arrays.copyOf(payload, payload.length + 1);
        wire[payload.length] = '!';
        InputStream input = new ByteArrayInputStream(wire) {
            @Override public synchronized int read(byte[] bytes, int off, int len) {
                return super.read(bytes, off, Math.min(len, readSize));
            }
        };
        assertEquals(expected.toString(), HttpRequestHead.readUtf8Body(input, payload.length));
        assertEquals('!', input.read());
    }

    @Test public void smallAndLargeBodiesRejectTruncationAtTheBoundary() throws Exception {
        for (int length : new int[]{1, 64, 4095, 4096, 4097, 8192}) {
            failsWith(400, () -> HttpRequestHead.readUtf8Body(
                    new ByteArrayInputStream(new byte[length - 1]), length));
        }
    }

    @Test public void smallBodyDoesNotSpinOnBrokenZeroProgressStream() throws Exception {
        InputStream input = new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] bytes, int off, int len) { return 0; }
        };
        try {
            HttpRequestHead.readUtf8Body(input, 64);
            fail("Expected an IOException on zero progress");
        } catch (IOException expected) {
            assertTrue(!(expected instanceof HttpRequestHead.HeadException));
        }
    }

    @Test public void reusedHeaderBufferKeepsStringsIndependentAndValidatesShorterLines()
            throws Exception {
        String large = "X-Large: " + repeat('a', 16000) + "é";
        HttpRequestHead head = reader("GET / HTTP/1.1\r\n" + large
                + "\r\nX-Small: \tvalue\n\r\n!");
        assertEquals("GET / HTTP/1.1", head.readRequestLine());
        String first = head.readHeaderLine();
        assertEquals("X-Small: \tvalue", head.readHeaderLine());
        assertEquals("", head.readHeaderLine());
        assertEquals(large, first);

        HttpRequestHead invalid = reader("GET / HTTP/1.1\r\n" + large
                + "\r\nX: \u0000\r\n\r\n");
        invalid.readRequestLine();
        invalid.readHeaderLine();
        failsWith(400, invalid::readHeaderLine);
    }

    @Test public void chunkedBodyDecodesUtf8AcrossChunksAndLeavesFollowingBytes()
            throws Exception {
        String content = repeat('A', 8191) + "🚗é" + repeat('z', 32000);
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        // Split within the four-byte emoji, then within the two-byte accent.
        int[] lengths = {8192, 4, payload.length - 8196};
        int offset = 0;
        for (int length : lengths) {
            wire.write((Integer.toHexString(length) + ";ext=test\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            wire.write(payload, offset, length);
            wire.write('\r');
            wire.write('\n');
            offset += length;
        }
        wire.write(("0\nX-Trailer: " + repeat('x', 1024) + "\r\nX: y\n\n!")
                .getBytes(StandardCharsets.US_ASCII));
        InputStream input = new BufferedInputStream(new ByteArrayInputStream(wire.toByteArray()), 17);
        assertEquals(content, HttpRequestHead.readChunkedUtf8Body(input, payload.length));
        assertEquals('!', input.read());
    }

    @Test public void chunkedBodyRejectsMalformedFramingAndPreservesEmptyAndInvalidUtf8()
            throws Exception {
        assertNull(chunked("0\r\n\r\n", 0));
        assertEquals("abc", chunked("3\nabc\n0\n\n", 3));
        failsWith(413, () -> chunked("2\r\nab\r\n2\r\ncd\r\n0\r\n\r\n", 3));
        failsWith(400, () -> chunked("2\r\na", 4));
        failsWith(400, () -> chunked("1\r\naX\r\n0\r\n\r\n", 4));
        failsWith(400, () -> chunked("1\r\na\r\n0\r\nX: \rX\r\n\r\n", 4));
        failsWith(400, () -> chunked("0\r\nX: y\r\n", 4));
        failsWith(431, () -> chunked("0\r\n" + repeat("X: y\r\n", 101) + "\r\n", 4));
        byte[] malformed = {'2', '\n', (byte) 0xc3, 'x', '\n', '0', '\n', '\n'};
        assertEquals(new String(new byte[]{(byte) 0xc3, 'x'}, StandardCharsets.UTF_8),
                HttpRequestHead.readChunkedUtf8Body(new ByteArrayInputStream(malformed), 2));
    }

    private static String chunked(String wire, int limit) throws Exception {
        return HttpRequestHead.readChunkedUtf8Body(
                new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), limit);
    }

    private static String repeat(char ch, int count) {
        StringBuilder s = new StringBuilder(count);
        for (int i = 0; i < count; i++) s.append(ch);
        return s.toString();
    }

    private static String repeat(String value, int count) {
        StringBuilder s = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) s.append(value);
        return s.toString();
    }
}
