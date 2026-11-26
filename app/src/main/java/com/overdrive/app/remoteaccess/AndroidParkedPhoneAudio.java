package com.overdrive.app.remoteaccess;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IInterface;
import android.os.Looper;
import android.util.Log;

import com.overdrive.app.camera.dilink5.DiLink5Platform;

import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Native side of {@link ParkedPhoneAudioGuard}: the car's HFP client (profile 16) call-audio
 * switch and its media sink (profile 11) connection. Holds its own profile bindings, separate
 * from the reconnect controller's, and never connects or disconnects HFP.
 */
final class AndroidParkedPhoneAudio implements ParkedPhoneAudioGuard.Platform {
    private static final String TAG = "ParkedPhoneAudio";
    static final int HFP_CLIENT = BluetoothPhoneReconnectController.PROFILE_ID;
    static final int MEDIA_SINK = 11; // Android A2DP_SINK: the car receives the phone's media.
    private static final long BIND_TIMEOUT_MS = 5_000L;
    private static final String PREFS = "parked_phone_audio";
    private static final String RECORDS_KEY = "records";

    private static ParkedPhoneAudioGuard shared;

    private final Context context;
    private final boolean modern;
    private final Map<Integer, BluetoothProfile> proxies = new LinkedHashMap<>();
    private Object attributionSource;

    private AndroidParkedPhoneAudio(Context context) {
        this.context = context.getApplicationContext();
        modern = BluetoothPlatformSupport.modernPlatform(Build.VERSION.SDK_INT);
    }

    /** The app process's one guard. */
    static synchronized ParkedPhoneAudioGuard shared(Context context) {
        if (shared == null) {
            Context app = context.getApplicationContext();
            java.util.concurrent.ScheduledThreadPoolExecutor worker =
                    new java.util.concurrent.ScheduledThreadPoolExecutor(1, runnable -> {
                        Thread thread = new Thread(runnable, "OverDrive-ParkedPhoneAudio");
                        thread.setDaemon(true);
                        return thread;
                    });
            worker.setRemoveOnCancelPolicy(true);
            shared = new ParkedPhoneAudioGuard(new AndroidParkedPhoneAudio(app), new PreferencesStore(app),
                    android.os.SystemClock::elapsedRealtime, message -> Log.i(TAG, message), worker);
        }
        return shared;
    }

