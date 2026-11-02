package com.overdrive.app.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/** Exercises original header parsing and authorization with an in-memory, bounded dummy request. */
public final class BrowserOriginTest {
    private static int passed;
    private static int failed;

    private static final class DummySocket extends Socket {
        final InputStream input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final InetAddress peer;
        final int localPort;
        boolean closed;

        DummySocket(String request, String peer, int localPort) throws Exception {
            input = new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
            this.peer = InetAddress.getByName(peer);
            this.localPort = localPort;
        }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(int timeout) {}
        @Override public InetAddress getInetAddress() { return peer; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(peer, 12345); }
        @Override public int getLocalPort() { return localPort; }
        @Override public void close() { closed = true; }
        String response() { return new String(output.toByteArray(), StandardCharsets.UTF_8); }
    }

    private static DummySocket request(String path, String headers, String peer, int port) throws Exception {
        DummySocket socket = new DummySocket("GET " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n" + headers + "\r\n", peer, port);
        Admission.websocket = null;
        new HttpServer().runFixture(socket);
        if (!socket.closed) throw new AssertionError("Request socket was not closed");
        return socket;
    }

    private static void status(String name, String path, String headers, String peer, int port, int expected) {
        run(name, () -> {
            DummySocket socket = request(path, headers, peer, port);
            String response = socket.response();
            if (!response.startsWith("HTTP/1.1 " + expected + " ")) {
                throw new AssertionError("Expected " + expected + ", got " + firstLine(response));
            }
            if (expected == 200 && !response.contains("\"dummySentinel\":true")) {
                throw new AssertionError("Authenticated dummy router was not reached");
            }
        });
    }

    private static void websocket(String name, String path, String headers, boolean expected) {
        run(name, () -> {
            DummySocket socket = request(path, "Upgrade: websocket\r\nSec-WebSocket-Key: DUMMY_KEY\r\n"
                    + headers, "127.0.0.1", 8080);
            if ((Admission.websocket != null) != expected) {
                throw new AssertionError("Expected admission=" + expected + ", actual=" + Admission.websocket);
            }
            if (!expected && !socket.response().startsWith("HTTP/1.1 401 ")
                    && !socket.response().startsWith("HTTP/1.1 403 ")) {
                throw new AssertionError("Rejected upgrade must send 401/403, got " + firstLine(socket.response()));
            }
        });
    }

    private interface Check { void run() throws Exception; }
    private static void run(String name, Check check) {
        try { check.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError failure) {
            failed++; System.out.println("FAIL " + name + ": " + failure.getMessage());
        }
    }
    private static String firstLine(String text) { return text.split("\r?\n", 2)[0]; }
    private static String origin(String value) { return "Origin: " + value + "\r\n"; }

    public static void main(String[] args) {
        System.out.println("SCOPE in-memory transport; original handler/auth decisions; dummy validators and endpoints");
        status("native IPv4 loopback remains usable", "/status", "", "127.0.0.1", 8080, 200);
        // Existing peer classification uses SocketAddress text. HotSpot includes IPv6 brackets;
        // this fixture preserves that baseline denial rather than changing an unrelated policy.
        status("existing HotSpot IPv6 peer classification is preserved", "/status", "", "::1", 8080, 401);
        status("same daemon browser origin remains usable", "/status", origin("http://127.0.0.1:8080"), "127.0.0.1", 8080, 200);
        status("localhost browser origin remains usable", "/status", origin("http://localhost:8080"), "127.0.0.1", 8080, 200);
        status("IPv6 browser origin parses on admitted loopback peer", "/status", origin("http://[::1]:8080"), "127.0.0.1", 8080, 200);
        status("valid remote bearer remains usable", "/status", "Authorization: Bearer DUMMY_VALID_JWT\r\n", "192.0.2.2", 8080, 200);
        status("valid remote cookie remains usable", "/status", "Cookie: other=1; byd_session=DUMMY_VALID_JWT\r\n", "192.0.2.2", 8080, 200);
        status("authenticated external webapp remains usable", "/status", origin("https://owner.invalid")
                + "Authorization: Bearer DUMMY_VALID_JWT\r\n", "127.0.0.1", 8080, 200);
        status("remote invalid bearer remains denied", "/status", "Authorization: Bearer INVALID_DUMMY\r\n", "192.0.2.2", 8080, 401);
        status("native stale bearer retains local fallback", "/status", "Authorization: Bearer INVALID_DUMMY\r\n", "127.0.0.1", 8080, 200);
        status("remote anonymous remains denied", "/status", "", "192.0.2.2", 8080, 401);
        status("tunnel disables local fallback", "/status", "X-Forwarded-For: 192.0.2.2\r\n", "127.0.0.1", 8080, 401);
        status("valid tunneled session remains usable", "/status", "X-Forwarded-For: 192.0.2.2\r\nAuthorization: Bearer DUMMY_VALID_JWT\r\n", "127.0.0.1", 8080, 200);
        status("public assets remain public", "/shared/theme.css", origin("https://other.invalid"), "192.0.2.2", 8080, 200);
        status("signed thumbnail remains usable", "/thumb/dummy.jpg?t=DUMMY_VALID_THUMB", origin("https://push.invalid"), "192.0.2.2", 8080, 200);
        status("JWT-only WireGuard retains no local fallback", "/api/wireguard", "", "127.0.0.1", 8080, 401);
        status("GenAI retains no local fallback", "/api/genai/status", "", "127.0.0.1", 8080, 401);
        status("GenAI retains foreign-origin rejection with JWT", "/api/genai/status", origin("https://other.invalid")
                + "Authorization: Bearer DUMMY_VALID_JWT\r\n", "127.0.0.1", 8080, 403);
        status("GenAI local valid session remains usable", "/api/genai/status", origin("http://127.0.0.1:8080")
                + "Authorization: Bearer DUMMY_VALID_JWT\r\n", "127.0.0.1", 8080, 200);

        String[] rejectedOrigins = {"http://203.0.113.10:18080", "", "null", "file://local.invalid", "http://127.0.0.1:8081",
                "https://127.0.0.1:8080", "http://user@127.0.0.1:8080", "http://127.0.0.1:8080/private",
                "http://127.0.0.1:8080?query=1", "http://127.0.0.1:8080#fragment", "not-an-origin",
                "http://127.0.0.1:8080, http://other.invalid", "http://127.0.0.1:8080 http://other.invalid"};
        for (String value : rejectedOrigins) {
            status("untrusted origin cannot use loopback fallback: " + value, "/status", origin(value), "127.0.0.1", 8080, 401);
        }
        status("stale bearer cannot restore foreign origin fallback", "/status", origin("https://other.invalid")
                + "Authorization: Bearer INVALID_DUMMY\r\n", "127.0.0.1", 8080, 401);
        status("actual accepted port is honored", "/status", origin("http://127.0.0.1:18080"), "127.0.0.1", 18080, 200);
        status("fixed default port cannot authorize another listener", "/status", origin("http://127.0.0.1:8080"), "127.0.0.1", 18080, 401);
        status("HTTP default port normalization", "/status", origin("http://127.0.0.1"), "127.0.0.1", 80, 200);
        status("explicit HTTP default port", "/status", origin("http://127.0.0.1:80"), "127.0.0.1", 80, 200);
        status("Host cannot authorize a foreign browser origin", "/status", "Host: other.invalid\r\n" + origin("http://other.invalid"), "127.0.0.1", 8080, 401);
        status("duplicate Origin cannot hide a foreign first origin", "/status", origin("https://other.invalid")
                + origin("http://127.0.0.1:8080"), "127.0.0.1", 8080, 401);
        status("duplicate Origin cannot hide a foreign last origin", "/status", origin("http://127.0.0.1:8080")
                + origin("https://other.invalid"), "127.0.0.1", 8080, 401);

        websocket("native video upgrade retains fallback", "/ws", "", true);
        websocket("same-origin video upgrade retains fallback", "/ws", origin("http://127.0.0.1:8080"), true);
        websocket("foreign video upgrade cannot use fallback", "/ws", origin("https://other.invalid"), false);
        websocket("foreign communicate upgrade cannot use fallback", "/ws/communicate", origin("https://other.invalid"), false);
        websocket("foreign cabin upgrade cannot use fallback", "/ws/cabin-audio", origin("https://other.invalid"), false);
        websocket("authenticated foreign upgrade retains JWT authority", "/ws?token=DUMMY_VALID_JWT", origin("https://other.invalid"), true);
        websocket("tunnel upgrade retains JWT requirement", "/ws", "X-Forwarded-For: 192.0.2.2\r\n", false);
        websocket("GenAI upgrade retains JWT requirement", "/ws/genai", origin("http://127.0.0.1:8080"), false);
        websocket("GenAI upgrade retains origin rejection", "/ws/genai?token=DUMMY_VALID_JWT", origin("https://other.invalid"), false);

        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed != 0) throw new AssertionError(failed + " authorization regression assertion(s) failed");
    }
}
