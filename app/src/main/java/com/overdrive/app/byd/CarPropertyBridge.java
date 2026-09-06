package com.overdrive.app.byd;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;

import com.byd.car.property.CarPropertyConfig;
import com.byd.car.property.CarPropertyValue;
import com.byd.car.property.ICarPropertyService;
import com.byd.datasource.feature.Response;
import com.byd.datasource.feature.Status;
import com.overdrive.app.daemon.CameraDaemon;

/**
 * Generic reach into DiCarServer's {@code ICarPropertyService} — the local
 * Android property bus the BYD framework apps use to read/write vehicle
 * features without going through the cloud daemon.
 *
 * <h3>Why this is non-trivial from a daemon process</h3>
 * Three separate gates have to be threaded:
 * <ol>
 *   <li>The bus is exposed only via DiCarServer's {@code CarServiceProvider}
 *       ContentProvider. Plain {@code getContentResolver().query()} works only
 *       from a process AMS knows about — manually-constructed
 *       {@link android.app.ActivityThread}s (which our daemon uses) hit
 *       {@code "Unable to find app for caller"}.</li>
 *   <li>The provider's calling-package check expects a string that resolves
 *       to a package owned by our uid. {@code Context.createPackageContext()}
 *       inherits {@code mBasePackageName} from its container so it always
 *       reports {@code "android"} → SecurityException.</li>
 *   <li>The unprivileged path {@code getContentProviderExternal()} works for
 *       shell uid (2000) which is pre-granted
 *       {@code ACCESS_CONTENT_PROVIDERS_EXTERNALLY}.</li>
 * </ol>
 *
 * <p>We therefore use:
 * {@code IActivityManager.getContentProviderExternal()} →
 * {@code IContentProvider.query(callingPkg, …)} → {@code BinderCursor}'s
 * extras {@code Bundle} → {@code IBinder} → {@code ICarPropertyService}.
 *
 * <h3>What you can actually do with it</h3>
 * The binder we get is fully functional. {@code getProperty()} and {@code
 * getPropertyConfigs()} round-trips work end-to-end (verified). {@code
 * setProperties()} also works mechanically — but the underlying property
 * config gates writes on signature-protected permissions like
 * {@code BYDAUTO_BODYWORK_SET}. Without an APK signed with the BYD platform
 * key, those writes return {@code STATUS_FAILED} regardless of how clean
 * the call is. This bridge is therefore best thought of as a <strong>read-side
 * + config-probe</strong> tool today; it remains useful for any future
 * non-sigperm property surface that DiCarServer exposes, and for confirming
 * (via {@link #readPropertyConfig}) what permission gate any property
 * actually carries.
 *
 * <h3>Auth</h3>
 * The provider permission {@code com.byd.car.server.PROVIDER} is
 * {@code protectionLevel=normal} — auto-granted on install. The
 * {@code getContentProviderExternal} path is gated by
 * {@code ACCESS_CONTENT_PROVIDERS_EXTERNALLY}, pre-granted to
 * {@code com.android.shell} (uid 2000).
 */
public final class CarPropertyBridge {

    private static final String TAG = "CarPropertyBridge";

    /** Provider URI as declared in DiCarServer's manifest. */
    private static final Uri PROVIDER_URI = Uri.parse(
            "content://com.byd.car.server.provider.CarServiceProvider");

    /** Class name passed in selectionArgs[0] — the BinderProvider uses this
     *  as a {@code Class.forName(...)} key into Spi.getService(). */
    private static final String SERVICE_CLASS_NAME =
            "com.byd.car.property.ICarPropertyService";

    /** Bundle extras key — see BinderCursor.KEY_BINDER. */
    private static final String EXTRAS_BINDER_KEY = "binder";

    // ── Singleton ──
    private static final Object LOCK = new Object();
    private static volatile CarPropertyBridge instance;

    private final Context appCtx;
    private volatile ICarPropertyService cached;

