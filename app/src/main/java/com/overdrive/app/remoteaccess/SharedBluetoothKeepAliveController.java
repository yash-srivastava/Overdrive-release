package com.overdrive.app.remoteaccess;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.services.VehicleActuatorService;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.monitor.ParkedPosition;
import com.overdrive.app.surveillance.SafeLocationManager;

import org.json.JSONObject;

/**
 * App-process owner of parked Bluetooth intent and its durable radio lease.
 *
 * <p>This class starts no thread, timer, receiver, scan, connection, or wake lock.
 * Existing parked/proximity ticks invoke reconciliation; explicit radio controls
 * use the same lock and saved Surveillance preference.
 */
public final class SharedBluetoothKeepAliveController {
    private static final String PREFS = "shared_bluetooth_runtime";
    private static final String OWNED = "radioOwned";
    private static final String LEGACY_ADOPTED = "remoteAccessLeaseAdopted";
    private static volatile String lastError = "";

    private SharedBluetoothKeepAliveController() {}

    /** One startup check; no power read is needed when neither consent nor a lease exists. */
    public static synchronized void resume(Context context) {
        try {
            SharedPreferences lease = lease(context);
            Consent consent = freshConsent();
            if (!consent.enabled && !lease.getBoolean(OWNED, false)) return;
            int power = VehiclePowerReader.readNow(context);
            reconcile(context, power == 0, power > 0, false);
        } catch (Throwable failure) {
            lastError = failure.getClass().getSimpleName();
        }
    }

    /**
     * Reconcile shared parked intent. Unknown/conflicting power never enables the radio.
     * An explicit saved OFF additionally turns off an initially unowned parked adapter.
     */
    public static synchronized JSONObject reconcile(
            Context context, boolean vehicleOff, boolean vehicleOn, boolean forceDisable) {
        try {
            SharedPreferences lease = lease(context);
            Consent consent = freshConsent();
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            int state = adapter == null ? -1 : adapter.getState();
            boolean owned = lease.getBoolean(OWNED, false);
            boolean safeZoneBluetoothOff = vehicleOff && !vehicleOn && isInsideUnlockRestrictedSafeZone();
            ParkedBluetoothKeepAlivePolicy.Action action = ParkedBluetoothKeepAlivePolicy.decide(
                    consent.enabled, consent.onOnly, vehicleOff, vehicleOn, owned,
                    forceDisable || safeZoneBluetoothOff, radioState(state));
            lastError = "";
            if (action == ParkedBluetoothKeepAlivePolicy.Action.RELEASE_OWNERSHIP) {
                if (!writeOwned(lease, false)) lastError = "lease_write_failed";
            } else if (action == ParkedBluetoothKeepAlivePolicy.Action.ENABLE) {
                // Persist first so process death cannot leave a feature-created radio unowned.
                if (!writeOwned(lease, true)) {
                    lastError = "lease_write_failed";
                } else {
                    // Cross-process OFF and on-only changes must win over a queued tick.
                    Consent latest = freshConsent();
                    if (!latest.enabled || latest.onOnly) {
                        if (adapter == null || adapter.getState() == BluetoothAdapter.STATE_OFF) {
                            writeOwned(lease, false);
                        }
                    } else if (adapter == null || !adapter.enable()) {
                        if (adapter != null && adapter.getState() == BluetoothAdapter.STATE_OFF) {
                            writeOwned(lease, false);
                        }
                        lastError = "enable_rejected";
                    }
                }
            } else if (action == ParkedBluetoothKeepAlivePolicy.Action.DISABLE) {
                // Explicit OFF also owns an uncompleted cleanup of an initially-on adapter.
                if (!writeOwned(lease, true)) {
                    lastError = "lease_write_failed";
                } else if (adapter == null || !adapter.disable()) {
                    if (adapter != null && adapter.getState() == BluetoothAdapter.STATE_OFF) {
                        writeOwned(lease, false);
                    } else {
                        lastError = "disable_rejected";
                    }
                }
            }
            return runtime(context);
        } catch (Throwable failure) {
            lastError = failure.getClass().getSimpleName();
            return runtime(context);
        }
    }

