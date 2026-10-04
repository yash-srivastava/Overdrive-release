package com.overdrive.app.navmap

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.overdrive.app.R
import com.overdrive.app.telenav.TelenavActions
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import com.overdrive.app.navmap.nav.ForwardGeocoder
import com.overdrive.app.navmap.nav.NavGuidanceEngine
import com.overdrive.app.navmap.nav.NavRoute
import com.overdrive.app.navmap.nav.NavVoice
import com.overdrive.app.navmap.nav.SearchResult
import com.overdrive.app.navmap.nav.ValhallaRouteClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RoadSense Map — native MapLibre head-unit map surface (Phase 1+2).
 *
 * <p>MANIFEST: this Activity needs an <activity> entry registered by the
 * parent — e.g.
 * <pre>
 *   &lt;activity
 *       android:name="com.overdrive.app.navmap.RoadSenseMapActivity"
 *       android:exported="false"
 *       android:theme="@style/Theme.Overdrive.M3"
 *       android:configChanges="orientation|screenSize|keyboardHidden" /&gt;
 * </pre>
 * Nav-rail / launch wiring is intentionally NOT done here.
 *
 * <p>What it does:
 *   - Renders an OpenFreeMap "liberty" basemap (no API key) and overlays the
 *     device's crowdsourced road hazards as data-driven SymbolLayer markers.
 *   - Fetches hazards for the visible viewport from the daemon
 *     ({@code GET /api/roadsense/hazards?bbox=...}) on camera-idle, debounced.
 *   - Tap a hazard → M3 bottom sheet to Confirm (human-verify, optionally
 *     correcting severity/type) or Delete (reject) it.
 *
 * <p>Lifecycle: MapLibre.getInstance(this) is called BEFORE setContentView,
 * and every MapView lifecycle callback is forwarded. This is the #1 MapLibre
 * crash source, so it is kept exact.
 *
 * <p>Hazard property contract (from RoadSenseApiHandler): each GeoJSON
 * Feature carries {id, type 0=BREAKER/1=POTHOLE/2=UNKNOWN/3=ROUGH,
 * severity 1..3, confidence 0..1, status 0=candidate/1=local/2=cloud,
 * observations, humanVerified, heading, updatedMs}.
 */
open class RoadSenseMapActivity : AppCompatActivity() {

    // ---------------------------------------------------------------------
    // View / map state
    // ---------------------------------------------------------------------
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var hazardSource: GeoJsonSource? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Background executor for the daemon HTTP calls — never on the looper. */
    @Volatile private var ioExecutor: ExecutorService? = null

    /** Monotonic token so a stale in-flight fetch result is discarded. */
    @Volatile private var fetchToken: Long = 0L

    /** Debounce runnable for camera-idle viewport refetches. */
    private val refetchRunnable = Runnable { fetchViewportHazards() }

    /** Most recent GPS fix (for the puck + prefetch); null until first fix. */
    @Volatile private var lastFix: RoadSenseHazardApiClient.LatLngFix? = null

    /** True until the first successful auto-recenter, so we only auto-jump once. */
    private var didInitialRecenter = false

    /**
     * Guard so the map click / camera-idle listeners are wired exactly once even
     * if onStyleLoaded runs again (e.g. a future day/night style reload). Defensive
     * — a duplicate registration would fire onMapTap / scheduleRefetch twice.
     */
    private var listenersWired = false

    // --- Navigation (route search + guidance) state ---
    private var routeSource: GeoJsonSource? = null
    // Handles to the two route LineLayers (casing + main) so the per-frame traveled-route
    // trim can update their line-gradient paint property without a getLayerAs lookup each
    // frame. Re-captured on every onStyleLoaded (theme reload recreates the layers),
    // nulled in onDestroy. See applyRouteTrim.
    private var routeMainLayer: LineLayer? = null
    private var routeCasingLayer: LineLayer? = null
    // Traveled-route trim state (per Activity instance — head unit + cluster each run
    // their own guidance + render tick). lastRouteProgress = monotonic clamp accumulator
    // (the trim boundary never walks backward on a GPS wobble / brief off-route, so the
    // traveled part never re-appears); lastAppliedProgress = paint dead-band so a parked /
    // sub-metre-creeping car emits no gradient writes. Both reset at every new-route site.
    private var lastRouteProgress: Float = 0f
    private var lastAppliedProgress: Float = -1f
    // Route theme colors (m3 ints) captured in onStyleLoaded — reused to rebuild the trim
    // gradient each frame and re-applied on a day/night flip so the un-trimmed remainder
    // keeps the right color (the gradient program ignores the static lineColor recolor).
    private var routeMainColor: Int = 0
    private var routeCasingColor: Int = 0
    private val guidance = NavGuidanceEngine()
    private var navVoice: NavVoice? = null
    @Volatile private var navigating = false

    // --- Search autocomplete ---
    // Tap a result → route there; long-press → place sheet (save Home/Work/fav)
    // so saving never requires computing a route first.
    private val searchAdapter = NavSearchResultAdapter(
        onResultTap = { result -> onSearchResultChosen(result) },
        onResultLongPress = { result -> showPlaceSheet(result, isDroppedPin = false) },
        onResultRemove = { result -> removeRecentSearch(result) }
    )
    @Volatile private var acToken: Long = 0L
    private var pendingAutocomplete: Runnable? = null

    // --- Alternate-route preview (before guidance starts) ---
    private val routeOptionAdapter = NavRouteOptionAdapter { idx -> onRouteOptionSelected(idx) }
    private var previewRoutes: List<NavRoute> = emptyList()
    private var previewDestLabel: String = ""

    // --- Multi-stop itinerary (Google-Maps-style waypoints) ---
    /**
     * The ORDERED itinerary AFTER the origin: [stop1, stop2, …, destination].
     * Convention (documented once here): the origin is ALWAYS the live GPS fix
     * ([lastFix]) and is NOT stored in this list; the LAST entry is the final
     * destination and every earlier entry is an intermediate via-stop the route
     * must pass through in order. So:
     *   - size 0 → no destination chosen yet (no preview).
     *   - size 1 → just a destination, no stops → use routesWithAlternates.
     *   - size >1 → destination + ≥1 stop → use routeVia([origin] + routeStops).
     */
    private val routeStops = ArrayList<SearchResult>()

    /**
     * When true, the NEXT chosen search result is INSERTED as an intermediate
     * stop (before the destination) instead of replacing the destination. Set by
     * the "Add stop" affordance, cleared after a pick or when the sheet resets.
     */
    @Volatile private var addingStop = false

    /** Index into [routeStops] being edited (re-searched), or -1 if none. */
    private var editingStopIndex = -1
    // Locked destination while navigating (for tap-to-switch + auto-reroute).
    private var lockedDestLat: Double = Double.NaN
    private var lockedDestLng: Double = Double.NaN
    @Volatile private var rerouting = false
    private var lastRerouteMs: Long = 0L
    private var previewSelectedIdx: Int = 0
    private var altRouteSource: GeoJsonSource? = null
    private var routeSheetBehavior:
        com.google.android.material.bottomsheet.BottomSheetBehavior<View>? = null

    // --- POI along route (EV charging / fuel) ---
    private var poiSource: GeoJsonSource? = null
    @Volatile private var poiEnabled = false

    // --- Itinerary markers (destination pin + numbered stop pins) ---
    private var markerSource: GeoJsonSource? = null
    /** Largest stop-ordinal bitmap registered so far (so we only build each once). */
    private var maxStopIconRegistered = 0

    // --- Dropped-pin marker (long-press the map to save/navigate a place) ---
    private var droppedPinSource: GeoJsonSource? = null
    /** The currently-open place-action sheet, so a new long-press dismisses the
     *  prior one (both share the single droppedPinSource). Null when none is up. */
    private var openPlaceSheet: BottomSheetDialog? = null
    /** Liveness of the currently-open place sheet — flipped false when the sheet is
     *  dismissed OR superseded by a swap, so its in-flight reverse-geocode callback
     *  is a real no-op (the dismiss listener is nulled on the swap path, so the flag,
     *  not the listener, is the reliable liveness signal). */
    private var openPlaceSheetAlive: java.util.concurrent.atomic.AtomicBoolean? = null

    /**
     * When non-null, the user tapped an UNSET Home/Work chip and the next chosen
     * search result should be SAVED into this slot (instead of routed to). One of
     * [SavedPlacesStore.KIND_HOME] / [SavedPlacesStore.KIND_WORK]. Cleared after a
     * pick or when search is dismissed, so a normal search never silently saves.
     */
    private var pendingSaveKind: String? = null

    // --- Hazard visibility filter ---
    private var hazardSymbolLayer: SymbolLayer? = null
    /** 0 = hidden, 1 = severe only, 2 = moderate+ (≥2), 3 = all (default). */
    private var hazardFilterMode = HAZARD_FILTER_ALL

    // --- 2D / 3D buildings ---
    /** Persisted 2D/3D choice; DEFAULT 2D (false) for both themes — 3D extrusion
     *  is GPU-heavy on the Adreno 610 and adds to the shared-GPU contention. */
    private var map3dEnabled = false

    /**
     * Per-map day/night override, persisted independently of the app theme.
     * [MAP_THEME_AUTO] (default) = follow the Overdrive app theme; [MAP_THEME_LIGHT]
     * / [MAP_THEME_DARK] = the user pinned the map to that scheme regardless of the
     * app. Cycled by the in-map theme FAB; honored by [isNightTheme] /
     * [styleUrlForTheme]. Loaded in onCreate.
     */
    private var mapThemeMode = MAP_THEME_AUTO

    /** True once we've restored any persisted trip on this Activity launch, so a
     *  later style reload (e.g. theme switch) doesn't re-trigger the restore. */
    private var didRestoreTrip = false

    /**
     * True when this Activity instance was launched onto the driver cluster
     * (am start --display N --ez cluster true). The cluster is a NON-TOUCH display,
     * so in this mode we strip every touch control, force a larger heading-up
     * immersive camera, and keep the view glanceable. Set once in onCreate.
     */
    private var clusterMode = false


    // ── Motion smoothing (dead-reckoning) ───────────────────────────────────────
    // The 1s guidance tick is the TRUTH feeder (pulls a fix → estimator + engine);
    // the ~12fps render tick GLIDES the puck + camera from the dead-reckoned estimate
    // so motion is continuous instead of teleporting once per sparse GPS fix.
    private val motionEstimator = com.overdrive.app.navmap.nav.VehicleMotionEstimator()
    /** True while the render micro-tick should run (nav, or cluster idle-follow). */
    @Volatile private var motionFollowActive = false
    /** Last render frame saw the car moving (speed > gate). Drives the adaptive tick:
     *  fast (RENDER_TICK_MS) when moving or navigating, idle (IDLE_RENDER_TICK_MS) when
     *  parked, so a stationary map stops pegging the main thread + GL RenderThread. */
    @Volatile private var lastFrameMoving = false
    /** HEAD-UNIT view of the daemon's navMap.clusterMapActive flag: true while the SAME
     *  drive is being mirrored live on the cluster display. When true, the head unit
     *  stops running its 30fps motion glide (the driver is watching the cluster, not the
     *  head unit) and falls back to the idle tick — the head-unit map stays truthful +
     *  fully interactive (search/pan/zoom repaint on demand under WHEN_DIRTY) but the
     *  smooth animated map is rendered only ONCE, on the cluster. Cluster instance leaves
     *  this false (it IS the live view). Polled off the looper — see headUnitProjectionPoll. */
    @Volatile private var projectionActive = false

