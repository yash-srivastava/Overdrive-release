package com.overdrive.app.byd.cloud;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

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
    private final CopyOnWriteArrayList<CloudSnapshotListener> snapshotListeners =
            new CopyOnWriteArrayList<>();

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

    /** Fired after every accepted vehicleInfo publication; read {@link #getSnapshot()} for it. */
    public interface CloudSnapshotListener {
        void onCloudSnapshotUpdated();
    }

    public void addSnapshotListener(CloudSnapshotListener listener) {
        if (listener != null) snapshotListeners.addIfAbsent(listener);
    }

    // ── Snapshot access ─────────────────────────────────────────────────

    public VehicleCloudSnapshot getSnapshot() {
        return snapshot.get();
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
        // Outside the publish lock: listeners may take their own locks.
        for (CloudSnapshotListener listener : snapshotListeners) {
            try {
                listener.onCloudSnapshotUpdated();
            } catch (Exception e) {
                logger.warn("Snapshot listener error: " + e.getMessage());
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
     * Serializes vehicle-realtime request/poll transactions. A transaction can
     * take up to ~15 seconds and mutates the shared client's session/request
     * state, so the regular five-minute poller, UI refresh, and parked DI5
     * heartbeat must never overlap each other.
     */
    private final ReentrantLock realtimeRequestLock = new ReentrantLock();

    // ── DiLink 5 parked cloud keep-alive ────────────────────────────────

    /** Maximum start-to-start gap between parked T-Box wake requests. */
    static final long DI5_PARKED_KEEPALIVE_INTERVAL_MS = 15_000L;
    /** Field diagnostic threshold; small allowance for normal scheduler jitter. */
    static final long DI5_PARKED_KEEPALIVE_DEADLINE_MS = 16_000L;
    /** Prompt lock retry when another cloud consumer briefly owns the shared client. */
    static final long DI5_PARKED_KEEPALIVE_LOCK_RETRY_MS = 500L;
    /** Failed wake calls retry sooner than the normal 15-second cadence. */
    static final long DI5_PARKED_KEEPALIVE_RETRY_FIRST_MS = 1_000L;
    static final long DI5_PARKED_KEEPALIVE_RETRY_SECOND_MS = 3_000L;
    static final long DI5_PARKED_KEEPALIVE_RETRY_MAX_MS = 5_000L;

    private final Object di5ParkedKeepAliveLock = new Object();
    /** Latest CameraDaemon ACC generation observed by reconcile(). */
    private long di5ParkedKeepAliveGeneration = -1L;
    /** Unique lease; changes on every start/stop, including same-generation toggles. */
    private long di5ParkedKeepAliveLease;
    private boolean di5ParkedKeepAliveShutdown;
    private ScheduledExecutorService di5ParkedKeepAliveExecutor;
    private ScheduledFuture<?> di5ParkedKeepAliveFuture;
    private Thread di5ParkedKeepAliveWorker;
    private BydCloudClient di5ParkedKeepAliveClient;
    private volatile long di5ParkedKeepAliveLastAttemptAt;
    private volatile long di5ParkedKeepAliveLastAttemptElapsedNanos;
    private volatile long di5ParkedKeepAliveLastSuccessAt;
    private volatile long di5ParkedKeepAliveLastRequestDurationMs = -1L;
    private volatile long di5ParkedKeepAliveRetryDelayMs;
    private volatile long di5ParkedKeepAliveLateWarningAttemptNanos =
            Long.MIN_VALUE;
    private volatile int di5ParkedKeepAliveDeadlineMisses;
    private volatile int di5ParkedKeepAliveConsecutiveFailures;

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
        // Credential reset/clear invalidates the shared client used by the
        // parked heartbeat too. Cancel its schedule and any active HTTP call
        // before dropping that client reference.
        stopDi5ParkedKeepAlive("cloud runtime reset");
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

    /**
     * Reconcile the parked BYD-cloud heartbeat against an authoritative
     * CameraDaemon ACC generation.
     *
     * <p>Generation ordering closes the OFF/ON race: if a stale OFF worker
     * reaches this method after a newer ON transition already stopped the
     * heartbeat, its lower generation is ignored and cannot resurrect it.
     */
    public void reconcileDi5ParkedKeepAlive(
            long generation, boolean desired, String reason) {
        if (generation < 0L) {
            if (!desired) stopDi5ParkedKeepAlive(reason);
            return;
        }

        boolean eligible = desired;
        String ineligibleReason = reason;
        BydCloudConfig config = null;
        if (eligible) {
            try {
                if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                    eligible = false;
                    ineligibleReason = "DiLink 5 is not selected";
                } else if (com.overdrive.app.config.UnifiedConfigManager
                        .isVehicleOnOnlyMode()) {
                    eligible = false;
                    ineligibleReason = "On Only mode";
                } else if (!com.overdrive.app.config.UnifiedConfigManager
                        .isDi5CloudKeepAliveEnabled()) {
                    eligible = false;
                    ineligibleReason = "setting disabled";
                } else {
                    config = BydCloudConfig.fromUnifiedConfig();
                    if (!config.isVerified()
                            || config.vin == null
                            || config.vin.isEmpty()) {
                        eligible = false;
                        ineligibleReason = "BYD Cloud account is not verified";
                    }
                }
            } catch (Throwable t) {
                eligible = false;
                ineligibleReason = "eligibility check failed: " + t.getMessage();
            }
        }

        KeepAliveStopHandle previous = null;
        boolean started = false;
        Throwable startFailure = null;
        synchronized (di5ParkedKeepAliveLock) {
            if (di5ParkedKeepAliveShutdown) return;
            if (generation < di5ParkedKeepAliveGeneration) {
                logger.debug("Ignoring stale DI5 cloud keep-alive reconcile gen="
                        + generation + " current=" + di5ParkedKeepAliveGeneration);
                return;
            }
            di5ParkedKeepAliveGeneration = generation;

            if (!eligible) {
                previous = detachDi5ParkedKeepAliveLocked();
            } else if (di5ParkedKeepAliveExecutor != null
                    && !di5ParkedKeepAliveExecutor.isShutdown()) {
                return;
            } else {
                previous = detachDi5ParkedKeepAliveLocked();
                resetDi5ParkedKeepAliveMetricsLocked();
                final long lease = ++di5ParkedKeepAliveLease;
                final long taskGeneration = generation;
                ScheduledExecutorService executor =
                        java.util.concurrent.Executors
                                .newSingleThreadScheduledExecutor(r -> {
                                    Thread t = new Thread(
                                            r, "Di5CloudKeepAlive");
                                    // Explicit shutdown still cancels this
                                    // worker; daemon=true is the final guard
                                    // against holding a terminating process.
                                    t.setDaemon(true);
                                    return t;
                                });
                di5ParkedKeepAliveExecutor = executor;
                try {
                    scheduleDi5ParkedKeepAliveLocked(
                            taskGeneration, lease, 0L);
                    started = true;
                } catch (Throwable t) {
                    startFailure = t;
                    di5ParkedKeepAliveFuture = null;
                    di5ParkedKeepAliveExecutor = null;
                    executor.shutdownNow();
                }
            }
        }

        cancelDi5ParkedKeepAlive(previous);
        if (startFailure != null) {
            logger.warn("Failed to start DI5 cloud keep-alive: "
                    + startFailure.getMessage());
        } else if (started) {
            logger.info("DI5 cloud keep-alive started (ACC gen=" + generation
                    + ", requestInterval=15s, retries=1/3/5s)");
        } else if (previous != null) {
            logger.info("DI5 cloud keep-alive stopped ("
                    + (ineligibleReason == null ? "not desired" : ineligibleReason)
                    + ")");
        }
    }

    /** Stop the parked heartbeat without permanently closing this provider. */
    public void stopDi5ParkedKeepAlive(String reason) {
        KeepAliveStopHandle handle;
        synchronized (di5ParkedKeepAliveLock) {
            handle = detachDi5ParkedKeepAliveLocked();
        }
        cancelDi5ParkedKeepAlive(handle);
        if (handle != null) {
            logger.info("DI5 cloud keep-alive stopped ("
                    + (reason == null ? "requested" : reason) + ")");
        }
    }

    /**
     * Permanent process-lifecycle stop. Once called, no racing ACC/config
     * callback can restart the heartbeat while CameraDaemon is shutting down.
     */
    public void shutdownDi5ParkedKeepAlive(String reason) {
        KeepAliveStopHandle handle;
        synchronized (di5ParkedKeepAliveLock) {
            di5ParkedKeepAliveShutdown = true;
            handle = detachDi5ParkedKeepAliveLocked();
        }
        cancelDi5ParkedKeepAlive(handle);
        if (handle != null) {
            logger.info("DI5 cloud keep-alive shutdown ("
                    + (reason == null ? "daemon shutdown" : reason) + ")");
        }
    }

    private void runDi5ParkedKeepAlive(long generation, long lease) {
        try {
            if (!isDi5ParkedKeepAliveLeaseActive(generation, lease)) return;

            // Defense in depth: lifecycle callers stop us on ACC ON/config
            // changes, but re-read the inexpensive authoritative gates before
            // every network request so a missed callback cannot spend data.
            if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                    || com.overdrive.app.config.UnifiedConfigManager
                            .isVehicleOnOnlyMode()
                    || !com.overdrive.app.config.UnifiedConfigManager
                            .isDi5CloudKeepAliveEnabled()
                    || !com.overdrive.app.monitor.AccMonitor
                            .isAccStateAuthoritative()
                    || com.overdrive.app.monitor.AccMonitor.isAccOn()) {
                reconcileDi5ParkedKeepAlive(
                        generation, false, "runtime eligibility changed");
                return;
            }

            BydCloudConfig config = BydCloudConfig.fromUnifiedConfig();
            if (!config.isVerified()
                    || config.vin == null
                    || config.vin.isEmpty()) {
                reconcileDi5ParkedKeepAlive(
                        generation, false, "BYD Cloud account unavailable");
                return;
            }

            long nowNanos = System.nanoTime();

            // A five-minute merge poll or page-load refresh may briefly own
            // the shared request lane. Retry promptly rather than dropping
            // this 15-second heartbeat slot.
            if (!realtimeRequestLock.tryLock()) {
                long previousAttemptNanos =
                        di5ParkedKeepAliveLastAttemptElapsedNanos;
                warnIfDi5ParkedKeepAliveLate(
                        previousAttemptNanos, nowNanos,
                        "vehicle realtime request already in flight");
                logger.debug("DI5 cloud keep-alive tick skipped: "
                        + "vehicle realtime request already in flight; "
                        + "retrying in "
                        + DI5_PARKED_KEEPALIVE_LOCK_RETRY_MS + "ms");
                scheduleDi5ParkedKeepAlive(
                        generation,
                        lease,
                        DI5_PARKED_KEEPALIVE_LOCK_RETRY_MS);
                return;
            }

            KeepAliveAttempt attempt = null;
            try {
                attempt = beginDi5ParkedKeepAliveAttempt(
                        generation, lease);
                if (attempt == null) return;

                warnIfDi5ParkedKeepAliveLate(
                        attempt.previousAttemptNanos,
                        attempt.startedNanos,
                        "scheduler/request-lock delay");
                logger.info("DI5 cloud keep-alive request starting (ACC gen="
                        + generation + ", gap="
                        + (attempt.previousAttemptNanos == 0L
                                ? "first"
                                : attempt.gapMs + "ms")
                        + ")");

                BydCloudClient client = getOrCreateClient();
                if (client == null) {
                    recordDi5ParkedKeepAliveFailure(
                            generation,
                            lease,
                            attempt.startedNanos,
                            "shared cloud client unavailable");
                    return;
                }
                if (!attachDi5ParkedKeepAliveClient(
                        generation, lease, client)) {
                    return;
                }

                long updateSequence =
                        vehicleInfoUpdateSequence.incrementAndGet();
                JSONObject vehicleInfo =
                        client.fetchVehicleRealtimeForParkedKeepAlive(
                                config.vin);

                if (!isDi5ParkedKeepAliveLeaseActive(generation, lease)) {
                    return;
                }
                if (vehicleInfo != null) {
                    updateFromVehicleInfo(
                            vehicleInfo, null, updateSequence);
                }
                di5ParkedKeepAliveLastSuccessAt =
                        System.currentTimeMillis();
                di5ParkedKeepAliveLastRequestDurationMs =
                        elapsedMillis(
                                attempt.startedNanos,
                                System.nanoTime());
                recordDi5ParkedKeepAliveSuccess(
                        generation,
                        lease,
                        attempt.startedNanos,
                        System.nanoTime());
                logger.info("DI5 cloud keep-alive request completed in "
                        + di5ParkedKeepAliveLastRequestDurationMs + "ms"
                        + (vehicleInfo == null
                                ? " (no realtime payload yet)" : ""));
            } catch (Throwable t) {
                if (isDi5ParkedKeepAliveLeaseActive(
                        generation, lease)) {
                    invalidateDi5ParkedKeepAliveProxyRoute(t);
                    if (attempt != null) {
                        di5ParkedKeepAliveLastRequestDurationMs =
                                elapsedMillis(
                                        attempt.startedNanos,
                                        System.nanoTime());
                    }
                    recordDi5ParkedKeepAliveFailure(
                            generation,
                            lease,
                            attempt == null
                                    ? System.nanoTime()
                                    : attempt.startedNanos,
                            t.getClass().getSimpleName() + ": "
                                    + t.getMessage());
                }
            } finally {
                clearDi5ParkedKeepAliveRequest(
                        Thread.currentThread());
                realtimeRequestLock.unlock();
            }
        } catch (Throwable t) {
            // ScheduledExecutor suppresses all future executions when a task
            // escapes with an exception. Never let one bad tick silently kill
            // the parked heartbeat.
            if (isDi5ParkedKeepAliveLeaseActive(generation, lease)) {
                invalidateDi5ParkedKeepAliveProxyRoute(t);
                recordDi5ParkedKeepAliveFailure(
                        generation,
                        lease,
                        System.nanoTime(),
                        "unexpected " + t.getClass().getSimpleName()
                                + ": " + t.getMessage());
            }
        }
    }

    private KeepAliveAttempt beginDi5ParkedKeepAliveAttempt(
            long generation, long lease) {
        synchronized (di5ParkedKeepAliveLock) {
            if (!isDi5ParkedKeepAliveLeaseActiveLocked(
                    generation, lease)) {
                return null;
            }
            long startedNanos = System.nanoTime();
            long previousAttemptNanos =
                    di5ParkedKeepAliveLastAttemptElapsedNanos;
            di5ParkedKeepAliveWorker = Thread.currentThread();
            di5ParkedKeepAliveClient = null;
            di5ParkedKeepAliveLastAttemptAt = System.currentTimeMillis();
            di5ParkedKeepAliveLastAttemptElapsedNanos = startedNanos;
            return new KeepAliveAttempt(
                    previousAttemptNanos,
                    startedNanos,
                    previousAttemptNanos == 0L
                            ? -1L
                            : elapsedMillis(
                                    previousAttemptNanos,
                                    startedNanos));
        }
    }

    private boolean attachDi5ParkedKeepAliveClient(
            long generation, long lease, BydCloudClient client) {
        synchronized (di5ParkedKeepAliveLock) {
            if (!isDi5ParkedKeepAliveLeaseActiveLocked(
                    generation, lease)
                    || di5ParkedKeepAliveWorker
                            != Thread.currentThread()) {
                return false;
            }
            di5ParkedKeepAliveClient = client;
            return true;
        }
    }

    static long di5ParkedKeepAliveRetryDelayMs(int consecutiveFailures) {
        if (consecutiveFailures <= 1) {
            return DI5_PARKED_KEEPALIVE_RETRY_FIRST_MS;
        }
        if (consecutiveFailures == 2) {
            return DI5_PARKED_KEEPALIVE_RETRY_SECOND_MS;
        }
        return DI5_PARKED_KEEPALIVE_RETRY_MAX_MS;
    }

    /**
     * Delay needed to preserve an exact start-to-start cadence. If the prior
     * request consumed the whole interval, the next attempt is immediately
     * eligible; requests are still serialized and never overlap.
     */
    static long di5ParkedKeepAliveCadenceDelayMs(
            long startedNanos, long finishedNanos) {
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(
                DI5_PARKED_KEEPALIVE_INTERVAL_MS);
        long remainingNanos =
                intervalNanos - Math.max(0L, finishedNanos - startedNanos);
        if (remainingNanos <= 0L) return 0L;
        long delayMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (TimeUnit.MILLISECONDS.toNanos(delayMs) < remainingNanos) {
            delayMs++;
        }
        return delayMs;
    }

    static long di5ParkedKeepAliveFailureDelayMs(
            int consecutiveFailures,
            long startedNanos,
            long finishedNanos) {
        return Math.min(
                di5ParkedKeepAliveRetryDelayMs(consecutiveFailures),
                di5ParkedKeepAliveCadenceDelayMs(
                        startedNanos, finishedNanos));
    }

    private void warnIfDi5ParkedKeepAliveLate(
            long previousAttemptNanos, long nowNanos, String reason) {
        if (previousAttemptNanos == 0L) return;
        long gapMs = elapsedMillis(
                previousAttemptNanos, nowNanos);
        if (gapMs < DI5_PARKED_KEEPALIVE_DEADLINE_MS
                || di5ParkedKeepAliveLateWarningAttemptNanos
                        == previousAttemptNanos) {
            return;
        }
        di5ParkedKeepAliveLateWarningAttemptNanos =
                previousAttemptNanos;
        di5ParkedKeepAliveDeadlineMisses++;
        logger.warn("DI5 cloud keep-alive deadline missed: gap="
                + gapMs + "ms (target="
                + DI5_PARKED_KEEPALIVE_INTERVAL_MS
                + "ms, " + reason + ")");
    }

    private static long elapsedMillis(
            long startedNanos, long finishedNanos) {
        return Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(
                        finishedNanos - startedNanos));
    }

    private void clearDi5ParkedKeepAliveRequest(Thread worker) {
        synchronized (di5ParkedKeepAliveLock) {
            if (di5ParkedKeepAliveWorker == worker) {
                di5ParkedKeepAliveWorker = null;
                di5ParkedKeepAliveClient = null;
            }
        }
    }

    private boolean isDi5ParkedKeepAliveLeaseActive(
            long generation, long lease) {
        synchronized (di5ParkedKeepAliveLock) {
            return isDi5ParkedKeepAliveLeaseActiveLocked(
                    generation, lease);
        }
    }

    private boolean isDi5ParkedKeepAliveLeaseActiveLocked(
            long generation, long lease) {
        return !di5ParkedKeepAliveShutdown
                && di5ParkedKeepAliveExecutor != null
                && !di5ParkedKeepAliveExecutor.isShutdown()
                && di5ParkedKeepAliveGeneration == generation
                && di5ParkedKeepAliveLease == lease;
    }

    private void recordDi5ParkedKeepAliveFailure(
            long generation,
            long lease,
            long startedNanos,
            String detail) {
        final int failures;
        final long retryDelayMs;
        synchronized (di5ParkedKeepAliveLock) {
            if (!isDi5ParkedKeepAliveLeaseActiveLocked(
                    generation, lease)) {
                return;
            }
            failures = ++di5ParkedKeepAliveConsecutiveFailures;
            retryDelayMs =
                    di5ParkedKeepAliveFailureDelayMs(
                            failures,
                            startedNanos,
                            System.nanoTime());
            di5ParkedKeepAliveRetryDelayMs = retryDelayMs;
            scheduleDi5ParkedKeepAliveLocked(
                    generation, lease, retryDelayMs);
        }
        // Keep overnight logs useful without emitting one warning for every
        // failed request during a long cellular outage.
        String retryDetail = retryDelayMs >= 1_000L
                ? "; retry in " + (retryDelayMs / 1000L) + "s"
                : "; retry in " + retryDelayMs + "ms";
        if (failures == 1 || failures % 5 == 0) {
            logger.warn("DI5 cloud keep-alive failed (" + failures
                    + " consecutive): " + detail + retryDetail);
        } else {
            logger.debug("DI5 cloud keep-alive failed (" + failures
                    + " consecutive): " + detail + retryDetail);
        }
    }

    private void recordDi5ParkedKeepAliveSuccess(
            long generation,
            long lease,
            long startedNanos,
            long finishedNanos) {
        synchronized (di5ParkedKeepAliveLock) {
            if (!isDi5ParkedKeepAliveLeaseActiveLocked(
                    generation, lease)) {
                return;
            }
            di5ParkedKeepAliveConsecutiveFailures = 0;
            di5ParkedKeepAliveRetryDelayMs = 0L;
            scheduleDi5ParkedKeepAliveLocked(
                    generation,
                    lease,
                    di5ParkedKeepAliveCadenceDelayMs(
                            startedNanos, finishedNanos));
        }
    }

    private void scheduleDi5ParkedKeepAlive(
            long generation, long lease, long delayMs) {
        synchronized (di5ParkedKeepAliveLock) {
            scheduleDi5ParkedKeepAliveLocked(
                    generation, lease, delayMs);
        }
    }

    private void scheduleDi5ParkedKeepAliveLocked(
            long generation, long lease, long delayMs) {
        if (!isDi5ParkedKeepAliveLeaseActiveLocked(
                generation, lease)) {
            return;
        }
        ScheduledExecutorService executor =
                di5ParkedKeepAliveExecutor;
        di5ParkedKeepAliveFuture = executor.schedule(
                () -> runDi5ParkedKeepAlive(generation, lease),
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS);
    }

    private static void invalidateDi5ParkedKeepAliveProxyRoute(
            Throwable failure) {
        if (!isIoFailure(failure)) return;
        try {
            com.overdrive.app.mqtt.ProxyHelper.invalidateCache();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isIoFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof java.io.IOException) return true;
            current = current.getCause();
        }
        return false;
    }

    private KeepAliveStopHandle detachDi5ParkedKeepAliveLocked() {
        ScheduledExecutorService executor =
                di5ParkedKeepAliveExecutor;
        ScheduledFuture<?> future = di5ParkedKeepAliveFuture;
        Thread worker = di5ParkedKeepAliveWorker;
        BydCloudClient client = di5ParkedKeepAliveClient;
        if (executor == null && future == null
                && worker == null && client == null) {
            return null;
        }
        ++di5ParkedKeepAliveLease;
        di5ParkedKeepAliveExecutor = null;
        di5ParkedKeepAliveFuture = null;
        di5ParkedKeepAliveWorker = null;
        di5ParkedKeepAliveClient = null;
        return new KeepAliveStopHandle(
                executor, future, worker, client);
    }

    private void resetDi5ParkedKeepAliveMetricsLocked() {
        di5ParkedKeepAliveLastAttemptAt = 0L;
        di5ParkedKeepAliveLastAttemptElapsedNanos = 0L;
        di5ParkedKeepAliveLastSuccessAt = 0L;
        di5ParkedKeepAliveLastRequestDurationMs = -1L;
        di5ParkedKeepAliveRetryDelayMs = 0L;
        di5ParkedKeepAliveLateWarningAttemptNanos =
                Long.MIN_VALUE;
        di5ParkedKeepAliveDeadlineMisses = 0;
        di5ParkedKeepAliveConsecutiveFailures = 0;
    }

    private static void cancelDi5ParkedKeepAlive(
            KeepAliveStopHandle handle) {
        if (handle == null) return;
        if (handle.future != null) {
            handle.future.cancel(true);
        }
        // Thread interruption stops the 1.5s result-poll sleeps; cancelling
        // the owning OkHttp Call also stops a worker currently blocked in I/O.
        if (handle.client != null) {
            handle.client.cancelRequestForThread(handle.worker);
        }
        if (handle.executor != null) {
            handle.executor.shutdownNow();
        }
    }

    private static final class KeepAliveStopHandle {
        final ScheduledExecutorService executor;
        final ScheduledFuture<?> future;
        final Thread worker;
        final BydCloudClient client;

        KeepAliveStopHandle(
                ScheduledExecutorService executor,
                ScheduledFuture<?> future,
                Thread worker,
                BydCloudClient client) {
            this.executor = executor;
            this.future = future;
            this.worker = worker;
            this.client = client;
        }
    }

    private static final class KeepAliveAttempt {
        final long previousAttemptNanos;
        final long startedNanos;
        final long gapMs;

        KeepAliveAttempt(
                long previousAttemptNanos,
                long startedNanos,
                long gapMs) {
            this.previousAttemptNanos = previousAttemptNanos;
            this.startedNanos = startedNanos;
            this.gapMs = gapMs;
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
            // The generic cloud-data merge poll uses the same realtime-status
            // endpoint as the retired parked heartbeat. While an authoritative
            // DiLink 5 parked session is explicitly using the local parked
            // hold, skip this background request too so the session is truly
            // local-only. ACC ON and every non-Di5 mode retain the existing
            // five-minute cloud merge behavior.
            if (shouldSuppressRealtimePollForDi5ParkedLocalHold()) {
                logger.debug("REST realtime poll skipped: "
                        + "DiLink 5 parked local keep-alive is active");
                return;
            }
            if (!realtimeRequestLock.tryLock()) {
                logger.debug("REST realtime poll skipped: "
                        + "vehicle realtime request already in flight");
                return;
            }
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
            } finally {
                realtimeRequestLock.unlock();
            }
        }, 0, POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static boolean shouldSuppressRealtimePollForDi5ParkedLocalHold() {
        try {
            return shouldSuppressRealtimePollForDi5ParkedLocalHold(
                    com.overdrive.app.camera.dilink5.DiLink5Platform
                            .isSelected(),
                    com.overdrive.app.config.UnifiedConfigManager
                            .isDi5ParkedKeepAliveEnabled(),
                    com.overdrive.app.monitor.AccMonitor
                            .isAccStateAuthoritative(),
                    com.overdrive.app.monitor.AccMonitor.isAccOn());
        } catch (Throwable t) {
            // This is an optimization around the generic cloud-data feature,
            // not part of the ACC state machine. Preserve its old behavior if
            // platform/config state cannot be inspected.
            return false;
        }
    }

    static boolean shouldSuppressRealtimePollForDi5ParkedLocalHold(
            boolean di5Selected,
            boolean localParkedKeepAliveEnabled,
            boolean accStateAuthoritative,
            boolean accOn) {
        return di5Selected
                && localParkedKeepAliveEnabled
                && accStateAuthoritative
                && !accOn;
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

            if (!realtimeRequestLock.tryLock()) {
                logger.debug("On-demand lock-state refresh skipped: "
                        + "vehicle realtime request already in flight");
                return false;
            }
            try {
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
            } finally {
                realtimeRequestLock.unlock();
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
            boolean parkedKeepAliveActive;
            synchronized (di5ParkedKeepAliveLock) {
                parkedKeepAliveActive =
                        di5ParkedKeepAliveExecutor != null
                        && !di5ParkedKeepAliveExecutor.isShutdown();
            }
            status.put("di5ParkedKeepAliveActive",
                    parkedKeepAliveActive);
            status.put("di5ParkedKeepAliveConsecutiveFailures",
                    di5ParkedKeepAliveConsecutiveFailures);
            status.put("di5ParkedKeepAliveRetryDelaySeconds",
                    di5ParkedKeepAliveRetryDelayMs / 1000L);
            status.put("di5ParkedKeepAliveIntervalSeconds",
                    DI5_PARKED_KEEPALIVE_INTERVAL_MS / 1000L);
            status.put("di5ParkedKeepAliveDeadlineMisses",
                    di5ParkedKeepAliveDeadlineMisses);
            status.put("di5ParkedKeepAliveLastRequestDurationMs",
                    di5ParkedKeepAliveLastRequestDurationMs);
            status.put("di5ParkedKeepAliveLastAttemptAge",
                    ageSeconds(di5ParkedKeepAliveLastAttemptAt));
            status.put("di5ParkedKeepAliveLastSuccessAge",
                    ageSeconds(di5ParkedKeepAliveLastSuccessAt));

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

    private static long ageSeconds(long timestampMs) {
        if (timestampMs <= 0L) return -1L;
        return Math.max(0L,
                (System.currentTimeMillis() - timestampMs) / 1000L);
    }
}
