package com.overdrive.app.remoteaccess;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Build;
import android.os.IInterface;
import android.os.SystemClock;
import android.util.Log;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.remote.RemoteDevViewBridgeAuth;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.util.DaemonHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.Collections;
import java.lang.reflect.Field;

/**
 * Collector in the real app process, controlled by the existing authenticated bridge.
 * Disabled means no feature worker, receiver, timer, scan, wake lock or GATT client.
 */
public final class BluetoothAccessMonitor {
    private static final String TAG = "BluetoothAccessMonitor";
    private static BluetoothAccessMonitor instance;
    private final JSONObject config;
    private final Context context;
    private final String instanceId = UUID.randomUUID().toString();
    private final List<PhoneWorker> workers = new ArrayList<>();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final BluetoothPhoneReconnectController reconnect;
    private volatile boolean running = true;

    private BluetoothAccessMonitor(Context context, JSONObject config) {
        this.config = config;
        this.context = context.getApplicationContext();
        JSONArray phones = config.optJSONArray("phones");
        if (phones != null) for (int i = 0; i < phones.length(); i++) {
            JSONObject phone = phones.optJSONObject(i);
            if (phone != null && phone.optBoolean("enabled")) workers.add(new PhoneWorker(phone.optString("address")));
        }
        activeWorkers.set(workers.size());
        List<String> approvedPhones = new ArrayList<>();
        for (PhoneWorker worker : workers) approvedPhones.add(worker.address);
        reconnect = new BluetoothPhoneReconnectController(approvedPhones,
                new AndroidBluetoothPhoneReconnect(this.context), this::reconnectBlockReason,
                new BluetoothPhoneReconnectController.Clock() {
                    @Override public long elapsedRealtime() { return SystemClock.elapsedRealtime(); }
                    @Override public long wallTimeMillis() { return System.currentTimeMillis(); }
                });
    }

    /** One startup read; a disabled feature never schedules a config poll. */
    public static void resumeIfEnabled(Context context) {
        if (RemoteAccessSettings.read().optBoolean("enabled")) {
            try {
                JSONObject result = command(context, new JSONObject().put("operation", "apply"));
                if (!result.optBoolean("success"))
                    Log.w(TAG, "Monitoring could not resume: " + result.optString("error", "unavailable"));
            } catch (Exception unavailable) {
                Log.w(TAG, "Monitoring could not resume: " + unavailable.getClass().getSimpleName());
            }
        }
    }

    public static synchronized JSONObject command(Context context, JSONObject request) throws Exception {
        String operation = request.optString("operation");
        if ("devices".equals(operation))
            return new JSONObject().put("success", true).put("devices", devices());
        if ("apply".equals(operation)) {
            JSONObject settings = RemoteAccessSettings.read();
            KeyFobAccessMonitor.apply(context, settings);
            JSONArray phones = settings.getJSONArray("phones");
            boolean hasEnabledPhone = false;
            for (int i = 0; i < phones.length(); i++)
                hasEnabledPhone |= phones.getJSONObject(i).optBoolean("enabled");
            if (instance != null && (!settings.optBoolean("enabled")
                    || !settings.optBoolean("keepBluetoothOn")
                    || !hasEnabledPhone
                    || !sameMonitorConfig(settings, instance.config)
                    || !instance.running)) stop();
            if (settings.optBoolean("enabled") && instance != null && !instance.running)
                return new JSONObject().put("success", false).put("error", "Previous monitor is still stopping")
                        .put("runtime", runtime(context));
            if (settings.optBoolean("enabled") && settings.optBoolean("keepBluetoothOn")
                    && hasEnabledPhone && instance == null) {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                BondedSnapshot paired = readBonded(adapter);
                boolean radioOn = radioOn(adapter);
                for (int i = 0; i < phones.length(); i++) {
                    JSONObject phone = phones.getJSONObject(i);
                    if (phone.optBoolean("enabled")
                            && BluetoothMonitorStartupPolicy.decide(radioOn, paired.available,
                                paired.find(phone.getString("address")) != null)
                                == BluetoothMonitorStartupPolicy.Decision.REJECT_UNPAIRED) {
                        Log.w(TAG, "Monitoring rejected: a trusted phone is no longer paired");
                        return new JSONObject().put("success", false).put("error", "A trusted phone is no longer paired")
                                .put("runtime", runtime(context));
                    }
                }
                instance = new BluetoothAccessMonitor(context, settings);
                for (PhoneWorker worker : instance.workers) worker.thread.start();
                Log.i(TAG, "Monitoring started for " + instance.workers.size()
                        + " trusted phone(s); radio=" + (radioOn ? "on" : "waiting"));
            }
            if (instance != null && instance.running) armProcessKeepAlive(context);
            return new JSONObject().put("success", true).put("runtime", runtime(context))
                    .put("keyFobRuntime", KeyFobAccessMonitor.runtime()).put("devices", devices());
        }
        if ("stop".equals(operation)) {
            stop();
            KeyFobAccessMonitor.stop();
            return new JSONObject().put("success", true).put("runtime", runtime(context))
                    .put("keyFobRuntime", KeyFobAccessMonitor.runtime());
        }
        return new JSONObject().put("success", false).put("error", "Unknown monitor operation");
    }

