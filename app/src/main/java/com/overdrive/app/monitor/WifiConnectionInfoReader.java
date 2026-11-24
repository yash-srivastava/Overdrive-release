package com.overdrive.app.monitor;

import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wi-Fi connection details for the shell-uid daemon without the Wi-Fi service's dump: a direct
 * {@code IWifiManager.getConnectionInfo} call under the shell package, whose NETWORK_SETTINGS
 * grant returns the real SSID with no location gate.
 */
final class WifiConnectionInfoReader {

    static final String SHELL_PACKAGE = "com.android.shell";
    private static final int INVALID_RSSI = -127;

    /** One read. Null fields are unknown; {@code ssid} is also null when the name is withheld. */
    static final class Info {
        final String ssid;
        final Integer rssi;
        final Boolean meteredHint;

        Info(String ssid, Integer rssi, Boolean meteredHint) {
            this.ssid = ssid;
            this.rssi = rssi;
            this.meteredHint = meteredHint;
        }
    }

    static final Info NOT_CONNECTED = new Info(null, null, null);

    /** Bounded independent reads: a stuck Wi-Fi service may never spawn unlimited workers. */
    private static final BoundedRead<Info> CONNECTION = new BoundedRead<>("NetworkMonitor-WifiInfo");
    private static final BoundedRead<OwnerMetering> OWNER = new BoundedRead<>("NetworkMonitor-WifiOwner");

    private WifiConnectionInfoReader() {}

    static Info read(long deadlineMs) {
        return read(deadlineMs, WifiConnectionInfoReader::readNow);
    }

    /** Package-visible injection preserves the existing timeout/concurrency contract. */
    static Info read(long deadlineMs, Callable<Info> source) {
        return CONNECTION.read(deadlineMs, source);
    }

    static String lastFailure() {
        return CONNECTION.lastFailure;
    }

    /**
     * Read the connected network id AND its saved owner metering configuration as shell.
     * Never use the daemon's Context (which reports "android" for UID 2000), a Wi-Fi
     * service dump, or an unbounded binder call. Missing evidence means refusal.
     */
    static OwnerMetering readOwnerMetering(long deadlineMs) {
        return OWNER.read(deadlineMs, WifiConnectionInfoReader::readOwnerNow);
    }

    static String lastOwnerFailure() {
        return OWNER.lastFailure;
    }

    static final class OwnerMetering {
        final int networkId;
        final Boolean apMeteredHint;
        final boolean savedMeteredHint;
        final int ownerOverride;

        OwnerMetering(int networkId, Boolean apMeteredHint,
                boolean savedMeteredHint, int ownerOverride) {
            this.networkId = networkId;
            this.apMeteredHint = apMeteredHint;
            this.savedMeteredHint = savedMeteredHint;
            this.ownerOverride = ownerOverride;
        }
    }

    private static final class BoundedRead<T> {
        private final String threadName;
        private FutureTask<T> inFlight;
        private long startedNanos;
        volatile String lastFailure = "";

        BoundedRead(String threadName) {
            this.threadName = threadName;
        }

        T read(long deadlineMs, Callable<T> source) {
            FutureTask<T> task;
            long started;
            synchronized (this) {
                if (inFlight == null || inFlight.isDone()) {
                    inFlight = new FutureTask<>(source);
                    startedNanos = System.nanoTime();
                    Thread worker = new Thread(inFlight, threadName);
                    worker.setDaemon(true);
                    worker.start();
                }
                task = inFlight;
                started = startedNanos;
            }
            long remainingMs = deadlineMs
                    - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (remainingMs <= 0) {
                lastFailure = "stalled";
                return null;
            }
            try {
                return task.get(remainingMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastFailure = "interrupted";
            } catch (TimeoutException e) {
                lastFailure = "timeout";
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof InvocationTargetException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                lastFailure = cause == null ? "failed" : cause.getClass().getSimpleName();
            } catch (CancellationException e) {
                lastFailure = "cancelled";
            }
            return null;
        }
    }

    /** SDK 29: the connectivity service lists the Wi-Fi agent's SSID in its capabilities. */
    static String ssidFromConnectivity(long deadlineMs) {
        return parseConnectivitySsid(BoundedShell.run(
                "dumpsys connectivity networks 2>/dev/null | grep -F 'Transports: WIFI'",
                deadlineMs).output);
    }

    /** First SSID on a Wi-Fi {@code NetworkAgentInfo} line; null when absent or withheld. */
    static String parseConnectivitySsid(String output) {
        if (output == null) {
            return null;
        }
        for (String raw : output.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.startsWith("NetworkAgentInfo{") || !line.contains("Transports: WIFI")) {
                continue;
            }
            int key = line.indexOf(" SSID: \"");
            if (key < 0) {
                continue;
            }
            int start = key + " SSID: ".length();
            // The SSID is the last capabilities field: ... SSID: "name"]}
            int end = line.indexOf("\"]}", start + 1);
            if (end < 0) {
                continue;
            }
            String ssid = WifiStatusParser.normalizeSsid(line.substring(start, end + 1));
            if (ssid != null) {
                return ssid;
            }
        }
        return null;
    }

