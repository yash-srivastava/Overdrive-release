package com.overdrive.app.mqtt;

import com.overdrive.app.logging.DaemonLogger;

import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

/**
 * MQTT publisher for a single broker connection.
 *
 * Manages the Paho MQTT client lifecycle: connect, publish, reconnect, disconnect.
 * Each instance is bound to one MqttConnectionConfig and publishes telemetry
 * JSON payloads to the configured topic.
 *
 * Proxy-aware: uses ProxyHelper to route through sing-box when available.
 * Reconnection: automatic with exponential backoff (5s → 10s → 20s → ... → 300s).
 *
 * Thread safety: all public methods are synchronized on the instance.
 */
public class MqttPublisherService implements MqttCallback {

    private static final String TAG = "MqttPublisher";
    private final DaemonLogger logger;

    // Backoff constants
    private static final int BACKOFF_BASE_SECONDS = 5;
    private static final int BACKOFF_CAP_SECONDS = 300;

    // Connection config
    private final MqttConnectionConfig config;
    private final String deviceId;

    // Paho MQTT client
    private MqttClient client;
    private volatile boolean running = false;
    private volatile boolean connected = false;

    // Stats
    private volatile long totalPublishes = 0;
    private volatile long failedPublishes = 0;
    private volatile long lastPublishTime = 0;
    private volatile int consecutiveFailures = 0;
    private volatile String lastError = null;

    public MqttPublisherService(MqttConnectionConfig config, String deviceId) {
        this.config = config;
        this.deviceId = deviceId;
        this.logger = DaemonLogger.getInstance(TAG + "-" + config.id);
    }

    // ==================== LIFECYCLE ====================

