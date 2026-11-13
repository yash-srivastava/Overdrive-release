package com.overdrive.app.remoteaccess;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.services.VehicleActuatorService;

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
    private static final String SAFE_ZONE_DISABLED = "safeZoneDisabled";
    private static final String SAFE_ZONE_OFF_CONFIRMED = "safeZoneOffConfirmed";
    private static final String SAFE_ZONE_DIRECTIVE = "safeZoneDirective";
    private static final String SAFE_ZONE_DIRECTIVE_AT = "safeZoneDirectiveAtElapsedMs";
    private static final long SAFE_ZONE_DIRECTIVE_MAX_AGE_MS = 15_000L;
    private static final String LEGACY_ADOPTED = "remoteAccessLeaseAdopted";
    private static volatile long lastSafeZoneDirectiveAtElapsedMs = -1L;
    private static volatile boolean lastSafeZoneDirectiveValue;
    private static volatile String lastError = "";

    private SharedBluetoothKeepAliveController() {}

    /** One startup check; no power read is needed when neither consent nor a lease exists. */
    public static synchronized void resume(Context context) {
        try {
            SharedPreferences lease = lease(context);
            Consent consent = freshConsent();
            if (!consent.enabled && !lease.getBoolean(OWNED, false)
                    && !lease.getBoolean(SAFE_ZONE_DISABLED, false)) return;
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
        // The 10-second watchdog and settings callers do not own the camera daemon's
        // location decision. They preserve its last authenticated directive until it
        // is refreshed or its short lease expires.
        return reconcile(context, vehicleOff, vehicleOn, forceDisable, false, false);
    }

    /**
     * Reconcile the shared radio lease. Only a successful response from the camera
     * daemon's authenticated phone-observation relay may refresh the safe-zone directive.
     */
    public static synchronized JSONObject reconcile(
            Context context, boolean vehicleOff, boolean vehicleOn, boolean forceDisable,
            boolean safeZoneDirectiveKnown, boolean safeZoneDisable) {
        return reconcile(context, vehicleOff, vehicleOn, forceDisable,
                safeZoneDirectiveKnown, safeZoneDisable, SystemClock.elapsedRealtime());
    }

    /** Timestamped form rejects delayed relay responses that predate a newer camera-daemon decision. */
    public static synchronized JSONObject reconcile(
            Context context, boolean vehicleOff, boolean vehicleOn, boolean forceDisable,
            boolean safeZoneDirectiveKnown, boolean safeZoneDisable,
            long safeZoneDirectiveAtElapsedMs) {
        try {
            SharedPreferences lease = lease(context);
            long now = SystemClock.elapsedRealtime();
            loadSafeZoneDirective(lease, now);
            if (safeZoneDirectiveKnown && safeZoneDirectiveAtElapsedMs >= 0
                    && safeZoneDirectiveAtElapsedMs <= now
                    && safeZoneDirectiveAtElapsedMs >= lastSafeZoneDirectiveAtElapsedMs
                    && !writeSafeZoneDirective(lease, safeZoneDisable, safeZoneDirectiveAtElapsedMs)) {
                lastError = "safe_zone_directive_write_failed";
                return runtime(context);
            }
            boolean safeZoneDirective = lastSafeZoneDirectiveValue;
            long directiveAt = lastSafeZoneDirectiveAtElapsedMs;
            long directiveAge = now - directiveAt;
            if (safeZoneDirective && (directiveAt < 0 || directiveAge < 0
                    || directiveAge > SAFE_ZONE_DIRECTIVE_MAX_AGE_MS)) {
                safeZoneDirective = false;
                if (!writeSafeZoneDirective(lease, false, now)) {
                    lastError = "safe_zone_directive_expiry_write_failed";
                    return runtime(context);
                }
            }

            Consent consent = freshConsent();
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            int state = adapter == null ? -1 : adapter.getState();
            boolean owned = lease.getBoolean(OWNED, false);
            boolean safeZoneOwned = lease.getBoolean(SAFE_ZONE_DISABLED, false);
            boolean safeZoneOffConfirmed = lease.getBoolean(SAFE_ZONE_OFF_CONFIRMED, false);

            // Once a safe-zone shutdown has reached OFF, a later ON transition is an
            // explicit external/vehicle re-enable. Drop our lease instead of fighting it.
            if (safeZoneOwned && safeZoneOffConfirmed
                    && (state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_TURNING_ON)) {
                writeOwned(lease, false);
                writeSafeZoneDisabled(lease, false);
                writeSafeZoneOffConfirmed(lease, false);
                owned = false;
                safeZoneOwned = false;
            } else if (safeZoneOwned && state == BluetoothAdapter.STATE_OFF) {
                writeSafeZoneOffConfirmed(lease, true);
                safeZoneOffConfirmed = true;
            }

            ParkedBluetoothKeepAlivePolicy.Action action = ParkedBluetoothKeepAlivePolicy.decide(
                    consent.enabled, consent.onOnly, vehicleOff, vehicleOn, owned,
                    forceDisable, safeZoneDirective, safeZoneOwned, radioState(state));
            lastError = "";
            if (action == ParkedBluetoothKeepAlivePolicy.Action.RELEASE_OWNERSHIP) {
                if (!writeOwned(lease, false) || !writeSafeZoneDisabled(lease, false)
                        || !writeSafeZoneOffConfirmed(lease, false))
                    lastError = "lease_write_failed";
            } else if (action == ParkedBluetoothKeepAlivePolicy.Action.ENABLE) {
                // Persist first so process death cannot leave a feature-created radio unowned.
                if (!writeOwned(lease, true)) {
                    lastError = "lease_write_failed";
                } else {
                    // Cross-process OFF and on-only changes must win over a queued tick.
                    Consent latest = freshConsent();
                    boolean restoreForDriving = safeZoneOwned && vehicleOn && !vehicleOff;
                    if ((!latest.enabled || latest.onOnly) && !restoreForDriving) {
                        if (adapter == null || adapter.getState() == BluetoothAdapter.STATE_OFF) {
                            writeOwned(lease, false);
                            writeSafeZoneDisabled(lease, false);
                            writeSafeZoneOffConfirmed(lease, false);
                        }
                    } else if (adapter == null || !adapter.enable()) {
                        if (adapter != null && adapter.getState() == BluetoothAdapter.STATE_OFF
                                && !safeZoneOwned) {
                            writeOwned(lease, false);
                        }
                        lastError = "enable_rejected";
                    } else if (safeZoneOwned) {
                        // The framework accepted restoration. The normal keep-alive lease
                        // remains owned, but the safe-zone-specific suppression has ended.
                        writeSafeZoneDisabled(lease, false);
                        writeSafeZoneOffConfirmed(lease, false);
                    }
                }
            } else if (action == ParkedBluetoothKeepAlivePolicy.Action.DISABLE) {
                if (!writeOwned(lease, true)) {
                    lastError = "lease_write_failed";
                } else if (safeZoneDirective && consent.enabled && !consent.onOnly
                        && !writeSafeZoneDisabled(lease, true)) {
                    lastError = "safe_zone_lease_write_failed";
                } else {
                    if (!safeZoneDirective) {
                        writeSafeZoneDisabled(lease, false);
                        writeSafeZoneOffConfirmed(lease, false);
                    }
                    if (adapter == null || !adapter.disable()) {
                        if (adapter != null && adapter.getState() == BluetoothAdapter.STATE_OFF) {
                            if (safeZoneDirective) writeSafeZoneOffConfirmed(lease, true);
                            else writeOwned(lease, false);
                        } else if (adapter == null || adapter.getState() != BluetoothAdapter.STATE_OFF) {
                            lastError = "disable_rejected";
                        }
                    } else if (safeZoneDirective && adapter.getState() == BluetoothAdapter.STATE_OFF) {
                        writeSafeZoneOffConfirmed(lease, true);
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
            if (!writeSafeZoneDirective(lease, false, SystemClock.elapsedRealtime())
                    || !writeSafeZoneDisabled(lease, false)
                    || !writeSafeZoneOffConfirmed(lease, false)) {
                lastError = "safe_zone_lease_write_failed";
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
            boolean safeZoneDisabled = lease.getBoolean(SAFE_ZONE_DISABLED, false);
            long directiveAt = lease.getLong(SAFE_ZONE_DIRECTIVE_AT, -1L);
            long directiveAge = SystemClock.elapsedRealtime() - directiveAt;
            boolean safeZoneDirective = lease.getBoolean(SAFE_ZONE_DIRECTIVE, false)
                    && directiveAt >= 0 && directiveAge >= 0
                    && directiveAge <= SAFE_ZONE_DIRECTIVE_MAX_AGE_MS;
            boolean pending = owned && (safeZoneDisabled || !consent.enabled || consent.onOnly)
                    && state != BluetoothAdapter.STATE_OFF;
            result.put("enabled", consent.enabled)
                    .put("requestedEnabled", consent.enabled && !consent.onOnly)
                    .put("radioOwned", owned)
                    .put("safeZoneDisabled", safeZoneDisabled)
                    .put("safeZoneDirectiveActive", safeZoneDirective)
                    .put("safeZoneDirectiveAgeMs", directiveAt < 0 ? JSONObject.NULL : Math.max(0, directiveAge))
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

    private static boolean writeSafeZoneDisabled(SharedPreferences lease, boolean disabled) {
        return lease.getBoolean(SAFE_ZONE_DISABLED, false) == disabled
                || lease.edit().putBoolean(SAFE_ZONE_DISABLED, disabled).commit();
    }

    private static boolean writeSafeZoneOffConfirmed(SharedPreferences lease, boolean confirmed) {
        return lease.getBoolean(SAFE_ZONE_OFF_CONFIRMED, false) == confirmed
                || lease.edit().putBoolean(SAFE_ZONE_OFF_CONFIRMED, confirmed).commit();
    }

    private static void loadSafeZoneDirective(SharedPreferences lease, long now) {
        if (lastSafeZoneDirectiveAtElapsedMs >= 0) {
            if (lastSafeZoneDirectiveAtElapsedMs > now) {
                lastSafeZoneDirectiveAtElapsedMs = -1L;
                lastSafeZoneDirectiveValue = false;
                lease.edit().putBoolean(SAFE_ZONE_DIRECTIVE, false)
                        .putLong(SAFE_ZONE_DIRECTIVE_AT, -1L).apply();
            }
            return;
        }
        long storedAt = lease.getLong(SAFE_ZONE_DIRECTIVE_AT, -1L);
        boolean storedValue = lease.getBoolean(SAFE_ZONE_DIRECTIVE, false);
        if (storedAt < 0 || storedAt > now) {
            lastSafeZoneDirectiveAtElapsedMs = -1L;
            lastSafeZoneDirectiveValue = false;
            if (storedAt > now) lease.edit().putBoolean(SAFE_ZONE_DIRECTIVE, false)
                    .putLong(SAFE_ZONE_DIRECTIVE_AT, -1L).apply();
        } else {
            lastSafeZoneDirectiveAtElapsedMs = storedAt;
            lastSafeZoneDirectiveValue = storedValue;
        }
    }

    private static boolean writeSafeZoneDirective(SharedPreferences lease, boolean disabled, long at) {
        if (at < 0) return false;
        lastSafeZoneDirectiveAtElapsedMs = at;
        lastSafeZoneDirectiveValue = disabled;
        // apply() updates the process-visible preference immediately without making
        // every 1 Hz phone observation wait for a synchronous disk commit.
        lease.edit().putBoolean(SAFE_ZONE_DIRECTIVE, disabled)
                .putLong(SAFE_ZONE_DIRECTIVE_AT, at).apply();
        return true;
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
