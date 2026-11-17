package com.overdrive.app.remoteaccess;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IInterface;
import android.os.SystemClock;

import com.overdrive.app.camera.dilink5.DiLink5Platform;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Verified native HFP bridge, controlled by the shared coordinator. */
final class AndroidBluetoothPhoneReconnect implements BluetoothPhoneReconnectController.Platform {
    private final Context context;
    private final boolean platformSupported;
    private final String connectionPermissionMethod;
    private final boolean modern;
    private final Object attributionSource;
    private HeadsetProfile pendingProfile;
    private final Set<String> pairing = new HashSet<>();
    private long pairingGraceUntil;
    private boolean receiverRegistered;
    private BluetoothPhoneReconnectController.Failure receiverFailure;
    private boolean closed;
    private BluetoothAdapter recoveryAdapter;
    private Object recoveryBinder;
    private int recoveryPermissions = -1;
    private long recoveryGeneration;
    private final BroadcastReceiver pairingReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction()))
                return;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            synchronized (AndroidBluetoothPhoneReconnect.this) {
                if (closed) return;
                if (device != null && state == BluetoothDevice.BOND_BONDING) {
                    pairing.add(device.getAddress());
                } else {
                    if (device != null) pairing.remove(device.getAddress());
                    // Yield normal pairing/reconnection setup after completion or failure.
                    pairingGraceUntil = SystemClock.elapsedRealtime() + 30_000;
                }
            }
        }
    };

    AndroidBluetoothPhoneReconnect(Context context) {
        this.context = context.getApplicationContext();
        modern = BluetoothPlatformSupport.modernPlatform(Build.VERSION.SDK_INT);
        boolean supported = BluetoothPlatformSupport.reconnectSupported();
        Object source = null;
        if (supported && modern) {
            try {
                source = BluetoothNativeCall.attributionSource(this.context);
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                supported = false;
                receiverFailure = new BluetoothPhoneReconnectController.Failure(
                        "caller_attribution", unavailable);
            }
        }
        platformSupported = supported;
        attributionSource = source;
        connectionPermissionMethod = platformSupported
                ? BluetoothPlatformSupport.connectionPermissionMethod(
                        Build.VERSION.SDK_INT,
                        Build.VERSION.SDK_INT != 29 && DiLink5Platform.isSelected())
                : null;
        if (platformSupported) {
            try {
                // This protected system action is observed only while the master monitor exists.
                this.context.registerReceiver(pairingReceiver,
                        new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
                receiverRegistered = true;
            } catch (RuntimeException unavailable) {
                // No pairing observation means recovery remains unavailable, never permissive.
                receiverFailure = new BluetoothPhoneReconnectController.Failure(
                        "receiver_registration", unavailable);
            }
        }
    }

    @Override public boolean supported() { return platformSupported; }

    @Override public synchronized long recoveryGeneration() {
        // This is a local capability read. Creating another HFP proxy must not
        // reopen a permission circuit that the same native service just refused.
        int permissions = 0;
        String[] names = {"android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN",
                "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_PRIVILEGED"};
        for (int i = 0; i < names.length; i++) {
            if (context.checkSelfPermission(names[i])
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) permissions |= 1 << i;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        Object binder = recoveryBinder;
        try {
            Field field = BluetoothAdapter.class.getDeclaredField("mService");
            field.setAccessible(true);
            Object service = adapter == null ? null : field.get(adapter);
            binder = service instanceof IInterface ? ((IInterface) service).asBinder() : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // An unobservable service is not evidence of a changed service.
        }
        if (adapter != recoveryAdapter || binder != recoveryBinder
                || permissions != recoveryPermissions) {
            recoveryAdapter = adapter;
            recoveryBinder = binder;
            recoveryPermissions = permissions;
            recoveryGeneration++;
        }
        return recoveryGeneration;
    }

    @Override public boolean adapterOn() {
        return phase("adapter_state", () -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter != null && adapter.getState() == BluetoothAdapter.STATE_ON;
        });
    }

    @Override public boolean bonded(String address) {
        return phase("bonded_devices", () -> {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            Object service = adapterService(adapter);
            Object value = invokeAdapter(service, "getBondedDevices", BluetoothDevice[].class,
                    new Class<?>[0]);
            if (!(value instanceof BluetoothDevice[])) throw new IllegalStateException();
            for (BluetoothDevice device : (BluetoothDevice[]) value) {
                if (device == null) throw new IllegalStateException();
                if (address.equalsIgnoreCase(device.getAddress()))
                    return device.getBondState() == BluetoothDevice.BOND_BONDED;
            }
            return false;
        });
    }

    @Override public boolean connected(String address) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice device = BluetoothAccessMonitor.findBonded(address);
        return adapter != null && device != null && BluetoothAccessMonitor.connected(device, adapter);
    }

    @Override public String activityBlockReason(String address) throws Exception {
        synchronized (this) {
            if (closed) return "bluetooth_state_unavailable";
            if (!receiverRegistered) throw receiverFailure != null ? receiverFailure
                    : new BluetoothPhoneReconnectController.Failure(
                            "receiver_registration", new IllegalStateException());
            if (!pairing.isEmpty()) return "pairing_active";
            if (SystemClock.elapsedRealtime() < pairingGraceUntil) return "pairing_grace";
        }
        BluetoothAdapter adapter = phase("adapter_state", BluetoothAdapter::getDefaultAdapter);
        if (adapter == null || !phase("adapter_state",
                () -> adapter.getState() == BluetoothAdapter.STATE_ON)) return "radio_off";
        // These adapter wrappers return idle/false after an IPC failure.
        // Read the same live Binder service so errors pause recovery.
        Object service = adapterService(adapter);
        Object discovering = phase("discovery_state", () -> {
            Object value = invokeAdapter(service, "isDiscovering", boolean.class,
                    new Class<?>[0]);
            if (!(value instanceof Boolean)) throw new IllegalStateException();
            return value;
        });
        if ((Boolean) discovering) return "discovery_active";
        Object paired = phase("bonded_devices", () -> {
            Object value = invokeAdapter(service, "getBondedDevices", BluetoothDevice[].class,
                    new Class<?>[0]);
            if (!(value instanceof BluetoothDevice[])) throw new IllegalStateException();
            return value;
        });
        for (BluetoothDevice device : (BluetoothDevice[]) paired) {
            if (device == null) throw new IllegalStateException("Paired device unavailable");
            if (address.equalsIgnoreCase(device.getAddress())) continue;
            Object state = phase("other_device_state", () -> {
                Object value = invokeAdapter(service, "getConnectionState", int.class,
                        new Class<?>[]{BluetoothDevice.class}, device);
                if (!(value instanceof Integer)) throw new IllegalStateException();
                return value;
            });
            if ((Integer) state != 0) return "other_bluetooth_active";
        }
        // Use the OEM connected list when offered, without requiring that nonstandard API.
        try {
            Object current = adapter.getClass().getMethod("getConnectDevices").invoke(adapter);
            if (!(current instanceof Iterable))
                throw new IllegalStateException("Connected-device state unavailable");
            for (Object value : (Iterable<?>) current) {
                if (!(value instanceof BluetoothDevice))
                    throw new IllegalStateException("Connected device unavailable");
                if (!address.equalsIgnoreCase(((BluetoothDevice) value).getAddress()))
                    return "other_bluetooth_active";
            }
        } catch (NoSuchMethodException absentOnAosp) {
            // Aggregate and individual Binder reads cover both verified layouts.
        } catch (Exception unavailable) {
            throw new BluetoothPhoneReconnectController.Failure("connected_devices", unavailable);
        }
        Object state = phase("adapter_connection_state", () -> {
            Object value = invokeAdapter(service, "getAdapterConnectionState", int.class,
                    new Class<?>[0]);
            if (!(value instanceof Integer)) throw new IllegalStateException();
            return value;
        });
        return (Integer) state == BluetoothProfile.STATE_DISCONNECTED ? null : "bluetooth_busy";
    }

    @Override public synchronized boolean openProfile(
            BluetoothPhoneReconnectController.ProfileListener listener) throws Exception {
        if (closed || !platformSupported) return false;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || adapter.getState() != BluetoothAdapter.STATE_ON) return false;
        BluetoothProfile.ServiceListener callback = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int id, BluetoothProfile proxy) {
                synchronized (AndroidBluetoothPhoneReconnect.this) {
                    if (pendingProfile != null && pendingProfile.proxy == proxy) pendingProfile = null;
                }
                // This listener belongs to the HFP client proxy we created. Its
                // close operation must use that ownership even if the reported
                // callback ID is unexpected; the coordinator rejects such IDs.
                listener.connected(id, new HeadsetProfile(
                        BluetoothPhoneReconnectController.PROFILE_ID, proxy,
                        connectionPermissionMethod, modern, attributionSource));
            }

            @Override public void onServiceDisconnected(int id) { listener.disconnected(id); }
        };
        // getProfileProxy(16) invokes this verified constructor but hides the
        // object until its callback. Retain it immediately to close an unfinished
        // binding on stop/timeout, even if the service never supplies a callback.
        Object value = phase("profile_binding", () -> {
            Class<?> proxyType = Class.forName("android.bluetooth.BluetoothHeadsetClient");
            Constructor<?> constructor = modern
                    ? proxyType.getDeclaredConstructor(Context.class,
                            BluetoothProfile.ServiceListener.class, BluetoothAdapter.class)
                    : proxyType.getDeclaredConstructor(Context.class,
                            BluetoothProfile.ServiceListener.class);
            constructor.setAccessible(true);
            Object proxy = modern ? constructor.newInstance(context, callback, adapter)
                    : constructor.newInstance(context, callback);
            if (!(proxy instanceof BluetoothProfile)) throw new IllegalStateException();
            return proxy;
        });
        pendingProfile = new HeadsetProfile(BluetoothPhoneReconnectController.PROFILE_ID,
                (BluetoothProfile) value, connectionPermissionMethod, modern, attributionSource);
        return true;
    }

    @Override public synchronized void cancelPendingProfile() {
        HeadsetProfile pending = pendingProfile;
        pendingProfile = null;
        closeProfile(pending);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cancelPendingProfile();
        pairing.clear();
        if (receiverRegistered) {
            receiverRegistered = false;
            try { context.unregisterReceiver(pairingReceiver); }
            catch (RuntimeException ignored) {}
        }
    }

    @Override public void closeProfile(BluetoothPhoneReconnectController.Profile profile) {
        if (!(profile instanceof HeadsetProfile)) return;
        HeadsetProfile headset = (HeadsetProfile) profile;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) adapter.closeProfileProxy(headset.id, headset.proxy);
    }

    private static final class HeadsetProfile implements BluetoothPhoneReconnectController.Profile {
        private final int id;
        private final BluetoothProfile proxy;
        private final String connectionPermissionMethod;
        private final boolean modern;
        private final Object attributionSource;

        HeadsetProfile(int id, BluetoothProfile proxy, String connectionPermissionMethod,
                boolean modern, Object attributionSource) {
            this.id = id;
            this.proxy = proxy;
            this.connectionPermissionMethod = connectionPermissionMethod;
            this.modern = modern;
            this.attributionSource = attributionSource;
        }

        @Override public Collection<?> busyDevices() throws Exception {
            // The public proxy returns an empty list on Binder failure. Query its service
            // directly so a failed read can never be mistaken for a free profile.
            return phase("profile_busy_devices", () -> {
                Object result = invokeService("getDevicesMatchingConnectionStates",
                        new Class<?>[]{int[].class}, new int[]{1, 2, 3});
                if (!(result instanceof Collection)) throw new IllegalStateException();
                return (Collection<?>) result;
            });
        }

        @Override public int connectionState(String address) throws Exception {
            return phase("profile_connection_state", () -> integer(invokeService("getConnectionState",
                    new Class<?>[]{BluetoothDevice.class}, device(address))));
        }

        @Override public int priority(String address) throws Exception {
            return phase("profile_priority", () -> integer(invokeService(connectionPermissionMethod,
                    new Class<?>[]{BluetoothDevice.class}, device(address))));
        }

        @Override public boolean connect(String address) throws Exception {
            return phase("profile_connect", () -> {
                Object accepted = invokeService("connect",
                        new Class<?>[]{BluetoothDevice.class}, device(address));
                if (!(accepted instanceof Boolean)) throw new IllegalStateException();
                return (Boolean) accepted;
            });
        }

        private Object invokeService(String name, Class<?>[] types, Object argument) throws Exception {
            if (id != BluetoothPhoneReconnectController.PROFILE_ID || proxy == null
                    || !"android.bluetooth.BluetoothHeadsetClient".equals(proxy.getClass().getName()))
                throw new IllegalStateException("Unexpected HFP profile");
            Object service = phase("profile_service", () -> {
                if (modern) {
                    Object value = BluetoothPlatformSupport.profileService(proxy,
                            Class.forName("android.bluetooth.IBluetoothHeadsetClient"));
                    requireLiveService(value);
                    return value;
                }
                Method getService = proxy.getClass().getDeclaredMethod("getService");
                getService.setAccessible(true);
                Object value = getService.invoke(proxy);
                requireLiveService(value);
                return value;
            });
            Class<?> serviceType = Class.forName("android.bluetooth.IBluetoothHeadsetClient");
            if (!modern) return serviceType.getMethod(name, types).invoke(service, argument);
            Class<?> resultType = "getDevicesMatchingConnectionStates".equals(name)
                    ? java.util.List.class : "connect".equals(name) ? boolean.class : int.class;
            return BluetoothNativeCall.verify(serviceType, name, resultType,
                    BluetoothNativeCall.frameworkAttributionType(),
                    BluetoothNativeCall.frameworkReceiverType(), types)
                    .invoke(service, attributionSource, argument);
        }

        private static BluetoothDevice device(String address) {
            BluetoothDevice device = BluetoothAccessMonitor.findBonded(address);
            if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED)
                throw new IllegalStateException("Trusted phone is no longer paired");
            return device;
        }

        private static int integer(Object value) {
            if (!(value instanceof Integer)) throw new IllegalStateException("HFP state unavailable");
            return (Integer) value;
        }
    }

    private Object invokeAdapter(Object service, String name, Class<?> result,
            Class<?>[] types, Object... arguments) throws Exception {
        Class<?> owner = Class.forName("android.bluetooth.IBluetooth");
        if (!modern) return owner.getMethod(name, types).invoke(service, arguments);
        return BluetoothNativeCall.verify(owner, name, result,
                BluetoothNativeCall.frameworkAttributionType(),
                BluetoothNativeCall.frameworkReceiverType(), types)
                .invoke(service, attributionSource, arguments);
    }

    private static void requireLiveService(Object service) {
        if (!(service instanceof IInterface) || ((IInterface) service).asBinder() == null
                || !((IInterface) service).asBinder().isBinderAlive())
            throw new BluetoothPhoneReconnectController.Failure(
                    "binder_unavailable", new IllegalStateException());
    }

    private static Object adapterService(BluetoothAdapter adapter) {
        return phase("adapter_service", () -> {
            Field field = BluetoothAdapter.class.getDeclaredField("mService");
            field.setAccessible(true);
            Object service = field.get(adapter);
            requireLiveService(service);
            return service;
        });
    }

    private interface NativeCall<T> {
        T run() throws Exception;
    }

    private static <T> T phase(String stage, NativeCall<T> operation) {
        try { return operation.run(); }
        catch (BluetoothPhoneReconnectController.Failure diagnosed) { throw diagnosed; }
        catch (Exception unavailable) {
            throw new BluetoothPhoneReconnectController.Failure(stage, unavailable);
        }
    }
}
