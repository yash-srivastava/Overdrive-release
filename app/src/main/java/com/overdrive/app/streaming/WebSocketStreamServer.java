package com.overdrive.app.streaming;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Timer;
import java.util.TimerTask;

import android.media.MediaCodec;
import com.overdrive.app.surveillance.HardwareEventRecorderGpu;
import com.overdrive.app.logging.DaemonLogger;

public class WebSocketStreamServer extends WebSocketServer
        implements HardwareEventRecorderGpu.StreamCallback {

    private static final String TAG = "WSStreamServer";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final int PORT = 8887;
    private static final long IDLE_TIMEOUT_MS = 30_000;
    // KF-RESYNC-002: how long to wait before retrying a per-client SPS/PPS
    // re-send when a "keyframe" request arrives before the encoder has
    // published its format (cachedSpsPps still null). A freshly-started
    // streamEncoder dequeues its first INFO_OUTPUT_FORMAT_CHANGED within a few
    // frame intervals (~tens of ms at 10–15 fps); 50ms comfortably covers
    // that one-frame race while keeping the deferral imperceptible at reveal.
    private static final long KEYFRAME_RESEND_DEFER_MS = 50L;

    private volatile byte[] cachedSpsPps = null;
    
    /** Returns cached SPS/PPS for late-joining clients. */
    public byte[] getCachedSpsPps() { return cachedSpsPps; }
    
    private final Set<WebSocket> clients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Timer idleTimer;
    private volatile long lastClientDisconnectElapsedMs = 0;
    private volatile boolean idleShutdownTriggered = false;
    private Runnable idleShutdownCallback;
    private long frameCount = 0;
    private long lastLogTime = 0;
    
    // Track external clients (e.g., from HttpServer /ws path)
    private volatile int externalClientCount = 0;
    
    // Reusable frame buffer. Lazy-allocated on the first H.264 packet so a
    // server instance that never receives a client (idle dashcam, surveillance
    // off) holds nothing. shutdown() drops it back to null.
    // Capped at MAX_REUSABLE_FRAME_BYTES — a runaway IDR (corrupt encoder
    // output) won't pin a multi-MB buffer for the daemon's lifetime.
    private static final int INITIAL_REUSABLE_FRAME_BYTES = 256 * 1024;
    private static final int MAX_REUSABLE_FRAME_BYTES = 4 * 1024 * 1024;
    private byte[] reusableFrameBuffer = null;

    // SECURITY (run-2 finding streaming/WebSocketStreamServer.java:onOpen-8887-no-auth):
    // the raw H.264 port must never be reachable from the network. Bind loopback
    // only — the supported remote path is the authenticated /ws relay on 8080
    // (HttpServer), which forwards frames via registerExternalClient().
    private static final InetAddress LOOPBACK;
    static {
        InetAddress lb;
        try {
            lb = InetAddress.getByName("127.0.0.1");
        } catch (java.net.UnknownHostException e) {
            lb = InetAddress.getLoopbackAddress();
        }
        LOOPBACK = lb;
    }

    public WebSocketStreamServer() {
        super(new InetSocketAddress(LOOPBACK, PORT));
        setReuseAddr(true);
        setConnectionLostTimeout(30);
        logger.info("WebSocketStreamServer created on " + LOOPBACK.getHostAddress() + ":" + PORT);
    }

    public WebSocketStreamServer(int port) {
        super(new InetSocketAddress(LOOPBACK, port));
        setReuseAddr(true);
        setConnectionLostTimeout(30);
        logger.info("WebSocketStreamServer created on " + LOOPBACK.getHostAddress() + ":" + port);
    }

    public void setIdleShutdownCallback(Runnable callback) {
        this.idleShutdownCallback = callback;
    }
    
    /**
     * Register an external client (e.g., from HttpServer /ws path).
     * This prevents idle timeout while external clients are connected.
     */
    public synchronized void registerExternalClient() {
        externalClientCount++;
        // An idle callback may have been queued immediately before this
        // subscription. If its lifecycle re-check sees us and cancels the
        // shutdown, the next disconnect must still be able to arm a timer.
        idleShutdownTriggered = false;
        cancelIdleTimer();
        logger.info("External client registered (total: " + externalClientCount + ")");
    }
    
    /**
     * Unregister an external client.
     */
    public synchronized void unregisterExternalClient() {
        externalClientCount = Math.max(0, externalClientCount - 1);
        logger.info("External client unregistered (remaining: " + externalClientCount + ")");
        if (clients.isEmpty() && externalClientCount == 0) {
            lastClientDisconnectElapsedMs =
                    android.os.SystemClock.elapsedRealtime();
            startIdleTimer();
        }
    }
    
    /**
     * Check if there are any active clients (internal or external).
     */
    public boolean hasActiveClients() {
        return !clients.isEmpty() || externalClientCount > 0;
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        logger.info("WS Client connected: " + conn.getRemoteSocketAddress() + " (total: " + clients.size() + ")");
        cancelIdleTimer();
        idleShutdownTriggered = false;
        if (cachedSpsPps != null) {
            try {
                conn.send(cachedSpsPps);
                logger.info("Sent cached SPS/PPS (" + cachedSpsPps.length + " bytes)");
            } catch (Exception e) {
                logger.error("Failed to send SPS/PPS", e);
            }
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        logger.info("WS Client disconnected (remaining: " + clients.size() + ")");
        if (clients.isEmpty()) {
            lastClientDisconnectElapsedMs =
                    android.os.SystemClock.elapsedRealtime();
            startIdleTimer();
        }
    }

    /** Optional hook to ask the owning encoder for a fresh IDR when a client
     *  requests a keyframe. Set by the pipeline to encoder::requestSyncFrame. */
    private Runnable keyframeRequestHook;
    public void setKeyframeRequestHook(Runnable hook) { this.keyframeRequestHook = hook; }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if ("keyframe".equals(message)) {
            // A live-view stream client (port 8887, fed by streamEncoder) requests a
            // clean decode restart — typically because its decoder just (re)gained a
            // Surface AFTER the connection's one-shot onOpen SPS/PPS replay was already
            // sent and dropped (decoder had no Surface yet). (The blind-spot lane no
            // longer flows through any WS server — it is the native SurfaceControl
            // path — so a "keyframe" request here only ever comes from the live-view
            // client; the prior "blind-spot overlay" attribution is obsolete.)
            // Without re-delivering
            // the parameter sets the decoder can never configure (maybeConfigure
            // blocks on pps==null) and shows permanent black. So: (1) re-send the
            // cached SPS/PPS to THIS client, and (2) ask the encoder for a fresh
            // IDR so the next frame is independently decodable.
            logger.info("Client requested keyframe — re-sending SPS/PPS + sync frame");
            byte[] sps = cachedSpsPps;
            if (sps != null && sps.length > 0) {
                try { conn.send(sps); } catch (Exception e) {
                    logger.warn("keyframe: SPS/PPS resend failed: " + e.getMessage());
                }
            } else {
                // FIX KF-RESYNC-002: cachedSpsPps is still null because the owning
                // encoder hasn't published its format yet. The drainer only calls
                // onSpsPps() when it dequeues INFO_OUTPUT_FORMAT_CHANGED (the encoder's
                // first coded output) — until then there are NO parameter sets to send.
                // This races a fresh stream start: a client connects fast and its
                // onOpen fires "keyframe" within milliseconds, often BEFORE the just-
                // started streamEncoder dequeues its first frame. Silently dropping the
                // re-send here is the connect-then-black screen — the per-client re-deliver
                // promised by this handler never happens, and once streamHeadersSent
                // flips true the drainer won't re-emit on its own either, so a client
                // that asked too early gets nothing and maybeConfigure() blocks on
                // pps==null. Defer one short retry: by the time it fires the encoder
                // has almost always published format, and we re-send to THIS client
                // transparently. This adds NO teardown path and is bounded — it only
                // touches a single deferred send per keyframe request (the requestSync
                // hook below still fires immediately to bias the next frame to an IDR),
                // so keep-warm intent is untouched. Use a one-shot daemon Timer so we
                // never block the WS reader thread; conn.isOpen() guards a client that
                // disconnected during the deferral window.
                final WebSocket deferredConn = conn;
                Timer t = new Timer("WS-KeyframeRetry", true);
                t.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        byte[] late = cachedSpsPps;
                        if (late != null && late.length > 0 && deferredConn.isOpen()) {
                            try { deferredConn.send(late); } catch (Exception e) {
                                logger.warn("keyframe: deferred SPS/PPS resend failed: " + e.getMessage());
                            }
                        } else if (late == null) {
                            // Encoder STILL hasn't published format after the deferral —
                            // log so a wedged-encoder lane is visible (W-level: app-side
                            // logcat only surfaces W/E). The onOpen one-shot already ran,
                            // and the drainer's first INFO_OUTPUT_FORMAT_CHANGED will
                            // sendToAll() this client anyway, so this is the diagnostic,
                            // not a second retry loop (which would risk an unbounded
                            // timer storm against a genuinely dead encoder).
                            logger.warn("keyframe: encoder format still unpublished after deferral — relying on drainer onSpsPps broadcast");
                        }
                    }
                }, KEYFRAME_RESEND_DEFER_MS);
            }
            Runnable hook = keyframeRequestHook;
            if (hook != null) {
                try { hook.run(); } catch (Throwable t) {
                    logger.warn("keyframe: sync-frame hook failed: " + t.getMessage());
                }
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) { }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            clients.remove(conn);
            logger.error("WS Error: " + ex.getMessage());
            if (clients.isEmpty()) {
                lastClientDisconnectElapsedMs =
                        android.os.SystemClock.elapsedRealtime();
                startIdleTimer();
            }
        } else {
            logger.error("WS Server error: " + ex.getMessage());
        }
    }

    @Override
    public void onStart() {
        logger.info("WebSocket Stream Server started on port " + PORT);
        lastClientDisconnectElapsedMs =
                android.os.SystemClock.elapsedRealtime();
        startIdleTimer();
    }

    private synchronized void startIdleTimer() {
        cancelIdleTimer();
        idleTimer = new Timer("WS-IdleTimer", true);
        idleTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                checkIdleTimeout();
            }
        }, IDLE_TIMEOUT_MS, 5000);
        logger.info("Idle timer started - shutdown after " + (IDLE_TIMEOUT_MS / 1000) + "s");
    }

    private synchronized void cancelIdleTimer() {
        if (idleTimer != null) {
            idleTimer.cancel();
            idleTimer = null;
        }
    }

    private synchronized void checkIdleTimeout() {
        if (!clients.isEmpty() || externalClientCount > 0) {
            cancelIdleTimer();
            return;
        }
        long idleTime = android.os.SystemClock.elapsedRealtime()
                - lastClientDisconnectElapsedMs;
        if (idleTime >= IDLE_TIMEOUT_MS && !idleShutdownTriggered) {
            idleShutdownTriggered = true;
            logger.info("Idle timeout (" + (idleTime / 1000) + "s) - triggering shutdown");
            cancelIdleTimer();
            if (idleShutdownCallback != null) {
                try {
                    idleShutdownCallback.run();
                } catch (Exception e) {
                    logger.error("Idle shutdown callback error", e);
                }
            } else {
                // FIX BS-RC-001: no idle-shutdown callback was registered. Without a
                // callback the bound port would stay held forever after the last
                // client disconnects — so self-release here so the port frees within
                // IDLE_TIMEOUT_MS of the last disconnect. The live-view wsStreamServer
                // always has a callback and takes the branch above.
                //
                // FIX BS-DEFECT-D: this server is the LIVE-VIEW H.264 stream lane
                // (port 8887, fed by streamEncoder). It is NOT the blind-spot lane.
                // The blind-spot feature now uses the NATIVE SurfaceControl path
                // (GpuSurveillancePipeline.enableBlindSpot → BsNativeLayer): the GPU
                // composites the stitched 7/8 view straight onto an on-screen layer,
                // with NO encoder, NO WebSocket server, and NO app-side decoder. There
                // is therefore no BS WS server bound on 8889 and no WsH264Client to
                // reconnect-storm it — the prior doc that attributed this idle branch
                // to a BlindSpotOverlayService.tick()-driven BS lane is obsolete and
                // has been corrected to prevent the false "8889 should be listening"
                // expectation that masked the un-armed native lane.
                //
                // Use logger.warn so the self-release is visible on-device (I/D log
                // levels are filtered out on the head unit); a port that could not
                // free itself is a recoverability signal, not routine info.
                logger.warn("Idle timeout with no shutdown callback - self-releasing port " + PORT);
                try {
                    shutdown();
                } catch (Exception e) {
                    logger.error("Idle self-shutdown error", e);
                }
            }
        }
    }

    @Override
    public void onSpsPps(ByteBuffer sps, ByteBuffer pps) {
        int spsSize = sps.remaining();
        int ppsSize = pps.remaining();
        cachedSpsPps = new byte[spsSize + ppsSize];
        sps.get(cachedSpsPps, 0, spsSize);
        pps.get(cachedSpsPps, spsSize, ppsSize);
        logger.info("Cached SPS/PPS: " + spsSize + " + " + ppsSize + " bytes");
        sendToAll(cachedSpsPps);
    }

    @Override
    public void onH264Packet(ByteBuffer data, MediaCodec.BufferInfo info) {
        if (clients.isEmpty()) return;

        // Lazy-init the reusable buffer on the first packet that actually
        // reaches a connected client. With surveillance on but no streaming
        // viewer the encoder still drains, but onH264Packet exits at the
        // empty-clients check above and we never spend the 256 KB.
        int frameSize = info.size;
        // Hard cap before any allocation: a corrupt encoder output larger
        // than MAX_REUSABLE_FRAME_BYTES is dropped. Putting this BEFORE the
        // lazy-init branch closes a window where buf=null + frameSize>MAX
        // would clamp seed to MAX and then `data.get(buf, 0, frameSize)`
        // would throw IndexOutOfBoundsException because frameSize > buf.length.
        if (frameSize > MAX_REUSABLE_FRAME_BYTES) {
            logger.warn("Dropping oversize H.264 packet: " + frameSize
                    + " bytes (cap=" + MAX_REUSABLE_FRAME_BYTES + ")");
            return;
        }
        byte[] buf = reusableFrameBuffer;
        if (buf == null) {
            int seed = Math.max(INITIAL_REUSABLE_FRAME_BYTES, frameSize);
            seed = Math.min(seed, MAX_REUSABLE_FRAME_BYTES);
            buf = new byte[seed];
            reusableFrameBuffer = buf;
        } else if (frameSize > buf.length) {
            // Frame larger than current buffer. Grow up to MAX (already
            // bounded above, so target == frameSize is achievable here).
            int target = Math.min(MAX_REUSABLE_FRAME_BYTES, Math.max(frameSize, buf.length * 2));
            buf = new byte[target];
            reusableFrameBuffer = buf;
            logger.warn("Resized frame buffer to " + buf.length + " bytes");
        }

        data.position(info.offset);
        data.get(buf, 0, frameSize);

        // Send to all clients (they copy internally)
        sendToAll(buf, frameSize);
        
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 10000) {
            logger.info("Stats: " + frameCount + " frames, " + clients.size() + " clients");
            lastLogTime = now;
        }
    }

    private void sendToAll(byte[] data) {
        sendToAll(data, data.length);
    }
    
    private void sendToAll(byte[] data, int length) {
        // Single ByteBuffer.wrap reused across clients. java-WebSocket reads
        // via position()/remaining() and copies internally before queueing,
        // so resetting position before each conn.send is safe and gets us
        // back the per-frame allocations the loop used to make (one wrapper
        // per client × N clients × 30 fps).
        ByteBuffer wrapped = ByteBuffer.wrap(data, 0, length);
        for (WebSocket conn : clients) {
            try {
                if (conn.isOpen()) {
                    wrapped.position(0);
                    conn.send(wrapped);
                }
                else clients.remove(conn);
            } catch (Exception e) {
                clients.remove(conn);
            }
        }
    }

    public int getClientCount() { return clients.size(); }
    public boolean hasClients() { return !clients.isEmpty(); }

    public void shutdown() {
        try {
            cancelIdleTimer();
            for (WebSocket conn : clients) {
                try { conn.close(); } catch (Exception ignored) {}
            }
            clients.clear();
            cachedSpsPps = null;
            reusableFrameBuffer = null;
            frameCount = 0;
            stop(1000);
            logger.info("WebSocket Stream Server stopped");
        } catch (Exception e) {
            logger.error("Error stopping server", e);
        }
    }
}
