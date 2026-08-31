package com.overdrive.app.mqtt;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Shared proxy detection utility for sing-box SOCKS/HTTP proxy.
 *
 * The BYD head unit may route internet through a sing-box proxy on port 8119.
 * This helper probes the proxy availability and provides socket factories
 * for both HTTP clients (OkHttp) and MQTT clients (Paho).
 *
 * Probe result is cached for 60 seconds to avoid excessive socket probes.
 * Cache is invalidated on connection failures so the next attempt re-probes.
 */
public class ProxyHelper {

    private static final String TAG = "ProxyHelper";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String PROXY_HOST = "127.0.0.1";
    private static final int PROXY_PORT = 8119;
    private static final int TAILSCALE_PROXY_PORT = 8539;
    private static final String PROXY_ENABLED_FILE = ScratchPaths.path(".tailscale/proxy_enabled");
    // Loopback TCP connect budget. 200ms was too tight: a cold/loaded sing-box (or a
    // probe issued while the proxy is still binding) could miss, and a SINGLE miss
    // poisoned a whole minute (see the asymmetric cache below) → every map search /
    // routing / tile fetch went DIRECT and timed out on in-car mobile data even though
    // the proxy was actually up. 500ms is still trivial against a localhost listener
    // (a refused connection returns immediately; only a genuinely slow accept waits).
    private static final int PROBE_TIMEOUT_MS = 500;
    // ASYMMETRIC cache (root-cause fix for "couldn't search routes while sing-box on"):
    //   • a POSITIVE probe caches for CACHE_DURATION_MS — the proxy rarely vanishes
    //     mid-drive, so we don't re-probe on every request.
    //   • a NEGATIVE probe caches for only NEG_CACHE_DURATION_MS, so a TRANSIENT miss
    //     self-heals within seconds instead of forcing NO_PROXY (→ direct → timeout)
    //     for a full minute. connectFailed()/invalidateCache() still force an immediate
    //     re-probe on a real failure; this just bounds the blast radius of a probe that
    //     missed while the proxy was fine.
    private static final long CACHE_DURATION_MS = 60_000;     // positive result: 60s
    private static final long NEG_CACHE_DURATION_MS = 2_000;  // negative result: 2s

    private static volatile boolean proxyChecked = false;
    private static volatile boolean proxyAvailable = false;
    private static volatile int proxyPort = PROXY_PORT;
    private static volatile long lastProbeTime = 0;

    /**
     * Guards the process-global {@code socksProxyHost}/{@code socksProxyPort} JVM
     * properties across the window where they matter: from the set/clear until the
     * connection's underlying socket has been created (Paho reads them at socket
     * creation, not afterwards). Two connect paths touch or depend on them:
     *  - WS/WSS + proxy (MqttPublisherService): must SET them (Paho bug #573 bypasses
     *    the SocketFactory for WebSockets) and needs them to survive until its socket
     *    is created.
     *  - Any DIRECT (no-proxy) connect using a default socket factory: must CLEAR them
     *    (leftover props would misroute the direct socket through a dead proxy) and
     *    needs them to stay cleared until its socket is created.
     * Without a shared lock, two connections connecting concurrently on their own
     * scheduler threads can interleave set/clear mid-window (ProxyHelper's negative
     * cache and invalidateCache() let them disagree about proxy state). Hold this lock
     * from the property mutation through the blocking connect() call. Factory-proxied
     * connects (explicit {@code new Socket(proxy)}) ignore the props entirely and must
     * NOT take this lock — connects are serialized only when the props are in play.
     */
    public static final Object SOCKS_PROPS_LOCK = new Object();

    private ProxyHelper() {} // Utility class