    /**
     * The collector only samples while this process lives, and nothing used to keep it
     * alive on proximity's own account: it free-rode on the surveillance daemon's
     * revival chain, so with the daemon off there was no keep-alive at all and a killed
     * process left the feature silently dead. Enabling proximity now arms the same two
     * mechanisms directly.
     *
     * <p>Gate G6 is deliberately untouched. {@code schedule(context)} reads
     * {@code isVehicleOnOnlyMode()} itself and cancels rather than arms in that mode, so
     * this adds no parked wake-ups where the user asked for none — proximity is already
     * ineligible there anyway ({@code eligible} requires {@code !isVehicleOnOnlyMode()}).
     */
    private static void armProcessKeepAlive(Context context) {
        try {
            com.overdrive.app.services.DaemonKeepaliveService.Companion
                    .start(context.getApplicationContext());
        } catch (Throwable unavailable) {
            Log.w(TAG, "Keep-alive service unavailable: " + unavailable.getClass().getSimpleName());
        }
        try {
            com.overdrive.app.receiver.ProcessRevivalReceiver.Companion
                    .schedule(context.getApplicationContext());
        } catch (Throwable unavailable) {
            Log.w(TAG, "Revival alarms unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    public static synchronized void stop() {
        BluetoothAccessMonitor current = instance;
        if (current == null) return;
        current.running = false;
        current.reconnect.stop();
        Log.i(TAG, "Monitoring stopping for " + current.workers.size() + " trusted phone(s)");
        for (PhoneWorker worker : current.workers) {
            worker.thread.interrupt();
            HttpURLConnection connection = worker.relay;
            if (connection != null) connection.disconnect();
            worker.releaseReader();
        }
        long deadline = SystemClock.elapsedRealtime() + 2000;
        for (PhoneWorker worker : current.workers) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            try { worker.thread.join(remaining); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
        }
        boolean stopped = true;
        for (PhoneWorker worker : current.workers) stopped &= !worker.thread.isAlive();
        if (stopped) instance = null;
    }

    private static JSONObject runtime(Context context) throws Exception {
        BluetoothAccessMonitor current = instance;
        JSONObject radio = SharedBluetoothKeepAliveController.runtime(context);
        boolean registered = false;
        boolean stopping = false;
        if (current != null) for (PhoneWorker worker : current.workers)
            {
                registered |= worker.reader != null && worker.reader.isRegistered();
                stopping |= !current.running && worker.thread.isAlive();
            }
        return new JSONObject().put("active", current != null && current.running)
                .put("readerRegistered", registered)
                .put("phoneCount", current == null ? 0 : current.workers.size())
                .put("radioOwned", radio.optBoolean("radioOwned"))
                .put("bluetoothKeepAlive", radio.optBoolean("enabled"))
                .put("bluetoothRuntime", radio).put("cleanupPending", stopping);
    }

    private static boolean sameMonitorConfig(JSONObject first, JSONObject second) {
        try {
            JSONObject a = new JSONObject(first.toString()), b = new JSONObject(second.toString());
            // Radio intent is shared across pages. Changing it never creates a
            // second sampler; each tick reads the common controller.
            a.remove("keepBluetoothOn");
            b.remove("keepBluetoothOn");
            return a.toString().equals(b.toString());
        } catch (Exception invalid) { return false; }
    }

    /** Reads shared consent and this monitor's generation again at each reconnect boundary. */
    private String reconnectBlockReason() {
        if (!running) return "master_off";
        try {
            UnifiedConfigManager.forceReload();
            JSONObject persisted = RemoteAccessSettings.read();
            if (!running || !persisted.optBoolean("enabled")) return "master_off";
            if (!sameMonitorConfig(persisted, config)) return "settings_changed";
            if (!persisted.optBoolean("keepBluetoothOn")) return "shared_bluetooth_off";
            if (UnifiedConfigManager.isVehicleOnOnlyMode()) return "vehicle_on_only";
            int power = VehiclePowerReader.readNow(context);
            return power == 0 ? null : power > 0 ? "vehicle_on" : "power_unknown";
        } catch (Throwable unavailable) { return "settings_unavailable"; }
    }

    private JSONObject reconnectSnapshot(String address) throws Exception {
        BluetoothPhoneReconnectController.Snapshot value = reconnect.snapshot(address);
        return new JSONObject().put("state", value.state).put("reason", value.reason)
                .put("lastAttemptMs", value.lastAttemptMs).put("retryInMs", value.retryInMs)
                .put("attemptCount", value.attemptCount)
                .put("errorStage", value.errorStage).put("errorClass", value.errorClass);
    }

    /** Consent is reread at each native connection-proof boundary. */
    private boolean collectorAuthorized() {
        if (!running) return false;
        try {
            UnifiedConfigManager.forceReload();
            JSONObject persisted = RemoteAccessSettings.read();
            return running && persisted.optBoolean("enabled")
                    && persisted.optBoolean("keepBluetoothOn")
                    && sameMonitorConfig(persisted, config);
        } catch (Exception | LinkageError unavailable) { return false; }
    }

    private final class PhoneWorker {
        private final String address;
        private final int awayThreshold;
        private final BluetoothProximitySamplingPolicy sampling = new BluetoothProximitySamplingPolicy();
        private final Thread thread;
        private volatile BluetoothRssiReader reader;
        private volatile HttpURLConnection relay;
        private long observationSequence;
        private long nextDiagnosticAt;
        private String lastDiagnostic;

        PhoneWorker(String address) {
            this.address = address;
            JSONObject phone = RemoteAccessSettings.phone(config, address);
            this.awayThreshold = phone == null ? config.optInt("awayThreshold", -24)
                    : phone.optInt("awayThreshold", config.optInt("awayThreshold", -24));
            thread = new Thread(this::collect, "OverDrive-Proximity-" + workers.size());
            thread.setDaemon(true);
        }

    private void collect() {
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                long cycleStartedMs = SystemClock.elapsedRealtime();
                // Restores/imports also revoke monitoring without relying on a web client.
                JSONObject persisted = RemoteAccessSettings.read();
                if (!persisted.optBoolean("enabled") || !persisted.optBoolean("keepBluetoothOn")
                        || !sameMonitorConfig(persisted, config)) {
                    Log.i(TAG, "Phone worker stopping: " + (persisted.optBoolean("enabled")
                            ? "settings changed" : "master off"));
                    break;
                }
                Integer raw = null;
                boolean connected = false;
                String reason = "disconnected";
                JSONObject radio = new JSONObject();
                BluetoothConnectionStateReader.Snapshot connectionProof =
                        BluetoothConnectionStateReader.Snapshot.unknown("connection_state_unavailable");
                BluetoothAdapter observedAdapter = null;
                try {
                    radio = SharedBluetoothKeepAliveController.runtime(context);
                    if (radio.optBoolean("enabled") || radio.optBoolean("radioOwned")
                            || radio.optBoolean("cleanupPending")) {
                        int power = VehiclePowerReader.readNow(context);
                        radio = SharedBluetoothKeepAliveController.reconcile(
                                context, power == 0, power > 0, false);
                    }
                    observedAdapter = BluetoothAdapter.getDefaultAdapter();
                    connectionProof = BluetoothConnectionStateReader.read(
                            context, observedAdapter, address,
                            BluetoothAccessMonitor.this::collectorAuthorized);
                    connected = connectionProof.connected();
                    if (!connectionProof.connectionReadKnown) {
                        releaseReader();
                        sampling.reset();
                        reason = connectionProof.reason;
                    } else if (!connected) {
                        releaseReader();
                        sampling.reset();
                    } else {
                        BluetoothRssiReader current = reader;
                        if (current == null) {
                            synchronized (this) {
                                if (!running) break;
                                current = new BluetoothRssiReader(context, address);
                                reader = current;
                            }
                        }
                        raw = current.read();
                        reason = raw == null ? "rssi_unavailable" : "reading";
                        if (raw == null) releaseReader();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable unavailable) {
                    releaseReader();
                    sampling.reset();
                    reason = unavailable instanceof UnsupportedOperationException
                            ? "unsupported_platform" : "rssi_unavailable";
                }
                long observedAtElapsedMs = SystemClock.elapsedRealtime();
                if (!running || Thread.currentThread().isInterrupted()) break;
                if (!collectorAuthorized()) break;
                // RSSI registration/read may wait. Do not publish native proof
                // across a radio, adapter or committed-mode change during it.
                try {
                    DiLink5Platform.ActiveModeSnapshot mode = DiLink5Platform.currentActiveModeSnapshot();
                    if (observedAdapter == null
                            || observedAdapter != BluetoothAdapter.getDefaultAdapter()
                            || observedAdapter.getState() != BluetoothAdapter.STATE_ON
                            || !connectionProof.matchesCommittedMode(mode)) {
                        connectionProof = BluetoothConnectionStateReader.Snapshot.unknown(
                                "connection_binding_changed");
                        raw = null;
                        connected = false;
                        reason = connectionProof.reason;
                        releaseReader();
                        sampling.reset();
                    }
                } catch (Exception | LinkageError unavailable) {
                    connectionProof = BluetoothConnectionStateReader.Snapshot.unknown(
                            "connection_state_unavailable");
                    raw = null;
                    connected = false;
                    reason = connectionProof.reason;
                    releaseReader();
                    sampling.reset();
                }
                try { reconnect.tick(address, connected); }
                catch (Throwable unavailable) { reconnect.stopAfterUnexpectedFailure(unavailable); }
                if (!running || Thread.currentThread().isInterrupted()) break;
                reportDiagnostic(reason);
                publish(raw, connected, reason, observedAtElapsedMs, connectionProof, radio);
                // Keep 1 Hz while the phone is near or in the transition band so
                // arrival confirmation remains snappy. Once two consecutive RSSI
                // readings are comfortably beyond the Away threshold, sample
                // every three seconds instead. A missing RSSI also uses the
                // slower retry cadence.
                // Disconnected recovery retains its existing shared three-second spacing.
                long intervalMs = connected ? sampling.nextIntervalMs(raw, awayThreshold)
                        : BluetoothPhoneReconnectController.RETRY_INTERVAL_MS;
                long sleepMs = Math.max(100, intervalMs
                        - (SystemClock.elapsedRealtime() - cycleStartedMs));
                Thread.sleep(sleepMs);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            releaseReader();
            if (activeWorkers.decrementAndGet() == 0) {
                running = false;
                reconnect.stop();
                Log.i(TAG, "Monitoring workers stopped");
            }
        }
    }

    private void reportDiagnostic(String reason) {
        BluetoothPhoneReconnectController.Snapshot state = reconnect.snapshot(address);
        String diagnostic = reason + "; reconnect=" + state.reason
                + (state.errorStage.isEmpty() ? "" : "; stage=" + state.errorStage)
                + (state.errorClass.isEmpty() ? "" : "; error=" + state.errorClass);
        long now = SystemClock.elapsedRealtime();
        if (!diagnostic.equals(lastDiagnostic) && now >= nextDiagnosticAt) {
            lastDiagnostic = diagnostic;
            nextDiagnosticAt = now + 30_000;
            Log.i(TAG, "Phone observation: " + diagnostic);
        }
    }

    private void publish(Integer raw, boolean connected, String reason, long observedAtElapsedMs,
            BluetoothConnectionStateReader.Snapshot connectionProof, JSONObject radio) {
        HttpURLConnection connection = null;
        try {
            JSONObject observation = new JSONObject().put("command", "remoteAccessObservation")
                    .put("instanceId", instanceId).put("observationSequence", ++observationSequence)
                    .put("revision", config.optLong("revision")).put("deviceAddress", address)
                    .put("rawRssi", raw == null ? JSONObject.NULL : raw)
                    .put("connected", connected).put("reason", reason)
                    .put("connectionReadKnown", connectionProof.connectionReadKnown)
                    .put("connectionState", connectionProof.connectionState)
                    .put("radioReadKnown", connectionProof.radioReadKnown)
                    .put("radioOn", connectionProof.radioOn)
                    .put("connectionObservedAtElapsedMs", connectionProof.connectionObservedAtElapsedMs)
                    .put("connectionModeGeneration", connectionProof.connectionModeGeneration)
                    .put("reconnect", reconnectSnapshot(address))
                    .put("observedAtElapsedMs", observedAtElapsedMs)
                    .put("runtime", new JSONObject().put("active", running)
                            .put("readerRegistered", reader != null && reader.isRegistered())
                            .put("radioOwned", radio.optBoolean("radioOwned"))
                            .put("bluetoothKeepAlive", radio.optBoolean("enabled"))
                            .put("bluetoothRuntime", radio));
            byte[] bytes = RemoteDevViewBridgeAuth.sign(observation).getBytes(StandardCharsets.UTF_8);
            connection = DaemonHttpClient.open("/api/remote-access/relay", "POST", 1500, 7000);
            relay = connection;
            if (!running) return;
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            connection.getResponseCode();
        } catch (Exception ignored) {
            // Daemon loss is unknown, never a departure. Only enabled ticks retry.
        } finally {
            relay = null;
            if (connection != null) connection.disconnect();
        }
    }

    private synchronized void releaseReader() {
        BluetoothRssiReader current = reader;
        reader = null;
        if (current != null) current.close();
    }
    }

    public static JSONArray devices() throws Exception {
        JSONArray result = new JSONArray();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) return result;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) for (BluetoothDevice device : bonded) {
            String name = device.getName();
            result.put(new JSONObject().put("address", device.getAddress().toUpperCase(Locale.US))
                    .put("name", name == null || name.trim().isEmpty() ? "Paired device" : name)
                    .put("connected", adapter.isEnabled() && connected(device, adapter)));
        }
        return result;
    }

