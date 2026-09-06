package com.overdrive.app.byd.cloud;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Singleton that owns the cloud vehicle data snapshot and notifies
 * listeners on lock state changes. Fed by BydCloudMqttSubscriber.
 */
public final class BydCloudDataProvider {

    private static final String TAG = "CloudDataProvider";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static volatile BydCloudDataProvider instance;

    private final AtomicReference<VehicleCloudSnapshot> snapshot = new AtomicReference<>();
    private final Object snapshotPublishLock = new Object();
    private final AtomicLong vehicleInfoUpdateSequence = new AtomicLong();
    /** Guarded by {@link #snapshotPublishLock}. */
    private long publishedVehicleInfoUpdateSequence;
    /** Guarded by {@link #snapshotPublishLock}; every sequence at or below this began pre-reset. */
    private long resetVehicleInfoUpdateSequence;
    /** Guarded by {@link #snapshotPublishLock}; retained across timestamp-less publications. */
    private long maximumVehicleInfoTimestamp;
    private final CopyOnWriteArrayList<CloudLockStateListener> lockListeners = new CopyOnWriteArrayList<>();

    // Track previous lock state to detect transitions
    private volatile boolean lastKnownLocked = false;
    private volatile boolean lastKnownValid = false;
    private volatile long totalMessagesReceived = 0;
    private volatile long lastMessageReceivedAt = 0;
    private volatile boolean mqttConnected = false;

    private BydCloudDataProvider() {}

    public static BydCloudDataProvider getInstance() {
        if (instance == null) {
            synchronized (BydCloudDataProvider.class) {
                if (instance == null) instance = new BydCloudDataProvider();
            }
        }
        return instance;
    }

    // ── Listener interface ──────────────────────────────────────────────

    public interface CloudLockStateListener {
        void onCloudLockStateChanged(boolean locked, long timestampMs);
    }

    public void addLockStateListener(CloudLockStateListener listener) {
        if (listener == null || lockListeners.contains(listener)) return;
        lockListeners.add(listener);

        // Immediate replay of current valid snapshot. Edge tracking
        // (lastKnownLocked/lastKnownValid) persists across ACC cycles within
        // the daemon's process lifetime; without this replay, a listener that
        // attaches AFTER the last edge fired gets nothing until the next
        // transition — which means a fresh sentry cycle never arms when the
        // car was already locked from the previous park.
        //
        // CRITICAL ordering: read snapshot AFTER `lockListeners.add` so we
        // can't miss an edge that fires on a different thread between add
        // and snapshot.get(). updateFromVehicleInfo iterates listeners via
        // CopyOnWriteArrayList — its iterator is snapshotted at iterator()
        // time, so a fire that started just before our add will skip us;
        // re-reading our snapshot here delivers whatever edge they wrote.
        // Worst case: same edge fires twice — applyLockEvent in CameraDaemon
        // is idempotent so duplicate fires are safe.
        VehicleCloudSnapshot s = snapshot.get();
        if (s != null && s.isLockStateFresh() && s.hasValidLockState()) {
            if (s.isAllLocked()) {
                try { listener.onCloudLockStateChanged(true, s.receivedAt); }
                catch (Exception e) { logger.warn("Lock listener replay error: " + e.getMessage()); }
            } else if (s.isAnyUnlocked()) {
                try { listener.onCloudLockStateChanged(false, s.receivedAt); }
                catch (Exception e) { logger.warn("Lock listener replay error: " + e.getMessage()); }
            }
        }
    }

    public void removeLockStateListener(CloudLockStateListener listener) {
        lockListeners.remove(listener);
    }

    // ── Snapshot access ─────────────────────────────────────────────────

    /**
     * Returns the current cloud snapshot, or {@code null} if the user has
     * cloud telemetry merge switched off.
     *
     * <p>This is the single accessor nearly every caller across the app uses
     * to read cloud vehicle data (doors, windows, trunk, sunroof, steering
     * heat, battery-heat/preconditioning, charging status/time-to-full,
     * etc.). Several of those call sites were reading cloud values
     * unconditionally as a "local had nothing" fallback, without checking
     * the cloudDataMerge setting first — meaning cloud data could still
     * reach the UI even with the switch off. Gating it here, once, means
     * every existing and future {@code getSnapshot()} caller automatically
     * respects the switch without having to remember to check it
     * individually — the ingestion side (MQTT subscriber /
     * updateFromVehicleInfo) is untouched, so the snapshot keeps updating in
     * the background; it's just never handed out while the switch is off.
     */
    public VehicleCloudSnapshot getSnapshot() {
        if (!isCloudMergeEnabled()) return null;
        return snapshot.get();
    }

