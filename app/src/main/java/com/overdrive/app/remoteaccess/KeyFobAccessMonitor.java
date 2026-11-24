package com.overdrive.app.remoteaccess;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.byd.OtaReportListeners;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.remote.RemoteDevViewBridgeAuth;
import com.overdrive.app.util.DaemonHttpClient;
import org.json.JSONObject;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ForkJoinPool;

/**
 * Opt-in fob observer: passive approach frames and optional instrument presence
 * searches share this worker. Only the daemon can authorize a lock action.
 * Disabled means no worker, receiver, timer or receive-table subscription.
 * A save that changes only non-fob settings is adopted in place: the instance,
 * its receiver generation and the daemon's arming seed survive the revision bump.
 */
public final class KeyFobAccessMonitor {
    private static final String TAG = "KeyFobAccessMonitor";
    private static final int OTA = 1032, VEHICLE_DATA = 1048;
    private static final int CALLBACK = OtaReportListeners.DYNAMIC_REPORT, TABLE_FEATURE = 0xaa000020;
    private static final byte[] ADD = {0, 0, 1, (byte) 0x80, 0, 0, 1, 1};
    private static final byte[] REMOVE = {0, 0, 1, (byte) 0x80, 0, 0, 1, 2};
    private static final String MARKER_PREFS = "proximity_key_fob_receiver";
    private static final String MARKER = "owned_can384_table_pending";
    private static final Object TABLE_LOCK = new Object();
    private static final long SCOPE_INTERVAL_MS = 2000, MAX_SCOPE_AGE_MS = 5000;
    private static KeyFobAccessMonitor instance;
    private static String stoppedReason = "disabled";
    private static boolean stoppedCleanupPending;

    private final Context context;
    private volatile JSONObject config;
    private final String instanceId = UUID.randomUUID().toString();
    private final KeyFobEventQueue events = new KeyFobEventQueue();
    private final KeyFobSourceRecovery recovery = new KeyFobSourceRecovery();
    private final Thread worker;
    private final KeyFobPresenceSearch presenceSearch;
    private final KeyFobWelcomeLight welcomeLight;
    private final SharedPreferences marker;
    private volatile boolean running = true, accepting, registered, tableRegistered;
    private volatile boolean cleanupPending;
    private volatile boolean listenerCleanupPending;
    private volatile boolean vehicleOnOnly;
    private volatile String reason = "waiting_for_vehicle";
    private volatile HttpURLConnection relay;
    private volatile long generation;
    private volatile int power = -1, driverLock = -1;
    private volatile long scopeObservedMs = -1;
    private long observationSequence;
    private Object manager, otaDevice;
    private volatile Object listener;
    private Method getInt, setBuffer, unregister;
    private int lockFeature;
    private boolean removeAttempted;
    private boolean listenerCleanupAttempted;