    private static Object wifiService() throws Exception {
        try {
            com.overdrive.app.shell.HiddenApiBypass.INSTANCE.bypass();
        } catch (Throwable ignored) {
        }
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "wifi");
        if (binder == null) {
            throw new IllegalStateException("wifi service unavailable");
        }
        return Class.forName("android.net.wifi.IWifiManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static WifiInfo connectionInfo(Object service) throws Exception {
        Class<?> api = Class.forName("android.net.wifi.IWifiManager");
        Object result;
        Method withFeature = connectionInfoMethod(api, String.class, String.class);
        if (withFeature != null) {
            result = withFeature.invoke(service, SHELL_PACKAGE, null);
        } else {
            Method legacy = connectionInfoMethod(api, String.class);
            if (legacy == null) {
                throw new NoSuchMethodException("getConnectionInfo");
            }
            result = legacy.invoke(service, SHELL_PACKAGE);
        }
        if (!(result instanceof WifiInfo)) {
            throw new IllegalStateException("no WifiInfo");
        }
        return (WifiInfo) result;
    }

    private static Info readNow() throws Exception {
        return fromWifiInfo(connectionInfo(wifiService()));
    }

    private static OwnerMetering readOwnerNow() throws Exception {
        Object service = wifiService();
        WifiInfo info = connectionInfo(service);
        if (info.getSupplicantState() != SupplicantState.COMPLETED || info.getNetworkId() < 0) {
            throw new IllegalStateException("connected shell Wi-Fi network id unavailable");
        }
        int networkId = info.getNetworkId();
        Boolean apHint = meterHint(info);
        Class<?> api = Class.forName("android.net.wifi.IWifiManager");
        // Android 10: (package); Android 11: (package, featureId);
        // newer framework: (package, featureId, callerNetworksOnly).
        Method method = connectionInfoMethod(api, "getConfiguredNetworks", String.class, String.class, boolean.class);
        Object result;
        if (method != null) {
            result = method.invoke(service, SHELL_PACKAGE, null, false);
        } else {
            method = connectionInfoMethod(api, "getConfiguredNetworks", String.class, String.class);
            if (method != null) {
                result = method.invoke(service, SHELL_PACKAGE, null);
            } else {
                method = connectionInfoMethod(api, "getConfiguredNetworks", String.class);
                if (method == null) throw new NoSuchMethodException("getConfiguredNetworks");
                result = method.invoke(service, SHELL_PACKAGE);
            }
        }
        // IWifiManager returns ParceledListSlice, not List, on Android 10+.
        Object entries = result instanceof List ? result
                : result == null ? null : result.getClass().getMethod("getList").invoke(result);
        if (!(entries instanceof List)) {
            throw new IllegalStateException("saved Wi-Fi configs unavailable");
        }
        for (Object value : (List<?>) entries) {
            if (!(value instanceof WifiConfiguration)) continue;
            WifiConfiguration config = (WifiConfiguration) value;
            if (config.networkId != networkId) continue;
            // Both fields are @SystemApi (absent from the compile SDK); a missing field refuses.
            int override = WifiConfiguration.class.getField("meteredOverride").getInt(config);
            boolean savedHint = WifiConfiguration.class.getField("meteredHint").getBoolean(config);
            return new OwnerMetering(networkId, apHint, savedHint, override);
        }
        throw new IllegalStateException("connected Wi-Fi config absent or redacted");
    }

    /** Android 11+ takes (package, featureId); Android 10 takes (package). */
    private static Method connectionInfoMethod(Class<?> api, Class<?>... params) {
        return connectionInfoMethod(api, "getConnectionInfo", params);
    }

    private static Method connectionInfoMethod(Class<?> api, String name, Class<?>... params) {
        try {
            return api.getMethod(name, params);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Boolean meterHint(WifiInfo info) {
        try {
            Object hint = WifiInfo.class.getMethod("getMeteredHint").invoke(info);
            return hint instanceof Boolean ? (Boolean) hint : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Info fromWifiInfo(WifiInfo info) {
        if (info.getSupplicantState() != SupplicantState.COMPLETED) {
            return NOT_CONNECTED;
        }
        Boolean metered = meterHint(info);
        int rssi = info.getRssi();
        return new Info(WifiStatusParser.normalizeSsid(info.getSSID()),
                rssi > INVALID_RSSI && rssi <= 0 ? rssi : null, metered);
    }
}
