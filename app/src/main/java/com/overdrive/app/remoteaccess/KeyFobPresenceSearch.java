package com.overdrive.app.remoteaccess;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import com.overdrive.app.byd.BydDeviceHelper;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/**
 * Callback-driven legacy physical-key search, advanced only by the existing
 * fob worker. No thread, Handler, timer, wake lock or lock command is created.
 */
final class KeyFobPresenceSearch {
    private static final int INSTRUMENT = 1007;
    private static final int SETTING = 1023;
    private final Context context;
    private final Runnable wakeWorker;
    private final BooleanSupplier stillAllowed;
    private final Environment environment;
    private volatile DiLink5Platform.ActiveModeSnapshot modeBinding;
    private final KeyFobPresenceCycle cycle;
    private volatile Object listener;
    private volatile boolean requestIssued;
    private boolean enabled, cleanupPending, cleanupAttempted, registrationAttempted;
    private Object manager, instrument;
    private Method getInt, setInt, unregister;
    private int resultFeature, searchFeature;
    /** BYD key screens pick the search pair from SET_SINGLE_DOUBLE_MODE: single = instrument, double = setting. */
    private int searchDevice = INSTRUMENT, otherResultFeature;
    private String keyModule = "single";
    private volatile boolean otherResultDue;
    private Integer otherResultRaw;
    private Integer baselineRaw, requestReturnCode;
    private volatile Integer lastCallbackRaw;
    private String baselineStatus = "not_read";
    private boolean requestAccepted;
    private volatile boolean progressObserved;
    private volatile String reason = "disabled";

    interface Environment {
        DiLink5Platform.ActiveModeSnapshot committedMode();
        boolean supportsNativeApi();
        long elapsedRealtime();
        Class<?> nativeType(String name) throws ClassNotFoundException;
        Object autoService(Context context);
        Object instrumentDevice(Context context) throws Exception;
    }

    KeyFobPresenceSearch(Context context, Runnable wakeWorker, BooleanSupplier stillAllowed) {
        this(context, wakeWorker, stillAllowed, KeyFobPresenceCycle.DEFAULT_INTERVAL_MS);
    }

    KeyFobPresenceSearch(Context context, Runnable wakeWorker, BooleanSupplier stillAllowed,
                         Environment environment) {
        this(context, wakeWorker, stillAllowed, KeyFobPresenceCycle.DEFAULT_INTERVAL_MS, environment);
    }

    /** B2: the call spacing follows the configured locking window (ten seconds by default). */
    KeyFobPresenceSearch(Context context, Runnable wakeWorker, BooleanSupplier stillAllowed,
                         long intervalMs) {
        this(context, wakeWorker, stillAllowed, intervalMs, new AndroidEnvironment());
    }

    KeyFobPresenceSearch(Context context, Runnable wakeWorker, BooleanSupplier stillAllowed,
                         long intervalMs, Environment environment) {
        this.context = context;
        this.wakeWorker = wakeWorker;
        this.stillAllowed = stillAllowed;
        this.environment = environment;
        this.cycle = new KeyFobPresenceCycle(intervalMs);
    }

    /** Invoked on the fob worker between normal scope/relay/event processing. */
    synchronized void tick(long now, boolean allowed, boolean parkedLocked) {
        if (!allowed || !stillAllowed.getAsBoolean()) {
            if (enabled || cycle.snapshot().pausedLocked)
                invalidate("disabled");
            cleanup(false);
            return;
        }
        if (!reconcileMode()) return;
        if (otherResultDue) readOtherResult();
        if (parkedLocked) {
            requestIssued = false;
            enabled = false;
            cycle.pauseLocked();
            reason = "search_paused_locked";
            cleanup(false);
            if (!cleanupPending) clearHandles();
            return;
        }
        cycle.resume();
        enabled = true;
        if (cycle.expire(now)) {
            requestIssued = false;
            reason = "search_timeout";
        }
        if (!cycle.snapshot().pending) cleanup(false);
        if (cleanupPending || !cycle.due(now)) return;
        start(now);
    }