    private CarPropertyBridge(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public static CarPropertyBridge getInstance(Context ctx) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) instance = new CarPropertyBridge(ctx);
            }
        }
        return instance;
    }

    /**
     * Get the singleton without an explicit Context — falls back to
     * {@code ActivityThread.currentApplication()} (app process) and then
     * {@code CameraDaemon.getAppContext()} (daemon process). Returns null if
     * neither is available.
     */
    public static CarPropertyBridge getInstance() {
        if (instance != null) return instance;
        synchronized (LOCK) {
            if (instance != null) return instance;
            try {
                Class<?> atClass = Class.forName("android.app.ActivityThread");
                Object app = atClass.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    instance = new CarPropertyBridge((Context) app);
                    return instance;
                }
            } catch (Throwable t) {
                log("currentApplication() returned null: " + t);
            }
            try {
                Context daemonCtx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (daemonCtx != null) {
                    instance = new CarPropertyBridge(daemonCtx);
                    return instance;
                }
            } catch (Throwable t) {
                log("CameraDaemon.getAppContext() fallback failed: " + t);
            }
            try {
                Context daemonCtx = com.overdrive.app.daemon.DaemonBootstrap.getContext();
                if (daemonCtx != null) {
                    instance = new CarPropertyBridge(daemonCtx);
                    return instance;
                }
            } catch (Throwable t) {
                log("DaemonBootstrap.getContext() fallback failed: " + t);
            }
        }
        return null;
    }

    // ── Public API ──

    /** Read a property's live HAL value. */
    public ReadResult readProperty(String propertyKey) {
        ICarPropertyService svc = ensureService();
        if (svc == null) {
            return ReadResult.error("ICarPropertyService unavailable (provider query failed)");
        }
        try {
            Response resp = svc.getProperty(propertyKey);
            if (resp == null) return ReadResult.error("Response is null");
            int statusCode = resp.status != null ? resp.status.code : Status.STATUS_UNKNOWN_ERROR;
            String desc = resp.status != null ? resp.status.description : "";
            Object result = resp.result;
            Integer intVal = null;
            String stringVal = null;
            if (result instanceof CarPropertyValue) {
                CarPropertyValue cpv = (CarPropertyValue) result;
                Object v = cpv.getValue();
                if (v instanceof Integer) intVal = (Integer) v;
                if (v != null) stringVal = String.valueOf(v);
            }
            return new ReadResult(true, statusCode, desc, intVal, stringVal,
                    result == null ? null : result.getClass().getName());
        } catch (Throwable t) {
            log("readProperty(" + propertyKey + ") threw: " + t);
            invalidateBinder();
            return ReadResult.error(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Read the {@link CarPropertyConfig} for a property — tells you the
     * declared access level and read/write permission strings without
     * actually reading or writing the value. Use this to check whether a
     * property is permission-gated before attempting a write.
     */
    public ConfigResult readPropertyConfig(String propertyKey) {
        ICarPropertyService svc = ensureService();
        if (svc == null) {
            return ConfigResult.error("ICarPropertyService unavailable");
        }
        try {
            java.util.List<CarPropertyConfig> list = svc.getPropertyConfigs(new String[]{propertyKey});
            if (list == null || list.isEmpty()) {
                return ConfigResult.error("getPropertyConfigs returned empty list");
            }
            CarPropertyConfig cfg = list.get(0);
            if (cfg == null) {
                return ConfigResult.error("config[0] was null");
            }
            return new ConfigResult(true, cfg.mAccess, cfg.mFeatureId,
                    cfg.mTypeName, cfg.mProviderName,
                    cfg.mReadPermission, cfg.mWritePermission);
        } catch (Throwable t) {
            log("readPropertyConfig(" + propertyKey + ") threw: " + t);
            invalidateBinder();
            return ConfigResult.error(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Generic int property write. Will succeed only for properties whose
     * write permission our calling uid actually holds — typically that means
     * {@code null/empty writePermission}, not the BYD signature-protected
     * actuator perms.
     */
    public Result setIntProperty(String propertyKey, int value) {
        ICarPropertyService svc = ensureService();
        if (svc == null) {
            return Result.error("ICarPropertyService unavailable (provider query failed)");
        }
        try {
            CarPropertyValue<Integer> cpv = new CarPropertyValue<>(propertyKey, value);
            Status st = svc.setProperties(new CarPropertyValue[]{cpv});
            if (st == null) return Result.error("setProperties returned null Status");
            return new Result(st.code == Status.STATUS_SUCCESS, st.code, st.description);
        } catch (Throwable t) {
            log("setIntProperty(" + propertyKey + "=" + value + ") threw: " + t);
            invalidateBinder();
            return Result.error(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ── Service discovery ──

    private ICarPropertyService ensureService() {
        ICarPropertyService svc = cached;
        if (svc != null) {
            IBinder b = svc.asBinder();
            if (b != null && b.isBinderAlive()) return svc;
        }
        synchronized (LOCK) {
            svc = cached;
            if (svc != null) {
                IBinder b = svc.asBinder();
                if (b != null && b.isBinderAlive()) return svc;
            }
            svc = resolveServiceViaProvider();
            if (svc == null) {
                svc = resolveServiceViaServiceManager();
            }
            cached = svc;
            return svc;
        }
    }

    private ICarPropertyService resolveServiceViaServiceManager() {
        try {
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = smClass.getMethod("getService", String.class);
            
            // Do not wrap Android Automotive's "car_service" (android.car.ICar)
            // as BYD ICarPropertyService — that throws
            // "Binder invocation to an incorrect interface" and poisons the cache.
            // DiLink5 telemetry falls back to dumpsys car_service instead.
            String[] serviceNames = new String[] { "byd_car_property", "car_property_service", "byd_car_service" };
            for (String name : serviceNames) {
                IBinder binder = (IBinder) getService.invoke(null, name);
                if (binder != null && binder.isBinderAlive()) {
                    log("Found service binder for: " + name);
                    try {
                        ICarPropertyService service = ICarPropertyService.Stub.asInterface(binder);
                        if (service != null) {
                            log("Resolved ICarPropertyService via ServiceManager(" + name + ")");
                            return service;
                        }
                    } catch (Throwable t) {
                        log("Stub.asInterface failed for " + name + ": " + t);
                    }
                }
            }
        } catch (Throwable t) {
            log("resolveServiceViaServiceManager threw: " + t);
        }
        return null;
    }

    private void invalidateBinder() {
        synchronized (LOCK) { cached = null; }
    }

    /**
     * Acquire {@code ICarPropertyService} via {@code
     * IActivityManager.getContentProviderExternal()}.
     *
     * <p>The "normal" path (a real {@link ContentResolver}) bottoms out in
     * {@code IActivityManager.getContentProvider(IApplicationThread caller, …)}
     * which calls {@code AMS.getRecordForAppLocked(caller)} to find a registered
     * ProcessRecord for the caller. Our daemon's {@link android.app.ActivityThread}
     * is constructed manually (no {@code attachApplication} handshake), so AMS
     * has no record for our IApplicationThread and rejects with
     * {@code "Unable to find app for caller …"}.
     *
     * <p>{@code getContentProviderExternal()} is the unprivileged-caller path.
     * It takes no IApplicationThread; instead it requires the caller to hold the
     * signature permission {@code ACCESS_CONTENT_PROVIDERS_EXTERNALLY}, which
     * {@code com.android.shell} (uid 2000) has pre-granted. Our daemon runs as
     * uid 2000 → permission is honored.
     *
     * <p>The provider it returns is the same {@code IContentProvider} a normal
     * client would acquire. We call {@code IContentProvider.query()} directly,
     * passing {@code "com.android.shell"} as the calling-package string so the
     * uid/package match holds inside the provider.
     */
    private ICarPropertyService resolveServiceViaProvider() {
        try { com.overdrive.app.shell.HiddenApiBypass.INSTANCE.bypass(); }
        catch (Throwable ignore) {}

        String authority = PROVIDER_URI.getAuthority();
        int userId = android.os.Process.myUid() / 100000;
        Object provider = null;
        Object holder = null;
        Object amsService = null;
        IBinder externalToken = new android.os.Binder();

        try {
            Class<?> amClass = Class.forName("android.app.ActivityManager");
            java.lang.reflect.Method getService = amClass.getMethod("getService");
            amsService = getService.invoke(null);
            if (amsService == null) {
                log("ActivityManager.getService() returned null");
                return null;
            }

            Class<?> iamClass = amsService.getClass();
            java.lang.reflect.Method gcpe = null;
            for (java.lang.reflect.Method m : iamClass.getMethods()) {
                if (!"getContentProviderExternal".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4
                        && p[0] == String.class && p[1] == int.class
                        && p[2] == IBinder.class && p[3] == String.class) {
                    gcpe = m;
                    break;
                }
            }
            if (gcpe == null) {
                log("IActivityManager has no matching getContentProviderExternal(String,int,IBinder,String)");
                return null;
            }
            holder = gcpe.invoke(amsService, authority, userId, externalToken, "CarPropertyBridge");
            if (holder == null) {
                log("getContentProviderExternal returned null holder for authority=" + authority);
                return null;
            }
            java.lang.reflect.Field providerField = holder.getClass().getField("provider");
            provider = providerField.get(holder);
            if (provider == null) {
                log("ContentProviderHolder.provider field was null");
                return null;
            }

            // Android 10 IContentProvider.query: (String pkg, Uri uri, String[] projection,
            //                                     Bundle queryArgs, ICancellationSignal signal)
            java.lang.reflect.Method queryMethod = null;
            for (java.lang.reflect.Method m : provider.getClass().getMethods()) {
                if (!"query".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 5
                        && p[0] == String.class
                        && p[1] == Uri.class
                        && p[2] == String[].class
                        && p[3] == Bundle.class) {
                    queryMethod = m;
                    break;
                }
            }
            if (queryMethod == null) {
                log("IContentProvider has no 5-arg query(String,Uri,String[],Bundle,...) method");
                return null;
            }

            String callingPkg = (android.os.Process.myUid() == 2000)
                    ? "com.android.shell"
                    : appCtx.getPackageName();
            Bundle qa = new Bundle();
            qa.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    new String[]{SERVICE_CLASS_NAME});
            Object cursorObj = queryMethod.invoke(provider, callingPkg, PROVIDER_URI,
                    null, qa, null);
            if (!(cursorObj instanceof Cursor)) {
                log("IContentProvider.query returned non-Cursor: "
                        + (cursorObj == null ? "null" : cursorObj.getClass()));
                return null;
            }
            Cursor cursor = (Cursor) cursorObj;
            try {
                return extractServiceFromCursor(cursor);
            } finally {
                try { cursor.close(); } catch (Throwable ignore) {}
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            log("resolveServiceViaProvider invocation threw: "
                    + cause.getClass().getName() + ": " + cause.getMessage());
            return null;
        } catch (Throwable t) {
            log("resolveServiceViaProvider threw: " + t);
            return null;
        } finally {
            // Release the external hold so AMS doesn't leak a ref.
            if (amsService != null && holder != null) {
                try {
                    java.lang.reflect.Method rcpe = null;
                    for (java.lang.reflect.Method m : amsService.getClass().getMethods()) {
                        if (!"removeContentProviderExternal".equals(m.getName())) continue;
                        Class<?>[] p = m.getParameterTypes();
                        if (p.length == 2 && p[0] == String.class && p[1] == IBinder.class) {
                            rcpe = m;
                            break;
                        }
                    }
                    if (rcpe != null) rcpe.invoke(amsService, authority, externalToken);
                } catch (Throwable ignore) {}
            }
        }
    }

    private ICarPropertyService extractServiceFromCursor(Cursor cursor) {
        try {
            Bundle extras = cursor.getExtras();
            if (extras == null) {
                log("Cursor had no extras Bundle");
                return null;
            }
            extras.setClassLoader(getClass().getClassLoader());
            Parcelable wrapper = extras.getParcelable(EXTRAS_BINDER_KEY);
            if (wrapper == null) {
                log("Extras missing 'binder' key — keys=" + extras.keySet());
                return null;
            }
            IBinder binder = extractBinder(wrapper);
            if (binder == null) {
                log("BinderParcelable yielded null IBinder (wrapper=" + wrapper.getClass().getName() + ")");
                return null;
            }
            ICarPropertyService svc = ICarPropertyService.Stub.asInterface(binder);
            log("Resolved ICarPropertyService — alive=" + binder.isBinderAlive());
            return svc;
        } catch (Throwable t) {
            log("extractServiceFromCursor threw: " + t);
            return null;
        }
    }

    /**
     * Pull the {@link IBinder} out of the {@code BinderParcelable} wrapper
     * via reflection — works whether the unmarshalled instance is our local
     * mirror class or DiCarServer's, since both expose an {@code mBinder}
     * field of type {@link IBinder}.
     */
    private IBinder extractBinder(Parcelable wrapper) {
        try {
            java.lang.reflect.Field f = wrapper.getClass().getDeclaredField("mBinder");
            f.setAccessible(true);
            Object v = f.get(wrapper);
            if (v instanceof IBinder) return (IBinder) v;
        } catch (NoSuchFieldException e) {
            for (java.lang.reflect.Field f : wrapper.getClass().getDeclaredFields()) {
                if (IBinder.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(wrapper);
                        if (v instanceof IBinder) return (IBinder) v;
                    } catch (Throwable ignore) {}
                }
            }
            log("BinderParcelable has no IBinder field. Class=" + wrapper.getClass().getName());
        } catch (Throwable t) {
            log("extractBinder threw: " + t);
        }
        return null;
    }

    private static void log(String msg) {
        try { CameraDaemon.log(TAG + ": " + msg); }
        catch (Throwable ignore) { android.util.Log.i(TAG, msg); }
    }

    // ── Result types ──

    public static final class Result {
        public final boolean success;
        public final int statusCode;
        public final String description;
        public final String error;

        public Result(boolean success, int statusCode, String description) {
            this.success = success;
            this.statusCode = statusCode;
            this.description = description == null ? "" : description;
            this.error = null;
        }
        private Result(String error) {
            this.success = false;
            this.statusCode = Status.STATUS_UNKNOWN_ERROR;
            this.description = "";
            this.error = error;
        }
        public static Result error(String msg) { return new Result(msg); }
    }

    public static final class ConfigResult {
        public final boolean success;
        public final int access;          // 1=R, 2=W, 3=RW
        public final long featureId;
        public final String typeName;
        public final String providerName;
        public final String readPermission;
        public final String writePermission;
        public final String error;

        public ConfigResult(boolean success, int access, long featureId,
                            String typeName, String providerName,
                            String readPermission, String writePermission) {
            this.success = success;
            this.access = access;
            this.featureId = featureId;
            this.typeName = typeName;
            this.providerName = providerName;
            this.readPermission = readPermission;
            this.writePermission = writePermission;
            this.error = null;
        }
        private ConfigResult(String error) {
            this.success = false;
            this.access = 0;
            this.featureId = 0;
            this.typeName = null;
            this.providerName = null;
            this.readPermission = null;
            this.writePermission = null;
            this.error = error;
        }
        public static ConfigResult error(String msg) { return new ConfigResult(msg); }
    }

    public static final class ReadResult {
        public final boolean success;
        public final int statusCode;
        public final String description;
        public final Integer intValue;
        public final String stringValue;
        public final String resultClass;
        public final String error;

        public ReadResult(boolean success, int statusCode, String description,
                          Integer intValue, String stringValue, String resultClass) {
            this.success = success;
            this.statusCode = statusCode;
            this.description = description == null ? "" : description;
            this.intValue = intValue;
            this.stringValue = stringValue;
            this.resultClass = resultClass;
            this.error = null;
        }
        private ReadResult(String error) {
            this.success = false;
            this.statusCode = Status.STATUS_UNKNOWN_ERROR;
            this.description = "";
            this.intValue = null;
            this.stringValue = null;
            this.resultClass = null;
            this.error = error;
        }
        public static ReadResult error(String msg) { return new ReadResult(msg); }
    }
}
