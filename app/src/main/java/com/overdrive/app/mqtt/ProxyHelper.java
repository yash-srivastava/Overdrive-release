package com.overdrive.app.mqtt;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
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
import java.util.Locale;

/**
 * Shared proxy detection utility for the local SOCKS/HTTP proxies: Tailscale
 * (8539), WireGuard (8541) and sing-box (8119).
 *
 * The BYD head unit may route internet through a sing-box proxy on port 8119.
 * The WireGuard listener (wgproxy) is selected only for the user's MQTT brokers; it sends
 * destinations inside the peers' AllowedIPs through the tunnel and dials everything else
 * directly or via sing-box.
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
    private static final int WIREGUARD_PROXY_PORT = 8541;
    private static final String PROXY_ENABLED_FILE = com.overdrive.app.util.ScratchRoot.path(".tailscale/proxy_enabled");
    private static final String WIREGUARD_PROXY_ENABLED_FILE = com.overdrive.app.wireguard.WireGuardPaths.PROXY_FLAG;
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

    // sing-box-only probe, cached apart from the Tailscale-first MQTT selection above.
    private static volatile boolean egressChecked = false;
    private static volatile boolean egressAvailable = false;
    private static volatile long lastEgressProbeTime = 0;

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
     * Proxy selection for the user's MQTT brokers: the Tailscale listener first (the Daemons
     * toggle exists for tailnet brokers), then sing-box. Public-internet clients must use
     * {@link #isEgressProxyAvailable()} instead. Result is cached — a POSITIVE result
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
        } else if (probePort(WIREGUARD_PROXY_PORT)) {
            proxyAvailable = true;
            proxyPort = WIREGUARD_PROXY_PORT;
            logger.info("Proxy probe: WireGuard proxy available on port " + WIREGUARD_PROXY_PORT);
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
     * Whether sing-box, the only general-egress proxy, is listening (same asymmetric cache as
     * {@link #isProxyAvailable()}). The Tailscale listener is never egress: the bundled CGO-free
     * tailscaled cannot resolve public hostnames on Android.
     */
    public static boolean isEgressProxyAvailable() {
        long now = System.currentTimeMillis();
        if (egressChecked) {
            long ttl = egressAvailable ? CACHE_DURATION_MS : NEG_CACHE_DURATION_MS;
            if ((now - lastEgressProbeTime) < ttl) {
                return egressAvailable;
            }
        }
        boolean up = probePort(PROXY_PORT);
        egressAvailable = up;
        lastEgressProbeTime = now;
        egressChecked = true;
        return up;
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
     * The SOCKS port we expect when the user enabled a proxy: Tailscale if its flag is on, else
     * WireGuard if its flag is on, else the Tailscale port. Stable (flag-based, not probe-based),
     * so "proxy warming up" diagnostics name the right endpoint.
     */
    public static int getExpectedProxyPort() {
        if (isProxyExpected()) return TAILSCALE_PROXY_PORT;
        if (isWireGuardProxyExpected()) return WIREGUARD_PROXY_PORT;
        return TAILSCALE_PROXY_PORT;
    }

    /**
     * Invalidate the proxy cache.
     * Call this on connection failures so the next attempt re-probes.
     */
    public static void invalidateCache() {
        proxyAvailable = false;
        proxyChecked = false;
        egressAvailable = false;
        egressChecked = false;
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

    /** Whether the WireGuard tunnel is enabled (flag written by the launcher to {@code .wireguard/proxy_enabled}). */
    public static boolean isWireGuardProxyExpected() {
        try (BufferedReader r = new BufferedReader(new FileReader(WIREGUARD_PROXY_ENABLED_FILE))) {
            return isProxyEnabledValue(r.readLine());
        } catch (Exception e) {
            return false;
        }
    }

    /** MQTT brokers may sit behind either SOCKS listener; {@link #routeFor} stays Tailscale-only. */
    public static boolean isMqttProxyExpected() {
        return isProxyExpected() || isWireGuardProxyExpected();
    }

    /** Parse a persisted {@code proxy_enabled} value: true iff "true" (any case) or "1", trimmed. */
    static boolean isProxyEnabledValue(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }

    /**
     * Proxy for PUBLIC-internet HTTP clients: sing-box over HTTP CONNECT (its proven path), else
     * {@link Proxy#NO_PROXY}. Never the Tailscale listener — see {@link #isEgressProxyAvailable()}.
     */
    public static Proxy getHttpProxy() {
        if (isEgressProxyAvailable()) {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT));
        }
        return Proxy.NO_PROXY;
    }

    /**
     * Proxy route CHAIN for clients whose destinations are PUBLIC internet
     * (BYD Cloud, GitHub release metadata): the egress proxy first, then a
     * DIRECT fallback — or just DIRECT when no proxy is up. OkHttp falls
     * through to direct within the same call when the proxy leg dies. Do NOT
     * use this for privacy-sensitive traffic — that's {@link #routeFor}'s
     * job, which intentionally has no direct fallback.
     */
    public static java.util.List<Proxy> proxyRouteChain(Proxy selected) {
        if (selected == null || Proxy.NO_PROXY.equals(selected)) {
            return java.util.Collections.singletonList(Proxy.NO_PROXY);
        }
        java.util.List<Proxy> chain = new java.util.ArrayList<>(2);
        chain.add(selected);
        chain.add(Proxy.NO_PROXY);
        return chain;
    }

    /**
     * Ready-made per-call OkHttp {@link java.net.ProxySelector} for simple
     * PUBLIC-internet clients (weather, analytics ping, RoadSense sync, map
     * routing/tiles): re-evaluates {@link #getHttpProxy()} on every request
     * and returns {@link #proxyRouteChain}, so the proxy is picked up when it
     * appears and a dead proxy can't wedge the feature. Clients
     * with bespoke logging or resolution cadence (BYD cloud transport,
     * AppUpdater, Telegram daemon) keep their own selectors built on
     * {@link #proxyRouteChain}.
     */
    public static java.net.ProxySelector chainProxySelector() {
        return CHAIN_PROXY_SELECTOR;
    }

    private static final java.net.ProxySelector CHAIN_PROXY_SELECTOR =
            new java.net.ProxySelector() {
                @Override
                public java.util.List<Proxy> select(java.net.URI uri) {
                    return proxyRouteChain(getHttpProxy());
                }

                @Override
                public void connectFailed(
                        java.net.URI uri,
                        SocketAddress address,
                        java.io.IOException failure) {
                    // Only proxy legs are reported here (OkHttp never calls
                    // connectFailed for DIRECT routes) — re-probe next call.
                    invalidateCache();
                }
            };

    /**
     * Single route (no direct fallback) for privacy-sensitive HTTP to {@code host}. Tailnet
     * hosts always use the Tailscale listener; private LAN hosts use it only while the Tailscale
     * proxy is enabled (subnet routes), failing closed if it is down; loopback goes direct;
     * public hosts use {@link #getHttpProxy()}.
     */
    public static Proxy routeFor(String host) {
        if (isTailnetHost(host)) return tailscaleProxy();
        if (isLoopbackHost(host)) return Proxy.NO_PROXY;
        if (isLanHost(host)) return isProxyExpected() ? tailscaleProxy() : Proxy.NO_PROXY;
        return getHttpProxy();
    }

    /** Per-request OkHttp {@link java.net.ProxySelector} returning {@link #routeFor}'s single route. */
    public static java.net.ProxySelector singleRouteSelector() {
        return SINGLE_ROUTE_SELECTOR;
    }

    private static final java.net.ProxySelector SINGLE_ROUTE_SELECTOR =
            new java.net.ProxySelector() {
                @Override
                public java.util.List<Proxy> select(java.net.URI uri) {
                    return java.util.Collections.singletonList(
                            routeFor(uri == null ? null : uri.getHost()));
                }

                @Override
                public void connectFailed(
                        java.net.URI uri,
                        SocketAddress address,
                        java.io.IOException failure) {
                    invalidateCache();
                }
            };

    /** Whether {@code proxy} is the Tailscale SOCKS listener. */
    public static boolean isTailscaleRoute(Proxy proxy) {
        return proxy != null
                && proxy.type() == Proxy.Type.SOCKS
                && proxy.address() instanceof InetSocketAddress
                && ((InetSocketAddress) proxy.address()).getPort() == TAILSCALE_PROXY_PORT;
    }

    private static Proxy tailscaleProxy() {
        return new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(PROXY_HOST, TAILSCALE_PROXY_PORT));
    }

    /** RFC1918 / link-local / ULA literals and home-network suffixes. No DNS lookups. */
    public static boolean isLanHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan")
                || h.endsWith(".home") || h.endsWith(".internal") || h.endsWith(".home.arpa")) {
            return true;
        }
        byte[] a = hostLiteral(h);
        if (a == null) return false;
        if (a.length == 4) {
            int b0 = a[0] & 0xff, b1 = a[1] & 0xff;
            return b0 == 10 || b0 == 127 || (b0 == 172 && b1 >= 16 && b1 <= 31)
                    || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254);
        }
        int b0 = a[0] & 0xff, b1 = a[1] & 0xff;
        return (b0 & 0xfe) == 0xfc || (b0 == 0xfe && (b1 & 0xc0) == 0x80);
    }

    /** Tailscale CGNAT range 100.64.0.0/10 or MagicDNS names. */
    public static boolean isTailnetHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".ts.net")) return true;
        byte[] a = hostLiteral(h);
        return a != null && a.length == 4 && (a[0] & 0xff) == 100 && ((a[1] & 0xff) & 0xc0) == 64;
    }

    /** localhost or a loopback literal. No DNS lookups. */
    static boolean isLoopbackHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        if (h.equals("localhost")) return true;
        byte[] a = hostLiteral(h);
        try {
            return a != null && InetAddress.getByAddress(a).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] hostLiteral(String h) {
        boolean v4 = h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = h.indexOf(':') >= 0 && h.matches("[0-9a-f:.]+");
        if (!v4 && !v6) return null;
        try {
            return InetAddress.getByName(h).getAddress();
        } catch (Exception e) {
            return null;
        }
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
                return new ProxiedSslSocketFactory(baseSslFactory, proxyPort);
            }
            return baseSslFactory;
        } catch (Exception e) {
            logger.error("Failed to create proxied SSL factory: " + e.getMessage());
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    /** System-trust TLS factory for PUBLIC brokers: through sing-box when it is up, else direct. */
    public static SSLSocketFactory getEgressSslSocketFactory() {
        SSLSocketFactory base = (SSLSocketFactory) SSLSocketFactory.getDefault();
        return isEgressProxyAvailable() ? new ProxiedSslSocketFactory(base, PROXY_PORT) : base;
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

        ProxiedSslSocketFactory(SSLSocketFactory sslFactory, int port) {
            this.sslFactory = sslFactory;
            this.proxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(PROXY_HOST, port));
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
