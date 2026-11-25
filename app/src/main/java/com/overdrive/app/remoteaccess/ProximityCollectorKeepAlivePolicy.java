package com.overdrive.app.remoteaccess;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Decides when the shell daemon must (re)start the app-process proximity collectors.
 *
 * <p>The head unit can kill the whole app process at switch-off. The collectors, their Bluetooth
 * keep-alive and the app's own revival alarms all die with it, so only the daemon can bring them
 * back. While parked with proximity enabled, the daemon starts the collector service once, then:
 * <ul>
 *   <li>with a trusted phone, restarts it only when relayed phone observations stop. The phone
 *       collector relays every 1–3 s, so a silence longer than {@link #STALE_MS} means it is not
 *       running;</li>
 *   <li>with only a key fob, which has no comparable heartbeat, re-issues the idempotent start
 *       every {@link #MAX_RETRY_MS}.</li>
 * </ul>
 * Restarts back off up to {@link #MAX_RETRY_MS}. The backoff resets only after a revival has
 * stayed alive for {@link #STABLE_MS}, so a head unit that keeps killing the process cannot drive
 * a fast restart loop for the whole park.
 *
 * <p>The first decision after construction (a daemon start or restart) does not know whether a
 * previous daemon left the service running, so a not-wanted first tick returns one STOP.
 *
 * <p>Pure state on a caller-supplied elapsed clock; confined to the caller's thread.
 */
public final class ProximityCollectorKeepAlivePolicy {
    /** Longer than the slowest collector cycle (two 3 s native waits plus the relay). */
    public static final long STALE_MS = 15_000L;
    /** Time a fresh process needs to start and relay its first reading. */
    public static final long FIRST_RETRY_MS = 20_000L;
    public static final long MAX_RETRY_MS = 300_000L;
    /** A revival must stay fresh this long before the backoff resets. */
    public static final long STABLE_MS = 120_000L;

    public enum Action { NONE, START, STOP }

    private boolean known;
    private boolean running;
    private long lastStartMs = -1L;
    private long retryMs = FIRST_RETRY_MS;
    private String reason = "";

    /**
     * @param wanted parked with proximity enabled and runnable
     * @param heartbeat a trusted phone is configured, so {@code lastObservationMs} proves liveness
     * @param lastObservationMs elapsed time of the latest relayed phone observation, or -1 when none
     */
    public Action decide(boolean wanted, boolean heartbeat, long nowMs, long lastObservationMs) {
        if (!wanted) {
            if (known && !running) return Action.NONE;
            known = true;
            running = false;
            lastStartMs = -1L;
            retryMs = FIRST_RETRY_MS;
            reason = "not_parked_or_disabled";
            return Action.STOP;
        }
        if (!running) {
            known = true;
            running = true;
            lastStartMs = nowMs;
            retryMs = FIRST_RETRY_MS;
            reason = "parked";
            return Action.START;
        }
        boolean clockReset = nowMs < lastStartMs;
        if (!heartbeat) {
            if (clockReset || nowMs - lastStartMs >= MAX_RETRY_MS) {
                lastStartMs = nowMs;
                reason = "reassert";
                return Action.START;
            }
            return Action.NONE;
        }
        boolean fresh = lastObservationMs >= 0 && nowMs >= lastObservationMs
                && nowMs - lastObservationMs <= STALE_MS;
        if (fresh) {
            if (!clockReset && nowMs - lastStartMs >= STABLE_MS) retryMs = FIRST_RETRY_MS;
            return Action.NONE;
        }
        if (clockReset || nowMs - lastStartMs >= retryMs) {
            lastStartMs = nowMs;
            retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
            reason = "collector_silent";
            return Action.START;
        }
        return Action.NONE;
    }

    /** Mirrors the phone start conditions in {@code BluetoothAccessMonitor.command("apply")}. */
    public static boolean phoneCollectorConfigured(JSONObject settings) {
        if (settings == null || !settings.optBoolean("enabled")
                || !settings.optBoolean("keepBluetoothOn")) return false;
        JSONArray phones = settings.optJSONArray("phones");
        if (phones == null) return false;
        for (int i = 0; i < phones.length(); i++) {
            JSONObject phone = phones.optJSONObject(i);
            if (phone != null && phone.optBoolean("enabled")) return true;
        }
        return false;
    }

    /** Mirrors {@code KeyFobAccessMonitor.consent}. */
    public static boolean keyFobConfigured(JSONObject settings) {
        return settings != null && settings.optBoolean("enabled") && settings.optBoolean("keyFobEnabled");
    }

    /** Why the latest START or STOP was returned. */
    public String reason() { return reason; }

    /** The wait before the next silent-collector restart. */
    public long retryMs() { return retryMs; }
}
