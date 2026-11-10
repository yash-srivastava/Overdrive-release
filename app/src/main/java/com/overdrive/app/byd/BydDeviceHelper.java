package com.overdrive.app.byd;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reflection utilities for safely accessing BYD SDK devices.
 * Every method is null-safe and exception-safe — never crashes.
 */
public final class BydDeviceHelper {

    private static final DaemonLogger logger = DaemonLogger.getInstance("BydDeviceHelper");
    private static final java.util.Map<Object, Object> safetyBeltListeners =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());
    private static final java.util.Map<Object, RetainedListener> retainedListeners =
            java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static final class RetainedListener {
        final Object device;
        final Object listener;
        final Method register;
        final Method unregister;
        final int[] featureIds;
        boolean attached = true;

        RetainedListener(Object device, Object listener, Method register,
                         Method unregister, int[] featureIds) {
            this.device = device;
            this.listener = listener;
            this.register = register;
            this.unregister = unregister;
            this.featureIds = featureIds == null ? null : featureIds.clone();
        }

        synchronized boolean refresh() {
            if (attached) {
                if (unregister == null) return false;
                try {
                    unregister.invoke(device, listener);
                    attached = false;
                } catch (Exception e) {
                    logger.debug("Listener unregister failed: " + e.getMessage());
                    return false;
                }
            }
            try {
                invokeRegister(device, listener, register, featureIds);
                attached = true;
                return true;
            } catch (Exception e) {
                logger.debug("Listener re-register failed: " + e.getMessage());
                return false;
            }
        }

        synchronized boolean detach() {
            if (!attached) return true;
            if (unregister == null) return false;
            try {
                unregister.invoke(device, listener);
                attached = false;
                return true;
            } catch (Exception e) {
                logger.debug("Listener unregister failed: " + e.getMessage());
                return false;
            }
        }

        synchronized boolean isDetached() {
            return !attached;
        }
    }

    private static void ensureRuntimeSdk() {
        ensureHiddenApiBypass();
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            com.overdrive.app.byd.dilink5.Dilink5SdkInjector.ensure(null);
        }
    }

    private static void ensureHiddenApiBypass() {
        try {
            com.overdrive.app.shell.HiddenApiBypass.INSTANCE.bypass();
        } catch (Throwable ignored) {
            // Pure JVM unit tests have no dalvik.system.VMRuntime; safe to ignore.
        }
    }

    static boolean adapterManagerReady(Object manager, String adapterGetter) {
        if (manager == null || adapterGetter == null || adapterGetter.isEmpty()) {
            return false;
        }
        Object connected = callGetter(manager, "isCarServiceConnect");
        return Boolean.TRUE.equals(connected)
                && callGetter(manager, adapterGetter) != null;
    }

    static boolean isDiLink5BinderServiceAlive(Object adapter) {
        if (adapter == null) return false;
        try {
            Field serviceField = findDeclaredField(adapter.getClass(), "mService");
            if (serviceField == null) return false;
            serviceField.setAccessible(true);
            Object service = serviceField.get(adapter);
            if (!(service instanceof android.os.IInterface)) return false;
            android.os.IBinder binder = ((android.os.IInterface) service).asBinder();
            return binder != null && binder.isBinderAlive();
        } catch (Throwable failure) {
            logger.debug("DiLink 5 adapter binder check failed for "
                    + adapter.getClass().getSimpleName() + ": " + failure.getMessage());
            return false;
        }
    }

    private static Field findDeclaredField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Class<?> loadDiLink5Class(String className, ClassLoader preferred) {
        ClassLoader[] loaders = new ClassLoader[] {
                preferred,
                com.overdrive.app.byd.dilink5.Dilink5SdkInjector.class.getClassLoader(),
                BydDeviceHelper.class.getClassLoader(),
                Context.class.getClassLoader()
        };
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                return Class.forName(className, true, loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * Resolve a live DiLink 5 {@code com.ts.lib.caradapter.*} manager in the app process.
     *
     * <p>In {@code /system/framework/byd.ota.master.jar}, {@code TsManagerImpl}'s getters (such as
     * {@code getChargingAdapterManager()}) return the cached field without lazy resolution, so a
     * call that races the asynchronous {@code CarAdapterService} bind or follows a transient
     * binder drop sees {@code null} and makes {@code BYDAutoChargingDevice.setACChargingLimit}
     * fail immediately with {@code rc=-1} before reaching {@code CarPropertyService}. Falling back
     * to {@code CarAdapterManager.getInstance(ctx).getCarAdapterManager(carAdapterKey)} resolves
     * the live Binder handle directly once {@code CarAdapterService} is bound.
     */
    public static Object acquireDiLink5CarAdapter(
            Context context, Object deviceHint, String tsAdapterGetter, String carAdapterKey) {
        if (context == null || VehicleActuatorBridge.isDiLink5RequestExpired()) {
            return null;
        }
        ensureHiddenApiBypass();
        try {
            com.overdrive.app.byd.dilink5.Dilink5SdkInjector.ensure(context);
        } catch (Throwable ignored) {
        }
        Context rawApp = context.getApplicationContext();
        Context appContext = withBydPermissionBypass(rawApp != null ? rawApp : context);
        ClassLoader preferredLoader = deviceHint != null
                ? deviceHint.getClass().getClassLoader()
                : com.overdrive.app.byd.dilink5.Dilink5SdkInjector.class.getClassLoader();

        if (tsAdapterGetter != null && !tsAdapterGetter.isEmpty()) {
            try {
                Class<?> tsManagerClass = loadDiLink5Class(
                        "android.hardware.bydauto.TsManagerImpl", preferredLoader);
                if (tsManagerClass != null) {
                    Method getInstance = tsManagerClass.getMethod("getInstance", Context.class);
                    Object tsManager = getInstance.invoke(null, appContext);
                    if (adapterManagerReady(tsManager, tsAdapterGetter)) {
                        Object adapter = callGetter(tsManager, tsAdapterGetter);
                        if (isDiLink5BinderServiceAlive(adapter)) {
                            return adapter;
                        }
                    }
                }
            } catch (Throwable failure) {
                logger.debug("DiLink 5 TsManagerImpl probe failed for "
                        + tsAdapterGetter + ": " + failure.getMessage());
            }
        }

        if (carAdapterKey != null && !carAdapterKey.isEmpty()) {
            try {
                Class<?> carAdapterMgrClass = loadDiLink5Class(
                        "com.ts.lib.caradapter.CarAdapterManager", preferredLoader);
                if (carAdapterMgrClass != null) {
                    Method getInstance = carAdapterMgrClass.getMethod("getInstance", Context.class);
                    Object carAdapterMgr = getInstance.invoke(null, appContext);
                    if (carAdapterMgr != null) {
                        Object bound = callGetter(carAdapterMgr, "isCarServiceBound");
                        if (!(bound instanceof Boolean)) {
                            bound = callGetter(carAdapterMgr, "isServiceBound");
                        }
                        if (!Boolean.TRUE.equals(bound)) {
                            carAdapterMgrClass.getMethod("connect").invoke(carAdapterMgr);
                            return null;
                        }
                        Method getAdapter = carAdapterMgrClass.getMethod(
                                "getCarAdapterManager", String.class);
                        Object adapter = getAdapter.invoke(carAdapterMgr, carAdapterKey);
                        if (isDiLink5BinderServiceAlive(adapter)) {
                            return adapter;
                        }
                    }
                }
            } catch (Throwable failure) {
                logger.debug("DiLink 5 CarAdapterManager fallback failed for '"
                        + carAdapterKey + "': " + failure.getMessage());
            }
        }
        return null;
    }

    /**
     * Bounded wait for a live DiLink 5 car adapter after {@code VehicleActuatorService} cold-starts
     * (typically ~100-250 ms for {@code CarAdapterService} bind completion).
     */
    public static Object awaitDiLink5CarAdapter(
            Context context,
            Object deviceHint,
            String tsAdapterGetter,
            String carAdapterKey,
            int maxAttempts,
            long sleepMs) {
        if (context == null || maxAttempts <= 0) return null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Object adapter = acquireDiLink5CarAdapter(
                    context, deviceHint, tsAdapterGetter, carAdapterKey);
            if (adapter != null) return adapter;
            if (VehicleActuatorBridge.isDiLink5RequestExpired()) break;
            if (sleepMs > 0L && attempt + 1 < maxAttempts) {
                android.os.SystemClock.sleep(sleepMs);
            }
        }
        return null;
    }

    /**
     * DiLink 5 returns success-shaped zeroes from several setters while the
     * underlying vehicle adapter is still disconnected. Check the public TS
     * manager before treating those writes as accepted.
     */
    public static boolean isDiLink5AdapterReady(
            Context context, Object device, String adapterGetter) {
        if (context == null || device == null
                || VehicleActuatorBridge.isDiLink5RequestExpired()) {
            return false;
        }
        try {
            ensureHiddenApiBypass();
            com.overdrive.app.byd.dilink5.Dilink5SdkInjector.ensure(context);
            Class<?> managerClass = Class.forName(
                    "android.hardware.bydauto.TsManagerImpl",
                    true,
                    device.getClass().getClassLoader());
            Method getInstance =
                    managerClass.getMethod("getInstance", Context.class);
            Context application = context.getApplicationContext();
            Object manager = getInstance.invoke(
                    null, application != null ? application : context);
            return adapterManagerReady(manager, adapterGetter);
        } catch (Throwable failure) {
            logger.debug("DiLink 5 adapter probe failed for "
                    + adapterGetter + ": " + failure.getMessage());
            return false;
        }
    }

    private static boolean registerRetainedListener(
            Object device, Object listener, Method register, int[] featureIds)
            throws Exception {
        invokeRegister(device, listener, register, featureIds);
        Class<?> listenerType = register.getParameterTypes()[0];
        Method unregister = findUnregisterMethod(device.getClass(), listenerType);
        retainedListeners.put(device,
                new RetainedListener(device, listener, register, unregister, featureIds));
        return true;
    }

    private static void invokeRegister(
            Object device, Object listener, Method register, int[] featureIds)
            throws Exception {
        if (featureIds == null) {
            register.invoke(device, listener);
        } else {
            register.invoke(device, listener, featureIds);
        }
    }

    private static boolean registerLegacyAdditive(
            Object device,
            Object listener,
            Class<?> listenerType,
            int[] featureIds,
            boolean retryWithEmptyIds,
            String label) throws Exception {
        Method registerWithIds = findRegisterMethodWithIds(device.getClass(), listenerType);
        boolean legacyFilteredRegistered = false;
        if (registerWithIds != null) {
            try {
                registerWithIds.invoke(device, listener, featureIds);
                legacyFilteredRegistered = true;
            } catch (Exception firstFailure) {
                logger.debug(label + " filtered registration failed: "
                        + firstFailure.getMessage());
            }
            if (!legacyFilteredRegistered && retryWithEmptyIds && featureIds.length > 0) {
                try {
                    registerWithIds.invoke(device, listener, new int[0]);
                    legacyFilteredRegistered = true;
                } catch (Exception fallbackFailure) {
                    logger.debug(label + " subscribe-all registration failed: "
                            + fallbackFailure.getMessage());
                }
            }
        }
        Method register = findRegisterMethod(device.getClass(), listenerType);
        if (register != null) {
            register.invoke(device, listener);
            return true;
        }
        return legacyFilteredRegistered;
    }

    static int refreshRetainedListeners(Object... devices) {
        if (devices == null) return 0;
        int refreshed = 0;
        for (Object device : devices) {
            RetainedListener retained = retainedListeners.get(device);
            if (retained != null && retained.refresh()) refreshed++;
        }
        return refreshed;
    }

    static int unregisterRetainedListeners(Object... devices) {
        if (devices == null) return 0;
        int removed = 0;
        for (Object device : devices) {
            RetainedListener retained = retainedListeners.get(device);
            if (retained != null && retained.detach()) {
                retainedListeners.remove(device);
                removed++;
            }
        }
        return removed;
    }

    static boolean hasDetachedRetainedListeners(Object... devices) {
        if (devices == null) return false;
        for (Object device : devices) {
            RetainedListener retained = retainedListeners.get(device);
            if (retained != null && retained.isDetached()) return true;
        }
        return false;
    }

    /**
     * Get a BYD device singleton via reflection.
     * Returns null if the device class doesn't exist or getInstance fails.
     */
    public static Object getDevice(String className, Context context) {
        return acquireDevice(className, context, null);
    }

    private static Object acquireDevice(
            String className, Context context, Throwable[] failureOut) {
        try {
            boolean dilink5 =
                    com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
            if (dilink5) {
                com.overdrive.app.byd.dilink5.Dilink5SdkInjector.ensure(context);
            }
            Class<?> cls = Class.forName(
                    className,
                    true,
                    dilink5
                            ? com.overdrive.app.byd.dilink5.Dilink5SdkInjector.class
                                    .getClassLoader()
                            : BydDeviceHelper.class.getClassLoader());
            Object device = invokeGetInstance(
                    cls, dilink5 ? withBydPermissionBypass(context) : context);
            int outcome = device != null ? 1 : 0;
            Integer previous = lastDeviceOutcome.put(className, outcome);
            if (previous == null || previous != outcome) {
                logger.info((device != null ? "Device OK: " : "Device NULL: ")
                        + cls.getSimpleName());
            }
            return device;
        } catch (Throwable e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            logger.debug("Device init failed: " + className + " — "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            lastDeviceOutcome.put(className, -1);
            if (failureOut != null) failureOut[0] = cause;
        }
        return null;
    }

    // DiLink 5's OTA SDK starts a HandlerThread and then binds a service, which a non-app process
    // can never do; it caches nothing on failure, so every retry leaks one live thread.
    private static final String DI5_OTA_DEVICE = "android.hardware.bydauto.ota.BYDAutoOtaDevice";
    private static final Object di5OtaLock = new Object();
    private static Throwable di5OtaBindRefusal; // guarded by di5OtaLock

    /**
     * {@code cls.getInstance(context)}. In a DiLink 5 non-app process the OTA constructor runs until
     * its bind is refused once; later calls rethrow that failure instead of constructing again.
     */
    public static Object invokeGetInstance(Class<?> cls, Context context) throws Exception {
        if (!DI5_OTA_DEVICE.equals(cls.getName())
                || !com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                || android.os.Process.myUid() >= android.os.Process.FIRST_APPLICATION_UID) {
            return cls.getMethod("getInstance", Context.class).invoke(null, context);
        }
        synchronized (di5OtaLock) {
            if (di5OtaBindRefusal != null) throw new InvocationTargetException(di5OtaBindRefusal);
            try {
                return cls.getMethod("getInstance", Context.class).invoke(null, context);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof SecurityException) {
                    di5OtaBindRefusal = failure.getCause();
                    logger.warn("DI5: " + cls.getSimpleName()
                            + " cannot bind outside the app process; not constructed again");
                }
                throw failure;
            }
        }
    }

    // Last logged getInstance outcome per class (1 OK, 0 NULL, -1 failed); 5 s pollers
    // log only transitions.
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer>
            lastDeviceOutcome = new java.util.concurrent.ConcurrentHashMap<>();

    // A DiLink 5 daemon (non-app UID) cannot bind the SDK's vehicle services, and the SDK re-runs
    // that constructor on every getInstance until one succeeds. Display pollers only: a backed-off
    // null must never feed a control or arming decision.
    private static final long POLLED_DEVICE_BACKOFF_MIN_MS = 5_000L;
    private static final long POLLED_DEVICE_BACKOFF_MAX_MS = 60_000L;
    // key = class@context identity -> {consecutive failures, retry-at, last success}, elapsedRealtime.
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]>
            polledDeviceState = new java.util.concurrent.ConcurrentHashMap<>();

    /** {@link #getDevice} for display pollers; backs off failed DiLink 5 daemon acquisitions. */
    public static Object getPolledDevice(String className, Context context) {
        if (className == null
                || !com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                || android.os.Process.myUid() >= android.os.Process.FIRST_APPLICATION_UID) {
            return getDevice(className, context);
        }
        String key = className + '@' + System.identityHashCode(context);
        long startedAt = android.os.SystemClock.elapsedRealtime();
        long[] state = polledDeviceState.get(key);
        if (state != null && startedAt < state[1]) return null;
        Throwable[] failure = new Throwable[1];
        Object device = acquireDevice(className, context, failure);
        recordPolledDevice(key, className, device != null, startedAt, failure[0]);
        return device;
    }

    private static void recordPolledDevice(String key, String className, boolean acquired,
                                           long startedAt, Throwable cause) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (acquired) {
            polledDeviceState.compute(key, (k, previous) -> new long[] {
                    0L, 0L, previous == null ? now : Math.max(previous[2], now)});
            return;
        }
        long[] next = polledDeviceState.compute(key, (k, previous) -> {
            // An acquisition that finished after this attempt started wins.
            if (previous != null && previous[2] >= startedAt) return previous;
            // A poller that went quiet for longer than the cap starts a fresh streak.
            boolean freshStreak = previous == null
                    || now - previous[1] > POLLED_DEVICE_BACKOFF_MAX_MS;
            long failures = freshStreak ? 1L : previous[0] + 1L;
            long delay = Math.min(POLLED_DEVICE_BACKOFF_MAX_MS,
                    POLLED_DEVICE_BACKOFF_MIN_MS << (int) Math.min(failures - 1L, 4L));
            return new long[] {failures, now + delay, previous == null ? 0L : previous[2]};
        });
        if (next[0] == 1L && next[1] > now) {
            logger.warn("DI5 daemon cannot acquire " + className + " ("
                    + (cause != null
                            ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                            : "getInstance returned null")
                    + "); polling backs off");
        }
    }

    /**
     * Wrap an app context so BYD's SDK-side permission checks see the BYD permissions as granted.
     *
     * <p>Some OEM SDK clients use a custom {@code Application}: BYD device
     * {@code getInstance(Context)} methods enforce signature permissions directly on the supplied
     * Context before they create their singleton. OverDrive is not platform-signed, so a raw app
     * context fails before any Binder/HAL call is attempted. The wrapper is opt-in and grants only
     * {@code android.permission.BYD*}; every unrelated Android permission still delegates to the
     * real context. Returning the wrapper from {@link Context#getApplicationContext()} prevents
     * the SDK from normalizing back to the unwrapped context before a later permission check.
     */
    public static Context withBydPermissionBypass(Context context) {
        if (context == null || context instanceof BydPermissionContext) return context;
        return new BydPermissionContext(context);
    }

    static boolean isBydPermissionName(String permission) {
        return permission != null && permission.startsWith("android.permission.BYD");
    }

    private static final class BydPermissionContext extends ContextWrapper {
        BydPermissionContext(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return isBydPermissionName(permission)
                    ? PackageManager.PERMISSION_GRANTED
                    : super.checkPermission(permission, pid, uid);
        }

        @Override
        public int checkCallingPermission(String permission) {
            return isBydPermissionName(permission)
                    ? PackageManager.PERMISSION_GRANTED
                    : super.checkCallingPermission(permission);
        }

        @Override
        public int checkCallingOrSelfPermission(String permission) {
            return isBydPermissionName(permission)
                    ? PackageManager.PERMISSION_GRANTED
                    : super.checkCallingOrSelfPermission(permission);
        }

        @Override
        public int checkSelfPermission(String permission) {
            return isBydPermissionName(permission)
                    ? PackageManager.PERMISSION_GRANTED
                    : super.checkSelfPermission(permission);
        }

        @Override
        public void enforcePermission(
                String permission, int pid, int uid, String message) {
            if (!isBydPermissionName(permission)) {
                super.enforcePermission(permission, pid, uid, message);
            }
        }

        @Override
        public void enforceCallingPermission(String permission, String message) {
            if (!isBydPermissionName(permission)) {
                super.enforceCallingPermission(permission, message);
            }
        }

        @Override
        public void enforceCallingOrSelfPermission(String permission, String message) {
            if (!isBydPermissionName(permission)) {
                super.enforceCallingOrSelfPermission(permission, message);
            }
        }
    }

    public static boolean verifyStartupAppAccess(
            Object device, String packageName, int attempts, long retryMs) {
        if (device == null || packageName == null || packageName.isEmpty()
                || attempts <= 0) {
            return false;
        }
        try {
            Method getter = device.getClass().getMethod(
                    "getStartupAppEnable", String.class);
            Method setter = device.getClass().getMethod(
                    "setStartupAppEnable", String.class, Boolean.TYPE);
            for (int attempt = 0; attempt < attempts; attempt++) {
                try {
                    if (Boolean.TRUE.equals(getter.invoke(device, packageName))) {
                        return true;
                    }
                    Object write = setter.invoke(device, packageName, true);
                    if (write instanceof Number
                            && ((Number) write).intValue() >= 0
                            && Boolean.TRUE.equals(
                                    getter.invoke(device, packageName))) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // The settings adapter connects asynchronously; retry until ready.
                }
                if (retryMs > 0L && attempt + 1 < attempts) {
                    android.os.SystemClock.sleep(retryMs);
                }
            }
        } catch (Throwable failure) {
            logger.debug("Startup app API unavailable: "
                    + failure.getMessage());
        }
        return false;
    }

    /**
     * Invoke the manager-level integer setter without requiring a per-device singleton.
     *
     * <p>The connected framework's protected
     * {@code AbsBYDAutoDevice.set(deviceType, featureId, value)} implementation is exactly this:
     * {@code BYDAutoDeviceManager.setInt(...)}. This fallback is therefore useful when a device's
     * constructor fails before returning a handle, while preserving the same HAL command.
     */
    public static int callManagerSetInt(
            Context context, int deviceType, int featureId, int value) {
        if (context == null || VehicleActuatorBridge.isDiLink5RequestExpired()) {
            return Integer.MIN_VALUE;
        }
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            com.overdrive.app.byd.dilink5.Dilink5SdkInjector.ensure(context);
        }
        try {
            Class<?> managerClass =
                    Class.forName("android.hardware.bydauto.BYDAutoDeviceManager");
            Method getInstance = managerClass.getMethod("getInstance", Context.class);
            Object manager = getInstance.invoke(
                    null,
                    com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                            ? withBydPermissionBypass(context)
                            : context);
            if (manager == null) {
                logger.debug("callManagerSetInt: BYDAutoDeviceManager unavailable");
                return Integer.MIN_VALUE;
            }
            Method setInt = findMethodCached(
                    manager,
                    "setInt",
                    managerSetIntMethodCache,
                    int.class,
                    int.class,
                    int.class);
            if (setInt == null) {
                logger.debug("callManagerSetInt: setInt(int,int,int) unavailable");
                return Integer.MIN_VALUE;
            }
            Object result = setInt.invoke(manager, deviceType, featureId, value);
            return result instanceof Number
                    ? ((Number) result).intValue() : Integer.MIN_VALUE;
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            logger.debug("callManagerSetInt failed for device=" + deviceType
                    + ", feature=0x" + Integer.toHexString(featureId)
                    + ", value=" + value + " — " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Call a no-arg getter method on a device. Returns null on failure.
     *
     * Uses a per-(Class, methodName) Method cache keyed on the device's
     * runtime Class — proxy classes (e.g. BYDAuto*Device$Stub$Proxy) are
     * process-stable, so a cached Method survives binder service restarts
     * that swap the underlying instance.
     */
    public static Object callGetter(Object device, String methodName) {
        if (device == null) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicNoArgMethodCache, NO_PARAMS);
        if (m == null) {
            // getMethod missed — try the declared-method walk (private/package
            // visibility on the proxy or its supertypes).
            return callGetterDeclared(device, methodName);
        }
        try {
            return m.invoke(device);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug("Getter " + methodName + " threw: " + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug("Getter " + methodName + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a getter with one int parameter.
     */
    public static Object callGetter(Object device, String methodName, int param) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicIntMethodCache, INT_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, param);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug("Getter " + methodName + "(" + param + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug("Getter " + methodName + "(" + param + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a method with one int parameter.
     * Used for SDK methods like voiceCtlMoonRoof(int), voiceCtlSunshadePanel(int).
     */
    public static Object callMethod(Object device, String methodName, int param1) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicIntMethodCache, INT_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, param1);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + param1 + ") threw: " +
                    (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + param1 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Invoke the first available public method taking one int. Aliases are tried only when a
     * method is absent; once a method exists, a thrown invocation is a real failure and no second
     * command is issued.
     */
    public static boolean invokeFirstAvailableIntMethod(
            Object device, int value, String... methodNames) {
        if (device == null || methodNames == null
                || VehicleActuatorBridge.isDiLink5RequestExpired()) {
            return false;
        }
        for (String methodName : methodNames) {
            if (methodName == null || methodName.isEmpty()) continue;
            Method method = lookupPublicMethodCached(
                    device.getClass(), methodName, publicIntMethodCache, INT_PARAMS);
            if (method == null) continue;
            try {
                Object result = method.invoke(device, value);
                if (result instanceof Boolean) return (Boolean) result;
                return !(result instanceof Number)
                        || ((Number) result).intValue() == 0;
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                logger.debug(methodName + "(" + value + ") threw: "
                        + (cause != null
                        ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                        : "unknown"));
                return false;
            } catch (Exception e) {
                logger.debug(methodName + "(" + value + ") failed: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    /**
     * Call a method with two int parameters.
     * Used for SDK methods like setAcWindLevel(int, int), setAcWindMode(int, int),
     * setSeatHeatingState(int, int), setSeatVentilatingState(int, int).
     */
    public static Object callMethod(Object device, String methodName, int param1, int param2) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicIntIntMethodCache, INT_INT_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, param1, param2);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + param1 + ", " + param2 + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + param1 + ", " + param2 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a method with four int parameters.
     * Used for SDK methods like setAcTemperature(int zone, int temp, int, int),
     * setAllWindowState(int lf, int rf, int lr, int rr).
     */
    public static Object callMethod(Object device, String methodName, int p1, int p2, int p3) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicInt3MethodCache, INT3_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, p1, p2, p3);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }
    public static Object callMethod(Object device, String methodName, int p1, int p2, int p3, int p4) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicInt4MethodCache, INT4_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, p1, p2, p3, p4);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ", " + p4 + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ", " + p4 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /** Call a method taking one int[] (e.g. DiLink 5 setWindowControlState(int[])). */
    public static Object callMethod(Object device, String methodName, int[] values) {
        if (device == null || values == null
                || VehicleActuatorBridge.isDiLink5RequestExpired()) {
            return null;
        }
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicIntArrayMethodCache, INT_ARRAY_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, (Object) values.clone());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + java.util.Arrays.toString(values) + ") threw: "
                    + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + java.util.Arrays.toString(values) + ") failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    private static Object callGetterDeclared(Object device, String methodName) {
        Class<?> cls = device.getClass();
        Method m = lookupDeclaredNoArgCached(cls, methodName);
        if (m == null) return null;
        try {
            return m.invoke(device);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug("DeclaredGetter " + methodName + " threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug("DeclaredGetter " + methodName + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call the generic get(int[], Class) method on a BYD device.
     * This is the correct SDK signature for reading feature ID values.
     * Falls back to get(int, int) if the array signature isn't found.
     *
     * <p><b>Primitive vs wrapper Class.</b> The HAL dispatches on the EXACT {@code Class}
     * object passed as the second argument, and it recognises the PRIMITIVE ones
     * ({@code Double.TYPE}) — not the wrappers ({@code Double.class}), which are a different
     * {@code Class} instance entirely. The OEM reference app proves this: its feature reader
     * probes {@code {Double.TYPE, Float.TYPE, Integer.TYPE, Long.TYPE}} in a loop and catches
     * {@code IllegalArgumentException} to advance to the next — you only write that if the
     * HAL rejects a type it doesn't match. Callers here have historically passed the wrapper,
     * so reads that should have worked returned null (the "PHEV charging power never reads"
     * bug). {@link #normalizePrimitive} maps a wrapper to its primitive so every existing
     * call site is fixed without touching it, and {@link #callGetProbing} tries the OEM's
     * full ladder when one type isn't enough.
     */
    public static Object callGet(Object device, int featureId, Class<?> returnType) {
        if (device == null) return null;
        try {
            Method m = findGetMethod(device);
            if (m != null) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2 && params[0] == int[].class) {
                    return m.invoke(device, new int[]{featureId}, normalizePrimitive(returnType));
                } else if (params.length == 2 && params[0] == int.class) {
                    return m.invoke(device, featureId, 0);
                }
            }
        } catch (Exception e) {
            logger.debug("callGet failed for id=" + featureId + " — " + e.getMessage());
        }
        return null;
    }

    /** DiLink 5 feature reads whose {@code Class} token must be BYDAutoEventValue itself. */
    public static Object callGetEventValue(Object device, int featureId) {
        if (device == null) return null;
        ensureRuntimeSdk();
        try {
            Class<?> eventValueClass =
                    Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            return callGet(device, featureId, eventValueClass);
        } catch (Throwable e) {
            logger.debug("callGetEventValue failed for id=0x"
                    + Integer.toHexString(featureId) + " — " + e.getMessage());
            return null;
        }
    }

    /**
     * Read a feature ID through the {@code get(int deviceType, int featureId)} overload with
     * an EXPLICIT device type, rather than letting {@link #callGet} pick a signature.
     *
     * <p>Needed because {@link #callGet} prefers the {@code get(int[], Class)} form and only
     * falls back to {@code (int,int)} when the array form is absent entirely — so on a device
     * exposing both, the {@code (int,int)} pairing is never attempted. This helper lets callers
     * select an explicit device-type channel, including the direct instrument health read.
     *
     * @return the raw returned object (usually a boxed number), or null when unavailable.
     */
    public static Object callGetWithDeviceType(Object device, int deviceType, int featureId) {
        if (device == null) return null;
        try {
            Method m = lookupPublicMethodCached(device.getClass(), "get",
                    publicIntIntMethodCache, INT_INT_PARAMS);
            if (m == null) return null;
            return m.invoke(device, deviceType, featureId);
        } catch (Exception e) {
            logger.debug("callGetWithDeviceType(" + deviceType + ", 0x"
                    + Integer.toHexString(featureId) + ") failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Map a boxed wrapper Class to its primitive {@code TYPE}, leaving anything else
     * untouched. The BYD HAL's {@code get(int[], Class)} matches primitives only (see
     * {@link #callGet}), so this makes a wrapper argument work instead of silently reading
     * nothing.
     */
    private static Class<?> normalizePrimitive(Class<?> t) {
        if (t == Double.class)  return Double.TYPE;
        if (t == Integer.class) return Integer.TYPE;
        if (t == Float.class)   return Float.TYPE;
        if (t == Long.class)    return Long.TYPE;
        if (t == Short.class)   return Short.TYPE;
        if (t == Boolean.class) return Boolean.TYPE;
        return t;
    }

    /**
     * Read a feature ID trying each primitive type in turn until one yields a usable value —
     * the OEM reference app's own strategy ({@code {Double.TYPE, Float.TYPE, Integer.TYPE,
     * Long.TYPE}} with an {@code IllegalArgumentException} catch per attempt).
     *
     * <p>Use this for values whose HAL-side width is not known for the trim in hand: the same
     * feature ID is a double on one firmware and an int on another, and guessing wrong reads
     * nothing. Returns the first non-null result (which may be a {@code BYDAutoEventValue} —
     * pass it through {@link #getDoubleValue} / {@link #getIntValue} as usual), or null when
     * no type worked.
     */
    public static Object callGetProbing(Object device, int featureId) {
        return callGetProbing(device, featureId, false);
    }

    /**
     * Integer-first variant of {@link #callGetProbing(Object, int)}, for callers that extract
     * the result with {@link #getIntValue}.
     *
     * <p><b>Why the order is not cosmetic.</b> {@code BYDAutoEventValue} carries INDEPENDENT
     * {@code intValue} / {@code doubleValue} fields, and the HAL fills only the one matching the
     * requested type. {@code getIntValue} always reads {@code intValue}. So if a Double-first
     * ladder wins on a feature the caller then reads as an int, {@code intValue} is still at its
     * default 0 — and 0 is IN BAND for some scales (a raw cell-temp code of 0 means -40 °C),
     * so it is not rejected and a plausible-looking wrong number is published. Asking for the
     * width you intend to read keeps the two in agreement.
     *
     * @param intFirst true → try {@code Integer.TYPE} before the wider types.
     */
    public static Object callGetProbing(Object device, int featureId, boolean intFirst) {
        if (device == null) return null;
        Method m = findGetMethod(device);
        if (m == null) return null;
        Class<?>[] params = m.getParameterTypes();
        // Only the (int[], Class) form takes a type argument; the (int,int) form has nothing
        // to probe, so a single call is all there is.
        if (!(params.length == 2 && params[0] == int[].class)) {
            try {
                if (params.length == 2 && params[0] == int.class) return m.invoke(device, featureId, 0);
            } catch (Exception e) {
                logger.debug("callGetProbing(int,int) failed id=0x" + Integer.toHexString(featureId)
                        + " — " + e.getMessage());
            }
            return null;
        }
        Class<?>[] ladder = intFirst
                ? new Class<?>[]{ Integer.TYPE, Long.TYPE, Double.TYPE, Float.TYPE }
                : new Class<?>[]{ Double.TYPE, Float.TYPE, Integer.TYPE, Long.TYPE };
        for (Class<?> t : ladder) {
            try {
                Object r = m.invoke(device, new int[]{featureId}, t);
                if (r != null) return r;
            } catch (IllegalArgumentException iae) {
                // HAL refused this width — try the next, exactly as the OEM reader does.
            } catch (Exception e) {
                logger.debug("callGetProbing id=0x" + Integer.toHexString(featureId)
                        + " type=" + t.getSimpleName() + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Extract intValue from a BYDAutoEventValue object.
     */
    public static int getIntValue(Object eventValue) {
        if (eventValue == null) return Integer.MIN_VALUE;
        try {
            // Direct field access — BYDAutoEventValue.intValue is public
            Field f = eventValue.getClass().getField("intValue");
            return f.getInt(eventValue);
        } catch (Exception e) {
            // Try as Integer directly (some get() calls return boxed primitives)
            if (eventValue instanceof Integer) return (Integer) eventValue;
            if (eventValue instanceof Number) return ((Number) eventValue).intValue();
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Extract doubleValue from a BYDAutoEventValue object.
     */
    public static double getDoubleValue(Object eventValue) {
        if (eventValue == null) return Double.NaN;
        try {
            Field f = eventValue.getClass().getField("doubleValue");
            return f.getDouble(eventValue);
        } catch (Exception e) {
            if (eventValue instanceof Double) return (Double) eventValue;
            if (eventValue instanceof Number) return ((Number) eventValue).doubleValue();
        }
        // Some feature ids are carried as a 32-bit float rather than a double. Without this the
        // read returned NaN and the channel looked absent on those trims — indistinguishable from
        // a dead getter, which is exactly the ambiguity that makes a missing value hard to diagnose.
        try {
            Field ff = eventValue.getClass().getField("floatValue");
            return ff.getFloat(eventValue);
        } catch (Exception ignored) { /* not a float-carrying event */ }
        return Double.NaN;
    }

    /**
     * Extract stringValue from a BYDAutoEventValue object.
     */
    public static String getStringValue(Object eventValue) {
        if (eventValue == null) return null;
        try {
            Field f = eventValue.getClass().getField("stringValue");
            return (String) f.get(eventValue);
        } catch (Exception e) {
            if (eventValue instanceof String) return (String) eventValue;
        }
        return null;
    }

    /**
     * Resolve the IBYDAutoListener-derived interface for a given device class
     * by inspecting registerListener parameter types. Some devices (e.g. ADAS)
     * declare a derived interface (IBYDAutoADASListener) instead of the base
     * IBYDAutoListener — Class.forName on the base name would still find a
     * class, but the proxy must implement the device-specific subtype or
     * registerListener.invoke fails with IllegalArgumentException.
     */
    private static Class<?> getListenerInterface(Class<?> cls, String listenerInterfaceName) {
        if (cls != null) {
            Method[] methods = cls.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().equals("registerListener")) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length == 1) {
                        Class<?>[] interfaces = parameterTypes[0].getInterfaces();
                        if (interfaces.length == 1 && interfaces[0].getName().equals(listenerInterfaceName)) {
                            return interfaces[0];
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Register a listener on a device using IBYDAutoListener interface.
     * Creates a dynamic proxy that forwards all calls to the callback.
     */
    public static boolean registerListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            String listenerInterfaceName = "android.hardware.IBYDAutoListener";
            Class<?> iListener = getListenerInterface(device.getClass(), listenerInterfaceName);
            if (iListener == null) {
                iListener = Class.forName(listenerInterfaceName);
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                iListener.getClassLoader(),
                new Class<?>[]{iListener},
                (p, method, args) -> {
                    String name = method.getName();
                    if ("hashCode".equals(name)) return System.identityHashCode(p);
                    if ("equals".equals(name)) return p == args[0];
                    if ("toString".equals(name)) return "BydListener";
                    try {
                        callback.onCallback(name, args);
                    } catch (Exception e) {
                        logger.debug("Listener callback error: " + name + " — " + e.getMessage());
                    }
                    return null;
                }
            );

            // Try registerListener(IBYDAutoListener)
            Method register = findRegisterMethod(device.getClass(), iListener);
            if (register != null) {
                register.invoke(device, proxy);
                return true;
            }
        } catch (Exception e) {
            logger.debug("registerListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a listener with specific feature IDs.
     */
    public static boolean registerListener(Object device, int[] featureIds, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            String listenerInterfaceName = "android.hardware.IBYDAutoListener";
            Class<?> iListener = getListenerInterface(device.getClass(), listenerInterfaceName);
            if (iListener == null) {
                iListener = Class.forName(listenerInterfaceName);
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                iListener.getClassLoader(),
                new Class<?>[]{iListener},
                (p, method, args) -> {
                    String name = method.getName();
                    if ("hashCode".equals(name)) return System.identityHashCode(p);
                    if ("equals".equals(name)) return p == args[0];
                    if ("toString".equals(name)) return "BydListener";
                    try {
                        callback.onCallback(name, args);
                    } catch (Exception e) {
                        logger.debug("Listener callback error: " + name + " — " + e.getMessage());
                    }
                    return null;
                }
            );

            // Try registerListener(IBYDAutoListener, int[])
            Method register = findRegisterMethodWithIds(device.getClass(), iListener);
            if (register != null) {
                register.invoke(device, proxy, featureIds);
                return true;
            }
            // Fallback to no-filter registration
            return registerListener(device, callback);
        } catch (Exception e) {
            logger.debug("registerListener(ids) failed: " + e.getMessage());
        }
        return false;
    }

    /** DiLink 5 speed callbacks are concrete methods on the typed listener. */
    public static boolean registerSpeedListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.speed.AbsBYDAutoSpeedListener listener =
                    new android.hardware.bydauto.speed.AbsBYDAutoSpeedListener() {
                        @Override
                        public void onSpeedChanged(double speed) {
                            invokeCallback(callback, "onSpeedChanged", new Object[]{speed});
                        }

                        @Override
                        public void onSpeedChanged(int speed) {
                            invokeCallback(callback, "onSpeedChanged", new Object[]{speed});
                        }

                        @Override
                        public void onSpeedValueChanged(double speed) {
                            invokeCallback(callback, "onSpeedChanged", new Object[]{speed});
                        }

                        @Override
                        public void onCurrentSpeedChanged(double speed) {
                            invokeCallback(callback, "onSpeedChanged", new Object[]{speed});
                        }

                        @Override
                        public void onCurrentSpeedChanged(int speed) {
                            invokeCallback(callback, "onSpeedChanged", new Object[]{speed});
                        }

                        @Override
                        public void onAccelerateDeepnessChanged(int value) {
                            invokeCallback(callback, "onAccelerateDeepnessChanged",
                                    new Object[]{value});
                        }

                        @Override
                        public void onBrakeDeepnessChanged(int value) {
                            invokeCallback(callback, "onBrakeDeepnessChanged",
                                    new Object[]{value});
                        }
                    };
            Method register = findRegisterMethod(
                    device.getClass(),
                    android.hardware.bydauto.speed.AbsBYDAutoSpeedListener.class);
            if (register != null) {
                return registerRetainedListener(device, listener, register, null);
            }
        } catch (LinkageError e) {
            logger.debug("registerSpeedListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerSpeedListener failed: " + e.getMessage());
        }
        return false;
    }

    /** DiLink 5 event-only HV bus and motor telemetry. */
    public static boolean registerCollectDataListener(
            Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.collectdata.AbsBYDAutoCollectDataListener listener =
                    new android.hardware.bydauto.collectdata.AbsBYDAutoCollectDataListener() {
                        @Override
                        public void onMotorMCUGeneratrixVolt(int front, int rear) {
                            invokeCallback(callback, "onMotorMCUGeneratrixVolt",
                                    new Object[]{front, rear});
                        }

                        @Override
                        public void onMotorMCUGeneratrixCurrent(int front, int rear) {
                            invokeCallback(callback, "onMotorMCUGeneratrixCurrent",
                                    new Object[]{front, rear});
                        }

                        @Override
                        public void onDriverMotorTemperature(int front, int rear) {
                            invokeCallback(callback, "onDriverMotorTemperature",
                                    new Object[]{front, rear});
                        }

                        @Override
                        public void onDriverMotorSpeed(int front, int rear) {
                            invokeCallback(callback, "onDriverMotorSpeed",
                                    new Object[]{front, rear});
                        }

                        @Override
                        public void onDriverMotorTorque(int front, int rear) {
                            invokeCallback(callback, "onDriverMotorTorque",
                                    new Object[]{front, rear});
                        }
                    };
            Method register = findRegisterMethod(
                    device.getClass(),
                    android.hardware.bydauto.collectdata.AbsBYDAutoCollectDataListener.class);
            if (register != null) {
                return registerRetainedListener(device, listener, register, null);
            }
        } catch (LinkageError e) {
            logger.debug("registerCollectDataListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerCollectDataListener failed: " + e.getMessage());
        }
        return false;
    }

    /** DiLink 5 radar probe callbacks are concrete methods on the typed listener. */
    public static boolean registerRadarListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.radar.AbsBYDAutoRadarListener listener =
                    new android.hardware.bydauto.radar.AbsBYDAutoRadarListener() {
                        @Override
                        public void onRadarProbeStateChanged(int area, int state) {
                            invokeCallback(callback, "onRadarProbeStateChanged",
                                    new Object[]{area, state});
                        }

                        @Override
                        public void onReverseRadarSwitchStateChanged(int state) {
                        }
                    };
            Method register = findRegisterMethod(
                    device.getClass(),
                    android.hardware.bydauto.radar.AbsBYDAutoRadarListener.class);
            if (register != null) {
                return registerRetainedListener(device, listener, register, null);
            }
        } catch (LinkageError e) {
            logger.debug("registerRadarListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerRadarListener failed: " + e.getMessage());
        }
        return false;
    }

    /** DiLink 5 regenerative-braking strength callback. */
    public static boolean registerSettingListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.setting.AbsBYDAutoSettingListener listener =
                    new android.hardware.bydauto.setting.AbsBYDAutoSettingListener() {
                        @Override
                        public void onEnergyFeedbackStrengthChanged(int strength) {
                            invokeCallback(callback, "onEnergyFeedbackStrengthChanged",
                                    new Object[]{strength});
                        }

                        @Override
                        public void onRecoverOrSaveParamsChanged(
                                int position, int location, int state, int code) {
                            invokeCallback(callback, "onRecoverOrSaveParamsChanged",
                                    new Object[]{position, location, state, code});
                        }

                        @Override
                        public void onCpdImsSwitchStateChanged(int state) {
                            invokeCallback(callback, "onCpdImsSwitchStateChanged",
                                    new Object[]{state});
                        }

                        @Override
                        public void onDataChanged(android.hardware.IBYDAutoEvent event) {
                            invokeCallback(callback, "onDataChanged", new Object[]{event});
                        }

                        @Override
                        public void onDataEventChanged(
                                int featureId,
                                android.hardware.bydauto.BYDAutoEventValue value) {
                            invokeCallback(callback, "onDataEventChanged",
                                    new Object[]{featureId, value});
                        }
                    };
            Method register = findRegisterMethod(
                    device.getClass(),
                    android.hardware.bydauto.setting.AbsBYDAutoSettingListener.class);
            if (register != null) {
                return registerRetainedListener(device, listener, register, null);
            }
        } catch (LinkageError e) {
            logger.debug("registerSettingListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerSettingListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed (device-specific) listener using a hand-rolled concrete
     * subclass of an abstract listener class. The BYD framework provides the
     * actual abstract class at runtime via the system classloader, and our
     * subclass extends it transparently.
     *
     * Why not Proxy.newProxyInstance: java.lang.reflect.Proxy only works with
     * interfaces, but AbsBYDAutoBodyworkListener / AbsBYDAutoDoorLockListener
     * are abstract classes — Proxy throws "is not an interface" at runtime.
     *
     * Two specialized helpers below cover the two known typed-listener cases.
     * Returns true on successful registration.
     */

    /**
     * Register a typed bodywork listener. Captures door state, window state,
     * and window-open percent callbacks.
     */
    public static boolean registerBodyworkListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener listener =
                new android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener() {
                    @Override
                    public void onDoorStateChanged(int area, int state) {
                        invokeCallback(callback, "onDoorStateChanged", new Object[]{area, state});
                    }
                    @Override
                    public void onWindowStateChanged(int area, int state) {
                        invokeCallback(callback, "onWindowStateChanged", new Object[]{area, state});
                    }
                    @Override
                    public void onWindowOpenPercentChanged(int area, int percent) {
                        invokeCallback(callback, "onWindowOpenPercentChanged", new Object[]{area, percent});
                    }
                    @Override
                    public void onPowerLevelChanged(int level) {
                        invokeCallback(callback, "onPowerLevelChanged", new Object[]{level});
                    }
                };
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            logger.debug("registerBodyworkListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerBodyworkListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerBodyworkListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * A second bodywork listener for the generic feature-id channel, which only the 2-arg
     * overload delivers. Its typed callbacks stay the base no-ops, so the typed listener's
     * door/window events are not duplicated. Not used on DiLink 5.
     */
    public static boolean registerBodyworkEventListener(
            Object device, int featureId, ListenerCallback callback) {
        if (device == null || com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
            return false;
        }
        try {
            android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener listener =
                new android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener() {
                    public void onDataEventChanged(int eventId, android.hardware.bydauto.BYDAutoEventValue value) {
                        invokeCallback(callback, "onDataEventChanged", new Object[]{eventId, value});
                    }
                };
            Method register = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener.class);
            if (register == null) return false;
            try {
                register.invoke(device, listener, new int[]{featureId});
            } catch (Exception filteredFailure) {
                logger.debug("Bodywork event filtered registration failed: "
                    + filteredFailure.getMessage());
                register.invoke(device, listener, new int[0]);
            }
            return true;
        } catch (LinkageError e) {
            logger.debug("registerBodyworkEventListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerBodyworkEventListener failed: " + e.getMessage());
        }
        return false;
    }

    public static boolean registerTyreListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.tyre.AbsBYDAutoTyreListener listener =
                new android.hardware.bydauto.tyre.AbsBYDAutoTyreListener() {
                    @Override
                    public void onTyrePressureValueChanged(int wheel, int value) {
                        invokeCallback(callback, "onTyrePressureValueChanged", new Object[]{wheel, value});
                    }
                    @Override
                    public void onTyrePressureValueByTypeChanged(int wheel, float value) {
                        invokeCallback(callback, "onTyrePressureValueByTypeChanged",
                                new Object[]{wheel, value});
                    }
                    @Override
                    public void onTyrePressureStateChanged(int wheel, int state) {
                        invokeCallback(callback, "onTyrePressureStateChanged", new Object[]{wheel, state});
                    }
                    @Override
                    public void onTyreBatteryValueChanged(int wheel, float value) {
                        invokeCallback(callback, "onTyreBatteryValueChanged", new Object[]{wheel, value});
                    }
                    @Override
                    public void onTyreBatteryValueChanged(int wheel, double value) {
                        invokeCallback(callback, "onTyreBatteryValueChanged", new Object[]{wheel, value});
                    }
                    @Override
                    public void onTyreBatteryStateChanged(int state) {
                        invokeCallback(callback, "onTyreBatteryStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onTyreTemperatureStateChanged(int state) {
                        invokeCallback(callback, "onTyreTemperatureStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onTyreTemperatureValueChanged(int wheel, int value) {
                        invokeCallback(callback, "onTyreTemperatureValueChanged",
                                new Object[]{wheel, value});
                    }
                    @Override
                    public void onTyreAirLeakStateChanged(int wheel, int state) {
                        invokeCallback(callback, "onTyreAirLeakStateChanged", new Object[]{wheel, state});
                    }
                    @Override
                    public void onTyreSignalStateChanged(int wheel, int state) {
                        invokeCallback(callback, "onTyreSignalStateChanged", new Object[]{wheel, state});
                    }
                    @Override
                    public void onTyreSystemStateChanged(int state) {
                        invokeCallback(callback, "onTyreSystemStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onIndirectTyreSystemStateChanged(int state) {
                        invokeCallback(callback, "onIndirectTyreSystemStateChanged", new Object[]{state});
                    }
                    // Legacy generic feature-ID event channel. DI5 uses the
                    // dedicated typed temperature callback above.
                    public void onDataEventChanged(int eventId, android.hardware.bydauto.BYDAutoEventValue value) {
                        invokeCallback(callback, "onDataEventChanged", new Object[]{eventId, value});
                    }
                };

            // Log available registerListener overloads for diagnostics
            Method[] allMethods = device.getClass().getMethods();
            StringBuilder overloads = new StringBuilder();
            for (Method m : allMethods) {
                if ("registerListener".equals(m.getName())) {
                    Class<?>[] params = m.getParameterTypes();
                    overloads.append("  registerListener(");
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) overloads.append(", ");
                        overloads.append(params[i].getSimpleName());
                    }
                    overloads.append(")\n");
                }
            }
            if (overloads.length() > 0) {
                logger.info("TyreDevice registerListener overloads:\n" + overloads);
            }

            if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                return registerLegacyAdditive(
                        device,
                        listener,
                        android.hardware.bydauto.tyre.AbsBYDAutoTyreListener.class,
                        com.overdrive.app.byd.BydFeatureIds.INSTRUMENT_TYRE_TEMP_IDS,
                        true,
                        "Tyre");
            }

            // Use exactly one overload. Some implementations keep a single listener
            // slot, so a second registration can silently replace the first one.
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.tyre.AbsBYDAutoTyreListener.class);
            if (register != null) {
                try {
                    registerRetainedListener(device, listener, register, null);
                    logger.info("Tyre listener registered via 1-arg overload");
                    return true;
                } catch (Exception e) {
                    logger.info("Tyre 1-arg registration failed: " + e.getMessage());
                }
            }

            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.tyre.AbsBYDAutoTyreListener.class);
            if (registerWithIds != null) {
                try {
                    registerRetainedListener(
                            device, listener, registerWithIds, new int[0]);
                    logger.info("Tyre listener registered via subscribe-all fallback");
                    return true;
                } catch (Exception e) {
                    logger.info("Tyre subscribe-all registration failed: " + e.getMessage());
                }
            }
            logger.debug("registerTyreListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerTyreListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerTyreListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed engine listener so onEngineCoolantLevelChanged and
     * onOilLevelChanged actually dispatch (the bare 1-arg
     * registerListener(IBYDAutoListener) registration succeeds but the HAL
     * never invokes the device-specific callbacks on AbsBYDAutoEngineListener
     * subclasses on most firmware).
     *
     * Uses one typed registration. The filtered overload is a fallback when
     * the normal typed overload is absent or rejects the listener.
     */
    public static boolean registerEngineListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.engine.AbsBYDAutoEngineListener listener =
                new android.hardware.bydauto.engine.AbsBYDAutoEngineListener() {
                    @Override
                    public void onEngineSpeedChanged(int value) {
                        invokeCallback(callback, "onEngineSpeedChanged", new Object[]{value});
                    }
                    @Override
                    public void onEngineCoolantLevelChanged(int state) {
                        invokeCallback(callback, "onEngineCoolantLevelChanged", new Object[]{state});
                    }
                    @Override
                    public void onOilLevelChanged(int value) {
                        invokeCallback(callback, "onOilLevelChanged", new Object[]{value});
                    }
                    // Generic feature-ID event channel, same pattern as the
                    // tyre listener. Engine extras (coolant temp, oil temp on
                    // PHEV firmware) tend to land here keyed on feature IDs
                    // we may not have in BYDAutoFeatureIds.Engine.
                    public void onDataEventChanged(int eventId, android.hardware.bydauto.BYDAutoEventValue value) {
                        invokeCallback(callback, "onDataEventChanged", new Object[]{eventId, value});
                    }
                };

            // Diagnostic: dump all registerListener overloads so we know what
            // shapes the HAL exposes on this firmware.
            Method[] allMethods = device.getClass().getMethods();
            StringBuilder overloads = new StringBuilder();
            for (Method m : allMethods) {
                if ("registerListener".equals(m.getName())) {
                    Class<?>[] params = m.getParameterTypes();
                    overloads.append("  registerListener(");
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) overloads.append(", ");
                        overloads.append(params[i].getSimpleName());
                    }
                    overloads.append(")\n");
                }
            }
            if (overloads.length() > 0) {
                logger.info("EngineDevice registerListener overloads:\n" + overloads);
            }

            if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                return registerLegacyAdditive(
                        device,
                        listener,
                        android.hardware.bydauto.engine.AbsBYDAutoEngineListener.class,
                        new int[0],
                        false,
                        "Engine");
            }

            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.engine.AbsBYDAutoEngineListener.class);
            if (register != null) {
                try {
                    register.invoke(device, listener);
                    logger.info("Engine listener registered via 1-arg overload");
                    return true;
                } catch (Exception e) {
                    logger.info("Engine 1-arg registration failed: " + e.getMessage());
                }
            }

            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.engine.AbsBYDAutoEngineListener.class);
            if (registerWithIds != null) {
                try {
                    registerWithIds.invoke(device, listener, new int[0]);
                    logger.info("Engine listener registered via subscribe-all fallback");
                    return true;
                } catch (Exception e) {
                    logger.info("Engine subscribe-all registration failed: " + e.getMessage());
                }
            }
            logger.debug("registerEngineListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerEngineListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerEngineListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register the concrete energy listener. The generic Proxy path cannot receive
     * methods declared only by the concrete class.
     */
    public static boolean registerEnergyListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.energy.AbsBYDAutoEnergyListener listener =
                    new android.hardware.bydauto.energy.AbsBYDAutoEnergyListener() {
                        @Override
                        public void onDataChanged(android.hardware.IBYDAutoEvent event) {
                            invokeCallback(callback, "onDataChanged", new Object[]{event});
                        }

                        @Override
                        public void onEnergyModeChanged(int mode) {
                            invokeCallback(callback, "onEnergyModeChanged", new Object[]{mode});
                        }

                        @Override
                        public void onOperationModeChanged(int mode) {
                            invokeCallback(callback, "onOperationModeChanged", new Object[]{mode});
                        }

                        @Override
                        public void onRoadSurfaceChanged(int mode) {
                            invokeCallback(callback, "onRoadSurfaceChanged", new Object[]{mode});
                        }

                        @Override
                        public void oniTACModeChanged(int mode) {
                            invokeCallback(callback, "oniTACModeChanged", new Object[]{mode});
                        }
                    };
            if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                Method legacyRegister = findRegisterMethod(
                        device.getClass(), android.hardware.IBYDAutoListener.class);
                if (legacyRegister != null) {
                    legacyRegister.invoke(device, listener);
                    return true;
                }
            }
            Class<?> listenerType =
                    android.hardware.bydauto.energy.AbsBYDAutoEnergyListener.class;
            Method register = findRegisterMethod(device.getClass(), listenerType);
            if (register != null) {
                try {
                    return registerRetainedListener(device, listener, register, null);
                } catch (Exception e) {
                    logger.debug("Energy 1-arg registration failed: " + e.getMessage());
                }
            }

            Method registerWithIds =
                    findRegisterMethodWithIds(device.getClass(), listenerType);
            if (registerWithIds != null) {
                try {
                    return registerRetainedListener(
                            device, listener, registerWithIds, new int[0]);
                } catch (Exception e) {
                    logger.debug("Energy subscribe-all registration failed: "
                            + e.getMessage());
                }
            }

            // Compatibility fallback for older SDKs exposing only the marker interface.
            register = findRegisterMethod(device.getClass(), android.hardware.IBYDAutoListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            logger.debug("registerEnergyListener: no registerListener method on "
                + device.getClass().getName());
        } catch (LinkageError e) {
            logger.debug("registerEnergyListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerEnergyListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed door-lock listener. Captures the canonical
     * onDoorLockStatusChanged(area, state) event the BMS emits when the gun is
     * connected and the lock state transitions.
     */
    public static boolean registerDoorLockListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener listener =
                new android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener() {
                    @Override
                    public void onDoorLockStatusChanged(int area, int state) {
                        invokeCallback(callback, "onDoorLockStatusChanged", new Object[]{area, state});
                    }
                };
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            logger.debug("registerDoorLockListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerDoorLockListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerDoorLockListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed charging listener. The bare 1-arg
     * registerListener(IBYDAutoListener) registration succeeds on most
     * firmware but the HAL never invokes the device-specific callbacks
     * on AbsBYDAutoChargingListener subclasses through that path —
     * onBatteryManagementDeviceStateChanged in particular has been
     * observed to silently drop on PHEV builds, which is the root cause
     * of charging-detection lag during AC charging start.
     *
     * Uses one typed registration, with the subscribe-all overload as a
     * fallback when the normal typed overload is unavailable.
     */
    public static boolean registerChargingListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.charging.AbsBYDAutoChargingListener listener =
                new android.hardware.bydauto.charging.AbsBYDAutoChargingListener() {
                    @Override
                    public void onBatteryManagementDeviceStateChanged(int state) {
                        invokeCallback(callback, "onBatteryManagementDeviceStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onChargerStateChanged(int state) {
                        invokeCallback(callback, "onChargerStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onChargingGunStateChanged(int state) {
                        invokeCallback(callback, "onChargingGunStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onChargingPowerChanged(double power) {
                        invokeCallback(callback, "onChargingPowerChanged", new Object[]{power});
                    }
                    @Override
                    public void onChargingPowerChanged(float power) {
                        invokeCallback(callback, "onChargingPowerChanged", new Object[]{power});
                    }
                    @Override
                    public void onChargingCapacityChanged(float capacity) {
                        invokeCallback(callback, "onChargingCapacityChanged", new Object[]{capacity});
                    }
                    @Override
                    public void onChargingCapacityChanged(double capacity) {
                        invokeCallback(callback, "onChargingCapacityChanged", new Object[]{capacity});
                    }
                };

            if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                return registerLegacyAdditive(
                        device,
                        listener,
                        android.hardware.bydauto.charging.AbsBYDAutoChargingListener.class,
                        new int[0],
                        false,
                        "Charging");
            }

            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.charging.AbsBYDAutoChargingListener.class);
            if (register != null) {
                try {
                    return registerRetainedListener(device, listener, register, null);
                } catch (Exception e) {
                    logger.debug("Charging 1-arg registration failed: " + e.getMessage());
                }
            }

            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.charging.AbsBYDAutoChargingListener.class);
            if (registerWithIds != null) {
                try {
                    return registerRetainedListener(
                            device, listener, registerWithIds, new int[0]);
                } catch (Exception e) {
                    logger.debug("Charging subscribe-all registration failed: "
                            + e.getMessage());
                }
            }
            logger.debug("registerChargingListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerChargingListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerChargingListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed instrument listener. The instrument device's real
     * signals — most importantly {@code onExternalChargingPowerChanged(float)}
     * (live AC/DC charging power in kW) — are CONCRETE methods on the
     * {@code AbsBYDAutoInstrumentListener} abstract class, NOT on the
     * {@code IBYDAutoListener} base interface (which is an empty marker). The
     * generic {@link #registerListener(Object, ListenerCallback)} path builds a
     * dynamic {@code Proxy} of {@code IBYDAutoListener}; a Proxy can only
     * implement interface methods, so it can NEVER receive
     * {@code onExternalChargingPowerChanged} — the HAL keeps a separate typed
     * {@code List<AbsBYDAutoInstrumentListener>} and dispatches that callback
     * only to it. Result: charging power never arrives via the listener and the
     * UI falls back to a nominal estimate. Mirrors {@link #registerChargingListener}.
     *
     * Uses one typed registration, with the filtered overload as a fallback
     * when the normal typed overload is unavailable.
     */
    public static boolean registerInstrumentListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener listener =
                new android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener() {
                    @Override
                    public void onExternalChargingPowerChanged(float power) {
                        invokeCallback(callback, "onExternalChargingPowerChanged", new Object[]{power});
                    }
                    @Override
                    public void onExternalChargingPowerChanged(double power) {
                        invokeCallback(callback, "onExternalChargingPowerChanged", new Object[]{power});
                    }
                    @Override
                    public void onSafetyBeltStatusChanged(int seat, int state) {
                        invokeCallback(callback, "onSafetyBeltStatusChanged", new Object[]{seat, state});
                    }
                    @Override
                    public void onSportModeStateChanged(int state) {
                        invokeCallback(callback, "onSportModeStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onOutCarTemperatureChanged(int tempC) {
                        invokeCallback(callback, "onOutCarTemperatureChanged",
                                new Object[]{tempC});
                    }
                    @Override
                    public void onDataEventChanged(
                            int featureId,
                            android.hardware.bydauto.BYDAutoEventValue value) {
                        invokeCallback(callback, "onDataEventChanged",
                                new Object[]{featureId, value});
                    }
                };

            if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                return registerLegacyAdditive(
                        device,
                        listener,
                        android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener.class,
                        new int[0],
                        false,
                        "Instrument");
            }

            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener.class);
            if (register != null) {
                try {
                    return registerRetainedListener(device, listener, register, null);
                } catch (Exception e) {
                    logger.debug("Instrument 1-arg registration failed: " + e.getMessage());
                }
            }

            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener.class);
            if (registerWithIds != null) {
                try {
                    int[] featureIds =
                            com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                                    ? new int[]{4208}
                                    : new int[0];
                    return registerRetainedListener(
                            device, listener, registerWithIds, featureIds);
                } catch (Exception e) {
                    logger.debug("Instrument filtered registration failed: "
                            + e.getMessage());
                }
            }
            logger.debug("registerInstrumentListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerInstrumentListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerInstrumentListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register the typed safety-belt listener. Passenger occupancy is delivered through
     * {@code onPassengerStatusChanged}, which is not a method on the generic
     * {@code IBYDAutoListener} marker interface.
     */
    public static boolean registerSafetyBeltListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        synchronized (safetyBeltListeners) {
            // The caller should already avoid duplicate registration, but retaining only one
            // listener makes a second helper-level registration a leak: the first callback can
            // no longer be unregistered. Treat the existing typed listener as success.
            if (safetyBeltListeners.containsKey(device)) return true;
            try {
                android.hardware.bydauto.safetybelt.AbsBYDAutoSafetyBeltListener listener =
                        new android.hardware.bydauto.safetybelt.AbsBYDAutoSafetyBeltListener() {
                            @Override
                            public void onSafetyBeltStatusChanged(int seat, int state) {
                                invokeCallback(callback, "onSafetyBeltStatusChanged",
                                        new Object[]{seat, state});
                            }

                            @Override
                            public void onPassengerStatusChanged(int area, int state) {
                                invokeCallback(callback, "onPassengerStatusChanged",
                                        new Object[]{area, state});
                            }
                        };
                Method register = findRegisterMethod(device.getClass(),
                        android.hardware.bydauto.safetybelt.AbsBYDAutoSafetyBeltListener.class);
                if (register != null) {
                    register.invoke(device, listener);
                    safetyBeltListeners.put(device, listener);
                    return true;
                }
                logger.debug("registerSafetyBeltListener: no registerListener method on "
                        + device.getClass().getName());
            } catch (LinkageError e) {
                logger.debug("registerSafetyBeltListener: class not available on this firmware");
            } catch (Exception e) {
                logger.debug("registerSafetyBeltListener failed: " + e.getMessage());
            }
        }
        return false;
    }

    /** Unregister and release the retained typed safety-belt listener for one device handle. */
    public static boolean unregisterSafetyBeltListener(Object device) {
        if (device == null) return false;
        synchronized (safetyBeltListeners) {
            Object listener = safetyBeltListeners.get(device);
            if (listener == null) return false;
            try {
                Method unregister = findUnregisterMethod(device.getClass(),
                        android.hardware.bydauto.safetybelt.AbsBYDAutoSafetyBeltListener.class);
                if (unregister == null) {
                    logger.debug("unregisterSafetyBeltListener: no unregisterListener method on "
                            + device.getClass().getName());
                    return false;
                }
                unregister.invoke(device, listener);
                safetyBeltListeners.remove(device);
                return true;
            } catch (LinkageError e) {
                logger.debug("unregisterSafetyBeltListener: class not available on this firmware");
            } catch (Exception e) {
                logger.debug("unregisterSafetyBeltListener failed: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Register a typed statistic listener. onElecPercentageChanged(double) — the
     * DECIMAL display SoC — is a concrete method on AbsBYDAutoStatisticListener, NOT
     * on the bare IBYDAutoListener marker interface, so the generic Proxy path can
     * never receive it (the HAL dispatches it only to typed subscribers) — exactly
     * like onExternalChargingPowerChanged on the instrument device. Via the generic
     * path SoC only ever came from the slow getElecPercentageValue() poll (integer on
     * this trim); the typed path delivers sub-integer SoC the moment it changes.
     * Forwards the same methods the generic hub (onGenericCallback) already handles so
     * there is zero behavioural regression, only added granularity. Typed first with a
     * generic fallback kept at the call site for firmware exposing only the bare
     * registerListener.
     */
    public static boolean registerStatisticListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        ensureRuntimeSdk();
        try {
            android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener listener =
                new android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener() {
                    @Override
                    public void onElecPercentageChanged(double percentage) {
                        invokeCallback(callback, "onElecPercentageChanged", new Object[]{percentage});
                    }
                    @Override
                    public void onFuelPercentageChanged(int percentage) {
                        invokeCallback(callback, "onFuelPercentageChanged", new Object[]{percentage});
                    }
                    @Override
                    public void onSOCBatteryPercentageChanged(int percentage) {
                        invokeCallback(callback, "onElecPercentageChanged",
                                new Object[]{percentage});
                    }
                    @Override
                    public void onTotalMileageValueChanged(float mileage) {
                        invokeCallback(callback, "onTotalMileageValueChanged",
                                new Object[]{mileage});
                    }
                    @Override
                    public void onEVMileageValueChanged(int mileage) {
                        invokeCallback(callback, "onEVMileageValueChanged",
                                new Object[]{mileage});
                    }
                    @Override
                    public void onElecDrivingRangeChanged(int range) {
                        invokeCallback(callback, "onElecDrivingRangeChanged",
                                new Object[]{range});
                    }
                    @Override
                    public void onDrivingRangeValueChanged(int range) {
                        invokeCallback(callback, "onElecDrivingRangeChanged",
                                new Object[]{range});
                    }
                    @Override
                    public void onFuelDrivingRangeChanged(int range) {
                        invokeCallback(callback, "onFuelDrivingRangeChanged",
                                new Object[]{range});
                    }
                    @Override
                    public void onEVRemainingBatteryPowerChanged(float kwh) {
                        invokeCallback(callback, "onRemainingBatteryPowerChanged",
                                new Object[]{kwh});
                    }
                    @Override
                    public void onRemainingBatteryPowerChanged(float kwh) {
                        invokeCallback(callback, "onRemainingBatteryPowerChanged",
                                new Object[]{kwh});
                    }
                    @Override
                    public void onTotalElecConChanged(double kwh) {
                        invokeCallback(callback, "onTotalElecConChanged",
                                new Object[]{kwh});
                    }
                };

            if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                return registerLegacyAdditive(
                        device,
                        listener,
                        android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener.class,
                        new int[0],
                        false,
                        "Statistic");
            }

            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener.class);
            if (register != null) {
                try {
                    return registerRetainedListener(device, listener, register, null);
                } catch (Exception e) {
                    logger.debug("Statistic 1-arg registration failed: " + e.getMessage());
                }
            }

            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener.class);
            if (registerWithIds != null) {
                try {
                    return registerRetainedListener(
                            device, listener, registerWithIds, new int[0]);
                } catch (Exception e) {
                    logger.debug("Statistic subscribe-all registration failed: "
                            + e.getMessage());
                }
            }
            logger.debug("registerStatisticListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerStatisticListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerStatisticListener failed: " + e.getMessage());
        }
        return false;
    }

    private static void invokeCallback(ListenerCallback callback, String method, Object[] args) {
        try {
            callback.onCallback(method, args);
        } catch (Exception e) {
            logger.debug("Typed listener callback error: " + method + " — " + e.getMessage());
        }
    }

    // ==================== EXTENDED GETTER METHODS ====================

    /**
     * Call get(int deviceType, int featureId) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callGetSingle(Object device, int featureId) {
        if (device == null) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "get", getSingleMethodCache,
                    int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callGetSingle permission denied for id=" + featureId + " — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callGetSingle failed for id=" + featureId + " — " + e.getMessage());
        }
        return -1;
    }

    /**
     * Call getDouble(int deviceType, int featureId) on a BYD device.
     * Returns Double.NaN on any failure.
     */
    public static double callGetDouble(Object device, int featureId) {
        if (device == null) return Double.NaN;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return Double.NaN;
            Method m = findMethodCached(device, "getDouble", getDoubleMethodCache,
                    int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId);
                if (result instanceof Number) return ((Number) result).doubleValue();
            }
        } catch (Exception e) {
            logger.debug("callGetDouble failed for id=" + featureId + " — " + e.getMessage());
        }
        return Double.NaN;
    }

    /**
     * Call getIntArray(int deviceType, int[] featureIds) on a BYD device.
     * Returns null on any failure.
     */
    public static int[] callGetIntArray(Object device, int[] featureIds) {
        if (device == null) return null;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return null;
            Method m = findMethodCached(device, "getIntArray", getIntArrayMethodCache,
                    int.class, int[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureIds);
                if (result instanceof int[]) return (int[]) result;
            }
        } catch (Exception e) {
            logger.debug("callGetIntArray failed — " + e.getMessage());
        }
        return null;
    }

    /**
     * Call getDoubleArray(int deviceType, int[] featureIds) on a BYD device.
     * The underlying SDK returns float[], so this method returns float[].
     * Returns null on any failure.
     */
    public static float[] callGetDoubleArray(Object device, int[] featureIds) {
        if (device == null) return null;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return null;
            Method m = findMethodCached(device, "getDoubleArray", getDoubleArrayMethodCache,
                    int.class, int[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureIds);
                if (result instanceof float[]) return (float[]) result;
            }
        } catch (Exception e) {
            logger.debug("callGetDoubleArray failed — " + e.getMessage());
        }
        return null;
    }

    /**
     * Call getBuffer(int deviceType, int featureId) on a BYD device.
     * Returns null on any failure.
     */
    public static byte[] callGetBuffer(Object device, int featureId) {
        if (device == null) return null;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return null;
            Method m = findMethodCached(device, "getBuffer", getBufferMethodCache,
                    int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId);
                if (result instanceof byte[]) return (byte[]) result;
            }
        } catch (Exception e) {
            logger.debug("callGetBuffer failed for id=" + featureId + " — " + e.getMessage());
        }
        return null;
    }

    // ==================== SETTER METHODS ====================

    /**
     * Send a set command using the BYDAutoEventValue pattern.
     * Creates a BYDAutoEventValue, sets intValue, calls device.set(int[], BYDAutoEventValue).
     * Falls back to callSetSingle if BYDAutoEventValue is not available.
     */
    public static boolean sendSetCommand(Object device, int featureId, int value) {
        int code = sendSetCommandRaw(device, featureId, value);
        return code >= 0;
    }

    /**
     * Same as {@link #sendSetCommand} but returns the RAW SDK result code
     * instead of a boolean, so callers can distinguish e.g.
     * BYDAUTO_COMMAND_RESULT_FAILED (-2147482648) from other negatives.
     * Returns {@link Integer#MIN_VALUE} only when the call threw before
     * producing a code (so it's distinguishable from the real -2147482648).
     * A Boolean SDK result maps to 0 (true) / -1 (false).
     */
    public static int sendSetCommandRaw(Object device, int featureId, int value) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) {
            return Integer.MIN_VALUE;
        }
        if (isForeignDiLink5FeatureId(featureId)) return Integer.MIN_VALUE;
        try {
            Class<?> eventValueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object eventValue = eventValueClass.getConstructor(new Class[0]).newInstance(new Object[0]);
            eventValueClass.getField("intValue").setInt(eventValue, value);
            Method setMethod = device.getClass().getMethod("set", int[].class, eventValueClass);
            Object result = setMethod.invoke(device, new int[]{featureId}, eventValue);
            if (result instanceof Integer) {
                return ((Integer) result).intValue();
            } else if (result instanceof Boolean) {
                return ((Boolean) result).booleanValue() ? 0 : -1;
            }
            return 0; // non-null result, assume success
        } catch (ClassNotFoundException e) {
            // BYDAutoEventValue not available, fall back to base class set()
            logger.debug("BYDAutoEventValue not found, falling back to callSetSingle");
            return callSetSingle(device, featureId, value);
        } catch (Exception e) {
            logger.debug("sendSetCommandRaw failed for featureId=0x" + Integer.toHexString(featureId) + ": " + e.getMessage());
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Grouped set: writes several feature ids in one {@code set(int[] ids, BYDAutoEventValue)}
     * call. Returns the RAW SDK result code (0 = accepted), or {@link Integer#MIN_VALUE} when the
     * call threw before producing one. {@code ids} and {@code values} must be the same length.
     *
     * <p>The event is built via the {@code BYDAutoEventValue(int[])} constructor. This matters:
     * live on a Sealion 6 DM-i (Di 3.0), the charging-schedule grouped write only takes effect
     * when built this way — the no-arg ctor + {@code intArrayValue} field form returns success
     * but is silently dropped by the charging HAL, so the schedule never arms (BMS never → 9).
     * The field form is kept only as a fallback for trims whose SDK lacks the int[] constructor.
     */
    public static int sendSetCommandGroupedRaw(Object device, int[] ids, int[] values) {
        if (device == null || ids == null || values == null || ids.length != values.length
                || VehicleActuatorBridge.isDiLink5RequestExpired()) {
            return Integer.MIN_VALUE;
        }
        for (int id : ids) {
            if (isForeignDiLink5FeatureId(id)) return Integer.MIN_VALUE;
        }
        try {
            Class<?> eventValueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object eventValue;
            try {
                eventValue = eventValueClass.getConstructor(int[].class).newInstance((Object) values);
            } catch (ReflectiveOperationException noUsableIntArrayCtor) {
                // Any SDK where that ctor is missing or unusable: fall back to the field form.
                eventValue = eventValueClass.getConstructor(new Class[0]).newInstance(new Object[0]);
                eventValueClass.getField("intArrayValue").set(eventValue, values);
            }
            Method setMethod = device.getClass().getMethod("set", int[].class, eventValueClass);
            Object result = setMethod.invoke(device, ids, eventValue);
            if (result instanceof Integer) {
                return ((Integer) result).intValue();
            } else if (result instanceof Boolean) {
                return ((Boolean) result).booleanValue() ? 0 : -1;
            }
            return 0; // non-null result, assume success
        } catch (Exception e) {
            logger.debug("sendSetCommandGroupedRaw failed: " + e.getMessage());
            return Integer.MIN_VALUE;
        }
    }

    /**
     * OEM-strict variant for commands whose contract requires an actual Integer result.
     * Returns null when the invocation did not produce one; unlike {@link #sendSetCommandRaw},
     * a void, Boolean, or other non-Integer result is never manufactured into success.
     */
    public static Integer sendSetCommandIntegerResult(Object device, int featureId, int value) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) return null;
        if (isForeignDiLink5FeatureId(featureId)) return null;
        try {
            Class<?> eventValueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object eventValue = eventValueClass.getConstructor(new Class[0]).newInstance(new Object[0]);
            eventValueClass.getField("intValue").setInt(eventValue, value);
            Method setMethod = device.getClass().getMethod("set", int[].class, eventValueClass);
            Object result = setMethod.invoke(device, new int[]{featureId}, eventValue);
            return result instanceof Integer ? (Integer) result : null;
        } catch (Exception e) {
            logger.debug("sendSetCommandIntegerResult failed for featureId=0x"
                    + Integer.toHexString(featureId) + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Call set(int deviceType, int featureId, int value) on a BYD device, including a protected
     * declaration inherited from an OEM base class. Returns the SDK result code, or -1 on failure.
     */
    public static int callSetSingle(Object device, int featureId, int value) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "set", setSingleMethodCache,
                    int.class, int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId, value);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callSetSingle permission denied for id=" + featureId + " — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callSetSingle failed for id=" + featureId + ", value=" + value + " — " + e.getMessage());
        }
        return -1;
    }

    /**
     * Call set(int deviceType, int[] featureIds, int[] values) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callSetBatch(Object device, int[] featureIds, int[] values) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "set", setBatchMethodCache,
                    int.class, int[].class, int[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureIds, values);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callSetBatch permission denied — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callSetBatch failed — " + e.getMessage());
        }
        return -1;
    }

    /**
     * Call set(int deviceType, int featureId, byte[] buffer) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callSetBuffer(Object device, int featureId, byte[] buffer) {
        if (device == null || VehicleActuatorBridge.isDiLink5RequestExpired()) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "set", setBufferMethodCache,
                    int.class, int.class, byte[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId, buffer);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callSetBuffer permission denied for id=" + featureId + " — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callSetBuffer failed for id=" + featureId + " — " + e.getMessage());
        }
        return -1;
    }

    // ==================== INTERNAL HELPERS ====================

    private static final java.util.Map<Class<?>, Method> getMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getSingleMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getDoubleMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getIntArrayMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getDoubleArrayMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getBufferMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> setSingleMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> managerSetIntMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> setBatchMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> setBufferMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * className → HAL device type. {@code ConcurrentHashMap}, not {@code HashMap}: this is written
     * from the collector poll thread, the HTTP {@code collectAllFull} thread and HAL/init worker
     * threads with no external lock, and a plain HashMap under concurrent {@code put} can corrupt
     * its table (classically, an endless loop inside {@code get}).
     */
    private static final java.util.Map<Class<?>, Integer> deviceTypeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Classes whose type-probe recently FAILED, and the wall-clock ms after which to retry.
     * Separate from {@link #deviceTypeCache} so a failure can never be mistaken for a resolved
     * type, and so {@code ConcurrentHashMap}'s no-null-values rule is respected.
     */
    private static final java.util.Map<Class<?>, Long> deviceTypeMissUntil =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How long a failed device-type probe is remembered before being retried. Sized near the
     * 5 s ACC-on poll cadence rather than a minute: a binder that recovers in 2 s should not blank
     * every manager-level read for 12 further cycles.
     *
     * <p>Measured with {@link android.os.SystemClock#elapsedRealtime()}, NOT wall clock. These head
     * units boot with an unset RTC and then jump the clock when NTP/GPS lands — a BACKWARD jump
     * would hold a wall-clock deadline in the future for hours, re-creating the process-lifetime
     * outage this TTL exists to prevent, and doing it precisely while handles are being acquired.
     */
    private static final long TYPE_MISS_TTL_MS = 10_000L;

    // ----- per-name reflection caches for callGetter / callMethod -----
    //
    // The existing per-Class caches above are each dedicated to a single
    // (methodName, paramTypes) tuple. callGetter / callMethod take the
    // methodName as a parameter, so they need a (Class, methodName) key.
    // One cache per paramType signature lets us drop methodName-on-key only
    // (paramTypes are implicit per cache).
    //
    // ConcurrentHashMap because these are hit from many threads at high
    // frequency (BydDataCollector polls every 5s while ACC ON, plus
    // setter calls from web/Telegram threads). ConcurrentHashMap forbids
    // null values, so we use NEGATIVE_CACHE_SENTINEL for "method not found".
    private static final Method NEGATIVE_CACHE_SENTINEL;
    static {
        Method sentinel = null;
        try {
            // Any well-known no-arg Method works as an identity sentinel.
            sentinel = Object.class.getDeclaredMethod("hashCode");
        } catch (NoSuchMethodException ignored) {
            // Object.hashCode is guaranteed to exist; this branch is unreachable.
        }
        NEGATIVE_CACHE_SENTINEL = sentinel;
    }

    private static volatile java.util.Set<Integer> diLink5FeatureIds;
    private static volatile boolean diLink5FeatureIdsUnavailable;

    /** DiLink 5's set(int[], value) returns 0 for unknown ids; refuse ids outside its own table. */
    static boolean isForeignDiLink5FeatureId(int featureId) {
        if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) return false;
        java.util.Set<Integer> known = diLink5FeatureIds();
        if (known == null || known.contains(featureId)) return false;
        logger.debug("feature id " + featureId + " (0x" + Integer.toHexString(featureId)
                + ") is not a DiLink 5 feature id — write not sent");
        return true;
    }

    private static java.util.Set<Integer> diLink5FeatureIds() {
        java.util.Set<Integer> known = diLink5FeatureIds;
        if (known != null || diLink5FeatureIdsUnavailable) return known;
        synchronized (BydDeviceHelper.class) {
            if (diLink5FeatureIds != null || diLink5FeatureIdsUnavailable) return diLink5FeatureIds;
            ensureRuntimeSdk();
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            try {
                Class<?> table = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
                collectIntConstants(table, ids);
                for (Class<?> nested : table.getDeclaredClasses()) collectIntConstants(nested, ids);
            } catch (Throwable unavailable) {
                logger.debug("DiLink 5 feature-id table unavailable: " + unavailable.getMessage());
            }
            if (ids.isEmpty()) {
                diLink5FeatureIdsUnavailable = true;
                return null;
            }
            diLink5FeatureIds = java.util.Collections.unmodifiableSet(ids);
            return diLink5FeatureIds;
        }
    }

    private static void collectIntConstants(Class<?> type, java.util.Set<Integer> out) {
        for (Field field : type.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != int.class
                    || !java.lang.reflect.Modifier.isStatic(modifiers)
                    || !java.lang.reflect.Modifier.isFinal(modifiers)) {
                continue;
            }
            try {
                field.setAccessible(true);
                out.add(field.getInt(null));
            } catch (Throwable ignored) {
            }
        }
    }

    private static final Class<?>[] NO_PARAMS = new Class<?>[0];
    private static final Class<?>[] INT_PARAMS = new Class<?>[]{int.class};
    private static final Class<?>[] INT_INT_PARAMS = new Class<?>[]{int.class, int.class};
    private static final Class<?>[] INT3_PARAMS = new Class<?>[]{int.class, int.class, int.class};
    private static final Class<?>[] INT4_PARAMS = new Class<?>[]{int.class, int.class, int.class, int.class};
    private static final Class<?>[] INT_ARRAY_PARAMS = new Class<?>[]{int[].class};

    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicNoArgMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicIntMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicIntIntMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicInt3MethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicInt4MethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicIntArrayMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            declaredNoArgMethodCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Cached Class.getMethod(name, paramTypes) lookup.
     * Returns null when the method doesn't exist (cached negatively via sentinel).
     */
    private static Method lookupPublicMethodCached(Class<?> cls, String methodName,
            java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>> cache,
            Class<?>[] paramTypes) {
        java.util.concurrent.ConcurrentMap<String, Method> perClass = cache.get(cls);
        if (perClass == null) {
            perClass = cache.computeIfAbsent(cls, k -> new java.util.concurrent.ConcurrentHashMap<>());
        }
        Method cached = perClass.get(methodName);
        if (cached != null) {
            return cached == NEGATIVE_CACHE_SENTINEL ? null : cached;
        }
        try {
            Method m = cls.getMethod(methodName, paramTypes);
            perClass.put(methodName, m);
            return m;
        } catch (NoSuchMethodException e) {
            perClass.put(methodName, NEGATIVE_CACHE_SENTINEL);
            return null;
        } catch (Exception e) {
            // SecurityException etc. — don't poison the cache; just return null.
            return null;
        }
    }

    /**
     * Cached Class.getDeclaredMethod(name) walk up the class hierarchy.
     * Returns null when no class in the hierarchy declares the method
     * (cached negatively via sentinel).
     */
    private static Method lookupDeclaredNoArgCached(Class<?> cls, String methodName) {
        java.util.concurrent.ConcurrentMap<String, Method> perClass = declaredNoArgMethodCache.get(cls);
        if (perClass == null) {
            perClass = declaredNoArgMethodCache.computeIfAbsent(cls, k -> new java.util.concurrent.ConcurrentHashMap<>());
        }
        Method cached = perClass.get(methodName);
        if (cached != null) {
            return cached == NEGATIVE_CACHE_SENTINEL ? null : cached;
        }
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod(methodName);
                m.setAccessible(true);
                perClass.put(methodName, m);
                return m;
            } catch (NoSuchMethodException ignored) {
                walk = walk.getSuperclass();
            } catch (Exception e) {
                // SecurityException — don't poison the cache.
                return null;
            }
        }
        perClass.put(methodName, NEGATIVE_CACHE_SENTINEL);
        return null;
    }

    private static Method findGetMethod(Object device) {
        Class<?> cls = device.getClass();
        Method ghit = getMethodCache.get(cls);
        if (ghit != null) return ghit == NEGATIVE_CACHE_SENTINEL ? null : ghit;

        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod("get", int[].class, Class.class);
                m.setAccessible(true);
                getMethodCache.put(cls, m);
                return m;
            } catch (NoSuchMethodException ignored) {}
            try {
                Method m = walk.getDeclaredMethod("get", int.class, int.class);
                m.setAccessible(true);
                getMethodCache.put(cls, m);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        // Sentinel, not null: these caches are ConcurrentHashMap now (they are hit from the poll
        // thread, the HTTP collectAllFull thread and web/Telegram setter threads, and a plain
        // HashMap can corrupt its table under concurrent put) — and CHM forbids null VALUES.
        getMethodCache.put(cls, NEGATIVE_CACHE_SENTINEL);
        return null;
    }

    /**
     * Public accessor for a device's numeric HAL type, or {@code Integer.MIN_VALUE} when it cannot
     * be resolved. Exposed for {@link BydManagerChannel}, whose manager-level reads are keyed by
     * {@code (deviceType, featureId)} — it must not duplicate this lookup, because the result is
     * cached per class here and a second cache could disagree.
     */
    public static int deviceTypeOf(Object device) {
        if (device == null) return Integer.MIN_VALUE;
        return resolveDeviceType(device);
    }

    private static int resolveDeviceType(Object device) {
        Class<?> cls = device.getClass();
        Integer hit = deviceTypeCache.get(cls);
        if (hit != null) return hit;   // get()-once, not containsKey+get: no unboxing NPE window
        // NEGATIVE CACHE WITH A TTL. Not caching failures at all (the previous revision) meant a
        // device that can never report its type re-ran two failing reflective probes on EVERY call —
        // up to ~34k probe pairs/day at the 5 s ACC-on cadence. Caching them forever (the revision
        // before that) turned one transient failure into a process-lifetime outage that no re-init
        // could clear. A short TTL has both properties: steady-state cost is one probe pair per
        // class per window, and a genuine recovery is picked up within one window.
        Long until = deviceTypeMissUntil.get(cls);
        if (until != null) {
            if (android.os.SystemClock.elapsedRealtime() < until) return Integer.MIN_VALUE;
            deviceTypeMissUntil.remove(cls);   // window elapsed → probe again
        }

        // Try getDevicetype() first (AbsBYDAutoDevice). The METHOD LOOKUP is cached permanently
        // (a Method is Class-bound, so its absence never changes) while only the VALUE is under the
        // TTL — otherwise every TTL window re-threw NoSuchMethodException for both accessors, with
        // full stack-trace fill-in, on the 5 s poll thread and once per unresolvable class.
        try {
            Method m = lookupPublicMethodCached(cls, "getDevicetype",
                    publicNoArgMethodCache, NO_PARAMS);
            if (m == null) throw new NoSuchMethodException("getDevicetype");
            Object result = m.invoke(device);
            if (result instanceof Number) {
                int type = ((Number) result).intValue();
                deviceTypeCache.put(cls, type);
                return type;
            }
        } catch (Exception ignored) {}

        // Fallback to getType() — same cached-lookup treatment.
        try {
            Method m = lookupPublicMethodCached(cls, "getType",
                    publicNoArgMethodCache, NO_PARAMS);
            if (m == null) throw new NoSuchMethodException("getType");
            Object result = m.invoke(device);
            if (result instanceof Number) {
                int type = ((Number) result).intValue();
                deviceTypeCache.put(cls, type);
                return type;
            }
        } catch (Exception ignored) {}

        // Failure is remembered only for TYPE_MISS_TTL_MS (see the negative-cache note above), so a
        // transient cause — a dead binder mid-restart, or a handle obtained under the broken
        // synthetic Context before ACC-ON replaces it — cannot become a permanent outage, while a
        // genuinely type-less device is not re-probed on every poll. Logged at DEBUG because with a
        // TTL this repeats once per window; the INFO-level version was itself a slow log leak.
        deviceTypeMissUntil.put(cls, android.os.SystemClock.elapsedRealtime() + TYPE_MISS_TTL_MS);
        logger.debug("Could not resolve deviceType for " + cls.getSimpleName()
                + " — retrying in " + (TYPE_MISS_TTL_MS / 1000) + "s");
        return Integer.MIN_VALUE;
    }

    /**
     * Drop cached device types so the next call re-probes. Intended for
     * {@link BydManagerChannel#invalidate()} — a dead binder can make {@code getDevicetype()} throw,
     * and although failures are no longer cached, a type resolved from a stale handle should not
     * outlive it.
     */
    static void invalidateDeviceTypeCache() {
        deviceTypeCache.clear();
        deviceTypeMissUntil.clear();   // a new handle deserves an immediate retry, not a stale TTL
    }

    /**
     * Find a method by name and parameter types on a device, walking up the class hierarchy.
     * Caches the result per device class in the provided cache map.
     */
    private static Method findMethodCached(Object device, String methodName,
            java.util.Map<Class<?>, Method> cache, Class<?>... paramTypes) {
        Class<?> cls = device.getClass();
        Method hit = cache.get(cls);
        if (hit != null) return hit == NEGATIVE_CACHE_SENTINEL ? null : hit;

        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                cache.put(cls, m);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        cache.put(cls, NEGATIVE_CACHE_SENTINEL);   // never null — see findGetMethod
        return null;
    }

    private static Method findRegisterMethod(Class<?> cls, Class<?> listenerInterface) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod("registerListener", listenerInterface);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        return null;
    }

    private static Method findUnregisterMethod(Class<?> cls, Class<?> listenerInterface) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            for (String name : new String[]{"unregisterListener", "unRegisterListener"}) {
                try {
                    Method m = walk.getDeclaredMethod(name, listenerInterface);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {}
            }
            walk = walk.getSuperclass();
        }
        return null;
    }

    private static Method findRegisterMethodWithIds(Class<?> cls, Class<?> listenerInterface) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod("registerListener", listenerInterface, int[].class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        return null;
    }

    /** Callback interface for listener proxies */
    public interface ListenerCallback {
        void onCallback(String methodName, Object[] args);
    }

    private BydDeviceHelper() {}
}
