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

        try {
            // Create client with in-memory persistence (no filesystem needed)
            client = new MqttClient(brokerUri, effectiveClientId, new MemoryPersistence());
            client.setCallback(this);

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

            // Proxy-aware socket factory
            options.setSocketFactory(ProxyHelper.getMqttSocketFactory());

            logger.info("Connecting to " + brokerUri + " as " + effectiveClientId
                    + " (proxy=" + ProxyHelper.isProxyAvailable() + ")");

            client.connect(options);
            connected = true;
            running = true;
            consecutiveFailures = 0;
            lastError = null;

            logger.info("Connected to " + brokerUri);
            return true;

        } catch (MqttException e) {
            connected = false;
            consecutiveFailures++;
            lastError = "Connect failed: " + e.getMessage() + " (reason=" + e.getReasonCode() + ")";
            logger.error(lastError);

            // Invalidate proxy cache on connection failure — proxy state may have changed
            ProxyHelper.invalidateCache();
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
                    client.disconnect(5000);
                }
                client.close();
            } catch (MqttException e) {
                logger.warn("Disconnect error: " + e.getMessage());
            }
            client = null;
        }

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
        lastError = "Connection lost: " + (cause != null ? cause.getMessage() : "unknown");
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

    // ==================== GETTERS ====================

    public MqttConnectionConfig getConfig() { return config; }
    public boolean isConnected() { return connected && client != null && client.isConnected(); }
    public boolean isRunning() { return running; }
    public long getTotalPublishes() { return totalPublishes; }
    public long getFailedPublishes() { return failedPublishes; }
    public long getLastPublishTime() { return lastPublishTime; }
    public String getLastError() { return lastError; }
}