    /**
     * Connect to the MQTT broker.
     * @return true if connected successfully
     */
    public synchronized boolean connect() {
        if (connected && client != null && client.isConnected()) {
            return true;
        }

        String brokerUri = config.getBrokerUri();
        if (brokerUri.isEmpty()) {
            lastError = "No broker URL configured";
            logger.error(lastError);
            return false;
        }

        String effectiveClientId = config.getEffectiveClientId(deviceId);

        MqttClient newClient = null;
        try {
            // Create client with in-memory persistence (no filesystem needed)
            newClient = new MqttClient(brokerUri, effectiveClientId, new MemoryPersistence());
            newClient.setCallback(this);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);
            options.setAutomaticReconnect(false); // We handle reconnect ourselves for proxy awareness

            // Auth
            if (config.username != null && !config.username.isEmpty()) {
                options.setUserName(config.username);
            }
            if (config.password != null && !config.password.isEmpty()) {
                options.setPassword(config.password.toCharArray());
            }

            // --- Protocol-Aware Socket Routing ---
            // SSL URIs (ssl://, wss://) require an SSLSocketFactory for the TLS handshake.
            // Applying a raw SocketFactory to an SSL connection causes Paho to send
            // unencrypted MQTT packets to a port expecting a TLS Client Hello → instant drop.
            boolean isSsl = config.isSsl();
            boolean isWebSocket = brokerUri.startsWith("ws://") || brokerUri.startsWith("wss://");

            if (ProxyHelper.isProxyAvailable()) {
                if (isWebSocket && isSsl) {
                    // WSS + Proxy: Paho 1.2.0+ has a bug (eclipse/paho.mqtt.java#573) where
                    // WebSocketSecureNetworkModule bypasses the SocketFactory and calls
                    // new Socket() directly for the initial TCP connection. Our ProxiedSslSocketFactory
                    // never gets invoked, so the SOCKS tunnel is never established.
                    //
                    // Workaround: set JVM-level SOCKS proxy properties so that ALL sockets
                    // (including Paho's internal new Socket()) route through sing-box.
                    // Then provide the appropriate SSLSocketFactory for the TLS layer only.
                    System.setProperty("socksProxyHost", "127.0.0.1");
                    System.setProperty("socksProxyPort", String.valueOf(ProxyHelper.getProxyPort()));
                    if (config.trustAllCerts) {
                        options.setSocketFactory(ProxyHelper.getTrustAllSslFactory());
                    } else {
                        options.setSocketFactory((javax.net.ssl.SSLSocketFactory)
                                javax.net.ssl.SSLSocketFactory.getDefault());
                    }
                } else if (isSsl) {
                    // SSL (non-WebSocket) + Proxy: our ProxiedSslSocketFactory works correctly
                    // because Paho's SSLNetworkModule calls factory.createSocket(host, port).
                    options.setSocketFactory(ProxyHelper.getProxiedSslSocketFactory(config.trustAllCerts));
                } else if (isWebSocket) {
                    // WS (plain) + Proxy: same Paho bug applies — use system SOCKS properties.
                    System.setProperty("socksProxyHost", "127.0.0.1");
                    System.setProperty("socksProxyPort", String.valueOf(ProxyHelper.getProxyPort()));
                } else {
                    // Plain TCP + Proxy: ProxiedSocketFactory works fine.
                    options.setSocketFactory(ProxyHelper.getMqttSocketFactory());
                }
            } else {
                // No proxy — clear any leftover system SOCKS properties from a previous
                // connection attempt where the proxy was active.
                System.clearProperty("socksProxyHost");
                System.clearProperty("socksProxyPort");

                if (isSsl) {
                    if (config.trustAllCerts) {
                        // Direct SSL with blind trust (Home Assistant self-signed certs)
                        options.setSocketFactory(ProxyHelper.getTrustAllSslFactory());
                    } else {
                        // Direct SSL with system trust store (public CAs)
                        options.setSocketFactory(javax.net.ssl.SSLSocketFactory.getDefault());
                    }
                }
            }
            // else: plain TCP, no proxy — Paho uses its default SocketFactory

            // --- Last Will and Testament (LWT) ---
            // When the broker detects an ungraceful disconnect (car drives into a tunnel,
            // head unit loses power), it publishes "offline" on our behalf so consumers
            // don't show stale telemetry.
            String lwtTopic = config.topic + "/availability";
            byte[] lwtPayload = "offline".getBytes();
            options.setWill(lwtTopic, lwtPayload, 1, true);

            logger.info("Connecting to " + brokerUri + " as " + effectiveClientId
                    + " (proxy=" + ProxyHelper.isProxyAvailable()
                    + ", ssl=" + isSsl
                    + ", ws=" + isWebSocket
                    + ", trustAll=" + config.trustAllCerts + ")");

            newClient.connect(options);
            client = newClient;
            connected = true;
            running = true;
            consecutiveFailures = 0;
            lastError = null;

            // Publish "online" availability immediately after successful connect.
            // This pairs with the LWT "offline" — consumers can subscribe to
            // <topic>/availability to track connection state.
            try {
                client.publish(lwtTopic, "online".getBytes(), 1, true);
            } catch (MqttException e) {
                logger.warn("Failed to publish availability online: " + e.getMessage());
            }

            logger.info("Connected to " + brokerUri);
            return true;

        } catch (MqttException e) {
            connected = false;
            consecutiveFailures++;

            // Paho's error 32103 (SERVER_CONNECT_ERROR) is a black hole — it hides the
            // real cause (SSL cert rejection, socket timeout, etc.) behind a generic message.
            // Walk the cause chain to extract the actual underlying exception.
            String rootCause = extractRootCause(e);
            lastError = "Connect failed (reason=" + e.getReasonCode() + ") Cause: " + rootCause;
            logger.error(lastError);

            // Invalidate proxy cache on connection failure — proxy state may have changed
            ProxyHelper.invalidateCache();

            // Close the client that failed to connect to avoid resource leak
            if (newClient != null) {
                try { newClient.close(); } catch (MqttException ignored) {}
            }
            return false;
        } catch (Throwable t) {
            // Catch ExceptionInInitializerError (Paho logging class not found) and any other errors
            connected = false;
            consecutiveFailures++;
            lastError = "Connect error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            if (t.getCause() != null) {
                lastError += " (caused by: " + t.getCause().getMessage() + ")";
            }
            logger.error(lastError);