    /** An explicit app-process control first saves the same preference used by every page. */
    public static synchronized boolean manualSet(Context context, boolean enabled) {
        try {
            if (!UnifiedConfigManager.setBluetoothKeepAliveEnabled(enabled)) {
                lastError = "preference_write_failed";
                return false;
            }
            return applySavedIntent(context, enabled);
        } catch (Throwable failure) {
            lastError = failure.getClass().getSimpleName();
            return false;
        }
    }

    /**
     * Apply a daemon-persisted explicit intent without rewriting it. A delayed foreground
     * service request cannot restore an older ON after a newer page/automation saved OFF.
     */
    public static synchronized boolean applySavedIntent(Context context, boolean enabled) {
        try {
            SharedPreferences lease = lease(context);
            Consent consent = freshConsent();
            if (consent.enabled != enabled) {
                lastError = "superseded_intent";
                return false;
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                lastError = "adapter_unavailable";
                return false;
            }
            int power = VehiclePowerReader.readNow(context);
            int state = adapter.getState();
            boolean targetAlreadyReached = enabled
                    ? state == BluetoothAdapter.STATE_ON : state == BluetoothAdapter.STATE_OFF;
            if (enabled && power == 0 && !targetAlreadyReached) {
                if (!writeOwned(lease, true)) {
                    lastError = "lease_write_failed";
                    return false;
                }
            } else if (enabled && power > 0) {
                writeOwned(lease, false);
            } else if (!enabled && !targetAlreadyReached) {
                if (!writeOwned(lease, true)) {
                    lastError = "lease_write_failed";
                    return false;
                }
            }
            // Re-read immediately before the framework enable/disable operation.
            if (freshConsent().enabled != enabled) {
                lastError = "superseded_intent";
                if (adapter.getState() == BluetoothAdapter.STATE_OFF) writeOwned(lease, false);
                return false;
            }
            boolean succeeded = VehicleActuatorService.setBluetoothEnabledDirect(enabled);
            if (!enabled && adapter.getState() == BluetoothAdapter.STATE_OFF) {
                writeOwned(lease, false);
            } else if (enabled && !succeeded
                    && adapter.getState() == BluetoothAdapter.STATE_OFF) {
                writeOwned(lease, false);
            }
            lastError = succeeded ? "" : enabled ? "enable_unconfirmed" : "disable_unconfirmed";
            return succeeded;
        } catch (Throwable failure) {
            lastError = failure.getClass().getSimpleName();
            return false;
        }
    }

    /** Read-only snapshot; it starts no monitoring and owns no additional resources. */
    public static synchronized JSONObject runtime(Context context) {
        JSONObject result = new JSONObject();
        try {
            SharedPreferences lease = lease(context);
            Consent consent = freshConsent();
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            int state = adapter == null ? -1 : adapter.getState();
            boolean owned = lease.getBoolean(OWNED, false);
            boolean pending = owned && (!consent.enabled || consent.onOnly)
                    && state != BluetoothAdapter.STATE_OFF;
            result.put("enabled", consent.enabled)
                    .put("requestedEnabled", consent.enabled && !consent.onOnly)
                    .put("radioOwned", owned)
                    .put("cleanupPending", pending)
                    .put("state", state)
                    .put("bluetoothEnabled", state == BluetoothAdapter.STATE_ON)
                    .put("error", lastError);
        } catch (Throwable failure) {
            try {
                result.put("enabled", false).put("requestedEnabled", false)
                        .put("radioOwned", false).put("cleanupPending", true)
                        .put("state", -1).put("bluetoothEnabled", false)
                        .put("error", failure.getClass().getSimpleName());
            } catch (Throwable ignored) {}
        }
        return result;
    }


