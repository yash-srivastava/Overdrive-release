package com.overdrive.app.mqtt;

import com.overdrive.app.logging.DaemonLogger;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;

import javax.net.SocketFactory;

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

        try {
            Socket probe = new Socket();
            probe.connect(new InetSocketAddress(PROXY_HOST, PROXY_PORT), PROBE_TIMEOUT_MS);
            probe.close();
            proxyAvailable = true;
            logger.info("Proxy probe: sing-box available on port " + PROXY_PORT);
        } catch (Exception e) {
            proxyAvailable = false;
        }

        return proxyAvailable;
    }

    /**
     * Invalidate the proxy cache.
     * Call this on connection failures so the next attempt re-probes.
     */
    public static void invalidateCache() {
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
}