            if (newClient != null) {
                try { newClient.close(); } catch (Exception ignored) {}
            }
            return false;
        }
    }

    /**
     * Disconnect from the MQTT broker.
     */
    public synchronized void disconnect() {
        running = false;
        connected = false;

        if (client != null) {
            try {
                if (client.isConnected()) {
                    // Publish graceful "offline" before disconnect.
                    // The LWT only fires on ungraceful drops — this covers clean shutdowns.
                    try {
                        String lwtTopic = config.topic + "/availability";
                        client.publish(lwtTopic, "offline".getBytes(), 1, true);
                    } catch (MqttException e) {
                        logger.warn("Failed to publish availability offline: " + e.getMessage());
                    }
                    client.disconnect(5000);
                }
            } catch (MqttException e) {
                logger.warn("Disconnect error: " + e.getMessage());
            } finally {
                try { client.close(); } catch (MqttException ignored) {}
                client = null;
            }
        }

        // Clean up JVM-level SOCKS proxy properties that may have been set for
        // WebSocket connections. These are global and would affect other connections.
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");

        logger.info("Disconnected from " + config.name + " (" + config.getBrokerUri() + ")");
    }

    /**
     * Publish a telemetry JSON payload to the configured topic.
     * Attempts reconnection if not connected.
     *
     * @return true if published successfully
     */
    public synchronized boolean publish(JSONObject payload) {
        if (!running) return false;

        // Reconnect if needed
        if (!connected || client == null || !client.isConnected()) {
            if (!connect()) {
                failedPublishes++;
                return false;
            }
        }

        try {
            String json = payload.toString();
            MqttMessage message = new MqttMessage(json.getBytes());
            message.setQos(config.qos);
            message.setRetained(config.retainMessages);

            client.publish(config.topic, message);

            totalPublishes++;
            lastPublishTime = System.currentTimeMillis();
            consecutiveFailures = 0;
            lastError = null;
            return true;

        } catch (MqttException e) {
            failedPublishes++;
            consecutiveFailures++;
            lastError = "Publish failed: " + e.getMessage();
            logger.warn(lastError);

            // Mark disconnected so next publish triggers reconnect
            connected = false;
            ProxyHelper.invalidateCache();
            return false;
        }
    }

    // ==================== MQTT CALLBACK ====================

    @Override
    public void connectionLost(Throwable cause) {
        connected = false;
        lastError = "Connection lost: " + (cause != null ? extractRootCause(cause) : "unknown");
        logger.warn(lastError);
        ProxyHelper.invalidateCache();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // We don't subscribe to anything — publish only
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Delivery confirmed (QoS 1)
    }

    // ==================== STATUS ====================

    /**
     * Get connection status as JSON for API responses.
     */
    public JSONObject getStatus() {
        JSONObject status = new JSONObject();
        try {
            status.put("id", config.id);
            status.put("name", config.name);
            status.put("connected", connected && client != null && client.isConnected());
            status.put("running", running);
            status.put("totalPublishes", totalPublishes);
            status.put("failedPublishes", failedPublishes);
            status.put("lastPublishTime", lastPublishTime);
            status.put("consecutiveFailures", consecutiveFailures);
            status.put("lastError", lastError != null ? lastError : "");
            status.put("brokerUri", config.getBrokerUri());
            status.put("topic", config.topic);
            status.put("ssl", config.isSsl());
            status.put("trustAllCerts", config.trustAllCerts);
            status.put("proxyActive", ProxyHelper.isProxyAvailable());
        } catch (Exception ignored) {}
        return status;
    }

    /**
     * Calculate backoff delay for reconnection.
     */
    public long getBackoffSeconds() {
        if (consecutiveFailures <= 0) return 0;
        long backoff = BACKOFF_BASE_SECONDS * (1L << Math.min(consecutiveFailures - 1, 10));
        return Math.min(backoff, BACKOFF_CAP_SECONDS);
    }

    // ==================== DIAGNOSTICS ====================

    /**
     * Walk the exception cause chain to find the real underlying error.
     *
     * Paho wraps the actual failure (SSLHandshakeException, CertPathValidatorException,
     * SocketTimeoutException, etc.) inside layers of MqttException. Error 32103
     * (SERVER_CONNECT_ERROR) is especially opaque — the getMessage() just says
     * "Unable to connect to server" with zero detail about WHY.
     *
     * This method digs through the chain and returns a human-readable string
     * showing each layer, so the log actually tells you what happened.
     */
    private static String extractRootCause(Throwable t) {
        if (t == null) return "Unknown";

        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());

        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth < 5) {
            sb.append(" → ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
            depth++;
        }

        return sb.toString();
    }

    // ==================== GETTERS ====================

    public MqttConnectionConfig getConfig() { return config; }
    public boolean isConnected() { return connected && client != null && client.isConnected(); }
    public boolean isRunning() { return running; }
    public long getTotalPublishes() { return totalPublishes; }
    public long getFailedPublishes() { return failedPublishes; }
    public long getLastPublishTime() { return lastPublishTime; }
    public String getLastError() { return lastError; }
}