    /**
     * Startup: a record left by a crash or kill restores unless a running collector still wants
     * the park applied; that collector re-applies on its next tick anyway.
     */
    static void restoreOnStartup(Context context) {
        try {
            Context app = context.getApplicationContext();
            shared(app).restoreAllAsync(() -> BluetoothAccessMonitor.parkedPhoneAudioWanted(app));
        } catch (Throwable unavailable) {
            Log.w(TAG, "Startup restore unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    @Override public boolean callAudioAllowed(String address) throws Exception {
        Object value = headsetCall("getAudioRouteAllowed", boolean.class,
                new Class<?>[]{BluetoothDevice.class}, device(address));
        if (!(value instanceof Boolean)) throw new IllegalStateException("Call audio state unavailable");
        return (Boolean) value;
    }

    @Override public void setCallAudioAllowed(String address, boolean allowed) throws Exception {
        headsetCall("setAudioRouteAllowed", void.class,
                new Class<?>[]{BluetoothDevice.class, boolean.class}, device(address), allowed);
    }

    @Override public boolean mediaConnected(String address) throws Exception {
        return media().getConnectionState(device(address)) == BluetoothProfile.STATE_CONNECTED;
    }

    @Override public boolean disconnectMedia(String address) throws Exception {
        return mediaCall("disconnect", device(address));
    }

    @Override public boolean connectMedia(String address) throws Exception {
        return mediaCall("connect", device(address));
    }

    @Override public synchronized void release() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        for (Map.Entry<Integer, BluetoothProfile> entry : proxies.entrySet()) {
            try { if (adapter != null) adapter.closeProfileProxy(entry.getKey(), entry.getValue()); }
            catch (RuntimeException ignored) {}
        }
        proxies.clear();
    }

    private Object headsetCall(String name, Class<?> result, Class<?>[] types, Object... arguments)
            throws Exception {
        if (!BluetoothPlatformSupport.verifiedPlatform(Build.VERSION.SDK_INT,
                Build.VERSION.SDK_INT != 29 && DiLink5Platform.isSelected()))
            throw new UnsupportedOperationException("Unverified Bluetooth platform");
        BluetoothProfile proxy = proxy(HFP_CLIENT);
        if (!"android.bluetooth.BluetoothHeadsetClient".equals(proxy.getClass().getName()))
            throw new UnsupportedOperationException("Unexpected HFP profile");
        Class<?> serviceType = Class.forName("android.bluetooth.IBluetoothHeadsetClient");
        Object service;
        if (modern) {
            service = BluetoothPlatformSupport.profileService(proxy, serviceType);
        } else {
            Method getService = proxy.getClass().getDeclaredMethod("getService");
            getService.setAccessible(true);
            service = getService.invoke(proxy);
        }
        if (!(service instanceof IInterface) || ((IInterface) service).asBinder() == null
                || !((IInterface) service).asBinder().isBinderAlive()) {
            dropProxy(HFP_CLIENT);
            throw new IllegalStateException("HFP service unavailable");
        }
        try {
            if (!modern) return serviceType.getMethod(name, types).invoke(service, arguments);
            return BluetoothNativeCall.verify(serviceType, name, result,
                    BluetoothNativeCall.frameworkAttributionType(),
                    BluetoothNativeCall.frameworkReceiverType(), types)
                    .invoke(service, attribution(), arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw wrapped;
        }
    }

    private boolean mediaCall(String name, BluetoothDevice device) throws Exception {
        BluetoothProfile proxy = media();
        try {
            Object accepted = proxy.getClass().getMethod(name, BluetoothDevice.class).invoke(proxy, device);
            return Boolean.TRUE.equals(accepted);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw wrapped;
        }
    }

    private BluetoothProfile media() throws Exception {
        BluetoothProfile proxy = proxy(MEDIA_SINK);
        if (!"android.bluetooth.BluetoothA2dpSink".equals(proxy.getClass().getName()))
            throw new UnsupportedOperationException("Unexpected media profile");
        return proxy;
    }

    private Object attribution() throws ReflectiveOperationException {
        if (attributionSource == null) attributionSource = BluetoothNativeCall.attributionSource(context);
        return attributionSource;
    }

    private BluetoothProfile proxy(int id) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Profile binding must not block the main thread");
        synchronized (this) {
            BluetoothProfile cached = proxies.get(id);
            if (cached != null) return cached;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || adapter.getState() != BluetoothAdapter.STATE_ON)
            throw new IllegalStateException("Bluetooth is off");
        CountDownLatch bound = new CountDownLatch(1);
        BluetoothProfile[] slot = new BluetoothProfile[1];
        boolean[] abandoned = new boolean[1];
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                synchronized (slot) {
                    if (abandoned[0]) {
                        try { adapter.closeProfileProxy(id, proxy); } catch (RuntimeException ignored) {}
                        return;
                    }
                    slot[0] = proxy;
                }
                bound.countDown();
            }

            @Override public void onServiceDisconnected(int profile) { dropProxy(id); }
        };
        if (!adapter.getProfileProxy(context, listener, id))
            throw new UnsupportedOperationException("Profile " + id + " unavailable");
        boolean ready = bound.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        BluetoothProfile proxy;
        synchronized (slot) {
            proxy = slot[0];
            if (!ready || proxy == null) {
                abandoned[0] = true;
                throw new IllegalStateException("Profile " + id + " did not bind");
            }
        }
        synchronized (this) {
            BluetoothProfile raced = proxies.get(id);
            if (raced != null) {
                try { adapter.closeProfileProxy(id, proxy); } catch (RuntimeException ignored) {}
                return raced;
            }
            proxies.put(id, proxy);
        }
        return proxy;
    }

    private synchronized void dropProxy(int id) {
        BluetoothProfile proxy = proxies.remove(id);
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (proxy != null && adapter != null) {
            try { adapter.closeProfileProxy(id, proxy); } catch (RuntimeException ignored) {}
        }
    }

    private static BluetoothDevice device(String address) {
        BluetoothDevice device = BluetoothAccessMonitor.findBonded(address);
        if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED)
            throw new IllegalStateException("Trusted phone is no longer paired");
        return device;
    }

    /** App-private, synchronous so the write-ahead record is durable before any change. */
    static final class PreferencesStore implements ParkedPhoneAudioGuard.Store {
        private final SharedPreferences prefs;

        PreferencesStore(Context context) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        @Override public Map<String, ParkedPhoneAudioGuard.Record> load() {
            return decode(prefs.getString(RECORDS_KEY, null));
        }

        @Override public boolean save(Map<String, ParkedPhoneAudioGuard.Record> records) {
            String encoded = encode(records);
            return encoded != null && (records.isEmpty()
                    ? prefs.edit().remove(RECORDS_KEY).commit()
                    : prefs.edit().putString(RECORDS_KEY, encoded).commit());
        }
    }

    static String encode(Map<String, ParkedPhoneAudioGuard.Record> records) {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, ParkedPhoneAudioGuard.Record> entry : records.entrySet()) {
                ParkedPhoneAudioGuard.Record record = entry.getValue();
                json.put(entry.getKey(), new JSONObject()
                        .put("prior", record.priorAllowed == null ? JSONObject.NULL : record.priorAllowed)
                        .put("media", record.mediaDisconnected));
            }
            return json.toString();
        } catch (Exception invalid) {
            return null;
        }
    }

    /** A malformed record still restores: unknown prior means "allowed", the platform default. */
    static Map<String, ParkedPhoneAudioGuard.Record> decode(String encoded) {
        Map<String, ParkedPhoneAudioGuard.Record> records = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) return records;
        try {
            JSONObject json = new JSONObject(encoded);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject value = json.optJSONObject(key);
                Object prior = value == null ? null : value.opt("prior");
                records.put(key, new ParkedPhoneAudioGuard.Record(
                        prior instanceof Boolean ? (Boolean) prior : null,
                        value == null || value.optBoolean("media", false)));
            }
        } catch (Exception malformed) {
            // Nothing addressable to restore; the platform default allows call audio.
        }
        return records;
    }
}
