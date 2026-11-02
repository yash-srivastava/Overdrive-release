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
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * MQTT publisher for a single broker connection.
 *
 * Manages the Paho MQTT client lifecycle: connect, publish, reconnect, disconnect.
 * Each instance is bound to one MqttConnectionConfig and publishes telemetry
 * JSON payloads to the configured topic.
 *
 * Proxy-aware: uses ProxyHelper to route through sing-box when available.
 * Reconnection: exponential backoff from 5s, capped at 60s for network failures and 300s for
 * broker refusals; a change in the car's network retries at once.
 *
 * Thread safety: all public methods are synchronized on the instance.
 */
public class MqttPublisherService implements MqttCallback {

    private static final String TAG = "MqttPublisher";
    private static final String CHARGE_CAP_PERCENT_KEY = "charge_cap_percent";
    private static final String CHARGE_CAP_ENABLED_KEY = "charge_cap_enabled";
    private static final String CABIN_TEMP_KEY = "cabin_temp";
    private static final String INSIDE_TEMP_KEY = "inside_temp";
    private static final String[] NO_TOPICS = new String[0];
    private final DaemonLogger logger;

    // Backoff constants
    private static final int BACKOFF_BASE_SECONDS = 5;
    private static final int BACKOFF_CAP_SECONDS = 300;
    // No route / DNS / timeout retries cost no data; only broker refusals earn the long cap.
    private static final int BACKOFF_NETWORK_CAP_SECONDS = 60;

    // How long to wait for an enabled-but-not-yet-up Tailscale proxy before falling back to a
    // direct dial. Covers the boot window (tailscaled still binding) without permanently refusing
    // a directly-reachable broker if the proxy is genuinely dead.
    private static final long PROXY_WARMUP_GRACE_MS = 60_000L;

    // Connection config
    private final MqttConnectionConfig config;
    private final String deviceId;

    // Paho MQTT client. Volatile: mutated only under the instance lock (connect/
    // disconnect), but read unsynchronized by status/IPC threads via isConnected()
    // and getStatus() — those must see a current reference, and must read it ONCE
    // into a local (two reads can NPE if disconnect() nulls the field in between).
    private volatile MqttClient client;
    private volatile boolean running = false;
    private volatile boolean connected = false;

    // Guards commandRouter creation/teardown. A dedicated lock — NOT the instance
    // monitor — so the Paho callback thread (messageArrived → ensureCommandRouter)
    // never blocks behind a scheduler thread holding the instance lock inside a
    // blocking connect() (~10s) or disconnect() (~5s quiesce). The old synchronized
    // ensureCommandRouter also created a bounded disconnect-vs-callback standoff:
    // disconnect(5000) waits for the callback thread to quiesce while the callback
    // thread waits for the instance lock. Lock ordering: instance lock → routerLock
    // (disconnect takes both); routerLock never wraps the instance lock.
    private final Object routerLock = new Object();

    // Stats
    private volatile long totalPublishes = 0;
    private volatile long failedPublishes = 0;
    private volatile long lastPublishTime = 0;
    private volatile int consecutiveFailures = 0;
    private volatile String lastError = null;
    // Proxy warm-up tracking (issue #182): when the first defer started, and whether we've already
    // logged this warm-up so the health loop doesn't spam an identical warning every cycle.
    private volatile long proxyWaitStartMs = 0L;
    private volatile boolean loggedProxyWait = false;
    // Reconnect pacing (see reconnectIfDue), on System.nanoTime so a wall-clock jump can't stall it.
    // lastAttemptNetwork is guarded by the instance lock.
    private volatile long nextRetryAtNanos = System.nanoTime();
    private volatile boolean lastFailureRefused = false;
    private String lastAttemptNetwork = null;

    // Change detection (report-by-exception) + Home Assistant discovery state.
    // TelemetryDiffer is documented single-thread-owned: ALL access to it must
    // happen on the scheduler thread inside the synchronized publishTelemetry().
    // The Paho callback thread (messageArrived) must NOT touch it directly —
    // it sets forceFullResend instead, and the publish thread performs reset().
    private final TelemetryDiffer differ = new TelemetryDiffer();
    private volatile boolean forceFullResend = false;
    private volatile boolean discoveryAnnounced = false;
    // Discoverable keys already covered by the announced bundle. Grows as fields appear so a
    // late-populating field (e.g. hv_pack_v, which needs cells + pack capacity, so it shows up
    // after the first publish) triggers a re-announce instead of never getting a component.
    private final java.util.Set<String> announcedKeys = new java.util.HashSet<>();
    // State-transition flush. On any mode edge (ACC on/off, charging start/stop) we flush a
    // full snapshot for a few cycles so the new state survives a single lost publish. Owned
    // by the publish thread, same as the differ.
    private boolean stateInit = false;
    private boolean prevAccOn = false;
    private boolean prevCharging = false;
    private int stateFlushCycles = 0;
    private volatile MqttCommandRouter commandRouter;
    private volatile String haVin = null;
    private volatile String haModel = null;
    private volatile String haSwVersion = null;
    // HA state topics are retained independently. Keep track of the ones we have actually
    // published so a capability transition can remove only state this connection owns.
    private boolean chargeCapPercentStatePublished = false;
    private boolean chargeCapEnabledStatePublished = false;
    // The HA device bundle is retained as one document. Track whether its last successful
    // version exposed the dynamic charge-cap controls so a capability change replaces it.
    private boolean chargeCapControlsAnnounced = false;

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

