package com.overdrive.app.mqtt;

import org.json.JSONObject;

import java.util.UUID;

/**
 * Configuration for a single MQTT broker connection.
 *
 * Each connection is independently configurable with its own broker, topic,
 * credentials, QoS, and publish interval. Multiple connections can run
 * simultaneously, all receiving the same telemetry data.
 *
 * Stored as JSON in /data/local/tmp/mqtt_connections.json.
 */
public class MqttConnectionConfig {

    public String id;                    // UUID, auto-generated
    public String name;                  // User-friendly label ("Home Assistant", "Fleet Server")
    public String brokerUrl;             // tcp://broker.hivemq.com or ssl://your-broker.com
    public int port;                     // 1883 (tcp) or 8883 (ssl)
    public String topic;                 // e.g. overdrive/vehicle/telemetry
    public String clientId;              // Auto-generated from deviceId + connectionId
    public String username;              // Optional MQTT auth
    public String password;              // Optional MQTT auth
    public int qos;                      // 0 = fire-and-forget, 1 = at-least-once
    public boolean enabled;              // Per-connection toggle
    public int publishIntervalSeconds;   // How often to publish (default: 5)
    public boolean adaptiveInterval;     // If true, slow down when parked (like ABRP)
    public boolean retainMessages;       // MQTT retain flag on published messages
    public boolean trustAllCerts;        // If true, accept self-signed/untrusted certs (Home Assistant)

    // Defaults
    private static final int DEFAULT_PORT = 1883;
    private static final int DEFAULT_QOS = 0;
    private static final int DEFAULT_PUBLISH_INTERVAL = 5;
    private static final boolean DEFAULT_ADAPTIVE = true;
    private static final boolean DEFAULT_RETAIN = false;
    private static final boolean DEFAULT_TRUST_ALL_CERTS = false;

    /**
     * Create a new connection config with defaults.
     */
    public MqttConnectionConfig() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = "";
        this.brokerUrl = "";
        this.port = DEFAULT_PORT;
        this.topic = "overdrive/vehicle/telemetry";
        this.clientId = "";
        this.username = "";
        this.password = "";
        this.qos = DEFAULT_QOS;
        this.enabled = false;
        this.publishIntervalSeconds = DEFAULT_PUBLISH_INTERVAL;
        this.adaptiveInterval = DEFAULT_ADAPTIVE;
        this.retainMessages = DEFAULT_RETAIN;
        this.trustAllCerts = DEFAULT_TRUST_ALL_CERTS;
    }

    /**
     * Build the full broker URI for Paho MQTT client.
     *
     * Supports all Paho URI formats:
     *   tcp://host:port          — plain MQTT
     *   ssl://host:port          — MQTT over TLS
     *   ws://host:port/path      — MQTT over WebSocket
     *   wss://host:port/path     — MQTT over secure WebSocket (the ISP firewall bypass)
     *
     * The regex must allow an optional path after the port (e.g. /mqtt) so that
     * wss://mqtt.eclipseprojects.io:443/mqtt is passed through as-is instead of
     * getting a second port appended.
     */
    public String getBrokerUri() {
        String url = brokerUrl;
        if (url == null || url.isEmpty()) return "";

        // Strip trailing slash (but not path components like /mqtt)
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        // If the URL already contains a port (with or without a trailing path), use as-is.
        // Matches: wss://broker:443  |  wss://broker:443/mqtt  |  ssl://broker:8883
        if (url.matches("^(tcp|ssl|ws|wss)://.*:\\d+(/.*)?$")) {
            return url;
        }

        // Protocol present but no port — append the configured port
        if (url.matches("^(tcp|ssl|ws|wss)://.*")) {
            return url + ":" + port;
        }

        // Bare hostname — prepend tcp:// and append port
        return "tcp://" + url + ":" + port;
    }

    /**
     * Generate a client ID from device ID and connection ID.
     * Falls back to connection ID if device ID is not available.
     */
    public String getEffectiveClientId(String deviceId) {
        if (clientId != null && !clientId.isEmpty()) {
            return clientId;
        }
        if (deviceId != null && !deviceId.isEmpty()) {
            return "overdrive-" + deviceId.substring(0, Math.min(8, deviceId.length())) + "-" + id;
        }
        return "overdrive-" + id;
    }

    /**
     * Check if the broker URI uses a secure protocol (ssl:// or wss://).
     */
    public boolean isSsl() {
        String uri = getBrokerUri();
        return uri.startsWith("ssl://") || uri.startsWith("wss://");
    }

    /**
     * Check if this connection has minimum required configuration.
     */
    public boolean isConfigured() {
        return brokerUrl != null && !brokerUrl.isEmpty()
                && topic != null && !topic.isEmpty();
    }

    /**
     * Returns a masked version of the password for display.
     */
    public String getMaskedPassword() {
        if (password == null || password.isEmpty()) return "";
        if (password.length() <= 2) return "••";
        return "••••" + password.substring(password.length() - 2);
    }

    // ==================== SERIALIZATION ====================

    /**
     * Serialize to JSON for storage.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("name", name);
            json.put("brokerUrl", brokerUrl);
            json.put("port", port);
            json.put("topic", topic);
            json.put("clientId", clientId);
            json.put("username", username);
            json.put("password", password);
            json.put("qos", qos);
            json.put("enabled", enabled);
            json.put("publishIntervalSeconds", publishIntervalSeconds);
            json.put("adaptiveInterval", adaptiveInterval);
            json.put("retainMessages", retainMessages);
            json.put("trustAllCerts", trustAllCerts);
        } catch (Exception ignored) {}
        return json;
    }

    /**
     * Serialize to JSON for API responses (masks password).
     */
    public JSONObject toSafeJson() {
        JSONObject json = toJson();
        try {
            json.put("password", getMaskedPassword());
            json.put("configured", isConfigured());
        } catch (Exception ignored) {}
        return json;
    }

    /**
     * Deserialize from JSON.
     */
    public static MqttConnectionConfig fromJson(JSONObject json) {
        MqttConnectionConfig config = new MqttConnectionConfig();
        config.id = json.optString("id", config.id);
        config.name = json.optString("name", "");
        config.brokerUrl = json.optString("brokerUrl", "");
        config.port = json.optInt("port", DEFAULT_PORT);
        config.topic = json.optString("topic", "overdrive/vehicle/telemetry");
        config.clientId = json.optString("clientId", "");
        config.username = json.optString("username", "");
        config.password = json.optString("password", "");
        config.qos = json.optInt("qos", DEFAULT_QOS);
        config.enabled = json.optBoolean("enabled", false);
        config.publishIntervalSeconds = json.optInt("publishIntervalSeconds", DEFAULT_PUBLISH_INTERVAL);
        config.adaptiveInterval = json.optBoolean("adaptiveInterval", DEFAULT_ADAPTIVE);
        config.retainMessages = json.optBoolean("retainMessages", DEFAULT_RETAIN);
        config.trustAllCerts = json.optBoolean("trustAllCerts", DEFAULT_TRUST_ALL_CERTS);
        return config;
    }

    @Override
    public String toString() {
        return "MqttConnectionConfig{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", broker='" + brokerUrl + ":" + port + '\'' +
                ", topic='" + topic + '\'' +
                ", enabled=" + enabled +
                ", interval=" + publishIntervalSeconds + "s" +
                ", adaptive=" + adaptiveInterval +
                ", ssl=" + isSsl() +
                ", trustAllCerts=" + trustAllCerts +
                '}';
    }
}
