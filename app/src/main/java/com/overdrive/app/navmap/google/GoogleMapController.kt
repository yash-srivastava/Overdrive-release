package com.overdrive.app.navmap.google

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.SymbolLayer
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Google basemap + traffic policy for one map Activity: budget/latch/health decisions, I8 swap
 * spacing, session warm-up and copyright. Public API is main-thread only; network runs on [executor].
 */
class GoogleMapController(context: Context, private val clusterMode: Boolean, private val host: Host) {

    interface Host {
        fun isNight(): Boolean
        fun nightPalette(): GoogleMapStyle.NightPalette
        fun backgroundHex(): String
        fun requestStyleReload()
        fun onGoogleNotice(notice: Notice, resetAtMs: Long)
        fun mainHandler(): Handler
        fun onAttributionChanged()
    }

    enum class Notice { TRAFFIC_PAUSED, MAP_PAUSED, MAP_UNAVAILABLE, KEY_PROBLEM }

    data class Caps(val googleBasemap: Boolean, val traffic: Boolean)

    private enum class Block { NONE, USER, KEY, QUOTA, HEALTH }

    private enum class Ready { NONE, UPGRADE, TRAFFIC, RENEW }

    private class Snap(
        val settings: GoogleMapsSettings,
        val level: TileBudgetLevel,
        val latch: GoogleLatch?,
        val trafficUnsupported: Boolean
    )

    private data class Inputs(val language: String, val region: String)

