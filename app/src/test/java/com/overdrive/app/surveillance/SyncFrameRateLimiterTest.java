package com.overdrive.app.surveillance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Viewer-driven forced IDRs are coalesced to at most one per second per
 * encoder: the first request passes, later ones inside the window ride the
 * IDR already on its way, and the window re-opens after it elapses.
 */
public class SyncFrameRateLimiterTest {

    private long nowMs = 5_000L;

    @Test
    public void motionWindowStartsAtZeroAndKeepsOneHundredMillisecondBoundaries() {
        nowMs = 0L;
        SyncFrameRateLimiter limiter = new SyncFrameRateLimiter(() -> nowMs, 100L);
        assertTrue(limiter.tryAcquire());
        nowMs = 99L;
        assertFalse(limiter.tryAcquire());
        nowMs = 100L;
        assertTrue(limiter.tryAcquire());
        nowMs = 101L;
        assertFalse(limiter.tryAcquire());
        nowMs = 199L;
        assertFalse(limiter.tryAcquire());
        nowMs = 200L;
        assertTrue(limiter.tryAcquire());
        // A long suspension admits the next frame immediately, then opens a
        // new 100 ms window. No wall timestamp enters the limiter.
        nowMs = 3_600_200L;
        assertTrue(limiter.tryAcquire());
        nowMs++;
        assertFalse(limiter.tryAcquire());
    }

    @Test
    public void firstRequestPassesSecondWithinWindowIsCoalescedThirdAfterWindowPasses() {
        SyncFrameRateLimiter limiter =
                new SyncFrameRateLimiter(() -> nowMs, SyncFrameRateLimiter.MIN_INTERVAL_MS);

        assertTrue("first request passes immediately", limiter.tryAcquire());
        nowMs += 400;
        assertFalse("400 ms later: coalesced", limiter.tryAcquire());
        nowMs += 599;
        assertFalse("999 ms after the grant: still coalesced", limiter.tryAcquire());
        nowMs += 1;
        assertTrue("1000 ms after the grant: passes", limiter.tryAcquire());
        assertFalse("the new grant opens a new window", limiter.tryAcquire());
    }

    @Test
    public void coalescedRequestsDoNotExtendTheWindow() {
        SyncFrameRateLimiter limiter = new SyncFrameRateLimiter(() -> nowMs, 1_000L);
        assertTrue(limiter.tryAcquire());
        for (int i = 0; i < 9; i++) {
            nowMs += 100;
            assertFalse(limiter.tryAcquire());
        }
        nowMs += 100;   // exactly 1 s after the grant, despite 9 coalesced requests
        assertTrue(limiter.tryAcquire());
    }

    @Test
    public void defaultWindowIsOneSecondAndClockIsMonotonic() {
        assertEquals(1_000L, SyncFrameRateLimiter.MIN_INTERVAL_MS);
        // Production constructor must be usable off-device (System.nanoTime, no SystemClock).
        SyncFrameRateLimiter limiter = new SyncFrameRateLimiter();
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    /** The stream-lane callers use the limited entry point. */
    @Test
    public void streamLaneCallersUseTheRateLimitedRequest() throws Exception {
        String httpServer = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/HttpServer.java");
        String pano = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/camera/PanoramicCameraGpu.java");

        assertTrue(httpServer.contains("requestSyncFrameRateLimited()"));
        assertFalse("the /ws sender must not bypass the limiter",
                httpServer.contains("encoder.requestSyncFrame();"));
        assertTrue(pano.contains("localStreamEncoder.requestSyncFrameRateLimited();"));
        assertFalse(pano.contains("localStreamEncoder.requestSyncFrame();"));
        // M7: never touch the stream encoder's bitrate from the sender — it
        // would shrink the recorder's shared pre-record ring.
        assertFalse(httpServer.contains(".setBitrate("));
    }

    @Test
    public void legacyKeyframeRequestsReachSharedLimiterWithoutOpeningRecoveryGate() throws Exception {
        String server = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/streaming/WebSocketStreamServer.java");
        String pipeline = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/surveillance/GpuSurveillancePipeline.java");
        assertTrue(pipeline.contains("setKeyframeRequestHook(encoderLocal::requestSyncFrameRateLimited)"));
        assertFalse(server.contains("shutdownRequested || waitingForIdr.contains(conn)"));
        int waiting = server.indexOf("if (waitingForIdr.contains(conn)) {");
        assertTrue(waiting >= 0);
        String waitingBranch = server.substring(waiting, server.indexOf('}', waiting));
        assertTrue(waitingBranch.contains("requestKeyframeIfConnected(conn);"));
        assertTrue(waitingBranch.contains("return;"));
        assertFalse(waitingBranch.contains("sendBounded("));
        assertFalse(waitingBranch.contains("waitingForIdr.remove("));
        int rejected = server.indexOf("if (!sendBounded(conn, ByteBuffer.wrap(sps), false)) {");
        assertTrue(rejected >= 0);
        assertTrue(server.substring(rejected, server.indexOf('}', rejected))
                .contains("requestKeyframeIfConnected(conn);"));
        int helper = server.indexOf("private void requestKeyframeIfConnected(WebSocket conn) {");
        assertTrue(helper >= 0);
        String hook = server.substring(helper, server.indexOf("public void onMessage(WebSocket conn, ByteBuffer", helper));
        assertTrue(hook.contains("if (shutdownRequested || !clients.contains(conn) || !conn.isOpen()) return;"));
        assertTrue(hook.contains("hook.run();"));
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