    private void start(long now) {
        long cycleId = cycle.begin(now, -1);
        if (cycleId == 0) return;
        long generation = cycle.snapshot().generation;
        final DiLink5Platform.ActiveModeSnapshot queryBinding = modeBinding;
        baselineRaw = lastCallbackRaw = requestReturnCode = null;
        baselineStatus = "not_read";
        requestAccepted = progressObserved = false;
        otherResultDue = false;
        otherResultRaw = null;
        try {
            initialize();
            if (!allowedNow(queryBinding)) throw new IllegalStateException("revoked");
            // The native cache may be unavailable until the first actual search.
            // It is optional freshness evidence, never a result or a query gate.
            try {
                Object initial = getInt.invoke(manager, INSTRUMENT, resultFeature);
                baselineRaw = initial instanceof Integer ? (Integer) initial : null;
                baselineStatus = baselineRaw != null && baselineRaw >= 0 && baselineRaw <= 3
                        ? "available" : "unavailable";
            } catch (ReflectiveOperationException unavailable) {
                baselineStatus = "read_failed";
            }
            if (!allowedNow(queryBinding)) throw new IllegalStateException("revoked");
            cycle.baseline(generation, cycleId, baselineRaw == null ? -1 : baselineRaw);
            Class<?> type = environment.nativeType("android.hardware.IBYDAutoListener");
            KeyFobPresenceCallback callbacks = new KeyFobPresenceCallback(type,
                    environment.nativeType("android.hardware.IBYDAutoEvent"), INSTRUMENT, resultFeature);
            registrationAttempted = false;
            listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName()))
                                return args != null && args.length == 1 && proxy == args[0];
                            if ("toString".equals(method.getName())) return "OverDrivePhysicalKeySearch";
                        }
                        if (!requestIssued || listener != proxy || !allowedNow(queryBinding))
                            return null;
                        boolean completed = false;
                        long observed = environment.elapsedRealtime();
                        if (callbacks.isError(method)) {
                            completed = cycle.fail(generation, cycleId, observed);
                        } else {
                            try {
                                Integer raw = callbacks.read(method, args);
                                if (raw != null && allowedNow(queryBinding)) {
                                    lastCallbackRaw = raw;
                                    if (raw == 0) progressObserved = true;
                                    completed = cycle.observe(generation, cycleId, raw, observed);
                                    if (completed && (raw == 2 || raw == 3) && otherResultFeature != 0)
                                        otherResultDue = true;
                                }
                            } catch (Throwable unavailable) {
                                completed = cycle.fail(generation, cycleId, observed);
                            }
                        }
                        if (completed) {
                            requestIssued = false;
                            if (!allowedNow(queryBinding)) {
                                reason = "search_platform_changed";
                                wakeWorker.run();
                                return null;
                            }
                            reason = "unknown".equals(cycle.snapshot().state)
                                    ? "search_result_unavailable" : "result_received";
                            wakeWorker.run();
                        }
                        return null;
                    });
            unregister = instrument.getClass().getMethod("unregisterListener", type);
            Object map = findField(instrument.getClass(), "mIBYDAutoListenerMap").get(instrument);
            synchronized (map) {
                if (!mapAllowsAddition(map))
                    throw new IllegalStateException("search_listener_conflict");
                cleanupAttempted = false;
                cleanupPending = true;
                registrationAttempted = true;
                instrument.getClass().getMethod("registerListener", type, int[].class)
                        .invoke(instrument, listener, new int[]{resultFeature});
                if (!listenerIsMapped()) throw new IllegalStateException("search_registration_failed");
            }
            if (!allowedNow(queryBinding)) throw new IllegalStateException("revoked");
            if (!cycle.requestStarted(generation, cycleId, environment.elapsedRealtime()))
                throw new IllegalStateException("search_cancelled");
            requestIssued = true;
            if (!allowedNow(queryBinding)) throw new IllegalStateException("revoked");
            Object result = setInt.invoke(manager, searchDevice, searchFeature, 2);
            requestReturnCode = result instanceof Integer ? (Integer) result : null;
            if (!(result instanceof Integer) || (Integer) result != 0)
                throw new IllegalStateException("search_request_failed");
            if (!allowedNow(queryBinding)) throw new IllegalStateException("revoked");
            requestAccepted = true;
            if (cycle.acceptRequest(generation, cycleId, environment.elapsedRealtime())) {
                requestIssued = false;
                if (allowedNow(queryBinding)) wakeWorker.run();
            }
            KeyFobPresenceCycle.Snapshot received = cycle.snapshot();
            reason = received.pending ? "searching"
                    : "unknown".equals(received.state) ? "search_result_unavailable" : "result_received";
        } catch (Throwable unavailable) {
            requestIssued = false;
            cycle.fail(generation, cycleId, environment.elapsedRealtime());
            reason = !platformAllowed() ? "search_platform_changed"
                    : unavailable.getMessage() != null
                        ? unavailable.getMessage() : "search_unavailable";
            cleanup(false);
            if (!cleanupPending) clearHandles();
        }
    }

    private void initialize() throws Exception {
        if (!platformAllowed())
            throw new UnsupportedOperationException("search_platform_unsupported");
        if (manager != null && instrument != null && getInt != null && setInt != null) return;
        Class<?> properties = environment.nativeType("android.os.SystemProperties");
        Method property = properties.getMethod("get", String.class, String.class);
        if (!"CANFD".equals(property.invoke(null, "sys.car.protocol", ""))
                && !"CANFD".equals(property.invoke(null, "persist.sys.protocol.record", "")))
            throw new UnsupportedOperationException("search_platform_unsupported");
        Class<?> ids = environment.nativeType("android.hardware.bydauto.BYDAutoFeatureIds");
        resultFeature = ids.getField("INSTRUMENT_SEEK_PHISICAL_KEY_RESULT").getInt(null);
        searchFeature = ids.getField("INSTRUMENT_SEARCH_APPLE_KEY_SET").getInt(null);
        Object features = environment.nativeType("android.hardware.bydauto.BYDAutoDeviceFeaturesMap")
                .getMethod("getFeatureIdsFromDevice", int.class).invoke(null, INSTRUMENT);
        if (!(features instanceof Set) || !((Set<?>) features).contains(resultFeature)
                || !((Set<?>) features).contains(searchFeature))
            throw new UnsupportedOperationException("search_features_unavailable");
        Class<?> managerType = environment.nativeType("android.hardware.BYDAutoManager");
        manager = environment.autoService(context);
        if (!managerType.isInstance(manager))
            throw new UnsupportedOperationException("search_manager_unavailable");
        getInt = managerType.getMethod("getInt", int.class, int.class);
        setInt = managerType.getMethod("setInt", int.class, int.class, int.class);
        instrument = environment.instrumentDevice(context);
        if (instrument == null) throw new UnsupportedOperationException("search_device_unavailable");
        selectKeyModule(ids, (Set<?>) features);
    }

    /** Leaves today's single pair in place unless the car reads double mode with both double features present. */
    private void selectKeyModule(Class<?> ids, Set<?> instrumentFeatures) {
        searchDevice = INSTRUMENT;
        keyModule = "single";
        otherResultFeature = 0;
        int mode, check, find;
        try {
            mode = ids.getField("SET_SINGLE_DOUBLE_MODE").getInt(null);
            check = ids.getField("SETTING_START_CHECK_APPLE_KEY_SET").getInt(null);
            find = ids.getField("INSTRUMENT_FIND_KEY_RESULT").getInt(null);
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return;
        }
        try {
            Object settings = environment.nativeType("android.hardware.bydauto.BYDAutoDeviceFeaturesMap")
                    .getMethod("getFeatureIdsFromDevice", int.class).invoke(null, SETTING);
            if (!(settings instanceof Set) || !((Set<?>) settings).contains(mode)
                    || !((Set<?>) settings).contains(check) || !instrumentFeatures.contains(find)) return;
            otherResultFeature = find;
            Object value = getInt.invoke(manager, SETTING, mode);
            if (!(value instanceof Integer) || (Integer) value != 0 && (Integer) value != 1) {
                keyModule = "unknown";
                return;
            }
            if ((Integer) value == 0) return;
            keyModule = "double";
            otherResultFeature = resultFeature;
            resultFeature = find;
            searchFeature = check;
            searchDevice = SETTING;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            keyModule = "unknown";
        }
    }

    /** One read-only sample of the other module's result after a not-found answer; never a query. */
    private void readOtherResult() {
        otherResultDue = false;
        if (manager == null || getInt == null || otherResultFeature == 0) return;
        try {
            Object value = getInt.invoke(manager, INSTRUMENT, otherResultFeature);
            otherResultRaw = value instanceof Integer ? (Integer) value : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            otherResultRaw = null;
        }
    }

    synchronized void invalidate(String nextReason) {
        requestIssued = false;
        enabled = false;
        cycle.reset();
        reason = nextReason;
        cleanup(false);
        if (!cleanupPending) clearHandles();
    }

    synchronized void close(boolean explicitRetry) {
        if (enabled || cycle.snapshot().pending || cycle.snapshot().pausedLocked)
            invalidate("disabled");
        requestIssued = false;
        cleanup(explicitRetry);
        if (!cleanupPending) clearHandles();
    }

    synchronized boolean hasCleanupFailure() {
        return cleanupPending && cleanupAttempted;
    }

    synchronized long wakeInMs(long now) {
        return enabled ? cycle.wakeInMs(now) : cycle.intervalMs();
    }

    synchronized JSONObject appendObservation(JSONObject observation) throws Exception {
        KeyFobPresenceCycle.Snapshot value = cycle.snapshot();
        return observation.put("presenceObservationState", value.state)
                .put("presenceObservationElapsedRealtimeMs", value.observedMs)
                .put("presenceObservationId", value.observationId)
                .put("presenceSearchCycleId", value.cycleId)
                .put("presenceQueryStartedElapsedRealtimeMs", value.startedMs)
                .put("presenceSourceGeneration", value.generation)
                .put("presenceSourceActive", enabled && allowedNow()
                        && !hasCleanupFailure())
                .put("presenceSearchStatus", value.status);
    }

    synchronized JSONObject runtime() throws Exception {
        JSONObject out = baseRuntime();
        Integer last = lastCallbackRaw;
        if (!"single".equals(keyModule) || last != null && (last == 2 || last == 3))
            out.put("keyModule", keyModule);
        if (otherResultRaw != null) out.put("otherKeyResultRaw", otherResultRaw);
        return out;
    }

    private JSONObject baseRuntime() throws Exception {
        KeyFobPresenceCycle.Snapshot value = cycle.snapshot();
        return new JSONObject().put("active", enabled && allowedNow()
                        && !hasCleanupFailure())
                .put("state", value.state).put("status", value.status)
                .put("reason", hasCleanupFailure() ? "search_cleanup_failed" : reason)
                .put("sourceGeneration", value.generation)
                .put("observationId", value.observationId)
                .put("observedAtElapsedMs", value.observedMs)
                .put("queryStartedElapsedMs", value.startedMs)
                .put("intervalMs", cycle.intervalMs())
                .put("timeoutMs", KeyFobPresenceCycle.TIMEOUT_MS)
                .put("baselineStatus", baselineStatus)
                .put("baselineRaw", baselineRaw == null ? JSONObject.NULL : baselineRaw)
                .put("requestAccepted", requestAccepted)
                .put("requestReturnCode", requestReturnCode == null ? JSONObject.NULL : requestReturnCode)
                .put("progressObserved", progressObserved)
                .put("lastCallbackRaw", lastCallbackRaw == null ? JSONObject.NULL : lastCallbackRaw)
                .put("cleanupPending", hasCleanupFailure());
    }

    private void cleanup(boolean explicitRetry) {
        if (listener == null) {
            cleanupPending = false;
            return;
        }
        if (!registrationAttempted) {
            // No registration was attempted: never unregister another consumer
            // or disable native delivery while resolving a narrowed-filter conflict.
            listener = null;
            cleanupPending = false;
            return;
        }
        if (explicitRetry) cleanupAttempted = false;
        if (cleanupAttempted) return;
        cleanupAttempted = true;
        try {
            if (instrument == null || unregister == null)
                throw new IllegalStateException("search_cleanup_unavailable");
            unregister.invoke(instrument, listener);
            if (listenerIsMapped()) throw new IllegalStateException("search_cleanup_failed");
            listener = null;
            cleanupPending = false;
            registrationAttempted = false;
        } catch (Throwable unavailable) {
            cleanupPending = true;
            reason = "search_cleanup_failed";
        }
    }

    private boolean listenerIsMapped() throws Exception {
        Object map = findField(instrument.getClass(), "mIBYDAutoListenerMap").get(instrument);
        Method getListeners = map.getClass().getMethod("getIdListeners", int.class);
        getListeners.setAccessible(true);
        Object values = getListeners.invoke(map, resultFeature);
        return values instanceof Collection && ((Collection<?>) values).contains(listener);
    }

    private static boolean mapAllowsAddition(Object map) throws Exception {
        Method empty = map.getClass().getMethod("isListenerEmpty");
        Method allEmpty = map.getClass().getMethod("isListenerAllIdsEmpty");
        empty.setAccessible(true);
        allEmpty.setAccessible(true);
        // A second independent narrowed filter could replace another consumer.
        return Boolean.TRUE.equals(empty.invoke(map)) || Boolean.FALSE.equals(allEmpty.invoke(map));
    }

    private void clearHandles() {
        manager = instrument = null;
        getInt = setInt = unregister = null;
    }

    private boolean allowedNow() {
        return allowedNow(modeBinding);
    }

    private boolean allowedNow(DiLink5Platform.ActiveModeSnapshot expected) {
        return expected != null && modeBinding == expected && platformAllowed()
                && stillAllowed.getAsBoolean();
    }

    private boolean platformAllowed() {
        DiLink5Platform.ActiveModeSnapshot bound = modeBinding;
        return environment.supportsNativeApi() && bound != null
                && bound.supportsLegacyVehicleApi() && bound.matches(environment.committedMode());
    }

    private boolean reconcileMode() {
        DiLink5Platform.ActiveModeSnapshot current = environment.committedMode();
        if (environment.supportsNativeApi() && modeBinding != null
                && modeBinding.supportsLegacyVehicleApi() && modeBinding.matches(current))
            return true;
        if (modeBinding != null || enabled || cycle.snapshot().pending
                || cycle.snapshot().pausedLocked) {
            requestIssued = false;
            enabled = false;
            modeBinding = null;
            cycle.resetForModeChange();
            baselineRaw = lastCallbackRaw = requestReturnCode = null;
            baselineStatus = "not_read";
            requestAccepted = progressObserved = false;
        }
        reason = "search_platform_changed";
        cleanup(false);
        if (cleanupPending) return false;
        clearHandles();
        if (!environment.supportsNativeApi() || current == null
                || !current.supportsLegacyVehicleApi()
                || !current.matches(environment.committedMode())
                || !stillAllowed.getAsBoolean()) return false;
        // Adoption is permitted only after the old exact SDK listener was removed.
        // The same cycle retains increasing generations and the configured call spacing
        // (ten seconds, or five for windows that are not a multiple of ten).
        modeBinding = current;
        return true;
    }

    private static final class AndroidEnvironment implements Environment {
        private Context bydContext;

        @Override public DiLink5Platform.ActiveModeSnapshot committedMode() {
            return DiLink5Platform.currentActiveModeSnapshot();
        }

        @Override public boolean supportsNativeApi() {
            return Build.VERSION.SDK_INT == 29;
        }

        @Override public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        @Override public Class<?> nativeType(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }

        private Context bydContext(Context context) {
            if (bydContext == null)
                bydContext = BydDeviceHelper.withBydPermissionBypass(context);
            return bydContext;
        }

        @Override public Object autoService(Context context) {
            return bydContext(context).getSystemService("auto");
        }

        @Override public Object instrumentDevice(Context context) throws Exception {
            return BydDeviceHelper.getDevice(
                    "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice",
                    bydContext(context));
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field value = current.getDeclaredField(name);
                value.setAccessible(true);
                return value;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
