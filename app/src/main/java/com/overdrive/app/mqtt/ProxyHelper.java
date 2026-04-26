package com.overdrive.app.mqtt;

import com.overdrive.app.logging.DaemonLogger;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
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
    private static final int PROBE_TIMEOUT_MS = 200;
    private static final long CACHE_DURATION_MS = 60_000; // 60 seconds

    private static volatile boolean proxyChecked = false;
    private static volatile boolean proxyAvailable = false;
    private static volatile long lastProbeTime = 0;

    private ProxyHelper() {} // Utility class

    /**
     * Check if the sing-box proxy is available.
     * Result is cached for 60 seconds.
     */
    public static boolean isProxyAvailable() {
        long now = System.currentTimeMillis();
        if (proxyChecked && (now - lastProbeTime) < CACHE_DURATION_MS) {
            return proxyAvailable;
        }

        proxyChecked = true;
        lastProbeTime = now;

        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(PROXY_HOST, PROXY_PORT), PROBE_TIMEOUT_MS);
            proxyAvailable = true;
            logger.info("Proxy probe: sing-box available on port " + PROXY_PORT);
        } catch (Exception e) {
            proxyAvailable = false;
        }

        return proxyAvailable;
    }

    /**
     * Get the proxy port number.
     * Used by MqttPublisherService to set JVM-level socksProxyPort for WebSocket connections.
     */
    public static int getProxyPort() {
        return PROXY_PORT;
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
     * Get a Java Proxy object for HTTP clients (OkHttp).
     * Returns Proxy.NO_PROXY if proxy is not available.
     */
    public static Proxy getHttpProxy() {
        if (isProxyAvailable()) {
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(PROXY_HOST, PROXY_PORT));
        }
        return Proxy.NO_PROXY;
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
                    new InetSocketAddress(PROXY_HOST, PROXY_PORT));
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
                    new InetSocketAddress(PROXY_HOST, PROXY_PORT));
        }

        @Override
        public Socket createSocket() throws java.io.IOException {
            // Paho calls createSocket() then connect(). We return a raw proxied socket.
            // Paho will then call startHandshake() — but we need to intercept connect().
            // Instead, return a marker socket that we'll upgrade in createSocket(host, port).
            return new Socket(proxy);
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
            // Paho may call this variant directly — layer TLS on the provided socket
            return sslFactory.createSocket(s, host, port, autoClose);
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
}