        // Mark the connection active as soon as it has a broker to attempt, independent of whether
        // this first connect succeeds. Otherwise a failed OR deferred initial connect leaves
        // running=false, and MqttConnectionManager's health loop bails on !isRunning() and never
        // reschedules — the connection stays dead until a daemon restart. That gate (not the direct
        // dial alone) is the real "never recovers" root cause behind #182, and the reason the
        // "will retry on first publish" contract at the call site never actually held.
        running = true;

        // issue #182: when the Tailscale or WireGuard SOCKS proxy is ENABLED the broker is normally reachable
        // ONLY through it (e.g. a LAN broker behind a subnet router while the car is on cellular).
        // A DIRECT dial in that state can never succeed off Wi-Fi and strands the connection, so
        // hold off while the proxy is warming up (tailscaled/wgproxy still binding at boot / after a link
        // change) instead of falling through to the direct-socket path below. We do NOT bump
        // consecutiveFailures — the health loop re-probes at the min-interval floor and connects the
        // instant the proxy binds. Bounded by PROXY_WARMUP_GRACE_MS so a genuinely dead proxy can't
        // permanently refuse a directly-reachable broker: after the grace window we fall through and
        // try a direct dial as a last resort.
        if (ProxyHelper.isMqttProxyExpected() && !ProxyHelper.isProxyAvailable()) {
            long now = System.currentTimeMillis();
            if (proxyWaitStartMs == 0L) proxyWaitStartMs = now;
            long waitedMs = now - proxyWaitStartMs;
            if (waitedMs < PROXY_WARMUP_GRACE_MS) {
                connected = false;
                lastError = "Proxy enabled but not reachable yet (127.0.0.1:"
                        + ProxyHelper.getExpectedProxyPort() + ") — deferring connect until proxy is up";
                if (!loggedProxyWait) {
                    logger.warn(lastError);
                    loggedProxyWait = true;
                }
                return false;
            }
            logger.warn("Proxy still unreachable after " + (waitedMs / 1000)
                    + "s — attempting a direct connect as a fallback");
        }
        // Proxy is up (or the grace window elapsed) — clear the warm-up state and proceed to connect.
        proxyWaitStartMs = 0L;
        loggedProxyWait = false;

        String effectiveClientId = config.getEffectiveClientId(deviceId);

