package com.overdrive.app.geo;

import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.server.LocaleManager;
import com.overdrive.app.surveillance.SafeLocation;
import com.overdrive.app.surveillance.SafeLocationManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiered reverse-geocoding resolver.
 *
 * <p>Resolution order, fail-closed:
 * <ol>
 *   <li><b>SafeLocation</b> — if (lat, lng) is inside any zone the user named
 *       "Home" / "Office" / etc, return that name. Privacy-critical: never
 *       leak the public address of a private place into a sidecar / push.</li>
 *   <li><b>Cache</b> ({@link GeoCache}, geohash7 + locale) — instant, offline.</li>
 *   <li><b>Android Geocoder</b> — built-in, offline on most devices, free, but
 *       returns {@code IOException("Service not Available")} on some BYD AOSP
 *       ROMs. Probed once per process and disabled on failure.</li>
 *   <li><b>Nominatim</b> (OpenStreetMap) — online; gated by
 *       {@code geocoding.allowOnline} + {@link NominatimRateLimiter} (1 req/s
 *       global + persisted exponential backoff on errors).</li>
 * </ol>
 *
 * <p>Public API is non-blocking from the recorder's perspective:
 * <ul>
 *   <li>{@link #resolveCachedOnly(double, double)} — synchronous, returns null
 *       if the cache (and SafeLocation overlay) misses. Safe to call on the
 *       recording-trigger path.</li>
 *   <li>{@link #resolveAsync(double, double)} — schedules a real lookup on the
 *       resolver's own thread; the caller submits a callback to be invoked
 *       when the result lands.</li>
 * </ul>
 */
public final class GeocodingResolver {

    private static final DaemonLogger logger = DaemonLogger.getInstance("Geocoder");

    /** OpenStreetMap-friendly UA string. Uses build-time version. */
    private static final String USER_AGENT_TEMPLATE =
            "OverDrive/%s (https://github.com/yash-srivastava/Overdrive-release)";

    private static final String NOMINATIM_DEFAULT =
            "https://nominatim.openstreetmap.org";

    /** zoom=14 ≈ suburb/district. Coarser is more cacheable + less identifying. */
    private static final int NOMINATIM_ZOOM = 14;

    /** HTTP timeouts — short enough to never block the recording trigger. */
    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS    = 6_000;

    /** Single resolver instance — Nominatim is rate-limited globally. */
    private static volatile GeocodingResolver instance;

    /** Bounded executor: 2 worker threads, 1024-task queue, oldest-discard. */
    private final ExecutorService executor;

    /**
     * Probe state for Android Geocoder availability.
     * <ul>
     *   <li>{@code null} — unknown (probe will run on next call)</li>
     *   <li>{@code TRUE} — available (cached)</li>
     *   <li>{@code FALSE} — unavailable on this ROM (latched off so we
     *       don't keep paying the SecurityException price on every call)</li>
     * </ul>
     */
    private volatile Boolean androidGeocoderProbeResult = null;

    /**
     * Inflight callback registry — keyed on {@code geohash7|locale}. Two
     * near-simultaneous resolves for the same key collapse onto a single
     * Nominatim request and fire all registered callbacks at the tail of
     * the resolve task.
     *
     * <p>Replaces the prior Future-based de-dup. The Future approach
     * required a SECOND `executor.execute(() -> fRef.get())` task per
     * caller for callback delivery. The callback is now delivered by the
     * lookup task itself. When a queued lookup is evicted, the discard-aware
     * rejection policy clears its inflight registry entry so later calls can
     * register a new leader instead of waiting on a task that never runs.
     *
     * <p>Value type: {@link java.util.concurrent.CopyOnWriteArrayList}
     * — concurrent appenders, lock-free reader at task tail.
     */
    private final ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<ResolveCallback>> inflight = new ConcurrentHashMap<>();

    private GeocodingResolver() {
        AtomicInteger seq = new AtomicInteger(0);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "Geocoder-" + seq.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };
        // Bounded queue + discard-aware oldest eviction: a 1000-recording burst all
        // missing cache (locale switch, fresh install, road trip) cannot
        // grow the queue forever. The 1 req/s rate limiter + 6 h cooldown
        // mean the queue could otherwise drain very slowly under network
        // failure. DiscardOldest keeps the most recent (most likely to
        // matter to the user) and drops stale geocode requests. The dropped
        // task must remove its inflight entry or later callers can get stuck.
        this.executor = new ThreadPoolExecutor(
                /* corePoolSize  = */ 2,
                /* maxPoolSize   = */ 2,
                /* keepAliveTime = */ 0L, TimeUnit.MILLISECONDS,
                /* workQueue     = */ new LinkedBlockingQueue<>(1024),
                tf,
                new GeocodeDiscardPolicy());
    }

    public static GeocodingResolver getInstance() {
        if (instance == null) {
            synchronized (GeocodingResolver.class) {
                if (instance == null) instance = new GeocodingResolver();
            }
        }
        return instance;
    }

    /**
     * Best-effort cache-only lookup. Used at recording-start time so the
     * sidecar gets *something* immediately even when async resolution lags.
     *
     * <p>Order: SafeLocation overlay → cache → null.
     *
     * <p>This overload assumes the "recording" flow gate is what should
     * gate the cache read — most callers (publishMotionFinal place suffix,
     * SRT prefix) reach this synchronously and the cache hit either lands
     * before the gate matters or doesn't. If you need a flow-specific
     * gate, use {@link #resolveCachedOnly(double, double, String)}.
     */
    public PlaceResult resolveCachedOnly(double lat, double lng) {
        return resolveCachedOnly(lat, lng, "recording");
    }

    /**
     * Flow-aware variant. {@code flow} is "recording" or "surveillance" —
     * the geocoding config has independent enable toggles per flow so a
     * user can tag dashcam clips while leaving sentry clips untagged.
     */
    public PlaceResult resolveCachedOnly(double lat, double lng, String flow) {
        if (!isFlowEnabled(flow)) return null;
        PlaceResult sz = resolveSafeZone(lat, lng);
        if (sz != null) return sz;
        return GeoCache.getInstance().get(lat, lng, LocaleManager.get());
    }

    /**
     * Asynchronously resolve a place name. Invokes {@code callback} on the
     * resolver's worker thread when the lookup completes; callback is never
     * called with null — failed/disabled resolves silently no-op.
     *
     * <p>Idempotent across concurrent callers for the same geohash + locale:
     * the second caller piggybacks on the first's Future instead of issuing
     * a duplicate Nominatim request.
     */
    public void resolveAsync(double lat, double lng, ResolveCallback callback) {
        resolveAsync(lat, lng, "recording", callback);
    }

    /**
     * Flow-aware variant. {@code flow} is "recording" or "surveillance".
     * The leader's resolve task is gated by the matching flow's
     * {@code allowOnline} toggle for Tier C (Nominatim).
     */
    public void resolveAsync(double lat, double lng, String flow, ResolveCallback callback) {
        if (!isFlowEnabled(flow)) return;
        final String locale = LocaleManager.get();
        final String key = Geohash.encode(lat, lng) + "|" + locale;

        // Fast-path: SafeLocation overlay is synchronous and free.
        PlaceResult sz = resolveSafeZone(lat, lng);
        if (sz != null) {
            if (callback != null) safeInvokeCallback(callback, sz);
            return;
        }

        // Fast-path: cache hit avoids the executor bounce.
        PlaceResult cached = GeoCache.getInstance().get(lat, lng, locale);
        if (cached != null) {
            if (callback != null) safeInvokeCallback(callback, cached);
            return;
        }

        // De-dup via a per-key callback list keyed on (hash, locale).
        // BOTH register and drain go through ConcurrentHashMap.compute so
        // the bin monitor for `key` serializes them — a late registration
        // races against a leader-drain that's running on another thread,
        // and exactly one of the following is true:
        //   (1) registration wins the bin monitor first → callback lands
        //       in the list, leader's drain takes the bin monitor next
        //       and sees it.
        //   (2) drain wins first → drain removes the entry under the
        //       monitor, registration's compute runs against the empty
        //       state and *becomes* a new leader. The second leader sees
        //       a cache hit (the first leader already wrote the cache),
        //       so its task short-circuits in the cache fast-path of
        //       doResolve and fires the callback synchronously.
        //
        // CopyOnWriteArrayList's internal lock is irrelevant — the bin
        // monitor is what guarantees ordering between the add and the
        // drain. The previous CHM.computeIfAbsent + list.add (outside
        // the monitor) split the operation into two unrelated locks and
        // could lose a callback whose add raced ahead of drain.
        final boolean[] becameLeader = new boolean[1];
        inflight.compute(key, (k, existing) -> {
            if (existing == null) {
                becameLeader[0] = true;
                java.util.concurrent.CopyOnWriteArrayList<ResolveCallback> fresh =
                        new java.util.concurrent.CopyOnWriteArrayList<>();
                if (callback != null) fresh.add(callback);
                return fresh;
            }
            if (callback != null) existing.add(callback);
            return existing;
        });
        if (!becameLeader[0]) return;  // Piggybacking — leader will fire the callback.

        final String flowFinal = flow;
        // Use execute(), not submit(): submit() wraps the task in a FutureTask,
        // concealing its onDropped() hook from the queue's rejection handler.
        GeocodeDiscardPolicy.Droppable task = new GeocodeDiscardPolicy.Droppable() {
            @Override public void onDropped() {
                // This task was removed before it ran. A later request for the
                // same key must be allowed to become a new lookup leader.
                inflight.remove(key);
            }

            @Override public void run() {
                PlaceResult result = null;
                try {
                    result = doResolve(lat, lng, locale, flowFinal, null);
                } catch (Throwable t) {
                    logger.warn("doResolve threw: " + t.getMessage());
                } finally {
                    // Snapshot-and-remove under the bin monitor so any
                    // registration racing this drain is forced to either
                    // land in our snapshot (if it won the bin monitor first)
                    // or become a new leader (if we won first). The
                    // PlaceResult sneaks out via a lambda capture into a
                    // single-element holder; compute itself returns null so
                    // the entry is removed.
                    final PlaceResult finalResult = result;
                    final java.util.List<ResolveCallback>[] drained =
                            new java.util.List[]{ null };
                    inflight.compute(key, (k, existing) -> {
                        drained[0] = existing;
                        return null;  // remove the entry
                    });
                    if (drained[0] != null && finalResult != null) {
                        for (ResolveCallback cb : drained[0]) {
                            safeInvokeCallback(cb, finalResult);
                        }
                    }
                }
            }
        };
        try {
            executor.execute(task);
        } catch (Throwable t) {
            // A task may fail to enqueue for reasons other than saturation.
            // Clear the registry even when execute() throws unexpectedly.
            task.onDropped();
            logger.warn("enqueue failed: " + t.getMessage());
        }
    }

    /** Invoke a callback under a try/catch so a buggy receiver can't kill the worker. */
    private static void safeInvokeCallback(ResolveCallback cb, PlaceResult r) {
        if (cb == null || r == null) return;
        try { cb.onResolved(r); } catch (Throwable t) {
            logger.warn("callback threw: " + t.getMessage());
        }
    }

    /**
     * Synchronous resolve — only call from a background thread. Goes through
     * the full tier chain. Returns null when every tier misses.
     *
     * <p>Used by the retroactive backfill sweep for clips written before
     * geocoding was enabled. Defaults to "recording" flow.
     */
    public PlaceResult resolveBlocking(double lat, double lng) {
        return resolveBlocking(lat, lng, "recording");
    }

    public PlaceResult resolveBlocking(double lat, double lng, String flow) {
        if (!isFlowEnabled(flow)) return null;
        return doResolve(lat, lng, LocaleManager.get(), flow, null);
    }

    /**
     * Variant that reports whether the ONLINE (Nominatim) tier actually ran. The
     * backfill sweep uses this to distinguish a GENUINE empty-address miss (our
     * own token was consumed and Nominatim returned no address → mark unresolved)
     * from a null where the online tier never executed (token raced away by a
     * concurrent live resolve, active cooldown, offline mode → retry next tick, do
     * NOT pin). {@code reachedNominatim[0]} is set true iff our call acquired the
     * rate-limiter token and issued the HTTP request.
     */
    public PlaceResult resolveBlocking(double lat, double lng, String flow, boolean[] reachedNominatim) {
        if (!isFlowEnabled(flow)) return null;
        return doResolve(lat, lng, LocaleManager.get(), flow, reachedNominatim);
    }

    public interface ResolveCallback {
        void onResolved(PlaceResult result);
    }

    // ---- Tier orchestration ----------------------------------------------

    private PlaceResult doResolve(double lat, double lng, String locale, String flow, boolean[] reachedNominatim) {
        // 1. SafeLocation overlay (synchronous, in-memory).
        PlaceResult sz = resolveSafeZone(lat, lng);
        if (sz != null) {
            // Don't cache safezone results — they invalidate when the user
            // edits the zone. resolveSafeZone is fast enough to repeat.
            return sz;
        }

        // 2. Cache (cheap, may have been written by another worker since the
        // outer fast-path checked). Re-checking here keeps this method usable
        // standalone (e.g. the backfill sweep enters here directly).
        PlaceResult cached = GeoCache.getInstance().get(lat, lng, locale);
        if (cached != null) return cached;

        // 3. Android Geocoder (offline, free, but ROM-flaky).
        PlaceResult os = resolveAndroidGeocoder(lat, lng, locale);
        if (os != null) {
            GeoCache.getInstance().put(lat, lng, os);
            return os;
        }

        // 4. Nominatim (online, gated).
        if (isOnlineAllowed(flow)) {
            PlaceResult n = resolveNominatim(lat, lng, locale, reachedNominatim);
            if (n != null) {
                GeoCache.getInstance().put(lat, lng, n);
                return n;
            }
        }
        return null;
    }

    // ---- Tier A: SafeLocation overlay ------------------------------------

    private PlaceResult resolveSafeZone(double lat, double lng) {
        try {
            SafeLocationManager mgr = SafeLocationManager.getInstance();
            if (!mgr.isFeatureEnabled()) return null;
            for (SafeLocation z : mgr.getZones()) {
                if (!z.isEnabled()) continue;
                double d = SafeLocationManager.haversine(
                        lat, lng, z.getLatitude(), z.getLongitude());
                if (d <= z.getRadiusMeters()) {
                    return new PlaceResult(
                            z.getName(),  // displayName
                            z.getName(),  // district (so the chip reads "Home" not "")
                            "",           // city omitted — it'd defeat the privacy intent
                            "", "",
                            LocaleManager.get(),
                            PlaceResult.Source.SAFEZONE,
                            System.currentTimeMillis());
                }
            }
        } catch (Throwable t) {
            logger.warn("SafeLocation lookup failed: " + t.getMessage());
        }
        return null;
    }

    // ---- Tier B: Android Geocoder ----------------------------------------

    private PlaceResult resolveAndroidGeocoder(double lat, double lng, String locale) {
        // Probe once. A `false` cached result keeps us from hitting the
        // unavailable HAL on every call.
        if (Boolean.FALSE.equals(androidGeocoderProbeResult)) return null;

        try {
            android.content.Context ctx = CameraDaemon.getAppContext();
            if (ctx == null) {
                // Without a Context we can't construct Geocoder. This happens
                // on early daemon-only paths; treat as unavailable for now,
                // the cache + Nominatim path will still cover us.
                return null;
            }
            java.util.Locale jLocale = parseLocaleTag(locale);
            android.location.Geocoder gc = new android.location.Geocoder(ctx, jLocale);

            List<android.location.Address> addrs = gc.getFromLocation(lat, lng, 1);
            // Mark as available if we got here without an exception, even on
            // an empty result. An empty list is "no place name" — different
            // from "service unavailable".
            if (androidGeocoderProbeResult == null) {
                androidGeocoderProbeResult = Boolean.TRUE;
            }
            if (addrs == null || addrs.isEmpty()) return null;

            android.location.Address a = addrs.get(0);
            String district = firstNonEmpty(a.getSubLocality(), a.getSubAdminArea());
            String city = firstNonEmpty(a.getLocality(), a.getSubAdminArea(), a.getAdminArea());
            String country = a.getCountryName() != null ? a.getCountryName() : "";
            String cc = a.getCountryCode() != null ? a.getCountryCode() : "";
            String displayName = composeDisplayName(district, city, country);
            if (displayName.isEmpty()) return null;

            return new PlaceResult(displayName, district == null ? "" : district,
                    city == null ? "" : city, country, cc, locale,
                    PlaceResult.Source.ANDROID_GEOCODER,
                    System.currentTimeMillis());
        } catch (Throwable t) {
            // First-time failure: latch off so we don't keep paying the
            // exception price on every recording. Ship-or-not is a per-ROM
            // decision — Nominatim picks up the slack.
            androidGeocoderProbeResult = Boolean.FALSE;
            logger.info("Android Geocoder unavailable on this ROM: " + t.getMessage());
            return null;
        }
    }

    // ---- Tier C: Nominatim ------------------------------------------------

    private PlaceResult resolveNominatim(double lat, double lng, String locale, boolean[] reachedNominatim) {
        if (!NominatimRateLimiter.tryAcquire()) {
            // Either still in cooldown or another caller just used the token;
            // we'd rather skip than queue and risk piling up requests.
            return null;
        }
        // Our call won the token and is about to issue the HTTP request — so a
        // subsequent null IS a genuine reach-but-empty result, not a throttle.
        if (reachedNominatim != null) reachedNominatim[0] = true;

        // Honour the local proxy when running, with ONE direct retry on an
        // IO-level proxied failure. The probe behind getHttpProxy() is a blind
        // loopback connect: it prefers the Tailscale SOCKS listener, which on
        // most cars is tailnet-only — public OSM hosts are unreachable THROUGH
        // it while the listener itself probes healthy, so without the retry
        // every reverse-geocode wedged for as long as it was up. Server HTTP
        // codes (429/5xx) are NOT retried — they arrived over a working route.
        // Both attempts share the single rate-limiter token acquired above.
        java.net.Proxy proxy = com.overdrive.app.mqtt.ProxyHelper.getHttpProxy();
        boolean viaProxy = proxy != null
                && proxy.type() != java.net.Proxy.Type.DIRECT;
        try {
            return nominatimAttempt(lat, lng, locale, proxy);
        } catch (java.io.IOException ioe) {
            if (!viaProxy) {
                NominatimRateLimiter.recordFailure();
                logger.warn("Nominatim error: " + ioe.getMessage());
                return null;
            }
            com.overdrive.app.mqtt.ProxyHelper.invalidateCache();
            logger.warn("Nominatim via proxy failed (" + ioe.getMessage()
                    + "); retrying direct");
            try {
                return nominatimAttempt(lat, lng, locale, java.net.Proxy.NO_PROXY);
            } catch (Throwable t) {
                NominatimRateLimiter.recordFailure();
                logger.warn("Nominatim error: " + t.getMessage());
                return null;
            }
        } catch (Throwable t) {
            NominatimRateLimiter.recordFailure();
            logger.warn("Nominatim error: " + t.getMessage());
            return null;
        }
    }

    /**
     * One Nominatim reverse-geocode request over ONE route. On bad mobile data
     * (or in regions where the OSM endpoint is firewalled) the sing-box tunnel
     * is the only working egress — every other outbound HTTP path in the
     * codebase (BYD Cloud, MQTT, AppUpdater) routes through ProxyHelper for
     * the same reason. The custom-Nominatim self-host case still works:
     * sing-box's routing config exempts internal IPs by default. IO-level
     * failures propagate so the caller can decide whether a direct retry is
     * warranted; server-code and parse failures are terminal here.
     */
    private PlaceResult nominatimAttempt(
            double lat, double lng, String locale, java.net.Proxy route)
            throws java.io.IOException {
        HttpURLConnection conn = null;
        try {
            String base = nominatimBaseUrl();
            String url = base + "/reverse"
                    + "?format=jsonv2"
                    + "&lat=" + URLEncoder.encode(Double.toString(lat), "UTF-8")
                    + "&lon=" + URLEncoder.encode(Double.toString(lng), "UTF-8")
                    + "&zoom=" + NOMINATIM_ZOOM
                    + "&addressdetails=1";

            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection(route);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent());
            conn.setRequestProperty("Accept", "application/json");
            // accept-language drives place-name script. Fall back to en so
            // foreign places get a Latin-script transliteration when OSM has
            // one.
            String acceptLang = locale + ", en;q=0.5";
            conn.setRequestProperty("Accept-Language", acceptLang);

            int code = conn.getResponseCode();
            if (code == 429 || code >= 500) {
                NominatimRateLimiter.recordFailure();
                logger.warn("Nominatim HTTP " + code + " — backing off");
                return null;
            }
            if (code >= 400) {
                NominatimRateLimiter.recordFailure();
                logger.warn("Nominatim HTTP " + code + " — backing off");
                return null;
            }

            String body = readAll(conn);
            JSONObject root = new JSONObject(body);
            String displayName = root.optString("display_name", "");
            JSONObject addr = root.optJSONObject("address");
            String district = "";
            String city = "";
            String country = "";
            String cc = "";
            if (addr != null) {
                district = firstNonEmpty(
                        addr.optString("suburb", ""),
                        addr.optString("neighbourhood", ""),
                        addr.optString("city_district", ""),
                        addr.optString("village", ""),
                        addr.optString("town", ""));
                city = firstNonEmpty(
                        addr.optString("city", ""),
                        addr.optString("town", ""),
                        addr.optString("municipality", ""),
                        addr.optString("county", ""),
                        addr.optString("state", ""));
                country = addr.optString("country", "");
                cc = addr.optString("country_code", "").toUpperCase();
            }
            if (displayName.isEmpty() && district.isEmpty() && city.isEmpty()) {
                NominatimRateLimiter.recordSuccess();
                return null;
            }
            NominatimRateLimiter.recordSuccess();
            return new PlaceResult(displayName, district, city, country, cc,
                    locale, PlaceResult.Source.NOMINATIM,
                    System.currentTimeMillis());
        } catch (java.io.IOException ioe) {
            // Route-level failure — propagate so the caller can decide
            // whether the other route deserves the one retry.
            throw ioe;
        } catch (Throwable t) {
            // Parse/JSON failures arrived over a WORKING route — terminal.
            NominatimRateLimiter.recordFailure();
            logger.warn("Nominatim parse error: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private static String readAll(HttpURLConnection conn) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder(2048);
            char[] buf = new char[2048];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String composeDisplayName(String district, String city, String country) {
        StringBuilder sb = new StringBuilder();
        if (district != null && !district.isEmpty()) sb.append(district);
        if (city != null && !city.isEmpty() && (district == null || !district.equals(city))) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(city);
        }
        if (country != null && !country.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(country);
        }
        return sb.toString();
    }

    private static String firstNonEmpty(String... vs) {
        for (String v : vs) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static java.util.Locale parseLocaleTag(String tag) {
        if (tag == null || tag.isEmpty()) return java.util.Locale.ENGLISH;
        try {
            return java.util.Locale.forLanguageTag(tag);
        } catch (Throwable ignored) {
            return java.util.Locale.ENGLISH;
        }
    }

    private static String userAgent() {
        String version;
        try {
            version = com.overdrive.app.BuildConfig.VERSION_NAME;
        } catch (Throwable t) {
            version = "1.0";
        }
        return String.format(USER_AGENT_TEMPLATE, version);
    }

    // ---- Config gates -----------------------------------------------------

    /**
     * Per-flow enable gate. {@code flow} is {@code "recording"},
     * {@code "surveillance"} or {@code "parking"} (Parking Intelligence; that
     * section only exists once the user set it explicitly, see
     * {@code DaemonParkingEnvironment.geocodingFlow}) — see
     * {@link UnifiedConfigManager#getGeocoding()} for the schema.
     */
    private static boolean isFlowEnabled(String flow) {
        return UnifiedConfigManager.isGeocodingEnabledForFlow(flow);
    }

    private static boolean isOnlineAllowed(String flow) {
        return UnifiedConfigManager.isGeocodingOnlineAllowedForFlow(flow);
    }

    /**
     * Custom Nominatim base URL is a single shared advanced setting (one
     * cooldown, one cache, one URL). {@code geocoding.advanced.customNominatimBase}.
     */
    private static String nominatimBaseUrl() {
        try {
            JSONObject geo = UnifiedConfigManager.loadConfig().optJSONObject("geocoding");
            if (geo == null) return NOMINATIM_DEFAULT;
            JSONObject advanced = geo.optJSONObject("advanced");
            if (advanced == null) return NOMINATIM_DEFAULT;
            String base = advanced.optString("customNominatimBase", "");
            if (base != null && !base.isEmpty()) {
                // Strip trailing slash so the path concat below stays clean.
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                return base;
            }
        } catch (Throwable ignored) {}
        return NOMINATIM_DEFAULT;
    }
}
