package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
        failsWith(400, () -> new HttpRequestHead.Framing()
                .accept("Transfer-Encoding: chunked"));
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
