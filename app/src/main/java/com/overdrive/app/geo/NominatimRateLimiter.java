package com.overdrive.app.geo;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

/**
 * Token-bucket rate limiter + persisted cooldown for Nominatim.
 *
 * <p>Two layers of throttling:
 * <ol>
 *   <li><b>Token bucket</b> (process-local, 1 token/s, capacity 1) — enforces
 *       OSM's "≤ 1 request per second per app" policy across every caller in
 *       the daemon (start/peak/end snapshots, retroactive backfill, SafeZone
 *       resolver) without coordination at the call sites.</li>
 *   <li><b>Persisted cooldown</b> (kept in {@code geocoding.nominatimCooldownUntilMs})
 *       — survives daemon restarts. When Nominatim returns 429, 5xx, or any
 *       network error, callers push the cooldown out exponentially. While the
 *       cooldown is in effect, {@link #tryAcquire()} returns {@code false}
 *       even if a token is available, so a flapping daemon can't IP-ban
 *       itself by retrying every cold start.</li>
 * </ol>
 *
 * <p>Acquisition and non-consuming probes synchronize on this class to share
 * the single-token gate across resolver callers. The persisted cooldown also
 * touches UnifiedConfigManager, which serializes configuration I/O internally.
 */
public final class NominatimRateLimiter {

    /** 1 token / second. */
    private static final long TOKEN_INTERVAL_NS = 1_000_000_000L;

    /** Backoff schedule for transient failures. Cap at 6 hours. */
    private static final long[] BACKOFF_MS = {
            5L * 60L * 1000L,        // 5 min
            15L * 60L * 1000L,       // 15 min
            60L * 60L * 1000L,       // 1 hour
            6L * 60L * 60L * 1000L   // 6 hours (cap)
    };

    /** Process-local token gate; all access is under synchronized class methods. */
    private static final NominatimTokenGate tokenGate = new NominatimTokenGate(TOKEN_INTERVAL_NS);

    /** Index into BACKOFF_MS used by {@link #recordFailure()}. Clamped to last entry. */
    private static int failureStreak = 0;

    private NominatimRateLimiter() {}

    /**
     * Try to consume the next token.
     *
     * <p>Returns {@code true} when:
     * <ul>
     *   <li>The persistent cooldown has elapsed, AND</li>
     *   <li>One token-bucket interval (1 s) has elapsed since the last grant.</li>
     * </ul>
     *
     * <p>Otherwise returns {@code false} immediately — callers fall through to
     * a stale-cache result or skip the lookup entirely. We never block the
     * caller; this is a non-blocking gate.
     */
    public static synchronized boolean tryAcquire() {
        long nowMs = System.currentTimeMillis();
        long cooldownUntil = readCooldownUntilMs();
        if (cooldownUntil > nowMs) {
            return false;
        }
        return tokenGate.tryAcquire(System.nanoTime());
    }

    /**
     * Non-consuming probe: true iff a {@link #tryAcquire()} right now WOULD be
     * refused (persisted cooldown active, or the 1 s token not yet available).
     * Does NOT advance the token clock. Used by the backfill sweep to tell a
     * THROTTLE miss (transient — retry next tick) from a genuine empty-address
     * miss (mark for cooldown), so a merely rate-limited resolvable clip is not
     * wrongly pinned. Best-effort racy vs a concurrent tryAcquire; the failure
     * mode is symmetric and harmless (at worst one extra cheap attempt later).
     */
    public static synchronized boolean isThrottled() {
        if (readCooldownUntilMs() > System.currentTimeMillis()) return true;
        return tokenGate.isThrottled(System.nanoTime());
    }

    /**
     * Record a Nominatim success. Resets the failure streak so the next
     * transient failure starts at the shortest backoff again.
     */
    public static synchronized void recordSuccess() {
        failureStreak = 0;
        // Clear any persisted cooldown so a stale value from a previous boot
        // doesn't keep us locked out longer than necessary.
        writeCooldownUntilMs(0L);
    }

    /**
     * Record a Nominatim failure (timeout, 4xx, 5xx, network drop). Pushes
     * the persisted cooldown out by the next exponential step.
     */
    public static synchronized void recordFailure() {
        int idx = Math.min(failureStreak, BACKOFF_MS.length - 1);
        long penalty = BACKOFF_MS[idx];
        long until = System.currentTimeMillis() + penalty;
        writeCooldownUntilMs(until);
        if (failureStreak < BACKOFF_MS.length) failureStreak++;
    }

    /** Test seam — read current cooldown wall-clock ms (0 if none active). */
    public static long getCooldownUntilMs() {
        return readCooldownUntilMs();
    }

    // ---- Persistence helpers ----------------------------------------------

    private static long readCooldownUntilMs() {
        try {
            JSONObject geo = UnifiedConfigManager.loadConfig().optJSONObject("geocoding");
            if (geo == null) return 0L;
            JSONObject advanced = geo.optJSONObject("advanced");
            if (advanced == null) return 0L;
            return advanced.optLong("nominatimCooldownUntilMs", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static void writeCooldownUntilMs(long ms) {
        // Read-modify-write under the UnifiedConfigManager INSTANCE monitor
        // to serialize against the /api/settings/geocoding POST handler, the
        // only other geocoding.advanced writer; it locks the same INSTANCE.
        //
        // We also build the `advanced` delta from a *fresh* JSONObject so a
        // concurrent reader of the live cached `geocoding.advanced` object
        // never observes a half-mutated state. (The previous in-place
        // `advanced.put(...)` mutation touched the cached reference.)
        synchronized (UnifiedConfigManager.INSTANCE) {
            try {
                JSONObject geo = UnifiedConfigManager.loadConfig().optJSONObject("geocoding");
                JSONObject curAdv = (geo != null) ? geo.optJSONObject("advanced") : null;
                JSONObject advanced = new JSONObject();
                if (curAdv != null) {
                    advanced.put("customNominatimBase",
                            curAdv.optString("customNominatimBase", ""));
                }
                advanced.put("nominatimCooldownUntilMs", ms);
                JSONObject delta = new JSONObject();
                delta.put("advanced", advanced);
                UnifiedConfigManager.updateSection("geocoding", delta);
            } catch (Throwable ignored) {
                // Cooldown is best-effort; failing to persist just means the
                // next boot may retry sooner than ideal. Not worth crashing
                // the resolver over.
            }
        }
    }
}
