package com.overdrive.app.server;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/** Functional compatibility checks for the actual HTTP body handler, with dummy routing. */
public final class HttpBodyCompatibilityTest {
    private static final Method HANDLER = handler();
    private static int passed;
    private static int failed;

    private static Method handler() {
        try {
            Method method = HttpServer.class.getDeclaredMethod("handleClient", Socket.class);
            method.setAccessible(true);
            return method;
        } catch (Exception exception) {
            throw new IllegalStateException("Production fixture setup failed", exception);
        }
    }

    private static final class RequestInput extends InputStream {
        private final byte[] bytes;
        private int position;
        private boolean missingBodyRead;

        RequestInput(byte[] bytes) { this.bytes = bytes; }

        @Override public int read() {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 255;
        }

        @Override public int read(byte[] destination, int offset, int count) {
            if (count == 0) return 0;
            if (position == bytes.length) {
                missingBodyRead = true;
                return -1;
            }
            int copied = Math.min(count, bytes.length - position);
            System.arraycopy(bytes, position, destination, offset, copied);
            position += copied;
            return copied;
        }
    }

    private static final class FixtureSocket extends Socket {
        final RequestInput input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean closed;

        FixtureSocket(String path, int declaredLength, String body, boolean authorized) {
            String headers = "POST " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:8080\r\n"
                    + "X-Forwarded-For: 192.0.2.1\r\n"
                    + (authorized ? "Authorization: Bearer DUMMY_VALID_JWT\r\n" : "")
                    + "Content-Length: " + declaredLength + "\r\n\r\n";
            input = new RequestInput((headers + body).getBytes(StandardCharsets.UTF_8));
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(int milliseconds) {}
        @Override public InetAddress getInetAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public SocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 44444);
        }
        @Override public void close() { closed = true; }

        String response() { return new String(output.toByteArray(), StandardCharsets.UTF_8); }
    }

    private static FixtureSocket invoke(String path, int length, String body, boolean authorized)
            throws Exception {
        FixtureSocket socket = new FixtureSocket(path, length, body, authorized);
        HttpServer.routedBody = null;
        HttpServer.routedCalls = 0;
        HttpServer server = new HttpServer();
        HANDLER.invoke(server, socket);
        require(socket.closed, "Socket must close after handling");
        return socket;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface private interface Test { void run() throws Exception; }

    private static void test(String name, Test test) throws Exception {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (AssertionError failure) {
            failed++;
            System.out.println("FAIL " + name + ": " + failure.getMessage());
        }
    }

    private static void accepted(String body) throws Exception {
        FixtureSocket socket = invoke("/api/audio/library", body.getBytes(StandardCharsets.UTF_8).length,
                                      body, true);
        require(socket.response().startsWith("HTTP/1.1 200"), "Valid body must reach the router");
        require(HttpServer.routedCalls == 1, "Valid request routes exactly once");
        require(body.isEmpty() ? HttpServer.routedBody == null : body.equals(HttpServer.routedBody),
                "Router must retain existing empty/null and nonempty body semantics");
    }

    private static void cap(String path, int cap) throws Exception {
        FixtureSocket socket = invoke(path, cap + 1, "", true);
        require(socket.response().startsWith("HTTP/1.1 413"), "Over-cap request must return 413");
        require(!socket.input.missingBodyRead, "Over-cap request must not read a body");
        require(HttpServer.routedCalls == 0, "Over-cap request must not reach the router");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("RUNTIME " + System.getProperty("java.runtime.version"));
        // Exercise normal requests before the assertion cases.
        for (int count = 0; count < 20; count++) {
            accepted("warmup");
            invoke("/api/audio/library", 16, "", false);
        }
        test("small ASCII body", () -> accepted("{\"sample\":\"synthetic\"}"));
        test("multi-byte UTF-8 body", () -> accepted("{\"sample\":\"₹é漢\"}"));
        test("64KiB ASCII body survives chunked reads", () -> accepted("a".repeat(65536)));
        test("empty authenticated body", () -> accepted(""));
        test("unauthenticated complete body denied", () -> {
            FixtureSocket socket = invoke("/api/audio/library", 4, "data", false);
            require(socket.response().startsWith("HTTP/1.1 401"), "Expected rejection");
            require(HttpServer.routedCalls == 0, "Expected no routing");
        });
        test("generic cap retained", () -> cap("/api/fixture", 16 * 1024 * 1024));
        test("backup cap retained", () -> cap("/api/backup/import", 32 * 1024 * 1024));
        test("audio cap retained", () -> cap("/api/audio/library", 72 * 1024 * 1024));
        test("WireGuard cap retained", () -> cap("/api/wireguard/config", 64 * 1024));
        test("widget cap retained", () -> cap("/api/widgets/custom", 64 * 1024));
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed != 0) throw new AssertionError(failed + " regression assertion(s) failed");
    }
}
