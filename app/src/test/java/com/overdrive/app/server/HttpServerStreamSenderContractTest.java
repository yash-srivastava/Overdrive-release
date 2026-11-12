package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Source pins for the single-port /ws sender and the listening socket.
 * The behaviour itself is covered by {@link StreamFrameDropPolicyTest}; this
 * guards the wiring in HttpServer that cannot be exercised without sockets
 * and an encoder.
 */
public class HttpServerStreamSenderContractTest {

    private static final String SERVER = "app/src/main/java/com/overdrive/app/server/HttpServer.java";

    @Test
    public void listenBacklogIs128() throws Exception {
        assertEquals(128, HttpServer.LISTEN_BACKLOG);
        String source = readRepositoryFile(SERVER);
        assertTrue(source.contains("boundSocket.setReuseAddress(true)"));
        assertTrue(source.contains("boundSocket.bind(new java.net.InetSocketAddress("));
        assertTrue(source.contains("InetAddress.getByName(\"0.0.0.0\"), port), LISTEN_BACKLOG)"));
        assertFalse(source.contains("new ServerSocket(port, LISTEN_BACKLOG,"));
        assertFalse(source.contains("new ServerSocket(port, 10,"));
    }

    @Test
    public void acceptedHttpConnectionsAndShutdownAreResourceBounded() throws Exception {
        String source = readRepositoryFile(SERVER);
        assertTrue(source.contains("new ArrayBlockingQueue<>(HTTP_PENDING_CONNECTIONS)"));
        assertTrue(source.contains("new ThreadPoolExecutor.AbortPolicy()"));
        assertTrue(source.contains("private volatile ServerSocket serverSocket"));
        assertTrue(source.contains("clientSockets.add(client)"));
        assertTrue(source.contains("clientSockets.clear()"));
        assertFalse(source.contains("private final ExecutorService threadPool = Executors.newFixedThreadPool(32);"));
    }

    @Test
    public void senderUsesTheKeyframeAwarePolicyAndNeverBlocksTheEncoder() throws Exception {
        String source = readRepositoryFile(SERVER);
        int start = source.indexOf("private void streamH264ToWebSocket(");
        int end = source.indexOf("private void sendWebSocketBinaryFrame(", start);
        assertTrue(start >= 0 && end > start);
        String sender = source.substring(start, end);

        assertTrue(sender.contains("new StreamFrameDropPolicy()"));
        assertFalse("the raw byte[] queue is gone", sender.contains("ArrayBlockingQueue"));
        // Encoder drain path: offer*, never put()/blocking calls.
        String callback = sender.substring(
                sender.indexOf("public void onH264Packet("), sender.indexOf("public void onStreamRetired()"));
        assertTrue(callback.contains("frameQueue.offerVideo(frame, isKeyframe, info.presentationTimeUs)"));
        assertTrue(callback.contains("BUFFER_FLAG_KEY_FRAME"));
        assertFalse(callback.contains(".put("));
        assertFalse(callback.contains("Thread.sleep"));
        assertTrue("entering skip asks the encoder for an IDR through the rate limiter",
                callback.contains("consumeSyncFrameRequest()") && callback.contains("requestSyncFrameRateLimited()"));
        assertTrue(sender.contains("frameQueue.offerConfig(combined);"));
    }

    @Test
    public void retiredEncoderIsReattachedOnTheSameSocketOrClosedWith1012() throws Exception {
        String source = readRepositoryFile(SERVER);
        assertEquals(250L, HttpServer.REATTACH_RETRY_MS);
        assertEquals(3_000L, HttpServer.REATTACH_TIMEOUT_MS);

        int start = source.indexOf("private void streamH264ToWebSocket(");
        int end = source.indexOf("private void sendWebSocketBinaryFrame(", start);
        String sender = source.substring(start, end);
        // The retirement notice only offers a sentinel from the encoder's lock.
        String retired = sender.substring(
                sender.indexOf("public void onStreamRetired()"), sender.indexOf("pipeline.registerExternalStreamClient(callback)"));
        assertTrue(retired.contains("frameQueue.offerRetired();"));
        assertFalse(retired.contains("registerExternalStreamClient"));

        // The loop re-attaches, resets the gates, and falls back to 1012.
        assertTrue(sender.contains("queued.isRetired()"));
        assertTrue(sender.contains("reattachAfterRetire(pipeline, callback, headersReceived, gotKeyframe,"));
        assertTrue(sender.contains("frameQueue, attachedEncoder);"));
        assertTrue(sender.contains("sendWebSocketClose(out, 1012, \"Stream restarting\");"));
        assertTrue("last-resort idle exit stays", sender.contains("System.currentTimeMillis() - lastFrameTime > 60000"));

        String reattach = source.substring(
                source.indexOf("reattachAfterRetire("), source.indexOf("private void sendWebSocketBinaryFrame("));
        assertTrue(reattach.contains("attachedEncoder.set(null);"));
        assertTrue(reattach.contains("headersReceived.set(false);"));
        assertTrue(reattach.contains("gotKeyframe.set(false);"));
        assertTrue(reattach.contains("frameQueue.resetForReattach();"));
        assertTrue(reattach.contains("attachedEncoder.set(next.getEncoder());"));
        assertTrue(reattach.contains("Thread.sleep(REATTACH_RETRY_MS);"));
    }

    @Test
    public void tunnelRelayedViewersGetSmallerSendBuffers() throws Exception {
        String source = readRepositoryFile(SERVER);
        assertTrue(source.contains("client.setSendBufferSize(tunnelRelayed ? 64 * 1024 : 256 * 1024);"));
        assertTrue(source.contains("client.getOutputStream(), tunnelRelayed ? 32 * 1024 : 128 * 1024);"));
        // Detection reuses the existing reverse-proxy fingerprinting.
        int upgrade = source.indexOf("handleWebSocketUpgrade(\n                            client,");
        assertTrue(upgrade >= 0);
        assertTrue(source.substring(upgrade, upgrade + 400).contains("hasTunnelHeaders);"));
    }

    private static String readRepositoryFile(String relativePath) throws Exception {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
