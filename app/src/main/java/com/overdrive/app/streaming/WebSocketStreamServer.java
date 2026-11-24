package com.overdrive.app.streaming;

import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    // Share one daemon scheduler instead of creating a Timer thread for every
    // early keyframe request. Retries are coalesced per connected client below.
    private static final ScheduledExecutorService KEYFRAME_RETRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "WS-KeyframeRetry");
                thread.setDaemon(true);
                return thread;
            });
    private final Set<WebSocket> pendingKeyframeRetries =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Timer idleTimer;
    private volatile long lastClientDisconnectElapsedMs = 0;
    private volatile boolean idleShutdownTriggered = false;
    // Stop is terminal for this server instance. Late onClose/onError callbacks
    // from WebSocketServer.stop() must not spawn a fresh idle Timer thread.
    private volatile boolean shutdownRequested = false;
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

    public WebSocketStreamServer() {
        super(new InetSocketAddress(PORT));
        setReuseAddr(true);
        setConnectionLostTimeout(30);
        logger.info("WebSocketStreamServer created on port " + PORT);
    }

    public WebSocketStreamServer(int port) {
        super(new InetSocketAddress(port));
        setReuseAddr(true);
        setConnectionLostTimeout(30);
        logger.info("WebSocketStreamServer created on port " + port);
    }

    public void setIdleShutdownCallback(Runnable callback) {
        this.idleShutdownCallback = callback;
    }

    private volatile long lastRefusalLogMs = 0L;

    /**
     * Camera video only for a valid session, as on 8080: the session cookie, an Authorization bearer, or ?token= (the
     * pages use the authenticated /ws on 8080; this port is kept for older clients). Not by address: a tailnet peer can
     * arrive as loopback.
     */
    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft,
            ClientHandshake request) throws InvalidDataException {
        String auth = request.hasFieldValue("Authorization") ? request.getFieldValue("Authorization") : null;
        if (auth == null || auth.isEmpty()) auth = bearerFromQuery(request.getResourceDescriptor());
        String cookie = request.hasFieldValue("Cookie") ? request.getFieldValue("Cookie") : null;
        if (!com.overdrive.app.server.AuthMiddleware.hasValidJwt(cookie, auth)) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastRefusalLogMs > 60_000L) {
                lastRefusalLogMs = now;
                logger.warn("WS client refused (no valid session): " + conn.getRemoteSocketAddress());
            }
            throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Unauthorized");
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
    }

    // "Bearer <token>" from a ?token= in the request path, or null.
    private static String bearerFromQuery(String resource) {
        if (resource == null) return null;
        int q = resource.indexOf('?');
        if (q < 0) return null;
        for (String param : resource.substring(q + 1).split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0 && "token".equals(param.substring(0, eq))) {
                try {
                    return "Bearer " + java.net.URLDecoder.decode(param.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
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
        // Serialize admission against shutdown's terminal transition; otherwise
        // an onOpen racing with stop() can re-add a socket after clients.clear().
        synchronized (this) {
            if (shutdownRequested) {
                try { conn.close(); } catch (Exception ignored) { }
                return;
            }
            clients.add(conn);
            cancelIdleTimer();
            idleShutdownTriggered = false;
        }
        logger.info("WS Client connected: " + conn.getRemoteSocketAddress() + " (total: " + clients.size() + ")");
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
        pendingKeyframeRetries.remove(conn);
        logger.info("WS Client disconnected (remaining: " + clients.size() + ")");
        if (clients.isEmpty() && !shutdownRequested) {
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
        if (shutdownRequested) return;
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
                // so keep-warm intent is untouched. The shared retry scheduler (one
                // pending retry per client) never blocks the WS reader thread; conn.isOpen() guards a client that
                // disconnected during the deferral window.
                final WebSocket deferredConn = conn;
                // Repeated requests from one client share a single pending retry.
                if (pendingKeyframeRetries.add(deferredConn)) {
                    KEYFRAME_RETRY_EXECUTOR.schedule(() -> {
                        try {
                            if (shutdownRequested) return;
                            byte[] late = cachedSpsPps;
                            if (late != null && late.length > 0 && deferredConn.isOpen()) {
                                try { deferredConn.send(late); } catch (Exception e) {
                                    logger.warn("keyframe: deferred SPS/PPS resend failed: " + e.getMessage());
                                }
                            } else if (late == null) {
                                // The drainer's format callback remains the fallback broadcast.
                                logger.warn("keyframe: encoder format still unpublished after deferral — relying on drainer onSpsPps broadcast");
                            }
                        } finally {
                            pendingKeyframeRetries.remove(deferredConn);
                        }
                    }, KEYFRAME_RESEND_DEFER_MS, TimeUnit.MILLISECONDS);
                }
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
            pendingKeyframeRetries.remove(conn);
            logger.error("WS Error: " + ex.getMessage());
            if (clients.isEmpty() && !shutdownRequested) {
                lastClientDisconnectElapsedMs =
                        android.os.SystemClock.elapsedRealtime();
                startIdleTimer();
            }
        } else {
            logger.error("WS Server error: " + ex.getMessage());
        }
    }

    @Override
    public synchronized void onStart() {
        if (shutdownRequested) return;
        logger.info("WebSocket Stream Server started on port " + PORT);
        lastClientDisconnectElapsedMs =
                android.os.SystemClock.elapsedRealtime();
        startIdleTimer();
    }

    private synchronized void startIdleTimer() {
        cancelIdleTimer();
        // Both the direct WebSocket port and HTTP /ws share this encoder. The
        // former can have zero clients while the latter remains connected.
        if (!StreamIdleTimeoutPolicy.shouldArm(shutdownRequested,
                clients.size(), externalClientCount, idleShutdownTriggered)) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastClientDisconnectElapsedMs == 0L) lastClientDisconnectElapsedMs = now;
        long delayMs = StreamIdleTimeoutPolicy.remainingMs(
                now, lastClientDisconnectElapsedMs, IDLE_TIMEOUT_MS);
        idleTimer = new Timer("WS-IdleTimer", true);
        // One-shot, not a five-second repeating poll. If the wall clock moves
        // and Timer fires before the monotonic deadline, re-arm the remainder.
        idleTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                checkIdleTimeout();
            }
        }, Math.max(1L, delayMs));
        logger.info("Idle timer armed for " + delayMs + "ms");
    }

    private synchronized void cancelIdleTimer() {
        if (idleTimer != null) {
            idleTimer.cancel();
            idleTimer = null;
        }
    }

    private void checkIdleTimeout() {
        final Runnable callback;
        synchronized (this) {
            if (!StreamIdleTimeoutPolicy.shouldArm(shutdownRequested,
                    clients.size(), externalClientCount, idleShutdownTriggered)) {
                cancelIdleTimer();
                return;
            }
            long idleTime = android.os.SystemClock.elapsedRealtime()
                    - lastClientDisconnectElapsedMs;
            if (idleTime < IDLE_TIMEOUT_MS) {
                // Timer uses wall time; make the decision using monotonic time.
                startIdleTimer();
                return;
            }
            idleShutdownTriggered = true;
            logger.info("Idle timeout (" + (idleTime / 1000) + "s) - triggering shutdown");
            cancelIdleTimer();
            callback = idleShutdownCallback;
        }
        // Do not call the stream owner (or WebSocketServer.stop()) while holding
        // the idle-monitor lock. stop() can wait for onOpen/onClose callbacks,
        // which need that lock to cancel timers or complete admission.
        if (callback != null) {
            try {
                callback.run();
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
            // Close the admission race before self-stop: a viewer may have
            // connected between the timeout decision and this callback.
            synchronized (this) {
                if (shutdownRequested || !clients.isEmpty() || externalClientCount > 0) {
                    return;
                }
                shutdownRequested = true;
            }
            logger.warn("Idle timeout with no shutdown callback - self-releasing port " + PORT);
            try {
                shutdown();
            } catch (Exception e) {
                logger.error("Idle self-shutdown error", e);
            }
        }
    }

    @Override
    public void onSpsPps(ByteBuffer sps, ByteBuffer pps) {
        if (shutdownRequested) return;
        // The callback may be shared with other consumers of the codec format.
        // Do not consume their ByteBuffer positions while caching headers.
        ByteBuffer spsView = sps.duplicate();
        ByteBuffer ppsView = pps.duplicate();
        int spsSize = spsView.remaining();
        int ppsSize = ppsView.remaining();
        byte[] headers = new byte[spsSize + ppsSize];
        spsView.get(headers, 0, spsSize);
        ppsView.get(headers, spsSize, ppsSize);
        // Serialize the final publish with shutdown, which clears the cache.
        synchronized (this) {
            if (shutdownRequested) return;
            cachedSpsPps = headers;
        }
        logger.info("Cached SPS/PPS: " + spsSize + " + " + ppsSize + " bytes");
        sendToAll(headers);
    }

    @Override
    public void onH264Packet(ByteBuffer data, MediaCodec.BufferInfo info) {
        if (shutdownRequested || clients.isEmpty()) return;

        // Lazy-init the reusable buffer on the first packet that actually
        // reaches a connected client. With surveillance on but no streaming
        // viewer the encoder still drains, but onH264Packet exits at the
        // empty-clients check above and we never spend the 256 KB.
        int frameSize = info.size;
        if (!CodecBufferWindow.isValid(data, info.offset, frameSize)) {
            logger.warn("Dropping invalid H.264 buffer window: offset=" + info.offset
                    + " size=" + frameSize + " limit=" + data.limit());
            return;
        }
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

        // The encoder drainer currently restores this buffer after dispatch,
        // but a stream callback must not change the position seen by another
        // callback or by future callers. No per-frame duplicate allocation.
        int originalPosition = data.position();
        try {
            data.position(info.offset);
            data.get(buf, 0, frameSize);
        } finally {
            data.position(originalPosition);
        }

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
        // Set the terminal latch BEFORE conn.close()/stop() invoke onClose or
        // onError. Those asynchronous callbacks must not recreate idle timers.
        synchronized (this) {
            shutdownRequested = true;
            cancelIdleTimer();
        }
        try {
            for (WebSocket conn : clients) {
                try { conn.close(); } catch (Exception ignored) {}
            }
            clients.clear();
            pendingKeyframeRetries.clear();
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