    private static boolean isCloudMergeEnabled() {
        try {
            return BydCloudConfig.fromUnifiedConfig().cloudDataMerge;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isConnectionHealthy() {
        VehicleCloudSnapshot s = snapshot.get();
        return s != null && s.isConnectionHealthy();
    }

    public boolean isLockStateFresh() {
        VehicleCloudSnapshot s = snapshot.get();
        return s != null && s.isLockStateFresh() && s.hasValidLockState();
    }

    public boolean isTelemetryFresh() {
        VehicleCloudSnapshot s = snapshot.get();
        return s != null && s.isTelemetryFresh();
    }

    // ── Data ingestion ──────────────────────────────────────────────────

    /**
     * Called by BydCloudMqttSubscriber when a new vehicleInfo message arrives.
     * Parses the JSON, updates the snapshot, and fires lock state listeners
     * if the lock state changed.
     */
    public void updateFromVehicleInfo(JSONObject vehicleInfo) {
        updateFromVehicleInfo(vehicleInfo, null);
    }

    public void updateFromVehicleInfo(JSONObject vehicleInfo, JSONObject hvac) {
        updateFromVehicleInfo(vehicleInfo, hvac, beginVehicleInfoUpdate());
    }

    long beginVehicleInfoUpdate() {
        return vehicleInfoUpdateSequence.incrementAndGet();
    }

    void updateFromVehicleInfo(
            JSONObject vehicleInfo, JSONObject hvac, long updateSequence) {
        if (vehicleInfo == null) return;

        VehicleCloudSnapshot.Builder nextBuilder =
                VehicleCloudSnapshot.fromVehicleInfo(vehicleInfo, hvac);
        VehicleCloudSnapshot next = nextBuilder.build();
        synchronized (snapshotPublishLock) {
            VehicleCloudSnapshot current = snapshot.get();
            long incomingTimestamp = next.vehicleInfoTimestamp;
            boolean timestamped = incomingTimestamp > 0;
            boolean preResetResponse = updateSequence <= resetVehicleInfoUpdateSequence;
            boolean timestampRegression = timestamped
                    && maximumVehicleInfoTimestamp > 0
                    && incomingTimestamp < maximumVehicleInfoTimestamp;
            // A timestamp-less cabin value is observed at receipt time. An explicit source time at
            // or before that observation is stale even when it advances the historical explicit
            // maximum or belongs to a request with a newer sequence.
            boolean staleAcrossTimestampLessBoundary = timestamped
                    && current != null
                    && current.vehicleInfoTimestamp == 0L
                    && current.hasInsideTemp()
                    && current.insideTempObservedAt > 0L
                    && incomingTimestamp <= current.insideTempObservedAt;
            // A strictly newer source timestamp is authoritative even when its request started
            // earlier. Missing timestamps, equal timestamps against the current timestamped
            // snapshot, and the first explicit timestamp are ordered by request generation.
            boolean sequenceRegression = (!timestamped
                    || maximumVehicleInfoTimestamp == 0
                    || incomingTimestamp == maximumVehicleInfoTimestamp)
                    && updateSequence <= publishedVehicleInfoUpdateSequence;
            if (preResetResponse || timestampRegression || staleAcrossTimestampLessBoundary
                    || sequenceRegression) {
                logger.debug("Ignoring out-of-order vehicleInfo: incoming="
                        + next.vehicleInfoTimestamp + "/" + updateSequence + " current="
                        + (current != null ? current.vehicleInfoTimestamp : 0L)
                        + "/" + publishedVehicleInfoUpdateSequence + " maxTimestamp="
                        + maximumVehicleInfoTimestamp + " resetSequence="
                        + resetVehicleInfoUpdateSequence);
                return;
            }

            // Preserve age only when both snapshots actually came from the same explicit source
            // observation. maximumVehicleInfoTimestamp survives timestamp-less publications and
            // therefore cannot establish that identity.
            boolean repeatedSourceTimestamp = incomingTimestamp > 0L
                    && current != null
                    && incomingTimestamp == current.vehicleInfoTimestamp;
            if (current != null && current.insideTempObservedAt > 0L
                    && next.hasInsideTemp()
                    && repeatedSourceTimestamp) {
                nextBuilder.insideTempObservedAt(current.insideTempObservedAt);
                next = nextBuilder.build();
            }

            snapshot.set(next);
            publishedVehicleInfoUpdateSequence =
                    Math.max(publishedVehicleInfoUpdateSequence, updateSequence);
            if (timestamped) {
                maximumVehicleInfoTimestamp =
                        Math.max(maximumVehicleInfoTimestamp, incomingTimestamp);
            }
            totalMessagesReceived++;
            lastMessageReceivedAt = System.currentTimeMillis();

            // Detect and deliver lock transitions under the same ordering lock as publication.
            if (next.hasValidLockState()) {
                boolean nowLocked = next.isAllLocked();
                boolean nowUnlocked = next.isAnyUnlocked();

                if (nowLocked && (!lastKnownValid || !lastKnownLocked)) {
                    lastKnownLocked = true;
                    lastKnownValid = true;
                    logger.info("Cloud lock state: LOCKED");
                    fireLockStateChanged(true, next.receivedAt);
                } else if (nowUnlocked && (!lastKnownValid || lastKnownLocked)) {
                    lastKnownLocked = false;
                    lastKnownValid = true;
                    logger.info("Cloud lock state: UNLOCKED");
                    fireLockStateChanged(false, next.receivedAt);
                }
            }
        }
    }

    private void fireLockStateChanged(boolean locked, long timestampMs) {
        for (CloudLockStateListener listener : lockListeners) {
            try {
                listener.onCloudLockStateChanged(locked, timestampMs);
            } catch (Exception e) {
                logger.warn("Lock listener error: " + e.getMessage());
            }
        }
    }

    /**
     * Reset state (credential change, disconnect).
     */
    public void reset() {
        stopSubscriber();
        synchronized (snapshotPublishLock) {
            snapshot.set(null);
            long resetSequence = vehicleInfoUpdateSequence.incrementAndGet();
            resetVehicleInfoUpdateSequence = resetSequence;
            publishedVehicleInfoUpdateSequence = resetSequence;
            maximumVehicleInfoTimestamp = 0L;
            lastKnownLocked = false;
            lastKnownValid = false;
        }
        mqttConnected = false;
    }

    // ── Subscriber lifecycle ────────────────────────────────────────────

    private volatile BydCloudMqttSubscriber subscriber;

    /**
     * Single canonical BydCloudClient shared across MQTT subscriber, REST
     * realtime poller, and on-demand refresh. Each client instance holds its
     * own session; multiple instances racing to log in invalidate each other's
     * sessionToken and produce code=1005 from /app/emqAuth/getEmqBrokerIp.
     * The synchronized login() inside BydCloudClient only protects against
     * concurrent calls *on the same instance*, so we must share one.
     */
    private volatile BydCloudClient sharedClient;

    /**
     * Start the MQTT subscriber if BYD Cloud credentials are configured and verified.
     * Safe to call multiple times — no-ops if already running.
     */
    public void startSubscriberIfConfigured() {
        if (subscriber != null) return;

        try {
            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            if (!config.isVerified()) return;

            BydCloudClient client = ensureSharedClient(config);
            if (client == null) return;

            subscriber = new BydCloudMqttSubscriber(client);
            subscriber.start();
            logger.info("Cloud MQTT subscriber started");

            // Start REST poller if toggle is on
            syncPollerState();
        } catch (Exception e) {
            logger.warn("Failed to start cloud subscriber: " + e.getMessage());
        }
    }

    public void stopSubscriber() {
        // Null the field into a local BEFORE calling stop(). If stop() (or
        // anything it triggers) ever re-enters reset()/stopSubscriber(), the
        // field is already null so the nested call no-ops instead of recursing
        // on the same instance. This is the structural guard against the
        // reentrant-teardown StackOverflow (see BydCloudMqttSubscriber.stop()).
        BydCloudMqttSubscriber s = subscriber;
        subscriber = null;
        if (s != null) {
            s.stop();
        }
        stopRealtimePoller();
        sharedClient = null;
        mqttConnected = false;
    }

    /**
     * Public entry point for any caller that needs a logged-in BydCloudClient.
     * Returns the shared singleton — same instance used by MQTT subscriber and
     * REST poller, so no duplicate logins. Returns null if not configured /
     * Bangcle tables unavailable.
     */
    public BydCloudClient getSharedClient() {
        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        if (!config.isVerified()) return null;
        return ensureSharedClient(config);
    }

    /**
     * Lazily build (or rebuild) the shared client. Returns null if Bangcle
     * tables aren't available. Safe to call from multiple threads — the first
     * caller initializes, subsequent callers see the existing instance.
     */
    private synchronized BydCloudClient ensureSharedClient(BydCloudConfig config) {
        if (sharedClient != null && sharedClient.isReady()) return sharedClient;
        try {
            BydCloudClient client = new BydCloudClient(config);
            java.io.InputStream tables = loadTables(config);
            if (tables == null) {
                logger.warn("Cannot create cloud client: transport tables not available");
                return null;
            }
            try {
                client.init(tables);
            } finally {
                try { tables.close(); } catch (Exception ignored) {}
            }
            sharedClient = client;
            return client;
        } catch (Exception e) {
            logger.warn("Failed to create shared cloud client: " + e.getMessage());
            return null;
        }
    }

    // ── REST Realtime Poller (toggle-gated) ─────────────────────────────

    private volatile java.util.concurrent.ScheduledExecutorService realtimePoller;
    private static final long POLL_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes

    // ── On-demand refresh (page-load fallback) ──────────────────────────
    private volatile long lastOnDemandRefreshMs = 0;
    private static final long ON_DEMAND_REFRESH_COOLDOWN_MS = 30 * 1000;

    /**
     * Start or stop the REST realtime poller based on the cloudDataMerge toggle.
     * Called on toggle change and on subscriber start.
     */
    public void syncPollerState() {
        BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
        if (config.cloudDataMerge && config.isVerified()) {
            startRealtimePoller(config.vin);
        } else {
            stopRealtimePoller();
        }
    }

    private void startRealtimePoller(String vin) {
        if (realtimePoller != null) return; // already running
        if (vin == null || vin.isEmpty()) return;

        logger.info("Starting REST realtime poller (every 5 min)");
        realtimePoller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CloudRealtimePoll");
            t.setDaemon(true);
            return t;
        });

        // Initial fetch immediately, then every 5 minutes
        final String pollVin = vin;
        realtimePoller.scheduleAtFixedRate(() -> {
            try {
                long updateSequence = vehicleInfoUpdateSequence.incrementAndGet();
                BydCloudConfig cfg = BydCloudConfig.fromUnifiedConfig();
                if (!cfg.cloudDataMerge) {
                    logger.info("Cloud data merge disabled — stopping poller");
                    stopRealtimePoller();
                    return;
                }

                BydCloudClient client = getOrCreateClient();
                if (client == null) return;

                JSONObject vehicleInfo = client.fetchVehicleRealtime(pollVin);
                if (vehicleInfo != null) {
                    updateFromVehicleInfo(vehicleInfo, null, updateSequence);
                    logger.info("REST realtime poll: data updated");
                }
            } catch (Exception e) {
                logger.warn("REST realtime poll failed: " + e.getMessage());
            }
        }, 0, POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void stopRealtimePoller() {
        if (realtimePoller != null) {
            realtimePoller.shutdownNow();
            realtimePoller = null;
            logger.info("REST realtime poller stopped");
        }
    }

    /**
     * On-demand REST poll triggered by the UI (e.g., when the vehicle-control
     * page loads). Only fires if our cached lock state is stale or missing,
     * and is globally rate-limited so opening the page rapidly can't hammer
     * the cloud API.
     *
     * Returns true if a fresh fetch was actually performed.
     */
    public boolean refreshLockStateIfStale() {
        long now = System.currentTimeMillis();

        // Already fresh? Nothing to do.
        VehicleCloudSnapshot s = snapshot.get();
        if (s != null && s.isLockStateFresh() && s.hasValidLockState()) {
            return false;
        }

        // Cooldown — don't let a navigation loop spam BYD's cloud.
        if (now - lastOnDemandRefreshMs < ON_DEMAND_REFRESH_COOLDOWN_MS) {
            return false;
        }
        lastOnDemandRefreshMs = now;

        try {
            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            if (!config.isVerified() || config.vin == null || config.vin.isEmpty()) {
                return false;
            }

            BydCloudClient client = getOrCreateClient();
            if (client == null) return false;

            long updateSequence = vehicleInfoUpdateSequence.incrementAndGet();
            JSONObject vehicleInfo = client.fetchVehicleRealtime(config.vin);
            if (vehicleInfo != null) {
                // Diagnostic: log door/lock fields so we can confirm the
                // poll actually delivered them.  pyBYD/BYD-re reports the
                // field names as leftFrontDoorLock, rightFrontDoorLock etc.
                // with values 1=UNLOCKED 2=LOCKED.
                logger.info("Realtime locks: lf=" + vehicleInfo.opt("leftFrontDoorLock")
                    + " rf=" + vehicleInfo.opt("rightFrontDoorLock")
                    + " lr=" + vehicleInfo.opt("leftRearDoorLock")
                    + " rr=" + vehicleInfo.opt("rightRearDoorLock")
                    + " online=" + vehicleInfo.opt("onlineState"));
                updateFromVehicleInfo(vehicleInfo, null, updateSequence);
                logger.info("On-demand lock-state refresh: data updated");
                return true;
            }
        } catch (Exception e) {
            logger.warn("On-demand lock-state refresh failed: " + e.getMessage());
        }
        return false;
    }

    private BydCloudClient getOrCreateClient() {
        // Always return the shared client used by the MQTT subscriber.
        // Creating separate instances causes them to race on login() and
        // invalidate each other's session tokens, producing code=1005 on
        // /app/emqAuth/getEmqBrokerIp.
        try {
            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            if (!config.isVerified()) return null;
            return ensureSharedClient(config);
        } catch (Exception e) {
            logger.warn("Failed to obtain shared cloud client: " + e.getMessage());
            return null;
        }
    }

    private java.io.InputStream loadTables(BydCloudConfig config) {
        return com.overdrive.app.byd.cloud.crypto.EnvelopeCodecFactory.openTablesStream(
                config.isChinaRegion(),
                com.overdrive.app.daemon.DaemonBootstrap.getContext());
    }

    // ── MQTT connection state (set by subscriber) ───────────────────────

    public void setMqttConnected(boolean connected) {
        this.mqttConnected = connected;
    }

    public boolean isMqttConnected() {
        return mqttConnected;
    }

    public long getTotalMessagesReceived() {
        return totalMessagesReceived;
    }

    public long getLastMessageReceivedAt() {
        return lastMessageReceivedAt;
    }

    /**
     * Build a status JSON for the API response.
     */
    public JSONObject getStatusJson() {
        JSONObject status = new JSONObject();
        try {
            VehicleCloudSnapshot s = snapshot.get();
            boolean hasData = lastMessageReceivedAt > 0;

            status.put("connected", mqttConnected || (realtimePoller != null));
            status.put("mqttConnected", mqttConnected);
            status.put("pollingActive", realtimePoller != null);
            status.put("totalMessages", totalMessagesReceived);

            if (hasData) {
                long ageSec = (System.currentTimeMillis() - lastMessageReceivedAt) / 1000;
                status.put("lastMessageAge", ageSec);
            } else {
                status.put("lastMessageAge", -1);
            }

            if (s != null && s.hasValidLockState()) {
                status.put("lockState", s.isAllLocked() ? "locked"
                        : s.isAnyUnlocked() ? "unlocked" : "unknown");
            } else {
                status.put("lockState", "unknown");
            }

            if (s != null) {
                status.put("onlineState", s.onlineState == VehicleCloudSnapshot.ONLINE ? "online"
                        : s.onlineState == VehicleCloudSnapshot.OFFLINE ? "offline" : "unknown");
                if (s.hasSoc()) status.put("socPercent", s.socPercent);
                if (s.hasChargingState()) {
                    switch (s.chargingState) {
                        case 0: status.put("chargingState", "not_charging"); break;
                        case 1: status.put("chargingState", "charging"); break;
                        case 15: status.put("chargingState", "not_charging"); break; // 15 is unreliable — does not reflect actual plug state
                        default: break; // don't report unknown states
                    }
                }
                if (s.hasElecRange()) status.put("rangeKm", s.elecRangeKm);
                if (s.hasFreshInsideTemp()) status.put("insideTempC", s.insideTempC);
            }

            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            status.put("cloudDataMerge", config.cloudDataMerge);
        } catch (Exception ignored) {}
        return status;
    }
}
