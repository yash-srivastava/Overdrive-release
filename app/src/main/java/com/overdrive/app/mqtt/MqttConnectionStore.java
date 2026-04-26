package com.overdrive.app.mqtt;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent storage for MQTT connection configurations.
 *
 * Stores all connections as a JSON array in /data/local/tmp/mqtt_connections.json.
 * Thread-safe — all reads/writes are synchronized.
 *
 * Maximum 5 connections to keep resource usage reasonable on the BYD head unit.
 */
public class MqttConnectionStore {

    private static final String TAG = "MqttConnectionStore";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final String CONFIG_PATH = "/data/local/tmp/mqtt_connections.json";
    public static final int MAX_CONNECTIONS = 5;

    private final List<MqttConnectionConfig> connections = new ArrayList<>();
    private final Object lock = new Object();

    /**
     * Load all connections from disk.
     * @return number of connections loaded
     */
    public int load() {
        synchronized (lock) {
            connections.clear();
            try {
                File file = new File(CONFIG_PATH);
                if (!file.exists()) {
                    logger.info("No MQTT config file found at " + CONFIG_PATH);
                    return 0;
                }

                byte[] bytes;
                try (FileInputStream fis = new FileInputStream(file)) {
                    bytes = new byte[(int) file.length()];
                    fis.read(bytes);
                }

                String content = new String(bytes, StandardCharsets.UTF_8);
                JSONArray array = new JSONArray(content);

                for (int i = 0; i < array.length() && i < MAX_CONNECTIONS; i++) {
                    JSONObject obj = array.getJSONObject(i);
                    MqttConnectionConfig config = MqttConnectionConfig.fromJson(obj);
                    connections.add(config);
                }

                logger.info("Loaded " + connections.size() + " MQTT connections");
                return connections.size();
            } catch (Exception e) {
                logger.error("Failed to load MQTT connections: " + e.getMessage());
                return 0;
            }
        }
    }

    /**
     * Save all connections to disk.
     * @return true if saved successfully
     */
    public boolean save() {
        synchronized (lock) {
            try {
                JSONArray array = new JSONArray();
                for (MqttConnectionConfig config : connections) {
                    array.put(config.toJson());
                }

                String content = array.toString(2);
                try (FileOutputStream fos = new FileOutputStream(CONFIG_PATH)) {
                    fos.write(content.getBytes(StandardCharsets.UTF_8));
                }

                logger.info("Saved " + connections.size() + " MQTT connections to " + CONFIG_PATH);
                return true;
            } catch (Exception e) {
                logger.error("Failed to save MQTT connections: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Get all connections (defensive copy).
     */
    public List<MqttConnectionConfig> getAll() {
        synchronized (lock) {
            return new ArrayList<>(connections);
        }
    }

    /**
     * Get all enabled and configured connections.
     */
    public List<MqttConnectionConfig> getEnabled() {
        synchronized (lock) {
            List<MqttConnectionConfig> enabled = new ArrayList<>();
            for (MqttConnectionConfig config : connections) {
                if (config.enabled && config.isConfigured()) {
                    enabled.add(config);
                }
            }
            return enabled;
        }
    }

    /**
     * Get a connection by ID.
     * @return the connection, or null if not found
     */
    public MqttConnectionConfig getById(String id) {
        synchronized (lock) {
            for (MqttConnectionConfig config : connections) {
                if (config.id.equals(id)) {
                    return config;
                }
            }
            return null;
        }
    }

    /**
     * Add a new connection.
     * @return the added config, or null if max connections reached
     */
    public MqttConnectionConfig add(MqttConnectionConfig config) {
        synchronized (lock) {
            if (connections.size() >= MAX_CONNECTIONS) {
                logger.warn("Cannot add MQTT connection: max " + MAX_CONNECTIONS + " reached");
                return null;
            }
            connections.add(config);
            save();
            logger.info("Added MQTT connection: " + config);
            return config;
        }
    }

    /**
     * Update an existing connection by ID.
     * Merges non-null fields from the update into the existing config.
     * @return true if updated
     */
    public boolean update(String id, JSONObject updates) {
        synchronized (lock) {
            MqttConnectionConfig existing = getById(id);
            if (existing == null) {
                logger.warn("Cannot update MQTT connection: ID not found: " + id);
                return false;
            }

            if (updates.has("name")) existing.name = updates.optString("name");
            if (updates.has("brokerUrl")) existing.brokerUrl = updates.optString("brokerUrl");
            if (updates.has("port")) existing.port = updates.optInt("port");
            if (updates.has("topic")) existing.topic = updates.optString("topic");
            if (updates.has("clientId")) existing.clientId = updates.optString("clientId");
            if (updates.has("username")) existing.username = updates.optString("username");
            // Only update password if explicitly provided (not masked)
            if (updates.has("password")) {
                String pwd = updates.optString("password");
                if (!pwd.startsWith("••")) {
                    existing.password = pwd;
                }
            }
            if (updates.has("qos")) existing.qos = updates.optInt("qos");
            if (updates.has("enabled")) existing.enabled = updates.optBoolean("enabled");
            if (updates.has("publishIntervalSeconds")) existing.publishIntervalSeconds = updates.optInt("publishIntervalSeconds");
            if (updates.has("adaptiveInterval")) existing.adaptiveInterval = updates.optBoolean("adaptiveInterval");
            if (updates.has("retainMessages")) existing.retainMessages = updates.optBoolean("retainMessages");
            if (updates.has("trustAllCerts")) existing.trustAllCerts = updates.optBoolean("trustAllCerts");

            save();
            logger.info("Updated MQTT connection: " + existing);
            return true;
        }
    }

    /**
     * Delete a connection by ID.
     * @return true if deleted
     */
    public boolean delete(String id) {
        synchronized (lock) {
            for (int i = 0; i < connections.size(); i++) {
                if (connections.get(i).id.equals(id)) {
                    MqttConnectionConfig removed = connections.remove(i);
                    save();
                    logger.info("Deleted MQTT connection: " + removed.name + " (" + id + ")");
                    return true;
                }
            }
            logger.warn("Cannot delete MQTT connection: ID not found: " + id);
            return false;
        }
    }

    /**
     * Get the number of connections.
     */
    public int size() {
        synchronized (lock) {
            return connections.size();
        }
    }
}