    /**
     * Check if the sing-box proxy is available. Result is cached — a POSITIVE result
     * for {@link #CACHE_DURATION_MS}, a NEGATIVE result for only
     * {@link #NEG_CACHE_DURATION_MS} so a transient probe miss doesn't strand traffic
     * on the (timing-out) direct path for a full minute.
     */
    public static boolean isProxyAvailable() {
        long now = System.currentTimeMillis();
        if (proxyChecked) {
            long ttl = proxyAvailable ? CACHE_DURATION_MS : NEG_CACHE_DURATION_MS;
            if ((now - lastProbeTime) < ttl) {
                return proxyAvailable;
            }
        }

        proxyChecked = true;
        lastProbeTime = now;

        // Probe each candidate port on its OWN socket. A Socket cannot be reconnected after a
        // failed connect(), so the previous single-socket form made the sing-box fallback throw
        // "Socket closed" whenever the Tailscale probe missed — reporting "no proxy" even when a
        // proxy was actually up (and, during the boot window, stranding MQTT on a direct dial).
        if (probePort(TAILSCALE_PROXY_PORT)) {
            proxyAvailable = true;
            proxyPort = TAILSCALE_PROXY_PORT;
            logger.info("Proxy probe: Tailscale proxy available on port " + TAILSCALE_PROXY_PORT);
        } else if (probePort(PROXY_PORT)) {
            proxyAvailable = true;
            proxyPort = PROXY_PORT;
            logger.info("Proxy probe: sing-box available on port " + PROXY_PORT);
        } else {
            proxyAvailable = false;
        }

        return proxyAvailable;
    }