        // Tear down any stale client before opening a new one. Without this, a reconnect after a
        // dropped/half-open connection leaves the old Paho client (same clientId) abandoned but not
        // closed — the broker then sees two clients with the same id and the new connect can fail
        // with "Client is connected" (Paho reason 32100). Closing it first releases the clientId.
        if (client != null) {
            try { if (client.isConnected()) client.disconnect(1000); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
            client = null;
        }

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

            // Whether this connect must SET the process-global SOCKS properties (WS/WSS +
            // proxy, Paho bug workaround) or CLEAR them (direct connect on a default
            // factory — leftover props would misroute it through a dead proxy). The actual
            // set/clear is deferred to just before connect() under ProxyHelper.SOCKS_PROPS_LOCK
            // so a sibling connection's connect can't stomp the props mid-window (see the
            // lock's javadoc). Factory-proxied paths ignore the props and skip the lock.
            boolean setSocksProps = false;
            boolean clearSocksProps = false;

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
                    setSocksProps = true;
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
                    setSocksProps = true;
                } else {
                    // Plain TCP + Proxy: ProxiedSocketFactory works fine.
                    options.setSocketFactory(ProxyHelper.getMqttSocketFactory());
                }
            } else {
                // No proxy — leftover system SOCKS properties from a previous WS+proxy
                // connect would misroute this DIRECT connection's default-factory socket,
                // so clear them (under the shared lock, at connect time below).
                clearSocksProps = true;

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

            // Props-sensitive connects hold SOCKS_PROPS_LOCK from the property mutation
            // through socket creation (inside connect()) so concurrent connects on other
            // scheduler threads can't set/clear the props mid-window. Only socket creation
            // reads them, so they are cleared as soon as connect returns: left set, they
            // would tunnel every other default-factory socket in the daemon.
            // Factory-proxied connects (neither flag) skip the lock entirely, so this
            // serializes connect attempts only when the global props are actually in play.
            if (setSocksProps || clearSocksProps) {
                synchronized (ProxyHelper.SOCKS_PROPS_LOCK) {
                    if (setSocksProps) {
                        System.setProperty("socksProxyHost", "127.0.0.1");
                        System.setProperty("socksProxyPort", String.valueOf(ProxyHelper.getProxyPort()));
                    } else {
                        System.clearProperty("socksProxyHost");
                        System.clearProperty("socksProxyPort");
                    }
                    try {
                        newClient.connect(options);
                    } finally {
                        System.clearProperty("socksProxyHost");
                        System.clearProperty("socksProxyPort");
                    }
                }
            } else {
                newClient.connect(options);
            }
            client = newClient;
            connected = true;
            running = true;
            consecutiveFailures = 0;
            nextRetryAtNanos = System.nanoTime();
            lastFailureRefused = false;
            lastError = null;
            // QoS 0 publishes made just before a drop "succeed" into the dead socket: resync all.
            forceFullResend = true;

            // Publish "online" availability immediately after successful connect.
            // This pairs with the LWT "offline" — consumers can subscribe to
            // <topic>/availability to track connection state.
            try {
                client.publish(lwtTopic, "online".getBytes(), 1, true);
            } catch (MqttException e) {
                logger.warn("Failed to publish availability online: " + e.getMessage());
            }

            // Home Assistant: re-announce discovery after every (re)connect, and listen for
            // HA's birth message so we re-announce + resend state when HA restarts.
            if (config.isHomeAssistant()) {
                discoveryAnnounced = false;
                try {
                    client.subscribe(HomeAssistantDiscovery.statusTopic(config.discoveryPrefix), 0);
                } catch (MqttException e) {
                    logger.warn("HA status subscribe failed: " + e.getMessage());
                }
                // Vehicle control (local SDK only): subscribe to inbound command topics
                // <base>/<key>/set and <base>/<key>/<sub>/set (composite climate/cover).
                if (config.isControlEnabled()) {
                    try {
                        client.subscribe(config.topic + "/+/set", config.qos);
                        client.subscribe(config.topic + "/+/+/set", config.qos);
                        logger.info("Subscribed to vehicle-control command topics under " + config.topic);
                    } catch (MqttException e) {
                        logger.warn("Control command subscribe failed: " + e.getMessage());
                    }
                }
            }

            // Inbound automation triggers: <base>/automation/<channel>. Independent of the
            // HA/control gates above — an external broker message on this subtree fires an
            // OverDrive automation (see messageArrived → Automations.publishMqttTrigger).
            // Re-subscribed on every (re)connect since connect() is the reconnect path.
            // Cheap and inert when no automation watches the channel.
            try {
                client.subscribe(config.topic + "/automation/+", config.qos);
                logger.info("Subscribed to inbound automation-trigger topics under " + config.topic + "/automation/");
            } catch (MqttException e) {
                logger.warn("Automation-trigger subscribe failed: " + e.getMessage());
            }

            logger.info("Connected to " + brokerUri);
            return true;

        } catch (MqttException e) {
            connected = false;
            consecutiveFailures++;
            lastFailureRefused = isBrokerRefusal(e);
            nextRetryAtNanos = System.nanoTime() + getBackoffSeconds() * 1_000_000_000L;

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
            lastFailureRefused = true;
            nextRetryAtNanos = System.nanoTime() + getBackoffSeconds() * 1_000_000_000L;
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

        // Router teardown under routerLock so it can't interleave with a concurrent
        // ensureCommandRouter() on the Paho callback thread. running=false is already
        // visible (volatile, set above), so a racing ensureCommandRouter() that enters
        // routerLock after us sees !running and returns null instead of resurrecting
        // a router for a connection that's shutting down.
        synchronized (routerLock) {
            if (commandRouter != null) {
                commandRouter.shutdown();
                commandRouter = null;
            }
        }

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

        // The JVM-level socksProxyHost/Port properties live only inside connect()'s
        // SOCKS_PROPS_LOCK window, so there is nothing to clear here.

        logger.info("Disconnected from " + config.name + " (" + config.getBrokerUri() + ")");
    }

    /** Latest vehicle identity used to build the HA discovery device block. */
    public void setHaMeta(String vin, String model, String swVersion) {
        this.haVin = vin;
        this.haModel = model;
        this.haSwVersion = swVersion;
    }

    /**
     * Publish a telemetry snapshot, applying change detection and the min/max
     * interval window. Behaviour depends on the connection mode:
     *
     *  - Home Assistant mode: per-field retained topics (only changed fields, or
     *    everything on heartbeat/first), plus a one-time device-bundle discovery
     *    announce. No aggregate JSON.
     *  - Aggregate mode: the full JSON blob to the configured topic, but only when
     *    a backing value changed (or on heartbeat) — retain stays valid because we
     *    always send a complete snapshot.
     *
     * @return true if nothing went wrong (a deliberate skip also returns true)
     */
    public synchronized boolean publishTelemetry(JSONObject snapshot) {
        if (!running) return false;

        // Apply a deferred reset requested by the Paho callback thread (HA birth).
        // Done here, on the publish thread, so the differ's HashMap is only ever
        // mutated by one thread — avoids the ConcurrentModificationException /
        // lost-update race that a direct differ.reset() in messageArrived caused.
        if (forceFullResend) {
            forceFullResend = false;
            differ.reset();
        }

        long now = System.currentTimeMillis();

        // Read the vehicle state up front — it drives both the state-transition full-sync AND the
        // per-state heartbeat ceiling (parked / charging overrides). Cheap in-memory reads; default
        // to the previous cycle's values if a monitor isn't ready so the interval stays stable.
        boolean carOn = prevAccOn, charging = prevCharging;
        try { carOn = com.overdrive.app.monitor.AccMonitor.isAccOn(); } catch (Throwable ignored) {}
        try { charging = com.overdrive.app.monitor.ChargingDetector.getInstance().isCharging(); } catch (Throwable ignored) {}

        long minMs = Math.max(1, config.minIntervalSeconds) * 1000L;
        // Heartbeat ceiling is state-aware: while charging (or, failing that, while parked) the
        // config's per-state override replaces maxIntervalSeconds. effectiveMaxIntervalSeconds
        // floors the result at minIntervalSeconds, so maxMs >= minMs always holds.
        long maxMs = config.effectiveMaxIntervalSeconds(carOn, charging) * 1000L;

        boolean first = differ.lastSendTimeMs() == 0;
        Set<String> changed = differ.changedKeys(snapshot);
        boolean cabinTombstone = isChangedNull(snapshot, changed, CABIN_TEMP_KEY)
                || isChangedNull(snapshot, changed, INSIDE_TEMP_KEY);
        boolean verifiedChargeCapState = hasVerifiedChargeCapState(snapshot);
        boolean advertiseChargeCapControls = config.isControlEnabled() && verifiedChargeCapState;
        String[] chargeCapTombstones = config.isHomeAssistant()
                ? chargeCapTombstoneKeys(chargeCapPercentStatePublished,
                        chargeCapEnabledStatePublished, verifiedChargeCapState, first)
                : NO_TOPICS;
        // Heartbeat: with heartbeatSendAll, fire on a fixed cadence since the last FULL sync
        // (immune to change-only partial publishes resetting the clock — the starvation bug).
        boolean heartbeat = (config.heartbeatSendAll ? differ.fullSyncElapsedMs(now)
                                                     : differ.elapsedMs(now)) >= maxMs;

        // Full-sync on every state-mode transition (ACC on↔off, charging start↔stop) so the new
        // state survives even if a single change-publish is lost at a network handoff. Cheap:
        // fires only on edges, a few full sends each. Inert until the monitors first report.
        if (config.flushOnStateChange && stateInit && (carOn != prevAccOn || charging != prevCharging)) {
            stateFlushCycles = 5;
        }
        prevAccOn = carOn; prevCharging = charging; stateInit = true;
        boolean flushNow = stateFlushCycles > 0;

        // Rate-limit floor: never transmit more often than the min interval, unless this is the
        // first publish, a heartbeat, or a state-transition flush.
        if (chargeCapTombstones.length == 0 && !cabinTombstone
                && !first && !heartbeat && !flushNow && differ.elapsedMs(now) < minMs) {
            return true;
        }

        if (config.isHomeAssistant()) {
            if (!ensureConnected()) return false;
            // Re-announce if a discoverable field has appeared that the last bundle didn't cover
            // (fields populate at different times; the first publish doesn't have them all yet).
            if (discoveryAnnounced && !announcedKeys.containsAll(discoverableKeys(snapshot))) {
                discoveryAnnounced = false;
            }
            if (chargeCapDiscoveryNeedsRefresh(discoveryAnnounced, chargeCapControlsAnnounced,
                    advertiseChargeCapControls)) {
                discoveryAnnounced = false;
            }
            if (!discoveryAnnounced) announceDiscovery(snapshot);

            if (chargeCapTombstones.length > 0) {
                if (!clearChargeCapState(chargeCapTombstones)) return false;
                // The differ cannot observe an omitted JSON key. Forget the complete snapshot
                // after clearing retained cap state so an eventual verified reappearance is sent
                // even when its values happen to match the earlier retained values.
                differ.reset();
                first = true;
            }

            boolean sendAll = first || heartbeat || !config.changeOnly || flushNow;
            Set<String> keys = sendAll ? publishableStateKeys(snapshot) : changed;
            if (cabinTombstone) {
                if (snapshot.opt(CABIN_TEMP_KEY) == JSONObject.NULL) keys.add(CABIN_TEMP_KEY);
                if (snapshot.opt(INSIDE_TEMP_KEY) == JSONObject.NULL) keys.add(INSIDE_TEMP_KEY);
            }
            if (!verifiedChargeCapState) {
                // The manager omits unverified cap values, but keep this defensive boundary at
                // the publish point too: malformed/partial snapshots must never become HA state.
                keys.remove(CHARGE_CAP_PERCENT_KEY);
                keys.remove(CHARGE_CAP_ENABLED_KEY);
            }
            if (flushNow && stateFlushCycles > 0) stateFlushCycles--;
            if (!sendAll && keys.isEmpty()) return true;

            boolean ok = true;
            for (String k : keys) {
                if (!TelemetryFieldCatalog.isPublishable(k)) continue;
                Object v = snapshot.opt(k);
                if (v == null || v instanceof JSONArray) continue;
                if (!publishString(HomeAssistantDiscovery.stateTopic(config.topic, k),
                        v == JSONObject.NULL ? "" : String.valueOf(v), true, config.qos)) {
                    ok = false;
                    break;
                }
                if (verifiedChargeCapState) recordChargeCapStatePublished(k);
            }
            if (ok && snapshot.has("lat") && snapshot.has("lon")
                    && (sendAll || changed.contains("lat") || changed.contains("lon"))) {
                publishLocation(snapshot);
            }
            // Re-assert the link so a stale "offline" (late will, sibling connection) can't linger.
            if (ok && (first || heartbeat)) {
                publishString(config.topic + "/availability", "online", true, config.qos);
            }
            if (ok) {
                differ.markKeysSent(snapshot, keys, now);
                if (sendAll) differ.markFullSync(now);
            }
            return ok;
        }

        // Aggregate mode — full snapshot. Honour the same full-sync triggers (heartbeat /
        // state-flush) so a parked snapshot still goes out even under changeOnly.
        boolean shouldSend = first || heartbeat || flushNow || cabinTombstone
                || differ.shouldPublish(!changed.isEmpty(), config.changeOnly, now, minMs, maxMs);
        if (flushNow && stateFlushCycles > 0) stateFlushCycles--;
        if (!shouldSend) return true;
        if (!publishString(config.topic, snapshot.toString(), config.retainMessages, config.qos)) {
            return false;
        }
        differ.markAllSent(snapshot, now);
        differ.markFullSync(now);
        return true;
    }

    /**
     * Publish a JSON payload to the configured topic (backward-compatible helper —
     * no change gating). Prefer {@link #publishTelemetry(JSONObject)}.
     */
    public synchronized boolean publish(JSONObject payload) {
        if (!running) return false;
        return publishString(config.topic, payload.toString(), config.retainMessages, config.qos);
    }

    /**
     * Publish an arbitrary payload to an arbitrary topic — the seam the automation
     * "Publish MQTT" action uses to notify Home Assistant (or any broker consumer). A
     * relative topic (no leading '/') is scoped under this connection's base topic so a
     * user needn't know the full prefix; an absolute topic (leading '/') is used as-is
     * minus the leading slash. Returns false when the connection isn't running so a
     * disabled/absent MQTT setup makes the action a clean no-op. Uses this connection's
     * QoS; retain is caller-chosen (HA state topics want retain=true).
     */
    public synchronized boolean publishToTopic(String topic, String payload, boolean retain) {
        if (!running) return false;
        if (topic == null || topic.isEmpty() || payload == null) return false;
        String full;
        if (topic.startsWith("/")) {
            full = topic.substring(1);
        } else {
            full = config.topic + "/" + topic;
        }
        return publishString(full, payload, retain, config.qos);
    }

    /** Low-level single-message publish with reconnect + stats handling. */
    private boolean publishString(String topic, String payload, boolean retain, int qos) {
        if (!ensureConnected()) {
            failedPublishes++;
            return false;
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(qos);
            message.setRetained(retain);
            client.publish(topic, message);

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
            connected = false;
            ProxyHelper.invalidateCache();
            return false;
        }
    }

    private boolean ensureConnected() {
        if (!running) return false;
        if (connected && client != null && client.isConnected()) return true;
        return reconnectIfDue();
    }

    /**
     * Reconnect unless still backing off on the same network. After a network-path failure, a
     * changed network (WiFi↔cellular, a new mobile address) retries at once on a fresh ramp; a
     * broker refusal keeps its ramp, since a new network can't fix credentials.
     */
    private boolean reconnectIfDue() {
        String network = networkFingerprint();
        boolean networkChanged = !lastFailureRefused && network != null
                && lastAttemptNetwork != null && !network.equals(lastAttemptNetwork);
        if (!networkChanged && System.nanoTime() - nextRetryAtNanos < 0) return false;
        if (networkChanged) {
            logger.info("Network changed — reconnecting now");
            consecutiveFailures = 0;
        }
        if (network != null) lastAttemptNetwork = network;
        return connect();
    }

    /**
     * Addresses of every up, non-loopback interface plus the source address the kernel picks for
     * outbound traffic (a default-route flip between two live interfaces changes only that).
     * null when unreadable, which never counts as a change.
     */
    static String networkFingerprint() {
        try {
            java.util.TreeSet<String> addrs = new java.util.TreeSet<>();
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            if (ifaces != null) {
                for (java.net.NetworkInterface nif : java.util.Collections.list(ifaces)) {
                    if (!nif.isUp() || nif.isLoopback()) continue;
                    for (java.net.InetAddress a : java.util.Collections.list(nif.getInetAddresses())) {
                        if (!a.isLinkLocalAddress()) addrs.add(nif.getName() + "/" + a.getHostAddress());
                    }
                }
            }
            String egress = "-";
            // UDP connect only selects a route; nothing is sent.
            try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
                probe.connect(java.net.InetAddress.getByAddress(new byte[] {8, 8, 8, 8}), 53);
                java.net.InetAddress local = probe.getLocalAddress();
                if (local != null) egress = local.getHostAddress();
            } catch (Throwable ignored) {}
            return addrs + " via " + egress;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The broker answered and refused us (CONNACK refusal, TLS trust), not a network-path failure. */
    private static boolean isBrokerRefusal(MqttException e) {
        int code = e.getReasonCode();
        if (code >= MqttException.REASON_CODE_INVALID_PROTOCOL_VERSION
                && code <= MqttException.REASON_CODE_NOT_AUTHORIZED) {
            return true;
        }
        Throwable t = e.getCause();
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof java.security.cert.CertificateException
                    || t instanceof java.security.cert.CertPathValidatorException
                    || t instanceof javax.net.ssl.SSLPeerUnverifiedException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Active connection health check, run every scheduler cycle independent of
     * whether a telemetry publish is actually due.
     *
     * Why this is needed: reconnect is otherwise only attempted as a side effect
     * of {@link #publishString} throwing. But the change-gated publish loop skips
     * idle cycles for up to {@code maxIntervalSeconds} (default 300s) while parked,
     * so a silently-dropped link (NAT/firewall idle-timeout, the ACC-OFF data
     * blackout) is never noticed — and with QoS 0 even the eventual heartbeat
     * publish can succeed into a half-open socket without throwing. The result is
     * a connection that reports "running" but transmits nothing until a manual
     * restart.
     *
     * Paho's keep-alive (30s) flips {@code client.isConnected()} to false / fires
     * {@code connectionLost} within ~keep-alive seconds of a real drop. Polling
     * that here lets the scheduler reconnect promptly instead of waiting for the
     * next heartbeat. Retries are paced by {@link #reconnectIfDue}, so a cheap
     * per-cycle call never hammers a dead broker.
     *
     * @return true if connected (already, or after a successful reconnect)
     */
    public synchronized boolean ensureAlive() {
        if (!running) return false;
        if (client != null && client.isConnected()) {
            connected = true;
            return true;
        }
        connected = false;
        return reconnectIfDue();
    }

    private Set<String> discoverableKeys(JSONObject snap) {
        Set<String> keys = new HashSet<>();
        Iterator<String> it = snap.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!TelemetryFieldCatalog.isPublishable(k)) continue;
            Object v = snap.opt(k);
            if (v == null || v == JSONObject.NULL || v instanceof JSONArray) continue;
            keys.add(k);
        }
        return keys;
    }

    /** Include explicit nulls so a stale sensor can clear its retained Home Assistant state. */
    private Set<String> publishableStateKeys(JSONObject snap) {
        Set<String> keys = discoverableKeys(snap);
        Iterator<String> it = snap.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (TelemetryFieldCatalog.isPublishable(key) && snap.opt(key) == JSONObject.NULL) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static boolean isChangedNull(JSONObject snapshot, Set<String> changed, String key) {
        return changed.contains(key) && snapshot.opt(key) == JSONObject.NULL;
    }

    /**
     * Charge-cap telemetry is verified only when the collector placed a complete, integral pair
     * into the normal telemetry snapshot. Do not accept strings or partial/raw register values.
     */
    static boolean hasVerifiedChargeCapState(JSONObject snapshot) {
        if (snapshot == null) return false;
        return isIntegralInRange(snapshot.opt(CHARGE_CAP_PERCENT_KEY), 50, 100)
                && isIntegralInRange(snapshot.opt(CHARGE_CAP_ENABLED_KEY), 0, 1);
    }

    /**
     * Return retained HA state topics that need clearing after verified charge-cap support
     * disappears. The flags are per connection, preventing unrelated/never-published topics from
     * being touched.
     */
    static String[] chargeCapTombstoneKeys(boolean percentPublished, boolean enabledPublished,
                                           boolean hasVerifiedCapState) {
        if (hasVerifiedCapState || (!percentPublished && !enabledPublished)) return NO_TOPICS;
        if (percentPublished && enabledPublished) {
            return new String[]{CHARGE_CAP_PERCENT_KEY, CHARGE_CAP_ENABLED_KEY};
        }
        return new String[]{percentPublished ? CHARGE_CAP_PERCENT_KEY : CHARGE_CAP_ENABLED_KEY};
    }

    /**
     * On the first publish after a daemon restart the in-memory ownership flags are empty, while
     * the broker can still retain this connection's previous charge-cap values. Clear both topics
     * when current readback is not verified so an old limit cannot outlive a trim/configuration
     * change merely because the process restarted before observing it.
     */
    static String[] chargeCapTombstoneKeys(boolean percentPublished, boolean enabledPublished,
                                           boolean hasVerifiedCapState, boolean firstPublish) {
        if (firstPublish && !hasVerifiedCapState
                && !percentPublished && !enabledPublished) {
            return new String[]{CHARGE_CAP_PERCENT_KEY, CHARGE_CAP_ENABLED_KEY};
        }
        return chargeCapTombstoneKeys(percentPublished, enabledPublished, hasVerifiedCapState);
    }

    static boolean chargeCapDiscoveryNeedsRefresh(boolean discoveryAnnounced,
                                                  boolean announcedControls,
                                                  boolean currentControls) {
        return discoveryAnnounced && announcedControls != currentControls;
    }

    private static boolean isIntegralInRange(Object value, int min, int max) {
        if (!(value instanceof Number)) return false;
        double numeric = ((Number) value).doubleValue();
        return !Double.isNaN(numeric) && !Double.isInfinite(numeric)
                && numeric == Math.rint(numeric) && numeric >= min && numeric <= max;
    }

    private boolean clearChargeCapState(String[] topics) {
        for (String key : topics) {
            if (!publishString(HomeAssistantDiscovery.stateTopic(config.topic, key),
                    "", true, config.qos)) {
                return false;
            }
            if (CHARGE_CAP_PERCENT_KEY.equals(key)) chargeCapPercentStatePublished = false;
            if (CHARGE_CAP_ENABLED_KEY.equals(key)) chargeCapEnabledStatePublished = false;
        }
        return true;
    }

    private void recordChargeCapStatePublished(String key) {
        if (CHARGE_CAP_PERCENT_KEY.equals(key)) chargeCapPercentStatePublished = true;
        if (CHARGE_CAP_ENABLED_KEY.equals(key)) chargeCapEnabledStatePublished = true;
    }

    private void publishLocation(JSONObject snap) {
        try {
            JSONObject loc = new JSONObject();
            loc.put("latitude", snap.optDouble("lat"));
            loc.put("longitude", snap.optDouble("lon"));
            // When the fix carried its own timestamp, pass it on: a consumer aligning
            // this position against another clock needs when the fix was TAKEN, not
            // when it was published. Omitted rather than zeroed when unreported.
            long fixUtc = snap.optLong("gps_utc", 0L);
            if (fixUtc > 0) loc.put("gps_utc", fixUtc);
            publishString(HomeAssistantDiscovery.locationTopic(config.topic), loc.toString(), true, config.qos);
        } catch (Exception ignored) {}
    }

    /** Publish the retained device-bundle discovery config (HA mode). */
    private void announceDiscovery(JSONObject snapshot) {
        try {
            String topic = HomeAssistantDiscovery.deviceConfigTopic(config.discoveryPrefix, deviceId);
            // Pass announcedKeys as sticky so a field that's momentarily absent from this snapshot
            // (e.g. the derived hv_pack_v) isn't dropped from the bundle on a re-announce — that
            // drop is what left fields Unavailable in HA after a restart.
            String bundle = HomeAssistantDiscovery.buildBundle(deviceId, haVin, haModel, haSwVersion,
                    config.topic, snapshot, announcedKeys, config.isControlEnabled());
            if (publishString(topic, bundle, true, 1)) {
                discoveryAnnounced = true;
                chargeCapControlsAnnounced = config.isControlEnabled()
                        && hasVerifiedChargeCapState(snapshot);
                announcedKeys.addAll(discoverableKeys(snapshot));
                logger.info("Published HA discovery bundle to " + topic
                        + " (" + announcedKeys.size() + " keys)");
            }
        } catch (Exception e) {
            logger.warn("HA discovery announce failed: " + e.getMessage());
        }
    }

    /**
     * Remove the HA device (empty retained payload) so toggling discovery off or
     * deleting a connection doesn't orphan entities. Best-effort; needs a live client.
     */
    public synchronized void removeDiscovery(String discoveryPrefix) {
        if (client == null || !client.isConnected()) return;
        try {
            String topic = HomeAssistantDiscovery.deviceConfigTopic(discoveryPrefix, deviceId);
            MqttMessage message = new MqttMessage(new byte[0]);
            message.setRetained(true);
            message.setQos(1);
            client.publish(topic, message);
            logger.info("Removed HA discovery at " + topic);
        } catch (MqttException e) {
            logger.warn("HA discovery remove failed: " + e.getMessage());
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
        // The only thing we subscribe to is HA's birth/status topic. When HA comes
        // back online, re-announce discovery and force a full state resend so its
        // entities repopulate immediately instead of waiting for the next change.
        if (config.isHomeAssistant() && topic != null
                && topic.equals(HomeAssistantDiscovery.statusTopic(config.discoveryPrefix))) {
            String payload = new String(message.getPayload()).trim();
            if ("online".equalsIgnoreCase(payload)) {
                logger.info("Home Assistant birth received — re-announcing discovery");
                discoveryAnnounced = false;
                // Defer the differ.reset() to the publish thread — do NOT touch
                // the differ from this Paho callback thread (it isn't thread-safe).
                forceFullResend = true;
            }
            return;
        }

        // Inbound automation trigger: <base>/automation/<channel>. An external broker
        // message (Home Assistant, Node-RED, …) becomes an automation signal keyed by
        // channel, so a rule can fire on "HA published X to channel Y". Enqueue-only —
        // Automations.publishMqttTrigger does an atomic map CAS + queues to the automation
        // worker, so it's safe and non-blocking on this Paho callback thread (never runs
        // action logic here). Inert at zero cost when no automation watches the channel.
        String autoPrefix = config.topic + "/automation/";
        if (topic != null && topic.startsWith(autoPrefix)) {
            String channel = topic.substring(autoPrefix.length());
            String payload = new String(message.getPayload());
            try {
                com.overdrive.app.automation.Automations.publishMqttTrigger(channel, payload,
                        message.isRetained());
            } catch (Throwable t) {
                logger.warn("Inbound MQTT automation trigger failed: " + t.getMessage());
            }
            return;
        }

        // Inbound vehicle-control command: <base>/<key>/set or <base>/<key>/<sub>/set.
        if (config.isControlEnabled() && topic != null
                && topic.startsWith(config.topic + "/") && topic.endsWith("/set")) {
            // NEVER execute a RETAINED command. Command topics must not be retained,
            // but if any client ever publishes one with retain=true (misconfigured HA
            // automation, manual `mosquitto_pub -r`), the broker replays it on EVERY
            // reconnect — and this connection reconnects at each network handoff /
            // proxy flap. A retained "OPEN" on <base>/tailgate/set would physically
            // open the tailgate after every reconnect. The automation branch above
            // stores a retained replay without firing; vehicle control has no such
            // store-only mode, so drop retained messages outright. Live commands
            // (retained=false) are unaffected.
            if (message.isRetained()) {
                logger.warn("Ignoring RETAINED control command on " + topic
                        + " — command topics must not be retained (clear it with an"
                        + " empty retained publish)");
                return;
            }
            String inner = topic.substring(config.topic.length() + 1, topic.length() - "/set".length());
            String key, sub;
            int slash = inner.indexOf('/');
            if (slash >= 0) { key = inner.substring(0, slash); sub = inner.substring(slash + 1); }
            else { key = inner; sub = null; }
            String payload = new String(message.getPayload());
            // ensureCommandRouter() is synchronized and returns the (volatile)
            // router so we don't race on the field read against disconnect().
            MqttCommandRouter router = ensureCommandRouter();
            if (router != null) router.handle(key, sub, payload);
        }
    }

    private MqttCommandRouter ensureCommandRouter() {
        // Runs on the Paho callback thread. Deliberately NOT synchronized on the
        // instance monitor: a scheduler thread can hold that lock for seconds inside
        // a blocking connect()/disconnect(), and stalling the callback thread here
        // delayed HA birth handling, automation triggers, and control commands (and
        // set up a bounded standoff with disconnect(5000)'s callback quiesce).
        // Fast path: the volatile read suffices once the router exists.
        MqttCommandRouter router = commandRouter;
        if (router != null) return router;
        synchronized (routerLock) {
            // Don't resurrect a router for a connection that's shutting down — a late
            // inbound message racing disconnect() would otherwise leak a new executor.
            // disconnect() sets running=false BEFORE taking routerLock, so whichever
            // side enters the lock second observes the other's effect.
            if (!running) return null;
            if (commandRouter == null) {
                commandRouter = new MqttCommandRouter(config.id,
                        this::publishVerifiedControlState);
            }
            return commandRouter;
        }
    }

    /** Publish a post-command readback and remember any retained charge-cap state it confirms. */
    private synchronized void publishVerifiedControlState(String key, String value) {
        if (publishString(config.topic + "/" + key, value, true, config.qos)) {
            recordChargeCapStatePublished(key);
        }
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
            // Single read of the volatile client — disconnect()/connect() can null or
            // swap the field concurrently (this runs unsynchronized on IPC threads),
            // and a second read after the null check could NPE. The NPE was previously
            // swallowed by the catch below, silently truncating the whole status blob.
            MqttClient c = client;
            status.put("id", config.id);
            status.put("name", config.name);
            status.put("connected", connected && c != null && c.isConnected());
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
            status.put("proxyPort", ProxyHelper.getProxyPort());
        } catch (Exception ignored) {}
        return status;
    }

    /**
     * Calculate backoff delay for reconnection.
     */
    public long getBackoffSeconds() {
        if (consecutiveFailures <= 0) return 0;
        long backoff = BACKOFF_BASE_SECONDS * (1L << Math.min(consecutiveFailures - 1, 10));
        return Math.min(backoff, lastFailureRefused ? BACKOFF_CAP_SECONDS : BACKOFF_NETWORK_CAP_SECONDS);
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
    /** Single volatile read into a local — see the note on {@link #client}. */
    public boolean isConnected() {
        MqttClient c = client;
        return connected && c != null && c.isConnected();
    }
    public boolean isRunning() { return running; }
    public long getTotalPublishes() { return totalPublishes; }
    public long getFailedPublishes() { return failedPublishes; }
    public long getLastPublishTime() { return lastPublishTime; }
    public String getLastError() { return lastError; }
}
