package com.overdrive.app.streaming;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression contract for security finding
 * {@code streaming/WebSocketStreamServer.java:onOpen-8887-no-auth}:
 * the raw H.264 WebSocket port must be bound to loopback only, never to the
 * wildcard address, so it is unreachable from LAN/tunnel peers.
 */
public class WebSocketStreamServerBindContractTest {

    private static String source() throws Exception {
        return new String(
                Files.readAllBytes(Paths.get(
                        "src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java")),
                StandardCharsets.UTF_8);
    }

    @Test
    public void constructorsBindLoopbackNotWildcard() throws Exception {
        String s = source();
        assertTrue(
                "default constructor must bind an explicit loopback address",
                s.contains("super(new InetSocketAddress(LOOPBACK, PORT))"));
        assertTrue(
                "port constructor must bind an explicit loopback address",
                s.contains("super(new InetSocketAddress(LOOPBACK, port))"));
        assertTrue(
                "loopback constant must resolve to 127.0.0.1 (or the loopback fallback)",
                s.contains("InetAddress.getByName(\"127.0.0.1\")")
                        && s.contains("InetAddress.getLoopbackAddress()"));
        assertFalse(
                "no constructor may use the wildcard single-arg InetSocketAddress(port)",
                s.contains("super(new InetSocketAddress(PORT))")
                        || s.contains("super(new InetSocketAddress(port))"));
    }
}