    // ── (C) Gyro yaw-rate fusion for SOTA crisp-turn heading ─────────────────────
    // The puck math (VehicleMotionEstimator) runs in THIS (app) process, and so does
    // the RoadSense IMU sidecar — but that one pumps samples OUT to the daemon for
    // hazard detection. For the puck we tap the gyro DIRECTLY here (no daemon round
    // trip): a lightweight SensorEventListener on the REAL "-iner" gyro (via ImuSource,
    // never getDefaultSensor → that returns a frozen STUB on this head unit, F-001/3),
    // plus a local GravityFrame to project the raw gyro vector onto measured gravity so
    // we get the true SIGNED yaw-rate about vertical (the 9.2°-tilted mount means raw
    // gz is not "up"; alongGravity tilt-corrects it — same technique the daemon's
    // hazard path uses). The estimator fuses this complementary-style and only trusts
    // it once it agrees with GPS heading, so a missing/stub/wrong-axis gyro silently
    // degrades to today's GPS-only path. All sensor work runs on a background thread;
    // it only calls into the estimator (its onGyroYaw is @Volatile-safe).
    private var gyroSensorManager: android.hardware.SensorManager? = null
    private var gyroSensor: android.hardware.Sensor? = null
    private var accelSensor: android.hardware.Sensor? = null
    private var sensorThread: android.os.HandlerThread? = null
    private var sensorHandler: android.os.Handler? = null
    /** Local gravity estimator for tilt-corrected yaw isolation (app-side copy; the
     *  daemon has its own for hazard detection). Touched only on the sensor thread. */
    private val puckGravity = com.overdrive.app.roadsense.detect.GravityFrame()
    private val gyroListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent) {
            when (event.sensor.type) {
                android.hardware.Sensor.TYPE_ACCELEROMETER ->
                    // Feed the gravity estimate so alongGravity() can isolate true yaw.
                    puckGravity.update(event.values[0], event.values[1], event.values[2])
                android.hardware.Sensor.TYPE_GYROSCOPE -> {
                    // Signed yaw rate about TRUE vertical (rad/s), tilt-corrected.
                    val yawRps = puckGravity.alongGravity(
                        event.values[0], event.values[1], event.values[2]
                    ).toDouble()
                    val tsMs = android.os.SystemClock.elapsedRealtime()
                    motionEstimator.onGyroYaw(yawRps, tsMs)
                }
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    /** Start the app-side gyro tap (idempotent). No-op (stays GPS-only) when the trim
     *  has no real "-iner" gyro. Registers on a background thread so 50 Hz sensor
     *  callbacks never touch the UI looper. */
    private fun startGyroFusion() {
        if (gyroSensorManager != null) return   // already running
        val sm = getSystemService(android.content.Context.SENSOR_SERVICE)
            as? android.hardware.SensorManager ?: return
        val resolved = com.overdrive.app.roadsense.detect.ImuSource.resolve(sm)
        // Need the REAL inertial gyro; without it, leave the estimator on GPS-only.
        if (resolved.gyroscope == null || !resolved.gyroIsInertial) return
        val t = android.os.HandlerThread("puck-gyro", android.os.Process.THREAD_PRIORITY_DEFAULT)
        t.start()
        sensorThread = t
        sensorHandler = android.os.Handler(t.looper)
        gyroSensorManager = sm
        gyroSensor = resolved.gyroscope
        // Only feed the gravity frame from the REAL "-iner" accel. A stub accel emits a
        // frozen, mostly-horizontal vector (F-001), which would make alongGravity() project
        // the gyro onto a bogus "vertical" and corrupt the yaw — and the health latch would
        // then just never trust the gyro (silent GPS-only, but fusion wasted on a doomed
        // projection even though a REAL gyro exists). With no accel registered, GravityFrame
        // is unseeded so alongGravity falls back to raw gz, which on the ~9.2° mount is
        // ≈0.987 of true vertical — a far better yaw proxy that lets the gyro earn the latch.
        accelSensor = resolved.accelerometer?.takeIf { resolved.accelIsInertial }
        // SENSOR_DELAY_GAME (~50 Hz) is plenty for heading integration and far cheaper
        // than the detection path's SENSOR_DELAY_FASTEST (~100 Hz).
        //
        // DO NOT add hardware FIFO batching (maxReportLatencyUs) to this tap.
        // Tried and REVERTED: the puck's yaw integrator timestamps each sample with
        // SystemClock.elapsedRealtime() AT DELIVERY (see gyroListener above) rather
        // than event.timestamp, and VehicleMotionEstimator.onGyroYaw integrates
        // yawRate × (tsMs - lastGyroTsMs). Batched samples arrive in a burst with
        // ~0ms wall-clock between them, so every sample after the first in a burst
        // gets dtS≈0 and is DISCARDED by the `if (dtS <= 0.0) return` guard —
        // silently losing ~half the turn rotation until the next GPS fix re-anchors
        // the heading. Batching here would need the listener to pass
        // event.timestamp (ns, elapsedRealtime base) into onGyroYaw first.
        accelSensor?.let { sm.registerListener(gyroListener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME, sensorHandler) }
        gyroSensor?.let { sm.registerListener(gyroListener, it, android.hardware.SensorManager.SENSOR_DELAY_GAME, sensorHandler) }
    }

    /** Stop the gyro tap + tear down its thread (call in onStop/onDestroy). */
    private fun stopGyroFusion() {
        gyroSensorManager?.let { try { it.unregisterListener(gyroListener) } catch (_: Throwable) {} }
        gyroSensorManager = null
        gyroSensor = null
        accelSensor = null
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
    }

    /** Periodic guidance tick: pulls a fresh GPS fix and advances guidance. */
    private val guidanceRunnable = object : Runnable {
        override fun run() {
            if (!navigating) return
            tickGuidance()
            mainHandler.postDelayed(this, GUIDANCE_TICK_MS)
        }
    }

    /** Render micro-tick: dead-reckons the vehicle motion forward from the last
     *  filtered fix and paints the puck + heading-up camera, so movement glides
     *  between the sparse 1s GPS truth fixes. Cheap: instant moveCamera (no anim
     *  thread) on an already-smooth interpolated pose. Self-reschedules at the FAST
     *  RENDER_TICK_MS only while navigating (real motion to glide); the no-route idle
     *  / cluster follow drops to IDLE_RENDER_TICK_MS — there's nothing to interpolate
     *  when parked, and at 30fps the tick + per-frame puck rewrite pegged the GL
     *  RenderThread on a stationary car (measured ~80% of a core, ×2 map instances). */
    private val renderRunnable = object : Runnable {
        override fun run() {
            if (!motionFollowActive) return
            try { renderMotionFrame() } catch (_: Throwable) {}
            // Fast tick while there's real motion to glide (navigating, or a moving car
            // on the no-route cluster follow); slow tick when parked so we stop waking
            // the main thread 30×/s to recompute an unchanged pose. Motion-aware (not
            // just nav-aware) so driving WITHOUT a route still glides smoothly.
            //   PROJECTION GATE: on the HEAD UNIT while the same drive is mirrored live
            //   on the cluster (projectionActive), don't run the 30fps glide — the driver
            //   is watching the cluster, so rendering the smooth animated map a SECOND
            //   time on the head unit is pure duplicate GPU. Drop to the idle rate; the
            //   head-unit map stays truthful (1Hz puck) and fully interactive (search/
            //   pan/zoom repaint on demand via WHEN_DIRTY). The cluster instance itself
            //   leaves projectionActive=false and keeps the fast glide.
            val fast = (navigating || lastFrameMoving) && !projectionActive
            // Cluster glides at its own (lower) cap-matched rate; head unit at 30fps.
            val fastTick = if (clusterMode) CLUSTER_RENDER_TICK_MS else RENDER_TICK_MS
            mainHandler.postDelayed(this, if (fast) fastTick else IDLE_RENDER_TICK_MS)
        }
    }

    private fun startMotionFollow() {
        if (motionFollowActive) return
        motionFollowActive = true
        mainHandler.removeCallbacks(renderRunnable)
        mainHandler.post(renderRunnable)
    }

    private fun stopMotionFollow() {
        motionFollowActive = false
        mainHandler.removeCallbacks(renderRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // MUST precede setContentView — initializes the MapLibre runtime so
        // the inflated MapView can attach its render surface. Getting this
        // order wrong is the canonical MapLibre crash.
        MapLibre.getInstance(this)
        // Make MapLibre's own tile/style/glyph/sprite fetches PROXY-AWARE — must
        // run after getInstance() and before the first style load. Without it the
        // basemap would bypass sing-box/Tailscale on a proxied head unit while
        // search/routing (already proxy-aware) worked.
        com.overdrive.app.navmap.nav.MapNetworking.installMapLibreHttpClient()

        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_roadsense_map)

        // Cluster mode: launched onto the non-touch driver-cluster display by the
        // daemon. Detected via the launching COMPONENT (the singleInstance alias
        // RoadSenseClusterMapActivity) OR the legacy `cluster` extra — component is
        // the robust signal (survives onNewIntent / can't be lost). Strip touch
        // chrome + go glanceable.
        // Cluster mode when we're the dedicated cluster subclass (robust — survives
        // onNewIntent / can't be lost) OR the legacy `cluster` extra is set.
        clusterMode = this is RoadSenseClusterMapActivity ||
            intent?.getBooleanExtra("cluster", false) == true

        // Safety: the cluster instance must NEVER end up on the default (head-unit)
        // display — if AMS placed it on display 0 (no cluster display present), it
        // would clobber the infotainment map. Finish immediately so the touch
        // instance is untouched.
        if (clusterMode && currentDisplayId() == android.view.Display.DEFAULT_DISPLAY) {
            android.util.Log.w("RoadSenseMap", "cluster instance landed on display 0 — finishing")
            finishAndRemoveTask()
            return
        }
        if (clusterMode) {
            applyClusterChrome()
            registerClusterDisplayWatch()
            startClusterFinishPoll()
            // The cluster mirror has no theme FAB (it's non-touch) — the user changes
            // the map theme on the head unit. Both Activities live in the SAME process,
            // so a SharedPreferences change listener on KEY_MAP_THEME lets the cluster
            // re-resolve its scheme and reload the basemap live, instead of staying on
            // whatever it loaded at launch until the projection is torn down + relaunched.
            registerClusterThemeWatch()
        } else {
            // Head unit: watch whether the same drive is mirrored live on the cluster,
            // so we can suppress our own duplicate 30fps glide while it is (see
            // headUnitProjectionPoll / projectionActive).
            startHeadUnitProjectionPoll()
        }

        findViewById<MaterialToolbar>(R.id.mapToolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        // Immersive-nav back FAB (the toolbar is hidden during immersive driving):
        // same affordance as the toolbar chevron — pop the Activity.
        findViewById<FloatingActionButton>(R.id.fabNavBack)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Edge-to-edge: pad the top chrome (toolbar/search/banner) down past the
        // status bar and lift the FAB stack above the system nav bar so nothing
        // is clipped. MapLibre's own chrome is offset separately in onMapReady.
        applyWindowInsets()

        // Responsive: on a wide (landscape) screen, cap the floating panels to a
        // column + center them (full-width edge-to-edge reads stretched on the
        // 1920px Seal). Re-applied on rotation via onConfigurationChanged.
        applyResponsiveLayout()

        findViewById<FloatingActionButton>(R.id.fabLocate).setOnClickListener {
            recenter()
        }

        // Destination search — Gmap-style: type-ahead autocomplete (debounced
        // Photon) drives the dropdown; submit (IME / glyph) is the on-submit
        // fallback. Wired in setupSearch().
        setupSearch()
        findViewById<FloatingActionButton>(R.id.fabEndNav)?.setOnClickListener {
            stopGuidance()
            showSnackbar(getString(R.string.roadsense_map_nav_ended))
        }

        // Hazard visibility filter (toggle / severity) + POI (EV charging / fuel) toggle.
        setupHazardFilter()
        findViewById<FloatingActionButton>(R.id.fabPoi)?.setOnClickListener { togglePoi() }
        findViewById<FloatingActionButton>(R.id.fabMap3d)?.setOnClickListener { toggleMap3d() }

        // Per-map day/night override (Auto → Light → Dark). Loaded before the first
        // style URL is resolved so the basemap opens in the persisted scheme.
        mapThemeMode = getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE)
            .getInt(KEY_MAP_THEME, MAP_THEME_AUTO)
        findViewById<FloatingActionButton>(R.id.fabMapTheme)?.let { fab ->
            // The theme control is irrelevant on the glanceable non-touch cluster.
            if (clusterMode) fab.visibility = View.GONE
            else fab.setOnClickListener { cycleMapTheme() }
        }
        updateMapThemeFab()

        // Explicit zoom +/- (MapLibre 11.x has no built-in zoom buttons; on a
        // head-unit an on-screen control is expected over pinch alone).
        findViewById<View>(R.id.fabZoomIn)?.setOnClickListener { zoomBy(1.0) }
        findViewById<View>(R.id.fabZoomOut)?.setOnClickListener { zoomBy(-1.0) }

        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mlMap -> onMapReady(mlMap) }
    }

    /**
     * Apply system-bar insets so the edge-to-edge map UI isn't clipped: the
     * top app bar + search bar + maneuver banner are pushed below the status
     * bar, and the FAB stack is lifted above the navigation bar. The MapView
     * itself stays full-bleed (the basemap reaches every edge); only the
     * overlaid controls are inset. Also re-offsets MapLibre's own chrome once
     * the real inset is known.
     */
    private fun applyWindowInsets() {
        val appBar = findViewById<View>(R.id.mapAppBar)
        val searchColumn = findViewById<View>(R.id.navSearchColumn)
        val banner = findViewById<View>(R.id.navBanner)
        val fabLocate = findViewById<View>(R.id.fabLocate)
        val fabEnd = findViewById<View>(R.id.fabEndNav)
        val fabNavBack = findViewById<View>(R.id.fabNavBack)
        val zoomControls = findViewById<View>(R.id.zoomControls)
        val controlsTop = findViewById<View>(R.id.mapControlsTop)
        val routeSheet = findViewById<View>(R.id.routeOptionsSheet)
        val routeSheetContent = findViewById<View>(R.id.routeSheetContent)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.mapRoot)
        ) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            appBar?.setPadding(0, bars.top, 0, 0)
            // The search COLUMN (bar + dropdown) is pinned to the top, just below
            // the status bar + toolbar. Offset the whole column (NOT the inner
            // card — that double-offset was the misalignment).
            (searchColumn?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(56); searchColumn.layoutParams = it
            }
            // CLUSTER: applyClusterChrome OWNS the banner geometry (top-start, vertically
            // centred in the clear band above the centre-left speed badge, overscan-safe
            // margins, grows downward). The inset
            // pass must NOT touch it here or it would fight that placement. HEAD UNIT: the
            // +56dp clears the search bar below the status bar.
            if (!clusterMode) {
                (banner?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                    it.topMargin = bars.top + dp(56); banner.layoutParams = it
                }
            }
            // Top-right map controls sit below the search bar.
            (controlsTop?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(128); controlsTop.layoutParams = it
            }
            // Immersive back FAB: just under the status bar, top-start.
            (fabNavBack?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = bars.top + dp(16); fabNavBack.layoutParams = it
            }
            (fabLocate?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = bars.bottom + dp(24); fabLocate.layoutParams = it
            }
            (fabEnd?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                // fabEndNav is the ONLY bottom|START control (every other bottom FAB is
                // bottom|end). On head units whose system bar / gesture inset / display
                // cutout sits on the LEFT edge (varies by OEM panel + orientation), a
                // fixed marginStart would leave the button UNDER that bar — occluded, so
                // it reads as "the cancel button isn't shown" on those models only. Add
                // the left inset so it always clears the bar. bars.bottom keeps it above
                // the nav bar as before.
                it.bottomMargin = bars.bottom + dp(24)
                it.marginStart = bars.left + dp(24)
                fabEnd.layoutParams = it
            }
            (zoomControls?.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let {
                it.bottomMargin = bars.bottom + dp(96); zoomControls.layoutParams = it
            }
            // Route-options sheet: pad its CONTENT above the nav bar so the Start
            // button + last row aren't hidden behind the system navigation bar.
            routeSheetContent?.setPadding(
                routeSheetContent.paddingLeft, routeSheetContent.paddingTop,
                routeSheetContent.paddingRight, bars.bottom + dp(12)
            )
            // Re-peek the sheet so the bottom inset is included in peekHeight.
            routeSheet?.let {
                routeSheetBehavior?.peekHeight = dp(260) + bars.bottom
            }
            // Re-offset MapLibre chrome now that we have the real inset.
            map?.let { applyMapChromeInsets(it) }
            insets
        }
    }

    /**
     * Cap the floating top panels (search column + maneuver banner) to a sensible
     * column width and center them on wide/landscape screens; on portrait/narrow
     * screens they fill the width. Without this, full-width panels stretch edge-to-
     * edge across the 1920px landscape display and look unbalanced. The route sheet
     * stays full-width (a bottom sheet is meant to span the bottom).
     */
    private fun applyResponsiveLayout() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val maxColW = (PANEL_MAX_WIDTH_DP * dm.density).toInt()
        val wide = screenW > maxColW + (32 * dm.density).toInt()
        val targetW = if (wide) maxColW else android.view.ViewGroup.LayoutParams.MATCH_PARENT

        fun setWidthCentered(id: Int) {
            val v = findViewById<View>(id) ?: return
            (v.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let { lp ->
                lp.width = targetW
                // Keep the existing top gravity; add horizontal centering when capped.
                lp.gravity = android.view.Gravity.TOP or
                    (if (wide) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START)
                v.layoutParams = lp
            }
        }
        setWidthCentered(R.id.navSearchColumn)
        // The cluster owns its own compact fixed-width banner geometry
        // (applyClusterChrome) — don't let the responsive full-width/centered sizing
        // clobber it (this also runs from onConfigurationChanged on rotation).
        if (!clusterMode) setWidthCentered(R.id.navBanner)
    }

    /**
     * On the cluster, continuously follow the live GPS fix in the immersive
     * heading-up camera even when no route is active, so the projected map always
     * tracks the car. While navigating, the guidance tick already drives the
     * camera, so this only runs the idle (no-route) follow.
     *
     * <p>This is a pure TRUTH FEEDER (like {@link #tickGuidance}): it fetches a fresh
     * fix at the 1 Hz GPS cadence and feeds it into the motion estimator, then ensures
     * the 30 fps {@link #renderMotionFrame} loop is running. The render loop is the
     * SOLE writer of the puck + heading-up camera. Previously this runnable wrote the
     * puck/camera DIRECTLY from the raw 1 Hz fix while the render tick (left running on
     * the cluster after guidance ended) ALSO dead-reckoned the puck from a stale fix —
     * two unsynchronized writers that visibly fought = the cluster puck jump. Routing
     * everything through the estimator gives the cluster the SAME smooth dead-reckoned
     * glide as the head-unit nav view and removes the race. feedMotionTruth() dedupes
     * identical re-polls so the estimator advances once per genuinely-new fix.
     */
    private val clusterFollowRunnable = object : Runnable {
        override fun run() {
            if (!clusterMode || isFinishing || isDestroyed) return
            if (!navigating) {   // guidance owns the estimator+camera while navigating
                ioExecutor().execute {
                    val fix = RoadSenseHazardApiClient.fetchCurrentLocation()
                    if (fix != null) mainHandler.post {
                        if (isFinishing || isDestroyed || navigating) return@post
                        lastFix = fix
                        feedMotionTruth(fix)   // single source of truth → render tick paints
                        startMotionFollow()    // idempotent; 30 fps glide of puck + camera
                    }
                }
            }
            mainHandler.postDelayed(this, GUIDANCE_TICK_MS)
        }
    }

    private fun startClusterFollow() {
        mainHandler.removeCallbacks(clusterFollowRunnable)
        mainHandler.post(clusterFollowRunnable)
        // Arm the cluster keep-warm repaint nudge alongside the follow loop (cluster
        // only). It defends the "projection went black" symptom against a surface/EGL
        // recreate that arrives BETWEEN resume/display events: under WHEN_DIRTY a parked
        // map emits no dirty event, so a silently-recreated surface would stay black; a
        // low-rate triggerRepaint() guarantees it repaints within one interval. Idempotent.
        startClusterKeepWarm()
    }

    /**
     * Force a single GL repaint of the cluster map NOW, bypassing the WHEN_DIRTY +
     * puck/camera dead-band suppression. Cheap (one buffer swap); cluster-only callers.
     * Also clears the puck dead-band anchor so the very next follow tick repaints too.
     */
    private fun forceClusterRepaint() {
        if (isFinishing || isDestroyed) return
        try { map?.triggerRepaint() } catch (_: Throwable) {}
        puckPaintedLat = Double.NaN   // defeat the per-frame dead-band on the next tick
    }

    /**
     * Cluster-only keep-warm repaint ticker. WHEN_DIRTY (set in onMapReady to kill the
     * parked-car 60fps GPU cost) means a recreated/lost GL surface on the OEM fission
     * display has no dirty event to repaint it on a stationary car → it stays BLACK until
     * an off/on cycle. A low-rate triggerRepaint() (every CLUSTER_KEEP_WARM_MS) bounds how
     * long a silently-recreated surface can stay black to one interval. Cost is ~one GL
     * frame every few seconds — negligible vs the CONTINUOUS mode WHEN_DIRTY replaced
     * (~80% of a core, doubled by the dual-map). Suspended in onStop with the other loops.
     */
    private val clusterKeepWarmRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || !clusterMode) return
            try { map?.triggerRepaint() } catch (_: Throwable) {}
            mainHandler.postDelayed(this, CLUSTER_KEEP_WARM_MS)
        }
    }

    private fun startClusterKeepWarm() {
        if (!clusterMode) return
        mainHandler.removeCallbacks(clusterKeepWarmRunnable)
        mainHandler.postDelayed(clusterKeepWarmRunnable, CLUSTER_KEEP_WARM_MS)
    }

    /**
     * Head-unit IDLE (non-navigating) GPS follow — the head-unit counterpart to
     * [clusterFollowRunnable]. The head unit previously had NO idle GPS ticker, so
     * the puck only refreshed while navigating or on a manual Locate tap; after a
     * resume-from-background it sat at the stale pre-background fix until the user
     * tapped Locate (while the cluster, which DOES have an idle follow, stayed live).
     *
     * <p>This refreshes the PUCK ONLY — it never moves the camera — so a user who
     * panned/zoomed away from their position keeps that view; the blue dot just stays
     * truthful underneath it. (Locate remains the explicit "snap back to me" action.)
     * Runs only on the head unit while NOT navigating: the guidance tick owns the puck
     * during nav, and the cluster has its own follow. Self-reschedules at the 1 Hz GPS
     * cadence; suspended in onStop, restarted in onResume.
     */
    private val idleFollowRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || navigating || clusterMode) return
            ioExecutor().execute {
                val fix = RoadSenseHazardApiClient.fetchCurrentLocation()
                if (fix != null) mainHandler.post {
                    if (isFinishing || isDestroyed || navigating || clusterMode) return@post
                    lastFix = fix
                    // SOTA idle glide: feed the estimator + run the render tick so the
                    // idle puck dead-reckons + gyro-fuses between the sparse 1 Hz fixes
                    // (was: updateLocationPuck = raw teleport once/sec). renderMotionFrame
                    // skips the camera block when NOT navigating/cluster (see its gate), so
                    // this stays PUCK-ONLY — a user who panned away keeps their view.
                    feedMotionTruth(fix)
                    startMotionFollow()   // idempotent; 30/200ms adaptive tick
                }
            }
            mainHandler.postDelayed(this, GUIDANCE_TICK_MS)
        }
    }

    private fun startIdleFollow() {
        if (navigating || clusterMode) return
        mainHandler.removeCallbacks(idleFollowRunnable)
        mainHandler.post(idleFollowRunnable)
    }

    private fun stopIdleFollow() {
        mainHandler.removeCallbacks(idleFollowRunnable)
    }

    /**
     * Cluster-display chrome: the driver cluster is NON-TOUCH and glanceable, so
     * hide everything interactive (toolbar/back, search column, all FABs, zoom,
     * map-control stack). Only the map + the turn-by-turn maneuver banner remain.
     * Map gestures are also off (no input device on the cluster). The camera is
     * driven entirely by the guidance loop in cluster mode.
     */
    private var clusterDisplayListener: android.hardware.display.DisplayManager.DisplayListener? = null
    /** Cluster-only: listens to the shared NavSession to mirror the infotainment route. */
    private var navSessionListener: NavSession.Listener? = null
    /** Cluster-only: the route the cluster's guidance engine is currently armed on,
     *  so a NavSession update can detect a mid-nav route CHANGE (reroute / switch)
     *  and re-arm guidance instead of staying on the stale route. Identity compare. */
    private var clusterNavRoute: NavRoute? = null

    /**
     * On the cluster, watch for our own display being removed (the OEM projection
     * torn down on ACC-off / disable / stop) and finish() this Activity so its GL
     * surface + follow loop don't orphan. Because the cluster runs in a separate
     * task (singleInstance alias), finishing it leaves the infotainment instance
     * untouched.
     */
    /**
     * The id of the display this Activity is on, version-safe. The Kotlin `display`
     * property compiles to {@code Activity.getDisplay()}, which is **API 30**; this
     * head unit runs **API 29** ([[reference_device_os_android10]]), so calling it
     * throws {@link NoSuchMethodError} at runtime — and the `display?.` safe-call guards
     * a null RETURN, not a missing METHOD, so it does NOT prevent the crash. Lint can't
     * catch it either (build.gradle.kts sets abortOnError=false / checkReleaseBuilds=false).
     * Use the API-30 accessor only when actually on ≥30; below that fall back to the
     * deprecated {@code WindowManager.getDefaultDisplay()} (API 17+, present on 29).
     * Returns -1 if neither is resolvable. This was crashing EVERY cluster map launch.
     */
    private fun currentDisplayId(): Int {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                display?.displayId ?: -1
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(android.content.Context.WINDOW_SERVICE)
                    as? android.view.WindowManager)?.defaultDisplay?.displayId ?: -1
            }
        } catch (_: Throwable) {
            -1
        }
    }

    private fun registerClusterDisplayWatch() {
        val myId = currentDisplayId().takeIf { it >= 0 } ?: return
        val dm = getSystemService(android.content.Context.DISPLAY_SERVICE)
            as? android.hardware.display.DisplayManager ?: return
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                // A fission re-composite / display reconfigure fires this while the
                // Activity stays RESUMED — the one event we receive that signals the GL
                // surface may have been recreated (and, under WHEN_DIRTY on a parked map,
                // left BLACK with no dirty event to repaint it). Force a repaint so the
                // map self-heals in place instead of staying black until an off/on cycle.
                if (displayId == myId) mainHandler.post { forceClusterRepaint() }
            }
            override fun onDisplayRemoved(displayId: Int) {
                if (displayId == myId) {
                    mainHandler.removeCallbacks(clusterFollowRunnable)
                    if (!isFinishing && !isDestroyed) finish()
                }
            }
        }
        clusterDisplayListener = l
        dm.registerDisplayListener(l, mainHandler)
    }

    // ---- Cluster self-finish poll (the map-leak fix) ------------------------
    // The OEM cluster projection close (18→0) deliberately never destroys the
    // fission VirtualDisplay (opcode 1 is forbidden — it poisons teardown), so the
    // onDisplayRemoved watch above does NOT fire on a normal stop/disable/ACC-cycle.
    // That left this cluster Activity parked on the still-alive display, re-surfacing
    // under the partial blind-spot card the next time a turn signal re-opened the
    // SAME projection — the "map shows on the cluster even when projection is
    // disabled" bug. So, mirroring the proven DeterrentActivity pattern, poll the
    // daemon-written navMap.clusterMapActive flag and finish ourselves the moment the
    // daemon (ClusterMapProjector.stop / abort) drops it. CLUSTER-ONLY (started only
    // from the clusterMode branch of onCreate), so the infotainment instance on
    // display 0 is never touched. The poll is removed in onDestroy via
    // mainHandler.removeCallbacksAndMessages(null).
    private var clusterFinishing = false
    private val clusterFinishPoll = object : Runnable {
        override fun run() {
            if (clusterFinishing || isFinishing || isDestroyed) return
            if (clusterMapDismissed()) {
                clusterFinishing = true
                mainHandler.removeCallbacks(clusterFollowRunnable)
                android.util.Log.i("RoadSenseMap", "cluster map dismissed by daemon — finishing")
                if (!isFinishing && !isDestroyed) finishAndRemoveTask()
                return
            }
            mainHandler.postDelayed(this, CLUSTER_FINISH_POLL_MS)
        }
    }

    private fun startClusterFinishPoll() {
        mainHandler.postDelayed(clusterFinishPoll, CLUSTER_FINISH_POLL_MS)
    }

    // ---- Cluster live theme watch -------------------------------------------
    // CLUSTER-ONLY. The head-unit cycleMapTheme() persists the chosen scheme to
    // PREFS_NAVMAP/KEY_MAP_THEME and reloads its own basemap. The cluster mirror is a
    // separate Activity instance (same process) that read mapThemeMode once at onCreate
    // and has no theme FAB, so it never followed a live dark↔light switch. Listen for
    // the SharedPreferences change, re-resolve mapThemeMode, and reload the style only
    // when the EFFECTIVE day/night state actually flips (cheap no-op otherwise). The
    // callback already fires on the main thread (same process that committed the apply()),
    // so reloadStyleForTheme — which touches the GL map — is safe to call directly.
    private var clusterThemeListener:
        android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun registerClusterThemeWatch() {
        val prefs = getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE)
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key != KEY_MAP_THEME) return@OnSharedPreferenceChangeListener
            if (isFinishing || isDestroyed) return@OnSharedPreferenceChangeListener
            val newMode = sp.getInt(KEY_MAP_THEME, MAP_THEME_AUTO)
            if (newMode == mapThemeMode) return@OnSharedPreferenceChangeListener
            val wasNight = isNightTheme()
            mapThemeMode = newMode
            if (isNightTheme() != wasNight) reloadStyleForTheme()
        }
        clusterThemeListener = l
        prefs.registerOnSharedPreferenceChangeListener(l)
    }

    // ---- Head-unit projection-active poll (dual-render avoidance) -----------
    // HEAD-UNIT ONLY. Tracks the daemon's navMap.clusterMapActive flag so the head
    // unit knows when the SAME drive is being shown live on the cluster. While it is,
    // [projectionActive] suppresses the head unit's 30fps motion glide (the cluster is
    // the live view the driver watches) — so the animated map renders once, not twice,
    // halving the dual-map GPU cost during nav. The head-unit map stays interactive
    // (search/pan/zoom) the whole time. The UCM read runs on the IO executor (it's an
    // EACCES-prone cross-UID file read — never the looper) and only the cheap flag flip
    // posts back to the main thread. Self-reschedules; removed in onDestroy via
    // removeCallbacksAndMessages(null). Started only on the head unit (clusterMode=false).
    private val headUnitProjectionPoll = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || clusterMode) return
            ioExecutor().execute {
                val active = isClusterProjectionActive()
                mainHandler.post {
                    if (isFinishing || isDestroyed || clusterMode) return@post
                    if (active != projectionActive) {
                        projectionActive = active
                        // Re-kick the render tick so the rate change takes effect now
                        // (when projection ENDS we want the head unit back to the smooth
                        // glide immediately; when it STARTS we drop to idle next tick).
                        if (motionFollowActive) {
                            mainHandler.removeCallbacks(renderRunnable)
                            mainHandler.post(renderRunnable)
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, PROJECTION_POLL_MS)
        }
    }

    private fun startHeadUnitProjectionPoll() {
        if (clusterMode) return
        mainHandler.removeCallbacks(headUnitProjectionPoll)
        mainHandler.postDelayed(headUnitProjectionPoll, PROJECTION_POLL_MS)
    }

    private fun stopHeadUnitProjectionPoll() {
        mainHandler.removeCallbacks(headUnitProjectionPoll)
    }

    /** True iff the daemon currently has the cluster map projection active
     *  (navMap.clusterMapActive == true). forceReload() for cross-UID freshness; a read
     *  failure is treated as NOT active so a transient UCM error can never wedge the head
     *  unit into the throttled state (worst case: the head unit keeps its own full glide,
     *  i.e. the pre-change behaviour). */
    private fun isClusterProjectionActive(): Boolean {
        return try {
            val nav = com.overdrive.app.config.UnifiedConfigManager.forceReload()
                .optJSONObject("navMap")
            nav != null && nav.optBoolean("clusterMapActive", false)
        } catch (_: Throwable) {
            false
        }
    }

    /** True once the daemon has signalled this cluster projection should end
     *  (navMap.clusterMapActive == false). forceReload() for cross-write freshness.
     *  A read failure is treated as "not dismissed" so a transient UCM error never
     *  kills a live projection; the onDisplayRemoved watch + ACC-off paths remain
     *  as independent backstops. */
    private fun clusterMapDismissed(): Boolean {
        return try {
            val nav = com.overdrive.app.config.UnifiedConfigManager.forceReload()
                .optJSONObject("navMap")
            // Absent flag ⇒ legacy / not-yet-written ⇒ treat as still active (don't
            // self-finish on a fresh config that predates the flag).
            nav != null && nav.has("clusterMapActive") &&
                !nav.optBoolean("clusterMapActive", true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun applyClusterChrome() {
        // Cluster overscan defeat. The fission VirtualDisplay declares overscan
        // (80,50,80,50) (confirmed on-car: dumpsys window mContent/mStable=[80,50]
        // [1840,670] on the full [0,0][1920,720] frame). Without opting in, the window
        // content is confined to that ~1760×620 overscan-SAFE rect and the OEM scales
        // it back up to the 1920×720 panel — black borders + a soft, upscaled map (the
        // "low-res projection" report). FLAG_LAYOUT_IN_OVERSCAN is the purpose-built
        // lever: it lets the window paint into the overscan area so the map fills the
        // true surface 1:1 (no upscale). The LAYOUT_FULLSCREEN|LAYOUT_STABLE flags
        // complement it by also zeroing any stable/system-bar inset attribution. ALL
        // cluster-only: applyClusterChrome runs solely from the clusterMode branch
        // (onCreate), exitImmersive (cluster), and onConfigurationChanged (cluster), so
        // the head unit (display 0, no overscan) is byte-for-byte unchanged.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        listOf(
            R.id.mapAppBar, R.id.navSearchColumn, R.id.zoomControls,
            R.id.fabLocate, R.id.fabEndNav, R.id.mapControlsTop, R.id.fabNavBack
        ).forEach { findViewById<View>(it)?.visibility = View.GONE }
        // Keep the maneuver banner glanceable but COMPACT — it sits over a short
        // 720px panel that also magnifies sp by the 320-dpi density, so the previous
        // 30/18sp card wrapped to 2-3 lines and covered most of the map. Use the
        // tighter cluster sizes + a smaller glyph so the card stays a slim strip at
        // the top and leaves the road visible.
        val density = resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()

        findViewById<View>(R.id.navBanner)?.alpha = 1f
        findViewById<TextView>(R.id.navBannerPrimary)?.let { tv ->
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, CLUSTER_BANNER_PRIMARY_SP)
            // Single line on the compact cluster card — the secondary row carries
            // the remaining/ETA, so the primary instruction need not wrap (a 2-line
            // primary is what drove the old card tall).
            tv.maxLines = 1
        }
        findViewById<TextView>(R.id.navBannerSecondary)?.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP, CLUSTER_BANNER_SECONDARY_SP
        )
        // Shrink the maneuver glyph from the 40dp layout default so it matches the
        // smaller text and doesn't set the card height on its own.
        findViewById<ImageView>(R.id.navBannerIcon)?.let { iv ->
            val side = px(CLUSTER_BANNER_ICON_DP)
            iv.layoutParams = iv.layoutParams?.apply {
                width = side; height = side
                (this as? android.view.ViewGroup.MarginLayoutParams)
                    ?.marginEnd = px(CLUSTER_BANNER_ICON_MARGIN_DP)
            }
        }
        // Stack the maneuver card DIRECTLY BELOW the speed badge, LEFT-ALIGNED with it: the
        // badge and the card share the same left edge (CLUSTER_SPEED_BADGE_LEFT_PX) and the
        // card's TOP sits at panelH/2 — the shared "seam". The daemon-drawn speed badge
        // (ClusterSpeedOverlay, z=MAX SurfaceControl) places its BOTTOM just above that same
        // seam (panelH/2 − gap), so the two form a clean vertical stack: short speed strip
        // on top, TBT card immediately below, both flush-left. The two processes never talk;
        // they agree because both anchor to panelH/2 read from the same physical panel.
        //
        // panelH is just the height of OUR OWN window: the cluster Activity paints into the
        // fission panel 1:1 (FLAG_LAYOUT_IN_OVERSCAN, set above — no border, no upscale), so
        // the root view [R.id.mapRoot] measures exactly the panel. Read that directly — no
        // DisplayManager, no dumpsys, no resources.displayMetrics (which on the fission
        // VirtualDisplay can report the HEAD-UNIT metrics — the bug the old band-math hit).
        // If the root isn't measured yet during cluster setup (height 0), defer this whole
        // pass to the next layout with doOnLayout; the card stays at its XML default
        // (gone/top) until then, so nothing flashes mis-placed.
        val root = findViewById<View>(R.id.mapRoot)
        val panelH = root?.height ?: 0
        if (panelH <= 0) {
            root?.doOnLayout { if (!isFinishing && !isDestroyed && clusterMode) applyClusterChrome() }
            return
        }
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.navBanner)?.let { card ->
            // Top-left corner = (badge left edge, panelH/2). Same left X as the badge →
            // flush-left stack; top at the seam the badge ends just above.
            val stackLeftX = CLUSTER_SPEED_BADGE_LEFT_PX
            val stackSeamY = panelH / 2
            (card.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let { lp ->
                lp.width = px(CLUSTER_BANNER_WIDTH_DP)
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                lp.marginStart = stackLeftX
                lp.topMargin = stackSeamY
                lp.bottomMargin = 0
                card.layoutParams = lp
            }
            card.radius = px(CLUSTER_BANNER_RADIUS_DP).toFloat()
            // Tighten the inner LinearLayout padding (shared with the head unit in XML;
            // overridden here so the head unit stays unchanged).
            (card.getChildAt(0) as? android.view.ViewGroup)?.setPadding(
                px(CLUSTER_BANNER_PAD_H_DP), px(CLUSTER_BANNER_PAD_V_DP),
                px(CLUSTER_BANNER_PAD_H_DP), px(CLUSTER_BANNER_PAD_V_DP)
            )
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Bail on a not-yet-initialized instance (the display-0 early-return in onCreate
        // finishes before mapView is assigned, yet AMS can still deliver a config delta).
        // Symmetric with every other lifecycle override's ::mapView.isInitialized guard.
        if (!::mapView.isInitialized) return
        // configChanges (now incl. uiMode|density|fontScale|screenLayout) keeps the GL
        // surface alive across rotation AND a system day/night flip — WITHOUT recreating
        // the Activity mid-GL-init (the recreate-during-setStyle was a crash window). The
        // tradeoff: we must now apply a uiMode (night) delta ourselves, since the Activity
        // no longer restarts to pick it up.
        // re-apply responsive widths + re-request insets so panels reflow cleanly.
        applyResponsiveLayout()
        // Re-assert the cluster's compact banner geometry + overscan chrome: a config
        // delta delivered here (no recreate) would otherwise leave it un-refreshed, since
        // applyResponsiveLayout deliberately skips the cluster banner. Cheap + idempotent.
        if (clusterMode) applyClusterChrome()
        findViewById<View>(R.id.mapRoot)?.requestApplyInsets()
        // A system day/night change only affects the map when the user hasn't pinned a
        // scheme (mapThemeMode == AUTO). reloadStyleForTheme() is a no-op-safe reload that
        // re-resolves styleBuilderForTheme() for the new effective scheme; its own
        // in-flight guard + onStyleLoaded's isFullyLoaded guard keep it crash-safe even if
        // this lands close to another reload. Guarded on an ACTUAL effective-scheme flip so
        // a pure rotation/density delta doesn't pay for a style reload.
        if (mapThemeMode == MAP_THEME_AUTO && map != null) {
            // Use isNightTheme() (NOT raw newConfig.uiMode): for an AUTO map it honors the
            // app's AppCompatDelegate night-mode pin BEFORE falling back to the system
            // uiMode — matching the seed in onStyleLoaded (lastAppliedNight = isNightTheme())
            // and every other reload site (cycleMapTheme / cluster theme-watch both gate on
            // isNightTheme()). Reading the raw SYSTEM uiMode here instead would, when the app
            // theme is PINNED and the system flips, fire a wasteful reload to the SAME
            // effective scheme AND desync lastAppliedNight from what's actually painted.
            val nightNow = isNightTheme()
            if (nightNow != lastAppliedNight) {
                lastAppliedNight = nightNow
                reloadStyleForTheme()
            }
        }
    }

    /** The night-ness the basemap was last loaded for, so onConfigurationChanged only
     *  reloads the style on a REAL day/night flip (now that uiMode no longer recreates
     *  the Activity). Seeded on first style load. */
    private var lastAppliedNight: Boolean = false

    // ---------------------------------------------------------------------
    // Map setup
    // ---------------------------------------------------------------------

    private fun onMapReady(mlMap: MapLibreMap) {
        // getMapAsync is not cancellable: if the Activity was finished between the
        // getMapAsync() call and this async callback (rapid back-then-reopen, or the
        // cluster-finish poll tearing us down), the MapView/render surface is already
        // gone and setRenderingRefreshMode/setMaximumFps below would touch a destroyed
        // surface. Bail if we're on the way out.
        if (isFinishing || isDestroyed) return
        map = mlMap

        // Render ONLY when the scene actually changes. MapLibre defaults to CONTINUOUS
        // — the GL RenderThread swaps a buffer EVERY vsync (60fps) even when nothing
        // moved, which on a parked car pegged a core (measured RenderThread ~80%) and
        // DOUBLED it once the cluster mirror's second map instance was up. WHEN_DIRTY
        // renders only on a real change (camera move, source update, gesture, tile/label
        // load) — so a stationary map costs ~0 GPU on BOTH screens, and the head unit
        // still repaints instantly when the user pans or runs a search. Paired with the
        // puck-rewrite dead-band (updateLocationPuckAt) so a held pose emits no dirty
        // frame at all. setMaximumFps then bounds the MOVING-map rate: the cluster is a
        // glanceable driver panel that doesn't need the head unit's full rate, so it's
        // capped lower. (Both calls are safe here — the MapRenderer is created during
        // MapView init, before this onMapReady callback fires.)
        mapView.setRenderingRefreshMode(
            org.maplibre.android.maps.renderer.MapRenderer.RenderingRefreshMode.WHEN_DIRTY
        )
        mapView.setMaximumFps(if (clusterMode) CLUSTER_MAX_FPS else HEADUNIT_MAX_FPS)

        // App-like feel: lock rotation + tilt, hide the compass (irrelevant
        // when north-locked) but ENABLE on-screen zoom +/- controls (no
        // pinch-only). Keep attribution/logo (OSM data license requires it).
        mlMap.uiSettings.apply {
            setRotateGesturesEnabled(false)
            setTiltGesturesEnabled(false)
            setCompassEnabled(false)
            // Cluster is non-touch → kill ALL gestures; head-unit keeps pinch/zoom.
            setZoomGesturesEnabled(!clusterMode)
            setQuickZoomGesturesEnabled(!clusterMode)
            setScrollGesturesEnabled(!clusterMode)
        }
        // MapLibre 11.x dropped the legacy on-screen zoom buttons, so we provide
        // our own +/- FABs (zoomInBy below). Pinch/quick-zoom gestures stay on
        // (head-unit only).
        if (clusterMode) {
            // On the cluster, hide MapLibre's own logo/attribution too (glanceable
            // surface; attribution shown on the head-unit instance which is the
            // user-facing one). Follow the live GPS continuously.
            mlMap.uiSettings.setLogoEnabled(false)
            mlMap.uiSettings.setAttributionEnabled(false)
            startClusterFollow()
        }

        // Lift MapLibre's own chrome (attribution, logo, zoom buttons) clear of
        // the system nav bar AND our FABs, so nothing is clipped at the bottom
        // edge. Margins are in px; convert from dp.
        applyMapChromeInsets(mlMap)

        // Initial camera: a sensible mid-zoom over the default region until
        // the first viewport fetch / recenter narrows it.
        mlMap.moveCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(DEFAULT_LAT, DEFAULT_LNG), DEFAULT_ZOOM)
        )

        // Theme-aware basemap: dark style in night mode, light otherwise, so the
        // map matches the rest of the app shell. Loaded from the BUNDLED style asset
        // (instant, no network round-trip; hosted-URL fallback) — re-add all our
        // sources/layers in the onStyleLoaded callback (setStyle wipes them).
        mlMap.setStyle(styleBuilderForTheme()) { style ->
            onStyleLoaded(style)
        }
    }

    /**
     * Push MapLibre's built-in chrome (zoom buttons, attribution, logo) in from
     * the screen edges so the system navigation bar and our FAB stack never clip
     * it. Uses the current window insets (bottom system bar) plus a base margin.
     */
    private fun applyMapChromeInsets(mlMap: MapLibreMap) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val navBottom = rootBottomInsetPx()
        mlMap.uiSettings.apply {
            // Attribution + logo: bottom-START, lifted clear of the nav bar
            // (required by the OSM data license, kept legible + unclipped).
            setAttributionMargins(dp(8), 0, 0, navBottom + dp(8))
            setLogoMargins(dp(40), 0, 0, navBottom + dp(8))
        }
    }

    /** Current bottom system-bar inset in px (0 if not yet laid out). */
    private fun rootBottomInsetPx(): Int {
        return try {
            val root = findViewById<View>(R.id.mapRoot)
            androidx.core.view.ViewCompat.getRootWindowInsets(root)
                ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                ?.bottom ?: 0
        } catch (_: Throwable) { 0 }
    }

    /** OpenFreeMap style URL chosen by the active app theme (day/night). */
    private fun styleUrlForTheme(): String =
        if (isNightTheme()) STYLE_URL_DARK else STYLE_URL_LIGHT

    /** Bundled on-device style ASSET path chosen by the active app theme (day/night). */
    private fun styleAssetForTheme(): String =
        if (isNightTheme()) STYLE_ASSET_DARK else STYLE_ASSET_LIGHT

    /**
     * Build the basemap [Style.Builder] for the current theme, preferring the
     * BUNDLED on-device style asset and falling back to the hosted URL.
     *
     * <p>Why asset-first: the style document (the layer/paint "recipe") shipped in
     * [styleAssetForTheme] is byte-equivalent to the hosted style we used to fetch,
     * so there is ZERO visual change — but loading it from disk removes a network
     * round-trip on every map open (no proxy/sing-box stall, works with no
     * connectivity for the style itself), and it makes the basemap OURS to tune
     * (colors / road casings / label halos / POI styling) instead of an opaque URL.
     * The tiles, sprite and glyphs referenced INSIDE the style are still streamed
     * from OpenFreeMap (and cached by {@link MapTilePrefetcher}), exactly as before.
     *
     * <p>Resilience: if the asset is somehow unreadable (corrupt packaging), we fall
     * back to {@code fromUri(styleUrlForTheme())} so this can never render worse than
     * the previous URL-only path. MapLibre resolves {@code asset://} URLs against the
     * APK assets, so [readStyleAsset] only needs to confirm the asset opens.
     */
    private fun styleBuilderForTheme(): Style.Builder {
        val assetPath = styleAssetForTheme()
        val json = readStyleAsset(assetPath)
        return if (json != null) {
            Style.Builder().fromJson(json)
        } else {
            Log.w("RoadSenseMap", "bundled style asset $assetPath unreadable — falling back to hosted URL")
            Style.Builder().fromUri(styleUrlForTheme())
        }
    }

    /** Read a bundled style asset to a String, or null if it can't be opened/read. */
    private fun readStyleAsset(assetPath: String): String? = try {
        assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            .takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        null
    }

    /**
     * Active day/night state for the BASEMAP. A per-map override
     * ([mapThemeMode]) wins when the user has explicitly pinned light/dark from the
     * in-map theme control; otherwise (AUTO, the default) it mirrors the source the
     * rest of the app uses (AppCompatDelegate runtime mode, then the Configuration
     * uiMode fallback) so the map follows the same theme as the WebView shell.
     */
    private fun isNightTheme(): Boolean {
        when (mapThemeMode) {
            MAP_THEME_LIGHT -> return false
            MAP_THEME_DARK -> return true
        }
        when (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> return true
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> return false
        }
        val ui = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Resolve a Material-3 color ROLE (e.g. "surface", "outline_variant",
     * "secondary_container") to the themed `md_sys_color_<role>_(light|dark)` value
     * for the CURRENT basemap theme. Single source of truth for both the basemap
     * recolor and the overlay colors, so the whole map (basemap + route + puck) flips
     * day/night coherently off [isNightTheme]. Falls back to a neutral grey if a role
     * name is mistyped (defensive — a missing R.color would otherwise crash the walk).
     */
    private fun m3(role: String): Int {
        val name = "md_sys_color_${role}_" + if (isNightTheme()) "dark" else "light"
        val id = resources.getIdentifier(name, "color", packageName)
        return if (id != 0) ContextCompat.getColor(this, id) else 0xFF808080.toInt()
    }

    /** Hex string form of [m3], for paint properties set via a color string. */
    private fun m3Hex(role: String): String = colorToHex(m3(role))

    /** "#AARRGGBB" for a packed ARGB int (MapLibre accepts rgba()/hex strings). */
    private fun colorToHex(color: Int): String = String.format("#%08X", color)

    /**
     * Recolor the bundled basemap to the Overdrive Material-3 palette at runtime, so
     * the map matches the app shell (day + night) instead of rendering the stock
     * OpenFreeMap colors. This walks the loaded style's layers ONCE (mirroring
     * [localizeMapLabels]) and rewrites each layer's flat paint color onto an M3 role.
     *
     * <p>Cost: a single layer pass at style-load / theme-switch — NOT per frame. The
     * GPU renders a recolored flat fill identically to the original, so there is zero
     * added render cost (no new layers, no blur, no extrusion).
     *
     * <p>Safety: wrapped in try/catch like [localizeMapLabels] so basemap schema
     * variance can never break the map — a failed recolor just leaves the stock colors.
     * Roles are chosen to stay clear of the OVERLAY colors (route/puck = primary,
     * hazard pins = fixed saturated hues) so those stay legible on the recolored map.
     *
     * <p>Expression-valued paints (zoom-interpolated road/landuse colors, the
     * natural_earth relief raster-opacity) are intentionally LEFT ALONE — overwriting
     * them with a flat color would drop their zoom ramp. We only rewrite layers whose
     * id matches a bucket below, and {@link #setLayerColor} additionally skips a
     * property that currently holds an Expression.
     *
     * <p>The set of paint properties recolored here was derived by inspecting OUR OWN
     * bundled style assets (maps/liberty_style.json + maps/dark_style.json): they carry
     * background-color, fill-color, fill-outline-color, line-color, fill-extrusion-color,
     * text-color and text-halo-color — the standard MapLibre GL spec-v8 color paints.
     * The text-halo is recolored to the surface (background) tone so labels keep the
     * same "outlined in the background colour" legibility they have in the stock style.
     */
    private fun recolorBasemapForTheme(style: Style) {
        try {
            // Resolve every role once for this pass (cheap; avoids re-resolving per layer).
            val land = m3("surface")
            val water = m3("secondary_container")
            val parks = m3("surface_container_high")
            val roadFill = m3(if (isNightTheme()) "surface_container_high" else "surface_container_lowest")
            val roadCasing = m3("outline_variant")
            val building = m3("surface_container")
            val outlineSoft = m3("outline_variant")
            val boundary = m3("outline")
            val label = m3("on_surface_variant")
            val labelHalo = m3("surface")
            val poiAccent = m3("tertiary")

            for (layer in style.layers) {
                val id = layer.id
                // NEVER recolor our own overlay layers — they're added to the style
                // before this pass and own their (theme-aware) colors. All of ours are
                // "roadsense-…"; skipping the prefix also protects the 3D extrusion
                // (roadsense-building-3d) so it keeps MAP_3D_COLOR_*.
                if (id.startsWith("roadsense-")) continue
                when {
                    // Background / land.
                    id == "background" -> setLayerColor(layer, land)

                    // Water bodies + waterways (fill + line).
                    id == "water" || id.startsWith("water") -> setLayerColor(layer, water)
                    id.startsWith("waterway") -> setLayerColor(layer, water)

                    // Parks / landcover / landuse fills.
                    id == "park" -> setLayerColor(layer, parks)
                    id == "park_outline" -> setLayerColor(layer, outlineSoft)
                    id.startsWith("landcover") || id.startsWith("landuse") ||
                        id.startsWith("aeroway_fill") -> setLayerColor(layer, parks)

                    // Road casings (drawn under the fills) — soft outline tone.
                    id.contains("_casing") -> setLayerColor(layer, roadCasing)

                    // Road fills (incl. tunnels/bridges/links) — brighter than land.
                    id.startsWith("road_") || id.startsWith("tunnel_") ||
                        id.startsWith("bridge_") || id.startsWith("aeroway_runway") ||
                        id.startsWith("aeroway_taxiway") -> setLayerColor(layer, roadFill)

                    // Buildings (2D fill only; 3D extrusion keeps MAP_3D_COLOR_*).
                    id == "building" -> setLayerColor(layer, building)

                    // Administrative boundaries.
                    id.startsWith("boundary") -> setLayerColor(layer, boundary)

                    // Labels: transit/airport get a subtle tertiary accent; the rest the
                    // neutral on-surface-variant. Halo is the base land tone for contrast.
                    layer is SymbolLayer -> {
                        val text = if (id == "poi_transit" || id == "airport") poiAccent else label
                        layer.setProperties(
                            PropertyFactory.textColor(colorToHex(text)),
                            PropertyFactory.textHaloColor(colorToHex(labelHalo))
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            // Schema variance across basemaps — never break the map over a recolor.
        }
    }

    /**
     * Set the appropriate COLOR paint property for [layer] by its type, but ONLY when
     * that property currently holds a flat color (skips Expression-valued paints so
     * their zoom ramp survives). Fills also get their outline recolored to match.
     */
    private fun setLayerColor(layer: org.maplibre.android.style.layers.Layer, color: Int) {
        val hex = colorToHex(color)
        when (layer) {
            is org.maplibre.android.style.layers.BackgroundLayer ->
                layer.setProperties(PropertyFactory.backgroundColor(hex))
            is org.maplibre.android.style.layers.FillLayer -> {
                if (!layer.fillColor.isExpression) {
                    layer.setProperties(PropertyFactory.fillColor(hex))
                }
                // Fill outline only when it's a flat color present (building's is an
                // Expression; many fills have no outline at all).
                if (!layer.fillOutlineColor.isExpression && layer.fillOutlineColor.value != null) {
                    layer.setProperties(PropertyFactory.fillOutlineColor(hex))
                }
            }
            is LineLayer -> {
                if (!layer.lineColor.isExpression) {
                    layer.setProperties(PropertyFactory.lineColor(hex))
                }
            }
            is org.maplibre.android.style.layers.FillExtrusionLayer -> {
                if (!layer.fillExtrusionColor.isExpression) {
                    layer.setProperties(PropertyFactory.fillExtrusionColor(hex))
                }
            }
            is CircleLayer -> {
                if (!layer.circleColor.isExpression) {
                    layer.setProperties(PropertyFactory.circleColor(hex))
                }
            }
        }
    }

    private fun onStyleLoaded(style: Style) {
        // CRASH GUARD (intermittent "clicking Map crashes the app"): this callback is
        // async (setStyle{...}) and is re-entered on every theme switch (reloadStyleForTheme).
        //  - Bail if the Activity is tearing down (rapid back-then-reopen).
        //  - Bail if THIS style is no longer the fully-loaded one: an overlapping setStyle
        //    (fast double-tap of the theme FAB, or the head-unit theme change + the cluster
        //    theme-watch firing in the same process) supersedes the in-flight style, and
        //    calling style.addSource/addLayer on a not-fully-loaded style throws MapLibre's
        //    validateState IllegalStateException UNCAUGHT on the main thread → process death.
        //  - Wrap the whole body so any residual add/decode failure is logged, never fatal.
        if (isFinishing || isDestroyed || !::mapView.isInitialized) return
        if (!style.isFullyLoaded) return
        try {
        // Record the scheme this style was loaded for, so onConfigurationChanged can tell
        // a real day/night flip (→ reload) from a pure rotation/density delta (→ skip).
        lastAppliedNight = isNightTheme()
        // 0) Route line FIRST (added before hazards/clusters so the line draws
        //    UNDER the hazard markers). Two-layer stroke: a wide casing under a
        //    narrower bright main line. Empty until a route is computed.
        // Theme-aware so the route stays legible on BOTH the light and dark recolored
        // basemaps (was hardcoded _light). Resolved via the same m3() role helper the
        // basemap recolor uses, so the whole map flips day/night coherently.
        val routeColor = m3("primary")
        val routeCasing = m3("on_primary_container")
        routeMainColor = routeColor
        routeCasingColor = routeCasing
        // withLineMetrics(true) is REQUIRED for line-gradient (line-progress is otherwise
        // never generated and the gradient silently renders nothing). It's a source-
        // construction option that persists across all later setGeoJson() calls. This
        // drives the traveled-route trim (the gradient goes transparent behind the car).
        style.addSource(
            GeoJsonSource(
                ROUTE_SOURCE_ID, EMPTY_FEATURE_COLLECTION,
                GeoJsonOptions().withLineMetrics(true)
            )
        )
        style.addLayer(
            LineLayer(ROUTE_CASING_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                // Keep the flat lineColor as a base/fallback (it does NOT conflict — a
                // CONSTANT line-color coexists with line-gradient; the gradient just wins
                // where it's set). The day/night recolor (setLayerColor) re-tints this so
                // a theme flip stays coherent; the gradient is re-applied below to match.
                PropertyFactory.lineColor(routeCasing),
                // Traveled portion (line-progress < p) transparent, ahead full color.
                // Seeded at p=0 (whole route visible until guidance feeds progress).
                PropertyFactory.lineGradient(routeGradient(0f, routeCasing)),
                PropertyFactory.lineWidth(11f),
                PropertyFactory.lineOpacity(0.9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        // Alternate routes — a SEPARATE source/layer drawn UNDER the selected
        // route, dimmed + thinner but tappable (tap selects that alternate).
        // Added before the bright route layers so the selected route sits on top.
        val altColor = m3("outline")
        style.addSource(GeoJsonSource(ALT_ROUTE_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            LineLayer(ALT_ROUTE_LAYER_ID, ALT_ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(altColor),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineOpacity(0.55f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        altRouteSource = style.getSourceAs(ALT_ROUTE_SOURCE_ID)

        style.addLayer(
            LineLayer(ROUTE_MAIN_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(routeColor),
                PropertyFactory.lineGradient(routeGradient(0f, routeColor)),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineOpacity(0.95f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        routeSource = style.getSourceAs(ROUTE_SOURCE_ID)
        routeCasingLayer = style.getLayerAs(ROUTE_CASING_LAYER_ID)
        routeMainLayer = style.getLayerAs(ROUTE_MAIN_LAYER_ID)
        // A style reload (day/night flip) mid-trip recreated the layers above at p=0 (full
        // route). Re-apply the trim progress held across the reload so the traveled part
        // stays hidden instead of flashing back in. lastAppliedProgress=-1 forces the
        // next applyRouteTrim to repaint; here we paint the CURRENT clamp value directly.
        if (navigating && lastRouteProgress > 0f) {
            routeCasingLayer?.setProperties(PropertyFactory.lineGradient(routeGradient(lastRouteProgress, routeCasing)))
            routeMainLayer?.setProperties(PropertyFactory.lineGradient(routeGradient(lastRouteProgress, routeColor)))
            lastAppliedProgress = lastRouteProgress
        }

        // 1) Register the four hazard marker icons (rasterized from the
        //    tintable vector drawables) once for this style.
        registerHazardIcons(style)

        // 2) Hazard source: empty FeatureCollection to start, clustered so
        //    dense corridors collapse into a count bubble at low zoom.
        val source = GeoJsonSource(
            HAZARD_SOURCE_ID,
            EMPTY_FEATURE_COLLECTION,
            GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(CLUSTER_RADIUS)
                .withClusterMaxZoom(CLUSTER_MAX_ZOOM)
        )
        style.addSource(source)
        hazardSource = source

        // 3) Cluster bubble (CircleLayer) — only features carrying point_count.
        val clusterColor = m3("primary")
        val clusterCircle = CircleLayer(CLUSTER_CIRCLE_LAYER_ID, HAZARD_SOURCE_ID).apply {
            setFilter(Expression.has(POINT_COUNT))
            setProperties(
                PropertyFactory.circleColor(clusterColor),
                // Bubble grows in steps with the cluster size.
                PropertyFactory.circleRadius(
                    Expression.step(
                        Expression.toNumber(Expression.get(POINT_COUNT)),
                        Expression.literal(16f),
                        Expression.stop(10, 20f),
                        Expression.stop(50, 26f)
                    )
                ),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#ffffff")
            )
        }
        style.addLayer(clusterCircle)

        // 4) Cluster count text on top of the bubble.
        val onPrimary = m3("on_primary")
        val clusterCount = SymbolLayer(CLUSTER_COUNT_LAYER_ID, HAZARD_SOURCE_ID).apply {
            setFilter(Expression.has(POINT_COUNT))
            setProperties(
                PropertyFactory.textField(Expression.get("point_count_abbreviated")),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor(onPrimary),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAllowOverlap(true)
            )
        }
        style.addLayer(clusterCount)

        // 5) Un-clustered hazard markers — the data-driven SOTA core.
        val hazardSymbols = SymbolLayer(HAZARD_SYMBOL_LAYER_ID, HAZARD_SOURCE_ID).apply {
            setFilter(Expression.not(Expression.has(POINT_COUNT)))
            setProperties(
                // icon-image = match(type): 0->breaker, 1->pothole, 3->rough, default->unknown
                PropertyFactory.iconImage(
                    Expression.match(
                        Expression.toNumber(Expression.get(PROP_TYPE)),
                        Expression.literal(0L), Expression.literal(ICON_BREAKER),
                        Expression.literal(1L), Expression.literal(ICON_POTHOLE),
                        Expression.literal(3L), Expression.literal(ICON_ROUGH),
                        Expression.literal(ICON_UNKNOWN) // default (incl. type 2 = UNKNOWN)
                    )
                ),
                // Pin tip marks the location — anchor at the bottom of the icon.
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                // icon-size scales with severity (1..3). Pins are pre-rendered
                // large/crisp, so the on-map scale is modest.
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.toNumber(Expression.get(PROP_SEVERITY)),
                        Expression.stop(1, 0.5f),
                        Expression.stop(3, 0.78f)
                    )
                ),
                // icon-opacity by status: candidate(0) dimmed, local/cloud solid.
                PropertyFactory.iconOpacity(
                    Expression.match(
                        Expression.toNumber(Expression.get(PROP_STATUS)),
                        Expression.literal(0L), Expression.literal(0.6f),
                        Expression.literal(1.0f) // default (status 1 local / 2 cloud)
                    )
                ),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        }
        style.addLayer(hazardSymbols)
        hazardSymbolLayer = hazardSymbols
        // Apply the persisted hazard-filter mode to the fresh layer.
        applyHazardFilter(hazardFilterMode)

        // 5b) POI (EV charging / fuel) markers along the route — created empty,
        //     populated when a route is chosen + the POI toggle is on. Drawn above
        //     the route lines but below hazards.
        registerPoiIcons(style)
        style.addSource(GeoJsonSource(POI_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            SymbolLayer(POI_LAYER_ID, POI_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(
                    Expression.match(
                        Expression.get(POI_PROP_KIND),
                        Expression.literal("charging"), Expression.literal(ICON_POI_CHARGING),
                        Expression.literal(ICON_POI_FUEL) // default (fuel)
                    )
                ),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconSize(0.6f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
        poiSource = style.getSourceAs(POI_SOURCE_ID)

        // 5c) Itinerary markers (destination pin + numbered stop pins) — drawn LAST
        //     (top of the stack) so the trip endpoints stay legible over the route
        //     line, hazards and POIs. iconImage is data-driven from each feature's
        //     "img" property (rs_dest / rs_stop_<n>), bitmaps registered on demand.
        style.addSource(GeoJsonSource(MARKER_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            SymbolLayer(MARKER_LAYER_ID, MARKER_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(Expression.get(MARKER_PROP_IMG)),
                // Pin tip marks the exact coordinate.
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconSize(0.82f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
        markerSource = style.getSourceAs(MARKER_SOURCE_ID)

        // 5c-bis) Dropped-pin marker — a single transient pin shown where the user
        //         long-presses the map (the "save a place" affordance). Its own
        //         source/layer so it's independent of the itinerary markers and can
        //         be cleared the moment the place sheet is dismissed. Drawn on top.
        if (style.getImage(ICON_DROPPED_PIN) == null) {
            // Brand primary (same source as the route line / cluster bubble), theme-
            // aware so it stays legible on the dark recolored basemap too. A style
            // reload (theme switch) wipes registered images, so onStyleLoaded re-runs
            // this with the fresh themed color.
            style.addImage(ICON_DROPPED_PIN, buildDestinationPinPlain(m3("primary")))
        }
        style.addSource(GeoJsonSource(DROPPED_PIN_SOURCE_ID, EMPTY_FEATURE_COLLECTION))
        style.addLayer(
            SymbolLayer(DROPPED_PIN_LAYER_ID, DROPPED_PIN_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(ICON_DROPPED_PIN),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconSize(0.82f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
        droppedPinSource = style.getSourceAs(DROPPED_PIN_SOURCE_ID)

        // 5d) 3D buildings: add a fill-extrusion layer over the openmaptiles vector
        //     source (present in both the light + dark basemaps; only liberty ships
        //     its own extrusion, so we add ours either way) and set its visibility
        //     from the persisted 2D/3D choice. Drawn UNDER our route/markers (added
        //     before nothing here — added last among basemap layers, but symbol
        //     layers above still composite on top since they were added earlier with
        //     their own draw order). Default 2D (hidden) — opt-in, GPU-heavier.
        setup3dBuildings(style)

        // 5e) Language-aware basemap labels: rewrite every label layer's text-field
        //     to prefer the user's language name (name:<lang>) and fall back to the
        //     local/default name. So "München"/"慕尼黑" etc. render to match the app.
        localizeMapLabels(style)

        // 5f) Recolor the basemap to the Overdrive M3 palette (day/night) so the map
        //     matches the app shell instead of the stock OpenFreeMap colors. One layer
        //     pass, no per-frame cost; re-runs here on every theme switch (setStyle →
        //     onStyleLoaded). Done AFTER localizeMapLabels (same kind of one-time walk)
        //     and BEFORE the overlay layers below so it never touches our route/puck.
        recolorBasemapForTheme(style)

        // 6+7) Tap-to-verify (query the hazard symbol layer at the tap point) and
        //      refetch on every camera-idle (debounced inside). Wired ONCE — a style
        //      reload re-runs onStyleLoaded but the map listeners persist, so a guard
        //      stops a duplicate registration firing each callback twice.
        if (!listenersWired) {
            map?.addOnMapClickListener { latLng -> onMapTap(latLng) }
            map?.addOnCameraIdleListener { scheduleRefetch() }
            // Long-press to drop a pin → place-action sheet (Navigate / Save). Touch
            // only — never on the glanceable, non-touch cluster.
            if (!clusterMode) {
                map?.addOnMapLongClickListener { latLng -> onMapLongPress(latLng) }
            }
            listenersWired = true
        }

        // 8) First viewport fetch.
        fetchViewportHazards()

        // 9) Configure the offline ambient tile cache once, then auto-center on
        //    the live GPS fix the first time the map opens (so it lands on the
        //    user, not the default region). Subsequent recenters are manual (FAB).
        MapTilePrefetcher.configureAmbientCache(applicationContext)
        if (!didInitialRecenter) {
            didInitialRecenter = true
            recenter()
        }
        // Keep the head-unit puck live while idle (not navigating): start the idle
        // follow so the blue dot tracks the live fix on the interactive map, not just
        // on a manual Locate tap. No-op while navigating or on the cluster (those own
        // the puck via their own loops). Idempotent (self-dedupes). onResume restarts
        // it after a background trip; onStop suspends it.
        if (!clusterMode && !navigating) startIdleFollow()

        // 10) Cluster mirror: subscribe to the shared NavSession so a route the
        //     user sets on the infotainment map renders here in real time (the
        //     listener replays the current state immediately on add, so a cluster
        //     launched mid-trip picks up the in-progress route). Route layers exist
        //     now (added above), so it's safe to render on the callback.
        if (clusterMode) subscribeClusterToNavSession()

        // 11) Restore any persisted trip (head-unit only) so re-entering the map
        //     comes back to the in-progress itinerary instead of an empty search.
        //     Guarded to run once per launch (a theme-driven style reload re-enters
        //     onStyleLoaded but must not re-restore on top of the live trip).
        if (!clusterMode) restoreTripIfAny()
        } catch (t: Throwable) {
            // Never let a style-setup failure kill the process — the map degrades to the
            // basemap (which already loaded) instead of crashing the whole app.
            android.util.Log.e("RoadSenseMap", "onStyleLoaded failed", t)
        }
    }

    /**
     * Cluster-only: render whatever the infotainment instance publishes to
     * [NavSession]. On a route → draw the line + start the heading-up guidance
     * follow; on clear → wipe the line and fall back to the idle GPS follow.
     */
    private fun subscribeClusterToNavSession() {
        // Subscribe exactly once per Activity instance. This is called from
        // onStyleLoaded, which RE-RUNS on every basemap reload — including each cluster
        // day/night theme flip mid-trip (registerClusterThemeWatch → reloadStyleForTheme
        // → onStyleLoaded). Without this guard each flip would add ANOTHER NavSession
        // listener (only the newest tracked in navSessionListener), leaking destroyed-
        // Activity closures into the process-wide NavSession singleton and dispatching
        // every route update N× over a long drive. Drop any prior registration first.
        navSessionListener?.let { NavSession.removeListener(it) }
        navSessionListener = NavSession.addListener { st ->
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val route = st.route
                if (route != null) {
                    renderRoute(route)
                    if (st.navigating && !navigating) {
                        // Drive the same guidance follow as the infotainment map,
                        // but WITHOUT re-publishing (clusterMode guards the publish).
                        guidance.start(route)
                        navigating = true
                        clusterNavRoute = route
                        resetRouteTrim()   // new route → start un-trimmed
                        // Reveal the turn-by-turn banner on the cluster. The cluster
                        // never runs startGuidance() (that's the head-unit-only entry
                        // point), so without this the banner stays at its XML default
                        // (gone) and every maneuver text update from tickGuidance lands
                        // on an invisible card — the "no turn-by-turn on the cluster"
                        // bug. Seed it with the trip total so it isn't blank for the
                        // ~1s until the first guidance tick fills in the next maneuver.
                        showManeuverBanner(
                            getString(R.string.roadsense_map_nav_started, st.destLabel), route)
                        findViewById<View>(R.id.navBanner)?.visibility = View.VISIBLE
                        // Snap into the immersive follow view now (same reason as
                        // startGuidance) — renderRoute above left the cluster at the
                        // route-overview framing; without this the stationary cluster
                        // would stay zoomed-out until the car moves >3m.
                        moveCameraToImmersiveStart(route)
                        mainHandler.removeCallbacks(guidanceRunnable)
                        mainHandler.post(guidanceRunnable)
                    } else if (st.navigating && navigating && route !== clusterNavRoute) {
                        // ROUTE CHANGED mid-nav (head-unit rerouted off-route, or the
                        // driver tapped an alternate). Re-arm the cluster's own
                        // guidance engine on the NEW route so its turn-by-turn +
                        // off-route math track the new path instead of the stale one.
                        // (renderRoute above already redrew the line.) The shared
                        // guidanceRunnable keeps ticking — no re-post needed.
                        clusterNavRoute = route
                        guidance.start(route)
                        resetRouteTrim()   // rerouted → reset trim against the NEW arc length
                        lastSpokenInstruction = null
                        // Reseed the banner to the NEW route immediately + drop the old
                        // route's turn glyph, so the prior route's maneuver text/ETA/icon
                        // don't linger for the ~1s until the next guidance tick repaints.
                        showManeuverBanner(
                            getString(R.string.roadsense_map_nav_started, st.destLabel), route)
                        setManeuverIcon(0)   // 0 → no glyph; next tick sets the real one
                        // renderRoute above re-framed to the whole-route OVERVIEW. Snap
                        // back into immersive follow (mirrors the nav-start branch) —
                        // otherwise a route switch while PARKED stays zoomed-out until
                        // the car moves >CAM_DEADBAND_M (the per-frame follow's dead-band
                        // short-circuits the camera write at a standstill).
                        moveCameraToImmersiveStart(route)
                    } else if (!st.navigating && navigating) {
                        // Defensive: a (route!=null, navigating=false) PREVIEW state.
                        // No live caller produces it today (NavSession.publishPreview has
                        // no callers — the head-unit only publishRoute/clear), so this is
                        // unreachable in the current build; kept correct in case preview-
                        // mirroring is wired later. Retire the banner + glyph.
                        navigating = false
                        clusterNavRoute = null
                        mainHandler.removeCallbacks(guidanceRunnable)
                        guidance.stop()
                        findViewById<View>(R.id.navBanner)?.visibility = View.GONE
                        findViewById<View>(R.id.navBannerIcon)?.visibility = View.GONE
                    }
                } else {
                    // Trip cleared on infotainment → wipe + resume idle follow.
                    navigating = false
                    clusterNavRoute = null
                    mainHandler.removeCallbacks(guidanceRunnable)
                    guidance.stop()
                    resetRouteTrim()   // route gone → reset trim (source emptied below)
                    findViewById<View>(R.id.navBanner)?.visibility = View.GONE
                    findViewById<View>(R.id.navBannerIcon)?.visibility = View.GONE
                    routeSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
                    altRouteSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
                    clearItineraryMarkers()
                    startClusterFollow()
                }
            }
        }
    }

    /**
     * Register the four hazard markers as proper MAP PINS (not flat tinted
     * glyphs). Each is a composited teardrop: a soft drop shadow, a filled
     * type-colored body, a white circular glyph well, and the hazard symbol
     * drawn in the body color inside the well. This reads as a real map marker
     * at any zoom and encodes hazard type by color + glyph at a glance. The
     * SymbolLayer anchors these at "bottom" so the pin tip marks the location.
     */
    private fun registerHazardIcons(style: Style) {
        // Type colors — distinct, high-contrast, theme-stable (markers sit on
        // both light and dark basemaps so we use saturated fixed hues, not
        // theme-attr surface colors which would vanish on one theme).
        val potholeColor = 0xFFE53935.toInt()  // red   — pothole (most severe-looking)
        val breakerColor = 0xFFFB8C00.toInt()  // amber — speed breaker
        val roughColor   = 0xFF8E24AA.toInt()  // purple— rough section
        val unknownColor = 0xFF546E7A.toInt()  // slate — unknown

        // Idempotent: only build + register a bitmap the style doesn't already have,
        // so a future style reload doesn't needlessly re-rasterize each pin.
        if (style.getImage(ICON_POTHOLE) == null)
            style.addImage(ICON_POTHOLE, buildHazardPin(R.drawable.ic_hazard_pothole, potholeColor))
        if (style.getImage(ICON_BREAKER) == null)
            style.addImage(ICON_BREAKER, buildHazardPin(R.drawable.ic_hazard_breaker, breakerColor))
        if (style.getImage(ICON_ROUGH) == null)
            style.addImage(ICON_ROUGH, buildHazardPin(R.drawable.ic_hazard_rough, roughColor))
        if (style.getImage(ICON_UNKNOWN) == null)
            style.addImage(ICON_UNKNOWN, buildHazardPin(R.drawable.ic_hazard_unknown, unknownColor))
    }

    /**
     * Compose one SOTA map pin bitmap: teardrop body in [bodyColor] with a
     * drop shadow, a white inner disc, and the [glyphRes] hazard symbol tinted
     * [bodyColor] centered in the disc. Drawn at [PIN_PX] so it stays crisp on
     * the hi-dpi head-unit panel. The geometry leaves transparent margin for
     * the shadow so SymbolLayer's "bottom" anchor lands on the pin tip.
     */
    private fun buildHazardPin(glyphRes: Int, bodyColor: Int): Bitmap {
        val w = PIN_PX
        val h = (PIN_PX * 1.3f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val bodyR = w * 0.34f           // circular head radius
        val headCy = bodyR + w * 0.06f  // head center y (room for shadow at tip)
        val tipY = h - w * 0.06f        // teardrop tip near the bottom

        // NO blur drop-shadow: BlurMaskFilter renders as a hard dark RECTANGLE on
        // this head unit (HW-layer blur unsupported / clipped to the bitmap bounds),
        // which looks bad on the light basemap. The white rim below gives enough
        // separation on both light + dark basemaps.

        // Pin body (teardrop) + a thin white rim for separation.
        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), body)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = w * 0.035f
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), rim)

        // White inner disc (the glyph well).
        val discR = bodyR * 0.62f
        val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, headCy, discR, disc)

        // Hazard glyph, tinted in the body color, centered in the disc. Use a
        // SRC_IN color filter (not DrawableCompat.setTint) so it deterministically
        // overrides the vector's own baked android:tint across API levels.
        // Null-safe (was `!!`): if the vector fails to resolve under a freshly-applied
        // night/day Configuration (resource-lookup race on a theme reload), return the
        // pin body WITHOUT the glyph instead of throwing a KotlinNullPointerException
        // that would propagate out of onStyleLoaded → addImage → process crash.
        val glyph = ContextCompat.getDrawable(this, glyphRes)?.mutate() ?: return bmp
        glyph.colorFilter = android.graphics.PorterDuffColorFilter(
            bodyColor, android.graphics.PorterDuff.Mode.SRC_IN
        )
        val g = (discR * 1.7f).toInt()
        val gl = (cx - g / 2f).toInt()
        val gt = (headCy - g / 2f).toInt()
        glyph.setBounds(gl, gt, gl + g, gt + g)
        glyph.draw(c)
        return bmp
    }

    /** Build a teardrop/pin path: a circle head at (cx,cy) r=[r] tapering to [tipY]. */
    private fun teardropPath(cx: Float, cy: Float, r: Float, tipY: Float): android.graphics.Path {
        val p = android.graphics.Path()
        // Start at the tip, sweep up around the head, back to the tip.
        val k = r * 0.55f
        p.moveTo(cx, tipY)
        p.cubicTo(cx - k, cy + r * 0.9f, cx - r, cy + r * 0.5f, cx - r, cy)
        p.arcTo(cx - r, cy - r, cx + r, cy + r, 180f, 180f, false)
        p.cubicTo(cx + r, cy + r * 0.5f, cx + k, cy + r * 0.9f, cx, tipY)
        p.close()
        return p
    }

    // ---------------------------------------------------------------------
    // Viewport-driven hazard fetch
    // ---------------------------------------------------------------------

    private fun scheduleRefetch() {
        mainHandler.removeCallbacks(refetchRunnable)
        mainHandler.postDelayed(refetchRunnable, REFETCH_DEBOUNCE_MS)
    }

    /**
     * Read the current visible bounds, fetch the hazards for that bbox on the
     * IO executor, and post the GeoJSON back to the source on the main thread.
     * A monotonic [fetchToken] discards a stale result if the camera moved
     * again before this one returned. Never blocks the UI thread; failures
     * keep the last good data.
     */
    private fun fetchViewportHazards() {
        val mlMap = map ?: return
        val bounds: LatLngBounds = mlMap.projection.visibleRegion.latLngBounds
        val minLng = bounds.getLonWest()
        val minLat = bounds.getLatSouth()
        val maxLng = bounds.getLonEast()
        val maxLat = bounds.getLatNorth()

        val token = ++fetchToken
        ioExecutor().execute {
            val geoJson = RoadSenseHazardApiClient.fetchHazardsGeoJson(minLng, minLat, maxLng, maxLat)
            if (geoJson == null) return@execute // keep last good data on failure
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (token != fetchToken) return@post // superseded by a newer fetch
                hazardSource?.setGeoJson(geoJson)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Tap-to-confirm / delete
    // ---------------------------------------------------------------------

    private fun onMapTap(latLng: LatLng): Boolean {
        val mlMap = map ?: return false
        val point: PointF = mlMap.projection.toScreenLocation(latLng)

        // Tapping the map dismisses the search dropdown + drops keyboard focus.
        if (findViewById<View>(R.id.navSearchDropdown)?.visibility == View.VISIBLE) {
            hideSearchDropdown()
            findViewById<android.widget.EditText>(R.id.navSearchInput)?.let {
                it.clearFocus(); hideKeyboard(it)
            }
        }
        // Abandon any armed "search to save" intent UNCONDITIONALLY (not just when the
        // dropdown is visible). After an empty-autocomplete hide the dropdown is GONE
        // but the arm survives by design; a map tap is a clear back-out, so without
        // this a later normal search-pick would silently save instead of navigating.
        // (Mirrors the unconditional disarm in showPlaceSheet.)
        pendingSaveKind = null

        // Hit-test PRECEDENCE matters: hazards + POIs sit ON TOP of the route line,
        // so the precise point markers MUST be tested BEFORE the route line —
        // otherwise tapping a hazard also hits the route box and wrongly "switches
        // route". Order: hazard → POI → route line (the big fallback target).
        val slop = TAP_SLOP_PX * resources.displayMetrics.density
        val box = android.graphics.RectF(
            point.x - slop, point.y - slop, point.x + slop, point.y + slop
        )

        // 1) Hazard markers (query a tolerance box too, so small pins are tappable).
        val hazardHit = mlMap.queryRenderedFeatures(box, HAZARD_SYMBOL_LAYER_ID)
            .firstOrNull { it.hasProperty(PROP_ID) }
        if (hazardHit != null) {
            val id = hazardHit.getStringProperty(PROP_ID) ?: return false
            val type = hazardHit.getNumberProperty(PROP_TYPE)?.toInt() ?: 2
            val severity = (hazardHit.getNumberProperty(PROP_SEVERITY)?.toInt() ?: 2).coerceIn(1, 3)
            val confidence = hazardHit.getNumberProperty(PROP_CONFIDENCE)?.toDouble() ?: 0.0
            val status = hazardHit.getNumberProperty(PROP_STATUS)?.toInt() ?: 0
            val observations = hazardHit.getNumberProperty(PROP_OBSERVATIONS)?.toInt() ?: 0
            showHazardSheet(id, type, severity, confidence, status, observations)
            return true
        }

        // 2) POI marker (charging / fuel) → add/remove-stop sheet.
        val poiHit = mlMap.queryRenderedFeatures(box, POI_LAYER_ID)
            .firstOrNull { it.hasProperty(POI_PROP_LAT) }
        if (poiHit != null) {
            val kind = poiHit.getStringProperty(POI_PROP_KIND) ?: "fuel"
            val name = poiHit.getStringProperty(POI_PROP_NAME).orEmpty()
            // Null-safe deref: MapLibre's click-listener dispatch has no try/catch, so a
            // malformed POI feature missing lat/lng would crash the app on tap. The
            // hasProperty(LAT) filter above normally guarantees presence; this is belt-
            // and-suspenders against a bad feature.
            val lat = poiHit.getNumberProperty(POI_PROP_LAT)?.toDouble() ?: return false
            val lng = poiHit.getNumberProperty(POI_PROP_LNG)?.toDouble() ?: return false
            showPoiSheet(kind, name, lat, lng)
            return true
        }

        // 3) Route line LAST — only when no marker was hit. Tapping an alternate's
        //    line selects it (preview) / switches the active route (navigating).
        if (previewRoutes.isNotEmpty()) {
            val altHit = mlMap.queryRenderedFeatures(box, ALT_ROUTE_LAYER_ID)
                .firstOrNull { it.hasProperty("idx") }
            if (altHit != null) {
                val idx = altHit.getNumberProperty("idx").toInt()
                if (navigating) switchToRouteDuringNav(idx) else onRouteOptionSelected(idx)
                return true
            }
        }
        return false
    }

    private fun showHazardSheet(
        id: String,
        type: Int,
        severity: Int,
        confidence: Double,
        status: Int,
        observations: Int
    ) {
        val view = layoutInflater.inflate(R.layout.sheet_roadsense_hazard, null)
        val dialog = BottomSheetDialog(this, R.style.Theme_Overdrive_M3_BottomSheet).apply {
            setContentView(view)
            setCancelable(true)
        }
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            BottomSheetBehavior.from(sheet).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        // Header
        view.findViewById<ImageView>(R.id.ivHazardIcon).setImageResource(iconForType(type))
        view.findViewById<TextView>(R.id.tvHazardType).setText(typeLabelRes(type))

        val statusChip = view.findViewById<Chip>(R.id.chipStatus)
        statusChip.setText(statusLabelRes(status))

        val confidencePct = (confidence * 100.0).toInt().coerceIn(0, 100)
        val reportsText = resources.getQuantityString(
            R.plurals.roadsense_map_reports, observations, observations
        )
        view.findViewById<TextView>(R.id.tvHazardMeta).text = getString(
            R.string.roadsense_map_meta_format,
            getString(severityLabelRes(severity)),
            confidencePct,
            reportsText
        )

        // Pre-check the correction chips at the hazard's current values.
        val sevGroup = view.findViewById<ChipGroup>(R.id.chipGroupSeverity)
        sevGroup.check(
            when (severity) {
                1 -> R.id.chipSevMinor
                3 -> R.id.chipSevSevere
                else -> R.id.chipSevModerate
            }
        )
        val typeGroup = view.findViewById<ChipGroup>(R.id.chipGroupType)
        typeGroup.check(
            when (type) {
                0 -> R.id.chipTypeBreaker
                1 -> R.id.chipTypePothole
                3 -> R.id.chipTypeRough
                else -> R.id.chipTypeUnknown
            }
        )

        view.findViewById<MaterialButton>(R.id.btnHazardConfirm).setOnClickListener {
            val newSeverity = severityFromChip(sevGroup.checkedChipId)
            val newType = typeFromChip(typeGroup.checkedChipId)
            // Only send a correction when the user actually changed a value.
            val sevArg = if (newSeverity != null && newSeverity != severity) newSeverity else null
            val typeArg = if (newType != null && newType != type) newType else null
            dialog.dismiss()
            submitVerdict(id, confirm = true, severity = sevArg, type = typeArg)
        }

        view.findViewById<MaterialButton>(R.id.btnHazardDelete).setOnClickListener {
            // Brief fade on the sheet content before dismiss for a polished delete.
            view.animate().alpha(0f).setDuration(140L).withEndAction {
                dialog.dismiss()
            }.start()
            submitVerdict(id, confirm = false, severity = null, type = null)
        }

        dialog.show()
    }

    /**
     * POST the verdict on the IO executor, then on success refetch the
     * viewport (so the pin updates / disappears) and toast confirmation.
     */
    private fun submitVerdict(id: String, confirm: Boolean, severity: Int?, type: Int?) {
        ioExecutor().execute {
            val ok = if (confirm) {
                RoadSenseHazardApiClient.confirmHazard(id, severity, type)
            } else {
                RoadSenseHazardApiClient.rejectHazard(id)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (ok) {
                    fetchViewportHazards()
                    val msg = if (confirm) R.string.roadsense_map_confirmed_toast
                    else R.string.roadsense_map_deleted_toast
                    showSnackbar(getString(msg))
                } else {
                    showSnackbar(getString(R.string.roadsense_map_action_failed))
                }
            }
        }
    }

    private fun showSnackbar(text: String) {
        val root = findViewById<View>(R.id.mapRoot)
        val bar = Snackbar.make(root, text, Snackbar.LENGTH_SHORT)
        // Anchor to a VISIBLE FAB so the snackbar sits above it: fabLocate is GONE
        // during immersive nav (fabEndNav is the one shown then), so picking the
        // hidden one floated the snackbar at the wrong height. Pick whatever's up,
        // else leave it unanchored (bottom of the screen).
        val anchor = when {
            navigating -> findViewById<View>(R.id.fabEndNav)?.takeIf { it.visibility == View.VISIBLE }
            else -> findViewById<View>(R.id.fabLocate)?.takeIf { it.visibility == View.VISIBLE }
        }
        if (anchor != null) bar.anchorView = anchor
        bar.show()
    }

    // ---------------------------------------------------------------------
    // Hazard visibility filter (toggle / severity)
    // ---------------------------------------------------------------------

    /** Wire the hazard-filter FAB → an M3 popup menu of visibility modes. */
    private fun setupHazardFilter() {
        hazardFilterMode = getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE)
            .getInt(KEY_HAZARD_FILTER, HAZARD_FILTER_ALL)
        findViewById<View>(R.id.fabHazardFilter)?.setOnClickListener { anchor ->
            val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
            menu.menu.add(0, HAZARD_FILTER_ALL, 0, getString(R.string.roadsense_map_hazards_all))
            menu.menu.add(0, HAZARD_FILTER_MODERATE, 1, getString(R.string.roadsense_map_hazards_moderate))
            menu.menu.add(0, HAZARD_FILTER_SEVERE, 2, getString(R.string.roadsense_map_hazards_severe))
            menu.menu.add(0, HAZARD_FILTER_HIDDEN, 3, getString(R.string.roadsense_map_hazards_hidden))
            menu.setOnMenuItemClickListener { item ->
                applyHazardFilter(item.itemId)
                getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE).edit()
                    .putInt(KEY_HAZARD_FILTER, item.itemId).apply()
                true
            }
            menu.show()
        }
    }

    /**
     * Filter the hazard SymbolLayer live by severity (no refetch) via a data-driven
     * filter expression. HIDDEN sets the layer invisible; the others gate on the
     * `severity` property (>= threshold). Cheap — just swaps the layer filter.
     */
    private fun applyHazardFilter(mode: Int) {
        hazardFilterMode = mode
        val layer = hazardSymbolLayer ?: return
        val base = Expression.not(Expression.has(POINT_COUNT)) // never the cluster aggregate
        when (mode) {
            HAZARD_FILTER_HIDDEN ->
                layer.setProperties(PropertyFactory.visibility(Property.NONE))
            else -> {
                layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                val minSeverity = when (mode) {
                    HAZARD_FILTER_SEVERE -> 3L
                    HAZARD_FILTER_MODERATE -> 2L
                    else -> 1L
                }
                layer.setFilter(
                    Expression.all(
                        base,
                        Expression.gte(Expression.toNumber(Expression.get(PROP_SEVERITY)),
                            Expression.literal(minSeverity))
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------------
    // 2D / 3D buildings
    // ---------------------------------------------------------------------

    /**
     * Wire 2D/3D buildings and apply the persisted choice.
     *
     * IMPORTANT: the light (liberty) style ALREADY SHIPS its own `building-3d`
     * fill-extrusion layer ([LIBERTY_3D_LAYER_ID]), so in 2D mode we must HIDE
     * that native layer — otherwise 3D always showed in light theme regardless of
     * the toggle. The dark style has NO extrusion layer, so for it (and any style
     * lacking one) we ADD our own [MAP_3D_LAYER_ID] over the shared openmaptiles
     * source. [applyMap3dVisibility] then drives BOTH (whichever exist) so the
     * toggle is authoritative on every theme.
     */
    private fun setup3dBuildings(style: Style) {
        map3dEnabled = getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE)
            .getBoolean(KEY_MAP_3D, false)
        // Only ADD our own extrusion layer when the style doesn't already provide
        // one (i.e. dark). If a native building-3d exists (liberty), we just toggle
        // ITS visibility — no second layer needed. Idempotent across style reloads.
        val hasNative = style.getLayer(LIBERTY_3D_LAYER_ID) != null
        if (!hasNative && style.getLayer(MAP_3D_LAYER_ID) == null) {
            val color = if (isNightTheme()) MAP_3D_COLOR_DARK else MAP_3D_COLOR_LIGHT
            val ext = FillExtrusionLayer(MAP_3D_LAYER_ID, MAP_3D_SOURCE).apply {
                sourceLayer = MAP_3D_SOURCE_LAYER
                minZoom = MAP_3D_MIN_ZOOM
                setProperties(
                    PropertyFactory.fillExtrusionColor(color),
                    PropertyFactory.fillExtrusionHeight(
                        Expression.get("render_height")),
                    PropertyFactory.fillExtrusionBase(
                        Expression.get("render_min_height")),
                    PropertyFactory.fillExtrusionOpacity(0.8f),
                    PropertyFactory.visibility(
                        if (map3dEnabled) Property.VISIBLE else Property.NONE)
                )
            }
            // Place under our route casing (added first in onStyleLoaded) so the
            // extruded volumes never occlude the route line or markers. If the
            // casing isn't present yet for any reason, fall back to a plain add.
            try {
                style.addLayerBelow(ext, ROUTE_CASING_LAYER_ID)
            } catch (_: Throwable) {
                style.addLayer(ext)
            }
        }
        // Drive visibility of whatever 3D layer(s) exist (native and/or ours).
        applyMap3dVisibility(style)
        updateMap3dFab()
    }

    // ---------------------------------------------------------------------
    // Map day/night theme override
    // ---------------------------------------------------------------------

    /**
     * Cycle the per-map theme override Auto → Light → Dark → Auto and apply it.
     * AUTO follows the Overdrive app theme; an explicit Light/Dark pins the
     * basemap to that scheme regardless of the app. The choice is persisted so it
     * survives leaving the map. Only RELOADS the basemap style when the resolved
     * day/night state actually changes (a no-op flip — e.g. Auto→Light while the
     * app is already light — just re-labels the FAB).
     */
    private fun cycleMapTheme() {
        val wasNight = isNightTheme()
        mapThemeMode = when (mapThemeMode) {
            MAP_THEME_AUTO -> MAP_THEME_LIGHT
            MAP_THEME_LIGHT -> MAP_THEME_DARK
            else -> MAP_THEME_AUTO
        }
        getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE).edit()
            .putInt(KEY_MAP_THEME, mapThemeMode).apply()
        updateMapThemeFab()
        val msg = when (mapThemeMode) {
            MAP_THEME_LIGHT -> R.string.roadsense_map_theme_light
            MAP_THEME_DARK -> R.string.roadsense_map_theme_dark
            else -> R.string.roadsense_map_theme_auto
        }
        showSnackbar(getString(msg))
        // Only pay for a full style reload when the effective scheme flipped.
        if (isNightTheme() != wasNight) reloadStyleForTheme()
    }

    /**
     * Swap the basemap to the style for the current [isNightTheme] and repaint all
     * our dynamic overlays after it loads. setStyle wipes every source/layer, so
     * onStyleLoaded re-adds the static scaffolding (route/hazard/poi/marker sources,
     * layers, icons) — but the LIVE content painted onto them (the active route
     * polyline, the location puck, the itinerary pins) must be re-drawn here or it
     * would vanish on a theme switch.
     */
    private fun reloadStyleForTheme() {
        val m = map ?: return
        // Coalesce overlapping reloads: a second setStyle() issued while the first is
        // still parsing (fast double-tap of the theme FAB, or the head-unit theme change
        // racing the cluster theme-watch in the same process) supersedes the in-flight
        // style, and the first callback would then run onStyleLoaded against a not-fully-
        // loaded style → MapLibre validateState IllegalStateException → crash. The flag
        // drops the duplicate request; the persisted KEY_MAP_THEME already holds the
        // latest mode, and styleBuilderForTheme() reads the live mode, so the single
        // in-flight reload lands on the correct scheme. (onStyleLoaded also defends with
        // its own isFullyLoaded guard — this is the structural complement.)
        if (styleReloadInFlight) return
        styleReloadInFlight = true
        // OUTER try/catch: the inner finally only resets the flag on the ASYNC callback
        // path. If styleBuilderForTheme() or setStyle() itself throws SYNCHRONOUSLY (e.g.
        // the native map peer was torn down), the callback never registers and the flag
        // would leak stuck-true — permanently blocking every future theme reload via the
        // `if (styleReloadInFlight) return` guard above. Reset it here so a synchronous
        // failure self-heals.
        try {
            m.setStyle(styleBuilderForTheme()) { style ->
                try {
                    if (isFinishing || isDestroyed) return@setStyle
                    // Re-add all static sources/layers/icons + refetch hazards.
                    onStyleLoaded(style)
                    // Repaint the live overlays the fresh style dropped.
                    if (previewRoutes.isNotEmpty()) {
                        drawRoutePreview(previewRoutes, previewSelectedIdx)
                    }
                    lastFix?.let { updateLocationPuck(it) }
                    // Repaint the dropped pin too (if a place sheet is still showing one), so
                    // it survives a theme switch like every other overlay.
                    droppedPinLatLng?.let { (lat, lng) -> showDroppedPin(lat, lng) }
                } finally {
                    styleReloadInFlight = false
                }
            }
        } catch (t: Throwable) {
            styleReloadInFlight = false
            android.util.Log.e("RoadSenseMap", "reloadStyleForTheme setStyle threw", t)
        }
    }

    /** True while a theme-driven [reloadStyleForTheme] setStyle() is parsing, so an
     *  overlapping reload request is coalesced (see reloadStyleForTheme). */
    private var styleReloadInFlight = false

    /** Reflect the active map-theme mode on the theme FAB icon (auto/sun/moon). */
    private fun updateMapThemeFab() {
        findViewById<FloatingActionButton>(R.id.fabMapTheme)?.let { fab ->
            val iconRes = when (mapThemeMode) {
                MAP_THEME_LIGHT -> R.drawable.ic_map_theme_light
                MAP_THEME_DARK -> R.drawable.ic_map_theme_dark
                else -> R.drawable.ic_map_theme_auto
            }
            fab.setImageResource(iconRes)
            // Accent the FAB when the user has pinned a scheme (non-AUTO).
            val attr = if (mapThemeMode != MAP_THEME_AUTO) androidx.appcompat.R.attr.colorPrimary
                       else com.google.android.material.R.attr.colorSurfaceContainerHighest
            fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(fab, attr)
            )
        }
    }

    /** Flip 2D⇄3D, persist the choice, and apply it live (no style reload). */
    private fun toggleMap3d() {
        map3dEnabled = !map3dEnabled
        getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE).edit()
            .putBoolean(KEY_MAP_3D, map3dEnabled).apply()
        map?.style?.let { applyMap3dVisibility(it) }
        updateMap3dFab()
        showSnackbar(getString(
            if (map3dEnabled) R.string.roadsense_map_3d_on else R.string.roadsense_map_3d_off))
    }

    /** Set visibility on EVERY 3D building layer present — the style's native
     *  `building-3d` (liberty) AND our added `roadsense-building-3d` (dark) — so the
     *  toggle is authoritative regardless of which the active basemap carries. */
    private fun applyMap3dVisibility(style: Style) {
        val vis = if (map3dEnabled) Property.VISIBLE else Property.NONE
        style.getLayer(LIBERTY_3D_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
        style.getLayer(MAP_3D_LAYER_ID)?.setProperties(PropertyFactory.visibility(vis))
    }

    /**
     * Make the basemap's place labels language-aware. OpenFreeMap vector tiles
     * carry localized name fields (`name:en`, `name:de`, `name:zh`, …) alongside
     * the local `name`. For every SymbolLayer whose text-field is the plain
     * `{name}` (or `get name`), swap in `coalesce(get name:<lang>, get name)` so a
     * label shows the user's language when the tile has it and the local name
     * otherwise. English is the tile default, so skip the rewrite for `en` (and
     * for any layer that doesn't label by `name`, e.g. house numbers) to avoid
     * needless churn. Best-effort per layer — a failure on one never aborts the
     * rest or the style load.
     */
    private fun localizeMapLabels(style: Style) {
        val lang = com.overdrive.app.navmap.nav.MapNetworking.lang
        if (lang.isEmpty() || lang == "en") return  // tiles already default to name (latin/en)
        val localized = Expression.coalesce(
            Expression.get("name:$lang"),
            Expression.get("name")
        )
        try {
            for (layer in style.layers) {
                if (layer !is SymbolLayer) continue
                val tf = layer.textField ?: continue
                // Only retarget layers that actually label by the generic name. The
                // serialized expression mentions "name" for those (e.g. {name},
                // get name, name:latin); leave number/ref/icon-only layers alone.
                val asString = tf.toString()
                if (!asString.contains("name")) continue
                if (asString.contains("ref") && !asString.contains("name")) continue
                layer.setProperties(PropertyFactory.textField(localized))
            }
        } catch (_: Throwable) {
            // Style schema variance across basemaps — never break the map over labels.
        }
    }

    /** Tint the 3D FAB to reflect the active mode (accent when 3D is on). */
    private fun updateMap3dFab() {
        findViewById<FloatingActionButton>(R.id.fabMap3d)?.let { fab ->
            val attr = if (map3dEnabled) androidx.appcompat.R.attr.colorPrimary
                       else com.google.android.material.R.attr.colorSurfaceContainerHighest
            fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(fab, attr)
            )
        }
    }

    // ---------------------------------------------------------------------
    // POI along route (free OSM / Overpass)
    // ---------------------------------------------------------------------

    /** Register the EV-charging + fuel POI marker bitmaps for the POI SymbolLayer. */
    private fun registerPoiIcons(style: Style) {
        // Idempotent: build the bitmap only when the style lacks the image (skip the
        // allocation on a style reload that already carries it).
        if (style.getImage(ICON_POI_CHARGING) == null)
            style.addImage(ICON_POI_CHARGING,
                buildPoiPin(R.drawable.ic_poi_charging, 0xFF2E7D32.toInt())) // green
        if (style.getImage(ICON_POI_FUEL) == null)
            style.addImage(ICON_POI_FUEL,
                buildPoiPin(R.drawable.ic_poi_fuel, 0xFF1565C0.toInt()))     // blue
    }

    /** Small rounded POI marker (colored disc + white glyph), reusing the pin compositor style. */
    private fun buildPoiPin(glyphRes: Int, bodyColor: Int): Bitmap {
        val s = (PIN_PX * 0.8f).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2f
        val r = s * 0.42f
        // No blur shadow (renders as a hard dark box on this HW; bad on light theme).
        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, cx, r, body)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = s * 0.04f
        }
        c.drawCircle(cx, cx, r, rim)
        // Null-safe (was `!!`): a failed glyph resolve returns the pin body without the
        // glyph rather than crashing out of onStyleLoaded → addImage. See buildHazardPin.
        val glyph = ContextCompat.getDrawable(this, glyphRes)?.mutate() ?: return bmp
        glyph.colorFilter = android.graphics.PorterDuffColorFilter(
            0xFFFFFFFF.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
        val g = (r * 1.2f).toInt()
        val off = (cx - g / 2f).toInt()
        glyph.setBounds(off, off, off + g, off + g)
        glyph.draw(c)
        return bmp
    }

    // ---------------------------------------------------------------------
    // Itinerary markers (destination pin + numbered stop pins on the map)
    // ---------------------------------------------------------------------

    /**
     * Paint the itinerary markers for [route] onto the marker source: one
     * numbered teardrop pin per intermediate via-stop (1..n) and a distinct
     * destination flag pin at the final point. Origin is the live puck, so it's
     * never marked here. [stops] is the ordered itinerary AFTER the origin
     * ([stop1, …, destination]); when empty (e.g. the cluster mirror has no
     * routeStops) the destination falls back to the route's last polyline point
     * so a pin still lands at the end. Idempotent registration of each bitmap.
     */
    private fun renderItineraryMarkers(route: NavRoute, stops: List<SearchResult>) {
        val style = map?.style ?: return
        if (route.points.isEmpty()) { markerSource?.setGeoJson(EMPTY_FEATURE_COLLECTION); return }

        // Destination color (route accent) + stop color (amber), theme-stable.
        val destColor = 0xFFE53935.toInt()   // red — the trip endpoint
        val stopColor = 0xFF1565C0.toInt()   // blue — intermediate via-stops

        // Destination pin (registered once).
        if (style.getImage(ICON_DEST) == null) {
            style.addImage(ICON_DEST, buildDestinationPin(destColor))
        }

        val features = StringBuilder("[")
        var first = true
        fun addFeature(lat: Double, lng: Double, img: String) {
            if (!first) features.append(","); first = false
            features.append(
                "{\"type\":\"Feature\",\"properties\":{\"$MARKER_PROP_IMG\":\"$img\"}," +
                    "\"geometry\":{\"type\":\"Point\",\"coordinates\":[$lng,$lat]}}"
            )
        }

        if (stops.size >= 2) {
            // Numbered via-stops (all but the last entry), then the destination.
            val lastIdx = stops.size - 1
            for (i in 0 until lastIdx) {
                val ordinal = i + 1
                val img = ICON_STOP_PREFIX + ordinal
                if (style.getImage(img) == null) {
                    style.addImage(img, buildStopPin(stopColor, ordinal))
                    if (ordinal > maxStopIconRegistered) maxStopIconRegistered = ordinal
                }
                addFeature(stops[i].lat, stops[i].lng, img)
            }
            addFeature(stops[lastIdx].lat, stops[lastIdx].lng, ICON_DEST)
        } else if (stops.size == 1) {
            addFeature(stops[0].lat, stops[0].lng, ICON_DEST)
        } else {
            // No itinerary metadata (cluster mirror) — pin the polyline's end.
            val end = route.points.last()
            addFeature(end.lat, end.lng, ICON_DEST)
        }
        features.append("]")
        markerSource?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":$features}")
    }

    /** Clear all itinerary markers. */
    private fun clearItineraryMarkers() {
        markerSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
    }

    /**
     * A SOTA destination marker: a teardrop pin (route-accent body + white rim)
     * with a checkered-flag glyph in a white inner disc — the universal "trip
     * end" affordance. Same teardrop geometry as the hazard pins so it reads as
     * part of the same marker family; tip at the bottom for "bottom" anchoring.
     */
    private fun buildDestinationPin(bodyColor: Int): Bitmap {
        val w = PIN_PX
        val h = (PIN_PX * 1.3f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val bodyR = w * 0.34f
        val headCy = bodyR + w * 0.06f
        val tipY = h - w * 0.06f

        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), body)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = w * 0.035f
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), rim)

        // White glyph well.
        val discR = bodyR * 0.62f
        val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, headCy, discR, disc)

        // Checkered flag inside the well, drawn in the body color so it reads at a
        // glance. A 3x3 checker on a short pole — compact + unmistakable.
        drawCheckeredFlag(c, cx, headCy, discR * 1.25f, bodyColor)
        return bmp
    }

    /**
     * A plain teardrop pin (body color + white rim + a small white dot in the
     * well) for the transient dropped-pin marker. Same teardrop geometry as the
     * destination/stop pins so it reads as part of the same marker family, but
     * with a neutral dot instead of a checkered flag (it's "a place", not the
     * trip end). Tip at the bottom for "bottom" anchoring.
     */
    private fun buildDestinationPinPlain(bodyColor: Int): Bitmap {
        val w = PIN_PX
        val h = (PIN_PX * 1.3f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val bodyR = w * 0.34f
        val headCy = bodyR + w * 0.06f
        val tipY = h - w * 0.06f

        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), body)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = w * 0.035f
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), rim)
        // White well + a centered body-colored dot.
        val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, headCy, bodyR * 0.62f, disc)
        val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, headCy, bodyR * 0.30f, dot)
        return bmp
    }

    /** Draw a small checkered flag centered on (cx,cy), fitting a [size] box, in [ink]. */
    private fun drawCheckeredFlag(c: Canvas, cx: Float, cy: Float, size: Float, ink: Int) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; style = android.graphics.Paint.Style.FILL
        }
        // Pole on the left.
        val poleW = size * 0.10f
        val poleLeft = cx - size * 0.42f
        val flagTop = cy - size * 0.40f
        val flagH = size * 0.52f
        c.drawRect(poleLeft, cy - size * 0.46f, poleLeft + poleW, cy + size * 0.50f, paint)
        // Checker grid (3 cols x 2 rows) to the right of the pole.
        val gridLeft = poleLeft + poleW
        val gridW = size * 0.74f
        val cols = 3; val rows = 2
        val cw = gridW / cols; val ch = flagH / rows
        // Outline the flag area faintly so the white cells read on the white disc.
        val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; style = android.graphics.Paint.Style.STROKE; strokeWidth = size * 0.04f
        }
        c.drawRect(gridLeft, flagTop, gridLeft + gridW, flagTop + flagH, outline)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if ((row + col) % 2 == 0) {
                    val l = gridLeft + col * cw
                    val t = flagTop + row * ch
                    c.drawRect(l, t, l + cw, t + ch, paint)
                }
            }
        }
    }

    /**
     * A numbered intermediate-stop pin: a teardrop body in [bodyColor] with a
     * white disc and the via-stop [ordinal] drawn in the body color. Matches the
     * destination/hazard marker family. Tip at the bottom for "bottom" anchoring.
     */
    private fun buildStopPin(bodyColor: Int, ordinal: Int): Bitmap {
        val w = PIN_PX
        val h = (PIN_PX * 1.3f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val bodyR = w * 0.34f
        val headCy = bodyR + w * 0.06f
        val tipY = h - w * 0.06f

        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor; style = android.graphics.Paint.Style.FILL
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), body)
        val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = w * 0.035f
        }
        c.drawPath(teardropPath(cx, headCy, bodyR, tipY), rim)

        val discR = bodyR * 0.62f
        val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.FILL
        }
        c.drawCircle(cx, headCy, discR, disc)

        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = bodyColor
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textSize = discR * 1.5f
        }
        // Vertically center the digit baseline in the disc.
        val fm = text.fontMetrics
        val baseline = headCy - (fm.ascent + fm.descent) / 2f
        c.drawText(ordinal.toString(), cx, baseline, text)
        return bmp
    }

    /** Toggle the POI overlay; when turning on with an active route, load POIs along it. */
    private fun togglePoi() {
        poiEnabled = !poiEnabled
        findViewById<FloatingActionButton>(R.id.fabPoi)?.let { fab ->
            // Resolve via theme attrs (not fixed _light colors) so the active/idle
            // tint is correct in both day and night.
            val attr = if (poiEnabled) androidx.appcompat.R.attr.colorPrimary
                       else com.google.android.material.R.attr.colorSurfaceContainerHighest
            fab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(fab, attr)
            )
        }
        if (!poiEnabled) { poiSource?.setGeoJson(EMPTY_FEATURE_COLLECTION); return }
        // If a route is active, search its corridor; otherwise search the current
        // map viewport so the user can browse charging/fuel near them with no route.
        val active = previewRoutes.getOrNull(previewSelectedIdx)
        if (active != null) loadPoisAlong(active.points)
        else loadPoisInViewport()
    }

    /** Load POIs across the current visible map bounds (no active route). */
    private fun loadPoisInViewport() {
        val mlMap = map ?: return
        val b = mlMap.projection.visibleRegion.latLngBounds
        // Corner points define the bbox; poisAlongRoute pads + bboxes internally.
        val corners = listOf(
            com.overdrive.app.navmap.nav.GeoPoint(b.getLatSouth(), b.getLonWest()),
            com.overdrive.app.navmap.nav.GeoPoint(b.getLatNorth(), b.getLonEast())
        )
        loadPoisAlong(corners)
    }

    /** Query OSM (Overpass) for charging+fuel near the given points' bbox; render as markers. */
    private fun loadPoisAlong(points: List<com.overdrive.app.navmap.nav.GeoPoint>) {
        showSnackbar(getString(R.string.roadsense_map_poi_loading))
        ioExecutor().execute {
            val pois = com.overdrive.app.navmap.nav.OverpassPoiClient.poisAlongRoute(
                points,
                setOf(com.overdrive.app.navmap.nav.PoiKind.CHARGING,
                    com.overdrive.app.navmap.nav.PoiKind.FUEL)
            )
            val fc = StringBuilder("[")
            pois.forEachIndexed { i, p ->
                if (i > 0) fc.append(",")
                val kind = if (p.kind == com.overdrive.app.navmap.nav.PoiKind.CHARGING) "charging" else "fuel"
                // Escape the name for JSON (quotes/backslashes) — it comes from OSM.
                val safeName = org.json.JSONObject.quote(p.name)
                fc.append("{\"type\":\"Feature\",\"properties\":{")
                fc.append("\"$POI_PROP_KIND\":\"$kind\",")
                fc.append("\"$POI_PROP_NAME\":$safeName,")
                fc.append("\"$POI_PROP_LAT\":${p.lat},\"$POI_PROP_LNG\":${p.lng}},")
                fc.append("\"geometry\":{\"type\":\"Point\",\"coordinates\":[${p.lng},${p.lat}]}}")
            }
            fc.append("]")
            mainHandler.post {
                if (isFinishing || isDestroyed || !poiEnabled) return@post
                poiSource?.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":$fc}")
                if (pois.isEmpty()) showSnackbar(getString(R.string.roadsense_map_poi_none))
            }
        }
    }

    /**
     * Tapping a charging/fuel POI opens an M3 bottom sheet with the place name +
     * type and a single primary action: ADD it as a stop, or — if it's already in
     * the itinerary — REMOVE it. Adding inserts it as an intermediate stop (before
     * the destination) and recomputes; if there's no destination yet, it becomes
     * the destination. Tapping "Navigate here" sets it as the destination outright.
     */
    private fun showPoiSheet(kind: String, name: String, lat: Double, lng: Double) {
        val title = name.ifBlank {
            getString(if (kind == "charging") R.string.roadsense_map_poi_charging_generic
                      else R.string.roadsense_map_poi_fuel_generic)
        }
        val label = title
        val existingIdx = routeStops.indexOfFirst {
            kotlin.math.abs(it.lat - lat) < 1e-5 && kotlin.math.abs(it.lng - lng) < 1e-5
        }
        val isStop = existingIdx >= 0

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(
            this, R.style.Theme_Overdrive_M3_BottomSheet
        )
        val view = layoutInflater.inflate(R.layout.sheet_poi_action, null)
        view.findViewById<TextView>(R.id.tvPoiName).text = title
        view.findViewById<TextView>(R.id.tvPoiKind).setText(
            if (kind == "charging") R.string.roadsense_map_poi_kind_charging
            else R.string.roadsense_map_poi_kind_fuel
        )
        view.findViewById<ImageView>(R.id.ivPoiIcon).setImageResource(
            if (kind == "charging") R.drawable.ic_poi_charging else R.drawable.ic_poi_fuel
        )

        val btnStop = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPoiStop)
        btnStop.setText(if (isStop) R.string.roadsense_map_remove_stop_action
                        else R.string.roadsense_map_add_stop)
        btnStop.setIconResource(if (isStop) R.drawable.ic_clear else R.drawable.ic_add)
        btnStop.setOnClickListener {
            sheet.dismiss()
            if (isStop) removePoiStop(existingIdx)
            else addPoiAsStop(SearchResult(label, lat, lng))
        }

        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPoiNavigate)
            ?.setOnClickListener {
                sheet.dismiss()
                routeToResult(SearchResult(label, lat, lng)) // explicit navigate → destination
            }

        // Hand the POI to the car's built-in navigation (factory nav via the Telenav bridge).
        val carNavAvailable = TelenavActions.isAvailable(applicationContext)
        view.findViewById<View>(R.id.poiCarNavSectionHeader).visibility =
            if (carNavAvailable) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.poiCarNavActionsRow).visibility =
            if (carNavAvailable) View.VISIBLE else View.GONE
        if (carNavAvailable) {
            view.findViewById<MaterialButton>(R.id.btnPoiCarNavNavigate)
                ?.setOnClickListener { anchor ->
                    showCarNavMenu(anchor, SearchResult(label, lat, lng), includeSave = false) {
                        sheet.dismiss()
                    }
                }
            view.findViewById<MaterialButton>(R.id.btnPoiCarNavSave)
                ?.setOnClickListener {
                    sheet.dismiss()
                    sendToCarNav(SearchResult(label, lat, lng), navigate = false)
                }
        }

        sheet.setContentView(view)
        sheet.show()
    }

    /** Insert a POI as an intermediate stop (before the destination) + recompute. */
    private fun addPoiAsStop(result: SearchResult) {
        if (routeStops.size - 1 >= MAX_STOPS) { // already at the via-stop cap
            showSnackbar(getString(R.string.roadsense_map_max_stops, MAX_STOPS)); return
        }
        if (routeStops.isEmpty()) routeStops.add(result)            // becomes destination
        else routeStops.add(routeStops.size - 1, result)           // before destination
        RecentSearchStore.add(applicationContext, result)
        recomputeItinerary()
        showSnackbar(getString(R.string.roadsense_map_stop_added))
    }

    /** Remove a POI that's currently an itinerary stop + recompute (or clear). */
    private fun removePoiStop(index: Int) {
        if (index !in routeStops.indices) return
        routeStops.removeAt(index)
        showSnackbar(getString(R.string.roadsense_map_stop_removed))
        if (routeStops.isEmpty()) clearRoutePreview() else recomputeItinerary()
    }

    // ---------------------------------------------------------------------
    // Dropped pin (long-press the map) → place-action sheet (save / navigate)
    // ---------------------------------------------------------------------

    /**
     * Map long-press → drop a pin at the pressed coordinate and open the
     * place-action sheet so the user can navigate there OR save it as Home / Work /
     * a favourite in ONE step — no need to compute a route first. The label is
     * reverse-geocoded off the UI thread (falls back to "Dropped pin"). Returns
     * true so MapLibre treats the long-press as consumed.
     */
    private fun onMapLongPress(latLng: LatLng): Boolean {
        // (showPlaceSheet dismisses the search panel + keyboard on open.)
        showDroppedPin(latLng.latitude, latLng.longitude)
        // Open with a provisional "Dropped pin" label; showPlaceSheet upgrades it
        // via reverse-geocode (reverseGeocode=true) once the name resolves.
        showPlaceSheet(
            SearchResult(getString(R.string.roadsense_map_dropped_pin),
                latLng.latitude, latLng.longitude),
            isDroppedPin = true,
            reverseGeocode = true
        )
        return true
    }

    /**
     * The shared place-action sheet (long-press the map, or long-press a search
     * result). Offers "Navigate here" + a one-tap Save row (Home / Work /
     * Favourite). [isDroppedPin] true clears the transient map pin on dismiss;
     * [reverseGeocode] true resolves a readable name for the coordinate (used by
     * the dropped-pin path, which starts with a placeholder label).
     *
     * <p>All per-sheet state ([current], [alive], the two text views) is LOCAL +
     * captured in this call's closures — never shared instance fields — so a
     * second sheet opened while this one's reverse-geocode is still in flight can
     * never have its title/result clobbered by the older callback (and the [alive]
     * guard drops a callback that lands after dismissal).
     */
    /**
     * Popup offering the three car-navigation actions for a place. Anchored on the tapped
     * control so it stays visible regardless of sheet height (the earlier bottom-of-sheet
     * buttons scrolled off the head unit's short landscape screen). [includeSave] adds the
     * favourite action for surfaces that lack a separate Save control (the route sheet's
     * header). [onChosen] runs before the action — used to dismiss the host sheet.
     */
    private fun showCarNavMenu(
        anchor: View,
        result: SearchResult?,
        includeSave: Boolean,
        onChosen: () -> Unit = {},
    ) {
        val target = result ?: return
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 0, 0, getString(R.string.carnav_menu_navigate_here))
        menu.menu.add(0, 1, 1, getString(R.string.carnav_menu_add_stop))
        if (includeSave) menu.menu.add(0, 2, 2, getString(R.string.carnav_save_favourite))
        menu.setOnMenuItemClickListener { item ->
            onChosen()
            when (item.itemId) {
                0 -> sendToCarNav(target, navigate = true, replace = true)   // fresh route
                1 -> sendToCarNav(target, navigate = true, replace = false)  // add as stop
                else -> sendToCarNav(target, navigate = false)               // save favourite
            }
            true
        }
        menu.show()
    }

    /** Send a place to the car's built-in navigation (factory nav) — navigate or save-favourite. */
    private fun sendToCarNav(result: SearchResult, navigate: Boolean, replace: Boolean = false) {
        val name = result.label
        val lat = result.lat
        val lng = result.lng
        android.widget.Toast.makeText(this, R.string.carnav_sending, android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            try {
                if (navigate) {
                    val ok = com.overdrive.app.telenav.TelenavActions.navigate(applicationContext, name, lat, lng, replace)
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this,
                            if (ok) R.string.carnav_navigate_ok else R.string.carnav_navigate_fail,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    com.overdrive.app.telenav.TelenavActions.addFavorite(applicationContext, name, lat, lng, "Normal")
                    runOnUiThread {
                        android.widget.Toast.makeText(this, R.string.carnav_saved_ok, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this, getString(R.string.carnav_failed, e.message ?: ""),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun showPlaceSheet(
        result: SearchResult,
        isDroppedPin: Boolean,
        reverseGeocode: Boolean = false
    ) {
        // Opening a place sheet is an explicit per-place context switch. Dismiss the
        // search panel + keyboard if up (so they don't linger behind/after the sheet —
        // the search-result long-press path opens this with the dropdown showing) and
        // ABANDON any armed "search to save" intent (hideSearchDropdown also clears it,
        // but disarm explicitly so the dropped-pin path, where no dropdown is up, is
        // covered too). Without this, a Save from the sheet would leave the dropdown up.
        if (findViewById<View>(R.id.navSearchDropdown)?.visibility == View.VISIBLE) {
            hideSearchDropdown()
            findViewById<android.widget.EditText>(R.id.navSearchInput)?.let {
                it.clearFocus(); hideKeyboard(it)
            }
        }
        pendingSaveKind = null
        // Only one place sheet at a time: dismiss any prior one first. Two stacked
        // dropped-pin sheets would share the single droppedPinSource, so dismissing
        // the newer would clear the pin out from under the older. Dismiss-then-open
        // keeps the visible pin matched to the visible sheet. The dismiss listener is
        // nulled so the swap doesn't run the outgoing sheet's pin-clear; therefore a
        // NON-dropped-pin incoming sheet must clear any existing pin itself (the
        // dropped-pin path repaints right after via showDroppedPin, so clearing here
        // when !isDroppedPin can't erase the new pin). Without this, a dropped-pin
        // sheet superseded by a search-result sheet would orphan the pin on the map.
        if (!isDroppedPin) clearDroppedPin()
        // Flip the outgoing sheet's liveness false BEFORE dismissing it: the dismiss
        // listener is nulled on this swap path, so its own `alive=false` never runs —
        // without this the prior sheet's in-flight reverse callback would still fire.
        openPlaceSheetAlive?.set(false)
        openPlaceSheet?.let { try { it.setOnDismissListener(null); it.dismiss() } catch (_: Throwable) {} }
        val sheet = BottomSheetDialog(this, R.style.Theme_Overdrive_M3_BottomSheet)
        openPlaceSheet = sheet
        val view = layoutInflater.inflate(R.layout.sheet_place_action, null)
        val nameView = view.findViewById<TextView>(R.id.tvPlaceName)
        val subView = view.findViewById<TextView>(R.id.tvPlaceSubtitle)

        // Per-sheet mutable backing result + liveness — local to THIS sheet. Liveness
        // is an AtomicBoolean held in a field too, so a SWAP (which nulls the dismiss
        // listener) can flip it false for the outgoing sheet (see above).
        var current = result
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        openPlaceSheetAlive = alive
        bindPlaceText(nameView, subView, result)

        view.findViewById<MaterialButton>(R.id.btnPlaceNavigate).setOnClickListener {
            sheet.dismiss()
            routeToResult(current) // explicit navigate → destination (never a save)
        }
        view.findViewById<MaterialButton>(R.id.btnPlaceSaveHome).setOnClickListener {
            sheet.dismiss()
            savePlace(SavedPlacesStore.KIND_HOME, current)
        }
        view.findViewById<MaterialButton>(R.id.btnPlaceSaveWork).setOnClickListener {
            sheet.dismiss()
            savePlace(SavedPlacesStore.KIND_WORK, current)
        }
        view.findViewById<MaterialButton>(R.id.btnPlaceSaveFav).setOnClickListener {
            sheet.dismiss()
            savePlace(SavedPlacesStore.KIND_CUSTOM, current)
        }

        // Hand the place to the car's built-in navigation (factory nav via Telenav bridge).
        val carNavAvailable = TelenavActions.isAvailable(applicationContext)
        view.findViewById<View>(R.id.carNavSectionHeader).visibility =
            if (carNavAvailable) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.carNavActionsRow).visibility =
            if (carNavAvailable) View.VISIBLE else View.GONE
        if (carNavAvailable) {
            view.findViewById<MaterialButton>(R.id.btnCarNavNavigate).setOnClickListener { anchor ->
                showCarNavMenu(anchor, current, includeSave = false) { sheet.dismiss() }
            }
            view.findViewById<MaterialButton>(R.id.btnCarNavSave).setOnClickListener {
                sheet.dismiss()
                sendToCarNav(current, navigate = false)
            }
        }

        sheet.setOnDismissListener {
            alive.set(false)
            // Only clear the shared pin/handle if THIS sheet is still the current one
            // (a dismiss-then-open swap nulls the listener on the old sheet, but guard
            // anyway so a stray dismiss can't clear a newer sheet's pin).
            if (openPlaceSheet === sheet) {
                openPlaceSheet = null
                openPlaceSheetAlive = null
            }
            if (isDroppedPin) clearDroppedPin()
        }
        sheet.setContentView(view)
        sheet.show()

        if (reverseGeocode) {
            val lat = result.lat
            val lng = result.lng
            ioExecutor().execute {
                val resolved = ForwardGeocoder.reverse(lat, lng)
                mainHandler.post {
                    // Guard on THIS sheet's liveness — a callback that lands after
                    // dismissal OR for a sheet the user already replaced (swap flips
                    // alive false) is a no-op.
                    if (isFinishing || isDestroyed || !alive.get() || resolved == null) return@post
                    current = resolved
                    bindPlaceText(nameView, subView, resolved)
                }
            }
        }
    }

    /** Bind a [SearchResult]'s label into the place-sheet title + muted subtitle,
     *  splitting on the first comma (Gmaps-style two-line treatment). */
    private fun bindPlaceText(nameView: TextView, subView: TextView, result: SearchResult) {
        nameView.text = firstSegment(result.label)
        val comma = result.label.indexOf(',')
        if (comma in 1 until result.label.length - 1) {
            subView.text = result.label.substring(comma + 1).trim()
            subView.visibility = View.VISIBLE
        } else {
            subView.visibility = View.GONE
        }
    }

    /** The active dropped-pin coordinate (lat,lng), or null when no pin is shown.
     *  Kept so a theme/style reload (which wipes all sources) can REPAINT the pin —
     *  every other overlay is repainted in reloadStyleForTheme; without this the pin
     *  alone would vanish while its place sheet is still open. */
    private var droppedPinLatLng: Pair<Double, Double>? = null

    /** Paint the transient dropped-pin marker at (lat,lng). */
    private fun showDroppedPin(lat: Double, lng: Double) {
        droppedPinLatLng = lat to lng
        droppedPinSource?.setGeoJson(
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\"," +
                "\"coordinates\":[$lng,$lat]}}"
        )
    }

    /** Clear the transient dropped-pin marker. */
    private fun clearDroppedPin() {
        droppedPinLatLng = null
        droppedPinSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
    }

    // ---------------------------------------------------------------------
    // Trip persistence (retain the itinerary across leaving + re-entering)
    // ---------------------------------------------------------------------

    /**
     * Persist the current itinerary (+ whether we're navigating) so re-entering the
     * map RESTORES the trip instead of starting from an empty search box. The origin
     * is always the live fix and the polyline is recomputed on restore, so only the
     * stops are stored. Cluster mirror never persists (it's a view-only consumer of
     * the head-unit's NavSession). Cheap SharedPreferences write; safe on main.
     */
    private fun persistTrip() {
        if (clusterMode) return
        NavTripStore.save(applicationContext, ArrayList(routeStops), navigating)
    }

    /**
     * Restore a persisted trip on first open: re-seed [routeStops] and recompute the
     * route from the LIVE origin (so it's never stale). If the saved trip was
     * actively navigating, resume turn-by-turn; otherwise re-show the route-options
     * preview. Runs once per Activity launch (guarded by [didRestoreTrip]); needs a
     * GPS fix, which it fetches itself. No-op on the cluster (it mirrors NavSession).
     */
    private fun restoreTripIfAny() {
        if (didRestoreTrip || clusterMode) return
        didRestoreTrip = true
        val trip = NavTripStore.load(applicationContext) ?: return
        if (trip.stops.isEmpty()) return
        // Need an origin to recompute. Use the last fix if we have one, else fetch.
        val resume = {
            routeStops.clear()
            routeStops.addAll(trip.stops)
            showSnackbar(getString(R.string.roadsense_map_trip_restored))
            recomputeItinerary(autoStart = trip.navigating)
        }
        if (lastFix != null) { resume(); return }
        ioExecutor().execute {
            val fix = RoadSenseHazardApiClient.fetchCurrentLocation()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (fix != null) lastFix = fix
                resume()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Navigation — destination search, route fetch/render, turn-by-turn
    // ---------------------------------------------------------------------

    /**
     * Wire the search field: type-ahead autocomplete (debounced Photon) into the
     * dropdown RecyclerView, a clear (X) button, IME-submit fallback, and the
     * leading glyph as a submit affordance.
     */
    private fun setupSearch() {
        val input = findViewById<android.widget.EditText>(R.id.navSearchInput)
        val clear = findViewById<View>(R.id.navSearchClear)
        val results = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.navSearchResults)
        results?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        results?.adapter = searchAdapter

        input?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                clear?.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                scheduleAutocomplete(q)
            }
        })
        input?.setOnEditorActionListener { v, _, _ ->
            // Submit fallback: take the first autocomplete row if present, else
            // do a full (Photon→Nominatim) search and route to the top hit.
            submitSearch(v.text?.toString().orEmpty())
            hideKeyboard(v)
            true
        }
        // Gmaps-style: focusing the empty field reveals recent destinations;
        // losing focus hides the dropdown so it doesn't linger on top of the map.
        input?.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (input.text.isNullOrEmpty()) showRecentSearches()
            } else {
                hideSearchDropdown()
            }
        }
        // Tapping the field (even when already focused) re-reveals recents/results.
        input?.setOnClickListener {
            if (input.text.isNullOrEmpty()) showRecentSearches()
        }
        findViewById<View>(R.id.navSearchButton)?.setOnClickListener {
            submitSearch(input?.text?.toString().orEmpty())
            input?.let { hideKeyboard(it) }
        }
        clear?.setOnClickListener {
            input?.setText("")
            hideSearchDropdown()
        }
    }

    /** Debounce per-keystroke autocomplete (~300ms); <3 chars hides the dropdown. */
    private fun scheduleAutocomplete(query: String) {
        pendingAutocomplete?.let { mainHandler.removeCallbacks(it) }
        val q = query.trim()
        // Typing replaces the saved-place chips with live search results.
        if (q.isNotEmpty()) hideSavedChips()
        if (q.length < AUTOCOMPLETE_MIN_CHARS) {
            // Empty field with focus → fall back to the saved/recents panel.
            if (q.isEmpty() &&
                findViewById<android.widget.EditText>(R.id.navSearchInput)?.isFocused == true) {
                showRecentSearches()
            } else {
                // Transient hide while still typing (query dipped below the min) —
                // keep any armed save-intent so a refined pick still saves.
                hideSearchDropdown(clearSaveIntent = false)
            }
            return
        }
        val r = Runnable { runAutocomplete(q) }
        pendingAutocomplete = r
        mainHandler.postDelayed(r, AUTOCOMPLETE_DEBOUNCE_MS)
    }

    /** Photon typeahead off the looper; a monotonic token discards stale results. */
    private fun runAutocomplete(query: String) {
        val focus = lastFix
        val token = ++acToken
        ioExecutor().execute {
            // Local matches (saved places + recents whose label contains the query) —
            // they need no network and make a place the user once saved permanently
            // findable by name, even if no geocoder has it. Computed INSIDE the executor
            // (runAutocomplete itself runs on the main thread): localMatches reads two
            // SharedPreferences files + parses JSON, which must not touch the UI thread.
            val local = localMatches(query)
            val remote = ForwardGeocoder.autocomplete(query, 6, focus?.lat, focus?.lng)
            // Merge local first (the user's own places rank above remote hits),
            // dropping any remote row that is the SAME place as a local one so a
            // saved pin doesn't appear twice. Dedup uses the store's shared rule.
            val merged = if (local.isEmpty()) remote else
                local + remote.filterNot { r -> local.any { RecentSearchStore.isSamePlace(it, r) } }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (token != acToken) return@post // superseded by a newer query
                // No rows for this (partial) query — a transient hide while typing,
                // NOT a back-out, so keep any armed save-intent for the refined pick.
                if (merged.isEmpty()) { hideSearchDropdown(clearSaveIntent = false); return@post }
                hideSavedChips()   // live results replace the saved-place chips
                searchAdapter.submitResults(merged)   // live results → no remove (✕)
                showSearchDropdown()
            }
        }
    }

    /**
     * Synchronous, NETWORK-FREE prefix/substring match over the user's own saved
     * places (Home / Work / favourites) + recent destinations, for surfacing
     * inside the live autocomplete dropdown. Case-insensitive label `contains`;
     * saved places first, then recents, de-duplicated by the store's same-place
     * rule (so a place that is both saved and recent shows once). Capped small so
     * local rows never crowd out remote suggestions. Never throws.
     */
    private fun localMatches(query: String): List<SearchResult> {
        val q = query.trim()
        if (q.length < AUTOCOMPLETE_MIN_CHARS) return emptyList()
        return try {
            val needle = q.lowercase()
            val saved = SavedPlacesStore.getAll(applicationContext).map { it.result }
            val recents = RecentSearchStore.getAll(applicationContext)
            val out = ArrayList<SearchResult>(4)
            for (r in saved + recents) {
                if (!r.label.lowercase().contains(needle)) continue
                if (out.any { RecentSearchStore.isSamePlace(it, r) }) continue
                out.add(r)
                if (out.size >= LOCAL_MATCH_CAP) break
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun showSearchDropdown() {
        // Only show while the search field actually has focus — guards against a
        // late autocomplete/recents result re-revealing the dropdown after the
        // user already dismissed it (tap map / clear / chose a result).
        if (findViewById<android.widget.EditText>(R.id.navSearchInput)?.isFocused != true) return
        val dd = findViewById<View>(R.id.navSearchDropdown) ?: return
        dd.animate().cancel()
        if (dd.visibility != View.VISIBLE) {
            dd.alpha = 0f
            dd.visibility = View.VISIBLE
            dd.animate().alpha(1f).setDuration(140L).start()
        }
    }

    /**
     * Hide the autocomplete dropdown. [clearSaveIntent] true (the default) also
     * cancels any armed "search to save" intent — correct for a genuine BACK-OUT
     * (focus loss, clear button, tap-map, result chosen, sheet open). The transient
     * autocomplete hides that fire DURING active typing (no rows for a partial query,
     * or the query dipped below the min length) pass FALSE: the user is still mid-
     * search to set a slot, so the intent must survive until they pick a result —
     * otherwise the refined pick would silently navigate instead of saving.
     */
    private fun hideSearchDropdown(clearSaveIntent: Boolean = true) {
        // Cancel any pending autocomplete + invalidate in-flight results so a
        // stale fetch can't re-show the dropdown after this dismissal (the token
        // bump makes already-dispatched IO fail the runAutocomplete token guard).
        pendingAutocomplete?.let { mainHandler.removeCallbacks(it) }
        pendingAutocomplete = null
        acToken++
        if (clearSaveIntent) pendingSaveKind = null
        hideSavedChips()
        val dd = findViewById<View>(R.id.navSearchDropdown) ?: return
        dd.animate().cancel()
        if (dd.visibility == View.VISIBLE) {
            dd.animate().alpha(0f).setDuration(120L).withEndAction {
                dd.visibility = View.GONE
            }.start()
        }
    }

    /**
     * A row in the autocomplete dropdown was tapped. Three modes:
     *   - EDIT (editingStopIndex >= 0): replace that itinerary entry in place.
     *   - ADD  (addingStop): insert as an intermediate stop before the destination.
     *   - DEFAULT: set the destination (clears any existing itinerary), Gmaps-style.
     * All three then recompute + re-preview the trip.
     */
    private fun onSearchResultChosen(result: SearchResult) {
        // "Search to save" mode (armed by tapping an unset Home/Work chip): a place
        // PICKED FROM THE SEARCH PANEL (typed autocomplete row, recents row, or IME
        // submit) is SAVED into that slot instead of routed to. This consume path is
        // INTENTIONALLY confined to onSearchResultChosen — explicit navigate actions
        // (set chips, place-sheet "Navigate here", POI navigate) call routeToResult()
        // directly and never consume the flag, so they can't be hijacked into a save.
        val saveKind = pendingSaveKind
        if (saveKind != null) {
            pendingSaveKind = null
            findViewById<android.widget.EditText>(R.id.navSearchInput)?.let {
                it.setText(""); hideKeyboard(it); it.clearFocus()
            }
            hideSearchDropdown()
            RecentSearchStore.add(applicationContext, result)
            savePlace(saveKind, result)
            return
        }
        routeToResult(result)
    }

    /**
     * Route to [result]: set it as the destination (or insert/replace an itinerary
     * stop when in add/edit mode), Gmaps-style, then recompute the preview. This is
     * the explicit-NAVIGATE path shared by the search default branch, the set
     * Home/Work/favourite chips, the place-action sheet, and the POI sheet. It
     * ABANDONS any armed "search to save" intent ([pendingSaveKind]) up front — an
     * explicit navigate is never a save, so a stale arm must not leak into it.
     */
    private fun routeToResult(result: SearchResult) {
        pendingSaveKind = null
        findViewById<android.widget.EditText>(R.id.navSearchInput)?.let {
            it.setText(result.label)
            hideKeyboard(it)
            it.clearFocus()
        }
        hideSearchDropdown()
        // Remember this place for the recent-searches list.
        RecentSearchStore.add(applicationContext, result)

        val editIdx = editingStopIndex
        val adding = addingStop
        // Reset the entry modes regardless of which branch we take.
        editingStopIndex = -1
        addingStop = false

        when {
            editIdx in routeStops.indices -> {
                routeStops[editIdx] = result
            }
            adding -> {
                // Insert before the destination (the last entry). If there's no
                // destination yet, this becomes the destination.
                if (routeStops.isEmpty()) {
                    routeStops.add(result)
                } else {
                    routeStops.add(routeStops.size - 1, result)
                }
            }
            else -> {
                // Default: a brand-new destination replaces the whole itinerary.
                routeStops.clear()
                routeStops.add(result)
            }
        }
        recomputeItinerary()
    }

    /**
     * Show the saved-place chips (Home / Work / custom favourites) + the recent
     * destinations when the (empty) search field gains focus — the Gmaps-style
     * "where to?" panel. The chips sit above the recents list; tapping a set chip
     * routes there, tapping an unset Home/Work chip prompts to set it. The dropdown
     * is shown if there's anything at all to offer (chips or recents).
     */
    private fun showRecentSearches() {
        buildSavedChips()
        val recents = RecentSearchStore.getAll(applicationContext)
        searchAdapter.submitRecents(recents)   // recents → each row gets a remove (✕)
        // Show even with no recents, as long as the saved-chips row has content
        // (Home/Work chips always render — set or "Set …").
        val hasChips = findViewById<ChipGroup>(R.id.navSavedChips)?.childCount ?: 0
        if (recents.isEmpty() && hasChips == 0) { hideSearchDropdown(); return }
        showSearchDropdown()
    }

    /**
     * Delete a single recent destination (the row's ✕) and refresh the panel in
     * place — the search field is still focused + empty, so re-running
     * showRecentSearches() re-renders the trimmed list (and collapses the dropdown
     * if that was the last recent and there are no saved chips). A brief snackbar
     * confirms; the removal is already persisted by the store.
     */
    private fun removeRecentSearch(result: SearchResult) {
        RecentSearchStore.remove(applicationContext, result)
        showRecentSearches()
        showSnackbar(getString(R.string.roadsense_map_recent_removed))
    }

    /**
     * Populate the saved-place chip row: Home + Work always (showing "Set Home"/
     * "Set Work" when unset), then one chip per custom favourite. The whole row is
     * revealed here and hidden whenever the user starts typing (search results
     * replace it). A long-press on a set chip offers to remove it.
     */
    private fun buildSavedChips() {
        val scroll = findViewById<View>(R.id.navSavedScroll) ?: return
        val group = findViewById<ChipGroup>(R.id.navSavedChips) ?: return
        group.removeAllViews()

        val home = SavedPlacesStore.getHome(applicationContext)
        val work = SavedPlacesStore.getWork(applicationContext)
        val customs = SavedPlacesStore.getCustom(applicationContext)

        group.addView(buildSavedChip(
            label = home?.label?.let { firstSegment(it) } ?: getString(R.string.roadsense_map_saved_set_home),
            iconRes = R.drawable.ic_saved_home,
            set = home != null,
            onTap = {
                if (home != null) routeToResult(home)   // set chip → navigate (never a save)
                else promptSaveCurrentDestination(SavedPlacesStore.KIND_HOME)
            },
            onRemove = if (home != null) { -> removeSavedPlace(SavedPlacesStore.KIND_HOME, home.label) } else null
        ))
        group.addView(buildSavedChip(
            label = work?.label?.let { firstSegment(it) } ?: getString(R.string.roadsense_map_saved_set_work),
            iconRes = R.drawable.ic_saved_work,
            set = work != null,
            onTap = {
                if (work != null) routeToResult(work)   // set chip → navigate (never a save)
                else promptSaveCurrentDestination(SavedPlacesStore.KIND_WORK)
            },
            onRemove = if (work != null) { -> removeSavedPlace(SavedPlacesStore.KIND_WORK, work.label) } else null
        ))
        for (c in customs) {
            group.addView(buildSavedChip(
                label = firstSegment(c.label),
                iconRes = R.drawable.ic_star,
                set = true,
                onTap = { routeToResult(c) },   // favourite chip → navigate (never a save)
                onRemove = { removeSavedPlace(SavedPlacesStore.KIND_CUSTOM, c.label) }
            ))
        }
        scroll.visibility = View.VISIBLE
    }

    /** Build one saved-place chip with a leading icon + tap / long-press handlers. */
    private fun buildSavedChip(
        label: String,
        iconRes: Int,
        set: Boolean,
        onTap: () -> Unit,
        onRemove: (() -> Unit)?
    ): Chip {
        val chip = Chip(this)
        chip.text = label
        chip.isCheckable = false
        chip.isClickable = true
        chip.setChipIconResource(iconRes)
        chip.isChipIconVisible = true
        chip.chipIconTint = android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(
                chip, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        // A set place reads as a filled tonal chip; an unset Home/Work as outlined.
        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(
                chip,
                if (set) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorSurfaceContainerHighest)
        )
        chip.setOnClickListener { onTap() }
        if (onRemove != null) {
            chip.setOnLongClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(label)
                    .setItems(arrayOf(getString(R.string.roadsense_map_saved_remove))) { _, _ -> onRemove() }
                    .show()
                true
            }
        }
        return chip
    }

    /** Hide the saved-place chip row (used when the user starts typing). */
    private fun hideSavedChips() {
        findViewById<View>(R.id.navSavedScroll)?.visibility = View.GONE
    }

    /** The first comma segment of a label ("Home St, City" → "Home St") for a chip. */
    private fun firstSegment(label: String): String {
        val c = label.indexOf(',')
        return if (c > 0) label.substring(0, c).trim() else label
    }

    /** Remove a saved place + refresh the chip row. */
    private fun removeSavedPlace(kind: String, label: String) {
        SavedPlacesStore.remove(applicationContext, kind, label)
        showSnackbar(getString(R.string.roadsense_map_saved_removed))
        buildSavedChips()
    }

    /**
     * Tapping an UNSET Home / Work chip. When a current destination exists, save
     * THAT into the slot immediately (one tap). Otherwise — instead of the old
     * dead-end "Choose destination" snackbar — enter "search to save" mode: arm
     * [pendingSaveKind] and surface the search field so the next chosen result is
     * saved straight into this slot (see [onSearchResultChosen]).
     */
    private fun promptSaveCurrentDestination(kind: String) {
        val dest = routeStops.lastOrNull()
        if (dest != null) {
            SavedPlacesStore.save(applicationContext, kind, dest)
            showSnackbar(getString(R.string.roadsense_map_saved_added))
            buildSavedChips()
            return
        }
        // No destination yet → search-to-save. The next picked result saves here.
        pendingSaveKind = kind
        val slotName = getString(
            if (kind == SavedPlacesStore.KIND_WORK) R.string.roadsense_map_saved_work
            else R.string.roadsense_map_saved_home
        )
        findViewById<android.widget.EditText>(R.id.navSearchInput)?.let { input ->
            input.setText("")
            input.requestFocus()
            try {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Throwable) {}
        }
        showSnackbar(getString(R.string.roadsense_map_search_to_save, slotName))
    }

    /**
     * Save [result] as a place. HOME/WORK go straight to their slot; CUSTOM prompts
     * for a label first. Invoked from the place / hazard / POI sheets' "save".
     */
    private fun savePlace(kind: String, result: SearchResult) {
        // NOTE: savePlace does NOT touch pendingSaveKind — its CONSUME SITES own the
        // clear (onSearchResultChosen's save-branch, showPlaceSheet on open). Clearing
        // here would wipe a slot the user re-armed during an in-flight submitSearch
        // (whose save path passes an explicit snapshot kind, not the live field).
        if (kind == SavedPlacesStore.KIND_CUSTOM) {
            // A pin the geocoder couldn't name carries the "Dropped pin" placeholder.
            // Pre-filling THAT as the favourite label is useless (and wouldn't be
            // findable by name later via the local-autocomplete merge), so start the
            // field EMPTY and lean on the hint to prompt a real name. A named/coordinate
            // /Plus-Code result still pre-fills its first segment as before.
            val unnamed = result.label.trim() == getString(R.string.roadsense_map_dropped_pin)
            val input = android.widget.EditText(this).apply {
                hint = getString(R.string.roadsense_map_custom_label_hint)
                if (!unnamed) setText(firstSegment(result.label))
                setSingleLine()
            }
            val pad = (16 * resources.displayMetrics.density).toInt()
            val container = android.widget.FrameLayout(this).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            val dlg = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.roadsense_map_custom_label_title)
                .setView(container)
                // Positive listener is null here; the real handler is wired in
                // setOnShowListener below so it can KEEP THE DIALOG OPEN on an empty
                // unnamed save (the default setPositiveButton lambda auto-dismisses, so
                // an abort there would close the dialog instead of letting the user type
                // a name). A named/coordinate/Plus-Code result saves + dismisses normally.
                .setPositiveButton(R.string.roadsense_map_save, null)
                .setNegativeButton(R.string.roadsense_map_cancel, null)
                .create()
            dlg.setOnShowListener {
                dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    ?.setOnClickListener {
                        val name = input.text?.toString()?.trim().orEmpty()
                        // An UNNAMED dropped pin left blank must NOT save the literal
                        // "Dropped pin" placeholder (useless + not findable by name via the
                        // autocomplete merge). Keep the dialog OPEN + refocus so the user
                        // can type a real name. A NAMED result (reverse-geocode hit /
                        // coordinate / Plus Code) cleared to empty keeps its own useful label.
                        if (name.isEmpty() && unnamed) {
                            input.error = getString(R.string.roadsense_map_custom_label_hint)
                            input.requestFocus()
                            return@setOnClickListener   // do NOT dismiss
                        }
                        val labelled = if (name.isNotEmpty())
                            result.copy(label = name) else result
                        SavedPlacesStore.save(applicationContext, kind, labelled)
                        showSnackbar(getString(R.string.roadsense_map_saved_added))
                        buildSavedChips()
                        dlg.dismiss()
                    }
            }
            dlg.show()
        } else {
            SavedPlacesStore.save(applicationContext, kind, result)
            showSnackbar(getString(R.string.roadsense_map_saved_added))
            buildSavedChips()
        }
    }

    /**
     * IME/glyph submit: full search, then treat the best hit exactly like a
     * tapped autocomplete row — so it honors add-stop / edit-stop mode (not just
     * "set destination"). [onSearchResultChosen] handles the routing + recompute.
     */
    private fun submitSearch(query: String) {
        if (query.isBlank()) return
        // CONSUME the armed "search to save" intent at SUBMIT time: this result
        // belongs to THIS submit, so it's paired with the snapshot intent the submit
        // was made under. hideSearchDropdown() clears the shared pendingSaveKind; we
        // carry the snapshot in a local and NEVER round-trip it back through the
        // field — so a slot the user re-arms mid-search is left untouched (no
        // wrong-slot save) and the snapshot can't be lost on the no-results path.
        val saveKind = pendingSaveKind
        hideSearchDropdown()
        val focus = lastFix
        showSnackbar(getString(R.string.roadsense_map_searching))
        ioExecutor().execute {
            val results = ForwardGeocoder.search(query.trim(), 1, focus?.lat, focus?.lng)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                val top = results.firstOrNull()
                if (top == null) {
                    // Distinguish a genuine zero-hit query from a TRANSPORT failure (the
                    // network/proxy couldn't reach the search hosts). Without this, a
                    // blocked host showed "No places found" — identical to a real miss —
                    // which read as "search never works". ForwardGeocoder.lastError carries
                    // the typed reason from the just-finished search.
                    val msg = when (com.overdrive.app.navmap.nav.ForwardGeocoder.lastError) {
                        com.overdrive.app.navmap.nav.ForwardGeocoder.SearchError.TIMEOUT,
                        com.overdrive.app.navmap.nav.ForwardGeocoder.SearchError.NETWORK ->
                            R.string.roadsense_map_search_network
                        else -> R.string.roadsense_map_no_results
                    }
                    showSnackbar(getString(msg))
                    // Failed submit → restore the save-intent so the user can retry
                    // without re-tapping the chip, unless they armed a fresher one.
                    if (saveKind != null && pendingSaveKind == null) pendingSaveKind = saveKind
                    return@post
                }
                if (saveKind != null) {
                    // This submit was "search to save" → save the hit into that slot
                    // directly (explicit; doesn't read the live field, so a slot the
                    // user re-armed during the search is preserved for their next pick).
                    RecentSearchStore.add(applicationContext, top)
                    savePlace(saveKind, top)
                } else {
                    // This submit was a NAVIGATE (no save armed at submit time). Route
                    // DIRECTLY via routeToResult — NOT onSearchResultChosen, which
                    // re-reads the live pendingSaveKind and would let a save the user
                    // armed mid-search hijack this navigate into a silent slot save.
                    // routeToResult clears the flag up front + honors add/edit-stop modes.
                    routeToResult(top)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Route preview (alternates) → choose → Start guidance
    // ---------------------------------------------------------------------

    /**
     * Set the chosen place as the trip DESTINATION (replacing any existing
     * itinerary, Gmaps-style) and preview. This is the default search entry
     * point (IME/submit + the autocomplete default branch route through here or
     * onSearchResultChosen). Delegates to [recomputeItinerary] once the
     * destination is the sole itinerary entry.
     */
    private fun previewRoutes(destLat: Double, destLng: Double, destLabel: String) {
        routeStops.clear()
        routeStops.add(SearchResult(destLabel, destLat, destLng))
        recomputeItinerary()
    }

    /**
     * Recompute + preview the current itinerary ([routeStops]) from the live GPS
     * origin. Picks the routing strategy by stop count:
     *   - exactly 1 entry (just a destination) → routesWithAlternates so the user
     *     still gets alternate routes for the simple case;
     *   - more than 1 entry (≥1 via-stop + destination) → routeVia through the
     *     ordered [origin, stop1, …, destination] (Valhalla returns one route, no
     *     alternates on multipoint — expected).
     * Renders the returned route(s) exactly like the single-destination flow.
     * Network runs on the IO executor; results post back guarded. Requires a GPS
     * fix (origin) and a configured routing key — both surfaced via snackbar.
     */
    /**
     * Map the routing client's TYPED last-error into a SPECIFIC user message, so a
     * failure stops masquerading as the catch-all "Could not find a route." The old
     * code only distinguished empty-key (need_key) from everything-else (route_failed),
     * so a wrong/expired BYOK key (HTTP 401) and a proxy timeout both showed the same
     * unhelpful text. Reads ValhallaRouteClient.lastError, set on the call that just
     * returned empty (single foreground routing call at a time).
     */
    private fun routeFailureMessage(): String = when (ValhallaRouteClient.lastError) {
        ValhallaRouteClient.RouteError.NO_KEY -> getString(R.string.roadsense_map_need_key)
        ValhallaRouteClient.RouteError.AUTH -> getString(R.string.roadsense_map_route_key_rejected)
        ValhallaRouteClient.RouteError.TIMEOUT,
        ValhallaRouteClient.RouteError.NETWORK -> getString(R.string.roadsense_map_route_network)
        ValhallaRouteClient.RouteError.HTTP -> {
            val code = ValhallaRouteClient.lastHttpCode
            if (code > 0) getString(R.string.roadsense_map_route_http, code)
            else getString(R.string.roadsense_map_route_failed)
        }
        // EMPTY (200 but no path) / NONE — genuinely no route between the points.
        else -> getString(R.string.roadsense_map_route_failed)
    }

    private fun recomputeItinerary(autoStart: Boolean = false) {
        // Persist the new itinerary so it survives leaving the map.
        persistTrip()
        // The itinerary view should reflect the current stops immediately even
        // while the route is being (re)computed.
        if (routeStops.isEmpty()) {
            clearRoutePreview()
            return
        }
        val origin = lastFix
        if (origin == null) {
            showSnackbar(getString(R.string.roadsense_map_no_location))
            return
        }
        val destination = routeStops.last()
        val destLat = destination.lat
        val destLng = destination.lng
        val destLabel = destination.label
        // Snapshot the itinerary for the worker so a concurrent edit can't desync.
        val stopsSnapshot = ArrayList(routeStops)
        val useVia = stopsSnapshot.size > 1

        showSnackbar(getString(R.string.roadsense_map_routing))
        ioExecutor().execute {
            val routes = if (useVia) {
                val pts = ArrayList<com.overdrive.app.navmap.nav.GeoPoint>(stopsSnapshot.size + 1)
                pts.add(com.overdrive.app.navmap.nav.GeoPoint(origin.lat, origin.lng))
                stopsSnapshot.forEach { pts.add(com.overdrive.app.navmap.nav.GeoPoint(it.lat, it.lng)) }
                ValhallaRouteClient.routeVia(pts)
            } else {
                ValhallaRouteClient.routesWithAlternates(
                    origin.lat, origin.lng, destLat, destLng, ROUTE_ALTERNATES
                )
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (routes.isEmpty()) {
                    showSnackbar(routeFailureMessage())
                    return@post
                }
                previewRoutes = routes
                previewDestLabel = destLabel
                previewSelectedIdx = 0
                // Lock the destination so off-route auto-reroute + in-nav route
                // switching always target the SAME place.
                lockedDestLat = destLat
                lockedDestLng = destLng
                drawRoutePreview(routes, 0)
                // A MID-NAV itinerary edit (add/remove/reorder a stop, or a
                // full-destination replace, while already navigating) must re-arm the
                // head-unit engine AND publish the new route to the cluster so the two
                // surfaces commit to the SAME route together — publishing without
                // re-arming let the cluster guide the new route while the head-unit kept
                // ticking the old one. But this MUST mirror switchToRouteDuringNav: it
                // silently re-arms on the new route and stays immersive (no sheet). It
                // must NOT also raise the route-options chooser, which would commit
                // engine+voice+cluster to routes[0] while inviting the driver to pick a
                // DIFFERENT route — leaving the surfaces hard-committed to an unchosen
                // route (and strandable via the sheet's Close). So the re-arm path and
                // the chooser are mutually exclusive: a navigating edit re-arms+publishes
                // (no sheet); the autoStart restore arms through startGuidance; only a
                // fresh (not-yet-navigating) preview shows the chooser.
                if (!clusterMode && navigating) {
                    // Route line already redrawn by drawRoutePreview above; staying in
                    // this branch (no showRouteOptionsSheet/frameRoutes) keeps the
                    // immersive follow view intact — matching switchToRouteDuringNav.
                    guidance.start(routes[0])
                    resetRouteTrim()
                    lastSpokenInstruction = null
                    NavSession.publishRoute(routes[0], destLabel)
                    loadHazardCountsForRoutes(routes)
                } else if (autoStart) {
                    // Restoring an active nav trip → resume turn-by-turn straight
                    // into the immersive view (no route-options sheet).
                    loadHazardCountsForRoutes(routes)
                    startGuidance(routes[0], destLabel)
                } else {
                    showRouteOptionsSheet(routes, destLabel)
                    frameRoutes(routes)
                    loadHazardCountsForRoutes(routes)
                }
            }
        }
    }

    /**
     * Count RoadSense hazards along each candidate route, by severity, and feed
     * the route-options adapter so each row shows severe/moderate/minor pills.
     * Fetches hazards once for the routes' combined bbox (the same daemon GeoJSON
     * endpoint the map uses), then assigns each hazard to a route if it lies
     * within HAZARD_CORRIDOR_M of any of that route's segments. All off-thread.
     */
    private fun loadHazardCountsForRoutes(routes: List<NavRoute>) {
        if (routes.isEmpty()) return
        // Combined bbox over all routes (small padding).
        var minLat = Double.MAX_VALUE; var minLng = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE; var maxLng = -Double.MAX_VALUE
        routes.forEach { r -> r.points.forEach { p ->
            if (p.lat < minLat) minLat = p.lat; if (p.lat > maxLat) maxLat = p.lat
            if (p.lng < minLng) minLng = p.lng; if (p.lng > maxLng) maxLng = p.lng
        } }
        if (minLat > maxLat) return
        val pad = 0.01
        ioExecutor().execute {
            val geo = RoadSenseHazardApiClient.fetchHazardsGeoJson(
                minLng - pad, minLat - pad, maxLng + pad, maxLat + pad
            ) ?: return@execute
            // Parse hazard points (lng,lat,severity) from the GeoJSON. Cap the list
            // at HAZARD_COUNT_CAP so a pathologically dense bbox can't blow up the
            // O(hazards × segments) corridor scan below — the per-route pills only
            // need a representative count, not an exhaustive one.
            data class Hz(val lat: Double, val lng: Double, val sev: Int)
            val hazards = ArrayList<Hz>()
            try {
                val feats = org.json.JSONObject(geo).optJSONArray("features") ?: org.json.JSONArray()
                var i = 0
                while (i < feats.length() && hazards.size < HAZARD_COUNT_CAP) {
                    val f = feats.optJSONObject(i)
                    i++
                    if (f == null) continue
                    val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                    val props = f.optJSONObject("properties") ?: continue
                    hazards.add(Hz(coords.optDouble(1), coords.optDouble(0), props.optInt("severity", 1)))
                }
            } catch (_: Throwable) { return@execute }
            if (hazards.isEmpty()) {
                // No RoadSense data anywhere in the area → every route is "not mapped yet".
                mainHandler.post {
                    routeOptionAdapter.setHazardCounts(routes.map {
                        NavRouteOptionAdapter.RouteHazardInfo(0, 0, 0, mapped = false)
                    })
                }
                return@execute
            }
            // Hazards exist in the area, so it IS surveyed: a route with 0 hits is
            // genuinely clear (mapped=true), not un-mapped.
            // Per-route bbox (padded by ~the corridor width in degrees) computed once,
            // so hazards far from a route skip the expensive point-to-segment scan.
            // Pure pre-reject: a hazard inside the corridor is always inside the bbox,
            // so the surviving counts are identical to scanning every hazard.
            val counts = routes.map { route ->
                var bMinLat = Double.MAX_VALUE; var bMinLng = Double.MAX_VALUE
                var bMaxLat = -Double.MAX_VALUE; var bMaxLng = -Double.MAX_VALUE
                route.points.forEach { p ->
                    if (p.lat < bMinLat) bMinLat = p.lat; if (p.lat > bMaxLat) bMaxLat = p.lat
                    if (p.lng < bMinLng) bMinLng = p.lng; if (p.lng > bMaxLng) bMaxLng = p.lng
                }
                bMinLat -= HAZARD_CORRIDOR_DEG; bMaxLat += HAZARD_CORRIDOR_DEG
                bMinLng -= HAZARD_CORRIDOR_DEG; bMaxLng += HAZARD_CORRIDOR_DEG
                var severe = 0; var moderate = 0; var minor = 0
                for (h in hazards) {
                    // Cheap bbox pre-reject before the per-segment distance loop.
                    if (h.lat < bMinLat || h.lat > bMaxLat || h.lng < bMinLng || h.lng > bMaxLng) continue
                    if (hazardNearRoute(h.lat, h.lng, route)) {
                        when (h.sev) { 3 -> severe++; 2 -> moderate++; else -> minor++ }
                    }
                }
                NavRouteOptionAdapter.RouteHazardInfo(severe, moderate, minor, mapped = true)
            }
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                routeOptionAdapter.setHazardCounts(counts)
            }
        }
    }

    /** True if (lat,lng) is within HAZARD_CORRIDOR_M of any segment of [route]. */
    private fun hazardNearRoute(lat: Double, lng: Double, route: NavRoute): Boolean {
        val pts = route.points
        for (i in 0 until pts.size - 1) {
            val d = guidance.pointToSegmentMeters(
                lat, lng, pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng
            )
            if (d <= HAZARD_CORRIDOR_M) return true
        }
        return false
    }

    /** Draw all candidate routes: alternates dimmed in one source, selected bright on top. */
    private fun drawRoutePreview(routes: List<NavRoute>, selectedIdx: Int) {
        // Alternates (everything except the selected) into the dimmed source,
        // each tagged with its original index for tap-to-select.
        val altFeatures = StringBuilder("[")
        var first = true
        routes.forEachIndexed { idx, r ->
            if (idx == selectedIdx) return@forEachIndexed
            if (!first) altFeatures.append(","); first = false
            altFeatures.append(routeFeature(r, idx))
        }
        altFeatures.append("]")
        altRouteSource?.setGeoJson(
            "{\"type\":\"FeatureCollection\",\"features\":$altFeatures}"
        )
        // Selected route into the bright (primary) source.
        routeSource?.setGeoJson(lineFeature(routes[selectedIdx]))
        // Destination + numbered stop pins for the selected route's itinerary.
        renderItineraryMarkers(routes[selectedIdx], routeStops)
    }

    /** A LineString Feature carrying its route index (for queryRenderedFeatures). */
    private fun routeFeature(route: NavRoute, idx: Int): String {
        return "{\"type\":\"Feature\",\"properties\":{\"idx\":$idx}," +
            "\"geometry\":{\"type\":\"LineString\",\"coordinates\":${coordArray(route)}}}"
    }

    /**
     * Build the line-gradient Expression that hides the TRAVELED portion of the route.
     * Transparent for line-progress in [0, p), then [fullColor] from p to the end, with a
     * tiny feather band just before p so the cut antialiases instead of being a hard pixel
     * edge. [p] is the traveled fraction in [0,1].
     *
     * Constraints honored: interpolate() REQUIRES strictly-ascending stop inputs, so p is
     * clamped to [TRIM_FEATHER, 1−ε] and the feather stop (p−feather) stays below p > 0.
     * Transparency lives in the gradient color stops (rgba alpha 0) — line-gradient cannot
     * vary alpha via lineOpacity. p=0 ⇒ the feather stop clamps to 0 and the whole line is
     * full color (no trim), which is the seed/preview state.
     */
    private fun routeGradient(p: Float, fullColor: Int): Expression {
        val full = Expression.color(fullColor)
        // p≈0 (seed / preview / pre-progress): SOLID full color, no transparent band — and
        // critically, only TWO stops (0<1) so we never emit two stops at input 0 (which a
        // feathered band at p=0 would, violating interpolate's strictly-ascending rule and
        // throwing at layer build → crash on map open).
        if (p <= TRIM_FEATHER) {
            return Expression.interpolate(
                Expression.linear(), Expression.lineProgress(),
                Expression.stop(0f, full),
                Expression.stop(1f, full)
            )
        }
        // Trim case: transparent up to (pc−feather), feather to pc, full beyond. pc in
        // (TRIM_FEATHER, 0.9999] guarantees 0 < (pc−feather) < pc < 1 — strictly ascending.
        val pc = p.coerceIn(TRIM_FEATHER, 0.9999f)
        val clear = Expression.rgba(0, 0, 0, 0)
        return Expression.interpolate(
            Expression.linear(), Expression.lineProgress(),
            Expression.stop(0f, clear),
            Expression.stop(pc - TRIM_FEATHER, clear),
            Expression.stop(pc, full),
            Expression.stop(1f, full)
        )
    }

    /**
     * Per-frame traveled-route trim: move the line-gradient boundary to the car's
     * along-route progress so the path behind the puck disappears (Waze/GMaps style). A
     * GPU PAINT-property update (setProperties(lineGradient)) — NOT setGeoJson, so it does
     * NOT re-tessellate geometry / peg the RenderThread. Driven from renderMotionFrame with
     * the SAME snapped point that paints the puck, so the boundary sits under the puck.
     *
     * Guards: only while navigating + on-route (off-route HOLDS the last boundary so the
     * traveled part can't snap to a wrong spot or re-appear); MONOTONIC (never walks
     * backward on a GPS wobble / global-rescan); paint dead-band (skip when p barely moved,
     * so a parked car emits zero gradient writes). Each map instance runs its own.
     */
    private fun applyRouteTrim(snap: NavGuidanceEngine.Snapped?, onRoute: Boolean) {
        if (!navigating || snap == null || !onRoute) return
        val arc = guidance.routeArcLengthMeters()
        if (arc <= 1.0) return
        var p = (snap.alongRouteM / arc).toFloat().coerceIn(0f, 1f)
        p = maxOf(p, lastRouteProgress)          // monotonic: traveled part never re-appears
        lastRouteProgress = p
        if (kotlin.math.abs(p - lastAppliedProgress) < TRIM_PROGRESS_EPS) return   // paint dead-band
        lastAppliedProgress = p
        routeMainLayer?.setProperties(PropertyFactory.lineGradient(routeGradient(p, routeMainColor)))
        routeCasingLayer?.setProperties(PropertyFactory.lineGradient(routeGradient(p, routeCasingColor)))
    }

    /** Reset the traveled-route trim to "full route" (p=0) and repaint both layers. Called
     *  at every new-route site (nav start / reroute / route switch) so the fresh route
     *  starts un-trimmed and a stale boundary from the prior route can't hide it. */
    private fun resetRouteTrim() {
        lastRouteProgress = 0f
        lastAppliedProgress = -1f
        routeMainLayer?.setProperties(PropertyFactory.lineGradient(routeGradient(0f, routeMainColor)))
        routeCasingLayer?.setProperties(PropertyFactory.lineGradient(routeGradient(0f, routeCasingColor)))
    }

    private fun lineFeature(route: NavRoute): String =
        "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":${coordArray(route)}}}"

    /**
     * Cache of serialized coordinate arrays keyed on the route instance. The preview
     * flow re-serializes the same routes repeatedly (select / redraw alternates), so
     * memoize per route reference. Cleared in [clearRoutePreview] when the routes are
     * discarded — the set of live routes is tiny (≤ ROUTE_ALTERNATES+1).
     */
    private val coordArrayCache = HashMap<NavRoute, String>()

    private fun coordArray(route: NavRoute): String {
        coordArrayCache[route]?.let { return it }
        val sb = StringBuilder("[")
        route.points.forEachIndexed { i, p ->
            if (i > 0) sb.append(",")
            sb.append("[").append(p.lng).append(",").append(p.lat).append("]")
        }
        val s = sb.append("]").toString()
        coordArrayCache[route] = s
        return s
    }

    private fun frameRoutes(routes: List<NavRoute>) {
        val b = LatLngBounds.Builder()
        var any = false
        routes.forEach { r -> r.points.forEach { b.include(LatLng(it.lat, it.lng)); any = true } }
        if (!any) return
        try {
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 140), RECENTER_ANIM_MS)
        } catch (_: Throwable) {}
    }

    private fun showRouteOptionsSheet(routes: List<NavRoute>, destLabel: String) {
        val sheet = findViewById<View>(R.id.routeOptionsSheet) ?: return
        val list = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.routeOptionsList)
        list?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        list?.adapter = routeOptionAdapter
        routeOptionAdapter.setRoutes(routes)

        findViewById<TextView>(R.id.tvRouteSheetTitle)?.text =
            getString(R.string.roadsense_map_routes_to, destLabel)

        findViewById<View>(R.id.btnRouteSheetClose)?.setOnClickListener { clearRoutePreview() }
        findViewById<View>(R.id.btnRouteSheetSave)?.setOnClickListener { anchor ->
            // Save the current destination as Home / Work / a custom favourite.
            val dest = routeStops.lastOrNull() ?: return@setOnClickListener
            val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
            menu.menu.add(0, 0, 0, getString(R.string.roadsense_map_save_as_home))
            menu.menu.add(0, 1, 1, getString(R.string.roadsense_map_save_as_work))
            menu.menu.add(0, 2, 2, getString(R.string.roadsense_map_save_as_custom))
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> savePlace(SavedPlacesStore.KIND_HOME, dest)
                    1 -> savePlace(SavedPlacesStore.KIND_WORK, dest)
                    else -> savePlace(SavedPlacesStore.KIND_CUSTOM, dest)
                }
                true
            }
            menu.show()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRouteStart)
            ?.setOnClickListener { startSelectedRoute() }

        // Hand the destination to the car's built-in navigation instead of OverDrive's route.
        // In the header (always visible) so it never scrolls off the short landscape screen.
        findViewById<View>(R.id.btnRouteCarNav)?.apply {
            visibility = if (TelenavActions.isAvailable(applicationContext)) View.VISIBLE else View.GONE
            if (visibility == View.VISIBLE) {
                setOnClickListener { anchor ->
                    showCarNavMenu(anchor, routeStops.lastOrNull(), includeSave = true)
                }
            }
        }

        // Build the ordered trip itinerary (origin → stops → destination + an
        // "Add stop" row) above the route candidates.
        rebuildStopsUi()

        sheet.visibility = View.VISIBLE
        if (routeSheetBehavior == null) {
            routeSheetBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
        }
        routeSheetBehavior?.apply {
            isHideable = true
            // Open EXPANDED so the Start CTA + all route rows are visible — a
            // collapsed peek hid the Start button below the fold.
            skipCollapsed = true
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }

        // Cap the scrollable middle so the whole sheet fits the short landscape
        // screen: with many route candidates the content would otherwise push the
        // sheet past the screen and the fixed header (with the car-nav icon) off the
        // top. Capping lets the middle scroll while the header + Start stay pinned.
        // Short content (a couple of routes) stays under the cap → sheet stays compact.
        findViewById<androidx.core.widget.NestedScrollView>(R.id.routeSheetScroll)?.let { scroll ->
            scroll.post {
                val cap = (resources.displayMetrics.heightPixels * 0.45f).toInt()
                if (scroll.height > cap) {
                    scroll.layoutParams = scroll.layoutParams.apply { height = cap }
                    scroll.requestLayout()
                }
            }
        }
    }

    /** A route-options row (or its map line) was selected → re-highlight + remember. */
    private fun onRouteOptionSelected(idx: Int) {
        if (idx < 0 || idx >= previewRoutes.size) return
        previewSelectedIdx = idx
        routeOptionAdapter.selectIndex(idx)
        drawRoutePreview(previewRoutes, idx)
    }

    // ---------------------------------------------------------------------
    // Stops / waypoints — itinerary UI (origin → stops → destination + add)
    // ---------------------------------------------------------------------

    /**
     * Rebuild the ordered itinerary list inside the route sheet from the current
     * [routeStops]. Renders, in order:
     *   - the ORIGIN row ("Your location", non-editable);
     *   - one row per itinerary entry: the last is the DESTINATION, earlier ones
     *     are intermediate STOPS. Each editable entry shows a remove (X) and, when
     *     there are ≥2 reorderable stops, up/down reorder controls;
     *   - an "Add stop" row (hidden once MAX_STOPS via-points are reached).
     * Rows are inflated/built in code into [R.id.routeStopsContainer]. The list is
     * tiny (≤ MAX_STOPS) so a code-populated LinearLayout is simpler than a
     * RecyclerView. Theme attrs only, so it's day/night-correct.
     */
    private fun rebuildStopsUi() {
        val container = findViewById<android.widget.LinearLayout>(R.id.routeStopsContainer) ?: return
        val divider = findViewById<View>(R.id.routeStopsDivider)
        container.removeAllViews()

        // No itinerary → hide the section entirely.
        if (routeStops.isEmpty()) {
            container.visibility = View.GONE
            divider?.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE
        divider?.visibility = View.VISIBLE

        // Origin (the live GPS fix) — always first, not part of routeStops.
        container.addView(buildOriginRow(container))

        val lastIdx = routeStops.size - 1
        // Reorder controls only make sense when there are ≥2 intermediate stops
        // (the destination is fixed as the last entry and isn't reordered).
        val stopCount = lastIdx // entries before the destination
        val reorderable = stopCount >= 2
        routeStops.forEachIndexed { idx, entry ->
            val isDestination = idx == lastIdx
            container.addView(buildStopRow(container, idx, entry, isDestination, reorderable))
        }

        // "Add stop" affordance — capped at MAX_STOPS intermediate stops (the
        // itinerary then holds MAX_STOPS stops + 1 destination).
        if (stopCount < MAX_STOPS) {
            container.addView(buildAddStopRow(container))
        }
    }

    /** Build the non-editable origin row ("Your location"). */
    private fun buildOriginRow(parent: android.view.ViewGroup): View {
        val row = layoutInflater.inflate(R.layout.item_nav_stop, parent, false)
        row.findViewById<ImageView>(R.id.ivStopIcon).setImageResource(R.drawable.ic_my_location)
        row.findViewById<TextView>(R.id.tvStopLabel).text = getString(R.string.roadsense_map_your_location)
        row.findViewById<View>(R.id.tvStopOrdinal).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopRemove).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopUp).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopDown).visibility = View.GONE
        // Origin is not interactive.
        row.isClickable = false
        row.background = null
        return row
    }

    /**
     * Build one itinerary entry row. Stops/destination are tappable to EDIT
     * (re-search + replace); each carries a remove (X), and reorderable stops
     * also get up/down controls. The destination uses a distinct label + icon.
     */
    private fun buildStopRow(
        parent: android.view.ViewGroup,
        index: Int,
        entry: SearchResult,
        isDestination: Boolean,
        reorderable: Boolean
    ): View {
        val row = layoutInflater.inflate(R.layout.item_nav_stop, parent, false)
        val icon = row.findViewById<ImageView>(R.id.ivStopIcon)
        val ordinal = row.findViewById<TextView>(R.id.tvStopOrdinal)
        val label = row.findViewById<TextView>(R.id.tvStopLabel)
        val sub = row.findViewById<TextView>(R.id.tvStopSubtitle)
        val remove = row.findViewById<ImageView>(R.id.btnStopRemove)
        val up = row.findViewById<ImageView>(R.id.btnStopUp)
        val down = row.findViewById<ImageView>(R.id.btnStopDown)

        // Split "name, rest…" into a title + muted subtitle (mirrors search rows).
        val comma = entry.label.indexOf(',')
        if (comma > 0 && comma < entry.label.length - 1) {
            label.text = entry.label.substring(0, comma).trim()
            sub.text = entry.label.substring(comma + 1).trim()
            sub.visibility = View.VISIBLE
        } else {
            label.text = entry.label
            sub.visibility = View.GONE
        }

        if (isDestination) {
            icon.setImageResource(R.drawable.ic_location_pin)
            ordinal.visibility = View.GONE
        } else {
            // Intermediate stops show their 1-based via-stop ordinal.
            icon.setImageResource(R.drawable.ic_location_pin)
            ordinal.visibility = View.VISIBLE
            ordinal.text = (index + 1).toString()
        }

        // Tap the row → edit this entry (re-search + replace in place).
        row.setOnClickListener { beginEditStop(index) }

        // Remove (X) — collapses the itinerary; if the destination is removed the
        // previous stop becomes the new destination (handled in removeStop).
        remove.visibility = View.VISIBLE
        remove.contentDescription = getString(R.string.roadsense_map_remove_stop)
        remove.setOnClickListener { removeStop(index) }

        // Reorder controls only for intermediate stops, and only when ≥2 exist.
        if (reorderable && !isDestination) {
            up.visibility = View.VISIBLE
            down.visibility = View.VISIBLE
            up.contentDescription = getString(R.string.roadsense_map_move_stop_up)
            down.contentDescription = getString(R.string.roadsense_map_move_stop_down)
            // First stop can't move up; last STOP (index == lastIdx-1) can't move down.
            val lastStopIndex = routeStops.size - 2
            up.isEnabled = index > 0
            up.alpha = if (index > 0) 1f else 0.3f
            down.isEnabled = index < lastStopIndex
            down.alpha = if (index < lastStopIndex) 1f else 0.3f
            up.setOnClickListener { if (index > 0) moveStop(index, index - 1) }
            down.setOnClickListener { if (index < lastStopIndex) moveStop(index, index + 1) }
        } else {
            up.visibility = View.GONE
            down.visibility = View.GONE
        }
        return row
    }

    /** Build the "Add stop" row that puts the search into add-stop mode. */
    private fun buildAddStopRow(parent: android.view.ViewGroup): View {
        val row = layoutInflater.inflate(R.layout.item_nav_stop, parent, false)
        row.findViewById<ImageView>(R.id.ivStopIcon).setImageResource(R.drawable.ic_add)
        row.findViewById<TextView>(R.id.tvStopLabel).text = getString(R.string.roadsense_map_add_stop)
        row.findViewById<View>(R.id.tvStopOrdinal).visibility = View.GONE
        row.findViewById<View>(R.id.tvStopSubtitle).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopRemove).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopUp).visibility = View.GONE
        row.findViewById<View>(R.id.btnStopDown).visibility = View.GONE
        row.setOnClickListener { beginAddStop() }
        return row
    }

    /**
     * Enter "adding a stop" mode: reveal + focus the search field so the next
     * chosen result is inserted as an intermediate stop. Collapses the sheet so
     * the search column is reachable above it.
     */
    private fun beginAddStop() {
        if (routeStops.size - 1 >= MAX_STOPS) { // already at the via-stop cap
            showSnackbar(getString(R.string.roadsense_map_max_stops, MAX_STOPS))
            return
        }
        addingStop = true
        editingStopIndex = -1
        promptSearchForStop()
    }

    /**
     * Enter "editing a stop" mode: the next chosen result replaces the entry at
     * [index]. Implemented as a search-and-replace (simple + functional).
     */
    private fun beginEditStop(index: Int) {
        if (index !in routeStops.indices) return
        addingStop = false
        editingStopIndex = index
        promptSearchForStop()
    }

    /** Surface the search field (drop the sheet so the search column is usable). */
    private fun promptSearchForStop() {
        // Let the sheet peek so the search bar/dropdown sit clear above it.
        routeSheetBehavior?.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
        findViewById<View>(R.id.navSearchColumn)?.visibility = View.VISIBLE
        findViewById<android.widget.EditText>(R.id.navSearchInput)?.let { input ->
            input.setText("")
            input.requestFocus()
            try {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Throwable) {}
        }
        showSnackbar(getString(R.string.roadsense_map_add_stop_hint))
    }

    /**
     * Remove the itinerary entry at [index] and recompute. Removing the LAST
     * entry (the destination) promotes the previous stop to destination; removing
     * the only entry clears the whole preview.
     */
    private fun removeStop(index: Int) {
        if (index !in routeStops.indices) return
        // Any in-flight edit/add targeting indices is now invalid.
        editingStopIndex = -1
        addingStop = false
        routeStops.removeAt(index)
        showSnackbar(getString(R.string.roadsense_map_stop_removed))
        if (routeStops.isEmpty()) {
            clearRoutePreview()
        } else {
            recomputeItinerary()
        }
    }

    /** Reorder: move the stop at [from] to [to] (both must be via-stops), recompute. */
    private fun moveStop(from: Int, to: Int) {
        val lastStopIndex = routeStops.size - 2 // destination is fixed at the end
        if (from !in 0..lastStopIndex || to !in 0..lastStopIndex) return
        if (from == to) return
        val entry = routeStops.removeAt(from)
        routeStops.add(to, entry)
        recomputeItinerary()
    }

    /**
     * Start guidance on the selected preview route. The route options SHEET is
     * dismissed, but the candidate routes + their dimmed lines are KEPT so the
     * driver can still tap an alternate mid-trip to switch (Gmaps-style).
     */
    private fun startSelectedRoute() {
        val routes = previewRoutes
        if (routes.isEmpty()) return
        val chosen = routes.getOrElse(previewSelectedIdx) { routes[0] }
        val label = previewDestLabel
        hideRouteSheet()
        // Keep alternates dimmed + tappable during nav; draw the chosen bright.
        drawRoutePreview(routes, previewSelectedIdx)
        startGuidance(chosen, label)
    }

    /** Hide just the options sheet (keeps previewRoutes for in-nav switching). */
    private fun hideRouteSheet() {
        findViewById<View>(R.id.routeOptionsSheet)?.let { sheet ->
            routeSheetBehavior?.state =
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
            sheet.visibility = View.GONE
        }
    }

    /** Fully clear the preview: routes, lines, sheet, locked destination, stops. */
    private fun clearRoutePreview(keepActive: Boolean = false) {
        previewRoutes = emptyList()
        coordArrayCache.clear()
        lockedDestLat = Double.NaN
        lockedDestLng = Double.NaN
        // Trip abandoned → drop the persisted itinerary too.
        if (!clusterMode) NavTripStore.clear(applicationContext)
        // Drop the whole itinerary + any pending add/edit mode so a fresh search
        // starts a brand-new trip.
        routeStops.clear()
        addingStop = false
        editingStopIndex = -1
        findViewById<android.widget.LinearLayout>(R.id.routeStopsContainer)?.removeAllViews()
        altRouteSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        if (!keepActive) {
            routeSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
            clearItineraryMarkers()
        }
        hideRouteSheet()
    }

    /**
     * Mid-navigation route switch: the driver tapped an alternate line. Re-arm the
     * guidance engine on the newly-selected route and redraw (selected bright,
     * others dimmed). Keeps the locked destination + immersive view.
     */
    private fun switchToRouteDuringNav(idx: Int) {
        if (idx < 0 || idx >= previewRoutes.size || idx == previewSelectedIdx) return
        previewSelectedIdx = idx
        val chosen = previewRoutes[idx]
        guidance.start(chosen)
        resetRouteTrim()   // switched to a different route → reset trim to the new line
        lastSpokenInstruction = null
        drawRoutePreview(previewRoutes, idx)
        // Mirror the driver's mid-nav route switch to the cluster too.
        if (!clusterMode) NavSession.publishRoute(chosen, previewDestLabel)
        showSnackbar(getString(R.string.roadsense_map_route_switched))
    }

    /** Paint the route polyline onto the route source + frame it on screen. */
    private fun renderRoute(route: NavRoute) {
        val coords = StringBuilder("[")
        route.points.forEachIndexed { i, p ->
            if (i > 0) coords.append(",")
            coords.append("[").append(p.lng).append(",").append(p.lat).append("]")
        }
        coords.append("]")
        val geoJson =
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\"," +
                "\"coordinates\":$coords}}"
        routeSource?.setGeoJson(geoJson)
        // Pin the destination at the route end (the cluster mirror has no
        // routeStops itinerary, so renderItineraryMarkers falls back to the
        // polyline's last point).
        renderItineraryMarkers(route, emptyList())

        // Frame the whole route with padding.
        if (route.points.size >= 2) {
            val b = LatLngBounds.Builder()
            route.points.forEach { b.include(LatLng(it.lat, it.lng)) }
            try {
                map?.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(b.build(), 120), RECENTER_ANIM_MS
                )
            } catch (_: Throwable) { /* degenerate bounds — ignore */ }
        }
    }

    /** Begin turn-by-turn: arm the engine, enter immersive view, start tick + voice. */
    private fun startGuidance(route: NavRoute, destLabel: String) {
        guidance.start(route)
        navigating = true
        resetRouteTrim()   // nav start → route fully visible, trim begins from 0
        // Guidance owns the puck now (its 1 Hz tick + 30 fps dead-reckon) — stop the
        // head-unit idle follow so the two don't both write the puck.
        stopIdleFollow()
        // Persist that we're now navigating so a re-entry resumes guidance (not just
        // the preview). The stops were already saved by recomputeItinerary.
        persistTrip()
        // The infotainment (control) instance publishes the active route so the
        // view-only cluster mirror renders it in real time. The cluster instance
        // itself never publishes (it's the consumer).
        if (!clusterMode) NavSession.publishRoute(route, destLabel)
        if (navVoice == null) navVoice = NavVoice(applicationContext)
        enterImmersive()
        // Snap the camera into the immersive follow view IMMEDIATELY — do NOT wait
        // for the first GPS tick. The route-options preview left the camera at a
        // wide route-overview framing; if the car is stationary at the origin, the
        // per-tick follow's dead-band (keyed on lat/lng/bearing, not zoom/tilt)
        // would see "barely moved" and SKIP the zoom/tilt transition, leaving the
        // map stuck at the overview until the car physically moves >3m. Force the
        // transition here from the route origin + its initial heading.
        moveCameraToImmersiveStart(route)
        showManeuverBanner(getString(R.string.roadsense_map_nav_started, destLabel), route)
        findViewById<View>(R.id.navBanner)?.visibility = View.VISIBLE
        // Drop any prior trip's turn glyph until the first guidance tick repaints the
        // real one — switching destination mid-nav reaches here without a stopGuidance
        // in between, so the old arrow would otherwise linger ~1s against the new
        // trip's text. Mirrors the cluster route-changed branch.
        setManeuverIcon(0)
        findViewById<FloatingActionButton>(R.id.fabEndNav)?.visibility = View.VISIBLE
        mainHandler.removeCallbacks(guidanceRunnable)
        mainHandler.post(guidanceRunnable)
    }

    /**
     * Immediately glide the camera into the immersive driving view (close zoom, 3D
     * tilt, heading-up) at the route's origin, so navigation doesn't appear "stuck"
     * at the overview framing while the car is still stationary. Records the target
     * as the dead-band baseline so the per-tick follow doesn't redundantly re-animate
     * to the same spot, yet still animates once the car actually moves.
     */
    private fun moveCameraToImmersiveStart(route: NavRoute) {
        val m = map ?: return
        val pts = route.points
        // Prefer the live fix (where the car actually is); fall back to the route's
        // first vertex (the origin Valhalla routed from).
        val target = lastFix?.let { LatLng(it.lat, it.lng) }
            ?: pts.firstOrNull()?.let { LatLng(it.lat, it.lng) }
            ?: return
        // Heading-up bearing: aim down the first route segment so the view faces the
        // direction of travel even before the car moves (GPS bearing is noise at 0
        // speed). Fall back to the last camera bearing for a degenerate 1-pt route.
        val bearing = if (pts.size >= 2)
            bearingBetween(pts[0].lat, pts[0].lng, pts[1].lat, pts[1].lng) else lastBearing
        lastBearing = bearing
        // Seed the bearing EMA to the start heading so the first moving tick eases
        // from here (not from a stale/0 value, which would whip the map around).
        smoothedBearing = ((bearing % 360.0) + 360.0) % 360.0
        val pos = org.maplibre.android.camera.CameraPosition.Builder()
            .target(target)
            .zoom(IMMERSIVE_ZOOM)
            .tilt(IMMERSIVE_TILT)
            .bearing(bearing)
            .build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), GUIDANCE_CAM_ANIM_MS)
        rememberAnimatedTarget(target.latitude, target.longitude, bearing)
    }

    /** Initial-bearing (forward azimuth) from (lat1,lng1) to (lat2,lng2), degrees 0..360. */
    private fun bearingBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)
        val y = kotlin.math.sin(dLng) * kotlin.math.cos(p2)
        val x = kotlin.math.cos(p1) * kotlin.math.sin(p2) -
            kotlin.math.sin(p1) * kotlin.math.cos(p2) * kotlin.math.cos(dLng)
        return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Enter the Waze/Gmaps-style immersive driving view: hide the search column,
     * toolbar, zoom + locate FABs, and the hazard-filter control so the map is
     * unobstructed; tilt the camera into a 3D perspective. The maneuver banner +
     * end-nav FAB stay. Heading-up rotation + close follow are applied per GPS
     * tick in tickGuidance().
     */
    private fun enterImmersive() {
        findViewById<View>(R.id.navSearchColumn)?.visibility = View.GONE
        findViewById<View>(R.id.mapAppBar)?.visibility = View.GONE
        findViewById<View>(R.id.zoomControls)?.visibility = View.GONE
        findViewById<View>(R.id.fabLocate)?.visibility = View.GONE
        // Hide the WHOLE top-control container (hazard-filter + POI), not just one
        // child — otherwise fabPoi stays floating in immersive nav.
        findViewById<View>(R.id.mapControlsTop)?.visibility = View.GONE
        hideSearchDropdown()
        // The toolbar (with its back chevron) is now hidden, so surface the
        // immersive back FAB in its place — but never on the non-touch cluster.
        findViewById<FloatingActionButton>(R.id.fabNavBack)?.visibility =
            if (clusterMode) View.GONE else View.VISIBLE
    }

    /** Restore the standard map chrome when navigation ends. */
    private fun exitImmersive() {
        // On the non-touch cluster, never restore touch chrome — re-apply the
        // glanceable cluster chrome and bail (e.g. after "arrived" ends guidance).
        if (clusterMode) { applyClusterChrome(); return }
        findViewById<View>(R.id.navSearchColumn)?.visibility = View.VISIBLE
        findViewById<View>(R.id.mapAppBar)?.visibility = View.VISIBLE
        findViewById<View>(R.id.zoomControls)?.visibility = View.VISIBLE
        findViewById<View>(R.id.fabLocate)?.visibility = View.VISIBLE
        findViewById<View>(R.id.mapControlsTop)?.visibility = View.VISIBLE
        // The toolbar back chevron is back — retire the immersive back FAB.
        findViewById<View>(R.id.fabNavBack)?.visibility = View.GONE
        // Level the camera back to top-down north-up.
        map?.let { m ->
            val pos = org.maplibre.android.camera.CameraPosition.Builder()
                .target(m.cameraPosition.target)
                .tilt(0.0)
                .bearing(0.0)
                .zoom(DEFAULT_ZOOM)
                .build()
            m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), RECENTER_ANIM_MS)
        }
    }

    /**
     * Gentle north-up camera pitch for the IDLE (resting / browsing) map, giving the
     * basemap a modern 3D perspective (depth on buildings + road casings) at street
     * zoom. Returns 0 (flat) on the non-touch cluster and while navigating — those
     * own the camera via their own loops (the cluster glanceable follow, and the
     * steeper heading-up [IMMERSIVE_TILT] during guidance). Only applied by
     * [recenter] at [FOLLOW_ZOOM]; the overview framings (route preview, nav-exit
     * level-off at [DEFAULT_ZOOM]) intentionally stay flat, where a tilt would distort.
     */
    private fun restingTilt(): Double =
        if (clusterMode || navigating) 0.0 else RESTING_TILT

    /**
     * True when an animateCamera to ([lat],[lng],[bearing]) is close enough to the
     * last animated target to skip (within [CAM_DEADBAND_M] and [CAM_DEADBAND_DEG]).
     * Records the new target as the last animated one only when we DON'T skip
     * (callers gate the actual animate on the negation of this).
     */
    private fun cameraWithinDeadband(lat: Double, lng: Double, bearing: Double): Boolean {
        if (lastAnimatedLat.isNaN() || lastAnimatedLng.isNaN() || lastAnimatedBearing.isNaN()) {
            return false
        }
        val moved = guidance.haversineMeters(lastAnimatedLat, lastAnimatedLng, lat, lng)
        var dBearing = kotlin.math.abs(bearing - lastAnimatedBearing) % 360.0
        if (dBearing > 180.0) dBearing = 360.0 - dBearing
        return moved < CAM_DEADBAND_M && dBearing < CAM_DEADBAND_DEG
    }

    /** Running heading-up bearing (EMA-smoothed); NaN until the first moving fix. */
    private var smoothedBearing: Double = Double.NaN
    /** elapsedRealtime of the last [smoothBearing] call, for the dt-aware alpha (the
     *  bearing low-pass is called from BOTH the ~12fps render frame and the 1Hz cluster
     *  idle follow, so the alpha must be derived from real dt to mean the same at both). */
    private var lastBearingSmoothMs: Long = 0L

    /** Running EMA of the speed-adaptive zoom; NaN until the first frame. */
    private var smoothedZoom: Double = Double.NaN

    /** Smoothed (eased) puck position actually painted, so a snapped↔raw mode flip
     *  glides instead of teleporting; NaN until the first render frame seeds it. */
    private var renderedPuckLat: Double = Double.NaN
    private var renderedPuckLng: Double = Double.NaN
    /** elapsedRealtime of the last render-frame positional ease, for its dt-aware alpha. */
    private var lastPuckEaseMs: Long = 0L
    /** Pose of the puck Feature ACTUALLY pushed to the GL source, so the per-frame
     *  rewrite can be skipped when the puck hasn't meaningfully moved/turned (freezes
     *  GL re-render on a parked car). NaN until the first paint seeds it. */
    private var puckPaintedLat: Double = Double.NaN
    private var puckPaintedLng: Double = Double.NaN
    private var puckPaintedBearing: Double = Double.NaN
    /** Sticky on-route latch (hysteresis): true while the puck snaps to the line.
     *  Enters below PUCK_SNAP_ENTER_M, leaves only above PUCK_SNAP_EXIT_M, so a GPS
     *  offset hovering near the boundary can't chatter the puck on/off the line. */
    private var puckOnRoute: Boolean = false

    /**
     * Speed → camera zoom, EMA-smoothed so it drifts open on the highway and closed
     * around town without stepping. [speedMps] is the filtered speed. Returns a
     * zoom level bracketing [IMMERSIVE_ZOOM]. Reset via NaN on nav stop.
     */
    private fun speedAdaptiveZoom(speedMps: Double): Double {
        val kmh = speedMps * 3.6
        val target = when {
            kmh < 25.0 -> ZOOM_SLOW
            kmh < 55.0 -> ZOOM_CITY
            kmh < 90.0 -> ZOOM_ARTERIAL
            else -> ZOOM_HIGHWAY
        }
        val cur = smoothedZoom
        smoothedZoom = if (cur.isNaN()) target else cur + (target - cur) * ZOOM_EMA_ALPHA
        return smoothedZoom
    }

    /**
     * Shortest-arc exponential moving average for the heading-up camera bearing.
     * The raw per-fix GPS heading is jittery (urban multipath), and even though the
     * 1s animateCamera eases the TRANSITION it still chases each sample's full
     * magnitude — so a noisy heading made the map visibly hunt/wobble. EMA-blend
     * toward the new target along the SHORTEST arc (handles the 359°→1° wrap) so
     * the rotation is smooth and monotonic. Returns a normalized 0..360 bearing.
     * Reset (NaN) on nav start so the first heading snaps instead of easing from 0.
     */
    private fun smoothBearing(target: Double): Double {
        val cur = smoothedBearing
        val now = android.os.SystemClock.elapsedRealtime()
        if (cur.isNaN()) {
            smoothedBearing = ((target % 360.0) + 360.0) % 360.0
            lastBearingSmoothMs = now
            return smoothedBearing
        }
        // dt-aware alpha so the time-constant is the SAME regardless of how often
        // this is called (the ~12fps render frame vs the 1Hz cluster idle follow).
        // alpha = 1 - exp(-dt/tau); clamp dt so a long gap (paused tick) can't snap.
        val dtS = ((now - lastBearingSmoothMs).coerceIn(0L, 1000L)) / 1000.0
        lastBearingSmoothMs = now
        val alpha = (1.0 - kotlin.math.exp(-dtS / BEARING_TAU_S)).coerceIn(0.0, 1.0)
        var delta = (target - cur + 540.0) % 360.0 - 180.0   // shortest signed arc, -180..180
        val next = ((cur + delta * alpha) % 360.0 + 360.0) % 360.0
        smoothedBearing = next
        return next
    }

    /** Record the target just handed to animateCamera (for the next dead-band check). */
    private fun rememberAnimatedTarget(lat: Double, lng: Double, bearing: Double) {
        lastAnimatedLat = lat
        lastAnimatedLng = lng
        lastAnimatedBearing = bearing
    }

    /** One guidance step: pull a fresh fix, advance the engine, update UI + voice. */
    private fun tickGuidance() {
        ioExecutor().execute {
            // Only the network GPS fetch runs on the IO thread.
            val fix = RoadSenseHazardApiClient.fetchCurrentLocation() ?: return@execute
            mainHandler.post {
                if (isFinishing || isDestroyed || !navigating) return@post
                // NavGuidanceEngine is single-thread-contract — advance it on the
                // main thread (same thread that start()/stop() it), not the IO thread.
                // Pass heading+speed+clock so the windowed map-matcher + adaptive
                // off-route latch engage (legacy 2-arg path stays for tests).
                val state = guidance.update(
                    fix.lat, fix.lng, fix.bearing, fix.bestSpeedMps,
                    android.os.SystemClock.elapsedRealtime()
                )
                lastFix = fix
                // Feed the TRUTH fix into the motion estimator. The puck + camera are
                // NOT painted here anymore — the ~12fps renderMotionFrame() glides
                // them from the dead-reckoned estimate so motion is continuous between
                // these sparse 1s fixes (was: teleport per fix). startMotionFollow()
                // is idempotent; ensure it's running.
                feedMotionTruth(fix)
                startMotionFollow()
                // Arrival: the engine's radius/remainder check, OR a speed-gated
                // near-stop on the final approach. The latter handles the common
                // head-unit case where GPS parks the car 20-30 m off the true pin
                // (just outside ARRIVAL_RADIUS_M): if we're within the final band AND
                // the car has slowed to a stop, the driver HAS arrived — end nav
                // instead of letting an off-route wander hunt a reroute around the block.
                // Require TWO consecutive near-stop ticks so a car briefly halted at a
                // light/queue that happens to sit just short of the pin doesn't end nav
                // on the very first stopped fix.
                if (state.remainingDistanceM <= NEAR_STOP_ARRIVAL_M && fix.bestSpeedMps <= NEAR_STOP_SPEED_MPS) {
                    nearStopTicks++
                } else {
                    nearStopTicks = 0
                }
                val nearStopArrived = nearStopTicks >= NEAR_STOP_ARRIVAL_TICKS
                // PUCK-PROXIMITY arrival (the "nav never ends near the destination"
                // fix). The guidance engine above is advanced ONLY by the 1 Hz daemon
                // /api/gps poll, whose position can lag or sit 20-30 m off the true pin
                // — so engine.arrived + the near-stop net can both miss even though the
                // PUCK the driver sees (driven by the low-latency DIRECT platform fix
                // through the motion estimator) has visibly reached the destination.
                // Cross-check the estimator pose against the LOCKED destination: when
                // the puck is within the arrival radius and the car has stopped, the
                // driver HAS arrived. Same speed gate + 2-tick debounce as the near-stop
                // net so a brief halt short of the pin can't end nav early. Head-unit
                // only (the locked dest + estimator live here; the cluster has neither).
                val puckArrived = puckReachedDestination() && fix.bestSpeedMps <= NEAR_STOP_SPEED_MPS
                if (puckArrived) puckArrivedTicks++ else puckArrivedTicks = 0
                val puckArrivedConfirmed = puckArrivedTicks >= NEAR_STOP_ARRIVAL_TICKS
                // ARRIVAL + REROUTE are LIFECYCLE actions owned by the head-unit (the
                // control surface). The cluster is a VIEW-ONLY mirror: it must not end
                // nav or recompute a route itself — it has no routeStops/locked dest, so
                // its maybeReroute would be a no-op anyway, and a self-arrival would race
                // the head-unit (the round-7 clusterArrived latch that guarded that race
                // then mis-suppressed genuinely-new trips). Instead the cluster just keeps
                // displaying; when the head-unit arrives it calls NavSession.clear(), which
                // tears the cluster down cleanly via the route==null branch. So gate both
                // lifecycle blocks to the head-unit; the cluster falls through to the
                // banner-display update below.
                if (!clusterMode) {
                    if (state.arrived || nearStopArrived || puckArrivedConfirmed) {
                        speakOnce(getString(R.string.roadsense_map_arrived))
                        showSnackbar(getString(R.string.roadsense_map_arrived))
                        stopGuidance()
                        return@post
                    }
                    if (state.offRoute) {
                        // Driver diverged → auto-recompute a route to the LOCKED
                        // destination from the current fix, then keep navigating. Pass
                        // the heading only when genuinely moving (a stationary GPS
                        // heading is noise and would lock the reroute to a wrong way).
                        val rerouteHeading = fix.bearing?.takeIf { fix.bestSpeedMps > 2.0 }
                        maybeReroute(fix.lat, fix.lng, rerouteHeading)
                        return@post
                    }
                }
                val m = state.currentManeuver
                if (m != null) {
                    // Bucketed turn distance (stable "50 m", not jittery "47 m").
                    val dist = com.overdrive.app.navmap.nav.MapNetworking.formatTurnDistance(state.distanceToManeuverM)
                    // Roundabout: append the exit ordinal when Valhalla gave one and
                    // the on-screen instruction doesn't already say it.
                    val instr = m.instruction + roundaboutExitSuffix(m)
                    // "then …" preview once the current turn is close.
                    val thenTxt = state.nextManeuver?.takeIf { state.distanceToManeuverM <= MANEUVER_ANNOUNCE_M }
                        ?.let { " ↱ ${getString(R.string.roadsense_map_then)} ${it.instruction}" } ?: ""
                    updateBannerText("$dist • $instr$thenTxt",
                        "${formatDistance(state.remainingDistanceM)} • ${formatEta(state.etaSeconds)}")
                    setManeuverIcon(m.type)
                    // Speak the instruction once when within the announce window.
                    // Prefer Valhalla's verbal_pre phrasing (purpose-built for TTS)
                    // over the on-screen text when present. (Single-shot — unchanged
                    // behaviour; no staging.)
                    if (state.distanceToManeuverM <= MANEUVER_ANNOUNCE_M && m.instruction != lastSpokenInstruction) {
                        speakOnce(m.verbalPre.ifBlank { m.instruction })
                        lastSpokenInstruction = m.instruction
                    }
                }
            }
        }
    }

    /**
     * Is the PUCK (the on-screen vehicle position) within the arrival radius of the
     * locked destination? Drives the puck-proximity arrival path so nav ends when the
     * driver visibly reaches the pin even if the daemon-poll engine position lags.
     *
     * Uses the motion estimator's current pose — the same source the puck is painted
     * from (low-latency DIRECT fix, dead-reckoned to the present) — NOT the laggy
     * daemon poll the guidance engine consumes. Measures straight-line distance to the
     * LOCKED destination (lockedDestLat/Lng, set on every route start/recompute) with
     * the engine's haversine. False when no locked dest, no estimator fix, or the puck
     * isn't yet inside the radius. Bias-free: estimate() returns null before the first
     * truth fix, so a fresh trip can't false-arrive at the origin.
     */
    private fun puckReachedDestination(): Boolean {
        if (lockedDestLat.isNaN() || lockedDestLng.isNaN()) return false
        if (!motionEstimator.hasFix()) return false
        val nowMono = android.os.SystemClock.elapsedRealtime()
        val m = motionEstimator.estimate(nowMono, nowMono) ?: return false
        val d = guidance.haversineMeters(m.lat, m.lng, lockedDestLat, lockedDestLng)
        return d <= com.overdrive.app.navmap.nav.NavGuidanceEngine.ARRIVAL_RADIUS_M
    }

    /** Roundabout exit ordinal suffix (" • exit 2") when Valhalla gave one and the
     *  instruction text doesn't already mention that ordinal. Empty otherwise. */
    private fun roundaboutExitSuffix(m: com.overdrive.app.navmap.nav.RouteManeuver): String {
        val n = m.roundaboutExitCount
        if (n <= 0) return ""
        // Skip if the instruction already states the exit (avoid "… 2nd exit • exit 2").
        if (m.instruction.contains(n.toString())) return ""
        return " • " + getString(R.string.roadsense_map_exit_n, n)
    }

    /**
     * Feed a raw GPS fix into the motion estimator — ONLY when it's a genuinely NEW
     * fix. The app polls /api/gps every ~1s but the daemon only gets a new GPS fix
     * every ~2s, so it RE-SENDS the same fix (identical `timestampMs`) on the
     * in-between poll. Re-anchoring the dead-reckon clock ([lastTruthElapsedMs]) on
     * that duplicate poll collapses the render frame's `dtS` to ~0 → the puck snaps
     * BACK ~1s of travel and re-advances → a 1s sawtooth judder. So we gate on the
     * fix timestamp: a duplicate poll is a no-op (the estimator keeps dead-reckoning
     * forward from the last real fix uninterrupted). When the daemon gives no
     * timestamp (shouldn't happen on this HU) we treat every fix as new.
     */
    /**
     * Freeze-guard re-anchor (called from the render tick when truth has gone stale near
     * the dead-reckon cap). Re-feeds the LAST KNOWN real position ([lastFix]) into the
     * estimator stamped at [nowMono], advancing the baseline so the dead-reckon dt resets
     * and the puck holds at the last real fix instead of freezing at the 3-s extrapolation
     * cap. Bypasses the daemon fallback gate (this is the safety net the gate can't be) and
     * the duplicate-ts guard (we deliberately re-stamp the same position at a new instant).
     * No-op until a first fix exists. Cheap: one onTruthPoint with the held position, which
     * the estimator absorbs as a tiny/zero move (no snap). Speed is held from lastFix so the
     * puck keeps coasting at the last real speed, not a stale extrapolated one.
     */
    private fun reanchorToLastFix(nowMono: Long) {
        val fix = lastFix ?: return
        if (!motionEstimator.hasFix()) return
        // CLAMP the accuracy so the estimator can NEVER reject this re-anchor. lastFix is
        // written unconditionally by the daemon feeders and may carry a coarse accuracy
        // (>55m, the estimator's MAX_ACCEPTED_ACCURACY_M reject threshold) — and a coarse
        // fix is exactly the degraded-GPS case where truth goes stale and this watchdog
        // must fire. A rejected re-anchor would NOT advance the estimator baseline, so the
        // puck would stay frozen at the dead-reckon cap while the anchor writes below
        // (mistakenly) re-armed the watchdog timer and kept the daemon gated — a re-armed
        // permanent freeze. Feeding a guaranteed-accepted accuracy (<= the estimator's
        // GOOD bar) makes the re-anchor always advance the baseline. We hold the LAST REAL
        // position (not the extrapolation), so a tight accuracy is the honest claim here.
        val reanchorAcc = minOf(fix.accuracy ?: REANCHOR_ACCURACY_M, REANCHOR_ACCURACY_M)
        val accepted = motionEstimator.onTruthPoint(
            lat = fix.lat, lng = fix.lng,
            speedMps = fix.bestSpeedMps,
            rawBearingDeg = fix.bearing,
            accuracyM = reanchorAcc,
            tsMs = nowMono,
            brakePercent = fix.brakePercent
        ).timestampMs == nowMono
        // Advance the anchors ONLY on a confirmed accept (mirrors onDirectFix), so a
        // surprise rejection can't re-arm the watchdog / keep the daemon gated on a
        // baseline that never moved. With the clamp above this always accepts; the
        // acceptance check is defensive belt-and-suspenders.
        if (accepted) {
            lastTruthElapsedMs = nowMono
            lastFixCaptureMonoMs = nowMono
            lastAcceptedFixMonoMs = nowMono
        }
    }

    private fun feedMotionTruth(fix: RoadSenseHazardApiClient.LatLngFix) {
        val ts = fix.timestampMs
        // Drop a duplicate OR OUT-OF-ORDER re-poll (ts <= last fed) so we don't re-anchor
        // the dead-reckon clock. Equality covers the 4s re-send of the same fix; the <=
        // also covers a BACK-DATED fix — the periodicSender getLastKnownLocation re-inject
        // can carry an older getTime() than the latest live fix, which an equality-only
        // gate would treat as new and re-anchor (a small backward puck step). The estimator
        // also rejects ts<=lastAcceptedTs, but the re-anchor happens HERE before that, so
        // gate it here too.
        if (ts != null && ts <= lastFedFixTsMs) return
        lastFedFixTsMs = ts ?: 0L
        // FALLBACK GATE: when the DIRECT in-process source is delivering fixes, it owns
        // the puck — skip feeding the estimator here so the coarser/older 1 Hz daemon
        // poll can't snap the puck back. The daemon poll stays the source only until the
        // direct listener warms up, or if it goes quiet (no permission / provider off).
        //
        // TWO conditions, both required to skip the daemon:
        //  (1) a direct fix was RECEIVED within the window — the deadlock tie-breaker: the
        //      daemon must STOP re-pinning the estimator baseline so a direct fix (carrying
        //      the EARLIER hardware-capture instant) can overtake it and win acceptance.
        //  (2) the puck was actually fed an ACCEPTED fix within the window — a freeze guard:
        //      if direct fixes keep arriving but the estimator keeps REJECTING them past
        //      this window (pathological: a provider whose capture instant trails ingest by
        //      more than the dead-reckon cap), resume the daemon so the puck can't strand at
        //      the MAX_DEAD_RECKON_S cap. The daemon feed (ingest=now) then out-ranks the
        //      stale baseline and is accepted, and the reset baseline lets direct overtake
        //      again immediately — so this can neither deadlock nor freeze. In NORMAL warmup
        //      (gap < ~2 s) a direct fix is accepted within ~2 fixes, well inside the window,
        //      so (2) never trips and there is no extra daemon feed / snap-back.
        val nowGate = android.os.SystemClock.elapsedRealtime()
        if (directGpsActive &&
            nowGate - lastDirectFixSeenMonoMs < DIRECT_FIX_FALLBACK_MS &&
            nowGate - lastAcceptedFixMonoMs < DIRECT_FIX_FALLBACK_MS) {
            return
        }
        // Feed the estimator in the MONOTONIC (elapsedRealtime) domain — same clock as
        // the direct source and the render tick — so the two feeders never collide in the
        // estimator's timestamp baseline (the daemon fix carries a WALL-CLOCK send-time,
        // ~1.78e12, that would otherwise reject or freeze a mono-stamped direct fix). The
        // daemon fix lost its true capture instant upstream (send-time stamping), so this
        // path stays ingestion-anchored (no transport-age compensation) — that's the
        // intended fallback behaviour; the DIRECT path is the one that extrapolates to now.
        val ingestMono = android.os.SystemClock.elapsedRealtime()
        lastTruthElapsedMs = ingestMono
        lastFixCaptureMonoMs = ingestMono
        lastAcceptedFixMonoMs = ingestMono   // daemon feed is accepted (ingest=now > baseline)
        motionEstimator.onTruthPoint(
            lat = fix.lat, lng = fix.lng,
            // Prefer the smooth BYD CAN wheel speed over noisy GPS speed (falls back to
            // GPS, then 0). This is the core puck-smoothness win: the dead-reckoner now
            // extrapolates at the true vehicle speed, so it no longer over-travels on a
            // GPS speed spike and snap-corrects when the next fix lands.
            speedMps = fix.bestSpeedMps,
            rawBearingDeg = fix.bearing,
            accuracyM = fix.accuracy,
            tsMs = ingestMono,
            // Brake pedal (0-100) when the CAN bus reported it — lets the estimator
            // shed predicted speed during braking so the puck eases to a stop instead
            // of overshooting then being pulled back.
            brakePercent = fix.brakePercent
        )
    }

    /** Daemon `lastUpdate` of the last fix actually fed to the estimator, so a
     *  duplicate re-poll (same timestamp) doesn't re-anchor the dead-reckon clock. */
    @Volatile private var lastFedFixTsMs: Long = 0L

    // ── In-process direct GPS source (low-latency truth) ─────────────────────────
    // The map runs in the app process and holds ACCESS_FINE_LOCATION, so it subscribes
    // to the platform location provider DIRECTLY — standard nav practice — instead of
    // learning each fix only through the 1 Hz loopback poll of the daemon. The daemon
    // poll (fetchCurrentLocation) stays as a cold-start seed + fallback, gated to fire
    // only when a direct fix hasn't arrived recently. Two wins over the poll:
    //   1) No poll-phase wait + no IPC round-trip — each fix reaches the estimator the
    //      instant the provider delivers it.
    //   2) The fix carries its HARDWARE-CAPTURE instant (Location.getElapsedRealtimeNanos,
    //      already in the elapsedRealtime/monotonic domain the render tick uses), so the
    //      dead-reckoner can extrapolate the pose to the TRUE present — including all
    //      transport age — instead of anchoring to the ingestion moment and trailing the
    //      vehicle by the pipeline latency (the puck-lag the daemon-poll path could never
    //      shed, since the fix's own time was overwritten with a send-time upstream).
    private var gpsThread: HandlerThread? = null
    @Volatile private var directGpsActive = false
    /** elapsedRealtime (ms) the last DIRECT fix was RECEIVED (regardless of whether the
     *  estimator accepted it). This — NOT acceptance — gates the daemon fallback feed.
     *  WHY reception, not acceptance: the daemon stamps the estimator at ingestion-now,
     *  so its lastAcceptedTs sits ≈now; a direct fix carries the EARLIER hardware-capture
     *  instant, so while the daemon keeps feeding, every direct fix is rejected
     *  (tsMs <= lastAcceptedTs) and could NEVER win acceptance — a permanent deadlock that
     *  left the puck on the laggy daemon feed forever. Gating on reception makes the daemon
     *  stop feeding as soon as direct fixes arrive; lastAcceptedTs then goes stale within
     *  DIRECT_FIX_FALLBACK_MS and the next direct capture out-ranks it → handoff completes. */
    @Volatile private var lastDirectFixSeenMonoMs: Long = 0L
    /** elapsedRealtime (ms) the estimator last ACCEPTED a fix from EITHER feeder — the
     *  freeze-guard: the daemon fallback resumes if no accepted fix landed within
     *  DIRECT_FIX_FALLBACK_MS, so a direct source that keeps getting rejected (pathological
     *  >cap capture-to-ingest lag) can't strand the puck at the dead-reckon cap. */
    @Volatile private var lastAcceptedFixMonoMs: Long = 0L

    // Explicit anonymous LocationListener overriding ALL FOUR methods — NOT a SAM lambda.
    // On the API-29 runtime the three non-location callbacks have NO interface default
    // (defaults were added in API 30), and core-library desugaring is OFF, so a lambda
    // (which implements only onLocationChanged) throws AbstractMethodError the moment
    // LocationManager dispatches a provider enable/disable/status — routine while driving.
    // Mirrors LocationSidecarService's listener, which overrides all four.
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { onDirectFix(loc) }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** Start the map's own location subscription (idempotent). GPS at the 1 Hz cadence
     *  + a coarse NETWORK fallback, delivered on a background looper so the provider
     *  callback never touches the UI thread. No-op (daemon poll remains the source) when
     *  permission is absent or no provider is enabled. */
    private fun startDirectGps() {
        if (directGpsActive) return
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        val t = HandlerThread("map-gps").also { it.start() }
        gpsThread = t
        try {
            var registered = false
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, t.looper)
                registered = true
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, gpsListener, t.looper)
                registered = true
            }
            // Only claim the direct source is active if a provider actually registered —
            // otherwise directGpsActive=true would (via a getLastKnownLocation seed) gate
            // the daemon poll OFF while no live provider feeds the puck. With no provider,
            // leave directGpsActive=false so the daemon poll stays the source.
            if (!registered) { stopDirectGps(); return }
            // Seed immediately from the last known fix so the puck isn't stale at open.
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onDirectFix(it) }
            directGpsActive = true
        } catch (_: SecurityException) {
            // Permission not granted (yet) → leave the daemon poll as the source. Tear
            // down the half-started thread so we don't leak it.
            stopDirectGps()
        } catch (_: Throwable) {
            stopDirectGps()
        }
    }

    /** Stop the direct subscription + tear down its thread. Idempotent. */
    private fun stopDirectGps() {
        directGpsActive = false
        try {
            (getSystemService(LOCATION_SERVICE) as? LocationManager)?.removeUpdates(gpsListener)
        } catch (_: Throwable) {}
        gpsThread?.let { try { it.quitSafely() } catch (_: Throwable) {} }
        gpsThread = null
    }

    /**
     * A direct platform fix arrived (on the gps HandlerThread). Hop to the main thread,
     * feed the estimator with the fix's HARDWARE-CAPTURE instant in the elapsedRealtime
     * (monotonic) domain so [renderMotionFrame] dead-reckons to the true present. CAN
     * wheel-speed/brake (when present) still come from the daemon fuse via [lastFix];
     * fall back to the fix's own GPS speed otherwise.
     */
    private fun onDirectFix(loc: Location) {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return
        // Capture instant in the MONOTONIC domain (== the render tick's nowMono clock).
        // A stub/emulated provider can report 0 here → fall back to "now" (loses age
        // compensation but keeps the no-poll/no-IPC latency win).
        val capRaw = loc.elapsedRealtimeNanos / 1_000_000L
        val captureMono = if (capRaw > 0L) capRaw else android.os.SystemClock.elapsedRealtime()
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            if (captureMono <= lastFedFixCaptureMonoMs) return@post  // de-dup / out-of-order
            lastFedFixCaptureMonoMs = captureMono
            // Mark RECEPTION (before the acceptance check) so the daemon fallback stops
            // feeding as soon as direct fixes flow — this is the tie-breaker that lets the
            // direct source escape the daemon's lastAcceptedTs≈now pin and win acceptance.
            lastDirectFixSeenMonoMs = android.os.SystemClock.elapsedRealtime()
            // Prefer the smooth BYD CAN wheel speed (carried on the last daemon fix) over
            // the noisy GPS speed; fall back to the fix's own speed, else 0.
            val canMps = lastFix?.canSpeedKmh?.let { it / 3.6 }
            val speedMps = canMps ?: (if (loc.hasSpeed()) loc.speed.toDouble() else 0.0)
            val result = motionEstimator.onTruthPoint(
                lat = loc.latitude, lng = loc.longitude,
                speedMps = speedMps,
                rawBearingDeg = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                tsMs = captureMono,                       // fix-capture instant, MONO domain
                brakePercent = lastFix?.brakePercent
            )
            // ACCEPTANCE gate: onTruthPoint returns a Motion stamped with the accepted
            // tsMs on success, or the UNCHANGED prior Motion on rejection (bad accuracy,
            // or tsMs <= the estimator baseline — which happens for the first 1-3 direct
            // fixes right after the daemon seeded the baseline with a LATER ingestion
            // instant). Only treat the fix as "the direct source is live" when it was
            // ACCEPTED. Otherwise: do NOT advance the render anchor (would dead-reckon off
            // a frozen baseline). The daemon fallback GATE is keyed on RECEPTION
            // (lastDirectFixSeenMonoMs, set above) — NOT on acceptance — so the daemon
            // stops feeding and lets the estimator baseline go stale, which is what
            // ultimately lets a direct fix win acceptance here.
            val accepted = result.timestampMs == captureMono
            if (!accepted) return@post
            lastAcceptedFixMonoMs = android.os.SystemClock.elapsedRealtime()  // freeze-guard signal
            // Anchor the render clock to this capture instant so the dead-reckon dt =
            // (nowMono − capture) covers the full transport age (see renderMotionFrame).
            lastFixCaptureMonoMs = captureMono
            // Mirror into lastFix so recenter()/idle readers see the fresh position. Build
            // a lightweight LatLngFix; timestampMs stays the capture instant (mono) — only
            // the estimator path consumes it now.
            lastFix = RoadSenseHazardApiClient.LatLngFix(
                lat = loc.latitude, lng = loc.longitude,
                bearing = if (loc.hasBearing()) loc.bearing.toDouble() else lastFix?.bearing,
                speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                timestampMs = captureMono,
                canSpeedKmh = lastFix?.canSpeedKmh,
                accelPercent = lastFix?.accelPercent,
                brakePercent = lastFix?.brakePercent
            )
            startMotionFollow()   // idempotent — ensure the glide tick is running
        }
    }

    /** elapsedRealtime (ms) capture-instant of the last fix fed via the DIRECT source,
     *  for de-dup. Distinct from [lastFedFixTsMs] (the daemon wall-clock dedup). */
    @Volatile private var lastFedFixCaptureMonoMs: Long = 0L
    /** Capture instant (elapsedRealtime ms) the dead-reckon clock anchors to. Set by
     *  the direct source ([onDirectFix]); the render tick extrapolates from it. */
    @Volatile private var lastFixCaptureMonoMs: Long = 0L

    /**
     * Paint one render frame from the dead-reckoned motion estimate: glide the puck
     * to the predicted position and ease the heading-up camera to follow it. Runs at
     * RENDER_TICK_MS (~30fps) so motion is continuous between the sparse ~2s truth
     * fixes. Uses moveCamera (instant) on an already-interpolated pose — no animator
     * thread, cheap on the Adreno 610. Below the moving-speed gate the bearing holds
     * (GPS heading is noise when stationary). Skips the camera write inside the
     * position/bearing dead-band so a parked car doesn't churn frames.
     */
    private fun renderMotionFrame() {
        // Single MONOTONIC clock for the dead-reckon. Both truth feeders now stamp the
        // estimator in the elapsedRealtime domain: the DIRECT source uses the fix's true
        // HARDWARE-CAPTURE instant (Location.getElapsedRealtimeNanos), so the dead-reckon
        // dt = (nowMono − capture) extrapolates the pose to the TRUE present INCLUDING the
        // full transport age — the lag fix (the old path anchored to the INGESTION instant
        // and could never shed the fix→ingestion latency); the daemon FALLBACK path stamps
        // the ingestion instant (no capture time survives upstream), so it stays
        // ingestion-anchored as before. `now` == `nowMono` so estimate()'s dt is correct
        // for both; the second arg feeds the (disabled) gyro-silence check in the same epoch.
        val nowMono = android.os.SystemClock.elapsedRealtime()
        // FREEZE-GUARD (render-tick watchdog, GRID-INDEPENDENT). The dead-reckon caps at
        // MAX_DEAD_RECKON_S (3 s): past it the puck FREEZES at the 3-s-ahead point until a
        // fresh truth fix lands. The daemon fallback poll is quantized to the 1 Hz feeder
        // grid (and can return null), so it cannot reliably re-feed BEFORE the cap. This
        // watchdog runs at the sub-second render cadence: if the estimator baseline is
        // approaching the cap and no truth fix has refreshed it, RE-ANCHOR the estimator to
        // the last known REAL position (lastFix) at `nowMono`. That holds the puck at the
        // last real fix (NOT flying off on a stale extrapolation) and resets the dead-reckon
        // clock, so the puck can never sit frozen at the cap regardless of grid phase or a
        // failed daemon poll. Only fires in a genuine truth STALL — during normal driving a
        // truth fix refreshes the baseline every ~1 s, far inside this window.
        // Guard lastFixCaptureMonoMs>0: a 0 baseline is "no truth anchored yet" (fresh /
        // post-resume-reset) — NOT a 2.2s-old stale anchor — so don't let the elapsed-since-
        // epoch-0 value (huge) trip the watchdog and re-anchor off a stale lastFix before the
        // first real fix lands. Also requires the estimator to already hold a fix.
        if (lastFixCaptureMonoMs > 0L &&
            (nowMono - lastFixCaptureMonoMs) >= KEEP_WARM_BEFORE_CAP_MS) {
            reanchorToLastFix(nowMono)
        }
        val m = motionEstimator.estimate(nowMono, nowMono) ?: return

        // Snap the dead-reckoned prediction onto the route polyline so the puck
        // rides the LINE (not floating beside it) and the heading-up camera can
        // steer by the smooth route TANGENT instead of noisy GPS heading. When we're
        // genuinely off-route (snap offset large) or not navigating, fall back to the
        // raw estimate + its filtered heading. Below the moving gate the heading is
        // held (a parked car's heading is noise either way).
        val moving = m.speedMps > IMMERSIVE_MIN_SPEED_MPS
        lastFrameMoving = moving   // drives the adaptive render-tick rate (fast vs idle)
        val snap = if (navigating) guidance.snapToRoute(m.lat, m.lng) else null
        // Sticky on-route latch (hysteresis): enter snapping when comfortably close to
        // the line, leave only when clearly off it, so a GPS offset hovering near a
        // single threshold can't flicker the puck on/off the line every frame.
        puckOnRoute = when {
            snap == null -> false
            puckOnRoute -> snap.offsetM <= PUCK_SNAP_EXIT_M    // already snapped → stay until clearly off
            else -> snap.offsetM <= PUCK_SNAP_ENTER_M          // not snapped → enter only when close
        }
        // Trim the traveled route behind the car (GPU line-gradient; uses this same
        // snapped progress so the boundary rides under the puck). Off-route holds.
        applyRouteTrim(snap, puckOnRoute)

        val targetLat: Double; val targetLng: Double; val rawBearing: Double
        if (puckOnRoute && snap != null) {
            // Glue the target to the line; steer by the route tangent (jitter-free).
            targetLat = snap.lat; targetLng = snap.lng
            rawBearing = if (moving) snap.bearingDeg else (smoothedBearing.takeUnless { it.isNaN() } ?: snap.bearingDeg)
        } else {
            targetLat = m.lat; targetLng = m.lng
            rawBearing = if (moving) m.bearingDeg else (smoothedBearing.takeUnless { it.isNaN() } ?: m.bearingDeg)
        }
        // Rate-limit the PAINTED puck position toward the target so a snapped↔raw mode
        // flip — which can move the source up to ~PUCK_SNAP_EXIT_M in one frame — glides
        // over a few frames instead of teleporting. A max-STEP clamp (not an EMA) is
        // deliberate: it adds ZERO lag during normal driving (each real per-frame step
        // is far below the clamp, so the puck tracks the target exactly), and ONLY a
        // discontinuity bigger than the clamp gets spread out. First frame seeds directly.
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val puckLat: Double; val puckLng: Double
        if (renderedPuckLat.isNaN()) {
            puckLat = targetLat; puckLng = targetLng
        } else {
            val dtS = ((nowMs - lastPuckEaseMs).coerceIn(0L, 1000L)) / 1000.0
            val maxStepM = (PUCK_MAX_CATCHUP_MPS * dtS).coerceAtLeast(0.5)
            val gapM = guidance.haversineMeters(renderedPuckLat, renderedPuckLng, targetLat, targetLng)
            val frac = if (gapM <= maxStepM) 1.0 else (maxStepM / gapM)
            puckLat = renderedPuckLat + (targetLat - renderedPuckLat) * frac
            puckLng = renderedPuckLng + (targetLng - renderedPuckLng) * frac
        }
        renderedPuckLat = puckLat; renderedPuckLng = puckLng; lastPuckEaseMs = nowMs
        // Bearing source for the painted heading:
        //  (A) ON-ROUTE: the route tangent IS ground-truth heading (smooth by
        //      construction — it only steps at vertices), so steer by it DIRECTLY
        //      instead of low-passing it through smoothBearing. The EMA was added to
        //      tame noisy GPS heading, but it also LAGS a real turn (corner-cut); the
        //      tangent has no noise to tame, so skipping the lag makes turns crisp.
        //  OFF-ROUTE / no route: keep the EMA over the (raw GPS or gyro-fused) heading.
        //      When the gyro is healthy m.bearingDeg is already the real-time gyro-
        //      integrated heading from estimate(), so the EMA just smooths its vertices.
        val bearing = when {
            puckOnRoute && snap != null && moving -> {
                // Paint the crisp tangent, but ALSO keep smoothBearing's state primed with
                // it (advances smoothedBearing + lastBearingSmoothMs). Otherwise the EMA
                // freezes for the whole on-route stretch and, at a snap-EXIT flip (lane
                // split / off-route), the next smoothBearing() would ease from a stale held
                // value → a visible heading whip. Priming keeps the flip seamless.
                smoothBearing(rawBearing)
                rawBearing                                               // tangent, no EMA lag
            }
            moving -> smoothBearing(rawBearing)
            else -> rawBearing
        }
        lastBearing = bearing
        // Puck at the eased pose — instant, it's already smooth.
        updateLocationPuckAt(puckLat, puckLng, bearing)

        // PUCK-ONLY when idle-browsing (not navigating, not the cluster mirror): the
        // idle follow now runs this same render tick (SOTA idle glide), but the idle
        // contract is "puck glides underneath, camera stays where the user panned". So
        // gate the heading-up camera follow on an active follow regime. Navigating + the
        // cluster mirror DO drive the camera (heading-up immersive view).
        if (!navigating && !clusterMode) return
        // Dead-band keyed on the PUCK position so a parked car doesn't churn frames.
        // While moving we want EVERY frame to glide, so the gate is intentionally
        // tiny (sub-metre) — its only job is to freeze a stationary vehicle.
        if (cameraWithinDeadband(puckLat, puckLng, bearing)) return
        map?.let { mp ->
            val zoom = speedAdaptiveZoom(m.speedMps)
            // Predictive look-ahead: push the camera TARGET ahead of the puck along
            // the heading, scaled by speed (more lead at speed = see further down the
            // road), so the puck sits lower in frame with the road ahead filling it.
            // The puck itself is drawn at the true pose (above) — only the camera leads.
            val (camLat, camLng) = if (moving) {
                val leadM = (m.speedMps * LOOKAHEAD_SECONDS).coerceIn(LOOKAHEAD_MIN_M, LOOKAHEAD_MAX_M)
                destinationPointAhead(puckLat, puckLng, bearing, leadM)
            } else { puckLat to puckLng }
            val pos = org.maplibre.android.camera.CameraPosition.Builder()
                .target(LatLng(camLat, camLng))
                .zoom(zoom)
                .tilt(IMMERSIVE_TILT)
                .bearing(bearing)
                .build()
            // moveCamera (INSTANT) — the pose is ALREADY interpolated by the 12 fps
            // dead-reckon + bearing EMA, so each frame is a tiny step. A per-frame
            // animateCamera would spawn overlapping 80 ms ValueAnimators that cancel
            // each other (visible stutter + GC churn on the Adreno 610); instant
            // writes on a pre-smoothed pose are what actually glides.
            mp.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
            rememberAnimatedTarget(puckLat, puckLng, bearing)
        }
    }

    /** A point [distM] meters from (lat,lng) along [bearingDeg] (great-circle). */
    private fun destinationPointAhead(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = 6371000.0
        val d = distM / r
        val br = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lng)
        val p2 = kotlin.math.asin(
            kotlin.math.sin(p1) * kotlin.math.cos(d) +
                kotlin.math.cos(p1) * kotlin.math.sin(d) * kotlin.math.cos(br))
        val l2 = l1 + kotlin.math.atan2(
            kotlin.math.sin(br) * kotlin.math.sin(d) * kotlin.math.cos(p1),
            kotlin.math.cos(d) - kotlin.math.sin(p1) * kotlin.math.sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }

    /** Elapsed-realtime stamp captured when lastFix was set, to convert the daemon
     *  wall-clock fix time into the monotonic domain for dead-reckoning dt. */
    @Volatile private var lastTruthElapsedMs: Long = 0L

    private var lastSpokenInstruction: String? = null
    /** Last camera bearing used in immersive follow (held when stationary). */
    private var lastBearing: Double = 0.0
    /** Consecutive guidance ticks the car has been near-stopped within the final-approach
     *  band — debounces the speed-gated arrival against a brief halt just short of the pin. */
    private var nearStopTicks: Int = 0
    /** Consecutive guidance ticks the PUCK (estimator pose, fed by the low-latency direct
     *  fix) has been within the arrival radius of the locked destination AND stopped — the
     *  puck-proximity arrival debounce, independent of the laggy daemon-poll engine state. */
    private var puckArrivedTicks: Int = 0

    // Dead-band for the guidance/cluster camera follow: skip the animateCamera when
    // the new target is within ~3m of the last animated target AND |Δbearing| < ~2°,
    // so a near-stationary fix doesn't churn a 1s glide every tick. The puck + banner
    // /voice still update — only the camera animate is elided.
    private var lastAnimatedLat: Double = Double.NaN
    private var lastAnimatedLng: Double = Double.NaN
    private var lastAnimatedBearing: Double = Double.NaN

    /**
     * Auto-reroute: when the engine reports off-route, recompute a fresh route
     * from the current position to the LOCKED destination and resume guidance on
     * it. Debounced (REROUTE_MIN_INTERVAL_MS) and single-flight (`rerouting`) so a
     * burst of off-route ticks fires at most one recompute. No-op if the
     * destination isn't set (shouldn't happen while navigating).
     */
    private fun maybeReroute(fromLat: Double, fromLng: Double, fromHeading: Double? = null) {
        if (rerouting) return
        if (lockedDestLat.isNaN() || lockedDestLng.isNaN()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRerouteMs < REROUTE_MIN_INTERVAL_MS) return
        lastRerouteMs = now
        rerouting = true
        showSnackbar(getString(R.string.roadsense_map_rerouting))
        val dLat = lockedDestLat; val dLng = lockedDestLng
        // Itinerary-aware: build the list of stops still AHEAD of the car so a
        // multi-stop trip keeps its remaining via-stops on a reroute instead of
        // collapsing to a direct run at the final destination. Snapshot on the main
        // thread (routeStops is main-thread state).
        val remainingStops = remainingStopsAhead(fromLat, fromLng)
        ioExecutor().execute {
            // Pass the travel heading so the new route departs in the direction the
            // car is already going (no demanded U-turn back to the old path).
            val routes: List<NavRoute> = if (remainingStops.size > 1) {
                // ≥1 via-stop still ahead → route THROUGH them in order. Valhalla
                // gives no alternates on multipoint, which is expected.
                val pts = ArrayList<com.overdrive.app.navmap.nav.GeoPoint>(remainingStops.size + 1)
                pts.add(com.overdrive.app.navmap.nav.GeoPoint(fromLat, fromLng))
                remainingStops.forEach { pts.add(com.overdrive.app.navmap.nav.GeoPoint(it.lat, it.lng)) }
                ValhallaRouteClient.routeVia(pts)
            } else {
                ValhallaRouteClient.routesWithAlternates(
                    fromLat, fromLng, dLat, dLng, ROUTE_ALTERNATES, fromHeading
                )
            }
            mainHandler.post {
                rerouting = false
                if (isFinishing || isDestroyed || !navigating) return@post
                if (routes.isEmpty()) return@post // keep the old route; try again next divergence
                previewRoutes = routes
                previewSelectedIdx = 0
                guidance.start(routes[0])
                resetRouteTrim()   // rerouted → reset trim against the NEW route arc length
                lastSpokenInstruction = null
                drawRoutePreview(routes, 0)
                // Propagate the NEW best route to the cluster mirror. Without this
                // the cluster keeps guiding on the STALE pre-divergence route after
                // the head unit reroutes (NavSession was only published at nav start).
                if (!clusterMode) NavSession.publishRoute(routes[0], previewDestLabel)
            }
        }
    }

    /**
     * The itinerary stops still AHEAD of the car, for an itinerary-aware reroute.
     * Drops any leading via-stop the car has already effectively reached (within
     * [STOP_REACHED_M]) so a reroute doesn't send the car BACK to a stop it just
     * passed. The final destination is always kept (never pruned). Returns the
     * ordered [aheadStop1, …, destination]; for a single-destination trip this is
     * just [destination]. Reads [routeStops] — call on the main thread.
     */
    private fun remainingStopsAhead(fromLat: Double, fromLng: Double): List<SearchResult> {
        if (routeStops.isEmpty()) return emptyList()
        val lastIdx = routeStops.size - 1
        val out = ArrayList<SearchResult>(routeStops.size)
        routeStops.forEachIndexed { idx, s ->
            // Always keep the destination; prune only intermediate stops we've reached.
            if (idx == lastIdx) { out.add(s); return@forEachIndexed }
            val reached = guidance.haversineMeters(fromLat, fromLng, s.lat, s.lng) <= STOP_REACHED_M
            if (!reached) out.add(s)
        }
        return out
    }

    private fun stopGuidance() {
        navigating = false
        // Nav ended (arrived / end-nav) → drop the persisted trip so a later re-entry
        // opens fresh, not back into a finished route.
        if (!clusterMode) NavTripStore.clear(applicationContext)
        mainHandler.removeCallbacks(guidanceRunnable)
        guidance.stop()
        // Stop the 30 fps dead-reckon render tick + reset the estimator on BOTH the
        // head unit AND the cluster when guidance ends. Previously the cluster KEPT
        // motionFollowActive=true, so renderMotionFrame() went on dead-reckoning from
        // the LAST guidance fix (feedMotionTruth only runs inside the guidance tick, so
        // no fresh truth ever arrived) WHILE clusterFollowRunnable simultaneously wrote
        // the puck from fresh 1 Hz GPS — two unsynchronized writers fighting over the
        // puck = the visible cluster jump. With the render tick stopped, the cluster
        // idle-follow (which now feeds the estimator — see clusterFollowRunnable) is the
        // SINGLE puck writer. The head unit hands back to its idle follow below.
        stopMotionFollow()
        motionEstimator.reset()
        if (!clusterMode) NavSession.clear()   // tell the cluster mirror nav ended
        lastSpokenInstruction = null
        smoothedBearing = Double.NaN   // reset bearing EMA so the next trip starts clean
        smoothedZoom = Double.NaN       // reset zoom EMA too
        renderedPuckLat = Double.NaN    // reset puck position-ease so it re-seeds next trip
        renderedPuckLng = Double.NaN
        puckPaintedLat = Double.NaN     // reset painted-pose dead-band so next trip re-seeds
        puckPaintedLng = Double.NaN
        puckPaintedBearing = Double.NaN
        lastAnimatedLat = Double.NaN    // reset camera dead-band baseline too (hygiene:
        lastAnimatedLng = Double.NaN    // a stale baseline could otherwise suppress the
        lastAnimatedBearing = Double.NaN // first idle-follow camera frame of the next trip)
        puckOnRoute = false             // reset the on-route snap latch
        lastFedFixTsMs = 0L             // reset the duplicate-fix dedup for the next trip
        nearStopTicks = 0               // reset the near-stop arrival debounce
        puckArrivedTicks = 0            // reset the puck-proximity arrival debounce
        lastRouteProgress = 0f          // reset traveled-route trim (source emptied below)
        lastAppliedProgress = -1f
        routeSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        altRouteSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        clearItineraryMarkers()
        findViewById<View>(R.id.navBanner)?.visibility = View.GONE
        findViewById<View>(R.id.navBannerIcon)?.visibility = View.GONE
        findViewById<FloatingActionButton>(R.id.fabEndNav)?.visibility = View.GONE
        exitImmersive()
        // Clear the navigation POIs (they were route-specific).
        poiSource?.setGeoJson(EMPTY_FEATURE_COLLECTION)
        // Hand the puck back to the idle follow so it keeps tracking the live fix after
        // the route ends (head unit → idle follow; cluster → its own idle follow).
        if (clusterMode) startClusterFollow() else startIdleFollow()
    }

    private fun speakOnce(text: String) {
        try { navVoice?.speak(text) } catch (_: Throwable) {}
    }

    private fun showManeuverBanner(primary: String, route: NavRoute) {
        updateBannerText(primary,
            "${formatDistance(route.totalDistanceMeters)} • ${formatEta(route.totalDurationSeconds)}")
    }

    private fun updateBannerText(primary: String, secondary: String) {
        findViewById<TextView>(R.id.navBannerPrimary)?.text = primary
        findViewById<TextView>(R.id.navBannerSecondary)?.text = secondary
    }

    /** Set the banner's maneuver glyph from a Valhalla maneuver type (or hide it). */
    private fun setManeuverIcon(type: Int) {
        val iv = findViewById<ImageView>(R.id.navBannerIcon) ?: return
        val res = maneuverIconRes(type)
        if (res == 0) { iv.visibility = View.GONE; return }
        iv.setImageResource(res)
        iv.visibility = View.VISIBLE
    }

    /**
     * Map a Valhalla `TripDirections::Maneuver::Type` to a turn glyph. Grouped by the
     * documented Valhalla enum: 1=start, 4-6=destination, 8=continue, 9/10=slight
     * right/left, 11/15=right/left, 12/16=sharp right/left, 13=uturn-right,
     * 14=uturn-left, 17-21=ramp/exit/merge, 23/24=keep right/left, 26/27=roundabout
     * enter/exit, 25=fork. Returns 0 for "no glyph" (continue/start/unknown).
     */
    private fun maneuverIconRes(type: Int): Int = when (type) {
        4, 5, 6 -> R.drawable.ic_man_arrive
        9 -> R.drawable.ic_man_slight_right
        10 -> R.drawable.ic_man_slight_left
        11 -> R.drawable.ic_man_turn_right
        15 -> R.drawable.ic_man_turn_left
        12 -> R.drawable.ic_man_sharp_right
        16 -> R.drawable.ic_man_sharp_left
        13, 14 -> R.drawable.ic_man_uturn
        17, 18, 19 -> R.drawable.ic_man_ramp           // ramp straight/right/left
        20, 21 -> R.drawable.ic_man_ramp               // exit right/left
        22 -> R.drawable.ic_man_straight               // stay/continue on highway
        23 -> R.drawable.ic_man_slight_right           // keep right
        24 -> R.drawable.ic_man_slight_left            // keep left
        25 -> R.drawable.ic_man_fork
        26, 27 -> R.drawable.ic_man_roundabout
        37, 38 -> R.drawable.ic_man_merge              // merge right/left
        else -> 0                                       // 0/1/7/8: none/start/becomes/continue
    }

    // Honours the user's Trips km/miles preference (shared formatter).
    private fun formatDistance(m: Double): String =
        com.overdrive.app.navmap.nav.MapNetworking.formatDistance(m)

    private fun formatEta(seconds: Double): String {
        val mins = (seconds / 60.0).toInt()
        val hour = getString(R.string.unit_hour_compact)
        val unit = getString(R.string.unit_minute)
        return if (mins >= 60) "${mins / 60} $hour ${mins % 60} $unit" else "$mins $unit"
    }

    // ---------------------------------------------------------------------
    // Recenter
    // ---------------------------------------------------------------------

    /**
     * Recenter affordance — eases the camera to the live GPS fix from the
     * daemon ([RoadSenseHazardApiClient.fetchCurrentLocation], GET /api/gps,
     * which auto-starts tracking). Runs the fetch off the looper; on success
     * animates to the fix, drops/updates the location puck, and schedules an
     * offline prefetch around it. Falls back to the default region only when
     * no fix is available yet (toast so the tap isn't silent). A button press
     * is the one place a brief default-region ease is acceptable.
     */
    private fun recenter() {
        ioExecutor().execute {
            val fix = RoadSenseHazardApiClient.fetchCurrentLocation()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (fix != null) {
                    lastFix = fix
                    // Center on the live fix at street zoom with a gentle north-up
                    // RESTING TILT so the idle map reads as a modern 3D perspective
                    // (buildings/road casings gain depth) instead of a flat top-down
                    // sheet. North-up (bearing 0) keeps it a calm "where am I" view —
                    // heading-up + steep tilt is reserved for active guidance. The tilt
                    // is suppressed on the non-touch cluster (its follow loop owns the
                    // camera) and while navigating (guidance owns it).
                    val pos = org.maplibre.android.camera.CameraPosition.Builder()
                        .target(LatLng(fix.lat, fix.lng))
                        .zoom(FOLLOW_ZOOM)
                        .tilt(restingTilt())
                        .bearing(0.0)
                        .build()
                    map?.animateCamera(
                        CameraUpdateFactory.newCameraPosition(pos), RECENTER_ANIM_MS
                    )
                    updateLocationPuck(fix)
                    MapTilePrefetcher.schedulePrefetch(
                        applicationContext, STYLE_URL, fix.lat, fix.lng, resources.displayMetrics.density
                    )
                } else {
                    showSnackbar(getString(R.string.roadsense_map_no_location))
                }
            }
        }
    }

    /**
     * Draw/move the location puck — a soft accuracy halo (CircleLayer) with a
     * DIRECTIONAL arrow on top (SymbolLayer) that rotates to the travel heading,
     * Gmaps-style. The feature carries a `bearing` property driving iconRotate;
     * when no bearing is known (stationary / no fix heading) it falls back to the
     * last camera bearing so the arrow doesn't snap to north. Created lazily on
     * the first fix; kept above the hazard layers so it's never occluded.
     */
    private fun updateLocationPuck(fix: RoadSenseHazardApiClient.LatLngFix) {
        val bearing = fix.bearing?.takeIf { fix.bestSpeedMps > IMMERSIVE_MIN_SPEED_MPS } ?: lastBearing
        updateLocationPuckAt(fix.lat, fix.lng, bearing)
    }

    /** Place the location puck at an explicit (lat,lng) + heading. Used by the
     *  dead-reckoning render frame (interpolated pose) and the fix-based path. */
    private fun updateLocationPuckAt(lat: Double, lng: Double, bearing: Double) {
        val style = map?.style ?: return
        // Skip while a style is mid-load (theme reload): the ~30fps render tick is a
        // SEPARATE caller from onStyleLoaded, so during the setStyle swap map.style points
        // at a not-fully-loaded style — getSourceAs returns null → the addSource/addLayer
        // path below would hit MapLibre's validateState ("newer style is loading") and
        // crash. The reload's onStyleLoaded re-registers the puck right after, and the
        // next render tick repaints it, so skipping this frame is invisible.
        if (!style.isFullyLoaded) return
        // Hot path: the puck source is rewritten EVERY render frame (~30fps from
        // renderMotionFrame). Push a TYPED Feature, not a JSON string. setGeoJson(String)
        // routes to nativeSetGeoJsonString, which runs a full JSON PARSER on the native
        // side every frame — and we pay a Kotlin string-concat heap alloc to build it
        // first (serialize → JNI → re-parse). setGeoJson(Feature) routes to
        // nativeSetFeature: the geometry crosses JNI as an object with no parse step.
        // The SymbolLayer's iconRotate(Expression.get("bearing")) reads the numeric
        // "bearing" property identically, so the rendered arrow is unchanged — this is
        // a pure cost cut on the per-frame path (less main-thread work + GC churn on
        // the Adreno 610 that also drives the daemon encoder).
        val existing = style.getSourceAs<GeoJsonSource>(PUCK_SOURCE_ID)
        if (existing != null) {
            // Dead-band the per-FRAME source rewrite. setGeoJson dirties the source and
            // forces MapLibre's GL RenderThread to re-render the frame; the render tick
            // calls this ~30fps, so a STATIONARY car (dead-reckon estimate holds, pose
            // unchanged) was re-rendering an identical scene every frame — measured as
            // ~80% of a core on the GL RenderThread, doubled by the cluster mirror's
            // second map instance. Skip the rewrite when the pose hasn't meaningfully
            // moved/turned: while driving each frame steps far beyond the gate, so this
            // costs nothing in motion; it only freezes a parked map.
            if (!puckPaintedLat.isNaN()) {
                val moved = guidance.haversineMeters(puckPaintedLat, puckPaintedLng, lat, lng)
                var dBearing = kotlin.math.abs(bearing - puckPaintedBearing) % 360.0
                if (dBearing > 180.0) dBearing = 360.0 - dBearing
                if (moved < PUCK_DEADBAND_M && dBearing < PUCK_DEADBAND_DEG) return
            }
            existing.setGeoJson(buildPuckFeature(lat, lng, bearing))
            puckPaintedLat = lat; puckPaintedLng = lng; puckPaintedBearing = bearing
            return
        }
        val feature = buildPuckFeature(lat, lng, bearing)
        puckPaintedLat = lat; puckPaintedLng = lng; puckPaintedBearing = bearing
        // Theme-aware accent so the puck reads on the dark recolored basemap too. A
        // theme switch wipes the registered arrow image + source (style reload), so
        // this re-registers with the fresh themed color on the next paint.
        val accent = m3("primary")
        // Register the directional arrow bitmap once.
        if (style.getImage(ICON_PUCK_ARROW) == null) {
            style.addImage(ICON_PUCK_ARROW, buildDirectionArrow(accent))
        }
        style.addSource(GeoJsonSource(PUCK_SOURCE_ID, feature))
        // Soft accuracy halo underneath.
        style.addLayer(
            CircleLayer(PUCK_HALO_LAYER_ID, PUCK_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(18f),
                PropertyFactory.circleColor(accent),
                PropertyFactory.circleOpacity(0.18f)
            )
        )
        // Directional arrow, rotated to the heading property (rotation aligned to
        // the map so it points the travel direction regardless of map bearing).
        style.addLayer(
            SymbolLayer(PUCK_CORE_LAYER_ID, PUCK_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(ICON_PUCK_ARROW),
                PropertyFactory.iconSize(0.85f),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        )
    }

    /** Build the puck Feature: a Point at (lng,lat) carrying the numeric "bearing"
     *  property that drives iconRotate. Typed (no JSON string) so the per-frame
     *  setGeoJson(Feature) skips native JSON parsing. */
    private fun buildPuckFeature(lat: Double, lng: Double, bearing: Double): Feature =
        Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
            addNumberProperty("bearing", bearing)
        }

    /**
     * Build a SOTA 3D-style navigation arrow puck (Gmaps/Waze look): a chevron
     * with a vertical light→dark gradient (lit front edge, shaded tail) for a
     * dimensional feel + a crisp white outline so it reads on any basemap. Points
     * "up" (north) at rotation 0 so iconRotate(bearing) aims it at the travel
     * heading. NO blur drop-shadow: BlurMaskFilter renders as a hard dark box on
     * this head unit (same reason the marker pins dropped theirs) — the white
     * outline provides the lift/separation instead.
     */
    private fun buildDirectionArrow(accent: Int): Bitmap {
        val s = (PIN_PX * 0.78f).toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = s / 2f
        val cy = s / 2f
        val r = s * 0.40f

        // Chevron geometry: a sharp arrowhead with a concave tail notch.
        fun arrowPath(scale: Float, dy: Float) = android.graphics.Path().apply {
            moveTo(cx, cy - r * 0.78f * scale + dy)            // tip
            lineTo(cx + r * 0.62f * scale, cy + r * 0.66f * scale + dy) // back-right
            lineTo(cx, cy + r * 0.30f * scale + dy)            // tail notch
            lineTo(cx - r * 0.62f * scale, cy + r * 0.66f * scale + dy) // back-left
            close()
        }

        // No blur shadow: it renders as a hard dark box on this HW (bad on light
        // theme). The white outline (step 2) gives the lift/separation instead.

        // 2) White outline (slightly larger arrow drawn behind the fill).
        val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.FILL
        }
        c.drawPath(arrowPath(1.18f, 0f), outline)

        // 3) Gradient-filled body: a lighter tint at the tip → the accent at the
        //    tail, giving the chevron a lit, dimensional 3D appearance.
        val lit = lightenColor(accent, 0.35f)
        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            shader = android.graphics.LinearGradient(
                cx, cy - r * 0.78f, cx, cy + r * 0.66f,
                lit, accent, android.graphics.Shader.TileMode.CLAMP
            )
        }
        c.drawPath(arrowPath(1.0f, 0f), body)
        return bmp
    }

    /** Blend [color] toward white by [amount] (0..1). */
    private fun lightenColor(color: Int, amount: Float): Int {
        val a = android.graphics.Color.alpha(color)
        val r = (android.graphics.Color.red(color) + (255 - android.graphics.Color.red(color)) * amount).toInt()
        val g = (android.graphics.Color.green(color) + (255 - android.graphics.Color.green(color)) * amount).toInt()
        val b = (android.graphics.Color.blue(color) + (255 - android.graphics.Color.blue(color)) * amount).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    // ---------------------------------------------------------------------
    // Label / icon resolution helpers
    // ---------------------------------------------------------------------

    private fun iconForType(type: Int): Int = when (type) {
        0 -> R.drawable.ic_hazard_breaker
        1 -> R.drawable.ic_hazard_pothole
        3 -> R.drawable.ic_hazard_rough
        else -> R.drawable.ic_hazard_unknown
    }

    private fun typeLabelRes(type: Int): Int = when (type) {
        0 -> R.string.roadsense_type_breaker
        1 -> R.string.roadsense_type_pothole
        3 -> R.string.roadsense_type_rough
        else -> R.string.roadsense_map_type_unknown
    }

    private fun severityLabelRes(severity: Int): Int = when (severity) {
        1 -> R.string.roadsense_sev_minor
        3 -> R.string.roadsense_sev_severe
        else -> R.string.roadsense_sev_moderate
    }

    private fun statusLabelRes(status: Int): Int = when (status) {
        1 -> R.string.roadsense_map_status_local
        2 -> R.string.roadsense_map_status_cloud
        else -> R.string.roadsense_map_status_candidate
    }

    private fun severityFromChip(checkedId: Int): Int? = when (checkedId) {
        R.id.chipSevMinor -> 1
        R.id.chipSevModerate -> 2
        R.id.chipSevSevere -> 3
        else -> null
    }

    private fun typeFromChip(checkedId: Int): Int? = when (checkedId) {
        R.id.chipTypeBreaker -> 0
        R.id.chipTypePothole -> 1
        R.id.chipTypeRough -> 3
        R.id.chipTypeUnknown -> 2
        else -> null
    }

    /**
     * Lazily create the IO executor. Synchronized (+ @Volatile field) so a burst of
     * concurrent callers — e.g. a guidance tick racing recenter() on first open —
     * can't each build a separate executor and leak all but one (the old
     * elvis-`.also` getter had that unsynchronized double-init race). Double-checked
     * so the common already-initialized path stays lock-free.
     */
    private fun ioExecutor(): ExecutorService {
        ioExecutor?.let { return it }
        return synchronized(this) {
            ioExecutor ?: Executors.newSingleThreadExecutor { r ->
                Thread(r, "RoadSenseMapIo").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }.also { ioExecutor = it }
        }
    }

    // ---------------------------------------------------------------------
    // MapView lifecycle forwarding — every callback must reach the MapView,
    // or the render surface leaks / crashes. This is intentionally verbatim.
    // ---------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        // mapView is a lateinit assigned at the END of onCreate. onCreate has an
        // early return (cluster instance that landed on display 0 → finishAndRemoveTask)
        // that bails BEFORE that assignment, yet super.onCreate already ran so AMS
        // still drives the full lifecycle on the finishing instance. Touching the
        // uninitialized lateinit here threw UninitializedPropertyAccessException and
        // crashed the whole app — the intermittent "clicking Map crashes Overdrive".
        // Guard every forward with ::mapView.isInitialized.
        if (::mapView.isInitialized) mapView.onStart()
        // (C) Start the app-side gyro tap for crisp-turn heading fusion. Idempotent +
        // self-disabling when no real gyro — safe to call unconditionally. Paired with
        // stopGyroFusion() in onStop so a backgrounded map isn't holding the sensor.
        if (::mapView.isInitialized) startGyroFusion()
    }

    override fun onResume() {
        super.onResume()
        if (!::mapView.isInitialized) return
        mapView.onResume()
        // RESUME-SNAP: while backgrounded the render tick was suspended, so the estimator's
        // filtered state + the painted puck (renderedPuckLat) are frozen at the position
        // from when we left. On return, resuming the loops would EMA-glide the estimator
        // and ramp the painted puck (PUCK_MAX_CATCHUP_MPS clamp) from that stale spot toward
        // the live fix — the "puck starts where I backgrounded and slides to where I am"
        // artifact. SOTA is to resume at the EXACT current position with no glide. After a
        // real away-period, hard-RESET the estimator (next truth fix SEEDS directly, no EMA
        // blend) and clear the painted anchor (renderMotionFrame's NaN branch seeds the puck
        // directly). The fresh direct-GPS subscription / daemon poll started below delivers
        // that current fix within ~1s. A momentary flip (< the threshold) skips the reset so
        // a quick notification-shade peek doesn't re-seed needlessly.
        val awayMs = if (backgroundedAtMono > 0L)
            android.os.SystemClock.elapsedRealtime() - backgroundedAtMono else 0L
        backgroundedAtMono = 0L
        if (awayMs >= RESUME_SNAP_AFTER_MS) {
            motionEstimator.reset()        // next onTruthPoint seeds directly (no glide)
            renderedPuckLat = Double.NaN   // paint re-seeds at the target (no catchup ramp)
            renderedPuckLng = Double.NaN
            lastFixCaptureMonoMs = 0L       // force a fresh truth anchor on the next fix
            lastFedFixCaptureMonoMs = 0L
            lastFedFixTsMs = 0L
            // Also clear the daemon-fallback gate timestamps. Otherwise, if the in-process
            // getLastKnownLocation seed comes back null on resume, these stay ~awayMs old
            // and can still read < DIRECT_FIX_FALLBACK_MS for the first ~1s — gating the
            // daemon poll OFF while the estimator is empty (reset) → a ~1s blank puck until
            // the live 1 Hz listener seeds. Zeroing them makes (now − last) huge → the gate
            // opens → the first daemon poll feeds/SEEDS the reset estimator immediately (the
            // daemon feed on a null estimator takes the SEED path, so no snap-back); a real
            // direct fix then re-pins both and the gate re-engages normally.
            lastDirectFixSeenMonoMs = 0L
            lastAcceptedFixMonoMs = 0L
        }
        // Subscribe to the platform location provider directly (low-latency truth feed;
        // see startDirectGps). Idempotent + permission-safe; the daemon poll below stays
        // as the cold-start seed + fallback until this warms up. getLastKnownLocation seeds
        // it immediately so the post-reset puck appears at the current position promptly.
        startDirectGps()
        // Resume the camera/guidance loops suspended in onStop (state was kept).
        if (navigating) {
            mainHandler.removeCallbacks(guidanceRunnable); mainHandler.post(guidanceRunnable)
            startMotionFollow()   // resume the dead-reckoning render tick
        } else if (clusterMode) {
            startClusterFollow()   // idempotent (self-dedupes)
        } else {
            // Head-unit, NOT navigating (idle browsing). Previously NOTHING ran here,
            // so the puck stayed frozen at the pre-background lastFix until the user
            // tapped Locate — while the cluster (which has its own idle follow) stayed
            // live. Mirror that here: start the head-unit idle follow so the puck
            // refreshes on resume and keeps tracking. PUCK-ONLY (never moves the camera)
            // so a user who panned away keeps their view.
            startIdleFollow()
        }
        // CLUSTER black-projection self-heal — runs on EVERY cluster resume, OUTSIDE the
        // navigating/idle branch split. A cluster instance can be navigating (the mirror
        // sets navigating=true from NavSession), and that branch above starts the motion
        // render tick but NOT the keep-warm ticker (removed in onStop) nor a one-shot
        // repaint — so a navigating cluster resumed while STATIONARY on a silently-
        // recreated WHEN_DIRTY surface would stay BLACK. Arm the keep-warm ticker + force
        // one repaint here so the heal covers BOTH navigating and idle cluster resumes.
        // (forceClusterRepaint/startClusterKeepWarm are no-ops / self-deduped off-cluster.)
        if (clusterMode) {
            startClusterKeepWarm()
            forceClusterRepaint()
        }
        // Head unit: resume watching the cluster-projection flag (suspended in onStop).
        if (!clusterMode) startHeadUnitProjectionPoll()
    }

    override fun onPause() {
        if (::mapView.isInitialized) mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Suspend the 1s guidance + cluster-follow loops while backgrounded — the
        // SurfaceView render is paused here, so continuing to fire network GETs +
        // animateCamera every second just drains the SoC / contends with the
        // daemon encoder for nothing. The `navigating`/`clusterMode` flags are KEPT
        // so onResume restarts seamlessly.
        mainHandler.removeCallbacks(guidanceRunnable)
        mainHandler.removeCallbacks(clusterFollowRunnable)
        mainHandler.removeCallbacks(clusterKeepWarmRunnable)   // suspend the keep-warm repaint nudge
        mainHandler.removeCallbacks(idleFollowRunnable)
        stopHeadUnitProjectionPoll()   // suspend the cluster-projection watch while backgrounded
        projectionActive = false       // clear so onResume re-derives it (the poll's first
                                       // read is 1s out; don't carry a stale-true → avoid
                                       // ~1s of throttled glide after a resume)
        stopMotionFollow()   // pause the dead-reckoning render tick while backgrounded
        stopGyroFusion()     // release the gyro + its thread while backgrounded
        stopDirectGps()      // release the platform location subscription + its thread
        backgroundedAtMono = android.os.SystemClock.elapsedRealtime()   // for the resume-snap
        if (::mapView.isInitialized) mapView.onStop()
        super.onStop()
    }

    /** elapsedRealtime (ms) the map was backgrounded (onStop), so onResume can tell a
     *  brief flip from a real away-period and SNAP the puck to the current position
     *  instead of gliding it from the stale pre-background spot. 0 = never backgrounded. */
    @Volatile private var backgroundedAtMono: Long = 0L

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
    }

    /** Animate the camera zoom by [delta] levels (+in / -out), clamped by the map. */
    private fun zoomBy(delta: Double) {
        val mlMap = map ?: return
        val target = mlMap.cameraPosition.zoom + delta
        mlMap.animateCamera(CameraUpdateFactory.zoomTo(target), 300)
    }

    private fun hideKeyboard(v: View) {
        try {
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        navigating = false
        mainHandler.removeCallbacksAndMessages(null)
        stopGyroFusion()   // defensive: release the gyro thread if onStop was skipped
        stopDirectGps()    // defensive: release the location subscription + thread
        clusterDisplayListener?.let { l ->
            try {
                (getSystemService(android.content.Context.DISPLAY_SERVICE)
                    as? android.hardware.display.DisplayManager)?.unregisterDisplayListener(l)
            } catch (_: Throwable) {}
        }
        clusterDisplayListener = null
        clusterThemeListener?.let { l ->
            try {
                getSharedPreferences(PREFS_NAVMAP, MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(l)
            } catch (_: Throwable) {}
        }
        clusterThemeListener = null
        // Dismiss any open place-action sheet so its Activity-parented window doesn't
        // leak if the Activity is finished out from under a showing sheet (e.g. the
        // cluster-finish poll's finishAndRemoveTask). Null the listener first so the
        // dismiss doesn't run dropped-pin cleanup against a torn-down map.
        openPlaceSheetAlive?.set(false)   // drop any in-flight reverse callback
        openPlaceSheet?.let { try { it.setOnDismissListener(null); it.dismiss() } catch (_: Throwable) {} }
        openPlaceSheet = null
        openPlaceSheetAlive = null
        // The head-unit map is the nav CONTROL surface; the cluster mirror only
        // observes NavSession. If the user backs out of the head-unit map mid-trip
        // (back FAB / toolbar chevron → finish, NOT the End-nav button → stopGuidance),
        // stopGuidance never runs, so NavSession is never cleared and the cluster
        // mirror keeps navigating with a STUCK turn-by-turn banner + a live tick.
        // Clear it here so the cluster ends too. Guarded !clusterMode: the cluster
        // instance is a pure consumer and must never publish/clear (and clear() is a
        // no-op when already idle, so this is safe even when not navigating).
        if (!clusterMode) NavSession.clear()
        NavSession.removeListener(navSessionListener)
        navSessionListener = null
        try { navVoice?.shutdown() } catch (_: Throwable) {}
        navVoice = null
        ioExecutor?.shutdownNow()
        ioExecutor = null
        MapTilePrefetcher.cancelPending()
        hazardSource = null
        routeSource = null
        routeMainLayer = null
        routeCasingLayer = null
        // Null symmetry: drop the remaining style-bound source/layer + sheet refs
        // so they don't pin a destroyed Style/MapView (no-op safety; map is gone).
        altRouteSource = null
        poiSource = null
        hazardSymbolLayer = null
        routeSheetBehavior = null
        map = null
        // Forward last so MapLibre releases its GL resources after we've
        // dropped our references. Guarded: the display-0 early return finishes
        // before mapView is assigned (see onStart note).
        if (::mapView.isInitialized) mapView.onDestroy()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "RoadSenseMapActivity"

        /** Cluster-only self-finish poll cadence (ms). The daemon sets
         *  navMap.clusterMapActive=false on stop/disable/ACC-off; ~500ms matches the
         *  DeterrentActivity deadline poll and dismisses the parked map well before a
         *  turn signal could re-surface it. */
        private const val CLUSTER_FINISH_POLL_MS = 500L

        /**
         * OpenFreeMap hosted styles — always-current, no API key, no limits. The
         * map picks light vs dark by the active app theme (styleUrlForTheme).
         * "liberty" is the richer, more detailed light basemap (more premium than
         * the minimal "positron"); "dark" is its night counterpart. STYLE_URL
         * (light) is the prefetch reference (offline cache is theme-agnostic —
         * tiles are shared across styles; only the style JSON differs).
         */
        const val STYLE_URL_LIGHT = "https://tiles.openfreemap.org/styles/liberty"
        const val STYLE_URL_DARK = "https://tiles.openfreemap.org/styles/dark"
        const val STYLE_URL = STYLE_URL_LIGHT

        /**
         * BUNDLED on-device style assets (preferred over the hosted URLs above). These
         * are the SAME OpenFreeMap basemap recipe shipped in the APK so the map opens
         * instantly without a style-document fetch, survives a flaky link, and is ours
         * to tune. The tiles/sprite/glyphs referenced inside are still streamed from
         * OpenFreeMap. Loaded via Style.Builder().fromJson(asset) in styleBuilderForTheme();
         * the hosted URL remains the fallback if an asset can't be read.
         */
        const val STYLE_ASSET_LIGHT = "maps/liberty_style.json"
        const val STYLE_ASSET_DARK = "maps/dark_style.json"

        // Source / layer ids.
        const val HAZARD_SOURCE_ID = "roadsense-hazards"
        const val HAZARD_SYMBOL_LAYER_ID = "roadsense-hazard-markers"
        const val CLUSTER_CIRCLE_LAYER_ID = "roadsense-hazard-clusters"
        const val CLUSTER_COUNT_LAYER_ID = "roadsense-hazard-cluster-count"

        // Marker icon ids registered via style.addImage.
        const val ICON_BREAKER = "hz_breaker"
        const val ICON_POTHOLE = "hz_pothole"
        const val ICON_ROUGH = "hz_rough"
        const val ICON_UNKNOWN = "hz_unknown"

        // GeoJSON property keys (match RoadSenseApiHandler output).
        const val PROP_ID = "id"
        const val PROP_TYPE = "type"
        const val PROP_SEVERITY = "severity"
        const val PROP_CONFIDENCE = "confidence"
        const val PROP_STATUS = "status"
        const val PROP_OBSERVATIONS = "observations"
        const val POINT_COUNT = "point_count"

        // Clustering.
        const val CLUSTER_RADIUS = 50
        const val CLUSTER_MAX_ZOOM = 14

        // Marker bitmap resolution (px) for style.addImage.
        // Hazard pin bitmap width (px); height is 1.3x. Large for hi-dpi crispness.
        const val PIN_PX = 96

        const val REFETCH_DEBOUNCE_MS = 400L
        const val RECENTER_ANIM_MS = 900

        // Initial camera until a recenter / live fix narrows it.
        const val DEFAULT_LAT = 1.3521
        const val DEFAULT_LNG = 103.8198
        const val DEFAULT_ZOOM = 13.0

        // Zoom used when centering on the live GPS fix (closer than the
        // overview default so the user sees their immediate surroundings).
        const val FOLLOW_ZOOM = 16.0

        // Location puck source + layers (kept above the hazard layers).
        const val PUCK_SOURCE_ID = "roadsense-location"
        const val ICON_PUCK_ARROW = "puck_arrow"
        const val PUCK_HALO_LAYER_ID = "roadsense-location-halo"
        const val PUCK_CORE_LAYER_ID = "roadsense-location-core"

        // Route line source + two-layer stroke (added UNDER the hazard markers).
        const val ROUTE_SOURCE_ID = "roadsense-route"
        const val ROUTE_CASING_LAYER_ID = "roadsense-route-casing"
        const val ROUTE_MAIN_LAYER_ID = "roadsense-route-main"

        // Alternate routes (dimmed, tappable) — separate source under the selected route.
        const val ALT_ROUTE_SOURCE_ID = "roadsense-alt-routes"
        const val ALT_ROUTE_LAYER_ID = "roadsense-alt-routes-line"

        // Itinerary markers: a destination pin at the route end + numbered pins at
        // each intermediate stop. Drawn ABOVE the route line + hazards so the trip
        // endpoints are always legible. Each feature carries an "img" property =
        // the registered image id (rs_dest / rs_stop_<n>) selected via iconImage(get).
        const val MARKER_SOURCE_ID = "roadsense-route-markers"
        const val MARKER_LAYER_ID = "roadsense-route-markers-layer"
        const val MARKER_PROP_IMG = "img"
        const val ICON_DEST = "rs_dest"
        const val ICON_STOP_PREFIX = "rs_stop_"

        // Dropped-pin marker (long-press the map to save/navigate a place).
        const val DROPPED_PIN_SOURCE_ID = "roadsense-dropped-pin"
        const val DROPPED_PIN_LAYER_ID = "roadsense-dropped-pin-layer"
        const val ICON_DROPPED_PIN = "rs_dropped_pin"

        // How many ALTERNATE routes to request (Valhalla may return fewer).
        const val ROUTE_ALTERNATES = 2

        // Max INTERMEDIATE stops (via-points) the itinerary allows; the trip then
        // holds up to MAX_STOPS stops + 1 destination. Capped so the via route
        // request + the rebuilt LinearLayout stay small.
        const val MAX_STOPS = 8

        // Corridor (m) within which a hazard counts as "on this route".
        const val HAZARD_CORRIDOR_M = 40.0

        // Degree padding for the per-route bbox pre-reject in loadHazardCountsForRoutes.
        // A strict superset of HAZARD_CORRIDOR_M (~40m): 1° lat ≈ 111.3km so 40m ≈
        // 0.00036°; lng degrees are SHORTER in meters away from the equator, so the
        // same 0.0004° always covers ≥40m on the lng axis too — guaranteeing any
        // in-corridor hazard survives the bbox, so counts are unchanged.
        const val HAZARD_CORRIDOR_DEG = 0.0004

        // Max hazards parsed for the per-route corridor scan — caps the O(n×segments)
        // work on a pathologically dense bbox (the pills only need a representative count).
        const val HAZARD_COUNT_CAP = 2000

        // Tap tolerance (dp) for hitting a thin route line with a finger.
        const val TAP_SLOP_PX = 22f

        // Max width (dp) of the floating top panels on wide/landscape screens —
        // beyond this they're capped + centered (Gmaps-style column), not stretched.
        const val PANEL_MAX_WIDTH_DP = 560

        // Autocomplete debounce + minimum query length (don't spam the geocoder).
        const val AUTOCOMPLETE_DEBOUNCE_MS = 300L
        const val AUTOCOMPLETE_MIN_CHARS = 3
        /** Max local (saved + recent) rows surfaced above remote autocomplete hits. */
        const val LOCAL_MATCH_CAP = 4

        // Guidance loop cadence + camera/announce thresholds. The camera
        // animation duration is matched to the tick interval so each glide ends
        // right as the next fix arrives — continuous motion, no animate-then-freeze
        // stutter (the old 2000ms tick / 900ms anim left the puck frozen ~1.1s).
        const val GUIDANCE_TICK_MS = 1000L
        /** Window after which the daemon poll resumes feeding the puck if the DIRECT
         *  in-process GPS source hasn't (a) delivered a fix OR (b) had a fix accepted —
         *  the two-clause source-preference gate in feedMotionTruth.
         *
         *  This NO LONGER has to beat the dead-reckon cap — the render-tick freeze-guard
         *  (reanchorToLastFix, fired at KEEP_WARM_BEFORE_CAP_MS) guarantees the puck can't
         *  freeze at the cap independently of this value or the 1 Hz grid. So this is now a
         *  pure source-preference knob: 2500 ms ≈ 2× the ~1 s direct-fix interval, so a
         *  SINGLE dropped direct fix (≈2 s gap) does NOT hand control back to the coarser
         *  daemon feed (avoiding the per-dropped-fix snap-back a 1500 ms value caused),
         *  while a genuinely-dead direct source (no permission / provider off, >2.5 s quiet)
         *  still lets the daemon poll resume as the fallback truth feed. */
        const val DIRECT_FIX_FALLBACK_MS = 2500L
        /** Render-tick freeze-guard threshold (ms): when the estimator baseline is this old
         *  and no truth fix has refreshed it, reanchorToLastFix() holds the puck at the last
         *  real position. MUST be < MAX_DEAD_RECKON_S (3000 ms) by a comfortable margin so a
         *  re-anchor always lands before the dead-reckon caps. Sub-second render cadence
         *  (RENDER_TICK_MS=33 / IDLE_RENDER_TICK_MS=200) makes this grid-independent — no
         *  1 Hz quantization. 2200 ms leaves ~800 ms headroom below the cap. */
        const val KEEP_WARM_BEFORE_CAP_MS = 2200L
        /** Accuracy (m) the freeze-guard re-anchor feeds the estimator — clamped to the
         *  estimator's GOOD bar (18m) so the re-anchor is ALWAYS accepted (never hits the
         *  55m reject), even when the last real fix was coarse. We hold the last real
         *  position, so claiming a tight accuracy is honest. */
        const val REANCHOR_ACCURACY_M = 18.0
        /** Min background duration (ms) before onResume hard-snaps the puck to the current
         *  position (estimator reset + paint re-seed, no glide). Below it, a momentary flip
         *  (notification shade, quick app switch) keeps the smooth state so it doesn't
         *  needlessly re-seed. ~1.2s ≈ just over one fix interval — long enough that the
         *  pre-background position is genuinely stale, short enough to catch any real
         *  away-and-back. */
        const val RESUME_SNAP_AFTER_MS = 1200L
        const val GUIDANCE_CAM_ANIM_MS = 1000
        const val MANEUVER_ANNOUNCE_M = 180.0

        // Cluster maneuver-banner text sizes (sp) — larger than the head-unit's
        // TitleLarge/BodyMedium for glanceability on the driver cluster.
        // Cluster maneuver-banner text sizes (sp). Kept glanceable but NOT oversized:
        // the cluster panel is short (720px) AND runs at density 320, so sp renders
        // ~1.33× larger than the same number on the 240-dpi head unit — the old 30/18
        // produced a card that wrapped to 2-3 lines and covered most of the map. 22/14
        // matches the head-unit TitleLarge/BodyMedium numerically (still a touch larger
        // physically from the density), so it reads at a glance without dominating.
        const val CLUSTER_BANNER_PRIMARY_SP = 18f
        const val CLUSTER_BANNER_SECONDARY_SP = 12f
        // Maneuver glyph side (dp) on the cluster — smaller than the 40dp layout
        // default so the icon matches the tighter text and doesn't drive card height.
        const val CLUSTER_BANNER_ICON_DP = 22
        // Compact-card geometry on the cluster: a small fixed-width strip (NOT
        // match_parent) pinned top-start, with tight padding + a smaller corner
        // radius, so the maneuver card occupies a slim corner and leaves the road
        // map visible. dp values, scaled by display density in applyClusterChrome.
        const val CLUSTER_BANNER_WIDTH_DP = 280
        const val CLUSTER_BANNER_PAD_H_DP = 12
        const val CLUSTER_BANNER_PAD_V_DP = 8
        const val CLUSTER_BANNER_RADIUS_DP = 16
        const val CLUSTER_BANNER_ICON_MARGIN_DP = 10
        // (The old CLUSTER_OVERSCAN_X/Y_DP top-start insets are gone: the banner now anchors
        // to the speed-badge CENTRE, which sits well inside the panel — far clear of the
        // (80,50)px overscan band — so no separate overscan margin is needed.)
        // Traveled-route trim (line-gradient). TRIM_FEATHER = the line-progress width of
        // the antialiased fade at the trim boundary (~1.2% of the route). TRIM_PROGRESS_EPS
        // = the paint dead-band: skip a gradient repaint until progress advances this much
        // (~0.2% of the route ≈ a few metres), so a parked/creeping car emits zero writes.
        const val TRIM_FEATHER = 0.012f
        const val TRIM_PROGRESS_EPS = 0.002f
        // The speed badge (ClusterSpeedOverlay, a daemon SurfaceControl layer at z=MAX so it
        // always composites OVER this map) is a short strip on the LEFT whose BOTTOM sits just
        // above the panel vertical centre. The TBT banner stacks directly below it, sharing
        // this left edge and pinning its TOP at panelH/2 (see applyClusterChrome). Only the
        // left edge needs to match the daemon; the seam is panelH/2 on both sides, and the
        // badge's own height/aspect are private to ClusterSpeedOverlay now.
        const val CLUSTER_SPEED_BADGE_LEFT_PX = 56    // mirrors ClusterSpeedOverlay.LEFT_MARGIN_PX

        // Immersive driving view: 3D tilt, close zoom, heading-up follow.
        const val IMMERSIVE_TILT = 55.0

        // Resting (idle/browse) camera pitch — a gentle perspective that gives the
        // static map depth (3D buildings + road casings) at street zoom, WITHOUT the
        // steep heading-up framing of active guidance. Deliberately NOT an independent
        // magic number: it is DERIVED as a fraction of our own [IMMERSIVE_TILT] so the
        // resting view always reads as a calmer, shallower cousin of the driving
        // framing and the two stay in proportion if either is ever retuned. ~0.62× of
        // the driving pitch → a shallow lean. Applied by recenter() at FOLLOW_ZOOM;
        // suppressed on the cluster + during nav.
        const val RESTING_TILT_FRACTION = 0.62
        const val RESTING_TILT = IMMERSIVE_TILT * RESTING_TILT_FRACTION
        const val IMMERSIVE_ZOOM = 17.5
        const val IMMERSIVE_MIN_SPEED_MPS = 1.5 // below this, GPS bearing is noise → hold last

        // Render micro-tick: dead-reckon the puck+camera between the GPS truth fixes
        // for continuous (non-teleporting) motion. 30fps is the smoothness floor the
        // eye reads as "fluid" for a moving map (12fps visibly stepped); each frame
        // is a cheap moveCamera on an already-interpolated pose. Kept at 30 (not a
        // 60fps Choreographer loop) so it stays light on the Adreno 610 which also
        // runs the daemon encoder.
        const val RENDER_TICK_MS = 33L

        // Cluster fast-tick: the cluster's GL is capped at CLUSTER_MAX_FPS (15), so
        // dead-reckoning the pose at the head unit's 30fps would just compute frames the
        // FPS cap discards. Match the compute rate to the cap (≈67ms = 15fps) — same
        // glance-smooth motion, half the per-frame pose math on the shared SoC.
        const val CLUSTER_RENDER_TICK_MS = 67L
        /** Cluster keep-warm repaint interval (ms): bounds how long a silently-recreated
         *  WHEN_DIRTY surface can stay black before a triggerRepaint() repaints it. ~3s is
         *  a negligible GPU cost (one frame/3s) vs the parked-car black-out risk. */
        const val CLUSTER_KEEP_WARM_MS = 3000L

        // Idle render tick: when the car is parked (no motion to interpolate) drop the
        // dead-reckon tick from 30fps to 5fps. At 30fps the tick + per-frame puck
        // source rewrite kept MapLibre's GL RenderThread pegged on a STATIONARY car
        // (measured ~80% of a core, doubled by the cluster mirror's second map). The
        // puck/camera dead-bands already elide the actual GL work when parked, so the
        // residual cost is just waking the main thread — 5fps keeps the map responsive
        // to the next fix while cutting that wakeup rate 6×. Snaps back to 30fps within
        // one idle tick (≤200 ms) the instant the car starts moving.
        const val IDLE_RENDER_TICK_MS = 200L

        // Per-instance GL render-rate cap (MapView.setMaximumFps), applied ON TOP of
        // WHEN_DIRTY mode. WHEN_DIRTY already drops a parked/idle map to ~0 frames; this
        // bounds the MOVING-map rate. The head unit is the user-facing, touch-interactive
        // surface — keep it at the 30fps smoothness floor. The cluster is a glanceable,
        // non-touch driver panel mirroring the same drive — it doesn't need the full rate
        // and shares the Adreno 610 with the recording encoder, so cap it at 15fps: still
        // reads as smooth map-scroll at a glance (sub-12 starts looking stepped) but ~2×
        // less GPU draw than 30 — directly cutting the second instance's cost. The render
        // TICK matches this cap (CLUSTER_RENDER_TICK_MS) so we don't dead-reckon 30 poses
        // /s only to drop half at the FPS cap.
        const val HEADUNIT_MAX_FPS = 30
        const val CLUSTER_MAX_FPS = 15

        // Head-unit poll cadence for the daemon's clusterMapActive flag (dual-render
        // avoidance). 1s matches the GPS/guidance cadence — the head unit doesn't need
        // to learn about a projection start/stop faster than that, and the read is a
        // cross-UID UCM forceReload kept off the looper. Mirrors CLUSTER_FINISH_POLL_MS's
        // role on the cluster side.
        const val PROJECTION_POLL_MS = 1000L

        // Speed-adaptive zoom (km/h → zoom): open the view out at speed (see further
        // ahead on the highway), close in around town. EMA-smoothed across thresholds
        // so it doesn't step. Values bracket IMMERSIVE_ZOOM (the town default).
        const val ZOOM_SLOW = 17.8     // < ~25 km/h (crawl / dense urban)
        const val ZOOM_CITY = 17.2     // ~25-55 km/h
        const val ZOOM_ARTERIAL = 16.2 // ~55-90 km/h
        const val ZOOM_HIGHWAY = 15.2  // > ~90 km/h
        const val ZOOM_EMA_ALPHA = 0.08 // gentle — zoom should drift, never snap

        // Predictive camera look-ahead: lead the camera target this many SECONDS of
        // travel ahead of the puck (clamped), so the puck sits low + road-ahead fills
        // the frame. ~4s lead is the consumer-nav feel; clamps keep it sane at the
        // extremes (stop-and-go vs highway).
        const val LOOKAHEAD_SECONDS = 4.0
        const val LOOKAHEAD_MIN_M = 30.0
        const val LOOKAHEAD_MAX_M = 220.0
        const val REROUTE_MIN_INTERVAL_MS = 8000L // min gap between auto-reroutes

        // A via-stop within this distance of the car counts as "reached" — pruned
        // from an itinerary-aware reroute so the car isn't sent back to a stop it
        // just passed. Generous (covers head-unit GPS offset) but well under any
        // realistic inter-stop spacing.
        const val STOP_REACHED_M = 60.0

        // Near-stop arrival: when the along-route remainder is inside this band AND
        // the car has slowed to (near) a stop, declare arrival even if GPS parks us
        // just outside the strict ARRIVAL_RADIUS_M. This is the "be smart enough to
        // end nav near the destination instead of rerouting" behaviour — pairs with
        // NavGuidanceEngine.NEAR_DEST_NO_REROUTE_M which suppresses reroutes in the
        // same band so a slow final-approach wander can't trigger a recompute.
        const val NEAR_STOP_ARRIVAL_M = 60.0 // inside the "final 50-100 m" the user flagged
        const val NEAR_STOP_SPEED_MPS = 1.5  // ~5.4 km/h — genuinely stopped, not a slow crawl
        const val NEAR_STOP_ARRIVAL_TICKS = 2 // consecutive near-stop ticks (≈2s at 1Hz) to confirm

        // Camera follow dead-band: skip the per-FRAME moveCamera when the puck has
        // barely moved + barely turned since the last frame. With the 80 ms render
        // tick we want EVERY frame to glide while moving (at 50 km/h that's ~1.1 m
        // /frame), so the distance gate is sub-metre — its only job is to freeze a
        // stationary vehicle (the dead-reckon estimate already holds when parked, so
        // this is belt-and-suspenders). The old 3 m gate skipped ~3 frames at city
        // speed → the camera lurched in 3 m steps instead of gliding.
        const val CAM_DEADBAND_M = 0.6
        const val CAM_DEADBAND_DEG = 0.4

        // Puck SOURCE-rewrite dead-band: skip the per-frame setGeoJson on the puck
        // GeoJsonSource when the painted pose has barely moved/turned. setGeoJson
        // dirties the source → forces a GL re-render, so without this a parked car
        // re-rendered an identical puck every render tick (the dominant idle-CPU cost,
        // ×2 with the cluster mirror). Sub-metre / sub-degree so it NEVER skips a frame
        // while driving (each real step dwarfs it); its only job is to freeze a parked
        // map. Slightly below the camera dead-band so the puck can never lag the camera.
        const val PUCK_DEADBAND_M = 0.5
        const val PUCK_DEADBAND_DEG = 0.3

        // On-route snap hysteresis (m). The render frame snaps the puck to the route
        // line while the lateral offset is small and only releases it to the raw
        // position once clearly off-route — a single threshold would let a GPS offset
        // hovering near it flicker the puck on/off the line every frame. Enter when
        // within ENTER; once snapped, stay until beyond EXIT (the gap is the deadband).
        const val PUCK_SNAP_ENTER_M = 32.0
        const val PUCK_SNAP_EXIT_M = 48.0

        // Max speed (m/s) at which the PAINTED puck catches up to its target. Set well
        // above any real vehicle speed (~60 m/s ≈ 216 km/h) so normal driving NEVER
        // clips (the puck tracks the target with zero added lag); ONLY a discontinuity
        // — the snapped↔raw mode flip, up to ~PUCK_SNAP_EXIT_M in one frame — is spread
        // across a handful of frames so it glides instead of teleporting.
        const val PUCK_MAX_CATCHUP_MPS = 60.0

        // Heading-up bearing low-pass TIME CONSTANT (seconds). smoothBearing derives
        // its per-call alpha = 1 - exp(-dt/tau) from the REAL elapsed dt, so the feel
        // is identical whether it's called at ~30fps (render frame) or 1Hz (cluster
        // idle follow) — a fixed alpha would silently collapse the time-constant ~30x
        // at the faster cadence and make the camera whip through corners + pass GPS
        // jitter. 0.35s = the consumer-nav feel: smooth heading-up rotation that still
        // turns promptly into corners (0.6s felt laggy — the arrow/camera trailed the
        // actual turn by a beat; now that bearing is steered by the route TANGENT when
        // on-route, a shorter tau tracks the turn without re-exposing GPS jitter).
        const val BEARING_TAU_S = 0.35

        // POI (EV charging / fuel) along-route layer.
        const val POI_SOURCE_ID = "roadsense-poi"
        const val POI_LAYER_ID = "roadsense-poi-markers"
        const val ICON_POI_CHARGING = "poi_charging"
        const val ICON_POI_FUEL = "poi_fuel"
        const val POI_PROP_KIND = "kind"
        const val POI_PROP_NAME = "name"
        const val POI_PROP_LAT = "plat"
        const val POI_PROP_LNG = "plng"

        // Hazard filter modes (persisted in prefs).
        const val HAZARD_FILTER_HIDDEN = 0
        const val HAZARD_FILTER_SEVERE = 1   // severity == 3
        const val HAZARD_FILTER_MODERATE = 2 // severity >= 2
        const val HAZARD_FILTER_ALL = 3      // severity >= 1 (default)
        const val PREFS_NAVMAP = "navmap_ui"
        const val KEY_HAZARD_FILTER = "hazard_filter_mode"

        // 2D/3D buildings. The 3D layer is a runtime-added fill-extrusion over the
        // openmaptiles vector source (present in BOTH the light=liberty and the
        // dark style — only liberty ships the extrusion layer, so we add our own
        // in either case + toggle visibility). minzoom matches liberty's building-3d
        // so the volumes only appear when zoomed in (and never load tiles below it).
        const val MAP_3D_LAYER_ID = "roadsense-building-3d"
        // The liberty (light) basemap ships its OWN fill-extrusion layer with this
        // id — we toggle ITS visibility for 2D/3D rather than adding a duplicate.
        const val LIBERTY_3D_LAYER_ID = "building-3d"
        const val MAP_3D_SOURCE = "openmaptiles"
        const val MAP_3D_SOURCE_LAYER = "building"
        const val MAP_3D_MIN_ZOOM = 14f
        // Fixed extrusion hues (NOT theme attrs — these tint 3D building volumes on
        // the basemap, so they track the BASEMAP theme, not the app M3 palette).
        const val MAP_3D_COLOR_LIGHT = "hsl(35,8%,85%)" // liberty's own building tint
        const val MAP_3D_COLOR_DARK = "#2b2d36"          // muted slate for night
        const val KEY_MAP_3D = "map_3d_enabled" // persisted 2D/3D choice (default 2D)

        // Per-map day/night override (independent of the app theme). AUTO follows
        // the Overdrive app theme; LIGHT/DARK pin the basemap regardless.
        const val MAP_THEME_AUTO = 0
        const val MAP_THEME_LIGHT = 1
        const val MAP_THEME_DARK = 2
        const val KEY_MAP_THEME = "map_theme_mode" // persisted map theme override

        const val EMPTY_FEATURE_COLLECTION =
            "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}