    private data class Viewport(
        val token: String, val zoom: Int,
        val north: Double, val south: Double, val east: Double, val west: Double
    )

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAVMAP, Context.MODE_PRIVATE)
    private val hiDpi = context.resources.displayMetrics.density >= 1.5f
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GoogleMapController").apply { isDaemon = true }
    }
    private val inFlight: MutableSet<TileSessionSpec> = ConcurrentHashMap.newKeySet<TileSessionSpec>()
    private val reevalPosted = AtomicBoolean(false)
    @Volatile private var inputs: Inputs? = null
    @Volatile private var destroyed = false
    private var resumed = false

    var googleStyleActive: Boolean = false
        private set
    var trafficShown: Boolean = false
        private set

    var trafficPref: Boolean
        get() = prefs.getBoolean(KEY_TRAFFIC, true)
        set(value) { prefs.edit().putBoolean(KEY_TRAFFIC, value).apply() }

    var mapTypePref: GoogleMapType
        get() = prefs.getString(KEY_MAP_TYPE, null)
            ?.let { name -> GoogleMapType.entries.firstOrNull { it.name == name } }
            ?: GoogleMapType.ROADMAP
        set(value) { prefs.edit().putString(KEY_MAP_TYPE, value.name).apply() }

    var googleBasemapPref: Boolean
        get() = prefs.getBoolean(KEY_BASEMAP, true)
        set(value) { prefs.edit().putBoolean(KEY_BASEMAP, value).apply() }

    private var built = false
    private var lastSwapMs = 0L
    private var awaitingSession = false
    private var reloadRequestedMs = 0L
    private var activeBase: TileSession? = null
    private var activeBaseSpec: TileSessionSpec? = null
    // Tile-health baseline taken at activation: only failures after it can trigger a fallback.
    private var activeSinceWallMs = 0L
    private var baseFailures = 0
    private var baseSuccessMs = 0L
    private var healthySinceMs = 0L
    private var fallbackActive = false
    private var retryAtMs = 0L
    private var backoffStep = 0
    private var keyNoticeShown = false
    private var tunedRefreshMs = 0L
    private var lastStyle: WeakReference<Style>? = null
    private var lastMap: WeakReference<MapLibreMap>? = null
    private var copyrightText: String? = null
    private var lastViewport: Viewport? = null
    private var lastCopyrightMs = 0L
    private var copyrightFailures = 0
    private var copyrightRetryAtMs = 0L

    private val budgetListener = Runnable {
        if (!destroyed && reevalPosted.compareAndSet(false, true)) {
            host.mainHandler().post {
                reevalPosted.set(false)
                if (resumed && !destroyed) reevaluate()
            }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed || !resumed) return
            reevalPosted.set(false)
            submit { warm() }
            if (googleStyleActive) checkHealth()
            reevaluate()
            host.mainHandler().postDelayed(this, POLL_MS)
        }
    }

    private val copyrightRunnable = Runnable { fetchCopyright() }

    init {
        submit { warm() }
    }

    fun caps(): Caps {
        val s = GoogleMapsConfig.cached()
        return Caps(s.basemapUsable, s.trafficUsable && !GoogleHttp.trafficOverlayUnsupported())
    }

    fun buildStyle(vector: () -> Style.Builder): Style.Builder {
        val json = try {
            chooseGoogleStyle()
        } catch (t: Throwable) {
            Log.w(TAG, "google style decision failed: ${t.javaClass.simpleName}")
            commitStyle(null, null, SystemClock.elapsedRealtime(), want = false)
            null
        }
        return if (json != null) Style.Builder().fromJson(json) else vector()
    }

    fun onStyleLoaded(style: Style, map: MapLibreMap) {
        if (destroyed || !style.isFullyLoaded) return
        lastStyle = WeakReference(style)
        lastMap = WeakReference(map)
        val n = snap()
        if (trafficCapable(n)) {
            GoogleTileSessions.cached(trafficSpec())?.let { ts ->
                GoogleMapStyle.addTrafficLayer(style, ts, trafficAnchor(style))
            }
        }
        // After the traffic source exists, so it is volatile with prefetch 0 on vector styles too (I6).
        tunedRefreshMs = trafficRefreshMs(n)
        GoogleMapStyle.tuneSources(style, tunedRefreshMs)
        applyTraffic(style, n, notify = false)
        GoogleMapStyle.applyLod(map, googleStyleActive)
        if (googleStyleActive) scheduleCopyright()
        // Posted: a session that got ready mid-load upgrades without re-entering setStyle.
        if (awaitingSession) host.mainHandler().post { if (!destroyed) reevaluate() }
    }

    fun onCameraIdle(map: MapLibreMap) {
        if (destroyed || !googleStyleActive) return
        lastMap = WeakReference(map)
        scheduleCopyright()
    }

    fun copyright(): String? = copyrightText

    fun setTraffic(style: Style?, on: Boolean) {
        trafficPref = on
        val target = style?.takeIf { it.isFullyLoaded }?.also { lastStyle = WeakReference(it) } ?: lastStyle?.get()
        val n = snap()
        applyTraffic(target, n, notify = false)
        if (on && !trafficShown) {
            when (trafficBlock(n)) {
                Block.QUOTA -> notice(Notice.TRAFFIC_PAUSED, GoogleMapsBudget.nextResetMs())
                Block.KEY -> notice(Notice.KEY_PROBLEM, GoogleMapsBudget.nextResetMs())
                else -> Unit
            }
        }
    }

    fun onResume() {
        if (destroyed || resumed) return
        resumed = true
        reevalPosted.set(false)
        GoogleMapsBudget.addListener(budgetListener)
        val h = host.mainHandler()
        h.removeCallbacks(poll)
        h.post(poll)
    }

    fun onPause() {
        if (!resumed) return
        resumed = false
        GoogleMapsBudget.removeListener(budgetListener)
        val h = host.mainHandler()
        h.removeCallbacks(poll)
        h.removeCallbacks(copyrightRunnable)
    }

    fun onDestroy() {
        if (destroyed) return
        onPause()
        destroyed = true
        GoogleMapsBudget.removeListener(budgetListener)
        executor.shutdownNow()
        lastStyle = null
        lastMap = null
    }

    // ── Decisions ───────────────────────────────────────────────────────────

    /** Picks Google vs vector for the next style and records it; returns the Google style JSON or null. */
    private fun chooseGoogleStyle(): String? {
        val now = SystemClock.elapsedRealtime()
        val n = snap()
        val type = mapTypePref
        val night = host.isNight()
        val want = basemapBlock(n, now) == Block.NONE
        val spec = if (want) baseSpec(type, night) else null
        val session = spec?.let { GoogleTileSessions.cached(it) }
        val maxZoom = if (type == GoogleMapType.SATELLITE || type == GoogleMapType.HYBRID) 20 else 21
        val json = session?.let { GoogleMapStyle.googleStyleJson(it, maxZoom, backgroundHex()) }
        commitStyle(session, spec, now, want)
        try {
            if (spec != null) {
                if (session == null) ensureAsync(spec, Ready.UPGRADE)
                // The other theme's roadmap session is created ahead so a day/night flip stays on Google.
                if (type == GoogleMapType.ROADMAP) ensureAsync(baseSpec(type, !night), Ready.NONE)
            }
            if (trafficCapable(n)) ensureAsync(trafficSpec(), Ready.TRAFFIC)
        } catch (t: Throwable) {
            Log.w(TAG, "session warm-up failed: ${t.javaClass.simpleName}", t)
        }
        return json
    }

    private fun commitStyle(session: TileSession?, spec: TileSessionSpec?, now: Long, want: Boolean) {
        reloadRequestedMs = 0L
        val wasGoogle = googleStyleActive
        val google = session != null
        if (built && google != wasGoogle) lastSwapMs = now
        built = true
        googleStyleActive = google
        awaitingSession = want && !google
        if (session != null) {
            if (!wasGoogle) markActivated()
            fallbackActive = false
        }
        if (session == null || activeBase?.token != session.token) clearCopyright()
        activeBase = session
        activeBaseSpec = if (session != null) spec else null
    }

    private fun snap() = Snap(
        GoogleMapsConfig.cached(),
        GoogleMapsBudget.tileLevel(),
        GoogleMapsBudget.latch(GoogleApi.TILES),
        GoogleHttp.trafficOverlayUnsupported()
    )

    private fun basemapBlock(n: Snap, now: Long): Block {
        val s = n.settings
        if (!s.basemapUsable) {
            val rejected = s.enabled && s.hasKey && s.termsAcceptedMs > 0 && s.basemap && tilesRejected(s)
            return if (rejected) Block.KEY else Block.USER
        }
        if (!googleBasemapPref) return Block.USER
        n.latch?.let { return latchBlock(it) }
        if (n.level >= TileBudgetLevel.EXHAUSTED) return Block.QUOTA
        if (clusterMode && n.level != TileBudgetLevel.HEALTHY) return Block.QUOTA
        if (fallbackActive && now < retryAtMs) return Block.HEALTH
        return Block.NONE
    }

    private fun trafficBlock(n: Snap): Block {
        if (!trafficPref || !trafficCapable(n)) return Block.USER
        n.latch?.let { return latchBlock(it) }
        if (n.level >= TileBudgetLevel.TRAFFIC_OFF) return Block.QUOTA
        if (clusterMode && n.level >= TileBudgetLevel.CONSTRAINED) return Block.QUOTA
        return Block.NONE
    }

    private fun trafficCapable(n: Snap) = n.settings.trafficUsable && !n.trafficUnsupported

    private fun latchBlock(l: GoogleLatch) = if (l.reason == "quota") Block.QUOTA else Block.KEY

    private fun tilesRejected(s: GoogleMapsSettings): Boolean {
        val state = s.apiState[GoogleApi.TILES]
        return state == GoogleApiState.DISABLED || state == GoogleApiState.DENIED
    }

    private fun trafficRefreshMs(n: Snap): Long {
        val base = if (GoogleMapsBudget.paidMode()) GoogleMapStyle.TRAFFIC_REFRESH_PAID_MS else GoogleMapStyle.TRAFFIC_REFRESH_MS
        return if (n.level == TileBudgetLevel.CONSTRAINED) base * 2 else base
    }

    private fun swapAllowed(now: Long) = lastSwapMs == 0L || now - lastSwapMs >= SWAP_MIN_MS

    /** Budget/latch swaps are immediate; every other Google⇄vector swap waits [SWAP_MIN_MS] (I8). */
    private fun reevaluate() {
        if (destroyed) return
        val now = SystemClock.elapsedRealtime()
        val n = snap()
        val block = basemapBlock(n, now)
        val mapNoticed = googleStyleActive && (block == Block.QUOTA || block == Block.KEY)
        if (googleStyleActive) {
            when (block) {
                Block.QUOTA -> {
                    requestReload(now)
                    notice(Notice.MAP_PAUSED, GoogleMapsBudget.nextResetMs())
                }
                Block.KEY -> {
                    // A fresh createSession must succeed before tiles are tried again: no 403 swap loop.
                    activeBaseSpec?.let { GoogleTileSessions.invalidate(it) }
                    requestReload(now)
                    notice(Notice.KEY_PROBLEM, GoogleMapsBudget.nextResetMs())
                }
                Block.HEALTH -> requestReload(now)
                Block.USER -> if (swapAllowed(now)) requestReload(now)
                Block.NONE -> renewIfNeeded(now)
            }
        } else if (block == Block.NONE && lastStyle != null) {
            val spec = baseSpec(mapTypePref, host.isNight())
            if (GoogleTileSessions.cached(spec) == null) {
                ensureAsync(spec, Ready.UPGRADE)
            } else if (awaitingSession || swapAllowed(now)) {
                requestReload(now)
            }
        }
        val style = lastStyle?.get()?.takeIf { it.isFullyLoaded }
        val refresh = trafficRefreshMs(n)
        if (style != null && refresh != tunedRefreshMs) {
            GoogleMapStyle.tuneSources(style, refresh)
            tunedRefreshMs = refresh
        }
        // One notice per event: a paused basemap already covers its traffic overlay.
        applyTraffic(style, n, notify = !mapNoticed)
    }

    /** Google→Google reload when the active session nears expiry or its language/region/theme inputs changed. */
    private fun renewIfNeeded(now: Long) {
        val active = activeBaseSpec ?: return
        val night = host.isNight()
        val target = baseSpec(active.mapType, night)
        // Keep the other theme's roadmap session warm so a day/night flip never detours through vector.
        if (active.mapType == GoogleMapType.ROADMAP) {
            try {
                ensureAsync(baseSpec(GoogleMapType.ROADMAP, !night), Ready.NONE)
            } catch (t: Throwable) {
                Log.w(TAG, "other-theme warm-up failed: ${t.javaClass.simpleName}", t)
            }
        }
        val current = GoogleTileSessions.cached(target)
        if (current == null) {
            ensureAsync(target, Ready.RENEW)
        } else if (current.token != activeBase?.token) {
            requestReload(now)
        }
    }

    private fun checkHealth() {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val h = GoogleHttp.tileHealth()
        val fresh = if (h.lastSuccessMs != baseSuccessMs) h.consecutiveFailures else h.consecutiveFailures - baseFailures
        val unhealthy = fresh >= FALLBACK_FAILURES &&
            wall - maxOf(h.lastSuccessMs, activeSinceWallMs) >= FALLBACK_NO_SUCCESS_MS
        if (unhealthy) {
            healthySinceMs = 0L
            if (fallbackActive || !swapAllowed(now)) return
            val wait = BACKOFF_MS[backoffStep]
            backoffStep = minOf(backoffStep + 1, BACKOFF_MS.size - 1)
            fallbackActive = true
            retryAtMs = now + wait
            // The retry then needs a fresh createSession, which doubles as a connectivity probe.
            activeBaseSpec?.let { GoogleTileSessions.invalidate(it) }
            requestReload(now)
            notice(Notice.MAP_UNAVAILABLE, wall + wait)
        } else if (h.consecutiveFailures == 0 && h.lastSuccessMs >= activeSinceWallMs) {
            if (healthySinceMs == 0L) healthySinceMs = now
            else if (now - healthySinceMs >= HEALTHY_RESET_MS) backoffStep = 0
        } else {
            healthySinceMs = 0L
        }
    }

    private fun markActivated() {
        val h = GoogleHttp.tileHealth()
        baseFailures = h.consecutiveFailures
        baseSuccessMs = h.lastSuccessMs
        activeSinceWallMs = System.currentTimeMillis()
        healthySinceMs = 0L
    }

    private fun requestReload(now: Long) {
        if (destroyed) return
        if (reloadRequestedMs != 0L && now - reloadRequestedMs < RELOAD_RETRY_MS) return
        reloadRequestedMs = now
        try {
            host.requestStyleReload()
        } catch (t: Throwable) {
            Log.w(TAG, "style reload failed: ${t.javaClass.simpleName}")
        }
    }

    private fun notice(notice: Notice, resetAtMs: Long) {
        if (notice == Notice.KEY_PROBLEM) {
            if (keyNoticeShown) return
            keyNoticeShown = true
        } else {
            val day = GoogleMapsBudget.nextResetMs()
            if (noticeDays.put("$clusterMode/${notice.name}", day) == day) return
        }
        try {
            host.onGoogleNotice(notice, resetAtMs)
        } catch (t: Throwable) {
            Log.w(TAG, "notice failed: ${t.javaClass.simpleName}")
        }
    }

    // ── Traffic overlay ─────────────────────────────────────────────────────

    private fun applyTraffic(style: Style?, n: Snap, notify: Boolean) {
        val block = trafficBlock(n)
        val want = block == Block.NONE
        var shown = false
        val st = style?.takeIf { it.isFullyLoaded }
        if (st != null) {
            try {
                if (want && st.getLayer(GoogleMapStyle.TRAFFIC_LAYER_ID) == null) {
                    val ts = GoogleTileSessions.cached(trafficSpec())
                    if (ts != null) {
                        GoogleMapStyle.addTrafficLayer(st, ts, trafficAnchor(st))
                        GoogleMapStyle.tuneSources(st, trafficRefreshMs(n))
                    } else {
                        ensureAsync(trafficSpec(), Ready.TRAFFIC)
                    }
                }
                if (st.getLayer(GoogleMapStyle.TRAFFIC_LAYER_ID) != null) {
                    GoogleMapStyle.setTrafficVisible(st, want)
                    shown = want
                }
            } catch (t: Throwable) {
                Log.w(TAG, "traffic update failed: ${t.javaClass.simpleName}")
            }
        }
        if (notify && trafficShown && !shown) {
            when (block) {
                Block.QUOTA -> notice(Notice.TRAFFIC_PAUSED, GoogleMapsBudget.nextResetMs())
                Block.KEY -> notice(Notice.KEY_PROBLEM, GoogleMapsBudget.nextResetMs())
                else -> Unit
            }
        }
        trafficShown = shown
    }

    /**
     * Google style: right above the base raster. Vector style: below the first label above the last
     * road line layer (the bundled styles put some labels under the roads), never above our overlay layers.
     */
    private fun trafficAnchor(style: Style): String? = try {
        val layers = style.layers
        val base = layers.indexOfFirst { it.id == GoogleMapStyle.BASE_LAYER_ID }
        if (base >= 0) {
            layers.getOrNull(base + 1)?.id
        } else {
            val lastRoad = layers.indexOfLast {
                it !is SymbolLayer && !isOurLayer(it.id) && ROAD_LAYER.containsMatchIn(it.id)
            }
            val label = (lastRoad + 1 until layers.size).firstOrNull { layers[it] is SymbolLayer } ?: -1
            val ours = layers.indexOfFirst { isOurLayer(it.id) }
            listOf(label, ours).filter { it >= 0 }.minOrNull()?.let { layers[it].id }
        }
    } catch (_: Throwable) {
        null
    }

    private fun isOurLayer(id: String) = id.startsWith(ROADSENSE_LAYER_PREFIX) || id.startsWith(GLASS_LAYER_PREFIX)

    // ── Sessions ────────────────────────────────────────────────────────────

    private fun specInputs(): Inputs = inputs ?: readInputs().also { inputs = it }

    private fun readInputs() = Inputs(GoogleTileSessions.language(), GoogleTileSessions.region(appContext))

    private fun baseSpec(type: GoogleMapType, night: Boolean): TileSessionSpec {
        val i = specInputs()
        val styles = if (type == GoogleMapType.ROADMAP && night) GoogleMapStyle.nightStylesJson(host.nightPalette()) else null
        return TileSessionSpec(type, false, styles, i.language, i.region, hiDpi)
    }

    private fun trafficSpec(): TileSessionSpec = trafficSpecFor(specInputs())

    private fun ensureAsync(spec: TileSessionSpec, ready: Ready) {
        if (destroyed || GoogleTileSessions.cached(spec) != null || !inFlight.add(spec)) return
        try {
            executor.execute {
                val ok = try {
                    GoogleTileSessions.ensure(spec) != null
                } catch (t: Throwable) {
                    Log.w(TAG, "session ensure failed: ${t.javaClass.simpleName}")
                    false
                } finally {
                    inFlight.remove(spec)
                }
                if (ok && ready != Ready.NONE && !destroyed) host.mainHandler().post { onReady(ready) }
            }
        } catch (_: RejectedExecutionException) {
            inFlight.remove(spec)
        }
    }

    private fun onReady(ready: Ready) {
        if (destroyed) return
        when (ready) {
            Ready.UPGRADE -> reevaluate()
            Ready.TRAFFIC -> applyTraffic(lastStyle?.get(), snap(), notify = false)
            Ready.RENEW -> requestReload(SystemClock.elapsedRealtime())
            Ready.NONE -> Unit
        }
    }

    /** Off-main: keeps the settings cache, language/region and the session store warm for main-thread reads. */
    private fun warm() {
        try {
            val s = GoogleMapsConfig.cached()
            if (!s.enabled || !s.hasKey) return
            val next = readInputs()
            inputs = next
            GoogleTileSessions.cached(trafficSpecFor(next))
        } catch (t: Throwable) {
            Log.w(TAG, "warm-up failed: ${t.javaClass.simpleName}")
        }
    }

    private fun trafficSpecFor(i: Inputs) = TileSessionSpec(GoogleMapType.ROADMAP, true, null, i.language, i.region, hiDpi)

    private fun backgroundHex(): String =
        (try { host.backgroundHex() } catch (_: Throwable) { null })?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_BACKGROUND

    private fun submit(task: () -> Unit) {
        if (destroyed) return
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
        }
    }

    // ── Copyright ───────────────────────────────────────────────────────────

    private fun clearCopyright() {
        copyrightText = null
        lastViewport = null
        copyrightFailures = 0
        copyrightRetryAtMs = 0L
    }

    private fun scheduleCopyright() {
        val h = host.mainHandler()
        h.removeCallbacks(copyrightRunnable)
        val now = SystemClock.elapsedRealtime()
        val wait = nextCopyrightWaitMs(now, lastCopyrightMs, copyrightRetryAtMs)
        if (wait > 0L) h.postDelayed(copyrightRunnable, wait) else fetchCopyright()
    }

    private fun fetchCopyright() {
        if (destroyed || !googleStyleActive) return
        val session = activeBase ?: return
        val map = lastMap?.get() ?: return
        val now = SystemClock.elapsedRealtime()
        if (now < copyrightRetryAtMs) {
            scheduleCopyright()
            return
        }
        val vp = try { viewport(map, session.token) } catch (_: Throwable) { null } ?: return
        if (lastViewport?.let { near(it, vp) } == true) return
        lastCopyrightMs = now
        submit {
            val text = GoogleTileSessions.fetchCopyright(session, vp.zoom, vp.north, vp.south, vp.east, vp.west)
            if (!destroyed) {
                host.mainHandler().post {
                    if (destroyed || activeBase?.token != session.token) return@post
                    if (text != null) {
                        copyrightFailures = 0
                        copyrightRetryAtMs = 0L
                        lastViewport = vp
                        if (text != copyrightText) {
                            copyrightText = text
                            host.onAttributionChanged()
                        }
                    } else {
                        val backoff = copyrightBackoffMs(copyrightFailures)
                        copyrightFailures++
                        copyrightRetryAtMs = SystemClock.elapsedRealtime() + backoff
                    }
                }
            }
        }
    }

    private fun viewport(map: MapLibreMap, token: String): Viewport {
        val zoom = Math.round(map.cameraPosition.zoom + 1.0).toInt().coerceIn(0, 22)
        val b = map.projection.visibleRegion.latLngBounds
        return Viewport(token, zoom, b.latitudeNorth, b.latitudeSouth, b.longitudeEast, b.longitudeWest)
    }

    /** Same session and zoom, edges moved under a quarter of the span: the copyright cannot have changed. */
    private fun near(a: Viewport, b: Viewport): Boolean {
        if (a.token != b.token || a.zoom != b.zoom) return false
        val latTol = abs(a.north - a.south) * VIEWPORT_TOLERANCE
        val lngTol = abs(a.east - a.west) * VIEWPORT_TOLERANCE
        return abs(a.north - b.north) <= latTol && abs(a.south - b.south) <= latTol &&
            abs(a.east - b.east) <= lngTol && abs(a.west - b.west) <= lngTol
    }

    companion object {
        private const val TAG = "GoogleMapController"
        private const val PREFS_NAVMAP = "navmap_ui"
        private const val KEY_TRAFFIC = "map_traffic"
        private const val KEY_MAP_TYPE = "google_map_type"
        private const val KEY_BASEMAP = "google_basemap"
        private const val ROADSENSE_LAYER_PREFIX = "roadsense-"
        private const val GLASS_LAYER_PREFIX = "od-"
        private const val DEFAULT_BACKGROUND = "#E5E3DF"
        private const val POLL_MS = 10_000L
        private const val SWAP_MIN_MS = 120_000L
        private const val RELOAD_RETRY_MS = 10_000L
        private const val FALLBACK_FAILURES = 6
        private const val FALLBACK_NO_SUCCESS_MS = 30_000L
        private const val HEALTHY_RESET_MS = 60_000L
        private const val COPYRIGHT_MIN_MS = 3_000L
        private const val VIEWPORT_TOLERANCE = 0.25
        private val ROAD_LAYER = Regex("road|highway|bridge|tunnel|motorway|street")
        private val BACKOFF_MS = longArrayOf(120_000L, 240_000L, 480_000L, 900_000L)
        private val COPYRIGHT_BACKOFF_MS = longArrayOf(15_000L, 30_000L, 60_000L, 180_000L)

        // Notice day keys shared by every controller in the process ("mode/notice" -> PT reset ms).
        private val noticeDays = ConcurrentHashMap<String, Long>()

        internal fun copyrightBackoffMs(consecutiveFailures: Int): Long =
            COPYRIGHT_BACKOFF_MS[consecutiveFailures.coerceIn(0, COPYRIGHT_BACKOFF_MS.lastIndex)]

        internal fun nextCopyrightWaitMs(nowMs: Long, lastAttemptMs: Long, retryAtMs: Long): Long {
            val earliest = maxOf(lastAttemptMs + COPYRIGHT_MIN_MS, retryAtMs)
            return maxOf(0L, earliest - nowMs)
        }
    }
}