    /** Loopback TCP probe of a single port on a FRESH socket (see caller for why per-port). */
    public static boolean probePort(int port) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(PROXY_HOST, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the proxy port number.
     * Used by MqttPublisherService to set JVM-level socksProxyPort for WebSocket connections.
     */
    public static int getProxyPort() {
        return proxyPort;
    }

    /**
     * The Tailscale SOCKS port we expect when the proxy is enabled. Unlike {@link #getProxyPort()}
     * (which stays at the sing-box default until a successful probe updates it), this is stable, so
     * callers can name the right port in "proxy warming up" diagnostics.
     */
    public static int getTailscaleProxyPort() {
        return TAILSCALE_PROXY_PORT;
    }

    /**
     * Invalidate the proxy cache.
     * Call this on connection failures so the next attempt re-probes.
     */
    public static void invalidateCache() {
        proxyAvailable = false;
        proxyChecked = false;
    }

    /**
     * Whether the user has ENABLED the Tailscale SOCKS proxy (persisted flag written by the
     * Daemons screen to {@code .tailscale/proxy_enabled}). When true, outbound traffic is
     * expected to go through the proxy ONLY, so callers must not fall back to a direct dial
     * that cannot reach a proxy-only (e.g. subnet-routed LAN) broker off Wi-Fi.
     */
    public static boolean isProxyExpected() {
        try (BufferedReader r = new BufferedReader(new FileReader(PROXY_ENABLED_FILE))) {
            return isProxyEnabledValue(r.readLine());
        } catch (Exception e) {
            return false;
        }
    }

    /** Parse a persisted {@code proxy_enabled} value: true iff "true" (any case) or "1", trimmed. */
    static boolean isProxyEnabledValue(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * Get a Java Proxy object for HTTP clients (OkHttp).
     * Returns Proxy.NO_PROXY if proxy is not available.
     */
    public static Proxy getHttpProxy() {
        if (isProxyAvailable()) {
            // Proxy TYPE must match the resolved backend port:
            //  - Tailscale (8539) is a `tailscaled --socks5-server` that ONLY speaks
            //    SOCKS5 and REJECTS HTTP CONNECT → it needs Proxy.Type.SOCKS.
            //  - sing-box (8119) is a "mixed" inbound; v26.8 reached it via HTTP CONNECT
            //    and that is the PROVEN path. The blanket SOCKS swap (added for the
            //    Tailscale case) regressed sing-box: route/geocode POSTs to the BYOK
            //    endpoint began failing whenever sing-box was engaged. Restore HTTP for
            //    the sing-box port; keep SOCKS only for the Tailscale port that requires it.
            Proxy.Type type = (proxyPort == TAILSCALE_PROXY_PORT)
                    ? Proxy.Type.SOCKS
                    : Proxy.Type.HTTP;
            return new Proxy(type, new InetSocketAddress(PROXY_HOST, proxyPort));
        }
        return Proxy.NO_PROXY;
    }

    /**
     * HTTP proxy selection for privacy-sensitive traffic. When the user has
     * enabled proxy-only routing but the listener is still unavailable, return
     * the expected local SOCKS endpoint so clients fail closed instead of
     * silently dialing the destination directly.
     */
    public static Proxy getFailClosedHttpProxy() {
        Proxy selected = getHttpProxy();
        if (!Proxy.NO_PROXY.equals(selected) || !isProxyExpected()) {
            return selected;
        }
        return new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(PROXY_HOST, TAILSCALE_PROXY_PORT));
    }

    /**
     * Get a SocketFactory that routes through the sing-box SOCKS proxy.
     * Used by Paho MQTT client via MqttConnectOptions.setSocketFactory().
     *
     * If proxy is not available, returns the default SocketFactory (direct connection).
     */
    public static SocketFactory getMqttSocketFactory() {
        if (isProxyAvailable()) {
            return new ProxiedSocketFactory();
        }
        return SocketFactory.getDefault();
    }

    /**
     * Get an SSLSocketFactory that routes through the sing-box SOCKS proxy.
     *
     * This is the hard part: we create a raw SOCKS-proxied TCP socket first,
     * then overlay TLS on top of it using SSLSocketFactory.createSocket(Socket, host, port, autoClose).
     * This lets Paho see an SSLSocketFactory (so it performs the TLS handshake) while the
     * underlying transport is routed through sing-box.
     *
     * @param trustAll if true, accepts any server certificate (for self-signed / Home Assistant)
     */
    public static SSLSocketFactory getProxiedSslSocketFactory(boolean trustAll) {
        try {
            SSLSocketFactory baseSslFactory = trustAll
                    ? getTrustAllSslFactory()
                    : (SSLSocketFactory) SSLSocketFactory.getDefault();

            if (isProxyAvailable()) {
                return new ProxiedSslSocketFactory(baseSslFactory);
            }
            return baseSslFactory;
        } catch (Exception e) {
            logger.error("Failed to create proxied SSL factory: " + e.getMessage());
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    /**
     * Get an SSLSocketFactory that trusts ALL certificates.
     *
     * WARNING: This disables certificate validation entirely. Only use for local
     * brokers with self-signed certs (Home Assistant, dev Mosquitto instances).
     * Never use against public brokers where MITM is a real risk.
     */
    public static SSLSocketFactory getTrustAllSslFactory() throws Exception {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        };

        SSLContext sc = SSLContext.getInstance("TLSv1.2");
        sc.init(null, trustAllCerts, new SecureRandom());
        return sc.getSocketFactory();
    }

    /**
     * SocketFactory that creates sockets routed through the sing-box SOCKS proxy.
     * Paho MQTT uses SocketFactory to create its TCP connections, so this is the
     * cleanest way to proxy MQTT traffic without modifying Paho internals.
     */
    static class ProxiedSocketFactory extends SocketFactory {

        private final Proxy proxy;

        ProxiedSocketFactory() {
            this.proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(PROXY_HOST, proxyPort));
        }

        @Override
        public Socket createSocket() {
            return new Socket(proxy);
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            Socket socket = new Socket(proxy);
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
                throws java.io.IOException {
            Socket socket = new Socket(proxy);
            socket.bind(new InetSocketAddress(localHost, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            Socket socket = new Socket(proxy);
            socket.connect(new InetSocketAddress(host, port));
            return socket;
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port,
                                   java.net.InetAddress localAddress, int localPort)
                throws java.io.IOException {
            Socket socket = new Socket(proxy);
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return socket;
        }
    }

    /**
     * SSLSocketFactory that creates TLS sockets routed through the sing-box SOCKS proxy.
     *
     * Strategy: create a raw SOCKS-proxied TCP socket, then layer TLS on top using
     * SSLSocketFactory.createSocket(Socket, host, port, autoClose). This gives Paho
     * a proper SSLSocket for the TLS handshake while the underlying bytes flow through sing-box.
     */
    static class ProxiedSslSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory sslFactory;
        private final Proxy proxy;

        ProxiedSslSocketFactory(SSLSocketFactory sslFactory) {
            this.sslFactory = sslFactory;
            this.proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(PROXY_HOST, proxyPort));
        }

        /**
         * No-arg createSocket() — Paho's SSLNetworkModule calls this first, then
         * either connects it and upgrades via createSocket(Socket, host, port, true),
         * or (in some versions) casts the result directly to SSLSocket.
         *
         * We return a DeferredSslSocket: a plain proxied Socket wrapped in a
         * delegating SSLSocket shell. This satisfies the cast to SSLSocket
         * immediately. When Paho calls connect(), the underlying SOCKS tunnel
         * is established. When Paho calls startHandshake(), we upgrade the
         * tunnel to real TLS on the fly.
         */
        @Override
        public Socket createSocket() throws java.io.IOException {
            return new DeferredSslSocket(new Socket(proxy), sslFactory);
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            // 1. Create raw TCP socket through SOCKS proxy
            Socket tunnel = new Socket(proxy);
            tunnel.connect(new InetSocketAddress(host, port));
            // 2. Layer TLS on top of the connected tunnel
            return sslFactory.createSocket(tunnel, host, port, true);
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
                throws java.io.IOException {
            Socket tunnel = new Socket(proxy);
            tunnel.bind(new InetSocketAddress(localHost, localPort));
            tunnel.connect(new InetSocketAddress(host, port));
            return sslFactory.createSocket(tunnel, host, port, true);
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            Socket tunnel = new Socket(proxy);
            tunnel.connect(new InetSocketAddress(host, port));
            return sslFactory.createSocket(tunnel, host.getHostName(), port, true);
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port,
                                   java.net.InetAddress localAddress, int localPort)
                throws java.io.IOException {
            Socket tunnel = new Socket(proxy);
            tunnel.bind(new InetSocketAddress(localAddress, localPort));
            tunnel.connect(new InetSocketAddress(address, port));
            return sslFactory.createSocket(tunnel, address.getHostName(), port, true);
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws java.io.IOException {
            // Paho may call this variant directly — layer TLS on the provided socket.
            // If 's' is our DeferredSslSocket, unwrap to the real underlying socket first
            // so we don't nest SSLSocket inside SSLSocket.
            Socket raw = (s instanceof DeferredSslSocket) ? ((DeferredSslSocket) s).getInnerSocket() : s;
            return sslFactory.createSocket(raw, host, port, autoClose);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return sslFactory.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return sslFactory.getSupportedCipherSuites();
        }
    }

    /**
     * A thin SSLSocket wrapper around a plain (SOCKS-proxied) Socket.
     *
     * Paho's SSLNetworkModule (via TCPNetworkModule.start()) does:
     *   1. factory.createSocket()                    → stored in 'socket' field
     *   2. socket.connect(sockaddr, timeout)          → TCP connect
     *   3. socket.setSoTimeout(1000)
     *   4. ((SSLSocket) socket).setEnabledCipherSuites(...)  ← before handshake!
     *   5. ((SSLSocket) socket).getSSLParameters()           ← before handshake!
     *   6. ((SSLSocket) socket).setSSLParameters(...)        ← before handshake!
     *   7. ((SSLSocket) socket).startHandshake()
     *
     * Steps 4-6 happen BEFORE startHandshake(), so we must buffer any
     * configuration (cipher suites, SSL parameters, protocols) and replay
     * them onto the real SSLSocket when it's created in startHandshake().
     *
     * DeferredSslSocket solves this by:
     *   - Extending SSLSocket so all casts succeed
     *   - Delegating connect/setSoTimeout to the inner proxied socket
     *   - Buffering SSL config (ciphers, protocols, parameters) pre-handshake
     *   - On startHandshake(), creating the real SSLSocket via
     *     sslFactory.createSocket(innerSocket, host, port, true),
     *     replaying buffered config, then performing the handshake
     */
    static class DeferredSslSocket extends SSLSocket {

        private final Socket innerSocket;
        private final SSLSocketFactory sslFactory;
        private SSLSocket realSsl;
        private String peerHost;
        private int peerPort;

        // Buffered SSL configuration — applied to realSsl in startHandshake()
        private String[] pendingEnabledCiphers;
        private String[] pendingEnabledProtocols;
        private javax.net.ssl.SSLParameters pendingSSLParameters;
        private Boolean pendingUseClientMode;
        private Boolean pendingNeedClientAuth;
        private Boolean pendingWantClientAuth;
        private Boolean pendingEnableSessionCreation;
        private final java.util.List<HandshakeCompletedListener> pendingListeners =
                new java.util.ArrayList<>();

        DeferredSslSocket(Socket innerSocket, SSLSocketFactory sslFactory) {
            this.innerSocket = innerSocket;
            this.sslFactory = sslFactory;
        }

        /** Expose the raw inner socket for unwrapping in createSocket(Socket,...) */
        Socket getInnerSocket() {
            return innerSocket;
        }

        // --- Connect: delegate to inner socket, remember peer for TLS upgrade ---

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            capturePeer(endpoint);
            innerSocket.connect(endpoint);
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            capturePeer(endpoint);
            innerSocket.connect(endpoint, timeout);
        }

        private void capturePeer(SocketAddress endpoint) {
            if (endpoint instanceof InetSocketAddress) {
                InetSocketAddress inet = (InetSocketAddress) endpoint;
                peerHost = inet.getHostString();
                peerPort = inet.getPort();
            }
        }

        // --- TLS upgrade on startHandshake ---

        @Override
        public void startHandshake() throws IOException {
            if (realSsl != null) {
                realSsl.startHandshake();
                return;
            }
            if (peerHost == null) {
                peerHost = innerSocket.getInetAddress() != null
                        ? innerSocket.getInetAddress().getHostName() : "unknown";
                peerPort = innerSocket.getPort();
            }
            realSsl = (SSLSocket) sslFactory.createSocket(innerSocket, peerHost, peerPort, true);

            // Replay buffered configuration onto the real SSLSocket
            if (pendingEnabledCiphers != null) realSsl.setEnabledCipherSuites(pendingEnabledCiphers);
            if (pendingEnabledProtocols != null) realSsl.setEnabledProtocols(pendingEnabledProtocols);
            if (pendingSSLParameters != null) realSsl.setSSLParameters(pendingSSLParameters);
            if (pendingUseClientMode != null) realSsl.setUseClientMode(pendingUseClientMode);
            if (pendingNeedClientAuth != null) realSsl.setNeedClientAuth(pendingNeedClientAuth);
            if (pendingWantClientAuth != null) realSsl.setWantClientAuth(pendingWantClientAuth);
            if (pendingEnableSessionCreation != null) realSsl.setEnableSessionCreation(pendingEnableSessionCreation);
            for (HandshakeCompletedListener l : pendingListeners) {
                realSsl.addHandshakeCompletedListener(l);
            }

            realSsl.startHandshake();
        }

        private SSLSocket ensureSsl() throws IOException {
            if (realSsl == null) startHandshake();
            return realSsl;
        }

        // --- I/O: delegate to real SSLSocket after handshake ---

        @Override
        public InputStream getInputStream() throws IOException {
            return ensureSsl().getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return ensureSsl().getOutputStream();
        }

        // --- SSLSocket methods: buffer pre-handshake, delegate post-handshake ---

        @Override
        public SSLSession getSession() {
            if (realSsl != null) return realSsl.getSession();
            return null;
        }

        @Override
        public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
            if (realSsl != null) {
                realSsl.addHandshakeCompletedListener(listener);
            } else {
                pendingListeners.add(listener);
            }
        }

        @Override
        public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
            if (realSsl != null) {
                realSsl.removeHandshakeCompletedListener(listener);
            } else {
                pendingListeners.remove(listener);
            }
        }

        @Override
        public String[] getSupportedCipherSuites() {
            if (realSsl != null) return realSsl.getSupportedCipherSuites();
            // Can't know without a real SSLSocket; return empty — Paho doesn't call this pre-handshake
            return new String[0];
        }

        @Override
        public String[] getEnabledCipherSuites() {
            if (realSsl != null) return realSsl.getEnabledCipherSuites();
            if (pendingEnabledCiphers != null) return pendingEnabledCiphers;
            return new String[0];
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            if (realSsl != null) {
                realSsl.setEnabledCipherSuites(suites);
            } else {
                pendingEnabledCiphers = suites;
            }
        }

        @Override
        public String[] getSupportedProtocols() {
            if (realSsl != null) return realSsl.getSupportedProtocols();
            return new String[0];
        }

        @Override
        public String[] getEnabledProtocols() {
            if (realSsl != null) return realSsl.getEnabledProtocols();
            if (pendingEnabledProtocols != null) return pendingEnabledProtocols;
            return new String[0];
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            if (realSsl != null) {
                realSsl.setEnabledProtocols(protocols);
            } else {
                pendingEnabledProtocols = protocols;
            }
        }

        @Override
        public javax.net.ssl.SSLParameters getSSLParameters() {
            if (realSsl != null) return realSsl.getSSLParameters();
            // Pre-handshake: return a fresh SSLParameters that Paho can configure.
            // Paho calls getSSLParameters(), mutates it, then calls setSSLParameters().
            // We'll capture the final state in setSSLParameters().
            if (pendingSSLParameters != null) return pendingSSLParameters;
            return new javax.net.ssl.SSLParameters();
        }

        @Override
        public void setSSLParameters(javax.net.ssl.SSLParameters params) {
            if (realSsl != null) {
                realSsl.setSSLParameters(params);
            } else {
                pendingSSLParameters = params;
            }
        }

        @Override
        public boolean getUseClientMode() {
            if (realSsl != null) return realSsl.getUseClientMode();
            return pendingUseClientMode != null ? pendingUseClientMode : true;
        }

        @Override
        public void setUseClientMode(boolean mode) {
            if (realSsl != null) {
                realSsl.setUseClientMode(mode);
            } else {
                pendingUseClientMode = mode;
            }
        }

        @Override
        public boolean getNeedClientAuth() {
            if (realSsl != null) return realSsl.getNeedClientAuth();
            return pendingNeedClientAuth != null ? pendingNeedClientAuth : false;
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            if (realSsl != null) {
                realSsl.setNeedClientAuth(need);
            } else {
                pendingNeedClientAuth = need;
            }
        }

        @Override
        public boolean getWantClientAuth() {
            if (realSsl != null) return realSsl.getWantClientAuth();
            return pendingWantClientAuth != null ? pendingWantClientAuth : false;
        }

        @Override
        public void setWantClientAuth(boolean want) {
            if (realSsl != null) {
                realSsl.setWantClientAuth(want);
            } else {
                pendingWantClientAuth = want;
            }
        }

        @Override
        public boolean getEnableSessionCreation() {
            if (realSsl != null) return realSsl.getEnableSessionCreation();
            return pendingEnableSessionCreation != null ? pendingEnableSessionCreation : true;
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            if (realSsl != null) {
                realSsl.setEnableSessionCreation(flag);
            } else {
                pendingEnableSessionCreation = flag;
            }
        }

        // --- Socket state delegation ---

        @Override
        public boolean isConnected() {
            return innerSocket.isConnected();
        }

        @Override
        public boolean isClosed() {
            return innerSocket.isClosed();
        }

        @Override
        public boolean isBound() {
            return innerSocket.isBound();
        }

        @Override
        public void close() throws IOException {
            if (realSsl != null) {
                realSsl.close(); // closes inner socket too (autoClose=true)
            } else {
                innerSocket.close();
            }
        }

        @Override
        public void setSoTimeout(int timeout) throws java.net.SocketException {
            // Pre-handshake: set on inner socket (Paho calls this before startHandshake)
            // Post-handshake: set on both (realSsl wraps innerSocket, but timeout is on the underlying)
            innerSocket.setSoTimeout(timeout);
            if (realSsl != null) {
                try { realSsl.setSoTimeout(timeout); } catch (java.net.SocketException ignored) {}
            }
        }

        @Override
        public int getSoTimeout() throws java.net.SocketException {
            return innerSocket.getSoTimeout();
        }

        @Override
        public void setTcpNoDelay(boolean on) throws java.net.SocketException {
            innerSocket.setTcpNoDelay(on);
        }

        @Override
        public java.net.InetAddress getInetAddress() {
            return innerSocket.getInetAddress();
        }

        @Override
        public int getPort() {
            return innerSocket.getPort();
        }

        @Override
        public int getLocalPort() {
            return innerSocket.getLocalPort();
        }
    }
}