    private KeyFobAccessMonitor(Context context, JSONObject settings) {
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
        this.config = settings;
        marker = this.context.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE);
        cleanupPending = marker.getBoolean(MARKER, false);
        worker = new Thread(this::collect, "OverDrive-Proximity-KeyFob");
        worker.setDaemon(true);
        welcomeLight = new KeyFobWelcomeLight(ForkJoinPool.commonPool(),
                () -> BydDeviceHelper.getDevice(
                        "android.hardware.bydauto.setting.BYDAutoSettingDevice", this.context),
                () -> VehiclePowerReader.readNow(this.context) == 0
                        && KeyFobWelcomeLight.enabled(RemoteAccessSettings.read())
                        && !DiLink5Platform.isSelected() && !UnifiedConfigManager.isVehicleOnOnlyMode() && running);
        presenceSearch = new KeyFobPresenceSearch(this.context, events::wake,
                () -> running && config.optBoolean("keyFobLockEnabled")
                        && !worker.isInterrupted()
                        && !UnifiedConfigManager.isVehicleOnOnlyMode() && stillConsented(),
                KeyFobPresenceEvaluator.pollIntervalMs(lockWindowMs(settings)));
    }

    public static void resumeIfEnabled(Context context) {
        try { apply(context, RemoteAccessSettings.read()); }
        catch (Throwable unavailable) { Log.w(TAG, "Resume failed: " + unavailable.getClass().getSimpleName()); }
    }

    public static synchronized void apply(Context context, JSONObject settings) {
        boolean enabled = consent(settings)
                && !com.overdrive.app.config.ServiceModeFeatureGate.isPausedInConfig("proximity");
        if (instance != null && (!enabled || !sameFobRules(settings, instance.config)
                || !instance.running)) stop();
        if (!enabled) {
            // A durable ownership marker is reconciled once after an app crash,
            // including a restart where consent has since been disabled.
            if (instance == null) reconcileAbandonedTable(context);
            return;
        }
        // A1: when only non-fob settings changed, the revision is adopted in place; instanceId,
        // receiver generation and the daemon's arming seed survive.
        if (instance == null) {
            instance = new KeyFobAccessMonitor(context, settings);
            instance.worker.start();
        } else if (!sameConfig(settings, instance.config)) instance.config = settings;
    }

    public static synchronized void stop() {
        KeyFobAccessMonitor current = instance;
        if (current == null) return;
        current.running = false;
        current.accepting = false;
        current.worker.interrupt();
        current.events.wake();
        HttpURLConnection connection = current.relay;
        if (connection != null) connection.disconnect();
        if (Thread.currentThread() != current.worker) {
            try { current.worker.join(2000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (!current.worker.isAlive()) {
            // Failed SDK unregistration retains the exact handle. Only an
            // explicit stop/apply retries it; a disabled worker never polls.
            current.deactivate(true);
            current.presenceSearch.close(true);
            boolean pending = current.hasCleanupFailure();
            stoppedReason = pending ? "cleanup_failed" : "disabled";
            stoppedCleanupPending = pending;
            if (!pending) instance = null;
        }
    }

    public static synchronized JSONObject runtime() throws Exception {
        KeyFobAccessMonitor current = instance;
        return current == null
                ? new JSONObject().put("active", false).put("registered", false)
                    .put("tableRegistered", false).put("reason", stoppedReason)
                    .put("cleanupPending", stoppedCleanupPending).put("sourceGeneration", 0)
                : current.runtimeSnapshot();
    }

    private static boolean consent(JSONObject settings) {
        return settings != null && settings.optBoolean("enabled")
                && settings.optBoolean("keyFobEnabled");
    }

    /** The rules this worker is built from: consent, the locking opt-in and the effective window. */
    static boolean sameFobRules(JSONObject first, JSONObject second) {
        return consent(first) == consent(second)
                && first.optBoolean("keyFobLockEnabled") == second.optBoolean("keyFobLockEnabled")
                && lockWindowMs(first) == lockWindowMs(second);
    }

    /** The effective locking window (normal mode forces the default); it sets the search spacing. */
    static long lockWindowMs(JSONObject settings) {
        return RemoteAccessSettings.effective(settings).optLong("keyFobLockWindowMs", 10_000);
    }

    private static boolean sameConfig(JSONObject first, JSONObject second) {
        return sameFobRules(first, second)
                && first.optLong("revision") == second.optLong("revision");
    }

    private boolean scopeAllowsReceiver() {
        return KeyFobEventEvaluator.eligibleScope(power, driverLock,
                config.optBoolean("keyFobLockEnabled"));
    }

    private String ineligibleScopeReason() {
        return power != 0 ? "vehicle_not_off"
                : driverLock == 1 ? "vehicle_not_locked" : "vehicle_lock_unknown";
    }

    private boolean stillConsented() {
        try {
            if (!running || com.overdrive.app.config.ServiceModeFeatureGate.isPausedInConfig("proximity")) return false;
            JSONObject live = RemoteAccessSettings.read();
            if (!sameFobRules(live, config)) return false;
            // A1: a revision-only change is adopted in place so the next heartbeat carries it.
            if (live.optLong("revision") != config.optLong("revision")) config = live;
            return true;
        } catch (Throwable unavailable) { return false; }
    }

    private void collect() {
        long nextScope = 0, nextHeartbeat = 0;
        boolean initialized = false;
        KeyFobEventQueue.Event pending = null;
        try {
            // Unsupported native layouts cannot become supported by retrying a service.
            verifyPlatform();
            while (stillConsented() && !Thread.currentThread().isInterrupted()) {
                long now = SystemClock.elapsedRealtime();
                if (!initialized) {
                    KeyFobSourceRecovery.Decision decision = recovery.decide(
                            now, stillConsented(), recoveryBlockedByCleanup());
                    if (decision == KeyFobSourceRecovery.Decision.REVOKED
                            || decision == KeyFobSourceRecovery.Decision.CLEANUP_REQUIRED) break;
                    if (decision == KeyFobSourceRecovery.Decision.READY) {
                        try {
                            initialize();
                            if (!removeAbandonedTable())
                                throw new IllegalStateException("cleanup_failed");
                            initialized = true;
                            nextScope = 0;
                        } catch (Throwable unavailable) {
                            retireSource("cleanup_failed".equals(unavailable.getMessage())
                                    ? "cleanup_failed" : "receiver_unavailable");
                            pending = null;
                        }
                    }
                    if (!initialized) {
                        presenceSearch.tick(SystemClock.elapsedRealtime(), false, false);
                        if (now >= nextHeartbeat) {
                            publish(events.latest());
                            nextHeartbeat = SystemClock.elapsedRealtime() + SCOPE_INTERVAL_MS;
                        }
                        if (recoveryBlockedByCleanup()) break;
                        events.await(Math.max(1, Math.min(SCOPE_INTERVAL_MS,
                                recovery.retryInMs(SystemClock.elapsedRealtime()))));
                        continue;
                    }
                }
                if (now >= nextScope) {
                    vehicleOnOnly = UnifiedConfigManager.isVehicleOnOnlyMode();
                    if (!vehicleOnOnly) readScope();
                    nextScope = SystemClock.elapsedRealtime() + SCOPE_INTERVAL_MS;
                    if (vehicleOnOnly) {
                        welcomeLight.observe(-1, false);
                        reason = "vehicle_on_only";
                        deactivate();
                    } else if (power < 0 || driverLock < 0) {
                        retireSource(ineligibleScopeReason());
                        initialized = false;
                        pending = null;
                    } else if (!scopeAllowsReceiver()) {
                        reason = ineligibleScopeReason();
                        deactivate();
                    } else if (events.failure() != null || events.hasOverflow()) {
                        retireSource("receiver_unavailable");
                        initialized = false;
                        pending = null;
                    } else if (!registered && !cleanupPending && !listenerCleanupPending) {
                        activate();
                        if (registered && tableRegistered && accepting) {
                            recovery.recovered();
                        } else if (!scopeAllowsReceiver()) {
                            // A real power/lock transition during registration is a scope wait.
                            if (power < 0 || driverLock < 0) {
                                retireSource(ineligibleScopeReason());
                                initialized = false;
                            }
                        } else {
                            retireSource(reason);
                            initialized = false;
                        }
                    }
                }
                if (hasCleanupFailure()) break;
                // Presence continues across ON/OFF until parked lock is confirmed.
                // Locked parking removes the instrument listener and stops searches;
                // the independent passive CAN receiver remains available for unlock.
                // A callback wakes this queue; no six-second blocking wait steals
                // scope reads, CAN frames or the existing two-second heartbeat.
                presenceSearch.tick(SystemClock.elapsedRealtime(),
                        initialized && !vehicleOnOnly && power == 0 && driverLock >= 0
                                && config.optBoolean("keyFobLockEnabled"),
                        KeyFobPresenceCycle.shouldPause(power, driverLock));
                if (hasCleanupFailure()) break;
                if (pending != null && (pending.generation != generation
                        || now - pending.observedAtMs > MAX_SCOPE_AGE_MS)) pending = null;
                KeyFobEventQueue.Event event = pending != null ? pending : events.poll();
                now = SystemClock.elapsedRealtime();
                if (event != null) {
                    // An SDK getter is invoked again before each edge can reach
                    // the daemon; cached heartbeat scope cannot authorize it.
                    vehicleOnOnly = UnifiedConfigManager.isVehicleOnOnlyMode();
                    if (!vehicleOnOnly) readScope();
                    nextScope = SystemClock.elapsedRealtime() + SCOPE_INTERVAL_MS;
                    if (vehicleOnOnly || !scopeAllowsReceiver() || !stillConsented()) {
                        reason = vehicleOnOnly ? "vehicle_on_only"
                                : ineligibleScopeReason();
                        if (!vehicleOnOnly && (power < 0 || driverLock < 0)) {
                            retireSource(reason);
                            initialized = false;
                        } else {
                            deactivate();
                        }
                        event = null;
                        pending = null;
                    }
                    // The edge's fresh scope read may be the first confirmation
                    // of locking; retire active search evidence before relaying it.
                    presenceSearch.tick(SystemClock.elapsedRealtime(),
                            initialized && !vehicleOnOnly && power == 0 && driverLock >= 0
                                    && config.optBoolean("keyFobLockEnabled"),
                            KeyFobPresenceCycle.shouldPause(power, driverLock));
                }
                if (event != null || now >= nextHeartbeat) {
                    boolean delivered = publish(event != null ? event : events.latest());
                    pending = delivered ? null : event;
                    nextHeartbeat = SystemClock.elapsedRealtime() + SCOPE_INTERVAL_MS;
                }
                if (pending != null) {
                    // Preserve an undelivered edge with its original timestamp;
                    // bounded retry cannot manufacture a new arrival.
                    Thread.sleep(250);
                    continue;
                }
                long untilNext = Math.min(nextScope, nextHeartbeat) - SystemClock.elapsedRealtime();
                untilNext = Math.min(untilNext,
                        presenceSearch.wakeInMs(SystemClock.elapsedRealtime()));
                events.await(Math.max(1, Math.min(SCOPE_INTERVAL_MS, untilNext)));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable unavailable) {
            reason = "cleanup_failed".equals(unavailable.getMessage())
                    ? "cleanup_failed" : "receiver_unavailable";
            Log.w(TAG, "Receiver stopped: " + unavailable.getClass().getSimpleName());
        } finally {
            running = false;
            welcomeLight.observe(-1, false);
            presenceSearch.close(false);
            deactivate();
            // Settings changes clear daemon state directly. This final relay also
            // clears it when this worker loses its native receiver unexpectedly.
            publish(events.latest());
        }
    }

    private void initialize() throws Exception {
        verifyPlatform();
        Class<?> ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
        Object features = Class.forName("android.hardware.bydauto.BYDAutoDeviceFeaturesMap")
                .getMethod("getFeatureIdsFromDevice", int.class).invoke(null, OTA);
        if (!(features instanceof Set) || !((Set<?>) features).contains(CALLBACK))
            throw new UnsupportedOperationException();
        lockFeature = ids.getField("OTA_LF_DOOR_LOCK").getInt(null);
        Class<?> managerClass = Class.forName("android.hardware.BYDAutoManager");
        manager = context.getSystemService("auto");
        if (!managerClass.isInstance(manager)) throw new UnsupportedOperationException();
        getInt = managerClass.getMethod("getInt", int.class, int.class);
        setBuffer = managerClass.getMethod("setBuffer", int.class, int.class, byte[].class);
        Context bydContext = BydDeviceHelper.withBydPermissionBypass(context);
        otaDevice = BydDeviceHelper.getDevice(
                "android.hardware.bydauto.ota.BYDAutoOtaDevice", bydContext);
        if (otaDevice == null) throw new UnsupportedOperationException();
    }

    private static void verifyPlatform() throws Exception {
        if (Build.VERSION.SDK_INT != 29) throw new UnsupportedOperationException();
        Class<?> properties = Class.forName("android.os.SystemProperties");
        Method property = properties.getMethod("get", String.class, String.class);
        if (!"CANFD".equals(property.invoke(null, "sys.car.protocol", ""))
                && !"CANFD".equals(property.invoke(null, "persist.sys.protocol.record", "")))
            throw new UnsupportedOperationException();
        Class<?> ids = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
        if (ids.getField("OTA_DYNAMIC_DATA_CALLBACK").getInt(null) != CALLBACK
                || ids.getField("OTA_SENT_MONITOR_TABLE_SET").getInt(null) != TABLE_FEATURE)
            throw new UnsupportedOperationException();
    }

    /** Native shape check only: no worker, device handle or receive table is acquired. */
    public static boolean platformSupported() {
        try {
            verifyPlatform();
            return true;
        } catch (Throwable unsupported) {
            return false;
        }
    }

    private void readScope() {
        power = KeyFobSourceRecovery.protocolPower(VehiclePowerReader.readNow(context));
        driverLock = -1;
        try {
            Object value = getInt.invoke(manager, OTA, lockFeature);
            if (value instanceof Integer && ((Integer) value == 1 || (Integer) value == 2))
                driverLock = (Integer) value;
        } catch (Throwable unavailable) { /* Unknown revokes the receiver. */ }
        scopeObservedMs = SystemClock.elapsedRealtime();
        welcomeLight.observe(power, KeyFobWelcomeLight.enabled(config) && !DiLink5Platform.isSelected());
    }

    /** A service failure retires every old edge before the same worker can retry. */
    private void retireSource(String failureReason) {
        presenceSearch.invalidate(failureReason);
        deactivate();
        changeGeneration();
        if (!hasCleanupFailure()) {
            manager = null;
            otaDevice = null;
            getInt = null;
            setBuffer = null;
            unregister = null;
            reason = failureReason;
        }
        power = -1;
        driverLock = -1;
        scopeObservedMs = SystemClock.elapsedRealtime();
        recovery.failed(scopeObservedMs);
    }

    private void activate() {
        if (!stillConsented() || !scopeAllowsReceiver()
                || UnifiedConfigManager.isVehicleOnOnlyMode()) return;
        changeGeneration();
        try {
            Class<?> type = Class.forName("android.hardware.IBYDAutoListener");
            final long listenerGeneration = generation;
            listenerCleanupAttempted = false;
            listenerCleanupPending = false;
            listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                            if ("toString".equals(method.getName())) return "OverDriveKeyFobObserver";
                        }
                        if (!accepting || !running || generation != listenerGeneration
                                || listener != proxy) return null;
                        if ("onError".equals(method.getName())) {
                            events.fail(listenerGeneration, "native_callback_error");
                        } else if ("onDataEventChanged".equals(method.getName())
                                && args != null && args.length == 2
                                && args[0] instanceof Integer && (Integer) args[0] == CALLBACK) {
                            try {
                                Object buffer = args[1].getClass().getField("bufferDataValue").get(args[1]);
                                int mask = KeyFobCanDecoder.decodeMask(
                                        buffer instanceof byte[] ? (byte[]) buffer : null);
                                if (mask != -2) events.offer(listenerGeneration,
                                        mask, SystemClock.elapsedRealtime());
                            } catch (Throwable unavailable) {
                                events.fail(listenerGeneration, "native_callback_shape");
                            }
                        }
                        return null;
                    });
            unregister = otaDevice.getClass().getMethod("unregisterListener", type);
            // AbsBYDAutoDevice owns a listener-ID map. Its unregister disables
            // OTA only when the last listener leaves; existing telemetry survives.
            if (!OtaReportListeners.register(otaDevice, type, listener))
                throw new IllegalStateException("registration_failed");
            registered = true;
            // Sparse first frames can arrive during the ADD/getter calls.
            // Buffer them now; no edge is relayed until activate finishes with
            // acknowledged table registration and a fresh eligible OFF scope.
            accepting = true;
            synchronized (TABLE_LOCK) {
                if (!stillConsented()) throw new IllegalStateException("disabled");
                if (!marker.edit().putBoolean(MARKER, true).commit())
                    throw new IllegalStateException("table_unavailable");
                removeAttempted = false;
                cleanupPending = true; // Set before an ADD whose result may be lost.
                Object result = setBuffer.invoke(manager, VEHICLE_DATA, TABLE_FEATURE, (Object) ADD);
                if (!(result instanceof Integer) || (Integer) result != 0)
                    throw new IllegalStateException("table_unavailable");
                tableRegistered = true;
            }
            readScope();
            vehicleOnOnly = UnifiedConfigManager.isVehicleOnOnlyMode();
            if (!stillConsented() || vehicleOnOnly || !scopeAllowsReceiver()) {
                reason = vehicleOnOnly ? "vehicle_on_only"
                        : ineligibleScopeReason();
                deactivate();
                return;
            }
            reason = "listening";
            Log.i(TAG, "Fob receiver registered for eligible powered-off vehicle");
        } catch (Throwable unavailable) {
            reason = "table_unavailable".equals(unavailable.getMessage())
                    ? "table_unavailable" : "registration_failed";
            deactivate();
        }
    }

    private boolean listenerIsMapped() throws Exception {
        return OtaReportListeners.contains(otaDevice, listener);
    }

    private void deactivate() { deactivate(false); }

    private void deactivate(boolean explicitReconciliation) {
        boolean hadReceiver = accepting || tableRegistered
                || (listener != null && !listenerCleanupAttempted);
        accepting = false;
        if (hadReceiver) changeGeneration();
        if (explicitReconciliation) {
            if (listener != null) listenerCleanupAttempted = false;
            if (cleanupPending && !tableRegistered) removeAttempted = false;
        }
        synchronized (TABLE_LOCK) {
            if (cleanupPending && !removeAttempted && manager != null && setBuffer != null) {
                removeAttempted = true;
                try {
                    Object result = setBuffer.invoke(manager, VEHICLE_DATA, TABLE_FEATURE, (Object) REMOVE);
                    if (result instanceof Integer && (Integer) result == 0) {
                        cleanupPending = !marker.edit().remove(MARKER).commit();
                    }
                } catch (Throwable unavailable) { /* Marker remains for reconciliation. */ }
            }
            tableRegistered = false;
        }
        if (listener != null && otaDevice != null && unregister != null
                && !listenerCleanupAttempted) {
            listenerCleanupAttempted = true;
            try {
                unregister.invoke(otaDevice, listener);
                if (listenerIsMapped()) {
                    listenerCleanupPending = true;
                } else {
                    listener = null;
                    registered = false;
                    listenerCleanupPending = false;
                }
            }
            catch (Throwable unavailable) { listenerCleanupPending = true; }
        }
        if (hasCleanupFailure()) {
            reason = "cleanup_failed";
            Log.w(TAG, "Fob receiver cleanup pending; owned table marker or SDK listener retained");
        }
    }

    private boolean hasCleanupFailure() {
        return (cleanupPending && !tableRegistered) || listenerCleanupPending
                || presenceSearch.hasCleanupFailure();
    }

    private boolean recoveryBlockedByCleanup() {
        // A crash marker needs one manager acquisition before its owned REMOVE
        // can be attempted. A failed actual cleanup remains explicit-retry only.
        return listenerCleanupPending || presenceSearch.hasCleanupFailure()
                || (cleanupPending && !tableRegistered && removeAttempted);
    }

    private void changeGeneration() {
        generation++;
        events.reset(generation);
    }

    private boolean removeAbandonedTable() {
        synchronized (TABLE_LOCK) {
            if (!marker.getBoolean(MARKER, false)) return true;
            removeAttempted = true;
            try {
                Object result = setBuffer.invoke(manager, VEHICLE_DATA, TABLE_FEATURE, (Object) REMOVE);
                boolean removed = result instanceof Integer && (Integer) result == 0
                        && marker.edit().remove(MARKER).commit();
                cleanupPending = !removed;
                return removed;
            } catch (Throwable unavailable) {
                cleanupPending = true;
                return false;
            }
        }
    }

    private static void reconcileAbandonedTable(Context context) {
        Context app = context.getApplicationContext();
        Context owner = app != null ? app : context;
        SharedPreferences prefs = owner.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(MARKER, false)) return;
        synchronized (TABLE_LOCK) {
            if (!prefs.getBoolean(MARKER, false)) return;
            try {
                verifyPlatform();
                Object manager = owner.getSystemService("auto");
                Object result = manager.getClass().getMethod("setBuffer",
                        int.class, int.class, byte[].class)
                        .invoke(manager, VEHICLE_DATA, TABLE_FEATURE, (Object) REMOVE);
                stoppedCleanupPending = !(result instanceof Integer && (Integer) result == 0
                        && prefs.edit().remove(MARKER).commit());
            } catch (Throwable unavailable) { stoppedCleanupPending = true; }
            stoppedReason = stoppedCleanupPending ? "cleanup_failed" : "disabled";
        }
    }

    private JSONObject runtimeSnapshot() throws Exception {
        long now = SystemClock.elapsedRealtime();
        boolean stopping = !running && worker.isAlive()
                && Thread.currentThread() != worker;
        boolean healthy = running && accepting && registered && tableRegistered
                && !vehicleOnOnly && events.failure() == null && !events.hasOverflow()
                && scopeAllowsReceiver()
                && scopeObservedMs >= 0 && now - scopeObservedMs <= MAX_SCOPE_AGE_MS;
        return new JSONObject().put("active", healthy).put("registered", registered)
                .put("tableRegistered", tableRegistered).put("reason", stopping ? "stopping" : reason)
                .put("stopping", stopping)
                .put("cleanupPending", hasCleanupFailure() || stopping)
                .put("sourceGeneration", generation)
                .put("presenceSearch", presenceSearch.runtime());
    }

    private boolean publish(KeyFobEventQueue.Event event) {
        HttpURLConnection connection = null;
        try {
            if (event.generation != generation) event = events.latest();
            JSONObject observation = new JSONObject().put("command", "remoteAccessKeyFobObservation")
                    .put("revision", config.optLong("revision")).put("instanceId", instanceId)
                    .put("observationSequence", ++observationSequence)
                    .put("sourceGeneration", generation).put("eventSequence", event.sequence)
                    .put("rawMask", event.rawMask).put("eventObservedAtElapsedMs", event.observedAtMs)
                    .put("runtime", runtimeSnapshot())
                    .put("scope", new JSONObject().put("power", power).put("driverLock", driverLock)
                            .put("observedAtElapsedMs", scopeObservedMs));
            presenceSearch.appendObservation(observation);
            byte[] bytes = RemoteDevViewBridgeAuth.sign(observation).getBytes(StandardCharsets.UTF_8);
            connection = DaemonHttpClient.open("/api/remote-access/relay", "POST", 400, 800);
            relay = connection;
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Throwable unavailable) {
            // Lost relay is unknown. An old event is never refreshed for retry.
            return false;
        } finally {
            relay = null;
            if (connection != null) connection.disconnect();
        }
    }
}