    /**
     * The existing "unlock only outside safe zones" choice also suppresses parked
     * Bluetooth in a confirmed selected zone. This deliberately reuses the same
     * geometry, GPS freshness and boundary policy as proximity unlock decisions.
     * Outside the zone, the normal parked keep-alive preference resumes unchanged.
     */
    private static boolean isInsideUnlockRestrictedSafeZone() {
        try {
            JSONObject settings = RemoteAccessSettings.read();
            if (!settings.optBoolean("enabled")
                    || !settings.optBoolean("restrictUnlockToOutsideSafeZones")) return false;

            java.util.List<String> ids = new java.util.ArrayList<>();
            org.json.JSONArray selected = settings.optJSONArray("proximitySafeZoneIds");
            if (selected != null) {
                for (int i = 0; i < selected.length(); i++) ids.add(selected.optString(i, ""));
            }
            java.util.List<ProximitySafeZonePolicy.Zone> zones = new java.util.ArrayList<>();
            if (!ids.isEmpty()) {
                for (SafeLocationManager.ZoneSnapshot zone :
                        SafeLocationManager.getInstance().getGeometrySnapshot()) {
                    zones.add(new ProximitySafeZonePolicy.Zone(zone.id, zone.name,
                            zone.latitude, zone.longitude, zone.radiusMeters, zone.geometryValid));
                }
            }
            ProximitySafeZonePolicy.Fix position = null;
            ProximitySafeZonePolicy.Fix parked = null;
            GpsMonitor.GpsFixSnapshot fix = GpsMonitor.getInstance().getFixSnapshot();
            if (fix != null && fix.hasLocation()) {
                position = new ProximitySafeZonePolicy.Fix(fix.latitude, fix.longitude,
                        fix.accuracy, GpsMonitor.fixAgeMs(fix));
            }
            ParkedPosition.Fix parkedFix = ParkedPosition.get().current();
            if (parkedFix != null) {
                parked = new ProximitySafeZonePolicy.Fix(parkedFix.latitude, parkedFix.longitude,
                        parkedFix.accuracyM,
                        SystemClock.elapsedRealtime() - parkedFix.fixElapsedMs);
            }
            ProximitySafeZonePolicy.Result result = ProximitySafeZonePolicy.evaluate(
                    true, false, true, ids, zones, position, parked);
            return "INSIDE".equals(result.state);
        } catch (Throwable unavailable) {
            // Never infer zone membership from stale, missing or invalid location data.
            return false;
        }
    }

    private static SharedPreferences lease(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences lease = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!lease.getBoolean(LEGACY_ADOPTED, false)) {
            SharedPreferences legacy = app.getSharedPreferences(
                    "remote_access_runtime", Context.MODE_PRIVATE);
            boolean inherited = legacy.getBoolean(OWNED, false);
            if (lease.edit().putBoolean(OWNED, lease.getBoolean(OWNED, false) || inherited)
                    .putBoolean(LEGACY_ADOPTED, true).commit() && inherited) {
                // Adopt before clearing so an interrupted migration never loses ownership.
                legacy.edit().putBoolean(OWNED, false).commit();
            }
        }
        return lease;
    }

    private static boolean writeOwned(SharedPreferences lease, boolean owned) {
        return lease.getBoolean(OWNED, false) == owned
                || lease.edit().putBoolean(OWNED, owned).commit();
    }

    private static Consent freshConsent() {
        UnifiedConfigManager.forceReload();
        return new Consent(UnifiedConfigManager.isBluetoothKeepAliveEnabled(),
                UnifiedConfigManager.isVehicleOnOnlyMode());
    }

    private static ParkedBluetoothKeepAlivePolicy.RadioState radioState(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
                return ParkedBluetoothKeepAlivePolicy.RadioState.OFF;
            case BluetoothAdapter.STATE_TURNING_ON:
                return ParkedBluetoothKeepAlivePolicy.RadioState.TURNING_ON;
            case BluetoothAdapter.STATE_ON:
                return ParkedBluetoothKeepAlivePolicy.RadioState.ON;
            case BluetoothAdapter.STATE_TURNING_OFF:
                return ParkedBluetoothKeepAlivePolicy.RadioState.TURNING_OFF;
            default:
                return ParkedBluetoothKeepAlivePolicy.RadioState.UNKNOWN;
        }
    }

    private static final class Consent {
        final boolean enabled;
        final boolean onOnly;

        Consent(boolean enabled, boolean onOnly) {
            this.enabled = enabled;
            this.onOnly = onOnly;
        }
    }
}