    static BluetoothDevice findBonded(String address) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return readBonded(adapter).find(address);
    }

    private static boolean radioOn(BluetoothAdapter adapter) {
        try { return adapter != null && adapter.getState() == BluetoothAdapter.STATE_ON; }
        catch (RuntimeException unavailable) { return false; }
    }

    private static final class BondedSnapshot {
        final boolean available;
        final Iterable<BluetoothDevice> devices;

        BondedSnapshot(boolean available, Iterable<BluetoothDevice> devices) {
            this.available = available;
            this.devices = devices;
        }

        BluetoothDevice find(String address) {
            for (BluetoothDevice device : devices)
                if (device != null && address.equalsIgnoreCase(device.getAddress())) return device;
            return null;
        }
    }

    private static BondedSnapshot readBonded(BluetoothAdapter adapter) {
        if (!radioOn(adapter)) return new BondedSnapshot(false, Collections.emptyList());
        try {
            Iterable<BluetoothDevice> devices;
            if (Build.VERSION.SDK_INT == 29) {
                // The API29 wrapper can return an empty set when its service is absent.
                // A direct Binder read preserves that failure as unknown instead of unpaired.
                Field field = BluetoothAdapter.class.getDeclaredField("mService");
                field.setAccessible(true);
                Object service = field.get(adapter);
                if (!(service instanceof IInterface) || ((IInterface) service).asBinder() == null
                        || !((IInterface) service).asBinder().isBinderAlive())
                    return new BondedSnapshot(false, Collections.emptyList());
                Object value = Class.forName("android.bluetooth.IBluetooth")
                        .getMethod("getBondedDevices").invoke(service);
                if (!(value instanceof BluetoothDevice[]))
                    return new BondedSnapshot(false, Collections.emptyList());
                devices = Arrays.asList((BluetoothDevice[]) value);
            } else {
                Set<BluetoothDevice> value = adapter.getBondedDevices();
                if (value == null) return new BondedSnapshot(false, Collections.emptyList());
                devices = value;
            }
            return radioOn(adapter) ? new BondedSnapshot(true, devices)
                    : new BondedSnapshot(false, Collections.emptyList());
        } catch (Exception unavailable) {
            return new BondedSnapshot(false, Collections.emptyList());
        }
    }

    static boolean connected(BluetoothDevice device, BluetoothAdapter adapter) {
        try { return Boolean.TRUE.equals(BluetoothDevice.class.getMethod("isConnected").invoke(device)); }
        catch (Exception ignored) {}
        try {
            Object values = adapter.getClass().getMethod("getConnectDevices").invoke(adapter);
            if (values instanceof Iterable) for (Object value : (Iterable<?>) values)
                if (value instanceof BluetoothDevice
                        && device.getAddress().equalsIgnoreCase(((BluetoothDevice) value).getAddress())) return true;
        } catch (Exception ignored) {}
        return false;
    }
}
